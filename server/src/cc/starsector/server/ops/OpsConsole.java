package cc.starsector.server.ops;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchShapes;
import cc.starsector.server.ops.ServerOpsCore.DescriptorInfo;
import cc.starsector.server.ops.ServerOpsCore.ServerState;
import cc.starsector.server.runtime.PilotIntentDatagramServer;
import cc.starsector.server.runtime.VoiceConfig;

/**
 * The ops web console's HTTP SHELL - the console a host
 * opens in a browser, in two grammars: {@code OpsConsole --pkg <root> [port]} (a stranger's unpacked
 * server package; {@code ops-web.bat} / {@code ops-web.sh} run it) and {@code OpsConsole --owner <repo
 * root> [ops file [port]] [--open]} (the maintainer's repository checkout). It only speaks HTTP: the
 * request rules live in {@link OpsConsoleForms}, the page in {@link OpsConsolePage}, every ops law in the
 * {@link ServerOpsCore} / {@link ServerOpsLaunch} / {@link ServerOpsKill} / {@link OpsVoicePanel} /
 * {@link OpsModsPanel} statics (pure, no UI toolkit). Its contract runs in
 * {@link OpsConsoleSelftest} ({@code OpsConsole --selftest}, the {@code server/build.ps1} hook).
 *
 * <p>WHY two grammars: the browser console is the product's console in both
 * modes, so the Swing window can retire. {@code --pkg} claims a package and is VERIFIED against the
 * packer's marker ({@link OpsRoots#packagedChecked}); {@code --owner} claims the repository root, which
 * must BE the working directory (owner mode's paths resolve under it; {@link #ownerClaimRefusalOrNull})
 * and must carry the stage tool ({@link OpsRoots#ownerOpenRefusal}). Neither mode is ever inferred: no
 * flag = the usage line. Plus {@code --selftest}.
 *
 * <p>WHY the token: the console starts and kills servers and bans
 * players, and loopback is NOT a trust boundary (any local process or a browser page aimed at
 * {@code 127.0.0.1} can reach it). The key PERSISTS across console restarts (
 * the symptom: a tab opened from one start read WRONG KEY after a restart): a boot first reads
 * {@code <pkg>/run/console.token} and keeps its key when the file passes the reuse law
 * ({@link #readKeptToken}: a regular file, one 43-char base64url line, POSIX {@code rw-------} and
 * owned by this account); else it mints 32 {@link SecureRandom} bytes (43 base64url chars) and
 * writes them there (POSIX {@code rw-------}). Re-key = stop the console, delete that file, start
 * it. The {@code open http://127.0.0.1:<port>/#<token>} address is printed ONLY to a terminal -
 * never to a redirected stdout ({@code nohup.out}, journald) - and a Windows terminal start also
 * opens it in the default browser ({@link #openBrowser}: one {@code cmd.exe /d /c start} spawn, the
 * {@link OpsJoinSelf} idiom; the console itself stays {@code java.awt.headless}). Every
 * {@code /api/*} request carries the key as
 * {@code X-CC-Ops}, compared with {@link MessageDigest#isEqual} (the server's bytes first); the
 * three static files are secret-free and need none. Kept from the July shell: the loopback bind,
 * the localhost-{@code Host} guard ({@link ServerOpsCore#webRequestAllowed}), no CORS header ever,
 * {@code X-Frame-Options: DENY}. Added: the exact-path law (the JDK's prefix contexts closed), the
 * CSP {@code default-src 'self'}, {@code Referrer-Policy: no-referrer}, the 64 KB body cap (413),
 * the 30 s request clock.
 *
 * <p>WHY the fixed 500: a handler throw answers ONE fixed text
 * ({@value #INTERNAL_ERROR}) and prints one stdout row naming only the exception's SimpleName -
 * an exception message can carry a path or a typed value the page must never echo.
 *
 * <p>Provenance: the web console design; the July
 * {@code ServerOpsWeb} idioms (boot, status splice, Start's collecting Ui and spawn seam), which
 * this class replaced (that class is deleted).
 */
public final class OpsConsole {

    static final int DEFAULT_PORT = 7717;
    /**
     * The shell's request-READ ceiling in seconds
     * ({@code sun.net.httpserver.maxReqTime}, set by {@link #boot}). It times only the reading of a
     * request (armed at request start, disarmed once the body's last byte is read); a handler's run
     * is NOT timed server-side ({@code maxRspTime} unset = forever), so the page's POST abort (the core
     * script's {@code isStatus ? 3000 : 35000}) is the ONLY clock on a stuck handler — it exceeds this
     * ceiling and the Kill's two 8 s polls (row {@code page-post-timeout}). A Kill's worst case is the
     * two polls, under 30 s.
     */
    static final int REQUEST_CLOCK_S = 30;
    /** The largest accepted request body (bytes); one more = 413. */
    static final int BODY_CAP = 65536;
    static final String TOKEN_HEADER = "X-CC-Ops";
    /** The key file under the package's {@code run/}: written at the first boot, KEPT by every later
     * one that finds it sound ({@link #readKeptToken}); deleting it before a start makes a new key. */
    static final String TOKEN_FILE = "console.token";
    /** The key's shape in the file: 32 random bytes as unpadded base64url (the page's {@code _isKey}
     * accepts it; the legs read "one 43-char line"). */
    static final String TOKEN_SHAPE = "[A-Za-z0-9_-]{43}";
    /** The most bytes {@link #readKeptToken} reads of a planted file (the key + one line break, with room). */
    static final int TOKEN_FILE_CAP = 64;

