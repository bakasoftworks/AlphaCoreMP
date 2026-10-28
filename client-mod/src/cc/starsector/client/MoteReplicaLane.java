package cc.starsector.client;

import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.MoteControlScript;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

/**
 * REPLICAS-ONLY MOTES.
 *
 * <p><b>The defect it removes.</b> Vanilla's {@code MoteControlScript} is a ship-system stats script
 * flagged {@code runScriptWhileIdle} ({@code apply} runs
 * on EVERY advance), so on this client it runs on the own body AND on every Ziggurat replica, and
 * spawns up to 30 LOCAL motes per ship ({@code apply}) that the server never sees and no
 * other player sees. The server's real motes ALSO arrive — as sourceless presence-adopts
 * ({@link MissilePoseLane#adoptSourceless}: the vanilla mint passes a null weapon, the server's pair
 * read is mount-keyed, so the wire carries no source) painted as NOOP pose puppets. Two swarms per
 * Ziggurat on one screen, one of them a phantom; a player's "generator stuck, spawning over and
 * over" tell is that phantom generator refilling with its
 * launch flash + sound while the real swarm sat beside it.
 *
 * <p><b>The rule.</b> Authority stays server-side; the replicas ARE the motes on every client. Three
 * moves, all presentation, zero wire cost (prefer-client-side law):
 * <ol>
 * <li><b>No local generator.</b> The vanilla spawn gate is
 *     {@code data.motes.size() < getMaxMotes(ship)} and {@code getMaxMotes} reads the PUBLIC
 *     static table {@code MoteControlScript.MOTE_DATA} (both launcher ids). Pinning
 *     {@code maxMotes = 0} for the served window makes the gate false on every ship in the engine —
 *     no reflection, no data override, B-legal. {@code getMaxMotes} has exactly two callers: the
 *     gate and the hum volume (which then saturates at 1.0 above 3 motes instead of
 *     ramping to 30 — accepted). Pinned + re-asserted every frame ({@link #tick}) on the SERVED
 *     path only (init after the session-null return, advance gated on {@code session != null} —
 *     a connect-fail hold-open or a serverless window never reaches
 *     teardown's restore, so it must never pin), RESTORED in
 *     {@code PredictReconcileCombatPlugin.teardownActive()} (the {@code REFIT_MODE_ADVANCE}
 *     precedent: a process-wide static must not outlive the served window — a later vanilla mission
 *     in the same JVM keeps its motes).</li>
 * <li><b>Replicas ride the vanilla shared list.</b> Each adopted mote replica is added to the nearest
 *     same-side Ziggurat's {@code SharedMoteAIData.motes} (public field; the census lane already reads
 *     it typed). Vanilla then does the rest itself on that ship's own script: the swarm hum
 *     (centroid + volume from that list), the {@code isMissileAlive} prune drops
 *     retired replicas, and the attractor lock keeps its duration — with an EMPTY list vanilla clears
 *     the lock every frame and then re-runs {@code calculateTargetData}
 *     + re-adds the target jitter plugin EVERY frame at effectLevel 1, a plugin
 *     pile-up the non-empty list prevents. The hulk branch flames the replicas out when the
 *     ship dies — the server does the same to its motes on the same tick.</li>
 * <li><b>Launch FX from the replica.</b> A replica adopted within {@link #LAUNCH_FX_SLOT_RADIUS} of one
 *     of the ship's SMALL system slots is a fresh launch (motes mint AT the slot; the adopt lags
 *     the mint by ~2 wire ticks): vanilla's muzzle flash at that slot +
 *     {@code mote_attractor_launch_mote}. Farther replicas (a joiner adopting a mid-life
 *     swarm, an attractor-flung mote) get no launch FX — the first-sight limit the mine arm documents.</li>
 * </ol>
 * The own ship's attractor arc + lock jitter stay vanilla-local (the own system is predicted locally,
 * and vanilla's own attractor code runs as before); remote activators keep {@link MoteAttractorFxLane}. Knob
 * {@code -Dcc.predict.moteReplica=off|meter|apply} (default apply; {@code meter} = count only, the
 * phantom generator stays ON); fail-soft throughout (every throw counted + logged, never rethrown).
 */
final class MoteReplicaLane {
    /** The mote discriminator on a SPEC ID ({@code mote} / {@code mote_hf},
     *  the two {@code data/shipsystems/proj} motes). STRICT — a null/unreadable spec is NOT a
     *  mote here (the sourceless FX class must not swallow an unknown body); the fail-closed
     *  puppet read stays {@code MissilePoseLane.isMoteSpec(MissileAPI)}. */
    static boolean isMoteSpec(String specId) {
        return "mote".equals(specId) || "mote_hf".equals(specId);
    }

