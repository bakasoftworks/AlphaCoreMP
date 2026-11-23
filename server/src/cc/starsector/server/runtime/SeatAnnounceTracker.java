package cc.starsector.server.runtime;

import cc.starsector.server.match.AllSkillsFleetLaw;
import cc.starsector.server.runtime.PersistentPilotIntentCombatServer.NPeerHooks;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The serve-lifetime seat-announce
 * tracker — the {@link SeatAnnounce} value, the round-open seed ({@link #addTrackerSeedRows}) and
 * the seat re-point announce ({@link #announceSeatVariantIfRepointed}, both overloads) and the
 * seat fielding's record-write announce ({@link #announceFieldingRecordWrite}).
 *
 * <p>WHY: carved VERBATIM out of {@link PersistentPilotIntentCombatServer}
 * — PPICS sat over its
 * non-blank WarnNb and every seat-announce addition grew it. This class is the growth home for
 * seat-announce work, never PPICS. The RAPP
 * chunk+fan+record loop ({@code PersistentPilotIntentCombatServer.fanRosterAppendChunks}) stays
 * in PPICS (the wave lane's home); the announce calls it re-qualified.
 *
 * <p>Threads / locks / init order: stateless — static methods + a record, no static field; the
 * tracker map is caller-owned (the serve session's {@code lastAnnouncedVariantBySeat}), so the
 * move changes no thread, lock or init order.
 */
public final class SeatAnnounceTracker {

    private SeatAnnounceTracker() {
    }

    /**
     * One serve-lifetime announce-tracker value — the
     * RAPP row a seat's variant was last ANNOUNCED with (what every window live at that announce
     * received, its fleet-record bits and digest stripped) + the fly-in's descriptor variant id at
     * that announce. Stored
     * whole because a vacant seat has no hull/session to rebuild a row from (the row ctor
     * throws on partial data; the name/cr helpers are bridge-private), so the round-open seed
     * ({@link #addTrackerSeedRows}) replays it as-is. Top-level member (not inside
     * {@code NPeerHooks} beside {@code FlyInResult}) so the lanes spell it
     * {@code SeatAnnounceTracker.SeatAnnounce}.
     */
    public record SeatAnnounce(RosterAppendFrameCodec.AppendRow row, String descriptorVariantId) {
        public SeatAnnounce {
            Objects.requireNonNull(row, "row");
        }
    }

    /**
     * The round-open tracker seed. The LAW: one belief
     * per seat across every window — survivors learn a seat's variant through the live announce
     * fan, joiners through the round-open replay log; the tracker is serve-lifetime (never
     * dropped on vacate/BYE/wrap), so a seat whose last announced row differs from its
     * descriptor (a null descriptor counts as differing) re-seeds that row into the fresh round
     * log, unless the round's carried-pick rows already carry that wire id (a redeployed seat's
     * carried row IS what it fielded). Appends the seed rows to {@code rows} (the ONE fan's list,
     * so they fan live — an equal-variant count-ignore for survivors — AND record for joiners);
     * returns how many it appended. A seed row describes a VACANT seat, so it is never a fleet-record
     * write: every stored row is the announce's flagless copy.
     */
    static int addTrackerSeedRows(Map<Integer, SeatAnnounce> tracker,
            List<RosterAppendFrameCodec.AppendRow> rows) {
        java.util.Set<Long> carriedWireIds = new java.util.HashSet<>();
        for (RosterAppendFrameCodec.AppendRow carried : rows) {
            carriedWireIds.add(carried.wireId());
        }
        int added = 0;
        for (SeatAnnounce announce : tracker.values()) {
            RosterAppendFrameCodec.AppendRow row = announce.row();
            if (row.variantId().equals(announce.descriptorVariantId())
                    || carriedWireIds.contains(row.wireId())) {
                continue;
            }
            rows.add(row);
            added++;
        }
        return added;
    }

    /**
     * W7 B1b: announce one seat fly-in's DEPLOYED variant when it differs from what
     * clients believe — one RAPP row on the seat's wire id, riding {@link PersistentPilotIntentCombatServer#fanRosterAppendChunks}
     * so late-join replay is automatic. Believed = the last-announced tracker row's variant,
     * defaulting to the fly-in's own descriptor variant (absent = no window was ever told
     * otherwise; the tracker stores the whole row it fanned). Always records the
     * fielded variant on the door first (the {@code /ship} listing's {@code (piloting)} mark).
     * A legacy identity-only fly-in ({@code variantId == null} — contract fakes/tests) has
     * nothing truthful to announce and skips entirely. Returns whether a repoint was ANNOUNCED
     * (fielded ≠ believed) — that edge is exactly a cross-variant refield, the
     * callers' arm condition for the mask-staleness gate. The mid-round form: the row fans live.
     */
    static boolean announceSeatVariantIfRepointed(PilotIntentDatagramServer door, int seatId,
            NPeerHooks.FlyInResult flyIn, Map<Integer, SeatAnnounce> lastAnnouncedVariantBySeat,
            List<byte[]> roundRosterAppendFrames) throws IOException {
        return announceSeatVariantIfRepointed(door, seatId, flyIn, lastAnnouncedVariantBySeat,
                roundRosterAppendFrames, null);
    }

    /**
     * {@link #announceSeatVariantIfRepointed} with a
     * {@code deferFanOrNull} — non-null (the round wrap only) collects the built row there
     * instead of fanning it, so the caller fans it AFTER the MRST (the client stamps a pre-MRST
     * row with the OLD round and drains it in the old world). The tracker put, the fielded
     * record, the return value and the log row are unchanged; the log row gains
     * {@code fan=post_mrst}. Null = the live fan (every mid-round caller).
     */
    static boolean announceSeatVariantIfRepointed(PilotIntentDatagramServer door, int seatId,
            NPeerHooks.FlyInResult flyIn, Map<Integer, SeatAnnounce> lastAnnouncedVariantBySeat,
            List<byte[]> roundRosterAppendFrames,
            List<RosterAppendFrameCodec.AppendRow> deferFanOrNull) throws IOException {
        return announce(door, seatId, flyIn, lastAnnouncedVariantBySeat, roundRosterAppendFrames,
                deferFanOrNull, false, null);
    }

    /**
     * The seat FIELDING's announce — the fly-in and the
     * round-wrap redeploy call this, never a transfer / module / pod lane. Every fielding is a
     * fleet-record write, and every record write puts EXACTLY ONE row on the round's log flagged
     * {@link RosterAppendFrameCodec#FLAG_FLEET_FIELDED} (+ {@link RosterAppendFrameCodec#FLAG_FLEET_ROUND_OPEN}
     * when {@code flyIn.fleetRoundOpen()}) with {@code flyIn.fleetKeyDigest()}, so a late joiner's
     * catch-up replays the write in order. The re-point gate is UNCHANGED (its compare, its return
     * value, its tracker put and its {@code seat_variant_repoint} row: the return arms the mask gate,
     * the resolver rebind and the af-target reset, and a same-variant refield never arms): when the
     * gate emits, THAT row goes out flagged; when it suppresses (fielded == believed), this entry
     * builds the same five-field row from the fly-in, flags it and fans it (or defers it beside the
     * gate's rows), touching no tracker state, printing no row, returning false. A legacy fly-in
     * ({@code variantId == null}) emits nothing.
     *
     * <p>SIZING: always-emit fans one 1-row frame a fielding live (30 seats x 1 life a minute x
     * 180 min = 5,400 frames in a 3-h endless round), but the round's log keeps, per wire id, only
     * its changing writes (the wire id's first fielding of the round included) plus at most ONE equal
     * re-write, the newest: {@code ledger} retires every older one for replay the moment the wire id's
     * next flagged row is on the log ({@link EqualRewriteLedger}). A seat re-fielding one variant all
     * round replays 2 frames however many lives it flew (30 seats = ~60 frames, not 5,400), so a late
     * joiner's catch-up stays near the ~3,500 frames a 3-h endless log held before always-emit (~55
     * ticks at the default {@link RosterAppendCatchup#DEFAULT_CAP} frames a tick while the RTT is at
     * most 7 ticks). A retired slot keeps its index (null; its frame bytes are freed) and a cursor
     * skips it without spending its per-tick or global budget.
     *
     * <p>The {@code ledger} parameter and the sizing above.
     */
    static boolean announceFieldingRecordWrite(PilotIntentDatagramServer door, int seatId,
            NPeerHooks.FlyInResult flyIn, Map<Integer, SeatAnnounce> lastAnnouncedVariantBySeat,
            List<byte[]> roundRosterAppendFrames,
            List<RosterAppendFrameCodec.AppendRow> deferFanOrNull, EqualRewriteLedger ledger)
            throws IOException {
        return announce(door, seatId, flyIn, lastAnnouncedVariantBySeat, roundRosterAppendFrames,
                deferFanOrNull, true, Objects.requireNonNull(ledger, "ledger"));
    }

    /**
     * The one announce body behind both entries; {@code recordWrite} = the caller is a seat
     * fielding (the row goes out flagged, and an equal-variant fielding still emits it);
     * {@code ledgerOrNull} = the round log's equal re-write ledger (non-null exactly when
     * {@code recordWrite}).
     */
    private static boolean announce(PilotIntentDatagramServer door, int seatId,
            NPeerHooks.FlyInResult flyIn, Map<Integer, SeatAnnounce> lastAnnouncedVariantBySeat,
            List<byte[]> roundRosterAppendFrames,
            List<RosterAppendFrameCodec.AppendRow> deferFanOrNull, boolean recordWrite,
            EqualRewriteLedger ledgerOrNull) throws IOException {
        // A realized-loadout VDEF the roster minted for this fly-in reaches
        // every live window BEFORE the RAPP row below names it (the one CTRL FIFO keeps the order; a no-op without a
        // mint). The fielded mark and the join-pick compare key on the STOCK id a realized mint stands for: the
        // listing and the requested pick name stock ids, so a mint is neither a lost mark nor a pick fallback.
        RealizedLoadoutLane.flushToDoor(door);
        if (flyIn.variantId() == null) {
            return false;
        }
        String fieldedStockId = RealizedLoadoutLane.goalOf(flyIn.variantId());
        door.recordSeatFieldedVariant(seatId, fieldedStockId);
        // The tracker value is the last announced ROW.
        SeatAnnounce lastAnnounce = lastAnnouncedVariantBySeat.get(seatId);
        String believed = lastAnnounce != null
                ? lastAnnounce.row().variantId() : flyIn.descriptorVariantId();
        // `believed` is what EVERY window believes (the
        // descriptor row / last announce) — the joiner's OWN window may instead have fielded
        // its join-window pick at t=0. A fresh admit's requested pick that differs from what
        // was fielded (rejected, canonicalized, or the default) announces even when
        // fielded == believed, so that window re-fields to the truth; the row is a no-op
        // duplicate for every other window. One-shot: taken at the seat's first fly-in.
        String requested = door.takeRequestedVariantPickOrNull(seatId);
        boolean requestDiffers = requested != null && !requested.isEmpty()
                && !requested.equals(fieldedStockId);
        if (flyIn.variantId().equals(believed) && !requestDiffers) {
            if (recordWrite) {
                // The gate suppressed the re-point, the
                // record write still owes its one row (no tracker state, no row print).
                emit(door, fieldedRow(flyIn, true), true, roundRosterAppendFrames, deferFanOrNull,
                        ledgerOrNull);
            }
            return false;
        }
        // Build the row ONCE, fan it, store it whole.
        RosterAppendFrameCodec.AppendRow announcedRow = fieldedRow(flyIn, recordWrite);
        emit(door, announcedRow, false, roundRosterAppendFrames, deferFanOrNull, ledgerOrNull);
        // The stored copy is FLAGLESS — the round-open
        // seed replays it for a vacant seat, and a re-sent row is never a second record write.
        lastAnnouncedVariantBySeat.put(seatId,
                new SeatAnnounce(announcedRow.withoutFleetWrite(), flyIn.descriptorVariantId()));
        System.out.println("npeer-serve seat_variant_repoint seat=" + seatId
                + " wire_id=" + flyIn.wireId() + " variant=" + flyIn.variantId()
                + " believed=" + believed
                + (requestDiffers ? " requested=" + requested + " (join pick fallback)" : "")
                + (deferFanOrNull != null ? " fan=post_mrst" : ""));
        if (requestDiffers) {
            // The seat asked for one hull and flies
            // another — a FALLBACK the debrief must list beside the client's own PICK FORK row.
            FallbackAnnounce.announceOnce("seat-pick-fallback-" + seatId, "seat=" + seatId
                    + " requested=" + requested + " fielded=" + flyIn.variantId()
                    + " (the client re-fields to fielded; a window still flying requested = a fork)");
        }
        return true;
    }

    /**
     * The fly-in's five-field RAPP row; {@code recordWrite}
     * flags it with the fly-in's round-open bit and fleet-key digest (the codec's one setter).
     */
    private static RosterAppendFrameCodec.AppendRow fieldedRow(NPeerHooks.FlyInResult flyIn,
            boolean recordWrite) {
        RosterAppendFrameCodec.AppendRow row = new RosterAppendFrameCodec.AppendRow(
                flyIn.wireId(), flyIn.side(), flyIn.variantId(), flyIn.shipName(),
                flyIn.crPerMille());
        return recordWrite ? row.withFleetWrite(flyIn.fleetRoundOpen(), flyIn.fleetKeyDigest()) : row;
    }

    /**
     * The live fan (null defer list) or the wrap's post-MRST deferral of one announce row. A
     * FLAGGED row of the live fan goes through {@code ledgerOrNull}; {@code gateSuppressed} = the
     * re-point gate kept silent and this is the record write's own row.
     * The ledger route.
     */
    private static void emit(PilotIntentDatagramServer door, RosterAppendFrameCodec.AppendRow row,
            boolean gateSuppressed, List<byte[]> roundRosterAppendFrames,
            List<RosterAppendFrameCodec.AppendRow> deferFanOrNull, EqualRewriteLedger ledgerOrNull)
            throws IOException {
        if (deferFanOrNull != null) {
            deferFanOrNull.add(row);
        } else if (ledgerOrNull != null && row.fleetFielded()) {
            ledgerOrNull.fanFlagged(door, row, gateSuppressed, roundRosterAppendFrames);
        } else {
            PersistentPilotIntentCombatServer.fanRosterAppendChunks(door, List.of(row), roundRosterAppendFrames);
        }
    }

    /**
     * The one live fan of a fleet-record REMOVAL row ({@code AppendRow.fleetVacatedRow}) onto the
     * round's log, through the ledger. Never deferred: no removal runs inside the wrap. Never stored:
     * the per-seat store holds fielding announces only, and a removal is no hull announce. THROWS
     * {@link IllegalArgumentException} on a row without the removal bit.
     *
     */
    static void announceFleetVacated(PilotIntentDatagramServer door, RosterAppendFrameCodec.AppendRow row,
            List<byte[]> roundRosterAppendFrames, EqualRewriteLedger ledger) throws IOException {
        Objects.requireNonNull(ledger, "ledger").fanVacated(door, row, roundRosterAppendFrames);
    }

    /**
     * The round log's EQUAL RE-WRITE ledger. An equal
     * re-write is a flagged, non-round-open row whose (side, goal) the client's fleet fold already
     * holds for its wire id, i.e. the (side, goal) of the wire id's newest flagged row on the same
     * log ({@code AllSkillsFleetLaw.FleetFold#foldFielded}'s own equality). Folding it changes no
     * record entry, spends no bound and moves no other wire id's key; its one lasting effect on a
     * replaying client is its own wire id's stamp, which the wire id's next ACCEPTED flagged row
     * overwrites. So once that row is on the log a late joiner's replay no longer needs the equal
     * re-write, and its slot is RETIRED: set to null, the index kept (no cursor shifts; the live fan
     * already sent it; a cursor past the slot is unaffected; a cursor before it skips it).
     *
     * <p>Never retired: a round-open row; the re-point gate's own row; any unflagged row; the newest
     * flagged row of each wire id; and the wire id's FIRST flagged row on the log, even when the gate
     * kept silent: it puts the entry in the record, and retiring it would fold every same-side row
     * between it and the wire id's next kept row over a record without that entry (a wrong key
     * stamp and a false MISMATCH on the joiner).
     *
     * <p>EXACT only while every row the ledger remembers is one the client accepted, so: a row whose
     * goal the law refuses is ignored whole (the client ignores it too); and a round-open row forgets
     * its wire id (a closed side refuses it) and supersedes nothing. No count of changing writes
     * bounds the client's fold, so nothing here counts them. The entries bound never binds: flagged
     * rows are seat fieldings, a seat's wire id is stable, and a match holds at most
     * {@code SEATS_MAX} (255) seats.
     *
     * <p>A fleet-record REMOVAL row passes through {@link #fanVacated}: it retires the wire id's
     * pending equal re-write and forgets the wire id, so a joiner's replay of a first fielding, a
     * retired equal re-write, the removal and a new tenure's fielding folds the entry present, gone,
     * present, as the serve held it. A removal row is never retired, never deferred and never
     * stored by the tracker.
     *
     *
     * <p>Every flagged row on a live log passes through {@link #fanFlagged}: the fielding announce is
     * the one site that sets the flag. A deferred wrap row is never seen here; it heads the NEXT
     * round's log, which the ledger meets with no entries (so a wire id's first live row there is
     * kept). Bound to ONE log by identity: meeting another log drops every entry first, so a slot
     * never names another round's log. Serve thread only, like every fan onto the log. Bounded by
     * the wire ids fielded on the current log.
     */
    static final class EqualRewriteLedger {
        /** A wire id's newest remembered flagged row: side, goal, and, when it is a retirable equal
         *  re-write, its log slot and that slot's frame (identity-checked at retirement); else -1 / null. */
        private record Entry(int side, String goalId, int slot, byte[] frame) {
        }

        private final Map<Long, Entry> byWire = new HashMap<>();
        private List<byte[]> log;

        /**
         * Fan one FLAGGED row live onto {@code log} (one 1-row frame), then retire the wire id's
         * pending equal re-write and remember this row (retirable when it is itself an equal
         * re-write the gate kept silent on).
         */
        void fanFlagged(PilotIntentDatagramServer door, RosterAppendFrameCodec.AppendRow row,
                boolean gateSuppressed, List<byte[]> log) throws IOException {
            bind(log);
            int slot = log.size();
            PersistentPilotIntentCombatServer.fanRosterAppendChunks(door, List.of(row), log);
            String goalId = RealizedLoadoutLane.goalOf(row.variantId());
            int side = row.side();
            if ((side != 0 && side != 1) || AllSkillsFleetLaw.goalRefusalOrNull(goalId) != null) {
                return;
            }
            Long wireId = row.wireId();
            if (row.fleetRoundOpen()) {
                byWire.remove(wireId);
                return;
            }
            Entry prior = byWire.get(wireId);
            boolean equal = prior != null && prior.side() == side && prior.goalId().equals(goalId);
            retire(prior, log);
            boolean retirable = equal && gateSuppressed && log.size() == slot + 1;
            byWire.put(wireId, new Entry(side, goalId, retirable ? slot : -1, retirable ? log.get(slot) : null));
        }

        /**
         * Fan one fleet-record REMOVAL row live onto {@code log} (one 1-row frame), retire the wire
         * id's pending equal re-write (the removal drops the stamp that re-write's one lasting effect
         * was), then FORGET the wire id: its next fielding is a FIRST row, kept, never retired (a
         * later equal row retiring it would fold every row after it over a record without the
         * entry). The removal row itself is never retired. THROWS {@link IllegalArgumentException}
         * on a row without the removal bit, before any change.
         *
         */
        void fanVacated(PilotIntentDatagramServer door, RosterAppendFrameCodec.AppendRow row,
                List<byte[]> log) throws IOException {
            if (!row.fleetVacated()) {
                throw new IllegalArgumentException("fanVacated: wire id " + row.wireId()
                        + " is not a fleet-record removal row");
            }
            bind(log);
            PersistentPilotIntentCombatServer.fanRosterAppendChunks(door, List.of(row), log);
            retire(byWire.remove(row.wireId()), log);
        }

        /** Drops every entry when {@code log} is not the bound log (a slot never names another round's log). */
        private void bind(List<byte[]> log) {
            if (this.log != log) {
                byWire.clear();
                this.log = log;
            }
        }

        /** Nulls {@code priorOrNull}'s retirable slot when that slot still holds the frame it remembered. */
        private static void retire(Entry priorOrNull, List<byte[]> log) {
            if (priorOrNull != null && priorOrNull.slot() >= 0 && priorOrNull.slot() < log.size()
                    && log.get(priorOrNull.slot()) == priorOrNull.frame()) {
                log.set(priorOrNull.slot(), null);
            }
        }
    }
}
