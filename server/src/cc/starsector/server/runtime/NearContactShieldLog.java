package cc.starsector.server.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cc.starsector.server.protocol.AuthoritativeEntitySnapshot;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.ShipAllegianceSnapshot;
import cc.starsector.server.protocol.ShipFluxSnapshot;

/**
 * Near-contact shield-geometry log: per sim tick, one single-line
 * {@code npeer-serve near_contact} row for every cross-side ship pair inside a radius gate —
 * both poses (pos/vel/facing), both shield states (on/off/none + facing), separation distance
 * and closing speed. This is the server half of the F9 shield-ram diff:
 * the client already logs {@code vShieldOn}/{@code vShieldFacing} at damage-apply, but
 * {@code server.out.log} carried no per-tick shield/pose truth, so H1 (OMNI facing desync) vs
 * H2 (pose dead-reckon lag at 300+ su/s closing) could not be diffed. With this lane armed, a
 * contact tick joins client-vs-server on the wire {@code entityId} both logs print.
 *
 * <p>Reads ONLY the already-captured {@link AuthoritativeWorldSnapshot} — zero new engine
 * reads, zero {@code EngineCombatBinding} surface. Shield facing is the snapshot's U8-quantized
 * wire truth ({@code shieldFacingU8}, 1.40625°/step, ≤0.71° from the engine float): exactly
 * what the wire delivers, so the client diff isolates delivery/apply lag rather than encode
 * rounding. Sides come from {@code allegianceStates().fleetSide} (0/1 fighting, anything else
 * skipped — never {@code ownerConnectionId}, which is session authority, not combat side).
 *
 * <p>Residency: the per-pair gate is primitive squared-distance math on the sim thread;
 * String.format runs only for in-gate pairs (the {@code logHullDrops} /
 * {@code wave_probe_sample} accepted on-thread-println shape). Default OFF — constructed only
 * when the {@code cc.server.shieldContactLog} radius knob is positive, watch-tier arms only.
 * Security: every field is an engine-read primitive (no wire/player text), stdout is the
 * write-only sink, one line per emit. Fail-soft: any per-tick anomaly confesses once and the
 * instrument disarms rather than disturbing the serve loop; a process-lifetime row cap bounds
 * log growth the same way ({@code near_contact_saturated} confession, then silent).
 */
final class NearContactShieldLog {

    /** Wire facing step: U8 over the full circle (FoeStateLaneCoordinator's encode constant). */
    private static final float SHIELD_FACING_STEP_DEG = 360f / 256f;

    /** Process-lifetime emit bound: ~40 MB of worst-case rows, far past any one watch's need. */
    private static final int ROW_CAP = 200_000;

    /**
     * Two-band gate: inside this radius every tick emits (contact truth — the ram instant
     * itself); between it and the knob radius a pair must also be CLOSING fast (approach
     * truth). Without the closing gate a fortress brawl parks own hulls beside station
     * modules for whole rounds and floods the cap with stationary-proximity rows.
     */
    private static final float TIGHT_RADIUS_SQUARED = 250f * 250f;

    /** Outer-band closing-speed floor (su/s): the F9 ram class approaches at 300+. */
    private static final float MIN_CLOSING = 100f;

    private final float radiusSquared;
    private int rowsEmitted;
    private boolean disarmed;

    /** @param radiusSu center-to-center gate radius in world units (knob value, > 0). */
    NearContactShieldLog(int radiusSu) {
        this.radiusSquared = (float) radiusSu * (float) radiusSu;
        System.out.println("npeer-serve near_contact_armed radius=" + radiusSu
                + " cap=" + ROW_CAP);
    }

