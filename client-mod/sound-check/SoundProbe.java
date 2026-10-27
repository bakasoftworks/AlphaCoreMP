// cc sound probe v2: the Java half of the kit's OpenAL self-check. join-cc.bat runs it
// BEFORE every game launch on the SAME java the game will use (jre-cc, or the install's jre\ on fallback),
// from starsector-core with the game's own relative -Djava.library.path=native\windows, and keeps its stdout
// +stderr as the sound-check log the debrief zip ships to the host. NOT part of the mod jar: a loose
// default-package .class in mods\CC_Client (no classloader-B deny scope, plain java.io is fine here).
// Compiled --release 8 (client-mod/build.ps1) so it runs on the install's Zulu 17 and the kit's Temurin 25.
//
// Steps, each only if the previous FAILED — the first success names the fix:
//   1 game shape      AL.create() exactly as sound init does (relative candidates first)
//   2 absolute        System.setProperty("org.lwjgl.librarypath", <abs native dir>) + AL.create()
//   3 javaload        System.load(<abs>\OpenAL64.dll) = the JVM's own LoadLibraryW (Windows' words, no LWJGL)
//   3b after-javaload AL.create() again (the module is now mapped: an A-vs-W path-string class shows here)
//   4 tempcopy        System.load(<temp copy of the dll>) — directory/ownership policy vs the file itself
// Exit codes (join-cc.bat branches on them): 0 game shape OK | 10 absolute OK (the always-on
// -Dorg.lwjgl.librarypath fix suffices) | 15 javaload OK but LWJGL absolute FAIL | 20 every load FAIL
// (process-shape class: the bat switches to the Explorer-parent launch) | 25 create fails for a NON-loader
// reason (device/context) | 30 LWJGL itself (lwjgl64.dll) failed. NEVER prints environment variables.
import java.io.File;
import java.io.PrintStream;
import java.nio.FloatBuffer;
import java.nio.charset.Charset;
import org.lwjgl.BufferUtils;
import org.lwjgl.Sys;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCdevice;

public class SoundProbe extends PrintStream {
    private static final int OK_GAME = 0, OK_ABS = 10, OK_JAVALOAD = 15, ALL_FAIL = 20, NON_LOADER = 25, LWJGL_FAIL = 30;

    public static void main(String[] a) {
        String absDir = a.length > 0 ? a[0] : new File("native" + File.separator + "windows").getAbsolutePath();
        String tmpCopy = a.length > 1 ? a[1] : null;
        // LWJGLUtil.DEBUG is latched at class init: set it BEFORE the first LWJGL touch so every
        // candidate path + its GetLastError text ("Could not load OpenAL library (126)") lands in the log.
        System.setProperty("org.lwjgl.util.Debug", "true");
        System.setOut(new SoundProbe(System.out));
        System.setErr(new SoundProbe(System.err));
        row("probe start java=" + p("java.version") + " vendor=" + p("java.vendor") + " home=" + p("java.home")
                + " os=" + p("os.name") + " " + p("os.version") + " arch=" + p("os.arch")
                + " cwd=" + p("user.dir") + " jlp=" + p("java.library.path") + " lwjglpath=" + p("org.lwjgl.librarypath")
                + " charset=" + Charset.defaultCharset().name() + " file.encoding=" + p("file.encoding")
                + " sun.jnu.encoding=" + p("sun.jnu.encoding") + " absdir=" + absDir + " tempcopy=" + tmpCopy);
        String[] files = {"native\\windows\\OpenAL64.dll", "native\\windows\\OpenAL32.dll", "native\\windows\\lwjgl64.dll",
                "OpenAL64.dll", "lwjgl.jar", absDir + File.separator + "OpenAL64.dll"};
        for (String n : files) {
            File f = new File(n);
            row("probe file " + n + " exists=" + f.exists() + " bytes=" + f.length() + " canRead=" + f.canRead()
                    + " abs=" + f.getAbsolutePath());
        }
        try {
            Sys.initialize();
            row("probe lwjgl OK version=" + Sys.getVersion());
        } catch (Throwable t) {
            row("probe lwjgl FAIL " + describe(t));
            result("lwjgl-init=FAIL", LWJGL_FAIL);
            return;
        }
        String s1 = tryCreate("game-shape");
        if (s1.startsWith("OK")) { result("game-shape=OK", OK_GAME); return; }
        if (s1.indexOf("locate") < 0 && s1.indexOf("load OpenAL") < 0) {
            // AL.create threw something other than the loader's "Could not locate OpenAL library":
            // device/context class — no path or launch-shape lever applies; say so and stop.
            result("game-shape=FAIL(non-loader) absolute=skipped javaload=skipped tempcopy=skipped", NON_LOADER);
            return;
        }
        System.setProperty("org.lwjgl.librarypath", absDir);
        String s2 = tryCreate("absolute");
        if (s2.startsWith("OK")) { result("game-shape=FAIL absolute=OK", OK_ABS); return; }
        String dll = absDir + File.separator + "OpenAL64.dll";
        String s3 = tryLoad("javaload", dll);
        String s3b = tryCreate("after-javaload");
        String s4 = (tmpCopy != null && new File(tmpCopy).isFile()) ? tryLoad("tempcopy", tmpCopy) : "SKIPPED (no temp copy)";
        String summary = "game-shape=FAIL absolute=FAIL javaload=" + head(s3) + " after-javaload=" + head(s3b) + " tempcopy=" + head(s4);
        result(summary, s3.startsWith("OK") ? OK_JAVALOAD : ALL_FAIL);
    }

