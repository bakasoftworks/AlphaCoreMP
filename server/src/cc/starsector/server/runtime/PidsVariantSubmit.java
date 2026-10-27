package cc.starsector.server.runtime;

import cc.starsector.server.ops.PeerText;
import cc.starsector.server.runtime.PilotIntentDatagramServer.PeerSession;
import cc.starsector.server.runtime.PilotIntentDatagramServer.VariantSubmissionLane;
import java.io.IOException;

/**
 * The custom-variant UP frame {@code VSUB} ({@link #onVariantSubmit}), carved VERBATIM out of
 * {@link PilotIntentDatagramServer} (a size carve:
 * the door file's size tier ahead of the hs-13 handshake work on the door, not a behaviour change). Its OWN class by
 * responsibility — a build submission is not session metadata, so it does not join {@link PidsMetaFrames}. Runs on
 * the READER thread (dispatched by the door's UP magic switch) and takes the door's own {@code lock} exactly as
 * before the carve.
 */
final class PidsVariantSubmit {
    private final PilotIntentDatagramServer door;

    PidsVariantSubmit(PilotIntentDatagramServer door) {
        this.door = door;
    }

    /**
     * W15 v0: one CTRL {@code VSUB} — decode (malformed = the family's
     * fail-closed posture via the caller's IOException catch ⇒ session kill), rate-gate
     * (POLICY: an over-rate submission is ANSWERED {@code custom_rate_limited} so the client's
     * 10 s re-submit timer never fires for it, the session survives), then the SEAT gate: an
     * observer (counted {@code observerCtrlDrops}, the seat-scoped kind) or an unbound-seat
     * sender (counted {@code variantSubmitsUnseatedDropped}, the chat precedent) is ANSWERED
     * {@code custom_unavailable} — a refusal is answered, never concealed
     * (before this the player waited forever on a silent drop), the
     * accounting unchanged, no new token, and the bucket ran FIRST so the answer is as bounded
     * as a pilot's. Then log the ingest row with the ONE peer-authored field quoted and hand
     * to the lane. The UP datagram itself is not sink-recorded; the submit frame + the author key ride
     * the drain's VSUB record row (kind 8) at its verdict; the VACK unicast is the wire-truth stamp.
     * Never fanned from here — the canonical VDEF fan is the sim thread's, AFTER registration.
     *
     */
    void onVariantSubmit(PeerSession session, byte[] payload) throws IOException {
        VariantSubmitFrameCodec.VariantSubmit submit = VariantSubmitFrameCodec.decodePayload(payload,
                door.requireIdTables());
        VariantDefinitionFrameCodec.VariantDefinition variant = submit.variant();
        int seatId;
        boolean overRate;
        boolean unseated;
        synchronized (door.lock) {
            // The rate law (refill + take) is CustomVariantLane.SubmitBucket.
            overRate = !session.variantSubmit.take(PilotIntentDatagramServer.nowMillis());
            unseated = session.observer || session.seatId < 0;
            if (overRate) {
                door.counters.variantSubmitsRateDropped++;
            } else {
                if (session.observer) {
                    door.readerCounters.observerCtrlDrops++;
                } else if (unseated) {
                    door.counters.variantSubmitsUnseatedDropped++;
                } else {
                    door.counters.variantSubmitsReceived++;
                }
            }
            seatId = session.seatId;
        }
        if (overRate) {
            door.sendVariantReceipt(session.sessionId, VariantReceiptFrameCodec.rejected(
                    submit.submitSeq(), DoorRejectReasons.CUSTOM_RATE_LIMITED,
                    variant.variantId()));
            return;
        }
        if (unseated) {
            // No seat to author a pick for — answered, not concealed; nothing minted.
            door.sendVariantReceipt(session.sessionId, VariantReceiptFrameCodec.rejected(
                    submit.submitSeq(), DoorRejectReasons.CUSTOM_UNAVAILABLE,
                    variant.variantId()));
            return;
        }
        // Ingest row: ids are printable-no-space by the codec law but CLIENT-chosen, the
        // display name is peer TEXT — every one of them rides quoted.
        System.out.println("pilot-datagram-server custom-variant vsub_recv seat=" + seatId
                + " session=" + session.sessionId
                + " seq=" + submit.submitSeq()
                + " local_id=" + PeerText.quote(variant.variantId())
                + " hull=" + PeerText.quote(variant.hullId())
                + " name=" + PeerText.quote(variant.displayName())
                + " bytes=" + payload.length);
        VariantSubmissionLane lane = door.variantSubmissionLane;
        VariantReceiptFrameCodec.VariantReceipt receipt;
        if (lane == null) {
            receipt = VariantReceiptFrameCodec.rejected(submit.submitSeq(),
                    DoorRejectReasons.CUSTOM_UNAVAILABLE, variant.variantId());
        } else {
            receipt = lane.onSubmit(session.sessionId, seatId, door.customAuthorKey(session), submit);
        }
        if (receipt != null) {
            door.sendVariantReceipt(session.sessionId, receipt);
            if (!receipt.accepted() && lane != null) {
                // D3: release a hold on this local id AFTER the VACK (never before the toast).
                lane.onRejectMailed(door.customAuthorKey(session), receipt.variantId(), receipt.reasonToken());
            }
        }
    }
}
