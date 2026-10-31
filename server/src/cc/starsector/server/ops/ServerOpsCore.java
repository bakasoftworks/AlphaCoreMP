package cc.starsector.server.ops;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.MatchPasswords;
import cc.starsector.server.runtime.BanSet;
import cc.starsector.server.runtime.CatalogPromotionLane;
import cc.starsector.server.runtime.CidDigest;
import cc.starsector.server.runtime.CustomVariantPersistSink;
import cc.starsector.server.runtime.OperatorCommandFile;
import cc.starsector.server.runtime.SimTickRate;

/**
 * The headless logic layer under the web console ({@link OpsConsole})
 * (the non-Swing-core extraction):
 * the Start launch vector, VPN-lease parse, server-state derivation, the set-before-start
 * {@code cc-server.properties} row editors (tick rate, match password,
 * match-shape descriptor row + selector-identity parse), operator verb lines (kick,
 * end-round/-match, sim-speed, custom-*, and the P27 ban family {@code ban} / {@code ban-src} /
 * {@code ban-cid} / {@code ban-session} / {@code unban} / {@code bans}) +
 * append-write ({@code OperatorCommandFile} grammar v0), capacity-headroom
 * math, and the byte/table formatting. Zero UI-toolkit imports by construction; the shell
 * ({@link OpsConsole}) owns the HTTP surface, timers, and process spawns and imports this
 * class one-way (the core never references the shell).
 *
 * <p>{@code --selftest} (the build-gate entry; {@code OpsSelftests --selftest} chains the same rows)
 * runs the headless logic-layer assertions (no window, safe in the build gate): verb-line
 * goldens round-tripped through the REAL {@code OperatorCommandFile.parse}, append-write
 * semantics, the state ladder (STOPPED / LISTENING / RUN / STALE / EXITED), staleness boundary,
 * headroom math, the table-row / byte-format goldens, the lease-KV parse, the Start arg-vector
 * goldens, the end-state headline/detail goldens, and the match-password sidecar editor table
 * (write content round-trips the real P1 parser).
 *
 * <p>Carved out: the Start / W10 launch
 * trio lives in {@link ServerOpsLaunch}, the window-follows-lane decision in {@link LaneFollow},
 * the selftest body in {@link ServerOpsCoreSelftest} (this class keeps the state ladder, the row
 * editors, operator brief, descriptor selector, lease, JSON and format families); a later carve moved the
 * three match-tuning rows (fortressDp / wave scale / joiner default) to {@link OpsMatchRows}, and the
 * map-size / station / backdrop apply family went to {@link OpsMatchApply}.
 */
public final class ServerOpsCore {

    /** Staleness bar: a run-phase snapshot older than this reads as a wedged server. */
    public static final long STALE_THRESHOLD_MS = 5000L;

    /** Owner mode's package root the Start button stages
     * and then launches ({@code ServeLauncher --pkg}; a DURABLE root whose {@code run/}
     * keeps every flight, with a VOLATILE {@code classes} junction into the run snapshot). Repo-relative:
     * the GUI's cwd is the repo root; {@link OpsRoots#owner} makes it absolute — every reader reads
     * that record, never this constant. */
    public static final String OPS_PKG_ROOT = "generated/ops-pkg";
    /** The owner-mode stage tool the Start chain runs first ({@link ServerOpsLaunch#stageCommand}:
     * provenance gate, serve-mods sync, run snapshot, mint, manifest; prints {@code STAGED <root>} last
     * and writes {@code <root>/stage.txt}). Its exists-check replaced the retired run-ops-serve one. */
    public static final String STAGE_TOOL = "local/mint-serve-template.ps1";
    /** W10
     * — the GUI button that launches a debug WATCH of the usual shape: the standing watch arm = the STANDING PUBLIC
     * RECIPE as ONE named script (a recipe has one verbatim home — that
     * file; this shell only points). Called with no args it is briefless ({@code -NoBrief}) and
     * arm-watch pulls the VPN lease itself. D-GUIREUSE:
     * the button passes {@code -NoOpsGui} and the PRESSING window follows the watch's own status
     * file ({@link LaneFollow.LaneLaunch#debugWatch}) — no sweep, no respawn, one window throughout. */
    public static final String WATCH_SCRIPT = "local/arm-standing-watch.ps1";
    /** Where the button's parent pwsh output lands (arm-watch detaches; the run's own log is
     * a stamped log in the watch's own output dir). */
    public static final String WATCH_LOG = "generated/ops/arm-standing-watch.out.log";
    /** The "Dev editor" button's script — boots the kit-env
     * render stage with the built client jar and opens the owner's editor tab (the Mod catalog
     * card); spawned as-is in any state (it refuses a stage already up by its own state file). */
    public static final String DEV_EDITOR_SCRIPT = "local/open-dev-editor.ps1";
    /** Where that button's pwsh output lands (appended per press). */
    public static final String DEV_EDITOR_LOG = "generated/ops/open-dev-editor.out.log";
    /**
     * The "Joiner GUI" button's script — re-opens the LIVE kit join window's browser tab (its
     * endpoint file answers api/ping), else arms a fresh join-cc.bat via arm-watch-join.ps1
     * against the current VPN lease; spawned as-is in any state. */
    public static final String JOINER_GUI_SCRIPT = "local/open-joiner-gui.ps1";
    /** Where that button's pwsh output lands (appended per press). */
    public static final String JOINER_GUI_LOG = "generated/ops/open-joiner-gui.out.log";

    /**
     * D-OPSFILE/D-GUIREUSE: the window FOLLOWS the lane it
     * launched. Each lane owns its status file, and these mirror the writers' own layouts —
     * {@code ServeLauncher}'s {@code ${PKG}/run/ops/ops-status.txt} under {@link #OPS_PKG_ROOT}
     * (the Start button runs the launcher) and
     * {@code run-pilot-render-watch.ps1} ({@code $outDir\ops-status.txt}). The command file rides
     * IN each snapshot ({@code OpsSnapshot#commandFile}), so only the status path is pinned here.
     * The press: a watch's window offered Start, launched the public lane, and kept reading
     * the WATCH's final snapshot — "MATCH ENDED" forever, on both presses.
     */
    public static final String PUBLIC_LANE = "public serve";
    public static final String PUBLIC_OPS_FILE = "generated/ops-pkg/run/ops/ops-status.txt";
    public static final String WATCH_LANE = "debug watch";
    public static final String WATCH_OPS_FILE = "generated/pilot-render-watch/ops-status.txt";
    /** How long a launched lane may take to write its first LIVE snapshot before the window
     * reports it (lease pull + kit-env asserts + engine boot ran 30–90 s on the flights). */
    public static final long LAUNCH_GRACE_MS = 240_000L;

    /** The relaunch budget the window's "life N/6" text counts against — a
     * config-broken serve crash-loops loud and stops after this many rebirths, never forever;
     * a healthy evening's host absorbs this many crashes before needing a hand restart. The
     * Start button's launcher owns the law ({@code ServeLauncher.MAX_RELAUNCHES};
     * {@code ServeLauncherContractRows} T3-budget pins the two equal). The 24 h first-client await
     * the retired pwsh vector passed rides the minted template's static {@code --client-wait-ms}
     * line. */
    public static final int START_MAX_RELAUNCHES = 5;
    /** The GUI/web bind-port field DEFAULT — a pinned loopback/dev port
     * (repo-unique, greppable). The lease pull or the operator's forwarded port replaces it
     * for public serves; 0/auto is refused by {@link ServerOpsLaunch#validateBind} (glide needs SAME-port
     * rebirth). A taken port THROWS loud at bind (knob cc.server.bindPort). */
    public static final String DEFAULT_BIND_PORT = "51337";

    public static final String TICK_RATE_KEY = "sim.tickRate";
    /** Marker the GUI keeps directly above its row so removal (60 = absent default) is clean. A host
     * file may still carry the pre-scrub form (this text + {@code " - "} + a development id), which
     * {@link #withTickRateRow} recognizes through {@link OpsMatchRows#isMarker}.
     * */
    public static final String TICK_RATE_COMMENT =
            "# sim tick rate in Hz (ServerOpsGui set-before-start; row absent = 60)";

    /** The properties row naming the standing descriptor (the stage tool
     * {@link #STAGE_TOOL} and {@code ServeLauncher} read the SAME row, no override), whose
     * {@code .password} sibling is the match-password channel. */
    public static final String MATCH_DESCRIPTOR_KEY = "match.descriptor";

    /**
     * The GUI's server-state ladder. LISTENING deliberately ignores staleness: the writer
     * blocks in {@code awaitLiveSessions} after the one lobby write, so a stale lobby stamp IS
     * the "listening, 0 joined" signal, not a wedge. GONE:
     * the snapshot's WRITER pid is dead — or is a newer, unrelated process that reused
     * the pid ({@link #writerAlive}) — with no clean {@code phase=final} write (kill fallback,
     * crash, or the no-session fuse exit). The file is a leftover, not a server: Start is
     * enabled, nothing is commandable/killable. Before this rung a dead server's lobby
     * snapshot read as LISTENING forever and greyed Start until someone deleted the file.
     * GONE is an ALARM,
     * so it fires only when the server died ON THIS SHELL'S
     * WATCH — the shell saw that pid alive, then dead ({@link WriterWatch}). A dead pid the
     * shell never saw alive (a previous session's leftover file) reads STOPPED, calmly, with
     * {@link #leftoverDetailText}: bars "-", no seat rows, Start offered.
     */
    public enum ServerState { STOPPED, LISTENING, RUN, STALE, GONE, EXITED }

    /** What Start does to the password sidecar. */
    public enum PasswordAction { KEEP, WRITE, REMOVE }

    /** The {@link #SEAT_COLUMNS} name of
     *  the cid-key cell (the Swing view + the page skip it; {@link #seatTableRow} fills it LAST). */
    public static final String CIDKEY_COLUMN = "cidkey";
    /** The {@link #SEAT_COLUMNS} name of the seat's MagicLib cell. */
    public static final String MAGIC_COLUMN = "MagicLib";
    /** The {@link #SEAT_COLUMNS} name of the MagicLib cell's why words
     *  ({@link #magicWhyWords}); model-only like {@link #CIDKEY_COLUMN} - the page shows it as a title. */
    public static final String MAGIC_WHY_COLUMN = "MagicLib why";

    /** Seats-table column set ({@link #seatTableRow} rows match it index-for-index). */
    public static final String[] SEAT_COLUMNS = {
            // "name" = the wire-announced player name (blank = none yet).
            "seat", "name", "client", "ship", "endpoint", "live",
            "tx/s", "rx/s", "tx total", "rx total", "ctrl",
            // The seat's MagicLib cell (seat.N.magic: follows |
            // reports | -; "" = a pre-seam writer) and, beside it, the host words for WHY a reporting
            // seat is not followed ("" otherwise). The why is a DATA column: the page reads it into the
            // MagicLib cell's title and has no cell of its own for it (its row template carries none).
            MAGIC_COLUMN, MAGIC_WHY_COLUMN,
            // W15 v0: the seat's FLYING custom id ("" = a stock hull);
            // the ops file's seat.N.custom row verbatim — the id is server-minted, never author text.
            "custom",
            // Every ACCEPTED not-banned id the seat holds (seat.N.customs,
            // comma-joined, server-minted) — ban/export reach a held-but-unflown custom.
            "customs",
            // The seat's cid KEY (seat.N.cidkey, up to
            // 384 hex; "" = an old writer / a blank cid) — LAST, never re-ordered: the Ban
            // player / Ban source lines carry it (F1). Model-only: both tables hide it.
            CIDKEY_COLUMN
    };

