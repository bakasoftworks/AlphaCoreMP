package cc.starsector.client;

import java.util.List;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.net.ServerWeaponMountState;

/**
 * The OWN-ship BEAM-CYCLE phase lock (the (115)(f) Paladin charge-starved
 * held-fire desync; knob {@code cc.predict.ownBeamCycle} = {@code off|meter|apply}, statics in
 * {@link FireLaneCoordinator}). Own ship only; regen-ammo BEAM mounts only ({@code isBeam() &&
 * usesAmmo() && getAmmoPerSecond() > 0} — the exact class {@code predict.dischargeReconcile}
 * excludes at eligibility, so no other lane reconciles it).
 *
 * <p><b>The defect (mechanism, [M+S]).</b> When a regen launcher is charge-starved under
 * held fire, the server fires at exactly the regen cadence (wire {@code firing} rising edges every
 * 60 ticks in the F9-3 window) — but the firer's OWN window free-runs at its LOCAL regen phase:
 * the engine's grant→restart is ATOMIC inside {@code weapon.advance} (the plugin never even
 * observes local ammo 1 — one ownlock ammo write the whole F9-3 run, zero on the Paladin mount),
 * so the ownlock ammo sub-lane is starve-blind and both windows fire at 1/s with INDEPENDENT
 * phases ⇒ steady inter-window offset ∈ [0, 1 s), drifting. Observers ride the wire edge (their
 * offset ≈ delivery latency, bounded-small); only the firer's own window diverges.
 *
 * <p><b>The fix (zero-wire, engine-faithful).</b> Phase-lock the OWN starved restart to the wire
 * pulse, input-side, native fire path throughout:
 * <ul>
 *   <li><b>Leg A — grant-at-edge:</b> on a wire-FIRING RISING edge with local ammo 0 and the
 *       mount quiescent ({@link #beamIdle} — burst-beam aware), {@code setAmmo(1)} — the native
 *       held-fire/autofire path restarts on it (the server already paid the deduct; no force-fire
 *       on the own side ever). Covers the lagging-phase half.</li>
 *   <li><b>Leg B — anti-early bank:</b> while STARVED-HELD (wire ammo 0 + wire not firing + local
 *       ammo 0 at entry + idle), assert per-frame {@code setForceNoFireOneFrame(true)} so a
 *       LEADING local regen grant BANKS instead of firing; the edge stops the assert and the
 *       native restart consumes the bank. Covers the leading-phase half. The assert gates every
 *       fire mode at the top of {@code weapon.advance} and a held mount
 *       costs nothing.</li>
 *   <li><b>Mirror shield:</b> blocking the atomic restart makes the banked/granted ammo VISIBLE
 *       to the ownlock ammo mirror, whose downward write (auth 0) would stomp the bank every
 *       fresh frame — while a mount is starve-engaged this lane owns its ammo and
 *       {@link FireLaneCoordinator#maintainOwnFireLock} holds the mirror
 *       ({@link #shieldsAmmoMirror}, counted).</li>
 * </ul>
 * Guards: never assert while in a burst or mid-charge (a committed burst is never fought —
 * never-conceal); stale wire (no fresh pose within {@link #staleHorizonTicks}) ⇒ free-run native
 * (today's behavior); normal regime (local ammo ≥ 1 outside a starve engagement) untouched —
 * prediction stays instant-local. Unconsumed grants time out fail-open ({@link #grantTimeoutTicks},
 * released press) and the mirror resumes.
 *
 * <p><b>The tail fix ((a)+(b) + the entry-frame assert).</b> The measured
 * p90≈19–21 t tail = a local regen grant landing inside the post-consume committed
 * window (burst + burst-delay ≈ 18 t) and refiring natively at READY. Three edits: (b) entry no
 * longer gates on the wire firing LEVEL (it outlasts the local window by the snapshot cadence;
 * {@code simTick} is the applied snapshot tick, so every lane clock is snapshot-quantized);
 * (a) the HELD edge branch keys on the RISING edge — an edge tick releases at most once
 * ({@code usedEdgeTick}); and the entry frame FALLS THROUGH into the HELD assert, because the
 * engine's burst tracker exits COOLING with the fire input forced false
 * and refires on the very next {@code weapon.advance} — an entry that only flips phase is one
 * frame late (why the ammo-1 re-entry moved nothing). Split-census tokens
 * ({@code own_cycle_census_free_*}, {@code reentry_lvl_high}, {@code stale_edge_skips},
 * {@code inwin_grants}, {@code census_held}) name which face fired.
 *
 * <p><b>Census (the A/B machine bar).</b> Burst-start = local charge 0→+ edge, sampled per frame;
 * while starve-engaged (or within {@link #censusRecentTicks} of it) the offset
 * |burst-start − last wire-edge ARRIVAL| lands in a 64-bucket tick histogram (bucket 63 =
 * overflow; no usable edge reference = {@code census_unref}). Fix ON: p90 ≤ ~6 ticks (grant at
 * arrival + native restart ≈ the observer's own latency bound). {@code meter}: full state machine
 * + census, ZERO writes — the A/B baseline arm, which also records the free-run magnitude
 * residual (expected p90 ≈ uniform-[0,60) tail). Counters count law decisions in meter AND apply
 * (the ownlock A/B idiom); engine writes only under apply. Fail-soft like every reconcile lane.
 */
