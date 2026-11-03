package cc.starsector.compat.pilotship;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import cc.starsector.compat.magic.MagicSeamRewriter;
import cc.starsector.compat.pilotship.PilotShipClassifier.JarIndex;
import cc.starsector.compat.pilotship.PilotShipClassifier.Twin;
import cc.starsector.compat.pilotship.PilotShipClassifier.Verdict;
import cc.starsector.compat.pilotship.PilotShipRedirectRewriter.TwinTable;

/**
 * The client agent's second load-time applier: on a joiner's game start it runs the serve's OWN classifier on each
 * mod class (the shared verdict: the same bytes and the same jar index give the same answer on both sides), then
 * the api-entry wrap ({@link ReplicaEntryWrapRewriter}) over the class as shipped, then the shared redirect
 * retargeted to the client's twin class ({@link #KIT}), so another player's copy of a piloted ship can run that
 * ship's own scripts as the pilot's inside a window the client's holder controls. Client-only reasons ONLY shrink
 * the rewritten set:
 * {@link #REASON_VIEWER_UI} (a combat-UI member the client has no drop twin for, or a screen writer reached through
 * an unrewritten same-jar helper), {@link #REASON_NO_TWIN} (the defining loader cannot see a twin the class
 * needs), the wrap's refusals, and the redirect's own self-check.
 *
 * <p>Loader probe: a class defined by the bootstrap, platform or system loader, or named under the engine, LWJGL
 * or JDK namespaces, is NEVER touched (returning bytes for an engine class trips the newer runtime's forced
 * format check, a fatal start). FAIL-OPEN: any refusal returns {@code null} (the original bytes load) with one
 * bounded row; a Throwable does the same and latches the receipt {@code latched:throw} with one class-name row,
 * so the client's holder keeps today's behaviour for the match. Rows (outcome and throw rows alike) are capped at
 * {@link #ROW_CAP}, then one count row; {@link #countsRow()} is the off-frame meter. The receipt {@link #RECEIPT_SYSPROP} reads
 * {@code applied:<k> refused:<r>} once a class is rewritten (k = classes rewritten, r = classes the shared verdict
 * rewrote that the client refused), never over a {@code latched:} or {@code disarmed:} value.
 *
 * <p>Loaded ONLY through the agent's private loader (never the system path), so the ASM it links is the agent
 * jar's own copy. {@link #census} runs the same decision DRY over jars for the static spike.
 *
 *
 */
public final class ReplicaPilotAgentTransformer implements ClassFileTransformer {

    /** The receipt sysprop; the same spelling as the agent premain's constant and the client mod's reader. */
    public static final String RECEIPT_SYSPROP = "cc.pilot.replica";
    public static final String ROW_PREFIX = "[cc-compat-agent] pilotreplica ";
    /** Per-class rows printed before the rest are only counted. */
    static final int ROW_CAP = 512;
    /** The client twin class every swapped call and the wrap invoke. */
    public static final String TWIN_OWNER = ReplicaEntryWrapRewriter.HOLDER;
    public static final String REASON_VIEWER_UI = "viewer_ui";
    public static final String REASON_NO_TWIN = "no_twin";
    /** A same-jar callee scan wider than this many distinct callees reads as {@link #REASON_VIEWER_UI}. */
    static final int MAX_HOP = 256;

    private static final String C = "com/fs/starfarer/api/combat/";
    private static final String ENGINE = C + "CombatEngineAPI";
    static final String COMBAT_UI = C + "CombatUIAPI";
    private static final String VIEWPORT = C + "ViewportAPI";
    private static final String SOUND = "com/fs/starfarer/api/SoundPlayerAPI";
    private static final String ENGINE_IMPL = "com/fs/starfarer/combat/CombatEngine";
    private static final String[] NEVER = {"com/fs/", "org/lwjgl/", "java/", "javax/", "sun/", "jdk/", "cc/"};

    /** The screen families (a hop-reached member of one makes {@link #REASON_VIEWER_UI}); the answer rows are not. */
    static final Set<String> SCREEN_FAMILIES = Set.of("status", "ui", "viewport", "sound");
    private static final Map<Twin, String> FAMILY = new LinkedHashMap<>();
    private static final Set<String> SCREEN_NAMES = new HashSet<>();

    /** The client table: the shared twin rows retargeted to {@link #TWIN_OWNER}, then the client's own rows. */
    public static final TwinTable KIT = new TwinTable(TWIN_OWNER, kitRows());

