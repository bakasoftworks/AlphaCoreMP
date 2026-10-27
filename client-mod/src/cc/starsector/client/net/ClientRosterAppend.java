package cc.starsector.client.net;

import cc.starsector.server.match.OfficerRosterCodec;
import java.util.List;
import java.util.Objects;

/**
 * The decoded payload of one {@code RAPP} roster-append control frame:
 * mid-round wave-spawned hulls the server appends to every client's replica roster. The
 * replica roster is descriptor-built and unknown-ids-inert, so a
 * wire id first seen mid-round would otherwise never mint client-side — the ghost match.
 * The server fans one frame per drained wave (≤16 rows; multi-frame allowed) and replays the
 * round's frames to late joiners; the plugin's apply is idempotent (known id ⇒ ignore), so
 * the catch-up replay is duplicate-safe.
 *
 * <p>Rows are {wireId, ABSOLUTE side, variantId, shipName, crPerMille} — side is
 * descriptor-absolute (the consumer maps relative to its own descriptor side at append). NO
 * spawn pose: the existing first-sight adopt spawns at the first authoritative wire pose.
 *
 * <p>Like {@link ClientMatchReset}, a small client-owned mirror of the server protocol class
 * ({@code cc.starsector.server.protocol.RosterAppendControlMessage}); the row invariants match
 * the server codec's {@code AppendRow} compact constructor so a malformed frame fails closed
 * identically ({@link ClientRosterAppendReader} translates to {@link java.io.IOException}).
 *
 * <p>{@code roundEpoch} is NOT on the wire — the session
 * re-wraps it at intake (the {@code ClientAsteroidFrame} precedent): the ctrl round epoch the net
 * thread held when it routed this frame (+1 per drained MRST), so the render-side lane orders the
 * frame against the round flip by STREAM order. The decoder builds epoch 0 (the 1-arg constructor);
 * {@code equals}/{@code hashCode} include it (a record) — no caller compares frames.
 */
public record ClientRosterAppend(List<Row> rows, int roundEpoch) {

