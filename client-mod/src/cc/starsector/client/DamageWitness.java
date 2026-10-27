package cc.starsector.client;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.combat.listeners.DamageListener;

import cc.starsector.client.net.ClientGameplayEdgeEvent;

/**
 * The leg-1c damage-application WITNESS (knob
 * {@code cc.predict.dmgWitness}, statics/hooks in {@link PredictReconcileCombatPlugin}):
 * watch-only per-shell + per-hit telemetry so the candidate matrix (a)–(e) falsifies
 * OFFLINE against the ccwr GPEE/hull/flux/shield series. Zero wire, zero server, zero
 * behavior — every entry point is fail-soft (a witness failure must never break the fire
 * or damage path it observes).
 *
 * <p><b>Lane 1 {@code dmg-shot}/{@code dmg-shot-out}</b> — one line per LOCAL own
 * discharge (both detection lanes feed the same hook: the onFire callback and the
 * scan; unselected-autofire shells included — the declare-funnel filter is downstream of
 * this witness) + a retire line at despawn carrying {@code didDamage}/target (the
 * {@link FuseBurstMeter} closeShell pattern, all specs). Presented spawns are TAGGED
 * (shooter entityId/mount/age; no extra line — the presenter already logs presents) and
 * retire through the same pass, so cell (e)'s over-connect is countable per shell.
 *
 * <p><b>Lane 2 {@code dmg-apply}</b> — the Foe/Remote damage-cap listeners arm a
 * pre-state stash in {@code modifyDamageTaken} (impact point, shieldHit, victim shield
 * on/facing, armor-grid total, hp — taken BEFORE that method's early return) and emit in
 * {@code reportDamageApplied} (the shipped clampUpper arm→consume ordering proof), with
 * the as-applied hull/armor/shield/EMP split read pre-restoreFx. Beam DPS ticks aggregate
 * per (source, target) and flush every {@link #BEAM_FLUSH_FRAMES} frames / on shieldHit
 * flip / at teardown, so anchor-#1's beam class stays witnessable without a per-tick line
 * flood.
 *
 * <p><b>Flood fold </b> — the measured ~97% report class (stash-missing +
 * ALL-ZERO split + bare-entity source: a zero-information contact stream that never
 * enters {@code modifyDamageTaken}, victim gloss corrected) folds to
 * per-(source→victim) census counters with NO line, so {@link #APPLY_LINE_MAX} covers the
 * whole fight's real applies. A NONZERO armless apply stays a LOUD line — that shape
 * would be a genuine modify-bypassing damage path (zero seen in a measured fight).
 *
 * <p>Threading: every entry point runs on the engine advance (render) thread — the two
 * discharge lanes, the per-frame tick, and the engine's applyDamage listener dispatch —
 * so state is plain fields, the {@link FuseBurstMeter} discipline.
 */
final class DamageWitness {

    /** Per-lane line caps — set HIGH so kill windows stay complete; counters continue past.
     *  Raised from 20000 to 60000 — long flights reached the old
     *  caps mid-fight; a log budget (a kit constant), not match behaviour. */
    static final int SHOT_LINE_MAX = 60000;
    static final int OUT_LINE_MAX = 60000;
    static final int APPLY_LINE_MAX = 60000;
    /** Apply-row down-sampling: past {@link #APPLY_LINE_MAX} the apply lane DOWN-SAMPLES
     *  instead of going dark — every further apply (own + remote + beam flush) folds into a
     *  per-(victim, tick-window) cell — except a kill row ({@link #KILL_LINE_MAX}) or a walker-reserve
     *  row ({@link #APPLY_RESERVE_PER_VICTIM}), which keep their full row —
     *  flushed as ONE {@code dmg-apply-agg} row per victim per window
     *  when the window closes (tick walk, round reset, teardown), so a 41-min flight stays auditable
     *  to its last tick. The window MUST equal {@code audit-dmg-delta.ps1 -WindowTicks} (default 300;
     *  the row carries {@code w=} so the auditor guards a mismatch). The cell's {@code hull=} mirrors
     *  the auditor's per-row rules exactly: a stash-missing row is counted, never summed (garbage
     *  engine figure); hull is clamped to {@code hpB}; a beam flush lands at its {@code tickLast}. */
    static final int AGG_WINDOW_TICKS = 300;
    static final int AGG_LINE_MAX = 20000;
    /** Full per-hit KILL rows printed past
     *  {@link #APPLY_LINE_MAX} (tagged {@code [kill row past line cap]}, never folded into a cell);
     *  past this bound a kill folds like any other apply. A kill = the LETHAL TRANSITION only
     *  ({@link HullCapBound#lethalTransition}: alive-or-unknown before, hull &le; 0 after — corpse
     *  hits never spend it). Projectile applies only: a beam-dealt lethal tick still
     *  folds through {@code foldBeamTick} (meter (a)'s one-shot row covers it). */
    static final int KILL_LINE_MAX = 2000;
    /** The per-victim WALKER RESERVE. Past
     *  {@link #APPLY_LINE_MAX} an apply (projectile row or beam flush) whose victim token names a
     *  CURRENT parity-walker subject ({@link ClientParityWalker#isWalkSubject}: {@code remote:<id>},
     *  or {@code own} = the session ship id) keeps its FULL row, tagged {@code [walker reserve]} at
     *  the kill tag's place and NOT folded into a cell, up to {@link #APPLY_RESERVE_PER_VICTIM} rows
     *  per victim for at most {@link #APPLY_RESERVE_VICTIMS} victims (a 17th folds as before).
     *  A recorded match spent the cap at ~35.8k presents and blinded walker subjects 289/282's
     *  per-hit attribution. Walker OFF = inert; stale bodies and the id-less 1v1 {@code foe}
     *  token never reserve. Seq continues past the kill range ({@code #APPLY_LINE_MAX+KILL_LINE_MAX+n}). */
    static final int APPLY_RESERVE_PER_VICTIM = 2000;
    static final int APPLY_RESERVE_VICTIMS = 16;
    /** Beam DPS aggregation: flush cadence + no-tick staleness close (frames). */
    static final int BEAM_FLUSH_FRAMES = 30;
    static final int BEAM_STALE_FRAMES = 10;
    /** armor-cell slots per beam aggregate ({@link BeamAgg#foldCell});
     *  a damaging tick on a 9th distinct cell counts into the flush row's {@code cellMore=}. */
    static final int BEAM_CELL_SLOTS = 8;
    /** Flood-fold census: max distinct (source→victim) pairs tracked (overflow counted, not lined). */
    static final int FLOOD_PAIR_MAX = 64;

    private final boolean enabled;
    /** The plugin's live replay-driven replica set (read-only view; render thread). */
    private final Set<ShipAPI> replicaShips;
    /** Mtrack + session handle for the own-predicted register arm. */
    private final PredictReconcileCombatPlugin plugin;

    // --- render-thread clock cache (refreshed each tick; lane-2 hooks read these) ---
    private ShipAPI ownShip;
    private long frame;
    private long snapTick;

    // --- lane 1: tracked shells (own + tagged presents), retire on vanish ---
    private final IdentityHashMap<Object, Shot> tracked = new IdentityHashMap<Object, Shot>();
    private int shotLines;
    private int outLines;
    private long shotsOwn;
    private long shotsAutofire;      // subset of shotsOwn: autofire-TRACKER shells, selected or not (r10)
    private long shotsAutofireSelected; // subset of shotsAutofire: tracker-ON shells from the SELECTED group
    private long presentsTagged;
    private long outsHit;
    private long outsMiss;
    private long droppedAtReset;

