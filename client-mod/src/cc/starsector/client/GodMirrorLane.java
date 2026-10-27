package cc.starsector.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import cc.starsector.client.net.ServerEntityPose;

/**
 * The god-mode replica mirror. The server's {@code /god}
 * rides five engine damage-taken {@code MutableStat} mults on the seat's hull (server
 * {@code AdminSeatDebugLane} + {@code AdminSeatDebugEnginePort.DAMAGE_TAKEN_STATS}); without a
 * mirror every other window's replica takes full LOCAL dents the server never deals, so its hull
 * bar dropped ~74 % and healed back over ~2 s (one measured sample:
 * {@code eng=2079.2 wire=8000}). The server marks the applied hull with the wire bit
 * {@link ServerEntityPose#FLAG_GOD}; this lane puts the SAME keyed mults on the local body.
 *
 * <p>Why these values: armor mult 0 makes the armor grid return 0 BEFORE the hull step, so
 * {@code getDamageToHull()} is exactly 0 and
 * the hull ledger never pushes an entry; the hull mult stays 1e-6, NEVER 0 (a hull mult 0 makes
 * {@code isTargetable} false, and local autofire would drop the
 * target the server still shoots). Shield flux, impact FX and shield numbers are untouched.
 *
 * <p>Carriers = ONE identity set (the {@code ReplicaCompDamageShield} pattern), never a per-slot
 * body: re-point sites keep a live old body across a slot change, and a non-god body left
 * carrying the mults would stop taking local dents. Release is the KEYED {@code unmodifyMult}
 * (never the no-arg {@code unmodify()}, which would wipe other lanes' mods on the same stats).
 * Releases: the {@code !god} edge, {@link #releaseForKill} at the head of
 * {@code AuthoritativePresenter.presentServerDeath} AND {@code presentServerFighterDeath} (the kill
 * blow is armor-subject — a mirrored body would survive its server death as a ghost; the dweller
 * seat's kill reaches the fighter twin directly), {@code respawnRemote}, and {@link #releaseAll}
 * at the MRST round wrap. Render thread only; per body per frame = one sentinel test, one bit
 * test, one identity lookup, no allocation.
 */
final class GodMirrorLane {

    /** The server's {@code AdminSeatDebugLane.GODMODE_STAT_ID} (parity row 8). */
    static final String STAT_ID = "cc_godmode";
    /** The server's {@code GODMODE_HULL_DAMAGE_TAKEN_MULT} (parity row 8); never 0 — see class doc. */
    static final float HULL_MULT = 1e-6f;
    /** Armor / EMP / engine / weapon damage-taken mult. */
    static final float OTHER_MULT = 0f;
    /** Edge log rows per round (reset by {@link #releaseAll}). */
    private static final int LOG_MAX_PER_ROUND = 32;

    private final Set<ShipAPI> carriers =
            Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
    private int applied;
    private int released;
    private int killReleased;
    private int unknownHold;
    private int roundResets;
    private int logRows;

    /** Per body per frame: the bit on the body's own pose edges the mults; unknown flags HOLD. */
    void maintain(ShipAPI body, ServerEntityPose pose) {
        if (body == null || pose == null) {
            return;
        }
        if (!pose.hasFluxFlags()) {
            unknownHold++;
            return;
        }
        boolean god = pose.isGodBit();
        if (!god && carriers.isEmpty()) {
            return;
        }
        boolean carrying = carriers.contains(body);
        if (god && !carrying) {
            // The own/1v1 CORPSE keeps its reference
            // while the respawn edge is held, and the server's latched god re-applies to the FRESH
            // hull under the same wire id — a corpse (hulk / hp 0 / removed) never carries; every
            // re-point is a new reference, so the fresh body applies once on its own edge.
            if (body.isAlive()) {
                apply(body);
            }
        } else if (!god && carrying) {
            release(body);
        }
    }

    private void apply(ShipAPI body) {
        MutableShipStatsAPI s = body.getMutableStats();
        s.getHullDamageTakenMult().modifyMult(STAT_ID, HULL_MULT);
        s.getArmorDamageTakenMult().modifyMult(STAT_ID, OTHER_MULT);
        s.getEmpDamageTakenMult().modifyMult(STAT_ID, OTHER_MULT);
        s.getEngineDamageTakenMult().modifyMult(STAT_ID, OTHER_MULT);
        s.getWeaponDamageTakenMult().modifyMult(STAT_ID, OTHER_MULT);
        carriers.add(body);
        applied++;
        logEdge("apply", body);
    }

    private void release(ShipAPI body) {
        MutableShipStatsAPI s = body.getMutableStats();
        s.getHullDamageTakenMult().unmodifyMult(STAT_ID);
        s.getArmorDamageTakenMult().unmodifyMult(STAT_ID);
        s.getEmpDamageTakenMult().unmodifyMult(STAT_ID);
        s.getEngineDamageTakenMult().unmodifyMult(STAT_ID);
        s.getWeaponDamageTakenMult().unmodifyMult(STAT_ID);
        carriers.remove(body);
        released++;
        logEdge("release", body);
    }

    /** Releases {@code body} iff it carries the mults; true when it did. */
    boolean releaseIfCarrying(ShipAPI body) {
        if (body != null && carriers.contains(body)) {
            release(body);
            return true;
        }
        return false;
    }

    /** The presenter's call, BEFORE the kill blow: a removed mod recomputes on the next damage read. */
    void releaseForKill(ShipAPI body) {
        if (releaseIfCarrying(body)) {
            killReleased++;
        }
    }

    /** MRST round wrap: release every carrier (bodies are unbound right after) and clear the set. */
    void releaseAll() {
        for (ShipAPI body : new ArrayList<ShipAPI>(carriers)) {
            release(body);
        }
        carriers.clear();
        roundResets++;
        logRows = 0;
    }

    private void logEdge(String edge, ShipAPI body) {
        if (logRows < LOG_MAX_PER_ROUND) {
            logRows++;
            PilotWatchLog.info("god-mirror edge=" + edge + " body="
                    + Integer.toHexString(System.identityHashCode(body)));
        }
    }

    /** Cumulative debrief tokens for the engine-meter flush line (never reset on read). */
    String meterTokens() {
        return " god_mirror_applied=" + applied
                + " god_mirror_released=" + released
                + " god_mirror_kill_released=" + killReleased
                + " god_mirror_carriers=" + carriers.size()
                + " god_mirror_unknown_hold=" + unknownHold;
    }

    int applied() {
        return applied;
    }

    int released() {
        return released;
    }

    int killReleased() {
        return killReleased;
    }

    int unknownHold() {
        return unknownHold;
    }

    int roundResets() {
        return roundResets;
    }

    int carrierCount() {
        return carriers.size();
    }
}