    private static List<Twin> kitRows() {
        List<Twin> rows = new ArrayList<>(PilotShipClassifier.TWINS);
        row(rows, "status", ENGINE, "maintainStatusForPlayerShip",
                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V");
        row(rows, "status", ENGINE, "setPaused", "(Z)V");
        row(rows, "status", ENGINE, "endCombat", "(F)V");
        row(rows, "status", ENGINE, "endCombat", "(FLcom/fs/starfarer/api/mission/FleetSide;)V");
        row(rows, "status", ENGINE, "setDoNotEndCombat", "(Z)V");
        row(rows, "ui", COMBAT_UI, "addMessage", "(I[Ljava/lang/Object;)V");
        row(rows, "ui", COMBAT_UI, "setDisablePlayerShipControlOneFrame", "(Z)V");
        row(rows, "ui", COMBAT_UI, "setShipInfoFanOutBrightness", "(F)V");
        row(rows, "ui", COMBAT_UI, "hideShipInfo", "()V");
        row(rows, "ui", COMBAT_UI, "setStrafeToggledOn", "(Z)V");
        row(rows, "viewport", VIEWPORT, "set", "(FFFF)V");
        row(rows, "viewport", VIEWPORT, "setViewMult", "(F)V");
        row(rows, "viewport", VIEWPORT, "setExternalControl", "(Z)V");
        row(rows, "viewport", VIEWPORT, "setCenter", "(Lorg/lwjgl/util/vector/Vector2f;)V");
        row(rows, "viewport", VIEWPORT, "setAlphaMult", "(F)V");
        row(rows, "viewport", VIEWPORT, "setEverythingNearViewport", "(Z)V");
        row(rows, "sound", SOUND, "playUISound", "(Ljava/lang/String;FF)Lcom/fs/starfarer/api/SoundAPI;");
        row(rows, "sound", SOUND, "playUILoop", "(Ljava/lang/String;FF)V");
        row(rows, "sound", SOUND, "playMusic", "(IILjava/lang/String;)V");
        row(rows, "sound", SOUND, "playCustomMusic", "(IILjava/lang/String;)V");
        row(rows, "sound", SOUND, "playCustomMusic", "(IILjava/lang/String;Z)V");
        row(rows, "sound", SOUND, "pauseMusic", "()V");
        row(rows, "sound", SOUND, "pauseCustomMusic", "()V");
        row(rows, "sound", SOUND, "resumeCustomMusic", "()V");
        row(rows, "sound", SOUND, "restartCurrentMusic", "()V");
        row(rows, "sound", SOUND, "setSuspendDefaultMusicPlayback", "(Z)V");
        row(rows, "autopilot", ENGINE, "isUIAutopilotOn", "()Z");
        row(rows, "ai", C + "ShipAPI", "getAI", "()Ljava/lang/Object;");
        row(rows, "ai", C + "CombatEntityAPI", "getAI", "()Ljava/lang/Object;");
        row(rows, "ai", C + "ShipAPI", "getShipAI", "()L" + C + "ShipAIPlugin;");
        return rows;
    }

    /** A receiver-first row: the twin keeps the member's name and takes the receiver as its first argument. */
    private static void row(List<Twin> rows, String family, String owner, String name, String desc) {
        Twin t = new Twin(Opcodes.INVOKEINTERFACE, owner, name, desc, name, "(L" + owner + ";" + desc.substring(1));
        rows.add(t);
        FAMILY.put(t, family);
        if (SCREEN_FAMILIES.contains(family)) {
            SCREEN_NAMES.add(name);
        }
    }

    /** The client row's family ({@code status ui viewport sound autopilot ai}); {@code null} for a shared twin row. */
    static String family(Twin t) {
        return FAMILY.get(t);
    }

    // Match-wide counters (defines run concurrently): read off-frame by countsRow.
    private static final AtomicInteger APPLIED = new AtomicInteger();
    private static final AtomicInteger REFUSED = new AtomicInteger();
    private static final AtomicInteger WRAPPED = new AtomicInteger();
    /** Defines that threw (each latched the receipt); counted whether or not its row printed. */
    private static final AtomicInteger THROWS = new AtomicInteger();
    private static final AtomicInteger ROWS = new AtomicInteger();
    private static final Map<String, AtomicInteger> BY_REASON = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, JarIndex> indexes = new ConcurrentHashMap<>();
    private final Map<ClassLoader, Set<String>> twins = Collections.synchronizedMap(new WeakHashMap<>());

    /** Public no-arg: the agent's premain instantiates this class reflectively, behind its private loader. */
    public ReplicaPilotAgentTransformer() {
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classBeingRedefined != null || !modLoader(loader) || never(className)) {
            return null;
        }
        if (!PilotShipClassifier.isCandidate(className) || PilotShipClassifier.siteNameMask(classfileBuffer) == 0) {
            return null;
        }
        String jar = "unknown";
        try {
            String location = locationOf(protectionDomain);
            jar = MagicSeamRewriter.jarLabel(location);
            Outcome o = decide(className, classfileBuffer, indexFor(location), location, twinsOf(loader));
            if (o == null || !o.serveRewritten) {
                return null; // the shared verdict keeps the class: nothing the client may add
            }
            if (o.bytes != null) {
                APPLIED.incrementAndGet();
                WRAPPED.addAndGet(o.wrapped.size());
                publish();
                row(className, jar, "follows the pilot, " + o.sites + " sites, " + o.wrapped.size() + " windows");
                return o.bytes;
            }
            REFUSED.incrementAndGet();
            BY_REASON.computeIfAbsent(o.kitReason, k -> new AtomicInteger()).incrementAndGet();
            publish();
            row(className, jar, "unchanged: " + o.kitReason + (o.detail.isEmpty() ? "" : " " + o.detail));
            return null;
        } catch (Throwable t) {
            try {
                // the throw's own simple name, never a call into the rewriters: their class may be what failed. The
                // row rides the shared cap (a per-class throw storm prints at most the cap, then one count row).
                String kind = t.getClass().getSimpleName();
                THROWS.incrementAndGet();
                System.setProperty(RECEIPT_SYSPROP, "latched:throw");
                capped(ROW_PREFIX + "latched:throw " + PilotShipClassifier.bounded(
                        className.substring(className.lastIndexOf('/') + 1)) + " (" + jar + "): "
                        + (kind.isEmpty() ? "Throwable" : kind));
            } catch (Throwable ignored) {
                // a failure to report must not escape a class define either; the original class loads
            }
            return null;
        }
    }