    // --- lane 2: per-victim pre-state stash (armed modify -> consumed report, same hit) ---
    private final IdentityHashMap<Object, Stash> stashes = new IdentityHashMap<Object, Stash>();
    private int applyLines;
    private int killLinesPastCap; // apply_kill_lines_past_cap
    // The walker reserve's bounded victim map (parallel
    // arrays, <= APPLY_RESERVE_VICTIMS slots, linear probe — no boxing) + its three exported meters.
    private final long[] reserveIds = new long[APPLY_RESERVE_VICTIMS];
    private final int[] reserveRowsBy = new int[APPLY_RESERVE_VICTIMS];
    private int reserveVictims;   // dmg_apply_reserve_victims
    private int reserveRows;      // dmg_apply_reserve_rows
    private long reserveRefused;  // dmg_apply_reserve_refused: subject rows a cap refused (folded) + malformed remote: tokens
    // Last compact target-stat signature per victim ship (identity-keyed,
    // weak: bodies die with their round). A first sight / CHANGED signature emits one verbose
    // dmg-tstat line (the per-modifier attribution) — bounded; the compact sig rides every row.
    private final java.util.WeakHashMap<ShipAPI, String> lastTsigByShip =
            new java.util.WeakHashMap<ShipAPI, String>();
    private int tstatLines;
    static final int TSTAT_LINE_MAX = 400;
    // Last compact DEALT-side signature per SHOOTER ship (the owner
    // skills-hypothesis census) — the tsig pattern on the other end of the formula.
    private final java.util.WeakHashMap<ShipAPI, String> lastDsigByShip =
            new java.util.WeakHashMap<ShipAPI, String>();
    private int dsigLines;
    static final int DSIG_LINE_MAX = 400;
    // The EX-REPLICA / STRANGER split. A dead replica's last ordnance lands
    // 1-2 ticks AFTER its kill-apply, by which time the body has left replicaShips and its
    // RemoteEntity.ship is nulled at the reap — so "not in the
    // replica set" is NOT "never bound" (6 of 21 "Ship@" ids were bound). This
    // map remembers every body that ever rode the replica set (walked in tick(); weak = bodies
    // die with their round) with its wire entity id, so the shooter token reads
    // ex-replica:<id>@hex. Anything else that is not own / own-wing / own-module / hulk-piece
    // (see isPiece below) = STRANGER — the standing detector for a local mint the wire
    // never announced; zero on a clean flight. Census = strangersSeen (any classify
    // seam) + the alive walk at teardown; pieces are counted apart (pieces=) and never graded.
    private final java.util.WeakHashMap<ShipAPI, Long> everReplica =
            new java.util.WeakHashMap<ShipAPI, Long>();
    /** Hulk pieces seen at any classify seam (distinct bodies; weak = die with their round). */
    private final java.util.WeakHashMap<ShipAPI, Boolean> piecesSeen =
            new java.util.WeakHashMap<ShipAPI, Boolean>();
    private int pieceIdsSeen;        // strong count (the weak map may shrink)
    /** The engine's SYNTHETIC SOURCE pods — {@code CombatEngine.spawnProjectile}
     *  with a null ship mints a bare {@code shuttlepod_xfer} {@code Ship} per call
     *  (never init'd / never in play / owner 0) and hands it
     *  to the projectile as its source; our sourceless missile adopts + recipe remints
     *  ({@code MissilePoseLane}) take that path, so every such projectile's hit reports the pod
     *  as {@code source}. Counted apart (syntheticPods=), never a STRANGER, never graded. */
    private final java.util.WeakHashMap<ShipAPI, Boolean> syntheticPodsSeen =
            new java.util.WeakHashMap<ShipAPI, Boolean>();
    private int syntheticPodIdsSeen; // strong count (the weak map may shrink)
    /** Stranger -> {firstSightTick, applyRows, hullSum}. */
    private final java.util.WeakHashMap<ShipAPI, double[]> strangersSeen =
            new java.util.WeakHashMap<ShipAPI, double[]>();
    private int strangerIdsSeen;     // strong count (the weak map may shrink)
    static final int STRANGER_LINE_MAX = 40;
    /** The classify SEAM that registers a stranger — each caller stamps
     *  its tag + row context BEFORE its classify call; the first-sight row prints them (the
     *  flight-2 rows named no seam, and no other row carried the hex). Plain fields, single-threaded. */
    private String seamTag = "?";
    private String seamCtx = "";
    private CombatEngineAPI engine;  // cached by tick() for the teardown census walk
    private long applies;
    private long armsTaken;
    private long armMissing;         // report with no stash (unexpected path)
    private long armStale;           // stash from an older frame (report never came)
    private long armOverwrite;       // same-frame double-arm before a report consumed
    private long beamTicks;
    private long beamFlushes;
    private long beamDroppedAtReset;
    private final ArrayList<BeamAgg> beamAggs = new ArrayList<BeamAgg>();
    // --- R5 down-sample cells past the apply line cap (key = victim + '@' + window; render thread) ---
    private final LinkedHashMap<String, AggCell> aggCells = new LinkedHashMap<String, AggCell>();
    private int aggLines;
    private long aggFolded;
    // --- flood fold: per-(source→victim) {count, tickFirst, tickLast}; cumulative like the counters ---
    private final LinkedHashMap<String, long[]> floodPairs = new LinkedHashMap<String, long[]>();
    private long floodFolded;
    private long floodPairOverflow;
    private long armlessNonzero;     // NONZERO armless lines seen (would be a modify-bypassing damage path)
    /** The lane-1 missile KILLER feed — an engine-global
     *  {@link DamageListener} (registered by the plugin's served-window init) that records who damaged a
     *  TRACKED missile; {@link #retireShot} takes the record into the missile row's {@code killer=} tokens.
     *  Declared below every field it reads ({@code tracked}, {@code snapTick}, {@code engine},
     *  {@code ownShip}); {@code enabled=false} leaves {@code tracked} empty, so the feed records nothing. */
    private final MissileKillerFeed killerFeed = new MissileKillerFeed(tracked::containsKey, () -> snapTick,
            () -> engine, () -> ownShip);

    DamageWitness(boolean enabled, Set<ShipAPI> replicaShips, PredictReconcileCombatPlugin plugin) {
        this.enabled = enabled;
        this.replicaShips = replicaShips;
        this.plugin = plugin;
    }

    /** The killer feed, for the plugin's ONE engine-global
     *  registration ({@code PredictReconcileCombatPlugin} served-window init, beside the own-missile-hit feed). */
    DamageListener missileKillerFeed() {
        return killerFeed;
    }

    /** The death-puff presenter's gate on the feed this
     *  witness owns ({@code SourcedMissileEndLane.presentDeathPuff}, set/cleared in a {@code finally}). */
    void missileKillerPresenting(boolean on) {
        killerFeed.presenting(on);
    }

    /** One tracked shell: spawn record + last-seen refresh (retire = vanished from the live list). */
    private static final class Shot {
        final DamagingProjectileAPI proj;
        final String role;           // "own" | "presented"
        final long shooterEntityId;  // presented only; -1 for own (own IS the session ship)
        final int mount;
        final String weaponId;
        final String specId;
        final long spawnTick;
        final float spawnX, spawnY, heading;
        final boolean autofire;
        final long presentAgeTicks;  // presented only: the catch-up age at spawn
        long lastFrame;
        /** The witness {@code frame} at registration (−1 = never stamped) — the apply row's {@code pFrames} base. */
        long spawnFrame = -1L;
        long lastTick;
        float lastX, lastY, lastElapsed;
        float lastHp = Float.NaN;    // missiles only: last live-scan getHitpoints() (NaN = never read)

        Shot(DamagingProjectileAPI proj, String role, long shooterEntityId, int mount,
                String weaponId, String specId, long spawnTick, float spawnX, float spawnY,
                float heading, boolean autofire, long presentAgeTicks) {
            this.proj = proj;
            this.role = role;
            this.shooterEntityId = shooterEntityId;
            this.mount = mount;
            this.weaponId = weaponId;
            this.specId = specId;
            this.spawnTick = spawnTick;
            this.spawnX = spawnX;
            this.spawnY = spawnY;
            this.heading = heading;
            this.autofire = autofire;
            this.presentAgeTicks = presentAgeTicks;
        }
    }

    /** Lane-2 pre-state, taken at modifyDamageTaken BEFORE its gate can early-return.
     *  The hit-point CELL context ({@code cellX/cellY} = the grid indices under the
     *  impact point, −1 = unmapped e.g. a shield hit outside the grid; {@code cellPre} = that
     *  cell's pre-hit value; {@code maskPre} = the 21-cell (5×5 minus corners) local pre sum). */
    private static final class Stash {
        final long frame;
        final float pointX, pointY;
        final boolean shieldHit;
        final boolean shieldOn;
        final float shieldFacing;
        final float armorTotal;
        final float hpBefore;
        final int cellX, cellY;
        /** The hit cell's v11 ordinal {@code cellX * h + cellY}
         *  ({@code h} = the grid's column length at arm — the {@link DamageWitness#cellFields} law, which
         *  still computes its own {@code cell=} from the emit-time grid), −1 when {@code cellX < 0}. Read
         *  only by the beam cell fold ({@link BeamAgg#foldCell}). */
        final int cellOrd;
        final float cellPre, maskPre;

        Stash(long frame, float pointX, float pointY, boolean shieldHit, boolean shieldOn,
                float shieldFacing, float armorTotal, float hpBefore,
                int cellX, int cellY, int cellOrd, float cellPre, float maskPre) {
            this.frame = frame;
            this.pointX = pointX;
            this.pointY = pointY;
            this.shieldHit = shieldHit;
            this.shieldOn = shieldOn;
            this.shieldFacing = shieldFacing;
            this.armorTotal = armorTotal;
            this.hpBefore = hpBefore;
            this.cellX = cellX;
            this.cellY = cellY;
            this.cellOrd = cellOrd;
            this.cellPre = cellPre;
            this.maskPre = maskPre;
        }
    }

    /** One open (source, target) beam-DPS aggregate; identity keys, labels resolved at open. */
    private static final class BeamAgg {
        final Object source;
        final Object target;
        final String srcTok;
        final String victim;
        final long openFrame;
        long lastFrame;
        long tickFirst, tickLast;
        int ticks;
        float hullSum, armorSum, shieldSum, empSum;
        boolean lastShieldHit;
        float lastArmorTotal = Float.NaN;
        float lastHp = Float.NaN;
        /** The per-CELL fold of this segment's DAMAGING ticks, so
         *  the C3 design (kit Σapplied vs server truth) can see whether the kit's beam damage lands on the
         *  server's cells. Slots {@code [0, cellN)} = distinct v11 ordinals ({@link Stash#cellOrd}) in
         *  FIRST-SEEN order, at most {@link #BEAM_CELL_SLOTS}, each with its tick count and the engine's
         *  Σ{@code getTotalDamageToArmor()} / Σ{@code getDamageToHull()}; {@code cellNone} = damaging ticks
         *  whose stash had no cell (stash missing/stale, or {@code cellX < 0}); {@code cellMore} = damaging
         *  ticks whose new cell found every slot taken. A tick with no positive finite armor or hull damage
         *  (a shield tick) folds nowhere — the server's {@code HullSourceLedger.record} returns before
         *  minting a slot for it. Allocated at the segment's FIRST celled damaging tick (a
         *  shield-only segment, re-opened every flush / shield flip, allocates nothing); printed on the {@code dmg-apply-beam} flush
         *  row only ({@link #cellTokens}), never on the post-cap {@code dmg-apply-agg} fold.
         *  DELETE TRIGGER: these fields and their row tokens are deleted when the C3 design
         *  closes, or at the first armed flight grade whose {@code join-perhit.py} report
         *  leaves {@code beam_cell_*} unread. */
        int[] cellOrd;
        int[] cellTicks;
        float[] cellArmor;
        float[] cellHull;
        int cellN;
        int cellNone;
        int cellMore;

        BeamAgg(Object source, Object target, String srcTok, String victim, long openFrame,
                long tick, boolean shieldHit) {
            this.source = source;
            this.target = target;
            this.srcTok = srcTok;
            this.victim = victim;
            this.openFrame = openFrame;
            this.lastFrame = openFrame;
            this.tickFirst = tick;
            this.tickLast = tick;
            this.lastShieldHit = shieldHit;
        }

