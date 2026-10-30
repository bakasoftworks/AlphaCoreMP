package cc.starsector.compat.pilotship;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import cc.starsector.compat.magic.MagicSeamRewriter;

/**
 * Decides, per mod or vanilla-script class, whether its {@code CombatEngineAPI.getPlayerShip()} calls may follow the
 * pilot (the class is rewritten by {@link PilotShipRedirectRewriter}) or must keep the engine's one answer (the class
 * stays byte-for-byte, with ONE counted reason). Bytes only: ASM core, no server class, nothing loaded.
 *
 * <p>WHY a classifier: the redirect makes a player branch run for every human-flown hull inside that hull's own
 * update. That is right for per-ship gameplay (a system's cursor target, a hullmod's own-hull bonus) and wrong where
 * the branch reaches beyond the ship or into hardware the server lacks: the battle clock (ONE engine-global stat), the
 * local keyboard and mouse (zero-seeded / never created on the server), immediate-mode drawing, a hand-over of the
 * engine's one player ship, and a side-wide bonus that must not stack once per human. Such classes keep today's answer.
 *
 * <p>The reasons, first match wins ({@link #REASON_ORDER}): a fixed {@link #DENY_LIST} entry; the host's exclude row;
 * an adopter (the pool holds {@link #PILOT_MARK}: the class already follows the pilot itself); IS-A
 * {@link #MAGIC_SUBSYSTEM} through the same-jar superclass chain (a mere reference is no reason); then four USE
 * reasons, each from the class's OWN code or from one same-jar hop: control transfer
 * ({@code setPlayerShipExternal} / {@code reFanOutShipInfo}), keyboard / mouse / input events, drawing (OpenGL or the
 * libraries' draw helpers), and a battle-clock WRITER; then a class another load-time transform already owns; then a
 * class whose pool names {@code getPlayerShip} but holds no redirect site.
 *
 * <p>A USE is an instruction, a field or method descriptor, a catch type, an invokedynamic handle or an ldc constant;
 * a constant-pool Class entry nothing uses (an inlined constant's leftover) and a generic-signature mention are no use.
 * The clock rule excludes WRITERS only: the value of {@code getTimeMult()} is followed on the operand stack (through
 * arithmetic, string building, null checks, casts and local stores) to its consumer; a primitive {@code get*}/{@code is*}
 * read is no reason, a {@code modify*}/{@code unmodify*} is a writer, and every other consumer (an escape into a field,
 * a return, an argument, an unmodelled stack shuffle) counts as a writer (fail-safe).
 *
 * <p>The hop is METHOD-precise: a same-jar method the class calls, resolved on its declaring class (superclasses first,
 * then interface default methods), counts only when that method, the same-hierarchy methods it calls and its class
 * initializer reach a use; the site class itself is judged on its whole own code (no per-method closure). Measured
 * over 27 jars, the class rule as first drafted lost 12 per-ship gameplay sites to grab-bag helpers and clock
 * readers; the method-precise hop and the writer-only clock rule keep 34 of the 35 - the one left unchanged is a
 * lock-on script whose class initializer loads a LazyFont, which the drawing rule counts.
 *
 *
 */
public final class PilotShipClassifier {

    private PilotShipClassifier() {
    }

    /** The redirected call's owner; the site is keyed on owner + name + descriptor together. */
    public static final String ENGINE_API = "com/fs/starfarer/api/combat/CombatEngineAPI";
    /** The redirected call's name (a same-named method with another descriptor is no site). */
    public static final String GET_PLAYER_SHIP = "getPlayerShip";
    /** The redirected call's descriptor. */
    public static final String GET_PLAYER_SHIP_DESC = "()Lcom/fs/starfarer/api/combat/ShipAPI;";
    /** The engine's own implementation class: a clock call through it is tracked the same as through the API. */
    static final String ENGINE_IMPL = "com/fs/starfarer/combat/CombatEngine";
    static final String GET_TIME_MULT = "getTimeMult";
    static final String KEYBOARD = "org/lwjgl/input/Keyboard";
    static final String MOUSE = "org/lwjgl/input/Mouse";
    static final String INPUT_EVENT = "com/fs/starfarer/api/input/InputEventAPI";
    static final String GL_PACKAGE = "org/lwjgl/opengl/";
    static final String KOTLIN_INTRINSICS = "kotlin/jvm/internal/Intrinsics";
    /** MagicLib's subsystem base: its subclasses run in MagicLib's own plugin loop, outside any ship's update. */
    public static final String MAGIC_SUBSYSTEM = "org/magiclib/subsystems/MagicSubsystem";
    /** The pilot mark's custom-data key: a class naming it already follows the pilot on its own. */
    public static final String PILOT_MARK = "cc_client.pilot";
    /** Calls that hand the engine's one player ship to another hull. Matched by name (any owner): fail-safe. */
    static final Set<String> CONTROL_TRANSFER_CALLS = Set.of("setPlayerShipExternal", "reFanOutShipInfo");
    /** MagicUI's immediate-mode draw helpers (both MagicUI copies; plus every {@code *WithinViewport}). */
    static final Set<String> MAGIC_UI_DRAWS = Set.of("addBar", "addText", "addTextNonScaling", "addStatusText",
            "openGL11ForText", "closeGL11ForText");

    public static final String REASON_DENY_LIST = "deny_list";
    public static final String REASON_HOST_EXCLUDE = "host_exclude";
    public static final String REASON_ADOPTER = "adopter";
    public static final String REASON_MAGIC_SUBSYSTEM = "magic_subsystem";
    public static final String REASON_CONTROL_TRANSFER = "control_transfer";
    public static final String REASON_KEYS_OR_MOUSE = "keys_or_mouse";
    public static final String REASON_DRAWING = "drawing";
    public static final String REASON_BATTLE_SPEED = "battle_speed";
    public static final String REASON_REGISTRY_ENTRY = "registry_entry";
    public static final String REASON_NO_SITES = "no_sites";
    /** Prefix of the reason for any Throwable while deciding, followed by its simple class name. */
    public static final String REASON_ERROR_PREFIX = "error:";
    /** Every fixed reason, in the order they are tried (one counted reason per unchanged class). */
    public static final List<String> REASON_ORDER = List.of(REASON_DENY_LIST, REASON_HOST_EXCLUDE, REASON_ADOPTER,
            REASON_MAGIC_SUBSYSTEM, REASON_CONTROL_TRANSFER, REASON_KEYS_OR_MOUSE, REASON_DRAWING, REASON_BATTLE_SPEED,
            REASON_REGISTRY_ENTRY, REASON_NO_SITES);

