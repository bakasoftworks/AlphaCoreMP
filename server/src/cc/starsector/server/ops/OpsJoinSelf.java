package cc.starsector.server.ops;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipException;

import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchPasswords;
import cc.starsector.server.runtime.BanSet;
import cc.starsector.server.runtime.HandshakeCodec;

/**
 * "Join from this PC" - the ONE core both package shells (Swing + web, Windows) call so a host can play on the
 * computer that serves: the player join window opens on this computer, pre-filled with this server's address,
 * port and match password. WHY one core: release parity - a package host reaches what
 * owner mode reaches with its Joiner GUI button ({@code ServerOpsLaunch.spawnJoinerGui}, a pwsh path kept as is),
 * and two shells must never hold two copies of the order below.
 *
 * <p>Shape: pure functions (each a selftest row's subject, {@code OpsJoinSelfSelftest}) + {@link #press}, an
 * ORDERED step list ({@link #STEPS}) over the {@link Effects} seams (files, the loopback ping, the ProtonVPN probe,
 * the spawn), faked in the rows and real in {@link #LIVE}. The shells supply {@link Inputs} from their own state
 * (the launcher rows read ONCE per press, the live snapshot, the saved match password) and call {@code press} off
 * the EDT / off the request thread; the core never reads {@code launcher.properties} itself.
 *
 * <p>What it touches, every number as the design states it:
 * one endpoint-file read ({@value #ENDPOINT_FILE_MAX_BYTES} B), one loopback GET ({@value #PING_TIMEOUT_MS} ms,
 * {@value #PING_BODY_MAX_BYTES} B), one join-settings read ({@value #SETTINGS_MAX_BYTES} B) + temp/move write, one
 * spawn of the kit's {@value #KIT_BAT} through a FIXED argv; when a shell supplies the package's kit stamps, step
 * (1c) reads the kit's {@value #KIT_MANIFEST} ({@value #KIT_MANIFEST_MAX_BYTES} B) and, with a fingerprint, its
 * {@value #KIT_SIDECAR} (1 MiB), before anything is written, and a stale kit with the package's kit zip at hand is
 * refreshed there: the zip read in memory ({@link KitZip#KIT_CAPS}) and verified, ONE {@value #STAGE_PREFIX}
 * folder beside {@code mods} written file by file ({@code CREATE_NEW}), the old kit renamed into
 * {@value #PARK_DIR} (never deleted) and the staged kit renamed in, {@code ATOMIC_MOVE} each, the staging folder
 * deleted. With the press armed ({@link Inputs#adminArm}: the console arms every
 * press whose live serve names a command file, {@link #armFileOrNull}, until that serve holds the door's cap of
 * armed keys - {@link Result#armLines}), step (6b) appends ONE {@code admin-cid <key>}
 * line to the live serve's command file before the spawn; {@link #pressWatch} presses twice for the debug flight's
 * two pilot windows, and {@link #scrubSettingsSecret} drops the settings' password rows at that flight's end.
 * The match password goes ONLY
 * into the join window's designed {@code password=} / {@code passwordFor=} rows - never argv, env, a stdout row, a note or a URL.
 *
 * <p>The installed kit's folder is only READ (its launcher, manifest and sidecar) - no step writes a file under it;
 * the stale-kit refresh alone replaces it, whole, by rename. WHY: the join window fetches the match document over
 * the wire from the server it dials, like every player's, so the host's kit carries no match file for the
 * console to keep in step with the serving shape.
 *
 *
 * <p>The client laws copied here
 * (read, never linked - the client tree is another build): {@code JoinSettings} (settings path + legacy),
 * {@code JoinHost} (endpoint file + install tag), {@code JoinBrowserUi.liveWindowUrl} (the single-instance ping);
 * the merge-write golden = the join-watch arm script.
 *
 *
 */
final class OpsJoinSelf {

    private OpsJoinSelf() {
    }

    // ---- bounds ----

    /** N1: the join window's endpoint file is {@code port|token|nonce} (~80 B); the client's own read refuses
     *  more ({@code JoinBrowserUi.liveWindowUrl}). */
    static final int ENDPOINT_FILE_MAX_BYTES = 256;
    /** N2: the client's single-instance ping timeout ({@code JoinBrowserUi.PING_TIMEOUT_MS}), connect AND read. */
    static final int PING_TIMEOUT_MS = 2000;
    /** N2: the ping body read is cut here (the client reads the same 1024 B); {@code "app":"join"} is early. */
    static final int PING_BODY_MAX_BYTES = 1024;
    /** N3: a join-settings file is a few hundred bytes; bigger = R6 with the file untouched. */
    static final int SETTINGS_MAX_BYTES = 64 * 1024;
    /** N5: the outer cmd's {@code start /wait} lives as long as the window it started; the wait only catches a cmd
     *  that failed to start the window (a nonzero exit). Later = opened. The spawn never kills it; the console closes
     *  a DEBUG-WATCH window when that watch's serve ends ({@link JoinWindowSweep}), a hand-pressed one never. */
    static final int SPAWN_WAIT_MS = 2000;
    /** {@link Effects#spawn}'s answer for "still running after {@link #SPAWN_WAIT_MS}" = opened. */
    static final int STILL_RUNNING = -1;
    /** The console window's title ({@code start}'s first quoted argument). */
    static final String WINDOW_TITLE = "AlphaCoreMP join";
    /** The kit's launcher; its own single-instance law re-opens a live window's tab. */
    static final String KIT_BAT = "join-cc.bat";
    /** JVM property {@code -Dcc.ops.joinWindowMinimized=true}: the join window opens minimized ({@link #argv}). Set by
     *  the proof chain's package-watch leg only; no settings row, nothing shipped reads it. */
    static final boolean JOIN_WINDOW_MINIMIZED = Boolean.getBoolean("cc.ops.joinWindowMinimized");
    /** The game's own launcher - its presence (with the kit bat) is what "a game with the kit" means. */
    static final String GAME_EXE = "starsector.exe";
    /**
     * The installed kit's own stamps, read by the kit check ({@link #kitCheckOf}) before anything is written into the
     * kit folder: {@value #KIT_MANIFEST} (the packer's five CRLF rows, {@code jarMd5} UPPERCASE; a real one is
     * ~120 B, so a file past {@value #KIT_MANIFEST_MAX_BYTES} B is not a kit manifest) and the sidecar
     * {@value #KIT_SIDECAR}, the kit's file list (its {@code fingerprint=} row = the kit's composite,
     * {@code match=} included); a kit installed before the list had that name holds none, so it reads stale.
     * {@link #KIT_STAMP_SHOWN} = the kit launcher's own value-guard charset: a {@code kitStamp} outside it is shown as
     * {@code ?}; {@link #MD5_HEX} = a 32-hex md5, either case (anything else is shown as {@code ?}).
     *
     */
    static final String KIT_MANIFEST = "kit-manifest.txt";
    static final int KIT_MANIFEST_MAX_BYTES = 4096;
    static final String KIT_SIDECAR = KitPackRules.SIDECAR_NAME;
    static final Pattern KIT_STAMP_SHOWN = Pattern.compile("[0-9A-Za-z._:-]{1,40}");
    static final Pattern MD5_HEX = Pattern.compile("[0-9A-Fa-f]{32}");
    /**
     * The kit refresh's folders, both directly under the game install (the parent of {@code mods}): ONE staging
     * folder {@value #STAGE_PREFIX}{@code <16 hex>} per refresh, extracted into BEFORE the installed kit is touched
     * (WHY: a full disk or a protected folder then fails with the old kit untouched) and deleted after; the old kit
     * renamed to {@value #PARK_DIR}{@code \CC_Client-<its kitStamp>} (a taken name gets {@code -2} ..
     * {@code -}{@value #PARK_SUFFIX_MAX}); a park is NEVER deleted. {@link #PARK_STAMP} = a kitStamp usable in a
     * Windows folder name (the launcher's charset without {@code :}, never ending in {@code .}, which Windows drops);
     * any other stamp parks as {@code unstamped}.
     */
    static final String STAGE_PREFIX = ".cc-kit-staging-";
    static final String PARK_DIR = "cc-kit-parked";
    static final int PARK_SUFFIX_MAX = 99;
    static final Pattern PARK_STAMP = Pattern.compile("[0-9A-Za-z._-]{0,39}[0-9A-Za-z_-]");
    /** N7: the dialled host as the join window's {@code host=} row may carry it (no colon, space or quote). */
    static final Pattern DIAL_HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");
    /** K3: an operator path ({@code <install>}) or kit hint ({@code <pack.kit>}) inside a sentence is cut here
     *  ({@code ServeLauncher.bounded} cuts at 200, too long for a dialog line). */
    static final int TEXT_PATH_MAX = 120;
    /** A dialled host shown in {@link #R5_SHAPE}; keeps that sentence at or under 120 chars. */
    static final int SHOWN_HOST_MAX = 60;
    /** The child env drops every key starting with this, ASCII case-blind: the kit's {@code CC_JOIN_DRYRUN} /
     *  {@code CC_ENVLAUNCH} would turn the press into a drill. */
    static final String CC_ENV_PREFIX = "cc_";
    /** The ONE env key the kit's bat appends to the game's JAVA_TOOL_OPTIONS as-is. {@link Effects#withClientDiag}
     *  puts it AFTER the {@value #CC_ENV_PREFIX} strip, so a debug-watch press's own join window writes the client
     *  GC log + the F9 mark-frame ring under its flight folder; a plain press adds nothing. */
    static final String CLIENT_JVM_FLAGS_KEY = "CC_CLIENT_JVM_FLAGS";
    /** The folder characters the double-quoted flag can carry through the bat's {@code set} and HotSpot's
     *  JAVA_TOOL_OPTIONS parser: letters, digits, {@code . _ : \ -} and the SPACE (measured); a path with a
     *  quote, a cmd metacharacter ({@code ( ) & ^ ! %}) or a non-ASCII letter skips both diagnostics (one stdout
     *  token, never the path) - unmeasured on the kit's cmd line, so refused rather than guessed. */
    static final Pattern DIAG_DIR_CHARS = Pattern.compile("[A-Za-z0-9._: \\\\-]+");
    /** The F9 ring's sub-folder under the diag folder ({@code cc.markframes.dir}); the GC log sits beside it. */
    static final String DIAG_FRAMES_DIR = "frames";
    /**
     * The debug flight's two-window keys, each put AFTER the {@value #CC_ENV_PREFIX} strip and only by this press
     * (an inherited copy never reaches a window): {@link #PREDICT_CID_KEY} = an env-delivered seat key - the SECOND
     * window's own, and any ARMED window's per-press mint (the client's set twin beats the
     * kit handoff's persisted id) - its own env key, never inside {@link #CLIENT_JVM_FLAGS_KEY}, because the kit's bat hands those flags to the game
     * JVM's JAVA_TOOL_OPTIONS, which the JVM echoes in its "Picked up" banner and a crash file writes out, and a
     * seat key never rides that path; {@link #WATCH_SIDELOG_KEY} = the window's client log file
     * {@code <diag folder>\}{@value #SIDELOG_FILE}, one file per window because the client's log appender APPENDS
     * (two windows on one file would interleave). {@link #DIAG_FIRST} / {@link #DIAG_SECOND} = each window's diag
     * folder under the flight folder. {@link #CID_MAX_CHARS} = the door's sendable seat-id length (each char
     * {@code 0x21..0x7E}).
     */
    static final String PREDICT_CID_KEY = "CC_PREDICT_CLIENT_ID";
    static final String WATCH_SIDELOG_KEY = "CC_WATCH_SIDELOG";
    static final String SIDELOG_FILE = "cc-client.log";
    static final String DIAG_FIRST = "client";
    static final String DIAG_SECOND = "client2";
    static final int CID_MAX_CHARS = 64;
    /**
     * The debug flight's client log cap. {@link #WATCH_SIDELOG_MAX_MIB_KEY} rides beside {@link #WATCH_SIDELOG_KEY}
     * on every window that gets a sidelog, under the same law (put AFTER the {@value #CC_ENV_PREFIX} strip, never an
     * inherited copy); its value = the launcher row {@link #ROW_WATCH_CLIENT_LOG_MAX_MIB} in MiB per window, read by
     * {@link #watchClientLogMaxMib}: blank or absent = {@link #WATCH_CLIENT_LOG_DEFAULT_MIB}, else a whole number in
     * {@link #WATCH_CLIENT_LOG_MIN_MIB}..{@link #WATCH_CLIENT_LOG_MAX_MIB}; anything else refuses the press
     * ({@link #R18}) before it reads anything. WHY a cap: each window writes about 200 MB per hour with every row
     * kept, so a host's long debug flight could fill the disk; 1024 MiB holds about five hours per window. At the
     * cap the kit restarts the file in place (its first rows kept, one marker row saying how much was dropped).
     * The range is the kit's own: a value outside it, the kit caps at 96 MiB rather than not at all.
     *
     */
    static final String WATCH_SIDELOG_MAX_MIB_KEY = "CC_WATCH_SIDELOG_MAX_MIB";
    static final String ROW_WATCH_CLIENT_LOG_MAX_MIB = "watch.clientLogMaxMiB";
    static final int WATCH_CLIENT_LOG_DEFAULT_MIB = 1024;
    static final int WATCH_CLIENT_LOG_MIN_MIB = 96;
    static final int WATCH_CLIENT_LOG_MAX_MIB = 65536;

    // ---- the client's file laws (copies of the JoinSettings and JoinHost constants) ----

    /** {@code JoinSettings.DIR_NAME}. */
    static final String SETTINGS_DIR_NAME = "cc-starsector";
    /** {@code JoinSettings.FILE_NAME}. */
    static final String SETTINGS_FILE_NAME = "join-settings.properties";
    /** {@code JoinHost.endpointFile}'s sub-folder. */
    static final String ENDPOINT_DIR_NAME = "join";
    /** {@code JoinHost.ENDPOINT_FILE_PREFIX}. */
    static final String ENDPOINT_FILE_PREFIX = "cc_join_endpoint-";
    /** {@code JoinHost.TOKEN_HEX_CHARS}. */
    static final int ENDPOINT_TOKEN_HEX_CHARS = 64;

    // ---- texts ----

