package cc.starsector.server.runtime;

import cc.starsector.server.runtime.PilotIntentDatagramServer.PeerSession;
import java.io.IOException;

/**
 * The three session-metadata UP frames — {@code PSTE} posture echo
 * ({@link #onPostureEcho}), {@code SNNK} awsd nack ({@link #onAwsdNack}), {@code MARK} owner mark
 * ({@link #onOwnerMark}) — carved VERBATIM out of {@link PilotIntentDatagramServer} (the
 * pre-step: PIDS sat at its WarnNb before the LEDGER-STATE-1 fix). WHY a separate class: the door file's size tier,
 * not a behaviour change. The SNAM posture holds for all three — session metadata, never sink-recorded. Every method
 * runs on the READER thread (dispatched by the door's UP magic switch) and takes the door's own {@code lock} exactly
 * as before the carve.
 */
final class PidsMetaFrames {
    private final PilotIntentDatagramServer door;

    PidsMetaFrames(PilotIntentDatagramServer door) {
        this.door = door;
    }

    /** The compiled EXPECTED client posture — a pure function of the shared
     * {@link ClientPostureRegistry} census (both jars compile the same fingerprint-locked class,
     * so this is deterministic across the pair; the hash pair is contract-printed and pinned as
     * the client-golden). Verdicts key on
     * {@code behaviorHash}; the full hash is informational drift (INSTRUMENT arms, e.g. the kit
     * logs checkbox). Deliberately ignores THIS JVM's sysprops/env ({@code baseline()} resolves
     * from empty inputs — client knobs reaching a serve JVM as unread JTO sysprops must not
     * skew the expectation). */
    private static final ClientPostureRegistry.Posture CLIENT_POSTURE_BASELINE =
            ClientPostureRegistry.baseline();

    /** One {@code PSTE}
     * posture echo — the client's resolved-posture digests, sent once at attach. Verdict =
     * behavior-hash vs {@link #CLIENT_POSTURE_BASELINE}, LOUD stdout row either way (v1 is
     * DETECTION, not suppression — admission-refusal stays a later decision; a mismatch
     * says "this client is locally re-postured", which on internal rigs is expected truth).
     * Corrupt echo = the codec throws {@link IOException} = failSession LOUD (the post-admit
     * CTRL law). NOT sink-recorded (the SNAM posture: session metadata, not gameplay wire
     * truth). A duplicate echo (client re-attach on the same server session) re-logs at most once per
     * {@link PilotIntentDatagramServer#MARK_MIN_INTERVAL_MILLIS} (the rest count {@code posture_rate_drop=}). */
    void onPostureEcho(PeerSession session, byte[] payload) throws IOException {
        PostureFrameCodec.PostureEcho echo = PostureFrameCodec.decodeEcho(payload);
        synchronized (door.lock) { // RESOURCE-LOGSINK-1 — the MARK rate idiom on
            long now = PilotIntentDatagramServer.nowMillis(); // the stdout row: the first echo prints, later ones at most once per window
            if (session.lastPostureEchoLogMillis != 0L
                    && now - session.lastPostureEchoLogMillis < PilotIntentDatagramServer.MARK_MIN_INTERVAL_MILLIS) {
                door.counters.postureEchoRateDropped++;
                return;
            }
            session.lastPostureEchoLogMillis = now;
        }
        ClientPostureRegistry.Posture expected = CLIENT_POSTURE_BASELINE;
        boolean behaviorMatch =
                java.util.Arrays.equals(echo.behaviorHash(), expected.behaviorHash());
        boolean fullMatch = java.util.Arrays.equals(echo.fullHash(), expected.hash());
        System.out.println("pilot-datagram-server posture_"
                + (behaviorMatch ? "match" : "mismatch")
                + " session=" + session.sessionId()
                + " rows=" + echo.rowCount() + " unknown=" + echo.unknownCount()
                + " behavior=" + ClientPostureRegistry.hex(echo.behaviorHash())
                + " full=" + ClientPostureRegistry.hex(echo.fullHash())
                + " fullDrift=" + (behaviorMatch && !fullMatch)
                + (behaviorMatch ? ""
                        : " expectedBehavior=" + ClientPostureRegistry.hex(expected.behaviorHash())
                                + " expectedFull=" + ClientPostureRegistry.hex(expected.hash())));
    }

    /** An AWSD delta-reject NACK — deposit the entityId for the serve
     * loop's pre-compose drain ({@link PeerSession#takeAwsdNacksOrEmpty()}); the composer
     * answers with an out-of-turn shared keyframe. Coalesced by entityId; the carried epoch is
     * ADVISORY (re-key is unconditional, so it is not stored). ADJUDICATED: NACKs do NOT
     * charge {@code livenessDeclarationBudget} — they are loss-correlated, not
     * intent-interleaved, so a loss burst during an intent stall must not read
     * hostile-or-dead; the pending-set cap is the flood bound. Not sink-recorded (the SNAM
     * posture: session metadata, not gameplay wire truth). */
    void onAwsdNack(PeerSession session, byte[] payload) throws IOException {
        AwsdNackFrameCodec.AwsdNack nack = AwsdNackFrameCodec.decodeDatagram(payload); //
        synchronized (door.lock) {
            door.counters.awsdNacksReceived++;
            long entityId = nack.entityId();
            if (session.pendingAwsdNacks.contains(entityId)
                    || session.pendingAwsdNacks.size() < PilotIntentDatagramServer.MAX_PENDING_AWSD_NACKS) {
                session.pendingAwsdNacks.add(entityId);
            } else {
                door.counters.awsdNacksDropped++;
            }
        }
    }

    /** One F9 owner-mark capture — decode
     * fail-closed (malformed = the family's posture via the caller's IOException catch),
     * rate-gate per seat (POLICY: an inside-window mark drops + counts, the session survives —
     * the client lane mirrors the window and never hits it), then ONE stdout row on the serve
     * log: {@code owner-marker seat=<n> #<k> clientTick=<t> serverTick=<T>}. No text, no fan,
     * no sim-thread work, not sink-recorded (the SNAM/SNNK posture: session metadata). An
     * unbound seat (null-authority smoke door) still prints with {@code seat=-1} — the row is
     * a forensics anchor, not attribution. */
    void onOwnerMark(PeerSession session, byte[] payload) throws IOException {
        MarkFrameCodec.OwnerMark mark = MarkFrameCodec.decodeDatagram(payload); //
        int seatId;
        synchronized (door.lock) {
            long now = PilotIntentDatagramServer.nowMillis();
            if (session.lastMarkAcceptMillis != 0L
                    && now - session.lastMarkAcceptMillis < PilotIntentDatagramServer.MARK_MIN_INTERVAL_MILLIS) {
                door.counters.ownerMarksRateDropped++;
                return;
            }
            session.lastMarkAcceptMillis = now;
            door.counters.ownerMarksReceived++;
            seatId = session.seatId;
        }
        // epochMs suffix = P6 debrief pre-work (sync-campaign-tooling.md): nowMillis() is
        // nanoTime-monotonic, so the cross-artifact wall-clock join needs this explicit stamp;
        // suffix-positioned so the graders' content-anchored 'owner-marker seat=' patterns hold.
        System.out.println("pilot-datagram-server owner-marker seat=" + seatId
                + " #" + mark.count() + " clientTick=" + mark.clientTick()
                + " serverTick=" + door.approxSimTick
                + " epochMs=" + System.currentTimeMillis());
    }
}