final class OwnBeamCycleLane {

    /** Wire-latch staleness horizon: past it the lane disengages to free-run (the 1 s idiom). */
    private static long staleHorizonTicks() {
        return Math.round(1.0 * ServerTickRate.ticksPerSecond());
    }

    /** Fail-open bound on an unconsumed edge grant (press released at the edge): mirror resumes after. */
    private static long grantTimeoutTicks() {
        return Math.round(1.0 * ServerTickRate.ticksPerSecond());
    }

    /** Census stays eligible this long after a starve engagement (absorbs exit/re-entry seams). */
    private static long censusRecentTicks() {
        return Math.round(2.0 * ServerTickRate.ticksPerSecond());
    }

    /** Offsets past this many ticks have no usable edge reference (previous life / long-dead edge). */
    private static final long CENSUS_MAX_REF_TICKS = 600;

    private static final int PHASE_FREE = 0;    // untracked-normal: no starve engagement
    private static final int PHASE_HELD = 1;    // starved-held: bank assert (apply) / tracked (meter)
    private static final int PHASE_GRANTED = 2; // edge released: grant/bank awaiting native consume

    private static final int GRANT_LOG_MAX = 20;

    private static final class MountState {
        boolean wireFiring;
        int wireAmmo;
        long freshTick = Long.MIN_VALUE;   // simTick of the last fresh wire latch
        long edgeTick = Long.MIN_VALUE;    // arrival simTick of the last wire-FIRING rising edge
        long grantTick = Long.MIN_VALUE;
        long starveActivityTick = Long.MIN_VALUE;
        long entryTick = Long.MIN_VALUE;     // simTick of the current/last FREE→HELD entry
        long usedEdgeTick = Long.MIN_VALUE;  // The edge tick that last released a grant/bank
        long consumeTick = Long.MIN_VALUE;   // simTick of the last native consume
        int phase = PHASE_FREE;
        float lastCharge;
        int lastAmmo = -1;                   // Previous frame's local ammo (grant-landing detect)
    }

    private final java.util.HashMap<Integer, MountState> mounts =
            new java.util.HashMap<Integer, MountState>();

    // Counters (law decisions in meter AND apply; writes only under apply — the ownlock idiom).
    long edges;             // wire-FIRING rising edges latched on tracked mounts
    long edgeGrants;        // Leg A: setAmmo(1) at edge / would-grant (meter)
    long bankReleases;      // edge arrived with a banked local grant already standing
    long nofireAsserts;     // Leg B: per-frame force-no-fire asserts / would-assert (meter)
    long grantConsumes;     // native restart consumed the grant/bank
    long grantTimeouts;     // grant aged out unconsumed (released press) — mirror resumed
    long mirrorHolds;       // ownlock ammo-mirror writes held while starve-engaged (counted at the mirror)
    long abortsStale;       // engagement dropped on wire-latch staleness ⇒ free-run native
    long censusUnref;       // burst-starts in-window with no usable edge reference
    // Split census + fix-face counters (which mechanism actually fires):
    long reentryLvlHigh;    // (b): entries at local ammo ≥1 taken while the wire LEVEL was still high
    long staleEdgeSkips;    // (a): HELD frames where the level is high but the edge is already USED
    long inwinGrants;       // local ammo 0→1 landed inside the post-consume committed window (recent starve)
    long censusHeld;        // burst-starts while HELD = the bank assert FAILED (should stay 0)
    long censusFreeN;       // burst-starts while FREE (native, unbanked) — the tail population
    long censusFreePrev1;   // ...of which the previous frame's local ammo was ≥1 (a visible, unbanked grant)
    long censusFreeLvl;     // ...of which the wire level was high at burst-start
    private final int[] censusHist = new int[64];
    private final int[] censusFreeHist = new int[64];   // FREE-native offsets (subset of censusHist)
    private final int[] censusFreeScHist = new int[64]; // FREE-native ticks-since-last-consume
    private long censusN;
    private long censusMaxTicks;
    private int grantLogLines;
    private boolean activeLogged;