    /** One class's client decision; {@code null} when the shared verdict has nothing to say (no candidate site). */
    static final class Outcome {
        boolean serveRewritten;
        Verdict verdict;
        String kitReason;
        String detail = "";
        byte[] bytes;
        int sites;
        boolean viaHop;
        List<ReplicaEntryWrapRewriter.Entry> wrapped = List.of();
        List<ReplicaEntryWrapRewriter.Entry> declared = List.of();
    }

    /**
     * The client decision, shared by {@link #transform} and the census: the shared verdict, then the client-only
     * shrink reasons in a fixed order (illegal names, viewer UI, no twin), then the wrap over the class as shipped
     * when it declares an armed entry row, then the redirect over {@link #KIT} on the wrapped bytes (the reverse order would
     * hand the wrap an input that already names the holder, which it refuses as a repeated window). {@code twins} =
     * the twin class's static members visible to the defining loader ({@code name+desc}), or {@code null} = assume
     * every row present (the census). Never throws for a class the classifier can read; a Throwable escapes only
     * from an internal fault.
     *
     * <p>The wrap-then-redirect order:
     */
    static Outcome decide(String name, byte[] in, JarIndex jar, String location, Set<String> twins) {
        Verdict v = PilotShipClassifier.classify(name, in, jar, null, location);
        if (v == null) {
            return null;
        }
        Outcome o = new Outcome();
        o.verdict = v;
        o.serveRewritten = v.reason() == null;
        o.declared = safeEntries(in);
        if (!o.serveRewritten) {
            o.kitReason = v.reason();
            return o;
        }
        if (never(name)) {
            return refuse(o, REASON_NO_TWIN, "namespace");
        }
        String illegal = ReplicaEntryWrapRewriter.illegalName(in);
        if (illegal != null) {
            return refuse(o, ReplicaEntryWrapRewriter.REASON_ILLEGAL_NAMES, illegal);
        }
        String ui = viewerUi(name, in, jar, location);
        if (ui != null) {
            o.viaHop = !ui.equals("self");
            return refuse(o, REASON_VIEWER_UI, ui);
        }
        if (twins != null) {
            String missing = missingTwin(in, twins, !o.declared.isEmpty());
            if (missing != null) {
                return refuse(o, REASON_NO_TWIN, missing);
            }
        }
        // The wrap reads the class AS SHIPPED, the redirect then runs over the wrapped bytes. WHY this order: the
        // redirect's twins live on the same holder the wrap calls, so a redirected input already names the holder,
        // which the wrap refuses as a repeated window (its guard against wrapping twice) - every class carrying
        // both a twin site and an entry row would load unchanged. The redirect's self-check is table-scoped (it
        // counts only twin names on the owner), so the wrap's enter / exit calls pass through it untouched.
        ReplicaEntryWrapRewriter.Result w = ReplicaEntryWrapRewriter.wrap(in);
        if (w != null && !w.applied()) {
            return refuse(o, w.reason(), w.detail());
        }
        byte[] wrapped = w == null ? in : w.patched();
        PilotShipRedirectRewriter.Result r = PilotShipRedirectRewriter.redirect(wrapped, v, KIT);
        if (!r.applied()) {
            return refuse(o, r.reason(), r.detail());
        }
        o.sites = r.sites();
        o.bytes = r.patched();
        o.wrapped = w == null ? List.of() : w.rows();
        return o;
    }

