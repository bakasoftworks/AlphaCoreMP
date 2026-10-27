package cc.starsector.server.bridge;

import cc.starsector.server.bridge.RealEngineDemoRoster.RealEngineDemoRosterException;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.platform.AllSkillsAttach;
import cc.starsector.server.platform.OfficerAttach;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link RealEngineDemoRoster}'s SEAT-REBIND collaborator (split out by adjudication; the
 * deferred-refactors {@code -d} RealEngineDemoRoster row is the spec of record): every verb that
 * re-points or force-releases a seat's FIXED wire id on the LIVE engine — station-module
 * mount/dismount, the command-transfer re-key
 * and the two kill verbs ({@code /suicide} W7b + wave-skip).
 * Extracted as ONE bundle because the verbs share the same mutations (the
 * roster's static live/stable seat maps), the same engine idioms (AI yield/restore, the F3
 * id-steal law, fail-closed reflective resolution) and the same gate pair (fire-family chain +
 * the displacement control).
 *
 * <p><b>Monitor law — every verb here locks {@code RealEngineDemoRoster.class}, never this
 * class.</b> The roster's remaining map mutators ({@code midDeploySeat}, {@code vacateSeatHull},
 * {@code resetForRedeploy}, the deploy itself) are {@code static synchronized} THERE; a verb
 * here locking {@code SeatRebind.class} would silently break their mutual exclusion. Shared
 * state rides package-private access on the roster ({@code liveShipByWireId},
 * {@code seatStableIdByWireId}, the deployed/descriptor/station gates, the seat-slot/name/CR
 * helpers and the PINNED identity constants — one pinned block, never duplicated): the maps stay
 * roster-owned; this class is behavior, not state.
 */
public final class SeatRebind {

    private SeatRebind() {
    }

    /**
     * The operator wave-skip's kill half — one lethal engine-resolved
     * bypass-shields hit ({@code 1e6} HIGH_EXPLOSIVE at the hull's own location) on EVERY live
     * hull owned by {@code side} (the wave side), through the engine's OWN {@code applyDamage},
     * so each death rides the NORMAL death/wire machinery — hulk mint, growth-seam
     * removal edge, deaths timeline — and the wave-clear trigger sees a genuinely cleared field
     * at the next capture. Enumeration mirrors the snapshot source's allegiance fold EXACTLY:
     * the repository {@code Ship} list minus non-roster fx mints
     * ({@code EngineBornGrowthSeam.isNonRosterMintId} — the {@code rosterShipList()} law),
     * filtered by {@code getOwner()==side && isAlive()} — so the killed set IS the counted set.
     * Damage source = the hull itself (self-attribution; a null source risks an attribution
     * NPE deeper in the kill path). Returns the number of hulls hit.
     *
     * <p>Headless cosmetic-FX NPEs thrown AFTER the damage lands are swallowed PER HIT (the
     * probe/deck-damage idiom, {@code SwallowCosmeticFxAuthoritativeDamageSink
     * .isHeadlessCosmeticFxNpe} + the {@code com.fs.graphics} NPE class); anything else THROWS
     * fail-closed — an engine damage-apply failure is never per-session (the wave-spawn law).
     */
    public static int forceKillSideHulls(int side) {
        synchronized (RealEngineDemoRoster.class) {
            if (side != 0 && side != 1) {
                throw new RealEngineDemoRosterException(
                        "forceKillSideHulls side must be 0 or 1, got " + side);
            }
            ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
            try {
                Class<?> engineClass = Class.forName(RealEngineDemoRoster.ENGINE_CLASS, true, loader);
                Object engine = engineClass.getMethod("getInstance").invoke(null);
                if (engine == null) {
                    throw new RealEngineDemoRosterException(
                            "CombatEngine.getInstance() returned null at forceKillSideHulls(" + side + ")");
                }
                Class<?> shipClass = Class.forName(RealEngineDemoRoster.SHIP_CLASS, false, loader);
                Object repo = engineClass.getMethod("getObjects").invoke(engine);
                if (repo == null) {
                    throw new RealEngineDemoRosterException(
                            "CombatEngine.getObjects() null at forceKillSideHulls(" + side + ")");
                }
                List<?> ships = (List<?>) repo.getClass().getMethod("getList", Class.class)
                        .invoke(repo, shipClass);
                if (ships == null) {
                    throw new RealEngineDemoRosterException(
                            "repository getList(Ship) null at forceKillSideHulls(" + side + ")");
                }
                Method getId = shipClass.getMethod("getId");
                Method getOwner = shipClass.getMethod("getOwner");
                Method isAlive = shipClass.getMethod("isAlive");
                Method getLocation = shipClass.getMethod("getLocation");
                Class<?> entityApi = Class.forName(RealEngineDemoRoster.COMBAT_ENTITY_API, false, loader);
                Class<?> damageTypeClass = Class.forName(RealEngineDemoRoster.DAMAGE_TYPE_API, false, loader);
                Class<?> vec2Class = Class.forName(RealEngineDemoRoster.LWJGL_VECTOR2F_CLASS, false, loader);
                Method applyDamage = engineClass.getMethod("applyDamage",
                        Object.class, entityApi, vec2Class, float.class, damageTypeClass,
                        float.class, boolean.class, boolean.class, Object.class, boolean.class);
                Object highExplosive = damageTypeClass.getField("HIGH_EXPLOSIVE").get(null);
                java.lang.reflect.Field vecX = vec2Class.getField("x");
                java.lang.reflect.Field vecY = vec2Class.getField("y");
                Constructor<?> vec2Ctor = vec2Class.getConstructor(float.class, float.class);
                // A PLAYER on the swept side (/switchteams) is not a wave
                // ship — its hull and parts are spared, the same identity set the wave-clear
                // count skips, so "the killed set IS the counted set" still holds.
                java.util.Set<Object> seatOwned = SeatHullParts.seatOwnedOnSide(side);
                int killed = 0;
                for (Object ship : ships) {
                    if (ship == null) {
                        continue;
                    }
                    if (EngineBornGrowthSeam.isNonRosterMintId(getId.invoke(ship))) {
                        continue; // fx/presentation mint — the allegiance fold never counts it
                    }
                    if (seatOwned.contains(ship)) {
                        continue;
                    }
                    Object owner = getOwner.invoke(ship);
                    Object alive = isAlive.invoke(ship);
                    if (!(owner instanceof Number) || ((Number) owner).intValue() != side
                            || !Boolean.TRUE.equals(alive)) {
                        continue;
                    }
                    forceKillHull(engine, applyDamage, highExplosive, vecX, vecY, vec2Ctor,
                            getLocation, ship, false, "forceKillSideHulls(" + side + ")");
                    killed++;
                }
                return killed;
            } catch (RealEngineDemoRosterException failure) {
                throw failure;
            } catch (Throwable resolveFailure) {
                Throwable cause = RealEngineDemoRoster.unwrap(resolveFailure);
                throw new RealEngineDemoRosterException(
                        "forceKillSideHulls(" + side + ") failed: " + cause.getClass().getName()
                                + ": " + cause.getMessage(), cause);
            }
        }
    }

    /**
     * The operator wave-skip's CORPSE half — remove EVERY hulk that fought
     * for {@code side} from play through the engine's own {@code removeEntity} (the
     * DeployWindowSweep recipe), fired {@code WaveSchedule.SKIP_HULK_SWEEP_DELAY_SEC} after the
     * kill half so a dead Omega parent's {@code ShardSpawner} never mints children — its
     * {@code advanceInCombat} gates on {@code isHulk() && isEntityInPlay()}
     * and the children only arrive 2–3 s post-death. The deferral is
     * load-bearing: the death fan (EDSP absence classification, growth-seam removal truth, the
     * fracture detector's family sighting) needs the corpse PRESENT for a few captures, and the
     * detector's family-vanish REMOVE cascade — its designed vanish path — then removes the
     * CLIENT corpses when this sweep fires, so both sides stay parity-clean.
     *
     * <p>Enumeration mirrors {@link #forceKillSideHulls} with aliveness inverted: same
     * repository list, same non-roster fx-mint skip, then {@code getOriginalOwner() == side}
     * (death flips {@code getOwner()} to neutral — the kill half's owner read would match
     * nothing) and {@code !isAlive()}, minus a PLAYER's wreck ({@link #waveSkipSweeps}). Sweeps
     * EVERY other wave-side corpse, not just the kill half's
     * victims: pre-skip battle corpses carry the same pending-children hazard and the verb's
     * meaning is "clear the field". Returns the number removed; THROWS fail-closed like the
     * kill half (an engine removal failure is never per-session).
     */
    public static int removeSideHulks(int side) {
        synchronized (RealEngineDemoRoster.class) {
            if (side != 0 && side != 1) {
                throw new RealEngineDemoRosterException(
                        "removeSideHulks side must be 0 or 1, got " + side);
            }
            ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
            try {
                Class<?> engineClass = Class.forName(RealEngineDemoRoster.ENGINE_CLASS, true, loader);
                Object engine = engineClass.getMethod("getInstance").invoke(null);
                if (engine == null) {
                    throw new RealEngineDemoRosterException(
                            "CombatEngine.getInstance() returned null at removeSideHulks(" + side + ")");
                }
                Class<?> shipClass = Class.forName(RealEngineDemoRoster.SHIP_CLASS, false, loader);
                Object repo = engineClass.getMethod("getObjects").invoke(engine);
                if (repo == null) {
                    throw new RealEngineDemoRosterException(
                            "CombatEngine.getObjects() null at removeSideHulks(" + side + ")");
                }
                List<?> ships = (List<?>) repo.getClass().getMethod("getList", Class.class)
                        .invoke(repo, shipClass);
                if (ships == null) {
                    throw new RealEngineDemoRosterException(
                            "repository getList(Ship) null at removeSideHulks(" + side + ")");
                }
                Method getId = shipClass.getMethod("getId");
                Method getOriginalOwner = shipClass.getMethod("getOriginalOwner");
                Method isAlive = shipClass.getMethod("isAlive");
                Class<?> entityApi = Class.forName(RealEngineDemoRoster.COMBAT_ENTITY_API, false, loader);
                Method removeEntity = engineClass.getMethod("removeEntity", entityApi);
                int removed = 0;
                // Snapshot the list first: removeEntity may mutate the repository in place
                // (the DeployWindowSweep iteration law).
                for (Object ship : new java.util.ArrayList<Object>(ships)) {
                    if (ship == null) {
                        continue;
                    }
                    Object id = getId.invoke(ship);
                    if (EngineBornGrowthSeam.isNonRosterMintId(id)) {
                        continue; // fx/presentation mint — never roster state
                    }
                    if (!waveSkipSweeps(side, id, getOriginalOwner.invoke(ship),
                            isAlive.invoke(ship))) {
                        continue;
                    }
                    removeEntity.invoke(engine, ship);
                    removed++;
                }
                return removed;
            } catch (RealEngineDemoRosterException failure) {
                throw failure;
            } catch (Throwable resolveFailure) {
                Throwable cause = RealEngineDemoRoster.unwrap(resolveFailure);
                throw new RealEngineDemoRosterException(
                        "removeSideHulks(" + side + ") failed: " + cause.getClass().getName()
                                + ": " + cause.getMessage(), cause);
            }
        }
    }

    /**
     *
     * The wave-skip corpse sweep's pick for ONE hull: a corpse ({@code isAlive()} not true)
     * that fought for {@code side} ({@code getOriginalOwner()}), unless it is a PLAYER's hull —
     * its trimmed stable id is a seat's CURRENT hull ({@code seatStableIdByWireId}) or ANY hull
     * a seat carried this round ({@link RealEngineDemoRoster#seatCarriedThisRound}: the t=0
     * seat hulls, every rebind target, every fly-in). So the hull a north seat left by a
     * transfer (owner 1, no seat holds it now) and an earlier life's wreck stay on the field
     * as corpses; only a corpse no seat flew this round sweeps. The serve calls it under the
     * roster monitor.
     */
    public static boolean waveSkipSweeps(int side, Object stableId, Object originalOwner,
            Object alive) {
        if (!(originalOwner instanceof Number) || ((Number) originalOwner).intValue() != side
                || Boolean.TRUE.equals(alive)) {
            return false;
        }
        String stable = String.valueOf(stableId).trim();
        return stableId == null || !(RealEngineDemoRoster.seatStableIdByWireId.containsValue(stable)
                || RealEngineDemoRoster.seatCarriedThisRound(stable));
    }