    /**
     * Fixed entries that keep today's answer whatever their code says. OperationsCenter and Knights of Ludd's
     * OracleSensorSuite raise their whole SIDE's command-point recovery from the flagship: once per human it would
     * stack, and the game rule is no stacking. MagicShipSystemSubsystem's player branch never advances its status
     * index (an endless loop if it ran). Secrets of the Frontier's Soulbond grants an ability to a DIFFERENT hull (the
     * engine's player ship) that moves both ships across the field — a beyond-the-ship effect under the same rule
     * (a first-hand read of the mod's two classes' bytecode — the Soulbond recon, verdict: deny).
     */
    public static final Set<String> DENY_LIST = Set.of(
            "com/fs/starfarer/api/impl/hullmods/OperationsCenter",
            "org/magiclib/subsystems/MagicShipSystemSubsystem",
            "org/selkie/kol/hullmods/OracleSensorSuite",
            "data/hullmods/SotfSoulbond");

    /**
     * Classes another load-time transform already rewrites: one class never carries two rewrites. A hand mirror of
     * the server registry's entries (this unit cannot see server classes); today's one entry also sits outside the
     * candidate namespaces, so the reason is a guard for an entry added later.
     */
    public static final Set<String> REGISTRY_ENTRY_CLASSES = Set.of("com/fs/starfarer/api/util/Misc");

    /** Internal-name prefixes never rewritten: own code, ASM, runtimes and libraries that never ask. */
    static final String[] NON_CANDIDATE_PREFIXES = {"cc/", "org/objectweb/asm/", "java/", "javax/", "jdk/", "sun/",
            "com/sun/", "kotlin/", "org/lwjgl/", "org/json/", "org/apache/", "org/codehaus/", "com/thoughtworks/"};
    /** The engine's namespace: never rewritten (its readers keep the real answer by construction) ... */
    static final String ENGINE_PREFIX = "com/fs/starfarer/";
    /** ... except vanilla's own script classes, which are mod-like content shipped in the API jar. */
    static final String SCRIPT_PREFIX = "com/fs/starfarer/api/impl/";

    static final int B_CONTROL = 1;
    static final int B_KEYS = 2;
    static final int B_GL = 4;
    static final int B_CLOCK_WRITE = 8;
    static final int B_CLOCK_READ = 16;
    /** The use bits that make a reason, in reason order, with their reasons. */
    static final int[] USE_BITS = {B_CONTROL, B_KEYS, B_GL, B_CLOCK_WRITE};
    static final String[] USE_REASONS = {REASON_CONTROL_TRANSFER, REASON_KEYS_OR_MOUSE, REASON_DRAWING,
            REASON_BATTLE_SPEED};
    static final int B_REASONS = B_CONTROL | B_KEYS | B_GL | B_CLOCK_WRITE;

    /** A hop closure wider than this many methods falls back to the declaring class's whole own uses. */
    static final int MAX_REACH = 512;
    /** Bound on a detail string (a log row may carry it). */
    static final int MAX_DETAIL = 64;

    private static final byte[] GPS_BYTES = GET_PLAYER_SHIP.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MARK_BYTES = PILOT_MARK.getBytes(StandardCharsets.US_ASCII);

    // ---------------------------------------------------------------- the verdict

    /** One class's decision: {@link #reason()} {@code null} = rewrite every site; else the one counted reason. */
    public static final class Verdict {
        private final String reason;
        private final int sites;
        private final String detail;
        private final Map<String, Integer> siteMethods;

        Verdict(String reason, int sites, String detail, Map<String, Integer> siteMethods) {
            this.reason = reason;
            this.sites = sites;
            this.detail = bounded(detail);
            this.siteMethods = Collections.unmodifiableMap(siteMethods);
        }

        /** {@code null} = the class follows the pilot; else one of {@link #REASON_ORDER} or {@code error:<SimpleName>}. */
        public String reason() {
            return reason;
        }

        /** Redirect sites in the class (>= 0; 0 for an error before the parse finished). */
        public int sites() {
            return sites;
        }

        /** Where the reason came from ({@code self}, {@code via <Class>.<method>}, ...), printable ASCII, <= 64 chars. */
        public String detail() {
            return detail;
        }

        /** Sites per method (name + descriptor), in name order; empty when none were counted. */
        public Map<String, Integer> siteMethods() {
            return siteMethods;
        }
    }

    /** True for a class the redirect may touch (by internal name alone; see the prefix tables). */
    public static boolean isCandidate(String internalName) {
        if (internalName == null || internalName.isEmpty()) {
            return false;
        }
        for (String p : NON_CANDIDATE_PREFIXES) {
            if (internalName.startsWith(p)) {
                return false;
            }
        }
        return !internalName.startsWith(ENGINE_PREFIX) || internalName.startsWith(SCRIPT_PREFIX);
    }

    /** True when {@code site} is the redirected call (owner + name + descriptor + the interface opcode). */
    public static boolean isSite(int opcode, String owner, String name, String descriptor) {
        return opcode == Opcodes.INVOKEINTERFACE && ENGINE_API.equals(owner) && GET_PLAYER_SHIP.equals(name)
                && GET_PLAYER_SHIP_DESC.equals(descriptor);
    }