    private ServerOpsCore() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && "--selftest".equals(args[0])) {
            ServerOpsCoreSelftest.run();
            System.out.println("server-ops-core selftest PASS");
            return;
        }
        System.err.println(
                "ServerOpsCore is the headless logic layer - run with --selftest, or open"
                        + " the web console via cc.starsector.server.ops.OpsConsole.");
        System.exit(2);
    }

    // ------------------------------------------------------------ pure logic

    /**
     * One VPN lease as {@link VpnLeaseProbe.Probe#toLease} builds it (port 1..65535):
     * {@code tunIp} is the server BIND host (the TUN-side local address), {@code exitIp:port}
     * is the advertised joiner endpoint. Both halves are LEASE-VOLATILE — re-pull after any
     * VPN reconnect. The {@code -Kv} text parse it came
     * from is gone with the process.
     */
    public record VpnLease(String exitIp, int port, String tunIp) {
    }


    /**
     * Raw value of the LAST uncommented {@code sim.tickRate=} row ({@code null} when
     * absent) — last-wins matches both {@code java.util.Properties} and the harness
     * {@code Read-KvFile}; the row grammar is line-start exact (the file's own convention),
     * so the leg-2 tombstone comments that merely QUOTE the row never match.
     */
    public static String tickRateRowValue(List<String> lines) {
        String value = null;
        for (String line : lines) {
            if (line.startsWith(TICK_RATE_KEY + "=")) {
                value = line.substring(TICK_RATE_KEY.length() + 1);
            }
        }
        return value;
    }

    /**
     * The file's lines with the tick-rate row upserted. Rate 60 (the default)
     * REMOVES every uncommented row (+ the GUI's marker directly above each) — absent IS the
     * default, so the tracked file returns to its canonical bytes; a non-60 rate replaces the
     * first row in place (dropping Properties-shadowed duplicates) or appends marker + row at
     * the end. Callers validate {@code rate} first ({@link SimTickRate#parse}).
     */
    public static List<String> withTickRateRow(List<String> lines, int rate) {
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (line.startsWith(TICK_RATE_KEY + "=")) {
                if (!out.isEmpty() && OpsMatchRows.isMarker(out.get(out.size() - 1),
                        TICK_RATE_COMMENT, TICK_RATE_COMMENT + " - ")) {
                    out.remove(out.size() - 1);
                }
                if (rate != SimTickRate.DEFAULT_TICKS_PER_SECOND && !placed) {
                    out.add(TICK_RATE_COMMENT);
                    out.add(TICK_RATE_KEY + "=" + rate);
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (rate != SimTickRate.DEFAULT_TICKS_PER_SECOND && !placed) {
            out.add(TICK_RATE_COMMENT);
            out.add(TICK_RATE_KEY + "=" + rate);
        }
        return out;
    }

    /**
     * {@code key}'s value when {@code line} is that
     * key's row under {@link java.util.Properties}' key grammar — the grammar the launcher and the
     * serve read {@code cc-server.properties} with, so a console row read or rewrite acts on the
     * row the serve boots, never a shadowed twin: leading blanks ({@code ' '}, {@code '\t'},
     * {@code '\f'}) skipped; a {@code #} / {@code !} line is a comment ({@code null}); the key ends
     * at the first {@code =}, {@code :} or blank, so {@code match.descriptorX=} is ANOTHER key
     * ({@code null}); then optional blanks, at most ONE {@code =} or {@code :}, optional blanks;
     * the rest, trimmed, is the value (possibly {@code ""}). Not handled, two Properties features:
     * a {@code \}-escaped key and a {@code \} line continuation (a row folded over two lines, or
     * swallowed by the line above it). The console refuses nothing there, while the launcher and
     * the serve still read those lines with Properties itself, so such a hand-edited file can
     * resolve differently here; the console never writes either shape. Pure; {@code null} line or
     * blank key ⇒ {@code null}.
     */
    public static String propertiesRowValue(String line, String key) {
        if (line == null || key == null || key.isEmpty()) {
            return null;
        }
        int n = line.length();
        int i = 0;
        while (i < n && isPropertiesBlank(line.charAt(i))) {
            i++;
        }
        if (i < n && (line.charAt(i) == '#' || line.charAt(i) == '!')) {
            return null;
        }
        if (!line.startsWith(key, i)) {
            return null;
        }
        i += key.length();
        if (i < n) {
            char end = line.charAt(i);
            if (end != '=' && end != ':' && !isPropertiesBlank(end)) {
                return null; // a longer key (match.descriptorX) or an escaped key tail
            }
        }
        while (i < n && isPropertiesBlank(line.charAt(i))) {
            i++;
        }
        if (i < n && (line.charAt(i) == '=' || line.charAt(i) == ':')) {
            i++;
        }
        return line.substring(i).trim();
    }

    /** The blanks {@code java.util.Properties} skips around a key (its line reader's set). */
    private static boolean isPropertiesBlank(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }

    /**
     * The standing descriptor path from the properties lines — LAST uncommented
     * {@code match.descriptor} row ({@link #propertiesRowValue}'s Properties key grammar,
     * so {@code match.descriptor = x} and {@code match.descriptor:x} count
     * as the serve counts them), trimmed like the serve mint trims it. Absent/blank ⇒ {@code null}, NOT a default:
     * the Start chain REFUSES without the row (the stage tool's STOP, {@code ServeLauncher}'s
     * {@code descriptor_row} refusal), so any "default" here could only mint
     * an orphaned armed sidecar beside a path no serve will read — a stale credential that
     * silently arms password mode when the row is restored (the spec's frozen default clause is
     * refuted by that measured throw).
     */
    public static String descriptorRowValue(List<String> lines) {
        String value = null;
        for (String line : lines) {
            String row = propertiesRowValue(line, MATCH_DESCRIPTOR_KEY);
            if (row != null) {
                value = row;
            }
        }
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * The {@code <descriptor>.password} sidecar path for these {@code cc-server.properties}
     * lines — the P1 sibling law ({@code mintMatchPassword} composes the identical path, so the
     * GUI edits exactly the file the next serve re-reads); {@code null} when no descriptor row
     * exists (see {@link #descriptorRowValue} — the GUI refuses a password Start then).
     */
    public static String passwordSidecarPath(List<String> lines) {
        String descriptor = descriptorRowValue(lines);
        return descriptor == null ? null : descriptor + ".password";
    }

    /**
     * The serve's config-relative
     * path rule for a row value ({@code match.descriptor} and its sidecar family) — an absolute
     * row is itself, a relative one resolves against the CONFIG FILE's directory (never the
     * GUI's cwd: a package's row reads {@code match/match.txt}); the serve's own twins
     * ({@code ServerConfig.resolvePath} / {@code configRelative}) are bound to that JVM's
     * singleton config and take no {@code Path}, hence this copy. Pure. Row strings stay in row
     * space (option labels, the written row) — resolve ONLY at the moment of file I/O
     * ({@code descriptorSwitchNeeded} compares strings). Pinned by {@code ServerOpsCoreSelftest}
     * {@code config-relative}.
     */
    public static Path configRelative(Path configFile, String row) {
        Path p = Paths.get(row.trim());
        Path dir = configFile.toAbsolutePath().getParent();
        return (p.isAbsolute() || dir == null ? p : dir.resolve(p)).toAbsolutePath().normalize();
    }

    /**
     * quote-strip + trim the typed match password (the JoinGui paste hazards),
     * then validate the non-blank result through the REAL serve-open parser — validation is
     * single-sourced in {@link MatchPasswords#parseSidecarPassword}, so whatever this accepts
     * the door parses identically by construction. Blank ⇒ {@code ""} (token mode); a charset/
     * length violation throws with a field-facing message (a typo becomes a dialog, never an
     * armed door the joiners can't match). The message's sentence = {@link #matchPasswordRefusalText}
     * under the process's {@link OpsRoots} mode (a package names no token mode).
     */
    public static String sanitizedMatchPassword(String raw) {
        String pw = raw == null ? "" : raw.replace("\"", "").trim();
        if (pw.isEmpty()) {
            return "";
        }
        try {
            return MatchPasswords.parseSidecarPassword(
                    List.of("password=" + pw), "Match password field");
        } catch (IllegalArgumentException bad) {
            throw new IllegalArgumentException(matchPasswordRefusalText(
                    OpsRoots.get().mode() == OpsRoots.Mode.PACKAGE) + "\n(" + bad.getMessage() + ")");
        }
    }

    // ---------------------------------------------------------------------------------------
    // the stranger-text composers.
    // WHY: a packaged server is run by a STRANGER host, who has no repo, no ops-cmd, no
    // local\*.ps1, no owner-mode mint flow and no token mode (checklist pkg.setup.staleHints)
    // — seven texts still spoke owner mode's workflow. Each
    // composer takes `packaged` (callers pass OpsRoots.get().mode() == OpsRoots.Mode.PACKAGE)
    // and serves BOTH shells (the Swing window here, the web page from the same sentence), so
    // the two never diverge. The OWNER branch is the fixed owner-mode text, pinned byte for byte by
    // ServerOpsCoreSelftest ownerGoldens; the PACKAGE branch is pinned by ServerOpsCoreSelftest pkg-texts-stranger.
    // ---------------------------------------------------------------------------------------

    /** The status detail when there is no ops file yet ({@code opsFileText} = the operator-config
     *  path the shell follows; shown in owner mode only). */
    public static String noFileDetail(String opsFileText, boolean packaged) {
        return packaged ? "No server is running. Press Start server."
                : "no ops file at " + opsFileText + " - server not started (or ops knob off)";
    }

    /** The Setup admission hint (two password fields); {@code target} = the match password
     *  sidecar's file name (or the missing-row marker). */
    public static String admissionHint(String target, boolean packaged) {
        return packaged
                ? "writes " + target + " (+ .adminpassword); match 8 to 64, admin 12 to 64, never a"
                        + " common word; match password = what players type to join, admin password ="
                        + " waves + /kick (must differ); a match password is required; blank admin = none"
                : "writes " + target + " (+ .adminpassword); match 8 to 64,"
                        + " admin 12 to 64, never a common word; match password = joiners (no kit re-pack),"
                        + " admin password = F11/F12 waves + /kick (must"
                        + " differ); blank match = TOKEN mode (Start asks first); blank admin = none";
    }

    /** The v3 inline twin of {@link #admissionHint}. */
    public static String admissionInlineHint(String target, boolean packaged) {
        return packaged ? "writes " + target + "; a match password is required; blank admin = none"
                : "writes " + target + "; blank match = TOKEN mode (Start asks),"
                        + " blank admin = none";
    }

    /** The armed-door Start confirm when the selected descriptor has no {@code .fingerprint}
     *  sidecar ({@code selDescriptor} = the descriptor path as the Setup pick names it). */
    public static String armedDoorConfirmText(String selDescriptor, boolean packaged) {
        return packaged
                ? "net.doorFingerprint=true but " + selDescriptor + ".fingerprint does not exist - the"
                        + " server will REFUSE this Start (fail-closed door preflight), leaving the"
                        + " match.descriptor row switched.\nMake a joiner kit for this match first, or set"
                        + " net.doorFingerprint=false.\nStart anyway?"
                : "net.doorFingerprint=true but " + selDescriptor + ".fingerprint"
                        + " does not exist - the serve wrapper will REFUSE this Start"
                        + " (fail-closed door preflight), leaving the match.descriptor"
                        + " row switched.\nPack a kit against this descriptor first"
                        + " (local\\package-joiner-kit.ps1 stages the sidecar), or set"
                        + " net.doorFingerprint=false.\nStart anyway?";
    }

    /** The web page's Export custom help sentence: the page's Export
     *  acts on a typed Custom id first, the Swing window on the picked seat only,
     *  so the two shells no longer share one sentence; pinned by {@code page-texts-from-composers}.
     *  The SAME amended sentence {@link #customExportTip}{@code (true)}
     *  carries (an export is the host's own file; Keep for everyone lists it), with the page's typed-id-first
     *  clause kept. */
    public static String customExportTipWeb() {
        return "Saves a copy of the typed Custom id's ship, else the picked seat's flying custom ship, as a file in"
                + " variants-inbox for your own use. Players do not see it; Keep for everyone lists it.";
    }

    /** The custom Export button's tooltip (the Swing window: the picked seat's flying custom only).
     *  The package text says an export is the host's own
     *  file, and Keep for
     *  everyone is what lists a ship to players; owner mode's text is its ownerGoldens row. */
    public static String customExportTip(boolean packaged) {
        return packaged ? "Saves a copy of the picked seat's flying custom ship as a file in variants-inbox for"
                + " your own use. Players do not see it; Keep for everyone lists it."
                : "Copies the picked seat's flying custom into"
                        + " custom.exportDir (default variants-inbox/) for the variant mint flow";
    }

    /** The Unban pick prompt; {@code bansMore} = the snapshot's unlisted-ban count (0..n).
     *  The package arm points at the package's own
     *  command file ({@code run/ops/ops-cmd.txt}, {@link ServeLauncher}'s OPS_CMD_FILE under
     *  run/ops) with the verb's real grammar ({@code unban cid|src <key>},
     *  {@link OperatorCommandFile}) - an unlisted ban was otherwise unliftable from a package. */
    public static String unbanPickPrompt(int bansMore, boolean packaged) {
        if (bansMore <= 0) {
            return "Ban to lift:";
        }
        return packaged ? "Ban to lift (" + bansMore + " more not listed - type \"unban cid <key>\""
                        + " or \"unban src <address>\" into run\\ops\\ops-cmd.txt):"
                : "Ban to lift (" + bansMore + " more not listed - ops-cmd unban):";
    }

    /** The Mods panel's roots-label tooltip ({@code repoRoot} = where relative entries resolve;
     *  named in owner mode only — a stranger is never shown a repo path). */
    public static String modsRootsTip(String repoRoot, boolean packaged) {
        return packaged ? "the folders searched for mods (the mods.roots row); none named = the serve-mods"
                + " folder of this package: extract a mod there, set it to required, press Start"
                : "the mods.roots row; absent = " + String.join(";",
                        InstalledModScan.DEFAULT_ROOTS) + ". Relative entries resolve under " + repoRoot;
    }

    /** The Setup match-shape hint. Package: after a change the host presses Start, and every player's join window
     *  fetches the new match from the server it dials, so the kit stays the same across shapes and nothing is
     *  handed to the players. ONE composer - {@code OpsConsolePageHtml.SHAPE_HINT} reads it, so the served
     *  hint never drifts from this text.
     * */
    public static String shapeHint(boolean packaged) {
        return packaged ? "writes match.descriptor; after a change press Start - every player's join window"
                        + " fetches the new match (the kit stays the same)"
                : "writes match.descriptor; a different match means the joiner kit must be"
                        + " re-packed against it before remote players can join";
    }

    /** The v3 inline twin of {@link #shapeHint} (package = the package hint's short form). */
    public static String shapeInlineHint(boolean packaged) {
        return packaged ? "after a change press Start - the players' join windows fetch the new match"
                : "the descriptor Start writes - a new shape needs a re-packed kit";
    }

    /** {@link #sanitizedMatchPassword}'s refusal sentence (the parser's detail rides after it). A
     *  package names no blank/token clause: it refuses a blank match password anyway
     *  (checklist pkg.setup.passwordRequired). */
    public static String matchPasswordRefusalText(boolean packaged) {
        String head = "The match password is a short code of letters, digits and dashes, starting"
                + " with a letter or digit (" + MatchPasswords.PASSWORD_MIN_CHARS
                + " to " + MatchPasswords.PASSWORD_MAX_CHARS + " characters)";
        return packaged ? head + "." : head + "; blank the field for token mode.";
    }

    /**
     * The one-line connect info a host sends a joiner —
     * {@code exitIp:port:password} — pasted whole into the join window's autofill field.
     * Inputs are the Network panel's advertised endpoint (filled only by a VPN lease pull;
     * reads {@code "-"} before one) and the Match password field (sanitized through the SAME
     * single-source parser Start uses, so the brick can never carry a value the door refuses).
     * Throws with a field-facing message when either half is missing — password mode is the
     * public-test admission lane; a token-mode host has no brick to send.
     */
    public static String joinBrick(String advertise, String passwordFieldText) {
        String endpoint = advertise == null ? "" : advertise.trim();
        if (endpoint.isEmpty() || "-".equals(endpoint)) {
            throw new IllegalArgumentException(
                    "No advertised endpoint yet - Pull VPN lease first (the brick carries"
                            + " the ip:port joiners dial).");
        }
        String pw = sanitizedMatchPassword(passwordFieldText);
        if (pw.isEmpty()) {
            // a package host never sees "token mode"
            throw new IllegalArgumentException(OpsRoots.get().mode() == OpsRoots.Mode.PACKAGE
                    ? "The brick carries the match password - type one into the Password field first."
                    : "The brick is for password mode - set a match password first"
                            + " (blank = token mode, which has no one-line credential).");
        }
        return endpoint + ":" + pw;
    }

    /**
     * Raw value of the LAST uncommented {@code password=} row ({@code null} when absent) —
     * the lenient PREFILL read only: it mirrors the parser's row grammar minus validation, so
     * a hand-edited charset-violating value prefills AS WRITTEN and bites at Start (the
     * idiom), while writes/serves stay fail-closed through the real parser.
     */
    public static String sidecarPasswordRowValue(List<String> lines) {
        String value = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0 && "password".equals(trimmed.substring(0, eq).trim())) {
                value = trimmed.substring(eq + 1).trim();
            }
        }
        return value;
    }

    /**
     * The Start-click sidecar action for a validated field value: blank field removes a present
     * sidecar (token mode — the GUI confirms first: deleting an armed credential gets Kick-level
     * confirm weight) and is a no-op when none exists (the token lane stays byte-identical);
     * a non-blank value equal to the sidecar's current row is KEEP, so a hand-authored sidecar
     * carrying the same password keeps its bytes (the no-change-no-write idiom).
     */
    public static PasswordAction passwordStartAction(String password, boolean sidecarPresent,
            String sidecarRowValue) {
        if (password.isEmpty()) {
            return sidecarPresent ? PasswordAction.REMOVE : PasswordAction.KEEP;
        }
        return password.equals(sidecarRowValue) ? PasswordAction.KEEP : PasswordAction.WRITE;
    }

    /**
     * The exact sidecar bytes Start writes — k=v + comments, newline-terminated; the selftest
     * round-trips this through the REAL {@link MatchPasswords#parseSidecarPassword} so the
     * write shape and the serve-open parse can never drift.
     */
    public static String passwordSidecarContent(String password) {
        return "# match password (console set-before-start)\n"
                + "# joiners type this ONE password in JoinGui / join-cc.bat\n"
                // A package serve has no
                // token mode - its Start refuses a blank password (ServerOpsLaunch.PACKAGE_PASSWORD_REQUIRED).
                // A blank console field KEEPS the saved password (the old "blank the GUI field and Start" line
                // was false), so the file's removal is the only way out.
                + "# token mode (no password) is for a development serve only: DELETE this file\n"
                + "# (a blank console field keeps the saved password). A hosted package refuses to start without it.\n"
                + "password=" + password + "\n";
    }

    /**
     * The admin sidecar path for a match-password sidecar path — the
     * {@code <descriptor>.adminpassword} sibling ({@link MatchPasswords#ADMIN_SIDECAR_SUFFIX};
     * the serve's {@code mintAdminPassword} composes the identical path, so the GUI writes what
     * the door reads by construction). {@code null} in ⇒ {@code null} (no descriptor row).
     */
    public static String adminSidecarOf(String passwordSidecarPath) {
        if (passwordSidecarPath == null) {
            return null;
        }
        String stem = passwordSidecarPath.endsWith(".password")
                ? passwordSidecarPath.substring(0,
                        passwordSidecarPath.length() - ".password".length())
                : passwordSidecarPath;
        return stem + MatchPasswords.ADMIN_SIDECAR_SUFFIX;
    }

    /**
     * The typed admin password — {@link #sanitizedMatchPassword}'s quote-strip +
     * trim, validated through the REAL serve-open admin parser
     * {@link MatchPasswords#parseAdminSidecarPassword} (the admin secret rides the SAME HELLO
     * slot, so it obeys the same charset/cap; stricter still: 12 to 64 characters,
     * never a common password — it is the prize every match-password holder can guess) with its
     * own field-facing message. Blank ⇒ {@code ""} (no admin seat).
     */
    public static String sanitizedAdminPassword(String raw) {
        String pw = raw == null ? "" : raw.replace("\"", "").trim();
        if (pw.isEmpty()) {
            return "";
        }
        try {
            return MatchPasswords.parseAdminSidecarPassword(
                    List.of("password=" + pw), "Admin password field");
        } catch (IllegalArgumentException bad) {
            throw new IllegalArgumentException(
                    "The admin password is a code of letters, digits and dashes, starting"
                            + " with a letter or digit (" + MatchPasswords.ADMIN_PASSWORD_MIN_CHARS
                            + " to " + MatchPasswords.PASSWORD_MAX_CHARS + " characters, never a"
                            + " common password); blank the field for no admin seat.\n("
                            + bad.getMessage() + ")");
        }
    }

    /**
     * The exact admin sidecar bytes Start writes — the
     * {@link #passwordSidecarContent} twin (same {@code password=} row key; the FILE NAME is the
     * distinction, D2), round-tripped through the REAL parser by the selftest.
     */
    public static String adminPasswordSidecarContent(String password) {
        return "# admin password (console set-before-start)\n"
                + "# whoever joins with THIS password (typed where the match password goes) is"
                + " ADMIN:\n"
                + "# F11 skip wave, F12 pause wave, /kick. Must differ from the match password.\n"
                + "# DELETE this file for no admin seat (a blank console field keeps the saved password)\n"
                + "password=" + password + "\n";
    }

    // ---- The Start match-shape selector family ----

    /**
     * One selectable minted descriptor for the Start match-shape selector: identity
     * parsed from a {@code ccmatch.v=1} file — the shape spec ({@code match.shape}), the minted
     * {@code match.id} (what the armed door's fingerprint composite pins), and how many seats it
     * carries.
     */
    public record DescriptorInfo(String matchId, String shape, int seatCount) {
    }

    /**
     * Parse a descriptor FILE's selector identity through the REAL boot codec —
     * {@link MatchDescriptorCodec#decode} is the exact parse the serve JVM runs on the standing
     * descriptor at open, so whatever this accepts the serve boots by construction and whatever
     * it rejects never lists as a bootable shape (the {@link #sanitizedMatchPassword}
     * single-source idiom; review find wf_ce0b03d4: a hand-rolled 3-key parse drifted from the
     * codec in BOTH directions — lenient on duplicate/garbage rows the codec REJECTS, strict on
     * leading blank lines the codec accepts).
     */
    public static DescriptorInfo parseDescriptorInfo(List<String> lines) {
        MatchDescriptor descriptor = MatchDescriptorCodec.decode(lines);
        return new DescriptorInfo(descriptor.matchId(), descriptor.shape(),
                descriptor.seats().size());
    }

    /**
     * Does landing {@code selectedPath} require a {@code match.descriptor} row
     * rewrite? Trim + slash-normalized + case-insensitive both sides (Windows filesystem), so
     * re-picking the standing row — however the row spells it — never rewrites the tracked
     * file (the no-change-no-write law; this decision is CORE logic so the build gate pins the
     * default-Start byte-identity, review find wf_ce0b03d4). {@code null} selection = boot the
     * row as-is, never a switch.
     */
    public static boolean descriptorSwitchNeeded(String selectedPath, String rowValue) {
        if (selectedPath == null) {
            return false;
        }
        String row = rowValue == null ? null : rowValue.trim().replace('\\', '/');
        return !selectedPath.trim().replace('\\', '/').equalsIgnoreCase(row);
    }

    /**
     * Is the udp door fingerprint ARMED per the properties lines (last uncommented
     * {@code net.doorFingerprint=} row, case-insensitive — the wrapper's {@code -ieq} law)?
     * Best-effort pre-write warning input only: the wrapper resolves env → row → off and stays
     * the one authority; an env override is invisible here by design.
     */
    public static boolean doorFingerprintArmedRow(List<String> lines) {
        String value = null;
        for (String line : lines) {
            if (line.startsWith("net.doorFingerprint=")) {
                value = line.substring("net.doorFingerprint=".length()).trim();
            }
        }
        return "true".equalsIgnoreCase(value);
    }

    /** The selector row label: shape first (it IS the shape selector), then file + seats. */
    public static String descriptorOptionLabel(String fileName, DescriptorInfo info) {
        return info.shape() + "  -  " + fileName + "  (" + info.seatCount()
                + (info.seatCount() == 1 ? " seat)" : " seats)");
    }

    /**
     * The properties lines with the ONE {@code match.descriptor=} row set to
     * {@code descriptorPath} — the {@link #withTickRateRow} surgery (replace the first
     * uncommented row in place, drop Properties-shadowed duplicates, append when absent), a row
     * being any line {@link #propertiesRowValue} reads as the key's (a
     * spaced or {@code :} twin is dropped, never left to shadow the written row). No
     * remove branch: the row is REQUIRED standing config (the stage tool and {@code ServeLauncher}
     * refuse without it), never an optional override with a default. Backslashes normalize to
     * {@code /} so a shell path join can never fork the row's canonical byte shape.
     */
    public static List<String> withDescriptorRow(List<String> lines, String descriptorPath) {
        String path = descriptorPath == null ? "" : descriptorPath.trim().replace('\\', '/');
        if (path.isEmpty()) {
            throw new IllegalArgumentException("descriptor path must not be blank");
        }
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (propertiesRowValue(line, MATCH_DESCRIPTOR_KEY) != null) {
                if (!placed) {
                    out.add(MATCH_DESCRIPTOR_KEY + "=" + path);
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (!placed) {
            out.add(MATCH_DESCRIPTOR_KEY + "=" + path);
        }
        return out;
    }

    /**
     * The big-label headline — EXITED must read as a completed match, not a crash;
     * STALE (pid alive, snapshot frozen) = a wedge; GONE (pid dead) = died without the clean
     * {@code phase=final} write (kill fallback / crash / fuse exit).
     */
    public static String stateHeadline(ServerState state) {
        switch (state) {
            case EXITED:
                return "MATCH ENDED";
            case STALE:
                return "STALE (server wedged? snapshot stopped)";
            case GONE:
                return "SERVER GONE (no clean end - killed or crashed)";
            default:
                return state.name();
        }
    }

    /**
     * Is the process that WROTE this snapshot still alive? {@code ProcessHandle} liveness
     * plus a pid-reuse guard: a live process that STARTED after the snapshot's stamp cannot be
     * its writer (the writer's start precedes every write it makes), so a recycled pid reads
     * dead. An unreadable start time (no {@code info()} access) falls back to plain liveness.
     * Probed by both shells on EVERY snapshot (one OpenProcess per refresh tick — trivial);
     * the old "only the STALE rung probes" law is what let a dead lobby pid read LISTENING.
     */
    public static boolean writerAlive(long pid, long stampMs) {
        return ProcessHandle.of(pid).map(h -> h.isAlive()
                && h.info().startInstant().map(t -> t.toEpochMilli() <= stampMs).orElse(true))
                .orElse(false);
    }

    /** {@link #writerAlive(long, long)} over a parsed snapshot. */
    public static boolean writerAlive(OpsSnapshot snap) {
        return writerAlive(snap.pid(), snap.stampMs());
    }

    /**
     * The EXITED detail line — says explicitly that ship-vanish + frozen meters are the
     * CLEAN end shape ({@code phase=final} only ever follows the goodbye fan). {@code round}
     * is the serve loop's 0-based index, so round=N ⇒ N+1 round(s) played.
     */
    public static String finalDetailText(OpsSnapshot snap) {
        return String.format(Locale.ROOT,
                "match ended cleanly after round %d (%d round(s) played, %d frames, up %ds)"
                        + " - server exited; totals %s out / %s in."
                        + " Start server begins a new match.",
                snap.round(), snap.round() + 1, snap.frame(), snap.upSeconds(),
                bytesText(snap.bytesSentTotal()), bytesText(snap.bytesRecvTotal()));
    }

    /**
     * Phase + staleness + writer liveness → state; an unknown phase reads as no live server
     * (fail-closed). A dead writer outranks phase/staleness on every non-final rung: the
     * snapshot is a leftover file, never a server — GONE (alarm) only when it died on THIS
     * shell's watch ({@code diedOnWatch}), else STOPPED (a previous
     * session's leftover; nothing is running); {@code final} stays EXITED — the server is
     * EXPECTED to be gone after its clean end.
     */
    public static ServerState deriveState(String phase, boolean stale, boolean writerAlive,
            boolean diedOnWatch) {
        switch (phase) {
            case "lobby":
                return writerAlive ? ServerState.LISTENING
                        : diedOnWatch ? ServerState.GONE : ServerState.STOPPED;
            case "run":
                return !writerAlive ? (diedOnWatch ? ServerState.GONE : ServerState.STOPPED)
                        : stale ? ServerState.STALE : ServerState.RUN;
            case "final":
                return ServerState.EXITED;
            default:
                return ServerState.STOPPED;
        }
    }

    /**
     * The per-shell "did it die on MY watch" tracker behind the GONE alarm.
     * Remembers the last writer pid this shell saw ALIVE; a dead writer is {@code diedOnWatch}
     * only when it is that very pid. A fresh shell reading a dead pid it never saw alive (the
     * Aug-23 leftover on Pull VPN lease) answers {@code false} ⇒ STOPPED + leftover
     * detail, never the "killed or crashed" headline for a server the owner never started.
     * One instance per shell; the web shell is polled from HTTP threads, hence synchronized.
     */
    public static final class WriterWatch {
        private long lastAlivePid = -1;

        /** Observe one snapshot's writer liveness; returns whether a DEAD writer is the pid
         * this shell last saw alive. Alive ⇒ remembers the pid, answers {@code false}. */
        public synchronized boolean observe(OpsSnapshot snap, boolean alive) {
            if (alive) {
                lastAlivePid = snap.pid();
                return false;
            }
            return snap.pid() == lastAlivePid;
        }
    }

    /** The calm detail line for a dead writer this shell never saw alive — the file is
     * a previous server's leftover, nothing is running. {@code nowMs} dates the stamp. */
    public static String leftoverDetailText(OpsSnapshot snap, long nowMs) {
        return "leftover snapshot from a previous server (pid " + snap.pid()
                + " not running, stamped " + agoText(nowMs - snap.stampMs())
                + " ago) - nothing is running; Start server begins a new match";
    }

    /** Coarse elapsed text for {@link #leftoverDetailText}: "0m", "7m", "3h 5m", "2d 1h". */
    public static String agoText(long elapsedMs) {
        long minutes = Math.max(0, elapsedMs) / 60_000L;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days > 0) {
            return days + "d " + (hours % 24) + "h";
        }
        if (hours > 0) {
            return hours + "h " + (minutes % 60) + "m";
        }
        return minutes + "m";
    }

    /** Start is offered whenever no server can be running: no file, clean end, dead writer. */
    public static boolean startEnabled(ServerState state) {
        return state == ServerState.STOPPED || state == ServerState.EXITED
                || state == ServerState.GONE;
    }

    /** A process exists to command/kill: LISTENING, RUN, or STALE (alive but wedged). */
    public static boolean liveServer(ServerState state) {
        return state == ServerState.LISTENING || state == ServerState.RUN
                || state == ServerState.STALE;
    }

    /**
     * The exact operator-file line for a verb ({@code OperatorCommandFile} grammar v0).
     * {@code seatId} is required &gt; 0 for KICK / BAN / BAN_SRC (the parser rejects anything
     * else) and ignored otherwise; an arg-carrying verb throws naming its builder. KICK here is
     * the key-less form (the frame-time occupant); {@link #kickSeatLine} mints the key-bound one.
     */
    public static String verbLine(OperatorCommandFile.Verb verb, int seatId) {
        switch (verb) {
            case KICK:
                if (seatId <= 0) {
                    throw new IllegalArgumentException("kick needs a seatId > 0, got " + seatId);
                }
                return "kick " + seatId;
            case END_ROUND:
                return "end-round";
            case END_MATCH:
                return "end-match";
            case SKIP_WAVE:
                // no-arg like end-match; today's writer is the launcher's
                // F11 tail bridge — a GUI Skip-wave button would mint through here too.
                return "skip-wave";
            case SET_SIM_SPEED:
                throw new IllegalArgumentException(
                        "set-sim-speed carries a float arg - mint it via simSpeedLine(float)");
            case SET_SIM_SPEED_AUTO:
                throw new IllegalArgumentException("sim-speed-auto carries on|off - mint it"
                        + " via simSpeedAutoLine(boolean)");
            case SET_JOINER_DEFAULT:
                // args-carrying-verbs-refuse guard, the
                // SET_SIM_SPEED idiom — the joiner-default id rides joinerDefaultLine(String).
                throw new IllegalArgumentException("set-joiner-default carries a text arg -"
                        + " mint it via joinerDefaultLine(String)");
            case CUSTOM_SUBMISSIONS:
                throw new IllegalArgumentException("custom-submissions carries on|off - mint it"
                        + " via customSubmissionsLine(boolean)");
            case CUSTOM_REJECT:
                throw new IllegalArgumentException("custom-reject carries a seat arg - mint it"
                        + " via customRejectLine(int)");
            case CUSTOM_BAN:
                throw new IllegalArgumentException("custom-ban carries an id arg - mint it via"
                        + " customBanLine(String)");
            case CUSTOM_EXPORT:
                throw new IllegalArgumentException("custom-export carries an id arg - mint it via"
                        + " customExportLine(String)");
            case CUSTOM_ALLOW:
                throw new IllegalArgumentException("custom-allow carries a seat arg - mint it"
                        + " via customAllowLine(int)");
            case CUSTOM_UNBAN:
                throw new IllegalArgumentException("custom-unban carries an id arg - mint it via"
                        + " customUnbanLine(String)");
            // The three kept verbs refuse
            // here and name their minters - before this arm a console minting one hit the AssertionError.
            case CUSTOM_KEEP:
                throw new IllegalArgumentException("custom-keep carries an id arg - mint it via"
                        + " customKeepLine(String)");
            case CUSTOM_KEEP_EXPORT:
                throw new IllegalArgumentException("custom-keep-export carries a file name arg - mint it via"
                        + " customKeepExportLine(String)");
            case CUSTOM_FORGET:
                throw new IllegalArgumentException("custom-forget carries a kept ship name arg - mint it via"
                        + " customForgetLine(String)");
            // The six ship verbs refuse here the kept three's way - the two
            // the console mints name their minters, the four it never mints (the ship editor's chat verbs) say so;
            // before this arm a caller minting one hit the AssertionError default.
            case SHIP_HIDE:
                throw new IllegalArgumentException("ship-hide carries a shipped ship id arg - mint it via"
                        + " shipHideLine(String)");
            case SHIP_SHOW:
                throw new IllegalArgumentException("ship-show carries a shipped ship id arg - mint it via"
                        + " shipShowLine(String)");
            case SHIP_REMOVE:
                throw new IllegalArgumentException("ship-remove carries a ship id arg - the console mints none (its"
                        + " Hide is shipHideLine(String), its Forget kept customForgetLine(String))");
            case SHIP_RESTORE:
                throw new IllegalArgumentException("ship-restore carries a ship id arg - the console mints none (its"
                        + " Show is shipShowLine(String), its Keep customKeepExportLine(String))");
            case SHIP_KEEP:
                throw new IllegalArgumentException("ship-keep carries a custom id arg - the console mints none (its"
                        + " Keep for everyone is customKeepLine(String))");
            case SHIP_LIST:
                throw new IllegalArgumentException("ship-list answers a chat caller's own seat - the console mints"
                        + " none (it reads the ships from the status rows)");
            case BAN:
            case BAN_SRC:
                // The P27 seat bans — the KICK shape
                // (seat > 0, the parser rejects anything else); the text-carrying three refuse
                // below and point at their builders (the SET_SIM_SPEED idiom).
                // this key-less line = the HOUSE-FILE shape only; GUI/web
                // mint banSeatLine (F1).
                String banVerb = verb == OperatorCommandFile.Verb.BAN ? "ban" : "ban-src";
                if (seatId <= 0) {
                    throw new IllegalArgumentException(banVerb + " needs a seatId > 0, got " + seatId);
                }
                return banVerb + " " + seatId;
            case BANS:
                return "bans";
            case BAN_CID:
                throw new IllegalArgumentException("ban-cid carries a hex key - mint it via"
                        + " banCidLine(String)");
            case BAN_SESSION:
                throw new IllegalArgumentException("ban-session carries a session id - mint it via"
                        + " banSessionLine(long)");
            case UNBAN:
                throw new IllegalArgumentException("unban carries cid|src + a key - mint it via"
                        + " unbanLine(String, String)");
            default:
                throw new AssertionError("unhandled verb " + verb);
        }
    }

    /**
     * The exact operator-file line for {@code set-sim-speed} (mid-round announced
     * dilation — a pacing multiplier at frozen dt; terminology pin: every surface says
     * sim-speed, never "tickrate"). Out-of-range/non-finite throws HERE, mirroring the file
     * grammar's reject-never-clamp; the server additionally refuses the verb on an AWSS-only
     * serve ({@code sim_speed_refused reason=delta_lane_off}) — the surface cannot know that,
     * the refusal comes back in the server log.
     */
    public static String simSpeedLine(float simSpeed) {
        if (!Float.isFinite(simSpeed) || simSpeed < OperatorCommandFile.SIM_SPEED_MIN
                || simSpeed > OperatorCommandFile.SIM_SPEED_MAX) {
            throw new IllegalArgumentException("sim-speed must be in ["
                    + OperatorCommandFile.SIM_SPEED_MIN + ", " + OperatorCommandFile.SIM_SPEED_MAX
                    + "], got " + simSpeed);
        }
        return "set-sim-speed " + simSpeed;
    }

    /**
     * The exact operator-file line for {@code sim-speed-auto} (arm/disarm the A2/A3
     * auto sim-speed governor; every surface says sim-speed, the pin). Server-side
     * semantics the surface cannot know: arming REFUSES on an AWSS-only serve
     * ({@code sim_speed_auto_refused reason=delta_lane_off}), and ANY manual
     * {@code set-sim-speed} DISARMS the governor until an explicit re-arm.
     */
    public static String simSpeedAutoLine(boolean autoOn) {
        return autoOn ? "sim-speed-auto on" : "sim-speed-auto off";
    }

    /**
     * APPEND one verb line (+"\n") to the operator file, creating it when absent — the server
     * consumes the whole file (delete-first) each 250 ms poll, and append means two quick GUI
     * clicks inside one poll window both land instead of the second overwriting the first.
     */
    public static void appendVerb(Path commandFile, String line) throws IOException {
        Files.writeString(commandFile, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    /** Egress burden as %% of a stated uplink: file rates are BYTES/s, links are megaBITS/s. */
    public static double egressPercentOfUplink(long sentBytesPerSec, double uplinkMbps) {
        if (uplinkMbps <= 0) {
            return 0.0;
        }
        return sentBytesPerSec * 8.0 / (uplinkMbps * 1_000_000.0) * 100.0;
    }

    /** Human byte count, decimal units (network convention): {@code 6.8 MB}, {@code 580 B}. */
    public static String bytesText(long bytes) {
        if (bytes < 1_000L) {
            return bytes + " B";
        }
        if (bytes < 1_000_000L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1_000.0);
        }
        if (bytes < 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0);
    }

    /** {@link #bytesText} with the /s suffix for the window rates. */
    public static String bpsText(long bytesPerSec) {
        return bytesText(bytesPerSec) + "/s";
    }

    /** One seats-table row from a parsed seat block. */
    public static Object[] seatTableRow(OpsSnapshot.SeatRow s) {
        return seatTableRow(s, false);
    }

    /** {@code matchOver} renders liveness as "ended" — a completed match, not a dead peer. */
    public static Object[] seatTableRow(OpsSnapshot.SeatRow s, boolean matchOver) {
        return new Object[] {
                s.seatId(), s.name(), s.clientId(), s.ship(), s.endpoint(),
                matchOver ? "ended" : (s.live() ? "live" : "dead"),
                bpsText(s.txBps()), bpsText(s.rxBps()),
                bytesText(s.txBytes()), bytesText(s.rxBytes()),
                s.ctrlDepth(),
                s.magic() == null ? "" : s.magic(),
                magicWhyWords(s.magic(), s.magicWhy()),
                s.custom() == null ? "" : s.custom(),
                s.customs() == null ? "" : s.customs(),
                s.cidKey() == null ? "" : s.cidKey()
        };
    }

    /**
     * The host words for the {@link #MAGIC_WHY_COLUMN} cell - WHY a seat whose
     * game reports its MagicLib states ({@code magic} = {@link OpsStatusFile#MAGIC_REPORTS}) is not followed, from the
     * status file's {@code seat.N.magic_why} token (the serve's own FOLLOW predicate wrote it; this only words it).
     * {@code ""} for a followed seat, a seat that reports nothing, a pre-seam writer, or a token without host words
     * ({@code no_up} cannot pair with {@code reports}: that seat's game sent no MagicLib flag).
     */
    static String magicWhyWords(String magic, String magicWhy) {
        if (!OpsStatusFile.MAGIC_REPORTS.equals(magic) || magicWhy == null) {
            return "";
        }
        return switch (magicWhy) {
            case cc.starsector.server.runtime.MagicSeamFollow.WHY_HOST_OFF -> "host switch off";
            case cc.starsector.server.runtime.MagicSeamFollow.WHY_NOT_APPLIED -> "MagicLib unchanged on the server";
            case cc.starsector.server.runtime.MagicSeamFollow.WHY_DOOR_UNARMED -> "the mod check is off for this match";
            default -> "";
        };
    }

    /** The MATCH line's shared head ({@link #magicSeamLineText}). */
    static final String MAGIC_SEAM_LINE_HEAD = "MagicLib abilities follow the pilot: ";

    /**
     * The ops GUI's MATCH-line sentence from the status file's
     * {@code magic_seam=} receipt ({@link OpsSnapshot#magicSeam}): {@code applied:<hex>} = on; {@code off} = off
     * (host switch); {@code disarmed:<reason>} = off, in HOST words ({@link #magicDisarmWords}: the rewriter's token
     * is a developer receipt, never a sentence a host can act on); {@code armed} /
     * {@code unset} = off (MagicLib not in this match: the seam never met a MagicLib to change). {@code ""} - no
     * line - for no snapshot, a pre-seam writer (no key) or a receipt outside that set. WHY the receipt alone: it
     * is the serve's own record of what happened to MagicLib at its boot; whether each seat is followed is the
     * per-seat cell's business.
     */
    static String magicSeamLineText(OpsSnapshot snap) {
        String receipt = snap == null ? "" : snap.magicSeam();
        if (receipt.startsWith(cc.starsector.server.transform.TransformRegistry.MAGIC_SEAM_RECEIPT_APPLIED_PREFIX)) {
            return MAGIC_SEAM_LINE_HEAD + "on";
        }
        if (cc.starsector.server.transform.TransformRegistry.MAGIC_SEAM_RECEIPT_OFF.equals(receipt)) {
            return MAGIC_SEAM_LINE_HEAD + "off (host switch)";
        }
        if (receipt.startsWith(cc.starsector.server.transform.TransformRegistry.MAGIC_SEAM_RECEIPT_DISARMED_PREFIX)) {
            String reason = receipt.substring(
                    cc.starsector.server.transform.TransformRegistry.MAGIC_SEAM_RECEIPT_DISARMED_PREFIX.length());
            return MAGIC_SEAM_LINE_HEAD + "off (" + magicDisarmWords(reason) + ")";
        }
        if (cc.starsector.server.transform.TransformRegistry.MAGIC_SEAM_RECEIPT_ARMED.equals(receipt)
                || OpsStatusFile.MAGIC_SEAM_UNSET.equals(receipt)) {
            return MAGIC_SEAM_LINE_HEAD + "off (MagicLib not in this match)";
        }
        return "";
    }

    /**
     * The host words for a {@code disarmed:<reason>} receipt (the MagicLib seam plan's released text, the ops GUI
     * match line). The rewriter's reasons fall in two families: the
     * MagicLib build was not one the seam knows ({@code window_absent} / {@code window_repeated} = the fork window
     * is not where the known builds put it; {@code not_allowlisted} = a build outside the verified set) = "version
     * not recognised", the host's action is a MagicLib the seam knows; everything else ({@code hook_*},
     * {@code call_count}, {@code self_check}, {@code error:<X>}) = the serve's own check failed, the token kept
     * (bounded) because it names the failing step for a bug report. The tokens are literals here, not the rewriter's
     * {@code MagicSeamRewriter.REASON_*} constants: server/compat is compiled into cc-premain.jar only, never onto the
     * ops GUI's classpath (server/build.ps1 $CompatClasses); the status-file token is already a string contract the
     * console selftest pins. Pure - locked by that selftest.
     */
    static String magicDisarmWords(String reason) {
        return switch (reason) {
            case "window_absent", "window_repeated", "not_allowlisted" -> "MagicLib version not recognised";
            default -> "the server's MagicLib check failed: " + PeerText.bound(reason, 64);
        };
    }

    /** The MATCH line's player-ship head ({@link #pilotShipLineText}). */
    static final String PILOT_SHIP_LINE_HEAD = "Each pilot's ship counts as the player's: ";

    /**
     * The ops GUI's second MATCH-line sentence, from the status file's
     * {@code pilot_ship=} receipt ({@link OpsSnapshot#pilotShip}), the {@link #magicSeamLineText} mirror:
     * {@code applied:<k>} = on, with k (the script classes the premain redirected so far - vanilla script classes
     * the redirect rewrites count, the engine's own ship-class wrap does not - a non-negative count; an unreadable
     * count still reads on, the prefix alone being the receipt's "live" test); {@code armed} = on with no script
     * class redirected yet; {@code off} = off; {@code refused:<reason>} = off because the start-time check refused
     * the switch, in host words ({@link #pilotShipRefusedWords}); {@code latched:<reason>} = off because the window
     * switched itself off after a fault this match (every latch reason reads the same: the host's action is one).
     * The refused branch is unreachable today - every refusal ends the serve's JVM before it writes a status file
     * (the start-time gate refuses the launch, the load-time wrap halts) - and stays so a future refusal that
     * does not halt reads in host words, never a raw token. {@code ""} - no line - for no snapshot, an older
     * writer (no key), {@code unset} (nothing decided the switch, e.g. a serve without the premain) or a receipt
     * outside that set. WHY no seat column: the window marks each seat's hull automatically, so the match-level
     * receipt is the whole story.
     */
    static String pilotShipLineText(OpsSnapshot snap) {
        String receipt = snap == null ? "" : snap.pilotShip();
        if (receipt.startsWith(cc.starsector.server.transform.TransformRegistry.PILOT_SHIP_RECEIPT_APPLIED_PREFIX)) {
            String k = receipt.substring(
                    cc.starsector.server.transform.TransformRegistry.PILOT_SHIP_RECEIPT_APPLIED_PREFIX.length());
            if (k.isEmpty() || k.length() > 9 || !k.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return PILOT_SHIP_LINE_HEAD + "on";
            }
            int n = Integer.parseInt(k);
            return PILOT_SHIP_LINE_HEAD + (n == 0 ? "on (no script class yet)"
                    : n == 1 ? "on (1 script class)" : "on (" + n + " script classes)");
        }
        if (cc.starsector.server.transform.TransformRegistry.PILOT_SHIP_RECEIPT_ARMED.equals(receipt)) {
            return PILOT_SHIP_LINE_HEAD + "on (no script class yet)";
        }
        if (cc.starsector.server.transform.TransformRegistry.PILOT_SHIP_RECEIPT_OFF.equals(receipt)) {
            return PILOT_SHIP_LINE_HEAD + "off";
        }
        if (receipt.startsWith(cc.starsector.server.transform.TransformRegistry.PILOT_SHIP_RECEIPT_REFUSED_PREFIX)) {
            String reason = receipt.substring(
                    cc.starsector.server.transform.TransformRegistry.PILOT_SHIP_RECEIPT_REFUSED_PREFIX.length());
            return PILOT_SHIP_LINE_HEAD + "off (the start-time check refused the switch: "
                    + pilotShipRefusedWords(reason) + ")";
        }
        if (receipt.startsWith(cc.starsector.server.transform.TransformRegistry.PILOT_SHIP_RECEIPT_LATCHED_PREFIX)) {
            return PILOT_SHIP_LINE_HEAD + "off (switched off for this match after a fault)";
        }
        return "";
    }

    /**
     * The host words for a {@code refused:<reason>} player-ship receipt. The
     * refusal reasons fall in two families: the engine's ship code is not the one the window was built against
     * ({@code ship_fingerprint} / {@code golden_mismatch} from the start-time gate; {@code not_target},
     * {@code self_check}, {@code window_repeated}, {@code window_absent} / {@code class_version} from the load-time
     * wrap) = "game version not recognised", the host's action is a server that matches the game or the switch off;
     * the server's own helper classes are absent or the wrong shape ({@code holder_missing} / {@code holder_abi}) =
     * "server files incomplete", the host's action is a whole server install. Any other token ({@code error:<X>}
     * from either step included) is kept (bounded) because it names the failing step for a bug report. The tokens
     * are spelled where the premain publishes them (no constant per token exists to share). Pure - locked by the
     * console selftest.
     */
    static String pilotShipRefusedWords(String reason) {
        return switch (reason) {
            case "ship_fingerprint", "golden_mismatch", "not_target", "self_check", "window_repeated",
                    "window_absent", "class_version" -> "game version not recognised";
            case "holder_missing", "holder_abi" -> "server files incomplete";
            default -> PeerText.bound(reason, 64);
        };
    }

    /**
     * W15 v0: the one-line customs summary the shells paint from the ops file's
     * {@code custom.*} globals ({@code OpsStatusFile} writes fixed keys, zeros when the lane is
     * OFF); {@code null} snapshot = the no-snapshot placeholder. Pure — locked by the selftest.
     * A writer that carries {@code custom.cap_global} adds the headroom pair
     * ("N pending, F/C free" — F = cap − accepted − pending, the ledger's gate arithmetic; a ban
     * frees nothing in v0); a pre-(e) file keeps the original shape byte-for-byte.
     */
    public static String customsSummaryText(OpsSnapshot snap) {
        if (snap == null) {
            return "customs: -";
        }
        String headroom = snap.customCapGlobal() <= 0 ? ""
                : ", " + snap.customPending() + " pending, "
                        + Math.max(0, snap.customCapGlobal() - snap.customCount() - snap.customPending())
                        + "/" + snap.customCapGlobal() + " free";
        return "customs: " + snap.customCount() + " accepted, " + snap.customRejected()
                + " rejected, " + snap.customBanned() + " banned" + headroom + " - submissions "
                + (snap.customSubmissions() ? "on" : "off");
    }

    /**
     * The second customs line — WHY customs fail (the rejected split), the
     * serve's policy knobs and the export readback; the Swing label's tooltip and the web card's
     * detail row. {@code null} snapshot or a pre-(e) writer (no {@code custom.cap_global}) = "-".
     */
    public static String customsDetailText(OpsSnapshot snap) {
        if (snap == null || snap.customCapGlobal() <= 0) {
            return "-";
        }
        String detail = "rejected: policy " + snap.customRejectedPolicy() + ", caps " + snap.customRejectedCaps()
                + ", engine " + snap.customRejectedEngine() + ", operator " + snap.customRejectedOperator()
                + " - policy " + snap.customTier() + ", " + snap.customPerSeat() + "/seat, s-mods "
                + snap.customSModCap() + ", d-mods " + snap.customDMods()
                + " - exported " + snap.customExported() + ", export failed " + snap.customExportFailed();
        if (snap.customFrameEncodeFailed() > 0 || snap.customVsubDecodeRefused() > 0) {
            // The R3b refusal meters, shown only once one fired (a clean serve and a
            // pre-R3b writer keep the line byte-identical).
            detail += " - frame encode failed " + snap.customFrameEncodeFailed() + ", vsub decode refused "
                    + snap.customVsubDecodeRefused();
        }
        return snap.customKeptKnown() ? detail + keptDetailText(snap) : detail;
    }

    /**
     * The kept-store tail of {@link #customsDetailText} - only when
     * the snapshot carries {@code custom.kept} (a pre-kept writer's text stays byte-identical): the kept count
     * over {@link CatalogPromotionLane#KEPT_CAP} + its ids, the over-cap words (a capped list is said, never a
     * silent subset), the inbox names not kept yet, and the writer's last verdict. Ids
     * and the verdict are the status file's own ({@code OpsStatusFile} bounds each), as given. Pure.
     */
    private static String keptDetailText(OpsSnapshot snap) {
        StringBuilder sb = new StringBuilder(" | kept for everyone: ").append(snap.customKept()).append('/')
                .append(CatalogPromotionLane.KEPT_CAP);
        sb.append(snap.customKept() > 0 ? ": " + String.join(", ", snap.customKeptIds()) : " (none)");
        if (snap.customKeptOverCap()) {
            sb.append(" - over cap, the first ").append(CatalogPromotionLane.KEPT_CAP).append(" listed");
        }
        List<String> inbox = snap.customInboxIds();
        sb.append(" | exported, not kept yet: ").append(inbox.size());
        if (!inbox.isEmpty()) {
            sb.append(": ").append(String.join(", ", inbox));
        }
        if (snap.customInboxOverCap()) {
            sb.append(" - over cap");
        }
        if (!"-".equals(snap.customKeptLast())) {
            sb.append(" | last: ").append(snap.customKeptLast());
        }
        return sb.toString();
    }

    /** {@code custom-submissions on|off} (W15 v0 step 3(f) grammar; the sim-speed-auto shape). */
    public static String customSubmissionsLine(boolean on) {
        return "custom-submissions " + (on ? "on" : "off");
    }

    /** {@code custom-reject <seat>}: drops the seat's PENDING deposit (the kick shape, seat &gt; 0). */
    public static String customRejectLine(int seatId) {
        if (seatId <= 0) {
            throw new IllegalArgumentException("custom-reject needs a seatId > 0, got " + seatId);
        }
        return "custom-reject " + seatId;
    }

    /** {@code custom-ban <id>}: the id passes the set-joiner-default token law HERE (the
     * {@link OpsMatchRows#parseJoinerDefault} two-channel idiom); the ledger answers an unknown
     * id loud in the server log, never this minter. */
    public static String customBanLine(String id) {
        return "custom-ban " + customIdToken(id);
    }

    /** {@code custom-export <id>}: copies the accepted custom into {@code custom.exportDir}. */
    public static String customExportLine(String id) {
        return "custom-export " + customIdToken(id);
    }

    /** {@code custom-allow <seat>}: the inverse of custom-reject (seat &gt; 0). */
    public static String customAllowLine(int seatId) {
        if (seatId <= 0) {
            throw new IllegalArgumentException("custom-allow needs a seatId > 0, got " + seatId);
        }
        return "custom-allow " + seatId;
    }

    /** {@code custom-unban <id>}: the inverse of custom-ban (the same token law). */
    public static String customUnbanLine(String id) {
        return "custom-unban " + customIdToken(id);
    }

    /** {@code custom-keep <id>} - keep this run's accepted custom
     * (a seat's flying {@code cc-v...} id) for every player, live and across restarts;
     * the id passes the custom-ban token law HERE (trimmed,
     * blank / null refused); the server answers an unknown id on the customs line, never this minter. */
    public static String customKeepLine(String id) {
        return "custom-keep " + customIdToken(id);
    }

    /** {@code custom-keep-export <name>} - keep a file of the
     * variants inbox: an earlier export ({@code cc-v...}) or a forgotten kept ship ({@code kept-...}); the
     * inbox pick names the file explicitly, never a ledger lookup. The same token law
     * plus one of those two prefixes ({@link CatalogPromotionLane#INBOX_PREFIX} /
     * {@link CatalogPromotionLane#INBOX_KEPT_PREFIX}, the scan's own inbox-list law). */
    public static String customKeepExportLine(String name) {
        String token = customIdToken(name);
        if (!token.startsWith(CatalogPromotionLane.INBOX_PREFIX)
                && !token.startsWith(CatalogPromotionLane.INBOX_KEPT_PREFIX)) {
            throw new IllegalArgumentException("custom-keep-export needs an exported ship's name ("
                    + CatalogPromotionLane.INBOX_PREFIX + "... or " + CatalogPromotionLane.INBOX_KEPT_PREFIX + "...)");
        }
        return "custom-keep-export " + token;
    }

    /** {@code custom-forget
     * <stem>} - take one kept ship off {@code /ship} (its files move back to the inbox, never deleted); the same token
     * law plus the kept SCAN's own admission law ({@link CatalogPromotionLane#stemLawful} and not a reserved
     * {@code cc-v} id - the scan's {@code scanKept}): a host-dropped kept file lists WITHOUT the {@code kept-} prefix
     * and must be forgettable from both consoles (the server's {@code forget} admits any listed lawful stem); a
     * {@code cc-v} id is refused here exactly as the scan refuses it. */
    public static String customForgetLine(String stem) {
        String token = customIdToken(stem);
        if (!keptStemLawful(token)
                || token.toLowerCase(java.util.Locale.ROOT).startsWith(CatalogPromotionLane.INBOX_PREFIX)) {
            throw new IllegalArgumentException("custom-forget needs a listed kept ship's name (not a "
                    + CatalogPromotionLane.INBOX_PREFIX + "... id)");
        }
        return "custom-forget " + token;
    }

    /** {@code ship-hide <id>} - take a ship that comes with the server off
     * {@code /ship} for every player, live and at every start, until {@link #shipShowLine}; the minted token passes
     * {@link #shippedIdToken} (trimmed, blank / null / unlawful / {@code cc-v} refused). The server answers an id
     * that is not one of its shipped ships on its {@code ships.last} row, never this minter. */
    public static String shipHideLine(String id) {
        return "ship-hide " + shippedIdToken("ship-hide", id);
    }

    /** {@code ship-show <id>} - the inverse of {@link #shipHideLine}, the same
     * token law. */
    public static String shipShowLine(String id) {
        return "ship-show " + shippedIdToken("ship-show", id);
    }

    /** The shipped-id token law of the two ship verbs: the custom-verb token law ({@link #customIdToken}), then the
     *  kept scan's stem law ({@link #keptStemLawful}, which every shipped variant id passes) and NOT a {@code cc-v}
     *  id - a player's flown custom is never a ship that comes with the server, so the refusal lands before a line
     *  exists (the {@link #customForgetLine} shape). */
    private static String shippedIdToken(String verb, String id) {
        String token = customIdToken(id);
        if (!keptStemLawful(token)
                || token.toLowerCase(Locale.ROOT).startsWith(CatalogPromotionLane.INBOX_PREFIX)) {
            throw new IllegalArgumentException(verb + " needs the name of a ship that comes with the server (not a "
                    + CatalogPromotionLane.INBOX_PREFIX + "... id)");
        }
        return token;
    }

    /** A verbatim twin of {@code CatalogPromotionLane.stemLawful} (the kept scan's admission law: an ASCII letter /
     *  digit first, then {@code [A-Za-z0-9._-]}, at most 64 chars) + the scan's reserved-id refusal (a {@code cc-v}
     *  prefix, case-folded) in the caller. WHY a twin: the console JVM runs on {@code server\classes} alone and a call
     *  into that runtime class links {@code org.json} (measured as a {@code NoClassDefFoundError} in
     *  the server-ops build gate); drift is pinned by the {@code keep-forget-minters} golden
     *  {@code custom-forget my_wolf} + its {@code bad id!} refusal. */
    private static boolean keptStemLawful(String s) {
        if (s == null || s.isEmpty() || s.length() > 64 || !keptStemChar(s.charAt(0), true)) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!keptStemChar(s.charAt(i), false)) {
                return false;
            }
        }
        return true;
    }

    private static boolean keptStemChar(char c, boolean first) {
        boolean alnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
        return alnum || (!first && (c == '.' || c == '_' || c == '-'));
    }

    /** The Swing Forget kept pick list (and the page's kept list)
     * = the status file's {@code custom.kept_ids} as written; empty without a snapshot. */
    public static List<String> keptPickRows(OpsSnapshot snapOrNull) {
        return snapOrNull == null ? List.of() : snapOrNull.customKeptIds();
    }

    /** The Swing Keep pick list when no seat is picked (and the
     * page's "Exported, not kept yet" list) = the status file's {@code custom.inbox_ids}; empty without one. */
    public static List<String> inboxPickRows(OpsSnapshot snapOrNull) {
        return snapOrNull == null ? List.of() : snapOrNull.customInboxIds();
    }

    /** The page's "Ships that come with the server" rows = the status file's
     * {@code ships.base_ids} as written (hidden ones included); empty without a snapshot. */
    public static List<String> shippedPickRows(OpsSnapshot snapOrNull) {
        return snapOrNull == null ? List.of() : snapOrNull.shipsBaseIds();
    }

    /** The hidden ones among them (a Show per row) = the status file's
     * {@code ships.hidden_ids}; empty without a snapshot. */
    public static List<String> hiddenPickRows(OpsSnapshot snapOrNull) {
        return snapOrNull == null ? List.of() : snapOrNull.shipsHiddenIds();
    }

    /** The kept-ship texts, ONE home for both shells (the designed
     * texts verbatim, except {@link #CUSTOM_KEEP_TIP_WEB}'s typed-id clause and
     * {@link #NOTHING_TO_KEEP_TEXT}, written beyond the design). */
    public static final String CUSTOM_KEEP_LABEL = "Keep for everyone";
    public static final String CUSTOM_FORGET_LABEL = "Forget kept";
    public static final String CUSTOM_KEEP_TIP = "Keeps the picked seat's flying custom ship for every player:"
            + " it joins the /ship list within seconds and at every start, until you forget it. With no seat"
            + " picked, choose an exported ship.";
    public static final String CUSTOM_KEEP_TIP_WEB = "Keeps the typed Custom id, else the picked seat's flying"
            + " custom ship, for every player: it joins the /ship list within seconds and at every start, until"
            + " you forget it. With neither, choose an exported ship in the list under the seats.";
    public static final String CUSTOM_FORGET_TIP = "Takes one kept ship off the /ship list; its file moves back"
            + " to variants-inbox. A player flying it keeps it until their next respawn.";
    public static final String NO_KEPT_TEXT = "No kept ships yet.";
    public static final String NOTHING_TO_KEEP_TEXT = "pick a seat flying a custom ship, or export one first"
            + " - nothing to keep";

    /** The Keep confirm, {@code id} = the minted
     * token (a seat's custom id or an inbox file name). */
    public static String keepConfirmText(String id) {
        return "Keep '" + id + "' for every player? It joins the /ship list now and at every start, until you"
                + " press Forget kept.";
    }

    /** The Forget confirm, {@code stem} = the
     * minted kept stem. */
    public static String forgetConfirmText(String stem) {
        return "Forget '" + stem + "'? It leaves /ship now; its file moves back to variants-inbox.";
    }

    /** The shipped-ships texts, ONE home for the page (its heading and empty
     * line are typed in the HTML and its script, each pinned to these by the page rows); every one is stranger
     * wording, pinned by the {@code pkg-texts-stranger} goldens. */
    public static final String SHIPS_TITLE = "Ships that come with the server";
    public static final String SHIPS_EMPTY_TEXT = "Start the server to see its ships.";
    public static final String SHIP_HIDE_TITLE = "Hide ship";
    public static final String SHIPS_SAVE_FAILED_TEXT = "The hidden list could not be saved, so a change may not"
            + " last past a restart. Failed saves: ";

    /** The Hide confirm, {@code id} = the minted shipped id. Show asks
     * nothing (the unban idiom). */
    public static String shipHideConfirmText(String id) {
        return "Hide '" + id + "' from every player? It leaves the /ship list now and at every start, until you press"
                + " Show. A player already flying it, or whose seat's default ship it is, keeps it.";
    }

    /** {@code ban-cid <key>} — the key is
     * normalized HERE by the file parser's own law ({@link BanSet#cidBanKeyOrNull}, mirrored so a bad
     * key is refused before it lands in the command file). Either
     * player-key form ({@link BanSet#CID_KEY_FORM}); the line carries the digest, the key a listing prints. */
    public static String banCidLine(String key) {
        String canon = BanSet.cidBanKeyOrNull(key);
        if (canon == null) {
            throw new IllegalArgumentException("ban-cid needs " + BanSet.CID_KEY_FORM);
        }
        return "ban-cid " + canon;
    }

    /** The GUI/web seat ban — {@code ban N <key>} /
     * {@code ban-src N <key>}, the key = the snapshot's {@code cidKey} for that seat (the
     * operator's EXPECTED occupant; the server refuses {@code target_changed} when another cid
     * now holds the seat — F1). Folded + {@link BanSet#isCidKey}-checked like
     * {@link #banCidLine}; an EMPTY key throws for BOTH verbs (an old writer or a blank cid —
     * a key-less {@code ban-src} from a surface would re-open F1 on the CGNAT-collateral verb).
     * The key is never echoed into the message (it arrives from a form). */
    public static String banSeatLine(OperatorCommandFile.Verb banOrBanSrc, int seatId,
            String cidKeyHex) {
        if (banOrBanSrc != OperatorCommandFile.Verb.BAN
                && banOrBanSrc != OperatorCommandFile.Verb.BAN_SRC) {
            throw new IllegalArgumentException("banSeatLine mints ban / ban-src only, got "
                    + banOrBanSrc);
        }
        String head = verbLine(banOrBanSrc, seatId); // the seat > 0 law
        String key = cidKeyHex == null ? "" : cidKeyHex.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("seat " + seatId + " has no client-id key in the"
                    + " last snapshot (an older server, or a blank client id) - nothing sent");
        }
        if (!BanSet.isCidKey(key)) {
            throw new IllegalArgumentException(head.substring(0, head.indexOf(' '))
                    + " needs the seat's hex cid key (even length, 2.."
                    + (2 * BanSet.CID_MAX_BYTES) + " chars of 0-9a-f) - nothing sent");
        }
        return head + " " + key;
    }

    /** The GUI/web seat kick —
     * {@code kick N <key>}, the key = the snapshot's {@code cidKey} for that seat (the operator's
     * EXPECTED occupant; the door refuses {@code target_changed} when another cid now holds the
     * seat). {@code seatId} &gt; 0 ({@link #verbLine}'s law). The key is trimmed + ASCII-folded and
     * must pass {@link BanSet#isCidKey} (else throws; the key is never echoed — it arrives from a
     * form); a BLANK key mints the key-less {@code kick N} (legal: a snapshot with no key column
     * kicks the frame-time occupant, the house-file grammar keeps the key optional). */
    public static String kickSeatLine(int seatId, String cidKeyHexOrBlank) {
        String head = verbLine(OperatorCommandFile.Verb.KICK, seatId); // the seat > 0 law
        String key = cidKeyHexOrBlank == null ? "" : cidKeyHexOrBlank.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return head;
        }
        if (!BanSet.isCidKey(key)) {
            throw new IllegalArgumentException("kick needs the seat's hex cid key (even length, 2.."
                    + (2 * BanSet.CID_MAX_BYTES) + " chars of 0-9a-f) - nothing sent");
        }
        return head + " " + key;
    }

    /** {@code ban-session <sessionId>} (sessionId NON-ZERO — a door session id is any signed long
     *  but 0, the no-session sentinel; the "&gt; 0" law refused half of them).
     * */
    public static String banSessionLine(long sessionId) {
        if (sessionId == 0L) {
            throw new IllegalArgumentException("ban-session needs a non-zero sessionId, got " + sessionId);
        }
        return "ban-session " + sessionId;
    }

    /** {@code unban cid|src <key>}: the kind token is required (A1 — a cid key can look like
     * an IPv4 literal) and the key is checked under THAT kind's grammar ({@link BanSet#cidBanKeyOrNull}
     * normalized like the parser / {@link BanSet#isSrcKey} as-is); anything else throws.
     * a cid key in either form, sent as the entry's digest. */
    public static String unbanLine(String kind, String key) {
        BanSet.Kind k = BanSet.kindOrNull(kind == null ? null : kind.trim());
        if (k == null) {
            throw new IllegalArgumentException("unban needs kind cid|src, got '" + kind + "'");
        }
        String raw = key == null ? "" : key.trim();
        String canon = k == BanSet.Kind.CID ? BanSet.cidBanKeyOrNull(raw) : raw;
        if (canon == null || !BanSet.isKey(k, canon)) {
            throw new IllegalArgumentException(k == BanSet.Kind.CID
                    ? "unban cid needs " + BanSet.CID_KEY_FORM
                    : "unban src needs a source key: " + BanSet.SRC_KEY_FORM);
        }
        return "unban " + BanSet.kindToken(k) + " " + canon;
    }

    /** {@code ban-ip <key>} from ONE typed address or range, the console's
     * twin of the operator file's {@code ban-ip} line. Trimmed; empty, a token with whitespace inside, or a token
     * {@link BanSet#srcBanKeyOrNull} refuses throws naming {@link BanSet#SRC_KEY_FORM} (plus the normalizer's reason,
     * which never quotes the typed token). The line carries the STORED key (an RFC-form IPv6 range becomes its
     * byte key), never the typed text, so the serve records exactly what a bans listing prints. */
    public static String banIpLine(String typed) {
        String t = typed == null ? "" : typed.trim();
        StringBuilder why = new StringBuilder(96);
        boolean oneToken = !t.isEmpty() && t.chars().noneMatch(Character::isWhitespace);
        String key = oneToken ? BanSet.srcBanKeyOrNull(t, why) : null;
        if (key == null) {
            throw new IllegalArgumentException("ban-ip needs an address or range: " + BanSet.SRC_KEY_FORM
                    + (why.length() > 0 ? " - " + why : ""));
        }
        return "ban-ip " + key;
    }

    /** The Ban address confirm text, {@code key} = the stored key the line
     * carries (so the host confirms what is recorded, never their typing). */
    public static String banIpConfirmText(String key) {
        return "Ban address " + key + "? Every player connecting from it is kicked now and refused at the door,"
                + " and cc-server.bans keeps it across server starts.";
    }

    /** P27: the Swing Unban pick's empty-list message (the web Bans section's twin). */
    public static final String NO_BANS_TEXT = "no bans in the last snapshot";

    /** The ONE shared ban-surface text law
     *  (Swing confirm / pick + the web JSON {@code label}) — the Ban player confirm naming the
     *  seat + its cid. Text-only: the cid is the ops file's sanitized row, never wire bytes. */
    public static String banConfirmText(int seatId, String clientId) {
        return fillBanConfirm(BAN_CONFIRM_TEMPLATE, seatId, clientId);
    }

    /** The confirm TEMPLATES — the ONE text
     * law behind both surfaces: Swing and the web console ({@code OpsConsoleForms.verbConfirm},
     * server-side; no page copy since the July page's verbatim embed retired) both fill
     * them via {@link #banConfirmText} / {@link #banSrcConfirmText}, so {@value #CONFIRM_SEAT} /
     * {@value #CONFIRM_CID} are filled one way. TRUE per door mode: a token serve refuses cid
     * bans ({@code cid_in_token_mode}, no kick); the key-bound line refuses a changed seat. */
    public static final String CONFIRM_SEAT = "{seat}";
    public static final String CONFIRM_CID = "{cid}";
    private static final String BAN_CONFIRM_TAIL = "\nIf another client now holds the seat,"
            + " nothing is banned.\nThe server log names the outcome.";
    // A ban records the address too (the shipped source-hold policy)
    public static final String BAN_CONFIRM_TEMPLATE = "Ban seat {seat} ({cid})?"
            + "\nA password serve kicks this player now and refuses their client id AND the address they"
            + " connected from at the door until unbanned."
            + "\nA token serve refuses client-id bans - use Ban source there." + BAN_CONFIRM_TAIL;
    private static final String BAN_SRC_CONFIRM_HEAD = "Ban the SOURCE address of seat {seat} ({cid})?";
    // A loopback bar lives in memory only - the bans-file parse drops a loopback src line at the next boot
    // (SourceRanges.isLoopbackKey; the host's own windows are never barred by a file) - so this confirm, unlike the
    // LAN / public ones below, never claims "across serves".
    public static final String BAN_SRC_CONFIRM_LOOPBACK = BAN_SRC_CONFIRM_HEAD
            + "\nThat address is THIS MACHINE (loopback)."
            + "\nEvery local joiner and test client on it is kicked and refused until unbanned or this serve"
            + " ends (a loopback bar is never kept in the bans file)." + BAN_CONFIRM_TAIL;
    public static final String BAN_SRC_CONFIRM_LAN = BAN_SRC_CONFIRM_HEAD
            + "\nThat address is on your LAN."
            + "\nEvery local joiner and test client on it is kicked and refused, across serves,"
            + " until unbanned." + BAN_CONFIRM_TAIL;
    public static final String BAN_SRC_CONFIRM_PUBLIC = BAN_SRC_CONFIRM_HEAD
            + "\nEvery client behind that address (CGNAT / shared LAN) is kicked and refused,"
            + " across serves, until unbanned." + BAN_CONFIRM_TAIL;

    /** S3-9: ONE textual classifier for a seat endpoint ({@code getHostAddress() + ":" + port},
     * lower-cased first): group 1 = loopback (127/8, {@code ::1} and its
     * {@code 0:0:0:0:0:0:0:1} print), group 2 = private / link-local LAN (10/8, 172.16/12,
     * 192.168/16, fc00::/7, fe80::/10, an optional {@code %scope}); an optional {@code :port}.
     * The SAME string is compiled here and embedded into the page's JS ({@code new RegExp}) —
     * text only, NEVER a DNS lookup; no inline flags (JS has none). */
    public static final String LOCAL_SOURCE_RE = "^/?(?:(127(?:\\.\\d{1,3}){3}|0:0:0:0:0:0:0:1|::1)"
            + "|(10(?:\\.\\d{1,3}){3}|172\\.(?:1[6-9]|2\\d|3[01])(?:\\.\\d{1,3}){2}"
            + "|192\\.168(?:\\.\\d{1,3}){2}|f[cd][0-9a-f]{2}:[0-9a-f:]*|fe[89ab][0-9a-f]:[0-9a-f:]*))"
            + "(?:%[0-9a-z._-]+)?(?::\\d{1,5})?$";
    private static final Pattern LOCAL_SOURCE = Pattern.compile(LOCAL_SOURCE_RE);

    /** Where a seat endpoint's source address lives, per {@link #LOCAL_SOURCE_RE}. */
    public enum SourceScope { LOOPBACK, LAN, PUBLIC }

    /** {@link #LOCAL_SOURCE_RE} over the ops file's endpoint cell (null / unmatched = PUBLIC). */
    public static SourceScope sourceScope(String endpoint) {
        if (endpoint == null) {
            return SourceScope.PUBLIC;
        }
        Matcher m = LOCAL_SOURCE.matcher(endpoint.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return SourceScope.PUBLIC;
        }
        return m.group(1) != null ? SourceScope.LOOPBACK : SourceScope.LAN;
    }

    /** The Ban source confirm (Swing; the page's twin picks the same template by the same regex):
     * a loopback / LAN endpoint names THIS MACHINE / your LAN and the across-serves refusal of
     * every local joiner (S3-9: the rig's 127.0.0.x persist into {@code cc-server.bans}); else the
     * CGNAT / shared-LAN collateral text. */
    public static String banSrcConfirmText(int seatId, String clientId, String endpoint) {
        SourceScope scope = sourceScope(endpoint);
        return fillBanConfirm(scope == SourceScope.LOOPBACK ? BAN_SRC_CONFIRM_LOOPBACK
                : scope == SourceScope.LAN ? BAN_SRC_CONFIRM_LAN : BAN_SRC_CONFIRM_PUBLIC,
                seatId, clientId);
    }

    /** Seat first, cid LAST (the cid's own text is never re-scanned) — the page fills in the
     * same order. */
    private static String fillBanConfirm(String template, int seatId, String clientId) {
        return template.replace(CONFIRM_SEAT, String.valueOf(seatId))
                .replace(CONFIRM_CID, clientId == null ? "" : clientId);
    }

    /** A ban key for display: a cid key (after its {@code h1:} tag, so every shown char is a digest
     *  char) longer than 12 chars = its first 12 + an ellipsis (U+2026); a src key (an IP literal /
     *  the /56 form) as-is. */
    public static String banKeyAbbrev(String kind, String key) {
        String k = "cid".equals(kind) ? CidDigest.body(key) : key == null ? "" : key;
        if ("cid".equals(kind) && k.length() > 12) {
            return k.substring(0, 12) + (char) 0x2026;
        }
        return k;
    }

    /** One ban row's pick / list label: {@code #i kind key-abbrev origin yyyy-mm-dd} (UTC date;
     *  {@code -} when the entry carries no timestamp, BanSet's optional-millis law). */
    public static String banRowLabel(OpsSnapshot.BanRow row) {
        String date = row.atMillis() > 0L
                ? java.time.Instant.ofEpochMilli(row.atMillis()).atOffset(java.time.ZoneOffset.UTC)
                        .toLocalDate().toString()
                : "-";
        return "#" + row.index() + " " + row.kind() + " " + banKeyAbbrev(row.kind(), row.key())
                + " " + row.origin() + " " + date;
    }

    private static String customIdToken(String id) {
        String token = OpsMatchRows.parseJoinerDefault(id);
        if (token.isEmpty()) {
            throw new IllegalArgumentException("a custom verb needs a minted id, got blank");
        }
        return token;
    }

    // ---- Shared shell law — the Swing window and the web console
    // ---- (OpsConsole) consume what is below; neither re-states a decision.

    /** Where a spawned Start's merged output lands (both shells name it in detail text): the
     * stage tool's console, then the launcher's (both spawns of the Start chain append here,
     * plus the chain's own {@code ops-start:} lines). */
    public static final String START_LOG = "generated/ops/ops-start.out.log";

    /** {@code ServeLauncher}'s flight-dir pointer {@code ${PKG}/run/latest.txt}
     * under {@link #OPS_PKG_ROOT} (its first line = {@code flight-<stamp>} beside it);
     * {@code <dir>/wrapper-receipt.txt} + {@code <dir>/lives/life-N.err.log} are what
     * {@link LaneFollow#lifeFailure} reads while a Start is PENDING — a life's stderr never
     * reaches {@link #START_LOG}. */
    public static final String PUBLIC_FLIGHT_POINTER = "generated/ops-pkg/run/latest.txt";

    /** Where the shape selector scans when no {@code match.descriptor} row names a
     * directory ({@link #descriptorScanDir}). */
    public static final String DESCRIPTOR_DIR = "generated/joiner-kit";

    /** Confirm key: blank-password Start deleting an armed sidecar (Kick-level weight). */
    public static final String CONFIRM_REMOVE_PASSWORD = "remove-password";

    /** Confirm key: blank-password Start with NO sidecar = a TOKEN-mode serve (the
     * "forgotten password" case). The public host lane is password-mode by law (the
     * kit joiner collects ONE credential, its window refuses a redacted kit without one), so a
     * token-mode Start here bounces every kit joiner {@code seat_token_unknown} — and admits
     * anyone holding a leaked descriptor token. Symmetric to {@link #CONFIRM_REMOVE_PASSWORD}
     * (that one already says "serve TOKEN mode?"); watch/solo lanes never Start from a shell. */
    public static final String CONFIRM_TOKEN_MODE_START = "token-mode-start";

    /** Confirm key: switched Start under the armed door with no {@code .fingerprint} sidecar. */
    public static final String CONFIRM_ARMED_DOOR = "armed-door-missing-fingerprint";

    /**
     * Shell-agnostic Start-flow surface ({@link ServerOpsLaunch#runStartFlow}): Swing adapts
     * {@code JOptionPane}; the web shell collects refused confirms into a
     * {@code needsConfirm} round-trip — safe to re-run because EVERY confirm is gathered
     * BEFORE the first write, so an aborted pass wrote nothing (the Cancel-side-effect-free
     * law, kept by construction).
     */
    public interface Ui {
        /** true = proceed. {@code key} is stable across shells (a web re-POST carries it). */
        boolean confirm(String key, String title, String message);

        /** A refusal/failure dialog ({error} JSON on the web side). Flow aborts after it. */
        void fail(String title, String message);

        /** The start-requested detail line (Swing status label / web response detail). */
        void info(String message);
    }

    /** The spawn seam — shells pass {@link ServerOpsLaunch#spawnStartScript}; the selftest records. Returns the
     * launched parent (null when only recorded) so a shell can follow its exit
     * ({@link LaneFollow#launchPhase}: a non-zero exit before the lane's first live snapshot = a dialog). */
    public interface SpawnFn {
        Process spawn(List<String> cmd) throws IOException;
    }

    // ---- The operator brief renders IN the ops window ----

    /**
     * Where {@code arm-watch.ps1} drops the LIVE operator brief; the ops shells POLL it.
     *
     * <p>The brief shows in the server-host GUI, not in a console host window of its own.
     *
     * A file is the channel — the same idiom as the password sidecar,
     * the operator command file and the ops snapshot — because the brief is written BEFORE
     * {@code run-pilot-render-watch.ps1} spawns the window, so no launcher argument has to be
     * threaded through two scripts to carry it.
     *
     * <p>LIVE means the arming script also DELETES this file in its {@code finally} sweep: a
     * standalone ops window started later must never resurrect a finished watch's brief. Shells
     * therefore hide the panel entirely when the file is absent, and the standalone / GUI-hosted
     * serve looks exactly as it did before this row.
     */
    public static final String BRIEF_FILE = "generated/owner-brief/current.txt";

    /** Header line the arming script writes above the body — the panel's stamp comes from it. */
    private static final String BRIEF_STAMP_PREFIX = "# armed ";

    /** Fewest text rows the brief panel shows (a one-liner must not open a tall empty box). */
    public static final int BRIEF_MIN_ROWS = 2;

    /** Most text rows it shows — past this the brief scrolls instead of growing the window.
     * Was 10 until 2026-08-25, when the operator found the box too small for full briefs and asked
     *
     * for it to auto-expand to the size of any amount of text put in it —
     * so the panel now grows to the WHOLE brief; 60 rows is only the sanity stop (window
     * geometry was the carved operator window's layout class's — the grow-only
     * {@code refitSize} / {@code briefColumns} helpers it replaced were deleted;
     * the scroll pane carries any remainder). */
    public static final int BRIEF_MAX_ROWS = 60;

    /** A parsed brief: the arming stamp ({@code null} when unheadered) and the body prose. */
    public record OwnerBrief(String stamp, String body) {
    }

    /**
     * Parse a polled brief file. Returns {@code null} for "no brief to show" — file absent,
     * unreadable, empty, or header-only — which is the state the shells render as a HIDDEN
     * panel, so the no-watch window is unchanged.
     *
     * <p>The header line is optional by design: a hand-written brief file (a headless host
     * dropping a note for themselves) still renders, just without a stamp in the border.
     */
    public static OwnerBrief parseBrief(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        String stamp = null;
        int lo = 0;
        String first = lines.get(0).trim();
        if (first.startsWith(BRIEF_STAMP_PREFIX)) {
            String s = first.substring(BRIEF_STAMP_PREFIX.length()).trim();
            stamp = s.isEmpty() ? null : s;
            lo = 1;
        }
        int hi = lines.size();
        while (lo < hi && lines.get(lo).isBlank()) {
            lo++;
        }
        while (hi > lo && lines.get(hi - 1).isBlank()) {
            hi--;
        }
        if (lo >= hi) {
            return null;
        }
        return new OwnerBrief(stamp, String.join("\n", lines.subList(lo, hi)));
    }

    /**
     * The brief body as bullet rows (the panel was
     * "a brick of run-on sentence"). A chat-authored brief arrives as ONE line joined on
     * {@code " | "}; every such piece — and every newline-separated row — becomes its own
     * {@code "• "} row, trimmed, empties dropped. Idempotent on already-bulleted text.
     *
     * <p>A brief is ONE
     * NUMBERED LIST of flight steps ({@code arm-standing-watch -Steps} renders {@code "n. action
     * → expectation"}); a row that already carries its step number keeps it — no bullet in front.
     */
    public static String briefBullets(String body) {
        if (body == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(body.length() + 32);
        for (String line : body.split("\n", -1)) {
            for (String piece : line.split("\\s\\|\\s", -1)) {
                String row = piece.trim();
                if (row.isEmpty()) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(row.startsWith("• ") || isNumberedStep(row) ? row : "• " + row);
            }
        }
        return out.toString();
    }

    /** {@code "1. "} … {@code "99. "} — a flight-step row that names its own number. */
    private static boolean isNumberedStep(String row) {
        int i = 0;
        while (i < row.length() && i < 2 && Character.isDigit(row.charAt(i))) {
            i++;
        }
        return i > 0 && row.length() > i + 1 && row.charAt(i) == '.' && row.charAt(i + 1) == ' ';
    }

    /** Titled-border text for the brief panel — it names the arming stamp when there is one. */
    public static String briefTitle(OwnerBrief brief) {
        String head = "Operator brief - read before you fly";
        return (brief == null || brief.stamp() == null) ? head
                : head + "  (armed " + brief.stamp() + ")";
    }

    /**
     * Visible rows for the brief text area, clamped to
     * [{@link #BRIEF_MIN_ROWS}, {@link #BRIEF_MAX_ROWS}].
     *
     * <p>{@code columns} is the wrap width the shell renders at: a word-wrapped area shows MORE
     * rows than the body has newlines, and sizing to the newline count alone would slice a long
     * paragraph. Counting wrapped rows instead keeps a short brief compact and lets a long one
     * scroll rather than push the window off-screen (the row-(34) grow-only re-fit would
     * otherwise honour every extra row).
     */
    public static int briefRows(String body, int columns) {
        if (body == null || body.isEmpty() || columns <= 0) {
            return BRIEF_MIN_ROWS;
        }
        int rows = 0;
        for (String line : body.split("\n", -1)) {
            rows += Math.max(1, (line.length() + columns - 1) / columns);
        }
        return Math.max(BRIEF_MIN_ROWS, Math.min(BRIEF_MAX_ROWS, rows));
    }

    /** The run-phase detail line (host:port pid up round frame clients tick). */
    public static String runDetailText(OpsSnapshot snap) {
        return String.format(Locale.ROOT,
                "%s:%d  pid=%d  up=%ds  round=%d frame=%d  clients=%d/%d  tick=%.1f/s",
                snap.host(), snap.port(), snap.pid(), snap.upSeconds(),
                snap.round(), snap.frame(), snap.clients(), snap.seatsTotal(),
                snap.tickPerS());
    }

    /** The pid-GONE detail — the server died WITHOUT the clean {@code phase=final}
     * write (kill fallback or crash), so point at the start log. */
    public static String goneDetailText(OpsSnapshot snap) {
        return "server pid " + snap.pid() + " is GONE without a final snapshot (kill fallback"
                + " or crash) - check " + OpsRoots.get().startLogText();
    }

    /** remember-half: run-phase rows with seats are worth keeping — the goodbye fan
     * usually clears sessions before the final write. Shells keep one {@code AtomicReference}
     * update behind this predicate. */
    public static boolean rememberRunSeats(OpsSnapshot snap) {
        return "run".equals(snap.phase()) && !snap.seats().isEmpty();
    }

    /** substitute-half: the rows to DISPLAY — a match-over empty table falls back to the
     * remembered run rows so the end state shows WHO played instead of an empty table. */
    public static List<OpsSnapshot.SeatRow> displaySeatRows(OpsSnapshot snap, boolean matchOver,
            List<OpsSnapshot.SeatRow> lastRun) {
        if (matchOver && snap.seats().isEmpty()) {
            return lastRun;
        }
        return snap.seats();
    }

    /** The selector scan dir — the standing row's own directory when it names one,
     * else {@link #DESCRIPTOR_DIR} (backslashes normalized like the row read). */
    public static String descriptorScanDir(String currentDescriptorRow) {
        String current = currentDescriptorRow == null ? null
                : currentDescriptorRow.replace('\\', '/');
        if (current != null && current.lastIndexOf('/') > 0) {
            return current.substring(0, current.lastIndexOf('/'));
        }
        return DESCRIPTOR_DIR;
    }

    /** One Start-selector entry; {@code path == null} = boot the row as-is (never a switch).
     * Invariant the shells lean on: {@code path != null ⇔ info != null}. */
    public record DescriptorOption(String path, DescriptorInfo info, String label) {
    }

    /** The whole selector population ({@link #descriptorOptions}): entries + initial pick. */
    public record DescriptorOptions(List<DescriptorOption> options, int selectedIndex) {
    }

    /**
     * The WHOLE selector-population law (moved verbatim from the Swing shell, now
     * shared with the web shell): scan the {@code scanDirRow} directory for {@code *.txt}
     * descriptors, each gated through the REAL boot codec ({@link #parseDescriptorInfo} —
     * tokens/sidecar/broken files are refused exactly as the serve would refuse them); a
     * standing row that is not among the scanned files is gated through the SAME parse (never
     * asserted untested) and listed first, as a switchable
     * "(standing row)" entry when it parses or a boots-as-is entry when it does not; no row at
     * all lists the cannot-start entry. Initial pick = the standing row when scanned, else
     * index 0.
     *
     * <p>Both file opens (the scan, the
     * standing row) resolve through {@link #configRelative} against {@code configFile} — the
     * serve's rule, so a package's {@code match/match.txt} row lists the package's files, never
     * the GUI cwd's; the option {@code path} strings stay in ROW space ({@code scanDirRow + "/"
     * + name}, backslashes normalized) so picking the standing row is never a "switch" that
     * rewrites the row absolute. Pinned by {@code ServerOpsCoreSelftest} {@code reroot-config-dir}.
     */
    public static DescriptorOptions descriptorOptions(Path configFile, String scanDirRow,
            String currentDescriptorRow) {
        String current = currentDescriptorRow == null ? null
                : currentDescriptorRow.replace('\\', '/');
        String dirText = scanDirRow.replace('\\', '/');
        List<String> paths = new ArrayList<>();
        List<DescriptorInfo> infos = new ArrayList<>();
        boolean pkg = packageMode();
        if (pkg) {
            packageOfferedOptions(configFile, scanDirRow, dirText, paths, infos);
        } else try (java.util.stream.Stream<Path> files = Files.list(
                configRelative(configFile, scanDirRow))) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".txt"))
                    .sorted().toList()) {
                try {
                    infos.add(parseDescriptorInfo(Files.readAllLines(p, StandardCharsets.UTF_8)));
                    paths.add(dirText + "/" + p.getFileName());
                } catch (IOException | RuntimeException notADescriptor) {
                    // tokens/sidecar/hand-broken file — the REAL codec refused it, so the
                    // serve would refuse it too: not a bootable shape
                }
            }
        } catch (IOException ignored) {
            // no descriptor dir — the selector still carries the boots-as-configured entry
        }
        List<DescriptorOption> options = new ArrayList<>();
        int selected = 0;
        if (current == null) {
            options.add(new DescriptorOption(null, null,
                    "(no match.descriptor row - the serve wrapper cannot start)"));
        } else {
            boolean scanned = false;
            for (String path : paths) {
                if (path.equalsIgnoreCase(current)) {
                    scanned = true;
                    break;
                }
            }
            if (!scanned) {
                try {
                    Path rowFile = configRelative(configFile, current);
                    DescriptorInfo rowInfo = parseDescriptorInfo(pkg ? readCappedLines(rowFile)
                            : Files.readAllLines(rowFile, StandardCharsets.UTF_8));
                    options.add(new DescriptorOption(current, rowInfo,
                            descriptorOptionLabel(current, rowInfo) + "  (standing row)"));
                } catch (IOException | RuntimeException notBootable) {
                    options.add(new DescriptorOption(null, null,
                            current + "  (standing row - the codec refused it; Start boots"
                                    + " it as-is)"));
                }
            }
        }
        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            options.add(new DescriptorOption(path, infos.get(i), descriptorOptionLabel(
                    path.substring(path.lastIndexOf('/') + 1), infos.get(i))));
            if (path.equalsIgnoreCase(current)) {
                selected = options.size() - 1;
            }
        }
        return new DescriptorOptions(List.copyOf(options), selected);
    }

    /** Package-mode picker entries the codec refused (a hand-broken / unreadable offered file), counted. */
    static final java.util.concurrent.atomic.AtomicInteger PICKER_SKIPS = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * ONE offered set: a PACKAGE's picker lists exactly {@link OpsMatchApply#offeredDescriptors}
     * - the match-settings / Copy bound (regular, non-symlink {@code match/*.txt}, no {@code -tokens}, ≤ 256, names
     * only; {@code .password} / {@code .fingerprint} never end {@code .txt}) - each gated by the REAL codec through
     * ONE bounded read (≤ 262144 B, no symlink followed); a refused file is skipped and counted in
     * {@link #PICKER_SKIPS}. Row strings stay in ROW space: {@code dirText/<name>} when the scan dir IS the
     * offered folder (a package's {@code match/match.txt} row), else the config-relative path.
     *
     */
    private static void packageOfferedOptions(Path configFile, String scanDirRow, String dirText,
            List<String> paths, List<DescriptorInfo> infos) {
        Path scanDir = configRelative(configFile, scanDirRow);
        Path configDir = configFile.toAbsolutePath().normalize().getParent();
        for (Path p : OpsMatchApply.offeredDescriptors(OpsRoots.get())) {
            try {
                infos.add(parseDescriptorInfo(readCappedLines(p)));
            } catch (IOException | RuntimeException notADescriptor) {
                PICKER_SKIPS.incrementAndGet();
                continue;
            }
            String row = p.getParent().equals(scanDir) ? dirText + "/" + p.getFileName()
                    : configDir != null && p.startsWith(configDir) ? configDir.relativize(p).toString() : p.toString();
            paths.add(row.replace('\\', '/'));
        }
    }

    /** The lenient prefill read for a sidecar path ({@code ""} when absent/unreadable/blank) —
     * the follow-the-pick family, shared by the Swing sync and {@code /api/config}.
     * {@code sidecarPath} is a ROW-space
     * string resolved through {@link #configRelative} against {@code configFile} (the file
     * Start writes and the serve opens), never the GUI's cwd; pinned by
     * {@code ServerOpsCoreSelftest} {@code reroot-config-dir}. */
    public static String sidecarPrefill(Path configFile, String sidecarPath) {
        try {
            Path sidecar = sidecarPath == null ? null : configRelative(configFile, sidecarPath);
            if (sidecar != null && Files.isRegularFile(sidecar)) {
                String row = sidecarPasswordRowValue(
                        Files.readAllLines(sidecar, StandardCharsets.UTF_8));
                if (row != null) {
                    return row;
                }
            }
        } catch (IOException ignored) {
            // unreadable sidecar bites at Start (and the serve opens fail-closed on it)
        }
        return "";
    }

    /**
     * THE one match-password resolver: a PACKAGE has ONE
     * match password - the STANDING row's ({@code match.descriptor} in {@code configFile}) sidecar, whatever
     * shape the host picks; owner mode keeps the per-descriptor read ({@code sidecarFactsJson}'s per-option
     * rule: {@link #sidecarPrefill} of {@code sidecarPath}, untouched). WHY one: one server, one password - a
     * shape switch costs the host no typing and the joiners no new password, and the overwrite hazard of the
     * per-shape files (a stale {@code B.txt.password} silently becoming the server's password after a switch)
     * dies by rule. {@code sidecarPath} = the ROW-space {@code .password} path the
     * caller would have read (the pick's, or {@code null} for the row's own); a package ignores it. Returns
     * {@code ""} when the standing row has no sidecar / no non-null {@code password=} row (the host types one;
     * the package Start refuses a blank). Lenient like {@link #sidecarPrefill}: the value is never validated
     * here, never logged. Callers: the Start flow ({@code ServerOpsLaunch}), {@code sidecarFactsJson}; the web
     * {@code keep} / reveal / brick / Join and the Swing prefill re-point here.
     *
     */
    public static String packageKeptPassword(Path configFile, String sidecarPath) {
        return sidecarPrefill(configFile, packageMode() ? packageStandingSidecar(configFile) : sidecarPath);
    }

    /** The admin twin of {@link #packageKeptPassword}: the {@code .adminpassword} beside the SAME resolved
     *  sidecar ({@link #adminSidecarOf}); {@code ""} without one. */
    public static String packageKeptAdminPassword(Path configFile, String sidecarPath) {
        return sidecarPrefill(configFile,
                adminSidecarOf(packageMode() ? packageStandingSidecar(configFile) : sidecarPath));
    }

    /** One mode read: the live {@link OpsRoots} record is a package. */
    static boolean packageMode() {
        return OpsRoots.get().mode() == OpsRoots.Mode.PACKAGE;
    }

    /**
     * The STANDING row's {@code .password} path (row space) read off {@code configFile} - ONE bounded read
     * (≤ {@link ServeLauncher#MAX_CONFIG_BYTES}, the launcher's own config cap); {@code null} when the file is
     * absent, over the cap, unreadable (one stderr line naming the exception class) or carries no row.
     *
     */
    static String packageStandingSidecar(Path configFile) {
        try {
            if (configFile == null || !Files.isRegularFile(configFile)) {
                return null;
            }
            return passwordSidecarPath(readCappedLines(configFile));
        } catch (IOException | RuntimeException unreadable) {
            System.err.println("[ops] kept password: the config cannot be read ("
                    + unreadable.getClass().getSimpleName() + ") - no kept password");
            return null;
        }
    }

    /** UTF-8 lines of one file read through ONE bounded stream (no symlink followed); a file past
     *  {@link ServeLauncher#MAX_CONFIG_BYTES} throws (refused, never truncated).
     *  PUBLIC for the serve-boot descriptor read in
     *  {@code runtime/}. */
    public static List<String> readCappedLines(Path file) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes((int) ServeLauncher.MAX_CONFIG_BYTES + 1);
            if (bytes.length > ServeLauncher.MAX_CONFIG_BYTES) {
                throw new IOException("over the read cap");
            }
            return new String(bytes, StandardCharsets.UTF_8).lines().toList();
        }
    }

    /**
     * The blocking owner-mode lease pull (its one caller today is the Swing
     * shell's lease thread, off the EDT). ONE
     * {@link VpnLeaseProbe#pull} over {@link VpnLeaseProbe.Env#live} - the ProtonVPN gateway
     * asked FIRST with a 3 s ceiling ({@link VpnLeaseProbe#ASK_TIMEOUT_MS}), then the
     * ProtonVPN log scan - then {@link VpnLeaseProbe.Probe#toLease}. Refusals throw
     * {@link IllegalStateException} carrying the shell-facing sentence: no gateway, a
     * non-public answer, no VPN address, or a missing / stale port (its why). An
     * {@link IOException} = the socket could not open or the log directory could not be
     * listed. {@link InterruptedException} stays declared for the callers' catches; nothing
     * here throws it any more (no process, no wait).
     */
    public static VpnLease pullVpnLease() throws IOException, InterruptedException {
        return VpnLeaseProbe.pull(VpnLeaseProbe.Env.live()).toLease();
    }

    /** The uplink field/param parse: garbage or blank reads 0 (egress bar shows 0, never NaN
     * — {@link #egressPercentOfUplink} already reads a non-positive uplink as 0). */
    public static double uplinkOrZero(String text) {
        try {
            return Double.parseDouble(text == null ? "" : text.trim());
        } catch (NumberFormatException bad) {
            return 0.0;
        }
    }

    /**
     * The web shell's CSRF/DNS-rebinding guard predicate — every request must
     * carry a localhost-form {@code Host} header (a rebinding attacker's page arrives under
     * ITS host name), and every POST must additionally carry the {@code X-CC-Ops} custom
     * header (a cross-origin page cannot set one without a CORS preflight the shell never
     * answers). Fail-closed: absent Host, foreign hosts, and any method beyond GET/POST all
     * refuse.
     */
    public static boolean webRequestAllowed(String method, String hostHeader,
            boolean hasOpsHeader) {
        if (hostHeader == null) {
            return false;
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        String bare;
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end < 0) {
                return false;
            }
            bare = host.substring(0, end + 1);
        } else {
            int colon = host.indexOf(':');
            bare = colon < 0 ? host : host.substring(0, colon);
        }
        if (!"127.0.0.1".equals(bare) && !"localhost".equals(bare) && !"[::1]".equals(bare)) {
            return false;
        }
        if ("POST".equals(method)) {
            return hasOpsHeader;
        }
        return "GET".equals(method);
    }

    /** Minimal JSON string escaper (quote, backslash, control chars) for the composers below. */
    public static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** Bare-double JSON emitter for the composers: a foreign/hand-edited ops file can carry
     * {@code util_pct=NaN} through the lenient {@code Double.parseDouble}, and NaN/Infinity
     * are not JSON — non-finite serializes as {@code null} so the page's {@code JSON.parse}
     * never breaks (the derived TEXT fields keep their Swing-parity "NaN%" strings; review
     * find wf_0eb7945a). Finite values print exactly like {@code StringBuilder.append}. */
    public static String jsonNumber(double d) {
        return Double.isFinite(d) ? Double.toString(d) : "null";
    }

    /** A JSON array of strings, each through {@link #jsonEscape}. */
    static String jsonStringArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            sb.append(i > 0 ? ",\"" : "\"").append(jsonEscape(values.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * The ONE {@code /api/status} composer — raw fields AND every presentation
     * string (headline/detail/bars/seat cells/totals, all through the same helpers the Swing
     * shell renders), so the web page's JS is dumb rendering and presentation law cannot fork
     * into it. {@code pidAlive} = {@link #writerAlive} probed by the shell on every snapshot;
     * {@code uplinkMbps} arrives as a query param so the egress math stays here.
     * {@code stateKey} is the color classification (run/listening/stale/gone/exited/
     * stopped — calm for the clean end, alarming only for wedge/crash shapes).
     */
    public static String statusJson(OpsSnapshot snap, String problem, String opsFileText,
            boolean pidAlive, boolean diedOnWatch, double uplinkMbps, long nowMs,
            List<OpsSnapshot.SeatRow> lastRun) {
        ServerState state = snap == null ? ServerState.STOPPED
                : deriveState(snap.phase(), snap.isStale(nowMs, STALE_THRESHOLD_MS), pidAlive,
                        diedOnWatch);
        boolean matchOver = state == ServerState.EXITED;
        // A snapshot whose writer is dead and was never seen alive by this shell is a
        // previous server's leftover — calm detail, bars "-", no seat rows (Swing parity).
        boolean leftover = snap != null && state == ServerState.STOPPED;
        String headline = stateHeadline(state);
        String stateKey = state.name().toLowerCase(Locale.ROOT);
        String detail;
        String utilTitle = "Sim utilization (% of frame budget):";
        int utilValue = 0;
        String utilText = "-";
        String workMaxText = "-";
        int egressValue = 0;
        String egressText = "-";
        String egressDetail = "-";
        StringBuilder seats = new StringBuilder("[");
        if (snap == null) {
            detail = problem == null
                    ? noFileDetail(opsFileText, OpsRoots.get().mode() == OpsRoots.Mode.PACKAGE)
                    : "ops file unreadable: " + problem;
        } else if (leftover) {
            detail = leftoverDetailText(snap, nowMs);
        } else {
            if (matchOver) {
                detail = finalDetailText(snap);
            } else if (state == ServerState.GONE) {
                detail = goneDetailText(snap);
            } else {
                detail = runDetailText(snap);
            }
            if (snap.tickPerS() > 0) {
                utilTitle = String.format(Locale.ROOT,
                        "Sim utilization (%% of %.2f ms frame budget):",
                        1000.0 / snap.tickPerS());
            }
            if (matchOver) {
                // The last window is teardown noise — freeze-at-a-value reads as a
                // crash; say "match over" instead and keep only the totals.
                utilText = "match over";
                egressText = "match over";
                egressDetail = "match totals " + bytesText(snap.bytesSentTotal()) + " out / "
                        + bytesText(snap.bytesRecvTotal()) + " in  (send failures "
                        + snap.sendFailures() + ")";
            } else {
                utilValue = (int) Math.min(100, Math.round(snap.utilPct()));
                utilText = String.format(Locale.ROOT, "%.1f%%", snap.utilPct());
                workMaxText = String.format(Locale.ROOT, "%.1f ms (overruns=%d)",
                        snap.workMaxMs(), snap.overrunFrames());
                double egressPct = egressPercentOfUplink(snap.sentBps(), uplinkMbps);
                egressValue = (int) Math.min(100, Math.round(egressPct));
                egressText = String.format(Locale.ROOT, "%.1f%%", egressPct);
                egressDetail = bpsText(snap.sentBps()) + " out, " + bpsText(snap.recvBps())
                        + " in  (totals " + bytesText(snap.bytesSentTotal()) + " / "
                        + bytesText(snap.bytesRecvTotal()) + ", send failures "
                        + snap.sendFailures() + ")";
            }
            boolean firstRow = true;
            for (OpsSnapshot.SeatRow s : displaySeatRows(snap, matchOver, lastRun)) {
                if (!firstRow) {
                    seats.append(',');
                }
                firstRow = false;
                seats.append('[');
                Object[] cells = seatTableRow(s, matchOver);
                for (int i = 0; i < cells.length; i++) {
                    if (i > 0) {
                        seats.append(',');
                    }
                    if (cells[i] instanceof Integer) {
                        seats.append(cells[i]);
                    } else {
                        seats.append('"').append(jsonEscape(String.valueOf(cells[i])))
                                .append('"');
                    }
                }
                seats.append(']');
            }
        }
        seats.append(']');
        boolean startEnabled = startEnabled(state);
        boolean commandable = snap != null && !"off".equals(snap.commandFile())
                && liveServer(state);
        boolean killEnabled = snap != null && liveServer(state);
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"state\":\"").append(state.name())
                .append("\",\"stateKey\":\"").append(stateKey)
                .append("\",\"headline\":\"").append(jsonEscape(headline))
                .append("\",\"detail\":\"").append(jsonEscape(detail))
                .append("\",\"matchOver\":").append(matchOver)
                .append(",\"startEnabled\":").append(startEnabled)
                .append(",\"commandable\":").append(commandable)
                .append(",\"killEnabled\":").append(killEnabled)
                .append(",\"utilTitle\":\"").append(jsonEscape(utilTitle))
                .append("\",\"utilValue\":").append(utilValue)
                .append(",\"utilText\":\"").append(jsonEscape(utilText))
                .append("\",\"workMaxText\":\"").append(jsonEscape(workMaxText))
                .append("\",\"egressValue\":").append(egressValue)
                .append(",\"egressText\":\"").append(jsonEscape(egressText))
                .append("\",\"egressDetail\":\"").append(jsonEscape(egressDetail))
                .append("\",\"seatColumns\":[");
        for (int i = 0; i < SEAT_COLUMNS.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(jsonEscape(SEAT_COLUMNS[i])).append('"');
        }
        sb.append("],\"seats\":").append(seats);
        // The customs card — the Swing label + tooltip texts verbatim (the
        // ONE composer law) and the submissions flag for the web's accept toggle.
        sb.append(",\"customs\":\"").append(jsonEscape(customsSummaryText(snap)))
                .append("\",\"customsDetail\":\"").append(jsonEscape(customsDetailText(snap)))
                .append("\",\"customSubmissions\":").append(snap != null && snap.customSubmissions());
        // The MATCH line's MagicLib sentence ("" = the page shows no line); a
        // previous server's leftover file says nothing about this match, so it carries none.
        sb.append(",\"magicSeamLine\":\"").append(jsonEscape(leftover ? "" : magicSeamLineText(snap))).append('"');
        // The MATCH line's player-ship sentence, the same leftover law.
        sb.append(",\"pilotShipLine\":\"").append(jsonEscape(leftover ? "" : pilotShipLineText(snap))).append('"');
        // The page's Kept / Exported lists - the status file's kept
        // rows (OpsSnapshot's optional keys: 0 / [] / "-" / false without a snapshot or from a pre-kept writer),
        // every string through jsonEscape; the page renders them as textContent only.
        sb.append(",\"customKept\":").append(snap == null ? 0 : snap.customKept())
                .append(",\"customKeptIds\":").append(jsonStringArray(keptPickRows(snap)))
                .append(",\"customKeptFailed\":").append(snap == null ? 0L : snap.customKeptFailed())
                .append(",\"customKeptLast\":\"").append(jsonEscape(snap == null ? "-" : snap.customKeptLast()))
                .append("\",\"customInboxIds\":").append(jsonStringArray(inboxPickRows(snap)))
                .append(",\"customKeptOverCap\":").append(snap != null && snap.customKeptOverCap())
                .append(",\"customInboxOverCap\":").append(snap != null && snap.customInboxOverCap());
        // The page's shipped-ships list - OpsSnapshot's optional ships rows
        // ([] / false / "-" / 0 without a snapshot or from a pre-ships writer), every string through jsonEscape; the
        // page renders them as textContent only.
        sb.append(",\"shipsBaseIds\":").append(jsonStringArray(shippedPickRows(snap)))
                .append(",\"shipsHiddenIds\":").append(jsonStringArray(hiddenPickRows(snap)))
                .append(",\"shipsOverCap\":").append(snap != null && snap.shipsOverCap())
                .append(",\"shipsLast\":\"").append(jsonEscape(snap == null ? "-" : snap.shipsLast()))
                .append("\",\"shipsPersistFailed\":").append(snap == null ? 0L : snap.shipsPersistFailed());
        // The P27 Bans section — the count,
        // the truncation tail, and each carried row with its Swing pick label (banRowLabel —
        // the ONE text law) so the page JS renders without re-deriving; keys are jsonEscape'd
        // (the page escapes again at render, the seat-cell law).
        sb.append(",\"bans\":").append(snap == null ? 0 : snap.bans())
                .append(",\"bansMore\":").append(snap == null ? 0 : snap.bansMore())
                .append(",\"banRows\":[");
        List<OpsSnapshot.BanRow> banRows = snap == null ? List.of() : snap.banRows();
        for (int i = 0; i < banRows.size(); i++) {
            OpsSnapshot.BanRow b = banRows.get(i);
            sb.append(i > 0 ? "," : "").append("{\"index\":").append(b.index())
                    .append(",\"kind\":\"").append(jsonEscape(b.kind()))
                    .append("\",\"key\":\"").append(jsonEscape(b.key()))
                    .append("\",\"keyAbbrev\":\"").append(jsonEscape(banKeyAbbrev(b.kind(), b.key())))
                    .append("\",\"origin\":\"").append(jsonEscape(b.origin()))
                    .append("\",\"atMillis\":").append(b.atMillis())
                    .append(",\"label\":\"").append(jsonEscape(banRowLabel(b))).append("\"}");
        }
        sb.append(']');
        if (snap != null) {
            sb.append(",\"raw\":{\"phase\":\"").append(jsonEscape(snap.phase()))
                    .append("\",\"pid\":").append(snap.pid())
                    .append(",\"host\":\"").append(jsonEscape(snap.host()))
                    .append("\",\"port\":").append(snap.port())
                    .append(",\"upSeconds\":").append(snap.upSeconds())
                    .append(",\"round\":").append(snap.round())
                    .append(",\"frame\":").append(snap.frame())
                    .append(",\"clients\":").append(snap.clients())
                    .append(",\"seatsTotal\":").append(snap.seatsTotal())
                    .append(",\"tickPerS\":").append(jsonNumber(snap.tickPerS()))
                    .append(",\"utilPct\":").append(jsonNumber(snap.utilPct()))
                    .append(",\"workMaxMs\":").append(jsonNumber(snap.workMaxMs()))
                    .append(",\"sentBps\":").append(snap.sentBps())
                    .append(",\"recvBps\":").append(snap.recvBps())
                    .append(",\"bytesSentTotal\":").append(snap.bytesSentTotal())
                    .append(",\"bytesRecvTotal\":").append(snap.bytesRecvTotal())
                    .append(",\"overrunFrames\":").append(snap.overrunFrames())
                    .append(",\"debtMs\":").append(jsonNumber(snap.debtMs()))
                    .append(",\"sendFailures\":").append(snap.sendFailures())
                    .append(",\"stampMs\":").append(snap.stampMs())
                    // Every custom.* global the ops file carries (zeros/"-" when
                    // the writer predates a key — the snapshot's optional-key law).
                    .append(",\"customCount\":").append(snap.customCount())
                    .append(",\"customRejected\":").append(snap.customRejected())
                    .append(",\"customRejectedPolicy\":").append(snap.customRejectedPolicy())
                    .append(",\"customRejectedCaps\":").append(snap.customRejectedCaps())
                    .append(",\"customRejectedEngine\":").append(snap.customRejectedEngine())
                    .append(",\"customRejectedOperator\":").append(snap.customRejectedOperator())
                    .append(",\"customFrameEncodeFailed\":").append(snap.customFrameEncodeFailed())
                    .append(",\"customVsubDecodeRefused\":").append(snap.customVsubDecodeRefused())
                    .append(",\"customBanned\":").append(snap.customBanned())
                    .append(",\"customPending\":").append(snap.customPending())
                    .append(",\"customCapGlobal\":").append(snap.customCapGlobal())
                    .append(",\"customTier\":\"").append(jsonEscape(snap.customTier()))
                    .append("\",\"customPerSeat\":").append(snap.customPerSeat())
                    .append(",\"customSModCap\":\"").append(jsonEscape(snap.customSModCap()))
                    .append("\",\"customDMods\":\"").append(jsonEscape(snap.customDMods()))
                    .append("\",\"customExported\":").append(snap.customExported())
                    .append(",\"customExportFailed\":").append(snap.customExportFailed())
                    .append('}');
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Can Join from this PC run on this computer? True only for an
     * {@code os.name} starting {@code Windows} (the
     * player kit runs on Windows only); null / blank / any other OS = false. Pure; thread: any.
     */
    static boolean joinHere(String osName) {
        return osName != null && osName.startsWith("Windows");
    }

    /**
     * The {@code /api/config} composer — everything the set-before-start form
     * needs in one read (the Swing window-open prefill law): tick row, the selector options
     * (each carrying its sidecar names — the follow-the-pick family, resolved here so
     * the page JS never re-derives it), bind defaults. An unreadable properties file opens the
     * form at defaults exactly like the Swing shell (it bites at Start instead).
     *
     * <p>
     * NO stored secret rides this JSON. Each {@code options[]} entry names its
     * {@code sidecar} + {@code adminSidecar} (row space, {@link #adminSidecarOf}) and says only
     * WHETHER a password is saved — {@code hasPassword} = the sidecar file exists AND holds a
     * NON-BLANK {@code password=} row ({@link #sidecarPasswordRowValue}), {@code hasAdmin} = the
     * admin sidecar file exists; the top level repeats those four for the selected option. The
     * July clear-text {@code prefill} key and the owner-only {@code mapHalf} prefill are DROPPED
     * (the console resolves {@code keep} server-side). {@code advertise} = the package Advertise
     * text: the saved {@code launcher.properties} {@code advertise} row when non-blank
     * (row {@code config-advertise-saved}), else {@link OpsSetupPrefill#packageAdvertise} of the
     * bind-port prefill; {@code ""} in owner mode. Pinned by {@code ServerOpsCoreSelftest}
     * {@code config-no-secret}.
     *
     * <p>The nine Start-row prefills
     * ({@code joinerDefault fortressWaveScale waveCategory endless modsEnabled modsOptional terrain
     * terrainLevel asteroids}) ride beside {@code fortressDp} / {@code waveRosterFullDp} /
     * {@code waveRosterMultiplier}, whose absent row prefills {@code "1"} — each the
     * Swing form's OWN {@code OpsMatchRows.*FieldPrefill} of the file's row (an absent row = that
     * prefill's default: {@code ""} for the four free-text rows, the combo default for the five
     * dials), so the console's Start re-sends the host's rows instead of blanking them. Pinned by
     * {@code config-prefills}.
     *
     * <p>{@code seatsPerSource} / {@code allSkills} sit between
     * {@code uplinkDefault} and {@code selectedIndex} (the Swing form's own prefills: an absent row = {@code "1"} / {@code "on"}), and every
     * option carries {@code "seats"} = its decoded seat count ({@code null} without a decode) - the New
     * dialog's Players prefill, the Swing twin of {@code DescriptorInfo.seatCount}. Pinned by
     * {@code config-seats-allskills}.
     *
     * <p>{@code rngPins} follows {@code allSkills} (the Swing form's own
     * {@code OpsMatchRows.rngPinsFieldPrefill}: an absent row = {@code "on"}, the compiled default). Pinned by
     * {@code config-rngpins}.
     *
     * <p>{@code magicSeam} follows {@code rngPins}
     * ({@code OpsMatchRows.magicSeamFieldPrefill}: an absent row = {@code "on"}, the serve's compiled default).
     * Pinned by {@code config-magicseam}.
     *
     * <p>{@code pilotShip} follows {@code magicSeam}
     * ({@code OpsMatchRows.pilotShipFieldPrefill}: an absent row = {@code "on"}, the serve's compiled default).
     * Pinned by {@code config-pilotship}.
     *
     * <p>{@code pvp} = {@link #selectedPvpWord} (on|off of the selected
     * option's shape; no props row), right after {@code uplinkDefault}, before {@code seatsPerSource}. Pinned by
     * {@code web-start-pvp}.
     *
     * <p>{@code gameRoot} = a package's
     * {@code launcher.properties} {@code game.root} row (read through
     * {@link ServeLauncher#readLauncherRows}, trimmed), {@code ""} without one and in owner mode - the
     * page's Game folder prefill; the console's Start saves the field back (row {@code web-start-gameroot}).
     *
     * <p>{@code advertiseFrom} / {@code advertiseBind} = the package's
     * {@code advertise.from} / {@code advertise.bind} launcher rows trimmed ({@code ""} without one and in
     * owner mode), served after {@code gameRoot} - the page follows a ProtonVPN fill's address only while
     * {@code advertiseFrom} is {@code protonvpn}. In that mode a non-blank {@code advertiseBind} replaces
     * the {@code bindHost} prefill (the Swing form's open-time rule), so a console restart opens on the
     * values the server's mode compare expects (row {@code config-advertise-saved}).
     *
     * <p>{@code joinHere} = {@link #joinHere} over this JVM's
     * {@code os.name}, a JSON boolean served right after {@code advertiseBind}: the page shows Join from
     * this PC only when true and the "needs Windows" line only when false (row {@code config-joinhere}).
     */
    public static String configJson(Path propsFile, double uplinkDefault) {
        return configJson(propsFile, uplinkDefault, OpsSetupDraft.Draft.EMPTY);
    }

    /** The package MANIFEST's kit stamps for the join press: {@code jarMd5OrNull}
     *  = the {@code kit.jar.md5} row (lowercase 32 hex; the installed kit's own manifest is compared against it before
     *  the press writes into that folder); {@code fingerprintOrNull} = the {@code kit.fingerprint} row (the kit's
     *  FULL jar / data / hs / match composite, {@code ;match=} included - {@link OpsJoinSelf#sameKit} strips it on
     *  BOTH sides at the compare, so a kit packed for another match shape is still this kit) the installed kit's
     *  composite is ALSO compared against (a data-only kit change keeps the jar md5); {@code zipOrNull} =
     *  the shipped {@code kits/<pack.kit>} when {@code pack.kit} is a plain {@link KitPackRules#PACK_KIT} name
     *  resolving INSIDE {@code kits/} ({@link KitPacker#baseKitPath}) and a regular file (the refresh
     *  source for a stale kit). All null when the MANIFEST is missing / unreadable or the rows absent - the press
     *  then skips the compare, exactly the pre-stamp behaviour. */
    public record KitStamps(String jarMd5OrNull, String fingerprintOrNull, Path zipOrNull) {
        public static final KitStamps NONE = new KitStamps(null, null, null);
    }

    /** {@link KitStamps} of the package at {@code pkgRoot}; never throws (see the record). */
    public static KitStamps packageKitStamps(Path pkgRoot) {
        Path pkg = pkgRoot.toAbsolutePath().normalize();
        try {
            PackageManifest m = PackageManifest.read(pkg.resolve(ServeLauncher.MANIFEST_FILE));
            Path zip = KitPacker.baseKitPath(KitPacker.kitsDir(pkg), m.packKit());
            return new KitStamps(blankToNull(m.kitJarMd5()), blankToNull(m.kitFingerprint()),
                    zip != null && Files.isRegularFile(zip) ? zip : null);
        } catch (IOException | RuntimeException unreadable) {
            return KitStamps.NONE;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * {@link #configJson(Path, double)} plus the unsaved-Setup draft as the members
     * {@code draft} (an object of control id to value, {@link OpsSetupDraft#jsonMembers}) and
     * {@code draftUpdated}; the page lays the draft over the filled form AFTER each config read (a server
     * phase change re-reads config, so the overlay has to ride the same read). An empty draft serves
     * {@code "draft":{}} - the page then changes nothing. Never a password (the draft holds none).
     */
    public static String configJson(Path propsFile, double uplinkDefault, OpsSetupDraft.Draft draft) {
        String json = configJsonBody(propsFile, uplinkDefault);
        // the draft members lead the object: every adjacency the config rows pin
        // (allSkills-rngPins-magicSeam-pilotShip-selectedIndex, the selected facts before options, the closing "}]}") stays byte-identical, and a page reading members
        // by name is indifferent to the position
        return "{" + OpsSetupDraft.jsonMembers(draft) + "," + json.substring(1);
    }

    private static String configJsonBody(Path propsFile, double uplinkDefault) {
        List<String> propsLines = List.of();
        try {
            if (Files.isRegularFile(propsFile)) {
                propsLines = Files.readAllLines(propsFile, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // unreadable file bites at Start; the form just opens at its defaults
        }
        String tickRow = tickRateRowValue(propsLines);
        String tick = tickRow == null || tickRow.trim().isEmpty() ? "60" : tickRow.trim();
        String currentDescriptor = descriptorRowValue(propsLines);
        DescriptorOptions opts = descriptorOptions(
                propsFile, descriptorScanDir(currentDescriptor), currentDescriptor);
        String sidecarAtOpen = passwordSidecarPath(propsLines);
        // A PACKAGE's console opens on its
        // config's bind rows (the Swing form's packageBindPrefill law) — the owner-mode literal 127.0.0.1 is
        // a same-PC-only bind (PackageBind ACCEPTS it; before that a stranger's first Start
        // would have bounced), not where a stranger's joiners reach. Owner mode keeps the literal byte-for-byte.
        boolean packaged = OpsRoots.get().mode() == OpsRoots.Mode.PACKAGE;
        String[] bindPrefill = packaged
                ? OpsSetupPrefill.packageBindPrefill(propsLines)
                : new String[] {"127.0.0.1", DEFAULT_BIND_PORT};
        // A package reads launcher.properties ONCE per
        // request through the launcher's own row reader (the open-time read the Setup prefills ride);
        // a missing file = no rows. gameRoot = the game.root row trimmed, "" without one / in owner mode;
        // the same read serves the saved Advertise row.
        Map<String, String> launcherRows = packaged ? ServeLauncher.readLauncherRows(
                ServerOpsLaunch.launcherPropertiesFile(), System.out) : Map.of();
        String gameRoot = launcherRows.getOrDefault(ServeLauncher.ROW_GAME_ROOT, "").trim();
        // Field: the debug WATCH's second game folder, served right after joinHere
        // (W2 reads it by name as the folder-2 prefill; W1 saves it through writeOrClearLauncherRow)
        String gameRootSecond = launcherRows.getOrDefault(ServeLauncher.ROW_GAME_ROOT_SECOND, "").trim();
        // the package Advertise rule (OpsSetupPrefill): the saved row, else the <public IP> placeholder
        String savedAdvertise = launcherRows.getOrDefault(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE, "").trim();
        String advertise = !packaged ? ""
                : savedAdvertise.isEmpty() ? OpsSetupPrefill.packageAdvertise(bindPrefill[1]) : savedAdvertise;
        // the ProtonVPN mode rows - in the mode the bind host opens on the fill's
        // saved advertise.bind (the Swing form's open-time rule)
        String advertiseFrom = launcherRows.getOrDefault(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM, "").trim();
        String advertiseBind = launcherRows.getOrDefault(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_BIND, "").trim();
        if (ServerOpsLaunch.ADVERTISE_FROM_PROTONVPN.equals(advertiseFrom) && !advertiseBind.isEmpty()) {
            bindPrefill[0] = advertiseBind;
        }
        // A package's facts = the KEPT password's sidecar for every
        // option (packageKeptPassword resolves the standing row's, read once here); owner = per option.
        String keptRow = packaged ? packageStandingSidecar(propsFile) : null;
        StringBuilder options = new StringBuilder(256);
        String selectedFacts = null;
        for (int i = 0; i < opts.options().size(); i++) {
            DescriptorOption o = opts.options().get(i);
            if (i > 0) {
                options.append(',');
            }
            String sidecarSel = o.path() == null ? sidecarAtOpen : o.path() + ".password";
            String facts = sidecarFactsJson(propsFile, sidecarSel, packaged ? keptRow : sidecarSel);
            if (i == opts.selectedIndex()) {
                selectedFacts = facts;
            }
            options.append("{\"path\":").append(jsonQuotedOrNull(o.path()))
                    .append(",\"label\":\"").append(jsonEscape(o.label()))
                    .append("\",\"shape\":").append(jsonQuotedOrNull(o.info() == null ? null
                            : o.info().shape()))
                    .append(",\"seats\":").append(o.info() == null ? "null"
                            : Integer.toString(o.info().seatCount()))
                    .append(',').append(facts).append('}');
        }
        if (selectedFacts == null) {
            selectedFacts = sidecarFactsJson(propsFile, sidecarAtOpen, packaged ? keptRow : sidecarAtOpen);
        }
        String pvp = selectedPvpWord(opts);
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"tickRate\":\"").append(jsonEscape(tick))
                .append("\",\"fortressDp\":\"")
                .append(jsonEscape(OpsMatchRows.fortressDpFieldPrefill(propsLines)))
                // The live-roster wave-DP prefill.
                .append("\",\"waveRosterFullDp\":\"")
                .append(jsonEscape(OpsMatchRows.waveRosterFullDpFieldPrefill(propsLines)))
                // The roster-multiplier prefill ("1" absent).
                .append(prefillMember("waveRosterMultiplier",
                        OpsMatchRows.waveRosterMultiplierFieldPrefill(propsLines)))
                .append(prefillMember("joinerDefault", OpsMatchRows.joinerDefaultFieldPrefill(propsLines)))
                .append(prefillMember("fortressWaveScale",
                        OpsMatchRows.fortressWaveScaleFieldPrefill(propsLines)))
                .append(prefillMember("waveCategory", OpsMatchRows.waveCategoryFieldPrefill(propsLines)))
                .append(prefillMember("endless", OpsMatchRows.endlessFieldPrefill(propsLines)))
                .append(prefillMember("modsEnabled", OpsMatchRows.modsEnabledFieldPrefill(propsLines)))
                .append(prefillMember("modsOptional", OpsMatchRows.modsOptionalFieldPrefill(propsLines)))
                .append(prefillMember("terrain", OpsMatchRows.terrainFieldPrefill(propsLines)))
                .append(prefillMember("terrainLevel", OpsMatchRows.terrainLevelFieldPrefill(propsLines)))
                .append(prefillMember("asteroids", OpsMatchRows.asteroidsFieldPrefill(propsLines)))
                .append("\",\"bindHost\":\"").append(jsonEscape(bindPrefill[0]))
                .append("\",\"bindPort\":\"").append(jsonEscape(bindPrefill[1]))
                .append("\",\"advertise\":\"").append(jsonEscape(advertise))
                .append("\",\"gameRoot\":\"").append(jsonEscape(gameRoot))
                .append("\",\"advertiseFrom\":\"").append(jsonEscape(advertiseFrom))
                .append("\",\"advertiseBind\":\"").append(jsonEscape(advertiseBind))
                .append("\",\"joinHere\":").append(joinHere(System.getProperty("os.name")))
                .append(",\"gameRootSecond\":\"").append(jsonEscape(gameRootSecond))
                .append("\",\"uplinkDefault\":")
                .append(jsonNumber(uplinkDefault))
                .append(",\"pvp\":\"").append(pvp)
                .append("\",\"seatsPerSource\":\"").append(jsonEscape(OpsMatchRows.seatsPerSourceFieldPrefill(propsLines)))
                .append("\",\"allSkills\":\"").append(jsonEscape(OpsMatchRows.allSkillsFieldPrefill(propsLines)))
                .append("\",\"rngPins\":\"").append(jsonEscape(OpsMatchRows.rngPinsFieldPrefill(propsLines)))
                .append("\",\"magicSeam\":\"").append(jsonEscape(OpsMatchRows.magicSeamFieldPrefill(propsLines)))
                .append("\",\"pilotShip\":\"").append(jsonEscape(OpsMatchRows.pilotShipFieldPrefill(propsLines)))
                .append("\",\"selectedIndex\":").append(opts.selectedIndex())
                .append(',').append(selectedFacts)
                .append(",\"options\":[").append(options).append("]}");
        return sb.toString();
    }

    /** The {@link #configJson} {@code pvp} member = the fixed word
     *  {@code on} when the SELECTED option decodes to {@link MatchDescriptors#PVP_SHAPE}, else {@code off} (no
     *  selection, an undecodable boots-as-is row, any other shape) - the page's PvP mode prefill, so a Start that
     *  re-sends it finds no switch. */
    static String selectedPvpWord(DescriptorOptions opts) {
        int i = opts.selectedIndex();
        DescriptorOption o = i >= 0 && i < opts.options().size() ? opts.options().get(i) : null;
        return o != null && o.info() != null && MatchDescriptors.isPvpShape(o.info().shape()) ? "on" : "off";
    }

    /** One {@link #configJson} string member's tail —
     * {@code ","<key>":"<escaped value>} (the open quote is closed by the next member; {@code null}
     * = {@code ""}). */
    private static String prefillMember(String key, String value) {
        return "\",\"" + key + "\":\"" + jsonEscape(value == null ? "" : value);
    }

    /** One sidecar's SECRET-FREE facts as JSON members —
     * {@code "sidecar":…,"adminSidecar":…,"hasPassword":…,"hasAdmin":…} (row-space names, both
     * {@code null} with no descriptor row). The match sidecar is read ONLY to learn whether a
     * NON-BLANK {@code password=} row exists (a blank row is no saved password — the package launcher
     * refuses it, {@code ServerOpsLaunch.PACKAGE_PASSWORD_REQUIRED});
     * its value never leaves this method. An unreadable sidecar /
     * an unparsable row path = {@code false} with one stderr line naming the exception class
     * (no path, no value), the {@link #terrainBackdropPrefill} fail-loud idiom.
     *
     * <p>The NAMES come from {@code sidecarRow}
     * (where this option's Start writes), the two FACTS from {@code factsRow} - the same row in owner mode, the
     * KEPT one ({@link #packageKeptPassword}'s standing sidecar) in a package, so every option reads the ONE
     * package password ({@code null} = both false). */
    private static String sidecarFactsJson(Path propsFile, String sidecarRow, String factsRow) {
        String adminRow = adminSidecarOf(sidecarRow);
        boolean hasPassword = false;
        boolean hasAdmin = false;
        if (sidecarRow != null && factsRow != null) {
            try {
                hasAdmin = Files.isRegularFile(configRelative(propsFile, adminSidecarOf(factsRow)));
                Path sidecar = configRelative(propsFile, factsRow);
                String saved = Files.isRegularFile(sidecar) ? sidecarPasswordRowValue(
                        Files.readAllLines(sidecar, StandardCharsets.UTF_8)) : null;
                hasPassword = saved != null && !saved.isBlank();
            } catch (IOException | RuntimeException unreadable) {
                System.err.println("[ops] config: sidecar unreadable ("
                        + unreadable.getClass().getSimpleName() + ") - hasPassword=false");
            }
        }
        return "\"sidecar\":" + jsonQuotedOrNull(sidecarRow) + ",\"adminSidecar\":"
                + jsonQuotedOrNull(adminRow) + ",\"hasPassword\":" + hasPassword
                + ",\"hasAdmin\":" + hasAdmin;
    }

    /** {@code null} = the JSON literal {@code null}, else the escaped quoted string. */
    private static String jsonQuotedOrNull(String s) {
        return s == null ? "null" : "\"" + jsonEscape(s) + "\"";
    }

    // ---- release parity: the Recording texts, the reachability self-dial, the Mods Remove note ----

    /** The Setup Recording items, in {@code record=} row order (off, diag, full). */
    public static final List<String> RECORD_ITEMS = List.of("off",
            "diagnostics (timing logs: up to a few hundred MB)", "full (also all network traffic: large)");
    public static final String RECORD_TIP = "Saves files into this start's run folder for bug reports. full writes up"
            + " to record.maxMiB (default 4096 MiB) of network traffic each time the server starts, and again each time"
            + " it restarts itself after a crash (up to 6 per Start); it includes chat and names.";
    public static final String REACH_LABEL = "Check from the internet";
    public static final String REACH_TIP = "Sends a test packet from this computer to the Advertise address and waits"
            + " for the running server to answer through the internet.";
    /** The status line both shells show for {@link OpsSnapshot#reach()}: this prefix + the dial's note. */
    public static final String REACHABILITY_PREFIX = "Reachability: ";
    /** A press while a dial of ANOTHER address runs (a press of the same address joins that dial). */
    public static final String REACH_BUSY = "A reachability check of another address is still running: wait a few"
            + " seconds, then press again.";
    public static final String MODS_REMOVE_TIP = "sets the picked mod to off; one inside serve-mods stays listed until"
            + " you delete its folder there, one added from elsewhere stops being searched. Nothing on disk is deleted.";
    static final String MODS_REMOVED_REPAIRED = " - the search list names serve-mods again, so mods removed earlier"
            + " show again, set to off";
    /** The press waits at most this long for its flight: the dial's 3.8 s ceiling + one DNS lookup + margin. */
    public static final long REACH_PRESS_WAIT_MS = 8_000L;
    /** The auto check's LIVE poll ({@link LaneFollow#launchPhase}), within {@link #LAUNCH_GRACE_MS}. */
    static final long REACH_LIVE_POLL_MS = 1_000L;
    /** The auto check's daemon (one per armed launch; it waits for LIVE, then dials once). */
    static final String REACH_CHECK_THREAD = "cc-reach-check";
    /** The ONE dial's daemon (press or auto): the DNS lookup and the socket live here, never on a request thread. */
    static final String REACH_DIAL_THREAD = "cc-reach-dial";

    /** One reachability dial: {@code host} as typed or resolved, {@code port} 1..65535, the mode's sentence. */
    @FunctionalInterface
    interface ReachDialer {
        ReachProbe.Result dial(String host, int port, boolean protonVpn);
    }

    /**
     * The dial seam: {@link ReachProbe#dial} with {@link ReachProbe.Dials#DEFAULT} and a fresh SecureRandom nonce (a
     * signed long, both signs). A selftest row swaps it (a counting / shrunk dialer) and restores it in a finally.
     *
     *
     */
    static volatile ReachDialer reachDialer = (host, port, protonVpn) -> ReachProbe.dial(host, port,
            new java.security.SecureRandom().nextLong(), ReachProbe.Dials.DEFAULT, protonVpn);

    /** The dial in flight with its target key ({@code host:port}, trimmed); a done future = none. */
    record ReachFlight(String target, java.util.concurrent.CompletableFuture<ReachProbe.Result> result) {
    }

    /**
     * The ONE shared reachability dial, both consoles' (the
     * {@code OpsConsole.leaseFlight} idiom, keyed by target): a request for the target in flight JOINS it (one
     * dial, one result for all); another target while one runs = busy ({@link #REACH_BUSY}); none running = a new
     * flight on a {@link #REACH_DIAL_THREAD} daemon. Never taken under a shell's start lock.
     */
    static final java.util.concurrent.atomic.AtomicReference<ReachFlight> reachFlight =
            new java.util.concurrent.atomic.AtomicReference<>();
    /** The last finished dial (console-wide), attached to snapshots by {@link #withReach}; null = none yet. */
    static final java.util.concurrent.atomic.AtomicReference<OpsSnapshot.Reach> LAST_REACH =
            new java.util.concurrent.atomic.AtomicReference<>();
    /** The launch the auto check last armed (one CAS per launch: a re-arm of the same launch is refused). */
    static final java.util.concurrent.atomic.AtomicReference<LaneFollow.LaneLaunch> REACH_ARMED =
            new java.util.concurrent.atomic.AtomicReference<>();
    /** Meters: auto-check polls whose status file did not parse (a mid-write read; the poll goes on), auto
     *  checks that ended without a dial (FAILED / TIMED_OUT / replaced / no advertise / busy). */
    static final java.util.concurrent.atomic.AtomicLong REACH_POLL_UNREADABLE = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong REACH_AUTO_ENDED = new java.util.concurrent.atomic.AtomicLong();

    /** {@link #checkReach(String, int, String, boolean)} with the typed-address sentence. */
    public static ReachProbe.Result checkReach(String host, int port, String advertiseText) {
        return checkReach(host, port, advertiseText, false);
    }

    /**
     * The "Check from the internet" PRESS of both consoles - one
     * dial of {@code host:port} through the shared {@link #reachFlight}, the caller waiting at most
     * {@link #REACH_PRESS_WAIT_MS} (a slower flight answers SILENT and finishes on its own thread). The note names
     * {@code advertiseText} (the host's Advertise as typed; blank = {@code host:port}). Call it OUTSIDE the start
     * lock: it blocks for up to 8 s.
     *
     * @throws IllegalArgumentException {@link ReachProbe#TYPE_ADDRESS} for a blank host
     * @throws IllegalStateException {@link #REACH_BUSY} while another address is dialed
     */
    public static ReachProbe.Result checkReach(String host, int port, String advertiseText, boolean protonVpn) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) {
            throw new IllegalArgumentException(ReachProbe.TYPE_ADDRESS);
        }
        String shown = advertiseText == null || advertiseText.isBlank() ? h + ":" + port : advertiseText.trim();
        java.util.concurrent.CompletableFuture<ReachProbe.Result> flight = joinReachFlight(h, port, shown, protonVpn);
        long t0 = System.nanoTime();
        try {
            return flight.get(REACH_PRESS_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException
                | InterruptedException slow) { // answered SILENT; the flight itself records + logs its own end
            if (slow instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            long ms = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            return new ReachProbe.Result(ReachProbe.Verdict.SILENT, ms,
                    ReachProbe.note(ReachProbe.Verdict.SILENT, shown, port, ms, protonVpn), "silent");
        }
    }

    /**
     * The shared flight for {@code host:port}: the running one when it dials the same target, a NEW one on a
     * {@link #REACH_DIAL_THREAD} daemon when none runs. The flight records {@link #LAST_REACH} and prints ONE
     * {@link #reachLogLine} per dial (joiners print nothing).
     *
     * @throws IllegalStateException {@link #REACH_BUSY} while another target's flight runs
     */
    static java.util.concurrent.CompletableFuture<ReachProbe.Result> joinReachFlight(String host, int port,
            String shown, boolean protonVpn) {
        String key = host + ":" + port;
        ReachFlight mine = new ReachFlight(key, new java.util.concurrent.CompletableFuture<>());
        ReachFlight flight = reachFlight.updateAndGet(cur -> cur == null || cur.result().isDone() ? mine : cur);
        if (flight != mine) {
            if (!flight.target().equals(key)) {
                throw new IllegalStateException(REACH_BUSY);
            }
            return flight.result();
        }
        Thread dial = new Thread(() -> {
            ReachProbe.Result r;
            try {
                ReachProbe.Result raw = reachDialer.dial(host, port, protonVpn);
                r = new ReachProbe.Result(raw.verdict(), raw.ms(),
                        ReachProbe.note(raw.verdict(), shown, port, raw.ms(), protonVpn), raw.reason());
            } catch (RuntimeException failed) { // the dial never throws for a network event; logged once below
                r = new ReachProbe.Result(ReachProbe.Verdict.SILENT, 0L,
                        ReachProbe.note(ReachProbe.Verdict.SILENT, shown, port, 0L, protonVpn), "silent");
                System.out.println("cc-console reach dial threw " + failed.getClass().getSimpleName());
            }
            LAST_REACH.set(new OpsSnapshot.Reach(reachToken(r.verdict()), r.ms(), r.note(), System.currentTimeMillis()));
            System.out.println(reachLogLine(r));
            mine.result().complete(r);
        }, REACH_DIAL_THREAD);
        dial.setDaemon(true);
        dial.start();
        return mine.result();
    }

    /** The stdout token of a verdict: {@code reached|local|silent|refused}. */
    static String reachToken(ReachProbe.Verdict v) {
        return switch (v) {
            case REACHED -> "reached";
            case REACHED_LOCAL -> "local";
            case SILENT -> "silent";
            case REFUSED -> "refused";
        };
    }

    /** The ONE stdout row per dial: bounded tokens only, never the address ({@link ReachProbe#REASONS}). */
    static String reachLogLine(ReachProbe.Result r) {
        return "cc-console reach result=" + reachToken(r.verdict()) + " reason=" + r.reason();
    }

    /** {@code snap} carrying the console's last dial ({@link OpsSnapshot#withReach}); null stays null. */
    public static OpsSnapshot withReach(OpsSnapshot snap) {
        return snap == null ? null : snap.withReach(LAST_REACH.get());
    }

    /** The status line for a dial: {@link #REACHABILITY_PREFIX} + its note; null = no line. */
    public static String reachabilityLine(OpsSnapshot.Reach reach) {
        return reach == null ? null : REACHABILITY_PREFIX + reach.note();
    }

    /** {@code "reach":} for the web status: {@code {"verdict","ms","note","at"}} or {@code null}. */
    public static String reachJson(OpsSnapshot.Reach reach) {
        return reach == null ? "null" : "{\"verdict\":\"" + jsonEscape(reach.verdict()) + "\",\"ms\":" + reach.ms()
                + ",\"note\":\"" + jsonEscape(reach.note()) + "\",\"at\":" + reach.atMs() + "}";
    }

    /**
     * The AUTO check - a STARTED answer in the ProtonVPN mode arms
     * ONE {@link #REACH_CHECK_THREAD} daemon per launch ({@link #REACH_ARMED}: the same launch again = false), which
     * polls {@link LaneFollow#launchPhase} every {@link #REACH_LIVE_POLL_MS} on {@code launch}'s status file. It ENDS
     * without a dial at FAILED / TIMED_OUT ({@link #LAUNCH_GRACE_MS}) or once {@code holder} no longer holds
     * {@code launch} (a Kill + Start = the next launch's own check, never two dials). At LIVE it reads the SAVED
     * {@code advertise} row of {@code launcherFile} (the in-mode Advertise), resolves its host once on this thread
     * and dials it ONLY when the address is global ({@link VpnLeaseProbe#globalIpv4}); a non-global one records
     * {@code local} without a dial. WHY a daemon: the Start answer and the brick never wait on it. No ProtonVPN
     * mode = nothing armed (false).
     *
     * @param holder the shell's last-Start holder (the web console's {@code startLaunch}); read, never written
     */
    public static boolean armReachCheck(LaneFollow.LaneLaunch launch, java.util.function.Supplier<LaneFollow.LaneLaunch> holder,
            boolean protonVpn, Path launcherFile) {
        if (!protonVpn || launch == null || holder == null || launcherFile == null) {
            return false;
        }
        LaneFollow.LaneLaunch before = REACH_ARMED.getAndSet(launch);
        if (before == launch) {
            return false;
        }
        Thread t = new Thread(() -> autoReach(launch, holder, launcherFile), REACH_CHECK_THREAD);
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** The one note the debug-watch press leaves when the console's own launch clock ({@link #LAUNCH_GRACE_MS})
     *  runs out before the door opens; a refused or dead start leaves NO note (the Start's text stands). */
    public static final String WATCH_JOIN_TIMED_OUT =
            "the server did not start listening within 4 minutes; press Join from this PC yourself when it does";
    /** The launch the debug-watch join last armed (one CAS per launch). Its OWN reference, the {@link #REACH_ARMED}
     *  twin: a reach check armed on the same launch must never un-arm it, nor it the reach check.
     * */
    static final java.util.concurrent.atomic.AtomicReference<LaneFollow.LaneLaunch> WATCH_ARMED =
            new java.util.concurrent.atomic.AtomicReference<>();
    /** The debug-watch join's daemon (one per armed launch; it waits for LIVE, then presses once). */
    static final String WATCH_JOIN_THREAD = "cc-watch-join";
    /** Meters: debug-watch join daemons that ended, by EVERY exit (pressed, press fault, FAILED, TIMED_OUT,
     *  replaced, interrupted); their polls whose status file did not parse (a mid-write read; the poll goes on). */
    static final java.util.concurrent.atomic.AtomicLong WATCH_JOIN_ENDED = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong WATCH_POLL_UNREADABLE = new java.util.concurrent.atomic.AtomicLong();

    /**
     * The release's "Launch debug WATCH" press, second half: after a Start answered STARTED with the
     * {@code server.clientDebug=true} row written, arm ONE daemon per launch (the {@link #armReachCheck} idiom, on
     * its OWN armed reference and thread name so a ProtonVPN-mode reach check on the same launch never un-arms
     * it) that polls {@link LaneFollow#launchPhase} on {@code launch}'s status file: LIVE (the lobby snapshot the
     * serve writes once its door is open) = {@code press.run()} once (the shell's own Join-from-this-PC press, its
     * one-in-flight law included) and exit; FAILED = exit silent; TIMED_OUT = {@code note.accept(}{@link
     * #WATCH_JOIN_TIMED_OUT}{@code )} and exit; {@code holder} no longer holding {@code launch} (a Kill or the
     * next Start) = exit. Never blocks the caller: the Start answer and the brick never wait on it.
     * <p>The daemon touches nothing but its four arguments and this
     * class's statics ({@link #WATCH_ARMED}, {@link #WATCH_JOIN_ENDED}, {@link #WATCH_POLL_UNREADABLE}); a press
     * that throws a {@link RuntimeException} prints ONE stdout row {@code cc-ops watch press=fault (<SimpleName>)}
     * (never the exception's message) and the daemon ends.
     *
     * @param holder the shell's last-Start holder (the web console's {@code startLaunch}); read, never written
     * @param press the shell's Join-from-this-PC press, run on the daemon thread (a Swing shell hops to the EDT)
     * @param note the shell's outcome sink (the web status's {@code watchNote}, the Swing status line)
     * <p>Its sibling for the watch's END leg is {@link #armWatchLease} (the lease the Start wrote beside the config
     * file). Only the web console arms BOTH ({@code OpsConsole.startFlow}); the Swing shell's debug WATCH arms this
     * press alone, so its {@code watch.lease} + {@code server.clientDebug=true} outlive its serve until the next
     * console Start - ACCEPTED, not fixed: the Swing window is retired, the carve removes
     * the path; owner mode's brief uses the web console.
     *
     * @return true when a daemon was armed for this launch; false for a null argument or the same launch again
     */
    public static boolean armWatchJoin(LaneFollow.LaneLaunch launch, java.util.function.Supplier<LaneFollow.LaneLaunch> holder,
            Runnable press, java.util.function.Consumer<String> note) {
        if (launch == null || holder == null || press == null || note == null) {
            return false;
        }
        LaneFollow.LaneLaunch before = WATCH_ARMED.getAndSet(launch);
        if (before == launch) {
            return false;
        }
        Thread t = new Thread(() -> watchJoin(launch, holder, press, note), WATCH_JOIN_THREAD);
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** The armed daemon's body ({@link #armWatchJoin}; the {@link #autoReach} poll loop); EVERY exit counts
     *  {@link #WATCH_JOIN_ENDED} once, the handle a selftest row awaits. */
    private static void watchJoin(LaneFollow.LaneLaunch launch, java.util.function.Supplier<LaneFollow.LaneLaunch> holder,
            Runnable press, java.util.function.Consumer<String> note) {
        try {
            while (holder.get() == launch) {
                OpsSnapshot snap = null;
                if (Files.isRegularFile(launch.opsFile())) {
                    try {
                        snap = OpsSnapshot.parseFile(launch.opsFile());
                    } catch (IOException | RuntimeException midWrite) {
                        WATCH_POLL_UNREADABLE.incrementAndGet();
                    }
                }
                LaneFollow.LaunchPhase phase = LaneFollow.launchPhase(launch, snap, snap != null && writerAlive(snap),
                        System.currentTimeMillis(), launch.exitCodeOrNull(), ServerOpsLaunch.STAGING.get());
                if (phase == LaneFollow.LaunchPhase.LIVE) {
                    try {
                        press.run();
                    } catch (RuntimeException fault) { // bounded token only: a message may carry host text
                        System.out.println("cc-ops watch press=fault (" + fault.getClass().getSimpleName() + ")");
                    }
                    return;
                }
                if (phase == LaneFollow.LaunchPhase.TIMED_OUT) {
                    note.accept(WATCH_JOIN_TIMED_OUT);
                    return;
                }
                if (phase != LaneFollow.LaunchPhase.PENDING) { // FAILED: the Start's own text stands, no note
                    return;
                }
                Thread.sleep(REACH_LIVE_POLL_MS);
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        } finally {
            WATCH_JOIN_ENDED.incrementAndGet();
        }
    }

    /** The lease daemon's thread name; its meter counts EVERY exit (cleared, kept, replaced, interrupted) — the
     *  handle a selftest row awaits. */
    static final String WATCH_LEASE_THREAD = "cc-watch-lease";
    static final java.util.concurrent.atomic.AtomicLong WATCH_LEASE_ENDED = new java.util.concurrent.atomic.AtomicLong();
    /** The most bytes a lease is read for: one stamp line; a planted long file never fills memory. */
    static final int WATCH_LEASE_READ_MAX = 128;

    /**
     * The debug WATCH's END leg. The Start wrote {@code server.clientDebug=true} into
     * the config file and the lease ({@link ServerOpsLaunch#WATCH_LEASE_FILE}) beside it; a serve that later reads
     * the file plain would inherit the debug keys, so this daemon ends the lease when the covered serve is gone. It
     * reads the lease's stamp as ITS OWN, waits while {@code holder} keeps {@code launch} and the serve process
     * lives (poll {@link #REACH_LIVE_POLL_MS}; a staging gap counts as alive), then under
     * {@link ServerOpsLaunch#PROPS_LOCK} and ONLY if the lease still carries its stamp (a newer press wrote another,
     * or a plain Start deleted it: both are left alone) deletes the lease and writes the row false. A holder that
     * moved to a newer launch ends the daemon silent: that Start owns the row. A launch with no process handle (a
     * recording spawn) is unobservable and arms nothing; the next Start's own write resets row and lease. ONE
     * stdout row at the end: {@code cc-ops watch lease=<cleared|kept|absent|fault> row=<false|unchanged|->}.
     *
     * <p>{@code atEnd} runs ONCE, on that END leg only, AFTER the clear and its row (the 3-arg form = nothing): the
     * console hands it the join settings' secret scrub ({@link OpsJoinSelf#scrubSettingsSecret}). WHY never on the
     * holder-moved exit: a newer watch may be mid-launch then, and a scrub would overwrite the save its own window is
     * writing (both sides write temp + move). A throwing {@code atEnd} is caught into ONE row
     * {@code cc-ops watch at_end=fault class=<SimpleName>} - the daemon's own accounting never depends on it.
     *
     *
     * @return true when a daemon was armed (a process handle + a readable, non-blank lease beside {@code propsFile})
     */
    static boolean armWatchLease(LaneFollow.LaneLaunch launch, java.util.function.Supplier<LaneFollow.LaneLaunch> holder,
            Path propsFile) {
        return armWatchLease(launch, holder, propsFile, () -> { });
    }

    /** {@link #armWatchLease(LaneFollow.LaneLaunch, java.util.function.Supplier, Path)} with the END leg's
     *  {@code atEnd} (never null). */
    static boolean armWatchLease(LaneFollow.LaneLaunch launch, java.util.function.Supplier<LaneFollow.LaneLaunch> holder,
            Path propsFile, Runnable atEnd) {
        if (launch == null || holder == null || propsFile == null || launch.current().get() == null) {
            return false;
        }
        Path lease = ServerOpsLaunch.watchLeaseFile(propsFile);
        String stamp;
        try {
            stamp = Files.isRegularFile(lease) ? readLeaseStamp(lease) : "";
        } catch (IOException unreadable) {
            stamp = "";
        }
        if (stamp.isEmpty()) {
            return false;
        }
        final String mine = stamp;
        Thread t = new Thread(() -> watchLease(launch, holder, propsFile, lease, mine, atEnd), WATCH_LEASE_THREAD);
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** The lease daemon's body ({@link #armWatchLease}); EVERY exit counts {@link #WATCH_LEASE_ENDED} once; the END
     *  leg (the clear) runs {@code atEnd} after its row, outside {@link ServerOpsLaunch#PROPS_LOCK}. */
    private static void watchLease(LaneFollow.LaneLaunch launch, java.util.function.Supplier<LaneFollow.LaneLaunch> holder,
            Path propsFile, Path lease, String stamp, Runnable atEnd) {
        try {
            while (holder.get() == launch && (launch.exitCodeOrNull() == null || ServerOpsLaunch.STAGING.get())) {
                Thread.sleep(REACH_LIVE_POLL_MS);
            }
            if (holder.get() != launch) {
                return; // a newer Start holds the row and the lease now
            }
            String verdict;
            synchronized (ServerOpsLaunch.PROPS_LOCK) {
                verdict = clearWatchLease(propsFile, lease, stamp);
            }
            System.out.println("cc-ops watch " + verdict);
            try {
                atEnd.run();
            } catch (RuntimeException fault) {
                System.out.println("cc-ops watch at_end=fault class=" + fault.getClass().getSimpleName());
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        } finally {
            WATCH_LEASE_ENDED.incrementAndGet();
        }
    }

    /** The clear itself (caller holds {@link ServerOpsLaunch#PROPS_LOCK}): the lease still ours → delete it and
     *  upsert the row false (the Start's own upsert, the file's line ending kept, a changed file written once); a
     *  foreign or absent lease leaves everything. Returns the stdout tokens. */
    static String clearWatchLease(Path propsFile, Path lease, String stamp) {
        try {
            if (!Files.isRegularFile(lease)) {
                return "lease=absent row=-";
            }
            if (!stamp.equals(readLeaseStamp(lease))) {
                return "lease=kept row=-";
            }
            Files.deleteIfExists(lease);
            if (!Files.isRegularFile(propsFile)) {
                return "lease=cleared row=-";
            }
            String raw = Files.readString(propsFile, StandardCharsets.UTF_8);
            List<String> now = raw.lines().toList();
            List<String> want = OpsMatchRows.upsertRow(now, OpsMatchRows.CLIENT_DEBUG_KEY, "false",
                    OpsMatchRows.CLIENT_DEBUG_COMMENT);
            if (want.equals(now)) {
                return "lease=cleared row=unchanged";
            }
            String sep = raw.contains("\r\n") ? "\r\n" : "\n";
            Files.writeString(propsFile, String.join(sep, want) + sep, StandardCharsets.UTF_8);
            return "lease=cleared row=false";
        } catch (IOException | RuntimeException fault) { // bounded token only: a message may carry a path
            return "lease=fault (" + fault.getClass().getSimpleName() + ")";
        }
    }

    /** The lease's one stamp line, trimmed, at most {@link #WATCH_LEASE_READ_MAX} bytes read. */
    private static String readLeaseStamp(Path lease) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(lease)) {
            return new String(in.readNBytes(WATCH_LEASE_READ_MAX), StandardCharsets.US_ASCII).trim();
        }
    }

    /** The armed daemon's body ({@link #armReachCheck}); every exit without a dial counts {@link #REACH_AUTO_ENDED}. */
    private static void autoReach(LaneFollow.LaneLaunch launch, java.util.function.Supplier<LaneFollow.LaneLaunch> holder,
            Path launcherFile) {
        while (true) {
            if (holder.get() != launch) {
                REACH_AUTO_ENDED.incrementAndGet();
                return;
            }
            OpsSnapshot snap = null;
            if (Files.isRegularFile(launch.opsFile())) {
                try {
                    snap = OpsSnapshot.parseFile(launch.opsFile());
                } catch (IOException | RuntimeException midWrite) {
                    REACH_POLL_UNREADABLE.incrementAndGet();
                }
            }
            LaneFollow.LaunchPhase phase = LaneFollow.launchPhase(launch, snap, snap != null && writerAlive(snap),
                    System.currentTimeMillis(), launch.exitCodeOrNull(), ServerOpsLaunch.STAGING.get());
            if (phase == LaneFollow.LaunchPhase.LIVE) {
                break;
            }
            if (phase != LaneFollow.LaunchPhase.PENDING) {
                REACH_AUTO_ENDED.incrementAndGet();
                return;
            }
            try {
                Thread.sleep(REACH_LIVE_POLL_MS);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                REACH_AUTO_ENDED.incrementAndGet();
                return;
            }
        }
        String advertise = ServeLauncher.readLauncherRows(launcherFile, System.out)
                .getOrDefault(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE, "").trim();
        String host = ServerOpsLaunch.addressPart(advertise);
        int port = ServerOpsLaunch.portOf(advertise);
        if (host.isEmpty() || port < 1 || port > 65_535) {
            REACH_AUTO_ENDED.incrementAndGet();
            System.out.println("cc-console reach skipped reason=no_advertise");
            return;
        }
        java.net.InetAddress to;
        try {
            to = java.net.InetAddress.getByName(host);
        } catch (java.net.UnknownHostException | SecurityException noAddress) {
            ReachProbe.Result r = new ReachProbe.Result(ReachProbe.Verdict.SILENT, 0L,
                    ReachProbe.note(ReachProbe.Verdict.SILENT, advertise, port, 0L, true), "dns");
            LAST_REACH.set(new OpsSnapshot.Reach(reachToken(r.verdict()), 0L, r.note(), System.currentTimeMillis()));
            System.out.println(reachLogLine(r));
            return;
        }
        if (!(to instanceof java.net.Inet4Address) || !VpnLeaseProbe.globalIpv4(to.getHostAddress())) {
            ReachProbe.Result r = new ReachProbe.Result(ReachProbe.Verdict.REACHED_LOCAL, 0L,
                    ReachProbe.note(ReachProbe.Verdict.REACHED_LOCAL, advertise, port, 0L, true), "local");
            LAST_REACH.set(new OpsSnapshot.Reach("local", 0L, r.note(), System.currentTimeMillis()));
            System.out.println(reachLogLine(r));
            return;
        }
        try {
            joinReachFlight(to.getHostAddress(), port, advertise, true)
                    .get(REACH_PRESS_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (IllegalStateException busy) {
            REACH_AUTO_ENDED.incrementAndGet();
            System.out.println("cc-console reach skipped reason=busy");
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException
                | InterruptedException slow) { // the flight records + logs its own end
            REACH_AUTO_ENDED.incrementAndGet();
        }
    }

    /**
     * true when {@code roots} holds a token resolving to a DIRECT
     * child of {@code keptRoot} (a split {@code serve-mods/<name>} row an older Remove wrote) - the row
     * {@link OpsModsPanel#rootsWithout(List, Path, InstalledModScan.InstalledMod, List, Path)} repairs. Pure; null
     * {@code keptRoot} (owner mode) = false.
     */
    public static boolean keptRootSplit(List<String> roots, Path repoRoot, Path keptRoot) {
        if (keptRoot == null || roots == null) {
            return false;
        }
        Path kept = keptRoot.toAbsolutePath().normalize();
        for (String token : roots) {
            Path r = InstalledModScan.resolveRoot(repoRoot, token);
            if (r != null && kept.equals(r.toAbsolutePath().normalize().getParent())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A packed server's ONE Mods Remove note (both shells): the
     * mod is off (one action); its folder in {@code serve-mods} stays and deleting it is the host's (said here).
     * {@code folderName} = the folder's own name (null / blank = no folder: the id alone); {@code repaired} = the
     * remove also folded a split row back ({@link #keptRootSplit}). The id is a mod_info id the scan read (host
     * disk text), cut at 64 chars.
     */
    public static String modsRemovedLine(String id, String folderName, boolean repaired) {
        String shownId = id == null ? "" : id.length() > 64 ? id.substring(0, 64) + "..." : id;
        String line = folderName == null || folderName.isBlank() ? "mods: " + shownId + " is off"
                : "mods: " + shownId + " is off - its folder " + PackageModSync.MODS_DIR + "/" + folderName
                        + " stays (delete it there to take it off the list)";
        return repaired ? line + MODS_REMOVED_REPAIRED : line;
    }

    /** Every host-facing text this section owns (the {@code launch-texts} row's input; templates unfilled). */
    static List<String> launchTexts() {
        List<String> all = new ArrayList<>(RECORD_ITEMS);
        all.addAll(List.of(RECORD_TIP, REACH_LABEL, REACH_TIP, REACHABILITY_PREFIX.trim(), REACH_BUSY,
                MODS_REMOVE_TIP, modsRemovedLine("<id>", "<name>", true), ServeLauncher.RIDER_SINK_PATH));
        return all;
    }
}