    private static Outcome refuse(Outcome o, String reason, String detail) {
        o.kitReason = reason == null ? "error:null" : reason;
        o.detail = PilotShipClassifier.bounded(detail);
        return o;
    }

    private static List<ReplicaEntryWrapRewriter.Entry> safeEntries(byte[] in) {
        try {
            return ReplicaEntryWrapRewriter.entriesOf(in);
        } catch (RuntimeException e) {
            return List.of(); // the classifier's own parse reports the class; no entry rows read
        }
    }

    /**
     * The census's view: armed and census-only rows ({@link ReplicaEntryWrapRewriter#declaredOf}), never throws.
     *
     *
     */
    private static List<ReplicaEntryWrapRewriter.Entry> safeDeclared(byte[] in) {
        try {
            return ReplicaEntryWrapRewriter.declaredOf(in);
        } catch (RuntimeException e) {
            return List.of(); // as safeEntries: the classifier's own parse reports the class
        }
    }

    /**
     * {@code self} when the class itself calls a combat-UI member with no client row, or a screen member through
     * the engine's implementation class; {@code via <Simple>.<method>} when a same-jar callee in a class the
     * shared verdict does NOT rewrite calls a screen member (its call stays the real one); else {@code null}.
     * One hop, method-precise on the callee's own declared method; more than {@link #MAX_HOP} callees reads as a hit.
     */
    static String viewerUi(String name, byte[] in, JarIndex jar, String location) {
        Map<String, Set<String>> callees = new LinkedHashMap<>();
        boolean[] self = {false};
        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int a, String nm, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String desc, boolean itf) {
                        if (screenSite(op, owner, n, desc, true)) {
                            self[0] = true;
                        } else if (!owner.equals(name) && !owner.startsWith("[")) {
                            callees.computeIfAbsent(owner, k -> new HashSet<>()).add(n + desc);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (self[0]) {
            return "self";
        }
        if (jar == null) {
            return null;
        }
        int seen = 0;
        for (Map.Entry<String, Set<String>> c : callees.entrySet()) {
            byte[] b = jar.bytes(c.getKey());
            if (b == null) {
                continue;
            }
            if (++seen > MAX_HOP) {
                return "hop_cap";
            }
            Verdict hv = PilotShipClassifier.classify(c.getKey(), b, jar, null, location);
            if (hv != null && hv.reason() == null) {
                continue; // the helper is rewritten too: its screen calls reach the client's drop twins
            }
            String hit = screenMethod(b, c.getValue());
            if (hit != null) {
                return "via " + PilotShipClassifier.simple(c.getKey()) + "." + hit;
            }
        }
        return null;
    }

    /** The first of {@code keys} declared in {@code b} whose body calls a screen member; {@code null} for none. */
    private static String screenMethod(byte[] b, Set<String> keys) {
        String[] hit = {null};
        try {
            new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int a, String nm, String d, String s, String[] e) {
                    if (hit[0] != null || !keys.contains(nm + d)) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String n, String desc, boolean itf) {
                            if (hit[0] == null && screenSite(op, owner, n, desc, false)) {
                                hit[0] = nm;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
            return "unreadable"; // shrink-only: an unreadable helper counts as a hit
        }
        return hit[0];
    }

    /**
     * A screen call: a combat-UI member (with {@code untabledOnly}, only one the client has no row for), a client
     * screen-family row, or a screen member's name called through the engine's implementation class.
     */
    static boolean screenSite(int op, String owner, String n, String desc, boolean untabledOnly) {
        if (owner.equals(ENGINE_IMPL)) {
            return SCREEN_NAMES.contains(n);
        }
        Twin t = KIT.site(op, owner, n, desc);
        if (owner.equals(COMBAT_UI)) {
            return !untabledOnly || t == null;
        }
        return !untabledOnly && t != null && SCREEN_FAMILIES.contains(FAMILY.getOrDefault(t, ""));
    }

    /** The first twin (name only) the class needs that {@code twins} lacks; {@code null} when all are present. */
    static String missingTwin(byte[] in, Set<String> twins, boolean wraps) {
        if (wraps && (!twins.contains(ReplicaEntryWrapRewriter.ENTER + ReplicaEntryWrapRewriter.ENTER_DESC)
                || !twins.contains(ReplicaEntryWrapRewriter.EXIT + ReplicaEntryWrapRewriter.EXIT_DESC))) {
            return twins.isEmpty() ? "twin_class_absent" : "enter_exit";
        }
        String[] miss = {null};
        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int a, String nm, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String desc, boolean itf) {
                        Twin t = KIT.site(op, owner, n, desc);
                        if (miss[0] == null && t != null && !twins.contains(t.twin() + t.twinDesc())) {
                            miss[0] = twins.isEmpty() ? "twin_class_absent" : t.twin();
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return miss[0];
    }

    /**
     * The twin class's public static members as the defining loader sees them, read as a resource (no class load,
     * no initializer); an absent or unreadable class = the empty set (every class that needs it is refused).
     */
    private Set<String> twinsOf(ClassLoader loader) {
        Set<String> s = twins.get(loader);
        if (s != null) {
            return s;
        }
        Set<String> found = new HashSet<>();
        try (InputStream in = loader.getResourceAsStream(TWIN_OWNER + ".class")) {
            if (in != null) {
                byte[] b = in.readNBytes(1 << 20);
                new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int a, String nm, String d, String sg, String[] e) {
                        if ((a & Opcodes.ACC_STATIC) != 0 && (a & Opcodes.ACC_PUBLIC) != 0) {
                            found.add(nm + d);
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        } catch (IOException | RuntimeException e) {
            found.clear(); // counted by the refusals it causes (no_twin rows)
        }
        Set<String> frozen = Set.copyOf(found);
        twins.put(loader, frozen);
        return frozen;
    }

    private static boolean modLoader(ClassLoader loader) {
        return loader != null && loader != ClassLoader.getSystemClassLoader()
                && loader != ClassLoader.getPlatformClassLoader();
    }

    static boolean never(String internalName) {
        for (String p : NEVER) {
            if (internalName.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private JarIndex indexFor(String location) {
        if (location == null) {
            return null;
        }
        JarIndex index = indexes.get(location);
        if (index == null) {
            index = JarIndex.forLocation(location);
            JarIndex prior = index == null ? null : indexes.putIfAbsent(location, index);
            index = prior != null ? prior : index;
        }
        return index;
    }

    private static String locationOf(ProtectionDomain domain) {
        CodeSource source = domain == null ? null : domain.getCodeSource();
        URL location = source == null ? null : source.getLocation();
        return location == null ? null : location.toString();
    }

    /** The receipt, never over a latched or disarmed value; {@code armed} stays until a class is rewritten. */
    private static synchronized void publish() {
        String now = System.getProperty(RECEIPT_SYSPROP);
        if (now != null && (now.startsWith("latched:") || now.startsWith("disarmed:"))) {
            return;
        }
        if (APPLIED.get() > 0) {
            System.setProperty(RECEIPT_SYSPROP, "applied:" + APPLIED.get() + " refused:" + REFUSED.get());
        }
    }

    private static void row(String className, String jar, String what) {
        capped(ROW_PREFIX + PilotShipClassifier.bounded(className.substring(className.lastIndexOf('/') + 1)) + " ("
                + jar + "): " + what);
    }

    /**
     * Every per-class row (an outcome or a latched throw) goes through ONE cap: the first {@link #ROW_CAP} print,
     * then one {@code rows_capped} row; the rest are only counted ({@code rows_dropped} in {@link #countsRow()}).
     *
     *
     */
    private static void capped(String line) {
        int n = ROWS.getAndIncrement();
        if (n < ROW_CAP) {
            System.out.println(line);
        } else if (n == ROW_CAP) {
            System.out.println(ROW_PREFIX + "rows_capped=" + ROW_CAP + " (later classes counted only, see countsRow)");
        }
    }

    /**
     * The match-end count row: {@code pilotreplica applied=<k> refused=<r> windows=<w> throws=<t> rows_dropped=<d>}
     * then one {@code <reason>=<n>} per refusal reason seen. Public and argument-free for an off-frame reflective
     * reader.
     */
    public static String countsRow() {
        StringBuilder sb = new StringBuilder("pilotreplica applied=").append(APPLIED.get()).append(" refused=")
                .append(REFUSED.get()).append(" windows=").append(WRAPPED.get()).append(" throws=")
                .append(THROWS.get()).append(" rows_dropped=").append(Math.max(0, ROWS.get() - ROW_CAP));
        for (Map.Entry<String, AtomicInteger> e : new TreeMap<>(BY_REASON).entrySet()) {
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue().get());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- census mode (the static spike, no game)

    /** The census totals (the printed rows' values) and the per-class verdict pairs the corpus rows assert on. */
    public static final class Census {
        public final Map<String, Integer> counts = new LinkedHashMap<>();
        /**
         * Every class the shared verdict judged: {@code name -> {serve verdict, client verdict, client detail}}; a
         * verdict is {@code -} when that side rewrites the class, else its reason ({@code none} = no answer); the
         * detail is the client refusal's bounded detail, else empty.
         */
        public final Map<String, String[]> classes = new TreeMap<>();
        /** Every wrapped entry row, as {@code class.method+desc}. */
        public final List<String> wrapped = new ArrayList<>();

        void add(String key, int n) {
            counts.merge(key, n, Integer::sum);
        }
    }

    /**
     * Receives every class the census rewrites on either side, with the exact bytes it judged: {@code serve} = the
     * server's redirect copy, {@code kit} = the client's wrapped copy, each {@code null} when that side leaves the class
     * alone. WHY a sink: the build gate links these very bytes, so its class set equals the census's counts by
     * construction instead of by a second walk that could drift.
     */
    public interface RewriteSink {
        void accept(String name, String location, byte[] original, byte[] serve, byte[] kit);
    }

    /** The census with no byte consumer (the printed columns only). */
    public static Census census(List<Path> jars, PrintStream out) throws IOException {
        return census(jars, out, null);
    }

    /**
     * Runs the client decision DRY over {@code jars} (each a jar; the defining jar is its own index, as on a
     * client) and prints the static spike's columns as {@code name=value} rows, then returns the totals. Pass the
     * API jar too: it resolves the entry rows' supertype walk (its own engine-namespace classes count as
     * {@code no_twin}, as the client never touches them).
     *
     * <p>The census-only damage columns ({@code damage_listener_all} over every declared row,
     * {@code damage_listener_sites} over the declared rows of a class the client rewrites; {@code w_sites} = the
     * windows actually wrapped):
     *
     * <p>{@code sink} ({@code null} = none) receives each class either side rewrites, before its counts are added, so
     * a consumer sees exactly the {@code serve_rewritten} / {@code kit_rewritten} set.
     *
     */
    public static Census census(List<Path> jars, PrintStream out, RewriteSink sink) throws IOException {
        long t0 = System.nanoTime();
        Census c = new Census();
        for (String k : List.of("classes_scanned", "serve_rewritten", "kit_rewritten", "kit_not_serve", "w_sites",
                "w_all", "same_shape_non_overrides", "same_shape_unresolved", "unapply_sites", "listener_sites",
                "plugin_sites", "raw_constant_key_polls", "hop_reached", "viewer_ui", "spawn_calls", "illegal_names",
                "damage_listener_sites", "damage_listener_all", "d5_writer_sites", "d5_writer_classes")) {
            c.counts.put(k, 0);
        }
        Map<String, String[]> supers = new HashMap<>();
        for (Path j : jars) {
            eachClass(j, (n, b) -> {
                ClassReader cr = new ClassReader(b);
                String[] row = new String[cr.getInterfaces().length + 1];
                row[0] = cr.getSuperName();
                System.arraycopy(cr.getInterfaces(), 0, row, 1, row.length - 1);
                supers.putIfAbsent(n, row);
            });
        }
        for (Path j : jars) {
            String location = j.toString();
            JarIndex idx = JarIndex.forLocation(location);
            eachClass(j, (name, bytes) -> {
                c.add("classes_scanned", 1);
                List<ReplicaEntryWrapRewriter.Entry> declared = safeDeclared(bytes);
                c.add("w_all", declared.size());
                for (ReplicaEntryWrapRewriter.Entry e : declared) {
                    c.add("damage_listener_all", e.family().equals("damage") ? 1 : 0);
                }
                PilotShipRedirectRewriter.Result serve = PilotShipRedirectRewriter.rewrite(name, bytes, idx, Set.of(),
                        location);
                Outcome o = decide(name, bytes, idx, location, null);
                if (serve == null && o == null) {
                    return;
                }
                boolean sr = serve != null && serve.applied();
                boolean kr = o != null && o.bytes != null;
                if (sink != null && (sr || kr)) {
                    sink.accept(name, location, bytes, sr ? serve.patched() : null, kr ? o.bytes : null);
                }
                c.classes.put(name, new String[] {sr ? "-" : serve == null ? "none" : serve.reason(),
                        kr ? "-" : o == null ? "none" : o.kitReason, kr || o == null ? "" : o.detail});
                c.add("serve_rewritten", sr ? 1 : 0);
                c.add("kit_rewritten", kr ? 1 : 0);
                c.add("kit_not_serve", kr && !sr ? 1 : 0);
                if (o != null && o.serveRewritten && !kr) {
                    c.add("kit_reason_" + o.kitReason.replace(':', '_'), 1);
                    c.add("viewer_ui", REASON_VIEWER_UI.equals(o.kitReason) ? 1 : 0);
                    c.add("hop_reached", o.viaHop ? 1 : 0);
                    c.add("illegal_names", ReplicaEntryWrapRewriter.REASON_ILLEGAL_NAMES.equals(o.kitReason) ? 1 : 0);
                }
                if (sr) {
                    scanRewritten(c, name, bytes, kr);
                }
                if (kr) {
                    c.add("w_sites", o.wrapped.size());
                    // the census-only family counts where it WOULD open a window (the declared rows of a class the
                    // client rewrites); the wrap never wraps it
                    for (ReplicaEntryWrapRewriter.Entry e : declared) {
                        c.add("damage_listener_sites", e.family().equals("damage") ? 1 : 0);
                    }
                    for (ReplicaEntryWrapRewriter.Entry e : o.wrapped) {
                        c.wrapped.add(name + "." + e.key());
                        int reach = reaches(name, e.itf(), supers);
                        c.add(reach == 0 ? "same_shape_non_overrides" : reach < 0 ? "same_shape_unresolved" : "w_ok",
                                1);
                    }
                    if (c.counts.getOrDefault("kit_class_rows", 0) < ROW_CAP) {
                        c.add("kit_class_rows", 1);
                        out.println("kit_class=" + name + " jar=" + MagicSeamRewriter.jarLabel(location) + " sites="
                                + o.sites + " windows=" + families(o.wrapped));
                    }
                }
            });
        }
        c.counts.put("index_errors", JarIndex.errorCount());
        c.counts.put("jars", jars.size());
        c.counts.put("ms", (int) ((System.nanoTime() - t0) / 1_000_000));
        for (Map.Entry<String, Integer> e : c.counts.entrySet()) {
            out.println(e.getKey() + "=" + e.getValue());
        }
        JarIndex.closeAll();
        return c;
    }

    private static String families(List<ReplicaEntryWrapRewriter.Entry> rows) {
        List<String> f = new ArrayList<>();
        for (ReplicaEntryWrapRewriter.Entry e : rows) {
            f.add(e.family() + ":" + e.name());
        }
        return f.isEmpty() ? "none" : String.join("+", f);
    }

    /** 1 = the class reaches {@code itf} through its supertypes, 0 = it provably does not, -1 = the walk left the jars. */
    static int reaches(String name, String itf, Map<String, String[]> supers) {
        List<String> q = new ArrayList<>(List.of(name));
        Set<String> seen = new HashSet<>(q);
        boolean unresolved = false;
        for (int i = 0; i < q.size() && i < 256; i++) {
            String s = q.get(i);
            if (s.equals(itf)) {
                return 1;
            }
            String[] row = supers.get(s);
            if (row == null) {
                unresolved |= !s.equals("java/lang/Object");
                continue;
            }
            for (String x : row) {
                if (x != null && seen.add(x)) {
                    q.add(x);
                }
            }
        }
        return unresolved ? -1 : 0;
    }

    /**
     * The D5 WRITERS (census only): the engine / sound-player setters a class the client rewrites may call on the
     * viewer's screen inside a replica window — the client neither drops them nor refuses the class as
     * {@code viewer_ui}, so their count is the exposure the S1 spike reads ({@code d5_writer_sites} = the call
     * sites in client-rewritten classes, {@code d5_writer_classes} = those classes). Keyed {@code owner.name}.
     *
     */
    private static final Set<String> D5_WRITERS = Set.of(
            SOUND + ".applyLowPassFilter", SOUND + ".setListenerPosOverrideOneFrame",
            ENGINE + ".setCombatNotOverFor", ENGINE + ".setCombatNotOverForAtLeast", ENGINE + ".setCustomExit",
            ENGINE + ".setBackgroundColor", ENGINE + ".setBackgroundGlowColor",
            ENGINE + ".setBackgroundGlowColorNonAdditive", ENGINE + ".setRenderStarfield");

    /**
     * The per-site columns over one class the serve rewrites (the shared verdict's set, the client's superset);
     * {@code kit} = the client rewrites it too (the D5 writer columns count only there).
     */
    private static void scanRewritten(Census c, String name, byte[] bytes, boolean kit) {
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            private boolean writerSeen;

            @Override
            public void visitEnd() {
                if (writerSeen) {
                    c.add("d5_writer_classes", 1);
                }
            }

            @Override
            public MethodVisitor visitMethod(int a, String nm, String d, String s, String[] e) {
                String key = nm + d;
                boolean unapply = key.equals(ReplicaEntryWrapRewriter.UNAPPLY_KEY);
                boolean listener = key.equals("advance(F)V");
                boolean plugin = key.equals("advance(FLjava/util/List;)V");
                return new MethodVisitor(Opcodes.ASM9) {
                    private int lastOp = -1;
                    private boolean counted;

                    @Override
                    public void visitInsn(int opcode) {
                        lastOp = opcode;
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        lastOp = Opcodes.BIPUSH;
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        lastOp = value instanceof Integer ? Opcodes.BIPUSH : Opcodes.LDC;
                    }

                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        lastOp = opcode;
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String n, String desc) {
                        lastOp = opcode;
                    }

                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String desc, boolean itf) {
                        Twin t = KIT.site(op, owner, n, desc);
                        if (t != null && !counted && (unapply || listener || plugin)) {
                            counted = true;
                            c.add(unapply ? "unapply_sites" : listener ? "listener_sites" : "plugin_sites", 1);
                        }
                        String fam = t == null ? null : FAMILY.get(t);
                        if (fam != null) {
                            c.add("screen_" + fam, 1);
                        }
                        boolean constArg = (lastOp >= Opcodes.ICONST_M1 && lastOp <= Opcodes.ICONST_5)
                                || lastOp == Opcodes.BIPUSH;
                        if (t != null && t.name().equals("isKeyDown") && constArg) {
                            c.add("raw_constant_key_polls", 1);
                        }
                        if (owner.equals(ENGINE) && (n.startsWith("spawn") || n.equals("addPlugin"))) {
                            c.add("spawn_calls", 1);
                        }
                        if (kit && D5_WRITERS.contains(owner + '.' + n)) {
                            c.add("d5_writer_sites", 1);
                            writerSeen = true;
                        }
                        lastOp = op;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private interface ClassSink {
        void accept(String internalName, byte[] bytes) throws IOException;
    }

    private static void eachClass(Path jar, ClassSink sink) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String fn = e.getName();
                if (e.isDirectory() || !fn.endsWith(".class") || fn.startsWith("META-INF/")
                        || fn.endsWith("module-info.class") || fn.endsWith("package-info.class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(e)) {
                    bytes = in.readNBytes(JarIndex.MAX_CLASS_BYTES + 1);
                }
                if (bytes.length > JarIndex.MAX_CLASS_BYTES) {
                    continue;
                }
                try {
                    sink.accept(fn.substring(0, fn.length() - ".class".length()), bytes);
                } catch (RuntimeException malformed) {
                    // census only: a class ASM cannot read is skipped; the classifier's own pass reports it
                }
            }
        }
    }

    /** Every {@code *.jar} under the arguments (a jar itself, or a folder walked at most 8 deep), sorted. */
    public static List<Path> expand(List<String> jarsOrDirs) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String a : jarsOrDirs) {
            Path p = Path.of(a);
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p, 8)) {
                    s.filter(x -> Files.isRegularFile(x) && x.getFileName().toString().toLowerCase(
                            java.util.Locale.ROOT).endsWith(".jar")).sorted().forEach(out::add);
                }
            } else if (Files.isRegularFile(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** {@code <jar|dir>...}: the census over every jar found; exit 2 = no jar found. */
    public static void main(String[] jarsOrDirs) throws IOException {
        List<Path> jars = expand(List.of(jarsOrDirs));
        if (jars.isEmpty()) {
            System.err.println("usage: ReplicaPilotAgentTransformer <jar|dir>... (no jar found)");
            System.exit(2);
        }
        census(jars, System.out);
    }
}