    /** One AL.create() + the exact per-frame call the game dies on (alListener), then AL.destroy(). */
    private static String tryCreate(String label) {
        long t0 = System.nanoTime();
        String r;
        try {
            AL.create();
            ALCdevice dev = AL.getDevice();
            String device = dev == null ? "null" : ALC10.alcGetString(dev, ALC10.ALC_DEVICE_SPECIFIER);
            String ver = AL10.alGetString(AL10.AL_VERSION);
            String ren = AL10.alGetString(AL10.AL_RENDERER);
            FloatBuffer fb = BufferUtils.createFloatBuffer(6);
            fb.put(new float[] {0f, 0f, -1f, 0f, 1f, 0f}).flip();
            AL10.alListener(AL10.AL_ORIENTATION, fb);
            int err = AL10.alGetError();
            // the device NAME is the player's own label for their hardware (it can carry a person's name, and no
            // sanitizer can know that): the log says only whether one was returned; version + renderer diagnose.
            r = "OK device=" + (dev == null || device == null ? "null" : "present") + " version='" + ver + "' renderer='" + ren
                    + "' alListener alError=" + err;
        } catch (Throwable t) {
            r = "FAIL " + describe(t);
        }
        try { AL.destroy(); } catch (Throwable ignored) { }
        row("probe " + label + " " + r + " (" + ((System.nanoTime() - t0) / 1000000L) + " ms)");
        return r;
    }

    /** System.load = the JVM's own LoadLibraryW on the full path: the OS loader's verdict in Windows' words. */
    private static String tryLoad(String label, String path) {
        String r;
        try {
            System.load(path);
            r = "OK";
        } catch (Throwable t) {
            r = "FAIL " + describe(t);
        }
        row("probe " + label + " path=" + path + " " + r);
        return r;
    }

    private static void result(String summary, int rc) {
        row("probe RESULT " + summary + " rc=" + rc);
        System.out.flush();
        System.err.flush();
        System.exit(rc);
    }

    private static String head(String s) { return s.startsWith("OK") ? "OK" : (s.startsWith("SKIPPED") ? "SKIPPED" : "FAIL"); }
    private static String p(String k) { String v = System.getProperty(k); return v == null ? "-" : v; }
    private static String describe(Throwable t) { return t.getClass().getName() + ": " + t.getMessage(); }

    /**
     * The player's profile folder, as the JVM and as the environment name it (the two can differ in case or form).
     * Every row passes {@link #row}, so replacing it there keeps the Windows account name out of the whole log -
     * the same law, and the same 4-character floor, as sound-check.ps1's own row seam.
     */
    private static final String[] HOMES = {System.getProperty("user.home"), System.getenv("USERPROFILE")};

    private static String noHome(String s) {
        for (String h : HOMES) {
            if (h == null || h.length() < 4) continue;
            StringBuilder b = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); ) {
                if (s.regionMatches(true, i, h, 0, h.length())) { b.append("%USERPROFILE%"); i += h.length(); }
                else { b.append(s.charAt(i)); i++; }
            }
            s = b.toString();
        }
        return s;
    }

    /**
     * LWJGL's debug lines (each candidate path + the loader's error text) are printed by the library straight to
     * stderr, past {@link #row}; the bat folds both streams into the one log. main() swaps both streams for an
     * instance of THIS class, which applies the same replacement. The probe class is its own stream type on
     * purpose: it ships as ONE loose .class file beside the bat, and a nested or anonymous class would be a
     * second file the packer, the bat and the kit's file checks would each have to learn.
     */
    private SoundProbe(PrintStream out) { super(out, true); }
    @Override public void print(String s) { super.print(s == null ? null : noHome(s)); }
    @Override public void print(Object o) { super.print(noHome(String.valueOf(o))); }
    @Override public void println(String s) { super.println(s == null ? null : noHome(s)); }
    @Override public void println(Object o) { super.println(noHome(String.valueOf(o))); }

    /** Single-line printable ASCII rows (the log ships in the debrief zip; a row is one line). */
    private static void row(String s) {
        s = noHome(s);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= 32 && c < 127 ? c : (c == '\n' || c == '\r' || c == '\t' ? ' ' : '?'));
        }
        System.out.println(b);
        System.out.flush();
    }
}
