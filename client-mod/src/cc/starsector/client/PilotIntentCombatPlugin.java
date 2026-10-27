package cc.starsector.client;

import cc.starsector.server.client.PilotIntentClientSession;
import cc.starsector.server.runtime.PilotManeuverIntent;
import cc.starsector.server.runtime.PilotWorldViewFrame;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.input.InputEventAPI;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.util.vector.Vector2f;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The FAITHFUL pilot combat plugin — the in-game client half of the
 * two-window live test. This is the game-side consumer of the {@link PilotIntentClientSession}
 * seam proven byte-identical game-free.
 *
 * <p>Distinct from {@link ServerFedPuppetCombatPlugin}, which speaks the OLD snapshot /
 * {@code LiveManeuverIntent} protocol that carries <strong>no cursor aim</strong> and therefore can
 * not drive the faithful strafe-toward-cursor lane. This plugin speaks the <strong>PilotIntent</strong>
 * protocol: each server tick a {@link PilotWorldViewFrame} comes DOWN (the two ship poses the window
 * renders) and a {@code PilotIntentFrame} — the human's live {@code (mouse-aim, WASD, fire)} — goes UP,
 * through the UNCHANGED {@code PilotWorldViewWireCodec}/{@code PilotIntentWireCodec} (no re-minted
 * codec; avoid-list honoured). The server maps the intent through the engine's own {@code BasicEngineAI}
 * movement + {@code PilotFireMapper} fire control, so the per-tick motion/fire is the ENGINE's, driven
 * by a real human's aim + keys + trigger over the network.
 *
 * <p><b>Threading.</b> The session's per-tick exchange ({@code receiveWorldView()} then
 * {@code sendIntent()}) is a strict, blocking ping-pong paced by the SERVER's tick. Blocking it on the
 * combat (render) thread would stall the game, so the exchange runs on a dedicated daemon
 * {@link #pilotThread}: it reads the view DOWN, publishes it to {@link #latestView} for the render
 * thread, and sends UP the latest input the render thread captured. The combat thread therefore never
 * blocks on the socket — it renders {@link #latestView} and updates {@link #aimDeg}/{@link #heldControls}/
 * {@link #fireHeld} each frame. The cross-thread fields are {@code volatile}; a render reading a view
 * one tick stale, or the pilot loop sending input one frame stale, is benign for a watch.
 *
 * <p><b>Authority.</b> Both rendered ships (the human's own ship at the view's source pose, the foe at
 * the target pose) are made SERVER-OWNED (a no-op {@link InertShipAI} so the local engine neither fights
 * the per-frame pose write nor flies its own AI) and the server, not local combat, decides death. The own
 * ship stays {@link CollisionClass#NONE}; the foe is {@link CollisionClass#SHIP} so
 * the local Hellbore visibly IMPACTS it — but a zero-damage {@code FoeDamageSuppressor} keeps that impact
 * cosmetic (FX + despawn only), so the server remains the sole damage/death authority. The human's keys
 * are NOT flown locally — they are captured and sent to the server, whose authoritative answer is the pose
 * written back. The visible round-trip lag IS the authority proof, exactly as in
 * {@link ServerFedPuppetCombatPlugin}'s player-piloted mode.
 *
 * <p><b>Authoritative facing + fire.</b> {@link PilotWorldViewFrame} schema v2 now
 * carries each ship's server-authoritative {@code getFacing()} and weapon fire state, so this plugin
 * renders the REAL feel rather than the position-only approximations it used to: the own ship is drawn
 * at the engine's turn-rate-limited heading ({@link PilotWorldViewFrame#srcFacingDeg()}) instead of
 * snapping instantly to the cursor, the foe at its true heading ({@link PilotWorldViewFrame#tgtFacingDeg()})
 * instead of the best-effort "bearing back at the player", and when the server reports a ship firing
 * ({@link PilotWorldViewFrame#srcFiring()} / {@link PilotWorldViewFrame#tgtFiring()}) the plugin issues
 * the engine's own {@code ShipCommand.FIRE} so the local engine presents its REAL Hellbore discharge.
 * Authority is unchanged: the foe's local hits are zeroed by the {@code FoeDamageSuppressor} (a1), so a
 * presented shot is cosmetic — it cannot kill the foe locally; the server stays the source of truth for
 * damage/death.
 */
public class PilotIntentCombatPlugin extends BaseEveryFrameCombatPlugin {

    private CombatEngineAPI engine;

    /** The most recent world view the pilot thread received, for the render thread to draw. */
    private volatile PilotWorldViewFrame latestView;
    /** This frame's live human input, captured on the render thread, sent UP on the pilot thread. */
    private volatile float aimDeg;
    private volatile int heldControls;
    private volatile boolean fireHeld;

    private volatile boolean running;
    private PilotIntentClientSession session;
    private Thread pilotThread;

    /** The local ship bound to the view's SOURCE pose (the human's own, server-driven, ship). */
    private ShipAPI ownShip;
    /** The local ship bound to the view's TARGET pose (the foe). */
    private ShipAPI foeShip;
    /** Guard so the server-authoritative foe death runs exactly once on the alive&rarr;dead edge. */
    private boolean foeKilled;

    /**
     * The foe is made COLLIDABLE ({@link CollisionClass#SHIP}) so the local Hellbore
     * visibly IMPACTS it (impact FX + projectile despawn — the "passes through the foe" gap), but a
     * {@link FoeDamageSuppressor} zeroes that local damage so the server stays the sole damage/death
     * authority. {@code volatile} because {@link #killFoe} flips it off (render thread) to let the
     * authoritative kill blow through, while the listener reads it. Bytecode RE confirmed
     * the engine's on-hit glow + despawn are damage-INDEPENDENT, so zeroed damage keeps the FX.
     */
    private volatile boolean suppressFoeDamage = true;

    // GAP 2 discharge instrument — READ-ONLY measurement of the candidate-2 player-ship
    // double-drive (presentFire issues a per-frame FIRE on the player ship while the game's own weapon
    // control drives the SAME ship). Counts DISTINCT local own-ship projectiles spawned across the
    // over-fire window vs srcFiring frames; the server baseline is 5 real discharges / 149 srcFiring frames.
    // NOT a throttle — the fix (rising-edge gating) is DECIDED from the measured number.
    private final Set<Object> seenOwnProj = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    private int localOwnDischarges;
    private int srcFiringFrames;
    private int advFrames;
    private final StringBuilder dischargeFrames = new StringBuilder();

    /** Massive HE used to drive the engine's OWN death sequence on a server-authoritative kill. */
    private static final float SERVER_KILL_DAMAGE = 1_000_000f;

    /**
     * The plugin instance of the current/last combat, tracked statically so a combat exited WITHOUT
     * {@link CombatEngineAPI#isCombatOver()} (the human retreats to the menu) still has its pilot thread
     * stopped and the (app-load-opened) session closed: a fresh plugin instance is created per mission,
     * so the next combat's {@link #init} tears this leftover down before consuming the session again.
     * Mirrors {@link ServerFedPuppetCombatPlugin}'s {@code perCombatClient} safety net (there is no
     * {@code cleanup()} hook on a combat plugin, only {@code init}).
     */
    private static volatile PilotIntentCombatPlugin active;

    @Override
    public void init(CombatEngineAPI engine) {
        // Tear down any session a prior combat left open by being exited without isCombatOver()
        // (the safety net — there is no cleanup() hook), then start this combat's own.
        teardownActive();
        this.engine = engine;
        // The socket is opened at CcClientModPlugin.onApplicationLoad() — the menu,
        // OUTSIDE combat's script SecurityManager — never here. Opening it during combat made the script
        // classloader B deny socket.getOutputStream()'s class load ("File access and reflection are not
        // allowed to scripts"). We consume the already-open session and only START the pilot thread here;
        // sending the first intent is what signals the server that combat actually began (so its
        // real-time budget does not burn during menu navigation — see run-headless-bootstrap-scout.ps1).
        // The session now opens on the cc-pilot-connect daemon thread (CcClientModPlugin.
        // connectPilotSession), so by the time the human reaches combat (seconds after app-load) it is
        // normally already open. Wait a bounded moment for it in the rare case combat is entered before
        // the background connect finishes, rather than dropping straight to LOCAL VANILLA. The loop body
        // only runs in that race (s == null); the normal path takes the session immediately, no delay.
        PilotIntentClientSession s = CcClientModPlugin.pilotSession();
        for (int i = 0; s == null && i < 10; i++) { // up to ~1s (10 * 100ms)
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            s = CcClientModPlugin.pilotSession();
        }
        if (s == null) {
            // No app-load pilot session (endpoint unconfigured, or the app-load connect failed/timed out).
            // Leave the ships to the local engine so the mission still loads (fail-closed).
            // this is exactly the "window stays LOCAL VANILLA" outcome — surface it.
            PilotWatchLog.info("init: NO app-load pilot session (CcClientModPlugin.pilotSession()==null after wait) "
                    + "-> ships left locally driven (window stays LOCAL VANILLA)");
            return;
        }
        this.session = s;
        this.running = true;
        this.pilotThread = new Thread(this::runPilotLoop, "cc-pilot-intent-" + s.shipId());
        this.pilotThread.setDaemon(true);
        this.pilotThread.start();
        active = this;
        // Confirm combat consumed the app-load session and the pilot thread started; the FIRST
        // intent the thread sends is the server's "combat began" signal (run-headless-bootstrap-scout.ps1).
        PilotWatchLog.info("init: consumed app-load pilot session shipId=" + s.shipId()
                + " group=" + s.selectedWeaponGroup() + " -> pilot thread started");
    }

    /**
     * The pilot thread: the strict per-tick ping-pong with the server, off the render thread. Reads the
     * world view DOWN, publishes it for rendering, then sends the latest captured human input UP. Exits
     * cleanly at end of stream / a terminal view, or fail-closed on any I/O error (the game keeps
     * running; the ships simply stop tracking the server).
     */
    private void runPilotLoop() {
        int recv = 0;
        int sent = 0;
        PilotWatchLog.info("runPilotLoop: started shipId=" + (session != null ? session.shipId() : -1L));
        try {
            while (running) {
                PilotWorldViewFrame view = session.receiveWorldView();
                if (view == null || view.terminal()) {
                    PilotWatchLog.info("runPilotLoop: clean end of stream after recv=" + recv + " sent=" + sent
                            + (view != null && view.terminal() ? " (terminal view)" : " (null view)"));
                    break; // clean end of stream / server signalled done
                }
                recv++;
                if (recv == 1) {
                    PilotWatchLog.info("runPilotLoop: FIRST worldview received frameSeq=" + view.frameSeq());
                }
                latestView = view;
                session.sendIntent(aimDeg, heldControls, fireHeld);
                sent++;
                if (sent == 1) {
                    PilotWatchLog.info("runPilotLoop: FIRST intent sent aimDeg=" + aimDeg
                            + " held=" + heldControls + " fire=" + fireHeld);
                }
            }
        } catch (Throwable t) {
            // un-swallowed. Catches Throwable, not just IOException — the
            // WSAECONNABORTED the server saw on frame 1 is a SocketException (an IOException), but a codec
            // / SecurityException / any other failure would otherwise die silently on this daemon thread
            // and leave only the server-side "0 frames exchanged" symptom. Log the EXACT window-side
            // throwable so the next WATCH pins which hypothesis holds (handoff 2026-06-07).
            if (running) {
                PilotWatchLog.error("runPilotLoop: exchange FAILED after recv=" + recv + " sent=" + sent
                        + " -> pilot loop ENDING; ships stop tracking the server (window stays LOCAL VANILLA)", t);
            } else {
                // teardownActive() set running=false then closed the session, unblocking the read with an
                // expected exception — a normal combat exit, not the bug under investigation.
                PilotWatchLog.info("runPilotLoop: closed by teardown after recv=" + recv + " sent=" + sent
                        + " (" + t.getClass().getSimpleName() + ")");
            }
        } finally {
            running = false;
            PilotWatchLog.info("runPilotLoop: ended recv=" + recv + " sent=" + sent);
        }
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused() || session == null) {
            return;
        }
        if (engine.isCombatOver()) {
            teardownActive();
            return;
        }
        PilotWorldViewFrame view = latestView;
        if (view == null) {
            return; // no authoritative frame yet — leave the ships alone
        }
        if (ownShip == null) {
            bindShips();
        }
        // GAP 2 discharge meter — read-only; see the field comment. Count distinct local
        // own-ship projectiles spawned vs srcFiring frames over the over-fire window, with the frame index
        // each discharge spawned at. This MEASURES the candidate-2 over-fire; it does not change firing.
        advFrames++;
        if (view.srcFiring()) {
            srcFiringFrames++;
        }
        if (ownShip != null) {
            for (DamagingProjectileAPI p : engine.getProjectiles()) {
                if (p.getSource() == ownShip && seenOwnProj.add(p)) {
                    localOwnDischarges++;
                    if (dischargeFrames.length() < 1500) {
                        dischargeFrames.append(advFrames).append(',');
                    }
                }
            }
            if (advFrames % 300 == 0) {
                logFireMeter("advance@" + advFrames);
            }
        }
        // Render the human's own ship at the SOURCE pose (server-driven; authority lag = the proof) and the
        // foe at the TARGET pose, BOTH at the engine's authoritative facing the server streamed down
        // — the own ship at its turn-rate-limited heading (not an instant snap to the cursor),
        // the foe at its true heading (not the old bearing-back-at-the-player approximation).
        renderPose(ownShip, view.srcX(), view.srcY(), view.srcFacingDeg());
        // MIRROR each ship's server-authoritative hull (the engine's own getHullLevel streamed
        // DOWN) onto the local server-owned ship via the engine's own setHitpoints, so the player SEES real
        // damage register — the blocker was pose-only replication (the foe was a non-collidable,
        // immortal pose-ghost: "I couldn't damage it"). This is snapshot replication, NOT local
        // collision (which would make the client the damage authority — a non-goal); the server stays the
        // source of truth and just hands the window the hull to display.
        mirrorHull(ownShip, view.srcHullFraction());
        // When the server reports a ship firing, present its REAL Hellbore discharge via the engine's own
        // FIRE command (cosmetic — the ships are server-owned, so the shot cannot kill locally; the server
        // owns damage/death). Aim at the opponent's pose; the Hellbore is a forward hardpoint so the already
        // authoritative facing decides the shot direction.
        if (view.srcFiring()) {
            presentFire(ownShip, foeShip, view.tgtX(), view.tgtY());
        }
        if (view.targetAlive()) {
            renderPose(foeShip, view.tgtX(), view.tgtY(), view.tgtFacingDeg());
            mirrorHull(foeShip, view.tgtHullFraction());
            if (view.tgtFiring()) {
                presentFire(foeShip, ownShip, view.srcX(), view.srcY());
            }
        } else {
            // The server reported the foe DEAD (the authoritative kill). Run the engine's OWN death sequence
            // once: real explosion + a drifting hulk corpse, so the kill the server
            // computed lands visibly in the window instead of the foe freezing at end-of-stream.
            killFoe(view.tgtX(), view.tgtY(), view.tgtFacingDeg());
        }
    }

    /**
     * Mirror a server-authoritative hull fraction onto a local server-owned ship via the
     * engine's OWN {@code setHitpoints}, so the window displays the REAL damage the server computed.
     * Presentation only (the ships are {@link CollisionClass#NONE}; the server owns damage/death), so a
     * read/clamp hiccup must never break the per-frame pose write — fail-soft. {@code fraction} is the
     * engine's {@code getHullLevel()} in {@code [0,1]}; below ~0 is left to {@link #killFoe}.
     */
    private void mirrorHull(ShipAPI ship, float fraction) {
        if (ship == null || !ship.isAlive() || !Float.isFinite(fraction)) {
            return;
        }
        try {
            float max = ship.getMaxHitpoints();
            if (max <= 0f) {
                return;
            }
            float clamped = fraction < 0f ? 0f : (fraction > 1f ? 1f : fraction);
            // Hold strictly above 0 so the engine's own liveness check never trips here; the explicit
            // server death frame (targetAlive=false) is what drives the real kill via killFoe().
            ship.setHitpoints(Math.max(1f, clamped * max));
        } catch (Throwable ignore) {
            // presentation only — a failed hull mirror must never disturb the pose write or the watch.
        }
    }

    /**
     * Run the engine's OWN death sequence on the foe when the server reports it dead (reusing
     * the proven server-owned-real-death path): place it at its last authoritative
     * pose, restore normal collision so the wreck behaves like any corpse, force a hulk, then apply
     * massive shield-bypassing HE so the engine runs its real explosion + debris/hulk. Idempotent via
     * {@link #foeKilled}; fail-soft so a death-FX hiccup never breaks the watch.
     */
    private void killFoe(float locX, float locY, float facingDeg) {
        if (foeKilled || foeShip == null || engine == null || !foeShip.isAlive()) {
            return;
        }
        foeKilled = true;
        // GAP 2: the kill = end of the over-fire window — emit the discharge meter once here.
        logFireMeter("killFoe");
        try {
            renderPose(foeShip, locX, locY, facingDeg);
            foeShip.setCollisionClass(CollisionClass.SHIP);
            // A served dweller foe leaves no hulk in vanilla (DwellerHullLaw).
            foeShip.setHulkChanceOverride(DwellerHullLaw.isDweller(foeShip) ? 0f : 1f);
            // BYPASS: lift the FoeDamageSuppressor so this authoritative 1,000,000 HE
            // kill blow is NOT zeroed (the listener returns null once the flag is off). The suppressor only
            // eats the cosmetic local Hellbore hits, never the server's decided death.
            suppressFoeDamage = false;
            engine.applyDamage(foeShip, foeShip.getLocation(), SERVER_KILL_DAMAGE,
                    DamageType.HIGH_EXPLOSIVE, 0f, true, false, null);
        } catch (Throwable ignore) {
            // The authoritative death is already decided server-side; a local FX failure must not break the watch.
        }
    }

    /**
     * Present a ship's authoritative fire as the engine's OWN discharge: set its target + issue
     * {@code ShipCommand.FIRE} on the configured weapon group toward {@code (aimX, aimY)}. Presentation
     * only (the ship is {@link CollisionClass#NONE}, so the shot is non-authoritative — it cannot kill);
     * fail-soft so a fire-render hiccup never disturbs the per-frame pose write or the watch.
     */
    private void presentFire(ShipAPI ship, ShipAPI target, float aimX, float aimY) {
        if (ship == null || !ship.isAlive()) {
            return;
        }
        try {
            if (target != null && target.isAlive()) {
                ship.setShipTarget(target);
            }
            ship.giveCommand(ShipCommand.FIRE, new Vector2f(aimX, aimY), session.selectedWeaponGroup());
        } catch (Throwable ignore) {
            // presentation only — the authoritative fire STATE is already proven on the wire;
            // a failed local discharge must never break the watch.
        }
    }

    /**
     * Write one authoritative position + facing onto a server-owned ship for this render frame, with
     * zero velocity / angular velocity so the engine's physics step does not integrate motion on top of
     * the write each frame (the per-frame tug-of-war that blurs an externally-driven puppet).
     * A {@code null} ship (none free to bind) is a no-op.
     */
    private void renderPose(ShipAPI ship, float locX, float locY, float facingDeg) {
        if (ship == null || !ship.isAlive()) {
            return;
        }
        Vector2f loc = ship.getLocation();
        loc.x = locX;
        loc.y = locY;
        Vector2f vel = ship.getVelocity();
        vel.x = 0f;
        vel.y = 0f;
        ship.setFacing(facingDeg);
        ship.setAngularVelocity(0f);
    }

    /**
     * Capture this frame's live human input on the render thread (the pilot thread sends it UP). The
     * faithful raw pilot input Starsector itself takes: the cursor's world bearing from the own ship
     * ({@code aimDeg}), the WASD movement keys currently held as a {@link PilotManeuverIntent} bitset,
     * and the left mouse button as the fire trigger. Events are observed, not consumed, so the human
     * still drives the game UI.
     */
    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        if (engine == null) {
            return;
        }
        ShipAPI aimFrom = ownShip != null ? ownShip : engine.getPlayerShip();
        if (aimFrom != null) {
            ViewportAPI vp = engine.getViewport();
            if (vp != null) {
                float mouseWorldX = vp.convertScreenXToWorldX(Mouse.getX());
                float mouseWorldY = vp.convertScreenYToWorldY(Mouse.getY());
                Vector2f from = aimFrom.getLocation();
                aimDeg = bearingDeg(from.x, from.y, mouseWorldX, mouseWorldY);
            }
        }
        heldControls = readHeldControls();
        fireHeld = Mouse.isButtonDown(0);
    }

    /** The WASD movement keys held this tick as a {@link PilotManeuverIntent} bitset. */
    private static int readHeldControls() {
        int bits = 0;
        if (Keyboard.isKeyDown(Keyboard.KEY_W)) bits |= PilotManeuverIntent.THRUST_FORWARD;
        if (Keyboard.isKeyDown(Keyboard.KEY_S)) bits |= PilotManeuverIntent.THRUST_BACK;
        if (Keyboard.isKeyDown(Keyboard.KEY_A)) bits |= PilotManeuverIntent.STRAFE_LEFT;
        if (Keyboard.isKeyDown(Keyboard.KEY_D)) bits |= PilotManeuverIntent.STRAFE_RIGHT;
        return bits;
    }

    /** Bearing (degrees, [0,360)) from {@code (fromX,fromY)} to {@code (toX,toY)} — same convention as
     * {@link PilotWorldViewFrame#bearingToTargetDeg()}. */
    private static float bearingDeg(float fromX, float fromY, float toX, float toY) {
        return (float) ((Math.toDegrees(Math.atan2(toY - fromY, toX - fromX)) + 360.0d) % 360.0d);
    }

    /**
     * Bind the human's own ship (the flagship) and one foe ship to the view's source/target poses, once,
     * and make both SERVER-OWNED so the local engine does not fight the per-frame pose write or
     * combat-kill a server-driven ship.
     */
    private void bindShips() {
        ownShip = engine.getPlayerShip();
        if (ownShip != null) {
            makeServerOwned(ownShip);
        }
        // Lane-shape safe: this presenter lane has no MATCH_RESET/multi-round rebind, so ownShip
        // here is always the just-fetched live player ship.
        int ownerSide = ownShip != null ? ownShip.getOwner() : 0; // LINT-ALLOW corpse-side: lane-shape safe
        for (ShipAPI ship : engine.getShips()) {
            if (ship == ownShip || !ship.isAlive() || ship.getOwner() == ownerSide) {
                continue; // first living ship on the OTHER side is the foe
            }
            foeShip = ship;
            makeServerOwnedFoe(foeShip);
            break;
        }
    }

    /**
     * Make a ship SERVER-OWNED: {@link CollisionClass#NONE} so local projectiles/collisions pass through
     * it (the server, not the local engine, decides its death) and a no-op {@link InertShipAI} so it
     * neither fires its own weapons nor fights the per-frame pose override. Mirrors
     * {@link ServerFedPuppetCombatPlugin#makeServerOwned}.
     */
    private void makeServerOwned(ShipAPI ship) {
        ship.setCollisionClass(CollisionClass.NONE);
        ship.setShipAI(new InertShipAI());
    }

    /**
     * Make the FOE server-owned but COLLIDABLE. Unlike {@link #makeServerOwned} (the
     * own ship, kept {@link CollisionClass#NONE} so it passes through the foe — its passthrough is
     * unobservable, the foe being inert and never firing), the foe gets {@link CollisionClass#SHIP} so the
     * local Hellbore physically IMPACTS it: the engine spawns the impact FX and despawns the shot (the
     * "passes through the foe" gap). A {@link FoeDamageSuppressor} then zeroes that local hit so the
     * server remains the sole damage/death authority; the {@link InertShipAI} still keeps the foe
     * from firing or maneuvering.
     */
    private void makeServerOwnedFoe(ShipAPI ship) {
        ship.setCollisionClass(CollisionClass.SHIP);
        ship.setShipAI(new InertShipAI());
        ship.addListener(new FoeDamageSuppressor());
    }

    /** GAP 2: emit the read-only discharge meter — distinct local own-ship discharges vs the
     * server-authoritative srcFiring frames, with the frame indices at which each local discharge spawned.
     * Server baseline = 5 discharges / 149 srcFiring frames; localOwnDischarges &gt;&gt; 5
     * CONFIRMS the candidate-2 player-ship double-drive over-fire (fix = rising-edge gating, decided from
     * this number — never a blind throttle). */
    private void logFireMeter(String where) {
        PilotWatchLog.info("GAP2 fire-meter [" + where + "]: localOwnDischarges=" + localOwnDischarges
                + " vs srcFiringFrames=" + srcFiringFrames + " / advFrames=" + advFrames
                + " frames=[" + dischargeFrames + "]");
    }

    /** Stop the pilot thread and close the session of the currently-active plugin, if any. Idempotent. */
    private static synchronized void teardownActive() {
        PilotIntentCombatPlugin prior = active;
        if (prior != null) {
            prior.running = false;
            if (prior.pilotThread != null) {
                prior.pilotThread.interrupt();
            }
            if (prior.session != null) {
                prior.session.close();
            }
            prior.session = null;
            active = null;
        }
    }

    /**
     * A no-op {@link ShipAIPlugin} installed on every server-owned ship so the local engine never fires
     * its weapons or fights the per-frame pose override. Mirrors
     * {@link ServerFedPuppetCombatPlugin}'s {@code InertShipAI}.
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

    /**
     * A {@link DamageTakenModifier} on the collidable foe that zeroes ALL local damage
     * while {@link #suppressFoeDamage} is set, so the local Hellbore impact is purely cosmetic (FX +
     * despawn) and the server stays the damage/death authority. Bytecode RE confirmed the on-hit
     * glow + projectile despawn are damage-INDEPENDENT, so the zeroed hit keeps the impact FX (only the
     * impact SOUND is lost). {@link #killFoe} clears the flag so the authoritative kill blow is NOT eaten.
     * A non-static inner class so it reads the enclosing instance's {@link #suppressFoeDamage}.
     */
    private final class FoeDamageSuppressor implements DamageTakenModifier {
        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage,
                                        Vector2f point, boolean shieldHit) {
            if (!suppressFoeDamage) {
                return null; // killFoe's authoritative blow passes through untouched
            }
            try {
                damage.getModifier().modifyMult("cc_a1_suppress", 0f);
            } catch (Throwable ignore) {
                // presentation-only suppression — never break the watch on a damage-modifier hiccup
            }
            return "cc_a1_suppress";
        }
    }
}
