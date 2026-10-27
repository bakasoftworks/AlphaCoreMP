package cc.starsector.client;

/**
 * The refit-override rule
 * — the pure verdict behind
 * the own-hull invariant in {@link PredictReconcileCombatPlugin}. java.lang-only ON
 * PURPOSE so {@code SyncDefectLawSelftest} drives it headless through {@link SyncDefectLawProbe}.
 *
 * <p>The hole: the mission briefing's Refit screen auto-saves the PLAYER flagship as
 * {@code mission_<missionId>_ship_<index>} (modules at
 * {@code index+10000+n}) and the engine swaps that save into the fielded PLAYER
 * fleet at t=0 whenever its hull id matches the requested variant's.
 * The joiner then flies a hull the server never announced. No fielded own hull ever carries that
 * shape lawfully: the mod's ids are catalog / {@code mudskipper2_cc_*} / the {@code shuttlepod_cc_pod}
 * placeholder, and an accepted joiner custom is RE-IDD by the server to {@code cc-v<serial>-<slug>}
 * ({@code SubmittedVariantLedger.mint}) — the raw local stem (which the charset would let a joiner
 * spell {@code mission_x_ship_1}) never reaches the live hull, and {@link #verdict} lets equality
 * win first anyway. So the shape on the LIVE own hull is proof of the leak — never a t=0
 * placeholder or a re-field in flight, so it needs no two-check hold — and it is the one own-hull
 * fork the plain announced-vs-live compare misses when the server has announced nothing yet.
 */
final class OwnHullForkLaw {

    /** What the invariant does with the pair. */
    enum Verdict {
        /** Nothing to judge (unreadable live id, no announce + a lawful live id) or equal ids. */
        OK,
        /** Announced != live, no engine-save shape: the standing two-check streak (a t=0 placeholder
         *  pod or a re-field in flight is a transient, not a fork). */
        MISMATCH,
        /** The live own hull carries the engine's briefing-Refit save id: fire NOW. */
        REFIT_FORK
    }

    /** {@code "mission_" + missionId + "_ship_" + index} — the engine's mission refit-save id shape. */
    static final String ENGINE_REFIT_PREFIX = "mission_";
    static final String ENGINE_REFIT_INFIX = "_ship_";

    private OwnHullForkLaw() {
    }

    /**
     * @param live      the LIVE own hull's variant id ({@code getVariant().getHullVariantId()}), null = unreadable
     * @param announced the registry's OWN variant (the server's announced fielded variant), null = not announced
     */
    static Verdict verdict(String live, String announced) {
        if (live == null) {
            return Verdict.OK;
        }
        if (announced != null && announced.equals(live)) {
            // equality wins — a server-announced id is never re-fielded against itself, whatever
            // its spelling (a joiner may lawfully name a custom stem "mission_x")
            return Verdict.OK;
        }
        if (isEngineRefitSaveId(live)) {
            return Verdict.REFIT_FORK;
        }
        if (announced == null) {
            return Verdict.OK;
        }
        return Verdict.MISMATCH;
    }

    /** The engine shape exactly: {@code mission_<non-empty>_ship_<digits>}; case-sensitive like the engine's writer. */
    static boolean isEngineRefitSaveId(String id) {
        if (id == null || !id.startsWith(ENGINE_REFIT_PREFIX)) {
            return false;
        }
        int at = id.lastIndexOf(ENGINE_REFIT_INFIX);
        if (at <= ENGINE_REFIT_PREFIX.length()) {
            return false; // no mission id between the prefix and the infix
        }
        int digits = at + ENGINE_REFIT_INFIX.length();
        if (digits >= id.length()) {
            return false;
        }
        for (int i = digits; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
