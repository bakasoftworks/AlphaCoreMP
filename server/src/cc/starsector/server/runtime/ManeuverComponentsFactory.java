package cc.starsector.server.runtime;

import cc.starsector.server.runtime.PersistentDedicatedCombatServer.ManeuverGatedStepFactory;
import java.util.Map;
import java.util.Objects;

/**
 * Shared assembler for the {@link ManeuverComponents} the {@code --maneuver} command+observe
 * path needs. It promotes the recipe the headless scout's real-engine
 * composed-maneuver-server run inlined (
 * {@code runComposedManeuverServerRealEngineCapture}) and the game-free
 * {@code FakeManeuverComponentsFactory} duplicated, into one place — so the scout's
 * real-engine run and the {@link DedicatedCombatServerMain} {@code --maneuver-factory} path
 * build the components identically.
 *
 * <p>It is deliberately engine-identity-free: the only engine/headless-specific part of the
 * components — the per-connection MANEUVER {@link ManeuverGatedStepFactory} — is supplied by
 * the caller. The scout passes
 * {@code headlessCombatContext::engineClientIntentManeuverAuthorityStep} (the real
 * {@code ShipAPI.giveCommand(ACCELERATE)} gate); the game-free factory passes its
 * recording step. The content registry is always fresh, and the owner map defaults to the
 * single-commanded-ship mapping the round-trip proved unless the caller overrides it.
 *
 * <p>This is the assembly half of the {@code --maneuver-factory} contract. A standalone
 * <em>real-engine</em> {@code create(EngineCombatBinding)} factory class still has to obtain a
 * configured {@code HeadlessCombatContext} to source its gated step from — the same gap a
 * real-engine {@code --binding-factory} has (today the scout holds the live context in-process
 * and calls {@link #singleCommandedShip} directly)..
 */
public final class ManeuverComponentsFactory {

    /**
     * The single-commanded-ship owner map proven by the maneuver round-trip:
     * repository ship id {@code 1} is owned by side {@code 0}. This is the
     * mapping the scout's inline factory and the game-free factory both used.
     */
    public static final Map<Long, Integer> SINGLE_COMMANDED_SHIP_OWNER_MAP =
            Map.of(Long.valueOf(1L), Integer.valueOf(0));

    private ManeuverComponentsFactory() {
    }

    /**
     * Assemble components from the caller's gated-step factory, a fresh
     * {@link ContentIdRegistry}, and the given owner map.
     */
    public static ManeuverComponents create(
            ManeuverGatedStepFactory gatedStepFactory, Map<Long, Integer> ownerByShipId) {
        Objects.requireNonNull(gatedStepFactory, "gatedStepFactory");
        Objects.requireNonNull(ownerByShipId, "ownerByShipId");
        return new ManeuverComponents(gatedStepFactory, new ContentIdRegistry(), ownerByShipId);
    }

    /**
     * Assemble components for the single commanded ship the round-trip proved
     * ({@link #SINGLE_COMMANDED_SHIP_OWNER_MAP}).
     */
    public static ManeuverComponents singleCommandedShip(ManeuverGatedStepFactory gatedStepFactory) {
        return create(gatedStepFactory, SINGLE_COMMANDED_SHIP_OWNER_MAP);
    }
}
