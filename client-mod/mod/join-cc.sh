#!/usr/bin/env bash
# join-cc.sh - the CC_Client joiner launcher for Linux (the twin of join-cc.bat on Windows).
# Run it from the Starsector folder (the one with starsector.sh):   bash mods/CC_Client/join-cc.sh
# The join window opens (address + port + match password the host gives you, your pilot name); Launch starts
# the game on the game's own Java (jre_linux) with the kit's compatibility agent, waits for the game to close,
# saves a session report (.tar.gz) to the Desktop, then offers R = rejoin with nothing re-entered.
#
# What this file guarantees, and why:
# - Every file and folder it creates is readable by this account only (umask 077 below; private folders 0700).
#   The game inherits that umask on purpose: it is what keeps the editor endpoint file the game writes under
#   saves/common private too.
# - The match password, the install id and a seat token never ride a command line or JAVA_TOOL_OPTIONS: each
#   launch writes them to a one-time 0600 handoff file in a 0700 folder, and only that file's random 16-hex
#   name rides to the game (-Dcc.kit.handoff).
# - The launch options ride JAVA_TOOL_OPTIONS / _JAVA_OPTIONS, set on the game's own command ONLY (never
#   exported, never added to an inherited value): a process's environment is readable by its own account only,
#   while its command line is readable by every account on the PC. Every helper Java (the join window, the
#   match refresh, the sound self-check, the report's java -version) runs with all three Java option variables
#   removed. A path inside those variables is wrapped in double quotes (Java splits them at whitespace and
#   groups only quoted text).
# - The java command is an array; nothing is ever re-parsed as shell text (no eval).
# - The compatibility agent is REQUIRED on Linux: the game's own classes differ from the Windows build the mod
#   is linked against, and the agent is what links them at load. Every reason it could not ride stops here,
#   before anything starts, with its fix.
#
# Drills (the host's tooling; a player sets none of these): CC_JOIN_VERIFY_ONLY=1 = the kit check only;
# CC_JOIN_DRYRUN=1 (or CC_DRY_RUN=1) = read the inputs from the environment, print the composed command line
# and exit before any Java starts; CC_JOIN_ENVLAUNCH=1 = the same inputs, real launch. Inputs: CC_JOIN_HOST,
# CC_JOIN_PORT, CC_JOIN_NAME, CC_JOIN_PASSWORD, CC_JOIN_CID, CC_JOIN_TOKEN, CC_JOIN_LOGS, CC_JOIN_SHIP.
# CC_JOIN_UI=browser|swing picks the join window's form.

# Before anything else: nothing this script (or the game it starts) writes is readable by other accounts.
umask 077
# The lines below use bash 4 features; "sh join-cc.sh" would read them as a different language.
[ -n "${BASH_VERSION:-}" ] || { echo "Run this file with bash:  bash mods/CC_Client/join-cc.sh"; exit 1; }
if [ "${BASH_VERSINFO[0]}" -lt 4 ]; then echo "This file needs bash 4 or newer."; exit 1; fi
set -euo pipefail
unset CDPATH
# Every name this script keeps state in starts clean: a variable of the same name inherited from the calling
# shell would otherwise keep its export mark and ride into every child process - the game included.
unset CCDRY CCLOG CCKITSTAMP CCKITMD5 CCAGENTMD5 CCJARMD5 CCAGENTJAR CCAGENTOPT CCMD5 CCDATABASE CCDATAWHY \
    CCRUNBASE CCRUNWHY CCRUNDIR CCDATADIR CCHODIR CCJOINDIR CCJAVA CCJAVATOK CCLINESRC CCLINEWHY CCLOGSDIR \
    CCHOST CCPORT CCNAME2 CCTOKEN CCPASSWORD CCCID CCLOGS CCSHIP CCTEAM CCENVTOKEN CCENVPASSWORD CCENVCID \
    CCPLAN CCPLANCLAIM CCNONCE CCWINDOWUP CCWINPID CCHANDOFF CCHOFILE CCHOCLAIM CCMATCH CCSNDRC CCSNDLOG \
    CCJTO CCJOPT CCGAMEPID CCGAMERC CCEARLY CCWORK CCKITLAUNCH CCWINCP CCJSH
CCRAW=(); CCLINE=(); CCARGV=(); CCROWS=()
# The player's own Java option variables never reach a child of this script (the Windows launcher clears
# them the same way); the game gets this launch's values on its own command line below.
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS

KIT=$(cd -- "$(dirname -- "$0")" && pwd -P)
ROOT=$(cd -- "$KIT/../.." && pwd -P)
CCKITLAUNCH=linux-sh
CCDRY=
if [ -n "${CC_JOIN_DRYRUN:-}${CC_DRY_RUN:-}" ]; then CCDRY=1; fi
CCLOG=
# The three credential inputs of the drill lanes are copied once and removed from the environment at once:
# from here on no child of this script inherits CC_JOIN_PASSWORD / CC_JOIN_TOKEN / CC_JOIN_CID.
CCENVTOKEN=${CC_JOIN_TOKEN:-}; CCENVPASSWORD=${CC_JOIN_PASSWORD:-}; CCENVCID=${CC_JOIN_CID:-}
unset CC_JOIN_TOKEN CC_JOIN_PASSWORD CC_JOIN_CID
CCHOFILE=; CCHOCLAIM=; CCPLAN=; CCPLANCLAIM=; CCWORK=; CCSNDLOG=; CCNONCE=; CCWINDOWUP=

# Leftovers of THIS run are removed whatever way it ends: an unread handoff file (it holds the password), the
# plan file and its claim, the report's temp folder, the sound self-check log.
cc_cleanup() {
    set +e
    if [ -n "$CCHOFILE" ]; then rm -f -- "$CCHOFILE"; fi
    if [ -n "$CCHOCLAIM" ]; then rmdir -- "$CCHOCLAIM" 2>/dev/null; fi
    if [ -n "$CCPLANCLAIM" ]; then
        rm -f -- "$CCPLAN" "$CCPLAN.alive" "$CCPLAN.tmp" "$CCPLAN.cancel"
        rmdir -- "$CCPLANCLAIM" 2>/dev/null
    fi
    if [ -n "$CCWORK" ]; then rm -rf -- "$CCWORK"; fi
    if [ -n "$CCSNDLOG" ]; then rm -f -- "$CCSNDLOG"; fi
}
trap cc_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

