package cc.starsector.client;

import cc.starsector.client.net.ClientDespawnReason;
import cc.starsector.client.net.ClientEntityDespawn;
import cc.starsector.client.net.ClientManeuverIntent;
import cc.starsector.client.net.ClientMovement;
import cc.starsector.client.net.InterpolatedPose;
import cc.starsector.client.net.RosterLifecycleTracker;
import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.net.ServerSnapshotClient;
import cc.starsector.client.net.ServerSnapshotFrame;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.input.InputEventAPI;
import org.lwjgl.input.Keyboard;
import org.lwjgl.util.vector.Vector2f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spike 2 of the client-integration arc: drive a combat puppet from
 * the <em>live authoritative server snapshot stream</em> instead of the Spike-1
 * scripted circle.
 *
 * <p>Spike 1 ({@link PuppetCombatPlugin}) proved the real engine renders
 * externally-driven kinematics from a path the local sim did not produce. This
 * plugin keeps the exact same per-frame writes
 * ({@code getLocation()/getVelocity()/setFacing/setAngularVelocity}) but sources
 * the pose from a {@link ServerSnapshotClient}: each frame it reads the latest
 * decoded {@link ServerSnapshotFrame}, selects one server ship entity, and writes
 * that authoritative pose onto the local puppet ship. The Starsector window now
 * renders server truth over a real socket.
 *
 * <p>The client holds no authority — it only writes server state onto the engine
 * and never reads authority back out — so it is consistent with the snapshot-authoritative-only
 * rule and the no-authority client contract.
 *
 * <p>When no server frame is available yet (server not up, or no snapshot received)
 * the puppet is left untouched, so the live observation is unambiguous: motion that
 * tracks the server is the proof. If, once tracking a real (non-smooth) server
 * path, the puppet's own AI fights the override, that is when Spike 1b's no-op
 * {@code ShipAIPlugin} suppression earns its place (deliberately not added yet —
 * Spike 1 saw no AI fight against a per-frame override).
 *
 * <p>Spike 3 adds the return path: local movement keys
 * captured in {@link #processInputPreCoreControls} are mapped to a
 * {@link ClientManeuverIntent} and handed to the {@link ServerSnapshotClient}, which
 * sends it to the server over the existing {@code client.ship_movement.requested}
 * seam. The server applies it and the resulting motion comes back in the snapshot
 * stream this plugin already renders — so the human pilots through the server. The
 * intent targets one server ship (see {@link #maneuverShipId()}); the maneuver
 * authority for a second owned ship is the real-engine half of Spike 4,
 * separate from the render generalization below.
 *
 * <p>Spike 4, client-side render half: instead of driving a
 * single entity-0 puppet, this plugin keeps a stable {@code serverId -> ShipAPI} map
 * ({@link #puppetByServerId}) and renders <em>every</em> server entity in the frame
 * onto its own distinct local puppet. The binding is assigned once (ascending server
 * entity id &rarr; next free ally ship) and kept, so a puppet never swaps which server
 * entity it tracks. The interpolator already keys on entity id, so each puppet smooths
 * independently with no per-entity buffer. With the demo server's two-ship binding
 * ({@code -Dcc.fake.ships=2}) the owner now watches two separately server-driven
 * puppets, not one.
 */
public class ServerFedPuppetCombatPlugin extends BaseEveryFrameCombatPlugin {

    private CombatEngineAPI engine;
    /**
     * Stable binding from each server entity id to the local puppet ship it drives
     * (Spike 4). Assigned once on first sight (ascending server entity id
     * &rarr; next free ally ship) and kept, so a puppet never swaps which server entity
     * it tracks across frames. Replaces the single-puppet field of Spikes 2/2a.
     */
    private final Map<Long, ShipAPI> puppetByServerId = new HashMap<>();
    /**
     * Client-side lifecycle consumer: derives a despawn whenever a server
     * entity that was in the roster leaves it, so this plugin can remove the local
     * puppet that entity was driving instead of leaving it frozen on screen. Per-combat
     * instance state, like {@link #puppetByServerId}.
     */
    private final RosterLifecycleTracker lifecycle = new RosterLifecycleTracker();
    private ServerSnapshotClient client;
    /**
     * True when this plugin opened and OWNS {@link #client} as a per-combat connection
     * and must therefore close it at combat end. False in the shared-menu-client
     * mode, where {@link CcClientModPlugin} owns the client's whole lifecycle.
     */
    private boolean ownsClient;
    /**
     * The per-combat client the plugin family currently owns, tracked <em>statically</em> so a
     * combat exited WITHOUT {@link CombatEngineAPI#isCombatOver()} (e.g. the human retreats to the
     * menu) still has its client closed: a fresh plugin instance is created per mission
     * ({@code new ServerFedPuppetCombatPlugin()}), so the next combat's {@link #init} closes this
     * leftover before opening its own. This is the real safety net — {@code CombatEnginePlugin} has
     * no {@code cleanup()} hook, only {@code init}. {@code null} in the shared-menu-client mode.
     */
    private static volatile ServerSnapshotClient perCombatClient;
    /**
     * Which server entity (index in the ascending-id snapshot) is the primary entity —
     * the maneuver-intent target ({@link #maneuverShipId()}). Rendering now drives every
     * server entity onto its own puppet (Spike 4); this index only picks which one the
     * human's movement keys steer.
     */
    private final int serverEntityIndex;
    /** Explicit owned-ship id for maneuver intents, or {@code -1} = use the rendered entity's id. */
    private final long configuredManeuverShipId;
    /**
     * Explicit render delay behind the newest snapshot, in nanoseconds, or {@code -1} to
     * auto-derive it from the measured snapshot interval. Auto-derivation
     * keeps the client tracking the server's configured tickrate with no client change.
     */
    private final long configuredInterpDelayNanos;
    /**
     * Player-piloted demo mode, from
     * {@code cc.puppet.playerPiloted} / {@code CC_PUPPET_PLAYER_PILOTED}; default {@code false}
     * keeps the proven side-agnostic ally-puppet binding ({@code cc_puppet_proof}/{@code
     * cc_despawn_proof}) exactly as before. When on, two things change for the two-side demo:
     * <ul>
     *   <li>the human's OWN ship (the flagship) becomes a SERVER-AUTHORITATIVE puppet bound to the
     *       maneuver-target server entity — its pose comes from the server's answer to the human's
     *       per-frame intents, local physics suppressed (it is not locally flown); and</li>
     *   <li>binding is SIDE-AWARE — each remaining server entity binds to a local ship on the
     *       matching {@code fleetSide} (friend &rarr; player-side ship, foe &rarr; enemy-side
     *       ship), so the engine's own friend/foe colouring renders the server's two sides.</li>
     * </ul>
     */
    private final boolean playerPiloted;
    /**
     * (83) CL-4: whether the roster-diff despawn INFERENCE (the older, relevance-UNSAFE
     * mechanism in {@link #advance}) is armed — knob {@code cc.client.rosterDiffDespawn},
     * default OFF: production truth is the explicit EDSP lane, and a scoped snapshot's roster
     * diff cannot tell a real despawn from "left my interest set". The retired
     * proof missions opt in at their registration sites via {@link #withRosterDiffDespawn()}
     * (their demo servers are AWSS-only, no EDSP lane).
     */
    private final boolean rosterDiffDespawn;
    /** The LWJGL key code currently held, or {@code -1}; its mapped movement, or {@code null}. */
    private int heldKey = -1;
    private ClientMovement heldMovement;
    /**
     * Damage applied to a puppet to drive a server-authoritative {@code DESTROYED} despawn as a
     * real in-game death. Far above any mission ship's armor+hull so a single HE hit
     * bypassing shields is always lethal, triggering the engine's explosion + debris/hulk corpse.
     */
    private static final float SERVER_KILL_DAMAGE = 1_000_000f;

    public ServerFedPuppetCombatPlugin() {
        this(resolveServerEntityIndex());
    }

    public ServerFedPuppetCombatPlugin(int serverEntityIndex) {
        // Compiled false (E3 hardwire): the cc.client.rosterDiffDespawn
        // knob-resolved shape is in git; the proof missions still arm via withRosterDiffDespawn().
        this(serverEntityIndex, false);
    }

    private ServerFedPuppetCombatPlugin(int serverEntityIndex, boolean rosterDiffDespawn) {
        this.serverEntityIndex = Math.max(0, serverEntityIndex);
        this.configuredManeuverShipId = resolveManeuverShipId();
        this.configuredInterpDelayNanos = resolveConfiguredInterpDelayNanos();
        this.playerPiloted = resolvePlayerPiloted();
        this.rosterDiffDespawn = rosterDiffDespawn;
    }

    /**
     * The retired proof-mission registration shape ((83) CL-4): roster-diff despawn armed
     * regardless of the {@code cc.client.rosterDiffDespawn} knob — those demo servers are
     * AWSS-only (no EDSP lane), and {@code cc_despawn_proof}'s staged vanish depends on the
     * inference.
     */
    public static ServerFedPuppetCombatPlugin withRosterDiffDespawn() {
        return new ServerFedPuppetCombatPlugin(resolveServerEntityIndex(), true);
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        if (CcClientModPlugin.connectPerCombat()) {
            // Per-combat connection mode: open THIS combat's own client now so the
            // fresh-binding-per-match server forms this match at combat start and its scheduled
            // roster-shrink despawn lands ~despawnAtAdvance ticks into THIS combat (a staged
            // vanish), not during menu time. First close any client a prior combat left open by
            // being exited without isCombatOver() (the safety net — there is no cleanup() hook),
            // then start a fresh one this plugin instance owns.
            closePerCombatClient();
            ServerSnapshotClient fresh = CcClientModPlugin.newServerClient();
            fresh.start();
            perCombatClient = fresh;
            this.client = fresh;
            this.ownsClient = true;
        } else {
            this.client = CcClientModPlugin.serverClient();
            this.ownsClient = false;
        }
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused() || client == null) {
            return;
        }
        if (ownsClient && engine.isCombatOver()) {
            // Per-combat connection mode: the match is over — close this combat's own
            // client promptly so the fresh-binding-per-match server is not left holding a match
            // during menu time between combats (which would advance the next match's despawn
            // budget before the owner re-enters combat). Closed once; subsequent frames
            // short-circuit on the null client guard above.
            closePerCombatClient();
            client = null;
            return;
        }
        ServerSnapshotFrame frame = client.latestFrame();
        if (frame == null) {
            return; // no authoritative frame yet — leave the puppets alone
        }

        // Lifecycle: before rendering, remove the local puppet of any server
        // entity that left the authoritative roster — the reaction the client previously
        // lacked, so a despawned ship no longer lingers frozen on screen.
        //
        // despawn-lane LIVE wire-in: prefer the server's explicit EDSP despawn
        // lane, drained from the client. It is derived from the FULL authoritative roster, so
        // it is relevance-safe — a real despawn survives per-peer relevance scoping that roster
        // diff over the (possibly scoped) snapshot cannot distinguish from "left my interest
        // set". Fall back to RosterLifecycleTracker roster diff ONLY for an
        // AWSS-only server that emits no explicit lane, so the existing 2-ship demo still
        // reacts. despawnPuppet is idempotent, so the unavoidable cross-thread race between an
        // AWSS roster-shrink and its trailing EDSP cannot double-remove a puppet.
        for (ClientEntityDespawn despawn : client.drainDespawns()) {
            despawnPuppet(despawn);
        }
        if (rosterDiffDespawn && !client.hasExplicitDespawnLane()) {
            // (83) CL-4: the inference only runs ARMED (knob default OFF; proof missions opt in)
            // — disarmed, an AWSS-only serve simply never despawns puppets, and no announce fires
            // (a "suppressed despawn" announce would false-fire on the documented AWSS/EDSP
            // cross-thread race above, the n1/n4 class CL-1 deleted).
            // observe runs even when the roster is now empty so a final all-despawn frame is
            // still acted on.
            for (ClientEntityDespawn despawn : lifecycle.observe(frame)) {
                // 1290 (81) N3: no EDSP lane -> roster-diff despawn inference (the older,
                // relevance-UNSAFE mechanism) — confess the first inferred despawn once.
                FallbackAnnounce.announceOnce("n3-despawn-roster-diff",
                        "no EDSP lane; despawn inferred from roster diff (relevance-unsafe)");
                despawnPuppet(despawn);
            }
        }
        if (frame.entityCount() == 0) {
            return; // roster empty — nothing to render this frame
        }

        // Spike 4: render EVERY server entity onto its own stable local
        // puppet, instead of the single entity-0 puppet of Spikes 2/2a. The render delay
        // is identical for all entities, so derive it once. Auto-derive it from the
        // measured snapshot interval unless explicitly overridden, so the puppets stay
        // smooth across a re-configured server tickrate with no client change (the
        // network tick is a configurable invariant).
        long interpDelayNanos = configuredInterpDelayNanos >= 0L
                ? configuredInterpDelayNanos
                : client.suggestedInterpDelayNanos();
        long renderTimeNanos = System.nanoTime() - interpDelayNanos;

        for (ServerEntityPose raw : frame.entities()) {
            ShipAPI puppet = puppetFor(raw.entityId(), raw.fleetSide());
            if (puppet == null) {
                continue; // no free ship left to bind this server entity to
            }
            renderPose(puppet, raw, renderTimeNanos);
        }
    }

    /**
     * Write one server entity's pose onto its bound local puppet for this render frame.
     *
     * <p>Render the puppet ~interpDelay BEHIND the newest snapshot,
     * interpolating between the two buffered authoritative frames bracketing the render
     * time (Source/Quake-style entity interpolation) instead of snapping to the latest
     * frame — the snap was the "vibrating into place" the operator saw. The
     * interpolated pose is matched by this entity's id, so it tracks the same ship across
     * frames. Before the buffer is warm (startup) the latest raw pose is held so the
     * puppet is placed immediately rather than left at its spawn.
     */
    private void renderPose(ShipAPI puppet, ServerEntityPose raw, long renderTimeNanos) {
        InterpolatedPose pose = client.interpolatedPose(renderTimeNanos, raw.entityId());

        float locX;
        float locY;
        float facing;
        if (pose != null) {
            locX = pose.locX();
            locY = pose.locY();
            facing = pose.facing();
        } else {
            locX = raw.locX();
            locY = raw.locY();
            facing = raw.facing();
        }

        // Write the authoritative position/facing and ZERO velocity + angular velocity:
        // with position fully authoritative from interpolation, leaving a velocity set
        // would make the physics step integrate location += velocity·dt on top of the
        // write each frame, the per-frame tug-of-war that blurred the puppet.
        Vector2f loc = puppet.getLocation();
        loc.x = locX;
        loc.y = locY;
        Vector2f vel = puppet.getVelocity();
        vel.x = 0f;
        vel.y = 0f;
        puppet.setFacing(facing);
        puppet.setAngularVelocity(0f);
    }

    /**
     * Capture local movement input and forward it to the server as a maneuver intent
     * (Spike 3). Runs before the engine's own controls each frame; we observe (do not
     * consume) the events so the human still drives the game UI. The most-recent held
     * movement key wins; releasing it returns to the hold (no-command) intent. The
     * {@link ServerSnapshotClient} sends the latest intent on its next connection.
     */
    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        if (client == null || events == null) {
            return;
        }
        for (InputEventAPI event : events) {
            if (event == null || !event.isKeyboardEvent()) {
                continue;
            }
            int key = event.getEventValue();
            if (event.isKeyDownEvent()) {
                ClientMovement movement = mapKey(key);
                if (movement != null) {
                    heldKey = key;
                    heldMovement = movement;
                }
            } else if (event.isKeyUpEvent() && key == heldKey) {
                heldKey = -1;
                heldMovement = null;
            }
        }
        long shipId = maneuverShipId();
        client.setManeuverIntent(heldMovement != null
                ? ClientManeuverIntent.maneuver(shipId, heldMovement)
                : ClientManeuverIntent.hold(shipId));
    }

    /** Map an LWJGL key code to a movement intent, or {@code null} if it is not a control key. */
    private static ClientMovement mapKey(int key) {
        switch (key) {
            case Keyboard.KEY_W: return ClientMovement.ACCELERATE;
            case Keyboard.KEY_S: return ClientMovement.DECELERATE;
            case Keyboard.KEY_A: return ClientMovement.TURN_LEFT;
            case Keyboard.KEY_D: return ClientMovement.TURN_RIGHT;
            case Keyboard.KEY_Q: return ClientMovement.STRAFE_LEFT;
            case Keyboard.KEY_E: return ClientMovement.STRAFE_RIGHT;
            case Keyboard.KEY_X: return ClientMovement.STOP;
            default: return null;
        }
    }

    /**
     * The owned-ship id the maneuver intent targets: the explicit override if
     * configured, else the id of the server entity this plugin renders (so the intent
     * targets the same ship the human watches), else {@code 0}.
     */
    private long maneuverShipId() {
        if (configuredManeuverShipId >= 0L) {
            return configuredManeuverShipId;
        }
        if (client != null) {
            ServerSnapshotFrame frame = client.latestFrame();
            if (frame != null && frame.entityCount() > 0) {
                ServerEntityPose pose = selectPose(frame);
                if (pose != null && pose.entityId() >= 0L) {
                    return pose.entityId();
                }
            }
        }
        return 0L;
    }

    /**
     * The primary server entity — the maneuver-intent target: the configured index,
     * clamped to range. Rendering drives every entity (see {@link #advance}); this picks
     * only which one the human's movement keys steer.
     */
    private ServerEntityPose selectPose(ServerSnapshotFrame frame) {
        int index = Math.min(serverEntityIndex, frame.entityCount() - 1);
        return frame.entityAt(index);
    }

    /**
     * The local puppet ship bound to server entity {@code serverId}, assigning a stable
     * binding on first sight (ascending server entity id &rarr; next free ally ship) and
     * keeping it, so a puppet never swaps which server entity it tracks. Returns
     * {@code null} when no unbound living ally ship is available (more server entities
     * than puppet ships). A bound ship that has died releases its binding so a survivor
     * can take over the slot.
     */
    /**
     * React to a server-authoritative despawn of the local puppet a vanished server entity
     * was driving, and release its binding.
     *
     * <p>The "server-owned + real death" direction:
     * the kill-vs-departure FX split the earlier comment deferred is now made. A
     * {@code DESTROYED} despawn drives a <em>real in-game death</em> — explosion + a drifting
     * hulk corpse — instead of the old instant {@link CombatEngineAPI#removeEntity} vanish, which
     * read as unrealistic: ships leave corpses. The puppet is
     * immune to LOCAL combat ({@link CollisionClass#NONE}, see {@link #makeServerOwned}), but
     * {@code applyDamage} is direct, so the SERVER's death still lands: restore normal collision
     * so the wreck behaves like any corpse, force a hulk so a corpse is guaranteed, then apply
     * massive HE bypassing shields at the hull and let the engine run its own death sequence
     * (FX + debris/hulk). A {@code DEPARTED} entity left with hull intact (retreat / non-lethal
     * removal) — no death FX, just remove it. A no-op when no puppet was bound to this entity.
     */
    private void despawnPuppet(ClientEntityDespawn despawn) {
        ShipAPI puppet = puppetByServerId.remove(despawn.entityId());
        if (puppet == null) {
            return; // nothing was bound to this server entity
        }
        if (engine == null || !puppet.isAlive()) {
            return;
        }
        if (despawn.reason() == ClientDespawnReason.DESTROYED) {
            puppet.setCollisionClass(CollisionClass.SHIP);
            puppet.setHulkChanceOverride(1f);
            engine.applyDamage(puppet, puppet.getLocation(), SERVER_KILL_DAMAGE,
                    DamageType.HIGH_EXPLOSIVE, 0f, true, false, null);
        } else {
            engine.removeEntity(puppet);
        }
    }

    /**
     * Close the per-combat client this plugin family owns, if any, and clear the static slot.
     * Idempotent and synchronized: combats are sequential, so the static slot always
     * refers to the current/last per-combat client. A no-op in the shared-menu-client mode (the
     * slot is {@code null}).
     */
    private static synchronized void closePerCombatClient() {
        ServerSnapshotClient prior = perCombatClient;
        if (prior != null) {
            prior.close();
            perCombatClient = null;
        }
    }

    private ShipAPI puppetFor(long serverId) {
        ShipAPI bound = puppetByServerId.get(serverId);
        if (bound != null) {
            if (bound.isAlive()) {
                return bound;
            }
            puppetByServerId.remove(serverId); // dead puppet — free the binding to rebind
        }
        ShipAPI free = findFreePuppet();
        if (free != null) {
            puppetByServerId.put(serverId, free);
            makeServerOwned(free);
        }
        return free;
    }

    /**
     * Side-aware binding for the player-piloted demo. In the proven side-agnostic
     * mode ({@code playerPiloted} off) this delegates to {@link #puppetFor(long)} so the existing
     * paths are unchanged. When on:
     * <ul>
     *   <li>the maneuver-target server entity ({@link #maneuverShipId()}) binds to the human's
     *       flagship, made SERVER-OWNED — the human's own ship becomes a server-authoritative
     *       puppet (pose from the server's answer to their intents, local physics suppressed); and</li>
     *   <li>every other server entity binds to a free LOCAL ship on the matching {@code fleetSide}
     *       (friend &rarr; player-owned ship, foe &rarr; enemy-owned ship), so the engine renders
     *       the server's two sides with its own friend/foe colours.</li>
     * </ul>
     */
    private ShipAPI puppetFor(long serverId, int fleetSide) {
        if (!playerPiloted) {
            return puppetFor(serverId);
        }
        ShipAPI bound = puppetByServerId.get(serverId);
        if (bound != null) {
            if (bound.isAlive()) {
                return bound;
            }
            puppetByServerId.remove(serverId);
        }
        ShipAPI chosen = null;
        if (serverId == maneuverShipId()) {
            ShipAPI player = engine.getPlayerShip();
            if (player != null && player.isAlive() && !puppetByServerId.containsValue(player)) {
                chosen = player; // the human's OWN ship, server-driven
            }
        }
        if (chosen == null) {
            chosen = findFreePuppetForOwner(localOwnerForFleetSide(fleetSide));
        }
        if (chosen != null) {
            puppetByServerId.put(serverId, chosen);
            makeServerOwned(chosen);
        }
        return chosen;
    }

    /**
     * Map a server {@code fleetSide} ordinal to the local engine {@code getOwner()} int. The two
     * fighting sides ({@code 0}/{@code 1}) match the engine's owner ints directly (FleetSide.PLAYER
     * is owner 0, FleetSide.ENEMY is owner 1); an unknown/neutral side falls back to the player's
     * own side so a pre-v5 frame still binds to player-owned ships.
     */
    private int localOwnerForFleetSide(int fleetSide) {
        if (fleetSide == 0 || fleetSide == 1) {
            return fleetSide;
        }
        ShipAPI player = engine.getPlayerShip();
        return player != null ? player.getOwner() : 0;
    }

    /**
     * The first living engine ship with {@code getOwner() == owner} that is neither the human
     * flagship nor already bound to another server entity, or {@code null} when none is free. The
     * flagship is excluded here so it is only ever bound via the explicit maneuver-target branch in
     * {@link #puppetFor(long, int)}.
     */
    private ShipAPI findFreePuppetForOwner(int owner) {
        ShipAPI player = engine.getPlayerShip();
        for (ShipAPI ship : engine.getShips()) {
            if (ship == player || !ship.isAlive() || ship.getOwner() != owner) {
                continue;
            }
            if (puppetByServerId.containsValue(ship)) {
                continue; // already driven by another server entity
            }
            return ship;
        }
        return null;
    }

    /**
     * Make a newly-bound puppet SERVER-OWNED so the SERVER — not the local engine — decides its
     * life and death. Two changes,
     * applied once at bind time:
     * <ul>
     *   <li>{@link CollisionClass#NONE}: local projectiles and physical collisions pass through
     *       the puppet, so the enemy can no longer damage or kill a server-driven ship. This
     *       closes the authority hole the watch exposed (the local engine had combat-killed the
     *       puppets with debris, pre-empting the server's despawn). A {@code DESTROYED} despawn
     *       restores normal collision before its scripted death (see {@link #despawnPuppet}).</li>
     *   <li>A no-op {@link InertShipAI}: the puppet does not fire its own weapons or fight its
     *       per-frame pose override. "Frozen but still shooting" was the watch artifact of the
     *       pose-override (server owns position) racing live local AI (local owns guns); with the
     *       AI suppressed the puppet is a pure server-driven display object. This is the no-op
     *       {@code ShipAIPlugin} suppression the Spike-1 comment anticipated.</li>
     * </ul>
     * Pose is still written every frame in {@link #renderPose}.
     */
    private void makeServerOwned(ShipAPI puppet) {
        puppet.setCollisionClass(CollisionClass.NONE);
        puppet.setShipAI(new InertShipAI());
    }

    /**
     * The first living player-side ship that is neither the human flagship nor already
     * bound to another server entity. In the Spike mission these are the allied "puppet"
     * ships; engine iteration order makes the first-bound entity take the first such ship.
     */
    private ShipAPI findFreePuppet() {
        ShipAPI player = engine.getPlayerShip();
        int playerOwner = player != null ? player.getOwner() : 0;
        for (ShipAPI ship : engine.getShips()) {
            if (ship == player || !ship.isAlive() || ship.getOwner() != playerOwner) {
                continue;
            }
            if (puppetByServerId.containsValue(ship)) {
                continue; // already driven by another server entity
            }
            return ship;
        }
        return null;
    }

    private static int resolveServerEntityIndex() {
        String raw = System.getProperty("cc.puppet.serverEntityIndex");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("CC_PUPPET_SERVER_ENTITY_INDEX");
        }
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Whether to run the player-piloted demo mode (own ship server-driven + side-aware friend/foe
     * binding), from {@code cc.puppet.playerPiloted} (then {@code CC_PUPPET_PLAYER_PILOTED});
     * default {@code false}. Any of {@code 1/true/yes/on} (case-insensitive) enables it.
     */
    private static boolean resolvePlayerPiloted() {
        String raw = System.getProperty("cc.puppet.playerPiloted");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("CC_PUPPET_PLAYER_PILOTED");
        }
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String v = raw.trim().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    /** Explicit maneuver-target ship id, or {@code -1} to track the rendered entity's id. */
    private static long resolveManeuverShipId() {
        String raw = System.getProperty("cc.maneuver.shipId");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("CC_MANEUVER_SHIP_ID");
        }
        if (raw == null || raw.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * The explicit interpolation render delay in nanoseconds from
     * {@code cc.puppet.interpDelayMillis} (then {@code CC_PUPPET_INTERP_DELAY_MILLIS}), or
     * {@code -1} to auto-derive it from the measured snapshot interval (the default). An
     * explicit value lets the owner pin the smoothness/latency trade-off at launch without
     * a rebuild; leaving it unset keeps the delay tracking the server's configured tickrate
     * automatically. A non-positive or unparseable value falls back to auto.
     */
    private static long resolveConfiguredInterpDelayNanos() {
        String raw = System.getProperty("cc.puppet.interpDelayMillis");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("CC_PUPPET_INTERP_DELAY_MILLIS");
        }
        if (raw == null || raw.isBlank()) {
            return -1L; // auto: derive from the measured snapshot interval
        }
        try {
            long millis = Long.parseLong(raw.trim());
            return millis > 0L ? millis * 1_000_000L : -1L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * A no-op {@link ShipAIPlugin} installed on every server-owned puppet so the
     * local engine never fires its weapons or fights the per-frame pose override. {@code advance}
     * issues no commands; the flag/config accessors return stable empty defaults the engine can
     * read without NPE. This is the "no-op {@code ShipAIPlugin} suppression" the Spike-1 comment
     * anticipated ("deliberately not added yet — Spike 1 saw no AI fight against a per-frame
     * override"); the real-capture watch is the case where it earns its place.
     */
    private static final class InertShipAI implements ShipAIPlugin {
        private final ShipwideAIFlags flags = new ShipwideAIFlags();
        private final ShipAIConfig config = new ShipAIConfig();

        @Override public void advance(float amount) { /* inert: issue no fire/maneuver commands */ }
        @Override public void setDoNotFireDelay(float amount) { /* no-op */ }
        @Override public void forceCircumstanceEvaluation() { /* no-op */ }
        @Override public boolean needsRefit() { return false; }
        @Override public ShipwideAIFlags getAIFlags() { return flags; }
        @Override public void cancelCurrentManeuver() { /* no-op */ }
        @Override public ShipAIConfig getConfig() { return config; }
    }
}
