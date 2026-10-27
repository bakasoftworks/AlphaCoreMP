package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.impl.combat.MoteControlScript;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

/**
 * The CLIENT half of the mote census (twin of the server's
 * {@code MoteCensusProbe}): once per second, the own ship's LOCAL vanilla mote generator state, so the
 * reported "Ziggurat mote generator stuck" (attractor FX loop, no motes, survived
 * /suicide + respawn) is diffed against the vanilla spawn gate on the side that actually paints the
 * player's motes.
 *
 * <p>Why the client side is the suspect: the player's own motes are the OWN body's local
 * {@code MoteControlScript} spawns (own-sourced wire missiles are never presence-adopted —
 * {@code MissilePoseLane}), so a stalled LOCAL generator hides even healthy server motes; the
 * vanilla shared data is keyed {@code ship + "_mote_AI_shared"} (engine customData,
 * {@code MoteControlScript}) and the engine's {@code Ship.toString()}
 * is {@code variantId + " " + name + " [" + hullId + "]"} —
 * so a RESPAWNED own body (same variant/name) INHERITS the dead body's data (the row prints the body's
 * identity hash next to the key to show exactly that); the loop sound the player heard plays iff
 * {@code motes.size() > 3}; a spawn needs {@code motes.size() < maxMotes && attractorLock
 * == null && !overloadedOrVenting} after the {@code isMissileAlive} prune.
 *
 * <p>REPLICAS-ONLY: {@link MoteReplicaLane} pins the local generator off
 * ({@code maxMotes=0}) and lists the server-mote replicas on the shared data, so from that build
 * {@code motes=} counts REPLICAS on the shared list, {@code listOwnMotes=} is expected 0 (any
 * non-zero = the pin failed) and {@code replicas=} counts every mote-spec replica body in the engine.
 *
 * <p>Presentation-side, read-only, fail-soft (one throw disarms the lane for the session). The shared
 * data is read through the typed API class ({@code MoteControlScript.SharedMoteAIData} lives in
 * {@code starfarer.api.jar} — B-legal; {@code java.lang.reflect} is NOT, the b-deny gate catch).
 * Rows ride the sidelog
 * ({@code predict own-mote census …}), capped at {@link #ROW_CAP}; a row is also emitted the second
 * the gate SIGNATURE changes ({@code edge=true}). Teardown totals ride {@link #meterTokens()}.
 */
final class OwnMoteCensusLane {
    static final float PERIOD_SEC = 1f;
    static final int ROW_CAP = 2400;
    static final String KEY_SUFFIX = "_mote_AI_shared";

    private float acc;
    private int rows;
    private int edges;
    private boolean disarmed;
    private String lastSig;
    private int lastBody;
    private int bodyChanges;
    private int inheritedAtRespawn;

    /** Once per render frame from the plugin's advance body; self-paced to {@link #PERIOD_SEC}. */
    void tick(PredictReconcileCombatPlugin plugin, float amount) {
        if (disarmed || rows >= ROW_CAP || plugin == null || plugin.engine == null) {
            return;
        }
        acc += amount;
        if (acc < PERIOD_SEC) {
            return;
        }
        acc = 0f;
        try {
            sample(plugin);
        } catch (Throwable t) {
            disarmed = true;
            PilotWatchLog.info("predict own-mote census DISARMED: " + t);
        }
    }

