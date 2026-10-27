package cc.starsector.server.runtime;

import cc.starsector.server.ops.PeerText;
import cc.starsector.server.runtime.PilotIntentDatagramServer.PeerSession;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The reliable-CTRL DOWN fan-out, carved out of {@link PilotIntentDatagramServer}: the ONE shared
 * DOWN-fan skeleton {@link #fanCtrlDown} (unified
 * 2026-07-17 after 5 verbatim twins) plus its 12 per-kind fans, moved with their
 * bodies and javadoc verbatim. WHY here: the PIDS carve plan item (3) (the
 * deferred-refactors {@code PilotIntentDatagramServer} growth WATCH-ITEM row) — PIDS sat at 4872
 * nb, 33 under its 4905 tier; the fans need only a session snapshot, the CTRL spool, the door
 * lock, the sink and the counter holder, so they move behind 7 injected collaborators and PIDS
 * keeps one-line public delegates (zero caller edits).
 *
 * <p>Thread-posture law (byte-identical to the pre-carve PIDS code): spool the same encoded bytes
 * to every live session OUTSIDE the lock (lock-order rule) → bump the caller's per-kind counter
 * UNDER the PIDS door lock → mirror ONE un-stamped {@code KIND_DOWN} sink record (the sink read
 * per call through the PIDS-owned volatile). Reader-thread callers (chat, seat names) and
 * serve-loop callers (hulk/roster/module/transfer/aim fans) are both fine because the CTRL spool
 * is strict per-session FIFO — enqueue order == delivery order regardless of caller.
 *
 *
 */
final class CtrlFanout {

    private final Supplier<List<PeerSession>> liveSessions;
    private final BiConsumer<PeerSession, byte[]> ctrlSpoolOrFail;
    private final Object lock;
    private final Supplier<WireRecordSink> sink;
    private final PidsDoorCounters counters; // guarded by lock
    /** Nullable exactly as in PIDS (legacy doors / non-fortress serves). */
    private final SeatRebindArbiter moduleSeats;
    /** Nullable exactly as in PIDS (unarmed/smoke doors). */
    private final String requiredDoorFingerprint;
    /** The serve's admission cursor, read per seat-name fan (supplies null on a door nobody wired:
     *  the plain fan). */
    private final Supplier<RosterAppendCatchup<PeerSession>> admissionCursor;

    CtrlFanout(Supplier<List<PeerSession>> liveSessions,
            BiConsumer<PeerSession, byte[]> ctrlSpoolOrFail, Object lock,
            Supplier<WireRecordSink> sink, PidsDoorCounters counters,
            SeatRebindArbiter moduleSeats, String requiredDoorFingerprint,
            Supplier<RosterAppendCatchup<PeerSession>> admissionCursor) {
        this.liveSessions = liveSessions;
        this.ctrlSpoolOrFail = ctrlSpoolOrFail;
        this.lock = lock;
        this.sink = sink;
        this.counters = counters;
        this.moduleSeats = moduleSeats;
        this.requiredDoorFingerprint = requiredDoorFingerprint;
        this.admissionCursor = admissionCursor;
    }

    /**
     * The ONE shared DOWN-fan skeleton (unified
     * 2026-07-17 after 5 verbatim twins): the caller encodes its frame ONCE
     * (payload bytes are peer-independent), this helper {@code ctrlSpoolOrFail}s the same
     * bytes to every live session OUTSIDE the lock (lock-order rule), bumps the caller's
     * per-kind counter UNDER the lock, and mirrors the sink truth with ONE un-stamped
     * {@code KIND_DOWN} record. Returns the fanned-session count for the caller's operator
     * line. Thread-agnostic: the chat/seat-name fans run on the READER thread, the hulk fans
     * on the SERVE-LOOP thread — the skeleton is identical either way (the CTRL spool is
     * strict per-session FIFO, so enqueue order == delivery order regardless of caller).
     * Deliberately NOT consumed by {@link PilotIntentDatagramServer#fanMatchReset} (its post-spool step is the
     * round barrier on serve-loop-owned volatile {@code roundId}, not a lock-guarded counter)
     * or {@link PilotIntentDatagramServer#spoolSeatNameCatchUp} (single-target spool where EACH frame is a real down
     * send recording its own {@code KIND_DOWN}).
     */
    private int fanCtrlDown(byte[] frame, Runnable bumpFannedCounterLocked) {
        return fanCtrlDown(frame, bumpFannedCounterLocked, false, null);
    }

