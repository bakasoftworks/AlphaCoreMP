package cc.starsector.client;

import cc.starsector.client.net.ServerSnapshotFrame;
import com.fs.starfarer.api.combat.MissileAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The presented-missile TRACK instrument (the A/B differ's
 * client half; ClientParityWalker sampling-walker idiom). Every presented missile — foe-fire,
 * FLC own-adopt, and lane presence-adopt alike, ballistic included — registers a track at its
 * present site and logs a greppable {@code cc-mtrack} line family through {@link PilotWatchLog}
 * (auto seat-prefixed, log-only ⇒ RAW-safe by construction):
 *
 * <pre>
 *   cc-mtrack spawn trk=N src=&lt;entityId&gt; mnt=&lt;mount&gt; tick=&lt;eventSimTick&gt; via=foe|own|adopt|predict cls=guided|flare|ballistic x= y=
 *   cc-mtrack pos   trk=N tick=&lt;appliedSimTick&gt; x= y= fc=&lt;facingDeg&gt; wire=&lt;stableId|-1&gt;
 *   cc-mtrack end   trk=N tick=&lt;appliedSimTick&gt; x= y= wire=&lt;stableId|-1&gt; why=gone|reset
 * </pre>
 *
 * <p>{@code pos} samples land on FRESH applied frames only (every {@code cc.mtrack.every}-th —
 * a stale frame advances no applied tick, so re-logging it would only add join noise);
 * {@code wire} carries the {@link MissilePoseLane} twin's stable v13 id once linked, so the
 * offline differ ({@code CcwrMissileTracks --client-log}) joins client tracks to server tracks
 * EXACTLY by id (fallback: source pair + spawn window). The instrument is knob-independent of
 * the lane — {@code cc.predict.missilePose=off} still tracks (that IS the A side of the A/B).
 *
 * <p>Knob {@code cc.mtrack.every} (sysprop, then env {@code CC_MTRACK_EVERY}): 0 = OFF
 * (default; register/advance are no-ops), N&gt;0 samples every Nth fresh frame. Malformed
 * THROWS at class load (the S1 knob discipline). Sibling knob {@code cc.mtrack.predict}
 * (sysprop, then env {@code CC_MTRACK_PREDICT}; default false): {@code true} adds the
 * own-predicted arm — every LOCAL own missile birth registers
 * {@code via=predict} (src = session shipId), so the offline differ can measure own
 * free-flying missiles against their wire twins; own bodies never link ⇒ their {@code pos}
 * lines carry {@code wire=-1} and the differ's source-pair fallback claims them. Budgets: a per-round {@code pos}-line budget
 * and a lifetime all-lines budget, each LOUD once on exhaustion — spawn/end edges keep flowing
 * under the round budget (join integrity), nothing flows past the lifetime budget.
 *
 * <p>Render-thread only, fail-soft throughout (instrument; never break the watch).
 */
final class PresentedMissileTrack {

    /** Sample cadence in FRESH frames; 0 = instrument OFF. THROWS on malformed (S1 discipline). */
    static final int MTRACK_EVERY = resolveEvery();
    /** The own-predicted arm — {@code true} adds a {@code via=predict}
     *  register per LOCAL own missile birth ({@link DamageWitness} own-birth block). THROWS
     *  on malformed (S1 discipline). */
    static final boolean MTRACK_PREDICT = resolvePredict();
    /** Per-round {@code pos}-line budget (spawn/end exempt); loud once on exhaustion.
     *  8000 exhausted by tick ~4.2k in one measured round — 40000 covers a full round at
     *  {@code every=5}. */
    private static final int ROUND_POS_BUDGET = 40000;
    /** Lifetime budget across ALL {@code cc-mtrack} lines; loud once, then silence.
     *  Scaled with the round raise so a multi-reset run's later rounds still flow
     *  (~5 full rounds + edges). */
    private static final int LIFETIME_LINE_BUDGET = 250000;

