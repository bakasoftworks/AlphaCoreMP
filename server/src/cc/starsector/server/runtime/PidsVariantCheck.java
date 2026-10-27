package cc.starsector.server.runtime;

import cc.starsector.server.ops.PeerText;
import cc.starsector.server.runtime.PilotIntentDatagramServer.VariantSubmissionLane;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;

/**
 * The custom-variant PRE-ADMISSION dry-run on the {@code VCHK} envelope channel ({@link #handleVariantCheck}),
 * carved VERBATIM out of {@link PilotIntentDatagramServer}
 * (the door file's size tier ahead of the VCHK13 lane, not a behaviour change). Its OWN class by responsibility — a
 * session-less, credential-gated dry run is neither session metadata ({@link PidsMetaFrames}) nor a seated
 * submission ({@link PidsVariantSubmit}). Runs on the READER thread (dispatched by the reader loop's VCHK channel
 * branch, before any session lookup). Since THE FLIP the ONE served shape is the
 * {@link VariantCheckFrameCodec#MAGIC13} payload:
 * {@link #handleVariantCheck13} takes the door's {@code lock} twice and RELEASES it across the wrap open, and ends
 * in the policy tail {@link #answerLocked}. The version-12 check (the match password readable in the preamble) is
 * no longer decoded: every other payload is a counted silent drop.
 *
 */
final class PidsVariantCheck {
    private final PilotIntentDatagramServer door;

    PidsVariantCheck(PilotIntentDatagramServer door) {
        this.door = door;
    }

    /**
     * VCHK: the custom-variant
     * PRE-ADMISSION dry-run — the joiner window asks "would your policy take this local custom?"
     * before any claim. The PROBE's twin, only more so: the gated work here is a variable-length
     * body decode + a policy walk, so the sender proves the credential BEFORE one byte of the VSUB
     * tail is decoded (an unauthenticated decode path would be a CPU amplifier).
     * Reader thread. The payload magic is peeked first: {@link VariantCheckFrameCodec#MAGIC13} is the one served
     * shape ({@link #handleVariantCheck13}, whose javadoc holds the lock law and the door order); every other
     * payload — the version-12 check with its readable password included, THE FLIP — is a counted
     * silent drop, decoded by nothing. What a served check may and may not touch is unchanged from the version-12
     * law: the dry-run gate, the per-key gap, the answer budget, the VSUB decode,
     * the pure {@code VariantPolicy.check} — and NOTHING else of the lane (no ledger offer, no mint, no deposit, no
     * session, no seat, no cap slot, no persist, no fan); a passed, budget-open, well-formed check is ANSWERED
     * with the VACK shape on the VCHK channel ({@link #answerLocked}).
     */
    void handleVariantCheck(DatagramFrameCodec.Frame frame, SocketAddress source) {
        if (!(source instanceof InetSocketAddress from)) {
            door.readerCounters.variantChecksDropped++;
            return;
        }
        // R1, the magic peek (no password on the wire at 13)
        if (PilotIntentDatagramServer.payloadMagic(frame.payload()) != VariantCheckFrameCodec.MAGIC13) {
            door.readerCounters.variantChecksDropped++; // a foreign magic = the silent-drop posture (no session)
            return;
        }
        handleVariantCheck13(frame.payload(), from);
    }

