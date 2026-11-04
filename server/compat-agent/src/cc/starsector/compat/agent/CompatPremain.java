package cc.starsector.compat.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The kit's own {@code -javaagent} (cc-compat-agent.jar): on the game's start it registers the MagicLib seam
 * transformer, so THIS client's MagicLib subsystem class carries the same one-call patch the serve's premain applies
 * and the client mod can report the replica bit in its HELLO.
 *
 * <p>System-path discipline: this class and {@link CompatAgentLoader} are the ONLY classes on the system class path.
 * The transformer, the rewriter and ASM sit under {@code META-INF/cc-compat/classes/} in this jar and are defined by
 * the private child-first loader; this unit compiles against the JDK alone, so a reference to ASM or the rewriter is
 * a compile error here. WHY: a player's own agent on another start carries an unrelocated ASM, which a parent-first
 * lookup would link instead of ours.
 *
 * <p>Self-gate (inert unless BOTH hold): the JVM's main class is {@value #LAUNCHER} (the first token of
 * {@code sun.java.command}) and the {@value #KIT_LAUNCH_SYSPROP} sysprop is present. Inert = exactly ONE stderr row
 * {@code [cc-compat-agent] inert reason=<token>}, no transformer, no receipt. Armed = {@value #RECEIPT_SYSPROP} set to
 * {@value #RECEIPT_ARMED} before the transformer is registered, so before any define. A setup failure after the gate
 * = {@code disarmed:error:<SimpleName>} + ONE stdout row, and the game starts unpatched.
 *
 * <p>Reads nothing beyond those two sysprops, its own agent argument, its own jar's entries and (the engine-name
 * remap's arming, off the Windows build only) the bytes of the game's engine jar to hash them (no other file, no
 * socket, env or player text); every row is a bounded token. Every path sits in {@code catch (Throwable)}: an exception escaping
 * a premain aborts the JVM, and this agent only ever buys presentation, never a start.
 *
 * <p>The second transformer (the replica pilot pass, {@value #PILOT_TRANSFORMER}): registered behind the SAME private
 * loader after the MagicLib one, with its own receipt {@value #PILOT_RECEIPT_SYSPROP}: {@value #PILOT_RECEIPT_OFF}
 * when the agent argument carries {@value #PILOT_OFF_ARG} (the joiner's switch: no pilot transformer, every mod
 * class loads as shipped), {@value #RECEIPT_ARMED} before it is registered, {@code disarmed:error:<SimpleName>} + ONE
 * stdout row when its setup fails. Each transformer arms or fails on its own: a failure of the second never disarms
 * the first. A load-time VerifyError in a rewritten class cannot be caught here; the switch is its recovery.
 *
 * <p>The engine-name remap ({@value #ENGINE_MAP_TRANSFORMER}) is armed FIRST, before the other two, so neither the
 * pilot switch's early return nor a failure of either can skip it: on a separate build of the engine (the Linux
 * download) the client mod links only through it. Its own receipt {@value #ENGINE_MAP_RECEIPT_SYSPROP} = what the
 * transformer's arming decided ({@value #ENGINE_MAP_IDENTITY} on the Windows build: nothing registered, no row; a map
 * id: registered + ONE stdout row {@code enginemap armed map=<id>}; anything else: not registered + ONE stdout row
 * {@code enginemap unarmed state=<receipt>}), or {@code disarmed:error:<SimpleName>} + ONE stdout row when its setup
 * fails (the loader's failure included). The receipt is set BEFORE the transformer is registered, so before any
 * define, and the transformer itself never writes it.
 *
 * <p>The second transformer: the engine-name
 * remap:
 */
public final class CompatPremain {

    /** The game's main class; the agent is inert under any other. */
    static final String LAUNCHER = "com.fs.starfarer.StarfarerLauncher";
    /** The kit launcher's marker sysprop; the agent is inert without it. */
    static final String KIT_LAUNCH_SYSPROP = "cc.kit.launch";
    /** The receipt the client mod reads (its applied-prefix check); spelled here, never linked from the rewriter. */
    static final String RECEIPT_SYSPROP = "cc.magic.seam";
    /** The receipt between the gate and the first define. */
    static final String RECEIPT_ARMED = "armed";
    /** The transformer's binary name, resolved through the private loader only. */
    static final String TRANSFORMER = "cc.starsector.compat.magic.MagicSeamAgentTransformer";
    /** The replica pilot pass's receipt; spelled here, never linked from its transformer. */
    static final String PILOT_RECEIPT_SYSPROP = "cc.pilot.replica";
    /** The pilot receipt when the joiner's switch is set. */
    static final String PILOT_RECEIPT_OFF = "off";
    /** The agent argument token that keeps the pilot pass unregistered (comma-separated tokens). */
    static final String PILOT_OFF_ARG = "pilot=off";
    /** The second transformer's binary name, resolved through the same private loader only. */
    static final String PILOT_TRANSFORMER = "cc.starsector.compat.pilotship.ReplicaPilotAgentTransformer";
    /** The engine-name remap's receipt (the client mod's link probe latches it); spelled here, never linked. */
    static final String ENGINE_MAP_RECEIPT_SYSPROP = "cc.engine.map";
    /** The engine-name remap's receipt on the Windows build (no transformer, no row). */
    static final String ENGINE_MAP_IDENTITY = "identity";
    /** The engine-name remap's binary name, resolved through the same private loader only. */
    static final String ENGINE_MAP_TRANSFORMER = "cc.starsector.compat.enginemap.EngineMapAgentTransformer";
    private static final String ROW = "[cc-compat-agent] ";

    private CompatPremain() {
    }

    /** The JVM's premain entry: the gate, then the transformers behind the private loader; never throws. */
    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            String inert = gate();
            if (inert != null) {
                System.err.println(ROW + "inert reason=" + inert);
                return;
            }
            arm(inst, pilotOff(agentArgs));
        } catch (Throwable t) {
            try {
                System.err.println(ROW + "inert reason=error:" + kind(t));
            } catch (Throwable ignored) {
                // nothing may escape a premain
            }
        }
    }

    /** {@code null} = armed; else the inert reason token ({@code not_launcher | no_kit_launch | error:<SimpleName>}). */
    static String gate() {
        try {
            String command = System.getProperty("sun.java.command");
            String main = command == null ? "" : command.trim();
            int space = main.indexOf(' ');
            if (!(space < 0 ? main : main.substring(0, space)).equals(LAUNCHER)) {
                return "not_launcher";
            }
            return System.getProperty(KIT_LAUNCH_SYSPROP) == null ? "no_kit_launch" : null;
        } catch (Throwable t) {
            return "error:" + kind(t);
        }
    }

    /** True when the agent argument's comma-separated tokens hold {@value #PILOT_OFF_ARG} (trimmed, exact case). */
    static boolean pilotOff(String agentArgs) {
        if (agentArgs == null || agentArgs.length() > 256) {
            return false;
        }
        for (String token : agentArgs.split(",")) {
            if (token.trim().equals(PILOT_OFF_ARG)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ONE private loader, then each transformer armed on its own: the engine-name remap first, then the MagicLib one,
     * then the replica pilot pass unless switched off. A loader failure disarms every receipt; a transformer's own
     * failure disarms only its receipt.
     */
    private static void arm(Instrumentation inst, boolean pilotOff) {
        CompatAgentLoader loader;
        try {
            loader = CompatAgentLoader.overOwnJar(CompatPremain.class);
        } catch (Throwable t) {
            disarm(ENGINE_MAP_RECEIPT_SYSPROP, "enginemap", t);
            disarm(RECEIPT_SYSPROP, "magicseam", t);
            if (pilotOff) {
                offPilot();
            } else {
                disarm(PILOT_RECEIPT_SYSPROP, "pilotreplica", t);
            }
            return;
        }
        armEngineMap(inst, loader);
        try {
            ClassFileTransformer t = newTransformer(loader, TRANSFORMER);
            System.setProperty(RECEIPT_SYSPROP, RECEIPT_ARMED);
            inst.addTransformer(t, false);
        } catch (Throwable t) {
            disarm(RECEIPT_SYSPROP, "magicseam", t);
        }
        if (pilotOff) {
            offPilot();
            return;
        }
        try {
            ClassFileTransformer t = newTransformer(loader, PILOT_TRANSFORMER);
            System.setProperty(PILOT_RECEIPT_SYSPROP, RECEIPT_ARMED);
            inst.addTransformer(t, false);
        } catch (Throwable t) {
            disarm(PILOT_RECEIPT_SYSPROP, "pilotreplica", t);
        }
    }

    /**
     * The engine-name remap behind {@code loader}: its constructor takes the arming decision; the receipt it answers
     * (a JDK {@link Supplier}) is published first, then it is registered only when it says so (a JDK
     * {@link BooleanSupplier}) - this unit links no class of the remap's package. Never throws.
     */
    static void armEngineMap(Instrumentation inst, CompatAgentLoader loader) {
        try {
            ClassFileTransformer t = newTransformer(loader, ENGINE_MAP_TRANSFORMER);
            String receipt = String.valueOf(((Supplier<?>) t).get());
            System.setProperty(ENGINE_MAP_RECEIPT_SYSPROP, receipt);
            if (((BooleanSupplier) t).getAsBoolean()) {
                inst.addTransformer(t, false);
                System.out.println(ROW + "enginemap armed map=" + receipt);
            } else if (!ENGINE_MAP_IDENTITY.equals(receipt)) {
                System.out.println(ROW + "enginemap unarmed state=" + receipt);
            }
        } catch (Throwable t) {
            disarm(ENGINE_MAP_RECEIPT_SYSPROP, "enginemap", t);
        }
    }

    private static void offPilot() {
        try {
            System.setProperty(PILOT_RECEIPT_SYSPROP, PILOT_RECEIPT_OFF);
            System.out.println(ROW + "pilotreplica off reason=agent_arg");
        } catch (Throwable ignored) {
            // nothing may escape a premain; the game starts with every mod class as shipped
        }
    }

    private static void disarm(String receipt, String tag, Throwable t) {
        try {
            String reason = "error:" + kind(t);
            System.setProperty(receipt, "disarmed:" + reason);
            System.out.println(ROW + tag + " disarmed reason=" + reason + " jar=none");
        } catch (Throwable ignored) {
            // nothing may escape a premain; the game starts unpatched
        }
    }

    /**
     * A fresh private loader over this class's own jar and the MagicLib transformer instantiated behind it. Throws
     * when the transformer did not come from that loader (a parent copy would link the parent's ASM).
     */
    static ClassFileTransformer newTransformer() throws Exception {
        return newTransformer(CompatAgentLoader.overOwnJar(CompatPremain.class), TRANSFORMER);
    }

    /** The named transformer instantiated behind {@code loader}; throws when another loader defined it. */
    static ClassFileTransformer newTransformer(CompatAgentLoader loader, String binaryName) throws Exception {
        Class<?> type = Class.forName(binaryName, true, loader);
        if (type.getClassLoader() != loader) {
            throw new IllegalStateException("transformer_not_private");
        }
        return (ClassFileTransformer) type.getDeclaredConstructor().newInstance();
    }

    private static String kind(Throwable t) {
        String n = t.getClass().getSimpleName();
        return n.isEmpty() ? "Throwable" : n;
    }
}
