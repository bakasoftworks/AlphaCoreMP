package cc.starsector.client;

import cc.starsector.client.net.ServerEntityPose;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import java.util.Locale;
import java.util.Map;

/**
 * The foe/replica ARMOR reconcile lane (defect (43)(d)): folds the
 * AWSD v10 authoritative whole-grid armor fraction ({@link ServerEntityPose#armorFrac()},
 * keyframe + {@code POSE_ARMOR_FRAC} delta cadence) onto the replica's local armor grid, closing
 * the round-long unbounded armor ramp the join measured (55–85% of pool). Doctrine class
 * = QTY-PRIMARY (armor wear is a continuous accumulated quantity, like
 * hull); the lane owns authoritative corrections to REPLICA armor, local sim owns
 * between-keyframe wear (the overlap law — the same split as hull).
 *
 * <p><b>Fold math</b> (the one non-copy from hull — armor has no single-scalar setter): target
 * pool {@code T = armorFrac × cellCount × maxInCell} (denominator engine-exact both sides);
 * local pool {@code S} = grid sum. A u8 quantization DEADBAND skips the fold whenever
 * the wire cannot distinguish the two ({@code round(clamp01(S/maxPool)×255) == wire u8} — bounds
 * steady-state noise to one quant cell, ~0.4% of pool). Otherwise a proportional per-cell rescale
 * {@code k = T/S} via {@link ArmorGridAPI#setArmorValue} (write door; canonical precedent
 * {@code BreachOnHitEffect}) preserves the spatial damage pattern; {@code S≈0 ∧ T>0} falls back
 * to a uniform fill {@code T/cellCount}. Per-cell clamp to {@code [0, maxInCell]}; the clamp
 * shortfall is then REDISTRIBUTED into the remaining sub-max-cell headroom (each cell gains
 * {@code headroom × shortfall/totalHeadroom}) so the fold lands ON the wire target — the
 * fold-math law: proportional rescale alone can never refill a zeroed cell
 * ({@code k·0=0}) and, once the surviving cells saturate at {@code maxInCell}, the whole rescale
 * is eaten by the per-cell clamp — a structurally unreachable residual keyframes cannot correct,
 * measured live as the specimen-26 stuck plateau (client pinned at exactly 130×q,
 * −5.5..−7% of pool, while the wire target ran above it). {@code T ≤ maxPool} keeps the
 * shortfall ≤ total headroom, so the clamp is preserved by construction (defensive clamp kept
 * for float drift). After any UPWARD fold the on-sprite armor-crack
 * decals are refreshed ({@link ShipAPI#syncWithArmorGridState()} +
 * {@link ShipAPI#syncWeaponDecalsWithArmorDamage()} — the decal law; downward folds paint
 * natively). Absolute set-toward-target, no per-step cap (hull precedent has none either).
 *
 * <p><b>D-OWNARMOR — ONE fold engine, TWO instances.</b> Generalized from the
 * foe-only {@code FoeArmorReconcileLane} (git mv; fold math byte-identical): {@link #foe()} =
 * the replica lane above (FHRL-owned, knob {@code cc.predict.foeArmorReconcile}); {@link #own()}
 * = the OWN-ship twin (OwnDetLane-owned, knob {@code cc.predict.ownArmorReconcile}) — the own
 * ship's grid is pulled to the server's whole-grid fraction exactly like a replica's. Before it
 * own armor was never reconciled (a recorded-run analysis: the own client applied 117 local hits vs the
 * server's 115, own armor depleted faster, and own-window damage popups read +3–4 % high
 * SYSTEMATICALLY, 46-vs-2, while the per-frame own-hull mirror hid it from hull). Same doctrine
 * split (the overlap law): the lane owns authoritative corrections, local sim owns
 * between-keyframe wear; the u8 deadband + proportional rescale keep the LOCAL spatial damage
 * pattern (the wire carries one scalar per ship — per-cell carriage is a measured wire-budget
 * ask, not this lane). The label ({@code foe}|{@code own}) keys every log line and
 * meter name, so the foe emitters stay byte-identical to the old foe-only lane's lines.
 *
 * <p>Knob {@code cc.predict.foeArmorReconcile} (foe) / {@code cc.predict.ownArmorReconcile}
 * (own) = {@code off|meter|apply}, default {@code apply}
 * ({@code meter} counts would-apply folds + divergence meters, zero writes — the debug
 * convention for suspected armor desync; invalid values THROW at class load). NEW-LANE LAW
 * collaborator owned by {@link FoeHullReconcileLane} (foe) / {@link OwnDetLane} (own) via the
 * 4-relay trick (field init /
 * maintain / match-reset / meter flush) so {@code PredictReconcileCombatPlugin} stays ZERO-byte
 * (the knob statics live HERE, never in PRCP). No ledger, no stale-suspension
 * machinery (hull writes every frame; armor folds only on fresh armor-bearing poses); the
 * per-entity maps are the bounded OBSERVABILITY attribution (never read by the fold
 * math) and — the bounded
 * {@code biteState} feeding {@link ArmorFoldLaw}: the old one-cell deadband is now a 2-cell
 * dead-band (the u8 straddle wrote the cell center back, the {@link OwnCrPinLaw} class) and an
 * UPWARD fold is held while a local bite is inside {@link ArmorFoldLaw#BITE_HOLD_SEC} (an
 * RTT-old snapshot healed fresh local hits = armor flicker); downward folds always pass. Both
 * round-scoped. Whole-body fail-soft; render-thread only.
 */