    /** The skeleton with the catch-up skip: when
     *  {@code skipCatchingUp}, a target whose RAPP catch-up cursor is active is WITHHELD (the
     *  frame is in the round log; its cursor delivers it in log order) and counted into
     *  {@code deferredOut[0]}. Returns the targets actually spooled ({@code live.size()} when
     *  nothing is skipped — every other fan byte-identical). */
    private int fanCtrlDown(byte[] frame, Runnable bumpFannedCounterLocked,
            boolean skipCatchingUp, int[] deferredOut) {
        return fanCtrlDown(frame, bumpFannedCounterLocked,
                skipCatchingUp ? PeerSession::rosterCatchupActive : null, deferredOut);
    }

    /** The skeleton with a per-target {@code divert} test: a target it answers true for is NOT
     *  spooled (the test has already routed the frame elsewhere, or withholds it) and is counted
     *  into {@code divertedOut[0]}; null = spool to every live target. */
    private int fanCtrlDown(byte[] frame, Runnable bumpFannedCounterLocked,
            Predicate<PeerSession> divert, int[] divertedOut) {
        List<PeerSession> live = liveSessions.get();
        int deferred = 0;
        for (PeerSession target : live) {
            if (divert != null && divert.test(target)) {
                deferred++;
                continue;
            }
            ctrlSpoolOrFail.accept(target, frame); // outside lock (lock-order rule)
        }
        synchronized (lock) {
            bumpFannedCounterLocked.run();
        }
        WireRecordSink s = sink.get();
        if (s != null) {
            s.record(WireRecordSink.KIND_DOWN, frame);
        }
        if (divertedOut != null) {
            divertedOut[0] = deferred;
        }
        return live.size() - deferred;
    }

    /** {@code fanChat} — the {@link #fanCtrlDown} skeleton on the CHTD frame
     * (server-stamped attribution — self-contained, no SNMS-ordering dependency). Echo
     * INCLUDED: the sender is a live session like any other — rendering on echo is the
     * client's delivery confirmation. Unlike the reset fan this runs on the READER thread
     * (chat is reactive, not a serve-loop verb; the single reader is the only thread that
     * ever nests two cores' monitors, so no cycle exists) and is door-agnostic: the legacy
     * door fans to its sole session (echo-only chat — all the sibling lane needs). */
    void fanChat(int seatId, String name, String text) {
        int fanned = fanCtrlDown(ChatFrameCodec.encodeDown(seatId, name, text),
                () -> counters.chatRelayed++);
        System.out.println("pilot-datagram-server chat_relay seat=" + seatId + " name=" + PeerText.quote(name)
                + " fanned=" + fanned + " text=" + PeerText.quote(text));
    }

    /**
     * W5 grace toasts: a server-authored CHTD broadcast from the SERVE thread — the
     * {@code fanModuleMount} thread posture on the {@link #fanChat} frame shape, attribution
     * {@code "server"}. Callers pass server-built ASCII constants (single-line printable chat
     * law), with TWO peer-fed exceptions: {@code announceJoin}'s {@code "<name> joined"} carries
     * the joiner's seat name (≤ 24 printable chars, validated at SNAM) at the HEAD of the line, and
     * {@code ChatCommands.shareBroadcastLine} carries a display name (peer TEXT) after its server
     * vocabulary head. So a consumer of a {@code server} line keys on author + SEAT ID, never on the text head
     * (the kit's {@code ShipReplyLatch} took a join
     * line headed like a {@code /ship} reply; a spoofer's seat is never the requester's).
     * {@code ChatFrameCodec.encodeDown} still validates fail-closed like every chat frame.
     */
    void fanServerChat(int seatId, String text) {
        int fanned = fanCtrlDown(ChatFrameCodec.encodeDown(seatId, "server", text),
                () -> counters.chatRelayed++);
        // `text` carries the PEER seat name via announceJoin (name + " joined") —
        // quoted like every other peer-fed print (peer text is never printed raw; PeerTextContractRows Q9).
        System.out.println("pilot-datagram-server server_chat seat=" + seatId
                + " fanned=" + fanned + " text=" + PeerText.quote(text));
    }

