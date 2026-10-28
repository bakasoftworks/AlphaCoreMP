package cc.starsector.client;

import cc.starsector.client.PredictReconcileCombatPlugin.PredictLane;

/**
 * The predict plugin's class-load knob table: every {@code cc.predict.*} JVM-property knob
 * {@link PredictReconcileCombatPlugin} and its collaborators read, parsed and validated ONCE into
 * static finals, beside the compile-time tuning constants that share their javadoc. An invalid
 * knob value THROWS here, at class load, never lazily inside a frame-loop catch.
 *
 * <p><b>Init order.</b> The plugin's static block calls {@link #load()} at the slot this table
 * used to hold, so the plugin's own class init still runs every parse below, in the same order,
 * and a bad value still fails the plugin's class load: the {@link ExceptionInInitializerError}
 * propagates out of the plugin's initializer with the parse's {@link IllegalArgumentException}
 * (naming the knob) as its cause. A reader that touches a non-constant knob before the plugin
 * loads (a game-free test) initializes only this holder. The plugin reads the names unqualified
 * through a static import; every other reader qualifies them as {@code PredictKnobConfig.X}.
 *
 * <p>The table moved here byte-for-byte; only {@code private} was dropped on the fields and the
 * links that name a plugin member were re-targeted.
 *
 */
final class PredictKnobConfig {

    private PredictKnobConfig() {}

    /**
     * No-op: calling it forces this class's initialization (the whole table below parses, and a
     * bad value throws), which is all the plugin's static block needs.
     */
    static void load() {}

    /**
     * The foe's local hull FLOOR as a fraction of max hull (the damage-CAP, axis B): a local
     * hit that WOULD bring the foe to or below this is capped (via {@link FoeDamageCap}) so the foe lands
     * here, ALIVE, instead of dying client-side — the real death is gated strictly on the authoritative
     * edge ({@link OwnSeatLifecycleLane#killFoe}). Small so the bar reads "critical but alive" before the server's kill blow.
     * WATCH-tunable.
     */
    static final float FOE_HULL_FLOOR_FRAC = 0.05f;
    /**
     * Snap escape-hatch: if the position error exceeds this distance the prediction has
     * forked beyond smoothing, so snap to the authoritative pose instead of gliding. Set well beyond the
     * 60 u ship radius and the ~12 u (1/5-radius) sub-visible budget, so it is the RARE large-error case,
     * not the normal path. Stored squared to avoid a per-frame sqrt.
     */
    static final float SNAP_DISTANCE = 300f;
    static final float SNAP_DISTANCE_SQ = SNAP_DISTANCE * SNAP_DISTANCE;
    /**
     * STEP-16 contact/step-change reconcile (default-ON; kill
     * switch {@code -Dcc.predict.stepRebase=false}). The own-ship reconcile is POSITION-only glide, so a
     * single-tick velocity step the server's collision handler imparts — which the client cannot
     * co-experience, its foe sitting at a slightly diverged pose — leaves a persistent velocity error the
     * glide converts into a PHANTOM DRAG with no local physical cause (measured up to 69.3 u/s;
     * non-contact per-tick |Δv| never exceeded 3.52). On each FRESH snapshot
     * the authoritative own-ship velocity is diffed against the previous fresh frame's, per sim tick; past
     * this threshold (0 false positives over 5361 non-contact frames; 42% above the measured non-contact
     * ceiling, 14x below the max contact step) the LOCAL velocity is re-based ONCE to the server's real
     * engine-computed value — the same write the foe lane makes every frame. Position glide + SNAP_DISTANCE
     * hatch + facing (the fix-B wire pin) stay untouched.
     *
     */
    static final boolean STEP_REBASE_ENABLED = !"false".equals(System.getProperty("cc.predict.stepRebase"));
    static final float STEP_REBASE_THRESHOLD = 5f; // u/s per sim tick on the AUTHORITATIVE velocity
    /** Per-event log cap (contacts measured rare — 17 frames >5 in a full WATCH; this only guards a misfire flood). */
    static final int STEP_REBASE_LOG_MAX = 50;
    /**
     * The own-system BURST-WINDOW EXEMPTION
     * (measured on a recorded run; law + full rationale: {@link BurstWindowLaw}). Kill switch
     * {@code -Dcc.predict.burstExempt=false}. In a live window (the DD5 latch, promoted from
     * report-only): STEP-16 velocity overwrites are SUPPRESSED (detector/baseline/meters keep
     * running), the item-④ glide-escape streak FREEZES, and the own position glide drops to the
     * {@link BurstWindowLaw#TETHER_RATE_PER_SEC} weak tether. The 300-su snap hatch stays ARMED
     * throughout; outside the window every lane is unchanged (global loosen REFUSED — the
     * parked-auth ESCAPE class keeps its guards).
     */
    static final boolean BURST_EXEMPT_ENABLED = !"false".equals(System.getProperty("cc.predict.burstExempt"));
    /**
     * The AUTHORITY-GATED OWN-VELOCITY REBASE-AND-HOLD lane, knob
     * {@code cc.predict.ownVelReconcile} = {@code off|meter|apply} (compiled default
     * {@code apply} — re-cut after the
     * revert of the 08-20 "shield ram" lane: the LAW is back on, the 240 su/s glide
     * cap runs on ENGAGED frames ONLY (its original shape) — the all-frames cap was what turned a
     * small shield-ram correction into a slow slide; the law is what keeps a real divergence off
     * the 300-su hatch; {@code off} = the prior own-reconcile lattice byte-for-byte (STEP-16 /
     * glide / escape / 300-su hatch, no law writes, no cap) and stays the rollback lever;
     * invalid values THROW at class load). The teleport root it fixes:
     * per-side NATIVE-COLLISION outcome divergence opens a continuous own-velocity gap inside
     * a burst window, where EVERY existing backstop disarms at once (STEP-16
     * burst-suppressed, glide control/cap-closed, escape frozen, position glide at the weak
     * tether), so the residual grows to the 300-su hatch (felt teleport #1) and the burst-end
     * haul-back (felt teleport #2). This lane's gate reads the local-vs-auth GAP beyond
     * thruster authority — law, calibration and full rationale: {@link OwnVelReconcileLaw}.
     * {@code meter}: the law advances and engage edges/would-hold frames are counted, no
     * writes and NO standdowns (the existing owners keep the lanes; NB a metered hold never
     * collapses the gap it measures, so engaged episodes read longer than apply would run).
     * {@code apply}: while engaged (engage frame INCLUDED) one capped-slew velocity write per
     * fresh frame toward the auth velocity ({@code OwnVelReconcileLaw#slewStep} —
     * sub-felt by construction; the one-shot rebase + exponential hold RETIRED as the
     * felt micro-snap authors); while engaged STEP-16/glide/escape stand down
     * ({@link PredictReconcileCombatPlugin#ownVelReconEngaged} — exactly-one-owner), the weak tether is voided and the own
     * position glide runs RATE-CAPPED ({@code OwnVelReconcileLaw#cappedGlideAlpha};
     * an earlier revision had extended the cap to ALL frames against the burst-end haul-back —
     * withdrawn to engaged-only, the felt cost outweighed it). Engage
     * counting waits on the fly-in ARM latch, as later re-cut (THREE consecutive
     * converged fresh frames — the single witness armed at the spawn instant).
     * The 300-su hatch stays armed as last resort.
     */
    static final String OWN_VELRECON_MODE = PredictLane.parseMode(
            "cc.predict.ownVelReconcile", System.getProperty("cc.predict.ownVelReconcile", "apply"));
    static final boolean OWN_VELRECON_METER = !"off".equals(OWN_VELRECON_MODE);
    static final boolean OWN_VELRECON_APPLY = "apply".equals(OWN_VELRECON_MODE);
    /** Engage-edge log cap (contact-era engages are seconds-scale; this only guards a gate-flap flood). */
    static final int OWN_VELRECON_LOG_MAX = 40;
    /**
     * Drift-gated own-ship VELOCITY GLIDE,
     * knob {@code cc.predict.velocityGlide} = {@code true|false} (default ON — live confirm
     * piggybacks the next WATCH; invalid values THROW; every corrective behind its own
     * toggle). The own-ship reconcile is POSITION-only by design (velocity+facing stay local for
     * zero-RTT control), so a latency-transient velocity residual FREEZES while both engines coast
     * — STEP-16 watches the AUTH velocity's own step, never the local-vs-auth gap, so it cannot
     * re-base a drift-phase decouple (measured frozen 6.4 u/s for 30 s, HUD-numbers-only). This
     * glides the LOCAL velocity toward the authoritative one ONLY while no VELOCITY-AFFECTING
     * control is held (gate-exempt: pure rotation since 2026-06-12, the ship-system bit 7 since
     * 2026-06-13, and the vent-window state bit 8 since 2026-07-01 — basis per
     * {@link VelocityGlideLaw#VELOCITY_NEUTRAL_MASK}) AND the authoritative copy is
     * coasting too — it cannot fight player input by
     * construction, and the drive-in/fork class (gap &gt; 40 u/s) stays with the SNAP lane — an
     * in-coast above-cap gap that PERSISTS ~1 s (which NO lane owned)
     * takes one snap-lane write via {@link PredictReconcileCombatPlugin#maintainGlideEscape}. Gate,
     * cap, and rate live in {@link VelocityGlideLaw} (pure), pinned offline against the real T1 +
     * TD1 recordings by an offline verifier (drift authStep measured exactly 0;
     * the T1 frozen gap converges &lt;0.5 u/s in 0.85 s on the real 30.7 s coast).
     */
    static final boolean VELOCITY_GLIDE_ENABLED = parseVelocityGlide(
            System.getProperty("cc.predict.velocityGlide", "true"));
    /** Engage-edge log cap (a coast entry is seconds-scale; this only guards a gate-flap flood). */
    static final int VELOCITY_GLIDE_LOG_MAX = 30;