    /**
     * The cross-team transfer refusal, worded exactly as before teams existed (the seat reads
     * it in chat; the serve drain's early gate and the commit-time check share it). */
    public static final String DENY_NOT_FRIENDLY = "transfer aborted - target is not friendly";

    /**
     *
     * The team a seat fights for, read off the body it sits in: the LIVE owner when it reads
     * 0 or 1; a wreck's live owner reads neutral (death flips it), so then its ORIGINAL owner;
     * else {@code fallbackSide}. A non-Number read counts as unreadable. Range 0..1, or the
     * fallback.
     */
    public static int seatSideOf(Object liveOwner, Object originalOwner, int fallbackSide) {
        if (liveOwner instanceof Number) {
            int live = ((Number) liveOwner).intValue();
            if (live == 0 || live == 1) {
                return live;
            }
        }
        if (originalOwner instanceof Number) {
            int original = ((Number) originalOwner).intValue();
            if (original == 0 || original == 1) {
                return original;
            }
        }
        return fallbackSide;
    }

    /**
     * The team law of a transfer: a target whose live owner is not the seat's team is refused
     * with {@link #DENY_NOT_FRIENDLY}; null = friendly. Side 0 keeps the pre-team law
     * ({@code owner != 0} denies). */
    public static String transferSideDenyOrNull(int targetOwner, int seatSide) {
        return targetOwner == seatSide ? null : DENY_NOT_FRIENDLY;
    }

    /** Logged once per process: a seat body whose owner read threw (the side falls back). */
    private static boolean seatSideReadFailLogged;

    /**
     *
     * The team of seat {@code seatId} NOW — the key that picks the transfer table it may
     * name: its live-mapped body ({@link #seatSideOf}: the hull, pod or module it sits in);
     * a dead seat's lingering wreck by its original owner (the commit reads the same body);
     * else its slot side; -1 when nothing reads (no deployed round). Census cadence only
     * (a map read and two reflective reads; a dead seat adds one ship-list scan), never
     * per tick.
     */
    public static int seatLiveSideOrNeg(int seatId, long seatWireId) {
        synchronized (RealEngineDemoRoster.class) {
            Object body = RealEngineDemoRoster.liveShipByWireId.get(seatWireId);
            if (body == null && RealEngineDemoRoster.deployed) {
                body = wreckSource(seatWireId).hulk();
            }
            if (body != null) {
                try {
                    int side = seatSideOf(body.getClass().getMethod("getOwner").invoke(body),
                            body.getClass().getMethod("getOriginalOwner").invoke(body), -1);
                    if (side >= 0) {
                        return side;
                    }
                } catch (ReflectiveOperationException | RuntimeException unreadable) {
                    if (!seatSideReadFailLogged) {
                        seatSideReadFailLogged = true;
                        System.out.println("npeer-serve seat_side_read_error seat=" + seatId
                                + " err=" + unreadable.getClass().getSimpleName());
                    }
                }
            }
            return RealEngineDemoRoster.seatSideOrNeg(seatId);
        }
    }

    /**
     * The ONE per-hull kill body both kill verbs share —
     * the engine's own {@code applyDamage}, self-attributed, bypass-shields, with the
     * headless cosmetic-FX NPE swallow. Extracted verbatim from the {@link #forceKillSideHulls}
     * loop so {@code /suicide} and wave-skip ride ONE tested path; {@code site} names the caller
     * in the fail-closed throw.
     *
     * <p><b>Dive suppression, split per verb.</b> PhaseAnchor's
     * listener intercepts any would-be lethal hull damage — this verb's 1e6 HE included — while
     * {@link RealEngineDemoRoster#PHASE_ANCHOR_CAN_DIVE_KEY} is unconsumed. With
     * {@code allowDive=false} the key is planted for the duration of the {@code applyDamage} so
     * the intercept sees {@code canDive=false} and the verb always runs the proven normal death
     * cycle (hulk mint → roster drop → HULL_DEATH → refield) — wave-skip keeps this: a stalled
     * dive would wedge round-end on an immortal phased ghost. With {@code allowDive=true}
     * ({@code /suicide}) the sentinel is NOT planted, so an eligible anchor hull's emergency
     * dive is reachable on the player's own verb — the serve loop's {@code PhaseDiveServeProbe}
     * then detects the start, neutralizes the headless floaty stall hazard, and classifies the
     * departure (F4b/F4c). The {@code finally} removes the key IFF this call put it —
     * exact-state restore, so REAL combat dives keep full vanilla semantics including the
     * one-per-battle one-shot; a leaked sentinel would silently disable a vanilla mechanic.
     */
    private static void forceKillHull(Object engine, Method applyDamage, Object highExplosive,
            java.lang.reflect.Field vecX, java.lang.reflect.Field vecY, Constructor<?> vec2Ctor,
            Method getLocation, Object ship, boolean allowDive, String site) throws Exception {
        Object loc = getLocation.invoke(ship);
        Object point = loc == null ? vec2Ctor.newInstance(0f, 0f)
                : vec2Ctor.newInstance(vecX.getFloat(loc), vecY.getFloat(loc));
        @SuppressWarnings("unchecked")
        Map<Object, Object> customData = (Map<Object, Object>) engine.getClass()
                .getMethod("getCustomData").invoke(engine);
        if (customData == null) {
            throw new RealEngineDemoRosterException(
                    site + " CombatEngine.getCustomData() returned null — cannot fence the"
                            + " PhaseAnchor dive intercept for a kill verb");
        }
        boolean plantedDiveSentinel = false;
        if (!allowDive && !customData.containsKey(RealEngineDemoRoster.PHASE_ANCHOR_CAN_DIVE_KEY)) {
            customData.put(RealEngineDemoRoster.PHASE_ANCHOR_CAN_DIVE_KEY, Boolean.TRUE);
            plantedDiveSentinel = true;
        }
        try {
            applyDamage.invoke(engine, ship, ship, point, 1_000_000f, highExplosive,
                    0f, Boolean.TRUE, Boolean.FALSE, null, Boolean.FALSE);
        } catch (Throwable damageThrow) {
            Throwable cause = RealEngineDemoRoster.unwrap(damageThrow);
            boolean cosmetic = cc.starsector.server.runtime
                    .SwallowCosmeticFxAuthoritativeDamageSink.isHeadlessCosmeticFxNpe(cause)
                    || (cause instanceof NullPointerException
                            && java.util.Arrays.stream(cause.getStackTrace()).anyMatch(
                                    el -> el.getClassName().startsWith("com.fs.graphics")));
            if (!cosmetic) {
                throw new RealEngineDemoRosterException(
                        site + " applyDamage threw a"
                                + " non-cosmetic boundary: " + cause.getClass().getName()
                                + ": " + cause.getMessage(), cause);
            }
        } finally {
            if (plantedDiveSentinel) {
                customData.remove(RealEngineDemoRoster.PHASE_ANCHOR_CAN_DIVE_KEY);
            }
        }
    }

    /**
     * The {@code /suicide} kill half: engine-native
     * self-destruct for ONE live seat hull — {@link #forceKillHull} scoped to a single wire id
     * via the round's live map, with {@code allowDive=true}: the player's
     * own verb leaves the PhaseAnchor emergency dive reachable for an eligible anchor hull.
     * Returns false (no-op, never an error) when the id is not live-mapped or the hull is no
     * longer alive — the seat is already dead/unmounted and the death machinery owes nothing.
     * Sim-thread only (the serve loop's drain site; applyDamage is never reader-thread work). A
     * THROW is fatal like the side-kill — an engine damage-apply failure is never per-session.
     */
    public static boolean forceKillLiveShipForWireId(long wireId) {
        synchronized (RealEngineDemoRoster.class) {
            Object ship = RealEngineDemoRoster.liveShipByWireId.get(wireId);
            if (ship == null) {
                return false;
            }
            ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
            try {
                Class<?> engineClass = Class.forName(RealEngineDemoRoster.ENGINE_CLASS, true, loader);
                Object engine = engineClass.getMethod("getInstance").invoke(null);
                if (engine == null) {
                    throw new RealEngineDemoRosterException(
                            "CombatEngine.getInstance() returned null at forceKillLiveShipForWireId("
                                    + wireId + ")");
                }
                Class<?> shipClass = Class.forName(RealEngineDemoRoster.SHIP_CLASS, false, loader);
                if (!Boolean.TRUE.equals(shipClass.getMethod("isAlive").invoke(ship))) {
                    return false;
                }
                Method getLocation = shipClass.getMethod("getLocation");
                Class<?> entityApi = Class.forName(RealEngineDemoRoster.COMBAT_ENTITY_API, false, loader);
                Class<?> damageTypeClass = Class.forName(RealEngineDemoRoster.DAMAGE_TYPE_API, false, loader);
                Class<?> vec2Class = Class.forName(RealEngineDemoRoster.LWJGL_VECTOR2F_CLASS, false, loader);
                Method applyDamage = engineClass.getMethod("applyDamage",
                        Object.class, entityApi, vec2Class, float.class, damageTypeClass,
                        float.class, boolean.class, boolean.class, Object.class, boolean.class);
                Object highExplosive = damageTypeClass.getField("HIGH_EXPLOSIVE").get(null);
                java.lang.reflect.Field vecX = vec2Class.getField("x");
                java.lang.reflect.Field vecY = vec2Class.getField("y");
                Constructor<?> vec2Ctor = vec2Class.getConstructor(float.class, float.class);
                forceKillHull(engine, applyDamage, highExplosive, vecX, vecY, vec2Ctor,
                        getLocation, ship, true, "forceKillLiveShipForWireId(" + wireId + ")");
                return true;
            } catch (RealEngineDemoRosterException failure) {
                throw failure;
            } catch (Throwable resolveFailure) {
                Throwable cause = RealEngineDemoRoster.unwrap(resolveFailure);
                throw new RealEngineDemoRosterException(
                        "forceKillLiveShipForWireId(" + wireId + ") failed: "
                                + cause.getClass().getName() + ": " + cause.getMessage(), cause);
            }
        }
    }

    /**
     * {@link #forceKillHull} for ONE resolved engine ship that is NOT a
     * seat body — the podtest driver's target / flight-source kills ({@code liveShipByWireId}
     * holds seats only, so {@link #forceKillLiveShipForWireId} cannot reach a spawned target).
     * {@code allowDive=false} (the wave-skip posture: a scripted kill must always run the normal
     * death cycle, never a phase dive). Returns false when the ship is null or already dead; a
     * THROW is fatal like the siblings. Sim-thread only.
     */
    public static boolean forceKillShip(Object ship, String site) {
        if (ship == null) {
            return false;
        }
        synchronized (RealEngineDemoRoster.class) {
            ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
            try {
                Class<?> engineClass = Class.forName(RealEngineDemoRoster.ENGINE_CLASS, true, loader);
                Object engine = engineClass.getMethod("getInstance").invoke(null);
                if (engine == null) {
                    throw new RealEngineDemoRosterException(
                            "CombatEngine.getInstance() returned null at forceKillShip(" + site + ")");
                }
                Class<?> shipClass = Class.forName(RealEngineDemoRoster.SHIP_CLASS, false, loader);
                if (!shipClass.isInstance(ship)
                        || !Boolean.TRUE.equals(shipClass.getMethod("isAlive").invoke(ship))) {
                    return false;
                }
                Method getLocation = shipClass.getMethod("getLocation");
                Class<?> entityApi = Class.forName(RealEngineDemoRoster.COMBAT_ENTITY_API, false, loader);
                Class<?> damageTypeClass = Class.forName(RealEngineDemoRoster.DAMAGE_TYPE_API, false, loader);
                Class<?> vec2Class = Class.forName(RealEngineDemoRoster.LWJGL_VECTOR2F_CLASS, false, loader);
                Method applyDamage = engineClass.getMethod("applyDamage",
                        Object.class, entityApi, vec2Class, float.class, damageTypeClass,
                        float.class, boolean.class, boolean.class, Object.class, boolean.class);
                Object highExplosive = damageTypeClass.getField("HIGH_EXPLOSIVE").get(null);
                java.lang.reflect.Field vecX = vec2Class.getField("x");
                java.lang.reflect.Field vecY = vec2Class.getField("y");
                Constructor<?> vec2Ctor = vec2Class.getConstructor(float.class, float.class);
                forceKillHull(engine, applyDamage, highExplosive, vecX, vecY, vec2Ctor,
                        getLocation, ship, false, "forceKillShip(" + site + ")");
                return true;
            } catch (RealEngineDemoRosterException failure) {
                throw failure;
            } catch (Throwable resolveFailure) {
                Throwable cause = RealEngineDemoRoster.unwrap(resolveFailure);
                throw new RealEngineDemoRosterException(
                        "forceKillShip(" + site + ") failed: "
                                + cause.getClass().getName() + ": " + cause.getMessage(), cause);
            }
        }
    }

