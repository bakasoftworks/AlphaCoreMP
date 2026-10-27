package cc.starsector.client;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;

/**
 * (62) canister-flak MEASURE instrument, client half (measure-first
 * — no fix rides this class). The chain to test: defense=canister_flak lives in
 * the engine's phaseCloak FIELD; client bit-11 mirrors {@code getPhaseCloak().isOn()}; a
 * zero-duration WEAPON system has ~no IN/ACTIVE dwell, so bit-11 (near-)never rises and the server
 * never presses. This probe logs, at a bounded cadence: the own hull's defense-slot system state
 * (EDGE lines on change + a periodic row), the bit-11/bit-7 frame counters from the capture site,
 * and a {@code canister_flak_proj} first-sight census over the local sim (foe-lane presentation
 * half: births here vs births server-side). A REAL phase hull on seat (grendel) makes the same
 * rows the instrument's positive control: its cloak dwell provably raises bit-11.
 * Log-only; single-line printable rows; bounded allocation (live-set sweep each frame, edge lines
 * capped, counters continue past the cap).
 *
 * Own-file per the PredictReconcileCombatPlugin growth-anchor law:
 * PRCP carries only the field + tick/reset delegate calls.
 */
final class CanisterFlakProbe {

    /** Emission cadence in advance frames (~5s at 60fps) — matches the PRCP meter idiom. */
    private static final int CADENCE = 300;
    /** Cap on defense-state EDGE lines per run (right-click spam safety); counters continue. */
    private static final int EDGE_LOG_MAX = 60;
    private static final String PROJ_SPEC = "canister_flak_proj";

    private Set<DamagingProjectileAPI> live = Collections.newSetFromMap(new IdentityHashMap<>());
    private int births;
    private int edgeLines;
    private int edges;
    private String lastState = "";

    /** Per-frame pass, delegated from PRCP advance beside fuseMeter/dmgWitness. */
    void tick(CombatEngineAPI engine, ShipAPI own, long bit11Frames, long bit7Frames,
            int pressRelays, long advFrames) {
        try {
            Set<DamagingProjectileAPI> now = Collections.newSetFromMap(new IdentityHashMap<>());
            for (DamagingProjectileAPI p : engine.getProjectiles()) {
                if (p != null && PROJ_SPEC.equals(p.getProjectileSpecId())) {
                    now.add(p);
                }
            }
            for (DamagingProjectileAPI p : now) {
                if (!live.contains(p)) {
                    births++;
                }
            }
            live = now;

            ShipSystemAPI cloak = own != null ? own.getPhaseCloak() : null;
            String state = cloak == null ? "none"
                    : cloak.getState() + " ammo=" + cloak.getAmmo()
                            + " cd=" + cloak.getCooldownRemaining() + " on=" + cloak.isOn();
            if (!state.equals(lastState)) {
                lastState = state;
                edges++;
                if (edgeLines < EDGE_LOG_MAX) {
                    edgeLines++;
                    PilotWatchLog.info("canister probe EDGE #" + edges + " own defense state=" + state
                            + " at advance@" + advFrames
                            + (edgeLines == EDGE_LOG_MAX ? " [edge log cap reached; counter continues]" : ""));
                }
            }
            if (advFrames % CADENCE == 0) {
                PilotWatchLog.info("canister probe own=" + (own == null ? "null" : own.getHullSpec().getHullId())
                        + " defense=" + state.replace(' ', ',')
                        + " edges=" + edges + " bit11Frames=" + bit11Frames + " bit7Frames=" + bit7Frames
                        + " pressRelays=" + pressRelays // Detector count; splits detect vs capture
                        + " projBirths=" + births + " projLive=" + live.size()
                        + " at advance@" + advFrames);
            }
        } catch (Throwable ignore) {
            // instrument is read-only presentation-side; never let it break the watch
        }
    }

    /** Round-reset (dead round's projectiles are gone; edge baseline re-adopts next frame). */
    void reset() {
        live = Collections.newSetFromMap(new IdentityHashMap<>());
        lastState = "";
    }
}
