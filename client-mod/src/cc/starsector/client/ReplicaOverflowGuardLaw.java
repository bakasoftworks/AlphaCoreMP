package cc.starsector.client;

/**
 * The pure half of {@link ReplicaOverflowGuard}.
 *
 *
 * <p>WHY: on a wire-covered body the SERVER computes flux and overload exactly and the wire carries both. A shield
 * hit whose shooter is another wire-driven replica is not the pilot's prediction of anything: its local flux rise is
 * a guess one snapshot wide (the per-frame pin rewrites it), but a local OVERLOAD is a guess the engine cannot take
 * back — the sound, the floaty and {@code shield.toggleOff()} fire INSIDE {@code increaseFlux}.
 * In one recorded match, 23 of the 32 such overloads seat 1 showed were wrong.
 * So such a hit lands exactly as the engine computes it UNLESS it would overflow; an overflowing one adds nothing
 * and the wire's overload flag stays the only author of that overload (the wire field exists,
 * so the guess is deleted at its author).
 */
final class ReplicaOverflowGuardLaw {

    /** The hit fits: the engine's own sum stands. */
    static final int LAND = 0;
    /** The hit would overflow: nothing is added; the wire authors the overload. */
    static final int DROP = 1;
    /** The hit is bigger than the body's whole capacity — it overloads at ANY flux level, on the server too if it
     *  lands — so the engine's overload is LEFT STANDING as a lead (stopping it would double-play
     *  the one-shots). Routine with fighters. */
    static final int RESIDUE = 2;

    private ReplicaOverflowGuardLaw() {
    }

    /**
     * The MODIFY-seam arm test, side-effect-free (never {@code hullSourceClass}, which counts). Arms only
     * for a RESOLVED shooter that is a non-own wire-driven ship (the caller resolves it with
     * {@code HullCapBound.shooterOf(param)} — the seam's param is the projectile or the beam, never the ship), on
     * a tracker that is neither overloaded nor venting (the engine adds nothing then), outside a
     * wire-acked presenter apply ({@link WireAckedFluxWindow} owns the flux there). An unresolved shooter (an EMP
     * arc, the EMP system, a ram, a scripted apply) stays unguarded and is counted at the report seam.
     */
    static boolean arms(boolean shooterResolved, boolean shooterOwn, boolean shooterWireDriven,
                        boolean overloaded, boolean venting, boolean wireAckedOpen) {
        return shooterResolved && !shooterOwn && shooterWireDriven && !overloaded && !venting && !wireAckedOpen;
    }

    /**
     * The REPORT-seam verdict. {@code pre} = the flux the guard set aside, {@code add} = what the engine wrote onto
     * the zeroed tracker, {@code max} = its capacity, {@code overloadBegan} = the apply itself began an overload
     * from zero. The DROP test is the engine's own strict {@code curr > max} written so that
     * a NaN restores {@code pre} instead of landing.
     */
    static int settle(float pre, float add, float max, boolean overloadBegan) {
        if (overloadBegan) {
            return RESIDUE;
        }
        return pre + add <= max ? LAND : DROP;
    }
}