    /**
     * One module mount's deploy-recorded
     * identity — the {@link RealEngineDemoRoster.SeatDeployResult} twin for MOUNT-EXISTING.
     * {@code stableId} is returned DIRECTLY (no mint happened, so the Main fly-in hook's
     * map-diff dance has nothing to diff); {@code variantId} is the module's OWN variant (the
     * RAPP re-point row the serve loop fans), {@code descriptorVariantId}/{@code side}/
     * {@code shipName} are the seat's descriptor truth exactly as a fly-in reports them.
     */
    public record ModuleMountResult(String stableId, String variantId,
            String descriptorVariantId, int side, String shipName, int crPerMille) {
    }

    /**
     * W2b E2: mount a seat onto a LIVE station weapon module —
     * MOUNT-EXISTING, the fly-in drain's alternative to minting a hull. Resolves the module by
     * engine slot id off the round's station base ({@code getChildModulesCopy}, the deploy
     * readback's own census idiom), yields its AI ({@code setShipAI(null)} — sticky; NEVER the
     * base's {@code isHoldFire}, which OR-gates ALL modules silent), and
     * re-points the seat's FIXED wire id at the module in the live + stable maps (the
     * {@code midDeploySeat} re-point idiom verbatim). {@code deployedShipCount} is deliberately
     * UNTOUCHED — nothing minted. The caller (serve loop) owns the registry re-key
     * ({@code releaseForRebind} + {@code internReservedMount}), the fire-lane resets, the
     * arbiter note and the MMNT/RAPP fan.
     *
     * <p>Fail-closed validations, in order: roster deployed + station fielded + descriptor
     * active; seat wire id NOT live-mapped (the midDeploySeat guard mirror — a mounted seat
     * vacates first); slot resolves to a live child module; parented to THIS round's base;
     * engine-alive; variant id equals the census expectation; non-blank stable id; module not
     * already a live-map VALUE (the F3 id-steal law — one body must never sit under two wire
     * ids).
     *
     * <p>The CR rider is CLOSED by static RE: the fleet-member tracker
     * chain is callable but UNSEEDED for modules (reads ~0.5 default) — the CR read below uses
     * the module Ship's own {@code getCurrentCR()}, which the mint seeds from
     * the station's live CR.
     */
    public static ModuleMountResult mountStationModule(int seatId, long seatWireId,
            String slotId, String expectedModuleVariantId) {
        synchronized (RealEngineDemoRoster.class) {
            if (!RealEngineDemoRoster.deployed || RealEngineDemoRoster.stationBaseShip == null) {
                throw new RealEngineDemoRosterException(
                        "mountStationModule(seat=" + seatId + ", slot=" + slotId + ") without a"
                                + " deployed station round (deployed=" + RealEngineDemoRoster.deployed
                                + ", base=" + (RealEngineDemoRoster.stationBaseShip != null)
                                + ") — module seats exist only on a fortress serve");
            }
            if (RealEngineDemoRoster.deployedDescriptor == null) {
                throw new RealEngineDemoRosterException(
                        "mountStationModule(seat=" + seatId + ") without an active descriptor —"
                                + " module mounts exist only on the descriptor-driven npeer path");
            }
            if (RealEngineDemoRoster.liveShipByWireId.containsKey(seatWireId)) {
                throw new RealEngineDemoRosterException(
                        "mountStationModule(seat=" + seatId + "): wire id " + seatWireId
                                + " is already live-mapped — a mounted seat must vacate its hull"
                                + " (mount-loss / reset) before a module mount");
            }
            DescriptorLayout.SeatSlot slot = DescriptorLayout.seatSlot(seatId);
            ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
            try {
                Class<?> engineClass = Class.forName(RealEngineDemoRoster.ENGINE_CLASS, true, loader);
                Object engine = engineClass.getMethod("getInstance").invoke(null);
                if (engine == null) {
                    throw new RealEngineDemoRosterException(
                            "CombatEngine.getInstance() returned null at mountStationModule(seat="
                                    + seatId + ")");
                }
                Class<?> shipClass = Class.forName(RealEngineDemoRoster.SHIP_CLASS, false, loader);
                Object rawChildren = shipClass.getMethod("getChildModulesCopy")
                        .invoke(RealEngineDemoRoster.stationBaseShip);
                if (!(rawChildren instanceof List)) {
                    throw new RealEngineDemoRosterException(
                            "mountStationModule(seat=" + seatId + "): base getChildModulesCopy()"
                                    + " returned " + (rawChildren == null ? "null"
                                            : rawChildren.getClass().getName()));
                }
                Method shipGetStationSlot = shipClass.getMethod("getStationSlot");
                Object module = null;
                for (Object child : (List<?>) rawChildren) {
                    if (child == null || !shipClass.isInstance(child)) {
                        continue;
                    }
                    Object childSlot = shipGetStationSlot.invoke(child);
                    if (childSlot == null) {
                        continue;
                    }
                    Object childSlotId = childSlot.getClass().getMethod("getId").invoke(childSlot);
                    if (slotId.equals(childSlotId)) {
                        module = child;
                        break;
                    }
                }
                if (module == null) {
                    throw new RealEngineDemoRosterException(
                            "mountStationModule(seat=" + seatId + "): no live child module in slot '"
                                    + slotId + "' — the module died/detached since the claim (the"
                                    + " drain's stale re-check should have caught this)");
                }
                if (shipClass.getMethod("getParentStation").invoke(module)
                        != RealEngineDemoRoster.stationBaseShip) {
                    throw new RealEngineDemoRosterException(
                            "mountStationModule(seat=" + seatId + "): slot '" + slotId + "' module is"
                                    + " not parented to this round's station base");
                }
                Class<?> shipApiClass = Class.forName(RealEngineDemoRoster.SHIP_API, false, loader);
                Object alive = engineClass.getMethod("isShipAlive", shipApiClass)
                        .invoke(engine, module);
                if (!Boolean.TRUE.equals(alive)) {
                    throw new RealEngineDemoRosterException(
                            "mountStationModule(seat=" + seatId + "): slot '" + slotId
                                    + "' module is not engine-alive");
                }
                // Variant check via the spec's own public getHullVariantId (the OfficerAttach
                // variant-object idiom) — a census/engine disagreement must refuse the mount, not
                // seat the player in an unexpected body.
                Object variant = shipClass.getMethod("getVariant").invoke(module);
                Object rawVariantId = variant == null
                        ? null : variant.getClass().getMethod("getHullVariantId").invoke(variant);
                String moduleVariantId = rawVariantId instanceof String ? (String) rawVariantId : "";
                if (!expectedModuleVariantId.equals(moduleVariantId)) {
                    throw new RealEngineDemoRosterException(
                            "mountStationModule(seat=" + seatId + "): slot '" + slotId + "' carries"
                                    + " variant '" + moduleVariantId + "' but the census expects '"
                                    + expectedModuleVariantId + "' — refusing a mis-keyed mount");
                }
                Object idValue = shipClass.getMethod("getId").invoke(module);
                String stable = (idValue instanceof String) ? ((String) idValue).trim() : "";
                if (stable.isEmpty()) {
                    throw new RealEngineDemoRosterException(
                            "mountStationModule(seat=" + seatId + "): module stable-id read failed"
                                    + " (ShipAPI.getId() returned "
                                    + (idValue == null ? "null" : idValue.getClass().getName()) + ")");
                }
                if (RealEngineDemoRoster.liveShipByWireId.containsValue(module)) {
                    throw new RealEngineDemoRosterException(
                            "mountStationModule(seat=" + seatId + "): slot '" + slotId + "' module is"
                                    + " already live-mapped under another wire id (F3 id-steal law —"
                                    + " one body must never carry two wire ids)");
                }
                // YIELD: null the module's own per-module AI (the maybeSuppressShipAi verb, sticky)
                // so the seat's fire lanes own the weapons. setShipAI's parameter type is engine-internal —
                // find the 1-arg method by name (the probe findOneArg idiom).
                Method setShipAI = null;
                for (Method candidate : shipClass.getMethods()) {
                    if ("setShipAI".equals(candidate.getName())
                            && candidate.getParameterCount() == 1) {
                        setShipAI = candidate;
                        break;
                    }
                }
                if (setShipAI == null) {
                    throw new RealEngineDemoRosterException(
                            "mountStationModule(seat=" + seatId + "): no 1-arg setShipAI on Ship —"
                                    + " the AI yield verb moved (re-verify the AI-verb anchors)");
                }
                // The mount twin of the re-key verbs' captain
                // move ({@code transferSeatToStationModule}) — the seat's captain follows it onto
                // the module it mounts, on every end (the kit's remote twin = ModuleMountLane
                // lane=remote-mount; the own end = OwnMountLifecycleLane lane=mount). Ship-only (a
                // module has no deployed member), resolved BEFORE the first mutation (the yield
                // below), never the station base (its setCaptain fans to every child module).
                TransferCaptain captain = resolveTransferCaptain(loader, shipClass, module, false,
                        moduleVariantId, slot.descriptorVariantId(),
                        DescriptorLayout.seatShipName(seatId));
                setShipAI.invoke(module, new Object[] {null});
                String captainPath = recaptainTransferTarget(captain, module);
                // The midDeploySeat re-point idiom verbatim: stable map re-points, live map mounts.
                Map<Long, String> stableByWire =
                        new LinkedHashMap<>(RealEngineDemoRoster.seatStableIdByWireId);
                stableByWire.put(seatWireId, stable);
                RealEngineDemoRoster.seatStableIdByWireId = Collections.unmodifiableMap(stableByWire);
                RealEngineDemoRoster.seatStableIdsThisRound.add(stable);
                RealEngineDemoRoster.liveShipByWireId.put(seatWireId, module);
                // CR for the RAPP row (per-mille) — the module Ship's OWN currentCR. Static-RE close
                // of the rider: the module mint seeds the station's live CR
                // onto the module SHIP (applyCRToShip) but NEVER onto
                // its fleet member's repair tracker, whose cr stays the 0.5F constructor default
                // — the tracker chain reads plausible-but-WRONG for modules.
                float cr = ((Number) shipClass.getMethod("getCurrentCR").invoke(module)).floatValue();
                System.out.println("real-engine-demo-roster mountStationModule seat=" + seatId
                        + " wireId=" + seatWireId + " slot=" + slotId
                        + " variant=" + moduleVariantId + " stableId=" + stable
                        + " captain=" + captainPath
                        + " (AI yielded; live map re-pointed — no mint)");
                return new ModuleMountResult(stable, moduleVariantId, slot.descriptorVariantId(),
                        slot.side(), DescriptorLayout.seatShipName(seatId),
                        DescriptorLayout.crPerMille(cr));
            } catch (RealEngineDemoRosterException e) {
                throw e;
            } catch (Throwable t) {
                Throwable cause = RealEngineDemoRoster.unwrap(t);
                throw new RealEngineDemoRosterException(
                        "mountStationModule(seat=" + seatId + ", slot=" + slotId + ") failed: "
                                + cause.getClass().getName() + ": "
                                + (cause.getMessage() == null ? "" : cause.getMessage()),
                        cause);
            }
        }
    }

