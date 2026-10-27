package cc.starsector.client;

/**
 * The API-free home of {@link PredictReconcileCombatPlugin#flyinBurnFor}'s law.
 * a top-level class free of API types loads WITHOUT
 * {@link PredictReconcileCombatPlugin} (whose superclass is the API's
 * {@code BaseEveryFrameCombatPlugin}), so the no-API {@code RosterAppendOfficerRowSmoke} JVM
 * ({@code client-mod/build.ps1}: selftest + mod jar + server classes only) pins it as C-AT3.
 * Moved verbatim out of the plugin, where it was nested.
 *
 */
final class FlyinBurnLaw {
    private FlyinBurnLaw() {
    }

    static float burnFor(boolean appended, boolean remounted, boolean atRest, boolean fighter,
            float burnSec) {
        if (!(appended || remounted)) {
            return 0f;
        }
        if (appended && !remounted && atRest) {
            return 0f;
        }
        return fighter ? 0f : burnSec;
    }
}
