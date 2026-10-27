package cc.starsector.client.net;

/**
 * One decoded {@code PSTB} posture wire block (lane-posture M4-A2 — the server's per-admission
 * spool telling this client its assigned posture stamp; {@link ClientPostureBlockReader}).
 * Schema 2 carries the optional experiment/diagnostic tag ({@code ""} = the
 * no-tag sentinel, an un-stamped baseline-expected admission) plus the wire-delivered
 * {@code WireKnob} set as flat {@code (id, value)} pairs — empty = every knob at its compiled
 * default. The reader validated the set (registered ids, ascending, values in range) before
 * minting this record; {@code cc.starsector.client.ClientWireKnobs} applies it.
 */
public final class ClientPostureBlock {

    private static final int[] NO_KNOBS = new int[0];

    private final String experimentTag;
    private final int[] knobPairs;

    /** Knob-less block: {@code experimentTag} must be non-null; {@code ""} = the no-tag sentinel. */
    public ClientPostureBlock(String experimentTag) {
        this(experimentTag, NO_KNOBS);
    }

    /** {@code experimentTag} non-null ({@code ""} = no tag); {@code knobPairs} flat (id, value) pairs, copied. */
    public ClientPostureBlock(String experimentTag, int[] knobPairs) {
        if (experimentTag == null) {
            throw new IllegalArgumentException("experimentTag must not be null");
        }
        if (knobPairs == null || knobPairs.length % 2 != 0) {
            throw new IllegalArgumentException("knobPairs must be a non-null even-length array");
        }
        this.experimentTag = experimentTag;
        this.knobPairs = knobPairs.clone();
    }

    /** The experiment/diagnostic tag, or {@code ""} when the admission is un-stamped. */
    public String experimentTag() {
        return experimentTag;
    }

    /** True when the server stamped this admission as deliberate variance (flags bit0). */
    public boolean hasTag() {
        return !experimentTag.isEmpty();
    }

    /** The carried knob set as flat {@code (id, value)} pairs (a copy; empty = all defaults). */
    public int[] knobPairs() {
        return knobPairs.clone();
    }

    /** The carried value of wire knob {@code id}, or {@code -1} when the block does not carry it. */
    public int knobValue(int id) {
        for (int i = 0; i + 1 < knobPairs.length; i += 2) {
            if (knobPairs[i] == id) {
                return knobPairs[i + 1];
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ClientPostureBlock{experimentTag='")
                .append(experimentTag).append("', knobs=[");
        for (int i = 0; i + 1 < knobPairs.length; i += 2) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(knobPairs[i]).append('=').append(knobPairs[i + 1]);
        }
        return sb.append("]}").toString();
    }
}