    /** The refusal dialog's title. */
    static final String TITLE = "Join refused";
    static final String LABEL = "Join from this PC";
    static final String TIP = "Opens the player join window on this computer, filled in with this server's"
            + " address, port and match password - press Launch in it. Needs the player kit (CC_Client) in"
            + " your game's mods folder.";
    /**
     * R2's {@code %2$s} kit hint ({@link Inputs#kitHint}) in
     * Join from this PC - ONE home, so no second copy can drift.
     * WHY words, not a file name:
     * {@link PackageManifest} carries no kit-file-name row (only {@code git.head} / {@code kit.jar.md5}),
     * so the sentence names the joiner kit zip by kind. K3-checked by {@code join-texts}.
     */
    static final String KIT_HINT = "the joiner kit zip";
    /** OPENED: {@code %s:%d} = the dialled host and port. */
    static final String NOTE_OPENED = "Join window opening (it dials %s:%d) - press Launch in it.";
    /**
     * REOPENED: a join window is already live (nothing written, nothing spawned, nothing armed).
     *
     * <p>Both REOPENED notes name the host's way back to admin after a
     * server restart. WHY a REOPENED press arms nothing: the window's seat key was minted for ONE press and lives
     * only in that press, so a re-arm would need the console to keep a grant credential across serves; the
     * window's key was armed for the serve life it was pressed in, so a restarted serve admits it plain.
     */
    static final String NOTE_REOPENED = "Your join window is already open - its tab was brought back. After a"
            + " server restart it is no longer signed in as host: sign in from the ship editor's 'Ships on this"
            + " server' card, or close that window's console and press Join from this PC again (also needed when"
            + " the port changed).";
    /** REOPENED in the web shell (the bat is started without a write or an arm; its own law re-opens the tab). */
    static final String NOTE_REOPENED_WEB = "Your join window is already open on the server computer - its tab"
            + " is brought back there; the small console that did it closes on any key. After a server restart that"
            + " window is no longer signed in as host: sign in from the ship editor's 'Ships on this server' card,"
            + " or close the join window and press Join from this PC again.";
    static final String R1 = "Game folder is not set - type your Starsector folder into Game folder (Setup tab),"
            + " press Start, then Join from this PC.";
    /** {@code %1$s} = the install, {@code %2$s} = the kit hint. */
    static final String R2 = "No player kit in this game: %1$s\\mods\\CC_Client\\join-cc.bat or"
            + " %1$s\\starsector.exe is missing. Extract CC_Client from %2$s into %1$s\\mods\\, then press Join"
            + " from this PC again.";
    static final String R4 = "Start the server first, then press Join from this PC.";
    static final String R5 = "The server listens on an IPv6 address, which the join window cannot dial - set"
            + " Listen on host to 0.0.0.0 and restart.";
    /** R5's sibling: {@code %s} = the host, sanitized and cut. */
    static final String R5_SHAPE = "The server address %s cannot be dialled by the join window";
    /** {@code %1$s} = the install, {@code %2$s} = the exception's simple name. */
    static final String R6 = "Your saved join settings could not be updated (%2$s) - nothing was started. Run"
            + " %1$s\\mods\\CC_Client\\join-cc.bat yourself.";
    /** {@code %1$s} = the install, {@code %2$s} = the exit code or the exception's simple name. */
    static final String R7 = "Windows did not open the join window (%2$s) - run %1$s\\mods\\CC_Client\\join-cc.bat"
            + " yourself.";
    /** The shells' one-press-in-flight refusal (N6). */
    static final String R8 = "The join window is already being opened - one moment.";
    /** The web page's {@code #join-linux} line. */
    static final String R9 = "Joining from this computer needs Windows: the player kit runs on Windows only. Join"
            + " from a Windows PC with the code from Copy join brick.";
    static final String R10 = "ProtonVPN did not answer - reconnect it and press Join from this PC again";
    /**
     * The kit check found the installed player kit is not this server's (its jar md5, or its jar / data / handshake
     * composite, differs from the package's stamps; a missing stamp file is the same answer) and no refresh ran;
     * nothing was written or started. {@code %1$s} = the installed kit's stamps as {@link #stampShown} renders them,
     * {@code %2$s} = the package jar md5's first 8 hex, {@code %3$s} = the kit hint, {@code %4$s} = the game's
     * {@code mods} folder (both bounded).
     */
    static final String R12 = "Your game's player kit is not this server's (yours: %1$s; this server's: jar %2$s) -"
            + " extract the CC_Client folder from %3$s into %4$s, replacing the old one, then press again.";
    /**
     * The kit refresh's answers. {@link #R13}: a stale kit while its join window is live (the window's game holds
     * the kit's files open) - nothing read or moved. {@link #R14}: the refresh stopped before the installed kit was
     * touched: {@code %1$s} = the installed stamps ({@link #stampShown}), {@code %2$s} = the package jar md5's first
     * 8 hex, {@code %3$s} = one {@code WHY_*} reason (or "a file operation failed (SimpleName)"), {@code %4$s} = the
     * kit zip, {@code %5$s} = the game's {@code mods} folder; or the new kit could not be moved in and the old one
     * was put back ({@link #WHY_ROLLED_BACK}). {@link #R15}: the old kit is parked and could NOT be put back:
     * {@code %1$s} = the park, {@code %2$s} = the {@code mods} folder. {@link #NOTE_KIT_REFRESHED}: the refresh
     * landed and the press went on; {@code %s} = the park. Every path bounded ({@link #boundedText}).
     *
     */
    static final String R13 = "Your game's player kit must be updated to this server's, but its join window is open"
            + " - close the join window and the game, then press again.";
    static final String R14 = "Your game's player kit (%1$s) is not this server's (jar %2$s) and could not be updated:"
            + " %3$s - nothing was changed; extract the CC_Client folder from %4$s into %5$s, replacing the old one,"
            + " then press again.";
    static final String R15 = "Updating your game's player kit stopped half way: your old kit is at %1$s - move that"
            + " folder back into %2$s as CC_Client, then press again.";
    static final String NOTE_KIT_REFRESHED = "Updated your game's player kit from this server's kit zip; the old one"
            + " is at %s.";
    static final String WHY_ZIP_MISMATCH = "the kit zip does not match this server";
    static final String WHY_NOT_WRITABLE = "the game folder could not be written";
    static final String WHY_IN_USE = "a file in the old kit is in use - close the game";
    static final String WHY_ROLLED_BACK = "the new kit could not be moved in, so the old one was put back";
    /**
     * OPENED when this press armed the window's seat key for elevation (its operator line reached the serve's
     * command file before the spawn): the window signs in as admin with nothing pasted. {@code %s:%d} = the dialled
     * host and port. A press that armed nothing answers {@link #NOTE_OPENED}.
     *
     */
    static final String NOTE_OPENED_ADMIN = "Join window opening (it dials %s:%d) - press Launch in it; paste"
            + " nothing - it signs in as admin.";
    /**
     * The second pilot window's answers (a debug flight with a second Game folder saved): {@link #R16} = that
     * folder holds no Starsector core jar; {@link #R17} = it is the first window's own game through its links (one
     * join endpoint file per game folder, so the second press would only bring the first window back). {@code %s}
     * = the saved row, bounded. On either refusal neither window is read, written or started.
     * {@link #SECOND_PREFIX} leads the second window's answer after the first's; {@link #NOTE_SECOND_LIVE} is that
     * answer when the second game's join window is already open.
     */
    static final String R16 = "Second game folder is not a Starsector folder (%s) - fix it on the Setup tab, or clear"
            + " it for one window, then press again.";
    static final String R17 = "Second game folder is the same game as the first (%s) - each window needs its own"
            + " Starsector folder; fix it on the Setup tab, or clear it, then press again.";
    /** The debug flight's client-log row does not parse: {@code %1$s} = the row's name, {@code %2$s} = its value,
     *  bounded. The name rides a slot: the join texts' dev-word law keeps that word out of every fixed sentence, and
     *  the name is shown only to a host who wrote the row. */
    static final String R18 = "The row %1$s in launcher.properties must be blank or a whole number from 96 to 65536"
            + " (MiB of client log per game window), not '%2$s' - fix the row, then press again.";
    static final String SECOND_PREFIX = "Second game folder: ";
    static final String NOTE_SECOND_LIVE = "its join window is already open - close it to open a fresh one.";

    // ---- types ----

    /**
     * One press's inputs, supplied by the shell. {@code launcherRows} = {@link ServeLauncher#readLauncherRows}'
     * map, read ONCE per press; {@code liveOrNull} = the followed status snapshot while its writer is alive;
     * {@code passwordOrBlank} = the SAVED match-password sidecar's value (secret: {@link #toString} never shows
     * it); {@code kitHint} = the {@code <pack.kit>} text for R2 (bounded here). No input names a match file: the
     * join window fetches the match over the wire. {@code propsPathOrNull} is read by no step.
     *
     * <p>The console-launched window's own facts: {@code adminArm} = the press arms each window's
     * seat key through the serve's command file - the console sets it on every press whose live serve names one
     * ({@link #armFileOrNull}), no admin secret read, until that serve holds the
     * door's cap of armed keys; the window's password row
     * carries the match password (the door elevates by cid, never by a second password - a pasted brick can no
     * longer downgrade the host's own window); {@code kitJarMd5OrNull} = the package MANIFEST's {@code kit.jar.md5} (lowercase 32 hex) the installed
     * kit's {@code kit-manifest.txt} is compared against BEFORE anything is written into it, null = no compare;
     * {@code kitFingerprintOrNull} = the MANIFEST's {@code kit.fingerprint} (the kit sidecar's FULL composite, its
     * own {@code match=} included; the kit check strips {@code ;match=} on BOTH sides) the installed kit's composite
     * is also compared against (WHY: a data-only kit change keeps the jar md5), null = the md5 compare alone;
     * {@code kitZipOrNull} = the package's
     * own copy of the joiner kit zip ({@code kits/<pack.kit>}), the refresh source for a stale kit, null = none
     * shipped (the refusal then names the zip to extract by hand).
     */
    record Inputs(Path packageRoot, Map<String, String> launcherRows, OpsSnapshot liveOrNull,
            String passwordOrBlank, String kitHint, String localAppData, String appData, String userHome,
            Path propsPathOrNull, boolean adminArm, String kitJarMd5OrNull,
            String kitFingerprintOrNull, Path kitZipOrNull) {

        /** The earlier shape: no admin arm, no kit stamps - the rows and shells that predate them. */
        Inputs(Path packageRoot, Map<String, String> launcherRows, OpsSnapshot liveOrNull,
                String passwordOrBlank, String kitHint, String localAppData, String appData, String userHome,
                Path propsPathOrNull) {
            this(packageRoot, launcherRows, liveOrNull, passwordOrBlank, kitHint, localAppData, appData, userHome,
                    propsPathOrNull, false, null, null, null);
        }

        /** These inputs with {@code adminArm} replaced. */
        Inputs withAdminArm(boolean arm) {
            return new Inputs(packageRoot, launcherRows, liveOrNull, passwordOrBlank, kitHint, localAppData,
                    appData, userHome, propsPathOrNull, arm, kitJarMd5OrNull, kitFingerprintOrNull, kitZipOrNull);
        }

        /** These inputs with the package's three kit stamps replaced (any may be null;
         *  {@link ServerOpsCore#packageKitStamps}'s record in the same order). */
        Inputs withKitStamps(String jarMd5OrNull, String fingerprintOrNull, Path zipOrNull) {
            return new Inputs(packageRoot, launcherRows, liveOrNull, passwordOrBlank, kitHint, localAppData,
                    appData, userHome, propsPathOrNull, adminArm, jarMd5OrNull, fingerprintOrNull, zipOrNull);
        }

        /** These inputs with {@code launcherRows} replaced - the second pilot window is the same press over its own
         *  Game folder row; the password, the arm and the kit stamps are carried over, so
         *  that window runs its own kit check over its own game.
         * */
        Inputs withLauncherRows(Map<String, String> rows) {
            return new Inputs(packageRoot, rows, liveOrNull, passwordOrBlank, kitHint, localAppData, appData,
                    userHome, propsPathOrNull, adminArm, kitJarMd5OrNull, kitFingerprintOrNull, kitZipOrNull);
        }

        /** Never the password (a shell logging its inputs must not leak it). */
        @Override
        public String toString() {
            return "OpsJoinSelf.Inputs[password=" + (passwordOrBlank == null || passwordOrBlank.isBlank()
                    ? "blank" : "set") + " adminArm=" + adminArm + "]";
        }
    }

    /** OPENED = the window was started; REOPENED = a live window answered (nothing written or started);
     *  REFUSED = a refusal sentence, titled {@link #TITLE}. */
    enum Outcome { OPENED, REOPENED, REFUSED }

    /**
     * A press's answer. {@code text} = the note (OPENED / REOPENED) or the refusal sentence (REFUSED) - a plain
     * String, so a new refusal slots in without a new member. {@code liveUrlOrNull} = the live
     * window's URL on REOPENED (Swing browses it; the web shell ignores it - no window URL crosses HTTP).
     * {@code reasonToken} = a bounded token for the stdout row ({@code r1}..{@code r10}, {@code spawn_nonzero},
     * ...; {@code -} when not refused), never a path, host, port or secret.
     *
     * <p>{@code armLines} = the {@code admin-cid} lines this call
     * APPENDED to the serve's command file, 0..2 (one per window it pressed: 0 or 1 for {@link #press}, up to 2
     * for {@link #pressWatch}), whatever the outcome - a window whose spawn failed after its arm still holds its
     * key at the door. WHY carried: each appended line takes one of the door's per-serve armed-key slots, so the
     * console counts these to stop arming before the door starts refusing keys it never reports back. A failed
     * or skipped append counts 0.
     */
    record Result(Outcome outcome, String text, String liveUrlOrNull, String reasonToken,
            List<JoinWindowSweep.Window> windows, int armLines) {
        /** {@code windows} = the join windows this press opened AND recorded - only the debug watch's
         *  ({@link #pressWatch}) records; empty otherwise, never null. */
        Result {
            windows = windows == null ? List.of() : List.copyOf(windows);
            if (armLines < 0) {
                throw new IllegalArgumentException("armLines < 0");
            }
        }

        /** A result that appended no arm line. */
        Result(Outcome outcome, String text, String liveUrlOrNull, String reasonToken,
                List<JoinWindowSweep.Window> windows) {
            this(outcome, text, liveUrlOrNull, reasonToken, windows, 0);
        }

        /** A result that opened no recorded window. */
        Result(Outcome outcome, String text, String liveUrlOrNull, String reasonToken) {
            this(outcome, text, liveUrlOrNull, reasonToken, List.of());
        }
    }

    /**
     * {@link Effects#spawnWindow}'s answer: {@code code} = {@link Effects#spawn}'s; {@code handleOrNull} = the started
     * process (the outer cmd, alive as long as its window) and {@code startOrNull} = its start instant read right after
     * the start, the pid-reuse guard of a later sweep; both null when the seam started no real process (a double) or
     * the OS would not say.
     */
    record Spawned(int code, ProcessHandle handleOrNull, Instant startOrNull) {
    }

    /**
     * The press's seams. {@link #LIVE} = the real ones; the rows fake every method. Thread: any (the shells
     * call {@link #press} off the EDT / request thread).
     */
    interface Effects {
        boolean isRegularFile(Path file);

        /** The file's bytes; a file over {@code cap} THROWS {@code IOException("over cap")} (the caller maps
         *  it: the endpoint file = no live window, the settings file = R6). */
        byte[] readBounded(Path file, int cap) throws IOException;

        /** {@code bytes} to a temp sibling, then {@code ATOMIC_MOVE} over {@code file}; the parent folder is
         *  created first. Reached by the join-settings writes only ({@link #step6Write}, {@link #scrubSettingsSecret})
         *  - never a file under the kit folder. */
        void writeAtomic(Path file, byte[] bytes) throws IOException;

        /** {@code dir} and every missing parent created ({@code Files.createDirectories}; an existing folder is
         *  fine). Reached by {@link #withClientDiag}'s spawn only - the join window's diag folders. */
        void createDirectories(Path dir) throws IOException;

        /** {@code GET http://127.0.0.1:<port>/<token>/api/ping}: the body (at most
         *  {@link #PING_BODY_MAX_BYTES}) on HTTP 200, else null; a silent or refused socket = null. */
        String pingBody(int port, String token) throws IOException;