        /** Fold ONE damaging tick into its cell slot — a
         *  &le; {@link #BEAM_CELL_SLOTS} linear probe + 3 adds; no allocation, no string work, no map.
         *  {@code ord < 0} = the stash had no cell. */
        void foldCell(int ord, float armor, float hull) {
            if (ord < 0) {
                cellNone++;
                return;
            }
            if (cellOrd == null) { // cellN == 0 until here, so the scan below and cellTokens never read a null array
                cellOrd = new int[BEAM_CELL_SLOTS];
                cellTicks = new int[BEAM_CELL_SLOTS];
                cellArmor = new float[BEAM_CELL_SLOTS];
                cellHull = new float[BEAM_CELL_SLOTS];
            }
            int i = 0;
            while (i < cellN && cellOrd[i] != ord) {
                i++;
            }
            if (i == cellN) {
                if (cellN >= BEAM_CELL_SLOTS) {
                    cellMore++;
                    return;
                }
                cellOrd[cellN++] = ord;
            }
            cellTicks[i]++;
            cellArmor[i] += armor;
            cellHull[i] += hull;
        }

        /** The flush row's cell tokens — the cross-lane contract
         *  {@code join-perhit.py} parses: {@code " cells=<ord>,… cellTicks=<n>,… cellArmor=<f>,…
         *  cellHull=<f>,…"} when a slot folded, then {@code " cellNone=<n>"} / {@code " cellMore=<n>"} when
         *  nonzero; {@code ""} when no damaging tick folded (a shield-only segment's row is unchanged).
         *  Floats print via {@code StringBuilder.append(float)} ({@code Float.toString}). Flush-time only. */
        String cellTokens() {
            if (cellN == 0 && cellNone == 0 && cellMore == 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder(48 + cellN * 32);
            if (cellN > 0) {
                sb.append(" cells=");
                for (int i = 0; i < cellN; i++) {
                    sb.append(i == 0 ? "" : ",").append(cellOrd[i]);
                }
                sb.append(" cellTicks=");
                for (int i = 0; i < cellN; i++) {
                    sb.append(i == 0 ? "" : ",").append(cellTicks[i]);
                }
                sb.append(" cellArmor=");
                for (int i = 0; i < cellN; i++) {
                    sb.append(i == 0 ? "" : ",").append(cellArmor[i]);
                }
                sb.append(" cellHull=");
                for (int i = 0; i < cellN; i++) {
                    sb.append(i == 0 ? "" : ",").append(cellHull[i]);
                }
            }
            if (cellNone > 0) {
                sb.append(" cellNone=").append(cellNone);
            }
            if (cellMore > 0) {
                sb.append(" cellMore=").append(cellMore);
            }
            return sb.toString();
        }
    }

    /** One R5 down-sample cell: every apply on {@code victim} whose tick falls in {@code window}. */
    private static final class AggCell {
        final String victim;
        final long window;
        long tickFirst, tickLast;
        int rows, beams, missing, clamped, kills;
        double hull, armor, shield, emp;
        float hpBMax = Float.NaN;

        AggCell(String victim, long window, long tick) {
            this.victim = victim;
            this.window = window;
            this.tickFirst = tick;
            this.tickLast = tick;
        }
    }

    /**
     * Lane 1 — one LOCAL own discharge (called from BOTH detection lanes right where they
     * bump {@code localOwnDischarges}, so unselected-autofire shells are included; the
     * cross-lane {@code seenOwnProj} dedup upstream guarantees exactly one call per shell).
     */
    void onOwnDischarge(long snapTick, DamagingProjectileAPI p, ShipAPI own) {
        if (!enabled || p == null) {
            return;
        }
        try {
            int mount = -1;
            String weaponId = "?";
            boolean autofire = false;
            boolean selected = false;
            WeaponGroupAPI grp = null;
            WeaponAPI w = p.getWeapon();
            if (w != null && own != null) {
                mount = own.getAllWeapons().indexOf(w);
                weaponId = w.getId();
                grp = own.getWeaponGroupFor(w);
                // engine-slew-owned aim = autofire, selected or not (label
                // only; the decl lane's selected-group SEND clause is separate and unchanged).
                autofire = grp != null && grp.isAutofiring();
                // sel= splits that label — the engine suspends the SELECTED
                // group's autofire on the playerShip (continuous gate;
                // the tracker stays ON), so auto=true sel=true shells are MANUAL fire under
                // suspension; only auto=true sel=false shells are genuine engine autofire.
                selected = grp != null && grp == own.getSelectedGroupAPI();
            }
            Vector2f loc = p.getLocation();
            float x = loc != null ? loc.x : Float.NaN;
            float y = loc != null ? loc.y : Float.NaN;
            Shot s = new Shot(p, "own", -1L, mount, weaponId, p.getProjectileSpecId(),
                    snapTick, x, y, p.getFacing(), autofire, 0L);
            s.lastFrame = frame;
            s.spawnFrame = frame;
            s.lastTick = snapTick;
            s.lastX = x;
            s.lastY = y;
            tracked.put(p, s);
            // The own-predicted mtrack arm — same instrument, via=predict,
            // so the offline differ joins own missile flight paths to their wire twins (own
            // bodies never link ⇒ wire=-1; the source-pair fallback claims them). Knobbed OFF
            // by default; log-only (RAW-safe). Non-missiles no-op inside register.
            if (PresentedMissileTrack.MTRACK_PREDICT) {
                plugin.mtrack.register(p, plugin.session != null ? plugin.session.shipId() : -1L,
                        mount, snapTick, "predict", null);
            }
            // link-only NUDGE enroll of own PREDICTED guided missiles —
            // knob-gated + classified inside (non-missiles/ballistic/flares no-op); independent
            // of the mtrack arm above. This witness callback is the ratified own-birth site.
            plugin.missileLane.enrollOwn(p,
                    plugin.session != null ? plugin.session.shipId() : -1L, mount, snapTick);
            shotsOwn++;
            if (autofire) {
                shotsAutofire++;
                if (selected) {
                    shotsAutofireSelected++;
                }
            }
            if (shotLines < SHOT_LINE_MAX) {
                shotLines++;
                // tgt= = the LOCAL autofire pick at fire time (this weapon's
                // AutofireAIPlugin ship target) mapped to its wire id — TARGET_ABSENT(-1) = no
                // plugin to sample, TARGET_NONE(-2) = plugin held no ship pick; tgtm=1 = the pick
                // was an ORDNANCE body (getTargetMissile), the PD ship-vs-ordnance discriminator.
                // Public-API reads only (B-legal), inside the line cap so post-cap cost is zero.
                long tgtWire = cc.starsector.server.runtime.PilotIntentFrame.TARGET_ABSENT;
                boolean tgtMissile = false;
                if (w != null && grp != null) {
                    com.fs.starfarer.api.combat.AutofireAIPlugin af = grp.getAutofirePlugin(w);
                    if (af != null) {
                        tgtWire = plugin.wireIdForLocalShip(af.getTargetShip());
                        tgtMissile = af.getTargetMissile() != null;
                    }
                }
                PilotWatchLog.info("predict dmg-shot #" + shotLines + ": tick=" + snapTick
                        + " mount=" + mount + " weapon=" + weaponId + " spec=" + s.specId
                        + " loc=" + x + "," + y + " heading=" + s.heading + " auto=" + autofire
                        + " sel=" + selected + " tgt=" + tgtWire + " tgtm=" + (tgtMissile ? 1 : 0)
                        + (shotLines == SHOT_LINE_MAX ? " [line cap reached; counters continue]" : ""));
            }
        } catch (Throwable ignore) {
            // witness lane: never break the discharge path it observes
        }
    }

    /**
     * Lane 1 — tag a presented spawn at its spawn instant (wire identity in scope; the
     * {@code tagPresented} precedent). No line — the presenter already logs every present;
     * the tag buys the retire outcome + lane-2 source attribution.
     */
    void tagPresented(Object spawned, ClientGameplayEdgeEvent event, long ageTicks) {
        if (!enabled || !(spawned instanceof DamagingProjectileAPI)) {
            return; // beam presents carry no projectile; their damage lands in lane 2 by shooter
        }
        try {
            DamagingProjectileAPI p = (DamagingProjectileAPI) spawned;
            Vector2f loc = p.getLocation();
            float x = loc != null ? loc.x : Float.NaN;
            float y = loc != null ? loc.y : Float.NaN;
            Shot s = new Shot(p, "presented", event.entityId(), event.mountIndex(),
                    "?", p.getProjectileSpecId(), event.simTick(), x, y, p.getFacing(),
                    false, ageTicks);
            s.lastFrame = frame;
            s.spawnFrame = frame;
            s.lastTick = snapTick;
            s.lastX = x;
            s.lastY = y;
            tracked.put(p, s);
            presentsTagged++;
        } catch (Throwable ignore) {
            // a tag failure must never break the presentation that already happened
        }
    }

    /** Lane-2 source attribution: the presented-tag map (identity), for the cap hooks. */
    private Shot presentTag(Object proj) {
        Shot s = tracked.get(proj);
        return s != null && "presented".equals(s.role) ? s : null;
    }

