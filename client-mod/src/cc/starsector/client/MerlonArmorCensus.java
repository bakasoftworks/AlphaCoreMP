package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Per-Merlon ARMOR census: makes the reported "drones look extremely
 * damaged at load" chew MACHINE-readable, so the at-mint vs early-window discrimination
 * (uncapped pre-bind damage vs a mint-state/registry cause) no longer needs human eyes.
 *
 * <p>The subjects are CLIENT-minted roster replicas (correcting an earlier claim — "engine-born by
 * the replica station's drone system, zero client mint sites" was FALSE): the server launches
 * the drones at the station hull center and serves them as roster entities; the client mints
 * each via the bots-bind spawn path at the wire pose (0,0) ({@code predict bots bind: entity 34
 * <- SPAWNED ... variant=merlon_Standard at (0.0,0.0)}). The census still SCANS rather than
 * hooking the bind lane — the scan is cheaper than a callback and grades ANY merlon regardless
 * of mint path. Armor read = the {@link DamageWitness} armorTotal idiom — the one repo-wide
 * client armor read: cell sum, try-wrapped, NaN fail-soft.
 *
 * <p>What each line class discriminates, read at debrief:
 * <ul>
 *   <li>{@code FIRST} with {@code frac} well under 1.0 at small {@code t} — the drone MINTS
 *       already chewed: mint-state/registry class, not combat damage.</li>
 *   <li>{@code FIRST} full then {@code DROP} lines under {@code hull=1.00} — real local armor
 *       damage in the early window with hull reconciled back but armor never repaired
 *       (the damage cap installs at bind; armor has no reconcile lane).</li>
 *   <li>Load-2 {@code FIRST} lines lower than load-1's (a mission re-pick) —
 *       registry contamination across loads.</li>
 * </ul>
 *
 * <p>{@link CrClockCensus} discipline verbatim: diagnostic only, always-on (no knob), capped,
 * fail-silent ({@code dead} latch — never break the frame), zero behavior. Render-thread only.
 */
final class MerlonArmorCensus {

    /** The census window in active frames from the first advance (~60 s at 60 fps): the chew is
     *  an AT-LOAD look, so the early window is where the answer is. */
    private static final int WINDOW_FRAMES = 3600;
    /** Hard line cap across all classes; counters continue past it. */
    private static final int LINE_CAP = 160;
    /** Per-drone DROP-line budget (continuous chew would otherwise spam; drops keep counting). */
    private static final int DROP_LINES_PER_DRONE = 8;
    /** Armor-sum delta below this is float noise, not damage. */
    private static final float DROP_EPSILON = 0.25f;
    /** First-sight fraction under this counts as "minted chewed" in the summary. */
    private static final float CHEWED_FRAC = 0.90f;

    /** Per-drone state: [firstSum, firstFrac, lastSum, dropCount, dropTotal, dropLines, riseCount]. */
    private final Map<ShipAPI, float[]> tracked = new IdentityHashMap<ShipAPI, float[]>();

    private int frames;
    private int lines;
    private int dronesSeen;
    private int chewedAtFirstSight;
    private int dropEvents;
    private int riseEvents;
    private boolean summaryDone;
    private boolean dead;

    /** Per active (unpaused) frame, feed or not — the chew predates the first authoritative frame. */
    void advance(float amount) {
        if (dead || summaryDone) {
            return;
        }
        try {
            frames++;
            if (frames > WINDOW_FRAMES) {
                emitSummary("window-end");
                return;
            }
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null) {
                return;
            }
            for (ShipAPI ship : engine.getShips()) {
                if (ship == null || !isMerlon(ship)) {
                    continue;
                }
                float sum = armorTotal(ship);
                if (Float.isNaN(sum)) {
                    continue;
                }
                float[] rec = tracked.get(ship);
                if (rec == null) {
                    float frac = armorFrac(ship, sum);
                    tracked.put(ship, new float[] {sum, frac, sum, 0f, 0f, 0f, 0f});
                    dronesSeen++;
                    if (!Float.isNaN(frac) && frac < CHEWED_FRAC) {
                        chewedAtFirstSight++;
                    }
                    log("FIRST id=" + idOf(ship) + " t=" + fmt(frames / 60f)
                            + " armor=" + fmt(sum) + " frac=" + fmt(frac)
                            + " hull=" + fmt(safeHull(ship))
                            + " owner=" + safeOwner(ship)
                            + " pos=" + safePos(ship));
                    continue;
                }
                float last = rec[2];
                if (sum < last - DROP_EPSILON) {
                    rec[3]++;
                    rec[4] += last - sum;
                    dropEvents++;
                    if (rec[5] < DROP_LINES_PER_DRONE) {
                        rec[5]++;
                        log("DROP id=" + idOf(ship) + " t=" + fmt(frames / 60f)
                                + " armor=" + fmt(last) + "->" + fmt(sum)
                                + " delta=" + fmt(last - sum)
                                + " hull=" + fmt(safeHull(ship))
                                + (rec[5] >= DROP_LINES_PER_DRONE
                                        ? " [drop-line cap for this drone; counters continue]" : ""));
                    }
                } else if (sum > last + DROP_EPSILON) {
                    rec[6]++;
                    riseEvents++;
                }
                rec[2] = sum;
            }
        } catch (Throwable t) {
            dead = true; // census is diagnostic only — never break the frame
            PilotWatchLog.info("cc-merlon-census DEAD: " + t);
        }
    }

    /** Round boundary (MRST / -Rounds 2): summarize the open window, then re-census fresh —
     *  the post-MRST re-mint path is the in-watch load-twice arm. */
    void resetRound() {
        if (dead) {
            return;
        }
        try {
            if (!summaryDone && frames > 0) {
                emitSummary("round-reset");
            }
            tracked.clear();
            frames = 0;
            dronesSeen = 0;
            chewedAtFirstSight = 0;
            dropEvents = 0;
            riseEvents = 0;
            summaryDone = false;
        } catch (Throwable t) {
            dead = true;
            PilotWatchLog.info("cc-merlon-census DEAD: " + t);
        }
    }

    private void emitSummary(String where) {
        summaryDone = true;
        for (Map.Entry<ShipAPI, float[]> e : tracked.entrySet()) {
            float[] rec = e.getValue();
            log("PER-DRONE id=" + idOf(e.getKey())
                    + " first=" + fmt(rec[0]) + " (frac=" + fmt(rec[1]) + ")"
                    + " last=" + fmt(rec[2])
                    + " drops=" + (int) rec[3] + " dropTotal=" + fmt(rec[4])
                    + " rises=" + (int) rec[6]);
        }
        // The summary line is the debrief grep target; the two counters on it are THE
        // discriminator: chewed_first>0 = mint-state class, drop_events>0 = early-window damage.
        PilotWatchLog.info("cc-merlon-census SUMMARY [" + where + "]: drones_seen=" + dronesSeen
                + " chewed_first=" + chewedAtFirstSight
                + " drop_events=" + dropEvents
                + " rise_events=" + riseEvents
                + " window_frames=" + Math.min(frames, WINDOW_FRAMES)
                + " lines=" + lines);
    }

    private static boolean isMerlon(ShipAPI ship) {
        try {
            return ship.getHullSpec() != null && "merlon".equals(ship.getHullSpec().getHullId());
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** The DamageWitness armorTotal idiom: armor-grid cell sum, best-effort, NaN on any failure. Package-private
     * so that {@code SourcedMissileEndLane.presentOwnShieldHit} snapshots the own grid with it. */
    static float armorTotal(ShipAPI ship) {
        try {
            ArmorGridAPI grid = ship.getArmorGrid();
            float[][] cells = grid != null ? grid.getGrid() : null;
            if (cells != null) {
                float sum = 0f;
                for (float[] col : cells) {
                    for (float v : col) {
                        sum += v;
                    }
                }
                return sum;
            }
        } catch (Throwable ignore) {
            // armor read is best-effort
        }
        return Float.NaN;
    }

    /** Sum over (cells × per-cell max); NaN when the grid is unreadable. */
    private static float armorFrac(ShipAPI ship, float sum) {
        try {
            ArmorGridAPI grid = ship.getArmorGrid();
            float[][] cells = grid != null ? grid.getGrid() : null;
            float max = grid != null ? grid.getMaxArmorInCell() : Float.NaN;
            if (cells != null && cells.length > 0 && max > 0f) {
                int n = cells.length * cells[0].length;
                return n > 0 ? sum / (n * max) : Float.NaN;
            }
        } catch (Throwable ignore) {
        }
        return Float.NaN;
    }

    private static String idOf(ShipAPI ship) {
        return Integer.toHexString(System.identityHashCode(ship));
    }

    private static float safeHull(ShipAPI ship) {
        try {
            return ship.getHullLevel();
        } catch (Throwable ignore) {
            return Float.NaN;
        }
    }

    private static String safeOwner(ShipAPI ship) {
        try {
            return String.valueOf(ship.getOwner());
        } catch (Throwable ignore) {
            return "?";
        }
    }

    private static String safePos(ShipAPI ship) {
        try {
            return fmt(ship.getLocation().x) + "," + fmt(ship.getLocation().y);
        } catch (Throwable ignore) {
            return "?";
        }
    }

    private void log(String body) {
        if (lines >= LINE_CAP) {
            return;
        }
        lines++;
        PilotWatchLog.info("cc-merlon-census " + body
                + (lines == LINE_CAP ? " [line cap; counters continue]" : ""));
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
