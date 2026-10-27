package cc.starsector.server.bridge;

import java.lang.reflect.Method;
import java.util.List;

/**
 * D-ZEROFLUX FIX — the server-side pre-frame snap that keeps an ENGAGED
 * carrier's zero-flux boost at the server's fixed 60 Hz exactly as a high-fps vanilla client keeps
 * it.
 *
 * <p><b>Engine law (every frame for a ship
 * with launch bays whose fighters are NOT pulled back and that is not overloaded/venting):</b>
 * {@code B = ZERO_FLUX_LEVEL·0.01·max + dt·diss + min(100, 0.01·max)}; iff {@code curr < B}:
 * {@code curr < 0.95·B} ⇒ {@code increaseFlux(3·dt·diss)} (a POSITIVE add — resets the elite
 * boost timer) else {@code setCurrFlux(B ± 3·rand)} (resets nothing).
 * Then {@code D.advance} dissipates {@code dt·diss}. After an in-band snap the next frame reads
 * {@code B − dt·diss}, which is BELOW the add line iff {@code dt·diss > 0.05·B} — true at
 * dt = 1/60 for any dissipation ≳ 870 (Astral-class: diss ≈ 1000 ⇒ 16.7 > 14.5), false at the
 * owner's 540 fps (1.9). So the vanilla law is frame-rate dependent (threshold ≈ 70 fps) and the
 * server sits on the no-boost side: an add every ~3 ticks, the elite timer never reaches 1 s,
 * {@code isEngineBoostActive} stays false while the client (its own engine at render dt + the
 * same 1 s timer) shows the boost — the equal-flux / opposite-verdict signature.
 *
 * <p><b>The fix = the dt→0 limit of the same law, applied by the server before each engine
 * frame:</b> a carrier that the engine snapped into the band last frame and whose dissipation
 * pulled it under the add line ({@code curr ∈ [B − dt·diss − margin, 0.95·B)}) is lifted to
 * {@link #preSnapValue} (mid-band) so the engine takes its OWN snap branch — no positive add, the
 * timer accumulates, the boost returns 1 s after the last real add exactly as at high fps. A ship
 * ramping up from far below (a fresh engage at low flux, post-vent) is left to the engine's own
 * add ramp — vanilla's ~1 s boost dropout on engage is preserved; weapon fire / damage adds still
 * reset the timer (they land ABOVE B and dissipate back into the band without an add, as at high
 * fps). Not a stat hack: raising {@code zeroFluxMinimumFluxLevel} would grant non-elite hulls a
 * boost vanilla never gives; the elite/non-elite verdict stays the engine's.
 *
 * <p>Public-API-only reflection ({@code ShipAPI} / {@code FluxTrackerAPI} /
 * {@code MutableShipStatsAPI} / {@code MutableStat} + the {@code Helmsmanship.ZERO_FLUX_LEVEL}
 * constant), bound once against the first ship's loader. Fail-soft: a reflective failure disarms
 * the snap for the session with ONE loud line (the serve never refuses; the boost regresses
 * visibly, never silently — the {@code zf-probe} rows read {@code why=add} again). Per tick the
 * cost is a handful of interface calls on engaged carriers only (non-carriers exit at
 * {@code hasLaunchBays}). The step amount handed in is the boundary's effective step (a ship-local
 * time mult — a phased carrier — shifts the engine's B by its dt·diss; the ±2.5 % mid-band lift
 * absorbs small deltas only; carriers do not phase in the standing roster).
 */
final class EngagedCarrierBandSnap {
    /** Engine constant: below this fraction of the band the engine ADDS flux (resets the timer). */
    static final float ADD_LINE_FRACTION = 0.95f;
    /** Pre-snap lands here (fraction of B): inside {@code [0.95·B, B)} with ±2.5 % of B to spare. */
    static final float PRE_SNAP_FRACTION = 0.975f;
    /** Lower bound slack under {@code B − dt·diss}: the engine's ±3 snap jitter + 1 unit float. */
    static final float BAND_EXIT_MARGIN = 4f;
    /** Vanilla default of {@code Helmsmanship.ZERO_FLUX_LEVEL} (public static, read reflectively). */
    static final float DEFAULT_ZERO_FLUX_LEVEL = 1.0f;

    private static Api api;
    private static boolean disarmed;
    private static long preSnaps;

    private EngagedCarrierBandSnap() {
    }

    // ---------------------------------------------------------------- pure law (contract-tested)

    /** The engine's band B for one frame — same float order as {@code Ship.advance}. */
    static float engageBand(float maxFlux, float dissipation, float dt, float zeroFluxLevel) {
        float band = zeroFluxLevel * 0.01f * maxFlux;
        float step = dt * dissipation + Math.min(100.0f, 0.01f * maxFlux);
        return band + step;
    }

    /**
     * True iff the engine would ADD this frame ({@code curr < 0.95·B}) although the ship was
     * inside the band before this frame's dissipation ({@code curr ≥ B − dt·diss − margin}) —
     * the frame-rate artefact, and nothing else.
     */
    static boolean needsPreSnap(float curr, float band, float dissipationStep) {
        return curr < ADD_LINE_FRACTION * band
                && curr >= band - dissipationStep - BAND_EXIT_MARGIN;
    }

