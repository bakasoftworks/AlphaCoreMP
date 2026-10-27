package cc.starsector.server.runtime;

import cc.starsector.server.runtime.AsteroidPoseFrameCodec.AsteroidRow;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Server-side asteroid-field KEEPER (sim thread only; one instance per
 * round, minted at {@code PilotServeSession.mintRound}).
 *
 * <p>WHY our own keeper: the engine's field keeper {@code combat/A/E} is never built by either JVM
 * (the serve mission never calls {@code addAsteroidField}); {@code CombatEngine.spawnAsteroid} bodies
 * carry fieldId 1000 and are never off-map swept; every per-spawn draw inside the engine is
 * {@code Math.random}. So THIS lane seeds ({@link AsteroidLaw} on the round's {@link Random}), sweeps
 * (300 u outside the map), re-targets (the {@link WireKnob#ASTEROIDS} knob, live) and observes.
 *
 * <p>WHY a post-step POLL: damage to an asteroid never reaches a {@code DamageListener}
 * ({@code Asteroid.applyDamage} reports nothing; only {@code Ship}/{@code Missile} call
 * {@code reportDamageApplied}) - so hp and velocity are polled after the step, the
 * hulk dv-poll precedent (collision is the last advance phase: a post-step read
 * sees the impulse the same tick). Bump eps = the {@link HulkFractureDetector} impulse pair.
 * The keeper's expire is vanilla's own {@code Asteroid.expire()} = {@code setHitpoints(0)}
 * ({@code isExpired} = hp &lt;= 0;
 * the explosion rides only {@code applyDamage}), and the engine removes the body at its next step
 * and the observe emits EXPIRED.
 *
 * <p>The per-tick reflective poll is the minimum coherent engine read; its cost is
 * METERED ({@code asteroid_observe_ns_max/avg}), never assumed. Zero tracked bodies = the observe returns
 * before any read or timer (0 B on the wire), but the timed preAdvance still runs every tick (two
 * {@code nanoTime} reads + the knob lookup, returning early at target 0, else the mint burst metered as
 * {@code asteroid_preadvance_ns_max} / {@code asteroid_mints_tick_max}).
 * Every engine touch is FAIL-SOFT: a port failure is counted + logged once, never thrown into the
 * serve frame. PUBLIC because the contract rows live in {@code cc.starsector.server.tests}.
 *
 */
public final class AsteroidFieldServeLane {

    /**
     * The engine seam (the fake in the contract rows; {@link ReflectivePort} in the serve).
     * {@code pose} fills {@code out8 = x, y, facing, vx, vy, angVel, hp, radius}; false = unreadable.
     *
     */
    public interface EnginePort {
        Object spawn(int size, float x, float y, float vx, float vy);

        List<Object> asteroids();

        boolean inPlay(Object body);

        boolean pose(Object body, float[] out8);

        void setFacing(Object body, float facing);

        void setAngularVelocity(Object body, float angVel);

        void setCollisionRadius(Object body, float radius);

        void setHitpoints(Object body, float hp);

        float mass(Object body);

        /** Fail-soft reflective failures swallowed inside the port (meter fold); 0 for a fake. */
        default long portErrorCount() {
            return 0L;
        }

        /**
         * Whether the lane may run its once-per-SERVE boot kill-lands
         * probe on this port (spawn one untracked body, {@code setHitpoints(0)}, read hp back).
         * Default false = contract fakes stay byte-identical (their spawn/hp-write counts are
         * asserted exactly); the reflective engine port opts in.
         */
        default boolean bootKillProbe() {
            return false;
        }

        /**
         * Remove {@code body} from play through the engine's own
         * {@code removeEntity}. True = the engine took the removal; false = unsupported (this
         * default) or refused (a reflective throw is counted inside the port).
         *
         */
        default boolean remove(Object body) {
            return false;
        }

        /**
         * Whether {@link #remove} can land on this port. The boot kill probe is
         * fail-closed on it: no removal = no probe body is ever spawned. Default false.
         *
         */
        default boolean canRemove() {
            return false;
        }
    }

    //
    /** Per-SERVE monotone asteroid id (never reused; private to the ASTP frame type). */
    private static final AtomicInteger NEXT_ID = new AtomicInteger(0);
    /** First-mint-per-size law line, once per SERVE (the measurement). */
    private static final boolean[] LAW_LOGGED = new boolean[AsteroidLaw.SIZE_COUNT];
    /** The boot kill-lands probe runs once per SERVE. */
    private static final java.util.concurrent.atomic.AtomicBoolean KILL_PROBED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final int LOG_ONCE_CAP = 16;
    /**
     * The Nth consecutive refusal of one body forces the dead path (EXPIRED row + untrack).
     *
     */
    public static final int FORCE_DEAD_REFUSALS = 8;

    /** One tracked body. {@code x/y} + the read* fields are refreshed on EVERY observe read. */
    private static final class Body {
        final Object handle;
        final int id;
        final int size;
        float radius;
        float x;
        float y;
        float readFacing;
        float readVx;
        float readVy;
        float readAngVel;
        float readHp;
        float lastVx;
        float lastVy;
        float lastAngVel;
        float lastHp;
        long lastHpEmitTick = Long.MIN_VALUE / 2;
        long lastBumpEmitTick = Long.MIN_VALUE / 2;
        long emittedTick = Long.MIN_VALUE;
        int coveredEpoch;
        boolean freshThisTick;
        boolean expiredByKeeper;
        /** main (REVIEW keeper-4): the ONE death row per body - never twice, never a ghost. */
        boolean deathRowQueued;
        /**
         * Consecutive refusals of THIS body; reset by a good finite read. At {@link #FORCE_DEAD_REFUSALS}
         * the dead path is forced (a body whose pose read fails every tick while
         * {@code inPlay} answers true was re-refused forever and never untracked).
         *
         */
        int refusalStreak;
        /**
         * The body's LAST observe read said ALIVE (pose ok + finite, in
         * play, hp &gt; 0). A force-untrack of such a body leaves a live engine rock no client shows
         * (the reflective kill never landed) — metered apart as {@code asteroid_zombie_live}.
         *
         */
        boolean lastReadAlive;

        Body(Object handle, int id, int size) {
            this.handle = handle;
            this.id = id;
            this.size = size;
        }
    }

    private final EnginePort port;
    private final int round;
    private final float mapHalf;
    private final long seedKnob;
    private final int hpCadenceTicks;
    private final int refreshTicks;
    private final Random rng;
    private final float driftDeg;
    private final AsteroidLaw.SpawnLine line;
    private final LinkedHashMap<Integer, Body> byId = new LinkedHashMap<>();
    private final List<AsteroidRow> pendingRows = new ArrayList<>();
    private final float[] out8 = new float[8];
    private final Set<String> loggedErrors = new LinkedHashSet<>();

    private boolean preAdvancedOnce;
    private int fills;
    private int lastTarget = -1;
    private boolean seedLogged;
    private boolean sweepArmed;
    private int sweepCursorId;
    private int sweepEpoch;
    private long sweepRowsTotal;
    private int sweepTicks;
    private int refreshCursorId;
    private long lastRefreshTick = Long.MIN_VALUE;

    private long rows;
    private long frames;
    private long bumps;
    private long hpRows;
    private long deaths;
    private long expired;
    private long sweeps;
    /** Join-sweep ARMS, metered apart from the off-map {@link #sweeps} (were conflated). */
    private long joinSweeps;
    private long observeNsMax;
    private long observeNsTotal;
    private long observeTicks;
    private long portErrors;
    private long rowRefusals;
    private int framesTickMax;
    private long refreshRows;
    private long spawned;
    private long hpWrites;
    // preAdvance meters (F4 LOW-1 / frame-residency
    // MED-1: the mint burst - up to 2*target reflective spawns in ONE tick - was untimed), the forced
    // deaths (LOW-3) and the round-line port-meter read failures (LOW-4, was a silent catch).
    private long preAdvanceNsMax;
    private int mintsTickMax;
    private long forceExpired;
    /** force-untracks whose last read was ALIVE (failed kill). */
    private long zombieLive;
    /** Zombies the engine's removeEntity took. */
    private long zombieRemoved;
    /** Zombies left alive on the sim (remove unsupported/refused). */
    private long zombieLeft;
    /** Keeper-expired bodies still read alive: setHitpoints(0) re-issued. */
    private long keeperRekills;
    private long portErrorReadFailed;

    public AsteroidFieldServeLane(EnginePort port, int round, float mapHalf, long seedKnob,
                                  int hpCadenceTicks, int refreshTicks) {
        //
        if (port == null) {
            throw new IllegalArgumentException("port");
        }
        this.port = port;
        this.round = round;
        this.mapHalf = mapHalf;
        this.seedKnob = seedKnob;
        this.hpCadenceTicks = Math.max(1, hpCadenceTicks);
        this.refreshTicks = Math.max(0, refreshTicks);
        this.rng = new Random(AsteroidLaw.roundSeed(seedKnob, round));
        this.driftDeg = AsteroidLaw.driftDeg(rng);
        this.line = AsteroidLaw.spawnLine(mapHalf, driftDeg);
    }

    // ------------------------------------------------------------------ pre-advance

    /**
     * Called BEFORE {@code publishNextTick}: (a) sweep bodies &gt; 300 u outside the map, (b) retarget
     * to the knob's population (expire the excess highest ids first, or mint on the spawn line).
     * Never throws.
     */
    public void preAdvance(long nextTick, int knobChoice) {
        // nanoTime around the existing call, max only
        // (the frame-residency MEASURE law) + mints this tick.
        long t0 = System.nanoTime();
        long spawnedBefore = spawned;
        try {
            preAdvanceInner(nextTick, knobChoice);
        } catch (RuntimeException unexpected) {
            portErrors++;
            logOnce("npeer-serve asteroid_port_failed op=preAdvance err=" + unexpected);
        } finally {
            long ns = System.nanoTime() - t0;
            if (ns > preAdvanceNsMax) {
                preAdvanceNsMax = ns;
            }
            int minted = (int) (spawned - spawnedBefore);
            if (minted > mintsTickMax) {
                mintsTickMax = minted;
            }
        }
    }

    private void preAdvanceInner(long nextTick, int knobChoice) {
        int target = targetFor(knobChoice);
        boolean firstCall = !preAdvancedOnce;
        preAdvancedOnce = true;
        if (lastTarget >= 0 && target != lastTarget) {
            System.out.println("npeer-serve asteroid_retarget round=" + round + " from=" + lastTarget
                    + " to=" + target + " tick=" + nextTick);
        }
        lastTarget = target;
        if (byId.isEmpty() && target == 0) {
            return;
        }
        int live = 0;
        for (Body b : byId.values()) {
            if (b.expiredByKeeper) {
                continue;
            }
            if (AsteroidLaw.outside(b.x, b.y, mapHalf, AsteroidLaw.SWEEP_MARGIN)) {
                expireByKeeper(b);
                sweeps++;
            } else {
                live++;
            }
        }
        if (live > target) {
            Body[] ordered = byId.values().toArray(new Body[0]);
            int excess = live - target;
            for (int i = ordered.length - 1; i >= 0 && excess > 0; i--) {
                Body b = ordered[i];
                if (!b.expiredByKeeper) {
                    expireByKeeper(b);
                    excess--;
                }
            }
        } else if (live < target) {
            int need = target - live;
            // tracked (live + dying) never exceeds 2*target - a port that refuses every read
            // (and every hp write) would otherwise grow the table by N per tick (fail-soft bound, logged once).
            int room = 2 * target - byId.size();
            if (need > room) {
                logOnce("npeer-serve asteroid_mint_capped round=" + round + " tracked=" + byId.size()
                        + " target=" + target + " tick=" + nextTick, "mint_capped");
                need = Math.max(0, room);
            }
            boolean initial = firstCall;
            if (fills == 0 && !seedLogged) {
                seedLogged = true;
                System.out.println("npeer-serve asteroid_seed round=" + round + " n=" + need
                        + " angle=" + driftDeg + " seed=" + AsteroidLaw.roundSeed(seedKnob, round)
                        + " line=" + line.x() + "," + line.y() + "," + line.width() + ","
                        + line.height() + "," + line.preAdvanceSpan()
                        + " initial=" + initial + " tick=" + nextTick);
            }
            fills++;
            for (int i = 0; i < need; i++) {
                mintOne(initial);
            }
        }
    }

    /** {none,20,100,400} = {0,20,100,400}; a value past the table (or non-numeric) = 0 (fail-closed). */
    private static int targetFor(int knobChoice) {
        return Math.max(0, WireKnob.ASTEROIDS.choicePercent(knobChoice));
    }

    private void mintOne(boolean initial) {
        AsteroidLaw.Draw d = AsteroidLaw.draw(rng, line, driftDeg, initial);
        Object o;
        try {
            o = port.spawn(d.size(), d.x(), d.y(), d.vx(), d.vy());
        } catch (RuntimeException spawnFailed) {
            portErrors++;
            logOnce("npeer-serve asteroid_port_failed op=spawn err=" + spawnFailed);
            return;
        }
        if (o == null) {
            portErrors++;
            return;
        }
        spawned++;
        logLawOnce(d.size(), o);
        float radius = AsteroidLaw.r0(d.size()) * d.scale();
        try {
            port.setFacing(o, d.facing());
            port.setAngularVelocity(o, d.angVel());
            port.setCollisionRadius(o, radius);
        } catch (RuntimeException writeFailed) {
            portErrors++;
            logOnce("npeer-serve asteroid_port_failed op=seed_write err=" + writeFailed);
        }
        Body b = new Body(o, NEXT_ID.incrementAndGet(), d.size());
        b.radius = radius;
        b.x = d.x();
        b.y = d.y();
        b.readFacing = d.facing();
        b.readVx = d.vx();
        b.readVy = d.vy();
        b.readAngVel = d.angVel();
        b.lastVx = d.vx();
        b.lastVy = d.vy();
        b.lastAngVel = d.angVel();
        b.readHp = AsteroidLaw.hp0(d.size());
        b.lastHp = b.readHp;
        b.freshThisTick = true;
        byId.put(b.id, b);
    }

    /** The first mint per size in the SERVE: native pose + mass BEFORE our overwrites. */
    private void logLawOnce(int size, Object o) {
        synchronized (LAW_LOGGED) {
            if (LAW_LOGGED[size]) {
                return;
            }
            LAW_LOGGED[size] = true;
        }
        float[] p = new float[8];
        boolean ok;
        float mass;
        try {
            ok = port.pose(o, p);
            mass = port.mass(o);
        } catch (RuntimeException readFailed) {
            portErrors++;
            logOnce("npeer-serve asteroid_port_failed op=law_read err=" + readFailed);
            return;
        }
        if (!ok) {
            System.out.println("npeer-serve asteroid_law size=" + size + " unreadable");
            return;
        }
        float hp = p[6];
        float r0Native = hp / 50f;
        float radiusNative = p[7];
        float r0 = AsteroidLaw.r0(size);
        boolean mismatch = offByMoreThanOnePercent(r0Native, r0)
                || offByMoreThanOnePercent(mass, AsteroidLaw.mass0(size))
                || !(radiusNative >= 0.75f * r0Native * 0.99f && radiusNative < r0Native * 1.01f);
        System.out.println("npeer-serve " + (mismatch ? "asteroid_law_MISMATCH" : "asteroid_law")
                + " size=" + size + " r0=" + r0Native + " hp=" + hp + " mass=" + mass
                + " radius_native=" + radiusNative + " expected r0=" + r0 + " hp="
                + AsteroidLaw.hp0(size) + " mass=" + AsteroidLaw.mass0(size));
        bootKillProbeOnce(size, p[0], p[1]);
    }

    /**
     * The boot kill-lands probe, once per SERVE, beside the first
     * {@code asteroid_law} line: {@link #runKillProbe} (off-field spawn,
     * {@code setHitpoints(0)}, finally-remove; skipped when the port cannot remove).
     * {@code ok=false} = the keeper's reflective kill does not land on this build, so every keeper
     * expiry would become an {@code asteroid_zombie_live} (a live engine rock no client shows). The
     * body is never tracked (no row ever names it). Port throws count into {@link #portErrors}.
     * Runs only on a port that opts in
     * ({@link EnginePort#bootKillProbe} — the reflective engine port).
     */
    private void bootKillProbeOnce(int size, float x, float y) {
        if (!port.bootKillProbe() || !KILL_PROBED.compareAndSet(false, true)) {
            return;
        }
        runKillProbe(size, x, y);
    }

    /**
     * The kill probe body, FAIL-CLOSED with explicit rollback (the
     * project's mutating-probe law; the first form spawned a real rock ON the law body's pose and
     * never removed it). Order: (1) no {@link EnginePort#canRemove} = spawn NOTHING, print
     * {@code asteroid_kill_probe skipped=no_remove}; (2) spawn OFF-FIELD at
     * ({@code mapHalf*4}, {@code mapHalf*4}) - the lane's own field half-extent, 4x = far past the
     * {@link AsteroidLaw#SWEEP_MARGIN} box, so no tracked body is overlapped; a non-positive or
     * non-finite mapHalf falls back to the law pose + 1e6 on both axes; (3) {@code setHitpoints(0)};
     * (4) {@code finally} = {@link EnginePort#remove} ALWAYS when a body was spawned, even on a throw.
     * Tokens: {@code hp_echo} = the engine's getHitpoints read-back &lt;= 0 (proves the kill WRITE
     * lands, not that the dead path removes the body); {@code removed} = remove took;
     * {@code gone} = {@code !inPlay(probe)} after the remove (the engine's own isEntityInPlay
     * excludes a pending removal at once);
     * {@code ok = hp_echo && gone}. LIMIT: whether the engine's advance-time dead path
     * removes a 0-hp rock on its own is NOT read here - that needs the body to survive an advance,
     * which is exactly the live-rock risk this rollback exists to prevent; the live meter for it is
     * {@code asteroid_zombie_live} / {@code asteroid_zombie_left}. Public = the contract rows drive it
     * past the once-per-SERVE latch. Returns the printed line.
     *
     */
    public String runKillProbe(int size, float lawX, float lawY) {
        boolean canRemove;
        try {
            canRemove = port.canRemove();
        } catch (RuntimeException canRemoveFailed) {
            portErrors++;
            logOnce("npeer-serve asteroid_port_failed op=kill_probe_can_remove err=" + canRemoveFailed);
            canRemove = false;
        }
        if (!canRemove) {
            String skipped = "npeer-serve asteroid_kill_probe skipped=no_remove size=" + size;
            System.out.println(skipped);
            return skipped;
        }
        boolean fieldKnown = mapHalf > 0f && Float.isFinite(mapHalf);
        float px = fieldKnown ? mapHalf * 4f : lawX + 1_000_000f;
        float py = fieldKnown ? mapHalf * 4f : lawY + 1_000_000f;
        Object probe = null;
        boolean hpEcho = false;
        boolean removed = false;
        boolean gone = false;
        try {
            probe = port.spawn(size, px, py, 0f, 0f);
            if (probe != null) {
                port.setHitpoints(probe, 0f);
                float[] p = new float[8];
                hpEcho = port.pose(probe, p) && p[6] <= 0f;
            }
        } catch (RuntimeException probeFailed) {
            portErrors++;
            logOnce("npeer-serve asteroid_port_failed op=kill_probe err=" + probeFailed);
        } finally {
            if (probe != null) {
                try {
                    removed = port.remove(probe);
                    gone = removed && !port.inPlay(probe);
                } catch (RuntimeException removeFailed) {
                    portErrors++;
                    logOnce("npeer-serve asteroid_port_failed op=kill_probe_remove err=" + removeFailed);
                }
            }
        }
        String line = "npeer-serve asteroid_kill_probe ok=" + (hpEcho && gone) + " hp_echo=" + hpEcho
                + " removed=" + removed + " gone=" + gone + " spawned=" + (probe != null) + " size=" + size;
        System.out.println(line);
        return line;
    }

    private static boolean offByMoreThanOnePercent(float measured, float expected) {
        return !(Math.abs(measured - expected) <= Math.abs(expected) * 0.01f);
    }

    private void expireByKeeper(Body b) {
        b.expiredByKeeper = true;
        setHpSafe(b.handle);
    }

    private void setHpSafe(Object handle) {
        try {
            port.setHitpoints(handle, 0f);
            hpWrites++;
        } catch (RuntimeException writeFailed) {
            portErrors++;
            logOnce("npeer-serve asteroid_port_failed op=setHitpoints err=" + writeFailed);
        }
    }

    // ------------------------------------------------------------------ observe

    /**
     * Called AFTER the step (beside the HKPS fan): poll every tracked body, emit ALIVE / DESTROYED /
     * EXPIRED rows, the join sweep and the refresh row, chunk into &lt;= 29-row frames and fan them.
     * Never throws into the serve frame.
     */
    public void observe(long simTick, Consumer<byte[]> fan) {
        if (byId.isEmpty()) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            observeInner(simTick, fan);
        } catch (RuntimeException unexpected) {
            portErrors++;
            logOnce("npeer-serve asteroid_port_failed op=observe err=" + unexpected);
        } finally {
            pendingRows.clear();
            long ns = System.nanoTime() - t0;
            observeNsTotal += ns;
            observeTicks++;
            if (ns > observeNsMax) {
                observeNsMax = ns;
            }
        }
    }

    private void observeInner(long simTick, Consumer<byte[]> fan) {
        pendingRows.clear();
        Iterator<Body> it = byId.values().iterator();
        while (it.hasNext()) {
            Body b = it.next();
            boolean ok;
            boolean inPlay;
            try {
                ok = port.pose(b.handle, out8);
                inPlay = port.inPlay(b.handle);
            } catch (RuntimeException readFailed) {
                portErrors++;
                logOnce("npeer-serve asteroid_port_failed op=pose err=" + readFailed);
                ok = false;
                inPlay = true;
            }
            // Remember whether this
            // read said ALIVE, so a later force-untrack can tell a zombie from an unreadable body.
            b.lastReadAlive = ok && inPlay && allFinite(out8) && out8[6] > 0f;
            boolean dead = !inPlay || (ok && out8[6] <= 0f);
            if (dead) {
                // main (REVIEW keeper-4): the final read is copied only when finite - a NaN death
                // read keeps the last good pose so the death row ALWAYS constructs (queueDeathRow clamps).
                if (ok && allFinite(out8)) {
                    b.x = out8[0];
                    b.y = out8[1];
                    b.readFacing = out8[2];
                    b.readVx = out8[3];
                    b.readVy = out8[4];
                    b.readAngVel = out8[5];
                }
                byte kind = b.expiredByKeeper ? AsteroidPoseFrameCodec.KIND_EXPIRED
                        : AsteroidPoseFrameCodec.KIND_DESTROYED;
                if (b.expiredByKeeper) {
                    expired++;
                } else {
                    deaths++;
                }
                queueDeathRow(b, kind);
                it.remove();
                continue;
            }
            if (!ok || !allFinite(out8)) {
                // main (REVIEW keeper-3/4): refuse = expire by the keeper, keep TRACKED - the next
                // observe's dead path sends the quiet EXPIRED row (never DESTROYED, never a client ghost).
                refuse(b, ok ? "non-finite pose" : "pose unreadable", true);
                b.freshThisTick = false;
                forceDeadIfStuck(b, it);
                continue;
            }
            if (b.expiredByKeeper) {
                // keeper-expired (swept / retargeted /
                // refused) yet the engine still reads it ALIVE = the reflective setHitpoints(0) did not land
                // (portErrors counted it). Re-issue the kill every tick, count the streak, force the ONE
                // EXPIRED row after FORCE_DEAD_REFUSALS ticks. NEVER an ALIVE row for a keeper-expired body -
                // the old code reset the streak here and emitted it forever, invisible to sweep/refresh.
                setHpSafe(b.handle);
                keeperRekills++;
                b.refusalStreak++;
                b.freshThisTick = false;
                forceDeadIfStuck(b, it);
                continue;
            }
            b.refusalStreak = 0; // A good read ends the streak
            float vx = out8[3];
            float vy = out8[4];
            float ang = out8[5];
            float hp = out8[6];
            if (!b.freshThisTick && impulseTripped(vx - b.readVx, vy - b.readVy, ang - b.readAngVel)) {
                bumps++;
            }
            b.x = out8[0];
            b.y = out8[1];
            b.readFacing = out8[2];
            b.readVx = vx;
            b.readVy = vy;
            b.readAngVel = ang;
            b.readHp = hp;
            b.radius = out8[7];
            boolean emit = false;
            if (b.freshThisTick) {
                emit = true;
            } else if (impulseTripped(vx - b.lastVx, vy - b.lastVy, ang - b.lastAngVel)) {
                // dv vs the last EMITTED row: the first trip emits the same tick; later trips inside
                // the cadence coalesce to the next cadence tick (the hulk burst law).
                if (simTick - b.lastBumpEmitTick >= hpCadenceTicks) {
                    emit = true;
                    b.lastBumpEmitTick = simTick;
                }
            } else if (hp != b.lastHp && simTick - b.lastHpEmitTick >= hpCadenceTicks) {
                emit = true;
                hpRows++;
                b.lastHpEmitTick = simTick;
            }
            b.freshThisTick = false;
            if (emit) {
                // A refusal inside emitAlive expires the
                // body in place; the next tick's keeper-expired branch counts the streak and forces the
                // EXPIRED row (the old forceDeadIfStuck here was unreachable: the streak had just been reset).
                emitAlive(b, simTick);
            }
        }
        if (sweepArmed) {
            runJoinSweep(simTick);
        } else if (refreshTicks > 0 && !byId.isEmpty()) {
            runRefresh(simTick);
        }
        fanPending(simTick, fan);
    }

    /** Builds + queues an ALIVE row from the body's last read; false = refused (body expired). */
    private boolean emitAlive(Body b, long simTick) {
        AsteroidRow row;
        try {
            row = new AsteroidRow(b.id, AsteroidPoseFrameCodec.KIND_ALIVE, (byte) b.size, (byte) 0,
                    b.radius, b.readHp, b.x, b.y, b.readFacing, b.readVx, b.readVy, b.readAngVel);
        } catch (IllegalArgumentException badRow) {
            refuse(b, badRow.getMessage(), true);
            return false;
        }
        pendingRows.add(row);
        b.lastVx = b.readVx;
        b.lastVy = b.readVy;
        b.lastAngVel = b.readAngVel;
        b.lastHp = b.readHp;
        b.emittedTick = simTick;
        if (sweepArmed) {
            b.coveredEpoch = sweepEpoch;
        }
        return true;
    }

    /**
     * A law-violating read: count, log once per distinct err, expire the body BY THE KEEPER (no ALIVE
     * row; the body stays tracked so the next observe's dead path sends ONE quiet EXPIRED row -
     * before this, a refused body on the wire was untracked row-less = a client ghost,
     * and a sweep/refresh refusal fell through to a DESTROYED row = a native explosion on every seat).
     */
    private void refuse(Body b, String err, boolean expire) {
        rowRefusals++;
        b.refusalStreak++; //
        logOnce("npeer-serve asteroid_row_refused id=" + b.id + " err=" + err, err);
        if (expire) {
            b.expiredByKeeper = true;
            setHpSafe(b.handle);
        }
    }

    /**
     * The {@link #FORCE_DEAD_REFUSALS}th consecutive refusal of one body forces the dead path: ONE
     * EXPIRED row (the last good read, clamped by {@link #queueDeathRow}; none if never emitted) and
     * untrack via the observe iterator. The stuck case:
     * pose unreadable every tick + {@code inPlay} failure answering true
     * = re-refused forever, tracked forever, and an emitted replica never got its EXPIRED row. Only the
     * main observe loop calls this (the sweep/refresh skip keeper-expired bodies, so they never re-refuse).
     * Also reached from the keeper-expired branch: a keeper-expired body that keeps reading
     * alive re-kills + counts each tick until it forces.
     *
     */
    private void forceDeadIfStuck(Body b, Iterator<Body> it) {
        if (b.refusalStreak < FORCE_DEAD_REFUSALS) {
            return;
        }
        b.expiredByKeeper = true;
        expired++;
        if (b.lastReadAlive) {
            // The engine still read
            // this body ALIVE = the reflective kill never landed; untracking leaves a live engine
            // rock no client shows. Its own meter, never lumped into asteroid_force_expired.
            zombieLive++;
            logOnce("npeer-serve asteroid_zombie_live id=" + b.id + " refusals=" + b.refusalStreak
                    + " round=" + round, "zombie_live");
            // Never abandon the live rock - remove it
            // through the engine's own removeEntity. false = unsupported/refused (a port throw is
            // counted inside the port), metered as left-alive; a keeper-side throw counts portErrors.
            boolean took = false;
            try {
                took = port.remove(b.handle);
            } catch (RuntimeException removeFailed) {
                portErrors++;
                logOnce("npeer-serve asteroid_port_failed op=zombie_remove err=" + removeFailed);
            }
            if (took) {
                zombieRemoved++;
            } else {
                zombieLeft++;
            }
        } else {
            forceExpired++;
            logOnce("npeer-serve asteroid_force_expired id=" + b.id + " refusals=" + b.refusalStreak
                    + " round=" + round, "force_expired");
        }
        queueDeathRow(b, AsteroidPoseFrameCodec.KIND_EXPIRED);
        it.remove();
    }

    /**
     * The ONE death row per body, built from the last GOOD read with every pose field clamped into
     * the row law (the client ignores a death row's pose: DESTROYED = its own {@code applyDamage} at
     * the local body, EXPIRED = {@code removeEntity}) - so a death row can never be refused and a body
     * the wire has seen always leaves the wire. A body never emitted needs no row (the client never
     * minted it). main (REVIEW keeper-4).
     */
    private void queueDeathRow(Body b, byte kind) {
        if (b.deathRowQueued || b.emittedTick == Long.MIN_VALUE) {
            return;
        }
        b.deathRowQueued = true;
        float radius = finiteClamp(b.radius, AsteroidPoseFrameCodec.MIN_RADIUS, AsteroidPoseFrameCodec.MAX_RADIUS,
                AsteroidLaw.r0(b.size));
        float x = finiteClamp(b.x, -AsteroidPoseFrameCodec.MAX_LOC_ABS, AsteroidPoseFrameCodec.MAX_LOC_ABS, 0f);
        float y = finiteClamp(b.y, -AsteroidPoseFrameCodec.MAX_LOC_ABS, AsteroidPoseFrameCodec.MAX_LOC_ABS, 0f);
        float facing = Float.isFinite(b.readFacing) ? b.readFacing : 0f;
        float vx = finiteClamp(b.readVx, -AsteroidPoseFrameCodec.MAX_VEL_ABS, AsteroidPoseFrameCodec.MAX_VEL_ABS, 0f);
        float vy = finiteClamp(b.readVy, -AsteroidPoseFrameCodec.MAX_VEL_ABS, AsteroidPoseFrameCodec.MAX_VEL_ABS, 0f);
        float ang = finiteClamp(b.readAngVel, -AsteroidPoseFrameCodec.MAX_ANG_VEL_ABS,
                AsteroidPoseFrameCodec.MAX_ANG_VEL_ABS, 0f);
        try {
            pendingRows.add(new AsteroidRow(b.id, kind, (byte) b.size, (byte) 0, radius, 0f,
                    x, y, facing, vx, vy, ang));
        } catch (IllegalArgumentException badRow) {
            refuse(b, "death row " + badRow.getMessage(), false); // unreachable by construction; metered
        }
    }

    private static float finiteClamp(float v, float lo, float hi, float fallback) {
        if (!Float.isFinite(v)) {
            return fallback;
        }
        return Math.min(hi, Math.max(lo, v));
    }

    private void runJoinSweep(long simTick) {
        sweepTicks++;
        int cap = 2 * AsteroidPoseFrameCodec.MAX_ROWS;
        boolean exhausted = true;
        for (Body b : byId.values()) {
            if (b.id <= sweepCursorId) {
                continue;
            }
            if (pendingRows.size() >= cap) {
                exhausted = false;
                break;
            }
            sweepCursorId = b.id;
            if (b.expiredByKeeper) {
                // A keeper-expired (refused /
                // swept / retargeted) body never gets an ALIVE row - its ONE EXPIRED row rides the dead path.
                continue;
            }
            if (b.emittedTick == simTick || b.coveredEpoch == sweepEpoch) {
                continue; // already carried a row this sweep (fresh / bump / hp): never twice
            }
            int before = pendingRows.size();
            if (emitAlive(b, simTick)) {
                sweepRowsTotal += pendingRows.size() - before;
            }
        }
        if (exhausted) {
            sweepArmed = false;
            System.out.println("npeer-serve asteroid_join_sweep done rows=" + sweepRowsTotal
                    + " ticks=" + sweepTicks + " round=" + round);
        }
    }

    private void runRefresh(long simTick) {
        if (lastRefreshTick == Long.MIN_VALUE) {
            lastRefreshTick = simTick;
            return;
        }
        if (simTick - lastRefreshTick < refreshTicks) {
            return;
        }
        lastRefreshTick = simTick;
        // The pick skips keeper-expired
        // bodies (no ALIVE row after a refusal); none live = no refresh row this cadence tick.
        Body pick = null;
        Body lowest = null;
        for (Body b : byId.values()) {
            if (b.expiredByKeeper) {
                continue;
            }
            if (lowest == null) {
                lowest = b;
            }
            if (b.id > refreshCursorId) {
                pick = b;
                break;
            }
        }
        if (pick == null) {
            pick = lowest; // wrap to the lowest live tracked id
        }
        if (pick == null) {
            return;
        }
        refreshCursorId = pick.id;
        if (pick.emittedTick == simTick) {
            return; // already carried a row this tick
        }
        if (emitAlive(pick, simTick)) {
            refreshRows++;
        }
    }

    private void fanPending(long simTick, Consumer<byte[]> fan) {
        int n = pendingRows.size();
        if (n == 0) {
            return;
        }
        int fannedThisTick = 0;
        for (int from = 0; from < n; from += AsteroidPoseFrameCodec.MAX_ROWS) {
            List<AsteroidRow> chunk = pendingRows.subList(from, Math.min(n, from + AsteroidPoseFrameCodec.MAX_ROWS));
            try {
                byte[] frame = AsteroidPoseFrameCodec.encode(simTick, chunk);
                fan.accept(frame);
                rows += chunk.size();
                frames++;
                fannedThisTick++;
            } catch (IOException | RuntimeException encodeOrFanFailed) {
                rowRefusals += chunk.size();
                logOnce("npeer-serve asteroid_row_refused id=frame err=" + encodeOrFanFailed,
                        String.valueOf(encodeOrFanFailed));
            }
        }
        if (fannedThisTick > framesTickMax) {
            framesTickMax = fannedThisTick;
        }
    }

    private static boolean allFinite(float[] v) {
        for (float f : v) {
            if (!Float.isFinite(f)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ join sweep

    /**
     * A newly-live session ARMS a full re-emit of every tracked body on the SHARED fan (idempotent
     * for everyone else; &lt;= 2 frames per tick). No-op + no row when nothing is tracked.
     *
     */
    public void armJoinSweep(long sessionId) {
        if (byId.isEmpty()) {
            return;
        }
        sweepArmed = true;
        sweepCursorId = 0;
        sweepEpoch++;
        sweepRowsTotal = 0L;
        sweepTicks = 0;
        joinSweeps++;
        System.out.println("npeer-serve asteroid_join_sweep armed session=" + sessionId
                + " rows=" + liveCount() + " round=" + round);
    }

    // ------------------------------------------------------------------ meters

    /** Round-end tokens (the {@code asteroid round} line). */
    public String roundTokens() {
        return " asteroid_rows=" + rows
                + " asteroid_frames=" + frames
                + " asteroid_bumps=" + bumps
                + " asteroid_hp=" + hpRows
                + " asteroid_deaths=" + deaths
                + " asteroid_expired=" + expired
                + " asteroid_sweeps=" + sweeps
                + " asteroid_join_sweeps=" + joinSweeps
                + " asteroid_port_errors=" + portErrors()
                + " asteroid_observe_ns_max=" + observeNsMax
                + " asteroid_observe_ns_avg=" + (observeTicks == 0 ? 0L : observeNsTotal / observeTicks)
                + " asteroid_row_refused=" + rowRefusals
                + " asteroid_frames_tick_max=" + framesTickMax
                + " asteroid_refresh_rows=" + refreshRows
                // preAdvance meters + forced deaths +
                // the port-meter read failures (read AFTER portErrors() above, which counts them).
                + " asteroid_preadvance_ns_max=" + preAdvanceNsMax
                + " asteroid_mints_tick_max=" + mintsTickMax
                + " asteroid_spawned=" + spawned
                + " asteroid_force_expired=" + forceExpired
                + " asteroid_zombie_live=" + zombieLive //
                // Zombie disposal split
                + " asteroid_zombie_removed=" + zombieRemoved
                + " asteroid_zombie_left=" + zombieLeft
                // keeper-expired re-kills
                + " asteroid_keeper_rekill=" + keeperRekills
                + " asteroid_port_error_read_failed=" + portErrorReadFailed;
    }

    public long rows() { return rows; }
    public long frames() { return frames; }
    public long bumps() { return bumps; }
    public long hpRows() { return hpRows; }
    public long deaths() { return deaths; }
    public long expired() { return expired; }
    public long sweeps() { return sweeps; }
    public long joinSweeps() { return joinSweeps; }
    public long observeNsMax() { return observeNsMax; }
    public long observeNsTotal() { return observeNsTotal; }
    public long observeTicks() { return observeTicks; }
    public long rowRefusals() { return rowRefusals; }
    public int framesTickMax() { return framesTickMax; }
    public long refreshRows() { return refreshRows; }
    public long spawned() { return spawned; }
    public long hpWrites() { return hpWrites; }
    /** The preAdvance / forced-death meters. */
    public long preAdvanceNsMax() { return preAdvanceNsMax; }
    public int mintsTickMax() { return mintsTickMax; }
    public long forceExpired() { return forceExpired; }
    /** force-untracks of bodies last read ALIVE. */
    public long zombieLive() { return zombieLive; }
    /** Zombies the engine removeEntity took. */
    public long zombieRemoved() { return zombieRemoved; }
    /** Zombies left alive (remove unsupported/refused). */
    public long zombieLeft() { return zombieLeft; }
    /** keeper-expired re-kill count. */
    public long keeperRekills() { return keeperRekills; }
    public long portErrorReadFailed() { return portErrorReadFailed; }
    public boolean sweepArmed() { return sweepArmed; }
    public float driftDeg() { return driftDeg; }
    public AsteroidLaw.SpawnLine spawnLine() { return line; }
    public int trackedCount() { return byId.size(); }

    /** Keeper-caught failures + the port's own swallowed reflective failures. */
    public long portErrors() {
        long inner;
        try {
            inner = port.portErrorCount();
        } catch (RuntimeException meterFailed) {
            // a meter read must never throw into the round line; the keeper count stands.
            // counted (asteroid_port_error_read_failed=), never silent.
            portErrorReadFailed++;
            inner = 0L;
        }
        return portErrors + inner;
    }

    /** Tracked and not already expired by the keeper (the retarget arithmetic). */
    public int liveCount() {
        int n = 0;
        for (Body b : byId.values()) {
            if (!b.expiredByKeeper) {
                n++;
            }
        }
        return n;
    }

    /** Tracked ids in ascending order (contract rows). */
    public List<Integer> trackedIds() {
        return Collections.unmodifiableList(new ArrayList<>(byId.keySet()));
    }

    // ------------------------------------------------------------------ static helpers

    /** The HKPS impulse eps pair: |dv| &gt; 0.5 u/s or |dAngVel| &gt; 2 deg/s. */
    public static boolean impulseTripped(float dvx, float dvy, float dAng) {
        return Math.hypot(dvx, dvy) > HulkFractureDetector.IMPULSE_VEL_EPS_US
                || Math.abs(dAng) > HulkFractureDetector.IMPULSE_ANG_EPS_DEG_S;
    }

    /** Frames needed for {@code rows} rows at {@link AsteroidPoseFrameCodec#MAX_ROWS} per frame. */
    public static int chunkCount(int rows) {
        if (rows <= 0) {
            return 0;
        }
        return (rows + AsteroidPoseFrameCodec.MAX_ROWS - 1) / AsteroidPoseFrameCodec.MAX_ROWS;
    }

    private void logOnce(String line) {
        logOnce(line, line);
    }

    private void logOnce(String line, String key) {
        if (loggedErrors.size() >= LOG_ONCE_CAP || !loggedErrors.add(String.valueOf(key))) {
            return;
        }
        System.out.println(line);
    }

    // ------------------------------------------------------------------ reflective port

    /**
     * The serve's engine port: reflection only ({@code server/src} is game-jar-free). Lazy: the engine-internal
     * {@code CombatEngine} via {@code Class.forName(..., true, AsteroidFieldServeLane.class
     * .getClassLoader())} + {@code getInstance()}, the 6-arg {@code spawnAsteroid} resolved on
     * {@code engine.getClass()} (the int-size form exists only on the engine class; ring always false),
     * {@code getAsteroids}, {@code isEntityInPlay}; entity members on the API interface
     * {@code CombatEntityAPI}; the {@code Vector2f} x/y fields cached off the first vector seen (the
     * {@code EngineCombatBinding.readXY} idiom). Every failure is FAIL-SOFT: one
     * {@code npeer-serve asteroid_port_failed op= err=} row per distinct message (capped at 16), the
     * op returns false/null/empty and counts into {@link #portErrorCount}.
     *
     */
    public static final class ReflectivePort implements EnginePort {
        /** The real engine port opts in to the boot kill-lands probe. */
        @Override
        public boolean bootKillProbe() {
            return true;
        }

        private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
        private static final String ENTITY_API = "com.fs.starfarer.api.combat.CombatEntityAPI";

        private Class<?> engineClass;
        private Method getInstance;
        private Method spawnAsteroid;
        private Method getAsteroids;
        private Method isEntityInPlay;
        private Method getLocation;
        private Method getVelocity;
        private Method getFacing;
        private Method getAngularVelocity;
        private Method getHitpoints;
        private Method getCollisionRadius;
        private Method getMass;
        private Method setFacing;
        private Method setAngularVelocity;
        private Method setCollisionRadius;
        private Method setHitpoints;
        private Field vecX;
        private Field vecY;
        private Throwable resolveFailure;
        /** Engine removeEntity(CombatEntityAPI), resolved lazily apart from the core set. */
        private Method removeEntity;
        private Throwable removeResolveFailure;
        private long errors;
        private final Set<String> logged = new LinkedHashSet<>();

        public ReflectivePort() {
        }

        @Override
        public long portErrorCount() {
            return errors;
        }

        private boolean resolved(String op) {
            if (engineClass != null) {
                return true;
            }
            if (resolveFailure != null) {
                errors++;
                return false;
            }
            try {
                ClassLoader loader = AsteroidFieldServeLane.class.getClassLoader();
                Class<?> ec = Class.forName(ENGINE_CLASS, true, loader);
                Class<?> api = Class.forName(ENTITY_API, false, ec.getClassLoader());
                getInstance = ec.getMethod("getInstance");
                spawnAsteroid = ec.getMethod("spawnAsteroid", int.class, float.class, float.class,
                        float.class, float.class, boolean.class);
                getAsteroids = ec.getMethod("getAsteroids");
                isEntityInPlay = ec.getMethod("isEntityInPlay", api);
                getLocation = api.getMethod("getLocation");
                getVelocity = api.getMethod("getVelocity");
                getFacing = api.getMethod("getFacing");
                getAngularVelocity = api.getMethod("getAngularVelocity");
                getHitpoints = api.getMethod("getHitpoints");
                getCollisionRadius = api.getMethod("getCollisionRadius");
                getMass = api.getMethod("getMass");
                setFacing = api.getMethod("setFacing", float.class);
                setAngularVelocity = api.getMethod("setAngularVelocity", float.class);
                setCollisionRadius = api.getMethod("setCollisionRadius", float.class);
                setHitpoints = api.getMethod("setHitpoints", float.class);
                engineClass = ec;
                return true;
            } catch (Throwable t) {
                resolveFailure = t;
                fail(op + "_resolve", t);
                return false;
            }
        }

        private Object engine() throws ReflectiveOperationException {
            return getInstance.invoke(null);
        }

        private void fail(String op, Throwable t) {
            errors++;
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null
                    ? t.getCause() : t;
            String msg = String.valueOf(cause);
            if (logged.size() < LOG_ONCE_CAP && logged.add(op + "|" + msg)) {
                System.out.println("npeer-serve asteroid_port_failed op=" + op + " err=" + msg);
            }
        }

        /**
         * The engine's own {@code removeEntity(CombatEntityAPI)} (the SeatRebind
         * removeSideHulks recipe), resolved apart from the core set so a missing remove never
         * disables the keeper; a resolve failure latches + counts like {@link #resolved}.
         */
        private boolean removeResolved() {
            if (removeEntity != null) {
                return true;
            }
            if (!resolved("remove")) {
                return false;
            }
            if (removeResolveFailure != null) {
                errors++;
                return false;
            }
            try {
                Class<?> api = Class.forName(ENTITY_API, false, engineClass.getClassLoader());
                removeEntity = engineClass.getMethod("removeEntity", api);
                return true;
            } catch (Throwable t) {
                removeResolveFailure = t;
                fail("remove_resolve", t);
                return false;
            }
        }

        /** true once the engine removeEntity resolved. */
        @Override
        public boolean canRemove() {
            return removeResolved();
        }

        /**
         * {@code CombatEngine.removeEntity(body)}; a throw = {@code fail("remove")} +
         * false.
         */
        @Override
        public boolean remove(Object body) {
            if (body == null || !removeResolved()) {
                return false;
            }
            try {
                removeEntity.invoke(engine(), body);
                return true;
            } catch (Throwable t) {
                fail("remove", t);
                return false;
            }
        }

        @Override
        public Object spawn(int size, float x, float y, float vx, float vy) {
            if (!resolved("spawn")) {
                return null;
            }
            try {
                return spawnAsteroid.invoke(engine(), size, x, y, vx, vy, Boolean.FALSE);
            } catch (Throwable t) {
                fail("spawn", t);
                return null;
            }
        }

        @Override
        public List<Object> asteroids() {
            if (!resolved("asteroids")) {
                return Collections.emptyList();
            }
            try {
                Object list = getAsteroids.invoke(engine());
                if (list instanceof List<?>) {
                    return new ArrayList<>((List<?>) list);
                }
                return Collections.emptyList();
            } catch (Throwable t) {
                fail("asteroids", t);
                return Collections.emptyList();
            }
        }

        /**
         * A port FAILURE answers {@code true}: {@code false} is the
         * engine's own removal signal and maps to a DESTROYED row (a native explosion on every seat);
         * an unreadable engine must not forge it - the pose read then decides (unreadable = refused =
         * a quiet EXPIRED).
         */
        @Override
        public boolean inPlay(Object body) {
            if (!resolved("inPlay")) {
                return true;
            }
            try {
                return Boolean.TRUE.equals(isEntityInPlay.invoke(engine(), body));
            } catch (Throwable t) {
                fail("inPlay", t);
                return true;
            }
        }

        @Override
        public boolean pose(Object body, float[] out8) {
            if (!resolved("pose")) {
                return false;
            }
            try {
                Object loc = getLocation.invoke(body);
                Object vel = getVelocity.invoke(body);
                if (loc == null || vel == null) {
                    return false;
                }
                if (vecX == null) {
                    vecX = loc.getClass().getField("x");
                    vecY = loc.getClass().getField("y");
                }
                out8[0] = vecX.getFloat(loc);
                out8[1] = vecY.getFloat(loc);
                out8[2] = (Float) getFacing.invoke(body);
                out8[3] = vecX.getFloat(vel);
                out8[4] = vecY.getFloat(vel);
                out8[5] = (Float) getAngularVelocity.invoke(body);
                out8[6] = (Float) getHitpoints.invoke(body);
                out8[7] = (Float) getCollisionRadius.invoke(body);
                return true;
            } catch (Throwable t) {
                fail("pose", t);
                return false;
            }
        }

        @Override
        public void setFacing(Object body, float facing) {
            invokeSetter("setFacing", body, facing);
        }

        @Override
        public void setAngularVelocity(Object body, float angVel) {
            invokeSetter("setAngularVelocity", body, angVel);
        }

        @Override
        public void setCollisionRadius(Object body, float radius) {
            invokeSetter("setCollisionRadius", body, radius);
        }

        @Override
        public void setHitpoints(Object body, float hp) {
            invokeSetter("setHitpoints", body, hp);
        }

        private void invokeSetter(String op, Object body, float value) {
            if (!resolved(op)) {
                return;
            }
            try {
                setterFor(op).invoke(body, value);
            } catch (Throwable t) {
                fail(op, t);
            }
        }

        /** Read AFTER {@link #resolved} so the cached handle is never observed unassigned. */
        private Method setterFor(String op) {
            switch (op) {
                case "setFacing":
                    return setFacing;
                case "setAngularVelocity":
                    return setAngularVelocity;
                case "setCollisionRadius":
                    return setCollisionRadius;
                default:
                    return setHitpoints;
            }
        }

        @Override
        public float mass(Object body) {
            if (!resolved("mass")) {
                return Float.NaN;
            }
            try {
                return (Float) getMass.invoke(body);
            } catch (Throwable t) {
                fail("mass", t);
                return Float.NaN;
            }
        }
    }
}
