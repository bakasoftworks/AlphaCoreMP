package cc.starsector.client;

/**
 * The predict init's own-side route in its own class. It is a pure function
 * of the match document and the own wire id (no engine type, no plugin state), so it lives apart from the predict
 * plugin whose session bind applies it ({@link PredictReconcileCombatPlugin#bindSession}).
 *
 * <p>
 * The {@link #ownSideRoute} answer, immutable: the registry-vs-single-foe gate, the own seat's descriptor side
 * (0 or 1), whether the own id names a document row (false = the bind's ERROR tag: presented as side 0), the
 * dense id count (&gt;= 0) and the per-id document variant + side maps (unmodifiable, ascending id).
 */
final class OwnSideRoute {
    final boolean registryShape;
    final int ownDescSide;
    final boolean ownRowFound;
    final long total;
    final java.util.Map<Long, String> variantById;
    final java.util.Map<Long, Integer> descSideById;

    private OwnSideRoute(boolean registryShape, int ownDescSide, boolean ownRowFound, long total,
            java.util.Map<Long, String> variantById, java.util.Map<Long, Integer> descSideById) {
        this.registryShape = registryShape;
        this.ownDescSide = ownDescSide;
        this.ownRowFound = ownRowFound;
        this.total = total;
        this.variantById = java.util.Collections.unmodifiableMap(variantById);
        this.descSideById = java.util.Collections.unmodifiableMap(descSideById);
    }

    /**
     * The predict init's ROUTE and its dense own-side walk (both in
     * {@link PredictReconcileCombatPlugin#bindSession}: the init's bind and the late-attach bind) as ONE pure function,
     * so the selftest that pins the 1v1 registry route drives the bind's own logic, never a copy of it.
     * Inputs = what the bind reads: the cached match document
     * (null = none) and the session's own wire id (a signed long: 0 = observer, &gt; 0 = a seat's hull id).
     * The route key IS the shared shape law's ({@link MatchMissionBuilder#isRegistryShape}), never re-derived here, so
     * the mission build, the dial and this init cannot disagree on a shape: a 1v1 document is a registry shape (the
     * server serves it as two seats and re-points a seat's pick); only a vanilla window or no document routes
     * single-foe. A registry route walks the server's dense id contract (ids 1..N in roster order x count), reading
     * the rows DIRECTLY so SEAT rows count like any other; the own side is the own id's row side (0 when the id
     * names no row). A single-foe route carries no walk (empty maps, side 0, total 0). No engine type and no plugin
     * state: the bind assigns the answer's fields itself, in its own order.
     */
    static OwnSideRoute ownSideRoute(cc.starsector.server.match.MatchDescriptor desc, long ownShipId) {
        String descShape = desc != null ? desc.shape() : "";
        java.util.Map<Long, String> variants = new java.util.TreeMap<Long, String>();
        java.util.Map<Long, Integer> sides = new java.util.TreeMap<Long, Integer>();
        if (!MatchMissionBuilder.isRegistryShape(descShape)) {
            return new OwnSideRoute(false, 0, false, 0L, variants, sides);
        }
        long id = 0L;
        for (cc.starsector.server.match.MatchDescriptor.RosterRow row : desc.roster()) {
            for (int i = 0; i < row.count(); i++) {
                id++;
                variants.put(Long.valueOf(id), row.variantId());
                sides.put(Long.valueOf(id), Integer.valueOf(row.side()));
            }
        }
        Integer ownRowSide = sides.get(Long.valueOf(ownShipId));
        return new OwnSideRoute(true, ownRowSide != null ? ownRowSide.intValue() : 0, ownRowSide != null, id,
                variants, sides);
    }
}
