package cc.starsector.server.runtime;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * The pending adhoc foe-spawn queue behind {@code /spawnfoe} + the operator
 * {@code spawn-foe} verb — canonical variant ids only (validated UPSTREAM: the chat lane
 * canonicalizes at the door against the boot debug catalogue; the operator file is the
 * owner-trusted house lane), one ENTRY per deposited command (count kept aggregate).
 * SIM-THREAD-ONLY by design: the operator-verb switch and the (c2) pre-advance wave drain
 * share the serve thread, so there is NO lock here — never touch this off the sim thread (chat
 * deposits ride {@code AdminWaveQueue} and land on the sim thread first).
 *
 * <p>Cap {@value #MAX_PENDING} pending foes aggregate, drop-NEWEST remainder with a loud line
 * (a typo storm or paste burst must never bank minutes of spawns); drain order FIFO per foe.
 * Spawn pacing/execution stays the caller's: the drain spawns at most the wave stagger's
 * leftover per-frame budget (D6) and records outcomes back here.
 *
 * <p>Honest receipts (the reply law applied to {@code SPAWN_FOE}): each entry
 * carries its depositing session ({@code receiptSessionId}; 0 = file/GUI mint, no receipt
 * lane) and its per-entry outcome counters; {@link #recordSpawned}/{@link #recordFailed}
 * return the entry the moment it EXHAUSTS (every foe accounted) so the serve loop mails ONE
 * receipt with the real outcome ({@code door.sendAdminReceipt}) — the door sends no inline
 * ack. Receipt TEXTS live here as pure builders so contract rows pin them beside the queue
 * they ride (the {@code AdminWaveQueue} precedent). This class stays door-free: it hands the
 * exhausted entry back; delivery is the caller's.
 */
public final class AdhocFoeSpawner {

    /** Aggregate pending cap AND the per-command count cap — {@link ChatCommands#SPAWNFOE_MAX_COUNT}
     * IS this constant (one command may fill the whole queue;
     * a count above the queue's free room drops the remainder with the queue-full receipt); a
     * constant by design (no knob). */
    public static final int MAX_PENDING = 32;

    /** The cluster spacing law for a pose-placed entry with
     * count &gt; 1 — {@code d = max(}{@value #CLUSTER_SPACING_RADII}{@code  x the FIRST spawned
     * unit's engine collision radius, }{@value #CLUSTER_SPACING_FLOOR}{@code )}. Why: every unit of
     * a posed entry used to spawn AT the one deposited point; overlapping hulls were shoved apart
     * or killed by the engine's collision resolve, so a live run showed "only 1". The
     * floor keeps a radius-0 read (a contract fake / an unread engine getter) from stacking units. */
    public static final float CLUSTER_SPACING_RADII = 2.4f;
    public static final float CLUSTER_SPACING_FLOOR = 60.0f;

    /** Refusal receipt — the serve has no wave-spawn hook (the {@code spawn_foe_refused
     * reason=no_hook} row's chat-visible half; previously silent to the admin). */
    public static final String SPAWN_UNAVAILABLE =
            "foe spawn unavailable on this serve (no spawn lane)";

    /** The map clamp for an explicit spawn pose — inside
     * ±({@code mapHalf} + this margin) on both axes; the engine's own out-of-bounds withdraw
     * would otherwise eat a hull placed past the border the moment it spawned. */
    public static final float POSE_MAP_MARGIN = 1000.0f;

    /** One deposited command: accepted count + depositing session + drain outcomes
     * (+ the optional explicit {@code @x,y} pose, null = wave edge). */
    public static final class Entry {
        private final String variantId;
        private final int count;
        private final long receiptSessionId;
        private final float[] pose;
        private final int side;
        private final boolean officer;
        private int issued;
        private int spawned;
        private int failed;
        /** Hulls of this entry the drain ACTUALLY fielded with the requested
         * captain (the 5-arg attach reported a captain back) — the receipt's honesty counter. */
        private int officered;
        /** The cluster spacing {@code d}, set from the FIRST spawned unit's collision
         * radius ({@link #clusterSpacing}); 0 until unit 0 reached the engine. */
        private float spacing;

        private Entry(String variantId, int count, long receiptSessionId, float[] poseOrNull,
                int side, boolean officer) {
            this.variantId = variantId;
            this.count = count;
            this.receiptSessionId = receiptSessionId;
            this.pose = poseOrNull == null ? null : poseOrNull.clone();
            this.side = side;
            this.officer = officer;
        }

        /** true when the row was toggled / typed {@code officer} — the drain fields
         *  every hull of this entry with the boot-warmed maxed captain for its variant
         *  ({@code AdhocOfficerTable}); false = today's path (the variant-keyed table row, if
         *  any — "N/A" is NOT "bare"). */
        public boolean officer() {
            return officer;
        }

        public long receiptSessionId() {
            return receiptSessionId;
        }

        public String variantId() {
            return variantId;
        }

        /** The descriptor side the hull fields on — {@value #SIDE_FOE} (the
         * {@code /spawnfoe} default) or {@value #SIDE_FRIEND} ({@code /spawnfriend}). */
        public int side() {
            return side;
        }

        /** True for a {@code /spawnfriend} entry (side {@value #SIDE_FRIEND}). */
        public boolean friendly() {
            return side == SIDE_FRIEND;
        }

        /** The engine deploy-grid facing for this entry's side (side 0 faces up, side 1
         * down — {@code RealEngineDemoRoster.midDeployCore}'s edge-slot law), the drain's
         * argument to {@link #spawnPoseForUnit}. */
        public float sideFacing() {
            return side == SIDE_FRIEND ? 90.0f : 270.0f;
        }

        /** The deposited {@code {x, y}} pose or null (wave-edge placement). */
        public float[] pose() {
            return pose == null ? null : pose.clone();
        }

        /** The BASE wave-hook pose {@code {x, y, facing}} for this entry (unit 0's pose), or null
         * when the entry carries no pose. */
        public float[] spawnPoseOrNull(float facing) {
            return pose == null ? null : new float[] {pose[0], pose[1], facing};
        }

        /** The drain's one call — the wave-hook pose for the
         * NEXT unit (k = {@link #nextUnit()}, the spawned count, so a failed unit's slot is
         * re-used, never a hole): k = 0 is {@link #spawnPoseOrNull} verbatim (count 1 stays
         * byte-identical); k &ge; 1 = base + {@link #ringOffset}{@code (k, spacing)}, clamped by
         * the ONE map law ({@link #clampInsideMap}). Null pose = null (the wave-edge slot law);
         * a non-finite {@code mapHalf} cannot clamp, so it keeps the base. Why here, not in the
         * drain: k and d both live on this entry, so the drain's call shape stays one line and
         * cannot mis-key the unit index. */
        public float[] spawnPoseForUnit(float facing, float mapHalf) {
            float[] base = spawnPoseOrNull(facing);
            if (base == null || spawned == 0 || !Float.isFinite(mapHalf)) {
                return base;
            }
            float[] offset = ringOffset(spawned, spacing);
            return clampInsideMap(new float[] {base[0] + offset[0], base[1] + offset[1], facing},
                    mapHalf);
        }

        /** The unit index the next spawn attempt fills (= units spawned so far). */
        public int nextUnit() {
            return spawned;
        }

        /** The accepted foe count of this entry. */
        public int count() {
            return count;
        }

        /** The cluster spacing {@code d} (0 until the first unit spawned). */
        public float spacing() {
            return spacing;
        }

        /** The {@code adhoc_foe_spawn} row's
         * {@code " unit=k/count spacing=d"} tail for the NEXT unit, read BEFORE the spawn
         * (the record bumps k): {@code spacing=-} for unit 0 (placed at the base, no d used)
         * and for a pose-less entry (the wave-edge slots); else d {@code %.1f}. */
        public String unitLogText() {
            String d = pose == null || spawned == 0 ? "-"
                    : String.format(java.util.Locale.ROOT, "%.1f", spacing);
            return " unit=" + spawned + "/" + count + " spacing=" + d;
        }

        /** {@code x,y} for log rows / receipts ({@code -} when absent). */
        public String poseText() {
            return pose == null ? "-" : formatPose(pose);
        }

        /** The exhaustion receipt — real outcome, per the D6 texts ("spawned Nx <id>" /
         * "spawned K of Nx <id> (spawn failed)"), + " at x,y" when a pose was deposited;
         * a friendly entry reads "spawned friendly Nx <id>" (so
         * the chat reader must see which side the hull went to). Contract rows pin the shapes.
         * The real-outcome rule: the captain tag reports what
         * was FIELDED, never the request flag — {@code (officer)} only when every spawned hull got
         * the requested captain, {@code (officer N/A)} when none did (variant not in the
         * boot-warmed table / the row guard dropped the spec / the attach reported no captain),
         * {@code (officer on K)} for a split. */
        public String receipt() {
            String at = pose == null ? "" : " at " + formatPose(pose);
            String who = side == SIDE_FRIEND ? "friendly " : "";
            String captain = ""; //
            if (officer) {
                captain = officered == 0 ? " (officer N/A)"
                        : officered == spawned ? " (officer)"
                        : " (officer on " + officered + ")";
            }
            if (failed == 0) {
                return "spawned " + who + count + "x " + variantId + captain + at;
            }
            return "spawned " + who + spawned + " of " + count + "x " + variantId + captain + at
                    + " (spawn failed)";
        }
    }

    /** The two sides an entry may field on — {@code /spawnfoe} = the descriptor's
     * side 1 (the AI foes), {@code /spawnfriend} = side 0 (the players' side). */
    public static final int SIDE_FOE = 1;
    public static final int SIDE_FRIEND = 0;

    /** {@code x,y} with the grammar's own precision (≤ 3 decimals, no exponent, Locale.ROOT). */
    static String formatPose(float[] pose) {
        return trimZeros(String.format(java.util.Locale.ROOT, "%.3f", pose[0])) + ","
                + trimZeros(String.format(java.util.Locale.ROOT, "%.3f", pose[1]));
    }

    private static String trimZeros(String fixed) {
        int end = fixed.length();
        while (end > 0 && fixed.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && fixed.charAt(end - 1) == '.') {
            end--;
        }
        return fixed.substring(0, end);
    }

    /** true when {@code pose = {x, y[, ...]}} lies inside
     * ±({@code mapHalf} + {@link #POSE_MAP_MARGIN}) on both axes (finite only). */
    public static boolean poseInsideMap(float[] pose, float mapHalf) {
        if (pose == null || pose.length < 2 || !Float.isFinite(pose[0])
                || !Float.isFinite(pose[1]) || !Float.isFinite(mapHalf)) {
            return false;
        }
        float limit = poseLimit(mapHalf);
        return Math.abs(pose[0]) <= limit && Math.abs(pose[1]) <= limit;
    }

    /** The ONE map-clamp limit ±({@code |mapHalf|} + {@link #POSE_MAP_MARGIN}) — shared by the
     * {@link #poseInsideMap} refusal and the {@link #clampInsideMap} cluster clamp. */
    private static float poseLimit(float mapHalf) {
        return Math.abs(mapHalf) + POSE_MAP_MARGIN;
    }

    /** {@code pose} with x, y clamped to the
     * {@link #poseInsideMap} limit (other components copied) — a cluster unit offset past the
     * border lands ON the limit instead of an engine out-of-bounds withdraw. A new array. */
    public static float[] clampInsideMap(float[] pose, float mapHalf) {
        float limit = poseLimit(mapHalf);
        float[] clamped = pose.clone();
        clamped[0] = Math.max(-limit, Math.min(limit, pose[0]));
        clamped[1] = Math.max(-limit, Math.min(limit, pose[1]));
        return clamped;
    }

    /** The cluster spacing {@code d} for a first-unit engine
     * collision radius — {@code max(CLUSTER_SPACING_RADII x r, CLUSTER_SPACING_FLOOR)}; a
     * negative / non-finite radius reads as 0 (the floor). */
    public static float clusterSpacing(float collisionRadius) {
        float r = Float.isFinite(collisionRadius) && collisionRadius > 0f ? collisionRadius : 0f;
        return Math.max(CLUSTER_SPACING_RADII * r, CLUSTER_SPACING_FLOOR);
    }

    /** The hex-ring walk offset {@code {dx, dy}} of cluster
     * unit {@code k} at spacing {@code d}: k = 0 is the origin; ring r &ge; 1 holds 6r slots at
     * radius r&middot;d, slot angle = 360&deg;&middot;slot/(6r) + 30&deg; on even rings (the
     * 0&deg;/30&deg; per-ring alternation). Adjacent slots on a ring sit &ge; d apart (chord
     * 2rd&middot;sin(30&deg;/r) &ge; d) and rings are d apart radially, so no two units of one
     * command overlap at d &ge; 2.4 collision radii. k &le; {@link #MAX_PENDING} in practice. */
    public static float[] ringOffset(int k, float d) {
        if (k <= 0) {
            return new float[] {0f, 0f};
        }
        int ring = 1;
        int first = 1;
        while (k >= first + 6 * ring) {
            first += 6 * ring;
            ring++;
        }
        double deg = 360.0d * (k - first) / (6 * ring) + (ring % 2 == 0 ? 30.0d : 0.0d);
        double rad = Math.toRadians(deg);
        double radius = (double) ring * d;
        return new float[] {(float) (radius * Math.cos(rad)), (float) (radius * Math.sin(rad))};
    }

    /** Immediate queue-full refusal receipt — the dropped remainder was previously silent to
     * chat; a partial accept still gets its exhaustion receipt for the accepted K later. */
    public static String queueFullReceipt(String variantId, int accepted, int requested) {
        return queueFullReceipt(variantId, accepted, requested, false);
    }

    /** The side-naming twin — a friendly drop reads "friendly spawn dropped". */
    public static String queueFullReceipt(String variantId, int accepted, int requested,
            boolean friendly) {
        if (accepted == 0) {
            return (friendly ? "friendly" : "foe") + " spawn dropped (queue full): " + requested
                    + "x " + variantId;
        }
        return "queued " + accepted + " of " + requested + "x " + variantId
                + " (queue full) - remainder dropped";
    }

    private final ArrayDeque<Entry> pending = new ArrayDeque<Entry>();
    private Entry lastIssued;
    private int enqueuedTotal;
    private int droppedTotal;
    private int spawnedTotal;
    private int failedTotal;

    /** Queue {@code count} foes of {@code variantId} for {@code receiptSessionId} (0 = no
     * receipt lane); past {@value #MAX_PENDING} aggregate pending the NEWEST remainder drops
     * with one loud line (never a throw — an admin verb is not a wire fault). Returns the
     * DROPPED count so the caller can mail the immediate queue-full receipt (D6). */
    public int enqueue(String variantId, int count, long receiptSessionId) {
        return enqueue(variantId, count, receiptSessionId, null);
    }

    /** The pose-carrying twin — {@code poseOrNull} = the validated
     * {@code {x, y}} (grammar + map clamp are the caller's), null = wave-edge placement. */
    public int enqueue(String variantId, int count, long receiptSessionId, float[] poseOrNull) {
        return enqueue(variantId, count, receiptSessionId, poseOrNull, SIDE_FOE);
    }

    /** The side-carrying form — {@code side} = {@link #SIDE_FOE} ({@code /spawnfoe})
     * or {@link #SIDE_FRIEND} ({@code /spawnfriend}); the ONE queue serves both (the 32 cap is
     * aggregate across sides — a friendly burst banks against the foe budget and vice versa). */
    public int enqueue(String variantId, int count, long receiptSessionId, float[] poseOrNull,
            int side) {
        return enqueue(variantId, count, receiptSessionId, poseOrNull, side, false);
    }

    /** The officer-carrying form — {@code officer} = the row's {@code officer} toggle /
     *  the typed keyword (see {@link Entry#officer()}); every pre-1393 caller = false. */
    public int enqueue(String variantId, int count, long receiptSessionId, float[] poseOrNull,
            int side, boolean officer) {
        Objects.requireNonNull(variantId, "variantId");
        if (poseOrNull != null && poseOrNull.length < 2) {
            throw new IllegalArgumentException("pose must carry {x, y}");
        }
        if (side != SIDE_FOE && side != SIDE_FRIEND) {
            throw new IllegalArgumentException("side must be 0 or 1, got " + side);
        }
        int accepted = Math.min(count, Math.max(0, MAX_PENDING - pendingCount()));
        int dropped = count - accepted;
        if (accepted > 0) {
            pending.addLast(new Entry(variantId, accepted, receiptSessionId, poseOrNull, side,
                    officer));
            enqueuedTotal += accepted;
        }
        if (dropped > 0) {
            droppedTotal += dropped;
            System.out.println("npeer-serve adhoc_foe_queue_full variant=" + variantId
                    + " side=" + side + " officer=" + officer + " dropped=" + dropped
                    + " pending=" + pendingCount());
        }
        return dropped;
    }

    public boolean hasPending() {
        return pendingCount() > 0;
    }

    /** Next id FIFO, or null when idle — the caller spawns it and records the outcome (the
     * strictly-serial poll→record discipline keys the outcome to the issuing entry). */
    public String pollNext() {
        Entry entry = pollNextEntry();
        return entry == null ? null : entry.variantId;
    }

    /** {@link #pollNext} returning the issuing ENTRY (id + pose) — the
     * drain's form; same serial poll→record discipline. */
    public Entry pollNextEntry() {
        for (Entry entry : pending) {
            if (entry.issued < entry.count) {
                entry.issued++;
                lastIssued = entry;
                return entry;
            }
        }
        return null;
    }

    /** One drained id reached the engine (the D6 fail-soft try's success arm) WITHOUT the
     * requested captain (or none was requested). Returns the EXHAUSTED entry (receipt due) or
     * null while foes remain. */
    public Entry recordSpawned() {
        return recordSpawned(false);
    }

    /** {@link #recordSpawned()} with the drain's honest word on the captain —
     * {@code officered} = the 5-arg attach reported the requested captain back on THIS hull
     * ({@code WaveSpawnResult.officerOrNull() != null} under an {@code officer} entry). Feeds
     * {@link Entry#receipt()}'s tag. */
    public Entry recordSpawned(boolean officered) {
        return recordSpawned(officered, 0f);
    }

    /** {@link #recordSpawned(boolean)} + the spawned hull's
     * engine collision radius ({@code WaveSpawnResult.collisionRadius()}, 0 = unread); the
     * entry's FIRST success fixes its cluster spacing ({@link #clusterSpacing}). */
    public Entry recordSpawned(boolean officered, float collisionRadius) {
        return record(true, officered, collisionRadius);
    }

    /** One drained id threw at spawn (logged {@code adhoc_spawn_fail} by the caller). Returns
     * the EXHAUSTED entry (receipt due) or null while foes remain. */
    public Entry recordFailed() {
        return record(false, false, 0f);
    }

    private Entry record(boolean succeeded, boolean officered, float collisionRadius) {
        Entry entry = lastIssued;
        if (entry == null) {
            throw new IllegalStateException("record without a preceding pollNext");
        }
        lastIssued = null;
        if (succeeded) {
            // Unit 0's radius fixes the cluster spacing d
            if (entry.spawned == 0) {
                entry.spacing = clusterSpacing(collisionRadius);
            }
            entry.spawned++;
            spawnedTotal++;
            if (officered) {
                entry.officered++;
            }
        } else {
            entry.failed++;
            failedTotal++;
        }
        if (entry.spawned + entry.failed == entry.count) {
            pending.remove(entry);
            return entry;
        }
        return null;
    }

    /** Foes queued and not yet issued to a spawn attempt (aggregate across entries). */
    public int pendingCount() {
        int total = 0;
        for (Entry entry : pending) {
            total += entry.count - entry.issued;
        }
        return total;
    }

    public int enqueuedCount() {
        return enqueuedTotal;
    }

    public int droppedCount() {
        return droppedTotal;
    }

    public int spawnedCount() {
        return spawnedTotal;
    }

    public int failedCount() {
        return failedTotal;
    }
}