    /**
     * VCHK13:
     * the same dry run with NO password on the wire — the sender proves it holds the match secret (or, for an
     * admin-class key, the admin secret) by sealing wraps the cookie bucket's share pair opens
     * ({@link PidsHello13#openVchkWraps}), and the VSUB frame rides sealed under the content key a wrap carries.
     * READER THREAD; takes the door's {@code lock} twice and RELEASES it across the open (no X25519 under the lock,
     * the {@link PidsHello13} law). Every failure is SILENCE, a counted drop: no step answers a failure, mints a
     * cookie, writes the HELLO guess ledger or raises a streak — the ONE ledger write is {@code revokeDryRun} (no
     * wrap opened, or an admin wrap from a key with no admin dry run: version-12 parity). Nothing the door itself
     * caused costs the sender its dry run: a stale cookie ({@code vchk13_stale}), a missing or erased pair
     * ({@code hs13_unserved}), a closed door, the per-key gap, the answer budget and a bad body after a good wrap
     * all leave it open. The answer budget is asked BEFORE the open (the open is the cost: ≤ 64 opens/s = ≤ 128
     * X25519/s, ≤ 10/s per passed key); the admin class is SNAPSHOT before the open so a kind-{@code a} wrap from a
     * match-class key skips the body trial (a right admin guess must cost no more than a wrong one); the
     * verdict uses the RE-READ dry-run state.
     *
     */
    private void handleVariantCheck13(byte[] payload, InetSocketAddress from) {
        PidsHello13 h13 = door.hello13;
        if (h13 == null || !h13.passwordDoor()) {
            door.readerCounters.variantChecksDropped++; // switch OFF (the unknown-magic posture) or a token door
            return;
        }
        VariantCheckFrameCodec.VariantCheck13 check = VariantCheckFrameCodec.decode13OrNull(payload);
        if (check == null) {
            door.readerCounters.variantChecksDropped++;
            return;
        }
        long now = PilotIntentDatagramServer.nowMillis();
        long bucket = door.doorCookie.bucketOfVerified(from, check.cookie(), now);
        if (bucket == DoorCookie.NO_BUCKET) {
            door.readerCounters.vchk13Stale++; // stale / forged / foreign: silence, never a COOKIE13 from this path
            return;
        }
        DoorShares13.Pair pair = h13.vchkPairOrNull(bucket);
        if (pair == null) {
            synchronized (door.lock) {
                door.readerCounters.hs13Unserved++;
            }
            return;
        }
        String key = DoorSourceBudget.sourceKey(from.getAddress());
        boolean adminSnapshot;
        synchronized (door.lock) {
            if (door.closed) {
                return;
            }
            if (!door.doorExpectations.acceptsPreAdmission(check.print())) {
                door.readerCounters.variantChecksDropped++;
                return;
            }
            if (!door.guessLedger.dryRunOpen(key)) {
                door.readerCounters.variantChecksUnpassed++;
                return;
            }
            if (!door.variantCheckBudget.admit(key, now)) {
                door.readerCounters.variantChecksHeld++;
                return;
            }
            if (!door.variantAnswerBudget.admit(key, now)) {
                door.readerCounters.variantChecksBudgeted++; // before the open: a denied check costs no X25519
                return;
            }
            adminSnapshot = door.guessLedger.dryRunAdmin(key);
        }
        DoorShares13.OpenWrapsResult opened =
                h13.openVchkWraps(pair, check.ya(), check.wrapM(), check.wrapA(), check.ada(), check.cookie());
        door.readerCounters.vchk13Opens++;
        Handshake13.DoorWraps wraps = opened.wrapsOrNull();
        boolean adminSkipped = false;
        byte[] vsub = null;
        if (wraps != null) {
            try {
                if (wraps.kind() == Handshake13.KIND_ADMIN && !adminSnapshot) {
                    adminSkipped = true;
                } else {
                    vsub = Handshake13.openBodyOrNull(wraps.ck(), check.seq(), check.bodyAad(), check.sealedBody());
                    door.readerCounters.vchk13Bodies++;
                }
            } finally {
                Arrays.fill(wraps.ck(), (byte) 0);
                Arrays.fill(wraps.root(), (byte) 0);
            }
        }
        synchronized (door.lock) {
            if (door.closed) {
                return;
            }
            if (opened.pairErased()) {
                door.readerCounters.hs13Unserved++; // the open proved nothing: silence, never a revoke
                return;
            }
            if (!door.guessLedger.dryRunOpen(key)) {
                door.readerCounters.variantChecksUnpassed++; // the dry run closed while the lock was released
                return;
            }
            if (wraps == null || (wraps.kind() == Handshake13.KIND_ADMIN && !door.guessLedger.dryRunAdmin(key))) {
                door.guessLedger.revokeDryRun(key);
                door.readerCounters.variantChecksRevoked++;
                return;
            }
            if (adminSkipped || vsub == null) {
                door.readerCounters.variantChecksDropped++; // a bad body after a good wrap: no revoke
                return;
            }
            answerLocked(vsub, check.nonce(), from, check.seq(), payload.length);
        }
    }

