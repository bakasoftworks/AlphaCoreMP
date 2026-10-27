package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The per-session datagram reliability core,
 * shared by both sides of the future UDP transport. Pure JDK and socket-free: callers feed
 * parsed datagram fields in ({@link #onDatagram}) and receive outbound datagrams as {@link Emission}s
 * through the {@link Sink}; every method takes an explicit {@code nowMillis} from ONE monotonic
 * source, so contracts and bench laws run deterministic under a simulated clock (the
 * {@code verify-servo-replay} posture). The B2 endpoint owns the wire header codec
 * (magic/version/roundId/session/CRC), the socket, the reader/timer threads, and the
 * death taxonomy; this core owns the two channel disciplines:
 *
 * <ul>
 *   <li><b>SNAP — unreliable-sequenced, latest-wins:</b> {@link #snapSend} stamps a monotone seq and
 *       emits immediately (no queue — structurally no cross-channel head-of-line, law L5);
 *       {@link #onDatagram} delivers a SNAP payload only when its seq advances the high-water mark,
 *       else drop + {@code snap_stale_dropped} — UNLESS the owner wired the optional stale hook
 *       (the stale-fold, 5-arg constructor): then the seq-stale payload goes up
 *       {@code snapStaleDelivery} + {@code snap_stale_folded} instead, for the owner to fold or
 *       discard by CONTENT tick (a reordered same-tick sibling chunk is still-newest truth the
 *       seq gate alone cannot see). Null hook (every caller older than the hook) = today's drop, unchanged.
 *       Seq spaces never reset (latest-wins must not accept a reset replay).</li>
 *   <li><b>CTRL — reliable-ordered:</b> bounded send window (default 512);
 *       retransmit on RTO expiry with ×2 backoff (simplified RFC-6298 estimator: srtt + 4·rttvar off
 *       un-retransmitted ack RTTs — Karn's rule; init 200 ms, floor 50 ms, cap 2 s). <b>Fast
 *       retransmit fires ONLY on SACK-hole evidence</b> (a delivered seq above a missing one), never
 *       on repeated cumulative acks — acks piggyback on the ~500 Hz SNAP stream, so a bare repeated
 *       ctrlAck is the idle-lane steady state, not a dup-ack signal. Receiver side:
 *       dedup, bounded reorder buffer (default 512), strict in-order delivery, cumulative ack +
 *       32-bit selective-ack bitfield.</li>
 * </ul>
 *
 * <p><b>Ack plumbing:</b> every emission (any channel) carries the receiver state {@code ctrlAck} /
 * {@code ctrlAckBits} / {@code snapHiSeen} stamped at emission time. When the outbound lane goes
 * idle the acks stop piggybacking, so: (a) an inbound CTRL data datagram answers with a bare
 * {@link Channel#ACK} when nothing has been emitted for {@code ackFlushIdleMillis} — this is what
 * lets the round-boundary MRST flush complete AFTER {@code gate.shutdown()} with zero gate threads
 * alive (the census-MISS repair); (b) {@link #onTimer} emits a keepalive ACK after
 * {@code keepaliveIdleMillis} of send-idle (also the NAT-binding refresh).
 *
 * <p><b>Window-full is a refusal, not a THROW</b>: {@link #ctrlSend} returns false and
 * counts {@code ctrl_window_refused}; the endpoint implements the bounded-block-then-terminal
 * ({@code ctrl_window_stall}) policy on top. All public methods are synchronized; delivery
 * callbacks and the sink run under the core's monitor (reentrant calls back into the core from a
 * callback are legal — same monitor; the retransmit paths snapshot before emitting so reentry
 * cannot corrupt window iteration).
 *
 * <p><b>The sender self-defends against garbage ack fields</b> (B1 review fold): a cumulative ack
 * beyond the highest seq ever sent, or a SACK bit for a never-sent seq, is corrupt-or-hostile
 * input even in-session (an internet-exposed UDP port; the transport's silent-drop posture) — either
 * would otherwise flush un-acked CTRL forever or ratchet spurious retransmit storms. Both are
 * ignored + counted ({@code ctrl_bad_acks_ignored}), never applied.
 */
public final class DatagramReliability {

    /** Core-level channels; the B2 header codec maps these (+ HELLO/WELCOME/BYE) to wire bytes. */
    public enum Channel { SNAP, CTRL, ACK }

    /** Retry-shift cap so the ×2 backoff never overflows (the cap clamps long before this). */
    private static final int MAX_BACKOFF_SHIFT = 16;

    /** Tuning knobs and their defaults; invalid values THROW — the house rule. */
    public static final class Config {
        final int ctrlWindow;
        final int ctrlReorderCapacity;
        final long rtoInitMillis;
        final long rtoFloorMillis;
        final long rtoCapMillis;
        final long keepaliveIdleMillis;
        final long ackFlushIdleMillis;

        public Config(int ctrlWindow, int ctrlReorderCapacity, long rtoInitMillis,
                long rtoFloorMillis, long rtoCapMillis, long keepaliveIdleMillis,
                long ackFlushIdleMillis) {
            if (ctrlWindow < 1) {
                throw new IllegalArgumentException("ctrlWindow must be >= 1: " + ctrlWindow);
            }
            if (ctrlReorderCapacity < 1) {
                throw new IllegalArgumentException(
                        "ctrlReorderCapacity must be >= 1: " + ctrlReorderCapacity);
            }
            if (rtoFloorMillis < 1 || rtoCapMillis < rtoFloorMillis
                    || rtoInitMillis < rtoFloorMillis || rtoInitMillis > rtoCapMillis) {
                throw new IllegalArgumentException("RTO knobs must satisfy 1 <= floor <= init <= cap:"
                        + " floor=" + rtoFloorMillis + " init=" + rtoInitMillis
                        + " cap=" + rtoCapMillis);
            }
            if (keepaliveIdleMillis < 1 || ackFlushIdleMillis < 0) {
                throw new IllegalArgumentException("idle knobs must be positive:"
                        + " keepalive=" + keepaliveIdleMillis + " ackFlush=" + ackFlushIdleMillis);
            }
            this.ctrlWindow = ctrlWindow;
            this.ctrlReorderCapacity = ctrlReorderCapacity;
            this.rtoInitMillis = rtoInitMillis;
            this.rtoFloorMillis = rtoFloorMillis;
            this.rtoCapMillis = rtoCapMillis;
            this.keepaliveIdleMillis = keepaliveIdleMillis;
            this.ackFlushIdleMillis = ackFlushIdleMillis;
        }

        public static Config defaults() {
            return new Config(512, 512, 200L, 50L, 2_000L, 250L, 20L);
        }

        /** The CTRL send window (frames in flight before the sender blocks) — the admission VDEF
         * budget row reads it ({@code WaveInflateContractRows.budgetRows}). */
        public int ctrlWindow() {
            return ctrlWindow;
        }
    }

    /**
     * One outbound datagram, pre-header: the B2 codec serializes these fields plus the envelope
     * (magic/version/roundId/session id/CRC). {@code payload} is null for bare ACK/keepalives.
     */
    public record Emission(Channel channel, long seq, long ctrlAck, int ctrlAckBits,
            long snapHiSeen, byte[] payload, boolean retransmit) {
    }

    /** Outbound consumer; called under the core monitor — must not block (a socket send is fine). */
    public interface Sink {
        void emit(Emission emission);
    }

    /** CTRL in-order delivery — called exactly once per message, in seq order. */
    public interface CtrlDelivery {
        void deliver(long seq, byte[] payload);
    }

    /** SNAP fresh delivery — called only for seqs that advance the latest-wins high-water mark. */
    public interface SnapDelivery {
        void deliver(long seq, byte[] payload);
    }

    /** Counter/gauge snapshot (the round-line gauges source; grammar belongs to the endpoint). */
    public record Stats(long snapSent, long snapFreshDelivered, long snapStaleDropped,
            long snapDropEst, long snapHiSeen, long peerSnapSeen, long snapGap,
            long ctrlSent, long ctrlDelivered, long ctrlDupDropped, long ctrlOverflowDropped,
            long ctrlRetransmits, long ctrlFastRetransmits, long ctrlWindowRefused,
            long ctrlBadAcksIgnored, long keepalivesSent, long ackFlushesSent, long rtoMillis,
            int maxPendingDepth, int maxReorderDepth) {
    }

    /** One un-acked CTRL message in the send window. */
    private static final class PendingCtrl {
        final byte[] payload;
        final long firstSendAt;
        long nextRetryAt;
        /** RTO expiries only — the ×2 backoff driver. Fast retransmits do NOT back off. */
        int rtoExpiries;
        /** Any retransmit at all — Karn's rule: an ambiguous ack never samples RTT. */
        boolean everRetransmitted;
        /** Highest SACK-delivered seq at the last fast retransmit — re-fire needs NEW evidence. */
        long lastFastRetxEvidence;

        PendingCtrl(byte[] payload, long now, long rto) {
            this.payload = payload;
            this.firstSendAt = now;
            this.nextRetryAt = now + rto;
        }
    }

    private final Config config;
    private final Sink sink;
    private final CtrlDelivery ctrlDelivery;
    private final SnapDelivery snapDelivery;
    /** stale-fold hook: seq-stale SNAP payloads deliver here instead of dropping;
     * null (every 4-arg caller) keeps the latest-wins drop exactly as before. */
    private final SnapDelivery snapStaleDelivery;

    // CTRL sender.
    private long nextCtrlSeq = 1L;
    private final TreeMap<Long, PendingCtrl> pending = new TreeMap<>();
    private double srttMillis = -1d;
    private double rttvarMillis;
    private long rtoMillis;

    // CTRL receiver ("next expected" = cumulative ack + 1).
    private long nextExpectedCtrl = 1L;
    private final TreeMap<Long, byte[]> reorderBuffer = new TreeMap<>();

    // SNAP, both directions.
    private long nextSnapSeq = 1L;
    private long snapHiSeen;
    private long peerSnapSeen;

    // snap-echo RTT: every inbound datagram echoes the peer's high-water mark of OUR
    // SNAP seqs (snapHiSeenField), so remembering when each SNAP seq was sent turns every
    // peerSnapSeen ADVANCE into a continuous RTT sample — no wire change, no new frame family.
    // The sample includes the peer's hold between receiving seq X and its next emission (bounded
    // by its inter-emission gap: the DOWN tick / keepalive cadence), so it reads a few ms above
    // the pure network RTT; sampled on advance only, so a re-echo of the same hi mark never
    // resamples. The ring self-guards against hostile hi fields: a slot only matches a seq WE
    // actually sent. Ring depth trades memory for the max in-flight window (1024 sends at a
    // 540 fps intent stream ≈ 1.9 s — RTTs beyond that simply skip sampling).
    private static final int SNAP_ECHO_RING = 1024;
    private final long[] snapEchoSeq = new long[SNAP_ECHO_RING];
    private final long[] snapEchoSentAt = new long[SNAP_ECHO_RING];
    private double snapEchoRttMillis = -1d;

    // ACK/keepalive lane (own seq space; ACKs are unordered but a monotone seq aids debugging).
    private long nextAckSeq = 1L;

    private boolean clockSeeded;
    private long lastOutboundAt;

    // Counters/gauges.
    private long snapSent;
    private long snapFreshDelivered;
    private long snapStaleDropped;
    private long snapStaleFolded;
    private long ctrlSent;
    private long ctrlDelivered;
    private long ctrlDupDropped;
    private long ctrlOverflowDropped;
    private long ctrlRetransmits;
    private long ctrlFastRetransmits;
    private long ctrlWindowRefused;
    private long ctrlBadAcksIgnored;
    private long keepalivesSent;
    private long ackFlushesSent;
    private int maxPendingDepth;
    private int maxReorderDepth;

    public DatagramReliability(Config config, Sink sink,
            CtrlDelivery ctrlDelivery, SnapDelivery snapDelivery) {
        this(config, sink, ctrlDelivery, snapDelivery, null);
    }

    /**
     * stale-fold overload: {@code snapStaleDelivery} (nullable) receives every SNAP
     * payload the latest-wins gate would otherwise drop as seq-stale. The hook runs under the
     * core monitor like {@code snapDelivery}; the wiring caller judges by content tick whether the
     * payload still folds (the reordered same-tick sibling-chunk case) — the core stays
     * tick-blind and knob-free (the composer law: policy lives on the owner).
     */
    public DatagramReliability(Config config, Sink sink, CtrlDelivery ctrlDelivery,
            SnapDelivery snapDelivery, SnapDelivery snapStaleDelivery) {
        if (config == null || sink == null || ctrlDelivery == null || snapDelivery == null) {
            throw new IllegalArgumentException("config/sink/ctrlDelivery/snapDelivery are required");
        }
        this.config = config;
        this.sink = sink;
        this.ctrlDelivery = ctrlDelivery;
        this.snapDelivery = snapDelivery;
        this.snapStaleDelivery = snapStaleDelivery;
        this.rtoMillis = config.rtoInitMillis;
    }

    /**
     * Enqueue + emit one reliable CTRL message. Returns false (nothing sent, no seq consumed) when
     * the send window is full — the endpoint's bounded-block-then-{@code ctrl_window_stall} policy
     * sits on top of this refusal.
     */
    public synchronized boolean ctrlSend(byte[] payload, long nowMillis) {
        return ctrlSendReturningSeq(payload, nowMillis) >= 0L;
    }

    /**
     * {@link #ctrlSend} returning the assigned seq, or {@code -1} on a window-full refusal
     * (nothing sent, no seq consumed). Born for the kicked-notice burst: a
     * session about to be failed gets no retransmits (dead sessions are wire-DEAD),
     * so the endpoint mails loss-redundant SAME-seq raw duplicates itself — and under the
     * strict in-order CTRL delivery contract only same-seq copies are real redundancy (any one
     * arriving delivers; distinct seqs would all stall on the first copy's loss). Same-seq
     * duplication needs the seq, and reading it back later races concurrent senders — hence
     * the return value, atomic under this monitor.
     */
    public synchronized long ctrlSendReturningSeq(byte[] payload, long nowMillis) {
        if (payload == null) {
            throw new IllegalArgumentException("CTRL payload is required");
        }
        seedClock(nowMillis);
        if (pending.size() >= config.ctrlWindow) {
            ctrlWindowRefused++;
            return -1L;
        }
        long seq = nextCtrlSeq++;
        pending.put(seq, new PendingCtrl(payload, nowMillis, rtoMillis));
        maxPendingDepth = Math.max(maxPendingDepth, pending.size());
        ctrlSent++;
        emit(Channel.CTRL, seq, payload, false, nowMillis);
        return seq;
    }

    /** Emit one unreliable latest-wins SNAP frame; returns its seq. Never queues, never blocks. */
    public synchronized long snapSend(byte[] payload, long nowMillis) {
        if (payload == null) {
            throw new IllegalArgumentException("SNAP payload is required");
        }
        seedClock(nowMillis);
        long seq = nextSnapSeq++;
        snapSent++;
        int slot = (int) (seq % SNAP_ECHO_RING);
        snapEchoSeq[slot] = seq;
        snapEchoSentAt[slot] = nowMillis;
        emit(Channel.SNAP, seq, payload, false, nowMillis);
        return seq;
    }

    /**
     * Feed one received datagram, post header-parse (the endpoint has already verified
     * CRC/magic/version/session id and unwrapped the fields). {@code payload} may be null for bare
     * ACK/keepalives. Ack fields are processed from EVERY channel — they piggyback everywhere.
     */
    public synchronized void onDatagram(Channel channel, long seq, long ctrlAck, int ctrlAckBits,
            long snapHiSeenField, long nowMillis, byte[] payload) {
        if (channel == null) {
            throw new IllegalArgumentException("channel is required");
        }
        seedClock(nowMillis);
        if (snapHiSeenField > peerSnapSeen) {
            // The snap-echo RTT sample — first echo of a new hi mark only (see field doc).
            int slot = (int) (snapHiSeenField % SNAP_ECHO_RING);
            if (snapEchoSeq[slot] == snapHiSeenField) {
                double rtt = Math.max(0d, nowMillis - snapEchoSentAt[slot]);
                snapEchoRttMillis = snapEchoRttMillis < 0d
                        ? rtt : 0.875d * snapEchoRttMillis + 0.125d * rtt;
            }
        }
        peerSnapSeen = Math.max(peerSnapSeen, snapHiSeenField);
        processAcks(ctrlAck, ctrlAckBits, nowMillis);
        switch (channel) {
            case SNAP -> onSnapData(seq, payload);
            case CTRL -> onCtrlData(seq, payload, nowMillis);
            case ACK -> { /* header-only; acks already processed */ }
        }
    }

    /**
     * Timer pump (the endpoint's single daemon timer thread, 10–20 ms granularity):
     * RTO-expired retransmits with ×2 backoff, then the send-idle keepalive.
     */
    public synchronized void onTimer(long nowMillis) {
        seedClock(nowMillis);
        // Snapshot before emitting: the sink may legally re-enter the core (same monitor), and a
        // reentrant ack/send must not corrupt this iteration of the window.
        List<Map.Entry<Long, PendingCtrl>> due = null;
        for (var entry : pending.entrySet()) {
            if (entry.getValue().nextRetryAt <= nowMillis) {
                if (due == null) {
                    due = new ArrayList<>();
                }
                due.add(entry);
            }
        }
        if (due != null) {
            for (var entry : due) {
                PendingCtrl p = entry.getValue();
                p.rtoExpiries++;
                p.everRetransmitted = true;
                long backoff = rtoMillis << Math.min(p.rtoExpiries, MAX_BACKOFF_SHIFT);
                p.nextRetryAt = nowMillis
                        + Math.min(config.rtoCapMillis, Math.max(rtoMillis, backoff));
                ctrlRetransmits++;
                emit(Channel.CTRL, entry.getKey(), p.payload, true, nowMillis);
            }
        }
        if (nowMillis - lastOutboundAt >= config.keepaliveIdleMillis) {
            keepalivesSent++;
            emit(Channel.ACK, nextAckSeq++, null, false, nowMillis);
        }
    }

    /** True when every CTRL message ever sent has been acked (the boundary-flush predicate). */
    public synchronized boolean ctrlFlushed() {
        return pending.isEmpty();
    }

    public synchronized int ctrlOutstanding() {
        return pending.size();
    }

    /** The CTRL send-window size ({@code config} is final — no monitor needed): the catch-up
     *  cursor's headroom. */
    public int ctrlWindow() {
        return config.ctrlWindow;
    }

    public synchronized long highestCtrlSeqSent() {
        return nextCtrlSeq - 1L;
    }

    /** Current retransmission timeout — the endpoint's window-full block bound. */
    public synchronized long currentRtoMillis() {
        return rtoMillis;
    }

    /**
     * The continuous snap-echo RTT estimate (EWMA, ms), or {@code NaN} before the
     * first echo. Updates on every inbound datagram that advances the peer's echoed high-water
     * mark of our SNAP sends, so on a live duplex lane it refreshes at roughly the peer's DOWN
     * cadence; slightly above pure network RTT by the peer's hold-to-next-emission (field doc).
     */
    public synchronized double snapEchoRttMillis() {
        return snapEchoRttMillis < 0d ? Double.NaN : snapEchoRttMillis;
    }

    /**
     * The CTRL-lane smoothed RTT (the private RFC-6298 {@code srtt} the RTO already
     * runs on, ms), or {@code NaN} before the first un-retransmitted CTRL ack (Karn's rule).
     * Sparse by nature (samples only when this side sends CTRL — PDCL/SNAM on a client); exposed
     * as the cross-check for the dense snap-echo estimate above.
     */
    public synchronized double ctrlSrttMillis() {
        return srttMillis < 0d ? Double.NaN : srttMillis;
    }

    /** seq-stale SNAP payloads handed up the stale hook instead of dropped
     * (0 forever with the 4-arg constructor). Getter, not a {@link Stats} field — the record
     * shape is destructured by standing tests and stays frozen. */
    public synchronized long snapStaleFolded() {
        return snapStaleFolded;
    }

    public synchronized Stats stats() {
        // snapDropEst is receiver-side never-arrived, ESTIMATED as hiSeen minus arrivals: network
        // duplication inflates arrivals (dups land in stale OR the stale-fold hook), biasing the
        // estimate LOW; trailing loss is invisible to it. The sender-side snapGap is the
        // complement — the B5 A/B write-up must read them together, not this one alone.
        long dropEst = Math.max(0L,
                snapHiSeen - snapFreshDelivered - snapStaleDropped - snapStaleFolded);
        return new Stats(snapSent, snapFreshDelivered, snapStaleDropped, dropEst,
                snapHiSeen, peerSnapSeen, Math.max(0L, snapSent - peerSnapSeen),
                ctrlSent, ctrlDelivered, ctrlDupDropped, ctrlOverflowDropped,
                ctrlRetransmits, ctrlFastRetransmits, ctrlWindowRefused,
                ctrlBadAcksIgnored, keepalivesSent, ackFlushesSent, rtoMillis,
                maxPendingDepth, maxReorderDepth);
    }

    // ------------------------------------------------------------------ inbound internals

    private void onSnapData(long seq, byte[] payload) {
        if (seq > snapHiSeen) {
            snapHiSeen = seq;
            snapFreshDelivered++;
            snapDelivery.deliver(seq, payload);
        } else if (snapStaleDelivery != null) {
            snapStaleFolded++;
            snapStaleDelivery.deliver(seq, payload);
        } else {
            snapStaleDropped++;
        }
    }

    private void onCtrlData(long seq, byte[] payload, long nowMillis) {
        if (seq < nextExpectedCtrl || reorderBuffer.containsKey(seq)) {
            ctrlDupDropped++;
        } else if (seq >= nextExpectedCtrl + config.ctrlReorderCapacity) {
            // Beyond the bounded reorder buffer (law L3): drop; the peer's RTO recovers it once
            // the cumulative ack advances. The peer's window (<= our capacity by default) makes
            // this unreachable in the shipped config; the bound still holds under any config.
            ctrlOverflowDropped++;
        } else if (seq == nextExpectedCtrl) {
            nextExpectedCtrl++;
            ctrlDelivered++;
            ctrlDelivery.deliver(seq, payload);
            drainReorderBuffer();
        } else {
            reorderBuffer.put(seq, payload);
            maxReorderDepth = Math.max(maxReorderDepth, reorderBuffer.size());
        }
        // The peer needs an ack for what just arrived (fresh, dup, or overflow — a dup means it is
        // retransmitting, i.e. our piggybacked acks are not reaching it). If the outbound lane has
        // gone idle, answer with a bare ACK now — this is what terminates the post-gate MRST
        // retransmit chain.
        if (nowMillis - lastOutboundAt >= config.ackFlushIdleMillis) {
            ackFlushesSent++;
            emit(Channel.ACK, nextAckSeq++, null, false, nowMillis);
        }
    }

    private void drainReorderBuffer() {
        while (!reorderBuffer.isEmpty() && reorderBuffer.firstKey() == nextExpectedCtrl) {
            var head = reorderBuffer.pollFirstEntry();
            nextExpectedCtrl++;
            ctrlDelivered++;
            ctrlDelivery.deliver(head.getKey(), head.getValue());
        }
    }

    private void processAcks(long ctrlAck, int ctrlAckBits, long nowMillis) {
        // Self-defense (class doc): an ack for a seq never sent is corrupt-or-hostile input —
        // applying it would flush un-acked CTRL forever (silent L1 break) and poison the RTT
        // estimator. Ignore the whole field set + count.
        if (ctrlAck < 0L || ctrlAck >= nextCtrlSeq) {
            ctrlBadAcksIgnored++;
            return;
        }
        long maxDelivered = ctrlAck;
        // Cumulative ack: everything <= ctrlAck.
        while (!pending.isEmpty() && pending.firstKey() <= ctrlAck) {
            var head = pending.pollFirstEntry();
            sampleRtt(head.getValue(), nowMillis);
        }
        // Selective acks: bit i => seq ctrlAck+1+i delivered (buffered at the peer). SACKed seqs
        // leave the window entirely — our receiver never renegs, and neither does the sibling's.
        // A bit for a never-sent seq is ignored + counted (it must not drive fast-retx evidence).
        if (ctrlAckBits != 0) {
            for (int i = 0; i < 32; i++) {
                if ((ctrlAckBits & (1 << i)) == 0) {
                    continue;
                }
                long seq = ctrlAck + 1L + i;
                if (seq >= nextCtrlSeq) {
                    ctrlBadAcksIgnored++;
                    continue;
                }
                maxDelivered = Math.max(maxDelivered, seq);
                PendingCtrl sacked = pending.remove(seq);
                if (sacked != null) {
                    sampleRtt(sacked, nowMillis);
                }
            }
        }
        // Fast retransmit — ONLY on SACK-hole evidence: a delivered seq above a still-
        // pending one. Each hole re-fires only on NEW evidence (a higher delivered seq than at its
        // last fast retransmit), so a repeated identical ack field never retransmits. Fast
        // retransmits re-arm the RTO but never drive the ×2 backoff (rtoExpiries only), or one
        // lost head under SACK chatter would inflate its own recovery latency. Snapshot before
        // emitting (reentrancy — see onTimer).
        if (maxDelivered > ctrlAck) {
            List<Map.Entry<Long, PendingCtrl>> holes = null;
            for (var entry : pending.headMap(maxDelivered, false).entrySet()) {
                if (maxDelivered <= entry.getValue().lastFastRetxEvidence) {
                    continue;
                }
                if (holes == null) {
                    holes = new ArrayList<>();
                }
                holes.add(entry);
            }
            if (holes != null) {
                for (var entry : holes) {
                    PendingCtrl hole = entry.getValue();
                    hole.lastFastRetxEvidence = maxDelivered;
                    hole.everRetransmitted = true;
                    hole.nextRetryAt = nowMillis + rtoMillis;
                    ctrlFastRetransmits++;
                    emit(Channel.CTRL, entry.getKey(), hole.payload, true, nowMillis);
                }
            }
        }
    }

    /** Karn's rule: only never-retransmitted sends produce RTT samples (RFC 6298 simplified). */
    private void sampleRtt(PendingCtrl acked, long nowMillis) {
        if (acked.everRetransmitted) {
            return;
        }
        double rtt = Math.max(0d, nowMillis - acked.firstSendAt);
        if (srttMillis < 0d) {
            srttMillis = rtt;
            rttvarMillis = rtt / 2d;
        } else {
            rttvarMillis = 0.75d * rttvarMillis + 0.25d * Math.abs(srttMillis - rtt);
            srttMillis = 0.875d * srttMillis + 0.125d * rtt;
        }
        long raw = (long) Math.ceil(srttMillis + 4d * rttvarMillis);
        rtoMillis = Math.min(config.rtoCapMillis, Math.max(config.rtoFloorMillis, raw));
    }

    // ------------------------------------------------------------------ outbound internals

    private void emit(Channel channel, long seq, byte[] payload, boolean retransmit,
            long nowMillis) {
        lastOutboundAt = nowMillis;
        sink.emit(new Emission(channel, seq, nextExpectedCtrl - 1L, receiverAckBits(),
                snapHiSeen, payload, retransmit));
    }

    private int receiverAckBits() {
        if (reorderBuffer.isEmpty()) {
            return 0;
        }
        int bits = 0;
        long base = nextExpectedCtrl; // == cumulative ack + 1; bit i covers seq base + i.
        Iterator<Long> it = reorderBuffer.navigableKeySet().iterator();
        while (it.hasNext()) {
            long seq = it.next();
            long offset = seq - base;
            if (offset >= 32) {
                break;
            }
            bits |= 1 << (int) offset;
        }
        return bits;
    }

    private void seedClock(long nowMillis) {
        if (!clockSeeded) {
            clockSeeded = true;
            lastOutboundAt = nowMillis;
        }
    }
}
