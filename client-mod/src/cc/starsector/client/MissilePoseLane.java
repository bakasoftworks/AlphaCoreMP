package cc.starsector.client;

import cc.starsector.client.net.ServerMissileState;
import cc.starsector.client.net.ServerSnapshotFrame;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.entities.Missile;
import com.fs.starfarer.combat.entities.Ship;
import com.fs.starfarer.loading.specs.F;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The MISSILE POSE lane (NEW-LANE LAW: collaborator, not
 * in-plugin — the {@link FireLaneCoordinator} coupling model, plugin backref for the mutable
 * cross-lane state). Knob {@code cc.predict.missilePose} = {@code off|meter|apply} (default
 * {@code apply} — the slice-2 {@code engineBornGrowth} default-ON precedent; the (h) watch is the
 * acceptance gate, knob-flip = rollback).
 *
 * <p>WHAT IT DOES: presented GUIDED missiles and FLARES become pose PUPPETS of their wire
 * kind=MISSILE records (schema v13 stable per-lifetime networkIds + source pair). At enroll
 * (the two present sites: PRCP foe {@code WEAPON_DISCHARGE}, FLC {@code executeAdopt} own-adopt)
 * the MINTED missile is classified from its factory AI — {@code isGuided()||isFlare()} — BEFORE
 * any replace; in-scope under apply gets a puppet {@link MissileAIPlugin} installed
 * ({@code setMissileAI} wraps it in the engine's AI-only wrapper ⇒ {@code isGuided()} flips FALSE:
 * local flare seduction structurally impossible, seeker handoff naturally skipped) plus
 * {@code setMaxFlightTime(1e9)} (fizzle suppression — server range/ECCM mults can outlive the
 * local clock). The puppet AI is engine-slot-gated (the gate re-sourced
 * after the getShipEngines() falsification): missiles WITH engine slots
 * get {@link ThrustPuppetAI} (per-frame ACCELERATE — the engine flame/glow + contrail are
 * ACCELERATE-command-driven, and every enrolled class's real AI
 * accelerates per-frame in powered flight); engine-less specs (bombs) keep the plain
 * {@link #NOOP_AI}. Ballistic (neither guided nor flare) splits: the
 * engine-LESS class (free-fall bombs) IS enrolled — its wire twin crosses fully sourced (M1) and
 * the old skip left a live local warhead diverging from the server track — while engine-slotted
 * ballistic (powered rockets) keeps local flight (the verdict) with its source pair
 * remembered so presence-adoption never double-presents its wire twin. Between fresh frames the engine integrates the puppet locally:
 * thrust puppets accelerate along facing (bounded by the local maxSpeed clamp; pose-adopt
 * overwrites loc/vel every fresh frame) but only while the pose is CURRENT — past
 * {@link #THRUST_STALE_TICKS} without a fresh adopted pose the puppet coasts like the plain no-op puppet
 * (the stale-gate: stale gaps and post-server-death tails never accelerate along stale
 * facing); bombs straight-line.
 *
 * <p>PER FRESH FRAME ({@code maintain}; stale NEVER ticks — the FoeHull stale-suspend precedent):
 * (1) prune twins whose local missile left play (local-PD kill ⇒ the wire id becomes re-adoptable
 * = the (3)(d) self-heal); (2) pose-adopt linked twins — loc := record, vel := per-id wire delta ×
 * ticks/s, facing := the v16 WIRE POSE truth when the record carries one (dissolves
 * both the drift-atan2 reconstruction and the DEM-beam-bearing special case; sentinel-pose
 * records keep atan2(vel) as the fallback), plus a one-shot {@code flameOut()} relay on the wire
 * FIZZLING bit (the engine's own death arc plays while the wire still tracks the drifting server
 * missile; a fizzling record also never presence-mints — the full-glow respawn guard); (3) link
 * unlinked twins by (source
 * pair, spawn window) FIFO-oldest, rejecting pair-matches whose wire contentId resolves to a
 * DIFFERENT spec than the twin's own (a MIRV child rides its launcher's
 * attribution pair, so without the reject a stage-1 twin could claim its child's id) and
 * pair-matches on an already-consumed wire id (the freshness guard — the mint
 * never recycles, so such a record is the old missile back from a feed gap, never a fresh
 * discharge's twin; the instant-mislink class); an unlinked
 * twin past the window is REMOVED LOUD (the wire never
 * confirmed it — e.g. it died server-side inside the present pipeline); (4) absence: a linked id
 * missing from G=6 consecutive FRESH frames is the authoritative end ⇒ the puppet body is REMOVED
 * outright (the end-of-feed removal; see {@link #retire}) — and a
 * MISSILE_SPLIT event ends its linked twin IMMEDIATELY, skipping the grace, with the vanilla
 * split moment + ballistic-children completion (see {@link #onSplit}); (5) presence-adopt: a live id, NOT own-sourced,
 * claimed by no twin, spawns a puppet AT the wire pose on its 2nd sighting (vel then known ⇒ exact
 * bearing+velocity; 1st sighting buffers) — resolve source ship (beam-aim idiom) + mount ordinal
 * weapon, spawn via the engine's own factory BY TRUE SPEC when the record's wire contentId
 * resolves through the plugin's content catalog (7-arg overload = the vanilla MIRV-child factory
 * seam, so a MIRV child presents as its child spec; unresolved degrades to the launcher recipe,
 * counted; an ANNOUNCED-but-unarrived entry defers the adopt ≤1 s first,
 * and a true-spec MIRV child gets vanilla's post-spawn damage recipe re-injected from the split
 * stash), classify the mint: guided/flare ⇒ enroll+link;
 * ballistic ⇒ remove the probe + blacklist the pair (counted, capped log). Unresolvable source ⇒
 * counted-unpresented (meter + capped log, never silent). Presence-adopted spawns join the
 * plugin's {@code presentedFoeProj} exclusion set so the autonomous-discharge meter never
 * counts them as a hold-fire leak.
 *
 * <p>KNOWN-BENIGN (recorded at land): a dead feed (no fresh frames) never ticks absence ⇒
 * puppets linger till round reset — the standing hold-last class. Two same-pair OWN missiles
 * (a predicted local + an FLC-adopted twin) inside one link window can swap tracks — same spec,
 * cosmetic. Own PREDICTED missiles stay unenrolled and own-sourced wire ids are never
 * presence-adopted (phase 1).
 *
 * <p>Render-thread only, fail-soft throughout (presentation lane; never break the watch).
 */
final class MissilePoseLane {

    /** Knob statics (the beamPresentation shape; THROWS at class load on an invalid value). */
    static final String MISSILE_POSE_MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.missilePose", System.getProperty("cc.predict.missilePose", "apply"));
    static final boolean MISSILE_POSE_METER = !"off".equals(MISSILE_POSE_MODE);
    static final boolean MISSILE_POSE_APPLY = "apply".equals(MISSILE_POSE_MODE);
    /** Per-event log cap (the beam-lane discipline; counters continue past it). 40
     *  exhausted ~5 s into 192910's combat (every per-track line landed in the first 0.7 s).
     *  FOE pose-lane lines only since the per-lane split — the own handoff lane
     *  carries its own budget ({@code OwnMissileHandoffLane.OWN_MISSILE_LOG_MAX}). */
    private static final int MISSILE_LOG_MAX = 400;
    /** MINE rows get their OWN budget — one recorded match spent the
     *  shared 400 by ~tick 3300 of ~12000, so only 10 of 44 mines had per-row evidence and none of the
     *  4 {@code mine wire-end EXPLODE} rows landed. Sized on the own lane's idiom
     *  ({@code OwnMissileHandoffLane.OWN_MISSILE_LOG_MAX}); routed by {@link #laneFor}. */
    private static final int MINE_LOG_MAX = 2000;
    /** Linked-twin absence tolerance in consecutive FRESH frames (G). Top of the
     *  ratified band: split ends skip the grace entirely (see {@link #onSplit}),
     *  so G only delays PD-kill/fizzle removal ~0.1-0.2 s (under the ≤0.5 s end-removal bar) while
     *  eating the laggy-seat partial-frame absence bursts. */
    private static final int ABSENCE_FRESH_MISSES = 6;
    /** Unlinked-twin link window in server sim ticks (~2 s): past it the wire never confirmed
     *  the present (the missile died server-side inside the pipeline, or the source never
     *  resolved) — the puppet retires via the same authoritative-end removal. Package-visible:
     *  {@code OwnMissileHandoffLane.OWN_GHOST_TICKS} (compiled 60, E3 hardwire) stays ≤ this
     *  ceiling as a compiled invariant (a ghost window past it would silently never fire;
     *  the expiry RELEASE below preempts the fade). */
    static final int LINK_WINDOW_TICKS = 120;
    /** The presence-adopt LAUNCH-INSTANT window in server sim ticks: past
     *  this many ticks between an id's FIRST sighting and its adopt, the adopt is a re-acquisition
     *  (dict-defer expiry, a pair released from the ballistic blacklist), never a launch — and a
     *  launch flash at the launcher for a body that left it seconds ago is worse than silence. */
    private static final int ADOPT_FX_RECENT_TICKS = 30;
    /** Geometric half of the same guard: a JUST-launched body is still within its own
     *  measured travel over the sighting gap (+ this mount/barrel slack in su) of the fire point,
     *  while a MID-FLIGHT first sighting — a joiner's in-flight flare, whose sighting age is also
     *  1 tick — is far downrange. Together the two tests stand in for the wire spawn tick the
     *  missile tier does not carry ({@code ServerMissileState} has no spawnTick field). */
    private static final float ADOPT_FX_MUZZLE_SLACK = 100f;
    /** Sighting-gap padding (in ticks) for the reach estimate: the adopt lands one wire tick after
     *  the first sighting, plus the render frame it is presented on. */
    private static final int ADOPT_FX_LAG_TICKS = 2;
    /** The announced-but-unarrived catalog defer window in server sim ticks
     *  (~1 s): a record whose contentId (>=1) has no catalog entry YET is a dictionary
     *  ARRIVAL RACE (the dictionary rides cycling chunk #0 and re-announces), not an unknown
     *  spec — the adopt waits up to this window before the permanent launcher-recipe
     *  fallback (the seat2 ×7 stage-1-art defect this removes). */
    private static final int DICT_DEFER_TICKS = 60;
    /** Fizzle-suppression flight time (server range/ECCM mults outlive the local clock).
     *  Package-private: the prox-charge engine door
     *  ({@code MineBombEndFxLane}) copies the fizzle relay's {@code parked} derivation. */
    static final float PUPPET_MAX_FLIGHT_TIME = 1_000_000_000f;
    /**
     * The SOURCELESS adopt, compiled ON (hardwired); the {@code cc.predict.stationMineAdopt}
     * on|off knob-resolved shape (the earlier silent drop as the A/B lever) is in git.
     *
     *
     */
    static final boolean STATION_MINE_ADOPT = true;

    /**
     * the belt-and-braces, {@code cc.predict.stationMineIntangible} =
     * {@code on|off}, DEFAULT OFF: ON gives a sourceless-adopted
     * (station-mine) body {@code CollisionClass.NONE} (the {@code DemDronePresenter}
     * precedent, minted for the Merlon armor-chew-at-load worry). OFF keeps the TRUE
     * spec's class — {@code minelayer_mine.proj} is {@code MISSILE_NO_FF}, so friendly contact
     * is already impossible BY CLASS (NONE was never an IFF mechanism) while enemy PD visibly
     * hits and kills the mine (the reported defect: PD beams passed right through it);
     * a local PD kill prunes the twin and presence-adopt self-heals from the live wire id
     * ({@code pruneLocalGone}). The accepted trade: vanilla friendly damage over
     * mine IFF. Merlon rider: the re-watch grades Merlon drones un-chewed at load —
     * NO_FF keeps drone contact impossible and the owner set at adopt precedes any contact;
     * {@code on} restores the intangible treatment (the A/B + regression lever).
     * Compiled OFF — the ratified posture baked PERMANENTLY (E3 hardwire,
     * the one clientfx row hardwiring the disabled state; the
     * {@code cc.predict.stationMineIntangible} knob-resolved shape is in git).
     */
    static final boolean STATION_MINE_INTANGIBLE = false;

    /**
     * The BOMB pose knob, {@code cc.predict.bombPose} = {@code on|off},
     * DEFAULT ON: engine-LESS ballistic mints (free-fall bombs: {@code bomb_proj},
     * {@code clusterbomb_proj}) enroll as wire pose puppets instead of the skip. The skip
     * left the discharge-presented bomb in free LOCAL flight with a live warhead — client-only
     * armor damage where the local track diverged from the server's.
     * M1/M3 proved bombs cross as fully-SOURCED wire MISSILE tracks,
     * so the standard twin machinery presents them. Engine-SLOTTED ballistic
     * (powered unguided rockets) keeps today's local-flight law — per-ordnance fix, measured
     * class only. {@code off} = the old ballistic skip, byte-for-byte. Invalid values THROW.
     */
    static final boolean BOMB_POSE = parseOnOff("cc.predict.bombPose",
            System.getProperty("cc.predict.bombPose", "on"));

    /**
     * The MINE presentation-FX knob, {@code cc.predict.mineFx} =
     * {@code on|off}, DEFAULT ON: the sourceless-adopted (station-mine) body gets
     * (i) vanilla's own spawn visual — {@code fadeOutThenIn(1.0)} + the {@code mine_spawn}
     * sound, vanilla's StealthMinefield pair verbatim (the wire carries no phase/windup state,
     * its poseFlags = VALID|FIZZLING only, so spec timings are mirrored client-side)
     * — and (ii) det FX at the wire end-of-track when the last pose sits inside the
     * fuse envelope of a live enemy hull (the endClass-transition observable): the
     * {@code mine_explosion} sound + a damage-LESS visual explosion built from the
     * {@code minelayer_mine.proj} explosionSpec values. FX-only by construction — the
     * safety property (NOOP_AI, no local fuse, no {@code DamagingExplosion}) is untouched.
     * {@code off} = today's silent vanish. Invalid values THROW.
     */
    static final boolean MINE_FX = parseOnOff("cc.predict.mineFx",
            System.getProperty("cc.predict.mineFx", "on"));

    /** The spawn fade of vanilla's StealthMinefield, mirrored on the adopted body. */
    private static final float MINE_SPAWN_FADE_SECONDS = 1.0f;

    private static boolean parseOnOff(String knob, String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(value)) {
            return true;
        }
        if ("off".equals(value)) {
            return false;
        }
        throw new IllegalStateException(knob + " must be on|off; got '" + raw + "'");
    }

    /**
     * The CARRIER weapon id for a sourceless mint. The engine's
     * 7-arg {@code spawnProjectile} dereferences the
     * weapon arg ONLY to take its spec when none resolved, i.e. only when the
     * weaponId failed to resolve — so a null WeaponAPI is legal as long as this id resolves — and
     * when the 4th arg (projectile spec) is supplied it OVERWRITES the spec local, so the carrier's
     * own {@code getProjectileSpec()} is discarded and ANY resolvable weapon id would do.
     * {@code minelayer1} is the honest one: vanilla, always loaded, and literally the weapon
     * {@code StealthMinefieldLT.getWeaponId()} fires for this body (see {@code RealEngineStationProbeMain}).
     */
    private static final String SOURCELESS_CARRIER_WEAPON = "minelayer1";
    /** The stale-gate: a thrust puppet issues ACCELERATE only
     *  while its twin's last adopted fresh pose is at most this many server sim ticks old —
     *  past it the puppet COASTS (exactly the plain no-op puppet's behavior) until the next fresh pose lands.
     *  K=5 straddles the engine's own 5-no-accel-frame contrail interrupt:
     *  the normal fresh cadence never trips it, while stale gaps and
     *  post-server-death tails stop accelerating along stale facing — the seat-2
     *  differ-regression mechanism (adopt 73.2→106.9 su) this gate exists to remove. */
    private static final int THRUST_STALE_TICKS = 5;
    /** The thrust gate's one-time handle: the missile's OWN
     *  {@code getEngineLocations()} (public on the concrete class, populated from the spec's engine
     *  slots by the constructor)
     *  — the LITERAL list the engine's glow/contrail
     *  gate reads. The falsification: the API-side {@code getShipEngines()} component list
     *  is populated only under {@code isFighter()||isShip()} ⇒
     *  structurally EMPTY for every missile. {@code java.lang.invoke} = the loader-B legal lane
     *  (java.lang.reflect is B-denied; the HulkKeepAliveAdapter precedent). Resolve failure ⇒
     *  fail-CLOSED thrust=false (degrades to today's no-glow, never bomb-thrust) + one capped
     *  log at first install. */
    private static final java.lang.invoke.MethodHandle ENGINE_LOCATIONS;
    private static final Throwable ENGINE_LOCATIONS_FAILURE;

    static {
        java.lang.invoke.MethodHandle handle = null;
        Throwable failure = null;
        try {
            handle = java.lang.invoke.MethodHandles.lookup().findVirtual(
                    com.fs.starfarer.combat.entities.Missile.class, "getEngineLocations",
                    java.lang.invoke.MethodType.methodType(List.class));
        } catch (Throwable t) {
            failure = t;
        }
        ENGINE_LOCATIONS = handle;
        ENGINE_LOCATIONS_FAILURE = failure;
    }

    /** One-shot flag for the resolve-failure log (static handle, per-instance lane logger). */
    private static boolean thrustGateFailureLogged;

    /** The mote glow's TRUE seam, the fix for motes that rendered as bare little blue dots with zero trails.
     *
     *  An earlier premise was FALSE: {@code forceShowAccelerating()} only writes the
     *  50 ms decel-hysteresis timer; it never sets the per-frame
     *  accelerating flag. That flag is set ONLY by the controller's accelerate step,
     *  which the ACCELERATE command reaches after its velocity push and
     *  which the public {@code showAccelerating(float)} reaches with
     *  NO physics at all. The engine controller's advance then raises every slot's
     *  glow toward 1.0 and emits the contrail particle iff that
     *  flag is set — it reads no velocity, no acceleration, only the flag + facing + spec max speed,
     *  so a wire-driven pose is fully compatible. Vanilla's motes are lit exactly this way:
     *  {@code headInDirectionWithoutTurning}
     *  enqueues a REAL ACCELERATE on the frames where forward thrust along
     *  the (spinning) facing best serves the flock direction (±45° arc), strafe/back/decel
     *  otherwise (no main-engine light) — the trailing {@code forceShowAccelerating()}
     *  merely re-permits the next accelerate-visual after those. The controller's concrete class is
     *  named the Java keyword {@code null} (never a class literal, never in the jar — B-deny-safe by
     *  construction), so the handle is resolved from the LIVE controller's runtime class at the
     *  first mote frame. {@code Missile.interruptContrail()} = the engine's own 6-frame
     *  contrail chop, which only ever runs for a missile that has enqueued
     *  ACCELERATE at least once ({@code canIncr}) — a puppet never does, so the puppet
     *  reproduces the chop itself (else one unbroken ribbon grows into a CONE). */
    private static final java.lang.invoke.MethodHandle INTERRUPT_CONTRAIL;
    private static final Throwable INTERRUPT_CONTRAIL_FAILURE;

    static {
        java.lang.invoke.MethodHandle handle = null;
        Throwable failure = null;
        try {
            handle = java.lang.invoke.MethodHandles.lookup().findVirtual(
                    com.fs.starfarer.combat.entities.Missile.class, "interruptContrail",
                    java.lang.invoke.MethodType.methodType(void.class));
        } catch (Throwable t) {
            failure = t;
        }
        INTERRUPT_CONTRAIL = handle;
        INTERRUPT_CONTRAIL_FAILURE = failure;
    }

    /** {@code showAccelerating(float)} on the live controller's runtime class (see above). Render
     *  thread only; resolved once; a failed resolve stays null (the mote degrades to the bare 0.4
     *  idle glow, counted + announced once — never silent). */
    private static java.lang.invoke.MethodHandle showAccelerating;
    private static boolean showAcceleratingTried;
    private static Throwable showAcceleratingFailure;

    static java.lang.invoke.MethodHandle showAcceleratingHandle(Object controller) {
        if (!showAcceleratingTried) {
            showAcceleratingTried = true;
            try {
                showAccelerating = java.lang.invoke.MethodHandles.lookup().findVirtual(
                        controller.getClass(), "showAccelerating",
                        java.lang.invoke.MethodType.methodType(void.class, float.class));
            } catch (Throwable t) {
                showAcceleratingFailure = t;
            }
        }
        return showAccelerating;
    }

    /** The NO-POSE fallback only. The live mote light is the wire's
     *  {@code POSE_FLAG_ACCELERATING} bit (the server engine's own per-frame ACCELERATE decision,
     *  minted for mote specs) — this arc gate (facing within ±45° of the VELOCITY bearing) rode
     *  jar {@code C10EC2B6} and lit every served mote in every frame read: vanilla's
     *  steering compares projected velocity GAINS toward the flock's desired
     *  direction, so a mote at max speed cruising along it thrusts nothing and goes dark, and the
     *  mote spins 400°/s under a permanent TURN command — neither is derivable from the wire pose.
     *  Kept for sentinel-pose records ({@code !hasPose()}), where the wire carries no bit. */
    private static final float MOTE_THRUST_ARC_DEG = 45f;
    /** Below this speed the velocity bearing is noise — the last good bearing is kept. */
    private static final float MOTE_DIR_MIN_SPEED = 20f;

    /** The AI-only no-op puppet plugin (engine-LESS specs — bombs — whose physics must stay
     *  byte-identical): {@code setMissileAI} wraps it in the engine's plain
     *  {@code MissileAIWrapper} (NOT GuidedMissileAI), so the
     *  install itself flips {@code isGuided()} FALSE and the FlareAI seduction gate
     *  (GuidedMissileAI victims only) can never touch a puppet. Stateless ⇒ shared instance. */
    private static final MissileAIPlugin NOOP_AI = new MissileAIPlugin() {
        @Override
        public void advance(float amount) {
        }
    };

    /** The thrust puppet AI, for engine-slotted specs: issues the public
     *  {@code giveCommand(ACCELERATE)} each advance, byte-equivalent to the internal enqueue
     *  every enrolled class's real AI performs unconditionally in powered flight:
     * the ACCELERATE-driven engine flame/glow
     *  lights and the engine's contrail interrupt never fires. Same wrapper class as
     *  {@link #NOOP_AI} ⇒ the {@code isGuided()} flip and seduction guard hold identically.
     *  Per-missile instance: the api {@code advance(float)} carries no missile ref. */
    private static final class ThrustPuppetAI implements MissileAIPlugin {
        private final MissilePoseLane owner;
        private final MissileAPI missile;
        /** Local sim seconds since the twin's last adopted fresh pose ({@code advance}
         *  accumulates; {@link #touchFresh} zeroes). Starts fresh by design: both install
         *  sites sit on a fresh wire event (enroll = the present tail, adopt = the 2nd
         *  sighting), and an enrolled twin that never links retires at the link window. */
        private float sinceFreshSeconds;
        /** One count per fresh→stale transition (not per coast frame). */
        private boolean staleLatched;
        /** A Ziggurat mote — light the glow the way the engine lights it
         *  ({@code showAccelerating(dt)} = the flag ACCELERATE sets, minus its physics), on the
         *  frames vanilla's steering would pick forward thrust; see {@link #INTERRUPT_CONTRAIL}. */
        private final boolean mote;
        /** Last good motion bearing (deg) for the thrust-arc gate; NaN until the first fresh pose
         *  carries speed — until then every frame is lit (vanilla's birth burst). */
        private float moteDirDeg = Float.NaN;
        /** Frames since the last lit frame — the engine's own {@code noAccelFrames}. */
        private int moteUnlitFrames;
        /** The last fresh pose carried a VALID wire pose ⇒ {@link #wireLit} is the server
         *  engine's ACCELERATE decision for that tick and the arc gate is bypassed. */
        private boolean wireLitKnown;
        private boolean wireLit;
        /** The light decision of the most recent mote frame (the census reads it). */
        boolean litNow;
        /** A decoy FLARE — the mote's display-only light, driven by the
         *  same wire bit (the server mints it for {@code isFlare()} specs). Vanilla
         *  lights a flare on its AI's OWN duty (SeekerFlareAI: a dark 0.5–1 s birth window, then
         *  gain-gated thrust while homing; FlareAI: every powered frame); the plain
         *  thrust puppet pinned ACCELERATE on 100 % of frames — glow stuck at 1.0, one unbroken
         *  GLOW ribbon, plus a physics push onto a wire-driven pose (the old mote recipe). */
        private final boolean flare;
        /** Sim seconds since the flare's last lit frame. The chop counts SERVER ticks, not
         *  client frames: the server body chops after 6 unlit frames of ITS 60 Hz loop;
         *  at the client's ~500 fps six frames is 12 ms, under one tick. */
        private float flareUnlitSeconds;

        ThrustPuppetAI(MissilePoseLane owner, MissileAPI missile, boolean mote, boolean flare) {
            this.owner = owner;
            this.missile = missile;
            this.mote = mote;
            this.flare = flare;
        }

        /** A fresh adopted pose re-arms thrust (called from {@code adoptPose}); a mote puppet
         *  also takes the record's wire ACCELERATING bit as its light until the next fresh pose.
         *  The latch follows EACH fresh record — a sentinel-pose
         *  record (no VALID bit) CLEARS it, so the arc fallback (and the {@code litArc} diagnostic
         *  it feeds) stays reachable instead of replaying a stale {@code wireLit}. */
        void touchFresh(ServerMissileState rec) {
            sinceFreshSeconds = 0f;
            staleLatched = false;
            if (mote || flare) {
                wireLitKnown = rec != null && rec.hasPose();
                wireLit = wireLitKnown && rec.accelerating();
            }
        }

        @Override
        public void advance(float amount) {
            try {
                sinceFreshSeconds += amount;
                if (sinceFreshSeconds * ServerTickRate.ticksPerSecond() > THRUST_STALE_TICKS) {
                    if (!staleLatched) {
                        staleLatched = true;
                        owner.thrustStaleGaps++;
                    }
                    litNow = false;     // A coasting mote is UNLIT for the census
                    return; // Stale pose — coast exactly as pre-fix
                }
                if (mote) {
                    // a fizzling mote shows the spec's own flameout (vanilla's mote AI skips
                    // everything while isFizzling()).
                    if (!missile.isFizzling()) {
                        advanceMote(amount);
                    } else {
                        litNow = false; // The flameout is UNLIT for the census
                    }
                    return;
                }
                if (missile.isFizzling()) {
                    return; // Vanilla missile AIs all skip while fizzling — no
                            //   thrust/glow on a flamed-out body riding out its fade
                }
                if (flare) {
                    // display-only for LIFE, the pre-link frames included — ONE enqueued
                    // ACCELERATE arms the engine's own chop (canIncr), which then
                    // counts every command-less display frame as unlit and shreds the ribbon.
                    advanceFlare(amount);
                    return;
                }
                missile.giveCommand(ShipCommand.ACCELERATE);
            } catch (Throwable ignore) {
                // presentation lane; never break the watch
            }
        }

        /** The mote frame. LIT frames = the engine's exact accelerate-visual with no velocity
         *  write; UNLIT frames = the engine's own 6-frame contrail chop, so each lit burst becomes
         *  ONE short ribbon that grows into vanilla's soft puff (mote.proj sizeMult 10 / 2 s)
         *  instead of one unbroken cone. */
        private void advanceMote(float amount) throws Throwable {
            // The wire bit is the light; the arc gate only when the record had no pose.
            boolean lit = wireLitKnown ? wireLit : moteWantsThrust();
            // Resolve the controller BEFORE the meters — a controller-less mote
            // has nothing to light, and the swallowed NPE used to leave the lit meters over-counted.
            ShipEngineControllerAPI ec = lit ? missile.getEngineController() : null;
            if (lit && ec == null) {
                lit = false;
            }
            litNow = lit;
            if (lit) {
                owner.moteLitFrames++;
                if (wireLitKnown) {
                    owner.moteLitWire++;
                } else {
                    owner.moteLitArc++;
                }
            } else {
                owner.moteUnlitFrames++;
            }
            if (lit) {
                moteUnlitFrames = 0;
                ec.forceShowAccelerating();          // the 50 ms hysteresis override, as vanilla's mote AI calls it
                java.lang.invoke.MethodHandle h = showAcceleratingHandle(ec);
                if (h != null) {
                    h.invoke(ec, amount);            // the flag, not the push
                } else {
                    owner.noteMoteGlowHandleMissing();
                }
            } else {
                moteUnlitFrames++;
                if (moteUnlitFrames > 5) {           // the engine's own chop rule, verbatim
                    moteUnlitFrames = 0;
                    if (INTERRUPT_CONTRAIL != null) {
                        INTERRUPT_CONTRAIL.invoke(missile);
                    }
                }
            }
        }

        /** The flare frame = {@link #advanceMote}'s two writes on the wire bit ALONE — no arc
         *  fallback: a flare with no pose yet (pre-link, sentinel record) is UNLIT = the idle
         *  0.4 glow, which is also SeekerFlareAI's dark birth window. A source that never mints
         *  the bit (a reel recorded before flares carried it, through the reel feed) therefore shows idle-glow flares.
         *  LIT = the engine's accelerate-visual, no velocity write; UNLIT = the engine's own
         *  contrail chop once the server body would have chopped (6 unlit TICKS). */
        private void advanceFlare(float amount) throws Throwable {
            boolean lit = wireLitKnown && wireLit;
            ShipEngineControllerAPI ec = lit ? missile.getEngineController() : null;
            if (lit && ec == null) {
                lit = false;
            }
            if (lit) {
                owner.flareLitFrames++;
                flareUnlitSeconds = 0f;
                ec.forceShowAccelerating();          // the hysteresis override, as vanilla's SeekerFlareAI calls it
                java.lang.invoke.MethodHandle h = showAcceleratingHandle(ec);
                if (h != null) {
                    h.invoke(ec, amount);            // the flag, not the push
                } else {
                    owner.noteMoteGlowHandleMissing();
                }
            } else {
                owner.flareUnlitFrames++;
                flareUnlitSeconds += amount;
                if (flareUnlitSeconds * ServerTickRate.ticksPerSecond() > 5f) {
                    flareUnlitSeconds = 0f;
                    if (INTERRUPT_CONTRAIL != null) {
                        INTERRUPT_CONTRAIL.invoke(missile);
                    }
                }
            }
        }

        /** Vanilla's per-frame steering choice, reproduced from what the wire carries: forward
         *  thrust wins while the facing lies within {@link #MOTE_THRUST_ARC_DEG} of the motion
         *  bearing. No bearing yet ⇒ lit (birth burst). */
        private boolean moteWantsThrust() {
            Vector2f v = missile.getVelocity();
            if (v != null && v.length() >= MOTE_DIR_MIN_SPEED) {
                moteDirDeg = (float) Math.toDegrees(Math.atan2(v.y, v.x));
            }
            if (Float.isNaN(moteDirDeg)) {
                return true;
            }
            float diff = missile.getFacing() - moteDirDeg;
            diff = ((diff % 360f) + 540f) % 360f - 180f;    // → [-180, 180)
            return Math.abs(diff) <= MOTE_THRUST_ARC_DEG;
        }
    }

    /** True when the lane replaces guided-missile AIs (the presenter's seeker-handoff guard). */
    static boolean puppetsGuided() {
        return MISSILE_POSE_APPLY;
    }

    /** The owning plugin — engine/session/shape/registry + the exclusion set. */
    final PredictReconcileCombatPlugin plugin;
    /** The lane's capped per-event logger (the PredictLane discipline). FOE pose
     *  lines only — the shared-budget coupling was retired after it starved 88% of
     *  225837's own-handoff forensics; the own lane logs through its own {@code lane}. */
    final PredictReconcileCombatPlugin.PredictLane lane =
            new PredictReconcileCombatPlugin.PredictLane(MISSILE_LOG_MAX);
    /** The MINE rows' own capped logger (foe/sourceless mine adopt, link,
     *  local-end, absence, and every {@link MineBombEndFxLane} end row); {@link #lane} keeps the
     *  non-mine foe pose rows. */
    final PredictReconcileCombatPlugin.PredictLane mineLane =
            new PredictReconcileCombatPlugin.PredictLane(MINE_LOG_MAX);

    /** The row's budget by twin class: a native-fuse mine or a sourceless-adopted (station/foe
     *  mine) body logs on {@link #mineLane}, everything else on {@link #lane}. */
    PredictReconcileCombatPlugin.PredictLane laneFor(Twin t) {
        return t != null && (t.mine || t.sourcelessMine) ? mineLane : lane;
    }
    /** The own-missile HANDOFF collaborator (the ARMED deferred-refactors MPL
     *  split, cut re-adjudicated at pay time): own-only knobs/counters/tombstones + the
     *  enroll/drive/handoff/fade law live there; this class keeps the shared Twin ledger,
     *  the iteration passes, and thin dispatches (backref coupling, the FFC idiom). */
    final OwnMissileHandoffLane own = new OwnMissileHandoffLane(this);
    /** The SPLIT / ballistic-children PRESENTATION collaborator:
     *  the split moment, the
     *  children completion, the cause partition and the child-recipe stash live
     *  there; this class keeps the shared Twin ledger, the iteration passes, and thin relays
     *  (backref coupling, the FFC idiom — same shape as {@link #own}). */
    final MissileSplitLane split = new MissileSplitLane(this);
    /** The DRONE-STRIKE conversion FX collaborator (NEW-LANE LAW; knob
     *  {@code cc.predict.droneStrikeFx}): the {@code terminator_missile} sourceless class (fake
     *  carrier weapon ⇒ the ECB identity scan degrades its pair to (0,-1)) carves OUT of the
     *  mine treatment in {@link #adoptSourceless} and gets vanilla's conversion presentation
     *  instead — without the carve every drone strike plays mine spawn/det FX. */
    final DroneStrikeFxLane droneStrike = new DroneStrikeFxLane(this);

    /** The mine/bomb END-OF-LIFE presentation collaborator, split out of this
     *  class: the
     *  kind-6 WireEnd store + intake, the prime/windup reconstruction, the det
     *  classifier + mine/bomb end FX and the impact sweeps live there; this class keeps
     *  the shared Twin ledger (prime state rides Twin fields), the iteration passes, and thin
     *  dispatches (backref coupling, the FFC idiom — same shape as {@link #own}/{@link #split}). */
    final MineBombEndFxLane end = new MineBombEndFxLane(this);

    /** The SOURCED end-confession collaborator (GPEE v7):
     *  same-tick puppet retire + generic foe-impact FX for sourced kind-6 ends; the sourceless
     *  family stays on the {@link #end} lane's absence-time arcs (law + scope live there). */
    final SourcedMissileEndLane sourcedEnd = new SourcedMissileEndLane(this);

    /** The SKIP-SET end-confession
     *  link/removal collaborator: engine-slotted unguided ordnance (reaper class) flies
     *  pure-local per the ballistic skip, so its GPEE v7 MISSILE_END confession needs a
     *  pair-FIFO wire-id link before the end can force the local bolt out (law + scope there). */
    final BallisticEndLinkLane ballistic = new BallisticEndLinkLane(this);

    /** One enrolled puppet ledger row (the DamageWitness {@code tracked} identity idiom).
     *  Package-private: the {@link OwnMissileHandoffLane} collaborator operates on
     *  the SAME shared ledger rows (the ChatLane visibility precedent). */
    static final class Twin {
        final MissileAPI missile;
        final long sourceEntityId;
        final int sourceMountIndex;
        final long spawnTick;
        final boolean flare;
        /** The local body's own projectile spec id (public {@code DamagingProjectileAPI} read;
         *  null = unreadable, fail-open) — the link-hardening term. */
        final String specId;
        long wireId = -1L; // unlinked until a stable id claims it
        float lastWireX;
        float lastWireY;
        long lastWireTick = Long.MIN_VALUE;
        int missedFresh;
        /** The installed thrust puppet (null for NOOP installs / meter mode) — the
         *  stale-gate's freshness hookup: {@code adoptPose} touches it per fresh pose. */
        ThrustPuppetAI thrustAi;
        /** one-shot fizzle-relay latch: the wire FIZZLING bit already played this
         *  twin's local {@code flameOut()} death arc. */
        boolean fizzled;
        /** The DUD verdict last written to the body by a relay — a fresh
         *  FIZZLING record whose bit differs re-applies the arming (the server mints {@code !isArmed()}
         *  every frame; it moves with the arming clock). Meaningful only under {@link #fizzled}. */
        boolean dudApplied;
        /** The belt kept this dud collidable once already —
         *  {@code predict_missile_bounce_kept} counts twins, not frames. */
        boolean bounceCounted;
        /** The prox-charge engine door installed the engine's own fuse +
         *  {@code flameOut()} on this body: {@link #retire} must leave it to the engine (the fuse's
         *  fade trigger removes it with the real det ~2 frames later; a flamed-out body that
         *  somehow never dets still expires natively at fizzle+fade). */
        boolean engineDetPending;
        /** TRUE for a link-only OWN twin (local-real body, seeker KEPT): the
         *  corrector nudges instead of pose-adopting, and the body is NEVER removed by the
         *  lane (terminal = flameOut fade). */
        final boolean own;
        /** The per-frame presented-minus-wire offset
         *  (su; apply mode only): projection lead + blend residual, written each post-handoff
         *  adopt frame ({@code OwnMissileHandoffLane.presentPose}); rides the cc-mtrack pos
         *  lines as {@code cdx=}/{@code cdy=} so the differ recovers RAW wire divergence by
         *  subtraction (additive tokens — mtrack samples AFTER lane writes). */
        float cdx;
        float cdy;
        /** {@code cdx}/{@code cdy} carry a real prior-frame baseline
         *  (set by the first {@code presentPose} that writes them). The first sample is SEEDING,
         *  never a d|cd|/dt measurement: without this the initial offset would read as one frame
         *  of ~900–2700 su/s correction per handoff twin (bookkeeping, not motion). */
        boolean corrSeeded;
        /** The per-twin measured projection horizon Δ = linkTick − spawnTick
         *  (clamped [0,15]t, captured at {@code onLinked}): birth fork + transport + interp
         *  delay in client-visible tick space (the 6–9t budget, per twin). */
        int horizonTicks;
        /** Wire chord per tick (the projection basis): seeded at X
         *  (local-velocity fallback when no prior wire sample), refreshed each fresh
         *  post-handoff frame. */
        float chordXPt;
        float chordYPt;
        /** Facing offset captured at X ({@code norm180(local − wire)}):
         *  presented facing = wire + offset × f over the blend window; zeroed on pop and at
         *  window expiry. */
        float facingBlendDeg;
        /** TRUE for an own twin whose factory spec carries behavior params (MIRV
         *  family): vanilla splits it LOCALLY (seeker kept), so it is handoff-EXEMPT — pure
         *  local flight + split release, never puppeted (its wire-side children are
         *  own-sourced ids, which presence-adopt refuses). */
        boolean splitCapable;
        /** TRUE for an own twin latched to the {@code link} policy at enroll
         *  (PUPPET@LINK: handoff on the first linked frame, hz 0, blend 0, facing raw,
         *  {@code cd ≡ 0} for life); FALSE = today's {@code handoff} hybrid. Latched — the
         *  A/B toggle never touches an in-flight twin. */
        boolean policyLink;
        /** this twin already counted in the missile-cut snap counter
         *  ({@code ownNudgeSnapMissiles}); the frame-cut {@code ownNudgeSnaps} keeps its
         *  legacy semantics for cross-run comparability. */
        boolean snapLatched;
        /** The hybrid handoff fired: puppet AI installed, wire-driven from here. */
        boolean handedOff;
        /** TRUE for a sourceless-adopted (station-mine) body: its
         *  end-of-track runs the det-FX classifier instead of the silent remove. Set only in
         *  {@code adoptSourceless}. */
        boolean sourcelessMine;
        /** The sourceless adopt minted with NO wire
         *  source replica ({@code sourceShip == null}: the engine substituted its synthetic source).
         *  A script-bomb END on it paints (M11) instead of running a wrong-sided engine door. */
        boolean mintSourceNull;
        /** conversion-twin forensics (droneconv twins only; the round-6 no-silent-lane
         *  audit): closest wire-pose approach to the own ship over the twin's adopted life (su;
         *  MAX_VALUE = never sampled), and when. */
        float convMinDistOwn = Float.MAX_VALUE;
        long convMinDistTick = Long.MIN_VALUE;
        /** First wire tick the record crossed FIZZLING (MIN_VALUE = never) — measures whether
         *  wire fizzle EVER crosses for this class (the open question; the birth-fizzle
         *  premise was never directly measured). */
        long convWireFizzTick = Long.MIN_VALUE;
        /** First wire tick the LOCAL body read isFizzling() (MIN_VALUE = never) — the engine's
         *  own local range/time fizzle tell (its range trigger). */
        long convLocalFizzTick = Long.MIN_VALUE;
        /** Mine prime detection — the stop-bubble anchor: last pose the mine was
         *  seen OUTSIDE {@code MINE_STOP_EPS_SU} of, and when. Anchored at first pose; every
         *  move past the bubble re-anchors and sets {@code everMoved}. */
        float refX;
        float refY;
        long refTick = Long.MIN_VALUE;
        /** The mine left its spawn bubble at least once — a never-moved end can't be graded
         *  primed-by-stop (spawn-primed residue; classifier falls to the near test). */
        boolean everMoved;
        /** Wire tick the stop was CONFIRMED at ({@code MINE_STOP_CONFIRM_SECONDS} inside the
         *  bubble) = the client's prime instant; MIN_VALUE = never primed. */
        long primedTick = Long.MIN_VALUE;
        /** One-shot {@code mine_windup_heavy} latch for the primed sequence. */
        boolean windupPlayed;
        /** one-shot canister end-CLASSIFIER latch: every exit (absence end OR
         *  local prune) dispatches the classifier; the first run wins, and a quiet verdict
         *  (wire DESTROYED) latches too — classified-once, not burst-once. */
        boolean canisterEndClassified;
        /** Wire tick the handoff fired at (blend-decay time base). */
        long handoffTick;
        /** The residual fork at handoff (local − wire, su): presented as a linearly decaying
         *  offset on top of the wire pose over {@link OwnMissileHandoffLane#OWN_HANDOFF_BLEND_TICKS}. */
        float blendX;
        float blendY;

        /** TRUE for a PHASE_MINE body ({@code MissileAPI.isMine()}, read
         *  once at mint): the twin KEEPS the engine's own {@code GuidedProximityFuseAI} (never a
         *  puppet install, never the 1e9 flight-time park, never the FIZZLING flameOut relay),
         *  so the vanilla ping / 3 s fuse / windup / self-det run on the local body, and the
         *  wire END goes through {@code MissileAPI.explode()} = the engine's own removal +
         *  {@code DamagingExplosion} (FX, sound, damage to local bodies incl. the own ship).
         *  The NOOP puppet on a mine killed all of that AND the explode() door
         *  (it gates on {@code getAI() instanceof GuidedProximityFuseAI}). */
        final boolean mine;

        Twin(MissileAPI missile, long sourceEntityId, int sourceMountIndex, long spawnTick,
                boolean flare, String specId, boolean own) {
            this.missile = missile;
            this.sourceEntityId = sourceEntityId;
            this.sourceMountIndex = sourceMountIndex;
            this.spawnTick = spawnTick;
            this.flare = flare;
            this.specId = specId;
            this.own = own;
            this.mine = keepsNativeAi(missile);
        }
    }

    /** The native-fuse discriminator: a mine body keeps its engine AI
     *  through link/handoff/adopt. Public API read ({@code isMine()}); an unreadable body
     *  degrades to FALSE = the standing puppet install (a seeker left running on a wire puppet
     *  is the worse failure for every non-mine class). Static + pure: the selftest row. */
    static boolean keepsNativeAi(MissileAPI missile) {
        try {
            return missile != null && missile.isMine();
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** The (own,-1) funnel's link key: every Doom mine of a burst shares
     *  ONE source pair and the records carry no spawn tick, so the lowest-id claim cross-paired
     *  local #1 with wire #3 whenever a lower id was a frame late (forks of
     *  150–810 su = the inter-use cursor travel). The local newborn and its true wire twin are
     *  the same placement (± the two sides' findClearLocation jitter, ≲150 su), so NEAREST
     *  wins; ties (and a body with no readable location) fall back to the lowest id — the
     *  candidates arrive id-ascending. Static + pure: the selftest row. */
    static ServerMissileState nearestTo(float x, float y, List<ServerMissileState> candidates) {
        ServerMissileState best = null;
        float bestD2 = Float.MAX_VALUE;
        for (ServerMissileState rec : candidates) {
            float dx = rec.posX() - x;
            float dy = rec.posY() - y;
            float d2 = dx * dx + dy * dy;
            if (best == null || d2 < bestD2) {
                best = rec;
                bestD2 = d2;
            }
        }
        return best;
    }

    /** A presence-adopt candidate's buffered 1st sighting (2nd sighting spawns with exact vel). */
    private static final class Sighting {
        final float x;
        final float y;
        final long tick;

        Sighting(float x, float y, long tick) {
            this.x = x;
            this.y = y;
            this.tick = tick;
        }
    }

    // === Lane state (render-thread only; per-round, cleared at resetForMatchReset) ===
    final IdentityHashMap<MissileAPI, Twin> twins = new IdentityHashMap<MissileAPI, Twin>();
    final Map<Long, Twin> twinByWireId = new HashMap<Long, Twin>();
    /** Package-private: the {@link MissileSplitLane} collaborator prunes the parent's
     *  sighting at the split retire (the shared-ledger visibility precedent). */
    final Map<Long, Sighting> sightings = new HashMap<Long, Sighting>();
    /** Source pairs classified ballistic (enroll or probe) — presence-adopt skips them wholesale
     *  (the double-present guard: their local bodies fly un-puppeted). */
    private final Set<Long> ballisticPairs = new HashSet<Long>();
    /** The dict-defer start tick by wire id (pruned with the live-id set each frame). */
    private final Map<Long, Long> dictDefer = new HashMap<Long, Long>();
    /** presence-adopt guard: wire ids seen FIZZLING (pruned with the live-id set) — a
     *  fizzling record never mints, or the faded local body would respawn at full glow via
     *  pruneLocalGone→presenceAdopt while the wire still tracks the drifting server dud. */
    private final Set<Long> fizzledIds = new HashSet<Long>();
    /** Every wire id EVER claimed by a twin this round (matcher claim or
     *  presence-adopt mint). The server mint is per-lifetime monotone and never recycled
     *  ({@code EngineCombatBinding.missileStableIdFor} — identity-keyed, counter never resets),
     *  so a record reappearing under a consumed id is ALWAYS the old missile back from a feed
     *  gap, never a fresh discharge's twin. The link matcher refuses these (the
     *  instant-mislink class: a fresh missile teleports 576–1482 su off the rack onto the old
     *  record); presence-adopt stays free to re-present them (the self-heal — correct
     *  for foe ids; own-sourced ids never presence-adopt, so a refused OWN reappearance stays
     *  feed-only until it ends, logged LOUD below — the lesser, honest degrade). NOT GC'd with
     *  the live-id set (the feed gap is exactly what it must survive); cleared at round reset. */
    private final Set<Long> consumedWireIds = new HashSet<Long>();
    /** One LOUD refusal line per wire id (the counter still ticks per refused frame — the
     *  {@link #linkCrossSpec} per-frame idiom). */
    private final Set<Long> consumedRefusalLogged = new HashSet<Long>();

    // === Cumulative counters (survive rounds — the FoeHull totals discipline) ===
    private int enrolledGuided;      // predict_missile_enrolled_guided
    private int enrolledFlare;       // predict_missile_enrolled_flare
    private int enrolledBomb;        // predict_missile_enrolled_bomb (engine-less
                                     //   ballistic enrolls — carved OUT of enroll_skip_ballistic,
                                     //   which now counts only the still-skipped engine-slotted/
                                     //   unresolvable class; the 45(f) counter-split discipline)
    private int enrollSkipBallistic; // predict_missile_enroll_skip_ballistic
    private int noopInstalls;        // predict_missile_noop_installs (apply; ALL puppet AI installs)
    private int thrustInstalls;      // predict_missile_thrust_installs (the engine-slotted subset)
    /** Mote frames that wanted the light but had no {@code showAccelerating} handle (resolve
     *  failed) — announced once, counted forever; a non-zero value = the bare-dot look is BACK. */
    private int moteGlowHandleMissing;

    void noteMoteGlowHandleMissing() {
        moteGlowHandleMissing++;
        if (moteGlowHandleMissing == 1) {
            lane.log("predict v13 mote glow: showAccelerating handle MISSING — motes ride the bare "
                    + "0.4 idle glow: " + showAcceleratingFailure);
            FallbackAnnounce.announceOnce("f8-mote-glow-handle-missing",
                    "showAccelerating resolve failed -> bare motes: " + showAcceleratingFailure);
        }
    }

    private int moteGlowInstalls;    // predict_missile_mote_glow_installs (mote glow puppet;
                                     //   was predict_missile_mote_noop_installs, the earlier NOOP carve-out)
    /** Mote light duty meters: puppet frames lit / unlit (duty = lit/(lit+unlit)); {@code litWire}
     *  = lit frames driven by the wire ACCELERATING bit, {@code litArc} = by the no-pose arc fallback
     *  (a non-zero litArc on a v16+ serve = sentinel-pose records reaching a mote puppet). */
    private int moteLitFrames;       // predict_missile_mote_lit_frames
    private int moteUnlitFrames;     // predict_missile_mote_unlit_frames
    private int moteLitWire;         // predict_missile_mote_lit_wire
    private int moteLitArc;          // predict_missile_mote_lit_arc
    private int flareGlowInstalls;   // predict_missile_flare_glow_installs
    private int flareLitFrames;      // predict_missile_flare_lit_frames
    private int flareUnlitFrames;    // predict_missile_flare_unlit_frames

    /** The most recent light decision of the mote puppet riding {@code m} (false when no
     *  puppet rides it) — {@link OwnMoteCensusLane}'s served-side {@code lit=N/M} term. */
    boolean moteLitNow(MissileAPI m) {
        Twin t = twins.get(m);
        return t != null && t.thrustAi != null && t.thrustAi.litNow;
    }
    private int thrustStaleGaps;     // predict_missile_thrust_stale_gaps (fresh→stale coasts)
    private int linked;              // predict_missile_linked
    private int linkExpired;         // predict_missile_link_expired (unlinked past window)
    private int linkCrossSpec;       // predict_missile_link_cross_spec (mislink rejects)
    private int linkRefusedConsumed; // predict_missile_link_refused_consumed (the
                                     //   freshness guard: matching claim refused, id already
                                     //   consumed by an earlier twin; per-frame like cross_spec)
    private int poseFrames;          // predict_missile_pose_frames (twin-frames with a record)
    private int poseSets;            // predict_missile_pose_sets (apply writes)
    private int poseFacingSets;      // predict_missile_pose_facing_sets (facing :=
                                     //   v16 wire pose truth; retires beam_facing_sets)
    private int fizzleRelays;        // predict_missile_fizzle_relays (one-shot FIZZLING
                                     //   flameOut death-arc relays)
    private int fizzleRelaysParked;  // predict_missile_fizzle_relay_parked (relays that
                                     //   found flightTime already parked AT the puppet cap by the
                                     //   engine's LOCAL fizzleOnReachingWeaponRange flameOut —
                                     //   own + foe; the pre-fix silent no-op = the "winks out" class)
    private int fizzleFadeIntangible;// predict_missile_fizzle_fade_intangible (the fade belt:
                                     //   fading puppets re-held at CollisionClass.NONE)
    // the foe relay's wire DUD split + the belt's kept duds.
    private int dudRelayed;          // predict_missile_dud_relayed (relays whose record said DUD)
    private int armedRelayed;        // predict_missile_armed_relayed (no DUD bit = armed)
    private int bounceKept;          // predict_missile_bounce_kept (dud twins left collidable)
    private int dudFlips;            // predict_missile_dud_flips (the bit moved after
                                     //   the relay latch — arming clock — and was re-applied)
    private int presenceAdopts;      // predict_missile_presence_adopts (spawned+kept)
    private int adoptProbeBallistic; // predict_missile_adopt_probe_ballistic (probe removed)
    private int adoptUnresolved;     // predict_missile_adopt_unresolved (counted-unpresented)
    private int adoptFxFlash;        // predict_event_rebase_adopt_fx_flash (launch smoke)
    private int adoptFxSound;        // predict_event_rebase_adopt_fx_sound (launch sound)
    private int adoptFxSkipped;      // predict_event_rebase_adopt_fx_skipped (not a launch instant,
                                     //   or a fail-soft FX piece — the presentFireFx skip idiom)
    private int specUnresolved;      // predict_missile_spec_unresolved (adopt fell to launcher recipe)
    private int recipeRemints;       // predict_missile_recipe_remints (MIRV-child true-spec remints
                                     //   + sourceless bodies minted through a launcher recipe)
    private int endRemoved;          // predict_missile_end_removed (authoritative-end removals)
    private int localGone;           // predict_missile_local_gone (PD-killed puppet pruned)
    // The predict_missile_split_* / _child_override_* counters live in MissileSplitLane.
    private int dictDeferred;        // predict_missile_dict_deferred (defer starts)
    private int dictDeferOk;         // predict_missile_dict_defer_ok (entry landed inside the window)
    private int dictDeferExpired;    // predict_missile_dict_defer_expired (window out -> recipe)
    // The sourceless (station-minefield) adopt arm.
    private int sourcelessAdopts;    // predict_missile_sourceless_adopts (spawned+kept)
    private int sourcelessPresenceAdopts; // predict_missile_sourceless_presence_adopts —
                                     //   sourceless adopts that ALSO bumped presenceAdopts (the
                                     //   apply-mode pair; subtracted from the
                                     //   starvation feed, which promises SOURCED mints only)
    private int sourcelessSkipped;   // predict_missile_sourceless_skipped (knob off)
    private int sourcelessUnresolved;// predict_missile_sourceless_unresolved (counted-unpresented)
    private int sourcelessFirstAdopts;// predict_missile_sourceless_first_adopts (the
                                     //   conversion-spec adopt on FIRST sighting — droneconv feed)
    /** predict_missile_sourceless_zero_dtick:
     *  apply-mode sourceless adopts whose sighting pair shared a tick (velocity guarded to 0). */
    private int sourcelessZeroDtick;
    /** predict_missile_sourceless_adopts_round: the
     *  ROUND-scoped twin of {@code sourcelessAdopts} (bumped beside it at both adopt arms, zeroed in
     *  {@link #resetForMatchReset} — reached from PRCP {@code finishMatchReset}, the same method that
     *  zeroes the C32 residual {@code presentedFoeProj} + {@code projSweptPresented}), so the
     *  watch-verdict residual bar subtracts a round count from a round residual (the whole-match
     *  token stays for its other readers). predict_missile_sourceless_recipe_threw: sourceless
     *  recipe mints that threw and FELL THROUGH to the carrier mint. predict_missile_sourceless_
     *  threw_refused: re-offers of an id whose carrier mint threw, refused before a new sighting
     *  (one unstable_fragment_missile id re-minted + threw every other tick). */
    private int sourcelessAdoptsRound;
    private int sourcelessRecipeThrew;
    private int sourcelessThrewRefused;
    /** The ids behind {@code sourcelessThrewRefused}; pruned against the live-id set with the
     *  sibling per-id maps each presence pass, cleared at the round reset. */
    private final java.util.Set<Long> sourcelessThrewIds = new java.util.HashSet<>();
    private int sourcelessOwnDegraded;// predict_missile_sourceless_own_degraded (the OWN
                                     //   guard: own-sourced id degraded to sourceless — never adopted)
    /** The OWN-guard memory:
     * wire ids seen OWN-SOURCED, kept so a later degrade to the (0,-1) sentinel
     *  (firer death nulls the server-side {@code getWeapon()} pair read) cannot slip an OWN body
     *  through the sourceless adopt arm and twin-double the still-flying native local round.
     *  Bounded by the same live-id retain as the other per-id maps. */
    private final java.util.Set<Long> ownSourcedWireIds = new java.util.HashSet<>();
    /** Mine wire ids whose LOCAL body ended before the wire end (its own
     *  fuse detonated, or a local kill), by the local tick: the sourceless arm never re-adopts
     *  one (the fade-then-redetonate double), and the wire END's twinless arm
     *  logs the local-vs-wire lead. Pruned against the live-id set each fresh frame. */
    private final Map<Long, Long> mineLocalDetTicks = new HashMap<Long, Long>();
    private int mineNativeFuseKept;  // predict_missile_mine_native_fuse_kept (puppet
                                     //   install SKIPPED on a mine body — the engine fuse AI stays)
    private int mineFizzleSkipped;   // predict_missile_mine_fizzle_skipped (wire FIZZLING
                                     //   on a mine = its expiry arc, never a flameOut relay)
    private int proxFizzleSkipped;   // predict_missile_prox_fizzle_skipped (wire FIZZLING
                                     //   on a prox charge = its fade-DET; the END door presents it)
    private int retireHeldEngineDet; // predict_missile_retire_held_engine_det (retire left
                                     //   a door-armed prox body to the engine fuse)
    private int mineLocalGone;       // predict_missile_mine_local_gone (mine body ended
                                     //   locally before its wire end — the own-shot idiom)
    private int mineReadoptRefused;  // predict_missile_mine_readopt_refused (sourceless
                                     //   re-adopt of a locally-ended / tombstoned mine id refused)
    private int mineSourceReplica;   // predict_missile_mine_source_replica (sourceless
                                     //   mine adopt spawned WITH its source ship replica)
    private int mineSourceSynthetic; // predict_missile_mine_source_synthetic (no replica —
                                     //   the engine's synthetic source, re-sided to the wire owner)
    // the B2 bomb/mine presentation halves.
    private int bombAdopts;          // predict_missile_bomb_adopts (presence-adopt bombs KEPT —
                                     //   carved out of adopt_probe_ballistic's remove path)
    private int mineSpawnFx;         // predict_missile_mine_spawn_fx (fade+sound at adopt)
    /** The served Nova/Orion script-bomb adopt meters. */
    private int scriptBombAdopts;    // predict_missile_scriptbomb_adopts (sourceless script-bomb twins minted)
    private int scriptBombBinds;     // predict_missile_scriptbomb_binds (mirror bound to the replica's own script)
    private int scriptBombBindDefault;// predict_missile_scriptbomb_bind_default (source script not a binder:
                                     //   the detached per-spec default, FX + flash only)
    private int scriptBombOwnSkipped;// predict_missile_scriptbomb_own_skipped (own-sourced record never adopted)
    private int canisterAdoptFx;     // predict_missile_canister_adopt_fx (fire
                                     //   sound plays — every acquisition seam bumps it)
    // per-DOOR canister acquisition counters (uncapped meters; settle the R1
    // door label empirically — log caps made attribution meter-only).
    private int canisterAcqEnroll;   // predict_missile_canister_acq_enroll (PRCP discharge-tail)
    private int canisterAcqAdopt;    // predict_missile_canister_acq_adopt (sourced presence-mint)
    private int canisterAcqSourceless;// predict_missile_canister_acq_sourceless (the sourceless arm)
    private int canisterAcqSkip;     // predict_missile_canister_acq_skip (enroll skip-ballistic
                                     //   exit — nonzero = un-acquired, "no door")
    // the mine/bomb end-of-life counters (mine_det_fx .. bomb_own_impact) live in
    // MineBombEndFxLane.
    // The predict_own_missile_* counters live in OwnMissileHandoffLane.

    MissilePoseLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** starvation-alarm feed: SOURCED presence-mints only (a server-attributed
     *  firer whose round arrived with no enrolled twin — exactly what accumulates if the foe
     *  WEAPON_DISCHARGE GPEE lane ever starves). The sourceless (station-minefield) arm is
     *  legitimately discharge-less, and its apply-mode adopt bumps the raw counter too — fed
     *  raw, ~25 minefield adopts false-fire the alarm. Subtracts the
     *  PAIRED counter, not {@code sourcelessAdopts}: the meter-only arm bumps that one
     *  WITHOUT {@code presenceAdopts}, so the raw subtraction would under-read there. */
    int presenceAdopts() {
        return sourcedPresenceFeed(presenceAdopts, sourcelessPresenceAdopts);
    }

    /** The feed arithmetic above, pure (the contract seam — {@code StarvationFeedSmoke}
     *  pins it against the live PRCP alarm threshold headless, where the adopt paths that move
     *  the real counters need a running engine). */
    static int sourcedPresenceFeed(int presenceAdopts, int sourcelessPresenceAdopts) {
        return presenceAdopts - sourcelessPresenceAdopts;
    }

    /** The apply-mode puppet AI install (both enroll sites): {@link ThrustPuppetAI} iff the
     *  missile's own engine-slot list is non-empty — the LITERAL source of the engine's
     *  {@code getEngineLocations().isEmpty()} glow/contrail gate, read via
     *  {@link #ENGINE_LOCATIONS} (the falsified {@code getShipEngines()} mirror is
     *  gone) — else the plain {@link #NOOP_AI} (bombs, the one non-thrusting enrolled class;
     *  their physics stay byte-identical). Any resolve/invoke failure = fail-CLOSED no-op. */
    ThrustPuppetAI installPuppetAI(MissileAPI missile) {
        if (keepsNativeAi(missile)) {
            // A MINE keeps its engine fuse AI — no setMissileAI, no
            // flight-time park (the engine's own expiry primes + detonates).
            // This amends the earlier rule ("NOOP puppet is correct for a
            // mine" — it killed the ping/windup/boom AND the explode() door).
            mineNativeFuseKept++;
            return null;
        }
        boolean thrust = false;
        try {
            if (ENGINE_LOCATIONS != null) {
                List<?> slots = (List<?>) ENGINE_LOCATIONS.invoke(missile);
                thrust = slots != null && !slots.isEmpty();
            } else if (!thrustGateFailureLogged) {
                thrustGateFailureLogged = true;
                lane.log("predict v13 missile thrust-gate resolve FAILED (fail-closed, no thrust): "
                        + ENGINE_LOCATIONS_FAILURE);
                // ride beside the capped lane.log so the run-wide
                // NOOP coast survives the cap into the verdict FALLBACK section.
                FallbackAnnounce.announceOnce("f7-thrust-gate-failed",
                        "ENGINE_LOCATIONS resolve failed -> run-wide no-thrust puppets");
            }
        } catch (Throwable ignore) {
            // presentation lane; an unreadable entity degrades to the no-op puppet (fail-closed)
        }
        boolean mote = thrust && isMoteSpec(missile);
        if (mote) {
            // Motes had rendered as the bare sprite with none of their vanilla FX:
            // a mote's WHOLE look is its
            // engine glow + GLOW contrail (mote.proj: 4x4 sprite, 12x35 GLOW engine, contrail
            // sizeMult 10 / duration 2). The carve-out gave motes NOOP_AI (the
            // ACCELERATE thrust puppet had pinned physics acceleration onto a wire-driven pose and
            // piled the contrail into a cone) = the bare sprite; the forceShowAccelerating()
            // puppet lit NOTHING (same grade) — that call is a hysteresis override,
            // not the light (INTERRUPT_CONTRAIL javadoc). The mote puppet now makes the
            // engine's own accelerate-visual call with no physics; velocity/facing stay the wire's.
            moteGlowInstalls++;
        }
        // A decoy flare takes the same display-only light (ThrustPuppetAI.flare).
        boolean flare = false;
        try {
            flare = thrust && !mote && missile.isFlare();
        } catch (Throwable ignore) {
            // unreadable spec: the plain thrust puppet, exactly as before the flare light
        }
        if (flare) {
            flareGlowInstalls++;
        }
        ThrustPuppetAI puppet = thrust ? new ThrustPuppetAI(this, missile, mote, flare) : null;
        missile.setMissileAI(puppet != null ? puppet : NOOP_AI);
        missile.setMaxFlightTime(PUPPET_MAX_FLIGHT_TIME);
        noopInstalls++;
        if (thrust) {
            thrustInstalls++;
        }
        return puppet;
    }

    /** The two vanilla mote projectile specs ({@code missileType MOTE}: {@code
     *  data/shipsystems/proj/mote.proj} + {@code mote_hf.proj}, launched by the Ziggurat's
     *  {@code mote_control} system via {@code motelauncher}/{@code motelauncher_hf}). Spec id is
     *  the discriminator (the public {@link #specIdOf} read); an UNREADABLE spec counts as a mote
     *  (fail-closed toward the no-thrust puppet — the lane's standing degrade direction). */
    static boolean isMoteSpec(MissileAPI missile) {
        String spec = specIdOf(missile);
        return spec == null || "mote".equals(spec) || "mote_hf".equals(spec);
    }

    /** The bomb discriminator: engine-slot-LESS via the same
     *  {@link #ENGINE_LOCATIONS} handle the thrust gate reads (free-fall ordnance carries no
     *  engine slots; powered unguided rockets do). Any resolve/read failure = FALSE, i.e. the
     *  body degrades to the old ballistic skip — byte-identical to today, never a puppet
     *  minted on a guess. */
    private static boolean engineLess(MissileAPI missile) {
        try {
            if (ENGINE_LOCATIONS == null) {
                return false;
            }
            List<?> slots = (List<?>) ENGINE_LOCATIONS.invoke(missile);
            return slots != null && slots.isEmpty();
        } catch (Throwable ignore) {
            // A throwing read misclassifies a bomb as not-bomb (ballistic skip).
            FallbackAnnounce.announceOnce("f6-engineless-threw", "engineLess read threw "
                    + ignore.getClass().getSimpleName() + " -> classified not-bomb");
            return false;
        }
    }

    /**
     * ENROLL a presented missile at its present site (PRCP foe discharge tail / FLC own-adopt),
     * same render-thread stack as the spawn — the engine never advances between mint and the
     * apply-mode no-op install, so the factory seeker AI is never advanced (a safety requirement).
     * Classification reads the FACTORY AI ({@code isGuided()||isFlare()}) BEFORE any replace;
     * engine-less ballistic (bombs) now enrolls as a puppet class of its own — the
     * skip left it in free local flight with a live warhead, and M1 proved its wire twin crosses
     * fully sourced. Engine-slotted ballistic keeps local flight and its pair is
     * remembered as the presence-adopt double-present guard.
     */
    void enroll(Object spawned, long sourceEntityId, int sourceMountIndex, long spawnTick) {
        if (!MISSILE_POSE_METER || !(spawned instanceof MissileAPI)) {
            return;
        }
        try {
            MissileAPI missile = (MissileAPI) spawned;
            String specId = specIdOf(missile);
            boolean guided = missile.isGuided();
            boolean flare = missile.isFlare();
            boolean bomb = !guided && !flare && BOMB_POSE && engineLess(missile);
            if (!guided && !flare && !bomb) {
                enrollSkipBallistic++;
                if (MineBombEndFxLane.isCanisterSpec(specId)) {
                    // A nonzero here says the canister exits enroll UN-acquired
                    // (engine-slotted / BOMB_POSE off) — "no door", distinct from lane-off.
                    canisterAcqSkip++;
                }
                ballisticPairs.add(pairKey(sourceEntityId, sourceMountIndex));
                // Lever C: remember the composed bolt so the wire end-confession can
                // find and force-remove it (the victim-seat phantom-damage fix).
                ballistic.register(missile, sourceEntityId, sourceMountIndex, spawnTick, specId);
                return;
            }
            if (flare) {
                enrolledFlare++;
            } else if (guided) {
                enrolledGuided++;
            } else {
                enrolledBomb++;
            }
            Twin twin = new Twin(missile, sourceEntityId, sourceMountIndex, spawnTick, flare,
                    specId, false);
            twins.put(missile, twin);
            if (MISSILE_POSE_APPLY) {
                twin.thrustAi = installPuppetAI(missile);
            }
            // The canister ENROLL acquisition seam (the R1 live-door candidate:
            // enroll-as-bomb). Spec-keyed + edge-driven — an enroll IS
            // one acquisition. (1) the system's own fire sound at the body (launch-truth: the
            // PRCP discharge tail this enroll rides IS the launch instant); (2) C1 intangibility,
            // knob-INDEPENDENT like the sourceless arm (the sourceless-arm law: a tangible twin deals local
            // damage the server never dealt — the dmg-shot-out lane — and local collision
            // kills bypass the end classifier via pruneLocalGone). Both APPLY-only: meter mode
            // mutates nothing perceivable. Residue [I] disclosed: canister body-hits vs
            // OWN ship stop presenting locally (server truth rides the hull mirror).
            if (MineBombEndFxLane.isCanisterSpec(specId)) {
                canisterAcqEnroll++;
                if (MISSILE_POSE_APPLY) {
                    try {
                        missile.setCollisionClass(CollisionClass.NONE);
                    } catch (Throwable ignore) {
                        // presentation lane: a collidable round still renders; prune-exit
                        // classifier keeps the burst honest either way
                    }
                    if (MineBombEndFxLane.CANISTER_FX) {
                        try {
                            Global.getSoundPlayer().playSound("system_canister_flak_fire", 1f, 1f,
                                    missile.getLocation(), missile.getVelocity());
                            canisterAdoptFx++;
                        } catch (Throwable ignore) {
                            // presentation nicety; the enroll itself stands
                        }
                    }
                }
            }
            lane.log("predict v13 missile-enroll: src=" + sourceEntityId + " mount="
                    + sourceMountIndex + " tick=" + spawnTick + " class="
                    + (flare ? "flare" : guided ? "guided" : "bomb")
                    + (MISSILE_POSE_APPLY ? "" : " (meter)"));
        } catch (Throwable ignore) {
            // presentation-side lane; never break the watch
            // 1290 (81) F5: a thrown enroll leaves the missile body unmanaged — systemic.
            FallbackAnnounce.announceOnce("f5-missile-enroll-threw", "enroll threw "
                    + ignore.getClass().getSimpleName() + " -> body unmanaged");
        }
    }

    /** The OWN link-only enroll (DamageWitness birth site). The law lives
     *  in {@link OwnMissileHandoffLane#enrollOwn}; thin relay so the witness call site stays. */
    void enrollOwn(Object spawned, long sourceEntityId, int sourceMountIndex, long spawnTick) {
        own.enrollOwn(spawned, sourceEntityId, sourceMountIndex, spawnTick);
    }

    /** The own WEAPONLESS mine newborn (law in {@link OwnMissileHandoffLane#enrollOwnMine}). */
    void enrollOwnMine(Object spawned, long sourceEntityId, long spawnTick) {
        own.enrollOwnMine(spawned, sourceEntityId, spawnTick);
    }

    /**
     * The per-FRESH-frame drive: prune → pose-adopt → link → absence → presence.
     * Called once per render frame right after {@code maintainBeamAim}; a stale frame returns
     * immediately (stale NEVER ticks absence — the dead-feed hold-last class is the recorded
     * known-benign).
     */
    void maintain(ServerSnapshotFrame snapshot, boolean freshSnapshot) {
        end.advanceHeld(plugin.lastSnapshotSimTick); // per-render-frame prox-door witness walk (fail-soft inside)
        if ((!MISSILE_POSE_METER && !OwnMissileHandoffLane.OWN_MISSILE_METER)
                || !freshSnapshot || snapshot == null) {
            return;
        }
        try {
            long tick = snapshot.simTick();
            pruneLocalGone(tick);
            List<ServerMissileState> records = snapshot.missiles();
            Map<Long, ServerMissileState> byId = new HashMap<Long, ServerMissileState>();
            for (ServerMissileState rec : records) {
                byId.put(rec.networkId(), rec);
            }
            own.gcTombstones(byId.keySet()); // tombstone GC (live-id prune)
            mineLocalDetTicks.keySet().retainAll(byId.keySet()); // latch GC (live-id prune)
            adoptAndLink(byId, tick);
            tickAbsence(byId, tick);
            end.driveMineWindup(tick); // primed-mine windup scheduling (fresh-frame cadence)
            presenceAdopt(byId, tick);
            ballistic.linkPass(byId, tick); // skip-set wire-id links (post-adopt: twins claim first)
            ballistic.retryPending(byId, tick); // buffered reorder-race ends re-dispatch
        } catch (Throwable ignore) {
            // presentation-side lane; never break the watch
        }
    }

    /** Twins whose local missile left play (local-PD kill / engine removal): drop the entry —
     *  the live wire id becomes claimable again, so presence-adopt self-heals it.
     *  A canister twin exiting HERE dispatches the end classifier too — with
     *  body intangibility, the residue is engine removals only (the wire-FIZZLING flameOut
     *  relay racing the ~RTT+6f absence grace), and vanilla law says that end BURSTS. */
    private void pruneLocalGone(long tick) {
        for (Iterator<Map.Entry<MissileAPI, Twin>> it = twins.entrySet().iterator(); it.hasNext();) {
            Twin t = it.next().getValue();
            if (plugin.engine == null || !plugin.engine.isEntityInPlay(t.missile)) {
                it.remove();
                if (t.wireId != -1L) {
                    twinByWireId.remove(t.wireId);
                    if (t.own) {
                        own.tombstone(t.wireId); // Law in the own lane
                    }
                    if (t.mine) {
                        // A mine body that left play BEFORE its wire end
                        // detonated LOCALLY (its own fuse — the own-shot idiom; or a local PD
                        // kill). The id stays live on the wire until the server's det: latch
                        // it so the sourceless arm never re-adopts it as a second body, and
                        // keep the local tick for the lead/lag row at the wire end.
                        mineLocalDetTicks.put(t.wireId, tick);
                        mineLocalGone++;
                        mineLane.log("predict v13 mine LOCAL-END: id=" + t.wireId + " tick=" + tick
                                + (t.own ? " own" : " foe") + " (body left play before the wire end)");
                    }
                }
                if (t.own) {
                    own.ownLocalGone++;
                } else {
                    localGone++;
                    if (MineBombEndFxLane.isCanisterSpec(t.specId)) {
                        end.onCanisterPruneExit(t, tick); // classifier-on-every-exit
                    }
                    if (DroneStrikeFxLane.isConversionSpec(t.specId)) {
                        // The tripwire — a conversion twin must never die locally now
                        // (body intangible + both engine fizzle axes dead): any row here is a
                        // NEW killer, and the forensics say which (collision ⇒ didDmg/hp;
                        // engine fizzle ⇒ fizz/fading; external removeEntity ⇒ none of them).
                        droneStrike.localGoneConv++;
                        convLog(convGoneRow(t));
                    }
                }
            }
        }
    }

    /** The local-death forensics row for a pruned conversion twin (fail-soft: the
     *  body is out of play but the Java object's last state stays readable). */
    private String convGoneRow(Twin t) {
        StringBuilder sb = new StringBuilder("predict v16 conv local-GONE: id=").append(t.wireId);
        try {
            sb.append(" ft=").append(t.missile.getFlightTime())
                    .append(" fizz=").append(t.missile.isFizzling())
                    .append(" fading=").append(t.missile.isFading())
                    .append(" hp=").append(t.missile.getHitpoints())
                    .append(" didDmg=").append(t.missile.didDamage());
            Vector2f loc = t.missile.getLocation();
            if (loc != null) {
                sb.append(" at=").append((int) loc.x).append(',').append((int) loc.y);
            }
        } catch (Throwable ignore) {
            sb.append(" (state unreadable)");
        }
        sb.append(" lastWire=").append((int) t.lastWireX).append(',').append((int) t.lastWireY)
                .append('@').append(t.lastWireTick).append(convForensics(t));
        return sb.toString();
    }

    /** The shared conversion-forensics token tail (prune + end + reset rows). */
    static String convForensics(Twin t) {
        return " minDistOwn=" + (t.convMinDistOwn == Float.MAX_VALUE
                        ? "never" : (int) t.convMinDistOwn + "@" + t.convMinDistTick)
                + " wireFizzTick=" + (t.convWireFizzTick == Long.MIN_VALUE
                        ? "never" : String.valueOf(t.convWireFizzTick))
                + " localFizzTick=" + (t.convLocalFizzTick == Long.MIN_VALUE
                        ? "never" : String.valueOf(t.convLocalFizzTick));
    }

    /** per-fresh-frame conversion sampling (the (ii)/(iii) instrumentation): wire
     *  FIZZLING first-cross latch, local engine-fizzle latch, closest wire-pose approach to the
     *  own ship. Cheap (conversion twins only, ≤ tens per round). */
    private void sampleConvForensics(Twin t, ServerMissileState rec, long tick) {
        if (t.convWireFizzTick == Long.MIN_VALUE && rec.fizzling()) {
            t.convWireFizzTick = tick;
        }
        try {
            if (t.convLocalFizzTick == Long.MIN_VALUE && t.missile.isFizzling()) {
                t.convLocalFizzTick = tick;
            }
            ShipAPI own = plugin.engine == null ? null : plugin.engine.getPlayerShip();
            Vector2f ownLoc = own == null ? null : own.getLocation();
            if (ownLoc != null) {
                float dx = rec.posX() - ownLoc.x;
                float dy = rec.posY() - ownLoc.y;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < t.convMinDistOwn) {
                    t.convMinDistOwn = d;
                    t.convMinDistTick = tick;
                }
            }
        } catch (Throwable ignore) {
            // forensics nicety; the pose drive stands
        }
    }

    /** Pose-adopt every linked twin with a record this frame, then link unlinked twins to
     *  unclaimed matching-pair ids (FIFO oldest twin first — wire births arrive in fire order). */
    private void adoptAndLink(Map<Long, ServerMissileState> byId, long tick) {
        for (ServerMissileState rec : byId.values()) {
            Twin t = twinByWireId.get(rec.networkId());
            if (t != null) {
                driveOwnOrAdopt(t, rec, tick);
            }
        }
        // Link pass: oldest unlinked twin per source pair claims the oldest unclaimed matching id.
        List<Twin> unlinked = null;
        for (Twin t : twins.values()) {
            if (t.wireId == -1L) {
                if (unlinked == null) {
                    unlinked = new ArrayList<Twin>();
                }
                unlinked.add(t);
            }
        }
        if (unlinked == null) {
            return;
        }
        unlinked.sort((a, b) -> Long.compare(a.spawnTick, b.spawnTick));
        // Claim candidates in ascending networkId — the mint is PROVEN monotone
        // (EngineCombatBinding single counter, never recycled), so ascending id = birth order;
        // HashMap order salvo-mispaired, and wire list order is a same-tick chunk-union rebuild.
        List<ServerMissileState> ordered = new ArrayList<ServerMissileState>(byId.values());
        ordered.sort((a, b) -> Long.compare(a.networkId(), b.networkId()));
        for (Twin t : unlinked) {
            // The OWN weaponless funnel (a Doom's mines all cross as
            // (own,-1)) claims the NEAREST unclaimed record, not the lowest id — see nearestTo.
            List<ServerMissileState> funnel = null;
            boolean ownFunnel = t.own && t.sourceMountIndex == ServerMissileState.NO_SOURCE_MOUNT;
            Vector2f funnelLoc = null;
            if (ownFunnel) {
                try {
                    funnelLoc = t.missile.getLocation();
                } catch (Throwable ignore) {
                    // unreadable body ⇒ the lowest-id claim (the pre-funnel shape)
                }
            }
            for (ServerMissileState rec : ordered) {
                if (!rec.hasSource() || twinByWireId.containsKey(rec.networkId())
                        || own.isTombstoned(rec.networkId())) {
                    continue;
                }
                if (rec.sourceMountIndex() == ServerMissileState.NO_SOURCE_MOUNT
                        && !(t.own && t.sourceMountIndex == ServerMissileState.NO_SOURCE_MOUNT)) {
                    continue; // A weaponless-sourced id is an OWN weaponless twin's alone
                }
                if (rec.sourceEntityId() == t.sourceEntityId
                        && rec.sourceMountIndex() == t.sourceMountIndex) {
                    if (consumedWireIds.contains(rec.networkId())) {
                        // The id was already consumed by an earlier twin —
                        // the mint never recycles, so this record is the OLD missile back from a
                        // feed gap; claiming it is the instant mislink. Leave it for
                        // presence-adopt (foe self-heal); this twin waits for its true twin.
                        linkRefusedConsumed++;
                        if (consumedRefusalLogged.add(rec.networkId())) {
                            lane.log("predict v13 missile-link REFUSED (consumed id): id="
                                    + rec.networkId() + " src=" + t.sourceEntityId
                                    + " mount=" + t.sourceMountIndex
                                    + " spawnTick=" + t.spawnTick + " tick=" + tick
                                    + " own=" + t.own);
                        }
                        continue;
                    }
                    if (isCrossSpec(t, rec)) {
                        // Same pair, DIFFERENT spec — a MIRV child on its
                        // launcher's attribution; leave the id free for presence-adopt.
                        linkCrossSpec++;
                        continue;
                    }
                    if (ownFunnel && funnelLoc != null) {
                        if (funnel == null) {
                            funnel = new ArrayList<ServerMissileState>();
                        }
                        funnel.add(rec); // Collect, claim the nearest below
                        continue;
                    }
                    claimLink(t, rec, tick);
                    break;
                }
            }
            if (funnel != null) {
                ServerMissileState rec = nearestTo(funnelLoc.x, funnelLoc.y, funnel);
                float dx = rec.posX() - funnelLoc.x;
                float dy = rec.posY() - funnelLoc.y;
                boolean lowest = rec == funnel.get(0);
                if (!lowest) {
                    own.ownMineNearestOverLowest++; // a would-be cross-pair avoided
                }
                // The link-snap read — the LOCAL newborn's facing
                // beside the WIRE facing the first adoptPose will write (both drawn from Math.random()*360).
                mineLane.log("predict own-mine link NEAREST: id=" + rec.networkId()
                        + " dist=" + (float) Math.sqrt(dx * dx + dy * dy)
                        + " cand=" + funnel.size() + " lowestId=" + funnel.get(0).networkId()
                        + (lowest ? "" : " (over lowest)") + " tick=" + tick
                        + " mineFacing=" + (int) t.missile.getFacing()
                        + " wireFacing=" + (rec.hasPose() ? String.valueOf((int) rec.facingDeg()) : "?"));
                claimLink(t, rec, tick);
            }
        }
    }

    /** The link claim (once the inline tail of {@link #adoptAndLink}, hoisted so the
     *  nearest-record funnel and the first-match arm share one body). */
    private void claimLink(Twin t, ServerMissileState rec, long tick) {
        t.wireId = rec.networkId();
        twinByWireId.put(t.wireId, t);
        consumedWireIds.add(t.wireId); // freshness ledger
        sightings.remove(t.wireId); // a claimed id is no presence-adopt candidate
        dictDefer.remove(t.wireId);
        if (t.own) {
            own.onLinked(t, rec, tick); // Law in the own lane
        } else {
            linked++;
            laneFor(t).log("predict v13 missile-link: id=" + t.wireId + " src="
                    + t.sourceEntityId + " mount=" + t.sourceMountIndex
                    + " spawnTick=" + t.spawnTick + " linkTick=" + tick);
            adoptPose(t, rec, tick);
        }
    }

    /** One twin-frame pose adoption: loc := record; vel := per-id wire delta × ticks/s (needs a
     *  prior record at an earlier tick); facing := the v16 wire pose truth when the record
     *  carries one, else atan2(vel) when non-degenerate (the sentinel/old-reel
     *  fallback); plus the one-shot FIZZLING death-arc relay. */
    void adoptPose(Twin t, ServerMissileState rec, long tick) {
        t.missedFresh = 0;
        if (t.thrustAi != null) {
            t.thrustAi.touchFresh(rec); // A fresh adopted pose re-arms thrust (+ the wire mote light)
        }
        poseFrames++;
        if (t.fizzled) {
            // The fade belt: the engine's first fizzling advance() flips the body to the
            // spec's afterFlameoutCollisionClass (WeaponSpecLoader default MISSILE_FF — none
            // of the 12 vanilla range-fizzle specs override it), so a fading PUPPET would
            // become friendly-fire collidable and (amsrm: dudProbabilityOnFlameout 0) deal
            // client-only damage the server never dealt. A puppet's fade is presentation:
            // hold it intangible for the window (one owner — the server's missile collides).
            // Only an ARMED body — a dud (the relay mirrored
            // the wire DUD bit) stays collidable so the engine's own harmless bounce plays.
            try {
                int act = MissileDudRelayLaw.beltAct(t.fizzled, t.missile.isFizzling(),
                        t.missile.getCollisionClass() != CollisionClass.NONE,
                        t.missile.isArmedWhileFizzling() || t.missile.isForceAlwaysArmed()); // forceAlwaysArmed counts as armed-while-fizzling
                if (act == MissileDudRelayLaw.BELT_NONE) {
                    t.missile.setCollisionClass(CollisionClass.NONE);
                    fizzleFadeIntangible++;
                } else if (act == MissileDudRelayLaw.BELT_KEEP && !t.bounceCounted) {
                    t.bounceCounted = true;
                    bounceKept++;
                }
            } catch (Throwable ignore) {
                // presentation belt; never break the watch
            }
        }
        long dt = t.lastWireTick == Long.MIN_VALUE ? 0L : tick - t.lastWireTick;
        float vx = 0f;
        float vy = 0f;
        if (dt > 0) {
            float perTick = 1f / (float) dt;
            long rate = ServerTickRate.ticksPerSecond();
            vx = (rec.posX() - t.lastWireX) * perTick * rate;
            vy = (rec.posY() - t.lastWireY) * perTick * rate;
        }
        // A handed-off OWN twin pose-adopts under ITS knob (own puppets exist only
        // under ownMissile=apply — the pose-lane knob must not gate their driver).
        boolean apply = t.own ? OwnMissileHandoffLane.OWN_MISSILE_APPLY : MISSILE_POSE_APPLY;
        if (apply) {
            Vector2f loc = t.missile.getLocation();
            if (loc != null) {
                loc.set(rec.posX(), rec.posY());
                if (t.own) {
                    // Projection + blend presentation law in the own lane
                    // (also the cdx/cdy writer; MUST run before lastWire* updates below)
                    own.presentPose(t, loc, rec, tick);
                }
            }
            if (dt > 0) {
                Vector2f vel = t.missile.getVelocity();
                if (vel != null) {
                    vel.set(vx, vy);
                }
            }
            // The v16 wire pose IS the server's own facing (quantized u16,
            // minted once at the engine read) — the drift-atan2 reconstruction and the
            // DEM-beam-bearing special case both DISSOLVE under it (drift-derived
            // facing ran median 67.7° off the beam bearing during standoff; the
            // beam bind stays the beam lane's own presentation seam). Sentinel-pose
            // records (old reels, fail-soft server reads) keep the drift fallback.
            if (rec.hasPose()) {
                // A handed-off OWN twin's facing slerps local→wire over the
                // blend window (own hook); foe puppets take the wire facing directly.
                t.missile.setFacing(t.own
                        ? own.presentedFacing(t, rec.facingDeg(), tick)
                        : rec.facingDeg());
                poseFacingSets++;
            } else if (dt > 0 && vx * vx + vy * vy > 1f) {
                t.missile.setFacing((float) Math.toDegrees(Math.atan2(vy, vx)));
            }
            poseSets++;
        }
        if (rec.fizzling() && !t.fizzled && !t.mine
                && MineBombEndFxLane.fizzleRelaySkips(false, t.specId)) {
            // A PROX CHARGE's wire FIZZLING is its
            // fade-DETONATION (flameoutTime == fadeTime ⇒ isFading ≡ isFizzling; vanilla's ProximityFuseAI
            // explodes on the first fading frame). The relay's flameOut on the
            // NOOP/thrust puppet was a silent 0.1 s fade (a player's "just disappeared"). Counted,
            // never relayed; t.fizzled stays FALSE so the kind-6 END door (MineBombEndFxLane) runs
            // at the server's explode pose — or, with no END, the G=6 absence end at the last wire
            // pose (the det-by-default law). Door OFF ⇒ fizzleRelaySkips is false here.
            proxFizzleSkipped++;
        } else if (rec.fizzling() && !t.fizzled && t.mine) {
            // A MINE's wire FIZZLING is its flight-time expiry —
            // vanilla's fade ⇒ trigger ⇒ ping ⇒ 3 s ⇒ explode (never a dud drift), and the
            // local body's own fuse AI runs exactly that on its own parked flight time. A
            // flameOut relay here killed the body mid-arc, pruned the twin, and the freed id
            // came back as a sourceless re-adopt (the 12 fade-then-redetonate doubles).
            // Counted, never relayed; t.fizzled stays FALSE so the wire-end
            // explode() door still fires.
            mineFizzleSkipped++;
        } else if (rec.fizzling() && !t.fizzled) {
            // Fizzle relay, one-shot: play the engine's OWN death arc (flameOut =
            // flightTime := max+1; burnout → glow-off → drift → fade)
            // WHILE the wire keeps tracking the drifting server missile — the
            // end-of-feed removal stays the end shape, so the dud-drift ghost
            // class cannot return.
            t.fizzled = true;
            if (DroneStrikeFxLane.isConversionSpec(t.specId)) {
                // FIZZLING is a BIRTH-state for the conversion class —
                // relaying it flameOut-killed the live local body mid-track, pruneLocalGone
                // dropped the twin, and the fizzledIds latch blocks re-adopt (the conversion
                // exemption is first-adopt only), so BOTH presentation lanes starved: local
                // collision AND the end-det classifier (rounds 2-4, 100% of conversions).
                // An amendment: the body must LIVE out the track so the absence-end
                // classifier (the ONE impact-presentation owner — det relay via OwnDetLane)
                // always dispatches. The "local collision = primary lane" premise is
                // RETIRED: the body is intangible from adopt (a local collision at the
                // impact point — e.g. the just-killed coincident drone shell losing NO_FF
                // protection — pruned the twin 1-2 frames before absence-end, rounds 2-5).
                // The t.fizzled latch above still one-shots this branch (harmless for
                // conversions everywhere since the guard drop).
                convLog("predict v16 conv fizzle NO-KILL: id=" + t.wireId + " tick=" + tick);
            } else {
                // The wire DUD split counts in meter mode too.
                t.dudApplied = rec.dud(); // the verdict this relay writes
                if (t.own) {
                    own.ownFizzleRelays++;
                    if (rec.dud()) {
                        own.ownDudRelays++;
                    } else {
                        own.ownArmedRelays++;
                    }
                } else {
                    fizzleRelays++;
                    if (rec.dud()) {
                        dudRelayed++;
                    } else {
                        armedRelayed++;
                    }
                }
                boolean parked = false;
                try {
                    // read OUTSIDE the apply gate so meter mode counts the class too
                    parked = t.missile.getFlightTime() >= PUPPET_MAX_FLIGHT_TIME - 1f;
                } catch (Throwable ignore) {
                    // unreadable ⇒ treated as un-parked (the pre-fix path)
                }
                if (parked) {
                    fizzleRelaysParked++;
                    if (t.own) {
                        own.ownFizzleRelaysParked++;
                    }
                }
                if (apply) {
                    try {
                        // Collapse the PUPPET_MAX_FLIGHT_TIME suppression cap first —
                        // flameOut is flightTime := maxFlightTime + 1, and at 1e9 that add
                        // rounds away (float ulp 64), so against the install cap the advance()
                        // death compare never turns true and the glow/contrail arc never
                        // engages (the kinematic-only burnout class). Fizzle timing is
                        // wire-authoritative once the server declared FIZZLING.
                        // Own Antimatter SRMs were seen to "wink out": a
                        // fizzleOnReachingWeaponRange spec runs the engine's LOCAL range check
                        // on the puppet too (spec + maxRange,
                        // no AI involved) and its flameOut() PARKS flightTime at the 1e9 cap
                        // (cap+1 == cap; cap+dt == cap: it never advances again). Collapsing
                        // the cap to that parked value then re-runs the same no-op — the relay
                        // logged, no death arc, and the absence removal popped a full-glow body
                        // (seat 2: 138 own relays, 42 local ends). Parked ⇒ cap := 0 so the
                        // parked 1e9 (and the flameOut's 1) both clear the death compare.
                        t.missile.setMaxFlightTime(parked ? 0f : t.missile.getFlightTime());
                        // The server twin's dud roll, BEFORE the death arc
                        t.missile.setArmedWhileFizzling(MissileDudRelayLaw.armedOnRelay(true, rec.dud()));
                        t.missile.flameOut();
                    } catch (Throwable ignore) {
                        // presentation nicety; the absence removal still ends the twin
                    }
                }
                lane.log("predict v16 missile-fizzle relay: id=" + t.wireId + " tick=" + tick
                        + " dud=" + rec.dud() + (parked ? " parked" : "") + (apply ? "" : " (meter)"));
            }
        } else if (MissileDudRelayLaw.rearmOnFresh(rec.fizzling(), t.fizzled, rec.dud(), t.dudApplied)
                && !DroneStrikeFxLane.isConversionSpec(t.specId)) {
            // The server mints !isArmed() EVERY frame and it moves with the
            // arming clock (a Hammer flamed out inside its 0.5 s armingTime is minted DUD, then arms and
            // clears) — the latch above sampled it once, so a moved bit is re-applied here (idempotent
            // field write; meters stay on the latch, this counts the flips).
            // A conversion twin never took the arming write (the NO-KILL branch), a mine/prox never
            // latches. Nothing is held or delayed.
            t.dudApplied = rec.dud();
            if (t.own) {
                own.ownDudFlips++; // a handed-off own twin's flip is the own lane's meter
            } else {
                dudFlips++;
            }
            if (apply) {
                try {
                    t.missile.setArmedWhileFizzling(MissileDudRelayLaw.armedOnRelay(true, rec.dud()));
                    // The belt above ran on the PRE-write state (KEEP for a dud), so a
                    // re-ARMED body would stay MISSILE_FF-collidable until the next fresh record — re-run
                    // the belt on the post-write values now (one owner of the hit = the server's missile).
                    if (MissileDudRelayLaw.beltAct(true, t.missile.isFizzling(),
                            t.missile.getCollisionClass() != CollisionClass.NONE,
                            t.missile.isArmedWhileFizzling() || t.missile.isForceAlwaysArmed())
                            == MissileDudRelayLaw.BELT_NONE) {
                        t.missile.setCollisionClass(CollisionClass.NONE);
                        fizzleFadeIntangible++;
                    }
                } catch (Throwable ignore) {
                    // presentation nicety; the belt + the absence removal still own the end
                }
            }
        }
        if (DroneStrikeFxLane.isConversionSpec(t.specId)) {
            sampleConvForensics(t, rec, tick); // (ii)/(iii) forensics latches
        }
        if (t.lastWireTick != tick) {
            t.lastWireX = rec.posX();
            t.lastWireY = rec.posY();
            t.lastWireTick = tick;
            end.trackMinePrime(t, tick); // Prime = the pose-history STOP (fresh poses only)
        }
    }

    /** The per-linked-twin fresh-frame dispatch: foe (and handed-off own) twins
     *  pose-adopt; a live own twin routes to the own lane (measure until the window closes,
     *  then handoff — {@link OwnMissileHandoffLane#drive}). */
    private void driveOwnOrAdopt(Twin t, ServerMissileState rec, long tick) {
        if (!t.own || t.handedOff) {
            adoptPose(t, rec, tick);
            return;
        }
        own.drive(t, rec, tick);
    }

    /** Absence-despawn (retire semantics re-lawed 1278): a linked id missing
     *  from G consecutive FRESH frames is the authoritative end ⇒ the puppet body is REMOVED; an
     *  unlinked twin past the link window retires the same way, LOUD (the wire never confirmed
     *  it). */
    private void tickAbsence(Map<Long, ServerMissileState> byId, long tick) {
        for (Iterator<Map.Entry<MissileAPI, Twin>> it = twins.entrySet().iterator(); it.hasNext();) {
            Twin t = it.next().getValue();
            if (t.wireId != -1L) {
                if (byId.containsKey(t.wireId)) {
                    continue;
                }
                if (++t.missedFresh < ABSENCE_FRESH_MISSES) {
                    continue;
                }
                if (t.own) {
                    if (t.sourcelessMine) {
                        // An OWN MINE twin's wire end IS the server
                        // det — its local body is a NOOP puppet (no local fuse) and the own
                        // retire below removes it silently, so the mine-end lane must paint the
                        // det here exactly as the sourceless arm does (mine_explosion + the
                        // own det-damage relay); the remove-take of the kind-6 verdict rides it.
                        end.onTrackEnd(t, tick);
                    }
                    if (t.handedOff) {
                        own.onPuppetAbsence(t, tick); // prompt-removal law
                    } else {
                        own.onAbsenceFade(t, tick); // flameOut-fade law
                    }
                    it.remove();
                    twinByWireId.remove(t.wireId);
                    continue;
                }
                endRemoved++;
                laneFor(t).log("predict v13 missile-absence: id=" + t.wireId + " gone " + t.missedFresh
                        + " fresh frames at tick=" + tick + " -> remove"
                        + (MISSILE_POSE_APPLY ? "" : " (meter)"));
                end.onTrackEnd(t, tick); // remove-take + end FX (end lane)
                retire(t);
                it.remove();
                twinByWireId.remove(t.wireId);
            } else if (own.ghostFadeDue(t, tick)) {
                own.onGhostFade(t, tick); // GHOST-kill law in the own lane
                it.remove();
            } else if (t.spawnTick < 0L || tick - t.spawnTick > LINK_WINDOW_TICKS) {
                // Negative spawnTick (a legacy sentinel) is expiry-ELIGIBLE, never
                // an overflow that pins the twin alive + FIFO-first forever.
                if (t.own) {
                    own.onLinkExpired(t, tick); // Release law
                    it.remove();
                    continue;
                }
                linkExpired++;
                laneFor(t).log("predict v13 missile-link EXPIRED: src=" + t.sourceEntityId + " mount="
                        + t.sourceMountIndex + " spawnTick=" + t.spawnTick + " tick=" + tick
                        + " (no wire twin inside the window) -> remove"
                        + (MISSILE_POSE_APPLY ? "" : " (meter)"));
                retire(t);
                it.remove();
            }
        }
    }

    /** The authoritative-end retire — end-of-feed removal, superseding
     *  the flameOut-only micro-decision (d): the wire said this missile no longer exists, so the
     *  presented body is removed outright. {@code flameOut()} alone left the body drifting as a
     *  dud through the SPEC's own fizzle window (flameOut = {@code flightTime := max+1};
     *  engine removal then waits {@code spec.getFlameoutTime()}) — an observed pilum
     *  MIRV ghost, client tracks +1338 ticks past the server end. Vanilla CONSUMES MIRV stage-1
     *  at the split, and for PD-kill/fizzle ends the SERVER already rode out its own fizzle
     *  before the track ended — prompt removal is the faithful end shape for every end class;
     *  the G=6 absence window is the grace (≤ ~0.5 s allowance). No warhead either way
     *  (removeEntity despawns — the ballistic-probe precedent, live since 1283). */
    void retire(Twin t) {
        if (t.engineDetPending) {
            // The prox-charge engine door armed this body
            // (own fuse + flameOut) in the same-tick onTrackEnd just before this call; removing it
            // now would delete it before the fuse ever advanced (no det). The engine removes it.
            retireHeldEngineDet++;
            return;
        }
        if (MISSILE_POSE_APPLY && plugin.engine != null && plugin.engine.isEntityInPlay(t.missile)) {
            plugin.engine.removeEntity(t.missile);
        }
    }

    /**
     * Presence-driven (re)adopt: a live wire id — NOT own-sourced (own predicted
     * missiles keep zero-RTT local flight), no twin, pair not known-ballistic — buffers its 1st
     * sighting and spawns AT the wire pose on the 2nd (vel known ⇒ exact bearing+velocity). The
     * mint is classified like every enroll: guided/flare kept as a linked puppet, ballistic probe
     * removed + pair blacklisted. Unresolvable source ship/mount degrades to counted-unpresented
     * (meter + capped log, never silent). Package-private: {@code MineNativeFuseLawSmoke} drives it.
     */
    void presenceAdopt(Map<Long, ServerMissileState> byId, long tick) {
        if (!MISSILE_POSE_METER) {
            return; // The own-nudge knob alone never presence-adopts (driven-lane law)
        }
        sightings.keySet().retainAll(byId.keySet());
        dictDefer.keySet().retainAll(byId.keySet());
        fizzledIds.retainAll(byId.keySet());
        ownSourcedWireIds.retainAll(byId.keySet());
        sourcelessThrewIds.retainAll(byId.keySet());
        for (ServerMissileState rec : byId.values()) {
            long id = rec.networkId();
            if (twinByWireId.containsKey(id)) {
                continue;
            }
            // Spec truth — hoisted above the fizzle latch: the
            // droneconv exemptions below must see the spec BEFORE the latch/sighting gates
            // can drop the record (the droneconv_*=0 defect: the strike's ONLY wire
            // representation died in these gates).
            String trueSpec = rec.contentId() >= 1
                    ? plugin.contentCatalog.get(rec.contentId()) : null;
            boolean conversion = DroneStrikeFxLane.isConversionSpec(trueSpec);
            if (rec.fizzling() || fizzledIds.contains(id)) {
                if (!conversion) {
                    // A FIZZLING record never mints — its local body (if any) already
                    // played the death arc; a re-mint here would be the faded-body full-glow
                    // respawn loop. Latched so a later flag flicker cannot re-open the id.
                    // First latch of a SOURCELESS id logs — this is the only silent
                    // drop on the sourceless arm, and without the row a starved conversion
                    // class is indistinguishable from wire-absent (poses are binary; no text
                    // lane records dictionary use).
                    if (fizzledIds.add(id) && !rec.hasSource()) {
                        convLog("predict v13 missile FIZZLE-LATCHED sourceless: id=" + id
                                + " spec=" + FallbackAnnounce.sanitize(trueSpec, 64) + " tick=" + tick);
                    }
                    continue;
                }
                // Droneconv: the conversion missile can cross FIZZLING from its
                // very first sighting (fizzleOnReachingWeaponRange + noFlameoutOnFizzling, set
                // by vanilla) — fall through: the twin's normal fizzle relay plays
                // the death arc AFTER the conversion FX lane got its input.
            }
            // A SOURCELESS record — source pair (0,-1) — is no longer dropped here.
            // It is the station-minefield class: the server emits every engine.getMissiles() body
            // unconditionally and only DEGRADES the pair when getWeapon()==null
            // (the server's pair read), so a hullmod-minted mine crosses fully identified
            // (contentKey='minelayer_mine', pose valid) and only this gate hid it.
            // The ballistic blacklist is a per-MOUNT memory, so it can only be consulted when there
            // IS a mount; pairKey(0,-1) would otherwise alias every sourceless body onto one entry.
            // A WEAPONLESS-SOURCED record (src N, mount -1 — a system-script
            // spawn; the server emits the pair instead of degrading it to (0,-1))
            // rides the sourceless arm exactly as before on every client; only adoptAndLink's
            // link pass can claim it, and only by an OWN weaponless twin (the firer's local mine).
            boolean sourceless = !rec.hasSource()
                    || rec.sourceMountIndex() == ServerMissileState.NO_SOURCE_MOUNT;
            if (sourceless && !STATION_MINE_ADOPT) {
                sourcelessSkipped++;
                continue; // knob off = the old drop-sourceless behavior, byte-for-byte
            }
            if (!sourceless
                    && ballisticPairs.contains(pairKey(rec.sourceEntityId(), rec.sourceMountIndex()))) {
                continue; // unresolved source has no spawn recipe; ballistic flies locally
            }
            if (!sourceless && plugin.session != null
                    && rec.sourceEntityId() == plugin.session.shipId()) {
                // own-sourced wire ids are never presence-adopted. The id is
                // REMEMBERED: a firer death degrades the pair to (0,-1)
                // server-side, and the sourceless arm below must still know this is an OWN body.
                ownSourcedWireIds.add(id);
                continue;
            }
            if (scriptBombOwnSkip(sourceless && rec.hasSource() && plugin.session != null
                    && rec.sourceEntityId() == plugin.session.shipId(), trueSpec)) {
                // An OWN (N,-1) Nova/Orion bomb is
                // never adopted — the own ship ran the vanilla script locally (mint + det + lurch),
                // so a served twin would double it (pre-fix: a station-mine paint over the local
                // det). Checked BEFORE the sighting store; the id is remembered in the own-sourced
                // set so a firer-death (0,-1) degrade cannot re-adopt it (it lands on the guard
                // below, counted there). One row per id.
                if (ownSourcedWireIds.add(id)) {
                    scriptBombOwnSkipped++;
                    mineLane.log("predict v13 missile-adopt SCRIPTBOMB OWN-SKIP: id=" + id + " spec="
                            + FallbackAnnounce.sanitize(trueSpec, 64) + " src=" + rec.sourceEntityId() + " tick=" + tick);
                }
                continue;
            }
            if (sourceless && ownSourcedWireIds.contains(id)) {
                // OWN-guard:
                // this id crossed own-sourced before degrading — the native local round still
                // flies on this client, so a sourceless adopt would twin-double it. Known
                // residue: a joiner that never saw a pre-degrade frame has no memory here and
                // stays unprotected (rejoin re-syncs; accepted at landing).
                sourcelessOwnDegraded++;
                continue;
            }
            if (sourceless && (mineLocalDetTicks.containsKey(id) || own.isTombstoned(id))) {
                // The id's LOCAL mine body already ended (its own fuse
                // detonated it, or an own newborn died) while the server's is still live — a
                // sourceless re-adopt here minted the second body (fade-in + a second det).
                // The wire END for the id lands on the twinless arm.
                mineReadoptRefused++;
                continue;
            }
            if (sourceless && sourcelessThrewIds.contains(id)) {
                // this id's carrier mint already threw
                // (counted + logged once). The throw is deterministic for the spec — a logged match's
                // re-sight/re-mint loop threw three times for ONE body — so refuse before a sighting.
                sourcelessThrewRefused++;
                continue;
            }
            Sighting first = sightings.get(id);
            if (first == null) {
                if (sourceless && conversion) {
                    // Droneconv: fast + short-lived (fizzleOnReachingWeaponRange —
                    // the distinct-tick second sighting may never come); adopt on FIRST
                    // sighting. Spec is wire-resolved, so this never mints a guess.
                    sourcelessFirstAdopts++;
                    adoptSourceless(rec, id, tick,
                            new Sighting(rec.posX(), rec.posY(), tick), trueSpec);
                    continue;
                }
                sightings.put(id, new Sighting(rec.posX(), rec.posY(), tick));
                continue;
            }
            if (tick <= first.tick) {
                continue; // same-tick re-offer (chunk union) — wait for a later tick
            }
            // Dict-race defer (APPLY only — meter spawns nothing): an ANNOUNCED id
            // (contentId>=1) with no catalog entry YET is a dictionary arrival race, not an
            // unknown spec — hold the adopt (sighting kept) up to DICT_DEFER_TICKS for the
            // cycling re-announce before the permanent launcher-recipe fallback. (trueSpec
            // itself resolves above the fizzle latch.)
            if (MISSILE_POSE_APPLY && rec.contentId() >= 1 && trueSpec == null) {
                Long deferStart = dictDefer.get(id);
                if (deferStart == null) {
                    dictDefer.put(id, tick);
                    dictDeferred++;
                    lane.log("predict v13 missile-adopt DEFER: id=" + id + " contentId="
                            + rec.contentId() + " tick=" + tick
                            + " (catalog entry not yet arrived)");
                    continue;
                }
                if (tick - deferStart <= DICT_DEFER_TICKS) {
                    continue;
                }
                dictDefer.remove(id);
                dictDeferExpired++;
                lane.log("predict v13 missile-adopt defer EXPIRED: id=" + id + " contentId="
                        + rec.contentId() + " waited=" + (tick - deferStart)
                        + "t -> launcher recipe");
            } else if (trueSpec != null && dictDefer.remove(id) != null) {
                dictDeferOk++;
            }
            sightings.remove(id);
            if (sourceless) {
                adoptSourceless(rec, id, tick, first, trueSpec);
                continue;
            }
            ShipAPI ship = plugin.registryShape ? registryShip(rec.sourceEntityId()) : plugin.foeShip;
            List<WeaponAPI> weapons = ship != null && ship.isAlive() ? ship.getAllWeapons() : null;
            int idx = rec.sourceMountIndex();
            WeaponAPI weapon = weapons != null && idx >= 0 && idx < weapons.size()
                    ? weapons.get(idx) : null;
            if (weapon == null) {
                adoptUnresolved++;
                lane.log("predict v13 missile-adopt UNRESOLVED: id=" + id + " src="
                        + rec.sourceEntityId() + " mount=" + idx
                        + " (no live local mount — counted-unpresented)");
                continue;
            }
            if (!MISSILE_POSE_APPLY) {
                presenceAdopts++;
                lane.log("predict v13 missile-adopt: id=" + id + " src=" + rec.sourceEntityId()
                        + " mount=" + idx + " tick=" + tick + " (meter)");
                continue;
            }
            float dtick = tick - first.tick;
            long rate = ServerTickRate.ticksPerSecond();
            float vx = (rec.posX() - first.x) / dtick * rate;
            float vy = (rec.posY() - first.y) / dtick * rate;
            float bearing = vx * vx + vy * vy > 1f
                    ? (float) Math.toDegrees(Math.atan2(vy, vx)) : weapon.getCurrAngle();
            // Spawn by TRUE SPEC — the resolved catalog spec rides arg-4 of the
            // 7-arg overload (the vanilla MIRV-child factory seam), so a
            // MIRV child presents as its child spec rather than the launcher recipe. Unset id /
            // defer-expired entry / engine resolve failure degrades to the 6-arg, counted.
            // The launcher RECIPE first — its spec is
            // the weapon spec's private clone, the only spec object the boot-time onHit swap can
            // reach; the true-spec overload reads the pristine registry copy (vanilla onHit) and
            // is now the MIRV-child path only (PresentedMintRecipe javadoc).
            CombatEntityAPI spawned = plugin.engine.spawnProjectile(ship, weapon, weapon.getId(),
                    new Vector2f(rec.posX(), rec.posY()), bearing, new Vector2f());
            if (spawned != null && trueSpec != null && !PresentedMintRecipe.sameSpec(spawned, trueSpec)) {
                CombatEntityAPI child = null;
                try {
                    child = plugin.engine.spawnProjectile(ship, weapon, weapon.getId(), trueSpec,
                            new Vector2f(rec.posX(), rec.posY()), bearing, new Vector2f());
                } catch (Throwable ignore) {
                    // a spec id this client can't resolve — keep the launcher-recipe body
                }
                if (child != null) {
                    plugin.engine.removeEntity(spawned);
                    spawned = child;
                    recipeRemints++;
                } else {
                    specUnresolved++;
                }
            }
            if (spawned == null) {
                adoptUnresolved++;
                lane.log("predict v13 missile-adopt UNRESOLVED: id=" + id + " spawn returned null"
                        + " (weapon=" + weapon.getId() + ")");
                continue;
            }
            // Exclusion FIRST (a replay-ship-source projectile not in the set counts as an
            // autonomous-discharge leak) — consumed-on-sight, round-reset cleared, so a removed
            // ballistic probe's entry is harmless either way.
            plugin.presentedFoeProj.add(spawned);
            plugin.localFire.onPresentedAdd();
            if (!(spawned instanceof MissileAPI)) {
                adoptProbeBallistic++;
                ballisticPairs.add(pairKey(rec.sourceEntityId(), rec.sourceMountIndex()));
                plugin.engine.removeEntity(spawned);
                plugin.localFire.onPresentedEphemeral(); // entry can never be consumed
                // This branch blacklists the pair with NO log (the missile-probe
                // sibling below logs) — per-weapon announce so the permanent fallback is visible.
                FallbackAnnounce.announceOnce("s8-probe-blacklist#" + weapon.getId(),
                        "non-missile probe -> pair blacklisted, local flight law");
                continue;
            }
            MissileAPI missile = (MissileAPI) spawned;
            boolean guided = missile.isGuided();
            boolean flare = missile.isFlare();
            // An engine-less ballistic probe (bomb) is now KEPT as a linked puppet —
            // the wire track is the flight truth (M1 full source pair). Engine-slotted ballistic
            // keeps the probe-remove + blacklist local-flight law.
            boolean bomb = !guided && !flare && BOMB_POSE && engineLess(missile);
            if (!guided && !flare && !bomb) {
                adoptProbeBallistic++;
                ballisticPairs.add(pairKey(rec.sourceEntityId(), rec.sourceMountIndex()));
                plugin.engine.removeEntity(spawned);
                lane.log("predict v13 missile-adopt probe BALLISTIC: id=" + id + " weapon="
                        + weapon.getId() + " (pair blacklisted; local flight law)");
                // Capped lane.log above; per-weapon announce rides.
                FallbackAnnounce.announceOnce("s8-probe-blacklist#" + weapon.getId(),
                        "engine-slotted ballistic probe -> pair blacklisted, local flight law");
                continue;
            }
            if (bomb) {
                bombAdopts++;
            }
            if (flare) {
                enrolledFlare++;
                // The vanilla LAUNCH INSTANT this lane never played. Wire
                // flares arriving as WEAPON_DISCHARGE get it from AuthoritativePresenter (60/75);
                // the 15/75 that arrive here presented SILENTLY — a pre-existing
                // hole, and the owner's vanilla-FEEL bar for the press gate rides on closing it.
                adoptLaunchFx(ship, weapon, rec.posX(), rec.posY(), vx, vy, bearing,
                        tick, first.tick, id);
            } else if (guided) {
                enrolledGuided++;
                // The guided and bomb branches
                // presented SILENTLY too — a replica fighter's bomb (phasecl_bomber, animationType
                // SMOKE) arrives here, never on WEAPON_DISCHARGE, so its vanilla launch puff +
                // fire sound were dropped. Same call, same launch-instant gate; spec-general.
                adoptLaunchFx(ship, weapon, rec.posX(), rec.posY(), vx, vy, bearing,
                        tick, first.tick, id);
            } else {
                enrolledBomb++;
                adoptLaunchFx(ship, weapon, rec.posX(), rec.posY(), vx, vy, bearing,
                        tick, first.tick, id);
            }
            // spawnProjectile never runs onFire, and a Threat fragment carrier's
            // whole visual is the swarm its onFire builds (carrier sprite = empty.png) — relay
            // to OUR wrappers only, treated replica only (FragmentCarrierPresentLaw). The relay
            // is fail-soft; the own ship is never a treated replica (no double swarm).
            ReplicaPresentedFireRelay.onPresented(spawned, weapon, ship, plugin.engine);
            String adoptSpecId = specIdOf(missile);
            // Canister fire sound on the SOURCED adopt — a recorded census overturned
            // the earlier sourceless premise (all 330 canister tracks crossed fully sourced, src=2
            // mounts 6..20), so the canister-sound arm in adoptSourceless is unreachable for this shape.
            // Same launch-truth compromise: the system's own fire sound at the adopt pose, no
            // muzzle flash; end burst already covers sourced twins (spec-keyed onTrackEnd).
            // C1 intangibility at EVERY acquisition seam, knob-independent (the
            // canister law) — this mint is the self-heal door, and a re-minted tangible twin
            // would re-open the prune-bypass the enroll seam just closed.
            if (!flare && MineBombEndFxLane.isCanisterSpec(adoptSpecId)) {
                canisterAcqAdopt++; // per-door acquisition meter
                try {
                    missile.setCollisionClass(CollisionClass.NONE);
                } catch (Throwable ignore) {
                    // presentation lane: a collidable round still renders
                }
                if (MineBombEndFxLane.CANISTER_FX) {
                    try {
                        Global.getSoundPlayer().playSound("system_canister_flak_fire", 1f, 1f,
                                missile.getLocation(), missile.getVelocity());
                        canisterAdoptFx++;
                    } catch (Throwable ignore) {
                        // presentation nicety; the adopt itself stands
                    }
                }
            }
            // Vanilla's post-spawn child damage injection, BEFORE the puppet
            // install (the factory ProximityFuseAI re-syncs like vanilla).
            split.applyChildStash(missile, ship, rec, trueSpec, tick, id); // Law in the split lane
            // presence-adopts register their mtrack here (the PRCP/FLC present
            // tails cover the foe/own routes); class passed explicitly — the no-op install
            // below flips isGuided() before the tracker could read the factory truth.
            plugin.mtrack.register(missile, rec.sourceEntityId(), rec.sourceMountIndex(), tick,
                    "adopt", flare ? "flare" : guided ? "guided" : "bomb");
            ThrustPuppetAI thrustAi = installPuppetAI(missile);
            Vector2f vel = missile.getVelocity();
            if (vel != null) {
                vel.set(vx, vy);
            }
            // Seed re-judge: prefer the v16 wire pose facing (the server's own
            // pointing — on-ray for unguided per the vanilla law) over the drift bearing, exactly
            // as the sourceless arm already does; the drift bearing is the composed FLIGHT line,
            // wrong as pointing for a strafe-launched round. ≤1-frame artifact either way (the
            // first adoptPose overwrites), but the seed frame now shows server truth where bound.
            missile.setFacing(rec.hasPose() ? rec.facingDeg() : bearing);
            Twin t = new Twin(missile, rec.sourceEntityId(), rec.sourceMountIndex(), tick, flare,
                    adoptSpecId, false);
            t.thrustAi = thrustAi;
            t.wireId = id;
            t.lastWireX = rec.posX();
            t.lastWireY = rec.posY();
            t.lastWireTick = tick;
            twins.put(missile, t);
            twinByWireId.put(id, t);
            consumedWireIds.add(id); // freshness ledger (re-adds are idempotent)
            presenceAdopts++;
            linked++;
            lane.log("predict v13 missile-adopt: id=" + id + " src=" + rec.sourceEntityId()
                    + " mount=" + idx + " tick=" + tick + " class="
                    + (flare ? "flare" : guided ? "guided" : "bomb")
                    + " weapon=" + weapon.getId() + " spec=" + t.specId
                    + (trueSpec == null ? " (launcher recipe)" : ""));
        }
    }

    /**
     *
     * Per-second velocity from a sighting pair; a same-tick pair ({@code dtick <= 0} — the
     * first-sighting conversion adopt passes the current tick as its own first sighting) reads
     * zero instead of 0/0 = NaN into the predict path.
     */
    static float sourcelessVelocity(float delta, float dtick, long rate) {
        return dtick <= 0f ? 0f : delta / dtick * rate;
    }

    /**
     * The SOURCELESS presence-adopt: the station-minefield class. The server emits
     * these bodies already (they are ordinary {@code engine.getMissiles()} members); what it cannot
     * emit is a MOUNT, because the minting call is
     * {@code spawnProjectile(station, NULL weapon, "minelayer1", …)} and the server's pair read is
     * weapon-keyed. So this arm reconstructs the body from
     * what the wire DOES carry — stable id, owner, position, the v16 pose, and the true spec via the
     * content catalog — with a weaponless, sourceless mint the engine explicitly supports
     * (a null source is substituted with a synthetic {@code Ship},
     * a null weapon resolves to a null firing weapon, and arg-4 overrides the projectile spec).
     *
     * <p>Three deliberate differences from the sourced arm above, each forced by the absent mount:
     * <ul>
     *   <li>No launcher recipe fallback — with no weapon there is no recipe, so an unresolved true
     *       spec is counted-unpresented rather than degraded into some other body.</li>
     *   <li>No launch FX — {@code adoptLaunchFx} needs a fire point, and a muzzle flash on a hull
     *       that did not fire a mount would be a lie (the launch-FX rule, same reasoning).</li>
     *   <li><b>No {@code isGuided()} ballistic probe.</b> That probe exists to blacklist a MOUNT
     *       that keeps minting unpresentable bodies, and there is no mount here. (A later
     *       correction: a mine would PASS the probe anyway — {@code Missile.isGuided()} is
     *       {@code getAI() instanceof GuidedMissileAI} and a
     *       PHASE_MINE flies {@code GuidedProximityFuseAI}, which implements it,
     *       so {@code isGuided()} is TRUE for a mine, not
     *       FALSE as this doc originally claimed.)</li>
     * </ul>
     *
     * <p><b>The damage property, RE-LAWED.</b> A mine's
     * damage is not its body: {@code GuidedProximityFuseAI} removes the missile and spawns
     * a SEPARATE {@code DamagingExplosion}. The earlier puppet install replaced the AI outright so a
     * presented mine could never detonate — and that also killed vanilla's ping / fuse / windup /
     * explosion AND the {@code Missile.explode()} door (which gates on the fuse AI class), so
     * every mine end presented as painted FX or nothing. A mine body now KEEPS its engine fuse AI
     * ({@link Twin#mine}): its local fuse may prime + detonate on local bodies (a prediction the
     * hull reconcile folds to wire truth, the replica-hit idiom), and the wire END detonates it
     * through {@code explode()}. The fuse AI dereferences {@code getSource().getOriginalOwner()}
     * every frame and the explosion ctor {@code getSource().getOwner()}, so the mint below spawns
     * WITH the wire source's local replica when one resolves, else re-sides the engine's synthetic
     * source ship to the wire owner (the engine substitutes one for a null source).
     * Fail-soft throughout.
     */
    private void adoptSourceless(ServerMissileState rec, long id, long tick, Sighting first,
            String trueSpec) {
        if (trueSpec == null) {
            sourcelessUnresolved++;
            mineLane.log("predict v13 missile-adopt SOURCELESS UNRESOLVED: id=" + id + " contentId="
                    + rec.contentId() + " (no catalog spec; no launcher recipe without a mount)");
            return;
        }
        if (!MISSILE_POSE_APPLY) {
            sourcelessAdopts++;
            sourcelessAdoptsRound++;
            mineLane.log("predict v13 missile-adopt SOURCELESS: id=" + id + " spec="
                    + FallbackAnnounce.sanitize(trueSpec, 64) + " tick=" + tick + " (meter)");
            return;
        }
        float dtick = tick - first.tick;
        long rate = ServerTickRate.ticksPerSecond();
        if (dtick <= 0f) {
            sourcelessZeroDtick++; //
        }
        float vx = sourcelessVelocity(rec.posX() - first.x, dtick, rate); //
        float vy = sourcelessVelocity(rec.posY() - first.y, dtick, rate); //
        float bearing = rec.hasPose() ? rec.facingDeg()
                : (vx * vx + vy * vy > 1f ? (float) Math.toDegrees(Math.atan2(vy, vx)) : 0f);
        CombatEntityAPI spawned;
        // The wire source's LOCAL replica as the mint source — a (N,-1)
        // weaponless-sourced record names its firer (a foe Doom's mine; the own ship's when its
        // newborn never linked). The fuse AI + explosion ctor dereference the source (javadoc).
        ShipAPI sourceShip = null;
        if (rec.hasSource()) {
            try {
                sourceShip = plugin.session != null && rec.sourceEntityId() == plugin.session.shipId()
                        ? plugin.ownShip : registryShip(rec.sourceEntityId());
                if (sourceShip != null && !sourceShip.isAlive()) {
                    sourceShip = null; // a hulk source is no source (the sourced arm's law)
                }
            } catch (Throwable ignore) {
                sourceShip = null;
            }
        }
        // The recipe mint has its OWN try — one shared
        // catch let a recipe throw skip the carrier fallback that exists for exactly this case.
        // A recipe throw is counted and falls through; only a CARRIER throw
        // is counted-unpresented, and its id is refused on re-offer (presenceAdopt).
        spawned = null;
        boolean recipeWeaponUsed = false; // The body carries the recipe weapon's spec damage (its modifiers follow below)
        try {
            // A launcher whose recipe IS this spec mints through the swapped
            // clone (our null-weapon-safe onHit); the true-spec registry mint is the fallback only.
            String recipeWeapon = PresentedMintRecipe.weaponIdForSpec(trueSpec);
            spawned = recipeWeapon == null ? null
                    : plugin.engine.spawnProjectile(sourceShip, null, recipeWeapon,
                            new Vector2f(rec.posX(), rec.posY()), bearing, new Vector2f());
            if (spawned != null && !PresentedMintRecipe.sameSpec(spawned, trueSpec)) {
                plugin.engine.removeEntity(spawned);
                spawned = null;
            }
        } catch (Throwable t) {
            sourcelessRecipeThrew++;
            FallbackAnnounce.announceOnce("sourceless-recipe-mint-threw", "sourceless recipe mint threw "
                    + t.getClass().getSimpleName() + " (first id=" + id + " spec="
                    + FallbackAnnounce.sanitize(trueSpec, 64) + ") -> carrier mint");
            spawned = null;
        }
        if (spawned == null) {
            try {
                spawned = plugin.engine.spawnProjectile(sourceShip, null, SOURCELESS_CARRIER_WEAPON, trueSpec,
                        new Vector2f(rec.posX(), rec.posY()), bearing, new Vector2f());
            } catch (Throwable t) {
                sourcelessUnresolved++;
                sourcelessThrewIds.add(id);
                mineLane.log("predict v13 missile-adopt SOURCELESS THREW: id=" + id + " spec="
                        + FallbackAnnounce.sanitize(trueSpec, 64) + " (" + t.getClass().getSimpleName() + ") — counted-unpresented");
                return;
            }
        } else {
            recipeRemints++;
            recipeWeaponUsed = true;
        }
        if (!(spawned instanceof MissileAPI)) {
            sourcelessUnresolved++;
            if (spawned != null) {
                plugin.engine.removeEntity(spawned);
            }
            mineLane.log("predict v13 missile-adopt SOURCELESS non-missile: id=" + id + " spec="
                    + FallbackAnnounce.sanitize(trueSpec, 64) + " (removed; counted-unpresented)");
            return;
        }
        MissileAPI missile = (MissileAPI) spawned;
        // The recipe mint's second vanilla half. Vanilla's own null-weapon
        // spawners finish the mint by applying the SOURCE's damage modifiers for the launcher's
        // mount type (ENERGY in api RiftLightningEffect.spawnMine, MISSILE in MineStrikeStats; both
        // guard on a non-null source) — the mine's
        // explosion carries the twin's own modifier as its extra components.
        // The base amount arrives with the recipe (the weapon clone's spec damage; PresentedMintRecipe javadoc).
        // Fail-soft: no source, no recipe type or a throw leaves the base amount standing.
        if (recipeWeaponUsed && sourceShip != null) {
            try {
                WeaponAPI.WeaponType mount = PresentedMintRecipe.mountTypeOf(trueSpec);
                if (mount != null) {
                    plugin.engine.applyDamageModifiersToSpawnedProjectileWithNullWeapon(sourceShip, mount,
                            false, missile.getDamage());
                }
            } catch (Throwable ignore) {
                // presentation lane: the recipe's base amount stands
            }
        }
        // Exclusion FIRST (the discipline, verbatim from the sourced arm): a presented body
        // outside the set reads as an autonomous-discharge leak.
        plugin.presentedFoeProj.add(spawned);
        plugin.localFire.onPresentedAdd();
        try {
            // the wire's authority, not the synthetic source's — mapped from the ABSOLUTE wire
            // side to the local owner (a switched seat's station mines/motes re-side)
            int localOwner = plugin.localOwnerForWireSide(rec.owner());
            missile.setOwner(localOwner);
            if (keepsNativeAi(missile)) {
                if (sourceShip != null) {
                    mineSourceReplica++;
                } else {
                    // No replica ⇒ the engine's synthetic source (owner 0 by default):
                    // re-side it so the native fuse scans the RIGHT enemies and the explosion
                    // ctor's getOwner() carries the wire side. Fail-soft: an unreadable source
                    // leaves the default (a wrong-sided scan primes late, never crashes).
                    ShipAPI synthetic = missile.getSource();
                    if (synthetic != null) {
                        synthetic.setOwner(localOwner);
                        synthetic.setOriginalOwner(localOwner);
                    }
                    mineSourceSynthetic++;
                }
            }
        } catch (Throwable ignore) {
            // presentation lane: an unsettable owner still renders, it just picks the engine default
        }
        plugin.mtrack.register(missile, rec.sourceEntityId(), rec.sourceMountIndex(), tick,
                "adopt-sourceless", "sourceless");
        ThrustPuppetAI thrustAi = installPuppetAI(missile);
        // The sourceless CLASS has three members — station
        // mines, the drone-strike conversion missile, and the canister-flak round (hidden
        // SYSTEM weapon absent from getAllWeapons() ⇒ same (0,-1) sentinel).
        boolean droneConversion = DroneStrikeFxLane.isConversionSpec(trueSpec);
        boolean canister = MineBombEndFxLane.isCanisterSpec(trueSpec);
        // The FOURTH sourceless member — the Ziggurat's motes (null weapon
        // on the vanilla mint). A mote is NOT a station mine: it takes neither the mine spawn
        // fade+sound below nor the mine-end explosion (sourcelessMine=false); its launch FX is
        // MoteReplicaLane's, at the slot, on adopt (:onReplicaAdopted). Before this split,
        // every mote adopt played mine_spawn and every mote end painted a mine explosion — the second
        // source of the reported "repeated looped weird fx" beside the phantom local generator.
        boolean mote = MoteReplicaLane.isMoteSpec(trueSpec);
        // The FIFTH member — the served Nova/Orion script bomb
        // (OrionDeviceStats.spawnBomb's body). NOT a station mine: vanilla's spawn presentation
        // (collision NONE, fade-in over the script's bombFadeInTime, no mine_spawn), the fuse-det
        // END door, and the FX-only mirror bound to the source replica's own system script.
        boolean scriptBomb = MineBombEndFxLane.isScriptBombSpec(trueSpec);
        CcServedBombBinder bombBinder = scriptBomb ? scriptBombBinderOf(sourceShip) : null;
        // Canister ALWAYS intangible, knob-independent like the conversion — (a) C1:
        // the spec-true body is MISSILE_NO_FF and would collide with hostiles locally, minting
        // client-side damage the server never dealt; (b) determinism: the end-burst arm keys on
        // the wire end, so no local collision may prune the twin first (the conversion lesson).
        // A script bomb too — vanilla spawnBomb sets NONE (C1-safe AND vanilla).
        boolean intangible = STATION_MINE_INTANGIBLE || droneConversion || canister || scriptBomb;
        if (intangible) {
            // The conversion body is ALWAYS intangible — with both engine fizzle
            // axes dead (maxRange=0 null-weapon mint + the 1e9 flight pin) this makes its
            // survival to the wire end DETERMINISTIC: no local collision can prune the twin
            // before absence-end dispatches the det classifier (rounds 2-5: the body died AT
            // the impact tick — coincident-shell collision class — and the silent prune
            // starved every presentation lane). Impact presentation is OWNED by the det
            // relay (OwnDetLane): exactly one owner per impact.
            try {
                missile.setCollisionClass(CollisionClass.NONE);
            } catch (Throwable ignore) {
                // presentation lane: a collidable mine still renders; the chew stays the knob's
                // A/B signal rather than a crash
            }
        }
        Vector2f vel = missile.getVelocity();
        if (vel != null) {
            vel.set(vx, vy);
        }
        missile.setFacing(bearing);
        // The conversion spec skips the mine FX/end-classification and pairs in the FX lane
        // below; the canister spec skips them for its own arm — mine fade+sound on
        // a flak round was the mislabeled-FX defect (1 s fade hid its short flight).
        if (scriptBomb) {
            // Vanilla spawnBomb verbatim — fadeOutThenIn
            // over the bound script's p.bombFadeInTime (Nova 1.0 / Orion 0.15; unbound = the
            // vanilla ctor value), NO mine_spawn (vanilla plays none; the system's useSound rides
            // the replica's own press).
            try {
                missile.fadeOutThenIn(CcServedBombMirror.fadeInFor(bombBinder != null ? bombBinder
                        : CcServedBombMirror.defaultBinder(trueSpec), trueSpec));
            } catch (Throwable e) {
                FallbackAnnounce.announceOnce("scriptbomb-fade",
                        "script-bomb spawn fade threw " + e.getClass().getSimpleName()
                        + " (first id=" + id + ") -> the body shows without the vanilla fade-in");
            }
        } else if (canister) {
            canisterAcqSourceless++; // per-door acquisition meter
            if (MineBombEndFxLane.CANISTER_FX) {
                // launch-truth compromise — no mount ⇒ no muzzle flash,
                // but the system's own fire sound at the adopt position is honest (the round
                // did launch server-side); end burst = the end lane's canister arm.
                try {
                    Global.getSoundPlayer().playSound("system_canister_flak_fire", 1f, 1f,
                            missile.getLocation(), missile.getVelocity());
                    canisterAdoptFx++;
                } catch (Throwable ignore) {
                    // presentation nicety; the adopt itself stands
                }
            }
        } else if (MINE_FX && !droneConversion && !mote) {
            // Vanilla's own minefield spawn presentation, verbatim:
            // snap invisible, fade in over 1 s, mine_spawn at the body. The wire carries no
            // phase/windup state (poseFlags = VALID|FIZZLING only), so the spec timing
            // is mirrored client-side; the true-spec body (PHASE_MINE) keeps whatever the
            // engine renders for its type. Known residue: a joiner adopting a mid-life
            // minefield replays the spawn fade+sound once per mine — the sighting age cannot
            // distinguish a fresh mint from a first sight (no wire spawn tick), and the common
            // case (mines minted through the match, adopted at ~2 ticks old) is exact.
            try {
                missile.fadeOutThenIn(MINE_SPAWN_FADE_SECONDS);
                Global.getSoundPlayer().playSound("mine_spawn", 1f, 1f,
                        missile.getLocation(), missile.getVelocity());
                mineSpawnFx++;
            } catch (Throwable ignore) {
                // presentation nicety; the adopt itself stands
            }
        }
        Twin t = new Twin(missile, rec.sourceEntityId(), rec.sourceMountIndex(), tick, false,
                specIdOf(missile), false);
        t.thrustAi = thrustAi;
        // sourcelessMine means the STATION-MINE class specifically — canister opts
        // out like the conversion, which also opts it out of mine prime detection and the
        // mine end classifier (its INTACT ends presented the r250 mine det otherwise).
        t.sourcelessMine = !droneConversion && !canister && !mote && !scriptBomb; // + script bomb
        t.mintSourceNull = sourceShip == null; //
        t.wireId = id;
        t.lastWireX = rec.posX();
        t.lastWireY = rec.posY();
        t.lastWireTick = tick;
        twins.put(missile, t);
        twinByWireId.put(id, t);
        consumedWireIds.add(id);
        sourcelessAdopts++;
        sourcelessAdoptsRound++;
        sourcelessPresenceAdopts++; // Pairs the bump below OUT of the feed
        presenceAdopts++;
        linked++;
        if (droneConversion) {
            droneStrike.onConversionAdopt(t);
        }
        if (MoteReplicaLane.isMoteSpec(t.specId)) {
            // replicas-only motes — the body joins the nearest same-side Ziggurat's
            // vanilla shared list (hum + lock semantics) and gets vanilla's launch FX at the slot.
            plugin.moteReplica.onReplicaAdopted(missile, plugin.localOwnerForWireSide(rec.owner()),
                    plugin.engine); // The nearest SAME-SIDE Ziggurat is a local-owner match
        }
        String adoptRow = "predict v13 missile-adopt SOURCELESS: id=" + id + " spec=" + t.specId
                + " owner=" + rec.owner() + " tick=" + tick + " carrier="
                + SOURCELESS_CARRIER_WEAPON
                + " intangible=" + intangible
                + " fx=" + (canister ? "canister:" + MineBombEndFxLane.CANISTER_FX
                        : mote ? "mote:launch" : String.valueOf(MINE_FX))
                + " birthFizz=" + rec.fizzling();
        if (droneConversion) {
            convLog(adoptRow);
        } else if (scriptBomb) {
            bindScriptBomb(t, missile, sourceShip, bombBinder, trueSpec, id, rec.sourceEntityId(), tick);
        } else {
            lane.log(adoptRow);
        }
    }

    /** The source replica's own system script when it is a
     *  served-bomb binder ({@code ShipAPI.getSystem().getScript()}); {@code null} = no
     *  source, no system, or a script of another kind. Fail-soft. */
    private static CcServedBombBinder scriptBombBinderOf(ShipAPI sourceShip) {
        if (sourceShip == null) {
            return null;
        }
        try {
            com.fs.starfarer.api.combat.ShipSystemAPI sys = sourceShip.getSystem();
            Object script = sys == null ? null : sys.getScript();
            return script instanceof CcServedBombBinder ? (CcServedBombBinder) script : null;
        } catch (Throwable e) {
            FallbackAnnounce.announceOnce("scriptbomb-binder-read",
                    "script-bomb source system-script read threw " + e.getClass().getSimpleName()
                    + " -> the detached default binder (FX + flash only)");
            return null;
        }
    }

    /** The bind verdict, pure (selftest row): a known
     *  source whose script binds ⇒ {@code "script"}; a known source whose script does not ⇒
     *  {@code "default"} (the detached per-spec binder: FX + flash only); NO source ⇒
     *  {@code "none"} (its END paints, and no door ever flames the body out, so a mirror
     *  could never trigger — none is installed). */
    static String scriptBombBindVerdict(boolean sourceKnown, boolean scriptBinds) {
        if (!sourceKnown) {
            return "none";
        }
        return scriptBinds ? "script" : "default";
    }

    /** The own-skip predicate, pure (selftest row): an
     *  own-sourced sourceless record of the script-bomb family is never adopted. */
    static boolean scriptBombOwnSkip(boolean ownSourcedSourceless, String trueSpec) {
        return ownSourcedSourceless && MineBombEndFxLane.isScriptBombSpec(trueSpec);
    }

    /** Bind the served script bomb's FX-only mirror
     * after the twin is stored, then the ONE adopt row for the bomb (through
     *  {@code mineLane}, its budget). A bind throw is counted as {@code bound=none}, announced once. */
    private void bindScriptBomb(Twin t, MissileAPI missile, ShipAPI sourceShip,
            CcServedBombBinder bombBinder, String trueSpec, long id, long srcId, long tick) {
        scriptBombAdopts++;
        String bound = scriptBombBindVerdict(sourceShip != null, bombBinder != null);
        String slot = "-";
        String press = "none";
        try {
            if ("script".equals(bound)) {
                slot = bombBinder.bindServedBomb(sourceShip, missile, new Vector2f(missile.getLocation()));
                press = bombBinder.lastSkip();
                scriptBombBinds++;
            } else if ("default".equals(bound)) {
                CcServedBombBinder def = CcServedBombMirror.defaultBinder(trueSpec);
                if (def == null) {
                    bound = "none";
                } else {
                    slot = def.bindServedBomb(null, missile, new Vector2f(missile.getLocation()));
                    scriptBombBindDefault++;
                }
            }
        } catch (Throwable e) {
            bound = "none";
            FallbackAnnounce.announceOnce("scriptbomb-bind",
                    "script-bomb mirror bind threw " + e.getClass().getSimpleName() + " (first id=" + id + " spec="
                    + FallbackAnnounce.sanitize(trueSpec, 64) + ") -> no mirror FX for this bomb (the engine door det still runs)");
        }
        mineLane.log("predict v13 missile-adopt SCRIPTBOMB: id=" + id + " spec=" + t.specId + " src=" + srcId
                + " slot=" + slot + " bound=" + bound + " tick=" + tick + " press=" + press);
    }

    /** conversion-forensics rows bypass the shared lane cap: station-mine spam
     *  exhausts {@code lane.log}'s per-combat budget long before the first terminator converts
     *  (adopt/PAIR/FIZZLE-LATCHED rows all silently dropped; meters were the only
     *  witness). These rows are a handful per run; their own small budget keeps the sidelog
     *  bounded against a degenerate wire. */
    private int convLogLines;

    void convLog(String line) {
        if (convLogLines < 240) {
            convLogLines++;
            PilotWatchLog.info(line);
        }
    }

    /**
     * The presence-adopt LAUNCH FX: the EXACT pair
     * {@link AuthoritativePresenter#presentFireFx} plays for a wire discharge —
     * {@code engine.spawnMuzzleFlashOrSmoke(ship, firePoint, weapon spec, bearing)} then
     * {@code Global.getSoundPlayer().playSound(FireSoundAdapter.fireSoundId(weapon)…)} at the
     * shooter's velocity, pitch/volume 1.0. For a flare launcher that IS the vanilla launch instant:
     * {@code flarelauncher2.wpn} is {@code animationType=SMOKE} + {@code fireSoundTwo=launch_flare_1},
     * and the engine's own per-shot path runs the same discriminator,
     * so the smoke branch + that sound is
     * literally what vanilla draws. Lane-internal and knobless — this closes a pre-existing silence,
     * it does not change what the sim does; the {@code playSplitMoment} MIRV moment is the in-lane
     * precedent. Every piece fails soft on its own and is counted ({@code adopt_fx_*}), the
     * presentFireFx discipline. Covers flares, and guided + bomb adopts (the replica
     * Flash bomber's puff).
     */
    private void adoptLaunchFx(ShipAPI ship, WeaponAPI weapon, float posX, float posY,
            float vx, float vy, float bearing, long tick, long firstTick, long id) {
        try {
            Vector2f muzzle = weapon.getFirePoint(0);
            if (muzzle == null) {
                muzzle = weapon.getLocation(); // the AuthoritativePresenter fallback
            }
            if (muzzle == null || !isLaunchInstant(muzzle, posX, posY, vx, vy, tick, firstTick)) {
                adoptFxSkipped++;
                lane.log("predict v13 missile-adopt FX SKIP: id=" + id + " weapon=" + weapon.getId()
                        + " sightAge=" + (tick - firstTick)
                        + " (mid-flight first sighting — a launch flash here would be a lie)");
                return;
            }
            try {
                plugin.engine.spawnMuzzleFlashOrSmoke(ship, muzzle, weapon.getSpec(), bearing);
                adoptFxFlash++;
            } catch (Throwable t) {
                adoptFxSkipped++;
            }
            String soundId = null;
            try {
                soundId = FireSoundAdapter.fireSoundId(weapon);
                if (soundId == null) {
                    adoptFxSkipped++;
                } else {
                    Global.getSoundPlayer().playSound(soundId, 1f, 1f, muzzle, ship.getVelocity());
                    adoptFxSound++;
                }
            } catch (Throwable t) {
                adoptFxSkipped++;
            }
            lane.log("predict v13 missile-adopt FX: id=" + id + " weapon=" + weapon.getId()
                    + " sound=" + soundId + " sightAge=" + (tick - firstTick));
        } catch (Throwable ignore) {
            // presentation lane; never break the watch
        }
    }

    /**
     * The recency guard, standing in for the wire spawn tick the missile tier does not carry:
     * the adopt must be within {@link #ADOPT_FX_RECENT_TICKS} of the id's FIRST sighting AND the
     * adopted body must still sit within its own measured travel over that gap (+
     * {@link #ADOPT_FX_MUZZLE_SLACK}) of the fire point. The second test is the one that catches a
     * mid-flight FIRST sighting — a joiner's in-flight flare has a 1-tick sighting age too, but it
     * is hundreds of su downrange, and a smoke puff at its launcher would be pure fiction.
     */
    private static boolean isLaunchInstant(Vector2f muzzle, float posX, float posY,
            float vx, float vy, long tick, long firstTick) {
        long gap = tick - firstTick;
        if (gap < 0L || gap > ADOPT_FX_RECENT_TICKS) {
            return false;
        }
        float speed = (float) Math.sqrt(vx * vx + vy * vy);
        float reach = speed * (gap + ADOPT_FX_LAG_TICKS) / ServerTickRate.ticksPerSecond()
                + ADOPT_FX_MUZZLE_SLACK;
        float dx = posX - muzzle.x;
        float dy = posY - muzzle.y;
        return dx * dx + dy * dy <= reach * reach;
    }

    /** Launch-smoke presentations — read by {@link LocalFireCensus#meterTokens}. */
    int adoptFxFlash() {
        return adoptFxFlash;
    }

    /** Launch-sound presentations — read by {@link LocalFireCensus#meterTokens}. */
    int adoptFxSound() {
        return adoptFxSound;
    }

    /** Skipped launch FX (not a launch instant, or a fail-soft piece). */
    int adoptFxSkipped() {
        return adoptFxSkipped;
    }

    /** Link hardening: TRUE iff both sides carry spec truth and they differ — the
     *  wire record is a different projectile riding the same source pair (a MIRV child inherits
     *  its launcher's attribution) and must not claim this twin. Either side unknown
     *  (unreadable local spec, unset contentId, catalog entry not yet announced) fails OPEN to
     *  today's pair-window law. */
    private boolean isCrossSpec(Twin t, ServerMissileState rec) {
        if (t.specId == null || rec.contentId() < 1) {
            return false;
        }
        String wireSpec = plugin.contentCatalog.get(rec.contentId());
        return wireSpec != null && !wireSpec.equals(t.specId);
    }

    /** The missile's own projectile spec id (public {@code DamagingProjectileAPI} read), null on
     *  any failure — spec truth is a presentation nicety, never load-bearing. */
    static String specIdOf(MissileAPI missile) {
        try {
            return missile.getProjectileSpecId();
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** A local missile body's census label ({@link BodyCensusRows}); null = LOCAL-ONLY. */
    String censusKeyOf(MissileAPI missile) {
        Twin t = twins.get(missile);
        if (t == null) {
            return null;
        }
        return (t.own ? "own-twin" : "wire") + (t.wireId == -1L ? " unlinked" : " wire=" + t.wireId);
    }

    /** The bots-shape source resolve (the beam-aim idiom). */
    /** The local tick a mine id's body left play before its wire end
     *  (null = it lived to the wire end); the end lane's lead/lag row reads it. */
    Long mineLocalDetTick(long wireId) {
        return mineLocalDetTicks.get(wireId);
    }

    ShipAPI registryShip(long entityId) {
        PredictReconcileCombatPlugin.RemoteEntity re = plugin.remoteEntities.get(entityId);
        return re != null ? re.ship : null;
    }

    /** Source-pair key (entityId is small; mount &lt; 4096 by construction). */
    static long pairKey(long sourceEntityId, int mountIndex) {
        return (sourceEntityId << 12) ^ (mountIndex & 0xFFF);
    }

    /** CL-2 dedupe read of the freshness ledger: TRUE iff the twin machinery
     *  ever claimed this id — its end presentation had an owner, so a buffered structural
     *  present would be the double-present. */
    boolean wireIdEverConsumed(long id) {
        return consumedWireIds.contains(id);
    }

    /** Live puppet bodies (the twins keyset, read-only view) — the DEM presenter's
     *  candidate pool (the wireIdFor accessor class). */
    java.util.Set<MissileAPI> puppetBodies() {
        return twins.keySet();
    }

    /** The stable v13 wire id puppeting this missile, or -1 (unlinked/untracked) — the
     *  {@code cc-mtrack} pos-line exact-join key. */
    long wireIdFor(MissileAPI missile) {
        Twin t = twins.get(missile);
        return t == null ? -1L : t.wireId;
    }

    /** The cc-mtrack pos-line correction suffix. The law lives in
     *  {@link OwnMissileHandoffLane#corrTokens}; thin relay so the mtrack call site stays. */
    String ownCorrTokens(MissileAPI missile) {
        return own.corrTokens(missile);
    }

    /** The puppet body currently linked to this stable v13 wire id, or {@code null} —
     *  the inverse of {@link #wireIdFor}, and the DEM attribution join
     *  (the server names the child that owns a beam; this resolves it to a live body). */
    MissileAPI bodyForWireId(long wireId) {
        Twin t = wireId < 0L ? null : twinByWireId.get(Long.valueOf(wireId));
        return t == null ? null : t.missile;
    }

    /** Round reset: totals line (FoeHull discipline), then the per-round identity state clears —
     *  counters stay cumulative across rounds, except the one ROUND twin
     *  {@code sourcelessAdoptsRound}. */
    void resetForMatchReset() {
        for (Twin t : twins.values()) {
            if (!t.own && DroneStrikeFxLane.isConversionSpec(t.specId)) {
                // The last silent conversion-twin exit (round wrap): loud, bounded.
                convLog("predict v16 conv reset-LIVE: id=" + t.wireId + convForensics(t));
            }
        }
        PilotWatchLog.info("predict missile-lane match-reset totals: twins=" + twins.size()
                + " enrolledGuided=" + enrolledGuided + " enrolledFlare=" + enrolledFlare
                + " linked=" + linked + " presenceAdopts=" + presenceAdopts
                + " endRemoved=" + endRemoved + " localGone=" + localGone
                // the freshness ledger's size at
                // its SOLE clear — a round that ends with a huge consumed set is the mislink-guard
                // working (or a leak) and was invisible before this field.
                + " consumedWireIds=" + consumedWireIds.size());
        twins.clear();
        twinByWireId.clear();
        sightings.clear();
        ballisticPairs.clear();
        split.resetForMatchReset();
        droneStrike.resetForMatchReset();
        dictDefer.clear();
        fizzledIds.clear();
        sourcelessThrewIds.clear(); // Ids are reused per round
        sourcelessAdoptsRound = 0; // The C32 residual it offsets zeroes in this same PRCP reset
        end.resetForMatchReset(); // end-cause verdicts are round-local like the twins they key
        sourcedEnd.resetForMatchReset(); // The VICTIM_MISMATCH announce-once ids are round-local (ids reused per round)
        ballistic.resetForMatchReset(); // Link state is round-local like the pairs it keys
        consumedWireIds.clear();
        consumedRefusalLogged.clear();
        own.resetForMatchReset();
    }

    /** The lane's greppable meter/census line (the counter-line discipline); silent at off
     *  (both knobs). NB the apply assert rides the PRCP init announce, never this line. */
    void logMeter(String where) {
        if (!MISSILE_POSE_METER && !OwnMissileHandoffLane.OWN_MISSILE_METER) {
            return;
        }
        PilotWatchLog.info(meterLine(where));
    }

    /** The census line {@link #logMeter} emits, as a
     *  value — {@code MineNativeFuseLawSmoke} reads its real tokens (byte-identical to the pre-split
     *  line plus the tokens). */
    String meterLine(String where) {
        return "predict v13 missile-meter [" + where + "]:"
                + " mode=" + MISSILE_POSE_MODE
                + " own=" + OwnMissileHandoffLane.OWN_MISSILE_MODE
                + " predict_missile_enrolled_guided=" + enrolledGuided
                + " predict_missile_enrolled_flare=" + enrolledFlare
                + " predict_missile_enrolled_bomb=" + enrolledBomb
                + " predict_missile_enroll_skip_ballistic=" + enrollSkipBallistic
                // The prox-charge relay skip + retire hold.
                + " predict_missile_prox_fizzle_skipped=" + proxFizzleSkipped
                + " predict_missile_retire_held_engine_det=" + retireHeldEngineDet
                + " predict_missile_noop_installs=" + noopInstalls
                + " predict_missile_thrust_installs=" + thrustInstalls
                + " predict_missile_mote_glow_installs=" + moteGlowInstalls
                + " predict_missile_mote_glow_handle=" + (showAccelerating != null ? "ok"
                        : showAcceleratingTried ? "MISSING" : "unresolved")
                + " predict_missile_mote_glow_handle_missing=" + moteGlowHandleMissing
                + " predict_missile_mote_lit_frames=" + moteLitFrames
                + " predict_missile_mote_unlit_frames=" + moteUnlitFrames
                + " predict_missile_mote_lit_wire=" + moteLitWire
                + " predict_missile_mote_lit_arc=" + moteLitArc
                + " predict_missile_flare_glow_installs=" + flareGlowInstalls
                + " predict_missile_flare_lit_frames=" + flareLitFrames
                + " predict_missile_flare_unlit_frames=" + flareUnlitFrames
                + " predict_missile_mote_chop_handle=" + (INTERRUPT_CONTRAIL != null ? "ok"
                        : "MISSING:" + INTERRUPT_CONTRAIL_FAILURE)
                + " predict_missile_thrust_stale_gaps=" + thrustStaleGaps
                + " predict_missile_linked=" + linked
                + " predict_missile_link_expired=" + linkExpired
                + " predict_missile_link_cross_spec=" + linkCrossSpec
                + " predict_missile_link_refused_consumed=" + linkRefusedConsumed
                + " predict_missile_pose_frames=" + poseFrames
                + " predict_missile_pose_sets=" + poseSets
                + " predict_missile_pose_facing_sets=" + poseFacingSets
                + " predict_missile_fizzle_relays=" + fizzleRelays
                + " predict_missile_fizzle_relay_parked=" + fizzleRelaysParked
                + " predict_missile_fizzle_fade_intangible=" + fizzleFadeIntangible
                // The wire DUD mirror's relay split + kept duds.
                + " predict_missile_dud_relayed=" + dudRelayed
                + " predict_missile_armed_relayed=" + armedRelayed
                + " predict_missile_bounce_kept=" + bounceKept
                + " predict_missile_dud_flips=" + dudFlips
                + " predict_missile_presence_adopts=" + presenceAdopts
                + " predict_missile_adopt_probe_ballistic=" + adoptProbeBallistic
                + " predict_missile_bomb_adopts=" + bombAdopts
                + " predict_missile_adopt_unresolved=" + adoptUnresolved
                + " predict_missile_spec_unresolved=" + specUnresolved
                + " predict_missile_recipe_remints=" + recipeRemints
                // Recipe throws that fell through to
                // the carrier; the ROUND twin of the whole-match adopts token (the residual bar's term).
                + " predict_missile_sourceless_recipe_threw=" + sourcelessRecipeThrew
                + " predict_missile_sourceless_adopts=" + sourcelessAdopts
                + " predict_missile_sourceless_adopts_round=" + sourcelessAdoptsRound
                // Appended beside its raw siblings: the pair subtracted from the
                // starvation feed — presence_adopts minus THIS is what the alarm reads.
                + " predict_missile_sourceless_presence_adopts=" + sourcelessPresenceAdopts
                + " predict_missile_sourceless_skipped=" + sourcelessSkipped
                + " predict_missile_sourceless_own_degraded=" + sourcelessOwnDegraded
                + " predict_missile_sourceless_unresolved=" + sourcelessUnresolved
                + " predict_missile_sourceless_threw_refused=" + sourcelessThrewRefused
                + " predict_missile_sourceless_first_adopts=" + sourcelessFirstAdopts
                + " predict_missile_sourceless_zero_dtick=" + sourcelessZeroDtick //
                + " predict_missile_mine_spawn_fx=" + mineSpawnFx
                // The served script-bomb adopt + wrapper/mirror meters
                + " predict_missile_scriptbomb_adopts=" + scriptBombAdopts
                + " predict_missile_scriptbomb_binds=" + scriptBombBinds
                + " predict_missile_scriptbomb_bind_default=" + scriptBombBindDefault
                + " predict_missile_scriptbomb_own_skipped=" + scriptBombOwnSkipped
                + CcServedBombMirror.meterTokens()
                + " predict_missile_mine_native_fuse_kept=" + mineNativeFuseKept
                + " predict_missile_mine_fizzle_skipped=" + mineFizzleSkipped
                + " predict_missile_mine_local_gone=" + mineLocalGone
                + " predict_missile_mine_readopt_refused=" + mineReadoptRefused
                + " predict_missile_mine_source_replica=" + mineSourceReplica
                + " predict_missile_mine_source_synthetic=" + mineSourceSynthetic
                + " predict_missile_canister_adopt_fx=" + canisterAdoptFx
                + " predict_missile_canister_acq_enroll=" + canisterAcqEnroll
                + " predict_missile_canister_acq_adopt=" + canisterAcqAdopt
                + " predict_missile_canister_acq_sourceless=" + canisterAcqSourceless
                + " predict_missile_canister_acq_skip=" + canisterAcqSkip
                // The mine/bomb end-of-life token block is emitted by the end lane, at
                // this exact position — the line stays byte-identical.
                + end.meterTokens()
                // The sourced end-confession token block (same splice discipline).
                + sourcedEnd.meterTokens()
                // The skip-set end-link token block (same splice discipline).
                + ballistic.meterTokens()
                + " predict_missile_end_removed=" + endRemoved
                + " predict_missile_local_gone=" + localGone
                // The split/child-override token block is emitted by the split lane, at this
                // exact position — the line stays byte-identical (the meterTokens precedent).
                + split.meterTokens()
                + droneStrike.meterTokens()
                + " predict_missile_dict_deferred=" + dictDeferred
                + " predict_missile_dict_defer_ok=" + dictDeferOk
                + " predict_missile_dict_defer_expired=" + dictDeferExpired
                + own.meterTokens()
                + " twins=" + twins.size();
    }

    /** The MISSILE_SPLIT presentation entry. The law lives in
     *  {@link MissileSplitLane#onSplit}; thin relay so the PRCP edge-event call site stays. */
    void onSplit(cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        split.onSplit(event);
    }

    /** The MISSILE_END (kind 6) intake. The store + the
     *  intake/classifier law live in {@link MineBombEndFxLane}; thin relay so the PRCP
     *  edge-event call site stays (the {@link #onSplit} idiom). The sourced same-tick retire hook
     *  runs AFTER the store intake (route-first law —
     *  the store is written before its consumers ask). */
    void onMissileEnd(cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        end.onMissileEnd(event);
        sourcedEnd.onEnd(event);
    }
}
