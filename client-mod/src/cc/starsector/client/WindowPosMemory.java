package cc.starsector.client;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import org.lwjgl.opengl.Display;

/**
 * Window-position memory: every game window
 * running this mod — the observer, seat windows, the owner's two kit clients — remembers its own
 * window POSITION across launches, other monitors included (absolute desktop coordinates, negative
 * x fine). Engine-native + client-side: LWJGL 2 {@link Display#getX()}/{@link Display#getY()}
 * (Windows impl = {@code GetWindowRect().left/top}, frame coordinates) and
 * {@link Display#setLocation(int, int)} ({@code SetWindowPos} on the same frame coordinates, so the
 * round trip never drifts by a border width). Position ONLY is restored — the size stays the
 * launcher's per-role {@code -DstartRes}; {@code w}/{@code h} ride the file for the record.
 *
 * <p>ONE FILE PER WINDOW ROLE AND GAME INSTALL, directly under {@code user.home} (ROLL-PROOF: never
 * the mod dir, which {@code refresh-play-env} replaces each roll; and NO subdirectory because the
 * mod jar may not reference {@code java.io.File}/{@code java.nio.file.Files} = cannot create one):
 * {@code <user.home>/.cc-starsector-window-<role>-<install tag>.properties} ({@link #fileName}),
 * lines {@code x=<int>} {@code y=<int>} {@code w=<int>} {@code h=<int>} (+ one {@code #} comment).
 * Role = {@code observer} ({@link ObserverMode#ON}), else {@code pilot-<name>} from
 * {@code cc.match.playerName} with the name reduced to {@code [A-Za-z0-9_-]} max 24 chars
 * (player-authored text = data, never a path component), else {@code seat<N>} from
 * {@code cc.predict.shipId}/{@code CC_PREDICT_SHIP_ID} (default 1, the
 * {@code MatchMissionBuilder.resolveOwnShipId} idiom). Install tag = {@link #installTag} over the
 * game's working directory. Two windows with the SAME role on the SAME install overwrite each
 * other's file; two installs never do (before the tag, the owner's two debug-watch windows
 * both ran as {@code pilot-<name>} and the second restored to the first one's spot: the kit's join
 * settings are ONE file per user, so both games launch with the same name).
 *
 * <p>Restore = {@link #startAtApplicationLoad()} on the main thread (Display already exists, the
 * loading screen is up): parse FAIL-CLOSED (ints only, known keys only, any garbage = no restore +
 * one row), hard-clamp {@code |x|,|y| <= 16384}, then a best-effort AWT screen test: kept when
 * SOME screen contains the inset point, else clamped into the PRIMARY screen
 * ({@link #clampToDesktop}; each screen's bounds scaled up by its DPI transform so a scaled
 * desktop can only LOOSEN the test, never reject a valid physical position); AWT is fully fenced
 * by {@code catch (Throwable)} — headless or broken AWT falls back to the hard clamp alone and
 * never takes the launch down. Save = the daemon {@value #THREAD_NAME} polling every
 * {@value #POLL_MILLIS} ms: a position that differs from the last WRITTEN one and has held for
 * {@value #STABLE_POLLS} consecutive polls is rewritten ({@code CREATE + TRUNCATE_EXISTING +
 * WRITE} through the B-legal {@link FileChannel} seam, the {@link ObserverFrameDump} idiom); an
 * ICONIFIED window ({@code x=y=-32000}) reads as "no position" and is never written
 * ({@link #readDisplay}).
 *
 * <p>Classloader-B law (gate {@code check-b-deny-refs.ps1}): every constant-pool ref here is
 * B-LEGAL — {@code Paths}/{@code Path}/{@code StandardOpenOption}/{@code FileChannel}/
 * {@code ByteBuffer}/{@code StandardCharsets}/{@code java.awt.*}/{@code Display}; no
 * {@code java.io.File*}, no {@code java.nio.file.Files}, no {@code java.util.prefs}, no reflection.
 * Class-load discipline: the whole seam (read, parse, AWT clamp, WRITE) is exercised once at
 * application load — the warm-up write of the current position — so the poll thread never
 * first-touches a class under the combat script SecurityManager. Fail-soft: a dead lane logs once
 * and never throws into the plugin. Skipped (one row) in the server JVM
 * ({@code cc.jvm.role=server}: headless content-fixture load), without a created Display, or
 * fullscreen (no window position exists).
 */