    /**
     * The policy tail, extracted verbatim from the version-12 handler, now gone; its steps:
     * the VSUB decode → the pure policy → the receipt → its encode → the quoted log row → the send
     * → {@code variant_checks}. Lock held; the caller already proved the credential and spent the answer budget.
     * {@code seq13} = the VCHK13 envelope seq (0..{@link Long#MAX_VALUE}): the VSUB {@code submitSeq} must equal
     * it, and a reply larger than its request is refused ({@code requestPayloadBytes13}; both frames carry the same
     * framing — the {@code PidsHello13} COOKIE13 floor), each a silent counted drop.
     *
     */
    private void answerLocked(byte[] tail, long nonce, InetSocketAddress from, long seq13, int requestPayloadBytes13) {
        VariantSubmitFrameCodec.VariantSubmit submit;
        try {
            if (PilotIntentDatagramServer.payloadMagic(tail) != VariantSubmitFrameCodec.MAGIC) {
                throw new IOException("variant-check tail is not a VSUB frame");
            }
            submit = VariantSubmitFrameCodec.decodePayload(tail, door.requireIdTables());
        } catch (IOException malformed) {
            door.readerCounters.variantChecksDropped++; // fail-closed decode = silent drop, never a kill (no session)
            return;
        }
        if (submit.submitSeq() != seq13) {
            door.readerCounters.variantChecksDropped++; // the sealed VSUB names another seq than its envelope (D3)
            return;
        }
        VariantDefinitionFrameCodec.VariantDefinition variant = submit.variant();
        VariantSubmissionLane lane = door.variantSubmissionLane;
        VariantReceiptFrameCodec.VariantReceipt receipt;
        VariantPolicy policy = lane == null ? null : lane.policyOrNull();
        if (policy == null) {
            receipt = VariantReceiptFrameCodec.rejected(submit.submitSeq(),
                    DoorRejectReasons.CUSTOM_UNAVAILABLE, variant.variantId());
        } else if (!lane.accepting()) {
            receipt = VariantReceiptFrameCodec.rejected(submit.submitSeq(),
                    DoorRejectReasons.CUSTOM_DISABLED, variant.variantId());
        } else {
            VariantPolicy.Refusal refusal;
            try {
                refusal = policy.check(variant);
            } catch (RuntimeException policyFault) {
                // Fail-closed, never fatal: a policy throw on content the codec bounded is a
                // server defect, not a wire event — counted + rowed, the reader thread lives.
                door.readerCounters.variantChecksDropped++;
                System.out.println("pilot-datagram-admission VCHK_CHECK_FAILED src=" + from
                        + " id=" + PeerText.quote(variant.variantId()) + " " + policyFault);
                return;
            }
            receipt = refusal == null
                    ? VariantReceiptFrameCodec.accepted(submit.submitSeq(), variant.variantId())
                    : VariantReceiptFrameCodec.rejected(submit.submitSeq(), refusal.token(),
                            variant.variantId());
        }
        byte[] vack;
        try {
            vack = VariantReceiptFrameCodec.encode(receipt);
        } catch (IOException | RuntimeException unencodable) {
            // The one handler exit that was not a counted drop. A receipt the
            // codec refuses is a server defect (reason token + id were codec-bounded on the way
            // in), never a wire event — counted + rowed, the reader thread lives, nothing thrown.
            door.readerCounters.variantChecksDropped++;
            System.out.println("pilot-datagram-admission VCHK_ENCODE_FAILED src=" + from
                    + " id=" + PeerText.quote(variant.variantId()) + " " + unencodable);
            return;
        }
        if (vack.length > requestPayloadBytes13) {
            door.readerCounters.variantChecksDropped++; // version 13's reply ≤ request belt (before the verdict row)
            return;
        }
        // Ids are printable-no-space by the codec law but CLIENT-chosen; the display name is
        // peer TEXT — quoted. Bounded by the budget above (≤ 64 rows/s).
        System.out.println("pilot-datagram-admission VCHK src=" + from
                + " seq=" + submit.submitSeq()
                + " id=" + PeerText.quote(variant.variantId())
                + " hull=" + PeerText.quote(variant.hullId())
                + " name=" + PeerText.quote(variant.displayName())
                + " verdict=" + (receipt.accepted() ? "POLICY_OK" : "REJECT")
                + " reason=" + (receipt.accepted() ? "-" : receipt.reasonToken()));
        door.sendRawDatagram(DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.VCHK, door.roundId,
                nonce, submit.submitSeq(), 0L, 0, 0L, vack), from);
        door.readerCounters.variantChecksAnswered++;
    }
}
