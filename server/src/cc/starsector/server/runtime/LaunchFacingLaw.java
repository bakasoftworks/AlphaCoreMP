package cc.starsector.server.runtime;

/**
 * The ONE pick law for a {@code WEAPON_DISCHARGE}'s wire
 * {@code aimFacingDeg}, shared by BOTH server emitters — the on-fire hook
 * ({@code cc.starsector.server.fire.CcOnFireEmitter}) and the poll-path
 * {@link GameplayEdgeEventDetector}.
 *
 * <p>WHY: a MISSILE thrusts along its own facing, so a remote view
 * that launched a rack round at the bare mount angle presented 15/60 hammerrack shots 2–6° off.
 * The just-spawned missile's {@code getFacing()} IS vanilla's launch angle
 * ({@code currAngle + angleOffsets[barrel]} + the SERVER's spread draw: jackhammer 10/10,
 * annihilator_fighter 0/30, canister_flak, bomb, flares; hammer/hammerrack/locust carry 0), so it rides the
 * EXISTING float — no new bytes, no schema bump. A bolt's flight line is already the wire
 * {@code flightBearingDeg}+{@code birthSpeed} vector, and the same-side turret snap and own-mount
 * seed consumers want the exact MOUNT angle for every non-missile: a ballistic facing would carry
 * the per-shot spread draw as a turret twitch vanilla never shows. Hence (A′):
 * missile with a finite facing ⇒ that facing normalized to [0,360) ({@code BaseEntity.setFacing}
 * is a raw assign, so currAngle ± offset can leave the range); everything else ⇒ the mount angle
 * unchanged, NaN included (honest absence, never a minted 0). No sanity band: the factory sets a
 * missile's facing at birth, and an adopt-served re-pose IS the born facing.
 *
 * <p>Provenance: the engine's own launch-angle and birth-facing assignments.
 *
 * Pure, no I/O; pinned by the {@code lfl-*} rows in {@code GameplayEdgeEventSmoke}.
 */
public final class LaunchFacingLaw {

    private LaunchFacingLaw() {
    }

    /** The wire {@code aimFacingDeg}: a missile's finite birth facing in [0,360), else
     *  {@code mountDeg} exactly (NaN stays NaN). */
    public static float pick(boolean missile, float projectileFacingDeg, float mountDeg) {
        if (missile && Float.isFinite(projectileFacingDeg)) {
            return ((projectileFacingDeg % 360f) + 360f) % 360f;
        }
        return mountDeg;
    }

    /** The counted defect case: a MISSILE discharge whose facing was unreadable, so the wire
     *  carries the mount angle (the done-when (c) instrument on the round lines). */
    public static boolean fallback(boolean missile, float projectileFacingDeg) {
        return missile && !Float.isFinite(projectileFacingDeg);
    }
}