    private void sample(PredictReconcileCombatPlugin plugin) throws Exception {
        CombatEngineAPI engine = plugin.engine;
        ShipAPI own = engine.getPlayerShip();
        if (own == null) {
            return;
        }
        int body = System.identityHashCode(own);
        boolean bodyChanged = lastBody != 0 && body != lastBody;
        if (bodyChanged) {
            bodyChanges++;
        }
        lastBody = body;
        String key = own + KEY_SUFFIX;
        Object raw = engine.getCustomData().get(key);
        MoteControlScript.SharedMoteAIData data =
                raw instanceof MoteControlScript.SharedMoteAIData ? (MoteControlScript.SharedMoteAIData) raw : null;
        int maxMotes = MoteControlScript.getMaxMotes(own);
        Vector2f ownLoc = own.getLocation();
        List<MissileAPI> engineMissiles = engine.getMissiles();
        Map<Object, Boolean> inList = new IdentityHashMap<>();
        int listOwnMotes = 0;
        int listOwnMotesFizz = 0;
        int replicas = 0;
        // The LIGHT census: vanilla lights a mote on exactly the frames its
        // steering issued ACCELERATE, and the controller's isAccelerating() IS that per-frame command
        // flag (cleared at the top of each processCommands) — so on the
        // twin `litCmd` over the OWN motes is the vanilla duty; a served replica never receives the
        // command (the puppet lights via showAccelerating), so its light is the puppet's own decision
        // (MissilePoseLane.moteLitNow) and litCmd there must read 0.
        int litOwnCmd = 0;
        int litReplica = 0;
        int litReplicaCmd = 0;
        for (MissileAPI m : engineMissiles) {
            inList.put(m, Boolean.TRUE);
            if (!MissilePoseLane.isMoteSpec(m)) {
                continue;
            }
            ShipEngineControllerAPI ec = m.getEngineController();
            boolean cmd = ec != null && ec.isAccelerating();
            if (m.getSource() == own) {
                listOwnMotes++;
                if (m.isFizzling()) {
                    listOwnMotesFizz++;
                }
                if (cmd) {
                    litOwnCmd++;
                }
            } else {
                replicas++; // Sourceless server-mote replicas (any side) in the engine list
                if (plugin.missileLane.moteLitNow(m)) {
                    litReplica++;
                }
                if (cmd) {
                    litReplicaCmd++;
                }
            }
        }
        // lit=N/M over the side's population: replicas on a served window, own motes on the twin.
        int litN = replicas > 0 ? litReplica : litOwnCmd;
        int litM = replicas > 0 ? replicas : listOwnMotes;
        int litCmd = litOwnCmd + litReplicaCmd;
        // Rider: a serverless twin never sees a snapshot tick — synthesize one from the engine
        // clock so the rig's tick-bucketed table aligns the twin's rows too.
        long tick = plugin.lastSnapshotSimTick;
        if (tick == Long.MIN_VALUE) {
            tick = Math.round(engine.getTotalElapsedTime(false) * ServerTickRate.ticksPerSecond());
        }
        int size = -1;
        int alive = 0;
        int listed = 0;
        int fizz = 0;
        int nonFinite = 0;
        float maxDist = 0f;
        float oldest = 0f;
        String lockDesc = "-";
        float remaining = Float.NaN;
        String target = "-";
        if (data != null) {
            List<MissileAPI> copy = data.motes == null ? new ArrayList<>() : new ArrayList<>(data.motes);
            size = copy.size();
            for (MissileAPI m : copy) {
                if (m == null) {
                    continue;
                }
                if (engine.isMissileAlive(m)) {
                    alive++;
                }
                if (inList.containsKey(m)) {
                    listed++;
                }
                if (m.isFizzling()) {
                    fizz++;
                }
                Vector2f loc = m.getLocation();
                if (loc == null || Float.isNaN(loc.x) || Float.isNaN(loc.y)
                        || Float.isInfinite(loc.x) || Float.isInfinite(loc.y)) {
                    nonFinite++;
                } else if (ownLoc != null) {
                    float dx = loc.x - ownLoc.x;
                    float dy = loc.y - ownLoc.y;
                    maxDist = Math.max(maxDist, (float) Math.sqrt(dx * dx + dy * dy));
                }
                oldest = Math.max(oldest, m.getFlightTime());
            }
            ShipAPI lock = data.attractorLock;
            lockDesc = lock == null ? "null" : lock.getId() + ":" + (lock.isAlive() ? "alive" : "dead");
            remaining = data.attractorRemaining;
            Vector2f t = data.attractorTarget;
            target = t == null ? "null" : String.format(Locale.ROOT, "%.0f,%.0f", t.x, t.y);
            if (bodyChanged && size > 0) {
                inheritedAtRespawn++; // the new body found the old body's motes under the same key
            }
        }
        boolean hulk = own.isHulk();
        boolean phased = own.isPhased();
        boolean ovlVent = own.getFluxTracker() != null && own.getFluxTracker().isOverloadedOrVenting();
        ShipSystemAPI system = own.getSystem();
        String sys = system == null ? "-"
                : system.getState() + "/" + String.format(Locale.ROOT, "%.2f", system.getEffectLevel());
        String sig = (data != null) + "|" + hulk + "|" + (size >= maxMotes) + "|" + (!"null".equals(lockDesc) && data != null)
                + "|" + (alive == 0) + "|" + ovlVent + "|" + (nonFinite > 0) + "|" + (listOwnMotes == 0);
        boolean edge = lastSig == null || !lastSig.equals(sig) || bodyChanged;
        lastSig = sig;
        if (edge) {
            edges++;
        }
        rows++;
        PilotWatchLog.info(String.format(Locale.ROOT,
                "predict own-mote census tick=%d edge=%s body=%08x bodyChanged=%s key=\"%s\" data=%s"
                        + " maxMotes=%d motes=%d moteAlive=%d inList=%d fizz=%d nonFinite=%d maxDist=%.0f oldest=%.1f"
                        + " lock=%s remaining=%.2f target=%s | listOwnMotes=%d listFizz=%d replicas=%d"
                        + " lit=%d/%d litCmd=%d"
                        + " hulk=%s phased=%s ovlVent=%s sys=%s hp=%.0f"
                        + (rows == ROW_CAP ? " [row cap reached; counters continue]" : ""),
                tick, edge, body, bodyChanged, own, data != null, maxMotes, size, alive, listed,
                fizz, nonFinite, maxDist, oldest, lockDesc, remaining, target, listOwnMotes, listOwnMotesFizz, replicas,
                litN, litM, litCmd,
                hulk, phased, ovlVent, sys, own.getHitpoints()));
    }

    /** Meter fragment (pinned key names; rides the spawn-gate meter line beside the mote FX tokens). */
    String meterTokens() {
        return " predict_own_mote_census_rows=" + rows
                + " predict_own_mote_census_edges=" + edges
                + " predict_own_mote_body_changes=" + bodyChanges
                + " predict_own_mote_inherited_at_respawn=" + inheritedAtRespawn
                + " predict_own_mote_census_disarmed=" + disarmed;
    }
}
