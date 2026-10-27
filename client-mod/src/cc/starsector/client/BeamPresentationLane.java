package cc.starsector.client;

import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.net.ServerSnapshotFrame;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;

/**
 * The beam-presentation lane — every half that makes a replica hull's BEAM fire read
 * correctly off the wire: the RELAY half (per-frame force-fire re-assert + the
 * burst-lifecycle feed on wire-FIRING beam mounts, {@link #maintainBeamPresentation}), the
 * AIM half (v12 beam-record steering + the idle release + the wire-terminus shim
 * management, {@link #maintainBeamAim}), and the whole beam meter family (relay/cycle/aim/
 * idle/term counters, the spec census, the shape census, and the v12
 * beam-meter debrief emitter {@link #logMeter}).
 *
 * <p><b>Provenance.</b> E2 whole-concern extraction (the extraction
 * packet's adjudication 5 — Option A, the missileLane
 * idiom; pays the deferred-refactor ledger's OWED bullet, both halves): the FSLC
 * beam tail + the PRCP beam-term meter state (the term
 * emitter, {@code TERM_LEN_EPS_SU}) moved byte-verbatim with only receiver rewrites
 * ({@code plugin.beamX} → lane-local; the two MODE statics + {@code BEAM_LOG_MAX} widened
 * package-visible on the plugin). Zero policy change; the three relay call sites keep their
 * exact textual slots (FSLC registry walk, PRCP 1v1 advance, PRCP beam-aim slot) — the
 * {@code "foe"}/{@code "remote"} label split is grader-parsed, never fold the paths.
 *
 * <p><b>Grader contract (byte-identical).</b> The {@code predict v12 beam-meter [<where>]:}
 * line's key=value token STRINGS are parsed order-independent by
 * {@code local/watch-bars/faraim-sweep-*.ps1}; the prefix + single-line grouping are
 * load-bearing. Keep every token string verbatim.
 *
 * <p><b>What stays plugin-side</b> (reached via {@code plugin.} /
 * {@code PredictReconcileCombatPlugin.}): the knob statics ({@code BEAM_PRESENTATION_*},
 * {@code BEAM_CYCLE_*}, {@code BEAM_LOG_MAX}, {@code SLEW_MOVED_DEG} — still shared with the
 * sameSide hold walk), {@code localFire}, {@code session}, {@code foeShip},
 * {@code registryShape}, {@code remoteEntities}.
 *
 * <p>Fresh-file growth watch: re-adjudicate the seam past ~900 nb
 * (the deferred-refactor ledger's E2 row).
 */
final class BeamPresentationLane {

    private final PredictReconcileCombatPlugin plugin;

    BeamPresentationLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    // beam-terminus length meter epsilon: covers the engine's +2 su converge
    // epsilon plus replica-vs-server pose residual at the muzzle; full-range regrow on a
    // cursor-stopped beam overshoots by hundreds of su, so 10 su separates the classes cleanly.
    static final float TERM_LEN_EPS_SU = 10f; // the terminus meter epsilon (lane-owned since E2)

