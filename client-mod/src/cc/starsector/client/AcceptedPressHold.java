package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipSystemSpecAPI;

/**
 * Dwell-proof system-press relay, client half. The up-lane system bits are LEVEL mirrors of the
 * engine's own accepted state ({@code isOn()} = IN|ACTIVE exactly),
 * but a zero-duration system has no IN|ACTIVE dwell to mirror: with {@code in = active = out = 0}
 * the charge tracker walks IDLE → IN → ACTIVE → OUT → COOLDOWN inside ONE advance call (every
 * stage's elapsed ≥ duration test passes at once), so a
 * sample taken after the ship's advance reads COOLDOWN and never sees the window. The press never
 * rides up the wire and the server never fires the system.
 *
 * <p>Two measured specimens, one per slot:
 * <ul>
 * <li><b>DEFENSE</b> (was {@code DefensePressHold}): invictus
 * {@code canister_flak}, a WEAPON-type defense the engine stores in the phaseCloak FIELD —
 * {@code bit11Frames=0} across a whole test session of right-click spam.
 * <li><b>PRIMARY</b>: the four flare launchers ({@code type:WEAPON}, engine class
 * {@code S}). Measured live: every Atlas / Apogee flare press was a
 * client-only picture — zero seat {@code pilot-sysint} rows, zero {@code flare_seeker} ids in the
 * reel, while the local {@code isActive()} (S overrides it to "a system weapon is firing") showed
 * the window the {@code isOn()} bit never carried.
 * </ul>
 *
 * Fix shape, both slots: detect the local engine's ACCEPTED press and synthesize
 * {@link #HOLD_NANOS} of bit dwell so the existing wire bit carries it. Wall-clock hold (not
 * frames) because render fps is unbounded (a measured client runs 540); ~100 ms ≈ 6 server ticks, far under
 * the specimens' 3–5 s cooldowns, so the server presses once and the engine refuses the tail — no
 * double fire, and no cancel edge (bit-low + server {@code isOn()} false compare equal). Only
 * ENGINE-ACCEPTED local presses relay: a press the local sim refused (cooldown/ammo) moves
 * neither ammo nor state. No wire schema change — same bit vocabulary, same frame, no new decode
 * surface (the wire-input inventory unchanged by design).
 *
 * <p><b>Slot gates differ on purpose.</b> DEFENSE relays for every non-phase-cloak defense, on
 * an ammo decrement OR the IDLE-exit edge (reconcile never writes the own cloak's ammo).
 * PRIMARY relays ONLY for a zero-dwell spec (non-toggle, {@code in <= 0}, {@code
 * active <= 0} — the tracker's own pass-through condition) and ONLY on the IDLE-exit edge:
 * (1) dwell systems already carry their press through {@code isOn()} — widening the relay buys
 * nothing and only adds exposure to a state WRITE reading as a press (no lane writes the own
 * primary's state today; the wire-slaved TRANSIT window is covered by {@link #forget()});
 * (2) the drone launchers are zero-dwell too but deduct
 * ammo per DRONE LAUNCHED, not per press ({@code usesAmmoForActivation()==false}),
 * so an ammo leg would relay a phantom order-cycle per launch. Known
 * residual: a zero-dwell system with ZERO cooldown reads IDLE → IDLE and stays invisible here
 * (vanilla = the drone launchers' order cycling — its own lane, not this relay).
 *
 * Own-file per the PredictReconcileCombatPlugin growth-anchor law:
 * PRCP carries only the two fields + one sample call per capture site.
 */
final class AcceptedPressHold {

    /** Synthesized dwell per accepted press, wall-clock (~6 server ticks at 60/s). */
    static final long HOLD_NANOS = 100_000_000L;

    private final boolean primarySlot;
    private ShipSystemAPI lastSystem;
    private int lastAmmo = Integer.MIN_VALUE;
    private boolean lastIdle;
    private long holdUntilNanos;
    private int presses;

    private AcceptedPressHold(boolean primarySlot) {
        this.primarySlot = primarySlot;
    }

    /** The right-click slot ({@code getPhaseCloak()}): every non-phase-cloak defense relays. */
    static AcceptedPressHold forDefenseSlot() {
        return new AcceptedPressHold(false);
    }

    /** The primary slot ({@code getSystem()}): zero-dwell non-toggle specs only, state edge only. */
    static AcceptedPressHold forPrimarySlot() {
        return new AcceptedPressHold(true);
    }

    /**
     * Once per capture frame from the PRCP bit-7 / bit-11 site. Returns true while dwell is being
     * synthesized; always false for null systems and for specs the slot's gate excludes.
     */
    boolean sample(ShipSystemAPI system) {
        return sample(system, System.nanoTime());
    }

    /** {@link #sample(ShipSystemAPI)} with the clock injected (PressHoldLawSmoke). */
    boolean sample(ShipSystemAPI system, long nowNanos) {
        try {
            if (system == null) {
                lastSystem = null;
                holdUntilNanos = 0L;
                return false;
            }
            ShipSystemSpecAPI spec = system.getSpecAPI();
            if (spec == null || !relays(spec)) {
                return false;
            }
            int ammo = system.getAmmo();
            boolean idle = system.getState() == ShipSystemAPI.SystemState.IDLE;
            if (system != lastSystem) {
                // new own ship / re-pointed system object: re-baseline, never fire on adopt
                lastSystem = system;
                lastAmmo = ammo;
                lastIdle = idle;
                holdUntilNanos = 0L;
                return false;
            }
            boolean pressed = (lastIdle && !idle) || (!primarySlot && ammo < lastAmmo);
            lastAmmo = ammo;
            lastIdle = idle;
            if (pressed) {
                presses++;
                holdUntilNanos = nowNanos + HOLD_NANOS;
            }
            return holdUntilNanos != 0L && nowNanos < holdUntilNanos;
        } catch (Throwable ignore) {
            // capture-site rider; a read hiccup must never cost the movement mask
            return false;
        }
    }

    private boolean relays(ShipSystemSpecAPI spec) {
        if (!primarySlot) {
            return !spec.isPhaseCloak();
        }
        return !spec.isToggle() && spec.getIn() <= 0f && spec.getActive() <= 0f;
    }

    /**
     * Drop the baseline + any live hold: the next sample re-adopts and can never fire. Called
     * every frame the capture site is NOT sampling a natively-flown hull (the transit input
     * gate) — a state the wire wrote during the gap must not read as a local press.
     */
    void forget() {
        lastSystem = null;
        holdUntilNanos = 0L;
    }

    /** Cumulative accepted-press count (CanisterFlakProbe row / capture summary). */
    int presses() {
        return presses;
    }
}