    private static boolean parseVelocityGlide(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(v)) {
            return true;
        }
        if ("false".equals(v)) {
            return false;
        }
        throw new IllegalArgumentException("cc.predict.velocityGlide must be true|false, got: " + raw);
    }

    /**
     * A multi-ship mission start lands PAUSED in the command map (the
     * 1-ship recipe never hit this; the owner cleared it with SPACE then TAB). When the bots
     * registry is engaged this unpauses the engine ONCE on the first paused frame so an unattended
     * run (and the TD2 show start) advances promptly; the command map itself stays up — combat
     * runs under it and a present owner leaves it with TAB. Compiled ON (E3 hardwire;
     * bots shapes only, one-shot — a later deliberate pause is never fought;
     * the {@code cc.predict.botsAutoUnpause} knob-resolved shape is in git).
     */
    static final boolean BOTS_AUTO_UNPAUSE = true;
    /**
     * The shared-time contract's tier-1 enforcement rung.
     * Single-player Starsector hands the local player a hard
     * pause (ESC, the command/tactical map); in shared real time NO client owns the clock, so a
     * non-owner client's instinctive pause would let the server march on while THIS engine sits dark
     * (the {@code advance()} body early-returns on {@code isPaused()}) and then snap violently on
     * unpause. When this guard is ON it welds the client engine to the shared clock — the local engine
     * is never allowed to STAY paused; the ESC/command overlay still SHOWS (presentation-only) but
     * combat keeps running underneath. Compiled ON (E3 hardwire: the
     * compiled default was {@code false} but EVERY shipping launcher forced
     * {@code -Dcc.predict.sharedTimeGuard=true}, so live=ON — the launcher forces died in the same
     * commit; SOURCE-faithful, not a server-side reimplementation; subsumes the bots one-shot
     * above; the {@code cc.predict.sharedTimeGuard} knob-resolved shape is in git). The contract's
     * banned-tier feel calls (phase slow-mo, combat speed-up) and the host-retains-pause question
     * are owner-priced and NOT decided here.
     */
    static final boolean SHARED_TIME_GUARD = true;
    /**
     * The low-HP hit-FX RESTORE knob, {@code cc.predict.lowHpFxRestore} =
     * {@code on|off} (default {@code on}: when a hull cap clamps a would-be-lethal hit, the cap —
     * also a {@link DamageListener} — inflates the engine's own applied-damage RECORD back to the
     * uncapped hull figure at the post-state pre-presentation seam and tops up the eaten portion's
     * debris ({@link HullCapCore#restoreFx}), so a big shot at the death's door sounds/reads like
     * the hit it was instead of a plink; {@code off} = the capped-record plink back, the rollback
     * lever). Compiled ON (E3 hardwire; the
     * {@code cc.predict.lowHpFxRestore} knob-resolved shape is in git).
     */
    static final boolean LOW_HP_FX_RESTORE = true;
    /**
     * The leg-1c damage-witness lane (watch-only per-shell/per-hit
     * telemetry; default ON — desync logging stays unimpeded by rule, so no off|meter|apply
     * mode row). All logic lives in {@link DamageWitness} (the 0232(a) new-lane law);
     * every touch here is a thin hook.
     */
    static final boolean DMG_WITNESS_ENABLED = !"false".equals(System.getProperty("cc.predict.dmgWitness"));
    /**
     * tick-pin gate for "running maxFluxDelta increased" lines: a new max must clear the
     * last PINNED value by this step to log (one Hellbore discharge quantum ≈ 27pp sits far above; startup
     * creep below), and the line count is hard-capped — the named recording limit.
     */
    static final float FLUX_PIN_MIN_STEP = 0.02f;
    static final int FLUX_PIN_LOG_MAX = 50;
    /**
     * schema-v6 foe ENGINE-STATE lane, knob {@code cc.predict.foeEngineState} =
     * {@code off|meter|apply} (default {@code apply} since the E2 flip; correctives stay
     * toggleable). {@code meter}: on the snapshot-tick clock (the counter pattern),
     * count agree/disagree of the LOCAL foe controller's derived 3-state code vs the carried
     * authoritative {@code controllerState} — sizing the divergence BEFORE any write ships.
     * {@code apply}: additionally drive the carried per-engine disabled bits through the REAL
     * local mechanism — engine i server-disabled && !local.isDisabled() ⇒ {@code disable(perm)};
     * server-clear && local.isDisabled() && !local.isPermanentlyDisabled() ⇒ {@code repair()} —
     * and let the local controller DERIVE flameout/recovery itself (NO {@code forceFlameout}:
     * one-shot, recovery would not track). {@code off}: pre-v6 behavior.
     * Invalid values THROW at class load (once silently tolerated — the one
     * knob that predated the knobs.md THROW discipline).
     */
    static final String FOE_ENGINE_STATE_MODE = PredictLane.parseMode(
            "cc.predict.foeEngineState", System.getProperty("cc.predict.foeEngineState", "apply"));
    static final boolean FOE_ENGINE_METER = !"off".equals(FOE_ENGINE_STATE_MODE);
    static final boolean FOE_ENGINE_APPLY = "apply".equals(FOE_ENGINE_STATE_MODE);
    /** Per-event log cap for the engine-state lane (flameouts are rare, seconds-scale). */
    static final int FOE_ENGINE_LOG_MAX = 60;
    /**
     * schema-v4 WEAPON-STATE reconcile lane, knob {@code cc.predict.weaponState}
     * = {@code off|meter|apply} (default {@code apply} since the flip —
     * default==live; join-cc pinned apply already). The
     * defect it exists for: locally-presented foe fire deals real local mount damage, the local
     * engine disables the weapon (w1 {@code getDisabledDuration} 6.147 s), and
     * NOTHING corrected it — while the authoritative per-mount {@code disabled} bit was on the wire
     * every frame saying {@code false} (recorded-bytes proof: {@code CcwrWeaponMountDump}).
     * {@code meter}: per fresh frame, per own+foe mount, count agree/disagree of local
     * {@code WeaponAPI.isDisabled()} vs the carried bit. {@code apply}: additionally reconcile a
     * disagreeing mount through the engine's REAL mechanism — server-disabled && !local ⇒
     * {@code disable()}; server-clear && local-disabled && !permanently-disabled ⇒ {@code repair()}
     * (a locally-perm-disabled mount is left alone, the v6 lane's no-resurrect guard). Mount
     * pairing is the server bridge's {@code getAllWeapons()} ordinal — both sides instantiate the
     * same variant (the v6 spec-order contract class). Invalid values THROW at class load.
     */
    static final String WEAPON_STATE_MODE = PredictLane.parseMode(
            "cc.predict.weaponState", System.getProperty("cc.predict.weaponState", "apply"));
    static final boolean WEAPON_STATE_METER = !"off".equals(WEAPON_STATE_MODE);
    static final boolean WEAPON_STATE_APPLY = "apply".equals(WEAPON_STATE_MODE);
    /** Per-event log cap for the weapon-state lane (mount disables are rare, seconds-scale). */
    static final int WEAPON_STATE_LOG_MAX = 60;

    /**
     * The BEAM-PRESENTATION lane, knob {@code cc.predict.beamPresentation} =
     * {@code off|meter|apply} (default {@code apply} — the beams commission ships ON; writes are
     * presentation-only, replay-driven hulls exclusively). Two halves, both per RENDER frame off
     * the hold-last frame (never fresh-gated: the engine's per-weapon force flag self-clears each
     * frame and beam sustain requires per-frame re-assert). RELAY {@link BeamPresentationLane#maintainBeamPresentation}:
     * a wire mount with {@code FLAG_FIRING} whose LOCAL weapon {@code isBeam()} gets
     * {@code setForceFireOneFrame(true)} — the authoritative trigger relayed into the local
     * engine's OWN beam fire cycle; the flag is consumed inside {@code weapon.advance()} and so
     * beats the ship-wide hold-fire re-assert; a dropped wire
     * flag simply stops the re-assert and the local beam runs its native chargedown. Beams ONLY —
     * force-firing a non-beam mount would double-present against the discharge replay lane. AIM
     * {@link FoeStateLaneCoordinator#maintainBeamAim}: each v12 sourced BEAM record steers its mount at the
     * authoritative line ({@code setSuspendAutomaticTurning(true)} + {@code setCurrAngle} of the
     * local-muzzle→wire-impact bearing); the local beam then terminates by its own collision
     * against reconciled hulls. {@code meter} counts both halves without writing. Invalid values
     * THROW at class load.
     */
    static final String BEAM_PRESENTATION_MODE = PredictLane.parseMode(
            "cc.predict.beamPresentation", System.getProperty("cc.predict.beamPresentation", "apply"));
    static final boolean BEAM_PRESENTATION_METER = !"off".equals(BEAM_PRESENTATION_MODE);
    static final boolean BEAM_PRESENTATION_APPLY = "apply".equals(BEAM_PRESENTATION_MODE);
    /** Per-event log cap for the beam lane (aim-resolve failures are the loud class). */
    static final int BEAM_LOG_MAX = 40;

    /**
     * The BEAM-CYCLE burst-lifecycle feed, knob {@code cc.predict.beamCycle}
     * = {@code off|apply} (default {@code apply}). The (114)(e) free-space residual face: the
     * server beam is live (wire FLAG_FIRING) while the local replica sits in refire COOLDOWN —
     * armed-no-ray windows (W17 8.6% / W18 3.3% of arms), each missed/late restart
     * re-appearing up to ~1.4k su away at sweep rate = a beam that snaps around instead of moving
     * continuously. Fix at the relay seam
     * ({@link BeamPresentationLane#maintainBeamPresentation}): per wire-FIRING replica BEAM
     * mount, mirror ammo + zero the cycle cooldown so the per-frame force-fire restarts the
     * burst through the engine's OWN READY path;
     * chargedown/chargeup stay engine-native (vanilla-shaped residual gap). Zero-wire, zero
     * server cost (the 20+-Paladin condition); wire-FIRING only ⇒ no autonomous bursts.
     * Rides the beam-presentation lane (acts only where the relay runs). Meters
     * {@code beam_cycle_*} on the v12 beam-meter line. Invalid values THROW at class load
     * (the {@code RemoteAimLane} local two-value idiom).
     */
    static final String BEAM_CYCLE_MODE = parseBeamCycleMode(
            "cc.predict.beamCycle", System.getProperty("cc.predict.beamCycle", "apply"));
    static final boolean BEAM_CYCLE_APPLY = "apply".equals(BEAM_CYCLE_MODE);

    /** Validated {@code off|apply} parse; anything else THROWS at class load (knobs.md law —
     *  the {@code RemoteAimLane}/{@code SameSideAimLane} local two-value idiom). */
    private static String parseBeamCycleMode(String knob, String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("off".equals(value) || "apply".equals(value)) {
            return value;
        }
        throw new IllegalArgumentException(knob + " must be off|apply, got: " + raw);
    }

    /**
     * The FOE-INTERP lane, knob {@code cc.predict.foeInterp} =
     * {@code off|meter|apply} (default {@code meter}: buffer fed + measured, no behavior
     * change — keeps the pending beam/teleport WATCHes single-variable, the 1275 idiom).
     * The defect class it exists for (Gap A of the netcode design): dead-reckon renders the
     * foe at the LATEST pose + carried velocity, so delivery jitter lands as per-frame
     * pose steps the 155.3/s glide must eat; {@code apply} instead renders the foe a
     * ramped ~2 measured intervals in the past off the {@link FoeInterpLane} buffer —
     * the interpolated pose substitutes the reconcile TARGET at the two
     * {@code reconcileFoe} seams, while the glide/SNAP law and every
     * {@code maintainFoe*} state lane keep reading the live snapshot (kinematics only
     * are time-shifted, I1). Foe SHIPS only (I2) — own ship never (zero-RTT),
     * hulks keep the 1272 coast law, missiles fly natively, replica beams emit from the
     * rendered pose so they follow free. Per-run static; NO auto jitter switcher in v1
     * (I6 — meter mode mints the threshold numbers first). Invalid values THROW at
     * class load.
     */
    static final String FOE_INTERP_MODE = PredictLane.parseMode(
            "cc.predict.foeInterp", System.getProperty("cc.predict.foeInterp", "meter"));
    static final boolean FOE_INTERP_METER = !"off".equals(FOE_INTERP_MODE);
    static final boolean FOE_INTERP_APPLY = "apply".equals(FOE_INTERP_MODE);

    /**
     * The FOE-FACING lane, knob {@code cc.predict.foeFacing} =
     * {@code off|meter|apply} (compiled default {@code apply} since the E2 flip; the angular
     * meters run in every non-off mode). The defect it exists for (C5): the station's facing target only
     * crosses a 0.5° wire quantum every 15 ticks, so the 155.3/s glide reproduces a 4 Hz
     * staircase over an exactly-carried ω the engine would have integrated smoothly.
     * {@code apply} adds the sub-quantum soft deadband; {@code meter} measures what it WOULD
     * have changed ({@code altered_frames}) plus the client's first angular telemetry. Law,
     * preconditions and cost: {@link FoeFacingLane}. Invalid values THROW at class load.
     */
    static final String FOE_FACING_MODE = PredictLane.parseMode(
            "cc.predict.foeFacing", System.getProperty("cc.predict.foeFacing", "apply"));
    static final boolean FOE_FACING_METER = !"off".equals(FOE_FACING_MODE);
    static final boolean FOE_FACING_APPLY = "apply".equals(FOE_FACING_MODE);

    /**
     * The OWN-ship ENGINE-STATE lane, knob {@code cc.predict.ownEngineState} =
     * {@code off|meter|apply} (default {@code apply} since the E2 flip). The
     * meter-first shipping condition is MET: rig recorded 548/5050 fresh frames
     * disagreeing while the SERVER own ship fully flamed out and the local replica kept partial
     * engines and kept maneuvering (per-JVM engine-hit rolls; the own lane was never
     * wire-corrected) — the A4 covered-diverging velocity/angular window sat exactly on that fork.
     * {@code meter}: the own-engine compare (local derived 3-state vs
     * carried {@code controllerState}), formerly gated with the weapon-state knob. {@code apply}:
     * additionally drive the carried per-engine disabled bits through {@link FoeStateLaneCoordinator#applyEngineState} —
     * the foe lane's ship-agnostic real disable/repair path, ITEM C mid-flameout settle guard
     * included — plus the own-hull spawn-settle DISABLE defer (the weapon lane's sub-lane 4 rule:
     * a fresh hull at the SAME wire id must not inherit a residual carried mask on the reappear
     * frame). NO boost-law suppression anywhere (measurement proved the boost law innocent). Invalid
     * values THROW at class load.
     */
    static final String OWN_ENGINE_STATE_MODE = PredictLane.parseMode(
            "cc.predict.ownEngineState", System.getProperty("cc.predict.ownEngineState", "apply"));
    static final boolean OWN_ENGINE_METER = !"off".equals(OWN_ENGINE_STATE_MODE);
    // package-visible: ReplicaCompDamageShield's own-hull face gates on it
    static final boolean OWN_ENGINE_APPLY = "apply".equals(OWN_ENGINE_STATE_MODE);

    /**
     * The foe SYSTEM-WINDOW reconcile lane, knob {@code cc.predict.foeSystemState} =
     * {@code off|meter|apply} (default {@code apply} since the E2 flip; rolled out v6-engine-lane/v4-weapon-lane-style —
     * MEASURE before any write ships). The defect it exists for: the foe replica runs an
     * {@code InertShipAI} (now {@link SubstepHoldAI}) and NEVER activates its own ship system, so an authoritative jets/burn-drive
     * window is counted-never-presented — a measurement found 44/44 in-window ticks mispredicted (local
     * {@code IDLE} vs authoritative {@code IN/ACTIVE/OUT}, turnAccel Δ150, effectLevel 1 vs 0), the
     * player-felt "enemy jets invisible". The already-arriving {@code SYSTEM_WINDOW} ON/OFF edges
     * ({@code GameplayEdgeEvent}, wire-complete + timely) carry exactly the engine's
     * {@code isActive()} boundary; phases/effectLevel are content-driven ({@code ship_systems.csv}) the
     * client already owns. {@code meter}: on the snapshot-tick clock, count agree/disagree of the foe
     * replica's local {@code getSystem().isActive()} vs the derived authoritative window-open state
     * (latest ON/OFF edge). {@code apply}: additionally drive the replica's OWN system through the
     * engine's REAL mechanism — while the authoritative window is open and the local system is not yet
     * active and {@code canBeActivated()}, {@code ShipAPI.useSystem()} (the held-key buffered-input path,
     * mirror of the server's {@code EngineCombatBinding.applySystemBit}; NOT {@code forceState} — the
     * faithful path). The local engine then runs IN/ACTIVE/OUT/COOLDOWN + effectLevel + stat mods itself; NO
     * new wire schema, NO server change; foe MOTION stays server-authoritative via {@link FoeStateLaneCoordinator#reconcileFoe}
     * (the inert replica issues no thrust, so the jets stat boost is FX/effectLevel-faithful, never
     * independent motion). Invalid values THROW at class load.
     */
    static final String FOE_SYSTEM_STATE_MODE = PredictLane.parseMode(
            "cc.predict.foeSystemState", System.getProperty("cc.predict.foeSystemState", "apply"));
    static final boolean FOE_SYSTEM_METER = !"off".equals(FOE_SYSTEM_STATE_MODE);
    static final boolean FOE_SYSTEM_APPLY = "apply".equals(FOE_SYSTEM_STATE_MODE);
    /** The foe system-state lane's rows carry {@code e=}
     *  and are budgeted PER ENTITY ({@link PerKeyLogBudget}): 40 rows each, 64 entities, 1500 in all
     *  (was one global 60 — a busy hull's per-frame rows starved every later window). */
    static final int FOE_SYSTEM_LOG_MAX = 1500;
    static final int FOE_SYSTEM_LOG_PER_ENTITY = 40;
    static final int FOE_SYSTEM_LOG_ENTITIES = 64;

    /**
     * OWN-CARRIER LOCAL BAY-MINT suppression, knob {@code cc.predict.ownBayMint}
     * = {@code off|meter|apply} (default {@code apply}; {@code meter} = no write, same as off — the
     * shared three-value parser, kept for knob-shape uniformity). The gap: fighters
     * are WIRE-born roster replicas (adopt-from-wire FORCED), and the
     * only local-mint guard was the replay-driven carrier lock ({@link ReplicaBodyLane#registerReplayDriven}) — the
     * PILOTED hull's own bays were ungated, so a carrier seat (Anubis) would mint LOCAL wings on top
     * of the server-born replicas. Lever = the engine's own bay-advance kill switch
     * {@code ship.A.C.REFIT_MODE_ADVANCE} (the refit screen's seam):
     * {@code C.advance} returns before the rate/interval/mint block, so
     * NO bay anywhere in the client engine mints or replaces — exactly the invariant a
     * predict-and-reconcile client wants — while the pilot's controls stay unlocked and the bay LIST
     * stays populated, which matters: {@code Ship.advance}'s engage-mode zero-flux-boost hold reads
     * {@code !launchBays.isEmpty()}, so clearing the list would desync own
     * speed; this latch leaves that branch identical both sides. Latched once per served window
     * in {@link PredictReconcileCombatPlugin#init} (before any spawn — the mint fires on the first unlocked advance frame),
     * cleared in {@link PredictReconcileCombatPlugin#teardownActive} (a vanilla:/serverless window never sets it). Known
     * presentation gap = the HUD replacement bar: bays with a null wing count as rate 1.0 in
     * {@code getSharedFighterReplacementRate} ⇒ the pilot's bar reads 100%
     * (the authoritative rate lives in the server's bay; derive rider queued).
     */
    static final String OWN_BAY_MINT_MODE = PredictLane.parseMode(
            "cc.predict.ownBayMint", System.getProperty("cc.predict.ownBayMint", "apply"));
    static final boolean OWN_BAY_MINT_SUPPRESS = "apply".equals(OWN_BAY_MINT_MODE);
    /**
     * The schema-v7 foe MANEUVER-COMMAND lane, knob {@code cc.predict.foeManeuver} =
     * {@code off|meter|apply} (default {@code apply} since the E2 flip; rolled out v6/v4/system-lane-style — MEASURE
     * before any write ships). The defect it exists for: {@link FoeStateLaneCoordinator#reconcileFoe} pose-snaps the
     * replica but nothing feeds its engine controller maneuver commands, so per-engine plume
     * flare/glow/contrail state — and the engine-sound loop that rides glow level — idles at the
     * drift glow at ALL times (seen in live play, with screenshots; the first investigation step confirmed
     * the mechanism: plumes are driven by the per-frame command queue and nothing else).
     * {@code meter}: on fresh snapshots (primary foe), compare the replica controller's 7 maneuver
     * booleans against the carried authoritative mask. {@code apply}: additionally re-issue
     * {@code ShipAPI.giveCommand} per set carried bit EVERY FRAME (the engine clears each ship's
     * command queue at the top of every {@code processCommands}, so commands must re-arrive per
     * frame — the server's own UP-replay lane proves the enqueue; map 1:1). The
     * replica's own controller then flares plumes, emits contrails, drives engine sound, and
     * integrates genuine between-snapshot thrust prediction (consistent with the netcode design; pose reconcile
     * stays authoritative). Invalid values THROW at class load.
     */
    static final String FOE_MANEUVER_MODE = PredictLane.parseMode(
            "cc.predict.foeManeuver", System.getProperty("cc.predict.foeManeuver", "apply"));
    static final boolean FOE_MANEUVER_METER = !"off".equals(FOE_MANEUVER_MODE);
    static final boolean FOE_MANEUVER_APPLY = "apply".equals(FOE_MANEUVER_MODE);
    /** Per-event log cap for the foe maneuver lane (disagree lines; apply issues are counter-only). */
    static final int FOE_MANEUVER_LOG_MAX = 60;
    /**
     * The foe FLUX apply lane, knob {@code cc.predict.foeFlux} = {@code off|meter|apply}
     * (default {@code apply} since the E2 flip). The defect it exists for: the wire carries
     * the full foe flux family on BOTH lanes (AWSS {@code ShipFluxSnapshot} floats; AWSD FLUX
     * section) but the client applied NONE of it, and the replica generates ~no flux locally
     * (presented fire bypasses the weapon fire cycle; vent is not carried) — so the vanilla
     * R-screen/hover target-info bar read a near-0 local {@code FluxTracker} while the real foe
     * climbed. {@code meter}: on fresh snapshots (primary foe), sample
     * the local-vs-authoritative {@code fluxLevel} delta. {@code apply}: additionally pin the
     * replica's own tracker to the carried values EVERY frame (idempotent value set — the
     * mirrorHull cadence; the tracker holds between snapshots): {@code setCurrFlux} then
     * {@code setHardFlux} — the vanilla reticle bar fills by curr/max and draws its 3px hard-flux
     * tick off {@code getMinFlux}. {@code fluxFlags} ride the pose minted-only;
     * flags APPLY (foe vent steam / overload FX) is a followup lane. Own ship
     * untouched. Invalid values THROW at class load.
     */
    static final String FOE_FLUX_MODE = PredictLane.parseMode(
            "cc.predict.foeFlux", System.getProperty("cc.predict.foeFlux", "apply"));
    static final boolean FOE_FLUX_METER = !"off".equals(FOE_FLUX_MODE);
    static final boolean FOE_FLUX_APPLY = "apply".equals(FOE_FLUX_MODE);
    /** Per-event log cap for the foe flux lane (max-delta pin lines only; apply is counter-only). */
    static final int FOE_FLUX_LOG_MAX = 40;
    /**
     * The foe FLUX-FLAGS reconcile lane, knob {@code cc.predict.foeFluxFlags} =
     * {@code off|meter|apply} (default {@code apply} since the E2 flip). The defect: the wire has carried
     * per-ship fluxFlags OVERLOADED/VENTING bits (ShipFluxSnapshot bit0/bit1 — a DIFFERENT
     * namespace from the UP-intent heldControls bits) since the flux tier shipped, but NO client
     * code ever consumed them — replicas never render foe overload arcs or vent steam while AI
     * foes vent 2–4×/match (census: 13 true-windows/1839 obs across 6 recorded matches). {@code meter}:
     * fresh-frame agree/disagree of BOTH bits vs the replica's local tracker. {@code apply}:
     * additionally slave the replica tracker's overload/vent STATE to the carried bits through the
     * engine's own API verbs, mismatch-edge (see {@link FoeStateLaneCoordinator#maintainFoeFluxFlags}).
     */
    static final String FOE_FLUXFLAGS_MODE = PredictLane.parseMode(
            "cc.predict.foeFluxFlags", System.getProperty("cc.predict.foeFluxFlags", "apply"));
    static final boolean FOE_FLUXFLAGS_METER = !"off".equals(FOE_FLUXFLAGS_MODE);
    static final boolean FOE_FLUXFLAGS_APPLY = "apply".equals(FOE_FLUXFLAGS_MODE);
    /** Per-event log cap for the foe flux-flags lane (disagree + slave-edge lines). Raised 40 → 400:
     *  one recorded flight's cap ran dry at tick 36655 of 43k — a lead pull spends 4–8 rows
     *  (LOCAL-RISE + one disagree per led tick + LEAD-CONFIRMED), so six of the pilot's ten pulls left
     *  NO lane row and the grade fell back to the reel + the cloak lane; ~200 B a row. */
    static final int FOE_FLUXFLAGS_LOG_MAX = 400;
    /**
     * The OWN-flux reconcile lane, knob {@code cc.predict.ownFlux} =
     * {@code off|meter|apply} (default {@code apply} since the E2 flip). The defect:
     * the own ship's LOCAL flux prediction can diverge from authority (flux_gap), and
     * a spurious local at-cap overload renders a full local overload (shield drop included) the
     * AUTHORITY never had — then the lockout-forced-off states leak UP through the intent sampler
     * (see the mask guard in {@link PredictReconcileCombatPlugin#captureOwnCommands}). {@code meter}: flux_gap stays
     * as-is + own overload/vent agree/disagree counters beside the own-shield rider. {@code apply}:
     * pin the own tracker's curr/hard flux to the authoritative pose on fresh frames (the foeFlux
     * mirror) and slave own overload/vent STATE to the carried bits with echo-safe gating (see
     * {@link PredictReconcileCombatPlugin#maintainOwnFlux} — the own lanes feed the UP sampler, so fall-slaving needs the
     * auth-edge/persistence gate and a vent RISE-slave must never ship at all).
     */
    static final String OWN_FLUX_MODE = PredictLane.parseMode(
            "cc.predict.ownFlux", System.getProperty("cc.predict.ownFlux", "apply"));
    static final boolean OWN_FLUX_METER = !"off".equals(OWN_FLUX_MODE);
    static final boolean OWN_FLUX_APPLY = "apply".equals(OWN_FLUX_MODE);
    /** Per-event log cap for the own-flux lane (slave-edge + mask-hold lines). */
    static final int OWN_FLUX_LOG_MAX = 40;
    /**
     * The OWN-CR pin lane, knob {@code cc.predict.ownCr} =
     * {@code off|meter|apply} (default {@code apply}). The defect: replica
     * ships are invisible to the engine's own "in combat" enemy-weight scan, so the LOCAL
     * PPT/CR clock freezes while the AUTHORITY's runs — the client HUD shows a frozen PPT bar
     * and CR% while the server is already draining CR. {@code meter}: wire-vs-local cr/dep
     * max-gap sampling + missing counter only. {@code apply}: on fresh {@code hasCr()} frames
     * pin {@code setTimeDeployed(wireDep)} + {@code setCurrentCR(wireCr)} — the recon-verified
     * pair that moves BOTH readouts (PPT countdown + CR% widget; pins). Peak stays LOCAL
     * (the HUD subtracts dep from local peak itself; wire peak is diagnostic-grade, pins D1).
     */
    static final String OWN_CR_MODE = PredictLane.parseMode(
            "cc.predict.ownCr", System.getProperty("cc.predict.ownCr", "apply"));
    static final boolean OWN_CR_METER = !"off".equals(OWN_CR_MODE);
    static final boolean OWN_CR_APPLY = "apply".equals(OWN_CR_MODE);
    /**
     * The OWN-cloak state-sync
     * lane, knob {@code cc.predict.ownCloak} = {@code off|meter|apply} (default {@code apply}).
     * The defect: the own body's phase cloak runs on the LOCAL engine only (the own reconcile is
     * compare-only), so a spurious local cloak cycle lands vanilla {@code PhaseCloakStats}
     * maxSpeed/accel mults (×0.0116) that the server body (cloak IDLE) never carries — a pure
     * state desync riding EVERY own-body mint. {@code meter}: the pre-existing agree/disagree
     * rider only (pre-build byte-for-byte). {@code apply}: when the ON-ness disagreement
     * persists {@link #OWN_CLOAK_FORCE_PATIENCE_FRAMES} consecutive fresh frames (a genuine
     * player press leads authority by ~RTT and reconverges well inside the window — never
     * fought), {@code forceState} the own cloak to the authoritative state+progress, the
     * {@code repairFoeCloakFromKeyframe} verb on the own body. Conditionality-safe: mirrors the wire truth,
     * never assumes a hull lacks its cloak. Phase-ON forces additionally require the carried
     * {@code isPhasedBit} (the foe-lane at-cap gate).
     */
    static final String OWN_CLOAK_MODE = PredictLane.parseMode(
            "cc.predict.ownCloak", System.getProperty("cc.predict.ownCloak", "apply"));

    /** own-facing APPLY on a ridden drone replica — the server
     *  authors orbit-drone facing (via OrbitDroneDrive) and the wire own-pose carries
     *  it; the local body's autopilot is un-toggleable (read-only API) so the per-frame
     *  override IS the mechanism. {@code apply} (default) pins
     *  facing+angVel from {@code lastOwnPose} every frame while the own hull is a drone
     *  replica; any other mode = no write. NO new wire surface. */
    static final String OWN_DRONE_FACING_MODE = PredictLane.parseMode(
            "cc.predict.ownDroneFacing", System.getProperty("cc.predict.ownDroneFacing", "apply"));
    static final boolean OWN_CLOAK_APPLY = "apply".equals(OWN_CLOAK_MODE);
    /** Fresh-frame persistence before an own-cloak force (≫RTT at any live snapshot cadence,
     *  ≪ the seconds-scale felt defect). */
    static final int OWN_CLOAK_FORCE_PATIENCE_FRAMES = 30;
    static final int OWN_CLOAK_FORCE_LOG_MAX = 20;
    /**
     * Overload base duration handed to {@code beginOverloadWithTotalBaseDuration} by BOTH slave
     * lanes. A FALLBACK ceiling only — the authoritative flag's fall edge is the real terminator
     * ({@code stopOverload}); a dead feed leaves at most this stat-modified tail (the engine
     * applies {@code getOverloadTimeMod} inside begin).
     */
    static final float SLAVE_OVERLOAD_BASE_SECONDS = 10f;
    /**
     * Echo gate: fresh own-pose frames a local-only lockout state must persist
     * UNCONFIRMED (local on, auth off, no auth falling edge seen) before the slave lane stops it.
     * ~300 ms at the 60 Hz fresh cadence — covers the UP-intent round-trip echo (local overload/vent
     * leads the authoritative bit by ≈RTT), so a legit local onset is never cancelled mid-echo,
     * while a BUG-B spurious overload now renders ≤ this window instead of multi-second.
     */
    static final int OWN_FLUX_SLAVE_CONFIRM_FRESH = 18;
    /**
     * The foe AUTOFIRE reconcile lane, knob {@code cc.predict.foeAutofire} =
     * {@code off|meter|apply} (default {@code apply} since the E2 flip). The defect it exists for: the enemy target
     * reticle's per-group "AUTOFIRE:" rows read the LOCAL replica's
     * {@code WeaponGroupAPI.isFiringAtWill()}, which kept its deploy defaults forever — no DOWN
     * tier carried autofire. The schema half (s3) rides per-mount
     * {@code FLAG_AUTOFIRE} on the existing mount-flags int: set when the mount's owning GROUP is
     * autofiring on the authoritative hull. {@code meter}: on fresh snapshots (primary foe),
     * derive per-group authoritative state from the carried mounts via the local replica's own
     * mount→group mapping and count agree/disagree vs the replica's groups. {@code apply}:
     * additionally reconcile a disagreeing group through the engine's REAL mechanism —
     * {@code giveCommand(TOGGLE_AUTOFIRE, null, g)} — DIFF/edge-triggered (TOGGLE flips state; an
     * every-frame re-issue would strobe), the exact DOWN mirror of the server's UP
     * {@code applyAutofireMask}. Display-state only: the standing per-frame
     * {@code setHoldFireOneFrame} gate keeps an autofire-ON replica group from actually
     * discharging. Invalid values THROW at class load.
     */
    static final String FOE_AUTOFIRE_MODE = PredictLane.parseMode(
            "cc.predict.foeAutofire", System.getProperty("cc.predict.foeAutofire", "apply"));
    static final boolean FOE_AUTOFIRE_METER = !"off".equals(FOE_AUTOFIRE_MODE);
    static final boolean FOE_AUTOFIRE_APPLY = "apply".equals(FOE_AUTOFIRE_MODE);
    /** Per-event log cap for the foe autofire lane (group toggles are rare, human-scale). */
    static final int FOE_AUTOFIRE_LOG_MAX = 60;
    /**
     * The foe SELECTED-GROUP reconcile lane knob, {@code cc.predict.foeSelection}
     * = {@code off} | {@code meter} | {@code apply}. The (114)(d) far-out-of-arc beam-terminus fix
     * rides this lane ({@code FoeStateLaneCoordinator.maintainFoeSelection}): the carried
     * per-mount {@code FLAG_SELECTED} bit mirrors the seat's real selected group onto the
     * replica so its aim param passes the engine group dispatch at any angle. Default APPLY
     * (unlike foeAutofire's meter default): the converge command is ABSOLUTE and idempotent
     * (plain {@code selected} assign — no toggle/strobe class), the
     * apply runs on mismatch only, and the lane self-disarms when no mount carries the bit
     * (old server / server {@code cc.server.mountSelectedFlag} off). {@code off}|{@code meter}
     * = the rollback positions. Invalid values THROW at class load.
     */
    static final String FOE_SELECTION_MODE = PredictLane.parseMode(
            "cc.predict.foeSelection", System.getProperty("cc.predict.foeSelection", "apply"));
    static final boolean FOE_SELECTION_METER = !"off".equals(FOE_SELECTION_MODE);
    static final boolean FOE_SELECTION_APPLY = "apply".equals(FOE_SELECTION_MODE);
    /** Per-event log cap for the foe selection lane (selection changes are rare, human-scale). */
    static final int FOE_SELECTION_LOG_MAX = 60;
    /**
     * The same-side HOLD-MECHANISM premise knob,
     * {@code cc.predict.sameSideHoldMode} = {@code fireonly} (default — the live per-weapon
     * {@code setForceNoFireOneFrame} hold) | {@code legacyoff} (the earlier
     * group {@code TOGGLE_AUTOFIRE} force-off, restored VERBATIM from history as
     * the A arm of the "turrets were always slewing" premise A/B). The frozen-at-rest
     * claim was never live-measured pre-fix (the slew meter landed WITH the fix); one AB
     * run with this axis differenced per window — steering {@code native} both — settles it on the
     * {@code same_side_mount_moved_frames} grade. Catalog-only ({@code cc.mtrack.every} idiom);
     * invalid values THROW at class load. Keep-vs-revert of the fireonly hold rides the run's
     * verdict.
     */
    static final String SAME_SIDE_HOLD_MODE = parseSameSideHoldMode(
            System.getProperty("cc.predict.sameSideHoldMode", "fireonly"));
    static final boolean SAME_SIDE_HOLD_LEGACY_OFF = "legacyoff".equals(SAME_SIDE_HOLD_MODE); // package: SubstepHoldAI

    private static String parseSameSideHoldMode(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("fireonly".equals(value) || "legacyoff".equals(value)) {
            return value;
        }
        throw new IllegalArgumentException(
                "cc.predict.sameSideHoldMode must be fireonly|legacyoff, got: " + raw);
    }
    /**
     * The foe SHIELD reconcile lane, knob {@code cc.predict.foeShield} =
     * {@code off|meter|apply} (default {@code apply} since the E2 flip). The defect it exists for: the authoritative
     * shieldOn state already crosses per-frame on the pose fluxFlags ({@code FLAG_SHIELD_ON},
     * the pose-flag packing) while the replica's shield stays deploy-default DOWN — seat 2 never sees
     * the boss shield. {@code meter}: fresh-frame agree/disagree of the replica's
     * {@code getShield().isOn()} vs the carried flag (primary foe). {@code apply}: the
     * unified state-gated press — {@code giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK,
     * aim, 0)} iff {@code authOn ? (!isOn && getActiveArc()==0f) : isOn} (type-independent:
     * the engine pins every shield's tracker to FRONT flip semantics — a
     * command-present frame FLIPS desired, command-free frames KEEP state, raises start only
     * from IDLE); law detail at {@code maintainFoeShield}. Phase-cloak
     * hulls are skipped (the SAME verb phase-toggles there). The replica's own engine then
     * unfolds native geometry around the wire-carried facing — zero new wire. Invalid values
     * THROW at class load.
     */
    static final String FOE_SHIELD_MODE = PredictLane.parseMode(
            "cc.predict.foeShield", System.getProperty("cc.predict.foeShield", "apply"));
    static final boolean FOE_SHIELD_METER = !"off".equals(FOE_SHIELD_MODE);
    static final boolean FOE_SHIELD_APPLY = "apply".equals(FOE_SHIELD_MODE);
    /** Per-event log cap for the foe shield lane (disagree flips only; apply is counter-only). */
    static final int FOE_SHIELD_LOG_MAX = 40;
    /**
     * The foe PHASE-CLOAK reconcile lane, knob {@code cc.predict.foeCloak} =
     * {@code off|meter|apply} (default {@code apply} since the E2 flip). The defect it
     * exists for: the replica of a phase hull (bastion grendel) never flies its own cloak, so the
     * authoritative phased windows — collision-off, 3× ship-local time, the phase visual — are
     * counted-never-presented locally. Carriage = the schema-v10 phase-cloak state tier (the v8
     * SYSTEM_STATE record layout on the {@code getPhaseCloak()} slot) plus the per-frame
     * {@code FLAG_PHASED} flux bit; this lane NEVER consumes GPEE {@code SYSTEM_WINDOW} edges —
     * those key {@code getSystem()} (= the ammofeed on grendel), a DIFFERENT system object, and
     * stay {@link FoeStateLaneCoordinator#maintainFoeSystemState}'s. {@code meter}: fresh-frame agree/disagree of the
     * replica's {@code isPhased()} vs the carried per-tick bit (collision parity). {@code apply}
     * mode: the per-tick bit's EDGE owns the twin's cloak edge —
     * {@code giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK, aim, 0)} at the rise (the verb TOGGLES the
     * cloak on phase hulls), {@code forceState(OUT, 0.5)} at the fall
     * (the engine clears {@code isPhased} mid-chargedown) — the keyframe-only v10 tier repairs the
     * twin only when FRESH ({@link FoeCloakLane#repairFoeCloakFromKeyframe}, the old
     * one-shot seed generalized), a held tier acts only in agreement with the bit; ONE action per
     * fresh frame (a per-frame re-issue would strobe — the FRONT-shield 27k-toggle precedent).
     * Decision table = {@link FoeCloakLane#cloakAction}. Verb partition (comment-pinned mutual exclusion):
     * {@link FoeStateLaneCoordinator#maintainFoeShield}'s phase guard skips cloak hulls — on cloak hulls THIS lane owns
     * the verb. Invalid values THROW at class load.
     */
    static final String FOE_CLOAK_MODE = PredictLane.parseMode(
            "cc.predict.foeCloak", System.getProperty("cc.predict.foeCloak", "apply"));
    static final boolean FOE_CLOAK_METER = !"off".equals(FOE_CLOAK_MODE);
    static final boolean FOE_CLOAK_APPLY = "apply".equals(FOE_CLOAK_MODE);
    /** Per-event log cap for the foe cloak lane (phase cycles are seconds-scale; applies are edge-only).
     *  Raised 60 → 400: one recorded flight spent the 60 rows on the FIRST Ziggurat by
     *  tick 7367 (27 APPLY + 33 disagree) and every later phase hull graded blind. */
    static final int FOE_CLOAK_LOG_MAX = 400;
    /**
     * The foe/remote HULL reconcile lane, knob {@code cc.predict.foeHullReconcile}
     * = {@code off|meter|apply} (default {@code apply} since the E2 flip). The defect it exists for: between kill
     * edges NOTHING reconciles replica hull (the damage-CAP design), so client
     * foe-hull error = ∫(local applied − server applied), unbounded — worst term is CROSS-SEAT
     * ledger starvation (other seats' damage never applies locally). Family (b)
     * baseline+replay: every frame engineHull := wire baseline − Σ outstanding
     * un-acked own-local hits, written via the proven {@code mirrorHull} clamp — the wire baseline
     * carries every seat's damage, the replay term keeps the axis-A visible-impact feel. The raw
     * local-applied ledger ({@link FoeHullReconcileLane.HullLedger}) runs UNCONDITIONALLY in all modes (so
     * debug bars/walk lines keep one read path; a standing constraint — reconcile never touches the
     * raw instruments). {@code meter}: ledger + tracking meters only, no write. {@code apply}:
     * floor-bounded write, suspended on stale feed. Invalid values THROW at class load.
     */
    static final String FOE_HULL_RECONCILE_MODE = PredictLane.parseMode(
            "cc.predict.foeHullReconcile", System.getProperty("cc.predict.foeHullReconcile", "apply"));
    static final boolean FOE_HULL_RECONCILE_METER = !"off".equals(FOE_HULL_RECONCILE_MODE);
    static final boolean FOE_HULL_RECONCILE_APPLY = "apply".equals(FOE_HULL_RECONCILE_MODE);
    /** A bad {@code cc.predict.fighterFlameoutClass} value fails HERE at class load like
     *  every sibling knob — lazily it first threw inside the FSLC catch and then escaped the meter flush
     * as an in-game Fatal. {@code modeOf} is a call, so the read forces the law's clinit. */
    static final int FIGHTER_FLAMEOUT_CLASS_MODE = FighterFlameoutClassLaw.MODE;
}