    /**
     * Per-frame pass (piggybacked beside the fuse-meter tick): refresh clock cache +
     * tracked shells' last-seen, retire vanished shells with their engine-recorded
     * outcome, and walk the beam-aggregate flush cadence.
     */
    void tick(CombatEngineAPI engine, ShipAPI own, long snapTick, long frame) {
        if (!enabled) {
            return;
        }
        this.ownShip = own;
        this.snapTick = snapTick;
        this.frame = frame;
        this.engine = engine;
        if (!aggCells.isEmpty()) { // R5: close every down-sample window the tick walked past
            try {
                flushAggBefore(Math.floorDiv(snapTick, (long) AGG_WINDOW_TICKS));
            } catch (Throwable ignore) {
                // witness lane: a log failure never reaches the engine advance
            }
        }
        // Remember every body that rides the replica set (containsKey per replica per
        // frame — a hash probe each, no engine read; the id walk runs ONCE per new replica).
        try {
            if (replicaShips != null) {
                for (ShipAPI s : replicaShips) {
                    if (!everReplica.containsKey(s)) {
                        everReplica.put(s, Long.valueOf(entityIdOf(s)));
                    }
                }
            }
        } catch (Throwable ignore) {
            // a concurrent bind/reap edit skips this frame's walk; the next frame catches up
        }
        if (engine == null) {
            return;
        }
        try {
            List<DamagingProjectileAPI> live = engine.getProjectiles();
            for (DamagingProjectileAPI p : live) {
                Shot s = tracked.get(p);
                if (s != null) {
                    s.lastFrame = frame;
                    s.lastTick = snapTick;
                    s.lastElapsed = p.getElapsed();
                    Vector2f loc = p.getLocation();
                    if (loc != null) {
                        s.lastX = loc.x;
                        s.lastY = loc.y;
                    }
                    // ONE getHitpoints() per tracked MISSILE
                    // per frame, inside the existing loop — the retire row's hp= token.
                    if (p instanceof MissileAPI) {
                        s.lastHp = p.getHitpoints();
                    }
                }
            }
            if (!tracked.isEmpty()) {
                for (Iterator<Shot> it = tracked.values().iterator(); it.hasNext();) {
                    Shot s = it.next();
                    if (s.lastFrame == frame) {
                        continue;
                    }
                    it.remove();
                    retireShot(s);
                }
            }
            if (!beamAggs.isEmpty()) {
                for (Iterator<BeamAgg> it = beamAggs.iterator(); it.hasNext();) {
                    BeamAgg a = it.next();
                    if (frame - a.openFrame >= BEAM_FLUSH_FRAMES
                            || frame - a.lastFrame > BEAM_STALE_FRAMES) {
                        it.remove();
                        flushBeamAgg(a);
                    }
                }
            }
        } catch (Throwable ignore) {
            // witness lane: never break the watch on a scan hiccup
        }
    }

    /** Retire line: the engine's own per-shell outcome (didDamage/target), all specs. */
    private void retireShot(Shot s) {
        boolean hit = false;
        String target = "none";
        try {
            hit = s.proj != null && s.proj.didDamage();
            CombatEntityAPI t = s.proj != null ? s.proj.getDamageTarget() : null;
            if (t != null) {
                seamTag = "retire";
                seamCtx = " spec=" + s.specId + " role=" + s.role + " outLines=" + outLines;
                target = classifyEntity(t);
            }
        } catch (Throwable ignore) {
            // outcome read is best-effort; the line still records the flight
        }
        if (hit) {
            outsHit++;
        } else {
            outsMiss++;
        }
        // MISSILE rows gain the hp/fading/killer tokens; the
        // feed record is taken on EVERY missile retire (past the line cap too — the map must drain);
        // the graders' regexes anchor on the prefix. On EVERY
        // row `frame=` is the last token before the cap suffix = s.lastFrame, the last frame the body was in getProjectiles() (its
        // track frame if never scanned) — the SAME `frame` field (the plugin's advFrames) dmg-apply prints.
        boolean missile = s.proj instanceof MissileAPI;
        MissileKillerFeed.Killer k = missile ? killerFeed.take(s.proj) : null;
        if (outLines < OUT_LINE_MAX) {
            outLines++;
            PilotWatchLog.info("predict dmg-shot-out #" + outLines + ": role=" + s.role
                    + (s.shooterEntityId >= 0 ? " entity=" + s.shooterEntityId : "")
                    + " mount=" + s.mount + " spec=" + s.specId
                    + " tick=" + s.spawnTick + " outTick=" + s.lastTick
                    + " didDamage=" + hit + " target=" + target
                    + " last=" + s.lastX + "," + s.lastY
                    + " ageTicks=" + (s.lastTick - s.spawnTick) + " flightSec=" + s.lastElapsed
                    + (missile ? missileRetireTokens(s, k) : "")
                    + (s.presentAgeTicks != 0L ? " presentAge=" + s.presentAgeTicks : "")
                    + " frame=" + s.lastFrame
                    + (outLines == OUT_LINE_MAX ? " [line cap reached; counters continue]" : ""));
        }
    }

    /** The MISSILE row's appended tokens —
     *  {@code hp=} (last live-scan hitpoints, {@code -1} = never read) {@code fading=} (the engine's
     *  own flag at retire, {@code ?} = the read threw — the row itself is that catch's record)
     *  {@code killer= ksrc= khits= kdmg= ktick=} ({@link MissileKillerFeed}; no record = {@code none}/0/-1). */
    private static String missileRetireTokens(Shot s, MissileKillerFeed.Killer k) {
        String fading;
        try {
            fading = String.valueOf(((MissileAPI) s.proj).isFading());
        } catch (Throwable t) {
            fading = "?";
        }
        return " hp=" + (Float.isNaN(s.lastHp) ? "-1" : String.format(java.util.Locale.ROOT, "%.1f", s.lastHp))
                + " fading=" + fading
                + " killer=" + (k != null && k.killer != null ? k.killer : "none")
                + " ksrc=" + (k != null && k.src != null ? k.src : "none")
                + " khits=" + (k != null ? k.hits : 0)
                + " kdmg=" + String.format(java.util.Locale.ROOT, "%.1f", k != null ? k.dmg : 0f)
                + " ktick=" + (k != null ? k.lastTick : -1L);
    }

    /**
     * Lane 2 arm — FIRST statement of the victim cap's {@code modifyDamageTaken} (before
     * its shieldHit/cap-state early return): stash the victim's impact-instant pre-state.
     */
    void armApply(CombatEntityAPI target, Object source, Vector2f point, boolean shieldHit) {
        if (!enabled || target == null) {
            return;
        }
        try {
            boolean shieldOn = false;
            float shieldFacing = Float.NaN;
            ShieldAPI shield = target.getShield();
            if (shield != null) {
                shieldOn = shield.isOn();
                shieldFacing = shield.getFacing();
            }
            // impact-point cell context (pre-hit) — best-effort, never gates the stash.
            int cellX = -1, cellY = -1, cellOrd = -1;
            float cellPre = Float.NaN, maskPre = Float.NaN;
            if (point != null && target instanceof ShipAPI) {
                try {
                    ArmorGridAPI grid = ((ShipAPI) target).getArmorGrid();
                    float[][] cells = grid != null ? grid.getGrid() : null;
                    int[] cell = grid != null ? grid.getCellAtLocation(point) : null;
                    if (cell != null && cell.length >= 2 && cells != null && cells.length > 0
                            && cells[0] != null && cell[0] >= 0 && cell[0] < cells.length
                            && cell[1] >= 0 && cell[1] < cells[0].length) {
                        cellX = cell[0];
                        cellY = cell[1];
                        cellOrd = cellX * cells[0].length + cellY; // The beam cell fold's key
                        cellPre = cells[cellX][cellY];
                        maskPre = maskSum(cells, cellX, cellY);
                    }
                } catch (Throwable ignore) {
                    // cell context is best-effort; the stash still records the hit
                }
            }
            Stash prev = stashes.put(target, new Stash(frame,
                    point != null ? point.x : Float.NaN, point != null ? point.y : Float.NaN,
                    shieldHit, shieldOn, shieldFacing, armorTotal(target), target.getHitpoints(),
                    cellX, cellY, cellOrd, cellPre, maskPre));
            armsTaken++;
            if (prev != null && prev.frame == frame) {
                armOverwrite++;
            }
        } catch (Throwable ignore) {
            // witness lane: never break the damage path it observes
        }
    }

    /** D1: the {@link ReplicaOverflowGuard} verdict of the hit about to be emitted ({@code " rog=land"} /
     *  {@code drop} / {@code res} / {@code fail}; {@code ""} = unarmed) — printed on the per-hit row only (a beam
     *  tick folds into its aggregate; the meters count it). */
    String rogTok = "";

    /**
     * Lane 2 emit — in the victim cap's {@code reportDamageApplied}, after the raw-ledger
     * record and BEFORE {@code restoreFx} rewrites the result (the as-applied split).
     * Beam DPS ticks aggregate; everything else emits one line.
     */
    void emitApply(String victim, Object source, CombatEntityAPI target,
            ApplyDamageResultAPI result, float clampUpper) {
        emitApply(victim, source, target, result, clampUpper, null);
    }