    static final String MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.moteReplica", System.getProperty("cc.predict.moteReplica", "apply"));
    static final boolean METER = !"off".equals(MODE);
    static final boolean APPLY = "apply".equals(MODE);
    /** The Ziggurat's system id (vanilla {@code ziggurat.ship}) — the only vanilla mote generator. */
    static final String SYSTEM_ID = "mote_control";
    /** The mote-launch sound vanilla's {@code MoteControlScript} plays. */
    static final String LAUNCH_SOUND = "mote_attractor_launch_mote";
    /** Fresh-launch radius around a SMALL system slot (world units). */
    static final float LAUNCH_FX_SLOT_RADIUS = 80f;
    /** Ship-assignment radius = collision radius + this pad (motes orbit close; an attractor-flung one
     *  can be 3000 away — unassigned ones are counted, not listed). */
    static final float ASSIGN_RADIUS_PAD = 400f;
    private static final int LOG_MAX = 24;

    private boolean pinned;
    private int pinnedNormal = -1;
    private int pinnedHf = -1;
    private long pinFrames;
    private int adopts;
    private int listed;
    private int launchFx;
    private int unassigned;
    private int meterCandidates;
    private int throwsSeen;
    private int logLines;

    /** Once per frame (and once from {@code init}): pin the vanilla generator off — two map reads. */
    void tick() {
        if (!APPLY) {
            return;
        }
        try {
            MoteControlScript.MoteData normal = MoteControlScript.MOTE_DATA.get(MoteControlScript.MOTELAUNCHER);
            MoteControlScript.MoteData hf = MoteControlScript.MOTE_DATA.get(MoteControlScript.MOTELAUNCHER_HF);
            if (!pinned) {
                pinnedNormal = normal == null ? -1 : normal.maxMotes;
                pinnedHf = hf == null ? -1 : hf.maxMotes;
                pinned = true;
                log("local generator PINNED off for the served window (maxMotes " + pinnedNormal + "/"
                        + pinnedHf + " -> 0; replicas are the motes)");
            }
            if (normal != null && normal.maxMotes != 0) {
                normal.maxMotes = 0;
            }
            if (hf != null && hf.maxMotes != 0) {
                hf.maxMotes = 0;
            }
            pinFrames++;
        } catch (Throwable t) {
            throwsSeen++;
            log("pin THREW " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** {@code teardownActive()}: the vanilla table comes back (captured values; the class constants
     *  when the capture itself read a stale 0 from an earlier window whose restore never ran). */
    void restore() {
        if (!pinned) {
            return;
        }
        pinned = false;
        try {
            MoteControlScript.MoteData normal = MoteControlScript.MOTE_DATA.get(MoteControlScript.MOTELAUNCHER);
            MoteControlScript.MoteData hf = MoteControlScript.MOTE_DATA.get(MoteControlScript.MOTELAUNCHER_HF);
            int toNormal = pinnedNormal > 0 ? pinnedNormal : MoteControlScript.MAX_MOTES;
            int toHf = pinnedHf > 0 ? pinnedHf : MoteControlScript.MAX_MOTES_HF;
            if (normal != null) {
                normal.maxMotes = toNormal;
            }
            if (hf != null) {
                hf.maxMotes = toHf;
            }
            log("local generator RESTORED (maxMotes " + toNormal + "/" + toHf + ") after " + pinFrames + " pinned frames");
        } catch (Throwable t) {
            throwsSeen++;
            log("restore THREW " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * {@link MissilePoseLane#adoptSourceless} relay for a mote-spec body: list it on the nearest
     * same-side Ziggurat's shared data and paint the launch FX when it sits at a launch slot.
     */
    void onReplicaAdopted(MissileAPI missile, int owner, CombatEngineAPI engine) {
        if (!METER || missile == null || engine == null) {
            return;
        }
        try {
            adopts++;
            // Vanilla's mint strips the friend-or-foe indicator layer from
            // every mote — a replica keeps parity with the mint line.
            try {
                missile.getActiveLayers().remove(CombatEngineLayers.FF_INDICATORS_LAYER);
            } catch (Throwable ignore) {
                // presentation nicety; never block the listing below
            }
            Vector2f at = missile.getLocation();
            if (at == null) {
                unassigned++;
                return;
            }
            ShipAPI best = null;
            float bestDist = Float.MAX_VALUE;
            for (ShipAPI s : engine.getShips()) {
                if (s == null || s.getOwner() != owner || !s.isAlive() || s.isHulk()) {
                    continue;
                }
                ShipSystemAPI sys = s.getSystem();
                if (sys == null || !SYSTEM_ID.equals(sys.getId())) {
                    continue;
                }
                Vector2f sl = s.getLocation();
                if (sl == null) {
                    continue;
                }
                float d = dist(sl, at);
                if (d <= s.getCollisionRadius() + ASSIGN_RADIUS_PAD && d < bestDist) {
                    best = s;
                    bestDist = d;
                }
            }
            if (best == null) {
                unassigned++;
                log("mote replica with no same-side Ziggurat within reach (owner=" + owner + " at "
                        + (int) at.x + "," + (int) at.y + ") — counted, not listed");
                return;
            }
            WeaponSlotAPI slot = nearestSmallSystemSlot(best, at);
            if (!APPLY) {
                meterCandidates++;
                log("METER would-list on " + ident(best) + " dist=" + (int) bestDist + " launchSlot="
                        + (slot != null) + " (mode=meter, nothing written)");
                return;
            }
            MoteControlScript.SharedMoteAIData data = MoteControlScript.getSharedData(best);
            List<MissileAPI> motes = data.motes;
            boolean present = false;
            for (MissileAPI m : motes) {
                if (m == missile) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                motes.add(missile);
                listed++;
            }
            if (slot != null) {
                WeaponSpecAPI spec = Global.getSettings().getWeaponSpec(MoteControlScript.getWeaponId(best));
                float dir = missile.getFacing();
                if (spec != null) {
                    engine.spawnMuzzleFlashOrSmoke(best, slot, spec, 0, dir);
                }
                Global.getSoundPlayer().playSound(LAUNCH_SOUND, 1f, 0.25f, slot.computePosition(best), new Vector2f());
                launchFx++;
            }
            if (listed <= 3 || (listed % 100) == 0) {
                log("listed #" + listed + " on " + ident(best) + " dist=" + (int) bestDist + " list=" + motes.size()
                        + " launchFx=" + (slot != null));
            }
        } catch (Throwable t) {
            throwsSeen++;
            log("adopt THREW " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Meter fragment (pinned key names; rides the spawn-gate meter line beside the mote FX tokens). */
    String meterTokens() {
        return " predict_mote_replica_mode=" + MODE
                + " predict_mote_replica_pinned=" + pinned
                + " predict_mote_replica_pin_frames=" + pinFrames
                + " predict_mote_replica_adopts=" + adopts
                + " predict_mote_replica_listed=" + listed
                + " predict_mote_replica_launch_fx=" + launchFx
                + " predict_mote_replica_unassigned=" + unassigned
                + " predict_mote_replica_meter_candidates=" + meterCandidates
                + " predict_mote_replica_throws=" + throwsSeen;
    }

    // ------------------------------------------------------------------ laws

    /** As in vanilla's {@code findSlots}, the SMALL system slots are the launch slots; the nearest one within
     *  {@link #LAUNCH_FX_SLOT_RADIUS} of the body, else null (a mid-life adopt). */
    static WeaponSlotAPI nearestSmallSystemSlot(ShipAPI s, Vector2f at) {
        try {
            if (s.getHullSpec() == null) {
                return null;
            }
            WeaponSlotAPI best = null;
            float bestDist = LAUNCH_FX_SLOT_RADIUS;
            for (WeaponSlotAPI slot : s.getHullSpec().getAllWeaponSlotsCopy()) {
                if (!slot.isSystemSlot() || slot.getSlotSize() != WeaponAPI.WeaponSize.SMALL) {
                    continue;
                }
                float d = dist(slot.computePosition(s), at);
                if (d <= bestDist) {
                    best = slot;
                    bestDist = d;
                }
            }
            return best;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static float dist(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static String ident(ShipAPI s) {
        String hull = s.getHullSpec() != null ? s.getHullSpec().getHullId() : "?";
        return hull + "#" + s.getId() + "@" + s.getOwner();
    }

    private void log(String msg) {
        if (logLines >= LOG_MAX) {
            return;
        }
        logLines++;
        PilotWatchLog.info("predict threat mote-replica: " + msg
                + (logLines == LOG_MAX ? " [lane log cap; counters continue]" : ""));
    }
}