    public ClientRosterAppend {
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("roster-append must carry at least one row");
        }
        rows = List.copyOf(rows);
    }

    /** The decoded (un-stamped) frame: epoch 0 — the reader and the pre-stamp smokes. */
    public ClientRosterAppend(List<Row> rows) {
        this(rows, 0);
    }

    /** This frame re-wrapped with the session's ctrl round epoch (intake-only; rows are shared). */
    public ClientRosterAppend withRoundEpoch(int epoch) {
        return new ClientRosterAppend(rows, epoch);
    }

    /** {@link Row#bayIndex()} sentinel — not a carrier-born fighter. Mirrors
     *  {@code RosterAppendFrameCodec.BAY_NONE}. */
    public static final int BAY_NONE = 0xFF;

    /**
     * One appended hull — invariants mirror the server codec's {@code AppendRow}, and the kit
     * {@link ClientRosterAppendReader} is STRICTER than that codec: its single-line law rejects a
     * {@code variantId} / {@code shipName} / officer line carrying a char
     * {@code WireTextLaw.isPrintableWireChar} refuses; messages here never
     * echo the ids. v2:
     * {@code carrierWireId} ({@code 0} = none) + {@code bayIndex}
     * ({@link #BAY_NONE} = none) place a carrier-born fighter for the wing HUD lane.
     */
    public record Row(long wireId, int side, String variantId, String shipName,
            int crPerMille, long carrierWireId, int bayIndex,
            OfficerRosterCodec.OfficerSpec officerOrNull, boolean atRest, boolean allSkillsCaptain) {
        /** Non-fighter row shape (tests / the pre-v2 callers): no carrier, no captain, not at rest. */
        public Row(long wireId, int side, String variantId, String shipName, int crPerMille) {
            this(wireId, side, variantId, shipName, crPerMille, 0L, BAY_NONE, null, false, false);
        }

        /** The v2 shape (carrier+bay, no captain, not at rest). */
        public Row(long wireId, int side, String variantId, String shipName, int crPerMille,
                long carrierWireId, int bayIndex) {
            this(wireId, side, variantId, shipName, crPerMille, carrierWireId, bayIndex, null, false,
                    false);
        }

        /** The v5 bit0-only shape: no all-skills bit (the callers that predate bit1). */
        public Row(long wireId, int side, String variantId, String shipName, int crPerMille,
                long carrierWireId, int bayIndex, OfficerRosterCodec.OfficerSpec officerOrNull,
                boolean atRest) {
            this(wireId, side, variantId, shipName, crPerMille, carrierWireId, bayIndex, officerOrNull,
                    atRest, false);
        }

        /**
         * v3: {@code officerOrNull} = the captain the SERVER attached to this
         * hull, decoded by {@link ClientRosterAppendReader} from the row's
         * {@code OfficerRosterCodec} grammar line (null = bare row). The replica spawn attaches
         * exactly this spec ({@code OfficerLoadout.attach(member, spec, wireId)}) so the local
         * twin's damage-taken stats match the server's (the "boss heals" overshoot).
         * Never on a carrier-born row; its variant is always the row's own (reader-enforced).
         *
         * <p>v5 {@code atRest}: the flags bit0 — the
         * SERVER hull was placed AT its pose with {@code burnDur 0} (no travel drive), so the
         * replica mints with NO parity burn ({@code PredictReconcileCombatPlugin.flyinBurnFor});
         * false on every legacy shape = the pre-v5 "burns like a fly-in" behavior.
         *
         * <p>v5 {@code allSkillsCaptain}: the flags bit1 — the
         * captain the SERVER restored on this hull IS its all-skills seat mint (a vacated transfer
         * source), so the replica mints the seat-shaped all-skills captain when the kit's all-skills
         * match flag is ON too. EXCLUSIVE with {@code officerOrNull}: a row carrying both is refused
         * here ({@link ClientRosterAppendReader} fails the frame closed); false on every legacy shape.
         */
        public Row {
            Objects.requireNonNull(variantId, "variantId");
            Objects.requireNonNull(shipName, "shipName");
            if (allSkillsCaptain && officerOrNull != null) {
                throw new IllegalArgumentException(
                        "officer line and all-skills bit are exclusive, wireId=" + wireId);
            }
            if (officerOrNull != null) {
                if (carrierWireId != 0L) {
                    throw new IllegalArgumentException("a carrier-born row cannot carry an officer: "
                            + wireId);
                }
                if (!variantId.equals(officerOrNull.variantId)) {
                    throw new IllegalArgumentException(
                            "officer variant is not the row's variant, wireId=" + wireId);
                }
            }
            if (wireId < 1L || wireId > 0xFFFFFFFFL) {
                throw new IllegalArgumentException("wireId outside u32 range: " + wireId);
            }
            if (side != 0 && side != 1) {
                throw new IllegalArgumentException("side must be 0 or 1, got " + side);
            }
            if (variantId.isBlank()) {
                throw new IllegalArgumentException("variantId must be non-blank");
            }
            if (shipName.isBlank()) {
                throw new IllegalArgumentException("shipName must be non-blank");
            }
            if (crPerMille < 0 || crPerMille > 1000) {
                throw new IllegalArgumentException("crPerMille outside [0,1000]: " + crPerMille);
            }
            if (carrierWireId < 0L || carrierWireId > 0xFFFFFFFFL) {
                throw new IllegalArgumentException(
                        "carrierWireId outside u32 range: " + carrierWireId);
            }
            if (bayIndex < 0 || bayIndex > BAY_NONE) {
                throw new IllegalArgumentException("bayIndex outside u8 range: " + bayIndex);
            }
            if ((carrierWireId == 0L) != (bayIndex == BAY_NONE)) {
                throw new IllegalArgumentException("carrierWireId/bayIndex must be both present"
                        + " or both absent; got " + carrierWireId + "/" + bayIndex);
            }
            if (carrierWireId == wireId) {
                throw new IllegalArgumentException("a hull cannot be its own carrier: " + wireId);
            }
        }

        /** True when this row is a carrier-born fighter (carrier + bay present). */
        public boolean hasCarrier() {
            return carrierWireId != 0L;
        }
    }
}
