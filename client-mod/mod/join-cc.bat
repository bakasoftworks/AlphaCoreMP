@echo off
rem AlphaCoreMP dedicated-server joiner launcher (auto seat - the server assigns the ship).
rem Double-click, fill in the window that opens (address + port + match password the host gives you,
rem your pilot name; the window fetches the match from the server), the game starts. When the
rem game is quit, this window saves a session report zip to the Desktop automatically
rem (joiner-debrief.ps1, same folder) - the joining user never touches PowerShell - then offers
rem R = rejoin (relaunch the game with the same inputs, nothing re-entered).
rem Must live in ^<Starsector^>\mods\CC_Client\ (ships with the mod zip).
rem No client-mode pins ride this bat: the documented knob posture COMPILES
rem into the mod - eventReplay default-ON, weaponState default apply, and
rem every ratified predict mode COMPILES apply (foeInterp=meter
rem stays the sole compiled-meter mode).
rem The window's "Ship (initial spawn)" pick rides the plan row ship=
rem -> CCSHIP -> -Dcc.predict.variantId (empty = the match default; the server validates the id).
rem The joiner-only editor flow: ONE window - the join window is started DETACHED,
rem hands the plan over by FILE (this bat polls) and stays as the launch face that opens the
rem in-game variant editor; a launch NONCE minted here (:mintnonce) binds it to the game this
rem bat starts (-Dcc.editor.launch in the JTO = the endpoint file's third field).
rem Kit version check: hard-stops unless this folder's jar matches the
rem kit-manifest.txt stamped next to it (packager pack-stamps the kit; deploy.ps1 stamps the
rem host's deployed copy). CC_JOIN_VERIFY_ONLY=1 runs only the check, no window/game (drills).
rem The window is cc.starsector.client.join.JoinGui INSIDE the mod jar,
rem run on the game's own bundled JRE - so the kit check above version-gates the
rem GUI too. It hands host/port/name/password back via a plan file this bat parses; the match the
rem window fetched from the server is saved as match.txt BEFORE
rem the transport detect below.
rem UDP session (the UDP-transport work): a match.txt next to this bat selects UDP mode - the
rem join window fetches the host's match from the server and Launch saves it here; when the host
rem changes the match, R at the rejoin prompt fetches the new one first (:refreshmatch).
rem Transport + descriptor ride the mod's CC_PREDICT_TRANSPORT / CC_MATCH_DESCRIPTOR env
rem aliases (read after the sysprops; no JAVA_TOOL_OPTIONS quoting for the spaced install
rem path). No match.txt = HARD STOP: the window has not fetched the match (there is no other lane).
rem Match password: ONE password the host announces admits every joiner - typed
rem into the window (remembered), it rides the plan file to this bat together with the window's
rem minted install id. Neither rides the game's command line or JAVA_TOOL_OPTIONS: each launch
rem pass writes them (and a scripted seat token) to a one-time handoff file in the player's local
rem app-data folder (LOCALAPPDATA\cc-starsector\handoff\), hands the game only that file's random
rem 16-hex name as -Dcc.kit.handoff, and deletes the file again at the end of the pass (a dry run
rem keeps it and prints its path). The name is CLAIMED for the whole pass by an empty folder of the
rem same name plus .claim (:minthandoff), so two windows of one player never share a name.
rem No credential = no file and no -D: the token/descriptor lanes above, byte-identical.
rem CC_JOIN_DRYRUN=1 prints the composed launch env and exits before the game starts
rem (game-free drills; committed runner: the host's join-drill runner). DRYRUN skips the window and
rem reads CC_JOIN_HOST / CC_JOIN_PORT / CC_JOIN_NAME / CC_JOIN_TOKEN / CC_JOIN_PASSWORD /
rem CC_JOIN_CID env instead (the old console prompts died with the join-window rework).
rem CC_JOIN_ENVLAUNCH=1 = the same env inputs, but the game DOES launch (the host's unattended
rem kit drills, e.g. the Mark-of-the-Web download drill). A public joiner sets neither.
setlocal DisableDelayedExpansion
if not exist "%~dp0..\..\starsector.exe" goto badloc
call :envclean

set "CCKITSTAMP="
set "CCKITMD5="
if not exist "%~dp0kit-manifest.txt" (
    echo   detail: kit-manifest.txt is missing from this CC_Client folder.
    goto kitbad
)
for /f "usebackq tokens=1,* delims==" %%A in ("%~dp0kit-manifest.txt") do (
    if /i "%%A"=="kitStamp" set "CCKITSTAMP=%%B"
    if /i "%%A"=="jarMd5" set "CCKITMD5=%%B"
)
rem Manifest values are HOST-AUTHORED text that later rides a bare %VAR% into echo (the kit-OK
rem line and the mismatch line below), and cmd re-parses whatever it substitutes - so a manifest
rem row like "kitStamp=x & <command> & rem" would RUN on the joiner's PC, on the success path.
rem Charset-gate both before any bare expansion; same rule the seat-token/password lane states.
call :vguard CCKITSTAMP || goto kitbadval
call :vguard CCKITMD5 || goto kitbadval
rem client pairing lane retired to wire (WELCOME v5 matchFlags, server-owned).
rem officerSkillsAll now rides the wire; an old kit-manifest officerSkillsAll row is
rem ignored like any unknown key (no parse, no vguard, no launch append).
set "CCJARMD5="
for /f "delims=" %%H in ('certutil -hashfile "%~dp0jars\cc-client-mod.jar" MD5 ^| find /v ":"') do if not defined CCJARMD5 set "CCJARMD5=%%H"
if defined CCJARMD5 set "CCJARMD5=%CCJARMD5: =%"
call :vguard CCJARMD5 || goto kitbadval
if not defined CCKITMD5 (
    echo   detail: kit-manifest.txt has no jarMd5 line.
    goto kitbad
)
if not defined CCJARMD5 (
    echo   detail: could not hash jars\cc-client-mod.jar - is it missing?
    goto kitbad
)
if /i not "%CCJARMD5%"=="%CCKITMD5%" (
    echo   detail: this folder's jar is %CCJARMD5% but the kit manifest says %CCKITMD5%.
    goto kitbad
)
echo Kit check OK: kit %CCKITSTAMP%, jar %CCJARMD5%.
if defined CC_JOIN_VERIFY_ONLY exit /b 0
rem Credential handoff folder (the launch pass below writes there): LOCALAPPDATA\cc-starsector\handoff.
rem Start-of-run sweep, before any credential is read: a handoff file older than a day is the
rem leftover of a window that was closed mid-game. forfiles compares DATES, so /d -2 removes a
rem file only once it is at least 24 hours old - never the fresh file of another console of this
rem player, even across midnight. Skipped when the folder does not exist (or LOCALAPPDATA is unset).
rem The second sweep line is the same law for the id CLAIMS (:minthandoff): a window killed mid-pass
rem leaves its empty .claim folder behind. Only a FOLDER of that age goes (@isdir), and rd removes
rem only an empty one; a stale claim the sweep cannot remove only costs the next mint a re-draw.
rem A LOCALAPPDATA that is blank or not an absolute path (no drive letter + backslash, no \\ share)
rem counts as unset: a relative one would put the files below whatever folder this window runs in.
rem The test reads a "_"-prefixed copy: a substring of an UNSET variable breaks the line's parse.
rem The last three sweep lines apply the same one-day law to this folder's other per-run leftovers:
rem a join-window plan file (plan-*.txt and its .tmp / .cancel / .alive siblings - FILES only) of a
rem window closed mid-launch, the plan name's empty claim folder (plan-*.claim, :mintplan - FOLDERS
rem only, plain rd) of a console closed while it waited, and a sound-probe copy folder (oal-*) whose
rem library copy was still in use.
set "CCHODIR="
if defined LOCALAPPDATA set "CCHODIR=%LOCALAPPDATA%\cc-starsector\handoff"
set "CCLADX=_%LOCALAPPDATA%"
if defined CCHODIR if not "%CCLADX:~2,2%"==":\" if not "%CCLADX:~1,2%"=="\\" set "CCHODIR="
set "CCLADX="
if defined CCHODIR if exist "%CCHODIR%\" forfiles /p "%CCHODIR%" /m *.txt /d -2 /c "cmd /c if @isdir==FALSE del /q @path" >nul 2>&1
if defined CCHODIR if exist "%CCHODIR%\" forfiles /p "%CCHODIR%" /m *.claim /d -2 /c "cmd /c if @isdir==TRUE rd @path" >nul 2>&1
if defined CCHODIR if exist "%LOCALAPPDATA%\cc-starsector\" forfiles /p "%LOCALAPPDATA%\cc-starsector" /m plan-*.txt* /d -2 /c "cmd /c if @isdir==FALSE del /q @path" >nul 2>&1
if defined CCHODIR if exist "%LOCALAPPDATA%\cc-starsector\" forfiles /p "%LOCALAPPDATA%\cc-starsector" /m plan-*.claim /d -2 /c "cmd /c if @isdir==TRUE rd @path" >nul 2>&1
if defined CCHODIR if exist "%LOCALAPPDATA%\cc-starsector\" forfiles /p "%LOCALAPPDATA%\cc-starsector" /m oal-* /d -2 /c "cmd /c if @isdir==TRUE rd /s /q @path" >nul 2>&1
rem Same law for a leftover sound-check log in TEMP: the pass-end delete below can lose a race with the
rem background sound check (started detached, still writing rows), whose next row re-creates the file.
rem (forfiles /p refuses a path ending in a backslash, so the folder rides %%~fD, which drops the "\.")
if defined TEMP if exist "%TEMP%\" for %%D in ("%TEMP%\.") do forfiles /p "%%~fD" /m cc-sound-check-*.log /d -2 /c "cmd /c if @isdir==FALSE del /q @path" >nul 2>&1

rem DRAGON leg (b): a kit packed with -BundleJre carries its OWN Java next to
rem this bat (jre-cc\ = jlink'd Eclipse Temurin 25 - the voice fast path needs Java 22+) plus the
rem kit-jvm.args launch line; both present = the join window AND the game run on it (see the
rem launch below). Absent = byte-identical to before: the install's jre\ + starsector.exe.
rem Single-line ifs on purpose: %~dp0 may hold "(x86)" and a ")" inside a ( block ) breaks cmd.
set "CCJAVA=%~dp0..\..\jre\bin\java.exe"
set "CCLAUNCH=starsector.exe"
if exist "%~dp0jre-cc\bin\javaw.exe" if exist "%~dp0kit-jvm.args" set "CCLAUNCH=jre-cc"
if "%CCLAUNCH%"=="jre-cc" set "CCJAVA=%~dp0jre-cc\bin\java.exe"
rem Health probe: a bundled runtime that cannot even print its version
rem (antivirus-quarantined DLL, corrupt or partial download) must not be trusted with the join
rem window or the game - fall back to the install's jre\ + starsector.exe, i.e. exactly the
rem no-bundle kit. This is a plain CreateProcess run; the ShellExecute-side refusal (SmartScreen
rem on a downloaded javaw.exe) is caught separately right after the launch below.
set "CCJREFALL="
if "%CCLAUNCH%"=="jre-cc" "%CCJAVA%" -version >nul 2>&1
if "%CCLAUNCH%"=="jre-cc" if %errorlevel% neq 0 set "CCJREFALL=jre-cc\bin\java.exe -version failed, exit %errorlevel%"
if defined CCJREFALL set "CCLAUNCH=starsector.exe"
if defined CCJREFALL set "CCJAVA=%~dp0..\..\jre\bin\java.exe"
rem CCKITLAUNCH rides to the mod as -Dcc.kit.launch (composed below): the one LOG row that says
rem the fallback fired (the mod's sidelog, bundled into the Desktop report) - the console NOTE
rem alone would be gone with this window.
if defined CCJREFALL set "CCKITLAUNCH=fallback-probe"
if defined CCJREFALL echo   NOTE: the kit's bundled Java could not start (%CCJREFALL%) - using
if defined CCJREFALL echo   your normal Starsector Java and launcher instead. The game still joins.
rem Gather inputs: the window (normal), or env (CC_JOIN_DRYRUN drills, game-free; CC_JOIN_ENVLAUNCH
rem launches for real - the host's unattended kit drills).
rem :joinwindow is also the R path's way back in (:refreshmatch, exit 1: the server refused the
rem remembered inputs, no password is remembered, or the served mods differ from this install's).
rem A second entry is safe: the first pass gave its plan claim up when it read the plan
rem (:planread) and its handoff claim at the pass end, so the lines below reset the names and mint a
rem fresh plan, claim, .alive tick and nonce exactly as on the first pass; the env lanes take
rem :planenv again.
:joinwindow
if defined CC_JOIN_DRYRUN goto planenv
if defined CC_JOIN_ENVLAUNCH goto planenv
if not exist "%CCJAVA%" goto nojre
rem The plan file carries the window's answers - the match password among them - so it lives in the
rem player's own app-data folder (LOCALAPPDATA\cc-starsector\plan-<random>.txt), never in the shared
rem temp folder. No usable LOCALAPPDATA = the hand-over refusal below (:handoffbad), before any window
rem opens; its file and claim names start empty because nothing of this run is held yet. The plan's
rem random name is CLAIMED before it is used (:mintplan, in the folder made just above it), so two
rem consoles started in the same second never share one plan. The first launcher tick is written
rem here, so a folder that refuses writes is refused the same way.
set "CCHOFILE="
set "CCHOCLAIM="
set "CCPLAN="
set "CCPLANCLAIM="
set "CCHOWHY=is not set or is not an absolute path"
if not defined CCHODIR goto handoffbad
if not exist "%LOCALAPPDATA%\cc-starsector\" mkdir "%LOCALAPPDATA%\cc-starsector" >nul 2>&1
set "CCHOWHY=cannot be written"
call :mintplan || goto handoffbad
(type nul >"%CCPLAN%.alive") 2>nul || goto handoffbad
rem The joiner-only editor flow: ONE window. The join window no longer exits to hand
rem the plan over - it stays as the launch face (status line, Open editor, Close) and reads the
rem game's editor endpoint bound by a launch NONCE this bat mints here, hands to the window as an
rem argument and to the game as -Dcc.editor.launch (the JTO compose below). The window runs
rem DETACHED on javaw (a JRE without javaw falls back to java.exe = a console window, still works)
rem and this bat POLLS: the plan file (written .tmp + atomic move by the window) = Launch;
rem <plan>.cancel = the window's Cancel button (or the native window closed). NO clock: this
rem console waits as long as the player keeps it open, and every poll rewrites <plan>.alive - the
rem window's lifetime IS that tick (it leaves by itself ~60 s after this console is closed). A
rem browser tab hidden, minimized or closed changes nothing: run this bat again (or the host's
rem Joiner GUI button) and the still-live window's tab is simply re-opened.
call :mintnonce
rem GUI v2: the join WINDOW (and its loopback responder) runs on the install's
rem jre\javaw - Zulu 17, ships with the game, never Mark-of-the-Web'd; only the GAME runs on jre-cc. An
rem install without javaw keeps the kit's derivation (java.exe = a console window, still works).
rem CC_JOIN_UI=browser|swing rides to the window as -Dcc.join.ui (the browser form, or the native window).
set "CCJAVAW=%CCJAVA:java.exe=javaw.exe%"
if exist "%~dp0..\..\jre\bin\javaw.exe" set "CCJAVAW=%~dp0..\..\jre\bin\javaw.exe"
if not exist "%CCJAVAW%" set "CCJAVAW=%CCJAVA%"
set "CCUIARG="
if defined CC_JOIN_UI set "CCUIARG=-Dcc.join.ui=%CC_JOIN_UI%"
echo Opening the join window (fill it in, then Launch)...
rem "%~dp0." not "%~dp0": a trailing backslash before the closing quote would escape it.
start "" "%CCJAVAW%" %CCUIARG% -cp "%~dp0jars\cc-client-mod.jar;%~dp0..\..\starsector-core\json.jar" cc.starsector.client.join.JoinGui "%~dp0." "%CCPLAN%" %CCNONCE%
set "CCWINDOWUP=1"
:planwait
if exist "%CCPLAN%" goto planread
if exist "%CCPLAN%.cancel" goto cancelled
rem The launcher tick: the window lives while this file keeps changing (~1/s). Redirect FIRST -
rem a %TIME% ending in a digit right before > would read as a file-handle number.
>"%CCPLAN%.alive" echo %TIME%
ping -n 2 127.0.0.1 >nul
goto planwait
:planread
set "CCHOST="
set "CCPORT="
set "CCNAME2="
set "CCTOKEN="
set "CCPASSWORD="
set "CCCID="
set "CCLOGS="
set "CCSHIP="
set "CC_JOIN_TEAM="
for /f "usebackq tokens=1,* delims==" %%A in ("%CCPLAN%") do (
    if /i "%%A"=="host" set "CCHOST=%%B"
    if /i "%%A"=="port" set "CCPORT=%%B"
    if /i "%%A"=="name" set "CCNAME2=%%B"
    if /i "%%A"=="token" set "CCTOKEN=%%B"
    if /i "%%A"=="password" set "CCPASSWORD=%%B"
    if /i "%%A"=="cid" set "CCCID=%%B"
    if /i "%%A"=="logs" set "CCLOGS=%%B"
    if /i "%%A"=="ship" set "CCSHIP=%%B"
    if /i "%%A"=="team" set "CC_JOIN_TEAM=%%B"
)
del "%CCPLAN%" >nul 2>&1
del "%CCPLAN%.alive" >nul 2>&1
call :planclaimfree
goto modedetect
:planenv
rem The copies below percent-expand INSIDE set "X=...": a quote in an env value would close that
rem quote and the rest of the value would run as a command, before :modedetect ever saw it. So the
rem SOURCE names are gated first (:vguard reads a NAME under delayed expansion - the value is never
rem parsed), and the free-text pilot name is copied with its quotes already stripped.
rem The three credential SOURCE names are blanked right after their copies: from there on only
rem this bat's own copies carry the values (the rejoin loop included), so no later child process
rem of this window inherits CC_JOIN_TOKEN / CC_JOIN_PASSWORD / CC_JOIN_CID.
call :vguard CC_JOIN_HOST || goto badvalue
call :vguard CC_JOIN_PORT || goto badvalue
call :vguard CC_JOIN_TOKEN || goto badvalue
call :vguard CC_JOIN_PASSWORD || goto badvalue
call :vguard CC_JOIN_CID || goto badvalue
call :vguard CC_JOIN_LOGS || goto badvalue
call :vguard CC_JOIN_SHIP || goto badvalue
set "CCHOST=%CC_JOIN_HOST%"
set "CCPORT=%CC_JOIN_PORT%"
set "CCNAME2="
if defined CC_JOIN_NAME set "CCNAME2=%CC_JOIN_NAME:"=%"
set "CCTOKEN=%CC_JOIN_TOKEN%"
set "CCPASSWORD=%CC_JOIN_PASSWORD%"
set "CCCID=%CC_JOIN_CID%"
set "CC_JOIN_TOKEN="
set "CC_JOIN_PASSWORD="
set "CC_JOIN_CID="
set "CCLOGS=%CC_JOIN_LOGS%"
set "CCSHIP=%CC_JOIN_SHIP%"
rem The team pick rides a WINDOW launch only (the plan's team= row): the env lanes pick no team.
set "CC_JOIN_TEAM="
:modedetect
rem Same rule as the manifest gate above, now for the window/env inputs: host/port and the
rem credential trio ride a bare %VAR% into echo and into the JAVA_TOOL_OPTIONS line, so gate
rem them BEFORE the ""-tests below - an unvalidated value carrying a quote breaks out of the
rem ""-test itself. The pilot NAME is deliberately NOT charset-gated (names are free text):
rem it is stripped of quotes here and only ever expands INSIDE quotes afterwards, which is
rem what keeps an "&" in a pilot name inert. The strip is the quoted set form on purpose -
rem the bare `set CCNAME2=%CCNAME2:"=%` it replaces re-parsed the name as a command line.
call :vguard CCHOST || goto badvalue
call :vguard CCPORT || goto badvalue
call :vguard CCTOKEN || goto badvalue
call :vguard CCPASSWORD || goto badvalue
call :vguard CCCID || goto badvalue
call :vguard CCLOGS || goto badvalue
call :vguard CCSHIP || goto badvalue
rem The window's Team choice (plan row team=): ONE digit 0..2 (0 = the server picks), else refused.
call :vguard CC_JOIN_TEAM || goto badvalue
if defined CC_JOIN_TEAM if not "%CC_JOIN_TEAM%"=="0" if not "%CC_JOIN_TEAM%"=="1" if not "%CC_JOIN_TEAM%"=="2" goto badvalue
rem logs opt-out (host decision 2026-08-21): only an exact "off" (the GUI checkbox,
rem or CC_JOIN_LOGS in drills) disables logging - an absent row (old plan/env) stays on.
if /i not "%CCLOGS%"=="off" set "CCLOGS=on"
rem `if defined` tests the NAME, never the value - the ""-test form would expand it. Required:
rem on an UNDEFINED CCNAME2 (no name given) cmd leaves %CCNAME2:"=% literal and the line breaks.
if defined CCNAME2 set "CCNAME2=%CCNAME2:"=%"
if "%CCHOST%"=="" goto badinput
if "%CCPORT%"=="" goto badinput

rem Transport mode: a match file selects UDP - the normal mode. The join window fetches the host's
rem match from the server and Launch saves it as match.txt; it stays valid until the host changes
rem the match (then R at the rejoin prompt fetches the new one, :refreshmatch).
rem Runs AFTER the window so the match it just saved is picked up. match.txt.txt is the
rem Notepad save-as double-extension trap - accept it rather than burn the session on it.
set "CCMATCH="
if exist "%~dp0match.txt" set "CCMATCH=%~dp0match.txt"
if not defined CCMATCH if exist "%~dp0match.txt.txt" set "CCMATCH=%~dp0match.txt.txt"
if defined CCMATCH goto udpmode
rem No match file = the window has not fetched the host's match (or this is not a kit extract at
rem all). The TCP lane is deleted and the mod fails closed without a
rem descriptor, so a launch from here can only die in-game - hard-stop with the fix instead.
echo  *** NO MATCH YET ***
echo The join window did not fetch the match.
echo FIX: run join-cc again and press Refresh (the server must be running).
if not defined CC_JOIN_DRYRUN pause
exit /b 1
:udpmode
set "CC_PREDICT_TRANSPORT=udp"
set "CC_MATCH_DESCRIPTOR=%CCMATCH%"
echo Mode: *** UDP session *** - the server's match is saved here (match.txt).
echo   If the host changed the match, press R at the rejoin prompt: it fetches
echo   the new one from the server. If they announced a NEW kit, extract it
echo   again and run join-cc - its window fetches the match.
:modeset

rem Sound self-check (the OpenAL bundle): on some PCs a Java started by a SCRIPT cannot load the
rem game's own starsector-core\native\windows\OpenAL64.dll by the RELATIVE path the engine tries
rem (Windows error 126 "module not found" on a file that is there), while the same game started
rem from Explorer plays sound - a loader/security-policy class, not a device or Java problem. Two
rem levers ride every launch: the ABSOLUTE library dir is handed to the engine below (the
rem "-Dorg.lwjgl.librarypath" append - the engine tries that path FIRST), and the mod retries sound
rem init once at load. This block runs SoundProbe.class (next to this bat) on the SAME Java the game
rem will use, from starsector-core, in the game's own shape, and keeps its output as the sound-check
rem log the session report bundles - the host reads the cause from that log alone. Exit code 20 =
rem no load of the dll succeeds inside a script-started Java at all: the game is then handed to
rem Explorer to start (:launchexplorer). Skipped under DRYRUN. The log lives in %TEMP% (the game
rem folder under Program Files is not writable without elevation) under a name of its own for this
rem run (two consoles never share it), and is deleted at the end of the pass that bundled it. The
rem probe's copy of the library goes to a fresh folder of this run in the player's own app-data
rem folder; the probe is handed that copy only when the folder AND the copy were made, otherwise an
rem empty argument (it then reports that step as skipped). Env vars are never printed
rem by the probe; the leftover launch env of an earlier run is cleared first so no "Picked up
rem JAVA_TOOL_OPTIONS" banner can fold a credential into the log. The Java launcher's THIRD
rem options variable, JDK_JAVA_OPTIONS (its banner starts "NOTE: Picked up"), is the player's own:
rem it is blanked for the probe child alone (the one-line setlocal on the probe line), so its value
rem never reaches the log either and the game still gets it. The same line blanks the six credential
rem names first (the bracket every later game / JVM / PowerShell start uses - see the game start lines below).
for %%I in ("%~dp0..\..\starsector-core\native\windows") do set "CCOALDIR=%%~fI"
for %%I in ("%~dp0..\..") do set "CCROOT=%%~fI"
set "CCSNDRC="
set "CCSNDOSDONE="
if defined CC_JOIN_DRYRUN goto sndskip
rem (named below the dry-run jump: a dry run reads no %RANDOM% before its handoff id draws)
set "CCSNDLOG=%TEMP%\cc-sound-check-%RANDOM%%RANDOM%.log"
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
rem the kit JRE runs the game with these (kit-jvm.args); the probe mirrors them so its shape matches.
set "CCPROBEFLAGS="
if "%CCLAUNCH%"=="jre-cc" set "CCPROBEFLAGS=--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Dfile.encoding=COMPAT"
set "CCOALTMP="
set "CCOALARG="
if defined CCHODIR set "CCOALTMP=%LOCALAPPDATA%\cc-starsector\oal-%RANDOM%%RANDOM%"
if defined CCOALTMP mkdir "%CCOALTMP%" >nul 2>&1 || set "CCOALTMP="
if defined CCOALTMP copy /y "%CCOALDIR%\OpenAL64.dll" "%CCOALTMP%\OpenAL64.dll" >nul 2>&1 && set "CCOALARG=%CCOALTMP%\OpenAL64.dll"
pushd "%~dp0..\..\starsector-core"
setlocal & set "CCPASSWORD=" & set "CCTOKEN=" & set "CCCID=" & set "CC_JOIN_PASSWORD=" & set "CC_JOIN_CID=" & set "CC_JOIN_TOKEN=" & (call ) & set "JDK_JAVA_OPTIONS=" & "%CCJAVA%" %CCPROBEFLAGS% -cp "lwjgl.jar;%~dp0." -Djava.library.path=native\windows -Dorg.lwjgl.util.Debug=true SoundProbe "%CCOALDIR%" "%CCOALARG%" > "%CCSNDLOG%" 2>&1 & endlocal
set "CCSNDRC=%errorlevel%"
popd
if defined CCOALARG del "%CCOALARG%" >nul 2>&1
if defined CCOALTMP rmdir "%CCOALTMP%" >nul 2>&1
echo Sound self-check: rc=%CCSNDRC% (0 = OK; details ride the session report).
if "%CCSNDRC%"=="20" set "CCKITLAUNCH=explorer"
if "%CCSNDRC%"=="20" echo   NOTE: the game's sound library will not load into a script-started Java on
if "%CCSNDRC%"=="20" echo   this PC - starting the game the Explorer way instead (your normal launcher).
:sndskip
rem CC_JOIN_LAUNCH=explorer forces the Explorer-parent launch (host drills; a player never sets it).
if /i "%CC_JOIN_LAUNCH%"=="explorer" set "CCKITLAUNCH=explorer"
rem The first pass steps over the R path's match refresh, straight into the pass.
goto rejoin

rem --- :refreshmatch - the R path (the rejoin prompt at the pass end): before the relaunch, ask the
rem server whether the match is still current. The host may change the match while a game runs;
rem the server then refuses the old one and the in-game banner says to close the game and press R.
rem The child is the join window's headless fetch (JoinGui --refresh-match): it proves the password
rem the window REMEMBERED for this host and port - no password, id or token rides its command line,
rem and the bracket blanks the six credential names first - and saves a newer served match as
rem match.txt. Its exit code survives the bracket's endlocal (the debrief line's rule). Tested
rem DESCENDING: 10 = a new match saved - relaunch; 2 = no answer - say so, then the prompt again;
rem 1 = refused, no remembered password, or the served mods differ from this install's - the
rem window opens again (:joinwindow); 0 = the match is current - relaunch.
rem The env lanes: a dry run exits before the prompt (:dryrun). CC_JOIN_ENVLAUNCH reaches it and
rem remembers no password, so its exit 1 takes :joinwindow then :planenv, whose credential sources
rem were blanked on the first pass: that relaunch carries no password (a host drill lane only).
:refreshmatch
rem The team pick is ONE-SHOT: it rode the first pass only. R relaunches with none (the server
rem remembers the team); a refused refresh re-opens the window (exit 1), where the player picks again.
set "CC_JOIN_TEAM="
setlocal & set "CCPASSWORD=" & set "CCTOKEN=" & set "CCCID=" & set "CC_JOIN_PASSWORD=" & set "CC_JOIN_CID=" & set "CC_JOIN_TOKEN=" & (call ) & "%CCJAVA%" -cp "%~dp0jars\cc-client-mod.jar;%~dp0..\..\starsector-core\json.jar" cc.starsector.client.join.JoinGui --refresh-match "%CCHOST%" "%CCPORT%" "%~dp0." & endlocal
if errorlevel 10 if exist "%~dp0match.txt" (set "CCMATCH=%~dp0match.txt" & set "CC_MATCH_DESCRIPTOR=%~dp0match.txt")
if errorlevel 10 goto rejoin
if errorlevel 2 (
    echo The server did not answer - wait for the host, then press R again.
    goto rprompt
)
if errorlevel 1 goto joinwindow
goto rejoin

rem Rejoin loop: after the game exits (debrief done), R relaunches the game
rem with the SAME inputs - no kit re-check, no window, nothing re-entered (session vars persist
rem under setlocal; the JTO compose below is a fresh set each pass, and the credential scrub stays
rem inside the loop) - once :refreshmatch above found the match current or saved the new one.
rem Mode detection stays ABOVE the label on purpose: the refresh saves a new match as match.txt,
rem the file the game is already handed; a refused refresh re-enters the window block instead.
:rejoin
rem A REJOIN pass (the first pass's window left when the game closed) gets a FRESH
rem nonce, so a window still on its way out never takes the new game for its own. Window lane only:
rem the env-driven and dry-run lanes never mint a nonce, so it never changes their JTO (drill goldens).
if defined CCNONCE if not defined CCWINDOWUP call :mintnonce
rem Credential handoff: the match password, the install id and the seat token never ride
rem JAVA_TOOL_OPTIONS (options text resurfaces in a JVM crash file, a "Picked up" banner and, on
rem the Explorer lane, the registry). A pass with a credential writes them, one k=v row each, to a
rem fresh file named by a random 16-hex id the pass CLAIMS (:minthandoff) in the handoff folder, and
rem only that id rides to the game (-Dcc.kit.handoff below). The rows are written by an internal redirect - no
rem child process ever sees a value - with the redirect FIRST (the tick rule above); all three are
rem :vguard-gated at :modedetect, so the bare expansions are safe. No credential = no file, no id.
rem LOCALAPPDATA unset, or a folder that cannot be written (no id claimed in 16 draws, or the file
rem refused), refuses the launch (:handoffbad) - never a fallback to a -D.
rem CCHOCLAIM is reset with its two siblings: a value inherited from the caller's environment is never
rem this window's claim, and a previous pass of this window gave its own claim up at its pass end
rem (:hoclaimfree, before the R below can reach this label).
set "CCHANDOFF="
set "CCHOFILE="
set "CCHOCLAIM="
if "%CCPASSWORD%%CCCID%%CCTOKEN%"=="" goto hodone
set "CCHOWHY=is not set or is not an absolute path"
if not defined CCHODIR goto handoffbad
set "CCHOWHY=cannot be written"
if not exist "%CCHODIR%\" mkdir "%CCHODIR%" >nul 2>&1
call :minthandoff || goto handoffbad
(type nul >"%CCHOFILE%") 2>nul || goto handoffbad
if not "%CCPASSWORD%"=="" >>"%CCHOFILE%" echo(password=%CCPASSWORD%|| goto handoffbad
if not "%CCCID%"=="" >>"%CCHOFILE%" echo(cid=%CCCID%|| goto handoffbad
if not "%CCTOKEN%"=="" >>"%CCHOFILE%" echo(token=%CCTOKEN%|| goto handoffbad
:hodone
rem AUTO seat: shipId=0 = "server assigns" (password lane; the mod fails fast at
rem load if no match password is set). The WELCOME's boundShipId becomes the operative seat.
set JAVA_TOOL_OPTIONS=-Dcc.predict.host=%CCHOST% -Dcc.predict.port=%CCPORT% -Dcc.predict.shipId=0 -Dcc.predict.duplex=true
rem Only the seat-ordinal-neutral name knob rides - the old seat2Name twin died
rem with the seat-2 pin (the seat is assigned at connect; cc.match.playerName wins in the mod).
if not "%CCNAME2%"=="" set JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% "-Dcc.match.playerName=%CCNAME2%"
rem The sound fix (see the self-check block above): the engine's LWJGL tries
rem "<org.lwjgl.librarypath>\OpenAL64.dll" FIRST and the game never sets that property, so on a PC
rem whose DLL search excludes the working directory every candidate it tries is relative. An
rem absolute dir here ends that class outright. Quoted like the name: the install path has spaces.
set JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% "-Dorg.lwjgl.librarypath=%CCOALDIR%"
rem The credentials - the window's match password + minted install id, and the scripted seat-token
rem escape hatch (CC_JOIN_TOKEN in drills, or a token= row in a hand-authored plan file, meant to
rem override the match.txt seat row; the window writes none) - went into the handoff file above;
rem only the file's id rides here (a 16-hex name minted by this bat, so the unquoted append is
rem safe). No credential = no id: the options stay byte-identical (descriptor extraction).
if defined CCHANDOFF set JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% -Dcc.kit.handoff=%CCHANDOFF%
rem The initial-spawn pick (plan row ship= / CC_JOIN_SHIP). :vguard-gated above, so
rem the unquoted append is safe; empty = no -D (server default). The server canonicalizes the id against
rem its own pickable list at admit - an unlisted id spawns the match default and says so in chat.
if not "%CCSHIP%"=="" set JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% -Dcc.predict.variantId=%CCSHIP%
rem The window's Team choice (plan row team= on a window launch; cleared on R): gated to one digit
rem 0..2 at :modedetect, so the unquoted append is safe; absent or 0 = no -D (the server picks).
if defined CC_JOIN_TEAM if not "%CC_JOIN_TEAM%"=="0" set JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% -Dcc.predict.team=%CC_JOIN_TEAM%
rem logs off = the mod's true no-log mode (sideLog=none - no cc-client.log, one
rem breadcrumb row in starsector.log) AND no debrief bundle below. Default on: JTO byte-identical.
rem A TOOLING-ARMED debug watch (host decision 2026-08-24) ALWAYS keeps the mod sidelog -
rem arm-watch-join hands a per-arm REPO path over as CC_WATCH_SIDELOG (PilotWatchLog's env fallback opens
rem it) and this bat then NEVER emits sideLog=none; the tick keeps governing ONLY the Desktop report zip.
if /i "%CCLOGS%"=="off" if not defined CC_WATCH_SIDELOG set JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% -Dcc.watch.sideLog=none
rem Tooling-armed CLIENT-ONLY JVM flags (arm-watch -ClientJvmFlags -> env
rem CC_CLIENT_JVM_FLAGS, space-joined, each flag ONE token: double-quoted when it holds a space) - e.g. the flight-#5 render-thread
rem JFR. A public joiner never sets it: JTO byte-identical when undefined.
if defined CC_CLIENT_JVM_FLAGS set JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% %CC_CLIENT_JVM_FLAGS%
rem Bundled-Java fallback marker (set by the health probe above, or by :kitjrefallback on a rejoin
rem pass): unset on the normal bundled launch and on no-bundle kits, so the JTO stays byte-identical.
if defined CCKITLAUNCH set JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% -Dcc.kit.launch=%CCKITLAUNCH%
rem Launch-nonce law: the launch nonce (:mintnonce, charset-gated) binds the join window to THIS
rem game's editor endpoint - the mod writes it as the endpoint file's third field. Unset on the
rem env-driven / dry-run lanes: their JTO stays byte-identical.
if defined CCNONCE set JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% -Dcc.editor.launch=%CCNONCE%
rem Mods root: the game boots from the KIT'S OWN mods root
rem (saves\common\cc_mods = links to the host's required mods + this kit + enabled_mods.json,
rem staged by the join window on Launch). The player's mods\enabled_mods.json is never read or
rem written; the normal launcher keeps their own list. _JAVA_OPTIONS, not JTO: the JVM parses
rem it AFTER the command line, so it beats the ../mods that vmparams / kit-jvm.args carry, on
rem both launch lanes. No staged root (env/dry-run lanes, an older window) = today's ../mods.
set "CCMODSROOT=%CCROOT%\saves\common\cc_mods"
if exist "%CCMODSROOT%\enabled_mods.json" set _JAVA_OPTIONS="-Dcom.fs.starfarer.settings.paths.mods=%CCMODSROOT%"
rem The manifest tickRate carrier is RETIRED - since HANDSHAKE v3 the
rem server's rate rides WELCOME at admit (wire wins over any cached value), so no -D append
rem here. A leftover tickRate= row in an old manifest is simply ignored.
rem Every game start line below (the direct launcher, the bundled-Java start, both fallbacks and
rem the Explorer lane's script) sits inside ONE-line setlocal ... endlocal that blanks the six
rem credential names first, so the started process inherits none of them; endlocal restores this
rem bat's own copies for the rejoin loop. One line because the lines after them jump away with
rem goto (a multi-line bracket would leave its setlocal open). (call ) after the blanking sets
rem resets errorlevel: a successful start leaves the previous value in place, and endlocal leaves
rem it alone, so the errorlevel checks after a start read that start's own result.
if defined CC_JOIN_DRYRUN goto dryrun
if "%CCKITLAUNCH%"=="explorer" goto launchexplorer
if "%CCLAUNCH%"=="jre-cc" goto launchkitjre
setlocal & set "CCPASSWORD=" & set "CCTOKEN=" & set "CCCID=" & set "CC_JOIN_PASSWORD=" & set "CC_JOIN_CID=" & set "CC_JOIN_TOKEN=" & (call ) & start "" /D "%~dp0..\.." "%~dp0..\..\starsector.exe" & endlocal
goto launched
:launchexplorer
rem Explorer-parent launch shape: the sound self-check found that NO load of the game's own
rem OpenAL64.dll succeeds inside a Java this script starts (rc 20), while an Explorer-started game
rem loads it - so hand the start to Explorer itself (the install's own launcher + jre). Explorer's
rem children inherit the USER environment (HKCU\Environment), not this window's, so the launch env
rem rides the registry - for as short a time as it must: a process gets its env
rem COPIED at creation, so once the game's own java.exe exists the values are removed again (the
rem poll below - seconds, not the whole session). :envclean also runs after the debrief and at
rem every bat start, in case a crash left them behind, and puts back the player's OWN
rem JAVA_TOOL_OPTIONS, saved before ours went in. ONE script owns those values for their whole
rem life - explorer-env.ps1 (same folder): -Mode apply saves the player's value, writes ours (content
rem read from this window's environment, never a command line - the :vguard rule), reads every
rem write back and tells Explorer to re-read its block; :envclean is its -Mode clean. A PC where
rem that script cannot run or verify gets NOTHING written (exit code 1 below) and the game is
rem started directly from this window instead - never a half-applied environment.
rem The start itself is explorer-launch.ps1 (same folder): Explorer opens a SHORTCUT whose
rem "Start in" is the install folder - "explorer.exe starsector.exe" would start the launcher in
rem some other folder, where it shows an error box and never runs Java (observed). The script
rem waits (~60 s) for a NEW java.exe/javaw.exe out of the install (a game that was already open,
rem or another program's Java, never counts - the launch env must reach OUR game before it is
rem removed) and reports: 0 = up (remove the env now), 3 = nothing yet (the values stay for the
rem launch that may still come; the post-debrief :envclean or the next run removes them), any
rem other code = the Explorer way failed (the launcher sat without Java, or no script at all):
rem the env is cleaned and the game starts directly from this window instead (env inherited).
rem The mods root (_JAVA_OPTIONS) rides the same way - skipped, with a NOTE from the script, when
rem the player has their OWN _JAVA_OPTIONS user variable: it is theirs (the clean removes only a
rem value that IS the kit's cc_mods override, whatever marks sit beside it). The apply line sits in the same six-name bracket
rem as the game start lines: the script needs no credential, so it inherits none.
setlocal & set "CCPASSWORD=" & set "CCTOKEN=" & set "CCCID=" & set "CC_JOIN_PASSWORD=" & set "CC_JOIN_CID=" & set "CC_JOIN_TOKEN=" & (call ) & powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0explorer-env.ps1" -Mode apply & endlocal
if not "%errorlevel%"=="0" goto explorerfailed
set "CCLAUNCH=explorer"
echo Starting the game through Explorer (your normal launcher)...
setlocal & set "CCPASSWORD=" & set "CCTOKEN=" & set "CCCID=" & set "CC_JOIN_PASSWORD=" & set "CC_JOIN_CID=" & set "CC_JOIN_TOKEN=" & (call ) & powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0explorer-launch.ps1" & endlocal
if "%errorlevel%"=="0" goto explorerup
if "%errorlevel%"=="3" goto explorerslow
goto explorerfailed
:explorerup
call :envclean
goto launched
:explorerslow
echo   NOTE: the game has not appeared after a minute - its launch settings stay in place until
echo   this window finishes (or until the next run of this file), then they are removed.
goto launched
:explorerfailed
echo   NOTE: the Explorer way did not bring the game up - starting it directly instead.
call :envclean
set "CCLAUNCH=starsector.exe"
set "CCKITLAUNCH=explorer-fallback"
set JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% -Dcc.kit.launch=explorer-fallback
setlocal & set "CCPASSWORD=" & set "CCTOKEN=" & set "CCCID=" & set "CC_JOIN_PASSWORD=" & set "CC_JOIN_CID=" & set "CC_JOIN_TOKEN=" & (call ) & start "" /D "%CCROOT%" "%CCROOT%\starsector.exe" & endlocal
goto launched
:launchkitjre
rem Bundled-Java launch (DRAGON leg (b)): what starsector.exe does, minus starsector.exe - the JVM
rem runs from starsector-core (the args file's relative classpath/paths) on jre-cc\javaw.exe (no
rem console), reading kit-jvm.args (= the install's vmparams with documented deltas; minted by
rem the host's args-mint script). The normal launcher window still opens, so the player's saved
rem resolution applies. JAVA_TOOL_OPTIONS composed above rides exactly as before.
rem The bracket's (call ) resets errorlevel first: a successful start leaves the previous value in
rem place, so a stale non-zero from an earlier command would read as a launch failure (observed).
setlocal & set "CCPASSWORD=" & set "CCTOKEN=" & set "CCCID=" & set "CC_JOIN_PASSWORD=" & set "CC_JOIN_CID=" & set "CC_JOIN_TOKEN=" & (call ) & start "" /D "%~dp0..\..\starsector-core" "%~dp0jre-cc\bin\javaw.exe" "@%~dp0kit-jvm.args" & endlocal
if errorlevel 1 goto kitjrefallback
rem Early-death check: a ShellExecute refusal (SmartScreen blocking a
rem downloaded javaw.exe - Windows shows its own error dialog and errorlevel above catches it) or
rem a JVM that starts and dies within its first seconds (no javaw.exe alive below) both used to
rem be a SILENT no-launch. Either way the install's own launcher takes over. A javaw.exe from some
rem other program keeps this check quiet - then the old behaviour, nothing worse. ping = a 5 s
rem sleep that survives redirected stdin (timeout.exe does not - drills run with stdin closed).
echo Starting the game on the kit's bundled Java...
ping -n 6 127.0.0.1 >nul
tasklist /FI "IMAGENAME eq javaw.exe" 2>nul | find /I "javaw.exe" >nul
if errorlevel 1 goto kitjrefallback
goto launched
:kitjrefallback
echo.
echo   NOTE: the kit's bundled Java (jre-cc) did not start - using your normal Starsector
echo   launcher instead. The game still joins.
set "CCLAUNCH=starsector.exe"
rem the JTO for THIS pass is already composed - append the log marker here (rejoin passes get it
rem from the compose block via CCKITLAUNCH).
set "CCKITLAUNCH=fallback-start"
set JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% -Dcc.kit.launch=fallback-start
setlocal & set "CCPASSWORD=" & set "CCTOKEN=" & set "CCCID=" & set "CC_JOIN_PASSWORD=" & set "CC_JOIN_CID=" & set "CC_JOIN_TOKEN=" & (call ) & start "" /D "%~dp0..\.." "%~dp0..\..\starsector.exe" & endlocal
:launched
echo.
echo Starsector is starting - it connects to %CCHOST%:%CCPORT% when you select the mission.
if "%CCLAUNCH%"=="jre-cc" echo   Java: this kit's own bundled runtime (jre-cc).
if defined _JAVA_OPTIONS echo   Mods: the game loads the host's mod set from saves\common\cc_mods (your own mod list is untouched).
if not "%CCSHIP%"=="" echo   Initial ship: %CCSHIP% (if the server no longer lists it, you spawn in the match default).
echo.
echo   1. Click "Play Starsector" in the launcher window that opens.
echo   2. Missions, select "Multiplayer Test" (the text under the mission shows
echo      whether the server is reachable; select it again to refresh). When the
echo      host says GO press Play - that connects you and claims your seat.
echo.
rem Leak fix: the game (started above) holds its env snapshot already - clear the launch options
rem so no LATER child (the debrief's java -version probe echoes them as a "Picked up
rem JAVA_TOOL_OPTIONS" banner) folds the host, the pilot name or the handoff id into the session
rem report. The credentials themselves never ride these options (the handoff file). One copy of the
rem options does stay on this PC: a game that crashes writes its own crash files (hs_err_pid*.log,
rem replay_pid*.log) into starsector-core, and those record the launch options - host, port, pilot
rem name and the already-used handoff id, no credential. The session report never carries them.
set "JAVA_TOOL_OPTIONS="
rem The join window itself is the launch face on the first pass (CCWINDOWUP); a
rem REJOIN pass has no window left (it leaves when the game closes), so the face alone is started
rem with the pass's fresh nonce - AFTER the JTO clear above so it inherits no credential. Never on
rem the env-driven drill/rig launches (CC_JOIN_ENVLAUNCH; the unattended auto-launch window closes
rem itself on Launch instead).
if not defined CC_JOIN_ENVLAUNCH if not defined CCWINDOWUP call :launchface
if defined CCWINDOWUP echo   The join window stays open: it opens your variant editor once the title screen is up.
rem Windows-side half of the sound self-check (sound-check.ps1, same folder): loader policy,
rem security-product names, event counts, a direct LoadLibrary of the dll - appended to the same
rem log in the background while the game loads; the debrief bundles the log. Never blocks the
rem launch. It describes the player's PC, so it runs ONLY when there is something to diagnose
rem (the probe above did not load the library: rc not 0), ONCE per run of this file (a rejoin pass
rem adds nothing), and never with the session report off (nothing would carry its rows).
set "CCSNDOS="
if defined CCSNDRC if not "%CCSNDRC%"=="0" if /i not "%CCLOGS%"=="off" if not defined CCSNDOSDONE set "CCSNDOS=1"
if defined CCSNDOS set "CCSNDOSDONE=1"
if defined CCSNDOS setlocal & set "CCPASSWORD=" & set "CCTOKEN=" & set "CCCID=" & set "CC_JOIN_PASSWORD=" & set "CC_JOIN_CID=" & set "CC_JOIN_TOKEN=" & (call ) & start /b "" powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sound-check.ps1" -GameDir "%~dp0..\.." -Log "%CCSNDLOG%" -Launch "%CCLAUNCH%" >nul 2>&1 & endlocal
rem logs off still WAITS for game exit (the rejoin prompt below needs it) but
rem writes no bundle - no Desktop zip, no temp copies. -NoBundle is either empty or a bare
rem switch token, so the unquoted expansion is cmd-safe.
rem The debrief is the ONLY wait for the game: a PC that will not run it at all (PowerShell scripts
rem blocked) returns at once, and the pass-end delete below would then remove the handoff file
rem before the game has read it. So a failed debrief holds this window on a keypress instead
rem (its exit code survives the bracket's endlocal).
set "CCDEBRIEFARGS="
if /i "%CCLOGS%"=="off" set "CCDEBRIEFARGS=-NoBundle"
setlocal & set "CCPASSWORD=" & set "CCTOKEN=" & set "CCCID=" & set "CC_JOIN_PASSWORD=" & set "CC_JOIN_CID=" & set "CC_JOIN_TOKEN=" & (call ) & powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0joiner-debrief.ps1" -WaitForGameExit %CCDEBRIEFARGS% -GameDir "%~dp0..\.." -ServerHost "%CCHOST%" -SoundLog "%CCSNDLOG%" -Launch "%CCLAUNCH%" -KitLaunch "%CCKITLAUNCH%" & endlocal
if errorlevel 1 echo   NOTE: the session report did not run on this PC. Press any key once you have QUIT the game.
if errorlevel 1 pause >nul
rem The game is gone: delete THIS pass's handoff file if it is still there (a game that never got
rem as far as reading it), then give up the pass's claim on its id (:hoclaimfree). Only the pass's
rem own id - another console's file or claim is never touched here. The pass's sound log went into
rem its report above, so it is deleted too (a rejoin pass's report then lists no sound log) - best
rem effort: a background sound check still running re-creates it with its next row, and the start
rem sweep above removes that leftover a day later.
rem Both ways out below (R = a new pass with a fresh id, or quit) come after these lines.
if defined CCHOFILE del "%CCHOFILE%" >nul 2>&1
call :hoclaimfree
if defined CCSNDLOG del "%CCSNDLOG%" >nul 2>&1
call :envclean
rem the game is gone, so the launch face is leaving on its own (3 missed pings) - a rejoin pass starts a fresh one
set "CCWINDOWUP="
echo.
rem The rejoin prompt replaces the old pause tail. R relaunches the game at
rem %CCHOST%:%CCPORT% with nothing re-entered; the server usually holds the match open, so
rem rejoin RIGHT AWAY. Anything but an exact R (Q, choice error 255, redirected stdin) exits -
rem fail-closed against a relaunch loop no one asked for.
:rprompt
echo Game closed? You can rejoin without re-entering anything.
echo   R = rejoin now (the game relaunches and re-dials %CCHOST%:%CCPORT%)
echo   Q or close this window = quit
choice /c RQ /n /m "Press R to rejoin, or Q to quit: "
if "%errorlevel%"=="1" goto refreshmatch
exit /b 0

:dryrun
echo DRYRUN jto=%JAVA_TOOL_OPTIONS%
rem A dry run KEEPS its handoff file (the drills read it back) and prints its PATH only - never a
rem row. The for variable expands after the line is parsed, so a path with "&" or ")" stays text.
if defined CCHOFILE for %%F in ("%CCHOFILE%") do echo DRYRUN handoff=%%~F
rem It gives its CLAIM up all the same: the kept file alone stops a later mint from reusing the id.
call :hoclaimfree
echo DRYRUN jopt=%_JAVA_OPTIONS%
echo DRYRUN launch=%CCLAUNCH%
if defined CC_PREDICT_TRANSPORT (echo DRYRUN transport=%CC_PREDICT_TRANSPORT%) else echo DRYRUN transport=udp-default
if defined CC_MATCH_DESCRIPTOR (echo DRYRUN descriptor=%CC_MATCH_DESCRIPTOR%) else echo DRYRUN descriptor=none
exit /b 0

:badloc
echo This file must be inside the Starsector folder at mods\CC_Client\
echo (starsector.exe was not found two folders up from here).
pause
exit /b 1

:nojre
echo No Java found: neither this kit's own jre-cc\bin\java.exe next to this
echo file nor the game's bundled jre\bin\java.exe two folders up. Is this
echo CC_Client folder inside the mods\ folder of a full Starsector install?
pause
exit /b 1

:cancelled
echo Launch cancelled - nothing was started. Run this again when ready.
echo (If a join window was already open, its browser tab was brought back instead - use that one.)
del "%CCPLAN%" >nul 2>&1
del "%CCPLAN%.cancel" >nul 2>&1
del "%CCPLAN%.alive" >nul 2>&1
del "%CCPLAN%.tmp" >nul 2>&1
call :planclaimfree
pause
exit /b 1

:kitbad
echo.
echo  *** KIT VERSION CHECK FAILED ***
echo Your CC_Client folder does not match the kit it shipped in - a stale or
echo half-updated client cannot join the match.
echo FIX: delete the CC_Client folder from mods\, then re-extract the NEWEST
echo kit zip the host sent you and copy its CC_Client folder back in.
echo Then tell the host what happened.
if defined CC_JOIN_VERIFY_ONLY exit /b 1
if defined CC_JOIN_DRYRUN exit /b 1
pause
exit /b 1

:badinput
echo No server address/port provided - ask the host, then run this again.
if defined CC_JOIN_DRYRUN exit /b 1
pause
exit /b 1

:kitbadval
echo.
echo  *** KIT MANIFEST REJECTED ***
echo A value in kit-manifest.txt contains characters that are not allowed
echo (letters, digits, dot, underscore, colon and hyphen only). A kit like this
echo is malformed or tampered with - it was NOT used and the game was not started.
echo FIX: ask the host to re-send the kit zip, and tell them this check fired.
if defined CC_JOIN_VERIFY_ONLY exit /b 1
if defined CC_JOIN_DRYRUN exit /b 1
pause
exit /b 1

:badvalue
echo.
echo  *** INPUT REJECTED ***
echo The address, port, credential or ship id contains characters that are not allowed
echo (letters, digits, dot, underscore, colon and hyphen only, and the first character
echo must be a letter or digit - a password may not start with a hyphen). Nothing was started.
echo FIX: re-run and enter the address and port exactly as the host gave them.
if defined CC_JOIN_DRYRUN exit /b 1
pause
exit /b 1

rem --- :handoffbad - the credential handoff file could not be written (CCHOWHY names why). The
rem launch is REFUSED: a credential never falls back to riding JAVA_TOOL_OPTIONS. A partial file
rem of this pass (its own fresh id) is deleted first, and the pass's claim on that id given up.
:handoffbad
if defined CCHOFILE del "%CCHOFILE%" >nul 2>&1
call :hoclaimfree
echo.
echo Your local app-data folder (LOCALAPPDATA) %CCHOWHY%, so the match credential cannot be handed to the game. Nothing was started.
if defined CC_JOIN_DRYRUN exit /b 1
pause
exit /b 1

rem --- :vguard - charset gate for a value that will later ride a BARE %VAR% into a cmd line.
rem Takes the variable NAME, never its value: the value must not be parsed by cmd on the call
rem line either. Delayed expansion is enabled LOCALLY (setlocal/endlocal) so !VGV! is substituted
rem after the line is tokenised - an "&", "|" or ">" inside the value is then literal text, not a
rem separator - and so the rest of the bat keeps normal expansion, which matters because pilot
rem names may legitimately contain "!" and must not be mangled. Empty passes: callers test for
rem "" separately, and an absent optional row (kitStamp, token, password, cid) is not an error.
rem PIPE-FREE on purpose: the old "echo value | findstr" gate ran its left
rem side in a child cmd that parsed the value a second time, so an "&" payload RAN inside the guard
rem (a space-free one also passed it). "for /f" with every allowed character as a delimiter yields a
rem token only when a disallowed character is present; eol=x switches off the default ";" line skip
rem (x is a delimiter, so it can never lead a token). The allowed set leaves dot, underscore, colon
rem and hyphen as its only non-alphanumerics, so four tests pin the first character. Length cap
rem first: no legitimate value passes 256 characters (a DNS name tops out at 253), and "for /f"
rem stops reading its string near 8185 characters - a longer value would hide its tail from it.
:vguard
setlocal EnableDelayedExpansion
set "VGV=!%~1!"
if "!VGV!"=="" (endlocal & exit /b 0)
if not "!VGV:~256,1!"=="" (endlocal & exit /b 1)
for /f "eol=x delims=0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz._:-" %%t in ("x!VGV!") do (endlocal & exit /b 1)
set "VGF=!VGV:~0,1!"
if "!VGF!"=="." (endlocal & exit /b 1)
if "!VGF!"=="_" (endlocal & exit /b 1)
if "!VGF!"==":" (endlocal & exit /b 1)
if "!VGF!"=="-" (endlocal & exit /b 1)
endlocal & exit /b 0

rem --- :launchface - the launch face alone (JoinGui --launched) for a REJOIN pass, on
rem javaw next to the window's java.exe (no console; a JRE without javaw falls back to java.exe =
rem a console window, still works). Never auto-opens the browser: a rejoin is not an editor ask.
:launchface
set "CCJAVAW=%CCJAVA:java.exe=javaw.exe%"
if exist "%~dp0..\..\jre\bin\javaw.exe" set "CCJAVAW=%~dp0..\..\jre\bin\javaw.exe"
if not exist "%CCJAVAW%" set "CCJAVAW=%CCJAVA%"
set "CCUIARG="
if defined CC_JOIN_UI set "CCUIARG=-Dcc.join.ui=%CC_JOIN_UI%"
setlocal & set "CCPASSWORD=" & set "CCTOKEN=" & set "CCCID=" & set "CC_JOIN_PASSWORD=" & set "CC_JOIN_CID=" & set "CC_JOIN_TOKEN=" & (call ) & start "" "%CCJAVAW%" %CCUIARG% -cp "%~dp0jars\cc-client-mod.jar;%~dp0..\..\starsector-core\json.jar" cc.starsector.client.join.JoinGui --launched "%~dp0." %CCNONCE% & endlocal
set "CCWINDOWUP=1"
exit /b 0
rem --- :mintnonce - launch-nonce law: CCNONCE = 8 lowercase hex chars, each nibble a fresh
rem !RANDOM! draw under delayed expansion (a %RANDOM% in the loop would expand once). Then the
rem :vguard rule for a value that rides a bare %VAR% into a command line: an exact-shape findstr
rem gate; a miss (never seen) EMPTIES the nonce - the window then binds to no game in particular
rem (the pre-(f) shape) rather than aborting a launch over a nicety.
:mintnonce
setlocal EnableDelayedExpansion
set "HEXD=0123456789abcdef"
set "NV="
for /l %%I in (1,1,8) do (
    set /a "ND=!RANDOM! %% 16"
    for %%D in (!ND!) do set "NV=!NV!!HEXD:~%%D,1!"
)
endlocal & set "CCNONCE=%NV%"
echo(%CCNONCE%| findstr /r /c:"^[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]$" >nul || set "CCNONCE="
exit /b 0
rem --- :minthandoff - CCHANDOFF = 16 lowercase hex chars (the handoff file's name), each nibble a
rem fresh !RANDOM! draw under delayed expansion; CCHOFILE = its full path, CCHOCLAIM = its claim.
rem Its OWN variables, never :mintnonce (CCNONCE belongs to the join window). No findstr shape
rem check: a pipe runs a child cmd that would inherit the credentials, and the id is hex by
rem construction.
rem The id is CLAIMED, not merely checked. cmd seeds RANDOM from the clock SECOND its window
rem started in, so two windows of one player started in the same second draw the SAME ids. A bare
rem "does the file exist" test let both pass before either wrote, and let a second window re-mint
rem an id whose file the first window's game had already read and deleted - the first window's
rem pass-end delete then removed the second window's live file. So each draw first makes an empty
rem folder named after the id plus .claim with md, which FAILS when that folder exists: creating a
rem folder is atomic, exactly one window gets it. A refused md means another window holds the id
rem (live, or killed less than a day ago) - draw again. Only under the claim is the file checked:
rem one already there is the leftover of a window that died, so the claim is given back and the
rem draw repeats. The claim is held until the pass lets go of the id - :hoclaimfree, right after
rem each delete of the pass's file and in :dryrun - and the start-of-run sweep removes a killed
rem window's claim once it is a day old. md and rd are built into cmd: no child process runs here.
rem 16 draws without a claim (a folder md cannot create in) return exit code 1 and the caller
rem refuses the launch with its "cannot be written" line.
:minthandoff
set "CCHOTRIES=0"
:minthandoffdraw
set /a "CCHOTRIES+=1"
if %CCHOTRIES% gtr 16 goto minthandofffull
setlocal EnableDelayedExpansion
set "HEXD=0123456789abcdef"
set "HV="
for /l %%I in (1,1,16) do (
    set /a "HD=!RANDOM! %% 16"
    for %%D in (!HD!) do set "HV=!HV!!HEXD:~%%D,1!"
)
endlocal & set "CCHANDOFF=%HV%"
md "%CCHODIR%\%CCHANDOFF%.claim" 2>nul || goto minthandoffdraw
if exist "%CCHODIR%\%CCHANDOFF%.txt" goto minthandoffstale
set "CCHOCLAIM=%CCHODIR%\%CCHANDOFF%.claim"
set "CCHOFILE=%CCHODIR%\%CCHANDOFF%.txt"
exit /b 0
:minthandoffstale
rd "%CCHODIR%\%CCHANDOFF%.claim" 2>nul
goto minthandoffdraw
:minthandofffull
set "CCHANDOFF="
exit /b 1
rem --- :hoclaimfree - give up this pass's claim on its handoff id (see :minthandoff): rd removes the
rem empty claim folder, and CCHOCLAIM is cleared so nothing is released twice. Nothing claimed =
rem nothing to do.
:hoclaimfree
if defined CCHOCLAIM rd "%CCHOCLAIM%" 2>nul
set "CCHOCLAIM="
exit /b 0
rem --- :mintplan - CCPLAN = the join window's plan file, LOCALAPPDATA\cc-starsector\plan-<random>.txt
rem (CCPLANID = its random part), CCPLANCLAIM = its claim. The window writes <plan>.cancel to give
rem up, and the plan itself to launch; two consoles started in the same second draw the SAME name
rem (cmd seeds RANDOM from that second), so one window's Cancel or Launch would reach both. So the
rem name is CLAIMED the :minthandoff way: an empty plan-<random>.claim folder made with md, which
rem FAILS when that folder exists - exactly one console gets a name. A refused md means another
rem console holds the name (live, or closed less than a day ago): draw again. Under the claim, a plan
rem or cancel file already there is the leftover of a window that died: the claim is given back and
rem the draw repeats. The claim is held while this console waits for the window - :planclaimfree,
rem right after the plan files are deleted (plan read, or cancelled) - and the start-of-run sweep
rem removes a closed console's claim once it is a day old. md and rd are built into cmd: no child
rem process runs here. 16 draws without a claim return exit code 1 and the caller refuses the launch
rem with its "cannot be written" line. The caller has made the cc-starsector folder already.
:mintplan
set "CCPLANTRIES=0"
:mintplandraw
set /a "CCPLANTRIES+=1"
if %CCPLANTRIES% gtr 16 goto mintplanfull
set "CCPLANID=%RANDOM%%RANDOM%"
md "%LOCALAPPDATA%\cc-starsector\plan-%CCPLANID%.claim" 2>nul || goto mintplandraw
set "CCPLANCLAIM=%LOCALAPPDATA%\cc-starsector\plan-%CCPLANID%.claim"
set "CCPLAN=%LOCALAPPDATA%\cc-starsector\plan-%CCPLANID%.txt"
if exist "%CCPLAN%" goto mintplanstale
if exist "%CCPLAN%.cancel" goto mintplanstale
exit /b 0
:mintplanstale
call :planclaimfree
set "CCPLAN="
goto mintplandraw
:mintplanfull
set "CCPLAN="
set "CCPLANID="
exit /b 1
rem --- :planclaimfree - give up this console's claim on its plan name (see :mintplan): rd removes
rem the empty claim folder, and CCPLANCLAIM is cleared so nothing is released twice. Nothing claimed
rem = nothing to do.
:planclaimfree
if defined CCPLANCLAIM rd "%CCPLANCLAIM%" 2>nul
set "CCPLANCLAIM="
exit /b 0
rem --- :envclean - remove the Explorer-launch env (see :launchexplorer) from the user environment
rem and put back the player's own JAVA_TOOL_OPTIONS: explorer-env.ps1 -Mode clean, the SAME script
rem and registry mechanism that wrote them (the write used to go through PowerShell and this clean
rem through reg.exe - a PC whose policy refused only reg.exe kept the kit's values for good). The
rem script removes only what is OURS and is silent when nothing is. Exit code 4 = something of ours
rem is still there (the next run of this file tries again). Any other failure - PowerShell itself
rem refused - is silent: on such a PC THIS kit's apply could not have written anything either (an
rem older kit's reg.exe rows may still sit there - no secret in them; accepted, scan row 7 B-F3).
:envclean
setlocal & set "CCPASSWORD=" & set "CCTOKEN=" & set "CCCID=" & set "CC_JOIN_PASSWORD=" & set "CC_JOIN_CID=" & set "CC_JOIN_TOKEN=" & (call ) & powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0explorer-env.ps1" -Mode clean >nul 2>&1 & endlocal
if "%errorlevel%"=="4" echo   NOTE: the game's launch settings could not be removed from your user environment - run this file again to retry.
exit /b 0
