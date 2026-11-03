package cc.starsector.client.luna;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fs.starfarer.api.combat.CombatEngineAPI;

/**
 * The kit's served-settings restore state: the {@link Snapshot} of every LunaLib setting a served match
 * overwrote (the player's own value per key), and the restore-pending mark a match exit raises.
 *
 * <p>WHY a mark and not a restore: LunaLib's settings panel reads its settings map every frame on the game
 * thread, so only the game thread may write the player's values back. An exit that runs OFF the game thread
 * (the stale-stream watchdog on the writer thread, the late-attach hold watchdog) only calls
 * {@link #markRestorePending}; the next game-thread drain point (the title screen's first frame through
 * {@link LunaSyncDrainPlugin} among them) clears it with {@link #drainOnGameThread}.
 *
 * <p>WHY the snapshot also records its engine: an exit that raises NO mark (the ESC exit from a connect-fail
 * hold-open window, where no session and no watchdog exist) would otherwise leave the served values live on the
 * title screen until the next match's init. {@link #restoreIfForeign} lets every other engine's drain point restore
 * a snapshot applied under an engine that is not its own.
 *
 * <p>Single claimant: {@link #claimSnapshotOrNull} is a {@code getAndSet(null)}, so of two drain points that
 * race exactly one gets the snapshot, and no snapshot is restored twice.
 *
 * <p>Classloader-B legal by construction: java.base only ({@code java.util} + {@code java.util.concurrent.atomic}),
 * the game API's {@code CombatEngineAPI} type (held and compared by reference only, never called), plus the restore
 * body in {@link LunaSyncApply}, reached only when a mark is drained or a foreign snapshot is found; no java.io, no
 * java.nio.file, no reflection.
 *
 *
 */
public final class LunaSyncState {

    /**
     * One overwritten setting: the LunaLib mod id + field id it lives under and the player's own value
     * before the served value replaced it ({@code oldValue} may be null: the key held no value).
     */
    public record Entry(String modId, String fieldId, Object oldValue) {
    }

    /**
     * Every setting one served match overwrote, keyed to that match's id, and the combat engine the apply ran
     * under ({@code engine}; compared by reference only, null when the apply had none). {@code entries} is copied
     * unmodifiable at construction (the snapshot crosses threads through {@link #publish} /
     * {@link #claimSnapshotOrNull}; a later write to the caller's list cannot reach it).
     */
    public record Snapshot(String matchId, List<Entry> entries, CombatEngineAPI engine) {
        public Snapshot {
            entries = List.copyOf(entries);
        }
    }

    private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>();
    private static volatile boolean restorePending;

    private LunaSyncState() {
    }

    /** Raise the restore mark; safe from ANY thread (it never touches LunaLib's map). */
    public static void markRestorePending() {
        restorePending = true;
    }

    /** One volatile read: whether a restore mark is waiting for a game-thread drain point. */
    public static boolean restorePending() {
        return restorePending;
    }

    /**
     * Game thread only: drain a waiting restore mark; true when a mark was cleared, false when none was raised
     * (nothing else runs then). With a mark, {@link LunaSyncApply#restoreOnGameThread} clears it BEFORE any restore
     * work runs (a mark raised during the drain survives for the next drain point instead of being lost), then
     * claims the snapshot, fetches each mod's map entry again and puts back every snapshotted key.
     *
     *
     */
    public static boolean drainOnGameThread() {
        if (!restorePending) {
            return false;
        }
        LunaSyncApply.restoreOnGameThread();
        return true;
    }

    /**
     * Game thread only: restore a snapshot applied under ANOTHER engine than {@code mine}; true when one was
     * restored. ONE volatile read of the published snapshot; with none, or one applied under {@code mine}, nothing
     * else runs and nothing is touched (a match engine's own drain point never undoes its own apply). Otherwise
     * {@link LunaSyncApply#restoreOnGameThread} claims it (single claimant, so a racing drain point restores it at
     * most once) and puts the player's values back.
     *
     *
     */
    public static boolean restoreIfForeign(CombatEngineAPI mine) {
        Snapshot s = SNAPSHOT.get();
        if (s == null || s.engine() == mine) {
            return false;
        }
        LunaSyncApply.restoreOnGameThread();
        return true;
    }

    /** Game thread only (the restore's first step): clear the mark; true when one was raised. */
    static boolean clearMarkOnGameThread() {
        boolean was = restorePending;
        restorePending = false;
        return was;
    }

    /** Take the published snapshot, leaving none: the ONE claimant restores it; every other caller gets null. */
    public static Snapshot claimSnapshotOrNull() {
        return SNAPSHOT.getAndSet(null);
    }

    /**
     * Publish the snapshot of the values a served apply just overwrote, only into an EMPTY slot
     * ({@code compareAndSet(null, snapshot)}); false = an earlier snapshot is still unclaimed. WHY never an
     * overwrite: an unclaimed snapshot means a missed restore, and it holds the player's own values; replacing it
     * would make the next restore put back the earlier match's served values instead. The caller restores (claims)
     * any earlier snapshot first and, on false, folds the earlier one in ({@link LunaSyncApply}).
     *
     *
     */
    public static boolean publish(Snapshot snapshot) {
        return SNAPSHOT.compareAndSet(null, snapshot);
    }
}
