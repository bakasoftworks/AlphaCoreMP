package cc.starsector.client;

import cc.starsector.client.net.ClientAsteroidFrame;
import cc.starsector.client.net.ClientAsteroidRow;
import cc.starsector.client.net.PredictReconcileDatagramSession;
import cc.starsector.client.net.PredictSession;
import com.fs.starfarer.api.combat.CombatAsteroidAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Battle terrain slice C, asteroid authority -- the CLIENT
 * asteroid lane.
 *
 * <p>WHY: the server's {@code AsteroidFieldServeLane} owns existence + pose + hp of every
 * asteroid (the engine's own field keeper, {@code combat/A/E}, never runs on either JVM: neither
 * side ever calls vanilla's field or ring-asteroid builders; the smoke greps the
 * client tree for their names) and fans them as
 * {@code ASTP} rows. This lane mints the local twin through the public API
 * ({@code spawnAsteroid(int, ...)} -- ring always false) and overwrites the engine's own
 * per-spawn randomness (radius + rendered sprite scale, facing, angVel, hp) from the wire; between
 * rows it re-asserts the server's ballistic coast on the presented-tick clock and YIELDS to the
 * local engine's own collision response -- the hulk pose law ported verbatim
 * ({@link FractureFamilyCoordinator}: coast, yield,
 * contact glide). Local bumps against replicas and the own ship are the local engine's own physics;
 * the server's next row corrects the asteroid.
 * Existence forks only on wire rows: ALIVE mints-or-adopts, DESTROYED kills through vanilla's own
 * {@code Asteroid.applyDamage} (native explosion + removal -- never painted FX), EXPIRED removes
 * quietly. A client-local kill (the body left play with no wire row) is metered honestly
 * ({@code local_gone}, and {@code remints} when a later ALIVE re-mints it) -- never concealed.
 *
 * <p>Round law: frames carry the session's net-thread MRST epoch
 * ({@code PredictReconcileDatagramSession#ctrlRoundEpoch}); a frame from a newer epoch
 * removes every live twin and re-baselines before it applies, a frame from an older epoch is
 * dropped -- so the lane never depends on whether the plugin's MRST rebuild ran (it sweeps ships +
 * projectiles only, never asteroids). A reborn server ({@code serverLife} change) or a fresh
 * session object is the same flip (its id counter / epoch counter restarted).
 *
 * <p>NEVER ADD LATENCY: a row applies the frame it is drained; the glide is a decaying offset
 * on a correction, never a hold-back. Frame-loop law: zero work at rest (no bodies, no frames).
 * B-legal: api-jar types + our own client classes only. Render thread only.
 */
final class AsteroidLane {

    // ---- knobs (the ArcHitFxLane.parseOnOff idiom; an invalid word throws at class load) ----

    /** {@code cc.predict.asteroids} = {@code off|meter|apply} (default {@code apply}). */
    static final String MODE = parseMode(System.getProperty("cc.predict.asteroids", "apply"));
    /** {@code cc.predict.asteroidContactGlide} = {@code on|off} (default on; off = pure snap). */
    static final boolean CONTACT_GLIDE = parseOnOff("cc.predict.asteroidContactGlide",
            System.getProperty("cc.predict.asteroidContactGlide", "on"));

    // ---- the hulk pose law constants, verbatim from FractureFamilyCoordinator ----
    static final float YIELD_TOL_US = 2f;
    static final float YIELD_ANG_TOL_DEG_S = 5f;
    static final float CONTACT_GLIDE_SECONDS = 0.125f;
    static final float CONTACT_GLIDE_VEL_US = 2f;
    static final float CONTACT_GLIDE_MIN_U = 0.5f;
    static final float POSE_LARGE_SNAP_UNITS = 50f;
    /** The server keeper's hp/burst cadence (ticks): an ALIVE row within this many ticks of a
     *  local kill predates it (stale evidence, not a fork). */
    static final long REMINT_STALE_CADENCE_TICKS = 4L;
    /** Sentinel: adopt the next drained frame's epoch as the baseline (fresh session/life). */
    static final int EPOCH_UNSET = Integer.MIN_VALUE;
    static final int LOG_MAX = 20;
    static final int LOG_EVERY_FRAMES = 60;
    /**
     * Tracked-id ceiling. A mint that would track a
     * 513th body is refused, counted {@code asteroid_mint_capped=} on the sidelog row and logged once:
     * a wire flood can never grow the frame-loop coast set without bound.
     *
     */
    static final int MAX_TRACKED = 512;

    /** One tracked twin: the entity + the coast anchor (the last adopted row) + glide state. */
    static final class Body {
        CombatEntityAPI entity; // null under MODE meter (count-only)
        int size;
        float radius;
        long anchorTick; // Long.MIN_VALUE = yield-seeded (game-time integral until the next row)
        float ax, ay, afacing, avx, avy, aang;
        long glideStartTick = Long.MIN_VALUE; // decays on the presented-tick clock
        float gx, gy, gf; // the glide offset (current - led), ADDED to the coast
        boolean yieldSeeded;
        float lastHp;
        int rowFrame = -1; // advance frame of the last row (the coast skips it)
    }

    final PredictReconcileCombatPlugin plugin;
    /** Package-visible mode seam (the smoke drives {@code off}/{@code meter} without a fresh JVM). */
    String mode = MODE;
    CombatEngineAPI lastEngine;
    PredictSession lastSession;
    long lastServerLife;
    int appliedEpoch = 0;
    final List<ClientAsteroidFrame> held = new ArrayList<ClientAsteroidFrame>();
    final Map<Integer, Body> bodies = new LinkedHashMap<Integer, Body>();
    final Set<Integer> seenIds = new HashSet<Integer>();
    final Map<Integer, Long> goneAtTick = new HashMap<Integer, Long>();

    long minted, adopted, yields, glides, deaths, expired, remints, remintStale, localGone;
    long dropped, droppedStale, heldFrames, flips, killRowsUntracked, applyFailures, coastFailures;
    long advanceNsMax, advanceNsTotal, advanceFrames;
    /** {@code mintFailures} = a null
     *  {@code spawnAsteroid} or a post-spawn set-up throw (the twin is rolled back);
     *  {@code mintCapped} = mints refused at {@link #MAX_TRACKED}; {@code queueDropped} = the
     *  session's {@code asteroidFramesDropped()} (the 256-frame intake cap; the
     *  count had no reader before this poll), polled once per {@link #LOG_EVERY_FRAMES} frames. */
    long mintFailures, mintCapped, queueDropped;
    /** The intake's death-row law -- death-only frames kept past the
     *  cap ({@code death_kept=}) and death rows lost past its bound ({@code death_dropped=}, each
     *  a possible ghost twin = a FINDING); polled beside {@link #queueDropped}. */
    long deathKept, deathDropped;
    private int lastDropPollFrame = Integer.MIN_VALUE / 2;
    private int frameNo;
    private int lastLogFrame = Integer.MIN_VALUE / 2;
    private long lastLoggedSig = -1L;
    private int logLines;
    private final Set<String> logOnce = new HashSet<String>();
    String lastRow;

    AsteroidLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ per frame

    /** Once per fed frame, AFTER PRCP's fresh-snapshot {@code lastSnapshotSimTick} update (the
     *  hulk-coast placement; before it every rock coasted at T-1); the smoke's
     *  {@code order.prcp} row pins it. */
    void advance(CombatEngineAPI engine) {
        frameNo++;
        PredictSession s = plugin.session;
        if (s == null) {
            return; // pre-bind advance frames: nothing can arrive
        }
        // (a) the bodies belong to the old engine: forget them (never touch a dead engine).
        if (engine != lastEngine) {
            bodies.clear();
            seenIds.clear();
            goneAtTick.clear();
            lastEngine = engine;
        }
        // (a2) a reborn server (id counter restarted) or a fresh session (epoch counter
        // restarted) = an epoch flip + an epoch re-baseline.
        long life = s.serverLife();
        if (life != lastServerLife || s != lastSession) {
            removeAll(engine);
            held.clear();
            lastServerLife = life;
            lastSession = s;
            appliedEpoch = EPOCH_UNSET;
        }
        List<ClientAsteroidFrame> frames = s.takeAsteroidPoses();
        if (frames == null) {
            frames = Collections.emptyList(); // proxy sessions return null = none
        }
        // (a3) nothing tracked and nothing drained = 0 work.
        if (bodies.isEmpty() && frames.isEmpty()) {
            maybeLog();
            return;
        }
        // (b) off: drain + count, never touch the engine.
        if ("off".equals(mode)) {
            dropped += frames.size();
            maybeLog();
            return;
        }
        long t0 = System.nanoTime();
        long presentedTick = plugin.lastSnapshotSimTick;
        float tickRate = ServerTickRate.ticksPerSecond();
        // (c) epoch law, frames in drain order (net-thread stamps are non-decreasing).
        held.addAll(frames);
        for (ClientAsteroidFrame f : held) {
            if (appliedEpoch == EPOCH_UNSET) {
                appliedEpoch = f.roundEpoch();
            }
            int action = epochAction(f.roundEpoch(), appliedEpoch);
            if (action < 0) {
                droppedStale++;
                continue;
            }
            if (action > 0) {
                heldFrames++;
                flips++;
                removeAll(engine);
                appliedEpoch = f.roundEpoch();
            }
            for (ClientAsteroidRow r : f.rows()) {
                try {
                    applyRow(engine, r, f.simTick(), presentedTick, tickRate);
                } catch (Throwable t) {
                    applyFailures++;
                    logOnce("asteroid_apply_failed " + t);
                }
            }
        }
        held.clear();
        // (d) prune twins that left play with no DESTROYED/EXPIRED row (a client-local kill).
        for (Iterator<Map.Entry<Integer, Body>> it = bodies.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Body> e = it.next();
            Body b = e.getValue();
            if (b.entity == null) {
                continue; // meter mode: count-only bodies
            }
            boolean inPlay;
            try {
                inPlay = engine.isEntityInPlay(b.entity);
            } catch (Throwable t) {
                coastFailures++;
                logOnce("asteroid_prune_failed " + t);
                continue;
            }
            if (!inPlay) {
                it.remove();
                localGone++;
                goneAtTick.put(e.getKey(), presentedTick);
            }
        }
        // (e) coast every tracked body that got no row this frame.
        float dt = engine.getElapsedInLastFrame();
        for (Body b : bodies.values()) {
            if (b.entity == null || b.rowFrame == frameNo) {
                continue;
            }
            try {
                assertCoast(b, presentedTick, dt, tickRate);
            } catch (Throwable t) {
                coastFailures++;
                logOnce("asteroid_coast_failed " + t);
            }
        }
        long ns = System.nanoTime() - t0;
        advanceFrames++;
        advanceNsTotal += ns;
        if (ns > advanceNsMax) {
            advanceNsMax = ns;
        }
        maybeLog();
    }

    /** One wire row (step 5). Package-visible for the smoke. */
    void applyRow(CombatEngineAPI engine, ClientAsteroidRow r, long simTick, long presentedTick,
            float tickRate) {
        Integer id = Integer.valueOf(r.asteroidId());
        Body b = bodies.get(id);
        boolean meter = "meter".equals(mode);
        if (r.kind() == ClientAsteroidRow.KIND_ALIVE) {
            if (b != null && b.entity != null && !engine.isEntityInPlay(b.entity)) {
                // gone locally since the last prune: the (d) prune, inline, then the unknown path
                bodies.remove(id);
                localGone++;
                goneAtTick.put(id, presentedTick);
                b = null;
            }
            if (b == null) {
                mint(engine, r, id, simTick, presentedTick, tickRate, meter);
            } else {
                adopt(b, r, simTick, presentedTick, tickRate, meter);
            }
            return;
        }
        if (b == null) {
            killRowsUntracked++; // already gone (local kill / never minted): count only
            goneAtTick.remove(id);
            return;
        }
        // Untrack AFTER the engine call (the
        // finally), never before -- a throw in applyDamage/removeEntity used to orphan a live,
        // untracked body. A failed applyDamage falls back to the quiet remove
        // so the twin never outlives its death row.
        try {
            CombatEntityAPI e = b.entity;
            boolean inPlay = e != null && engine.isEntityInPlay(e);
            if (r.kind() == ClientAsteroidRow.KIND_DESTROYED) {
                deaths++;
                if (inPlay && !meter) {
                    try {
                        // vanilla's own Asteroid.applyDamage at hp <= 0: native explosion + removal
                        engine.applyDamage(e, e.getLocation(), e.getHitpoints() + 1f,
                                DamageType.KINETIC, 0f, true, false, null);
                    } catch (Throwable t) {
                        applyFailures++;
                        logOnce("asteroid_destroy_failed " + t);
                        removeQuiet(engine, e);
                    }
                }
            } else {
                expired++;
                if (inPlay && !meter) {
                    removeQuiet(engine, e);
                }
            }
        } finally {
            bodies.remove(id);
            goneAtTick.remove(id);
        }
    }

    /** Quiet {@code removeEntity}; a throw counts {@code applyFailures} + logs once (never
     *  rethrown). */
    private void removeQuiet(CombatEngineAPI engine, CombatEntityAPI e) {
        try {
            engine.removeEntity(e);
        } catch (Throwable t) {
            applyFailures++;
            logOnce("asteroid_remove_failed " + t);
        }
    }

    private void mint(CombatEngineAPI engine, ClientAsteroidRow r, Integer id, long simTick,
            long presentedTick, float tickRate, boolean meter) {
        Long gone = goneAtTick.get(id);
        if (gone != null && simTick <= gone.longValue() + REMINT_STALE_CADENCE_TICKS) {
            remintStale++; // the row predates the local kill: stale evidence, not a fork
            return;
        }
        if (bodies.size() >= MAX_TRACKED) {
            // The tracked-id ceiling
            mintCapped++;
            logOnce("asteroid_mint_capped max=" + MAX_TRACKED);
            return;
        }
        float leadS = leadSeconds(presentedTick, simTick, tickRate);
        float[] led = lead(r.locX(), r.locY(), r.facing(), r.velX(), r.velY(), r.angVel(), leadS);
        Body b = new Body();
        b.size = r.size();
        b.radius = r.radius();
        b.lastHp = r.hp();
        anchor(b, r, simTick);
        b.rowFrame = frameNo;
        if (meter) {
            bodies.put(id, b);
        } else {
            CombatEntityAPI e = engine.spawnAsteroid(r.size(), led[0], led[1], r.velX(), r.velY());
            if (e == null) {
                mintFailures++; // LOW-2, now metered
                logOnce("asteroid_mint_failed");
                return;
            }
            // Track the twin the moment it exists;
            // a set-up throw rolls it back (untrack + quiet remove) -- never an untracked live body
            // (the client twin of the server ghost fixed).
            b.entity = e;
            bodies.put(id, b);
            try {
                float local = e.getCollisionRadius();
                e.setCollisionRadius(r.radius());
                if (e instanceof CombatAsteroidAPI && local > 0f) {
                    // undo the engine's own random scale on the RENDERED sprite (R2 rows 21-22)
                    SpriteAPI sprite = ((CombatAsteroidAPI) e).getSpriteAPI();
                    if (sprite != null) {
                        float k = r.radius() / local;
                        sprite.setSize(sprite.getWidth() * k, sprite.getHeight() * k);
                    }
                }
                e.setFacing(led[2]);
                e.setAngularVelocity(r.angVel());
                e.setHitpoints(r.hp());
            } catch (Throwable t) {
                bodies.remove(id);
                mintFailures++;
                logOnce("asteroid_mint_setup_failed " + t);
                removeQuiet(engine, e);
                return;
            }
        }
        minted++;
        if (!seenIds.add(id) || gone != null) {
            remints++; // the true fork: this id was live in this epoch and died locally
        }
        goneAtTick.remove(id);
    }

    private void adopt(Body b, ClientAsteroidRow r, long simTick, long presentedTick, float tickRate,
            boolean meter) {
        adopted++;
        b.rowFrame = frameNo;
        CombatEntityAPI e = b.entity;
        if (meter || e == null) {
            anchor(b, r, simTick);
            b.lastHp = r.hp();
            return;
        }
        boolean armed = presentedTick != Long.MIN_VALUE;
        float leadS = leadSeconds(presentedTick, simTick, tickRate);
        float[] led = lead(r.locX(), r.locY(), r.facing(), r.velX(), r.velY(), r.angVel(), leadS);
        float dx = led[0] - e.getLocation().x;
        float dy = led[1] - e.getLocation().y;
        float dFacingSigned = ((led[2] - e.getFacing()) % 360f + 540f) % 360f - 180f;
        float posU = (float) Math.sqrt(dx * dx + dy * dy);
        float velUs = (float) Math.hypot(r.velX() - e.getVelocity().x, r.velY() - e.getVelocity().y);
        boolean glide = glideEligible(posU, velUs, b.yieldSeeded,
                b.glideStartTick != Long.MIN_VALUE, armed);
        if (glide) {
            // offset = presented-before - led target; zero visible jump at adopt
            b.gx = -dx;
            b.gy = -dy;
            b.gf = -dFacingSigned;
            b.glideStartTick = presentedTick;
            glides++;
        } else {
            e.getLocation().set(led[0], led[1]);
            e.setFacing(led[2]);
            b.glideStartTick = Long.MIN_VALUE; // a hard adopt clears any glide
        }
        e.getVelocity().set(r.velX(), r.velY());
        e.setAngularVelocity(r.angVel());
        e.setHitpoints(r.hp());
        if (b.radius != r.radius()) {
            e.setCollisionRadius(r.radius());
            b.radius = r.radius();
        }
        b.lastHp = r.hp();
        anchor(b, r, simTick);
    }

    /** The coast anchor = the row at its own simTick. */
    private static void anchor(Body b, ClientAsteroidRow r, long simTick) {
        b.ax = r.locX();
        b.ay = r.locY();
        b.afacing = r.facing();
        b.avx = r.velX();
        b.avy = r.velY();
        b.aang = r.angVel();
        b.anchorTick = simTick;
        b.yieldSeeded = false;
    }

    /** Step 6: the {@code FractureFamilyCoordinator.assertPoseCoast} port. */
    void assertCoast(Body b, long presentedTick, float dt, float tickRate) {
        CombatEntityAPI e = b.entity;
        float dvx = e.getVelocity().x - b.avx;
        float dvy = e.getVelocity().y - b.avy;
        if (yieldTripped(dvx, dvy, e.getAngularVelocity() - b.aang)) {
            // the local engine's collision response is real: adopt it into the coast
            b.ax = e.getLocation().x;
            b.ay = e.getLocation().y;
            b.afacing = ((e.getFacing() % 360f) + 360f) % 360f;
            b.avx = e.getVelocity().x;
            b.avy = e.getVelocity().y;
            b.aang = e.getAngularVelocity();
            b.anchorTick = Long.MIN_VALUE;
            b.glideStartTick = Long.MIN_VALUE;
            b.yieldSeeded = true;
            yields++;
            return; // the engine state IS the coast this frame
        }
        if (b.anchorTick != Long.MIN_VALUE && presentedTick != Long.MIN_VALUE) {
            float[] p = lead(b.ax, b.ay, b.afacing, b.avx, b.avy, b.aang,
                    (presentedTick - b.anchorTick) / tickRate);
            float px = p[0];
            float py = p[1];
            float pf = p[2];
            if (b.glideStartTick != Long.MIN_VALUE) {
                float k = 1f - (presentedTick - b.glideStartTick) / (CONTACT_GLIDE_SECONDS * tickRate);
                if (k > 1f) {
                    k = 1f;
                }
                if (k > 0f) {
                    px += b.gx * k;
                    py += b.gy * k;
                    pf += b.gf * k;
                } else {
                    b.glideStartTick = Long.MIN_VALUE;
                }
            }
            e.getLocation().set(px, py);
            e.setFacing(((pf % 360f) + 360f) % 360f);
        } else {
            // yield-seeded (or presented tick unarmed): the game-time integral until the next row
            b.ax += b.avx * dt;
            b.ay += b.avy * dt;
            b.afacing = ((b.afacing + b.aang * dt) % 360f + 360f) % 360f;
            e.getLocation().set(b.ax, b.ay);
            e.setFacing(b.afacing);
        }
        // re-assert vel/angVel so sub-tolerance nudges never accumulate
        e.getVelocity().set(b.avx, b.avy);
        e.setAngularVelocity(b.aang);
    }

    /** Remove every live twin from the engine and forget the round's state (an epoch flip). */
    private void removeAll(CombatEngineAPI engine) {
        for (Body b : bodies.values()) {
            if (b.entity == null) {
                continue;
            }
            try {
                if (engine.isEntityInPlay(b.entity)) {
                    engine.removeEntity(b.entity);
                }
            } catch (Throwable t) {
                applyFailures++;
                logOnce("asteroid_remove_failed " + t);
            }
        }
        bodies.clear();
        seenIds.clear();
        goneAtTick.clear();
    }

    /**
     * Step 7: NO state change. Bodies are round-scoped by the epoch law (the first post-MRST frame
     * removes + re-mints them); the MRST rebuild sweeps ships + projectiles only, never asteroids.
     * PRCP calls {@link #advance} after the fresh-snapshot
     * tick update, so an F1 HOLD frame (PRCP returns early) skips the lane: frames stay queued and
     * apply on the first post-hold frame. Not called from PRCP;
     * it is kept as the documented answer for a future caller.
     */
    void resetForMatchReset() {
        logOnce("asteroid lane reset: epoch law owns the round (appliedEpoch=" + appliedEpoch
                + " live=" + bodies.size() + ")");
    }

    // ------------------------------------------------------------------ sidelog

    private void maybeLog() {
        // The intake-cap drop count gains its
        // reader -- one locked read per LOG_EVERY_FRAMES frames (never per frame); any
        // nonzero queue_dropped is a FINDING (a dropped DESTROYED/EXPIRED row = a ghost twin).
        if (frameNo - lastDropPollFrame >= LOG_EVERY_FRAMES) {
            lastDropPollFrame = frameNo;
            if (lastSession instanceof PredictReconcileDatagramSession) {
                PredictReconcileDatagramSession s = (PredictReconcileDatagramSession) lastSession;
                queueDropped = s.asteroidFramesDropped();
                deathKept = s.asteroidDeathFramesKept();
                deathDropped = s.asteroidDeathRowsDropped();
            }
        }
        long sig = minted + adopted + yields + glides + deaths + expired + remints + remintStale
                + localGone + dropped + droppedStale + heldFrames + killRowsUntracked
                + applyFailures + coastFailures + mintFailures + mintCapped + queueDropped
                + deathKept + deathDropped;
        if (sig == lastLoggedSig || frameNo - lastLogFrame < LOG_EVERY_FRAMES) {
            return;
        }
        lastLoggedSig = sig;
        lastLogFrame = frameNo;
        int yieldOpen = 0;
        for (Body b : bodies.values()) {
            if (b.yieldSeeded) {
                yieldOpen++;
            }
        }
        lastRow = "asteroid lane epoch=" + appliedEpoch + " mode=" + mode + " minted=" + minted
                + " adopted=" + adopted + " yields=" + yields + " glides=" + glides
                + " deaths=" + deaths + " expired=" + expired + " remints=" + remints
                + " remint_stale=" + remintStale + " local_gone=" + localGone
                + " yield_open=" + yieldOpen + " dropped=" + dropped + " stale=" + droppedStale
                + " kill_rows_untracked=" + killRowsUntracked
                + " failures=" + (applyFailures + coastFailures + mintFailures)
                // The mint and intake-cap meter tokens
                + " mint_failures=" + mintFailures + " asteroid_mint_capped=" + mintCapped
                + " queue_dropped=" + queueDropped + " flips=" + flips
                + " death_kept=" + deathKept + " death_dropped=" + deathDropped // the death-row meters
                + " advance_ns_max=" + advanceNsMax
                + " advance_ns_avg=" + (advanceFrames == 0 ? 0 : advanceNsTotal / advanceFrames)
                + " live=" + bodies.size();
        PilotWatchLog.info(lastRow);
    }

    private void logOnce(String msg) {
        if (logLines >= LOG_MAX || !logOnce.add(msg)) {
            return;
        }
        logLines++;
        PilotWatchLog.info(msg + " (logged once per distinct message)");
    }

    // ------------------------------------------------------------------ pure statics (smoke)

    /** Seconds from the row's simTick to the presented tick; 0 when the clock is unarmed. */
    static float leadSeconds(long presentedTick, long simTick, float tickRate) {
        return presentedTick == Long.MIN_VALUE ? 0f : (presentedTick - simTick) / tickRate;
    }

    /** Ballistic lead: {@code {x + vx*dt, y + vy*dt, wrap360(facing + angVel*dt)}}. */
    static float[] lead(float x, float y, float facing, float vx, float vy, float angVel,
            float dtSeconds) {
        return new float[] {x + vx * dtSeconds, y + vy * dtSeconds,
                ((facing + angVel * dtSeconds) % 360f + 360f) % 360f};
    }

    /** The yield trigger: a real local collision RESPONSE. */
    static boolean yieldTripped(float dvx, float dvy, float dAng) {
        return dvx * dvx + dvy * dvy > YIELD_TOL_US * YIELD_TOL_US
                || Math.abs(dAng) > YIELD_ANG_TOL_DEG_S;
    }

    /** The contact-glide predicate. */
    static boolean glideEligible(float posU, float velUs, boolean yieldSeeded, boolean glideLive,
            boolean presentedArmed) {
        return CONTACT_GLIDE && presentedArmed
                && (velUs > CONTACT_GLIDE_VEL_US || yieldSeeded || glideLive)
                && posU > CONTACT_GLIDE_MIN_U && posU < POSE_LARGE_SNAP_UNITS;
    }

    /** -1 drop (older round), 0 apply, +1 flip (newer round). */
    static int epochAction(int frameEpoch, int appliedEpoch) {
        return frameEpoch < appliedEpoch ? -1 : frameEpoch == appliedEpoch ? 0 : 1;
    }

    private static String parseMode(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("off".equals(v) || "meter".equals(v) || "apply".equals(v)) {
            return v;
        }
        throw new IllegalStateException("cc.predict.asteroids must be off|meter|apply; got '" + raw + "'");
    }

    private static boolean parseOnOff(String knob, String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(v)) {
            return true;
        }
        if ("off".equals(v)) {
            return false;
        }
        throw new IllegalStateException(knob + " must be on|off; got '" + raw + "'");
    }

    // ------------------------------------------------------------------ getters

    int liveCount() { return bodies.size(); }
    long minted() { return minted; }
    long adopted() { return adopted; }
    long yields() { return yields; }
    long glides() { return glides; }
    long deaths() { return deaths; }
    long expired() { return expired; }
    long remints() { return remints; }
    long remintStale() { return remintStale; }
    long localGone() { return localGone; }
    long dropped() { return dropped; }
    long droppedStale() { return droppedStale; }
    long heldFrames() { return heldFrames; }
    long killRowsUntracked() { return killRowsUntracked; }
    int appliedEpoch() { return appliedEpoch; }
}