    /**
     * W2b E2: voluntary dismount — the mount's reverse. Restores the
     * module's engine AI via {@code setDefaultAI(getFleetMember())} (1-arg —
     * ModManager re-picks the station-module AI; NEVER {@code resetDefaultAI()}, which picks
     * BasicShipAI) and vacates the seat's live-map entry. {@code seatStableIdByWireId} is
     * deliberately untouched (the {@link RealEngineDemoRoster#vacateSeatHull} precedent: the
     * stable map is the round's identity record until the next mount re-points it). Returns the
     * module's stable id — the caller's registry re-key ({@code releaseForRebind} + fresh
     * {@code networkIdFor}) and MMNT fan key on it. Fail-closed: an unmounted seat THROWS (the
     * dismount drain fires exactly once per deposit by construction).
     */
    public static String dismountStationModule(int seatId, long seatWireId) {
        synchronized (RealEngineDemoRoster.class) {
            Object module = RealEngineDemoRoster.liveShipByWireId.get(seatWireId);
            if (module == null) {
                throw new RealEngineDemoRosterException(
                        "dismountStationModule(seat=" + seatId + ", wireId=" + seatWireId + "): wire"
                                + " id not live-mapped — a dismount must name a mounted seat");
            }
            ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
            try {
                Class<?> shipClass = Class.forName(RealEngineDemoRoster.SHIP_CLASS, false, loader);
                Object idValue = shipClass.getMethod("getId").invoke(module);
                String stable = (idValue instanceof String) ? ((String) idValue).trim() : "";
                if (stable.isEmpty()) {
                    throw new RealEngineDemoRosterException(
                            "dismountStationModule(seat=" + seatId + "): module stable-id read"
                                    + " failed (ShipAPI.getId() returned "
                                    + (idValue == null ? "null" : idValue.getClass().getName())
                                    + ")");
                }
                Object fleetMember = shipClass.getMethod("getFleetMember").invoke(module);
                Method setDefaultAI = null;
                for (Method candidate : shipClass.getMethods()) {
                    if ("setDefaultAI".equals(candidate.getName())
                            && candidate.getParameterCount() == 1) {
                        setDefaultAI = candidate;
                        break;
                    }
                }
                if (setDefaultAI == null) {
                    throw new RealEngineDemoRosterException(
                            "dismountStationModule(seat=" + seatId + "): no 1-arg setDefaultAI on"
                                    + " Ship — the AI restore verb moved (re-verify against the installed"
                                    + " engine build)");
                }
                setDefaultAI.invoke(module, fleetMember);
                // The dismount's captain revert — the module the seat
                // leaves gets its own original back (Ship-only, the transfer-off-a-module
                // shape: AI restored first, then the revert, then the map), so no vacated module
                // keeps a seat-grade captain under its restored AI; the kit's twin = both
                // ModuleMountLane.applyDismount arms. A dead module keeps what it carries.
                String sourceCaptain = vacatedCaptain(shipClass, module, false, false, false, null);
                RealEngineDemoRoster.liveShipByWireId.remove(seatWireId);
                System.out.println("real-engine-demo-roster dismountStationModule seat=" + seatId
                        + " wireId=" + seatWireId + " stableId=" + stable
                        + " sourceCaptain=" + sourceCaptain
                        + " (AI restored; live map vacated — module lives on, unpiloted)");
                return stable;
            } catch (RealEngineDemoRosterException e) {
                throw e;
            } catch (Throwable t) {
                Throwable cause = RealEngineDemoRoster.unwrap(t);
                throw new RealEngineDemoRosterException(
                        "dismountStationModule(seat=" + seatId + ", wireId=" + seatWireId
                                + ") failed: " + cause.getClass().getName() + ": "
                                + (cause.getMessage() == null ? "" : cause.getMessage()),
                        cause);
            }
        }
    }

    /**
     * W3 L1b-ii: one command-transfer re-key's outcome. Either
     * {@code denyReason != null} — a NORMAL-race ineligibility (target died/left/wrong class),
     * line-law-safe server-authored text, nothing mutated — or the re-keyed identity: the
     * source hull's stable id (the caller mints its fresh above-band id), the target's stable
     * id (the caller re-points the seat's reserved wire id onto it) and the target's
     * FlyInResult-shaped fields for the RAPP re-point fan (the {@link ModuleMountResult} twin).
     *
     * <p>The two source-captain facts, read at the verb
     * AFTER the vacated hull's revert ran — {@code sourceAllSkillsCaptain} = the restored original
     * IS its all-skills seat mint ({@link #sourceAllSkillsCaptain}; the source announce row's bit)
     * and {@code sourceCaptainFallback} = it is a curated captain the bare announce row cannot
     * reproduce ({@link #sourceCaptainFallback}; counted on the announce line). Both false on a deny.
     */
    public record TransferRekeyResult(String denyReason, String sourceStableId,
            String targetStableId, String variantId, String descriptorVariantId, int side,
            String shipName, int crPerMille, String sourceVariantId, int sourceCrPerMille,
            boolean sourceAllSkillsCaptain, boolean sourceCaptainFallback) {

        /** The NORMAL-race refusal: nothing mutated, every identity field absent. Public for the contract rows. */
        public static TransferRekeyResult deny(String reason) {
            return new TransferRekeyResult(reason, null, null, null, null, -1, null, 0, null, 0,
                    false, false);
        }
    }

    /**
     * The source announce row's all-skills bit, with the
     * boot's knob — the form the three re-key verbs call ({@link #sourceAllSkillsCaptain(String,
     * Object, boolean)} holds the law).
     */
    public static boolean sourceAllSkillsCaptain(String sourceCaptainToken, Object hull) {
        return sourceAllSkillsCaptain(sourceCaptainToken, hull, AllSkillsAttach.enabled());
    }

    /**
     * TRUE iff the verb's revert RAN on {@code hull}
     * (its {@code sourceCaptain=} token starts {@code reverted:}; a gone body, a wreck and a pod keep
     * what they carry and read false — a wreck still holds the seat's own person), the all-skills
     * knob is ON, and the hull's captain read AFTER the revert (= its own original) is an all-skills
     * seat mint ({@link AllSkillsAttach#isMintedPersonOrThrow}). The captain is read by NAME and
     * ARITY ({@code getCaptain}/0) on the hull's runtime class, so a contract row drives it with a
     * fake. Never throws into the verb: a failed read — the captain's or the mint's portrait read
     * alike — is false (the bare row) + one line PER failure, no latch.
     */
    public static boolean sourceAllSkillsCaptain(String sourceCaptainToken, Object hull,
            boolean allSkillsKnob) {
        if (!allSkillsKnob || !revertRan(sourceCaptainToken, hull)) {
            return false;
        }
        try {
            return AllSkillsAttach.isMintedPersonOrThrow(hullCaptain(hull));
        } catch (Exception e) {
            logSourceCaptainReadFailed("transfer_source_allskills_read_failed", e);
            return false;
        }
    }

    /**
     * The announce line's {@code captain_fallback} count,
     * with the strict curated lookup the seat attach tries FIRST (the fielded variant's own table
     * row, {@link OfficerAttach#tableSpecOrNull}) — the form the three re-key verbs call.
     */
    public static boolean sourceCaptainFallback(String sourceCaptainToken, Object hull,
            String sourceVariantId) {
        return sourceCaptainFallback(sourceCaptainToken, hull, sourceVariantId,
                v -> OfficerAttach.tableSpecOrNull(v) != null);
    }

    /**
     * TRUE iff the verb's revert RAN on {@code hull} and its
     * restored original is a REAL captain — non-null, not a vanilla default person
     * ({@code isDefault}/0: a captain-less member carries a fresh default person, never null), not
     * an all-skills mint (the row's bit covers that one) — whose source variant has NO strict
     * curated row. The bare announce row makes a client that MINTS the twin look up that strict row
     * only, so this captain (the seat descriptor's fallback row on an out-of-table pick, or a wave
     * row's own captain) is server truth the twin does not carry; the line counts it. Same read and
     * failure law as {@link #sourceAllSkillsCaptain(String, Object, boolean)}.
     */
    public static boolean sourceCaptainFallback(String sourceCaptainToken, Object hull,
            String sourceVariantId, java.util.function.Predicate<String> hasStrictCuratedRow) {
        if (!revertRan(sourceCaptainToken, hull)) {
            return false;
        }
        try {
            Object captain = hullCaptain(hull);
            return captain != null
                    && !Boolean.TRUE.equals(captain.getClass().getMethod("isDefault").invoke(captain))
                    && !AllSkillsAttach.isMintedPersonOrThrow(captain)
                    && !hasStrictCuratedRow.test(sourceVariantId);
        } catch (Exception e) {
            logSourceCaptainReadFailed("transfer_source_fallback_read_failed", e);
            return false;
        }
    }

    /** The {@code sourceCaptain=} token says the revert wrote the hull's original back. */
    private static boolean revertRan(String sourceCaptainToken, Object hull) {
        return hull != null && sourceCaptainToken != null
                && sourceCaptainToken.startsWith("reverted:");
    }

    /** {@code getCaptain}/0 on the hull's own runtime class; throws into the caller's catch. */
    private static Object hullCaptain(Object hull) throws ReflectiveOperationException {
        return hull.getClass().getMethod("getCaptain").invoke(hull);
    }

    /** One line per failed source-captain read (a transfer-rate event, bounded tokens only). */
    private static void logSourceCaptainReadFailed(String token, Exception e) {
        System.out.println("npeer-serve " + token + " err="
                + RealEngineDemoRoster.unwrap(e).getClass().getSimpleName());
    }

