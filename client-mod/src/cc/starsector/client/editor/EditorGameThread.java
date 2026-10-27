package cc.starsector.client.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.json.JSONException;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.HullModEffect;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;

import cc.starsector.client.PilotWatchLog;
import cc.starsector.client.net.ClientVariantDefinition;

/**
 * W15 v1 editor — the game logic decides:
 * hullmod applicability answered by the GAME, on the GAME thread, exactly the way the refit
 * screen asks it. The responder's worker builds the candidate variant and posts a job; this plugin
 * (ONE per combat engine, installed lazily through {@code CombatEngineAPI.addPlugin} — the title
 * screen's engine advances plugins every frame, paused or not) runs the job inside {@code advance}:
 * {@code createFXDrone(candidate)} (a real, DETACHED Ship: no fleet member, no captain, never in the
 * engine's ship list — drill), then for EVERY
 * hullmod spec the refit's own five gates — {@code isApplicableToShip}, {@code getUnapplicableReason},
 * {@code showInRefitScreenModPickerFor}, {@code canBeAddedOrRemovedNow(ship, null, NONE)},
 * {@code getCanNotBeInstalledNowReason} — and {@code removeEntity} in the same frame.
 *
 * <p>Session 4 rebuild ("the game thread did not answer within
 * 2000 ms" under a burst of edits on a weapon-carrying candidate):
 * <ul>
 * <li><b>Key</b> — the five gates read the drone's hull, hullmods and s-mods only, never its weapons,
 * vents, capacitors or wings ({@link #key}); one answer serves every fit of the same mod set.</li>
 * <li><b>Cache</b> — every available answer is kept per key (LRU, {@value #CACHE_MAX}); a weapon or
 * vent edit never re-asks the game.</li>
 * <li><b>Coalesce</b> — ONE queued job at a time: a newer question supersedes a queued one (its waiters
 * get {@code pending}), the same question joins the queued/running job, at most one job per frame.</li>
 * <li><b>Never block</b> — a validate waits at most {@value #GRACE_MS} ms for a fresh answer, then
 * answers {@code pending}; the SPA re-validates until the answer lands (state {@link Answer#state}).</li>
 * <li><b>Instrument</b> — one sidelog row per job (duration, frames waited, queue wait, waiters,
 * superseded) and running counters for {@code api/ping} ({@link #statsJson}).</li>
 * </ul>
 * No engine ⇒ {@code unavailable} (the server stays the arbiter, the SPA says so); an unavailable answer
 * is never cached.
 */
final class EditorGameThread extends BaseEveryFrameCombatPlugin {

    /** How long a validate waits for a FRESH answer before answering {@code pending}. */
    static final long GRACE_MS = 250L;
    /** Answers kept, by key (LRU). */
    static final int CACHE_MAX = 64;

    /** One hullmod's answers from the game (all five gates), null-safe strings. */
    static final class ModGate {
        final boolean applicable;
        final String reason;
        final boolean show;
        final boolean now;
        final String nowReason;

        ModGate(boolean applicable, String reason, boolean show, boolean now, String nowReason) {
            this.applicable = applicable;
            this.reason = reason == null ? "" : reason;
            this.show = show;
            this.now = now;
            this.nowReason = nowReason == null ? "" : nowReason;
        }

        boolean clean() {
            return applicable && show && now;
        }
    }

    /** The game's whole answer for one candidate, or why there is none (yet). */
    static final class Answer {
        static final String GAME = "game";
        static final String PENDING = "pending";
        static final String UNAVAILABLE = "unavailable";

        /** {@link #GAME} (mods filled), {@link #PENDING} (asked, no answer yet) or {@link #UNAVAILABLE}. */
        final String state;
        /** hullmod id → gates; EMPTY unless {@link #GAME}. Every spec with an effect is present. */
        final Map<String, ModGate> mods;
        final String note;
        final int errors;
        final long frame;
        final String thread;
        /** Game-thread time the answer cost, ms (0 for a cached/pending/unavailable answer). */
        final long tookMs;
        /** True when served from the cache. */
        final boolean cached;

        private Answer(String state, Map<String, ModGate> mods, String note, int errors, long frame, String thread,
                long tookMs, boolean cached) {
            this.state = state;
            this.mods = mods;
            this.note = note;
            this.errors = errors;
            this.frame = frame;
            this.thread = thread;
            this.tookMs = tookMs;
            this.cached = cached;
        }

        boolean available() {
            return GAME.equals(state);
        }

        boolean pending() {
            return PENDING.equals(state);
        }

        static Answer unavailable(String why) {
            return new Answer(UNAVAILABLE, Collections.<String, ModGate>emptyMap(), why, 0, -1, "", 0L, false);
        }

        static Answer pending(String why) {
            return new Answer(PENDING, Collections.<String, ModGate>emptyMap(), why, 0, -1, "", 0L, false);
        }

        Answer asCached() {
            return new Answer(state, mods, "cached", errors, frame, thread, 0L, true);
        }
    }

