package cc.starsector.server.runtime;

/**
 * The {@code /knob} admin verb's serve-loop drain
 * ({@link OperatorCommandFile.Verb#SET_KNOB}; the {@link AdminWaveVerbs} idiom — deposited by the
 * door, drained on the serve thread, the receipt mailed back with the REAL effect).
 * Grammar (fixed-set at BOTH mint sites, so {@code text} is already folded + validated):
 * {@code list} = every knob's live word; {@code <knob> show} = one knob's word + its choices;
 * {@code <knob> <word>} = set it in {@link WireKnobState} and RE-SPOOL the {@code PSTB} posture
 * block to every live session ({@link PilotIntentDatagramServer#respoolPostureBlockToAll}) so
 * the flip lands mid-match on each seat's next combat poll. The first knob is the foe-cloak lead/wait A/B.
 *
 */
final class AdminKnobVerb {

    private AdminKnobVerb() {
    }

    static void handle(PilotIntentDatagramServer door, OperatorCommandFile.Command command,
            int round, int frame) {
        String[] parts = command.text().split(" ");
        if (parts.length == 1 && "list".equals(parts[0])) {
            receipt(door, command, "knobs: " + WireKnobState.summary());
            return;
        }
        WireKnob knob = parts.length == 2 ? WireKnob.byVerbName(parts[0]) : null;
        if (knob == null) {
            receipt(door, command, "unknown knob (" + knobNames() + ")");
            return;
        }
        if (knob.isDerived()) {
            // A DERIVED row is READ-ONLY — `show` and any
            // word answer the same receipt (no 256-word choice list); no set, no re-spool, no
            // operator_knob row. The receipt names the row's
            // own boot home, the serve config key it is derived from.
            String bootKey = switch (knob) {
                case AIM_FAN_TICKS -> "aim.fanEveryTicks";
                case SNAP_STRIDE_TICKS -> "net.downEveryTicks";
                default -> "its serve config";
            };
            receipt(door, command, "knob " + knob.verbName() + " = "
                    + knob.choiceName(WireKnobState.get(knob))
                    + " (read-only; set " + bootKey + " at boot)");
            return;
        }
        if ("show".equals(parts[1])) {
            receipt(door, command, "knob " + knob.verbName() + " = "
                    + knob.choiceName(WireKnobState.get(knob)) + " (choices: "
                    + String.join(", ", knob.choices()) + ")");
            return;
        }
        int value = knob.choiceIndex(parts[1]);
        if (value < 0) {
            receipt(door, command, "usage: /knob " + knob.verbName() + " "
                    + String.join("|", knob.choices()));
            return;
        }
        int was = WireKnobState.set(knob, value);
        int seats = door == null ? 0 : door.respoolPostureBlockToAll();
        // A terrain knob flipped one at a time still re-stats the live
        // ships on the serve (the /terrain verb's own path does the same once for the pair)
        int ships = knob == WireKnob.TERRAIN || knob == WireKnob.TERRAIN_LEVEL
                ? AdminTerrainVerb.reapply() : 0;
        System.out.println("npeer-serve operator_knob key=" + knob.key() + " value="
                + knob.choiceName(value) + " was=" + knob.choiceName(was) + " respooled=" + seats
                + " ships=" + ships + " round=" + round + " frame=" + frame);
        receipt(door, command, "knob " + knob.verbName() + " = " + knob.choiceName(value)
                + " (was " + knob.choiceName(was) + "; sent to " + seats + " seat"
                + (seats == 1 ? "" : "s") + ")");
    }

    private static String knobNames() {
        StringBuilder sb = new StringBuilder();
        for (WireKnob k : WireKnob.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(k.verbName());
        }
        return sb.toString();
    }

    /** The chat receipt to the depositing session; the house-file twin (session 0) logs instead. */
    private static void receipt(PilotIntentDatagramServer door, OperatorCommandFile.Command command,
            String line) {
        if (door != null && command.receiptSessionId() != 0L) {
            door.sendAdminReceipt(command.receiptSessionId(), line);
        } else {
            System.out.println("npeer-serve operator_knob_receipt text=" + line);
        }
    }
}
