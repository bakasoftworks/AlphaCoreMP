package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipFluxView;
import cc.starsector.server.adapter.ShipManeuverView;
import cc.starsector.server.adapter.ShipSystemView;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.runtime.PersistentPilotIntentCombatServer.PilotRoundSummary;
import cc.starsector.server.runtime.PersistentPilotIntentCombatServer.PilotServeConfig;
import java.util.List;
import static cc.starsector.server.runtime.PersistentPilotIntentCombatServer.maxSpeedForShipId;
import static cc.starsector.server.runtime.PersistentPilotIntentCombatServer.viewForShipId;

/**
 * The default-OFF boost /
 * system measurement sampler carved VERBATIM out of {@link PilotServeSession} (that file was over
 * its WARN tier; this block was self-contained) - the per-round tallies, the round-start reset
 * ({@link #resetRound}), the per-tick sample ({@link #sample}) and the gated round print
 * ({@link #printRound}). Provenance: the flux-rise / system-engage measurement, ported
 * to the N-peer serve (the origin comments moved with the code below).
 * <p>Ownership = {@link BanOperatorLane}'s shape: PSS holds one final instance and passes every
 * session input as an argument. The two gate knobs ({@code sampleBoost} = cc.measure.boostSample,
 * {@code sampleSystem} = cc.measure.systemSample, both default false) and {@code measureOwnedShipId}
 * stay PSS fields - they are PilotServeResolver copy-back mirrors that PssResolverMirrorContractRows
 * R1a/R1b bind to the session by name - so the lane owns the tallies only.
 * <p>Serve (frame) thread only, no lock (none was held at any of the three old sites). Order of use =
 * PSS mintRound -> resetRound; serveFrame (after the wave-probe rows, before the autofire-gate
 * sampler) -> sample; closeRound (after the per-session send-gap rows, before the growth-seam
 * uninstall) -> printRound. Knobs off = two boolean tests per call, no allocation (as before).
 */
final class BoostSystemSampleLane {
    private int boostSamples;
    private float ownFluxPeak;
    private float ownMaxSpeedCapMax;
    private float ownMaxSpeedCapMin;
    private int sysLateFrom;
    private int sysSamples;
    private int sysActiveTicks;
    private int sysActiveLateTicks;
    private float sysEffectMax;
    private float sysMaxSpeedActiveMax;
    private float sysMaxSpeedIdleMax;
    private float sysMaxSpeedActiveLateMax;
    private float sysMaxSpeedIdleLateMax;
    private String sysStateLate;

    /** PSS mintRound's round-start reset (every round, knobs on or off, as before); {@code config} =
     *  the session's own PilotServeConfig (sysLateFrom = framesPerSession / 2). */
    void resetRound(PilotServeConfig config) {
        // Measure tallies at N: the retired serve() lane's
        // per-round counters verbatim (git history) — same fields, same print grammar
        // below (sampleBoost / sampleSystem).
        boostSamples = 0;
        ownFluxPeak = 0f;
        ownMaxSpeedCapMax = Float.NaN;
        ownMaxSpeedCapMin = Float.NaN;
        sysLateFrom = config.framesPerSession() / 2;
        sysSamples = 0;
        sysActiveTicks = 0;
        sysActiveLateTicks = 0;
        sysEffectMax = 0f;
        sysMaxSpeedActiveMax = Float.NaN;
        sysMaxSpeedIdleMax = Float.NaN;
        // SECOND-HALF cap maxes (the sysLateFrom split the re-engagement proof already
        // uses): the fly-in ARRIVAL sprint at N (the engine's deploy path grants a large
        // temporary maxSpeed while the hull flies onto the map, and the system stays
        // unengaged until arrival) lands in the whole-run idle max, so the jets-lift
        // check compares the late pair — arrival is long over by mid-run.
        sysMaxSpeedActiveLateMax = Float.NaN;
        sysMaxSpeedIdleLateMax = Float.NaN;
        sysStateLate = "";
    }

