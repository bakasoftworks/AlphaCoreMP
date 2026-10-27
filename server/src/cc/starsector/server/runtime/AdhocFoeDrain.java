package cc.starsector.server.runtime;

import cc.starsector.server.runtime.PersistentPilotIntentCombatServer.NPeerHooks;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * The (c2c) adhoc {@code /spawnfoe} + operator {@code spawn-foe} drain's ONE-ENTRY step — the
 * fail-soft try body carved VERBATIM out of {@code PilotServeSession.serveFrame}.
 *
 * The serve loop keeps what is the frame's: the leftover
 * per-tick spawn budget, the worst-frame note, the RAPP fan and the exhaustion receipt send.
 *
 * <p>WHY a class of its own: an admin-typed id must never kill a serve with players on, so this
 * body is the ONE place the drain's fail-soft law lives — and while it sat inline no contract row
 * could drive it. The {@code placed=} token NPE'd on every pose-less entry (a null
 * {@code Entry.pose} = the wave-edge slot, the {@code /spawnfoe <id> <n>} default) AFTER
 * {@code recordSpawned}, and the catch's {@code recordFailed} then threw
 * {@code IllegalStateException} OUT of the fail-soft block — an un-posed {@code /spawnfoe} ended
 * the serve. {@code AdhocFoeDrainContractRows} pins both halves with a fake wave hook.
 */
public final class AdhocFoeDrain {

    private AdhocFoeDrain() {}