    /**
     * True while this lane owns the mount's ammo (starve-engaged): the ownlock ammo mirror must
     * hold its write or it stomps the banked/granted restart charge (class doc). Caller counts
     * via {@link #mirrorHolds}.
     */
    boolean shieldsAmmoMirror(int mountIndex) {
        MountState st = mounts.isEmpty() ? null : mounts.get(mountIndex);
        return st != null && st.phase != PHASE_FREE;
    }

    /**
     * The per-frame drive (caller = {@link FireLaneCoordinator#maintainOwnBeamCycle}, own ship
     * only, every frame; {@code fresh} marks a fresh carried snapshot). Order inside a frame:
     * fresh wire latch → census sample → state machine — and the caller runs AFTER the ownlock
     * mirror pass, so an edge grant lands post-mirror and is consumed by the next
     * {@code weapon.advance} before any unshielded mirror frame sees it.
     */
    void advanceFrame(ShipAPI own, ServerEntityPose pose, boolean fresh, long simTick,
            boolean apply) {
        if (own == null || !own.isAlive()) {
            if (!mounts.isEmpty()) {
                mounts.clear(); // death/respawn: never carry an engagement across hull epochs
            }
            return;
        }
        try {
            List<WeaponAPI> weapons = own.getAllWeapons();
            if (weapons == null) {
                return;
            }
            if (fresh && pose != null && pose.hasWeaponMounts()) {
                latchWire(pose, weapons, simTick);
            }
            for (java.util.Map.Entry<Integer, MountState> e : mounts.entrySet()) {
                int idx = e.getKey().intValue();
                if (idx < 0 || idx >= weapons.size()) {
                    continue;
                }
                WeaponAPI w = weapons.get(idx);
                if (w == null) {
                    continue;
                }
                step(w, idx, e.getValue(), simTick, apply);
            }
        } catch (Throwable ignore) {
            // reconcile lane; never break the watch
        }
    }

    /** Fresh-frame wire latch + rising-edge detect over the tracked (regen-beam) mount class. */
    private void latchWire(ServerEntityPose pose, List<WeaponAPI> weapons, long simTick) {
        for (ServerWeaponMountState mount : pose.weaponMounts()) {
            int idx = mount.mountIndex();
            if (idx < 0 || idx >= weapons.size() || !mount.usesAmmo()) {
                continue;
            }
            WeaponAPI w = weapons.get(idx);
            if (w == null || !w.isBeam() || !w.usesAmmo() || !(w.getAmmoPerSecond() > 0f)) {
                continue;
            }
            MountState st = mounts.get(idx);
            if (st == null) {
                st = new MountState();
                st.wireFiring = mount.firing(); // seed without minting a false edge
                mounts.put(idx, st);
            } else if (!st.wireFiring && mount.firing()) {
                st.edgeTick = simTick;
                edges++;
            }
            st.wireFiring = mount.firing();
            st.wireAmmo = mount.ammo();
            st.freshTick = simTick;
        }
    }

    /**
     * The between-bursts quiescence test, BURST-BEAM AWARE. The engine's beam
     * class computes {@code getBurstFireTimeRemaining()} as {@code (burstDuration+chargeup) −
     * elapsedInBurst} with NO phase gate (the tracker's
     * elapsed time resets to 0 outside a burst) ⇒ a burst beam
     * reports its FULL burst length at every idle frame and a {@code <= 0} test can never hold
     * (the Paladin PD: 0.2 s at every idle frame — the census-empty root cause; the same
     * term keeps {@link OwnFireLockLaw#ammoQuiescent} permanently BUSY on burst-beam mounts,
     * left as-is there: it needs the pending-hold machinery for regen launchers, own row).
     * For burst beams the READY phase is exactly {@code !isFiring() && getCooldownRemaining()
     * <= 0} ({@code getChargeLevel()} = cdRem/(cd+chargedown) while cooling, 1.0 in-burst).
     * Non-burst beams keep the {@code getBurstFireTimeRemaining() <= 0} test (their tracker
     * has burstDuration 0 ⇒ it reads 0 when idle).
     */
    private static boolean beamIdle(WeaponAPI w, boolean inBurst, float charge) {
        if (inBurst || charge > 0f || w.isFiring()) {
            return false;
        }
        return w.isBurstBeam()
                ? w.getCooldownRemaining() <= 0f
                : w.getBurstFireTimeRemaining() <= 0f;
    }