    /** PSS serveFrame's post-advance sample. {@code frame} = the round's served frame
     *  (0 .. framesPerSession - 1); {@code measureOwnedShipId} = the resolver's MIN seat wire id (> 0 for
     *  a seated hull; Long.MAX_VALUE when it saw no seat); the knobs = the session's gate fields. */
    void sample(EngineCombatBinding binding, int frame, long measureOwnedShipId,
            boolean sampleBoost, boolean sampleSystem) {
        // Flux-rise measurement sample at N (default OFF):
        // the retired serve() lane's twin (git history), but keyed by wire id
        // (ShipFluxView.shipId()) instead of
        // repository index 0 — the descriptor roster at N is not index-stable.
        // Fail-soft: a null list or an absent measured ship just skips this tick.
        if (sampleBoost) {
            List<ShipFluxView> flux = binding.shipFluxStatesOrNull();
            List<ShipManeuverView> maneuver = binding.shipManeuverProfilesOrNull();
            ShipFluxView ownFlux = null;
            if (flux != null) {
                for (ShipFluxView view : flux) {
                    if (view.shipId() == measureOwnedShipId) {
                        ownFlux = view;
                        break;
                    }
                }
            }
            if (ownFlux != null) {
                float fluxLevel = ownFlux.fluxLevel();
                float cap = maxSpeedForShipId(maneuver, measureOwnedShipId);
                if (Float.isFinite(fluxLevel) && fluxLevel > ownFluxPeak) {
                    ownFluxPeak = fluxLevel;
                }
                if (Float.isFinite(cap)) {
                    if (Float.isNaN(ownMaxSpeedCapMax) || cap > ownMaxSpeedCapMax) {
                        ownMaxSpeedCapMax = cap;
                    }
                    if (Float.isNaN(ownMaxSpeedCapMin) || cap < ownMaxSpeedCapMin) {
                        ownMaxSpeedCapMin = cap;
                    }
                }
                boostSamples++;
            }
        }
        // System-engage measurement sample at N (default OFF): the read-only
        // twin of the retired serve() lane's — the foe-idle leg does not exist at N
        // (live-seat law; the
        // one-client measure admits one seat, so there is no foe). Fail-soft like above.
        if (sampleSystem) {
            List<ShipSystemView> systems = binding.shipSystemStatesOrNull();
            List<ShipManeuverView> maneuver = binding.shipManeuverProfilesOrNull();
            ShipSystemView ownSys = viewForShipId(systems, measureOwnedShipId);
            if (ownSys != null) {
                sysSamples++;
                boolean active = ownSys.active();
                if (active) {
                    sysActiveTicks++;
                    if (frame >= sysLateFrom) {
                        sysActiveLateTicks++;
                    }
                }
                float effect = ownSys.effectLevel();
                if (Float.isFinite(effect) && effect > sysEffectMax) {
                    sysEffectMax = effect;
                }
                float cap = maxSpeedForShipId(maneuver, measureOwnedShipId);
                if (Float.isFinite(cap)) {
                    if (active) {
                        if (Float.isNaN(sysMaxSpeedActiveMax) || cap > sysMaxSpeedActiveMax) {
                            sysMaxSpeedActiveMax = cap;
                        }
                        if (frame >= sysLateFrom
                                && (Float.isNaN(sysMaxSpeedActiveLateMax)
                                        || cap > sysMaxSpeedActiveLateMax)) {
                            sysMaxSpeedActiveLateMax = cap;
                        }
                    } else {
                        if (Float.isNaN(sysMaxSpeedIdleMax) || cap > sysMaxSpeedIdleMax) {
                            sysMaxSpeedIdleMax = cap;
                        }
                        if (frame >= sysLateFrom
                                && (Float.isNaN(sysMaxSpeedIdleLateMax)
                                        || cap > sysMaxSpeedIdleLateMax)) {
                            sysMaxSpeedIdleLateMax = cap;
                        }
                    }
                }
                if (frame >= sysLateFrom) {
                    sysStateLate = ownSys.state();
                }
            }
        }
    }

    /** PSS closeRound's gated measurement lines. {@code round} = the closing round (>= 0),
     *  {@code summary} = its PilotRoundSummary (fires only); the knobs = the session's gate fields. */
    void printRound(int round, PilotRoundSummary summary, boolean sampleBoost, boolean sampleSystem) {
        // Gated measurement lines at N: the retired serve()
        // lane's grammar verbatim — measure-real-pilot-loop.ps1 parses these fields by
        // name.
        if (sampleBoost) {
            System.out.println("pilot-boost-sample round=" + round
                    + " fires=" + summary.firesIssued()
                    + " boost_samples=" + boostSamples
                    + " own_flux_peak=" + ownFluxPeak
                    + " own_maxspeed_cap_max=" + ownMaxSpeedCapMax
                    + " own_maxspeed_cap_min=" + ownMaxSpeedCapMin);
        }
        if (sampleSystem) {
            System.out.println("pilot-system-sample round=" + round
                    + " sys_late_from=" + sysLateFrom
                    + " sys_samples=" + sysSamples
                    + " sys_active_ticks=" + sysActiveTicks
                    + " sys_active_late_ticks=" + sysActiveLateTicks
                    + " sys_effect_max=" + sysEffectMax
                    + " sys_maxspeed_active_max=" + sysMaxSpeedActiveMax
                    + " sys_maxspeed_idle_max=" + sysMaxSpeedIdleMax
                    + " sys_state_late=" + (sysStateLate.isEmpty() ? "NONE" : sysStateLate)
                    // Appended fields only (grammar rule): the SECOND-HALF cap pair — the
                    // jets-lift comparison rides these (the fly-in arrival sprint poisons
                    // the whole-run idle max at N; see the counter block's comment).
                    + " sys_maxspeed_active_late_max=" + sysMaxSpeedActiveLateMax
                    + " sys_maxspeed_idle_late_max=" + sysMaxSpeedIdleLateMax);
        }
    }
}
