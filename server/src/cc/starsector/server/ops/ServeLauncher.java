package cc.starsector.server.ops;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDocumentLaws;
import cc.starsector.server.match.MatchDocumentRefused;
import cc.starsector.server.match.MatchPasswords;
import cc.starsector.server.match.MatchRespawnPolicy;
import cc.starsector.server.platform.ActivationGate;
import cc.starsector.server.runtime.MatchDocPreflight;
import cc.starsector.server.runtime.PostureRegistry;
import cc.starsector.server.runtime.SimTickRate;

/**
 * The release package's PowerShell-free server launcher (the package ships NO PowerShell).
 *
 * A host runs ONE class: it reads the frozen argv template ({@link ArgvTemplate}),
 * refuses READABLE before life 1 (one console line {@code serve-launcher refused: <sentence>} + the
 * receipt row {@code refused_prelaunch=true reason=<token>}), then supervises the serve JVM with
 * the receipt grammar the Ops GUI already reads ({@link ServeSupervisor}).
 *
 * <p><b>Order</b>: parse args → read {@code launcher.properties} +
 * the template text → create {@code <pkg>/run/} → take {@code run/serve.lock} by
 * {@link FileChannel#tryLock()} BEFORE any other touch under {@code run/} (a refused second
 * launcher writes nothing — re-pointing {@code latest.txt} first would send the GUI's Kill to
 * the wrong flight, the pwsh live-pid-guard-before-pointer order) → refuse {@code serve_running} while
 * {@code run/ops/ops-status.txt}'s writer is alive → the {@code run/stop} boot law
 * ({@link #stopOrderedAtBoot}: one written after this launcher started exits {@link #EXIT_STOPPED_AT_BOOT}
 * here, an older one is deleted as stale) → delete the two {@code run/ops/} files → create {@code flight-<yyyyMMdd-HHmmss>/} (+ {@code lives/},
 * {@code settings/*}) → delete then write {@code latest.txt} → the pre-checks in the vocabulary's
 * order → {@code server-cmd.txt} + {@code launch.txt} → the {@code door unarmed} line
 * → the shutdown hook → {@link ServeSupervisor#run()}.
 *
 * <p><b>Pre-boot row parses use the serve JVM's OWN pure parsers</b> on the config file read here
 * (never {@code ServerConfig}'s static cascade, which would read THIS JVM's sysprops): at the pin
 * every behaviour parse but the tick rate fires AFTER the ~50 s engine boot, so a typo would cost
 * a boot per life. The launcher runs on {@code classes/} alone and headless: no
 * engine, bridge or GUI class is referenced (the activation gate needs the game jars; it stays
 * in the serve JVM).
 *
 * <p>Deltas from the first design: D1 {@code java.home} row; D2
 * {@code game.root} = the install root OR its {@code starsector-core} child; D3 the stop file
 * polled mid-life ({@link ServeSupervisor}); D4 unknown config keys warn; D5 the child's last
 * stderr line first ({@link ServeSupervisor}); D6 the relaunch delay a constructor argument.
 *
 * <p><b>Password refusal + host console</b>:
 * the public package ships the kit's REDACTED descriptor, which the serve opens only under a password
 * ({@link MatchPasswords#requireLiveTokensInTokenMode}) — so {@code password_missing} refuses HERE, before
 * the ~50 s boot a stranger would otherwise pay per life to learn it; a present {@code .password} file is
 * parsed by the serve's own {@link MatchPasswords#parseSidecarPassword} ({@code row_parse}); the host sees
 * one console line per life start and the terminal receipt row ({@link ServeSupervisor}).
 *
 *
 */
public final class ServeLauncher {

    public static final int EXIT_OK = 0;
    public static final int EXIT_RELAUNCH_BUDGET = 1;
    public static final int EXIT_REFUSED_PRELAUNCH = 2;
    public static final int EXIT_MANIFEST_MISMATCH = 3;
    /** = {@link ActivationGate#EXIT_REFUSED}: the game's activation check refused (FINAL, never relaunched). */
    public static final int EXIT_ACTIVATION_REFUSED = ActivationGate.EXIT_REFUSED;
    /**
     * A {@code run/stop} written after this launcher started was found at boot ({@link #stopOrderedAtBoot}): the
     * launcher exited before any flight, pointer, life or supervisor. 4 is no other launcher or package-script code
     * (0 1 2 3 78 above; 130 = Ctrl+C; 1 is also the JVM's own uncaught-error and forced-end code), so no reader
     * takes it for a refusal or a failing server.
     *
     *
     */
    public static final int EXIT_STOPPED_AT_BOOT = 4;
    /** Relaunches after life 1 (= the Ops GUI's {@code ServerOpsCore.START_MAX_RELAUNCHES}). */
    public static final int MAX_RELAUNCHES = 5;
    /** Pause before each relaunch (D6: the rows drive {@link ServeSupervisor} with a short one). */
    public static final long RELAUNCH_DELAY_MS = 2000L;

    static final String RUN_DIR = "run";
    static final String LOCK_FILE = "serve.lock";
    static final String STOP_FILE = "stop";
    /** {@link #stopOrderedAtBoot}'s claim: a boot-time {@code run/stop} renamed here before its file time is read. */
    static final String STOP_CLAIM_FILE = "stop.claimed";
    static final String POINTER_FILE = "latest.txt";
    static final String OPS_DIR = "ops";
    static final String OPS_STATUS_FILE = "ops-status.txt";
    static final String OPS_CMD_FILE = "ops-cmd.txt";
    static final String SERVER_CMD_FILE = "server-cmd.txt";
    static final String LAUNCH_FILE = "launch.txt";
    static final String LIVES_DIR = "lives";
    static final String SETTINGS_DIR = "settings";
    static final List<String> SETTINGS_SUBDIRS = List.of("logs", "saves", "screenshots");
    /** The flight dir stamp = {@code LaneFollow}'s {@code flight-[0-9]{8}-[0-9]{6}} pointer shape. */
    static final DateTimeFormatter FLIGHT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    static final String FLIGHT_PREFIX = "flight-";

    static final String DEFAULT_CONFIG = "cc-server.properties";
    static final String DEFAULT_TEMPLATE = "serve.argv";
    static final String DEFAULT_LAUNCHER_PROPERTIES = "launcher.properties";
    static final String MANIFEST_FILE = "MANIFEST.txt";
    /** The game's core engine jar: the folder holding it IS {@code ${GAME}} (D2). */
    static final String CORE_JAR = "starfarer_obf.jar";
    static final String CORE_DIR = "starsector-core";
    static final List<String> MESA_DLLS = List.of("opengl32.dll", "libgallium_wgl.dll");

    // launcher.properties rows (BOX rows the serve JVM never sees)
    static final String ROW_JAVA_HOME = "java.home";
    static final String ROW_GAME_ROOT = "game.root";
    /** The SECOND install the debug WATCH opens a second pilot window from (blank / absent =
     *  one window); a box row like {@link #ROW_GAME_ROOT}, validated by the press that uses it, never by the serve. */
    static final String ROW_GAME_ROOT_SECOND = "game.root.second";
    static final String ROW_HEAP_MIN = "heap.min";
    static final String ROW_HEAP_MAX = "heap.max";
    static final String ROW_CI_COUNT = "jit.compilerCount";
    static final String ROW_GL_DRIVER = "gl.driver";
    static final String GL_LLVMPIPE = "llvmpipe";
    static final String GL_HARDWARE = "hardware";
    /**
     * Owner mode's bind:
     * BOX rows, never tracked-config rows, exported to the serve as its registered env twins by
     * {@link #childEnv}. Blank = not set: the config's {@code server.bindPort} row rules, as in a stranger package.
     *
     *
     */
    static final String ROW_BIND_HOST = "bind.host";
    static final String ROW_BIND_PORT = "bind.port";
    /**
     * The recording rows ({@link ServeRecord}): {@code record} = off | diag | full (absent = off), {@code record.maxMiB}
     * = the per-start network-record cap (absent = {@value ServeRecord#CAP_DEFAULT_MIB}). Read in BOTH modes
     * ({@link #recordRows}): a package's rows are the host's Recording pick; owner mode's rows are
     * MINT-extracted from the owner-mode serve argv ({@code mint-serve-template.ps1} {@code Get-RecordRows}, the five
     * lines taken out of the template and re-inserted once before the main class, so the served set is unchanged).
     * Only {@link #R_RECORD_DISK} and the {@code net.sinkPath} rider stay packed-only.
     *
     *
     */
    static final String ROW_RECORD = "record";
    static final String ROW_RECORD_MAX_MIB = "record.maxMiB";
    /**
     * {@code timer.optOut=off} skips the per-start timer opt-out
     * exactly as {@code -D}{@value TimerOptOut#KNOB_PROPERTY}{@code =off} on this JVM does ({@link TimerOptOut#knobOff}'s
     * grammar: {@code off}, trimmed, any case; anything else = on): the property OR the row = off
     * ({@link #timerApplier}). WHY a row: a host's choice lives in the file an update keeps, not in a start
     * script an update replaces. The console passes only its own property on; the launcher reads this row.
     */
    static final String ROW_TIMER_OPT_OUT = "timer.optOut";

