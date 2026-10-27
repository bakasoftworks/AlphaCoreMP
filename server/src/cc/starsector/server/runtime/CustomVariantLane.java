package cc.starsector.server.runtime;

import cc.starsector.server.ops.PeerText;
import cc.starsector.server.runtime.SubmittedVariantLedger.Offer;
import cc.starsector.server.runtime.SubmittedVariantLedger.Pending;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.VariantDefinition;
import cc.starsector.server.runtime.VariantReceiptFrameCodec.VariantReceipt;
import cc.starsector.server.runtime.VariantSubmitFrameCodec.VariantSubmit;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Custom variants, v0: THE {@link
 * PilotIntentDatagramServer.VariantSubmissionLane} — the reader-thread half ({@link #onSubmit}:
 * pure {@link VariantPolicy} → {@link SubmittedVariantLedger#offer} gate → immediate receipt or
 * deposit) and the sim-thread half ({@link #drainTick}: mint → {@link Registrar} engine pass +
 * registration → VDEF fan → VACK, in that order: a VDEF is registry-resolvable
 * before any RAPP row can name it, and the receipt follows the fan so a client that folds the
 * VDEF has the spec before it learns the id).
 *
 * <p>Seams are interfaces so the contract suite drives the lane with fakes: {@link Registrar}
 * (the bridge's sim-thread engine pass, {@code RealEngineVariantRegistrar}), {@link ReceiptMailer}
 * ({@code door::sendVariantReceipt}), {@link VariantFan} ({@code door.fanCtrlDown} of the
 * encoded VDEF; null-tolerant for the step-2 tree where the runtime catalog is not yet wired).
 * Peer text in every row rides {@link PeerText#quote}.
 */
public final class CustomVariantLane implements PilotIntentDatagramServer.VariantSubmissionLane,
        SeatVariantPicks.CustomPickSource {

    // ------------------------------------------------------------------ the door's VSUB law
    // Carved VERBATIM out of PilotIntentDatagramServer:
    // the rate + hostile-budget law is POLICY, so its constants, the hostile token
    // set and the per-session bucket live with the policy lane. The door keeps the transport
    // glue — decode, seat gate, ingest row, receipt send — and holds one SubmitBucket per
    // session. Static by design: the law binds every door, with or without a lane instance.

    /** VSUB rate law (policy, NOT protocol — an over-rate submission
     * is answered {@code custom_rate_limited}, counted, the session survives): per-session-KEY
     * token bucket (carried across a same-key takeover), burst {@code 8}, refill 1 token per 7.5 s (= 8 per 60 s). */
    public static final int VSUB_BUCKET_BURST = 8;
    public static final long VSUB_TOKEN_REFILL_MILLIS = 7_500L;
    /** VSUB hostile budget: a session whose submissions draw this many
     * HOSTILE-class refusals (policy / shape / engine / size — never rate, caps, disabled or
     * unavailable, which a well-behaved stale outbox can draw every round) is failed loud. */
    public static final int VSUB_HOSTILE_BUDGET = 64;

    /** Refusal tokens that draw down the {@link #VSUB_HOSTILE_BUDGET}: a
     * conforming client never sends a submission its own oracle already refuses, so these are
     * malice or a broken editor; rate / caps / disabled / unavailable are NOT hostile (a stale
     * outbox re-sent every round is well-behaved). */
    private static final java.util.Set<String> VSUB_HOSTILE_TOKENS = java.util.Set.of(
            DoorRejectReasons.CUSTOM_HULL_DENIED, DoorRejectReasons.CUSTOM_WEAPON_DENIED,
            DoorRejectReasons.CUSTOM_WING_DENIED, DoorRejectReasons.CUSTOM_HULLMOD_DENIED,
            DoorRejectReasons.CUSTOM_SHAPE_INVALID, DoorRejectReasons.CUSTOM_HULLMOD_INAPPLICABLE,
            DoorRejectReasons.CUSTOM_SMOD_CAP, DoorRejectReasons.CUSTOM_OP_EXCEEDED,
            DoorRejectReasons.CUSTOM_ENGINE_REJECTED, DoorRejectReasons.CUSTOM_TOO_LARGE);

    /** True iff a refusal with this token counts toward the hostile budget (pinned by rows). */
    public static boolean isHostileCustomRefusal(String reasonToken) {
        return VSUB_HOSTILE_TOKENS.contains(reasonToken);
    }

    /**
     * One session's VSUB accounting — the rate-law token bucket + hostile-refusal count,
     * carved verbatim out of the door's {@code PeerSession} (three fields + the inline refill
     * arithmetic of {@code onVariantSubmit} / {@code sendVariantReceipt}). Guarded by the DOOR
     * lock (the caller's monitor), like the chat bucket it mirrors; CARRIED across a same-key
     * takeover ({@link #copyFrom}) — the law is per admitted KEY, the chat bucket's RL-1 shape.
     */
    public static final class SubmitBucket {
        private double tokens = VSUB_BUCKET_BURST;
        private long refillMillis;
        private int hostileRefusals;

        /**
         * A same-key takeover's successor continues the
         * predecessor's accounting — before this every re-HELLO minted a full bucket AND a fresh
         * hostile budget, so the 8 per 60 s law was really "8 per admission". The elapsed refill
         * runs off the carried stamp (an honest reconnect after a gap is full again). The HOSTILE
         * count is NOT carried: a dead row is never swept on a timer,
         * so a carried count would last the key's whole server life, and an honest local custom the
         * server's tier refuses — replayed by the kit on every admission — would reach 64 and then
         * loop through a 60 s kick for ever. The budget stays per session; the kick itself is the
         * rate limit on it (one failed session per 64 hostile refusals).
         */
        public void copyFrom(SubmitBucket predecessor) {
            this.tokens = predecessor.tokens;
            this.refillMillis = predecessor.refillMillis;
        }

        /** Refill by the time elapsed since the previous call, then take one token: true = the
         * submission is inside the rate law (one token taken); false = over-rate, nothing taken.
         * The first call refills nothing (no prior stamp) — the bucket starts full. */
        public boolean take(long nowMillis) {
            if (refillMillis != 0L) {
                long elapsed = nowMillis - refillMillis;
                if (elapsed > 0L) {
                    tokens = Math.min((double) VSUB_BUCKET_BURST,
                            tokens + elapsed / (double) VSUB_TOKEN_REFILL_MILLIS);
                }
            }
            refillMillis = nowMillis;
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }

        /** Count one hostile-class refusal: true exactly when the count reaches
         * {@link #VSUB_HOSTILE_BUDGET} (the caller fails the session loud, machine token
         * {@code custom_hostile_budget}). */
        public boolean drawHostile() {
            hostileRefusals++;
            return hostileRefusals >= VSUB_HOSTILE_BUDGET;
        }

        /** Hostile-class refusals drawn so far (rows). */
        public int hostileRefusals() {
            return hostileRefusals;
        }
    }

    /** Told after every ACCEPT receipt (drain) or dup-answer (reader) — the
     * pick surface seeds any pick held for that author (seed after VACK).
     * {@code sessionId}/{@code seatId} = the SUBMITTER (the seat the door
     * bound the VSUB to) so an in-match {@code /submit} with no held pick still becomes that
     * seat's next respawn — a FRESH accept only (the drain); the dup-answer passes seat -1 (holds
     * only), because a dup may be the client's re-admit replay of its last VSUB.
     * A fresh accept of a KEEP-ONLY submit
     * ({@link VariantDefinitionFrameCodec#FLAG_KEEP_ONLY}) also passes seat -1: the host kept the build for
     * everyone and asked that their own next respawn stay as it was. */
    public interface AcceptListener {
        void onAccepted(String authorKey, String canonicalId, String localId, long sessionId, int seatId);
    }

    /** Told after every REJECT receipt — reader thread (operator /
     * policy / ledger gate, operator {@code custom-reject}) or sim thread (engine pass) — so the
     * pick surface drops a HELLO/{@code /ship} hold on that local id at once instead of at the
     * 30 s deadline. Called AFTER the receipt is mailed (the accept twin's order law). */
    public interface RejectListener {
        void onRejected(String authorKey, String localId, String reasonToken);
    }

    /** Told after an operator {@code custom-ban} / {@code custom-reject}
     * newly BANNED these canonical ids (sim thread, the operator drain) — the door drops every seat's
     * seeded next-respawn override naming one of them, so a banned custom stops respawning NOW
     * instead of at round wrap. Called AFTER the ledger ban + the log row (the accept/reject twins'
     * order law); never fired for a refused ban (unknown / already banned). Returns the number of
     * standing picks the listener cleared — the receipt names it so the operator sees the ban bite. */
    public interface BanListener {
        int onBanned(List<String> canonicalIds);
    }

    /** The sim-thread engine pass + registration for ONE minted definition. */
    public interface Registrar {
        /** null token = registered; else the {@link DoorRejectReasons#custom()} token + log detail. */
        Verdict register(VariantDefinition canonical);

        /** The same pass with an OVERWRITE put — the catalog lane's live
         * UPDATE of an id THIS serve registered (never a boot id: the lane gates that first). Default =
         * the plain pass (fixtures). */
        default Verdict register(VariantDefinition canonical, boolean overwrite) {
            return register(canonical);
        }
    }

    /** A registrar verdict: {@code reasonToken == null} means ACCEPTED; {@code unusedOp} = the
     * engine pass's OP headroom on an accept ({@code -1} = not reported), an ops-row datum only. */
    public record Verdict(String reasonToken, String detail, int unusedOp) {
        public static Verdict accepted() {
            return new Verdict(null, "", -1);
        }

        /** Step 3(f): an accept carrying the engine pass's {@code getUnusedOP} reading. */
        public static Verdict accepted(int unusedOp) {
            return new Verdict(null, "", unusedOp);
        }

        public static Verdict rejected(String token, String detail) {
            if (!DoorRejectReasons.custom().contains(Objects.requireNonNull(token, "token"))) {
                throw new IllegalArgumentException("not a custom reject token: " + token);
            }
            return new Verdict(token, detail == null ? "" : detail, -1);
        }

        public boolean isAccepted() {
            return reasonToken == null;
        }
    }

    /** {@code door::sendVariantReceipt} — thread-agnostic by the door's contract. */
    public interface ReceiptMailer {
        void send(long sessionId, VariantReceipt receipt);
    }

    /** Fan ONE encoded VDEF frame to every live session (the door's CTRL fan). */
    public interface VariantFan {
        void fanVariantDefinition(byte[] vdefFrame);

        /**
         * Fan ONE multi-body unit (a
         * station's module frames first, its parent LAST) to every live session, in list order. WHY
         * (security scan row 5): a station row must reach every newcomer whole — a session that sees
         * the parent before one of its modules refuses the parent (the kit's module-resolve law). This
         * default fans frame by frame, so every fake and the custom / promotion fans keep today's
         * behavior; the wave lane's door fan ({@code WaveLoadoutInflater.installOrNull}) overrides it
         * to read the live-session set ONCE per body.
         */
        default void fanVariantDefinitions(java.util.List<byte[]> vdefFrames) {
            for (byte[] f : vdefFrames) {
                fanVariantDefinition(f);
            }
        }
    }

    private final VariantPolicy policy;
    private final SubmittedVariantLedger ledger;
    private final Registrar registrar;
    private final ReceiptMailer mailer;
    private final VariantFan fanOrNull;
    private volatile AcceptListener acceptListenerOrNull;
    private volatile RejectListener rejectListenerOrNull; //
    private volatile BanListener banListenerOrNull; //
    /** The catalog promotion lane's sim-thread step, run first in {@link #drainTick}. */
    private volatile Runnable tickHookOrNull;
    /** Step 3(e): the off-thread writer (null = persistence off); set at install, fed by the drain. */
    private volatile CustomVariantPersistSink persistOrNull;
    /** Step 3(f) {@code custom-export <id>} inbox; the wiring sets the resolved
     * {@code custom.exportDir} — a fixture lane keeps the compiled default. */
    private volatile java.io.File exportDir = new java.io.File(CustomVariantConfig.EXPORT_DIR_DEFAULT);
    /** Step 3(f) {@code custom-submissions on|off}: the door's accept gate (reader thread reads). */
    private volatile boolean accepting = true;
    /** Step 3(f) {@code custom-reject <seat>}: author keys refused for the rest of the serve. */
    private final java.util.Set<String> blockedAuthors = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private int policyRefusals;
    /** Reader thread only: VSUBs refused by the operator gate (off / blocked author). */
    private int operatorRefusals;
    private int registered;
    /** Sim thread only: {@code custom-export} verbs that never reached the writer
     * (unknown id / persist off / writer full) — the caller half of {@code custom.export_failed}. */
    private int exportRefusals;
    /** The kept store's two dirs + the catalog lane that lists it
     *  (install-time, {@link #setKeptSources}); null dirs = derived from {@link #exportDir} (a fixture lane). */
    private volatile java.nio.file.Path keptDirOrNull;
    private volatile java.nio.file.Path inboxDirOrNull;
    private volatile CatalogPromotionLane promoOrNull;
    /** Sim thread only: keep / keep-export / forget verbs (and a ban naming a kept stem) refused before the
     *  writer — the caller half of {@code custom.kept_failed} (never {@link #operatorRefusals}: that one is
     *  the VSUB gate's share of {@code custom.rejected}). */
    private int keptRefusals;
    /** The host ship verbs' final-verdict book (install-time; null = none,
     *  the queued line is then the caller's only answer). */
    private volatile HostReceiptBook receiptBookOrNull;

    public CustomVariantLane(VariantPolicy policy, SubmittedVariantLedger ledger, Registrar registrar,
            ReceiptMailer mailer, VariantFan fanOrNull) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.registrar = Objects.requireNonNull(registrar, "registrar");
        this.mailer = Objects.requireNonNull(mailer, "mailer");
        this.fanOrNull = fanOrNull;
    }

    public SubmittedVariantLedger ledger() {
        return ledger;
    }

    /** Peer-build sharing v2: the door's share verbs + the fielding auto-share reach the ledger
     * through this (the door never holds the lane class; the smoke's dry-run lane keeps null). */
    @Override
    public SubmittedVariantLedger ledgerOrNull() {
        return ledger;
    }

    public VariantPolicy policy() {
        return policy;
    }

    /** W15 v3 VCHK: the door's pre-admission dry-run runs THIS pure policy on
     * the reader thread — the boot snapshot, immutable; nothing else of the lane is touched. */
    @Override
    public VariantPolicy policyOrNull() {
        return policy;
    }

    /** Install (or clear) the accept listener; thread-agnostic. */
    public void setAcceptListener(AcceptListener listenerOrNull) {
        acceptListenerOrNull = listenerOrNull;
    }

    /** The hook {@link #drainTick} runs first on the sim thread (the registry's one writer). */
    public void setTickHook(Runnable hookOrNull) {
        tickHookOrNull = hookOrNull;
    }

    /**
     * W15 v0 step 3(b): the RUNTIME VDEF catalog — every accepted custom re-encoded, mint order
     * — the door spools it to each newcomer after the boot catalog. Read-time encode (≤ 64
     * frames per admission; admissions are rare); a decoded VDEF always re-encodes (codec
     * round-trip law), so a throw here is a codec bug, never a serve condition.
     */
    public List<byte[]> acceptedVariantFrames() {
        List<SubmittedVariantLedger.Accepted> snapshot = ledger.acceptedSnapshot();
        List<byte[]> frames = new java.util.ArrayList<>(snapshot.size());
        for (SubmittedVariantLedger.Accepted a : snapshot) {
            try {
                frames.add(VariantDefinitionFrameCodec.encode(a.canonical(), ledger.tables()));
            } catch (IOException unencodable) {
                throw new IllegalStateException("VDEF encode failed for accepted " + a.canonicalId(), unencodable);
            }
        }
        return frames;
    }

    // ------------------------------------------------------------------ SeatVariantPicks.CustomPickSource

    @Override
    public String ownCanonicalOrNull(String authorKey, String raw) {
        return ledger.ownCanonicalOrNull(authorKey, raw);
    }

    @Override
    public boolean ownPending(String authorKey, String raw) {
        return ledger.ownPendingLocalId(authorKey, raw);
    }

    @Override
    public List<String> ownCanonicalIds(String authorKey) {
        return ledger.ownAcceptedIds(authorKey);
    }

    private void notifyAccepted(String authorKey, String canonicalId, String localId,
            long sessionId, int seatId) {
        AcceptListener l = acceptListenerOrNull;
        if (l != null) {
            l.onAccepted(authorKey, canonicalId, localId, sessionId, seatId);
        }
    }

    /** Install (or clear) the reject listener; thread-agnostic. */
    public void setRejectListener(RejectListener listenerOrNull) {
        rejectListenerOrNull = listenerOrNull;
    }

    /** One REJECT receipt for {@code authorKey}'s local id: release any hold on it (D3). */
    private void notifyRejected(String authorKey, String localId, String reasonToken) {
        RejectListener l = rejectListenerOrNull;
        if (l != null) {
            l.onRejected(authorKey == null ? "" : authorKey, localId, reasonToken);
        }
    }

    /** Install (or clear) the ban listener; thread-agnostic. */
    public void setBanListener(BanListener listenerOrNull) {
        banListenerOrNull = listenerOrNull;
    }

    /** Newly banned canonical ids → the listener; returns the standing picks it cleared (0 = none
     * installed or none named). Fired OUTSIDE the ledger monitor (the ban call has returned). */
    private int notifyBanned(List<String> canonicalIds) {
        BanListener l = banListenerOrNull;
        if (l == null || canonicalIds.isEmpty()) {
            return 0;
        }
        return l.onBanned(canonicalIds);
    }

    /** The door mailed a reader-thread REJECT we returned from {@link #onSubmit} (D3). */
    @Override
    public void onRejectMailed(String authorKey, String localId, String reasonToken) {
        notifyRejected(authorKey, localId, reasonToken);
    }

    // ------------------------------------------------------------------ reader thread

    @Override
    public VariantReceipt onSubmit(long sessionId, int seatId, String authorKey, VariantSubmit submit) {
        VariantDefinition v = submit.variant();
        // Step 3(f): the operator gate answers BEFORE the policy — `custom-submissions off` and a
        // `custom-reject`ed seat both refuse custom_disabled at once (the client stops re-sending).
        boolean blocked = authorKey != null && blockedAuthors.contains(authorKey);
        if (!accepting || blocked) {
            operatorRefusals++;
            System.out.println("custom-variant operator_refused seat=" + seatId + " session=" + sessionId
                    + " seq=" + submit.submitSeq() + " local_id=" + PeerText.quote(v.variantId())
                    + " reason=" + DoorRejectReasons.CUSTOM_DISABLED
                    + " cause=" + (blocked ? "seat_rejected" : "submissions_off"));
            return VariantReceiptFrameCodec.rejected(submit.submitSeq(), DoorRejectReasons.CUSTOM_DISABLED,
                    v.variantId());
        }
        VariantPolicy.Refusal refusal = policy.check(v);
        if (refusal != null) {
            policyRefusals++;
            System.out.println("custom-variant policy_refused seat=" + seatId + " session=" + sessionId
                    + " seq=" + submit.submitSeq() + " local_id=" + PeerText.quote(v.variantId())
                    + " reason=" + refusal.token() + " detail=" + PeerText.quote(refusal.detail()));
            return VariantReceiptFrameCodec.rejected(submit.submitSeq(), refusal.token(), v.variantId());
        }
        byte[] frame = canonicalSubmitFrame(sessionId, seatId, submit);
        Offer offer = ledger.offer(sessionId, seatId, authorKey == null ? "" : authorKey, submit, frame);
        switch (offer.kind()) {
            case DUPLICATE:
                System.out.println("custom-variant duplicate seat=" + seatId + " session=" + sessionId
                        + " seq=" + submit.submitSeq() + " id=" + PeerText.quote(offer.canonicalId()));
                // A re-join's HELLO pick may be held on this local id — the dup IS its accept.
                // Seat -1 = holds ONLY, never the submitter seed — a dup is as often
                // the client's re-admit replay of its LAST VSUB (PredictReconcileDatagramSession
                // replayAttachFramesAfterAdopt) or an editor double-click as a fresh intent, and a
                // replay must not move a standing /ship pick. A byte-identical re-submit therefore
                // re-picks nothing; /ship <id> does.
                notifyAccepted(authorKey == null ? "" : authorKey, offer.canonicalId(), v.variantId(),
                        sessionId, -1);
                return VariantReceiptFrameCodec.accepted(submit.submitSeq(), offer.canonicalId());
            case PENDING_DUPLICATE:
                return null;
            case REFUSED:
                System.out.println("custom-variant gate_refused seat=" + seatId + " session=" + sessionId
                        + " seq=" + submit.submitSeq() + " reason=" + offer.reasonToken()
                        + " accepted=" + ledger.acceptedCount() + " pending=" + ledger.pendingCount());
                return VariantReceiptFrameCodec.rejected(submit.submitSeq(), offer.reasonToken(), v.variantId());
            case DEPOSITED:
            default:
                return null;
        }
    }

    /** Reader thread only: submit frames the canonical re-encode refused (the deposit then carries none). */
    private int submitFrameEncodeFailed;

    /**
     * The submit frame the drain's VSUB record row carries, re-encoded canonically under the ledger's
     * tables. It runs HERE, on the reader thread, so the sim-thread drain never encodes: nothing rides
     * the frame loop without earning it, and the drain already spends its frame on the engine pass. A
     * refusal deposits an EMPTY frame (the row is then written without a body and a replay refuses to
     * re-admit it), counts {@link #submitFrameEncodeFailed} and prints ONE numbers-only row: the codec's
     * message quotes the player-chosen local id, so neither it nor the id reaches the log.
     *
     */
    private byte[] canonicalSubmitFrame(long sessionId, int seatId, VariantSubmit submit) {
        try {
            return VariantSubmitFrameCodec.encode(submit, ledger.tables());
        } catch (IOException | RuntimeException e) {
            submitFrameEncodeFailed++;
            System.out.println("custom-variant frame_encode_failed seat=" + seatId + " session=" + sessionId
                    + " seq=" + submit.submitSeq() + " cause=" + e.getClass().getSimpleName());
            return new byte[0];
        }
    }

    public int submitFrameEncodeFailed() {
        return submitFrameEncodeFailed;
    }

    // ------------------------------------------------------------------ replay (the re-sim's deposit)

    /** Sim thread, replay only: recorded VSUB rows whose frame refused {@code decodePayload} under this
     *  JVM's id tables (a tables drift the CRC cannot see, a tampered reel, codec drift). */
    private int vsubDecodeRefused;

    /**
     * Re-admit one recorded VSUB row — the re-sim's stand-in for the reader thread: decode the recorded
     * submit frame under the ledger's tables and deposit it for the next {@link #drainTick}, which mints
     * and registers exactly as live did. Returns the frame's own submitSeq (the caller compares it with
     * the row's; a difference is its counted mismatch). SKIPS the operator gate, the policy and the ledger
     * gate on purpose: all three answered on the reader thread live, and a row is written only for a
     * deposit that reached a drain, so the recorded row IS the proof they passed; re-taking the ledger
     * gate at the drain would judge from a different ledger state
     * ({@link SubmittedVariantLedger#depositReplayed}). Reader-thread outcomes that wrote NO row (an
     * operator / policy / gate refusal, a DUPLICATE's hold) stay unreplayed: they move only pick holds,
     * and the replay forces the fly-in from its recorded row, so the sim never sees them. A decode
     * refusal rethrows NUMBERS ONLY with no cause (the codec's messages quote player-chosen ids) and
     * counts {@link #vsubDecodeRefused}.
     */
    public int replayOffer(long sessionId, int seat, String authorKey, byte[] frame) throws IOException {
        byte[] f = frame == null ? new byte[0] : frame;
        VariantSubmit decoded;
        try {
            decoded = VariantSubmitFrameCodec.decodePayload(f, ledger.tables());
        } catch (IOException | RuntimeException refused) {
            vsubDecodeRefused++;
            throw new IOException("reel VSUB row session=" + sessionId + " seat=" + seat + " body=" + f.length
                    + " B: decode refused");
        }
        long crc = SubmittedVariantLedger.bodyCrc(decoded.variant(), ledger.tables());
        ledger.depositReplayed(new Pending(sessionId, seat, authorKey == null ? "" : authorKey,
                decoded.submitSeq(), decoded.variant(), crc, f));
        return decoded.submitSeq();
    }

    public int vsubDecodeRefused() {
        return vsubDecodeRefused;
    }

    // ------------------------------------------------------------------ sim thread

    /**
     * Drain ≤ {@link SubmittedVariantLedger#DRAIN_PER_TICK} deposits on the SIM thread: mint →
     * registrar → (fan) → receipt. A registrar THROW is a fail-closed {@code custom_engine_rejected}
     * with the row naming the cause — never a serve abort (the fly-in's spawn-retry is the last net).
     * Returns the number drained.
     */
    public int drainTick() {
        return drainTick(-1, -1L, MatchRecordRows.BAND_POST, null);
    }

    /**
     * {@link #drainTick()} that also hands ONE applied-input
     * {@code VSUB} row per drained submission to {@code sinkOrNull} (null = unrecorded), written the
     * moment its verdict is known — before the register / fan / receipt side effects — keyed by the
     * serve's {@code round}, the container {@code tick} (-1 at the round-start gate) and {@code band}.
     * The row carries the deposit's author key and the submit frame {@link #onSubmit} encoded on the
     * reader thread, so this drain writes the body without encoding it.
     */
    public int drainTick(int round, long tick, byte band, MatchRecordRows.RowSink sinkOrNull) {
        Runnable hook = tickHookOrNull;
        if (hook != null) {
            hook.run(); // CatalogPromotionLane.tick: ≤ 1 engine pass, its own fault net
        }
        List<Pending> batch = ledger.drain(SubmittedVariantLedger.DRAIN_PER_TICK);
        for (Pending p : batch) {
            String canonicalId = ledger.mint(p);
            VariantDefinition canonical = SubmittedVariantLedger.withId(p.submitted(), canonicalId);
            Verdict verdict;
            try {
                verdict = registrar.register(canonical);
                if (verdict == null) {
                    // A registrar contract violation is a SERVER fault (non-hostile).
                    verdict = Verdict.rejected(DoorRejectReasons.CUSTOM_ENGINE_FAULT, "registrar returned null");
                }
            } catch (RuntimeException | Error engineFailure) {
                // The registrar answers content itself; anything that
                // ESCAPES it is ours (an Error, a bridge bug) — custom_engine_fault, never the hostile token.
                verdict = Verdict.rejected(DoorRejectReasons.CUSTOM_ENGINE_FAULT,
                        engineFailure.getClass().getSimpleName() + ": " + engineFailure.getMessage());
            }
            if (sinkOrNull != null) {
                sinkOrNull.row(MatchRecordRows.KIND_VSUB, MatchRecordRows.encodeVsub(new MatchRecordRows.VsubRow(
                        p.sessionId(), p.seatId(), p.submitSeq(), canonicalId, p.bodyCrc(), verdict.isAccepted(),
                        verdict.isAccepted() ? "" : verdict.reasonToken(), p.authorKey(), p.submitFrame()),
                        band, round), tick);
            }
            if (verdict.isAccepted()) {
                ledger.recordAccepted(p, canonicalId, canonical, verdict.unusedOp());
                registered++;
                CustomVariantPersistSink sink = persistOrNull;
                if (sink != null) {
                    sink.offer(ledger.acceptedOrNull(canonicalId)); // format + enqueue only (3(e))
                }
                // The SUBMIT's keep-only bit (the minted copy never
                // carries it) decides the seed below; the audit row names it only when set.
                boolean keepOnly = p.submitted().keepOnly();
                System.out.println("custom-variant registered seat=" + p.seatId() + " session=" + p.sessionId()
                        + " seq=" + p.submitSeq() + " id=" + PeerText.quote(canonicalId)
                        + " hull=" + PeerText.quote(canonical.hullId())
                        + " name=" + PeerText.quote(canonical.displayName())
                        + " local_id=" + PeerText.quote(p.submitted().variantId())
                        + " accepted=" + ledger.acceptedCount() + (keepOnly ? " keep_only=1" : ""));
                if (fanOrNull != null) {
                    try {
                        fanOrNull.fanVariantDefinition(VariantDefinitionFrameCodec.encode(canonical, ledger.tables()));
                    } catch (IOException unencodable) {
                        throw new IllegalStateException("VDEF encode failed for a registered custom", unencodable);
                    }
                }
                mailer.send(p.sessionId(), VariantReceiptFrameCodec.accepted(p.submitSeq(), canonicalId));
                // The VACK above answers a keep-only submit exactly as
                // any accept (the minted id); only the seed changes - seat -1 = holds ONLY, the dup path's rule,
                // so the submitter's next respawn and its "next respawn" line are left untouched.
                notifyAccepted(p.authorKey(), canonicalId, p.submitted().variantId(),
                        p.sessionId(), keepOnly ? -1 : p.seatId()); // seed AFTER the VACK
            } else {
                ledger.recordRejected(p, verdict.reasonToken());
                System.out.println("custom-variant engine_refused seat=" + p.seatId() + " session=" + p.sessionId()
                        + " seq=" + p.submitSeq() + " minted=" + PeerText.quote(canonicalId)
                        + " reason=" + verdict.reasonToken() + " detail=" + PeerText.quote(verdict.detail()));
                FallbackAnnounce.announceOnce("custom-variant-" + verdict.reasonToken(),
                        "custom variant refused by the engine pass: " + verdict.reasonToken());
                mailer.send(p.sessionId(), VariantReceiptFrameCodec.rejected(p.submitSeq(),
                        verdict.reasonToken(), p.submitted().variantId()));
                notifyRejected(p.authorKey(), p.submitted().variantId(), verdict.reasonToken()); // D3, after the VACK
            }
        }
        return batch.size();
    }

    public int policyRefusals() {
        return policyRefusals;
    }

    public int operatorRefusals() {
        return operatorRefusals;
    }

    /** {@code custom-export} verbs refused before the writer (see {@link #export}). */
    public int exportRefusals() {
        return exportRefusals;
    }

    public int registeredCount() {
        return registered;
    }

    // ------------------------------------------------------------------ step 3(e) persistence

    /** Install the off-thread writer (null = off); the drain feeds every accept from now on. */
    public void setPersistSink(CustomVariantPersistSink sinkOrNull) {
        persistOrNull = sinkOrNull;
    }

    public CustomVariantPersistSink persistSinkOrNull() {
        return persistOrNull;
    }

    /** Serve end (the sim thread, after the last tick): catch-up feed of the ledger snapshot
     * (idempotent — the writer dedupes by id, so a queue-full drop still lands) then close. */
    public void closePersist() {
        CustomVariantPersistSink sink = persistOrNull;
        if (sink == null) {
            return;
        }
        persistOrNull = null;
        int fed = sink.offerAll(ledger.acceptedSnapshot());
        System.out.println("custom-variant persist_catch_up fed=" + fed + " accepted=" + ledger.acceptedCount());
        sink.close();
    }

    // ------------------------------------------------------------------ step 3(f) operator verbs (sim thread)

    /** {@code custom-submissions on|off}: the door's accept gate; a live OFF refuses every later VSUB
     * {@code custom_disabled} (the lane stays installed — customs already accepted keep flying). */
    public void setAccepting(boolean on) {
        accepting = on;
        System.out.println("custom-variant submissions state=" + (on ? "on" : "off")
                + " accepted=" + ledger.acceptedCount() + " pending=" + ledger.pendingCount());
    }

    public boolean accepting() {
        return accepting;
    }

    /**
     * {@code custom-reject <seat>}: drop the seat's un-drained deposits (each mailed a REJECT
     * {@code custom_disabled} receipt so the client's re-submit timer stops), BAN every custom the
     * seat's author already has (unpickable/unlisted; registry entry stays — v0 has no eviction)
     * and refuse the author for the rest of the serve. {@code authorKeyOrNull} = the seat's live
     * session author key ({@code PilotIntentDatagramServer#customAuthorKey}: the cid in password mode,
     * {@code seat#<n>} in token mode) when the caller knows it (a seat with no ledger trace is still blocked).
     * Returns the one-line receipt.
     */
    public String rejectSeat(int seatId, String authorKeyOrNull) {
        String author = authorKeyOrNull != null ? authorKeyOrNull : ledger.authorKeyForSeatOrNull(seatId);
        List<Pending> dropped = ledger.dropPendingForSeat(seatId);
        for (Pending p : dropped) {
            mailer.send(p.sessionId(), VariantReceiptFrameCodec.rejected(p.submitSeq(),
                    DoorRejectReasons.CUSTOM_DISABLED, p.submitted().variantId()));
            notifyRejected(p.authorKey(), p.submitted().variantId(), DoorRejectReasons.CUSTOM_DISABLED); // D3
        }
        List<String> bannedIds = author == null ? List.of() : ledger.banAllByAuthor(author);
        if (author != null) {
            blockedAuthors.add(author);
        }
        // In password mode the author key IS the seat's full client id and the
        // player stays seated — print its log form (PeerText.cidForLog), here and in operator_allow below.
        System.out.println("custom-variant operator_reject seat=" + seatId
                + " author=" + (author == null ? "none" : PeerText.quoteCid(author))
                + " dropped=" + dropped.size() + " banned=" + bannedIds.size()
                + (bannedIds.isEmpty() ? "" : " ids=" + String.join(",", bannedIds)));
        // The banned ids may already be seeded as next-respawn picks — clear them NOW.
        int cleared = notifyBanned(bannedIds);
        return "custom-reject seat " + seatId + ": dropped " + dropped.size() + " pending, banned "
                + bannedIds.size() + " accepted, " + (author == null ? "no author to block" : "author blocked")
                + (cleared == 0 ? "" : ", " + cleared + " standing pick" + (cleared == 1 ? "" : "s") + " cleared");
    }

    /** {@code custom-ban <id>}: one canonical id → unpickable/unlisted (registry entry stays).
     * A {@code kept-} stem is refused BEFORE the ledger (a kept ship
     * leaves {@code /ship} through Forget kept, never a ban), and a ban of an id whose kept copy this run
     * wrote names that copy (it stays listed for everyone until it is forgotten). */
    public String ban(String canonicalId) {
        if (canonicalId != null && canonicalId.startsWith(CustomVariantPersistSink.KEPT_STEM_PREFIX)) {
            keptRefusals++;
            System.out.println("custom-variant operator_ban_refused id=" + PeerText.quote(canonicalId)
                    + " reason=kept_ship");
            return "custom-ban " + canonicalId + ": a kept ship - Forget kept takes it off /ship";
        }
        SubmittedVariantLedger.Accepted a = ledger.ban(canonicalId);
        if (a == null) {
            boolean known = ledger.acceptedOrNull(canonicalId) != null;
            System.out.println("custom-variant operator_ban_refused id=" + PeerText.quote(canonicalId)
                    + " reason=" + (known ? "already_banned" : "unknown_id"));
            return "custom-ban " + canonicalId + ": " + (known ? "already banned" : "unknown id");
        }
        System.out.println("custom-variant operator_ban id=" + PeerText.quote(canonicalId) + " seat=" + a.seatId()
                + " hull=" + PeerText.quote(a.canonical().hullId()) + " evict=deferred banned=" + ledger.bannedCount());
        // A seat may already hold this id as its seeded next-respawn pick — clear it NOW.
        int cleared = notifyBanned(List.of(canonicalId));
        CustomVariantPersistSink sink = persistOrNull;
        String keptStem = sink == null ? null : sink.keptStemOf(canonicalId);
        return "custom-ban " + canonicalId + ": banned (unpickable from now; registry entry kept"
                + (cleared == 0 ? "" : "; " + cleared + " standing pick" + (cleared == 1 ? "" : "s") + " cleared") + ")"
                + (keptStem == null ? "" : "; its kept copy " + keptStem + " stays on /ship - Forget kept removes it");
    }

    /**
     * {@code custom-allow <seat>}: the inverse of {@link #rejectSeat} — lift the
     * author block and un-ban EVERY banned custom of the seat's author (the operator lifts the
     * seat's sanctions whole; one id kept banned = a following {@code custom-ban}). Each un-banned
     * id is re-fanned as VDEF: a joiner admitted during the ban never received it (the runtime
     * catalog spools accepted-not-banned only), and every other client's fold is an overwrite put
     * (idempotent). Nothing to un-clear: the ban cleared a seeded pick, the seat re-picks itself.
     */
    public String allowSeat(int seatId, String authorKeyOrNull) {
        String author = authorKeyOrNull != null ? authorKeyOrNull : ledger.authorKeyForSeatOrNull(seatId);
        boolean unblocked = author != null && blockedAuthors.remove(author);
        List<String> unbanned = author == null ? List.of() : ledger.unbanAllByAuthor(author);
        for (String id : unbanned) {
            refanUnbanned(id);
        }
        System.out.println("custom-variant operator_allow seat=" + seatId
                + " author=" + (author == null ? "none" : PeerText.quoteCid(author))
                + " unblocked=" + unblocked + " unbanned=" + unbanned.size()
                + (unbanned.isEmpty() ? "" : " ids=" + String.join(",", unbanned)));
        return "custom-allow seat " + seatId + ": " + (author == null ? "no author to allow"
                : unblocked ? "author unblocked" : "author was not blocked")
                + ", unbanned " + unbanned.size() + " accepted";
    }

    /** {@code custom-unban <id>}: the inverse of {@link #ban} — pickable/listed/
     * spooled again from now on, re-fanned to the live seats (see {@link #allowSeat}). */
    public String unban(String canonicalId) {
        SubmittedVariantLedger.Accepted a = ledger.unban(canonicalId);
        if (a == null) {
            boolean known = ledger.acceptedOrNull(canonicalId) != null;
            System.out.println("custom-variant operator_unban_refused id=" + PeerText.quote(canonicalId)
                    + " reason=" + (known ? "not_banned" : "unknown_id"));
            return "custom-unban " + canonicalId + ": " + (known ? "not banned" : "unknown id");
        }
        refanUnbanned(canonicalId);
        System.out.println("custom-variant operator_unban id=" + PeerText.quote(canonicalId) + " seat=" + a.seatId()
                + " hull=" + PeerText.quote(a.canonical().hullId()) + " banned=" + ledger.bannedCount());
        return "custom-unban " + canonicalId + ": unbanned (pickable again; re-fanned to live seats)";
    }

    private void refanUnbanned(String canonicalId) {
        SubmittedVariantLedger.Accepted a = ledger.acceptedOrNull(canonicalId);
        if (fanOrNull == null || a == null) {
            return;
        }
        try {
            fanOrNull.fanVariantDefinition(VariantDefinitionFrameCodec.encode(a.canonical(), ledger.tables()));
        } catch (IOException unencodable) {
            throw new IllegalStateException("VDEF encode failed for an un-banned custom", unencodable);
        }
    }

    /** Install-time: the resolved {@code custom.exportDir} ({@link #export(String)} targets it). */
    public void setExportDir(java.io.File dir) {
        exportDir = Objects.requireNonNull(dir, "exportDir");
    }

    public java.io.File exportDir() {
        return exportDir;
    }

    /** {@code custom-export <id>} into the configured inbox ({@link #setExportDir}). */
    public String export(String canonicalId) {
        return export(canonicalId, exportDir);
    }

    /** {@code custom-export <id>}: queue the inbox copy on the persist writer (off-thread). */
    public String export(String canonicalId, java.io.File inboxDir) {
        SubmittedVariantLedger.Accepted a = ledger.acceptedOrNull(canonicalId);
        CustomVariantPersistSink sink = persistOrNull;
        String reason = a == null ? "unknown_id" : sink == null ? "persist_off" : null;
        if (reason != null) {
            exportRefusals++;
            System.out.println("custom-variant operator_export_refused id=" + PeerText.quote(canonicalId)
                    + " reason=" + reason);
            return "custom-export " + canonicalId + ": " + reason.replace('_', ' ');
        }
        boolean queued = sink.offerExport(a, inboxDir);
        if (!queued) {
            exportRefusals++;
        }
        System.out.println("custom-variant operator_export id=" + PeerText.quote(canonicalId)
                + " inbox=" + inboxDir + " queued=" + queued);
        return "custom-export " + canonicalId + ": " + (queued ? "queued to " + inboxDir : "dropped (writer full)");
    }

    /** {@code custom.exported}: inbox copies the writer really landed (0 with persist off). */
    public long exportsWritten() {
        CustomVariantPersistSink sink = persistOrNull;
        return sink == null ? 0L : sink.exportsWritten();
    }

    /** {@code custom.export_failed}: refused-before-the-writer + failed-at-the-writer. */
    public long exportsFailed() {
        CustomVariantPersistSink sink = persistOrNull;
        return exportRefusals + (sink == null ? 0L : sink.exportsFailed());
    }

    // ------------------------------------------------------------------ kept ships (sim thread)

    /** install-time — {@code keptDir} = {@code <inbox>/kept} (the catalog
     *  lane's KEPT source), {@code inboxDir} = the resolved {@code custom.exportDir}, {@code promoOrNull} = the
     *  lane that lists the kept store (its counts gate the verbs; null = the three kept verbs refuse
     *  {@code kept_off}). */
    public void setKeptSources(java.nio.file.Path keptDir, java.nio.file.Path inboxDir,
            CatalogPromotionLane promoOrNull) {
        this.keptDirOrNull = Objects.requireNonNull(keptDir, "keptDir");
        this.inboxDirOrNull = Objects.requireNonNull(inboxDir, "inboxDir");
        this.promoOrNull = promoOrNull;
    }

    /** The catalog lane listing the kept store (status rows read its counts), null = none installed. */
    public CatalogPromotionLane promoOrNull() {
        return promoOrNull;
    }

    /** install-time — the book the host ship verbs record their callers in
     *  (the catalog lane settles it). */
    public void setReceiptBook(HostReceiptBook bookOrNull) {
        this.receiptBookOrNull = bookOrNull;
    }

    public HostReceiptBook receiptBookOrNull() {
        return receiptBookOrNull;
    }

    /** One kept verb's enqueue verdict — its receipt line, and whether the
     *  writer took the job (only a taken job can earn a final verdict later). */
    public record KeptEnqueue(String line, boolean queued) {
    }

    private java.nio.file.Path inboxDir() {
        java.nio.file.Path inbox = inboxDirOrNull;
        return inbox != null ? inbox : exportDir.toPath().toAbsolutePath();
    }

    private java.nio.file.Path keptDir() {
        java.nio.file.Path kept = keptDirOrNull;
        return kept != null ? kept : inboxDir().resolve(CatalogPromotionLane.KEPT_DIR_NAME);
    }

    /**
     * {@code custom-keep <id>} — keep THIS run's accepted custom for
     * every player. This verb never looks at a file: an exported file is kept with
     * {@link #keepFromExport}. Sim thread: LOOK UP + ENQUEUE only; the writer's verdict lands on the
     * customs status line ({@code custom.kept_last}), never here.
     */
    public String keep(String canonicalId) {
        return keep(canonicalId, "custom-keep " + canonicalId + ": ", false).line();
    }

    /**
     * The keep body under a caller's receipt {@code head} — the console's
     * {@code custom-keep} keeps its head and sentences; the host's {@code /shipkeep} passes its own head and
     * {@code hostWording} (the short phrases a chat line and the editor's card read). Same gate, same enqueue.
     */
    public KeptEnqueue keep(String canonicalId, String head, boolean hostWording) {
        String refusal = keptGateRefusalOrNull();
        if (refusal == null && ledger.isBanned(canonicalId)) {
            refusal = "banned";
        }
        SubmittedVariantLedger.Accepted a = refusal == null ? ledger.acceptedOrNull(canonicalId) : null;
        if (refusal == null && a == null) {
            refusal = "unknown_id";
        }
        if (refusal != null) {
            return new KeptEnqueue(keptRefused("operator_keep_refused", head, canonicalId, refusal, hostWording), false);
        }
        CustomVariantPersistSink sink = persistOrNull;
        boolean queued = sink.offerKeep(a, keptDir());
        System.out.println("custom-variant operator_keep id=" + PeerText.quote(canonicalId) + " queued=" + queued);
        return queued ? new KeptEnqueue(head + (hostWording ? "saving" : "saving - the customs line shows the result"), true)
                : new KeptEnqueue(keptRefused("operator_keep_refused", head, canonicalId,
                        sink.retired() ? "persist_off" : "writer_busy", hostWording), false);
    }

    /** {@code custom-keep-export <name>}: keep an inbox file — an earlier run's export ({@code cc-v…}) or a
     *  forgotten kept file ({@code kept-…}); the WRITER reads it under the kept-read law
     *  and re-checks it against this serve's policy. Sim thread: LOOK UP + ENQUEUE only. */
    public String keepFromExport(String name) {
        return keepFromExport(name, "custom-keep-export " + name + ": ", false).line();
    }

    /** The keep-export body under a caller's receipt head (the
     *  {@link #keep(String, String, boolean)} split; the host's put-back passes its head + {@code hostWording}). */
    public KeptEnqueue keepFromExport(String name, String head, boolean hostWording) {
        String refusal = keptGateRefusalOrNull();
        boolean ccv = name != null && name.startsWith(SubmittedVariantLedger.ID_PREFIX);
        boolean keptName = name != null && name.startsWith(CustomVariantPersistSink.KEPT_STEM_PREFIX);
        if (refusal == null && (!(ccv || keptName) || !CatalogPromotionLane.stemLawful(name))) {
            refusal = "bad_name";
        }
        if (refusal == null && ccv && ledger.isBanned(name)) {
            refusal = "banned";
        }
        if (refusal != null) {
            return new KeptEnqueue(keptRefused("operator_keep_export_refused", head, name, refusal, hostWording), false);
        }
        CustomVariantPersistSink sink = persistOrNull;
        boolean queued = sink.offerKeepFromExport(name, inboxDir(), keptDir());
        System.out.println("custom-variant operator_keep_export name=" + PeerText.quote(name) + " queued=" + queued);
        return queued ? new KeptEnqueue(head + (hostWording ? "restoring" : "saving - the customs line shows the result"), true)
                : new KeptEnqueue(keptRefused("operator_keep_export_refused", head, name,
                        sink.retired() ? "persist_off" : "writer_busy", hostWording), false);
    }

    /** {@code custom-forget <stem>}: take one kept ship off {@code /ship} — its files MOVE back to the inbox
     *  (never deleted). Sim thread: LOOK UP + ENQUEUE only; the writer re-checks the disk. */
    public String forget(String stem) {
        return forget(stem, "custom-forget " + stem + ": ", false).line();
    }

    /** The forget body under a caller's receipt head (the
     *  {@link #keep(String, String, boolean)} split; the host's removal passes its head + {@code hostWording}).
     *  {@code stem} must be the CANONICAL kept spelling: the listing check compares exactly. */
    public KeptEnqueue forget(String stem, String head, boolean hostWording) {
        CatalogPromotionLane promo = promoOrNull;
        String refusal = keptOffRefusalOrNull(promo);
        if (refusal == null && (!CatalogPromotionLane.stemLawful(stem)
                || (promo != null && !promo.keptIds().contains(stem)))) {
            refusal = "not_kept";
        }
        if (refusal != null) {
            return new KeptEnqueue(keptRefused("operator_forget_refused", head, stem, refusal, hostWording), false);
        }
        CustomVariantPersistSink sink = persistOrNull;
        boolean queued = sink.offerForget(stem, keptDir(), inboxDir());
        System.out.println("custom-variant operator_forget stem=" + PeerText.quote(stem) + " queued=" + queued);
        return queued ? new KeptEnqueue(head + (hostWording ? "removing" : "forgetting - the customs line shows the result"), true)
                : new KeptEnqueue(keptRefused("operator_forget_refused", head, stem,
                        sink.retired() ? "persist_off" : "writer_busy", hostWording), false);
    }

    /** {@code custom.kept_failed}: kept verbs refused before the writer + kept jobs the writer refused / failed. */
    public long keptFailed() {
        CustomVariantPersistSink sink = persistOrNull;
        return keptRefusals + (sink == null ? 0L : sink.keptFailed());
    }

    private String persistRefusalOrNull() {
        CustomVariantPersistSink sink = persistOrNull;
        return sink == null || sink.retired() ? "persist_off" : null;
    }

    /** The writer gate + {@link #keptOffRefusalOrNull} + the kept list's fullness: {@code keptListed} (on
     *  {@code /ship}) OR the stems the last scan read (≤ the cap; refused ones included) — the scan reads only
     *  the first {@link CatalogPromotionLane#KEPT_CAP}, so a 33rd file would never list. Forget skips the
     *  fullness check (it is how a full list makes room) and takes {@link #keptOffRefusalOrNull} alone. */
    private String keptGateRefusalOrNull() {
        CatalogPromotionLane promo = promoOrNull;
        String refusal = keptOffRefusalOrNull(promo);
        if (refusal == null && promo != null && (promo.keptListed() >= CatalogPromotionLane.KEPT_CAP
                || promo.keptIds().size() >= CatalogPromotionLane.KEPT_CAP)) {
            refusal = "kept_full";
        }
        return refusal;
    }

    /**
     * The gate all three kept verbs share — {@code persist_off},
     * then {@code kept_off} when no catalog lane lists the kept store ({@code promo} null = the boot row's
     * {@code kept_verbs=off}): a Keep would land a file nothing lists until a restart under a false "on /ship
     * for every player", and a Forget could not check the listing. {@code promo} = the caller's one read of
     * {@link #promoOrNull}.
     */
    private String keptOffRefusalOrNull(CatalogPromotionLane promo) {
        String refusal = persistRefusalOrNull();
        if (refusal == null && promo == null) {
            refusal = "kept_off";
        }
        return refusal;
    }

    /** One refusal: ONE row with a bounded token, counted ({@link #keptRefusals}), the authored sentence — or, for a
     *  host ship verb ({@code hostWording}), its short phrase ({@link CustomVariantOps#hostRefusalPhrase}). */
    private String keptRefused(String row, String head, String idOrStem, String token, boolean hostWording) {
        keptRefusals++;
        System.out.println("custom-variant " + row + " id=" + PeerText.quote(String.valueOf(idOrStem)) + " reason=" + token);
        if (hostWording) {
            return head + CustomVariantOps.hostRefusalPhrase(token);
        }
        return head + switch (token) {
            case "persist_off" -> "the server cannot save files - make the server folder writable, then restart";
            case "kept_full" -> "the kept list is full (" + CatalogPromotionLane.KEPT_CAP + ") - forget one first";
            case "kept_off" -> "the kept list is not available on this server";
            case "banned" -> "that ship is banned - unban it first";
            case "unknown_id" -> "no custom ship has that id on this run - an exported file is kept with custom-keep-export";
            case "bad_name" -> "only a custom ship's cc-v... id or a kept- file can be kept";
            case "not_kept" -> "not a kept ship";
            default -> "the file writer is busy - press again";
        };
    }
}