    /**
     * One post-step observation of the freshly-captured tick. Sim thread only (the snapshot's
     * capture thread); never throws — a read anomaly confesses one line and disarms.
     */
    void observe(AuthoritativeWorldSnapshot snap) {
        if (disarmed) {
            return;
        }
        try {
            List<ShipAllegianceSnapshot> sides = snap.allegianceStates();
            List<AuthoritativeEntitySnapshot> poses = snap.entities();
            if (sides == null || sides.isEmpty() || poses == null || poses.isEmpty()) {
                return; // allegiance tier unavailable this tick — nothing pairable, stay armed
            }
            Map<Long, AuthoritativeEntitySnapshot> poseById = new HashMap<>(poses.size() * 2);
            for (AuthoritativeEntitySnapshot pose : poses) {
                poseById.put(pose.entityId(), pose);
            }
            Map<Long, ShipFluxSnapshot> fluxById = null; // built lazily, most ticks emit nothing
            for (ShipAllegianceSnapshot own : sides) {
                if (own.fleetSide() != 0) {
                    continue;
                }
                AuthoritativeEntitySnapshot ownPose = poseById.get(own.entityId());
                if (ownPose == null) {
                    continue;
                }
                for (ShipAllegianceSnapshot foe : sides) {
                    if (foe.fleetSide() != 1) {
                        continue;
                    }
                    AuthoritativeEntitySnapshot foePose = poseById.get(foe.entityId());
                    if (foePose == null) {
                        continue;
                    }
                    float dx = foePose.locX() - ownPose.locX();
                    float dy = foePose.locY() - ownPose.locY();
                    float dist2 = dx * dx + dy * dy;
                    if (dist2 > radiusSquared || dist2 <= 0f) {
                        continue;
                    }
                    float dist = (float) Math.sqrt(dist2);
                    // Radial approach rate along the line of centers; positive = closing.
                    float closing = -((dx * (foePose.velX() - ownPose.velX()))
                            + (dy * (foePose.velY() - ownPose.velY()))) / dist;
                    if (dist2 > TIGHT_RADIUS_SQUARED && closing < MIN_CLOSING) {
                        continue; // outer band: approach truth only (see TIGHT_RADIUS_SQUARED)
                    }
                    if (fluxById == null) {
                        fluxById = fluxByEntityId(snap.fluxStates());
                    }
                    emitRow(snap.simTick(), ownPose, foePose, dist, closing,
                            fluxById.get(own.entityId()), fluxById.get(foe.entityId()));
                    if (disarmed) {
                        return;
                    }
                }
            }
        } catch (RuntimeException e) {
            disarmed = true;
            System.out.println("npeer-serve near_contact_disarmed cause="
                    + e.getClass().getSimpleName());
        }
    }

    private static Map<Long, ShipFluxSnapshot> fluxByEntityId(List<ShipFluxSnapshot> fluxStates) {
        if (fluxStates == null || fluxStates.isEmpty()) {
            return Map.of(); // flux tier unavailable: rows carry shield=none, pose half still pays
        }
        Map<Long, ShipFluxSnapshot> byId = new HashMap<>(fluxStates.size() * 2);
        for (ShipFluxSnapshot flux : fluxStates) {
            byId.put(flux.entityId(), flux);
        }
        return byId;
    }

    private void emitRow(long tick, AuthoritativeEntitySnapshot own, AuthoritativeEntitySnapshot foe,
            float dist, float closing, ShipFluxSnapshot ownFlux, ShipFluxSnapshot foeFlux) {
        System.out.println(String.format(Locale.ROOT,
                "npeer-serve near_contact tick=%d own=%d foe=%d dist=%.1f closing=%.1f"
                        + " own_x=%.1f own_y=%.1f own_vx=%.1f own_vy=%.1f own_face=%.1f"
                        + " own_shield=%s own_sface=%.1f"
                        + " foe_x=%.1f foe_y=%.1f foe_vx=%.1f foe_vy=%.1f foe_face=%.1f"
                        + " foe_shield=%s foe_sface=%.1f",
                tick, own.entityId(), foe.entityId(), dist, closing,
                own.locX(), own.locY(), own.velX(), own.velY(), own.facing(),
                shieldWord(ownFlux), shieldFacingDeg(ownFlux),
                foe.locX(), foe.locY(), foe.velX(), foe.velY(), foe.facing(),
                shieldWord(foeFlux), shieldFacingDeg(foeFlux)));
        if (++rowsEmitted >= ROW_CAP) {
            disarmed = true;
            System.out.println("npeer-serve near_contact_saturated rows=" + rowsEmitted);
        }
    }

    private static String shieldWord(ShipFluxSnapshot flux) {
        if (flux == null || !flux.hasShield()) {
            return "none";
        }
        return flux.shieldOn() ? "on" : "off";
    }

    private static float shieldFacingDeg(ShipFluxSnapshot flux) {
        return flux == null ? 0f : flux.shieldFacingU8() * SHIELD_FACING_STEP_DEG;
    }
}