# A message that ends the run. A terminal window opened just for this file closes when it exits, so an
# interactive run waits for Enter first; drills (and a redirected input) never wait.
pause_close() {
    if [ -t 0 ] && [ -z "$CCDRY" ] && [ -z "${CC_JOIN_VERIFY_ONLY:-}" ]; then
        printf 'Press Enter to close.'
        read -r _ || true
    fi
}
die() {
    printf '%s\n' "$@" >&2
    pause_close
    exit 1
}
note() { printf '  NOTE: %s\n' "$*"; }
# One row in this kit's own log (join-cc.log): fixed words, counts, codes and install paths only - never a
# value the player typed.
log_row() {
    if [ -n "$CCLOG" ]; then
        { printf '%s join-cc: %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$CCLOG"; } 2>/dev/null || true
    fi
}

# The charset gate (the Windows launcher's vguard): letters, digits, dot, underscore, colon and hyphen only,
# the first character a letter or digit, at most 256 characters; empty passes (callers test for "" apart). Here
# it keeps whitespace in a host value from smuggling an extra Java option into JAVA_TOOL_OPTIONS, and keeps
# the handoff file one row per value. The set is spelled out (no a-z ranges: their meaning follows the locale).
vguard() {
    local v=$1
    if [ -z "$v" ]; then return 0; fi
    if [ "${#v}" -gt 256 ]; then return 1; fi
    case $v in
        *[!0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz._:-]*) return 1 ;;
        [._:-]*) return 1 ;;
    esac
    return 0
}

# $1 bytes from the kernel's random source as 2*$1 lowercase hex digits, or nothing.
hex() {
    local h
    h=$(od -An -N"$1" -tx1 /dev/urandom 2>/dev/null | tr -d ' \n') || h=
    case $h in *[!0123456789abcdef]*) h= ;; esac
    if [ "${#h}" -ne $(( $1 * 2 )) ]; then h=; fi
    printf '%s' "$h"
}

# The md5 of a file (lowercase), or nothing. md5sum, else busybox's.
md5_of() {
    local out
    if command -v md5sum >/dev/null 2>&1; then out=$(md5sum "$1" 2>/dev/null) || out=
    else out=$(busybox md5sum "$1" 2>/dev/null) || out=
    fi
    out=${out%% *}
    out=${out#\\}
    out=${out,,}
    case $out in *[!0123456789abcdef]*) out= ;; esac
    if [ "${#out}" -ne 32 ]; then out=; fi
    printf '%s' "$out"
}

kitbad() {
    die "$@" "" " *** KIT VERSION CHECK FAILED ***" \
        "Your CC_Client folder does not match the kit it shipped in - a stale or" \
        "half-updated client cannot join the match." \
        "FIX: delete the CC_Client folder from mods/, then re-extract the NEWEST" \
        "kit zip the host sent you and copy its CC_Client folder back in." \
        "Then tell the host what happened."
}
kitbadval() {
    die "" " *** KIT MANIFEST REJECTED ***" \
        "A value in kit-manifest.txt contains characters that are not allowed" \
        "(letters, digits, dot, underscore, colon and hyphen only). A kit like this" \
        "is malformed or tampered with - it was NOT used and the game was not started." \
        "FIX: ask the host to re-send the kit zip, and tell them this check fired."
}
badvalue() {
    die "" " *** INPUT REJECTED ***" \
        "The address, port, credential or ship id contains characters that are not allowed" \
        "(letters, digits, dot, underscore, colon and hyphen only, and the first character" \
        "must be a letter or digit - a password may not start with a hyphen). Nothing was started." \
        "FIX: re-run and enter the address and port exactly as the host gave them."
}
badinput() { die "No server address/port provided - ask the host, then run this again."; }
# The per-user folder could not take the handoff/plan files: the launch is REFUSED - a credential never falls
# back to riding a command line or JAVA_TOOL_OPTIONS.
handoffbad() {
    if [ -n "$CCHOFILE" ]; then rm -f -- "$CCHOFILE"; CCHOFILE=; fi
    if [ -n "$CCHOCLAIM" ]; then rmdir -- "$CCHOCLAIM" 2>/dev/null || true; CCHOCLAIM=; fi
    die "Your per-user folder ($CCRUNDIR) cannot be written, so the match credential cannot be handed" \
        "to the game. Nothing was started."
}

# ---- C1 the location: <Starsector>/mods/CC_Client/ (the Linux install keeps mods/ beside starsector.sh) ----
if [ ! -f "$ROOT/starsector.sh" ]; then
    die "This file must be inside the Starsector folder at mods/CC_Client/" \
        "(starsector.sh was not found two folders up from here; a linked mods or CC_Client folder" \
        "is not supported - copy the folder in instead)."
fi

