package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;

import org.lwjgl.util.vector.Vector2f;

import java.util.List;

/**
 * The engine-typed seam of the {@code HKCG} cut-geometry replay:
 * rebuilds the two {@code Tesselator} carve-feed bags from the server's carried result rings
 * and drives the engine's own PUBLIC two-arg {@code Ship.splitShip(oo, oo)}
 * (the carve is RNG-FREE downstream of the fed bags), so the local piece splits into the
 * server's EXACT shapes instead of rolling the local tesselation RNG. The coordinator stays
 * API-clean; every compile-time engine-internal reference lives here (the {@link HulkOverkillPin} /
 * {@link HulkKeepAliveAdapter} isolation idiom).
 *
 * <p>{@code oo} is a public dumb bag (public default ctor, public fields), but its three
 * carve-consumed list fields are named: a non-ASCII identifier (exact ring) / {@code void} (visual ring) /
 * {@code String} (cut points) — two are Java KEYWORDS, unreachable from source ⇒
 * {@code java.lang.invoke} VarHandles via {@code privateLookupIn} for all three (a settled choice;
 * the loader-B legal lane, {@code java.lang.reflect} is B-denied). The exact-ring name is built
 * from the {@code \u00D3} escape so source encoding can never corrupt it (code point
 * verified against the engine jar). The {@code super} decal-ring field is populated
 * NOWHERE in vanilla and stays untouched.
 *
 * <p>FAIL-SOFT: any init/access failure = adapter DISABLED + a one-shot log; a bag-population
 * failure latches the same way; a {@code splitShip} throw/null does NOT latch (engine-side, not
 * an access failure) — every miss returns {@code null} and the caller falls back to today's
 * local no-arg cut, never a throw into the apply path.
 */
final class HulkCutGeometryAdapter {

    /** The exact bound ring the carve translates + feeds point-in-poly. */
    private static final java.lang.invoke.VarHandle EXACT_RING;
    /** {@code oo.void} — the visual bound ring (keyword name). */
    private static final java.lang.invoke.VarHandle VISUAL_RING;
    /** {@code oo.String} — the cut-point list (armor-cell zeroing along the cut). */
    private static final java.lang.invoke.VarHandle CUT_POINTS;
    private static final Throwable INIT_FAILURE;

    /** The exact-ring field name, U+00D3 escaped (never raw in source). */
    private static final String EXACT_RING_FIELD = "\u00D3" + "00000";

    static {
        java.lang.invoke.VarHandle exactRing = null;
        java.lang.invoke.VarHandle visualRing = null;
        java.lang.invoke.VarHandle cutPoints = null;
        Throwable failure = null;
        try {
            java.lang.invoke.MethodHandles.Lookup lookup =
                    java.lang.invoke.MethodHandles.privateLookupIn(
                            com.fs.starfarer.util.Tesselator.oo.class,
                            java.lang.invoke.MethodHandles.lookup());
            exactRing = lookup.findVarHandle(
                    com.fs.starfarer.util.Tesselator.oo.class, EXACT_RING_FIELD, List.class);
            visualRing = lookup.findVarHandle(
                    com.fs.starfarer.util.Tesselator.oo.class, "void", List.class);
            cutPoints = lookup.findVarHandle(
                    com.fs.starfarer.util.Tesselator.oo.class, "String", List.class);
        } catch (Throwable t) {
            failure = t;
            exactRing = null;
            visualRing = null;
            cutPoints = null;
        }
        EXACT_RING = exactRing;
        VISUAL_RING = visualRing;
        CUT_POINTS = cutPoints;
        INIT_FAILURE = failure;
    }

    /** Latched on the first bag-population access failure — one clean fall to disabled, never
     * per-cut throw spam. Render-thread written; volatile for the smoke/debrief reads. */
    private static volatile Throwable runtimeFailure;
    private static volatile boolean statusLogged;

    private HulkCutGeometryAdapter() {
    }

    /** True while the replay lane is live (all three VarHandles resolved, no access failure). */
    static boolean isEnabled() {
        return EXACT_RING != null && VISUAL_RING != null && CUT_POINTS != null
                && runtimeFailure == null;
    }

    /** The init or first runtime failure, for the smoke/one-shot log; null when healthy. */
    static Throwable failure() {
        return INIT_FAILURE != null ? INIT_FAILURE : runtimeFailure;
    }

    /** The resolved VarHandle shapes, for the smoke assert + the one-shot ENABLED line. */
    static String describe() {
        if (EXACT_RING == null || VISUAL_RING == null || CUT_POINTS == null) {
            return "unresolved";
        }
        return "exact:" + EXACT_RING.varType().getName()
                + " visual:" + VISUAL_RING.varType().getName()
                + " cutPoints:" + CUT_POINTS.varType().getName();
    }

    /**
     * Replay one authoritative cut on {@code parent}: build the two {@code oo} bags (already
     * assembled by the coordinator per the aliasing law — closed rings, chain-order cut
     * points) and call the engine's own {@code splitShip(oo1, oo2)}. Returns the carved-off
     * piece, or {@code null} on ANY miss (parent not an engine {@code Ship}, adapter disabled,
     * bag-population access failure, engine throw/null) — the caller falls back to the local
     * no-arg cut; never throws.
     */
    static ShipAPI cut(ShipAPI parent,
            List<Vector2f> exact1, List<Vector2f> visual1, List<Vector2f> cutPoints1,
            List<Vector2f> exact2, List<Vector2f> visual2, List<Vector2f> cutPoints2) {
        if (!statusLogged) {
            statusLogged = true;
            // The s-F(1) first-cut one-shot line: the replay lane's in-game verdict.
            PilotWatchLog.info("predict hulk-cut-geometry: " + (isEnabled()
                    ? "ENABLED " + describe() : "DISABLED (" + failureText() + ")"));
        }
        if (!isEnabled() || !(parent instanceof com.fs.starfarer.combat.entities.Ship)) {
            return null;
        }
        com.fs.starfarer.combat.entities.Ship ship =
                (com.fs.starfarer.combat.entities.Ship) parent;
        com.fs.starfarer.util.Tesselator.oo bag1;
        com.fs.starfarer.util.Tesselator.oo bag2;
        try {
            bag1 = new com.fs.starfarer.util.Tesselator.oo();
            bag2 = new com.fs.starfarer.util.Tesselator.oo();
            EXACT_RING.set(bag1, exact1);
            VISUAL_RING.set(bag1, visual1);
            CUT_POINTS.set(bag1, cutPoints1);
            EXACT_RING.set(bag2, exact2);
            VISUAL_RING.set(bag2, visual2);
            CUT_POINTS.set(bag2, cutPoints2);
        } catch (Throwable t) {
            runtimeFailure = t;
            PilotWatchLog.info("predict hulk-cut-geometry: DISABLED mid-run ("
                    + failureText() + ")");
            return null;
        }
        try {
            // splitShip is public, RNG-free, pure f(bags): mass/HP by bbox-area ratio,
            // weapon partition point-in-poly, bounds = the fed rings re-centered.
            return ship.splitShip(bag1, bag2);
        } catch (Throwable t) {
            return null; // engine-side miss: the caller counts hkcg_fallback + local cut
        }
    }

    private static String failureText() {
        Throwable cause = failure();
        return cause == null ? "no cause recorded"
                : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