    /**
     * {@code fanSeatName} — the {@link #fanCtrlDown} skeleton on the SNMR frame
     * ({@code WireRecordSink.verify.decodeDown} is the legacy v1 record contract — the
     * CHTD/MRST precedent covers a new down magic). Echo included: the announcer's own SNMR
     * is its delivery confirmation. Runs on the READER thread (names are reactive session
     * metadata, not a serve-loop verb) and is door-agnostic like the chat fan.
     */
    void fanSeatName(SeatNameFrameCodec.SeatNameRecord record) {
        // Every fanned name also teaches the module-seats arbiter its
        // occupancy-reply label (reader thread, no lock held — the never-nested law; the record
        // is hasName()-guarded by both callers and codec-validated, the noteSeatName caller law).
        if (moduleSeats != null) {
            moduleSeats.noteSeatName(record.seatId(), record.name());
        }
        byte[] frame = SeatNameFrameCodec.encodeSeatNameRecord(
                record.seatId(), record.shipId(), record.name());
        // A target whose admission cursor still holds an
        // UNSENT name row for this seat gets that row REPLACED (newest wins, delivered in the
        // cursor's turn) instead of a live spool; otherwise the frame spools as before — CTRL is
        // ordered, so it lands after any row the cursor already sent. The cursor monitor is taken
        // here with NO door lock AND NO reliability monitor held (reader thread, after the
        // delivery callback returned — PilotIntentDatagramServer.fanStashedSeatNames):
        // the cursor's lock order is its monitor, then the door lock, then a core's
        // monitor; a caller holding a core's monitor here would cycle against the serve thread's
        // sendUpTo. A null or unpaced cursor declines every target (the plain fan).
        RosterAppendCatchup<PeerSession> cursor = admissionCursor.get();
        Predicate<PeerSession> replacePending = cursor == null ? null
                : target -> cursor.replacePendingName(target.sessionId(), record.seatId(), frame);
        int[] replaced = {0};
        int fanned = fanCtrlDown(frame, () -> counters.seatNamesFanned++, replacePending, replaced);
        System.out.println("pilot-datagram-server seat_name_fan seat=" + record.seatId()
                + " ship=" + record.shipId() + " name=" + PeerText.quote(record.name())
                + " fanned=" + fanned + " replaced=" + replaced[0]);
    }

    /**
     * {@code fanHulkFracture} — the {@link #fanCtrlDown} skeleton on the HKFR
     * frame. Called from the SERVE-LOOP thread per drained edge (the {@code fanMatchReset}
     * precedent); deliberately the reliable CTRL spool, NOT the tick composition — cross-lane
     * vs the EDSP death edge by design, the client parks early fracture records (the
     * lane-ordering correction). No admission catch-up exists for HKFR: a newcomer's world
     * holds no corpses.
     */
    void fanHulkFracture(HulkFractureFrameCodec.FractureEdge edge) {
        int fanned = fanCtrlDown(HulkFractureFrameCodec.encode(edge),
                () -> counters.hulkFracturesFanned++);
        System.out.println("pilot-datagram-server hulk_fracture_fan root=" + edge.rootEntityId()
                + " deathTick=" + edge.deathTick() + " kind=" + edge.edgeKind()
                + " rank=" + edge.sizeRank() + " count=" + edge.familyPieceCount()
                + " tick=" + edge.simTick() + " fanned=" + fanned);
    }

    /**
     * {@code fanMatchOver} — the {@link #fanCtrlDown} skeleton on the MOVR
     * serve-end verdict. Called from the SERVE-LOOP thread at most ONCE per serve, in the gap
     * between round-loop exit and {@link PilotIntentDatagramServer#fanGoodbye} — the CTRL spool is strict per-session
     * FIFO and the goodbye BYEs only after spool-drain (or its unarmed bound), so the verdict
     * always rides AHEAD of the teardown, bounded best-effort. No admission catch-up: a
     * session admitted after the round loop exits has no match left to end.
     */
    void fanMatchOver(MatchOverFrameCodec.MatchOverRecord record) {
        int fanned = fanCtrlDown(MatchOverFrameCodec.encode(record),
                () -> counters.matchOversFanned++);
        System.out.println("pilot-datagram-server match_over_fan outcome=" + record.outcome()
                + " winner=" + record.winnerSide() + " tick=" + record.simTick()
                + " fanned=" + fanned);
    }

    /**
     * {@code fanHulkPose} — the {@link #fanCtrlDown} skeleton on the HKPS
     * corpse-pose keyframe. Called from the SERVE-LOOP thread per drained keyframe, AFTER the
     * same tick's HKFR edges (same reliable ctrl spool — the client's rank interpretation is
     * race-free by ordering). Deliberately NO per-record operator line (the log law: periodic
     * keyframes at ~2 Hz per piece would spam — the serve loop prints {@code hulk_pose_fan}
     * FIRST-per-family only; volume rides the counters). No admission catch-up, as HKFR: a
     * newcomer's world holds no corpses; the periodic cadence self-heals late binds.
     */
    void fanHulkPose(HulkPoseFrameCodec.PoseKeyframe keyframe) {
        fanCtrlDown(HulkPoseFrameCodec.encode(keyframe), () -> counters.hulkPosesFanned++);
    }

    /** ASTP asteroid-pose frame: the {@link #fanHulkPose} skeleton (serve loop, post-step,
     *  after the HKPS frames; no per-frame line). */
    void fanAsteroidPose(byte[] frame) { //
        fanCtrlDown(frame, () -> counters.asteroidPosesFanned++);
    }

