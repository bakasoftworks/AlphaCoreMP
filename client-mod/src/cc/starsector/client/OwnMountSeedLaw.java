package cc.starsector.client;

/** The pure own-adopt mount-seed laws ({@code AimRebasePhaseSmoke} rows). A top-level holder free of
 *  API types — it loads on that smoke's API-less classpath, where {@link FireLaneCoordinator} cannot
 *  ({@code AimRebaseVectorSmoke}'s NoClassDefFoundError note in {@code client-mod/build.ps1});
 *  {@link AimRebaseLane.AimRebasePhase} precedent. Moved verbatim out of FLC (was nested there).
 * */
final class OwnMountSeedLaw {
    /** True only for a mount that has NOT fired locally on this body, on a bound wire aim,
     *  non-beam, under the local autofire AI. */
    static boolean ownAdoptSeedDecision(boolean maskBit, boolean aimBound, boolean isBeam, boolean autofiring) {
        return !maskBit && aimBound && !isBeam && autofiring;
    }

    /** {@code mask} with bit {@code mount} set; an out-of-range mount ({@code < 0} unresolved,
     *  {@code >= 64}) returns {@code mask} unchanged — it never shifts into the mask. */
    static long markMountFired(long mask, int mount) {
        return mount < 0 || mount >= 64 ? mask : mask | (1L << mount);
    }

    /** Bit {@code mount} of {@code mask}; out of range reads as fired (never seeds). */
    static boolean mountFired(long mask, int mount) {
        return mount < 0 || mount >= 64 || (mask & (1L << mount)) != 0L;
    }

    private OwnMountSeedLaw() {
    }
}