    /** One mount's census sample + phase step (class-doc state machine). */
    private void step(WeaponAPI w, int idx, MountState st, long simTick, boolean apply) {
        float charge = w.getChargeLevel();
        boolean inBurst = w.isInBurst();
        int localAmmo = w.getAmmo();
        // Census FIRST (the burst may exit the phase this same frame): local charge 0->+ edge.
        if (st.lastCharge <= 0f && charge > 0f
                && (st.phase != PHASE_FREE
                        || (st.starveActivityTick != Long.MIN_VALUE
                            && simTick - st.starveActivityTick <= censusRecentTicks()))) {
            long off = st.edgeTick == Long.MIN_VALUE ? -1L : simTick - st.edgeTick;
            if (off < 0L || off > CENSUS_MAX_REF_TICKS) {
                censusUnref++;
            } else {
                int b = (int) Math.min(off, censusHist.length - 1L);
                censusHist[b]++;
                censusN++;
                if (off > censusMaxTicks) {
                    censusMaxTicks = off;
                }
                // Split: which phase the burst started from + what preceded it.
                if (st.phase == PHASE_HELD) {
                    censusHeld++;
                } else if (st.phase == PHASE_FREE) {
                    censusFreeN++;
                    censusFreeHist[b]++;
                    if (st.lastAmmo >= 1) {
                        censusFreePrev1++;
                    }
                    if (st.wireFiring) {
                        censusFreeLvl++;
                    }
                    long sc = st.consumeTick == Long.MIN_VALUE ? -1L : simTick - st.consumeTick;
                    if (sc >= 0L) {
                        censusFreeScHist[(int) Math.min(sc, censusFreeScHist.length - 1L)]++;
                    }
                }
            }
        }
        st.lastCharge = charge;
        boolean freshOk = st.freshTick != Long.MIN_VALUE
                && simTick >= st.freshTick && simTick - st.freshTick <= staleHorizonTicks();
        if (st.phase == PHASE_FREE) {
            // A local regen grant that lands INSIDE the post-edge committed window
            // (burst + burst-delay ≈ 18 t on the Paladin) sits at ammo 1 and would fire natively
            // at READY (+18 t) — while a starve engagement is recent, re-enter at ammo 1 too so
            // Leg B banks it to the next edge (measured tail: p90=19 t with ammo==0 only).
            boolean recentStarve = st.starveActivityTick != Long.MIN_VALUE
                    && simTick - st.starveActivityTick <= censusRecentTicks();
            if (recentStarve && st.lastAmmo == 0 && localAmmo == 1 && !beamIdle(w, inBurst, charge)) {
                inwinGrants++; // The local regen grant landed mid-burst/cooldown (the tail seed)
            }
            st.lastAmmo = localAmmo;
            // The wire firing LEVEL no longer gates entry — it outlasts the local
            // committed window by the snapshot cadence; a stale level with its edge
            // already USED is a hold, not an edge (the HELD branch keys on the RISING edge).
            if (freshOk && st.wireAmmo == 0
                    && (localAmmo == 0 || (localAmmo == 1 && recentStarve))
                    && beamIdle(w, inBurst, charge)) {
                st.phase = PHASE_HELD; // both sides starved-idle: the engagement opens
                st.entryTick = simTick;
                st.starveActivityTick = simTick;
                if (st.wireFiring) {
                    reentryLvlHigh++;
                }
                if (!activeLogged) {
                    activeLogged = true;
                    PilotWatchLog.info("predict own-beam-cycle ACTIVE: mount=" + idx
                            + " starved-held engagement @tick=" + simTick
                            + (apply ? "" : " (meter)"));
                }
                // FALL THROUGH — the entry frame must also assert. The engine's burst tracker
                // leaves COOLING→IDLE with the fire input forced false, so a
                // held mount at READY with ammo 1 refires on the very NEXT weapon.advance; an
                // entry that only flips phase and returns is one frame late (why the ammo-1 re-entry moved nothing).
            } else {
                return;
            }
        }
        st.lastAmmo = localAmmo;
        st.starveActivityTick = simTick;
        if (st.phase == PHASE_HELD) {
            if (!freshOk) {
                st.phase = PHASE_FREE; // stale wire ⇒ free-run native (never hold blind)
                abortsStale++;
            } else if (inBurst || charge > 0f) {
                st.phase = PHASE_FREE; // a burst is running (committed / meter free-run) — never fight it
            } else if (st.wireAmmo >= 1 || localAmmo >= 2) {
                st.phase = PHASE_FREE; // server standing grant / external refill: not starved-held
            } else if (st.edgeTick != Long.MIN_VALUE && st.edgeTick != st.usedEdgeTick
                    && (st.edgeTick >= st.entryTick || st.wireFiring)) {
                // The RISING edge (unused; at/after entry, or the level still high on a
                // never-used edge = the earlier rule minus re-use). Stop banking; grant if
                // still empty (Leg A), else the bank stands.
                st.usedEdgeTick = st.edgeTick;
                if (localAmmo == 0 && beamIdle(w, inBurst, charge)) { // burst-beam aware
                    edgeGrants++;
                    if (apply) {
                        w.setAmmo(1); // the server already paid this deduct at its own burst start
                    }
                    if (grantLogLines < GRANT_LOG_MAX) {
                        grantLogLines++;
                        PilotWatchLog.info("predict own-beam-cycle edge-grant @tick=" + simTick
                                + ": mount=" + idx + (apply ? "" : " (meter)"));
                    }
                } else if (localAmmo >= 1) {
                    bankReleases++;
                }
                st.phase = PHASE_GRANTED;
                st.grantTick = simTick;
            } else {
                if (st.wireFiring) {
                    staleEdgeSkips++; // Level high on a USED edge = hold, not an edge
                }
                nofireAsserts++;
                if (apply) {
                    w.setForceNoFireOneFrame(true); // Leg B: a leading local grant BANKS
                }
            }
            return;
        }
        // PHASE_GRANTED: awaiting the native restart consume.
        if (inBurst || charge > 0f || w.isFiring()) {
            st.phase = PHASE_FREE;
            st.consumeTick = simTick;
            grantConsumes++;
        } else if (st.grantTick == Long.MIN_VALUE || simTick < st.grantTick
                || simTick - st.grantTick > grantTimeoutTicks()) {
            st.phase = PHASE_FREE; // released press / clock anomaly: fail open, mirror resumes
            grantTimeouts++;
        }
    }