    /**
     * {@code fanHulkCutGeometry} — the {@link #fanCtrlDown} skeleton on the
     * HKCG cut-geometry record. Called from the SERVE-LOOP thread per drained record, and
     * MUST run BEFORE the same cut's {@link #fanHulkFracture} PIECE_ADDED — the ctrl spool is
     * strict per-session FIFO, so enqueue order == delivery order and the client latch is
     * warm at edge arrival (drain-loop order alone carries the law). Deliberately NO
     * per-record operator line (4 records per cut would spam — the serve loop prints
     * {@code hulk_cut_geometry_fan} FIRST-per-cut only; volume rides the counters). No
     * admission catch-up, as HKFR: a newcomer's world holds no corpses.
     */
    void fanHulkCutGeometry(HulkCutGeometryFrameCodec.CutGeometryRecord record) {
        fanCtrlDown(HulkCutGeometryFrameCodec.encode(record), () -> counters.hulkCutGeometryFanned++);
    }

    /**
     * {@code fanRosterAppend} — the {@link #fanCtrlDown} skeleton on a
     * pre-encoded RAPP frame. Called from the SERVE-LOOP thread at the (c2) wave drain,
     * immediately post-spawn (precedes that tick's SNAP fan in the same iteration; CTRL-vs-SNAP
     * cross-lane order is NOT guaranteed — the client contract is unknown-ids-inert until the
     * append lands, so an early pose is simply not yet mintable). The frame arrives pre-encoded
     * because the serve loop also appends the SAME bytes to its per-round catch-up log
     * (frames-as-sent law, the late-joiner replay).
     */
    void fanRosterAppend(byte[] frame, int rowCount) {
        // Sessions mid catch-up are skipped (deferred= counts
        // them); fanned= now counts the sessions actually sent to.
        int[] deferred = new int[1];
        int fanned = fanCtrlDown(frame, () -> counters.rosterAppendsFanned++, true, deferred);
        System.out.println("pilot-datagram-server roster_append_fan rows=" + rowCount
                + " fanned=" + fanned + " deferred=" + deferred[0]);
    }

    /**
     * {@code fanModuleMount} — the {@link #fanCtrlDown} skeleton on a pre-encoded MMNT
     * frame. Called from the SERVE-LOOP thread at the mount/dismount drains, strictly BEFORE
     * the same repoint's RAPP fan (the ctrl spool is strict per-session FIFO, so enqueue order
     * == delivery order — the sheet build-verify (d) law). Handovers are rare; the serve loop's
     * own {@code module_mount}/{@code module_dismount} lines carry the story, so the fan prints
     * per-event like RAPP.
     */
    void fanModuleMount(byte[] frame, int kind) {
        int fanned = fanCtrlDown(frame, () -> counters.moduleMountsFanned++);
        System.out.println("pilot-datagram-server module_mount_fan kind=" + kind
                + " fanned=" + fanned);
    }

    /**
     * {@code fanTransfer} — the {@link #fanCtrlDown} skeleton on a pre-encoded XFER
     * command-transfer control frame. Called from the SERVE-LOOP thread at the transfer lane's
     * emission sites (begin/deny/commit/abort). Transfers are rare human actions; the serve
     * loop's own {@code transfer_commit}/{@code transfer_abort} lines carry the story, so the
     * fan prints per-event like MMNT.
     */
    void fanTransfer(byte[] frame, int kind) {
        // A session mid RAPP catch-up is
        // WITHHELD here too — a live XFER references roster/pod ids its deferred RAPP has not
        // taught yet (the inversion the burst path could never produce); the
        // completion replay (in-flight BEGINs) is that session's transfer view, exactly the
        // late-joiner semantics. deferred= counts the withheld sessions.
        int[] deferred = new int[1];
        int fanned = fanCtrlDown(frame, () -> counters.commandTransfersFanned++, true, deferred);
        System.out.println("pilot-datagram-server transfer_fan kind=" + kind
                + " fanned=" + fanned + " deferred=" + deferred[0]);
    }

    /**
     * {@code fanPilotAim} — the {@link #fanCtrlDown} skeleton on a pre-encoded
     * AIMP batched aim frame. Called from the SERVE-LOOP thread at the per-tick aim drain
     * (~8 Hz change-gated batches). Deliberately NO per-frame operator line (the HKPS log
     * law: periodic frames would spam — the serve loop prints {@code pilot_aim_fan}
     * FIRST-per-round only; volume rides the counters). ARMED doors only
     * ({@code requiredDoorFingerprint} non-null — the VDEF emission-gate precedent):
     * unarmed/smoke doors emit nothing, byte-identical to pre-AIMP.
     */
    void fanPilotAim(byte[] frame) {
        if (requiredDoorFingerprint == null) {
            return;
        }
        fanCtrlDown(frame, () -> counters.pilotAimsFanned++);
    }
}
