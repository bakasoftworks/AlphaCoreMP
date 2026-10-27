package cc.starsector.server.runtime;

import cc.starsector.server.runtime.PersistentDedicatedCombatServer.ManeuverGatedStepFactory;
import java.util.Map;
import java.util.Objects;

/**
 * The three pieces the {@code --maneuver} command+observe path needs beyond the
 * {@link cc.starsector.server.bridge.EngineCombatBinding}: the per-connection MANEUVER
 * gated-step factory (the real-engine one wraps {@code HeadlessCombatContext
 * .engineClientIntentManeuverAuthorityStep}), the server-owned {@link ContentIdRegistry},
 * and the {@code shipId -> owner} map the snapshot source folds in.
 *
 * <p>Promoted to a top-level type so the headless scout's real-engine
 * composed-maneuver-server run, the {@link DedicatedCombatServerMain}
 * {@code --maneuver-factory} reflective hook, and the game-free contract all
 * share one component type and one {@link ManeuverComponentsFactory assembler} — instead of
 * the scout duplicating the recipe inline. A {@code --maneuver-factory} class returns this
 * from a {@code public static ManeuverComponents create(EngineCombatBinding)} method, so the
 * standing server stays engine-identity-free and the same {@code main} drives both the
 * game-free contract and the real-engine run.
 */
public record ManeuverComponents(
        ManeuverGatedStepFactory gatedStepFactory,
        ContentIdRegistry contentRegistry,
        Map<Long, Integer> ownerByShipId) {

    public ManeuverComponents {
        Objects.requireNonNull(gatedStepFactory, "gatedStepFactory");
        Objects.requireNonNull(contentRegistry, "contentRegistry");
        Objects.requireNonNull(ownerByShipId, "ownerByShipId");
        ownerByShipId = Map.copyOf(ownerByShipId);
    }
}
