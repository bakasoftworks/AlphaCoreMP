package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The STATIC replica predicate bridge + census the
 * {@code CcWrap*Effect} weapon-effect wrappers key on. The engine mints those wrappers by NAME
 * through ScriptStore on the SAME loader chain that loads this mod's plugin (the
 * {@link ClientFireBirthSink} statics-bridge shape, statically confirmed), so these
 * statics ARE shared with every wrapper instance.
 *
 * <p>Predicate contract: {@link #isTreatedReplica} answers "is this ship a fully-treated remote
 * body?" via the bound {@link PredictReconcileCombatPlugin#remoteBodyFullyTreated} (bind at
 * {@code init()} beside the {@link ClientFireBirthSink} bind, unbind at {@code teardownActive}).
 * FAIL DIRECTION IS LOAD-BEARING: every uncertain path (no bound consumer, off-thread caller,
 * null ship) answers {@code false} → the wrapper delegates to the VANILLA body via
 * {@code super.advance()}. A false negative reproduces today's ghosting (status quo, watch-visible);
 * a false positive would suppress a real local effect on the player's own predicted ship — never
 * risk that. Serverless windows (menu/refit/missions without a server) therefore stay pure vanilla.
 *
 * <p>Census (the sweep-receipt discipline of {@link ClientOnFireAttachSweep}, runtime half):
 * per-wrapper-tag rows of {@code frames} (replica branch taken), {@code doorShut} (a gated sim
 * door reached and dropped), {@code visual} (a wrapper-minted visual ANALOG row — visuals kept by
 * inheritance do NOT count here; the watch's entity scan covers those). Combat-thread-only writes
 * (wrappers count only after the predicate passed its thread check); cumulative, never reset —
 * {@link #logCensus} emits one greppable line at teardown for the watch grader.
 */
final class CcReplicaEffectGate {

    /** The bound consumer window's plugin (bind at {@code init()}, unbind at teardown —
     *  serverless windows stay unbound → wrappers are vanilla-transparent). */
    private static volatile PredictReconcileCombatPlugin consumer;
    /** The combat/render thread captured at bind — the thread-check fail-soft's reference. */
    private static volatile Thread mainThread;

    private static long prebind;
    private static long threadMiss;

    /** Wrapper tag → {frames, doorShut, visual}. Combat-thread-only after bind (see class doc);
     *  LinkedHashMap keeps the census line's row order stable across runs. */
    private static final Map<String, long[]> census = new LinkedHashMap<String, long[]>();

    private CcReplicaEffectGate() {
    }

    /** Bind the live combat window (plugin init, combat/render thread). */
    static void bind(PredictReconcileCombatPlugin plugin, Thread thread) {
        consumer = plugin;
        mainThread = thread;
    }

    /** Drop the dying window's binding — wrappers fall back to vanilla ({@code prebind} counts). */
    static void unbind() {
        consumer = null;
        mainThread = null;
    }

    /**
     * The one predicate every wrapper branches on. {@code true} ONLY when a bound plugin, asked
     * on its own combat thread, confirms the FULL replica-body treatment — see the class doc's
     * fail-direction law for why everything else is {@code false}.
     */
    static boolean isTreatedReplica(ShipAPI ship) {
        if (ship == null) {
            return false;
        }
        PredictReconcileCombatPlugin c = consumer;
        if (c == null) {
            prebind++;
            return false;
        }
        if (Thread.currentThread() != mainThread) {
            threadMiss++;
            return false;
        }
        return c.remoteBodyFullyTreated(ship);
    }

    /**
     * The served-bomb SPAWN-script predicate:
     * {@code true} iff a SERVED match is bound (a bound consumer holding a live predict session,
     * asked on its own combat thread) AND {@code ship} is not the local own ship. Deliberately NOT
     * {@link #isTreatedReplica}: for a spawn script the vanilla fallback is the harmful direction
     * (a local bomb mint + lurch + DamagingExplosion on a body the wire already serves), and the
     * wire delivers EVERY non-own bomb — so every non-own ship takes the no-mint branch. The own
     * identity is the plugin's bound own ship, else the engine's player ship (the plugin's own
     * pre-bind fallback idiom). Null ship / unbound (serverless window, the SERVER JVM) /
     * off-thread ⇒ {@code false} ⇒ vanilla. Counts into the shared {@code prebind}/{@code threadMiss}.
     */
    static boolean servedNonOwn(ShipAPI ship) {
        if (ship == null) {
            return false;
        }
        PredictReconcileCombatPlugin c = consumer;
        if (c == null) {
            prebind++;
            return false;
        }
        if (Thread.currentThread() != mainThread) {
            threadMiss++;
            return false;
        }
        if (c.session == null) {
            return false; // bound-and-inert serverless window: pure vanilla
        }
        ShipAPI own = c.ownShip;
        if (own == null && c.engine != null) {
            own = c.engine.getPlayerShip();
        }
        return servedNonOwnVerdict(true, ship == own);
    }

    /** {@link #servedNonOwn}'s verdict, pure (selftest
     *  row): served AND not the own ship. */
    static boolean servedNonOwnVerdict(boolean servedBound, boolean isOwn) {
        return servedBound && !isOwn;
    }

    /** The bound consumer's registry id for a replica body ({@code -1}: unbound,
     *  off-thread, or not a registry replica); the presenter rows' {@code victim=remote:<id>} key. */
    static long boundRemoteEntityId(ShipAPI ship) {
        PredictReconcileCombatPlugin c = consumer;
        if (c == null || Thread.currentThread() != mainThread) {
            return -1L;
        }
        return c.remoteEntityIdOf(ship);
    }

    /** The wrapper took its replica branch this frame (proves the swap is exercised at all). */
    static void replicaFrame(String tag) {
        row(tag)[0]++;
    }

    /** A gated sim door was REACHED and dropped (the vanilla body would have minted a sim
     *  entity/listener on this exact call). The watch's zero-ghost assertion pairs this with the
     *  entity scan: doorShut > 0 proves the door was exercised, the scan proves nothing minted. */
    static void doorShut(String tag) {
        row(tag)[1]++;
    }

    /** The wrapper minted a visual ANALOG row (spawnEmpArcVisual / rift-visual loop) in place of
     *  the dropped sim call — the watch's visuals-intact half. */
    static void visualMinted(String tag) {
        row(tag)[2]++;
    }

    private static long[] row(String tag) {
        long[] r = census.get(tag);
        if (r == null) {
            r = new long[3];
            census.put(tag, r);
        }
        return r;
    }

    /** One greppable census line ({@code wsg census when=…}) — teardown-emitted; rows are
     *  cumulative across rounds (the server sink's never-reset discipline). */
    static void logCensus(String when) {
        StringBuilder sb = new StringBuilder("wsg census when=").append(when)
                .append(" prebind=").append(prebind)
                .append(" threadMiss=").append(threadMiss);
        if (census.isEmpty()) {
            sb.append(" rows=0 (no replica branch ever taken)");
        }
        for (Map.Entry<String, long[]> e : census.entrySet()) {
            long[] r = e.getValue();
            sb.append(' ').append(e.getKey()).append("=frames:").append(r[0])
                    .append(",doorShut:").append(r[1]).append(",visual:").append(r[2]);
        }
        PilotWatchLog.info(sb.toString());
    }
}
