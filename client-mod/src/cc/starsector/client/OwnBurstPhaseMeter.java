package cc.starsector.client;

import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import java.util.Arrays;
import java.util.List;

/**
 * The OWN-ship fire-chain phase METER: three uncapped edge rows + their counters, pure observation —
 * no weapon / ship / flux / cooldown / ammo / designation write anywhere.
 *
 *
 * <p>WHY, as measured on an Onslaught m20 heavyneedler (burst 30): the server
 * charges a burst's flux up front, the own-flux pin writes it onto the local hull BEFORE the local twin
 * fires, and vanilla autofire then refuses the twin on its flux gate
 * ({@code getFluxCostToFire() > getFluxAvailable()}) — the local burst ran 177-215 ticks late and nothing
 * re-phased it. No local row measured it (a recorded residue: "the local autofire decision is
 * unlogged"). The rows, "measure first, no fix" (never a burst-class retire):
 * <ul>
 * <li>{@code predict ownlock auth-burst-rise} ({@link #onCarriedMount}; caller =
 * {@code FireLaneCoordinator.maintainOwnFireLock}'s FRESH-frame per-carried-mount pass, before that pass's
 * ammo / cooldown writes — so {@code cc.predict.ownFireLock=off} silences it — but AFTER the same call's
 * fresh-frame own-flux pin ({@code ft.setCurrFlux} under {@code OWN_FIRE_LOCK_APPLY}) and the reload-hold
 * mirror's apply / remove. So the row's {@code flux_avail} / {@code gate_held} are POST-pin values: the
 * server's headroom after its up-front burst charge = the gate the local twin will face, never the local
 * pre-pin headroom:
 * the wire mount's charge crossed {@link OwnFireLockLaw#AUTH_BURST_CHARGE_MIN} upward (= the server's
 * burst START, {@link OwnFireLockLaw#authBurstRise}) on a burst &gt; 1, non-beam mount; the row carries
 * the LOCAL mount's cooldown / firing / in-burst and the flux headroom against the burst cost at that
 * instant.</li>
 * <li>{@code predict own-burst-gate start|end} ({@link #gateEdge}; caller = the
 * {@link AutofireTargetAiPlugin#shouldFire} span latch): the local autofire AI refused a READY mount
 * (span start) / fired or left ready (span end).</li>
 * <li>{@code predict own-adopt local-state} ({@link #onAdopt}; caller = {@code executeAdopt} beside the
 * adopted-ring push, once per adjudicated server-only own volley): the local mount's ammo / cooldown /
 * burst / firing — the refire-divergence lens's first act (lane C, 213444 r1 1421); NO class filter
 * (the voidblaster is burst 1 — it is this row's subject).</li>
 * </ul>
 * Counters tail-append to the {@code predict ownlock-meter} line ({@link #meterTokens}); row count =
 * counter identity (rises, adopt rows, gate span STARTs; {@code flux_held} = the starts whose gate read
 * held). The six token names on that line (with the two {@code reload_mirror_*} ones) are the b4 bar's
 * INFO contract — never rename them. The LAST token, {@code ownlock_burst_meter_faults=}, prints the
 * STATIC {@link #faults} (JVM-cumulative across plugin instances and rounds — a reader grades it
 * {@code > 0}, never as a per-round delta): without it a meter that faults every frame reads
 * {@code spans=0 rises=0}, identical to the measurement's null result.
 *
 *
 * <p>Frame residency: the rise costs one array read/write + two float compares per carried
 * mount per FRESH frame (wire cadence), the class + flux reads only on a rising edge; the adopt row is
 * bounded by the server-only count; the gate latch ({@link Gate}, one per wrapper) costs two weapon reads
 * per own burst mount per frame and reads flux / cost at edges only. Rows = edges only, through
 * {@link PilotWatchLog} (the async appender); ints / floats / tick numbers only (no player text).
 *
 * <p>State = own body identity x mount 0..63 ({@code prevCharge} NaN = unseen = the rise's seed,
 * {@code lastAdoptTick} {@code Long.MIN_VALUE} = none), reset through ONE identity {@link #bind} on EVERY
 * entry (the {@code OwnReloadHoldLane} shape): first join, respawn, round wrap / MRST, the post-lift
 * rejoin (new plugin), the RosterAppend re-point. A RESUME keeps the body ⇒ state kept. A throw never
 * escapes into the caller's pass (a meter fault must not skip the fire-lock laws): it counts
 * {@link #faults} and logs once per JVM.
 */