    /** Histogram percentile in ticks (bucket 63 = 63+ overflow); -1 when the census is empty. */
    private int censusPercentile(double p) {
        return percentile(censusHist, censusN, p);
    }

    private static int percentile(int[] hist, long n, double p) {
        if (n <= 0) {
            return -1;
        }
        long need = (long) Math.ceil(p * n);
        long cum = 0;
        for (int b = 0; b < hist.length; b++) {
            cum += hist[b];
            if (cum >= need) {
                return b;
            }
        }
        return hist.length - 1;
    }

    private static long sum(int[] hist) {
        long s = 0;
        for (int v : hist) {
            s += v;
        }
        return s;
    }

    /** Meter tokens for the {@code predict own-beam-cycle} counter line. */
    String meterTokens() {
        return " own_cycle_edges=" + edges
                + " own_cycle_edge_grants=" + edgeGrants
                + " own_cycle_bank_releases=" + bankReleases
                + " own_cycle_nofire_asserts=" + nofireAsserts
                + " own_cycle_grant_consumes=" + grantConsumes
                + " own_cycle_grant_timeouts=" + grantTimeouts
                + " own_cycle_mirror_holds=" + mirrorHolds
                + " own_cycle_aborts_stale=" + abortsStale
                + " own_cycle_census_n=" + censusN
                + " own_cycle_census_p50=" + censusPercentile(0.50)
                + " own_cycle_census_p90=" + censusPercentile(0.90)
                + " own_cycle_census_max=" + (censusN > 0 ? censusMaxTicks : -1L)
                + " own_cycle_census_unref=" + censusUnref
                // Split census + fix faces (which mechanism fired):
                + " own_cycle_reentry_lvl_high=" + reentryLvlHigh
                + " own_cycle_stale_edge_skips=" + staleEdgeSkips
                + " own_cycle_inwin_grants=" + inwinGrants
                + " own_cycle_census_held=" + censusHeld
                + " own_cycle_census_free_n=" + censusFreeN
                + " own_cycle_census_free_p50=" + percentile(censusFreeHist, censusFreeN, 0.50)
                + " own_cycle_census_free_p90=" + percentile(censusFreeHist, censusFreeN, 0.90)
                + " own_cycle_census_free_prev1=" + censusFreePrev1
                + " own_cycle_census_free_lvl=" + censusFreeLvl
                + " own_cycle_census_free_sc_p50=" + percentile(censusFreeScHist, sum(censusFreeScHist), 0.50)
                + " own_cycle_census_free_sc_p90=" + percentile(censusFreeScHist, sum(censusFreeScHist), 0.90);
    }
}
