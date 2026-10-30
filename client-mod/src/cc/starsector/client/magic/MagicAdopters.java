package cc.starsector.client.magic;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The adopter defer: a MagicLib subsystem class that already follows the pilot mark itself (a contract-v1 adopter
 * reads the ship customData key {@code cc_client.pilot} in its own code) keeps its own rule, so the hook answers -1
 * for it and the adopter's own {@code shouldActivateAI} runs exactly as before.
 *
 * <p><b>Minted once, off the frame.</b> {@link #mintFromJars} runs where the kit plugin's application load runs (the
 * loading screen on a game, the boot's plugin loop on the server), never on the sim thread: it reads every class
 * entry of every enabled mod jar ONCE and keeps the names of the classes whose constant pool holds the exact
 * CONSTANT_Utf8 {@code cc_client.pilot} (tag 1, length 15, the 15 bytes). The per-class answer is then a
 * {@link ClassValue} whose {@code computeValue} looks the class and its superclasses up in that name set: no I/O,
 * ever, on any thread. Until a set is installed {@link #current()} is null and the hook answers -1 (the mod's own
 * code runs), so the defer can never be skipped by a race.
 *
 *
 * <p><b>Direction of error:</b> a false positive (some unrelated class names the key) defers to the original code,
 * the safe side. Known misses, counted as non-adopters: an adopter whose key read lives in a helper class, and a
 * subclass compiled from a loose script (no jar entry).
 *
 * <p><b>The jar list</b> = the URLs of the loader that defined this class: the kit jar shares the game's ONE
 * script-jar loader with every enabled mod jar, so its URL list IS the enabled mods' jars. The kit's own jar is
 * skipped (it ships no subsystem). No file name or path is ever logged; the mint returns counts only.
 *
 */
public final class MagicAdopters {

    /** The pilot mark key whose UTF8 constant marks an adopter class. */
    static final String PILOT_KEY = "cc_client.pilot";
    /** CONSTANT_Utf8 tag, u2 length 15, then the key bytes: the exact constant, never a substring of a longer one. */
    private static final byte[] NEEDLE = needle(PILOT_KEY);
    /** The kit's own jar file name (skipped by the scan). */
    static final String KIT_JAR_NAME = "cc-client-mod.jar";
    /** One class entry larger than this is skipped and counted (no mod ships a 16 MB class). */
    static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;

    private static volatile MagicAdopters current;

    private final Set<String> names;
    private final ClassValue<Boolean> adopter = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            return Boolean.valueOf(chainNamed(c, names));
        }
    };

    private MagicAdopters(Set<String> names) {
        this.names = names;
    }

    /** The installed adopter set, or null before the mint ran (the hook then answers -1). */
    static MagicAdopters current() {
        return current;
    }

    /**
     * Install a minted name set (the mint's last step; the client rows install synthetic sets the same way). A new
     * install gets a fresh {@link ClassValue}, so no answer cached against an older set survives it.
     *
     */
    static MagicAdopters install(Set<String> classNames) {
        MagicAdopters a = new MagicAdopters(Collections.unmodifiableSet(new HashSet<>(classNames)));
        current = a;
        return a;
    }

    /** True when {@code c} or a superclass is a minted adopter; cached per class, no I/O. */
    boolean isAdopter(Class<?> c) {
        return !names.isEmpty() && adopter.get(c).booleanValue();
    }

    /** Adopter class names in this set: 0..Integer.MAX_VALUE. */
    int size() {
        return names.size();
    }

    /**
     * PURE: true when {@code c} or any superclass has its binary name in {@code names}. Walking past MagicLib's own
     * base class costs a few steps once per class and never matches (MagicLib's classes do not name the key).
     *
     */
    static boolean chainNamed(Class<?> c, Set<String> names) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (names.contains(k.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * PURE: true when {@code bytes[0..len)} holds the exact CONSTANT_Utf8 {@code cc_client.pilot}.
     *
     */
    static boolean carriesKey(byte[] bytes, int len) {
        int last = len - NEEDLE.length;
        outer:
        for (int i = 0; i <= last; i++) {
            if (bytes[i] != NEEDLE[0]) {
                continue;
            }
            for (int j = 1; j < NEEDLE.length; j++) {
                if (bytes[i + j] != NEEDLE[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Mint the set from the jars of {@code loader} (a URLClassLoader on a real game or server; anything else mints
     * an empty set) and install it. Never throws: a jar that cannot be read is counted and skipped, so a partial
     * set is still installed. Returns bounded counts for the caller's one row:
     * {@code adopters=<n> jars=<n> classes=<n> jar_errors=<n> skipped=<n>}.
     *
     */
    public static String mintFromJars(ClassLoader loader) {
        Set<String> found = new HashSet<>();
        int jars = 0;
        int classes = 0;
        int jarErrors = 0;
        int skipped = 0;
        String firstError = "none";
        URL[] urls = loader instanceof URLClassLoader ? ((URLClassLoader) loader).getURLs() : new URL[0];
        byte[] buf = new byte[64 * 1024];
        for (URL url : urls) {
            String path = url == null ? "" : url.getPath();
            String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
            if (url == null || !"file".equals(url.getProtocol()) || !lower.endsWith(".jar")) {
                skipped++;
                continue;
            }
            if (lower.endsWith("/" + KIT_JAR_NAME)) {
                skipped++;
                continue;
            }
            jars++;
            try (InputStream raw = url.openStream(); ZipInputStream zin = new ZipInputStream(raw)) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    String name = e.getName();
                    if (e.isDirectory() || !name.endsWith(".class") || name.startsWith("META-INF/")) {
                        continue;
                    }
                    int len = 0;
                    boolean tooBig = false;
                    int n;
                    while ((n = zin.read(buf, len, buf.length - len)) > 0) {
                        len += n;
                        if (len == buf.length) {
                            if (buf.length >= MAX_CLASS_BYTES) {
                                tooBig = true;
                                break;
                            }
                            buf = Arrays.copyOf(buf, buf.length * 2);
                        }
                    }
                    if (tooBig) {
                        skipped++;
                        continue;
                    }
                    classes++;
                    if (carriesKey(buf, len)) {
                        found.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
                    }
                }
            } catch (Throwable t) {
                jarErrors++;
                if ("none".equals(firstError)) {
                    firstError = t.getClass().getSimpleName();
                }
            }
        }
        MagicAdopters a = install(found);
        return "adopters=" + a.size() + " jars=" + jars + " classes=" + classes + " jar_errors=" + jarErrors
                + (jarErrors > 0 ? " first_error=" + firstError : "") + " skipped=" + skipped;
    }

    private static byte[] needle(String key) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] n = new byte[3 + k.length];
        n[0] = 1;
        n[1] = (byte) (k.length >>> 8);
        n[2] = (byte) k.length;
        System.arraycopy(k, 0, n, 3, k.length);
        return n;
    }
}