        /** The ProtonVPN gateway + log probe ({@link VpnLeaseProbe#pull}). */
        VpnLeaseProbe.Probe probe() throws IOException;

        /** Starts {@code argv} in {@code cwd} with exactly {@code env}, stdin closed, out / err discarded;
         *  the exit code when it exits within {@link #SPAWN_WAIT_MS}, else {@link #STILL_RUNNING} (never
         *  killed here). */
        int spawn(List<String> argv, Path cwd, Map<String, String> env) throws IOException;

        /** {@link #spawn} keeping the started process's handle - the seam {@link #step7Spawn} uses, so a debug-watch
         *  window can be closed when its serve ends. The default = {@link #spawn}'s code with NO handle (a double that
         *  starts no process); {@link Live} answers the real one. */
        default Spawned spawnWindow(List<String> argv, Path cwd, Map<String, String> env) throws IOException {
            return new Spawned(spawn(argv, cwd, env), null, null);
        }

        Map<String, String> parentEnv();

        /** {@code %SystemRoot%}; blank = {@code C:\Windows}. */
        String systemRoot();

        /**
         * The kit refresh's six file seams ({@link #refreshOrRefuse}), each a {@code default} that THROWS so an older
         * implementor still compiles; {@link Live} implements them and BOTH wrappers ({@link #withProbe},
         * {@link #withClientDiag}) delegate them. This one: {@code p} through its links ({@code toRealPath}).
         *
         */
        default Path realPath(Path p) throws IOException {
            throw new UnsupportedOperationException("realPath");
        }

        /** The kit zip's file entries, inflated in memory under {@link KitZip#KIT_CAPS} ({@link KitZip#read}). */
        default List<KitZip.Entry> readKitZip(Path zip) throws IOException {
            throw new UnsupportedOperationException("readKitZip");
        }

        /** A NEW empty folder in {@code root} named {@value #STAGE_PREFIX}{@code <16 random hex>}; a taken name throws. */
        default Path createStage(Path root) throws IOException {
            throw new UnsupportedOperationException("createStage");
        }

        /** {@code bytes} into a NEW file, its parent folders created first; an existing file throws. */
        default void writeNew(Path file, byte[] bytes) throws IOException {
            throw new UnsupportedOperationException("writeNew");
        }

        /** The folder {@code from} renamed to {@code to} in ONE {@code ATOMIC_MOVE}, {@code to}'s parent created
         *  first; a taken {@code to} throws {@link FileAlreadyExistsException} BEFORE the move (never a replace). */
        default void moveDir(Path from, Path to) throws IOException {
            throw new UnsupportedOperationException("moveDir");
        }

        /** The staging folder and all it holds deleted; a folder not named {@value #STAGE_PREFIX}{@code *} is
         *  refused ({@code IllegalArgumentException}) - it deletes only its own staging folder. */
        default void deleteStage(Path stage) throws IOException {
            throw new UnsupportedOperationException("deleteStage");
        }

        /** One operator line appended to the serve's command file (the line + LF, the file created when absent);
         *  the serve consumes the file on its next poll. A {@code default} that THROWS, as the refresh seams: an
         *  older implementor still compiles, and the press then opens the window unarmed.
         * */
        default void appendCommandLine(Path commandFile, String line) throws IOException {
            throw new UnsupportedOperationException("appendCommandLine");
        }

        /** {@link #probe}'s shape as a value, for {@link #withProbe}. */
        interface ProbeCall {
            VpnLeaseProbe.Probe call() throws IOException;
        }

        /**
         * These seams with {@link #probe} replaced by {@code call} - the ONE shape both shells need:
         * Swing pre-pulls the probe through its single-flight puller
         * and hands the answer (or its refusal) in; the web console joins its shared flight; the rows fake it.
         * Every other seam delegates to {@code this}. Thread: any.
         */
        default Effects withProbe(ProbeCall call) {
            Effects base = this;
            return new Effects() {
                @Override public boolean isRegularFile(Path file) { return base.isRegularFile(file); }
                @Override public byte[] readBounded(Path file, int cap) throws IOException { return base.readBounded(file, cap); }
                @Override public void writeAtomic(Path file, byte[] bytes) throws IOException { base.writeAtomic(file, bytes); }
                @Override public void createDirectories(Path dir) throws IOException { base.createDirectories(dir); }
                @Override public String pingBody(int port, String token) throws IOException { return base.pingBody(port, token); }
                @Override public VpnLeaseProbe.Probe probe() throws IOException { return call.call(); }
                @Override public int spawn(List<String> argv, Path cwd, Map<String, String> env) throws IOException { return base.spawn(argv, cwd, env); }
                @Override public Spawned spawnWindow(List<String> argv, Path cwd, Map<String, String> env) throws IOException { return base.spawnWindow(argv, cwd, env); }
                @Override public Map<String, String> parentEnv() { return base.parentEnv(); }
                @Override public String systemRoot() { return base.systemRoot(); }
                /** The kit refresh's seams, delegated (a missed one would run the throwing default in production).
                 * */
                @Override public Path realPath(Path p) throws IOException { return base.realPath(p); }
                @Override public List<KitZip.Entry> readKitZip(Path zip) throws IOException { return base.readKitZip(zip); }
                @Override public Path createStage(Path root) throws IOException { return base.createStage(root); }
                @Override public void writeNew(Path file, byte[] bytes) throws IOException { base.writeNew(file, bytes); }
                @Override public void moveDir(Path from, Path to) throws IOException { base.moveDir(from, to); }
                @Override public void deleteStage(Path stage) throws IOException { base.deleteStage(stage); }
                @Override public void appendCommandLine(Path f, String l) throws IOException { base.appendCommandLine(f, l); }
            };
        }

        /**
         * These seams with {@link #spawn} adding the join window's two diagnostics under {@code diagDir} - the
         * client GC log ({@code -Xlog} into the folder) and the F9 mark-frame ring ({@code cc.markframes.dir} = its
         * {@value #DIAG_FRAMES_DIR} sub-folder) - through the ONE key the kit's bat already honours
         * ({@link #clientDiagEnv}). Null = {@code this}: a plain Join adds nothing. The folders are made inside
         * {@code spawn}, never at decoration: a refused press leaves no empty folder, and a missing {@code -Xlog}
         * folder stops the game JVM from starting (measured). Only a window THIS press opens gets them - a live
         * window (REOPENED) keeps its own env. Thread: any.
         */
        default Effects withClientDiag(Path diagDirOrNull) {
            if (diagDirOrNull == null) {
                return this;
            }
            Effects base = this;
            return new Effects() {
                @Override public boolean isRegularFile(Path file) { return base.isRegularFile(file); }
                @Override public byte[] readBounded(Path file, int cap) throws IOException { return base.readBounded(file, cap); }
                @Override public void writeAtomic(Path file, byte[] bytes) throws IOException { base.writeAtomic(file, bytes); }
                @Override public void createDirectories(Path dir) throws IOException { base.createDirectories(dir); }
                @Override public String pingBody(int port, String token) throws IOException { return base.pingBody(port, token); }
                @Override public VpnLeaseProbe.Probe probe() throws IOException { return base.probe(); }
                @Override public int spawn(List<String> argv, Path cwd, Map<String, String> env) throws IOException {
                    return base.spawn(argv, cwd, clientDiagEnv(base, env, diagDirOrNull));
                }
                @Override public Spawned spawnWindow(List<String> argv, Path cwd, Map<String, String> env) throws IOException {
                    return base.spawnWindow(argv, cwd, clientDiagEnv(base, env, diagDirOrNull));
                }
                @Override public Map<String, String> parentEnv() { return base.parentEnv(); }
                @Override public String systemRoot() { return base.systemRoot(); }
                /** The kit refresh's seams, delegated (a missed one would run the throwing default in production).
                 * */
                @Override public Path realPath(Path p) throws IOException { return base.realPath(p); }
                @Override public List<KitZip.Entry> readKitZip(Path zip) throws IOException { return base.readKitZip(zip); }
                @Override public Path createStage(Path root) throws IOException { return base.createStage(root); }
                @Override public void writeNew(Path file, byte[] bytes) throws IOException { base.writeNew(file, bytes); }
                @Override public void moveDir(Path from, Path to) throws IOException { base.moveDir(from, to); }
                @Override public void deleteStage(Path stage) throws IOException { base.deleteStage(stage); }
                @Override public void appendCommandLine(Path f, String l) throws IOException { base.appendCommandLine(f, l); }
            };
        }
    }

    /** The real seams. */
    static final Effects LIVE = new Live();

    /** The real seams: files, one loopback GET, the ProtonVPN probe, one ProcessBuilder. In the console JVM
     *  only (never the serve JVM). */
    static final class Live implements Effects {

        @Override
        public boolean isRegularFile(Path file) {
            return Files.isRegularFile(file);
        }

        @Override
        public byte[] readBounded(Path file, int cap) throws IOException {
            if (Files.size(file) > cap) {
                throw new IOException("over cap");
            }
            try (InputStream in = Files.newInputStream(file)) {
                byte[] bytes = in.readNBytes(cap + 1);
                if (bytes.length > cap) {
                    throw new IOException("over cap"); // grew between the size check and the read
                }
                return bytes;
            }
        }

        @Override
        public void createDirectories(Path dir) throws IOException {
            Files.createDirectories(dir);
        }

        @Override
        public void writeAtomic(Path file, byte[] bytes) throws IOException {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".ops-tmp");
            try {
                // The write sits INSIDE the catch's reach -
                // a partial write used to leave the password-bearing temp behind.
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException notAtomic) {
                    System.out.println("cc-ops join write=non_atomic");
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException failed) {
                Files.deleteIfExists(tmp); // the temp holds the password: never left behind (a failed write or move)
                throw failed;
            }
        }