final class ArmorReconcileLane {

    /** Resolved {@code cc.predict.foeArmorReconcile} mode (off|meter|apply; THROWs on other at
     *  class load — the knob statics live HERE, never in PRCP). */
    static final String FOE_MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.foeArmorReconcile",
            System.getProperty("cc.predict.foeArmorReconcile", "apply"));
    /** Resolved {@code cc.predict.ownArmorReconcile} mode (same vocabulary +
     *  default; read by the PRCP posture print). */
    static final String OWN_MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.ownArmorReconcile",
            System.getProperty("cc.predict.ownArmorReconcile", "apply"));

    /** This instance's resolved mode + label ({@code foe}|{@code own}); the label keys every
     *  log line and meter name — {@code foe} reproduces the foe-only lane's emitters byte-for-byte. */
    private final String mode;
    private final String label;
    private final boolean meter;
    private final boolean apply;

    /** The per-cell sibling — once it carries per-cell truth for an entity
     *  (APPLY mode + engaged), this whole-grid fold disengages for that entity (per-cell
     *  subsumes the aggregate; the deadband fold would fight per-cell corrections). Bound
     *  by the owning lane (FHRL/OwnDetLane) at construction; null = pre-v11 behavior. */
    private ArmorCellLane cellLane;
    private long skipCellLive;

    void bindCellLane(ArmorCellLane lane) {
        this.cellLane = lane;
    }

    private ArmorReconcileLane(String mode, String label) {
        this.mode = mode;
        this.label = label;
        this.meter = !"off".equals(mode);
        this.apply = "apply".equals(mode);
    }

    /** The foe/replica instance (knob {@code cc.predict.foeArmorReconcile}; FHRL-owned). */
    static ArmorReconcileLane foe() {
        return new ArmorReconcileLane(FOE_MODE, "foe");
    }

    /** The own-ship instance (knob {@code cc.predict.ownArmorReconcile};
     *  OwnDetLane-owned, relayed from the PRCP own block right after the own-hull mirror). */
    static ArmorReconcileLane own() {
        return new ArmorReconcileLane(OWN_MODE, "own");
    }

    /** Uniform-fill threshold: below this local pool a proportional rescale has no pattern to
     *  preserve (and {@code k = T/S} would explode) — fill evenly instead. */
    private static final float UNIFORM_FILL_POOL_EPS = 0.01f;

    /** Redistribution threshold: clamp shortfalls (and headrooms) at or below this are float
     *  noise, not a bitten clamp — skip the redistribution pass for them. */
    private static final float REDISTRIB_EPS = 0.01f;

    // Cumulative counters (persist across rounds, hull precedent; the meter flush + the
    // match-reset totals line read these).
    private long applies;
    private long skipQuant;
    private long skipNan;
    private long uniformFills;
    /** Fresh ALIVE poses skipped for {@code !hasArmor()} alone — the
     *  formerly counter-less gate the blind-spin class hid behind. Expected ≈0 after
     *  the armor-tier carriage fix (residual = identity-path published frames, which bypass
     *  synthesis and stay armor-less). */
    private long skipNoArmor;
    private float absDeltaMax;
    private double absDeltaSum;
    /** Post-redistribution |written − target| — was the pre-fix clamp shortfall (pinned at
     *  1750/3000 on one recorded run); with the redistribution it must collapse to ≈0, and THAT
     *  collapse is a graded fix signal (semantics change recorded). */
    private float clampResidualMax;
    /** Observability: folds where the clamp bit and the shortfall was redistributed
     *  into sub-max headroom, + the largest single-fold shortfall so moved. Zero across a
     *  specimen-26-shaped run = the fix never engaged. */
    private long redistribs;
    private float redistribMax;

    /** Bounded per-entity attribution, ROUND-scoped (entity ids do not compare
     *  across rounds — cleared at match-reset after its line): id → {seen, noArmorSkips},
     *  where seen = armor-bearing fresh alive poses that reached the fold math. Which ids
     *  the lane MEASURED vs which only ever skipped at the no-armor gate is exactly the
     *  attribution gap (applies were an aggregate; per-ship engagement UNKNOWN). */
    private static final int ATTRIB_MAX_IDS = 512;
    private static final int ATTRIB_LIST_CAP = 64;
    private final java.util.HashMap<Long, long[]> attrib = new java.util.HashMap<>();
    private long attribOverflow;

    /** {@link ArmorFoldLaw} bite source, ROUND-scoped, bounded like {@link #attrib}:
     *  id → {lastLocalSum (post-fold grid sum at the previous evaluation, NaN = none yet),
     *  biteAtSec (SIM seconds of the last detected local bite, 0 = never)}. Armor never
     *  regenerates in combat, so a drop of the local grid sum between two of this lane's own
     *  evaluations IS a local bite. An id refused by the bound gets no hold (= the pre-law
     *  fold) and counts into {@link #biteStateOverflow}.
     *  WHY = a stale upward heal of a fresh local bite. */
    private final java.util.HashMap<Long, double[]> biteState = new java.util.HashMap<>();
    private long biteStateOverflow;
    /** Local bites detected (grid-sum drops &gt; one cell of one slot).
     *  WHY = stale upward heals of fresh local bites. */
    private long bites;
    /** Upward folds held because a local bite is inside {@link ArmorFoldLaw#BITE_HOLD_SEC}.
     *  WHY = stale upward heals of fresh local bites. */
    private long skipBiteHold;
    /** Throws caught by the fold's fail-soft (formerly a swallowed catch — code law: every
     *  catch counts). */
    private long foldThrows;

    /**
     * Fold the pose's authoritative armor fraction onto the ship's grid — called by
     * {@link FoeHullReconcileLane#maintain} (foe: inside its fail-soft try, once per frame per
     * replica) / {@link OwnDetLane#maintainOwnArmor} (own: once per frame for the own ship)
     * ({@code ship}/{@code pose} already null-checked and alive there). Folds only on a FRESH
     * armor-bearing pose: {@code freshSnapshot} keeps every skipped wire tick from re-counting,
     * {@code hasArmor()} sign-gates the AWSS lane's sentinel (armor {@code 0} is REAL — no
     * min-1 on this family).
     */
    void maintain(ShipAPI ship, ServerEntityPose pose, boolean freshSnapshot) {
        if (!meter) {
            return; // off: fully inert
        }
        if (cellLane != null && cellLane.cellLive(pose.entityId())) {
            skipCellLive++; // v11: the per-cell lane owns this entity now (apply + engaged)
            return;
        }
        if (!freshSnapshot || !ship.isAlive()) {
            return; // stale ticks never re-count; lanes disengage on death (correct)
        }
        if (!pose.hasArmor()) {
            skipNoArmor++; // The formerly silent gate, now counted + attributed
            attribute(pose.entityId(), 1);
            return;
        }
        attribute(pose.entityId(), 0);
        try {
            ArmorGridAPI grid = ship.getArmorGrid();
            float[][] cells = grid != null ? grid.getGrid() : null;
            float maxInCell = grid != null ? grid.getMaxArmorInCell() : 0f;
            if (cells == null || cells.length == 0 || cells[0].length == 0 || maxInCell <= 0f) {
                skipNan++;
                return;
            }
            int cellCount = cells.length * cells[0].length;
            float maxPool = cellCount * maxInCell;
            float localSum = 0f;
            for (float[] col : cells) {
                for (float v : col) {
                    localSum += v;
                }
            }
            if (Float.isNaN(localSum) || Float.isInfinite(localSum)) {
                skipNan++;
                return;
            }
            // The fold verdict is
            // ArmorFoldLaw (2-cell dead-band + local-bite hold) instead of the old 1-cell
            // `localU8 == wireU8` skip. The compare-the-wire-u8 law is kept (pose.armorFrac()
            // is u8/255f exactly, round recovers it).
            int localU8 = Math.round(clamp01(localSum / maxPool) * 255f);
            int wireU8 = Math.round(pose.armorFrac() * 255f);
            float target = pose.armorFrac() * maxPool;
            // Gap meter BEFORE any skip (never conceal: a held/banded divergence stays visible
            // in abs_delta_max; abs_delta_mean stays the mean over applies).
            float absDelta = Math.abs(localSum - target);
            if (absDelta > absDeltaMax) {
                absDeltaMax = absDelta;
            }
            double[] bs = biteRow(pose.entityId());
            double nowSec = simNowSec();
            if (bs != null) {
                float lastSum = (float) bs[0];
                // one cell of one armor slot = ArmorCellLane's per-cell eps
                float eps = maxInCell * ArmorCellLane.CELL_EPS_FRAC;
                if (lastSum == lastSum && localSum < lastSum - eps && nowSec == nowSec) {
                    bites++;
                    bs[1] = nowSec;
                }
                bs[0] = localSum; // overwritten with the post-fold sum below if the fold writes
            }
            int verdict = ArmorFoldLaw.action(localU8, wireU8,
                    bs != null && ArmorFoldLaw.biteRecent(bs[1], nowSec));
            if (verdict == ArmorFoldLaw.FOLD_SKIP_BAND) {
                skipQuant++; // now the 2-cell dead-band counter (token name kept for greps)
                return;
            }
            if (verdict == ArmorFoldLaw.FOLD_SKIP_BITE_HOLD) {
                skipBiteHold++; // the snapshot predates the local hit — prediction stands
                return;
            }
            absDeltaSum += absDelta;
            applies++; // meter mode = would-apply; the flush line's mode= disambiguates
            if (!apply) {
                return;
            }
            float written = 0f;
            if (localSum <= UNIFORM_FILL_POOL_EPS && target > 0f) {
                uniformFills++;
                float fill = clamp(target / cellCount, 0f, maxInCell);
                for (int x = 0; x < cells.length; x++) {
                    for (int y = 0; y < cells[x].length; y++) {
                        grid.setArmorValue(x, y, fill);
                        written += fill;
                    }
                }
            } else {
                float k = target / localSum;
                // Clamped rescale into a scratch first (never trust setArmorValue to
                // write-through the array we hold), then the redistribution:
                // move the clamp shortfall into the remaining sub-max headroom so the
                // fold lands ON the wire target — this is the only path that refills a
                // zeroed cell (k·0=0) once its neighbors saturate (the specimen-26 stuck plateau).
                float[][] out = new float[cells.length][];
                float preSum = 0f;
                float headroom = 0f;
                for (int x = 0; x < cells.length; x++) {
                    out[x] = new float[cells[x].length];
                    for (int y = 0; y < cells[x].length; y++) {
                        float v = clamp(cells[x][y] * k, 0f, maxInCell);
                        out[x][y] = v;
                        preSum += v;
                        headroom += maxInCell - v;
                    }
                }
                float shortfall = target - preSum;
                if (shortfall > REDISTRIB_EPS && headroom > REDISTRIB_EPS) {
                    redistribs++;
                    if (shortfall > redistribMax) {
                        redistribMax = shortfall;
                    }
                    float perHeadroom = shortfall / headroom;
                    for (int x = 0; x < out.length; x++) {
                        for (int y = 0; y < out[x].length; y++) {
                            out[x][y] = clamp(out[x][y]
                                    + (maxInCell - out[x][y]) * perHeadroom, 0f, maxInCell);
                        }
                    }
                }
                for (int x = 0; x < out.length; x++) {
                    for (int y = 0; y < out[x].length; y++) {
                        grid.setArmorValue(x, y, out[x][y]);
                        written += out[x][y];
                    }
                }
            }
            if (bs != null) {
                // The post-fold sum, so a fold that moved
                // the grid never reads as a local bite at the next evaluation.
                bs[0] = written;
            }
            float residual = Math.abs(written - target);
            if (residual > clampResidualMax) {
                clampResidualMax = residual;
            }
            if (target > localSum) {
                // Upward fold: refresh the on-sprite armor-crack decals or they go stale
                // (the decal law — replica armor presents ONLY via decals; downward folds
                // paint natively as new cracks).
                ship.syncWithArmorGridState();
                ship.syncWeaponDecalsWithArmorDamage();
            }
        } catch (Throwable ignore) {
            // presentation-side lane; never break the watch — counted + exported
            // (the code law "every catch counts").
            foldThrows++;
        }
    }

    /** The {@link #biteState} row for an id, created on first sight (lastLocalSum NaN, never
     *  bitten); null when the bound refuses it (counted).
     *  WHY = stale upward heals of fresh local bites. */
    private double[] biteRow(long entityId) {
        double[] row = biteState.get(entityId);
        if (row == null) {
            if (biteState.size() >= ATTRIB_MAX_IDS) {
                biteStateOverflow++;
                return null;
            }
            row = new double[] { Double.NaN, 0d };
            biteState.put(entityId, row);
        }
        return row;
    }

    /** The SIM-seconds clock for the bite hold: the engine's total elapsed time EXCLUDING pauses
     *  ({@code CombatEngineAPI.getTotalElapsedTime(false)}, the clock DwellerCensusLane
     *  already reads) — the same paused-frames-do-not-advance law as ArmorCellLane's
     *  {@code simNowSec}. The caller's {@code hullLocalSimSec} is not passed to this lane and
     *  the relays (FHRL/OwnDetLane) are out of this lane's scope. NaN when no engine (⇒ no
     *  hold, no bite stamp). WHY = stale upward heals of fresh local bites. */
    private static double simNowSec() {
        com.fs.starfarer.api.combat.CombatEngineAPI engine =
                com.fs.starfarer.api.Global.getCombatEngine();
        return engine != null ? engine.getTotalElapsedTime(false) : Double.NaN;
    }

    /** Round boundary: the cumulative totals line (counters persist across rounds — hull
     *  precedent), then the round's attribution line + its map cleared (entity ids do not
     *  compare across rounds). */
    void resetForMatchReset() {
        if (!meter) {
            return;
        }
        PilotWatchLog.info("predict " + label + "-armor-lane match-reset totals: applies=" + applies
                + " skipQuant=" + skipQuant + " skipNan=" + skipNan
                + " uniformFills=" + uniformFills + " skipNoArmor=" + skipNoArmor
                + " redistribs=" + redistribs
                + " skipBiteHold=" + skipBiteHold + " bites=" + bites
                + " foldThrows=" + foldThrows);
        logAttribution("match-reset");
        attrib.clear();
        attribOverflow = 0;
        // Bite state is round-scoped (ids do not compare
        // across rounds; the engine clock restarts).
        biteState.clear();
    }

    /**
     * The armor counters, one greppable line per meter flush (the counter-line
     * discipline), label-keyed ({@code <label>-armor-meter} / {@code predict_<label>_armor_*},
     * label = foe|own). Silent when this instance's knob is {@code off}; flushes on the lane's
     * OWN knob — {@link FoeHullReconcileLane#logFoeHullReconcileMeter} / {@link OwnDetLane#logMeter}
     * relay here before their own {@code !meter} early-returns.
     */
    void logMeter(String where) {
        if (!meter) {
            return;
        }
        PilotWatchLog.info("predict v3 " + label + "-armor-meter [" + where + "]:"
                + " mode=" + mode
                + " predict_" + label + "_armor_applies=" + applies
                + " predict_" + label + "_armor_skip_quant=" + skipQuant
                + " predict_" + label + "_armor_skip_nan=" + skipNan
                + " predict_" + label + "_armor_uniform_fills=" + uniformFills
                + " predict_" + label + "_armor_skip_noarmor=" + skipNoArmor
                + " predict_" + label + "_armor_skip_celllive=" + skipCellLive
                + " predict_" + label + "_armor_abs_delta_max=" + fmt1(absDeltaMax)
                + " predict_" + label + "_armor_abs_delta_mean="
                + fmt1(applies > 0 ? absDeltaSum / applies : 0d)
                + " predict_" + label + "_armor_clamp_residual_max=" + fmt1(clampResidualMax)
                + " predict_" + label + "_armor_redistribs=" + redistribs
                + " predict_" + label + "_armor_redistrib_max=" + fmt1(redistribMax)
                // fold-law meters:
                + " predict_" + label + "_armor_skip_bite_hold=" + skipBiteHold
                + " predict_" + label + "_armor_bites=" + bites
                + " predict_" + label + "_armor_bite_state_overflow=" + biteStateOverflow
                + " predict_" + label + "_armor_fold_throws=" + foldThrows);
        logAttribution(where);
    }

    /** One id's attribution row upsert — slot 0 = seen (measured), 1 = no-armor
     *  skip; the map is bounded, refused increments overflow-count instead of growing it. */
    private void attribute(long entityId, int slot) {
        long[] row = attrib.get(entityId);
        if (row == null) {
            if (attrib.size() >= ATTRIB_MAX_IDS) {
                attribOverflow++;
                return;
            }
            row = new long[2];
            attrib.put(entityId, row);
        }
        row[slot]++;
    }

    /**
     * The round's per-entity attribution, one bounded single-line row —
     * {@code noarmor_only} = ids the lane NEVER measured (only no-armor skips; the
     * blind class, expected EMPTY post-fix), {@code mixed} = ids with both folds and
     * no-armor skips (a partially-engaging carriage — diagnostic if the fix half-works).
     * Lists are skip-count-descending, capped at {@value #ATTRIB_LIST_CAP} entries; the
     * counts before them are uncapped truth.
     */
    private void logAttribution(String where) {
        int seenIds = 0;
        java.util.ArrayList<Map.Entry<Long, long[]>> noArmorOnly = new java.util.ArrayList<>();
        java.util.ArrayList<Map.Entry<Long, long[]>> mixed = new java.util.ArrayList<>();
        for (Map.Entry<Long, long[]> e : attrib.entrySet()) {
            long seen = e.getValue()[0];
            long skips = e.getValue()[1];
            if (seen > 0) {
                seenIds++;
                if (skips > 0) {
                    mixed.add(e);
                }
            } else if (skips > 0) {
                noArmorOnly.add(e);
            }
        }
        java.util.Comparator<Map.Entry<Long, long[]>> bySkipsDesc =
                (a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]);
        noArmorOnly.sort(bySkipsDesc);
        mixed.sort(bySkipsDesc);
        PilotWatchLog.info("predict v3 " + label + "-armor-attrib [" + where + "]:"
                + " ids=" + attrib.size()
                + " seen_ids=" + seenIds
                + " noarmor_only_ids=" + noArmorOnly.size()
                + " mixed_ids=" + mixed.size()
                + " attrib_overflow=" + attribOverflow
                + " noarmor_only=" + attribList(noArmorOnly, false)
                + " mixed=" + attribList(mixed, true));
    }

    /** Capped {@code id:skips} (or {@code id:seen/skips}) comma list, {@code -} when empty. */
    private static String attribList(java.util.List<Map.Entry<Long, long[]>> rows,
            boolean withSeen) {
        if (rows.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(rows.size(), ATTRIB_LIST_CAP);
        for (int i = 0; i < n; i++) {
            Map.Entry<Long, long[]> e = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(e.getKey().longValue()).append(':');
            if (withSeen) {
                sb.append(e.getValue()[0]).append('/');
            }
            sb.append(e.getValue()[1]);
        }
        if (rows.size() > n) {
            sb.append(",+").append(rows.size() - n);
        }
        return sb.toString();
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** One-decimal format, ROOT locale (grep-stable) — the lane's private fmt1 twin (the
     *  FoeInterpLane private-formatter pattern). */
    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
