package cc.starsector.client;

import cc.starsector.client.net.CcwrIntentSourceReader;
import cc.starsector.client.net.ClientGameplayEdgeEvent;
import cc.starsector.client.net.ClientPostureBlock;
import cc.starsector.client.net.ClientRosterAppend;
import cc.starsector.client.net.PredictSession;
import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.net.ServerSnapshotFrame;
import cc.starsector.client.voice.VoiceClientSettings;
import cc.starsector.server.runtime.ClientPostureRegistry;
import cc.starsector.server.runtime.PilotIntentFrame;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipSystemSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.combat.listeners.DamageListener;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.combat.threat.VoltaicDischargeOnFireEffect;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.LabelAPI;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cc.starsector.client.PredictKnobConfig.*;

/**
 * The PREDICT-AND-RECONCILE combat plugin — the in-game prediction CLIENT.
 *
 * <p>This is the faithful successor to {@link PilotIntentCombatPlugin}, which it leaves entirely
 * untouched as the puppet regression baseline. Where the puppet plugin <em>suppresses</em> all local
 * simulation every frame — {@code renderPose} hard-writes the authoritative pose and ZEROES velocity,
 * a cosmetic per-frame {@code ShipCommand.FIRE} ({@code presentFire}), a {@code FoeDamageSuppressor}
 * zeroes the local Hellbore hit, and the own ship is {@link CollisionClass#NONE} (passthrough) — this
 * plugin lets the local real engine SIMULATE and only RECONCILES it against the server's snapshots.
 * That dissolves the GAP 1 (passthrough) and
 * GAP 2 (over-fire) symptoms at the root: they existed only because local simulation was forbidden.
 *
 * <p><b>The hybrid split.</b>
 * <ul>
 *   <li><b>Own ship — input-predicted (zero-RTT).</b> The own ship is the human's flagship, flown by
 *       the engine's OWN native player control: real {@link CollisionClass#SHIP}, real held-trigger
 *       Hellbore firing at the engine's real cooldown. We do NOT puppet it, do NOT make it
 *       server-owned, and do NOT install an inert AI — so it responds instantly to the human (GAP 2
 *       dissolves: the engine's weapon control fires at its real cadence; no {@code presentFire}). The
 *       captured input is still sent UP so the server computes its authoritative copy; locally we only
 *       gently nudge the own ship's POSITION toward the authoritative pose (smooth error-decay) to bound
 *       prediction drift, leaving facing + velocity to native control. (Refinement over the plan's
 *       step 3, which named the server's {@code PilotManeuverIntent}->{@code BasicEngineAI} AI lane:
 *       in-game the human is present, so the engine's native player flight IS the faithful local flight —
 *       MORE faithful than re-driving the AI lane, not a shortcut.)</li>
 *   <li><b>Foe — dead-reckoned.</b> The client has no foe input, so between snapshots the foe continues
 *       its last authoritative {@code (velX,velY,angularVelocity)} (the engine integrates it) and is
 *       reconciled to each authoritative pose. It is a real {@link CollisionClass#SHIP} (so the own
 *       Hellbore visibly COLLIDES + despawns — GAP 1 dissolves) with no damage suppressor: a local hit
 *       is PRESENTATION; the server-authoritative hull is mirrored back over it each frame (so the
 *       client can never decide the foe's death), and the engine's real death sequence runs on the
 *       authoritative edge ({@link OwnSeatLifecycleLane#killFoe}, reusing the existing foe-kill path).</li>
 * </ul>
 *
 * <p><b>Why AWSS, not {@code PilotWorldViewFrame}.</b> Prediction needs velocity to dead-reckon and to
 * keep a post-correction trajectory plausible; the puppet's {@code PilotWorldViewFrame} carries no
 * velocity, so this plugin consumes the full {@code AWSS} snapshot ({@link ServerSnapshotFrame} via
 * {@link cc.starsector.client.net.AwssSnapshotReader}), which carries {@code velX/velY/angularVelocity}
 * + hull (the first concrete consequence of the netcode design). The intent UP channel is the
 * unchanged faithful {@code PilotIntent} lane.
 *
 * <p><b>Threading.</b> As in {@link PilotIntentCombatPlugin}, the strict per-tick exchange
 * ({@code receiveSnapshot()} then {@code sendIntent()}) runs on a daemon {@link #netThread} so the
 * render thread never blocks on the socket; cross-thread fields are {@code volatile}.
 */
public class PredictReconcileCombatPlugin extends BaseEveryFrameCombatPlugin {

    /**
     * Reconcile TIME-CONSTANT rates (per second), frame-rate-INDEPENDENT (the cadence fix).
     * Each frame the per-frame glide factor is derived from the real frame {@code dt} via
     * {@code alpha = 1 - exp(-rate*dt)} ({@link #frameAlpha}), so the correction closes the gap at a fixed
     * rate-per-SECOND regardless of render fps. The tuning was a FIXED per-frame alpha (own 0.10 /
     * foe 0.25) that was implicitly tied to the owner's 540 fps ({@code 0.10/frame @540fps ≈ 61%/snapshot},
     * but only 10%/snapshot @60fps — a ~6x weaker pull on another machine). These rates are chosen so that
     * at 540 fps the per-frame factor REPRODUCES the proven 0.10 / 0.25 (own: {@code -ln(0.90)*540 ≈ 56.9};
     * foe: {@code -ln(0.75)*540 ≈ 155.3}) — i.e. movement is unchanged on the owner's machine, but now
     * fps-stable elsewhere. WATCH-tunable.
     */
    private static final float OWN_RECONCILE_RATE = 56.9f;
    private static final float FOE_RECONCILE_RATE = 155.3f;
    // own-ship reconcile residual counters (D2's instrument; the
    // own-ship pin is the live rival explanation for the backward-kite percept). Measure-only.
    private int ownReconFrames;
    private double ownReconResidSu;
    private float ownReconResidMax;
    private int ownReconBackFrames;
    private double ownReconBackSu;
    private float ownReconBackMax;
    // own-ship 300-su SNAP disclosure: the 4-arg reconcilePosition
    // discarded the snap boolean, so own-pose snaps fired UNDISCLOSED (the resid_max=994556
    // respawn-remount discontinuity was only inferable). Counted at the own tether call.
    private int ownReconSnaps;
    // Meter-hole fixes, both drive-OFF
    // stratified (fly-in glide is by-design huge and polluted resid_max at 2953.66):
    // a settled residual max + the haul-rate meter (su of own-pose correction per 0.5 s
    // gliding window, max kept = the teleport percept quantity; grades the D-CAP).
    private float ownReconResidMaxSettled;
    private float ownHaulWindowSu;
    private float ownHaulWindowSec;
    private float ownHaulMaxSuPerHalfS;
    /** Forces PredictKnobConfig's class init inside this plugin's own, at the slot the table held: a bad knob still fails at class load, never lazily in a frame-loop catch. */
    static { PredictKnobConfig.load(); }
    /**
     * tick-stamped gameplay-edge-event
     * INTAKE, default ON (kit baseline: live-feel long confirmed, so the spec's
     * gate is paid; an explicit {@code =false} still wins — the {@link PredictKnobConfig#DMG_WITNESS_ENABLED} default-ON idiom;
     * twin read {@code PredictDialLane.announceSeatName} must agree). When ON the net thread reads
     * the down stream DEMULTIPLEXED (AWSS + the durable GPEE event lane) and this plugin
     * drains/measures each event against the latest applied snapshot tick (the spec's tick mapping) and, for
     * own-ship window kinds, against the LOCAL engine's window state — the measurement that sizes
     * the (U)-class window skew BEFORE any re-base write is built (the STEP-16 discipline: measured
     * detector first, threshold-gated treatment from that data). With the flag OFF the session read
     * path is byte-identical to pre-B3.
     */
    static final boolean EVENT_REPLAY_ENABLED =
            !"false".equals(System.getProperty("cc.predict.eventReplay", "true"));
    /** eventReplay-intake log cap (raisable; twin = {@link AuthoritativePresenter#SHOT_LOG_MAX}). */
    static final int EVENT_REPLAY_LOG_MAX = 80;
    /**
     * TRUE exactly while THIS window holds the engine's bay kill switch ({@code REFIT_MODE_ADVANCE}):
     * written beside it at the {@link #init} latch and the {@link #teardownActive} clear, never
     * derived from {@link PredictKnobConfig#OWN_BAY_MINT_SUPPRESS}. {@link ReplicaBodyLane#registerReplayDriven}'s
     * own-team carrier-lock SKIP reads this live mirror: under the kill switch the per-body lock buys
     * nothing for bays (the bay advance returns before it reads the lock)
     * and only paints the tab-map X, greys Transfer and forces RETREAT on an own carrier — but a window
     * that registers replicas WITHOUT the latch (ownBayMint off / meter, or an attach that skipped the
     * served init tail) must keep that lock, since there it is the only local-wing-mint guard. A plain
     * static, not the engine's: the game-free selftest drives it with no engine jar on its classpath.
     *
     */
    static boolean bayKillSwitchLatched;

    /** Frames left of the post-adopt retreat-clear re-run (the
     *  adopt-time clear can no-op on the engine's drive-off one-frame latency —
     *  the advance tail re-runs it until {@code isRetreating()} reads false). Set by
     *  {@link CommandTransferLane}'s own-COMMIT apply; package-private by design. */
    int ownAdoptRetreatClearLeft;

    /** direct-RETREAT-order clear (adopt / re-home / 1 s sweep)
     *  + the issuer instrument; package-private for {@link CommandTransferLane}. */
    final ReplicaOrderSweep orderSweep = new ReplicaOrderSweep();
    /**
     * Wire bit → the matching public {@link ShipCommand} (bits 0..6 in the canonical
     * {@code WIRE_BIT_*} / {@code BIT_ACCELERATE..BIT_STRAFE_RIGHT} order; the public commands map
     * 1:1 onto the internal ship-command cases the engine's {@code processCommands} consumes).
     * Keeper note: the rejected lane was the
     * {@code LiveManeuverIntent}→giveCommand seat-wiring ARCHITECTURE, not this enqueue — public
     * {@code giveCommand} is byte-equivalent to the internal enqueue the server predict feed uses.
     */
    static final ShipCommand[] MANEUVER_COMMAND_FOR_BIT = {
            ShipCommand.ACCELERATE,
            ShipCommand.ACCELERATE_BACKWARDS,
            ShipCommand.DECELERATE,
            ShipCommand.TURN_LEFT,
            ShipCommand.TURN_RIGHT,
            ShipCommand.STRAFE_LEFT,
            ShipCommand.STRAFE_RIGHT,
    };
    /**
     * The KEYBOARD-ONLY tap/toggle verbs blocked
     * while the chat box is typing, on top of {@link #MANEUVER_COMMAND_FOR_BIT}. Every ship verb
     * is engine-POLLED ({@code Keymap} binding → raw {@code Keyboard.isKeyDown}) — event consumption
     * can never
     * stop ship control, so the "consumption covers taps" premise was false; the command
     * block is the one lever (the engine's advance drops blocked non-forced commands before
     * {@code processCommands}, same frame when registered pre-core-controls). Deliberately absent:
     * FIRE + USE_SELECTED_GROUP (mouse-left — aim/fire stay live while typing, the
     * accepted adjudication) and TOGGLE_SHIELD_OR_PHASE_CLOAK (mouse-right rides the same
     * command — blocking it would dead the mouse shield toggle mid-typing).
     */
    private static final ShipCommand[] TYPING_BLOCKED_TAP_COMMANDS = {
            ShipCommand.USE_SYSTEM,
            ShipCommand.VENT_FLUX,
            ShipCommand.SELECT_GROUP,
            ShipCommand.TOGGLE_AUTOFIRE,
            ShipCommand.HOLD_FIRE,
            ShipCommand.PULL_BACK_FIGHTERS,
    };
    // ------------------------------------------------------------------ hulk-split sync

    /** Hulk-split sync — the whole {@code HKFR} fracture-family lane (state + drivers)
     * lives in the extracted {@link FractureFamilyCoordinator} (the {@link #fireLanes} idiom;
     * the orchestration seam). The plugin keeps only the thin touchpoints: veto install at
     * combat init, net-thread intake + despawn-tick feed, render sweep, mint/suppress at the kill
     * edges, and the round-wrap reset. */
    final FractureFamilyCoordinator fractures = new FractureFamilyCoordinator(this);
    /** D-HULKDEL: the remote-respawn outgoing-body law, own-file. */
    final RemoteRespawnCorpseLane respawnCorpse = new RemoteRespawnCorpseLane(this);
    /** Seat-name tags + the server-hull-bar overlay (state +
     * draw laws) live whole in the extracted {@link SeatNameHullBarLane};
     * the plugin keeps the render hook, the protected draw-reach seam, and the bind/reset/drain
     * touchpoints. */
    final SeatNameHullBarLane seatHud = new SeatNameHullBarLane(this);
    final FighterWingHudLane fighterHud = new FighterWingHudLane(this); // piloted-carrier wing panel (icons/pips/rate) from the wire
    /** Chat bubbles over ships — state, timers, suppression + draw laws live whole
     * in the extracted {@link ChatBubbleLane} (sibling-collaborator law); the plugin keeps only
     * these thin relays (render/advance/reset). Intake rides {@link ChatLane}'s drain forward. */
    final ChatBubbleLane chatBubbles = new ChatBubbleLane(this);
    /** Large-list picker — LSTD assembly, prompt/panel render, the
     * pure {@link ListPickerLaw} feed and resolve-to-send live whole in the extracted
     * {@link ListPickerLane} (sibling-collaborator law); the plugin keeps only these thin
     * relays (pump/input/render/reset). Selection deposits ride {@link ChatLane}'s send queue. */
    final ListPickerLane picker = new ListPickerLane(this);
    /** Voice presence — positions publish, speaker glyph, HUD
     * talking row, PTT input relay + settings hand-off live whole in {@link VoicePresenceLane}. */
    final VoicePresenceLane voiceHud = new VoicePresenceLane(this);
    /** The bottom-right net row + the bottom-up stacker it shares with
     * the voice row; {@link HudReadoutLane#prefs()} = the ONE HUD-toggle resolver. */
    final HudReadoutLane hudReadout = new HudReadoutLane(this);
    /** The own-ship speed / flux / system samplers + the sim-rate
 * and net row composers, carved VERBATIM into {@link OwnReadoutLane}; their state stays HERE
 * (teardown meter flush + the diagnostic dump read it) as package-private fields the lane
     * reads through {@code plugin}.     */
    final OwnReadoutLane ownReadout = new OwnReadoutLane(this);
    final TerrainLane terrainLane = new TerrainLane(this); //
    final AsteroidLane asteroidLane = new AsteroidLane(this); //
    /** The GPEE edge-event lane — net-thread intake, despawn fold,
     * the per-snapshot drain + demux/present, the own-window mid-flight meter, the own-discharge
     * declaration push and the replay-meter flush — carved VERBATIM into {@link EdgeEventLane};
     * its state stays HERE (teardown flush, diagnostic dump, match reset and the net thread read
     * it) as package-private fields the lane reads through {@code plugin}. */
    final EdgeEventLane edgeEvents = new EdgeEventLane(this);
    /** SERVE autopilot fire — the AI's selected-group fire + aim folded into the
     * held lane's level/aim samples ({@link AutopilotFireLane}); rules in {@code AutopilotFireServeLaw}. */
    final AutopilotFireLane autopilotFire = new AutopilotFireLane(this);
    /** Spawn leg: the OWN-SEAT LIFECYCLE — the server-decided death edges,
     * the owner-mint fix, the fly-in / at-rest respawns, the claim-at-play field, the alive re-field
     * resolver, the shared own-spawn core and the post-respawn sampler — carved VERBATIM into
     * {@link OwnSeatLifecycleLane} as a REAL extraction: the nine fields exclusive to those bodies
     * (one-shot log latches, the bounded-retry counter, the sampler clock, the owner-mint counter and
     * its two caps) live THERE; the shared seat/body state stays here, package-private for the lane. */
    final OwnSeatLifecycleLane ownSeat = new OwnSeatLifecycleLane(this);
    /** The OBSERVER free camera (no-op unless {@link ObserverMode#ON}). */
    final ObserverCameraLane observerCam = new ObserverCameraLane(this);
    /** own-vs-wire facing rows across each own fly-in hold. */
    private final FlyInFacingRowLane flyInFacing = new FlyInFacingRowLane();

    /**
     * Render hook ({@code CombatLayeredRenderingPlugin}) — thin relays only: the chat typing line,
     * then the two {@link SeatNameHullBarLane} passes (each carries its own
     * dead-latch + per-ship gates; placement/fog/side laws live lane-side).
     */
    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        long t0 = System.nanoTime(); // Wall split: the plugin's own UI-pass wall (rnd_ms)
        try {
            renderInUICoordsBody(viewport);
        } finally {
            frameLedger.noteRenderWall(System.nanoTime() - t0);
        }
    }

    private void renderInUICoordsBody(ViewportAPI viewport) {
        hudReadout.beginFrame(); // bottom-right stacker — no slot claimed yet
        chat.renderChatTypingLine(); // Independent of the seat-tag lane's gates
        seatHud.renderServerHullBars(viewport); // knob-gated OFF, own dead-latch
        seatHud.renderSeatNameTags(viewport); // always-on tags
        chatBubbles.render(viewport); // After the tags — bubbles sit one row above
        hudReadout.renderNet(viewport); // the ping/updates row = the BOTTOM slot
        hudReadout.renderSim(viewport); // The sim-rate row (was the left-HUD cc_sim_rate) — MIDDLE slot
        voiceHud.render(viewport); // speaker glyph beside the tag + HUD talking row = the TOP slot (extra talker rows stack above it, never jostling sim/ping)
        observerCam.render(viewport); // OBSERVER posture row, top-left (no-op unless ObserverMode.ON)
        picker.render(); // Prompt/panel LAST — the modal sits over every HUD lane
    }

    /**
     * Draw-reach seam — kept ON the plugin (not the lane): the RenderCorpseGuardSmoke selftest
     * subclasses this plugin cross-package and overrides this method to record which
     * ships reach draw, so the corpse/fog guard laws stay pinned; the lane renders every tag back
     * through this relay. Never called outside the render thread / that smoke.
     */
    protected void drawSeatNameTag(ViewportAPI vp, ShipAPI ship, String name) {
        seatHud.drawSeatNameTag(vp, ship, name);
    }

    /**
     * The one-shot sidelog posture dump at {@code PSTB} receipt —
     * one row per census knob + the trailer family, the server dump grammar verbatim, into the
     * pilot watch sidelog (the session class stays log-silent by design, so the dump lives at
     * THIS poll). The block's server-set wire tag, when present, supersedes the local
     * {@code cc.posture.experiment} read ({@code resolveLive}'s documented M4-A2 contract);
     * idempotence = the session's take-clears latch (a re-admission's fresh block dumps again —
     * the new stamp is new truth, and the dump is bounded by the once-per-admission spool).
     */
    private void drainPostureBlock() {
        ClientPostureBlock block = session != null ? session.takePostureBlockOrNull() : null;
        if (block == null) {
            return;
        }
        ClientPostureRegistry.Posture posture = block.hasTag()
                ? ClientPostureRegistry.resolve(System.getProperties(), System.getenv(),
                        block.experimentTag())
                : ClientPostureRegistry.resolveLive();
        PilotWatchLog.info("posture sidelog dump (PSTB receipt; wire tag="
                + (block.hasTag() ? "'" + block.experimentTag() + "'" : "none") + "):");
        for (String line : posture.lines()) {
            PilotWatchLog.info(line);
        }
        // Schema 2 — apply the wire-delivered knob census (the per-admission spool
        // and every operator /knob re-spool land here; ClientWireKnobs holds them volatile).
        for (String row : ClientWireKnobs.applyBlock(block)) {
            PilotWatchLog.info(row);
        }
    }

    /**
     * The ONE own-side authority for any side-keyed lookup or
     * side filter: the bind-time capture when it exists, else the live read as a round-1
     * pre-capture last resort. The old "a death implies a prior successful capture" premise was
     * FALSIFIED: the capture lived only in {@link #bindShips}, which the
     * spawn-on-sight lane never enters, so a whole session ran pre-capture and the first own
     * death handed every side-keyed spawn the corpse's owner 100 (fresh own hull minted NEUTRAL
     * = the blue-HUD/FoW no-op, foe respawn via {@code getFleetManager(1-100=-99)}). The capture
     * now runs in BOTH binder lanes ({@link #captureOwnSide}); if the fallback still ever reads a
     * non-combatant side it logs loud once instead of poisoning spawns silently. NEVER read a
     * possibly-dead ship's {@code getOwner()} for a side inline — the engine flips a corpse to
     * owner 100, which nulls {@code getFogOfWar} (the render NPE) and passes every live
     * ship through a side-equality foe scan (the dead-ship side-misread class; smoke law B1). build.ps1's
     * bind-side lint enforces this file-wide; the RenderCorpseGuardSmoke gate pins the behavior.
     */
    int sideAuthority() { // package-private: StationAdoptBindLane's owner filter reads it
        if (ownSideAtBind >= 0) {
            return ownSideAtBind;
        }
        int live = ownShip != null ? ownShip.getOwner() : 0; // LINT-ALLOW corpse-side: round-1 pre-capture fallback
        if (live != 0 && live != 1 && !sideFallbackPoisonLogged) {
            sideFallbackPoisonLogged = true;
            PilotWatchLog.error("predict sideAuthority: pre-capture fallback read NON-COMBATANT side "
                    + live + " — the side anchor was never captured and side-keyed spawns will be"
                    + " wrong until it is (uncaptured-anchor class)", null);
        }
        return live;
    }

    /**
     * {@code /switchteams} support — the LOCAL engine owner for an ABSOLUTE wire side
     * ({@link TeamPerspectiveLaw#localOwner}): the own team ⇒ {@link #sideAuthority()}, the other
     * ⇒ its opposite, neutral/unknown unmapped. Every site that applies a raw wire side to a local
     * body goes through here (sourceless missiles + motes, {@code MissilePoseLane}).
     */
    int localOwnerForWireSide(int abs) {
        return TeamPerspectiveLaw.localOwner(abs, ownDescSide, sideAuthority());
    }

    /**
     * THE bind-side capture ({@link #ownSideAtBind}), shared by both binder
     * lanes: {@link #bindShips} (local-bots) and {@link #spawnRemoteEntities} (spawn-on-sight).
     * Root cause: the capture lived ONLY in bindShips, so the remote-registry lane ran
     * every side-keyed lookup off the live-read fallback for the whole session — correct while the
     * own ship lived, owner 100 at the first own-death instant. Logs on capture (once per session
     * by the {@code ownSideAtBind < 0} gate) so a live run PROVES the side anchor in one grep.
     */
    private void captureOwnSide(String where) {
        if (ownSideAtBind < 0 && ownShip != null
                && (ownShip.getOwner() == 0 || ownShip.getOwner() == 1)) { // LINT-ALLOW corpse-side: capture guard
            ownSideAtBind = ownShip.getOwner(); // LINT-ALLOW corpse-side: THE bind-time capture; see field doc
            PilotWatchLog.info("predict bind-side captured: ownSideAtBind=" + ownSideAtBind
                    + " [" + where + "] (side anchor; sideAuthority now corpse-proof)");
        }
    }
    /**
     * The SYNTHETIC-SEAT input override, knob
     * {@code cc.predict.intentSource} = {@code ccwr:<abs path>[:skip=N]} (default unset = OFF: the
     * proven live-input lane, byte-identical). When set, this client stays the REAL predict client
     * end to end — session, demux, predict, reconcile, apply — with ONLY the input-poll VALUES
     * replaced: each net-loop exchange consumes the next recorded UP frame's
     * aim/facing/heldControls/fireHeld ({@link CcwrIntentSourceReader}; a real human's recorded
     * stream, carried verbatim) instead of this frame's Mouse/controller reads, and
     * {@code session.sendIntent(...)} plus every byte below it is unchanged (shipId/frameSeq/weapon
     * group stay THIS session's). The render-thread pollers are idled so they cannot race the
     * recording. After exhaustion the lane sends NEUTRAL frames (no keys, no fire, facings held at
     * the last recorded values — the ghost precedent: an honest "round finished", never an invented
     * input). {@code skip=N} enters mid-recording (like the ghost tool's {@code --skip}: e.g. straight
     * into the fire window). A malformed value THROWS at class load; an unreadable recording aborts
     * combat init loudly — a synthetic seat must fail loud, never silently fly neutral.
     */
    private static final CcwrIntentSourceReader.Spec INTENT_SOURCE = parseIntentSource(
            System.getProperty("cc.predict.intentSource", ""));

    private static CcwrIntentSourceReader.Spec parseIntentSource(String raw) {
        String value = raw == null ? "" : raw.trim();
        return value.isEmpty() ? null : CcwrIntentSourceReader.parseSpec(value);
    }

    /**
     * The LIVENESS instrument, knob
     * {@code cc.predict.stallRead} = {@code <atExchange>:<millis>} (default unset = OFF: the proven
     * lanes are byte-identical — one null static, the {@link #INTENT_SOURCE} pattern). When set,
     * the net thread sleeps {@code millis} immediately BEFORE the read of exchange index
     * {@code atExchange} (0-based count of completed reads) — the application-layer slow reader
     * the server's PeerSendGate ladder exists to police (conflation → DOWNSHIFTED → KICKED while
     * the sim never waits). Indexing is orientation-grade, not exact: pre-combat priming frames
     * shift wire indices by one. A malformed value THROWS at class load —
     * a liveness probe must fail loud, never silently not stall.
     */
    private static final long[] STALL_READ = parseStallRead(System.getProperty("cc.predict.stallRead", ""));

    private static long[] parseStallRead(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            throw new IllegalArgumentException("cc.predict.stallRead must be <atExchange>:<millis>, got: " + value);
        }
        long atExchange = Long.parseLong(value.substring(0, colon));
        long millis = Long.parseLong(value.substring(colon + 1));
        if (atExchange < 0 || millis <= 0) {
            throw new IllegalArgumentException(
                    "cc.predict.stallRead needs atExchange >= 0 and millis > 0, got: " + value);
        }
        return new long[] { atExchange, millis };
    }

    /**
     * The duplex client half: split the one net thread's strict per-tick ping-pong
     * into a READER loop (receive/demux → publish {@code latestSnapshot}, unchanged routing) and a
     * WRITER loop (one intent UP per render frame, paced by {@code advance()}'s deposit signal +
     * an unconditional heartbeat floor) — so the client's UP rate is its local frame rate, never
     * 1/RTT (the v1 weld: one UP per DOWN received). Knob {@code cc.predict.duplex} (default OFF —
     * the proven welded lane stays byte-identical; invalid values THROW). Requires a duplex-mode
     * server ({@code net.sendMode=duplex}); against a v1 server this client would ratchet UP
     * backlog — kit discipline + the server's echo-age ratchet tripwire police that pairing.
     */
    private static final boolean DUPLEX = parseDuplex(System.getProperty("cc.predict.duplex", "false"));

    private static boolean parseDuplex(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(v)) {
            return true;
        }
        if ("false".equals(v)) {
            return false;
        }
        throw new IllegalArgumentException("cc.predict.duplex must be true|false, got: " + raw);
    }

    /**
     * The WRITER-side liveness instrument, knob
     * {@code cc.predict.stallWrite} = {@code <atSend>:<millis>} (default unset = OFF: one null
     * static, the {@link #STALL_READ} pattern). When set, the duplex writer thread sleeps
     * {@code millis} immediately BEFORE send index {@code atSend} (0-based count of completed
     * sends) — the "stalled writer with live reader" drill shape: the server's RECV deadline must
     * reap the silent seat while its DOWN lane still streams. Under welded mode the knob is
     * accepted but never fires (the welded loop has no writer thread). A malformed value THROWS
     * at class load — a liveness probe must fail loud, never silently not stall.
     */
    private static final long[] STALL_WRITE = parseStallWrite(System.getProperty("cc.predict.stallWrite", ""));

    private static long[] parseStallWrite(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            throw new IllegalArgumentException("cc.predict.stallWrite must be <atSend>:<millis>, got: " + value);
        }
        long atSend = Long.parseLong(value.substring(0, colon));
        long millis = Long.parseLong(value.substring(colon + 1));
        if (atSend < 0 || millis <= 0) {
            throw new IllegalArgumentException(
                    "cc.predict.stallWrite needs atSend >= 0 and millis > 0, got: " + value);
        }
        return new long[] { atSend, millis };
    }

    /**
     * Heartbeat floor — the duplex writer re-sends the last captured input after
     * this many milliseconds without a fresh deposit. Default-ON and unconditional (NOT a rider):
     * {@code advance()} early-returns on {@code isPaused()} BEFORE input capture, so a client
     * sitting in the ESC/pause menu deposits nothing and would go silent past the server's 10 s
     * read deadline and get reaped. Re-sending the stale captured fields is exactly v1's pause
     * behavior (its net thread answered every DOWN with the frozen volatiles regardless of pause).
     * 250 ms = 40× inside the deadline, ~63 B × 4/s of bandwidth, and bounds how stale the
     * server-held intent can go while the local human sits in a menu. No knob (pt 11: no rate
     * knob unless measurement demands one).
     */
    private static final long HEARTBEAT_FLOOR_MILLIS = 250L;

    /**
     * Advance-staleness watchdog — the ESC-exit seat-leak fix. ESC-exit to title
     * MID-ROUND abandons this plugin to GC with NO engine callback ({@code removePlugin} is never
     * engine-called, {@code isCombatOver()} never latches under the mod's own
     * {@code setDoNotEndCombat(true)}), so the daemon writer thread kept heartbeating a live seat
     * indefinitely — today's only match-end BYE trigger was {@code advance()} seeing
     * {@code isCombatOver()}. Fix: {@link #lastAdvanceMillis} is stamped at the VERY TOP of
     * {@code advance()} — BEFORE the paused early-return, and paused frames still deliver
     * {@code advance(0)}, so a pause/menu can never false-fire — and the duplex writer (which
     * already wakes at least every {@value #HEARTBEAT_FLOOR_MILLIS} ms) tears the session down
     * ({@code teardownActive()} → BYE, the s16 goodbye law) once the stamp goes stale past this
     * bound with a live session. 10 s = 40 writer wakes and matches the server's own read
     * deadline; the welded (non-duplex) lane has no writer thread and keeps today's behavior.
     * The OTHER zombie class (whole-JVM game loop stepping plugins post-quit) is
     * indistinguishable in-JVM from healthy play and stays the launcher REAPER's to cover.
     */
    private static final long ADVANCE_STALE_TEARDOWN_MILLIS = 10_000L;

    /** Wall-clock stamp of the latest {@code advance()} entry (see the watchdog constant above). */
    private volatile long lastAdvanceMillis;

    /**
     * Own-ship true-speed HUD readout (gap ③) + the gap-② measurement instrument.
     * Vanilla icon (burn-drive = a speed system) for the two status-panel lines, and the (u/s) delta past
     * which the SERVER-speed line is highlighted as a "debuff" (red). A persistent local-vs-server speed
     * divergence is the visible signature of a movement-affecting mechanic the authoritative server does
     * NOT model (the zero-flux / flux->speed bonus, or the ship-system boost) but the client's real local
     * engine does — which is exactly what the follow-on work sets out to integrate onto the server.
     */
    static final String SPEED_ICON = "graphics/icons/hullsys/burn_drive.png";
    static final float SPEED_DIVERGE_HILITE = 10f;
    /**
     * Flux OBSERVABILITY beside the speed lines.
     * Beside the two SPEED lines, surface LOCAL vs SERVER FLUX (the
     * authoritative flux now decoded onto each AWSS pose, schema v3+), so the operator can correlate live and
     * tell us which mechanic actually drives the 110-vs-140 own-ship speed gap,
     * so this MEASURES it directly instead of guessing. The flux icon highlights the
     * SERVER line when the flux LEVEL diverges past {@link #FLUX_DIVERGE_HILITE}. The icon
     * path matters: {@code maintainStatusForPlayerShip} draws only a REGISTERED
     * sprite — stock {@code settings.json} registers {@code venting_flux2.png}
     * ({@code icon_tactical_venting}, the vanilla VENTING status icon), never {@code venting_flux.png}.
     */
    static final String FLUX_ICON = "graphics/icons/tactical/venting_flux2.png";
    static final float FLUX_DIVERGE_HILITE = 0.10f;
    /**
     * Timestamped sim-rate readout: highlight the SIM RATE line once the server clock outruns
     * the client by more than this factor (the owner read ~1.27). Applied to the trailing-window ratio
     * — the slope buffer's half-window span gate doubles as the warmup suppressor
     * ({@code measuring…} until it fills), so no separate warmup constant.
     */
    static final double SIM_RATE_DIVERGE_HILITE = 1.10d;
    /**
     * A wall dt beyond this is a pause/load gap (advance() early-returns while paused, so the first
     * post-pause frame carries a multi-second dt), not a steady-state render hitch — excluded from the
     * clock accounting (and so from the servo window) so it cannot masquerade as dropped time.
     */
    private static final double PAUSE_GAP_SEC = 0.5d;
    /**
     * Stale-snapshot threshold (an honest boundary): render runs far faster than the net
     * tick, and the feed stops entirely at teardown, so SERVER telemetry + the running max deltas are recomputed
     * only on a FRESH authoritative frame (one whose {@code simTick} advanced). Once no fresh frame has arrived
     * for this much WALL time the readout is flagged STALE — which is what stops the post-teardown frozen server
     * pose from inflating the speed/flux delta (the "steady-state maxDelta612 was a stale artifact" lesson).
     * WALL-CLOCK, not render frames: the old 120-frame count was fps-coupled — the intended ~2 s
     * window shrank to ~0.22 s at fps=540, so an ordinary 0.25 s WAN delivery gap cut velocity glide mid-match.
     */
    private static final long STALE_SNAPSHOT_NANOS = 2_000_000_000L;
    /**
     * The ping + server-tickrate HUD readout beside
     * the speed/flux lines. PING = the session's transport RTT estimate (UDP: the reliability core's
     * snap-echo EWMA — every inbound datagram echoes the server's high-water mark of our UP
     * intent seqs, so it refreshes at ~the DOWN cadence with ZERO wire change; TCP reads n/a —
     * the archived fallback stream carries no UP echo). SERVER TICK is MEASURED off the snapshot
     * stream, never a client constant: ΔsimTick/Δwall over the trailing
     * {@link #SLOPE_WINDOW_SEC} window, beside the DOWN publish rate (frames the net loop
     * landed in {@link #latestSnapshot} per second — post-conflation, what reconciliation
     * actually consumed).
     */
    private static final String NET_ICON = "graphics/icons/tactical/neural_link.png";

    CombatEngineAPI engine;
    /** The extracted presentation seam — foe-fire trio + shared death core; built in init. */
    AuthoritativePresenter presenter;

    /** The most recent authoritative AWSS snapshot the net thread received, for the render thread to reconcile to. */
    volatile ServerSnapshotFrame latestSnapshot; // package: SeatNameHullBarLane reads the published frame (render thread)
    /** This frame's live human input, captured on the render thread, sent UP on the net thread. */
    volatile float aimDeg;
    /**
     * The own ship's REAL visual heading this frame ({@code ownShip.getFacing()}), captured on the render
     * thread and sent UP separately from {@link #aimDeg} (the facing-frame fix (B)). Native
     * Starsector flight is A/D-driven — the cursor aims weapons, it does NOT steer the hull — so the server
     * pins its own-ship copy to THIS heading (not the cursor) and translation thrust integrates in the frame
     * the human actually flew. Fix (A) wrongly equated facing with {@link #aimDeg} and cursor-locked the ship.
     */
    private volatile float ownFacingDeg;
    private volatile int heldControls;
    volatile boolean fireHeld; // package: this frame's DERIVED held-fire level (mouse-0 post-swallow OR the AI selected-group fold), sampled in processInputPreCoreControls; AutopilotFireLane.heldRecency re-reads it as one input to its LIVE level re-derivation at a discharge instant (not a raw read of the sample)
    /**
     * PINT v3: the aim WORLD POINT this frame — the own ship's live {@code getMouseTarget()}
     * (engine-computed world coords; the exact value the parity walker reads, which measured
     * wire-UNCOVERED). The server pins its engine ship's {@code mouseTarget} to this each
     * tick so the authoritative mounts slew toward the player's true aim, and FIRE aims at this point.
     * Fail-soft like {@link #ownFacingDeg}: keeps the last finite value on any read hiccup.
     */
    volatile float aimWorldX;
    volatile float aimWorldY;
    /**
     * The own ship's live SELECTED weapon-group ordinal
     * ({@code getWeaponGroupsCopy().indexOf(getSelectedGroupAPI())}; stays {@code -1} until the
     * first resolve — the session falls back to the connect-time group; an off-list
     * resolve is {@code SELECTED_GROUP_DESELECTED} (-2), a REAL engine state carried UP so the
     * server can release the playerShip autofire override — never conflated with the -1
     * never-sampled fallback) and the per-group
     * {@code isAutofiring()} bitmask ({@code -1} = ABSENT: never sampled — intentSource seats and
     * pre-first-sample frames — the server skips the autofire diff on the sentinel). Engine STATE
     * mirrored UP like {@link #ownFacingDeg}, not recorded input — {@code applyIntentSourceFrame}
     * never substitutes these (the contract covers only the four recorded input fields).
     */
    private volatile int liveSelectedGroup = -1;
    private volatile int liveAutofireMask = -1;
    /**
     * PINT v6: the own ship's live ACTIVE-TUBE ordinal — the selected group's
     * active weapon's index in that group's {@code getWeaponsCopy()} order iff the group is
     * ALTERNATING, else {@code TUBE_ORDINAL_ABSENT} (-1). Engine STATE mirrored UP like
     * {@link #liveSelectedGroup}: the engine advances an alternating group's tube pointer on
     * select-command RISING EDGES while the wire carries selection STATE, so a re-select of the
     * already-selected group is a wire-invisible client-only +1 (the seat-1 off-by-one
     * class) — carrying the pointer as per-tick state lets the server conform
     * read-compare-apply at fire-quiescent ticks, healing the whole divergence class.
     */
    private volatile int liveActiveTubeOrdinal = PilotIntentFrame.TUBE_ORDINAL_ABSENT;
    /**
     * PINT v5: the own ship's live R-key ship-target designation as a WIRE id — engine
     * STATE mirrored UP like {@link #liveSelectedGroup} (the client engine already accepted the
     * pick; raw keys are never read). Stays {@code TARGET_ABSENT} (-1) until the first sample
     * (intentSource seats never sample — the server skips designation, pre-v5 behavior); a sampled
     * frame carries a real wire id or {@code TARGET_NONE} (-2, no target / unmappable — the server
     * clears iff its hull's target differs). Mapping: own ship ⇒ the session's wire id; a remote
     * replica ⇒ its {@link #remoteEntities} key (1v1-scale linear scan — deliberately NO parallel
     * reverse map, a frozen design choice).
     */
    private volatile long liveTargetWireId = cc.starsector.server.runtime.PilotIntentFrame.TARGET_ABSENT;
    /**
     * PINT v5: 0..255 wrapping rising-edge counter of the sampled fire button —
     * cumulative input STATE, so the latest-wins UP lane cannot conflate away a tap the way a
     * level-sampled {@link #fireHeld} loses one; the server's held lane
     * arms a bounded depth-1 press latch on any observed advance. Never resets mid-session (wrap-safe
     * {@code !=} compare server-side); intentSource seats leave it 0 (legacy-inert — the latch never arms).
     */
    private volatile int fireEdgeSeq;
    /** Render-thread-only previous fire-button sample backing the {@link #fireEdgeSeq} edge detect. */
    private boolean fireEdgePrevHeld;
    /**
     * Mouse1 held from the menu leave/join click bleeds
     * into the fire intent at mission (re)entry
     * (pre-existing). Armed at construction + re-armed at combat init (a leave/join cycle mints a
     * fresh combat instance, so (re)entry ⇔ init; intra-match round wraps never re-arm — a fire
     * held across a round reset stays legit). While armed: the wire intent reads fire-neutral and
     * FIRE/USE_SELECTED_GROUP are frame-blocked locally; cleared on the first mouse1-up sample.
     */
    private boolean swallowFireUntilMouseRelease = true;

    private volatile boolean running;
    // Not volatile: the late-attach bind publishes it under the class lock, where the hold watchdog reads it.
    PredictSession session; // package-private: FractureFamilyCoordinator reads shipId at mint
    private Thread netThread;

    /** The live session's voice orchestrator (session-owned, closed with
     * it) or null when no session is live — the ONE handle the voice/presence lanes read. */
    cc.starsector.client.voice.VoiceClient voice() {
        PredictSession s = session;
        return s == null ? null : s.voice();
    }
    /**
     * Duplex client half — the writer thread + its deposit signal. The volatile
     * input fields above ARE the latest-wins intent mailbox (exactly the fields v1's net thread
     * read at send time — latest-wins because each render frame overwrites them); the signal is
     * the proven loader-B monitor pattern (plain lock + wait/notify, like {@code pendingFireDecls}
     * — NO new {@code java.util.concurrent} class under script classloader B without a WATCH
     * proving it loads). {@code intentFresh} is guarded by {@code intentSignal}; a heartbeat wake
     * with it false re-sends the last captured input (the pause-menu liveness floor).
     */
    private Thread writerThread;
    private volatile boolean writerRunning;
    private final Object intentSignal = new Object();
    private boolean intentFresh; // guarded by intentSignal
    /** Duplex writer meters for the WATCH log: heartbeat-floor sends + MRST-gap held wakes. */
    private int heartbeatSends;
    private int writerResetHolds;
    /** Sends the transport gate ate (reconnecting / authority held / kicked) — kept
     * OUT of {@code sent} so the ended line's send count is wire truth, not attempts. */
    private int sendsGated;

    /** The human's own ship (the flagship) — flown by the engine's native player control, reconciled to truth. */
    ShipAPI ownShip;
    /** The foe — dead-reckoned + reconciled, server owns its damage/death. */
    ShipAPI foeShip;
    /**
     * Own side captured at bind time, while the ship is a live combatant (-1 until first bind).
     * Session-scoped — survives applyMatchReset. A dead own ship reads owner 100 (hulk), so the
     * respawn rebuild must never derive sides from the corpse: one recorded run spawned the fresh own
     * ship via getFleetManager(100) and the foe via getFleetManager(-99) after an own-death round
     * (blue own HUD / NEUTRAL foe; recurred in a later run when only ONE
     * binder lane captured — now {@link #captureOwnSide} in both).
     */
    int ownSideAtBind = -1; // package: SeatNameHullBarLane reads the bind-time side capture
    /** One-shot: sideAuthority's pre-capture fallback read a non-combatant side. */
    private boolean sideFallbackPoisonLogged;
    /** bindShips entries — drives the sparse bind diagnostic while the foe stays unbound. */
    private int bindAttempts;
    /** Guard so the server-authoritative foe death runs exactly once on the alive->dead edge. */
    boolean foeKilled;
    /** Sim tick of the server-decided foe kill (set by {@code killFoe}; MIN = none).
     *  {@link ServerDealtSourceLaw} keys on the kill TICK. */
    long foeDeathTick = Long.MIN_VALUE;
    /**
     * While true, {@link FoeDamageCap} CAPS a would-be-lethal local hit so the foe cannot die client-side
     * (axis B). {@link OwnSeatLifecycleLane#killFoe} clears it so the authoritative kill blow passes through (axis C). Read by
     * the non-static {@link FoeDamageCap} inner class; written only on the render thread.
     */
    boolean capFoeDamage = true;
    /**
     * Foe-fire presentation: the OWN-ship mirror of {@link #capFoeDamage}. Presented
     * foe projectiles deal REAL local damage to the own ship (prediction; the per-frame hull mirror
     * returns hull to truth), so the own ship now needs the same axis-B kill guard the foe has had:
     * {@link OwnDamageCap} caps a would-be-lethal LOCAL hit so the client can never
     * decide the own ship's death. Cleared ONLY by {@link OwnSeatLifecycleLane#killOwn} (axis C) so the
     * server-decided kill blow passes through — exactly the {@link OwnSeatLifecycleLane#killFoe}/{@code capFoeDamage} shape.
     */
    boolean capOwnDamage = true; // package: OwnMountLifecycleLane adopt/release (axis B arm/off)
    /** One-shot guard: {@link OwnDamageCap} installed on the own ship (both bind paths call {@link #installOwnCap}). */
    boolean ownCapInstalled; // package: OwnMountLifecycleLane adopt re-installs the cap
    /** Logged once when own/foe are first resolved against the snapshot, so a bad mapping is visible in the WATCH. */
    private boolean mappingLogged;
    /** Logged once if the seat-2+ index fallback is REFUSED (never silently mis-map own onto entity 1). */
    private boolean fallbackRefusedLogged;
    /**
     * Latched the first time the own entityId resolves BY ID in a snapshot. The seat-1
     * index fallback is allowed ONLY while false (its startup missing-id purpose) — after the mapping
     * is established, own-id absence is the server-decided OWN death ({@link OwnSeatLifecycleLane#killOwn}), never a remap
     * (the un-gated fallback remapped own onto the KILLER and killFoe'd its alive body).
     */
    private boolean ownPoseSeen;
    /**
     * The last authoritative own pose (id-keyed). The server kills and roster-drops in
     * the SAME frame, so the drop frame carries no own pose — this previous-frame pose is the
     * authoritative death pose {@link OwnSeatLifecycleLane#killOwn} snaps to. Package-read by {@code FighterWingHudLane}
     * (its per-frame rate byte) — the lanes never write it.
     */
    ServerEntityPose lastOwnPose;

    /** |local own hull facing − last wire facing| shortest arc (deg) — the
     * aim-meter hull-gap classifier's input (re-field fly-in shots fired with the
     * local hull still on the fly-in heading, ~160° off the wire facing, ~1.1k ticks after the
     * ARMED respawn edge — hull-pose divergence, not aim error, and outside any fixed edge
     * window). 0 when either side is unavailable (fail-open: no exclusion without evidence). */
    float ownHullFacingGapDeg() {
        try {
            ShipAPI own = ownShip;
            ServerEntityPose auth = lastOwnPose;
            if (own == null || auth == null || !own.isAlive()) {
                return 0f;
            }
            return Math.abs(((auth.facing() - own.getFacing()) % 360f + 540f) % 360f - 180f);
        } catch (Throwable ignore) {
            return 0f;
        }
    }
    /**
     * Latched the first time the FOE resolves in a snapshot — the foe-side mirror of
     * {@link #ownPoseSeen}. The single-foe death edge ({@link OwnSeatLifecycleLane#killFoe} else-branch) is gated on it:
     * an unattended fast join consumes pre-spawn/entity-less early wire frames (e.g. a rig run's
     * first mapping frame {@code entities=0}), and reading foe ABSENCE off those as the roster-drop
     * death contract killFoe'd a foe that had never appeared. Death arms only once the foe was EVER
     * wire-seen; latched BEFORE the seat-1 startup fallback (same rule as {@link #ownPoseSeen}: a
     * fallback-resolved pose never counts as an established mapping). The registry shapes need no
     * twin — their bodies spawn at first wire sight, so {@code re.ship != null} already implies seen.
     */
    private boolean foePoseSeen;
    /** One-shot: the foe death edge was DEFERRED pre-mapping (foe never wire-seen yet) — loud, so a foe that never appears stays visible. */
    private boolean foeKillPreMappingLogged;
    /** Guard so the server-authoritative OWN death runs exactly once on the alive->dead edge. */
    boolean ownKilled; // package: ModuleMountLane's death-clear + pending sweep read it
    /** Sim tick of the server-decided OWN kill (set by {@code killOwn} only — a voluntary dismount
     *  latches {@link #ownKilled} but never this; MIN = none).
     * It fixes the dismount-latch misclassification. */
    long ownDeathTick = Long.MIN_VALUE;
    /**
     * fly-in respawns fielded for the OWN seat: the own wire id REAPPEARING
     * alive after {@link #ownKilled} is the server's remount contract (cooldown:N re-interns the
     * fresh hull at the SAME reserved-band wire id) — {@link OwnSeatLifecycleLane#respawnOwn}.
     */
    int ownRespawns;
    /**
     * Join-time variant pick — armed by the RAPP OWN fold
     * ({@code RosterAppendLane.drain}) whenever the server announces the seat's fielded variant;
     * resolved by {@link OwnSeatLifecycleLane#maintainOwnVariantRefield} (alive: re-field NOW if the live own hull's
     * variant differs) or by the next {@link OwnSeatLifecycleLane#respawnOwn} (dead: the registry read fields it).
     * Package: the roster lane sets it. Counters = the kit-sidelog grade row.
     */
    boolean ownRefieldPending;
    int ownRefields;
    /**
     * W13 leg 2c — CLAIM AT PLAY: armed at init when
     * the mission build fielded NO own hull ({@code MatchMissionBuilder.lastBuiltOwnShipId()==0},
     * the zero-hull claim-at-play build) while the WELCOME assigned a real seat; resolved by
     * {@link OwnSeatLifecycleLane#claimOwnSeatAtPose} at the first alive wire sight of the own id. While pending the
     * player rides the engine's placeholder pod ({@code CombatEngine.getPlayerShip()} mints it,
     * owner 0, never in getShips) and {@link OwnSeatLifecycleLane#killOwn} treats own-id absence as "not yet fielded".
     */
    boolean claimPending;
    int claimAttempts;
    /** The placeholder flagship held inert while the claim is pending. */
    final ClaimPlaceholderHold claimHold = new ClaimPlaceholderHold();
    /** The travel-drive fire lock — refuses the own trigger while
     *  the fresh own pose carries the server's {@code FLAG_DRIVE_LOCK} (own file by the new-lane law). */
    final DriveLockLane driveLock = new DriveLockLane();

    /** Rider 7 (R8): the transfer lane's read — an own BEGIN folded under a pending claim has the
     *  placeholder (not F's vacated hull) as its source. */
    boolean claimPending() {
        return claimPending;
    }
    /** Leg 2c: the init wait for the Play dial's WELCOME (ms; knob {@code cc.predict.claimWaitMillis},
     *  default 5000 = the 3 s click-HELLO horizon + backoff; floor 1000). */
    private static final int CLAIM_WAIT_MILLIS =
            Math.max(1000, Integer.getInteger("cc.predict.claimWaitMillis", 5000).intValue());
    /** The standing own-hull invariant's cadence (frames) + its state — see the
     *  advance site beside {@link OwnSeatLifecycleLane#maintainOwnVariantRefield}. */
    private static final int OWN_HULL_INVARIANT_FRAMES = 120;
    private int ownHullInvariantFrames;
    private int ownHullMismatchStreak;
    private int ownHullInvariantArms;
    /** one-shot for the announce-less refit-fork confession ({@link OwnHullForkLaw}). */
    private boolean ownRefitForkConfessed;
    /** Killed registry entries reset UNBOUND on id-reappears-alive ({@link #respawnRemote}). */
    private int remoteRespawns;
    /** One-shot: a respawn edge fired on the 1v1 single-foe lane, which has NO mid-round re-field (recorded residual). */
    private boolean foeRespawnUnsupportedLogged;
    /** EDSP despawn frames CONSUMED off the demuxed stream (net thread writes, render reads). */
    volatile int despawnConsumed;
    /** The server-sent despawn reason for the OWN ship (null until received); net->render. */
    volatile cc.starsector.client.net.ClientDespawnReason ownDespawnReason; // package: OwnMountLifecycleLane adopt clears it
    /** The simTick of the own ship's server-sent despawn (-1 until received); net->render. */
    volatile long ownDespawnTick = -1L; // package-private: FractureFamilyCoordinator reads at mint
    /** MRST frames NOT rebuilt for (bots shape — multi-round is the 1v1 lane), counted plugin-side. */
    volatile int matchResetsIgnored;
    /** Seats the wrap fold skipped for a queued MMNT (summary {@code fold_skipped_mount}). */
    volatile int matchResetFoldSkippedMountSeats;
    /**
     * Client half: the round-boundary MRST the net thread received, awaiting
     * the render-thread rebuild ({@link #applyMatchReset}). Publish-order contract: the net thread
     * writes this strictly AFTER {@link #latestSnapshot} was set to the fresh round's first AWSS (the
     * demux routes MRST inline immediately before that frame), so a render thread that observes this
     * non-null re-reads latestSnapshot and is guaranteed the NEW round's frame — never the dead
     * round's tail. Cleared by the render thread once the rebuild ran.
     */
    private volatile cc.starsector.client.net.ClientMatchReset pendingReset;
    /** MRST boundaries the render thread REBUILT for (the multi-round respawn lane). */
    volatile int matchResetsApplied;
    /** L-R1: a re-admit WELCOME carried a DIFFERENT per-boot serverLife nonce —
     * the server was REBORN (fresh JVM, tick 0, fresh wire_id space), so the whole net world is
     * a stale epoch: replicas would absence-die as fake kills and reused wire_ids would silently
     * REBIND across hull classes. Armed at the re-admit edge (render thread), consumed by
     * the SAME applyMatchReset machinery as an MRST (full mission-init-equivalent rebuild), never
     * hand-enumerated. Same-life blackout re-admits (equal nonce) never arm it — the
     * play-tested glide path stays byte-for-byte untouched. */
    private boolean pendingLifeReset; // render-thread only (armed + consumed there)
    /** L-R1: the serverLife nonce this plugin's net world is built against (0 = unadopted;
     * lazy-adopted from the session once admitted, re-adopted at every re-admit edge). */
    private long boundServerLife;
    /** L-R1: fresh-server-life epoch rebaselines applied (the matchResetsApplied sibling). */
    private volatile int lifeResetsApplied;
    /**
     * wire-true MRST own-respawn: the contract guarantees the frame seen
     * beside a pendingReset is post-reset in STREAM order, but the server is still mid-
     * {@code resetForRedeploy} at receipt, so the first post-MRST frames may not carry the own id
     * yet (the id-absent frame spawned at the side-blind (0,-2000) = a player's
     * "center fly-in"). While a live own-id row is absent the rebuild HOLDS — the old world coasts
     * on pure local prediction and the writer's MRST-gap quiesce covers send pacing — and re-checks
     * every frame; sweep+spawn then run together at the first frame whose wire pose is real.
     * 0 = not holding (armed by {@link #applyMatchReset}, cleared by {@link #finishMatchReset}).
     */
    private long mrstHoldStartMillis;
    /** Frames the current MRST hold has coasted (diagnostic, logged at apply). */
    private int mrstHoldFrames;
    /**
     * Starvation bound: how long the MRST rebuild may hold for a live own-id frame
     * before spawning anyway at the engine-edge fallback ({@code loc=null} — never the old
     * side-blind (0,-2000)). Server redeploy landed ~60 ms after the MRST on the measured runs
     * (154407), so 1500 ms is WAN-jitter headroom while staying well inside transport-liveness
     * windows (the writer skips sends for the whole hold).
     */
    private static final long MRST_OWN_POSE_WAIT_MS = 1_500L;
    /**
     * The net loop ended (clean EOF or fail-closed) — no further rounds can arrive, so the
     * render thread restores the engine's native end-of-combat (one-shot, 1v1 lane only; the bots
     * shape keeps its recorded never-self-ends behavior) and the FINAL round's result flow runs.
     */
    private volatile boolean streamEnded;
    /** One-shot guard for the {@link #streamEnded} setDoNotEndCombat(false) restore. */
    private boolean combatEndRestored;
    /**
     * Scout-faithful spawn. One-shot at
     * round start: the server/scout direct-deploys both Mudskippers AT REST at (±1500,0) facing each other
     * ({@code RealEngineDemoRoster}); but the client's mission-API deploy ({@code addToFleet} with NO
     * positions) makes the engine AUTO-DEPLOY at the default mission zones (~(0,∓3747) on Y) WITH the
     * deployment WARP-IN burn (the measured 690 maxSpeed / 603 u/s vel along Y at tick 0) — so the
     * two engines start in DIFFERENT worlds and the reconcile then fights a 4036 u, wrong-axis gap from frame
     * 1 (a player-felt "desync as soon as i move" + client-local death). {@link FoeStateLaneCoordinator#reconcilePosition} already SNAPS
     * position past {@link PredictKnobConfig#SNAP_DISTANCE}, but the own ship's warp-in VELOCITY + FACING are left to native
     * control, so they persist. This one-shot HARD-SNAPS both ships to the FIRST authoritative pose
     * (loc + vel + facing + angVel) so the client adopts the server's exact initial world, neutralizing the
     * warp-in — with NO hard-coded coords (the server is the single source of geometry). After this one shot
     * the normal input-predicted reconcile resumes.
     */
    boolean initialWorldAdopted; // package: OwnMountLifecycleLane adopt's lever arg

    /** (83) CL-14 — advance-thread twin of the net loop's {@code reAdmitsSeen}: the last re-admit
     * generation folded into the adopt gate. A B4 re-admitted session never hard-resnapped (the
     * latch stayed up across the blackout); observing {@code session.reAdmits()} advance clears
     * {@link #initialWorldAdopted} so the existing one-shot machinery re-adopts off the fresh
     * session's next fresh snapshot (the resumeOwnAuthority no-op
     * inside a repeat adopt is harmless). */
    private long reAdmitsAdoptSeen;

    /** re-admission generation, bumped where the adopt gate observes
     * {@code session.reAdmits()} advance. The epoch and the published view ride
     * UNORDERED channels ({@code session.reAdmits()} vs the {@code latestSnapshot} slot — flight 1
     * armed @407807 vs merger clear @407816), so the advance thread can stamp a PRE-clear view's
     * full roster with the new epoch; {@link RemoteEntity#sightEpoch} is therefore FORENSIC-ONLY
     * (the reconnect-hold log row confesses a raced stamp) — no death lane keys on it. */
    int reAdmitEpoch;
    /** The one-shot re-admission absence-hold: while pending, "own id absent after an
     * established mapping" / "remote entity absent" is the merger's post-clear PARTIAL refill
     * gap, not an authoritative death (roster absence is the wire death contract, and
     * re-admission clears the merger — without this hold the two compose into a synthesized kill
     * on a hull the wire never dropped: the W5-D2 1M-HE corpse + respawn). Inside
     * the hold window absence NEVER adjudicates, on EITHER edge, sight regardless — the
     * "seen-post-clear-then-absent = genuine death" corollary trusted a sight stamp a pre-clear
     * view can fake (see {@link #reAdmitEpoch}); it killed the ALIVE Aurora live. A true death
     * inside the window presents at the bounded expiry — the trade, both edges now.
     * Cleared one-shot by {@link #reAdmitAbsenceHoldActive} at the expiry (pumped every frame —
     * see the arm site) and at {@code finishMatchReset} (a round rebuild moots the refill). */
    boolean reAdmitAbsenceHoldPending;
    /** Hold clock start (-1 = not yet started): the first published simTick STRICTLY
     * NEWER than {@link #reAdmitAbsenceHoldArmTick}, i.e. the first provably post-clear view —
     * the pre-clear stale view re-presents its old tick and can never start (or expire) the
     * clock, and a frozen stream never advances it, so a dead feed cannot expire the hold into a
     * synthesized death — session-death machinery owns that path. */
    long reAdmitAbsenceHoldFromTick = -1L;
    /** The simTick of the view in hand when the adopt gate armed the hold (possibly the
     * PRE-clear stale view's — the fresh session's ticks are strictly newer than it either way,
     * blackout-advanced). Gates the clock start above. */
    long reAdmitAbsenceHoldArmTick = Long.MIN_VALUE;
    /** true only on the frame whose per-frame pump consult expired the hold: the own
     * death edge adjudicates a still-absent own row on exactly this transition (the disarmed
     * edge has no other wake-up once the pump owns the expiry). */
    private boolean reAdmitHoldExpiredThisFrame;
    /** Hold bound in sim ticks (~3 s @60 Hz): many full refill cycles above the AWSD
     * keyframe cadence ({@code cc.net.snapKeyframeTicks}, default 15) and the measured W5-D2
     * refill gap (~4 ticks). A true blackout death presents at most this late — never masked. */
    static final long RE_ADMIT_ABSENCE_HOLD_TICKS = 180L;
    /** capped-log budget for remote-side hold skips (reset at each arm). */
    int reAdmitHoldRemoteSkipLogs;

    /**
     * One-shot: the own flagship's explicit {@code setCaptain} stats re-derive
     * (+ the ASK-3 member display twin) — round-local like {@link #initialWorldAdopted};
     * respawnOwn calls the lever directly instead (a respawn is not a round reset).
     */
    private boolean flagshipLeverApplied;

    /**
     * Recut: one-shot SETTLED lever re-fire deadline (0 = disarmed), armed ~2 s after
     * either lever lane fires. Self-heals any adopt-window stats replacement (the settled
     * "175 client / 185 server" residue is UNMEASURED post-adoption) and logs the settled
     * engine-derived speeds — the watch proved every lever line sampled fly-in
     * transients. Round reset disarms it; the fresh round's own t=0 lever re-arms.
     */
    long leverSettledRefireAtMillis; // package: OwnMountLifecycleLane adopt arms it

    /** The settled re-fire margin past quiescence (covers the ~0.1 s t=0 adopt window), well
     * under a life. The RESPAWN arming adds {@code OWN_SPAWN_FLYIN_BURN_SEC} on top — this
     * margin alone raced the 4 s fly-in burn and sampled travel-drive speed. */
    static final long LEVER_SETTLED_REFIRE_DELAY_MILLIS = 2_000L; // package: OwnMountLifecycleLane adopt

    // === Remote-entity registry (descriptor shapes bots:* — and, since a later
    // extension, seats:K + gauntlet:N). Engaged once in init() off MatchMissionBuilder.cachedDescriptor();
    // when registryShape is false every field below stays dormant and the certified 1v1 single-foe
    // path above runs byte-identical (the untouched-path proof). Server id contract (
    // generalized): entity ids are DENSE 1..N in descriptor roster build order × count
    // (bots: seat=1, side-0 allies 2..A+1, side-1 enemies A+2..; seats:/gauntlet:: seat i = ship i). ===
    private boolean botsShape;
    /** A seats:/gauntlet: descriptor — registry-driven like bots, but all rows are
     *  SEAT rows (a hull is ABSENT until claim⇒fly-in), so first sight can arrive mid-round. */
    private boolean npeerShape;
    /** botsShape || npeerShape — the registry-vs-single-foe routing gate (D5). */
    boolean registryShape; // package: MissilePoseLane resolves presence-adopt sources per shape
    /** TRUE-VANILLA: a vanilla: compare-instrument window (never served). The
     *  serverless sim-touching lanes — engine-global timeMult scrub, servo mult apply, shared-time
     *  pause guard — are SILENCED so the window behaves 100% stock (a recorded run's vanilla half ran a
     *  feed-less servo ×0.87 AND stripped the engine's own phase-cloak time dilation). Meters, the
     *  CR census and HUD readouts stay — they read, never write. */
    boolean vanillaWindow; // package-private: ClockServoLane reads it (K20 extraction seam)
    /** The OWN team's ABSOLUTE wire side; registry sides are RELATIVE to it. SEEDED by the
     *  descriptor (dense-walk row side of session shipId) and LIVE
     *  ({@code /switchteams}): {@link #applyTeamChange} re-keys it from the own row's wire
     *  {@code fleetSide} each frame (the server-settable value). */
    int ownDescSide; // package: RosterAppendLane / SideTotalsOverrideLane read it
    /** CR parity: the match descriptor's {@code rules.deployCr} — the CR every
     *  client-local spawn/adopt applies (server twin: {@code chooseDeployCr} floors at it). Registry
     *  else-branch spawns and the station adopt seams took the engine mission default (0.7; station
     *  0.5) while the server deploys at descriptor CR. 0.7 = the certified pre-knob default. */
    float descriptorDeployCr = 0.7f; // package: StationAdoptBindLane applies it at both adopt seams
    /** Fixed primary-foe wire id (lowest registered id on the other side) — the HUD/witness/parity anchor. */
    private long primaryFoeId = -1L;
    /** Dense id-contract walk result: wire entityId -> descriptor variant (D5 expectedVariantFor generalization). */
    // package-private: the station lane adds/removes ROUND-LOCAL module
    // variant rows here (the RAPP append precedent), keyed by the adopted module wire ids.
    final java.util.Map<Long, String> variantById = new java.util.TreeMap<Long, String>();
    /** One-shot latch for the defect-3 auto-unpause (see BOTS_AUTO_UNPAUSE). */
    private boolean botsUnpauseDone;
    /** One-shot log latch for the R9 shared-time guard (see SHARED_TIME_GUARD); the guard itself is continuous. */
    private boolean sharedTimeGuardLogged;
    /** Expected wire entityId -> registry entry, ascending (every non-own id gets an entry up front). */
    final java.util.Map<Long, RemoteEntity> remoteEntities = new java.util.TreeMap<Long, RemoteEntity>();
    /** Entries flagged by the fighter kill lane, awaiting the next-frame reap
     *  sweep (render-thread only; the kill fires INSIDE the registry walk, so removal is deferred
     *  to a sweep that owns its own iterator). Drained by
     *  {@link FoeStateLaneCoordinator#reapFlaggedRemotes}; cleared at match reset. */
    final java.util.List<RemoteEntity> remoteReapQueue = new java.util.ArrayList<RemoteEntity>();
    /** Cumulative reaped-entry count (census {@code reaped=} field). */
    int remoteReaped;
    /** Entries still missing a local ship — bindRemoteEntities retries per frame while > 0 (never unbinds). */
    // package-private: StationAdoptBindLane binds a station BASE by
    // adopting an engine-minted body (a bind the spawn-on-sight gate never performs) and mints
    // round-local module rows, so it owns the matching census arithmetic.
    int remoteUnbound;
    boolean remoteBindCompleteLogged; // package: RosterAppendLane appends re-arm the complete log
    /** RAPP roster-append intake/fold + the /ship seat variant
     *  re-point law (bite-#1 extraction — the lane owns queue, counters and row law). */
    final RosterAppendLane rapp = new RosterAppendLane(this);
    // VDEF variant-definition fold lane — drained just AHEAD of rapp so a
    // server-delivered variant is engine-registry-resolvable before any RAPP row names it.
    final VariantDefinitionLane vdef = new VariantDefinitionLane(this);
    // W15 v0: VACK custom-variant receipt lane — drained beside vdef; toasts the
    // server's verdict on this session's ONE submission into the chat log.
    final VariantReceiptLane vack = new VariantReceiptLane(this);
    /** net-thread intake of the EDSP reason per REMOTE entity, keyed by wire id
     *  (render-thread remove-take in {@link FoeStateLaneCoordinator#killRemote}; the FFC despawnTickByEntity lock idiom).
     *  Round-local (cleared at finishMatchReset). The own-ship reason keeps its dedicated fields. */
    final java.util.HashMap<Long, cc.starsector.client.net.ClientDespawnReason>
            despawnReasonByEntity =
                    new java.util.HashMap<Long, cc.starsector.client.net.ClientDespawnReason>();
    /** Max net ticks a fighter kill waits for its trailing EDSP reason. */
    static final long FIGHTER_REASON_HOLD_TICKS = 3L;
    /** mount-lifecycle re-key pose hold, in sim ticks. MMNT rides CTRL and fans
     *  pre-advance, so at a mount re-point (seat id: old hull retired, module pose pending) or a
     *  dismount re-key (fresh module id minted, first pose pending) the row's id is structurally
     *  absent from snapshots for ~1 tick — the absence-kill in that gap blows a LIVE body
     *  (one incident: the just-adopted citadel replica died at hpB=15000 and the twin
     *  mint followed). Bounded so a real edge-death still presents (fighter-hold idiom). */
    static final long MOUNT_REKEY_POSE_HOLD_TICKS = 10L;
    /** Despawn/absence hold, in sim ticks, for an id the in-flight
     *  command transfer names as its adopt target ({@link CommandTransferLane#isPendingAdoptTarget}):
     *  a COMMIT re-key retires the target id as a same-tick DEPARTED, and the own adopt can sit in
     *  its bounded landing-swap wait (≤{@link CommandTransferLane#SWAP_FORCE_MILLIS} = 5 s) before
     *  consuming the body — so the hold must OUTLAST that wait (360 t = 6 s at 60 Hz), else the
     *  kill lane ejects the live body mid-wait and re-mints the E3 @170416 false wreck-adopt. */
    static final long TRANSFER_ADOPT_POSE_HOLD_TICKS = 360L;
    /** The fighter-recall FX lane (NEW-LANE LAW collaborator; knob
     *  {@code cc.predict.recallFx}). Enroll relay = {@link FoeStateLaneCoordinator#killFighterRemote}. */
    final RecallFxLane recallFx = new RecallFxLane();
    /** The phase-anchor emergency-dive presentation lane (NEW-LANE LAW
     *  collaborator; knob {@code cc.predict.phaseDive}). Enroll relay = {@link FoeStateLaneCoordinator#drainPhaseDives};
     *  departure takes = {@link FoeStateLaneCoordinator#killRemote}/{@link OwnSeatLifecycleLane#killOwn}. */
    final PhaseDivePresentLane phaseDive = new PhaseDivePresentLane();
    /** The Threat fabrication-reveal presentation lane (NEW-LANE LAW collaborator;
     *  knob {@code cc.predict.constructionFx}). Enroll relay = the growth bind after
     *  {@link #neutralizeRemoteBody} in {@code spawnRemoteEntities}; END = wire truth (carrier despawn). */
    final ConstructionPresentLane constructionFx = new ConstructionPresentLane();
    /** The reclaimed-hulk dissolve presentation lane. */
    final ReclamationPresentLane reclamationFx = new ReclamationPresentLane();
    /** The overseer energy-lash phase glow on the lashed swarm replica. */
    final EnergyLashPresentLane energyLashFx = new EnergyLashPresentLane();
    /** Schema v12: the remote Ziggurat's mote-attractor targeting arc + lock jitter. */
    final MoteAttractorFxLane moteAttractorFx = new MoteAttractorFxLane();
    /** The own body's LOCAL mote-generator census (1 Hz sidelog rows). */
    final OwnMoteCensusLane ownMoteCensus = new OwnMoteCensusLane();
    /** The Shrouded Dweller census (1 Hz sidelog rows; always-on so the vanilla twin measures with the same code). */
    final DwellerCensusLane dwellerCensus = new DwellerCensusLane();
    /** replicas-only motes — the vanilla local generator pinned off, replicas listed on the shared data + launch FX. */
    final MoteReplicaLane moteReplica = new MoteReplicaLane();
    /** The /switchteams per-frame team-perspective pass (own team from the live own
     *  row; registry sides + bound bodies re-sided in ONE frame) — the lane's javadoc is the WHY. */
    final TeamChangeLane teamChange = new TeamChangeLane(this);
    /** Diagnostic: one-shot latch for the formerly-silent getPlayerShip()==null early-return. */
    private boolean remoteNoAnchorLogged;
    private boolean remoteCensusLogged;
    // Census fix: frames stepped after the feed ended (running=false) — the render loop keeps
    // stepping headless (doNotEndCombat), so the settled census is emitted a few frames AFTER stream
    // end, once the tail snapshots (final kills) have been consumed.
    private int postFeedFrames;
    private boolean postFeedCensusLogged;
    private static final int POST_FEED_CENSUS_FRAMES = 10;

    /** One wire entity the registry presents locally: bound ship + per-entity death/cap state. */
    static final class RemoteEntity {
        final long entityId;
        /** 0 = own side (ally), 1 = enemy — RELATIVE to {@link #ownDescSide}. MUTABLE since
         *  {@code /switchteams}: {@link #applyTeamChange} re-derives it every frame
         *  from the row's live wire {@code fleetSide}, so a switched seat (own or remote) and a
         *  switched reel re-side in place; before that it was final + sticky from the
         *  descriptor / first append (a RAPP repoint never re-read the side byte). */
        int side;
        ShipAPI ship;   // null until bound; never unbound after
        boolean killed;
        /** Sim tick {@link #killed} latched (MIN = alive / re-life).
         * {@link ServerDealtSourceLaw} keys on the kill TICK. */
        long deathTick = Long.MIN_VALUE;
        /** FRESH merged snapshot frames
         *  (simTick advanced since the last frame) whose spawn-on-sight pass found this entry bodiless
         *  with no pose on the wire — never a render pass against a stale snapshot, so a snapshot gap
         *  cannot age a live entry caught between its append row and its first pose: the
         *  dead-before-sight signal {@link RosterAppendLane#DEAD_BEFORE_SIGHT_FRAMES} grades when a
         *  fresh appended id meets the entity cap. Cumulative for the entry object (a bind never
         *  resets it). */
        int awaitFrames;
        /** Per-entity axis-B cap (mirrors {@link #capFoeDamage}); cleared by killRemote for axis C. */
        boolean capActive = true;
        /** The body meter (a) already counted as a premature
         *  local kill — one count per body (a corpse takes more hits at hull 0; a recorded flight's
         *  reel shows kills=2), re-armed by identity when a respawn binds a fresh body. */
        ShipAPI prematureKillBody;
        /** this replica's derived authoritative system-window state (latest ON/OFF edge); null until seen. */
        Boolean sysServerOpen;
        /** The window KEY = the last applied ON edge's simTick; -1 = none (see FoeSystemWindowLaw.Window). */
        long sysOpenTick = FoeSystemWindowLaw.NO_WINDOW;
        /** one-shot mid-window system seed consumed (see {@link FoeStateLaneCoordinator#seedFoeSystemState}). */
        boolean sysSeeded;
        /** this replica's teleport-jump lane state (D1 class
         *  latch, D6 fence, D3 snap-only window, D4 heal debt); policy + meters live in
         *  {@link TeleportJumpLane}. */
        final TeleportJumpLane.JumpState jump = new TeleportJumpLane.JumpState();
        /** drone-replica collision gate, latched at mint (hull designation
         *  "Drone"): the engine's drone&lt;-&gt;mothership narrowphase exemption rides
         *  {@code getAI() instanceof DroneAI} (no setter), which the
         *  InertShipAI treatment can never satisfy — an ungated drone replica is born colliding
         *  INSIDE its station and grinds on it while armor has no reconcile lane. The gate holds
         *  {@code CollisionClass.NONE} near any station complex, {@code SHIP} in the open. */
        boolean droneGate;
        /** Current gate state: true = {@code CollisionClass.NONE} applied (the mint state). */
        boolean droneIntangible;
        /** Transition-line budget (flap guard; state keeps switching past the cap). */
        int droneGateLogs;
        /** Unconditional raw hull ledger (created/seeded lazily at first maintain;
         *  nulled at the MRST sweep / respawn / finishMatchReset so re-lives reseed from wire only). */
        FoeHullReconcileLane.HullLedger ledger;
        /** Fighter reason-hold start tick (MIN_VALUE = not holding): the EDSP
         *  reason can trail the wire absence by 1-2 net ticks, so a fighter's kill waits
         *  ≤{@link #FIGHTER_REASON_HOLD_TICKS} for it before the DESTROYED-lightweight fallback.
         *  Non-fighters never hold. */
        long reasonHoldSinceTick = Long.MIN_VALUE;
        /** Reap-on-kill: the sim tick at which
         *  {@link FoeStateLaneCoordinator#killFighterRemote} flagged this entry for REMOVAL from
         *  {@link #remoteEntities} (MIN_VALUE = live / never). A fighter's engine body is
         *  instant-removed at death and its growth wire id is never re-issued, so the entry has
         *  no future — yet before this reap it stayed forever (858 dead rows by the end of one
         *  flight, walked every render frame at ~17 sites = the client frame overruns).
         *  The sweep ({@link FoeStateLaneCoordinator#reapFlaggedRemotes}) runs at the TOP of
         *  the next frame's maintain, so every lane sees one full frame of {@code killed=true}
         *  first. Non-fighters (hulk-capable, same-id remount) are never flagged. */
        long reapTick = Long.MIN_VALUE;
        /** Armed at a mount-lifecycle re-key edge ({@link ModuleMountLane}
         *  mount re-point / dismount fresh-row mint): the row's FIRST post-re-key pose is still
         *  in flight, so wire absence is the re-key gap, not death. Disarmed by the first alive
         *  pose; the kill lane holds ≤{@link #MOUNT_REKEY_POSE_HOLD_TICKS} then falls through
         *  loud ({@link FoeStateLaneCoordinator#killRemote}). */
        boolean rekeyPoseHold;
        /** First absence-sighting tick while {@link #rekeyPoseHold} (MIN_VALUE = none yet). */
        long rekeyHoldSinceTick = Long.MIN_VALUE;
        /** First despawn/absence-sighting tick while this id is a
         *  pending transfer adopt target (MIN_VALUE = not holding); the kill lane holds
         *  ≤{@link #TRANSFER_ADOPT_POSE_HOLD_TICKS} then falls through loud
         *  ({@link FoeStateLaneCoordinator#killRemote}). */
        long adoptHoldSinceTick = Long.MIN_VALUE;
        /** Armed at a REMOTE seat's DISMOUNT apply ({@link ModuleMountLane}):
         *  the dismount CTRL drains ahead of the same tick's snapshot, so the seat row's RETIRING
         *  module life still shows one alive row there (module variant, slot pose) — adopting it
         *  re-fields the ride as a free body, and the life's trailing {@code DEPARTED} despawn
         *  then hulks that mint (the observer-seen corpse). Spawn-on-
         *  sight holds until the vacate is REFLECTED (row absent once — the own side's
         *  gap idiom), then re-arms; ≤{@link #MOUNT_REKEY_POSE_HOLD_TICKS} then falls through
         *  loud (an INSTANT respawnPolicy has no absence gap — same recorded limitation). */
        boolean dismountDepartHold;
        /** First held-row-sighting tick while {@link #dismountDepartHold} (MIN_VALUE = none yet). */
        long dismountDepartHoldSinceTick = Long.MIN_VALUE;
        /** The re-admission epoch this entity was last wire-seen ALIVE
         *  under (0 = the initial admission; {@link PredictReconcileCombatPlugin#reAdmitEpoch}).
         *  FORENSIC-ONLY: a pre-clear stale view consumed after the epoch
         *  bump can stamp the dead session's roster with the new epoch, so the kill lane holds
         *  on the hold window alone; the reconnect-hold log row prints this to confess a raced
         *  stamp ({@code sightEpoch==epoch} while held). */
        int sightEpoch;
        /** RAPP-appended mid-round wave hull: minted via the factory twin,
         *  REMOVED (not reset) by the MRST sweep (waves are round-local). */
        final boolean appended;
        /** Wire id reappeared ALIVE after a kill (the server REMOUNT lane,
         *  {@code spawnFleetMember(..., FLY_IN_BURN_DUR)}): the re-mint arms the
         *  {@link #replicaFlyinBurn} parity burn. Drone/fighter replicas are never marked
         *  HERE — they relaunch from a carrier, no server fly-in — but hangar births DO
         *  arrive {@link #appended} (growth-lane RAPP rows), so the fighter burn exclusion
         *  lives in {@link #replicaFlyinBurn} itself.
         *  ALSO armed for every SEAT row by {@link #applyMatchReset}
         *  (the gated reset's {@code midDeploySeat} fly-in), and CONSUMED at the
         *  mint that spends it — the burn is a per-mint event, never a standing property. */
        boolean remounted;
        /** A STATION MODULE row minted by {@link StationAdoptBindLane}: it is
         *  BOUND at mint (adopted onto an engine-minted child body, never spawned), so
         *  {@link #spawnRemoteEntities} must skip it, and it is NEVER pose-reconciled
         *  ({@code CombatEngine.updateStationModuleLocations} re-derives loc+facing from the parent
         *  every frame — the correction rides the BASE). Round-local: the lane's resetRound drops it. */
        final boolean stationModule;
        /** RAPP row payload consumed at the appended mint ({@code null}/0 on descriptor entries). */
        final String appendShipName;
        final int appendCrPerMille;
        /** RAPP v2: the carrier wire id of a bay-born fighter ({@code 0} = none) +
         *  its bay index ({@code 0xFF} = none) — the {@code FighterWingHudLane} enrolls the
         *  bound replica into a fleet-manager wing sourced from that carrier+bay. */
        final long appendCarrierWireId;
        final int appendBayIndex;
        /** RAPP v3: the captain the SERVER attached to this hull (null = bare) —
         *  {@link #spawnReplicaMemberFirst} attaches exactly this spec ahead of the variant-keyed
         *  table lookup, so the twin's damage-taken stats match the server's. */
        final cc.starsector.server.match.OfficerRosterCodec.OfficerSpec appendOfficer;
        /** RAPP v5 flags bit0: the SERVER twin was placed
         *  AT its pose with {@code burnDur 0} (posed adhoc / pod / posed wave; no travel drive), so
         *  the {@link #replicaFlyinBurn} parity burn is the ANTI-parity here: a client-only drive
         *  that outruns the server body and refuses {@code USE_SYSTEM} (the recorded
         *  jets-refused class). Consumed only on the appended, non-remounted mint
         *  ({@link #flyinBurnFor}); false on descriptor / station-module rows. */
        final boolean appendAtRest;
        /** RAPP v5 flags bit1: the captain the SERVER restored on
         *  this hull IS its all-skills seat mint (a vacated transfer source), so {@link #spawnReplicaMemberFirst}
         *  mints the seat-shaped all-skills captain when this kit's all-skills match flag is ON too, and the
         *  bare captain plus one loud line when it is OFF ({@link #appendedCaptainArm}). Never set with
         *  {@link #appendOfficer} (the row refuses that pair); false on descriptor / station-module rows. */
        final boolean appendAllSkillsCaptain;

        RemoteEntity(long entityId, int side) {
            this(entityId, side, false, null, 0, false, 0L, 0xFF, null, false, false);
        }

        /** The pre-v5 RAPP append shape ({@code appendAtRest=false}) — the selftest callers. */
        RemoteEntity(long entityId, int side, String appendShipName, int appendCrPerMille,
                long appendCarrierWireId, int appendBayIndex,
                cc.starsector.server.match.OfficerRosterCodec.OfficerSpec appendOfficer) {
            this(entityId, side, appendShipName, appendCrPerMille, appendCarrierWireId,
                    appendBayIndex, appendOfficer, false);
        }

        /** The RAPP append shape (v2 carrier+bay tail; v3 officer tail;
         *  v5 at-rest bit). */
        RemoteEntity(long entityId, int side, String appendShipName, int appendCrPerMille,
                long appendCarrierWireId, int appendBayIndex,
                cc.starsector.server.match.OfficerRosterCodec.OfficerSpec appendOfficer,
                boolean appendAtRest) {
            this(entityId, side, appendShipName, appendCrPerMille, appendCarrierWireId,
                    appendBayIndex, appendOfficer, appendAtRest, false);
        }

        /** The RAPP append shape with the v5 all-skills bit — the
         *  roster-append lane's mint. */
        RemoteEntity(long entityId, int side, String appendShipName, int appendCrPerMille,
                long appendCarrierWireId, int appendBayIndex,
                cc.starsector.server.match.OfficerRosterCodec.OfficerSpec appendOfficer,
                boolean appendAtRest, boolean appendAllSkillsCaptain) {
            this(entityId, side, true, appendShipName, appendCrPerMille, false,
                    appendCarrierWireId, appendBayIndex, appendOfficer, appendAtRest,
                    appendAllSkillsCaptain);
        }

        /** The pre-bit full shape (the station-module mint): no all-skills bit. */
        private RemoteEntity(long entityId, int side, boolean appended, String appendShipName,
                int appendCrPerMille, boolean stationModule, long appendCarrierWireId,
                int appendBayIndex,
                cc.starsector.server.match.OfficerRosterCodec.OfficerSpec appendOfficer,
                boolean appendAtRest) {
            this(entityId, side, appended, appendShipName, appendCrPerMille, stationModule,
                    appendCarrierWireId, appendBayIndex, appendOfficer, appendAtRest, false);
        }

        private RemoteEntity(long entityId, int side, boolean appended, String appendShipName,
                int appendCrPerMille, boolean stationModule, long appendCarrierWireId,
                int appendBayIndex,
                cc.starsector.server.match.OfficerRosterCodec.OfficerSpec appendOfficer,
                boolean appendAtRest, boolean appendAllSkillsCaptain) {
            this.entityId = entityId;
            this.side = side;
            this.appended = appended;
            this.appendShipName = appendShipName;
            this.appendCrPerMille = appendCrPerMille;
            this.stationModule = stationModule;
            this.appendCarrierWireId = appendCarrierWireId;
            this.appendBayIndex = appendBayIndex;
            this.appendOfficer = appendOfficer;
            this.appendAtRest = appendAtRest;
            this.appendAllSkillsCaptain = appendAllSkillsCaptain;
        }
    }

    // === Synthetic-seat intent source (see INTENT_SOURCE). Loaded in init (before the
    // net thread starts), then net-thread-only: one recorded UP frame is consumed per exchange into
    // the four input fields above. The render-thread pollers are idled while active. ===
    private List<PilotIntentFrame> intentSourceFrames;
    private int intentSourceCursor;
    private int intentSourceReplayed;
    private int intentSourceNeutral;
    private boolean intentSourceExhaustLogged;

    // === Unified input-lane (allowlist-clean re-capture, ⭐⭐): own-ship native-control movement
    // CAPTURE via the PUBLIC ShipEngineControllerAPI booleans (replaces the FALSIFIED reflective command-ordinal
    // capture: the java.lang.reflect.* script-classloader-B blocklist denied that client path at frame
    // 1 — SecurityException reading Ship.getCommands() and the command enum's fields, so heldControls stayed 0 and the own ship
    // never moved server-side). Each frame we read the 7 engine-controller booleans the engine's OWN native
    // player control sets (isAccelerating/...isStrafingRight) into a FIXED-ORDER SEMANTIC bitmask. The wire
    // byte-format is UNCHANGED (heldControls stays an int); only the bit SEMANTICS shift from "engine command
    // ordinal" to "stable semantic index". The server maps each set bit through the PUBLIC api
    // `ShipCommand` (a plain-name getField on the
    // plain-named api jar) -> internal giveCommand (the server is NOT under classloader-B, so its
    // name->enum reflection is free), so both sides drive the engine's OWN command lane -> predictor==authority
    // by construction and the reconcile idles to ~0. ===
    static final int BIT_ACCELERATE           = 0;
    static final int BIT_ACCELERATE_BACKWARDS = 1;
    static final int BIT_DECELERATE           = 2;
    static final int BIT_TURN_LEFT            = 3;
    static final int BIT_TURN_RIGHT           = 4;
    static final int BIT_STRAFE_LEFT          = 5;
    static final int BIT_STRAFE_RIGHT         = 6;
    // The ship-system WINDOW bit (bit 7, == PilotIntentFrame.USE_SHIP_SYSTEM),
    // carried ABOVE the 7 movement-ordinal bits so heldControls stays byte-identical on the wire. Set while the
    // native-flown own ship's system isOn() (= IN|ACTIVE exactly; was isActive() = IN|ACTIVE|OUT,
    // which held the bit through the whole OUT chargedown so a double-tap toggle CANCEL never reached the server
    // until OUT-exit). The rising edge still lands at chargeup (IN) start (the ACTIVE-only
    // isStateActive() dropped the 1 s chargeup and put the server ~1 s behind); the server mirrors BOTH edges via
    // the engine's faithful ShipAPI.useSystem() (activation press on high / cancel press while low + server isOn).
    private static final int BIT_USE_SYSTEM           = 7;
    // The bit-7 EDGE HOLD-STRETCH: a sub-tick-active system (orion
    // active=0.001 s) makes isOn() a 1-render-frame pulse, and the server's per-tick latest-wins
    // deposit slot only conflation-saves it via the server's OR-latch when the datagram ARRIVES; the
    // measured live residual is the LATENCY cd-phase refusal (local cooldown epoch leads the
    // server's by one-way delay, so a re-press at local-ready meets server cd≈0.04 ≈ 2.4t and is
    // refused honestly). Fix: past each local rising edge hold bit-7 HIGH ≥4 sim ticks (~67 ms,
    // 60 Hz fixed step), dt-ACCUMULATED (never render-frame-counted — fps-robust; the retired
    // EDGE_BIT_MIN_HOLD_FRAMES=5 producer frames ≈ 9 ms ≈ 0.6t at 540 fps covered nothing). The
    // ingest OR-latch + pristine re-stage fold each tick's window, so the held span is a
    // per-tick re-press server-side, converting the refusal into a ≤3t-late accept — the engine's
    // own held-key idiom, faithfulness-RESTORING. NON-TOGGLE systems ONLY (spec.isToggle() gate):
    // a toggle's double-tap cancel IS the bit-DROP timing, and stretching it would delay
    // uncloak-class cancels by up to the stretch; a non-toggle press while IN/ACTIVE is a true
    // engine no-op (chargeTracker reads the press only at IDLE), so the
    // stretched tail cannot mis-fire anything.
    private static final float SYS_EDGE_MIN_HOLD_SECONDS = 4f / 60f;
    // The flux-VENT bit (bit 8, == PilotIntentFrame.VENT_FLUX), same precedent as bit 7
    // (heldControls was always a 4-byte wire int — no codec change). Set while the native-flown own ship is
    // VENTING (getFluxTracker().isVenting() — the engine's own accepted vent, so a V press refused while
    // overloaded is never carried); the server replays the rising edge via the engine's own
    // giveCommand(ShipCommand.VENT_FLUX) so the authoritative flux drops by the real vent mechanism.
    private static final int BIT_VENT_FLUX            = 8;
    // PINT v5: the own-ship RETREAT bit (bit 9, == PilotIntentFrame.RETREATING), same precedent as
    // bits 7/8 (a new SET of valid heldControls values — no codec change). Set while the native-flown own ship
    // isRetreating() — the engine's OWN accepted retreat (vanilla's unpaused-tap + reachability gate ran in THIS
    // local engine before the bit could rise; a falling edge mirrors the engine's own vent auto-cancel, the only
    // organic clear). The server drives its authoritative hull's travel drive on the edges (drive-off before clear).
    private static final int BIT_RETREATING           = 9;
    // The own-ship SHIELD_ON bit (bit 10, == PilotIntentFrame.SHIELD_ON), same precedent as
    // bits 7/8/9 (a new SET of valid heldControls values — no codec change). Set while the native-flown own
    // ship's shield isOn() — the engine's OWN accepted shield state (overload lockout, the 0.2s buffered
    // raise, and AUTO_OMNI assist all ran in THIS local engine before the bit could rise). The server twin
    // presses the verb under the unified state-gated law — raise only at idle, lower only
    // while on (EVERY shield type flips desired per command-present frame — a FRONT-pinned tracker);
    // phase-cloak hulls are guarded server-side (same verb toggles the cloak there).
    private static final int BIT_SHIELD_ON            = 10;
    // The own-ship PHASE-CLOAK bit (bit 11, == PilotIntentFrame.PHASE_CLOAK), same
    // precedent as bits 7/8/9/10 (a new SET of valid heldControls values — no codec change, no PINT bump:
    // v5 bumped for FIELDS, not bits). Set while the native-flown own ship's phase cloak isOn() — the
    // engine's OWN accepted cloak state (IN|ACTIVE exactly, flips at toggle-acceptance; NOT isPhased,
    // which lags isOn <=0.25s through the OUT chargedown and would double-press down edges — the foe-cloak
    // lane's proven operand). The server mirrors via mismatch-edge giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK)
    // (applyCloakBit; shield hulls are guarded the other way — the D-M2-4 verb partition, both directions).
    private static final int BIT_PHASE_CLOAK          = 11;
    /** PilotIntentFrame.PULL_BACK_FIGHTERS state-mirror — own.isPullBackFighters(), the Z-key carrier fighter mode (regroup=true). */
    private static final int BIT_PULL_BACK_FIGHTERS   = 12;
    // Capture counters (the unified lane is long-proven LIVE; the Keyboard-polling FAVORABLE/UNFAVORABLE
    // verdict was pruned with that closed arc). The teardown summary keeps these cheap greppable counts —
    // systemFrames/ventFrames corroborate the window-edge meters.
    private int capObservedFrames;       // frames we read the engine-controller booleans
    private int capMovementFrames;       // frames we captured >=1 MOVEMENT bit
    private int capSystemFrames;         // frames the own ship's system was engaged (bit 7 set UP)
    private int capSystemStretchFrames;  // Frames bit 7 rode UP on the hold-stretch ALONE (raw sample low)
    private int capVentFrames;           // frames the own ship was venting (bit 8 set UP)
    private int capRetreatFrames;        // Frames the own ship was retreating (bit 9 set UP)
    private int capShieldFrames;         // Frames the own ship's shield was ON (bit 10 set UP)
    private int capCloakFrames;          // frames the own ship's phase cloak was ON (bit 11 set UP)

    // GAP 2 discharge instrument — READ-ONLY measurement that the over-fire is GONE now the
    // engine's own weapon control drives the own ship (no per-frame presentFire). Counts DISTINCT local
    // own-ship projectiles spawned vs the frames the human HELD the trigger; the server baseline is
    // ~5 real discharges / 149 firing frames. With native firing this should track the
    // engine's real cadence (NOT >> the baseline), which is the GAP 2 pass check.
    private final Set<Object> seenOwnProj = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    private int localOwnDischarges;
    // own-source damagers with NO weapon — the engine's own explosion entities
    // (flak/devastator proximity bursts, the own death explosion), excluded from discharge accounting.
    private int ownNonWeaponDamagers;
    private int fireHeldFrames;
    int advFrames;

    // === Own-ship true-speed readout (gap ③) + gap-② divergence instrument. The own
    // ship's authoritative |velX,velY| is ALREADY decoded into its AWSS pose (previously unused for the own
    // ship — only the foe consumed velocity, to dead-reckon). We surface it on the player HUD beside the
    // LOCAL-predicted speed (gap ③: the true server-limited speed was otherwise unknowable)
    // and record the running max |local-server| delta to the WATCH log — a persistent delta MEASURES gap ②
    // (a server-unmodelled speed mechanic) instead of guessing it. Render-thread only; no wire/codec change. ===
    float lastLocalSpeed;
    float lastServerSpeed;
    float maxSpeedDeltaSeen;
    // Owner de-flicker ORDER: the HUD speed lines dead-band their shown integer
    // so a signal astride an x.5 rounding edge cannot flip 174/175 every frame. ALL logic lives in
    // SpeedReadoutDeadband (own file — growth-anchor law); raw speeds still feed the log + highlight.
    final SpeedReadoutDeadband speedDeflicker = new SpeedReadoutDeadband();
    // Own-pose-UNDER-FIRE instrument (report-only):
    // the corr.-10 ~140 u/s under-fire velocity gap has NO owning corrector BY DESIGN (glide
    // MAX_GAP refuses, gate closed under thrust, STEP-16 keys auth-side steps, snap position-only)
    // — this measures how big it actually gets while being shot. Latch = local own hull hp
    // DECREASED within the last UNDER_FIRE_LATCH_TICKS sim ticks (decaying); gap = |local vel −
    // auth vel| VECTOR magnitude, sampled per fresh own-pose frame in maintainSpeedReadout.
    static final long UNDER_FIRE_LATCH_TICKS = 120; // ≈2 s at the 60 Hz fixed step
    float lastOwnHullHp = Float.NaN;                // NaN until first sample (no false latch)
    long underFireUntilTick = Long.MIN_VALUE;       // latch expiry on the sim-tick clock
    float underFireVelGapMax;
    // Own-BURST instrument (report-only): the
    // under-fire latch is blind to un-shot system bursts, so this sibling latches on the OWN ship
    // system being locally active (plasmajets on the aurora seat; system-ID-generic per DD2) and
    // holds BurstWindowLaw.TAIL_TICKS past the last active sample — jets isActive() spans IN+ACTIVE+OUT
    // and the post-ACTIVE overspeed decays into cooldown, so the tail covers the decay window.
    // Latch advances on fresh frames BEFORE maintainStepRebase (so rebase counting sees the same
    // frame's window); gap = |local − auth| velocity VECTOR magnitude sampled like the under-fire
    // twin; STEP-16 rebases landing inside a live window are counted separately. No writes.
    // PROMOTION: the latch is no longer report-only — it is the burst-window
    // exemption's window authority (BURST_EXEMPT_ENABLED). Arithmetic lives in BurstWindowLaw
    // (pure, offline-replayable); the tail constant moved there unchanged (120 ≈ 2 s).
    long burstUntilTick = Long.MIN_VALUE;           // latch expiry on the sim-tick clock
    private boolean burstLatchWasLive;                      // off→on edge = a new burst window
    private int burstWindows;
    float burstVelGapMax;
    private int burstStepRebaseEvents;
    private int burstStep16Suppressed;                      // in-window overwrites suppressed
    private int burstRelaxedFrames;                         // Frames glided at the weak tether
    private int burstEscapeFreezes;                         // Frames a nonzero escape streak held frozen
    // Flux + system observability. LOCAL = the client's real local engine; SERVER = the
    // authoritative AWSS pose (flux decoded into ownPose for schema-v3+ frames; -1 = the feed sent v1 / "n/a").
    float lastLocalFluxLevel = -1f;
    float lastServerFluxLevel = -1f;
    float lastLocalCurrFlux;
    float lastLocalMaxFlux;
    float lastServerCurrFlux;
    float lastServerMaxFlux;
    float maxFluxDeltaSeen;
    // The flux_gap meter: |local−server| flux LEVEL accumulated
    // at every fresh hasFlux snapshot sample (same points as maxFluxDeltaSeen) — mean = sum/samples.
    double fluxGapSum;
    long fluxGapSamples;
    // Flux tick-pin state: the last PINNED running-max value + lines emitted (capped).
    float lastFluxDeltaPinned;
    int fluxPinLines;
    String lastLocalSysState = "n/a";
    // Stale-snapshot guard state (see STALE_SNAPSHOT_NANOS): the simTick of the last frame applied, and the
    // wall clock of the last FRESH one (MIN_VALUE = none seen this round yet). Render-thread only.
    long lastSnapshotSimTick = Long.MIN_VALUE;
    private long lastFreshSnapshotWallNanos = Long.MIN_VALUE;
    /** Armed by finishMatchReset; the first fresh post-reset
     *  snapshot stamps the own-hull epoch bump + lifecycle edge with ITS tick. Render-thread only. */
    private boolean settleRebasePending;

    // === Foe/remote hull-reconcile lane (render-thread only), extracted to
    // FoeHullReconcileLane (the deferred-refactors PRCP cohesion seam): lane
    // state + drivers live there; PRCP keeps the knob statics, the shared mirrorHull +
    // FOE_HULL_FLOOR_FRAC, and the cap-listener/bar/walker/meter feed sites. ===
    final FoeHullReconcileLane hullLane = new FoeHullReconcileLane(
            this, FOE_HULL_RECONCILE_METER, FOE_HULL_RECONCILE_APPLY, FOE_HULL_FLOOR_FRAC);

    // === STEP-16 step-rebase state (see STEP_REBASE_ENABLED): the previous FRESH frame's
    // authoritative own-ship velocity + simTick (the detector's baseline), the event counter surfaced in
    // logSpeedMeter, and the running max per-tick authoritative step (the instrument stays live even with
    // the flag off, so a flag-off WATCH still measures the contact-step regime). Render-thread only. ===
    float prevAuthVelX = Float.NaN; // package (this + the 6 baselines below): OwnMountLifecycleLane adopt re-arms them
    float prevAuthVelY = Float.NaN;
    long prevAuthVelSimTick = Long.MIN_VALUE;
    private int stepRebaseEvents;

    // === Velocity-glide state (see VELOCITY_GLIDE_ENABLED): the latest fresh-frame
    // authoritative per-tick step (the glide gate's quiet input — written by maintainStepRebase,
    // NaN until a baseline exists, so the gate starts closed), the no-allocation law output
    // buffer, and the meter counters. Render-thread only. ===
    float lastAuthStepPerTick = Float.NaN;
    private final float[] glideOut = new float[2];
    boolean glideWasWriting;
    private int glideFrames;
    private int glideEngagements;
    private float maxGlideGapSeen;
    // Above-cap persistence escape (see maintainGlideEscape): the streak of
    // gate-open above-cap fresh frames on the sim-tick clock, the previous fresh simTick that
    // clocks it, and the meter counters. Render-thread only, same discipline as the glide state.
    long escapePrevSimTick = Long.MIN_VALUE;
    long aboveCapTicks;
    private int glideEscapeEvents;
    private float maxGapEscaped;
    // === Own-velocity rebase-and-hold (see OWN_VELRECON_MODE): the stateful pure
    // law (engage streak / hold / release hysteresis all lane-side — OwnMountLifecycleLane's
    // adopt re-arm resets it beside the sibling baselines above) + the meters:
    // engage edges (meter mode: would-engage), engaged fresh frames, u/s applied (apply only),
    // max gap at an engage edge, cap-bound write count. Render-thread only, same
    // discipline as the glide state. ===
    final OwnVelReconcileLaw velRecon = new OwnVelReconcileLaw();
    private int velReconEngages;
    private int velReconHoldFrames;
    private float velReconSu;
    private float velReconGapMax;
    private int velReconCapped; // Slew writes where the cap bound (Δv < gap)

    // === EventReplay intake state (see EVENT_REPLAY_ENABLED): the net thread enqueues
    // decoded GPEE events; the render thread drains them in advance() once the tick's snapshot is
    // applied, so the age (snapshotTick − eventTick) is computed against the lane's real anchor.
    final ArrayDeque<ClientGameplayEdgeEvent> edgeEventQueue = new ArrayDeque<>();
    int eventReplayReceived;          // predict_event_rebase_received
    int eventReplayOwnDischarge;      // predict_event_rebase_own_discharge
    int eventReplayFoeDischarge;      // predict_event_rebase_foe_discharge
    int eventReplayOwnWindowEdges;    // predict_event_rebase_own_window_edges (vent+system)
    int eventReplayFoeWindowEdges;    // predict_event_rebase_foe_window_edges
    int eventReplayAutofireTarget;    // predict_event_rebase_autofire_target (v9 kind 7; own rows also feed afTargets)
    int eventReplayPaladinDet;        // predict_event_rebase_paladin_det (v10 kind 8; routed to paladinDetLane)
    int eventReplayArcHit;            // predict_event_rebase_arc_hit (v13 kind 9; routed to arcHitLane)
    int eventReplayCritMalfunction; // predict_event_rebase_crit_malfunction (v14 kind 10; routed to critMalfunctionLane)
    int eventReplayOwnWindowAgree;    // own edge whose open/closed matches the LOCAL window state
    int eventReplayOwnWindowDisagree; // own edge that contradicts it — the (U)-class skew signal
    int eventReplayAheadOfSnapshot;   // event tick > latest applied snapshot tick (the spec's ahead case)
    // Hold-ahead: events that outran the applied snapshot wait here and
    // re-offer at each drain until T_s ≥ T (render-thread only; cleared per round). The depth
    // meters ride the same lane so every WAN-shape watch measures the skew class for free
    // (measured hairpin baseline: ±1 tick).
    final ArrayDeque<ClientGameplayEdgeEvent> pendingAheadEvents = new ArrayDeque<>();
    long eventReplayAheadMaxTicks;    // predict_event_rebase_ahead_max (ticks, at hold time)
    long eventReplayAgeMaxTicks;      // predict_event_rebase_age_max (ticks, at present time)
    int eventReplayLogLines;          // per-event line cap (held events log at present, so keyed off received no more)

    // === Mid-flight own-window skew meter.
    // The edge meter above compares only AT edge instants — a window diverging MID-flight (one side ends
    // the system early: the player-felt "ON on the server, not on mine") is invisible to it.
    // GPEE rides the durable exactly-once lane, so the LATEST own window edge IS the server's
    // window state between edges (null until the round's first edge — counted unknown, never guessed).
    // Each FRESH snapshot tick we compare that derived state against the local engine's own accepted
    // window read (localWindowState — like-for-like with the server detector's isActive /
    // isVenting edges) and count per-direction disagree frames + contiguous skew streaks in sim ticks.
    // Expected prediction latency shows up as RTT-short streaks around every real edge; the WATCH-gated
    // (U)-class corrective is sized from the LONG ones (and their direction). Applies nothing; no new
    // knob (a pure meter on the eventReplay lane); render-thread only. Index 0=SYSTEM, 1=VENT. ===
    static final int MF_SYS = 0;
    static final int MF_VENT = 1;
    static final String[] MF_KIND_NAME = {"sys", "vent"};
    /** Streaks longer than this (sim ticks; ~0.5 s at the server rate) are beyond honest edge-lag
     *  latency. Read per call, never cached, so the WELCOME-seeded rate is honored
     *  regardless of class-load order. */
    static int mfLagTicks() {
        return (int) Math.round(0.5 * ServerTickRate.ticksPerSecond());
    }
    /** Per-streak log cap (counters continue past it) — skews are rare; 212641 saw ~1 per watch. */
    static final int MF_STREAK_LOG_MAX = 32;
    final Boolean[] mfServerOpen = new Boolean[2]; // latest own edge; null = none this round
    final long[] mfStreakStartTick = {Long.MIN_VALUE, Long.MIN_VALUE};
    final boolean[] mfStreakSrvOn = new boolean[2]; // open streak's direction (server=OPEN?)
    long mfLastSampleTick = Long.MIN_VALUE;
    final int[] mfFrames = new int[2];         // fresh-tick samples with both sides readable
    final int[] mfUnknownFrames = new int[2];  // fresh ticks skipped: no server edge seen yet
    final int[] mfDisagreeSrvOn = new int[2];  // server=OPEN local=closed (the player-felt case)
    final int[] mfDisagreeSrvOff = new int[2]; // server=closed local=OPEN (the mirror of the case above)
    final int[] mfStreaks = new int[2];
    final int[] mfStreaksOverLag = new int[2];
    final long[] mfMaxStreakTicks = new long[2];
    int mfStreakLogLines;
    // The foe-fire presentation + FX counters live in AuthoritativePresenter.
    private float maxAuthVelStepSeen;
    /**
     * The shared reconcile-LANE skeleton the four meter/apply
     * lanes ride (foe engine-state v6, weapon-state v4, foe system-window, foe maneuver-command
     * v7): one validated {@code off|meter|apply} knob parse (THROWS at class load — the knobs.md
     * discipline), the four shared meter counters, and the capped per-event log. Lane-specific
     * state (apply counters, the engine lane's per-tick dedup guard) stays in plugin fields.
     * Counter semantics per lane: carried/missing = fresh frames with/without the lane's
     * authoritative state (the system lane's "unknown" = missing); agree/disagree = local state vs
     * carried authoritative state (per pose-frame for the weapon lane's per-mount compare).
     */
    static final class PredictLane {
        /** Validated {@code off|meter|apply} parse; anything else THROWS at class load. */
        static String parseMode(String knob, String raw) {
            String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
            if ("off".equals(value) || "meter".equals(value) || "apply".equals(value)) {
                return value;
            }
            throw new IllegalArgumentException(knob + " must be off|meter|apply, got: " + raw);
        }

        private final int logMax;
        private int logLines;
        private int extraBudget;    // Storm re-opens, bounded below
        private int reopens;
        int framesCarried;
        int framesMissing;
        int agree;
        int disagree;

        PredictLane(int logMax) {
            this.logMax = logMax;
        }

        /** Capped per-event log line (at most {@code logMax} per combat — the lane discipline). */
        void log(String line) {
            if (logLines < logMax + extraBudget) {
                logLines++;
                PilotWatchLog.info(line);
            }
        }

        /**
         * The {@link #log} cap test, so a
         * caller skips building a line the cap would drop (no concat on the capped path).
         */
        boolean canLog() {
            return logLines < logMax + extraBudget;
        }

        /**
         * storm-triggered capture re-arm. Three own-engine storms in a row
         * hit teardown with the per-combat cap spent PRE-storm, so the
         * line-level {@code lm=}/{@code wm=}/{@code fo=}/{@code mult=}/{@code mc=} rider tokens for
         * the storm window never landed and every grade fell back to counters. A caller that has
         * detected a live storm re-opens a SPENT budget by {@code extra} lines, at most
         * {@code maxReopens} times per combat — a no-op while budget remains, so calling it every
         * storm tick is safe and self-regulating.
         */
        void reopen(int extra, int maxReopens) {
            if (reopens < maxReopens && logLines >= logMax + extraBudget) {
                reopens++;
                extraBudget += extra;
                PilotWatchLog.info("predict lane log cap REOPENED (+" + extra + ", reopen "
                        + reopens + "/" + maxReopens + ") — storm capture re-arm");
            }
        }
    }

    // Foe engine-state lane (snapshot-tick clock — one count per FRESH frame tick).
    final PredictLane foeEngineLane = new PredictLane(FOE_ENGINE_LOG_MAX);
    long engineStateLastMeterTick = Long.MIN_VALUE;
    int engineStateApplyDisables;   // apply lane: per-engine disable(perm) writes
    int engineStateApplyRepairs;    // apply lane: per-engine repair() writes
    int engineStateRepairRampDeferred; // repair edges deferred by the flameout-ramp gate (WQ 96)
    int engineStateMaskForkTicks;   // metered ticks where the ≤32-engine disabled mask forked local vs wire
    int engineStateMaskForkEntTicks; // Same fork, counted per-ENTITY pre-apply — the meter above is 1v1-only, structurally 0 in bastion (every ship a registry remote)

    // Weapon-state lane (v4 mount tier; own + foe poses, per fresh frame —
    // counted per pose-frame, so healthy 1v1 carried ≈ 2 × fresh frames).
    final PredictLane weaponLane = new PredictLane(WEAPON_STATE_LOG_MAX);
    int weaponMissingMountless;     // 45(f): missing frames where the LOCAL hull is genuinely weaponless (station modules, shuttle pod) — structural
    int weaponMissingArmed;         // 45(f): missing frames on an ARMED local hull — the defect-signal class
    int weaponMountAbsentLocal;     // 45(f): carried mounts with no local weapon at that index (roster/variant mismatch) — was a silent continue
    int weaponStateApplyDisables;   // apply lane: WeaponAPI.disable() writes
    int weaponStateApplyRepairs;    // apply lane: WeaponAPI.repair() writes
    int weaponStatePermSkips;       // server-clear but locally PERM-disabled — left alone
    // creator-governed / script-force-disabled mounts the apply lane leaves alone (DwellerHullLaw javadoc).
    int weaponStateDwellerSkipFrames;   // frames a dweller hull's whole mount list was skipped
    int weaponStateForceDisabledSkips;  // mounts skipped because the LOCAL weapon is script-force-disabled
    final WeaponRedisableBackstop weaponRedisable = new WeaponRedisableBackstop(); // D4b(2), own-file
    final OwnWeaponRegenStall ownWeaponStall = new OwnWeaponRegenStall();          // D4b(1), own-file
    final EngineRedisableBackstop engineRedisable = new EngineRedisableBackstop(); // own-file
    final OwnEngineRegenStall ownEngineStall = new OwnEngineRegenStall();          // own-file
    final DoorDetExpectation doorDet = new DoorDetExpectation(); // own-file
    // charge-glow present-from-wire lane; ALL logic own-file (growth-anchor law).
    final WeaponGlowLane weaponGlow = new WeaponGlowLane(weaponLane);
    // Replica component-damage shield; ALL logic own-file (growth-anchor law).
    final ReplicaCompDamageShield compDmgShield = new ReplicaCompDamageShield(foeEngineLane, weaponLane);
    // The god-mode replica mirror (wire FLAG_GOD → cc_godmode mults); own-file.
    final GodMirrorLane godMirror = new GodMirrorLane();
    /** The BEAM-PRESENTATION lane collaborator (E2 whole-concern extraction,
     *  the missileLane idiom): relay + aim halves,
     *  the whole beam meter family and the v12 beam-meter emitter live in
     *  {@link BeamPresentationLane}; the plugin keeps the knob statics + thin relays. */
    final BeamPresentationLane beamPres = new BeamPresentationLane(this);
    /** The TELEPORT-RECONCILE lane collaborator (K16 extraction —
     *  a file-size backstop payment): knob statics, the GPEE v3 SYSTEM_JUMP marker fold,
     *  per-entity windows/fences/heals, own-ship patience and the meter family live in the lane;
     *  the plugin keeps body/state resolution, the {@link #jumpSnap} seam and thin per-frame
     *  relays (the {@link #foeInterp} isolation idiom). */
    final TeleportJumpLane teleportLane = new TeleportJumpLane(this);
    /** The adopt/re-baseline collaborator (K-birth): world adoption + the (56)
     *  re-join deploy-burn kill + the (57) one-shot own-vitals adopt live THERE; thin relays here. */
    final RejoinRebaselineLane rejoin = new RejoinRebaselineLane(this);
    /** The FOE-INTERP lane collaborator (see {@link PredictKnobConfig#FOE_INTERP_MODE}): buffer,
     *  ramp and meters live in the extracted class (the {@link #fractures} isolation idiom);
     *  the plugin keeps the thin touchpoints only — feed at the two latestSnapshot publish
     *  sites, target substitution at the two reconcileFoe seams, round resets, meter line. */
    final FoeInterpLane foeInterp = new FoeInterpLane(FOE_INTERP_METER, FOE_INTERP_APPLY);
    /** The FOE-FACING lane collaborator (see {@link PredictKnobConfig#FOE_FACING_MODE}):
     *  the sub-quantum deadband + the client's angular telemetry, same isolation idiom. Thin
     *  touchpoints: the one facing write in {@link FoeStateLaneCoordinator#reconcileFoeTo}, the snap re-arm, the
     *  round resets, the meter line. */
    final FoeFacingLane foeFacing = new FoeFacingLane(FOE_FACING_METER, FOE_FACING_APPLY); // package: rejoin snap hatch
    /** The foe-replica STEP meter (per-mark, the F9 wire row) — measures the enemies' motion. */
    final FoeStepMeter foeStep = new FoeStepMeter();
    /** This advance frame's dt (s), latched beside the glide alphas at the top of the reconcile
     *  so the facing lane's {@code change_hz} runs on the real frame clock without threading dt
     *  through {@code maintainRemoteEntities}. Render thread, frame-scoped. */
    float foeFacingFrameDtSec;
    // Foe SYSTEM-WINDOW reconcile lane (see FOE_SYSTEM_STATE_MODE); carried/missing =
    // the old frames/unknown (fresh in-window+readable vs no derived window state yet).
    final PredictLane foeSystemLane = new PredictLane(FOE_SYSTEM_LOG_MAX);
    final PerKeyLogBudget foeSystemLogBudget = new PerKeyLogBudget( // gates every foeSystemLane row
            FOE_SYSTEM_LOG_PER_ENTITY, FOE_SYSTEM_LOG_ENTITIES, FOE_SYSTEM_LOG_MAX);
    int foeSystemApplyPresses;  // apply lane: ShipAPI.useSystem() invocations
    int foeSystemCancelPresses; // Cancel twin: fold presses on closed windows
    // mid-window forceState seed lane (rides the SAME apply opt-in; no new knob).
    int foeSystemSeeds;         // seed lane: forceState/setAmmo one-shots actually applied
    /** D4 one-shot latch, 1v1 primary foe (registry entities latch per-entry); reset per round (MRST). */
    boolean foeSystemSeeded;
    // Foe MANEUVER-COMMAND lane (v7 tier; see FOE_MANEUVER_MODE).
    final PredictLane foeManeuverLane = new PredictLane(FOE_MANEUVER_LOG_MAX);
    long foeManeuverApplyCommands;  // apply lane: giveCommand issues (per bit per frame)
    // Foe FLUX apply lane (see FOE_FLUX_MODE): meter = fresh-frame level-delta samples
    // (primary foe; agree/disagree unused — the compare is a float delta), apply = every-frame
    // idempotent tracker pin.
    final PredictLane foeFluxLane = new PredictLane(FOE_FLUX_LOG_MAX);
    long foeFluxApplyFrames;    // apply lane: setCurrFlux(+setHardFlux) pin frames
    int foeFluxSamples;         // meter: fresh-frame |local−auth| fluxLevel samples
    float foeFluxSum;           // meter: running sum of the level deltas (mean at flush)
    float foeFluxMaxDelta;      // meter: max level delta seen (pinned via the capped log)
    // zero-flux boost-clock discipline (see settleBoostClockAfterFluxPin).
    long foeFluxBoostClockZeros;     // foe lane: pins that zeroed the boost debounce clock
    long foeCrPinFrames;             // foe setCurrentCR pins (u8-cell changes; meter-mode = would-pin)
    long foeCrQuantSkips;            // foe CR frames skipped as wire-indistinguishable
    long foeCrMissing;               // fresh foe frames whose pose carried no CR tier
    // Foe FLUX-FLAGS lane (see FOE_FLUXFLAGS_MODE): meter = fresh-frame
    // agree/disagree of BOTH carried bits vs the replica tracker (BOTH call sites — the bastion
    // boss is a REGISTRY remote, the ship sets are disjoint, the shield/cloak-lane precedent);
    // apply = mismatch-edge state slaving via the engine's own verbs.
    final PredictLane foeFluxFlagsLane = new PredictLane(FOE_FLUXFLAGS_LOG_MAX);
    int foeFluxFlagsOverloadBegins;  // apply: beginOverloadWithTotalBaseDuration + one-shots
    int foeFluxFlagsOverloadStops;   // apply: stopOverload on the auth-clear mismatch
    int foeFluxFlagsVentBegins;      // apply: ventFlux() attempts on the auth-set mismatch
    int foeFluxFlagsVentStops;       // apply: stopVenting on the auth-clear mismatch
    int foeFluxFlagsVentRestartSuppressed; // drained-tail frames held, not re-begun
    int foeFluxFlagsVentTailLogLines;      // the vent TAIL row's OWN budget used
    int foeFluxFlagsVentTailRowsDropped;   // TAIL rows past that budget (ring still has them)
    // OWN-flux lane (see OWN_FLUX_MODE). Meter counters live beside the
    // own-shield rider in maintainFluxReadout; apply counters + echo-gate clocks are here.
    private final PredictLane ownFluxLane = new PredictLane(OWN_FLUX_LOG_MAX);
    int ownOverloadAgree;            // meter: fresh frames local isOverloaded == auth bit
    int ownOverloadDisagree;
    int ownVentAgree;                // meter: fresh frames local isVenting == auth bit
    int ownVentDisagree;
    private long ownFluxPinFrames;           // apply: fresh-frame setCurrFlux(+setHardFlux) pins
    // own-flux dead band (OwnFluxPinLaw):
    // the exact-inequality pin rewound the bar an RTT per snapshot. Skips + pre-decision gap meters.
    private long ownFluxPinBandSkips;        //   fresh frames inside the band: no write
    private float ownFluxGapMaxFrac;         //   meter: max |local - wire| / maxFlux (curr or hard), pre-decision
    private double ownFluxGapSumFrac;        //   meter: sum of the per-frame gap fraction (mean = sum / samples)
    private long ownFluxGapSamples;
    private long ownFluxPrevFreshSimTick = Long.MIN_VALUE; // staleTicks source (the dTicks idiom)
    // Own overload begin-once per wire
    // window (OverloadWindowLaw): the level test re-began a full overload on the lagged-fall tail.
    private boolean ownOvlBegunThisWindow;   //   the tracker read overloaded since the wire flag rose; no reset
                                             //   site exists (prevOwnAuthOverloaded has none) — self-clears on
                                             //   the first fresh frame with the wire flag down (review L3)
    private boolean ownOvlSuppressLogged;    //   one log row per window
    private int ownOverloadRestartSuppressed; // tail frames held, not re-begun
    private long ownFluxBoostClockZeros;     // apply: pins that zeroed the boost debounce clock
    private int ownOverloadSlaveBegins;      // apply: auth-rise begins (local never overloaded)
    private int ownLashOverloads;            // Slaved rises painted as the lash disruption
    private boolean ownLashOverloadColored;  //   the overload colour is ours to reset at the STOP
    private int ownLashOverloadsLate;        // Painted off a HELD floaty (flag beat the arc)
    private int ownLashUnphasedSkips;        // Rises with an arc inside the window, hull UNPHASED (plain)
    private boolean ownOverloadFloatyHeld;   //   a slaved rise on a PHASE hull with no lash arc yet waits LASH_FLOATY_HOLD_NANOS
    private long ownOverloadFloatyHeldSince = Long.MIN_VALUE;
    private int ownOverloadSlaveStops;       // apply: auth-edge/persistence-gated stopOverload
    private int ownVentSlaveStops;           // apply: auth-edge/persistence-gated stopVenting
    private boolean prevOwnAuthOverloaded;   // last fresh-frame auth bits (falling-edge detector)
    private boolean prevOwnAuthVenting;
    private boolean prevOwnAuthPhased;       //   + the phased bit (the lash gate: the frame BEFORE the rise)
    private int ownOverloadUnconfirmedFresh; // consecutive fresh frames local-on/auth-off (echo clock)
    private int ownVentUnconfirmedFresh;
    // The bits-7/10/11 lockout-contamination mask guard (captureOwnCommands).
    int lastOwnAuthFluxFlags = -1;   // latest fresh own-pose fluxFlags; -1 = never seen
    private boolean heldPreLockSystemOn;     // last UN-locked samples the guard holds during a
    private boolean heldPreLockShieldOn;     //   local-only lockout (see the guard comment at the
    private boolean heldPreLockCloakOn;      //   sampler for why bit-8 vent is exempt)
    // bit-7 hold-stretch state (see SYS_EDGE_MIN_HOLD_SECONDS).
    private boolean sysBitPrevRaw;           // previous frame's raw (lock-adjusted) bit-7 sample; rising-edge detector
    private float sysHoldStretchSec;         // seconds of armed stretch remaining; ≤0 = quiescent
    private long ownLockoutMaskHolds;        // frames the guard substituted held bits
    private int ownLockoutMaskHoldLogs;      // capped log lines for the above
    private static java.lang.invoke.MethodHandle boostClockZeroHandle;
    private static Class<?> boostClockZeroOwner;
    private static boolean boostClockZeroUnavailable;
    // OWN-CR pin lane (see OWN_CR_MODE). Gap meters stay live under apply
    // (bar 2); the wire latches feed the always-on CrClockCensus riders (bar 1).
    private long ownCrPinFrames;      // apply: fresh-frame setTimeDeployed+setCurrentCR pins
    private long ownCrQuantSkips;     // apply: CR pins skipped as wire-indistinguishable
    private long ownCrBandSkips;      // apply: one-cell pins absorbed by the 2-cell dead-band
    private long ownDepBandSkips;     // setTimeDeployed skipped inside
                                      //   OwnCrPinLaw.DEP_BAND_SEC (the PPT-clock rewind fix)
    private float ownCrGapMax;        // meter: max |local getCurrentCR() - wire cr|, pre-pin
    private float ownDepGapMax;       // meter: max |local dep clock - wire dep| seconds, pre-pin
    private int ownCrMissing;         // fresh own-pose frames whose frame carried no CR tier
    private float ownCrWireLast = Float.NaN;  // census riders: last fresh wire cr (NaN = none yet;
    private float ownDepWireLast = Float.NaN; //   round-local, reset in finishMatchReset)
    // Foe AUTOFIRE reconcile lane (see FOE_AUTOFIRE_MODE).
    final PredictLane foeAutofireLane = new PredictLane(FOE_AUTOFIRE_LOG_MAX);
    int foeAutofireApplyToggles;  // apply lane: giveCommand(TOGGLE_AUTOFIRE) issues
    final PredictLane foeSelectionLane = new PredictLane(FOE_SELECTION_LOG_MAX);
    int foeSelectionApplyCommands;  // apply lane: giveCommand(SELECT_GROUP) issues
    // same-side hold counters (reassertReplayDrivenHold): held = per-frame
    // hold-walk visits of same-side non-beam replica weapons (long: ~hundreds per frame across a
    // bound fortress). Under fireonly each visit IS a setForceNoFireOneFrame write; under the
    // legacyoff premise arm the group toggle is the hold and the visit is meter-only —
    // either way held>0 proves the walk ran (the grade-holdmeter held bar stays valid).
    // truncations = isFiring() bursts cut by stopFiring() (fireonly only).
    long sameSideHeldWeaponFrames;
    int sameSideStopFireTruncations;
    // Slew meter riding the same walk (the (74) frozen→moving control): per held
    // weapon, RELATIVE angle currAngle − shipFacing — the fortress base's client facing moves
    // (the facing reconcile), so world currAngle drifts even on a frozen turret; relative stays
    // flat. moved = weapon-frames with |Δrel| > SLEW_MOVED_DEG; maxdelta = max |Δrel| since the last
    // meter flush. Weak weapon keys: MRST sweeps the bodies (explicit clear in finishMatchReset),
    // weak refs cover mid-round despawns.
    static final float SLEW_MOVED_DEG = 0.1f; // shared with the beam idle slew meter (BeamPresentationLane)
    long sameSideMountMovedFrames;
    float sameSideMountMaxDelta;
    final java.util.Map<WeaponAPI, Float> sameSideMountLastRel = new java.util.WeakHashMap<>();
    // The SNAP-AND-HOLD aim lane (NEW-LANE LAW collaborator): same-side replica
    // turret bearing slaved to the wire discharge aim; record at presentEdgeEvent, steer + meters
    // ride the reassertReplayDrivenHold walk. Knob cc.predict.sameSideAim = snap|native.
    final SameSideAimLane sameSideAim = new SameSideAimLane(this);
    // The AIMP remote-aim lane (NEW-LANE LAW collaborator):
    // remote piloted ships' manual turrets slew natively toward the pilot's wire aim point
    // (mouseTarget seam); folds takePilotAims() per frame at the advance() maintainer block.
    // Knob cc.predict.remoteAim = off|meter|apply (default apply).
    final RemoteAimLane remoteAim = new RemoteAimLane(this);
    // Foe SHIELD reconcile lane (see FOE_SHIELD_MODE): meter = fresh-frame replica-vs-
    // carried shieldOn agreement (primary foe), apply = every-frame hold re-issue while the flag holds.
    final PredictLane foeShieldLane = new PredictLane(FOE_SHIELD_LOG_MAX);
    long foeShieldApplyCommands;  // apply lane: giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK) issues
    long foeShieldFacingForced;   // OMNI forceFacing applications while on
    boolean foeShieldFacingForcedLogged; // one-shot first-force evidence log
    // Last authoritative shield-facing u8 per wire entity — KEEP-LAST-FORCED law:
    // a frame without the facing byte (a ≤v10 stamp from a v11 server when every facing
    // quantized to 0) means "no NEW facing", never "facing zero", so the lane keeps forcing
    // the last known value. Cleared at the round boundary with the other per-round state.
    final java.util.Map<Long, Integer> lastShieldFacingU8 = new java.util.HashMap<>();
    // The press-shadow DIAGNOSTIC probe (knobless, hard-capped: ≤20 samples per
    // run, ≤10 per entity per round), extracted to its own collaborator at the 9500-nb hard
    // backstop. PRCP keeps the touchpoints only: the shield-lane
    // press pair below, the advance() flush, the round reset, and the meter counter read.
    final PressShadowProbe pressShadow = new PressShadowProbe(this);
    // The local-fire census (NEW-LANE LAW — telemetry born as its own
    // collaborator). Package-private: MissilePoseLane reports its two ephemeral presented-adds.
    final LocalFireCensus localFire = new LocalFireCensus(this);
    // The entity-SPAWNING system PRESS GATE (knob
    // cc.predict.foeSpawnPressGate, default ON) — its own collaborator per the NEW-LANE LAW.
    // Package-private: LocalFireCensus rides its meter fragment onto the census line it verifies.
    final SpawnSystemGate spawnGate = new SpawnSystemGate(this);
    // The HULLMOD arm of the same double-mint class (knob cc.predict.foeSpawnHullmodGate,
    // default ON) — a builtin never routes through useSystem(), so spawnGate cannot reach it.
    // NEW-LANE LAW collaborator; the whole PRCP relay is this field + the one neutralize line.
    final SpawnHullmodGate spawnModGate = new SpawnHullmodGate(this);
    // swarm-fighter base sprite hide; ALL logic own-file.
    final SwarmSpriteGate swarmSpriteGate = new SwarmSpriteGate();
    // swarm-cloud probe (diagnostic rows for fresh swarm-fighter replicas; knob-gated, default off).
    final SwarmCloudProbe swarmCloudProbe = new SwarmCloudProbe();

    // the drone collision gate, extracted at the 9500-nb backstop fire (cold
    // first candidate, deferred-refactors.md); the mint-side arm stays at the spawn site below.
    final DroneGateLane droneGateLane = new DroneGateLane(this);

    // The "Incoming vessels" feed-spam suppressor;
    // wraps all six mirror-spawn sites, knob cc.predict.deployFeedMute.
    final DeployFeedGate feedGate = new DeployFeedGate(this);
    // the chat lane (input-law feed, typing render, CHTD HUD drain,
    // CHTU send drain) extracted to its own collaborator — the deferred-refactors K4 cold
    // ChatLane pre-pay; PRCP keeps this field + 6 thin relays + the round-reset relay.
    final ChatLane chat = new ChatLane(this); // package-private: ListPickerLane rides its send queue + box-open read
    long ownShieldAgree;          // own-ship rider: fresh own-pose frames local shield == carried flag
    long ownShieldDisagree;       // own-ship rider: fresh own-pose frames the two disagree
    // The own-ship CLOAK agreement rider (the bit-11 UP lane's end-to-end check, the
    // ownShieldAgree shape on the v10 phase-cloak tier): fresh own-pose frames where the local cloak's
    // isOn() == carried auth state ∈ {IN, ACTIVE}. Tier ABSENT (non-phase hull / pre-v10) = skip, never
    // a disagree; unknown future state code = skip (the foe-lane decode tolerance).
    long ownCloakAgree;           // own-ship cloak rider: fresh own-pose frames the two agree
    long ownCloakDisagree;        // own-ship cloak rider: fresh own-pose frames the two disagree
    // lead-side split of the disagree above: local_on = local isOn, auth off
    // (dominant when the LOCAL window is longer — server cycling FASTER, or press-entry latency);
    // auth_on = the inverse (dominant when the LOCAL side cycles FASTER — the felt-accelerator shape).
    long ownCloakDisagreeLocalOn;
    long ownCloakDisagreeAuthOn;
    // The disagreement-force apply state: consecutive fresh frames of ON-ness disagreement + force count.
    int ownCloakDisagreeStreak;
    long ownCloakForces;
    int ownCloakForceLines;
    // The own-hull probe: emitted lines (rate-capped) + fresh frames since the last.
    private int ownHullProbeLines;
    private int ownHullProbeCooldown;
    // The foe PHASE-CLOAK reconcile lane (see FOE_CLOAK_MODE): meter = fresh-frame
    // replica isOn() vs carried IN|ACTIVE at BOTH call sites (the foe-shield precedent —
    // in bastion the grendel is a REGISTRY remote, never the primary foe), apply = mismatch-EDGE
    // toggle + the fresh-keyframe repair (a later remedy folded the one-shot seed in).
    final PredictLane foeCloakLane = new PredictLane(FOE_CLOAK_LOG_MAX);
    int foeCloakApplyToggles;       // apply lane: giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK) issues — the strobe meter (edge law ⇒ human-scale counts)
    int foeCloakSeeds;              // forceState/setAmmo writes applied (fresh-keyframe repairs + the bit's falling-edge OUT force)
    int foeCloakSeedPhaseGateSkips; // repairs whose IN/ACTIVE force the same-frame phased-bit gate refused
    int foeCloakBitEdgeApplies;     // Actions the per-tick FLAG_PHASED edge drove (toggle ON at the rise, force OUT at the fall)
    int foeCloakHeldRetries;        // re-presses where the HELD tier agreed with the bit (refused press / locally un-phased twin)
    /** Mirror of the ShipFluxSnapshot fluxFlags FLAG_SHIELD_ON bit (the server's packing contract). */
    static final int FLUX_FLAG_HAS_SHIELD = 1 << 2;
    static final int FLUX_FLAG_SHIELD_ON = 1 << 3; // package: the rejoin lane's (57) shield conform reads it
    /**
     * The ShipFluxSnapshot OVERLOADED/VENTING bits (packing re-confirmed at
     * mint). bit0/bit1 of the SAME fluxFlags byte as the shield bits
     * above; a different namespace from the UP-intent heldControls bit numbering.
     */
    static final int FLUX_FLAG_OVERLOADED = 1;
    static final int FLUX_FLAG_VENTING = 1 << 1;
    /** Primary-foe derived authoritative system-window state (latest ON/OFF edge); null until seen. */
    Boolean foeSysServerOpen;
    /** The primary foe's window KEY (last applied ON simTick; -1 = none). */
    long foeSysOpenTick = FoeSystemWindowLaw.NO_WINDOW;
    // The own-ENGINE meter rider — later UPGRADED to a full meter/apply lane
    // (see OWN_ENGINE_STATE_MODE): a recorded run met the meter-first shipping condition (548/5050
    // fresh frames disagreeing across a server-side flameout the replica never mirrored).
    private int ownEngineFramesCarried;
    private int ownEngineFramesMissing;
    private int ownEngineAgree;
    private int ownEngineDisagree;
    int ownEngineApplyDisables;
    int ownEngineApplyRepairs;
    /** Own apply-DISABLE edges deferred by the spawn-settle guard (counted, never silent). */
    int ownEngineDisableDeferred;
    /** WQ (96): own repair edges deferred by the flameout-ramp gate (counted, never silent). */
    int ownEngineRepairRampDeferred;
    // windows-shaped accounting (the mf_* streak idiom, simplified): 180241
    // read 87 disagree FRAMES = 4 transition windows — a frame-count meter on a boundary process
    // scales with damage taken, not desync, so the bar re-laws to windows n / maxTicks and the
    // raw frame count demotes to INFO. A streak = a contiguous disagree run in sim ticks, closed
    // by the first agreeing carried frame (or the round reset, honestly).
    private long ownEngineStreakStartTick = Long.MIN_VALUE;
    private long ownEngineStreakLastTick = Long.MIN_VALUE;
    private int ownEngineStreaks;
    private long ownEngineMaxStreakTicks;

    // === The state-1 SOURCE FIX: replay-driven ships
    // (the InertShipAI bodies — 1v1 foe / bots-shape remotes) must fire ONLY via replayed authoritative
    // discharges. InertShipAI alone does NOT stop local fire: the engine advances weapon-group AUTOFIRE
    // plugins for any autofire-ON group unless it is the PLAYER ship's selected group
    // (the engine's WeaponGroup.advance -> advanceAuto), so un-suppressed these bodies also fire on a local
    // autonomous schedule — a recorded run's local-only Hellbore that flamed out the own ship's engines with
    // zero wire damage. Suppression = the engine's OWN hold-fire gate re-asserted per frame
    // (ShipAPI.setHoldFireOneFrame(true): the same idiom the engine's tutorial uses to hold AI ships'
    // fire; advanceAuto's fire boolean is && !isHoldFireOneFrame(), and Ship.advance clears the flag at
    // its own end AFTER its groups consumed it, so a per-frame set holds continuously under either
    // plugin/ship advance ordering and self-reverts if this plugin stops advancing). Replayed fire is
    // untouched — presentFoeDischarge spawns via CombatEngineAPI.spawnProjectile, which bypasses the
    // weapon fire cycle. Own ship NEVER in the set (native player fire is the by-design zero-RTT
    // prediction). Meter: distinct replay-ship-source projectiles NOT presenter-spawned, SPLIT on
    // getWeapon(): weaponless = the engine's OWN source-attributed death-explosion
    // class (expected at client-side death mirrors — counted apart); weapon-backed = AUTONOMOUS
    // (bar: 0 while suppression is live; >0 = the gate leaked — the state-1 defect class is back). ===
    final Set<ShipAPI> replayDrivenShips = Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
    private final Set<Object> seenReplayShipProj = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    // Package: MissilePoseLane adds its presence-adopt spawns (same exclusion discipline).
    final Set<Object> presentedFoeProj = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    // The sets above grew per shot to a round reset registry shapes never reach. ~5 s at
    // 60 fps, on the render thread that owns them (bounded, timed as sweep_us on merger-meter).
    private static final int PROJ_SET_SWEEP_FRAMES = 300;
    private int projSetSweepFrames, projSweepFails;
    private long projSweptTotal, projSweepLastUs;
    /** Round-scoped presentedFoeProj sweeps: never-consumed entries, so LocalFireCensus residual = size + this. */
    int projSweptPresented;
    // The devastator-fuse both-sides measure (constructed in init() beside the
    // presenter; force-loaded at app load). Render-thread only, like the sets above.
    FuseBurstMeter fuseMeter;
    // The leg-1c damage witness (render-thread only, like the sets above).
    final DamageWitness dmgWitness = new DamageWitness(DMG_WITNESS_ENABLED, replayDrivenShips, this);
    final CanisterFlakProbe canisterProbe = new CanisterFlakProbe(); // measure only
    final AcceptedPressHold defensePressHold = AcceptedPressHold.forDefenseSlot(); // the canister-flak defense-press fix
    final AcceptedPressHold systemPressHold = AcceptedPressHold.forPrimarySlot(); // for flare launchers
    // The extracted own-fire-lock + discharge-reconcile lane
    // coordinator (state + drivers moved VERBATIM; map).
    final FireLaneCoordinator fireLanes = new FireLaneCoordinator(this);
    final FoeStateLaneCoordinator foeState = new FoeStateLaneCoordinator(this); // K17: foe-state reconcile family

    /** {@link #removeStaleBody} rows logged so far (cap {@value #REMOVE_BODY_LOG_MAX}). */
    private int removeBodyRows;
    private static final int REMOVE_BODY_LOG_MAX = 40;

    /**
     * The ONE removal verb for a SHIP body this client
     * minted (or inherited from the mission build) and now drops WITHOUT a death: the claim-at-play
     * placeholder, a pose-raced re-mint, a vacated / stranded transfer body. Vanilla evicts a
     * fleet-manager DEPLOYED member only through its death listener
     * ({@code CombatFleetManager.shipDestroyed/shipDisabled -> removeDeployed}), so a bare
     * {@code removeEntity} left every such member in the deployed set for the rest of the combat:
     * scanned by every per-member engine walk ({@link DeployedSetEvictLane}) and drawn by
     * any consumer that walks the deployed set (the owner's client ended with census
     * {@code deployed0=…/dead1} — one side-0 member whose ship was OUT OF PLAY — beside a tab-map
     * arrow parked at the initial spawn, where the removed placeholder pod last was). Evict FIRST
     * (the listener's own verb; idempotent; the body stays in the world for the call), then remove.
     * Ship bodies only — projectiles / missiles keep the bare engine call. Logged, bounded.
     */
    void removeStaleBody(ShipAPI body, String why) {
        if (body == null || engine == null) {
            return;
        }
        int before = foeState.deployedEvict.evicted;
        foeState.deployedEvict.evict(engine, body, why);
        boolean inPlay = engine.isEntityInPlay(body);
        if (inPlay) {
            engine.removeEntity(body);
        }
        if (removeBodyRows < REMOVE_BODY_LOG_MAX) {
            removeBodyRows++;
            PilotWatchLog.info("predict remove-body [" + why + "]: "
                    + Integer.toHexString(System.identityHashCode(body)) + " variant="
                    + (body.getVariant() != null ? body.getVariant().getHullVariantId() : "?")
                    + " owner=" + body.getOwner() + " alive=" + body.isAlive() + " inplay=" + inPlay
                    + " deployed_evicted=" + (foeState.deployedEvict.evicted != before)
                    + (removeBodyRows == REMOVE_BODY_LOG_MAX ? " [log cap reached]" : ""));
        }
    }
    /** The per-frame {@code getAllWeapons()} cache every replica lane reads. */
    final WeaponListCache weaponLists = new WeaponListCache();
    // The served-STATION adopt-bind lane (NEW-LANE LAW — collaborator).
    // Inert unless the descriptor carries a station row; the plugin owns only the wiring
    // (descriptor acceptance in init, the per-frame drive below, the MRST resetRound).
    final StationAdoptBindLane stations = new StationAdoptBindLane(this);

    /** The MMNT module-mount fold lane (module piloting); drive call
     *  sits between {@code vdef.drain()} and {@code rapp.drain()} (wire order MMNT≺RAPP). */
    final ModuleMountLane mmnt = new ModuleMountLane(this);
    /** XFER command-transfer edge frames taken (package: the S2 lane
     *  counts through it since the apply leg; the take keeps the queue empty/bounded). */
    int commandTransferFramesTaken;
    /** The XFER command-transfer lane (NEW-LANE-LAW
     *  collaborator): vanilla-trigger poll -> {@code /transfer #id}, own-BEGIN pod adoption +
     *  TRANSIT, DENY/ABORT typed return, COMMIT own/remote re-point; drive calls = the
     *  registryShape intake, {@code applyStaged()} post-spawn, and the {@code pinPump} as
     *  advance()'s FINAL statement (presenter pins last). */
    final CommandTransferLane xfer = new CommandTransferLane(this);
    /** The OWN seat's mount-lifecycle side effects + death-edge disarm episode (adopt/release/
     *  vacate/disarm — the own-lifecycle carve; bodies + episode state live THERE). */
    final OwnMountLifecycleLane ownLife = new OwnMountLifecycleLane(this);
    /** Mounted seats by seat wire id (lane-law state: the table other lanes read —
     *  roster guard, spawn suppression; maintained ONLY by {@link ModuleMountLane}). */
    final Map<Long, ModuleMountLane.MountedSeat> mountedSeats =
            new LinkedHashMap<Long, ModuleMountLane.MountedSeat>();
    /** The mounted BODIES (identity set) — the hold carve-out's hot-path predicate:
     *  a seat-mounted module's weapons ride the seat fire lanes, never the same-side hold. */
    final Set<ShipAPI> mountedBodies =
            Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
    /** Own seat's mounted module body, null while unmounted — the own-pose
     *  suppression predicate (the engine derives module pose from the parent every frame;
     *  own-pose reconcile writes would fight that derivation). */
    ShipAPI ownMountedModule;
    // The ONE replica-body treatment ledger (see neutralizeRemoteBody) —
    // identity-keyed, value = "the RemoteDamageCap is installed". Round-local: cleared beside
    // replayDrivenShips in finishMatchReset (the bodies themselves are swept there).
    final java.util.Map<ShipAPI, Boolean> remoteBodyTreated =
            new IdentityHashMap<ShipAPI, Boolean>();
    // The missile pose lane (NEW-LANE LAW — collaborator, not in-plugin).
    final MissilePoseLane missileLane = new MissilePoseLane(this);
    // The autofire-TARGET apply coordinator (NEW-LANE LAW — collaborator).
    // Own-ship wire picks live there; the CcClientModPlugin-installed AutofireTargetAiPlugin
    // wrappers query livePickFor per weapon advance via activeAutofireTargets() (null/no-pick =
    // vanilla pass-through). Intake rides presentEdgeEvent kind-7; clock rides drainEdgeEvents.
    final AutofireTargetCoordinator afTargets = new AutofireTargetCoordinator(this);
    // The OWN mine/flash-bomb det-damage lane (NEW-LANE LAW — collaborator).
    final OwnDetLane ownDetLane = new OwnDetLane(this);
    // The damage-cap family (HullCapCore + the Foe/Own/Remote caps, the
    // own-missile hit feed, the premature-kill meter, fx_restores) carved VERBATIM into HullCapLane
    // (deferred-refactors.md PRCP row; the 9500 backstop crossed). The SAME four
    // registration sites instantiate the lane's inner classes in the same order (hullCap.new X()),
    // so listener identity/order + the clamp->restore one-shot are unchanged.
    final HullCapLane hullCap = new HullCapLane(this);
    /** The replica-body treatment, the
     *  wire-acked hit and the replay-driven hold, carved VERBATIM into {@link ReplicaBodyLane}; its
     *  ctor stores {@code this} and reads nothing, so any field position is init-safe. */
    final ReplicaBodyLane replicaBody = new ReplicaBodyLane(this);
    // The GPEE v10 PALADIN_DET (kind 8) FX presenter (NEW-LANE LAW —
    // collaborator; knob statics live in the lane). Intake rides presentEdgeEvent kind-8.
    final PaladinDetFxLane paladinDetLane = new PaladinDetFxLane(this);
    /** The GPEE v13 ARC_HIT (kind 9) presenter — the server-named EMP-arc hit
     *  (arc to the true node + the applied-split floaty); knob statics live in the lane. */
    final ArcHitFxLane arcHitLane = new ArcHitFxLane(this);
    final CritMalfunctionLane critMalfunctionLane = new CritMalfunctionLane(this); //
    // The presented-missile track instrument (cc.mtrack.every;
    // OFF by default — log-only, knob-independent of the lane so it can measure the A side too).
    final PresentedMissileTrack mtrack = new PresentedMissileTrack(this, missileLane);
    // The DEM slaved-drone presenter (NEW-LANE LAW — collaborator).
    final DemDronePresenter demLane = new DemDronePresenter(this, missileLane);
    // The CONTENT-DICTIONARY catalog — wire contentId → true content key
    // (engine projectile spec id), folded last-write-wins off each fresh applied snapshot's
    // cycling announcements (a re-fed entry is a no-op). Presentation-only: never trusted
    // beyond picking the presented spec; an unresolved id falls back to the launcher recipe.
    // Persists across round resets (contentIds are match-scoped — the server registry lives
    // outside the round loop); cleared at teardownActive with the session. Render-thread only.
    final Map<Integer, String> contentCatalog = new HashMap<>();
    // own-ship CR/PPT clock census (frozen- vs slow-clock discriminator).
    private final CrClockCensus crCensus = new CrClockCensus();
    // per-Merlon armor census — the at-load chew discriminator.
    private final MerlonArmorCensus merlonCensus = new MerlonArmorCensus();

    // serve-end MOVR verdict -> native end-of-combat drive.
    final MatchOverLane matchOverLane = new MatchOverLane(variantById, remoteEntities);
    // HOUS KICKED notice -> banner + floaty + native defeat drive.
    private final KickedNoticeLane kickedNoticeLane = new KickedNoticeLane();
    final AutonomousLeakRemover leakRemover = new AutonomousLeakRemover(); //
    int replayShipAutonomousDischarges; // predict_event_rebase_replay_ship_autonomous_discharge
    /** One-shot foe-discharge starvation tripwire.
     *  Fires once if sourced foe-track adopts accumulate while ZERO foe WEAPON_DISCHARGE edges
     *  have arrived — the signature a real GPEE foe-lane starvation would leave (composed
     *  presentation IS foe-fire visibility, so under starvation foe fire surfaces only as
     *  adopted server tracks). Threshold clears the healthy per-run adopt residue (~20, the
     *  own-ghost class) and the legitimate zero of the opening seconds. */
    static final int FOE_DISCHARGE_STARVATION_ADOPTS = 25; // package-private: StarvationFeedSmoke
                                                           // binds the live threshold
    boolean foeDischargeStarvationWarned;
    private int replayShipAutonomousLogLines;
    int replayShipNonWeaponDamagers; // predict_event_rebase_replay_ship_nonweapon_damager (death-explosion class)
    // The mine/null-weapon-MINT arm split out of the bucket above (spec id non-null —
    // the engine's DamagingExplosion hardcodes null for both getWeapon and getProjectileSpecId).
    int replayShipWeaponlessSpawns;  // predict_event_rebase_replay_ship_weaponless_spawn
    private int replayShipWeaponlessLogLines; // capped like the sibling autonomous arm
    boolean autofireSuppressLogged;

    // === The declared-discharge fire lane, client send half: the same
    // projectile scan that feeds recordLocalDischarge also DECLARES each local own-gun discharge
    // UP — {declSeq, clientTick, floatToIntBits(aimDeg)} tuples queued on the render thread (same
    // synchronized-ArrayDeque pattern as edgeEventQueue), drained by the NET thread immediately after
    // each sendIntent and ONLY there (a PDCL between rounds would fail-close the server's un-demuxed
    // awaitReentry read — riding strictly behind an intent makes that impossible by construction).
    // Round-boundary reset: applyMatchReset drains this queue + restarts fireDeclSeq at 0
    // alongside the combat rebuild — the server wires a FRESH declared-fire lane per round and drops
    // declarations that raced the boundary as stale, so a carried-over seq/queue would desync the
    // audit trail. Declarations are NOT gated on EVENT_REPLAY_ENABLED — the fire lane is independent
    // of the down-lane event replay.
    final ArrayDeque<long[]> pendingFireDecls = new ArrayDeque<>();
    int fireDeclSeq;      // monotonic per session, first declaration = 0 (server logs seq_max)
    private int fireDeclSent;     // fire_decl_sent — the client counterpart of the server's fire_decl_received
    int fireDeclDropped;  // fire_decl_dropped — capture-queue overflow (net loop dead/backed up)
    int fireDeclAutofireSkipped; // fire_decl_autofire_skipped — autofire discharges NOT declared (mirrored state, not input)
    // The unwired-own-fire detector — every own discharge that reaches
    // declareFireDischarge is classified by UnwiredOwnFireLaw against the held-fire level sample
    // (fireHeld) and the engine's UI autopilot. secondsSinceFireHeld ticks in advance() beside
    // fireHeldFrames on the engine's per-frame combat dt (SIM seconds — fps-independent, frozen
    // while paused; the error cap rides clientSimTimeSec the same way); the four counters ride the
    // GAP2 fire-meter line as own_fire_wired_held / own_fire_wired_autofire /
    // own_fire_unwired_autopilot / own_fire_unwired_other — the measurement of the recon's
    // AI-fires-how hinge. The recency resets per round (the round-rebuild block); the counters
    // stay cumulative like the rest of the meter.
    float secondsSinceFireHeld = UnwiredOwnFireLaw.NEVER_HELD;
    int ownFireWiredHeld;
    int ownFireWiredAutofire;
    int ownFireWiredSystem; // SYSTEM-slot weapon discharges (the system press carried them); meter own_fire_wired_system
    int ownFireUnwiredAutopilot;
    int ownFireUnwiredOther;
    private float ownFireUnwiredLastErrorSec = UnwiredOwnFireLaw.NO_ERROR_YET;
    // The client confessing-gun decl lane — covered-spec own discharges
    // arrive via the engine's own onFire push (CcClientOnFireEmitter → ClientFireBirthSink →
    // onOwnFireBirth) at the TRUE muzzle instant; the scan stays for uncovered specs
    // (beams/missiles) with seenOwnProj as the cross-lane dedup.
    int fireDeclBirthSeen;          // predict_onfire_client_birth — callback-lane own discharges consumed
    int fireDeclBirthForeign;       // predict_onfire_client_foreign — non-own-source births (foe/remote local fire)
    int fireDeclBirthPresentedSkip; // predict_onfire_client_presented_skip — presenter spawns (expect 0: spawnProjectile bypasses the weapon cycle)
    /** Pending-declaration bound — mirrors the server lane's deque bound; oldest dropped (stale decls expire server-side anyway). */
    static final int FIRE_DECL_PENDING_MAX = 256;
    /** Per-send log cap (counters keep counting past it), same discipline as DISCHARGE_MISS_LOG_MAX. */
    private static final int FIRE_DECL_LOG_MAX = 40;

    // AWSD delta-reject NACKs (SNNK) mirror the PDCL deposit/drain law — the
    // NET (reader) thread drains the merger's pending queue right after each onAwsdFrame fold
    // into this deque (the merger is net-thread-confined; this deque is the cross-thread
    // hand-off), and sendPendingAwsdNacks drains it strictly behind an intent at BOTH send
    // sites (welded net loop + duplex writer loop). Bounded drop-oldest like the merger's own
    // cap; round boundary clears it alongside pendingFireDecls (stale cross-round NACKs would
    // only force pointless re-keys). Each element = {entityId, rejectedEpoch}.
    private final ArrayDeque<int[]> pendingAwsdNacks = new ArrayDeque<>();
    int awsdNackTx; // awsd_nack_tx — SNNK frames actually sent on CTRL (WireMarkRows reads it)
    private static final int AWSD_NACK_PENDING_MAX = 256;
    private static final int AWSD_NACK_LOG_MAX = 40;

    // === The TIMESTAMPED dual-side SIM-RATE instrument ("build a timestamped
    // dual-side speed/flux instrument to QUANTIFY the rate ratio (~1.27)"). Settles, in ONE place on the client,
    // whether the two combat CLOCKS agree. The client's LOCAL sim-time is the running sum of the engine's own
    // per-frame combat dt (the `amount` advance() receives); the SERVER's sim-time is read straight off the
    // AWSS simTick, which advances exactly one fixed 1/60 s step per server tick. Dividing each by the SAME
    // wall-clock window (anchored at the first authoritative frame, so both windows start together) yields
    // clientRate / serverRate (sim-seconds per real-second) and serverOverClient — the latter IS the "everything
    // ran faster on the server by ~1.27" the owner read live off the speed HUD, now a number, not an eyeball.
    // Render-thread only; read-only; no wire/codec change. A value of clientRate<1.0 ⇒ the client sim is the slow
    // clock (owner's hypothesis); ~1.0 on both ⇒ the 110-vs-140 is NOT a global clock but a per-mechanic cap.
    long combatWallStartNanos = Long.MIN_VALUE;
    double clientSimTimeSec; // package-private: ClockServoLane reads it (K20 extraction seam)
    private long firstServerSimTick = Long.MIN_VALUE;

    // === Per-frame clock accounting (the surviving core of the STEP-5 discriminator, whose H1-vs-H2
    // question was ANSWERED: H2 → STEP-6/7/8 named the open-loop pacer → the STEP-9 servo corrects it; the
    // small/big bucket split was pruned with the closed arc). Feeds the servo (measureFrameClock is its
    // frame intake) and the clock-meter's corrected-drift verdict. Render-thread only. ===
    private long lastFrameNanos = Long.MIN_VALUE;
    private long clockFramesMeasured;   // frames with a valid realDt (excludes the 1st + pause/load gaps)
    private double sumAmountSec;        // Σ amount over measured frames
    private double sumRealDtSec;        // Σ realDt over measured frames
    private long pauseGapFrames;        // frames whose realDt > PAUSE_GAP_SEC (excluded as pause/load gaps)
    private float maxAmountSec;         // largest amount seen (empirically reveals the engine's per-frame cap)

    // === The UNCLAMPED frame-overrun witness — the client mirror of the server's
    // frame_overrun rows. The engine clamps `amount` (~33 ms), so the pin analysis was blind to any
    // client frame longer than the clamp; realDt from measureFrameClock is the unclamped wall truth.
    // Threshold = 2× the 60 Hz budget (matches the spec); pause/load gaps stay excluded via
    // PAUSE_GAP_SEC. Per-overrun rows ride a dedicated budget (the convLog idiom — never the shared lane
    // cap, which mine spam exhausts); the summary tokens ride the clock-meter line. Render-thread only. ===
    private static final double FRAME_OVERRUN_SEC = 2.0d / 60.0d;
    private static final int FRAME_OVERRUN_ROW_MAX = 480;
    private long overrunFrames;         // measured frames with realDt > FRAME_OVERRUN_SEC
    private double maxRealDtSec;        // worst unclamped measured frame (pause gaps excluded)
    private int overrunRowsLogged;      // dedicated row budget used

    // === The HIGH-FPS HITCH tier. The frame-overrun floor is 33 ms;
    // a player at 540 fps (~1.9 ms frames) FEELS a 15–30 ms hitch that never crosses it (both recorded
    // F9 marks fell under every instrument floor). A hitch = realDt > max(HITCH_ABS_FLOOR_SEC,
    // HITCH_BASE_MULT × the running frame-time baseline), at or under the overrun floor (the overrun
    // row owns everything above — never two rows for one frame). Baseline = an EMA of realDt that
    // its own hitches never feed, so a stutter storm cannot raise the bar it is measured against; at
    // vsync 60 fps the multiplier term (~67 ms) sits above the overrun floor, so the tier is silent
    // there by construction. Dedicated row budget; summary tokens ride the clock-meter line. The row
    // carries the authoritative tick so an F9 marker row co-locates without the wall-stamp join. ===
    private static final double HITCH_ABS_FLOOR_SEC = 0.008d;
    private static final double HITCH_BASE_MULT = 4.0d;
    private static final double HITCH_BASE_ALPHA = 0.02d;
    private static final int HITCH_ROW_MAX = 480;
    private double hitchBaseSec = Double.NaN; // EMA frame-time baseline (NaN = unseeded)
    private long hitchFrames;           // measured frames in the hitch band
    private int hitchRowsLogged;        // dedicated row budget used

    /** Per-round client worst-frames ring — the
     *  client twin of the server's (104)(d) rows. Rides measureFrameClock's realDt; knob + ring +
     *  emit + cause vocabulary live in {@link ClientWorstFramesLedger} (default-OFF, watch-tier).
     *  Package-private: {@code FractureFamilyCoordinator} notes "split" at
     *  hulk-split family close-outs (the sibling-collaborator field shape). */
    final ClientWorstFramesLedger frameLedger = new ClientWorstFramesLedger(8);
    /** Periodic by-class registry census (logic in {@link RemoteEntityCensus}). */
    final RemoteEntityCensus remoteCensus = new RemoteEntityCensus();
    /** The F9 wire-MARK send lane (rate mirror + sentinel map + fail-soft lane-side). */
    final MarkWireSendLane markWire = new MarkWireSendLane(() -> session);

    // === NO-FEED CONTROL. A serverless cc_predict run is stock combat load (no net
    // thread traffic, the plugin otherwise idle), so metering it measures the ENGINE's open-loop frame pacing
    // (CombatState.traverse: sim credited the PLANNED period, sleep overshoot never compensated) on this
    // machine WITHOUT the mod's load. Discriminates the STEP-7 story ("mod load slows the client"; predicts
    // ~1.00 here) from the STEP-8 one ("Windows sleep wakeup overshoot slows ANY sleep-paced run"; predicts
    // ~0.93-0.97 at fps:120). Wall-anchored at the first no-feed frame; fully RESET + re-anchored if a feed
    // arrives later, so the fed measurement stays anchored at the first authoritative frame exactly as before.
    private boolean noFeedMeter;
    private int noFeedFrames;
    // HOLD-UX: the pre-match park pose {x, y, facing}, captured at the first connected
    // no-feed frame and re-pinned every hold frame; cleared when the first authoritative frame releases
    // the hold (the noFeedEnd transition below).
    private float[] holdParkPose;

    /** CLOCK SERVO — K20-extracted to {@link ClockServoLane}
     *  (extraction-first): the servo statics + {@link ClockServoLaw} instance,
     *  trace state + emitter, announced-sim-speed retarget pair, I.9 latch, and the
     *  engine-global timeMult scrub all live there. This plugin keeps the frame intake
     *  ({@link #measureFrameClock}), the clock-meter verdict, and the 1 Hz slope/net-rate buffers
     *  ({@link #sampleRateWindows} — fired by the lane once per trace interval). */
    final ClockServoLane clockServo = new ClockServoLane(this);

    /** I.14 trace-touch bundle (the v2–v4 build-when-adjacent riders, landed together —
     *  all additive logging/anchoring, no law contact, kill-switch-irrelevant). The I.2′ {@code C_lock}
     *  post-lock anchors moved to {@link ClockServoLane} with the trace (K20 extraction).
     *  I.3 {@code slope10s}: the re-registered server-sag trigger variable as a logged number — trailing
     *  ~10 s {@code ΔserverSim/Δwall}, the only form a real sag can't hide from (a 2% sag takes
     *  ~minutes to move the cumulative {@code serverRate} but shows in the slope in seconds). Sampled once
     *  per trace interval; {@code n/a} serverless. Exactly what the I.12(b) servo-guard parser keys on.
     *  The HUD SIM RATE lines ride this same buffer (windowed, not cumulative-anchor —
     *  a mid-round adopt made the cumulative form read a phantom ~69x for the rest of the round), so each
     *  sample also carries {@code clientSimTimeSec}. */
    private static final double SLOPE_WINDOW_SEC = 10.0d;
    private final ArrayDeque<double[]> slopeSamples = new ArrayDeque<>(); // {wallSec, serverSimSec, clientSimSec} @ 1 Hz
    /** {wallSec, simTick, framesPublished} @ 1 Hz for the SERVER TICK readout (render thread only). */
    final ArrayDeque<double[]> netRateSamples = new ArrayDeque<>();
    /** DOWN frames the net loop published to {@link #latestSnapshot} (net-thread written). */
    private volatile long netFramesPublished;
    // MIRROR: the last AWSD frame's v9 side-totals block — null until a v9 frame
    // lands (v8 server / pre-first frame ⇒ the override lane stays fully inert). Reader thread
    // writes each frame (memoryless), the render-thread lane reads (volatile handoff).
    private volatile cc.starsector.client.net.AwsdSnapshotReader.SideTotalsBlock serverSideTotals;

    /** Render-thread read for {@link SideTotalsOverrideLane}; null ⇒ lane inert (v8 / no frame yet). */
    cc.starsector.client.net.AwsdSnapshotReader.SideTotalsBlock serverSideTotalsOrNull() {
        return serverSideTotals;
    }
    /** The currently-active plugin instance, so a combat exited WITHOUT isCombatOver() still gets torn down
     *  (mirrors {@link PilotIntentCombatPlugin#active} — there is no cleanup() hook on a combat plugin). */
    private static volatile PredictReconcileCombatPlugin active;

    /** The ACTIVE plugin's autofire-target coordinator, or {@code null}
     *  (no active combat plugin). The {@link AutofireTargetAiPlugin} wrappers read this PER
     *  advance — never captured at mint, since weapons (and their wrappers) are built before
     *  this plugin's init on mission load. A stale not-yet-torn-down instance is harmless:
     *  livePickFor gates on ITS ownShip identity + live session. */
    static AutofireTargetCoordinator activeAutofireTargets() {
        PredictReconcileCombatPlugin p = active;
        return p == null ? null : p.afTargets;
    }

    /** Set on init's connect-fail hold-open path (which returns before registryShape is ever
     *  computed) so the StartBannerGuard refresh covers the held-open serverless shell too. */
    private boolean connectFailHoldOpen;

    /**
     * The LATE-ATTACH HOLD. TRUE while this window — opened
     * with the claim-at-play dial still in flight past the claim wait — keeps the placeholder parked and
     * held inert and polls for the WELCOME to bind into THIS round (such a window used to run the round
     * local-vanilla). The hold has NO timer of its own: its limit is the dial's own give-up
     * ({@link PredictDialLane#PREDICT_CONNECT_ATTEMPTS} attempts; the banner counts them); the
     * window's only other exit is the hold watchdog ({@link #ADVANCE_STALE_TEARDOWN_MILLIS} of stale
     * advance = abandoned), and ESC leaves at any time. The served statics ({@link #pinServedStatics})
     * are taken at the hold's start, so they outlive an ESC'd hold by at most that watchdog bound, as on
     * the served path. Writers: init (set; reset at every init), the bind (clear), the poll's
     * superseded arm and teardown (clear, under the class lock); volatile for the cross-thread clear.
     */
    private volatile boolean lateAttachHold;
    /** TRUE while the hold still polls; FALSE once the dial gave up or ended (the window stays parked
     *  and held open, ESC leaves), the bind consumed it, or teardown ended the window. */
    private volatile boolean lateAttachPolling;
    /** Wall-clock start of the hold (the bind / stop rows' wait length). */
    private long lateAttachSinceMillis;

    @Override
    public void init(CombatEngineAPI engine) {
        teardownActive();
        CcClientModPlugin.noteClaimSettled(); // W13 leg 2c: a fresh init voids any stale claim banner state
        CcClientModPlugin.noteRoundStartedWithoutSession(false);
        // The late-attach hold flags start clear every init
        // (a stale global TRUE would key the hold wording for a LOCAL window).
        CcClientModPlugin.noteLateAttachHold(false);
        lateAttachHold = false;
        lateAttachPolling = false;
        this.engine = engine;
        HullCapBound.resetReplicaFallback(); // The static slot never outlives its combat
        ObserverFrameDump.registerIfArmed(engine, this); // no-op unless observer + frameDir
        PlayerCameraLock.registerIfArmed(engine); // no-op unless -Dcc.watch.cameraLockPlayer=true (rig windows)
        MarkFrameRing.registerIfArmed(engine, this); // no-op unless -Dcc.markframes.dir armed at load
        this.presenter = new AuthoritativePresenter(engine, AuthoritativePresenter.SHOT_LOG_MAX);
        this.presenter.setGodMirror(godMirror); // The kill blow releases the mirror first
        this.fuseMeter = new FuseBurstMeter(); // class pre-warmed at app load
        // I.10: claim the active slot BEFORE the session null-return, so a SERVERLESS
        // combat (no predict session — the verify/no-feed family) still gets torn down at combat end / the
        // next init(): the servo modifier comes off the dying engine and the final teardown log block lands.
        // teardownActive() is null-safe on session/netThread.
        active = this;
        // Bind the client onFire birth sink BEFORE the session-null return —
        // a serverless window stays bound-and-inert (onOwnFireBirth returns on ownShip==null),
        // and teardownActive's unbind stays symmetric with this claim. init runs on the
        // combat/render thread: that thread IS the sink's delivery-thread reference.
        ClientFireBirthSink.bind(this, Thread.currentThread());
        // Same claim/thread, second consumer — the CcWrap* weapon-effect
        // wrappers' replica predicate goes live only while a combat window is bound.
        CcReplicaEffectGate.bind(this, Thread.currentThread());
        // Unlabeled-reel echo: the
        // resolved predict.* lane modes in ONE line, so any reel names the knob state it ran under.
        PilotWatchLog.info("predict lanes: eventReplay=" + EVENT_REPLAY_ENABLED
                + " foeEngineState=" + FOE_ENGINE_STATE_MODE
                + " weaponState=" + WEAPON_STATE_MODE
                + " weaponGlow=" + WeaponGlowLane.MODE
                + " ownEngineState=" + OWN_ENGINE_STATE_MODE
                + " foeSystemState=" + FOE_SYSTEM_STATE_MODE
                + " ownBayMint=" + OWN_BAY_MINT_MODE //
                + " fighterHud=" + FighterWingHudLane.MODE //
                + " foeManeuver=" + FOE_MANEUVER_MODE
                + " foeFlux=" + FOE_FLUX_MODE
                + " foeAutofire=" + FOE_AUTOFIRE_MODE
                + " foeShield=" + FOE_SHIELD_MODE
                + " foeCloak=" + FOE_CLOAK_MODE
                + " ownFireLock=" + FireLaneCoordinator.OWN_FIRE_LOCK_MODE
                + " ownBeamCycle=" + FireLaneCoordinator.OWN_BEAM_CYCLE_MODE
                + " dischargeReconcile=" + FireLaneCoordinator.DISCHARGE_RECONCILE_MODE
                // The watch vehicle's apply assert greps THIS token — the
                // announce is unconditional (pre-session-null), one line per window.
                + " ownMissile=" + OwnMissileHandoffLane.OWN_MISSILE_MODE
                + " ownDetDamage=" + OwnDetLane.MODE
                + " ownArmorReconcile=" + ArmorReconcileLane.OWN_MODE //
                + " armorCells=" + ArmorCellLane.MODE // v11 per-cell lane
                + " armorReplay=" + ArmorCellLane.REPLAY_MODE // wire-acked armor replay
                + " onFireDecl=" + (ClientFireBirthSink.installed() ? "installed" : "off"));
        // TRUE-VANILLA: mint the compare-instrument flag BEFORE the session-null
        // return (a vanilla: window never connects, so this is its only init path). One loud line so
        // every vanilla reel names its purity state.
        // re-arm the (re)entry fire-swallow here too — construction covers
        // the normal fresh-instance path; this covers any harness that re-inits an instance.
        swallowFireUntilMouseRelease = true;
        cc.starsector.server.match.MatchDescriptor vanillaDesc = MatchMissionBuilder.cachedDescriptor();
        vanillaWindow = vanillaDesc != null && vanillaDesc.shape().startsWith("vanilla:");
        if (vanillaWindow) {
            PilotWatchLog.info("predict vanilla-compare: PURE WINDOW (shape=" + vanillaDesc.shape()
                    + ") — serverless sim-touching lanes silenced (timeMult scrub OFF, servo apply OFF,"
                    + " shared-time pause guard OFF; census/meters/HUD stay — read-only)");
        }
        // The socket is opened at the Multiplayer Test click (CcClientModPlugin.dialPredictAtJoin;
        // before that at application load) — never here: not because combat forbids it (loader
        // B denies by class NAME only), but because the dial belongs to the player's join
        // action and its WELCOME must precede the mission build. We only consume the already-open
        // session and start the net thread; the FIRST intent the thread sends signals the server that
        // combat began. Wait a bounded moment for the background connect in the rare race where combat is
        // entered before it finishes — the claim wait; on the claim-at-play dial, a wait that expires with
        // the dial still in flight then engages the late-attach hold (below), which binds the WELCOME into
        // THIS round when it lands.
        CcClientModPlugin.markRoundEntered(); // W13: the post-round auto-reselect discriminator
        // W13 leg 2c: on the CLAIM-AT-PLAY lane
        // (registry shape, pilot posture) THIS is where the dial happens — Play IS the claim; the
        // click only probed. The bounded wait below widens to cover the HELLO horizon (the combat
        // fade hides most of it, measured on the proof flight); the legacy lanes (1v1 click dial,
        // observer) keep the ~1 s race wait byte-identical.
        boolean claimDial = CcClientModPlugin.dialPredictAtPlay();
        int waitPolls = claimDial ? Math.max(10, CLAIM_WAIT_MILLIS / 100) : 10;
        long waitT0 = System.currentTimeMillis();
        PredictSession s = CcClientModPlugin.predictSessionForMissionInit();
        for (int i = 0; s == null && i < waitPolls; i++) { // legacy: up to ~1s (10 * 100ms); claim: CLAIM_WAIT_MILLIS
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            s = CcClientModPlugin.predictSessionForMissionInit();
        }
        if (claimDial) {
            PilotWatchLog.info("predict init: CLAIM AT PLAY dial -> " + (s != null ? "WELCOME landed" : "NO session")
                    + " after " + (System.currentTimeMillis() - waitT0) + " ms (bound " + (waitPolls * 100)
                    + " ms, knob cc.predict.claimWaitMillis)");
        }
        CcClientModPlugin.noteRoundStartedWithoutSession(s == null); // the banner's "still connecting" key
        s = c2GuardAllows(s);
        if (INTENT_SOURCE != null) {
            // Load the recording BEFORE the net thread exists, so a bad file aborts
            // loudly here instead of surfacing as a confusing mid-exchange failure. Above the
            // session-null arm: a late-attach bind runs from advance, where a load throw would be a
            // Fatal — so a session-less window loads it too (a rig-only knob; shipped kits never set it).
            try {
                CcwrIntentSourceReader.Recording rec = CcwrIntentSourceReader.read(INTENT_SOURCE.ccwrPath());
                if (rec.frames.isEmpty()) {
                    throw new java.io.IOException("CCWR recording has no UP frames: " + INTENT_SOURCE.ccwrPath());
                }
                if (INTENT_SOURCE.skipFrames() >= rec.frames.size()) {
                    throw new java.io.IOException("intentSource skip=" + INTENT_SOURCE.skipFrames()
                            + " out of range for a " + rec.frames.size() + "-frame recording");
                }
                this.intentSourceFrames = rec.frames;
                this.intentSourceCursor = INTENT_SOURCE.skipFrames();
                PilotWatchLog.info("predict intentSource: ACTIVE ccwr=" + INTENT_SOURCE.ccwrPath()
                        + " upFrames=" + rec.frames.size() + " skip=" + INTENT_SOURCE.skipFrames()
                        + " downRecords=" + rec.recordsDown + (rec.truncatedTail ? " truncatedTail=true" : "")
                        + " -> input-poll VALUES come from the recording; session/predict/reconcile stay REAL");
            } catch (java.io.IOException e) {
                PilotWatchLog.error("predict intentSource: recording load FAILED -> combat init ABORTED "
                        + "(a synthetic seat must fail loud, never silently fly neutral)", e);
                throw new IllegalStateException("cc.predict.intentSource recording load failed: " + e, e);
            }
        }
        if (s == null) {
            // connect-fail hold-open: a registry shape fields only the own
            // seat locally, so with no session the enemy side stays empty forever and the native
            // mission flow would declare a frame-0 victory over a fight that never happened.
            // Hold the combat open (a registry shape never restores it — its stream-end restore
            // skips registry shapes, and the late-attach hold below may still bind a stream into
            // this window; ESC is the exit) and suppress the native "enemy fleet is defeated"
            // one-shot (seam map = StartBannerGuard); PredictFailBannerCombatPlugin owns the
            // player-facing messaging. Non-registry shapes (1v1, vanilla:) field both sides
            // locally — left untouched.
            String failShape = vanillaDesc != null ? vanillaDesc.shape() : "";
            if (MatchMissionBuilder.isRegistryShape(failShape)) {
                connectFailHoldOpen = true;
                engine.setDoNotEndCombat(true);
                StartBannerGuard.seed(engine);
                PilotWatchLog.info("predict init: connect-fail HOLD-OPEN engaged (shape=" + failShape
                        + ") -> native frame-0 victory suppressed, exit via ESC");
            }
            // The LATE-ATTACH HOLD — the claim-at-play dial
            // is still in flight past the claim wait, so park the placeholder inert (advanceBody), take
            // the served statics NOW (before any hull advances: a carrier placeholder must never mint
            // local wings) with the hold watchdog as their restore owner, and bind the WELCOME into
            // THIS round when it lands (pollLateAttachHold). connectFailHoldOpen is always TRUE under
            // the claim dial (no knob turns it off); the conjunct keeps a non-registry window out.
            if (connectFailHoldOpen && LateAttach.decide(claimDial, false, CcClientModPlugin.predictDialInFlight(),
                    CcClientModPlugin.predictConnectGaveUp()) == LateAttach.Decision.HOLD) {
                long holdT0 = System.currentTimeMillis();
                lateAttachHold = true;
                lateAttachPolling = true;
                lateAttachSinceMillis = holdT0;
                CcClientModPlugin.noteLateAttachHold(true);
                pinServedStatics();
                lastAdvanceMillis = holdT0; // the hold watchdog's first wake never reads a zero stamp
                startHoldWatchdog();
                PilotWatchLog.info("predict init: LATE-ATTACH HOLD (dial attempt " + CcClientModPlugin.predictDialAttempt()
                        + " of " + CcClientModPlugin.predictDialAttempts() + ") -> placeholder parked + inert;"
                        + " binds on WELCOME; exits: the dial's give-up, ESC, the stale-advance watchdog");
            }
            PilotWatchLog.info("predict init: NO app-load predict session (CcClientModPlugin.predictSession()==null "
                    + "after wait) -> " + (lateAttachHold
                            ? "window HELD for the dial (placeholder parked + inert, no reconcile until the bind)"
                            : "ships left locally driven (window stays LOCAL VANILLA, no reconcile)"));
            return;
        }
        pinServedStatics();
        bindSession(s);
    }

    /**
     * The C2 race guard, extracted verbatim so init and the
     * late-attach bind run the same decision. Returns {@code s} when it may bind, null when the guard
     * refuses (or {@code s} was null). On the claim-at-play build the built own id is 0, so the guard
     * never refuses there; it still protects the legacy builds and clears the latch on a clean bind.
     */
    private static PredictSession c2GuardAllows(PredictSession s) {
        if (s != null) {
            long built = MatchMissionBuilder.lastBuiltOwnShipId();
            if (built != 0L && s.shipId() != built) {
                // C2 race guard: the mission was
                // built on the AUTO fallback BEFORE a late WELCOME assigned the seat — binding
                // now would drive a hull this fleet did not field (a mid-round hull swap).
                // Fail-closed: refuse the bind THIS round; the next mission build resolves the
                // seeded seat and binds normally. PredictFailBannerCombatPlugin presents it
                // (the third key); the kit README already says wait at the menu for GO.
                FallbackAnnounce.announceOnce("auto-seat-stale-build",
                        "session seat=" + s.shipId() + " but this mission fielded shipId="
                        + built + " -> session NOT bound this round; leave to the menu and"
                        + " re-enter the mission to fly the assigned seat");
                CcClientModPlugin.setAutoSeatStaleBuild(true);
                s = null;
            } else {
                CcClientModPlugin.setAutoSeatStaleBuild(false); // a clean bind clears the latch
            }
        }
        return s;
    }

    /**
     * Bind {@code s} into THIS window — init's served tail,
     * extracted verbatim so the healthy init and the late-attach hold's WELCOME run one wiring: the
     * three engine-level listeners, the session + registry + claim wiring, the net (+ duplex writer)
     * threads. Runs once per window on the render thread: from init, or from advanceBody the frame the
     * hold's WELCOME lands (that frame pays the descriptor walk; the bind row logs its ms). NOT here:
     * the served statics ({@link #pinServedStatics} — the hold takes them at init, before any hull
     * advances) and INTENT_SOURCE (init — a load throw from advance would be a Fatal).
     */
    private void bindSession(PredictSession s) {
        // The engine-wide hulk overkill veto — registered once per combat init,
        // server-driven windows only (a serverless window keeps vanilla local fracture, like the
        // bindShips-side breakProb suppression that also only runs with a live session). The
        // listener manager dies with this combat engine, so there is nothing to unregister; match
        // resets keep the same engine + listener.
        try {
            engine.getListenerManager().addListener(new FractureFamilyCoordinator.HulkOverkillVeto(fractures));
        } catch (Throwable t) {
            PilotWatchLog.error("predict init: hulk overkill-veto registration FAILED"
                    + " (local corpse overkill may diverge this combat)", t);
        }
        // The ballistic lane's
        // own-hit ring feed, ONE engine-level DamageTakenModifier registered beside the veto (same
        // once-per-combat-init, served-window-only site). Replaces the per-cap feeds, which missed a
        // hit on a foe MODULE (its own ShipAPI; the parent's listeners never see it) and on a
        // cap-less remote. Own try so a veto failure cannot skip it; logs once (init-once site).
        try {
            engine.getListenerManager().addListener(hullCap.new OwnMissileHitFeed());
        } catch (Throwable t) {
            PilotWatchLog.error("predict init: own-missile-hit feed registration FAILED"
                    + " (ballistic END sibling refusal blind this combat: a sibling END may present)", t);
        }
        // The dmg-shot-out KILLER feed — ONE engine-global
        // DamageListener (MissileKillerFeed, owned by the witness) beside the own-missile-hit feed: same
        // once-per-combat-init, served-window-only site; own try so a sibling failure cannot skip it; logs
        // once. The manager dies with this engine (nothing to unregister); match resets keep both.
        try {
            engine.getListenerManager().addListener(dmgWitness.missileKillerFeed());
        } catch (Throwable t) {
            PilotWatchLog.error("predict init: missile killer feed registration FAILED"
                    + " (dmg-shot-out killer= reads none this combat)", t);
        }
        this.session = s;
        rapp.seedFromSession(s); // The lane's retired epoch = the session's (it outlives a mission)
        voiceHud.onSessionBound(s); // Settings file -> voice BEFORE the net loop can admit
        // Engage the remote-entity registry for a loaded
        // bots-shape descriptor (D-0 canonicalizes bots:0:N -> bots:N, so startsWith covers both) AND
        // for the N-peer shapes seats:K / gauntlet:N / teams:S:N — every non-own descriptor id
        // pre-registers and spawns at first wire sight (which for SEAT rows IS late-join adopt AND
        // mid-stream entity-appear: an unoccupied seat's hull is ABSENT until claim⇒fly-in).
        // teams: joined the key list afterwards (
        // its SEAT rows split across two sides + explicit AI rows — npeer semantics; the dense id-contract
        // walk below reads roster rows directly, so the two-sided layout needs no special case).
        // 1v1/unset leaves the registry empty and registryShape false — the single-foe path runs
        // untouched.
        cc.starsector.server.match.MatchDescriptor desc = MatchMissionBuilder.cachedDescriptor();
        if (desc != null) {
            // The live kit descriptor's rules.deployCr — the CR every
            // client-local spawn/adopt applies (field javadoc carries the mint-path map).
            descriptorDeployCr = desc.deployCr();
        }
        String descShape = desc != null ? desc.shape() : "";
        npeerShape = MatchMissionBuilder.isNpeerShape(descShape);
        botsShape = descShape.startsWith("bots");
        registryShape = botsShape || npeerShape;
        // W13 leg 2c: the mission fielded NO own hull (claim-at-play build, built==0)
        // while the WELCOME assigned a real seat -> the seat hull fields at the FIRST alive wire
        // sight of the own id (claimOwnSeatAtPose), re-pointing the player from the engine's
        // placeholder pod. Observer (seat 0) and legacy builds (built != 0) never arm this.
        claimPending = registryShape && s.shipId() != 0L && !ObserverMode.ON
                && MatchMissionBuilder.lastBuiltOwnShipId() == 0L;
        claimAttempts = 0;
        MatchMissionBuilder.noteSessionSeat(s.shipId()); // the mission-relative lanes' seat under a zero-hull build
        if (claimPending) {
            CcClientModPlugin.noteClaimPending(s.shipId());
            PilotWatchLog.info("predict init: CLAIM AT PLAY -> seat " + s.shipId() + " assigned, no own hull"
                    + " fielded at build; the player rides the engine placeholder pod until the own wire id"
                    + " is first seen alive, then the seat hull fields at its pose (W13 leg 2c)");
        }
        if (registryShape) {
            // Dense id-contract walk (ids 1..N in roster build order × count): descriptor side +
            // variant per wire id — reads the rows DIRECTLY, so SEAT rows count like any other (kills
            // the old side-0 SEAT-exclusion asymmetry and its dense-offset arithmetic, D5).
            java.util.Map<Long, Integer> descSideById = new java.util.TreeMap<Long, Integer>();
            long id = 0L;
            for (cc.starsector.server.match.MatchDescriptor.RosterRow row : desc.roster()) {
                for (int i = 0; i < row.count(); i++) {
                    id++;
                    variantById.put(Long.valueOf(id), row.variantId());
                    descSideById.put(Long.valueOf(id), Integer.valueOf(row.side()));
                }
            }
            long total = id;
            Integer ownRowSide = descSideById.get(Long.valueOf(s.shipId()));
            ownDescSide = ownRowSide != null ? ownRowSide.intValue() : 0;
            int sameSideTotal = 0;
            int otherSideTotal = 0;
            for (long eid = 1; eid <= total; eid++) {
                boolean sameSide = descSideById.get(Long.valueOf(eid)).intValue() == ownDescSide;
                if (sameSide) { sameSideTotal++; } else { otherSideTotal++; }
                if (eid == s.shipId()) {
                    continue; // the own seat — flown by native player control, never registry-bound
                }
                if (!sameSide && primaryFoeId < 0) {
                    primaryFoeId = eid; // fixed HUD/witness/parity anchor (bots: == old botsSide0+1)
                }
                remoteEntities.put(Long.valueOf(eid), new RemoteEntity(eid, sameSide ? 0 : 1));
            }
            remoteUnbound = remoteEntities.size();
            // Take the descriptor's STATION rows (engine seam
            // getVariant(id).isStation()) — ANY side. Under DEFEND the fortress is an own-side
            // ally: MatchMissionBuilder never mission-fields an own-side station (a station in
            // the local side's reserves sets isDeployedStation, which kills the seat's
            // reserves==1 auto-deploy and pops the modal
            // deploy dialog), so its base rides spawnRemoteEntities and the lane adopts
            // it from the registry entry. Only a station row that IS the own seat refuses
            // (flagship law).
            stations.acceptDescriptorRows(variantById, descSideById, ownDescSide, s.shipId());
            PilotWatchLog.info("predict bots registry: ENGAGED shape=" + descShape
                    + " side0=" + sameSideTotal + " side1=" + otherSideTotal
                    + " remoteEntities=" + remoteEntities.size() + " ownId=" + s.shipId()
                    + " ownDescSide=" + ownDescSide + " primaryFoe=" + primaryFoeId
                    + (ownRowSide == null ? " (ERROR: own id has NO descriptor row — presenting as side 0)" : "")
                    + (botsShape && s.shipId() != 1L ? " (WARN: seat contract expects ownId=1)" : ""));
            // Spawn-on-sight: the bots-shape client fields
            // ONLY the seat ship locally — every remote body is spawned at first wire sight by
            // spawnRemoteEntities, so the local enemy side is EMPTY until the first snapshot lands
            // and the real-window mission would otherwise conclude (victory) before the first spawn.
            // The headless bench already runs under doNotEndCombat, so this is window-only in effect.
            // Known consequence (recorded): the bots mission never self-ends — the owner exits via ESC.
            engine.setDoNotEndCombat(true);
            // Frame-0 seed: wave-empty side-1 satisfies isEnemyInFullRetreat()
            // before the first plugin advance (seam map = StartBannerGuard).
            StartBannerGuard.seed(engine);
        }
        // The engine must never END the combat on a mid-match death — a
        // round-1 kill would run the native end-of-mission flow (and tear down the persistent socket)
        // BEFORE the MRST boundary arrives. Restored one-shot in advance() when the stream ends, so
        // the FINAL round's victory/defeat flow still runs; subsumes the bots-shape set above (which
        // deliberately keeps its never-self-ends behavior — the restore skips botsShape).
        engine.setDoNotEndCombat(true);
        // Arm the staleness stamp before the writer exists, so the watchdog's first
        // wake can never read a zero stamp as a 50-year staleness.
        this.lastAdvanceMillis = System.currentTimeMillis();
        this.running = true;
        this.netThread = new Thread(this::runNetLoop, "cc-predict-" + s.shipId());
        this.netThread.setDaemon(true);
        this.netThread.start();
        ChatLane.bindEditorLane(chat); // the drain runs from here on: the editor's host sign-in may deposit
        if (DUPLEX) {
            // The reader thread above stops sending (runNetLoop's send block is
            // welded-only); this writer sends one intent per render-frame deposit + heartbeat floor.
            this.writerRunning = true;
            this.writerThread = new Thread(this::runWriterLoop, "cc-predict-w-" + s.shipId());
            this.writerThread.setDaemon(true);
            this.writerThread.start();
        }
        PilotWatchLog.info("predict bind: consumed predict session shipId=" + s.shipId()
                + " group=" + s.selectedWeaponGroup() + " -> net thread started"
                + (DUPLEX ? " + duplex writer thread (cc.predict.duplex=true)" : ""));
    }

    /**
     * The two process-wide served statics, taken before the
     * first ship advance — on the served path (just ahead of {@link #bindSession}) AND at the start of
     * the late-attach hold (a carrier placeholder held inert must still never mint local wings that the
     * adopt-in-place would keep). Only on a window with a restore owner: a served window (the writer
     * watchdog, the stream-end / combat-over teardown, the next init) or a held one (the hold watchdog,
     * {@link #startHoldWatchdog}); a plain connect-fail hold-open / serverless window has none, so a
     * pin taken there would outlive the window — a later vanilla mission in this JVM would field no
     * fighters and a single-player Ziggurat would go moteless.
     *
     * <p>The vanilla mote generator pinned off. The
     * engine's bay-advance kill switch latched BEFORE any hull spawns (see OWN_BAY_MINT_MODE), with its
     * live mirror {@link #bayKillSwitchLatched} written beside it — the two move together, and
     * teardownActive clears both.
     */
    private void pinServedStatics() {
        moteReplica.tick();
        if (OWN_BAY_MINT_SUPPRESS) {
            com.fs.starfarer.combat.entities.ship.A.C.REFIT_MODE_ADVANCE = true;
            bayKillSwitchLatched = true; // the live mirror the own-carrier lock skip reads
            PilotWatchLog.info("predict own-bay-mint: REFIT_MODE_ADVANCE latched TRUE for this window"
                    + " (every local launch-bay advance OFF -> no local wing mint/replacement on any hull;"
                    + " fighters arrive server-born; pilot controls untouched; HUD wing panel fed by"
                    + " FighterWingHudLane)");
        }
    }

    /**
     * The late-attach hold's restore owner — the session-less
     * twin of the duplex writer's stale-advance watchdog. A held window has no writer thread, and ESC
     * abandons it with no engine callback ({@code isCombatOver} never latches under the hold-open's
     * doNotEndCombat), so without this the served statics taken at the hold's start would leak into
     * the next vanilla combat. Wakes every {@link #HEARTBEAT_FLOOR_MILLIS} ms; tears down once
     * {@code advance()} is stale past {@link #ADVANCE_STALE_TEARDOWN_MILLIS} (the existing bound —
     * the statics outlive an ESC'd hold by at most that, as on the served path). Every decision rides
     * the class monitor teardownActive takes: a superseded window ({@code active != this}: the next
     * init's teardown already restored) or a bound one (session published under the same monitor; the
     * served restore owners take over) stands down, so this thread can never tear down another window.
     */
    private void startHoldWatchdog() {
        Thread w = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(HEARTBEAT_FLOOR_MILLIS);
                } catch (InterruptedException e) {
                    PilotWatchLog.info("predict hold-watchdog: interrupted -> exits (the next init's"
                            + " teardown restores the statics)");
                    return;
                }
                synchronized (PredictReconcileCombatPlugin.class) {
                    if (active != this || session != null) {
                        return;
                    }
                    long sinceAdvance = System.currentTimeMillis() - lastAdvanceMillis;
                    if (sinceAdvance > ADVANCE_STALE_TEARDOWN_MILLIS) {
                        PilotWatchLog.info("predict hold-watchdog: advance() stale " + sinceAdvance
                                + " ms in the late-attach hold -> abandoned-window teardown (bay kill switch"
                                + " + mote pin restored; the hold never binds after this)");
                        try {
                            teardownActive();
                        } catch (Throwable t) {
                            PilotWatchLog.error("predict hold-watchdog: teardown threw (statics may stay"
                                    + " pinned until the next init)", t);
                        }
                        return;
                    }
                }
            }
        }, "cc-predict-hold-watchdog");
        w.setDaemon(true);
        w.start();
    }

    /**
     * One frame of the late-attach hold's poll (advanceBody,
     * while held, unbound and still polling). The dial's give-up + in-flight flags are sampled BEFORE
     * the session: the connect thread publishes the session and sets its give-up flag before its
     * finally clears in-flight, so a publish landing between the reads reads BIND or WAIT, never STOP
     * with a live session ({@link LateAttach#step}). The bind's check-and-claim rides the class monitor:
     * a window the hold watchdog tore down ({@code active} null) or a superseded one never binds, and
     * the session is published under that monitor, so a watchdog waking mid-bind reads a bound window
     * and stands down. The bind itself ({@link #bindSession}) runs outside it. STOP (the dial gave up or
     * ended) latches the poll off: the window stays parked + held open, ESC leaves.
     */
    private void pollLateAttachHold() {
        boolean gaveUp = CcClientModPlugin.predictConnectGaveUp();
        boolean inFlight = CcClientModPlugin.predictDialInFlight();
        PredictSession late = CcClientModPlugin.predictSessionForMissionInit();
        LateAttach.Step step = LateAttach.step(late != null, inFlight, gaveUp);
        if (step == LateAttach.Step.BIND) {
            boolean mayBind;
            PredictSession bound = null;
            synchronized (PredictReconcileCombatPlugin.class) {
                mayBind = LateAttach.holdPollMayBind(active == this, session == null, lateAttachPolling);
                if (mayBind) {
                    bound = c2GuardAllows(late);
                }
                if (bound != null) {
                    session = bound;
                    lateAttachHold = false;
                    CcClientModPlugin.noteLateAttachHold(false);
                } else if (!mayBind) {
                    lateAttachHold = false; // torn down or superseded: the global flag is the active window's
                }
                lateAttachPolling = false;
            }
            if (bound != null) {
                CcClientModPlugin.noteRoundStartedWithoutSession(false);
                long bindT0 = System.currentTimeMillis();
                bindSession(bound);
                PilotWatchLog.info("predict late-attach: WELCOME after " + (bindT0 - lateAttachSinceMillis)
                        + " ms in the hold -> bound THIS round in " + (System.currentTimeMillis() - bindT0) + " ms");
            } else if (mayBind) {
                PilotWatchLog.info("predict late-attach: WELCOME seat refused by the stale-build guard"
                        + " -> parked + held open, not bound this round; ESC leaves");
            }
        } else if (step == LateAttach.Step.STOP) {
            lateAttachPolling = false;
            PilotWatchLog.info("predict late-attach: dial "
                    + (gaveUp || CcClientModPlugin.predictConnectGaveUp() ? "GAVE UP" : "ENDED")
                    + " after " + (System.currentTimeMillis() - lateAttachSinceMillis)
                    + " ms in the hold -> parked + held open; ESC leaves");
        }
    }

    /**
     * The net thread: the strict per-tick ping-pong with the server, off the render thread. Reads the
     * authoritative AWSS snapshot DOWN, publishes it for reconciliation, then sends the latest captured
     * human input UP. Exits cleanly at end of stream, or fail-closed on any I/O error (the game keeps
     * running; the ships simply stop being corrected and free-run on local prediction).
     *
     * <p>Duplex: under {@code cc.predict.duplex} this SAME loop is the READER half —
     * receive/demux → publish {@code latestSnapshot} → route MRST/EDSP/SNMS/GPEE exactly as welded —
     * and the send block below is skipped ({@link #runWriterLoop} owns every UP). Its end (clean EOF
     * or error) wakes the writer so it cannot outlive the stream.
     */
    /** Net-thread-only per-(entity,tier)
     * latest-wins chunk/fragment reassembly —
     * fed between the demuxed receive and the {@code latestSnapshot} publish; EDSP evicts,
     * MRST clears (both wrapped inline below, so eviction precedes the same batch's frame).
     * Engaged ONLY on the demux path: without EDSP consumption a merge could never evict, and
     * the bare pre-B3 read stays byte-identical (a chunking server serving an eventReplay-OFF
     * client is out-of-contract — the multi-round rule already requires the knob ON). */
    final cc.starsector.client.net.SnapshotChunkMerger chunkMerger = // WireMarkRows reads it
            new cc.starsector.client.net.SnapshotChunkMerger();

    /** One net-loop publish of the merger's per-entity tier ages, keyed by
     * the published frame's simTick. The merger is NET-thread-only; the walker runs on the RENDER
     * thread — it must never touch the merger, so ages cross threads ONLY through this immutable
     * stamp (volatile publish; the list is freshly allocated per publish, never mutated after). The
     * consumer tick-checks {@code simTick} against its snapshot — a mismatched (torn) read is a
     * benign no-age walk tick (fail-soft instrument; absent age = unmeasured in the differ). */
    private record AgeStamp(long simTick,
            java.util.List<cc.starsector.client.net.SnapshotChunkMerger.TierTicks> ticks) {
        cc.starsector.client.net.SnapshotChunkMerger.TierTicks byId(long entityId) {
            for (cc.starsector.client.net.SnapshotChunkMerger.TierTicks t : ticks) {
                if (t.entityId() == entityId) {
                    return t;
                }
            }
            return null;
        }
    }

    private volatile AgeStamp latestAges;

    /** Last re-admission generation consumed by the net loop (net thread only) —
     * when the session's {@code reAdmits()} advances past this, the blocked take just returned
     * from a re-HELLO re-admission and the merger's partial state belongs to the DEAD session. */
    private long reAdmitsSeen;

    /** Render thread only: last {@code session.reconnecting()} state presented —
     * edge-detected in {@link OwnReadoutLane#maintainNetReadout} for the arm/re-admit log lines. */
    boolean reconnectingShown;

    private void runNetLoop() {
        int recv = 0;
        int sent = 0;
        PilotWatchLog.info("predict runNetLoop: started shipId=" + (session != null ? session.shipId() : -1L));
        try {
            while (running) {
                if (STALL_READ != null && recv == STALL_READ[0]) {
                    // The deliberate application-layer slow reader (see
                    // STALL_READ). The server's gate may KICK this seat mid-sleep (socket aborted)
                    // — the next read then fails into the standard fail-closed free-run path.
                    PilotWatchLog.info("predict stallRead: SLEEPING " + STALL_READ[1]
                            + " ms before the read of exchange " + recv + " (cc.predict.stallRead)");
                    try {
                        Thread.sleep(STALL_READ[1]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    PilotWatchLog.info("predict stallRead: AWAKE -> resuming reads");
                }
                // With eventReplay ON, read demultiplexed (GPEE events routed to the
                // queue; EDSP despawns CONSUMED — the server-sent death-reason lane;
                // MRST CONSUMED — stashed here, published to the render thread below
                // strictly AFTER the same receive's AWSS landed in latestSnapshot, so the rebuild
                // always sees the NEW round's frame; the SNMS seat-name table stashed for
                // the render-thread wire-first apply); OFF keeps the pre-B3 bare-AWSS read
                // byte-identical (no demux — a multi-round server requires the eventReplay knob ON).
                cc.starsector.client.net.ClientMatchReset[] resetStash =
                        EVENT_REPLAY_ENABLED ? new cc.starsector.client.net.ClientMatchReset[1] : null;
                ServerSnapshotFrame snapshot = EVENT_REPLAY_ENABLED
                        ? session.receiveSnapshot(edgeEvents::enqueueEdgeEvent,
                                reset -> {
                                    chunkMerger.clear();
                                    resetStash[0] = reset;
                                },
                                despawn -> {
                                    chunkMerger.evict(despawn.entityId());
                                    edgeEvents.onDespawnEvent(despawn);
                                },
                                seatHud::onSeatNamesEvent,
                                awsd -> {
                                    // The AWSD delta+keyframe ship-lane fold
                                    // (the L3 lane when the server's snapDelta is ON) — the AWSS
                                    // publish block below, mirrored: ages BEFORE the frame (B5
                                    // tear contract), then publish + the net/s numerator.
                                    // Sticky decode-lane signal — any AWSD fold
                                    // ⇒ exactAmmoLane fails closed (hold-last hazard).
                                    fireLanes.awsdLaneSeen = true;
                                    // Every AWSD frame announces the sim-speed
                                    // (mid-round change + late-join adopt ride the same u16).
                                    clockServo.noteAnnouncedSimSpeed(awsd.simSpeedPermille());
                                    // MIRROR: v9 side-totals stash (null on v8).
                                    serverSideTotals = awsd.sideTotals();
                                    ServerSnapshotFrame folded = chunkMerger.onAwsdFrame(awsd);
                                    latestAges = new AgeStamp(folded.simTick(),
                                            chunkMerger.allTierTicks());
                                    latestSnapshot = folded;
                                    netFramesPublished++;
                                    // The interp feed mirrors the publish
                                    // (addOrFold inside — this seam repeats simTicks on
                                    // co-tick chunk folds).
                                    foeInterp.onPublish(System.nanoTime(), folded);
                                    // per-record armor cell-run delivery
                                    // (NET thread — the lanes' bounded inbox is the
                                    // cross-thread seam; cells never ride ServerEntityPose).
                                    long cellOwnId = session.shipId();
                                    for (cc.starsector.client.net.AwsdSnapshotReader.AwsdRecord
                                            rec : awsd.records()) {
                                        if (rec.cellRuns().isEmpty()) {
                                            continue;
                                        }
                                        java.util.List<ArmorCellLane.Run> cellRuns =
                                                new java.util.ArrayList<>(rec.cellRuns().size());
                                        for (cc.starsector.client.net.AwsdSnapshotReader.CellRun
                                                r : rec.cellRuns()) {
                                            cellRuns.add(new ArmorCellLane.Run(
                                                    r.startIdx(), r.values()));
                                        }
                                        if (rec.entityId() == cellOwnId) {
                                            ownDetLane.onCellRecord(rec.entityId(),
                                                    awsd.simTick(), cellRuns);
                                        } else {
                                            hullLane.onCellRecord(rec.entityId(),
                                                    awsd.simTick(), cellRuns);
                                        }
                                    }
                                    // Hand the fold's freshly-queued NACKs
                                    // to the send path (deposit here on the reader thread; the
                                    // drain rides strictly behind the next intent send).
                                    for (cc.starsector.client.net.SnapshotChunkMerger.PendingNack
                                            nack : chunkMerger.drainPendingNacks()) {
                                        synchronized (pendingAwsdNacks) {
                                            if (pendingAwsdNacks.size() >= AWSD_NACK_PENDING_MAX) {
                                                pendingAwsdNacks.pollFirst();
                                            }
                                            pendingAwsdNacks.addLast(
                                                    new int[] {nack.entityId(), nack.epoch()});
                                        }
                                    }
                                },
                                // The chat-aware overload — CHTD relays queue
                                // for the render thread (the leg-4 HUD drain consumes them).
                                chat::enqueueChatMessage,
                                // SNMR per-seat name records fold into the one wire-name
                                // map on this thread; the render thread applies on the dirty flag.
                                seatHud::onSeatNameRecordEvent,
                                // HKFR fracture edges queue on this thread; the render
                                // thread drains + applies them (maintainFractureFamilies).
                                fractures::onHulkFractureEvent,
                                // HKPS corpse-pose keyframes queue on this thread;
                                // the same render sweep one-shot-adopts them after the edges.
                                fractures::onHulkPoseEvent,
                                // HKCG cut-geometry records queue on this
                                // thread; the render sweep latches them BEFORE the edges so the
                                // paired ADD replays the server's exact cut.
                                fractures::onHulkCutGeometryEvent,
                                // RAPP roster appends queue on this thread;
                                // the render thread folds them into the replica registry BEFORE
                                // the spawn-on-sight gate (RosterAppendLane.drain).
                                rapp::enqueue)
                        : session.receiveSnapshot();
                if (snapshot == null) {
                    PilotWatchLog.info("predict runNetLoop: clean end of stream after recv=" + recv + " sent=" + sent);
                    rapp.streamEnded = true; // No reset can follow — mute the epoch valve
                    break;
                }
                if (session.reAdmits() != reAdmitsSeen) {
                    // The take above returned across a re-HELLO re-admission — the
                    // merger's per-entity partials are the DEAD session's (blackout-missed EDSPs
                    // would leave them un-evictable). Clear BEFORE this fresh session's first
                    // frame feeds it; the D4.5 late-join adopt path rebuilds off the baseline.
                    reAdmitsSeen = session.reAdmits();
                    chunkMerger.clear();
                    PilotWatchLog.info("predict reconnect: RE-ADMITTED (reAdmits=" + reAdmitsSeen
                            + ") -> merger cleared; late-join adopt resumes off the fresh"
                            + " session's baseline");
                }
                recv++;
                if (recv == 1) {
                    PilotWatchLog.info("predict runNetLoop: FIRST snapshot received simTick=" + snapshot.simTick()
                            + " entities=" + snapshot.entityCount());
                }
                if (EVENT_REPLAY_ENABLED) {
                    // Reassemble a chunked tick BELOW decode, BEFORE the publish —
                    // adopt/covering returns the decoded object itself (K=1 stays identical).
                    snapshot = chunkMerger.onFrame(snapshot);
                    // Publish the merger's tier ages BEFORE latestSnapshot so a render
                    // thread that sees the new frame sees its ages (the reverse tear = tick-check miss
                    // = benign no-age walk). eventReplay OFF ⇒ latestAges stays null (unmeasured).
                    latestAges = new AgeStamp(snapshot.simTick(), chunkMerger.allTierTicks());
                }
                latestSnapshot = snapshot;
                netFramesPublished++; // The net/s readout's numerator
                foeInterp.onPublish(System.nanoTime(), snapshot); // Feed mirrors the publish
                if (resetStash != null && resetStash[0] != null) {
                    // Publish-order contract (see pendingReset): latestSnapshot above IS the
                    // fresh round's first frame — the demux routed the MRST inline immediately before
                    // returning it — so pendingReset is set only now.
                    pendingReset = resetStash[0];
                    PilotWatchLog.info("predict match-reset: MRST received resetSeq=" + resetStash[0].resetSeq()
                            + " completedRound=" + resetStash[0].completedRoundIndex()
                            + " nextRound=" + resetStash[0].nextRoundIndex()
                            + " -> render-thread rebuild pending");
                }
                if (!DUPLEX) {
                    // Welded (v1) send half — byte-identical to pre-duplex. Under duplex every UP
                    // (intent + PDCL + intentSource substitution) moves to runWriterLoop (pt 10).
                    if (INTENT_SOURCE != null) {
                        applyIntentSourceFrame(); // Recorded values into the four fields; the send below is UNCHANGED
                    }
                    long droppedBefore = session.sendsDropped(); //
                    session.sendIntent(aimDeg, ownFacingDeg, heldControls, fireHeld, aimWorldX, aimWorldY,
                            liveSelectedGroup, liveAutofireMask, liveTargetWireId, fireEdgeSeq,
                            liveActiveTubeOrdinal);
                    if (session.sendsDropped() == droppedBefore) {
                        sent++;
                    }
                    if (sent == 1) {
                        PilotWatchLog.info("predict runNetLoop: FIRST intent sent aimDeg=" + aimDeg
                                + " ownFacingDeg=" + ownFacingDeg + " held=" + heldControls + " fire=" + fireHeld);
                    }
                    // Declared discharges ride the up lane strictly BEHIND this tick's intent
                    // (the only place they are ever sent — see sendPendingFireDeclarations).
                    sendPendingFireDeclarations();
                    sendPendingAwsdNacks(); // SNNK rides the same behind-intent slot
                    chat.sendPendingChats(); // CHTU rides the same behind-intent slot
                }
            }
        } catch (Throwable t) {
            if (running) {
                PilotWatchLog.error("predict runNetLoop: exchange FAILED after recv=" + recv + " sent=" + sent
                        + " -> net loop ENDING; ships stop being reconciled (free-run on local prediction)", t);
            } else {
                PilotWatchLog.info("predict runNetLoop: closed by teardown after recv=" + recv + " sent=" + sent
                        + " (" + t.getClass().getSimpleName() + ")");
            }
        } finally {
            running = false;
            streamEnded = true; // Render thread restores native end-of-combat (one-shot)
            if (DUPLEX) {
                // pt 10 lifecycle: the reader ended (EOF or error) — wake the writer so it exits
                // before its next send instead of heartbeating into a dead stream.
                synchronized (intentSignal) {
                    intentSignal.notifyAll();
                }
            }
            if (INTENT_SOURCE != null) {
                // Counter line (house discipline): how much of the recording this seat re-spoke.
                PilotWatchLog.info("predict intentSource: replayed=" + intentSourceReplayed
                        + " neutral=" + intentSourceNeutral + " of "
                        + (intentSourceFrames != null ? intentSourceFrames.size() : 0)
                        + " recorded frames (skip=" + INTENT_SOURCE.skipFrames() + ")");
            }
            // The version-13 HELD / r2 meters live on the datagram session
            // only (no PredictSession default); null = another session class, its tokens print 0
            cc.starsector.client.net.PredictReconcileDatagramSession hs13Meters =
                    session instanceof cc.starsector.client.net.PredictReconcileDatagramSession
                            ? (cc.starsector.client.net.PredictReconcileDatagramSession) session : null;
            PilotWatchLog.info("predict runNetLoop: ended recv=" + recv + " sent=" + sent
                    + " fire_decl_sent=" + fireDeclSent + " fire_decl_dropped=" + fireDeclDropped + " fire_decl_autofire_skipped=" + fireDeclAutofireSkipped
                    + chunkMerger.teardownTokens()
                    + " awsd_nack_tx=" + awsdNackTx
                    // W5-D5: the B4 re-admit counters had accessors but zero
                    // consumers — appended fields only (grammar rule), all internal numerics.
                    + (session != null
                            ? " re_hello_episodes=" + session.reHelloEpisodes()
                                    + " re_hellos_sent=" + session.reHellosSent()
                                    + " welcome_duplicates=" + session.welcomeDuplicates()
                                    + " nonce_escalations=" + session.reconnectNonceEscalations()
                                    + " re_admits=" + session.reAdmits()
                                    + " bye_echoes_swallowed=" + session.reconnectByeEchoesSwallowed()
                                    // Appended fields only (grammar rule)
                                    + " prev_gen_adopts=" + session.prevGenerationAdopts()
                                    + " late_gen_adopts=" + session.lateGenerationAdopts()
                                    + " socket_recreates=" + session.socketRecreates()
                                    + " sends_dropped=" + session.sendsDropped()
                                    // Appended at line END (grammar rule)
                                    + " attach_replays=" + session.attachFramesReplayed()
                                    // Appended at line END (grammar rule)
                                    + " connect_hello_retransmits=" + session.connectHelloRetransmits()
                                    + " connect_pre_admit_drops=" + session.connectPreAdmitDrops()
                                    // Appended at line END (grammar rule); counts only
                                    + " connect_cookie_challenges=" + session.connectCookieChallenges()
                                    + " re_hello_cookie_challenges=" + session.reHelloCookieChallenges()
                                    + " re_hello_cookie_givebacks_suppressed=" + session.reHelloCookieGiveBacksSuppressed() //
                                    // Appended at line END (grammar rule);
                                    // counts only, 0 on any other session class (hs13Meters above)
                                    + " re_hello_held_cap=" + (hs13Meters != null ? hs13Meters.reHelloHeldSealCap() : 0L)
                                    + " re_hello_held_budget=" + (hs13Meters != null ? hs13Meters.reHelloHeldBudget() : 0L)
                                    + " re_hello_sibling_seals=" + (hs13Meters != null ? hs13Meters.reHelloSiblingSeals() : 0L)
                                    + " connect_held=" + (hs13Meters != null ? hs13Meters.connectHeld() : 0)
                            : ""));
            // BACKSTOP only — this fires the instant the stream ends, BEFORE the render
            // thread consumes the tail snapshots (a run measured kills=1 here while all 4 per-kill
            // lines landed on later frames). The settled census is the render-side post-feed emit in
            // advance(); this one exists for paths where the render loop stops stepping.
            logRemoteCensusBackstop("streamEnd");
        }
    }

    /**
     * The synthetic seat's input substitution,
     * called by the net thread once per exchange immediately before {@code session.sendIntent(...)}:
     * overwrite the four input-value fields ({@link #aimDeg}/{@link #ownFacingDeg}/
     * {@link #heldControls}/{@link #fireHeld}) with the next recorded UP frame's values — a real
     * human's recorded stream, carried verbatim — while everything below those values stays the
     * REAL predict client path. shipId/frameSeq/weapon group are NOT taken from the recording (the
     * session sends its own identity + its own snapshot-tick echo). After exhaustion: NEUTRAL — no
     * keys, no fire, facings keep the last recorded values still sitting in the fields (the ghost
     * precedent; the idled pollers cannot overwrite them).
     */
    private void applyIntentSourceFrame() {
        List<PilotIntentFrame> frames = intentSourceFrames;
        if (intentSourceCursor < frames.size()) {
            PilotIntentFrame f = frames.get(intentSourceCursor++);
            aimDeg = f.cursorFacingDeg();
            ownFacingDeg = f.ownShipFacingDeg();
            heldControls = f.heldControls();
            fireHeld = f.fireHeld();
            aimWorldX = f.aimWorldX(); // Recorded aim world point re-spoken verbatim
            aimWorldY = f.aimWorldY();
            intentSourceReplayed++;
        } else {
            heldControls = 0;
            fireHeld = false;
            intentSourceNeutral++;
            if (!intentSourceExhaustLogged) {
                intentSourceExhaustLogged = true;
                PilotWatchLog.info("predict intentSource: recording EXHAUSTED after " + intentSourceReplayed
                        + " replayed frames -> NEUTRAL tail (no keys, no fire, facings held)");
            }
        }
    }

    /**
     * Duplex client half: the WRITER loop — one intent UP per render-frame
     * deposit signal, independent of DOWN arrivals, so UP rate = local frame rate at any RTT (the
     * industry norm this arc lands: RTT affects staleness only, never delivery). Mechanics:
     *
     * <ul>
     * <li><b>Pacing:</b> waits on {@link #intentSignal} up to {@link #HEARTBEAT_FLOOR_MILLIS}; a
     * fresh deposit sends immediately, a timeout re-sends the last captured input (see the
     * heartbeat constant's javadoc — the pause-menu liveness floor, default-ON, exactly v1's
     * frozen-volatiles pause behavior). Coalescing to latest under socket backpressure is
     * structural: while a send blocks, later deposits overwrite the volatile fields and collapse
     * into one {@code intentFresh} signal.</li>
     * <li><b>frameSeq:</b> echoes the plugin's volatile {@link #latestSnapshot} AT SEND TIME (v1
     * semantics; the session's {@code lastSnapshot} is reader-thread state now — never read here;
     * the explicit-echo-tick {@code sendIntent} overload carries it).</li>
     * <li><b>PDCL ordering:</b> declarations drain strictly AFTER each intent send, on this one
     * writer thread — the same server-demux ordering contract as welded.</li>
     * <li><b>MRST gap quiesce</b> (client half of the server's pt-5 stale-UP story): while a reset
     * is pending (reader received MRST, render thread has not rebuilt yet) this loop HOLDS — no
     * intent or PDCL straddles the local teardown/rebuild; a boundary-raced frame already in
     * flight is the server barrier's job ({@code stale_ups}/{@code stale_pdcls}).</li>
     * <li><b>Lifecycle:</b> either loop's death stops the other — a reader end wakes this loop to
     * exit before its next send; a writer send failure closes the session so the reader's blocked
     * read fails out (mirror of the server gate's one-ladder-two-feeders rule). Teardown
     * interrupts AND joins this thread (bounded).</li>
     * <li><b>{@code cc.predict.stallWrite}</b> ({@link #STALL_WRITE}) sleeps before send index N —
     * the drill's stalled-writer-live-reader leg; {@code cc.predict.stallRead} keeps stalling the
     * reader only, which under duplex no longer delays UPs (the drill's other new leg).</li>
     * </ul>
     */
    private void runWriterLoop() {
        int sent = 0;
        PilotWatchLog.info("predict writerLoop: started (duplex) heartbeatFloorMs=" + HEARTBEAT_FLOOR_MILLIS);
        try {
            while (running && writerRunning) {
                boolean fresh;
                synchronized (intentSignal) {
                    if (!intentFresh) {
                        try {
                            intentSignal.wait(HEARTBEAT_FLOOR_MILLIS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt(); // teardown interrupt: exit below
                        }
                    }
                    fresh = intentFresh;
                    intentFresh = false;
                }
                if (!running || !writerRunning || Thread.currentThread().isInterrupted()) {
                    break;
                }
                // Advance-staleness watchdog (see ADVANCE_STALE_TEARDOWN_MILLIS):
                // an ESC-exit-to-title abandons the plugin with this loop still heartbeating the
                // seat — tear down (BYE, seat vacates, round ends clean) instead of leaking it
                // until the NEXT mission's init(). teardownActive is idempotent and skips the
                // self-join when called from this thread.
                long sinceAdvance = System.currentTimeMillis() - lastAdvanceMillis;
                if (sinceAdvance > ADVANCE_STALE_TEARDOWN_MILLIS && session != null) {
                    PilotWatchLog.info("predict writerLoop: advance() stale " + sinceAdvance
                            + " ms with a live session -> abandoned-combat teardown (ESC-exit"
                            + " seat-leak fix; BYE rides the kept-open mission exit, W5-D1)");
                    teardownActive();
                    break;
                }
                if (pendingReset != null) {
                    // MRST gap quiesce (javadoc above): the deposit is not lost — the volatiles keep
                    // the values; the post-rebuild advance() re-signals (or the heartbeat retries).
                    writerResetHolds++;
                    continue;
                }
                if (STALL_WRITE != null && sent == STALL_WRITE[0]) {
                    PilotWatchLog.info("predict stallWrite: SLEEPING " + STALL_WRITE[1]
                            + " ms before send " + sent + " (cc.predict.stallWrite)");
                    try {
                        Thread.sleep(STALL_WRITE[1]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    PilotWatchLog.info("predict stallWrite: AWAKE -> resuming sends");
                }
                PredictSession s = session;
                if (s == null) {
                    break; // teardown released the session between wakes
                }
                if (INTENT_SOURCE != null && fresh) {
                    // Under duplex: one recorded frame per FRESH deposit (≈ render
                    // cadence); a heartbeat re-sends the held values without advancing the cursor.
                    applyIntentSourceFrame();
                }
                ServerSnapshotFrame snap = latestSnapshot;
                long droppedBefore = s.sendsDropped(); // Count only wire sends
                s.sendIntent(aimDeg, ownFacingDeg, heldControls, fireHeld, aimWorldX, aimWorldY,
                        liveSelectedGroup, liveAutofireMask, liveTargetWireId, fireEdgeSeq,
                        liveActiveTubeOrdinal, snap != null ? snap.simTick() : -1L);
                if (s.sendsDropped() != droppedBefore) {
                    sendsGated++; // the transport's gate ate it (reconnecting/held/kicked)
                } else {
                    sent++;
                    if (!fresh) {
                        heartbeatSends++;
                    }
                }
                if (sent == 1) {
                    PilotWatchLog.info("predict writerLoop: FIRST intent sent aimDeg=" + aimDeg
                            + " ownFacingDeg=" + ownFacingDeg + " held=" + heldControls + " fire=" + fireHeld);
                }
                // Declared discharges ride the up lane strictly BEHIND this send's intent
                // — the single writer thread preserves the welded ordering contract verbatim.
                sendPendingFireDeclarations();
                sendPendingAwsdNacks(); // SNNK rides the same behind-intent slot
                chat.sendPendingChats(); // CHTU rides the same behind-intent slot
            }
        } catch (Throwable t) {
            if (running && writerRunning) {
                PilotWatchLog.error("predict writerLoop: send FAILED after sent=" + sent
                        + " -> closing session so the reader ends too (free-run on local prediction)", t);
                PredictSession s = session;
                if (s != null) {
                    s.close();
                }
            } else {
                PilotWatchLog.info("predict writerLoop: closed by teardown after sent=" + sent
                        + " (" + t.getClass().getSimpleName() + ")");
            }
        } finally {
            writerRunning = false;
            PilotWatchLog.info("predict writerLoop: ended sent=" + sent
                    + " sends_gated=" + sendsGated // Appended field (grammar rule)
                    + " heartbeat_sends=" + heartbeatSends + " reset_holds=" + writerResetHolds
                    + " fire_decl_sent=" + fireDeclSent + " fire_decl_dropped=" + fireDeclDropped + " fire_decl_autofire_skipped=" + fireDeclAutofireSkipped
                    + " awsd_nack_tx=" + awsdNackTx + markWire.censusTokens());
        }
    }

    /** The watch marker hotkey —
     * default F9 (LWJGL 67), {@code -Dcc.watch.markerKey} override; owner keybinds untouched
     * (events are read, never consumed). A press drops one uncapped tick-aligned
     * {@code owner-marker #n tick=...} line so a mid-watch "something weird" needs no
     * wall-clock guesswork to window. */
    /** fail-open to F9 (67) — a bare parseInt in static init would
     * turn a malformed -Dcc.watch.markerKey into ExceptionInInitializerError killing the whole
     * plugin; {@link KnobParse} keeps that fail-open AND announces the malformed case. */
    private static final int WATCH_MARKER_KEY =
            KnobParse.intOr("cc.watch.markerKey", 67, "cfg.watch.markerKey.unparseable");
    private int ownerMarkers;
    /** D-F9NOROW forensics: debug-only bounded key-poll sight, own file. */
    private final KeyPollSightLane keyPollSight = new KeyPollSightLane();

    /** Watch-end hotkey:
     *
     * F10 drops one {@code watch-end request}
     * row; the WATCH LAUNCHER tails the shared starsector.log for it and appends the SAME
     * {@code end-match} operator verb the ServerOpsGui button writes. The mod itself can never
     * write the operator file — the in-combat script SecurityManager forbids file access
     * (PilotWatchLog header); the pre-warmed log4j stream is the one open channel. Fixed key,
     * no knob (the no-new-client-knobs line); a {@code cc.watch.markerKey} rebound to 68 wins the
     * else-if and F10 stays a marker — acceptable, the GUI button still ends the watch. */
    private static final int WATCH_END_KEY = 68; // LWJGL KEY_F10

    /** Wave-skip hotkey:
     *
     * F11 drops one {@code wave-skip request} row;
     * the WATCH LAUNCHER tails it (the F10 bridge's second regex) and appends the
     * {@code skip-wave} operator verb — NOT wire (the F10 end-match precedent: operator file =
     * host-trust boundary, no seat authority, no schema). F11 is NOT unbound (the build-time
     * "unbound" claim was falsified by the owner's live report): vanilla's rebindable
     * hide-HUD toggle defaults to it ({@code CombatState.hideHud} flip in the misc-input loop,
     * which skips consumed events). So this key is handled in
     * {@link #processInputPreCoreControls} — log the bridge row, then CONSUME, and the vanilla
     * toggle never sees the press; {@code vanilla:*} control windows are exempt (no consume,
     * F11 keeps its vanilla meaning there). F9/F10 = 67/68, this rig's other verbs, stay
     * observed-only in {@link #advance} (no vanilla conflict reported across all watches;
     * F8 retired).
     * Unlike F10 the row can fire MANY times per match — the launcher bridge stays armed. */
    private static final int WAVE_SKIP_KEY = 87; // LWJGL KEY_F11
    /** F12 = the F11 bridge TWIN — the launcher regex bridges the
     *  {@code wave-pause request} row to the operator {@code pause-wave} toggle ({@code WaveSchedule}
     *  freezes its timer base); same debug gate, same consume, zero wire surface. */
    private static final int WAVE_PAUSE_KEY = 88; // LWJGL KEY_F12

    /** F9 receipt: text popped above your ship saying you marked a bug in the logs
     *
     * — the one own-ship floaty site (the F8 MODE label it shared the
     *  site with retired with the A/B toggle). {@code addFloatingTextAlways}
     *  (PUBLIC API, {@code CombatEngineAPI}) not {@code addFloatingText}: the plain call is
     *  settings-gated AND viewport-culled in the engine, so a pilot with damage floaties off
     *  would never see it; the trailing floats are the engine's own defaults (fade 1.0 /
     *  dwell 1.5 / 0) plus vanilla's 1.0 alpha term. Fail-soft, the log row is the record. */
    static void showOwnShipLabel(CombatEngineAPI engine, String text, java.awt.Color color) {
        try {
            ShipAPI labelShip = engine != null ? engine.getPlayerShip() : null;
            if (labelShip != null && labelShip.getLocation() != null) {
                engine.addFloatingTextAlways(labelShip.getLocation(), text, 60f,
                        color, labelShip, 0f, 0f, 1f, 1.5f, 0f, 1f);
            }
        } catch (Throwable ignore) {
            // label is a nicety; the flip/enroll/mark + log already landed
        }
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        // The WALL SPLIT: the plugin's own advance wall per
        // frame, stamped onto the frame ledger's row for this frame (adv_ms beside total_ms), so a
        // stutter read can say whether the hitch is ours BEFORE any per-lane timer exists.
        // Entry + exit stamps — the ledger splits the wall at its clock point
        // (measureFrameClock runs MID-advance) so each slot carries the wall inside ITS window.
        frameLedger.noteAdvanceEntry(System.nanoTime());
        try {
            advanceBody(amount, events);
        } finally {
            frameLedger.noteAdvanceExit(System.nanoTime());
        }
    }

    private void advanceBody(float amount, List<InputEventAPI> events) {
        // Stamp FIRST — before every early-return incl. the paused one (paused
        // frames still deliver advance(0)), so the writer-side staleness watchdog only ever
        // fires on a genuinely abandoned combat (ESC-exit to title), never on pause/menus.
        lastAdvanceMillis = System.currentTimeMillis();
        // Sample the LMB every frame (fade / paused frames too)
        // for the post-round auto-reselect mash guard — a define with the button already held
        // through combat's last frames is a hold/mash, never a list click.
        CcClientModPlugin.markCombatFrame();
        weaponLists.nextFrame(); // Stamp BEFORE the first consumer (the hold re-assert)
        beamPres.beginRelayFrame(); // Last frame's relay set is spent (SubstepHoldAI read it on the fast passes)
        // W-PLUGIN-GAP kill: the replay-driven hold re-assert runs UNCONDITIONALLY
        // ahead of every early-return in this method (pre-feed hold, paused, combat-over, boundary
        // frame — each was a return path that skipped the old mid-body call sites, and the pre-feed
        // hold was benign ONLY because pre-feed has no enemy bodies to target). The flags are
        // one-frame and consumed by Ship.advance, so extra asserts on paused frames are idempotent;
        // the set is empty until the first registerReplayDriven.
        replicaBody.reassertReplayDrivenHold();
        // The claim-at-play placeholder stays inert while
        // the seat claim is pending — sibling of the hold re-assert above (one-frame flags, ahead
        // of every early-return, idempotent on paused frames). Lane = ClaimPlaceholderHold.
        // Also held through the late-attach hold, and
        // anchored on the engine's player ship until the first feed sets ownShip (the same body:
        // ownShip is read from getPlayerShip) — which also closes the pre-feed frames of a served
        // claim, where the parked placeholder was never fire-held. Then one poll frame of the hold.
        boolean placeholderHeld = claimPending || lateAttachHold;
        ShipAPI holdAnchor = ownShip;
        if (placeholderHeld && holdAnchor == null && engine != null) {
            holdAnchor = engine.getPlayerShip();
        }
        claimHold.hold(placeholderHeld, holdAnchor);
        if (lateAttachHold && session == null && lateAttachPolling) {
            pollLateAttachHold();
        }
        // The travel-drive fire lock (DriveLockLane) — one-frame
        // flags, so ahead of every early-return like the holds above. This runs BEFORE this frame's
        // snapshot read, so it reads the PUBLISHED frame; stale = the staleSnapshot 2 s wall gate below,
        // with no fresh frame yet (MIN_VALUE) counted stale — the lane fails OPEN.
        long driveLockFreshNanos = lastFreshSnapshotWallNanos;
        driveLock.holdFrom(ownShip, engine != null ? engine.getPlayerShip() : null, latestSnapshot,
                session != null ? session.shipId() : 0L, driveLockFreshNanos == Long.MIN_VALUE
                        || System.nanoTime() - driveLockFreshNanos >= STALE_SNAPSHOT_NANOS);
        // Defect E: the own MOUNTED module's manual-group cursor slew,
        // engine-native re-enable (sibling of the hold re-assert above; idempotent, fail-soft).
        ownLife.maintainOwnMountedGroupSelect();
        // The transfer-adopt group-select heal — bounded-window
        // sibling of the module maintainer above (idempotent, fail-soft).
        ownLife.maintainOwnAdoptGroupSelect();
        // Fold + apply the server's AIMP remote aim rows — remote
        // piloted ships' manual turrets slew natively toward their pilot's cursor (sibling of
        // the two maintainers above; runs AFTER the hold re-assert so its suspend-clear on
        // AIMP-driven ships is the frame's last word; fail-soft inside the lane).
        remoteAim.maintain(amount); // Amount = the lerp clock (0 while paused)
        // Marker poll BEFORE the paused early-return — a pause-frame press
        // still lands (advance(0) delivers events); repeats skipped so a held key is one marker.
        // Debug gate: F10 end + F8 A/B live ONLY in a debug-flagged match
        // (WELCOME bit1, read live per frame; pre-seed 0 = dead until a debug admit).
        // F9 is PUBLIC, so players can mark stuff in logs
        // — the marker row + retro-dump +
        // the own-ship floaty fire in EVERY served match; only the aim-rebase A/B fence (an
        // F8-family instrument) stays a bit1 rider. Keys here are observed, never consumed, so a
        // dead verb simply means nothing fires.
        if (events != null) {
            final boolean debugMatch = MatchConfigFromWire.clientDebug();
            for (InputEventAPI event : events) {
                keyPollSight.see(event, debugMatch, lastSnapshotSimTick); // F9 no-row sight
                // isConsumed() FIRST — isRepeat()/getEventValue() THROW on a consumed event
                // (the same guard order as processInputPreCoreControls; a live crash: a player's autofire
                // toggle = a consumed key-down, "Attempt to access data of a consumed InputEvent").
                if (event != null && !event.isConsumed() && event.isKeyboardEvent()
                        && event.isKeyDownEvent() && !event.isRepeat()) {
                    if (event.getEventValue() == WATCH_MARKER_KEY) {
                        ownerMarkers++;
                        PilotWatchLog.info("owner-marker #" + ownerMarkers
                                + " tick=" + lastSnapshotSimTick);
                        if (debugMatch) {
                            // A/B-slew PREP: each marker fences an aim-rebase
                            // phase — one uncapped per-phase row (maxima+counts since the last
                            // fence). Debug-only: it belongs to the F8 A/B instrument family.
                            fireLanes.aimRebase.closeAimRebasePhase("owner-marker #" + ownerMarkers,
                                    lastSnapshotSimTick);
                        }
                        // The marker press also retro-dumps the last ~10 s
                        // frame ring at the ~5 ms floor (logic lane-side). Public: log-only,
                        // bounded (top-K), and exactly the forensics a player's mark is for.
                        frameLedger.dumpMarker(ownerMarkers, lastSnapshotSimTick);
                        WireMarkRows.markRow(this, ownerMarkers, lastSnapshotSimTick); // The WIRE side of the press
                        markWire.send(ownerMarkers, lastSnapshotSimTick); // ALSO the server log
                        // Flush this window's rendered
                        // frame ring around the mark + trigger every sibling window's via the
                        // shared mark file (enqueue only; the encoder daemon does the disk work).
                        MarkFrameRing.onMark(ownerMarkers, lastSnapshotSimTick);
                        // The body census — every drawn body keyed own /
                        // wire=N / LOCAL-ONLY + the deployed-set residuals, so the mark frame
                        // names the on-map thing outright (bounded per mark and per run).
                        BodyCensusRows.markRows(this, ownerMarkers, lastSnapshotSimTick);
                        // The hulk-pose adopt RING — the last ~15 s of big
                        // adopts (dpos/dvel > 5) as marker rows, so a late F9 keeps its rows
                        // after the capped live "hulk-pose adopt:" lines have run out (
                        // cap gone at tick ~11219, the F9 #3 hulk snap @47154 unrowed).
                        fractures.dumpAdoptRing("owner-marker", ownerMarkers, lastSnapshotSimTick);
                        // The per-entity rings — WHICH foe's flux flags
                        // disagreed / vent-tailed and WHICH same-side mount recorded / released in
                        // the last ~15 s, after the capped live lines ran out.
                        foeState.dumpFluxRings("owner-marker", ownerMarkers, lastSnapshotSimTick);
                        sameSideAim.dumpRings("owner-marker", ownerMarkers, lastSnapshotSimTick);
                        // The on-screen receipt: text above your ship saying you marked a bug
                        // in the logs, like F8's pop-up text
                        // — yellow own-ship floaty (the F8 MODE label is retired).
                        showOwnShipLabel(engine, "BUG MARKED IN LOG #" + ownerMarkers,
                                java.awt.Color.YELLOW);
                    } else if ((debugMatch || MatchConfigFromWire.seatAdmin())
                            && event.getEventValue() == WATCH_END_KEY) {
                        if (MatchConfigFromWire.seatAdmin()) {
                            // An ADMIN seat's F10 ends the match in ANY match through the server's own
                            // admin chat verb (the F11/F12 shape below: the door re-checks admin, the
                            // operator verb is deposited server-side; nothing is trusted client-side).
                            // Fail-soft: a missed send is logged, nothing breaks.
                            try {
                                session.sendChat("/endmatch");
                                PilotWatchLog.info("admin endmatch verb sent tick=" + lastSnapshotSimTick);
                            } catch (Throwable t) {
                                PilotWatchLog.info("admin endmatch verb send FAILED (" + t + ")");
                            }
                        } else {
                            // A non-admin DEBUG seat keeps the log row the owner-mode launcher
                            // bridges to the operator 'end-match' verb (no wire reach of its own).
                            PilotWatchLog.info("watch-end request tick=" + lastSnapshotSimTick
                                    + " key=F10");
                        }
                    }
                    // F8 (the own-missile A/B toggle) DELETED once
                    // the handoff arm won its adoption; the key is
                    // free for the game again.
                }
            }
        }
        // Must run BEFORE the paused early-return — a multi-ship mission start lands
        // paused in the command map, and plugins only see advance(0) frames until it is cleared.
        // Gated on the command UI actually SHOWING (measured: an ungated one-shot fired
        // ~130 ms after init, BEFORE the map opened — the map then re-paused with the shot spent).
        if (BOTS_AUTO_UNPAUSE && registryShape && !botsUnpauseDone && engine != null && engine.isPaused()
                && engine.getCombatUI() != null && engine.getCombatUI().isShowingCommandUI()) {
            botsUnpauseDone = true;
            engine.setPaused(false);
            PilotWatchLog.info("predict bots unpause: APPLIED (command-map start pause cleared;"
                    + " one-shot, cc.predict.botsAutoUnpause)");
        }
        // Shared-time contract: in shared real time NO
        // client owns the clock. Suppress this engine's local pause CONTINUOUSLY so a non-owner guest's
        // instinctive ESC/command-map cannot dark-stall the predictor (the early-return below) while the
        // server marches — the overlay still shows, combat runs underneath. Runs BEFORE the early-return so
        // the unpaused frame proceeds normally; subsumes the bots one-shot above when ON. Default OFF.
        if (SHARED_TIME_GUARD && !vanillaWindow && engine != null && engine.isPaused()) {
            engine.setPaused(false);
            if (!sharedTimeGuardLogged) {
                sharedTimeGuardLogged = true;
                PilotWatchLog.info("predict shared-time guard: ACTIVE (client-local pause suppressed;"
                        + " continuous, cc.predict.sharedTimeGuard) — pause is a server-protocol state, not client-local");
            }
        }
        // Strip foreign ENGINE-GLOBAL timeMult modifiers BEFORE the paused
        // early-return (the player-branch cloak stat writers run even on paused frames via
        // advanceEvenIfPaused) and BEFORE the measureFrameClock → ClockServoLane.frame chain below (the servo's
        // multInEffect read must see a servo-only mult or the fold mislabels the frame).
        // Engine-global only; per-ship stats.getTimeMult is the faithful lane and is never touched.
        // TRUE-VANILLA: a vanilla: window skips the scrub — the engine-global stat there
        // belongs to the ENGINE's own writers (phase-cloak/temporal-shell dilation IS vanilla law).
        if (!vanillaWindow) {
            clockServo.scrubEngineTimeMult();
        }
        // The one-shot sidelog posture dump at PSTB receipt — polled before
        // the pause gate (the block lands at admission catch-up; a paused menu-idle frame must
        // not delay the posture record the owner debriefs against).
        drainPostureBlock();
        if (engine == null || engine.isPaused()) {
            return;
        }
        terrainLane.advance(engine); // Epoch compare = one volatile read; presentation only when terrain != none
        // Hold the native "enemy fleet is defeated" one-shot off across the
        // registry window's wave-empty stretches (roster init AND between waves); stops at the
        // MOVR drive — the residual drains inside its whiteout delay (seam map = StartBannerGuard).
        if ((registryShape || connectFailHoldOpen) && !matchOverLane.hasDriven()) {
            StartBannerGuard.topUp(engine);
        }
        // Drive the native end-of-combat from the SERVER verdict BEFORE the
        // stream-end handling below — MOVR rides the CTRL lane ahead of the BYE, so the banner
        // lands at-or-before the BYE seam even when both arrive in the same frame.
        matchOverLane.advance(session != null ? session.matchOverRecord() : null, sideAuthority(),
                ownDescSide); // The won-compare is ABSOLUTE (a switched seat wins with its new team)
        // A mailed KICKED notice presents + drives the native defeat the same
        // frame it lands (a kicked session gets no MOVR — its serve-side session is dead).
        kickedNoticeLane.advance(session != null ? session.kickedNoticeReason() : -1);
        // The stream is over — no further rounds can arrive, so restore the engine's
        // native end-of-combat exactly once and let the final round's result flow run. Registry
        // shapes (bots + N-peer) keep the recorded never-self-ends behavior (player
        // exits via ESC): their locally-fielded world is a presentation of the wire roster, and a
        // mid-battle stream end must not resolve into a fake local victory.
        if (streamEnded && !combatEndRestored) {
            combatEndRestored = true;
            if (!registryShape) {
                engine.setDoNotEndCombat(false);
                PilotWatchLog.info("predict match-reset: stream ended -> native end-of-combat restored"
                        + " (resets applied=" + matchResetsApplied + ")");
            } else {
                // A registry shape never self-ends, so the isCombatOver() path below
                // can never reach teardownActive() — after the match-end BYE the window idles
                // un-flushed until its JVM dies, losing every teardown-only row (aim-rebase tail,
                // fire meter, freshness levers). The BYE ended the mod's session, so
                // flush at that seam: meters land in the log now, the window stays up free-running
                // for presentation (no fake local victory), ESC/window-close stay the exits.
                PilotWatchLog.info("predict match-reset: stream ended on a registry shape ->"
                        + " teardown meters flushed at the BYE seam (presentation window stays up)");
                // A kicked latch landing after the per-frame poll above must drive before teardown nulls session
                kickedNoticeLane.advance(session != null ? session.kickedNoticeReason() : -1);
                teardownActive();
            }
        }
        if (engine.isCombatOver()) {
            teardownActive();
            return;
        }
        // Put queued chat relays on the HUD feed (live frames only; a null
        // combat UI holds the queue — chat delivers between rounds and drains when the UI is up).
        chat.drainChatMessages();
        chatBubbles.advance(amount); // dwell/fade clock (paused frames deliver 0)
        picker.pump(); // LSTD drain-take -> assemble -> law tick (UI-null drops)
        // SM passenger probe: tick the pre-opened file sink on LIVE combat
        // frames only — does a mod-held stream keep writing under the combat-phase sandbox? OFF (one
        // null check) unless cc.predict.smProbePath is set; fail-safe inside.
        SmPassengerProbe.onCombatTick();
        // The station cluster's fail-closed INERT sweep runs on every live
        // frame, feed or not — the mission-minted Star Fortress exists from combat init
        // (before any plugin advance), so a pre-match hold (connected, no
        // snapshot yet) would otherwise leave 24 vanilla-AI modules shooting at the seat. Binding
        // needs an authoritative frame and rides the registry block below.
        stations.sweepInert();
        // The Merlon armor census scans on the same every-live-frame cadence —
        // the chew is an AT-LOAD look, so it must observe before the first authoritative frame.
        merlonCensus.advance(amount);
        feedGate.advance(amount); // The deploy-feed mute window clock
        recallFx.advance(engine, amount); // DEPARTED recall animations
        foeState.drainPhaseDives(); // DIVE ctrl records -> lane enroll (own or remote body)
        phaseDive.advance(engine, amount); // emergency-dive playouts
        constructionFx.advance(engine, amount); // Threat fabrication reveals (pending pairs + live builds)
        swarmCloudProbe.advance(engine, amount); // Fresh swarm-fighter cloud rows (knob-gated, read-only)
        reclamationFx.advance(engine, amount); // Threat hulk reclamation dissolves (fade + puffs + loop)
        energyLashFx.advance(engine, amount); // 20 s phase glow countdown + fade
        // Unified input-lane: CAPTURE the own ship's native-control movement state (the 7
        // ShipEngineControllerAPI booleans) into the semantic bitmask every active frame (independent of the
        // snapshot), so the net thread always ships the LATEST input up.
        captureOwnCommands(ownShip != null ? ownShip : engine.getPlayerShip(), amount);
        // The pre-adopt burn-kill WINDOW — every live frame incl. pre-snapshot
        // hold frames (this sits ABOVE the snapshot gate below; the at-sight one-shot left a
        // visible flare because the vanilla deploy engages the drive on its own schedule).
        // No-op unless a re-join armed it; closed at adopt.
        rejoin.maintainPreAdoptBurnKill(ownShip != null ? ownShip : engine.getPlayerShip());
        ownSeat.tickOwnRespawnSampler(); // 1 Hz ×5 s post-respawn H-A/H-C discriminator
        crCensus.advance(amount, ownShip != null ? ownShip : engine.getPlayerShip(),
                ownCrWireLast, ownDepWireLast); // wire riders
        fighterHud.advance(); // wings registered + rate label overridden AFTER vanilla's widgetPanel.advance
        voiceHud.advance(amount); // own-body/camera/anchor positions published for the audio thread (D8)
        observerCam.advance(amount); // Observer free camera — above the no-feed return, a camera needs no feed
        if (DUPLEX) {
            // pt 10 deposit: this frame's input set is now coherent (aim/fire from the input phase
            // + the movement mask above, all in the volatile fields = the latest-wins mailbox) —
            // signal the writer for one UP this frame. Welded path: no signal, no writer thread.
            synchronized (intentSignal) {
                intentFresh = true;
                intentSignal.notifyAll();
            }
        }
        ServerSnapshotFrame snapshot = latestSnapshot;
        if (session == null || snapshot == null) {
            // NO-FEED CONTROL: no server (or no authoritative frame yet) — meter the engine's
            // own pacing under stock-equivalent load; the local engine free-runs untouched otherwise.
            if (combatWallStartNanos == Long.MIN_VALUE) {
                combatWallStartNanos = System.nanoTime();
                noFeedMeter = true;
                PilotWatchLog.info("predict fidelity STEP-8: NO-FEED control meter started (stock-load engine pacing)");
            }
            if (noFeedMeter) {
                noFeedFrames++;
                clientSimTimeSec += amount;
                measureFrameClock(amount);
                if (noFeedFrames % 300 == 0) {
                    logClockMeter("noFeed@" + noFeedFrames);
                }
                // HOLD-UX: a CONNECTED no-feed window is the pre-match hold (the server
                // is waiting for the remaining players), not the STEP-8 stock-pacing control — park
                // the ship + say why, instead of the no-feed sim-rate row (which is also the HUD-ordering
                // fix; rationale at maintainPreMatchHold). A dead stream (BYE during hold) falls back to
                // the free-running control readout; the clock METER above runs unchanged either way.
                // The late-attach hold parks the same way before any session exists.
                if ((session != null && !streamEnded) || lateAttachHold) {
                    maintainPreMatchHold();
                } else {
                    ownReadout.maintainNoFeedSimRateReadout();
                }
            }
            return;
        }
        if (noFeedMeter) {
            // A feed arrived after a no-feed metering window: log the control verdict, then RESET so the fed
            // measurement re-anchors at this first authoritative frame exactly as it always has.
            logClockMeter("noFeedEnd@" + noFeedFrames);
            resetClockMeter();
            noFeedMeter = false;
            holdParkPose = null; // hold released — cc_hold fades+removes natively once unmaintained
        }
        // Round boundary: the server reset + re-deployed between rounds (MRST). Rebuild the
        // local world BEFORE any of this frame's snapshot work — every per-round baseline below must
        // restart from the NEW round's frame, not the dead round's tail. The local `snapshot` above
        // may predate the boundary, so re-read latestSnapshot (published before pendingReset).
        if (boundServerLife == 0L && session != null) {
            boundServerLife = session.serverLife(); // L-R1 lazy first adopt (0 until admitted)
        }
        if (pendingReset != null || pendingLifeReset) {
            snapshot = latestSnapshot;
            applyMatchReset(snapshot);
            if (pendingReset != null || pendingLifeReset) {
                // HOLD: the rebuild deferred — no post-reset frame carries a live own
                // id yet. The old world coasts on pure local prediction this frame: none of the
                // snapshot work below may run against a boundary frame (a live winner's ship would
                // hit the killOwn roster-absence edge; the dead round's meters would consume the
                // fresh round's ticks). The replay-driven hold re-assert already ran at the top of
                // advance, so the hold does not lapse mid-boundary.
                return;
            }
        }
        // Stale-snapshot guard: a FRESH frame is one whose simTick advanced since last frame. The
        // net tick is far slower than render (and stops at teardown), so SERVER telemetry + the running max
        // deltas are recomputed only on a fresh frame, and the readout is flagged STALE once no fresh frame has
        // arrived for STALE_SNAPSHOT_NANOS of wall time — so the post-teardown frozen server pose stops
        // inflating the delta, at a window that does not shrink with render fps.
        boolean freshSnapshot = snapshot.simTick() != lastSnapshotSimTick;
        if (freshSnapshot) {
            lastSnapshotSimTick = snapshot.simTick();
            if (settleRebasePending) { // The round-start epoch bump
                fireLanes.ownHullEpochBumpTick = lastSnapshotSimTick;
                fireLanes.lastLifecycleEdgeTick = lastSnapshotSimTick;
                settleRebasePending = false;
                // Once per round edge — the flight's settle-clock read
                // (the engine-disagree row's ticksSinceEdge= prints only on a disagree).
                PilotWatchLog.info("predict settle-rebase tick=" + lastSnapshotSimTick
                        + " epoch=" + fireLanes.ownHullEpoch);
            }
            lastFreshSnapshotWallNanos = System.nanoTime();
            if (!snapshot.contentDictionary().isEmpty()) {
                // Fold this frame's cycling content-dictionary announcements
                // into the catalog (law + lifecycle at the contentCatalog field doc).
                contentCatalog.putAll(snapshot.contentDictionary());
            }
        }
        boolean staleSnapshot = lastFreshSnapshotWallNanos != Long.MIN_VALUE
                && System.nanoTime() - lastFreshSnapshotWallNanos >= STALE_SNAPSHOT_NANOS;
        // Hull-reconcile clock + stale-feed suspension
        // (body = FoeHullReconcileLane.beginFrame since the extraction;
        // the sim delta feeds the replay-expiry clock — sim seconds, not frames).
        hullLane.beginFrame(freshSnapshot, amount);
        // Walk the interp delay ramp + mint this frame's render cursor
        // (consumed by the reconcileFoe seams in the foe blocks below).
        foeInterp.beginRenderFrame(System.nanoTime());
        // Complete pending press-shadow samples BEFORE this frame's lanes press
        // again — the post line is the pressed ship's state one render frame after the press.
        pressShadow.flush(snapshot.simTick());
        // commander-lane adoption race: a mission build that lost the
        // admit race (WELCOME matchFlags seeded after MatchMissionBuilder read 0x0) skipped the three
        // commander/fleet-context lanes ONE-SHOT — replay them here, every frame until done, BEFORE this
        // frame's spawn-on-sight and seat claim (each attach installs the side-0 fleet context
        // fail-closed, so a spawn ahead of the mint threw once per flight) and before any seat lever.
        // Moved above the spawn; it needs only the build's stash, the
        // WELCOME flag and the side-0 fleet manager, none of which the spawn or the adoption below provides.
        OfficerLoadout.maybeRecoverCommanderLanes();
        if (registryShape) {
            // Fold pending VDEF variant definitions FIRST — catalog ≺ RAPP
            // (a wire-only variant must be engine-resolvable before a RAPP row or this frame's
            // spawn-on-sight mint names it; the door's admission burst ordering makes this the
            // common case, this drain order closes the race).
            vdef.drain();
            // W15 v0: the VACK verdict toast — order-free (presentation only; the
            // ACCEPTED canonical id's VDEF fold + RAPP repoint are the server's own fan).
            vack.drain();
            // module-mount handovers fold between the two — after VDEF
            // (a mount's module variant must be resolvable) and BEFORE RAPP, preserving wire
            // order MMNT≺RAPP render-side (the roster lane's mounted-seat guard is warm before
            // the seat's paired RAPP row folds).
            mmnt.drain();
            // XFER edges drain into the transfer lane — a BEGIN
            // registers podWireId BEFORE this frame's rapp.drain() (own-pod replica suppression,
            // the ordering law); heavy applies stage for the post-spawn pump below.
            java.util.List<cc.starsector.client.net.ClientCommandTransfer> xferEdges =
                    session.takeCommandTransfers();
            if (!xferEdges.isEmpty()) {
                frameLedger.note("xfer"); // transfer-edge frames (client pod first-exec candidates)
            }
            xfer.intake(xferEdges);
            // /switchteams: the team-perspective pass FIRST — the RAPP fold below
            // sides new rows against ownDescSide, the station adopt claims by relative owner and
            // spawn-on-sight picks the spawn manager from re.side; all three must read the live team.
            teamChange.advance(snapshot, session.shipId());
            // Fold pending RAPP appends BEFORE the spawn-on-sight gate so a
            // wave hull appended (or a seat re-pointed, W7 B1b) this frame can mint at this
            // same frame's first pose sight. Intake ≺ this fold
            // is load-bearing — the fold's REPOINT arm consults xfer.hasStagedBeginFor so a
            // foreign BEGIN's S→cc_pod repoint keeps the ALIVE source bound for applyStaged's
            // re-home under F (else a cc_pod re-mint became F's body on a late SNAP).
            rapp.drain();
            // The station adopt-bind drive sits BETWEEN the two — after the
            // RAPP fold (so every announced wire id is already a registry row and can never be
            // mistaken for a module pose) and before spawn-on-sight (so an adopted base is bound
            // to re.ship before the spawn gate would mint a SECOND station for that wire id).
            stations.maintain(snapshot);
            // spawn-on-sight retries per frame until every expected wire entity has a
            // local body (a bound entry never unbinds; a dead-at-first-sight entry stops retrying; a
            // dead-before-sight APPENDED entry may be retired at the entity cap — RosterAppendLane).
            if (ownShip == null || remoteUnbound > 0) {
                int unboundBefore = remoteUnbound;
                spawnRemoteEntities(snapshot, freshSnapshot);
                if (remoteUnbound != unboundBefore) {
                    frameLedger.note("spawn"); // replica-mint frames (client first-exec candidates)
                }
            }
            // The XFER heavy pump AFTER spawn-on-sight — pod
            // adoption, own/remote COMMIT re-points and the swap/return waits need this frame's
            // bound replica bodies.
            xfer.applyStaged();
        } else if (ownShip == null || foeShip == null) {
            // Retry until BOTH ships bind. The old ownShip-only gate made the foe
            // scan one-shot — headless the roster install seeds a player ship, so ownShip bound on the
            // first frame and a foe miss that frame stuck foeShip null for the whole run (carried=0).
            bindShips();
        }
        // (The hold re-assert is hoisted to the top of advance.)
        // lever: while the stream feeds, hold the LOCAL victory verdict shut.
        // doNotEndCombat (held since init) covers the engine self-end path only; the CombatState
        // banner/dialog consumers call isEnemyInFullRetreat() directly,
        // and THAT check short-circuits on combatNotOverFor — e.g. a
        // server-owned fighter roster draining the local enemy deployed list must not pop a local
        // retreat banner. The max-latch setter makes the per-frame 1 s re-assert idempotent;
        // gated on running so the countdown expires at stream end and the FINAL round's native
        // result flow can conclude (registry shapes never self-end anyway).
        if (running) {
            engine.setCombatNotOverForAtLeast(1f);
        }
        // PRIMARY census emit: the feed ended but the render loop keeps stepping
        // (doNotEndCombat) and demonstrably consumes the remaining death-frame snapshots over the next
        // few frames — emit the settled census after a short post-feed ramp, not at stream end.
        if (registryShape && !running && !postFeedCensusLogged) {
            postFeedFrames++;
            if (postFeedFrames >= POST_FEED_CENSUS_FRAMES) {
                postFeedCensusLogged = true;
                logRemoteCensus("postFeed@" + postFeedFrames, true);
            }
        }
        // GAP 2 discharge meter — read-only; count distinct local own-ship projectiles vs the
        // frames the human held the trigger. With native engine firing this tracks the real cooldown cadence.
        advFrames++;
        // Timestamped sim-rate: anchor BOTH clocks at the first authoritative frame (so the wall-clock
        // window, the local sim-time sum, and the server simTick all start together), then accumulate the local
        // engine's per-frame combat dt. The server's sim-time is derived from simTick in logSpeedMeter.
        if (combatWallStartNanos == Long.MIN_VALUE) {
            combatWallStartNanos = System.nanoTime();
            firstServerSimTick = snapshot.simTick();
        }
        clientSimTimeSec += amount;
        // per-frame clock discriminator — compare THIS frame's amount vs its wall dt (anchored
        // at the same first authoritative frame as clientSimTimeSec, so both clocks start together).
        measureFrameClock(amount);
        if (fireHeld) {
            fireHeldFrames++;
        }
        // The held-lane recency the unwired-own-fire verdict reads (0 = held now),
        // in SIM seconds off this frame's engine dt — 10 s of grace is 10 s at 60 fps and at 540 fps alike.
        secondsSinceFireHeld = UnwiredOwnFireLaw.tickSecondsSinceHeld(fireHeld, secondsSinceFireHeld, amount);
        // Sweep BEFORE this pass's adds, outside the ownShip gate (null-own spawns fill sets too).
        if (++projSetSweepFrames >= PROJ_SET_SWEEP_FRAMES) {
            projSetSweepFrames = 0;
            sweepProjSets();
        }
        if (ownShip != null) {
            for (DamagingProjectileAPI p : engine.getProjectiles()) {
                // covered-spec own discharges are consumed by the onFire
                // callback lane FIRST (onOwnFireBirth adds them to seenOwnProj at the true muzzle
                // instant, so this scan skips them); the scan remains the uncovered-spec
                // (beams/missiles, ~65/118 covered) + fallback lane.
                if (p.getSource() == ownShip && seenOwnProj.add(p)) {
                    // An ADOPTED server_only bolt is the presenter's OWN-role spawn —
                    // identity-consumed here FIRST (the presentedFoeProj discipline, own twin) so it
                    // skips ALL local-discharge accounting below: counting it would mint a false
                    // client_only pending + a GAP-2 phantom, and re-DECLARING a server-originated
                    // shot up the PDCL lane would make the server fire it AGAIN (frozen).
                    if (fireLanes.presentedOwnProj.remove(p)) {
                        continue;
                    }
                    // A weaponless own-source damager is NOT a weapon discharge — it is
                    // the engine's own explosion entity (ProximityFuseAI.explode() spawns the flak/
                    // devastator burst DamagingExplosion with weapon=null, and DamagingExplosion.getWeapon()
                    // hardcodes null; the own death explosion is the same class). The server's
                    // GPEE never emits for these (mount cooldown/ammo signals only), so counting them minted
                    // mount=−1 pair-any thieves in the rebase pairing (one run's pair_any_fallback
                    // w1=98, the 74.7° tail's prime suspect) AND a spurious legacy fire decl per burst
                    // (weapon==null falls past the autofire-group check in declareFireDischarge).
                    if (p.getWeapon() == null) {
                        ownNonWeaponDamagers++;
                        // The piloted Mine Strike's local newborn is an own WEAPONLESS
                        // mine — its link-only twin is minted here (the witness below is weapon-keyed:
                        // no decl, no mount). Non-mines no-op inside.
                        missileLane.enrollOwnMine(p, session != null ? session.shipId() : -1L,
                                lastSnapshotSimTick);
                        continue;
                    }
                    localOwnDischarges++;
                    driveLock.onOwnDischarge(true); // The scan sees a birth one frame late
                    dmgWitness.onOwnDischarge(lastSnapshotSimTick, p, ownShip); //
                    if (EVENT_REPLAY_ENABLED) {
                        fireLanes.recordLocalDischarge(lastSnapshotSimTick, p);
                    }
                    edgeEvents.declareFireDischarge(p);
                } else if ((replayDrivenShips.contains(p.getSource()) || recallFx.isDeparting(p.getSource()))
                        && seenReplayShipProj.add(p)) {
                    // A body mid recall animation left the set at its kill but is
                    // held by RecallFxLane — a shot from it is the same class, graded and leak-removed here.
                    // source-fix meter: a distinct replay-ship-source projectile is the presenter's
                    // replayed spawn (exclusion set, entry consumed), an engine-own NON-WEAPON damager (split
                    // below), or a LOCAL AUTONOMOUS discharge — the state-1 defect class the hold-fire gate
                    // exists to make impossible.
                    // Same destructive consume-on-first-sight, now counted —
                    // the earlier structural argument becomes a measured number (adds/consumed/residual).
                    if (!localFire.consumePresented(p)) {
                        if (p.getWeapon() == null) {
                            // A weaponless damager is the engine's own source-attributed
                            // explosion entity — the ship-death DamagingExplosion (the engine's Ship death block
                            // spawns it with the DYING ship itself as source, and it lands in
                            // getProjectiles()), which tripped the autonomous meter 0→1 at every
                            // client-side foe-death mirror. advanceAuto fire always
                            // carries its weapon, so weapon==null is never the state-1 leak class.
                            // SPLIT the bucket. A weaponless damager is NOT always a
                            // death explosion: a hullmod-minted mine is spawned
                            // spawnProjectile(source, NULL weapon, "minelayer1", …) and lands here too,
                            // so a live minefield silently inflated a counter whose stated contract is
                            // "expected == client-side death mirrors of replay-driven ships".
                            // The discriminator is the spec id, and the engine hardcodes it: a genuine
                            // DamagingExplosion returns null from BOTH getWeapon() and
                            // getProjectileSpecId(), while a spawned
                            // mine carries its real spec. Neither arm changes behavior — both only
                            // count and log, and control flow past the if/else is identical — so this
                            // is a METER/LOG correction, and in particular the graded
                            // ..._autonomous_discharge bar (watch-verdict.ps1:236, ==0) is untouched.
                            String weaponlessSpec = null;
                            try {
                                weaponlessSpec = p.getProjectileSpecId();
                            } catch (Throwable ignore) {
                                // fail-soft: an unreadable spec degrades to the death-explosion arm,
                                // i.e. exactly the earlier classification — never a new bar trip.
                            }
                            if (weaponlessSpec == null) {
                                replayShipNonWeaponDamagers++;
                                PilotWatchLog.info("predict autofire-suppress: non-weapon damager #"
                                        + replayShipNonWeaponDamagers + " from replay-driven ship "
                                        + Integer.toHexString(System.identityHashCode(p.getSource()))
                                        + " at snapTick=" + lastSnapshotSimTick
                                        + " (death-explosion class; not a weapon discharge)");
                            } else {
                                replayShipWeaponlessSpawns++;
                                if (replayShipWeaponlessLogLines < EVENT_REPLAY_LOG_MAX) {
                                    replayShipWeaponlessLogLines++;
                                    PilotWatchLog.info("predict autofire-suppress: weaponless SPAWN #"
                                            + replayShipWeaponlessSpawns + " spec=" + weaponlessSpec
                                            + " from replay-driven ship "
                                            + Integer.toHexString(System.identityHashCode(p.getSource()))
                                            + " at snapTick=" + lastSnapshotSimTick
                                            + " (null-weapon mint class — e.g. a hullmod minefield;"
                                            + " not a death explosion, not a weapon discharge)");
                                }
                            }
                        } else {
                            replayShipAutonomousDischarges++;
                            // Classify the counted object (weapon id,
                            // burst, AIHints.SYSTEM, missile/flare) on its OWN log cap. The legacy
                            // line below is untouched on purpose: re-wording or deleting it
                            // retro-blinds every 07-24 reel on re-grade.
                            localFire.onAutonomousDischarge(p, lastSnapshotSimTick, replayShipAutonomousDischarges);
                            if (replayShipAutonomousLogLines < EVENT_REPLAY_LOG_MAX) {
                                replayShipAutonomousLogLines++;
                                PilotWatchLog.info("predict autofire-suppress: AUTONOMOUS local discharge #"
                                        + replayShipAutonomousDischarges + " from replay-driven ship "
                                        + Integer.toHexString(System.identityHashCode(p.getSource()))
                                        + " at snapTick=" + lastSnapshotSimTick + " — hold-fire gate leaked"
                                        + (replayShipAutonomousLogLines == EVENT_REPLAY_LOG_MAX
                                                ? " [log cap reached; counter continues]" : ""));
                            }
                            leakRemover.onLeak(p);
                        }
                    }
                }
            }
            leakRemover.drain(engine); // Outside the live projectile iteration

            if (advFrames % 300 == 0) {
                logFireMeter("advance@" + advFrames);
                logSpeedMeter("advance@" + advFrames);
                logClockMeter("advance@" + advFrames);
                fuseMeter.logSummary("advance@" + advFrames); // silent until a devastator is seen
                dmgWitness.logMeter("advance@" + advFrames, false); // census, silent until active
            }
        }
        // The devastator-fuse scan — NOT inside the ownShip gate above (presented
        // foe shells fly regardless of the own ship's state). Own/other shells first-sight here;
        // presented shells were tagged at their spawn instant with the wire pairing key.
        fuseMeter.tick(engine, ownShip, session.shipId(), lastSnapshotSimTick, advFrames);
        dmgWitness.tick(engine, ownShip, lastSnapshotSimTick, advFrames); // retire/flush pass
        canisterProbe.tick(engine, ownShip, capCloakFrames, capSystemFrames,
                defensePressHold.presses(), advFrames); // press relay


        // Resolve which authoritative pose corrects which local ship. 1v1 mapping: own = the pose whose
        // entityId is this session's pilot shipId (the server-side contract for the feed); foe = the
        // other ship. Fall back to ascending-entityId order if the server did not key on shipId.
        ServerEntityPose ownPose = snapshot.entityById(session.shipId());
        // Registry shapes: the "foe" the HUD/witness/parity lanes see is the PRIMARY
        // foe — the lowest other-side descriptor id (bots: == old botsSide0+1; gauntlet joiner: the
        // boss) — but its reconcile/death flows ONLY through the registry below (the !registryShape
        // gate on the single-foe block prevents double treatment).
        ServerEntityPose foePose = registryShape
                ? snapshot.entityById(primaryFoeId)
                : firstOther(snapshot, ownPose);
        // Latch the id-keyed mapping the first time the own id resolves, and retain the
        // last authoritative own pose (the same-frame kill+roster-drop means the drop frame carries
        // none — this is killOwn's authoritative death pose). Latched BEFORE the fallback below so a
        // fallback-resolved pose never counts as an established mapping.
        if (ownPose != null) {
            if (!ownPoseSeen) {
                // The DEPLOY
                // edge — first wire sight of the own ship — arms the aim-meter settle window
                // like every other lifecycle edge (the former comment "initial join has no
                // residual to defer" was the un-armed gap this edge now closes).
                fireLanes.lastLifecycleEdgeTick = snapshot.simTick();
            }
            ownPoseSeen = true;
            lastOwnPose = ownPose;
            if (ownPose.hitpoints() > 0f) {
                ownLife.ownDeathEdgeArmed = true; // An alive own row re-arms the death edge
            }
        } else if (ownLife.ownDismountAwaitingGap && ownPoseSeen) {
            // own-vacate hold release: the own row's absence proves the pose stream now
            // reflects the dismount (the cooldown gap began) — the NEXT alive row is the
            // refielded hull, and the respawn edge below may take it.
            ownLife.ownDismountAwaitingGap = false;
            PilotWatchLog.info("predict module-mount: own dismount reflected in pose stream"
                    + " (cooldown gap seen) — respawn edge released for the refielded hull");
        }
        // The foe-side twin latch, also BEFORE the fallback below — absence-as-death
        // (the killFoe else-branch at the foot of this method) arms only after the foe was EVER
        // wire-seen, so pre-spawn entities=0 frames on a fast unattended join are not read as a kill.
        if (foePose != null) {
            foePoseSeen = true;
        }
        // NO ascending-order fallback on bots shapes — their id contract is explicit
        // (seat == session shipId == 1) and the seat DROPS from the roster snapshot on death (the
        // server loop now survives seat death), so the fallback would reconcile + hull-mirror the
        // own ship onto ally id 2 for the rest of the run. Own absence on a bots shape = own death
        // (the killOwn edge in the own block below).
        // The ascending-order fallback is SEAT-1-ONLY — under any other seat it
        // would silently reconcile the own ship onto entity 1 (the OTHER pilot's ship), converting a
        // server-side missing-id bug into an invisible client mis-map. A seat-2+ window refuses
        // loudly instead and skips reconcile this frame (own absence stays visible in the log).
        // BOTH branches are further gated to the pre-mapping phase (!ownPoseSeen) — the
        // fallback exists for a STARTUP missing-id bug only. Once the id-keyed mapping was ever
        // established, own-id absence IS the wire's own-death signal and takes the killOwn edge below
        // (the un-gated drop-frame fallback remapped own onto the killer, produced
        // foePose=null, and killFoe ran the real death on the ALIVE killer's body).
        if (ownPose == null && !registryShape && !ownPoseSeen) {
            if (session.shipId() == 1L) {
                ownPose = snapshot.entityAt(0);
                foePose = snapshot.entityAt(1);
            } else if (!fallbackRefusedLogged) {
                fallbackRefusedLogged = true;
                PilotWatchLog.info("predict mapping FALLBACK REFUSED: snapshot lacks own entityId="
                        + session.shipId() + " (entities=" + snapshot.entityCount()
                        + "); index fallback is seat-1-only — own reconcile skipped until the id appears");
            }
        }
        // Latch on the first entity-BEARING frame, not the first frame — a fast
        // unattended join sees pre-spawn entities=0 frames first, and burning the one-shot on
        // those hid the real mapping from every later diagnosis. The pre-spawn phase stays visible
        // via the foe-death DEFERRED one-shot below.
        if (!mappingLogged && snapshot.entityCount() > 0) {
            mappingLogged = true;
            PilotWatchLog.info("predict mapping: shipId=" + session.shipId()
                    + " ownPose=" + (ownPose != null ? ownPose.entityId() : "null")
                    + " foePose=" + (foePose != null ? foePose.entityId() : "null")
                    + " (entities=" + snapshot.entityCount() + ")");
        }

        // The CLIENT half of the parity-audit instrument — walk the
        // LOCAL predicted state at the authoritative tick, BEFORE the reconcile below pulls it toward
        // truth (predicted-vs-authority is the pre-registered comparison; in-window motion + latency
        // skew are absorbed by the differ's tolerances). Walking at simTick % walkEvery mirrors the
        // server's frame % walkEvery with the same N, so both sides walk the SAME tick numbers and
        // the parity differ joins them (server events.ndjson vs this window's starsector.log).
        // Measure each queued gameplay edge event against this frame's snapshot anchor.
        if (EVENT_REPLAY_ENABLED) {
            edgeEvents.drainEdgeEvents(snapshot.simTick());
            // Sample MID-FLIGHT own-window state once per fresh snapshot tick, AFTER the
            // drain so this tick's edges are already folded into the derived server state.
            if (freshSnapshot) {
                edgeEvents.sampleMidflightWindows(snapshot.simTick());
            }
            // Late re-apply: wire names that landed after ships already bound
            // (one-shot per intake via the lane's dirty flag; sweep laws lane-side).
            seatHud.maintainWireNames();
            // Drain + apply authoritative hulk-fracture edges (HKFR only arrives on
            // the demuxed read, so the lane lives inside the event-replay gate like its intake).
            fractures.maintainFractureFamilies(amount);
        }
        // AFTER the fresh-snapshot tick update, like the hulk coast above (else rocks coast at T-1)
        asteroidLane.advance(engine);
        if (freshSnapshot && ClientParityWalker.enabled()) {
            // The walk SUBJECT — anchor foe while alive, else lane-re-bound to
            // the lowest live ledgered side-1 registry foe (fortress waves included; why + rules =
            // walkSubjectFor). The 1278 leg-3 hull triplet rides it (PRE-write ordering kept).
            FoeHullReconcileLane.WalkSubject ws = hullLane.walkSubjectFor(foeShip, foePose, snapshot);
            // Resolve this snapshot tick's per-tier ages (tick-checked — see AgeStamp;
            // a mismatch or null stamp walks age-free, which the differ treats as unmeasured).
            cc.starsector.client.net.SnapshotChunkMerger.TierTicks ownAges = null;
            cc.starsector.client.net.SnapshotChunkMerger.TierTicks foeAges = null;
            AgeStamp ages = latestAges;
            if (ages != null && ages.simTick() == snapshot.simTick()) {
                ownAges = ownPose != null ? ages.byId(ownPose.entityId()) : null;
                foeAges = ws != null && ws.id >= 0 ? ages.byId(ws.id) : null;
            }
            boolean walked = ClientParityWalker.maybeWalkTick(snapshot.simTick(), engine,
                    ownPose != null ? ownPose.entityId() : -1L, ownShip,
                    ws != null ? ws.id : -1L, ws != null ? ws.ship : null,
                    ownAges, foeAges,
                    // The own-ship under-fire latch state at this walk tick —
                    // appended to the own-ship walk line so the differ can attribute vel-gap rows.
                    snapshot.simTick() <= underFireUntilTick,
                    ws != null ? ws.rawHull : Float.NaN,
                    ws != null ? ws.rawHullUnc : Float.NaN,
                    ws != null ? ws.wireHp : Float.NaN);
            if (walked) {
                // The walker-load decision instrument (measure-first) —
                // a walk frame that ranks worst carries this tag; no correlation = accept-as-is.
                frameLedger.note("walk");
                // Row 4 issuer instrument — side-0 order tuples per walk.
                ClientParityWalker.walkOrders(snapshot.simTick(), engine, this);
            }
        }

        // scout-faithful spawn: on the FIRST authoritative frame, adopt the server's exact initial
        // world (the scout direct-deploys both ships AT REST at ±1500,0; the client's mission auto-deploy
        // warps them in elsewhere). One-shot hard snap of both ships to truth so both engines start in the
        // SAME world; the normal reconcile below then idles to ~0 instead of fighting the 4036 u spawn gap.
        // Done AFTER the parity walk above so the first walk still records the raw spawn divergence. See
        // initialWorldAdopted.
        // (83) CL-14: a re-admit (B4) means the world advanced across a blackout with the latch
        // still up — drop it BEFORE the gate so this fresh session hard-resnaps like a late joiner.
        if (session != null && session.reAdmits() != reAdmitsAdoptSeen) {
            reAdmitsAdoptSeen = session.reAdmits();
            // Sidelog tell: the fresh session's re-announces went out.
            PilotWatchLog.info("predict reconnect: re-admit #" + reAdmitsAdoptSeen
                    + " adopted; attach frames replayed so far=" + session.attachFramesReplayed()
                    + " (SNAM/PSTE/VSUB re-sent on the fresh session)");
            // L-R1: fresh-server-life detector — a re-admit whose WELCOME carried
            // a different per-boot nonce is a REBORN server, never a blackout glide. Arm the full
            // epoch rebaseline (consumed by the applyMatchReset consume gate); equal nonces take
            // today's hold path below byte-for-byte.
            long lifeNow = session.serverLife();
            if (boundServerLife != 0L && lifeNow != 0L && lifeNow != boundServerLife) {
                pendingLifeReset = true;
                PilotWatchLog.info("predict reconnect: FRESH SERVER LIFE at re-admit — serverLife "
                        + Long.toHexString(boundServerLife) + " -> " + Long.toHexString(lifeNow)
                        + "; full net-world epoch rebaseline armed (L-R1)");
            }
            boundServerLife = lifeNow;
            // The death-edge state is PRE-CLEAR stream state — reset it with the
            // merger, or the post-clear partial refill reads "own id absent after an established
            // mapping" as an authoritative death (the W5-D2 synthesized corpse + respawn).
            reAdmitEpoch++;
            reAdmitAbsenceHoldPending = true;
            reAdmitAbsenceHoldFromTick = -1L;
            // The view in hand may still be the PRE-clear stale one (the unordered-channel
            // race, W5-D4) — its tick gates the clock start so only a post-clear view starts it.
            reAdmitAbsenceHoldArmTick = snapshot.simTick();
            reAdmitHoldRemoteSkipLogs = 0;
            ownLife.ownDeathEdgeArmed = false;
            ownLife.ownDeathEdgeSkipLogged = false;
            PilotWatchLog.info("predict reconnect: re-admission absence-hold ARMED epoch="
                    + reAdmitEpoch + " — absence-death adjudication held until +"
                    + RE_ADMIT_ABSENCE_HOLD_TICKS + " ticks past the refill start");
            if (initialWorldAdopted) {
                initialWorldAdopted = false;
                PilotWatchLog.info("predict reconnect: re-admit reached the adopt gate ->"
                        + " initial-world re-adopt armed ((83) CL-14)");
            }
        }
        if (reAdmitAbsenceHoldPending) {
            // Pump the hold clock every frame (not only on absence consults) so the window
            // anchors to the refill itself — otherwise a clean refill leaves the hold latched and
            // the FIRST real absence minutes later would be held ~3 s. The expiry transition is
            // latched for the own edge below (a disarmed edge has no other wake-up).
            reAdmitHoldExpiredThisFrame = !reAdmitAbsenceHoldActive(snapshot.simTick());
        } else {
            reAdmitHoldExpiredThisFrame = false;
        }
        // W13 leg 2c: THE CLAIM — first alive wire sight of the own seat id with no own
        // hull fielded -> field it AT REST at the wire pose through the respawn core; the same frame
        // falls through to adoption + reconcile with the fresh hull (the placeholder pod is left
        // orphaned — it is never in getShips()). A failed spawn retries next frame (claimPending stays).
        // Runs BEFORE adoption so the initial world adopts onto the real hull, never the pod.
        if (claimPending && ownPose != null && ownPose.hitpoints() > 0f && !xfer.ownTransit()) {
            ownSeat.claimOwnSeatAtPose(ownPose);
        }
        if (freshSnapshot && !initialWorldAdopted && ownShip != null && ownPose != null) {
            // (83) CL-8: latch ONLY on a fully-clean adopt — a swallowed snap/vitals throw now
            // retries on the next fresh snapshot (adopt is idempotent absolute-state).
            initialWorldAdopted =
                    rejoin.adoptInitialWorld(ownShip, ownPose, foeShip, foePose); // (55) release/deferral inside
        }
        // The seat-stats lever (+ its display twin): fires the frame ADOPTION lands;
        // the round reset clears the latch. Detail + ground truth: OfficerLoadout.applySeatStatsLever javadoc.
        if (initialWorldAdopted && !flagshipLeverApplied && ownShip != null) {
            OfficerLoadout.applySeatStatsLever(ownShip,
                    MatchMissionBuilder.ownDescriptorVariantOrNull(),
                    MatchMissionBuilder.isOwnSeatShipId(), "t0", true);
            flagshipLeverApplied = true;
            leverSettledRefireAtMillis = System.currentTimeMillis()
                    + LEVER_SETTLED_REFIRE_DELAY_MILLIS;
        }
        // The settled re-fire (idempotent, id-keyed-modifier law): one-shot per arming, on the live own hull.
        if (leverSettledRefireAtMillis != 0L && ownShip != null
                && System.currentTimeMillis() >= leverSettledRefireAtMillis) {
            leverSettledRefireAtMillis = 0L;
            OfficerLoadout.applySeatStatsLever(ownShip,
                    MatchMissionBuilder.ownDescriptorVariantOrNull(),
                    MatchMissionBuilder.isOwnSeatShipId(), "settled", initialWorldAdopted);
        }
        rejoin.maintainPendingResume(ownShip); // no-op unless a conform press deferred the release
        // Sticky guard: re-assert the display twin (the codex reads the captain LIVE at R-press).
        OfficerLoadout.maintainSeatDisplayTwin(ownShip, MatchMissionBuilder.isOwnSeatShipId());

        // Frame-rate-independent reconcile factors (the cadence fix): derive this frame's glide
        // from the real frame dt so the pull is a fixed rate-per-second regardless of render fps.
        float ownAlpha = frameAlpha(OWN_RECONCILE_RATE, amount);
        float foeAlpha = frameAlpha(FOE_RECONCILE_RATE, amount);
        foeFacingFrameDtSec = amount; // The facing lane's change_hz clock

        // The OWN fly-in respawn edge: the own wire id reappearing ALIVE after
        // the server-decided own death IS the remount contract (the fresh hull is interned at the SAME
        // reserved-band wire id; the step-3 bench proved the 1-tick death gap + reappear). A stale feed
        // cannot fabricate this edge — the death was latched off a snapshot that LACKED the own id
        // (roster absence is the wire death contract), so only a fresh remount frame carries it alive
        // again. On success the SAME frame falls through to the normal reconcile below with the fresh
        // ship; on a failed spawn ownKilled stays latched and the edge retries next frame (fail-soft).
        if (ownKilled && ownPose != null && ownPose.hitpoints() > 0f
                && !ownLife.ownDismountAwaitingGap && !mmnt.holdsOwnRespawn()
                // Hold the edge over a live transfer TRANSIT —
                // respawnOwn's setPlayerShipExternal would race the transit-end restore
                // order-dependently; the edge is retry-latched on ownKilled, so holding is free.
                && !xfer.ownTransit()) {
            // A pending OR MOUNTED own seat holds the respawn
            // edge — pending: the seat's next life is a module (SNAP overtook the MMNT CTRL
            // frame) and a hull fielded now would orphan it; mounted: a stale ownKilled re-latch
            // must not mint a duplicate free hull beside the live module. A
            // post-dismount hold additionally waits for the pose stream to reflect
            // the vacate. The mount apply also heals a raced hull.
            ownSeat.respawnOwn(ownPose);
        }
        // Join-pick: the ALIVE twin of the edge above. The t=0
        // mission build fields the seat's DESCRIPTOR roster row before
        // the server's fielded variant can be known — a /ship pick seeded at admit (HELLO v8
        // variantId) deploys a DIFFERENT hull server-side, and the seat's RAPP re-point row
        // (announceSeatVariantIfRepointed) used to land registry-only, applied at the NEXT own
        // respawn: the joiner flew a hull the server did not have.
        // Now the RAPP OWN fold arms ownRefieldPending and this site re-fields at once through the
        // SAME spawn core as the respawn edge (client-side, no new wire); the guards
        // mirror the edge's (adopted world, no mount/transfer tenure on the seat id). The
        // registry-vs-hull compare lives in the resolver so a replay/duplicate row never re-fields.
        if (ownRefieldPending && !ownKilled && ownShip != null && ownPose != null
                && ownPose.hitpoints() > 0f && initialWorldAdopted
                && ownMountedModule == null && !mmnt.holdsOwnRespawn() && !xfer.ownTransit()) {
            ownSeat.maintainOwnVariantRefield(ownPose);
        } else if (!ownRefieldPending && !ownKilled && ownShip != null && ownPose != null
                && ownPose.hitpoints() > 0f && initialWorldAdopted
                && ownMountedModule == null && !mmnt.holdsOwnRespawn() && !xfer.ownTransit()
                && ++ownHullInvariantFrames >= OWN_HULL_INVARIANT_FRAMES) {
            // A silent own-hull fork must NEVER happen
            // again: the standing invariant — the LIVE own hull IS the server's announced
            // variant. The RAPP OWN fold is the seam that arms the re-field; this is the backstop
            // for any row that was lost, deduped or raced (the fork lived 22349 ticks
            // behind a registry-only dedup). Under the same guards as the resolver, every
            // OWN_HULL_INVARIANT_FRAMES a registry/live mismatch must persist for two
            // consecutive checks (~4 s: a t=0 placeholder pod or a re-field in flight is a
            // transient, not a fork) before it arms the L5 re-field and announces once. Cost = one
            // string compare per 2 s.
            ownHullInvariantFrames = 0;
            String announced = variantById.get(Long.valueOf(session.shipId()));
            String live = ownShip.getVariant() != null ? ownShip.getVariant().getHullVariantId() : null;
            OwnHullForkLaw.Verdict verdict = OwnHullForkLaw.verdict(live, announced);
            if (verdict == OwnHullForkLaw.Verdict.OK) {
                ownHullMismatchStreak = 0;
            } else if (verdict == OwnHullForkLaw.Verdict.REFIT_FORK) {
                // Refit fork: the live own hull carries the
                // engine's briefing-Refit save id (mission_<id>_ship_<n>) — the Refit screen
                // auto-saved it and the engine swapped it into the t=0 PLAYER fleet on a hull-id
                // match. Never a transient, so it fires on the
                // FIRST check (~2 s, not ~4 s): with an announce it arms the same alive re-field
                // (the resolver fields the ANNOUNCED registry variant, so the refit is undone);
                // without one there is nothing to re-field TO (the resolver would clear "already
                // fielded"), so it confesses once and stays LOUD — never conceal. Ids only: a
                // variant's displayName is player text and is never echoed.
                ownHullMismatchStreak = 0;
                String fork = "OWN hull invariant: live " + live + " is the engine's briefing-Refit"
                        + " save (announced " + FallbackAnnounce.sanitize(announced, 64)
                        + ") -> a Refit leaked into the t=0 fleet";
                if (announced != null) {
                    ownRefieldPending = true;
                    ownHullInvariantArms++;
                    fork += "; alive re-field ARMED to the announced variant (arms=" + ownHullInvariantArms + ")";
                    PilotWatchLog.error("predict refieldOwn: " + fork, null);
                } else if (!ownRefitForkConfessed) {
                    ownRefitForkConfessed = true;
                    fork += "; NO announced variant to re-field to -> the seat keeps the refit hull"
                            + " (own-variant DESYNC until an announce or the next own respawn; one-shot log)";
                    PilotWatchLog.error("predict refieldOwn: " + fork, null);
                }
                FallbackAnnounce.announceOnce("own-hull-refit-fork", fork);
            } else if (++ownHullMismatchStreak >= 2) {
                ownHullMismatchStreak = 0;
                ownRefieldPending = true;
                ownHullInvariantArms++;
                String fork = "OWN hull invariant: live " + live + " != announced "
                        + FallbackAnnounce.sanitize(announced, 64) + " with no re-field pending"
                        + " -> alive re-field ARMED (arms=" + ownHullInvariantArms
                        + "; the RAPP fold missed or deduped the row)";
                PilotWatchLog.error("predict refieldOwn: " + fork, null);
                FallbackAnnounce.announceOnce("own-hull-invariant", fork);
            }
        }

        // Own ship: input-predicted. Gently nudge position toward truth; leave facing + velocity to the
        // engine's native player control (the zero-RTT win). Hull is server-authoritative — mirror it.
        // Since the duel this is a LIVE lane: presented foe projectiles hit locally as
        // prediction, the mirror returns hull to truth each frame, and OwnDamageCap bounds the kill edge.
        // The else-branch is the authoritative OWN-death edge — own id absent after an
        // established mapping (roster ABSENCE is the wire death contract), or an authoritative hull<=0
        // (never carried today; kept symmetric with the foe block below).
        if (ownShip != null && ownPose != null && ownPose.hitpoints() > 0f) {
            // Transit patience — while the own teleport-class system runs its jump
            // window the hatch + step-rebase would fight the engine's own displacement; suspend
            // both (pose only — hull mirror and every other lane keep running).
            boolean ownJumpPatience = teleportLane.maintainOwnPatience(ownShip, snapshot.simTick());
            // While the own seat pilots a station module, the engine
            // re-derives its pose from the parent every frame (updateStationModuleLocations) —
            // position glide/hard-snap, step-rebase and velocity glide would fight that
            // derivation. Suppress pose writes only; hull mirror + every meter lane keep running.
            boolean ownMounted = ownMountedModule != null;
            // Advance the burst latch FIRST (fresh frames only, as before) so THIS frame's
            // window state gates the tether and the rebase alike — "latch before rebase counting"
            // stays true, and the tether no longer lags the window by one frame.
            if (freshSnapshot) {
                updateOwnBurstLatch(ownShip, snapshot.simTick());
            }
            boolean burstLive = BURST_EXEMPT_ENABLED
                    && BurstWindowLaw.live(snapshot.simTick(), burstUntilTick);
            // In TRANSIT the source hull is a wire-slaved
            // observer (server AI owns it; the xfer pin pump hard-pins its pose) — the soft
            // glide/rebase would fight the pin exactly like the jump/mount cases.
            boolean ownTransit = xfer.ownTransit();
            if (!ownJumpPatience && !ownMounted && !ownTransit) { // Residual meter gated with it
                // ONE engine-native own fly-in read per frame —
                // the RejoinRebaselineLane-proven predicate (isOn() = live burn; the OUT
                // chargedown reads false) — feeds the ARM hold belt AND the settled/haul-rate
                // meter stratification below. Fail-soft OFF: an unreadable drive never blocks
                // the reconcile (belt un-held = pre-belt behavior; meters stay honest).
                boolean ownDriveOn = false;
                try {
                    ShipSystemAPI ownDrive = ownShip.getTravelDrive();
                    ownDriveOn = ownDrive != null && ownDrive.isOn();
                } catch (Throwable ignore) {
                    // fail-soft: belt+stratification read OFF this frame
                }
                // Belt v2: the local-drive read alone is
                // BLIND to pose-adopted own fly-ins — the adopt seams kill or never light the
                // local drive (turnOffTravelDriveAtAdopt at initial adopt; a parked
                // hull STEP-16-rebased onto the server's launch at the /ship-swap respawn)
                // while the AUTH hull glides at 600-class. Compose the auth-over-max regime
                // read law-side: ONE stat read, fail-soft NaN = drive read alone.
                float ownMaxSpeedU = Float.NaN;
                try {
                    ownMaxSpeedU = ownShip.getMutableStats().getMaxSpeed().getModifiedValue();
                } catch (Throwable ignore) {
                    // fail-soft: hold degrades to the bare drive read this frame
                }
                boolean ownFlyInHold = OwnVelReconcileLaw.flyInHold(ownPose.velX(),
                        ownPose.velY(), ownMaxSpeedU, ownDriveOn);
                // Advance the own-velocity rebase-and-hold on FRESH frames
                // BEFORE the position glide below, so this frame's tether override and the
                // STEP-16/glide standdowns all read this frame's engage state. Same guards as
                // the rebase (jump patience / mount / transit suppress it).
                if (freshSnapshot) {
                    maintainOwnVelReconcile(ownShip, ownPose, snapshot.simTick(), ownFlyInHold);
                }
                flyInFacing.sample(ownShip.getFacing(), ownPose.facing(), snapshot.simTick(),
                        ownFlyInHold, freshSnapshot); // own-vs-wire facing across the hold
                // The own-SHIP reconcile residual meter:
                // the own-ship pin (OWN_RECONCILE_RATE) is
                // the live RIVAL explanation for the backward-kite percept — one watch now
                // falsifies both hypotheses. Residual sampled BEFORE the glide, backward-
                // stratified by along-facing velocity. Fail-soft, measure-only (the
                // glide cap below reads the sample EVERY frame; -1 = none this frame).
                float ownGlideResid = -1f;
                try {
                    Vector2f orLoc = ownShip.getLocation();
                    Vector2f orVel = ownShip.getVelocity();
                    if (orLoc != null) {
                        float ordx = ownPose.locX() - orLoc.x;
                        float ordy = ownPose.locY() - orLoc.y;
                        float orResid = (float) Math.sqrt(ordx * ordx + ordy * ordy);
                        ownGlideResid = orResid;
                        ownReconFrames++;
                        ownReconResidSu += orResid;
                        if (orResid > ownReconResidMax) {
                            ownReconResidMax = orResid;
                        }
                        // The meter hole — resid_max
                        // is fly-in-polluted (one run's 2953.66 masks everything under it).
                        // Hold-OFF stratification keeps a settled max the grade bars can read
                        // (the composed read — pose-adopted glides polluted the drive-only
                        // cut — settled==max on both aurora initial lives).
                        if (!ownFlyInHold && orResid > ownReconResidMaxSettled) {
                            ownReconResidMaxSettled = orResid;
                        }
                        if (orVel != null) {
                            double orRad = Math.toRadians(ownShip.getFacing());
                            if (orVel.x * Math.cos(orRad) + orVel.y * Math.sin(orRad) < 0f) {
                                ownReconBackFrames++;
                                ownReconBackSu += orResid;
                                if (orResid > ownReconBackMax) {
                                    ownReconBackMax = orResid;
                                }
                            }
                        }
                    }
                } catch (Throwable ignore) {
                    // meter only; the reconcile below must never be blocked
                }
                // In a live burst window the full-rate glide is the drag that slaves the
                // visual lunge to the ~3-tick-stale auth track — drop to the weak tether. The 300-su
                // snap hatch stays ARMED inside reconcilePosition regardless of alpha.
                // An ENGAGED own-vel reconcile voids the weak tether's
                // premise (the gate adjudicated the gap beyond thruster authority) — engaged
                // frames keep the full own rate even inside a live burst window.
                float tetherAlpha = ownAlpha;
                if (burstLive && !ownVelReconEngaged()) {
                    tetherAlpha = frameAlpha(BurstWindowLaw.TETHER_RATE_PER_SEC, amount);
                    burstRelaxedFrames++;
                }
                // Rate cap on ENGAGED own-glide frames only:
                // the gate has adjudicated a real divergence, so the glide closing it
                // moves at most 240 su/s — inside the vanilla motion envelope (binds only past
                // ~6 su at the full rate). Disengaged frames glide at the old full rate: a
                // small shield-ram correction snaps back instead of sliding. An earlier version had run
                // the cap on EVERY frame against the burst-END haul-back (residual
                // banked at the 0.5/s weak tether, hauled back in 2–4 frames at expiry) — that
                // all-frames cap is what a player felt as the slow smeary reconcile; withdrawn.
                if (ownVelReconEngaged()) {
                    tetherAlpha = OwnVelReconcileLaw.cappedGlideAlpha(tetherAlpha, ownGlideResid, amount);
                }
                // The 5-arg overload returns the snap verdict — disclose it
                // (the void 4-arg call swallowed own-ship 300-su snaps; see ownReconSnaps).
                boolean ownSnapped = foeState.reconcilePosition(ownShip, ownPose.locX(),
                        ownPose.locY(), tetherAlpha, false);
                if (ownSnapped) {
                    ownReconSnaps++;
                }
                // The haul-rate meter: su of own-pose
                // correction applied per 0.5 s of settled gliding (max window kept) = the
                // teleport PERCEPT quantity, grading the D-CAP directly. Correction
                // this frame = alpha × resid (glide lerp arithmetic) or the FULL resid on a
                // hatch snap (a snap IS a teleport — the meter must scream, not average it
                // away). Fly-in-HOLD frames excluded like resid_max_settled (fly-in glide is
                // by-design huge; pose-adopted glides too); expected bound under the cap
                // ≈ 120 su/window + snaps.
                if (!ownFlyInHold && ownGlideResid > 0f) {
                    ownHaulWindowSu += ownSnapped ? ownGlideResid : tetherAlpha * ownGlideResid;
                }
                ownHaulWindowSec += amount;
                if (ownHaulWindowSec >= 0.5f) {
                    if (ownHaulWindowSu > ownHaulMaxSuPerHalfS) {
                        ownHaulMaxSuPerHalfS = ownHaulWindowSu;
                    }
                    ownHaulWindowSu = 0f;
                    ownHaulWindowSec = 0f;
                }
            }
            float ownWireHp = ownPose.hitpoints();
            float ownHullTarget = ownDetLane.ownMirrorTarget(ownShip, ownWireHp, freshSnapshot);
            hullLane.noteOwnMaxHullSkew(ownShip, ownPose, freshSnapshot, snapshot.simTick()); // Measure only
            mirrorHull(ownShip, ownHullTarget);
            ownDetLane.maintainOwnArmor(ownShip, ownPose, freshSnapshot, snapshot.simTick()); // own armor
            maintainOwnHullProbe(ownShip, ownWireHp, ownHullTarget, freshSnapshot,
                    snapshot.simTick());
            // Contact/step-change detector + one-shot velocity re-base, on FRESH frames only
            // (the authoritative stream is the baseline). Before the maintain* readouts so the speed/maneuver
            // telemetry this frame reflects the post-rebase local state.
            if (freshSnapshot && !ownJumpPatience && !ownMounted && !ownTransit) { // Latch already advanced above this frame
                maintainStepRebase(ownShip, ownPose, snapshot.simTick());
            }
            // The drift-gated velocity glide, every frame against the latest pose
            // (after the rebase, so a rebase frame glides a zero gap; the gate consumes the
            // rebase detector's own step measurement, which a contact also slams shut). Fresh
            // frames also clock the item-④ above-cap persistence escape (sim-tick clock).
            maintainVelocityGlide(ownShip, ownPose, amount, staleSnapshot,
                    freshSnapshot, snapshot.simTick());
            // Surface the server-authoritative own-ship speed + flux beside the local-engine values
            // (gap ③ + the gap-② / "is it flux?" measurement instrument) and the local ship-system state.
            // SERVER values + the running max deltas update only on a fresh frame; STALE flags a stopped feed.
            ownReadout.maintainSpeedReadout(ownShip, ownPose, freshSnapshot, staleSnapshot);
            ownReadout.maintainFluxReadout(ownShip, ownPose, freshSnapshot, staleSnapshot);
            // The OWN-flux apply lane — AFTER maintainFluxReadout sampled its
            // meters (meter-samples-then-pin; flux_gap stays the honest misprediction meter).
            maintainOwnFlux(ownShip, ownPose, freshSnapshot, snapshot.simTick());
            godMirror.maintain(ownShip, ownPose); // Beside, never inside, the knob-gated own-flux lane
            // The OWN-CR pin lane — same meter-samples-then-pin law (gaps are
            // sampled pre-pin inside; the always-on CrClockCensus keeps the discriminator role).
            maintainOwnCr(ownShip, ownPose, freshSnapshot, snapshot.simTick());
            ownReadout.maintainSystemReadout(ownShip);
            ownReadout.maintainSimRateReadout(staleSnapshot);
            ownReadout.maintainNetReadout(staleSnapshot); // Ping + measured server tickrate
            // The OWN-mount weapon-state reconcile (the w1 6.147 s client-only
            // disable fix) + the own-engine lane (meter/apply), fresh frames only.
            foeState.maintainWeaponState(ownShip, ownPose, freshSnapshot, snapshot.simTick(), "own");
            if (freshSnapshot) compDmgShield.ensureOwnCritFace(ownShip, ownPose, snapshot.simTick()); // Ungated (not behind OWN_ENGINE_METER) — starves only the nested crit roll
            maintainOwnEngineState(ownShip, ownPose, freshSnapshot, snapshot.simTick());
            // The OWN fire-phase lock lane — AFTER the flux/weapon meters sampled
            // (meter-samples-then-pin; flux_gap stays the honest misprediction outcome meter).
            fireLanes.maintainOwnFireLock(ownShip, ownPose, freshSnapshot, snapshot.simTick());
            // The own beam-cycle phase lock — AFTER the ownlock mirror pass
            // (mirror-then-grant: an edge grant lands post-mirror and the lane shields its
            // engaged mounts from the next frames' downward mirror writes).
            fireLanes.maintainOwnBeamCycle(ownShip, ownPose, freshSnapshot, snapshot.simTick());
            // The discharge-reconcile early-retire pass + auth-ammo cache refresh
            // is auth-vs-auth, so placement after the lock pin is for
            // cache coherence only, not compare correctness.
            fireLanes.maintainDischargeReconcile(ownPose, freshSnapshot, snapshot.simTick());
            // Autofire lead-error meter (measure-only) — pose-vel compare freshest here.
            fireLanes.maintainAutofireLeadMeter(ownShip, ownPose, freshSnapshot, snapshot.simTick());
        } else if (ownShip != null && ownPoseSeen) {
            // The own id vanished from the roster after an established mapping (or its authoritative
            // hull hit zero): the server decided the OWN ship's death. A STALE feed cannot trip this —
            // a stopped stream re-applies the last snapshot, which still carries the own id. EXCEPT
            // across a module mount: the mount rides CTRL, so the CURRENT snapshot can
            // still be the retiring hull's death-gap frame — that edge belongs to the ENDED life and
            // must not present death on the live module (a run hulked it client-side).
            // Disarmed from mount-apply until the first alive own row; a real module death re-fires
            // after that.
            if (reAdmitAbsenceHoldActive(snapshot.simTick())) {
                // Own twin: inside the hold window absence never adjudicates even
                // with the edge ARMED — a pre-clear view consumed after the epoch bump re-arms
                // the edge off the DEAD session's roster (the unordered-channel race that stamped
                // the remote lane's sightEpoch). The pump above already advanced the clock this
                // frame, so this consult is a pure read. Expiry adjudicates below.
                if (!ownLife.ownDeathEdgeSkipLogged) {
                    ownLife.ownDeathEdgeSkipLogged = true;
                    PilotWatchLog.info("predict killOwn: own row absent inside the re-admission"
                            + " hold window — kill held (armed=" + ownLife.ownDeathEdgeArmed
                            + ") tick=" + snapshot.simTick());
                }
            } else if (ownLife.ownDeathEdgeArmed) {
                ownSeat.killOwn(ownPose);
            } else if (reAdmitHoldExpiredThisFrame) {
                // The re-admission hold EXPIRED with the own row still absent — a true
                // blackout death adjudicates now (≤ hold late), never masked.
                ownLife.ownDeathEdgeArmed = true;
                PilotWatchLog.info("predict killOwn: re-admission absence-hold expired with the"
                        + " own row still absent -> adjudicating the held death");
                ownSeat.killOwn(ownPose);
            } else if (!ownLife.ownDeathEdgeSkipLogged) {
                ownLife.ownDeathEdgeSkipLogged = true;
                PilotWatchLog.info("predict killOwn: death edge DISARMED (module-mount gap) —"
                        + " absence not adjudicated (re-arms at the first alive own row)");
            }
        }

        // Foe: dead-reckon + reconcile. NOTE the foe hull is deliberately NOT mirrored:
        // the old per-frame mirror ERASED the player's local hit (the bar tracked the SERVER, not the
        // visible impact). Instead the REAL local collision damage STANDS so the bar drops on the player's
        // hit (axis A); {@link FoeDamageCap} caps a would-be-lethal hit so the foe never dies client-side
        // (axis B, installed in bindShips); and the real death runs only on the authoritative edge below
        // via killFoe (axis C).
        if (registryShape) {
            // Every non-own wire entity gets the proven single-foe treatment, per entity.
            foeState.maintainRemoteEntities(snapshot, foeAlpha, freshSnapshot);
        } else if (foePose != null && foePose.hitpoints() > 0f) {
            if (foeKilled && !foeRespawnUnsupportedLogged) {
                // Recorded residual: the 1v1 single-foe lane has NO mid-round
                // re-field (fly-in respawn rides the registry shapes; this shape re-fields at the
                // MRST boundary). Loud one-shot so a respawn-enabled 1v1 watch fails visible, not
                // silent — every treatment below no-ops on the dead body via its isAlive guard.
                foeRespawnUnsupportedLogged = true;
                PilotWatchLog.info("predict respawn: foe id reappeared ALIVE after killFoe on the"
                        + " 1v1 single-foe lane -> NO mid-round re-field here (registry shapes only;"
                        + " corpse stays until the MRST boundary rebuild)");
            }
            // Inside a teleport discontinuity window the glide branch is
            // disabled — errors snap regardless of magnitude (1v1 twin of the registry path).
            // The fence-aware seam — foeInterp=apply substitutes the buffered target.
            foeState.reconcileFoe(foeShip, foePose, foeAlpha,
                    teleportLane.foeSnapOnly(foeShip, snapshot.simTick()),
                    teleportLane.foeFenceTick());
            // The schema-v6 foe engine-state lane (meter default; apply opt-in), fresh frames only.
            foeState.maintainFoeEngineState(foeShip, foePose, freshSnapshot, snapshot.simTick());
            // Foe mounts ride the same weapon-state lane (foe fire is presented).
            foeState.maintainWeaponState(foeShip, foePose, freshSnapshot, snapshot.simTick(), "foe");
            // one-shot mid-window system SEED — only while NO edge knowledge
            // exists for this foe (serverOpen==null); the edge-driven lane below owns steady state.
            if (!foeSystemSeeded && foeSysServerOpen == null && freshSnapshot) {
                foeSystemSeeded = foeState.seedFoeSystemState(foeShip, foePose, snapshot.simTick());
            }
            // The foe system-window lane (meter default; apply opt-in), fresh frames only.
            foeState.maintainFoeSystemState(foeShip, foeSysServerOpen, foeSysOpenTick,
                    foePose != null ? foePose.entityId() : -1L, freshSnapshot, snapshot.simTick(), true);
            // Consume a pending never-cycled heal where the pose scalars are at hand
            // (after the system lane, so an in-flight useSystem press had its chance first).
            if (freshSnapshot) {
                teleportLane.maintainFoeHeal(foeShip, foePose, snapshot.simTick());
            }
            // The foe maneuver-command lane (plumes) — apply runs EVERY frame by design.
            foeState.maintainFoeManeuver(foeShip, foePose, freshSnapshot, snapshot.simTick(), true);
            // The foe FLUX apply lane (R-screen target bar) — apply pins EVERY frame.
            foeState.maintainFoeFlux(foeShip, foePose, freshSnapshot, snapshot.simTick(), true);
            // The foe FLUX-FLAGS lane (overload arcs / vent steam) — meter on
            // fresh frames, apply mismatch-edge every frame.
            foeState.maintainFoeFluxFlags(foeShip, foePose, freshSnapshot, snapshot.simTick(), true);
            godMirror.maintain(foeShip, foePose); // the certified 1v1 seat-2 foe
            // The foe AUTOFIRE lane (reticle group rows) — fresh frames, edge-triggered.
            foeState.maintainFoeAutofire(foeShip, foePose, freshSnapshot, snapshot.simTick(), true);
            // The foe SELECTED-GROUP lane ((114)(d) far-aim terminus) — fresh
            // frames, mismatch-edge.
            foeState.maintainFoeSelection(foeShip, foePose, freshSnapshot, snapshot.simTick(), true);
            // The foe SHIELD lane (seat-2 boss shield) — law-split apply (OMNI hold /
            // non-OMNI mismatch-edge; the engine's FRONT toggle law).
            foeState.maintainFoeShield(foeShip, foePose, freshSnapshot, snapshot.simTick(), true);
            // The cloak lane — per-tick FLAG_PHASED edge +
            // fresh-keyframe repair (the old one-shot seed folded in; never keys SYSTEM_WINDOW).
            foeState.cloak.maintainFoeCloak(foeShip, foePose, freshSnapshot, snapshot.simTick(), true);
            // The foe HULL raw ledger + baseline+replay reconcile — the ledger runs
            // in EVERY mode (off included); the floor-bounded write (apply) lands AFTER every
            // damage-side lane above and BEFORE the beam presentation reads hulls.
            hullLane.maintain(foeShip, foePose, freshSnapshot, snapshot.simTick(), null);
            // The beam-presentation RELAY — per render frame, never fresh-gated.
            beamPres.maintainBeamPresentation(foeShip, foePose, snapshot.simTick(), "foe");
        } else if (foePoseSeen) {
            // The foe is gone from the snapshot or its authoritative hull hit zero -> server-decided death.
            ownSeat.killFoe(foePose);
        } else if (!foeKillPreMappingLogged) {
            // A fast unattended join reads pre-spawn entities=0 frames
            // BEFORE the server fields the ships — foe absence there is a world that has not spawned
            // yet, NOT the roster-drop death contract. Loud one-shot so a foe that never appears
            // stays visible in the log instead of silently never dying.
            foeKillPreMappingLogged = true;
            PilotWatchLog.info("predict foe-death DEFERRED pre-mapping: foe not yet wire-seen"
                    + " (entities=" + snapshot.entityCount() + ") -> death edge arms on the first"
                    + " foe-bearing frame");
        }
        // The beam-presentation AIM half — once per frame off the frame's v12 beam
        // records, AFTER the blocks above so hulls sit at this frame's reconciled poses.
        beamPres.maintainBeamAim(snapshot);
        // The missile pose lane — FRESH frames only (stale never ticks
        // absence); after the beam aim so puppet poses land on this frame's reconciled world.
        missileLane.maintain(snapshot, freshSnapshot);
        // The mtrack instrument samples AFTER the lane's pose writes so
        // a puppet's logged pos is this frame's applied pose, not the pre-adoption one.
        mtrack.advance(snapshot, freshSnapshot);
        // DEM presenter AFTER the missile lane so beam matching and the
        // drone slave both read this frame's adopted puppet poses.
        demLane.advance(snapshot, freshSnapshot);
        // Convergence tail: the adopt-time retreat clear (CommandTransferLane
        // own-COMMIT) can no-op on the engine's drive-off one-frame latency (its guard
        // + its isOn flip) — re-run drive-off + flag-clear until isRetreating()
        // reads false, bounded. Exhaustion while still retreating is LOUD: the bit-9 emitter
        // would keep crossing a spurious TRUE (the server quarantine belts the
        // edge, but the root did not clear — that log is the re-fly's named observable).
        // Once a second, cancel direct RETREAT orders / stray retreat flags
        // on replay-driven side-0 replicas (the yellow map-arrow fix; each clear logs its tuple).
        orderSweep.advance(engine, replayDrivenShips, ownShip, this);
        if (ownAdoptRetreatClearLeft > 0 && ownShip != null) {
            try {
                if (!ownShip.isRetreating()) {
                    ownAdoptRetreatClearLeft = 0;
                } else {
                    ownShip.turnOffTravelDrive();
                    ownShip.setRetreating(false, false);
                    ownAdoptRetreatClearLeft--;
                    if (ownAdoptRetreatClearLeft == 0) {
                        PilotWatchLog.error("cc-transfer adopt retreat-clear NOT CONVERGED"
                                + " — own body still isRetreating() after the re-run budget"
                                + " (local assignment re-set?)", null);
                    }
                }
            } catch (Throwable t) {
                ownAdoptRetreatClearLeft = 0;
            }
        }
        // Own-drone facing APPLY — while the own hull is a ridden drone
        // replica the server's OrbitDroneDrive authors facing and the wire own-pose carries
        // it; pin the local body to it every frame (lastOwnPose survives inter-snapshot
        // frames) + zero angVel so the next physics step does not integrate over the write
        // (the renderPose tug-of-war law). The local autopilot spin this overrides
        // is the W3-L sideways-spray mechanism.
        if (ownShip != null && lastOwnPose != null
                && "apply".equals(OWN_DRONE_FACING_MODE)
                && DroneGateLane.isDroneReplica(ownShip)) {
            ownShip.setFacing(lastOwnPose.facing());
            ownShip.setAngularVelocity(0f);
        }
        // The XFER pose pin is advance()'s FINAL statement — the
        // engine runs plugins LAST (transfer mgr @1346 / pod AI @1358 / pod physics @1386 /
        // plugins @1547), so this hard-set is the last pod/transit-hull pose word this frame.
        xfer.pinPump(snapshot);
    }

    /** The first snapshot entity that is not {@code self}, or {@code null} if there is none. */
    static ServerEntityPose firstOther(ServerSnapshotFrame snapshot, ServerEntityPose self) { // package: SeatNameHullBarLane 1v1 foe resolve
        for (ServerEntityPose pose : snapshot.entities()) {
            if (self == null || pose.entityId() != self.entityId()) {
                return pose;
            }
        }
        return null;
    }

    /** The v8 PROJECT-OWNED wire state code → engine phase (charter R1 mapping; null = future code). */
    static ShipSystemAPI.SystemState systemStateForCode(int code) { // package: rejoin lane cloak conform
        switch (code) {
            case 0: return ShipSystemAPI.SystemState.IDLE;
            case 1: return ShipSystemAPI.SystemState.IN;
            case 2: return ShipSystemAPI.SystemState.ACTIVE;
            case 3: return ShipSystemAPI.SystemState.OUT;
            case 4: return ShipSystemAPI.SystemState.COOLDOWN;
            default: return null;
        }
    }

    /**
     * The flux-pin BOOST-CLOCK discipline (zero-flux boost flicker fix).
     * The engine's anti-flicker law:
     * {@code isEngineBoostActive} needs a debounce clock to exceed 1.0 s of continuous
     * at-or-below-threshold time (threshold =
     * {@code getZeroFluxMinimumFluxLevel()}), and EVERY {@code increaseFlux} that leaves flux
     * strictly above the threshold zeroes that clock — so in vanilla the boost can
     * never re-engage (with its sound) faster than once per second. {@code setCurrFlux} performs
     * NO reset: a reconcile pin that adopts an above-threshold authoritative value
     * leaves the clock full, and the replica tracker's own per-frame dissipation below a
     * near-threshold sample then re-engages the boost EVERY FRAME until the next pin restores it
     * — the reported "zillion times a second" toggle. Mirror the engine's own write
     * discipline after every pin: value above threshold ⇒ {@code forceOffEngineBoost()} (the
     * engine's public clock-zero). The concrete tracker
     * method is not on {@code FluxTrackerAPI}, so it is bound once via {@code java.lang.invoke}
     * on the runtime class (loader B denies {@code java.lang.reflect}, allows invoke —
     * check-b-deny-refs). Fail-soft: on any bind/invoke failure the guard turns itself off for
     * the session (pins keep working; only the flicker guard degrades) and logs once.
     *
     * @return true when this pin zeroed the clock (for the per-lane counters)
     */
    static boolean settleBoostClockAfterFluxPin(ShipAPI ship, FluxTrackerAPI ft, float writtenCurr) {
        if (boostClockZeroUnavailable || ship == null || ft == null) {
            return false;
        }
        try {
            float max = ft.getMaxFlux();
            if (!(max > 0f) || !Float.isFinite(writtenCurr)) {
                return false;
            }
            float threshold = ship.getMutableStats().getZeroFluxMinimumFluxLevel().getModifiedValue();
            if (!Float.isFinite(threshold) || !(writtenCurr / max > threshold)) {
                return false; // strict >, the engine's own comparison — at-threshold does NOT reset
            }
            java.lang.invoke.MethodHandle h = boostClockZeroHandle;
            Class<?> cls = ft.getClass();
            if (h == null || boostClockZeroOwner != cls) {
                h = java.lang.invoke.MethodHandles.publicLookup().findVirtual(
                        cls, "forceOffEngineBoost", java.lang.invoke.MethodType.methodType(void.class));
                boostClockZeroOwner = cls;
                boostClockZeroHandle = h;
            }
            h.invoke(ft);
            return true;
        } catch (Throwable t) {
            boostClockZeroUnavailable = true;
            PilotWatchLog.info("predict flux-pin boost-clock zero UNAVAILABLE (flicker guard off"
                    + " for this session): " + t);
            return false;
        }
    }

    /**
     * A full lane (see
     * {@link PredictKnobConfig#OWN_ENGINE_STATE_MODE}) — the own-ship ENGINE-STATE meter/apply. Meter: compare the
     * OWN local controller's derived 3-state code against the carried authoritative v6
     * {@code controllerState}, the same compare the foe lane runs ({@link FoeStateLaneCoordinator#maintainFoeEngineState}).
     * Apply (opt-in): hand the carried per-engine masks to {@link FoeStateLaneCoordinator#applyEngineState} — the
     * meter-first shipping condition ("no write ships until this meter ever disagrees", baseline
     * 0x0) was MET on a later run (548/5050 disagree across a server-side flameout the
     * replica never mirrored). Formerly gated with the weapon-state knob; now its
     * own knob {@code cc.predict.ownEngineState} (default meter keeps the recorded behavior).
     */
    /** Close the open own-engine disagree window into the streak counters
     *  (streaks n / max ticks) — called at the first agreeing carried frame and the round reset. */
    private void closeOwnEngineStreak() {
        if (ownEngineStreakStartTick == Long.MIN_VALUE) {
            return;
        }
        long ticks = Math.max(0L, ownEngineStreakLastTick - ownEngineStreakStartTick);
        ownEngineStreaks++;
        if (ticks > ownEngineMaxStreakTicks) {
            ownEngineMaxStreakTicks = ticks;
        }
        ownEngineStreakStartTick = Long.MIN_VALUE;
        ownEngineStreakLastTick = Long.MIN_VALUE;
    }

    private void maintainOwnEngineState(ShipAPI own, ServerEntityPose ownPose, boolean freshSnapshot,
            long simTick) {
        if (!OWN_ENGINE_METER || !freshSnapshot || own == null || ownPose == null || !own.isAlive()) {
            return;
        }
        try {
            if (!ownPose.hasEngineState()) {
                ownEngineFramesMissing++;
                ownEngineStall.release(own, simTick); // weapon-lane parity — no engine tier ⇒ no stall
                return;
            }
            ownEngineFramesCarried++;
            // own-hull malfunction/regen suppression rides ONLY with the
            // apply lane live (the gate lives inside the method) — wire stays the sole author
            compDmgShield.ensureOwnEngineFace(own, ownPose, simTick);
            ShipEngineControllerAPI ctrl = own.getEngineController();
            if (ctrl == null) {
                return;
            }
            int localState = ctrl.isFlamingOut() ? 1 : (ctrl.isDisabled() ? 2 : 0);
            if (localState == ownPose.engineControllerState()) {
                ownEngineAgree++;
                closeOwnEngineStreak(); // M4(5): first agreeing carried frame closes the window
            } else {
                ownEngineDisagree++;
                if (ownEngineStreakStartTick == Long.MIN_VALUE) {
                    ownEngineStreakStartTick = simTick;
                }
                ownEngineStreakLastTick = simTick;
                // A disagree streak past 120 ticks (2 s — a storm, never an edge
                // blip) re-arms the shared budget so the storm's APPLY/disagree lines land.
                if (simTick - ownEngineStreakStartTick > 120) {
                    weaponLane.reopen(200, 3);
                }
                // Shares the weapon lane's log cap (one rollout lane).
                // @tick + ticks-since-lifecycle-edge so the next
                // own-engine disagree burst pins its field (kill-window artifact vs mid-round real).
                weaponLane.log("predict own-engine disagree @tick=" + simTick
                        + " ticksSinceEdge=" + (fireLanes.lastLifecycleEdgeTick == Long.MIN_VALUE
                                ? "n/a" : String.valueOf(simTick - fireLanes.lastLifecycleEdgeTick))
                        + ": local=" + localState
                        + " auth=" + ownPose.engineControllerState()
                        + " disabledMask=0x" + Integer.toHexString(ownPose.engineDisabledMask())
                        + " permMask=0x" + Integer.toHexString(ownPose.enginePermDisabledMask()));
            }
            if (OWN_ENGINE_APPLY) {
                foeState.applyEngineState(own, ctrl, ownPose, simTick, true);
            }
        } catch (Throwable ignore) {
            // presentation-side meter/apply; never break the watch
        }
    }

    /**
     * Contact/step-change detector + ONE-shot own-ship velocity re-base (see
     * {@link PredictKnobConfig#STEP_REBASE_ENABLED}). Called on FRESH snapshots only: diffs the authoritative velocity
     * against the previous fresh frame's, normalized per sim tick (a 69 u/s step stays detectable up to a
     * 13-tick snapshot gap; measured cadence is 1 tick/snapshot). Past {@link PredictKnobConfig#STEP_REBASE_THRESHOLD} the
     * local velocity takes the server's real engine-computed value once — replacing the phantom drag with
     * the real collision impulse. The max-step instrument and the baseline update run even when the flag is
     * off. Fail-soft; the baseline reseeds in {@code finally} so a hiccup cannot wedge the detector.
     */
    /**
     * Advance the own-burst latch on a fresh frame (field block doc): a locally
     * active own ship system re-arms the expiry; the off→on edge counts a new burst window. The
     * backwards-clock self-heal mirrors the under-fire latch (round restart). Fail-soft.
     */
    private void updateOwnBurstLatch(ShipAPI own, long tickNow) {
        try {
            ShipSystemAPI sys = own.getSystem();
            // Arithmetic delegated to the pure BurstWindowLaw so the offline replay
            // harness drives the EXACT shipped law (the VelocityGlideLaw extraction pattern).
            burstUntilTick = BurstWindowLaw.advance(sys != null && sys.isActive(), tickNow, burstUntilTick);
            boolean live = BurstWindowLaw.live(tickNow, burstUntilTick);
            if (live && !burstLatchWasLive) {
                burstWindows++;
            }
            burstLatchWasLive = live;
        } catch (Throwable ignore) {
            // presentation-side instrument; never break the watch
        }
    }

    /** exactly-one-owner read: true only when the APPLY-mode hold owns the own-velocity write. */
    private boolean ownVelReconEngaged() {
        return OWN_VELRECON_APPLY && velRecon.engaged();
    }

    /**
     * Drive {@link OwnVelReconcileLaw} one FRESH own-pose frame (see
     * {@link PredictKnobConfig#OWN_VELRECON_MODE} for the full design note; called under the same
     * jump-patience/mount/transit guards as the rebase, BEFORE it so the standdowns read this
     * frame's engage state). While engaged (engage frame included): one capped-slew write
     * toward the authoritative velocity, dt = the REAL elapsed sim time between fresh
     * frames so the slew rate is cadence-exact. Fail-soft like every reconcile write.
     * {@code ownFlyInHold} = the caller's per-frame composed fly-in read (the
     * local drive ON or auth-over-max — {@code OwnVelReconcileLaw.flyInHold}): while
     * held, the law holds the ARM latch and resets its streak (the bare 3-frame
     * streak armed on the burn ramp at fresh-per-tick cadence; the
     * drive-only read armed mid-glide on pose-adopted fly-ins).
     */
    private void maintainOwnVelReconcile(ShipAPI own, ServerEntityPose ownPose, long simTick,
            boolean ownFlyInHold) {
        if (!OWN_VELRECON_METER || own == null || !own.isAlive()) {
            return;
        }
        float authVx = ownPose.velX();
        float authVy = ownPose.velY();
        try {
            Vector2f vel = own.getVelocity();
            long dTicks = prevAuthVelSimTick == Long.MIN_VALUE ? 1L
                    : Math.max(1L, simTick - prevAuthVelSimTick);
            float gapX = authVx - vel.x;
            float gapY = authVy - vel.y;
            float gap = (float) Math.sqrt(gapX * gapX + gapY * gapY);
            boolean wasArmed = velRecon.armed();
            boolean edge = velRecon.advance(vel.x, vel.y, authVx, authVy, dTicks, ownFlyInHold);
            if (!wasArmed && velRecon.armed()) {
                // One line per life — the fly-in ARM latch witnessed convergence.
                PilotWatchLog.info("predict vel own-vel reconcile ARMED: gap=" + fmtSpeed(gap)
                        + " gate=" + fmtSpeed(OwnVelReconcileLaw.gate(dTicks))
                        + " simTick=" + simTick);
            }
            if (edge) {
                velReconEngages++;
                if (gap > velReconGapMax) {
                    velReconGapMax = gap;
                }
                if (velReconEngages <= OWN_VELRECON_LOG_MAX) {
                    PilotWatchLog.info("predict vel own-vel reconcile ENGAGE #" + velReconEngages
                            + " (" + OWN_VELRECON_MODE + "): gap=" + fmtSpeed(gap)
                            + " gate=" + fmtSpeed(OwnVelReconcileLaw.gate(dTicks))
                            + " dTicks=" + dTicks + " simTick=" + simTick
                            + " localVel=(" + fmtSpeed(vel.x) + "," + fmtSpeed(vel.y)
                            + ") authVel=(" + fmtSpeed(authVx) + "," + fmtSpeed(authVy) + ")");
                }
            } else if (velRecon.engaged()) {
                velReconHoldFrames++;
            }
            if (velRecon.engaged() && OWN_VELRECON_APPLY && gap > 0f) {
                // The capped-slew write, engage frame INCLUDED — the one-shot rebase
                // and the exponential hold both RETIRED; every write sub-felt by construction
                // (the ENGAGE log above keeps the FULL gap — no concealment).
                float step = OwnVelReconcileLaw.slewStep(gap,
                        dTicks / (float) ServerTickRate.ticksPerSecond());
                float f = step / gap;
                vel.x += f * gapX;
                vel.y += f * gapY;
                velReconSu += step;
                if (step < gap) {
                    velReconCapped++; // Writes where the cap bound (spread engages)
                }
            }
        } catch (Throwable ignore) {
            // presentation-side reconcile; never break the watch
        }
    }

    private void maintainStepRebase(ShipAPI own, ServerEntityPose ownPose, long simTick) {
        float authVx = ownPose.velX();
        float authVy = ownPose.velY();
        if (!Float.isFinite(authVx) || !Float.isFinite(authVy)) {
            return;
        }
        try {
            if (prevAuthVelSimTick != Long.MIN_VALUE && simTick > prevAuthVelSimTick) {
                float dvx = authVx - prevAuthVelX;
                float dvy = authVy - prevAuthVelY;
                float perTick = (float) Math.sqrt(dvx * dvx + dvy * dvy)
                        / Math.max(1L, simTick - prevAuthVelSimTick);
                lastAuthStepPerTick = perTick; // The velocity-glide gate's quiet input
                if (perTick > maxAuthVelStepSeen) {
                    maxAuthVelStepSeen = perTick;
                }
                if (STEP_REBASE_ENABLED && perTick > STEP_REBASE_THRESHOLD && own.isAlive()) {
                    // exactly-one-owner: an ENGAGED own-vel reconcile hold owns the
                    // own-velocity write — STEP-16 stands down (detector/baseline/meters keep
                    // running; the finally below still reseeds the baseline).
                    if (ownVelReconEngaged()) {
                        return;
                    }
                    // Inside a live burst window the "one-shot" re-base storms every ramp
                    // tick (369 fires measured, 32+ consecutive) and IS the felt yank:
                    // both sims run the same script, so suppress the write and let local converge
                    // naturally. Detector, baseline and meters keep running (the finally below).
                    if (BURST_EXEMPT_ENABLED && BurstWindowLaw.live(simTick, burstUntilTick)) {
                        burstStep16Suppressed++;
                        if (burstStep16Suppressed <= STEP_REBASE_LOG_MAX) {
                            PilotWatchLog.info("predict burst STEP-16 SUPPRESSED #" + burstStep16Suppressed
                                    + ": authStep=" + fmtSpeed(perTick) + "/tick simTick=" + simTick);
                        }
                        return;
                    }
                    Vector2f vel = own.getVelocity();
                    float beforeX = vel.x;
                    float beforeY = vel.y;
                    vel.x = authVx;
                    vel.y = authVy;
                    stepRebaseEvents++;
                    if (simTick <= burstUntilTick) {
                        burstStepRebaseEvents++; // Rebase landed inside a live burst window
                    }
                    if (stepRebaseEvents <= STEP_REBASE_LOG_MAX) {
                        PilotWatchLog.info("predict fidelity STEP-16 step-rebase #" + stepRebaseEvents
                                + ": authStep=" + fmtSpeed(perTick) + "/tick (dTick="
                                + (simTick - prevAuthVelSimTick) + ") simTick=" + simTick
                                + " localVel=(" + fmtSpeed(beforeX) + "," + fmtSpeed(beforeY)
                                + ") -> authVel=(" + fmtSpeed(authVx) + "," + fmtSpeed(authVy) + ")");
                    }
                }
            }
        } catch (Throwable ignore) {
            // presentation-side reconcile; never break the watch
        } finally {
            prevAuthVelX = authVx;
            prevAuthVelY = authVy;
            prevAuthVelSimTick = simTick;
        }
    }

    /**
     * Apply one drift-gated VELOCITY GLIDE step to the own ship (see
     * {@link PredictKnobConfig#VELOCITY_GLIDE_ENABLED} for the full design note). Called every active frame from
     * the own-ship reconcile block, after {@link #maintainStepRebase}: the {@link VelocityGlideLaw}
     * gate (no velocity-affecting control held — turn + system bits exempt — + authoritative copy coasting)
     * and cap decide whether to write;
     * a closed gate or refused write leaves the engine's native velocity completely untouched.
     * Stale feed = no glide (free-run). Fail-soft like every reconcile write.
     */
    private void maintainVelocityGlide(ShipAPI own, ServerEntityPose ownPose, float dt,
            boolean staleSnapshot, boolean freshSnapshot, long simTick) {
        if (!VELOCITY_GLIDE_ENABLED || staleSnapshot || own == null || !own.isAlive()
                || ownMountedModule != null // Module velocity is parent-derived
                // exactly-one-owner: an ENGAGED own-vel reconcile hold owns the
                // velocity write — glide + item-④ escape stand down until release.
                || ownVelReconEngaged()) {
            glideWasWriting = false;
            return;
        }
        try {
            Vector2f vel = own.getVelocity();
            float preX = vel.x;
            float preY = vel.y;
            // Item ④: fresh frames advance the above-cap persistence streak; a matured
            // streak takes the one-shot escape snap and this frame's reconcile write is done.
            if (freshSnapshot && maintainGlideEscape(vel, preX, preY, ownPose, simTick)) {
                glideWasWriting = false;
                return;
            }
            boolean wrote = VelocityGlideLaw.step(heldControls, lastAuthStepPerTick,
                    preX, preY, ownPose.velX(), ownPose.velY(), dt, glideOut);
            if (!wrote) {
                glideWasWriting = false;
                return;
            }
            float gapX = ownPose.velX() - preX;
            float gapY = ownPose.velY() - preY;
            float gap = (float) Math.sqrt(gapX * gapX + gapY * gapY);
            if (gap > maxGlideGapSeen) {
                maxGlideGapSeen = gap;
            }
            if (!glideWasWriting) {
                glideEngagements++;
                if (glideEngagements <= VELOCITY_GLIDE_LOG_MAX) {
                    PilotWatchLog.info("predict velocity-glide ENGAGE #" + glideEngagements
                            + ": gap=" + fmtSpeed(gap) + " authStep=" + fmtSpeed(lastAuthStepPerTick)
                            + "/tick simTick=" + lastSnapshotSimTick);
                }
            }
            vel.x = glideOut[0];
            vel.y = glideOut[1];
            glideFrames++;
            glideWasWriting = true;
        } catch (Throwable ignore) {
            // presentation-side reconcile; never break the watch
        }
    }

    /**
     * The ABOVE-CAP PERSISTENCE ESCAPE. {@link VelocityGlideLaw#step}
     * REFUSES {@code gap > MAX_GAP} (the drive-in/fork class belongs to the snap lanes), but hands-off
     * coasting keeps the auth copy QUIET so STEP-16 never fires, and the SNAP hatch is position-only —
     * an in-coast velocity gap above the cap had NO owner and PARKED (measured 90 u/s frozen for ~690
     * ticks after jets-boosted strafe reversals; glide meter frozen, {@code maxGapGlided=33} proving
     * sub-cap glide worked all round). Each FRESH frame advances the pure law's streak
     * ({@link VelocityGlideLaw#escapeTicks} — gate-open AND above-cap, sim-tick clock, stale-resume
     * clamped); a streak of {@link VelocityGlideLaw#escapeThresholdTicks} (~1 s) takes the snap lane's own
     * write ONCE: local velocity = authoritative, the exact STEP-16 one-shot shape. The deploy
     * drive-in cannot mature it live ({@link RejoinRebaselineLane#adoptInitialWorld} zeroes the gap on the first
     * snapshot; the QUIET gate holds the streak while auth still maneuvers). Returns true when this
     * frame's write was the escape snap. Offline rows: verify-velocity-glide V9/V10.
     */
    private boolean maintainGlideEscape(Vector2f vel, float preX, float preY,
            ServerEntityPose ownPose, long simTick) {
        long dTicks = escapePrevSimTick == Long.MIN_VALUE ? 1L : simTick - escapePrevSimTick;
        escapePrevSimTick = simTick;
        // FREEZE the streak inside a live burst window — hold, don't advance, don't
        // fire. The parked-auth ESCAPE class (all 4 specimens OUTSIDE windows) keeps
        // its guard untouched; the counter discloses any in-window hold (never-conceal-desync).
        if (BURST_EXEMPT_ENABLED && BurstWindowLaw.live(simTick, burstUntilTick)) {
            if (aboveCapTicks > 0L) {
                burstEscapeFreezes++;
            }
            return false;
        }
        aboveCapTicks = VelocityGlideLaw.escapeTicks(heldControls, lastAuthStepPerTick,
                preX, preY, ownPose.velX(), ownPose.velY(), aboveCapTicks, dTicks);
        if (!VelocityGlideLaw.escapeDue(aboveCapTicks)) {
            return false;
        }
        float gapX = ownPose.velX() - preX;
        float gapY = ownPose.velY() - preY;
        float gap = (float) Math.sqrt(gapX * gapX + gapY * gapY);
        vel.x = ownPose.velX();
        vel.y = ownPose.velY();
        glideEscapeEvents++;
        if (gap > maxGapEscaped) {
            maxGapEscaped = gap;
        }
        aboveCapTicks = 0L;
        if (glideEscapeEvents <= VELOCITY_GLIDE_LOG_MAX) {
            PilotWatchLog.info("predict velocity-glide ESCAPE #" + glideEscapeEvents
                    + ": gap=" + fmtSpeed(gap)
                    + " localVel=(" + fmtSpeed(preX) + "," + fmtSpeed(preY)
                    + ") -> authVel=(" + fmtSpeed(ownPose.velX()) + "," + fmtSpeed(ownPose.velY())
                    + ") simTick=" + simTick);
        }
        return true;
    }

    /**
     * Mirror a server-authoritative ABSOLUTE hull (the AWSS {@code hitpoints}) onto a local ship via the
     * engine's own {@code setHitpoints}, so the window shows the REAL damage the server computed and a
     * local (predicted) hit can never decide death — the mirror overwrites any local damage back to truth
     * each frame. Held strictly above 0 so the engine's own liveness check never trips here; the explicit
     * authoritative death frame (hull<=0 / foe absent) drives the real kill via {@link OwnSeatLifecycleLane#killFoe}. Fail-soft.
     */
    void mirrorHull(ShipAPI ship, float authHitpoints) {
        if (ship == null || !ship.isAlive() || !Float.isFinite(authHitpoints)) {
            return;
        }
        try {
            float max = ship.getMaxHitpoints();
            if (max <= 0f) {
                return;
            }
            float clamped = authHitpoints < 0f ? 0f : Math.min(authHitpoints, max);
            ship.setHitpoints(Math.max(1f, clamped));
        } catch (Throwable ignore) {
            // presentation only — a failed hull mirror must never disturb the reconcile or the watch
        }
    }

    /**
     * The re-admission absence-hold clock, shared by the own killOwn edge
     * and {@link FoeStateLaneCoordinator#maintainRemoteEntities}'s killRemote guard: true while
     * the one-shot hold is pending and inside {@link #RE_ADMIT_ABSENCE_HOLD_TICKS}. The clock
     * starts only at the first simTick STRICTLY NEWER than the arm-frame tick (a pre-clear stale
     * view re-presents its old tick and can neither start nor expire it — the W5-D4 race); until
     * then every consult holds. The call that finds the bound exceeded clears the pending latch
     * (one-shot). The per-frame pump at the adopt gate owns start + expiry in practice, so the
     * death-edge consults are pure reads; the own edge consults BEFORE the remote block each
     * frame, so a held own death can never lose the expiry to a remote-side clear.
     */
    boolean reAdmitAbsenceHoldActive(long simTick) {
        if (!reAdmitAbsenceHoldPending) {
            return false;
        }
        if (reAdmitAbsenceHoldFromTick < 0L) {
            if (simTick <= reAdmitAbsenceHoldArmTick) {
                return true; // pre-clear (or frozen) tick — the refill hasn't shown yet
            }
            reAdmitAbsenceHoldFromTick = simTick;
        }
        if (simTick - reAdmitAbsenceHoldFromTick <= RE_ADMIT_ABSENCE_HOLD_TICKS) {
            return true;
        }
        reAdmitAbsenceHoldPending = false;
        PilotWatchLog.info("predict reconnect: re-admission absence-hold EXPIRED at tick="
                + simTick + " (held since tick=" + reAdmitAbsenceHoldFromTick
                + ") — absence-death adjudication resumes");
        return false;
    }

    /**
     * own-spawn fly-in burn (seconds), the 4-arg {@code spawnShipOrWing}
     * overload's 4th arg: {@code deploy()}'s travel-drive branch fires only at burn ≥ 1 and the
     * 3-arg overload passes 0.0F, so own spawns had no plume/sound. An explicit burn ZEROES the
     * engine's {@code Math.random()} jitter term (deterministic, parity-safe);
     * {@code traveldrive.system} is ENGINE_MOD {@code engineGlow*} + the
     * {@code system_travel_drive_loop} sound with NO flux cost — the engine's OWN fly-in FX,
     * presentation only (the server's spawn kinematics stay the authoritative pose stream the
     * client mirrors). FOE replica spawns deliberately stay 3-arg (working posture).
     *
     * <p>The burn length: the value is
     * a tunable FEEL dial — knob {@code cc.match.flyinBurnSec} (the same name the server's
     * {@code FLY_IN_BURN_DUR} resolves, ferried like {@code cc.match.seatNName}), FAIL-SOFT: unset
     * or unparsable = 4.0 (byte-identical to the prior constant; 1.5 s played as too little,
     * so 4 s was tried), clamped ≥ 1 (the engine {@code deploy()} travel-drive branch only fires at
     * burn ≥ 1). Tune at watch via {@code -D}, no jar roll.
     */
    static final float OWN_SPAWN_FLYIN_BURN_SEC = Math.max(1.0f,
            KnobParse.floatOr("cc.match.flyinBurnSec", 4.0f, "cfg.match.flyinBurnSec.unparseable"));

    /**
     * The fly-in speed parity row — knob {@code cc.predict.flyinParity}
     * {@code apply} (default) | {@code off}, STRICT (the pairClass idiom: a typo fails loud).
     * APPLY arms the engine's OWN travel drive on mid-match foe-replica mints via
     * {@link #replicaFlyinBurn}; OFF = the legacy B2(d) burn-0 posture, whole-family reversible.
     * The "re-arm instead of kill at adopt" design was REJECTED at build on measured
     * grounds: {@code turnOnTravelDrive} no-ops while the drive system still reads ON,
     * and the round-open deployAll burn is CONTEXT-lane (initial 1+1 s with
     * ~1.8 s eaten by the deploy pre-roll) — a knob-length
     * adopt re-arm would MINT ~2 s of anti-parity. The adopt kill therefore STAYS:
     * it matches the server's ~spent round-open drive.
     */
    private static final boolean FLYIN_PARITY = resolveFlyinParity();

    private static boolean resolveFlyinParity() {
        String raw = System.getProperty("cc.predict.flyinParity", "apply").trim();
        if (raw.equalsIgnoreCase("apply")) {
            return true;
        }
        if (raw.equalsIgnoreCase("off")) {
            return false;
        }
        throw new IllegalArgumentException("cc.predict.flyinParity: apply|off, got " + raw);
    }

    /**
     * The burn arg for a foe-replica mint. The server passes
     * {@code FLY_IN_BURN_DUR} on BOTH mid-match spawn lanes (remount + wave append),
     * so mid-match replicas (appended wave hulls + remounted
     * remotes) arm the same shared {@code cc.match.flyinBurnSec} burn: drive ON ⇒ raised
     * max-speed clamp + latched-at-spawn auto-steer, so the replica INTEGRATES the fly-in at
     * 600+ instead of braking to cruise between snapshots (the cruise-lock —
     * over-max velocity with no movement commands draws a per-frame decelerate).
     * Round-open first-sight mints stay burn-0: the server's battle-start
     * deployAll rides the CONTEXT burn, spent by sight time (measured). The mint's own
     * ~1.8 s pre-roll displacement past the wire pose is absorbed the SAME frame —
     * {@link #spawnRemoteEntities} runs before {@link FoeStateLaneCoordinator#maintainRemoteEntities}, whose
     * &gt;SNAP_DISTANCE reconcile hard-snaps the body back before render.
     *
     * <p>The gated match RESET is a third burn lane — the server
     * re-deploys the roster with every seat excluded, then flies each DEPLOYABLE seat in through
     * {@code midDeploySeat} ({@code FLY_IN_BURN_DUR}; an occupied-but-not-yet-
     * deployable seat flies in later through the T1 lane, burn-armed too), so a SEAT row's reset
     * re-bind is {@link RemoteEntity#remounted} (armed in {@link #applyMatchReset}, which DISARMS
     * every non-seat row); non-seat roster rows ride the reset's deployAll context burn, spent by
     * sight like round-open, and stay burn-0. The flag is consumed at the mint (per-mint event):
     * a remounted bot no longer burns at every later round-open re-bind. A real run graded the
     * burn-0 seat re-bind as the "shorter fly-in after /wrap". Known residue:
     * non-mint binds (transfer COMMIT, module mount, station adopt) leave the flag for the
     * row's next mint, and a round-1 mid-round JOIN of a remote seat still mints burn-0 (pre-seed
     * rows are un-armed) — pool row.
     *
     * <p>(77) FIGHTER exclusion, a vanilla-like fix:
     * hangar-born fighters are ENGINE-born on the server (native bay launch — the growth seam
     * only ANNOUNCES them, no mod spawn, no {@code FLY_IN_BURN_DUR}), yet they arrive as RAPP
     * per-fighter rows ⇒ {@code appended} — so the parity argument runs
     * in REVERSE for them: the burn itself is the anti-parity (server twin never burns), felt
     * as a player's "engines flare super hard" at every bay birth (one recorded run had
     * 284 fighter mints ride the burn). FIGHTER hull-size variants therefore mint
     * burn-0. Cruise-lock safe: an unburned server fighter never exceeds the shared hull max,
     * so the replica integrates its poses without the raised clamp. Measured residue [I]:
     * {@code drone_terminator} rows (10 that run) still burn — designation "Drone", not
     * FIGHTER hull size; unreported by the owner, same class if it ever presents.
     */
    private static float replicaFlyinBurn(RemoteEntity re, String variantId) {
        if (!FLYIN_PARITY || flyinBurnFor(re.appended, re.remounted, re.appendAtRest, false) == 0f) {
            return 0f; // no mid-match fly-in on the server (or a posed at-rest append): no variant read
        }
        boolean fighter = false;
        try {
            ShipVariantAPI variant = Global.getSettings().getVariant(variantId);
            fighter = variant != null && variant.getHullSpec() != null
                    && variant.getHullSpec().getHullSize() == ShipAPI.HullSize.FIGHTER; // (77)
        } catch (Throwable ignore) {
            // fail-soft: an unresolvable variant keeps the pre-(77) parity burn; the mint
            // itself will surface the real problem loudly at spawn
        }
        return flyinBurnFor(re.appended, re.remounted, re.appendAtRest, fighter);
    }

    /**
     * The pure replica burn law. No mid-match lane ⇒ 0;
     * an APPENDED row the server placed AT REST (RAPP v5 bit0: {@code burnDur 0}, no travel drive)
     * ⇒ 0 — its parity burn would be a client-only drive (the jets-refused class,
     * the engine refuses USE_SYSTEM under it); a REMOUNT keeps the burn even on
     * an at-rest-appended row (a remount is a server fly-in, {@link RemoteEntity#remounted}); an
     * engine-born bay FIGHTER ⇒ 0 ((77)); else the shared {@link #OWN_SPAWN_FLYIN_BURN_SEC}.
     * {@code FLYIN_PARITY} stays in the caller. The body lives in {@link FlyinBurnLaw} (API-free).
     */
    static float flyinBurnFor(boolean appended, boolean remounted, boolean atRest, boolean fighter) {
        return FlyinBurnLaw.burnFor(appended, remounted, atRest, fighter, OWN_SPAWN_FLYIN_BURN_SEC);
    }

    /**
     * The REMOTE fly-in respawn: a killed registry entry's wire id reappeared
     * ALIVE (the server remounted that seat's fresh hull at the SAME id). Reset the entry UNBOUND —
     * the {@link #applyMatchReset} registry idiom, verbatim — so the proven spawn-on-sight adopt
     * ({@link #spawnRemoteEntities}) re-fields it from the wire next frame; the outgoing body goes
     * through {@link RemoteRespawnCorpseLane}: a server-mirrored corpse (family latched to the
     * wire) is KEPT as wreckage (the old blanket disposal
     * deleted it on the other client at every respawn); anything else is disposed as client-only
     * residue. If the corpse anchored the HUD/witness/parity foe, the anchor
     * clears and re-points at the next side-1 spawn (the foeShip==null rule in
     * spawnRemoteEntities).
     */
    void respawnRemote(RemoteEntity re) { // package: RosterAppendLane B1b repoint drops stale bodies through it
        if (re.ship == foeShip) {
            foeShip = null; // re-anchored by the next side-1 spawn
        }
        // A mid-match reappearance = the server REMOUNT lane (burnDur =
        // FLY_IN_BURN_DUR) — mark it so the re-mint arms the parity burn. Drones/fighters
        // relaunch from their carrier (no server fly-in): never marked.
        if (re.ship == null
                || (!re.ship.isFighter() && !DroneGateLane.isDroneReplica(re.ship))) {
            re.remounted = true;
        }
        // Outgoing body: keep a server-mirrored corpse, dispose residue (the old blanket disposal
        // WAS the deletion seen in play). Guarded inside: never throws.
        // Release the god mirror FIRST, unconditionally — the corpse lane keeps
        // live station modules alive, and nothing maintains an unbound body again.
        godMirror.releaseIfCarrying(re.ship);
        respawnCorpse.releaseOutgoingBody(re);
        re.ship = null;
        re.killed = false;
        re.deathTick = Long.MIN_VALUE; // r1 — a re-life is alive
        re.reapTick = Long.MIN_VALUE; // A same-id re-life un-flags a pending reap
        re.capActive = true;
        re.sysServerOpen = null;
        re.sysOpenTick = FoeSystemWindowLaw.NO_WINDOW;
        re.sysSeeded = false;
        re.jump.reset(); // Fresh hull, fresh class read + fresh fences
        re.ledger = null; // Fresh hull ⇒ fresh ledger, reseeded from WIRE at first maintain
        re.reasonHoldSinceTick = Long.MIN_VALUE; // A fresh life starts unheld
        re.rekeyPoseHold = false; // The re-key gap ended with this life
        re.rekeyHoldSinceTick = Long.MIN_VALUE;
        re.dismountDepartHold = false; // Ends with the life (repoint/real respawn)
        re.dismountDepartHoldSinceTick = Long.MIN_VALUE;
        foeInterp.resetRoundLocal(); // Never lerp across a respawn re-adopt
        foeStep.resetRoundLocal(); // A fresh hull mints no step
        foeFacing.resetRoundLocal(); // A fresh hull mints no facing step
        remoteUnbound++;
        remoteBindCompleteLogged = false;
        remoteRespawns++;
        PilotWatchLog.info("predict respawn: REMOTE entity " + re.entityId + " (side " + re.side
                + ") wire id reappeared ALIVE after kill -> reset UNBOUND; spawn-on-sight re-adopts"
                + " (respawns=" + remoteRespawns + ")");
    }

    /**
     * Capture this frame's live human AIM + FIRE on the render thread (the net thread sends them UP). The same
     * faithful raw pilot input Starsector itself takes: the cursor's world bearing from the own ship
     * ({@code aimDeg}) and the left mouse button as the fire trigger. Movement is NO LONGER read here as a
     * hand-rolled key bitset (the FALSIFIED double-interpretation); it is captured in {@link #advance} as the
     * engine's OWN emitted command ordinals. Events are observed, not consumed — with ONE exception:
     * while the chat box is open the {@link ChatInputLaw} swallows every KEYBOARD event
     * and every keyboard-driven ship verb is blocked for the frame ({@link #blockShipKeyCommandsWhileTyping}); mouse events
     * are never consumed, so aim/fire below stay live even mid-typing. (Aim/fire are read here, not
     * in advance, because they need no command-queue timing — they read Mouse state directly.)
     */
    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        // Wave-skip F11 collides with vanilla's hide-HUD
        // toggle (CombatState.hideHud, misc-input loop — it skips consumed events, and this
        // pre-core hook runs before it: the same seam ChatInputLaw's swallow already proves
        // live). Log the bridge row HERE, then consume EVERY F11 event (down/repeat/up) so the
        // vanilla toggle never sees the key. WATCH windows only — vanilla:* control windows
        // must stay vanilla-pure, so they keep today's un-consumed behavior. Sits BEFORE the
        // early-returns below to keep the old advance()-loop reach (it too ran unconditionally).
        // Debug gate: debug-flagged matches only (WELCOME bit1) — OFF must
        // NOT consume: the vanilla hide-HUD meaning of F11 is restored for public testers
        // (the key-theft defect), so the whole block gates, not just the log row.
        // The admin path widens the gate: an ADMIN seat (WELCOME bit3, per-seat) also owns
        // F11/F12 — but it asks the SERVER via the /wave chat verbs (door re-checks the
        // session's admin flag; the serve loop mails back a sim-thread RECEIPT carrying the
        // verb's real resulting state, never a door-side guess),
        // where the debug path stays the launcher's log-bridge (a debug watch has no door).
        if (events != null && !vanillaWindow
                && (MatchConfigFromWire.clientDebug() || MatchConfigFromWire.seatAdmin())) {
            for (InputEventAPI event : events) {
                // isConsumed() FIRST — getEventValue()/isRepeat() THROW on a consumed event
                if (event != null && !event.isConsumed() && event.isKeyboardEvent()) {
                    int key = event.getEventValue();
                    if (key == WAVE_SKIP_KEY || key == WAVE_PAUSE_KEY) {
                        if (event.isKeyDownEvent() && !event.isRepeat()) {
                            if (MatchConfigFromWire.seatAdmin()) {
                                String verb = key == WAVE_SKIP_KEY ? "/wave skip" : "/wave pause";
                                try {
                                    session.sendChat(verb);
                                    PilotWatchLog.info("admin wave verb sent " + verb
                                            + " tick=" + lastSnapshotSimTick);
                                } catch (Throwable t) {
                                    // Fail-soft (the CommandTransferLane trigger idiom): the
                                    // key stays consumed, the miss is logged, nothing breaks.
                                    PilotWatchLog.info("admin wave verb send FAILED " + verb
                                            + " (" + t + ")");
                                }
                            } else {
                                // The launcher bridges this row to the operator
                                // 'skip-wave' verb (kill wave field + advance the schedule,
                                // host-side); F12 → 'pause-wave' (toggle) same way.
                                PilotWatchLog.info((key == WAVE_SKIP_KEY
                                        ? "wave-skip request tick=" : "wave-pause request tick=")
                                        + lastSnapshotSimTick
                                        + (key == WAVE_SKIP_KEY ? " key=F11" : " key=F12"));
                            }
                        }
                        event.consume();
                    }
                }
            }
        }
        if (INTENT_SOURCE != null) {
            return; // Synthetic seat: the recording owns aim/fire — never poll Mouse
        }
        if (engine == null) {
            return;
        }
        // picker-Enter commission: MODAL-FIRST feed order — an OPEN panel
        // consumes its keys (selection Enter included) BEFORE ChatInputLaw can act on them,
        // so a picker selection never re-opens the chat box (both lanes skip consumed events).
        // The pre-fix order (chat first) was commented "belt only", which covered only the
        // box-open case (picker PARKED = all-PASS); the reverse collision — panel OPEN, box
        // closed, fresh Enter = chat's own open key with a PASS disposition — double-acted.
        // An open BOX still owns the keyboard: the picker is PARKED = all-PASS by law.
        picker.feedPickerInput(events);
        chat.feedChatInput(events);
        voiceHud.feedInput(events); // PTT keys AFTER the text lanes — a typed Space is theirs
        // Transit input gate (b), AFTER the chat feed
        // (chat/acks/F11 keep riding): while the seat rides the transfer pod the server has
        // parked its intents — aim/fire capture parks too, and the fire level + edge baseline
        // read neutral. Idempotent every frame; exit re-arm is automatic.
        if (xfer.ownTransit()) {
            fireHeld = false;
            fireEdgePrevHeld = false;
            return;
        }
        ShipAPI aimFrom = ownShip != null ? ownShip : engine.getPlayerShip();
        if (aimFrom != null) {
            // Non-null ONLY while a ship-AI drives the own hull — the AI's aim (firing
            // weapon's ray, else its ship target) rides the wire instead of the idle physical cursor.
            Vector2f aiAim = autopilotFire.aiAimPoint(aimFrom);
            ViewportAPI vp = engine.getViewport();
            if (vp != null) {
                float mouseWorldX = aiAim != null ? aiAim.x : vp.convertScreenXToWorldX(Mouse.getX());
                float mouseWorldY = aiAim != null ? aiAim.y : vp.convertScreenYToWorldY(Mouse.getY());
                Vector2f from = aimFrom.getLocation();
                aimDeg = bearingDeg(from.x, from.y, mouseWorldX, mouseWorldY);
            }
            // PINT v3: keep the aim WORLD POINT too — from the engine's OWN getMouseTarget()
            // (zoom/UI-scale handled; the value the server-side mount slew needs), not the raw viewport
            // math above (kept byte-identical for aimDeg). Fail-soft: keep the last finite point.
            try {
                Vector2f mt = aiAim != null ? aiAim : aimFrom.getMouseTarget();
                if (mt != null && Float.isFinite(mt.x) && Float.isFinite(mt.y)) {
                    aimWorldX = mt.x;
                    aimWorldY = mt.y;
                }
            } catch (Throwable ignore) {
                // aim-point read is presentation-side input; keep the prior point on any hiccup
            }
        }
        boolean firePressed = Mouse.isButtonDown(0);
        // The (re)entry swallow latch — a mouse1 still held from the menu
        // join/leave click is not a fire intent. Until the first release: the wire sample reads
        // neutral, and FIRE/USE_SELECTED_GROUP are frame-blocked so the engine's own polled fire
        // (raw Mouse.isButtonDown, consume-immune) can't fire the local hull off the same click.
        if (swallowFireUntilMouseRelease) {
            if (firePressed) {
                ShipAPI blockTarget = engine.getPlayerShip();
                if (blockTarget != null) {
                    blockTarget.blockCommandForOneFrame(ShipCommand.FIRE);
                    blockTarget.blockCommandForOneFrame(ShipCommand.USE_SELECTED_GROUP);
                }
                firePressed = false;
            } else {
                swallowFireUntilMouseRelease = false;
                PilotWatchLog.info("predict fire-swallow: (re)entry latch cleared at first"
                        + " mouse1-up");
            }
        }
        // Under a ship-AI (U autopilot / a command-map order) the held lane ALSO carries
        // the AI's selected-group fire — the level its own weapons show (AutopilotFireLane); same trust as
        // mouse-0, zero wire change. AFTER the swallow latch: the latch guards the mouse, never the AI.
        firePressed = autopilotFire.deriveHeld(firePressed);
        // Neutral while the drive lock held — AFTER the AI fold, BEFORE the edge count.
        firePressed = driveLock.gateWireFire(firePressed);
        // Kit half: a click made while ownKilled is latched is no press on the next
        // hull — the wire level reads neutral and the level is tracked WITHOUT counting an edge (the
        // swallow shape), so the first fresh-hull frame carries no edge minted while dead. The
        // latch also covers one LIVE body: the transit pod of a transfer from a wreck (weaponless,
        // so the neutral level costs nothing; the held-bit gate below never sees it — transit first).
        if (ownKilled) {
            fireEdgePrevHeld = firePressed;
            fireHeld = false;
            return;
        }
        // PINT v5: count the press's rising edge into the wrapping 0..255 fireEdgeSeq
        // BESIDE the level sample — the cumulative form of the same faithful input read, nothing
        // consumed. The INTENT_SOURCE early-return above keeps recorded seats at 0 (legacy-inert).
        if (firePressed && !fireEdgePrevHeld) {
            fireEdgeSeq = (fireEdgeSeq + 1) & 0xFF;
        }
        fireEdgePrevHeld = firePressed;
        fireHeld = firePressed;
    }

    /**
     * Twin gate (b), re-cut after a play report
     * falsified both halves of the original adjudication: while typing, block EVERY
     * keyboard-driven ship verb ({@link #MANEUVER_COMMAND_FOR_BIT} +
     * {@link #TYPING_BLOCKED_TAP_COMMANDS}) for THIS frame via {@code blockCommandForOneFrame}
     * — the one lever that works, because ship keys are engine-POLLED (see the tap-array
     * javadoc; consumption never reaches them). Two corrections over the earlier live shape:
     * (i) the target is {@code engine.getPlayerShip()} — the exact instance
     * {@code CombatState.controlPlayerShip} feeds commands to — never the plugin's own
     * {@code ownShip} field; (ii) the old {@code isAlive()/isEntityInPlay} guards are gone
     * (blocking on a dead/out-of-play hull is a no-op, and a false-negative guard silently
     * reopened the leak — the prime mod-side suspect for the reported 'S' escape). Blocked commands
     * go neutral in the {@link ShipEngineControllerAPI} booleans too, so the
     * {@link #captureOwnCommands} mirror UP reads neutral the same frame (no forced mask, no
     * reconcile fight). Package-private: ChatLane's and ListPickerLane's feed relays are the
     * in-package callers — the key-block generalization (chat-open OR
     * picker-open; blockCommandForOneFrame is idempotent, so the overlap frame is free).
     */
    void blockShipKeyCommandsWhileTyping() {
        CombatEngineAPI eng = engine;
        ShipAPI target = eng != null ? eng.getPlayerShip() : null;
        if (target == null) {
            return;
        }
        for (ShipCommand verb : MANEUVER_COMMAND_FOR_BIT) {
            target.blockCommandForOneFrame(verb);
        }
        for (ShipCommand verb : TYPING_BLOCKED_TAP_COMMANDS) {
            target.blockCommandForOneFrame(verb);
        }
    }

    /**
     * Snapshot the own ship's native-control MOVEMENT state into the volatile {@link #heldControls} semantic
     * bitmask the net thread ships UP, and update the capture-timing instrument. Reads the 7 PUBLIC
     * {@link ShipEngineControllerAPI} booleans the engine's own native player control sets each frame
     * (allowlist-clean: no {@code java.lang.reflect.*}, so the classloader-B blocklist that killed
     * the reflective command-ordinal capture does not apply — ⭐⭐). Bit order is the fixed semantic index
     * ({@code BIT_ACCELERATE}..{@code BIT_STRAFE_RIGHT}); the server maps each bit to the matching
     * engine command. The controller state is set during the engine's own advance, so reading it here
     * lags native control by ≤1 frame — invisible for HELD movement. Fail-soft; keeps the prior mask on any hiccup.
     */
    private void captureOwnCommands(ShipAPI own, float amount) {
        if (INTENT_SOURCE != null) {
            return; // Synthetic seat: the recording owns facing/heldControls (capture counters stay 0)
        }
        if (own == null) {
            return;
        }
        // Transit input gate (a): the source hull is a
        // wire-slaved observer during TRANSIT, so the movement mask and the system hold-stretch
        // state go neutral instead of sampling the parked hull (idempotent every frame; the
        // stretch/edge baselines re-arm naturally when the gate stops firing).
        if (xfer.ownTransit()) {
            heldControls = 0;
            sysHoldStretchSec = 0f;
            sysBitPrevRaw = false;
            // The parked hull's systems are wire-driven through the window —
            // re-baseline the press relays so a state the WIRE wrote never reads as a local press.
            systemPressHold.forget();
            defensePressHold.forget();
            return;
        }
        // Kit half: while ownKilled is latched the kit has no body its seat is flying —
        // ownShip is the hulk, a dive's or a retreat's removed body, a dead-adopted wreck, the body a
        // killOwn that threw left alive, or (voluntary dismount) the vacated server-driven module —
        // so its levels are no input (a vent held
        // at death rode up through the dead window and pressed VENT on the refielded hull). Held bits
        // go neutral = the pull-back bit ONLY (a fresh hull's construction default; 0 would toggle
        // it), the press relays re-baseline; facing / group / mask / tube / target KEEP their last
        // live sample (a neutral autofire mask would falsely release a server-side gate). Frames
        // keep flowing. The server's refield gate (PilotApplyState.refieldGateHolds) is the floor;
        // this closes the later-echo races it cannot see. NOT covered: a mount
        // BEFORE the adopt has a live server seat on the module, where 0x1000 is applied as input.
        if (ownKilled) {
            heldControls = 1 << BIT_PULL_BACK_FIGHTERS;
            sysHoldStretchSec = 0f;
            sysBitPrevRaw = false;
            systemPressHold.forget();
            defensePressHold.forget();
            return;
        }
        // Capture the own ship's REAL A/D-driven heading so the server pins its copy
        // to THIS (not the cursor) and thrust integrates in the frame the human actually flew. Read every
        // frame alongside the movement bitmask so the net thread always ships the latest heading up.
        try {
            float f = own.getFacing();
            if (Float.isFinite(f)) {
                ownFacingDeg = f;
            }
        } catch (Throwable ignore) {
            // facing read is presentation-side input; keep the prior heading on any hiccup
        }
        // Sample the engine's OWN selected-group ordinal + per-group
        // autofire toggle state each frame, exactly like the movement booleans below — engine
        // decisions mirrored UP, never raw keys. Fail-soft: keep the prior values on any hiccup.
        try {
            List<WeaponGroupAPI> groups = own.getWeaponGroupsCopy();
            if (groups != null) {
                // indexOf < 0 IS an engine state, not a hiccup — the engine's Ship nulls its
                // selection on any out-of-bounds SELECT_GROUP (the player picked an empty group),
                // and that deselect is what releases the engine's playerShip autofire override.
                // The old `if (sel >= 0)` guard silently dropped it, pinning the server hull's
                // selection forever (seat-1 autofire dead). Exceptions still keep
                // the prior value via the enclosing catch.
                int sel = groups.indexOf(own.getSelectedGroupAPI());
                liveSelectedGroup = sel >= 0
                        ? sel
                        : cc.starsector.server.runtime.PilotIntentFrame.SELECTED_GROUP_DESELECTED;
                int afMask = 0;
                for (int g = 0; g < groups.size(); g++) {
                    WeaponGroupAPI wg = groups.get(g);
                    if (wg != null && wg.isAutofiring()) {
                        afMask |= (1 << g);
                    }
                }
                liveAutofireMask = afMask;
                // PINT v6: sample the selected ALTERNATING group's active-tube
                // pointer — engine STATE exactly like the two reads above. getType() is gated
                // BEFORE getActiveWeapon() (lazy-init side effect on LINKED groups); a
                // non-ALTERNATING or absent selection writes ABSENT rather than keeping the
                // prior ordinal, so a stale tube never outlives the selection that produced it.
                // Pre-written ABSENT (unlike the keep-prior fields above — deliberate): the tube
                // apply is cross-gated on the selection sampled JUST above, so a mid-sample
                // hiccup must degrade to a server SKIP (ABSENT), never a prior-group ordinal
                // riding a fresh selection.
                liveActiveTubeOrdinal = PilotIntentFrame.TUBE_ORDINAL_ABSENT;
                int tube = PilotIntentFrame.TUBE_ORDINAL_ABSENT;
                if (sel >= 0 && sel < groups.size()) {
                    WeaponGroupAPI selGroup = groups.get(sel);
                    if (selGroup != null && selGroup.getType() == WeaponGroupType.ALTERNATING) {
                        List<WeaponAPI> tubes = selGroup.getWeaponsCopy();
                        int idx = tubes != null ? tubes.indexOf(selGroup.getActiveWeapon()) : -1;
                        if (idx >= 0 && idx <= 127) {
                            tube = idx; // wire validity -1..127; an unmappable read stays ABSENT
                        }
                    }
                }
                liveActiveTubeOrdinal = tube;
            }
        } catch (Throwable ignore) {
            // group/autofire read is presentation-side input; keep the prior values on any hiccup
        }
        // PINT v5: sample the engine's OWN ship-target designation each frame, exactly
        // like the group/autofire STATE reads above — the R-key pick the client engine already
        // accepted, never the raw key. Fail-soft: keep the prior designation on any hiccup.
        try {
            liveTargetWireId = wireIdForLocalShip(own.getShipTarget());
        } catch (Throwable ignore) {
            // target read is presentation-side input; keep the prior designation on any hiccup
        }
        try {
            ShipEngineControllerAPI ec = own.getEngineController();
            int mask = 0;
            if (ec != null) {
                if (ec.isAccelerating())          { mask |= (1 << BIT_ACCELERATE); }
                if (ec.isAcceleratingBackwards()) { mask |= (1 << BIT_ACCELERATE_BACKWARDS); }
                if (ec.isDecelerating())          { mask |= (1 << BIT_DECELERATE); }
                if (ec.isTurningLeft())           { mask |= (1 << BIT_TURN_LEFT); }
                if (ec.isTurningRight())          { mask |= (1 << BIT_TURN_RIGHT); }
                if (ec.isStrafingLeft())          { mask |= (1 << BIT_STRAFE_LEFT); }
                if (ec.isStrafingRight())         { mask |= (1 << BIT_STRAFE_RIGHT); }
            }
            // The SHARED bits-7/10/11 lockout-contamination mask guard
            // (the authoritative shield drop at 99.6% flux): a LOCAL-only
            // overload/vent forces the local system/shield/cloak off, and sampling those
            // forced-off states would carry the prediction artifact UP and strip the server's
            // REAL states. While the local tracker is locked but the AUTHORITATIVE own fluxFlags
            // are NOT, hold the three bits at their last pre-lockout values instead. Bit-8 vent
            // is exempt by design (the engine refuses vent while overloaded
            // — there is no lockout-forced vent state to mis-sample). Auth-locked frames sample
            // live as always (a REAL overload drops the bits — the server's own engine forces the
            // same states off). Gated cc.predict.ownFlux=apply; auth flags never seen ⇒ inert.
            boolean lockLocal = false;
            try {
                FluxTrackerAPI lockFt = own.getFluxTracker();
                lockLocal = lockFt != null && (lockFt.isOverloaded() || lockFt.isVenting());
            } catch (Throwable ignore) {
                // tracker read is presentation-side input; guard stays inert on any hiccup
            }
            boolean lockHold = OWN_FLUX_APPLY && lockLocal && lastOwnAuthFluxFlags >= 0
                    && (lastOwnAuthFluxFlags & (FLUX_FLAG_OVERLOADED | FLUX_FLAG_VENTING)) == 0;
            // Carry the ship-system WINDOW state UP
            // (bit 7). The own ship is native-flown, so reading getSystem().isOn() (= IN|ACTIVE exactly)
            // captures the engine's OWN activation decision, exactly as the 7
            // booleans above capture its movement decision — no raw-key double-interpretation. The rising
            // edge still lands at chargeup (IN) start ≈ the press (the fix stands); a later fix moved
            // the FALLING edge from OUT-exit (isActive = IN|ACTIVE|OUT) to the CANCEL instant
            // (ACTIVE→OUT drops isOn immediately), so a vanilla double-tap toggle cancel reaches the
            // server ≈RTT later instead of after the full chargedown (~2×out overhang). The server
            // mirrors both directions via the engine's faithful ShipAPI.useSystem(): rising →
            // activation press; low-while-server-isOn → ONE cancel press per mismatched tick.
            try {
                ShipSystemAPI sys = own.getSystem();
                // A zero-dwell primary (the four WEAPON-type flare launchers) walks
                // IDLE→COOLDOWN inside one advance, so isOn() never reads true and the press never
                // rode up (every owner flare volley was a client-only picture).
                // AcceptedPressHold synthesizes ~100ms of dwell per engine-ACCEPTED local press;
                // dwell systems return false there and keep the pure level mirror. Eager `|`
                // (not `||`): the detector must sample EVERY frame to baseline the state.
                int relaysBefore = systemPressHold.presses();
                boolean sysOn = sys != null && sys.isOn();
                boolean relayOn = systemPressHold.sample(sys);
                if (systemPressHold.presses() != relaysBefore && relaysBefore < 40) {
                    PilotWatchLog.info("predict sys-press relay #" + systemPressHold.presses()
                            + ": zero-dwell " + sys.getId() + " accepted locally -> bit-7 dwell"
                            + " (tick~" + lastSnapshotSimTick + ")");
                }
                // The lockout guard latches the LEVEL mirror only: latching the synthesized dwell
                // would pin bit 7 high through a whole local-only overload, and the server's
                // canBeActivated() ignores cooldown — it would re-fire at every cooldown expiry.
                if (!lockLocal) {
                    heldPreLockSystemOn = sysOn;
                }
                boolean rawOn = (lockHold ? heldPreLockSystemOn : sysOn) | relayOn;
                // Arm the NON-toggle hold-stretch at each rising edge
                // (see SYS_EDGE_MIN_HOLD_SECONDS for the full WHY). The spec read is fail-soft in
                // its own try: a hiccup arms nothing and the frame degrades to the pre-(98) pulse.
                if (rawOn && !sysBitPrevRaw) {
                    float armed = 0f;
                    try {
                        ShipSystemSpecAPI spec = sys != null ? sys.getSpecAPI() : null;
                        if (spec != null && !spec.isToggle()) {
                            armed = SYS_EDGE_MIN_HOLD_SECONDS;
                        }
                    } catch (Throwable ignore) {
                        // spec read is presentation-side input; no stretch on any hiccup
                    }
                    sysHoldStretchSec = armed;
                }
                sysBitPrevRaw = rawOn;
                boolean stretchOn = !rawOn && sysHoldStretchSec > 0f;
                if (sysHoldStretchSec > 0f) {
                    sysHoldStretchSec -= amount; // dt-accumulated, fps-robust (frame-counting is not)
                }
                if (rawOn || stretchOn) {
                    mask |= (1 << BIT_USE_SYSTEM);
                    capSystemFrames++;
                    if (stretchOn) {
                        capSystemStretchFrames++;
                    }
                }
            } catch (Throwable ignore) {
                // system read is presentation-side input; keep the movement mask on any hiccup
            }
            // Carry the flux-VENT intent UP (bit 8). isVenting() is the engine's
            // OWN accepted vent decision (a V press the engine refused — overloaded — never sets it), read
            // exactly like the bit-7 isOn() above. The server vents its authoritative ship on the
            // rising edge via the engine's own giveCommand(VENT_FLUX), so server flux drops at the real vent
            // rate instead of the passive rate seen diverging in a watched run.
            // NOTE: glide-gate-EXEMPT — a window-length state, not thrust
            // (VelocityGlideLaw.VENT_BIT); wire semantics here are unchanged.
            try {
                FluxTrackerAPI flux = own.getFluxTracker();
                if (flux != null && flux.isVenting()) {
                    mask |= (1 << BIT_VENT_FLUX);
                    capVentFrames++;
                }
            } catch (Throwable ignore) {
                // vent read is presentation-side input; keep the mask built so far on any hiccup
            }
            // PINT v5: carry the own-ship RETREAT state UP (bit 9) — isRetreating() is
            // the engine's OWN accepted retreat, read exactly like the bit-7/bit-8 states above
            // (see BIT_RETREATING for the vanilla gate/clear semantics the server mirrors).
            try {
                if (own.isRetreating()) {
                    mask |= (1 << BIT_RETREATING);
                    capRetreatFrames++;
                }
            } catch (Throwable ignore) {
                // retreat read is presentation-side input; keep the mask built so far on any hiccup
            }
            // Carry the own-ship SHIELD state UP (bit 10) — getShield().isOn() is the
            // engine's OWN accepted shield state, read exactly like the bit-7/8/9 states above
            // (see BIT_SHIELD_ON for the hold-replay/phase-guard semantics the server mirrors).
            try {
                ShieldAPI sh = own.getShield();
                boolean shieldOn = sh != null && sh.isOn();
                if (!lockLocal) {
                    heldPreLockShieldOn = shieldOn;
                }
                if (lockHold ? heldPreLockShieldOn : shieldOn) {
                    mask |= (1 << BIT_SHIELD_ON);
                    capShieldFrames++;
                }
            } catch (Throwable ignore) {
                // shield read is presentation-side input; keep the mask built so far on any hiccup
            }
            // Carry the own-ship PHASE-CLOAK state UP (bit 11) — getPhaseCloak().isOn()
            // is the engine's OWN accepted cloak state (IN|ACTIVE exactly, flips at toggle-acceptance; NOT
            // isPhased — it lags isOn <=0.25s through OUT), read exactly like the bit-7/8/9/10 states above
            // (see BIT_PHASE_CLOAK for the mismatch-edge semantics the server mirrors).
            try {
                ShipSystemAPI cloak = own.getPhaseCloak();
                // A zero-duration WEAPON defense in the cloak slot has no
                // isOn() dwell (measured bit11Frames=0 under spam) — AcceptedPressHold synthesizes
                // ~100ms of dwell per engine-ACCEPTED local press so the existing bit carries it.
                // Real phase cloaks return false there and keep the pure level mirror. Eager `|`
                // (not `||`): the detector must sample EVERY frame to baseline ammo/state.
                boolean cloakOn = cloak != null && cloak.isOn();
                boolean cloakRelay = defensePressHold.sample(cloak);
                if (!lockLocal) {
                    heldPreLockCloakOn = cloakOn; // LEVEL only — never latch the synthesized dwell
                }
                if ((lockHold ? heldPreLockCloakOn : cloakOn) | cloakRelay) {
                    mask |= (1 << BIT_PHASE_CLOAK);
                    capCloakFrames++;
                }
            } catch (Throwable ignore) {
                // cloak read is presentation-side input; keep the mask built so far on any hiccup
            }
            // Carry the own-ship carrier FIGHTER MODE up (bit 12) — isPullBackFighters()
            // is the engine's OWN accepted Z-key level (a pure per-command flip;
            // every hull constructs at true = regroup), read exactly like the bit-10/11 states above; the
            // server converges by the mismatch-edge law (applyPullBackBit: one toggle per mismatched tick).
            // Level mirror on every hull: non-carriers agree at the shared engine default and issue nothing.
            try {
                if (own.isPullBackFighters()) {
                    mask |= (1 << BIT_PULL_BACK_FIGHTERS);
                }
            } catch (Throwable ignore) {
                // fighter-mode read is presentation-side input; keep the mask built so far on any hiccup
            }
            // Guard bookkeeping: count held frames; capped one-line visibility.
            if (lockHold) {
                ownLockoutMaskHolds++;
                if (ownLockoutMaskHoldLogs < 20) {
                    ownLockoutMaskHoldLogs++;
                    PilotWatchLog.info("predict own-flux lockout-mask HOLD #" + ownLockoutMaskHolds
                            + ": local locked, auth unlocked -> bits 7/10/11 held at pre-lockout"
                            + " (sys=" + heldPreLockSystemOn + " shield=" + heldPreLockShieldOn
                            + " cloak=" + heldPreLockCloakOn + ")");
                }
            }
            heldControls = mask;
            capObservedFrames++;
            if (mask != 0) {
                capMovementFrames++;
            }
        } catch (Throwable t) {
            // capture is presentation-side input; never break the watch — keep the prior mask
        }
    }

    /**
     * PINT v5: map a locally-resolved {@link ShipAPI} target to its WIRE id for
     * {@link #liveTargetWireId}. Own ship ⇒ the session's wire id; a remote replica ⇒ its
     * {@link #remoteEntities} key via a linear scan (1v1 scale: NO parallel reverse
     * map); {@code null} or unmappable (a local-only body: hulk, unbound replica, pre-bind frame)
     * ⇒ {@code TARGET_NONE} — a sampled no-target, never ABSENT (the producer DID sample).
     * Package-visible: {@link DamageWitness} maps the shot-line
     * {@code tgt=} (the local autofire pick at fire time) through the same idiom.
     */
    long wireIdForLocalShip(ShipAPI target) {
        if (target == null) {
            return cc.starsector.server.runtime.PilotIntentFrame.TARGET_NONE;
        }
        if (target == ownShip) {
            PredictSession s = session;
            if (s != null) {
                return s.shipId();
            }
            return cc.starsector.server.runtime.PilotIntentFrame.TARGET_NONE;
        }
        for (java.util.Map.Entry<Long, RemoteEntity> entry : remoteEntities.entrySet()) {
            RemoteEntity re = entry.getValue();
            if (re != null && re.ship == target) {
                return entry.getKey();
            }
        }
        return cc.starsector.server.runtime.PilotIntentFrame.TARGET_NONE;
    }

    /** Emit the final capture-counter summary (called at teardown) so the WATCH log always carries it. */
    private void logCaptureTimingSummary(String where) {
        PilotWatchLog.info("predict cmd-capture summary [" + where + "]: observedFrames=" + capObservedFrames
                + " movementFrames=" + capMovementFrames + " systemFrames=" + capSystemFrames
                + " systemStretchFrames=" + capSystemStretchFrames
                + " systemPressRelays=" + systemPressHold.presses()
                + " ventFrames=" + capVentFrames + " retreatFrames=" + capRetreatFrames
                + " shieldFrames=" + capShieldFrames
                + " cloakFrames=" + capCloakFrames
                + " (engine-controller booleans + getSystem().isOn() + getFluxTracker().isVenting(),"
                + " no reflection)");
    }

    /** Bearing (degrees, [0,360)) from {@code (fromX,fromY)} to {@code (toX,toY)}. */
    private static float bearingDeg(float fromX, float fromY, float toX, float toY) {
        return (float) ((Math.toDegrees(Math.atan2(toY - fromY, toX - fromX)) + 360.0d) % 360.0d);
    }

    /** Smooth from {@code current} toward {@code target} (both degrees) by {@code alpha} along the shortest arc. */
    static float smoothAngleDeg(float current, float target, float alpha) {
        float delta = ((target - current + 540f) % 360f) - 180f; // shortest signed arc in (-180,180]
        return current + alpha * delta;
    }

    /**
     * Frame-rate-independent reconcile factor (the cadence fix): the fraction of the residual to
     * close THIS frame so the gap decays at a fixed time-constant {@code rate} per second regardless of
     * render fps. {@code alpha = 1 - exp(-rate*dt)} — saturates toward 1 for a large {@code dt} (a lag spike
     * snaps harder, which is correct) and is 0 for a non-positive frame (paused / zero dt).
     */
    private static float frameAlpha(float rate, float dt) {
        if (!(dt > 0f)) {
            return 0f;
        }
        float a = 1f - (float) Math.exp(-rate * dt);
        if (a < 0f) return 0f;
        if (a > 1f) return 1f;
        return a;
    }

    /**
     * Bind the human's own ship (the flagship) and one foe ship. The own ship is left to the engine's
     * native player control (real collision, real native firing) — NOT puppeted, NOT server-owned. The
     * foe is made dead-reckon-friendly: an inert AI (its motion comes from dead-reckon + reconcile, not a
     * local combat AI) but a real {@link CollisionClass#SHIP} so the own Hellbore COLLIDES + despawns for
     * real (GAP 1). A {@link FoeDamageCap} listener lets a non-lethal local hit apply in
     * FULL (real FX + number + bar drop, axis A) but CAPS a would-be-lethal hit to the hull floor so the
     * foe never dies client-side (axis B); the authoritative death runs only via {@link OwnSeatLifecycleLane#killFoe} (axis C).
     */
    /**
     * Install {@link OwnDamageCap} on the own ship exactly once (both bind paths
     * call this; idempotent). Fail-soft: an install failure is logged once and NOT retried — the cap
     * is a guard rail, and a retry loop on a broken listener API would spam every frame.
     */
    void installOwnCap() { // package: OwnMountLifecycleLane adopt re-installs the cap
        if (ownCapInstalled || ownShip == null) {
            return;
        }
        ownCapInstalled = true;
        try {
            ownShip.addListener(hullCap.new OwnDamageCap());
        } catch (Throwable t) {
            PilotWatchLog.error("predict own-cap install FAILED (own ship locally unkillable-guard absent)", t);
        }
    }

    /** Mint a round-local STATION MODULE registry row for
     *  {@link StationAdoptBindLane} (the lane binds {@code ship} itself at the adopt site; a module
     *  row is never spawned, so it carries no RAPP factory payload). */
    RemoteEntity newStationModuleEntity(long wireId, int side) {
        return new RemoteEntity(wireId, side, false, null, 0, true, 0L, 0xFF, null, false);
    }

    /** The registry entity id a replica body carries (the witness rows'
     *  {@code victim=remote:<id>} key), {@code -1} when the body is not a registry replica (the 1v1
     *  foe, the own ship, a corpse). Linear over the registry: a per-tick presenter row's lookup,
     *  never a per-frame one. */
    long remoteEntityIdOf(ShipAPI ship) {
        if (ship == null) {
            return -1L;
        }
        for (RemoteEntity re : remoteEntities.values()) {
            if (re.ship == ship) {
                return re.entityId;
            }
        }
        return -1L;
    }

    /** The replica-body carve's package
     *  delegates: pure forwards to {@link ReplicaBodyLane} (no guard, no reorder), so every outside
     *  caller keeps its plugin name. {@link ReplicaBodyLane#neutralizeRemoteBody}. */
    boolean neutralizeRemoteBody(ShipAPI ship, RemoteEntity capEntity) {
        return replicaBody.neutralizeRemoteBody(ship, capEntity);
    }

    /** {@link ReplicaBodyLane#remoteBodyFullyTreated}. */
    boolean remoteBodyFullyTreated(ShipAPI ship) {
        return replicaBody.remoteBodyFullyTreated(ship);
    }

    /** {@link ReplicaBodyLane#applyWireAckedHit} (the 7-arg form; the 9-arg one has no outside caller). */
    float applyWireAckedHit(ShipAPI victim, Vector2f at, float damage, DamageType type,
            float emp, Object source, long hitTick) {
        return replicaBody.applyWireAckedHit(victim, at, damage, type, emp, source, hitTick);
    }

    /** {@link ReplicaBodyLane#forgetRemoteBodyTreatment}. */
    void forgetRemoteBodyTreatment(ShipAPI ship) {
        replicaBody.forgetRemoteBodyTreatment(ship);
    }

    /** {@link ReplicaBodyLane#unregisterReplayDriven}. */
    void unregisterReplayDriven(ShipAPI ship) {
        replicaBody.unregisterReplayDriven(ship);
    }

    /** {@link ReplicaBodyLane#isReplayDriven}. */
    boolean isReplayDriven(ShipAPI ship) {
        return replicaBody.isReplayDriven(ship);
    }

    /** {@link ReplicaBodyLane#replayDrivenSize}. */
    int replayDrivenSize() {
        return replicaBody.replayDrivenSize();
    }

    /** Census rider: the servo's window fps {@code mean/min}, drained per census line —
     *  RENDER THREAD ONLY (the post-feed emit); off-thread census lines read {@link #fpsWindowPeek}
     *  (the drain is a non-atomic read+reset). */
    String fpsWindowTake() {
        return clockServo.fpsWindowTake();
    }

    /** The same window without the reset — for the stream-end / teardown backstops, which may
     *  run on the net thread. */
    String fpsWindowPeek() {
        return clockServo.fpsWindowPeek();
    }

    /**
     * The between-rounds rebuild the MRST boundary commands.
     * The server reset its engine and re-deployed FRESH full-hull ships (wire entity ids re-interned
     * from 1), so the round-1 bodies here — hulks and the damaged winner alike — mirror nothing
     * anymore: remove them, spawn clean replacements at the new round's authoritative poses, hand the
     * player the fresh own ship, and restart every per-round BASELINE (tick clocks, bind/kill guards,
     * queues, identity sets, ship-keyed maps) so round 2 reads like a fresh combat on the SAME
     * socket/mission — the session, net thread, SNMS table and clock-servo law survive by design.
     * Run-level tallies and max meters (the eventReplay, discharge and capture counters) stay
     * cumulative: the debrief reads them as per-run bars. The proven bind treatments (own bind-once + OwnDamageCap;
     * foe InertShipAI + SHIP collision + FoeDamageCap + replay-driven hold-fire + seat name) are NOT
     * duplicated here — nulling the refs makes the existing bindShips() re-run them this same frame.
     * 1v1 shape only (multi-round is the two-human duel lane); a bots-shape MRST is counted ignored.
     * Render-thread only. Fail-soft: a spawn failure logs loud and leaves the refs for bindShips to
     * retry against whatever exists — never a render-thread throw.
     */
    private void applyMatchReset(ServerSnapshotFrame snapshot) {
        cc.starsector.client.net.ClientMatchReset reset = pendingReset;
        if (reset == null && !pendingLifeReset) {
            return;
        }
        if (botsShape) {
            // Bots stays the single-round bench lane — no serve loop emits MRST at it. seats:/
            // gauntlet: no longer ignores here: the dual-window descriptor watches ARE
            // multi-round, and a run proved both windows RECEIVED the MRST and refused it at
            // this gate (the old "multi-round is the 1v1 lane" assumption), so a dead window
            // stayed bricked through round 1.
            matchResetsIgnored++;
            retirePendingReset(reset);
            pendingLifeReset = false; // The bench lane has no rebirth class — counted ignore
            PilotWatchLog.info("predict match-reset: match_reset_unsupported_shape (bots) "
                    + resetTag(reset) + " -> ignored (the bots bench is single-round)");
            return;
        }
        if (!registryShape && ownShip == null && foeShip == null) {
            matchResetsIgnored++;
            retirePendingReset(reset);
            pendingLifeReset = false;
            PilotWatchLog.error("predict match-reset: NO round-1 ships ever bound -> rebuild skipped"
                    + " (" + resetTag(reset) + "); bindShips keeps retrying", null);
            return;
        }
        // The wire-true hold gate (see mrstHoldStartMillis): rebuild only once a
        // post-reset frame carries the own id ALIVE, or the starvation bound expired. Both branches
        // below then consume ownPoseHold, so a fresh rebuild can never spawn off a stale/absent row.
        ServerEntityPose ownPoseHold = snapshot != null ? snapshot.entityById(session.shipId()) : null;
        boolean ownPoseFresh = ownPoseHold != null && ownPoseHold.hitpoints() > 0f;
        if (!ownPoseFresh) {
            long now = System.currentTimeMillis();
            if (mrstHoldStartMillis == 0L) {
                mrstHoldStartMillis = now;
                PilotWatchLog.info("predict match-reset: HOLD armed " + resetTag(reset)
                        + " (post-reset frame carries no live own id; rebuild deferred to the first"
                        + " own-id frame, engine-edge fallback at " + MRST_OWN_POSE_WAIT_MS
                        + " ms)");
            }
            if (now - mrstHoldStartMillis < MRST_OWN_POSE_WAIT_MS) {
                mrstHoldFrames++;
                return; // pendingReset stays set: advance() coasts, the writer stays quiesced
            }
            PilotWatchLog.error("predict match-reset: HOLD EXPIRED after " + mrstHoldFrames
                    + " frames / " + (now - mrstHoldStartMillis) + " ms with no live own-id frame"
                    + " (server never deployed the seat?) -> engine-edge fallback spawn"
                    + " (loc=null; never (0,-2000))", null);
        }
        int sweptProj = 0;
        int sweptBodies = 0;
        if (registryShape) {
            // The seats:/gauntlet: registry rebuild —
            // the same law as the 1v1 branch below (sweep the ending round's leftovers, spawn the
            // fresh own ship at the wire pose, restart the per-round baselines), except remotes are
            // NOT spawned here: every registry entry resets to UNBOUND and the proven first-sight
            // adopt path (spawnRemoteEntities — the SAME mechanism that fields a late join / SEAT
            // fly-in) rebuilds each one from the fresh round's wire.
            // r1 wrap re-announce race — a seat re-announce still
            // queued here would be dropped by the cut below AFTER this own read fielded the stale
            // variant. Fold the queued SEAT rows into the registry first (registry-only, none of the
            // drain's arms). The server now fans the wrap re-point AFTER
            // the MRST (stamped N = the reset's own epoch), so the fold takes epoch <= resetEpoch.
            // Fold (i) — the guard against the own-variant
            // bail below (pinned by RosterAppendResetSmoke L9e).
            Long ownKeyR = Long.valueOf(session.shipId());
            // A null own row with no bound own ship ⇒ the bail below skips the sweep/unbind this
            // registry-only fold relies on; leave the frames to the post-retire drain's arms.
            if (variantById.get(ownKeyR) != null || ownShip != null) {
                // MMNT≺RAPP (mmnt.drain runs AFTER this) — a seat with
                // an un-taken MMNT keeps its row queued for the drain's mount guards
                // (RosterAppendResetSmoke L9j pins it). A null peek = a proxy stub.
                Set<Long> mountQueued = java.util.Objects.requireNonNullElse(session.peekQueuedModuleMountSeats(), Set.of());
                // Lens fold: the meter + the row count the seats the predicate
                // actually REJECTED — a peeked seat with no queued row is not a skip. The set fills under the RAL
                // queue monitor (a local; no monitor nests), the row is written after it is released.
                // RosterAppendResetSmoke L9j-peek-idle pins it.
                Set<Long> skipped = mountQueued.isEmpty() ? null : new java.util.TreeSet<Long>();
                rapp.foldSeatRowsBeforeReset(reset != null && reset.roundEpoch() > 0 ? reset.roundEpoch()
                        : Integer.MAX_VALUE, id -> {
                            if (!MatchMissionBuilder.isSeatShipId(id)) {
                                return false;
                            }
                            if (skipped != null && mountQueued.contains(Long.valueOf(id))) {
                                skipped.add(Long.valueOf(id));
                                return false;
                            }
                            return true;
                        });
                if (skipped != null && !skipped.isEmpty()) {
                    matchResetFoldSkippedMountSeats += skipped.size();
                    PilotWatchLog.info("predict match-reset: fold SKIPS seats with a queued MMNT ids=" + skipped);
                }
            }
            String ownVariantR = variantById.get(ownKeyR);
            if (ownVariantR == null && ownShip != null) {
                ownVariantR = ownShip.getVariant().getHullVariantId();
            }
            if (ownVariantR == null) {
                matchResetsIgnored++;
                retirePendingReset(reset);
                pendingLifeReset = false;
                mrstHoldStartMillis = 0L; // a hold may have armed before this bail
                mrstHoldFrames = 0;
                PilotWatchLog.error("predict match-reset: registry rebuild has NO own variant"
                        + " (no descriptor row for ownId=" + session.shipId()
                        + " and no bound own ship) -> reset ignored", null);
                return;
            }
            int ownerSideR = sideAuthority();
            // The hold gate above adjudicated the pose — fresh wire row, or null on
            // the expired hold (⇒ the engine-edge spawn below; never a hardcoded loc).
            ServerEntityPose ownPoseR = ownPoseFresh ? ownPoseHold : null;
            godMirror.releaseAll(); // Every carrier, BEFORE the sweep + the unbind loop
            try {
                for (DamagingProjectileAPI proj : new ArrayList<DamagingProjectileAPI>(engine.getProjectiles())) {
                    engine.removeEntity(proj);
                    sweptProj++;
                }
                // Spawn the fresh own ship BEFORE the sweep so the engine is never player-shipless
                // (the 1v1 branch's ordering); setShipAI(null) = native player control.
                float ownFacingR = ownPoseR != null && Float.isFinite(ownPoseR.facing()) ? ownPoseR.facing()
                        : ownerSideR == 0 ? 90f : 270f; // side-keyed, never a side-blind constant (F2)
                ShipAPI newOwn = ownSeat.spawnOwnSeat(engine.getFleetManager(ownerSideR), ownerSideR,
                        ownVariantR, ownPoseR != null ? new Vector2f(ownPoseR.locX(), ownPoseR.locY()) : null,
                        ownFacingR, OWN_SPAWN_FLYIN_BURN_SEC, "match-reset-registry"); // shared mint
                if (newOwn == null) {
                    PilotWatchLog.error("predict match-reset: registry rebuild own spawn returned null"
                            + " (variant=" + FallbackAnnounce.sanitize(ownVariantR, 64)
                            + ") -> bindShips binds what exists", null);
                } else {
                    // The fly-in marker is THIS site's alone —
                    // the registry reset's server twin flies the seat in (midDeploySeat,
                    // FLY_IN_BURN_DUR); the 1v1 reset twin is a deployAll context burn and a
                    // respawn / re-field mint is never followed by an adopt.
                    rejoin.noteOwnFlyinMint(newOwn, ownFacingR, OWN_SPAWN_FLYIN_BURN_SEC);
                }
                // Every body but the fresh own goes: bound remotes, the old own (corpse or alive),
                // round hulks/pieces. Spawn-on-sight re-fields the remotes from the new round's wire.
                // evict-then-remove (the engine's own
                // death-listener verb) + the out-of-play backstop — the ghost-arrow fix.
                // The fresh own ship's child
                // modules are Ships in getShips() too — they stay with their parent.
                for (ShipAPI stale : new ArrayList<ShipAPI>(engine.getShips())) {
                    if (stale != newOwn && !isModuleOf(stale, newOwn)) {
                        foeState.deployedEvict.evictForReset(engine, stale);
                        engine.removeEntity(stale);
                        sweptBodies++;
                    }
                }
                foeState.deployedEvict.resetBackstop(engine, newOwn);
            } catch (Throwable t) {
                PilotWatchLog.error("predict match-reset: registry rebuild spawn/sweep FAILED ->"
                        + " spawn-on-sight retries against the surviving world", t);
            }
            // Waves are round-local — the rebuild REMOVES appended entries
            // (+ their variant rows) instead of resetting them unbound; the next round's waves
            // re-append via fresh RAPP frames. Queued frames older than the consumed reset's epoch
            // are the OLD round's and drop with them (prevents forever-unbound census noise); the
            // NEW round's (stamped at intake, deferred by the lane) stay queued and fold after the
            // rebuild. A bare life reset (reset == null) drops everything.
            // Cut by the consumed reset's epoch.
            // An UNSTAMPED reset (epoch 0: the TCP twin / a 3-arg-constructed one) keeps the old
            // law — drop everything, like a bare life reset.
            rapp.clearQueue(reset != null && reset.roundEpoch() > 0 ? reset.roundEpoch() : Integer.MAX_VALUE);
            for (java.util.Iterator<java.util.Map.Entry<Long, RemoteEntity>> appendedIt =
                    remoteEntities.entrySet().iterator(); appendedIt.hasNext();) {
                java.util.Map.Entry<Long, RemoteEntity> appendedEntry = appendedIt.next();
                if (appendedEntry.getValue().appended) {
                    variantById.remove(appendedEntry.getKey());
                    appendedIt.remove();
                }
            }
            rapp.onRoundReset(); // re-arm the per-round roster bounds (refusal counts, spawn budgets)
            // The station band + its module rows are ROUND-LOCAL (the server
            // re-interns wire ids from 1) — drop them BEFORE the recount below, or the fresh
            // round's remoteUnbound would carry the dead round's module rows.
            try {
                merlonCensus.resetRound(); // Summarize + re-census (fail-silent inside)
                matchOverLane.resetRound(); // never-re-field defect assert
                stations.resetRound();
                mmnt.resetRound(); // Mounted state is round-local too
            } catch (Throwable t) {
                // This method must never throw on the render thread (the sweepInert/maintain
                // guard shape): a station-reset throw would abort the recount below and leave the
                // fresh round counting the dead round's rows.
                PilotWatchLog.error("predict match-reset: station band reset FAILED -> the fresh"
                        + " round may carry stale module rows into its unbound census", t);
            }
            int resetSeatBurnsArmed = 0;
            for (RemoteEntity re : remoteEntities.values()) {
                re.ship = null;
                re.killed = false;
                re.deathTick = Long.MIN_VALUE; // r1 — round-local kill tick
                re.reapTick = Long.MIN_VALUE; // The reap queue is round-local (coordinator clears it)
                re.capActive = true;
                re.sysServerOpen = null;
                re.sysOpenTick = FoeSystemWindowLaw.NO_WINDOW;
                re.sysSeeded = false;
                re.jump.reset(); // Fences/windows are round-local
                re.ledger = null; // Ledgers are per-life — reseed from WIRE next round
                // The server flies every DEPLOYABLE seat back in through
                // midDeploySeat (FLY_IN_BURN_DUR; a not-yet-deployable seat flies in later through the
                // T1 lane, burn-armed too) — a SEAT row's re-bind arms the parity
                // burn like a remount (replicaFlyinBurn doc); a vacated seat has no alive row, nothing
                // binds. A NON-seat row is DISARMED (a bot armed by a pre-reset respawn that never
                // minted re-binds as a deployAll context-burn twin).
                // A bare LIFE reset (reset == null — a reborn
                // server re-admitting this seat) runs this branch too; its alive seats have SPENT
                // their burn and the server flies nobody back in, so arm on an MRST only — else a
                // re-admitted seat re-binds with a 4 s plume + latched auto-steer against a fighting twin.
                boolean seatRow = reset != null && MatchMissionBuilder.isSeatShipId(re.entityId);
                re.remounted = seatRow;
                if (seatRow) {
                    resetSeatBurnsArmed++;
                }
            }
            remoteUnbound = remoteEntities.size();
            remoteBindCompleteLogged = false;
            remoteNoAnchorLogged = false;
            PilotWatchLog.info("predict match-reset: seat re-binds armed the fly-in parity burn n="
                    + resetSeatBurnsArmed + "/" + remoteEntities.size() + " burn=" + OWN_SPAWN_FLYIN_BURN_SEC
                    + " (the reset's `bots bind ... burn=` rows are the signal)");
            finishMatchReset(reset, ownVariantR, "registry:" + remoteEntities.size(),
                    ownPoseR != null ? "wire-fresh" : "engine-edge",
                    ownPoseR != null ? ownPoseR.locX() + "," + ownPoseR.locY() : "engine-edge",
                    sweptProj, sweptBodies);
            return;
        }
        String ownVariant = ownShip != null ? ownShip.getVariant().getHullVariantId()
                : foeShip.getVariant().getHullVariantId();
        String foeVariant = foeShip != null ? foeShip.getVariant().getHullVariantId() : ownVariant;
        // NEVER ownShip.getOwner() here: after an own-death round the corpse reads owner 100 and
        // poisons BOTH spawn sides. Bind-time capture is the
        // side authority; it is set on any path that ever bound an own ship.
        int ownerSide = ownSideAtBind >= 0 ? ownSideAtBind : 0;
        // New round's authoritative poses (the server is the single source of geometry). The hold
        // gate above adjudicated the own row — null only on the expired hold, which
        // spawns at the engine edge below; adoptInitialWorld re-snaps either way.
        ServerEntityPose ownPose = ownPoseFresh ? ownPoseHold : null;
        ServerEntityPose foePose = snapshot != null ? firstOther(snapshot, ownPose) : null;
        godMirror.releaseAll(); // Every carrier, BEFORE the evict-then-remove below
        try {
            // The server's resetForRedeploy() serves the fresh round from a CLEAN world — mirror that
            // before spawning, or the ending round's leftovers keep dealing client-local damage the
            // server never sees: in-flight shells AND the death explosion (DamagingExplosion implements
            // DamagingProjectileAPI, so getProjectiles() reaches it) sit exactly where the killed ship
            // respawns. Live find: a solo respawn pre-check — fresh foe bound at hp=1000, then
            // battered within frames; hull is never wire-corrected, so it stuck all round.
            for (DamagingProjectileAPI proj : new ArrayList<DamagingProjectileAPI>(engine.getProjectiles())) {
                engine.removeEntity(proj);
                sweptProj++;
            }
            // Spawn the fresh own ship BEFORE removing the old one so the engine is never
            // player-shipless; setShipAI(null) = native player control (the mission-deploy shape).
            float ownFacing = ownPose != null && Float.isFinite(ownPose.facing()) ? ownPose.facing()
                    : ownerSide == 0 ? 90f : 270f; // side-keyed, never a side-blind constant (F2)
            ShipAPI newOwn = ownSeat.spawnOwnSeat(engine.getFleetManager(ownerSide), ownerSide, ownVariant,
                    ownPose != null ? new Vector2f(ownPose.locX(), ownPose.locY()) : null,
                    ownFacing, OWN_SPAWN_FLYIN_BURN_SEC, "match-reset-1v1"); // shared mint (null = no re-point; sweep below skips null)
            // evict-then-remove on all three removals + the backstop.
            if (ownShip != null) {
                foeState.deployedEvict.evictForReset(engine, ownShip);
                engine.removeEntity(ownShip);
            }
            if (foeShip != null) {
                foeState.deployedEvict.evictForReset(engine, foeShip);
                engine.removeEntity(foeShip);
            }
            // Round hulks/pieces the two refs above don't reach — clear them BEFORE the foe spawns
            // into the same spot (the 1v1 world holds exactly the two bodies being replaced).
            // The fresh own ship's modules stay.
            for (ShipAPI stale : new ArrayList<ShipAPI>(engine.getShips())) {
                if (stale != newOwn && stale != ownShip && stale != foeShip
                        && !isModuleOf(stale, newOwn)) {
                    foeState.deployedEvict.evictForReset(engine, stale);
                    engine.removeEntity(stale);
                    sweptBodies++;
                }
            }
            foeState.deployedEvict.resetBackstop(engine, newOwn);
            float foeFacing = foePose != null && Float.isFinite(foePose.facing()) ? foePose.facing()
                    : 1 - ownerSide == 0 ? 90f : 270f; // side-keyed like the own fallback (F2)
            // 3-arg -> 5-arg for the captain ONLY. Burn stays 0f — the 3-arg overload's own
            // body is `spawnShipOrWing(v, loc, facing, 0.0F)`, so the
            // "FOE replica spawns deliberately stay 3-arg" fly-in posture is preserved.
            // Stays 0f under flyinParity too — this is a ROUND-OPEN re-field (the server
            // side rides the deployAll CONTEXT burn, spent by reset-sight), not a remount; the
            // server's round-reset lane is UNVERIFIED, recorded open.
            CombatFleetManagerAPI foeMgr1v1 = engine.getFleetManager(1 - ownerSide);
            Boolean foeFeedTok = feedGate.mute(foeMgr1v1); //
            ShipAPI newFoe;
            try {
                newFoe = foeMgr1v1.spawnShipOrWing(foeVariant,
                        foePose != null ? new Vector2f(foePose.locX(), foePose.locY()) : null,
                        foeFacing, 0f,
                        // W4: legacy 1v1 foe — descriptor-less, curated lane both ends.
                        OfficerLoadout.captainOrNull(foeVariant, false));
            } finally {
                feedGate.unmute(foeMgr1v1, foeFeedTok);
            }
            if (newOwn == null || newFoe == null) {
                PilotWatchLog.error("predict match-reset: spawn returned null (own="
                        + (newOwn != null) + " foe=" + (newFoe != null) + " variant=" + ownVariant
                        + "/" + foeVariant + ") -> bindShips binds what exists", null);
            }
        } catch (Throwable t) {
            PilotWatchLog.error("predict match-reset: rebuild spawn/remove FAILED -> bindShips retries"
                    + " against the surviving world", t);
        }
        finishMatchReset(reset, ownVariant, foeVariant,
                ownPose != null ? "wire-fresh" : "engine-edge",
                ownPose != null ? ownPose.locX() + "," + ownPose.locY() : "engine-edge",
                sweptProj, sweptBodies);
    }

    /** The ONE site that consumes a pending MRST — hands
     *  the consumed reset to the roster lane, which retires its round epoch BY VALUE (max, never
     *  {@code ++}), on the ignore paths too (an ignored reset can never strand a stamped frame). A
     *  bare life reset ({@code consumed == null}) retires nothing: no MRST flipped the epoch.
     *  {@code consumed} = the reset applyMatchReset read at ENTRY (its local / finishMatchReset's
     *  parameter), never the volatile field re-read here: a net-thread overwrite inside one apply
     *  (the publish clobber) must surface as the lane's fail-open ERROR row, not be retired silently
     *  (pinned by RosterAppendResetSmoke L8c). */
    private void retirePendingReset(cc.starsector.client.net.ClientMatchReset consumed) {
        rapp.onResetConsumed(consumed);
        pendingReset = null;
    }

    /** The shared applyMatchReset tail (extracted, both shapes byte-for-byte the 1v1
     * tail): per-round baselines/guards/queues/sets/refs restart. Session-scoped state survives:
     * the socket/session, wireNameByShipId (re-marked dirty so the fresh ships re-apply it),
     * one-shot log latches, the servo law (resetClockMeter deliberately keeps it). */
    private void finishMatchReset(cc.starsector.client.net.ClientMatchReset reset,
            String ownVariant, String foeVariant, String poseSrc, String ownLoc,
            int sweptProj, int sweptBodies) {
        feedGate.resetRound(); // Every round has its own spawn burst — window re-arms
        frameLedger.resetRound(); // Flush the ended round's worst-frame rows + tag the rebuild frame
        ownShip = null;
        foeShip = null;
        claimPending = false; // W13 leg 2c: a match reset fields its own hulls — no claim in flight across it
        claimHold.release(null); // rider 6: the held body is swept with the round (no restore target)
        driveLock.release(); // No held state / previous-bit memory crosses a round
        CcClientModPlugin.noteClaimSettled();
        bindAttempts = 0;
        mappingLogged = false;
        fallbackRefusedLogged = false;
        ownPoseSeen = false;
        foePoseSeen = false; // Round N+1's early frames can be entity-less too
        foeKillPreMappingLogged = false;
        lastOwnPose = null;
        initialWorldAdopted = false;
        flagshipLeverApplied = false; // Fresh round hull re-runs the stats lever
        leverSettledRefireAtMillis = 0L; // The fresh round's own t=0 lever re-arms it
        ownCapInstalled = false;
        foeState.resetForMatchReset(); // K17: foe-family round-scoped latches/maps
        teleportLane.resetRound(); // Fences/windows/patience are round-local (K16)
        ownCrWireLast = Float.NaN; // the census wire riders are round-local
        ownDepWireLast = Float.NaN;
        foeInterp.resetRoundLocal(); // dead-round frames must never bracket a lerp
        foeStep.resetRoundLocal(); // No step across a dead round
        foeFacing.resetRoundLocal(); // A round boundary mints no facing step
        sameSideMountLastRel.clear(); // weapon-keyed last-rel dies with the round's bodies
        sameSideAim.resetRound(); // weapon-keyed aim records die with the round's bodies
        remoteAim.resetRound(); // AIMP aim rows + driven set die with the round's bodies
        pressShadow.resetRound(); // Diag: per-round sub-cap + pressed-ref clears (body in the probe)
        foeKilled = false;
        ownKilled = false;
        // r1 — the kill ticks + the door-det expectations are
        // round-local (a stale tick / ship object must never classify the fresh round's applies).
        foeDeathTick = Long.MIN_VALUE;
        ownDeathTick = Long.MIN_VALUE;
        doorDet.clear();
        CcServedBombMirror.sweep(); // served-bomb FX mirrors die with the round
        ownLife.resetRound(); // death-edge re-arm + the vacate hold dies with the round
        xfer.resetRound(); // Transit/staged/retired-id state is round-local
        ownCloakDisagreeStreak = 0; // A stale streak must not defeat the RTT patience gate
        // A round rebuild moots any in-flight re-admission refill hold — and the arm tick
        // may not compare against the fresh round's tick space, so never let it linger.
        reAdmitAbsenceHoldPending = false;
        reAdmitAbsenceHoldFromTick = -1L;
        reAdmitAbsenceHoldArmTick = Long.MIN_VALUE;
        reAdmitHoldExpiredThisFrame = false;
        capFoeDamage = true;
        capOwnDamage = true;
        // Hull ledgers are per-life — the totals line + reseed live in the lane
        // (extracted there; re-mints from WIRE next round, NEVER the reconciled scalar).
        hullLane.resetForMatchReset();
        weaponLists.reset();              // Ship identities re-mint
        missileLane.resetForMatchReset(); // Puppet twins/sightings are round-local
        afTargets.dropPicks();            // Wire picks + ordinal cache are round-local
        mtrack.resetForMatchReset();      // Close live tracks + re-arm pos budget
        demLane.resetForMatchReset();     // DEM drones/tracks are round-local
        ownDespawnReason = null;
        ownDespawnTick = -1L;
        synchronized (despawnReasonByEntity) {
            despawnReasonByEntity.clear(); // Remote reasons are round-local too
        }
        recallFx.resetRound(engine); // mid-animation bodies die with the round
        phaseDive.resetRound(engine); // mid-dive bodies die with the round
        constructionFx.resetRound(engine); // mid-reveal hulls + pending pairs die with the round
        swarmCloudProbe.resetRound(); // Probe windows die with the round
        reclamationFx.resetRound(); // mid-dissolve hulk families die with the round
        energyLashFx.resetRound(); // Phase glows die with the round
        moteAttractorFx.resetRound(engine); // mid-charge attractor presses die with the round
        ownDetLane.resetRound(); // Own det ledger + attach state are round-local
        arcHitLane.resetRound(); // The per-flavor arc-hit log budget re-arms per round
        engineRedisable.clear(); // The identity maps held every past own-hull object across lives/rounds
        weaponRedisable.clear();
        // The MRST rebuild fields fresh hulls at the SAME wire ids —
        // an own-hull epoch bump + lifecycle edge. The
        // npeer wrap RESTARTS the server tick space at 0 (a recorded flight logged `round=1 tick=0`) while
        // the legacy 1v1 path may not, so stamping the OLD round's last tick here held the settle
        // window open for the fresh round's first ~16k ticks (a tick behind the bump reads ACTIVE,
        // fail-closed). Both edges go UNARMED (MIN) here and settleRebasePending stamps them with
        // the first post-reset authoritative snapshot's tick — safe under both clock shapes.
        fireLanes.ownHullEpoch++;
        fireLanes.ownHullEpochBumpTick = Long.MIN_VALUE;
        fireLanes.lastLifecycleEdgeTick = Long.MIN_VALUE;
        settleRebasePending = true;
        lastSnapshotSimTick = Long.MIN_VALUE;
        MarkFrameRing.onRoundReset();     // The round tick space restarts — capture guard + ring re-arm
        ObserverFrameDump.onRoundReset();
        lastFreshSnapshotWallNanos = Long.MIN_VALUE;
        prevAuthVelX = Float.NaN;
        prevAuthVelY = Float.NaN;
        prevAuthVelSimTick = Long.MIN_VALUE;
        lastAuthStepPerTick = Float.NaN;
        glideWasWriting = false;
        escapePrevSimTick = Long.MIN_VALUE;
        aboveCapTicks = 0L;
        velRecon.reset(); // Engage episodes do not survive the round (meters stay cumulative, sibling shape)
        lastLocalSpeed = 0f;
        lastServerSpeed = 0f;
        speedDeflicker.reset();
        lastLocalFluxLevel = -1f;
        lastServerFluxLevel = -1f;
        lastLocalSysState = "n/a";
        lastFluxDeltaPinned = 0f;
        // Windows do not survive the round — close any open skew streak honestly (the
        // 212641 R2 kill-edge shape), then the derived server state restarts unknown until the
        // fresh round's first edge. Cumulative counters persist like the rest of this meter family.
        for (int k = 0; k < 2; k++) {
            if (mfStreakStartTick[k] != Long.MIN_VALUE && mfLastSampleTick != Long.MIN_VALUE) {
                edgeEvents.closeMidflightStreak(k, mfLastSampleTick, "match-reset");
            }
            mfStreakStartTick[k] = Long.MIN_VALUE;
            mfServerOpen[k] = null;
        }
        mfLastSampleTick = Long.MIN_VALUE;
        closeOwnEngineStreak(); // Windows do not survive the round either
        synchronized (edgeEventQueue) {
            edgeEventQueue.clear();
        }
        pendingAheadEvents.clear(); // B2(a): held ahead edges belong to the ended round
        fireLanes.pendingAuthDischarges.clear();
        fireLanes.pendingLocalDischarges.clear();
        seenOwnProj.clear();
        secondsSinceFireHeld = UnwiredOwnFireLaw.NEVER_HELD; // The held-lane recency belongs to the ended round
        seenReplayShipProj.clear();
        presentedFoeProj.clear();
        projSweptPresented = 0; // round-scoped, like the set it compensates
        localFire.resetRound();
        spawnGate.resetRound(); // per-round gate log dedup (the verdict cache is content truth)
        fuseMeter.reset(); // Dead round's shells get no outcome, not a fake burst
        dmgWitness.reset(); // Witness shells/stashes/aggregates die with the round too
        canisterProbe.reset(); // dead round's canisters are gone, not despawn-births
        // Discharge-reconcile state dies with the round —
        // sets/queues/ring/cache alongside the pairing deques above; cumulative counters persist.
        fireLanes.presentedOwnProj.clear();
        fireLanes.adoptTreatmentQueue.clear();
        fireLanes.retireTreatmentQueue.clear();
        fireLanes.retiredRecentRing.clear();
        fireLanes.recentPairedLocalRing.clear();
        fireLanes.dischargeMountAuthCache.clear();
        fireLanes.mountRearmLatch.clear(); // Latch ticks belong to the ended round's clock
        fireLanes.mountLastDrainedShotTick.clear(); // Drained shot ticks, same clock
        replayDrivenShips.clear();
        remoteBodyTreated.clear(); // The treatment ledger dies with its bodies
        HullCapBound.resetReplicaFallback(); // The replica fallback slot dies with them too
        seatHud.resetForMatchReset(); // per-round tag re-arm + wire-name dirty re-mark (lane-side)
        fighterHud.resetForMatchReset(); // Replica wings/enrolments are round-local
        // Fracture state dies with the round — the server detector's reset() clears
        // its families at the wrap (a cleared family can never apply a stale edge into the fresh
        // world). Cumulative counters persist; the totals line is the round's un-capped debrief
        // signal (the observability payment).
        fractures.resetForMatchReset();
        chat.resetForMatchReset(); // re-arm relay (queues survive the wrap by design)
        chatBubbles.resetForMatchReset(); // Bubbles die with the round; latch re-arms
        picker.resetForMatchReset(); // Listing dies with the round; latch re-arms
        voiceHud.resetForMatchReset(); // Glyph envelopes/held PTT die with the round
        synchronized (pendingFireDecls) {
            pendingFireDecls.clear();
        }
        fireDeclSeq = 0;
        synchronized (pendingAwsdNacks) {
            pendingAwsdNacks.clear(); // The merger's NACK state died at the MRST clear
        }
        resetClockMeter();
        // Pose provenance + spawn loc in the applied line (154407's logs could not
        // show the loc), plus how long the wire-true hold coasted (0/0 = own id live at receipt).
        long holdMs = mrstHoldStartMillis != 0L ? System.currentTimeMillis() - mrstHoldStartMillis : 0L;
        String sweepTerm = foeState.deployedEvict.takeResetSweepTerm(); //
        if (pendingLifeReset) {
            // L-R1: THE one delta over the stock MRST tail — a server rebirth
            // REGRESSES tick space (fresh JVM ticks from ~0), so the elapsed-time anchor re-arms
            // off the fresh life's first frame. MRST deliberately keeps it (rounds are
            // tick-CONTINUOUS on one life). Clock servo KEPT across both shapes (the MRST
            // precedent — it self-converges; revisit only on watch evidence).
            firstServerSimTick = Long.MIN_VALUE;
            lifeResetsApplied++;
            PilotWatchLog.info("server_life_reset applied serverLife="
                    + Long.toHexString(boundServerLife)
                    + " life_resets=" + lifeResetsApplied
                    + " own_variant=" + ownVariant + " foe_variant=" + foeVariant
                    + " pose_src=" + poseSrc + " own_loc=(" + ownLoc + ")"
                    + " hold_frames=" + mrstHoldFrames + " hold_ms=" + holdMs
                    + " swept_proj=" + sweptProj + " swept_bodies=" + sweptBodies + sweepTerm);
        }
        if (reset != null) {
            matchResetsApplied++;
            PilotWatchLog.info("match_reset applied reset_seq=" + reset.resetSeq()
                    + " completed_round=" + reset.completedRoundIndex()
                    + " next_round=" + reset.nextRoundIndex()
                    + " own_variant=" + ownVariant + " foe_variant=" + foeVariant
                    + " pose_src=" + poseSrc + " own_loc=(" + ownLoc + ")"
                    + " hold_frames=" + mrstHoldFrames + " hold_ms=" + holdMs
                    + " swept_proj=" + sweptProj + " swept_bodies=" + sweptBodies + sweepTerm);
        }
        retirePendingReset(reset);
        pendingLifeReset = false;
        mrstHoldStartMillis = 0L;
        mrstHoldFrames = 0;
    }

    /** L-R1: log tag for the boundary being applied — MRST rows carry a resetSeq; a pure
     * server-life rebaseline has no MRST frame ({@code reset == null}). */
    private static String resetTag(cc.starsector.client.net.ClientMatchReset reset) {
        return reset != null ? "resetSeq=" + reset.resetSeq() : "server-life-reset";
    }

    private void bindShips() {
        bindAttempts++;
        if (ownShip == null) {
            // Bind-once like the original gate: a transient null from getPlayerShip() on a later retry
            // must not unbind an own ship the meters/reconcile already hold.
            ownShip = engine.getPlayerShip();
            seatHud.applySeatName(ownShip, session.shipId()); // Name on the bind frame (no-op unbound/knob-off)
            fractures.suppressLocalBreakProb(ownShip); // The at-death fracture roll is server-owned
            rejoin.onFirstOwnSight(ownShip); // re-join deploy-burn kill (no-op unarmed/null)
            if (ownShip != null) {
                // The mission-fielded own hull rides
                // no spawn lane — start it at descriptor CR through the engine seam (kills the
                // 30% own-cr bind gap; the own-cr pin lane then no-ops at equal value).
                CrDeploySeam.applyDeployCr(ownShip, descriptorDeployCr);
            }
        }
        captureOwnSide("bindShips"); // Shared capture — see captureOwnSide
        installOwnCap();
        // Own ship: otherwise untouched — native player flight is the faithful local prediction.
        // Why sideAuthority(): the live read here mis-bound the own side's fresh ship as the
        // foe on a corpse-anchored rebind (owner 100) and on an anchorless seat-2 frame (null -> 0).
        int ownerSide = sideAuthority();
        for (ShipAPI ship : engine.getShips()) {
            if (ship == ownShip || !ship.isAlive() || ship.getOwner() == ownerSide) {
                continue; // first living ship on the OTHER side is the foe
            }
            foeShip = ship;
            foeShip.setCollisionClass(CollisionClass.SHIP);
            foeShip.setShipAI(new SubstepHoldAI(this, foeShip, engine)); // per-sub-step hold seam
            replicaBody.registerReplayDriven(foeShip); // Source fix: hold local autofire from the bind frame on
            foeShip.addListener(hullCap.new FoeDamageCap()); // axis A+B: local hits drop the bar but can't kill
            fractures.suppressLocalBreakProb(foeShip); // The at-death fracture roll is server-owned
            spawnModGate.suppress(foeShip); // The legacy 1v1 foe took its treatments inline, pre-gate -- a minefield/shard foe here would mint locally exactly like the replicas
            HullCapBound.offerReplicaFallback(foeShip); // the inline-treated 1v1 foe joins the cap's replica class slot too
            // The 1v1 foe IS the other seat's ship (deploy-order id contract, 1 <-> 2).
            seatHud.applySeatName(foeShip, session.shipId() == 1L ? 2L : session.shipId() == 2L ? 1L : -1L);
            break;
        }
        // Bind diagnostic: F1′ (carried=0 headless) localized to THIS scan — record which
        // gate filtered each candidate on the first attempt, on the attempt that binds, and sparsely
        // while the foe stays unbound (the advance() retry re-enters until both ships are bound).
        if (bindAttempts == 1 || foeShip != null || bindAttempts % 300 == 0) {
            try {
                StringBuilder scan = new StringBuilder();
                for (ShipAPI ship : engine.getShips()) {
                    scan.append(" [").append(Integer.toHexString(System.identityHashCode(ship)))
                            .append(" owner=").append(ship.getOwner())
                            .append(" alive=").append(ship.isAlive())
                            .append(" hp=").append(Math.round(ship.getHitpoints()))
                            .append(" hulk=").append(ship.isHulk())
                            .append(ship == ownShip ? " =own" : "")
                            .append(ship == foeShip ? " =foe" : "")
                            .append(']');
                }
                PilotWatchLog.info("predict bind diag: attempt=" + bindAttempts
                        + " player=" + (ownShip == null ? "null"
                                : Integer.toHexString(System.identityHashCode(ownShip)) + "/owner=" + ownerSide)
                        + " foeBound=" + (foeShip != null) + " ships=" + scan);
            } catch (Throwable ignore) {
                // diagnostic only; never break the watch
            }
        }
    }

    /**
     * Spawn-on-sight (bots shapes only; replaces the earlier
     * local-pool bind): the 2v2 real-window run MEASURED the server battle resolving in 164 paced frames
     * (~2.7 s) while the local mission's enemy fleet was still on its deploy ramp ({@code localPools
     * side1=0}), so wire entities died before any local body existed. Now the client mission fields
     * ONLY the seat ship and every remote entity gets its local body the FIRST frame its pose appears
     * on the wire — spawned at the authoritative pose via the engine's own
     * {@link com.fs.starfarer.api.combat.CombatFleetManagerAPI#spawnShipOrWing}, with the descriptor
     * row's variant GUARANTEED by the id contract (no local-pool bijection to race). Each spawned
     * ship gets the proven treatment: collision {@code SHIP}, {@code InertShipAI} (now {@link SubstepHoldAI}), a per-entity
     * {@link RemoteDamageCap}; the first side-1 spawn becomes {@link #foeShip} for the HUD/witness/
     * parity lanes. Log lines keep the {@code predict bots bind:} prefix so the smoke/window debrief
     * rows (C1/X3) parse unchanged. {@code freshSnapshot} (the caller's own stale-snapshot guard) gates
     * only the dead-before-sight clock: a pass against a stale snapshot never ages an unposed entry.
     *
     */
    private void spawnRemoteEntities(ServerSnapshotFrame snapshot, boolean freshSnapshot) {
        bindAttempts++;
        if (ownShip == null) {
            ownShip = engine.getPlayerShip();
            if (ownShip == null) {
                // this was the ONE silent path — it also skips the PARTIAL log below,
                // so a run stuck here showed zero registry activity. One-shot, plus an ACQUIRED line
                // when the anchor finally resolves, so the two suspected causes separate in the log.
                if (!remoteNoAnchorLogged) {
                    remoteNoAnchorLogged = true;
                    PilotWatchLog.info("predict bots bind: NO SIDE ANCHOR (engine.getPlayerShip()==null)"
                            + " at attempt=" + bindAttempts + " -> retrying every frame (one-shot log)");
                }
                return; // no side anchor yet; retry next frame
            }
            if (remoteNoAnchorLogged) {
                PilotWatchLog.info("predict bots bind: side anchor ACQUIRED at attempt=" + bindAttempts);
            }
            if (claimPending) {
                // W13 leg 2c: under a zero-hull build this anchor IS the engine's placeholder pod
                // (owner 0 — the side authority reads right); the seat hull replaces it at the claim.
                PilotWatchLog.info("predict bots bind: side anchor = t=0 placeholder (claim-at-play:"
                        + " the build's ONE placeholder flagship, else the engine pod) variant="
                        + (ownShip.getVariant() != null ? ownShip.getVariant().getHullVariantId() : "?")
                        + " owner=" + ownShip.getOwner()); // LINT-ALLOW corpse-side: the placeholder is never a corpse
            }
            // The own seat names from config/wire at anchor time; a later change made the
            // lane N-aware — spawned remotes now ALSO name at bind + via the dirty re-apply sweep
            // (SNMR records key any wire id), so gauntlet players see each other's picks.
            seatHud.applySeatName(ownShip, session.shipId());
            fractures.suppressLocalBreakProb(ownShip); // The at-death fracture roll is server-owned
            // Registry shapes never enter bindShips (the relay's only prior
            // home), so the re-join deploy-burn kill was structurally unreachable on the product
            // shape — this anchor bind IS the registry path's first own-hull sight (the
            // vanilla-fielded seat flagship). Bind rules themselves untouched.
            rejoin.onFirstOwnSight(ownShip);
            // The mission-fielded own hull rides
            // no spawn lane — start it at descriptor CR through the engine seam (kills the
            // 30% own-cr bind gap; the own-cr pin lane then no-ops at equal value).
            CrDeploySeam.applyDeployCr(ownShip, descriptorDeployCr);
        }
        // this lane never entered bindShips, so the anchor below was
        // never captured here — the exact mid-round-own-death poisoning the next comment warns
        // about happened live (fresh own hull minted owner-100, foe respawn at -99).
        captureOwnSide("spawnRemoteEntities");
        installOwnCap();
        // bind-side authority, never the live owner — a mid-round own-death here would
        // read 100 and send getFleetManager(100)/getFleetManager(-99) spawning into a wrong fleet.
        int ownerSide = sideAuthority();
        int awaiting = 0;
        for (RemoteEntity re : remoteEntities.values()) {
            if (re.ship != null || re.killed) {
                continue;
            }
            if (re.stationModule) {
                // A module row is ADOPTED onto an engine-minted child, never
                // spawned — a standalone spawnShipOrWing of a module variant would field a second,
                // parentless hull. An unbound module row is the station lane's repair case (4).
                continue;
            }
            if (mmnt.mountedOrPending(re.entityId)) {
                // A mounted (or mount-pending) seat's pose drives an
                // adopted module body — minting a free-floating hull at it is the
                // late-joiner hazard (MMNT replay lands before the RAPP catch-up; the pending
                // window must suppress too or the race re-opens).
                continue;
            }
            if (xfer.suppressMintFor(re.entityId)) {
                // A RETIRED id (post-COMMIT toWireId, the own
                // transfer pod) must never re-mint — a stale pre-COMMIT snapshot still carries
                // it alive (SNAP vs CTRL unordered); the mountedOrPending shape verbatim.
                continue;
            }
            ServerEntityPose pose = snapshot.entityById(re.entityId);
            if (re.dismountDepartHold) {
                // The seat's RETIRING module life can still show alive rows
                // this tick (the dismount CTRL drained ahead of the snapshot) — adopting one
                // re-fields the ride (module variant, slot pose) and its trailing DEPARTED
                // despawn hulks the mint. Hold until the vacate is reflected (row absent once),
                // then re-arm; bounded + loud like the re-key hold.
                long holdTick = snapshot.simTick();
                if (pose == null) {
                    re.dismountDepartHold = false;
                    re.dismountDepartHoldSinceTick = Long.MIN_VALUE;
                    // Consume the retiring life's queued end-of-life reason NOW: left queued, a
                    // FUTURE real death of this id could read a stale DEPARTED and present
                    // corpse-free — a masked desync (never-conceal).
                    cc.starsector.client.net.ClientDespawnReason retiring;
                    synchronized (despawnReasonByEntity) {
                        retiring = despawnReasonByEntity.remove(Long.valueOf(re.entityId));
                    }
                    PilotWatchLog.info("predict dismount depart-hold: entity " + re.entityId
                            + " vacate reflected (row absent) -> spawn-on-sight re-armed"
                            + (retiring != null ? "; retiring-life reason consumed: " + retiring : "")
                            + " tick=" + holdTick);
                    awaiting++;
                    continue;
                }
                if (re.dismountDepartHoldSinceTick == Long.MIN_VALUE) {
                    re.dismountDepartHoldSinceTick = holdTick;
                    PilotWatchLog.info("predict dismount depart-hold: entity " + re.entityId
                            + " retiring-life row still on the wire — holding spawn-on-sight"
                            + " for the vacate gap (max " + MOUNT_REKEY_POSE_HOLD_TICKS
                            + " ticks) tick=" + holdTick);
                }
                if (holdTick - re.dismountDepartHoldSinceTick <= MOUNT_REKEY_POSE_HOLD_TICKS) {
                    awaiting++;
                    continue;
                }
                long heldFor = holdTick - re.dismountDepartHoldSinceTick;
                re.dismountDepartHold = false;
                re.dismountDepartHoldSinceTick = Long.MIN_VALUE;
                PilotWatchLog.info("predict dismount depart-hold: entity " + re.entityId
                        + " TIMED OUT after " + heldFor + " ticks — no absence gap (INSTANT"
                        + " respawn shape?) -> adopting the live row LOUD tick=" + holdTick);
            }
            if (pose == null) {
                awaiting++; // not on the wire yet; retry next frame
                if (freshSnapshot) {
                    re.awaitFrames++; // the entity cap's dead-before-sight clock (RosterAppendLane): fresh frames only
                }
                continue;
            }
            if (pose.hitpoints() <= 0f) {
                // Died before first sight (frame-0 anchoring makes this a paused-start residual —
                // the wire roster drops dead ships, so no later frame can carry it): no body to
                // present. Record loudly, stop retrying; the census keeps it visibly unbound.
                re.killed = true;
                re.deathTick = snapshot.simTick(); // r1 kill tick
                PilotWatchLog.info("predict bots bind: entity " + re.entityId + " (side " + re.side
                        + ") DEAD AT FIRST SIGHT -> no local body spawned (stays unbound in the census until the entity cap retires it)");
                continue;
            }
            String variantId = expectedVariantFor(re);
            if (variantId == null) {
                re.killed = true; // id-contract violation — fail loud once, never spawn a guessed variant
                re.deathTick = snapshot.simTick(); // r1 kill tick
                PilotWatchLog.info("predict bots bind: ERROR entity " + re.entityId + " (side " + re.side
                        + ") has NO descriptor variant row -> not spawned (stays unbound in the census until the entity cap retires it)");
                continue;
            }
            try {
                float facing = Float.isFinite(pose.facing()) ? pose.facing() : (re.side == 0 ? 90f : 270f);
                int spawnOwner = re.side == 0 ? ownerSide : 1 - ownerSide;
                CombatFleetManagerAPI spawnMgr = engine.getFleetManager(spawnOwner);
                // The parity burn, fighter-excluded; logged on the SPAWNED line so any
                // watch grades the exclusion for free (fighter mints must read burn=0.0)
                float flyinBurn = replicaFlyinBurn(re, variantId);
                ShipAPI ship;
                // The damage-divergence root fix: EVERY replica hull — roster AND
                // appended — mints through the ONE member-first helper below, so the member's
                // captain + fleetCommanderForStats + fleet context exist BEFORE the engine bakes
                // the combat stats at instantiate (the server's AllSkillsAttach/OfficerAttach
                // deploy shape). The former bare spawnShipOrWing lane minted its member INSIDE the
                // engine and installed the commander post-spawn ⇒ Cybernetic Augmentation + the
                // threshold-scaled fleetwide damage-taken writers ELIDED on the first instantiate
                // of every seat replica (measured 1.21× client over-damage, bare-hull regime).
                // WING ids alone keep spawnShipOrWing (spawnFleetMember is a SHIP lane; roster
                // rows are never wings — loud if one ever is).
                boolean wingVariant = isWingVariantId(variantId);
                if (re.appended && wingVariant) {
                    // The growth lane announces PER-FIGHTER variant ids; a
                    // _wing SPEC id SHIP-minted here silently substitutes errorShipVariant
                    // (FleetMember.updateVariantIfNeeded SHIP branch) — log loud, mint anyway
                    // so the substitute stays visible in the census. Fighter
                    // VARIANT ids no longer trip this (isWingVariantId keys on the wing table).
                    PilotWatchLog.info("predict bots bind: WARNING entity " + re.entityId
                            + " appended variant '" + variantId + "' is a WING SPEC id -- the growth"
                            + " lane announces per-fighter ids; errorShipVariant expected");
                }
                if (re.appended || !wingVariant) {
                    ship = spawnReplicaMemberFirst(spawnMgr, spawnOwner, re, variantId,
                            new Vector2f(pose.locX(), pose.locY()), facing, flyinBurn,
                            re.appended ? re.appendShipName : null,
                            re.appended ? re.appendCrPerMille / 1000f : descriptorDeployCr);
                } else {
                    // Roster WING row (never seen on the standing shape): the engine's own
                    // captain-bearing overload — a null captain (no officer row / knob off) is
                    // the exact pre-officer call shape.
                    PilotWatchLog.info("predict bots bind: entity " + re.entityId
                            + " roster variant '" + variantId + "' is a WING id -> spawnShipOrWing"
                            + " lane (member minted in-engine; the commander residual applies)");
                    Boolean feedTok = feedGate.mute(spawnMgr); //
                    try {
                        ship = spawnMgr.spawnShipOrWing(variantId,
                                new Vector2f(pose.locX(), pose.locY()),
                                facing, flyinBurn, // burn-0 unless remounted; (77) fighters 0
                                OfficerLoadout.captainOrNull(variantId,
                                        MatchMissionBuilder.descriptorVariantForShipIdOrNull(
                                                re.entityId),
                                        MatchMissionBuilder.isSeatShipId(re.entityId)));
                    } finally {
                        feedGate.unmute(spawnMgr, feedTok);
                    }
                    if (ship != null) {
                        // this lane applies NO CR — the engine defaults
                        // 0.7 while the server deploys every body at descriptor CR; the CR seam corrects it.
                        CrDeploySeam.applyDeployCr(ship, descriptorDeployCr);
                    }
                }
                if (ship == null) {
                    // A NULL mint spends the entry's spawn
                    // budget (the lane logs the first and the spent budget, never every frame).
                    awaiting++;
                    rapp.onSpawnFailed(re, variantId,
                            (re.appended || !wingVariant) ? "spawnFleetMember" : "spawnShipOrWing",
                            null, snapshot.simTick());
                    continue;
                }
                re.ship = ship;
                rapp.onSpawned(re);
                re.remounted = false; // The parity burn is per-MINT — spent here, re-armed by the next remount / reset seat re-bind
                // Probe row 0 BEFORE the first-sight snap below — its drift = the
                // engine's ten vanilla-AI pre-advances at deploy.
                swarmCloudProbe.onMinted(ship, re.entityId, pose.locX(), pose.locY());
                fighterHud.onRemoteBound(re); // A bay-born fighter joins the piloted carrier's replica wing
                remoteUnbound--;
                // Recut: EVERY seat hull on EVERY window shows the short list — the ASK-3
                // display twin runs at the replica lanes too (the codex targets the RETICLE
                // ship, and rival-seat replica members kept the real 19-skill person).
                // Seat-walk gated; no-op inside unless the all-skills knob is ON.
                if (MatchMissionBuilder.isSeatShipId(re.entityId)) {
                    // CA visibility BEFORE the twin: the twin swaps the member's captain,
                    // and the commander field must be on the member the engine will reuse.
                    OfficerLoadout.installSeatCommanderVisibility(ship);
                    OfficerLoadout.applySeatDisplayTwin(ship);
                } else {
                    // Grunt coverage for the spawnShipOrWing lane (member minted
                    // inside the engine — pair installs post-spawn, heals from the next
                    // instantiate; the commander-residual class). Side gate as above.
                    OfficerLoadout.attachFleetContext(ship,
                            MatchMissionBuilder.descriptorSideForShipIdOrMinusOne(re.entityId));
                }
                if (cc.starsector.client.net.ClientCommandTransfer.POD_WIRE_VARIANT_ID
                        .equals(variantId)) {
                    // The pod collision-class fix: match the SERVER's body,
                    // not vanilla's transfer shuttle (vanilla's NONE is for that shuttle ONLY).
                    // Seat row re-pointed to the pod variant = the FIELDED respawn pod, a normal
                    // collidable 1000-hp ship server-side -> SHIP (shots land + damage applies
                    // locally; hull/armor reconcile hold it at wire truth). Appended podWireId
                    // row = the /transfer flight replica -> NONE BOTH sides by law.
                    // Both keep the native 1s launch pop-in; no per-frame gate owed (the server
                    // deploys the respawn pod at the map edge, never at the wreck). Pod branch
                    // stays FIRST — the else-chain would force SHIP on any non-fighter.
                    // The ONE-ENTITY pod: a seat row whose pod is FLYING is NONE
                    // server-side (xfer spec) — mint NONE too; the lane flips SHIP at the STRANDED abort.
                    ship.setCollisionClass(MatchMissionBuilder.isSeatShipId(re.entityId)
                            && !xfer.isFlyingPodSeat(re.entityId)
                            ? CollisionClass.SHIP : CollisionClass.NONE);
                    ship.setAnimatedLaunch();
                } else if (DroneGateLane.isDroneReplica(ship)) {
                    // The drone-collision fix: the engine's drone<->mothership
                    // narrowphase exemption rides getAI() instanceof DroneAI (an engine-internal check,
                    // no setter; NOT isDrone(), which is plain mutable state the drone launcher
                    // sets — FALSE on replicas), so a collidable drone replica is born colliding
                    // INSIDE its station (560.00006 KINETIC ×10/10 both seats) while
                    // armor has no reconcile lane — the chew is permanent. Mint intangible +
                    // the engine's own launch pop-in (setAnimatedLaunch = its native 1 s NONE
                    // window), then the per-frame gate (applyDroneCollisionGate) flips to SHIP
                    // once clear of every station complex — steady orbit is 650 su + radius out
                    // (drone_station_lt), so foe fire hits the drone locally exactly as vanilla.
                    ship.setCollisionClass(CollisionClass.NONE);
                    ship.setAnimatedLaunch();
                    re.droneGate = true;
                    re.droneIntangible = true;
                } else if (!ship.isFighter()) {
                    // Fighters keep the hull-spec-derived FIGHTER collision class
                    // (ShipHullSpecLoader derives it from hullSize; the engine's own
                    // getCollisionClass() override then handles the disabled/landing edges natively)
                    ship.setCollisionClass(CollisionClass.SHIP);
                }
                // The four proven treatments (InertShipAI + replay-driven
                // hold-fire + per-entity RemoteDamageCap + break-prob suppression) moved VERBATIM
                // behind the ledgered helper, so no body can ever take a SECOND cap — the station
                // lane treats the same bodies from its own adopt path.
                neutralizeRemoteBody(ship, re);
                // A growth-born omega child's bind IS the split frame
                if (re.appended) fractures.noteAppendedBirth(ship);
                // A growth-born construction-swarm CARRIER takes vanilla's
                // own shape (bounds / collision radius / cloud radius × the class the keyframe's max
                // hull names) — BEFORE the hull lane's first maintain mirrors that max onto the body.
                if (re.appended) constructionFx.onCarrierBound(ship, pose);
                // A growth-born Threat constructable beside its carrier replica
                // starts the LOCAL reveal (AFTER neutralizeRemoteBody: the treated ledger is set).
                if (re.appended) constructionFx.onReplicaBound(ship, engine);
                // A growth-born reclamation swarm beside a Threat hulk IS the
                // server's reclamation script frame — start the LOCAL dissolve on that family.
                if (re.appended) reclamationFx.onReplicaBound(ship, engine, fractures);
                boolean treatmentFailed = replicaBody.lastTreatmentFailed; // read on the spot (same thread)
                if (ship.isStation()) {
                    // Review fix: the engine deploy minted the whole module cluster
                    // inside this same spawn call — treat the children NOW (no cap: their wire
                    // rows are SABL's, minted at bind) so no engine tick ever advances a
                    // vanilla-AI module. The next frame's sweepInert stays the fail-closed net.
                    for (ShipAPI child : ship.getChildModulesCopy()) {
                        if (child != null) {
                            neutralizeRemoteBody(child, null);
                        }
                    }
                }
                // FIRST-SIGHT hard-snap — adopt the full kinematic state (velocity +
                // angular velocity on top of the spawn args' loc/facing), so a mid-stream appearance
                // (SEAT claim⇒fly-in, late join) starts moving exactly as the authority has it. This IS
                // the per-entity late-join adopt AND the mid-stream entity-appear — one mechanism.
                rejoin.hardSnapToPose(ship, pose);
                // A Hive-launched swarm fighter is born INSIDE its Hive's cloud — vanilla
                // SwarmLauncherEffect.onFire transfers 50 bright members to 0.5 s ahead; the
                // replica lane minted a fresh dim cloud AT the body (probe). AFTER the snap: the
                // lead rides the adopted wire velocity. No donor in reach = vanilla's fresh law stands.
                if (re.appended) SwarmLaunchSeed.seed(ship, re.entityId, engine);
                // wire-first name at replica bind (no-op when no SNMR record names this
                // id); a record landing later re-applies via the dirty sweep.
                seatHud.applySeatName(ship, re.entityId);
                if (re.side == 1 && foeShip == null && !ship.isFighter()) {
                    // the primary foe for the HUD/witness/parity lanes — never a fighter
                    // (respawnRemote clears the anchor and the NEXT side-1 spawn re-captures
                    // it, which growth can make a fighter)
                    foeShip = ship;
                }
                if (treatmentFailed) {
                    // The body IS bound, but its replica treatment threw: it may still fly a local
                    // AI and take uncapped damage. Never report that as a clean SPAWNED — count it
                    // awaiting so the census shows this bind is not complete.
                    awaiting++;
                    PilotWatchLog.error("predict bots bind: entity " + re.entityId + " (side "
                            + re.side + ") <- TREATMENT FAILED on ship "
                            + Integer.toHexString(System.identityHashCode(ship)) + " variant="
                            + variantId + " (see the neutralize error above) — the body is bound"
                            + " but may act locally and takes uncapped damage", null);
                } else {
                    PilotWatchLog.info("predict bots bind: entity " + re.entityId + " (side "
                            + re.side + ") <- SPAWNED ship "
                            + Integer.toHexString(System.identityHashCode(ship))
                            + " owner=" + ship.getOwner() + " variant=" + variantId
                            + " at (" + pose.locX() + "," + pose.locY() + ") cr="
                            + ship.getCurrentCR() + " burn=" + flyinBurn
                            // The instantiate-time stat signature (the server twin
                            // prints the same getters at its deploy) — fc=1 + cmdElite>0 at
                            // SPAWN proves the member-first mint; a missing cybernetic_augmentation key on
                            // hdmMods names the elision directly.
                            + " tstat{" + TargetStatSig.verbose(ship) + "}");
                }
            } catch (Throwable t) {
                // fail-soft per entity: a spawn failure stays visible as unbound in the census.
                // The throw spends the entry's spawn budget
                // (its first throw logs with the stack trace, later ones only count).
                awaiting++;
                rapp.onSpawnFailed(re, variantId, "SPAWN", t, snapshot.simTick());
            }
        }
        if (remoteUnbound == 0 && !remoteBindCompleteLogged) {
            remoteBindCompleteLogged = true;
            PilotWatchLog.info("predict bots bind: COMPLETE bound=" + remoteEntities.size()
                    + "/" + remoteEntities.size() + " attempts=" + bindAttempts);
        } else if (remoteUnbound > 0 && (bindAttempts == 1 || bindAttempts % 300 == 0)) {
            PilotWatchLog.info("predict bots bind: PARTIAL unbound=" + remoteUnbound
                    + "/" + remoteEntities.size() + " attempt=" + bindAttempts
                    + " awaitingFirstSight=" + awaiting);
        }
    }

    /** The descriptor variant a wire entity must spawn as — the dense id-contract walk read
     *  DIRECTLY off the descriptor rows at engage ({@link #variantById}; kills the
     *  old side-offset arithmetic and its SEAT-exclusion asymmetry) — spawn-on-sight GUARANTEES the
     *  variant instead of log-asserting a local-pool pairing. {@code null} = no row (contract violation). */
    private String expectedVariantFor(RemoteEntity re) {
        return variantById.get(Long.valueOf(re.entityId));
    }

    /**
     * The join-pipeline selftest seam: the replica crew law of the mint
     * below, pure — {@code max(maxCrew, minCrew, 1)}. A factory member ships with ZERO crew and
     * {@code canBeDeployedForCombat} locks any hull under {@code noDeployCrewPercent} (5 %), so
     * the floor is never below 1 and never below the combat-time min (0.8 × max reads UNDER
     * the CR plugin's understrength line). {@code JoinPipelineLawSmoke} pins the table.
     */
    static float replicaCrew(float maxCrew, float minCrew) {
        return Math.max(Math.max(maxCrew, minCrew), 1f);
    }

    /**
     * The ONE replica mint lane (the damage-divergence root fix): the server
     * {@code midDeployWaveShip} / seat-deploy recipe's public-API twin, formerly the
     * appended-wave-only shape, now EVERY replica hull. Order is the law:
     * {@code createFleetMember} → {@code setOwner} (the factory member's owner is hardcoded
     * to 0) → name/CR → {@link OfficerLoadout#attach} (captain AND, for a seat
     * under the all-skills knob, {@code setFleetCommanderForStats} + fleet context — the
     * server's AllSkillsAttach ordering) → {@link OfficerLoadout#attachFleetContext} (the
     * grunt pair, descriptor-side-0 rows only; both skipped for a seat's flying transfer pod, which
     * the server builds bare) → {@link OfficerLoadout#attachAppendedCommander}
     * (a non-seat row's pair — waves, pods (a seat's flying transfer pod too), stations, side-1 descriptor rows — keyed on its absolute side) → {@code spawnFleetMember} (explicit loc + facing
     * + burn) → the CR seam. Because the member exists BEFORE the engine
     * instantiates it for combat, every captain/commander-derived stat (Cybernetic Augmentation,
     * threshold-scaled fleetwide damage-taken writers) derives at create — no post-spawn
     * residual ({@code installSeatCommanderVisibility} stays as the idempotent post-spawn
     * twin and now no-ops). Seat-ness + transfer key come from the dense id walk (W7 B1b: seat
     * repoints fan through here, wave ids walk false/null). The feed gate mutes the
     * "Incoming vessels" line like every replica lane. Returns null when the
     * engine declines the spawn (caller retries next frame — unchanged census contract).
     */
    private ShipAPI spawnReplicaMemberFirst(CombatFleetManagerAPI spawnMgr, int spawnOwner,
            RemoteEntity re, String variantId, Vector2f loc, float facing, float flyinBurn,
            String shipNameOrNull, float deployCr) {
        FleetMemberAPI member = Global.getFactory()
                .createFleetMember(FleetMemberType.SHIP, variantId);
        member.setOwner(spawnOwner);
        if (shipNameOrNull != null) {
            member.setShipName(shipNameOrNull);
        }
        member.getRepairTracker().setCR(deployCr);
        // A root-cause fix: the server twin's crew
        // step this lane skipped. spawnFleetMember → instantiateForCombat reads
        // canBeDeployedForCombat() BEFORE the engine's own crew seed
        // runs, and a factory member has ZERO crew (< noDeployCrewPercent
        // 5 %) ⇒ setControlsLocked(true) on EVERY replica minted here ⇒ the engine's task manager
        // hands the hull a RETREAT assignment ⇒ the yellow tactical-map band on every side-0
        // replica (remote seat bodies, their pods, their vacated hulls; t=0 mission hulls are
        // mission-crewed, hence unbanded). Server idiom (RealEngineDemoRoster crew fix):
        // max(maxCrew, minCrew, 1) — bare minCrew reads 0.8 under the combat-time minCrew and
        // the CR plugin's understrength malus drags maxCR.
        member.getCrewComposition().setCrew(replicaCrew(member.getMaxCrew(), member.getMinCrew()));
        // A RAPP v3 row carries the captain the SERVER attached — attach exactly
        // that (the wire is the authority on what the hull carries); a bare row keeps the
        // variant-keyed table lookup byte-identical (t=0 rows, seat repoints, pods, fighters), save
        // the seat's flying transfer pod below.
        boolean seatRow = MatchMissionBuilder.isSeatShipId(re.entityId);
        boolean podVariant = cc.starsector.client.net.ClientCommandTransfer.POD_WIRE_VARIANT_ID.equals(variantId);
        boolean podFlying = xfer.isFlyingPodSeat(re.entityId);
        // A seat row on the pod variant while its transfer flies is that transfer's pod riding the
        // seat's own wire id. The server builds it bare on the seat's side, so the replica takes no
        // captain and neither the seat pair nor the fleet-context pair; its pair comes from the side
        // law below alone (OfficerLoadout.isSeatTransferPod). Every other seat row is unchanged.
        boolean transferPod = OfficerLoadout.isSeatTransferPod(seatRow, podVariant, podFlying);
        // The captain arm: the wire officer line, else none for the transfer pod, else the all-skills
        // bit's seat-shaped mint, else the bare variant-keyed lookup (appendedCaptainArm).
        appendedCaptainArm(member, re.appendOfficer, re.appendAllSkillsCaptain, variantId,
                MatchMissionBuilder.descriptorVariantForShipIdOrNull(re.entityId), seatRow, transferPod,
                re.entityId);
        if (!transferPod) {
            OfficerLoadout.attachFleetContext(member,
                    MatchMissionBuilder.descriptorSideForShipIdOrMinusOne(re.entityId));
        }
        // A non-seat row takes the commander pair
        // the server resolves for it, keyed on its ABSOLUTE side (re.side is relative to the own team)
        // and never on the local manager it spawns into: under the all-skills knob side 1 = a plain
        // commander, side 0 = the union with no fleet data for a wave, a pod or a station. A seat's
        // flying transfer pod takes it as a pod. A seat keeps the pair attach() gave it, and so does an
        // all-skills-bit hull, read as a seat (appendedPairSeatFlag; with the flag OFF no pair installs
        // on either); a descriptor side-0 grunt keeps the fleet-context pair.
        OfficerLoadout.attachAppendedCommander(member, re.appended,
                appendedPairSeatFlag(seatRow, re.appendAllSkillsCaptain), podVariant, podFlying,
                re.side, ownDescSide);
        Boolean feedTok = feedGate.mute(spawnMgr); //
        ShipAPI ship;
        try {
            ship = spawnMgr.spawnFleetMember(member, loc, facing,
                    flyinBurn); // fly-in parity (server twin: FLY_IN_BURN_DUR); fighters 0
        } finally {
            feedGate.unmute(spawnMgr, feedTok);
        }
        if (ship != null) {
            // the server twin's post-spawn CR set: through the engine seam, so
            // cr_effect applies (a bare field stamp never crossed it).
            CrDeploySeam.applyDeployCr(ship, deployCr);
        }
        return ship;
    }

    /** Cap on {@link #ALLSKILLS_KNOB_OFF_ANNOUNCED}: past it the knob-OFF line stays silent (the
     *  disagreement is a whole-kit fact, already named this many times). */
    private static final int ALLSKILLS_KNOB_OFF_ANNOUNCE_CAP = 64;

    /** The entity ids whose all-skills-bit-with-the-flag-OFF line already fired: a spawn the engine
     *  declines re-enters {@link #appendedCaptainArm} every frame until its failure budget spends, so
     *  the line fires once per entity id for the kit's life, never once per retry. */
    private static final Set<Long> ALLSKILLS_KNOB_OFF_ANNOUNCED =
            Collections.synchronizedSet(new java.util.HashSet<Long>());

    /**
     * The captain arm of {@link #spawnReplicaMemberFirst},
     * pure of the plugin's state so a game-free row drives every branch. In order: the WIRE officer
     * line wins (the server attached exactly that spec); a seat's flying transfer pod takes no
     * captain (the server builds it bare); the all-skills bit (the FACT that the server's restored
     * captain on this hull IS its all-skills seat mint) mints the SEAT-shaped captain when this kit's
     * all-skills match flag is ON — {@link OfficerLoadout#attach(FleetMemberAPI, String, String, boolean)}
     * with seat = true installs the union commander and the side-0 fleet context, then the all-skills
     * captain; the bit with the flag OFF is a pair disagreement (the server mints all-skills seats,
     * this kit does not), so the hull takes the bare path and ONE loud line per entity names it —
     * never a mint from a lone bit; otherwise the bare path, the variant-keyed curated lookup with
     * the seat's descriptor fallback, unchanged from the pre-bit arm. {@code descriptorVariantOrNull}
     * is the caller's descriptor lookup for {@code entityId} (u32 wire id, &gt; 0) and reaches only
     * the bare path. Returns the arm taken: {@code officer} | {@code pod} | {@code all-skills} |
     * {@code all-skills-knob-off} | {@code bare}.
     */
    static String appendedCaptainArm(FleetMemberAPI member,
            cc.starsector.server.match.OfficerRosterCodec.OfficerSpec appendOfficer,
            boolean appendAllSkillsCaptain, String variantId, String descriptorVariantOrNull,
            boolean seatRow, boolean transferPod, long entityId) {
        if (appendOfficer != null) {
            OfficerLoadout.attach(member, appendOfficer, entityId);
            return "officer";
        }
        if (transferPod) {
            return "pod";
        }
        if (appendAllSkillsCaptain && OfficerLoadout.allSkillsEnabled()) {
            OfficerLoadout.attach(member, variantId, null, true);
            return "all-skills";
        }
        String arm = "bare";
        if (appendAllSkillsCaptain) {
            arm = "all-skills-knob-off";
            if (ALLSKILLS_KNOB_OFF_ANNOUNCED.size() < ALLSKILLS_KNOB_OFF_ANNOUNCE_CAP
                    && ALLSKILLS_KNOB_OFF_ANNOUNCED.add(Long.valueOf(entityId))) {
                PilotWatchLog.error("predict roster-append: entity " + entityId
                        + " carries the all-skills bit but this kit's match flag is OFF (pair"
                        + " disagreement: the server mints all-skills seats, this kit does not)"
                        + " - bare captain", null);
            }
        }
        OfficerLoadout.attach(member, variantId, descriptorVariantOrNull, seatRow);
        return arm;
    }

    /** The seat flag {@link OfficerLoadout#attachAppendedCommander}
     *  reads for a member-first replica — a seat row, or a hull whose server-restored captain IS the
     *  all-skills seat mint: that hull keeps the SEAT-shaped commander pair its captain arm installed,
     *  as the server's vacated hull keeps its own (a non-seat pair there would split the stats). */
    static boolean appendedPairSeatFlag(boolean seatRow, boolean appendAllSkillsCaptain) {
        return seatRow || appendAllSkillsCaptain;
    }

    /** Is this id a fighter WING SPEC id (the {@code spawnFleetMember} SHIP lane cannot mint it)?
     *  A resolvable SHIP VARIANT — fighter hulls included ({@code hoplon_Escort},
     *  {@code attack_swarm_Attack}) — is NOT a wing: the member-first lane mints every one of them
     *  as a wing-less FIGHTER-hull ship (the former {@code v.isFighter()} read
     *  fired the WING warning on every appended fighter while the engine substituted nothing).
     *  Engine-table reads, fail-soft to the {@code _wing} suffix the growth lane keys on. */
    private static boolean isWingVariantId(String variantId) {
        if (variantId == null) {
            return false;
        }
        try {
            if (Global.getSettings().getVariant(variantId) != null) {
                return false; // a ship variant (any hull size) — the SHIP lane mints it
            }
        } catch (RuntimeException ignore) {
            // unknown id may throw instead of returning null — fall through to the wing-spec read
        }
        try {
            if (Global.getSettings().getFighterWingSpec(variantId) != null) {
                return true;
            }
        } catch (RuntimeException ignore) {
            // unknown wing id may throw — fall through to the suffix law
        }
        return variantId.endsWith("_wing");
    }

    /**
     * The owed raw own-wire-hull probe at the ONE
     * mirrorHull site: decides any future wire-vs-ledger fork in a single row (wireHp≈auth ⇒
     * ledger math; wireHp≈0 ⇒ decode/demux). Emits on fresh own-pose frames only: the first few
     * frames (decode sanity at join/round), then only while the ledger is engaged or the hull
     * sits near the floor, rate-limited and line-capped. Diagnostic-only; never throws.
     */
    private void maintainOwnHullProbe(ShipAPI own, float wireHp, float target,
            boolean freshSnapshot, long simTick) {
        if (!freshSnapshot || ownHullProbeLines >= 40) {
            return;
        }
        try {
            ownHullProbeCooldown++;
            float max = own.getMaxHitpoints();
            boolean ledgerEngaged = Math.abs(target - wireHp) > 0.5f;
            boolean nearFloor = max > 0f && own.getHitpoints() / max < 0.02f;
            boolean firstFrames = ownHullProbeLines < 5 && ownHullProbeCooldown <= 5;
            if (!firstFrames && !((ledgerEngaged || nearFloor) && ownHullProbeCooldown >= 30)) {
                return;
            }
            ownHullProbeCooldown = 0;
            ownHullProbeLines++;
            PilotWatchLog.info("predict own-hull probe: t=" + simTick
                    + " wireHp=" + fmt1(wireHp) + " target=" + fmt1(target)
                    + " postHull=" + fmt1(own.getHitpoints()) + "/" + fmt1(max)
                    + (ownHullProbeLines == 40 ? " [line cap reached]" : ""));
        } catch (Throwable ignore) {
            // probe only; the mirror already ran
        }
    }

    /**
     * The OWN-flux APPLY lane (see {@link PredictKnobConfig#OWN_FLUX_MODE}); meters live in
     * {@link OwnReadoutLane#maintainFluxReadout} (sampled before this pins). Fresh own-pose frames pin the own
     * tracker's curr/hard flux to authority (the foeFlux mirror, boost-clock settled the same
     * way), then the overload/vent STATE is slaved to the carried fluxFlags bits — but the OWN
     * lanes echo through the UP-intent sampler ({@link #captureOwnCommands} reads the very states
     * this writes), so slaving is echo-gated, unlike the foe lane:
     * <ul>
     * <li>overload RISE (auth on, local off): immediate — authority says overloaded, render it.
     *     No feedback hazard: during the slaved lockout the mask guard holds bits 7/10/11
     *     and the auth side is genuinely locked anyway.</li>
     * <li>overload/vent FALL (local on, auth off): immediate ONLY on a true authoritative falling
     *     edge (the server explicitly ended a state it had confirmed); otherwise the local state
     *     is UNCONFIRMED — a legit local onset leads the auth bit by ≈RTT (the UP echo), so wait
     *     {@link PredictKnobConfig#OWN_FLUX_SLAVE_CONFIRM_FRESH} fresh frames before stopping. A BUG-B spurious
     *     overload now renders ≤~300 ms instead of a full local overload.</li>
     * <li>vent RISE: NEVER slaved. The bit-8 sampler reads {@code isVenting()} — re-venting the
     *     local ship against the player's own cancel would raise bit-8 again and livelock the
     *     server's vent through the echo (the meter counts the disagreement instead; a
     *     server-side vent can only exist because OUR bit-8 asked for it, so there is no
     *     legitimate auth-on/local-off steady state).</li>
     * </ul>
     *
     * <p>The curr/hard pin rides
     * the {@link OwnFluxPinLaw} dead band (the exact pin rewound the bar an RTT per snapshot), and
     * the overload rise rides {@link OverloadWindowLaw} (one begin per wire window — the level test
     * re-began a full overload on the lagged-fall tail). Meters on {@link #logOwnFluxMeter}.
     */
    private void maintainOwnFlux(ShipAPI own, ServerEntityPose ownPose, boolean freshSnapshot,
            long simTick) {
        if (!OWN_FLUX_APPLY || own == null || ownPose == null) {
            return;
        }
        try {
            FluxTrackerAPI ft = own.getFluxTracker();
            if (ft == null) {
                return;
            }
            if (ownOverloadFloatyHeld) {
                maintainOwnHeldOverloadFloaty(own, ft, simTick); // before the edges
            }
            if (freshSnapshot && ownPose.hasFlux()) {
                // The exact pin set the
                // RTT-old wire flux every snapshot (a saw-tooth rewind). Gap meters record FIRST
                // (pre-decision, so the divergence stays visible), then OwnFluxPinLaw's dead band
                // (max(2 % max, staleTicks x own dissipation)) decides; outside it = today's pin verbatim.
                // staleTicks = the fresh-frame inter-snapshot delta (the PRCP maintainOwnVelReconcile
                // dTicks idiom: first frame 1, floor 1), own field so this lane never reads another's clock.
                long staleTicks = ownFluxPrevFreshSimTick == Long.MIN_VALUE ? 1L
                        : Math.max(1L, simTick - ownFluxPrevFreshSimTick);
                ownFluxPrevFreshSimTick = simTick;
                float wireCurr = ownPose.currFlux();
                float wireHard = ownPose.hasHardFlux() ? ownPose.hardFlux() : Float.NaN;
                float maxFlux = ft.getMaxFlux();
                float gap = Math.abs(ft.getCurrFlux() - wireCurr);
                if (!Float.isNaN(wireHard)) {
                    gap = Math.max(gap, Math.abs(ft.getHardFlux() - wireHard));
                }
                if (maxFlux > 0f && Float.isFinite(gap)) {
                    float frac = gap / maxFlux;
                    ownFluxGapMaxFrac = Math.max(ownFluxGapMaxFrac, frac);
                    ownFluxGapSumFrac += frac;
                    ownFluxGapSamples++;
                }
                float band = OwnFluxPinLaw.band(maxFlux, staleTicks,
                        own.getMutableStats().getFluxDissipation().getModifiedValue(),
                        ServerTickRate.ticksPerSecond());
                if (!OwnFluxPinLaw.pairPinDue(ft.getCurrFlux(), wireCurr, ft.getHardFlux(), wireHard, band)) {
                    ownFluxPinBandSkips++;
                } else {
                    ft.setCurrFlux(wireCurr);
                    if (ownPose.hasHardFlux()) {
                        ft.setHardFlux(ownPose.hardFlux());
                    }
                    ownFluxPinFrames++;
                    if (settleBoostClockAfterFluxPin(own, ft, wireCurr)) {
                        ownFluxBoostClockZeros++;
                    }
                }
            }
            if (!ownPose.hasFluxFlags()) {
                return;
            }
            boolean authOvl = (ownPose.fluxFlags() & FLUX_FLAG_OVERLOADED) != 0;
            boolean authVent = (ownPose.fluxFlags() & FLUX_FLAG_VENTING) != 0;
            boolean authOvlFell = false;
            boolean authVentFell = false;
            boolean authPhasedNow = ownPose.isPhasedBit();
            boolean authPhasedPrior = prevOwnAuthPhased; // the fresh frame BEFORE this one (F2 gate)
            if (freshSnapshot) {
                authOvlFell = prevOwnAuthOverloaded && !authOvl;
                authVentFell = prevOwnAuthVenting && !authVent;
                prevOwnAuthOverloaded = authOvl;
                prevOwnAuthVenting = authVent;
                prevOwnAuthPhased = authPhasedNow;
                // The echo clocks tick on fresh frames only (the auth side can only answer at
                // snapshot cadence); any agreeing fresh frame resets them.
                ownOverloadUnconfirmedFresh =
                        (ft.isOverloaded() && !authOvl) ? ownOverloadUnconfirmedFresh + 1 : 0;
                ownVentUnconfirmedFresh =
                        (ft.isVenting() && !authVent) ? ownVentUnconfirmedFresh + 1 : 0;
            }
            // The old LEVEL test
            // (authOvl && !isOverloaded) re-began a full overload + sound + floaty when the local
            // overload expired before the lagged wire fall. OverloadWindowLaw: ONE begin per wire window.
            int ovlAct = OverloadWindowLaw.action(authOvl, ft.isOverloaded(), ownOvlBegunThisWindow);
            if (ovlAct == OverloadWindowLaw.OVL_ACT_BEGIN) {
                if (ft.isVenting()) {
                    ft.stopVenting(); // begin no-ops while venting
                }
                ft.beginOverloadWithTotalBaseDuration(SLAVE_OVERLOAD_BASE_SECONDS);
                ft.playOverloadSound();
                // An auth overload rising right after a wire LASH arc on this PHASED
                // hull is the Overseer's phase-field disruption — vanilla paints the bright EMP
                // overload colour + its own floaty, reset when the
                // overload ends. Vanilla begins that overload only
                // `if (target.isPhased())`, so the gate = the local cloak (client-driven, the
                // player's own press) OR the server's phased bit on the fresh frame BEFORE the rise
                // (the cloak drops ≤ 4 ticks after the overload; also covers a press the server saw
                // first) OR on the rise frame. Unphased ⇒ vanilla's plain floaty, no hold.
                boolean phaseHull = own.isPhased() || authPhasedPrior || authPhasedNow;
                boolean arcInWindow = ArcHitFxLane.lashHitOwnRecently();
                boolean lashed = phaseHull && arcInWindow;
                if (lashed) {
                    own.setOverloadColor(VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR_BRIGHT);
                    ft.showOverloadFloatyIfNeeded("Phase Field Disruption!",
                            VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR, 4.0F, true);
                    ownLashOverloadColored = true;
                    ownLashOverloads++;
                } else if (phaseHull) {
                    // No arc yet — the flag can beat the hit by one tick across a
                    // snapshot edge; hold the plain floaty for a trailing lash arc (released below).
                    ownOverloadFloatyHeld = true;
                    ownOverloadFloatyHeldSince = System.nanoTime();
                } else {
                    if (arcInWindow) {
                        ownLashUnphasedSkips++; // the F2 class: lashed while unphased, self-overloaded
                    }
                    ft.showOverloadFloatyIfNeeded(); // plain "Overloaded!", vanilla's caller-side one-shot
                }
                ownOverloadSlaveBegins++;
                ownFluxLane.log("predict own-flux overload BEGIN (auth rise) @tick=" + simTick
                        + " phased=" + (own.isPhased() ? "local" : "-") + "/" + (authPhasedPrior ? "prior" : "-")
                        + "/" + (authPhasedNow ? "now" : "-") + (arcInWindow && !lashed ? " lash=UNPHASED-SKIP" : "")
                        + (lashed ? " lash=PHASE-FIELD-DISRUPTION" : ""));
            } else if (ovlAct == OverloadWindowLaw.OVL_ACT_SUPPRESS_RESTART) {
                // The expired tail inside a still-raised wire
                // window — hold, never re-begin; metered + one row per window (the disagree meter
                // in OwnReadoutLane stays untouched so the tail divergence is still visible).
                ownOverloadRestartSuppressed++;
                // The wire STILL says OVERLOADED — hold the engine's
                // own state SILENTLY (state-only begin; no sound, no floaty, no lash paint), so
                // the own ship never fires/shields locally against a server that still has it overloaded.
                if (ft.isVenting()) {
                    ft.stopVenting();
                }
                ft.beginOverloadWithTotalBaseDuration(SLAVE_OVERLOAD_BASE_SECONDS);
                if (!ownOvlSuppressLogged) {
                    ownOvlSuppressLogged = true;
                    ownFluxLane.log("predict own-flux overload RESTART-SUPPRESSED (expired tail, wire still"
                            + " overloaded) @tick=" + simTick);
                }
            } else if (ft.isOverloaded() && !authOvl
                    && (authOvlFell || ownOverloadUnconfirmedFresh >= OWN_FLUX_SLAVE_CONFIRM_FRESH)) {
                ft.stopOverload();
                if (ownLashOverloadColored) {
                    own.resetOverloadColor(); // vanilla's own reset
                    ownLashOverloadColored = false;
                }
                ownOverloadFloatyHeld = false; // an overload that ended inside the hold shows nothing
                ownOverloadSlaveStops++;
                ownOverloadUnconfirmedFresh = 0;
                ownFluxLane.log("predict own-flux overload STOP @tick=" + simTick
                        + (authOvlFell ? " (auth fall edge)" : " (unconfirmed past echo window)"));
            }
            // Advance the window memory AFTER the action ran,
            // on EVERY frame (not only fresh) — the begin above runs on any frame, so a begin that
            // took on a non-fresh frame is remembered before its local overload can expire; authOvl
            // only changes on fresh frames, so the window still closes exactly at the wire's fall.
            ownOvlBegunThisWindow = OverloadWindowLaw.nextBegun(authOvl, ft.isOverloaded(),
                    ownOvlBegunThisWindow);
            if (!ownOvlBegunThisWindow) {
                ownOvlSuppressLogged = false;
            }
            if (ft.isVenting() && !authVent
                    && (authVentFell || ownVentUnconfirmedFresh >= OWN_FLUX_SLAVE_CONFIRM_FRESH)) {
                ft.stopVenting();
                ownVentSlaveStops++;
                ownVentUnconfirmedFresh = 0;
                ownFluxLane.log("predict own-flux vent STOP @tick=" + simTick
                        + (authVentFell ? " (auth fall edge)" : " (unconfirmed past echo window"
                        + " — server refused/never saw the vent)"));
            }
        } catch (Throwable t) {
            // presentation-side apply; never break the watch.
            // logs once (brief-common catch law) — the new band/window code rides inside this try.
            FallbackAnnounce.announceOnce("own-flux-apply", String.valueOf(t));
        }
    }

    /**
     * The OWN-CR pin lane (see {@link PredictKnobConfig#OWN_CR_MODE}); the always-on
     * {@link CrClockCensus} stays the freeze discriminator and the gap meters
     * here stay live under apply. Fresh own-pose CR frames sample the wire-vs-local
     * cr/dep gaps FIRST (the honest divergence meter — pre-pin by construction), latch the wire
     * pair for the census riders, then apply pins {@code setTimeDeployed(wireDep)} +
     * {@code setCurrentCR(wireCr)} — the recon-verified pin pair that moves BOTH readouts
     * (PPT countdown + CR% widget). Wire {@code dep} is the RAW deployed-seconds accumulator
     * and peak stays LOCAL (the HUD subtracts dep from local peak itself); {@code setCurrentCR}
     * re-applies stats only on change, so the idempotent pin is cheap.
     *
     * <p>Quantization guard: the AWSD baseline CR rides a u8 grid (server
     * {@code AwsdSnapshotCodec.fracU8} = round(cr×255), dequant k/255 — widen this guard's
     * grid together with that codec if the tier is ever widened), and the encode is
     * non-injective: server 0.7f encodes to k=179 (f32 product 0.7f×255f is EXACTLY 178.5,
     * rounds up) which dequants to 0.7019608f. Pinning that raw dequant crossed the engine's
     * change-guard ({@code Ship.setCurrentCR} applies stats only on a value CHANGE) and minted
     * a client-only {@code cr_effect} +0.0653604% movement mod (the CR plugin's
     * improve-side slope above the 0.7 threshold) while the server sat exactly ON the zero
     * edge — the measured settled-tick 0.052 su/s parity divergence. So: skip the
     * CR pin whenever the LOCAL value re-encodes to the SAME u8 cell as the wire value — the
     * wire cannot distinguish them, and the local value is the better hypothesis (it came from
     * the exact per-mille deploy seam). Genuine CR movement (decay crosses u8 cells) still
     * pins; residual precision under decay is bounded by the wire grid (≤1/510 CR).
     *
     * <p>The {@code setTimeDeployed}
     * pin now rides {@link OwnCrPinLaw#depPinDue} (0.5 s dead band) — the exact per-frame pin
     * rewound the PPT clock by the snapshot lag; skips count into {@code predict_own_dep_band_skips}.
     */
    private void maintainOwnCr(ShipAPI own, ServerEntityPose ownPose, boolean freshSnapshot,
            long simTick) {
        if (!OWN_CR_METER || own == null || ownPose == null || !freshSnapshot) {
            return;
        }
        try {
            if (!ownPose.hasCr()) {
                ownCrMissing++;
                return;
            }
            float wireCr = ownPose.cr();
            float wireDep = ownPose.crDepSec();
            ownCrWireLast = wireCr;
            ownDepWireLast = wireDep;
            ownCrGapMax = Math.max(ownCrGapMax, Math.abs(own.getCurrentCR() - wireCr));
            ownDepGapMax = Math.max(ownDepGapMax,
                    Math.abs(own.getTimeDeployedForCRReduction() - wireDep));
            if (OWN_CR_APPLY) {
                // The deploy clock was
                // pinned to the exact wire f32 every fresh frame (PPT clock rewound 0.1-0.3 s per
                // snapshot). The local clock is the engine's own (it advances only with a significant
                // enemy in range), so a gap inside
                // OwnCrPinLaw.DEP_BAND_SEC is legit lead: skip (metered); the dep gap meter above
                // stays pre-decision. The CR pin below is untouched.
                if (OwnCrPinLaw.depPinDue(own.getTimeDeployedForCRReduction(), wireDep)) {
                    own.setTimeDeployed(wireDep);
                } else {
                    ownDepBandSkips++;
                }
                // (92): only pin CR across a REAL wire step — same-u8-cell values are
                // wire-indistinguishable and the raw dequant would mint a phantom cr_effect.
                // The step is now a 2-CELL
                // dead-band (OwnCrPinLaw) — a one-cell disagreement is the quantization
                // straddle (local and wire < 1 cell apart on either side of a rounding edge),
                // and pinning it wrote the cell CENTER back, flipping the HUD integer up for
                // <= 1 s; a real drift crosses 2 cells within seconds and still pins.
                int cellGap = OwnCrPinLaw.cellGap(own.getCurrentCR(), wireCr);
                if (cellGap >= OwnCrPinLaw.PIN_DEAD_BAND_CELLS) {
                    own.setCurrentCR(wireCr);
                    ownCrPinFrames++;
                } else if (cellGap == 0) {
                    ownCrQuantSkips++;
                } else {
                    ownCrBandSkips++; // the one-cell straddle the dead-band now absorbs
                }
            }
        } catch (Throwable t) {
            // clock/readout reconcile; never break the watch.
            // logs once (brief-common catch law) — the new dep band rides inside this try.
            FallbackAnnounce.announceOnce("own-cr-apply", String.valueOf(t));
        }
    }
    /** The held "Overloaded!" floaty (an auth rise with no lash arc in the
     *  lookback): a wire LASH arc on the own hull since the rise ⇒ the disruption paint + floaty
     *  (late — the flag beat the arc across a snapshot edge; the own twin of
     *  {@link ArcHitFxLane#maintainRemoteLashOverload}); else the plain floaty once the hold lapses
     *  ({@link ArcHitFxLane#LASH_FLOATY_HOLD_NANOS}); nothing if the overload already ended —
     *  checked FIRST (an arc after the end must not paint a colour nobody sees). */
    private void maintainOwnHeldOverloadFloaty(ShipAPI own, FluxTrackerAPI ft, long simTick) {
        if (!ft.isOverloadedOrVenting()) {
            ownOverloadFloatyHeld = false;
        } else if (ArcHitFxLane.lastLashHitOwnNanos() >= ownOverloadFloatyHeldSince) {
            ownOverloadFloatyHeld = false;
            own.setOverloadColor(VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR_BRIGHT);
            ft.showOverloadFloatyIfNeeded("Phase Field Disruption!",
                    VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR, 4.0F, true);
            ownLashOverloadColored = true;
            ownLashOverloads++;
            ownLashOverloadsLate++;
            ownFluxLane.log("predict own-flux overload lash=PHASE-FIELD-DISRUPTION (arc after the rise) @tick=" + simTick);
        } else if (System.nanoTime() - ownOverloadFloatyHeldSince > ArcHitFxLane.LASH_FLOATY_HOLD_NANOS) {
            ownOverloadFloatyHeld = false;
            ft.showOverloadFloatyIfNeeded();
        }
    }

    /**
     * Pre-match hold (session up, no authoritative frame yet: the server is
     * holding serve for the remaining players). Park the local ship at its spawn pose (zero velocity +
     * per-frame re-pin; untouched it free-runs until the first snapshot teleports it — the recorded
     * "float+teleport") and put up a {@code cc_hold} row saying WHY nothing is happening. Countless by
     * design (ZERO wire additions — the SEAT occupancy lane is decoded-but-unconsumed client-side and
     * SEAT flush-during-hold is unverified; N/M deferred). No explicit clear: an unmaintained status row
     * is engine-removed ~1.25 s after the hold releases (age-gate 1.0 s + 0.25 s fade — C.o age clock +
     * gated fadeOut, status-list advance removes faded-out entries).
     *
     * <p>Replacing the no-feed {@code cc_sim_rate} row while CONNECTED is also the whole
     * HUD-ordering fix, superseding the specced one-shot 8-key prime: the status stack is
     * insertion-ordered with the reorder arg hardcoded false,
     * and the same removal-on-fade means a once-primed unmaintained key loses its slot ~1.25 s into any
     * real hold — the prime cannot deliver its goal. The fed per-frame maintain order (this plugin's readout call order +
     * in-function call order) already IS the canonical order and the sim-rate wall anchor is set
     * same-frame before it, so with no cc_ row inserted ahead of the feed, every path builds the
     * stack canonically and the prime would be dead weight — NOT BUILT.
     */
    private void maintainPreMatchHold() {
        try {
            ShipAPI player = engine.getPlayerShip();
            if (player != null) {
                if (holdParkPose == null) {
                    holdParkPose = new float[] {
                            player.getLocation().x, player.getLocation().y, player.getFacing()};
                    PilotWatchLog.info("predict hold-ux: pre-match hold — ship parked at spawn pose"
                            + " (cc_hold row up; releases at the first authoritative frame)"
                            + (session == null ? " [late-attach hold, no session yet]" : ""));
                }
                player.getLocation().set(holdParkPose[0], holdParkPose[1]);
                player.setFacing(holdParkPose[2]);
                player.getVelocity().set(0f, 0f);
                player.setAngularVelocity(0f);
            }
            // The row says which wait this is — the
            // late-attach hold's dial (still polling, or given up / ended), else the served pre-match hold.
            if (session == null && lateAttachPolling) {
                engine.maintainStatusForPlayerShip("cc_hold", NET_ICON, "CONNECTING TO SERVER",
                        "ship parked; joins this round once the server lets it in", false);
            } else if (session == null) {
                engine.maintainStatusForPlayerShip("cc_hold", NET_ICON, "SERVER CONNECT FAILED - ESC LEAVES",
                        "this round never started", true);
            } else {
                engine.maintainStatusForPlayerShip("cc_hold", NET_ICON, "WAITING FOR PLAYERS",
                        "ship parked until match start", false);
            }
        } catch (Throwable ignore) {
            // HUD readout is presentation-side; never break the watch on a status hiccup
        }
    }

    /**
     * Reset the sim-rate anchor + the STEP-5 per-frame clock discriminator, so a fed
     * measurement that follows a no-feed control window starts clean (re-anchored at the first
     * authoritative frame by the existing anchor block). DELIBERATELY NOT reset: the {@link ClockServoLaw}
     * state (window + mult) — the plant (fps cap + scheduler) is the same machine across the
     * no-feed→fed transition, so the fed window starts PRE-LOCKED with the learned mult (I.7's priming
     * for free) instead of re-paying warmup at the raw deficit; {@code noFeedFrames} also survives
     * (label-only).
     */
    private void resetClockMeter() {
        combatWallStartNanos = Long.MIN_VALUE;
        clientSimTimeSec = 0d;
        firstServerSimTick = Long.MIN_VALUE;
        lastFrameNanos = Long.MIN_VALUE;
        clockFramesMeasured = 0L;
        sumAmountSec = 0d;
        sumRealDtSec = 0d;
        pauseGapFrames = 0L;
        maxAmountSec = 0f;
        // I.14: re-arm the servo trace + post-lock anchor (clientSimTimeSec and the wall anchor just
        // reset; the servo window deliberately SURVIVES — resetTrace() leaves the law untouched, so
        // the anchor re-arms on the next locked frame, i.e. immediately, giving C_lock a clean
        // post-reset base instead of a stale clientSim offset).
        clockServo.resetTrace();
        slopeSamples.clear();
        netRateSamples.clear(); // Same re-anchor discipline as the slope buffer
    }

    /** The K16 D3 hard-snap seam — {@link TeleportJumpLane} re-enters the plugin's
     *  {@link FoeStateLaneCoordinator#reconcilePosition} (forced: glide bypassed) at the authoritative dest. */
    void jumpSnap(ShipAPI body, float destX, float destY) {
        foeState.reconcilePosition(body, destX, destY, 1.0f, true);
    }

    /** KEEP = isEntityInPlay (pending adds count:
     *  the onOwnFireBirth dedup holds) OR still in getProjectiles (a pending-REMOVAL body the scan
     *  still sees would re-add as a false autonomous) — nothing the scan's consume can grade is swept. */
    private void sweepProjSets() {
        long t0 = System.nanoTime();
        try {
            Set<Object> live = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            live.addAll(engine.getProjectiles());
            java.util.function.Predicate<Object> gone = p -> !live.contains(p)
                    && !(p instanceof CombatEntityAPI && engine.isEntityInPlay((CombatEntityAPI) p));
            int pre = presentedFoeProj.size();
            presentedFoeProj.removeIf(gone);
            projSweptPresented += pre - presentedFoeProj.size();
            pre += seenOwnProj.size() + seenReplayShipProj.size();
            seenOwnProj.removeIf(gone); seenReplayShipProj.removeIf(gone);
            projSweptTotal += pre - presentedFoeProj.size() - seenOwnProj.size() - seenReplayShipProj.size();
        } catch (Throwable t) {
            if (projSweepFails++ == 0) {
                PilotWatchLog.info("predict proj-set sweep FAILED (once; counted in proj_sweep_fails): " + t);
            }
        }
        projSweepLastUs = (System.nanoTime() - t0) / 1000L;
    }

    /**
     * The push twin of the scan hit body: {@link ClientFireBirthSink}
     * delivers every covered-spec discharge birth here AT the engine's own onFire dispatch (true
     * muzzle instant, same-frame-death births included — both structurally invisible to the
     * scan's first-sighting anchor). Guards in scan order, then EXACTLY the scan's hit body;
     * {@code seenOwnProj.add} is the cross-lane dedup (whichever lane sees a projectile first
     * consumes it — the scan skips callback-consumed bolts and vice versa). The presentedOwnProj
     * check is structurally unreachable (the presenter's {@code spawnProjectile} bypasses the
     * weapon cycle, so no onFire fires for presented spawns) — kept as a
     * belt+suspenders, expect 0.
     */
    void onOwnFireBirth(DamagingProjectileAPI p, WeaponAPI w) {
        if (ownShip == null || p == null || w == null) {
            return; // no bound own ship / degenerate callback — falls to the scan lane
        }
        if (p.getSource() != ownShip) {
            fireDeclBirthForeign++; // every covered foe/remote local birth lands here; counter only
            return;
        }
        if (fireLanes.presentedOwnProj.remove(p)) {
            fireDeclBirthPresentedSkip++;
            return;
        }
        if (!seenOwnProj.add(p)) {
            return; // scan already consumed it (dedup)
        }
        fireDeclBirthSeen++;
        localOwnDischarges++;
        driveLock.onOwnDischarge(false); // The kit-side in-lock witness (birth lane)
        dmgWitness.onOwnDischarge(lastSnapshotSimTick, p, ownShip); // lane 1, the callback twin
        if (EVENT_REPLAY_ENABLED) {
            fireLanes.recordLocalDischarge(lastSnapshotSimTick, p);
        }
        edgeEvents.declareFireDischarge(p);
    }

    /**
     * Client send half, NET-thread drain: send every pending declaration as a {@code PDCL}
     * frame, called ONLY immediately after {@code session.sendIntent(...)} in the ping-pong loop —
     * the ordering contract the server's demux depends on (PDCL frames sit behind this tick's PINT;
     * the between-rounds {@code awaitReentry} read is not demuxed, and riding strictly behind an
     * intent means no PDCL can ever be emitted between rounds). A send failure propagates and
     * fail-closes the net loop exactly like a failed intent send.
     */
    private void sendPendingFireDeclarations() throws IOException {
        while (true) {
            long[] d;
            synchronized (pendingFireDecls) {
                d = pendingFireDecls.pollFirst();
            }
            if (d == null) {
                return;
            }
            float aim = Float.intBitsToFloat((int) d[2]);
            float wx = Float.intBitsToFloat((int) d[3]); // aim world point at discharge (PDCL v2)
            float wy = Float.intBitsToFloat((int) d[4]);
            int mount = (int) d[5]; // Mount identity at discharge (-1 = unknown)
            float mx = Float.intBitsToFloat((int) d[6]); // committed shot's ray (PDCL v4)
            float my = Float.intBitsToFloat((int) d[7]);
            float mb = Float.intBitsToFloat((int) d[8]);
            float ms = Float.intBitsToFloat((int) d[9]);
            session.sendFireDeclaration((int) d[0], d[1], aim, wx, wy, mount, mx, my, mb, ms);
            fireDeclSent++;
            if (fireDeclSent <= FIRE_DECL_LOG_MAX) {
                PilotWatchLog.info("predict fire-decl sent #" + d[0] + ": clientTick=" + d[1]
                        + " aimDeg=" + aim + " aimWorld=(" + wx + "," + wy + ") mount=" + mount
                        + (fireDeclSent == FIRE_DECL_LOG_MAX ? " [log cap reached; fire_decl_sent continues]" : ""));
            }
        }
    }

    /**
     * Send half: drain every pending {@code SNNK} NACK onto the reliable CTRL
     * lane, called ONLY immediately after {@code sendPendingFireDeclarations()} at both send
     * sites — the same strictly-behind-an-intent slot the PDCL lane rides (single sender thread
     * per mode preserves ordering; the CTRL lane is demuxed by magic server-side). A send
     * failure propagates and fail-closes the loop exactly like a failed intent send.
     */
    private void sendPendingAwsdNacks() throws IOException {
        while (true) {
            int[] n;
            synchronized (pendingAwsdNacks) {
                n = pendingAwsdNacks.pollFirst();
            }
            if (n == null) {
                return;
            }
            session.sendAwsdNack(n[0], n[1]);
            awsdNackTx++;
            if (awsdNackTx <= AWSD_NACK_LOG_MAX) {
                PilotWatchLog.info("predict awsd-nack sent: entityId=" + n[0]
                        + " rejectedEpoch=" + n[1]
                        + (awsdNackTx == AWSD_NACK_LOG_MAX ? " [log cap reached; awsd_nack_tx continues]" : ""));
            }
        }
    }

    /** Server sim-seconds elapsed since the anchor frame (simTick advances one fixed step per tick
     * at the {@link ServerTickRate} — 1/60 s at the default). */
    private double serverSimSeconds() {
        if (firstServerSimTick == Long.MIN_VALUE || lastSnapshotSimTick == Long.MIN_VALUE) {
            return 0d;
        }
        return (lastSnapshotSimTick - firstServerSimTick) / (double) ServerTickRate.ticksPerSecond();
    }

    /** Format a speed scalar for the HUD / WATCH log (rounded units-per-second). */
    static String fmtSpeed(float s) {
        return Math.round(s) + " u/s";
    }

    /** Two-decimal format for the sim-rate readout (ROOT locale so the WATCH log is grep-stable). */
    static String fmt2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** One-decimal format for the tick/net rate readout, ROOT locale (grep-stable). */
    static String fmt1(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** Four-decimal format for sub-frame seconds (e.g. the empirical per-frame cap), ROOT locale. */
    private static String fmt4(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    /** Format a flux reading for the HUD / WATCH log: {@code level% (curr/max)}, or {@code n/a} when unknown. */
    static String fmtFlux(float level, float curr, float max) {
        if (level < 0f) {
            return "n/a";
        }
        return Math.round(level * 100f) + "% (" + Math.round(curr) + "/" + Math.round(max) + ")";
    }

    /** Emit the own-ship speed readout (local vs server-authoritative) + the running max
     *  delta to the WATCH log, so gap ② (a server-unmodelled speed mechanic) is MEASURED, not guessed. */
    void logSpeedMeter(String where) {
        PilotWatchLog.info("predict fidelity speed-meter [" + where + "]: localSpeed=" + fmtSpeed(lastLocalSpeed)
                + " serverSpeed=" + fmtSpeed(lastServerSpeed)
                + " delta=" + fmtSpeed(Math.abs(lastLocalSpeed - lastServerSpeed))
                + " maxDelta=" + fmtSpeed(maxSpeedDeltaSeen)
                // The step-rebase summary counters — events fired, the largest authoritative
                // per-tick velocity step seen (instrument live even when the flag is off), and the flag state.
                + " | stepRebase=" + (STEP_REBASE_ENABLED ? "ON" : "off")
                + " events=" + stepRebaseEvents
                + " maxAuthStep=" + fmtSpeed(maxAuthVelStepSeen) + "/tick"
                // Max |local−auth| velocity VECTOR gap while the hull-hp
                // under-fire latch was live (report-only; an adjudication input).
                + " own_underfire_velgap_max=" + fmtSpeed(underFireVelGapMax)
                // Burst instrument — max gap while the own-system burst latch was live
                // (+2 s tail), windows seen, and STEP-16 rebases inside a live window (Move-3 bar (a);
                // report-only — thresholds untouched pre-measure).
                + " own_burst_velgap_max=" + fmtSpeed(burstVelGapMax)
                + " own_burst_windows=" + burstWindows
                + " own_burst_step16=" + burstStepRebaseEvents
                // burst-window exemption meters (by law the meters ship
                // with the lane): knob state, in-window STEP-16 writes suppressed, frames glided
                // at the weak tether, frames a nonzero escape streak held frozen.
                + " own_burst_exempt=" + (BURST_EXEMPT_ENABLED ? "ON" : "off")
                + " own_burst_step16_suppressed=" + burstStep16Suppressed
                + " own_burst_relaxed_frames=" + burstRelaxedFrames
                + " own_burst_escape_frozen=" + burstEscapeFreezes
                // own-velocity rebase-and-hold meters (by law the
                // meters ship with the lane): knob state, engage edges (meter mode:
                // would-engage), engaged fresh frames, u/s applied, max gap at an engage edge,
                // cap-bound writes (separates spread engages from one-write closes).
                + " own_velrecon=" + OWN_VELRECON_MODE
                + " own_velrecon_engages=" + velReconEngages
                + " own_velrecon_frames=" + velReconHoldFrames
                + " own_velrecon_su=" + fmtSpeed(velReconSu)
                + " own_velrecon_max=" + fmtSpeed(velReconGapMax)
                + " own_velrecon_capped=" + velReconCapped
                // Flux + system folded in, tracked like velocity —
                // local vs server flux level + the running max flux-level delta, plus local sys.
                + " | localFlux=" + fmtFlux(lastLocalFluxLevel, lastLocalCurrFlux, lastLocalMaxFlux)
                + " serverFlux=" + fmtFlux(lastServerFluxLevel, lastServerCurrFlux, lastServerMaxFlux)
                + " maxFluxDelta=" + (maxFluxDeltaSeen > 0f ? Math.round(maxFluxDeltaSeen * 100f) + "%" : "0%")
                // flux_gap: max/mean |local−server| flux LEVEL
                // over all fresh snapshot samples — the probe whose reading decides the flux-reconcile sequel.
                + " flux_gap_max=" + fmt1(maxFluxDeltaSeen * 100f) + "%"
                + " flux_gap_mean=" + (fluxGapSamples > 0
                        ? fmt1((fluxGapSum / fluxGapSamples) * 100.0) + "%" : "n/a")
                + " flux_gap_samples=" + fluxGapSamples
                + " | localSys=" + lastLocalSysState
                // TIMESTAMPED sim-rate: the dual-side clock measurement. wall = real
                // seconds since the anchor frame; clientSim = Σ local combat dt; serverSim = simTick/60; the
                // rates are sim-sec/real-sec; serverOverClient quantifies "the server runs faster by X" (~1.27).
                + " | " + simRateSummary()
                // Ping + measured tick/net rates — the WATCH rider greps this beside
                // the HUD's live lines (ping n/a on TCP by design; see PredictSession).
                + " | ping=" + (session != null && !Double.isNaN(session.netPingMillis())
                        ? Math.round(session.netPingMillis()) + "ms" : "n/a")
                + " tickNet10s=[" + ownReadout.tickRateSummary() + "]"
                // cap-clamped hits whose presentation was restored (re-watch bar: >0 on capped hits).
                + " fx_restores=" + hullCap.fxRestores + hullCap.rogTokens() // D1
                // Meter (a) premature local kills per cap class +
                // the bound's resolve faults — here for the same verify-hull-ledger reason as the note below.
                // resolve_faults is JVM-cumulative (a static; the three counters are per plugin instance)
                // and also counts the witness's dsig-census shooterOf throws.
                + " predict_remote_local_kill_premature=" + hullCap.localKillPrematureRemote
                + " predict_foe_local_kill_premature=" + hullCap.localKillPrematureFoe
                + " predict_own_local_kill_premature=" + hullCap.localKillPrematureOwn
                + " predict_hull_cap_resolve_faults=" + HullCapBound.resolveFaults
                // The roster-append round-epoch meters (wrap bar
                // per seat: deferred rows >= 6 and frames >= 2, failopen 0).
                + " predict_roster_append_reset_deferred_rows=" + rapp.rowsDeferred()
                + " predict_roster_append_reset_deferred_frames=" + rapp.framesDeferred()
                + " predict_roster_append_reset_dropped_rows=" + rapp.rowsDroppedAtReset()
                // r1 — old-epoch SEAT rows folded before the own read.
                + " predict_roster_append_reset_folded_rows=" + rapp.rowsFoldedAtReset()
                + " predict_roster_append_reset_folded_frames=" + rapp.framesFoldedAtReset()
                + " fold_skipped_mount=" + matchResetFoldSkippedMountSeats
                + " predict_roster_append_epoch_failopen=" + rapp.epochFailOpens()
                // These meters — posthumous (late) dead
                // sources, classify throws, door-det expectations armed / acked.
                // Here (not the FoeHullReconcileLane line) so verify-hull-ledger's source compile of
                // that lane never needs these PRCP fields in the built jar.
                + " predict_dead_src_late=" + deadSrcLate
                + " predict_dead_src_classify_fail=" + deadSrcClassifyFail
                + " predict_door_det_armed=" + doorDet.armed
                + " predict_door_det_acked=" + doorDet.matched);
        edgeEvents.logEventReplayMeter(where); // B3: its own line, only when cc.predict.eventReplay is ON
        logEngineStateMeter(where); // Its own line, only when the engine-state lane is on
        logWeaponStateMeter(where); // weapon-state + own-engine lines, when the lane is on
        fireLanes.logOwnFireLockMeter(where); // Own fire-lock line, when the lane is on
        fireLanes.logOwnBeamCycleMeter(where); // Own beam-cycle line + census, when on
        fireLanes.logDischargeReconcileMeter(where); // discharge-reconcile line, when the lane is on
        fireLanes.logAutofireLeadMeter(where); // Autofire lead-error line, always-on
        logFoeSystemStateMeter(where); // Foe system-window line, when the lane is on
        logFoeManeuverMeter(where); // Foe maneuver-command line, when the lane is on
        logFoeFluxMeter(where); // Foe flux line, when the lane is on
        logFoeAutofireMeter(where); // Foe autofire line, when the lane is on
        logFoeSelectionMeter(where); // Foe selected-group line, when the lane is on
        logSameSideHoldMeter(where); // same-side fire-only hold line (always on)
        logFoeShieldMeter(where); // Foe shield + own-agreement line, when the lane is on
        logFoeCloakMeter(where); // Foe phase-cloak line, when the lane is on
        hullLane.logFoeHullReconcileMeter(where); // Foe hull ledger/reconcile line, when the lane meters
        ownDetLane.logMeter(where); // Own det-damage counter line, when the lane meters
        remoteAim.logMeter(where); // AIMP remote-aim fold/apply line, when the lane is on
        logFoeFluxFlagsMeter(where); // Foe overload/vent flag line, when the lane is on
        logOwnFluxMeter(where); // Own flux pin + slave + mask-guard line, when on
        logOwnCrMeter(where); // own CR clock pin + gap line, when the lane is on
        logTimeMultScrubMeter(where); // engine-global timeMult scrub correctness meter
        logVelocityGlideMeter(where); // Its own line, emitted with the flag state
    }

    /** The velocity-glide counters, one greppable line per meter flush. */
    private void logVelocityGlideMeter(String where) {
        PilotWatchLog.info("predict velocity-glide [" + where + "]: "
                + (VELOCITY_GLIDE_ENABLED ? "ON" : "off")
                + " glideFrames=" + glideFrames
                + " engagements=" + glideEngagements
                + " maxGapGlided=" + fmtSpeed(maxGlideGapSeen)
                // Item ④: the above-cap persistence escape counters (see maintainGlideEscape).
                + " escapes=" + glideEscapeEvents
                + " maxGapEscaped=" + fmtSpeed(maxGapEscaped));
    }

    /**
     * The foe system-state lane counters, one greppable line per meter flush (the
     * counter-line discipline). Silent when {@code cc.predict.foeSystemState=off}.
     */
    private void logFoeSystemStateMeter(String where) {
        if (!FOE_SYSTEM_METER) {
            return;
        }
        PilotWatchLog.info("predict v3 foe-system-meter [" + where + "]:"
                + " mode=" + FOE_SYSTEM_STATE_MODE
                + " predict_foe_system_frames=" + foeSystemLane.framesCarried
                + " predict_foe_system_unknown=" + foeSystemLane.framesMissing
                + " predict_foe_system_agree=" + foeSystemLane.agree
                + " predict_foe_system_disagree=" + foeSystemLane.disagree
                + " predict_foe_system_apply_presses=" + foeSystemApplyPresses
                + foeState.systemGuardMeterTokens() // the system-guard tokens
                + " predict_foe_system_cancel_presses=" + foeSystemCancelPresses
                + " predict_foe_system_seeds=" + foeSystemSeeds
                + " predict_foe_system_log_entities=" + foeSystemLogBudget.keys() // per-entity log budget keys
                + " predict_foe_system_log_capped=" + foeSystemLogBudget.capped());
    }

    /**
     * The foe maneuver-command lane counters, one greppable line per meter flush (the
     * counter-line discipline). Silent when {@code cc.predict.foeManeuver=off}.
     */
    private void logFoeManeuverMeter(String where) {
        if (!FOE_MANEUVER_METER) {
            return;
        }
        PilotWatchLog.info("predict v7 foe-maneuver-meter [" + where + "]:"
                + " mode=" + FOE_MANEUVER_MODE
                + " predict_foe_maneuver_carried=" + foeManeuverLane.framesCarried
                + " predict_foe_maneuver_missing=" + foeManeuverLane.framesMissing
                + " predict_foe_maneuver_agree=" + foeManeuverLane.agree
                + " predict_foe_maneuver_disagree=" + foeManeuverLane.disagree
                + " predict_foe_maneuver_apply_commands=" + foeManeuverApplyCommands);
    }

    /**
     * The foe flux lane counters, one greppable line per meter flush (the
     * counter-line discipline). Silent when {@code cc.predict.foeFlux=off}.
     */
    private void logFoeFluxMeter(String where) {
        if (!FOE_FLUX_METER) {
            return;
        }
        PilotWatchLog.info("predict foe-flux-meter [" + where + "]:"
                + " mode=" + FOE_FLUX_MODE
                + " predict_foe_flux_carried=" + foeFluxLane.framesCarried
                + " predict_foe_flux_missing=" + foeFluxLane.framesMissing
                + " predict_foe_flux_max_level_delta=" + foeFluxMaxDelta
                + " predict_foe_flux_mean_level_delta="
                + (foeFluxSamples > 0 ? String.valueOf(foeFluxSum / foeFluxSamples) : "n/a")
                + " predict_foe_flux_apply_frames=" + foeFluxApplyFrames
                + foeState.fluxPinMeterTokens() // the flux-pin guard tokens
                + " predict_foe_flux_boost_clock_zeros=" + foeFluxBoostClockZeros
                + " predict_foe_cr_pin_frames=" + foeCrPinFrames // rides this knob
                + " predict_foe_cr_quant_skips=" + foeCrQuantSkips
                + " predict_foe_cr_missing=" + foeCrMissing);
    }

    /**
     * The foe autofire lane counters, one greppable line per meter flush (the
     * counter-line discipline). Silent when {@code cc.predict.foeAutofire=off}.
     */
    private void logFoeAutofireMeter(String where) {
        if (!FOE_AUTOFIRE_METER) {
            return;
        }
        PilotWatchLog.info("predict foe-autofire-meter [" + where + "]:"
                + " mode=" + FOE_AUTOFIRE_MODE
                + " predict_foe_autofire_carried=" + foeAutofireLane.framesCarried
                + " predict_foe_autofire_missing=" + foeAutofireLane.framesMissing
                + " predict_foe_autofire_agree=" + foeAutofireLane.agree
                + " predict_foe_autofire_disagree=" + foeAutofireLane.disagree
                + " predict_foe_autofire_apply_toggles=" + foeAutofireApplyToggles);
    }

    /**
     * The foe selected-group lane counters, one greppable line per meter flush
     * (the counter-line discipline). Silent when {@code cc.predict.foeSelection=off}.
     */
    private void logFoeSelectionMeter(String where) {
        if (!FOE_SELECTION_METER) {
            return;
        }
        PilotWatchLog.info("predict foe-selection-meter [" + where + "]:"
                + " mode=" + FOE_SELECTION_MODE
                + " predict_foe_selection_carried=" + foeSelectionLane.framesCarried
                + " predict_foe_selection_missing=" + foeSelectionLane.framesMissing
                + " predict_foe_selection_agree=" + foeSelectionLane.agree
                + " predict_foe_selection_disagree=" + foeSelectionLane.disagree
                + " predict_foe_selection_apply_commands=" + foeSelectionApplyCommands);
    }

    /**
     * The same-side fire-only hold counters (always-on lane, no mode knob), one
     * greppable line per meter flush (the counter-line discipline). Truncations ≈ leak
     * pressure: each is a native burst the hold cut after ≤1 rendered frame; the
     * {@code seenReplayShipProj} weapon-backed census stays THE autonomous-discharge grade.
     * The slew riders grade the (74) fix itself: frozen (defect) ⇒ moved ≈ 0;
     * native slew alive ⇒ moved GROWS while the census stays 0. maxdelta is since-last-flush.
     */
    private void logSameSideHoldMeter(String where) {
        PilotWatchLog.info("predict same-side-hold-meter [" + where + "]:"
                + " same_side_held_weapon_frames=" + sameSideHeldWeaponFrames
                + " same_side_stopfire_truncations=" + sameSideStopFireTruncations
                + " same_side_mount_moved_frames=" + sameSideMountMovedFrames
                + " same_side_mount_maxdelta=" + fmt4(sameSideMountMaxDelta)
                // The newer keys ride AFTER the grader's four pinned keys (append-safe contract —
                // grade-holdmeter matches the prefix unanchored; never insert between them).
                + " same_side_hold_mode=" + SAME_SIDE_HOLD_MODE
                + sameSideAim.meterTokens());
        sameSideMountMaxDelta = 0f; // Maxdelta reads per-flush-window, not per-run
    }

    /**
     * The foe shield lane counters + the own-ship agreement rider, one greppable line
     * per meter flush (the counter-line discipline). Silent when
     * {@code cc.predict.foeShield=off}.
     */
    private void logFoeShieldMeter(String where) {
        if (!FOE_SHIELD_METER) {
            return;
        }
        PilotWatchLog.info("predict foe-shield-meter [" + where + "]:"
                + " mode=" + FOE_SHIELD_MODE
                + " predict_foe_shield_carried=" + foeShieldLane.framesCarried
                + " predict_foe_shield_missing=" + foeShieldLane.framesMissing
                + " predict_foe_shield_agree=" + foeShieldLane.agree
                + " predict_foe_shield_disagree=" + foeShieldLane.disagree
                + " predict_foe_shield_apply_commands=" + foeShieldApplyCommands
                + " predict_foe_shield_facing_forced=" + foeShieldFacingForced
                + " press_shadow_sampled=" + pressShadow.sampledCount()
                + " own_shield_agree=" + ownShieldAgree
                + " own_shield_disagree=" + ownShieldDisagree);
    }

    /**
     * The foe phase-cloak lane counters, one greppable line per meter flush
     * (the counter-line discipline). Silent when {@code cc.predict.foeCloak=off}.
     */
    private void logFoeCloakMeter(String where) {
        if (!FOE_CLOAK_METER) {
            return;
        }
        PilotWatchLog.info("predict v10 foe-cloak-meter [" + where + "]:"
                + " mode=" + FOE_CLOAK_MODE
                + " predict_foe_cloak_carried=" + foeCloakLane.framesCarried
                + " predict_foe_cloak_missing=" + foeCloakLane.framesMissing
                + " predict_foe_cloak_agree=" + foeCloakLane.agree
                + " predict_foe_cloak_disagree=" + foeCloakLane.disagree
                + " predict_foe_cloak_apply_toggles=" + foeCloakApplyToggles
                + " predict_foe_cloak_seeds=" + foeCloakSeeds
                // keyframe repairs skipped as wire lag
                + " predict_foe_cloak_kf_repair_lag_skips=" + foeState.cloak.cloakKfRepairLagSkips
                + " predict_foe_cloak_seed_gate_skips=" + foeCloakSeedPhaseGateSkips
                + " predict_foe_cloak_bit_edge_applies=" + foeCloakBitEdgeApplies
                + " predict_foe_cloak_held_retries=" + foeCloakHeldRetries
                + " own_cloak_agree=" + ownCloakAgree
                + " own_cloak_disagree=" + ownCloakDisagree
                + " own_cloak_disagree_local_on=" + ownCloakDisagreeLocalOn
                + " own_cloak_disagree_auth_on=" + ownCloakDisagreeAuthOn
                + " own_cloak_mode=" + OWN_CLOAK_MODE
                + " own_cloak_forces=" + ownCloakForces);
    }

    /**
     * The foe flux-flags lane counters, one greppable line per meter flush
     * (the counter-line discipline). Silent when {@code cc.predict.foeFluxFlags=off}.
     */
    private void logFoeFluxFlagsMeter(String where) {
        if (!FOE_FLUXFLAGS_METER) {
            return;
        }
        PilotWatchLog.info("predict foe-fluxflags-meter [" + where + "]:"
                + " mode=" + FOE_FLUXFLAGS_MODE
                + " predict_foe_fluxflags_carried=" + foeFluxFlagsLane.framesCarried
                + " predict_foe_fluxflags_missing=" + foeFluxFlagsLane.framesMissing
                + " predict_foe_fluxflags_agree=" + foeFluxFlagsLane.agree
                + " predict_foe_fluxflags_disagree=" + foeFluxFlagsLane.disagree
                + " predict_foe_fluxflags_overload_begins=" + foeFluxFlagsOverloadBegins
                + " predict_foe_fluxflags_overload_stops=" + foeFluxFlagsOverloadStops
                + " predict_foe_fluxflags_vent_begins=" + foeFluxFlagsVentBegins
                + " predict_foe_fluxflags_vent_stops=" + foeFluxFlagsVentStops
                + " predict_foe_fluxflags_vent_restart_suppressed=" + foeFluxFlagsVentRestartSuppressed
                + foeState.fluxFlagsGuardMeterTokens() // the foe flux-flags guard tokens
                + " predict_foe_fluxflags_wireacked_restores=" + replicaBody.wireAckedFluxRestores
                + " predict_foe_fluxflags_wireacked_overload_residue=" + replicaBody.wireAckedOverloadResidue
                // The wire-acked ARMOR restore (hits, Σ hp, faults)
                + " predict_foe_wire_acked_armor_restored=" + replicaBody.wireAckedArmorRestored
                + " predict_foe_wire_acked_armor_restored_hp=" + Math.round(replicaBody.wireAckedArmorRestoredHp)
                + " predict_foe_wire_acked_armor_restore_faults=" + replicaBody.wireAckedArmorFaults
                + " predict_foe_wire_acked_armor_nested=" + replicaBody.wireAckedArmorNested // nested snapshot calls, not faults
                + " predict_foe_fluxflags_vent_tail_rows_dropped=" + foeFluxFlagsVentTailRowsDropped);
    }

    /**
     * The own-flux lane counters (pin + echo-gated slaves + the
     * lockout-mask guard), one greppable line per meter flush. Silent when
     * {@code cc.predict.ownFlux=off}.
     */
    private void logOwnFluxMeter(String where) {
        if (!OWN_FLUX_METER) {
            return;
        }
        PilotWatchLog.info("predict own-flux-meter [" + where + "]:"
                + " mode=" + OWN_FLUX_MODE
                + " predict_own_flux_pin_frames=" + ownFluxPinFrames
                + " predict_own_flux_boost_clock_zeros=" + ownFluxBoostClockZeros
                + " own_overload_agree=" + ownOverloadAgree
                + " own_overload_disagree=" + ownOverloadDisagree
                + " own_vent_agree=" + ownVentAgree
                + " own_vent_disagree=" + ownVentDisagree
                + " predict_own_flux_overload_slave_begins=" + ownOverloadSlaveBegins
                + " predict_own_flux_lash_overloads=" + ownLashOverloads
                + " predict_own_flux_lash_overloads_late=" + ownLashOverloadsLate //
                + " predict_own_flux_lash_unphased=" + ownLashUnphasedSkips // gate refusals
                + " predict_own_flux_overload_slave_stops=" + ownOverloadSlaveStops
                + " predict_own_flux_vent_slave_stops=" + ownVentSlaveStops
                + " predict_own_flux_lockout_mask_holds=" + ownLockoutMaskHolds
                // The flux pin-band and overload-restart guard meters.
                + " predict_own_flux_pin_band_skips=" + ownFluxPinBandSkips
                + " predict_own_flux_gap_max_frac=" + String.format(java.util.Locale.ROOT, "%.4f", ownFluxGapMaxFrac)
                + " predict_own_flux_gap_mean_frac=" + String.format(java.util.Locale.ROOT, "%.4f",
                        ownFluxGapSamples == 0L ? 0.0 : ownFluxGapSumFrac / ownFluxGapSamples)
                + " predict_own_overload_restart_suppressed=" + ownOverloadRestartSuppressed);
    }

    /**
     * The own-CR lane counters (gap meters stay live under apply),
     * one greppable line per meter flush. Silent when {@code cc.predict.ownCr=off}.
     */
    private void logOwnCrMeter(String where) {
        if (!OWN_CR_METER) {
            return;
        }
        PilotWatchLog.info("predict own-cr-meter [" + where + "]:"
                + " mode=" + OWN_CR_MODE
                + " predict_own_cr_pin_frames=" + ownCrPinFrames
                + " predict_own_cr_quant_skips=" + ownCrQuantSkips // (92) same-u8-cell skips
                + " predict_own_cr_band_skips=" + ownCrBandSkips // one-cell skips
                + " predict_own_cr_gap_max=" + fmt1(ownCrGapMax * 100f) + "%"
                + " predict_own_dep_gap_max=" + fmt1(ownDepGapMax)
                + " predict_own_dep_band_skips=" + ownDepBandSkips // dep dead-band skips
                + " predict_own_cr_missing=" + ownCrMissing);
    }

    /**
     * The engine-global timeMult scrub counter, one greppable line per meter
     * flush. Knobless by design: the scrub is a correctness LAW (see {@link ClockServoLane#scrubEngineTimeMult})
     * and its counter doubles as the free correctness meter — foe-only combats expect 0.
     */
    private void logTimeMultScrubMeter(String where) {
        PilotWatchLog.info("predict engine-timemult-scrub [" + where + "]:"
                + " predict_time_scrub_foreign_ids=" + clockServo.foreignScrubCount());
    }

    /**
     * The foe engine-state lane counters, one greppable line per meter flush (the
     * counter-line discipline). Silent when {@code cc.predict.foeEngineState=off}.
     */
    private void logEngineStateMeter(String where) {
        if (!FOE_ENGINE_METER) {
            return;
        }
        PilotWatchLog.info("predict v6 engine-meter [" + where + "]:"
                + " mode=" + FOE_ENGINE_STATE_MODE
                + " predict_engine_state_carried=" + foeEngineLane.framesCarried
                + " predict_engine_state_missing=" + foeEngineLane.framesMissing
                + " predict_engine_state_agree=" + foeEngineLane.agree
                + " predict_engine_state_disagree=" + foeEngineLane.disagree
                + " predict_engine_state_apply_disables=" + engineStateApplyDisables
                + " predict_engine_state_apply_repairs=" + engineStateApplyRepairs
                + " engine_state_repair_ramp_deferred=" + engineStateRepairRampDeferred
                + " engine_state_mask_fork_ticks=" + engineStateMaskForkTicks
                + " engine_state_mask_fork_ent_ticks=" + engineStateMaskForkEntTicks
                + compDmgShield.meterTokens()
                + godMirror.meterTokens() // Cumulative, silent when foeEngineState=off
                + FighterFlameoutClassLaw.meterTokens(this)); // the fighter flameout-class tokens
    }

    /**
     * The weapon-state lane counters + the own-engine lane, two greppable
     * lines per meter flush (the counter-line discipline), each silent when its own
     * knob is {@code off} (split gates: the own-engine line rides
     * {@code cc.predict.ownEngineState}, no longer the weapon-state knob).
     */
    private void logWeaponStateMeter(String where) {
        if (WEAPON_STATE_METER) {
            PilotWatchLog.info("predict v4 weapon-meter [" + where + "]:"
                    + " mode=" + WEAPON_STATE_MODE
                    + " predict_weapon_state_carried=" + weaponLane.framesCarried
                    + " predict_weapon_state_missing=" + weaponLane.framesMissing
                    + " predict_weapon_state_missing_mountless=" + weaponMissingMountless
                    + " predict_weapon_state_missing_armed=" + weaponMissingArmed
                    + " predict_weapon_state_mount_absent_local=" + weaponMountAbsentLocal
                    + " predict_weapon_state_agree=" + weaponLane.agree
                    + " predict_weapon_state_disagree=" + weaponLane.disagree
                    + " predict_weapon_state_apply_disables=" + weaponStateApplyDisables
                    + " predict_weapon_state_apply_repairs=" + weaponStateApplyRepairs
                    + " predict_weapon_state_perm_skips=" + weaponStatePermSkips
                    + " weapon_state_disable_deferred=" + fireLanes.weaponStateDisableDeferred
                    + " predict_weapon_state_dweller_skip_frames=" + weaponStateDwellerSkipFrames
                    + " predict_weapon_state_force_disabled_skips=" + weaponStateForceDisabledSkips
                    + weaponRedisable.meterTokens()
                    + ownWeaponStall.meterTokens()
                    + engineRedisable.meterTokens() //
                    + foeState.engineGuardMeterTokens() // the engine guard tokens
                    + ownEngineStall.meterTokens()  //
                    + swarmSpriteGate.meterTokens()
                    + constructionFx.meterTokens()
                    + swarmCloudProbe.meterTokens()
                    + reclamationFx.meterTokens()
                    + energyLashFx.meterTokens()
                    + moteAttractorFx.meterTokens()
                    + ownMoteCensus.meterTokens()
                    + dwellerCensus.meterTokens()
                    + moteReplica.meterTokens()
                    + weaponGlow.meterTokens());
        }
        if (OWN_ENGINE_METER) {
            PilotWatchLog.info("predict own-engine-meter [" + where + "]:"
                    + " mode=" + OWN_ENGINE_STATE_MODE
                    + " predict_own_engine_carried=" + ownEngineFramesCarried
                    + " predict_own_engine_missing=" + ownEngineFramesMissing
                    + " predict_own_engine_agree=" + ownEngineAgree
                    + " predict_own_engine_disagree=" + ownEngineDisagree
                    + " predict_own_engine_apply_disables=" + ownEngineApplyDisables
                    + " predict_own_engine_apply_repairs=" + ownEngineApplyRepairs
                    + " own_engine_disable_deferred=" + ownEngineDisableDeferred
                    + " own_engine_repair_ramp_deferred=" + ownEngineRepairRampDeferred
                    + " own_engine_streaks=" + ownEngineStreaks
                    + " own_engine_max_streak_ticks=" + ownEngineMaxStreakTicks
                    + " own_engine_open_streak_ticks=" + (ownEngineStreakStartTick == Long.MIN_VALUE
                            ? 0L : Math.max(0L, ownEngineStreakLastTick - ownEngineStreakStartTick)));
        }
        beamPres.logMeter(where); // The beam-lane census (silent at off)
        // The own-ship reconcile residual census (D2's instrument).
        PilotWatchLog.info("predict own-recon-meter [" + where + "]:"
                + " own_recon_frames=" + ownReconFrames
                + " own_recon_resid_su=" + (float) ownReconResidSu
                + " own_recon_resid_max=" + ownReconResidMax
                + " own_recon_back_frames=" + ownReconBackFrames
                + " own_recon_back_su=" + (float) ownReconBackSu
                + " own_recon_back_max=" + ownReconBackMax
                + " own_recon_snaps=" + ownReconSnaps
                // The settled/haul meters: settled = drive-OFF stratified (fly-in pollution
                // excluded); haul = max su of own-pose correction per 0.5 s gliding window
                // (the percept quantity — D-CAP bound ≈ 120 su/window + disclosed snaps).
                + " own_recon_resid_max_settled=" + ownReconResidMaxSettled
                + " own_haul_max_su_half_s=" + ownHaulMaxSuPerHalfS);
        missileLane.logMeter(where); // The missile-lane census (silent at off)
        mtrack.logCensus(where);     // The mtrack census (silent at OFF)
        demLane.logMeter(where);     // The DEM-presenter census (silent at off)
        crCensus.logFlush(where, ownShip, ownCrWireLast, ownDepWireLast); //
        // Tidy: the census itself skips periodic advance@ wheres — edge/teardown only
        teleportLane.logMeters(where); // The jump-marker meter family (silent at off)
        if (FOE_INTERP_METER) {
            PilotWatchLog.info("predict foe-interp-meter [" + where + "]:"
                    + " mode=" + FOE_INTERP_MODE + foeInterp.meterTokens());
        }
        // Work-queue (38)(a): the snapshot-starvation timeline — the merger's drop/mixed
        // meters at the same flush cadence the grade read foe-interp deltas at.
        PilotWatchLog.info("predict merger-meter [" + where + "]:" + chunkMerger.meterTokens()
                + WireMarkRows.periodicTokens(this) // The core's SNAP gauges ride the timeline
                // The projectile-set sweep's gauges (sizes flat over a long serve = fixed)
                + " proj_sets=" + seenOwnProj.size() + "/" + seenReplayShipProj.size() + "/" + presentedFoeProj.size()
                + " proj_swept=" + projSweptTotal + " sweep_us=" + projSweepLastUs + " proj_sweep_fails=" + projSweepFails
                + driveLock.meterTokens()); // The rig's R-LOCK INFO row reads drive_lock_frames
        if (FOE_FACING_METER) {
            // The C5 grade reads astep_max_deg per entity (station base id
            // named by the bind log's "band COMMITTED base id="): a reproduced staircase shows
            // ~0.46 deg in one frame, smooth engine integration of -2 deg/s at 540 fps ~0.0037.
            PilotWatchLog.info("predict foe-facing-meter [" + where + "]:"
                    + " mode=" + FOE_FACING_MODE + foeFacing.meterTokens());
            // The RE-ARM: these rows produced ZERO hits on a recorded watch despite the
            // emitter running correctly (81 rows built). Cause: they were newline CONTINUATIONS of
            // the line above, and log4j tags only the first line (%m%n) — so the archive cut
            // (`watch-preflight-lib.ps1:202`, `if ($line -notmatch 'CC-PILOT-WATCH') { continue }`)
            // dropped every one at capture time. Each row now gets its OWN tagged line. Row shape
            // ("  id=…") is unchanged, so the token greps work as written.
            for (String row : foeFacing.perEntityReport().split("\n")) {
                if (!row.isBlank()) {
                    PilotWatchLog.info("predict foe-facing-ent [" + where + "]:" + row);
                }
            }
        }
    }

    /** The timestamped dual-side sim-rate summary for the WATCH log (see the field block). */
    private String simRateSummary() {
        if (combatWallStartNanos == Long.MIN_VALUE) {
            return "simRate: not-yet-anchored";
        }
        double wallSec = (System.nanoTime() - combatWallStartNanos) / 1_000_000_000.0d;
        double serverSimSec = serverSimSeconds();
        if (wallSec < 0.001d) {
            return "simRate: wall=<warmup>";
        }
        String svrOverCli = clientSimTimeSec > 0.05d ? fmt2(serverSimSec / clientSimTimeSec) : "n/a";
        return "simRate: wall=" + fmt2(wallSec) + "s clientSim=" + fmt2(clientSimTimeSec) + "s serverSim="
                + fmt2(serverSimSec) + "s clientRate=" + fmt2(clientSimTimeSec / wallSec)
                + " serverRate=" + fmt2(serverSimSec / wallSec) + " serverOverClient=" + svrOverCli
                + " clientNow=" + clockServo.servoNowRate() + " servo=" + clockServo.servoState()
                + " slope10s=" + serverSlope10s();
    }

    /** The trailing-window server clock slope {@code ΔserverSim/Δwall}
     *  over the ~10 s sample buffer — the re-registered server-sag trigger variable. {@code n/a} until the
     *  buffer spans half the window, and on serverless runs (no server clock). A frozen feed (stale tail)
     *  truthfully decays toward 0.00. */
    private String serverSlope10s() {
        double[] d = slopeWindowDeltas();
        if (firstServerSimTick == Long.MIN_VALUE || d == null) {
            return "n/a";
        }
        return fmt2(d[1] / d[0]);
    }

    /** trailing-window deltas {@code {Δwall, ΔserverSim, ΔclientSim}} off the 1 Hz slope
     *  buffer — the shared numeric core under {@link #serverSlope10s} and both HUD sim-rate lines.
     *  {@code null} until the buffer spans half of {@link #SLOPE_WINDOW_SEC}, and on a backwards server
     *  re-anchor mid-buffer (round boundary): a negative Δ is a re-anchor artifact, not a rate — same
     *  guard idiom as {@link OwnReadoutLane#tickRateSummary}'s negative-dTick reset. */
    double[] slopeWindowDeltas() {
        if (slopeSamples.size() < 2) {
            return null;
        }
        double[] oldest = slopeSamples.peekFirst();
        double[] newest = slopeSamples.peekLast();
        double dWall = newest[0] - oldest[0];
        if (dWall < SLOPE_WINDOW_SEC / 2.0d || newest[1] - oldest[1] < 0d) {
            return null;
        }
        return new double[] {dWall, newest[1] - oldest[1], newest[2] - oldest[2]};
    }

    /** K20 extraction seam — the 1 Hz slope + net-rate sampling, fired by {@link ClockServoLane}
     *  once per filled trace interval (the slope and the net rates ride the trace cadence by design; the
     *  buffers and their readers stay plugin-side). */
    void sampleRateWindows(double wallSec) {
        // I.3 slope sample (1 Hz): trailing-window {wall, serverSim, clientSim} for the slope10s log token
        // + the windowed HUD sim-rate lines.
        slopeSamples.addLast(new double[] {wallSec, serverSimSeconds(), clientSimTimeSec});
        while (!slopeSamples.isEmpty() && wallSec - slopeSamples.peekFirst()[0] > SLOPE_WINDOW_SEC + 1.0d) {
            slopeSamples.removeFirst();
        }
        // The SERVER TICK / net-rate trailing window rides the same 1 Hz cadence.
        if (lastSnapshotSimTick != Long.MIN_VALUE) {
            netRateSamples.addLast(new double[] {
                    wallSec, (double) lastSnapshotSimTick, (double) netFramesPublished});
            while (!netRateSamples.isEmpty()
                    && wallSec - netRateSamples.peekFirst()[0] > SLOPE_WINDOW_SEC + 1.0d) {
                netRateSamples.removeFirst();
            }
        }
    }

    /**
     * Accumulate ONE frame into the per-frame clock discriminator. Diffs
     * {@code System.nanoTime()} to get the real wall dt for the frame the engine just advanced by
     * {@code amount} sim-seconds, then buckets it (small/uncapped vs big/capped) and tallies the dropped
     * real time per bucket. The first frame seeds the timestamp (no dt yet); a frame whose wall dt exceeds
     * {@link #PAUSE_GAP_SEC} is a pause/load gap (advance() does not run while paused) and is excluded.
     * Render-thread only; read-only.
     */
    private void measureFrameClock(float amount) {
        long now = System.nanoTime();
        if (lastFrameNanos == Long.MIN_VALUE) {
            lastFrameNanos = now;
            frameLedger.seedClock(); // The first slot's window starts at this clock point
            return; // need two frames to form a dt
        }
        double realDt = (now - lastFrameNanos) / 1_000_000_000.0d;
        lastFrameNanos = now;
        if (!(realDt > 0d)) {
            return;
        }
        // The foe-step meter classes a jump by THIS frame's unclamped wall dt (a
        // pause/load gap counts as long too — its jumps are the frame loop's, never the wire's).
        foeStep.beginFrame(realDt);
        if (amount > maxAmountSec) {
            maxAmountSec = amount;
        }
        if (realDt > PAUSE_GAP_SEC) {
            pauseGapFrames++;
            frameLedger.observeGap(realDt); // Confessed on the round summary, never ranked
            return; // a pause/load gap, not a steady-state render frame — keep it out of the clock accounting
        }
        clockFramesMeasured++;
        sumAmountSec += amount;
        sumRealDtSec += realDt;
        // Unclamped overrun witness — one row per >2×-budget frame
        // (dedicated cap) + running max/count for the clock-meter summary. The row carries the
        // clamped `amount` too, so a reader sees exactly what the engine hid (%r on the sidelog
        // line is the wall stamp the F9-pin co-location join uses).
        if (realDt > maxRealDtSec) {
            maxRealDtSec = realDt;
        }
        if (realDt > FRAME_OVERRUN_SEC) {
            overrunFrames++;
            if (overrunRowsLogged < FRAME_OVERRUN_ROW_MAX) {
                overrunRowsLogged++;
                PilotWatchLog.info("predict frame-overrun wall_ms=" + fmt2(realDt * 1000.0d)
                        + " amount_ms=" + fmt2(amount * 1000.0d) + " n=" + overrunFrames);
            }
        } else if (Double.isNaN(hitchBaseSec)) {
            hitchBaseSec = realDt; // Seed the baseline on the first non-overrun frame
        } else if (realDt > HITCH_ABS_FLOOR_SEC && realDt > HITCH_BASE_MULT * hitchBaseSec) {
            // Hitch band: above both floors, under the overrun floor; never feeds the EMA.
            hitchFrames++;
            if (hitchRowsLogged < HITCH_ROW_MAX) {
                hitchRowsLogged++;
                PilotWatchLog.info("predict frame-hitch wall_ms=" + fmt2(realDt * 1000.0d)
                        + " base_ms=" + fmt2(hitchBaseSec * 1000.0d)
                        + " amount_ms=" + fmt2(amount * 1000.0d)
                        + " tick=" + lastSnapshotSimTick + " n=" + hitchFrames);
            }
        } else {
            hitchBaseSec += HITCH_BASE_ALPHA * (realDt - hitchBaseSec);
        }
        // Rank this frame in the worst-frames ring (self-gated; reuses this realDt so
        // the ledger inherits the entry anchor + pause-gap exclusion with zero new brackets).
        frameLedger.observe(realDt, amount, lastSnapshotSimTick);
        remoteCensus.tick(this, System.nanoTime()); // One census line per ~60 s
        ownMoteCensus.tick(this, amount); // own-body mote generator census, 1 Hz
        dwellerCensus.tick(this, amount); // Shrouded Dweller census, 1 Hz (twin + served, same code)
        if (session != null) {
            moteReplica.tick(); // re-assert the generator pin (two map reads); served sessions only
        }
        clockServo.frame(amount, realDt);
    }

    /**
     * The per-run clock meter (the surviving core of the STEP-5 instrument; its H1/H2 question was
     * answered and the bucketing pruned with the closed arc). With the servo locked, {@code amount} — and so
     * {@code aggRatio} — measures the CORRECTED clock (192340 follow-up), so the verdict is net corrected
     * drift; ~1.0 means the servo is doing its job. {@code maxAmount} still empirically reveals the engine's
     * per-frame cap; {@code pauseGaps} counts excluded pause/load frames.
     */
    void logClockMeter(String where) {
        double aggRatio = sumRealDtSec > 0d ? sumAmountSec / sumRealDtSec : 0d;
        double avgFps = sumRealDtSec > 0d ? clockFramesMeasured / sumRealDtSec : 0d;
        String verdict;
        if (clockFramesMeasured < 30) {
            verdict = "INCONCLUSIVE (only " + clockFramesMeasured + " measured frames)";
        } else if (ClockServoLane.SERVO_ENABLED && clockServo.windowLocked()) {
            if (aggRatio >= 1.02d) {
                verdict = "CORRECTED CLOCK FAST @" + fmt2(aggRatio) + " since anchor (servo "
                        + clockServo.servoState() + " — clamp railed? check servo-trace target= vs servo=)";
            } else if (aggRatio <= 0.98d) {
                verdict = "CORRECTED CLOCK SLOW @" + fmt2(aggRatio) + " since anchor (servo "
                        + clockServo.servoState() + " — clamp railed? check servo-trace target= vs servo=)";
            } else {
                verdict = "corrected clock wall-true @" + fmt2(aggRatio) + " since anchor (servo "
                        + clockServo.servoState() + " holding)";
            }
        } else {
            // servo off/warmup: aggRatio is the RAW open-loop pacer rate (the answered STEP-5..8 story —
            // sleep-overshoot deficit / no-sleep free-run; detail).
            verdict = "raw open-loop clock @" + fmt2(aggRatio) + " since anchor (servo "
                    + clockServo.servoState() + ")";
        }
        PilotWatchLog.info("predict fidelity clock-meter [" + where + "]: frames=" + clockFramesMeasured
                + " avgFps=" + fmt2(avgFps) + " aggRatio(amount/realDt)=" + fmt2(aggRatio)
                + " | maxAmount=" + fmt4(maxAmountSec) + "s pauseGaps=" + pauseGapFrames
                // the unclamped-witness summary: overruns = frames > 2×60Hz-budget,
                // maxRealDt = the wall truth the engine's amount clamp hides.
                + " overruns=" + overrunFrames + " maxRealDt=" + fmt4(maxRealDtSec) + "s"
                // The high-fps hitch band + the baseline it was judged against.
                + " hitches=" + hitchFrames + " hitchBase=" + fmt2(hitchBaseSec * 1000.0d) + "ms"
                + " servo=" + clockServo.servoState()
                + " -> " + verdict);
    }

    /** GAP 2: emit the read-only discharge meter — distinct local own-ship discharges vs the
     *  frames the human held the trigger. Server baseline = ~5 discharges / 149 firing frames;
     *  with native engine firing this should track the engine's real cooldown cadence, NOT >> the baseline. */
    void logFireMeter(String where) {
        PilotWatchLog.info("predict GAP2 fire-meter [" + where + "]: localOwnDischarges=" + localOwnDischarges
                + " vs fireHeldFrames=" + fireHeldFrames + " / advFrames=" + advFrames
                + " fire_decl_sent=" + fireDeclSent + " fire_decl_dropped=" + fireDeclDropped + " fire_decl_autofire_skipped=" + fireDeclAutofireSkipped
                // engine-own explosion entities excluded from discharge accounting
                // (flak/devastator proximity bursts + the own death explosion; weapon==null class).
                + " own_nonweapon_damagers=" + ownNonWeaponDamagers
                // The unwired-own-fire split (UnwiredOwnFireLaw) — the AI-fires-how measurement
                + " own_fire_wired_held=" + ownFireWiredHeld + " own_fire_wired_autofire=" + ownFireWiredAutofire
                + " own_fire_wired_system=" + ownFireWiredSystem
                + " own_fire_unwired_autopilot=" + ownFireUnwiredAutopilot + " own_fire_unwired_other=" + ownFireUnwiredOther
                // Frames the AI's selected-group fire was carried as the held level + the last aim source
                + " own_fire_synth_held_frames=" + autopilotFire.synthHeldFrames + " aim_source=" + autopilotFire.lastAimSource);
    }

    /**
     * Is the vanilla ship-AI driving the own hull?
     * The engine's own control test is {@code isAutopilotOn() || playerShip.getAI() != null}:
     * U attaches an AI AND sets the autopilot field, a command-map order on
     * the own hull attaches an AI WITHOUT the field, and the mod nulls the own AI for native player
     * control ({@code OwnSeatLifecycleLane}). So {@code getShipAI() != null} covers both. The API's
     * {@code isUIAutopilotOn()} is NOT used as the signal: the engine's getter returns
     * the field NEGATED — it rides the log line raw ({@link #uiAutopilotFlag}) so the first WATCH grades
     * that static reading empirically. Fail-soft false.
     */
    boolean ownAiDriven() {
        try {
            return ownShip != null && ownShip.getShipAI() != null;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** The API's raw {@code isUIAutopilotOn()} for the log line only (see {@link #ownAiDriven}); "?" on a throw. */
    String uiAutopilotFlag() {
        try {
            return engine != null ? String.valueOf(engine.isUIAutopilotOn()) : "-";
        } catch (Throwable ignore) {
            return "?";
        }
    }

    /**
     * Count one classified own discharge; an UNWIRED verdict (the server's
     * own twin never fires this shot) writes ONE capped {@link PilotWatchLog#error} per
     * {@link UnwiredOwnFireLaw#ERROR_EVERY_SECONDS} sim seconds while such discharges accrue, plus the
     * one-shot {@code own-fire-unwired} FALLBACK announce. Log-only by design — the detector is the
     * measurement; a player-facing line waits on what the first WATCH log says. The weapon id is
     * catalog data (a served variant's mounts are catalog-validated server-side) and still passes the
     * printable sanitize at write time per the standing log law.
     */
    void noteOwnFireVerdict(UnwiredOwnFireLaw.Verdict v, WeaponAPI w) {
        switch (v) {
            case WIRED_AUTOFIRE:
                ownFireWiredAutofire++;
                return;
            case WIRED_HELD:
                ownFireWiredHeld++;
                return;
            case WIRED_SYSTEM:
                ownFireWiredSystem++;
                return;
            case UNWIRED_AUTOPILOT:
                ownFireUnwiredAutopilot++;
                break;
            case UNWIRED_OTHER:
            default:
                ownFireUnwiredOther++;
                break;
        }
        autopilotFire.noteUnwired(ownFireUnwiredAutopilot + ownFireUnwiredOther); // The one-per-combat player line
        String weapon = "-";
        try {
            weapon = w != null ? FallbackAnnounce.sanitize(w.getId(), 64) : "-";
        } catch (Throwable ignore) {
            // diagnostics only
        }
        String detail = v + " weapon=" + weapon + " sinceFireHeldSec="
                + (secondsSinceFireHeld == UnwiredOwnFireLaw.NEVER_HELD ? "never" : fmt1(secondsSinceFireHeld))
                + " simSec=" + fmt1(clientSimTimeSec) + " advFrame=" + advFrames
                + " aiDriven=" + ownAiDriven() + " uiAutopilotFlag=" + uiAutopilotFlag()
                + " unwired_autopilot=" + ownFireUnwiredAutopilot
                + " unwired_other=" + ownFireUnwiredOther;
        if (UnwiredOwnFireLaw.errorDue((float) clientSimTimeSec, ownFireUnwiredLastErrorSec)) {
            ownFireUnwiredLastErrorSec = (float) clientSimTimeSec;
            PilotWatchLog.error("predict own-fire UNWIRED: " + detail
                    + " — a local own discharge the held lane + autofire mask never carried; the server's"
                    + " own twin holds fire unless it serves PDCL (declaredFireLane, default off; the client"
                    + " is not told) (next line >= " + fmt1(UnwiredOwnFireLaw.ERROR_EVERY_SECONDS)
                    + " sim s; counters continue on the GAP2 fire-meter)", null);
        }
        FallbackAnnounce.announceOnce("own-fire-unwired", detail);
    }

    /**
     * The census line (smoke row source): bound/unbound counts, kills, per-side local
     * liveness ({@code isAlive}, the engine's own verdict). Bots shapes only. The PRIMARY emit is the
     * render-side post-feed one in {@code advance()} (the stream closes long before the render
     * thread consumes the tail, so a stream-end census reads stale kill/liveness numbers); the
     * stream-end + teardown calls go through {@link #logRemoteCensusBackstop} and fire at most once.
     * {@code remoteAiClass} is a diagnostic, not a pass key — {@code getShipAI()} returns an engine
     * wrapper, so an {@code instanceof InertShipAI} probe is structurally false.
     */
    private void logRemoteCensus(String where, boolean renderThread) {
        if (!registryShape) {
            return;
        }
        try {
            int boundCount = 0;
            int kills = 0;
            int live0 = 0;
            int live1 = 0;
            String aiClass = "none";
            for (RemoteEntity re : remoteEntities.values()) {
                if (re.ship == null) {
                    continue;
                }
                boundCount++;
                if (re.killed) {
                    kills++;
                }
                if (re.ship.isAlive()) {
                    if (re.side == 0) { live0++; } else { live1++; }
                    if ("none".equals(aiClass)) {
                        aiClass = re.ship.getShipAI() == null ? "null"
                                : re.ship.getShipAI().getClass().getSimpleName();
                    }
                }
            }
            PilotWatchLog.info("predict bots census: bound=" + boundCount + "/" + remoteEntities.size()
                    + " unbound=" + remoteUnbound + " kills=" + kills + " reaped=" + remoteReaped
                    + " respawns_own=" + ownRespawns + " refields_own=" + ownRefields
                    + " respawns_remote=" + remoteRespawns
                    + " liveRemote side0=" + live0 + " side1=" + live1
                    + " remoteAiClass=" + aiClass
                    + " ownAlive=" + (ownShip != null && ownShip.isAlive())
                    + " where=" + where + " " + RemoteEntityCensus.breakdown(this, renderThread));
        } catch (Throwable ignore) {
            // census is diagnostic only; never break teardown
        }
    }

    /** Stream-end / teardown census backstop: emits only if no census fired yet (the post-feed
     *  render-side emit is the primary and fires regardless of this flag). */
    private void logRemoteCensusBackstop(String where) {
        if (remoteCensusLogged || postFeedCensusLogged) {
            return;
        }
        remoteCensusLogged = true;
        logRemoteCensus(where, false); // may run on the net thread (streamEnd) — peek, never drain
    }

    /** Stop the net thread and close the session of the currently-active plugin, if any. Idempotent. */
    private static synchronized void teardownActive() {
        PredictReconcileCombatPlugin prior = active;
        if (prior != null) {
            // Law B (the folded-spec retire) rides the session's close, never a kept-open exit; the body flips
            // the holder when it keeps the session open
            boolean[] sessionClosed = {true};
            try {
                teardownActiveBody(prior, sessionClosed);
            } finally {
                // The retire is the LAST act of a ~90-line teardown; a throw above
                // it must not leave folded specs tracked as live for the JVM's life.
                retireFoldedAtTeardown(sessionClosed[0]);
            }
        }
    }

    /** {@link #teardownActive}'s body, verbatim; {@code sessionClosed[0]} = its one out-param. */
    private static void teardownActiveBody(PredictReconcileCombatPlugin prior, boolean[] sessionClosed) {
        // Rider: symmetric with the init latch — a
        // process-wide static must not outlive the served window (a later vanilla mission in the
        // same JVM would field no fighters); FIRST statement so no flush below can skip it.
        com.fs.starfarer.combat.entities.ship.A.C.REFIT_MODE_ADVANCE = false;
        bayKillSwitchLatched = false; // the live mirror follows the engine static, same statement order
        // Same law for the vanilla mote table — the served window's generator pin
        // comes off so a later vanilla mission in this JVM keeps its motes.
        prior.moteReplica.restore();
        // The late-attach hold dies with its window — a
        // resumed advance never polls or binds after this, and the banner's hold wording goes too.
        prior.lateAttachHold = false;
        prior.lateAttachPolling = false;
        CcClientModPlugin.noteLateAttachHold(false);
        // The dying window's onFire deliveries stop consuming NOW —
        // symmetric with the init() bind (post-teardown births count prebind, fall to scan).
        ClientFireBirthSink.unbind();
        // The wrapper census line (wsg census …) then the symmetric unbind —
        // wrappers on a dead window fall back to vanilla via the prebind path.
        CcReplicaEffectGate.logCensus("teardown");
        CcReplicaEffectGate.unbind();
        CcServedBombMirror.sweep(); // off-thread-safe (flags, the plugin self-removes)
        prior.logRemoteCensusBackstop("teardown");
        prior.logCaptureTimingSummary("teardown");
        prior.logSpeedMeter("teardown"); // Final local-vs-server speed divergence in the WATCH log
        prior.logClockMeter("teardown"); // final corrected-clock drift verdict in the log
        prior.frameLedger.flushTeardown(); // A truncated final round still confesses
        MarkFrameRing.onCombatEnd(); // Drop the in-memory frame ring (an open post window finishes itself)
        if (prior.fuseMeter != null) {
            prior.fuseMeter.logSummary("teardown"); // silent if no devastators seen
        }
        prior.dmgWitness.logMeter("teardown", true); // Flush open beam aggregates + census
        // The two emits teardown MISSED — with them, EVERY end path (combat
        // over, ops-GUI End match, window close, staleness) flushes the full meter set.
        prior.logFireMeter("teardown");
        // Flush the tail aim-rebase phase (rows are F9-fenced; with
        // zero markers this one row spans the whole run — a live positive control per watch).
        prior.fireLanes.aimRebase.closeAimRebasePhase("teardown", prior.lastSnapshotSimTick);
        prior.fractures.logFractureTotals("teardown");
        // The per-entity rings flush beside the hulk adopt ring (marker #0,
        // window on the last snapshot tick — FractureFamilyCoordinator's teardown dumpAdoptRing argument shape); never cleared per round.
        prior.foeState.dumpFluxRings("teardown", 0L, prior.lastSnapshotSimTick);
        prior.sameSideAim.dumpRings("teardown", 0L, prior.lastSnapshotSimTick);
        prior.clockServo.removeClockServo(); // Leave the engine's time-mult stat clean after combat
        // The content catalog dies with the session, NOT at the round
        // boundary (contentIds are match-scoped — applyMatchReset must keep it).
        prior.contentCatalog.clear();
        prior.running = false;
        prior.writerRunning = false;
        ChatLane.unbindEditorLane(prior.chat); // the drain stops: no editor deposit lands on this lane
        // The staleness watchdog calls this FROM the writer thread — a
        // self-interrupt/self-join would spin the 1 s grace and log a bogus still-alive
        // line, so the writer's own interrupt+join is skipped (it exits right after).
        boolean onWriterThread = prior.writerThread == Thread.currentThread();
        if (prior.netThread != null) {
            prior.netThread.interrupt();
        }
        if (prior.writerThread != null && !onWriterThread) {
            // Interrupt AND join (bounded) — the writer owns the socket's
            // write half, so teardown must not leave it racing the session close below.
            prior.writerThread.interrupt();
        }
        // (sessionClosed[0] starts true — the caller's holder; Law B rides the session's close, never a kept-open exit)
        if (prior.session != null) {
            if (prior.session instanceof cc.starsector.client.net.PredictReconcileDatagramSession) {
                // The freshness-lever receipts — the session class stays
                // log-silent (counters are its surface; the plugin logs).
                cc.starsector.client.net.PredictReconcileDatagramSession udp =
                        (cc.starsector.client.net.PredictReconcileDatagramSession) prior.session;
                PilotWatchLog.info("predict teardown: snap freshness levers"
                        + " tick_conflate=" + udp.snapTickConflate()
                        + " stale_fold=" + udp.snapStaleFold()
                        + " stale_folded=" + udp.snapStaleFolded()
                        + " stale_fold_queued=" + udp.snapStaleFoldQueued()
                        + " stale_old_dropped=" + udp.snapStaleOldDropped()
                        + " old_tick_dropped=" + udp.snapOldTickDropped()
                        + " snap_superseded_dropped=" + udp.snapSupersededDropped() // the catch-up discard count
                        + " stale_passthrough=" + udp.snapStalePassthrough()
                        + " passthrough_queued=" + udp.snapStalePassthroughQueued()
                        + " passthrough_dropped=" + udp.snapStalePassthroughDropped());
                // The CTRL cap receipt — dropped=0 means the cap never bit;
                // high_water meters the real un-drained pile-up (menu-sit + admission burst)
                // so any re-size of CTRL_QUEUE_CAP rests on flights, not guesses.
                PilotWatchLog.info("predict teardown: ctrl queue cap"
                        + " dropped=" + udp.ctrlFramesDropped()
                        + " high_water=" + udp.ctrlQueueHighWater());
                WireMarkRows.teardownRow(udp); // The SNAP-gap histogram receipt
            }
            if (!prior.session.closeAtMissionExit()) {
                sessionClosed[0] = false;
                PilotWatchLog.info("predict teardown: session KEPT OPEN, mission-exit goodbye armed"
                        + " (W5-D1: seat TRUE-vacates -> server grace, or ban-vacated at once on a ban"
                        + " kick; B4 re-admit lane owns the menu gap, re-pick = reclaim)");
            } else {
                // The session really closed - its kept /auth line goes with it.
                ChatLane.forgetAuthFor(prior.session);
            }
        }
        if (prior.writerThread != null) {
            if (!onWriterThread) {
                try {
                    prior.writerThread.join(1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (prior.writerThread.isAlive()) {
                    PilotWatchLog.info("predict teardown: writer thread still alive past 1 s join grace");
                }
            }
            prior.writerThread = null;
        }
        prior.session = null;
        active = null;
    }

    /**
     * Law B: the specs this session's VDEF folds registered are RETIRED with the session
     * and leave the engine map at the next session's first fold — never here: this teardown runs
     * while the engine's {@code CombatState} still draws the end-of-match UI through every deployed
     * hull's variant (a station parent's module ids resolve by id with no null guard — the
     * Fatal on every window). Boot ids were never put (Law A).
     * A kept-open session keeps its folds live — its re-pick reclaims the seat without a fresh
     * admission burst. Split out so {@link #teardownActive}'s callers wrap it in
     * a {@code finally}: any throw above it in the teardown must not leave the tracker live for the
     * JVM's life.
     */
    private static void retireFoldedAtTeardown(boolean sessionClosed) {
        if (sessionClosed) {
            BootVariantRegistry.retireFolded("predict teardown");
        } else if (BootVariantRegistry.foldedCount() > 0) {
            PilotWatchLog.info("predict teardown: session kept open -> " + BootVariantRegistry.foldedCount()
                    + " folded variant spec(s) retained for the re-pick");
        }
    }

    /**
     * Is this damage-report SOURCE a server-killed ship body? The engine
     * passes the SHIP itself as the report-seam source of its own death explosion (and of a hulk's
     * ram): {@code src=entity-ex-replica:<id>} / {@code entity-own} in the witness rows. Local death
     * is server-decided by construction (axis C — a local hit never kills), so a dead source's hit
     * is SERVER-DEALT and already on the wire: {@link FoeHullReconcileLane.HullLedger#recordApplied}
     * records it acked (no replay entry) instead of holding it as a local prediction awaiting the
     * wire. A recorded case: the own hulk's 1869.8 apply on the neighbour aurora sat as
     * {@code outstanding} for the whole 2.0 s replay expiry because the wire's 4441 drop had landed
     * in the death snapshot BEFORE the push — the hull bar visibly rebounded a second or two
     * later. Reads the kill latches first ({@code ownKilled} / {@code RemoteEntity.killed} /
     * {@code foeKilled} are set BEFORE the presenter runs the engine death, so same-frame explosion
     * applies classify too), then the engine's own liveness. Never throws; unreadable = ordinary.
     *
     * <p>Kill-tick classification.
     * Renamed from {@code isServerKilledSource}. Three defects of the
     * boolean form: (1) the engine passes the firing SHIP as the report-seam source for its
     * PROJECTILE damage too, so a shot still in flight when
     * its firer died pushed no replay entry (bar snaps UP to wire, drops at RTT); (2) {@code ownKilled}
     * is also latched by a voluntary module DISMOUNT, so a LIVE module classified dead; (3) the
     * catch swallowed silently. Now: the kill TICK latches ({@link #ownDeathTick} /
     * {@link #foeDeathTick} / {@code RemoteEntity.deathTick}, set only by the server-decided kill
     * paths) feed {@link ServerDealtSourceLaw#classify} — inside the 30 t kill window = the death
     * flood, acked; latched but later = the posthumous class, ORDINARY and counted
     * ({@link #deadSrcLate}); the {@code !isAlive()} fallback is gone (an un-latched dead body is an
     * ordinary, metered replay entry). THEN the door class: a prox-charge ENGINE-DOOR det the
     * presenter armed ({@link #doorDet}) is server-dealt too when the victim sits in its reach inside
     * its window. A throw counts {@link #deadSrcClassifyFail} + announces once; returns LOCAL.
     *
     * <p>Renamed from {@code serverDealtSource}; returns a
     * {@link HullCreditRing} source class — SERVER_DEALT where it returned true; REPLICA when the source
     * is {@code foeShip} or a {@code RemoteEntity.ship} (a wire-driven replica: may use a Shape A
     * credit); LOCAL for own, non-ship, unresolved or a throw (never uses credit).
     */
    int hullSourceClass(Object source, CombatEntityAPI target) {
        if (!(source instanceof ShipAPI)) {
            return HullCreditRing.SRC_LOCAL;
        }
        ShipAPI s = (ShipAPI) source;
        try {
            long remoteDeathTick = Long.MIN_VALUE;
            boolean isOwn = s == ownShip;
            boolean isFoe = !isOwn && s == foeShip;
            boolean isRemote = false;
            if (!isOwn && !isFoe) {
                for (RemoteEntity re : remoteEntities.values()) {
                    if (re.ship == s) {
                        remoteDeathTick = re.deathTick;
                        isRemote = true;
                        break;
                    }
                }
            }
            int cls = ServerDealtSourceLaw.classify(isOwn, isFoe, ownDeathTick, foeDeathTick,
                    remoteDeathTick, lastSnapshotSimTick);
            if (cls == ServerDealtSourceLaw.SRC_DEAD_ACKED) {
                return HullCreditRing.SRC_SERVER_DEALT;
            }
            if (cls == ServerDealtSourceLaw.SRC_DEAD_LATE) {
                deadSrcLate++;
            }
            if (target != null) {
                Vector2f loc = target.getLocation();
                if (loc != null && doorDet.matches(source, loc.x, loc.y, target.getCollisionRadius(),
                        lastSnapshotSimTick)) {
                    return HullCreditRing.SRC_SERVER_DEALT;
                }
            }
            return isFoe || isRemote ? HullCreditRing.SRC_REPLICA : HullCreditRing.SRC_LOCAL;
        } catch (Throwable t) {
            deadSrcClassifyFail++;
            FallbackAnnounce.announceOnce("dead-src-classify",
                    "hullSourceClass threw " + t.getClass().getSimpleName()
                            + " -> classified ordinary (predict_dead_src_classify_fail)");
        }
        return HullCreditRing.SRC_LOCAL;
    }

    /** Latched-dead sources hit OUTSIDE the kill window (posthumous projectiles; ordinary entries).
     *  A meter exported on the summary line. */
    long deadSrcLate;
    /** {@link #hullSourceClass} throws (the catch was silent).
     *  A meter exported on the summary line. */
    long deadSrcClassifyFail;

    /** TRUE iff {@code s} is a child module
     *  of {@code parent} ({@code ShipAPI.getParentStation() == parent}). A null parent is never a
     *  parent (a non-module's getParentStation() is null — without the guard every non-module would
     *  match a null newOwn). Pure over the API getter, used by both match-reset sweeps. */
    static boolean isModuleOf(ShipAPI s, ShipAPI parent) {
        return parent != null && s != null && s.getParentStation() == parent;
    }

}
