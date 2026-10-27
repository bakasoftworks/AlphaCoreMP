package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The CLIENT half of the Shrouded Dweller census
 * (twin of the server's {@code DwellerCensusProbe}, same columns): once per second over
 * {@code engine.getShips()}, every {@link DwellerHullLaw#isDweller} hull — alive, hulk, dead-not-hulk,
 * disabled / force-disabled mounts over the alive hulls' non-decorative weapons, per-mount disabled
 * EDGES against the previous sample, and deaths since the previous sample — so the fidelity rig's
 * shrouded pair reads the vanilla TWIN window and the served window through the SAME code and diffs
 * both against the server row.
 *
 * <p>ALWAYS-ON, no knob (the {@link OwnMoteCensusLane} discipline: self-paced, capped, fail-silent):
 * the vanilla twin runs this same client jar with no session, so a knob would have to be plumbed
 * into two launchers for a lane that costs one ship-list walk per second. Deaths are keyed by engine
 * object identity: a dweller that vanishes from the list counts as a death, because vanilla's
 * no-corpse law ({@code setHulkChanceOverride(0f)}) removes
 * the body outright — {@code hulks=} is therefore vanilla law 0 and any non-zero value is the
 * corpse class; {@code mountEdges} on a Maelstrom is the creator's own per-frame firing
 * stagger, expected non-zero on BOTH sides.
 *
 * <p>Presentation-side, read-only, public {@code com.fs.starfarer.api} only (the mod classloader's
 * deny: no reflection, no non-API engine ref). Rows ride the sidelog
 * ({@code predict dweller census …}), capped at {@link #ROW_CAP}; a serverless twin synthesizes
 * {@code tick=} from the engine clock (the {@code OwnMoteCensusLane} clock idiom) so the rig's
 * tick-bucketed table aligns both reels. Teardown totals ride {@link #meterTokens()}.
 */
final class DwellerCensusLane {
    static final float PERIOD_SEC = 1f;
    static final int ROW_CAP = 2400;

    private float acc;
    private int rows;
    private int edges;
    private int deathsTotal;
    private int mountEdgesTotal;
    /** Rows on which at least one dweller HULK was present (vanilla law 0 — the D7 tell). */
    private int hulkRows;
    /** Samples past {@link #ROW_CAP} whose row was suppressed. */
    private int rowsCapped;
    private boolean disarmed;
    /** The previous sample's state signature
     *  (alive, hulks, dead, disabled, forced, plugins, linger) as ints — replaces a per-sample
     *  concatenated String; {@code hasLastSig} stands in for the old {@code lastSig == null}. */
    private final int[] lastSig = new int[7];
    private boolean hasLastSig;
    /** Dweller ships that were ALIVE (isAlive && !isHulk) at the previous sample, by identity. */
    private Map<ShipAPI, Boolean> prevAlive = new IdentityHashMap<>();
    /** Per non-decorative mount of an alive dweller: its {@code isDisabled()} at the previous sample. */
    private Map<WeaponAPI, Boolean> prevMountDisabled = new IdentityHashMap<>();
    /** The double-buffer partners of prevAlive /
     *  prevMountDisabled — sample() clears and fills these, then swaps, so the 1 Hz render-thread
     *  census allocates no fresh map per sample. */
    private Map<ShipAPI, Boolean> nowAlive = new IdentityHashMap<>();
    private Map<WeaponAPI, Boolean> nowMounts = new IdentityHashMap<>();

    /** Once per render frame from the plugin's advance body; self-paced to {@link #PERIOD_SEC}. */
    void tick(PredictReconcileCombatPlugin plugin, float amount) {
        // No ROW_CAP early-out — sampling (and every
        // total on meterTokens) continues past the cap; sample() suppresses only the row emission.
        if (disarmed || plugin == null || plugin.engine == null) {
            return;
        }
        acc += amount;
        if (acc < PERIOD_SEC) {
            return;
        }
        acc = 0f;
        try {
            sample(plugin);
        } catch (Throwable t) {
            disarmed = true;
            PilotWatchLog.info("predict dweller census DISARMED: " + t);
        }
    }

    private void sample(PredictReconcileCombatPlugin plugin) {
        CombatEngineAPI engine = plugin.engine;
        // Serverless twin: no snapshot tick ever arrives — synthesize one from the engine clock.
        long tick = plugin.lastSnapshotSimTick;
        if (tick == Long.MIN_VALUE) {
            tick = Math.round(engine.getTotalElapsedTime(false) * ServerTickRate.ticksPerSecond());
        }
        int alive = 0;
        int hulks = 0;
        int dead = 0;
        int mounts = 0;
        int disabled = 0;
        int forced = 0;
        int mountEdges = 0;
        // Past ROW_CAP the row is never printed,
        // so the row-only builders (sorted hull map + ids string) are skipped; the counters, the
        // death/mount-edge maps (double-buffered, cleared not reallocated) and the edge signature
        // still run so every meterTokens() total keeps counting.
        boolean capped = rows >= ROW_CAP;
        nowAlive.clear();
        nowMounts.clear();
        Map<String, Integer> hulls = capped ? null : new TreeMap<>();
        StringBuilder ids = capped ? null : new StringBuilder();
        int seen = 0;
        for (ShipAPI ship : engine.getShips()) {
            if (!DwellerHullLaw.isDweller(ship)) {
                continue;
            }
            seen++;
            char state;
            if (ship.isHulk()) {
                hulks++;
                state = 'h';
            } else if (ship.isAlive()) {
                alive++;
                state = 'a';
            } else {
                dead++;
                state = 'd';
            }
            if (!capped) {
                appendRowFields(ship, state, hulls, ids);
            }
            if (state != 'a') {
                continue;
            }
            nowAlive.put(ship, Boolean.TRUE);
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (w.isDecorative()) {
                    continue;
                }
                mounts++;
                boolean dis = w.isDisabled();
                if (dis) {
                    disabled++;
                }
                if (w.isForceDisabled()) {
                    forced++;
                }
                Boolean prev = prevMountDisabled.get(w);
                if (prev != null && prev.booleanValue() != dis) {
                    mountEdges++;
                }
                nowMounts.put(w, Boolean.valueOf(dis));
            }
        }
        int deaths = 0;
        for (ShipAPI prev : prevAlive.keySet()) {
            if (!nowAlive.containsKey(prev)) {
                deaths++;
            }
        }
        deathsTotal += deaths;
        mountEdgesTotal += mountEdges;
        // The vanilla dweller BODY is not the hull sprite (hidden) but the
        // DwellerCombatPlugin's WobblyParts, registered per ship in the engine custom-data map under
        // DwellerCombatPlugin.KEY_SHIP_MAP (the plugin removes itself only once its
        // parts faded). plugins= that map's size, linger= entries whose ship the engine no
        // longer holds alive — a served run kept a pale body ≥11 s past the tick-5544
        // removal; a linger>0 here names the plugin's parts as that body, linger=0 rules them out.
        int plugins = -1;
        int linger = 0;
        Object shipMap = engine.getCustomData().get("DwellerCombatPlugin_shipMap_key");
        if (shipMap instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) shipMap;
            plugins = m.size();
            for (Object k : m.keySet()) {
                if (k instanceof ShipAPI && !engine.isShipAlive((ShipAPI) k)) {
                    linger++;
                }
            }
        }
        // Swap the double buffers (the stale
        // prev maps become next sample's cleared now maps) instead of dropping them for the GC.
        Map<ShipAPI, Boolean> aliveSwap = prevAlive;
        prevAlive = nowAlive;
        nowAlive = aliveSwap;
        Map<WeaponAPI, Boolean> mountSwap = prevMountDisabled;
        prevMountDisabled = nowMounts;
        nowMounts = mountSwap;
        // public-client skip: a match with NO dwellers (every public fortress
        // reel today) has nothing to census — no dweller seen this sample, no death since the
        // last one, no DwellerCombatPlugin ship map (the key is minted by the first dweller
        // spawn) ⇒ no row, no edge, no cap spend. The lane otherwise burned its ROW_CAP on
        // all-zero rows in every public client's log. The rig's dweller pairs are unaffected
        // (a dweller present, dying or lingering always prints).
        // `seen` (dwellers walked) replaces
        // ids.length() as the no-dweller test (ids is null past the cap; seen==0 <=> ids empty);
        // the edge signature is compared as ints, not a concatenated String.
        if (seen == 0 && deaths == 0 && plugins < 0) {
            return;
        }
        if (hulks > 0) {
            hulkRows++;
        }
        boolean edge = sigEdge(alive, hulks, dead, disabled, forced, plugins, linger);
        if (edge) {
            edges++;
        }
        if (capped) {
            rowsCapped++; // Counters above kept; row suppressed
            return;
        }
        rows++;
        PilotWatchLog.info(String.format(Locale.ROOT,
                "predict dweller census tick=%d edge=%s alive=%d hulks=%d dead=%d disabled=%d/%d forced=%d/%d"
                        + " plugins=%d linger=%d mountEdges=%d deaths=%d deathsTotal=%d hulls={%s} ids=[%s]"
                        + (rows == ROW_CAP ? " [row cap reached; counters continue]" : ""),
                tick, edge, alive, hulks, dead, disabled, mounts, forced, mounts,
                plugins, linger, mountEdges, deaths, deathsTotal, joinCounts(hulls), ids));
    }

    /** The row-only hull-count + ids fields of one
     *  dweller — built only while rows < ROW_CAP (the printed row is their sole reader). */
    private static void appendRowFields(ShipAPI ship, char state, Map<String, Integer> hulls, StringBuilder ids) {
        hulls.merge(ship.getHullSpec() == null ? "-" : ship.getHullSpec().getHullId(), 1, Integer::sum);
        if (ids.length() > 0) {
            ids.append(',');
        }
        ids.append(ship.getId()).append('@').append(ship.getOwner()).append(':').append(state);
        // WHERE the dweller is and WHOM it hunts — the twin's tendrils died
        // off-camera (seat-follow view), so the pair's target-choice compare (served tendrils flew
        // to the seat) needs positions + the AI's ship target in the row, not a reel.
        // Format `@x,y>target` (ints, no spaces: the row reader whitespace-splits); target = the
        // hull id of getShipTarget() or '-' (a served replica has no AI: '-' is expected there).
        if (ship.getLocation() != null) {
            ids.append('@').append(Math.round(ship.getLocation().x)).append(',')
                    .append(Math.round(ship.getLocation().y));
        }
        ShipAPI tgt = ship.getShipTarget();
        ids.append('>').append(tgt == null || tgt.getHullSpec() == null ? "-" : tgt.getHullSpec().getHullId());
    }

    /** true on the first sample or when any of the
     *  seven state counts differs from the previous sample's (same semantics as the old String sig). */
    private boolean sigEdge(int alive, int hulks, int dead, int disabled, int forced, int plugins, int linger) {
        int[] s = lastSig;
        boolean edge = !hasLastSig || s[0] != alive || s[1] != hulks || s[2] != dead || s[3] != disabled
                || s[4] != forced || s[5] != plugins || s[6] != linger;
        s[0] = alive;
        s[1] = hulks;
        s[2] = dead;
        s[3] = disabled;
        s[4] = forced;
        s[5] = plugins;
        s[6] = linger;
        hasLastSig = true;
        return edge;
    }

    /** {@code k=v,k=v} with NO spaces (a whitespace-split row reader keeps the map as one token). */
    private static String joinCounts(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Meter fragment (pinned key names; rides the weapon-state meter line beside the mote census tokens). */
    String meterTokens() {
        return " predict_dweller_census_rows=" + rows
                + " predict_dweller_census_edges=" + edges
                + " predict_dweller_deaths=" + deathsTotal
                + " predict_dweller_mount_edges=" + mountEdgesTotal
                + " predict_dweller_hulk_rows=" + hulkRows
                + " predict_dweller_census_rows_capped=" + rowsCapped //
                + " predict_dweller_census_disarmed=" + disarmed;
    }
}