    private static int resolveEvery() {
        String raw = System.getProperty("cc.mtrack.every");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("CC_MTRACK_EVERY");
        }
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            int every = Integer.parseInt(raw.trim());
            if (every < 0) {
                throw new IllegalArgumentException("cc.mtrack.every must be >= 0, got " + every);
            }
            return every;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cc.mtrack.every not an int: '" + raw + "'", e);
        }
    }

    private static boolean resolvePredict() {
        String raw = System.getProperty("cc.mtrack.predict");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("CC_MTRACK_PREDICT");
        }
        if (raw == null || raw.isBlank()) {
            return false;
        }
        raw = raw.trim();
        if (raw.equalsIgnoreCase("true")) {
            return true;
        }
        if (raw.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("cc.mtrack.predict must be true|false, got '" + raw + "'");
    }

    /** One tracked presented missile. */
    private static final class Rec {
        final int trk;
        float lastX;
        float lastY;

        Rec(int trk) {
            this.trk = trk;
        }
    }

    private final PredictReconcileCombatPlugin plugin;
    private final MissilePoseLane lane;
    private final IdentityHashMap<MissileAPI, Rec> tracked =
            new IdentityHashMap<MissileAPI, Rec>();
    private int nextTrk = 1;
    private long lastAppliedTick = -1L;
    private int freshFrames;
    // census counters (cumulative; budgets below gate emission, not counting)
    private int spawnLines;
    private int posLines;
    private int endLines;
    private int roundPosLines;
    private int totalLines;
    private boolean roundBudgetLoud;
    private boolean lifetimeBudgetLoud;

    PresentedMissileTrack(PredictReconcileCombatPlugin plugin, MissilePoseLane lane) {
        this.plugin = plugin;
        this.lane = lane;
    }

    /**
     * Register a presented missile at its present site — call BEFORE the lane's enroll so the
     * classification still reads the FACTORY AI ({@code clsOrNull} = null ⇒ computed here);
     * a lane presence-adopt registers with its already-known class (the AI is replaced by then).
     */
    void register(Object spawned, long srcId, int mount, long simTick, String via,
            String clsOrNull) {
        if (MTRACK_EVERY <= 0 || !(spawned instanceof MissileAPI)) {
            return;
        }
        try {
            MissileAPI missile = (MissileAPI) spawned;
            String cls = clsOrNull;
            if (cls == null) {
                cls = missile.isGuided() ? "guided" : missile.isFlare() ? "flare" : "ballistic";
            }
            Rec rec = new Rec(nextTrk++);
            Vector2f loc = missile.getLocation();
            if (loc != null) {
                rec.lastX = loc.x;
                rec.lastY = loc.y;
            }
            tracked.put(missile, rec);
            spawnLines++;
            emit("cc-mtrack spawn trk=" + rec.trk + " src=" + srcId + " mnt=" + mount
                    + " tick=" + simTick + " via=" + via + " cls=" + cls
                    + " spec=" + missile.getProjectileSpecId()
                    + " x=" + rec.lastX + " y=" + rec.lastY, false);
        } catch (Throwable ignore) {
            // instrument; never break the watch
        }
    }

    /**
     * The per-render-frame drive (right after the missile lane's maintain): prune ended tracks
     * (end line at the last applied tick), then on every {@code MTRACK_EVERY}-th FRESH frame
     * emit a {@code pos} sample per live track.
     */
    void advance(ServerSnapshotFrame snapshot, boolean freshSnapshot) {
        if (MTRACK_EVERY <= 0 || tracked.isEmpty() && lastAppliedTick < 0) {
            return;
        }
        try {
            if (freshSnapshot && snapshot != null) {
                lastAppliedTick = snapshot.simTick();
                freshFrames++;
            }
            boolean sample = freshSnapshot && snapshot != null
                    && freshFrames % MTRACK_EVERY == 0;
            for (Iterator<Map.Entry<MissileAPI, Rec>> it = tracked.entrySet().iterator();
                    it.hasNext();) {
                Map.Entry<MissileAPI, Rec> e = it.next();
                MissileAPI missile = e.getKey();
                Rec rec = e.getValue();
                if (plugin.engine == null || !plugin.engine.isEntityInPlay(missile)) {
                    endLines++;
                    emit("cc-mtrack end trk=" + rec.trk + " tick=" + lastAppliedTick
                            + " x=" + rec.lastX + " y=" + rec.lastY
                            + " wire=" + lane.wireIdFor(missile) + " why=gone", false);
                    it.remove();
                    continue;
                }
                Vector2f loc = missile.getLocation();
                if (loc != null) {
                    rec.lastX = loc.x;
                    rec.lastY = loc.y;
                }
                if (sample) {
                    posLines++;
                    roundPosLines++;
                    emit("cc-mtrack pos trk=" + rec.trk + " tick=" + lastAppliedTick
                            + " x=" + rec.lastX + " y=" + rec.lastY
                            // The missing facing token — presented facing
                            // per sample, so facing sync is measurable offline per track.
                            + " fc=" + missile.getFacing()
                            + " wire=" + lane.wireIdFor(missile)
                            // own-nudge cumulative-correction suffix (apply-mode
                            // own twins only, else "") — RAW stays recoverable by subtraction.
                            + lane.ownCorrTokens(missile), true);
                }
            }
        } catch (Throwable ignore) {
            // instrument; never break the watch
        }
    }

    /** Round reset: close every live track (why=reset) and re-arm the per-round pos budget. */
    void resetForMatchReset() {
        if (MTRACK_EVERY <= 0) {
            return;
        }
        try {
            for (Map.Entry<MissileAPI, Rec> e : tracked.entrySet()) {
                Rec rec = e.getValue();
                endLines++;
                emit("cc-mtrack end trk=" + rec.trk + " tick=" + lastAppliedTick
                        + " x=" + rec.lastX + " y=" + rec.lastY
                        + " wire=" + lane.wireIdFor(e.getKey()) + " why=reset", false);
            }
            tracked.clear();
            roundPosLines = 0;
            roundBudgetLoud = false;
        } catch (Throwable ignore) {
            // instrument; never break the watch
        }
    }

    /** Greppable census line (the counter-line discipline); silent at OFF. */
    void logCensus(String where) {
        if (MTRACK_EVERY <= 0) {
            return;
        }
        PilotWatchLog.info("cc-mtrack census [" + where + "]: every=" + MTRACK_EVERY
                + " live=" + tracked.size() + " spawns=" + spawnLines + " pos=" + posLines
                + " ends=" + endLines + " lines=" + totalLines
                + " roundBudget=" + (roundBudgetLoud ? "EXHAUSTED" : "ok")
                + " lifetimeBudget=" + (lifetimeBudgetLoud ? "EXHAUSTED" : "ok"));
    }

    /** Budget-gated emit: {@code posBudgeted} lines stop at the round budget; everything stops
     *  at the lifetime budget — each exhaustion is LOUD exactly once. */
    private void emit(String line, boolean posBudgeted) {
        if (totalLines >= LIFETIME_LINE_BUDGET) {
            if (!lifetimeBudgetLoud) {
                lifetimeBudgetLoud = true;
                PilotWatchLog.info("cc-mtrack budget EXHAUSTED (lifetime " + LIFETIME_LINE_BUDGET
                        + " lines): instrument silent for the rest of this JVM");
            }
            return;
        }
        if (posBudgeted && roundPosLines > ROUND_POS_BUDGET) {
            if (!roundBudgetLoud) {
                roundBudgetLoud = true;
                totalLines++;
                PilotWatchLog.info("cc-mtrack budget EXHAUSTED (round " + ROUND_POS_BUDGET
                        + " pos lines): pos suppressed till match reset (spawn/end keep flowing)");
            }
            return;
        }
        totalLines++;
        PilotWatchLog.info(line);
    }
}