    /**
     * W3 L1b-ii: re-key a seat's FIXED wire id from its current
     * hull onto a friendly target hull — the {@link #mountStationModule} /
     * {@link #dismountStationModule} idioms COMBINED. Source hull: engine AI restored via
     * {@code setDefaultAI(getFleetMember())} (ModManager re-picks; NEVER
     * {@code resetDefaultAI()}, which picks BasicShipAI) and its live-map entry re-pointed away
     * (the hull lives on, AI-flown, like a dismounted module). Target hull: resolved by STABLE
     * id off the engine's own ship list (the {@code waveProbeSampleLine} scan idiom — the
     * registry has no reverse wireId lookup, so the caller resolves wireId→stable off its
     * registry snapshot and passes both), eligibility re-checked HERE atomically under the
     * roster lock (alive + the seat's own team + not fighter/hulk/station-module/pod — a stale target is a
     * typed DENY, never a throw: mid-flight target death is a normal race, unlike the wiring
     * failures below), AI yielded ({@code setShipAI(null)}, sticky), live + stable maps
     * re-pointed (the mount re-point idiom verbatim).
     *
     * <p>F3 id-steal law, takeover-widened: a target body already live-mapped under
     * {@code targetHullWireId} is the PARKED-seat displacement the caller announced (its entry
     * vacates as part of this re-key — the caller has already detached the grace AI and
     * recorded the parked seat's mount loss); under any OTHER wire id it THROWS. Fail-closed
     * THROWS (wiring, never per-session): no deployed round/descriptor, seat wire id not
     * live-mapped (the drain's winner-mounted re-check should have caught it), stable-id read
     * failure. {@code deployedShipCount} deliberately untouched — nothing minted.
     */
    public static TransferRekeyResult transferSeatToHull(int seatId,
            long seatWireId, long targetHullWireId, String targetStableId) {
        synchronized (RealEngineDemoRoster.class) {
            if (!RealEngineDemoRoster.deployed || RealEngineDemoRoster.deployedDescriptor == null) {
                throw new RealEngineDemoRosterException(
                        "transferSeatToHull(seat=" + seatId + ") without a deployed descriptor round"
                                + " (deployed=" + RealEngineDemoRoster.deployed + ") — transfers"
                                + " exist only on the descriptor-driven npeer path");
            }
            Object source = RealEngineDemoRoster.liveShipByWireId.get(seatWireId);
            // A DEAD seat's hull was live-map-vacated at its
            // death (vacateSeatHull) but the wreck lingers — a transfer OUT of it commits with
            // the wreck as source (resolved by the seat's stable id off the engine list; ONLY
            // a hulk qualifies, a live unmapped source is still the wiring bug below).
            // A wreck GONE from the list (fractured-then-removed / exploded
            // mid-flight; the drain keeps such a flight — vanilla parity) commits
            // source-less: no AI restore, no source body ops, maps still re-pointed.
            boolean hulkSource = false;
            boolean sourceGone = false;
            if (source == null) {
                WreckSource wreck = wreckSource(seatWireId);
                source = wreck.hulk();
                hulkSource = source != null;
                sourceGone = wreck.gone();
            }
            if (source == null && !sourceGone) {
                throw new RealEngineDemoRosterException(
                        "transferSeatToHull(seat=" + seatId + "): wire id " + seatWireId
                                + " is not live-mapped — the drain's winner-mounted re-check must"
                                + " precede the re-key");
            }
            ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
            try {
                Class<?> engineClass = Class.forName(RealEngineDemoRoster.ENGINE_CLASS, true, loader);
                Object engine = engineClass.getMethod("getInstance").invoke(null);
                if (engine == null) {
                    throw new RealEngineDemoRosterException(
                            "CombatEngine.getInstance() returned null at transferSeatToHull(seat="
                                    + seatId + ")");
                }
                Class<?> shipClass = Class.forName(RealEngineDemoRoster.SHIP_CLASS, false, loader);
                // Resolve the target by stable id off the live ship list (waveProbeSampleLine
                // idiom) — a miss is the normal target-left-the-field race, not a wiring failure.
                Object target = null;
                Method getId = shipClass.getMethod("getId");
                for (Object candidate : (List<?>) engineClass.getMethod("getShips").invoke(engine)) {
                    if (shipClass.isInstance(candidate)
                            && targetStableId.equals(getId.invoke(candidate))) {
                        target = candidate;
                        break;
                    }
                }
                if (target == null) {
                    return TransferRekeyResult.deny("transfer aborted - target left the field");
                }
                if (target == source) {
                    return TransferRekeyResult.deny("transfer aborted - already your ship");
                }
                Class<?> shipApiClass = Class.forName(RealEngineDemoRoster.SHIP_API, false, loader);
                Object alive = engineClass.getMethod("isShipAlive", shipApiClass)
                        .invoke(engine, target);
                if (!Boolean.TRUE.equals(alive)) {
                    return TransferRekeyResult.deny("transfer aborted - target was destroyed");
                }
                int owner = ((Number) shipClass.getMethod("getOwner").invoke(target)).intValue();
                // Friendly = the SEAT's team, read off
                // the body it sits in (the pod it rides at a commit or a dock-back; a wreck by
                // its original owner, death turns the live owner neutral); a body gone from the
                // field falls back to the seat's slot side.
                int seatSide = source == null ? -1 : seatSideOf(
                        shipClass.getMethod("getOwner").invoke(source),
                        shipClass.getMethod("getOriginalOwner").invoke(source), -1);
                if (seatSide < 0) {
                    seatSide = DescriptorLayout.seatSlot(seatId).side();
                }
                String sideDeny = transferSideDenyOrNull(owner, seatSide);
                if (sideDeny != null) {
                    return TransferRekeyResult.deny(sideDeny);
                }
                // The census class exclusions, re-read at commit: fighters, hulks,
                // station modules (those transfer via /module) and shuttle pods never seat.
                if (Boolean.TRUE.equals(shipClass.getMethod("isFighter").invoke(target))
                        || Boolean.TRUE.equals(shipClass.getMethod("isHulk").invoke(target))
                        || Boolean.TRUE.equals(shipClass.getMethod("isStationModule").invoke(target))
                        || Boolean.TRUE.equals(shipClass.getMethod("isShuttlePod").invoke(target))) {
                    return TransferRekeyResult.deny("transfer aborted - target cannot be piloted");
                }
                Long stolenKey = null;
                for (Map.Entry<Long, Object> live
                        : RealEngineDemoRoster.liveShipByWireId.entrySet()) {
                    if (live.getValue() == target) {
                        stolenKey = live.getKey();
                        break;
                    }
                }
                if (stolenKey != null && stolenKey.longValue() != targetHullWireId) {
                    throw new RealEngineDemoRosterException(
                            "transferSeatToHull(seat=" + seatId + "): target " + targetStableId
                                    + " is live-mapped under wire id " + stolenKey + ", not the"
                                    + " named " + targetHullWireId + " (F3 id-steal law — one body"
                                    + " must never carry two wire ids)");
                }
                String srcIdValue = source == null
                        ? String.valueOf(RealEngineDemoRoster.seatStableIdByWireId.get(seatWireId))
                                .trim()
                        : String.valueOf(getId.invoke(source)).trim();
                if (srcIdValue.isEmpty()) {
                    throw new RealEngineDemoRosterException(
                            "transferSeatToHull(seat=" + seatId + "): source stable-id read failed");
                }
                Object variant = shipClass.getMethod("getVariant").invoke(target);
                Object rawVariantId = variant == null
                        ? null : variant.getClass().getMethod("getHullVariantId").invoke(variant);
                String targetVariantId = rawVariantId instanceof String ? (String) rawVariantId : "";
                // W3-I(b): the SOURCE's engine-true identity, read pre-mutation off
                // the retiring hull — the commit fan announces it under the fresh source id so
                // no client infers the retiree's shape from the post-repoint seat row.
                Object srcVariant = source == null
                        ? null : shipClass.getMethod("getVariant").invoke(source);
                Object rawSrcVariantId = srcVariant == null
                        ? null : srcVariant.getClass().getMethod("getHullVariantId").invoke(srcVariant);
                String sourceVariantId = rawSrcVariantId instanceof String ? (String) rawSrcVariantId : "";
                float sourceCr = source == null ? 0f
                        : ((Number) shipClass.getMethod("getCurrentCR").invoke(source))
                                .floatValue();
                // One-entity pod: a SOURCE that reads
                // isShuttlePod() is the seat's in-flight pod body (the seat rode it since
                // mountSeatOnPod at BEGIN) — this DOCK re-key leaves it behind for the caller's
                // despawnFlight, so NO AI restore (setDefaultAI(pod, null) on a bare pod is the
                // r5 hazard) and getFleetMember() is never read on it (a bare pod has none).
                boolean podSource = source != null
                        && Boolean.TRUE.equals(shipClass.getMethod("isShuttlePod").invoke(source));
                // MUTATE (all checks passed): source AI restored, target AI yielded, maps
                // re-pointed. The dismount restore first — a failure before the map re-point
                // leaves the seat coherently on its old hull.
                Object fleetMember = source == null || podSource
                        ? null : shipClass.getMethod("getFleetMember").invoke(source);
                Method setDefaultAI = null;
                Method setShipAI = null;
                for (Method candidate : shipClass.getMethods()) {
                    if ("setDefaultAI".equals(candidate.getName())
                            && candidate.getParameterCount() == 1) {
                        setDefaultAI = candidate;
                    } else if ("setShipAI".equals(candidate.getName())
                            && candidate.getParameterCount() == 1) {
                        setShipAI = candidate;
                    }
                }
                if (setDefaultAI == null || setShipAI == null) {
                    throw new RealEngineDemoRosterException(
                            "transferSeatToHull(seat=" + seatId + "): 1-arg setDefaultAI/setShipAI"
                                    + " missing on Ship — the AI verbs moved (re-verify the AI-verb anchors)");
                }
                // The seat's captain follows it onto the
                // target, and the hull it leaves gets its own original back. Handles resolved + the person
                // minted HERE, before the first mutation, so a reflective or mint failure leaves
                // both bodies untouched (only the setCaptain invokes run after the AI ops).
                TransferCaptain captain = resolveTransferCaptain(loader, shipClass, target, true,
                        targetVariantId, DescriptorLayout.seatSlot(seatId).descriptorVariantId(),
                        DescriptorLayout.seatShipName(seatId));
                // A wreck gets no AI back (vanilla CombatState.setPlayerShip
                // skips the restore for a hulk source too).
                String sourceAiPath = sourceGone ? "gone-source(no body)"
                        : hulkSource ? "hulk-source(no AI restore)"
                        : podSource ? "pod-source(removed by caller)"
                        : restoreSourceAi(source, fleetMember, setDefaultAI, setShipAI);
                String sourceCaptain = vacatedCaptain(shipClass, source, sourceGone, hulkSource,
                        podSource || isPodVariant(sourceVariantId), fleetMember);
                boolean srcAllSkills = sourceAllSkillsCaptain(sourceCaptain, source);
                boolean srcFallback = sourceCaptainFallback(sourceCaptain, source, sourceVariantId);
                setShipAI.invoke(target, new Object[] {null});
                String captainPath = recaptainTransferTarget(captain, target);
                // W3-J clear: the restored source AI may have run
                // RETREAT during the stint — the handback then delivers a hull whose travel
                // drive, task-manager RETREAT order and Ship retreat flags all survive
                // setShipAI(null) (plain Ship fields). Engine
                // -native recipe in the notifyVentingStarted order: drive off FIRST
                // (an engine guard refuses a flag-clear while the drive is on), then the
                // order cancel (without it the per-pass assignment
                // re-sets retreating every frame), then the flag clear. A same-frame flag-clear
                // can still no-op on the drive-off one-frame latency; the
                // applyRetreatBit level law + the bit-9 quarantine supply
                // the next-tick re-assert. This suppress is BELT, not authority — the
                // "the seat's wire bit-9 says drive OFF" premise was falsified: our
                // own ECB retreat lane re-engaged the drive on a spurious client bit-9 edge
                // post-commit. A NoSuchMethod lands in the catch-all = loud.
                String retreatClear = clearRetreatOrderAndState(loader, engineClass, shipClass,
                        engine, target);
                if (stolenKey != null) {
                    RealEngineDemoRoster.liveShipByWireId.remove(stolenKey);
                }
                Map<Long, String> stableByWire =
                        new LinkedHashMap<>(RealEngineDemoRoster.seatStableIdByWireId);
                stableByWire.put(seatWireId, targetStableId);
                RealEngineDemoRoster.seatStableIdByWireId = Collections.unmodifiableMap(stableByWire);
                RealEngineDemoRoster.seatStableIdsThisRound.add(targetStableId);
                RealEngineDemoRoster.liveShipByWireId.put(seatWireId, target);
                DescriptorLayout.SeatSlot slot = DescriptorLayout.seatSlot(seatId);
                float cr = ((Number) shipClass.getMethod("getCurrentCR").invoke(target)).floatValue();
                System.out.println("real-engine-demo-roster transferSeatToHull seat=" + seatId
                        + " wireId=" + seatWireId + " sourceStable=" + srcIdValue
                        + " targetStable=" + targetStableId + " targetVariant=" + targetVariantId
                        + " displacedWireId=" + (stolenKey == null ? "none" : stolenKey)
                        + " sourceAiPath=" + sourceAiPath
                        + " retreatClear=" + retreatClear
                        + " captain=" + captainPath + " fc_for_stats=kept(transfer)"
                        + " sourceCaptain=" + sourceCaptain
                        + " (source AI restored; target AI yielded; live map re-pointed — no mint)");
                return new TransferRekeyResult(null, srcIdValue, targetStableId, targetVariantId,
                        slot.descriptorVariantId(), slot.side(),
                        DescriptorLayout.seatShipName(seatId),
                        DescriptorLayout.crPerMille(cr), sourceVariantId,
                        DescriptorLayout.crPerMille(sourceCr), srcAllSkills, srcFallback);
            } catch (RealEngineDemoRosterException e) {
                throw e;
            } catch (Throwable t) {
                Throwable cause = RealEngineDemoRoster.unwrap(t);
                throw new RealEngineDemoRosterException(
                        "transferSeatToHull(seat=" + seatId + ", target=" + targetStableId
                                + ") failed: " + cause.getClass().getName() + ": "
                                + (cause.getMessage() == null ? "" : cause.getMessage()),
                        cause);
            }
        }
    }