    // the closed refusal vocabulary (receipt row reason=<token>)
    public static final String R_TEMPLATE_MISSING = "template_missing";
    public static final String R_TEMPLATE_TOKEN = "template_token";
    public static final String R_TEMPLATE_LEAK = "template_leak";
    public static final String R_GAME_ROOT = "game_root";
    public static final String R_JAVA_MISSING = "java_missing";
    public static final String R_MESA_MISSING = "mesa_missing";
    public static final String R_GAME_JAR_MISSING = "game_jar_missing";
    public static final String R_CONFIG_MISSING = "config_missing";
    public static final String R_DESCRIPTOR_ROW = "descriptor_row";
    public static final String R_DESCRIPTOR_MISSING = "descriptor_missing";
    public static final String R_PASSWORD_MISSING = "password_missing";
    /** The optional {@code .adminpassword} sidecar in a shape the serve would refuse at its open (present without a
     * match password, or equal to it); its grammar failures are {@link #R_ROW_PARSE}. */
    public static final String R_ADMIN_SIDECAR = "admin_sidecar";
    public static final String R_SIDECAR_MISSING = "sidecar_missing";
    public static final String R_SIDECAR_JAR = "sidecar_jar";
    public static final String R_SIDECAR_STALE = "sidecar_stale";
    /**
     * An armed door whose sidecar {@code match=} component is not the descriptor's {@code match.id} - a SPLIT pair
     * (a package Apply interrupted between its two moves, or a hand-copied file): the door would expect one match
     * text while the serve plays another. Refused before life 1 with {@link #SIDECAR_PAIR_SENTENCE}.
     *
     *
     *
     */
    public static final String R_SIDECAR_PAIR = "sidecar_pair";
    /** The {@link #R_SIDECAR_PAIR} sentence ({@code <path>} = the sidecar). WHY "delete and start again", never
     *  "re-extract": the package zip carries no match files (they are host files); the first start copies a shipped
     *  pair back when BOTH halves are absent. */
    static final String SIDECAR_PAIR_SENTENCE = "the fingerprint file <path> belongs to another match text - press"
            + " Apply again on the console's Setup tab, or delete both match files and start again (a shipped shape"
            + " is copied back)";
    /**
     * A password-mode Start whose match document no join window could adopt: the descriptor FILE, read under
     * {@link #MAX_CONFIG_BYTES} and decoded with the serve's own codec, fails {@link MatchDocPreflight#prepare} (a
     * receive law, the text / deflated caps, the inflate round-trip). Refused before life 1 with
     * {@link #MATCH_DOC_SENTENCE} (+ {@link #MATCH_DOC_PACKAGE_TAIL} on a packed server) - the serve's boot throws on
     * the same document ({@code PersistentPilotIntentCombatServer.preflightMatchDoc}), so without this check the
     * host would watch lives fail instead of reading one sentence. Token mode serves no document: never refused here.
     *
     *
     */
    public static final String R_MATCH_DOC = "match_doc_unservable";
    /** The {@link #R_MATCH_DOC} sentence ({@code <reason>} = the refusal's reason token, {@code <field>} = OUR field
     *  name - never descriptor text). */
    static final String MATCH_DOC_SENTENCE = "Start refused: players could not receive this match (<reason>, <field>)"
            + " - re-Apply the shape with fewer players, or pick another shape.";
    /** The {@link #R_MATCH_DOC} tail on a PACKED server (pack.stamp set): a shipped shape's pair is copied back from
     *  the package's match defaults when both halves are absent; the defaults themselves are program files the update
     *  script renews (the zip carries no match files, so "re-extract" would change nothing). */
    static final String MATCH_DOC_PACKAGE_TAIL = "If every listed shape is refused, delete a shipped shape's two match"
            + " files and start again (a fresh copy is made); if it is refused again, run update.bat (update.sh on"
            + " Linux) again with the zip you downloaded.";
    /** The packed-serve rider: a set {@code map.half} / {@code match.deployCr} row is inert there - the
     *  serve takes both from the descriptor - so it warns, never refuses (the CR twin worded alike). */
    static final String RIDER_MAP_HALF = "this server takes the map size from the match file, not the map.half row";
    static final String RIDER_DEPLOY_CR = "this server takes the deploy CR from the match file, not the match.deployCr"
            + " row";
    /**
     * A server's (either mode) {@link #ROW_RECORD} / {@link #ROW_RECORD_MAX_MIB} row does not parse, or the template already
     * sets a key the record lines would add ({@link ServeRecord}'s sentence); {@link #R_RECORD_DISK} = {@code record=full}
     * with less usable space under {@code run/} than the cap plus {@value ServeRecord#DISK_HEADROOM_MIB} MiB, checked
     * once per start (each relaunch may write up to one cap more; the tip says so).
     *
     *
     */
    public static final String R_RECORD_ROW = "record_row";
    public static final String R_RECORD_DISK = "record_disk";
    /** The packed-serve rider for a config {@code net.sinkPath} row (one warning line, never a refusal). */
    static final String RIDER_SINK_PATH = "the config row net.sinkPath records all network traffic into one file outside"
            + " this start's run folder, with no size cap unless net.sinkMaxMiB is set; record=full in launcher.properties"
            + " records into each start's run folder, capped by record.maxMiB";
    public static final String R_ROW_PARSE = "row_parse";
    public static final String R_BIND_PORT = "bind_port";
    public static final String R_MANIFEST_MISSING = "manifest_missing";
    public static final String R_MANIFEST_MISMATCH = "manifest_mismatch";
    /** A packed server's Mods rows could not be honoured ({@link PackageModSync}: an id with no folder, an
     * ambiguous id, a folder-name collision, a copy failure); the sentence names the mod. */
    public static final String R_MODS = "mods";
    /**
     * Linux only, checked right after the template: the package folder holds whitespace or a colon, or {@code game.root}
     * holds a colon ({@link #pathUnsafe} / {@link #hasColon}) — ld.so splits {@code LD_PRELOAD} on both, and a colon
     * splits the classpath and the library path. {@link #R_DISPLAY_MISSING}: Linux only, after java — {@code DISPLAY} is
     * blank in the INJECTED inherited env (the engine needs an X screen; {@code serve.sh} starts a virtual one).
     *
     *
     */
    public static final String R_PATH_UNSAFE = "path_unsafe";
    public static final String R_DISPLAY_MISSING = "display_missing";
    /** Console only: a refused second launcher touches nothing under {@code run/}. */
    public static final String R_LOCKED = "locked";
    /**
     * Console only, like {@link #R_LOCKED}: a serve whose launcher is gone still writes {@code run/ops/ops-status.txt};
     * refused after the lock, before any {@code run/} write ({@link #liveStatusWriter}).
     *
     *
     */
    public static final String R_SERVE_RUNNING = "serve_running";
    /** The {@code tryLock} retry: 10 tries 50 ms apart = 0.5 s before {@code locked}. */
    static final int LOCK_TRIES = 10;
    static final long LOCK_RETRY_MS = 50L;

    public static final String REFUSED_PREFIX = "serve-launcher refused: ";
    public static final String DOOR_UNARMED_LINE =
            "door unarmed: the content fingerprint is not checked at the door";
    /** {@link #stopOrderedAtBoot}'s one console line for an order (exit {@link #EXIT_STOPPED_AT_BOOT}). */
    public static final String STOPPED_AT_BOOT_LINE =
            "serve-launcher: stopped at boot by run/stop (written after this launcher started)";
    /**
     * How far BEFORE this launcher's start instant a {@code run/stop}'s file time may lie and still be an order.
     * WHY not zero: a file time is stamped from the system clock tick (a 1 ms step on the NTFS volumes measured; one
     * default Windows tick = 15.625 ms), not from the clock the process start instant is read from, and a file
     * written right after a spawn returned was measured up to 0.56 ms BEFORE that child's start instant - a strict
     * compare would read such a stop as stale and boot the serve behind it. TWO default ticks bound that skew: one
     * tick (16 ms) left 0.375 ms over a 15.625 ms host tick, unmeasured on a default-tick box (a miss there = an
     * orphan serve after a Kill at boot; widened). The slack errs toward an order: a stop written at most
     * this long before the start costs one lost boot (exit {@link #EXIT_STOPPED_AT_BOOT}), never a serve booted
     * behind a stop.
     *
     *
     */
    static final long STOP_START_SLACK_MS = 32L;
    static final String USAGE = "usage: java -cp <pkg>/classes cc.starsector.server.ops.ServeLauncher"
            + " [--pkg <dir>] [--config <file>] [--template <file>] [--launcher-properties <file>]";

    /** The env vars a life inherits; everything else (every {@code CC_*} included) is dropped. */
    static final List<String> ENV_KEEP = List.of(
            "PATH", "SystemRoot", "windir", "ComSpec", "TEMP", "TMP", "USERPROFILE", "HOME", "LANG", "LC_ALL");
    /**
     * Linux only, kept beside {@link #ENV_KEEP} when inherited (exact name: a Linux env is case-sensitive): the X screen
     * {@code xvfb-run} starts and the cookie it mints. Never on Windows, whose child map stays today's. The package's
     * shim rides {@link #ENV_LD_PRELOAD}, SET by {@link #childEnv} whatever was inherited.
     *
     *
     */
    static final List<String> ENV_KEEP_LINUX = List.of("DISPLAY", "XAUTHORITY");
    static final String ENV_LD_PRELOAD = "LD_PRELOAD";
    /** Read like {@code ServerConfig}'s own cap: a config past this is not a config. */
    static final long MAX_CONFIG_BYTES = 262144;
    /** The armed-door missing-sidecar TAIL on a PACKED server (replaces owner mode's "set net.doorFingerprint=false"
     *  advice); the remedy = the first start's seed of an absent pair, never a re-extract (the zip carries no match
     *  files). */
    public static final String T5_SIDECAR_MISSING_PACKAGE = "This match shape came without its fingerprint file:"
            + " pick a listed match shape, or delete this match file and start again (a shipped shape is copied back;"
            + " a shape you made: make it again with New match shape).";
    /**
     * The {@link #R_SIDECAR_STALE} tail on a PACKED server (pack.stamp
     * set), {@code <match>} = the match file as {@link #hostRel} names it. WHY this remedy and never the update
     * script: a packed folder's shipped shapes live in its match defaults and are copied into the match folder
     * when BOTH files of a pair are absent, so deleting the pair restores a shipped shape keyed to this package's
     * client; a shape the host made has no shipped copy and is made again.
     */
    static final String SIDECAR_STALE_PACKAGE_TAIL = " - delete <match> and its .fingerprint and start again (a"
            + " shipped shape is copied back; a shape you made: make it again with New match shape)";
    /** The {@link #R_MANIFEST_MISMATCH} tail on a PACKED server in
     *  place of {@code (re-extract the package)}: an extract that stopped half-way is finished by running the
     *  update script again ({@code <script>} = {@code update.bat}, or {@code sh update.sh} on Linux). */
    static final String MANIFEST_MISMATCH_PACKAGE_TAIL = " - run <script> again with the zip you downloaded";

    /** {@code file} as a host names it in a sentence - relative to
     *  {@code pkg} with this platform's separator when it sits under the folder, else the full path. */
    static String hostRel(Path pkg, Path file, boolean windows) {
        Path abs = file.toAbsolutePath().normalize();
        Path root = pkg.toAbsolutePath().normalize();
        if (!abs.startsWith(root) || abs.equals(root)) {
            return abs.toString();
        }
        StringBuilder rel = new StringBuilder();
        for (Path name : root.relativize(abs)) {
            rel.append(rel.length() == 0 ? "" : windows ? "\\" : "/").append(name);
        }
        return rel.toString();
    }
    static final int MAX_LAUNCHER_PROPERTIES_BYTES = 64 * 1024;

    /** The usable bytes of the file store holding a folder (0 or more). */
    @FunctionalInterface
    interface DiskFree {
        long usableBytes(Path folder) throws IOException;
    }