# ---- C2 the kit version check: this folder's jar must be the jar kit-manifest.txt names ----
CCKITSTAMP=; CCKITMD5=; CCAGENTMD5=
if [ ! -f "$KIT/kit-manifest.txt" ]; then kitbad "  detail: kit-manifest.txt is missing from this CC_Client folder."; fi
while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    case $line in *=*) ;; *) continue ;; esac
    case ${line%%=*} in
        [Kk][Ii][Tt][Ss][Tt][Aa][Mm][Pp]) CCKITSTAMP=${line#*=} ;;
        [Jj][Aa][Rr][Mm][Dd]5) CCKITMD5=${line#*=} ;;
        [Aa][Gg][Ee][Nn][Tt][Mm][Dd]5) CCAGENTMD5=${line#*=} ;;
    esac
done < "$KIT/kit-manifest.txt"
# Manifest values are host-authored text that is printed and compared below: charset-gated first.
vguard "$CCKITSTAMP" || kitbadval
vguard "$CCKITMD5" || kitbadval
vguard "$CCAGENTMD5" || kitbadval
if ! command -v md5sum >/dev/null 2>&1 && ! command -v busybox >/dev/null 2>&1; then
    die "Neither md5sum nor busybox is installed, so the kit check cannot run. Nothing was started." \
        "FIX: install coreutils (it provides md5sum), then run this again."
fi
CCJARMD5=$(md5_of "$KIT/jars/cc-client-mod.jar")
if [ -z "$CCKITMD5" ]; then kitbad "  detail: kit-manifest.txt has no jarMd5 line."; fi
if [ -z "$CCJARMD5" ]; then kitbad "  detail: could not hash jars/cc-client-mod.jar - is it missing?"; fi
if [ "$CCJARMD5" != "${CCKITMD5,,}" ]; then
    kitbad "  detail: this folder's jar is $CCJARMD5 but the kit manifest says $CCKITMD5."
fi
echo "Kit check OK: kit $CCKITSTAMP, jar $CCJARMD5."
if [ -n "${CC_JOIN_VERIFY_ONLY:-}" ]; then exit 0; fi

# ---- C4 the compatibility agent: required here, so every reason it could not ride stops the run now ----
CCAGENTJAR="$KIT/jars/cc-compat-agent.jar"
CCAGENTOPT=
if [ -z "$CCAGENTMD5" ]; then
    kitbad "  detail: kit-manifest.txt has no agentMd5 line - this kit carries no compatibility agent," \
        "  and on Linux the game cannot start the mod without it."
fi
if [ -e "$CCAGENTJAR.off" ]; then
    die "jars/cc-compat-agent.jar.off is present, but on Linux the game cannot start the mod without the" \
        "compatibility agent. Nothing was started." \
        "FIX: delete jars/cc-compat-agent.jar.off, then run this again. (To switch off only its" \
        "pilot-replica part, put an empty jars/cc-compat-agent.pilot.off file there instead.)"
fi
# Java reads -javaagent:<jar>=<options> and splits at the FIRST "=", so a path holding one cuts the jar
# path short; a double quote would break the quoted paths inside JAVA_TOOL_OPTIONS.
case $KIT in
    *[=\"]*) die "This Starsector folder's path contains '=' or a double quote, and Java cannot load the" \
        "compatibility agent from such a path. Nothing was started." \
        "FIX: move or rename the Starsector folder so its path has neither, then run this again." ;;
esac
# A class path is split at ":", so the jars the helper Java runs from must sit on a path without one.
case $KIT in
    *:*) die "This Starsector folder's path contains ':', which a Java class path cannot hold. Nothing was started." \
        "FIX: move or rename the Starsector folder so its path has no ':', then run this again." ;;
esac
if [ ! -f "$CCAGENTJAR" ]; then
    kitbad "  detail: jars/cc-compat-agent.jar is missing."
fi
CCMD5=$(md5_of "$CCAGENTJAR")
if [ "$CCMD5" != "${CCAGENTMD5,,}" ]; then
    kitbad "  detail: jars/cc-compat-agent.jar does not match this kit's manifest."
fi
if [ -e "$KIT/jars/cc-compat-agent.pilot.off" ]; then
    # "=pilot=off" = the "=" that opens Java's agent options, then the option.
    CCAGENTOPT='=pilot=off'
    note "jars/cc-compat-agent.pilot.off is present, so the compatibility agent starts with its" \
        "pilot-replica part off - other pilots' ships run their mod scripts the plain way on this screen." \
        "Delete the .pilot.off file to turn it back on."
fi

# ---- C5 the per-user folders: ONE ladder, the same text the mod's Java side resolves ----
# runtimeBase = $XDG_RUNTIME_DIR when set and absolute, else dataBase;
# dataBase    = $XDG_DATA_HOME when set and absolute, else $HOME/.local/share.
# <runtimeBase>/cc-starsector holds the short-lived files (handoff/<id>.txt, join/ endpoint file, the plan
# family); <dataBase>/cc-starsector the lasting ones (settings, install id, join-window.log). HOME is read
# from the environment only, exactly as the game reads it: a HOME that differs from the account's home
# record would otherwise put the handoff file where the game does not look.
case ${HOME:-} in
    /*) ;;
    *) die "HOME is not set to an absolute path, so the per-user folder for the match credential cannot" \
        "be found. Nothing was started." \
        "FIX: log in normally (the login sets HOME), then run this again." ;;
esac
CCDATABASE="$HOME/.local/share"; CCDATAWHY=home
case ${XDG_DATA_HOME:-} in /*) CCDATABASE=$XDG_DATA_HOME; CCDATAWHY=XDG_DATA_HOME ;; esac
CCRUNBASE=$CCDATABASE; CCRUNWHY=$CCDATAWHY
case ${XDG_RUNTIME_DIR:-} in /*) CCRUNBASE=$XDG_RUNTIME_DIR; CCRUNWHY=XDG_RUNTIME_DIR ;; esac
CCRUNDIR="$CCRUNBASE/cc-starsector"
CCDATADIR="$CCDATABASE/cc-starsector"
CCHODIR="$CCRUNDIR/handoff"
CCJOINDIR="$CCRUNDIR/join"

# This kit's own log: the game's output, the "Picked up JAVA_TOOL_OPTIONS" line Java prints, and this file's
# rows. One older generation is kept. Never part of the session report.
if [ -z "$CCDRY" ]; then
    if [ -e "$KIT/join-cc.log" ]; then mv -f -- "$KIT/join-cc.log" "$KIT/join-cc.log.1" 2>/dev/null || true; fi
    if ! { : >> "$KIT/join-cc.log"; } 2>/dev/null; then
        die "This CC_Client folder cannot be written ($KIT/join-cc.log). Nothing was started." \
            "FIX: make the Starsector folder writable for this account, then run this again."
    fi
    CCLOG="$KIT/join-cc.log"
    log_row "run start kit=$CCKITSTAMP"
fi

# The folder rule: made 0700; a link or a folder of another account is never used (a file planted there could
# read or swap the credential); a folder of this account with wider permissions is narrowed, with one row.
# Never a silent fallback to another base: the mod resolves the same ladder and would look elsewhere.
base_unusable() {   # $1 = where the base came from, $2 = the folder that could not be made
    case $1 in
        XDG_RUNTIME_DIR) die "XDG_RUNTIME_DIR points at $XDG_RUNTIME_DIR, which this account cannot use -" \
            "log in normally or unset it, then run again. Nothing was started." ;;
        XDG_DATA_HOME) die "XDG_DATA_HOME points at $XDG_DATA_HOME, which this account cannot use -" \
            "fix or unset it, then run again. Nothing was started." ;;
        *) die "The folder $2 cannot be created in your home folder - check that it is writable," \
            "then run again. Nothing was started." ;;
    esac
}
safe_dir() {   # $1 = the folder, $2 = where its base came from (XDG_RUNTIME_DIR | XDG_DATA_HOME | home)
    local d=$1 m
    if [ ! -e "$d" ] && [ ! -L "$d" ]; then
        if ! mkdir -p -m 700 -- "$d" 2>/dev/null; then base_unusable "$2" "$d"; fi
    fi
    if [ -L "$d" ] || [ ! -d "$d" ] || [ ! -O "$d" ]; then
        die "$d is a link, not a folder, or belongs to another account, so the match credential cannot" \
            "be kept there safely. Nothing was started." \
            "FIX: remove or rename $d, then run this again."
    fi
    m=$(stat -c %a -- "$d" 2>/dev/null) || m=
    if [ "$m" != 700 ]; then
        chmod 700 -- "$d" 2>/dev/null || true
        chmod -R go-rwx -- "$d" 2>/dev/null || true
        log_row "folder made private path=$d was=${m:-unknown}"
    fi
}
safe_dir "$CCRUNDIR" "$CCRUNWHY"
safe_dir "$CCHODIR" "$CCRUNWHY"
safe_dir "$CCJOINDIR" "$CCRUNWHY"
safe_dir "$CCDATADIR" "$CCDATAWHY"
# Start-of-run sweep: per-run leftovers at least a day old (a terminal closed mid-game or mid-launch).
# Another console's fresh files are never touched.
sweep() { find "$1" -maxdepth 1 "${@:2}" -mmin +1440 -delete 2>/dev/null || true; }
sweep "$CCHODIR" -type f -name '*.txt'
sweep "$CCHODIR" -type d -name '*.claim' -empty
sweep "$CCRUNDIR" -type f -name 'plan-*.txt*'
sweep "$CCRUNDIR" -type d -name 'plan-*.claim' -empty
sweep "$CCRUNDIR" -type f -name 'sound-probe-*.log'

# ---- the java line: READ from the install's own starsector.sh (a player's memory settings live there) ----
# The drop list is the Windows kit's own (kit-runtime.ps1: Drop + DropPrefix), minus -XX:ShenandoahGCMode=iu,
# which the game's own Java 17 still knows (the Windows kit drops it for a newer Java): CPU-feature pins a
# lesser CPU clamps with warnings, the modal error box (it would hang a start with no terminal), preview mode,
# extended crash reports, heap pre-touch and the compiler-directives file. The java token itself is checked
# and re-added absolute; "$@" (after the main class) is never read.
CC_DROP=(-XX:+ShowMessageBoxOnError --enable-preview -XX:+ExtensiveErrorReports -XX:UseAVX=3 -XX:AVX3Threshold=0
    -XX:UseSSE=4 -XX:+UseSSE42Intrinsics -XX:+UseFMA -XX:+UseBMI1Instructions -XX:+UseBMI2Instructions
    -XX:+UseCLMUL -XX:+UseFastStosb -XX:+AlwaysPreTouch)
CC_DROP_PREFIX=(-XX:CompilerDirectivesFile=)
CC_MAIN=com.fs.starfarer.StarfarerLauncher

# Reads the command that runs jre_linux/bin/java up to the main class: CR and "\" continuations stripped, each
# line split into words without any expansion; a leading "exec" or NAME=value word is skipped. Anything that
# would need a shell to mean what it says (quotes, $, `, ;, |, &, <, >, parentheses, a comment) = no parse.
# 0 = CCJAVATOK + CCRAW filled; 1 = CCLINEWHY says why.
parse_starsector_sh() {
    local line t started= found= cp=
    local -a words
    CCRAW=(); CCJAVATOK=; CCLINEWHY=
    while IFS= read -r line || [ -n "$line" ]; do
        line=${line%$'\r'}
        if [ -z "$started" ]; then
            # a comment line that merely names the java path does not start the command
            case ${line#"${line%%[![:space:]]*}"} in '#'*) continue ;; esac
            case $line in *jre_linux/bin/java*) started=1 ;; *) continue ;; esac
        fi
        line=${line%\\}
        read -r -a words <<< "$line" || true
        for t in ${words[@]+"${words[@]}"}; do
            if [ -z "$CCJAVATOK" ]; then
                if [ "$t" = exec ] || [[ $t =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then continue; fi
                case $t in
                    *jre_linux/bin/java) CCJAVATOK=$t; continue ;;
                    *) CCLINEWHY="the java command is wrapped in another command"; return 1 ;;
                esac
            fi
            if [ "$t" = "$CC_MAIN" ]; then found=1; break; fi
            case $t in
                '#'*|*[\"\'\$\`\;\|\&\<\>\(\)\\]*) CCLINEWHY="the java command uses shell syntax"; return 1 ;;
            esac
            CCRAW+=("$t")
        done
        if [ -n "$found" ]; then break; fi
    done < "$1"
    if [ -z "$started" ]; then CCLINEWHY="no jre_linux/bin/java command"; return 1; fi
    if [ -z "$found" ]; then CCLINEWHY="no $CC_MAIN after the java command"; return 1; fi
    case $CCJAVATOK in *[\"\'\$\`\\]*) CCLINEWHY="the java path uses shell syntax"; return 1 ;; esac
    for t in ${CCRAW[@]+"${CCRAW[@]}"}; do
        if [ -n "$cp" ]; then cp=2; break; fi
        case $t in -classpath|-cp|--class-path) cp=1 ;; esac
    done
    if [ "$cp" != 2 ]; then CCLINEWHY="no class path in the java command"; return 1; fi
    return 0
}
# The stock fallback: the same list, checked in beside this file (one option per line).
read_stock_args() {
    local line
    CCRAW=()
    if [ ! -f "$KIT/linux-stock.args" ]; then kitbad "  detail: linux-stock.args is missing from this CC_Client folder."; fi
    while IFS= read -r line || [ -n "$line" ]; do
        line=${line%$'\r'}
        line=${line#"${line%%[![:space:]]*}"}
        line=${line%"${line##*[![:space:]]}"}
        case $line in ''|'#'*) continue ;; esac
        CCRAW+=("$line")
    done < "$KIT/linux-stock.args"
    if [ "${#CCRAW[@]}" -eq 0 ]; then kitbad "  detail: linux-stock.args is empty."; fi
}
drop_listed() {   # CCRAW -> CCLINE, minus the drop list
    local t d keep
    CCLINE=()
    for t in "${CCRAW[@]}"; do
        keep=1
        for d in "${CC_DROP[@]}"; do if [ "$t" = "$d" ]; then keep=; break; fi; done
        for d in "${CC_DROP_PREFIX[@]}"; do case $t in "$d"*) keep= ;; esac; done
        if [ -n "$keep" ]; then CCLINE+=("$t"); fi
    done
}
if parse_starsector_sh "$ROOT/starsector.sh"; then
    CCLINESRC=parsed
    case $CCJAVATOK in /*) CCJAVA=$CCJAVATOK ;; *) CCJAVA="$ROOT/${CCJAVATOK#./}" ;; esac
else
    CCLINESRC=stock
    CCJAVA="$ROOT/jre_linux/bin/java"
    read_stock_args
    note "starsector.sh's java command could not be read ($CCLINEWHY), so the stock Starsector" \
        "0.98a-RC8 line is used instead - memory settings changed in starsector.sh do not apply to this launch."
fi
drop_listed
log_row "java line=$CCLINESRC${CCLINEWHY:+ why=$CCLINEWHY} options=${#CCLINE[@]}"
# The logs folder the game writes starsector.log and the mod's cc-client.log to (the session report reads both).
CCLOGSDIR=.
for t in "${CCLINE[@]}"; do
    case $t in -Dcom.fs.starfarer.settings.paths.logs=*) CCLOGSDIR=${t#*=} ;; esac
done
case $CCLOGSDIR in /*) ;; *) CCLOGSDIR="$ROOT/$CCLOGSDIR" ;; esac

# ---- pre-flight: what the game needs from this PC, each with its fix ----
if [ ! -f "$CCJAVA" ] || [ ! -x "$CCJAVA" ]; then
    die "The game's Java ($CCJAVA) is missing or not executable - the game was unpacked without its file" \
        "permissions. Nothing was started. FIX (then run this again):" \
        "  chmod +x $(printf '%q' "$(dirname -- "$CCJAVA")")/* $(printf '%q' "$(dirname -- "$(dirname -- "$CCJAVA")")/lib/jspawnhelper")"
fi
CCJSH="$(dirname -- "$(dirname -- "$CCJAVA")")/lib/jspawnhelper"
if [ -e "$CCJSH" ] && [ ! -x "$CCJSH" ]; then
    die "The game's Java helper ($CCJSH) is not executable - the game was unpacked without its file" \
        "permissions, and the game window cannot open. Nothing was started. FIX (then run this again):" \
        "  chmod +x $(printf '%q' "$CCJSH")"
fi
# The game's window library asks xrandr for the screen modes before it opens a window.
if [ -z "$CCDRY" ] && ! command -v xrandr >/dev/null 2>&1; then
    die "xrandr is not installed; the game window cannot open without it. Nothing was started." \
        "FIX: install it (Debian/Ubuntu: x11-xserver-utils, Arch: xorg-xrandr, Fedora: xrandr), then run this again."
fi
CCWINCP="$KIT/jars/cc-client-mod.jar:$ROOT/json.jar"

# ---- C7 / C9 the inputs: the join window (normal), or the environment (drills) ----
# A plan name / handoff id is CLAIMED by making an empty <name>.claim folder (mkdir fails when it exists), so
# two consoles never share one; a leftover file under a fresh claim is a dead console's: draw again.
mint_plan() {
    local i id
    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16; do
        id=$(hex 4)
        if [ -z "$id" ]; then return 1; fi
        mkdir -- "$CCRUNDIR/plan-$id.claim" 2>/dev/null || continue
        CCPLANCLAIM="$CCRUNDIR/plan-$id.claim"; CCPLAN="$CCRUNDIR/plan-$id.txt"
        if [ -e "$CCPLAN" ] || [ -e "$CCPLAN.cancel" ]; then
            rmdir -- "$CCPLANCLAIM" 2>/dev/null || true; CCPLANCLAIM=; CCPLAN=; continue
        fi
        return 0
    done
    return 1
}
plan_claim_free() {
    if [ -n "$CCPLANCLAIM" ]; then rmdir -- "$CCPLANCLAIM" 2>/dev/null || true; fi
    CCPLANCLAIM=
}
# The launch nonce binds the join window to THIS launch's game (the mod writes it into the editor endpoint file).
mint_nonce() { CCNONCE=$(hex 4); }
helper_java() {   # a helper Java: from the install root, with every Java option variable removed
    ( cd -- "$ROOT" && exec env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS "$CCJAVA" "$@" )
}
open_window() {
    local ui=() line k v
    CCPLAN=; CCPLANCLAIM=
    mint_plan || handoffbad
    { : > "$CCPLAN.alive"; } 2>/dev/null || handoffbad
    mint_nonce
    if [ -n "${CC_JOIN_UI:-}" ]; then ui=("-Dcc.join.ui=$CC_JOIN_UI"); fi
    echo "Opening the join window (fill it in, then Launch)..."
    helper_java ${ui[@]+"${ui[@]}"} -cp "$CCWINCP" cc.starsector.client.join.JoinGui "$KIT" "$CCPLAN" \
        ${CCNONCE:+"$CCNONCE"} > /dev/null 2>&1 < /dev/null &
    CCWINPID=$!
    CCWINDOWUP=1
    # The window hands the plan over by FILE (written .tmp + renamed) = Launch; <plan>.cancel = its Cancel.
    # Every poll rewrites <plan>.alive: the window lives while that tick keeps changing.
    while [ ! -e "$CCPLAN" ]; do
        if [ -e "$CCPLAN.cancel" ]; then window_cancelled; fi
        if ! kill -0 "$CCWINPID" 2>/dev/null; then
            if [ -e "$CCPLAN" ]; then break; fi
            if [ -e "$CCPLAN.cancel" ]; then window_cancelled; fi
            die "The join window closed before Launch - it may have failed to open (a desktop session is" \
                "needed). Nothing was started. Its log: $CCDATADIR/join-window.log"
        fi
        { date +%T > "$CCPLAN.alive"; } 2>/dev/null || true
        sleep 1
    done
    CCHOST=; CCPORT=; CCNAME2=; CCTOKEN=; CCPASSWORD=; CCCID=; CCLOGS=; CCSHIP=; CCTEAM=
    while IFS= read -r line || [ -n "$line" ]; do
        line=${line%$'\r'}
        case $line in *=*) ;; *) continue ;; esac
        k=${line%%=*}; v=${line#*=}
        case ${k,,} in
            host) CCHOST=$v ;; port) CCPORT=$v ;; name) CCNAME2=$v ;; token) CCTOKEN=$v ;;
            password) CCPASSWORD=$v ;; cid) CCCID=$v ;; logs) CCLOGS=$v ;; ship) CCSHIP=$v ;; team) CCTEAM=$v ;;
        esac
    done < "$CCPLAN"
    rm -f -- "$CCPLAN" "$CCPLAN.alive"
    plan_claim_free
}
window_cancelled() {
    rm -f -- "$CCPLAN" "$CCPLAN.cancel" "$CCPLAN.alive" "$CCPLAN.tmp"
    plan_claim_free
    die "Launch cancelled - nothing was started. Run this again when ready." \
        "(If a join window was already open, its browser tab was brought back instead - use that one.)"
}
env_inputs() {
    local x
    for x in "${CC_JOIN_HOST:-}" "${CC_JOIN_PORT:-}" "$CCENVTOKEN" "$CCENVPASSWORD" "$CCENVCID" \
        "${CC_JOIN_LOGS:-}" "${CC_JOIN_SHIP:-}"; do
        vguard "$x" || badvalue
    done
    CCHOST=${CC_JOIN_HOST:-}; CCPORT=${CC_JOIN_PORT:-}; CCNAME2=${CC_JOIN_NAME:-}
    CCTOKEN=$CCENVTOKEN; CCPASSWORD=$CCENVPASSWORD; CCCID=$CCENVCID
    # Read once: a later pass of this run (the R path) carries no credential from the environment.
    CCENVTOKEN=; CCENVPASSWORD=; CCENVCID=
    CCLOGS=${CC_JOIN_LOGS:-}; CCSHIP=${CC_JOIN_SHIP:-}
    # The team pick rides a window launch only.
    CCTEAM=
}
# C10 the input gate: host/port and the credential trio ride JAVA_TOOL_OPTIONS / the handoff file, so they
# are charset-gated; the free-text pilot name only loses its double quotes (it rides inside a quoted token).
gate_inputs() {
    local x
    for x in "$CCHOST" "$CCPORT" "$CCTOKEN" "$CCPASSWORD" "$CCCID" "$CCLOGS" "$CCSHIP" "$CCTEAM"; do
        vguard "$x" || badvalue
    done
    case $CCTEAM in ''|0|1|2) ;; *) badvalue ;; esac
    # Logging is on unless the plan says exactly "off" (the window's checkbox).
    if [ "${CCLOGS,,}" = off ]; then CCLOGS=off; else CCLOGS=on; fi
    CCNAME2=${CCNAME2//\"/}
    if [ -z "$CCHOST" ] || [ -z "$CCPORT" ]; then badinput; fi
}
# C14 the match: the window saves the host's match as match.txt (match.txt.txt = a save-as slip, accepted).
# No match = the window has not fetched it; a launch could only die in-game, so stop with the fix instead.
match_detect() {
    CCMATCH=
    if [ -f "$KIT/match.txt" ]; then CCMATCH="$KIT/match.txt"
    elif [ -f "$KIT/match.txt.txt" ]; then CCMATCH="$KIT/match.txt.txt"
    fi
    if [ -z "$CCMATCH" ]; then
        die " *** NO MATCH YET ***" "The join window did not fetch the match." \
            "FIX: run join-cc again and press Refresh (the server must be running)."
    fi
    echo "Mode: *** UDP session *** - the server's match is saved here (match.txt)."
    echo "  If the host changed the match, press R at the rejoin prompt: it fetches"
    echo "  the new one from the server. If they announced a NEW kit, extract it"
    echo "  again and run join-cc - its window fetches the match."
}
# C15 the sound self-check: SoundProbe (beside this file) on the game's own Java, from the install root, in
# the game's own library shape. Its output is the sound log the session report carries; its exit code is
# printed (0 = the game's sound library loads). Bounded: a sound server that hangs never blocks the launch.
sound_probe() {
    local t=()
    CCSNDRC=
    if [ -n "$CCDRY" ]; then return 0; fi
    CCSNDLOG="$CCRUNDIR/sound-probe-$$.log"
    if command -v timeout >/dev/null 2>&1; then t=(timeout 60); fi
    if ( cd -- "$ROOT" && exec ${t[@]+"${t[@]}"} env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS \
        "$CCJAVA" -cp "lwjgl.jar:$KIT" -Djava.library.path=native/linux -Dorg.lwjgl.util.Debug=true \
        SoundProbe "$ROOT/native/linux" "" ) > "$CCSNDLOG" 2>&1 < /dev/null; then CCSNDRC=0; else CCSNDRC=$?; fi
    echo "Sound self-check: rc=$CCSNDRC (0 = OK; details ride the session report)."
    log_row "sound self-check rc=$CCSNDRC"
}
prepare() {   # the inputs of a pass: gathered, gated, the match found, the sound checked
    if [ -n "$CCDRY" ] || [ -n "${CC_JOIN_ENVLAUNCH:-}" ]; then env_inputs; else open_window; fi
    gate_inputs
    match_detect
    sound_probe
}

# ---- C11 the credential handoff + C16 the launch options + C18/C19 the start ----
mint_handoff() {
    local i id
    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16; do
        id=$(hex 8)
        if [ -z "$id" ]; then return 1; fi
        mkdir -- "$CCHODIR/$id.claim" 2>/dev/null || continue
        if [ -e "$CCHODIR/$id.txt" ] || [ -L "$CCHODIR/$id.txt" ]; then
            rmdir -- "$CCHODIR/$id.claim" 2>/dev/null || true; continue
        fi
        CCHANDOFF=$id; CCHOCLAIM="$CCHODIR/$id.claim"; CCHOFILE="$CCHODIR/$id.txt"
        return 0
    done
    return 1
}
compose() {
    CCJTO="-Dcc.predict.host=$CCHOST -Dcc.predict.port=$CCPORT -Dcc.predict.shipId=0 -Dcc.predict.duplex=true"
    if [ -n "$CCNAME2" ]; then CCJTO+=" \"-Dcc.match.playerName=$CCNAME2\""; fi
    # The sound fix the Windows kit rides too: the engine tries <org.lwjgl.librarypath>/<library> FIRST.
    CCJTO+=" \"-Dorg.lwjgl.librarypath=$ROOT/native/linux\""
    if [ -n "$CCHANDOFF" ]; then CCJTO+=" -Dcc.kit.handoff=$CCHANDOFF"; fi
    if [ -n "$CCSHIP" ]; then CCJTO+=" -Dcc.predict.variantId=$CCSHIP"; fi
    if [ -n "$CCTEAM" ] && [ "$CCTEAM" != 0 ]; then CCJTO+=" -Dcc.predict.team=$CCTEAM"; fi
    # Logs off = the mod's no-log mode; a tooling-armed watch (CC_WATCH_SIDELOG) always keeps its log.
    if [ "$CCLOGS" = off ] && [ -z "${CC_WATCH_SIDELOG:-}" ]; then CCJTO+=" -Dcc.watch.sideLog=none"; fi
    if [ -n "${CC_CLIENT_JVM_FLAGS:-}" ]; then CCJTO+=" $CC_CLIENT_JVM_FLAGS"; fi
    CCJTO+=" -Dcc.kit.launch=$CCKITLAUNCH"
    if [ -n "$CCNONCE" ]; then CCJTO+=" -Dcc.editor.launch=$CCNONCE"; fi
    # The kit's own mods root (links to the host's required mods + this kit, staged by the join window):
    # _JAVA_OPTIONS is read AFTER the command line, so it beats starsector.sh's paths.mods. The player's own
    # mods/enabled_mods.json is never read or written.
    CCJOPT=
    if [ -f "$ROOT/saves/common/cc_mods/enabled_mods.json" ]; then
        CCJOPT="\"-Dcom.fs.starfarer.settings.paths.mods=$ROOT/saves/common/cc_mods\""
    fi
    CCARGV=("$CCJAVA" "-javaagent:$CCAGENTJAR$CCAGENTOPT" "${CCLINE[@]}" -XX:-ShowMessageBoxOnError "$CC_MAIN")
}
dry_report() {
    local a
    echo "DRYRUN jto=$CCJTO"
    # A dry run KEEPS its handoff file (drills read it back) and prints its path only - never a row.
    if [ -n "$CCHOFILE" ]; then echo "DRYRUN handoff=$CCHOFILE"; fi
    if [ -n "$CCHOCLAIM" ]; then rmdir -- "$CCHOCLAIM" 2>/dev/null || true; fi
    CCHOFILE=; CCHOCLAIM=
    echo "DRYRUN jopt=$CCJOPT"
    echo "DRYRUN launch=$CCKITLAUNCH"
    echo "DRYRUN agent=-javaagent:$CCAGENTJAR$CCAGENTOPT"
    echo "DRYRUN transport=udp"
    echo "DRYRUN descriptor=$CCMATCH"
    echo "DRYRUN java_line=$CCLINESRC${CCLINEWHY:+ ($CCLINEWHY)}"
    echo "DRYRUN rundir=$CCRUNDIR"
    echo "DRYRUN datadir=$CCDATADIR"
    if command -v xrandr >/dev/null 2>&1; then echo "DRYRUN xrandr=present"; else echo "DRYRUN xrandr=missing"; fi
    echo "DRYRUN cwd=$ROOT"
    for a in "${CCARGV[@]}"; do echo "DRYRUN arg=$a"; done
    printf 'DRYRUN cmd='; printf '%q ' "${CCARGV[@]}"; echo
    exit 0
}
start_game() {
    # The game is this script's CHILD (the report runs after it). It ignores the terminal's hang-up, as the
    # Windows game outlives its console; Ctrl+C here never reaches it (a background child ignores it).
    log_row "pass start nonce=${CCNONCE:+set} handoff=${CCHANDOFF:+set} mods_root=${CCJOPT:+kit}"
    if [ -n "$CCJOPT" ]; then
        ( trap '' HUP; cd -- "$ROOT" && JAVA_TOOL_OPTIONS=$CCJTO _JAVA_OPTIONS=$CCJOPT \
            CC_PREDICT_TRANSPORT=udp CC_MATCH_DESCRIPTOR=$CCMATCH exec "${CCARGV[@]}" ) >> "$CCLOG" 2>&1 < /dev/null &
    else
        ( trap '' HUP; cd -- "$ROOT" && JAVA_TOOL_OPTIONS=$CCJTO \
            CC_PREDICT_TRANSPORT=udp CC_MATCH_DESCRIPTOR=$CCMATCH exec "${CCARGV[@]}" ) >> "$CCLOG" 2>&1 < /dev/null &
    fi
    CCGAMEPID=$!
    log_row "game started pid=$CCGAMEPID"
}
launch_face() {   # a REJOIN pass: the window alone, as the launch face, with this pass's nonce
    local ui=()
    if [ -n "${CC_JOIN_UI:-}" ]; then ui=("-Dcc.join.ui=$CC_JOIN_UI"); fi
    helper_java ${ui[@]+"${ui[@]}"} -cp "$CCWINCP" cc.starsector.client.join.JoinGui --launched "$KIT" \
        ${CCNONCE:+"$CCNONCE"} > /dev/null 2>&1 < /dev/null &
    CCWINDOWUP=1
}
run_pass() {
    local i
    # A rejoin pass gets a FRESH nonce, so a window still on its way out never takes the new game for its own.
    if [ -n "$CCNONCE" ] && [ -z "$CCWINDOWUP" ]; then mint_nonce; fi
    CCHANDOFF=; CCHOFILE=; CCHOCLAIM=
    if [ -n "$CCPASSWORD$CCCID$CCTOKEN" ]; then
        mint_handoff || handoffbad
        # noclobber: the file is created fresh (an existing name or link is never written through).
        ( set -C
          { if [ -n "$CCPASSWORD" ]; then printf 'password=%s\n' "$CCPASSWORD"; fi
            if [ -n "$CCCID" ]; then printf 'cid=%s\n' "$CCCID"; fi
            if [ -n "$CCTOKEN" ]; then printf 'token=%s\n' "$CCTOKEN"; fi
          } > "$CCHOFILE" ) 2>/dev/null || handoffbad
    fi
    compose
    if [ -n "$CCDRY" ]; then dry_report; fi
    start_game
    # C19 early death: a game that is gone within 5 s never got as far as its window.
    CCEARLY=
    for i in 1 2 3 4 5; do
        sleep 1
        if ! kill -0 "$CCGAMEPID" 2>/dev/null; then CCEARLY=1; break; fi
    done
    if [ -n "$CCEARLY" ]; then
        if wait "$CCGAMEPID"; then CCGAMERC=0; else CCGAMERC=$?; fi
        echo
        echo "The game stopped within its first seconds (exit $CCGAMERC). Its last lines ($KIT/join-cc.log):"
        { tail -n 40 -- "$CCLOG" 2>/dev/null | grep -v -E '^(NOTE: )?Picked up ' | tail -n 25; } || true
        echo
    else
        echo
        echo "Starsector is starting - it connects to $CCHOST:$CCPORT when you select the mission."
        if [ -n "$CCJOPT" ]; then
            echo "  Mods: the game loads the host's mod set from saves/common/cc_mods (your own mod list is untouched)."
        fi
        if [ -n "$CCSHIP" ]; then
            echo "  Initial ship: $CCSHIP (if the server no longer lists it, you spawn in the match default)."
        fi
        echo
        echo "  1. Click \"Play Starsector\" in the launcher window that opens."
        echo "  2. Missions, select \"Multiplayer Test\" (the text under the mission shows"
        echo "     whether the server is reachable; select it again to refresh). When the"
        echo "     host says GO press Play - that connects you and claims your seat."
        echo
        if [ -z "${CC_JOIN_ENVLAUNCH:-}" ] && [ -z "$CCWINDOWUP" ]; then launch_face; fi
        if [ -n "$CCWINDOWUP" ]; then
            echo "  The join window stays open: it opens your variant editor once the title screen is up."
        fi
        if wait "$CCGAMEPID"; then CCGAMERC=0; else CCGAMERC=$?; fi
    fi
    log_row "game exit rc=$CCGAMERC early=${CCEARLY:-no}"
    if [ "$CCLOGS" != off ]; then debrief || true; fi
    # The pass is over: its handoff file goes (if the game never read it), and its claim is given up.
    if [ -n "$CCHOFILE" ]; then rm -f -- "$CCHOFILE"; CCHOFILE=; fi
    if [ -n "$CCHOCLAIM" ]; then rmdir -- "$CCHOCLAIM" 2>/dev/null || true; CCHOCLAIM=; fi
    if [ -n "$CCSNDLOG" ]; then rm -f -- "$CCSNDLOG"; CCSNDLOG=; fi
    CCWINDOWUP=
}

# ---- C21 the session report: the same content as the Windows report, as a .tar.gz on the Desktop ----
squeeze() {   # $1 = text, $2 = cap: one line, whitespace runs folded
    local s=$1
    s=${s//[$'\t\r\n']/ }
    while [[ $s == *'  '* ]]; do s=${s//  / }; done
    s=${s# }; s=${s% }
    printf '%s' "${s:0:$2}"
}
redact() {   # the home folder (it names the account) becomes ~
    local s=$1 tl='~'
    if [ "${#HOME}" -ge 4 ]; then s=${s//"$HOME"/$tl}; fi
    printf '%s' "$s"
}
redact_file() {   # $1 -> $2, line by line
    local line
    while IFS= read -r line || [ -n "$line" ]; do printf '%s\n' "$(redact "$line")"; done < "$1" > "$2"
}
grep_rows() {   # $1 = file, $2 = pattern, $3 = last N, $4 = cap per row: the rows joined with " || "
    local -a hits
    local out= h
    mapfile -t hits < <(grep -E -- "$2" "$1" 2>/dev/null | tail -n "$3") || true
    for h in ${hits[@]+"${hits[@]}"}; do out+="${out:+ || }$(squeeze "$h" "$4")"; done
    printf '%s' "${out:-none}"
}
debrief() {
    local stamp work desk out m=() f v sidelog times avg n
    stamp="$(date +%Y%m%d-%H%M%S)-$$"
    work=$(mktemp -d "${TMPDIR:-/tmp}/cc-debrief-XXXXXXXX" 2>/dev/null) || { note "the session report could not be written (no temp folder)."; return 0; }
    CCWORK=$work
    m+=("cc joiner debrief bundle $stamp")
    m+=("game_dir=$ROOT")
    m+=("os=$(uname -srm 2>/dev/null || echo unknown)")
    # 1) the game's log and the mod's own log - the LIVE file only: cc-client.log.1 is always an earlier session.
    if [ -f "$CCLOGSDIR/starsector.log" ]; then
        cp -- "$CCLOGSDIR/starsector.log" "$work/starsector.log" 2>/dev/null
        m+=("starsector_log=copied bytes=$(wc -c < "$CCLOGSDIR/starsector.log" | tr -d ' ')")
    else
        m+=("starsector_log=NOT_FOUND path=$CCLOGSDIR/starsector.log")
    fi
    sidelog="$CCLOGSDIR/cc-client.log"
    case ${CC_WATCH_SIDELOG:-} in ''|off|none) ;; *) sidelog=$CC_WATCH_SIDELOG ;; esac
    if [ -f "$sidelog" ]; then
        cp -- "$sidelog" "$work/cc-client.log" 2>/dev/null
        m+=("cc-client_log=copied bytes=$(wc -c < "$sidelog" | tr -d ' ')")
    fi
    # 2) the Java the game runs on. All three option variables removed and any "Picked up" line dropped:
    # those lines echo option values, which are not the report's to carry.
    v=$(env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS "$CCJAVA" -version 2>&1 \
        | grep -v -E '^(NOTE: )?Picked up ' | tr '\n' '|') || v=
    v=${v%|}; v=${v//|/ | }
    m+=("java_version=$(squeeze "$v" 300)")
    # The launch rows.
    m+=("launch=$CCKITLAUNCH" "kit_launch=$CCKITLAUNCH" "java_line=$CCLINESRC${CCLINEWHY:+ ($CCLINEWHY)}")
    m+=("agent=on${CCAGENTOPT:+ pilot=off}" "logs_dir=$CCLOGSDIR" "game_rc=$CCGAMERC" "early_death=${CCEARLY:-no}")
    m+=("sound_probe_rc=${CCSNDRC:-skipped}")
    # The log tails: the game's reported Java, its sound init, the mod's kit rows (its engine link check
    # included), the sound self-check verdict.
    m+=("game_java=$(grep_rows "$work/starsector.log" 'Java version:' 1 300)")
    m+=("sound_init=$(grep_rows "$work/starsector.log" 'OpenAL|Fatal:|nalListenerfv|sound initialization|UnsatisfiedLinkError' 6 200)")
    m+=("sidelog_kit=$(grep_rows "$work/cc-client.log" 'kit launch|sound-retry|sound second chance|engine-map' 6 200)")
    if [ -n "$CCSNDLOG" ] && [ -f "$CCSNDLOG" ]; then
        m+=("sound_probe=$(grep_rows "$CCSNDLOG" '^probe RESULT' 3 200)")
        redact_file "$CCSNDLOG" "$work/sound-check.log" 2>/dev/null
        m+=("sound_log=copied bytes=$(wc -c < "$CCSNDLOG" | tr -d ' ')")
    else
        m+=("sound_probe=no sound log" "sound_log=NOT_FOUND")
    fi
    # The sound library's own needs on this PC, and the PC's sound cards (the Linux side of the Windows
    # report's sound diagnostic).
    f="$ROOT/native/linux/libopenal64.so"
    if command -v ldd >/dev/null 2>&1 && [ -f "$f" ]; then
        ldd "$f" 2>&1 | head -n 40 > "$work/ldd-openal.raw" || true
        redact_file "$work/ldd-openal.raw" "$work/ldd-openal.txt" 2>/dev/null; rm -f -- "$work/ldd-openal.raw"
        m+=("ldd_openal=$(grep_rows "$work/ldd-openal.txt" 'not found' 10 120)")
    else
        m+=("ldd_openal=unavailable")
    fi
    if command -v aplay >/dev/null 2>&1; then
        aplay -l 2>&1 | head -n 30 > "$work/aplay.txt" || true
        n=$(grep -c '^card ' "$work/aplay.txt" 2>/dev/null) || n=0
        m+=("aplay_cards=$n")
    else
        m+=("aplay_cards=aplay not installed")
    fi
    # 3) the mod list + enabled_mods.json.
    if [ -d "$ROOT/mods" ]; then
        v=
        for f in "$ROOT/mods"/*/; do
            if [ -d "$f" ]; then f=${f%/}; v+="${v:+, }${f##*/}"; fi
        done
        m+=("mods_installed=$v")
        if [ -f "$ROOT/mods/enabled_mods.json" ]; then cp -- "$ROOT/mods/enabled_mods.json" "$work/" 2>/dev/null; fi
    fi
    # 4) ping to the host.
    if command -v ping >/dev/null 2>&1; then
        v=$(ping -c 4 -W 2 "$CCHOST" 2>&1) || true
        times=$(printf '%s\n' "$v" | grep -o 'time=[0-9.]*' | cut -d= -f2 | tr '\n' ',') || times=
        avg=$(printf '%s\n' "$v" | grep -E 'min/avg/max' | cut -d= -f2 | cut -d/ -f2 | tr -d ' ') || avg=
        if [ -n "$times" ]; then m+=("ping_host=$CCHOST avg_ms=$avg samples_ms=${times%,}")
        else m+=("ping_host=$CCHOST FAILED")
        fi
    else
        m+=("ping_host=$CCHOST FAILED ping not installed")
    fi
    # 5) the game's own launch file (the Windows report's vmparams twin), the kit manifest, the java line.
    cp -- "$ROOT/starsector.sh" "$work/starsector.sh.txt" 2>/dev/null
    cp -- "$KIT/kit-manifest.txt" "$work/kit-manifest.txt" 2>/dev/null
    for f in "${CCARGV[@]}"; do redact "$f"; printf '\n'; done > "$work/java-line.txt"
    for f in "${m[@]}"; do redact "$f"; printf '\n'; done > "$work/manifest.txt"
    out=
    if command -v xdg-user-dir >/dev/null 2>&1; then out=$(xdg-user-dir DESKTOP 2>/dev/null) || out=; fi
    desk=$out
    if [ -z "$desk" ] || [ ! -d "$desk" ]; then desk="$HOME/Desktop"; fi
    if [ ! -d "$desk" ]; then desk=$HOME; fi
    out="$desk/cc-debrief-$stamp.tar.gz"
    if tar -czf "$out" -C "$work" . 2>/dev/null; then
        echo
        echo "Session report saved:"
        echo "  $out"
        echo "If the host asks for the session report, send back that ONE file."
    else
        note "the session report could not be written to $desk."
    fi
    rm -rf -- "$work"
    CCWORK=
    return 0
}

