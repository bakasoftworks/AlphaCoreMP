package cc.starsector.client;

import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import org.lwjgl.util.vector.Vector2f;

import cc.starsector.client.net.ServerEntityPose;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * D-FOEARMOR per-cell armor apply lane:
 * the client half of AWSD v11 per-cell
 * armor carriage — a HullLedger-shaped shadow/bite/expiry state machine per entity, one class
 * with a {@code foe}/{@code own} instance each (the {@link ArmorReconcileLane} ownership
 * pattern: FHRL owns foe, OwnDetLane owns own).
 *
 * <p><b>Apply law:</b> per entity, {@code shadow[]} = the last server value per cell
 * (init = maxInCell — spawn default full both sides); a per-frame diff of the live grid
 * against {@code lastFrame[]} stamps LOCAL bites in SIM seconds (every engine damage path,
 * no hit-hook dependency). On a wire cell section: {@code shadow = v}; a cell with a bite
 * inside {@link #REPLAY_WINDOW_SEC} and {@code live < v} keeps the local prediction (the
 * server snapshot predates the local hit); a server value at/below local CONFIRMS the bite
 * (stamp cleared); everything else writes the server value. An un-confirmed bite past the
 * window restores to shadow (the phantom-hit class, bounded instead of permanent). Upward
 * writes batch ONE {@code syncWithArmorGridState()} + {@code syncWeaponDecalsWithArmorDamage()}
 * per entity per frame (the decal law; downward paints natively).
 *
 * <p>The law knew no hit identity (a
 * server-first hit was stripped twice; a local-first one left the union). Now a lowering section
 * write STAMPS the cell (tick + Δ); the replica cap's modify seam keys a late local copy on the
 * stamps and {@link #armReplay replays} it wire-acked (write-up, the engine strips + presents,
 * {@link #finishReplay} restores); the keep-local branch is the per-cell claim/drop LEDGER (a bite
 * pairs a live stamp upward, else claims; a drop nets the claim; claims restore 2 s after the FIRST
 * bite, paired remainders at +2G).
 *
 * <p><b>Delivery:</b> cell runs are NOT pose state — the reader's apply site
 * calls {@link #onRecord} directly per record (sections buffer here, bounded, and drain at
 * the next {@link #maintain}, where the grid is in hand). A run past THIS grid's cellCount
 * refuses the whole section per-ENTITY ({@code refused} counter, no partial apply) — the
 * grid-dim desync surface; structural faults never reach here (the reader refuses the frame).
 *
 * <p><b>Engagement:</b> the first non-empty valid section makes the entity {@code cellLive};
 * {@link #cellLive(long)} additionally requires APPLY mode and an un-failed lane — in {@code meter} mode the
 * whole-grid fold stays engaged (zero-write diagnostics must not disable the shipping
 * correction; refines the apply law's wording). Wired at the v11 wire
 * commit: knob {@link #MODE} + census row, the reader tail + the PRCP NET-thread delivery
 * (the inbox seam), and the {@code ArmorReconcileLane} cellLive fold-skip.
 *
 * <p>Whole-body fail-soft (one loud line, then inert); render-thread only; bounded
 * allocation throughout (wire-derived
 * counts are capped, drops are counted, log lines are single-line).
 */
final class ArmorCellLane {

    /** Un-confirmed local bites restore to shadow past this SIM-second window (the
     *  HullLedger {@code HULL_REPLAY_EXPIRY_SEC} value — the hull replay-expiry law, same clock). */
    static final double REPLAY_WINDOW_SEC = 2.0;
    /** Compare epsilon as a fraction of maxInCell: one u8 quant step — sub-quant jitter
     *  never reads as a bite or a divergence. */
    static final float CELL_EPS_FRAC = 1f / 255f;
    /** Wire-law mirrors ({@code AwsdSnapshotCodec} v11): structural caps a decoded section
     *  can never legally exceed — {@link Run} enforces them so a malformed section is a
     *  programming error here, never wire data (the reader refuses those frames). */
    static final int RUN_LEN_MAX = 32;
    /** Bounded-allocation caps (fail-soft: past them, drop + count, never grow; the entity
     *  cap first tries {@link #evictIdle} — a dead entity's slot, counted as {@code evicted}). */
    static final int MAX_ENTITIES = 128;
    static final int MAX_PENDING_SECTIONS_PER_ENTITY = 64;
    /** The cap-time idle-eviction horizon,
     *  SIM seconds (same clock as the bites). {@link #maintain} returns before the entity
     *  lookup for a dead ship and a despawned entity's sections stop, so an entry untouched
     *  (no admit, no maintain) past this is dead; tied to {@link #REPLAY_WINDOW_SEC} because
     *  past it every bite stamp the entry held has expired — eviction drops no live bite. */
    static final double EVICT_IDLE_SEC = REPLAY_WINDOW_SEC;
    /** Cross-thread inbox cap in sections ({@link #onRecord} appends on the NET/reader
     *  thread; {@link #maintain} drains on the render thread — the chat/fracture
     *  queue-then-drain precedent). Drop-OLDEST past it: values are absolute, later wins. */
    static final int INBOX_MAX = 256;

    /** Resolved {@code cc.predict.armorCells} mode (off|meter|apply; THROWs on other at
     *  class load — the ArmorReconcileLane knob-statics idiom; censused in
     *  {@code ClientPostureRegistry}). One knob drives BOTH instances. */
    static final String MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.armorCells",
            System.getProperty("cc.predict.armorCells", "apply"));

    /** Resolved {@code cc.predict.armorReplay} (off|meter|apply,
     *  default apply; THROWs on other at class load — the {@link #MODE} idiom) — the wire-acked REPLAY of a
     *  server-first hit ({@link #armReplay}). {@code meter} counts {@code replay_would_ack} and writes
     *  nothing. The P2 claim/drop ledger rides {@link #MODE}. The own instance never arms (own fire is
     *  client-first). */
    static final String REPLAY_MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.armorReplay", System.getProperty("cc.predict.armorReplay", "apply"));
    /** The stamp window G in server sim ticks (client−server offset p95 9, max
     *  10); a stamp is live at age {@code snapTick − dropTick} ∈ [−1, G] (−1: PRCP publishes the section
     *  before the render thread's snapshot tick moves). */
    static final int REPLAY_G_TICKS = 10;
    /** The key band (report 02 Q3: a same-size hit one cell off overlaps 0.83·B, two cells 0.57·B). */
    static final float REPLAY_KEY_BAND = 0.6f;
    /** {@code dropTick} sentinel: no stamp. */
    static final int NO_STAMP = Integer.MIN_VALUE;
    /** The late-twin watch ring size ({@link LateWatch}; 64 acked
     *  replays inside one G window would be needed to overwrite a still-watching entry) and the round WARN's
     *  minimum acked count (below it a 10 % share is noise — 2 of 19 never warns). */
    static final int LATE_RING = 64;
    static final int LATE_WARN_MIN_ACKED = 20;

    /** One decoded v11 cell run: absolute quantized values for cell ordinals
     *  {@code [startIdx, startIdx + values.length)} (ordinal = x·h + y, x the OUTER
     *  {@code float[][]} index — the server retainer's walk order). The values array is
     *  handed over: the lane may keep it, the caller must not mutate it after. */
    record Run(int startIdx, byte[] values) {
        Run {
            if (startIdx < 0 || startIdx > 0xFFFF) {
                throw new IllegalArgumentException("cell run startIdx outside u16: " + startIdx);
            }
            if (values == null || values.length < 1 || values.length > RUN_LEN_MAX) {
                throw new IllegalArgumentException("cell run len outside [1," + RUN_LEN_MAX
                        + "]: " + (values == null ? "null" : values.length));
            }
        }
    }

    private final String mode;
    private final String label;
    private final boolean meter;
    private final boolean apply;
    private final boolean replayMeter;
    private final boolean replayApply;
    /** Bumped by {@link #resetForMatchReset}: an arm minted before the reset is discarded, never restored. */
    private int epoch;
    private boolean failed;

    /** Render-thread-confined (seeded/drained only inside {@link #maintain}). */
    private final HashMap<Long, EntityCells> entities = new HashMap<>();

    /** One buffered wire section awaiting the render-thread drain. */
    private record Pending(long entityId, long tick, List<Run> runs) {
    }

    /** The NET→render hand-off (guarded by its own monitor; the only cross-thread state). */
    private final ArrayDeque<Pending> inbox = new ArrayDeque<>();
    private long inboxDropped; // guarded by the inbox monitor; folded into dropped at drain

    // Cumulative counters (persist across rounds, hull precedent; round-scoped state is
    // the entities map itself).
    private long sectionsReceived;
    private long cellsApplied;
    private long keptBites;
    private long restored;
    private long refused;
    private long wouldWrites;
    private long writes;
    private long decalBatches;
    private long dropped;
    private long evicted;
    /** Earliest sim second a cap-hit {@link #evictIdle} pass can succeed (armed by a failed pass). */
    private double evictScanNotBeforeSec = Double.NEGATIVE_INFINITY;
    private long engagedEntities;
    private float absDeltaMax;
    private double absDeltaSum;
    private long absDeltaSamples;
    /** The replay + ledger meters (the round meter line;
     *  package-visible for the verifier). hp = the engine's presented hull of acked hits; hpLive = the
     *  bound's numerator: Σ_mask max(0, liveBefore − post) + that hull. */
    long replayAcked;
    double replayAckedHp;
    double replayAckedHpLive;
    long replayWouldAck;
    long replayOrphan;
    long replayClamped;
    long replayDead;
    long replayDkFaults;
    /** Stamps debited by the PRESENTER's own strip ({@link #consumeStrip}). */
    long replayPresenterConsumed;
    long pairsClaimFirst;
    long pairsDropFirst;
    long pairsRemRestored;
    /** The future D3 K inputs (report 02 Q5 "Missing meters") — claims
     *  that left the ledger never netted against a server drop (+ Σ claim hp); bites that met a stamp aged past G
     *  (the censored tail: count, Σ min(bite, Δ), max age in ticks); the claim→drop offset (ticks) of a never-paired
     *  claim's first netting, buckets [0,2] [3,5] [6,10] [11,20] [21,40] [41,∞). Pure observation (no write, no
     *  pairing, no claim moves); cumulative across rounds (the siblings' precedent); package-visible for the verifier. */
    long claimsExpiredUnpaired;
    double claimsExpiredUnpairedHp;
    long biteOnStaleStamp;
    double biteOnStaleStampHp;
    long biteOnStaleStampAgeMax;
    final long[] claimDropOffsetHist = new long[6];
    long claimDropOffsetMax;
    /** The LIVE replay
     *  false-positive meter. An acked replay is LATE ({@code replay_acked_late}) when a later lowering section on
     *  its own mask cells — at a server tick strictly after every stamp the replay consumed, within G of its
     *  snapshot tick — sums to the key band: the client restored a hit the server had NOT yet applied, so that
     *  hit's hull/armor reaches the replica with the wire, ≤ G ticks late. {@code replicaLocalHp} = Σ hull of the
     *  replica's own non-acked local applies ({@link #noteReplicaLocalHull}; the offline grader's KEPT
     *  denominator). Cumulative across rounds (the siblings' precedent); the {@code round*} pair + flag gate the
     *  one-per-round WARN ({@link #settleLate}). */
    private long replayAckedLate;
    private double replayAckedHpLate;
    private double replicaLocalHp;
    private long roundAcked;
    private long roundLate;
    private boolean roundLateWarned;
    /** The late-twin watch ring (pre-allocated; zero allocation per hit) + the reused per-section candidate list. */
    private final LateWatch[] lateRing = LateWatch.ring(LATE_RING);
    private int lateNext;
    private final int[] lateCand = new int[LATE_RING];
    /** The frame's pairing writes went upward (the bite-ledger helper's out-flag; one decal batch). */
    private boolean ledgerUp;
    /** The last arm this lane armed (any cap's) — {@link #armReplay}'s one-open-arm guard. */
    private ReplayArm heldArm;

    private ArmorCellLane(String mode, String replayMode, String label) {
        this.mode = mode;
        this.label = label;
        this.meter = !"off".equals(mode);
        this.apply = "apply".equals(mode);
        this.replayMeter = !"off".equals(replayMode);
        this.replayApply = "apply".equals(replayMode);
    }

    /** The foe/replica instance (FHRL-owned at wiring; mode = the resolved
     *  {@code cc.predict.armorCells} value once the knob lands). */
    static ArmorCellLane foe(String mode) {
        return new ArmorCellLane(mode, REPLAY_MODE, "foe");
    }

    /** Verifier seam: the foe instance with an explicit replay mode (one JVM, all modes). */
    static ArmorCellLane foe(String mode, String replayMode) {
        return new ArmorCellLane(mode, replayMode, "foe");
    }

    /** The own-ship instance (OwnDetLane-owned at wiring — D-OWNARMOR rides the same lane). */
    static ArmorCellLane own(String mode) {
        return new ArmorCellLane(mode, "off", "own");
    }

    /**
     * Buffer one record's decoded cell section for an entity (the reader apply site's
     * per-record call — the NET/reader thread at PRCP's AWSD fold lambda; empty run lists
     * are the idle tail and never reach here). Sections cross to the render thread via the
     * bounded inbox and drain at the next {@link #maintain}, where grids are in hand.
     */
    void onRecord(long entityId, long serverSimTick, List<Run> runs) {
        if (failed || !meter || runs == null || runs.isEmpty()) {
            return;
        }
        synchronized (inbox) {
            if (inbox.size() >= INBOX_MAX) {
                inbox.pollFirst(); // drop-OLDEST: absolute values, later wins
                inboxDropped++;
            }
            inbox.addLast(new Pending(entityId, serverSimTick, runs));
        }
    }

    /** Render-thread admission of one drained section into its entity's pending deque
     *  (bounded both per-map and per-entity; drop + count past either cap — the map cap
     *  after a failed {@link #evictIdle}). {@code simNowSec} = the draining maintain's clock:
     *  a section arriving is a liveness touch. */
    private void admit(Pending p, double simNowSec) {
        long entityId = p.entityId();
        EntityCells e = entities.get(entityId);
        if (e == null) {
            if (entities.size() >= MAX_ENTITIES && !evictIdle(simNowSec)) {
                dropped++;
                return;
            }
            e = new EntityCells();
            entities.put(entityId, e);
        }
        e.lastTouchSimSec = simNowSec;
        if (e.pending.size() >= MAX_PENDING_SECTIONS_PER_ENTITY) {
            dropped++;
            return;
        }
        e.pending.addLast(p); // The section tick rides to the drain (the stamp's T_s)
        e.lastSectionTick = p.tick();
    }

    /**
     * The entity-cap eviction — runs ONLY
     * on a cap hit (never a per-frame scan). One pass, one compare per entry, picks the
     * least-recently-touched entry and evicts it when idle past {@link #EVICT_IDLE_SEC}; the
     * caller's admission takes the freed slot. Ids never re-mint within a round
     * ({@link #resetForMatchReset}); a same-id remount after eviction re-seeds as first sight
     * (spawn-full shadow), which is the fresh hull's truth. A failed pass arms
     * {@code evictScanNotBeforeSec} = oldest touch + horizon: no entry can go idle sooner
     * (touches only move forward; a full map admits nothing without an eviction), so a cap
     * full of per-frame-touched entities costs one pass per horizon (never more than one
     * ≤ MAX_ENTITIES pass per cap hit) while each blocked admission still counts
     * {@code dropped}. Allocation: the entry iterator only (no copy, no boxing).
     */
    private boolean evictIdle(double simNowSec) {
        if (simNowSec <= evictScanNotBeforeSec) {
            return false;
        }
        Map.Entry<Long, EntityCells> oldest = null;
        double oldestTouch = Double.POSITIVE_INFINITY;
        for (Map.Entry<Long, EntityCells> en : entities.entrySet()) {
            double t = en.getValue().lastTouchSimSec;
            if (t < oldestTouch) {
                oldestTouch = t;
                oldest = en;
            }
        }
        if (oldest == null) {
            return false; // empty map / all touches NaN: never evict blind, never arm the gate
        }
        if (!(simNowSec - oldestTouch > EVICT_IDLE_SEC)) {
            evictScanNotBeforeSec = oldestTouch + EVICT_IDLE_SEC;
            return false;
        }
        // NEVER CONCEAL: the evicted entry's UNAPPLIED server sections are
        // server truth lost — counted into dropped (the entry itself rides evicted; pin = verifier leg R4).
        dropped += oldest.getValue().pending.size();
        entities.remove(oldest.getKey());
        evicted++;
        return true;
    }

    /**
     * Per-frame drive for one entity (render thread; simNow = the FHRL sim-second clock,
     * threaded in at wiring): local-bite diff, buffered-section apply, phantom-bite expiry,
     * decal batch. Same call cardinality as the fold lane ({@link FoeHullReconcileLane#maintain}
     * foe / {@link OwnDetLane#maintainOwnArmor} own).
     * {@code snapTick} = the frame's server snapshot tick
     * (FHRL passes it, and OwnDetLane too; the stamp-liveness clock of the P2
     * bite pairing, the tick domain); {@code Long.MIN_VALUE} = unknown ⇒ no stamp is ever live in the diff.
     * The ONLY {@code maintain} — the tick-less overload is retired, so
     * a caller that drops the tick (the own-armor double-apply revert) fails to compile; a verifier that wants
     * the unknown-tick path passes {@code Long.MIN_VALUE} itself.
     */
    void maintain(ShipAPI ship, ServerEntityPose pose, double simNowSec, long snapTick) {
        if (failed || !meter || ship == null || pose == null || !ship.isAlive()) {
            return;
        }
        try {
            // The current-entity bound: touch the CURRENT entity before its own drain can
            // admit a new id at the cap and evict it (a re-admit hold lasts up to 180 t), which then
            // dropped it at the cap site below or re-seeded it FULL. Pin = verifier leg R5.
            EntityCells cur = entities.get(pose.entityId());
            if (cur != null) {
                cur.lastTouchSimSec = simNowSec;
            }
            // Drain the NET-thread inbox first (for ALL entities — sections land in their
            // per-entity pending even when this call's grid is briefly unavailable).
            java.util.ArrayList<Pending> drained = null;
            synchronized (inbox) {
                if (!inbox.isEmpty()) {
                    drained = new java.util.ArrayList<>(inbox);
                    inbox.clear();
                }
                dropped += inboxDropped;
                inboxDropped = 0;
            }
            if (drained != null) {
                for (Pending p : drained) {
                    admit(p, simNowSec);
                }
            }
            ArmorGridAPI grid = ship.getArmorGrid();
            float[][] cells = grid != null ? grid.getGrid() : null;
            if (cells == null || cells.length == 0 || cells[0] == null) {
                return;
            }
            float maxInCell = grid.getMaxArmorInCell();
            if (!(maxInCell > 0f)) {
                return;
            }
            int h = cells[0].length;
            int cellCount = cells.length * h;
            EntityCells e = cur != null ? cur : entities.get(pose.entityId()); // cur survived the drain: it holds the newest touch
            if (e == null) {
                if (entities.size() >= MAX_ENTITIES && !evictIdle(simNowSec)) {
                    dropped++;
                    return;
                }
                e = new EntityCells();
                entities.put(pose.entityId(), e);
            }
            e.lastTouchSimSec = simNowSec;
            if (e.shadow == null || e.shadow.length != cellCount) {
                e.seed(cellCount, maxInCell, cells, h);
            }
            float eps = maxInCell * CELL_EPS_FRAC;
            boolean upward;
            ledgerUp = false;

            if (e.pending.isEmpty()) {
                // Fast path: the steady-state frame (no wire section since the
                // last drain — the common case) fuses (a)+(c)+(d) into ONE row-major pass.
                // Exact equivalence with the sectioned path: a cell stamped in (a) is never
                // expiry-eligible in the same frame (its stamp is now), so the (c) test rides
                // the else branch; (d) reads the post-write value like the copy pass did.
                upward = steadyPass(e, cells, h, cellCount, eps, simNowSec, snapTick, grid);
            } else {
                upward = sectionedPass(e, cells, h, cellCount, eps, simNowSec, snapTick, maxInCell, grid);
            }
            upward |= ledgerUp;

            // (e) One decal batch per entity per frame with any upward write.
            if (upward) {
                ship.syncWithArmorGridState();
                ship.syncWeaponDecalsWithArmorDamage();
                decalBatches++;
            }
        } catch (Throwable t) {
            failSoft(t);
        }
    }

    /**
     * The fused steady-state pass (pending empty): per cell — local-bite diff vs last frame,
     * else phantom-bite expiry (restore to shadow past the window), then the lastFrame refresh.
     * Row-major ({@code x} outer = the {@code float[][]} outer index; ordinal {@code x·h+y}) —
     * no per-cell div/mod. Returns true when any upward write happened.
     */
    private boolean steadyPass(EntityCells e, float[][] cells, int h, int cellCount, float eps,
            double simNowSec, long snapTick, ArmorGridAPI grid) {
        boolean upward = false;
        boolean biteSeen = false;
        boolean checkBites = e.anyBite;
        double[] biteAtSec = e.biteAtSec;
        float[] lastFrame = e.lastFrame;
        float[] shadow = e.shadow;
        int i = 0;
        for (int x = 0; i < cellCount; x++) {
            float[] col = cells[x];
            for (int y = 0; y < h; y++, i++) {
                float live = col[y];
                if (live < lastFrame[i] - eps) {
                    // (a) local bite, SIM seconds — the P2 ledger: pair / claim
                    live = onBite(e, i, x, y, live, lastFrame[i] - live, eps, simNowSec, snapTick, grid, col);
                    biteSeen |= biteAtSec[i] > 0;
                } else if (checkBites && biteAtSec[i] > 0) {
                    if (simNowSec - biteAtSec[i] > REPLAY_WINDOW_SEC) {
                        expireClaim(e, i); // (c) un-confirmed past the window: restore to shadow
                        if (live < shadow[i] - eps) {
                            if (apply) {
                                grid.setArmorValue(x, y, shadow[i]);
                                writes++;
                                upward = true;
                                live = col[y];
                            } else {
                                wouldWrites++;
                            }
                            restored++;
                        }
                    } else {
                        biteSeen = true;
                    }
                }
                lastFrame[i] = live; // (d) after our writes — wire writes never read as bites
            }
        }
        e.anyBite = biteSeen;
        return upward;
    }

    /** The sectioned frame (≥1 buffered wire section): the original (a) diff, (b) drain, then
     *  a fused (c)+(d) pass. Returns true when any upward write happened. */
    private boolean sectionedPass(EntityCells e, float[][] cells, int h, int cellCount, float eps,
            double simNowSec, long snapTick, float maxInCell, ArmorGridAPI grid) {
        // (a) Local-bite diff vs last frame's values (before any wire write this frame, so
        // our own writes below never read as bites next frame either — lastFrame refreshes
        // after them). Each bite enters the P2 ledger (pair at a live stamp, else claim).
        {
            int i = 0;
            for (int x = 0; i < cellCount; x++) {
                float[] col = cells[x];
                for (int y = 0; y < h; y++, i++) {
                    if (col[y] < e.lastFrame[i] - eps) {
                        onBite(e, i, x, y, col[y], e.lastFrame[i] - col[y], eps, simNowSec, snapTick, grid, col);
                        e.anyBite |= e.biteAtSec[i] > 0;
                    }
                }
            }
        }

        // (b) Drain buffered sections (arrival order; absolute values — later wins).
        boolean upward = false;
        while (!e.pending.isEmpty()) {
            Pending sec = e.pending.pollFirst();
            List<Run> runs = sec.runs();
            long now = snapTick != Long.MIN_VALUE ? snapTick : sec.tick(); // the stamp-expiry clock (C1)
            boolean valid = true;
            for (Run run : runs) {
                if (run.startIdx() + run.values().length > cellCount) {
                    valid = false;
                    break;
                }
            }
            if (!valid) {
                refused++;
                continue; // whole-section refusal: no partial apply
            }
            int nLate = lateCandidates(sec.entityId(), sec.tick()); // sec.entityId() = admit's map key
            sectionsReceived++;
            if (!e.live) {
                e.live = true;
                engagedEntities++;
            }
            for (Run run : runs) {
                byte[] values = run.values();
                for (int k = 0; k < values.length; k++) {
                    int i = run.startIdx() + k;
                    int x = i / h;
                    int y = i % h;
                    float v = (values[k] & 0xFF) / 255f * maxInCell;
                    float dSrv = e.shadow[i] - v; // the server's drop here (C5: read before the store)
                    e.shadow[i] = v;
                    float live = cells[x][y];
                    float target = v;
                    if (e.claimSum[i] > eps && e.biteAtSec[i] > 0
                            && simNowSec - e.biteAtSec[i] <= REPLAY_WINDOW_SEC) {
                        // The P2 ledger replaces the keep-local
                        // branch: a live local claim nets against the drop, cell := max(0, v − rem),
                        // rem = max(0, claim − Δsrv) (report 02 Q5(c): the HullLedger shape; the amount
                        // is what keeps leg B). A fully paired claim is CONFIRMED; a paired remainder
                        // restores at +2G; an unpaired claim keeps its first-bite 2-s clock.
                        float rem = Math.max(0f, e.claimSum[i] - Math.max(0f, dSrv));
                        if (dSrv > eps) {
                            pairsClaimFirst++;
                            // The claim→drop offset of a NEVER-paired
                            // claim (a remainder's biteAtSec is armRemClock's synthetic time, not a bite age).
                            if (!e.remClock[i]) {
                                int tps = ServerTickRate.ticksPerSecond();
                                long t = Math.round((simNowSec - e.biteAtSec[i]) * (tps > 0 ? tps : 60));
                                claimDropOffsetHist[t <= 2 ? 0 : t <= 5 ? 1 : t <= 10 ? 2 : t <= 20 ? 3 : t <= 40 ? 4 : 5]++;
                                claimDropOffsetMax = Math.max(claimDropOffsetMax, t);
                            }
                        }
                        if (rem > eps) {
                            e.claimSum[i] = rem;
                            target = Math.max(0f, v - rem);
                            keptBites++; // the local prediction stands below the server value
                            if (dSrv > eps) {
                                armRemClock(e, i, simNowSec);
                            }
                        } else {
                            e.claimSum[i] = 0f;
                            e.biteAtSec[i] = 0;
                            e.remClock[i] = false;
                        }
                    }
                    // C1: a LOWERING write stamps (non-sliding, lazy G expiry); an UP
                    // write clears; an EQUAL write (R=2 / repair re-send) does nothing (report 02 F2).
                    if (apply && target < live - eps) {
                        stampDrop(e, i, sec.tick(), now, live - target);
                        accrueLate(nLate, i, live - target);
                    } else if (target > live + eps) {
                        clearStamp(e, i);
                    }
                    if (Math.abs(live - target) > eps) {
                        float d = Math.abs(live - target);
                        if (d > absDeltaMax) {
                            absDeltaMax = d;
                        }
                        absDeltaSum += d;
                        absDeltaSamples++;
                        if (apply) {
                            grid.setArmorValue(x, y, target);
                            writes++;
                            if (target > live) {
                                upward = true;
                            }
                        } else {
                            wouldWrites++;
                        }
                    }
                    cellsApplied++;
                }
            }
            settleLate(nLate); // The section's end — a candidate at the band matches once
        }

        // (c) Phantom-bite expiry (un-confirmed past the window restores to shadow) fused
        // with (d) the lastFrame refresh AFTER our writes — wire writes never read as bites.
        boolean biteSeen = false;
        int i = 0;
        for (int x = 0; i < cellCount; x++) {
            float[] col = cells[x];
            for (int y = 0; y < h; y++, i++) {
                float live = col[y];
                if (e.biteAtSec[i] > 0) {
                    if (simNowSec - e.biteAtSec[i] > REPLAY_WINDOW_SEC) {
                        expireClaim(e, i);
                        if (live < e.shadow[i] - eps) {
                            if (apply) {
                                grid.setArmorValue(x, y, e.shadow[i]);
                                writes++;
                                upward = true;
                                live = col[y];
                            } else {
                                wouldWrites++;
                            }
                            restored++;
                        }
                    } else {
                        biteSeen = true;
                    }
                }
                e.lastFrame[i] = live;
            }
        }
        e.anyBite = biteSeen;
        return upward;
    }

    /**
     * The P2 bite ledger: a
     * local bite of {@code bite} at cell i. An OLD claim whose first-bite window already passed is
     * restored first, by its own amount (this bite never renews it). At a LIVE drop stamp the bite
     * PAIRS — the drop-first order, the server already struck it: {@code p = min(bite, dropDelta)} goes
     * back up (batched into the frame's one decal sync), {@code dropDelta −= p}; the rest is CLAIMED
     * ({@code claimSum += bite − p}), its 2-s clock set ONLY when none runs (the FIRST bite — the
     * clock renewal is gone). Returns the cell value after any write.
     */
    private float onBite(EntityCells e, int i, int x, int y, float live, float bite, float eps,
            double simNowSec, long snapTick, ArmorGridAPI grid, float[] col) {
        if (e.biteAtSec[i] > 0 && simNowSec - e.biteAtSec[i] > REPLAY_WINDOW_SEC) {
            float back = Math.min(e.claimSum[i], e.shadow[i] - (live + bite));
            expireClaim(e, i);
            if (back > eps) {
                restored++;
                live = ledgerWrite(grid, col, x, y, live + back);
            }
        }
        // A bite on a stamp aged past G would have paired had G been
        // wider (report 02: seat-1 ackAge piles at G). Observation only — the stampLive law below is unchanged.
        if (e.dropTick[i] != NO_STAMP && snapTick != Long.MIN_VALUE && e.dropDelta[i] > 0f
                && snapTick - e.dropTick[i] > REPLAY_G_TICKS) {
            biteOnStaleStamp++;
            biteOnStaleStampHp += Math.min(bite, e.dropDelta[i]);
            biteOnStaleStampAgeMax = Math.max(biteOnStaleStampAgeMax, snapTick - e.dropTick[i]);
        }
        if (stampLive(e, i, snapTick) && e.dropDelta[i] > 0f) {
            float p = Math.min(bite, e.dropDelta[i]);
            e.dropDelta[i] -= p;
            if (e.dropDelta[i] <= eps) {
                clearStamp(e, i);
            }
            pairsDropFirst++;
            live = ledgerWrite(grid, col, x, y, live + p);
            bite -= p;
        }
        if (bite > eps) {
            e.claimSum[i] += bite;
            if (!(e.biteAtSec[i] > 0)) {
                e.biteAtSec[i] = Math.max(simNowSec, 1e-9);
            }
        }
        return live;
    }

    /** An upward ledger write through the engine's {@code setArmorValue} (apply) or a counted would-write. */
    private float ledgerWrite(ArmorGridAPI grid, float[] col, int x, int y, float v) {
        if (!apply) {
            wouldWrites++;
            return col[y];
        }
        grid.setArmorValue(x, y, v);
        writes++;
        ledgerUp = true;
        return col[y];
    }

    /** A claim leaves the ledger (expiry / old-claim restore): a paired remainder counts {@code pairs_rem_restored};
     *  a claim never netted against a server drop counts {@code claims_expired_unpaired}. */
    private void expireClaim(EntityCells e, int i) {
        //
        if (e.claimSum[i] > 0f && !e.remClock[i]) {
            claimsExpiredUnpaired++;
            claimsExpiredUnpairedHp += e.claimSum[i];
        }
        e.biteAtSec[i] = 0;
        e.claimSum[i] = 0f;
        if (e.remClock[i]) {
            e.remClock[i] = false;
            pairsRemRestored++;
        }
    }

    /** P2: a paired remainder restores at +2G ticks (the sim-second clock bites run on; the earlier of
     *  this and a running first-bite clock wins). */
    private static void armRemClock(EntityCells e, int i, double simNowSec) {
        int tps = ServerTickRate.ticksPerSecond();
        double at = simNowSec - REPLAY_WINDOW_SEC + 2.0 * REPLAY_G_TICKS / (tps > 0 ? tps : 60);
        e.biteAtSec[i] = Math.max(e.biteAtSec[i] > 0 ? Math.min(e.biteAtSec[i], at) : at, 1e-9);
        e.remClock[i] = true;
        e.anyBite = true;
    }

    /** C1: a lowering write of {@code delta} by a section at {@code sectionTick}; a missing or expired
     *  stamp (lazy, {@code now − dropTick > G}) restarts at THIS drop (non-sliding). */
    private static void stampDrop(EntityCells e, int i, long sectionTick, long now, float delta) {
        int t = e.dropTick[i];
        if (t == NO_STAMP || now - t > REPLAY_G_TICKS) {
            e.dropTick[i] = (int) sectionTick;
            e.dropDelta[i] = 0f;
        }
        e.dropDelta[i] += delta;
    }

    private static void clearStamp(EntityCells e, int i) {
        e.dropTick[i] = NO_STAMP;
        e.dropDelta[i] = 0f;
    }

    /** A stamp is live at age {@code snapTick − dropTick} ∈ [−1, G]; an unknown tick is never live. */
    private static boolean stampLive(EntityCells e, int i, long snapTick) {
        int t = e.dropTick[i];
        if (t == NO_STAMP || snapTick == Long.MIN_VALUE) {
            return false;
        }
        long age = snapTick - t;
        return age >= -1L && age <= REPLAY_G_TICKS;
    }

    /**
     * One cap's wire-acked replay arm: everything the POST
     * needs, owned by the CAP (one per cap, reused — zero allocation per hit; a per-cap arm cannot be
     * re-pointed by another cap's hit). {@code snap} = the WHOLE grid before the write-up,
     * grown to the largest grid this cap saw. Render thread; the modify → report chain is ONE
     * synchronous {@code Ship.applyDamage}.
     */
    static final class ReplayArm {
        boolean armed;
        int frame;
        int epoch;
        long entityId;
        ShipAPI ship;
        float preHull;
        /** T_s = the minimum dropTick over the stamped mask cells (the FHRL window's order bound). */
        long hitTick;
        /** The MAXIMUM dropTick over the stamped mask cells ({@code ackTmax=}; the
         *  late-twin order bound — a twin section must be strictly later than every stamp the replay consumed). */
        long hitTickMax;
        long age;
        float sumDelta;
        float sProj;
        float dk;
        float maskUp;
        int n;
        final int[] ord = new int[21];
        final boolean[] inner = new boolean[21];
        final boolean[] stamped = new boolean[21];
        final float[] preUp = new float[21];
        final float[] liveBefore = new float[21];
        float[] snap = new float[0];
        int w;
        int h;
    }

    /**
     * One acked replay awaiting its LATE twin (lane 05's FP class):
     * written by {@link #watchLate} at the counted POST,
     * read by the section drain ({@link #lateCandidates} / {@link #accrueLate} / {@link #settleLate}). A section
     * of this entity at a server tick in {@code (hitTickMax, snapTick + G]} that LOWERS {@code ord[0..n)} adds its
     * drop to {@code lateSum}; at {@code lateSum ≥ REPLAY_KEY_BAND·sProj} the replay counts late, once. Past
     * {@code snapTick + G} (a later section of this entity passing) the entry expires. ACCEPTED AMBIGUITY:
     * a THIRD server hit on the same cells inside G reads as the twin — on a same-spot server-first
     * STREAM (refire ≤ age + G) or a beam on the mask, k−1 of k acked replays count late, so this count is a LIVE
     * UPPER BOUND on the offline grader's graded {@code genuine} measure (one-to-one, source- and amount-aware: NOT
     * blind to the third hit); compare the two per round on the next flight before trusting the WARN's share; a
     * dedup (settle at expiry, subtract lowerings a later acked replay consumed) is a design lens of its own. Ring
     * slots are reused in order; an overwrite of a still-watching entry needs {@link #LATE_RING} acked replays
     * inside one G window and goes UNCOUNTED (an under-count, never a throw).
     */
    private static final class LateWatch {
        boolean live;
        long entityId;
        int epoch;
        /** The replay's client snapshot tick (= arm.hitTick + arm.age) — the G window's anchor. */
        long snapTick;
        long hitTickMax;
        float sProj;
        /** The replay's hp_live amount (ackLive + hull) — what {@code replay_acked_hp_late} adds on a match. */
        float amount;
        int n;
        final int[] ord = new int[21];
        float lateSum;

        static LateWatch[] ring(int size) {
            LateWatch[] r = new LateWatch[size];
            for (int k = 0; k < size; k++) {
                r[k] = new LateWatch();
            }
            return r;
        }
    }

    /**
     * The KEY + PRE of the wire-acked REPLAY, called by the
     * replica cap's {@code modifyDamageTaken} AFTER its gate. A server-first hit arrives twice: its cell
     * section lowered (and stamped) the mask first, now the local copy would strip it AGAIN. The
     * key: ΣΔ (live stamps over the 21-mask) ≥ {@link #REPLAY_KEY_BAND}·S_proj ∧ ΣΔ &gt; 21·eps, where
     * S_proj = the engine's own split on the PRE mask
     * {@code pre_i = live_i + dropDelta_i}: A = Σ_inner pre + ½Σ_outer pre (floored at 0.05·15·maxInCell),
     * B = Dk·(1 − min(A/(A+Dk), 0.85)), S_proj = Σ min(pre_i, B/15 inner | B/30 outer). On a key in APPLY:
     * snapshot the whole grid, write each stamped mask cell UP to {@code min(pre_i, maxInCell)} (a clamp
     * counts), fill {@code into}. The arm adds NO modifier — the caller falls through to {@code capIfLethal}.
     * Refusals: knob off, not cell-live, null point, {@code isDps}, own-seat source, an open
     * wire-acked window, an arm already held. Returns {@code into} when armed, else null.
     */
    ReplayArm armReplay(ReplayArm into, long entityId, ShipAPI ship, DamageAPI damage, Object param,
            Vector2f point, long snapTick, int frame, ShipAPI ownShip, boolean wireAckedOpen) {
        if (failed || !replayMeter || into == null || into.armed || ship == null || damage == null
                || point == null || wireAckedOpen || !cellLive(entityId)) {
            return null;
        }
        try {
            if (heldArm != null && heldArm.armed && heldArm != into) {
                // ONE open arm per lane — a second cap on the same body must never stack a second
                // write-up over the first (its snapshot would hold the first's write-up); a stale one = orphan.
                if (heldArm.frame == frame) {
                    return null;
                }
                restoreOrphan(heldArm);
            }
            if (damage.isDps()) {
                return null;
            }
            ShipAPI shooter = HullCapBound.shooterOf(param);
            if (shooter != null && shooter == ownShip) {
                return null; // own-seat fire leads the wire: never a replay
            }
            EntityCells e = entities.get(entityId);
            ArmorGridAPI grid = ship.getArmorGrid();
            float[][] cells = grid != null ? grid.getGrid() : null;
            if (e == null || e.shadow == null || cells == null || cells.length == 0 || cells[0] == null) {
                return null;
            }
            int w = cells.length;
            int h = cells[0].length;
            float maxInCell = grid.getMaxArmorInCell();
            int[] c = grid.getCellAtLocation(point); // ACL's own resolve (report 02 F8: never via the witness)
            if (w * h != e.shadow.length || !(maxInCell > 0f) || c == null || c.length < 2
                    || c[0] < 0 || c[0] >= w || c[1] < 0 || c[1] >= h) {
                return null;
            }
            float eps = maxInCell * CELL_EPS_FRAC;
            int n = 0;
            float sumDelta = 0f;
            float a = 0f;
            long tMin = Long.MAX_VALUE;
            long tMax = Long.MIN_VALUE;
            for (int dx = -2; dx <= 2; dx++) { // the 21-mask: 5×5 minus corners, grid-clipped
                for (int dy = -2; dy <= 2; dy++) {
                    int x = c[0] + dx;
                    int y = c[1] + dy;
                    if (dx * dx + dy * dy == 8 || x < 0 || x >= w || y < 0 || y >= h) {
                        continue;
                    }
                    int o = x * h + y;
                    boolean in = dx >= -1 && dx <= 1 && dy >= -1 && dy <= 1;
                    boolean st = stampLive(e, o, snapTick) && e.dropDelta[o] > 0f;
                    float live = cells[x][y];
                    float pre = st ? live + e.dropDelta[o] : live;
                    into.ord[n] = o;
                    into.inner[n] = in;
                    into.stamped[n] = st;
                    into.liveBefore[n] = live;
                    into.preUp[n] = pre;
                    if (st) {
                        sumDelta += e.dropDelta[o];
                        tMin = Math.min(tMin, e.dropTick[o]);
                        tMax = Math.max(tMax, e.dropTick[o]);
                    }
                    a += in ? pre : 0.5f * pre;
                    n++;
                }
            }
            a = Math.max(a, 0.05f * 15f * maxInCell);
            float dk = replayDk(ship, damage, shooter);
            float b = dk * (1f - Math.min(a / (a + dk), 0.85f));
            float sProj = 0f;
            for (int k = 0; k < n; k++) {
                sProj += Math.min(into.preUp[k], b * (into.inner[k] ? 1f / 15f : 1f / 30f));
            }
            if (!(sProj > 0f) || !(sumDelta >= REPLAY_KEY_BAND * sProj) || !(sumDelta > 21f * eps)) {
                return null;
            }
            if (!replayApply) {
                replayWouldAck++; // meter: a real mode — counted, nothing written
                return null;
            }
            if (into.snap.length < w * h) {
                into.snap = new float[w * h];
            }
            for (int x = 0; x < w; x++) {
                System.arraycopy(cells[x], 0, into.snap, x * h, h);
            }
            float up = 0f;
            for (int k = 0; k < n; k++) {
                if (into.stamped[k]) {
                    float t = into.preUp[k];
                    if (t > maxInCell) {
                        t = maxInCell;
                        into.preUp[k] = t;
                        replayClamped++;
                    }
                    if (t > into.liveBefore[k]) {
                        grid.setArmorValue(into.ord[k] / h, into.ord[k] % h, t);
                        writes++;
                        up += t - into.liveBefore[k];
                    }
                }
            }
            into.armed = true;
            heldArm = into;
            into.frame = frame;
            into.epoch = epoch;
            into.entityId = entityId;
            into.ship = ship;
            into.preHull = ship.getHitpoints();
            into.hitTick = tMin;
            into.hitTickMax = tMax;
            into.age = snapTick - tMin;
            into.sumDelta = sumDelta;
            into.sProj = sProj;
            into.dk = dk;
            into.maskUp = up;
            into.n = n;
            into.w = w;
            into.h = h;
            return into;
        } catch (Throwable t) {
            failSoft(t);
            return null;
        }
    }

    /** Dk = getDamage × the type's armor mult × the victim's armor-damage-taken mult (× the kinetic armor
     *  taken mult on KINETIC) × the resolved shooter's size-class stat
     *  (the one shooter term that reaches the armor step; via
     *  {@link HullCapBound#shooterSizeClassMult}, ≥ 1). A throw ⇒ getDamage × armorMult, counted. */
    private float replayDk(ShipAPI ship, DamageAPI damage, ShipAPI shooter) {
        DamageType type = damage.getType();
        float base = damage.getDamage() * (type != null ? type.getArmorMult() : 1f);
        try {
            MutableShipStatsAPI s = ship.getMutableStats();
            float k = s.getArmorDamageTakenMult().getModifiedValue();
            if (type == DamageType.KINETIC) {
                k *= s.getKineticArmorDamageTakenMult().getModifiedValue();
            }
            float dk = base * k * (shooter != null ? HullCapBound.shooterSizeClassMult(shooter, ship) : 1f);
            return Float.isFinite(dk) && dk >= 0f ? dk : base;
        } catch (Throwable t) {
            replayDkFaults++;
            return base;
        }
    }

    /**
     * The POST, in the SAME {@code applyDamage}'s
     * {@code reportDamageApplied} (the cap calls it after {@code recordApplied} + {@code emitApply} +
     * {@code notePrematureKill}, inside the FHRL wire-acked window). The engine has struck the
     * written-up mask (its number, sound, sparks, decals fired); if the body lives: consume each stamp by
     * {@code min(dropDelta_i, preUp_i − post_i)}, then restore the WHOLE-GRID snapshot (every cell whose
     * post ≠ pre, NaN-safe) — never {@code e.shadow} (a live P2 claim or an earlier
     * same-frame bite keeps its value, and the next diff sees exactly those, not this hit); ONE decal batch
     * when a restore went upward. A dead/hulked body restores nothing ({@code replay_dead}); a stale epoch,
     * another body, a re-minted grid or an evicted entity counts {@code replay_orphan}. Disarms.
     */
    void finishReplay(ReplayArm arm, ShipAPI ship, ApplyDamageResultAPI result, boolean bodyAlive) {
        if (arm == null || !arm.armed) {
            return;
        }
        arm.armed = false;
        try {
            if (arm.epoch != epoch || ship != arm.ship) {
                replayOrphan++;
                return;
            }
            if (!bodyAlive) {
                replayDead++;
                return;
            }
            ArmorGridAPI grid = ship.getArmorGrid();
            float[][] cells = grid != null ? grid.getGrid() : null;
            int h = arm.h;
            if (cells == null || cells.length != arm.w || cells[0] == null || cells[0].length != h) {
                replayOrphan++;
                return;
            }
            EntityCells e = entities.get(arm.entityId);
            boolean known = e != null && e.dropDelta != null && e.dropDelta.length == arm.w * h;
            float eps = grid.getMaxArmorInCell() * CELL_EPS_FRAC;
            float liveCost = liveCost(arm, cells); // The row's ackLive= is this same function at emit
            for (int k = 0; k < arm.n; k++) {
                int o = arm.ord[k];
                float post = cells[o / h][o % h];
                if (known && arm.stamped[k] && e.dropTick[o] != NO_STAMP) {
                    float used = Math.min(e.dropDelta[o], arm.preUp[k] - post);
                    if (used > 0f) {
                        e.dropDelta[o] -= used;
                        if (e.dropDelta[o] <= eps) {
                            clearStamp(e, o);
                        }
                    }
                }
            }
            boolean upward = false;
            float[] pre = arm.snap;
            for (int x = 0, i = 0; x < arm.w; x++) {
                float[] col = cells[x];
                for (int y = 0; y < h; y++, i++) {
                    float post = col[y];
                    if (!(post == pre[i])) {
                        grid.setArmorValue(x, y, pre[i]);
                        writes++;
                        upward |= pre[i] > post;
                    }
                }
            }
            if (upward) {
                ship.syncWithArmorGridState();
                ship.syncWeaponDecalsWithArmorDamage();
                decalBatches++;
            }
            if (!known) {
                replayOrphan++; // C10: the entity was evicted/reset — restored from the arm's own snapshot
                return;
            }
            float hull = result != null ? Math.max(0f, result.getDamageToHull()) : 0f;
            replayAcked++;
            replayAckedHp += hull;
            replayAckedHpLive += liveCost + hull;
            roundAcked++;
            watchLate(arm, liveCost + hull);
            maybeWarnLate(); // the share can be reached from the acked side (early lates, then clean)
        } catch (Throwable t) {
            failSoft(t);
        }
    }

    /** The hit's LIVE-MASK cost: Σ over the arm's mask of
     *  max(0, liveBefore − post), what the engine's strike took BELOW the pre-write-up live values (the true
     *  double-strip share of this replay; {@code replay_acked_hp_live} = this + the hull). ONE function for
     *  the meter ({@link #finishReplay}) and the row token ({@link #liveCostNow}) so the two agree by
     *  construction — the C9 2 % clause then grades per row. */
    static float liveCost(ReplayArm arm, float[][] cells) {
        float cost = 0f;
        int h = arm.h;
        for (int k = 0; k < arm.n; k++) {
            int o = arm.ord[k];
            cost += Math.max(0f, arm.liveBefore[k] - cells[o / h][o % h]);
        }
        return cost;
    }

    /** The witness row's {@code ackLive=} at EMIT (after the engine's strike, before the POST): {@link #liveCost}
     *  on the armed body's live grid; NaN for a disarmed arm, no grid, a re-minted grid or a throw. One grid
     *  read per acked hit (never per frame). */
    float liveCostNow(ReplayArm arm) {
        try {
            ShipAPI ship = arm != null && arm.armed ? arm.ship : null;
            ArmorGridAPI grid = ship != null ? ship.getArmorGrid() : null;
            float[][] cells = grid != null ? grid.getGrid() : null;
            if (cells == null || cells.length != arm.w || cells[0] == null || cells[0].length != arm.h) {
                return Float.NaN;
            }
            return liveCost(arm, cells);
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /**
     * The late-twin detector's WRITE (the counted POST branch of
     * {@link #finishReplay}, after the counters): the next ring slot takes the arm's entity, epoch, snapshot tick
     * ({@code hitTick + age} = the {@code snapTick} {@link #armReplay} saw — no new {@code finishReplay}
     * parameter), {@code hitTickMax}, S_proj, the hp_live amount and the mask ordinals; {@code lateSum} restarts
     * at 0. No allocation.
     */
    private void watchLate(ReplayArm arm, float amount) {
        LateWatch w = lateRing[lateNext];
        lateNext = (lateNext + 1) % LATE_RING;
        w.live = true;
        w.entityId = arm.entityId;
        w.epoch = arm.epoch;
        w.snapTick = arm.hitTick + arm.age;
        w.hitTickMax = arm.hitTickMax;
        w.sProj = arm.sProj;
        w.amount = amount;
        w.n = arm.n;
        System.arraycopy(arm.ord, 0, w.ord, 0, arm.n);
        w.lateSum = 0f;
    }

    /**
     * The READ's candidate pass, ONCE per valid drained section
     * (≤ {@link #LATE_RING} compares): an entry of THIS entity past its window ({@code secTick > snapTick + G})
     * or from another epoch expires; one strictly later than every stamp its replay consumed
     * ({@code secTick > hitTickMax}) becomes a candidate (an index into the reused {@code lateCand}). A same-tick
     * or earlier section (a re-send of the consumed stamp's section) never matches. Returns the candidate count.
     */
    private int lateCandidates(long entityId, long secTick) {
        int c = 0;
        for (int k = 0; k < LATE_RING; k++) {
            LateWatch w = lateRing[k];
            if (!w.live || w.entityId != entityId) {
                continue;
            }
            if (w.epoch != epoch || secTick > w.snapTick + REPLAY_G_TICKS) {
                w.live = false; // expired (a pre-reset entry is already cleared by resetLateRound)
                continue;
            }
            if (secTick > w.hitTickMax) {
                lateCand[c++] = k;
            }
        }
        return c;
    }

    /** One LOWERING write of {@code drop} at ordinal {@code i} accrues
     *  to every candidate whose mask holds {@code i} (≤ 21 int compares per candidate; nCand 0 = one compare). */
    private void accrueLate(int nCand, int i, float drop) {
        for (int c = 0; c < nCand; c++) {
            LateWatch w = lateRing[lateCand[c]];
            for (int k = 0; k < w.n; k++) {
                if (w.ord[k] == i) {
                    w.lateSum += drop;
                    break;
                }
            }
        }
    }

    /**
     * The section's end: a candidate whose accrued drop reached
     * {@code REPLAY_KEY_BAND·sProj} counts late ONCE ({@code live = false}); below the band it keeps accruing over
     * later sections until it expires. After a match the round WARN gate runs ({@link #maybeWarnLate}).
     */
    private void settleLate(int nCand) {
        for (int c = 0; c < nCand; c++) {
            LateWatch w = lateRing[lateCand[c]];
            if (!w.live || !(w.lateSum >= REPLAY_KEY_BAND * w.sProj)) {
                continue;
            }
            w.live = false;
            replayAckedLate++;
            replayAckedHpLate += w.amount;
            roundLate++;
            maybeWarnLate();
        }
    }

    /** The round WARN gate — ≥
     *  {@link #LATE_WARN_MIN_ACKED} acked and a late share ≥ 10 % (lane 05: genuine FPs ≈ 4 %) —
     *  checked at EVERY acked replay AND every late match (3 early lates then 17 clean replays reach 15 % at acked
     *  20 with no new late landing; pin row (xv-b)). ONE line per round; the share is the LIVE UPPER BOUND of
     *  {@link LateWatch} (H2). {@code PilotWatchLog.warn} never throws (its fallback is guarded), so nothing here
     *  reaches the drain's failSoft. */
    private void maybeWarnLate() {
        if (roundLateWarned || roundAcked < LATE_WARN_MIN_ACKED || roundLate * 10 < roundAcked) {
            return;
        }
        roundLateWarned = true;
        PilotWatchLog.warn("predict v3 " + label + "-armor-cells replay_fp_late WARN: round late=" + roundLate
                + " acked=" + roundAcked + " share=" + fmt1(100.0 * roundLate / roundAcked)
                + "% (live upper bound; bound 10 %)");
    }

    /** {@code replica_local_hp}: DamageWitness's per-apply feed (a
     *  non-acked, non-stale, non-kill {@code remote:} apply's hull; a beam per tick). Adds max(0, hull) when
     *  finite — a NaN / infinite / negative split never poisons the sum. Cumulative (survives the round reset). */
    void noteReplicaLocalHull(float hull) {
        if (Float.isFinite(hull)) {
            replicaLocalHp += Math.max(0f, hull);
        }
    }

    /**
     * An arm whose report never came (a throw
     * inside {@code applyDamage} or an earlier report listener): called by the cap at a LATER frame.
     * Restore-only of what the arm itself wrote — each written-up mask cell back DOWN to its
     * {@code liveBefore} where it still exceeds it (a whole-grid snapshot from an earlier frame would
     * revert every section applied since) — and refresh {@code lastFrame} there so the diff reads no
     * bite. Counts {@code replay_orphan}; a pre-reset arm or a dead body writes nothing. Disarms.
     */
    void restoreOrphan(ReplayArm arm) {
        if (arm == null || !arm.armed) {
            return;
        }
        arm.armed = false;
        replayOrphan++;
        try {
            ShipAPI ship = arm.ship;
            if (arm.epoch != epoch || ship == null || !ship.isAlive()) {
                return;
            }
            ArmorGridAPI grid = ship.getArmorGrid();
            float[][] cells = grid != null ? grid.getGrid() : null;
            int h = arm.h;
            if (cells == null || cells.length != arm.w || cells[0] == null || cells[0].length != h) {
                return;
            }
            EntityCells e = entities.get(arm.entityId);
            boolean known = e != null && e.lastFrame != null && e.lastFrame.length == arm.w * h;
            for (int k = 0; k < arm.n; k++) {
                int o = arm.ord[k];
                if (arm.stamped[k] && cells[o / h][o % h] > arm.liveBefore[k]) {
                    grid.setArmorValue(o / h, o % h, arm.liveBefore[k]);
                    writes++;
                    if (known) {
                        e.lastFrame[o] = arm.liveBefore[k];
                    }
                }
            }
        } catch (Throwable t) {
            failSoft(t);
        }
    }

    /**
     * The PRESENTER's own strip consumes its section stamps:
     * PRCP {@code applyWireAckedHit} restores the grid after its apply, but the section that booked THAT
     * hit had stamped the cells; left live, the next LOCAL hit at the spot would key a false replay on a
     * Δ already spent. Debits {@code min(dropDelta, strip)} at one ordinal, clearing the stamp at ≤ eps;
     * an unknown entity / ordinal / stamp or a non-positive strip is a no-op. Render thread, per changed
     * cell of one presenter apply (mine / missile ends — never per frame).
     */
    void consumeStrip(long entityId, int ordinal, float strip, float maxInCell) {
        EntityCells e = entities.get(entityId);
        if (e == null || e.dropDelta == null || ordinal < 0 || ordinal >= e.dropDelta.length
                || e.dropTick[ordinal] == NO_STAMP || !(strip > 0f)) {
            return;
        }
        e.dropDelta[ordinal] -= Math.min(e.dropDelta[ordinal], strip);
        if (e.dropDelta[ordinal] <= maxInCell * CELL_EPS_FRAC) {
            clearStamp(e, ordinal);
        }
        replayPresenterConsumed++;
    }

    /** Verifier read seams (render thread): the P2 ledger / C1 stamp state at one ordinal
     *  (NaN / {@link #NO_STAMP} = unknown entity or ordinal). */
    float claimOf(long entityId, int ordinal) {
        EntityCells e = entities.get(entityId);
        return e == null || e.claimSum == null || ordinal < 0 || ordinal >= e.claimSum.length
                ? Float.NaN : e.claimSum[ordinal];
    }

    float dropDeltaOf(long entityId, int ordinal) {
        EntityCells e = entities.get(entityId);
        return e == null || e.dropDelta == null || ordinal < 0 || ordinal >= e.dropDelta.length
                ? Float.NaN : e.dropDelta[ordinal];
    }

    int dropTickOf(long entityId, int ordinal) {
        EntityCells e = entities.get(entityId);
        return e == null || e.dropTick == null || ordinal < 0 || ordinal >= e.dropTick.length
                ? NO_STAMP : e.dropTick[ordinal];
    }

    /** Read-only shadow seam (render thread — the same thread {@link #maintain}
     *  runs on; DamageWitness cell terms): shadow value at one v11 cell ordinal (x·h+y).
     *  NaN when the lane is inert / entity unseeded / ordinal out of range. A seeded-but-
     *  sectionless entity reads its spawn-full default — {@link #shadowSectionTick} tells the
     *  consumer how stale that truth is. */
    float shadowCell(long entityId, int ordinal) {
        if (failed || !meter) {
            return Float.NaN;
        }
        EntityCells e = entities.get(entityId);
        if (e == null || e.shadow == null || ordinal < 0 || ordinal >= e.shadow.length) {
            return Float.NaN;
        }
        return e.shadow[ordinal];
    }

    /** Same seam: Σ shadow over the given ordinals (any out-of-range ordinal ⇒ NaN — a
     *  mask/grid mismatch must read as absent, never as a partial sum). */
    float shadowSum(long entityId, int[] ordinals) {
        if (failed || !meter || ordinals == null) {
            return Float.NaN;
        }
        EntityCells e = entities.get(entityId);
        if (e == null || e.shadow == null) {
            return Float.NaN;
        }
        float sum = 0f;
        for (int ordinal : ordinals) {
            if (ordinal < 0 || ordinal >= e.shadow.length) {
                return Float.NaN;
            }
            sum += e.shadow[ordinal];
        }
        return sum;
    }

    /** Same seam: the server sim tick of the entity's last admitted cell section
     *  ({@code Long.MIN_VALUE} = entity unknown; {@code 0} = seeded, no section yet). */
    long shadowSectionTick(long entityId) {
        EntityCells e = entities.get(entityId);
        return e == null ? Long.MIN_VALUE : e.lastSectionTick;
    }

    /** True once this entity has per-cell carriage AND the lane writes — the fold lane's
     *  disengage read ({@code meter} mode keeps the fold live; see class doc). A
     *  FAILED lane ({@link #failSoft}) is never live — the whole-grid fold re-engages as the armor writer. */
    boolean cellLive(long entityId) {
        if (!apply || failed) {
            return false;
        }
        EntityCells e = entities.get(entityId);
        return e != null && e.live;
    }

    /** Round wrap: entity ids re-mint — drop all per-entity state; cumulative counters
     *  persist (hull precedent). Emits the totals line through {@link PilotWatchLog} (the
     *  sidelog every sibling meter rides; System.out reached no log). */
    void resetForMatchReset() {
        if (meter) {
            PilotWatchLog.info("predict " + label + "-armor-cells match-reset totals:"
                    + " sections=" + sectionsReceived + " cells=" + cellsApplied
                    + " keptBites=" + keptBites + " restored=" + restored
                    + " refused=" + refused + " dropped=" + dropped + " evicted=" + evicted);
        }
        entities.clear(); // stamps + claims ride the entries
        epoch++; // Every cap's held arm is from the old round — discarded, never restored
        evictScanNotBeforeSec = Double.NEGATIVE_INFINITY; // the gate was armed against the old map
        synchronized (inbox) {
            inbox.clear(); // entity ids re-mint across rounds — stale sections are noise
        }
        resetLateRound();
    }

    /** The round wrap of the late-twin meter — the WARN re-arms and
     *  every ring entry stops watching (the epoch test already excludes them; both, cheap). The cumulative
     *  late / hp / replica-local counters persist (the siblings' precedent). */
    private void resetLateRound() {
        roundAcked = 0;
        roundLate = 0;
        roundLateWarned = false;
        for (LateWatch w : lateRing) {
            w.live = false;
        }
    }

    /** The meter line (single-line discipline; same prefix family AND sink as the
     *  fold/hull meters — {@link PilotWatchLog}). Flush points land with the wiring commit. */
    void logMeter(String where) {
        if (!meter) {
            return;
        }
        double mean = absDeltaSamples > 0 ? absDeltaSum / absDeltaSamples : 0.0;
        PilotWatchLog.info("predict v3 " + label + "-armor-cells [" + where + "]:"
                + " mode=" + mode
                + " records=" + sectionsReceived
                + " cells=" + cellsApplied
                + " kept_bites=" + keptBites
                + " restored=" + restored
                + " refused=" + refused
                + " engaged=" + engagedEntities
                + " would_writes=" + wouldWrites
                + " writes=" + writes
                + " decal_batches=" + decalBatches
                + " dropped=" + dropped
                + " evicted=" + evicted
                + " abs_delta_max=" + fmt1(absDeltaMax)
                + " abs_delta_mean=" + fmt1(mean)
                // The replay + P2 ledger meters
                + " replay_mode=" + (replayApply ? "apply" : replayMeter ? "meter" : "off")
                + " replay_acked=" + replayAcked
                + " replay_acked_hp=" + fmt1(replayAckedHp)
                + " replay_acked_hp_live=" + fmt1(replayAckedHpLive)
                + " replay_would_ack=" + replayWouldAck
                + " replay_orphan=" + replayOrphan
                + " replay_clamped=" + replayClamped
                + " replay_dead=" + replayDead
                + " replay_dk_faults=" + replayDkFaults
                + " replay_presenter_consumed=" + replayPresenterConsumed //
                // The live replay FP meter + its denominator (names EXACT)
                + " replay_acked_late=" + replayAckedLate
                + " replay_acked_hp_late=" + fmt1(replayAckedHpLate)
                + " replica_local_hp=" + fmt1(replicaLocalHp)
                + " pairs_claim_first=" + pairsClaimFirst
                + " pairs_drop_first=" + pairsDropFirst
                + " pairs_rem_restored=" + pairsRemRestored
                // The D3 K inputs (names EXACT)
                + " claims_expired_unpaired=" + claimsExpiredUnpaired
                + " claims_expired_unpaired_hp=" + fmt1(claimsExpiredUnpairedHp)
                + " bite_on_stale_stamp=" + biteOnStaleStamp
                + " bite_on_stale_stamp_hp=" + fmt1(biteOnStaleStampHp)
                + " bite_on_stale_stamp_age_max=" + biteOnStaleStampAgeMax
                + " claim_drop_offset_hist=" + claimDropOffsetHist[0] + "/" + claimDropOffsetHist[1] + "/"
                + claimDropOffsetHist[2] + "/" + claimDropOffsetHist[3] + "/" + claimDropOffsetHist[4] + "/"
                + claimDropOffsetHist[5] + " claim_drop_offset_max=" + claimDropOffsetMax);
    }

    /** Verifier read seams for the two
     *  entity-cap counters the meter lines export (render thread, same as {@link #maintain}). */
    long droppedTotal() {
        return dropped;
    }

    long evictedTotal() {
        return evicted;
    }

    /** Verifier read seams for the late-twin meter, the
     *  replica-local denominator and the round WARN gate (render thread, same as {@link #maintain}). */
    long replayAckedLate() {
        return replayAckedLate;
    }

    double replayAckedHpLate() {
        return replayAckedHpLate;
    }

    double replicaLocalHp() {
        return replicaLocalHp;
    }

    boolean roundLateWarned() {
        return roundLateWarned;
    }

    long roundAcked() {
        return roundAcked;
    }

    private void failSoft(Throwable t) {
        failed = true;
        String msg = String.valueOf(t.getMessage());
        int nl = msg.indexOf('\n');
        PilotWatchLog.info("predict " + label + "-armor-cells FAIL-SOFT (lane inert): "
                + t.getClass().getName() + ": " + (nl >= 0 ? msg.substring(0, nl) : msg));
    }

    private static String fmt1(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static final class EntityCells {
        final ArrayDeque<Pending> pending = new ArrayDeque<>();
        float[] shadow;
        double[] biteAtSec;
        float[] lastFrame;
        /** The C1 drop stamp — the server tick of the FIRST
         *  lowering write of the live stamp ({@link #NO_STAMP} = none; non-sliding) + Σ of the lowering
         *  writes since — and the P2 ledger: the un-acked local claim per cell, and whether its clock is a
         *  paired remainder's (+2G). Born at seed, re-born at a re-mint, dropped with the entry. */
        int[] dropTick;
        float[] dropDelta;
        float[] claimSum;
        boolean[] remClock;
        boolean live;
        long lastSectionTick;
        /** Sim second of the last admit/maintain for this entity — {@link ArmorCellLane#evictIdle}'s key. */
        double lastTouchSimSec;
        /** Steady-pass flag: false ⇒ no cell carries an un-confirmed bite stamp (set at every
         *  stamp, re-derived at the end of every full pass) — the steady pass skips the
         *  expiry test per cell while false. Conservatively true is always safe. */
        boolean anyBite;

        /** First sight (or grid re-mint under a reused id): shadow = full armor (spawn
         *  default both sides — an already-damaged first section is naturally divergent),
         *  lastFrame = the live values (pre-existing local damage is not a fresh bite). */
        void seed(int cellCount, float maxInCell, float[][] cells, int h) {
            shadow = new float[cellCount];
            java.util.Arrays.fill(shadow, maxInCell);
            biteAtSec = new double[cellCount];
            dropTick = new int[cellCount];
            java.util.Arrays.fill(dropTick, NO_STAMP);
            dropDelta = new float[cellCount];
            claimSum = new float[cellCount];
            remClock = new boolean[cellCount];
            lastFrame = new float[cellCount];
            for (int i = 0; i < cellCount; i++) {
                lastFrame[i] = cells[i / h][i % h];
            }
            anyBite = false;
        }
    }
}