    /**
     * The {@link #R_RECORD_DISK} read: {@code Files.getFileStore(run).getUsableSpace()}. A package-private SEAM (the
     * design's "a seam") so the suite grades the floor on any disk: a row swaps it and restores it in its finally.
     *
     *
     */
    static volatile DiskFree diskFree = folder -> Files.getFileStore(folder).getUsableSpace();
    /**
     * The config rows the serve reads that the posture census does NOT register ({@code ServerConfig.resolve}
     * reads any file key; {@code crossCheckRegistry} passes unregistered ones): {@code OpsMatchRows}'s
     * joiner default, the wave inflater's six ({@code WaveLoadoutInflater.resolveKnobs}; {@code wave.seed} is
     * read there and FORCED as a sysprop by {@code ReplayBoot} in a replay JVM after the posture enforce — a
     * registry row for it moved a live posture, so it stays unregistered), the finale creep
     * (PersistentPilotIntentCombatServer), the stall stack + the canister probe ({@code PilotServeResolver}) —
     * the launcher's census; the rows' T11 census pins it (a new unregistered row lands here too).
     */
    static final List<String> UNREGISTERED_KNOWN_KEYS = List.of("match.joinerDefaultVariant", "wave.inflate",
            "wave.quality", "wave.seed", "wave.smods", "wave.dmods", "wave.qualityPerWave", "wave.finaleCreepSu",
            "sim.stallStackMs", "serve.canisterProbe",
            // the Mods tab's rows: read by this launcher on a packed server (PackageModSync) and by the owner-mode
            // stage library; mods.optional is a registered knob
            OpsMatchRows.MODS_ENABLED_KEY, InstalledModScan.MODS_ROOTS_KEY);
    private static final int MAX_CONSOLE_TOKEN_CHARS = 200;
    /**
     * The running-serve guard reads at most this much of {@code run/ops/ops-status.txt}; its
     * {@code stamp_ms=} / {@code pid=} rows are the file's 2nd / 3rd lines ({@code OpsStatusFile.composeLines}).
     *
     *
     */
    static final int MAX_STATUS_SCAN_BYTES = 64 * 1024;
    private static final Pattern STATUS_PID = Pattern.compile("pid=([0-9]{1,18})");
    private static final Pattern STATUS_STAMP = Pattern.compile("stamp_ms=([0-9]{1,18})");
    /** {@code bind.host}: one whitespace-free token, at most a DNS name's 253 characters. */
    private static final Pattern BIND_HOST = Pattern.compile("\\S{1,253}");

    private static final Pattern HEAP = Pattern.compile("[1-9][0-9]{0,12}[kKmMgGtT]?");
    private static final Pattern CI_COUNT = Pattern.compile("[1-9][0-9]{0,3}");
    private static final Pattern GAME_JAR = Pattern.compile("\\$\\{GAME\\}[/\\\\]([^$;\\s]+?\\.jar)(?![^$;\\s])");
    /** A descriptor's seat-token row as {@code MatchDescriptorCodec} writes it ({@code seat.N.token=<token>}). */
    private static final Pattern SEAT_TOKEN_ROW = Pattern.compile("seat\\.[0-9]+\\.token=(.*)");
    /** The match-password sidecar beside the descriptor (PersistentPilotIntentCombatServer.mintMatchPassword). */
    static final String PASSWORD_SUFFIX = ".password";

    private ServeLauncher() {
    }

    public static void main(String[] a) {
        System.exit(run(a, System.out, System.err));
    }

    /** One pre-launch refusal: its vocabulary token, the host-facing sentence, the exit code. */
    record Refusal(String token, String sentence, int exit) {
        static Refusal of(String token, String sentence) {
            return new Refusal(token, sentence, EXIT_REFUSED_PRELAUNCH);
        }
    }

