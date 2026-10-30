package cc.starsector.server.premain;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import cc.starsector.compat.magic.MagicSeamRewriter;
import cc.starsector.compat.pilotship.PilotShipClassifier;
import cc.starsector.compat.pilotship.PilotShipRedirectRewriter;
import cc.starsector.compat.pilotship.ShipAdvanceWindowRewriter;
import cc.starsector.server.transform.TransformLaunchGate;
import cc.starsector.server.transform.TransformRegistry;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The player-ship window's load-time applier, registered by {@link CcTransformPremain} only when the host row
 * {@code pilotship.enabled} resolves true and the eager gate passed. Two halves with opposite fail postures:
 *
 * <ul>
 * <li>The engine ship class ({@link ShipAdvanceWindowRewriter#TARGET}): its load-time bytes are re-verified
 * against the fingerprint the eager gate checked, then wrapped. Any drift, miss or Throwable HALTS the JVM naming
 * the host row: a transformer cannot refuse by throwing (the JVM swallows it and loads the original), and a serve
 * whose redirected scripts run with no window would answer the engine's player ship everywhere, silently.</li>
 * <li>Every other candidate class (a script that could ask for the player's ship): the redirect rewriter decides
 * per class; any miss or Throwable loads the ORIGINAL bytes with one row (fail-open per class).</li>
 * </ul>
 *
 * <p>Candidates: every class outside the JDK, the bundled libraries, this program and the engine itself, except
 * that the engine's own vanilla script package IS a candidate (the engine's own readers keep the real answer by
 * construction); then a constant-pool walk for the {@code getPlayerShip} name before any parse, so a class that
 * never names the call costs one walk. A redefinition is never touched.
 *
 * <p>Rows on stdout (class names and jar file names only, bounded): one per window wrap; of the redirect rows
 * ({@code follows the pilot}) and the unchanged rows, the first {@link #ROW_BUDGET} of each kind, every later one
 * counted; {@link #countsRow()} is the match-end count row. The receipt {@code cc.pilot.ship} is
 * re-set to {@code applied:<classes rewritten so far>} per rewrite, never over a {@code latched:} or
 * {@code refused:} value.
 *
 *
 */
public final class PilotShipApplier implements ClassFileTransformer {

    /** Rows printed per row kind before the rest are only counted. */
    static final int ROW_BUDGET = 20;

    /**
     * The unchanged-class reasons, in the classifier's first-match order, then {@code error} (a Throwable) and
     * {@code other} (a token outside this set); the count row prints every bucket, zeros included.
     */
    static final List<String> REASONS = List.of("deny_list", "host_exclude", "adopter", "magic_subsystem",
            "control_transfer", "keys_or_mouse", "drawing", "battle_speed", "registry_entry", "no_sites", "error",
            "other");

    /** The holder the wrapped engine method and the typed target both link, by name (resolved, never linked here). */
    static final String HOLDER_CLASS = "cc.starsector.server.transform.PilotShipWindow";
    /** The typed target the redirected call sites link. */
    static final String TARGET_CLASS = "cc.starsector.server.probes.PilotShipCall";
    static final String TARGET_METHOD = "playerShip";
    static final String TARGET_DESC =
            "(Lcom/fs/starfarer/api/combat/CombatEngineAPI;)Lcom/fs/starfarer/api/combat/ShipAPI;";

    private static final String[] NEVER_PREFIXES = {"cc/", "org/objectweb/asm/", "java/", "javax/", "jdk/", "sun/",
            "com/sun/", "kotlin/", "org/lwjgl/", "org/json/", "org/apache/", "org/codehaus/", "com/thoughtworks/"};
    private static final String ENGINE_PREFIX = "com/fs/starfarer/";
    private static final String VANILLA_SCRIPTS_PREFIX = "com/fs/starfarer/api/impl/";
    private static final byte[] CALL_NAME = "getPlayerShip".getBytes(StandardCharsets.US_ASCII);

    // Match-wide counters: one applier per JVM, read off-frame by countsRow (atomic: defines run concurrently).
    private static final AtomicInteger CLASSES = new AtomicInteger();
    private static final AtomicInteger WINDOWS = new AtomicInteger();
    private static final AtomicInteger REDIRECTED = new AtomicInteger();
    private static final AtomicInteger SITES = new AtomicInteger();
    private static final AtomicInteger UNCHANGED = new AtomicInteger();
    private static final AtomicIntegerArray BY_REASON = new AtomicIntegerArray(REASONS.size());
    private static final AtomicInteger REDIRECT_ROWS = new AtomicInteger();
    private static final AtomicInteger UNCHANGED_ROWS = new AtomicInteger();

    private final TransformRegistry.Entry window;
    private final Set<String> hostExclude;
    private final ConcurrentHashMap<String, PilotShipClassifier.JarIndex> indexes = new ConcurrentHashMap<>();

    PilotShipApplier(TransformRegistry.Entry window, Set<String> hostExclude) {
        this.window = window;
        this.hostExclude = hostExclude;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classBeingRedefined != null) {
            return null;
        }
        if (ShipAdvanceWindowRewriter.TARGET.equals(className)) {
            return wrapWindow(className, protectionDomain, classfileBuffer);
        }
        if (!isCandidate(className) || !poolHasUtf8(classfileBuffer, CALL_NAME)) {
            return null;
        }
        return redirect(className, protectionDomain, classfileBuffer);
    }

    /**
     * The engine half: re-verify, wrap, or halt naming the host row (never returns on a failure). The wrap
     * rewrites no script class, so it publishes the count unchanged: the receipt turns {@code applied:} the
     * moment the window is live, and k stays the script classes the redirect rewrote. The refusal receipt names
     * the failing step: {@code ship_fingerprint} (the load-time bytes drifted from the verified jar),
     * {@code not_target} (the rewriter did not take the class), the rewriter's own reason token
     * ({@code self_check}, {@code window_repeated}, {@code window_absent}, {@code class_version},
     * {@code error:<SimpleName>}), else {@code error:<SimpleName>} of the Throwable; every token bounded.
     *
     */
    private byte[] wrapWindow(String className, ProtectionDomain domain, byte[] in) {
        String cause = null;
        try {
            List<String> drift = TransformLaunchGate.verify(window, in);
            if (!drift.isEmpty()) {
                cause = "ship_fingerprint";
                throw new IllegalStateException("load-time bytes differ from the launch-gate-verified jar (another"
                        + " transformer rewrote them first, or a different jar shadows it): " + drift.get(0));
            }
            ShipAdvanceWindowRewriter.Result wrapped = ShipAdvanceWindowRewriter.rewrite(className, in);
            byte[] out = wrapped == null ? null : wrapped.patched();
            if (out == null) {
                cause = wrapped == null ? "not_target" : bounded(String.valueOf(wrapped.reason()), 48);
                throw new IllegalStateException("the window rewriter refused the verified bytes: "
                        + (wrapped == null ? "not_target" : wrapped.reason() + " " + wrapped.detail()));
            }
            WINDOWS.incrementAndGet();
            publishApplied(CLASSES.get());
            System.out.println("[cc-premain] pilotship " + classLabel(className) + " ("
                    + MagicSeamRewriter.jarLabel(locationOf(domain)) + "): window wrapped");
            return out;
        } catch (Throwable t) {
            try {
                System.err.println("[cc-transform] REFUSING (load-time player-ship window): " + window.id() + " on "
                        + classLabel(className) + " - " + (t.getMessage() != null ? t.getMessage() : t.toString()));
                publishRefused(cause != null ? cause : "error:" + bounded(MagicSeamRewriter.simpleName(t), 48));
            } catch (Throwable ignored) {
                // the halt below must happen whatever the report did
            }
            Runtime.getRuntime().halt(2);
            throw new AssertionError("unreachable");
        }
    }

    /** The script half: one verdict per candidate class, original bytes on any miss or Throwable. */
    private byte[] redirect(String className, ProtectionDomain domain, byte[] in) {
        String jar = "unknown";
        try {
            String location = locationOf(domain);
            jar = MagicSeamRewriter.jarLabel(location);
            PilotShipClassifier.JarIndex index = indexFor(location);
            PilotShipRedirectRewriter.Result r = PilotShipRedirectRewriter.rewrite(className, in, index, hostExclude,
                    location);
            if (r != null && r.patched() != null) {
                REDIRECTED.incrementAndGet();
                SITES.addAndGet(r.sites());
                publishApplied(CLASSES.incrementAndGet());
                if (REDIRECT_ROWS.getAndIncrement() < ROW_BUDGET) {
                    System.out.println("[cc-premain] pilotship " + classLabel(className) + " (" + jar
                            + "): follows the pilot, " + r.sites() + " sites");
                }
                return r.patched();
            }
            unchanged(className, jar, r == null || r.reason() == null ? "no_sites" : r.reason());
            return null;
        } catch (Throwable t) {
            try {
                unchanged(className, jar, "error:" + MagicSeamRewriter.simpleName(t));
            } catch (Throwable ignored) {
                // a failure to report must not escape a class define either; the original class loads
            }
            return null;
        }
    }

    /**
     * One index per code source, built outside any map lock (building one may load classes, which re-enters this
     * transformer); a race builds twice and keeps the first. Null without a location or when none can be built.
     */
    private PilotShipClassifier.JarIndex indexFor(String location) {
        if (location == null) {
            return null;
        }
        PilotShipClassifier.JarIndex index = indexes.get(location);
        if (index == null) {
            index = PilotShipClassifier.JarIndex.forLocation(location);
            PilotShipClassifier.JarIndex prior = index == null ? null : indexes.putIfAbsent(location, index);
            index = prior != null ? prior : index;
        }
        return index;
    }

    private static void unchanged(String className, String jar, String reason) {
        UNCHANGED.incrementAndGet();
        int bucket = reason.startsWith("error:") ? REASONS.indexOf("error") : REASONS.indexOf(reason);
        BY_REASON.incrementAndGet(bucket < 0 ? REASONS.indexOf("other") : bucket);
        if (UNCHANGED_ROWS.getAndIncrement() < ROW_BUDGET) {
            System.out.println("[cc-premain] pilotship unchanged " + classLabel(className) + " (" + jar + "): "
                    + bounded(reason, 48));
        }
    }

    /**
     * The match-end count row: {@code pilotship classes=<k> windows=<w> redirected=<r> sites=<s> unchanged=<u>}
     * then one {@code <reason>=<n>} per {@link #REASONS} bucket and {@code rows_dropped=<d>} (rows over the
     * budget). Public and argument-free for the serve's reflective off-frame reader (the server unit compiles
     * before this one, so it cannot link this class).
     */
    public static String countsRow() {
        StringBuilder sb = new StringBuilder("pilotship classes=").append(CLASSES.get())
                .append(" windows=").append(WINDOWS.get()).append(" redirected=").append(REDIRECTED.get())
                .append(" sites=").append(SITES.get()).append(" unchanged=").append(UNCHANGED.get());
        for (int i = 0; i < REASONS.size(); i++) {
            sb.append(' ').append(REASONS.get(i)).append('=').append(BY_REASON.get(i));
        }
        int dropped = Math.max(0, REDIRECT_ROWS.get() - ROW_BUDGET) + Math.max(0, UNCHANGED_ROWS.get() - ROW_BUDGET);
        return sb.append(" rows_dropped=").append(dropped).toString();
    }

    /** The candidate pre-filter: outside every never-touched prefix, and the engine only in its vanilla script package. */
    static boolean isCandidate(String internalName) {
        for (String prefix : NEVER_PREFIXES) {
            if (internalName.startsWith(prefix)) {
                return false;
            }
        }
        return !internalName.startsWith(ENGINE_PREFIX) || internalName.startsWith(VANILLA_SCRIPTS_PREFIX);
    }

    /**
     * True when the class's constant pool holds a Utf8 entry EQUAL to {@code name}: a walk of the pool's entry
     * table, no parse. An unknown tag or a truncated pool answers true, so the rewriter (fail-open) decides.
     */
    static boolean poolHasUtf8(byte[] b, byte[] name) {
        try {
            if (b == null || b.length < 10 || (b[0] & 0xff) != 0xCA || (b[1] & 0xff) != 0xFE
                    || (b[2] & 0xff) != 0xBA || (b[3] & 0xff) != 0xBE) {
                return false;
            }
            int count = u2(b, 8);
            int p = 10;
            for (int i = 1; i < count; i++) {
                int tag = b[p] & 0xff;
                switch (tag) {
                    case 1 -> {
                        int len = u2(b, p + 1);
                        if (len == name.length && java.util.Arrays.equals(b, p + 3, p + 3 + len, name, 0, len)) {
                            return true;
                        }
                        p += 3 + len;
                    }
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> p += 5;
                    case 5, 6 -> {
                        p += 9;
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> p += 3;
                    case 15 -> p += 4;
                    default -> {
                        return true;
                    }
                }
            }
            return false;
        } catch (IndexOutOfBoundsException truncated) {
            return true;
        }
    }

    private static int u2(byte[] b, int at) {
        return ((b[at] & 0xff) << 8) | (b[at + 1] & 0xff);
    }

    /**
     * The arm-time probe of what the rewritten classes will link, through the system loader every game and mod
     * loader delegates to: {@code {reason, boundary}} on a miss ({@code holder_missing} = a class absent,
     * {@code holder_abi} = a method absent or of the wrong shape), else null. The typed target's method is read
     * from its class bytes, so no game API type loads before the game does.
     */
    static String[] holderProbeRefusal() {
        ClassLoader system = ClassLoader.getSystemClassLoader();
        Class<?> holder;
        Class<?> target;
        try {
            holder = Class.forName(HOLDER_CLASS, false, system);
            target = Class.forName(TARGET_CLASS, false, system);
        } catch (ClassNotFoundException | LinkageError e) {
            return new String[] {"holder_missing", MagicSeamRewriter.simpleName(e) + ": "
                    + bounded(String.valueOf(e.getMessage()), 160) + " is not on the system class path (the serve jar"
                    + " carries it)"};
        }
        try {
            requireStatic(holder.getMethod("enter", Object.class), void.class);
            requireStatic(holder.getMethod("exit"), void.class);
            requireStatic(holder.getMethod("answerOrNull"), Object.class);
        } catch (NoSuchMethodException | IllegalStateException e) {
            return new String[] {"holder_abi", HOLDER_CLASS + " lacks " + e.getMessage()};
        }
        try (InputStream in = target.getResourceAsStream("/" + TARGET_CLASS.replace('.', '/') + ".class")) {
            boolean[] found = {false};
            if (in != null) {
                new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc, String sig,
                            String[] exceptions) {
                        found[0] |= TARGET_METHOD.equals(name) && TARGET_DESC.equals(desc)
                                && (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                                        == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            if (!found[0]) {
                return new String[] {"holder_abi", TARGET_CLASS + " lacks public static " + TARGET_METHOD + TARGET_DESC};
            }
        } catch (Exception e) {
            return new String[] {"holder_abi", TARGET_CLASS + " bytes unreadable: " + MagicSeamRewriter.simpleName(e)};
        }
        return null;
    }

    private static void requireStatic(Method m, Class<?> returns) {
        if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != returns) {
            throw new IllegalStateException("static " + returns.getSimpleName() + " " + m.getName());
        }
    }

    /**
     * The file-only row {@code pilotship.exclude}: a comma list, each entry trimmed and lower-cased (the
     * classifier matches a jar file name, with or without {@code .jar}, any location path segment, or an entry
     * read as a package prefix of the class's internal name, case-insensitively); empty without a config file.
     */
    static Set<String> parseExclude(Properties file) {
        String raw = file == null ? null : file.getProperty(TransformRegistry.PILOT_SHIP_EXCLUDE_FILE_KEY);
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return Set.copyOf(out);
    }

    /** The eager gate's and the load-time halt's one refusal row text (stderr, beside the launch refusal). */
    static String refusalRow(String reason) {
        return "[cc-premain] pilotship: refused " + reason + "; set " + TransformRegistry.PILOT_SHIP_ENABLED_FILE_KEY
                + "=false to start";
    }

    /** Sets the receipt {@code refused:<reason>} and prints {@link #refusalRow}. */
    static void publishRefused(String reason) {
        System.setProperty(TransformRegistry.PILOT_SHIP_RECEIPT_SYSPROP,
                TransformRegistry.PILOT_SHIP_RECEIPT_REFUSED_PREFIX + reason);
        System.err.println(refusalRow(reason));
    }

    /**
     * {@code applied:<k>}, compare-and-set on the system properties: never over {@code latched:} (the holder
     * switched the feature off for the match) or {@code refused:}, and never back to a lower count when two
     * defines race.
     */
    private static void publishApplied(int k) {
        String key = TransformRegistry.PILOT_SHIP_RECEIPT_SYSPROP;
        String applied = TransformRegistry.PILOT_SHIP_RECEIPT_APPLIED_PREFIX;
        Properties props = System.getProperties();
        for (int attempt = 0; attempt < 16; attempt++) {
            Object cur = props.get(key);
            if (cur instanceof String s) {
                if (s.startsWith(TransformRegistry.PILOT_SHIP_RECEIPT_LATCHED_PREFIX)
                        || s.startsWith(TransformRegistry.PILOT_SHIP_RECEIPT_REFUSED_PREFIX)
                        || (s.startsWith(applied) && appliedCount(s.substring(applied.length())) >= k)) {
                    return;
                }
                if (props.replace(key, s, applied + k)) {
                    return;
                }
            } else if (props.putIfAbsent(key, applied + k) == null) {
                return;
            }
        }
    }

    private static int appliedCount(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The defining code source as a file-system path (decoded), else its URL path; null without one. */
    private static String locationOf(ProtectionDomain domain) {
        CodeSource source = domain == null ? null : domain.getCodeSource();
        URL url = source == null ? null : source.getLocation();
        if (url == null) {
            return null;
        }
        try {
            return Path.of(url.toURI()).toString();
        } catch (Exception notAFile) {
            return url.getPath();
        }
    }

    /** A class name as a row prints it: dotted, printable ASCII only, at most 160 chars. */
    private static String classLabel(String internalName) {
        return bounded(internalName.replace('/', '.'), 160);
    }

    private static String bounded(String raw, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && i < max; i++) {
            char c = raw.charAt(i);
            sb.append(c > 0x20 && c < 0x7f ? c : '?');
        }
        return raw.length() > max ? sb + "+len=" + raw.length() : sb.toString();
    }
}
