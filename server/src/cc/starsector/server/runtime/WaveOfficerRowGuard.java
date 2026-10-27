package cc.starsector.server.runtime;

import cc.starsector.server.match.OfficerRosterCodec;
import cc.starsector.server.platform.OfficerAttach;

/**
 * The ONE containment seam between a
 * wave/probe row's captain and the RAPP v3 officer tail. Two laws, both fail-SOFT to an
 * unofficered hull, both LOUD.
 *
 * <p>(1) {@link #rekeyForWireOrNull} — the wave re-key (a GOAL-keyed table/row spec onto the
 * fielded {@code cc_wave_<goal>_<hex8>} id) grows the encoded line by exactly
 * {@code ID_PREFIX.length() + 1 + 8} = 17 chars, so the line the BOOT sweep round-tripped
 * ({@code OfficerAttach.validateRowSpecs} / {@code bootWarmTable}) is NOT the line this drain
 * encodes: a spec whose goal-keyed line lands in [496, 512] passes boot and blows the 512-char cap
 * at the mint, inside {@code serveFrame}. This re-runs {@link OfficerAttach#requireWireRoundTrip}
 * on the RE-KEYED spec and returns null (the hull spawns bare on BOTH ends) instead of throwing on
 * the sim thread. Only the id-CHANGING case pays the encode — {@code withVariantId} returns
 * {@code this} for a same-id call and boot already proved that line (no per-spawn work
 * boot already did). Widest real line today = 275 chars inflated (sindrian_diktat w8
 * {@code executor_Standard}), so this is a tripwire, not a live cost.
 *
 * <p>(2) {@link #officeredOrBare} — every other v3 row-shape law ({@code AppendRow}'s own-variant
 * and carrier laws, {@code encodeLine}'s grammar + skill bounds) throws
 * {@code IllegalArgumentException} at the mint; inside {@code serveFrame} that is a serve-killing
 * fatal (a measured blast radius: engine_exit=1, every seat dropped mid-round). The bare
 * 5-arg row appends instead — the hull is fielded, only its captain is missing. A v2-CLASS defect
 * (blank name, bad CR) still fails fast: the bare row re-throws it, unchanged from pre-v3.
 *
 * <p>Honest about the residual (never-conceal-desync): a drop at (2) IS a real divergence for that
 * one hull — the server member keeps the captain the engine attached, the client twin gets none —
 * which is why the row is loud and why (1) exists: (1) drops BEFORE the spawn, so the re-key class
 * can never reach (2). An {@code officer_row_dropped stage=row} line in a log is a bug to fix, not
 * a state to live in.
 */
public final class WaveOfficerRowGuard {

    private WaveOfficerRowGuard() {
    }

    /**
     * The spec re-keyed to the id the hull is ACTUALLY fielded under, or null when the re-keyed
     * line cannot ride RAPP v3 (⇒ spawn the hull bare; the client twin fields no captain either).
     */
    public static OfficerRosterCodec.OfficerSpec rekeyForWireOrNull(
            OfficerRosterCodec.OfficerSpec specOrNull, String fieldedVariantId,
            String lane, String slot, int round, long tick) {
        if (specOrNull == null) {
            return null;
        }
        OfficerRosterCodec.OfficerSpec rekeyed = specOrNull.withVariantId(fieldedVariantId);
        if (rekeyed == specOrNull) {
            // The fielded id IS the goal id: boot round-tripped this exact line already.
            return rekeyed;
        }
        try {
            OfficerAttach.requireWireRoundTrip(rekeyed);
        } catch (IllegalStateException | IllegalArgumentException reject) {
            System.out.println("npeer-serve officer_row_dropped stage=rekey lane=" + lane
                    + " slot=" + WireTextLaw.foldUnprintable(slot).replace(' ', '_')
                    + " goal=" + WireTextLaw.foldUnprintable(specOrNull.variantId)
                    + " fielded=" + WireTextLaw.foldUnprintable(fieldedVariantId)
                    + " captain=" + WireTextLaw.foldUnprintable(specOrNull.fullName()).replace(' ', '_')
                    + " err=" + WireTextLaw.foldUnprintable(reject.getMessage())
                    + " round=" + round + " tick=" + tick
                    + " (the re-keyed line cannot ride RAPP v3: the hull fields NO captain on"
                    + " either end - fix the table row, never the frame)");
            return null;
        }
        return rekeyed;
    }

    /** The shape before the at-rest bit ({@code atRest=false}) — the contract rows' call; every drain passes the bit. */
    public static RosterAppendFrameCodec.AppendRow officeredOrBare(long wireId, int side,
            String variantId, String shipName, int crPerMille,
            OfficerRosterCodec.OfficerSpec officerOrNull, String lane, int round, long tick) {
        return officeredOrBare(wireId, side, variantId, shipName, crPerMille, officerOrNull, lane,
                round, tick, false);
    }

    /**
     * The officered RAPP row, or the BARE row plus one loud line when the mint rejects (both loud
     * lines fold every id / name token:).
     * {@code atRest} = the spawn's own
     * {@code WaveSpawnResult#atRest} (the RAPP v5 flags bit) rides BOTH arms — a dropped captain
     * must never also re-arm the client twin's fly-in burn on a posed hull.
     */
    public static RosterAppendFrameCodec.AppendRow officeredOrBare(long wireId, int side,
            String variantId, String shipName, int crPerMille,
            OfficerRosterCodec.OfficerSpec officerOrNull, String lane, int round, long tick,
            boolean atRest) {
        try {
            return RosterAppendFrameCodec.AppendRow.officered(wireId, side, variantId, shipName,
                    crPerMille, officerOrNull, atRest);
        } catch (IllegalArgumentException reject) {
            System.out.println("npeer-serve officer_row_dropped stage=row lane=" + lane
                    + " wire_id=" + wireId + " variant=" + WireTextLaw.foldUnprintable(variantId)
                    + " captain=" + (officerOrNull == null ? "none"
                            : WireTextLaw.foldUnprintable(officerOrNull.fullName()).replace(' ', '_')
                                    + "/" + WireTextLaw.foldUnprintable(officerOrNull.variantId))
                    + " err=" + reject.getMessage()
                    + " round=" + round + " tick=" + tick
                    + " (DESYNC: the server member keeps this captain, the client twin gets a bare"
                    + " row - the hull still appends; a serve death would drop EVERY seat)");
            return new RosterAppendFrameCodec.AppendRow(wireId, side, variantId, shipName,
                    crPerMille, 0L, RosterAppendFrameCodec.BAY_NONE, "", atRest);
        }
    }
}
