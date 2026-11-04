package cc.starsector.client;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The engine link probe: at application load, BEFORE any seam touches the engine's internal classes, prove every
 * class and member the engine map names resolves on the engine build this game is running — or stop the game with
 * the engine's own Fatal dialog.
 *
 * <p>WHY: the Linux download of the game is a separate build of the engine whose internal classes carry different
 * names. The kit's compat agent renames the mod's compiled references at class load and
 * {@link EngineNames} renames the by-name lookups; if either is missing (the game started without the kit's
 * launcher, an unknown engine build, a transform that failed) the mod would run half-linked — a multiplayer client
 * that desyncs instead of failing. Every seam's own {@code catch} would degrade it silently, so this probe runs
 * FIRST and refuses the start instead.
 *
 * <p>The pass: latch the agent's receipt ({@code cc.engine.map}) into
 * {@link EngineNames}; load every class row ({@code Class.forName(name, false, loader)}: no static initializer
 * runs); look up every member row by its KIND — {@code findStatic} / {@code findVirtual} / {@code findStaticGetter}
 * / {@code findGetter} through a private lookup on the owner, so an access rule never reads as a missing member and
 * a static member looked up as an instance one (or the reverse) is a miss; force-define every class the agent
 * rewrites (no initializer: the transform runs at define, so its failure surfaces here, never mid-combat); then read
 * the agent's {@code cc.engine.map.errors}. A member's type comes from this build's descriptor: a method through
 * {@code MethodType.fromMethodDescriptorString(desc, loader)}, a field through the same parser over {@code "()" +
 * desc} (a bare field descriptor is not a method descriptor and throws — which would refuse every start on every
 * OS). On Windows every spelling is the identity, so the same lookups are a free tell that a game update moved a
 * name. B-safe: {@code java.lang} and {@code java.lang.invoke} only.
 *
 * <p>The forced define loads only each rewritten class and its supertypes (all are the engine's own or the
 * public API's today); a rewritten class whose supertype came from an optional mod would turn that mod's absence
 * into a refused start, so such a class must never join the map's rewritten list.
 */
public final class EngineLinkProbe {
    private EngineLinkProbe() {}

    /** The agent's receipt property: {@code identity}, a map id, or {@code unknown:<hex>}; unset without the agent. */
    static final String RECEIPT_PROP = "cc.engine.map";
    /** The agent's per-class transform failures ({@code <class>:<Kind>} rows); unset or empty when none. */
    static final String ERRORS_PROP = "cc.engine.map.errors";
    /** Misses named in the one log row; the count beside it is always exact. */
    private static final int MAX_MISSES_NAMED = 8;

    /** One pass's tally. Package-private for the build-time self-test. */
    static final class Result {
        final int rows;
        final int defined;
        final int misses;
        final String named;

        Result(int rows, int defined, int misses, String named) {
            this.rows = rows;
            this.defined = defined;
            this.misses = misses;
            this.named = named;
        }
    }

    /**
     * THE FIRST STATEMENT of {@code onApplicationLoad}. All rows resolve and the agent reported no error ⇒ one
     * sidelog row {@code engine-map: <receipt> probe=ok rows=<n> defined=<n>}; else one row naming the misses, then
     * an {@link IllegalStateException} the engine shows as its Fatal dialog (a half-linked client never plays).
     */
    public static void verifyAtApplicationLoad() {
        EngineNames.latch(System.getProperty(RECEIPT_PROP));
        String receipt = FallbackAnnounce.sanitize(EngineNames.receipt(), 48);
        Result r = run(EngineLinkProbe.class.getClassLoader(), EngineNames.mapped(), true);
        String errors = System.getProperty(ERRORS_PROP);
        boolean agentClean = errors == null || errors.isEmpty();
        if (r.misses == 0 && agentClean) {
            PilotWatchLog.info(okRow(receipt, r));
            return;
        }
        PilotWatchLog.warn("engine-map: " + receipt + " probe=FAIL rows=" + r.rows + " defined=" + r.defined
                + " misses=" + r.misses + (r.named.isEmpty() ? "" : " [" + r.named + "]")
                + " agent-errors=" + (agentClean ? "none" : FallbackAnnounce.sanitize(errors, 240)));
        throw new IllegalStateException(playerText(receipt));
    }

    /** The success row; the build-time self-test greps the same text. */
    static String okRow(String receipt, Result r) {
        return "engine-map: " + receipt + " probe=ok rows=" + r.rows + " defined=" + r.defined;
    }

    /** The Fatal dialog's text (the engine prefixes it with "Fatal: "). */
    static String playerText(String receipt) {
        return "AlphaCoreMP: the mod was linked against a different build of the game engine (engine map: " + receipt
                + "). Start the game with the kit's launcher - join-cc.bat on Windows, bash join-cc.sh on Linux -"
                + " never the game's own launcher. If it already was, this kit does not fit this game version.";
    }

    /**
     * One pass over the table: every class row, every member row, then (when {@code define}) every rewritten class.
     * {@code map} picks the spelling ({@link EngineNames#clsFor}); in game it is the latched choice. Never throws: a
     * row's failure of any kind is a miss.
     */
    static Result run(ClassLoader loader, boolean map, boolean define) {
        int rows = 0;
        int defined = 0;
        int misses = 0;
        StringBuilder named = new StringBuilder();
        String[] classes = EngineNameTable.CLASSES;
        for (int i = 0; i < classes.length; i += EngineNameTable.CLASS_STRIDE) {
            rows++;
            try {
                Class.forName(EngineNames.clsFor(classes[i], map), false, loader);
            } catch (Throwable t) {
                misses = miss(named, misses, "class " + classes[i], t);
            }
        }
        String[] m = EngineNameTable.MEMBERS;
        for (int i = 0; i < m.length; i += EngineNameTable.MEMBER_STRIDE) {
            rows++;
            try {
                lookup(loader, m, i, map);
            } catch (Throwable t) {
                misses = miss(named, misses, m[i + EngineNameTable.M_KIND] + " " + m[i + EngineNameTable.M_OWNER]
                        + "." + m[i + EngineNameTable.M_NAME], t);
            }
        }
        if (define) {
            for (String n : EngineNameTable.REMAPPED) {
                try {
                    Class.forName(n, false, loader);
                    defined++;
                } catch (Throwable t) {
                    misses = miss(named, misses, "define " + n, t);
                }
            }
        }
        return new Result(rows, defined, misses, named.toString());
    }

    /** Resolve one member row through the owner's private lookup, dispatched on its KIND. */
    private static void lookup(ClassLoader loader, String[] m, int i, boolean map) throws ReflectiveOperationException {
        String kind = m[i + EngineNameTable.M_KIND];
        String winOwner = m[i + EngineNameTable.M_OWNER];
        String winName = m[i + EngineNameTable.M_NAME];
        String winDesc = m[i + EngineNameTable.M_WIN_DESC];
        Class<?> owner = Class.forName(EngineNames.clsFor(winOwner, map), false, loader);
        String name = EngineNames.memberFor(winOwner, winName, winDesc, map);
        String desc = EngineNames.descFor(winOwner, winName, winDesc, map);
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
        switch (kind) {
            case "static-method":
                lookup.findStatic(owner, name, MethodType.fromMethodDescriptorString(desc, loader));
                return;
            case "method":
                lookup.findVirtual(owner, name, MethodType.fromMethodDescriptorString(desc, loader));
                return;
            case "static-field":
                lookup.findStaticGetter(owner, name, fieldType(desc, loader));
                return;
            case "field":
                lookup.findGetter(owner, name, fieldType(desc, loader));
                return;
            default:
                throw new IllegalArgumentException("unknown member kind");
        }
    }

    /** A field descriptor's type: the method parser over {@code "()" + desc}, its return type. */
    static Class<?> fieldType(String desc, ClassLoader loader) {
        return MethodType.fromMethodDescriptorString("()" + desc, loader).returnType();
    }

    /** Count one miss; name the first {@link #MAX_MISSES_NAMED} as bounded ASCII tokens. */
    private static int miss(StringBuilder named, int misses, String what, Throwable t) {
        if (misses < MAX_MISSES_NAMED) {
            if (named.length() > 0) {
                named.append("; ");
            }
            named.append(FallbackAnnounce.sanitize(what, 120)).append(':').append(t.getClass().getSimpleName());
        }
        return misses + 1;
    }

    /**
     * {@code --selftest}: the build runs it on the built mod jar + the Windows game jars. Row 1 = the in-game pass
     * on this classpath (no agent ⇒ identity), printed as the in-game row; row 2 = the CONTROL, the same lookups in
     * the other spelling, which must miss (a probe that looks nothing up cannot pass it). Exit 0 = both hold.
     */
    public static void main(String[] args) {
        if (args.length != 1 || !"--selftest".equals(args[0])) {
            System.out.println("usage: EngineLinkProbe --selftest");
            System.exit(2);
            return;
        }
        ClassLoader loader = EngineLinkProbe.class.getClassLoader();
        EngineNames.latch(System.getProperty(RECEIPT_PROP));
        String receipt = FallbackAnnounce.sanitize(EngineNames.receipt(), 48);
        boolean map = EngineNames.mapped();
        Result r = run(loader, map, true);
        String errors = System.getProperty(ERRORS_PROP);
        boolean ok = true;
        if (r.misses == 0 && (errors == null || errors.isEmpty())) {
            System.out.println(okRow(receipt, r));
        } else {
            ok = false;
            System.out.println("FAIL engine-link-probe: receipt=" + receipt + " rows=" + r.rows + " defined=" + r.defined
                    + " misses=" + r.misses + " [" + r.named + "] - flips if a table row, a member KIND, the field-type"
                    + " parse or a rewritten class name no longer matches the engine and mod jars on this classpath");
        }
        Result control = run(loader, !map, false);
        if (control.misses > 0) {
            System.out.println("control: the " + (map ? "identity" : EngineNameTable.MAP_ID) + " spelling misses="
                    + control.misses + " of rows=" + control.rows + " (expected > 0)");
        } else {
            ok = false;
            System.out.println("FAIL engine-link-probe-control: every row resolved in the other spelling too - flips if"
                    + " the probe stops looking names up or the map stops translating them");
        }
        System.exit(ok ? 0 : 1);
    }
}
