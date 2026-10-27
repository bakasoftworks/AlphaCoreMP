package cc.starsector.server.runtime;

import java.util.function.IntSupplier;

/**
 * Battle TERRAIN on the serve: the {@code /terrain} admin
 * verb's serve-loop drain ({@link OperatorCommandFile.Verb#SET_TERRAIN}; the {@link AdminKnobVerb}
 * idiom — deposited by the door, drained on the serve thread, the receipt mailed back with the
 * REAL effect). Grammar (fixed-set at BOTH mint sites, so {@code text} is already
 * folded + validated): {@code show} = both knobs' live words; {@code <word>} = set
 * {@link WireKnob#TERRAIN}, level kept; {@code <word> <level>} = set both. A set writes
 * {@link WireKnobState}, RE-SPOOLS the {@code PSTB} posture block to every live session
 * ({@link PilotIntentDatagramServer#respoolPostureBlockToAll}) so the client lanes re-derive their
 * mults + presentation from the knob, and re-applies the SERVER's own terrain stat lane on the
 * live authoritative ships through {@link #reapplyHook} (the seam slice A's stat half registers;
 * absent = logged as {@code hook=none}, the knob still flips — the wire half never hides a missing
 * sim half). One owner per quantity: the mults are a deterministic function of the two
 * words + each ship's own {@code corona_resistance}, so nothing per-tick rides the wire.
 */
final class AdminTerrainVerb {

    /** The serve-side one-pass re-apply over every live ship (returns the ship count touched);
     * registered by the stat lane at serve start, null until it lands. Volatile: set once from
     * the boot thread, read on the serve thread. */
    static volatile IntSupplier reapplyHook;

    private AdminTerrainVerb() {
    }

    static void handle(PilotIntentDatagramServer door, OperatorCommandFile.Command command,
            int round, int frame) {
        String[] parts = command.text().split(" ");
        if (parts.length == 1 && "show".equals(parts[0])) {
            receipt(door, command, "terrain = " + live() + " (choices: "
                    + String.join(", ", WireKnob.TERRAIN.choices()) + "; level "
                    + String.join(", ", WireKnob.TERRAIN_LEVEL.choices()) + ")");
            return;
        }
        int word = parts.length >= 1 ? WireKnob.TERRAIN.choiceIndex(parts[0]) : -1;
        int level = parts.length == 2 ? WireKnob.TERRAIN_LEVEL.choiceIndex(parts[1])
                : parts.length == 1 ? WireKnobState.get(WireKnob.TERRAIN_LEVEL) : -1;
        if (word < 0 || level < 0 || parts.length > 2) {
            receipt(door, command, "usage: /terrain " + String.join("|", WireKnob.TERRAIN.choices())
                    + " [" + String.join("|", WireKnob.TERRAIN_LEVEL.choices()) + "]");
            return;
        }
        String was = live();
        WireKnobState.set(WireKnob.TERRAIN, word);
        WireKnobState.set(WireKnob.TERRAIN_LEVEL, level);
        int seats = door == null ? 0 : door.respoolPostureBlockToAll();
        int ships = reapply();
        System.out.println("npeer-serve operator_terrain terrain=" + live() + " was=" + was
                + " respooled=" + seats + " ships=" + ships
                + (reapplyHook == null ? " hook=none" : "")
                + " round=" + round + " frame=" + frame);
        receipt(door, command, "terrain = " + live() + " (was " + was + "; sent to " + seats
                + " seat" + (seats == 1 ? "" : "s") + "; " + ships + " ship"
                + (ships == 1 ? "" : "s") + " re-statted)");
    }

    /** {@code <word> <level>} — the live pair as receipt/log text. */
    static String live() {
        return WireKnob.TERRAIN.choiceName(WireKnobState.get(WireKnob.TERRAIN)) + " "
                + WireKnob.TERRAIN_LEVEL.choiceName(WireKnobState.get(WireKnob.TERRAIN_LEVEL));
    }

    /** Run the registered re-apply once (0 when none is registered or it threw — logged). Also
     * the {@link AdminKnobVerb} path's hook when {@code /knob} flips one terrain knob alone. */
    static int reapply() {
        IntSupplier hook = reapplyHook;
        if (hook == null) {
            return 0;
        }
        try {
            return hook.getAsInt();
        } catch (RuntimeException failed) {
            System.out.println("npeer-serve operator_terrain_reapply_failed err=" + failed);
            return 0;
        }
    }

    /** The chat receipt to the depositing session; the house-file twin (session 0) logs instead. */
    private static void receipt(PilotIntentDatagramServer door, OperatorCommandFile.Command command,
            String line) {
        if (door != null && command.receiptSessionId() != 0L) {
            door.sendAdminReceipt(command.receiptSessionId(), line);
        } else {
            System.out.println("npeer-serve operator_terrain_receipt text=" + line);
        }
    }
}