    /**
     * The testable entry (never calls {@code System.exit}): returns {@link #EXIT_OK},
     * {@link #EXIT_RELAUNCH_BUDGET}, {@link #EXIT_REFUSED_PRELAUNCH}, {@link #EXIT_MANIFEST_MISMATCH},
     * {@link #EXIT_ACTIVATION_REFUSED} or {@link #EXIT_STOPPED_AT_BOOT}.
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, isWindows(), System.getenv());
    }

    /**
     * {@link #run(String[], PrintStream, PrintStream)} with the host's OS and inherited env INJECTED. WHY a seam: the
     * separator token, the java name, the two Linux refusals, the exec bit, the natives step and the child env all key on
     * {@code windows}, and {@code display_missing} reads {@code inheritedEnv}, never {@code System.getenv()} — so the
     * rows grade the Linux branch on a Windows suite box ({@code windows=false} + their own map). The public entry
     * passes {@code isWindows()} + {@code System.getenv()}; nothing else calls this.
     *
     *
     */
    static int run(String[] args, PrintStream out, PrintStream err, boolean windows, Map<String, String> inheritedEnv) {
        Map<String, String> opts = parseArgs(args);
        Path pkg = null;
        if (opts != null) {
            pkg = opts.containsKey("--pkg") ? absolute(opts.get("--pkg")) : defaultPackageRoot();
        }
        if (opts == null || pkg == null) {
            err.println(USAGE);
            return EXIT_REFUSED_PRELAUNCH;
        }
        Path config = opts.containsKey("--config") ? absolute(opts.get("--config")) : pkg.resolve(DEFAULT_CONFIG);
        Path template = opts.containsKey("--template") ? absolute(opts.get("--template")) : pkg.resolve(DEFAULT_TEMPLATE);
        Path launcherProps = opts.containsKey("--launcher-properties")
                ? absolute(opts.get("--launcher-properties")) : pkg.resolve(DEFAULT_LAUNCHER_PROPERTIES);
        if (config == null || template == null || launcherProps == null) {
            err.println(USAGE);
            return EXIT_REFUSED_PRELAUNCH;
        }
        // A PACKED folder's first-start repair runs BEFORE
        // any read of the live files - the seeds, dropped rows, leftovers, the settings merge, the match re-key;
        // a folder without a release pack.stamp is a no-op. Its lines ride this launcher's own out.
        PackageRepair.repairIfPacked(pkg, out);
        Map<String, String> box = readLauncherRows(launcherProps, out);
        List<String> templateLines;
        try {
            templateLines = ArgvTemplate.read(template);
        } catch (IOException | RuntimeException unreadable) {
            templateLines = null; // the template_missing pre-check names it, after the lock
        }

        Path run = pkg.resolve(RUN_DIR);
        try {
            Files.createDirectories(run);
        } catch (IOException | RuntimeException e) {
            say(out, REFUSED_PREFIX + "the folder " + run + " cannot be created (" + e.getClass().getSimpleName() + ")");
            return EXIT_REFUSED_PRELAUNCH;
        }
        Path lockPath = run.resolve(LOCK_FILE);
        FileChannel lockChannel;
        try {
            lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) {
            say(out, REFUSED_PREFIX + "the lock file " + lockPath + " cannot be opened (" + e.getClass().getSimpleName() + ")");
            return EXIT_REFUSED_PRELAUNCH;
        }
        try {
            FileLock lock = null;
            // The console's probes (the GUI chain's lock wait, the stage tool's step 0, the
            // preflight's g0) each WIN this lock for one tryLock -> release cycle while this JVM boots; a single tryLock
            // landing inside such a cycle would refuse `locked` falsely. A real twin holds the lock for its whole
            // life, so a short retry weakens nothing.
            for (int attempt = 0; attempt < LOCK_TRIES && lock == null; attempt++) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(LOCK_RETRY_MS);
                    } catch (InterruptedException stop) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                try {
                    lock = lockChannel.tryLock();
                } catch (OverlappingFileLockException heldInThisJvm) {
                    lock = null;
                } catch (IOException e) {
                    say(out, REFUSED_PREFIX + "the lock file " + lockPath + " cannot be locked (" + e.getClass().getSimpleName() + ")");
                    return EXIT_REFUSED_PRELAUNCH;
                }
            }
            if (lock == null) {
                say(out, REFUSED_PREFIX + "the package is locked by another running server launcher (" + lockPath + ")");
                return EXIT_REFUSED_PRELAUNCH;
            }
            return runLocked(pkg, run, config, template, templateLines, box, out, windows, inheritedEnv);
        } finally {
            try {
                lockChannel.close(); // releases the lock
            } catch (IOException e) {
                say(out, "serve-launcher warning: the lock file " + lockPath + " did not close cleanly");
            }
        }
    }

    /** Everything after the lock: stale signals, the flight, the pre-checks, the supervisor. */
    private static int runLocked(Path pkg, Path run, Path config, Path template, List<String> templateLines,
            Map<String, String> box, PrintStream out, boolean windows, Map<String, String> inheritedEnv) {
        Path stopFile = run.resolve(STOP_FILE);
        Path opsDir = run.resolve(OPS_DIR);
        Path opsStatus = opsDir.resolve(OPS_STATUS_FILE);
        Path opsCmd = opsDir.resolve(OPS_CMD_FILE);
        // An orphaned serve's status file is never wiped, nor a
        // twin booted on its port - FIRST after the lock, before every run/ write; console only (no flight, no receipt)
        long writer = liveStatusWriter(opsStatus, out);
        if (writer > 0L) {
            // stop.bat reaches only a running launcher - in THIS state (the launcher gone, the
            // serve alive) the remedy is ending the writer's process; the Ops console's Kill does that.
            say(out, REFUSED_PREFIX + "a server is still running (pid " + writer + ") and writing " + opsStatus
                    + "; its launcher is gone, so stop.bat cannot reach it - end process " + writer
                    + " yourself (Task Manager / taskkill, kill on Linux; the Ops console's Kill), then start again");
            return EXIT_REFUSED_PRELAUNCH;
        }
        // The run/stop boot law in place of its stale clear - before every run/
        // write but the lock, so a stop the Ops console's Kill wrote after this launcher started ends it here
        if (stopOrderedAtBoot(stopFile, ProcessHandle.current().info().startInstant(), out)) {
            return EXIT_STOPPED_AT_BOOT;
        }
        deleteStale(opsStatus, out);
        deleteStale(opsCmd, out);
        Path flight;
        try {
            Files.createDirectories(opsDir);
            flight = createFlightDir(run);
            Files.createDirectories(flight.resolve(LIVES_DIR));
            for (String sub : SETTINGS_SUBDIRS) {
                Files.createDirectories(flight.resolve(SETTINGS_DIR).resolve(sub));
            }
        } catch (IOException | RuntimeException e) {
            say(out, REFUSED_PREFIX + "a flight folder under " + run + " cannot be created (" + e.getClass().getSimpleName() + ")");
            return EXIT_REFUSED_PRELAUNCH;
        }
        Path pointer = run.resolve(POINTER_FILE);
        try {
            Files.deleteIfExists(pointer);
            Files.write(pointer, (flight.getFileName() + "\n").getBytes(StandardCharsets.US_ASCII));
        } catch (IOException | RuntimeException e) {
            say(out, "serve-launcher warning: " + pointer + " could not be written (" + e.getClass().getSimpleName() + ")");
        }
        Path receipt = flight.resolve(LaneFollow.RECEIPT_FILE);

        String glDriver = box.getOrDefault(ROW_GL_DRIVER, "").isBlank() ? GL_LLVMPIPE : box.get(ROW_GL_DRIVER).trim();
        boolean llvmpipe = !GL_HARDWARE.equals(glDriver); // an unknown value refuses at the row step
        String javaRow = box.getOrDefault(ROW_JAVA_HOME, "").trim();
        Path javaHome = javaRow.isEmpty() ? pkg.resolve("jre") : resolveRow(pkg, javaRow);
        Path java = javaHome == null ? null : javaHome.resolve("bin").resolve(windows ? "java.exe" : "java");
        String gameRow = box.getOrDefault(ROW_GAME_ROOT, "").trim();
        Path gameRoot = gameRow.isEmpty() ? null : resolveRow(pkg, gameRow);
        Path game = gameRoot == null ? null : resolveGame(gameRoot);
        String nativeName = windows ? "windows" : "linux";

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("PKG", pkg.toString());
        tokens.put("JAVA", java == null ? "" : java.toString());
        tokens.put("GAME", game == null ? "" : game.toString());
        tokens.put("NATIVE", nativeName);
        // the TARGET's separator (the flag), never the host JVM's File.pathSeparator: an injected run is complete
        tokens.put("SEP", windows ? ";" : ":");
        tokens.put("RUN", flight.toString());
        tokens.put("CONFIG", config.toString());
        tokens.put("HEAP_MIN", box.getOrDefault(ROW_HEAP_MIN, "").isBlank() ? "1g" : box.get(ROW_HEAP_MIN).trim());
        tokens.put("HEAP_MAX", box.getOrDefault(ROW_HEAP_MAX, "").isBlank() ? "2g" : box.get(ROW_HEAP_MAX).trim());
        tokens.put("CI_COUNT", box.getOrDefault(ROW_CI_COUNT, "").trim());

        Checked c = new Checked();
        Refusal refusal = preChecks(pkg, config, template, templateLines, box, tokens, windows, llvmpipe,
                java, gameRow, game, nativeName, inheritedEnv, c, out);
        if (refusal != null) {
            ServeSupervisor.appendRow(receipt, "refused_prelaunch=true reason=" + refusal.token(), out);
            say(out, REFUSED_PREFIX + refusal.sentence());
            return refusal.exit();
        }

        writeLines(flight.resolve(SERVER_CMD_FILE), c.argv, out);
        writeLines(flight.resolve(LAUNCH_FILE), List.of(
                "workdir=" + c.workDir,
                "gallium_driver=" + (llvmpipe ? GL_LLVMPIPE : ""),
                "lp_num_threads=" + (llvmpipe ? "1" : ""),
                "supervisor_pid=" + ProcessHandle.current().pid(),
                "java=" + java,
                "template=" + template,
                "config=" + config,
                "bind_source=" + (c.bindPortFromBox ? "box" : "config"),
                // the record row as read in either mode; blank only if the rows were never reached
                "record=" + (c.record == null ? "" : c.record.row()),
                // the replay-pins row as read (absent = on, the compiled default); never a seed
                "rngpin=" + (c.rngPins == null ? "" : c.rngPins)), out);
        if (!c.doorArmed) {
            say(out, DOOR_UNARMED_LINE);
        }

        // The box host whenever set; the port only from the box row (a config port the serve reads)
        Map<String, String> env = childEnv(inheritedEnv, opsStatus, opsCmd, llvmpipe,
                box.getOrDefault(ROW_BIND_HOST, "").trim(), c.bindPortFromBox ? String.valueOf(c.bindPort) : "", windows, pkg);
        ServeSupervisor supervisor = new ServeSupervisor(flight, stopFile, receipt, c.workDir, c.argv, env,
                MAX_RELAUNCHES, RELAUNCH_DELAY_MS, out, c.bindPort, timerApplier(box));
        Thread hook = new Thread(supervisor::requestStop, "serve-launcher-stop");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return supervisor.run();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                say(out, "serve-launcher: shutting down");
            }
        }
    }

    /**
     * The supervisor's timer opt-out for these launcher rows - a
     * {@link #ROW_TIMER_OPT_OUT} row reading off = {@link TimerOptOut#notAttempted()} (the receipt's rc is the
     * knob's own {@link TimerOptOut#NOT_ATTEMPTED}); any other row, or none = {@link TimerOptOut#PRODUCTION},
     * which still reads the {@value TimerOptOut#KNOB_PROPERTY} property at apply time. So property OR row = off.
     */
    static TimerOptOut.Applier timerApplier(Map<String, String> box) {
        return TimerOptOut.knobOff(box.get(ROW_TIMER_OPT_OUT)) ? TimerOptOut.notAttempted() : TimerOptOut.PRODUCTION;
    }

    /** What the pre-checks resolved, for the launch after them. */
    private static final class Checked {
        List<String> argv;
        Path workDir;
        boolean doorArmed;
        int bindPort;
        /** {@code bindPort} came from the {@code bind.port} BOX row (exported), not the config row. */
        boolean bindPortFromBox;
        PackageManifest manifest;
        Refusal manifestRefusal;
        /** The {@link #ROW_RECORD} mode as read (BOTH modes); null = not read yet. */
        ServeRecord.Mode record;
        /** The replay-pins row as read, {@code on|off}, for the
         * {@code launch.txt} receipt ({@link #rngPinsStamp}); null = the rows were never reached. */
        String rngPins;
    }

    /**
     * The replay-pins receipt word for {@code launch.txt}: {@code off} for a {@code false} row,
     * else {@code on} — an absent (or blank) row reads on because the compiled default is true
     * (the shipped template and both shells' prefill say the same). A RECEIPT of the file the serve
     * JVM will read, never a decision: no argument is composed from it, and the seed is not
     * stamped (it is minted inside the serve JVM, after this launcher has written the file).
     * Called only after {@link #checkRows} passed, so the row is blank, {@code true} or {@code false}.
     *
     */
    static String rngPinsStamp(Properties props) {
        return "false".equals(row(props, OpsMatchRows.RNG_PINS_KEY)) ? "off" : "on";
    }

    /** The pre-checks in the vocabulary's order; the FIRST failure is returned, null = all pass. */
    private static Refusal preChecks(Path pkg, Path config, Path template, List<String> templateLines,
            Map<String, String> box, Map<String, String> tokens, boolean windows, boolean llvmpipe, Path java,
            String gameRow, Path game, String nativeName, Map<String, String> inheritedEnv, Checked c,
            PrintStream out) {
        // template
        if (templateLines == null) {
            return Refusal.of(R_TEMPLATE_MISSING, "the launch template " + template + " does not exist or cannot be read");
        }
        if (templateLines.isEmpty()) {
            return Refusal.of(R_TEMPLATE_MISSING, "the launch template " + template + " has no argument lines");
        }
        try {
            c.argv = ArgvTemplate.substitute(templateLines, tokens);
        } catch (IllegalArgumentException bad) {
            return Refusal.of(R_TEMPLATE_TOKEN, "the launch template " + template + " cannot be used: " + bad.getMessage());
        }
        String leak = ArgvTemplate.leakingLine(templateLines);
        if (leak != null) {
            return Refusal.of(R_TEMPLATE_LEAK, "the launch template " + template
                    + " names a folder of the computer it was made on: " + bounded(leak));
        }
        // Linux only - a path ld.so / the classpath would split, FIRST
        // after the template (it needs nothing); the package dir is named before game.root
        if (!windows) {
            String unsafe = pathUnsafe(pkg.toString()) ? pkg.toString() : hasColon(gameRow) ? bounded(gameRow) : null;
            if (unsafe != null) {
                return Refusal.of(R_PATH_UNSAFE, "the folder " + unsafe + " has a space or a colon in its path (Linux"
                        + " splits library paths on both): move the package and the game to paths with neither");
            }
        }
        // java (+ the software-GL pair beside it on Windows; the exec bit a zip drops on Linux)
        if (java == null || !Files.isRegularFile(java)) {
            return Refusal.of(R_JAVA_MISSING, "no Java runtime at " + (java == null
                    ? "the folder named by " + ROW_JAVA_HOME + " (not a valid folder path)" : java.toString())
                    + " (set " + ROW_JAVA_HOME + " in the launcher properties, or ship the jre folder)");
        }
        // A non-exec runtime would pass isRegularFile and burn all six
        // lives at pb.start() (ServeSupervisor counts a start failure as a life) - refused here instead, Linux only
        if (!windows && !Files.isExecutable(java)) {
            return Refusal.of(R_JAVA_MISSING, "the Java runtime at " + java + " is not executable: run sh serve.sh (it"
                    + " restores the execute bits the zip drops)");
        }
        if (windows && llvmpipe) {
            for (String dll : MESA_DLLS) {
                Path p = java.resolveSibling(dll);
                if (!Files.isRegularFile(p)) {
                    return Refusal.of(R_MESA_MISSING, "software rendering needs " + p
                            + " beside the Java runtime (or set " + ROW_GL_DRIVER + "=" + GL_HARDWARE + ")");
                }
            }
        }
        // The INJECTED env, never System.getenv() (the rows' seam)
        String display = windows ? null : inheritedEnv.get("DISPLAY");
        if (!windows && (display == null || display.isBlank())) {
            return Refusal.of(R_DISPLAY_MISSING, "DISPLAY is not set: start the server with sh serve.sh (it starts a"
                    + " virtual screen)");
        }
        // game
        if (gameRow.isEmpty()) {
            return Refusal.of(R_GAME_ROOT, "the row " + ROW_GAME_ROOT
                    + " in the launcher properties is blank; set it to the game's install folder");
        }
        if (game == null) {
            return Refusal.of(R_GAME_ROOT, "the folder named by " + ROW_GAME_ROOT + " (" + bounded(gameRow)
                    + ") does not hold " + CORE_JAR + ", directly or in " + CORE_DIR);
        }
        // Windows only - on Linux the game's native/linux is OPTIONAL (the package's
        // two .so come first on the library path and are guarded by the MANIFEST step: a missing one = manifest_mismatch)
        Path nativeDir = game.resolve("native").resolve(nativeName);
        if (windows && !Files.isDirectory(nativeDir)) {
            return Refusal.of(R_GAME_ROOT, "the game folder " + game + " has no native libraries folder " + nativeDir);
        }
        try {
            c.workDir = game.toRealPath();
        } catch (IOException | RuntimeException e) {
            return Refusal.of(R_GAME_ROOT, "the game folder " + game + " cannot be resolved (" + e.getClass().getSimpleName() + ")");
        }
        for (String line : templateLines) {
            Matcher m = GAME_JAR.matcher(line);
            while (m.find()) {
                Path jar = game.resolve(m.group(1)).normalize();
                if (!jar.startsWith(game) || !Files.isRegularFile(jar)) {
                    return Refusal.of(R_GAME_JAR_MISSING, "the game jar " + jar + " named by the launch template is missing");
                }
            }
        }
        // config + the D4 unknown-key warnings
        if (!Files.isRegularFile(config)) {
            return Refusal.of(R_CONFIG_MISSING, "the server config " + config + " does not exist");
        }
        Properties props = new Properties();
        try {
            if (Files.size(config) > MAX_CONFIG_BYTES) {
                return Refusal.of(R_CONFIG_MISSING, "the server config " + config + " is larger than " + MAX_CONFIG_BYTES + " bytes");
            }
            try (InputStream in = Files.newInputStream(config)) {
                props.load(in);
            }
        } catch (IOException | RuntimeException e) {
            return Refusal.of(R_CONFIG_MISSING, "the server config " + config + " cannot be read (" + e.getClass().getSimpleName() + ")");
        }
        warnUnknownKeys(props, config, out);
        // descriptor
        String descriptorRow = row(props, "match.descriptor");
        if (descriptorRow.isEmpty()) {
            return Refusal.of(R_DESCRIPTOR_ROW, "the server config " + config + " has no match.descriptor row");
        }
        Path descriptor;
        try {
            Path p = Path.of(descriptorRow);
            Path configDir = config.getParent();
            descriptor = (p.isAbsolute() || configDir == null ? p : configDir.resolve(p)).toAbsolutePath().normalize();
        } catch (InvalidPathException bad) {
            return Refusal.of(R_DESCRIPTOR_ROW, "the match.descriptor row is not a valid path: " + bounded(descriptorRow));
        }
        if (!Files.isRegularFile(descriptor)) {
            return Refusal.of(R_DESCRIPTOR_MISSING, "the match descriptor " + descriptor + " does not exist");
        }
        // the password sidecar: present = parsed by the serve's own grammar (a bad file throws at the
        // serve's open, ~50 s in); absent + a REDACTED descriptor = a serve nobody can join (requireLiveTokensInTokenMode)
        Path password = Path.of(descriptor + PASSWORD_SUFFIX);
        String matchPassword = null;
        if (Files.exists(password)) {
            try {
                matchPassword = MatchPasswords.parseSidecarPassword(Files.readAllLines(password), password.toString());
            } catch (IOException | RuntimeException bad) {
                return Refusal.of(R_ROW_PARSE, "the password file " + password + " cannot be used: "
                        + (bad instanceof IllegalArgumentException ? bad.getMessage() : bad.getClass().getSimpleName()));
            }
        } else if (descriptorIsRedacted(descriptor)) {
            return Refusal.of(R_PASSWORD_MISSING, "the match descriptor " + descriptor + " is the public copy (its seat"
                    + " tokens are placeholders), so this server admits players by password only: write one line,"
                    + " password=<8 to 64 characters>, into the file " + password);
        }
        // the OPTIONAL admin sidecar: the serve's own mintAdminPassword laws
        // (PersistentPilotIntentCombatServer), checked here so a bad admin file costs one console line, not six
        // ~50 s lives - present = the admin grammar; present with no match password, or equal to it, = the serve
        // would throw at its open. No message echoes a password (MatchPasswords' own law).
        Path admin = Path.of(descriptor + MatchPasswords.ADMIN_SIDECAR_SUFFIX);
        if (Files.exists(admin)) {
            String adminPassword;
            try {
                adminPassword = MatchPasswords.parseAdminSidecarPassword(Files.readAllLines(admin), admin.toString());
            } catch (IOException | RuntimeException bad) {
                return Refusal.of(R_ROW_PARSE, "the admin password file " + admin + " cannot be used: "
                        + (bad instanceof IllegalArgumentException ? bad.getMessage() : bad.getClass().getSimpleName()));
            }
            if (matchPassword == null) {
                return Refusal.of(R_ADMIN_SIDECAR, "the admin password file " + admin + " is present but there is no"
                        + " match password file " + password + " - the admin credential rides the match-password"
                        + " slot: write a match password, or delete the admin file");
            }
            if (MatchPasswords.constantTimeEquals(adminPassword, matchPassword)) {
                return Refusal.of(R_ADMIN_SIDECAR, "the admin password file " + admin + " holds the same password as "
                        + password + " - every player would be admin; choose a different admin password");
            }
        }
        // Password mode serves the match document to every join window,
        // so the serve's own decode + the document preflight run here over the descriptor FILE - one sentence now
        // instead of failed lives later. A PACKED server (pack.stamp set; the memoized manifest read, so no refusal
        // order changes) gets the package tail; owner mode names the file. Token mode serves no document: skipped.
        if (matchPassword != null) {
            MatchDocumentRefused unservable = matchDocRefusal(descriptor);
            if (unservable != null) {
                String sentence = MATCH_DOC_SENTENCE.replace("<reason>", unservable.reason())
                        .replace("<field>", unservable.field());
                String stamp = loadManifest(pkg, c) == null ? c.manifest.meta.get(OpsRoots.PACK_STAMP) : null;
                if (stamp != null && !stamp.isBlank()) {
                    return Refusal.of(R_MATCH_DOC, sentence + " " + MATCH_DOC_PACKAGE_TAIL);
                }
                return Refusal.of(R_MATCH_DOC, sentence + " (the match file " + descriptor + ")");
            }
        }
        // the armed door's sidecar: exists, jar= parses, jar= == the manifest's kit.jar.md5
        c.doorArmed = "true".equals(row(props, "net.doorFingerprint"));
        if (c.doorArmed) {
            Path sidecar = Path.of(descriptor + ".fingerprint");
            if (!Files.isRegularFile(sidecar)) {
                // A PACKED server (pack.stamp set; the memoized
                // manifest read, so no refusal order changes) gets the T5_SIDECAR_MISSING_PACKAGE tail - never owner mode's door-off advice
                String stamp = loadManifest(pkg, c) == null ? c.manifest.meta.get(OpsRoots.PACK_STAMP) : null;
                if (stamp != null && !stamp.isBlank()) {
                    return Refusal.of(R_SIDECAR_MISSING, "the fingerprint file " + sidecar + " does not exist. "
                            + T5_SIDECAR_MISSING_PACKAGE);
                }
                return Refusal.of(R_SIDECAR_MISSING, "net.doorFingerprint=true but the fingerprint file " + sidecar
                        + " does not exist (set net.doorFingerprint=false to run without the door check)");
            }
            String jar;
            try {
                String composite = ContentFingerprints.parseSidecarFingerprint(
                        Files.readAllLines(sidecar, StandardCharsets.UTF_8), sidecar.toString());
                jar = ContentFingerprints.componentOrNull(composite, "jar");
            } catch (IOException | RuntimeException bad) {
                jar = null;
            }
            if (jar == null || jar.isBlank()) {
                return Refusal.of(R_SIDECAR_JAR, "the fingerprint file " + sidecar + " carries no readable jar= value");
            }
            Refusal manifestProblem = loadManifest(pkg, c);
            if (manifestProblem != null) {
                return manifestProblem;
            }
            String expected = c.manifest.meta.get(PackageManifest.KIT_JAR_MD5);
            if (expected == null) {
                return Refusal.of(R_MANIFEST_MISSING, "the package manifest " + pkg.resolve(MANIFEST_FILE)
                        + " has no " + PackageManifest.KIT_JAR_MD5 + " row to check the fingerprint file against");
            }
            // case-SENSITIVE: the door compares composites byte-for-byte (DoorFingerprintExpectations), so an
            // upper-case sidecar would bounce every joiner — it IS stale here (the pwsh twin's -ine was looser)
            if (!expected.equals(jar.trim())) {
                // A PACKED server's line ends with the per-file remedy -
                // a RELEASE stamp only: the remedy's "copied back" is the first-start repair, a no-op on a source stamp
                String stamp = c.manifest.meta.get(OpsRoots.PACK_STAMP);
                return Refusal.of(R_SIDECAR_STALE, "the fingerprint file " + sidecar + " was made for another client"
                        + " build (jar=" + jar.trim() + ", this package's client is " + expected
                        + "); every player would be turned away at the door"
                        + (PackageRepair.packedStamp(stamp)
                                ? SIDECAR_STALE_PACKAGE_TAIL.replace("<match>", hostRel(pkg, descriptor, windows)) : ""));
            }
            // The PAIR - a sidecar keyed to another
            // match text than the descriptor's; a consistent pair (every owner and package write) passes unchanged
            if (sidecarPairSplit(descriptor, sidecar, out)) {
                return Refusal.of(R_SIDECAR_PAIR, SIDECAR_PAIR_SENTENCE.replace("<path>", sidecar.toString()));
            }
        }
        // behaviour rows, with the serve's own parsers
        Refusal rowProblem = checkRows(props, box);
        if (rowProblem != null) {
            return rowProblem;
        }
        c.rngPins = rngPinsStamp(props);
        // Port: a non-blank bind.port BOX row WINS (an
        // invalid one refuses even beside a valid config row); a blank one leaves the config row's rule unchanged
        String boxPort = box.getOrDefault(ROW_BIND_PORT, "").trim();
        c.bindPortFromBox = !boxPort.isEmpty();
        String port = c.bindPortFromBox ? boxPort : row(props, "server.bindPort");
        int portValue;
        try {
            portValue = port.isEmpty() ? 0 : Integer.parseInt(port);
        } catch (NumberFormatException bad) {
            portValue = -1;
        }
        if (portValue <= 0 || portValue > 65535) {
            return Refusal.of(R_BIND_PORT, (c.bindPortFromBox ? "the row " + ROW_BIND_PORT + " in the launcher properties"
                    : "server.bindPort") + " must be a fixed port from 1 to 65535 (got '"
                    + bounded(port) + "'); a restarted server must come back on the same port");
        }
        c.bindPort = portValue;
        // manifest
        Refusal manifestProblem = loadManifest(pkg, c);
        if (manifestProblem != null) {
            return manifestProblem;
        }
        String packStamp = c.manifest.meta.get(OpsRoots.PACK_STAMP);
        boolean packed = packStamp != null && !packStamp.isBlank();
        String mismatch = PackageManifest.firstMismatch(pkg, c.manifest);
        if (mismatch != null) {
            // A PACKED server names the update script as the remedy - a
            // RELEASE stamp only (the update check refuses a source-built folder, which is rebuilt instead); the mods
            // step below keeps the wider "any stamp" reading, which a source folder's rows rely on
            String remedy = PackageRepair.packedStamp(packStamp)
                    ? MANIFEST_MISMATCH_PACKAGE_TAIL.replace("<script>", windows ? "update.bat" : "sh update.sh")
                    : packed && packStamp.startsWith(PackageUpdateCheck.SOURCE_STAMP_PREFIX)
                            ? " - rebuild it with build.ps1 (this folder was built from source)"
                            : " (re-extract the package)";
            return new Refusal(R_MANIFEST_MISMATCH, "the package does not match its manifest: " + mismatch + remedy,
                    EXIT_MANIFEST_MISMATCH);
        }
        // mods: on a PACKED server (the packer's pack.stamp row; owner mode's
        // stage library already mirrored its mods and re-minted the manifest over them) the host's Mods rows are
        // honoured HERE, after the integrity check and before life 1: the named mods are copied into
        // serve-mods/ and enabled_mods.json is written (PackageModSync). Nothing is deleted.
        if (packed) {
            // The rider - one warning per SET inert row
            if (!row(props, OpsMatchApply.MAP_HALF_KEY).isEmpty()) {
                say(out, "serve-launcher warning: " + RIDER_MAP_HALF);
            }
            if (!row(props, "match.deployCr").isEmpty()) {
                say(out, "serve-launcher warning: " + RIDER_DEPLOY_CR);
            }
        }
        // The record rows apply in BOTH modes. MINT
        // extracts the owner-mode template's five record lines into rows (record=full + record.maxMiB=<cap>; an owner-mode
        // serve keeps them as a SET), so an owner-mode stage minted after that reads them HERE or records
        // nothing; only the disk floor + the net.sinkPath rider stay package-only (the package-only limit covers the REFUSAL, not the lines).
        Refusal recordProblem = recordRows(pkg, template, templateLines, box, tokens, props, c, out, packed);
        if (recordProblem != null) {
            return recordProblem;
        }
        if (packed) {
            String rootsRow = modsRootsRowOrNull(config, out);
            try {
                for (String line : PackageModSync.sync(pkg, row(props, OpsMatchRows.MODS_ENABLED_KEY),
                        row(props, "mods.optional"), rootsRow)) {
                    say(out, "serve-launcher mods: " + line);
                }
            } catch (IllegalArgumentException refused) {
                return Refusal.of(R_MODS, "mods: " + refused.getMessage());
            } catch (IOException | RuntimeException copyFailed) {
                return Refusal.of(R_MODS, "mods: copying a mod into " + pkg.resolve(PackageModSync.MODS_DIR)
                        + " failed (" + copyFailed.getClass().getSimpleName() + ": " + copyFailed.getMessage() + ")");
            }
        }
        return null;
    }

    /**
     * The recording rows, BOTH modes (an owner stage minted after MINT's record-line
     * extraction serves its five lines through these rows), after the manifest check and before the Mods copy (so a
     * refusal writes nothing): the config {@code net.sinkPath} rider (one warning, never a refusal; {@code packed}
     * only); {@link #ROW_RECORD} /
     * {@link #ROW_RECORD_MAX_MIB} (read for {@code full} only) parsed by {@link ServeRecord} ({@link #R_RECORD_ROW}); the record lines inserted
     * before the template's main class and the argv re-substituted from them, so {@code ${RUN}} names this start's
     * flight ({@code record=off} = the template itself, {@code c.argv} untouched); then, {@code record=full} in a
     * PACKED server only (the owner's own launch path never refuses on disk space), the disk floor
     * ({@link #R_RECORD_DISK}) on the usable space of {@code <pkg>/run} through {@link #diskFree} (an unreadable
     * store warns once and the start goes on). Sets {@code c.record}.
     *
     *
     */
    private static Refusal recordRows(Path pkg, Path template, List<String> templateLines, Map<String, String> box,
            Map<String, String> tokens, Properties props, Checked c, PrintStream out, boolean packed) {
        if (packed && !row(props, "net.sinkPath").isEmpty()) {
            say(out, "serve-launcher warning: " + RIDER_SINK_PATH);
        }
        ServeRecord.Mode mode;
        int cap;
        List<String> recorded;
        try {
            mode = ServeRecord.parse(box.get(ROW_RECORD));
            // only full reads the cap (ServeRecord.lines): a stale cap row beside off / diag never blocks a start
            cap = mode == ServeRecord.Mode.FULL ? ServeRecord.capMiB(box.get(ROW_RECORD_MAX_MIB))
                    : ServeRecord.CAP_DEFAULT_MIB;
            recorded = ServeRecord.withLines(templateLines, mode, cap, template);
        } catch (IllegalArgumentException refused) {
            return Refusal.of(R_RECORD_ROW, String.valueOf(refused.getMessage()));
        }
        c.record = mode;
        if (mode == ServeRecord.Mode.OFF) {
            return null;
        }
        try {
            c.argv = ArgvTemplate.substitute(recorded, tokens);
        } catch (IllegalArgumentException bad) {
            return Refusal.of(R_TEMPLATE_TOKEN, "the launch template " + template + " cannot be used: " + bad.getMessage());
        }
        if (mode != ServeRecord.Mode.FULL || !packed) {
            return null;
        }
        Path run = pkg.resolve(RUN_DIR);
        long freeMiB;
        try {
            freeMiB = Math.max(0L, diskFree.usableBytes(run)) / (1024L * 1024L);
        } catch (IOException | RuntimeException unreadable) {
            say(out, "serve-launcher warning: the free space of " + run + " could not be read ("
                    + unreadable.getClass().getSimpleName() + "); recording goes on unchecked");
            return null;
        }
        if (freeMiB < ServeRecord.diskFloorMiB(cap)) {
            return Refusal.of(R_RECORD_DISK, ServeRecord.diskText(run, freeMiB, cap));
        }
        return null;
    }

    /**
     * Whether the descriptor FILE carries a {@code seat.N.token=REDACTED-*} row — the mark of the kit's public copy
     * ({@link MatchDescriptor#isRedactedToken}). A textual read of the codec's row shape:
     * the launcher decodes a descriptor only ONCE, in password mode, for the match-document preflight
     * ({@link #matchDocRefusal}, with the serve's own codec) - this read stays textual; unreadable / oversized = not
     * redacted (the serve rules on it).
     */
    private static boolean descriptorIsRedacted(Path descriptor) {
        try {
            if (Files.size(descriptor) > MAX_CONFIG_BYTES) {
                return false;
            }
            for (String line : Files.readAllLines(descriptor, StandardCharsets.UTF_8)) {
                Matcher m = SEAT_TOKEN_ROW.matcher(line.trim());
                if (m.matches() && MatchDescriptor.isRedactedToken(m.group(1).trim())) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
        return false;
    }

    /**
     * The {@link #R_MATCH_DOC} check: null = the document is servable, else the refusal (reason + OUR field). The
     * FILE is read the way the serve's boot reads it (capped at {@link #MAX_CONFIG_BYTES}, UTF-8 lines) and decoded
     * with the serve's own codec, then {@link MatchDocPreflight#prepare} judges the bytes the door would serve. An
     * over-cap file = {@code (oversize, file)}, an unreadable one {@code (malformed, file)}; a codec or preflight
     * refusal keeps its own reason + field; any OTHER decode / preflight failure = {@code (malformed, decode)} - never
     * its message, which may quote a descriptor line (the no-echo law).
     *
     *
     */
    private static MatchDocumentRefused matchDocRefusal(Path descriptor) {
        List<String> lines;
        try {
            if (Files.size(descriptor) > MAX_CONFIG_BYTES) {
                return new MatchDocumentRefused(MatchDocumentLaws.REASON_OVERSIZE, "file");
            }
            lines = new String(Files.readAllBytes(descriptor), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException | RuntimeException unreadable) {
            return new MatchDocumentRefused(MatchDocumentLaws.REASON_MALFORMED, "file");
        }
        try {
            MatchDocPreflight.prepare(MatchDescriptorCodec.decode(lines));
            return null;
        } catch (MatchDocumentRefused refused) {
            return refused;
        } catch (RuntimeException undecodable) {
            return new MatchDocumentRefused(MatchDocumentLaws.REASON_MALFORMED, "decode");
        }
    }

    /**
     * Whether the pair is SPLIT ({@link #R_SIDECAR_PAIR}): the descriptor's LAST {@code match.id=} row (a textual
     * read like {@link #descriptorIsRedacted}, each file capped at {@link #MAX_CONFIG_BYTES}) differs from the
     * {@code match=} component of the sidecar's {@code fingerprint=} composite. Only a pair with BOTH ids read and
     * different is split; an unreadable / oversized file answers false with one warning line naming the exception
     * class, a missing id or component answers false silently (the serve's own codec and the door rule on such a
     * file, as before this check).
     *
     *
     */
    static boolean sidecarPairSplit(Path descriptor, Path sidecar, PrintStream out) {
        try {
            if (Files.size(descriptor) > MAX_CONFIG_BYTES || Files.size(sidecar) > MAX_CONFIG_BYTES) {
                throw new IOException("over the " + MAX_CONFIG_BYTES + " B cap");
            }
            String id = null;
            for (String line : Files.readAllLines(descriptor, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("match.id=")) {
                    id = t.substring("match.id=".length()).trim();
                }
            }
            String keyed = ContentFingerprints.componentOrNull(ContentFingerprints.parseSidecarFingerprint(
                    Files.readAllLines(sidecar, StandardCharsets.UTF_8), sidecar.toString()), "match");
            return id != null && keyed != null && !id.equals(keyed.trim());
        } catch (IOException | RuntimeException unreadable) {
            say(out, "serve-launcher warning: the match-file pair check could not read " + descriptor.getFileName()
                    + " / its fingerprint file (" + unreadable.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /** Reads {@code <pkg>/MANIFEST.txt} once; absent, unreadable or malformed = {@code manifest_missing}. */
    private static Refusal loadManifest(Path pkg, Checked c) {
        if (c.manifest != null || c.manifestRefusal != null) {
            return c.manifestRefusal;
        }
        Path manifest = pkg.resolve(MANIFEST_FILE);
        try {
            c.manifest = PackageManifest.read(manifest);
        } catch (NoSuchFileException absent) {
            c.manifestRefusal = Refusal.of(R_MANIFEST_MISSING, "the package manifest " + manifest + " does not exist");
        } catch (IOException | RuntimeException bad) {
            c.manifestRefusal = Refusal.of(R_MANIFEST_MISSING, "the package manifest " + manifest
                    + " cannot be used: " + (bad instanceof IllegalArgumentException
                            ? bad.getMessage() : bad.getClass().getSimpleName()));
        }
        return c.manifestRefusal;
    }

    /**
     * The behaviour rows the serve parses only after its engine boot, parsed here by the SAME
     * pure parsers: {@code sim.tickRate} ({@link SimTickRate#parse}), {@code match.respawnPolicy}
     * at that rate ({@link MatchRespawnPolicy#parse(String, long)}), {@code match.fortressDp} in
     * [0, 5000], {@code moderation.maxSeatsPerSource} in [0, 255], and the booleans —
     * {@code net.doorFingerprint} + {@code server.engineBornGrowth} + {@code server.officerSkillsAll}
     * by the serve's exact {@code "true"|"false"} switch (PersistentPilotIntentCombatServer
     * .mintDoorFingerprint, PilotServeResolver's engineBornGrowth read: {@code TRUE} or {@code yes}
     * THROWS there), the other three by {@code ServerConfig.resolveBoolean}'s set. A blank row = absent
     * (the file tier skips blanks). Plus the launcher's own heap / compiler-count / GL / bind-host rows.
     */
    private static Refusal checkRows(Properties props, Map<String, String> box) {
        String rate = row(props, "sim.tickRate");
        int ticks;
        try {
            ticks = SimTickRate.parse(rate);
        } catch (IllegalArgumentException bad) {
            return rowRefusal("sim.tickRate", bad.getMessage());
        }
        String respawn = row(props, "match.respawnPolicy");
        if (!respawn.isEmpty()) {
            try {
                MatchRespawnPolicy.parse(respawn, ticks);
            } catch (IllegalArgumentException bad) {
                return rowRefusal("match.respawnPolicy", bad.getMessage());
            }
        }
        String fortress = row(props, "match.fortressDp");
        if (!fortress.isEmpty()) {
            int dp;
            try {
                dp = Integer.parseInt(fortress);
            } catch (NumberFormatException bad) {
                dp = -1;
            }
            if (dp < 0 || dp > 5000) {
                return rowRefusal("match.fortressDp", "must be a whole number from 0 to 5000, got '" + fortress + "'");
            }
        }
        // The join door's per-address seat allowance. The
        // registry's strict posture passes any int, so the range is checked HERE: 1..3 digits,
        // 0 (no limit) .. the match seat cap; anything else would boot a door with a cap the host never meant.
        String seatsPerSource = row(props, OpsMatchRows.SEATS_PER_SOURCE_KEY);
        if (!seatsPerSource.isEmpty() && !seatsPerSourceRowOk(seatsPerSource)) {
            return rowRefusal(OpsMatchRows.SEATS_PER_SOURCE_KEY, "must be a whole number from 0 to "
                    + OpsMatchRows.SEATS_PER_SOURCE_MAX + ", got '" + seatsPerSource + "'");
        }
        // server.officerSkillsAll joins the STRICT pair: its one serve reader (AllSkillsAttach) is the
        // exact "true"|"false" switch, so a loose "on" would pass here and stop the serve at boot.
        // The replay-pins row joins it for the same reason - its
        // one reader (the serve JVM's startup) is the exact switch; the launcher only validates.
        for (String key : List.of("net.doorFingerprint", "server.engineBornGrowth", OpsMatchRows.ALL_SKILLS_KEY,
                OpsMatchRows.RNG_PINS_KEY)) {
            String v = row(props, key);
            if (!v.isEmpty() && !v.equals("true") && !v.equals("false")) {
                return rowRefusal(key, "must be true or false, got '" + v + "'");
            }
        }
        for (String key : List.of("net.socketLiveness", "net.snapDelta", "predict.eventReplay")) {
            String v = row(props, key);
            if (!v.isEmpty() && !looseBoolean(v)) {
                return rowRefusal(key, "must be true/false/1/0/yes/no/on/off, got '" + v + "'");
            }
        }
        for (String key : List.of(ROW_HEAP_MIN, ROW_HEAP_MAX)) {
            String v = box.getOrDefault(key, "").trim();
            if (!v.isEmpty() && !HEAP.matcher(v).matches()) {
                return rowRefusal(key, "must be a memory size like 2g or 1024m, got '" + v + "'");
            }
        }
        String ci = box.getOrDefault(ROW_CI_COUNT, "").trim();
        if (!ci.isEmpty() && !CI_COUNT.matcher(ci).matches()) {
            return rowRefusal(ROW_CI_COUNT, "must be blank or a whole number, got '" + ci + "'");
        }
        String gl = box.getOrDefault(ROW_GL_DRIVER, "").trim();
        if (!gl.isEmpty() && !gl.equals(GL_LLVMPIPE) && !gl.equals(GL_HARDWARE)) {
            return rowRefusal(ROW_GL_DRIVER, "must be " + GL_LLVMPIPE + " or " + GL_HARDWARE + ", got '" + gl + "'");
        }
        // The box host rides the child env verbatim
        String host = box.getOrDefault(ROW_BIND_HOST, "").trim();
        if (!host.isEmpty() && !BIND_HOST.matcher(host).matches()) {
            return rowRefusal(ROW_BIND_HOST, "must be blank or one address with no spaces (like 0.0.0.0), got '"
                    + host + "'");
        }
        return null;
    }

    /** The seat-allowance row grammar: 1..3 ASCII digits, value 0..{@link OpsMatchRows#SEATS_PER_SOURCE_MAX}
     * (the Start form's own {@link OpsMatchRows#parseSeatsPerSource} window, so a value the window
     * writes is never one this pre-check refuses). */
    private static boolean seatsPerSourceRowOk(String v) {
        return v.length() <= 3 && v.chars().allMatch(c -> c >= '0' && c <= '9')
                && Integer.parseInt(v) <= OpsMatchRows.SEATS_PER_SOURCE_MAX;
    }

    /**
     * The config-row pre-check as a pure function over a config's rows (the launcher box rows
     * empty): {@code null} when every behaviour row parses, else the refusal sentence the launcher
     * prints. WHY public: the contract rows drive the SAME validator {@link #run} runs, never a copy
     * of its grammar.
     */
    public static String configRowProblemOrNull(Properties props) {
        Refusal r = checkRows(props, Map.of());
        return r == null ? null : r.sentence();
    }

    private static Refusal rowRefusal(String key, String message) {
        return Refusal.of(R_ROW_PARSE, "the row " + key + " does not parse: " + bounded(String.valueOf(message)));
    }

    /** {@code ServerConfig.resolveBoolean}'s accepted set: true 1 yes on / false 0 no off, case-insensitive. */
    private static boolean looseBoolean(String v) {
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on")
                || v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("no") || v.equalsIgnoreCase("off");
    }

    /** A config row as the serve's file tier reads it: trimmed, blank = absent ({@code ""}). */
    private static String row(Properties props, String key) {
        String v = props.getProperty(key);
        return v == null ? "" : v.trim();
    }

    /** A config row trimmed, or null when the key is ABSENT (blank stays {@code ""}): the Mods roots row, where blank
     *  = search only {@code serve-mods/} and absent = the package defaults ({@link PackageModSync#plan}); the suite's
     *  T11 census reads this shape as a row read like {@link #row}. */
    private static String rowOrNull(Properties props, String key) {
        String v = props.getProperty(key);
        return v == null ? null : v.trim();
    }

    /**
     * D4: ONE warning line per config key the serve does not read (not a FILE key of
     * {@link PostureRegistry#knobs()}, not {@link #UNREGISTERED_KNOWN_KEYS}), sorted — never a
     * refusal. WHY: the serve's loader ignores a mistyped key silently, so the host's intended
     * setting would never apply.
     */
    private static void warnUnknownKeys(Properties props, Path config, PrintStream out) {
        Set<String> known = new TreeSet<>();
        for (PostureRegistry.Knob k : PostureRegistry.knobs()) {
            if (k.fileKey() != null) {
                known.add(k.fileKey());
            }
        }
        known.addAll(UNREGISTERED_KNOWN_KEYS);
        for (String key : new TreeSet<>(props.stringPropertyNames())) {
            if (!known.contains(key)) {
                say(out, "serve-launcher warning: the server config " + config.getFileName() + " has a row '"
                        + bounded(key) + "' that this server does not read; it is ignored");
            }
        }
    }

    /**
     * The child's environment, built from scratch: ONLY {@link #ENV_KEEP} from
     * {@code inherited} (name matched case-insensitively, the inherited spelling kept — Windows
     * hands {@code Path}), every {@code CC_*} dropped, then the two ops files as ABSOLUTE paths
     * under their registered env twins (the posture census rows for {@code cc.server.opsFile} /
     * {@code cc.server.commandFile}; what {@code run-ops-serve.ps1}'s {@code $extraEnv} sets) and,
     * under software GL, {@code GALLIUM_DRIVER=llvmpipe} + {@code LP_NUM_THREADS=1}. A non-blank
     * {@code bindHost} / {@code bindPort} (the {@code bind.host} / {@code bind.port} BOX rows)
     * rides its registered env twin ({@code CC_SERVER_BIND_HOST} / {@code CC_SERVER_BIND_PORT}),
     * put AFTER the {@code CC_*} drop so an inherited twin never wins; the serve's env tier outranks its
     * config file. Blank = not exported (the serve reads the config rows); nothing else is ferried.
     *
     *
     *
     * <p>On {@code windows} the map above is the WHOLE map (byte-identical to before the Linux
     * branch). On Linux it also keeps {@link #ENV_KEEP_LINUX} when inherited and SETS {@link #ENV_LD_PRELOAD} to
     * {@code <pkg>/native/linux/libjawt.so} ABSOLUTE whatever was inherited (an inherited value is dropped like any
     * unlisted row); {@code LD_LIBRARY_PATH} / {@code LD_AUDIT} / {@code JAVA_TOOL_OPTIONS} stay dropped.
     *
     */
    static Map<String, String> childEnv(Map<String, String> inherited, Path opsStatus, Path opsCmd, boolean llvmpipe,
            String bindHost, String bindPort, boolean windows, Path pkg) {
        Map<String, String> env = new TreeMap<>();
        for (Map.Entry<String, String> e : inherited.entrySet()) {
            String k = e.getKey();
            if (k == null || e.getValue() == null || k.regionMatches(true, 0, "CC_", 0, 3)) {
                continue;
            }
            for (String keep : ENV_KEEP) {
                if (keep.equalsIgnoreCase(k)) {
                    env.put(k, e.getValue());
                    break;
                }
            }
            if (!windows && ENV_KEEP_LINUX.contains(k)) {
                env.put(k, e.getValue());
            }
        }
        if (!windows) {
            env.put(ENV_LD_PRELOAD,
                    pkg.resolve("native").resolve("linux").resolve("libjawt.so").toAbsolutePath().toString());
        }
        env.put(registeredEnv("cc.server.opsFile"), opsStatus.toAbsolutePath().toString());
        env.put(registeredEnv("cc.server.commandFile"), opsCmd.toAbsolutePath().toString());
        if (bindHost != null && !bindHost.isBlank()) {
            env.put(registeredEnv("cc.server.bindHost"), bindHost.trim());
        }
        if (bindPort != null && !bindPort.isBlank()) {
            env.put(registeredEnv("cc.server.bindPort"), bindPort.trim());
        }
        if (llvmpipe) {
            env.put("GALLIUM_DRIVER", GL_LLVMPIPE);
            env.put("LP_NUM_THREADS", "1");
        }
        return env;
    }

    /**
     * Whether a package path would split on Linux: any whitespace or {@code ':'} once ONE leading drive prefix
     * ({@code [A-Za-z]:}) is stripped. ld.so splits {@code LD_PRELOAD} (the package's shim path) on both; a colon also
     * splits the classpath and the library path. WHY the strip: a Linux {@code toAbsolutePath()} is
     * {@code /...} and never carries one, while every Windows temp root the injected rows build does — without it an
     * injected {@code windows=false} run would refuse here before reaching its own step.
     *
     *
     */
    static boolean pathUnsafe(String path) {
        String p = stripDrive(path);
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) == ':' || Character.isWhitespace(p.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** {@code game.root}'s half: only a colon counts (a space is legal — argv is never shell-split, nor the library path). */
    static boolean hasColon(String path) {
        return stripDrive(path).indexOf(':') >= 0;
    }

    private static String stripDrive(String path) {
        boolean drive = path.length() >= 2 && path.charAt(1) == ':'
                && ((path.charAt(0) >= 'A' && path.charAt(0) <= 'Z') || (path.charAt(0) >= 'a' && path.charAt(0) <= 'z'));
        return drive ? path.substring(2) : path;
    }

    /** The env twin the posture census registers for {@code key}; a missing row is a code bug (throws). */
    private static String registeredEnv(String key) {
        for (PostureRegistry.Knob k : PostureRegistry.knobs()) {
            if (k.key().equals(key) && k.env() != null) {
                return k.env();
            }
        }
        throw new IllegalStateException("posture census has no env twin for " + key);
    }

    /**
     * The pid of a serve still WRITING {@code run/ops/ops-status.txt}, else 0. WHY:
     * the lock refuses a second LAUNCHER, but an orphaned serve (its launcher gone: a forced kill skips the shutdown
     * hook) keeps writing this file, and the stale-file wipe after the lock would erase it and boot a
     * twin on the same port. The rule = {@code ServerOpsCore.writerAlive}'s, inlined (no console-shell import: the T9
     * census): the pid alive AND its start instant at or before {@code stamp_ms} (a recycled pid started after the
     * stamp is not the writer; an unreadable instant = alive); NO freshness window. The read = the raw first
     * {@code pid=} / {@code stamp_ms=} lines of the first {@value #MAX_STATUS_SCAN_BYTES} bytes (the pwsh guard's
     * scan, {@code run-ops-serve.ps1}), never {@code OpsSnapshot.parse}: it throws with RAW row text (seat cidkey /
     * endpoint rows) that would reach the console. Absent, row-less, unparseable or a pid of 0 = no live writer; an
     * unreadable file warns once and reads the same. Only the pid ever reaches the console.
     *
     *
     */
    static long liveStatusWriter(Path opsStatus, PrintStream out) {
        if (!Files.isRegularFile(opsStatus)) {
            return 0L;
        }
        byte[] head;
        try (InputStream in = Files.newInputStream(opsStatus)) {
            head = in.readNBytes(MAX_STATUS_SCAN_BYTES);
        } catch (IOException | RuntimeException e) {
            say(out, "serve-launcher warning: " + opsStatus + " could not be read (" + e.getClass().getSimpleName()
                    + "); no running server is assumed");
            return 0L;
        }
        String text = new String(head, StandardCharsets.US_ASCII);
        if (head.length == MAX_STATUS_SCAN_BYTES) {
            text = text.substring(0, text.lastIndexOf('\n') + 1); // a line cut at the bound is not a row
        }
        long pid = -1L;
        long stamp = -1L;
        for (String line : text.split("\r?\n|\r")) {
            Matcher m;
            if (pid < 0L && (m = STATUS_PID.matcher(line)).matches()) {
                pid = Long.parseLong(m.group(1));
            } else if (stamp < 0L && (m = STATUS_STAMP.matcher(line)).matches()) {
                stamp = Long.parseLong(m.group(1));
            }
        }
        if (pid <= 0L || stamp < 0L) {
            return 0L;
        }
        long stampMs = stamp;
        boolean alive = ProcessHandle.of(pid).map(h -> h.isAlive()
                && h.info().startInstant().map(t -> t.toEpochMilli() <= stampMs).orElse(true)).orElse(false);
        return alive ? pid : 0L;
    }

    /**
     * A launcher row as a path under {@code pkg} (an absolute row stands); a row the platform cannot parse
     * (quotes, {@code <>|?*} on Windows) = null, never a throw: the JVM's own
     * {@code InvalidPathException} once escaped {@code run()} as a stack trace + exit 1, the budget code.
     */
    static Path resolveRow(Path pkg, String row) {
        try {
            return pkg.resolve(row).normalize();
        } catch (InvalidPathException bad) {
            return null;
        }
    }

    /** D2: whichever of {@code root} and {@code root/starsector-core} holds the core jar; neither = null. */
    static Path resolveGame(Path root) {
        if (Files.isRegularFile(root.resolve(CORE_JAR))) {
            return root;
        }
        Path core = root.resolve(CORE_DIR);
        return Files.isRegularFile(core.resolve(CORE_JAR)) ? core : null;
    }

    /**
     * {@code launcher.properties} as plain {@code key=value} lines ({@code #} / {@code !} comments,
     * blanks skipped, key and value trimmed, the FIRST {@code =} splits), UTF-8. WHY not
     * {@link Properties}: it reads a backslash as an escape, so a host pasting
     * {@code game.root=C:\Games\Starsector} would silently get {@code C:GamesStarsector}.
     * A missing file = no rows (the game_root pre-check then names the row); an unreadable one
     * warns once and reads as no rows.
     */
    static Map<String, String> readLauncherRows(Path file, PrintStream out) {
        Map<String, String> rows = new HashMap<>();
        if (!Files.exists(file)) {
            return rows;
        }
        String text;
        try {
            if (Files.size(file) > MAX_LAUNCHER_PROPERTIES_BYTES) {
                throw new IOException("over " + MAX_LAUNCHER_PROPERTIES_BYTES + " bytes");
            }
            text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            say(out, "serve-launcher warning: " + file + " cannot be read (" + e.getClass().getSimpleName() + ")");
            return rows;
        }
        if (!text.isEmpty() && text.charAt(0) == (char) 0xFEFF) {
            text = text.substring(1); // a Windows editor's UTF-8 BOM would otherwise ride the first key
        }
        for (String raw : text.split("\r?\n|\r")) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || eq <= 0) {
                continue;
            }
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1).trim(); // Explorer's "Copy as path" quotes
            }
            rows.put(line.substring(0, eq).trim(), value);
        }
        return rows;
    }

    /**
     * The config's {@code mods.roots} row read RAW ({@link #readLauncherRows}'s grammar, UTF-8), null when the
     * row is absent, {@code ""} when present and blank (the Mods tab's explicit-empty list). WHY not the
     * {@link Properties} the rest of the config is read through: it decodes the file latin-1 and unescapes
     * backslashes, so a root under a non-ASCII profile folder, or a hand-typed {@code C:\mods}, would reach
     * {@link PackageModSync} mangled while the Mods tab still lists the mod. (The console writes {@code /}
     * tokens so the Properties load itself cannot throw on a console-written row.)
     */
    static String modsRootsRowOrNull(Path config, PrintStream out) {
        return readLauncherRows(config, out).get(InstalledModScan.MODS_ROOTS_KEY);
    }

    /** {@code flight-<yyyyMMdd-HHmmss>} created atomically; a same-second collision waits for the next second. */
    private static Path createFlightDir(Path run) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Path dir = run.resolve(FLIGHT_PREFIX + LocalDateTime.now().format(FLIGHT_STAMP));
            try {
                return Files.createDirectory(dir);
            } catch (FileAlreadyExistsException taken) {
                if (attempt >= 3) {
                    throw taken;
                }
                try {
                    Thread.sleep(1_050L - (System.currentTimeMillis() % 1_000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw taken;
                }
            }
        }
    }

    /**
     * The {@code run/stop} boot law, true = an ORDER for this launcher (exit {@link #EXIT_STOPPED_AT_BOOT} before the
     * flight, the pointer, any life or the supervisor). WHY: the Ops console's Kill trusts the alive launcher it
     * spawned before that launcher has written its pointer, and writes {@code stopFile}; deleting that stop as stale
     * would boot the serve behind a Kill that already said "stop".
     *
     * <p>A regular {@code stopFile} is CLAIMED first: renamed atomically to {@link #STOP_CLAIM_FILE}, so a stop
     * written while this runs either lands in the claim (its file time then reads fresh) or creates a NEW
     * {@code stopFile}, which the supervisor's pre-life check honours - never one deleted unread. The claim's file
     * time later than {@code launcherStart} minus {@link #STOP_START_SLACK_MS} = an order: the claim is deleted and
     * {@link #STOPPED_AT_BOOT_LINE} said (the one line). Earlier = stale (a finished flight's stop, the previous
     * launcher's own shutdown-hook stop): deleted, false, nothing said.
     *
     * <p>Fail-closed: an EMPTY {@code launcherStart} (the JVM cannot read its own start) or an unreadable file time
     * = an order - at worst one lost boot the host repeats, never a serve orphaned behind a stop. A non-regular
     * {@code stopFile} (a folder: never what the Kill, {@code stop.bat} or {@code stop.sh} write) keeps the plain
     * stale clear, and a claim that fails leaves the file in place (a warning line): either way the supervisor's
     * pre-life check still stops on a surviving {@code stopFile}.
     *
     *
     */
    static boolean stopOrderedAtBoot(Path stopFile, Optional<Instant> launcherStart, PrintStream out) {
        if (!Files.isRegularFile(stopFile, LinkOption.NOFOLLOW_LINKS)) {
            deleteStale(stopFile, out);
            return false;
        }
        Path claim = stopFile.resolveSibling(STOP_CLAIM_FILE);
        try {
            Files.move(stopFile, claim, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            say(out, "serve-launcher warning: " + stopFile + " could not be claimed (" + e.getClass().getSimpleName()
                    + "); it is left in place, so the server stops before its first start");
            return false;
        }
        Instant written;
        try {
            written = Files.getLastModifiedTime(claim, LinkOption.NOFOLLOW_LINKS).toInstant();
        } catch (IOException | RuntimeException unreadable) {
            written = null; // fail-closed: the order line below is this path's one line
        }
        deleteStale(claim, out);
        boolean order = written == null || launcherStart.isEmpty()
                || written.isAfter(launcherStart.get().minusMillis(STOP_START_SLACK_MS));
        if (order) {
            say(out, STOPPED_AT_BOOT_LINE);
        }
        return order;
    }

    private static void deleteStale(Path file, PrintStream out) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            say(out, "serve-launcher warning: the stale file " + file + " could not be removed ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    private static void writeLines(Path file, List<String> lines, PrintStream out) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        try {
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            say(out, "serve-launcher warning: " + file + " could not be written (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** {@code --pkg} / {@code --config} / {@code --template} / {@code --launcher-properties}, each once with a value; else null. */
    private static Map<String, String> parseArgs(String[] args) {
        Set<String> flags = Set.of("--pkg", "--config", "--template", "--launcher-properties");
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!flags.contains(args[i]) || i + 1 >= args.length || opts.containsKey(args[i])
                    || args[i + 1].isBlank()) {
                return null;
            }
            opts.put(args[i], args[i + 1]);
        }
        return opts;
    }

    /** The package root = the parent of this class's code source ({@code <pkg>/classes/} or {@code <pkg>/cc-server.jar}). */
    private static Path defaultPackageRoot() {
        try {
            Path source = Path.of(ServeLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path parent = source.toAbsolutePath().normalize().getParent();
            return parent;
        } catch (URISyntaxException | RuntimeException unknown) {
            return null; // the caller prints the usage line naming --pkg
        }
    }

    private static Path absolute(String path) {
        try {
            return Path.of(path).toAbsolutePath().normalize();
        } catch (InvalidPathException bad) {
            return null; // the caller prints the usage line
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** One console line, printable ASCII only (anything else becomes {@code ?}), bounded per line. */
    static void say(PrintStream out, String line) {
        StringBuilder sb = new StringBuilder(Math.min(line.length(), 1024));
        for (int i = 0; i < line.length() && sb.length() < 1024; i++) {
            char ch = line.charAt(i);
            sb.append(ch >= 0x20 && ch < 0x7F ? ch : '?');
        }
        out.println(sb);
    }

    /** The bound applied to an operator-typed value (a key, a row, a template line) inside a console sentence. */
    static String bounded(String s) {
        return s.length() <= MAX_CONSOLE_TOKEN_CHARS ? s : s.substring(0, MAX_CONSOLE_TOKEN_CHARS) + "...";
    }
}