final class WindowPosMemory {
    static final String THREAD_NAME = "cc-window-pos";
    static final String FILE_PREFIX = ".cc-starsector-window-";
    static final String FILE_SUFFIX = ".properties";
    /** {@link #installTag} when the game's working directory is unknown. */
    static final String NO_INSTALL_TAG = "noinstall";
    /** Hard sanity clamp on a restored coordinate, applied before any AWT lookup. */
    static final int COORD_LIMIT = 16384;
    /** Pixels of the window that must sit on SOME screen (inset test) / on the primary after the clamp. */
    static final int MIN_VISIBLE_PX = 64;
    static final long POLL_MILLIS = 2000L;
    static final int STABLE_POLLS = 2;
    static final int MAX_NAME_CHARS = 24;
    /** A position file larger than this is not ours — parse refuses it (fail-closed). */
    static final int MAX_FILE_BYTES = 4096;
    private static final int MAX_POLL_ERRORS = 5;

    private static volatile boolean started;
    private static volatile String role;
    private static volatile Path file;

    /** Last WRITTEN values — main thread at load (before the poller starts), then poller only. */
    private static boolean haveSaved;
    private static int savedX;
    private static int savedY;
    private static int savedW;
    private static int savedH;
    private static int writeErrors;

    private WindowPosMemory() {
    }