    /**
     * The cheap pre-filter, before any ClassReader: does a constant-pool Utf8 equal {@code getPlayerShip}? A truncated
     * or unknown pool reads {@code true}, so the full parse decides and reports {@code error:<SimpleName>} once.
     */
    public static boolean mentionsGetPlayerShip(byte[] b) {
        if (b == null || b.length < 10 || (b[0] & 0xff) != 0xCA || (b[1] & 0xff) != 0xFE) {
            return false;
        }
        int[] off = poolOffsets(b);
        if (off == null) {
            return true;
        }
        for (int i = 1; i < off.length - 1; i++) {
            if (off[i] > 0 && b[off[i]] == 1 && utf8Equals(b, off[i], GPS_BYTES)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The decision. {@code null} when the class is no candidate or its pool never names {@code getPlayerShip} (no
     * parse); else a {@link Verdict} that never throws. {@code jar} = the defining code source's index for the hop and
     * the IS-A chain ({@code null} = no hop); {@code hostExclude} = the host's exclude entries ({@code null} = none);
     * {@code codeSourcePath} = the code source location as a path or a {@code file:} URL ({@code null} = unknown).
     */
    public static Verdict classify(String internalName, byte[] bytes, JarIndex jar, Set<String> hostExclude,
            String codeSourcePath) {
        if (!isCandidate(internalName) || !mentionsGetPlayerShip(bytes)) {
            return null;
        }
        try {
            return decide(internalName, bytes, jar, hostExclude, codeSourcePath);
        } catch (Throwable t) {
            // Fail-OPEN per class: the reason row is the count; the caller loads the original bytes.
            return new Verdict(REASON_ERROR_PREFIX + MagicSeamRewriter.simpleName(t), 0, "", Map.of());
        }
    }

    /**
     * The verdict ladder behind {@link #classify}. LIMIT: with no jar index (a loose script the game compiles from a
     * mod's source files at load has no code source location) no helper hop is walked, so a same-mod helper
     * that alone carries a use reason (control transfer, keyboard or mouse, drawing, clock write) leaves the class
     * rewritten unless the host excludes it.
     *
     *
     */
    private static Verdict decide(String internalName, byte[] bytes, JarIndex jar, Set<String> hostExclude,
            String codeSourcePath) {
        ClassModel c = model(bytes);
        if (!internalName.equals(c.name)) {
            return new Verdict(REASON_ERROR_PREFIX + "name_mismatch", c.sites, "", c.siteMethods());
        }
        Map<String, Integer> sm = c.siteMethods();
        if (DENY_LIST.contains(c.name)) {
            return new Verdict(REASON_DENY_LIST, c.sites, "fixed entry", sm);
        }
        if (hostExcluded(hostExclude, codeSourcePath, c.name)) {
            return new Verdict(REASON_HOST_EXCLUDE, c.sites, "host row", sm);
        }
        if (c.pilotMark) {
            return new Verdict(REASON_ADOPTER, c.sites, "self", sm);
        }
        String[] via = new String[USE_BITS.length];
        int hop;
        synchronized (jar == null ? c : jar) {
            if (isMagicSubsystem(c, jar)) {
                return new Verdict(REASON_MAGIC_SUBSYSTEM, c.sites, "is-a", sm);
            }
            hop = jar == null ? 0 : hop(c, jar, via);
        }
        for (int k = 0; k < USE_BITS.length; k++) {
            if ((c.ownBits & USE_BITS[k]) != 0) {
                return new Verdict(USE_REASONS[k], c.sites, "self", sm);
            }
            if ((hop & USE_BITS[k]) != 0) {
                return new Verdict(USE_REASONS[k], c.sites, via[k], sm);
            }
        }
        if (REGISTRY_ENTRY_CLASSES.contains(c.name)) {
            return new Verdict(REASON_REGISTRY_ENTRY, c.sites, "registry entry", sm);
        }
        if (c.sites == 0) {
            return new Verdict(REASON_NO_SITES, 0, "", sm);
        }
        return new Verdict(null, c.sites, "", sm);
    }

    // ---------------------------------------------------------------- host exclude row

    /**
     * Splits the host's {@code pilotship.exclude} comma list: trimmed, non-empty, at most 64 entries of at most 128
     * chars (an entry over the bound is dropped, never truncated into a different name).
     */
    public static Set<String> parseExcludeList(String commaList) {
        Set<String> out = new LinkedHashSet<>();
        if (commaList == null) {
            return out;
        }
        for (String e : commaList.split(",")) {
            String t = e.trim();
            if (!t.isEmpty() && t.length() <= 128 && out.size() < 64) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * True when an entry names the class's mod, case-insensitive: the location's jar FILE NAME (with or without
     * {@code .jar}) or any PATH SEGMENT equals it, so a mod's folder name matches as well as its jar; or, read as a
     * package prefix (dots as slashes, trailing slashes trimmed), the internal name equals it or starts with it plus
     * {@code /}, so a mod shipped as loose scripts (no code source location) is reachable too and
     * {@code data.scripts.mymod} never matches {@code data/scripts/mymodx/Foo}. A {@code null} location skips the
     * location test; a {@code null} internal name skips the name test.
     *
     *
     */
    static boolean hostExcluded(Set<String> exclude, String location, String internalName) {
        if (exclude == null || exclude.isEmpty()) {
            return false;
        }
        String[] segs = location == null ? new String[0] : decodeLocation(location).split("[/\\\\]+");
        String last = segs.length == 0 ? "" : segs[segs.length - 1];
        String stem = last.toLowerCase(Locale.ROOT).endsWith(".jar") ? last.substring(0, last.length() - 4) : null;
        for (String e : exclude) {
            String t = e == null ? "" : e.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (stem != null && stem.equalsIgnoreCase(t)) {
                return true;
            }
            for (String s : segs) {
                if (s.equalsIgnoreCase(t)) {
                    return true;
                }
            }
            String p = t.replace('.', '/');
            while (p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            if (internalName != null && !p.isEmpty() && internalName.regionMatches(true, 0, p, 0, p.length())
                    && (internalName.length() == p.length() || internalName.charAt(p.length()) == '/')) {
                return true;
            }
        }
        return false;
    }

    /** A code-source location as a plain path string: {@code jar:} / {@code file:} / {@code !/} and %XX undone. */
    static String decodeLocation(String loc) {
        String s = loc.trim();
        if (s.startsWith("jar:")) {
            s = s.substring(4);
        }
        int bang = s.indexOf("!/");
        if (bang >= 0) {
            s = s.substring(0, bang);
        }
        if (!s.startsWith("file:")) {
            return s;
        }
        s = s.substring(5);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '%' && i + 2 < s.length() && Character.digit(s.charAt(i + 1), 16) >= 0
                    && Character.digit(s.charAt(i + 2), 16) >= 0) {
                out.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.append(ch);
            }
        }
        String p = out.toString();
        // file:/C:/x -> C:/x (a leading slash before a drive letter is URL form, not a path root)
        while (p.length() > 3 && p.charAt(0) == '/' && (p.charAt(2) == ':' || p.charAt(1) == '/')) {
            p = p.substring(1);
        }
        return p;
    }

    // ---------------------------------------------------------------- the class model

    /** One method: its use bits, its calls (for the hop) and its redirect sites. */
    static final class MethodModel {
        final String key;
        final int access;
        int bits;
        int sites;
        final List<String[]> calls = new ArrayList<>();
        /** Memo of the hop closure's bits; -1 = not computed. */
        int reach = -1;

        MethodModel(String key, int access) {
            this.key = key;
            this.access = access;
        }
    }

    /** One class: header, methods, its own use bits (methods + field / method descriptors), adopter flag, sites. */
    static final class ClassModel {
        String name;
        String superName;
        String[] interfaces = new String[0];
        final Map<String, MethodModel> methods = new LinkedHashMap<>();
        int ownBits;
        boolean pilotMark;
        int sites;

        Map<String, Integer> siteMethods() {
            Map<String, Integer> out = new TreeMap<>();
            for (MethodModel m : methods.values()) {
                if (m.sites > 0) {
                    out.put(m.key, m.sites);
                }
            }
            return out;
        }
    }

    /** A resolved call: the declaring class and its method. */
    record Target(ClassModel cls, MethodModel method) {
    }

    static ClassModel model(byte[] bytes) {
        ClassModel c = new ClassModel();
        c.pilotMark = poolHasMark(bytes);
        new ClassReader(bytes).accept(new ModelVisitor(c), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (MethodModel m : c.methods.values()) {
            c.ownBits |= m.bits;
            c.sites += m.sites;
        }
        return c;
    }

    private static final class ModelVisitor extends ClassVisitor {
        private final ClassModel c;

        ModelVisitor(ClassModel c) {
            super(Opcodes.ASM9);
            this.c = c;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            c.name = name;
            c.superName = superName;
            c.interfaces = interfaces == null ? new String[0] : interfaces;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            c.ownBits |= descBits(descriptor);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            MethodModel m = new MethodModel(name + descriptor, access);
            m.bits |= descBits(descriptor);
            c.methods.putIfAbsent(m.key, m);
            return new CodeVisitor(m);
        }
    }

    /** Collects a method's use bits, calls and sites; records its instructions for the clock walk. */
    private static final class CodeVisitor extends MethodVisitor {
        private final MethodModel m;
        private List<Insn> ins = new ArrayList<>();
        private boolean clock;

        CodeVisitor(MethodModel m) {
            super(Opcodes.ASM9);
            this.m = m;
        }

        @Override
        public void visitLabel(Label label) {
            ins.add(new Insn(Insn.LABEL, null, null, null, null, 0, label, null));
        }

        @Override
        public void visitInsn(int opcode) {
            ins.add(new Insn(opcode, null, null, null, null, 0, null, null));
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            ins.add(new Insn(opcode, null, null, null, null, operand, null, null));
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            ins.add(new Insn(opcode, null, null, null, null, var, null, null));
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            m.bits |= typeBits(type);
            ins.add(new Insn(opcode, type, null, null, null, 0, null, null));
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            m.bits |= typeBits(owner) | descBits(descriptor);
            ins.add(new Insn(opcode, owner, name, descriptor, null, 0, null, null));
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            m.bits |= callBits(owner, name, descriptor);
            if (isSite(opcode, owner, name, descriptor)) {
                m.sites++;
            }
            if (isClockCall(owner, name)) {
                clock = true;
            }
            if (!owner.startsWith("[")) {
                m.calls.add(new String[] {owner, name, descriptor});
            }
            ins.add(new Insn(opcode, owner, name, descriptor, null, 0, null, null));
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... args) {
            m.bits |= descBits(descriptor) | handleBits(bsm);
            for (Object a : args) {
                m.bits |= constantBits(a);
                if (a instanceof Handle h && !h.getOwner().startsWith("[")) {
                    m.calls.add(new String[] {h.getOwner(), h.getName(), h.getDesc()});
                }
            }
            ins.add(new Insn(Opcodes.INVOKEDYNAMIC, null, name, descriptor, null, 0, null, null));
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            ins.add(new Insn(opcode, null, null, null, null, 0, label, null));
        }

        @Override
        public void visitLdcInsn(Object value) {
            m.bits |= constantBits(value);
            ins.add(new Insn(Opcodes.LDC, null, null, null, value, 0, null, null));
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            ins.add(new Insn(Opcodes.IINC, null, null, null, null, var, null, null));
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            ins.add(new Insn(Opcodes.TABLESWITCH, null, null, null, null, 0, dflt, labels));
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            ins.add(new Insn(Opcodes.LOOKUPSWITCH, null, null, null, null, 0, dflt, labels));
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            m.bits |= descBits(descriptor);
            ins.add(new Insn(Opcodes.MULTIANEWARRAY, null, null, descriptor, null, numDimensions, null, null));
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            if (type != null) {
                m.bits |= typeBits(type);
            }
        }

        @Override
        public void visitEnd() {
            if (clock) {
                m.bits |= new ClockFlow(ins).run();
            }
            ins = null;
        }
    }

    // ---------------------------------------------------------------- use bits

    static boolean isClockCall(String owner, String name) {
        return GET_TIME_MULT.equals(name) && (ENGINE_API.equals(owner) || ENGINE_IMPL.equals(owner));
    }

    static int typeBits(String type) {
        if (type == null) {
            return 0;
        }
        int i = 0;
        while (i < type.length() && type.charAt(i) == '[') {
            i++;
        }
        String t = type.substring(i);
        if (i > 0 && t.startsWith("L") && t.endsWith(";")) {
            t = t.substring(1, t.length() - 1);
        }
        if (t.equals(KEYBOARD) || t.equals(MOUSE) || t.equals(INPUT_EVENT)) {
            return B_KEYS;
        }
        return t.startsWith(GL_PACKAGE) ? B_GL : 0;
    }

    static int descBits(String d) {
        if (d == null) {
            return 0;
        }
        int b = 0;
        if (d.contains("L" + KEYBOARD + ";") || d.contains("L" + MOUSE + ";") || d.contains("L" + INPUT_EVENT + ";")) {
            b |= B_KEYS;
        }
        if (d.contains("L" + GL_PACKAGE)) {
            b |= B_GL;
        }
        return b;
    }

    static int callBits(String owner, String name, String desc) {
        int b = typeBits(owner) | descBits(desc);
        if (CONTROL_TRANSFER_CALLS.contains(name)) {
            b |= B_CONTROL;
        }
        if (isDrawHelper(owner, name)) {
            b |= B_GL;
        }
        return b;
    }

    /** The libraries' drawing entry points: MagicUI's draw helpers, LazyLib's LazyFont / DrawUtils, GraphicsLib's shader. */
    static boolean isDrawHelper(String owner, String name) {
        if (owner.endsWith("/MagicUI")) {
            return MAGIC_UI_DRAWS.contains(name) || name.endsWith("WithinViewport");
        }
        return owner.startsWith("org/lazywizard/lazylib/ui/LazyFont")
                || owner.equals("org/lazywizard/lazylib/opengl/DrawUtils")
                || owner.equals("org/dark/shaders/post/PostProcessShader");
    }

    /** A method handle as a use; a handle to the engine clock escapes the stat, so it counts as a writer. */
    static int handleBits(Handle h) {
        int b = callBits(h.getOwner(), h.getName(), h.getDesc());
        return isClockCall(h.getOwner(), h.getName()) ? b | B_CLOCK_WRITE : b;
    }

    static int constantBits(Object v) {
        if (v instanceof Handle h) {
            return handleBits(h);
        }
        if (v instanceof Type t) {
            return t.getSort() == Type.METHOD ? descBits(t.getDescriptor()) : typeBits(t.getDescriptor());
        }
        return 0;
    }

    // ---------------------------------------------------------------- IS-A and the one hop

    static boolean isMagicSubsystem(ClassModel c, JarIndex jar) {
        if (MAGIC_SUBSYSTEM.equals(c.name)) {
            return true;
        }
        String s = c.superName;
        for (int depth = 0; s != null && depth < 32; depth++) {
            if (MAGIC_SUBSYSTEM.equals(s)) {
                return true;
            }
            ClassModel p = jar == null ? null : jar.model(s);
            if (p == null) {
                return false;
            }
            s = p.superName;
        }
        return false;
    }

    /**
     * The use bits every same-jar method the class calls reaches (one hop, method-precise); {@code via[k]} names the
     * first callee that set {@link #USE_BITS}{@code [k]}. A call to the class's own declared method is the class rule's.
     */
    static int hop(ClassModel c, JarIndex jar, String[] via) {
        int bits = 0;
        Set<String> done = new HashSet<>();
        for (MethodModel m : c.methods.values()) {
            for (String[] call : m.calls) {
                String key = call[1] + call[2];
                boolean own = call[0].equals(c.name);
                if ((own && c.methods.containsKey(key)) || !done.add(call[0] + '.' + key)) {
                    continue;
                }
                Target t = own ? resolveInherited(jar, c, key) : resolve(jar, call[0], key, 0);
                if (t == null || t.cls().name.equals(c.name)) {
                    continue;
                }
                int r = reach(jar, t) & B_REASONS;
                for (int k = 0; k < USE_BITS.length; k++) {
                    if ((r & USE_BITS[k]) != 0 && via[k] == null) {
                        via[k] = "via " + simple(t.cls().name) + "." + call[1];
                    }
                }
                bits |= r;
            }
        }
        return bits;
    }

    private static Target resolveInherited(JarIndex jar, ClassModel c, String key) {
        Target t = c.superName == null ? null : resolve(jar, c.superName, key, 1);
        for (int i = 0; t == null && i < c.interfaces.length; i++) {
            t = resolve(jar, c.interfaces[i], key, 1);
        }
        return t;
    }

    /** The declaring class of {@code owner.key} inside the jar: the class, its superclasses, then interface defaults. */
    static Target resolve(JarIndex jar, String owner, String key, int depth) {
        ClassModel c = jar.model(owner);
        if (c == null || depth > 32) {
            return null;
        }
        MethodModel m = c.methods.get(key);
        if (m != null) {
            return new Target(c, m);
        }
        Target t = c.superName == null ? null : resolve(jar, c.superName, key, depth + 1);
        if (t != null) {
            return t;
        }
        Target abstractHit = null;
        for (String i : c.interfaces) {
            Target u = resolve(jar, i, key, depth + 1);
            if (u != null && (u.method().access & Opcodes.ACC_ABSTRACT) == 0) {
                return u;
            }
            abstractHit = abstractHit == null ? u : abstractHit;
        }
        return abstractHit;
    }

    /**
     * The method's closure: itself, the methods it calls on its own class hierarchy (resolved), and the declaring
     * class's initializer. A call into another class is a second hop, never followed. Memoised per method.
     */
    static int reach(JarIndex jar, Target t) {
        if (t.method().reach >= 0) {
            return t.method().reach;
        }
        ClassModel d = t.cls();
        int bits = 0;
        int nodes = 0;
        ArrayDeque<Target> q = new ArrayDeque<>();
        Set<MethodModel> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        q.add(t);
        seen.add(t.method());
        MethodModel clinit = d.methods.get("<clinit>()V");
        if (clinit != null && seen.add(clinit)) {
            q.add(new Target(d, clinit));
        }
        while (!q.isEmpty()) {
            Target u = q.poll();
            if (++nodes > MAX_REACH) {
                bits |= d.ownBits;
                break;
            }
            bits |= u.method().bits;
            for (String[] call : u.method().calls) {
                if (!call[0].equals(u.cls().name) && !call[0].equals(d.name)) {
                    continue;
                }
                Target v = resolve(jar, call[0], call[1] + call[2], 0);
                if (v != null && seen.add(v.method())) {
                    q.add(v);
                }
            }
        }
        t.method().reach = bits;
        return bits;
    }

    // ---------------------------------------------------------------- the clock walk

    /** One recorded instruction; {@link #LABEL} marks a label position. */
    static final class Insn {
        static final int LABEL = -1;
        final int op;
        final String owner;
        final String name;
        final String desc;
        final Object cst;
        final int var;
        final Label label;
        final Label[] targets;

        Insn(int op, String owner, String name, String desc, Object cst, int var, Label label, Label[] targets) {
            this.op = op;
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.cst = cst;
            this.var = var;
            this.label = label;
            this.targets = targets;
        }
    }

    /**
     * Follows each {@code getTimeMult()} result on a value-level operand-stack model (one entry per value, with its
     * category) from the call to its consumer, forking at branches while the value is live and re-starting at every
     * {@code ALOAD} whose nearest earlier store to that slot stored it. Returns {@link #B_CLOCK_WRITE} when any
     * consumer writes (or cannot be modelled), {@link #B_CLOCK_READ} when the clock is only read.
     */
    static final class ClockFlow {
        static final int MAX_STEPS = 20_000;
        static final int MAX_PATHS = 256;
        private static final int HARMLESS = 0;
        private static final int READ = 1;
        private static final int WRITE = 2;
        private static final int PASS = 3;

        record Entry(int cat, boolean taint) {
        }

        private static final Entry C1 = new Entry(1, false);
        private static final Entry C2 = new Entry(2, false);
        private static final Entry TAINT = new Entry(1, true);

        private final List<Insn> ins;
        private final Map<Label, Integer> at = new HashMap<>();
        private final Set<Integer> taintedStores = new HashSet<>();
        private final Set<String> seen = new HashSet<>();
        private final ArrayDeque<Object[]> work = new ArrayDeque<>();
        private int steps;
        private int paths;
        private boolean overflow;

        ClockFlow(List<Insn> ins) {
            this.ins = ins;
            for (int i = 0; i < ins.size(); i++) {
                if (ins.get(i).op == Insn.LABEL) {
                    at.put(ins.get(i).label, i);
                }
            }
        }

        int run() {
            boolean any = false;
            for (int i = 0; i < ins.size(); i++) {
                Insn x = ins.get(i);
                if (isInvoke(x.op) && isClockCall(x.owner, x.name)) {
                    any = true;
                    fork(i + 1, List.of(TAINT));
                }
            }
            Set<Integer> origins = new HashSet<>();
            while (!work.isEmpty()) {
                while (!work.isEmpty()) {
                    Object[] w = work.poll();
                    @SuppressWarnings("unchecked")
                    List<Entry> st = (List<Entry>) w[1];
                    if (walk((Integer) w[0], st)) {
                        return B_CLOCK_WRITE;
                    }
                }
                for (int a = 0; a < ins.size(); a++) {
                    Insn x = ins.get(a);
                    if (x.op == Opcodes.ALOAD && !origins.contains(a) && taintedStores.contains(lastStore(a, x.var))) {
                        origins.add(a);
                        fork(a + 1, List.of(TAINT));
                    }
                }
            }
            if (overflow) {
                return B_CLOCK_WRITE;
            }
            return any ? B_CLOCK_READ : 0;
        }

        private void fork(int index, List<Entry> st) {
            if (++paths > MAX_PATHS) {
                overflow = true;
                return;
            }
            work.add(new Object[] {index, new ArrayList<>(st)});
        }

        private int lastStore(int a, int var) {
            for (int i = a - 1; i >= 0; i--) {
                Insn x = ins.get(i);
                if (x.op >= Opcodes.ISTORE && x.op <= Opcodes.ASTORE && x.var == var) {
                    return i;
                }
            }
            return -1;
        }

        /** One path from {@code i}; true = a writer (or an unmodelled shape) was found. */
        private boolean walk(int i, List<Entry> st) {
            while (i < ins.size()) {
                if (++steps > MAX_STEPS || overflow) {
                    return true;
                }
                Insn x = ins.get(i);
                int op = x.op;
                if (op == Insn.LABEL) {
                    if (!seen.add(i + ":" + st)) {
                        return false;
                    }
                    i++;
                    continue;
                }
                switch (op) {
                    case Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP_X2, Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2,
                            Opcodes.SWAP -> {
                        if (!shuffle(op, st)) {
                            return true;
                        }
                    }
                    case Opcodes.POP2 -> pop(st, !st.isEmpty() && st.get(st.size() - 1).cat() == 2 ? 1 : 2);
                    case Opcodes.GOTO -> {
                        Integer to = at.get(x.label);
                        if (to == null) {
                            return true;
                        }
                        i = to;
                        continue;
                    }
                    case Opcodes.JSR, Opcodes.RET -> {
                        return true;
                    }
                    case Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> {
                        pop(st, 1);
                        if (hasTaint(st)) {
                            for (Label l : x.targets) {
                                if (!forkAt(l, st)) {
                                    return true;
                                }
                            }
                            return !forkAt(x.label, st);
                        }
                        return false;
                    }
                    case Opcodes.RETURN -> {
                        return false;
                    }
                    case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN,
                            Opcodes.ATHROW -> {
                        return anyTaint(pop(st, 1));
                    }
                    default -> {
                        Entry[] popped = pop(st, pops(x));
                        if (anyTaint(popped)) {
                            int c = consume(x, popped);
                            if (c == WRITE) {
                                return true;
                            }
                            if (c == PASS) {
                                st.add(TAINT);
                                i++;
                                continue;
                            }
                            if (op == Opcodes.ASTORE) {
                                taintedStores.add(i);
                            }
                        }
                        int push = pushCat(x);
                        if (push > 0) {
                            st.add(push == 2 ? C2 : C1);
                        }
                        if (isConditionalJump(op) && hasTaint(st) && !forkAt(x.label, st)) {
                            return true;
                        }
                    }
                }
                if (!hasTaint(st)) {
                    return false;
                }
                i++;
            }
            return false;
        }

        private boolean forkAt(Label l, List<Entry> st) {
            Integer to = l == null ? null : at.get(l);
            if (to == null) {
                return false;
            }
            fork(to, st);
            return true;
        }

        /** What consuming a live clock value does: a harmless use, a read, a writer, or a pass-through (a cast). */
        private static int consume(Insn x, Entry[] p) {
            switch (x.op) {
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL -> {
                    for (int k = 1; k < p.length; k++) {
                        if (p[k] != null && p[k].taint()) {
                            return WRITE;
                        }
                    }
                    String n = x.name;
                    if (n.startsWith("modify") || n.startsWith("unmodify")) {
                        return WRITE;
                    }
                    boolean getter = n.startsWith("get") || n.startsWith("is");
                    return getter && primitive(Type.getReturnType(x.desc)) ? READ : WRITE;
                }
                case Opcodes.INVOKESTATIC -> {
                    return KOTLIN_INTRINSICS.equals(x.owner) && (x.name.startsWith("check") || x.name.equals("areEqual"))
                            ? HARMLESS : WRITE;
                }
                case Opcodes.CHECKCAST -> {
                    return PASS;
                }
                case Opcodes.GETFIELD -> {
                    return primitive(Type.getType(x.desc)) ? READ : WRITE;
                }
                case Opcodes.POP, Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
                        Opcodes.INSTANCEOF, Opcodes.ASTORE, Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> {
                    return HARMLESS;
                }
                default -> {
                    return WRITE;
                }
            }
        }

        private static boolean primitive(Type t) {
            return t.getSort() >= Type.BOOLEAN && t.getSort() <= Type.DOUBLE;
        }

        /** Pops {@code n} entries, bottom to top; entries below the modelled part read as {@code null}. */
        private static Entry[] pop(List<Entry> st, int n) {
            Entry[] out = new Entry[n];
            for (int k = n - 1; k >= 0; k--) {
                out[k] = st.isEmpty() ? null : st.remove(st.size() - 1);
            }
            return out;
        }

        private static boolean anyTaint(Entry[] e) {
            for (Entry x : e) {
                if (x != null && x.taint()) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasTaint(List<Entry> st) {
            for (Entry x : st) {
                if (x.taint()) {
                    return true;
                }
            }
            return false;
        }

        /** The DUP / SWAP family on modelled entries; false = it reaches below the model (fail-safe: a writer). */
        private static boolean shuffle(int op, List<Entry> st) {
            int n = st.size();
            if (n < 1) {
                return false;
            }
            Entry v1 = st.get(n - 1);
            switch (op) {
                case Opcodes.DUP -> st.add(v1);
                case Opcodes.SWAP -> {
                    if (n < 2) {
                        return false;
                    }
                    Collections.swap(st, n - 1, n - 2);
                }
                case Opcodes.DUP_X1 -> {
                    if (n < 2) {
                        return false;
                    }
                    st.add(n - 2, v1);
                }
                case Opcodes.DUP_X2 -> {
                    if (n < 2) {
                        return false;
                    }
                    if (st.get(n - 2).cat() == 2) {
                        st.add(n - 2, v1);
                    } else if (n < 3) {
                        return false;
                    } else {
                        st.add(n - 3, v1);
                    }
                }
                case Opcodes.DUP2 -> {
                    if (v1.cat() == 2) {
                        st.add(v1);
                    } else if (n < 2) {
                        return false;
                    } else {
                        Entry v2 = st.get(n - 2);
                        st.add(v2);
                        st.add(v1);
                    }
                }
                case Opcodes.DUP2_X1 -> {
                    if (v1.cat() == 2) {
                        if (n < 2) {
                            return false;
                        }
                        st.add(n - 2, v1);
                    } else {
                        if (n < 3) {
                            return false;
                        }
                        Entry v2 = st.get(n - 2);
                        st.add(n - 3, v2);
                        st.add(n - 2, v1);
                    }
                }
                default -> { // DUP2_X2
                    if (v1.cat() == 2) {
                        if (n < 2) {
                            return false;
                        }
                        if (st.get(n - 2).cat() == 2) {
                            st.add(n - 2, v1);
                        } else if (n < 3) {
                            return false;
                        } else {
                            st.add(n - 3, v1);
                        }
                    } else {
                        if (n < 3) {
                            return false;
                        }
                        Entry v2 = st.get(n - 2);
                        int at = st.get(n - 3).cat() == 2 ? n - 3 : n - 4;
                        if (at < 0) {
                            return false;
                        }
                        st.add(at, v2);
                        st.add(at + 1, v1);
                    }
                }
            }
            return true;
        }

        static boolean isInvoke(int op) {
            return op >= Opcodes.INVOKEVIRTUAL && op <= Opcodes.INVOKEDYNAMIC;
        }

        static boolean isConditionalJump(int op) {
            return (op >= Opcodes.IFEQ && op <= Opcodes.IF_ACMPNE) || op == Opcodes.IFNULL || op == Opcodes.IFNONNULL;
        }

        /** Values (not slots) an instruction pops; the DUP family, POP2, jumps and returns are handled by the walk. */
        static int pops(Insn x) {
            int op = x.op;
            if (op >= Opcodes.IALOAD && op <= Opcodes.SALOAD) {
                return 2;
            }
            if (op >= Opcodes.ISTORE && op <= Opcodes.ASTORE) {
                return 1;
            }
            if (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE) {
                return 3;
            }
            if ((op >= Opcodes.IADD && op <= Opcodes.DREM) || (op >= Opcodes.ISHL && op <= Opcodes.LXOR)
                    || (op >= Opcodes.LCMP && op <= Opcodes.DCMPG)) {
                return 2;
            }
            if ((op >= Opcodes.INEG && op <= Opcodes.DNEG) || (op >= Opcodes.I2L && op <= Opcodes.I2S)) {
                return 1;
            }
            if (op >= Opcodes.IFEQ && op <= Opcodes.IFLE) {
                return 1;
            }
            if (op >= Opcodes.IF_ICMPEQ && op <= Opcodes.IF_ACMPNE) {
                return 2;
            }
            switch (op) {
                case Opcodes.POP, Opcodes.PUTSTATIC, Opcodes.GETFIELD, Opcodes.NEWARRAY, Opcodes.ANEWARRAY,
                        Opcodes.ARRAYLENGTH, Opcodes.CHECKCAST, Opcodes.INSTANCEOF, Opcodes.MONITORENTER,
                        Opcodes.MONITOREXIT, Opcodes.IFNULL, Opcodes.IFNONNULL -> {
                    return 1;
                }
                case Opcodes.PUTFIELD -> {
                    return 2;
                }
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKEINTERFACE -> {
                    return Type.getArgumentCount(x.desc) + 1;
                }
                case Opcodes.INVOKESTATIC, Opcodes.INVOKEDYNAMIC -> {
                    return Type.getArgumentCount(x.desc);
                }
                case Opcodes.MULTIANEWARRAY -> {
                    return x.var;
                }
                default -> {
                    return 0;
                }
            }
        }

        /** The category of the value an instruction pushes; 0 = it pushes none. */
        static int pushCat(Insn x) {
            int op = x.op;
            switch (op) {
                case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1, Opcodes.LLOAD,
                        Opcodes.DLOAD, Opcodes.LALOAD, Opcodes.DALOAD, Opcodes.I2L, Opcodes.I2D, Opcodes.L2D,
                        Opcodes.F2L, Opcodes.F2D, Opcodes.D2L -> {
                    return 2;
                }
                case Opcodes.LDC -> {
                    return x.cst instanceof Long || x.cst instanceof Double ? 2 : 1;
                }
                case Opcodes.GETSTATIC, Opcodes.GETFIELD -> {
                    return Type.getType(x.desc).getSize();
                }
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE,
                        Opcodes.INVOKEDYNAMIC -> {
                    return Type.getReturnType(x.desc).getSize();
                }
                default -> {
                    if ((op >= Opcodes.ACONST_NULL && op <= Opcodes.FCONST_2) || op == Opcodes.BIPUSH
                            || op == Opcodes.SIPUSH || op == Opcodes.ILOAD || op == Opcodes.FLOAD
                            || op == Opcodes.ALOAD) {
                        return 1;
                    }
                    if (op >= Opcodes.IALOAD && op <= Opcodes.SALOAD) {
                        return 1;
                    }
                    if (op >= Opcodes.IADD && op <= Opcodes.DNEG) {
                        return (op - Opcodes.IADD) % 2 == 1 ? 2 : 1;
                    }
                    if (op >= Opcodes.ISHL && op <= Opcodes.LXOR) {
                        return (op - Opcodes.ISHL) % 2 == 1 ? 2 : 1;
                    }
                    if ((op >= Opcodes.I2L && op <= Opcodes.DCMPG) || op == Opcodes.NEW || op == Opcodes.NEWARRAY
                            || op == Opcodes.ANEWARRAY || op == Opcodes.ARRAYLENGTH || op == Opcodes.CHECKCAST
                            || op == Opcodes.INSTANCEOF || op == Opcodes.MULTIANEWARRAY) {
                        return 1;
                    }
                    return 0;
                }
            }
        }
    }

    // ---------------------------------------------------------------- the per-jar index

    /**
     * A code source's classes by internal name, for the one hop and the IS-A chain: a jar opened lazily on the first
     * lookup (and kept open; the defining loader holds it open anyway), a directory of class files, or an in-memory
     * map. Parsed class models are memoised per index. Thread-safe (every lookup locks the index).
     */
    public static final class JarIndex {
        private static final Map<String, JarIndex> MEMO = new HashMap<>();
        private static final AtomicInteger ERRORS = new AtomicInteger();
        static final int MAX_MEMO = 512;
        static final int MAX_MODELS = 16_384;
        static final int MAX_CLASS_BYTES = 16 << 20;

        private final Path path;
        private final Map<String, byte[]> fixed;
        private ZipFile zip;
        private boolean opened;
        private final Map<String, ClassModel> models = new HashMap<>();

        private JarIndex(Path path, Map<String, byte[]> fixed) {
            this.path = path;
            this.fixed = fixed;
        }

        /**
         * The memoised index of a code-source location (a path or a {@code file:} / {@code jar:} URL); {@code null}
         * for a null, blank or unparseable location (no hop). Nothing is read until the first lookup.
         */
        public static JarIndex forLocation(String pathOrNull) {
            Path p = toPath(pathOrNull);
            if (p == null) {
                return null;
            }
            String key = p.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
            synchronized (MEMO) {
                JarIndex j = MEMO.get(key);
                if (j == null) {
                    j = new JarIndex(p, null);
                    if (MEMO.size() < MAX_MEMO) {
                        MEMO.put(key, j);
                    }
                }
                return j;
            }
        }

        /** An in-memory index (fixtures, tests): internal name to class bytes. */
        public static JarIndex ofClasses(Map<String, byte[]> classes) {
            return new JarIndex(null, Map.copyOf(classes));
        }

        /** Lookups that failed on I/O or a malformed class since start (each counted once here, never thrown). */
        public static int errorCount() {
            return ERRORS.get();
        }

        /** Closes every memoised index's jar and forgets it (a later lookup re-opens). */
        public static void closeAll() {
            synchronized (MEMO) {
                for (JarIndex j : MEMO.values()) {
                    j.close();
                }
                MEMO.clear();
            }
        }

        /** Closes this index's jar handle and drops its models. */
        public synchronized void close() {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException e) {
                    ERRORS.incrementAndGet();
                }
            }
            zip = null;
            opened = false;
            models.clear();
        }

        /** The class bytes, or {@code null} when absent, unreadable or larger than 16 MB. */
        public synchronized byte[] bytes(String internalName) {
            if (fixed != null) {
                return fixed.get(internalName);
            }
            String entry = internalName + ".class";
            try {
                if (Files.isDirectory(path)) {
                    Path f = path.resolve(entry);
                    return Files.isRegularFile(f) && Files.size(f) <= MAX_CLASS_BYTES ? Files.readAllBytes(f) : null;
                }
                if (!opened) {
                    opened = true;
                    zip = new ZipFile(path.toFile());
                }
                ZipEntry e = zip == null ? null : zip.getEntry(entry);
                if (e == null) {
                    return null;
                }
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] b = in.readNBytes(MAX_CLASS_BYTES + 1);
                    return b.length > MAX_CLASS_BYTES ? null : b;
                }
            } catch (IOException | RuntimeException e) {
                ERRORS.incrementAndGet();
                return null;
            }
        }

        /** The memoised model of a class in this index; {@code null} when absent or malformed. */
        synchronized ClassModel model(String internalName) {
            if (models.containsKey(internalName)) {
                return models.get(internalName);
            }
            byte[] b = bytes(internalName);
            ClassModel m = null;
            if (b != null) {
                try {
                    m = PilotShipClassifier.model(b);
                } catch (RuntimeException e) {
                    ERRORS.incrementAndGet();
                }
            }
            if (models.size() < MAX_MODELS) {
                models.put(internalName, m);
            }
            return m;
        }

        private static Path toPath(String loc) {
            if (loc == null || loc.isBlank()) {
                return null;
            }
            try {
                String s = loc.trim();
                if (s.startsWith("jar:") || s.startsWith("file:")) {
                    String plain = decodeLocation(s);
                    try {
                        return Paths.get(plain);
                    } catch (RuntimeException e) {
                        String u = s.startsWith("jar:") ? s.substring(4) : s;
                        int bang = u.indexOf("!/");
                        return Paths.get(new URI(bang >= 0 ? u.substring(0, bang) : u));
                    }
                }
                return Paths.get(s);
            } catch (Exception e) {
                ERRORS.incrementAndGet();
                return null;
            }
        }
    }

    // ---------------------------------------------------------------- raw constant pool

    /**
     * Start offset of every constant-pool entry by index (0 and a long / double's second slot = -1), with the pool's
     * end offset at {@code [count]}; {@code null} for a truncated pool or an unknown tag.
     */
    static int[] poolOffsets(byte[] b) {
        try {
            int count = u2(b, 8);
            int[] off = new int[count + 1];
            off[0] = -1;
            int p = 10;
            for (int i = 1; i < count; i++) {
                off[i] = p;
                int tag = b[p] & 0xff;
                int size = switch (tag) {
                    case 1 -> 3 + u2(b, p + 1);
                    case 7, 8, 16, 19, 20 -> 3;
                    case 15 -> 4;
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> 5;
                    case 5, 6 -> 9;
                    default -> -1;
                };
                if (size < 0 || p + size > b.length) {
                    return null;
                }
                p += size;
                if (tag == 5 || tag == 6) {
                    off[++i] = -1;
                }
            }
            off[count] = p;
            return off;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int u2(byte[] b, int p) {
        return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
    }

    /** True when the Utf8 entry at {@code off} equals {@code want} (ASCII). */
    static boolean utf8Equals(byte[] b, int off, byte[] want) {
        int len = u2(b, off + 1);
        if (len != want.length) {
            return false;
        }
        for (int k = 0; k < len; k++) {
            if (b[off + 3 + k] != want[k]) {
                return false;
            }
        }
        return true;
    }

    private static boolean poolHasMark(byte[] b) {
        int[] off = poolOffsets(b);
        if (off == null) {
            return false;
        }
        for (int i = 1; i < off.length - 1; i++) {
            if (off[i] <= 0 || b[off[i]] != 1) {
                continue;
            }
            int len = u2(b, off[i] + 1);
            int start = off[i] + 3;
            for (int s = start; s + MARK_BYTES.length <= start + len; s++) {
                int k = 0;
                while (k < MARK_BYTES.length && b[s + k] == MARK_BYTES[k]) {
                    k++;
                }
                if (k == MARK_BYTES.length) {
                    return true;
                }
            }
        }
        return false;
    }

    static String simple(String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    /** Printable ASCII, at most {@link #MAX_DETAIL} chars; {@code null} reads as empty. */
    static String bounded(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(s.length(), MAX_DETAIL));
        for (int i = 0; i < s.length() && sb.length() < MAX_DETAIL; i++) {
            char ch = s.charAt(i);
            sb.append(ch >= 0x20 && ch < 0x7f ? ch : '?');
        }
        return sb.toString();
    }
}
