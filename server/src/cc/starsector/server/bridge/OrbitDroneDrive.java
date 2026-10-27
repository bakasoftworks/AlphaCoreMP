package cc.starsector.server.bridge;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * W3 drone-orbit Shape 1:
 * while a player seat rides a station-launched drone, the STATION's own drone-launcher system
 * keeps advancing the drone's ring slot under our AI yield, so this drive re-derives ZERO orbit
 * geometry — it samples the launcher's {@code getDesiredLocation}/{@code getDesiredVelocity}
 * ring outputs (resolved by 1-param name scan on the launcher system object) and replays the
 * vanilla drone-follower movement law verbatim through the
 * engine's own {@code SmoothMovementModuleCombatCopy} PD easing module ({@code DoNotObfuscate},
 * public 2-float ctor — instantiated reflectively, no source port).
 *
 * <p>Per-tick law (vanilla verbatim): engine-disabled ⇒ mirror smooth←ship, no ship write;
 * else setAcceleration(ship), setMaxSpeed(max(ship.max, max(station.cruise, station.max+50))),
 * advance(desiredLoc, desiredVel, dt), then write-back — dist(ship, smooth) &gt; 10 su ⇒ snap
 * smooth←ship (never move the body), else ship.loc←smooth.loc; ship.vel←smooth.vel
 * unconditionally in the un-nudged branch. NUDGE SIMPLIFICATION (disclosed): on
 * {@code isWasNudged} we {@code setWasNudged(false)} and mirror-frame; vanilla instead
 * accelerates toward the target for a 2s window (not ported). The
 * deviation is collision-window-only and the &gt;10 su guard self-heals displacement.
 *
 * <p>Arm/validate contract: the transfer commit arms a seat wire id when its
 * fresh body is a drone with a live launcher; EVERY tick re-validates (drone bit, mothership
 * alive + not hulk, non-null desired location) so a stale arm is harmless — a fresh respawn
 * body is not a drone, and a dead/hulk mothership or a null ring output falls back to free-fly
 * (not driven ⇒ the pilot's own movement lane stays live).
 * A null desired VELOCITY also falls back — the easing law needs both
 * ring outputs. Any reflective failure disarms the seat loudly once and free-flies.
 *
 * <p><b>Facing author:</b> with the
 * drone's AI yielded nothing authors facing, so the driven ticks also replay the vanilla
 * NON-COMBAT drone facing law — {@code AWAY_FROM_SHIP} (radial outward from the station: the
 * defaultFacing of BOTH {@code drone_station_lt.system} (merlon) and {@code drone_station_mid.system}
 * (ravelin, the standing midline station — orbitDir/orbitSpeed
 * differ per system but ride the sampled ring outputs, never this class); vanilla follower case 4
 * — NO velocity fallback exists in this mode), eased through the vanilla
 * bang-bang turn controller modeled internally (1° deadband, predictive brake
 * {@code 0.5ω²/TD} with overshoot guard, {@code TA} accel ramp, {@code MW} angular-velocity
 * clamp, passive {@code TD} decay inside the deadband).
 * Constants read off the body's own mutable stats
 * ({@code getMaxTurnRate}/{@code getTurnAcceleration}) + the engine controller's
 * {@code getTurnDeceleration} (fallback = TA when unresolvable — v2 [H] TD≈TA). Integration is
 * AUTHORED (internal ω + per-tick {@code setFacing} + {@code setAngularVelocity(0)}) because
 * {@code setFacing} does not zero ω and the engine's no-command
 * angular-decay ordering against a bare ω write is unmeasured — the authored form is
 * deterministic under yield. Degenerate radius (drone exactly at station center) skips the
 * tick — a non-vanilla guard, disclosed (vanilla case 4 has none). A facing-resolve failure
 * disables ONLY the author (ring drive keeps flying), loudly once.
 *
 * <p><b>Glow law:</b> vanilla's follower
 * never issues a command for its plume — it pokes the engine glow directly, so a ridden
 * drone under this drive (movement replayed, glow omitted) read a clear flare flag and its
 * replicas never flared. The driven ticks now also replay the follower's glow block
 * verbatim after the movement block (every exit: engine-disabled mirror, nudge mirror,
 * normal write-back — vanilla runs it after the whole movement if/else): while the module's
 * accel vector is non-zero and the MODULE velocity exceeds 5 su/s, an accel-vs-facing angle
 * under 90° shows ({@code forceShowAccelerating()} + {@code showAccelerating(dt)}, once
 * 1.5 s have passed since the last hide) and over 100° hides ({@code showDecelerating(dt)},
 * once 1.5 s since the last show); undecided frames keep showing/hiding for 0.5 s after the
 * last decision; both timers start at 10.5 s (the class's field inits).
 * The three show/hide hooks are public on the impl controller,
 * resolved by plain name; angle via {@code Math.atan2} where vanilla uses its fast
 * atan2 — sub-degree drift at the 90°/100° gates, disclosed.
 * The engine's own glow integration then sets the flare flag the wire carries as maneuver
 * bit 7, so the ridden drone reads true at the same moments vanilla's would. Fail-soft:
 * a resolve/runtime failure disables ONLY the glow law, loudly once.
 *
 * <p>Reflection lives HERE at birth, never in {@code EngineCombatBinding} (the -f shard's
 * reflection-family home law; ECB is past its size WARN tier and takes only the hook).
 */
final class OrbitDroneDrive {

    private static final String SMOOTH_CLASS =
            "com.fs.starfarer.campaign.fleet.SmoothMovementModuleCombatCopy";
    private static final float WRITEBACK_SNAP_DISTANCE_SU = 10f;
    private static final float STATION_MAX_SPEED_PAD = 50f;
    private static final float FALLBACK_DT_SECONDS = 1f / 60f;
    // ---- glow law constants ----
    private static final float GLOW_TIMER_INIT_SEC = 10.5f;
    private static final float GLOW_MIN_MODULE_SPEED = 5f;
    private static final float GLOW_SHOW_ARC_DEG = 90f;
    private static final float GLOW_HIDE_ARC_DEG = 100f;
    private static final float GLOW_HYSTERESIS_SEC = 1.5f;
    private static final float GLOW_TAIL_SEC = 0.5f;

    /** One armed seat's cached reflection surface + its easing module instance. */
    private static final class ArmedSeat {
        Object smooth;
        Method smoothAdvance;
        Method smoothSetAcceleration;
        Method smoothSetMaxSpeed;
        Method smoothGetLocation;
        Method smoothGetVelocity;
        Method desiredLocation;
        Method desiredVelocity;
        Method isDrone;
        Method getLaunchingShip;
        Method isHulk;
        Method isAlive;
        Method isEngineDisabled;
        Method isWasNudged;
        Method setWasNudged;
        Method getLocation;
        Method getVelocity;
        Method getAcceleration;
        Method getMaxSpeed;
        Method getSystem;
        Method getEngineController;
        Method controllerCruiseSpeed;
        // ---- facing author (W3-L) — null/false = author disabled, drive still flies ----
        Method getFacing;
        Method setFacing;
        Method setAngularVelocity;
        Object maxTurnRateStat;
        Object turnAccelStat;
        Method statModifiedValue;
        Method controllerTurnDecel; // null ⇒ TD falls back to TA
        boolean facingAuthored;
        float authoredTurnRate;
        // ---- glow law (the class javadoc's Glow law) — false = law OFF, drive still flies ----
        Method smoothGetAccelVector;
        Method glowGetFacing;
        Method ctrlForceShowAccelerating;
        Method ctrlShowAccelerating;
        Method ctrlShowDecelerating;
        boolean glowLawArmed;
        float tSinceShow = GLOW_TIMER_INIT_SEC; // time since the last show
        float tSinceHide = GLOW_TIMER_INIT_SEC; // time since the last hide
    }

    private final Map<Long, ArmedSeat> armed = new HashMap<>();

    /**
     * The vanilla drone-AI pick — ONE home for both callers:
     * the transfer COMMIT source restore ({@code SeatRebind.restoreSourceAi})
     * and the transit BEGIN park ({@code
     * EngineCombatBinding.graceAttachTransitAi}). Returns the engine's own
     * {@code ModManager.pickDroneAIPlugin(drone, mothership, system)} result: the default
     * {@code setDefaultAI} path picks {@code BasicShipAI} for drones, so a
     * vacated station drone parked that way free-flies until something re-picks — the observed
     * few seconds of regular basic AI was exactly the transit window between the
     * cautious park and the commit restore. {@code getDroneSource()} is unusable (it reads
     * through {@code getAI() instanceof DroneAI}, null after our yield); the
     * mothership comes from {@code getLaunchingShip()}. The 3-arg pick is resolved by name
     * scan with {@code param[2].isInstance(system)} guarding the cast. Gate-miss ⇒ null: not a
     * drone; mothership null / dead / hulk (the same {@code isAlive}+{@code isHulk} pair the
     * per-tick drive validates on — the commit restore previously read
     * {@code engine.isShipAlive}, unified here); null launcher system; null pick. Reflective
     * failures PROPAGATE — each caller owns its fallback + log line.
     */
    static Object pickVanillaDroneAiOrNull(Object drone) throws ReflectiveOperationException {
        Class<?> shipClass = drone.getClass();
        if (!Boolean.TRUE.equals(shipClass.getMethod("isDrone").invoke(drone))) {
            return null;
        }
        Object mothership = shipClass.getMethod("getLaunchingShip").invoke(drone);
        if (mothership == null
                || Boolean.TRUE.equals(shipClass.getMethod("isHulk").invoke(mothership))
                || !Boolean.TRUE.equals(shipClass.getMethod("isAlive").invoke(mothership))) {
            return null;
        }
        Object system = shipClass.getMethod("getSystem").invoke(mothership);
        if (system == null) {
            return null;
        }
        Class<?> modManagerClass = Class.forName(
                "com.fs.starfarer.launcher.ModManager", false, shipClass.getClassLoader());
        Object modManager = modManagerClass.getMethod("getInstance").invoke(null);
        for (Method candidate : modManagerClass.getMethods()) {
            if ("pickDroneAIPlugin".equals(candidate.getName())
                    && candidate.getParameterCount() == 3
                    && candidate.getParameterTypes()[2].isInstance(system)) {
                return candidate.invoke(modManager, drone, mothership, system);
            }
        }
        return null;
    }

    /**
     * Transfer-commit arm: arm {@code seatWireId} when {@code ship} (the seat's
     * freshly re-keyed body) is a drone with a live launcher-bearing mothership; otherwise
     * clear any prior arm. The smooth module is seeded at the ship's CURRENT loc/vel so the
     * first driven tick never yanks the body toward a zero-initialized easing state. Fail-soft:
     * a reflective failure logs once and leaves the seat unarmed (free-fly, = prior behavior).
     */
    void armIfEligible(long seatWireId, Object ship) {
        armed.remove(seatWireId);
        if (ship == null) {
            return;
        }
        try {
            Class<?> shipClass = ship.getClass();
            ArmedSeat seat = new ArmedSeat();
            seat.isDrone = shipClass.getMethod("isDrone");
            if (!Boolean.TRUE.equals(seat.isDrone.invoke(ship))) {
                return;
            }
            seat.getLaunchingShip = shipClass.getMethod("getLaunchingShip");
            Object mothership = seat.getLaunchingShip.invoke(ship);
            if (mothership == null) {
                return;
            }
            seat.isHulk = shipClass.getMethod("isHulk");
            seat.isAlive = shipClass.getMethod("isAlive");
            if (Boolean.TRUE.equals(seat.isHulk.invoke(mothership))
                    || !Boolean.TRUE.equals(seat.isAlive.invoke(mothership))) {
                return;
            }
            seat.getSystem = shipClass.getMethod("getSystem");
            Object launcher = seat.getSystem.invoke(mothership);
            if (launcher == null) {
                return;
            }
            for (Method candidate : launcher.getClass().getMethods()) {
                if (candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0].isInstance(ship)) {
                    if ("getDesiredLocation".equals(candidate.getName())) {
                        seat.desiredLocation = candidate;
                    } else if ("getDesiredVelocity".equals(candidate.getName())) {
                        seat.desiredVelocity = candidate;
                    }
                }
            }
            if (seat.desiredLocation == null || seat.desiredVelocity == null) {
                System.out.println("orbit-drone-drive arm seat=" + seatWireId
                        + " skipped: launcher " + launcher.getClass().getName()
                        + " exposes no 1-param getDesiredLocation/getDesiredVelocity"
                        + " (not a drone-launcher system) — seat free-flies");
                return;
            }
            seat.isEngineDisabled = shipClass.getMethod("isEngineDisabled");
            seat.isWasNudged = shipClass.getMethod("isWasNudged");
            seat.setWasNudged = shipClass.getMethod("setWasNudged", boolean.class);
            seat.getLocation = shipClass.getMethod("getLocation");
            seat.getVelocity = shipClass.getMethod("getVelocity");
            seat.getAcceleration = shipClass.getMethod("getAcceleration");
            seat.getMaxSpeed = shipClass.getMethod("getMaxSpeed");
            seat.getEngineController = shipClass.getMethod("getEngineController");
            Object controller = seat.getEngineController.invoke(mothership);
            seat.controllerCruiseSpeed = controller.getClass().getMethod("getCruiseSpeed");
            Class<?> smoothClass =
                    Class.forName(SMOOTH_CLASS, false, shipClass.getClassLoader());
            float accel = ((Number) seat.getAcceleration.invoke(ship)).floatValue();
            float maxSpeed = ((Number) seat.getMaxSpeed.invoke(ship)).floatValue();
            seat.smooth = smoothClass.getConstructor(float.class, float.class)
                    .newInstance(accel, maxSpeed);
            seat.smoothAdvance = null;
            for (Method candidate : smoothClass.getMethods()) {
                if ("advance".equals(candidate.getName()) && candidate.getParameterCount() == 3) {
                    seat.smoothAdvance = candidate;
                    break;
                }
            }
            seat.smoothSetAcceleration = smoothClass.getMethod("setAcceleration", float.class);
            seat.smoothSetMaxSpeed = smoothClass.getMethod("setMaxSpeed", float.class);
            seat.smoothGetLocation = smoothClass.getMethod("getLocation");
            seat.smoothGetVelocity = smoothClass.getMethod("getVelocity");
            if (seat.smoothAdvance == null) {
                throw new NoSuchMethodException("3-arg advance missing on " + SMOOTH_CLASS);
            }
            // Seed the easing state at the body's live pose — the un-nudged branch writes
            // ship.vel←smooth.vel unconditionally, so an unseeded module would zero the body.
            copyVec(seat.getLocation.invoke(ship), seat.smoothGetLocation.invoke(seat.smooth));
            copyVec(seat.getVelocity.invoke(ship), seat.smoothGetVelocity.invoke(seat.smooth));
            resolveFacingAuthor(seat, shipClass, ship, seatWireId);
            resolveGlowLaw(seat, shipClass, ship, seatWireId);
            armed.put(seatWireId, seat);
            System.out.println("orbit-drone-drive armed seat=" + seatWireId
                    + " launcher=" + launcher.getClass().getName());
        } catch (Throwable t) {
            System.out.println("orbit-drone-drive arm seat=" + seatWireId
                    + " failed (seat free-flies): " + t.getClass().getName() + ": "
                    + (t.getMessage() == null ? "" : t.getMessage()));
        }
    }

    /** Round reset: every arm dies with the round; the next commit re-arms. */
    void clearArms() {
        armed.clear();
    }

    /**
     * One tick of the orbit drive for {@code seatWireId}'s resolved {@code ship}. Returns
     * {@code true} only when the ring drove (or engine-disabled mirrored) this tick — the
     * caller then yields the movement lane. {@code false} = not armed, validation missed
     * (free-fly fallback), or a reflective failure (which also disarms, loudly once).
     */
    boolean driveIfArmed(long seatWireId, Object ship, float dtSeconds) {
        ArmedSeat seat = armed.get(seatWireId);
        if (seat == null || ship == null) {
            return false;
        }
        try {
            if (!Boolean.TRUE.equals(seat.isDrone.invoke(ship))) {
                return false;
            }
            Object mothership = seat.getLaunchingShip.invoke(ship);
            if (mothership == null
                    || Boolean.TRUE.equals(seat.isHulk.invoke(mothership))
                    || !Boolean.TRUE.equals(seat.isAlive.invoke(mothership))) {
                return false;
            }
            Object launcher = seat.getSystem.invoke(mothership);
            if (launcher == null) {
                return false;
            }
            Object desiredLoc = seat.desiredLocation.invoke(launcher, ship);
            Object desiredVel = seat.desiredVelocity.invoke(launcher, ship);
            if (desiredLoc == null || desiredVel == null) {
                return false;
            }
            Object shipLoc = seat.getLocation.invoke(ship);
            Object shipVel = seat.getVelocity.invoke(ship);
            Object smoothLoc = seat.smoothGetLocation.invoke(seat.smooth);
            Object smoothVel = seat.smoothGetVelocity.invoke(seat.smooth);
            float dt = (Float.isFinite(dtSeconds) && dtSeconds > 0f)
                    ? dtSeconds : FALLBACK_DT_SECONDS;
            if (Boolean.TRUE.equals(seat.isEngineDisabled.invoke(ship))) {
                copyVec(shipVel, smoothVel);
                copyVec(shipLoc, smoothLoc);
                replayGlowLaw(seat, ship, dt);
                return true;
            }
            seat.smoothSetAcceleration.invoke(seat.smooth,
                    ((Number) seat.getAcceleration.invoke(ship)).floatValue());
            Object controller = seat.getEngineController.invoke(mothership);
            float stationCruise =
                    ((Number) seat.controllerCruiseSpeed.invoke(controller)).floatValue();
            float stationMax = ((Number) seat.getMaxSpeed.invoke(mothership)).floatValue();
            float shipMax = ((Number) seat.getMaxSpeed.invoke(ship)).floatValue();
            seat.smoothSetMaxSpeed.invoke(seat.smooth,
                    Math.max(shipMax, Math.max(stationCruise, stationMax + STATION_MAX_SPEED_PAD)));
            seat.smoothAdvance.invoke(seat.smooth, desiredLoc, desiredVel, dt);
            if (Boolean.TRUE.equals(seat.isWasNudged.invoke(ship))) {
                // Disclosed simplification (class javadoc): mirror-frame, no 2s nudge chase.
                seat.setWasNudged.invoke(ship, false);
                copyVec(shipVel, smoothVel);
                copyVec(shipLoc, smoothLoc);
                authorFacing(seat, ship, mothership, dt);
                replayGlowLaw(seat, ship, dt);
                return true;
            }
            float dx = vecX(shipLoc) - vecX(smoothLoc);
            float dy = vecY(shipLoc) - vecY(smoothLoc);
            if (Math.sqrt(dx * dx + dy * dy) > WRITEBACK_SNAP_DISTANCE_SU) {
                copyVec(shipLoc, smoothLoc);
            } else {
                copyVec(smoothLoc, shipLoc);
            }
            copyVec(smoothVel, shipVel);
            authorFacing(seat, ship, mothership, dt);
            replayGlowLaw(seat, ship, dt);
            return true;
        } catch (Throwable t) {
            armed.remove(seatWireId);
            System.out.println("orbit-drone-drive seat=" + seatWireId
                    + " DISARMED on failure (seat free-flies): " + t.getClass().getName()
                    + ": " + (t.getMessage() == null ? "" : t.getMessage()));
            return false;
        }
    }

    /**
     * Resolve the facing-author surface (the class javadoc's Facing author). Fail-soft BY DESIGN:
     * a resolve failure disables only the author — the ring drive itself stays armed, which is
     * exactly the earlier behavior (facing unauthored), never a lost orbit.
     */
    private static void resolveFacingAuthor(ArmedSeat seat, Class<?> shipClass, Object ship,
            long seatWireId) {
        try {
            ClassLoader loader = shipClass.getClassLoader();
            seat.getFacing = shipClass.getMethod("getFacing");
            seat.setFacing = shipClass.getMethod("setFacing", float.class);
            seat.setAngularVelocity = shipClass.getMethod("setAngularVelocity", float.class);
            // Stat reads through the PUBLIC API interfaces (an impl-class Method can be
            // access-refused even for a public method; the interface Method never is).
            Class<?> statsApi = Class.forName(
                    "com.fs.starfarer.api.combat.MutableShipStatsAPI", false, loader);
            Object stats = shipClass.getMethod("getMutableStats").invoke(ship);
            seat.maxTurnRateStat = statsApi.getMethod("getMaxTurnRate").invoke(stats);
            seat.turnAccelStat = statsApi.getMethod("getTurnAcceleration").invoke(stats);
            Class<?> statClass = Class.forName(
                    "com.fs.starfarer.api.combat.MutableStat", false, loader);
            seat.statModifiedValue = statClass.getMethod("getModifiedValue");
            try {
                Object controller = seat.getEngineController.invoke(ship);
                seat.controllerTurnDecel =
                        controller.getClass().getMethod("getTurnDeceleration");
            } catch (Throwable noDecel) {
                seat.controllerTurnDecel = null; // TD ⇒ TA fallback (class javadoc)
            }
            seat.authoredTurnRate = 0f;
            seat.facingAuthored = true;
        } catch (Throwable t) {
            seat.facingAuthored = false;
            System.out.println("orbit-drone-drive facing author OFF seat=" + seatWireId
                    + " (ring drive keeps flying): " + t.getClass().getName() + ": "
                    + (t.getMessage() == null ? "" : t.getMessage()));
        }
    }

    /**
     * One authored facing tick (the class javadoc's Facing author): vanilla AWAY_FROM_SHIP radial
     * -outward desired angle + the vanilla bang-bang ease, authored integration. A runtime
     * failure disables the author loudly once; the drive tick that called us already drove.
     */
    private void authorFacing(ArmedSeat seat, Object ship, Object mothership, float dt) {
        if (!seat.facingAuthored) {
            return;
        }
        try {
            Object shipLoc = seat.getLocation.invoke(ship);
            Object stationLoc = seat.getLocation.invoke(mothership);
            float dx = vecX(shipLoc) - vecX(stationLoc);
            float dy = vecY(shipLoc) - vecY(stationLoc);
            if (dx == 0f && dy == 0f) {
                return; // degenerate radius — disclosed non-vanilla skip (case 4 has no fallback)
            }
            float desired = (float) Math.toDegrees(Math.atan2(dy, dx));
            float facing = ((Number) seat.getFacing.invoke(ship)).floatValue();
            float maxTurnRate = ((Number) seat.statModifiedValue
                    .invoke(seat.maxTurnRateStat)).floatValue();
            float turnAccel = ((Number) seat.statModifiedValue
                    .invoke(seat.turnAccelStat)).floatValue();
            float turnDecel = turnAccel;
            if (seat.controllerTurnDecel != null) {
                turnDecel = ((Number) seat.controllerTurnDecel
                        .invoke(seat.getEngineController.invoke(ship))).floatValue();
            }
            if (!(maxTurnRate > 0f) || !(turnAccel > 0f) || !(turnDecel > 0f)) {
                return; // zero/NaN stats — nothing sane to ease with this tick
            }
            float omega = seat.authoredTurnRate;
            float err = signedAngleDiffDeg(facing, desired);
            float brake = 0.5f * omega * omega / turnDecel; // stopping angle
            float restErr = Math.abs(
                    signedAngleDiffDeg(facing + Math.signum(omega) * brake, desired));
            if (restErr > 1f) { // 1° deadband
                float dir = Math.signum(err);
                if (Math.signum(omega) == dir && brake > Math.abs(err)) {
                    dir = -dir; // overshoot guard
                }
                omega += dir * turnAccel * dt; // accel ramp
                if (Math.abs(omega) > maxTurnRate) {
                    omega = Math.signum(omega) * maxTurnRate; // rate clamp
                }
            } else {
                // In-deadband: vanilla passive decay toward 0.
                float decay = turnDecel * dt;
                omega = Math.abs(omega) <= decay ? 0f : omega - Math.signum(omega) * decay;
            }
            seat.authoredTurnRate = omega;
            float next = facing + omega * dt;
            while (next >= 360f) {
                next -= 360f;
            }
            while (next < 0f) {
                next += 360f;
            }
            seat.setFacing.invoke(ship, next);
            seat.setAngularVelocity.invoke(ship, 0f);
        } catch (Throwable t) {
            seat.facingAuthored = false;
            System.out.println("orbit-drone-drive facing author DISABLED on failure"
                    + " (ring drive keeps flying): " + t.getClass().getName() + ": "
                    + (t.getMessage() == null ? "" : t.getMessage()));
        }
    }

    /**
     * Resolve the glow-law surface (the class javadoc's Glow law): the module's public
     * {@code getAccelVector()}, the body's
     * {@code getFacing}, and the impl controller's three public show hooks
     * ({@code forceShowAccelerating()} / {@code showAccelerating(float)} /
     * {@code showDecelerating(float)} — plain names, resolved
     * on the live controller class like {@code getCruiseSpeed}). Fail-soft BY DESIGN: a
     * resolve failure disables only the glow law; the ring drive + facing author stay armed.
     */
    private static void resolveGlowLaw(ArmedSeat seat, Class<?> shipClass, Object ship,
            long seatWireId) {
        try {
            seat.smoothGetAccelVector = seat.smooth.getClass().getMethod("getAccelVector");
            seat.glowGetFacing = shipClass.getMethod("getFacing");
            Object controller = seat.getEngineController.invoke(ship);
            Class<?> ctrlClass = controller.getClass();
            seat.ctrlForceShowAccelerating = ctrlClass.getMethod("forceShowAccelerating");
            seat.ctrlShowAccelerating = ctrlClass.getMethod("showAccelerating", float.class);
            seat.ctrlShowDecelerating = ctrlClass.getMethod("showDecelerating", float.class);
            seat.tSinceShow = GLOW_TIMER_INIT_SEC;
            seat.tSinceHide = GLOW_TIMER_INIT_SEC;
            seat.glowLawArmed = true;
        } catch (Throwable t) {
            seat.glowLawArmed = false;
            System.out.println("orbit-drone-drive glow law OFF seat=" + seatWireId
                    + " (ring drive keeps flying): " + t.getClass().getName() + ": "
                    + (t.getMessage() == null ? "" : t.getMessage()));
        }
    }

    /**
     * One glow-law tick (the class javadoc's Glow law) — the vanilla follower's glow block
     * verbatim, run after the movement block on every driven exit. Reads the MODULE's accel vector +
     * velocity (not the body's), the body's facing (already authored this tick), and pokes
     * the drone's own controller so the engine's glow integration
     * sets the flare flag the maneuver byte's bit 7 carries. A runtime failure disables the
     * law loudly once; the drive tick that called us already drove.
     */
    private void replayGlowLaw(ArmedSeat seat, Object ship, float dt) {
        if (!seat.glowLawArmed) {
            return;
        }
        try {
            Object accel = seat.smoothGetAccelVector.invoke(seat.smooth);
            Object moduleVel = seat.smoothGetVelocity.invoke(seat.smooth);
            float ax = vecX(accel);
            float ay = vecY(accel);
            float vx = vecX(moduleVel);
            float vy = vecY(moduleVel);
            Object controller = seat.getEngineController.invoke(ship);
            boolean decided = false;
            if ((ax != 0f || ay != 0f)
                    && Math.sqrt(vx * vx + vy * vy) > GLOW_MIN_MODULE_SPEED) { // the accel + speed gate
                float facing = ((Number) seat.glowGetFacing.invoke(ship)).floatValue();
                float accelDeg = (float) Math.toDegrees(Math.atan2(ay, ax));
                float diff = Math.abs(signedAngleDiffDeg(facing, accelDeg)); // in [0,180], like vanilla's angle diff
                if (diff < GLOW_SHOW_ARC_DEG) {
                    if (seat.tSinceHide > GLOW_HYSTERESIS_SEC) { // the show hysteresis
                        seat.ctrlForceShowAccelerating.invoke(controller);
                        seat.ctrlShowAccelerating.invoke(controller, dt);
                        seat.tSinceShow = 0f;
                        decided = true;
                    }
                } else if (diff > GLOW_HIDE_ARC_DEG && seat.tSinceShow > GLOW_HYSTERESIS_SEC) {
                    seat.ctrlShowDecelerating.invoke(controller, dt); // hide
                    seat.tSinceHide = 0f;
                    decided = true;
                }
            }
            if (!decided) { // the 0.5 s show/hide tails
                if (seat.tSinceShow < GLOW_TAIL_SEC) {
                    seat.ctrlForceShowAccelerating.invoke(controller);
                    seat.ctrlShowAccelerating.invoke(controller, dt);
                } else if (seat.tSinceHide < GLOW_TAIL_SEC) {
                    seat.ctrlShowDecelerating.invoke(controller, dt);
                }
            }
            seat.tSinceShow += dt; // both timers advance
            seat.tSinceHide += dt;
        } catch (Throwable t) {
            seat.glowLawArmed = false;
            System.out.println("orbit-drone-drive glow law DISABLED on failure"
                    + " (ring drive keeps flying): " + t.getClass().getName() + ": "
                    + (t.getMessage() == null ? "" : t.getMessage()));
        }
    }

    /** Signed shortest angular distance {@code from → to}, degrees in (-180, 180]. */
    private static float signedAngleDiffDeg(float from, float to) {
        float d = (to - from) % 360f;
        if (d > 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    private static float vecX(Object vec) throws Exception {
        return ((Number) vec.getClass().getMethod("getX").invoke(vec)).floatValue();
    }

    private static float vecY(Object vec) throws Exception {
        return ((Number) vec.getClass().getMethod("getY").invoke(vec)).floatValue();
    }

    /** {@code to.set(from.x, from.y)} — lwjgl Vector2f via reflection. */
    private static void copyVec(Object from, Object to) throws Exception {
        to.getClass().getMethod("set", float.class, float.class)
                .invoke(to, vecX(from), vecY(from));
    }
}
