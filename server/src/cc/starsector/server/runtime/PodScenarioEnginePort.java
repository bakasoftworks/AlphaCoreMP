package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineDemoRoster;
import cc.starsector.server.bridge.SeatRebind;
import cc.starsector.server.bridge.TransferPodSpawner;
import cc.starsector.server.match.MatchSeatAuthority;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The live-engine {@link PodScenarioDriver.Port} — every read/mutation the
 * {@code /podtest} driver needs, expressed through the round's EXISTING seams (no new engine
 * reflection lives here): the pod lane's flight table, the pod spawner's ship handles, the
 * rebind arbiter's {@code transferBegin} (the same row a chat {@code /transfer} seeds — the serve
 * lane's drain then mints/flies/commits the pod untouched by this class), the combat binding's
 * alive/hulk floors, {@link SeatRebind}'s engine-native kill verb, and the wave/adhoc drains'
 * spawn idiom (mint → intern wire id → growth-known → RAPP row → census dirty).
 *
 * <p>WHY a separate class: the driver is a pure state machine with contract rows over a fake
 * port; this class is the one place the scenario touches the engine, so a fault here surfaces
 * as a FAIL step (every method is total — null/false/0/NaN on any fault, logged once per call
 * with a {@code podtest_port_*} row), never a throw into the serve loop.
 *
 * <p>Grading reads: the seat's live body is {@code liveShipByWireId.get(S)} — the pod
 * during a flight, the pod BODY after a strand, the target after a commit, the source after a
 * dock-back — so identity + {@code isPodBody} are read straight off that map through the
 * spawner's total readers. Wire → stable id is the inverted registry snapshot, cached and
 * refilled on a miss (the registry only grows within a round).
 *
 * <p>Sim-thread only (the driver is pumped inline after the transfer lane's pump).
 *
 * <p>Public ONLY so the contract rows ({@code tests} package) can reach the pure
 * {@link #seatAnchorOrNull} seam; the constructor stays
 * package-private — the serve loop is the sole live caller.
 */
public final class PodScenarioEnginePort implements PodScenarioDriver.Port {

    /** The target's variant — the first one the engine registry resolves; cheap
     * vanilla frigates so the kill is instant and the wreck small. The stock demo id is the
     * fall-through (always resolvable on a descriptor serve). */
    static final List<String> TARGET_VARIANT_PREFERENCE = List.of(
            "lasher_Standard", "wolf_Assault", "kite_Standard", "hound_Standard");
    static final String TARGET_VARIANT_FALLBACK = "mudskipper2_Hellbore";
    /** The target's offset from the seat body along x (su); the sign alternates per
     * spawn so T2 never lands on T1's hulk. */
    static final float TARGET_OFFSET_SU = 1800f;
    static final float TARGET_FACING = 90f;
    /** The emp FOE stands ~400 su along +x from the
     * seat and faces back at it (180°) so its emitters bear; hostile side = 1 − seatSide. */
    static final float EMP_FOE_OFFSET_SU = 400f;
    static final float EMP_FOE_FACING = 180f;

    private final TransferPodLane podLane;
    private final TransferPodSpawner podSpawner;
    private final SeatRebindArbiter rebindArbiter;
    private final Map<Integer, Long> seatWireIdBySeatId;
    private final MatchSeatAuthority authority;
    private final EngineCombatBinding binding;
    private final ShipNetworkIdRegistry stableIdRegistry;
    private final PilotIntentDatagramServer door;
    private final Set<String> growthKnownKeys;
    private final List<byte[]> roundRosterAppendFrames;
    private final CommandTransferServeLane transferLane;
    private final int round;

    private final Map<Long, String> stableByWire = new HashMap<>();
    private float spawnSign = 1f;
    private String targetVariant;

    PodScenarioEnginePort(TransferPodLane podLane, TransferPodSpawner podSpawner,
            SeatRebindArbiter rebindArbiter, Map<Integer, Long> seatWireIdBySeatId,
            MatchSeatAuthority authority, EngineCombatBinding binding,
            ShipNetworkIdRegistry stableIdRegistry, PilotIntentDatagramServer door,
            Set<String> growthKnownKeys, List<byte[]> roundRosterAppendFrames,
            CommandTransferServeLane transferLane, int round) {
        this.podLane = Objects.requireNonNull(podLane, "podLane");
        this.podSpawner = Objects.requireNonNull(podSpawner, "podSpawner");
        this.rebindArbiter = Objects.requireNonNull(rebindArbiter, "rebindArbiter");
        this.seatWireIdBySeatId = Objects.requireNonNull(seatWireIdBySeatId, "seatWireIdBySeatId");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.stableIdRegistry = Objects.requireNonNull(stableIdRegistry, "stableIdRegistry");
        this.door = Objects.requireNonNull(door, "door");
        this.growthKnownKeys = growthKnownKeys; // nullable: engineBornGrowth off
        this.roundRosterAppendFrames =
                Objects.requireNonNull(roundRosterAppendFrames, "roundRosterAppendFrames");
        this.transferLane = Objects.requireNonNull(transferLane, "transferLane");
        this.round = round;
    }

    // ------------------------------------------------------------------ seat reads

    private Object seatBodyOrNull(int seatId) {
        Long wire = seatWireIdBySeatId.get(seatId);
        return wire == null ? null : RealEngineDemoRoster.liveShipForWireIdOrNull(wire);
    }

    /**
     * The seat's WRECK when the live wire map no longer carries the seat (the
     * real-engine f2 FAIL: {@code seat_mount_loss cause=HULL_DEATH} drops wire S from
     * {@code liveShipByWireId} the same tick, so every seat read above goes null while the hulk
     * is still in play). Wire → stable through the registry SNAPSHOT — never the port's
     * {@link #stableByWire} cache: a commit re-keys the seat's wire onto the new hull
     * ({@code TransferRekeyHook}) and a cached row would resolve the PREVIOUS hull — then the
     * hulk off {@code getShips()} (a hulk stays listed until it fractures away). Null when no
     * wreck is left; rare (once per f2 spawn), so the snapshot walk is fine.
     */
    private Object seatWreckOrNull(int seatId) {
        return seatAnchorOrNull(null, seatWireIdBySeatId.get(seatId), stableIdRegistry::snapshot,
                podSpawner::resolveShipOrNull);
    }

    /**
     * The anchor resolution as a PURE seam over injected inputs
     * (the instance path needs the whole serve wiring, so the rows pin THIS):
     * the seat's staging/readiness ANCHOR is its live-mapped body when the wire map still
     * carries it (the snapshot is then never taken, the resolver never consulted), else the
     * wreck the registry SNAPSHOT keys under the seat's wire (the fix — a snapshot,
     * never a cached wire→stable row, because a commit re-keys the wire onto the new hull),
     * else null. Never throws: a null wire / null snapshot / null-valued row / resolver miss all
     * answer null. Rows: {@code PodScenarioDriverContractRows} row 16.
     */
    public static Object seatAnchorOrNull(Object liveBodyOrNull, Long wire,
            Supplier<Map<String, Long>> registrySnapshot,
            Function<String, Object> resolveShipOrNull) {
        if (liveBodyOrNull != null) {
            return liveBodyOrNull;
        }
        if (wire == null) {
            return null;
        }
        Map<String, Long> snapshot = registrySnapshot.get();
        if (snapshot == null) {
            return null;
        }
        for (Map.Entry<String, Long> e : snapshot.entrySet()) {
            if (e.getValue() != null && e.getValue().longValue() == wire.longValue()) {
                return resolveShipOrNull.apply(e.getKey());
            }
        }
        return null;
    }

    @Override
    public boolean seatReady(int seatId) {
        Long wire = seatWireIdBySeatId.get(seatId);
        if (wire == null || !authority.occupiedSeatIds().contains(seatId)) {
            return false;
        }
        Object body = RealEngineDemoRoster.liveShipForWireIdOrNull(wire);
        if (body != null && podSpawner.isAlive(body)) {
            return true;
        }
        // A dead seat whose wreck still lingers may launch its shuttle FROM the hulk;
        // the seat body is then no longer live-mapped — resolve the wreck
        // (the live map drops the seat AT death, so `body` is null here) and ask the
        // binding.
        Object wreck = body == null ? seatWreckOrNull(seatId) : body;
        String seatStable = wreck == null ? null : podSpawner.stableIdOrNull(wreck);
        return seatStable != null && binding.isSeatHulkInPlay(seatStable);
    }

    @Override
    public String seatBodyStableOrNull(int seatId) {
        return podSpawner.stableIdOrNull(seatBodyOrNull(seatId));
    }

    @Override
    public boolean seatBodyIsPod(int seatId) {
        return podSpawner.isPodBody(seatBodyOrNull(seatId));
    }

    @Override
    public boolean seatBodyAlive(int seatId) {
        Object body = seatBodyOrNull(seatId);
        return body != null && podSpawner.isAlive(body);
    }

    // ------------------------------------------------------------------ target lifecycle

    @Override
    public long spawnTargetOrZero(int seatId, String label, long tick) {
        // f2 stages the target beside the seat's WRECK (the seat is dead by design).
        Object body = seatBodyOrNull(seatId);
        if (body == null) {
            body = seatWreckOrNull(seatId);
        }
        float[] loc = body == null ? null : podSpawner.locationOrNull(body);
        if (loc == null) {
            System.out.println("npeer-serve podtest_port_spawn_denied seat=" + seatId
                    + " reason=no_seat_body_location round=" + round + " tick=" + tick);
            return 0L;
        }
        int side = RealEngineDemoRoster.seatSideOrNeg(seatId);
        if (side < 0) {
            System.out.println("npeer-serve podtest_port_spawn_denied seat=" + seatId
                    + " reason=no_seat_side round=" + round + " tick=" + tick);
            return 0L;
        }
        String variant = targetVariant();
        float x = loc[0] + spawnSign * TARGET_OFFSET_SU;
        float y = loc[1];
        spawnSign = -spawnSign;
        try {
            RealEngineDemoRoster.WaveDeployResult spawned =
                    RealEngineDemoRoster.midDeployAtRest(side, variant, label, x, y, TARGET_FACING);
            long wire = stableIdRegistry.networkIdFor(spawned.stableId());
            if (growthKnownKeys != null) {
                growthKnownKeys.add(spawned.stableId()); // our own RAPP row below, never growth-announced
            }
            int crPerMille = Math.max(0, Math.min(1000, Math.round(spawned.deployCr() * 1000f)));
            PersistentPilotIntentCombatServer.fanRosterAppendChunks(door,
                    List.of(new RosterAppendFrameCodec.AppendRow(wire, side, variant, label,
                            crPerMille, 0L, RosterAppendFrameCodec.BAY_NONE, "",
                            spawned.atRest())), roundRosterAppendFrames); // midDeployAtRest
            transferLane.markCensusDirty();
            stableByWire.put(wire, spawned.stableId());
            System.out.println("npeer-serve podtest_target_spawn seat=" + seatId + " side=" + side
                    + " variant=" + variant + " wire_id=" + wire + " stable_id="
                    + spawned.stableId() + " at=" + x + "," + y + " round=" + round
                    + " tick=" + tick);
            return wire;
        } catch (IOException | RuntimeException failure) {
            System.out.println("npeer-serve podtest_port_spawn_failed seat=" + seatId
                    + " variant=" + variant + " err=" + failure + " round=" + round
                    + " tick=" + tick);
            return 0L;
        }
    }

    // A near-clone of spawnTargetOrZero above —
    // same mint -> intern wire -> growth-known -> RAPP row -> census-dirty idiom
    // (RealEngineDemoRoster.midDeployAtRest) — but the foe is HOSTILE (side = 1 − seatSide),
    // posed at a fixed +400su / 180° so its emitters bear on the seat, and the variant is caller-
    // supplied (the token's empFoeVariantOrNull()) rather than the target-preference roll.
    @Override
    public long spawnEmpFoeOrZero(int seatId, String variantId, String label, long tick) {
        Object body = seatBodyOrNull(seatId);
        if (body == null) {
            body = seatWreckOrNull(seatId);
        }
        float[] loc = body == null ? null : podSpawner.locationOrNull(body);
        if (loc == null) {
            System.out.println("npeer-serve podtest_port_spawn_denied seat=" + seatId
                    + " reason=no_seat_body_location kind=emp round=" + round + " tick=" + tick);
            return 0L;
        }
        int seatSide = RealEngineDemoRoster.seatSideOrNeg(seatId);
        if (seatSide < 0) {
            System.out.println("npeer-serve podtest_port_spawn_denied seat=" + seatId
                    + " reason=no_seat_side kind=emp round=" + round + " tick=" + tick);
            return 0L;
        }
        int foeSide = 1 - seatSide;
        float x = loc[0] + EMP_FOE_OFFSET_SU;
        float y = loc[1];
        try {
            RealEngineDemoRoster.WaveDeployResult spawned = RealEngineDemoRoster.midDeployAtRest(
                    foeSide, variantId, label, x, y, EMP_FOE_FACING);
            long wire = stableIdRegistry.networkIdFor(spawned.stableId());
            if (growthKnownKeys != null) {
                growthKnownKeys.add(spawned.stableId()); // our own RAPP row below, never growth-announced
            }
            int crPerMille = Math.max(0, Math.min(1000, Math.round(spawned.deployCr() * 1000f)));
            PersistentPilotIntentCombatServer.fanRosterAppendChunks(door,
                    List.of(new RosterAppendFrameCodec.AppendRow(wire, foeSide, variantId, label,
                            crPerMille, 0L, RosterAppendFrameCodec.BAY_NONE, "",
                            spawned.atRest())), roundRosterAppendFrames); // midDeployAtRest
            transferLane.markCensusDirty();
            stableByWire.put(wire, spawned.stableId());
            System.out.println("npeer-serve podtest_emp_foe_spawn seat=" + seatId + " side="
                    + foeSide + " variant=" + variantId + " wire_id=" + wire + " stable_id="
                    + spawned.stableId() + " at=" + x + "," + y + " round=" + round
                    + " tick=" + tick);
            return wire;
        } catch (IOException | RuntimeException failure) {
            System.out.println("npeer-serve podtest_port_spawn_failed seat=" + seatId
                    + " variant=" + variantId + " kind=emp err=" + failure + " round=" + round
                    + " tick=" + tick);
            return 0L;
        }
    }

    private String targetVariant() {
        if (targetVariant == null) {
            for (String candidate : TARGET_VARIANT_PREFERENCE) {
                if (RealEngineDemoRoster.variantResolvable(candidate)) {
                    targetVariant = candidate;
                    break;
                }
            }
            if (targetVariant == null) {
                targetVariant = TARGET_VARIANT_FALLBACK;
            }
        }
        return targetVariant;
    }

    private Object targetShipOrNull(long targetWireId) {
        String stable = stableForWireOrNull(targetWireId);
        return stable == null ? null : podSpawner.resolveShipOrNull(stable);
    }

    @Override
    public boolean targetAlive(long targetWireId) {
        String stable = stableForWireOrNull(targetWireId);
        return stable != null && binding.isTransferTargetAlive(stable);
    }

    @Override
    public boolean holdTarget(long targetWireId) {
        Object ship = targetShipOrNull(targetWireId);
        return ship != null && podSpawner.holdStill(ship);
    }

    @Override
    public String beginOrDeny(int seatId, long targetWireId, long tick) {
        try {
            SeatRebindArbiter.TransferBegin begin = rebindArbiter.transferBegin(
                    seatId, targetWireId, "podtest target", tick);
            return begin.granted() ? null : begin.replyLine();
        } catch (RuntimeException failure) {
            return "begin threw " + failure;
        }
    }

    // ------------------------------------------------------------------ kills

    private boolean kill(Object ship, String site) {
        if (ship == null) {
            return false;
        }
        try {
            return SeatRebind.forceKillShip(ship, site);
        } catch (RuntimeException failure) {
            System.out.println("npeer-serve podtest_port_kill_failed site=" + site
                    + " err=" + failure + " round=" + round);
            return false;
        }
    }

    @Override
    public boolean killTarget(long targetWireId) {
        return kill(targetShipOrNull(targetWireId), "podtest_target");
    }

    @Override
    public boolean killFlightSource(int seatId) {
        return kill(podLane.sourceShipOrNull(seatId), "podtest_source");
    }

    @Override
    public boolean killSeatBody(int seatId) {
        Long wire = seatWireIdBySeatId.get(seatId);
        if (wire == null) {
            return false;
        }
        try {
            return SeatRebind.forceKillLiveShipForWireId(wire);
        } catch (RuntimeException failure) {
            System.out.println("npeer-serve podtest_port_kill_failed site=podtest_seat seat="
                    + seatId + " err=" + failure + " round=" + round);
            return false;
        }
    }

    // ------------------------------------------------------------------ lane reads

    @Override
    public boolean hasFlight(int seatId) {
        return podLane.hasFlight(seatId);
    }

    @Override
    public boolean hasPod(int seatId) {
        return podLane.hasPod(seatId);
    }

    @Override
    public float podDistanceOrNaN(int seatId, long targetWireId) {
        String podStable = podLane.podStableIdOrNull(seatId);
        Object pod = podStable == null ? null : podSpawner.resolveShipOrNull(podStable);
        return podSpawner.distanceOrNaN(pod, targetShipOrNull(targetWireId));
    }

    @Override
    public int podSpawns() {
        return podLane.podSpawns();
    }

    /** The round's transfer lane recorded the seat in
     * THIS tick's TRUE-vacate drain ({@link CommandTransferServeLane#seatVacatedThisPump}); no lock. */
    @Override
    public boolean seatVacatedThisPump(int seatId) {
        return transferLane.seatVacatedThisPump(seatId);
    }

    // ------------------------------------------------------------------ despawn

    /**
     * Despawn the unit an admin has selected with R-hover:
     * remove a NON-SEAT hull by wire id through the engine's own {@code removeObject} — the
     * {@link TransferPodSpawner#removeShipOrFalse} primitive, a VANISH, never a kill: no hulk, no
     * explosion, so the despawn classifier publishes DEPARTED (a healthy removal,
     * {@code DespawnReasonEvidence}) and every client evicts the replica corpse-free. Refusals are
     * constant receipt texts plus the digits (never an echo): a seat's wire (every seat's FIXED id
     * — a transferred-into hull is a seat id too), a live transfer pod's body, an id the registry
     * does not carry, a body the engine no longer lists, an engine throw. The wire → stable walk
     * is a FRESH registry snapshot, never {@link #stableByWire} (a transfer commit re-keys a seat's
     * wire onto the new hull and the cache would resolve the previous one) — a per-verb cost.
     * Policy: any non-seat ship, wave hulls included (an admin already skips
     * waves) — EXCEPT a station module or a fighter (the designed
     * module refusal never shipped; a module / wing member is owned by its parent hull and
     * {@code removeObject} under a live parent is unexercised engine behaviour — refused, and a
     * kind read the engine cannot answer refuses too, fail-closed). Sim thread only (the operator
     * switch); fail-soft — an admin typo never kills the serve. The decision is the pure
     * {@link #despawnByWire(String, DespawnSeams)} over six seams so a contract row
     * ({@code DespawnByWireContractRows}) pins every branch without an engine.
     */
    public String despawnByWire(String digits) {
        return despawnByWire(digits, new DespawnSeams() {
            @Override
            public boolean isSeatWire(long wire) {
                return seatWireIdBySeatId.containsValue(wire);
            }

            @Override
            public Map<String, Long> registrySnapshotOrNull() {
                return stableIdRegistry.snapshot();
            }

            @Override
            public boolean isPodStable(String stable) {
                for (Integer seatId : seatWireIdBySeatId.keySet()) {
                    if (stable.equals(podLane.podStableIdOrNull(seatId))) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public Object resolveShipOrNull(String stable) {
                return podSpawner.resolveShipOrNull(stable);
            }

            @Override
            public Boolean isModuleOrFighterOrNull(Object ship) {
                return podSpawner.isModuleOrFighterOrNull(ship);
            }

            @Override
            public boolean remove(Object ship) {
                return podSpawner.removeShipOrFalse(ship, "despawn");
            }
        });
    }

    /** The six engine/registry reads {@link #despawnByWire(String)} decides over — an interface so
     * the law is contract-testable with stubs. */
    public interface DespawnSeams {
        /** Any seat's FIXED wire id (a transferred-into hull is a seat id too). */
        boolean isSeatWire(long wire);

        /** A FRESH registry snapshot (stable id → wire id), or null when none. */
        Map<String, Long> registrySnapshotOrNull();

        /** The stable id names a seat's live transfer pod body. */
        boolean isPodStable(String stable);

        /** The live engine ship for the stable id, or null when the engine no longer lists it. */
        Object resolveShipOrNull(String stable);

        /** True = a station module / fighter (owned by a parent), false = a free hull, null =
         * the engine could not be asked. */
        Boolean isModuleOrFighterOrNull(Object ship);

        /** The engine's own {@code removeObject}; false = it threw (already logged). */
        boolean remove(Object ship);
    }

    /** The pure decision behind {@link #despawnByWire(String)}: every refusal is a constant text
     * plus the digits, in this order — bad id, a player's ship, no ship, a transfer pod, not on
     * the field, a module or fighter (or an unanswerable kind read), the engine refusing. */
    public static String despawnByWire(String digits, DespawnSeams seams) {
        long wire;
        try {
            wire = Long.parseLong(digits);
        } catch (NumberFormatException bad) {
            return "despawn refused: bad id";
        }
        if (wire <= 0L) {
            return "despawn refused: bad id";
        }
        if (seams.isSeatWire(wire)) {
            return "despawn refused: #" + wire + " is a player's ship";
        }
        String stable = null;
        Map<String, Long> snapshot = seams.registrySnapshotOrNull();
        if (snapshot != null) {
            for (Map.Entry<String, Long> e : snapshot.entrySet()) {
                if (e.getValue() != null && e.getValue().longValue() == wire) {
                    stable = e.getKey();
                    break;
                }
            }
        }
        if (stable == null) {
            return "despawn refused: no ship #" + wire;
        }
        if (seams.isPodStable(stable)) {
            return "despawn refused: #" + wire + " is a transfer pod";
        }
        Object ship = seams.resolveShipOrNull(stable);
        if (ship == null) {
            return "despawn refused: #" + wire + " is not on the field";
        }
        Boolean owned = seams.isModuleOrFighterOrNull(ship);
        if (owned == null || owned) {
            return "despawn refused: #" + wire + " is a module or fighter";
        }
        return seams.remove(ship)
                ? "despawned #" + wire
                : "despawn failed: engine refused #" + wire + " (see the serve log)";
    }

    @Override
    public String stableForWireOrNull(long wireId) {
        String cached = stableByWire.get(wireId);
        if (cached != null) {
            return cached;
        }
        for (Map.Entry<String, Long> e : stableIdRegistry.snapshot().entrySet()) {
            stableByWire.put(e.getValue(), e.getKey());
        }
        return stableByWire.get(wireId);
    }
}