    /** One question in flight: shared by every waiter asking the same key. */
    private static final class Job {
        final String key;
        final ShipVariantAPI candidate;
        final long postedNanos;
        final long postedFrame;
        final CountDownLatch done = new CountDownLatch(1);
        volatile Answer answer;
        int waiters = 1;

        Job(String key, ShipVariantAPI candidate, long postedFrame) {
            this.key = key;
            this.candidate = candidate;
            this.postedNanos = System.nanoTime();
            this.postedFrame = postedFrame;
        }

        void finish(Answer a) {
            answer = a;
            done.countDown();
        }
    }

    private static volatile EditorGameThread installed;
    private static final Object INSTALL = new Object();

    /** Guards {@link #queued}, {@link #running}, {@link #CACHE} and the counters. */
    private static final Object STATE = new Object();
    private static Job queued;
    private static Job running;
    private static final LinkedHashMap<String, Answer> CACHE = new LinkedHashMap<String, Answer>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Answer> eldest) {
            return size() > CACHE_MAX;
        }
    };
    private static long jobsRun;
    private static long jobsSuperseded;
    private static long cacheHits;
    private static long cacheMisses;
    private static long joined;
    private static long lastJobMs;
    private static long maxJobMs;
    private static long totalJobMs;

    private volatile CombatEngineAPI engine;
    private volatile long frames;
    private volatile String threadName = "";

    private EditorGameThread(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void init(CombatEngineAPI e) {
        engine = e;
    }

    /** At most ONE job per frame: a job is the whole frame hitch, never two of them. */
    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        frames++;
        threadName = Thread.currentThread().getName();
        Job j;
        synchronized (STATE) {
            j = queued;
            queued = null;
            running = j;
        }
        if (j == null) {
            return;
        }
        long t0 = System.nanoTime();
        long queuedMs = (t0 - j.postedNanos) / 1000000L;
        Answer a;
        try {
            a = askOnGameThread(j.candidate);
        } catch (RuntimeException fault) {
            PilotWatchLog.error("editor: game-thread job failed", fault);
            a = Answer.unavailable("game-thread job failed: " + fault.getClass().getSimpleName());
        }
        long tookMs = (System.nanoTime() - t0) / 1000000L;
        if (a.available()) {
            a = new Answer(Answer.GAME, a.mods, a.note, a.errors, a.frame, a.thread, tookMs, false);
        }
        int waiters;
        int cacheSize;
        synchronized (STATE) {
            if (a.available()) {
                CACHE.put(j.key, a);
            }
            running = null;
            jobsRun++;
            lastJobMs = tookMs;
            totalJobMs += tookMs;
            if (tookMs > maxJobMs) {
                maxJobMs = tookMs;
            }
            waiters = j.waiters;
            cacheSize = CACHE.size();
        }
        j.finish(a);
        PilotWatchLog.info("editor: game job " + j.key + " state=" + a.state + " took=" + tookMs + "ms framesWaited="
                + (frames - j.postedFrame) + " queuedMs=" + queuedMs + " waiters=" + waiters + " errors=" + a.errors
                + " cache=" + cacheSize + (a.available() ? "" : " note=" + a.note));
    }

    /** The plugin bound to the CURRENT engine (a new engine gets a new plugin; the old one is orphaned). */
    private static EditorGameThread forCurrentEngine() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return null;
        }
        synchronized (INSTALL) {
            EditorGameThread g = installed;
            if (g != null && g.engine == engine) {
                return g;
            }
            g = new EditorGameThread(engine);
            engine.addPlugin(g);
            installed = g;
            PilotWatchLog.info("editor: game-thread gate installed on the live combat engine from "
                    + Thread.currentThread().getName());
            return g;
        }
    }

    /**
     * The question's identity: hull + hullmods + perma-mods + s-mods, each list sorted. The five refit
     * gates read exactly that from the drone (vanilla gate scripts read the hull spec, the variant's
     * mod set and the s-mod set; none reads fitted weapons, vents, capacitors or wings), so every fit
     * of one mod set shares one answer.
     */
    static String key(ClientVariantDefinition def) {
        StringBuilder b = new StringBuilder(96);
        b.append(def.hullId());
        appendSorted(b, def.hullMods());
        appendSorted(b, def.permaMods());
        appendSorted(b, def.sMods());
        return b.toString();
    }

    private static void appendSorted(StringBuilder b, List<String> ids) {
        b.append('|');
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<String> sorted = new ArrayList<String>(ids);
        Collections.sort(sorted);
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(sorted.get(i));
        }
    }

    /**
     * Ask the game about {@code candidate} (a FRESH variant the caller will not reuse — the engine tags
     * it {@code fx_drone}) under {@code key}. Cached ⇒ immediate. Else the question is posted (joining
     * an identical queued/running job, superseding a different queued one) and the caller waits at most
     * {@link #GRACE_MS}; no answer by then ⇒ {@code pending} (the job still runs and lands in the cache).
     */
    static Answer applicability(String key, ShipVariantAPI candidate) {
        final EditorGameThread g = forCurrentEngine();
        if (g == null) {
            return Answer.unavailable("no combat engine (not at the title or in a battle)");
        }
        Job job;
        synchronized (STATE) {
            Answer hit = CACHE.get(key);
            if (hit != null) {
                cacheHits++;
                return hit.asCached();
            }
            cacheMisses++;
            if (running != null && running.key.equals(key)) {
                job = running;
                job.waiters++;
                joined++;
            } else if (queued != null && queued.key.equals(key)) {
                job = queued;
                job.waiters++;
                joined++;
            } else {
                if (queued != null) {
                    jobsSuperseded++;
                    queued.finish(Answer.pending("superseded by a newer question"));
                }
                job = new Job(key, candidate, g.frames);
                queued = job;
            }
        }
        boolean ran;
        try {
            ran = job.done.await(GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            ran = false;
        }
        if (!ran) {
            // Liveness: the title engine stops advancing when the window is idled (unfocused/minimised —
            // BaseGameState idleWhileWindowNotVisible) or replaced by a title action (mission list); the
            // queue drains on whichever plugin advances next, so the SPA keeps polling — say why meanwhile.
            boolean stalled = g.frames == job.postedFrame;
            return Answer.pending(stalled
                    ? "the game window is not advancing frames (minimised or on another screen?) — it will answer when it does"
                    : "the game is still checking this build");
        }
        Answer a = job.answer;
        return a != null ? a : Answer.unavailable("game-thread job produced no answer");
    }

    /** Running counters for {@code api/ping} (and the smoke's burst row). */
    static JSONObject statsJson() throws JSONException {
        JSONObject o = new JSONObject();
        synchronized (STATE) {
            o.put("jobs", jobsRun);
            o.put("superseded", jobsSuperseded);
            o.put("joined", joined);
            o.put("cacheHits", cacheHits);
            o.put("cacheMisses", cacheMisses);
            o.put("cacheSize", CACHE.size());
            o.put("lastJobMs", lastJobMs);
            o.put("maxJobMs", maxJobMs);
            o.put("totalJobMs", totalJobMs);
            o.put("queued", queued != null);
            o.put("running", running != null);
        }
        EditorGameThread g = installed;
        o.put("frames", g == null ? -1 : g.frames);
        o.put("graceMs", GRACE_MS);
        return o;
    }

    /** Inside advance(): the drone, five gates per hullmod, the drone removed. */
    private Answer askOnGameThread(ShipVariantAPI candidate) {
        CombatEngineAPI e = engine;
        ShipAPI drone = null;
        try {
            drone = e.createFXDrone(candidate);
            if (drone == null) {
                return Answer.unavailable("createFXDrone returned null");
            }
            Map<String, ModGate> mods = new LinkedHashMap<String, ModGate>();
            int errors = 0;
            for (HullModSpecAPI spec : Global.getSettings().getAllHullModSpecs()) {
                String id = spec.getId();
                HullModEffect eff = spec.getEffect();
                if (id == null || id.isEmpty() || eff == null) {
                    continue;
                }
                try {
                    boolean applicable = eff.isApplicableToShip(drone);
                    String reason = applicable ? null : eff.getUnapplicableReason(drone);
                    boolean show = eff.showInRefitScreenModPickerFor(drone);
                    // A design-time build is refitted AT A DOCK: the refit's spaceport rule (BaseHullMod
                    // `req_spaceport` tag + null market ⇒ "can only be installed at a colony with a spaceport")
                    // is satisfied by construction, so it never locks a mod here; every other "not now" rule
                    // is the game's word as-is.
                    boolean now = spec.hasTag("req_spaceport")
                            || eff.canBeAddedOrRemovedNow(drone, null, CoreUITradeMode.NONE);
                    String nowReason = now ? null : eff.getCanNotBeInstalledNowReason(drone, null, CoreUITradeMode.NONE);
                    mods.put(id, new ModGate(applicable, reason, show, now, nowReason));
                } catch (RuntimeException gateFault) {
                    // The game's own script refused to answer for this mod on a detached ship: report it
                    // as inapplicable WITH the fault named (fail-closed for the SPA; the server re-checks).
                    errors++;
                    mods.put(id, new ModGate(false, "game error: " + gateFault.getClass().getSimpleName(), true, true, null));
                }
            }
            return new Answer(Answer.GAME, Collections.unmodifiableMap(mods), "game", errors, frames, threadName, 0L, false);
        } catch (RuntimeException fault) {
            return Answer.unavailable("game refused the candidate: " + fault.getClass().getSimpleName());
        } finally {
            if (drone != null) {
                try {
                    e.removeEntity(drone);
                } catch (RuntimeException ignored) {
                    // a detached drone was never in the engine's lists; removal is defensive
                }
            }
        }
    }
}