        @Override
        public String pingBody(int port, String token) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/" + token + "/api/ping")
                        .toURL().openConnection(Proxy.NO_PROXY);
                c.setConnectTimeout(PING_TIMEOUT_MS);
                c.setReadTimeout(PING_TIMEOUT_MS);
                c.setUseCaches(false);
                c.setInstanceFollowRedirects(false);
                if (c.getResponseCode() != 200) {
                    return null;
                }
                try (InputStream in = c.getInputStream()) {
                    return new String(in.readNBytes(PING_BODY_MAX_BYTES), StandardCharsets.UTF_8);
                }
            } catch (IOException | RuntimeException down) {
                System.out.println("cc-ops join ping=down (" + down.getClass().getSimpleName() + ")");
                return null;
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }

        @Override
        public VpnLeaseProbe.Probe probe() throws IOException {
            return VpnLeaseProbe.pull(VpnLeaseProbe.Env.live());
        }

        @Override
        public int spawn(List<String> argv, Path cwd, Map<String, String> env) throws IOException {
            return spawnWindow(argv, cwd, env).code();
        }

        /** The real start: the handle and its start instant are read right after the start, while the process lives
         *  (the outer {@code cmd /c start /wait} lives as long as its window). */
        @Override
        public Spawned spawnWindow(List<String> argv, Path cwd, Map<String, String> env) throws IOException {
            ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd.toFile());
            Map<String, String> childEnv = pb.environment();
            childEnv.clear();
            childEnv.putAll(env);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            ProcessHandle handle = p.toHandle();
            Instant start = handle.info().startInstant().orElse(null);
            try {
                p.getOutputStream().close(); // stdin closed: the window is started, never fed
            } catch (IOException closeFailed) {
                System.out.println("cc-ops join stdin_close=failed (" + closeFailed.getClass().getSimpleName() + ")");
            }
            int code;
            try {
                code = p.waitFor(SPAWN_WAIT_MS, TimeUnit.MILLISECONDS) ? p.exitValue() : STILL_RUNNING;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                System.out.println("cc-ops join spawn_wait=interrupted");
                code = STILL_RUNNING;
            }
            return new Spawned(code, handle, start);
        }

        @Override
        public Map<String, String> parentEnv() {
            return System.getenv();
        }

        @Override
        public String systemRoot() {
            String root = System.getenv("SystemRoot");
            return root == null || root.isBlank() ? "C:\\Windows" : root;
        }

        /**
         * The kit refresh's file operations - ALL of them live here, none in the outer class: the real path of the
         * game's {@code mods}, the kit zip inflated in memory, ONE staging folder ({@link #STAGE_RANDOM}'s 8 bytes as
         * 16 hex; {@code createDirectory} refuses a taken name), {@code CREATE_NEW} writes into it, the exists test
         * then {@code ATOMIC_MOVE} renames (never {@code REPLACE_EXISTING}: an atomic move onto an existing name is
         * implementation-specific, so "name taken" is tested first and never reads as "held"), and the staging
         * folder's own deepest-first delete. Same press thread as every other seam.
         *
         */
        private static final SecureRandom STAGE_RANDOM = new SecureRandom();

        @Override
        public Path realPath(Path p) throws IOException {
            return p.toRealPath();
        }

        @Override
        public List<KitZip.Entry> readKitZip(Path zip) throws IOException {
            return KitZip.read(zip, KitZip.KIT_CAPS);
        }

        @Override
        public Path createStage(Path root) throws IOException {
            byte[] rand = new byte[8];
            STAGE_RANDOM.nextBytes(rand);
            return Files.createDirectory(root.resolve(STAGE_PREFIX + HexFormat.of().formatHex(rand)));
        }

        @Override
        public void writeNew(Path file, byte[] bytes) throws IOException {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        @Override
        public void moveDir(Path from, Path to) throws IOException {
            Path parent = to.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(String.valueOf(to.getFileName()));
            }
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        }

        @Override
        public void deleteStage(Path stage) throws IOException {
            Path name = stage.getFileName();
            if (name == null || !name.toString().startsWith(STAGE_PREFIX)) {
                throw new IllegalArgumentException("not a kit staging folder");
            }
            List<Path> all;
            try (Stream<Path> walk = Files.walk(stage)) {
                all = new ArrayList<>(walk.toList());
            }
            all.sort(Comparator.reverseOrder());
            for (Path q : all) {
                Files.deleteIfExists(q);
            }
        }

        /** The append through the console's own verb writer ({@link ServerOpsCore#appendVerb}, the web verbs'
         *  path) - this class holds no second writer of the serve's command file. Same press thread.
         * */
        @Override
        public void appendCommandLine(Path commandFile, String line) throws IOException {
            ServerOpsCore.appendVerb(commandFile, line);
        }
    }

    // ---- pure functions (thread: any) ----

    /**
     * The game install the SAVED {@code game.root} row names: the row (trimmed; blank / absent = null) through
     * {@link ServeLauncher#resolveRow} (relative = under {@code packageRoot}; unparsable = null) and
     * {@link ServeLauncher#resolveGame} (the folder or its {@code starsector-core} form must hold the core jar,
     * else null). The row may name the install OR its {@code starsector-core} folder; the install is returned
     * either way. Thread: any.
     */
    static Path installOrNull(Path packageRoot, Map<String, String> rows) {
        String row = rows == null ? null : rows.get(ServeLauncher.ROW_GAME_ROOT);
        row = row == null ? "" : row.trim();
        if (row.isEmpty() || packageRoot == null) {
            return null;
        }
        Path path = ServeLauncher.resolveRow(packageRoot, row);
        if (path == null) {
            return null;
        }
        Path core = ServeLauncher.resolveGame(path);
        if (core == null) {
            return null;
        }
        return core.equals(path) ? path.getParent() : path;
    }

    /** R1 for no install; R2 (naming the install and {@code kitHint}, each cut to {@link #TEXT_PATH_MAX}) when
     *  {@value #GAME_EXE} or the kit's {@value #KIT_BAT} is missing; else null. Thread: any. */
    static String installRefusalOrNull(Path installOrNull, Effects fx, String kitHint) {
        if (installOrNull == null) {
            return R1;
        }
        if (!fx.isRegularFile(installOrNull.resolve(GAME_EXE))
                || !fx.isRegularFile(cwd(installOrNull).resolve(KIT_BAT))) {
            return String.format(Locale.ROOT, R2, boundedText(installOrNull.toString()), boundedText(kitHint));
        }
        return null;
    }

    /**
     * Is the live serve bound to the ProtonVPN TUN address of the saved fill? The saved rows ARE the mode (no
     * marker object exists; {@link ServerOpsLaunch#writeFillRows}): {@code advertise.from} (trimmed) is
     * {@code protonvpn} AND {@code advertise.bind} (trimmed) is non-blank and equals {@code liveHost}. Never the
     * {@code 10.2.0.x} shape. Thread: any.
     */
    static boolean tunMode(Map<String, String> rows, String liveHost) {
        if (rows == null || liveHost == null) {
            return false;
        }
        String from = trimmed(rows.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM));
        String bind = trimmed(rows.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_BIND));
        return ServerOpsLaunch.ADVERTISE_FROM_PROTONVPN.equals(from) && !bind.isEmpty()
                && bind.equals(liveHost.trim());
    }

    /**
     * The {@code host:port} the join window dials, or THROWS {@link IllegalArgumentException} carrying the
     * refusal sentence. Wildcard {@code 0.0.0.0} = {@code 127.0.0.1}; an IPv6 host = R5; TUN mode
     * ({@link #tunMode}) = the hairpin through the public endpoint of the in-mode RE-PROBE -
     * never the saved {@code advertise} row alone, a rotated exit would dial a dead address - so a null probe =
     * R10; any other host = itself. Then N7: the host must match {@link #DIAL_HOST} and the port be 1..65535,
     * else {@link #R5_SHAPE}. {@code live} non-null. Thread: any. Every branch but TUN mode is {@link #dialLocal}.
     */
    static String dial(OpsSnapshot live, Map<String, String> rows, VpnLeaseProbe.Probe probeOrNull) {
        String host = trimmed(live.host());
        if ("0.0.0.0".equals(host) || host.indexOf(':') >= 0 || !tunMode(rows, host)) {
            return dialLocal(live);
        }
        if (probeOrNull == null) {
            throw new IllegalArgumentException(R10);
        }
        return shapeChecked(tunDial(probeOrNull, rows.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE), live));
    }

    /**
     * The live serve's OWN endpoint, never the VPN hairpin, or THROWS {@link IllegalArgumentException} carrying the
     * refusal sentence: wildcard {@code 0.0.0.0} = {@code 127.0.0.1}, an IPv6 host = {@link #R5}, any other host =
     * itself (a serve bound to the VPN's TUN address dials that address straight, no probe), then N7 as
     * {@link #dial}. WHY a second home: the debug watch's FIRST window of two dials it whatever the saved
     * {@code advertise.from} says - one window local, one through the VPN; a hairpin on both would leave the local
     * path unwatched. {@code live} non-null. Thread: any.
     */
    static String dialLocal(OpsSnapshot live) {
        String host = trimmed(live.host());
        if ("0.0.0.0".equals(host)) {
            return shapeChecked("127.0.0.1:" + live.port());
        }
        if (host.indexOf(':') >= 0) {
            throw new IllegalArgumentException(R5);
        }
        return shapeChecked(host + ":" + live.port());
    }

    /** N7 on a dialled endpoint: the host must match {@link #DIAL_HOST} and the port be 1..65535, else
     *  {@link #R5_SHAPE}; returns {@code host:port} normalised. Shared by {@link #dial}, {@link #dialLocal} and {@link #dialPublic}. */
    private static String shapeChecked(String endpoint) {
        int colon = endpoint.lastIndexOf(':');
        String dialHost = colon < 0 ? endpoint : endpoint.substring(0, colon);
        int port = colon < 0 ? -1 : portOrMinus1(endpoint.substring(colon + 1));
        if (!DIAL_HOST.matcher(dialHost).matches() || port < 1 || port > 65535) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, R5_SHAPE, shownHost(dialHost)));
        }
        return dialHost + ":" + port;
    }

    /**
     * The debug WATCH's SECOND window's dial: one window local, one through the VPN - the dev arm's shape since
     * 2026-07-06, now the package console's too. The hairpin through the VPN's public forwarded
     * endpoint WHATEVER the saved {@code advertise.from} says - a folder whose rows read {@code typed} has
     * {@link #tunMode} false, so the mode cannot be the gate. The gate is the live serve itself: a probe that
     * answered AND whose TUN address equals the live bind host (the serve really sits on the VPN) =
     * {@link #tunDial} over it (its dead-port check against the running serve included, that refusal propagates),
     * then N7 as {@link #dial}. A null probe, or a serve bound elsewhere (LAN, loopback, wildcard) = null = the
     * caller falls back to {@link #dial}: there is no VPN path to dial, and a refusal would block every LAN debug
     * watch. Thread: any.
     */
    static String dialPublic(OpsSnapshot live, Map<String, String> rows, VpnLeaseProbe.Probe probeOrNull) {
        if (probeOrNull == null || probeOrNull.tunIp() == null) {
            return null;
        }
        String host = trimmed(live.host());
        if (host.isEmpty() || !host.equals(probeOrNull.tunIp().trim())) {
            return null;
        }
        String advertise = rows == null ? null : rows.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE);
        return shapeChecked(tunDial(probeOrNull, advertise, live));
    }

    /** The TUN dial: the probe's port when found, else the saved advertise's; 1..65535 = the lease checked
     *  against the RUNNING serve in PACKAGE words, else the lease-free package rule. Either throws its own
     *  refusal sentence, which propagates. */
    private static String tunDial(VpnLeaseProbe.Probe p, String advertiseText, OpsSnapshot live) {
        int port = p.portFound() ? p.port()
                : ServerOpsLaunch.portOf(advertiseText == null ? "" : advertiseText.trim());
        if (port > 0 && port <= 65535) {
            return ServerOpsLaunch.brickEndpoint(new ServerOpsCore.VpnLease(p.exitIp(), port, p.tunIp()),
                    null, advertiseText, live, ServerOpsLaunch.BindWords.PACKAGE);
        }
        return ServerOpsLaunch.packageBrickEndpoint(advertiseText, live);
    }

    /**
     * The join window's settings file ({@code %LOCALAPPDATA%\cc-starsector\join-settings.properties}): an
     * absolute {@code localAppData}, else a non-blank {@code appData}, else {@code userHome}. The THIRD copy of
     * this law - the others are {@code JoinSettings.defaultPath} / {@code settingsBase} (client) and
     * the join-watch arm script; a twin row over {@code JoinSettings.DIR_NAME}
     * / {@code FILE_NAME} ({@code join-client-twin}) pins the names. Thread: any.
     */
    static Path settingsFile(String localAppData, String appData, String userHome) {
        Path local = absoluteEnvDir(localAppData);
        Path base = local != null ? local
                : appData == null || appData.isBlank() ? Paths.get(userHome) : Paths.get(appData);
        return base.resolve(SETTINGS_DIR_NAME).resolve(SETTINGS_FILE_NAME);
    }

    /** The older roaming {@code %APPDATA%} settings file ({@code JoinSettings.pathForRun}'s legacy), null when
     *  {@code appData} is unset or not absolute. Thread: any. */
    static Path legacySettingsFile(String appData) {
        Path base = absoluteEnvDir(appData);
        return base == null ? null : base.resolve(SETTINGS_DIR_NAME).resolve(SETTINGS_FILE_NAME);
    }

    /**
     * The settings file the join window reads at its NEXT run ({@code JoinSettings.pathForRun}'s law): the
     * current file absent and the legacy one a regular file = the LEGACY path, else the current one. The core
     * moves nothing: the join window does its own {@code migrateLegacy} at its next run (it moves the legacy file
     * we wrote, holding its seat-key cid, to the current path). Writing the current path instead would make that
     * window delete the legacy file as a leftover, cid and all. Thread: any.
     */
    static Path settingsForRun(Effects fx, String localAppData, String appData, String userHome) {
        Path current = settingsFile(localAppData, appData, userHome);
        Path legacy = legacySettingsFile(appData);
        if (legacy == null || legacy.equals(current) || !fx.isRegularFile(legacy) || fx.isRegularFile(current)) {
            return current;
        }
        return legacy;
    }

    /** {@code host=} / {@code port=} always; {@code password=} / {@code passwordFor=} too when armed (case-blind,
     *  as the golden's PowerShell {@code -match}). */
    private static final Pattern DROP_HOST_PORT = Pattern.compile("^\\s*(host|port)\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_ARMED =
            Pattern.compile("^\\s*(host|port|password|passwordFor)\\s*=", Pattern.CASE_INSENSITIVE);

    /**
     * The join-settings merge-write (the arm script's merge in Java = the
     * golden): every existing row is kept BYTE-VERBATIM (ISO-8859-1 in and out, never parsed, never echoed) except the
     * {@code host} / {@code port} rows (+ the {@code password} / {@code passwordFor} rows when a password is
     * given); the FREEZE row - no password given, the file has a non-empty {@code password=} row and NO
     * {@code passwordFor=} row - binds that old password to the file's LAST {@code host} / {@code port} values
     * (the window would otherwise bind it to the NEW rows); then the new rows LAST: {@code host=}, {@code port=},
     * and when armed {@code password=} + {@code passwordFor=<host with ':' as '\:'>\:<port>}. A row ending in an
     * ODD run of backslashes gets one more (a {@code Properties} continuation would swallow the next row). Rows
     * split on CRLF, LF or a lone CR (as .NET {@code ReadAllLines} and {@code Properties.load}); output CRLF with a
     * trailing CRLF. {@code password} passes {@link #passwordOrBlank} again (unlawful = blank). Every {@code cid}
     * row is kept byte-verbatim here; {@link #merge(byte[], String, int, String, String)} is the form that writes
     * one. Thread: any.
     */
    static byte[] merge(byte[] existingOrNull, String dialHost, int dialPort, String password) {
        return merge(existingOrNull, dialHost, dialPort, password, null);
    }

    /** A {@code cid} row, case-blind (the {@code DROP_*} idiom): dropped when the merge writes its own. */
    private static final Pattern DROP_CID = Pattern.compile("^\\s*cid\\s*=", Pattern.CASE_INSENSITIVE);

    /**
     * {@link #merge(byte[], String, int, String)} plus, when {@code cidToWriteOrNull} is set (a {@link #mintCid}
     * value), the window's seat-key row: every existing {@code cid} row (case-blind) dropped and
     * {@code cid=<value>} added LAST, after {@code passwordFor=} - a {@code Properties} read keeps the LAST row of a
     * key, so the window reads exactly the key the press arms. Null = byte-identical to the 4-arg form.
     *
     */
    static byte[] merge(byte[] existingOrNull, String dialHost, int dialPort, String password,
            String cidToWriteOrNull) {
        String pw = passwordOrBlank(password);
        boolean armed = !pw.isEmpty();
        List<String> existing = settingsRows(existingOrNull);
        Pattern drop = armed ? DROP_ARMED : DROP_HOST_PORT;
        List<String> out = new ArrayList<>();
        for (String row : existing) {
            if (!drop.matcher(row).find() && (cidToWriteOrNull == null || !DROP_CID.matcher(row).find())) {
                out.add(continuationGuard(row));
            }
        }
        if (!armed) {
            String old = lastValue(existing, "password");
            if (old != null && !old.isEmpty() && lastValue(existing, "passwordFor") == null) {
                out.add(continuationGuard("passwordFor=" + orEmpty(lastValue(existing, "host")) + "\\:"
                        + orEmpty(lastValue(existing, "port"))));
            }
        }
        out.add("host=" + dialHost);
        out.add("port=" + dialPort);
        if (armed) {
            out.add("password=" + pw);
            out.add("passwordFor=" + dialHost.replace(":", "\\:") + "\\:" + dialPort);
        }
        if (cidToWriteOrNull != null) {
            out.add("cid=" + cidToWriteOrNull);
        }
        return crlfBytes(out);
    }

    /** The settings writer's law: each row + CRLF, ISO-8859-1 (byte-verbatim for the rows read the same way). */
    private static byte[] crlfBytes(List<String> rows) {
        StringBuilder sb = new StringBuilder();
        for (String row : rows) {
            sb.append(row).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * May this saved {@code cid} be KEPT as the window's seat key? Non-null, 1..{@value #CID_MAX_CHARS} chars, each
     * {@code 0x21..0x7E}, and no backslash. WHY stricter than the join window's own keep rule: the door's sendable
     * seat id is at most {@value #CID_MAX_CHARS} printable ASCII chars, and a backslash reads one way through the
     * window's {@code Properties} load and another through this class's row read - the press must arm exactly the
     * value the window sends. An id the window would keep but this refuses is re-minted once (a new seat key).
     * Pure.
     */
    static boolean cidLawful(String s) {
        if (s == null || s.isEmpty() || s.length() > CID_MAX_CHARS) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x21 || ch > 0x7E || ch == '\\') {
                return false;
            }
        }
        return true;
    }

    /** The seat-key mint: 16 random bytes as 32 lowercase hex - the join window's own mint shape, always
     *  {@link #cidLawful}. */
    private static final SecureRandom RNG = new SecureRandom();

    static String mintCid() {
        return String.format(Locale.ROOT, "%016x%016x", RNG.nextLong(), RNG.nextLong());
    }

    /**
     * The {@code cid} the join window will read from {@code settingsBytes} - its own load ({@code Properties} over
     * ISO-8859-1: case-SENSITIVE keys, the value's trailing blanks kept, a {@code :} or blank separator honoured);
     * a malformed escape stops that load where it stands, so the rows read before it are what the window gets
     * (one stdout token row, never a value). Null = no {@code cid} row reaches the window. Pure.
     *
     */
    static String windowCidOrNull(byte[] settingsBytes) {
        Properties props = new Properties();
        try {
            props.load(new ByteArrayInputStream(settingsBytes));
        } catch (IOException | IllegalArgumentException malformed) {
            System.out.println("cc-ops join cid_read=malformed (" + malformed.getClass().getSimpleName() + ")");
        }
        return props.getProperty("cid");
    }

    /** A secret row, case-blind ({@code DROP_ARMED} minus host / port): what the settings scrub removes. */
    private static final Pattern DROP_SECRET =
            Pattern.compile("^\\s*(password|passwordFor)\\s*=", Pattern.CASE_INSENSITIVE);

    /**
     * The join settings' secret scrub, for the END of a debug flight (the serve or the window gone): only the
     * console's watch daemon sees that end, so it calls this - a press never does. In each settings file that
     * exists (the current one and the legacy roaming one): every {@code password} / {@code passwordFor} row
     * (case-blind) dropped, with any row a backslash continuation folds into it, and every other row kept
     * byte-verbatim through {@link #continuationGuard}, CRLF out, temp + move; a file with no such row is not
     * written. WHY every password row, unconditionally: this class cannot tell the kept admin secret from the
     * match password without carrying the admin secret into the press, the join window saves whatever password
     * the host typed, and every console press writes the match password row again - so the scrub costs a console
     * host nothing. The answer, also ONE stdout row {@code cc-ops join settings_secret=<answer>} (never a value):
     * {@code scrubbed} (a row dropped), {@code clean} (a file, no secret row), {@code absent} (no file) or
     * {@code failed (<SimpleName>)} (the first failure; that file untouched, the other file still scrubbed). A
     * scrub while a window is mid-launch could overwrite that window's own save (both sides write temp + move),
     * hence the flight's end only. Thread: any.
     */
    static String scrubSettingsSecret(Effects fx, String localAppData, String appData, String userHome) {
        List<Path> files = new ArrayList<>();
        String failed = null;
        try {
            files.add(settingsFile(localAppData, appData, userHome));
        } catch (RuntimeException unresolved) {
            failed = unresolved.getClass().getSimpleName();
        }
        Path legacy = legacySettingsFile(appData);
        if (legacy != null && !files.contains(legacy)) {
            files.add(legacy);
        }
        boolean present = false;
        boolean dropped = false;
        for (Path file : files) {
            try {
                if (!fx.isRegularFile(file)) {
                    continue;
                }
                present = true;
                List<String> kept = new ArrayList<>();
                boolean secret = false;
                boolean continued = false;
                for (String row : settingsRows(fx.readBounded(file, SETTINGS_MAX_BYTES))) {
                    if (continued || DROP_SECRET.matcher(row).find()) {
                        secret = true;
                        continued = endsInOddBackslashes(row);
                    } else {
                        kept.add(continuationGuard(row));
                    }
                }
                if (secret) {
                    fx.writeAtomic(file, crlfBytes(kept));
                    dropped = true;
                }
            } catch (IOException | RuntimeException scrubFailed) {
                failed = failed != null ? failed : scrubFailed.getClass().getSimpleName();
            }
        }
        String answer = failed != null ? "failed (" + failed + ")"
                : dropped ? "scrubbed" : present ? "clean" : "absent";
        System.out.println("cc-ops join settings_secret=" + answer);
        return answer;
    }

    /**
     * The saved match password, or {@code ""}: null / blank = {@code ""}; a value the server's own sidecar law
     * refuses ({@link MatchPasswords#parseSidecarPassword}: 8..64 of {@code [A-Za-z0-9-]}, no leading dash, not
     * blocklisted - such a sidecar never arms a serve) = {@code ""} + ONE stdout row naming no value. Thread: any.
     */
    static String passwordOrBlank(String sidecarValue) {
        if (sidecarValue == null || sidecarValue.isBlank()) {
            return "";
        }
        try {
            return MatchPasswords.parseSidecarPassword(List.of("password=" + sidecarValue), "join");
        } catch (IllegalArgumentException unlawful) {
            System.out.println("cc-ops join password=dropped_unlawful");
            return "";
        }
    }

    /** The join window's endpoint file for {@code install} ({@code JoinHost.endpointFile(localAppData,
     *  installRoot)}'s law): {@code <localAppData>\cc-starsector\join\cc_join_endpoint-<tag>.data}; an unset,
     *  blank or relative {@code localAppData} = null (the client writes no file then). Thread: any. */
    static Path endpointFile(String localAppData, Path install) {
        Path base = absoluteEnvDir(localAppData);
        return base == null || install == null ? null : base.resolve(SETTINGS_DIR_NAME).resolve(ENDPOINT_DIR_NAME)
                .resolve(ENDPOINT_FILE_PREFIX + installTag(install) + ".data");
    }

    /** {@code JoinHost.installTag}: the first 4 bytes, lower-case hex, of SHA-256 over the UTF-8 bytes of the
     *  install's absolute, normalized, {@code Locale.ROOT} lower-cased path. Thread: any. */
    static String installTag(Path install) {
        String key = install.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        byte[] d;
        try {
            d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", absent);
        }
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 4; i++) {
            sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16)).append(Character.forDigit(d[i] & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * The live join window's URL, or null ({@code JoinBrowserUi.liveWindowUrl}'s law over the seams): the file a
     * regular file of at most {@value #ENDPOINT_FILE_MAX_BYTES} B; {@code port|token} or {@code port|token|nonce}
     * ({@code JoinHost.parseEndpoint}: port 1..65535, token 64 lower-case hex, nonce 8 lower-case hex); the ping
     * answers 200 with {@code "app":"join"} in its body. The URL = {@code http://127.0.0.1:<port>/<token>/}, as
     * the client composes it. Thread: any.
     */
    static String liveUrlOrNull(Effects fx, Path endpointFile) {
        if (endpointFile == null || !fx.isRegularFile(endpointFile)) {
            return null;
        }
        String text;
        try {
            text = new String(fx.readBounded(endpointFile, ENDPOINT_FILE_MAX_BYTES), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            System.out.println("cc-ops join endpoint=unreadable (" + unreadable.getClass().getSimpleName() + ")");
            return null;
        }
        String[] fields = text.trim().split("\\|", -1);
        if (fields.length < 2 || fields.length > 3) {
            return null;
        }
        int port = portOrMinus1(fields[0].trim());
        String token = fields[1].trim();
        if (port < 1 || port > 65535 || !token.matches("[0-9a-f]{" + ENDPOINT_TOKEN_HEX_CHARS + "}")) {
            return null;
        }
        if (fields.length == 3 && !fields[2].trim().matches("[0-9a-f]{8}")) {
            return null;
        }
        String body;
        try {
            body = fx.pingBody(port, token);
        } catch (IOException | RuntimeException down) {
            System.out.println("cc-ops join ping=down (" + down.getClass().getSimpleName() + ")");
            return null;
        }
        if (body == null || !body.contains("\"app\":\"join\"")) {
            return null;
        }
        return "http://127.0.0.1:" + port + "/" + token + "/";
    }

    /**
     * The spawn's FIXED argv: {@code <systemRoot>\System32\cmd.exe /d /c start /wait
     * "AlphaCoreMP join" cmd.exe /d /c .\join-cc.bat} - the title element carries its quotes as ONE element (so
     * {@code start} reads it as the title), the inner {@code cmd /d /c} leaves no prompt behind, and no host,
     * port or password text ever reaches cmd (the window reads them from its settings file). The bat is named by
     * its EXPLICIT relative path, never bare: a bare name is a PATH lookup, and a host whose
     * environment carries the Windows hardening row {@code NoDefaultCurrentDirectoryInExePath} excludes the current
     * folder from that lookup - the started window then dies in one frame on "'join-cc.bat' is not recognized" while
     * this console still answers OPENED (measured on a host that inherited the row from the shell that started the
     * console). A relative path is resolved against the spawn's cwd ({@link #cwd}), never looked up. Thread: any.
     *
     * <p>{@code /wait} keeps the outer cmd (started with no window of its own) alive exactly as long as the window, so
     * the handle the spawn keeps IS the window's: its descendants name the window's cmd and everything under it while
     * they live - what {@link JoinWindowSweep} closes when a debug watch's serve ends. Cost: one idle cmd per window.
     * A window that dies in one frame still exits 0 through {@code start /wait} (measured), so that case still answers
     * OPENED.
     *
     * <p>{@code minimized} = {@link #JOIN_WINDOW_MINIMIZED}: {@code start /min /wait ...}. WHY:
     * the proof chain's package-watch leg presses this join on a fixture whose launcher is a stub, on the owner's box
     * while the owner works; a normal {@code start} window is handed to Windows Terminal and takes the foreground
     * (measured: one terminal session + one foreground change per press), a minimized one is never handed over and
     * takes nothing (measured: none). TEST-ONLY: the real window stays normal - cmd passes its show state down to the
     * game the launcher starts, so a minimized join would open the game minimized.
     */
    static List<String> argv(String systemRoot) {
        return argv(systemRoot, JOIN_WINDOW_MINIMIZED);
    }

    static List<String> argv(String systemRoot, boolean minimized) {
        String root = systemRoot;
        while (root.endsWith("\\")) {
            root = root.substring(0, root.length() - 1);
        }
        List<String> argv = new ArrayList<>(List.of(root + "\\System32\\cmd.exe", "/d", "/c", "start"));
        if (minimized) {
            argv.add("/min");
        }
        argv.addAll(List.of("/wait", "\"" + WINDOW_TITLE + "\"", "cmd.exe", "/d", "/c", ".\\" + KIT_BAT));
        return List.copyOf(argv);
    }

    /**
     * The spawn's cwd = the kit's own folder {@code <install>/mods/CC_Client}, also where
     * {@link #installRefusalOrNull} looks for {@value #KIT_BAT}.
     * built by a path JOIN of two names, never one {@code mods\CC_Client} string - a non-Windows {@code Path}
     * reads that as ONE file name, so a Linux build would refuse a good install (reasoned). The
     * R2 / R6 / R7 sentences keep their Windows backslashes: they are shown only where the Windows-only kit can
     * run (the Swing package hides the button off Windows, the web answers R9 first). Thread: any.
     */
    static Path cwd(Path install) {
        return install.resolve("mods").resolve("CC_Client");
    }

    /** The child env = {@code parent} minus every key starting {@link #CC_ENV_PREFIX} (ASCII case-blind);
     *  {@code JAVA_TOOL_OPTIONS} is KEPT for parity with a by-hand run. A debug-watch press
     *  re-adds up to four {@code CC_*} keys AFTER this strip, each its own, never an inherited one: the diag flags
     *  ({@link #clientDiagEnv}), the window's sidelog ({@value #WATCH_SIDELOG_KEY}) with its cap
     *  ({@value #WATCH_SIDELOG_MAX_MIB_KEY}) and the second window's seat key ({@value #PREDICT_CID_KEY}), the last
     *  three in {@link #step7Spawn}. Thread: any. */
    static Map<String, String> childEnv(Map<String, String> parent) {
        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : parent.entrySet()) {
            String k = e.getKey();
            if (k == null || e.getValue() == null) {
                continue;
            }
            if (k.regionMatches(true, 0, CC_ENV_PREFIX, 0, CC_ENV_PREFIX.length())) {
                continue;
            }
            env.put(k, e.getValue());
        }
        return env;
    }

    /**
     * {@code env} (already {@link #childEnv}-stripped) plus {@value #CLIENT_JVM_FLAGS_KEY} for {@code diagDir}:
     * {@code "-Xlog:gc*,safepoint:file=<dir>\gclog-%p-%t.log:time,uptime" "-Dcc.markframes.dir=<dir>\frames"}
     * ({@code %p} / {@code %t} = the JVM's own pid / start stamp). Each flag is double-quoted so a folder with a
     * SPACE (a host's profile folder named with one) stays ONE token: the bat's {@code set} keeps the quotes and
     * HotSpot's JAVA_TOOL_OPTIONS parser strips them (measured on Temurin 17: the GC log landed inside a
     * spaced folder). The SAME {@code env} comes back - nothing added,
     * no folder made - when the path is relative (it would bind every JVM started from that window's user env to
     * the kit folder) or outside {@link #DIAG_DIR_CHARS}, or when {@code fx} cannot make the folders; each prints
     * one {@code cc-ops join diag=skipped_<token>} row, never the path. Pure over {@code fx} (the rows fake it).
     * Thread: any.
     */
    static Map<String, String> clientDiagEnv(Effects fx, Map<String, String> env, Path diagDir) {
        String dir = diagDir.toString();
        if (!diagDir.isAbsolute() || !DIAG_DIR_CHARS.matcher(dir).matches()) {
            System.out.println("cc-ops join diag=skipped_path");
            return env;
        }
        try {
            fx.createDirectories(diagDir.resolve(DIAG_FRAMES_DIR));
        } catch (IOException | RuntimeException mkdirFailed) {
            System.out.println("cc-ops join diag=skipped_mkdir (" + mkdirFailed.getClass().getSimpleName() + ")");
            return env;
        }
        Map<String, String> out = new LinkedHashMap<>(env);
        out.put(CLIENT_JVM_FLAGS_KEY, "\"-Xlog:gc*,safepoint:file=" + dir + "\\gclog-%p-%t.log:time,uptime\""
                + " \"-Dcc.markframes.dir=" + dir + "\\" + DIAG_FRAMES_DIR + "\"");
        return out;
    }

    // ---- the press ----

    /** One press's working state, passed step to step (package-visible for the rows that drive one step). */
    static final class Press {
        final Inputs in;
        final Effects fx;
        Path install;
        String password = "";
        String dialHost;
        int dialPort;
        /** Step (1c) refreshed the installed kit this press: {@link #NOTE_KIT_REFRESHED} filled, which {@link #press}
         *  puts in front of the answer's text; null = nothing changed. */
        String kitNoteOrNull;
        /**
         * The window's seat key and sidelog. {@code cidOverrideOrNull} = this window's ENV-delivered key: the second
         * pilot window's own fresh key ({@link #pressWatch}), or an ARMED window's per-press mint ({@link #settleCid})
         * - put in its env and never in the settings file (that file's {@code cid} row stays the un-armed first
         * window's, or untouched); {@code sidelogOrNull} = this window's client log file ({@value #WATCH_SIDELOG_KEY}),
         * null = none; {@code cidWanted} = this is the first of two windows, so its key is read or minted even
         * without an arm (the second window's key must differ from it); {@code cid} = this window's key once the
         * settings write ran (null = not wanted); {@code armed} = its {@code admin-cid} line reached the serve's
         * command file.
         */
        String cidOverrideOrNull;
        Path sidelogOrNull;
        /** The cap that rides with {@link #sidelogOrNull} ({@value #WATCH_SIDELOG_MAX_MIB_KEY}), MiB in
         *  {@link #WATCH_CLIENT_LOG_MIN_MIB}..{@link #WATCH_CLIENT_LOG_MAX_MIB}: the parsed launcher row, or the
         *  default when no row was read, so a sidelog never rides without a cap. */
        int sidelogMaxMib = WATCH_CLIENT_LOG_DEFAULT_MIB;
        boolean cidWanted;
        String cid;
        boolean armed;
        /** The window this press opens goes into {@link Result#windows} (the debug watch's presses only: the console
         *  closes those when the watch's serve ends). */
        boolean recordWindow;
        /** The debug watch's SECOND window: step (5) tries {@link #dialPublic} (the VPN's public endpoint) before
         *  {@link #dial} - one window local, one through the VPN. */
        boolean publicDial;
        /** The debug watch's FIRST window when a second one follows: step (5) dials {@link #dialLocal} (the live
         *  bind's own endpoint), never the VPN hairpin and never the probe. A lone window keeps {@link #dial}.
         * */
        boolean localDial;

        Press(Inputs in, Effects fx) {
            this.in = in;
            this.fx = fx;
        }
    }

    /** One step: null = continue, a {@link Result} = the press ends with it. */
    @FunctionalInterface
    interface StepBody {
        Result run(Press p);
    }

    /** A named step (the name pins the order in the rows). */
    record Step(String name, StepBody body) {
    }

    /** The press, IN ORDER; {@link #press} returns the first non-null {@link Result}. The kit check
     *  ({@link #step1cKitCurrent}) runs second, before anything is written: a stale kit refuses with nothing written,
     *  or is refreshed before the window that runs it is opened. No step
     *  writes a file under the kit folder. The seat-key arm
     *  ({@link #step6bArm}) sits between the settings write and the spawn: the {@code cid} row is on disk before the
     *  serve is told to elevate it, a failed write arms nothing, and the line reaches the serve's poll long before
     *  the game boots. */
    static final List<Step> STEPS = List.of(
            new Step("step1Install", OpsJoinSelf::step1Install),
            new Step("step1cKitCurrent", OpsJoinSelf::step1cKitCurrent),
            new Step("step2LiveWindow", OpsJoinSelf::step2LiveWindow),
            new Step("step3LiveGate", OpsJoinSelf::step3LiveGate),
            new Step("step4Password", OpsJoinSelf::step4Password),
            new Step("step5Dial", OpsJoinSelf::step5Dial),
            new Step("step6Write", OpsJoinSelf::step6Write),
            new Step("step6bArm", OpsJoinSelf::step6bArm),
            new Step("step7Spawn", OpsJoinSelf::step7Spawn));

    /**
     * Join from this PC: {@link #STEPS} in order, the first non-null {@link Result} wins, then ONE stdout row
     * {@code cc-ops join outcome=<opened|reopened|refused> reason=<token|->} (no dial, no path, no secret; the
     * shells may print their own row beside it). A kit the press refreshed puts {@link #NOTE_KIT_REFRESHED} in
     * front of the answer's text, whatever the answer (WHY: the game folder changed and the host must hear where
     * the old kit went). Thread: any; the shells call it off the EDT /
     * request thread and hold one press in flight (R8). The body is {@link #pressOne}, which the debug flight's
     * {@link #pressWatch} runs once per window.
     */
    static Result press(Inputs in, Effects fx) {
        return pressOne(new Press(in, fx));
    }

    /**
     * The debug flight's pilot windows from ONE call on the caller's thread - so the shells' one-press-in-flight
     * law (R8) covers both windows' reads, writes, arms and spawns, and their order has one home. No thread, no
     * wait: the second window is pressed the moment the first one opened.
     *
     * <p>Window 1 = {@link #press} over {@code in}, its diagnostics and sidelog under
     * {@code <flight>\}{@value #DIAG_FIRST}. A blank {@code game.root.second} launcher row = that window alone.
     * Set = the row is checked FIRST, before window 1 reads anything: not a game = {@link #R16}, the first window's
     * own game through its links ({@code toRealPath}: a junction or a case variant is the same folder) =
     * {@link #R17}, a path that cannot be resolved = {@link #R16} (distinct installs unproven = refused); each
     * {@code r1_second}, nothing read, written, appended or started. Then window 1 (its key read or minted into the
     * settings file even without an arm); a window 1 that did not OPEN is the answer. Else window 2 at once: the
     * same press over the second Game folder, its diagnostics and sidelog under
     * {@code <flight>\}{@value #DIAG_SECOND}, a fresh seat key in its env ({@value #PREDICT_CID_KEY}, never equal
     * to window 1's). With the press armed each window arms ITS OWN key before its own spawn - which is also
     * what lets the second window past the door's per-address seat cap when both arrive from one tunnel address.
     * ONE stdout row {@code cc-ops join second=<opened|reopened|refused:<token>|none>} (none = no second window
     * was pressed) beside each window's own outcome row. The answer = window 1's, followed by
     * {@link #SECOND_PREFIX} and window 2's text ({@link #NOTE_SECOND_LIVE} when its join window was already
     * open); {@link Outcome} never grows.
     *
     * <p>The answer's {@link Result#windows} = every window this call opened (one or two; a live window it only
     * re-opened is not one): the console closes them when the watch's serve ends.
     *
     * <p>Before even the row-2 check: the {@link #ROW_WATCH_CLIENT_LOG_MAX_MIB} row. A value
     * {@link #watchClientLogMaxMib} refuses = {@link #R18} ({@code client_log_row}), nothing read, written, appended
     * or started; else each window's sidelog carries that cap ({@value #WATCH_SIDELOG_MAX_MIB_KEY}).
     *
     *
     * <p>Window 2's dial = {@link #dialPublic} first - one window local, one through the VPN:
     * the VPN's public forwarded endpoint when the live serve sits on the probe's TUN address, else {@link #dial};
     * ONE stdout row {@code cc-ops join second_dial=<vpn|local>} (no address). Window 1 of two dials
     * {@link #dialLocal} - the live bind's own endpoint, never the hairpin a ProtonVPN-saved folder would take,
     * no probe - and says ONE stdout row {@code cc-ops join first_dial=local} (no address). A single window keeps
     * {@link #dial}.
     */
    static Result pressWatch(Inputs in, Effects fx, Path flightOrNull) {
        Map<String, String> rows = in.launcherRows();
        String capRow = trimmed(rows == null ? null : rows.get(ROW_WATCH_CLIENT_LOG_MAX_MIB));
        int capMib = watchClientLogMaxMib(capRow);
        if (capMib < 0) {
            Result refusedCap = refused("client_log_row", String.format(Locale.ROOT, R18,
                    ROW_WATCH_CLIENT_LOG_MAX_MIB, boundedText(capRow)));
            System.out.println("cc-ops join outcome=refused reason=" + refusedCap.reasonToken());
            return refusedCap;
        }
        String row2 = trimmed(rows == null ? null : rows.get(ServeLauncher.ROW_GAME_ROOT_SECOND));
        Path diag1 = flightOrNull == null ? null : flightOrNull.resolve(DIAG_FIRST);
        Press first = new Press(in, fx.withClientDiag(diag1));
        first.sidelogOrNull = sidelogOrNull(diag1);
        first.sidelogMaxMib = capMib;
        first.recordWindow = true;
        if (row2.isEmpty()) {
            Result only = pressOne(first);
            System.out.println("cc-ops join second=none");
            return only;
        }
        Map<String, String> rows2 = new HashMap<>(rows);
        rows2.put(ServeLauncher.ROW_GAME_ROOT, row2);
        String refusal = secondInstallRefusalOrNull(in.packageRoot(), rows, rows2, row2, fx);
        if (refusal != null) {
            Result refusedSecond = refused("r1_second", refusal);
            System.out.println("cc-ops join outcome=refused reason=" + refusedSecond.reasonToken());
            return refusedSecond;
        }
        first.cidWanted = true;
        first.localDial = true;
        Result r1 = pressOne(first);
        if (r1.outcome() != Outcome.OPENED) {
            System.out.println("cc-ops join second=none");
            return r1;
        }
        Path diag2 = flightOrNull == null ? null : flightOrNull.resolve(DIAG_SECOND);
        Press second = new Press(in.withLauncherRows(rows2), fx.withClientDiag(diag2));
        second.sidelogOrNull = sidelogOrNull(diag2);
        second.sidelogMaxMib = capMib;
        second.recordWindow = true;
        second.publicDial = true;
        String cid2 = mintCid();
        while (cid2.equals(first.cid)) {
            cid2 = mintCid();
        }
        second.cidOverrideOrNull = cid2;
        Result r2 = pressOne(second);
        String shown = switch (r2.outcome()) {
            case OPENED -> "opened";
            case REOPENED -> "reopened";
            case REFUSED -> "refused:" + r2.reasonToken();
        };
        System.out.println("cc-ops join second=" + shown);
        String text2 = r2.outcome() == Outcome.REOPENED ? NOTE_SECOND_LIVE : r2.text();
        List<JoinWindowSweep.Window> opened = new ArrayList<>(r1.windows());
        opened.addAll(r2.windows());
        return new Result(Outcome.OPENED, r1.text() + " " + SECOND_PREFIX + text2, null, "-", opened,
                r1.armLines() + r2.armLines());
    }

    /** {@link #pressWatch}'s row-2 check: null = a second game distinct from the first, else {@link #R16} /
     *  {@link #R17} filled with the bounded row. A {@code toRealPath} failure = {@link #R16} (fail-closed).
     * */
    private static String secondInstallRefusalOrNull(Path packageRoot, Map<String, String> rows,
            Map<String, String> rows2, String row2, Effects fx) {
        String r16 = String.format(Locale.ROOT, R16, boundedText(row2));
        Path install2 = installOrNull(packageRoot, rows2);
        if (install2 == null) {
            return r16;
        }
        Path install1 = installOrNull(packageRoot, rows);
        if (install1 == null) {
            return null;
        }
        try {
            if (fx.realPath(install1).equals(fx.realPath(install2))) {
                return String.format(Locale.ROOT, R17, boundedText(row2));
            }
        } catch (IOException | RuntimeException unresolved) {
            System.out.println("cc-ops join second_path=unresolved (" + unresolved.getClass().getSimpleName() + ")");
            return r16;
        }
        return null;
    }

    /**
     * The row-2 check for a shell that has not pressed yet: the web console's debug-watch Start runs it BEFORE the
     * serve starts, so a wrong second folder refuses the Start instead of leaving a running serve with no windows
     * (formerly {@link #R16} / {@link #R17} fired only in the daemon's press, AFTER the serve was live, and the
     * "press again" they ask for is what the console refuses while a serve lives). {@code rows} = the launcher rows
     * as the Start is about to save them (the typed folders overlaid); a blank / absent {@code game.root.second} =
     * one window = null. Else exactly {@link #pressWatch}'s check: null = two distinct games, or {@link #R16} /
     * {@link #R17} filled with the bounded row. Thread: any (path resolution only; nothing read past the core jar,
     * nothing written).
     */
    static String secondFolderRefusalOrNull(Path packageRoot, Map<String, String> rows, Effects fx) {
        String row2 = trimmed(rows == null ? null : rows.get(ServeLauncher.ROW_GAME_ROOT_SECOND));
        if (row2.isEmpty()) {
            return null;
        }
        Map<String, String> rows2 = new HashMap<>(rows);
        rows2.put(ServeLauncher.ROW_GAME_ROOT, row2);
        return secondInstallRefusalOrNull(packageRoot, rows, rows2, row2, fx);
    }

    /** {@code <diagDir>\}{@value #SIDELOG_FILE}, or null when there is no diag folder or it fails the diag
     *  decorator's own law (absolute, {@link #DIAG_DIR_CHARS}) - the sidelog never names a folder
     *  {@link #clientDiagEnv} refuses to make. */
    static Path sidelogOrNull(Path diagDirOrNull) {
        if (diagDirOrNull == null || !diagDirOrNull.isAbsolute()
                || !DIAG_DIR_CHARS.matcher(diagDirOrNull.toString()).matches()) {
            return null;
        }
        return diagDirOrNull.resolve(SIDELOG_FILE);
    }

    /** {@link #ROW_WATCH_CLIENT_LOG_MAX_MIB}'s value as MiB per window: null, blank or absent =
     *  {@link #WATCH_CLIENT_LOG_DEFAULT_MIB}; 1..5 ASCII digits (surrounding blanks ignored) in
     *  {@link #WATCH_CLIENT_LOG_MIN_MIB}..{@link #WATCH_CLIENT_LOG_MAX_MIB} = that number; anything else (a sign, a
     *  unit, a fraction, junk, out of range) = -1, the press's {@link #R18}. Pure, never throws.
     * */
    static int watchClientLogMaxMib(String rowOrNull) {
        String v = trimmed(rowOrNull);
        if (v.isEmpty()) {
            return WATCH_CLIENT_LOG_DEFAULT_MIB;
        }
        if (!v.matches("[0-9]{1,5}")) {
            return -1;
        }
        int mib = Integer.parseInt(v);
        return mib >= WATCH_CLIENT_LOG_MIN_MIB && mib <= WATCH_CLIENT_LOG_MAX_MIB ? mib : -1;
    }

    /** One window's press: {@link #press}'s body over a prepared {@link Press} (see there).
     * */
    private static Result pressOne(Press p) {
        Result result = null;
        for (Step step : STEPS) {
            result = step.body().run(p);
            if (result != null) {
                break;
            }
        }
        if (result == null) {
            throw new IllegalStateException("the join steps ended without a result");
        }
        // Every answer carries whether this window's arm line landed, so
        // a spawn refusal after the append still reports the door slot it took.
        String text = p.kitNoteOrNull == null ? result.text() : p.kitNoteOrNull + " " + result.text();
        result = new Result(result.outcome(), text, result.liveUrlOrNull(), result.reasonToken(), result.windows(),
                p.armed ? 1 : 0);
        System.out.println("cc-ops join outcome=" + result.outcome().name().toLowerCase(Locale.ROOT)
                + " reason=" + result.reasonToken());
        return result;
    }

    /** (1) The install: R1 / R2 = REFUSED; nothing read, written or started. */
    private static Result step1Install(Press p) {
        p.install = installOrNull(p.in.packageRoot(), p.in.launcherRows());
        String refusal = installRefusalOrNull(p.install, p.fx, p.in.kitHint());
        return refusal == null ? null : refused(p.install == null ? "r1" : "r2", refusal);
    }

    /**
     * The kit check's answer for a caller that has not pressed yet (a watch press runs it before the serve starts).
     * {@code refusedOrNull} = stop with this REFUSED result; null = proceed, {@code noteOrNull} = what changed in the
     * game folder (null = nothing). WHY a record and not a {@link Result}: a refreshed kit must PROCEED with a note,
     * and {@link Outcome} has no "proceed" constant (both shells switch on it exhaustively).
     *
     */
    record KitCheck(Result refusedOrNull, String noteOrNull) {
    }

    /**
     * The kit check alone, before a Start: step (1)'s install law first (an unset or bad Game folder = its R1 / R2
     * here, nothing read past it), then {@link #kitCheckOf}. Null = the kit is current or the check is skipped
     * (proceed, nothing to say). Thread: any, as {@link #press}.
     */
    static KitCheck kitCheckOrNull(Inputs in, Effects fx) {
        Press p = new Press(in, fx);
        Result r = step1Install(p);
        return r != null ? new KitCheck(r, null) : kitCheckOf(p);
    }

    /**
     * (1c) The installed kit is this server's, checked before the press writes anything (WHY: a stale kit refuses
     * with nothing written, and a refreshed kit is in place before its window is opened). Null =
     * continue; a refresh's note is kept on the press ({@link Press#kitNoteOrNull}) for its answer.
     *
     */
    private static Result step1cKitCurrent(Press p) {
        KitCheck c = kitCheckOf(p);
        if (c == null) {
            return null;
        }
        p.kitNoteOrNull = c.noteOrNull();
        return c.refusedOrNull();
    }

    /**
     * The kit check over a press whose step (1) passed. No package jar md5 ({@code kitJarMd5OrNull} null) = ONE row
     * {@code cc-ops join kit_check=skipped} and ZERO reads (a shell that feeds no stamps keeps its old press, a
     * fingerprint alone never arms it). Else the installed {@value #KIT_MANIFEST}'s {@code jarMd5} must equal the
     * package's, case-blind (the manifest is UPPERCASE, the package stamp lowercase), and, when the package carries
     * a fingerprint, the installed sidecar's composite must equal it with {@code ;match=} stripped on BOTH sides (the
     * packer's own "same kit" law: a kit packed for another match shape is still this kit). A missing / unreadable
     * manifest, a missing {@code jarMd5} row, or a missing / unparsable sidecar while a fingerprint is set = STALE
     * (fail-closed). ONE row {@code cc-ops join kit_check=current|stale} (no path, no md5); current = null; stale
     * with the package's kit zip at hand = {@link #refreshOrRefuse}; stale without one = REFUSED {@code kit_stale}
     * with {@link #R12}, nothing written.
     */
    private static KitCheck kitCheckOf(Press p) {
        String want = p.in.kitJarMd5OrNull();
        if (want == null) {
            System.out.println("cc-ops join kit_check=skipped");
            return null;
        }
        Path kit = cwd(p.install);
        List<String> m = stampRowsOrNull(p.fx, kit.resolve(KIT_MANIFEST), KIT_MANIFEST_MAX_BYTES);
        String jar = m == null ? null : lastValue(m, "jarMd5");
        String fp = p.in.kitFingerprintOrNull();
        boolean ok = want.equalsIgnoreCase(jar) && (fp == null || sameKit(installedCompositeOrNull(p.fx, kit), fp));
        System.out.println("cc-ops join kit_check=" + (ok ? "current" : "stale"));
        if (ok) {
            return null;
        }
        String stamp = m == null ? null : lastValue(m, "kitStamp");
        String yours = stampShown(stamp, jar);
        if (p.in.kitZipOrNull() != null) {
            return refreshOrRefuse(p, stamp, yours);
        }
        return new KitCheck(refused("kit_stale", r12(p, yours)), null);
    }

    /**
     * The stale kit replaced from the package's kit zip, SILENTLY (WHY: the press is the host's own request and the
     * old kit is parked, never deleted), in this order -
     * each refusal ONE row {@code cc-ops join kit_refresh=refused:<token>} ({@code (SimpleName)} when an exception
     * caused it), never a path or an entry name on stdout:
     * (0) a live join window ({@link #step2LiveWindow}'s detector) = {@code live_window} / {@link #R13}, nothing read
     * or moved (its game holds the kit's files open);
     * (1) the zip read in memory and checked against the package's stamps ({@link #zipIsThisKit}) =
     * {@code verify} on a damaged or foreign zip, {@code io} on any other read failure - nothing on disk touched yet;
     * (2) the {@code CC_Client/} entries written into a NEW staging folder beside {@code mods} (README and anything
     * else outside {@code CC_Client/} skipped), each name contained ({@link #stagedPathOrNull}, else {@code verify});
     * a create / write failure = {@code denied} (the staging IS the writability probe);
     * (3) the installed kit renamed into its park ({@link #PARK_DIR}; a taken name tries the next suffix) - a
     * cross-volume refusal or no free name = {@code io}, any other failure = {@code held} (a file in it is open:
     * an inference, a failed rename moves nothing);
     * (4) the staged kit renamed in; on failure the park is renamed back = {@code rolled_back} (R14), or, when that
     * fails too, {@code rollback_failed} ({@link #R15} naming the park);
     * (5) the staging folder deleted (a failure = ONE {@code kit_stage=left} row, never fatal), {@code done}, and
     * the press goes on with {@link #NOTE_KIT_REFRESHED}. Every refusal after (2) began deletes the staging folder;
     * no path deletes a park.
     */
    private static KitCheck refreshOrRefuse(Press p, String kitStampOrNull, String yours) {
        if (liveUrlOrNull(p.fx, endpointFile(p.in.localAppData(), p.install)) != null) {
            System.out.println("cc-ops join kit_refresh=refused:live_window");
            return new KitCheck(refused("kit_live_window", R13), null);
        }
        Map<String, byte[]> byName = new LinkedHashMap<>();
        try {
            for (KitZip.Entry e : p.fx.readKitZip(p.in.kitZipOrNull())) {
                byName.put(e.name(), e.bytes());
            }
        } catch (KitZip.Refused | ZipException damaged) {
            return refreshRefused(p, "verify", damaged, yours, WHY_ZIP_MISMATCH);
        } catch (IOException | RuntimeException failed) {
            return refreshRefused(p, "io", failed, yours, whyIo(failed));
        }
        if (!zipIsThisKit(byName, p.in.kitJarMd5OrNull(), p.in.kitFingerprintOrNull())) {
            return refreshRefused(p, "verify", null, yours, WHY_ZIP_MISMATCH);
        }
        Path mods;
        Path root;
        try {
            mods = p.fx.realPath(cwd(p.install).getParent());
            root = Objects.requireNonNull(mods.getParent(), "the mods folder has no parent");
        } catch (IOException | RuntimeException failed) {
            return refreshRefused(p, "io", failed, yours, whyIo(failed));
        }
        Path stage;
        try {
            stage = p.fx.createStage(root);
        } catch (IOException | RuntimeException failed) {
            return refreshRefused(p, "denied", failed, yours, WHY_NOT_WRITABLE);
        }
        try {
            for (Map.Entry<String, byte[]> e : byName.entrySet()) {
                if (!e.getKey().startsWith(KitPackRules.MOD_DIR + "/")) {
                    continue;
                }
                Path dst = stagedPathOrNull(stage, e.getKey());
                if (dst == null) {
                    dropStage(p, stage);
                    return refreshRefused(p, "verify", null, yours, WHY_ZIP_MISMATCH);
                }
                p.fx.writeNew(dst, e.getValue());
            }
        } catch (IOException | RuntimeException failed) {
            dropStage(p, stage);
            return refreshRefused(p, "denied", failed, yours, WHY_NOT_WRITABLE);
        }
        Path installed = mods.resolve(KitPackRules.MOD_DIR);
        String parkBase = KitPackRules.MOD_DIR + "-" + parkStamp(kitStampOrNull);
        Path park = null;
        FileAlreadyExistsException lastTaken = null;
        for (int n = 1; n <= PARK_SUFFIX_MAX && park == null; n++) {
            Path candidate = root.resolve(PARK_DIR).resolve(n == 1 ? parkBase : parkBase + "-" + n);
            try {
                p.fx.moveDir(installed, candidate);
                park = candidate;
            } catch (FileAlreadyExistsException taken) {
                lastTaken = taken; // the next suffix; all taken = the io refusal below names it
            } catch (AtomicMoveNotSupportedException crossVolume) {
                dropStage(p, stage);
                return refreshRefused(p, "io", crossVolume, yours, whyIo(crossVolume));
            } catch (IOException | RuntimeException held) {
                dropStage(p, stage);
                return refreshRefused(p, "held", held, yours, WHY_IN_USE);
            }
        }
        if (park == null) {
            dropStage(p, stage);
            return refreshRefused(p, "io", lastTaken, yours, whyIo(lastTaken));
        }
        try {
            p.fx.moveDir(stage.resolve(KitPackRules.MOD_DIR), installed);
        } catch (IOException | RuntimeException failed) {
            try {
                p.fx.moveDir(park, installed);
            } catch (IOException | RuntimeException stuck) {
                dropStage(p, stage);
                System.out.println("cc-ops join kit_refresh=rollback_failed (" + stuck.getClass().getSimpleName() + ")");
                return new KitCheck(refused("kit_rollback_failed", String.format(Locale.ROOT, R15,
                        boundedText(park.toString()), boundedText(mods.toString()))), null);
            }
            dropStage(p, stage);
            System.out.println("cc-ops join kit_refresh=rolled_back (" + failed.getClass().getSimpleName() + ")");
            return new KitCheck(refused("kit_rolled_back", r14(p, yours, WHY_ROLLED_BACK)), null);
        }
        dropStage(p, stage);
        System.out.println("cc-ops join kit_refresh=done");
        return new KitCheck(null, String.format(Locale.ROOT, NOTE_KIT_REFRESHED, boundedText(park.toString())));
    }

    /**
     * The refresh's helpers. {@code refreshRefused}: ONE {@code kit_refresh=refused:<token>} row (+ the cause's
     * SimpleName) and REFUSED {@code kit_<token>} with {@link #R14}. {@code whyIo}: R14's reason for a failed file
     * operation, naming only the exception's SimpleName. {@code dropStage}: the staging folder deleted, a failure =
     * ONE {@code kit_stage=left (<SimpleName>)} row (never fatal; the folder is our own, inert). {@code parkStamp}:
     * the installed kitStamp when {@link #PARK_STAMP} takes it, else {@code unstamped}. {@code r14}: {@link #R14}
     * filled (the zip and the {@code mods} folder bounded).
     */
    private static KitCheck refreshRefused(Press p, String token, Exception causeOrNull, String yours, String why) {
        System.out.println("cc-ops join kit_refresh=refused:" + token
                + (causeOrNull == null ? "" : " (" + causeOrNull.getClass().getSimpleName() + ")"));
        return new KitCheck(refused("kit_" + token, r14(p, yours, why)), null);
    }

    private static String whyIo(Exception causeOrNull) {
        return "a file operation failed" + (causeOrNull == null ? "" : " (" + causeOrNull.getClass().getSimpleName()
                + ")");
    }

    private static void dropStage(Press p, Path stage) {
        try {
            p.fx.deleteStage(stage);
        } catch (IOException | RuntimeException left) {
            System.out.println("cc-ops join kit_stage=left (" + left.getClass().getSimpleName() + ")");
        }
    }

    static String parkStamp(String kitStampOrNull) {
        return kitStampOrNull != null && PARK_STAMP.matcher(kitStampOrNull).matches() ? kitStampOrNull : "unstamped";
    }

    private static String r14(Press p, String yours, String why) {
        return String.format(Locale.ROOT, R14, yours, md5Shown(p.in.kitJarMd5OrNull()), why,
                boundedText(String.valueOf(p.in.kitZipOrNull())), boundedText(cwd(p.install).getParent().toString()));
    }

    /**
     * The kit zip is THIS server's kit, judged in memory before anything is written: the jar and the kit's
     * {@value #KIT_BAT} present; the jar's md5 = the package's {@code want} (case-blind); the zip's own
     * {@value #KIT_MANIFEST} names that md5 (the next press's check reads it); and, when the package carries a
     * fingerprint {@code fpOrNull}: the jar / data / handshake composite computed from the entries' bytes (the
     * packer's digest surface, {@link KitPackRules#surfaceKeyOrNull}, capped at {@link KitPackRules#MAX_SURFACE_BYTES})
     * equals it with {@code ;match=} stripped, AND the zip's own sidecar says the same (WHY the sidecar too: the next
     * press compares the INSTALLED sidecar - a zip whose sidecar disagreed would be re-parked on every press). A
     * composite that cannot be formed = ONE {@code kit_zip=no_composite} row and false. Pure; thread: any.
     *
     */
    static boolean zipIsThisKit(Map<String, byte[]> byName, String want, String fpOrNull) {
        byte[] jar = byName.get(KitPackRules.JAR_ENTRY);
        byte[] manifest = byName.get(KitPackRules.MANIFEST_ENTRY);
        if (jar == null || manifest == null || want == null
                || !byName.containsKey(KitPackRules.MOD_DIR + "/" + KIT_BAT)) {
            return false;
        }
        String md5 = ContentFingerprints.md5Hex(jar);
        if (!md5.equalsIgnoreCase(want) || !md5.equalsIgnoreCase(lastValue(settingsRows(manifest), "jarMd5"))) {
            return false;
        }
        if (fpOrNull == null) {
            return true;
        }
        TreeMap<String, byte[]> surface = new TreeMap<>();
        long surfaceBytes = 0;
        for (Map.Entry<String, byte[]> e : byName.entrySet()) {
            String key = KitPackRules.surfaceKeyOrNull(e.getKey());
            if (key != null) {
                surfaceBytes += e.getValue().length;
                if (surfaceBytes > KitPackRules.MAX_SURFACE_BYTES) {
                    return false;
                }
                surface.put(key, e.getValue());
            }
        }
        byte[] sidecar = byName.get(KitPackRules.SIDECAR_ENTRY);
        if (sidecar == null || sidecar.length > KitPackRules.MAX_SIDECAR_BYTES) {
            return false;
        }
        try {
            String fromBytes = ContentFingerprints.composite(md5, ContentFingerprints.dataMd5(surface),
                    HandshakeCodec.HANDSHAKE_VERSION, "m");
            String zipSidecar = ContentFingerprints.parseSidecarFingerprint(settingsRows(sidecar), "the kit zip sidecar");
            return sameKit(fromBytes, fpOrNull) && sameKit(zipSidecar, fpOrNull);
        } catch (IllegalArgumentException noComposite) {
            System.out.println("cc-ops join kit_zip=no_composite");
            return false;
        }
    }

    /**
     * Where a zip entry lands in the staging folder, or null when it may not land there: a name outside the kit
     * zip's grammar ({@link KitZip#nameOk}: no {@code ..}, {@code .}, {@code \}, {@code :}, leading {@code /},
     * empty segment or non-printable char) or one that resolves outside {@code stage} or onto it. WHY a second
     * test after the zip reader's own: the zip's entry names are DATA, and the containment is the writer's law.
     * Pure; thread: any.
     */
    static Path stagedPathOrNull(Path stage, String name) {
        if (!KitZip.nameOk(name)) {
            return null;
        }
        Path d = stage.resolve(name).normalize();
        return d.startsWith(stage) && !d.equals(stage) ? d : null;
    }

    /**
     * The kit check's helpers, pure over the {@link Effects} seams. {@code stampRowsOrNull}: a stamp file's rows
     * ({@link #settingsRows}); null when it is not a regular file (ordinary, no print) or its bounded read fails (ONE
     * row {@code cc-ops join kit_stamp=unreadable (<SimpleName>)}). {@code installedCompositeOrNull}: the kit
     * sidecar's {@code fingerprint=} value; null when the sidecar is absent / unreadable or carries no usable row
     * (ONE row {@code cc-ops join kit_sidecar=no_fingerprint}). {@code sameKit}: both composites equal once
     * {@code ;match=} is stripped ({@link KitPacker#withoutMatch}); case-sensitive, as the packer (both sides are
     * minted lowercase). {@code stampShown}: R12's "yours" slot - {@code kit <kitStamp>, jar <first 8 hex>}, each
     * {@code ?} when absent or out of shape, {@code no kit stamp} when both are absent. {@code md5Shown}: a 32-hex
     * md5's first 8 hex UPPERCASE, else {@code ?}. {@code r12}: {@link #R12} filled.
     *
     */
    private static List<String> stampRowsOrNull(Effects fx, Path file, int cap) {
        if (!fx.isRegularFile(file)) {
            return null;
        }
        try {
            return settingsRows(fx.readBounded(file, cap));
        } catch (IOException | RuntimeException e) {
            System.out.println("cc-ops join kit_stamp=unreadable (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private static String installedCompositeOrNull(Effects fx, Path kit) {
        List<String> rows = stampRowsOrNull(fx, kit.resolve(KIT_SIDECAR), KitPackRules.MAX_SIDECAR_BYTES);
        if (rows == null) {
            return null;
        }
        try {
            return ContentFingerprints.parseSidecarFingerprint(rows, "the installed kit sidecar");
        } catch (IllegalArgumentException noRow) {
            System.out.println("cc-ops join kit_sidecar=no_fingerprint");
            return null;
        }
    }

    static boolean sameKit(String installedOrNull, String fp) {
        return installedOrNull != null && KitPacker.withoutMatch(installedOrNull).equals(KitPacker.withoutMatch(fp));
    }

    static String stampShown(String kitStampOrNull, String jarOrNull) {
        if (kitStampOrNull == null && jarOrNull == null) {
            return "no kit stamp";
        }
        boolean stampOk = kitStampOrNull != null && KIT_STAMP_SHOWN.matcher(kitStampOrNull).matches();
        return "kit " + (stampOk ? kitStampOrNull : "?") + ", jar " + md5Shown(jarOrNull);
    }

    static String md5Shown(String md5OrNull) {
        return md5OrNull != null && MD5_HEX.matcher(md5OrNull).matches()
                ? md5OrNull.substring(0, 8).toUpperCase(Locale.ROOT) : "?";
    }

    private static String r12(Press p, String yours) {
        return String.format(Locale.ROOT, R12, yours, md5Shown(p.in.kitJarMd5OrNull()), boundedText(p.in.kitHint()),
                boundedText(cwd(p.install).getParent().toString()));
    }

    /** (2) A live join window = REOPENED with the Swing note + its URL; NOTHING written, NOTHING spawned. The web
     *  shell branches on REOPENED itself (its own note, a spawn WITHOUT a write - the bat's single-instance law
     *  re-opens the tab on the server computer); that branch is the shell's, not the core's. */
    private static Result step2LiveWindow(Press p) {
        String url = liveUrlOrNull(p.fx, endpointFile(p.in.localAppData(), p.install));
        return url == null ? null : new Result(Outcome.REOPENED, NOTE_REOPENED, url, "-");
    }

    /** (3) No live serve = R4. */
    private static Result step3LiveGate(Press p) {
        return p.in.liveOrNull() == null ? refused("r4", R4) : null;
    }

    /** (4) The saved password, lawful or blank. */
    private static Result step4Password(Press p) {
        p.password = passwordOrBlank(p.in.passwordOrBlank());
        return null;
    }

    /** (5) The dial; TUN mode asks the probe first (a failed probe = null = R10). A refusal carries its
     *  sentence: token {@code r10} for R10, else {@code dial}. A {@link Press#publicDial} press (the debug watch's
     *  second window) always probes and tries {@link #dialPublic} first, {@link #dial} when that is null; its one
     *  stdout row {@code cc-ops join second_dial=<vpn|local>} says which path it took. A
     *  {@link Press#localDial} press (the debug watch's first window of two) asks no probe and dials
     *  {@link #dialLocal} whatever the saved rows say, then one stdout row {@code cc-ops join first_dial=local}.
     * */
    private static Result step5Dial(Press p) {
        OpsSnapshot live = p.in.liveOrNull();
        Map<String, String> rows = p.in.launcherRows();
        VpnLeaseProbe.Probe probe = null;
        if (!p.localDial && (p.publicDial || tunMode(rows, live.host()))) {
            try {
                probe = p.fx.probe();
            } catch (IOException | RuntimeException silent) {
                System.out.println("cc-ops join probe=failed (" + silent.getClass().getSimpleName() + ")");
                probe = null;
            }
        }
        String endpoint = null;
        boolean viaVpn = false;
        try {
            if (p.localDial) {
                endpoint = dialLocal(live);
            } else if (p.publicDial) {
                endpoint = dialPublic(live, rows, probe);
                viaVpn = endpoint != null;
            }
            if (endpoint == null) {
                endpoint = dial(live, rows, probe);
            }
        } catch (IllegalArgumentException refusal) {
            String text = refusal.getMessage();
            return refused(R10.equals(text) ? "r10" : "dial", text);
        }
        if (p.localDial) {
            System.out.println("cc-ops join first_dial=local");
        }
        if (p.publicDial) {
            System.out.println("cc-ops join second_dial=" + (viaVpn ? "vpn" : "local"));
        }
        int colon = endpoint.lastIndexOf(':');
        p.dialHost = endpoint.substring(0, colon);
        p.dialPort = Integer.parseInt(endpoint.substring(colon + 1));
        return null;
    }

    /** (6) The merge-write of the join settings; an unreadable / over-cap file or a failed write = R6 and NO
     *  spawn (an unreadable file stays untouched). The seat key ({@link #settleCid}) is settled over the same bytes
     *  and, when minted for the FILE (an un-armed first-of-two window), WRITTEN in the same merge;
     * an ARMED window's key rides its env and is never written. */
    private static Result step6Write(Press p) {
        Path file;
        byte[] existing = null;
        try {
            file = settingsForRun(p.fx, p.in.localAppData(), p.in.appData(), p.in.userHome());
            if (p.fx.isRegularFile(file)) {
                existing = p.fx.readBounded(file, SETTINGS_MAX_BYTES);
            }
        } catch (IOException | RuntimeException unreadable) {
            return refused("r6_read", r6(p.install, unreadable));
        }
        byte[] merged = merge(existing, p.dialHost, p.dialPort, p.password);
        String cidRow = settleCid(p, existing, merged);
        if ("minted".equals(cidRow)) {
            merged = merge(existing, p.dialHost, p.dialPort, p.password, p.cid);
        }
        try {
            p.fx.writeAtomic(file, merged);
        } catch (IOException | RuntimeException writeFailed) {
            p.cid = null;
            return refused("r6_write", r6(p.install, writeFailed));
        }
        if (p.cid != null) {
            System.out.println("cc-ops join cid=" + cidRow);
        }
        return null;
    }

    /**
     * This window's seat key, set on {@code p.cid}; the answer = the stdout token
     * {@code kept|minted|minted_env|override} ({@code "-"} = not wanted, {@code p.cid} stays null). Wanted when the
     * press is armed or this is the first of two windows. The second window takes its override and never
     * touches the file's row. An ARMED window ({@link Inputs#adminArm} - the hand Join press and the WATCH press
     * alike) gets a FRESH mint on EVERY press, delivered through its env ({@value #PREDICT_CID_KEY}, the override's
     * own lane) and never written: the file's persisted {@code cid} row is the seat key the host sends to every CC
     * server it ever joins, and each of those keeps it in full in its ops-status {@code cidkey} row - so a stored
     * cid is never an admin grant credential, and the mint loops until it differs from the saved row.
     * Else - an un-armed first-of-two window taking its own seat back -
     * the saved {@code cid} is KEPT only when {@link #cidLawful} AND it is the very value the join window will read
     * from the merged bytes ({@link #windowCidOrNull}: a case variant {@code CID=}, a trailing blank or a
     * continuation the console's row read sees differently fails it); otherwise a fresh {@link #mintCid} - which
     * the caller then WRITES, or the window would mint another.
     *
     */
    private static String settleCid(Press p, byte[] existing, byte[] mergedWithoutCid) {
        if (p.cidOverrideOrNull != null) {
            p.cid = p.cidOverrideOrNull;
            return "override";
        }
        if (!p.in.adminArm() && !p.cidWanted) {
            return "-";
        }
        String saved = lastValue(settingsRows(existing), "cid");
        if (p.in.adminArm()) {
            String fresh = mintCid();
            while (fresh.equals(saved)) {
                fresh = mintCid();
            }
            p.cidOverrideOrNull = fresh;
            p.cid = fresh;
            return "minted_env";
        }
        if (cidLawful(saved) && saved.equals(windowCidOrNull(mergedWithoutCid))) {
            p.cid = saved;
            return "kept";
        }
        p.cid = mintCid();
        return "minted";
    }

    /**
     * (6b) The seat-key arm: with the press armed and a key settled, ONE {@code admin-cid <key>} line
     * ({@link BanSet#cidKey}, lowercase hex of the key's UTF-8) appended to the live serve's command file - the
     * door then elevates the window that presents that key and never counts or caps it at the per-address seat
     * cap. No command file (null / blank / {@code off}) = one skipped row; a failed append = one failed row naming
     * the class. Fail-open on the elevation, never on access: either way the window opens plain
     * ({@link #NOTE_OPENED}) and the host can still sign in by hand; this step never refuses. Stdout rows carry
     * tokens only - never the key or the file. Only this press's own
     * ENV-delivered mint is ever armed - never the settings file's persisted row (see {@link #settleCid}); the guard
     * is unreachable by construction and skips the arm, never the window.
     */
    private static Result step6bArm(Press p) {
        if (!p.in.adminArm() || p.cid == null) {
            return null;
        }
        if (!p.cid.equals(p.cidOverrideOrNull)) {
            System.out.println("cc-ops join admin_arm=skipped_not_env_key");
            return null;
        }
        String cmd = armFileOrNull(p.in.liveOrNull());
        if (cmd == null) {
            System.out.println("cc-ops join admin_arm=skipped_no_command_file");
            return null;
        }
        try {
            p.fx.appendCommandLine(Paths.get(cmd), "admin-cid " + BanSet.cidKey(p.cid));
        } catch (IOException | RuntimeException armFailed) {
            System.out.println("cc-ops join admin_arm=failed (" + armFailed.getClass().getSimpleName() + ")");
            return null;
        }
        p.armed = true;
        System.out.println("cc-ops join admin_arm=appended");
        return null;
    }

    /**
     * The live serve's command file the arm line is written to, or null when there is none to write
     * ({@code liveOrNull} null, or its {@code commandFile} null, blank or {@code off}) - the ONE predicate behind both
     * the console's arm decision ({@link Inputs#adminArm}) and step (6b)'s write, so the console arms exactly the
     * presses whose arm line can land (none once the serve holds the door's cap of armed keys: the console's
     * count). WHY the host's window is armed with no admin secret read: the command file
     * is the trust root (a player never reaches it) and the key is minted fresh for this press only, so the
     * secret's value adds nothing to the grant. A token-mode door refuses the arm and the window opens plain.
     *
     */
    static String armFileOrNull(OpsSnapshot liveOrNull) {
        String cmd = liveOrNull == null ? null : liveOrNull.commandFile();
        return cmd == null || cmd.isBlank() || "off".equals(cmd) ? null : cmd;
    }

    /** (7) The spawn: a start failure or a nonzero exit within the wait = R7; exit 0 or still running = OPENED
     *  ({@link #NOTE_OPENED_ADMIN} when this window was armed, else {@link #NOTE_OPENED}). The press's own env keys go in AFTER the {@link #childEnv} strip, so
     *  an inherited {@code CC_*} copy never reaches the window: the sidelog when set (with its cap,
     *  {@value #WATCH_SIDELOG_MAX_MIB_KEY}), the env-delivered seat key
     *  when set (the second window's, or an armed window's) - never inside {@value #CLIENT_JVM_FLAGS_KEY}.
     *  An OPENED window of a press that records ({@link Press#recordWindow}) rides
     *  {@link Result#windows} when the spawn kept its handle and start instant; without both it cannot be closed
     *  safely later, so it is not recorded. */
    private static Result step7Spawn(Press p) {
        Spawned spawned;
        try {
            Map<String, String> env = childEnv(p.fx.parentEnv());
            if (p.sidelogOrNull != null) {
                env.put(WATCH_SIDELOG_KEY, p.sidelogOrNull.toString());
                env.put(WATCH_SIDELOG_MAX_MIB_KEY, Integer.toString(p.sidelogMaxMib));
            }
            if (p.cidOverrideOrNull != null) {
                env.put(PREDICT_CID_KEY, p.cidOverrideOrNull);
            }
            spawned = p.fx.spawnWindow(argv(p.fx.systemRoot()), cwd(p.install), env);
        } catch (IOException | RuntimeException startFailed) {
            return refused("spawn_failed", String.format(Locale.ROOT, R7, boundedText(p.install.toString()),
                    startFailed.getClass().getSimpleName()));
        }
        int code = spawned.code();
        if (code != 0 && code != STILL_RUNNING) {
            return refused("spawn_nonzero", String.format(Locale.ROOT, R7, boundedText(p.install.toString()),
                    Integer.toString(code)));
        }
        List<JoinWindowSweep.Window> opened = !p.recordWindow || spawned.handleOrNull() == null
                || spawned.startOrNull() == null ? List.of()
                : List.of(new JoinWindowSweep.Window(JoinWindowSweep.live(spawned.handleOrNull()),
                        spawned.startOrNull(), cwd(p.install)));
        return new Result(Outcome.OPENED, String.format(Locale.ROOT, p.armed ? NOTE_OPENED_ADMIN : NOTE_OPENED,
                p.dialHost, p.dialPort), null, "-", opened);
    }

    // ---- helpers ----

    private static Result refused(String token, String text) {
        return new Result(Outcome.REFUSED, text, null, token);
    }

    private static String r6(Path install, Exception e) {
        return String.format(Locale.ROOT, R6, boundedText(install.toString()), e.getClass().getSimpleName());
    }

    /** K3: {@code s} cut to {@link #TEXT_PATH_MAX} chars ({@code ...} at the cut); null = {@code ""}. */
    static String boundedText(String s) {
        String t = s == null ? "" : s;
        return t.length() <= TEXT_PATH_MAX ? t : t.substring(0, TEXT_PATH_MAX - 3) + "...";
    }

    /** A host inside {@link #R5_SHAPE}: non-printable-ASCII as {@code ?}, cut to {@link #SHOWN_HOST_MAX}. */
    private static String shownHost(String host) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < host.length() && sb.length() < SHOWN_HOST_MAX; i++) {
            char ch = host.charAt(i);
            sb.append(ch >= 0x20 && ch < 0x7F ? ch : '?');
        }
        return host.length() > SHOWN_HOST_MAX ? sb + "..." : sb.toString();
    }

    /** {@code s} as a port-sized number, -1 when it is not 1-9 digits (an optional {@code +}; never a throw). */
    private static int portOrMinus1(String s) {
        return s.matches("\\+?[0-9]{1,9}") ? Integer.parseInt(s) : -1;
    }

    private static String trimmed(String s) {
        return s == null ? "" : s.trim();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** {@code JoinSettings.absoluteEnvDir}: a set, non-blank, ABSOLUTE folder value, else null. */
    private static Path absoluteEnvDir(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Path p = Paths.get(value);
            return p.isAbsolute() ? p : null;
        } catch (InvalidPathException notAPath) {
            System.out.println("cc-ops join env_dir=not_a_path");
            return null;
        }
    }

    /** The settings bytes as rows: ISO-8859-1 (byte-verbatim), split on CRLF / LF / lone CR; a final terminator
     *  ends the last row (no empty row after it); empty or null = no rows. */
    private static List<String> settingsRows(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        List<String> rows = new ArrayList<>(Arrays.asList(text.split("\r\n|\r|\n", -1)));
        if (rows.get(rows.size() - 1).isEmpty()) {
            rows.remove(rows.size() - 1);
        }
        return rows;
    }

    /** The LAST row for {@code key} ({@code ^\s*key\s*=}, case-blind as the golden), its value trimmed; null =
     *  no such row, {@code ""} = a row with an empty value. */
    private static String lastValue(List<String> rows, String key) {
        Pattern p = Pattern.compile("^\\s*" + key + "\\s*=", Pattern.CASE_INSENSITIVE);
        String value = null;
        for (String row : rows) {
            Matcher m = p.matcher(row);
            if (m.find()) {
                value = row.substring(m.end()).trim();
            }
        }
        return value;
    }

    /** One more backslash when {@code row} ends in an ODD run of them. */
    private static String continuationGuard(String row) {
        return endsInOddBackslashes(row) ? row + "\\" : row;
    }

    /** Does {@code row} end in an ODD run of backslashes - a {@code Properties} continuation onto the next row?
     * */
    private static boolean endsInOddBackslashes(String row) {
        int run = 0;
        for (int i = row.length() - 1; i >= 0 && row.charAt(i) == '\\'; i--) {
            run++;
        }
        return run % 2 == 1;
    }
}
