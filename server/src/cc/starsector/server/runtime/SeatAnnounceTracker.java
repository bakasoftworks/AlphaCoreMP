package cc.starsector.server.runtime;

import cc.starsector.server.runtime.PersistentPilotIntentCombatServer.NPeerHooks;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The serve-lifetime seat-announce
 * tracker — the {@link SeatAnnounce} value, the round-open seed ({@link #addTrackerSeedRows}) and
 * the seat re-point announce ({@link #announceSeatVariantIfRepointed}, both overloads).
 *
 * <p>WHY: carved VERBATIM out of {@link PersistentPilotIntentCombatServer}
 * — PPICS sat over its
 * non-blank WarnNb and every seat-announce addition grew it. This class is the growth home for
 * seat-announce work, never PPICS. The RAPP
 * chunk+fan+record loop ({@code PersistentPilotIntentCombatServer.fanRosterAppendChunks}) stays
 * in PPICS (the wave lane's home); the announce calls it re-qualified.
 *
 * <p>Threads / locks / init order: stateless — three statics + a record, no static field; the
 * tracker map is caller-owned (the serve session's {@code lastAnnouncedVariantBySeat}), so the
 * move changes no thread, lock or init order.
 */
public final class SeatAnnounceTracker {

    private SeatAnnounceTracker() {
    }

    /**
     * One serve-lifetime announce-tracker value — the
     * RAPP row a seat's variant was last ANNOUNCED with (byte-for-byte what every window live at
     * that announce received) + the fly-in's descriptor variant id at that announce. Stored
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
     * returns how many it appended.
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
        if (flyIn.variantId() == null) {
            return false;
        }
        door.recordSeatFieldedVariant(seatId, flyIn.variantId());
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
                && !requested.equals(flyIn.variantId());
        if (flyIn.variantId().equals(believed) && !requestDiffers) {
            return false;
        }
        // Build the row ONCE, fan it, store it whole.
        RosterAppendFrameCodec.AppendRow announcedRow = new RosterAppendFrameCodec.AppendRow(
                flyIn.wireId(), flyIn.side(), flyIn.variantId(), flyIn.shipName(),
                flyIn.crPerMille());
        if (deferFanOrNull != null) {
            deferFanOrNull.add(announcedRow);
        } else {
            PersistentPilotIntentCombatServer.fanRosterAppendChunks(door, List.of(announcedRow), roundRosterAppendFrames);
        }
        lastAnnouncedVariantBySeat.put(seatId,
                new SeatAnnounce(announcedRow, flyIn.descriptorVariantId()));
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
}