final class OwnBurstPhaseMeter {

    /** Faults across every entry (the wrapper's gate latch included), JVM-cumulative (static: every plugin
     *  instance and round); the first logs once per JVM; the {@code ownlock_burst_meter_faults=} token. */
    static long faults;

    private ShipAPI body;
    private final float[] prevCharge = new float[64];
    private final long[] lastAdoptTick = new long[64];
    long authBurstRises;
    long gateSpans;
    long gateFluxHeld;
    long adoptStateRows;

    OwnBurstPhaseMeter() {
        Arrays.fill(prevCharge, Float.NaN);
        Arrays.fill(lastAdoptTick, Long.MIN_VALUE);
    }

    /** IDENTITY, not equals (the {@code FireLaneCoordinator.bindSeedBody} idiom). */
    private void bind(ShipAPI own) {
        if (own != body) {
            body = own;
            Arrays.fill(prevCharge, Float.NaN);
            Arrays.fill(lastAdoptTick, Long.MIN_VALUE);
        }
    }

    /** One carried own mount on a fresh frame: the auth-burst-rise row (class doc). */
    void onCarriedMount(ShipAPI own, WeaponAPI weapon, int idx,
            cc.starsector.client.net.ServerWeaponMountState mount, long simTick, boolean pendingHere) {
        if (idx < 0 || idx >= 64) {
            return;
        }
        try {
            bind(own);
            float prev = prevCharge[idx];
            float cur = mount.chargeLevel();
            prevCharge[idx] = cur;
            if (!OwnFireLockLaw.authBurstRise(prev, cur) || !burstClass(weapon)) {
                return;
            }
            authBurstRises++;
            float avail = fluxAvailable(own);
            float cost = weapon.getFluxCostToFire();
            PilotWatchLog.info("predict ownlock auth-burst-rise @tick=" + tickText(simTick) + " mount=" + idx
                    + " local_cd=" + weapon.getCooldownRemaining() + " firing=" + (weapon.isFiring() ? 1 : 0)
                    + " in_burst=" + (weapon.isInBurst() ? 1 : 0) + " flux_avail=" + avail + " cost=" + cost
                    + " gate_held=" + (OwnFireLockLaw.fluxGateHeld(cost, avail) ? 1 : 0)
                    + " pending=" + (pendingHere ? 1 : 0) + " last_adopt=" + adoptText(idx));
        } catch (Throwable t) {
            fault(t);
        }
    }

    /** One adjudicated server-only own volley ({@code a.tick}, {@code a.mount}): records the mount's
     *  last adopt tick (the rise / gate rows' {@code last_adopt=}) and prints the local-state row. No own
     *  body or an unresolved mount ⇒ no row (the discharge-reconcile ADOPT SKIP row covers that case). */
    void onAdopt(ShipAPI own, int mount, long tick) {
        if (own == null || mount < 0 || mount >= 64) {
            return; // no body = no subject (and a transient null never resets the live body's state)
        }
        try {
            bind(own);
            lastAdoptTick[mount] = tick;
            List<WeaponAPI> all = own.getAllWeapons();
            WeaponAPI w = all == null || mount >= all.size() ? null : all.get(mount);
            if (w == null) {
                return;
            }
            adoptStateRows++;
            PilotWatchLog.info("predict own-adopt local-state @tick=" + tick + " mount=" + mount
                    + " local_ammo=" + w.getAmmo() + " local_cd=" + w.getCooldownRemaining()
                    + " in_burst=" + (w.isInBurst() ? 1 : 0) + " firing=" + (w.isFiring() ? 1 : 0));
        } catch (Throwable t) {
            fault(t);
        }
    }

    /** One own-burst-gate span edge from the wrapper's latch ({@code start} = the delegate refused a READY
     *  mount; else the span ended). Counts the START (and its held flux gate); prints one row per edge. */
    void gateEdge(WeaponAPI weapon, boolean start, long tick, int mount) {
        ShipAPI own = weapon.getShip();
        bind(own);
        float avail = fluxAvailable(own);
        float cost = weapon.getFluxCostToFire();
        boolean held = OwnFireLockLaw.fluxGateHeld(cost, avail);
        if (start) {
            gateSpans++;
            if (held) {
                gateFluxHeld++;
            }
        }
        PilotWatchLog.info("predict own-burst-gate " + (start ? "start" : "end") + " @tick=" + tickText(tick)
                + " mount=" + mount + " flux_avail=" + avail + " cost=" + cost + " gate_held=" + (held ? 1 : 0)
                + " last_adopt=" + adoptText(mount));
    }