    /** The lifted value: mid-band, so the engine's own snap branch ({@code setCurrFlux}) runs. */
    static float preSnapValue(float band) {
        return PRE_SNAP_FRACTION * band;
    }

    // ---------------------------------------------------------------- per-tick applier

    /** Pre-snaps count since boot (the headless probe's witness; never printed per tick). */
    static long preSnapCount() {
        return preSnaps;
    }

    /**
     * Apply the pre-snap to every engaged carrier in {@code ships} for a frame of {@code dt}
     * seconds. Called by the step boundary immediately BEFORE the engine advance.
     */
    static void apply(List<?> ships, float dt) {
        if (disarmed || ships == null || ships.isEmpty()) {
            return;
        }
        try {
            for (Object ship : ships) {
                if (ship == null) {
                    continue;
                }
                if (api == null) {
                    api = new Api(ship);
                }
                if (!((Boolean) api.shipHasLaunchBays.invoke(ship)).booleanValue()
                        || ((Boolean) api.shipIsPullBackFighters.invoke(ship)).booleanValue()
                        || ((Boolean) api.shipIsHulk.invoke(ship)).booleanValue()) {
                    continue;
                }
                Object ft = api.shipGetFluxTracker.invoke(ship);
                if (ft == null || ((Boolean) api.fluxIsOverloadedOrVenting.invoke(ft)).booleanValue()) {
                    continue;
                }
                float curr = ((Number) api.fluxGetCurrFlux.invoke(ft)).floatValue();
                float max = ((Number) api.fluxGetMaxFlux.invoke(ft)).floatValue();
                Object stats = api.shipGetMutableStats.invoke(ship);
                Object dissStat = stats == null ? null : api.statsFluxDissipation.invoke(stats);
                if (dissStat == null) {
                    continue;
                }
                float diss = ((Number) api.statGetModifiedValue.invoke(dissStat)).floatValue();
                float band = engageBand(max, diss, dt, api.zeroFluxLevel);
                if (needsPreSnap(curr, band, dt * diss)) {
                    api.fluxSetCurrFlux.invoke(ft, Float.valueOf(preSnapValue(band)));
                    preSnaps++;
                }
            }
        } catch (ClassNotFoundException e) {
            disarmed = true; // game-free fake lanes carry no engine API — nothing to snap, by design
            System.out.println("cc-zf-band idle (no engine API on this lane: " + e.getMessage() + ")");
        } catch (Throwable t) {
            disarmed = true;
            System.out.println("cc-zf-band DISARMED (reflective failure; engaged carriers lose the"
                    + " zero-flux boost at 60 Hz until restart): " + t);
        }
    }

    /** Bound once against the first ship's own loader; every handle is a public API method. */
    private static final class Api {
        final Method shipHasLaunchBays, shipIsPullBackFighters, shipIsHulk, shipGetFluxTracker,
                shipGetMutableStats;
        final Method fluxGetCurrFlux, fluxGetMaxFlux, fluxSetCurrFlux, fluxIsOverloadedOrVenting;
        final Method statsFluxDissipation, statGetModifiedValue;
        final float zeroFluxLevel;

        Api(Object ship) throws Exception {
            ClassLoader loader = ship.getClass().getClassLoader();
            Class<?> shipApi = Class.forName("com.fs.starfarer.api.combat.ShipAPI", false, loader);
            Class<?> fluxApi = Class.forName("com.fs.starfarer.api.combat.FluxTrackerAPI", false, loader);
            Class<?> statsApi = Class.forName("com.fs.starfarer.api.combat.MutableShipStatsAPI", false, loader);
            Class<?> statApi = Class.forName("com.fs.starfarer.api.combat.MutableStat", false, loader);
            shipHasLaunchBays = shipApi.getMethod("hasLaunchBays");
            shipIsPullBackFighters = shipApi.getMethod("isPullBackFighters");
            shipIsHulk = shipApi.getMethod("isHulk");
            shipGetFluxTracker = shipApi.getMethod("getFluxTracker");
            shipGetMutableStats = shipApi.getMethod("getMutableStats");
            fluxGetCurrFlux = fluxApi.getMethod("getCurrFlux");
            fluxGetMaxFlux = fluxApi.getMethod("getMaxFlux");
            fluxSetCurrFlux = fluxApi.getMethod("setCurrFlux", float.class);
            fluxIsOverloadedOrVenting = fluxApi.getMethod("isOverloadedOrVenting");
            statsFluxDissipation = statsApi.getMethod("getFluxDissipation");
            statGetModifiedValue = statApi.getMethod("getModifiedValue");
            float zfl = DEFAULT_ZERO_FLUX_LEVEL;
            try {
                // API-side public static (= 1.0F); a
                // mod could rebind it, so read the live value — fail-soft to the vanilla default.
                zfl = Class.forName("com.fs.starfarer.api.impl.campaign.skills.Helmsmanship", true, loader)
                        .getField("ZERO_FLUX_LEVEL").getFloat(null);
            } catch (Throwable ignored) {
                // vanilla default stands
            }
            zeroFluxLevel = zfl;
            System.out.println("cc-zf-band ARMED zeroFluxLevel=" + zeroFluxLevel
                    + " addLine=" + ADD_LINE_FRACTION + " preSnap=" + PRE_SNAP_FRACTION
                    + " exitMargin=" + BAND_EXIT_MARGIN);
        }
    }
}