    /**
     * One-entity pod: at BEGIN, re-key the
     * seat's FIXED wire id from its current hull onto the freshly spawned pod resolved BY
     * STABLE ID — the seat RIDES the pod for the whole flight, so the pod becomes the SOURCE
     * of the DOCK-time {@link #transferSeatToHull} / {@link #transferSeatToStationModule}
     * (their pod-source branch). The hull verb's shape with the pod as target: source = the
     * live-mapped hull, else the lingering wreck / gone body ({@link #wreckSource}; a
     * live-unmapped source is the same wiring THROW); source variant + CR read PRE-mutation
     * (the W3-I(b) idiom — the BEGIN fan announces the retiree under the caller's fresh id F).
     * NO source-AI op (the transit park's caretaker was already attached by the serve lane's
     * {@code engageTransitPark}), NO target-AI yield (the pod KEEPS its
     * {@code TransferCommandAI} — a {@code setShipAI(null)} would ground the flight), NO
     * retreat clear (a bare pod carries no order): MUTATE = a LIVE vacated hull's captain
     * reverted to its own original ({@link #revertVacatedCaptain}), then the live + stable maps
     * re-pointed (the hull verb's re-point idiom verbatim). The result's variant is the
     * client-resolvable {@code shuttlepod_cc_pod}
     * ({@link MatchDescriptors#BASTION_SHUTTLEPOD_VARIANT_ID} — the body's engine spec is the
     * runtime-minted {@code shuttlepod_xfer} twin of the SAME hull), CR 1000‰, name
     * = the seat's. Typed DENIES (the caller despawns the flight + aborts): the pod is missing
     * from the engine list (removed before the re-key) or IS the source; the F3 id-steal law
     * still THROWS on a pod already live-mapped under any wire id (a fresh spawn never is —
     * wiring, not a race). {@code deployedShipCount} untouched — nothing minted here.
     */
    public static TransferRekeyResult mountSeatOnPod(int seatId, long seatWireId,
            String podStableId, Object podHandleOrNull) {
        synchronized (RealEngineDemoRoster.class) {
            if (!RealEngineDemoRoster.deployed || RealEngineDemoRoster.deployedDescriptor == null) {
                throw new RealEngineDemoRosterException(
                        "mountSeatOnPod(seat=" + seatId + ") without a deployed descriptor round"
                                + " (deployed=" + RealEngineDemoRoster.deployed + ") — transfers"
                                + " exist only on the descriptor-driven npeer path");
            }
            Object source = RealEngineDemoRoster.liveShipByWireId.get(seatWireId);
            boolean hulkSource = false; // The transferSeatToHull wreck-source twin
            boolean sourceGone = false; // The gone-source twin
            if (source == null) {
                WreckSource wreck = wreckSource(seatWireId);
                source = wreck.hulk();
                hulkSource = source != null;
                sourceGone = wreck.gone();
            }
            if (source == null && !sourceGone) {
                throw new RealEngineDemoRosterException(
                        "mountSeatOnPod(seat=" + seatId + "): wire id " + seatWireId
                                + " is not live-mapped — the serve lane's mounted re-check must"
                                + " precede the BEGIN re-key");
            }
            ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
            try {
                Class<?> engineClass = Class.forName(RealEngineDemoRoster.ENGINE_CLASS, true, loader);
                Object engine = engineClass.getMethod("getInstance").invoke(null);
                if (engine == null) {
                    throw new RealEngineDemoRosterException(
                            "CombatEngine.getInstance() returned null at mountSeatOnPod(seat="
                                    + seatId + ")");
                }
                Class<?> shipClass = Class.forName(RealEngineDemoRoster.SHIP_CLASS, false, loader);
                Method getId = shipClass.getMethod("getId");
                // Fixed after a real-engine FAIL: the spawner's own handle FIRST — the
                // pod was added via CombatEngine.addObject THIS tick and is pending-add until the
                // next advance, so getShips() does not list it yet. The handle counts only when
                // it is a Ship whose id IS podStableId (a stale/foreign handle falls through).
                Object pod = null;
                if (podHandleOrNull != null && shipClass.isInstance(podHandleOrNull)
                        && podStableId.equals(getId.invoke(podHandleOrNull))) {
                    pod = podHandleOrNull;
                }
                // Fallback = the stable-id scan off the live ship list (the transferSeatToHull
                // target idiom) — a miss means the spawn was removed before the re-key: a typed
                // deny, the caller unwinds the flight.
                if (pod == null) {
                    for (Object candidate : (List<?>) engineClass.getMethod("getShips").invoke(engine)) {
                        if (shipClass.isInstance(candidate)
                                && podStableId.equals(getId.invoke(candidate))) {
                            pod = candidate;
                            break;
                        }
                    }
                }
                if (pod == null) {
                    return TransferRekeyResult.deny("transfer aborted - command shuttle unavailable");
                }
                if (pod == source) {
                    return TransferRekeyResult.deny("transfer aborted - already aboard the shuttle");
                }
                for (Map.Entry<Long, Object> live
                        : RealEngineDemoRoster.liveShipByWireId.entrySet()) {
                    if (live.getValue() == pod) {
                        throw new RealEngineDemoRosterException(
                                "mountSeatOnPod(seat=" + seatId + "): pod " + podStableId
                                        + " is already live-mapped under wire id " + live.getKey()
                                        + " (F3 id-steal law — one body must never carry two"
                                        + " wire ids; a fresh spawn is never mapped)");
                    }
                }
                String srcIdValue = source == null
                        ? String.valueOf(RealEngineDemoRoster.seatStableIdByWireId.get(seatWireId))
                                .trim()
                        : String.valueOf(getId.invoke(source)).trim();
                if (srcIdValue.isEmpty()) {
                    throw new RealEngineDemoRosterException(
                            "mountSeatOnPod(seat=" + seatId + "): source stable-id read failed");
                }
                // W3-I(b): the SOURCE's engine-true identity, read pre-mutation off
                // the retiring hull — the BEGIN fan announces it under the fresh source id F.
                Object srcVariant = source == null
                        ? null : shipClass.getMethod("getVariant").invoke(source);
                Object rawSrcVariantId = srcVariant == null
                        ? null : srcVariant.getClass().getMethod("getHullVariantId").invoke(srcVariant);
                String sourceVariantId = rawSrcVariantId instanceof String ? (String) rawSrcVariantId : "";
                float sourceCr = source == null ? 0f
                        : ((Number) shipClass.getMethod("getCurrentCR").invoke(source))
                                .floatValue();
                boolean podSource = source != null
                        && (Boolean.TRUE.equals(shipClass.getMethod("isShuttlePod").invoke(source))
                                || isPodVariant(sourceVariantId));
                Object fleetMember = source == null || hulkSource || podSource
                        ? null : shipClass.getMethod("getFleetMember").invoke(source);
                // MUTATE (all checks passed): no AI op on either body (source caretaker already
                // attached by the park; the pod keeps its flight AI).
                // the hull the seat leaves gets its OWN original captain back
                // first (the BEGIN revert, every end), then the maps re-point.
                String sourceCaptain = vacatedCaptain(shipClass, source, sourceGone, hulkSource,
                        podSource, fleetMember);
                boolean srcAllSkills = sourceAllSkillsCaptain(sourceCaptain, source);
                boolean srcFallback = sourceCaptainFallback(sourceCaptain, source, sourceVariantId);
                Map<Long, String> stableByWire =
                        new LinkedHashMap<>(RealEngineDemoRoster.seatStableIdByWireId);
                stableByWire.put(seatWireId, podStableId);
                RealEngineDemoRoster.seatStableIdByWireId = Collections.unmodifiableMap(stableByWire);
                RealEngineDemoRoster.seatStableIdsThisRound.add(podStableId);
                RealEngineDemoRoster.liveShipByWireId.put(seatWireId, pod);
                DescriptorLayout.SeatSlot slot = DescriptorLayout.seatSlot(seatId);
                System.out.println("real-engine-demo-roster mountSeatOnPod seat=" + seatId
                        + " wireId=" + seatWireId + " sourceStable=" + srcIdValue
                        + " podStable=" + podStableId
                        + " sourcePath=" + (sourceGone ? "gone" : hulkSource ? "hulk" : "live")
                        + " sourceCaptain=" + sourceCaptain
                        + " (no AI op on either body; live map re-pointed — no mint)");
                return new TransferRekeyResult(null, srcIdValue, podStableId,
                        MatchDescriptors.BASTION_SHUTTLEPOD_VARIANT_ID,
                        slot.descriptorVariantId(), slot.side(),
                        DescriptorLayout.seatShipName(seatId), 1000, sourceVariantId,
                        DescriptorLayout.crPerMille(sourceCr), srcAllSkills, srcFallback);
            } catch (RealEngineDemoRosterException e) {
                throw e;
            } catch (Throwable t) {
                Throwable cause = RealEngineDemoRoster.unwrap(t);
                throw new RealEngineDemoRosterException(
                        "mountSeatOnPod(seat=" + seatId + ", pod=" + podStableId
                                + ") failed: " + cause.getClass().getName() + ": "
                                + (cause.getMessage() == null ? "" : cause.getMessage()),
                        cause);
            }
        }
    }

    /**
     * W3-J full retreat clear: the engine-native recipe both
     * transfer verbs run on the freshly re-keyed seat body — {@code turnOffTravelDrive} (drive
     * off first, the flag-clear guard), then the task-manager ROOT cancel
     * ({@code cancelDirectOrdersForMember} — removes the RETREAT
     * assignment so the per-pass processing stops re-setting the flag), then
     * {@code setRetreating(false,false)}. Accessor chain byte-verified:
     * {@code getFleetManager(int)} → {@code getDeployedFleetMember(Ship)} (returns the
     * fleet manager's deployed-member handle directly) → {@code getTaskManager(false)}
     * (the primary TM). A null member (station modules carry no deployed row) skips the
     * cancel — state-only. Every reflective failure THROWS to the caller's loud catch-all.
     * Returns the compact path token the transfer log line carries.
     */
    private static String clearRetreatOrderAndState(ClassLoader loader, Class<?> engineClass,
            Class<?> shipClass, Object engine, Object body) throws Exception {
        shipClass.getMethod("turnOffTravelDrive").invoke(body);
        int side = ((Number) shipClass.getMethod("getOwner").invoke(body)).intValue();
        Object manager = engineClass.getMethod("getFleetManager", int.class)
                .invoke(engine, side);
        String orderPath = "no-manager";
        if (manager != null) {
            Object member = manager.getClass()
                    .getMethod("getDeployedFleetMember", shipClass).invoke(manager, body);
            if (member == null) {
                orderPath = "no-member";
            } else {
                Object taskManager = manager.getClass()
                        .getMethod("getTaskManager", boolean.class).invoke(manager, false);
                Class<?> memberClass = Class.forName(
                        "com.fs.starfarer.combat.CombatFleetManager$O0", false, loader);
                taskManager.getClass().getMethod("cancelDirectOrdersForMember", memberClass)
                        .invoke(taskManager, member);
                orderPath = "order-cancelled";
            }
        }
        shipClass.getMethod("setRetreating", boolean.class, boolean.class)
                .invoke(body, false, false);
        return orderPath;
    }

    /**
     * W3 station-module transfer: one live
     * UNMOUNTED-claimable weapon-module's identity for the {@code /transfer} census second
     * pass — {@code slotId} is the engine slot id verbatim ({@code "WS 013"}), {@code stableId}
     * the module Ship's own engine id (== its AWSS pose id == the client's remoteEntities key),
     * {@code variantId} its full module variant.
     */
    public record StationModuleCensusRow(String slotId, String stableId, String variantId) {
    }

    /**
     * The bounded census walk — one row per live child module of this round's
     * station base whose engine slot id is in {@code slotIds} (the authored
     * {@code MatchDescriptors.moduleSeatCensus} vocabulary; the walk never invents slots). The
     * mount idiom verbatim: {@code getChildModulesCopy} + {@code getStationSlot().getId()}
     * match + engine-alive only. Fail-soft EMPTY when no station round is deployed (a
     * non-fortress serve simply lists no modules); a reflective failure THROWS like the mount
     * family — the engine idioms moved, which is wiring, never a race.
     */
    public static List<StationModuleCensusRow> stationModuleCensus(
            java.util.Collection<String> slotIds) {
        synchronized (RealEngineDemoRoster.class) {
            if (!RealEngineDemoRoster.deployed || RealEngineDemoRoster.stationBaseShip == null) {
                return List.of();
            }
            ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
            try {
                Class<?> engineClass = Class.forName(RealEngineDemoRoster.ENGINE_CLASS, true, loader);
                Object engine = engineClass.getMethod("getInstance").invoke(null);
                if (engine == null) {
                    throw new RealEngineDemoRosterException(
                            "CombatEngine.getInstance() returned null at stationModuleCensus");
                }
                Class<?> shipClass = Class.forName(RealEngineDemoRoster.SHIP_CLASS, false, loader);
                Class<?> shipApiClass = Class.forName(RealEngineDemoRoster.SHIP_API, false, loader);
                Object rawChildren = shipClass.getMethod("getChildModulesCopy")
                        .invoke(RealEngineDemoRoster.stationBaseShip);
                if (!(rawChildren instanceof List)) {
                    throw new RealEngineDemoRosterException(
                            "stationModuleCensus: base getChildModulesCopy() returned "
                                    + (rawChildren == null ? "null"
                                            : rawChildren.getClass().getName()));
                }
                Method shipGetStationSlot = shipClass.getMethod("getStationSlot");
                Method getId = shipClass.getMethod("getId");
                Method getVariant = shipClass.getMethod("getVariant");
                Method isShipAlive = engineClass.getMethod("isShipAlive", shipApiClass);
                java.util.List<StationModuleCensusRow> rows = new java.util.ArrayList<>();
                for (Object child : (List<?>) rawChildren) {
                    if (child == null || !shipClass.isInstance(child)) {
                        continue;
                    }
                    Object childSlot = shipGetStationSlot.invoke(child);
                    if (childSlot == null) {
                        continue;
                    }
                    Object childSlotId = childSlot.getClass().getMethod("getId").invoke(childSlot);
                    if (!(childSlotId instanceof String) || !slotIds.contains(childSlotId)) {
                        continue;
                    }
                    if (!Boolean.TRUE.equals(isShipAlive.invoke(engine, child))) {
                        continue; // a dead module is unlistable, like any dead hull
                    }
                    Object idValue = getId.invoke(child);
                    String stable = (idValue instanceof String) ? ((String) idValue).trim() : "";
                    if (stable.isEmpty()) {
                        continue; // unidentifiable body — never publish a wireless row
                    }
                    Object variant = getVariant.invoke(child);
                    Object rawVariantId = variant == null
                            ? null : variant.getClass().getMethod("getHullVariantId").invoke(variant);
                    rows.add(new StationModuleCensusRow((String) childSlotId, stable,
                            rawVariantId instanceof String ? (String) rawVariantId : ""));
                }
                return rows;
            } catch (RealEngineDemoRosterException e) {
                throw e;
            } catch (Throwable t) {
                Throwable cause = RealEngineDemoRoster.unwrap(t);
                throw new RealEngineDemoRosterException(
                        "stationModuleCensus failed: " + cause.getClass().getName() + ": "
                                + (cause.getMessage() == null ? "" : cause.getMessage()),
                        cause);
            }
        }
    }