    /**
     * Spawn ONE polled entry unit: the engine spawn (pose-aware, officered when the entry asks and
     * the boot-warmed table has a pick), the wave-hull intern, the RAPP append row into
     * {@code appendRows}, then the entry's record. Returns the EXHAUSTED entry (its receipt is due
     * to the depositor) or null while units remain. Fail-soft: an {@link IOException} or
     * {@link RuntimeException} from the spawn is logged as {@code adhoc_spawn_fail} and recorded
     * as a failed unit — never rethrown.
     *
     * @param adhocFoes the queue the entry was polled from (the strictly-serial poll→record
     *     discipline: exactly ONE record per poll)
     * @param adhocEntry the entry {@code pollNextEntry} handed back for this unit
     * @param waveSpawn the engine hook ({@code hooks.waveSpawn()}, non-null by the caller's admission)
     * @param tick the sim tick the unit lands on ({@code lastSimTick + 1})
     */
    public static AdhocFoeSpawner.Entry spawnOne(AdhocFoeSpawner adhocFoes,
            AdhocFoeSpawner.Entry adhocEntry, NPeerHooks.WaveSpawnHook waveSpawn,
            ShipNetworkIdRegistry stableIdRegistry, Set<String> growthKnownKeysOrNull,
            List<RosterAppendFrameCodec.AppendRow> appendRows, float mapHalf, int round,
            long tick) {
        String adhocVariantId = adhocEntry.variantId();
        AdhocFoeSpawner.Entry adhocExhausted = null;
        // The poll→record discipline allows ONE record per polled unit —
        // a throw AFTER recordSpawned (the placed= NPE was one) must never reach
        // recordFailed, whose IllegalStateException would leave this fail-soft block.
        boolean recorded = false;
        try {
            // An explicit `@x,y` pose (validated at
            // the grammar, map-clamped at the operator switch) places the
            // foe AT that point, no travel drive, facing the entry side's
            // deploy-grid facing (270 for side 1, 90 for side 0); null =
            // the wave edge slot as before. The entry's side
            // (1 = /spawnfoe, 0 = /spawnfriend) rides the hook AND the
            // append row (the client maps it relative to its own side).
            int adhocSide = adhocEntry.side();
            // An `officer` entry resolves its BOOT-WARMED pick (one map read;
            // never a sim-thread classify) and rides the 5-arg spawn; the RAPP row goes
            // through the ONE guard (this drain used to build
            // AppendRow.officered directly — a spec the row grammar rejects would have
            // thrown inside this catch AFTER the hull was spawned + interned = fielded
            // server-side with NO row on any seat, a hidden desync). N/A = the pre-1393
            // path verbatim (the variant-keyed table row when one exists).
            cc.starsector.server.match.AdhocOfficerFactory.Pick adhocPick =
                    adhocEntry.officer()
                            ? cc.starsector.server.platform.AdhocOfficerTable
                                    .pickOrNull(adhocVariantId)
                            : null;
            if (adhocEntry.officer() && adhocPick == null) {
                System.out.println("npeer-serve adhoc_officer_unavailable variant="
                        + adhocVariantId + " (not in the boot-warmed table -> fielding"
                        + " N/A) round=" + round + " tick=" + tick);
            }
            cc.starsector.server.match.OfficerRosterCodec.OfficerSpec adhocOfficer =
                    adhocPick == null ? null
                            : WaveOfficerRowGuard.rekeyForWireOrNull(adhocPick.spec(),
                                    adhocVariantId, "adhoc", adhocVariantId, round, tick);
            // A posed count>1 entry CLUSTERS — unit k
            // lands at base + the hex-ring offset (spacing from unit 0's collision
            // radius, map-clamped by the pose law); unit 0 = the base pose verbatim.
            // The row tail is read BEFORE the spawn (the record bumps k).
            String adhocUnitText = adhocEntry.unitLogText();
            float[] adhocPose = adhocEntry.spawnPoseForUnit(adhocEntry.sideFacing(), mapHalf);
            NPeerHooks.WaveSpawnResult spawned = adhocOfficer == null
                    ? waveSpawn.spawn(adhocSide, adhocVariantId, adhocVariantId, adhocPose)
                    : waveSpawn.spawn(adhocSide, adhocVariantId, adhocVariantId, adhocPose,
                            adhocOfficer);
            long adhocWireId = PersistentPilotIntentCombatServer.internWaveHull(
                    stableIdRegistry, growthKnownKeysOrNull, spawned, adhocVariantId);
            appendRows.add(WaveOfficerRowGuard.officeredOrBare(
                    adhocWireId, adhocSide, adhocVariantId, adhocVariantId,
                    spawned.crPerMille(), spawned.officerOrNull(), "adhoc", round, tick,
                    spawned.atRest())); // A posed adhoc hull is at rest
            // The receipt's captain tag = what was FIELDED (the 5-arg
            // attach reported the requested pick back), never the request flag
            adhocExhausted = adhocFoes.recordSpawned(
                    adhocOfficer != null && spawned.officerOrNull() != null,
                    spawned.collisionRadius());
            recorded = true;
            String adhocOfficerLabel = spawned.officerOrNull() == null ? "none"
                    : adhocOfficer != null && adhocPick != null ? adhocPick.logLabel()
                            : "table:" + spawned.officerOrNull().level;
            System.out.println("npeer-serve adhoc_foe_spawn side=" + adhocSide
                    + " variant=" + adhocVariantId
                    + " pose=" + adhocEntry.poseText() + adhocUnitText
                    // pose= is the ANCHOR on every row of a
                    // multi-unit spawn; placed= is this unit's own deposited pose — `-` for a
                    // pose-less unit (the wave-edge slot).
                    + " placed=" + (adhocPose == null ? "-" : AdhocFoeSpawner.formatPose(adhocPose))
                    + " officer=" + adhocOfficerLabel
                    + " wire_id=" + adhocWireId
                    + " stable_id=" + spawned.stableId()
                    + " pending=" + adhocFoes.pendingCount()
                    + " round=" + round + " tick=" + tick);
        } catch (IOException | RuntimeException adhocSpawnFailure) {
            if (!recorded) {
                adhocExhausted = adhocFoes.recordFailed();
            }
            // fielded=true = the hull IS in the world and recorded (only the row after the
            // record threw); false = nothing spawned for this unit.
            System.out.println("npeer-serve adhoc_spawn_fail variant="
                    + adhocVariantId + " fielded=" + recorded
                    + " err=" + adhocSpawnFailure.getMessage()
                    + " round=" + round + " tick=" + tick);
        }
        return adhocExhausted;
    }
}