    /** {@code replay} = the replica cap's armed wire-acked ARMOR
     *  replay (null = none) — its row tokens ({@link #replayTokens}). */
    void emitApply(String victim, Object source, CombatEntityAPI target,
            ApplyDamageResultAPI result, float clampUpper, ArmorCellLane.ReplayArm replay) {
        String rog = rogTok; // D1: one-shot — a replica cap sets it right before this call
        rogTok = "";
        if (!enabled || result == null) {
            return;
        }
        try {
            applies++;
            Stash st = null;
            Stash raw = stashes.remove(target);
            String stashState;
            if (raw == null) {
                stashState = "missing";
                armMissing++;
            } else if (raw.frame != frame) {
                stashState = "stale";
                armStale++;
            } else {
                stashState = "fresh";
                st = raw;
            }
            seamTag = "apply-src";
            seamCtx = " victim=" + victim + " type=" + result.getType()
                    + " srcClass=" + (source == null ? "null" : source.getClass().getSimpleName())
                    + " applyLines=" + applyLines + " dps=" + result.isDps();
            String srcTok = classifySource(source);
            noteReplicaLocal(victim, source, result);
            if (result.isDps()) {
                beamTicks++;
                foldBeamTick(source, target, srcTok, victim, result, st);
                return;
            }
            boolean allZero = result.getDamageToHull() == 0f
                    && result.getTotalDamageToArmor() == 0f
                    && result.getDamageToPrimaryArmorCell() == 0f
                    && result.getDamageToShields() == 0f
                    && result.getOverMaxDamageToShields() == 0f
                    && result.getEmpDamage() == 0f;
            boolean armless = "missing".equals(stashState);
            // The flood fold — signature-exact to the classification (stash-missing +
            // all-zero split + bare-entity source, the classifier ladder's "entity-" token — a
            // DamagingProjectileAPI IS a CombatEntityAPI, so the token, not instanceof, is the key):
            // census counters per (source→victim), NO line. Everything else keeps its line.
            if (armless && allZero && srcTok.startsWith("entity-")) {
                foldFlood(srcTok, victim);
                return;
            }
            if (armless && !allZero) {
                armlessNonzero++;
            }
            float hpAfter = Float.NaN;
            try {
                hpAfter = target != null ? target.getHitpoints() : Float.NaN;
            } catch (Throwable ignore) {
                // post-state read is best-effort
            }
            // The victim's damage-taken stat signature at hit time — the
            // client half of the both-ends "fixed" gate (server twin = the stat-sig dump on
            // the same getters). Verbose attribution once per instance / on change.
            String tsig = "";
            if (target instanceof ShipAPI) {
                ShipAPI victimShip = (ShipAPI) target;
                tsig = TargetStatSig.compact(victimShip);
                String prevSig = lastTsigByShip.get(victimShip);
                if ((prevSig == null || !prevSig.equals(tsig)) && tstatLines < TSTAT_LINE_MAX) {
                    lastTsigByShip.put(victimShip, tsig);
                    tstatLines++;
                    PilotWatchLog.info("predict dmg-tstat #" + tstatLines + ": tick=" + snapTick
                            + " victim=" + victim + " ship="
                            + Integer.toHexString(System.identityHashCode(victimShip))
                            + (prevSig == null ? " first" : " CHANGED") + " "
                            + TargetStatSig.verbose(victimShip)
                            + (tstatLines == TSTAT_LINE_MAX ? " [line cap reached]" : ""));
                }
            }
            // The SHOOTER-side dealt-sig census (first sight / CHANGED per shooter
            // instance) — real projectile applies only (beams aggregate above; the flood class
            // folded already). The row's ship= hex joins to the apply rows' src= tokens.
            ShipAPI shooter = HullCapBound.shooterOf(source); // The one copy
            if (shooter != null) {
                String dsig = DealtStatSig.compact(shooter);
                String prevD = lastDsigByShip.get(shooter);
                // classifySource above already ran classifyEntity on this shooter, so
                // a STRANGER is registered by now; its rows/hull accrue to the census record.
                double[] strangerRec = strangersSeen.get(shooter);
                if (strangerRec != null) {
                    strangerRec[1]++;
                    strangerRec[2] += Math.max(0f, result.getDamageToHull());
                }
                if ((prevD == null || !prevD.equals(dsig)) && dsigLines < DSIG_LINE_MAX) {
                    lastDsigByShip.put(shooter, dsig);
                    dsigLines++;
                    seamTag = "dsig";
                    seamCtx = " victim=" + victim + " dsigLines=" + dsigLines;
                    PilotWatchLog.info("predict dmg-dsig #" + dsigLines + ": tick=" + snapTick
                            + " frame=" + frame
                            + " shooter=" + classifyEntity(shooter) + " ship="
                            + Integer.toHexString(System.identityHashCode(shooter))
                            + (prevD == null ? " first" : " CHANGED")
                            + (strangerRec != null ? strangerFields(shooter) : "") + " "
                            + DealtStatSig.verbose(shooter)
                            + (dsigLines == DSIG_LINE_MAX ? " [line cap reached]" : ""));
                }
            }
            // A KILL apply past APPLY_LINE_MAX keeps its FULL
            // per-hit row (an earlier lethal row survived only as an agg cell with no src/hpA),
            // bounded by KILL_LINE_MAX on its own counter; applyLines is not bumped, so the row's seq
            // continues past the cap (#APPLY_LINE_MAX+n) and the row is NOT folded into any cell.
            boolean killPastCap = applyLines >= APPLY_LINE_MAX && killLinesPastCap < KILL_LINE_MAX
                    && HullCapBound.lethalTransition(st == null ? Float.NaN : st.hpBefore, hpAfter); // corpse hits never count
            // The walker reserve — asked ONLY past the cap and
            // after the kill test (a kill keeps its own counter + tag); false = fold as before.
            boolean reservePastCap = applyLines >= APPLY_LINE_MAX && !killPastCap && reserveAdmit(victim);
            if (applyLines < APPLY_LINE_MAX || killPastCap || reservePastCap) {
                int seq;
                String capTag;
                if (killPastCap) {
                    killLinesPastCap++;
                    seq = APPLY_LINE_MAX + killLinesPastCap;
                    capTag = " [kill row past line cap]";
                } else if (reservePastCap) {
                    seq = APPLY_LINE_MAX + KILL_LINE_MAX + reserveRows;
                    capTag = " [walker reserve]";
                } else {
                    applyLines++;
                    seq = applyLines;
                    capTag = applyLines == APPLY_LINE_MAX
                            ? " [line cap reached; down-sampling to dmg-apply-agg]" : "";
                }
                PilotWatchLog.info("predict dmg-apply #" + seq + ": tick=" + snapTick
                        + " frame=" + frame // sub-tick order (tick= is snapshot-coarse)
                        + " victim=" + victim + " src=" + srcTok + " type=" + result.getType()
                        + " hull=" + result.getDamageToHull()
                        + " armor=" + result.getTotalDamageToArmor()
                        + " armorCell=" + result.getDamageToPrimaryArmorCell()
                        + " shield=" + result.getDamageToShields()
                        + " shieldOver=" + result.getOverMaxDamageToShields()
                        + " emp=" + result.getEmpDamage()
                        + " clamp=" + clampUpper
                        + (st != null
                                ? " point=" + st.pointX + "," + st.pointY
                                        + " shieldHit=" + st.shieldHit + " vShieldOn=" + st.shieldOn
                                        + " vShieldFacing=" + st.shieldFacing + " vArmor=" + st.armorTotal
                                        + " hpB=" + st.hpBefore
                                        + cellFields(target, st) // cells-vs-shadow terms
                                : "")
                        + " hpA=" + hpAfter + " stash=" + stashState
                        + (tsig.isEmpty() ? "" : " tsig{" + tsig + "}")
                        + (armless && !allZero ? " ARMLESS_NONZERO" : "")
                        // D-OWNARMOR (b) instrument
                        + armorPoolSuffix(target, st)
                        + replayTokens(replay)
                        + rog
                        + remoteKinTokens(victim, source, target)
                        + capTag);
            } else {
                foldAgg(victim, result, st, stashState, hpAfter);
            }
        } catch (Throwable ignore) {
            // witness lane: never break the damage path it observes
        }
    }

    /**
     * The remote-victim kinematics tokens on the per-hit apply row, so an
     * offline grade can join a hit to the server's pose row and bolt at the same label and tell a late BOLT
     * from a displaced VICTIM: {@code vPos=x,y vFace=deg} = the local victim body's centre + facing at the
     * apply; {@code pSpd} = the hitting projectile's |velocity| (a ray reports its extender's head speed);
     * {@code pBorn} = the tracked shot's spawn label ({@link Shot#spawnTick}: the wire birth tick of a
     * presented bolt, the snapshot tick of an own shell); {@code pFrames} = this row's {@code frame} minus
     * the witness frame at the shot's registration. Unreadable = {@code NaN} / {@code -1} (no projectile
     * source, an untracked one). Remote victims only ({@code remote:} / {@code remote-stale:}); the beam
     * flush row prints none (a beam tick carries no projectile). Frame-loop law: plain getters on the objects
     * this row already holds, plus one identity-map probe — row-time only, nothing per frame.
     */
    private String remoteKinTokens(String victim, Object source, CombatEntityAPI target) {
        if (victim == null || !victim.startsWith("remote")) {
            return "";
        }
        Vector2f at = target == null ? null : target.getLocation();
        float face = target == null ? Float.NaN : target.getFacing();
        float pSpd = Float.NaN;
        long pBorn = -1L;
        long pFrames = -1L;
        if (source instanceof DamagingProjectileAPI) {
            Vector2f v = ((DamagingProjectileAPI) source).getVelocity();
            pSpd = v == null ? Float.NaN : (float) Math.hypot(v.x, v.y);
            Shot s = tracked.get(source);
            if (s != null) {
                pBorn = s.spawnTick;
                pFrames = s.spawnFrame < 0L ? -1L : frame - s.spawnFrame;
            }
        }
        return " vPos=" + (at == null ? Float.NaN + "," + Float.NaN : at.x + "," + at.y) + " vFace=" + face
                + " pSpd=" + pSpd + " pBorn=" + pBorn + " pFrames=" + pFrames;
    }

    /** The wire-acked tokens: {@code acked=1} on ANY row emitted inside an FHRL wire-acked
     *  window (the presenter's or a replay's); on an acked ARMOR replay the arm's key numbers too
     *  (the row's hull/hpA are the engine's PRESENTED strip, read before the POST restores it). */
    private String replayTokens(ArmorCellLane.ReplayArm arm) {
        String s = plugin != null && plugin.hullLane.wireAckedOpen() ? " acked=1" : "";
        return arm == null ? s : s + " replay=acked ackAge=" + arm.age + " ackSig=" + (arm.sumDelta / arm.sProj)
                + " ackS=" + arm.sProj + " ackDk=" + arm.dk + " maskUp=" + arm.maskUp
                // The hit's live-mask cost at emit — ackLive + hull = the hp_live meter delta
                + " ackLive=" + (plugin != null ? plugin.hullLane.armorCells().liveCostNow(arm) : Float.NaN)
                // The latest consumed stamp's server tick (the late-twin order bound)
                + " ackTmax=" + arm.hitTickMax;
    }

    /**
     * The {@code replica_local_hp} feed (the FOE cell lane's
     * {@link ArmorCellLane#noteReplicaLocalHull}; the own instance never sees a {@code remote:} victim): the hull of
     * EVERY apply on a bound {@code remote:<id>} body ({@code remote-stale:} excluded by the prefix) outside an FHRL
     * wire-acked window, minus the engine kill apply ({@code source == null} and hull &gt; 1e5 — the scratch grader's
     * filter). Called BEFORE the beam / flood / line-cap splits, so a printed row, a {@code dmg-apply-agg} fold and a
     * beam tick all count (a beam per tick: the same sum its {@code dmg-apply-beam} flush prints, with no teardown
     * flush-order dependence) — the offline grader's KEPT denominator without the {@link #APPLY_LINE_MAX} row cap.
     */
    private void noteReplicaLocal(String victim, Object source, ApplyDamageResultAPI result) {
        float hull = result.getDamageToHull();
        if (plugin == null || victim == null || !victim.startsWith("remote:") || plugin.hullLane.wireAckedOpen()
                || (source == null && hull > 1e5f)) {
            return;
        }
        plugin.hullLane.armorCells().noteReplicaLocalHull(hull);
    }

