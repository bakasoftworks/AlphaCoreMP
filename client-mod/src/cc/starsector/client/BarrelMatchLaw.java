package cc.starsector.client;

/**
 * D-BARREL nearest-barrel LAW: the replica's
 * muzzle FLASH + sound sit on the barrel the shot left, INFERRED locally from the wire muzzle
 * (the GPEE v8 spawn point the bolt already rides) against the local weapon's own fire points —
 * NOT carried on the wire. The GPEE v11 {@code barrelIndex} int (66→70 B per discharge)
 * was RETIRED the day after it rolled: per-shot bytes multiply by total fire rate, and 4 bytes
 * per shot event is not cheap when a full server could have 1000+ MGs firing at
 * once.
 *
 * <p>The FX point ONLY — the launch facing is the wire's
 * {@code aimFacingDeg} (the server's projectile facing for a missile), so the barrel serves no
 * angle and the pick is a plain argmin. A wrong flash barrel is ≤ 16 su and sits ON the local
 * sprite; the wire muzzle itself would float 8–19 su off the lagging replica, which is why the
 * flash keeps the local fire point at all. The former relative margin + fast-accept radius
 * (and the engine-order sequence tie-break layered on them) existed to steer the launch angle and
 * were deleted with it.
 *
 * <p>Pure arithmetic (B-legal: no reflection, no I/O), pinned by
 * {@code cc.starsector.client.selftest.BarrelMatchLawSmoke}. {@code dist[k]} =
 * |firePoint(k) − wire muzzle| for the LOCAL spec's slot-typed fire-offset count (in the engine,
 * {@code getFirePoint(k)} is a bare {@code list.get(k)}
 * — out of range THROWS, so the caller never guesses the count);
 * {@code NaN}, negative or infinite = that fire point was unreadable and is skipped. Returns the
 * argmin over the readable distances, or {@link #BARREL_UNKNOWN} when there is no barrel or
 * nothing readable (the presenter maps it to fire point 0: fail-soft presentation).
 */
public final class BarrelMatchLaw {

    /** Honest absence: no barrel, or no readable fire point. */
    public static final int BARREL_UNKNOWN = -1;
    /** Loop ceiling for callers building {@code dist} (vanilla max 12 barrels — locust;
     *  mod headroom, mirrors the retired v11 decode bound). */
    public static final int MAX_BARREL_INDEX = 63;

    private BarrelMatchLaw() {
    }

    /** The law over per-barrel distances; see the class javadoc. */
    public static int matchBarrel(double[] dist) {
        int best = BARREL_UNKNOWN;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int k = 0; dist != null && k < dist.length; k++) {
            double d = dist[k];
            if (d >= 0.0 && d < bestDist) {   // NaN / negative / +inf never pass
                bestDist = d;
                best = k;
            }
        }
        return best;
    }
}