    /**
     * W3 station-module transfer commit: re-key a seat's FIXED wire id
     * from its current hull onto an UNMOUNTED live station weapon module — the
     * {@link #transferSeatToHull} source-retire half verbatim plus the
     * {@link #mountStationModule} target idioms, composed under ONE roster lock. Source hull:
     * engine AI restored via {@code setDefaultAI(getFleetMember())} (never
     * {@code resetDefaultAI()}), live-map entry re-pointed away. Target module: resolved BY
     * STABLE ID off {@code getChildModulesCopy} (parent implied — a stable id only ever names
     * one body), engine-alive re-checked, AI yielded ({@code setShipAI(null)}, sticky), live +
     * stable maps re-pointed (the mount re-point idiom verbatim).
     *
     * <p>Typed DENIES (normal races, nothing mutated): station/module gone ("target left the
     * field" — a flight legally outlives the station), module dead ("target was destroyed"),
     * module live-mapped anywhere ("module already claimed" — the AI_HULL path only, NO
     * displacement: unlike a parked-seat takeover, a claimed module is never displaceable).
     * Fail-closed THROWS (wiring): no deployed descriptor round, seat wire id not live-mapped,
     * stable-id read failure. {@code targetModuleWireId} is log/interface identity only — the
     * F3 stolen-key displacement path deliberately does not exist here.
     */
    public static TransferRekeyResult transferSeatToStationModule(int seatId,
            long seatWireId, long targetModuleWireId, String targetStableId) {
        synchronized (RealEngineDemoRoster.class) {
            if (!RealEngineDemoRoster.deployed || RealEngineDemoRoster.deployedDescriptor == null) {
                throw new RealEngineDemoRosterException(
                        "transferSeatToStationModule(seat=" + seatId + ") without a deployed"
                                + " descriptor round (deployed=" + RealEngineDemoRoster.deployed
                                + ") — transfers exist only on the descriptor-driven npeer path");
            }
            Object source = RealEngineDemoRoster.liveShipByWireId.get(seatWireId);
            boolean hulkSource = false; // The transferSeatToHull wreck-source twin
            boolean sourceGone = false; // The gone-source twin
            if (source == null) {
                WreckSource wreck = wreckSource(seatWireId);
                source = wreck.hulk();
                hulkSource = source != null;
                sourceGone = wreck.gone();
            }
            if (source == null && !sourceGone) {
                throw new RealEngineDemoRosterException(
                        "transferSeatToStationModule(seat=" + seatId + "): wire id " + seatWireId
                                + " is not live-mapped — the drain's winner-mounted re-check must"
                                + " precede the re-key");
            }
            ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
            try {
                Class<?> engineClass = Class.forName(RealEngineDemoRoster.ENGINE_CLASS, true, loader);
                Object engine = engineClass.getMethod("getInstance").invoke(null);
                if (engine == null) {
                    throw new RealEngineDemoRosterException(
                            "CombatEngine.getInstance() returned null at transferSeatToStationModule"
                                    + "(seat=" + seatId + ")");
                }
                Class<?> shipClass = Class.forName(RealEngineDemoRoster.SHIP_CLASS, false, loader);
                // Module resolve BY STABLE ID (parent implied): the station gone or
                // the slot's body detached is the normal target-left race, never wiring.
                Object module = null;
                if (RealEngineDemoRoster.stationBaseShip != null) {
                    Object rawChildren = shipClass.getMethod("getChildModulesCopy")
                            .invoke(RealEngineDemoRoster.stationBaseShip);
                    if (rawChildren instanceof List) {
                        Method getId = shipClass.getMethod("getId");
                        for (Object child : (List<?>) rawChildren) {
                            if (child != null && shipClass.isInstance(child)
                                    && targetStableId.equals(getId.invoke(child))) {
                                module = child;
                                break;
                            }
                        }
                    }
                }
                if (module == null) {
                    return TransferRekeyResult.deny("transfer aborted - target left the field");
                }
                Class<?> shipApiClass = Class.forName(RealEngineDemoRoster.SHIP_API, false, loader);
                Object alive = engineClass.getMethod("isShipAlive", shipApiClass)
                        .invoke(engine, module);
                if (!Boolean.TRUE.equals(alive)) {
                    return TransferRekeyResult.deny("transfer aborted - target was destroyed");
                }
                // Claimed-meanwhile deny: a live-map VALUE anywhere means another seat
                // rides it (a mount or another transfer won the race) — never displace.
                if (RealEngineDemoRoster.liveShipByWireId.containsValue(module)) {
                    return TransferRekeyResult.deny("transfer aborted - module already claimed");
                }
                Object idValue = source == null
                        ? RealEngineDemoRoster.seatStableIdByWireId.get(seatWireId)
                        : shipClass.getMethod("getId").invoke(source);
                String srcIdValue = (idValue instanceof String) ? ((String) idValue).trim() : "";
                if (srcIdValue.isEmpty()) {
                    throw new RealEngineDemoRosterException(
                            "transferSeatToStationModule(seat=" + seatId
                                    + "): source stable-id read failed");
                }
                Object variant = shipClass.getMethod("getVariant").invoke(module);
                Object rawVariantId = variant == null
                        ? null : variant.getClass().getMethod("getHullVariantId").invoke(variant);
                String moduleVariantId = rawVariantId instanceof String ? (String) rawVariantId : "";
                // W3-I(b): source identity pre-mutation — the hull-verb idiom verbatim.
                Object srcVariant = source == null
                        ? null : shipClass.getMethod("getVariant").invoke(source);
                Object rawSrcVariantId = srcVariant == null
                        ? null : srcVariant.getClass().getMethod("getHullVariantId").invoke(srcVariant);
                String sourceVariantId = rawSrcVariantId instanceof String ? (String) rawSrcVariantId : "";
                float sourceCr = source == null ? 0f
                        : ((Number) shipClass.getMethod("getCurrentCR").invoke(source))
                                .floatValue();
                // One-entity pod: the hull-verb
                // pod-source branch verbatim — a source reading isShuttlePod() is the seat's
                // in-flight pod body; no AI restore (the r5 setDefaultAI(pod, null) hazard),
                // no getFleetMember() read on it (a bare pod has none); the caller despawns it.
                boolean podSource = source != null
                        && Boolean.TRUE.equals(shipClass.getMethod("isShuttlePod").invoke(source));
                // MUTATE (all checks passed) — the transferSeatToHull order verbatim: source AI
                // restored first (a failure before the map re-point leaves the seat coherently
                // on its old hull), module AI yielded, maps re-pointed.
                Object fleetMember = source == null || podSource
                        ? null : shipClass.getMethod("getFleetMember").invoke(source);
                Method setDefaultAI = null;
                Method setShipAI = null;
                for (Method candidate : shipClass.getMethods()) {
                    if ("setDefaultAI".equals(candidate.getName())
                            && candidate.getParameterCount() == 1) {
                        setDefaultAI = candidate;
                    } else if ("setShipAI".equals(candidate.getName())
                            && candidate.getParameterCount() == 1) {
                        setShipAI = candidate;
                    }
                }
                if (setDefaultAI == null || setShipAI == null) {
                    throw new RealEngineDemoRosterException(
                            "transferSeatToStationModule(seat=" + seatId + "): 1-arg"
                                    + " setDefaultAI/setShipAI missing on Ship — the AI verbs"
                                    + " moved (re-verify the AI-verb anchors)");
                }
                // The module twin of the hull verb's
                // captain move + source revert. Ship-only (a module has no
                // deployed member), resolved + minted before the first mutation. Never the
                // station base — its setCaptain fans to every child module.
                TransferCaptain captain = resolveTransferCaptain(loader, shipClass, module, false,
                        moduleVariantId, DescriptorLayout.seatSlot(seatId).descriptorVariantId(),
                        DescriptorLayout.seatShipName(seatId));
                String sourceAiPath = sourceGone ? "gone-source(no body)"
                        : hulkSource ? "hulk-source(no AI restore)"
                        : podSource ? "pod-source(removed by caller)"
                        : restoreSourceAi(source, fleetMember, setDefaultAI, setShipAI);
                String sourceCaptain = vacatedCaptain(shipClass, source, sourceGone, hulkSource,
                        podSource || isPodVariant(sourceVariantId), fleetMember);
                boolean srcAllSkills = sourceAllSkillsCaptain(sourceCaptain, source);
                boolean srcFallback = sourceCaptainFallback(sourceCaptain, source, sourceVariantId);
                setShipAI.invoke(module, new Object[] {null});
                String captainPath = recaptainTransferTarget(captain, module);
                // W3-J clear defensive twin — the hull-verb recipe
                // verbatim; a station module usually has no deployed-member row, so the order
                // cancel resolves to state-only there (the helper's no-member path).
                String retreatClear = clearRetreatOrderAndState(loader, engineClass, shipClass,
                        engine, module);
                Map<Long, String> stableByWire =
                        new LinkedHashMap<>(RealEngineDemoRoster.seatStableIdByWireId);
                stableByWire.put(seatWireId, targetStableId);
                RealEngineDemoRoster.seatStableIdByWireId = Collections.unmodifiableMap(stableByWire);
                RealEngineDemoRoster.seatStableIdsThisRound.add(targetStableId);
                RealEngineDemoRoster.liveShipByWireId.put(seatWireId, module);
                DescriptorLayout.SeatSlot slot = DescriptorLayout.seatSlot(seatId);
                // The module Ship's OWN currentCR — the mountStationModule CR idiom (the fleet
                // -member tracker chain reads plausible-but-WRONG for modules).
                float cr = ((Number) shipClass.getMethod("getCurrentCR").invoke(module)).floatValue();
                System.out.println("real-engine-demo-roster transferSeatToStationModule seat="
                        + seatId + " wireId=" + seatWireId + " sourceStable=" + srcIdValue
                        + " targetStable=" + targetStableId + " targetVariant=" + moduleVariantId
                        + " targetModuleWireId=" + targetModuleWireId
                        + " sourceAiPath=" + sourceAiPath
                        + " retreatClear=" + retreatClear
                        + " captain=" + captainPath + " sourceCaptain=" + sourceCaptain
                        + " (source AI restored; module AI yielded; live map re-pointed — no mint)");
                return new TransferRekeyResult(null, srcIdValue, targetStableId, moduleVariantId,
                        slot.descriptorVariantId(), slot.side(),
                        DescriptorLayout.seatShipName(seatId),
                        DescriptorLayout.crPerMille(cr), sourceVariantId,
                        DescriptorLayout.crPerMille(sourceCr), srcAllSkills, srcFallback);
            } catch (RealEngineDemoRosterException e) {
                throw e;
            } catch (Throwable t) {
                Throwable cause = RealEngineDemoRoster.unwrap(t);
                throw new RealEngineDemoRosterException(
                        "transferSeatToStationModule(seat=" + seatId + ", target="
                                + targetStableId + ") failed: " + cause.getClass().getName()
                                + ": " + (cause.getMessage() == null ? "" : cause.getMessage()),
                        cause);
            }
        }
    }

    /**
     * W3 transfer-OUT source-AI restore, drone-aware: a
     * vacated born-growth drone resumes its launcher orbit via the engine's own drone-AI pick
     * ({@link OrbitDroneDrive#pickVanillaDroneAiOrNull} — the one home, where the
     * gate + the ModManager resolve are documented; the default {@code setDefaultAI} path
     * picks {@code BasicShipAI} for drones, so an orbit would never resume there). Gate-miss
     * or ANY reflective failure falls through to {@code setDefaultAI} — exactly the prior
     * behavior, free-fly BasicShipAI. Returns the path taken for the transfer summary line.
     * The transit BEGIN park already attached this same AI, so on the drone path
     * this is a fresh re-pick of the same class (one-frame smooth-module re-seed, no body move).
     */
    /** A dead seat's source resolve — {@code hulk} = the lingering wreck
     * (the transfer source); {@code gone} = the seat HAD a body this round but nothing with
     * its stable id is on the engine list any more (fractured-then-removed / exploded) — the
     * commit proceeds source-less. Neither = live-unmapped (the caller's wiring throw). */
    record WreckSource(Object hulk, boolean gone) {
        static final WreckSource NONE = new WreckSource(null, false);
    }

