package cc.starsector.server.bridge;

import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.runtime.TransferPodLane;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * W3 L2: the engine seam behind
 * {@link TransferPodLane.Port} — builds, steers, and removes the physical command-shuttle
 * pod, mirroring vanilla's own pod construction VERBATIM:
 * SpecStore fetch of the {@code shuttlepod_xfer} variant (registered by vanilla's own
 * {@code class.while()} at fleet-manager construction — a miss is a typed deny, fail-closed),
 * the {@code loading.specs._} 3-arg builder with NULL Persons (null-tolerance byte-verified
 * in the builder — {@code _} is a JDK9+ keyword, hence this whole seam rides server-side reflection),
 * {@code CollisionClass.NONE}, owner 0, name "Command Shuttle", facing/location = source,
 * {@code addObject}, then the vanilla {@code TransferCommandAI(pod, target)} — the engine's
 * own object-list AI advance flies it headless (verify [S]). Deviations from vanilla,
 * recorded: {@code setLaunchingShip}/{@code setAnimatedLaunch} SKIPPED (cosmetic; collisions
 * moot at NONE — revisit at L3); Persons null (L5 owns captain moves).
 *
 * <p>LAW: never call {@code Ship.init()} on the pod — the
 * map-null lives in the fake-player lane only. LAW: the pod
 * rides the wire DELIBERATELY — the serve lane's mint branch fans its RAPP row and the old
 * rosterShipList identity exclusion is DELETED, so the wire mirrors the world: a failed
 * removeObject leaves the pod in-world AND on-wire (honest; the LOUD line below stays the
 * signal).
 *
 * <p>ONE-ENTITY flips: the pod body is the seat's
 * hull for its whole life, so the spawner also FLIPS a body between its two shapes in place.
 * The ENTIRE engine pod special-casing is one live predicate {@code Ship.isShuttlePod()} =
 * {@code hullSize==FRIGATE && variantId.equals("shuttlepod_xfer")},
 * and {@code Ship.setVariantForHullmodCheckOnly(ShipVariantAPI)} is a
 * bare {@code this.spec} write that {@code getVariant()}/{@code isShuttlePod()} read live —
 * {@code shuttlepod_cc_pod} and {@code shuttlepod_xfer} are the SAME hull, so a flip drifts
 * no stat. Both specs are REGISTRY SINGLETONS (the xfer spec off SpecStore, the
 * cc_pod file variant off the engine's variant registry through
 * {@link RealEngineDemoRoster#resolveVariantById(String)} — the same lookup every deploy
 * uses), so every flip installs a {@code HullVariantSpec.clone()} (which
 * deep-copies weapons/groups/modules), never the shared object. {@link #toFlight} = cc_pod →
 * xfer + {@code CollisionClass.NONE} + {@code TransferCommandAI}; {@link #toPilotable} = the
 * strand: {@code abortLanding()} (public) + {@code setSinceLaunch(1.5f)}
 * past the {@code giveCommand} lift-off gate ({@code isLiftingOffOrLanding}
 * = {@code isAnimatedLaunch && sinceLaunch <= 1}), xfer →
 * cc_pod, {@code setAI(null)}, velocity zero, pose pushed clear of the landed-on hull and
 * clamped {@link #POSE_PUSH_ZONE_SLACK} inside the map, {@code CollisionClass.SHIP} LAST
 * (the offset precedes the NONE→SHIP flip).
 *
 * <p>Every port method is total: reflective failures log one {@code pod_*} line and return
 * null/false/NaN — the lane strands fail-closed (the seat is aboard the body, so a failed
 * verb never removes it) and the transfer stays arbiter-truth. The handle resolve latches
 * its failure (one LOUD line, then quiet denies). Sim-thread only.
 */
public final class TransferPodSpawner implements TransferPodLane.Port {

    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String COLLISION_CLASS = "com.fs.starfarer.api.combat.CollisionClass";
    private static final String SPEC_STORE = "com.fs.starfarer.loading.SpecStore";
    private static final String HULL_VARIANT_SPEC = "com.fs.starfarer.loading.specs.HullVariantSpec";
    private static final String SHIP_FACTORY = "com.fs.starfarer.loading.specs._";
    private static final String TRANSFER_AI =
            "com.fs.starfarer.combat.ai.movement.maneuvers.TransferCommandAI";
    private static final String VECTOR2F = "org.lwjgl.util.vector.Vector2f";
    private static final String POD_VARIANT_ID = "shuttlepod_xfer";
    /** The PILOTABLE shape — our mod's file variant of the same hull
     * (the bastion roster's seat row), resolved off the engine's variant registry. */
    private static final String PILOTABLE_VARIANT_ID = MatchDescriptors.BASTION_SHUTTLEPOD_VARIANT_ID;
    private static final String POD_NAME = "Command Shuttle";
    /** {@code Ship.giveCommand} drops every command while
     * {@code isLiftingOffOrLanding} (= {@code isAnimatedLaunch && sinceLaunch <= 1.0f},
     * recomputed in {@code advance}); {@code abortLanding()} leaves
     * sinceLaunch at max(0, prev) so a docked pod would ignore input ~1 s. A stranded body
     * sets sinceLaunch to this (public {@code setSinceLaunch(float)}) — the
     * gate clears on the NEXT advance (one command may still drop that tick). */
    private static final float LIFTOFF_GATE_CLEAR = 1.5f;
    /** A landing/docked pod sits INSIDE its target's bounds (the
     * mover snaps it) — the strand pushes it out past both collision radii plus
     * this margin BEFORE the NONE→SHIP flip, else the engine's push-apart fires inside a hulk. */
    private static final float STRAND_CLEARANCE = 25f;
    /** Combat-map edge getters: minX, minY,
     * maxX, maxY — legal-name reflection, UTF-8 source (JEP 400). */
    private static final String MAP_MIN_X = "Ò00000";
    private static final String MAP_MIN_Y = "Õ00000";
    private static final String MAP_MAX_X = "Ö00000";
    private static final String MAP_MAX_Y = "void";
    /** Vanilla clamps the pod to the map edges ±1000 OUTSIDE. */
    private static final float MAP_SLACK = 1000f;
    /** A STRANDED body ({@link #toPilotable}) is clamped this far
     * INSIDE the map bounds. The engine's map-edge return push starts 200 su inside each
     * bound (strict &gt;, origin-ward velocity add up to the ship's max speed each frame, no
     * damping once clear) and its {@code isShuttlePod()} exemption
     * is hardcoded to the vanilla {@code shuttlepod_xfer} variant id —
     * the moment the body flips to {@code shuttlepod_cc_pod} it is an ordinary frigate to
     * that push, so a velocity-zero body outside this inset is NOT at rest. Probe-proven: a
     * slack-0 edge pose coasts ~2200 su across the map at max speed (P11 leg B), a pose
     * 250 su inside sits exactly at rest (leg C). 200 zone + 50 margin. */
    private static final float POSE_PUSH_ZONE_SLACK = 250f;

    private static final class Handles {
        Class<?> shipClass;
        Class<?> specClass;
        Class<?> aiClass;
        Method getInstance;
        Method getShips;
        Method getCombatMap;
        Method addObject;
        Method removeObject;
        Method getId;
        Method specFetch;
        Method factoryBuild;
        Method setCollisionClass;
        Object collisionNone;
        Method setOwner;
        /** The source's live + original owner: the pod's team ({@link #podOwnerFor}). */
        Method getOwner;
        Method getOriginalOwner;
        Method setName;
        Method getFacing;
        Method setFacing;
        Method getLocation;
        Field vecX;
        Field vecY;
        Method vecSet;
        Constructor<?> aiCtor;
        Method setAI;
        Method getAI;
        Method aiSetTo;
        Method isLanding;
        Method beginLanding;
        Method isFinishedLanding;
        Method isAlive;
        // The flip seams — described in the class javadoc.
        Object collisionShip;
        /** {@code Ship.setVariantForHullmodCheckOnly(ShipVariantAPI)}. */
        Method setVariant;
        /** {@code Ship.abortLanding()} (public). */
        Method abortLanding;
        /** {@code Ship.setSinceLaunch(float)} (public). */
        Method setSinceLaunch;
        /** {@code BaseEntity.getVelocity()} / {@code getCollisionRadius()}. */
        Method getVelocity;
        Method getCollisionRadius;
        /** {@code Ship.getVariant()} + {@code HullVariantSpec.getHullVariantId()}
         * + {@code HullVariantSpec.clone()}. */
        Method getVariant;
        Method getHullVariantId;
        Method specClone;
        /** The {@code ShipAPI} kind predicates the {@code /despawn}
         * refusal keys on — {@code isFighter()} (a wing member: its carrier's deck owns it),
         * {@code isStationModule()} + {@code getParentStation()} (an engine-minted STATION_MODULE
         * child rides its parent's wire band, {@code internWaveHull}); the same trio
         * {@code EngineCombatBinding} reads for its roster-size dump. */
        Method isFighter;
        Method isStationModule;
        Method getParentStation;
        /** The cc_pod registry singleton, resolved lazily (content may post-date the handle
         * resolve); cloned per flip, never installed as-is. */
        Object pilotableSpec;
        // Map-edge methods resolve lazily off the live map object (belt-tier: a resolve
        // failure downgrades the clamp to a logged no-op, never the flight).
        Method mapMinX;
        Method mapMinY;
        Method mapMaxX;
        Method mapMaxY;
        boolean mapUnavailable;
    }

    private Handles handles;
    private boolean handlesFailed;

    private Handles handlesOrNull() {
        if (handles != null) {
            return handles;
        }
        if (handlesFailed) {
            return null;
        }
        try {
            ClassLoader loader = TransferPodSpawner.class.getClassLoader();
            Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
            Class<?> shipClass = Class.forName(SHIP_CLASS, false, loader);
            Class<?> shipApiClass = Class.forName(SHIP_API, false, loader);
            Class<?> aiClass = Class.forName(TRANSFER_AI, false, loader);
            Class<?> vecClass = Class.forName(VECTOR2F, false, loader);
            Handles h = new Handles();
            h.shipClass = shipClass;
            h.specClass = Class.forName(HULL_VARIANT_SPEC, false, loader);
            h.aiClass = aiClass;
            h.getInstance = engineClass.getMethod("getInstance");
            h.getShips = engineClass.getMethod("getShips");
            h.getCombatMap = engineClass.getMethod("getCombatMap");
            h.addObject = methodAccepting(engineClass, "addObject", shipClass);
            h.removeObject = methodAccepting(engineClass, "removeObject", shipClass);
            h.getId = shipClass.getMethod("getId");
            h.specFetch = Class.forName(SPEC_STORE, true, loader)
                    .getMethod("o00000", Class.class, String.class);
            h.factoryBuild = factoryBuild(Class.forName(SHIP_FACTORY, true, loader), h.specClass);
            h.collisionNone = Class.forName(COLLISION_CLASS, false, loader)
                    .getField("NONE").get(null);
            h.setCollisionClass =
                    methodAccepting(shipClass, "setCollisionClass", h.collisionNone.getClass());
            h.setOwner = shipClass.getMethod("setOwner", int.class);
            h.getOwner = shipClass.getMethod("getOwner");
            h.getOriginalOwner = shipClass.getMethod("getOriginalOwner");
            h.setName = shipClass.getMethod("setName", String.class);
            h.getFacing = shipClass.getMethod("getFacing");
            h.setFacing = shipClass.getMethod("setFacing", float.class);
            h.getLocation = shipClass.getMethod("getLocation");
            h.vecX = vecClass.getField("x");
            h.vecY = vecClass.getField("y");
            h.vecSet = vecClass.getMethod("set", float.class, float.class);
            h.aiCtor = aiClass.getConstructor(shipClass, shipClass);
            h.setAI = methodAccepting(shipClass, "setAI", aiClass);
            h.getAI = shipClass.getMethod("getAI");
            h.aiSetTo = aiClass.getMethod("setTo", shipClass);
            h.isLanding = shipClass.getMethod("isLanding");
            h.beginLanding = shipClass.getMethod("beginLandingAnimation", shipApiClass);
            h.isFinishedLanding = shipClass.getMethod("isFinishedLanding");
            h.isAlive = shipClass.getMethod("isAlive");
            h.collisionShip = h.collisionNone.getClass().getField("SHIP").get(null);
            h.setVariant = methodAccepting(shipClass, "setVariantForHullmodCheckOnly", h.specClass);
            h.abortLanding = shipClass.getMethod("abortLanding");
            h.setSinceLaunch = shipClass.getMethod("setSinceLaunch", float.class);
            h.getVelocity = shipClass.getMethod("getVelocity");
            h.getCollisionRadius = shipClass.getMethod("getCollisionRadius");
            h.getVariant = shipClass.getMethod("getVariant");
            h.getHullVariantId = h.specClass.getMethod("getHullVariantId");
            h.specClone = h.specClass.getMethod("clone");
            h.isFighter = shipClass.getMethod("isFighter");
            h.isStationModule = shipClass.getMethod("isStationModule");
            h.getParentStation = shipClass.getMethod("getParentStation");
            handles = h;
            return h;
        } catch (ReflectiveOperationException | RuntimeException e) {
            handlesFailed = true;
            System.out.println("npeer-serve pod_handles_unresolved err=" + e);
            return null;
        }
    }

    /** The declared single-arg overload accepting {@code argClass} (addObject/removeObject
     * declare the engine's entity supertype; setAI its AI interface). */
    private static Method methodAccepting(Class<?> owner, String name, Class<?> argClass)
            throws NoSuchMethodException {
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(argClass)) {
                return m;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name
                + "(accepting " + argClass.getName() + ")");
    }

    /** The 3-arg builder on {@code _} taking (HullVariantSpec, Person, Person)
     * — the overload vanilla's pod construction calls; both Persons ride null here. */
    private static Method factoryBuild(Class<?> factoryClass, Class<?> specClass)
            throws NoSuchMethodException {
        for (Method m : factoryClass.getMethods()) {
            if (m.getName().equals("o00000") && m.getParameterCount() == 3
                    && m.getParameterTypes()[0].isAssignableFrom(specClass)) {
                return m;
            }
        }
        throw new NoSuchMethodException(factoryClass.getName()
                + ".o00000(spec, Person, Person)");
    }

    private Object engineOrNull(Handles h) throws ReflectiveOperationException {
        return h.getInstance.invoke(null);
    }

    /**
     * Round-0 first-execution warm (the
     * treadmill law): without this, the first live {@code pod_spawn} pays
     * the lane's whole JVM first-execution bill ON the sim thread inside the pre_seat
     * bracket — handle resolve, the SpecStore lazy {@code shuttlepod_xfer} fetch (disk-bound:
     * the 89–438 ms corpus spread — 10/10 stalls are per-process FIRSTS, repeats
     * never stall), the factory build's classloads, and the {@code TransferCommandAI} ctor
     * classload. Prefetch-only BY LAW: the built pod is never {@code addObject}'ed and no AI
     * is installed — a later amendment dropped the engine add/remove round-trip (promotion
     * runs only in the advance() pending-add drain, never in pump(), so the round-trip warmed
     * nothing this prefetch doesn't; removeObject would queue a pending-REMOVE besides). The
     * ctor is pure field assignment, so the warm pod
     * riding as its own target is inert. Fail-soft: returns a status string for the caller's
     * one {@code pod_spawn_warm} line; a warm failure never gates the serve.
     */
    public String warmSpawnCycle() {
        Handles h = handlesOrNull();
        if (h == null) {
            return "handles=fail";
        }
        try {
            Object spec = h.specFetch.invoke(null, h.specClass, POD_VARIANT_ID);
            if (spec == null) {
                return "handles=ok spec=missing";
            }
            Object pod = h.factoryBuild.invoke(null, spec, null, null);
            if (pod == null) {
                return "handles=ok spec=ok build=null";
            }
            h.aiCtor.newInstance(pod, pod);
            // Flip warm: the cc_pod registry resolve + one clone (the strand's first bill).
            String pilotable = pilotableSpecCloneOrNull(h) == null ? "missing" : "ok";
            return "handles=ok spec=ok build=ok ai=ok pilotable=" + pilotable;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "err=" + e;
        }
    }

    /** A FRESH clone of the {@code shuttlepod_cc_pod} spec (the registry singleton resolved
     * once through the deploy lane's own lookup, {@link RealEngineDemoRoster#resolveVariantById(String)}
     * — the engine-internal registry identity stays named there), or null = unresolvable (one
     * LOUD line per call; the caller denies). */
    private static Object pilotableSpecCloneOrNull(Handles h) {
        try {
            if (h.pilotableSpec == null) {
                h.pilotableSpec = RealEngineDemoRoster.resolveVariantById(PILOTABLE_VARIANT_ID);
            }
            return h.specClone.invoke(h.pilotableSpec);
        } catch (Exception e) {
            System.out.println("npeer-serve pod_pilotable_spec_missing variant="
                    + PILOTABLE_VARIANT_ID + " err=" + e);
            return null;
        }
    }

    @Override
    public Object resolveShipOrNull(String stableId) {
        Handles h = handlesOrNull();
        if (h == null || stableId == null) {
            return null;
        }
        try {
            Object engine = engineOrNull(h);
            if (engine == null) {
                return null;
            }
            // The roster's waveProbeSampleLine resolve idiom: stable id off the live list.
            for (Object candidate : (List<?>) h.getShips.invoke(engine)) {
                if (h.shipClass.isInstance(candidate)
                        && stableId.equals(h.getId.invoke(candidate))) {
                    return candidate;
                }
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve pod_resolve_threw id=" + stableId + " err=" + e);
            return null;
        }
    }

    /**
     *
     * The pod's owner = the team of the seat launching it, read off its source hull
     * ({@link SeatRebind#seatSideOf}: the live owner, a wreck by its original owner); an
     * unreadable source keeps the pre-team owner 0. Range 0..1.
     */
    public static int podOwnerFor(Object sourceLiveOwner, Object sourceOriginalOwner) {
        return SeatRebind.seatSideOf(sourceLiveOwner, sourceOriginalOwner, 0);
    }

    @Override
    public Object spawnPodOrNull(Object sourceShip, Object targetShip) {
        Handles h = handlesOrNull();
        if (h == null || sourceShip == null || targetShip == null) {
            return null;
        }
        boolean added = false;
        Object pod = null;
        Object engine = null;
        try {
            Object spec = h.specFetch.invoke(null, h.specClass, POD_VARIANT_ID);
            if (spec == null) {
                System.out.println("npeer-serve pod_spec_missing variant=" + POD_VARIANT_ID);
                return null;
            }
            pod = h.factoryBuild.invoke(null, spec, null, null);
            if (pod == null) {
                System.out.println("npeer-serve pod_build_null variant=" + POD_VARIANT_ID);
                return null;
            }
            // Vanilla-verbatim dress-up. NEVER Ship.init() (the class-javadoc LAW).
            h.setCollisionClass.invoke(pod, h.collisionNone);
            // Vanilla owns a pod by its fleet manager's
            // side; ours flies for the SEAT's team (the transfer commit reads the pod's owner as
            // the seat's side, so a north pod owned by side 0 would refuse every north hull).
            h.setOwner.invoke(pod, podOwnerFor(h.getOwner.invoke(sourceShip),
                    h.getOriginalOwner.invoke(sourceShip)));
            h.setName.invoke(pod, POD_NAME);
            h.setFacing.invoke(pod, ((Number) h.getFacing.invoke(sourceShip)).floatValue());
            Object sourceLoc = h.getLocation.invoke(sourceShip);
            h.vecSet.invoke(h.getLocation.invoke(pod),
                    h.vecX.getFloat(sourceLoc), h.vecY.getFloat(sourceLoc));
            engine = engineOrNull(h);
            if (engine == null) {
                System.out.println("npeer-serve pod_engine_null");
                return null;
            }
            h.addObject.invoke(engine, pod);
            added = true;
            h.setAI.invoke(pod, h.aiCtor.newInstance(pod, targetShip));
            return pod;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve pod_spawn_threw err=" + e);
            if (added) {
                // Rollback the world mutation (guarded, fail-closed): a half-built pod must
                // not stay in the engine; if even the removal fails, the walk family's own
                // guards stay the floor (and the wire honestly mirrors the leftover).
                try {
                    h.removeObject.invoke(engine, pod);
                } catch (ReflectiveOperationException | RuntimeException rollback) {
                    System.out.println("npeer-serve pod_rollback_failed err=" + rollback);
                }
            }
            return null;
        }
    }

    @Override
    public boolean retargetPod(Object pod, Object newTarget) {
        Handles h = handlesOrNull();
        if (h == null || pod == null || newTarget == null) {
            return false;
        }
        try {
            Object ai = h.getAI.invoke(pod);
            if (!h.aiClass.isInstance(ai)) {
                return false;
            }
            h.aiSetTo.invoke(ai, newTarget);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve pod_retarget_threw err=" + e);
            return false;
        }
    }

    /** The lazy live-map + edge-method resolve shared by {@link #clampToMap} and
     * {@link #toPilotable} (belt-tier): the map object with {@code h.mapMinX..MaxY}
     * resolved, or null (engine/map not up yet). Throws ride to the caller's
     * {@code mapUnavailable} latch. */
    private Object mapWithEdgesOrNull(Handles h) throws ReflectiveOperationException {
        Object engine = engineOrNull(h);
        if (engine == null) {
            return null;
        }
        Object map = h.getCombatMap.invoke(engine);
        if (map == null) {
            return null;
        }
        if (h.mapMinX == null) {
            Class<?> mapClass = map.getClass();
            h.mapMinX = mapClass.getMethod(MAP_MIN_X);
            h.mapMinY = mapClass.getMethod(MAP_MIN_Y);
            h.mapMaxX = mapClass.getMethod(MAP_MAX_X);
            h.mapMaxY = mapClass.getMethod(MAP_MAX_Y);
        }
        return map;
    }

    @Override
    public void clampToMap(Object pod) {
        Handles h = handlesOrNull();
        if (h == null || pod == null || h.mapUnavailable) {
            return;
        }
        try {
            Object map = mapWithEdgesOrNull(h);
            if (map == null) {
                return;
            }
            Object loc = h.getLocation.invoke(pod);
            float x = h.vecX.getFloat(loc);
            float y = h.vecY.getFloat(loc);
            float clampedX = Math.max(((Number) h.mapMinX.invoke(map)).floatValue() - MAP_SLACK,
                    Math.min(((Number) h.mapMaxX.invoke(map)).floatValue() + MAP_SLACK, x));
            float clampedY = Math.max(((Number) h.mapMinY.invoke(map)).floatValue() - MAP_SLACK,
                    Math.min(((Number) h.mapMaxY.invoke(map)).floatValue() + MAP_SLACK, y));
            if (clampedX != x || clampedY != y) {
                h.vecSet.invoke(loc, clampedX, clampedY);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            h.mapUnavailable = true;
            System.out.println("npeer-serve pod_clamp_unavailable err=" + e);
        }
    }

    /** Engine {@code Ship.isAlive()} (= !isHulk && hp&gt;0 &&
     * in-engine — false for a hulk, a fractured piece, or a removed body). Total-false:
     * null / non-Ship / a reflective throw all read dead (the strand side of the both-dead
     * law is the fail-safe end — a pilotable pod, never a blind return-home). */
    @Override
    public boolean isAlive(Object ship) {
        Handles h = handlesOrNull();
        if (h == null || ship == null || !h.shipClass.isInstance(ship)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(h.isAlive.invoke(ship));
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve pod_isalive_threw err=" + e);
            return false;
        }
    }

    /** {@code Ship.abortLanding()} (= setAnimatedLaunch
     * + sinceLaunch max(0, prev): isLanding false, the launch animation replays, so the
     * {@code giveCommand} gate holds ~1 s — vanilla's own lift-off feel, kept for a
     * retarget; only the strand clears it). True without a call when not landing. */
    @Override
    public boolean abortLanding(Object pod) {
        Handles h = handlesOrNull();
        if (h == null || pod == null) {
            return false;
        }
        try {
            if (!Boolean.TRUE.equals(h.isLanding.invoke(pod))) {
                return true;
            }
            h.abortLanding.invoke(pod);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve pod_abortlanding_threw err=" + e);
            return false;
        }
    }

    /** A pilotable body resumes a flight IN PLACE. Everything that can
     * fail (SpecStore fetch, clone, the AI ctor — pure field assignment)
     * runs BEFORE the three writes, so a deny leaves the body untouched; the AI goes on
     * LAST so it never advances a half-flipped body. */
    @Override
    public boolean toFlight(Object body, Object target) {
        Handles h = handlesOrNull();
        if (h == null || body == null || target == null) {
            return false;
        }
        try {
            Object spec = h.specFetch.invoke(null, h.specClass, POD_VARIANT_ID);
            if (spec == null) {
                System.out.println("npeer-serve pod_spec_missing variant=" + POD_VARIANT_ID);
                return false;
            }
            Object xferClone = h.specClone.invoke(spec);
            Object ai = h.aiCtor.newInstance(body, target);
            h.setVariant.invoke(body, xferClone);
            h.setCollisionClass.invoke(body, h.collisionNone);
            h.setAI.invoke(body, ai);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve pod_toflight_threw err=" + e);
            return false;
        }
    }

    /**
     * STRAND in place — the flying pod becomes the seat's pilotable
     * hull where it is. Order: (a) lift off if landing + {@link #LIFTOFF_GATE_CLEAR}; (b) spec
     * flip xfer → cc_pod clone; (c) {@code setAI(null)} (a plain field write
     * — the body COASTS with no AI and no command); (d) velocity zero; (e) pose pushed out
     * past {@code landedOnOrNull}'s collision radius ({@link #STRAND_CLEARANCE}); (f) clamped
     * {@link #POSE_PUSH_ZONE_SLACK} inside the map (fail-soft on the map seam, as
     * {@link #clampToMap}); (g) {@code CollisionClass.SHIP} LAST — the ordering law puts the
     * offset before the NONE→SHIP flip. The cc_pod spec is cloned FIRST so an unresolvable
     * variant denies before any write. Any throw after that = LOUD false, body half-flipped
     * (the caller still strands — the seat is aboard).
     */
    @Override
    public boolean toPilotable(Object pod, Object landedOnOrNull) {
        Handles h = handlesOrNull();
        if (h == null || pod == null) {
            return false;
        }
        try {
            Object spec = pilotableSpecCloneOrNull(h);
            if (spec == null) {
                return false;
            }
            if (Boolean.TRUE.equals(h.isLanding.invoke(pod))) {
                h.abortLanding.invoke(pod);
            }
            h.setSinceLaunch.invoke(pod, LIFTOFF_GATE_CLEAR);
            h.setVariant.invoke(pod, spec);
            h.setAI.invoke(pod, new Object[] {null});
            h.vecSet.invoke(h.getVelocity.invoke(pod), 0f, 0f);
            Object loc = h.getLocation.invoke(pod);
            if (landedOnOrNull != null) {
                Object onLoc = h.getLocation.invoke(landedOnOrNull);
                float ox = h.vecX.getFloat(onLoc);
                float oy = h.vecY.getFloat(onLoc);
                double dx = h.vecX.getFloat(loc) - ox;
                double dy = h.vecY.getFloat(loc) - oy;
                double len = Math.hypot(dx, dy);
                double dirX;
                double dirY;
                if (len < 1e-3) {
                    // Coincident (the mover snapped it onto the target): leave along the facing.
                    double facing = Math.toRadians(((Number) h.getFacing.invoke(pod)).floatValue());
                    dirX = Math.cos(facing);
                    dirY = Math.sin(facing);
                } else {
                    dirX = dx / len;
                    dirY = dy / len;
                }
                float dist = ((Number) h.getCollisionRadius.invoke(landedOnOrNull)).floatValue()
                        + ((Number) h.getCollisionRadius.invoke(pod)).floatValue()
                        + STRAND_CLEARANCE;
                h.vecSet.invoke(loc, (float) (ox + dirX * dist), (float) (oy + dirY * dist));
            }
            clampInsideMap(h, loc);
            h.setCollisionClass.invoke(pod, h.collisionShip);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve pod_pilotable_threw err=" + e);
            return false;
        }
    }

    /** The inset clamp ({@link #POSE_PUSH_ZONE_SLACK} INSIDE the bounds) on a live
     * location vector — fail-soft on the map seam exactly like {@link #clampToMap}
     * ({@code mapUnavailable} latch ⇒ the location stays as-is, one LOUD line). */
    private void clampInsideMap(Handles h, Object loc) throws ReflectiveOperationException {
        if (h.mapUnavailable) {
            return;
        }
        try {
            Object map = mapWithEdgesOrNull(h);
            if (map == null) {
                return;
            }
            float x = h.vecX.getFloat(loc);
            float y = h.vecY.getFloat(loc);
            float clampedX = Math.max(((Number) h.mapMinX.invoke(map)).floatValue() + POSE_PUSH_ZONE_SLACK,
                    Math.min(((Number) h.mapMaxX.invoke(map)).floatValue() - POSE_PUSH_ZONE_SLACK, x));
            float clampedY = Math.max(((Number) h.mapMinY.invoke(map)).floatValue() + POSE_PUSH_ZONE_SLACK,
                    Math.min(((Number) h.mapMaxY.invoke(map)).floatValue() - POSE_PUSH_ZONE_SLACK, y));
            if (clampedX != x || clampedY != y) {
                h.vecSet.invoke(loc, clampedX, clampedY);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            h.mapUnavailable = true;
            System.out.println("npeer-serve pod_clamp_unavailable err=" + e);
        }
    }

    /** A LIVE stranded pod body — an engine Ship, {@code isAlive()},
     * whose live variant id is {@code shuttlepod_cc_pod}. Total-false (a dead cc_pod wreck,
     * a non-Ship, or a read throw all read false = an ordinary source). */
    @Override
    public boolean isPodBody(Object ship) {
        Handles h = handlesOrNull();
        if (h == null || ship == null || !h.shipClass.isInstance(ship)) {
            return false;
        }
        try {
            if (!Boolean.TRUE.equals(h.isAlive.invoke(ship))) {
                return false;
            }
            Object variant = h.getVariant.invoke(ship);
            return variant != null
                    && PILOTABLE_VARIANT_ID.equals(h.getHullVariantId.invoke(variant));
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve pod_ispodbody_threw err=" + e);
            return false;
        }
    }

    @Override
    public float distanceOrNaN(Object shipA, Object shipB) {
        Handles h = handlesOrNull();
        if (h == null || shipA == null || shipB == null) {
            return Float.NaN;
        }
        try {
            Object locA = h.getLocation.invoke(shipA);
            Object locB = h.getLocation.invoke(shipB);
            double dx = h.vecX.getFloat(locA) - h.vecX.getFloat(locB);
            double dy = h.vecY.getFloat(locA) - h.vecY.getFloat(locB);
            return (float) Math.hypot(dx, dy);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve pod_distance_threw err=" + e);
            return Float.NaN;
        }
    }

    /** A ship's live location as {@code {x, y}}, null when unresolvable —
     * the podtest driver's spawn pose (the target lands ±1800 su from the seat body). */
    public float[] locationOrNull(Object ship) {
        Handles h = handlesOrNull();
        if (h == null || ship == null) {
            return null;
        }
        try {
            Object loc = h.getLocation.invoke(ship);
            return loc == null ? null : new float[] {h.vecX.getFloat(loc), h.vecY.getFloat(loc)};
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve pod_location_threw err=" + e);
            return null;
        }
    }

    /** Pin a live ship in place — {@code setAI(null)} + velocity zero (the
     * {@link #toPilotable} idiom); a live-AI friendly podtest target would otherwise fly off
     * toward the wave field before the pod reaches it. Total-false on any fault. */
    public boolean holdStill(Object ship) {
        Handles h = handlesOrNull();
        if (h == null || ship == null) {
            return false;
        }
        try {
            h.setAI.invoke(ship, new Object[] {null});
            h.vecSet.invoke(h.getVelocity.invoke(ship), 0f, 0f);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve pod_hold_threw err=" + e);
            return false;
        }
    }

    @Override
    public boolean isLanding(Object pod) {
        Handles h = handlesOrNull();
        if (h == null || pod == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(h.isLanding.invoke(pod));
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve pod_islanding_threw err=" + e);
            return false;
        }
    }

    @Override
    public boolean beginLanding(Object pod, Object landOn) {
        Handles h = handlesOrNull();
        if (h == null || pod == null || landOn == null) {
            return false;
        }
        try {
            h.beginLanding.invoke(pod, landOn);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve pod_beginlanding_threw err=" + e);
            return false;
        }
    }

    @Override
    public boolean isFinishedLanding(Object pod) {
        Handles h = handlesOrNull();
        if (h == null || pod == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(h.isFinishedLanding.invoke(pod));
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve pod_finishedlanding_threw err=" + e);
            return false;
        }
    }

    @Override
    public String stableIdOrNull(Object pod) {
        Handles h = handlesOrNull();
        if (h == null || pod == null) {
            return null;
        }
        try {
            Object id = h.getId.invoke(pod);
            if (!(id instanceof String)) {
                return null;
            }
            String trimmed = ((String) id).trim();
            return trimmed.isEmpty() ? null : trimmed;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve pod_stable_threw err=" + e);
            return null;
        }
    }

    /**
     * Is {@code ship} a body the {@code /despawn} verb must
     * refuse because it is OWNED by another hull — a fighter (its wing / carrier deck) or a
     * station module ({@code isStationModule()} or a non-null {@code getParentStation()}: an
     * engine-minted child under a live parent; {@code removeObject} on it would leave the parent's
     * module slot dangling [hypothesis on engine behaviour, unexercised — hence the refusal]).
     * Null = the engine could not be asked (handles unresolved / a throw) — the caller treats
     * null as a refusal (fail-closed), never as "not a module".
     */
    public Boolean isModuleOrFighterOrNull(Object ship) {
        Handles h = handlesOrNull();
        if (h == null || ship == null) {
            return null;
        }
        try {
            return Boolean.TRUE.equals(h.isFighter.invoke(ship))
                    || Boolean.TRUE.equals(h.isStationModule.invoke(ship))
                    || h.getParentStation.invoke(ship) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve ship_kind_read_failed err=" + e);
            return null;
        }
    }

    /**
     * {@link #despawnPod}'s law on ANY engine ship handle, with the outcome
     * returned — the {@code /despawn} operator arm mails it as the receipt (D5 law), so the
     * failure is a receipt AND a LOUD row, never a swallowed miss. Same engine door
     * ({@code removeObject}: a vanish, no hulk — the pod precedent), same fail-soft shape.
     */
    public boolean removeShipOrFalse(Object ship, String site) {
        Handles h = handlesOrNull();
        if (h == null || ship == null) {
            return false;
        }
        try {
            Object engine = engineOrNull(h);
            if (engine == null) {
                return false;
            }
            h.removeObject.invoke(engine, ship);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("npeer-serve ship_remove_failed site=" + site + " err=" + e);
            return false;
        }
    }

    @Override
    public void despawnPod(Object pod) {
        Handles h = handlesOrNull();
        if (h == null || pod == null) {
            return;
        }
        try {
            Object engine = engineOrNull(h);
            if (engine != null) {
                h.removeObject.invoke(engine, pod);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Removal failed ⇒ the pod may still be in the world — and post-lift honestly
            // still on the wire (the L4 S1 world-mirrors-wire law). Surface LOUD.
            System.out.println("npeer-serve pod_despawn_failed err=" + e);
        }
    }
}