    // ---- R5 down-sample lane (past APPLY_LINE_MAX) ----

    /** Fold one post-cap projectile apply into its (victim, window) cell — the auditor's own per-row
     *  rules applied at the source so the cell's {@code hull=} equals the sum it would have formed. */
    private void foldAgg(String victim, ApplyDamageResultAPI result, Stash st, String stashState,
            float hpAfter) {
        aggFolded++;
        AggCell c = cellFor(victim, snapTick);
        c.rows++;
        c.armor += result.getTotalDamageToArmor();
        c.shield += result.getDamageToShields();
        c.emp += result.getEmpDamage();
        if (!Float.isNaN(hpAfter) && hpAfter <= 0f) {
            c.kills++;
        }
        if ("missing".equals(stashState)) {
            c.missing++; // the auditor skips stash-missing rows (garbage engine hull figure on kill frames)
            return;
        }
        float hull = result.getDamageToHull();
        if (st != null && !Float.isNaN(st.hpBefore)) {
            if (Float.isNaN(c.hpBMax) || st.hpBefore > c.hpBMax) {
                c.hpBMax = st.hpBefore;
            }
            if (hull > st.hpBefore) { // kill-frame overkill clamp (a ship loses only the hull it had)
                hull = Math.max(0f, st.hpBefore);
                c.clamped++;
            }
        }
        if (hull > 0f) {
            c.hull += hull;
        }
    }

    /** Fold one post-cap beam flush at its tickLast (the auditor attributes beam hull there). */
    private void foldAggBeam(BeamAgg a) {
        aggFolded++;
        AggCell c = cellFor(a.victim, a.tickLast);
        c.beams++;
        c.armor += a.armorSum;
        c.shield += a.shieldSum;
        c.emp += a.empSum;
        if (a.hullSum > 0f) {
            c.hull += a.hullSum;
        }
    }

    private AggCell cellFor(String victim, long tick) {
        long window = Math.floorDiv(tick, (long) AGG_WINDOW_TICKS);
        String key = victim + '@' + window;
        AggCell c = aggCells.get(key);
        if (c == null) {
            c = new AggCell(victim, window, tick);
            aggCells.put(key, c);
        }
        if (tick < c.tickFirst) {
            c.tickFirst = tick;
        }
        if (tick > c.tickLast) {
            c.tickLast = tick;
        }
        return c;
    }

    /** Emit + drop every cell whose window closed before {@code window} (a late beam fold into an
     *  older window re-opens a cell that flushes on the next walk — the auditor sums rows per bucket). */
    private void flushAggBefore(long window) {
        for (Iterator<AggCell> it = aggCells.values().iterator(); it.hasNext();) {
            AggCell c = it.next();
            if (c.window < window) {
                it.remove();
                emitAggRow(c);
            }
        }
    }

    private void flushAggAll() {
        for (Iterator<AggCell> it = aggCells.values().iterator(); it.hasNext();) {
            AggCell c = it.next();
            it.remove();
            emitAggRow(c);
        }
    }

    private void emitAggRow(AggCell c) {
        if (aggLines >= AGG_LINE_MAX) {
            return;
        }
        aggLines++;
        PilotWatchLog.info("predict dmg-apply-agg #" + aggLines + ": tick=" + (c.window * AGG_WINDOW_TICKS)
                + " tickFirst=" + c.tickFirst + " tickLast=" + c.tickLast + " w=" + AGG_WINDOW_TICKS
                + " victim=" + c.victim + " rows=" + c.rows + " beams=" + c.beams
                + " hull=" + c.hull + " armor=" + c.armor + " shield=" + c.shield + " emp=" + c.emp
                + " missing=" + c.missing + " clamped=" + c.clamped + " kills=" + c.kills
                + " hpBMax=" + c.hpBMax
                + (aggLines == AGG_LINE_MAX ? " [line cap reached; counters continue]" : ""));
    }

    /** Fold one beam DPS tick into its (source, target) aggregate; shieldHit flip flushes. */
    private void foldBeamTick(Object source, Object target, String srcTok, String victim,
            ApplyDamageResultAPI result, Stash st) {
        boolean shieldHit = st != null && st.shieldHit;
        BeamAgg agg = null;
        for (BeamAgg a : beamAggs) {
            if (a.source == source && a.target == target) {
                agg = a;
                break;
            }
        }
        if (agg != null && st != null && agg.lastShieldHit != shieldHit) {
            beamAggs.remove(agg);
            flushBeamAgg(agg); // shieldHit flip: close the segment at the boundary
            agg = null;
        }
        if (agg == null) {
            agg = new BeamAgg(source, target, srcTok, victim, frame, snapTick, shieldHit);
            beamAggs.add(agg);
        }
        agg.lastFrame = frame;
        agg.tickLast = snapTick;
        agg.ticks++;
        float hull = result.getDamageToHull();
        float armor = result.getTotalDamageToArmor();
        agg.hullSum += hull;
        agg.armorSum += armor;
        agg.shieldSum += result.getDamageToShields();
        agg.empSum += result.getEmpDamage();
        // The per-cell fold — a damaging tick only (the server's
        // HullSourceLedger.record gate: positive finite hull or armor); a shield tick folds no cell.
        // Each side folds as the server stores it (HullSourceLedger.record: a side that is
        // not positive-finite is 0), so a NaN / negative component never reaches the row. Pin: smoke row (g).
        float cellArmor = Float.isFinite(armor) && armor > 0f ? armor : 0f;
        float cellHull = Float.isFinite(hull) && hull > 0f ? hull : 0f;
        if (cellArmor > 0f || cellHull > 0f) {
            agg.foldCell(st == null || st.cellX < 0 ? -1 : st.cellOrd, cellArmor, cellHull);
        }
        if (st != null) {
            agg.lastShieldHit = st.shieldHit;
            agg.lastArmorTotal = st.armorTotal;
            agg.lastHp = st.hpBefore;
        }
    }

    private void flushBeamAgg(BeamAgg a) {
        beamFlushes++;
        // A walker subject's post-cap flush keeps its row too.
        boolean reserved = applyLines >= APPLY_LINE_MAX && reserveAdmit(a.victim);
        if (applyLines < APPLY_LINE_MAX || reserved) {
            int seq = reserved ? APPLY_LINE_MAX + KILL_LINE_MAX + reserveRows : ++applyLines;
            PilotWatchLog.info("predict dmg-apply-beam #" + seq + ": victim=" + a.victim
                    + " src=" + a.srcTok + " ticks=" + a.ticks
                    + " tickFirst=" + a.tickFirst + " tickLast=" + a.tickLast
                    + " hull=" + a.hullSum + " armor=" + a.armorSum
                    + " shield=" + a.shieldSum + " emp=" + a.empSum
                    + " shieldHitLast=" + a.lastShieldHit + " vArmorLast=" + a.lastArmorTotal
                    + " hpLast=" + a.lastHp
                    + a.cellTokens() // "" unless a damaging tick folded
                    + (reserved ? " [walker reserve]"
                            : applyLines == APPLY_LINE_MAX ? " [line cap reached; down-sampling to dmg-apply-agg]" : ""));
        } else {
            foldAggBeam(a);
        }
    }

    /** Admit ONE post-cap row into the walker reserve. Cost
     *  per call = a static walker read, an in-place id parse and a &le; 16-slot probe — no
     *  allocation; called only past {@link #APPLY_LINE_MAX}. False = the row folds as before. */
    private boolean reserveAdmit(String victim) {
        if (!ClientParityWalker.enabled() || victim == null) {
            return false; // walker OFF: the reserve is inert
        }
        long id = -1L;
        cc.starsector.client.net.PredictSession s = plugin == null ? null : plugin.session; // one read: the teardown may null it
        try {
            if (victim.startsWith("remote:")) {
                id = Long.parseLong(victim, 7, victim.length(), 10);
            } else if ("own".equals(victim) && s != null) {
                id = s.shipId(); // the walker's own subject = ownPose(session.shipId())
            }
        } catch (NumberFormatException e) {
            reserveRefused++; // a malformed token (never minted today) folds, counted
            return false;
        }
        if (!ClientParityWalker.isWalkSubject(id)) {
            return false;
        }
        int slot = -1;
        for (int i = 0; i < reserveVictims; i++) {
            if (reserveIds[i] == id) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            if (reserveVictims >= APPLY_RESERVE_VICTIMS) {
                reserveRefused++; // the 17th subject folds as before
                return false;
            }
            slot = reserveVictims++;
            reserveIds[slot] = id;
        }
        if (reserveRowsBy[slot] >= APPLY_RESERVE_PER_VICTIM) {
            reserveRefused++;
            return false;
        }
        reserveRowsBy[slot]++;
        reserveRows++;
        return true;
    }

    /**
     * Fold one flood report into its (source→victim) census pair: {count,
     * tickFirst, tickLast}. Pair keys carry the identity-hex tokens on purpose — the
     * concentration facts (per-victim single replica source; ONE foe-side source per seat)
     * stay witnessable from the census line alone now that the class emits no lines.
     */
    private void foldFlood(String srcTok, String victim) {
        floodFolded++;
        String key = srcTok + ">" + victim;
        long[] rec = floodPairs.get(key);
        if (rec == null) {
            if (floodPairs.size() >= FLOOD_PAIR_MAX) {
                floodPairOverflow++;
                return;
            }
            rec = new long[] {0L, snapTick, snapTick};
            floodPairs.put(key, rec);
        }
        rec[0]++;
        rec[2] = snapTick;
    }

    /** Sum the victim's armor grid (the offline join's client twin of the AWSS armorTotal). */
    private static float armorTotal(CombatEntityAPI target) {
        try {
            if (target instanceof ShipAPI) {
                ArmorGridAPI grid = ((ShipAPI) target).getArmorGrid();
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
            }
        } catch (Throwable ignore) {
            // armor read is best-effort
        }
        return Float.NaN;
    }

