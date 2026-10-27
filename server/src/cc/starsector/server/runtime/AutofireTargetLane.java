package cc.starsector.server.runtime;

import cc.starsector.server.protocol.GameplayEdgeEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The autofire-TARGET sync lane's server half: a per-round,
 * per-tick poll+diff over SEAT ships' autofire plugins that emits {@code AUTOFIRE_TARGET}
 * gameplay edge events (GPEE schema v9) when the server's engine-accepted target pick for
 * a weapon mount CHANGES (including →cleared), plus a slow keepalive re-emit
 * ({@link #KEEPALIVE_EVERY_TICKS}) while a non-null pick is held — bounding UDP loss
 * staleness with the lane's OWN transport (checked against the no-backstop rule:
 * this repairs the lane's delivery, not a missing lane).
 *
 * <p>Why it exists (a strict re-pair of a recorded run): the client's LOCAL
 * autofire pick diverges from the server's on the same engaged cluster — strict pairs
 * &gt;45° were 15/15 DIFFERENT ship, and server-only discharges concentrate on autofire
 * mounts — so the guns' aim rays split at the PICK, not the aim math. This is the
 * per-weapon DOWN mirror of the per-ship {@code applyShipTarget}
 * designation lane. Client apply is leg 2; at v9 the client decodes + counts only.
 *
 * <p>Poll seam: NO engine push seam exists — the pick is a private field
 * on the engine's autofire plugin, repaced by {@code AutofireManagerV2}'s
 * {@code IntervalTracker(0.4,0.7)} with no callback — so this lane polls the PUBLIC API
 * ({@code ShipAPI.getWeaponGroupsCopy()} / {@code WeaponGroupAPI.getAIPlugins()}
 * index-parallel with {@code getWeaponsCopy()} — the {@code FoeAutofireGateSampler}
 * idiom — then {@code AutofireAIPlugin.getTargetShip()}), all runtime reflection off
 * interface {@link Method}s (the product compile sees no game jars). Mount ordinal =
 * the ship's {@code getAllWeapons()} index — the SAME per-ship space every other wire
 * mount ordinal rides (ECB mount tier; spaces identical across repoints).
 *
 * <p>Identity is fail-closed: ships enumerate through the binding's
 * {@code positionShipIdByEngineShip()} → {@code lastEntityIdByPositionShipId} maps (the
 * discharge-detector idiom — zero new binding surface); a target that does not resolve to
 * a wire id (fighter, fresh spawn, roster gap) is SKIPPED + counted
 * ({@link #skipUnresolvedCount()}), never emitted as a guessed id. Event-lane home over
 * AWSS: +8 B × N autofire mounts on the ship tier would grow the
 * single-entity datagram floor for 0.4–0.7 s-cadence state; the event lane costs
 * bytes only on change. All reads fail-soft: a throw never reaches the serve loop, and
 * {@link #FAIL_DISARM_AT} consecutive-failure polls disarm the lane for the round
 * (the {@code FoeAutofireGateSampler} law).
 */
final class AutofireTargetLane {

    /** Re-emit a held non-null pick every ~1 s of sim (60 ticks) — UDP staleness bound. */
    static final int KEEPALIVE_EVERY_TICKS = 60;

    /** Failed observe() calls before the lane disarms for the round (fail-soft law). */
    static final int FAIL_DISARM_AT = 8;

    private static final String P = "npeer-serve autofire_target_lane";

    private static final class MountState {
        long lastSentTargetWireId = GameplayEdgeEvent.TARGET_NONE;
        long lastEmitTick = Long.MIN_VALUE;
    }

    private final Map<Long, Map<Integer, MountState>> stateByShipWireId = new HashMap<>();

    private Method getAllWeapons;
    private Method getWeaponGroupsCopy;
    private Method getWeaponsCopy;
    private Method getAIPlugins;
    private Method getTargetShip;

    private boolean dead;
    private int failures;
    private int emitted;
    private int keepalives;
    private int skipUnresolved;

    /**
     * Per-tick poll+diff; never throws. Returns this tick's {@code AUTOFIRE_TARGET}
     * events (empty when no pick changed and no keepalive is due). Ships enumerate from
     * {@code positionShipIdByEngineShip} filtered to {@code seatIdByWireId} keys; both
     * maps come from the serve loop's existing per-tick reads.
     */
    List<GameplayEdgeEvent> observe(
            long simTick,
            Map<Long, Integer> seatIdByWireId,
            Map<Object, Long> positionShipIdByEngineShip,
            Map<Long, Long> entityIdByPositionShipId) {
        if (dead || seatIdByWireId.isEmpty() || positionShipIdByEngineShip == null
                || positionShipIdByEngineShip.isEmpty() || entityIdByPositionShipId == null) {
            return List.of();
        }
        try {
            if (getTargetShip == null) {
                armReflection();
            }
            List<GameplayEdgeEvent> out = null;
            for (Map.Entry<Object, Long> byShip : positionShipIdByEngineShip.entrySet()) {
                Long wireId = entityIdByPositionShipId.get(byShip.getValue());
                if (wireId == null || !seatIdByWireId.containsKey(wireId)) {
                    continue;
                }
                out = pollShip(simTick, wireId, byShip.getKey(),
                        positionShipIdByEngineShip, entityIdByPositionShipId, out);
            }
            failures = 0;
            return out == null ? List.of() : out;
        } catch (Throwable t) {
            failures++;
            if (failures == 1) {
                System.out.println(P + " observe_fail tick=" + simTick + " err=" + t);
            }
            if (failures >= FAIL_DISARM_AT) {
                dead = true;
                System.out.println(P + " disarmed reason=failures tick=" + simTick);
            }
            return List.of();
        }
    }

    /**
     * Drop all diff state for one seat ship — called at the {@code seat_variant_repoint}
     * rebind seam: the fresh hull's mount ordinals mean new weapons, so
     * the next poll re-diffs from the cleared baseline and re-emits live picks.
     */
    void resetShip(long shipWireId) {
        stateByShipWireId.remove(shipWireId);
    }

    /** Cumulative pick-change emits this round (the {@code gpee_af_target=} meter). */
    int emitCount() {
        return emitted;
    }

    /** Cumulative keepalive re-emits this round ({@code gpee_af_target_keepalive=}). */
    int keepaliveCount() {
        return keepalives;
    }

    /** Cumulative unresolvable-target skips ({@code gpee_af_target_skip_unresolved=}). */
    int skipUnresolvedCount() {
        return skipUnresolved;
    }

    private List<GameplayEdgeEvent> pollShip(
            long simTick,
            long shipWireId,
            Object engineShip,
            Map<Object, Long> positionShipIdByEngineShip,
            Map<Long, Long> entityIdByPositionShipId,
            List<GameplayEdgeEvent> out) throws Exception {
        List<?> allWeapons = (List<?>) getAllWeapons.invoke(engineShip);
        if (allWeapons == null || allWeapons.isEmpty()) {
            return out;
        }
        IdentityHashMap<Object, Integer> mountOrdinalByWeapon =
                new IdentityHashMap<>(allWeapons.size() * 2);
        for (int i = 0; i < allWeapons.size(); i++) {
            mountOrdinalByWeapon.put(allWeapons.get(i), i);
        }
        Map<Integer, MountState> shipState =
                stateByShipWireId.computeIfAbsent(shipWireId, k -> new HashMap<>());
        for (Object group : (List<?>) getWeaponGroupsCopy.invoke(engineShip)) {
            List<?> weapons = (List<?>) getWeaponsCopy.invoke(group);
            List<?> plugins = (List<?>) getAIPlugins.invoke(group);
            for (int i = 0; i < weapons.size() && i < plugins.size(); i++) {
                Object plugin = plugins.get(i);
                Integer mountIndex = plugin == null
                        ? null : mountOrdinalByWeapon.get(weapons.get(i));
                if (mountIndex == null) {
                    continue;
                }
                Object target = getTargetShip.invoke(plugin);
                long currentTargetWireId;
                if (target == null) {
                    currentTargetWireId = GameplayEdgeEvent.TARGET_NONE;
                } else {
                    Long targetPositionId = positionShipIdByEngineShip.get(target);
                    Long targetWireId = targetPositionId == null
                            ? null : entityIdByPositionShipId.get(targetPositionId);
                    if (targetWireId == null) {
                        skipUnresolved++;
                        continue;
                    }
                    currentTargetWireId = targetWireId;
                }
                MountState state = shipState.computeIfAbsent(mountIndex, k -> new MountState());
                boolean changed = currentTargetWireId != state.lastSentTargetWireId;
                boolean keepaliveDue = !changed && currentTargetWireId > 0L
                        && simTick - state.lastEmitTick >= KEEPALIVE_EVERY_TICKS;
                if (!changed && !keepaliveDue) {
                    continue;
                }
                if (out == null) {
                    out = new ArrayList<>(4);
                }
                out.add(GameplayEdgeEvent.autofireTarget(
                        simTick, shipWireId, mountIndex, currentTargetWireId));
                if (changed) {
                    emitted++;
                } else {
                    keepalives++;
                }
                state.lastSentTargetWireId = currentTargetWireId;
                state.lastEmitTick = simTick;
            }
        }
        return out;
    }

    /** Resolve the poll {@link Method}s off the PUBLIC API interfaces once (never off
     *  concrete engine classes — interface methods stay invokable on any impl without
     *  accessibility games). */
    private void armReflection() throws Exception {
        Class<?> shipApi = Class.forName("com.fs.starfarer.api.combat.ShipAPI");
        Class<?> groupApi = Class.forName("com.fs.starfarer.api.combat.WeaponGroupAPI");
        Class<?> autofireApi = Class.forName("com.fs.starfarer.api.combat.AutofireAIPlugin");
        getAllWeapons = shipApi.getMethod("getAllWeapons");
        getWeaponGroupsCopy = shipApi.getMethod("getWeaponGroupsCopy");
        getWeaponsCopy = groupApi.getMethod("getWeaponsCopy");
        getAIPlugins = groupApi.getMethod("getAIPlugins");
        getTargetShip = autofireApi.getMethod("getTargetShip");
    }
}
