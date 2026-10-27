package cc.starsector.server.runtime;

/**
 * D-STUTTER2 det-bracket attribution witness — the two capped, per-round
 * "npeer-serve det_split" / "det_edges_split" rows plus their shared thresholds, factored out of
 * the {@code serveNPeer} loop of {@link PersistentPilotIntentCombatServer}.
 *
 * <p>WHY here: forensic-only frame-stall attribution. When a served frame's det bracket (or its
 * inner {@code det_edges} sub-bracket) exceeds {@link #DET_ATTRIB_NANOS}, the dominant sub-step is
 * named on the {@code worst_frame} row ({@code causes=det_*}, never flipping {@code first=}) and a
 * single capped line prints the whole split. This is a witness, not behavior — no knob, no wire,
 * no authority effect. The stamps that feed these methods are cut inline on the sim thread (the
 * minimum coherent split, ~0.2 µs/tick); only the attribution arithmetic + row emission live here.
 *
 * <p>Provenance: the split was born to convict the periodic {@code det_edges} burner (the gpee
 * watch-log's synchronous HDD write on the sim thread — first the six-stamp det_split,
 * then the finer det_edges_split).
 * Carved out of PersistentPilotIntentCombatServer
 * as a pure move.
 */
final class DetBracketAttribution {

    /** A det bracket at/over this gets its sub-bracket split attributed
     *  ({@code causes=det_*} + one capped {@code det_split} row). Witness only; no knob. */
    private static final long DET_ATTRIB_NANOS = 25_000_000L;
    /** det_split rows per round (a measured run's census was 12 stalls/round — 40 keeps the tail). */
    private static final int DET_SPLIT_ROWS_MAX = 40;
    private static final String[] DET_SUB_KEYS = {"det_growth", "det_readers", "det_edges",
            "det_despawn", "det_seat", "det_fans"};

    private DetBracketAttribution() {
    }

    /**
     * Attribute one tick's det bracket. Stamps bound six sub-brackets (in serve order):
     * growth = engine-born growth drain + RAPP fan; readers = parity walk + hull-drop read +
     * near-contact/wave/boost/system/afGate samplers; edges = discharge detector + birth drain +
     * shadow fold + truth merge + gpee log; despawn = canister/escort/dive probes + missile-split,
     * afTarget, paladin, despawn observes + reason correction; seat = reclassifier fold + composer
     * evict + NACK/suicide drains + discharge-edge dispatch + seat-mount-loss loop; fans = hulk
     * cut/fracture/pose + pilot-aim fans + seat-event encode. Under {@link #DET_ATTRIB_NANOS}
     * nothing happens (six subtractions). Over it: the dominant key rides the worst_frame row as
     * {@code causes=} (attribution note — never flips {@code first=}) and one {@code det_split}
     * row prints the whole split, capped per round. Returns the updated row count.
     */
    static int attributeDetSplit(WorstFramesLedger ledger, long tick, int round, int rows,
            long start, long growthEnd, long readersEnd, long edgesEnd, long despawnEnd,
            long seatEnd, long end) {
        long total = end - start;
        if (total < DET_ATTRIB_NANOS) {
            return rows;
        }
        long[] sub = {growthEnd - start, readersEnd - growthEnd, edgesEnd - readersEnd,
                despawnEnd - edgesEnd, seatEnd - despawnEnd, end - seatEnd};
        int dominant = 0;
        for (int i = 1; i < sub.length; i++) {
            if (sub[i] > sub[dominant]) {
                dominant = i;
            }
        }
        ledger.noteAttrib(tick, DET_SUB_KEYS[dominant]);
        if (rows >= DET_SPLIT_ROWS_MAX) {
            return rows;
        }
        System.out.println(String.format(java.util.Locale.ROOT,
                "npeer-serve det_split tick=%d round=%d det_ms=%.2f growth=%.2f readers=%.2f"
                + " edges=%.2f despawn=%.2f seat=%.2f fans=%.2f dominant=%s%s",
                tick, round, total / 1e6, sub[0] / 1e6, sub[1] / 1e6, sub[2] / 1e6,
                sub[3] / 1e6, sub[4] / 1e6, sub[5] / 1e6, DET_SUB_KEYS[dominant],
                rows + 1 == DET_SPLIT_ROWS_MAX ? " [row cap reached; causes= continues]" : ""));
        return rows + 1;
    }

    /**
     * One recorded match's 18 combat stalls were ALL det_edges
     * (51–185 ms) on ticks with ≥1 edge — this finer split names the burner INSIDE the
     * bracket (mounts read / detector / births+fold / merge / gpee enqueue) on stall ticks
     * only (five subtractions otherwise). Same {@link #DET_SPLIT_ROWS_MAX} cap as det_split;
     * witness, no knob. Returns the updated row count.
     */
    static int attributeDetEdgesSplit(int rows, long tick, int round, long detReadersEnd,
            long edgesMountsEnd, long edgesObserveEnd, long edgesBirthsEnd, long edgesMergeEnd,
            long detEdgesEnd, int edgesN, int birthsN) {
        if (detEdgesEnd - detReadersEnd < DET_ATTRIB_NANOS || rows >= DET_SPLIT_ROWS_MAX) {
            return rows;
        }
        System.out.println(String.format(java.util.Locale.ROOT,
                "npeer-serve det_edges_split tick=%d round=%d edges_ms=%.2f mounts=%.2f"
                + " observe=%.2f births=%.2f merge=%.2f log=%.2f edges_n=%d births_n=%d",
                tick, round, (detEdgesEnd - detReadersEnd) / 1e6,
                (edgesMountsEnd - detReadersEnd) / 1e6,
                (edgesObserveEnd - edgesMountsEnd) / 1e6,
                (edgesBirthsEnd - edgesObserveEnd) / 1e6,
                (edgesMergeEnd - edgesBirthsEnd) / 1e6,
                (detEdgesEnd - edgesMergeEnd) / 1e6,
                edgesN, birthsN));
        return rows + 1;
    }
}