# ---- C23 / C24 the rejoin prompt and the match refresh before a relaunch ----
# R relaunches with the same inputs, once the server confirms the match (JoinGui --refresh-match: 10 = a new
# match saved, 2 = no answer, 1 = refused / no remembered password / different mods = the window again,
# 0 = current). Anything but R (Q, end of input) quits: never a relaunch loop no one asked for.
rprompt() {
    local ans rc
    while :; do
        echo "Game closed? You can rejoin without re-entering anything."
        echo "  R = rejoin now (the game relaunches and re-dials $CCHOST:$CCPORT)"
        echo "  Q or close this window = quit"
        ans=
        if ! read -r -n 1 -p "Press R to rejoin, or Q to quit: " ans; then echo; exit 0; fi
        echo
        case $ans in R|r) ;; *) exit 0 ;; esac
        # The team pick is one-shot: R relaunches with none (the server remembers it).
        CCTEAM=
        if helper_java -cp "$CCWINCP" cc.starsector.client.join.JoinGui --refresh-match "$CCHOST" "$CCPORT" "$KIT" \
            < /dev/null; then rc=0; else rc=$?; fi
        if [ "$rc" -ge 10 ]; then
            if [ -f "$KIT/match.txt" ]; then CCMATCH="$KIT/match.txt"; fi
            return 0
        elif [ "$rc" -ge 2 ]; then
            echo "The server did not answer - wait for the host, then press R again."
        elif [ "$rc" -ge 1 ]; then
            prepare
            return 0
        else
            return 0
        fi
    done
}

prepare
while :; do
    run_pass
    rprompt
done