    /**
     * Restore this window's saved position, warm the save seam, start the poller. Called once from
     * {@code CcClientModPlugin.onApplicationLoad} (main thread). Never throws.
     */
    static void startAtApplicationLoad() {
        if (started) {
            return;
        }
        started = true;
        try {
            if ("server".equals(System.getProperty("cc.jvm.role"))) {
                PilotWatchLog.info("window-pos: skipped reason=server-jvm");
                return;
            }
            if (!Display.isCreated()) {
                PilotWatchLog.info("window-pos: skipped reason=display-not-created");
                return;
            }
            if (Display.isFullscreen()) {
                PilotWatchLog.info("window-pos: skipped reason=fullscreen");
                return;
            }
            String home = System.getProperty("user.home");
            if (home == null || home.isBlank()) {
                PilotWatchLog.info("window-pos: skipped reason=no-user-home");
                return;
            }
            role = resolveRole();
            file = Paths.get(home, fileName(role, installTag(System.getProperty("user.dir"))));
            String restored = restore();
            // Warm-up = a real write of the current position: the FileChannel WRITE seam, the
            // ByteBuffer/charset path and the poller's nested class all first-touch HERE, outside
            // the combat SecurityManager (ObserverFrameDump's warmup.jpg pattern).
            int[] cur = readDisplay();
            String warm = cur == null ? "warmup=skipped"
                    : (writeFile(cur) ? "warmup=written" : "warmup=FAILED");
            PilotWatchLog.info("window-pos: role=" + role + " file=" + file
                    + " restored=" + restored + " " + warm);
            Thread t = new Thread(new Poller(), THREAD_NAME);
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            PilotWatchLog.info("window-pos: DEAD at load " + t);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Role
    // ----------------------------------------------------------------------------------------

    /** Package-visible: {@link MarkFrameRing} names its rows/mark file by
     *  the SAME role token — one resolver, one sanitizer, no duplicate. */
    static String resolveRole() {
        if (ObserverMode.ON) {
            return "observer";
        }
        String pilot = sanitizeName(System.getProperty("cc.match.playerName"));
        if (pilot != null) {
            return "pilot-" + pilot;
        }
        return "seat" + resolveSeatId();
    }

    /**
     * Reduce a player-authored name to {@code [A-Za-z0-9_-]}, max {@value #MAX_NAME_CHARS} chars
     * (every other char DROPPED, never escaped); {@code null} when nothing survives — the caller
     * falls back to the seat role. The result can never carry a path separator, a dot or a space.
     */
    static String sanitizeName(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(MAX_NAME_CHARS);
        for (int i = 0; i < raw.length() && sb.length() < MAX_NAME_CHARS; i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (ok) {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** The position file's name under {@code user.home}: {@value #FILE_PREFIX}{@code <role>-<tag>}{@value #FILE_SUFFIX}. */
    static String fileName(String role, String tag) {
        return FILE_PREFIX + role + "-" + tag + FILE_SUFFIX;
    }

    /**
     * The game INSTALL's tag for the file name ("the second one goes to *its*
     * position rather than its former position"): 8 lower-case hex digits of {@link String#hashCode()}
     * over the install root's absolute, normalized, {@code Locale.ROOT} lower-cased path. The install
     * root = the working directory ({@code user.dir}) with a {@code starsector-core} leaf dropped —
     * every launcher starts the game in {@code <install>\starsector-core}, and a working directory
     * that IS the install maps to the same tag. WHY not the join package's SHA-256 tag
     * ({@code JoinHost.installTag}): that class runs only outside the game (classloader-B exemption),
     * and this jar adds no constant-pool ref for a name. Fail-soft: a {@code null} / blank / unparsable
     * directory = {@value #NO_INSTALL_TAG} (one shared file, the shape before the tag). Pure; package-visible
     * for {@code WindowPosRows}.
     */
    static String installTag(String userDir) {
        if (userDir == null || userDir.isBlank()) {
            return NO_INSTALL_TAG;
        }
        try {
            Path p = Paths.get(userDir).toAbsolutePath().normalize();
            Path leaf = p.getFileName();
            if (leaf != null && "starsector-core".equalsIgnoreCase(leaf.toString()) && p.getParent() != null) {
                p = p.getParent();
            }
            String hex = Integer.toHexString(p.toString().toLowerCase(Locale.ROOT).hashCode());
            StringBuilder sb = new StringBuilder(8);
            for (int i = hex.length(); i < 8; i++) {
                sb.append('0');
            }
            return sb.append(hex).toString();
        } catch (RuntimeException bad) {
            return NO_INSTALL_TAG;
        }
    }

    private static long resolveSeatId() {
        try {
            String raw = System.getProperty("cc.predict.shipId");
            if (raw == null || raw.isBlank()) {
                raw = System.getenv("CC_PREDICT_SHIP_ID");
            }
            return (raw == null || raw.isBlank()) ? 1L : Long.parseLong(raw.trim());
        } catch (Throwable t) {
            return 1L;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Restore (main thread, at load)
    // ----------------------------------------------------------------------------------------

    /**
     * A coordinate of exactly {@code -1} is mapped to {@code 0} per axis before
     * {@link Display#setLocation(int, int)}, whose {@code -1} means "centre this axis" (LWJGL
     * sentinel), never a position.
     *
     * @return {@code x,y} (with a {@code clamped-from} note when moved) or {@code none reason=<why>}.
     */
    private static String restore() {
        byte[] bytes;
        try {
            FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
            try {
                long size = ch.size();
                if (size <= 0L || size > MAX_FILE_BYTES) {
                    return "none reason=size:" + size;
                }
                ByteBuffer buf = ByteBuffer.allocate((int) size);
                while (buf.hasRemaining() && ch.read(buf) >= 0) {
                    // read until full or EOF
                }
                buf.flip();
                bytes = new byte[buf.remaining()];
                buf.get(bytes);
            } finally {
                ch.close();
            }
        } catch (Throwable t) {
            // First launch of a role = NoSuchFileException: the ordinary "no memory yet" case.
            return "none reason=unreadable:" + t.getClass().getSimpleName();
        }
        int[] v = parse(new String(bytes, StandardCharsets.UTF_8));
        if (v == null) {
            return "none reason=parse";
        }
        int x = v[0];
        int y = v[1];
        if (Math.abs(x) > COORD_LIMIT || Math.abs(y) > COORD_LIMIT) {
            return "none reason=range";
        }
        int[] c = clampToDesktop(x, y);
        // LWJGL sentinel: setLocation(-1, ..) / (.., -1) = "centre this axis", so a saved -1 (a
        // real frame coordinate one px past the edge) is mapped to 0 per axis.
        int lx = c[0] == -1 ? 0 : c[0];
        int ly = c[1] == -1 ? 0 : c[1];
        Display.setLocation(lx, ly);
        return lx + "," + ly + ((lx != x || ly != y) ? "(clamped-from " + x + "," + y + ")" : "");
    }

    /**
     * FAIL-CLOSED parse of the role file: {@code key=int} lines, keys {@code x y w h} each at most
     * once, {@code #} comments and blank lines ignored, ANYTHING else (unknown key, non-int, a
     * duplicate, a missing x or y) = {@code null}. Package-private for a game-free unit check.
     */
    static int[] parse(String text) {
        int[] v = new int[4];
        boolean[] seen = new boolean[4];
        for (String line : text.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.charAt(0) == '#') {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq <= 0) {
                return null;
            }
            String key = s.substring(0, eq).trim();
            int slot;
            if ("x".equals(key)) {
                slot = 0;
            } else if ("y".equals(key)) {
                slot = 1;
            } else if ("w".equals(key)) {
                slot = 2;
            } else if ("h".equals(key)) {
                slot = 3;
            } else {
                return null;
            }
            if (seen[slot]) {
                return null;
            }
            try {
                v[slot] = Integer.parseInt(s.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            seen[slot] = true;
        }
        return (seen[0] && seen[1]) ? v : null;
    }

    /**
     * Keep {@code x,y} on a monitor: the inset point {@code (x + MIN_VISIBLE_PX, y + MIN_VISIBLE_PX)}
     * is tested against EVERY screen's rect; when SOME screen contains it the position is accepted
     * UNCHANGED (a far or negative-x monitor is a valid home). Otherwise — the point sits in the
     * dead area of an L-shaped layout, or on a monitor that is gone — {@code x,y} are clamped into
     * the PRIMARY screen's rect ({@link GraphicsEnvironment#getDefaultScreenDevice()}) leaving at
     * least {@value #MIN_VISIBLE_PX} px visible. (The former clamp into the UNION bounding box of
     * all screens accepted dead-area points and restored fully off-screen.)
     * Each screen's user-space bounds are scaled up by its default-transform DPI factor (LWJGL
     * reports PHYSICAL pixels; a 150 % monitor would otherwise shrink its rect and reject a valid
     * position) — the test can only loosen, never tighten, under scaling. AWT headless/broken =
     * return the input unchanged (the hard {@value #COORD_LIMIT} clamp already ran). Never throws.
     */
    private static int[] clampToDesktop(int x, int y) {
        try {
            GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
            int px = x + MIN_VISIBLE_PX;
            int py = y + MIN_VISIBLE_PX;
            for (GraphicsDevice d : env.getScreenDevices()) {
                if (scaledBounds(d).contains(px, py)) {
                    return new int[] {x, y};
                }
            }
            Rectangle p = scaledBounds(env.getDefaultScreenDevice());
            if (p.isEmpty()) {
                return new int[] {x, y};
            }
            int cx = Math.max(p.x, Math.min(x, p.x + p.width - MIN_VISIBLE_PX));
            int cy = Math.max(p.y, Math.min(y, p.y + p.height - MIN_VISIBLE_PX));
            return new int[] {cx, cy};
        } catch (Throwable t) {
            return new int[] {x, y};
        }
    }

    /** A screen's user-space bounds scaled UP to physical pixels by its default DPI transform. */
    private static Rectangle scaledBounds(GraphicsDevice d) {
        Rectangle b = d.getDefaultConfiguration().getBounds();
        double sx = Math.max(1.0, d.getDefaultConfiguration().getDefaultTransform().getScaleX());
        double sy = Math.max(1.0, d.getDefaultConfiguration().getDefaultTransform().getScaleY());
        return new Rectangle((int) Math.floor(b.x * sx), (int) Math.floor(b.y * sy),
                (int) Math.ceil(b.width * sx), (int) Math.ceil(b.height * sy));
    }

    // ----------------------------------------------------------------------------------------
    // Save (warm-up at load on the main thread, then the poller only)
    // ----------------------------------------------------------------------------------------

    /**
     * {@code {x, y, w, h}} of the live window, or {@code null} when there is no USABLE window
     * position: no Display, fullscreen, ICONIFIED ({@code !Display.isVisible()} — the Windows impl
     * is {@code !isMinimized}; a minimized window reports {@code x=y=-32000}, which the poller used
     * to persist and so wiped the memory), or a
     * coordinate beyond {@value #COORD_LIMIT} (the same sentinel by any other path). The warm-up
     * write at load and the poller both read through this filter; a {@code null} never reaches
     * the file.
     */
    private static int[] readDisplay() {
        if (!Display.isCreated() || Display.isFullscreen() || !Display.isVisible()) {
            return null;
        }
        int x = Display.getX();
        int y = Display.getY();
        if (Math.abs(x) > COORD_LIMIT || Math.abs(y) > COORD_LIMIT) {
            return null;
        }
        return new int[] {x, y, Display.getWidth(), Display.getHeight()};
    }

    private static boolean sameAsSaved(int[] v) {
        return haveSaved && v[0] == savedX && v[1] == savedY && v[2] == savedW && v[3] == savedH;
    }

    /** CREATE + TRUNCATE_EXISTING + WRITE through the B-legal channel seam; first failure logs once. */
    private static boolean writeFile(int[] v) {
        String text = "# cc-starsector window position role=" + role + "\n"
                + "x=" + v[0] + "\ny=" + v[1] + "\nw=" + v[2] + "\nh=" + v[3] + "\n";
        try {
            FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                ByteBuffer buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
            } finally {
                ch.close();
            }
            savedX = v[0];
            savedY = v[1];
            savedW = v[2];
            savedH = v[3];
            haveSaved = true;
            return true;
        } catch (Throwable t) {
            if (writeErrors++ == 0) {
                PilotWatchLog.info("window-pos: write failed " + t);
            }
            return false;
        }
    }

    /**
     * The {@value #THREAD_NAME} daemon: every {@value #POLL_MILLIS} ms read the window rect; a
     * value that differs from the last written one and has repeated for {@value #STABLE_POLLS}
     * consecutive polls is written (one {@code saved} row). A {@code null} read (no window,
     * fullscreen, ICONIFIED = the {@code -32000} sentinel) or a FAILED read resets the candidate
     * and the stable count and is never written. Reads are fenced — at
     * JVM exit the display is destroyed mid-poll — and {@value #MAX_POLL_ERRORS} faults retire
     * the lane with one row.
     */
    private static final class Poller implements Runnable {
        @Override
        public void run() {
            int[] cand = null;
            int stable = 0;
            int errors = 0;
            while (true) {
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException ie) {
                    return;
                }
                try {
                    int[] cur = readDisplay(); // null = no window / fullscreen / ICONIFIED
                    if (cur == null || sameAsSaved(cur)) {
                        cand = null;
                        stable = 0;
                        continue;
                    }
                    if (cand != null && cur[0] == cand[0] && cur[1] == cand[1]
                            && cur[2] == cand[2] && cur[3] == cand[3]) {
                        stable++;
                    } else {
                        cand = cur;
                        stable = 1;
                    }
                    if (stable < STABLE_POLLS) {
                        continue;
                    }
                    stable = 0;
                    if (writeFile(cur)) {
                        PilotWatchLog.info("window-pos: saved " + cur[0] + "," + cur[1] + " "
                                + cur[2] + "x" + cur[3]);
                    } else if (++errors >= MAX_POLL_ERRORS) {
                        PilotWatchLog.info("window-pos: DEAD after " + errors + " write faults");
                        return;
                    }
                } catch (Throwable t) {
                    // A failed read is not a position: drop the candidate, never write it.
                    cand = null;
                    stable = 0;
                    if (++errors >= MAX_POLL_ERRORS) {
                        PilotWatchLog.info("window-pos: DEAD " + t);
                        return;
                    }
                }
            }
        }
    }
}
