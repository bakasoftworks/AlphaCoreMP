package cc.starsector.server.runtime;

import cc.starsector.server.ops.PeerText;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * The door PROBE's reply ({@link #handleProbeLocked}) and its two per-source document budget terms
 * ({@link #matchDocHeldLocked}, {@link #lunaDocHeldLocked}), carved VERBATIM out of {@link PilotIntentDatagramServer}
 * (the door file's size tier, not a behaviour change). Its OWN class by
 * responsibility: a pre-claim, session-less answer to a password-proven probe. Every method here is called with the
 * door's {@code lock} already held (the door's admit branches; {@link PidsHello13}'s passed-key branch asks the two
 * budget terms), takes no lock of its own and writes no session, seat or identity state: its effects are the door's
 * per-source budgets, its reader counters, the reply datagrams and the door's stdout rows. The probe listing's ids
 * stay on the door ({@link PilotIntentDatagramServer#probeListingIds}): a contract row pins that method's source there.
 *
 */
final class PidsProbeReply {
    private final PilotIntentDatagramServer door;

    PidsProbeReply(PilotIntentDatagramServer door) {
        this.door = door;
    }

    /**
     * The door PROBE — the joiner GUI's
     * pre-launch {@code /ship} list fetch. Password-mode door ONLY (a token door answers
     * {@code probe_unsupported}: its credential is a per-seat secret, the wrong shape to gate
     * a shared listing). Runs the door's EXISTING pre-claim checks in their frozen order — the
     * fingerprint pre-check already passed in the caller, then the password trio — and,
     * BEFORE any ledger/authority claim, answers the listing RAW to the source and forgets
     * the sender: no session, no seat, no spool, no state but the {@link DoorSourceBudget} LRU
     * (keyed only AFTER the password passed). Reply = the in-session LSTD frames byte-for-byte
     * ({@code ListingEmitter.shipEntries} with no marks; seatId 0, listingId
     * {@link PilotIntentDatagramServer#PROBE_LISTING_ID}) on channel
     * CTRL with sessionId 0 and seq = frame index, CAPPED at {@link PilotIntentDatagramServer#PROBE_MAX_FRAMES}.
     * Failing checks take the existing REJECT WELCOME (≤~105 B reflector, unchanged) —
     * including a door with NO pickables ({@code probe_no_pickables}: reachable + password
     * OK + picking unavailable, the GUI's "server default only"). Budget denials are SILENT
     * drops (counted), never a reply. Lock held (the budget + counters live under it).
     * A probe whose {@code match=} term is stale on a door that serves a match document also asks the
     * document budget ({@link #matchDocHeldLocked}) and, once served, gets the sealed DESC frames
     * BEFORE the mod-set and ship listings.
     * Every proven probe on a door holding a LunaSettings sheet also asks the sheet's budget
     * ({@link #lunaDocHeldLocked}) and gets the sealed LUNA frames after any DESC frames, whatever its
     * match term; the mod-set listing rides every proven reply, its FIRST row the sheet's announce.
     *
     */
    void handleProbeLocked(HandshakeCodec.HelloPayload hello, InetSocketAddress from, PidsHello13.Hs13 hs13) {
        String reason;
        // Every door lists the curated mod scan under the 4-frame cap (the debug-door widening
        // + its 128-frame cap are DELETED).
        List<String> ids = door.probeListingIds();
        int frameCap = PilotIntentDatagramServer.PROBE_MAX_FRAMES;
        if (door.passwordLedger == null) {
            reason = DoorRejectReasons.PROBE_UNSUPPORTED;
        } else {
            reason = door.passwordRejectReasonOrNull(hello);
            if (reason == null && ids.isEmpty()) {
                reason = DoorRejectReasons.PROBE_NO_PICKABLES;
            }
        }
        // A wrong
        // password is a plain REJECT. The hold on every attempt — correct guesses included — is
        // LAWFUL because the source is cookie-verified (return-routable): the guess ledger ran in
        // handleHelloMultiLocked after the resend and before this compare (per key 250 ms → 8 s
        // backoff, ≈ 0.125 compares/s steady; global 16/s first-seen + 8/s suspect windows, a
        // ceiling of ≈ 74 compares/s over any number of keys; cliff: first-timers held during a
        // fresh-key flood), so a correct guess is never answered at line rate.
        if (reason != null) {
            door.sendRejectLocked(hello, from, reason, " (probe)", hs13);
            return;
        }
        if (!hs13.budgetAsked) {
            long now = PilotIntentDatagramServer.nowMillis();
            if (matchDocHeldLocked(hs13, from, now) || lunaDocHeldLocked(hs13, from, now)) {
                return;
            }
            if (!door.probeBudget.admit(from.toString(), now)) {
                door.readerCounters.probeDropped++;
                return;
            }
        }
        List<byte[]> frames;
        try {
            // The joiner default rides as the entry's NEXT_RESPAWN mark (the codec's
            // existing override mark — no schema bump; the join window reads it as `default`).
            // Each probe row carries the ship's display name for the
            // join window's ship list; a null pickable never reaches here, its empty ids answer PROBE_NO_PICKABLES.
            // The hull id rides the same rows' targetLabel: the join combo and the browser
            // list show and search it, and only the server knows a kept build's hull.
            // The kept copies list newest first; the base-first partition stands
            frames = ListingEmitter.encodeListing(0, ListingFrameCodec.KIND_SHIP,
                    PilotIntentDatagramServer.PROBE_LISTING_ID, ListingEmitter.shipEntries(ListingEmitter.keptNewestFirst(ids,
                            door.pickableVariants == null ? null : door.pickableVariants::keptAtMsOrNull), null,
                            door.variantPicks.joinerDefaultVariantOrNull(),
                            door.pickableVariants == null ? null : door.pickableVariants::displayNameOrNull,
                            door.pickableVariants == null ? null : door.pickableVariants::hullIdOrNull));
        } catch (RuntimeException codecLaw) {
            // Fail-closed, never fatal: a listing-law violation (the emitter THROWS by design)
            // must not kill the door's reader thread over a pre-admission nicety.
            door.readerCounters.probeDropped++;
            System.out.println("pilot-datagram-admission PROBE_ENCODE_FAILED src=" + from
                    + " " + codecLaw);
            return;
        }
        // The boot MOD SET rides the same reply FIRST
        // as its own KIND_MODSET listing (seq = its own frame index, same listingId; a pre-rung-2
        // kit ignores the kind) so the join page can name what the joiner lacks BEFORE a 60-s boot
        // bounces at the door. Sent before the ship frames: the collector declares the reply done
        // on the ship listing, so the set is on the wire by then.
        // The listing rides EVERY proven reply, a set-less door's too, its
        // FIRST row the LunaSettings announce (the sheet's digest and row count, or the no-sheet term), so a reader
        // told a sheet exists holds for its chunks instead of reading a lost chunk as "no sheet".
        String lunaTerm = door.lunaSheet == null ? LunaSheetCodec.NO_SHEET_TERM
                : door.lunaSheet.digest16() + ":" + door.lunaSheet.rows();
        List<byte[]> modFrames;
        int optionalCount = door.optionalModSet == null ? 0 : door.optionalModSet.size();
        try {
            // Required rows first, then the published OPTIONAL rows (mark 27; the door admits any lawful subset)
            modFrames = ListingEmitter.encodeListing(0, ListingFrameCodec.KIND_MODSET,
                    PilotIntentDatagramServer.PROBE_LISTING_ID, ListingEmitter.modSetEntriesWithAnnounce(lunaTerm, door.modSet, door.optionalModSet));
        } catch (RuntimeException codecLaw) {
            // the ship half still serves and the announce alone still rides (a lawful token + term); the door's
            // fold names the mod skew
            modFrames = ListingEmitter.encodeListing(0, ListingFrameCodec.KIND_MODSET,
                    PilotIntentDatagramServer.PROBE_LISTING_ID, ListingEmitter.modSetEntriesWithAnnounce(lunaTerm, null, null));
            door.readerCounters.probeModsetEncodeFailed++; //
            System.out.println("pilot-datagram-admission PROBE_MODSET_ENCODE_FAILED src=" + from
                    + " " + codecLaw);
        }
        int modSent = Math.min(modFrames.size(), PilotIntentDatagramServer.PROBE_MODSET_MAX_FRAMES);
        // THIS probe's password is proven and both budgets granted, so a
        // stale term draws the match document FIRST (seq = its own chunk index, sealed under this exchange's root;
        // the kit trial-opens its player and admin roots). The collector finishes on the ship listing, so the chunks
        // are on the wire before it; a probe presenting the served id gets none. Sent only after the listing encoded.
        int docSent = 0;
        if (hs13.matchStale && door.matchDoc != null) {
            byte[][] doc = door.matchDoc.reply(hs13.root);
            for (int i = 0; i < doc.length; i++) {
                door.sendRawDatagram(DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.CTRL,
                        door.roundId, 0L, i, 0L, 0, 0L, doc[i]), from);
            }
            docSent = doc.length;
            door.readerCounters.probeDocServed++;
        }
        // The sheet rides EVERY proven reply, never gated on the match term
        // (that gate would starve a returning player and every confirming re-probe), after DESC, before the listing.
        int lunaSent = 0;
        if (door.lunaSheet != null) {
            byte[][] luna = door.lunaSheet.reply(hs13.root);
            for (int i = 0; i < luna.length; i++) {
                door.sendRawDatagram(DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.CTRL,
                        door.roundId, 0L, i, 0L, 0, 0L, luna[i]), from);
            }
            lunaSent = luna.length;
            door.readerCounters.probeLunaServed++;
        }
        for (int i = 0; i < modSent; i++) {
            door.sendRawDatagram(DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.CTRL,
                    door.roundId, 0L, i, 0L, 0, 0L, modFrames.get(i)), from);
        }
        int sent = Math.min(frames.size(), frameCap);
        for (int i = 0; i < sent; i++) {
            door.sendRawDatagram(DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.CTRL,
                    door.roundId, 0L, i, 0L, 0, 0L, frames.get(i)), from);
        }
        door.readerCounters.helloProbed++;
        System.out.println("pilot-datagram-admission PROBE client=" + PeerText.quoteCid(hello.clientId())
                + " src=" + from + " frames=" + sent + " of=" + frames.size()
                + " pickable=" + ids.size()
                + " modset_frames=" + modSent + " of=" + modFrames.size()
                + " mods=" + (door.modSet == null ? 0 : door.modSet.size())
                + " optional=" + optionalCount
                + " doc_frames=" + docSent
                + " luna_frames=" + lunaSent);
    }

    /**
     * The match document's budget term, asked at most ONCE per probe, IMMEDIATELY before whichever
     * {@link PilotIntentDatagramServer#probeBudget} ask the probe reaches (the passed-key ask before the open, else the ask after it), and
     * only for a stale-term probe on a door that serves a document. True = DENIED: counted
     * {@code probe_doc_held} and the probe is dropped SILENTLY (nothing sent, the probe budget not asked, its
     * passed-key flag not set), so the kit's next try meets an open gap; never the listing alone, because the kit
     * finishes on the listing and would adopt "no document". The host's exempt addresses skip the per-key gap only.
     * Lock held.
     */
    boolean matchDocHeldLocked(PidsHello13.Hs13 hs13, InetSocketAddress from, long now) {
        if (door.matchDoc == null || !hs13.matchStale) {
            return false;
        }
        if (door.docBudget.admit(DoorSourceBudget.sourceKey(from.getAddress()), now,
                door.sourceSeatCap.exempt(from.getAddress()))) {
            return false;
        }
        door.readerCounters.probeDocHeld++;
        return true;
    }

    /**
     * The LunaSettings sheet's budget term: {@link #matchDocHeldLocked}'s shape WITHOUT its match-term gate, asked at
     * most ONCE per password-proven probe on a door holding a sheet, right after the document term and before the
     * {@link PilotIntentDatagramServer#probeBudget} ask. True = DENIED: counted {@code probe_luna_held} and the probe dropped SILENTLY (nothing
     * sent, nothing stamped beyond the document term's own grant), so the kit's next try meets an open gap; never the
     * listing without the sheet, which a kit told of a sheet would hold on. Lock held.
     *
     */
    boolean lunaDocHeldLocked(PidsHello13.Hs13 hs13, InetSocketAddress from, long now) {
        if (door.lunaSheet == null) {
            return false;
        }
        if (door.lunaDocBudget.admit(DoorSourceBudget.sourceKey(from.getAddress()), now,
                door.sourceSeatCap.exempt(from.getAddress()))) {
            return false;
        }
        door.readerCounters.probeLunaHeld++;
        return true;
    }
}
