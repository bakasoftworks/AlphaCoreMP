package cc.starsector.server.runtime;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * The serve's LIVE values of the wire-delivered client knobs ({@link WireKnob}) — the server half
 * of lane-posture P4 "server-settable, wire-delivered" for the knobs that vary mid-match
 * (the foe-cloak lead/wait A/B). Boot value per knob = its config row
 * (registered in {@link PostureRegistry} so the census dump + the strict-config law cover it —
 * for {@link WireKnob#FOE_CLOAK_LEAD}: sysprop {@code cc.predict.foeCloakLead}, env
 * {@code CC_PREDICT_FOE_CLOAK_LEAD}, file {@code predict.foeCloakLead}; absent = choice 0, an
 * unknown word is a boot-time refuse, never a silent default). Live writes come from the
 * {@code /knob} admin verb ({@link AdminKnobVerb}), which then re-spools the {@code PSTB} block
 * to every live session. A DERIVED row ({@link WireKnob#isDerived()}: {@link
 * WireKnob#AIM_FAN_TICKS}, {@link WireKnob#SNAP_STRIDE_TICKS}) has no config row: it boots at its {@code defaultChoice()}, only the
 * serve writes it ({@link #setDerived}), and {@link #set} refuses it. {@link #pairs()} renders
 * the WHOLE census as the flat {@code (id, value)} array
 * {@link PostureFrameCodec#encodeBlock(String, int[])} takes.
 */
public final class WireKnobState {

    private static final WireKnob[] KNOBS = WireKnob.values();
    private static final AtomicIntegerArray VALUES = new AtomicIntegerArray(KNOBS.length);

    static {
        for (int i = 0; i < KNOBS.length; i++) {
            VALUES.set(i, bootValue(KNOBS[i]));
        }
    }

    private WireKnobState() {
    }

    /**
     * One knob's boot value, the static init's one step:
     * a DERIVED row skips the config resolve (it has no key, so {@link #envFor} / {@link
     * #fileKeyFor} would throw) and boots at its {@code defaultChoice()}; every other row resolves
     * its config word and REFUSES an unknown one (a boot-time refuse, never a silent default).
     * Package-private so {@code AimFanKnobRows} AF4 can pin the skip in a JVM that already loaded
     * this class.
     */
    static int bootValue(WireKnob knob) {
        if (knob.isDerived()) {
            return knob.defaultChoice();
        }
        String word = ServerConfig.resolve(knob.key(), envFor(knob), fileKeyFor(knob),
                knob.choiceName(knob.defaultChoice()));
        int value = knob.choiceIndex(word);
        if (value < 0) {
            throw new IllegalArgumentException(knob.key() + " must be one of "
                    + String.join("|", knob.choices()) + "; got '" + word + "'");
        }
        return value;
    }

    /** The registered env twin of a knob's sysprop key ({@link PostureRegistry} row parity). */
    static String envFor(WireKnob knob) {
        switch (knob) {
            case FOE_CLOAK_LEAD:
                return "CC_PREDICT_FOE_CLOAK_LEAD";
            case TERRAIN: //
                return "CC_MATCH_TERRAIN";
            case TERRAIN_LEVEL:
                return "CC_MATCH_TERRAIN_LEVEL";
            case ASTEROIDS: //
                return "CC_MATCH_ASTEROIDS";
            default:
                throw new IllegalStateException("unregistered wire knob " + knob);
        }
    }

    /** The registered config-file row of a knob ({@link PostureRegistry} row parity). */
    static String fileKeyFor(WireKnob knob) {
        switch (knob) {
            case FOE_CLOAK_LEAD:
                return "predict.foeCloakLead";
            case TERRAIN: //
                return "match.terrain";
            case TERRAIN_LEVEL:
                return "match.terrainLevel";
            case ASTEROIDS: //
                return "match.asteroids";
            default:
                throw new IllegalStateException("unregistered wire knob " + knob);
        }
    }

    /** The live value (a choice index) of {@code knob}. */
    public static int get(WireKnob knob) {
        return VALUES.get(knob.ordinal());
    }

    /** Set the live value; returns the previous one. Caller re-spools the block (the verb does).
     *  A derived row is REFUSED ({@code IllegalArgumentException}): only {@link #setDerived}
     *  writes it, so no verb path can ever flip it. */
    public static int set(WireKnob knob, int value) {
        if (knob.isDerived()) {
            throw new IllegalArgumentException(knob.key() + " is read-only (derived; the serve sets it)");
        }
        if (value < 0 || value >= knob.choiceCount()) {
            throw new IllegalArgumentException(knob.key() + " value " + value + " outside 0.."
                    + (knob.choiceCount() - 1));
        }
        return VALUES.getAndSet(knob.ordinal(), value);
    }

    /**
     * The serve's write of a DERIVED row
     * ({@code PilotServeSession.copyBack}: {@link WireKnob#AIM_FAN_TICKS} from the resolved fan,
     * {@link WireKnob#SNAP_STRIDE_TICKS} from the resolved {@code net.downEveryTicks});
     * returns the previous value. Refuses ({@code IllegalArgumentException}) a non-derived row
     * (those are config / {@code /knob} rows) and a value outside the row's table. No re-spool:
     * the serve sets it before any session is admitted, and every admission spools the census.
     */
    public static int setDerived(WireKnob knob, int value) {
        if (!knob.isDerived()) {
            throw new IllegalArgumentException(knob.key() + " is not a derived knob");
        }
        if (value < 0 || value >= knob.choiceCount()) {
            throw new IllegalArgumentException(knob.key() + " value " + value + " outside 0.."
                    + (knob.choiceCount() - 1));
        }
        return VALUES.getAndSet(knob.ordinal(), value);
    }

    /** The whole census as flat {@code (id, value)} pairs, ids ascending (the encode contract). */
    public static int[] pairs() {
        int[] out = new int[KNOBS.length * 2];
        for (int i = 0; i < KNOBS.length; i++) {
            out[i * 2] = KNOBS[i].id();
            out[i * 2 + 1] = VALUES.get(i);
        }
        return out;
    }

    /** One {@code key=word} summary per knob, space-separated (receipts + serve log rows). */
    public static String summary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < KNOBS.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(KNOBS[i].verbName()).append('=').append(KNOBS[i].choiceName(VALUES.get(i)));
        }
        return sb.toString();
    }
}
