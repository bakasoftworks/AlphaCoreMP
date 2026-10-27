package cc.starsector.client;

/**
 * Non-burst beam RELAY law: a replica's continuous beam is force-fired while the server is charging
 * UP or at full fire, and released at the first charge-DOWN sample.
 *
 *
 * <p>WHY: the
 * wire {@code FLAG_FIRING} bit is the server's {@code isFiring()} = "not idle", which INCLUDES
 * charge-down. The relay used to force-fire on that bit, so the local weapon was re-pressed during
 * every server charge-down. The server's idle gaps between beam cycles are 1-4 ticks, shorter than a
 * 0.1 s (6-tick) local charge-down, so the local charge tracker went from charge-down straight back
 * to charge-up without reaching idle. The engine destroys a beam's ray only on idle and creates it
 * only on idle to charge-up, so
 * the local ray was never re-created and stayed at full length while every server cycle restarted
 * at the muzzle (the station graviton overrun).
 *
 * <p>The law ({@link #assertFire}): {@code firing && !(chargeNow < chargePrev)}, where
 * {@code chargePrev} is the charge at the previous FRESH sample of the same mount (idle samples
 * included). Charge-up and full fire assert; the first falling sample releases, so the local
 * engine runs its OWN charge-down beside the server's, reaches idle (ray destroyed), and the
 * server's next charge-up re-creates the local ray at the muzzle. An EQUAL sample asserts (the
 * literal contract): that keeps the restart edge at zero added latency when a 1-tick server idle
 * falls between two client samples ({@code 1/6 -> [idle] -> 1/6}). Burst beams pass through
 * untouched ({@code burst ⇒ firing}); the lane never calls {@link #step} for them.
 *
 * <p>Charge domain: {@code ServerWeaponMountState.chargeLevel()}. On the AWSD delta path it is
 * {@code u8/255} in [0,1] (server {@code fracU8} = round, clamped; a delta without the charge field
 * carries the anchor's value, never a 0 default). On the AWSS
 * keyframe path it is the raw wire float and may be NaN: a NaN on either side is never "a fall",
 * so the law asserts, i.e. it degrades to the pre-law relay (never to a stuck release).
 *
 * <p>Fresh-tick guard ({@link #step}): the relay runs every RENDER frame, including held-over frames
 * that repeat the last snapshot. The (prev, now) pair advances only when {@code simTick} changes, so a
 * held-over frame re-reads the same pair and repeats the same decision. Laws: engine-faithful (the
 * engine's own state machine runs the lifecycle; no painted beam, no hand-set ray length), no added
 * latency (release at most one snapshot after the server's charge-down; the restart edge is
 * unchanged), client-side derivation (from data the client already receives), frame-loop
 * residency (a long compare and a float compare per call, no allocation). Caller: {@code BeamPresentationLane.maintainBeamPresentation}.
 * Smoke: {@code BeamRelayLawSmoke}.
 */
final class BeamRelayLaw {

    /** The lane force-fires the local weapon this frame. */
    static final int ASSERT = 1;
    /** Edge: the relay released on a wire-FIRING charge-down sample ({@code beam_relay_charge_stops}). */
    static final int CHARGE_STOP = 1 << 1;
    /**
     * Edge: the relay re-asserted after a stop while the server went idle (an idle sample was seen,
     * or the re-assert sample follows skipped ticks where a short idle could hide) and the local
     * weapon was never seen idle since the stop ({@code beam_relay_idle_bridged}; the local ray
     * survives into the next server cycle, i.e. the overrun this law exists to end; target ≈ 0).
     * The skipped-tick arm also counts a server re-press during charge-down (no idle), so the
     * counter errs high, never low.
     */
    static final int IDLE_BRIDGED = 1 << 2;

    private BeamRelayLaw() {
    }

    /** The per-frame decision (pure). NaN charge ⇒ no fall ⇒ assert (the pre-law relay). */
    static boolean assertFire(boolean firingWire, float chargeNow, float chargePrev, boolean burst) {
        if (!firingWire) {
            return false;
        }
        if (burst) {
            return true;
        }
        return !(chargeNow < chargePrev);
    }

    /**
     * One mount's relay history: ONE previous charge value plus the edge state behind the two
     * counters. Allocated once per non-burst beam weapon at its first relayed frame; mutated in place.
     */
    static final class Track {
        long tick = Long.MIN_VALUE; // simTick of the latest fresh sample (MIN = never sampled)
        float now = Float.NaN;      // charge at {@code tick}
        float prev = Float.NaN;     // charge at the fresh sample before {@code tick}
        boolean relaying;           // the last decision asserted
        boolean serverIdle;         // since the last stop: a wire-idle sample was seen
        boolean localIdle;          // since the last stop: the local weapon was seen idle
    }

    /**
     * One relay frame for a NON-burst mount. {@code localFiring} = the local
     * {@code WeaponAPI.isFiring()} (non-idle); the law reads it only while stopped and only until the
     * local idle is seen, so the caller may pass {@code t.relaying || t.localIdle || weapon.isFiring()}
     * and skip the engine read otherwise (equivalent by construction). Returns
     * an {@link #ASSERT} / {@link #CHARGE_STOP} / {@link #IDLE_BRIDGED} bit set; each edge bit fires
     * once per edge (a held-over frame repeats the decision, never the edge).
     */
    static int step(Track t, long simTick, boolean firingWire, float chargeNow, boolean localFiring) {
        boolean skipped = false;
        if (simTick != t.tick) {
            skipped = t.tick != Long.MIN_VALUE && simTick - t.tick > 1L;
            t.prev = t.now;
            t.now = chargeNow;
            t.tick = simTick;
            if (!t.relaying && !firingWire) {
                t.serverIdle = true;
            }
        }
        if (assertFire(firingWire, t.now, t.prev, false)) {
            int out = ASSERT;
            if (!t.relaying) {
                if ((t.serverIdle || skipped) && !t.localIdle && localFiring) {
                    out |= IDLE_BRIDGED;
                }
                t.relaying = true;
            }
            return out;
        }
        int out = 0;
        if (t.relaying) {
            t.relaying = false;
            t.serverIdle = !firingWire;
            t.localIdle = false;
            if (firingWire) {
                out |= CHARGE_STOP;
            }
        } else if (!localFiring) {
            t.localIdle = true;
        }
        return out;
    }
}