    // BEAM-PRESENTATION lane (v12 beam-source tier; see BEAM_PRESENTATION_MODE).
    final PredictReconcileCombatPlugin.PredictLane beamLane =
            new PredictReconcileCombatPlugin.PredictLane(PredictKnobConfig.BEAM_LOG_MAX);
    int beamRelayMountFrames;   // mount-frames wire-FIRING on a local beam mount (meter+apply)
    int beamRelayAsserts;       // apply: setForceFireOneFrame(true) writes
    // Design 05: the NON-burst relay follows the wire's charge-down
    // (BeamRelayLaw). One Track per non-burst beam weapon, weak keys cover despawns; no existing per-mount
    // record fits (beamIdleLastRel / beamTermRayPrev are aim-lane samples keyed on other weapon sets).
    private final java.util.Map<WeaponAPI, BeamRelayLaw.Track> beamRelayTracks = new java.util.WeakHashMap<>();
    int beamRelayChargeStops;   // apply: relay released on a wire-FIRING charge-down sample
    int beamRelayIdleBridged;   // apply: re-asserts after a server idle the local weapon never idled through
    int beamRelayThrows;        // the mount-walk catch (relay + force-fire) — every catch counts
    // The beams the relay force-fired THIS plugin frame (identity set, cleared at
    // the plugin frame head by beginRelayFrame). The engine consumes setForceFireOneFrame on pass 1 of the NEXT
    // engine frame, so SubstepHoldAI re-asserts it on that frame's fast-time passes 2..k — else the per-sub-step
    // ship-level hold (WeaponGroup: shouldFire && !holdFire && !holdFireOneFrame) charges a wire-FIRING beam down.
    private final java.util.Set<WeaponAPI> relayedBeams =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<WeaponAPI, Boolean>());
    void beginRelayFrame() { if (!relayedBeams.isEmpty()) { relayedBeams.clear(); } }
    boolean relayedThisFrame(WeaponAPI weapon) { return relayedBeams.contains(weapon); }
    // The BEAM-CYCLE burst-lifecycle feed census (see BEAM_CYCLE_MODE).
    int beamCycleCdZeroed;      // apply: setRemainingCooldownTo(0) writes on wire-FIRING beam mounts
    int beamCycleAmmoFed;       // apply: setAmmo mirror writes (differing frames only)
    /** Relayed wire-FIRING burst-beam mount frames (meter AND
     *  apply): the cycle_feed bar's NODATA arm reads it to tell "no burst beam was ever relayed" from "the
     *  burst feed is dead"; kits that predate this counter print no such token. */
    int beamCycleBurstFrames;
    int beamAimRecords;         // sourced v12 BEAM records reaching the aim lane (meter+apply)
    int beamAimSets;            // apply: suspend+setCurrAngle steering writes
    int beamAimUnresolved;      // sourced records with no live local beam mount (divergence surface)
    int beamIdleReleases;       // apply: fire->idle suspend releases (idle pose -> native autofire)
    // Idle slew meter (the same-side slew-meter idiom, beam twin): sampled by the FSLC
    // release walk on RELEASED mounts only (fire-window mounts are snap-owned — motion there is
    // the wire's, not the native slew under grade). moved>0 = a freed beam mount actually slews.
    long beamIdleMovedFrames;   // apply: idle beam mount-frames with |Δrel| > SLEW_MOVED_DEG
    float beamIdleMaxDelta;     // apply: max idle |Δrel| deg/frame (cumulative, like releases)
    boolean beamRelayActiveLogged; // one-shot first-relay latch
    boolean beamIdleReleaseLogged; // one-shot first idle-release latch
    // (114)(d) beam-terminus lane: the WireTermAutofireShim install/arm census
    // (apply tier) + the local-length-vs-wire-terminus meter (meter tier; the defect face measured
    // in-lane: over>0 = an observer beam locally LONGER than the server terminus + epsilon).
    int beamTermShimInstalls;   // apply: shims installed into live group AI lists (once per weapon)
    int beamTermArms;           // apply: per-frame wire-terminus arms on shimmed beam weapons
    /** The glide / snap census of those arms (apply tier): a terminus change
     *  that kept the glide pair ({@code WireTermAutofireShim.ARM_GLIDE}) vs one that applied raw ({@code ARM_SNAP});
     *  fresh and held arms count in neither. glides=0 with snaps>0 = the glide is dead on this flight. Lane-lifetime
     *  counts like {@link #beamTermArms} (nothing resets either); printed last on the beam-meter line. */
    int beamTermGlides;
    int beamTermSnaps;
    int beamTermUnshimmed;      // apply: records whose weapon exposed no group/plugin seam (fail-soft)
    long beamTermOverFrames;    // meter: record-frames local len > wire dist + TERM_LEN_EPS_SU
    float beamTermOverMax;      // meter: max local-over-wire overshoot (su)
    long beamTermCappedFrames;  // meter: record-frames local len <= wire dist + eps (engagement)
    // The over-residual ATTRIBUTION split (armed = the shim's wire aim was
    // live at meter time, so an over-frame means the spec ignored the aim: the non-converge
    // class; fallback = shim absent/disarmed: the record-gap/unshimmed class). armed+fallback
    // == beamTermOverFrames by construction.
    long beamTermOverArmed;     // meter: over-frames while the term shim was ARMED
    long beamTermOverFallback;  // meter: over-frames while the term shim was absent/disarmed
    // The keep-target fix census + the GATE meter. keepSet counts weapons whose
    // keep-beam-target-while-chargedown was switched on at shim install (closes the engine's
    // chargedown aim-null — the FIFTEENTH-watch 48% armed-over class). aimOver counts
    // CONVERGE-spec armed frames whose live length exceeded the shim's own aim envelope
    // (max endpoint dist + eps, lerp-phase-proof): the engine-ignored-the-fed-aim face, machine
    // bar ≈0. Distinct from beamTermOverFrames, which stays vs the RAW wire terminus and now
    // also carries the interp catch-up lag as a disclosed, bounded cost observable.
    int beamTermKeepSet;        // apply: weapons keep-target-armed at shim install
    // The gate meter RE-CUT (its mechanism later corrected: stale = the
    // sweep recomputed a bit-equal endpoint — static aim / the shared-ray0 structural read —
    // never an idle freeze; rays don't outlive non-IDLE): aimOver grades only frames whose
    // ray0 endpoint MOVED since the last meter read. graded+stale partition the armed
    // converge-spec reads; the bar denominator is graded, ≈0 stays the bar.
    long beamTermAimOverFrames; // meter: swept converge-spec armed frames len > aim envelope + eps
    float beamTermAimOverMax;   // meter: max such overshoot (su)
    long beamTermAimGraded;     // meter: armed converge-spec frames graded (ray0 swept)
    long beamTermAimStale;      // meter: armed converge-spec frames skipped (ray0 frozen)
    // The round-4 DIFFERENTIATOR census (zero knobs, meter tier): per
    // armed CONVERGE-spec metered frame a 5-bit local-state shape — bit0 = group
    // isAutofiring() (the routing-flip candidate's direct observable), bit1 = weapon
    // isFiring(), bit2 = chargeLevel>0, bit3 = ammo==0 (ammo-limited specs), bit4 = live ray
    // readable (the destroy-lingers question). Tallied three ways — all frames / gate-
    // stale frames (ray frozen) / aim_over frames (the face) — so the face's shape mix vs the
    // baseline mix IS the attribution differentiator. One-frame skew disclosed: bits read at
    // METER time, the sweep they grade ran in the preceding sim tick. Plus the
    // server-side lens: min wire-terminus distance + to-at-muzzle share (a near-zero-envelope
    // record would explain aim_over_max ≈ full range from the serve side).
    final long[] beamTermShapeFrames = new long[32];
    final long[] beamTermShapeStale = new long[32];
    final long[] beamTermShapeOver = new long[32];
    float beamTermWireMin = Float.MAX_VALUE;
    long beamTermWireAtMuzzle;  // meter: records whose wire terminus sat < 1 su from the muzzle
    /** Per weapon-SPEC attribution census. Key = sanitized local weapon
     *  spec id (never wire-derived); value = {frames, overArmed, overFallback, overMaxSu,
     *  converge 1/0/-1 unknown} with converge read once at mint from the API
     *  {@code BeamWeaponSpecAPI.isConvergeOnPoint()}. Bounded at 24 distinct specs; overflow
     *  folds into {@code _other} (bounded-allocation law). Emitted at teardown as
     *  {@code beam_term_spec=}. */
    final java.util.Map<String, long[]> beamTermSpecCensus = new java.util.LinkedHashMap<>();

    /** The per-spec attribution blob: top 8 rows by total over-frames,
     *  whitespace-free ({@code id~cC:frames/overArmed/overFallback/overMaxSu} joined by
     *  commas; {@code none} when empty). Read once at teardown by the beam-meter line. */
    private String beamTermSpecTokens() {
        if (beamTermSpecCensus.isEmpty()) {
            return "none";
        }
        java.util.List<java.util.Map.Entry<String, long[]>> rows =
                new java.util.ArrayList<>(beamTermSpecCensus.entrySet());
        java.util.Collections.sort(rows, new java.util.Comparator<java.util.Map.Entry<String, long[]>>() {
            @Override
            public int compare(java.util.Map.Entry<String, long[]> a, java.util.Map.Entry<String, long[]> b) {
                long ao = a.getValue()[1] + a.getValue()[2];
                long bo = b.getValue()[1] + b.getValue()[2];
                return ao == bo ? 0 : (ao > bo ? -1 : 1);
            }
        });
        StringBuilder sb = new StringBuilder();
        int emitted = 0;
        for (java.util.Map.Entry<String, long[]> e : rows) {
            if (emitted++ == 8) {
                break;
            }
            long[] v = e.getValue();
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append("~c").append(v[4]).append(':')
                    .append(v[0]).append('/').append(v[1]).append('/').append(v[2])
                    .append('/').append(v[3]);
        }
        return sb.toString();
    }

    /** The shape-census blob: sparse {@code shape:frames/stale/over}
     *  rows (shape = the 5-bit index documented at the arrays), comma-joined, {@code none}
     *  when empty. Read once at teardown by the beam-meter line. */
    private String beamTermShapeTokens() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < beamTermShapeFrames.length; i++) {
            if (beamTermShapeFrames[i] == 0L && beamTermShapeStale[i] == 0L
                    && beamTermShapeOver[i] == 0L) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(i).append(':').append(beamTermShapeFrames[i]).append('/')
                    .append(beamTermShapeStale[i]).append('/').append(beamTermShapeOver[i]);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    boolean beamTermShimLogged; // one-shot first-install latch

    /**
     * The beam-presentation RELAY half (see {@link PredictKnobConfig#BEAM_PRESENTATION_MODE}): per
     * RENDER frame per replay-driven hull off the hold-last pose, re-assert the local engine's
     * per-weapon force-fire flag on every wire-FIRING mount whose local weapon is a beam. NOT
     * fresh-gated by design (the force flag self-clears each frame; beam sustain = per-frame
     * re-assert; a dropped wire flag stops the re-assert and the engine runs its native
     * chargedown). Never called for the own ship — own beams are locally-fired prediction.
     *
     * <p>The BURST-LIFECYCLE feed rides the same loop (knob
     * {@code cc.predict.beamCycle}, {@link PredictKnobConfig#BEAM_CYCLE_APPLY}): a
     * force-fire assert alone cannot restart a burst while the local cycle sits in refire
     * COOLDOWN or the mount ran out of locally-deducted ammo — the engine's restart interlocks
     * gate on both. While the wire says FIRING those
     * interlocks are fed from wire truth (ammo mirror FLOORED at 1: the server's
     * deduct-at-start makes wire ammo read 0 during a drained-pool burst, and a raw mirror
     * starves the very restart it exists to feed — plus cooldown zero), so the restart runs
     * through the engine's OWN READY path; chargedown/chargeup still run engine-native.
     *
     * <p>For a NON-burst beam the wire FIRING bit is "not idle"
     * (it spans the server's charge-down), so the assert now follows {@link BeamRelayLaw}: firing and
     * the charge did not fall since the previous fresh snapshot. The first charge-down sample releases
     * the relay, the local engine charges down and idles (ray destroyed) inside the server's idle gap,
     * and the next server charge-up re-creates the local ray at the muzzle. Burst beams keep the
     * FIRING-bit relay and the feed above byte-equal. Held-over frames repeat the last decision.
     */
    void maintainBeamPresentation(ShipAPI ship, ServerEntityPose pose, long simTick, String who) {
        if (!PredictKnobConfig.BEAM_PRESENTATION_METER || ship == null || pose == null || !ship.isAlive()) {
            return;
        }
        try {
            if (!pose.hasWeaponMounts()) {
                return;
            }
            List<WeaponAPI> weapons = plugin.weaponLists.weapons(ship); // per-frame cache
            if (weapons == null) {
                return;
            }
            for (cc.starsector.client.net.ServerWeaponMountState mount : pose.weaponMounts()) {
                if (!mount.firing()) {
                    relayIdleSample(weapons, mount, simTick);
                    continue;
                }
                int idx = mount.mountIndex();
                if (idx < 0 || idx >= weapons.size()) {
                    continue;
                }
                WeaponAPI weapon = weapons.get(idx);
                if (weapon == null || !weapon.isBeam()) {
                    // Census the SKIPPED arm too — a wire-FIRING mount whose local weapon is
                    // not a beam is the index/variant-mismatch shape that would mint a weapon-backed
                    // damager on a replay-driven hull, i.e. an autonomous_discharge by construction.
                    plugin.localFire.onBeamRelayMount(ship, idx, weapon, false);
                    continue; // non-beam fire presents via the discharge replay lane
                }
                beamRelayMountFrames++;
                if (weapon.isBurstBeam()) { beamCycleBurstFrames++; }
                if (!PredictKnobConfig.BEAM_PRESENTATION_APPLY) {
                    continue;
                }
                if (!weapon.isBurstBeam() && !relayNonBurst(weapon, mount, true, simTick)) {
                    continue; // Server charging DOWN — hold, the local engine idles
                }
                weapon.setForceFireOneFrame(true);
                relayedBeams.add(weapon); // SubstepHoldAI re-asserts this on passes 2..k
                plugin.localFire.onBeamRelayMount(ship, idx, weapon, true); // Census: the relayed arm
                beamRelayAsserts++;
                // The (114)(e) free-space face: armed-no-ray windows
                // are the AMMO restart interlock, not cooldown-overrun
                // (the engine's restart tracker: deductOneAmmo()<1 blocks the READY→FIRING restart, and a
                // starved weapon parks in READY where getCooldownRemaining()==0). The server
                // deducts ammo at burst START, so on a drained pool (Paladin: 20 ammo, regen
                // 1/s, ~3/s spam consumption) the wire carries ammo=0 during every steady-state
                // FIRING window — a raw mirror writes that 0 down and stomps the replica's own
                // regen, starving the restart (0.8829 armed-no-ray measured). Fix: while
                // the wire says FIRING, FLOOR the mirror at 1 — the server IS firing this burst,
                // so restart-enable is wire truth; mirror-down stays live for counts >=1. The
                // force-fire above then restarts through the engine's native READY path.
                // Wire-FIRING mounts only ⇒ never mints an autonomous burst (holdFire untouched).
                if (PredictKnobConfig.BEAM_CYCLE_APPLY) {
                    if (mount.usesAmmo() && weapon.usesAmmo() && mount.ammo() >= 0) {
                        int fed = Math.min(mount.ammo(), weapon.getMaxAmmo());
                        if (fed < 1) {
                            fed = 1;
                        }
                        if (weapon.getAmmo() != fed) {
                            weapon.setAmmo(fed);
                            beamCycleAmmoFed++;
                        }
                    }
                    if (weapon.getCooldownRemaining() > 0f) {
                        weapon.setRemainingCooldownTo(0f);
                        beamCycleCdZeroed++;
                    }
                }
                if (!beamRelayActiveLogged) {
                    beamRelayActiveLogged = true;
                    beamLane.log("predict v12 beam-relay ACTIVE: " + who + " mount=" + idx
                            + " wire-FIRING relayed into the local fire cycle @tick=" + simTick);
                }
            }
        } catch (Throwable t) {
            beamRelayThrows++; // presentation-side relay; never break the watch — counted, the rest of this replica's mounts skip this frame
        }
    }

    /**
     * The NON-burst relay gate (design 05; {@link BeamRelayLaw}): steps the mount's {@link BeamRelayLaw.Track}
     * and tallies the two edge counters; returns whether to force-fire this frame. A Track is minted
     * (once per weapon) only on a wire-FIRING frame, so an idle mount that was never relayed costs one
     * map read and allocates nothing. {@code isFiring()} is read only while the relay is stopped and
     * the local weapon has not yet been seen idle.
     *
     */
    private boolean relayNonBurst(WeaponAPI weapon, cc.starsector.client.net.ServerWeaponMountState mount,
            boolean firingWire, long simTick) {
        BeamRelayLaw.Track t = beamRelayTracks.get(weapon);
        if (t == null) {
            if (!firingWire) {
                return false;
            }
            t = new BeamRelayLaw.Track();
            beamRelayTracks.put(weapon, t);
        }
        int out = BeamRelayLaw.step(t, simTick, firingWire, mount.chargeLevel(),
                t.relaying || t.localIdle || weapon.isFiring());
        if ((out & BeamRelayLaw.CHARGE_STOP) != 0) {
            beamRelayChargeStops++;
        }
        if ((out & BeamRelayLaw.IDLE_BRIDGED) != 0) {
            beamRelayIdleBridged++;
        }
        return (out & BeamRelayLaw.ASSERT) != 0;
    }

    /**
     * A wire-IDLE sample of an already-tracked non-burst beam mount feeds its charge history (the law's
     * "previous fresh sample" includes idle samples, else the first charge-up sample after an idle would
     * read as a fall against the last charge-down value and delay the restart). No-op until some beam
     * was relayed (the empty-map short-circuit).
     */
    private void relayIdleSample(List<WeaponAPI> weapons, cc.starsector.client.net.ServerWeaponMountState mount,
            long simTick) {
        if (beamRelayTracks.isEmpty()) {
            return;
        }
        int idx = mount.mountIndex();
        if (idx < 0 || idx >= weapons.size()) {
            return;
        }
        WeaponAPI weapon = weapons.get(idx);
        if (weapon != null) {
            relayNonBurst(weapon, mount, false, simTick);
        }
    }

    /**
     * The beam-presentation AIM half: per render frame, each v12 sourced BEAM record
     * resolves source entityId → replay-driven hull (the {@code presentFoeDischarge} idiom: 1v1
     * foe / registry row), mountIndex → local weapon (the server {@code getAllWeapons()} ordinal
     * contract), and steers the mount at the authoritative line — suspend the turret tracker's
     * auto-return, then set the absolute local-muzzle→wire-impact bearing. Own-sourced records
     * are skipped (locally aimed).
     *
     * <p><b>Idle half (the (114)(c) pose fix).</b> The snap latches
     * {@code suspendAutomaticTurning=true} and no lane ever cleared it for a beam (AIMP's clear is
     * {@code !isBeam()}-filtered in {@code RemoteAimLane}; the same-side hold-walk and
     * {@code SameSideAimLane} skip beams too — the confirmed exactly-one-owner GAP), so
     * after its first fire a replica beam mount froze at the last snapped angle ("teleport on fire,
     * otherwise frozen", the owner face). Now every replica beam mount WITHOUT a live wire beam
     * record this frame gets the suspend cleared: the engine's shared slew block
     * resumes and the group's own {@code AutofireAIPlugin} slews the
     * mount at turnRate — track-when-threatened / rest-when-idle, vanilla-faithful. Fire stays
     * suppressed INDEPENDENTLY: slew rides {@code suspendAutomaticTurning}, fire rides the
     * per-frame ship-level {@code holdFireOneFrame} (the engine's weapon group gates the fire
     * boolean on it, yet advances the AI target regardless
     * — verified ground truth), so the clear cannot enable autonomous fire. Mounts WITH a live record stay
     * snap-owned (damage fidelity: the local ray follows currAngle). A wire-FIRING mount with no
     * record yet (chargeup — MEASURE row) now slews toward the local pick instead of
     * holding; the record-arrival snap re-pins it, residual bounded by the local-vs-server
     * autofire pick divergence (an accepted hazard). No mounted-body carve-out: the clear
     * cannot strangle seat fire (disjoint flags) and native aim IS the wanted idle behavior on a
     * piloted module.
     *
     * <p><b>Terminus half (the (114)(d) fix).</b> The snap above is ANGLE-only;
     * the force-fired replica beam's LENGTH was computed by the local engine with no aim point —
     * full spec range in open space (the owner's out-of-arc terminus desync,
     * damage-relevant). Each recorded weapon now also gets its
     * {@link WireTermAutofireShim} armed with the wire to-point ({@code ServerBeamState.toX/toY}
     * = the server engine's own cursor-stopped {@code getTo()}), so the engine's native converge
     * length cap reproduces the server length — and the
     * local damage sweep runs on the same capped ray (visual ⟂ damage never split). The idle
     * walk disarms; the shim self-drains if this lane ever stops re-asserting.
     */
    void maintainBeamAim(ServerSnapshotFrame snapshot) {
        if (!PredictKnobConfig.BEAM_PRESENTATION_METER) {
            return;
        }
        try {
            java.util.Set<WeaponAPI> liveBeamMounts = PredictKnobConfig.BEAM_PRESENTATION_APPLY
                    ? new java.util.HashSet<WeaponAPI>() : java.util.Collections.<WeaponAPI>emptySet();
            List<cc.starsector.client.net.ServerBeamState> beams = snapshot.beams();
            if (beams == null) {
                beams = java.util.Collections.emptyList();
            }
            for (cc.starsector.client.net.ServerBeamState beam : beams) {
                if (!beam.hasShipSource()) {
                    // A source id in the MISSILE partition is a DEM payload
                    // beam attributed to its child missile — DemDronePresenter's lane, not
                    // this one. Resolving it here would look the id up in remoteEntities
                    // (ships only), miss, and count a spurious beamAimUnresolved.
                    continue;
                }
                if (plugin.session != null && beam.sourceEntityId() == plugin.session.shipId()) {
                    continue; // own beams aim from local input
                }
                beamAimRecords++;
                ShipAPI ship = plugin.foeShip;
                if (plugin.registryShape) {
                    PredictReconcileCombatPlugin.RemoteEntity re = plugin.remoteEntities.get(beam.sourceEntityId());
                    ship = re != null ? re.ship : null;
                }
                List<WeaponAPI> weapons = ship != null && ship.isAlive() ? plugin.weaponLists.weapons(ship) : null;
                int idx = beam.sourceMountIndex();
                WeaponAPI weapon = weapons != null && idx >= 0 && idx < weapons.size()
                        ? weapons.get(idx) : null;
                if (weapon == null || !weapon.isBeam()) {
                    beamAimUnresolved++;
                    beamLane.log("predict v12 beam-aim UNRESOLVED: src=" + beam.sourceEntityId()
                            + " mount=" + idx + " (no live local beam mount)");
                    continue;
                }
                Vector2f muzzle = weapon.getFirePoint(0);
                if (muzzle == null) {
                    muzzle = weapon.getLocation();
                }
                if (muzzle != null) {
                    meterTermLength(weapon, muzzle, beam, ship); // Meter tier: pure read
                }
                if (!PredictKnobConfig.BEAM_PRESENTATION_APPLY) {
                    continue;
                }
                // Fire-window ownership is claimed by the RECORD, not the successful snap: a
                // degenerate-bearing or muzzle-less continue below still holds last aim by design.
                liveBeamMounts.add(weapon);
                // Terminus half: arm BEFORE the degenerate-bearing gate — a to-at-muzzle
                // record means the server beam terminated at the muzzle, and the armed native
                // cap (dist+2) reproduces exactly that.
                WireTermAutofireShim shim = ensureTermShim(ship, weapon);
                float aimX = beam.toX();
                float aimY = beam.toY();
                if (shim != null) {
                    int arm = shim.armWireAim(beam.toX(), beam.toY(), snapshot.simTick());
                    beamTermArms++;
                    // The glide / snap census off the arm's branch code
                    if (arm == WireTermAutofireShim.ARM_GLIDE) {
                        beamTermGlides++;
                    } else if (arm == WireTermAutofireShim.ARM_SNAP) {
                        beamTermSnaps++;
                    }
                    // Bearing rides the SAME interpolated point the engine's length
                    // cap consumes, so the whole terminus sweeps smoothly (face (e)).
                    Vector2f smooth = shim.armedAim();
                    aimX = smooth.x;
                    aimY = smooth.y;
                } else {
                    beamTermUnshimmed++;
                }
                if (muzzle == null) {
                    continue;
                }
                float dx = aimX - muzzle.x;
                float dy = aimY - muzzle.y;
                if (dx * dx + dy * dy < 1f) {
                    continue; // impact at the muzzle — degenerate bearing; hold last aim
                }
                weapon.setSuspendAutomaticTurning(true);
                weapon.setCurrAngle((float) Math.toDegrees(Math.atan2(dy, dx)));
                beamAimSets++;
            }
            if (PredictKnobConfig.BEAM_PRESENTATION_APPLY) {
                clearIdleBeamSuspend(liveBeamMounts);
                beamLiveMountsPrevFrame = liveBeamMounts;
            }
        } catch (Throwable ignore) {
            // presentation-side aim; never break the watch
        }
    }

    /** Last frame's fire-window beam mounts; membership diff = the fire→idle
     *  transition meter ({@code beamIdleReleases}). Refs go stale harmlessly at round reset: a
     *  dead ship's mount just never matches again (identity membership only, one-frame horizon). */
    private java.util.Set<WeaponAPI> beamLiveMountsPrevFrame = java.util.Collections.emptySet();

    /** Idle slew-meter prev sample (ship-relative deg, the same-side slew-meter idiom).
     *  Weak keys cover despawns; RESEEDED (not graded) on the release-transition frame so the
     *  fire-window snap jump never counts as slew — moved-frames measure pure native autofire. */
    private final java.util.Map<WeaponAPI, Float> beamIdleLastRel = new java.util.WeakHashMap<>();

    /** Installed {@link WireTermAutofireShim}s by weapon (weak keys cover
     *  despawns; install is once per weapon, idempotent, APPLY-gated). */
    private final java.util.Map<WeaponAPI, WireTermAutofireShim> beamTermShims =
            new java.util.WeakHashMap<>();

    /** Last metered ray0 endpoint per weapon
     *  (weak keys cover despawns). Rays are CREATED at chargeup entry and DESTROYED on entering
     *  charge-IDLE (a further engine gate is
     *  belt-and-suspenders over an already-empty list), and {@code isFiring()} ≡ non-IDLE ⇒ a
     *  live ray implies the sweep loop RAN. A bit-equal endpoint across meters therefore means
     *  the sweep recomputed it bit-equal (static aim between fan samples, plus the guardian's
     *  5-records-shared-ray0 structural 4/5) — no aim MOTION was consumed, so the frame stays
     *  ungradable by the gate meter (the false-positive class) and counts as
     *  {@code beam_term_aim_stale}. Multi-record mounts still grade once per frame. */
    private final java.util.Map<WeaponAPI, float[]> beamTermRayPrev =
            new java.util.WeakHashMap<>();

    /** resolve-or-install the terminus aim shim for a recorded beam weapon:
     *  the weapon's group ({@code getWeaponGroupFor}) exposes its LIVE autofire-plugin list
     *  ({@code WeaponGroupAPI.getAIPlugins()} returns the backing list);
     *  the entry whose {@code getWeapon()} is ours gets
     *  wrapped in place. Pure public API — the B-legal seam the read landed on
     *  (ship {@code mouseTarget} never reaches an autofire group's weapons —
     *  {@code advanceAuto} discards the group aim). Returns null
     *  (fail-soft, censused as {@code beamTermUnshimmed}) when the seam is missing. */
    private WireTermAutofireShim ensureTermShim(ShipAPI ship, WeaponAPI weapon) {
        WireTermAutofireShim shim = beamTermShims.get(weapon);
        if (shim != null) {
            return shim;
        }
        WeaponGroupAPI group = ship != null ? ship.getWeaponGroupFor(weapon) : null;
        List<AutofireAIPlugin> ais = group != null ? group.getAIPlugins() : null;
        if (ais == null) {
            return null;
        }
        for (int i = 0; i < ais.size(); i++) {
            AutofireAIPlugin p = ais.get(i);
            if (p == null || p.getWeapon() != weapon) {
                continue;
            }
            if (p instanceof WireTermAutofireShim) {
                shim = (WireTermAutofireShim) p; // already ours (re-resolve after a map sweep)
            } else {
                shim = new WireTermAutofireShim(p);
                ais.set(i, shim);
                beamTermShimInstalls++;
                // Close the engine's chargedown aim-null for the shim's tenure —
                // at beamSpeed 1e6 one aim-null frame is a full-range run-out (the measured
                // 48% out-of-arc armed-over class). Prior captured in the shim for restore.
                shim.installKeepTarget(weapon);
                beamTermKeepSet++;
                if (!beamTermShimLogged) {
                    beamTermShimLogged = true;
                    beamLane.log("predict v12 beam term-shim ACTIVE: wire terminus drives"
                            + " the native converge length cap");
                }
            }
            beamTermShims.put(weapon, shim);
            return shim;
        }
        return null;
    }

    /** The terminus length meter (meter tier, pure read): per recorded beam,
     *  compare the LIVE local beam's length ({@code weapon.getBeams()}, last swept endpoint)
     *  against the wire terminus distance. {@code over > 0} = the (114)(d) defect face (an
     *  observer beam locally longer than the server terminus + {@code TERM_LEN_EPS_SU});
     *  {@code capped} = the engagement positive the machine-control run grades on.
     *  Also feeds the round-4 differentiator census — the 5-bit local-state shape
     *  (legend at {@code beamTermShapeFrames}) tallied per armed
     *  converge frame / gate-stale frame / aim_over frame, plus the wire-distance server lens.
     *  Spec-census rows may now mint at zero frames (rows are fetched before the ray gates so
     *  no-ray frames can read the converge flag). */
    private void meterTermLength(WeaponAPI weapon,
                                 Vector2f muzzle,
                                 cc.starsector.client.net.ServerBeamState rec,
                                 ShipAPI ship) {
        // server-side lens: wire terminus distance BEFORE the ray gates, so records
        // with no live local ray still feed it (a near-zero envelope here would explain
        // aim_over_max ≈ full range from the serve side).
        float wdx = rec.toX() - muzzle.x;
        float wdy = rec.toY() - muzzle.y;
        float wireDist = (float) Math.sqrt(wdx * wdx + wdy * wdy);
        if (wireDist < beamTermWireMin) {
            beamTermWireMin = wireDist;
        }
        if (wireDist < 1f) {
            beamTermWireAtMuzzle++;
        }
        // Attribution: armed-at-meter-time splits the over classes — the shim armed on
        // the PREVIOUS frame and drains per sim tick, so armed here means the sweep that grew
        // this beam ran with the wire aim live (an over then = the spec ignored the aim).
        WireTermAutofireShim shim = beamTermShims.get(weapon);
        boolean armed = shim != null && shim.isArmed();
        long[] census = termCensusRow(weapon);
        List<BeamAPI> live = weapon.getBeams();
        BeamAPI b = live != null && !live.isEmpty() ? live.get(0) : null;
        boolean rayLive = b != null && b.getFrom() != null && b.getTo() != null;
        // The differentiator shape (gate-meter universe = armed converge frames): the
        // face's shape mix vs the baseline mix attributes the routing-flip candidate.
        // Bits read at METER time (one-frame skew vs the graded sweep, disclosed).
        int shape = -1;
        if (armed && census[4] == 1L) {
            shape = 0;
            WeaponGroupAPI group = ship != null ? ship.getWeaponGroupFor(weapon) : null;
            if (group != null && group.isAutofiring()) {
                shape |= 1;  // group still routes advanceAuto (OFF = the flip)
            }
            if (weapon.isFiring()) {
                shape |= 2;
            }
            if (weapon.getChargeLevel() > 0f) {
                shape |= 4;
            }
            if (weapon.usesAmmo() && weapon.getAmmo() == 0) {
                shape |= 8;  // the ammo-starve candidate (face (e) / (d) coupling)
            }
            if (rayLive) {
                shape |= 16; // the destroy-lingers question
            }
            beamTermShapeFrames[shape]++;
        }
        if (!rayLive) {
            return; // no live local ray — the shape row (bit4=0) is this frame's whole read
        }
        census[0]++;
        float ldx = b.getTo().x - b.getFrom().x;
        float ldy = b.getTo().y - b.getFrom().y;
        float localLen = (float) Math.sqrt(ldx * ldx + ldy * ldy);
        float over = localLen - (wireDist + TERM_LEN_EPS_SU);
        // The GATE meter (converge specs only — c0 specs ignore the aim BY DESIGN):
        // live length vs the shim's own aim ENVELOPE (max endpoint dist, lerp-phase-proof —
        // the engine consumed some convex lerp of those endpoints every tick since the arm).
        // over HERE = the engine ignored the fed aim: the face, ≈0 = the machine bar.
        // The vs-wire compare below keeps its exact terminus-meter semantics as the honest cost meter
        // of the interp (≤1 record-interval lag counts there, never here).
        // The gate-meter RE-CUT (its mechanism later corrected: a bit-equal endpoint = the sweep
        // recomputed it bit-equal under static aim / the shared-ray0 structural read, never an
        // idle freeze — rays don't outlive non-IDLE): grade only frames whose ray0 endpoint
        // MOVED since the last meter read — no consumed aim MOTION means nothing to grade, and
        // grading a static ray against a still-shrinking envelope was the
        // false-positive class. Sensitivity kept: an engine-ignored aim GROWS the ray
        // (beamSpeed×dt run-out) — the ignore frame itself always moves the endpoint.
        float[] raySample = beamTermRayPrev.get(weapon);
        boolean swept = raySample == null
                || raySample[0] != b.getTo().x || raySample[1] != b.getTo().y;
        if (armed && census[4] == 1L) {
            if (swept) {
                beamTermAimGraded++;
                float aimOver = localLen - (shim.armedEnvelopeDist(muzzle.x, muzzle.y)
                        + TERM_LEN_EPS_SU);
                if (aimOver > 0f) {
                    beamTermAimOverFrames++;
                    if (shape >= 0) {
                        beamTermShapeOver[shape]++; // The face's shape mix
                    }
                    if (aimOver > beamTermAimOverMax) {
                        beamTermAimOverMax = aimOver;
                    }
                }
            } else {
                beamTermAimStale++;
                if (shape >= 0) {
                    beamTermShapeStale[shape]++; // The frozen-ray shape mix
                }
            }
        }
        if (raySample == null) {
            raySample = new float[2];
            beamTermRayPrev.put(weapon, raySample);
        }
        raySample[0] = b.getTo().x;
        raySample[1] = b.getTo().y;
        if (over > 0f) {
            beamTermOverFrames++;
            if (armed) {
                beamTermOverArmed++;
            } else {
                beamTermOverFallback++;
            }
            census[armed ? 1 : 2]++;
            long overSu = (long) Math.ceil(over);
            if (overSu > census[3]) {
                census[3] = overSu;
            }
            if (over > beamTermOverMax) {
                beamTermOverMax = over;
            }
        } else {
            beamTermCappedFrames++;
        }
    }

    /** resolve-or-mint the per-spec attribution row (never null). Key =
     *  sanitized LOCAL weapon spec id; converge read once at mint from the API spec
     *  ({@code BeamWeaponSpecAPI.isConvergeOnPoint()}, 1/0/-1 unknown). Bounded at 24 distinct
     *  specs — overflow folds into {@code _other}. */
    private long[] termCensusRow(WeaponAPI weapon) {
        String id = weapon.getId();
        id = id == null ? "_null" : id.replaceAll("[^A-Za-z0-9_-]", "_");
        java.util.Map<String, long[]> census = beamTermSpecCensus;
        long[] row = census.get(id);
        if (row == null && census.size() >= 24 && !census.containsKey(id)) {
            id = "_other";
            row = census.get(id);
        }
        if (row == null) {
            long converge = -1L;
            try {
                com.fs.starfarer.api.loading.WeaponSpecAPI spec = weapon.getSpec();
                if (spec instanceof com.fs.starfarer.api.loading.BeamWeaponSpecAPI) {
                    converge = ((com.fs.starfarer.api.loading.BeamWeaponSpecAPI) spec)
                            .isConvergeOnPoint() ? 1L : 0L;
                }
            } catch (Throwable ignore) {
                // spec surface absent on some mount shapes — census rides converge=-1
            }
            row = new long[]{0L, 0L, 0L, 0L, converge};
            census.put(id, row);
        }
        return row;
    }

    /** The idle half of {@link #maintainBeamAim} (javadoc there): walk the
     *  relay's own resolution universe (1v1 {@code foeShip} / registry rows) and release every
     *  beam mount the fire window does not own this frame. */
    private void clearIdleBeamSuspend(java.util.Set<WeaponAPI> liveBeamMounts) {
        if (plugin.registryShape) {
            for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
                releaseIdleBeamMounts(re != null ? re.ship : null, liveBeamMounts);
            }
        } else {
            releaseIdleBeamMounts(plugin.foeShip, liveBeamMounts);
        }
    }

    private void releaseIdleBeamMounts(ShipAPI ship, java.util.Set<WeaponAPI> liveBeamMounts) {
        if (ship == null || !ship.isAlive()) {
            return;
        }
        List<WeaponAPI> weapons = plugin.weaponLists.weapons(ship); // per-frame cache
        if (weapons == null) {
            return;
        }
        for (int w = 0; w < weapons.size(); w++) {
            WeaponAPI weapon = weapons.get(w);
            if (weapon == null || !weapon.isBeam() || liveBeamMounts.contains(weapon)) {
                continue;
            }
            weapon.setSuspendAutomaticTurning(false);
            WireTermAutofireShim idleShim = beamTermShims.get(weapon); // Idle ⇒ native aim
            if (idleShim != null) {
                idleShim.disarmWireAim();
            }
            float rel = ((weapon.getCurrAngle() - ship.getFacing()) % 360f + 540f) % 360f - 180f;
            if (beamLiveMountsPrevFrame.contains(weapon)) {
                beamIdleReleases++;
                beamIdleLastRel.put(weapon, rel); // reseed only — snap jump is not slew
                if (!beamIdleReleaseLogged) {
                    beamIdleReleaseLogged = true;
                    beamLane.log("predict v12 beam idle-release ACTIVE: fire->idle suspend"
                            + " cleared; native autofire slew owns the idle pose");
                }
            } else {
                Float prevRel = beamIdleLastRel.put(weapon, rel);
                if (prevRel != null) {
                    float slewDelta = Math.abs(((rel - prevRel + 540f) % 360f) - 180f);
                    if (slewDelta > PredictReconcileCombatPlugin.SLEW_MOVED_DEG) {
                        beamIdleMovedFrames++;
                    }
                    if (slewDelta > beamIdleMaxDelta) {
                        beamIdleMaxDelta = slewDelta;
                    }
                }
            }
        }
    }

    /** The v12 beam-meter debrief line (beam-source, terminus, attribution and shape tiers) — the moved PRCP
     *  emitter, token strings byte-identical (the faraim-sweep
     *  grader contract); the glide census appends {@code beam_term_glides} / {@code beam_term_snaps} at the END, every earlier token unmoved. */
    void logMeter(String where) {
        if (PredictKnobConfig.BEAM_PRESENTATION_METER) {
            PilotWatchLog.info("predict v12 beam-meter [" + where + "]:"
                    + " mode=" + PredictKnobConfig.BEAM_PRESENTATION_MODE
                    + " beam_relay_mount_frames=" + beamRelayMountFrames
                    + " beam_relay_asserts=" + beamRelayAsserts
                    + " beam_relay_charge_stops=" + beamRelayChargeStops
                    + " beam_relay_idle_bridged=" + beamRelayIdleBridged
                    + " beam_relay_throws=" + beamRelayThrows
                    + " beam_cycle_mode=" + PredictKnobConfig.BEAM_CYCLE_MODE
                    + " beam_cycle_cd_zeroed=" + beamCycleCdZeroed
                    + " beam_cycle_ammo_fed=" + beamCycleAmmoFed
                    + " beam_cycle_burst_frames=" + beamCycleBurstFrames
                    + " beam_aim_records=" + beamAimRecords
                    + " beam_aim_sets=" + beamAimSets
                    + " beam_aim_unresolved=" + beamAimUnresolved
                    + " beam_idle_releases=" + beamIdleReleases
                    + " beam_idle_moved=" + beamIdleMovedFrames
                    + " beam_idle_maxdelta=" + beamIdleMaxDelta
                    + " beam_term_shim=" + beamTermShimInstalls
                    + " beam_term_arms=" + beamTermArms
                    + " beam_term_unshimmed=" + beamTermUnshimmed
                    + " beam_term_over=" + beamTermOverFrames
                    + " beam_term_over_armed=" + beamTermOverArmed
                    + " beam_term_over_fallback=" + beamTermOverFallback
                    + " beam_term_over_max=" + beamTermOverMax
                    + " beam_term_capped=" + beamTermCappedFrames
                    + " beam_term_keep=" + beamTermKeepSet
                    + " beam_term_aim_over=" + beamTermAimOverFrames
                    + " beam_term_aim_over_max=" + beamTermAimOverMax
                    + " beam_term_aim_graded=" + beamTermAimGraded
                    + " beam_term_aim_stale=" + beamTermAimStale
                    + " beam_term_shape=" + beamTermShapeTokens()
                    + " beam_term_wire_min=" + (beamTermWireMin == Float.MAX_VALUE
                            ? -1f : beamTermWireMin)
                    + " beam_term_wire_muzzle=" + beamTermWireAtMuzzle
                    + " beam_term_spec=" + beamTermSpecTokens()
                    + " beam_term_glides=" + beamTermGlides
                    + " beam_term_snaps=" + beamTermSnaps);
        }
    }
}