    /** The per-folder console lock under the package's {@code run/} ({@link #takeConsoleLock}). */
    static final String CONSOLE_LOCK_FILE = "console.lock";
    /**
     * The running console's card under {@code run/} ({@link #writeRunningCard}; it tells a user a console is already
     * open): {@code pid=} {@code port=} {@code since=} {@code pack=}, one per line, written after the
     * boot and removed by a confirmed Close ops GUI ({@link #handleQuit}). A second console that finds the lock held
     * reads it ({@link #runningConsole}) and, when that pid is alive, opens the RUNNING page instead of refusing
     * ({@link #reopenRunning}). A stale card (a crash, a killed window) is harmless: the lock is free then, so the
     * card is never read, and the next boot overwrites it. WHY a sidecar and not the lock file's own bytes: on
     * Windows a held {@link FileLock} is mandatory - another process cannot read the locked bytes.
     */
    static final String RUNNING_FILE = "console.running";
    /** The most bytes {@link #runningConsole} reads of the card (four short lines, with room). */
    static final int RUNNING_FILE_CAP = 256;
    /** Close ops GUI ({@link #handleQuit}): its confirm key, the dialog's title and ask, the ok answer's detail. */
    static final String QUIT_KEY = "quit";
    static final String QUIT_TITLE = "Close the ops GUI?";
    static final String QUIT_ASK = "This page goes dark and the ops GUI program ends. A server it started keeps"
            + " running (End match stops it). To open the ops GUI again, double-click ops-web.bat"
            + " (sh ops-web.sh on Linux).";
    static final String QUIT_DETAIL = "the ops GUI is closing";
    /** What a confirmed Close ops GUI runs AFTER its answer is written: {@link #QUIT_LIVE} ends the JVM from a
     *  daemon thread 400 ms later (the answer's bytes leave first; the lock goes with the JVM); the selftest's rig
     *  swaps in a counter for its life, so no row can end the suite's JVM. */
    static final Runnable QUIT_LIVE = () -> {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException interrupted) { // exit anyway
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "cc-console-quit");
        t.setDaemon(true);
        t.start();
    };
    static volatile Runnable quitHook = QUIT_LIVE;
    /**
     * this JVM's hold on {@code run/console.lock}, kept for the
     * JVM's life. WHY static fields: a channel {@link FileChannel#open} made is closed by its Cleaner once nothing
     * reaches it, and its lock goes with it - held in a {@link #pkgMain} local, the lock would be gone at the first
     * GC and a second console (or the update script's busy check) would find the folder free. Row
     * {@code console-lock-static} pins the hold across a {@link System#gc()}.
     */
    private static FileChannel consoleLockChannel;
    private static FileLock consoleLock;
    static final String REFUSED_TOKEN = "{\"error\":\"wrong or missing console key\",\"title\":\"Refused\"}";
    static final String REFUSED_HOST = "{\"error\":\"refused (loopback-only console)\",\"title\":\"Refused\"}";
    static final String TOO_LARGE = "{\"error\":\"request too large\",\"title\":\"Refused\"}";
    static final String INTERNAL_ERROR = "internal error - see the console window";
    static final String KILL_RUNNING = "a Kill is already running - its result follows";
    /** Show saved passwords' confirm key: {@code /api/reveal} answers the secrets only to a form
     * whose {@code confirmed} carries it. */
    static final String REVEAL_KEY = "reveal";
    /** The reveal refusal for a descriptor the selector does not offer (the file-read bound). */
    static final String REVEAL_UNKNOWN_SHAPE = "unknown match shape";
    /** Copy join brick's refusal title (the Swing package brick's dialog title). */
    static final String BRICK_REFUSED = "Copy join brick refused";
    /** Fill from ProtonVPN's refusal title ({@code POST /api/lease}). */
    static final String LEASE_REFUSED = "Fill from ProtonVPN refused";
    /** The {@code op=} words the route takes, one per {@link OpsMatchApply.Op} (the stdout row's op word). */
    static final Map<String, OpsMatchApply.Op> MATCH_SETTINGS_OPS = Map.of("size", OpsMatchApply.Op.SIZE,
            "station", OpsMatchApply.Op.STATION, "backdrop", OpsMatchApply.Op.BACKDROP,
            "cr", OpsMatchApply.Op.DEPLOY_CR, "players", OpsMatchApply.Op.PLAYERS);
    /** An {@code op=} outside {@link #MATCH_SETTINGS_OPS} (only a hand-made request sends one; never echoed). */
    static final String MATCH_SETTINGS_BAD_OP =
            "Unknown match setting: send op=size, station, backdrop, cr or players.";
    /** {@code POST /api/draft}'s refusal title (the page's note reads the
     * {@code error} member under it). */
    static final String DRAFT_TITLE = "Unsaved Setup not kept";
    /** The draft write failed on disk: the fixed sentence (never a path or an exception message). */
    static final String DRAFT_IO = "The unsaved Setup could not be written to the console's run folder.";
    /** A package-only Apply asked of an owner console: the deploy-CR and Players Applies change a package's
     * match files, which owner mode has none of (its three map Applies re-author the owner descriptor sets,
     * {@link #ownerMatchApply}). */
    static final String MATCH_SETTINGS_NOT_PACKAGE = "The Battle map Applies change a server package's"
            + " match files; this console does not run on a server package.";
    /** An owner Apply that failed part-way - the Swing owner Applies'
     * FAILED sentence (copied, so this console owes nothing to the Swing class); the real guard it names is
     * the door's {@code match=} staleness check: a session claim whose print differs from the door's only in its
     * match term is refused {@code match_stale}, while a join window's probe is served the serve's match. */
    static final String OWNER_APPLY_PARTIAL = "Apply may be PARTIAL - re-run it. A player whose game"
            + " holds another match than the serve's is refused at the door (match_stale), never let in on a"
            + " stale match.";
    /** The owner prefill's {@code problem} when the config file cannot be read (the map size field opens blank). */
    static final String OWNER_PROPS_UNREADABLE = "The config file could not be read - map size not prefilled.";
    /** The longest {@code /api/config} query the match-settings prefill decodes (a longer one = not offered). */
    static final int CONFIG_QUERY_MAX = 4096;

    private static final String JSON = "application/json; charset=utf-8";
    private static final String HTML = "text/html; charset=utf-8";
    private static final String CSS = "text/css; charset=utf-8";
    private static final String JS = "text/javascript; charset=utf-8";
    private static final String TEXT = "text/plain; charset=utf-8";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The Kill order seam: {@link ServerOpsKill#killPublicServe} outside the selftest. */
    @FunctionalInterface
    interface KillFn {
        ServerOpsKill.Outcome kill(Path opsFile, long writerPid, Process knownLauncher);
    }

    /** One booted console: its server (stop = {@link #stop}), the shell, the token and its file. */
    record Boot(HttpServer server, OpsConsole console, String token, Path tokenFile) {
        int port() {
            return server.getAddress().getPort();
        }

        /** Stops the server AND retires its non-daemon pool (else the JVM never exits). */
        void stop() {
            server.stop(0);
            if (server.getExecutor() instanceof ExecutorService pool) {
                pool.shutdownNow();
            }
        }
    }

    @FunctionalInterface
    private interface Route {
        void handle(HttpExchange ex) throws IOException;
    }

    /**
     * One followed status file and the state that is true only of it -
     * the twin of the Swing model's {@code follow} (a press re-points the poll and forgets the previous lane's
     * writer and seat rows). {@code writerWatch} = the GONE alarm's "died on MY watch" memory for THIS file;
     * {@code lastRunSeats} = this file's last run rows (the remember / substitute law), empty after a swap.
     * Immutable: a seats update replaces the record ({@link #rememberSeats}), never a field, and the writer
     * watch's identity marks the swap generation.
     */
    record Follow(Path opsFile, ServerOpsCore.WriterWatch writerWatch, List<OpsSnapshot.SeatRow> lastRunSeats) {
        Follow {
            Objects.requireNonNull(opsFile, "opsFile");
            Objects.requireNonNull(writerWatch, "writerWatch");
            lastRunSeats = List.copyOf(lastRunSeats);
        }

        /** A swap's record: {@code opsFile} with a fresh writer watch and no remembered seats. */
        static Follow fresh(Path opsFile) {
            return new Follow(opsFile, new ServerOpsCore.WriterWatch(), List.of());
        }

        Follow withSeats(List<OpsSnapshot.SeatRow> seats) {
            return new Follow(opsFile, writerWatch, seats);
        }
    }

    private final OpsRoots roots;
    /** The folder's package identity read once at construction ({@link #packIdOrNull}); null = not a packed
     *  folder. Read once: an update cannot run while this console holds {@code run/console.lock}. */
    private final PackId packId;
    /**
     * The status file this console FOLLOWS, with its own writer watch
     * and last-run seats ({@link Follow}) - read by status, the snapshot reads, the CMD gate and Kill. WHY a
     * reference, not the roots' constant: it is the launcher's status file (or the {@code --owner} positional)
     * until an Arm WATCH press (owner mode) re-points it at the watch's own ({@link #pressOwnerWatch}); the next started
     * Start points it back at {@link OpsRoots#opsFile} (the Swing window's follow rule; a watch's end never
     * swaps). Every swap is a fresh {@link Follow}, so one lane's writer and seat rows never describe another.
     * Package mode never swaps (its seed IS {@link OpsRoots#opsFile}). Seeded once in the constructor.
     * Invariant: each reader takes ONE {@code get()} into a local per request, so one answer never mixes two
     * files.
     */
    final AtomicReference<Follow> followed;
    private final Path propsFile;
    private final Path briefFile;
    private final double uplinkDefault;
    private final byte[] token;
    private final ServerOpsCore.SpawnFn spawn;
    private final KillFn killFn;
    /** What a ProtonVPN pull reads ({@link VpnLeaseProbe.Env#live} outside the selftest). */
    private final VpnLeaseProbe.Env leaseEnv;
    /** The ONE gateway pull in flight ({@link #sharedProbe}); null / done = none. */
    private final AtomicReference<CompletableFuture<VpnLeaseProbe.Probe>> leaseFlight = new AtomicReference<>();

    /** Serializes every read-then-write of the props tree (Start, Voice, Mods) and of the unsaved Setup draft
     * (its POST, its config load, its rebase) - one operator. */
    private final Object startLock = new Object();
    /** This console's last Start ({@code null} = none): the Kill order's known launcher. */
    private final AtomicReference<LaneFollow.LaneLaunch> startLaunch = new AtomicReference<>();
    /** The Start that went LIVE (identity): consumed, so its object leaves the status JSON. */
    private final AtomicReference<LaneFollow.LaneLaunch> liveLaunch = new AtomicReference<>();
    /** The Start whose terminal failure {@link #failId} already counted (identity). */
    private final AtomicReference<LaneFollow.LaneLaunch> failedLaunch = new AtomicReference<>();
    /** +1 per Start that turns terminal FAILED / REFUSED (0 = none yet); the page's dialog key. */
    private final AtomicInteger failId = new AtomicInteger();
    /** Kill is single-flight; Swing's {@code killInFlight} twin. */
    private final AtomicBoolean killInFlight = new AtomicBoolean();
    private final AtomicBoolean briefFailLogged = new AtomicBoolean();
    private final AtomicReference<String> lastBriefJson = new AtomicReference<>("null");
    /** Join from this PC's seams ({@link OpsJoinSelf#LIVE} outside
     * the selftest; its {@code probe} is swapped for {@link #sharedProbe} at each press). */
    private final OpsJoinSelf.Effects joinFx;
    /** Owner mode's two script spawns (Dev editor, Joiner GUI);
     * {@link OwnerLaunch#LIVE} outside the selftest. Reached only behind a {@code Mode.OWNER} test. */
    private final OwnerLaunch ownerLaunch;
    /** One Join press in flight; never the shared {@code leaseFlight}: a join writes + spawns.
     * {@link #pressJoinOnce} is its ONE compare-and-set, for the join POST and the debug-watch daemon alike. */
    private final AtomicBoolean joinInFlight = new AtomicBoolean();
    /** The debug-watch press's last outcome sentence ({@code ""} = none), served as the status JSON's
     * {@code watchNote}: the core's own bounded operator text (an {@link OpsJoinSelf} answer, or
     * {@link ServerOpsCore#WATCH_JOIN_TIMED_OUT}), never player text, never a secret. Written by the daemon's
     * press + note sinks; every accepted Start (either route) clears it. Package-visible for the selftest's
     * clear leg. */
    final AtomicReference<String> watchNote = new AtomicReference<>("");
    /** The last Join press's inputs ({@code null} = no press yet), set
     * right before {@link OpsJoinSelf#press} - a receipt for the console rows, never printed or served
     * ({@link OpsJoinSelf.Inputs#toString} shows the password only as blank / set). It holds the MATCH
     * password in memory until the next press, never the admin secret (the {@code adminArm} bit reads none). */
    final AtomicReference<OpsJoinSelf.Inputs> lastPressInputs = new AtomicReference<>();
    /**
     * The sentence a Join press answers after its OPENED note when the console withheld the window's admin arm
     * because this serve already holds the door's cap of armed seat keys
     * ({@link PilotIntentDatagramServer#ARMED_ADMIN_CAP}, the door's own constant, so the two cannot drift): that
     * window opens plain, so the host hears it is not signed in and the two ways back.
     *
     */
    static final String NOTE_ARM_CAP = "This window is not signed in as host: this server has already signed in "
            + PilotIntentDatagramServer.ARMED_ADMIN_CAP + " windows since it started - restart the server, or set"
            + " an Admin password (Setup tab) to sign in from the ship editor card.";
    /** The arm lines this console appended to the live serve ({@link ArmLedger}); read and written by
     * {@link #joinAnswer} only, under the one-press-in-flight law. */
    private final ArmLedger armLedger = new ArmLedger();

    /**
     * How many {@code admin-cid} lines this console appended to ONE serve's command file, keyed by that serve's
     * identity = its status file's writer pid ({@link OpsSnapshot#pid}, &gt; 0 for a live serve; the snapshot
     * carries no boot stamp) and restarted from zero when the pid changes - the pid-keyed idiom of
     * {@link ServerOpsCore.WriterWatch}. WHY: the door holds at most
     * {@link PilotIntentDatagramServer#ARMED_ADMIN_CAP} armed keys per serve life and reports a refused key only
     * in the serve's own log, so a press past the cap opened a plain window under the signed-in note. Every armed
     * press is one door slot: the console mints a fresh key for each armed window and never re-arms a key, so
     * counting appended lines counts slots. The count comes from {@link OpsJoinSelf.Result#armLines}, so a failed
     * or skipped append never counts. F-C-2: a pid the OS hands a LATER serve is told
     * apart by the status file's {@link OpsSnapshot#upSeconds} - one serve's up_s never falls, so the same pid
     * with a smaller up_s than last seen is a restart and the count starts over.
     *
     */
    static final class ArmLedger {
        private long servePid = -1L;
        private long upSeen = -1L;
        private int appended;

        /** Keys the count to the serve identity {@code pid} + {@code upSeconds}: a new pid, or the SAME pid whose
         *  up_s fell below the last seen value (a same-pid restart), starts from zero. */
        private void observe(long pid, long upSeconds) {
            if (pid != servePid || upSeconds < upSeen) {
                servePid = pid;
                appended = 0;
            }
            upSeen = upSeconds;
        }

        /** True when the serve ({@code pid}, {@code upSeconds}) already holds the door's cap of keys this console
         *  armed on it. */
        synchronized boolean full(long pid, long upSeconds) {
            observe(pid, upSeconds);
            return appended >= PilotIntentDatagramServer.ARMED_ADMIN_CAP;
        }

        /** Counts {@code lines} (0..2) appended to the serve ({@code pid}, {@code upSeconds}). */
        synchronized void add(long pid, long upSeconds, int lines) {
            observe(pid, upSeconds);
            appended += lines;
        }
    }

    OpsConsole(OpsRoots roots, Path briefFile, double uplinkDefault, String token,
            ServerOpsCore.SpawnFn spawn, KillFn killFn, VpnLeaseProbe.Env leaseEnv, OpsJoinSelf.Effects joinFx,
            OwnerLaunch ownerLaunch, Path followSeed) {
        this.roots = Objects.requireNonNull(roots, "roots");
        this.packId = packIdOrNull(roots.pkg());
        this.leaseEnv = Objects.requireNonNull(leaseEnv, "leaseEnv");
        this.joinFx = Objects.requireNonNull(joinFx, "joinFx");
        this.ownerLaunch = Objects.requireNonNull(ownerLaunch, "ownerLaunch");
        this.followed = new AtomicReference<>(Follow.fresh(followSeed != null ? followSeed : roots.opsFile()));
        this.propsFile = roots.config();
        this.briefFile = Objects.requireNonNull(briefFile, "briefFile");
        this.uplinkDefault = uplinkDefault;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.spawn = Objects.requireNonNull(spawn, "spawn");
        this.killFn = Objects.requireNonNull(killFn, "killFn");
    }

    // ---- main / boot ---------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true"); // the console never opens a window
        if (args.length == 1 && "--selftest".equals(args[0])) {
            List<String> fails = OpsConsoleSelftest.run();
            if (!fails.isEmpty()) {
                System.out.println("ops-console selftest FAILED (" + fails.size() + " row(s))");
                System.exit(1);
            }
            System.out.println("ops-console selftest PASS");
            return;
        }
        if (args.length >= 1 && PKG_FLAG.equals(args[0])) {
            pkgMain(args);
            return;
        }
        if (args.length >= 1 && OWNER_FLAG.equals(args[0])) {
            ownerMain(args);
            return;
        }
        System.err.println(USAGE);
        System.exit(2);
    }

    /** The package-mode flag: {@code --pkg <unpacked server root>} (the rest of
     * the grammar = {@link #USAGE}). */
    static final String PKG_FLAG = "--pkg";
    /** The owner-mode grammar's flag (never inferred - only this
     * flag plus both claim checks land a console in {@code Mode.OWNER}). */
    static final String OWNER_FLAG = "--owner";
    /** A LAST argument asking the console to open its keyed address in a browser (the launcher's decision). */
    static final String OPEN_FLAG = "--open";
    /** Owner mode's default port: one above the package's {@link #DEFAULT_PORT}, so the maintainer's console and
     * a package console started on the same box never contend for one bind. */
    static final int OWNER_DEFAULT_PORT = 7718;
    /** The whole grammar, printed on stderr (exit 2) for anything else. */
    static final String USAGE = "usage: OpsConsole --pkg <unpacked server root> [port 0..65535] | --owner <repo root>"
            + " [ops file [port 0..65535]] [--open] | --selftest";
    /** The claim refusal: the typed root, then the working directory (absolute). */
    static final String OWNER_CWD_MISMATCH = "OpsConsole --owner: the repository root must be the working directory"
            + " (given %s, working directory %s)";

    /** One parsed {@code --owner} command line: {@code opsFile} null = absent; {@code port} 0..65535. */
    record OwnerArgs(String root, String opsFile, int port, boolean open) {
    }

    /**
     * The PURE {@code --owner} grammar - {@code args[0]} is
     * {@link #OWNER_FLAG}; a LAST argument equal to {@link #OPEN_FLAG} is stripped ({@code open}); then 1..3
     * positionals = the root, [the ops file], [the port]. An ops file starting with {@code -} is refused (a
     * mistyped flag never becomes a followed file); the port parses exactly as {@link #pkgMain}'s (trimmed,
     * 0..65535); absent = {@link #OWNER_DEFAULT_PORT}.
     *
     * @return the parse, or null = the usage line
     */
    static OwnerArgs ownerArgsOrNull(String[] args) {
        if (args == null || args.length < 1 || !OWNER_FLAG.equals(args[0])) {
            return null;
        }
        int end = args.length;
        boolean open = end > 1 && OPEN_FLAG.equals(args[end - 1]);
        if (open) {
            end--;
        }
        int positionals = end - 1;
        if (positionals < 1 || positionals > 3) {
            return null;
        }
        String opsFile = positionals >= 2 ? args[2] : null;
        if (opsFile != null && opsFile.startsWith("-")) {
            return null;
        }
        int port = OWNER_DEFAULT_PORT;
        if (positionals == 3) {
            try {
                port = Integer.parseInt(args[3].trim());
            } catch (NumberFormatException notAPort) { // reported by the usage line
                return null;
            }
            if (port < 0 || port > 65535) {
                return null;
            }
        }
        return new OwnerArgs(args[1], opsFile, port, open);
    }

    /**
     * Claim check (i) - the typed root must BE the working
     * directory (owner mode resolves every repo-relative path under the cwd, so a console started elsewhere
     * would follow, stage and write another tree). Compared by REAL path, so a junction or link whose target
     * is the cwd is accepted. Pure apart from the two real-path reads.
     *
     * @return null when they match, else {@link #OWNER_CWD_MISMATCH} formatted with the typed path and the
     *         absolute cwd - also for any read failure (never a stack trace; the sentence IS the stderr line)
     */
    static String ownerClaimRefusalOrNull(Path typed, Path cwd) {
        boolean same;
        try {
            same = typed.toRealPath().equals(cwd.toRealPath());
        } catch (IOException | RuntimeException unresolved) { // an unresolvable root is a mismatch: the sentence
            same = false;
        }
        return same ? null : String.format(Locale.ROOT, OWNER_CWD_MISMATCH, typed, cwd.toAbsolutePath());
    }

    /**
     * {@code --owner <repo root> [ops file [port]] [--open]}. Both
     * claim checks run BEFORE any stdout line (a launcher merges stderr into stdout and reads line 1): (i)
     * {@link #ownerClaimRefusalOrNull}, (ii) {@link OpsRoots#ownerOpenRefusal}; each refusal = ONE stderr
     * sentence + exit 2. Then {@link OpsRoots#owner} UNMODIFIED is the live record (the positional ops file
     * only seeds {@link #followed}, made absolute here), the boot, the listening row and
     * {@link #printOwnerAddress}. The browser opens when {@code --open} was given OR the key was newly minted
     * (an open tab's old key is dead, so a fresh tab is its only recovery).
     */
    private static void ownerMain(String[] args) throws IOException {
        OwnerArgs parsed = ownerArgsOrNull(args);
        if (parsed == null) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        Path cwd = Paths.get("");
        String refusal;
        try {
            refusal = ownerClaimRefusalOrNull(Paths.get(parsed.root()), cwd);
        } catch (InvalidPathException badRoot) { // the typed root is not a path at all: the same sentence
            refusal = String.format(Locale.ROOT, OWNER_CWD_MISMATCH, parsed.root(), cwd.toAbsolutePath());
        }
        if (refusal == null) {
            refusal = OpsRoots.ownerOpenRefusal(cwd);
        }
        if (refusal != null) {
            System.err.println(refusal);
            System.exit(2);
            return;
        }
        Path seed;
        try {
            seed = parsed.opsFile() == null ? null : Paths.get(parsed.opsFile()).toAbsolutePath().normalize();
        } catch (InvalidPathException badFile) { // the usage line: an ops file that is not a path
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        OpsRoots roots = OpsRoots.owner();
        OpsRoots.ROOTS.set(roots);
        String keyBefore = readKeptToken(roots.pkg().resolve(ServeLauncher.RUN_DIR).resolve(TOKEN_FILE)).token();
        Boot boot = boot(roots, parsed.port(), ServerOpsLaunch::spawnStartScript, ServerOpsKill::killPublicServe,
                VpnLeaseProbe.Env.live(), OpsJoinSelf.LIVE, OwnerLaunch.LIVE, seed);
        System.out.println("cc-console listening port=" + boot.port());
        boolean keyMinted = keyBefore == null || !keyBefore.equals(boot.token());
        printOwnerAddress(boot, parsed.open() || keyMinted, ServerOpsCore.joinHere(System.getProperty("os.name")),
                BrowserSpawn.LIVE);
    }

    /**
     * Owner mode's lines after the listening row. {@code open} =
     * {@link #printAddress} as on a terminal (the keyed address + ONE browser spawn on Windows); otherwise the
     * KEYLESS {@code address: http://127.0.0.1:<port>/} line + {@code cc-console browser result=not-asked} - a
     * launcher tees this stdout into a log, so no key may ride it unasked (an open tab keeps its key in the
     * page's session storage and recovers by itself). Parameters, so a row drives both branches without a
     * process.
     */
    static void printOwnerAddress(Boot boot, boolean open, boolean windows, BrowserSpawn spawn) {
        if (open) {
            printAddress(boot, true, windows, spawn);
            return;
        }
        System.out.println("address: http://127.0.0.1:" + boot.port() + "/");
        System.out.println("cc-console browser result=not-asked");
    }

    /**
     * Owner mode's two script spawns, the Swing window's Dev editor
     * and Joiner GUI buttons. WHY no parameters: no request text can reach an argv - each spawn is the fixed
     * {@code pwsh -NoProfile -File <script>} of {@link ServerOpsLaunch#spawnDevEditor} /
     * {@link ServerOpsLaunch#spawnJoinerGui}. The selftest's owner rig fakes it, so no row runs pwsh.
     */
    interface OwnerLaunch {
        Process devEditor() throws IOException;

        Process joinerGui() throws IOException;

        /** The real spawns (output appended to each script's own log). */
        OwnerLaunch LIVE = new OwnerLaunch() {
            @Override
            public Process devEditor() throws IOException {
                return ServerOpsLaunch.spawnDevEditor();
            }

            @Override
            public Process joinerGui() throws IOException {
                return ServerOpsLaunch.spawnJoinerGui();
            }
        };
    }

    /**
     * The WATCH lane's force-kill - a pid (an OS pid, &gt; 0) in, the
     * operator note out: {@link #signalledNote} or {@link #goneNote}, the Swing Kill's two sentences.
     * {@link OwnerWatch#LIVE} forcibly ends the process; a selftest row plants a recorder.
     */
    @FunctionalInterface
    interface PidKill {
        String kill(long pid);

        static String signalledNote(long pid) {
            return "kill signalled to pid " + pid;
        }

        static String goneNote(long pid) {
            return "pid " + pid + " not found (already gone?)";
        }
    }

    /**
     * The Arm WATCH press's seams (owner mode) - the Swing owner button's exact
     * arguments ({@link ServerOpsLaunch#spawnWatchScript}, the forward folder
     * {@link ServerOpsLaunch#BRIEF_FORWARD_DIR}, the watch's own status file {@link ServerOpsCore#WATCH_OPS_FILE}),
     * the brief file to forward ({@code null} = this console's own brief file) and the WATCH lane's force-kill.
     * WHY a static record and not boot arguments: the console's one {@code SpawnFn} is the Start's, and the live
     * paths are repo-relative - resolved against the working directory {@code --owner} claimed - so a selftest
     * row on them would write into the live repository; a row swaps {@link #ownerWatch} and restores it in a
     * {@code finally}.
     */
    record OwnerWatch(ServerOpsCore.SpawnFn spawn, Path watchOps, Path forwardDir, Path briefFileOrNull,
            PidKill pidKill) {
        OwnerWatch {
            Objects.requireNonNull(spawn, "spawn");
            Objects.requireNonNull(watchOps, "watchOps");
            Objects.requireNonNull(forwardDir, "forwardDir");
            Objects.requireNonNull(pidKill, "pidKill");
        }

        /** The real seams: the watch script spawn, the repo-relative paths, Swing's force-kill (a
         * {@code destroyForcibly} that is refused, or a pid with no process, reads "not found"). */
        static final OwnerWatch LIVE = new OwnerWatch(ServerOpsLaunch::spawnWatchScript,
                Paths.get(ServerOpsCore.WATCH_OPS_FILE), Paths.get(ServerOpsLaunch.BRIEF_FORWARD_DIR), null,
                pid -> ProcessHandle.of(pid).map(ProcessHandle::destroyForcibly).orElse(false)
                        ? PidKill.signalledNote(pid) : PidKill.goneNote(pid));
    }

    /** The Arm WATCH seams in force ({@link OwnerWatch#LIVE} outside a selftest row). */
    static volatile OwnerWatch ownerWatch = OwnerWatch.LIVE;

    /** {@code --pkg <root> [port]}: ONLY a port may follow the root (0..65535, 0 = ephemeral);
     * a refusal = its sentence on stderr + exit 2 (the deleted {@code ServerOpsWeb}'s package grammar). */
    private static void pkgMain(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        boolean grammar = args.length == 2 || args.length == 3;
        if (args.length == 3) {
            try {
                port = Integer.parseInt(args[2].trim());
            } catch (NumberFormatException notAPort) { // reported by the usage line below
                grammar = false;
            }
            grammar &= port >= 0 && port <= 65535;
        }
        if (!grammar) {
            System.err.println("usage: OpsConsole --pkg <unpacked server root> [port 0..65535]"
                    + " (the console takes nothing else)");
            System.exit(2);
            return;
        }
        OpsRoots pkg;
        try {
            pkg = OpsRoots.packagedChecked(Paths.get(args[1]));
        } catch (IllegalArgumentException refused) { // InvalidPathException included; the sentence is the report
            System.err.println(refused.getMessage());
            System.exit(2);
            return;
        }
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        String busy = takeConsoleLock(pkg.pkg(), windows);
        if (busy != null) {
            Running live = runningConsole(pkg.pkg());
            if (live == null) { // the update script's check, or a holder with no live card: the one refusal sentence
                System.err.println(busy);
                System.exit(2);
                return;
            }
            System.exit(reopenRunning(pkg.pkg(), live, interactiveConsole(), windows, BrowserSpawn.LIVE));
            return;
        }
        // The first-start repair of a PACKED folder, after
        // the console lock (the update script sees a console) and before the boot reads any live file.
        PackageRepair.repairIfPacked(pkg.pkg(), System.out);
        OpsRoots.ROOTS.set(pkg);
        Boot boot = boot(pkg, port, ServerOpsLaunch::spawnStartScript, ServerOpsKill::killPublicServe);
        System.out.println("cc-console listening port=" + boot.port());
        writeRunningCard(pkg.pkg(), boot);
        String packLine = boot.console().packLine();
        if (packLine != null) {
            System.out.println(packLine);
        }
        printAddress(boot, interactiveConsole(), ServerOpsCore.joinHere(System.getProperty("os.name")),
                BrowserSpawn.LIVE);
    }

    /**
     * Takes {@code <pkg>/run/console.lock} for this JVM's life,
     * the launcher's {@code serve.lock} shape - {@code run/} created first, a {@code CREATE + WRITE} channel, ONE
     * {@code tryLock} - held in {@link #consoleLock} (the static-field why is there). WHY a lock: two consoles on
     * one folder raced on its files (the second died on its port with no sentence), and the update script must be
     * able to tell that a console is open. One try, no retry: nothing else probes this lock in a cycle.
     *
     * @param windows names the Windows update script and separator in the refusal (else the Linux twins)
     * @return null = held now; else the ONE refusal sentence: the lock is held elsewhere (another console, or the
     *     update script's check), or the file cannot be opened / locked (the exception's class only)
     */
    static synchronized String takeConsoleLock(Path pkg, boolean windows) {
        Path run = pkg.resolve(ServeLauncher.RUN_DIR);
        Path file = run.resolve(CONSOLE_LOCK_FILE);
        FileChannel channel;
        try {
            Files.createDirectories(run);
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) { // the sentence is the report
            return "the console lock file " + file + " cannot be opened (" + e.getClass().getSimpleName() + ")";
        }
        FileLock lock;
        String refusal = null;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException heldInThisJvm) { // = held: the busy sentence below reports it
            lock = null;
        } catch (IOException e) {
            lock = null;
            refusal = "the console lock file " + file + " cannot be locked (" + e.getClass().getSimpleName() + ")";
        }
        if (lock == null) {
            closeLockChannel(channel, file);
            return refusal != null ? refusal : consoleBusySentence(windows);
        }
        consoleLockChannel = channel;
        consoleLock = lock;
        return null;
    }

    /** The held-elsewhere sentence of {@link #takeConsoleLock} (the package folder's own names, no path). */
    static String consoleBusySentence(boolean windows) {
        return windows
                ? "another console (or update.bat) holds run\\console.lock for this folder - close it first"
                : "another console (or update.sh) holds run/console.lock for this folder - close it first";
    }

    /**
     * The running console's card ({@link #RUNNING_FILE}): {@code since} = the boot's local {@code yyyy-MM-dd HH:mm},
     * {@code pack} = the folder's package stamp ({@link PackId#stamp}) or {@code -} when the folder is not packed.
     */
    record Running(long pid, int port, String since, String pack) {
        private static final Pattern SINCE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}");

        String text() {
            return "pid=" + pid + "\nport=" + port + "\nsince=" + since + "\npack=" + pack + "\n";
        }

        /** The card's shape, or null: {@code k=v} lines (blank ones skipped), no other key, every value in its
         *  shape (pid &gt; 0, port 0..65535, since as above, pack a {@link #PACK_TOKEN} or {@code -}). */
        static Running parse(String text) {
            long pid = -1;
            int port = -1;
            String since = null;
            String pack = null;
            for (String raw : text.split("\n")) {
                String line = raw.strip();
                if (line.isEmpty()) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    return null;
                }
                String value = line.substring(eq + 1).strip();
                try {
                    switch (line.substring(0, eq)) {
                        case "pid" -> pid = Long.parseLong(value);
                        case "port" -> port = Integer.parseInt(value);
                        case "since" -> since = SINCE.matcher(value).matches() ? value : null;
                        case "pack" -> pack = "-".equals(value) || PACK_TOKEN.matcher(value).matches() ? value : null;
                        default -> {
                            return null;
                        }
                    }
                } catch (NumberFormatException notANumber) {
                    return null;
                }
            }
            return pid > 0 && port >= 0 && port <= 65535 && since != null && pack != null
                    ? new Running(pid, port, since, pack) : null;
        }
    }

    /** Writes this console's card after the boot; a failure = one warning line (the console runs on without it:
     *  a second start then meets the lock's refusal sentence as before). */
    static void writeRunningCard(Path pkg, Boot boot) {
        PackId id = boot.console().packId;
        String since = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        try {
            writeRunning(pkg, new Running(ProcessHandle.current().pid(), boot.port(), since,
                    id == null ? "-" : id.stamp()));
        } catch (IOException | RuntimeException unwritable) {
            System.err.println("console warning: run/" + RUNNING_FILE + " could not be written ("
                    + unwritable.getClass().getSimpleName() + ")");
        }
    }

    static Path writeRunning(Path pkg, Running card) throws IOException {
        Path run = pkg.resolve(ServeLauncher.RUN_DIR);
        Files.createDirectories(run);
        Path file = run.resolve(RUNNING_FILE);
        Files.writeString(file, card.text(), StandardCharsets.US_ASCII);
        return file;
    }

    /** Removes the card (a confirmed Close ops GUI); a failure is one warning line. */
    static void deleteRunning(Path pkg) {
        Path file = pkg.resolve(ServeLauncher.RUN_DIR).resolve(RUNNING_FILE);
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            System.err.println("console warning: " + file + " did not delete (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** The card of a console RUNNING on {@code pkg}: the file read ({@link #RUNNING_FILE_CAP} bytes at most),
     *  parsed, and its pid alive in this OS; else null (no card, a malformed or unreadable one, a dead pid). */
    static Running runningConsole(Path pkg) {
        Path file = pkg.resolve(ServeLauncher.RUN_DIR).resolve(RUNNING_FILE);
        String text;
        try (java.io.InputStream in = Files.newInputStream(file)) {
            text = new String(in.readNBytes(RUNNING_FILE_CAP), StandardCharsets.US_ASCII);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        Running card = Running.parse(text);
        if (card == null) {
            return null;
        }
        return ProcessHandle.of(card.pid()).map(ProcessHandle::isAlive).orElse(false) ? card : null;
    }

    /**
     * A second console on a folder whose console is RUNNING (it leads the user to the console already open):
     * one row names the holder, one sentence says what happens, then the running page
     * is opened the way a fresh start opens it ({@link #printAddress}'s law: a terminal gets the keyed address and
     * ONE browser spawn on Windows; a redirected stdout gets the file hint, never the key). Nothing of the folder
     * is touched: the card and the key are READ, the lock stays the holder's.
     *
     * @return the exit code: 0 = the page opened, or the address was printed (a non-Windows terminal, a redirected
     *     stdout); 2 = a Windows terminal could not open the browser, or found no usable key file - the window then
     *     pauses on the address it printed (ops-web.bat pauses on every non-zero code)
     */
    static int reopenRunning(Path pkg, Running live, boolean tty, boolean windows, BrowserSpawn spawn) {
        System.out.println("cc-console already-running pid=" + live.pid() + " port=" + live.port() + " since="
                + live.since() + " pack=" + live.pack());
        System.out.println("the ops GUI is already running since " + live.since() + " (build " + live.pack()
                + "): opening its page, not a second ops GUI");
        if (!tty) {
            System.out.println("address: see run/" + TOKEN_FILE + " (port " + live.port() + ")");
            return 0;
        }
        KeptKey key = readKeptToken(pkg.resolve(ServeLauncher.RUN_DIR).resolve(TOKEN_FILE));
        if (key.token() == null) {
            System.out.println("address: http://127.0.0.1:" + live.port() + "/ - add # and the key from run/"
                    + TOKEN_FILE + " to its end (that file reads " + key.reason() + ")");
            return 2;
        }
        String url = "http://127.0.0.1:" + live.port() + "/#" + key.token();
        System.out.println("open " + url);
        String result = openBrowser(url, windows, spawn);
        System.out.println("cc-console browser result=" + result);
        return windows && !"opened".equals(result) ? 2 : 0;
    }

    /** Drops this JVM's {@link #consoleLock} (the selftest's cleanup; a console never lets go before it exits). */
    static synchronized void releaseConsoleLock() {
        FileChannel channel = consoleLockChannel;
        consoleLock = null;
        consoleLockChannel = null;
        if (channel != null) {
            closeLockChannel(channel, null);
        }
    }

    private static void closeLockChannel(FileChannel channel, Path file) {
        try {
            channel.close(); // releases its lock, if any
        } catch (IOException e) { // said once; the JVM's exit releases it in any case
            System.err.println("console warning: the console lock file" + (file == null ? "" : " " + file)
                    + " did not close cleanly (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * The folder's package identity - {@code stamp} = the MANIFEST's
     * {@code pack.stamp}, {@code head9} = the first 9 characters of its {@code git.head} (null when absent). Both
     * are shown to the host (the page header, the boot line), so each must be a plain token
     * ({@link #PACK_TOKEN}); anything else is not shown.
     */
    record PackId(String stamp, String head9) {
        /** The boot line: {@code console: package <stamp> (<head9>)}, the parenthesis only with a head. */
        String bootLine() {
            return "console: package " + stamp + (head9 == null ? "" : " (" + head9 + ")");
        }
    }

    /** A MANIFEST meta value fit to show: 1..64 of {@code A-Za-z0-9._-}. */
    static final Pattern PACK_TOKEN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /**
     * {@code <pkg>/MANIFEST.txt}'s {@link PackId} through {@link PackageManifest#read} (its own caps), or null:
     * no manifest (owner mode's roots, a selftest root), no or a blank {@code pack.stamp} (not packed), a stamp
     * that is not a {@link #PACK_TOKEN}, or an unreadable / malformed file (ONE stderr line naming the class).
     */
    static PackId packIdOrNull(Path pkg) {
        Path manifest = pkg.resolve(ServeLauncher.MANIFEST_FILE);
        PackageManifest read;
        try {
            read = PackageManifest.read(manifest);
        } catch (NoSuchFileException absent) { // not a package folder: nothing to show
            return null;
        } catch (IOException | IllegalArgumentException unreadable) {
            System.err.println("console warning: " + manifest + " could not be read for the package line ("
                    + unreadable.getClass().getSimpleName() + ")");
            return null;
        }
        String stamp = read.meta.get(OpsRoots.PACK_STAMP);
        if (stamp == null || !PACK_TOKEN.matcher(stamp.trim()).matches()) {
            return null;
        }
        String head = read.meta.get(PackageManifest.GIT_HEAD);
        String head9 = head == null || !PACK_TOKEN.matcher(head.trim()).matches() ? null
                : head.trim().substring(0, Math.min(9, head.trim().length()));
        return new PackId(stamp.trim(), head9);
    }

    /** This console's boot line ({@link PackId#bootLine}), null on a folder that is not packed. */
    String packLine() {
        return packId == null ? null : packId.bootLine();
    }

    /**
     * The lines after the listening row: a terminal gets the full address and,
     * on Windows, ONE browser spawn of it ({@link #openBrowser}; its result row names no key); a
     * redirected stdout gets only the file hint - no address, no spawn (rigs, legs, {@code nohup},
     * a service). {@code tty} / {@code windows} / {@code spawn} are parameters so the selftest's
     * {@code web-browser-open} row drives every branch without a terminal or a process.
     */
    static void printAddress(Boot boot, boolean tty, boolean windows, BrowserSpawn spawn) {
        if (!tty) {
            System.out.println("address: see run/" + TOKEN_FILE);
            return;
        }
        String url = "http://127.0.0.1:" + boot.port() + "/#" + boot.token();
        System.out.println("open " + url);
        System.out.println("cc-console browser result=" + openBrowser(url, windows, spawn));
    }

    /** The browser spawn seam: {@link #LIVE} starts the process; the selftest records the argv. */
    @FunctionalInterface
    interface BrowserSpawn {
        void start(List<String> argv) throws IOException;

        /** Output discarded, stdin closed, never waited for ({@code start} returns at once). */
        BrowserSpawn LIVE = argv -> {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            p.getOutputStream().close();
        };
    }

    /**
     * Opens {@code url} in the default browser on Windows (its design lens:
     * {@code java.awt.Desktop} is dead here because {@link #main} forces {@code java.awt.headless}
     * on every platform, and that law stays). The spawn is {@link #browserCommand}'s fixed argv
     * through {@code spawn}; every throw = {@code failed:<SimpleName>} (a message could carry the
     * url, which carries the key). Off Windows = {@code unsupported}: Linux prints the address as
     * before ({@code ops-web.sh} runs under xvfb; a forwarded display must never be opened by
     * accident). Residual: the url rides the browser process's command line for its life, readable
     * by other local accounts in a process listing - on Windows those already read
     * {@code run/console.token} (no ACL), so no new reader class.
     *
     * @return {@code opened} | {@code unsupported} | {@code failed:<SimpleName>} - bounded tokens
     */
    static String openBrowser(String url, boolean windows, BrowserSpawn spawn) {
        if (!windows) {
            return "unsupported";
        }
        try {
            String root = System.getenv("SystemRoot");
            spawn.start(browserCommand(root == null || root.isBlank() ? "C:\\Windows" : root, url));
            return "opened";
        } catch (Throwable failed) { // the result row names the class only
            return "failed:" + failed.getClass().getSimpleName();
        }
    }

    /**
     * The FIXED argv (the {@link OpsJoinSelf#argv} idiom): {@code <systemRoot>\System32\cmd.exe
     * /d /c start "" <url>} - the empty quoted title element keeps {@code start} from reading the url
     * as a window title, {@code /d} skips AutoRun, and the url is a plain element: its only
     * characters are the address's and the key's ({@code A-Za-z0-9_-}, {@code :/.#}), none of which
     * {@code cmd} treats specially, so it is never quoted (a quoted url would reach ShellExecute
     * with its quotes on some {@code start} versions).
     */
    static List<String> browserCommand(String systemRoot, String url) {
        return List.of(systemRoot + "\\System32\\cmd.exe", "/d", "/c", "start", "\"\"", url);
    }

    /** {@link #boot(OpsRoots, int, ServerOpsCore.SpawnFn, KillFn, VpnLeaseProbe.Env)} on the real
     * ProtonVPN gateway ({@link VpnLeaseProbe.Env#live}; building it reads an env var, never the network). */
    static Boot boot(OpsRoots roots, int port, ServerOpsCore.SpawnFn spawn, KillFn kill) throws IOException {
        return boot(roots, port, spawn, kill, VpnLeaseProbe.Env.live());
    }

    /** {@link #boot(OpsRoots, int, ServerOpsCore.SpawnFn, KillFn,
     * VpnLeaseProbe.Env, OpsJoinSelf.Effects)} with the real Join from this PC seams
     * ({@link OpsJoinSelf#LIVE}: files, one loopback ping, one {@code cmd.exe} spawn). */
    static Boot boot(OpsRoots roots, int port, ServerOpsCore.SpawnFn spawn, KillFn kill,
            VpnLeaseProbe.Env leaseEnv) throws IOException {
        return boot(roots, port, spawn, kill, leaseEnv, OpsJoinSelf.LIVE);
    }

    /** {@link #boot(OpsRoots, int, ServerOpsCore.SpawnFn, KillFn, VpnLeaseProbe.Env, OpsJoinSelf.Effects,
     * OwnerLaunch, Path)} with the real owner spawns and no follow seed (the roots' own status file). */
    static Boot boot(OpsRoots roots, int port, ServerOpsCore.SpawnFn spawn, KillFn kill,
            VpnLeaseProbe.Env leaseEnv, OpsJoinSelf.Effects joinFx) throws IOException {
        return boot(roots, port, spawn, kill, leaseEnv, joinFx, OwnerLaunch.LIVE, null);
    }

    /**
     * The ONE boot path (main and the selftest's HTTP rows): the 30 s request clock BEFORE the
     * server class loads its config, a loopback bind, then the key - {@code <pkg>/run/console.token}
     * KEPT when it passes {@link #readKeptToken} (the key survives a console
     * restart, so an open tab keeps working), else minted fresh and written there (only once the
     * bind succeeded) - ONE stdout row {@code cc-console key=kept} | {@code key=new reason=<token>}
     * (never the key), the twenty-two exact-path contexts, a 4-thread {@code cc-console} pool, the
     * server started.
     * {@code leaseEnv} = what Fill from ProtonVPN and the
     * in-mode Start / brick re-ask pull from - the selftest points it at a loopback responder.
     * {@code joinFx} = Join from this PC's seams
     * ({@code POST /api/join}) - the selftest fakes the spawn, the ping and the env, so the rig never
     * runs {@code cmd}.
     * {@code ownerLaunch} = owner mode's Dev editor / Joiner GUI
     * spawns (the selftest fakes them); {@code followSeed} = the status file {@link #followed} starts on
     * (null = {@link OpsRoots#opsFile}) - handed to the constructor, so it holds before the server starts.
     */
    static Boot boot(OpsRoots roots, int port, ServerOpsCore.SpawnFn spawn, KillFn kill,
            VpnLeaseProbe.Env leaseEnv, OpsJoinSelf.Effects joinFx, OwnerLaunch ownerLaunch, Path followSeed)
            throws IOException {
        System.setProperty("sun.net.httpserver.maxReqTime", String.valueOf(REQUEST_CLOCK_S));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        Path keptFile = roots.pkg().resolve(ServeLauncher.RUN_DIR).resolve(TOKEN_FILE);
        KeptKey kept = readKeptToken(keptFile);
        String token = kept.token();
        if (token == null) {
            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        }
        OpsConsole console = new OpsConsole(roots,
                Paths.get(System.getProperty("cc.ops.briefFile", ServerOpsCore.BRIEF_FILE)), 50.0, token,
                spawn, kill, leaseEnv, joinFx, ownerLaunch, followSeed);
        console.register(server);
        ExecutorService pool = Executors.newFixedThreadPool(4, r -> new Thread(r, "cc-console"));
        server.setExecutor(pool);
        Path tokenFile = keptFile;
        if (kept.token() == null) {
            try {
                tokenFile = writeToken(roots.pkg(), token);
            } catch (IOException | RuntimeException unwritable) { // rethrown: no console without its token file
                server.stop(0);
                pool.shutdownNow();
                throw unwritable;
            }
        }
        System.out.println(kept.token() != null ? "cc-console key=kept" : "cc-console key=new reason=" + kept.reason());
        server.start();
        return new Boot(server, console, token, tokenFile);
    }

    /** The boot's read of the key file: {@code token} = the kept key, or {@code null} with the
     * {@code reason} token the stdout row prints ({@code kept} when a key is held). */
    record KeptKey(String token, String reason) {
    }

    /**
     * The reuse law (the owner check is its design lens's (d) fold):
     * the file is KEPT iff it is a regular file reached without following a link, at most
     * {@value #TOKEN_FILE_CAP} bytes, holding one {@link #TOKEN_SHAPE} line (one trailing line break,
     * CRLF or LF, allowed), and on a POSIX fs EXACTLY {@code rw-------} AND owned by this account
     * (a root console must not adopt a {@code 0600} file another account planted in a writable
     * {@code run/}; a loosened file is treated as burnt). Anything else, a throw included, is a
     * reason token for a fresh key: {@code absent} | {@code not_file} (a link, a folder) |
     * {@code malformed} | {@code loose_perms} | {@code not_owner} | {@code unreadable} - never the
     * file's content. On Windows (no POSIX view) the mode and owner checks are skipped: the
     * package folder's readers already read the file (the README's shared-computer sentence). Every
     * copy of the file is a live key until the console is re-keyed (stop, delete it, start).
     */
    static KeptKey readKeptToken(Path file) {
        try {
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException absent) {
                return new KeptKey(null, "absent");
            }
            if (!attrs.isRegularFile()) {
                return new KeptKey(null, "not_file");
            }
            if (attrs.size() > TOKEN_FILE_CAP) {
                return new KeptKey(null, "malformed");
            }
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                PosixFileAttributes posix = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!"rw-------".equals(PosixFilePermissions.toString(posix.permissions()))) {
                    return new KeptKey(null, "loose_perms");
                }
                UserPrincipal me = file.getFileSystem().getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name"));
                if (!me.equals(posix.owner())) {
                    return new KeptKey(null, "not_owner");
                }
            }
            byte[] bytes;
            try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes(TOKEN_FILE_CAP + 1);
            }
            String text = new String(bytes, StandardCharsets.US_ASCII);
            if (text.endsWith("\r\n")) {
                text = text.substring(0, text.length() - 2);
            } else if (text.endsWith("\n")) {
                text = text.substring(0, text.length() - 1);
            }
            return text.matches(TOKEN_SHAPE) ? new KeptKey(text, "kept") : new KeptKey(null, "malformed");
        } catch (IOException | RuntimeException unreadable) { // a lookup / read failure = a fresh key
            return new KeptKey(null, "unreadable");
        }
    }

    /** Writes {@code <pkg>/run/console.token} for a NEW key: the old file (or a planted link) is
     * removed, a POSIX fs creates the new one {@code rw-------} BEFORE the token is written into it. */
    static Path writeToken(Path pkg, String token) throws IOException {
        Path run = pkg.resolve(ServeLauncher.RUN_DIR);
        Files.createDirectories(run);
        Path file = run.resolve(TOKEN_FILE);
        Files.deleteIfExists(file);
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        OpenOption[] opts = posix
                ? new OpenOption[] {StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS}
                : new OpenOption[] {StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS};
        Files.write(file, token.getBytes(StandardCharsets.US_ASCII), opts);
        return file;
    }

    /** A real terminal? {@code System.console() != null}, and on a JDK 22+ (whose Console exists
     * even when stdout is redirected) also {@code isTerminal()}; a failed probe = not a terminal. */
    static boolean interactiveConsole() {
        Console c = System.console();
        if (c == null) {
            return false;
        }
        for (Method m : Console.class.getMethods()) {
            if ("isTerminal".equals(m.getName()) && m.getParameterCount() == 0) {
                try {
                    return Boolean.TRUE.equals(m.invoke(c));
                } catch (ReflectiveOperationException | RuntimeException probeFailed) { // fail-closed: the file hint
                    System.out.println("cc-console tty_probe_failed class=" + probeFailed.getClass().getSimpleName());
                    return false;
                }
            }
        }
        return true;
    }

    // ---- the guard funnel ----------------------------------------------------------------------

    private void register(HttpServer server) {
        server.createContext("/", guarded("/", Set.of("GET"), false,
                ex -> respond(ex, 200, HTML, OpsConsolePage.html())));
        server.createContext("/ops.css", guarded("/ops.css", Set.of("GET"), false,
                ex -> respond(ex, 200, CSS, OpsConsolePage.css())));
        server.createContext("/ops.js", guarded("/ops.js", Set.of("GET"), false,
                ex -> respond(ex, 200, JS, OpsConsolePage.js())));
        server.createContext("/api/status", guarded("/api/status", Set.of("GET"), true, this::handleStatus));
        server.createContext("/api/config", guarded("/api/config", Set.of("GET"), true, this::handleConfig));
        server.createContext("/api/match-settings", guarded("/api/match-settings", Set.of("POST"), true, this::handleMatchSettings));
        server.createContext("/api/draft", guarded("/api/draft", Set.of("POST"), true, this::handleDraft));
        server.createContext("/api/shape-new", guarded("/api/shape-new", Set.of("POST"), true, this::handleShapeNew));
        server.createContext("/api/start", guarded("/api/start", Set.of("POST"), true, this::handleStart));
        server.createContext("/api/watch", guarded("/api/watch", Set.of("POST"), true, this::handleWatch));
        server.createContext("/api/reveal", guarded("/api/reveal", Set.of("POST"), true, this::handleReveal));
        server.createContext("/api/brick", guarded("/api/brick", Set.of("POST"), true, this::handleBrick));
        server.createContext("/api/join", guarded("/api/join", Set.of("POST"), true, this::handleJoin));
        server.createContext("/api/dev-editor", guarded("/api/dev-editor", Set.of("POST"), true, this::handleDevEditor));
        server.createContext("/api/lease", guarded("/api/lease", Set.of("POST"), true, this::handleLease));
        server.createContext("/api/reach", guarded("/api/reach", Set.of("POST"), true, this::handleReach));
        server.createContext("/api/verb", guarded("/api/verb", Set.of("POST"), true, this::handleVerb));
        server.createContext("/api/kill", guarded("/api/kill", Set.of("POST"), true, this::handleKill));
        server.createContext("/api/voice", guarded("/api/voice", Set.of("GET", "POST"), true, this::handleVoice));
        server.createContext("/api/mods", guarded("/api/mods", Set.of("GET", "POST"), true, this::handleMods));
        server.createContext("/api/quit", guarded("/api/quit", Set.of("POST"), true, this::handleQuit));
    }

    /**
     * Every request's funnel, in order: the EXACT path (else 404 - the JDK context is a prefix
     * match), the localhost {@code Host} guard (else 403), the token on {@code /api/*} (else 403
     * {@value #REFUSED_TOKEN}; BEFORE the method, so a caller without the key learns no route's
     * methods), the method (else 405); a handler throw = 500 {@value #INTERNAL_ERROR} + one
     * {@code cc-console error class=<SimpleName>} row.
     */
    private HttpHandler guarded(String path, Set<String> methods, boolean api, Route route) {
        return ex -> {
            try {
                if (!path.equals(ex.getRequestURI().getPath())) {
                    respond(ex, 404, TEXT, "404");
                    return;
                }
                String method = ex.getRequestMethod();
                if (!ServerOpsCore.webRequestAllowed(method, ex.getRequestHeaders().getFirst("Host"), true)) {
                    respond(ex, 403, JSON, REFUSED_HOST);
                    return;
                }
                if (api && !tokenMatches(ex.getRequestHeaders().getFirst(TOKEN_HEADER))) {
                    respond(ex, 403, JSON, REFUSED_TOKEN);
                    return;
                }
                if (!methods.contains(method)) {
                    respond(ex, 405, TEXT, "405");
                    return;
                }
                route.handle(ex);
            } catch (Exception unexpected) { // the fixed 500 + its one row (no message, no stack)
                System.out.println("cc-console error class=" + unexpected.getClass().getSimpleName());
                try {
                    respond(ex, 500, TEXT, INTERNAL_ERROR);
                } catch (IOException replyFailed) { // headers already sent / client gone
                    System.out.println("cc-console error_reply_failed class=" + replyFailed.getClass().getSimpleName());
                }
            } finally {
                ex.close();
            }
        };
    }

    /** The server's bytes FIRST; an absent header compares as empty. */
    private boolean tokenMatches(String header) {
        return MessageDigest.isEqual(token, header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", contentType);
        h.set("Cache-Control", "no-store");
        h.set("X-Frame-Options", "DENY");
        h.set("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'");
        h.set("Referrer-Policy", "no-referrer");
        ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** The body (UTF-8), or {@code null} after answering 413 past {@value #BODY_CAP} bytes - read
     * bounded ({@code readNBytes}), never trusting a {@code Content-Length}. */
    private static String readBody(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(BODY_CAP + 1);
        if (b.length > BODY_CAP) {
            respond(ex, 413, JSON, TOO_LARGE);
            return null;
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    /** The decoded form, or {@code null} once answered (413, or {@code errorJson(title, …)} for a
     * malformed body). */
    private static Map<String, List<String>> readForm(HttpExchange ex, String title) throws IOException {
        String body = readBody(ex);
        if (body == null) {
            return null;
        }
        try {
            return OpsConsoleForms.decodeForm(body);
        } catch (IllegalArgumentException malformed) { // answered with the fixed refusal text
            respond(ex, 200, JSON, OpsConsoleForms.errorJson(title, malformed.getMessage()));
            return null;
        }
    }

    // ---- shared state reads --------------------------------------------------------------------

    /** The snapshot of {@code opsFile} (the file of a request's ONE read of {@link #followed}), or null. */
    private static OpsSnapshot readSnapshotOrNull(Path opsFile) {
        if (!Files.isRegularFile(opsFile)) {
            return null;
        }
        try {
            return OpsSnapshot.parseFile(opsFile);
        } catch (IOException | RuntimeException unreadable) { // a half-written file = no snapshot this request
            return null;
        }
    }

    /** The one state derivation: liveness + died-on-watch (the writer watch of {@code f}, the follow
     * {@code snap} was read from) + phase/staleness. */
    private static ServerState deriveTracked(Follow f, OpsSnapshot snap, boolean pidAlive, long now) {
        return snap == null ? ServerState.STOPPED
                : ServerOpsCore.deriveState(snap.phase(), snap.isStale(now, ServerOpsCore.STALE_THRESHOLD_MS),
                        pidAlive, f.writerWatch().observe(snap, pidAlive));
    }

    /** The CMD gate (the Swing window's, in {@link OpsWindowModel#apply}): a live server with a command file;
     * {@code snap} = the snapshot of {@code f}'s file. */
    private static boolean commandable(Follow f, OpsSnapshot snap) {
        if (snap == null) {
            return false;
        }
        boolean alive = ServerOpsCore.writerAlive(snap);
        return ServerOpsCore.liveServer(deriveTracked(f, snap, alive, System.currentTimeMillis()))
                && !"off".equals(snap.commandFile());
    }

    /** Keeps {@code seats} as the last run's rows of the follow
     * {@code cur} a status read took - dropped when a swap replaced it since (they describe the old file); two
     * polls on the same follow each store a whole list (the writer watch's identity = the swap generation). */
    private void rememberSeats(Follow cur, List<OpsSnapshot.SeatRow> seats) {
        followed.updateAndGet(f -> f.writerWatch() == cur.writerWatch() ? f.withSeats(seats) : f);
    }

    /** Is {@code f} the WATCH lane - owner mode following the watch's
     * own status file ({@code seams.watchOps()})? Both sides absolute + normalized, so a console seeded with the
     * watch file by any spelling of it counts. Package mode: never. */
    private boolean watchLane(Follow f, OwnerWatch seams) {
        return roots.mode() == OpsRoots.Mode.OWNER && f.opsFile().toAbsolutePath().normalize()
                .equals(seams.watchOps().toAbsolutePath().normalize());
    }

    private List<String> propsLines() throws IOException {
        return Files.isRegularFile(propsFile) ? Files.readAllLines(propsFile, StandardCharsets.UTF_8) : List.of();
    }

    /** Is this console's adopted launcher (the stage, then the launcher the chain swapped in)
     * alive? ({@code OpsWindowModel.launcherAlive}'s twin.) */
    private boolean launcherUp() {
        LaneFollow.LaneLaunch l = startLaunch.get();
        Process p = l == null || l.current() == null ? null : l.current().get();
        return p != null && p.isAlive();
    }

    // ---- GET /api/status -----------------------------------------------------------------------

    /**
     * The July status shape ({@link ServerOpsCore#statusJson}) + the spliced keys before its
     * closing brace: {@code launch} (this console's last Start, {@link LaneFollow#launchJson}),
     * {@code brief} ({@code {title, body[]}} or null), {@code failId}, {@code staging},
     * {@code launcherUp}, {@code seatsRemembered} (the rows served are the last run's) and
     * {@code meters} (the snapshot's raw numbers, null without one).
     *
     * <p>{@code reach} = the console's last finished
     * reachability dial ({@link ServerOpsCore#reachJson} of {@link ServerOpsCore#LAST_REACH}: verdict, ms,
     * note, at) or null. REPORT only: this GET never dials (row {@code web-reach}).
     *
     * <p>{@code watchNote} = {@link #watchNote} through the console's JSON
     * escape ({@code ""} when none); the page notes a new non-empty sentence once (row {@code web-watch-note}).
     *
     * <p>{@code watchLane} (owner mode ONLY; a package answer never
     * carries it) = {@link #watchLane}: the console follows the Arm WATCH launch's own status file, where Kill is the
     * force-kill of the pid that file names.
     */
    private void handleStatus(HttpExchange ex) throws IOException {
        Map<String, List<String>> query;
        try {
            query = OpsConsoleForms.decodeForm(ex.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException malformed) { // answered: the page shows it on the detail line
            respond(ex, 200, JSON, OpsConsoleForms.errorJson("Status refused", malformed.getMessage()));
            return;
        }
        OpsSnapshot snap = null;
        String problem = null;
        Follow cur = followed.get(); // ONE read: this answer describes one file
        Path opsFile = cur.opsFile();
        if (Files.isRegularFile(opsFile)) {
            try {
                snap = OpsSnapshot.parseFile(opsFile);
            } catch (IOException | RuntimeException e) { // surfaced on the detail line (statusJson)
                problem = e.getMessage();
            }
        }
        long now = System.currentTimeMillis();
        boolean pidAlive = snap != null && ServerOpsCore.writerAlive(snap);
        boolean diedOnWatch = snap != null && cur.writerWatch().observe(snap, pidAlive);
        ServerState state = snap == null ? ServerState.STOPPED
                : ServerOpsCore.deriveState(snap.phase(), snap.isStale(now, ServerOpsCore.STALE_THRESHOLD_MS),
                        pidAlive, diedOnWatch);
        List<OpsSnapshot.SeatRow> lastRun = cur.lastRunSeats();
        if (snap != null && ServerOpsCore.rememberRunSeats(snap)) {
            lastRun = snap.seats();
            rememberSeats(cur, lastRun);
        }
        String uplinkParam = OpsConsoleForms.first(query, "uplink");
        double uplink = uplinkParam == null ? uplinkDefault : ServerOpsCore.uplinkOrZero(uplinkParam);
        String launch = launchJsonOrNull(snap, pidAlive, now);
        // ServerOpsCore.displaySeatRows: an EXITED (match over) empty table shows the last run's rows
        boolean seatsRemembered = snap != null && state == ServerState.EXITED && snap.seats().isEmpty()
                && !lastRun.isEmpty();
        String status = ServerOpsCore.statusJson(snap, problem, opsFile.toString(), pidAlive, diedOnWatch,
                uplink, now, lastRun);
        if (!status.endsWith("}")) {
            throw new IllegalStateException("status JSON does not end with '}'");
        }
        respond(ex, 200, JSON, status.substring(0, status.length() - 1)
                + ",\"launch\":" + (launch == null ? "null" : launch)
                + ",\"brief\":" + briefJson()
                + ",\"failId\":" + failId.get()
                + ",\"staging\":" + ServerOpsLaunch.STAGING.get()
                + ",\"launcherUp\":" + launcherUp()
                + ",\"seatsRemembered\":" + seatsRemembered
                + ",\"reach\":" + ServerOpsCore.reachJson(ServerOpsCore.LAST_REACH.get())
                + ",\"watchNote\":\"" + OpsConsoleForms.jsonEscape(watchNote.get()) + "\""
                + (packId == null ? "" : ",\"pack\":\"" + OpsConsoleForms.jsonEscape(packId.stamp()) + "\"")
                + (roots.mode() == OpsRoots.Mode.OWNER ? ",\"watchLane\":" + watchLane(cur, ownerWatch) : "")
                + ",\"meters\":" + metersJson(snap) + "}");
    }

    /** This console's last Start as {@link LaneFollow#launchJson}, or {@code null} (none / consumed
     * by LIVE); a launch that turns terminal FAILED / REFUSED bumps {@link #failId} once. */
    private String launchJsonOrNull(OpsSnapshot snap, boolean pidAlive, long now) {
        LaneFollow.LaneLaunch launch = startLaunch.get();
        if (launch == null || launch == liveLaunch.get()) {
            return null;
        }
        LaneFollow.LaunchPhase phase = LaneFollow.launchPhase(launch, snap, pidAlive, now,
                launch.exitCodeOrNull(), ServerOpsLaunch.STAGING.get());
        if (phase == LaneFollow.LaunchPhase.LIVE) {
            liveLaunch.set(launch);
            return null;
        }
        LaneFollow.TerminalRow term = LaneFollow.terminalRow(launch.flightPointer(), launch.pressedAtMs());
        LaneFollow.Terminal kind = term == null ? null : term.kind();
        // launchJson's own branch order: CLEAN_EXIT / STOPPED are no failure, PENDING is not terminal
        boolean failed = kind == LaneFollow.Terminal.REFUSED_PRELAUNCH
                || ((phase == LaneFollow.LaunchPhase.FAILED || phase == LaneFollow.LaunchPhase.TIMED_OUT)
                        && kind != LaneFollow.Terminal.CLEAN_EXIT && kind != LaneFollow.Terminal.STOPPED);
        LaneFollow.LaneLaunch counted = failedLaunch.get();
        if (failed && counted != launch && failedLaunch.compareAndSet(counted, launch)) {
            failId.incrementAndGet();
        }
        return LaneFollow.launchJson(launch, phase, LaneFollow.lifeFailure(launch.flightPointer(),
                launch.pressedAtMs()), term, now);
    }

    /** {@code {"title":…,"body":[rows]}} from the brief file (the Swing window's source and its
     * {@link ServerOpsCore#briefBullets} rows), {@code null} without one; a half-written read
     * keeps the last answer (Swing's {@code keepBrief}). */
    private String briefJson() {
        if (!Files.isRegularFile(briefFile)) {
            lastBriefJson.set("null");
            return "null";
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(briefFile, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException halfWritten) { // logged once per console; the last brief stays
            if (briefFailLogged.compareAndSet(false, true)) {
                System.out.println("cc-console brief_unreadable class=" + halfWritten.getClass().getSimpleName());
            }
            return lastBriefJson.get();
        }
        ServerOpsCore.OwnerBrief brief = ServerOpsCore.parseBrief(lines);
        String json;
        if (brief == null) {
            json = "null";
        } else {
            String bullets = ServerOpsCore.briefBullets(brief.body());
            StringBuilder sb = new StringBuilder("{\"title\":\"")
                    .append(OpsConsoleForms.jsonEscape(ServerOpsCore.briefTitle(brief))).append("\",\"body\":[");
            boolean firstRow = true;
            for (String row : bullets == null || bullets.isEmpty() ? new String[0] : bullets.split("\n")) {
                sb.append(firstRow ? "" : ",").append('"').append(OpsConsoleForms.jsonEscape(row)).append('"');
                firstRow = false;
            }
            json = sb.append("]}").toString();
        }
        lastBriefJson.set(json);
        return json;
    }

    /** The snapshot's raw meters ({@link OpsSnapshot}), {@code null} without a snapshot; a
     * non-finite double is {@code null} (never an invalid JSON token). */
    private static String metersJson(OpsSnapshot snap) {
        if (snap == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(256)
                .append("{\"tickPerS\":").append(num(snap.tickPerS()))
                .append(",\"utilPct\":").append(num(snap.utilPct()))
                .append(",\"workMaxMs\":").append(num(snap.workMaxMs()))
                .append(",\"overrunFrames\":").append(snap.overrunFrames())
                .append(",\"sentBps\":").append(snap.sentBps())
                .append(",\"recvBps\":").append(snap.recvBps())
                .append(",\"bytesSentTotal\":").append(snap.bytesSentTotal())
                .append(",\"bytesRecvTotal\":").append(snap.bytesRecvTotal())
                .append(",\"sendFailures\":").append(snap.sendFailures())
                .append(",\"phaseFrames\":").append(snap.phaseFrames())
                .append(",\"phases\":{");
        boolean firstPhase = true;
        for (OpsSnapshot.PhaseStat p : snap.phases()) {
            sb.append(firstPhase ? "" : ",").append('"').append(OpsConsoleForms.jsonEscape(p.name()))
                    .append("\":{\"meanMs\":").append(num(p.meanMs())).append(",\"p95Ms\":")
                    .append(num(p.p95Ms())).append('}');
            firstPhase = false;
        }
        return sb.append("}}").toString();
    }

    private static String num(double v) {
        return Double.isFinite(v) ? Double.toString(v) : "null";
    }

    // ---- POST /api/start + POST /api/watch -----------------------------------------------------

    /**
     * Start: the form -&gt; the picked descriptor + its info (re-parsed beside the config, the
     * July idiom) -&gt; the KEEP text resolved server-side from the PICKED option's sidecar, never
     * serialized -&gt; {@link OpsConsoleForms#startFields} -&gt; the package bind
     * ({@link PackageBind#resolve}) -&gt; under the start lock the live-launcher refusal and
     * {@link ServerOpsLaunch#runStartFlow} behind a collecting Ui (a refused confirm = one
     * {@code needsConfirm}; every confirm is asked before the first write).
     *
     * <p>In the ProtonVPN mode ({@link ServerOpsLaunch#protonMode}
     * over the saved launcher rows and the form's {@code bindHost} + {@code advertise} - the page sends
     * Advertise for this compare only, it is never bound) the bind step asks the gateway again first
     * ({@link #sharedProbe}, outside the start lock): an answer is saved
     * ({@link ServerOpsLaunch#writeFillRows}) and binds through
     * {@link ServerOpsLaunch#resolveStartBind} in {@link ServerOpsLaunch.BindWords#PACKAGE} words (a
     * blank port: {@link PackageBind#resolve}), and the ok answer carries {@code "fill"}; a silent
     * gateway binds the typed pair through {@link PackageBind#resolve} and the detail gains
     * {@link VpnLeaseProbe#NOTE_START_UNCHECKED}. Out of the mode, a saved {@code protonvpn} row turns
     * {@code typed} once the bind resolves (row {@code web-start-lease}).
     *
     * <p>A STARTED press in the mode then arms the
     * auto reachability check ({@link ServerOpsCore#armReachCheck}, holder = {@link #startLaunch}) before
     * the answer is composed; the answer never waits on it (row {@code web-start-arms-reach}).
     */
    private void handleStart(HttpExchange ex) throws IOException {
        startFlow(ex, false);
    }

    /**
     * Launch debug WATCH ({@code POST /api/watch}): a Start whose fields carry {@code debugWatch} (the flow writes
     * the debug posture row {@code true}; a plain Start writes it {@code false}), then the daemon that presses Join
     * from this PC once the door opens. Shown on the page only where Join from this PC is ({@code joinHere}): on a
     * non-Windows console a hand-made POST gets {@link OpsJoinSelf#R9} in the join POST's shape BEFORE any Start
     * runs - a watch without a join window would be a plain Start (rows {@code web-watch-route},
     * {@code web-watch-linux}).
     *
     * <p>In owner mode the route is the Swing owner button instead -
     * {@link #pressOwnerWatch}, before the {@code joinHere} test; the package body is unchanged.
     */
    private void handleWatch(HttpExchange ex) throws IOException {
        if (roots.mode() == OpsRoots.Mode.OWNER) {
            pressOwnerWatch(ex);
            return;
        }
        if (!ServerOpsCore.joinHere(System.getProperty("os.name"))) {
            respond(ex, 200, JSON, joinRefused("r9", OpsJoinSelf.R9));
            return;
        }
        startFlow(ex, true);
    }

    /** The Arm WATCH press's refusal title (owner mode). */
    static final String OWNER_WATCH_TITLE = "Arm WATCH refused";

    /**
     * The Arm WATCH press ({@code POST /api/watch} in owner mode) =
     * the Swing owner button: {@link ServerOpsLaunch#runWatchFlow(ServerOpsCore.Ui, ServerOpsCore.SpawnFn,
     * ServerOpsCore.OwnerBrief, Path)} with the brief THIS console shows forwarded - read from the server's
     * brief file ({@link OwnerWatch#briefFileOrNull}, else {@link #briefFile}), never from the request: no
     * field of the body is read - under the Start's own STOPPED-only gate ({@link #liveLaunchRefusal}).
     * Invariant: the spawn is {@code watchCommand(forward)}'s FIXED argv, whose one variable is a file this JVM
     * wrote from the brief FILE, so no request byte reaches a process. An armed press REGISTERS the launch in
     * {@link #startLaunch} (the page holds STARTING instead of showing the last watch's leftover snapshot as
     * current, Kill knows its launcher, a second press is refused while it lives) and swaps {@link #followed}
     * to the watch's own status file with a fresh {@link Follow} - the Swing press does both. The answer = the
     * core's own info line, or its fail text; ONE stdout row {@code cc-console watch
     * result=armed|refused:live|fault}, plus {@code cc-console watch brief_unreadable class=<SimpleName>} when
     * the brief path exists but cannot be read (the press then arms brief-less).
     */
    private void pressOwnerWatch(HttpExchange ex) throws IOException {
        if (readForm(ex, OWNER_WATCH_TITLE) == null) { // the body cap; NO field is read
            return;
        }
        OwnerWatch seams = ownerWatch;
        Path briefSrc = seams.briefFileOrNull() != null ? seams.briefFileOrNull() : briefFile;
        ServerOpsCore.OwnerBrief brief = null; // the brief this console shows (briefJson's source)
        if (Files.exists(briefSrc)) {
            try {
                brief = ServerOpsCore.parseBrief(Files.readAllLines(briefSrc, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException unreadable) { // one bounded row; the press arms brief-less
                System.out.println("cc-console watch brief_unreadable class=" + unreadable.getClass().getSimpleName());
            }
        }
        Path watchOps = seams.watchOps().toAbsolutePath().normalize();
        CollectingUi ui = new CollectingUi(List.of());
        AtomicReference<Process> spawned = new AtomicReference<>();
        long pressedAtMs = System.currentTimeMillis();
        String live;
        boolean armed = false;
        synchronized (startLock) {
            live = liveLaunchRefusal(startLaunch.get());
            if (live == null) {
                armed = ServerOpsLaunch.runWatchFlow(ui, cmd -> {
                    Process p = seams.spawn().spawn(cmd);
                    spawned.set(p);
                    return p;
                }, brief, seams.forwardDir());
                if (armed) { // the Swing press: register the launch, then follow its status file
                    startLaunch.set(new LaneFollow.LaneLaunch(ServerOpsCore.WATCH_LANE, watchOps,
                            ServerOpsCore.WATCH_LOG, pressedAtMs, spawned.get()));
                    followed.set(Follow.fresh(watchOps));
                }
            }
        }
        if (live != null) {
            System.out.println("cc-console watch result=refused:live");
            respond(ex, 200, JSON, OpsConsoleForms.errorJson(OWNER_WATCH_TITLE, live));
            return;
        }
        System.out.println("cc-console watch result=" + (armed ? "armed" : "fault"));
        respond(ex, 200, JSON, armed ? OpsConsoleForms.okJson(ui.infoMessage)
                : OpsConsoleForms.errorJson(ui.errorTitle, ui.errorMessage));
    }

    /**
     * The ONE web Start body, for Start ({@code debugWatch} false) and Launch debug WATCH ({@code true}): the
     * flow described on {@link #handleStart}, with {@code debugWatch} carried into the fields. An accepted Start
     * clears {@link #watchNote} (either route); a debug-watch one runs the second-folder check
     * ({@link OpsJoinSelf#secondFolderRefusalOrNull}) and the kit check FIRST ({@link #watchKitCheckOrNull}, both
     * before the lock: a refused check is the Start's refusal, nothing spawned), then arms {@link ServerOpsCore#armWatchJoin}
     * beside the reach check (holder = {@link #startLaunch}), whose press = {@link #pressJoinOnce} in its WATCH shape
     * with this launch's flight folder resolved AT PRESS TIME (the flight pointer may not exist yet when the daemon
     * is armed; {@link OpsJoinSelf#pressWatch} places each window's diagnostics under it) and whose notes land in
     * {@link #watchNote}; and the lease daemon {@link ServerOpsCore#armWatchLease} (same holder), whose END leg scrubs
     * the join settings' password rows ({@link #scrubJoinSettings}) and then closes the join windows this launch's
     * press opened ({@link JoinWindowSweep#endLeg}). The arms' booleans are not an answer: the Start
     * already started.
     */
    private void startFlow(HttpExchange ex, boolean debugWatch) throws IOException {
        Map<String, List<String>> form = readForm(ex, "Start refused");
        if (form == null) {
            return;
        }
        String descriptor = OpsConsoleForms.first(form, "descriptor");
        if (descriptor != null && descriptor.isBlank()) {
            descriptor = null; // boot the row as-is (the null-path selector entry)
        }
        DescriptorInfo selInfo = null;
        if (descriptor != null) {
            try {
                // ONE bounded NOFOLLOW read (the package readers' cap);
                // an over-cap file is unreadable here (row web-start-descriptor-overcap)
                selInfo = ServerOpsCore.parseDescriptorInfo(ServerOpsCore.readCappedLines(
                        ServerOpsCore.configRelative(propsFile, descriptor)));
            } catch (IOException | RuntimeException notBootable) { // the flow refuses it with its own text
                System.out.println("cc-console descriptor_unreadable class=" + notBootable.getClass().getSimpleName());
            }
        }
        String passwordAction = OpsConsoleForms.first(form, "passwordAction");
        // The keep READ moved under startLock
        // (below, beside the flow it feeds); the fields are validated here with no kept text ("" for keep)
        boolean keepsPassword = passwordAction == null || "keep".equals(passwordAction);
        OpsWindowModel.StartFields f;
        ServerOpsLaunch.StartBind bind;
        VpnLeaseProbe.Fill fill = null;
        String fallback = null;
        Path launcherFile = ServerOpsLaunch.launcherPropertiesFile();
        String recordRow;
        boolean inMode; // the press's ProtonVPN mode: also the auto reach check's arm below
        try {
            recordRow = recordRowToSave(roots.mode(), OpsConsoleForms.first(form, "record"));
            f = OpsConsoleForms.startFields(form, descriptor, selInfo, null);
            // the ProtonVPN mode - the saved fill rows vs the submitted bind
            // host + Advertise address; in the mode the gateway is asked again OUTSIDE startLock (the
            // shared flight) and an answer binds the fresh lease
            Map<String, String> rows = ServeLauncher.readLauncherRows(launcherFile, System.out);
            inMode = ServerOpsLaunch.protonMode(rows, f.bindHost(), OpsConsoleForms.first(form, "advertise"));
            VpnLeaseProbe.Probe probe = inMode ? probeOrNull("start") : null;
            if (probe != null) {
                VpnLeaseProbe.Fill answered = VpnLeaseProbe.fill(probe, f.bindPort());
                fill = answered; // the fill rows are SAVED only once the flow started (below)
                int port = portOrZero(answered.bindPort());
                bind = port > 0
                        ? ServerOpsLaunch.resolveStartBind(new ServerOpsCore.VpnLease(
                                ServerOpsLaunch.addressPart(answered.advertise()), port, answered.bindHost()), null,
                                answered.bindHost(), answered.bindPort(), ServerOpsLaunch.localAddresses(),
                                ServerOpsLaunch.BindWords.PACKAGE)
                        : PackageBind.resolve(answered.bindHost(), answered.bindPort(), ServerOpsLaunch.localAddresses());
            } else {
                bind = PackageBind.resolve(f.bindHost(), f.bindPort(), ServerOpsLaunch.localAddresses());
                if (inMode) { // the gateway did not answer: the typed pair stands, unchecked (owner fallback rule)
                    fallback = String.format(Locale.ROOT, VpnLeaseProbe.NOTE_START_UNCHECKED,
                            bind.bindHost() + ":" + bind.bindPort());
                } else if (savedProton(rows)) { // the reference is saved, so a mismatch IS an edit
                    saveLauncherRows(() -> ServerOpsLaunch.writeModeRow(launcherFile, false));
                }
            }
        } catch (IllegalArgumentException refused) { // the operator-facing refusal text
            respond(ex, 200, JSON, OpsConsoleForms.errorJson("Start refused", refused.getMessage()));
            return;
        }
        String gameRoot = OpsConsoleForms.first(form, "gameRoot"); // control chars refused by startFields
        String gameRootSecond = OpsConsoleForms.first(form, "gameRootSecond"); // null = the row untouched
        CollectingUi ui = new CollectingUi(form.getOrDefault("confirmed", List.of()));
        AtomicReference<Process> spawned = new AtomicReference<>();
        long pressedAtMs = System.currentTimeMillis();
        boolean started;
        LaneFollow.LaneLaunch launched = null;
        String kitNote = null;
        if (debugWatch) {
            // Fold: the player kit check BEFORE the Start and OUTSIDE startLock (a
            // refresh moves folders - seconds of disk - never under the lock that serializes the props writes; the
            // lock's own live-launch refusal still runs after it). A watch whose window could not open must not
            // leave a serve running: a refused check (the install, a stale kit with no package zip, a failed or
            // half-done refresh) IS the Start's refusal - nothing spawned, no row written - and a fault refuses the
            // same way (closed). A refresh's note heads the ok text; the daemon's own press repeats the check and
            // finds the kit current. The Game folder the form carries is the one the Start is about to save, so the
            // check reads THAT folder (row web-watch-kit-check).
            // The second Game folder's check rides the same seat, FIRST (path
            // resolution only; R16 / R17 used to fire in the daemon's press AFTER the serve was live - a running serve,
            // no windows, and the "press again" they ask for is what the console refuses while a serve lives). Both
            // checks read the launcher rows as THIS Start is about to save them (watchRows; row web-watch-second-check).
            // The client-log cap row (OpsJoinSelf.ROW_WATCH_CLIENT_LOG_MAX_MIB)
            // is parsed here too, before both: its R18 used to fire only in the daemon's press (OpsJoinSelf.pressWatch)
            // - the same running-serve-no-windows shape the two checks above were moved here to end. The sentence is the
            // press's own (R18); the row's value shown back is the host's own launcher text, bounded (the junk-cap post
            // of row web-watch-second-check).
            OpsJoinSelf.KitCheck c;
            String second;
            boolean capRefused = false;
            try {
                Map<String, String> watchRows = watchRows(gameRoot, gameRootSecond);
                String capRow = watchRows.get(OpsJoinSelf.ROW_WATCH_CLIENT_LOG_MAX_MIB);
                capRefused = OpsJoinSelf.watchClientLogMaxMib(capRow) < 0;
                second = capRefused
                        ? String.format(Locale.ROOT, OpsJoinSelf.R18, OpsJoinSelf.ROW_WATCH_CLIENT_LOG_MAX_MIB,
                                OpsJoinSelf.boundedText(capRow == null ? "" : capRow.trim()))
                        : OpsJoinSelf.secondFolderRefusalOrNull(roots.pkg(), watchRows, joinFx);
                c = second == null ? watchKitCheckOrNull(watchRows) : null;
            } catch (IOException | RuntimeException fault) {
                System.out.println("cc-console watch kit_check=fault class=" + fault.getClass().getSimpleName());
                respond(ex, 200, JSON, OpsConsoleForms.errorJson("Start refused",
                        "the player kit check failed (" + fault.getClass().getSimpleName() + ")"));
                return;
            }
            if (second != null) {
                System.out.println(capRefused ? "cc-console watch cap_row=refused:client_log_row"
                        : "cc-console watch second=refused:r1_second");
                respond(ex, 200, JSON, OpsConsoleForms.errorJson("Start refused", second));
                return;
            }
            if (c != null && c.refusedOrNull() != null) {
                System.out.println("cc-console watch kit_check=refused:" + c.refusedOrNull().reasonToken());
                respond(ex, 200, JSON, OpsConsoleForms.errorJson("Start refused", c.refusedOrNull().text()));
                return;
            }
            kitNote = c == null ? null : c.noteOrNull();
        }
        synchronized (startLock) {
            String live = liveLaunchRefusal(startLaunch.get());
            if (live != null) {
                respond(ex, 200, JSON, OpsConsoleForms.errorJson("Start refused", live));
                return;
            }
            // The keep password is read HERE, under the
            // lock that serializes every props read-then-write, so a concurrent Start / Apply cannot change the
            // standing row between this read and the flow's own reads (row web-start-keeps-password)
            String passwordText = keepsPassword ? keptPassword(descriptor, false) : f.passwordText();
            OpsWindowModel.StartFields fields = new OpsWindowModel.StartFields(bind.bindHost(), bind.bindPort(),
                    f.tickText(), f.fortressDpText(), passwordText, f.selDescriptor(), f.selInfo(),
                    f.joinerDefaultText(), f.fortressWaveScaleText(), f.adminPasswordText(), f.waveCategoryText(),
                    f.endlessText(), f.modsEnabledText(), f.modsOptionalText(), f.terrainText(),
                    f.terrainLevelText(), f.asteroidsText(), f.waveRosterFullDpText(),
                    f.waveRosterMultiplierText(), f.seatsPerSourceText(), f.allSkillsText(),
                    f.rngPinsText(), false, f.pvpText(), f.pvpStationText());
            fields = fields.withDebugWatch(debugWatch); // the flow's upsert writes the posture row either way
            // The package Start's wrap - the Game folder
            // saved into launcher.properties right before the ONE spawn
            // (blank = no write); a refused write throws out of the spawn, so the flow's own "Start
            // failed" ui.fail answers it the way a spawn failure is answered (row web-start-gameroot).
            // The Recording pick rides the same wrap, saved
            // as the record row right before the Game folder (null = the row untouched; row web-start-record)
            // the second game folder wraps the INNERMOST spawn, so the disk
            // order is record, game.root, game.root.second, spawn - and a refused Start writes none of them
            started = ServerOpsLaunch.runStartFlow(ui, ServerOpsLaunch.savingLauncherRow(
                    ServerOpsLaunch.savingLauncherRow(savingOrClearingLauncherRow(cmd -> {
                        Process p = spawn.spawn(cmd);
                        spawned.set(p);
                        return p;
                    }, ServerOpsLaunch.launcherPropertiesFile(), ServeLauncher.ROW_GAME_ROOT_SECOND, gameRootSecond),
                    ServerOpsLaunch.launcherPropertiesFile(), ServeLauncher.ROW_GAME_ROOT, gameRoot),
                    ServerOpsLaunch.launcherPropertiesFile(), ServeLauncher.ROW_RECORD, recordRow),
                    propsFile, fields);
            if (started) {
                // the wrapper's reference IS the launch's current: the chain spawns the launcher
                // through this same SpawnFn, so its set swaps the followed process
                launched = new LaneFollow.LaneLaunch(ServerOpsCore.PUBLIC_LANE, roots.opsFile(),
                        roots.startLog().toString(), pressedAtMs, spawned.get(), roots.pointer(), spawned);
                startLaunch.set(launched);
                // A started Start follows the launcher's status file
                // again, with a fresh writer watch and no seat rows (the Swing follow rule); package mode
                // never re-points (it follows that file from construction)
                if (!roots.opsFile().equals(followed.get().opsFile())) {
                    followed.set(Follow.fresh(roots.opsFile()));
                }
                watchNote.set(""); // a new Start: the last watch's sentence no longer describes it
                // the typed Setup is now the real config: its unsaved draft would overlay a started one
                OpsSetupDraft.clear(OpsSetupDraft.fileFor(roots));
            }
        }
        if (started) {
            // The AUTO reachability check (the
            // server-package README promises it) - armed OUTSIDE startLock and BEFORE the answer
            // is composed, never waited on (the core starts one daemon per launch and returns). The holder
            // is startLaunch: it keeps this launch until the next started Start replaces it, which ends the
            // daemon (ServerOpsCore.armReachCheck's holder contract). inMode IS this press's mode; out of the
            // ProtonVPN mode nothing is armed. Row web-start-arms-reach.
            ServerOpsCore.armReachCheck(launched, startLaunch::get, inMode, launcherFile);
            if (debugWatch) {
                // The debug watch's post-launch press, armed like the reach
                // check (never waited on; the next Start or a Kill replaces the holder and ends it). The folder is
                // computed inside the press: the flight pointer is written by the launch, after this arm.
                LaneFollow.LaneLaunch armed = launched;
                // The join windows THIS launch's press opens, closed by its
                // lease END leg below - never by a newer Start's (the daemon returns before atEnd when the holder moved)
                JoinWindowSweep.WindowSet windows = new JoinWindowSweep.WindowSet(JoinWindowSweep.LIVE,
                        System.out::println);
                ServerOpsCore.armWatchJoin(armed, startLaunch::get, () -> {
                    // the WATCH shape whatever the flight pointer holds: a pointer not yet written costs the
                    // diagnostics folders, never the second window or the sidelogs' law
                    Path flight = LaneFollow.flightDirOrNull(armed.flightPointer(), armed.pressedAtMs());
                    pressJoinOnce(true, flight, watchNote::set, windows::record);
                }, watchNote::set);
                // Fold: the lease daemon beside the press daemon - same
                // holder, same life. Its END leg clears the debug posture row and the lease the flow wrote, then
                // scrubs the join settings' password rows (the window saved the match password there; the daemon's
                // javadoc says why only at the end), then closes the join windows this launch opened. Nothing is
                // armed when the flow wrote no lease or the launch has no process handle (a recording spawn).
                ServerOpsCore.armWatchLease(armed, startLaunch::get, propsFile,
                        JoinWindowSweep.endLeg(this::scrubJoinSettings, windows));
            }
        }
        String json;
        if (started) {
            json = OpsConsoleForms.okJson((kitNote != null ? kitNote + " " : "")
                    + (ui.infoMessage == null ? "start requested" : ui.infoMessage)
                    + (fallback != null ? " " + fallback : ""));
            if (fill != null) {
                // the re-ask answered: the equality reference is saved ONCE the flow started, beside the one
                // answer that carries `fill` to the page: a confirm or refusal
                // answer carries no fill, so a save before it left the page on the stale Advertise and the
                // confirm re-POST out of the mode (the fresh lease dropped as "typed", the old port bound);
                // unsaved, the next press re-asks the gateway, per the web Start clause
                // the started path cleared the draft above, but under an
                // earlier hold of the start lock - a draft save landing between the two holds must still agree
                // with the fill this answer puts into the page's address fields, so the save settles those three
                VpnLeaseProbe.Fill answered = fill;
                saveLauncherRows(() -> ServerOpsLaunch.writeFillRows(launcherFile, answered.bindHost(),
                        answered.advertise()), addressSettle(answered.bindHost(), answered.bindPort(),
                        answered.advertise()));
                json = json.substring(0, json.length() - 1) + ",\"fill\":" + fillJson(fill) + "}";
            }
        } else if (ui.errorTitle != null) {
            json = OpsConsoleForms.errorJson(ui.errorTitle, ui.errorMessage);
        } else if (ui.refusedKey != null) {
            json = OpsConsoleForms.needsConfirmJson(List.of(
                    new OpsConsoleForms.ConfirmSpec(ui.refusedKey, ui.refusedTitle, ui.refusedMessage)));
        } else {
            json = OpsConsoleForms.errorJson("Start refused", "start did not run");
        }
        respond(ex, 200, JSON, json);
    }

    /** Swing's {@code launcherUp} gate, handler-side:
     * a Start while this console's own spawned process
     * still lives is refused before the flow runs. Null = no refusal. */
    static String liveLaunchRefusal(LaneFollow.LaneLaunch prior) {
        Process p = prior == null || prior.current() == null ? null : prior.current().get();
        if (p == null || !p.isAlive()) {
            return null;
        }
        return "this console's server process (" + ServerOpsLaunch.pidText(p)
                + ") is still running - Kill it (or wait for it to end) first";
    }

    /**
     * The Start form's {@code record} pick (the Recording select,
     * {@code setup.record}) as the launcher row to save: absent / blank = null (the row untouched - a page
     * without the select changes nothing); else the trimmed text, which must be exactly {@code off},
     * {@code diag} or {@code full} ({@link ServeRecord#parse}, case-exact - the launcher's own row grammar).
     *
     * @throws IllegalArgumentException the launcher's row refusal text (the Start answers "Start refused")
     */
    static String recordRowOrNull(String field) {
        String v = field == null ? "" : field.trim();
        if (v.isEmpty()) {
            return null;
        }
        ServeRecord.parse(v);
        return v;
    }

    /**
     * The Recording pick is a PACKAGE row only.
     * The Start wrap must save the record row only when packaged; the page's select has
     * no mode gate and {@link #recordPrefill} answers {@code ""} outside a package, so the owner-mode form posts
     * {@code off} - which, saved through the wrap, landed on the launcher file right before the launcher spawn, AFTER
     * the stage had written owner mode's MINT-extracted {@code record=} row: an owner-mode serve ran unrecorded, silently
     * (since {@link ServeLauncher#recordRows} reads the row in both modes). Outside a package the field
     * is IGNORED (null = the row untouched) and never parsed - the launcher's own {@code record_row} grammar still guards
     * the row on disk in both modes ({@link ServeLauncher#R_RECORD_ROW}); only the disk floor is package-only.
     * Owner mode's recording follows its own serve argv. Row {@code web-record-owner-untouched}.
     */
    static String recordRowToSave(OpsRoots.Mode mode, String field) {
        return mode == OpsRoots.Mode.PACKAGE ? recordRowOrNull(field) : null;
    }

    /** The web Start Ui: a {@code confirmed=} key passes, the FIRST refused confirm is recorded
     * for the {@code needsConfirm} round trip (safe: every confirm precedes the first write). */
    private static final class CollectingUi implements ServerOpsCore.Ui {
        private final List<String> confirmed;
        String refusedKey;
        String refusedTitle;
        String refusedMessage;
        String errorTitle;
        String errorMessage;
        String infoMessage;

        CollectingUi(List<String> confirmed) {
            this.confirmed = confirmed;
        }

        @Override
        public boolean confirm(String key, String title, String message) {
            if (confirmed.contains(key)) {
                return true;
            }
            if (refusedKey == null) {
                refusedKey = key;
                refusedTitle = title;
                refusedMessage = message;
            }
            return false;
        }

        @Override
        public void fail(String title, String message) {
            errorTitle = title;
            errorMessage = message;
        }

        @Override
        public void info(String message) {
            infoMessage = message;
        }
    }

    // ---- GET /api/config + POST /api/match-settings --------------------------------------------

    /**
     * {@link ServerOpsCore#configJson} plus
     * {@code matchSettings} = {@link #matchSettingsJson} for ONE shape - the query's {@code descriptor}
     * (the page re-asks with its pick on a shape change) or, without one, the standing row the
     * config's {@code selectedIndex} names. Row {@code config-match-settings}. Before it,
     * {@code record} = {@link #recordPrefill}.
     */
    private void handleConfig(HttpExchange ex) throws IOException {
        String json = ServerOpsCore.configJson(propsFile, uplinkDefault, draftLocked());
        // "packaged" = this console runs on a server package - the
        // page shows the package-only Setup controls and keeps the one package password on a shape switch
        respond(ex, 200, JSON, json.substring(0, json.length() - 1) + ",\"packaged\":"
                + (roots.mode() == OpsRoots.Mode.PACKAGE) + ",\"record\":\""
                + OpsConsoleForms.jsonEscape(recordPrefill()) + "\",\"matchSettings\":"
                + matchSettingsJson(ex.getRequestURI().getRawQuery()) + "}");
    }

    /**
     * The Recording select's prefill - a package's
     * {@code launcher.properties} {@code record} row trimmed (the launcher's own reader; no row = {@code off},
     * the launcher's default), {@code ""} outside a package. A hand-typed value the select does not offer is
     * served as typed: the page's select law keeps its current pick then (row {@code web-start-record}).
     */
    private String recordPrefill() {
        if (roots.mode() != OpsRoots.Mode.PACKAGE) {
            return "";
        }
        String row = ServeLauncher.readLauncherRows(ServerOpsLaunch.launcherPropertiesFile(), System.out)
                .getOrDefault(ServeLauncher.ROW_RECORD, "").trim();
        return row.isEmpty() ? "off" : row;
    }

    /**
     * The Battle map prefill - in a package
     * {@code {half, station, backdrop, deployCr, problem}} from
     * {@link OpsMatchApply#matchSettingsOf} (ONE capped read) of the offered file the query names
     * ({@link #offeredMatchFileOrNull}); a query past {@value #CONFIG_QUERY_MAX} chars, a malformed
     * one, a control char or an unoffered name answers four blanks + {@link OpsMatchApply#R6_NOT_OFFERED}
     * and opens no match file. {@code problem} is {@code ""} when the file read.
     *
     * <p>Owner mode answers {@link #ownerMatchSettingsJson} (the query
     * is not read) - without it the page hides the map row and the owner Applies are unreachable.
     */
    private String matchSettingsJson(String rawQuery) {
        if (roots.mode() == OpsRoots.Mode.OWNER) {
            return ownerMatchSettingsJson();
        }
        if (roots.mode() != OpsRoots.Mode.PACKAGE) {
            return "null";
        }
        Path offered = null;
        if (rawQuery == null || rawQuery.length() <= CONFIG_QUERY_MAX) {
            try {
                Map<String, List<String>> q = OpsConsoleForms.decodeForm(rawQuery);
                OpsConsoleForms.refuseControlChars(q);
                offered = offeredMatchFileOrNull(OpsConsoleForms.first(q, "descriptor"));
            } catch (IllegalArgumentException refused) { // malformed / control char = not offered
                offered = null;
            }
        }
        OpsMatchApply.MatchSettings m = offered == null
                ? new OpsMatchApply.MatchSettings("", "", "", "", OpsMatchApply.R6_NOT_OFFERED)
                : OpsMatchApply.matchSettingsOf(offered);
        return "{\"half\":\"" + OpsConsoleForms.jsonEscape(m.half()) + "\",\"station\":\""
                + OpsConsoleForms.jsonEscape(m.station()) + "\",\"backdrop\":\""
                + OpsConsoleForms.jsonEscape(m.backdrop()) + "\",\"deployCr\":\""
                + OpsConsoleForms.jsonEscape(m.crPercent()) + "\",\"problem\":\""
                + OpsConsoleForms.jsonEscape(m.problem()) + "\"}";
    }

    /**
     * The page's shape pick resolved to ONE offered
     * match file, or null. Blank / absent = the standing {@code match.descriptor} row (the selector's
     * null-path entry, {@link #handleReveal}'s rule); the text resolves the serve's way
     * ({@link ServerOpsCore#configRelative}) and must EQUAL a member of
     * {@link OpsMatchApply#offeredDescriptors} - the bound on every match-file read and write, so
     * {@code ../x}, a password / tokens file or {@code serve-mods/} is never opened. A path outside
     * {@code <pkg>/match/} is refused before the folder is listed.
     */
    private Path offeredMatchFileOrNull(String descriptor) {
        String row = descriptor;
        if (row == null || row.isBlank()) {
            try {
                row = ServerOpsCore.descriptorRowValue(propsLines());
            } catch (IOException unreadable) { // no standing row this request = not offered
                System.out.println("cc-console match_settings_props_unreadable class="
                        + unreadable.getClass().getSimpleName());
                return null;
            }
        }
        if (row == null || row.isBlank()) {
            return null;
        }
        Path abs;
        try {
            abs = ServerOpsCore.configRelative(propsFile, row);
        } catch (InvalidPathException notAPath) { // a hand-made name the file system cannot hold
            return null;
        }
        Path matchDir = roots.pkg().resolve(OpsMatchApply.MATCH_DIR).toAbsolutePath().normalize();
        if (!matchDir.equals(abs.getParent())) {
            return null;
        }
        for (Path p : OpsMatchApply.offeredDescriptors(roots)) {
            if (p.equals(abs)) {
                return p;
            }
        }
        return null;
    }

    /**
     * A Battle map Apply, the Swing package row's twin.
     * Order: the form's CR / LF / NUL refused
     * ({@link OpsConsoleForms#refuseControlChars}); {@code op} ({@link #MATCH_SETTINGS_OPS}, else
     * {@link #MATCH_SETTINGS_BAD_OP}); a package console only ({@link #MATCH_SETTINGS_NOT_PACKAGE});
     * {@code descriptor} resolved to an offered file ({@link #offeredMatchFileOrNull}, else
     * {@link OpsMatchApply#R6_NOT_OFFERED}); then under
     * {@code startLock} the ONE live gate {@link ServerOpsLaunch#serveBusy} (a {@code serve.bat} boot
     * holds {@code serve.lock} before any ops-status exists) and
     * {@link OpsMatchApply#applyPackageMatchSetting}, which owns the value rules (16 chars, grammar)
     * and every refusal text. Answers {@code {ok, changed, repaired, note, matchTextChanged, half,
     * station, backdrop, deployCr}} or {@code {error, title}}. ONE stdout row per request that reaches
     * the handler: {@code cc-console match-settings op=<size|station|backdrop|cr|players|-> result=<changed|
     * unchanged|repaired|refused:<token>>} - no value, no path, no id (row {@code web-match-settings});
     * an unreadable props file on a blank pick adds one {@code cc-console match_settings_props_unreadable} row.
     *
     * <p>No confirm is asked. The host who pressed Apply wants the
     * change, and a confirm reads as if it went live; the live gate refuses an Apply while a serve runs, so a
     * written change lands at the next Start (the answer's {@code note} names that next step).
     *
     * <p>An owner console takes the owner branch right after
     * {@code op} parsed ({@link #ownerMatchApply}); the same row, results
     * {@code changed|unchanged|refused:value|refused:not_package|fault}.
     */
    private void handleMatchSettings(HttpExchange ex) throws IOException {
        Map<String, List<String>> form = readForm(ex, OpsMatchApply.CONFIRM_TITLE);
        String[] line = {"-", "refused:body"};
        String json = form == null ? null : matchSettingsAnswer(form, line);
        System.out.println("cc-console match-settings op=" + line[0] + " result=" + line[1]);
        if (json != null) {
            respond(ex, 200, JSON, json);
        }
    }

    /** {@link #handleMatchSettings}' answer in its order;
     * {@code line} = [op word, result word] for the stdout row ({@code "-"} until {@code op} parsed). */
    private String matchSettingsAnswer(Map<String, List<String>> form, String[] line) {
        String title = OpsMatchApply.CONFIRM_TITLE;
        try {
            OpsConsoleForms.refuseControlChars(form);
        } catch (IllegalArgumentException refused) { // the fixed CR / LF / NUL text
            line[1] = "refused:control_char";
            return OpsConsoleForms.errorJson(title, refused.getMessage());
        }
        String opWord = String.valueOf(OpsConsoleForms.first(form, "op"));
        OpsMatchApply.Op op = MATCH_SETTINGS_OPS.get(opWord);
        if (op == null) {
            line[1] = "refused:op";
            return OpsConsoleForms.errorJson(title, MATCH_SETTINGS_BAD_OP);
        }
        line[0] = opWord;
        if (roots.mode() == OpsRoots.Mode.OWNER) {
            return ownerMatchApply(form, op, line);
        }
        if (roots.mode() != OpsRoots.Mode.PACKAGE) {
            line[1] = "refused:not_package";
            return OpsConsoleForms.errorJson(title, MATCH_SETTINGS_NOT_PACKAGE);
        }
        String value = OpsConsoleForms.first(form, "value");
        Path offered = offeredMatchFileOrNull(OpsConsoleForms.first(form, "descriptor"));
        if (offered == null) {
            line[1] = "refused:not_offered";
            return OpsConsoleForms.errorJson(title, OpsMatchApply.R6_NOT_OFFERED);
        }
        OpsMatchApply.ApplyResult r;
        try {
            synchronized (startLock) {
                boolean live = ServerOpsLaunch.serveBusy(roots.pkg(), new StringBuilder());
                r = OpsMatchApply.applyPackageMatchSetting(roots, offered, op, value, live);
            }
        } catch (OpsMatchApply.ApplyRefused refused) { // the core's host-facing sentence + its bounded token
            line[1] = "refused:" + refused.token();
            return OpsConsoleForms.errorJson(title, refused.getMessage());
        }
        line[1] = r.changed() ? "changed" : r.repaired() ? "repaired" : "unchanged";
        OpsMatchApply.MatchSettings m = r.settings();
        return "{\"ok\":true,\"changed\":" + r.changed() + ",\"repaired\":" + r.repaired() + ",\"note\":\""
                + OpsConsoleForms.jsonEscape(r.note()) + "\",\"matchTextChanged\":" + r.matchTextChanged()
                + ",\"half\":\"" + OpsConsoleForms.jsonEscape(m.half()) + "\",\"station\":\""
                + OpsConsoleForms.jsonEscape(m.station()) + "\",\"backdrop\":\""
                + OpsConsoleForms.jsonEscape(m.backdrop()) + "\",\"deployCr\":\""
                + OpsConsoleForms.jsonEscape(m.crPercent()) + "\"}";
    }

    /**
     * The owner map Applies, the Swing owner shell's three (map size,
     * station family, backdrop) with its exact arguments: {@link OpsMatchApply#applyMapSize} /
     * {@link OpsMatchApply#applyStationVariant} / {@link OpsMatchApply#applyTerrainBackdrop} on the owner
     * descriptor sets ({@link OpsMatchApply#mapSizeDescriptorFiles} / {@link OpsMatchApply#stationDescriptorFiles},
     * the deployed mod root), so the form's {@code descriptor} is ignored. Order: a deploy-CR / Players op is
     * package-only ({@link #MATCH_SETTINGS_NOT_PACKAGE}); then, with no confirm asked (the package branch's
     * reason), under {@link #startLock} (it writes the
     * props file) the apply - no live gate, as in Swing: the change lands at the next serve boot. The core's value
     * refusal answers its own text ({@code refused:value}); any other failure answers {@link #OWNER_APPLY_PARTIAL}
     * + the exception's SimpleName ({@code fault}). The ok answer carries {@link #ownerMatchSettingsJson}'s
     * members after the write, the shape the page's Apply reads.
     */
    private String ownerMatchApply(Map<String, List<String>> form, OpsMatchApply.Op op, String[] line) {
        String title = OpsMatchApply.CONFIRM_TITLE;
        if (op != OpsMatchApply.Op.SIZE && op != OpsMatchApply.Op.STATION && op != OpsMatchApply.Op.BACKDROP) {
            line[1] = "refused:not_package";
            return OpsConsoleForms.errorJson(title, MATCH_SETTINGS_NOT_PACKAGE);
        }
        String typed = OpsConsoleForms.first(form, "value");
        String value = typed == null ? "" : typed.trim();
        boolean changed;
        List<String> report;
        try {
            synchronized (startLock) {
                if (op == OpsMatchApply.Op.SIZE) {
                    OpsMatchApply.MapSizeResult r = OpsMatchApply.applyMapSize(propsFile,
                            OpsMatchApply.mapSizeDescriptorFiles(), OpsMatchApply.mapSizeDeployedModRoot(), value);
                    changed = r.changed();
                    report = r.report();
                } else if (op == OpsMatchApply.Op.STATION) {
                    OpsMatchApply.StationVariantResult r = OpsMatchApply.applyStationVariant(propsFile,
                            OpsMatchApply.stationDescriptorFiles(), OpsMatchApply.mapSizeDeployedModRoot(), value);
                    changed = r.changed();
                    report = r.report();
                } else {
                    OpsMatchApply.BackdropResult r = OpsMatchApply.applyTerrainBackdrop(propsFile,
                            OpsMatchApply.mapSizeDescriptorFiles(), OpsMatchApply.mapSizeDeployedModRoot(), value);
                    changed = r.changed();
                    report = r.report();
                }
            }
        } catch (IllegalArgumentException refused) { // the core's own value sentence; nothing written
            line[1] = "refused:value";
            return OpsConsoleForms.errorJson(title, refused.getMessage());
        } catch (IOException | RuntimeException failed) { // counted by the route's row (result=fault)
            line[1] = "fault";
            return OpsConsoleForms.errorJson(title, OWNER_APPLY_PARTIAL + " (" + failed.getClass().getSimpleName() + ")");
        }
        line[1] = changed ? "changed" : "unchanged";
        return "{\"ok\":true,\"changed\":" + changed + ",\"repaired\":false,\"note\":\""
                + OpsConsoleForms.jsonEscape(String.join(" ", report)) + "\",\"matchTextChanged\":false,"
                + ownerMatchSettingsJson().substring(1);
    }

    /**
     * The owner map row's prefill, from the Swing owner form's sources:
     * {@code half} = {@link OpsMatchApply#mapHalfFieldPrefill} of the props file, {@code station} =
     * {@link OpsMatchApply#stationFamilyPrefill} of the station descriptor set, {@code backdrop} =
     * {@link OpsMatchApply#terrainBackdropPrefill} of the map-size set; {@code deployCr} is always {@code ""} (no
     * owner deploy-CR Apply). An unreadable props file = {@code half} {@code ""} + {@code problem}
     * {@link #OWNER_PROPS_UNREADABLE} + one {@code cc-console match_settings_props_unreadable} row. Shared by the
     * config answer and the owner Apply's answer.
     */
    String ownerMatchSettingsJson() {
        String half = "";
        String problem = "";
        try {
            half = OpsMatchApply.mapHalfFieldPrefill(propsLines());
        } catch (IOException unreadable) { // one bounded row; the field opens blank with the problem line
            System.out.println("cc-console match_settings_props_unreadable class="
                    + unreadable.getClass().getSimpleName());
            problem = OWNER_PROPS_UNREADABLE;
        }
        return "{\"half\":\"" + OpsConsoleForms.jsonEscape(half) + "\",\"station\":\""
                + OpsConsoleForms.jsonEscape(OpsMatchApply.stationFamilyPrefill(OpsMatchApply.stationDescriptorFiles()))
                + "\",\"backdrop\":\""
                + OpsConsoleForms.jsonEscape(OpsMatchApply.terrainBackdropPrefill(OpsMatchApply.mapSizeDescriptorFiles()))
                + "\",\"deployCr\":\"\",\"problem\":\"" + OpsConsoleForms.jsonEscape(problem) + "\"}";
    }

    // ---- the unsaved Setup draft ---------------------------------------------------------------

    /**
     * {@code POST /api/draft} - one typed Setup field kept as the unsaved
     * draft ({@link OpsSetupDraft}), so a page closed before Start loses nothing. Form fields {@code id} +
     * {@code value}. Order (the match-settings route's): the body ({@link #readForm}: 413 or a malformed-body
     * refusal); the form's CR / LF / NUL ({@link OpsConsoleForms#refuseControlChars}); {@code id} in
     * {@link OpsSetupDraft#DRAFT_IDS}; {@code value} present and {@link OpsSetupDraft#lawfulValue}; then under
     * {@link #startLock} the read-modify-write: the config base, {@link OpsSetupDraft#load} (a stale draft
     * dropped), {@link OpsSetupDraft#put} (its row and byte caps) and the atomic {@link OpsSetupDraft#write}.
     * WHY the lock: the pool runs two POSTs at once, and the lock that already serializes every props
     * read-then-write makes the draft's one at a time (a lost update otherwise).
     *
     * <p>Answers {@code {"ok":true}}, or 200 + {@link OpsConsoleForms#errorJson} under {@link #DRAFT_TITLE} for
     * EVERY refusal (the console's law: the page's note reads the {@code error} member; never a 4xx / 500 from
     * here). ONE stdout row per request that reaches the handler: {@code cc-console draft=saved id=<id> n=<rows>}
     * (the id printed only after the allowlist passed, so it is a server constant) or {@code cc-console
     * draft=refused:<id|value|rows|size|io|control_char|body>} - never an id on a refusal, never a value or a
     * path (row {@code web-draft}).
     */
    private void handleDraft(HttpExchange ex) throws IOException {
        Map<String, List<String>> form = readForm(ex, DRAFT_TITLE);
        String[] line = {OpsSetupDraft.REFUSED + ":body"};
        String json = form == null ? null : draftAnswer(form, line);
        System.out.println("cc-console draft=" + line[0]);
        if (json != null) {
            respond(ex, 200, JSON, json);
        }
    }

    /** {@link #handleDraft}'s answer in its order; {@code line[0]} = the
     * stdout row's word after {@code draft=}. */
    private String draftAnswer(Map<String, List<String>> form, String[] line) {
        try {
            OpsConsoleForms.refuseControlChars(form);
        } catch (IllegalArgumentException refused) { // the fixed CR / LF / NUL text
            line[0] = OpsSetupDraft.REFUSED + ":control_char";
            return OpsConsoleForms.errorJson(DRAFT_TITLE, refused.getMessage());
        }
        String id = OpsConsoleForms.first(form, "id");
        if (!OpsSetupDraft.lawfulId(id)) {
            line[0] = OpsSetupDraft.REFUSED + ":id";
            return OpsConsoleForms.errorJson(DRAFT_TITLE, OpsSetupDraft.REFUSED_ID);
        }
        String value = OpsConsoleForms.first(form, "value");
        if (!OpsSetupDraft.lawfulValue(value)) { // null (absent) included
            line[0] = OpsSetupDraft.REFUSED + ":value";
            return OpsConsoleForms.errorJson(DRAFT_TITLE, OpsSetupDraft.REFUSED_VALUE);
        }
        OpsSetupDraft.Draft next;
        try {
            synchronized (startLock) {
                Path file = OpsSetupDraft.fileFor(roots);
                long base = OpsSetupDraft.configBase(propsFile, ServerOpsLaunch.launcherPropertiesFile());
                next = OpsSetupDraft.put(OpsSetupDraft.load(file, base), id, value, base, Instant.now());
                OpsSetupDraft.write(file, next);
            }
        } catch (OpsSetupDraft.Refused refused) { // a cap: its bounded token + fixed sentence
            line[0] = OpsSetupDraft.REFUSED + ":" + refused.token();
            return OpsConsoleForms.errorJson(DRAFT_TITLE, refused.getMessage());
        } catch (IOException io) { // answered + the stdout row; the prior draft file stands
            line[0] = OpsSetupDraft.REFUSED + ":io";
            return OpsConsoleForms.errorJson(DRAFT_TITLE, DRAFT_IO);
        }
        line[0] = "saved id=" + id + " " + OpsSetupDraft.summary(next);
        return "{\"ok\":true}";
    }

    /** The draft {@code GET /api/config} serves, loaded under
     * {@link #startLock}. WHY the lock: {@link OpsSetupDraft#load} DELETES a stale file, and an unlocked delete
     * could erase a row a concurrent {@code POST /api/draft} just wrote under the lock. */
    private OpsSetupDraft.Draft draftLocked() {
        synchronized (startLock) {
            return OpsSetupDraft.load(OpsSetupDraft.fileFor(roots),
                    OpsSetupDraft.configBase(propsFile, ServerOpsLaunch.launcherPropertiesFile()));
        }
    }

    /**
     * re-stamps a kept draft's {@code base} with the config's CURRENT
     * newest mtime. Called under {@link #startLock} right after each of this console's OWN successful writes of
     * the two base files (a launcher row save, Voice, Mods). WHY: the draft is dropped at load when a config
     * file is newer than its base (a hand edit wins), so without the re-stamp every Voice / Mods / Fill / brick
     * press would silently drop the unsaved Setup. Reads with base 0 (never drops); a failed write = one
     * {@code cc-console draft=rebase_failed (<SimpleName>)} row, the draft then dropped at the next load.
     * This form settles nothing ({@link #rebaseDraft(Map)} over an empty map): Voice, Mods and the launcher's
     * mode row put no value into a Setup field.
     */
    private void rebaseDraft() {
        rebaseDraft(Map.of());
    }

    /**
     * {@link #rebaseDraft()} that also SETTLES the draft - each
     * {@code settled} entry (a draft control id to the value the console's answer just put into that page
     * field) whose id the draft already HOLDS replaces that row through {@link OpsSetupDraft#put} (the same
     * closed id list and caps; the row keeps its place, a put on a held id never moves it in the insertion
     * order; the draft's {@code updated} time is kept, as a plain re-stamp keeps it). WHY: a Fill, or a brick
     * that asked the gateway again, sets the page's address fields without a keystroke, so the page's own draft
     * save never fires - the draft then laid the OLDER typed address over the saved fill at the next page
     * load. WHY replace and not drop: the bind port has no launcher row of its own, so a dropped row would lose
     * the fill's port at the next load, while the replaced one shows what the page showed. A blank or
     * {@code null} value, an id the draft does not hold and an empty draft settle nothing: a settle never mints
     * a draft or a row. Rows (counts only, never an id, a value or an address): {@code cc-console
     * draft=settled n=<k>} once the write landed with k &gt; 0 rows settled; a refused settle leaves that row as
     * it was and is counted into ONE {@code cc-console draft=rebase_failed (Refused) n=<r>} row; any other fault
     * is the one {@code rebase_failed (<SimpleName>)} row - never a fault out of the config write that already
     * succeeded.
     */
    private void rebaseDraft(Map<String, String> settled) {
        Path f = OpsSetupDraft.fileFor(roots);
        OpsSetupDraft.Draft d = OpsSetupDraft.load(f, 0L);
        if (d.isEmpty()) {
            return;
        }
        try {
            long base = OpsSetupDraft.configBase(propsFile, ServerOpsLaunch.launcherPropertiesFile());
            OpsSetupDraft.Draft next = new OpsSetupDraft.Draft(d.rows(), base, d.updatedOrNull());
            int settledRows = 0;
            int refusedRows = 0;
            for (Map.Entry<String, String> e : settled.entrySet()) {
                String value = e.getValue();
                if (value == null || value.isBlank() || !next.rows().containsKey(e.getKey())) {
                    continue;
                }
                try {
                    next = OpsSetupDraft.put(next, e.getKey(), value, base, d.updatedOrNull());
                    settledRows++;
                } catch (OpsSetupDraft.Refused notKept) { // counted into the one row below; the row stays as it was
                    refusedRows++;
                }
            }
            if (refusedRows > 0) {
                System.out.println("cc-console draft=rebase_failed (" + OpsSetupDraft.Refused.class.getSimpleName()
                        + ") n=" + refusedRows);
            }
            OpsSetupDraft.write(f, next);
            if (settledRows > 0) {
                System.out.println("cc-console draft=settled n=" + settledRows);
            }
        } catch (IOException | RuntimeException e) { // a fault here must never read as the config write failing
            System.out.println("cc-console draft=rebase_failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * The settle map of an answer that puts a ProtonVPN fill into the
     * page's three address fields ({@code setup.bindHost}, {@code setup.bindPort}, {@code setup.advertise},
     * in that order) for {@link #saveLauncherRows(LauncherWrite, Map)}; a {@code null} argument = that field
     * not set by the answer (left out). The values are the ones the answer carries to the page - the console's
     * fill / brick computation, which may echo the form's own typed port or Advertise (already refused for
     * control chars); {@link OpsSetupDraft#put} stays the only admission, the same closed list and caps a keyed
     * draft save meets.
     */
    private static Map<String, String> addressSettle(String bindHostOrNull, String bindPortOrNull,
            String advertiseOrNull) {
        Map<String, String> settled = new LinkedHashMap<>();
        if (bindHostOrNull != null) {
            settled.put("setup.bindHost", bindHostOrNull);
        }
        if (bindPortOrNull != null) {
            settled.put("setup.bindPort", bindPortOrNull);
        }
        if (advertiseOrNull != null) {
            settled.put("setup.advertise", advertiseOrNull);
        }
        return settled;
    }

    // ---- the ONE web password read -------------------------------------------------------------

    /** Every {@link #keptPassword} call (a process count; row {@code web-start-keeps-password}). */
    static final AtomicInteger KEPT_READS = new AtomicInteger();
    /** {@link #keptPassword} calls made WITHOUT {@code startLock} held - always 0 by construction; a non-zero
     *  count means a reader left the lock (the row's seam: it reads the delta over a keep Start). */
    static final AtomicInteger KEPT_READS_UNLOCKED = new AtomicInteger();

    /**
     * THE web password read: all four readers - Start's {@code keep}, Show saved
     * passwords, Copy join brick's {@code keep}, Join from this PC - resolve here through ONE core resolver,
     * {@link ServerOpsCore#packageKeptPassword} / {@link ServerOpsCore#packageKeptAdminPassword}. WHY one: a
     * package has ONE match (+ admin) password - the standing row's sidecar whatever shape is picked, so a
     * switch costs the host no typing and the joiners no new password. In owner mode the core reads the path
     * this method passes, byte-identical to the old per-reader reads: {@code descriptor} (row space) + its
     * {@code .password}, or the standing row's own sidecar when {@code descriptor} is {@code null}.
     * Callers hold {@code startLock} (counted, never asserted: {@link #KEPT_READS_UNLOCKED}). The value is
     * never logged; {@code ""} = none.
     *
     */
    private String keptPassword(String descriptor, boolean admin) throws IOException {
        KEPT_READS.incrementAndGet();
        if (!Thread.holdsLock(startLock)) {
            KEPT_READS_UNLOCKED.incrementAndGet();
        }
        String sidecar = descriptor == null ? ServerOpsCore.passwordSidecarPath(propsLines())
                : descriptor + ".password";
        return admin ? ServerOpsCore.packageKeptAdminPassword(propsFile, sidecar)
                : ServerOpsCore.packageKeptPassword(propsFile, sidecar);
    }

    // ---- POST /api/shape-new -------------------------------------------------------------------

    /** New match shape's refusal title (the form's title without its dots). */
    static final String SHAPE_NEW_TITLE = "New match shape";
    /** Owner mode has no package match folder: New is a server package's (owner mode mints by script). */
    static final String SHAPE_NEW_NOT_PACKAGE = "New match shape makes a server package's match files; this"
            + " console does not run on a server package.";
    /** New's base file did not read / decode within the 262144 B cap (this console's own words behind the {@link MatchShapes#CREATE_REFUSED} prefix). */
    static final String SHAPE_NEW_BASE_UNREADABLE = MatchShapes.CREATE_REFUSED
            + "the base match shape cannot be read - pick another";
    /** New's files could not be read or written ({@code <class>} = the exception's simple name; T6's prefix). */
    static final String SHAPE_NEW_IO_FAILED = MatchShapes.CREATE_REFUSED + "the match files could not be read or"
            + " written (<class>)";

    /**
     * New match shape: {@code POST /api/shape-new}, keyed, {@code name= base= half= cr=
     * terrain= players=}. Order: the form's control chars refused; a package console only
     * ({@link #SHAPE_NEW_NOT_PACKAGE}); each of {@code half} / {@code cr} / {@code terrain} longer than
     * {@value OpsMatchApply#MAX_VALUE_CHARS} chars is refused BEFORE any parse, then parsed by OpsMatchApply's own
     * parsers and refused with its texts ({@link OpsMatchApply#R3_MAP_SIZE}, {@link OpsMatchApply#R8_DEPLOY_CR},
     * {@link OpsMatchApply#BACKDROP_WORD}); then under {@code startLock}: the live gate
     * ({@link ServerOpsLaunch#serveBusy} = {@link OpsMatchApply#R1_LIVE}: New is a {@code #pre-start} action - the
     * picker refresh it causes belongs before Start), {@code base} = an offered file
     * ({@link #offeredMatchFileOrNull}, else {@link OpsMatchApply#R6_NOT_OFFERED}) read ≤ 262144 B and decoded, the
     * STANDING row's {@code .fingerprint} read ≤ {@value PackageFolderWriter#MAX_SIDECAR_BYTES} B (else
     * {@link OpsMatchApply#R4_SIDECAR}), then {@link MatchShapes#create} (name N7, exists, the ship belt, N2 64 -
     * its T6 texts verbatim). Synchronous. Answers {@code {ok, shape, text}} (T6's created sentence) or
     * {@code {error, title}}. ONE stdout row: {@code cc-console shape-new result=created shape=<stem>} |
     * {@code cc-console shape-new result=refused reason=<token>} - no value, no path, no id (row
     * {@code web-shape-new}).
     *
     * <p>{@code players=} (optional) - a present value past
     * {@value OpsMatchApply#MAX_VALUE_CHARS} chars or outside {@link OpsMatchApply#parsePlayers} is
     * {@link OpsMatchApply#R9_PLAYERS} (token {@code players}) before the lock; absent / blank = the base's own
     * seat count; a count the base's shape does not take is T6's {@code T6_PLAYERS} (token {@code players}).
     * Row {@code web-shape-new-players}.
     */
    private void handleShapeNew(HttpExchange ex) throws IOException {
        Map<String, List<String>> form = readForm(ex, SHAPE_NEW_TITLE);
        String[] line = {null, "body"};
        String json = form == null ? null : shapeNewAnswer(form, line);
        System.out.println(line[0] != null ? "cc-console shape-new result=created shape=" + line[0]
                : "cc-console shape-new result=refused reason=" + line[1]);
        if (json != null) {
            respond(ex, 200, JSON, json);
        }
    }

    /** {@link #handleShapeNew}'s answer in its order; {@code line} =
     *  [created stem or null, refusal token]. */
    private String shapeNewAnswer(Map<String, List<String>> form, String[] line) throws IOException {
        String title = SHAPE_NEW_TITLE;
        try {
            OpsConsoleForms.refuseControlChars(form);
        } catch (IllegalArgumentException refused) { // the fixed CR / LF / NUL text
            line[1] = "control_char";
            return OpsConsoleForms.errorJson(title, refused.getMessage());
        }
        if (roots.mode() != OpsRoots.Mode.PACKAGE) {
            line[1] = "not_package";
            return OpsConsoleForms.errorJson(title, SHAPE_NEW_NOT_PACKAGE);
        }
        String halfText = Objects.requireNonNullElse(OpsConsoleForms.first(form, "half"), "");
        String crText = Objects.requireNonNullElse(OpsConsoleForms.first(form, "cr"), "");
        String terrainText = Objects.requireNonNullElse(OpsConsoleForms.first(form, "terrain"), "");
        float half = Float.NaN;
        if (halfText.length() <= OpsMatchApply.MAX_VALUE_CHARS) {
            try {
                half = OpsMatchApply.parseMapHalf(halfText);
            } catch (IllegalArgumentException bad) { // answered with OpsMatchApply's own text below
                half = Float.NaN;
            }
        }
        if (Float.isNaN(half)) {
            line[1] = "map_size";
            return OpsConsoleForms.errorJson(title, OpsMatchApply.R3_MAP_SIZE.replace("<x>",
                    OpsMatchApply.shown(halfText)));
        }
        if (crText.length() > OpsMatchApply.MAX_VALUE_CHARS) {
            line[1] = "deploy_cr";
            return OpsConsoleForms.errorJson(title, OpsMatchApply.R8_DEPLOY_CR.replace("<x>",
                    OpsMatchApply.shown(crText)));
        }
        float cr;
        try {
            cr = OpsMatchApply.parseDeployCrPercent(crText);
        } catch (OpsMatchApply.ApplyRefused refused) { // R8, its token deploy_cr
            line[1] = refused.token();
            return OpsConsoleForms.errorJson(title, refused.getMessage());
        }
        String terrain = terrainText.trim().toLowerCase(Locale.ROOT);
        if (terrainText.length() > OpsMatchApply.MAX_VALUE_CHARS || !MatchDescriptor.isTerrainWord(terrain)) {
            line[1] = "backdrop_word";
            return OpsConsoleForms.errorJson(title, OpsMatchApply.BACKDROP_WORD.replace("<x>",
                    OpsMatchApply.shown(terrainText)));
        }
        // Players - PRESENT text over 16 chars is refused before any
        // parse, else OpsMatchApply.parsePlayers (R9, token players); ABSENT or blank = the base's own seat
        // count, known only after the decode below (an older page that sends no players still works)
        String playersText = OpsConsoleForms.first(form, "players");
        int players = 0; // 0 = absent: resolved to the base's seat count under the lock
        if (playersText != null && !playersText.isBlank()) {
            try {
                if (playersText.length() > OpsMatchApply.MAX_VALUE_CHARS) {
                    throw new OpsMatchApply.ApplyRefused("players", OpsMatchApply.R9_PLAYERS.replace("<x>",
                            OpsMatchApply.shown(playersText)));
                }
                players = OpsMatchApply.parsePlayers(playersText);
            } catch (OpsMatchApply.ApplyRefused refused) { // R9, its token players
                line[1] = refused.token();
                return OpsConsoleForms.errorJson(title, refused.getMessage());
            }
        }
        MatchShapes.CreateResult r;
        synchronized (startLock) {
            if (ServerOpsLaunch.serveBusy(roots.pkg(), new StringBuilder())) {
                line[1] = "server_live";
                return OpsConsoleForms.errorJson(title, OpsMatchApply.R1_LIVE);
            }
            Path base = offeredMatchFileOrNull(OpsConsoleForms.first(form, "base"));
            if (base == null) {
                line[1] = "not_offered";
                return OpsConsoleForms.errorJson(title, OpsMatchApply.R6_NOT_OFFERED);
            }
            MatchDescriptor baseShape;
            try {
                baseShape = MatchDescriptorCodec.decode(ServerOpsCore.readCappedLines(base));
            } catch (IOException | RuntimeException unreadable) { // answered: the fixed text + its stdout token
                line[1] = "base_unreadable";
                return OpsConsoleForms.errorJson(title, SHAPE_NEW_BASE_UNREADABLE);
            }
            String standingName = "match.txt";
            try {
                Path standing = standingDescriptorFile();
                standingName = standing.getFileName().toString();
                r = MatchShapes.create(roots.pkg().resolve(OpsMatchApply.MATCH_DIR), OpsConsoleForms.first(form, "name"),
                        baseShape, half, cr, terrain, readSidecarLines(standing.resolveSibling(
                                standingName + MatchShapes.SIDECAR_SUFFIX)),
                        players > 0 ? players : baseShape.seats().size());
            } catch (java.nio.file.NoSuchFileException | IllegalArgumentException sidecar) { // no / un-rekeyable sidecar
                line[1] = "sidecar_damaged";
                return OpsConsoleForms.errorJson(title, OpsMatchApply.R4_SIDECAR.replace("<name>", standingName));
            } catch (IOException failed) { // a props / sidecar read refused (cap, I/O) or a write failure
                line[1] = "io_failed";
                return OpsConsoleForms.errorJson(title, SHAPE_NEW_IO_FAILED.replace("<class>",
                        failed.getClass().getSimpleName()));
            }
        }
        if (!r.ok()) {
            line[1] = createToken(r.text());
            return OpsConsoleForms.errorJson(title, r.text());
        }
        String name = r.file().getFileName().toString();
        String stem = name.substring(0, name.length() - MatchShapes.TXT.length());
        line[0] = stem;
        return OpsConsoleForms.shapeTextJson(stem, r.text());
    }

    /** The standing {@code match.descriptor} row's file (config-relative, the serve's resolve); an absent /
     *  blank row throws {@link java.nio.file.NoSuchFileException} (no sidecar to re-key).
     * */
    private Path standingDescriptorFile() throws IOException {
        String row = ServerOpsCore.descriptorRowValue(propsLines());
        if (row == null || row.isBlank()) {
            throw new java.nio.file.NoSuchFileException("match.descriptor");
        }
        return ServerOpsCore.configRelative(propsFile, row.trim());
    }

    /** A {@code .fingerprint} sidecar's ISO-8859-1 lines through ONE bounded read (no symlink followed); past
     *  {@value PackageFolderWriter#MAX_SIDECAR_BYTES} B it is refused (an IOException), never truncated.
     * */
    private static List<String> readSidecarLines(Path file) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(PackageFolderWriter.MAX_SIDECAR_BYTES + 1);
            if (bytes.length > PackageFolderWriter.MAX_SIDECAR_BYTES) {
                throw new IOException("over the sidecar read cap");
            }
            return new String(bytes, StandardCharsets.ISO_8859_1).lines().toList();
        }
    }

    /** A {@link MatchShapes#create} refusal text's bounded stdout token ({@code name | exists | ship | full |
     *  players | other}). {@code players} = {@link MatchShapes#T6_PLAYERS},
     *  the only T6 text ending in " players". */
    static String createToken(String text) {
        String why = text.startsWith(MatchShapes.CREATE_REFUSED) ? text.substring(MatchShapes.CREATE_REFUSED.length())
                : text;
        if (why.equals(MatchShapes.T6_NAME)) {
            return "name";
        }
        if (why.equals(MatchShapes.T6_FULL)) {
            return "full";
        }
        if (why.endsWith(MatchShapes.T6_EXISTS.replace("<name>", ""))) {
            return "exists";
        }
        if (why.endsWith(MatchShapes.T6_PLAYERS.substring(MatchShapes.T6_PLAYERS.lastIndexOf(' ')))) {
            return "players";
        }
        return why.startsWith(MatchShapes.T6_SHIP.substring(0, MatchShapes.T6_SHIP.indexOf("<id>"))) ? "ship" : "other";
    }

    // ---- POST /api/reveal ----------------------------------------------------------------------

    /**
     * Show saved passwords, the Swing form's saved
     * password prefill twin. WHY its own route: {@code /api/config} never
     * serves a stored password (row {@code web-config-no-secret}); this route does, but only to a keyed
     * POST whose {@code confirmed} carries the server-minted {@value #REVEAL_KEY} confirm. The form's
     * {@code descriptor} (blank = the standing row) must EQUAL one offered selector option's path
     * ({@link ServerOpsCore#descriptorOptions} built the way {@code configJson} builds it) - the bound
     * on the file read, so no other path is ever opened; the sidecar is then {@link #handleStart}'s own
     * rule ({@code descriptor + ".password"}, else the row's {@code passwordSidecarPath}), its admin
     * sibling {@link ServerOpsCore#adminSidecarOf}. A missing / unreadable sidecar answers {@code ""}.
     * ONE stdout row {@code cc-console reveal}: no secret, no length, no path (row {@code web-reveal}).
     *
     * <p>The two values come from
     * {@link #keptPassword} - in a PACKAGE the ONE kept pair (the standing row's sidecars) for every offered
     * pick, in owner mode the pick's own sidecar as before. The offered check stays
     * {@link ServerOpsCore#descriptorOptions}, which in a package lists exactly
     * {@link OpsMatchApply#offeredDescriptors} (regular, non-symlink {@code match/*.txt}, no {@code -tokens}) plus
     * the standing row - the picker's own set (row {@code web-reveal}'s rotate + {@code -tokens} legs).
     */
    private void handleReveal(HttpExchange ex) throws IOException {
        Map<String, List<String>> form = readForm(ex, "Reveal refused");
        if (form == null) {
            return;
        }
        if (!form.getOrDefault("confirmed", List.of()).contains(REVEAL_KEY)) {
            respond(ex, 200, JSON, OpsConsoleForms.needsConfirmJson(List.of(new OpsConsoleForms.ConfirmSpec(
                    REVEAL_KEY, "Show saved passwords", "Show the saved match and admin passwords on this page?"))));
            return;
        }
        String descriptor = OpsConsoleForms.first(form, "descriptor");
        if (descriptor != null && descriptor.isBlank()) {
            descriptor = null; // the null-path selector entry: the standing row's own sidecar
        }
        boolean offered = descriptor == null;
        String password = "";
        String admin = "";
        synchronized (startLock) {
            List<String> lines = propsLines();
            if (descriptor != null) {
                String current = ServerOpsCore.descriptorRowValue(lines);
                for (ServerOpsCore.DescriptorOption o : ServerOpsCore.descriptorOptions(propsFile,
                        ServerOpsCore.descriptorScanDir(current), current).options()) {
                    if (descriptor.equals(o.path())) {
                        offered = true;
                        break;
                    }
                }
            }
            if (offered) { // the ONE resolver: a package's kept pair whatever the pick, owner = the pick's
                password = keptPassword(descriptor, false);
                admin = keptPassword(descriptor, true);
            }
        }
        if (!offered) {
            respond(ex, 200, JSON, OpsConsoleForms.errorJson("Reveal refused", REVEAL_UNKNOWN_SHAPE));
            return;
        }
        System.out.println("cc-console reveal");
        respond(ex, 200, JSON, "{\"ok\":true,\"password\":\"" + OpsConsoleForms.jsonEscape(password)
                + "\",\"adminPassword\":\"" + OpsConsoleForms.jsonEscape(admin) + "\"}");
    }

    // ---- POST /api/brick -----------------------------------------------------------------------

    /**
     * Copy join brick
     * (release parity, {@code setup.brick}). WHY server-side:
     * the brick is built against the RUNNING serve's port, and the {@code keep} password is the SAVED
     * one - neither is the page's to know. Order = Swing's: the form's control chars refused
     * ({@link OpsConsoleForms#refuseControlChars}, the Start way); the endpoint
     * ({@link ServerOpsLaunch#packageBrickEndpoint} over the typed {@code advertise} and the live
     * snapshot - Swing's {@code liveSnapshot} rule: the status file's snapshot only while
     * {@link ServerOpsCore#liveServer} holds for the derived state); the password (the Start form's
     * tri-state: {@code keep} = the standing row's saved sidecar password read under the start lock,
     * {@code replace} = the typed text); {@link ServerOpsCore#joinBrick}. Any refusal = the
     * {@value #BRICK_REFUSED} error. Then the endpoint is saved as the {@code launcher.properties}
     * {@code advertise} row for the next window (a failed save = one {@code [ops] Advertise not saved}
     * line with its class, {@code advertiseSaved=false}, the brick still answered). ONE stdout row
     * {@code cc-console brick}: no secret, no endpoint, no length (row {@code web-brick}).
     *
     * <p>The control chars are refused before the gateway is
     * asked (a refused form sends no datagram). In the
     * ProtonVPN mode ({@link ServerOpsLaunch#protonMode} over the saved rows and the form's
     * {@code bindHost} + {@code advertise}) the gateway is asked again ({@link #sharedProbe}, outside
     * the start lock): an answer's exit:port is the endpoint ({@link ServerOpsLaunch#brickEndpoint} in
     * {@link ServerOpsLaunch.BindWords#PACKAGE} words, checked against the live serve), saved as the
     * fill reference ({@link ServerOpsLaunch#writeFillRows}) and answered as {@code "fill"}; a silent
     * gateway bricks the typed Advertise as above and answers {@code "note"} =
     * {@link VpnLeaseProbe#NOTE_BRICK_UNCHECKED}. Out of the mode, a saved {@code protonvpn} row turns
     * {@code typed} with the Advertise save (row {@code web-brick-lease}).
     */
    private void handleBrick(HttpExchange ex) throws IOException {
        Map<String, List<String>> form = readForm(ex, BRICK_REFUSED);
        if (form == null) {
            return;
        }
        OpsSnapshot live = liveOrNull();
        String advertise = OpsConsoleForms.first(form, "advertise");
        boolean owner = roots.mode() == OpsRoots.Mode.OWNER;
        Path launcherFile = ServerOpsLaunch.launcherPropertiesFile();
        Map<String, String> rows = owner ? Map.of() : ServeLauncher.readLauncherRows(launcherFile, System.out);
        // the ProtonVPN mode re-ask, outside startLock; the form's control chars are refused FIRST,
        // so a refused form never sends the gateway a datagram
        boolean inMode = false;
        VpnLeaseProbe.Probe probe = null;
        try {
            OpsConsoleForms.refuseControlChars(form);
            inMode = !owner && ServerOpsLaunch.protonMode(rows, OpsConsoleForms.first(form, "bindHost"), advertise);
        } catch (IllegalArgumentException refused) { // the operator-facing refusal text
            respond(ex, 200, JSON, OpsConsoleForms.errorJson(BRICK_REFUSED, refused.getMessage()));
            return;
        }
        if (owner) {
            ownerBrick(ex, form, advertise, live);
            return;
        }
        if (inMode) {
            probe = probeOrNull("brick");
        }
        String endpoint;
        String brick;
        try {
            int leasePort = probe == null ? 0 : probe.portFound() ? probe.port()
                    : ServerOpsLaunch.portOf(advertise == null ? "" : advertise.trim());
            endpoint = leasePort >= 1 && leasePort <= 65535
                    ? ServerOpsLaunch.brickEndpoint(new ServerOpsCore.VpnLease(probe.exitIp(), leasePort,
                            probe.tunIp()), null, advertise, live, ServerOpsLaunch.BindWords.PACKAGE)
                    : ServerOpsLaunch.packageBrickEndpoint(advertise, live);
            brick = ServerOpsCore.joinBrick(endpoint, brickPassword(form));
        } catch (IllegalArgumentException refused) { // the operator-facing refusal text
            respond(ex, 200, JSON, OpsConsoleForms.errorJson(BRICK_REFUSED, refused.getMessage()));
            return;
        }
        // Every save rides saveLauncherRows (under startLock beside Start's savingLauncherRow - two
        // whole-file rewrites of launcher.properties never lose each other's row)
        boolean advertiseSaved;
        String extra = "";
        if (probe != null) { // the re-ask answered: its endpoint becomes the saved fill reference
            String tun = probe.tunIp();
            String fillPort = Integer.toString(ServerOpsLaunch.portOf(endpoint));
            // The unsaved Setup settles to the same three values the
            // page's address fields take from the "fill" member below
            advertiseSaved = saveLauncherRows(() -> ServerOpsLaunch.writeFillRows(launcherFile, tun, endpoint),
                    addressSettle(tun, fillPort, endpoint));
            extra = ",\"fill\":" + fillJson(new VpnLeaseProbe.Fill(tun, fillPort, endpoint, probe.portFound(), ""));
        } else {
            boolean typedEdit = !inMode && savedProton(rows);
            // The saved Advertise row is the one draftable row this save
            // settles (the trimmed typed text); the mode row settles no Setup field
            advertiseSaved = saveLauncherRows(() -> {
                ServerOpsLaunch.writeLauncherRow(launcherFile, ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE, endpoint);
                if (typedEdit) { // the reference is saved, so a mismatch IS an edit
                    ServerOpsLaunch.writeModeRow(launcherFile, false);
                }
            }, addressSettle(null, null, endpoint));
            if (inMode) { // the gateway did not answer: the saved address stands, unchecked
                extra = ",\"note\":\"" + OpsConsoleForms.jsonEscape(VpnLeaseProbe.NOTE_BRICK_UNCHECKED) + "\"";
            }
        }
        System.out.println("cc-console brick");
        respond(ex, 200, JSON, "{\"ok\":true,\"brick\":\"" + OpsConsoleForms.jsonEscape(brick)
                + "\",\"advertiseSaved\":" + advertiseSaved + extra + "}");
    }

    /**
     * Copy join brick in owner mode, the Swing owner button's order.
     * The lease is pulled FIRST through the console's ONE shared gateway flight ({@link #sharedProbe}, then
     * {@link VpnLeaseProbe.Probe#toLease} - the Swing owner lease); a failure prints ONE
     * {@code cc-console lease result=unchecked reason=<token> press=brick} row (the {@link #probeOrNull} row)
     * and keeps its sentence, which {@link ServerOpsLaunch#brickEndpoint}'s no-lease refusals name. The
     * endpoint is checked against the RUNNING serve in {@link ServerOpsLaunch.BindWords#OWNER} words; the
     * password = {@link #brickPassword}; then {@link ServerOpsCore#joinBrick}. WHY no save: the Swing owner
     * brick writes no launcher row, so the answer carries {@code advertiseSaved:false} plus {@code fill} when a
     * lease answered. The control chars were refused by the caller before the gateway is asked.
     */
    private void ownerBrick(HttpExchange ex, Map<String, List<String>> form, String advertise, OpsSnapshot live)
            throws IOException {
        ServerOpsCore.VpnLease lease = null;
        String why = null;
        try {
            lease = sharedProbe().toLease();
        } catch (IllegalStateException | IOException failed) { // one bounded row; the sentence feeds the refusal
            why = failed instanceof IOException ? failed.getClass().getSimpleName() : failed.getMessage();
            System.out.println("cc-console lease result=unchecked reason=" + leaseReason(failed) + " press=brick");
        }
        String endpoint;
        String brick;
        try {
            endpoint = ServerOpsLaunch.brickEndpoint(lease, why, advertise, live, ServerOpsLaunch.BindWords.OWNER);
            brick = ServerOpsCore.joinBrick(endpoint, brickPassword(form));
        } catch (IllegalArgumentException refused) { // the operator-facing refusal text
            respond(ex, 200, JSON, OpsConsoleForms.errorJson(BRICK_REFUSED, refused.getMessage()));
            return;
        }
        String fill = lease == null ? "" : ",\"fill\":" + fillJson(new VpnLeaseProbe.Fill(lease.tunIp(),
                Integer.toString(lease.port()), endpoint, true, ""));
        System.out.println("cc-console brick");
        respond(ex, 200, JSON, "{\"ok\":true,\"brick\":\"" + OpsConsoleForms.jsonEscape(brick)
                + "\",\"advertiseSaved\":false" + fill + "}");
    }

    /** The brick's password, both modes - the Start form's
     * tri-state: {@code keep} (or no action) = the standing row's saved sidecar password read under
     * {@link #startLock}; {@code replace} = the typed text ({@link OpsConsoleForms#matchPasswordText}). */
    private String brickPassword(Map<String, List<String>> form) throws IOException {
        String passwordAction = OpsConsoleForms.first(form, "passwordAction");
        String kept = null;
        if (passwordAction == null || "keep".equals(passwordAction)) {
            synchronized (startLock) {
                kept = keptPassword(null, false);
            }
        }
        return OpsConsoleForms.matchPasswordText(form, kept);
    }

    /** The live gate shared by Copy join brick and Join from this
     * PC (moved out of {@link #handleBrick} verbatim) - Swing's {@code liveSnapshot} rule: the status
     * file's snapshot only while {@link ServerOpsCore#liveServer} holds for the derived state, else null. */
    private OpsSnapshot liveOrNull() {
        Follow f = followed.get(); // ONE read: the snapshot and its writer watch belong to one file
        OpsSnapshot snap = readSnapshotOrNull(f.opsFile());
        boolean pidAlive = snap != null && ServerOpsCore.writerAlive(snap);
        ServerState state = deriveTracked(f, snap, pidAlive, System.currentTimeMillis());
        return ServerOpsCore.liveServer(state) ? snap : null;
    }

    // ---- POST /api/join ------------------------------------------------------------------------

    /**
     * Join from this PC, the web twin of the Swing package button
     * for release parity: opens
     * the player join window ON THE SERVER COMPUTER, filled in with this serve's address, port and saved
     * match password. WHY server-side: the dial is the RUNNING serve's and the password is the SAVED
     * sidecar's - neither is the page's to know - and the window is a process on this computer. The body
     * is an empty form (the funnel's {@link #readForm}; nothing in it is read). The press =
     * {@link #pressJoinOnce} with NO diagnostics folder (a hand press; only a window the debug watch opens
     * gets them): one press in flight (a second POST answers {@link OpsJoinSelf#R8} at once), the flag
     * released BEFORE the answer is sent, so a client that reads the answer and presses again never meets a
     * stale R8. Answers {@code {ok, detail}} or the {@link OpsJoinSelf#TITLE} error; ONE stdout row per press
     * (row {@code web-join}).
     */
    private void handleJoin(HttpExchange ex) throws IOException {
        Map<String, List<String>> form = readForm(ex, OpsJoinSelf.TITLE);
        if (form == null) {
            return;
        }
        if (roots.mode() == OpsRoots.Mode.OWNER) {
            ownerJoin(ex);
            return;
        }
        respond(ex, 200, JSON, pressJoinOnce(false, null, detail -> { }));
    }

    /** The Swing owner Joiner GUI button's note, verbatim. */
    static final String JOINER_GUI_NOTE = "Joiner GUI handed to " + ServerOpsCore.JOINER_GUI_SCRIPT
            + " (re-opens the live tab, else a fresh join-cc.bat; log " + ServerOpsCore.JOINER_GUI_LOG + ")";

    /**
     * The join POST in owner mode = the Swing owner window's Joiner
     * GUI button - ONE fixed script spawn ({@link OwnerLaunch#joinerGui}; it re-opens the live join window's
     * tab, else arms a fresh one), never the package's Join from this PC press (its one-in-flight flag and the
     * debug-watch daemon stay the package's). Answers {@code {ok, detail:JOINER_GUI_NOTE}} or the
     * {@code Joiner GUI} error naming only the exception's SimpleName (a message can carry a path); ONE stdout
     * row {@code cc-console join result=spawned-joiner-gui|fault}.
     */
    private void ownerJoin(HttpExchange ex) throws IOException {
        try {
            ownerLaunch.joinerGui();
        } catch (IOException e) { // answered with the class name only, one row
            System.out.println("cc-console join result=fault");
            respond(ex, 200, JSON, OpsConsoleForms.errorJson("Joiner GUI", "Could not launch "
                    + ServerOpsCore.JOINER_GUI_SCRIPT + " (" + e.getClass().getSimpleName() + ")"));
            return;
        }
        System.out.println("cc-console join result=spawned-joiner-gui");
        respond(ex, 200, JSON, "{\"ok\":true,\"detail\":\"" + OpsConsoleForms.jsonEscape(JOINER_GUI_NOTE) + "\"}");
    }

    // ---- POST /api/dev-editor ------------------------------------------------------------------

    /** The Swing owner Dev editor button's note, verbatim. */
    static final String DEV_EDITOR_NOTE = "Dev editor handed to " + ServerOpsCore.DEV_EDITOR_SCRIPT
            + " (boots the stage if needed, opens one tab; log " + ServerOpsCore.DEV_EDITOR_LOG + ")";

    /**
     * Dev editor, the Swing owner window's button - keyed POST, an
     * empty form (the funnel's {@link #readForm}; nothing in it is read). Owner mode only: a package console
     * answers {@code {"ok":false,"detail":"refused:not_owner"}} (the repository's script is not there).
     * Owner = ONE fixed script spawn ({@link OwnerLaunch#devEditor}: no request text reaches its argv) answering
     * {@code {ok, detail:DEV_EDITOR_NOTE}}, or the {@code Dev editor} error naming only the exception's
     * SimpleName. ONE stdout row {@code cc-console dev-editor result=spawned|refused:not_owner|fault}.
     */
    private void handleDevEditor(HttpExchange ex) throws IOException {
        Map<String, List<String>> form = readForm(ex, "Dev editor refused");
        if (form == null) {
            return;
        }
        if (roots.mode() != OpsRoots.Mode.OWNER) {
            System.out.println("cc-console dev-editor result=refused:not_owner");
            respond(ex, 200, JSON, "{\"ok\":false,\"detail\":\"refused:not_owner\"}");
            return;
        }
        try {
            ownerLaunch.devEditor();
        } catch (IOException e) { // answered with the class name only, one row
            System.out.println("cc-console dev-editor result=fault");
            respond(ex, 200, JSON, OpsConsoleForms.errorJson("Dev editor", "Could not launch "
                    + ServerOpsCore.DEV_EDITOR_SCRIPT + " (" + e.getClass().getSimpleName() + ")"));
            return;
        }
        System.out.println("cc-console dev-editor result=spawned");
        respond(ex, 200, JSON, "{\"ok\":true,\"detail\":\"" + OpsConsoleForms.jsonEscape(DEV_EDITOR_NOTE) + "\"}");
    }

    /**
     * One Join-from-this-PC press under the one-in-flight law, shared by the join POST (its thread) and the
     * debug-watch daemon ({@link ServerOpsCore#armWatchJoin}'s press, the daemon's thread). The compare-and-set
     * on {@link #joinInFlight} is the ONLY state the two threads share here: a press while another is in
     * flight answers {@link OpsJoinSelf#R8} at once (the daemon never waits); else {@link #joinAnswer} in the
     * shape {@code watch} names (false = the hand press; true = the debug watch's, {@code flightOrNull} its flight
     * folder or null when the pointer is not written yet), the flag cleared in {@code finally}. Returns the
     * POST's answer JSON (and prints the press's ONE stdout row); {@code detailSink} gets the same outcome's
     * sentence, never JSON. Package-visible for the selftest's one-in-flight leg.
     *
     */
    String pressJoinOnce(boolean watch, Path flightOrNull, java.util.function.Consumer<String> detailSink) {
        return pressJoinOnce(watch, flightOrNull, detailSink, opened -> { });
    }

    /** {@link #pressJoinOnce(boolean, Path, java.util.function.Consumer)} handing the windows the press opened and
     *  recorded (the debug watch's only, {@link OpsJoinSelf.Result#windows}) to {@code openedSink} - the debug-watch
     *  daemon's launch set, closed when that watch's serve ends. */
    String pressJoinOnce(boolean watch, Path flightOrNull, java.util.function.Consumer<String> detailSink,
            java.util.function.Consumer<List<JoinWindowSweep.Window>> openedSink) {
        if (!joinInFlight.compareAndSet(false, true)) {
            detailSink.accept(OpsJoinSelf.R8);
            return joinRefused("r8", OpsJoinSelf.R8);
        }
        JoinPress p;
        try {
            p = joinAnswer(watch, flightOrNull);
        } finally {
            joinInFlight.set(false);
        }
        openedSink.accept(p.windows());
        String json = p.ok() ? joinOk(p.token(), p.text()) : joinRefused(p.token(), p.text());
        detailSink.accept(p.text());
        return json;
    }

    /** One press's outcome, read by both {@link #pressJoinOnce} callers without parsing JSON: {@code ok}, the
     * bounded stdout token ({@code opened} / {@code reopened}, or the refusal's reason) and the operator sentence
     * (the core's own text). {@code windows} = the press's recorded windows
     * (empty but for a debug-watch OPENED). */
    private record JoinPress(boolean ok, String token, String text, List<JoinWindowSweep.Window> windows) {
        JoinPress(boolean ok, String token, String text) {
            this(ok, token, text, List.of());
        }
    }

    /**
     * One press's outcome. Order: a non-Windows console =
     * {@link OpsJoinSelf#R9}, as the player kit runs on Windows only; the live gate
     * ({@link #liveOrNull}); the launcher rows read once; the password = the SAVED sidecar read under
     * {@link #startLock} (the brick's {@code keep} path, never a form field); then
     * {@link OpsJoinSelf#press} OUTSIDE the start lock with the probe swapped for {@link #sharedProbe}
     * (a silent gateway's ~3 s must never stall a Start; its refusals reach the core, which answers
     * R10). OPENED = the core's note; REOPENED = {@link #reopenOnServer}; REFUSED = the core's sentence.
     * A fault (a {@link RuntimeException}, or the config file unreadable) = {@code Join failed
     * (<SimpleName>)}, token {@code press_fault}. Prints no outcome row: {@link #pressJoinOnce} prints it.
     *
     * <p>{@code watch} false = the hand
     * press, {@link OpsJoinSelf#press}, no diagnostics; true = the debug watch's press, {@link OpsJoinSelf#pressWatch}
     * over {@code flightOrNull}, which places each window's diagnostics and sidelog under the flight folder itself
     * ({@code client}, {@code client2}; null = no diagnostics) and presses the second window when the second Game
     * folder is set - the caller resolves no sub-folder and sets no client diagnostics of its own.
     *
     * <p>The window's password = the kept MATCH password, and the admin
     * elevation rides {@link OpsJoinSelf.Inputs#adminArm} - the serve's seat-key arm of a per-press env-minted key,
     * so neither an admin secret nor the armed key ever sits in the window's settings file.
     * WHY: the window has one password slot,
     * so a pasted brick overwrote a kept admin secret there (a silent downgrade). The match password is read under
     * the lock;
     * the package's kit stamps ({@link ServerOpsCore#packageKitStamps}) ride the inputs too; the inputs land
     * in {@link #lastPressInputs} right before the press (row {@code web-join-admin-first}).
     *
     * <p>The arm is set on EVERY press whose live serve names a command
     * file ({@link OpsJoinSelf#armFileOrNull}, the predicate step (6b)'s write uses) - a saved admin secret is no
     * longer needed and is not read for it, so the host's first press signs its window in as admin with nothing
     * typed. A REOPENED answer arms nothing (its note names the host's ways back after a restart); a token-mode
     * door refuses the arm, so those windows open plain.
     *
     * <p>Once this console has appended the door's cap of arm lines to
     * the live serve ({@link ArmLedger}, keyed by the status file's pid), a press arms NOTHING - its window opens
     * plain and an OPENED answer ends with {@link #NOTE_ARM_CAP} - and prints ONE {@code cc-console join
     * admin_arm=withheld_cap} row beside {@link #pressJoinOnce}'s outcome row; every press over a live serve
     * then adds the lines it appended ({@link OpsJoinSelf.Result#armLines}). WHY here: the door refuses a key past
     * its cap in the serve's own log only, so without this count that window opened plain under the signed-in
     * note.
     */
    private JoinPress joinAnswer(boolean watch, Path flightOrNull) {
        try {
            if (!ServerOpsCore.joinHere(System.getProperty("os.name"))) {
                return new JoinPress(false, "r9", OpsJoinSelf.R9);
            }
            OpsSnapshot live = liveOrNull();
            Map<String, String> rows = ServeLauncher.readLauncherRows(ServerOpsLaunch.launcherPropertiesFile(),
                    System.out);
            String kept;
            synchronized (startLock) {
                // The press reads no match file - the join window fetches the
                // match from the server. The window gets the MATCH password; the ADMIN secret is not read at all.
                kept = keptPassword(null, false);
            }
            // Every press arms the host's own window whenever its arm line
            // can land - the same command-file predicate step (6b)'s write uses, so no secret gates the arm.
            boolean armable = OpsJoinSelf.armFileOrNull(live) != null;
            boolean capFull = armable && armLedger.full(live.pid(), live.upSeconds());
            boolean adminArm = armable && !capFull;
            if (capFull) {
                System.out.println("cc-console join admin_arm=withheld_cap");
            }
            OpsJoinSelf.Inputs in = pressInputs(live, rows, kept, adminArm);
            lastPressInputs.set(in);
            OpsJoinSelf.Effects fx = joinFx.withProbe(this::sharedProbe);
            OpsJoinSelf.Result r = watch ? OpsJoinSelf.pressWatch(in, fx, flightOrNull) : OpsJoinSelf.press(in, fx);
            if (live != null) {
                armLedger.add(live.pid(), live.upSeconds(), r.armLines());
            }
            return switch (r.outcome()) {
                case OPENED -> new JoinPress(true, "opened", capFull ? r.text() + " " + NOTE_ARM_CAP : r.text(),
                        r.windows());
                case REOPENED -> reopenOnServer(rows, joinFx.parentEnv());
                case REFUSED -> new JoinPress(false, r.reasonToken(), r.text());
            };
        } catch (IOException | RuntimeException fault) { // the fixed sentence naming the class, no message
            return new JoinPress(false, "press_fault", "Join failed (" + fault.getClass().getSimpleName() + ")");
        }
    }

    /**
     * The press inputs every join-shaped call builds the same way: the package root, the launcher rows, the live
     * snapshot, the window's password, the kit hint, the settings-file locations from the SAME environment the
     * press spawns with ({@link OpsJoinSelf.Effects#parentEnv}), the admin-arm bit and the
     * package MANIFEST's kit stamps ({@link ServerOpsCore#packageKitStamps}).
     */
    private OpsJoinSelf.Inputs pressInputs(OpsSnapshot live, Map<String, String> rows, String kept, boolean adminArm)
            throws IOException {
        Map<String, String> env = joinFx.parentEnv();
        ServerOpsCore.KitStamps stamps = ServerOpsCore.packageKitStamps(roots.pkg());
        return new OpsJoinSelf.Inputs(roots.pkg(), rows, live, kept, OpsJoinSelf.KIT_HINT,
                env.get("LOCALAPPDATA"), env.get("APPDATA"), env.get("USERPROFILE"), null)
                .withAdminArm(adminArm)
                .withKitStamps(stamps.jarMd5OrNull(), stamps.fingerprintOrNull(), stamps.zipOrNull());
    }

    /**
     * The debug watch's player-kit check before its Start ({@link OpsJoinSelf#kitCheckOrNull}: the install law, then
     * the installed kit against the package's stamps, a stale kit refreshed from the package's kit zip). The inputs
     * are the hand press's with NO secrets (password blank, no arm: the check reads neither) over {@code rows} = the
     * launcher rows as the Start is about to save them ({@link #watchRows}: the Start saves the form's Game folder,
     * so the check must read it); {@link #lastPressInputs} is untouched (no press). Null = current or skipped.
     * The route's Windows gate ({@link #handleWatch}) already answered a non-Windows host. Thread: the HTTP handler;
     * no lock held (the serving-descriptor read that once sat here fed the Press field; the join window fetches the
     * match over the wire, so the read died with the field at the fold).
     *
     */
    private OpsJoinSelf.KitCheck watchKitCheckOrNull(Map<String, String> rows) throws IOException {
        OpsSnapshot live = liveOrNull();
        return OpsJoinSelf.kitCheckOrNull(pressInputs(live, rows, "", false), joinFx.withProbe(this::sharedProbe));
    }

    /**
     * The launcher rows as a debug-watch Start is about to save them - what the watch's pre-Start checks read (the
     * second-folder check, then the kit check): the saved rows with the form's Game folder ({@code typedGameRoot};
     * blank = the saved row stands, the Start's own blank-is-no-write wrap) and second folder ({@code typedSecond}:
     * null = the row untouched, blank = CLEARED = one window, else the typed folder - the law of
     * {@link #savingOrClearingLauncherRow}) overlaid.
     */
    private static Map<String, String> watchRows(String typedGameRoot, String typedSecond) throws IOException {
        Map<String, String> rows = new java.util.HashMap<>(ServeLauncher.readLauncherRows(
                ServerOpsLaunch.launcherPropertiesFile(), System.out));
        if (typedGameRoot != null && !typedGameRoot.isBlank()) {
            rows.put(ServeLauncher.ROW_GAME_ROOT, typedGameRoot);
        }
        if (typedSecond != null) {
            if (typedSecond.isBlank()) {
                rows.remove(ServeLauncher.ROW_GAME_ROOT_SECOND);
            } else {
                rows.put(ServeLauncher.ROW_GAME_ROOT_SECOND, typedSecond);
            }
        }
        return rows;
    }

    /**
     * The join settings' secret scrub at a debug watch's END ({@link OpsJoinSelf#scrubSettingsSecret}; the lease
     * daemon's {@code atEnd}). The files are located from the SAME environment the press located them with
     * ({@link OpsJoinSelf.Effects#parentEnv}) and touched through the same effects - so a console whose effects are
     * not the live ones (a selftest's) scrubs nothing of the real user's. Prints its own ONE row, never a value.
     * Thread: the lease daemon.
     */
    private void scrubJoinSettings() {
        Map<String, String> env = joinFx.parentEnv();
        OpsJoinSelf.scrubSettingsSecret(joinFx, env.get("LOCALAPPDATA"), env.get("APPDATA"), env.get("USERPROFILE"));
    }

    /**
     * The web REOPENED branch. A join window
     * is already live on the server computer, and its URL must never cross HTTP (the page's viewer may
     * sit on another computer), so the kit's bat is started WITHOUT the settings write: the bat's own
     * single-instance law re-opens the live window's tab there. The spawn = the core's fixed argv / cwd
     * / child env; a start failure or a nonzero exit within the wait = {@link OpsJoinSelf#R7}, worded
     * exactly as the core's step 7 words it.
     */
    private JoinPress reopenOnServer(Map<String, String> rows, Map<String, String> env) {
        Path install = OpsJoinSelf.installOrNull(roots.pkg(), rows);
        if (install == null) { // the Game folder went away since the core's step 1
            return new JoinPress(false, "r1", OpsJoinSelf.R1);
        }
        String shown = OpsJoinSelf.boundedText(install.toString());
        int code;
        try {
            code = joinFx.spawn(OpsJoinSelf.argv(joinFx.systemRoot()), OpsJoinSelf.cwd(install),
                    OpsJoinSelf.childEnv(env));
        } catch (IOException | RuntimeException startFailed) { // answered as R7 naming its class
            return new JoinPress(false, "spawn_failed", String.format(Locale.ROOT, OpsJoinSelf.R7, shown,
                    startFailed.getClass().getSimpleName()));
        }
        if (code != 0 && code != OpsJoinSelf.STILL_RUNNING) {
            return new JoinPress(false, "spawn_nonzero", String.format(Locale.ROOT, OpsJoinSelf.R7, shown,
                    Integer.toString(code)));
        }
        return new JoinPress(true, "reopened", OpsJoinSelf.NOTE_REOPENED_WEB);
    }

    /** The press's ONE stdout row (bounded tokens: never a path, host, port or secret) + {@code {ok, detail}}. */
    private static String joinOk(String outcome, String detail) {
        System.out.println("cc-console join outcome=" + outcome + " reason=-");
        return "{\"ok\":true,\"detail\":\"" + OpsConsoleForms.jsonEscape(detail) + "\"}";
    }

    /** The press's ONE stdout row + the {@link OpsJoinSelf#TITLE} error carrying {@code text}. */
    private static String joinRefused(String reason, String text) {
        System.out.println("cc-console join outcome=refused reason=" + reason);
        return OpsConsoleForms.errorJson(OpsJoinSelf.TITLE, text);
    }

    // ---- POST /api/lease + the shared ProtonVPN flight ------------------------------------------

    /**
     * Fill from ProtonVPN, the Swing package button's twin
     * ({@code setup.lease}). Order: the form's control chars refused
     * ({@link OpsConsoleForms#refuseControlChars}); ONE gateway pull ({@link #sharedProbe} - a POST
     * that arrives while a pull runs takes its result); {@link VpnLeaseProbe#fill} over the form's
     * {@code bindPort} (the found port wins, else the typed one); the fill saved as the mode's
     * reference ({@link ServerOpsLaunch#writeFillRows} under the start lock; a failed save = one
     * {@code [ops]} line and {@code "saved":false}, the fill still answered). Answers
     * {@code {ok, bindHost, bindPort, advertise, portFound, saved, note}} or the
     * {@value #LEASE_REFUSED} error with the probe's sentence (N1-N4) or the control-char refusal.
     * ONE stdout row {@code cc-console lease result=filled|address_only|refused reason=<token>}:
     * bounded tokens only, never an address, a port or a sentence (row {@code web-lease}).
     */
    private void handleLease(HttpExchange ex) throws IOException {
        Map<String, List<String>> form = readForm(ex, LEASE_REFUSED);
        if (form == null) {
            return;
        }
        VpnLeaseProbe.Fill fill;
        try {
            OpsConsoleForms.refuseControlChars(form);
            fill = VpnLeaseProbe.fill(sharedProbe(), OpsConsoleForms.first(form, "bindPort"));
        } catch (IllegalArgumentException | IllegalStateException | IOException refused) { // one bounded row + the sentence
            System.out.println("cc-console lease result=refused reason=" + leaseReason(refused));
            String sentence = refused instanceof IOException
                    ? String.format(Locale.ROOT, VpnLeaseProbe.N4, refused.getClass().getSimpleName())
                    : refused.getMessage();
            respond(ex, 200, JSON, OpsConsoleForms.errorJson(LEASE_REFUSED, sentence));
            return;
        }
        Path launcherFile = ServerOpsLaunch.launcherPropertiesFile();
        // The unsaved Setup settles to the three values this answer puts
        // into the page's address fields (a blank typed port in an address-only fill settles nothing)
        boolean saved = saveLauncherRows(() -> ServerOpsLaunch.writeFillRows(launcherFile, fill.bindHost(),
                fill.advertise()), addressSettle(fill.bindHost(), fill.bindPort(), fill.advertise()));
        System.out.println("cc-console lease result="
                + (fill.portFound() ? "filled reason=none" : "address_only reason=no_port"));
        respond(ex, 200, JSON, "{\"ok\":true,\"bindHost\":\"" + OpsConsoleForms.jsonEscape(fill.bindHost())
                + "\",\"bindPort\":\"" + OpsConsoleForms.jsonEscape(fill.bindPort())
                + "\",\"advertise\":\"" + OpsConsoleForms.jsonEscape(fill.advertise())
                + "\",\"portFound\":" + fill.portFound() + ",\"saved\":" + saved
                + ",\"note\":\"" + OpsConsoleForms.jsonEscape(fill.note()) + "\"}");
    }

    /**
     * ONE ProtonVPN gateway pull that any number of requests
     * JOIN. WHY shared, never queued: the pull is read-only (two bytes to the
     * gateway, then a log read), so every request arriving while a pull runs takes that pull's
     * result - a flood of POSTs costs one pull per gateway wait (at most ~3.1 s), never one datagram
     * per POST. The request that finds no live flight runs the pull on ITS OWN request thread
     * (the 4-thread pool); joiners block on its future. Never called under {@link #startLock}: a
     * silent gateway's 3 s wait would stall every Start, brick and Voice write behind it.
     *
     * @throws IllegalStateException the probe's refusal sentence (N1-N3), or N4 naming any other failure
     * @throws IOException the probe could not ask (the socket would not open, the log folder unlistable)
     */
    private VpnLeaseProbe.Probe sharedProbe() throws IOException {
        CompletableFuture<VpnLeaseProbe.Probe> mine = new CompletableFuture<>();
        CompletableFuture<VpnLeaseProbe.Probe> flight = leaseFlight.updateAndGet(
                cur -> cur == null || cur.isDone() ? mine : cur);
        if (flight == mine) {
            try {
                mine.complete(VpnLeaseProbe.pull(leaseEnv));
            } catch (Throwable failed) { // handed to the creator and every joiner through the future
                mine.completeExceptionally(failed);
            }
        }
        try {
            return flight.join();
        } catch (CompletionException failed) { // unwrapped: the probe's own refusal types pass through
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            if (cause instanceof IllegalStateException refusal) {
                throw refusal;
            }
            if (cause instanceof IOException couldNotAsk) {
                throw couldNotAsk;
            }
            throw new IllegalStateException(String.format(Locale.ROOT, VpnLeaseProbe.N4,
                    cause.getClass().getSimpleName()));
        }
    }

    /** The in-mode Start / brick re-ask - the shared pull, or
     * {@code null} when the gateway did not answer (the fallback rule: the press goes on, unchecked)
     * after ONE bounded row {@code cc-console lease result=unchecked reason=<token> press=<press>}. */
    private VpnLeaseProbe.Probe probeOrNull(String press) {
        try {
            return sharedProbe();
        } catch (IllegalStateException | IOException silent) { // logged once here; the press answers its note
            System.out.println("cc-console lease result=unchecked reason=" + leaseReason(silent) + " press=" + press);
            return null;
        }
    }

    /** The stdout token for a lease refusal - a fixed word per cause, never the sentence. */
    static String leaseReason(Exception refused) {
        if (refused instanceof IllegalArgumentException) {
            return "form";
        }
        String m = refused instanceof IOException ? null : refused.getMessage();
        return VpnLeaseProbe.N1.equals(m) ? "gateway_silent" : VpnLeaseProbe.N2.equals(m) ? "not_public"
                : VpnLeaseProbe.N3.equals(m) ? "no_local_address" : "could_not_ask";
    }

    // ---- POST /api/reach ------------------------------------------------------------------------

    /** The error title of a refused reachability press. */
    static final String REACH_REFUSED = "Check from the internet";
    /** The longest Advertise text a press dials (a DNS name's own bound). */
    static final int REACH_ADVERTISE_MAX = 253;

    /**
     * Check from the internet, the Swing {@code setup.reach}
     * twin. Form {@code bindHost bindPort advertise}. Order: the form's CR / LF / NUL refused
     * ({@link OpsConsoleForms#refuseControlChars}); the target ({@link #reachTarget}: Advertise as ONE
     * whitespace-free token of at most {@value #REACH_ADVERTISE_MAX} chars, {@code host:port} or a bare host
     * dialed at {@code bindPort}; else {@link ReachProbe#TYPE_ADDRESS}, no datagram); the live gate (Swing's
     * {@code reachPress} order: no running server by {@link #liveOrNull} = {@link ReachProbe#START_FIRST}, no
     * datagram, no DNS); the ProtonVPN sentence pick
     * ({@link ServerOpsLaunch#protonMode} over the saved rows, the brick's compare); then
     * {@link ServerOpsCore#checkReach} OUTSIDE {@link #startLock} - the ONE shared dial (a press of the same
     * target joins it, another target while one runs = busy), waited at most
     * {@link ServerOpsCore#REACH_PRESS_WAIT_MS}. Answers {@code {ok:true, verdict:reached|local|silent|refused,
     * ms, note}}, {@code {ok:true, verdict:busy, note}} or {@code {error, title}}. STDOUT: the dial itself prints
     * ONE {@code cc-console reach result=<verdict> reason=<token>} row (ServerOpsCore's flight; a joiner adds
     * none); a press that dials nothing prints ONE {@code cc-console reach result=not_dialed
     * reason=control_char|address|start_first|busy}. Never an address, a host name or a port (rows {@code web-reach},
     * {@code web-status-reach}). Outbound: at most 3 x 159 B UDP per dial to the host's own typed address.
     */
    private void handleReach(HttpExchange ex) throws IOException {
        Map<String, List<String>> form = readForm(ex, REACH_REFUSED);
        if (form == null) {
            return;
        }
        String advertise = OpsConsoleForms.first(form, "advertise");
        String[] target;
        try {
            OpsConsoleForms.refuseControlChars(form);
        } catch (IllegalArgumentException refused) { // the fixed CR / LF / NUL text; nothing dialed
            System.out.println("cc-console reach result=not_dialed reason=control_char");
            respond(ex, 200, JSON, OpsConsoleForms.errorJson(REACH_REFUSED, refused.getMessage()));
            return;
        }
        target = reachTarget(advertise, OpsConsoleForms.first(form, "bindPort"));
        if (target == null) {
            System.out.println("cc-console reach result=not_dialed reason=address");
            respond(ex, 200, JSON, OpsConsoleForms.errorJson(REACH_REFUSED, ReachProbe.TYPE_ADDRESS));
            return;
        }
        // Swing's reachPress gate - no
        // running server = nothing to answer the dial: refused, no dial, no DNS; the address is judged first
        if (liveOrNull() == null) {
            System.out.println("cc-console reach result=not_dialed reason=start_first");
            respond(ex, 200, JSON, OpsConsoleForms.errorJson(REACH_REFUSED, ReachProbe.START_FIRST));
            return;
        }
        Map<String, String> rows = ServeLauncher.readLauncherRows(ServerOpsLaunch.launcherPropertiesFile(), System.out);
        boolean protonVpn = ServerOpsLaunch.protonMode(rows, OpsConsoleForms.first(form, "bindHost"), advertise);
        int port = Integer.parseInt(target[1]);
        ReachProbe.Result r;
        try {
            r = ServerOpsCore.checkReach(target[0], port, target[0] + ":" + port, protonVpn);
        } catch (IllegalStateException busy) { // another address is being dialed: answered, logged once
            System.out.println("cc-console reach result=not_dialed reason=busy");
            respond(ex, 200, JSON, "{\"ok\":true,\"verdict\":\"busy\",\"note\":\""
                    + OpsConsoleForms.jsonEscape(String.valueOf(busy.getMessage())) + "\"}");
            return;
        }
        respond(ex, 200, JSON, "{\"ok\":true,\"verdict\":\"" + ServerOpsCore.reachToken(r.verdict())
                + "\",\"ms\":" + r.ms() + ",\"note\":\"" + OpsConsoleForms.jsonEscape(r.note()) + "\"}");
    }

    /**
     * A press's dial target {@code [host, port]} (port 1..65535
     * as digits), or null = {@link ReachProbe#TYPE_ADDRESS}. Advertise trimmed must be non-blank, at most
     * {@value #REACH_ADVERTISE_MAX} chars, with no whitespace and no quote (the {@code <public IP>} placeholder
     * holds a space, so it is refused here too). With a colon it is {@code host:port} (the text after the LAST
     * colon = the port, the host non-blank with no second colon); without one it is a bare host dialed at
     * {@code bindPort}. {@code bindPort}: blank = none, else an int 0..65535 (anything else refuses); a port
     * of 0 or none for a bare host refuses (nothing to dial). Pure.
     */
    static String[] reachTarget(String advertise, String bindPort) {
        String adv = advertise == null ? "" : advertise.trim();
        if (adv.isEmpty() || adv.length() > REACH_ADVERTISE_MAX || adv.indexOf('"') >= 0
                || adv.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        String bp = bindPort == null ? "" : bindPort.trim();
        int typedPort = 0;
        if (!bp.isEmpty()) {
            if (!bp.chars().allMatch(c -> c >= '0' && c <= '9') || bp.length() > 5
                    || Integer.parseInt(bp) > 65535) {
                return null;
            }
            typedPort = Integer.parseInt(bp);
        }
        String host = adv;
        int port = typedPort;
        int colon = adv.lastIndexOf(':');
        if (colon >= 0) {
            host = adv.substring(0, colon);
            String p = adv.substring(colon + 1);
            if (p.isEmpty() || p.length() > 5 || !p.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return null;
            }
            port = Integer.parseInt(p);
        }
        if (host.isEmpty() || host.indexOf(':') >= 0 || port < 1 || port > 65535) {
            return null;
        }
        return new String[] {host, Integer.toString(port)};
    }

    /** One {@code launcher.properties} write step (whole-file rewrites through
     * {@link ServerOpsLaunch#writeLauncherRow}). */
    @FunctionalInterface
    private interface LauncherWrite {
        void write() throws IOException;
    }

    /** The brick's save idiom shared by the lease, the Start and
     * the brick - the write under {@link #startLock}; a refused write = ONE
     * {@code [ops] Advertise not saved into launcher.properties (<SimpleName>)} line and {@code false}. This form
     * settles no Setup field (the mode row): {@link #saveLauncherRows(LauncherWrite, Map)} over an empty map. */
    private boolean saveLauncherRows(LauncherWrite write) {
        return saveLauncherRows(write, Map.of());
    }

    /** {@link #saveLauncherRows(LauncherWrite)} whose successful write
     * then re-stamps AND settles the unsaved Setup ({@link #rebaseDraft(Map)}) in the same lock hold -
     * {@code settled} = the draft ids the answer puts into the page's fields, to those values
     * ({@link #addressSettle}); a failed write settles nothing, and a settle fault never turns the save into
     * {@code false}. */
    private boolean saveLauncherRows(LauncherWrite write, Map<String, String> settled) {
        try {
            synchronized (startLock) {
                write.write();
                rebaseDraft(settled); // this console's own write must not make the unsaved Setup look stale
            }
            return true;
        } catch (IOException | RuntimeException notSaved) { // logged once, answered as not saved
            System.out.println("[ops] Advertise not saved into launcher.properties ("
                    + notSaved.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /**
     * {@code spawn} with a launcher row saved or CLEARED right before it,
     * through {@link ServerOpsLaunch#writeOrClearLauncherRow} - a BLANK field removes every {@code key=} row
     * (the debug WATCH's second game folder is optional: a host who cleared the field gets back to one window),
     * a typed one is written verbatim. A {@code null} field (a page or a hand-made request that sends none)
     * returns {@code spawn} itself, the row untouched. The value is not checked here: the press validates it as
     * an install. A refused write throws out of the spawn naming the key, the file NAME and the exception class
     * only (no path, no message); the Start flow answers it as its spawn failure, so the launch never runs.
     */
    private static ServerOpsCore.SpawnFn savingOrClearingLauncherRow(ServerOpsCore.SpawnFn spawn, Path file,
            String key, String fieldText) {
        if (fieldText == null) {
            return spawn;
        }
        return cmd -> {
            try {
                ServerOpsLaunch.writeOrClearLauncherRow(file, key, fieldText);
            } catch (IOException e) {
                throw new IOException("could not save " + key + " into " + file.getFileName() + " ("
                        + e.getClass().getSimpleName() + ")", e);
            }
            return spawn.spawn(cmd);
        };
    }

    /** The saved launcher rows name the ProtonVPN mode ({@code advertise.from}, trimmed). */
    private static boolean savedProton(Map<String, String> rows) {
        String from = rows.get(ServerOpsLaunch.LAUNCHER_ROW_ADVERTISE_FROM);
        return ServerOpsLaunch.ADVERTISE_FROM_PROTONVPN.equals(from == null ? "" : from.trim());
    }

    /** A port field's digits as 1..65535, else 0 (blank / not a number / out of range). */
    private static int portOrZero(String field) {
        try {
            int port = Integer.parseInt(field == null ? "" : field.trim());
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (NumberFormatException notAPort) { // 0 = the PackageBind path names the bad port
            return 0;
        }
    }

    /** The {@code "fill"} member a Start / brick re-ask answers
     * with - the addresses the page puts in its three fields. */
    private static String fillJson(VpnLeaseProbe.Fill fill) {
        return "{\"bindHost\":\"" + OpsConsoleForms.jsonEscape(fill.bindHost())
                + "\",\"bindPort\":\"" + OpsConsoleForms.jsonEscape(fill.bindPort())
                + "\",\"advertise\":\"" + OpsConsoleForms.jsonEscape(fill.advertise())
                + "\",\"note\":\"" + OpsConsoleForms.jsonEscape(fill.note()) + "\"}";
    }

    // ---- POST /api/verb + /api/kill ------------------------------------------------------------

    /**
     * A verb: the CMD gate, then {@link OpsConsoleForms#verbLine} (the Swing minters) and its
     * occupant-bound {@link OpsConsoleForms#verbConfirm}: a confirm whose key the form's
     * {@code confirmed=} list lacks answers {@code needsConfirm} and writes NOTHING; else the line
     * is appended to the command file the snapshot self-advertises.
     */
    private void handleVerb(HttpExchange ex) throws IOException {
        Map<String, List<String>> form = readForm(ex, "Verb refused");
        if (form == null) {
            return;
        }
        Follow f = followed.get(); // ONE read
        OpsSnapshot snap = readSnapshotOrNull(f.opsFile());
        if (!commandable(f, snap)) {
            respond(ex, 200, JSON, OpsConsoleForms.errorJson("Verb refused", "not commandable"));
            return;
        }
        long writerPid = ServerOpsCore.writerAlive(snap) ? snap.pid() : 0L;
        String line;
        OpsConsoleForms.ConfirmSpec spec;
        try {
            line = OpsConsoleForms.verbLine(form, snap);
            spec = OpsConsoleForms.verbConfirm(form, snap, writerPid);
        } catch (IllegalArgumentException refused) { // answered + one bounded row
            System.out.println("cc-console verb_refused verb="
                    + PeerText.quote(PeerText.bound(String.valueOf(OpsConsoleForms.first(form, "verb")), 40))
                    + " reason=" + PeerText.quote(PeerText.bound(refused.getMessage(), 80)));
            respond(ex, 200, JSON, OpsConsoleForms.errorJson("Verb refused", refused.getMessage()));
            return;
        }
        if (spec != null && !form.getOrDefault("confirmed", List.of()).contains(spec.key())) {
            respond(ex, 200, JSON, OpsConsoleForms.needsConfirmJson(List.of(spec)));
            return;
        }
        try {
            ServerOpsCore.appendVerb(Paths.get(snap.commandFile()), line);
        } catch (IOException io) { // answered + one row; nothing was sent
            System.out.println("cc-console verb_write_failed class=" + io.getClass().getSimpleName());
            respond(ex, 200, JSON, OpsConsoleForms.errorJson("Verb failed", "could not write the command file"));
            return;
        }
        respond(ex, 200, JSON, OpsConsoleForms.okJson(line));
    }

    /**
     * Close ops GUI (the page's own visible way to exit the console):
     * {@code POST
     * /api/quit}, keyed like every route, ONE server-minted confirm ({@link #QUIT_KEY}; the page's dialog shows
     * {@link #QUIT_ASK}); then the running card is removed, {@code cc-console quit=page} is printed, the ok answer
     * ({@link #QUIT_DETAIL}) is written and {@link #quitHook} ends the JVM after it ({@link #QUIT_LIVE}); the lock
     * goes with the JVM. A server this console started is its own process and keeps running (the README's
     * black-window law, unchanged). Both modes: a package console and the repository's owner console.
     */
    private void handleQuit(HttpExchange ex) throws IOException {
        Map<String, List<String>> form = readForm(ex, "Close refused");
        if (form == null) {
            return;
        }
        if (!form.getOrDefault("confirmed", List.of()).contains(QUIT_KEY)) {
            respond(ex, 200, JSON, OpsConsoleForms.needsConfirmJson(
                    List.of(new OpsConsoleForms.ConfirmSpec(QUIT_KEY, QUIT_TITLE, QUIT_ASK))));
            return;
        }
        deleteRunning(roots.pkg());
        System.out.println("cc-console quit=page");
        respond(ex, 200, JSON, OpsConsoleForms.okJson(QUIT_DETAIL));
        quitHook.run();
    }

    /**
     * Kill = {@link ServerOpsKill#killPublicServe} (stop file first, the writer, the launcher
     * last), single-flight, behind Swing's confirm ({@code verbConfirm} {@code verb=kill}, key
     * {@code kill}); {@code writerPid} = the snapshot's pid when its writer is ALIVE (the Swing
     * Kill press's gate, {@link ServerOpsCore#writerAlive}, not {@code liveServer}). The ok answer is
     * {@code {"ok":true,"outcome":<killOutcomeToken>,"detail":<text>}} and one
     * {@code cc-console kill outcome=<token>} row is printed; "nothing to stop" stays the refusal.
     *
     * <p>On the WATCH lane ({@link #watchLane}: owner mode following the
     * watch's own status file) with a snapshot naming a pid, Kill is the Swing window's force-kill branch
     * instead: the confirm asks {@link #forceKillAsk}, then {@link OwnerWatch#pidKill} ends that pid and the
     * answer is {@code {"ok":true,"outcome":"force_signalled|force_gone","detail":<its note>}} with ONE row
     * {@code cc-console kill result=force_signalled|force_gone}. WHY: the watch's serve is not the launcher's
     * child, so the public stop file has no reader there - the Swing window ends the pid the watch's status
     * file names. No snapshot there = the path above (its "nothing to stop" refusal).
     */
    private void handleKill(HttpExchange ex) throws IOException {
        Map<String, List<String>> form = readForm(ex, "Kill refused");
        if (form == null) {
            return;
        }
        if (!killInFlight.compareAndSet(false, true)) {
            respond(ex, 200, JSON, OpsConsoleForms.errorJson("Kill", KILL_RUNNING));
            return;
        }
        try {
            Follow f = followed.get(); // ONE read: the confirm and the kill name the same file
            Path opsFile = f.opsFile();
            OpsSnapshot snap = readSnapshotOrNull(opsFile);
            long writerPid = snap != null && ServerOpsCore.writerAlive(snap) ? snap.pid() : 0L;
            OwnerWatch seams = ownerWatch;
            boolean force = snap != null && snap.pid() > 0 && watchLane(f, seams);
            Map<String, List<String>> killForm = new LinkedHashMap<>(form);
            killForm.put("verb", List.of("kill"));
            OpsConsoleForms.ConfirmSpec spec;
            try {
                spec = OpsConsoleForms.verbConfirm(killForm, snap, writerPid);
            } catch (IllegalArgumentException refused) { // a CR / LF / NUL in the form
                respond(ex, 200, JSON, OpsConsoleForms.errorJson("Kill refused", refused.getMessage()));
                return;
            }
            if (force) { // the WATCH lane asks Swing's force-kill question (same key: one confirm round)
                spec = new OpsConsoleForms.ConfirmSpec(spec.key(), spec.title(), forceKillAsk(snap.pid()));
            }
            if (!form.getOrDefault("confirmed", List.of()).contains(spec.key())) {
                respond(ex, 200, JSON, OpsConsoleForms.needsConfirmJson(List.of(spec)));
                return;
            }
            if (force) {
                String note = seams.pidKill().kill(snap.pid());
                String token = PidKill.signalledNote(snap.pid()).equals(note) ? "force_signalled" : "force_gone";
                System.out.println("cc-console kill result=" + token);
                respond(ex, 200, JSON, "{\"ok\":true,\"outcome\":\"" + token + "\",\"detail\":\""
                        + OpsConsoleForms.jsonEscape(note) + "\"}");
                return;
            }
            LaneFollow.LaneLaunch launch = startLaunch.get();
            ServerOpsKill.Outcome outcome = killFn.kill(opsFile, writerPid,
                    launch == null || launch.current() == null ? null : launch.current().get());
            String token = killOutcomeToken(outcome);
            System.out.println("cc-console kill outcome=" + token);
            respond(ex, 200, JSON, outcome.nothingToStop()
                    ? OpsConsoleForms.errorJson("Kill refused", outcome.text())
                    : "{\"ok\":true,\"outcome\":\"" + token + "\",\"detail\":\""
                            + OpsConsoleForms.jsonEscape(outcome.text()) + "\"}");
        } finally {
            killInFlight.set(false);
        }
    }

    /** The WATCH lane's Kill question, the Swing confirm verbatim
     * ({@code pid} = the watch's status file's writer, &gt; 0). */
    static String forceKillAsk(long pid) {
        return "Force-kill server pid " + pid + "? (fallback - prefer End match)";
    }

    /**
     * The Kill outcome as a bounded token for the page
     * and the stdout row ({@link ServerOpsKill.Outcome} has no enum):
     * {@code nothing_to_stop} (step 1's refusal), else {@code receipt} (the supervisor's terminal row
     * came within the bound), else {@code escalated} (lives or the launcher were ended without one),
     * else {@code no_receipt}. A literal, never host text (row {@code web-kill-outcome}).
     */
    static String killOutcomeToken(ServerOpsKill.Outcome outcome) {
        if (outcome.nothingToStop()) {
            return "nothing_to_stop";
        }
        if (outcome.terminalRow() != null) {
            return "receipt";
        }
        if (!outcome.livesKilled().isEmpty() || outcome.launcherKilled()) {
            return "escalated";
        }
        return "no_receipt";
    }

    // ---- GET/POST /api/voice -------------------------------------------------------------------

    /**
     * GET = the 12 {@link OpsVoicePanel#PANEL_KNOBS} values ({@link OpsVoicePanel#knobValue}: the
     * flags as JSON booleans, the rest integers) + {@code bandwidth}; POST = the Voice tab's Apply:
     * {@link OpsConsoleForms#voiceConfig} (a refusal names its
     * {@code fields}), the 12 rows written the panel's way, the ONE atomic {@code voice set} verb
     * appended when the CMD gate holds, and the panel's note text.
     */
    private void handleVoice(HttpExchange ex) throws IOException {
        if ("GET".equals(ex.getRequestMethod())) {
            VoiceConfig cfg = OpsVoicePanel.fromProps(propsLines());
            StringBuilder sb = new StringBuilder("{");
            for (String knob : OpsVoicePanel.PANEL_KNOBS) {
                sb.append('"').append(knob).append("\":").append(OpsVoicePanel.knobValue(cfg, knob)).append(',');
            }
            respond(ex, 200, JSON, sb.append("\"bandwidth\":\"")
                    .append(OpsConsoleForms.jsonEscape(OpsConsoleForms.voiceBandwidthText(cfg))).append("\"}").toString());
            return;
        }
        Map<String, List<String>> form = readForm(ex, "Voice refused");
        if (form == null) {
            return;
        }
        String verb;
        synchronized (startLock) {
            // The read-modify-write holds ServerOpsLaunch.PROPS_LOCK inside
            // startLock, as handleMods does (the WATCH lease daemon's clear rewrites this file under that lock)
            synchronized (ServerOpsLaunch.PROPS_LOCK) {
                String raw = Files.isRegularFile(propsFile) ? Files.readString(propsFile, StandardCharsets.UTF_8) : "";
                List<String> fresh = raw.isEmpty() ? List.of() : raw.lines().toList();
                VoiceConfig current = OpsVoicePanel.fromProps(fresh);
                VoiceConfig desired;
                try {
                    desired = OpsConsoleForms.voiceConfig(form, current);
                } catch (IllegalArgumentException bad) { // answered with the knobs it names
                    StringBuilder named = new StringBuilder();
                    for (String knob : OpsVoicePanel.offendingKnobs(bad.getMessage())) {
                        named.append(named.length() == 0 ? "" : ",").append('"')
                                .append(OpsConsoleForms.jsonEscape(knob)).append('"');
                    }
                    respond(ex, 200, JSON, "{\"error\":\"" + OpsConsoleForms.jsonEscape(bad.getMessage())
                            + "\",\"title\":\"Voice refused\",\"fields\":[" + named + "]}");
                    return;
                }
                verb = OpsVoicePanel.applyVerb(current, desired);
                String sep = raw.contains("\r\n") ? "\r\n" : "\n";
                try {
                    Files.writeString(propsFile, String.join(sep, OpsVoicePanel.withVoiceRows(fresh, desired)) + sep,
                            StandardCharsets.UTF_8);
                } catch (IOException io) { // answered + one row; nothing written
                    System.out.println("cc-console voice_write_failed class=" + io.getClass().getSimpleName());
                    respond(ex, 200, JSON, OpsConsoleForms.errorJson("Voice",
                            "voice: wrote nothing (could not write " + propsFile.getFileName() + ")"));
                    return;
                }
            }
            rebaseDraft(); // written: the unsaved Setup must not look older than this console's own write
        }
        Follow f = followed.get(); // ONE read
        OpsSnapshot snap = readSnapshotOrNull(f.opsFile());
        boolean sent = false;
        if (verb != null && commandable(f, snap)) {
            try {
                ServerOpsCore.appendVerb(Paths.get(snap.commandFile()), verb);
                sent = true;
            } catch (IOException io) { // answered + one row; the rows stand
                System.out.println("cc-console voice_verb_failed class=" + io.getClass().getSimpleName());
                respond(ex, 200, JSON, OpsConsoleForms.errorJson("Voice", "voice: rows written, live retune verb failed"));
                return;
            }
        }
        // The note says SENT, never "applied" (the relay's answer is only in the server log)
        String tail = sent ? " + live retune SENT (server applies or refuses it whole;"
                        + " outcome = the server log's operator_voice line)"
                : verb == null ? " (no knob changed)"
                : " (server not live - applies at next boot)";
        respond(ex, 200, JSON, "{\"ok\":true,\"note\":\"" + OpsConsoleForms.jsonEscape("voice: wrote "
                + OpsVoicePanel.PANEL_KNOBS.length + " rows to " + propsFile.getFileName() + tail) + "\"}");
    }

    // ---- /api/mods -----------------------------------------------------------------------------

    /**
     * The Mods tab. GET = {@link #modsJson}. POST =
     * form {@code op=add|remove|use|rescan} - the Mods actions (Add folder,
     * Remove, the Use combo, Rescan; {@link #modsEdit}) over a FRESH read of the props file under
     * {@link #startLock} + {@link ServerOpsLaunch#PROPS_LOCK} (the {@link #handleVoice} idiom: the line
     * separator kept, ONE write, and only when a row changed). A success answers the GET shape
     * re-scanned over the rows now on disk plus {@code "ok":true}; a refusal answers
     * {@code {error,title:"Mods"}} and writes nothing.
     */
    private void handleMods(HttpExchange ex) throws IOException {
        if ("GET".equals(ex.getRequestMethod())) {
            respond(ex, 200, JSON, modsJson(roots.modsBase(), propsLines(), false));
            return;
        }
        Map<String, List<String>> form = readForm(ex, "Mods");
        if (form == null) {
            return;
        }
        String op = OpsConsoleForms.first(form, "op");
        if (op == null) {
            respond(ex, 200, JSON, OpsConsoleForms.errorJson("Mods", "missing field"));
            return;
        }
        String body;
        synchronized (startLock) {
            // The read-modify-write holds ServerOpsLaunch.PROPS_LOCK, taken
            // inside startLock (the Start's own order) - the WATCH lease daemon rewrites this file under that lock
            // alone, so an unlocked truncate-then-write could hand its read an empty file (its one row written over
            // the host's config) or overwrite its clear with this handler's older copy (row web-props-locked)
            synchronized (ServerOpsLaunch.PROPS_LOCK) {
                String raw = Files.isRegularFile(propsFile) ? Files.readString(propsFile, StandardCharsets.UTF_8) : "";
                List<String> fresh = raw.isEmpty() ? List.of() : raw.lines().toList();
                ModsEdit edit = modsEdit(op, form, roots.modsBase(), fresh, roots.mode() == OpsRoots.Mode.PACKAGE
                        ? roots.pkg().resolve(PackageModSync.MODS_DIR) : null);
                List<String> want = fresh;
                if (edit.refusal() != null) {
                    body = OpsConsoleForms.errorJson("Mods", edit.refusal());
                } else {
                    if (edit.writes()) { // the write order: roots, then enabled, then optional
                        want = InstalledModScan.withModsRootsRow(want, edit.roots());
                        want = OpsMatchRows.withModsEnabledRow(want, edit.enabled());
                        want = OpsMatchRows.withModsOptionalRow(want, edit.optional());
                    }
                    body = null;
                    if (!want.equals(fresh)) {
                        String sep = raw.contains("\r\n") ? "\r\n" : "\n";
                        try {
                            Files.writeString(propsFile, String.join(sep, want) + sep, StandardCharsets.UTF_8);
                            rebaseDraft(); // written: the unsaved Setup must not look older than this write
                        } catch (IOException io) { // answered + one row; nothing written
                            System.out.println("cc-console mods_write_failed class=" + io.getClass().getSimpleName());
                            body = OpsConsoleForms.errorJson("Mods", "could not write " + propsFile.getFileName());
                        }
                    }
                    if (body == null) {
                        body = modsJson(roots.modsBase(), want, true);
                        if (edit.note() != null) { // a package Remove's note (ServerOpsCore.modsRemovedLine)
                            body = body.substring(0, body.length() - 1) + ",\"note\":\""
                                    + OpsConsoleForms.jsonEscape(edit.note()) + "\"}";
                        }
                    }
                }
            }
        }
        respond(ex, 200, JSON, body);
    }

    /**
     * One mods POST's composed rows, or its refusal
     * text. {@code writes} false = nothing to compose (rescan, an unchanged Use); {@code roots}
     * null = the {@code mods.roots} row untouched (an ABSENT row stays absent:
     * {@link InstalledModScan#roots} then reads its defaults; the console never writes them in).
     */
    private record ModsEdit(String refusal, boolean writes, String roots, String enabled, String optional,
            String note) {
        static final ModsEdit NONE = new ModsEdit(null, false, null, null, null);

        /** No {@code note} (only a package Remove carries one). */
        ModsEdit(String refusal, boolean writes, String roots, String enabled, String optional) {
            this(refusal, writes, roots, enabled, optional, null);
        }

        static ModsEdit refused(String why) {
            return new ModsEdit(why, false, null, null, null);
        }
    }

    /**
     * The op over {@code lines} (the fresh read)
     * and the scan of their roots ({@link #consoleRoots}; {@code base} = the package root the
     * tokens resolve against). Writes nothing; its only filesystem reads are the folder scans and
     * {@code classifyFolder}. A missing verb arg = {@code missing field}.
     * {@code keptRoot} = a package's
     * {@code <pkg>/serve-mods} (null in owner mode), handed to {@link #modsRemove}.
     */
    private static ModsEdit modsEdit(String op, Map<String, List<String>> form, Path base, List<String> lines,
            Path keptRoot) {
        List<String> modRoots = consoleRoots(lines);
        List<InstalledModScan.InstalledMod> scanned = InstalledModScan.scanRoots(base, modRoots);
        List<OpsModsPanel.ModRow> rows = OpsModsPanel.modRows(scanned, lines);
        switch (op) {
            case "add":
                return modsAdd(OpsConsoleForms.first(form, "path"), base, modRoots, rows);
            case "remove":
                return modsRemove(OpsConsoleForms.first(form, "id"), OpsConsoleForms.first(form, "path"), base,
                        modRoots, scanned, rows, keptRoot);
            case "use":
                return modsUse(OpsConsoleForms.first(form, "id"), OpsConsoleForms.first(form, "use"), rows);
            case "rescan":
                return ModsEdit.NONE;
            default:
                return ModsEdit.refused("unknown op");
        }
    }

    /**
     * Add folder = the typed-path text law
     * ({@link OpsConsoleForms#modsPathRefusal}), then four refusals
     * (no mod there, a {@code ';'} in the path, already listed, a mod id already listed); the
     * accepted folder joins the roots as ONE token ({@link OpsModsPanel#rootToken}); the choice
     * rows are re-joined unchanged ({@code modsEnabledText} / {@code modsOptionalText}).
     */
    private static ModsEdit modsAdd(String path, Path base, List<String> modRoots, List<OpsModsPanel.ModRow> rows) {
        String typed = OpsConsoleForms.modsPathRefusal(path);
        if (typed != null) {
            return ModsEdit.refused(typed);
        }
        Path folder = Paths.get(path).toAbsolutePath().normalize();
        String kind = OpsModsPanel.classifyFolder(folder);
        if (kind == null) {
            return ModsEdit.refused("No mod in " + folder + "\n" + OpsModsPanel.ADD_HINT + ".");
        }
        if (folder.toString().indexOf(';') >= 0) {
            return ModsEdit.refused("Cannot list " + folder + ": a ';' in the path (the mods.roots separator)."
                    + "\nMove or rename the folder first.");
        }
        for (String token : modRoots) {
            Path r = InstalledModScan.resolveRoot(base, token);
            if (r == null) {
                continue;
            }
            Path ra = r.toAbsolutePath().normalize();
            if (ra.equals(folder) || ("mod".equals(kind) && folder.getParent() != null
                    && folder.getParent().equals(ra) && !Files.isRegularFile(ra.resolve(InstalledModScan.MOD_INFO)))) {
                return ModsEdit.refused(folder + " is already listed (searched as " + token + ").");
            }
        }
        for (InstalledModScan.InstalledMod m : InstalledModScan.scanRoots(base, List.of(folder.toString()))) {
            for (OpsModsPanel.ModRow row : rows) {
                if (row.installed() && row.id().equals(m.id())) {
                    return ModsEdit.refused("Mod id '" + m.id() + "' is already listed from "
                            + row.folders().get(0).folder() + "\nA mod id may live in one searched"
                            + " folder only (the launcher refuses an ambiguous id). Remove that one first.");
                }
            }
        }
        List<String> next = new ArrayList<>(modRoots);
        next.add(OpsModsPanel.rootToken(base, folder));
        return new ModsEdit(null, true, String.join(";", next),
                OpsModsPanel.joinChoices(rows, OpsModsPanel.MOD_REQUIRED),
                OpsModsPanel.joinChoices(rows, OpsModsPanel.MOD_OPTIONAL));
    }

    /**
     * Remove. The victim = the row whose id is {@code id}, else the row whose folders hold
     * {@code path} (normalized {@link Path} equality; a path the text law refuses matches nothing).
     * A bare root (a {@code mods.roots} token that is no listed mod's folder) matches no row and is
     * refused, never removed. Its folders leave the roots
     * ({@link OpsModsPanel#rootsWithout}); its id leaves both choice rows
     * ({@link OpsModsPanel#joinChoices} over the kept rows). Nothing on disk is deleted.
     *
     * <p>In a package {@code keptRoot} = {@code <pkg>/serve-mods}
     * rides {@link OpsModsPanel#rootsWithout(List, Path, InstalledModScan.InstalledMod, List, Path)} - the
     * serve-mods token is never dropped or split, a split row an older Remove wrote is folded back - and the
     * answer carries {@code note} = {@link ServerOpsCore#modsRemovedLine} (the victim's folder name when it sits
     * directly in serve-mods, else none; {@code repaired} when the remove folded a split row,
     * {@link ServerOpsCore#keptRootSplit} before and not after). Roots equal to the current ones = a null row:
     * an ABSENT row stays absent (row {@code web-mods-remove}). Owner mode: no note, the
     * 4-arg rule (null keptRoot).
     */
    private static ModsEdit modsRemove(String id, String path, Path base, List<String> modRoots,
            List<InstalledModScan.InstalledMod> scanned, List<OpsModsPanel.ModRow> rows, Path keptRoot) {
        if (id == null && path == null) {
            return ModsEdit.refused("missing field");
        }
        int at = indexOfId(rows, id);
        if (at < 0 && path != null && OpsConsoleForms.modsPathRefusal(path) == null) {
            Path want = Paths.get(path).toAbsolutePath().normalize();
            for (int i = 0; i < rows.size() && at < 0; i++) {
                for (InstalledModScan.InstalledMod m : rows.get(i).folders()) {
                    if (m.folder().toAbsolutePath().normalize().equals(want)) {
                        at = i;
                        break;
                    }
                }
            }
        }
        if (at < 0) {
            return ModsEdit.refused(path == null ? "no listed mod has that id" : "no listed mod lives in that folder");
        }
        List<String> next = new ArrayList<>(modRoots);
        OpsModsPanel.ModRow victim = rows.get(at);
        String keptFolder = null;
        Path kept = keptRoot == null ? null : keptRoot.toAbsolutePath().normalize();
        for (InstalledModScan.InstalledMod m : victim.folders()) {
            next = OpsModsPanel.rootsWithout(next, base, m, scanned, keptRoot);
            Path folder = m.folder().toAbsolutePath().normalize();
            if (keptFolder == null && kept != null && kept.equals(folder.getParent()) && folder.getFileName() != null) {
                keptFolder = folder.getFileName().toString();
            }
        }
        String note = keptRoot == null ? null : ServerOpsCore.modsRemovedLine(victim.id(), keptFolder,
                ServerOpsCore.keptRootSplit(modRoots, base, keptRoot) && !ServerOpsCore.keptRootSplit(next, base, keptRoot));
        List<OpsModsPanel.ModRow> keptRows = new ArrayList<>(rows);
        keptRows.remove(at);
        return new ModsEdit(null, true, next.equals(modRoots) ? null : String.join(";", next),
                OpsModsPanel.joinChoices(keptRows, OpsModsPanel.MOD_REQUIRED),
                OpsModsPanel.joinChoices(keptRows, OpsModsPanel.MOD_OPTIONAL), note);
    }

    /**
     * The Use combo: the value law ({@link OpsConsoleForms#modsUseValue}), a listed id, then the
     * choice rows re-joined with that row's new choice; an unchanged choice composes nothing.
     */
    private static ModsEdit modsUse(String id, String use, List<OpsModsPanel.ModRow> rows) {
        String choice;
        try {
            choice = OpsConsoleForms.modsUseValue(use);
        } catch (IllegalArgumentException bad) { // the fixed refusal text IS the answer
            return ModsEdit.refused(bad.getMessage());
        }
        if (id == null) {
            return ModsEdit.refused("missing field");
        }
        int at = indexOfId(rows, id);
        if (at < 0) {
            return ModsEdit.refused("no listed mod has that id");
        }
        OpsModsPanel.ModRow row = rows.get(at);
        if (choice.equals(row.choice())) {
            return ModsEdit.NONE;
        }
        List<OpsModsPanel.ModRow> next = new ArrayList<>(rows);
        next.set(at, new OpsModsPanel.ModRow(row.id(), row.name(), row.version(), choice, row.folders()));
        return new ModsEdit(null, true, null, OpsModsPanel.joinChoices(next, OpsModsPanel.MOD_REQUIRED),
                OpsModsPanel.joinChoices(next, OpsModsPanel.MOD_OPTIONAL));
    }

    /** The index of the row whose id equals
     * {@code id}, else -1 ({@code null} matches none). */
    private static int indexOfId(List<OpsModsPanel.ModRow> rows, String id) {
        for (int i = 0; id != null && i < rows.size(); i++) {
            if (rows.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The Mods tab's JSON: {@code roots} (the {@code mods.roots} row's tokens; an ABSENT row = the
     * mode's default, {@link #consoleRoots} - a package host is never shown repo
     * defaults), {@code rows} ({@link OpsModsPanel#modRows} over the scan, each cell the
     * table's text) and {@code status} (the tab's caption); {@code ok} prepends
     * {@code "ok":true} (a POST's success). Carved
     * out of the GET handler so the POST answers the same shape.
     */
    private static String modsJson(Path base, List<String> lines, boolean ok) {
        List<String> modRoots = consoleRoots(lines);
        List<OpsModsPanel.ModRow> rows = OpsModsPanel.modRows(InstalledModScan.scanRoots(base, modRoots), lines);
        StringBuilder sb = new StringBuilder(ok ? "{\"ok\":true,\"roots\":[" : "{\"roots\":[");
        for (int i = 0; i < modRoots.size(); i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(OpsConsoleForms.jsonEscape(modRoots.get(i))).append('"');
        }
        sb.append("],\"rows\":[");
        for (int i = 0; i < rows.size(); i++) {
            OpsModsPanel.ModRow r = rows.get(i);
            sb.append(i == 0 ? "" : ",")
                    .append("{\"mod\":\"").append(OpsConsoleForms.jsonEscape(r.installed()
                            ? OpsModsPanel.displayName(r) : r.id() + OpsModsPanel.NOT_INSTALLED_SUFFIX))
                    .append("\",\"id\":\"").append(OpsConsoleForms.jsonEscape(r.id()))
                    .append("\",\"version\":\"").append(OpsConsoleForms.jsonEscape(r.version()))
                    .append("\",\"use\":\"").append(OpsConsoleForms.jsonEscape(r.choice()))
                    .append("\",\"folder\":\"").append(OpsConsoleForms.jsonEscape(folderCell(r))).append("\"}");
        }
        return sb.append("],\"status\":\"").append(OpsConsoleForms.jsonEscape(modsStatus(rows))).append("\"}")
                .toString();
    }

    /** The searched roots: the present row's tokens ({@link InstalledModScan#roots}); absent = the mode's
     * default - a package's own {@code serve-mods} folder ({@link PackageModSync#PACKAGE_DEFAULT_ROOTS}),
     * so a package host is never shown repo defaults; owner mode the repository's
     * {@link InstalledModScan#DEFAULT_ROOTS}, the Swing Mods panel's own rule. */
    private static List<String> consoleRoots(List<String> lines) {
        return InstalledModScan.roots(lines, OpsRoots.get().mode() == OpsRoots.Mode.PACKAGE
                ? PackageModSync.PACKAGE_DEFAULT_ROOTS : InstalledModScan.DEFAULT_ROOTS);
    }

    /** The Mods table's Folder cell: blank when not installed, an AMBIGUOUS note over two folders. */
    private static String folderCell(OpsModsPanel.ModRow m) {
        if (!m.installed()) {
            return "";
        }
        if (m.folders().size() > 1) {
            return "AMBIGUOUS - in " + m.folders().size() + " folders: " + m.folders().get(0).folder() + " …";
        }
        return m.folders().get(0).folder().toString();
    }

    /** The Mods tab's caption (the listed / required / optional / not-installed counts over the rows). */
    private static String modsStatus(List<OpsModsPanel.ModRow> rows) {
        int req = 0;
        int opt = 0;
        int missing = 0;
        for (OpsModsPanel.ModRow r : rows) {
            if (OpsModsPanel.MOD_REQUIRED.equals(r.choice())) {
                req++;
            } else if (OpsModsPanel.MOD_OPTIONAL.equals(r.choice())) {
                opt++;
            }
            if (!r.installed()) {
                missing++;
            }
        }
        String text = rows.isEmpty() ? "No mods listed - vanilla only. Add a folder to list its mods."
                : rows.size() + " listed: " + req + " required, " + opt + " optional"
                        + (missing > 0 ? ", " + missing + " not installed (set off or remove)" : "") + ".";
        return text + "  Saved at once; applies at the next Start (a running serve keeps its set).";
    }
}