    /** The heavyneedler class both burst rows share: burst &gt; 1 and not a beam. */
    static boolean burstClass(WeaponAPI weapon) {
        WeaponSpecAPI spec = weapon.getSpec();
        return spec != null && spec.getBurstSize() > 1 && !weapon.isBeam();
    }

    /** max − curr (vanilla's engine-internal {@code Ship.getFluxAvailable}); no tracker = NaN (never shown held). */
    private static float fluxAvailable(ShipAPI own) {
        FluxTrackerAPI ft = own == null ? null : own.getFluxTracker();
        return ft == null ? Float.NaN : ft.getMaxFlux() - ft.getCurrFlux();
    }

    long lastAdopt(int mount) {
        return mount < 0 || mount >= 64 ? Long.MIN_VALUE : lastAdoptTick[mount];
    }

    private String adoptText(int mount) {
        long t = lastAdopt(mount);
        return t == Long.MIN_VALUE ? "-" : Long.toString(t);
    }

    private static String tickText(long tick) {
        return tick == Long.MIN_VALUE ? "-" : Long.toString(tick);
    }

    static void fault(Throwable t) {
        if (faults++ == 0) {
            PilotWatchLog.info("predict own-burst-phase meter threw (logged once): " + t.getClass().getSimpleName());
        }
    }

    /**
     * One {@link AutofireTargetAiPlugin}'s own-burst-gate state (per wrapper = per {@code WeaponAPI}; a fresh
     * hull / mission re-mints wrappers ⇒ fresh state; a span open at death has no END row — the reader
     * tolerates an unmatched START). {@link #bind} runs ONCE per frame from the wrapper's {@code advance()}
     * (own-ness, ordinal and tick computed there); {@link #observe} from its {@code shouldFire}
     * only when bound. Neither throws (a fault counts {@link #faults}).
     */
    static final class Gate {
        private OwnBurstPhaseMeter meter;
        private int mount;
        private long tick;
        private boolean span;
        private byte burstClass; // 0 unread, 1 metered class, -1 not (a weapon's spec is fixed)

        /** True ⇔ a live OWN-ship burst &gt; 1 non-beam mount this frame (non-own: one identity compare). */
        boolean bind(AutofireTargetCoordinator coordinator, ShipAPI ship, WeaponAPI weapon) {
            OwnBurstPhaseMeter m = null;
            try {
                int ord = coordinator == null || ship == null ? -1 : coordinator.ownMountOrdinal(ship, weapon);
                if (ord >= 0 && ord < 64 && burstClass == 0) {
                    burstClass = (byte) (burstClass(weapon) ? 1 : -1);
                }
                if (ord >= 0 && ord < 64 && burstClass > 0) {
                    m = coordinator.burstMeter();
                    mount = ord;
                    tick = coordinator.snapshotTick();
                }
            } catch (Throwable t) {
                m = null;
                fault(t);
            }
            if (m == null) {
                span = false; // not metered this frame: a later span starts fresh (no stale END row)
            }
            meter = m;
            return m != null;
        }

        /** The span latch: START when the mount is READY (lane B: {@code cdRem <= 0}, not in burst) and the
         *  delegate refused; END when it fires or leaves ready. One row per edge, never per frame; reads only. */
        void observe(WeaponAPI weapon, boolean v) {
            try {
                boolean ready = weapon.getCooldownRemaining() <= 0f && !weapon.isInBurst();
                if (!span && !v && ready) {
                    span = true;
                    meter.gateEdge(weapon, true, tick, mount);
                } else if (span && (v || !ready)) {
                    span = false;
                    meter.gateEdge(weapon, false, tick, mount);
                }
            } catch (Throwable t) {
                fault(t);
            }
        }
    }

    /** The counter tokens, tail-appended to the {@code predict ownlock-meter} line (leading space); the last,
     *  {@code ownlock_burst_meter_faults=}, is the STATIC JVM-cumulative {@link #faults} (graded {@code > 0},
     *  never a per-round delta — class doc). */
    String meterTokens() {
        return " ownlock_auth_burst_rises=" + authBurstRises
                + " ownlock_burst_gate_spans=" + gateSpans
                + " ownlock_burst_gate_flux_held=" + gateFluxHeld
                + " ownlock_adopt_state_rows=" + adoptStateRows
                + " ownlock_burst_meter_faults=" + faults;
    }
}