    /**
     * The dead seat's lingering wreck as a transfer SOURCE —
     * {@code vacateSeatHull} dropped its live-map row at death, {@code seatStableIdByWireId}
     * still names its stable id (only a fly-in re-points that). Resolved off the engine's
     * ship list (the {@code TransferPodSpawner.resolveShipOrNull} idiom — the pod already
     * launched from this very object); the hulk only if the body is there AND
     * {@code isHulk()}; {@code gone} when no body carries the id (the
     * drain keeps such a flight, so the verb must not throw). Fail-soft NONE.
     */
    private static WreckSource wreckSource(long seatWireId) {
        String stable = RealEngineDemoRoster.seatStableIdByWireId.get(seatWireId);
        if (stable == null) {
            return WreckSource.NONE;
        }
        try {
            ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
            Class<?> engineClass = Class.forName(RealEngineDemoRoster.ENGINE_CLASS, true, loader);
            Object engine = engineClass.getMethod("getInstance").invoke(null);
            if (engine == null) {
                return WreckSource.NONE;
            }
            Class<?> shipClass = Class.forName(RealEngineDemoRoster.SHIP_CLASS, false, loader);
            Method getId = shipClass.getMethod("getId");
            Method isHulk = shipClass.getMethod("isHulk");
            for (Object candidate : (List<?>) engineClass.getMethod("getShips").invoke(engine)) {
                if (shipClass.isInstance(candidate) && stable.equals(getId.invoke(candidate))) {
                    return Boolean.TRUE.equals(isHulk.invoke(candidate))
                            ? new WreckSource(candidate, false) : WreckSource.NONE;
                }
            }
            return new WreckSource(null, true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("real-engine-demo-roster hulk-source resolve threw wireId="
                    + seatWireId + " err=" + RealEngineDemoRoster.unwrap(e));
            return WreckSource.NONE;
        }
    }

    private static String restoreSourceAi(Object source, Object fleetMember,
            Method setDefaultAI, Method setShipAI) throws Exception {
        try {
            Object droneAi = OrbitDroneDrive.pickVanillaDroneAiOrNull(source);
            if (droneAi != null) {
                setShipAI.invoke(source, droneAi);
                return "drone-orbit";
            }
        } catch (Throwable t) {
            System.out.println("real-engine-demo-roster transfer source drone-AI restore fell"
                    + " back to default: " + RealEngineDemoRoster.unwrap(t));
        }
        setDefaultAI.invoke(source, fleetMember);
        return "default";
    }

    private static final String PERSON_API = "com.fs.starfarer.api.characters.PersonAPI";
    private static final String FLEET_MEMBER_API = "com.fs.starfarer.api.fleet.FleetMemberAPI";

    /**
     * A transfer target's captain move,
     * resolved BEFORE the re-key verb's first mutation (the fail-before-mutate idiom): every
     * reflective handle, the target's member, and (knob ON) the freshly minted person.
     * {@code path} = {@code allskills} (person minted), {@code curated} (knob OFF, the
     * member-borne curated attach runs at apply time — it IS the member's setCaptain) or
     * {@code none} (knob OFF and no member to carry a curated row).
     */
    private record TransferCaptain(String path, Object person, Object member,
            Method shipSetCaptain, Method memberSetCaptain, Method memberGetCaptain,
            String targetVariantId, String seatDescVariantId) {
    }

    /**
     * The captain FOLLOWS the seat onto the
     * body it lands in — vanilla's landing move (the engine's transfer sets the new ship's
     * captain on the Ship and its member, never the fleet commander). Minted fresh like every
     * seat attach and never read off the source (at a dock the source is the bare pod). The
     * commander pair is NOT moved: fleet-wide skills apply only at ship creation, and the
     * client's plain-commander replica derives the same numbers as this pair-less target.
     * {@code withMember} false = a station module (Ship-only; a module has no deployed member).
     * THROWS: a reflective miss or a failed mint lands in the verb's catch-all (serve-fatal) —
     * a half-captained hull would be a stats desync.
     */
    private static TransferCaptain resolveTransferCaptain(ClassLoader loader, Class<?> shipClass,
            Object target, boolean withMember, String targetVariantId, String seatDescVariantId,
            String label) throws Exception {
        Class<?> personApi = Class.forName(PERSON_API, false, loader);
        Class<?> memberApi = Class.forName(FLEET_MEMBER_API, false, loader);
        Method shipSetCaptain = shipClass.getMethod("setCaptain", personApi);
        Method memberSetCaptain = memberApi.getMethod("setCaptain", personApi);
        Method memberGetCaptain = memberApi.getMethod("getCaptain");
        Object member = withMember ? shipClass.getMethod("getFleetMember").invoke(target) : null;
        CaptainArm arm = captainArm(AllSkillsAttach.enabled(), member != null);
        Object person = arm == CaptainArm.ALL_SKILLS ? AllSkillsAttach.mintSeatCaptain(label) : null;
        return new TransferCaptain(arm.token(), person, member, shipSetCaptain, memberSetCaptain,
                memberGetCaptain, targetVariantId, seatDescVariantId);
    }

    /**
     * Which captain a transfer target or a mounted
     * station module receives — the engine-free decision {@link #resolveTransferCaptain} routes
     * on, so a contract row drives every cell without the game. {@link #token()} is the
     * verb's {@code captain=} word.
     */
    public enum CaptainArm {
        /** Knob ON: a freshly minted all-skills person, member or not (a module is Ship-only). */
        ALL_SKILLS("allskills"),
        /** Knob OFF with a member: the member-borne curated row, applied at re-key time. */
        CURATED("curated"),
        /** Knob OFF and no member to carry a curated row: the target is left untouched. */
        NONE("none");

        private final String token;

        CaptainArm(String token) {
            this.token = token;
        }

        /** The {@code captain=} token the re-key and mount rows print. */
        public String token() {
            return token;
        }
    }

    /**
     * The captain arm as a pure function of the
     * all-skills knob and whether the target carries a fleet member (false for a station
     * module, which has no deployed member). The knob wins over member presence.
     */
    public static CaptainArm captainArm(boolean allSkillsKnob, boolean hasMember) {
        if (allSkillsKnob) {
            return CaptainArm.ALL_SKILLS;
        }
        return hasMember ? CaptainArm.CURATED : CaptainArm.NONE;
    }

    /**
     * Apply a resolved {@link TransferCaptain}
     * to the target — the client lever's twin ({@code allskills}: member + Ship; knob OFF: the
     * curated seat lookup, fielded variant first then the seat descriptor, the same order as the
     * client's). Returns the {@code captain=} token: {@code allskills|curated|none}; {@code none}
     * leaves the target byte-identical. Throws into the caller's catch-all.
     */
    private static String recaptainTransferTarget(TransferCaptain c, Object target)
            throws Exception {
        if (c.person() != null) {
            if (c.member() != null) {
                c.memberSetCaptain().invoke(c.member(), c.person());
            }
            c.shipSetCaptain().invoke(target, c.person());
            return c.path();
        }
        if (c.member() != null
                && OfficerAttach.maybeAttachSeat(c.member(), c.targetVariantId(),
                        c.seatDescVariantId())) {
            c.shipSetCaptain().invoke(target, c.memberGetCaptain().invoke(c.member()));
            return "curated";
        }
        return "none";
    }

    /**
     * The BEGIN revert — the LIVE hull a seat leaves
     * gets its OWN original captain back (Ship + member when present), so no hop leaves a
     * seat-grade captain behind on a wave hull. Vanilla's departure branch restores the
     * original too; its other branch (blank the ship when the departing person WAS the
     * original) is an object-identity test that splits across the ends (a client re-mints its
     * own ship's captain), so it is never used: the original is restored on every end, and a
     * null original is passed through as vanilla passes it (the Ship then mints its default
     * person). The caller has excluded pods, hulks and gone bodies.
     *
     * <p>engine-free by construction — every
     * method is found by NAME and ARITY on the object's own runtime class (the {@code setShipAI}
     * idiom: no API class in the signature), so a contract row drives it with a plain fake.
     * Every handle is resolved before the first write; a lookup or write that throws lands in
     * the verb's catch-all (serve-fatal — a half-reverted hull is a stats desync). Returns the
     * {@code sourceCaptain=} token, computed AFTER both writes: {@link #revertedToken}.
     */
    public static String revertVacatedCaptain(Object hull, Object fleetMemberOrNull)
            throws Exception {
        Object original = hull.getClass().getMethod("getOriginalCaptain").invoke(hull);
        Method hullSet = oneArgByName(hull, "setCaptain", original);
        Method memberSet = fleetMemberOrNull == null
                ? null : oneArgByName(fleetMemberOrNull, "setCaptain", original);
        hullSet.invoke(hull, original);
        if (memberSet != null) {
            memberSet.invoke(fleetMemberOrNull, original);
        }
        return revertedToken(original);
    }

    /** Logged once per process: a restored captain whose level read threw (token {@code L?}). */
    private static volatile boolean revertLevelReadFailLogged;

    /**
     * The revert token, in its OWN catch so a
     * level read can never fail the verb after its writes landed. {@code reverted:L<n>} = the
     * restored person's {@code getStats().getLevel()} printed as read (an int, 0 or more on every
     * vanilla person; the all-skills seat captain reads 14); {@code reverted:null} = the
     * original was null and null was written; {@code reverted:L?} = the writes landed but the
     * level read threw or was not a number. The kit's revert prints the same three shapes.
     */
    private static String revertedToken(Object original) {
        if (original == null) {
            return "reverted:null";
        }
        try {
            Object stats = original.getClass().getMethod("getStats").invoke(original);
            Object level = stats.getClass().getMethod("getLevel").invoke(stats);
            if (level instanceof Number) {
                return "reverted:L" + ((Number) level).intValue();
            }
        } catch (Exception e) {
            if (!revertLevelReadFailLogged) {
                revertLevelReadFailLogged = true;
                Throwable cause = RealEngineDemoRoster.unwrap(e);
                System.out.println("real-engine-demo-roster revert_level_read_failed err="
                        + cause.getClass().getSimpleName()
                        + " (logged once; the token reads reverted:L?)");
            }
        }
        return "reverted:L?";
    }

    /**
     * The public one-arg method {@code name} on the
     * target's runtime class whose parameter accepts {@code arg} (null = any reference type);
     * of several, the most specific parameter type wins (a covariant bridge's wider twin
     * dispatches to the same body). None = {@link NoSuchMethodException} naming the method.
     */
    private static Method oneArgByName(Object target, String name, Object arg)
            throws NoSuchMethodException {
        Method best = null;
        for (Method candidate : target.getClass().getMethods()) {
            if (candidate.getParameterCount() != 1 || !name.equals(candidate.getName())) {
                continue;
            }
            Class<?> param = candidate.getParameterTypes()[0];
            if (arg == null ? param.isPrimitive() : !param.isInstance(arg)) {
                continue;
            }
            if (best == null || (param != best.getParameterTypes()[0]
                    && best.getParameterTypes()[0].isAssignableFrom(param))) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new NoSuchMethodException(target.getClass().getSimpleName() + "." + name
                    + "/1 accepting " + (arg == null ? "null" : arg.getClass().getSimpleName()));
        }
        return best;
    }

    /**
     * The {@code sourceCaptain=} token of every re-key
     * verb — only a LIVE, non-pod vacated hull is reverted ({@link #revertVacatedCaptain}); a gone
     * body, a wreck (or a live-mapped body already dead) and a pod keep what they carry, the
     * client twin's skip set (null / not alive / pod).
     */
    private static String vacatedCaptain(Class<?> shipClass, Object source, boolean sourceGone,
            boolean hulkSource, boolean podSource, Object fleetMember) throws Exception {
        if (sourceGone || source == null) {
            return "kept-gone";
        }
        if (hulkSource || !Boolean.TRUE.equals(shipClass.getMethod("isAlive").invoke(source))) {
            return "kept-hulk";
        }
        if (podSource) {
            return "kept-pod";
        }
        return revertVacatedCaptain(source, fleetMember);
    }

    /** A body fielded as the command-shuttle's pilotable twin (a stranded/landed pod reads
     *  {@code isShuttlePod()} false under this spec) — never a captain-bearing hull. */
    private static boolean isPodVariant(String variantId) {
        return MatchDescriptors.BASTION_SHUTTLEPOD_VARIANT_ID.equals(variantId);
    }
}
