package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import java.util.List;

/**
 * Headless REAL-ENGINE multi-round heap MEASUREMENT for the round-boundary heap drain —
 * the live verification the port still owed ("the code path is proven faithful + compiling, not yet
 * proven heap-effective on the product").
 * A measurement, not a WATCH: it drives the
 * REAL engine across many reset rounds with no socket / no client / no renderer, so no operator is needed.
 *
 * <p><b>Why this main, not the socket server.</b> NOTE (breadcrumb-corrected 2026-06-19): the
 * original rationale here — "the server path cannot drive a multi-round reset loop today,
 * {@code any_firing=false}" — is STALE. A later run recorded the real-engine
 * server serving N lethal rounds to ONE persistent socket with roster-0 reset ({@code resets=1},
 * any_firing=true), and served AI-vs-AI lethal fire is DONE-RECONCILED; the socket loop
 * ({@code PersistentDedicatedCombatServer}) already drives {@code matchResetHook.reset()} at the
 * roster-0 round boundary. This standalone main remains justified as a non-WATCH headless A/B heap
 * scaffold (no socket / no client / no renderer, operator-free), driving the product reset path directly
 * ({@link RealEngineCombatBindingFactory#resetForRedeploy()}, which runs the
 * {@code RealEngineRoundHeapDrain} when correctives are ON) round after round and measures the heap
 * — exactly the scout's {@code runFleetSoak} pattern (the {@code fleet-soak-201649} GREEN bar:
 * per-round {@code heap_after_gc_mb} flat) but exercising {@code server/src} rather than the scout.
 *
 * <p><b>The measurement is an A/B on the master switch.</b> Run it twice (two JVM launches), once
 * with {@code -Dcc.predict.correctivesOff=false} (default — drain ON) and once with {@code =true}
 * (drain OFF). Each round: advance {@code --frames-per-round} faithful inner steps (the engine's own
 * per-frame {@code updateVisibility} mints the fog-tracker entries; the per-round roster re-deploy
 * adds two fresh {@code Ship}s), then {@code resetForRedeploy()}, then GC and record the retained
 * heap. With the drain ON the swapped-out round's entities are unregistered through the engine's own
 * remove hook and the heap stays flat; with it OFF the prior rounds' ships stay pinned in the
 * renderer / fog map / anim managers and the heap trends up. The wrapper
 * compares the two slopes — the leak the drain removes,
 * isolated.
 *
 * <p><b>Faithful advance.</b> Each step goes through the SAME {@link EngineBackedCombatStepBoundary}
 * the serve loop uses ({@code fixedDt * timeMult * combatSpeedMult} → {@code invokeAdvanceInner}), so
 * the simulation the measurement drives is byte-identical to a served round's; nothing about combat
 * is reimplemented or faked. The only non-faithful surface is the measurement scaffold
 * itself (the GC + {@code Runtime} heap read + the per-round log), which has no engine effect.
 *
 *
 *
 */
public final class RealEngineHeapDrainMeasureMain {

    private RealEngineHeapDrainMeasureMain() {
    }

    public static void main(String[] args) throws Exception {
        int rounds = 60;
        int framesPerRound = 300;
        float fixedDt = SimTickRate.fixedDt(); // canonical DEFAULT_DT (byte-identical to a served round)
        String label = "heap-drain-measure";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--rounds": rounds = Integer.parseInt(args[++i]); break;
                case "--frames-per-round": framesPerRound = Integer.parseInt(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                case "--label": label = args[++i]; break;
                default: break;
            }
        }
        boolean correctivesOff = "true".equalsIgnoreCase(
                System.getProperty("cc.predict.correctivesOff", "false"));

        System.out.println("heap-drain-measure-begin label=" + label
                + " rounds=" + rounds
                + " frames_per_round=" + framesPerRound
                + " fixed_dt=" + fixedDt
                + " correctives_off=" + correctivesOff);

        // ---- Bring up the real engine (L3 substrate + content init + 2-Mudskipper roster) ----
        EngineCombatBinding binding = RealEngineCombatBindingFactory.create();
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);

        // ---- Warm-up round: advance once + reset so the fog trackers are minted (the drain's fog
        // corrective only resolves after a round has run) and the JIT/allocators settle, before the
        // first measured round. The warm-up's own reset exercises the drain too (logged, not scored). ----
        long simTick = 0L;
        for (int f = 0; f < framesPerRound; f++) {
            step.step(fixedDt, simTick++);
        }
        RealEngineCombatBindingFactory.resetForRedeploy();
        double baselineMb = measureHeapMb();
        System.out.println("heap-drain-measure round=-1 phase=warmup heap_after_gc_mb="
                + fmt(baselineMb) + " live_ships=" + liveShipCount(binding));

        double firstMb = -1.0;
        double lastMb = -1.0;
        double maxMb = baselineMb;
        for (int round = 0; round < rounds; round++) {
            for (int f = 0; f < framesPerRound; f++) {
                step.step(fixedDt, simTick++);
            }
            RealEngineCombatBindingFactory.resetForRedeploy();
            double heapMb = measureHeapMb();
            int ships = liveShipCount(binding);
            if (firstMb < 0.0) {
                firstMb = heapMb;
            }
            lastMb = heapMb;
            if (heapMb > maxMb) {
                maxMb = heapMb;
            }
            System.out.println("heap-drain-measure round=" + round
                    + " heap_after_gc_mb=" + fmt(heapMb)
                    + " live_ships=" + ships);
        }

        double deltaTotal = lastMb - firstMb;
        double perRound = rounds > 1 ? deltaTotal / (rounds - 1) : 0.0;
        System.out.println("heap-drain-measure-end label=" + label
                + " correctives_off=" + correctivesOff
                + " rounds=" + rounds
                + " baseline_mb=" + fmt(baselineMb)
                + " first_round_mb=" + fmt(firstMb)
                + " last_round_mb=" + fmt(lastMb)
                + " max_mb=" + fmt(maxMb)
                + " delta_total_mb=" + fmt(deltaTotal)
                + " per_round_mb=" + fmt(perRound));
    }

    /** Live (repository-order) ship count via the binding's motion view; -1 if the view is unbound. */
    private static int liveShipCount(EngineCombatBinding binding) {
        List<?> motions = binding.shipMotionsOrNull();
        return motions == null ? -1 : motions.size();
    }

    /**
     * Retained-heap reading in MB after a best-effort full collection: two GC passes with a
     * finalization run + short settles between, mirroring the scout soak's {@code System.gc()} +
     * measure. Not exact (the JVM may defer), but stable enough across rounds for a slope comparison.
     */
    private static double measureHeapMb() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 2; i++) {
            System.gc();
            System.runFinalization();
            try {
                Thread.sleep(120L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long used = rt.totalMemory() - rt.freeMemory();
        return used / (1024.0 * 1024.0);
    }

    private static String fmt(double mb) {
        return String.format(java.util.Locale.ROOT, "%.2f", mb);
    }
}