    /** Local 21-cell (5×5 minus corners — the vanilla splash footprint) sum
     *  around {@code (cx,cy)}, grid-clipped. */
    private static float maskSum(float[][] cells, int cx, int cy) {
        float sum = 0f;
        int w = cells.length;
        int h = cells[0].length;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                if (dx * dx + dy * dy == 8) {
                    continue; // the four corners
                }
                int x = cx + dx;
                int y = cy + dy;
                if (x >= 0 && x < w && y >= 0 && y < h) {
                    sum += cells[x][y];
                }
            }
        }
        return sum;
    }

    /** The same 21-cell footprint as v11 ordinals (x·h+y — the {@link ArmorCellLane.Run} law),
     *  grid-clipped; bounded 21-slot allocation per emitted row. */
    private static int[] maskOrdinals(int cx, int cy, int w, int h) {
        int[] out = new int[21];
        int n = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                if (dx * dx + dy * dy == 8) {
                    continue;
                }
                int x = cx + dx;
                int y = cy + dy;
                if (x >= 0 && x < w && y >= 0 && y < h) {
                    out[n++] = x * h + y;
                }
            }
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /**
     * The per-hit armor-state terms: hit cell (v11 ordinal) pre/post from the LIVE
     * grid, 21-cell mask pre/post, and the same cells from {@link ArmorCellLane}'s shadow (the
     * last server-written per-cell truth, via the {@code plugin.hullLane} foe instance — the
     * replica victims this instrument measures; the own-ship instance is OwnDetLane-owned and
     * deliberately out of scope). {@code shadowAge} = snapshot ticks since the entity's last
     * applied cell section (huge = seeded-default shadow, the join tool discounts it). Empty
     * string when the stash carried no cell ({@code cellX<0}) or any read fails.
     */
    private String cellFields(CombatEntityAPI target, Stash st) {
        if (st.cellX < 0 || !(target instanceof ShipAPI)) {
            return "";
        }
        StringBuilder sb = new StringBuilder(128);
        try {
            ShipAPI ship = (ShipAPI) target;
            ArmorGridAPI grid = ship.getArmorGrid();
            float[][] cells = grid != null ? grid.getGrid() : null;
            if (cells == null || cells.length == 0 || cells[0] == null
                    || st.cellX >= cells.length || st.cellY >= cells[0].length) {
                return "";
            }
            int h = cells[0].length;
            int ord = st.cellX * h + st.cellY;
            sb.append(" cell=").append(ord)
                    .append(" cellPre=").append(st.cellPre)
                    .append(" cellPost=").append(cells[st.cellX][st.cellY])
                    .append(" maskPre=").append(st.maskPre)
                    .append(" maskPost=").append(maskSum(cells, st.cellX, st.cellY));
            if (ship != ownShip) {
                long entityId = plugin.wireIdForLocalShip(ship);
                if (entityId >= 0) {
                    FoeHullReconcileLane lane = plugin.hullLane;
                    float cs = lane.cellShadow(entityId, ord);
                    if (!Float.isNaN(cs)) {
                        sb.append(" cellShadow=").append(cs)
                                .append(" maskShadow=").append(lane.cellShadowSum(entityId,
                                        maskOrdinals(st.cellX, st.cellY, cells.length, h)))
                                .append(" shadowAge=")
                                .append(snapTick - lane.cellShadowSectionTick(entityId));
                    }
                }
            }
        } catch (Throwable ignore) {
            return ""; // cell terms are best-effort; the row still records the split
        }
        return sb.toString();
    }

    /**
     * D-OWNARMOR (b) instrument. The victim's armor POOL at hit
     * time — distinct from the {@code armor=} field above (that is damage-dealt-to-armor). Lets the
     * next flight prove each client's armor matched the server's per hit: {@code armorPost}/{@code
     * armorMax} pair the server twin's field names exactly; {@code armorPre} is a client-only bonus
     * from the stash captured pre-application. Rounded ints, brace-free; omits any field it cannot
     * read. Denominator law = ArmorReconcileLane's {@code cellCount × maxInCell}.
     */
    private static String armorPoolSuffix(CombatEntityAPI target, Stash st) {
        StringBuilder sb = new StringBuilder();
        float pre = (st != null) ? st.armorTotal : Float.NaN;
        if (Float.isFinite(pre)) {
            sb.append(" armorPre=").append(Math.round(pre));
        }
        float post = armorTotal(target);
        if (Float.isFinite(post)) {
            sb.append(" armorPost=").append(Math.round(post));
        }
        if (target instanceof ShipAPI) {
            try {
                ArmorGridAPI grid = ((ShipAPI) target).getArmorGrid();
                float[][] cells = grid != null ? grid.getGrid() : null;
                if (cells != null && cells.length > 0 && cells[0].length > 0) {
                    sb.append(" armorMax=").append(
                            Math.round((double) cells.length * cells[0].length * grid.getMaxArmorInCell()));
                }
            } catch (Throwable ignore) {
                // armorMax read is best-effort
            }
        }
        return sb.toString();
    }

    /**
     * Lane-2 source attribution: own | presented (tag map, shooter
     * entityId) | replica (untagged replica-ship source — forced-fire beams land here) |
     * other. The report seam's source param was UNUSED before this witness.
     *
     * <p>Measured caveat: for projectile damage the engine passes the
     * SHIP, not the projectile, as the report-seam source — the presented-tag branch
     * below never engages for projectiles (222518: every real weapon apply was
     * {@code entity-*}-sourced). Stream separation rides victim + topology instead
     * (own vs replica suffices); the branch stays for the proj-sourced echo class.
     * <p>package-private — PRCP's one-shot
     * {@code local-kill-premature} row reuses it (with the lane enabled the preceding emitApply
     * already classified that source, so the census registration below is a no-op there).
     */
    String classifySource(Object source) {
        try {
            if (source instanceof DamagingProjectileAPI) {
                DamagingProjectileAPI p = (DamagingProjectileAPI) source;
                Shot tag = presentTag(p);
                if (tag != null) {
                    return "presented:" + tag.shooterEntityId + ":m" + tag.mount;
                }
                return "proj-" + classifyEntity(p.getSource());
            }
            if (source instanceof BeamAPI) {
                return "beam-" + classifyEntity(((BeamAPI) source).getSource());
            }
            if (source instanceof CombatEntityAPI) {
                return "entity-" + classifyEntity((CombatEntityAPI) source);
            }
        } catch (Throwable ignore) {
            // attribution is best-effort; the line still records the split
        }
        return source == null ? "null" : source.getClass().getSimpleName();
    }

    /**
     * Identity-class token: own | replica@hex (replay-driven set) | ex-replica:&lt;id&gt;@hex (was
     * bound once — the split) | hulk-piece@hex (a splitShip clone — the
     * seam, {@link #isPiece}) | own-wing@hex / own-module@hex (the own ship's local kin) |
     * class@hex for the rest. A {@code Ship@hex} token is therefore a STRANGER: an engine ship
     * the wire never bound and the own ship never launched — registered in the census here.
     */
    private String classifyEntity(CombatEntityAPI e) {
        if (e == null) {
            return "none";
        }
        if (e == ownShip) {
            return "own";
        }
        String hex = Integer.toHexString(System.identityHashCode(e));
        if (e instanceof ShipAPI) {
            ShipAPI s = (ShipAPI) e;
            if (replicaShips != null && replicaShips.contains(s)) {
                return "replica@" + hex;
            }
            Long id = everReplica.get(s);
            if (id != null) {
                return "ex-replica:" + id + "@" + hex;
            }
            if (isPiece(s)) {
                if (!piecesSeen.containsKey(s)) {
                    piecesSeen.put(s, Boolean.TRUE);
                    pieceIdsSeen++;
                }
                return "hulk-piece@" + hex;
            }
            String kin = ownKin(s);
            if (kin != null) {
                return kin + "@" + hex;
            }
            if (isSyntheticPod(s)) {
                if (!syntheticPodsSeen.containsKey(s)) {
                    syntheticPodsSeen.put(s, Boolean.TRUE);
                    syntheticPodIdsSeen++;
                }
                return "synthetic-pod@" + hex;
            }
            noteStranger(s, hex);
        }
        return e.getClass().getSimpleName() + "@" + hex;
    }

    /** own-wing (a wing the own ship launched) | own-module (a module of the own station) | null. */
    private String ownKin(ShipAPI s) {
        try {
            if (ownShip != null) {
                com.fs.starfarer.api.combat.FighterWingAPI w = s.getWing();
                if (w != null && w.getSourceShip() == ownShip) {
                    return "own-wing";
                }
                if (s.getParentStation() == ownShip) {
                    return "own-module";
                }
            }
        } catch (Throwable ignore) {
            // kin read is best-effort; an unreadable body classifies as a stranger (visible)
        }
        return null;
    }

    /**
     * Register a STRANGER at ANY classify seam and print its first-sight row right here.
     * the residual (3 ids/window, rows=0 hull=0) was registered only through the
     * flood fold ({@code entity-Ship@hex} pair keys) — a seam that never reached the dsig row, so
     * no {@code hull=} ever named it. {@code ship=hex} joins this row to the flood pair / dsig row.
     */
    private void noteStranger(ShipAPI s, String hex) {
        if (!strangersSeen.containsKey(s)) {
            strangersSeen.put(s, new double[] { snapTick, 0, 0 });
            strangerIdsSeen++;
            if (strangerIdsSeen <= STRANGER_LINE_MAX) {
                PilotWatchLog.info("predict dmg-stranger #" + strangerIdsSeen + ": tick=" + snapTick
                        + " frame=" + frame + " ship=" + hex + strangerFields(s)
                        + " seam=" + seamTag + seamCtx + strangerIdentity(s)
                        + (strangerIdsSeen == STRANGER_LINE_MAX ? " [line cap reached]" : ""));
            }
        }
    }

    /**
     * The mint-naming fields for the first-sight row: variant id,
     * engine id/name, fleet-member / in-play / pod / drone / station bits, the engine player-ship
     * identity test (the {@code CombatEngine} fake "Command Shuttle" hypothesis), collision
     * class, tags (an {@code fx_drone} tag = {@code createFXDrone}) and location. Built progressively
     * inside ONE guard so a throwing getter keeps every field read before it.
     */
    private String strangerIdentity(ShipAPI s) {
        StringBuilder b = new StringBuilder(160);
        try {
            com.fs.starfarer.api.combat.ShipVariantAPI v = s.getVariant();
            b.append(" variant=").append(v != null ? v.getHullVariantId() : "-");
            b.append(" id=").append(s.getId()).append(" name=").append(s.getName());
            b.append(" fleetMember=").append(s.getFleetMember() != null);
            b.append(" inPlay=").append(engine != null ? String.valueOf(engine.isEntityInPlay(s)) : "?");
            b.append(" playerShip=").append(engine != null ? String.valueOf(s == engine.getPlayerShip()) : "?");
            b.append(" pod=").append(s.isShuttlePod()).append(" drone=").append(s.isDrone())
                    .append(" station=").append(s.isStation())
                    .append(" parent=").append(s.getParentStation() != null);
            b.append(" coll=").append(s.getCollisionClass()).append(" layer=").append(s.getLayer());
            java.util.Set<String> tags = s.getTags();
            String tagStr = tags == null ? "-" : tags.toString();
            b.append(" tags=").append(tagStr.length() > 120 ? tagStr.substring(0, 120) + "..." : tagStr);
            org.lwjgl.util.vector.Vector2f loc = s.getLocation();
            if (loc != null) {
                b.append(" loc=").append(Math.round(loc.x)).append(',').append(Math.round(loc.y));
            }
        } catch (Throwable t) {
            b.append(" identity-threw=").append(t.getClass().getSimpleName());
        }
        return b.toString();
    }

    /**
     * A hulk PIECE. {@code Ship.splitShip} CLONES the dying ship
     * — owner 100, hitpoints 0, {@code isPiece = true}, hull
     * spec cloned WITH the parent's hullId — and the collision solver
     * applies KINETIC contact damage with the OTHER body as {@code source}. So a piece is a
     * dead, never-bound shooter carrying a replica hullId: 100 % of the residual STRANGER
     * class (166/319 hull, micro-hits, first sight = the skip-wave death clusters). {@code
     * everReplica} cannot know a clone; the api predicate is the one seam left. A throw reads
     * false — the body then classifies as a STRANGER, i.e. stays visible.
     */
    /**
     * The engine's null-ship {@code spawnProjectile} substitute — a bare
     * {@code shuttlepod_xfer} body ({@code isShuttlePod} is exactly that variant id)
     * with no fleet member and never in play. The engine's REAL pods (the
     * "Command Shuttle" fake player ship = {@code ownShip} on an observer window; a transfer pod)
     * are in play and stay STRANGERS. A throw reads false — the body then classifies as a
     * STRANGER, i.e. stays visible.
     */
    private boolean isSyntheticPod(ShipAPI s) {
        try {
            // The engine's fake "Command Shuttle" player
            // ship (null fleet member, never in the play lists) also
            // matched on any pilot window where it is not ownShip. Every observed synthetic pod row is
            // alive=false hp=0.0; the fake player ship is alive and IS engine.getPlayerShip().
            return s.isShuttlePod() && s.getFleetMember() == null
                    && engine != null && !engine.isEntityInPlay(s)
                    && s != engine.getPlayerShip() && !s.isAlive();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isPiece(ShipAPI s) {
        try {
            return s.isPiece();
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** The wire entity id a replica body is bound to (walked once per body), -1 if unresolved. */
    private long entityIdOf(ShipAPI s) {
        try {
            if (plugin != null && plugin.remoteEntities != null) {
                for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
                    if (re != null && re.ship == s) {
                        return re.entityId;
                    }
                }
            }
        } catch (Throwable ignore) {
            // a concurrent edit of the map leaves the id unresolved; the body still reads ex-replica
        }
        return -1L;
    }

    /** STRANGER fields — what the recorded bytes lacked (no hullId for the four
     *  long-lived unbound ids): the hull names the mint class. */
    private static String strangerFields(ShipAPI s) {
        try {
            com.fs.starfarer.api.combat.FighterWingAPI w = s.getWing();
            return " STRANGER hull=" + (s.getHullSpec() != null ? s.getHullSpec().getHullId() : "?")
                    + " fighter=" + s.isFighter()
                    + " wing=" + (w != null ? w.getWingId() : "-")
                    + " owner=" + s.getOwner()
                    + " render=" + !s.isDoNotRender()
                    + " alive=" + s.isAlive()
                    + " piece=" + s.isPiece()
                    + " hp=" + s.getHitpoints();
        } catch (Throwable t) {
            return " STRANGER fields-threw=" + t.getClass().getSimpleName();
        }
    }

    /**
     * Teardown census: every ALIVE engine ship that is neither own, replica, ex-replica,
     * hulk piece nor own kin, with its fields (capped rows), then ONE summary row the Stage B grade reads
     * ({@code run-stage-b.ps1} B-STRANGER0: {@code seen=0 alive=0} in BOTH sidelogs = PASS).
     * The alive walk is teardown-only (one engine list read per window lifetime).
     */
    private void logStrangerCensus(String where) {
        int alive = 0;
        int walked = 0;
        try {
            List<ShipAPI> ships = engine != null ? engine.getShips() : null;
            for (int i = 0; ships != null && i < ships.size(); i++) {
                ShipAPI s = ships.get(i);
                if (s == null) {
                    continue;
                }
                walked++;
                if (s == ownShip || !s.isAlive()
                        || (replicaShips != null && replicaShips.contains(s))
                        || everReplica.containsKey(s) || isPiece(s) || ownKin(s) != null) {
                    continue;
                }
                alive++;
                if (alive <= STRANGER_LINE_MAX) {
                    double[] rec = strangersSeen.get(s);
                    PilotWatchLog.info("predict dmg-stranger-census [" + where + "] #" + alive
                            + ": ship=" + Integer.toHexString(System.identityHashCode(s))
                            + strangerFields(s)
                            + " firstSight=" + (rec != null ? String.valueOf((long) rec[0]) : "-")
                            + " rows=" + (rec != null ? String.valueOf((long) rec[1]) : "0")
                            + (alive == STRANGER_LINE_MAX ? " [census row cap]" : ""));
                }
            }
        } catch (Throwable ignore) {
            // census is diagnostic only
        }
        long seenRows = 0;
        double seenHull = 0;
        for (double[] r : strangersSeen.values()) {
            seenRows += (long) r[1];
            seenHull += r[2];
        }
        PilotWatchLog.info("predict dmg-witness strangers [" + where + "]: seen=" + strangerIdsSeen
                + " seenRows=" + seenRows + " seenHull=" + Math.round(seenHull)
                + " alive=" + alive + " pieces=" + pieceIdsSeen + " syntheticPods=" + syntheticPodIdsSeen
                + " exReplica=" + everReplica.size()
                + " engineShips=" + walked);
    }

    /** Round boundary (MRST): dead-round shells/stashes/aggregates get no outcome, not a fake one. */
    void reset() {
        if (!enabled) {
            return;
        }
        droppedAtReset += tracked.size();
        tracked.clear();
        killerFeed.clear();
        stashes.clear();
        beamDroppedAtReset += beamAggs.size();
        beamAggs.clear();
        try {
            flushAggAll(); // R5: folded applies were REAL — print them under the round they belong to
        } catch (Throwable ignore) {
            // witness lane: diagnostic only
        }
    }

    /** Census line (300-frame cadence + teardown; silent until the lane sees anything).
     *  {@code flushOpenBeams} = teardown only: close still-open aggregates first. */
    void logMeter(String where, boolean flushOpenBeams) {
        if (!enabled) {
            return;
        }
        try {
            if (flushOpenBeams && !beamAggs.isEmpty()) {
                for (Iterator<BeamAgg> it = beamAggs.iterator(); it.hasNext();) {
                    BeamAgg a = it.next();
                    it.remove();
                    flushBeamAgg(a);
                }
            }
            if (flushOpenBeams) {
                flushAggAll(); // R5: after the beam close (a closing beam may fold into a cell)
                logStrangerCensus(where); // teardown-only, always printed (the grade row)
            }
            if (shotsOwn + presentsTagged + applies + beamTicks == 0) {
                return;
            }
            PilotWatchLog.info("predict dmg-witness summary [" + where + "]: shots_own=" + shotsOwn
                    + " shots_auto=" + shotsAutofire + " shots_auto_sel=" + shotsAutofireSelected
                    + " presents_tagged=" + presentsTagged
                    + " outs_hit=" + outsHit + " outs_miss=" + outsMiss + " inflight=" + tracked.size()
                    + " applies=" + applies + " beam_ticks=" + beamTicks + " beam_flushes=" + beamFlushes
                    + " arm_taken=" + armsTaken + " arm_missing=" + armMissing + " arm_stale=" + armStale
                    + " arm_overwrite=" + armOverwrite + " flood_folded=" + floodFolded
                    + " armless_nonzero=" + armlessNonzero + " dropped_reset=" + droppedAtReset
                    + " beam_dropped_reset=" + beamDroppedAtReset
                    + " lines=" + shotLines + "/" + outLines + "/" + applyLines
                    + " agg_lines=" + aggLines + " agg_folded=" + aggFolded
                    + " apply_kill_lines_past_cap=" + killLinesPastCap //
                    // The walker reserve's meters
                    + " dmg_apply_reserve_rows=" + reserveRows + " dmg_apply_reserve_victims=" + reserveVictims
                    + " dmg_apply_reserve_refused=" + reserveRefused
                    + " dsig_lines=" + dsigLines
                    + " strangers_seen=" + strangerIdsSeen + " ex_replica=" + everReplica.size());
            if (!floodPairs.isEmpty()) {
                StringBuilder fp = new StringBuilder("predict dmg-witness flood [").append(where).append("]:");
                for (Map.Entry<String, long[]> en : floodPairs.entrySet()) {
                    long[] rec = en.getValue();
                    fp.append(' ').append(en.getKey()).append('=').append(rec[0])
                            .append("@[").append(rec[1]).append("..").append(rec[2]).append(']');
                }
                if (floodPairOverflow > 0) {
                    fp.append(" pairOverflow=").append(floodPairOverflow);
                }
                PilotWatchLog.info(fp.toString());
            }
        } catch (Throwable ignore) {
            // census is diagnostic only
        }
    }
}
