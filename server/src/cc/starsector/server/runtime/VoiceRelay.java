package cc.starsector.server.runtime;

import cc.starsector.server.runtime.VoiceFrameCodec.TalkerFrame;
import cc.starsector.server.runtime.VoiceFrameCodec.VoiceUp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;

/**
 * W12 L2 — the voice relay: ingress on the door's reader
 * thread, the {@code pilot-voice-relay} bundler thread, the global-slot / proximity mixer and
 * the mute table. Generic over the door's session object ({@link Endpoint#handle}) so the whole
 * class runs game-free under contract rows through a recording {@link Transport}.
 *
 * <p><b>Ingress ({@link #onVoiceUp}, reader thread)</b> — check order: voice disabled ⇒ drop;
 * stale {@code roundId} ⇒ drop; per-talker token bucket (burst {@value #BUCKET_BURST} frames,
 * refill one per {@code frameMs}) empty ⇒ drop; {@link VoiceFrameCodec#decodeUp} — its
 * {@link IllegalArgumentException} PROPAGATES so the door kills the session (a CRC-valid
 * out-of-domain UP is the post-admit UP law; UDP loss cannot produce that shape); Opus bytes
 * over the config-derived cap ⇒ DROP + count (not a session kill: a live
 * {@code /voice bitrateKbps} retune races the client's in-flight frames, so an over-cap frame is
 * a race, not a malice signal); envelope seq not strictly advancing ⇒ drop (replay/reorder
 * protection for free); else push to the talker's 3-deep FIFO (drop-oldest + count when full —
 * never a latest-wins slot: two unsynced clocks would beat and PLC-gap systematically).
 *
 * <p><b>Pump ({@link #pumpOnce}, relay thread; public so rows drive it without a thread):</b>
 * (i) pop one frame per talker (two when the FIFO holds ≥2 AND few talkers are active AND the
 * second routes like the first — same echo bit, same proximity — a backlogged talker catches up,
 * seq tags disambiguate, the wire's 8-entry cap always holds);
 * (ii) GLOBAL slots: non-proximity talkers compete for {@code maxTalkers} slots — a holder keeps
 * its slot while active (silence > 2×frameMs releases it, so does a switch to proximity frames
 * or to echo mic-test frames), free slots go to waiters by oldest
 * burst start, and a holder past {@value #GLOBAL_TENURE_MILLIS} ms whose slot is contested yields
 * to the back of the line (never first-come-forever); (iii) per listener: the slotted global
 * talkers, then proximity talkers within {@code proxCullSu} nearest-first (both anchors
 * positioned-or-ghost), the talker's own frames skipped unless they carry
 * {@link VoiceFrameCodec#FLAG_ECHO} (bit-only; the sheet's {@code echo} is just
 * the VCFG default) — a GLOBAL echo frame reaches its sender ONLY (mic test), a
 * PROXIMITY echo frame reaches the others as usual PLUS one slot-free copy to its sender
 * — mutes dropped,
 * trimmed to {@code maxTalkers} unique talkers; an empty list sends NOTHING (idle voice costs
 * zero), else one or more self-contained VCDN parts via {@link Transport#sendVoice} stamped with
 * the listener's own monotonic voice seq.
 *
 * <p><b>Live set:</b> the door rebuilds {@link #setLive} under ITS lock at its four mutation
 * sites (admit / remove / fail / bye); the relay never touches the door lock and purges its
 * per-session AUDIO state for departed ids. Mutes are per-LISTENER sets keyed by the TARGET's
 * session id and live exactly as long as the door's TABLE holds the session:
 * the door {@link #detachMutes detaches} a session's rows at its table drop and
 * {@link #attachMutes re-attaches} them under the successor id on a same-occupant takeover
 * (cid / seat-token keyed — the door's own identity law), so a rejoin keeps both what the
 * occupant muted and who muted them, while a seat handed to a DIFFERENT occupant (a new table
 * key, no predecessor) never inherits either. A non-live target never reaches the pump, so a
 * lingering id in a set is inert until its detach.
 *
 * <p>Security: seat from the session, never
 * wire text; fixed-cap allocation by the codec; Opus bytes are relayed opaque and NEVER logged —
 * {@link #meterLine} prints counts only.
 */
public final class VoiceRelay implements AutoCloseable {

    /** One live session as the relay sees it ({@code handle} = the door's session object). */
    public record Endpoint(long sessionId, int seatId, long shipId, Object handle) {
        public Endpoint {
            Objects.requireNonNull(handle, "handle");
        }
    }

    /** The door's send/round surface (thread-agnostic sends; the relay thread is a 3rd sender). */
    public interface Transport {
        void sendVoice(Object handle, long voiceSeq, byte[] vcdnPart);

        int roundId();

        void sendCtrl(Object handle, byte[] ctrlFrame);
    }

    public static final String THREAD_NAME = "pilot-voice-relay";
    public static final int FIFO_DEPTH = 3;
    public static final int BUCKET_BURST = 10;
    /** A contested global slot is yielded after this tenure (re-queued at the back). */
    public static final long GLOBAL_TENURE_MILLIS = 20_000L;

    /** One accepted UP frame waiting in a talker's FIFO; {@code arrivalMicros} = the ingress
     *  {@code nanoTime / 1000} (read only for a stamped frame). */
    private record Pending(VoiceUp up, long envelopeSeq, long arrivalMillis, int arrivalMicros) {
    }

    /** Per-session state: the talker half (reader thread, guarded by its monitor) + the relay-
     * thread-only half (slot tenure, popped frames, listener seq). */
    private static final class PeerState {
        final Endpoint endpoint;
        // -- reader thread (synchronized on this) --
        double tokens = BUCKET_BURST;
        long bucketRefillMillis;
        long seqHighWater = -1L;
        final ArrayDeque<Pending> fifo = new ArrayDeque<>(FIFO_DEPTH);
        long lastFrameMillis;
        long burstStartMillis;
        int lastBurstId = -1;
        boolean lastProximity;
        // -- relay thread only --
        Pending primary;
        Pending catchUp;
        boolean slotted;
        long slotSince;
        long listenerSeq;

        PeerState(Endpoint endpoint) {
            this.endpoint = endpoint;
        }
    }

    private volatile VoiceConfig config;
    private final Transport transport;
    private final VoicePositionSlot slot;
    private volatile Endpoint[] live = new Endpoint[0];
    private final ConcurrentHashMap<Long, PeerState> peers = new ConcurrentHashMap<>();
    /** listener sessionId → muted target sessionIds (lock-free reads on the pump). */
    private final ConcurrentHashMap<Long, Set<Long>> mutes = new ConcurrentHashMap<>();
    /** Listener sessionId → its last {@code /mute} menu's
     *  observer HANDLE → target sessionId ({@link VoiceMuteCommand}; ≤ the observer cap per
     *  listener). Purged with the listener's mute rows at {@link #detachMutes} — never carried to
     *  a takeover successor (the ban menu-pin law: a successor re-lists). */
    private final ConcurrentHashMap<Long, Map<Long, Long>> muteMenuPins = new ConcurrentHashMap<>();
    private volatile Thread thread;
    private volatile boolean closed;

    // -- meters (counts only) --
    private final AtomicLong voiceFramesIn = new AtomicLong();
    private final AtomicLong voiceFramesOut = new AtomicLong();
    private final AtomicLong voicePartsOut = new AtomicLong();
    private final AtomicLong voiceBytesOut = new AtomicLong();
    private final AtomicLong voiceRateDropped = new AtomicLong();
    private final AtomicLong voiceSeqDropped = new AtomicLong();
    private final AtomicLong voiceStaleRound = new AtomicLong();
    private final AtomicLong voiceOverCapDropped = new AtomicLong();
    private final AtomicLong voiceFifoDropped = new AtomicLong();
    private final AtomicLong voiceGlobalTrims = new AtomicLong();
    private final AtomicLong voiceProxTrims = new AtomicLong();
    private final AtomicLong voiceDisabledDropped = new AtomicLong();
    private final AtomicLong voiceSeatUnfit = new AtomicLong();
    private final AtomicLong voicePumpErrors = new AtomicLong();
    private volatile int talkersLastPump;
    /** STAGE-STAMP meter (relay thread only; stamped = probe frames only): per
     *  stamped frame popped this pump, capture→ingress and ingress→bundle in µs (wrap-aware int
     *  diffs; the capture stamp is the talker's untrusted QPC µs — meaningful on loopback, a
     *  logged number otherwise), printed as ONE {@code voice-relay stamps} row per
     *  {@value #STAMP_ROW_MILLIS} ms and reset. Zero work until a stamped frame is popped. */
    private static final long STAMP_ROW_MILLIS = 5000L;
    private int stampN;
    private long stampCapInMin;
    private long stampCapInSum;
    private long stampCapInMax;
    private long stampInOutMin;
    private long stampInOutSum;
    private long stampInOutMax;
    private long stampRowMillis;

    public VoiceRelay(VoiceConfig config, Transport transport, VoicePositionSlot slot) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.slot = Objects.requireNonNull(slot, "slot");
    }

    public VoiceConfig config() {
        return config;
    }

    /** The VCFG frame for the admit spool (the standing sheet). */
    public byte[] configFrame() {
        return VoiceConfigFrameCodec.encode(config);
    }

    /** The current live endpoints (the door's last {@link #setLive}). */
    public Endpoint[] liveEndpoints() {
        return live.clone();
    }

    // ---- live set --------------------------------------------------------------------------

    /** Door thread, under the door's lock: replace the live set and purge departed ids; a departed
     *  id's {@link #toggleMute} is refused from here on (the door detaches its rows after this). */
    public void setLive(Endpoint[] endpoints) {
        Objects.requireNonNull(endpoints, "endpoints");
        Endpoint[] copy = endpoints.clone();
        Set<Long> liveIds = new HashSet<>();
        for (Endpoint e : copy) {
            liveIds.add(e.sessionId());
        }
        live = copy;
        peers.keySet().removeIf(id -> !liveIds.contains(id));
        // Mutes are NOT purged here: a BYE'd / death-classified session leaves
        // the live set long before its table entry goes, and a same-occupant takeover must still
        // find its rows — the door's detachMutes at the table drop is the purge.
    }

    // ---- ingress (reader thread) -----------------------------------------------------------

    /**
     * One VOICE-channel datagram from an admitted session. Throws
     * {@link IllegalArgumentException} ONLY for a CRC-valid out-of-domain VCUP (the door kills);
     * every other refusal drops + counts.
     */
    public void onVoiceUp(Endpoint talker, int roundId, long envelopeSeq, byte[] payload,
            long nowMillis) {
        Objects.requireNonNull(talker, "talker");
        Objects.requireNonNull(payload, "payload");
        VoiceConfig cfg = config;
        if (!cfg.enabled()) {
            voiceDisabledDropped.incrementAndGet();
            return;
        }
        if (roundId != transport.roundId()) {
            voiceStaleRound.incrementAndGet();
            return;
        }
        if (talker.seatId() < 0 || talker.seatId() > 0xFF) {
            voiceSeatUnfit.incrementAndGet();
            return;
        }
        PeerState st = peers.computeIfAbsent(talker.sessionId(), id -> new PeerState(talker));
        synchronized (st) {
            // Elapsed-refill bucket: one token per frameMs, burst BUCKET_BURST.
            if (st.bucketRefillMillis != 0L) {
                long elapsed = nowMillis - st.bucketRefillMillis;
                if (elapsed > 0L) {
                    st.tokens = Math.min((double) BUCKET_BURST,
                            st.tokens + elapsed / (double) cfg.frameMs());
                }
            }
            st.bucketRefillMillis = nowMillis;
            if (st.tokens < 1.0) {
                voiceRateDropped.incrementAndGet();
                return;
            }
            st.tokens -= 1.0;
        }
        VoiceUp up = VoiceFrameCodec.decodeUp(payload); // IAE propagates: post-admit UP law
        if (up.opus().length > cfg.frameBytesCap()) {
            voiceOverCapDropped.incrementAndGet();
            return;
        }
        synchronized (st) {
            if (envelopeSeq <= st.seqHighWater) {
                voiceSeqDropped.incrementAndGet();
                return;
            }
            st.seqHighWater = envelopeSeq;
            // A proximity flip starts a new burst — else a
            // one-frame prox flip (slot released) and back re-wins a free slot by its OLD oldest start.
            boolean newBurst = up.burstStart() || up.burstId() != st.lastBurstId
                    || up.proximity() != st.lastProximity
                    || nowMillis - st.lastFrameMillis > 2L * cfg.frameMs();
            if (newBurst) {
                st.burstStartMillis = nowMillis;
            }
            st.lastBurstId = up.burstId();
            st.lastProximity = up.proximity();
            st.lastFrameMillis = nowMillis;
            if (st.fifo.size() >= FIFO_DEPTH) {
                st.fifo.pollFirst();
                voiceFifoDropped.incrementAndGet();
            }
            st.fifo.addLast(new Pending(up, envelopeSeq, nowMillis,
                    (int) (System.nanoTime() / 1000L)));
            voiceFramesIn.incrementAndGet();
        }
    }

    // ---- relay thread ----------------------------------------------------------------------

    public void start() {
        if (thread != null) {
            return;
        }
        Thread t = new Thread(this::runLoop, THREAD_NAME);
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private void runLoop() {
        while (!closed) {
            try {
                Thread.sleep(config.effectiveRelayTickMs());
            } catch (InterruptedException interrupted) {
                return;
            }
            try {
                pumpOnce();
            } catch (RuntimeException pumpFailed) {
                // The relay thread never dies on one bad tick; count + announce once per 1000.
                if (voicePumpErrors.incrementAndGet() % 1000L == 1L) {
                    System.out.println("voice-relay pump_error " + pumpFailed);
                }
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * One bundler tick on the PRODUCTION clock — the
     * door's {@link PilotIntentDatagramServer#nowMillis()} ({@code nanoTime} ms), the clock ingress
     * stamps {@code lastFrameMillis} / {@code burstStartMillis} with. Why: the pump ran on
     * {@code System.currentTimeMillis()} while ingress stamped {@code nanoTime} ms, so every
     * global slot read as ~1.7e12 ms silent and was released on every pump — {@code maxTalkers}
     * bounded nothing and the contested-slot / tenure yield was unreachable. This overload IS the
     * production clock, so a contract row drives exactly what {@link #runLoop} runs.
     */
    public void pumpOnce() {
        pumpOnce(PilotIntentDatagramServer.nowMillis());
    }

    /**
     * One bundler tick. Relay thread only (or a contract row). Pops, assigns global slots, builds
     * + sends every listener's bundle.
     */
    public void pumpOnce(long nowMillis) {
        VoiceConfig cfg = config;
        Endpoint[] eps = live;
        VoicePositionSlot.View view = slot.view();
        int front = view.front();
        long silenceMillis = 2L * cfg.frameMs();

        // (i) pop: count talkers with a frame first so the 2-frame catch-up never overflows the
        // wire's MAX_TALKERS_WIRE entries (2 × active ≤ 8).
        List<PeerState> active = new ArrayList<>(eps.length);
        for (Endpoint ep : eps) {
            PeerState st = peers.get(ep.sessionId());
            if (st == null) {
                continue;
            }
            st.primary = null;
            st.catchUp = null;
            synchronized (st) {
                if (!st.fifo.isEmpty()) {
                    active.add(st);
                }
            }
        }
        boolean catchUpAllowed = active.size() * 2 <= VoiceFrameCodec.MAX_TALKERS_WIRE;
        for (PeerState st : active) {
            synchronized (st) {
                st.primary = st.fifo.pollFirst();
                if (catchUpAllowed && !st.fifo.isEmpty() && sameRoute(st.primary, st.fifo.peekFirst())) {
                    st.catchUp = st.fifo.pollFirst();
                }
            }
        }
        talkersLastPump = active.size();

        // ONE bundle instant per pump for every stamped frame it carries + the meter row.
        int outMicros = (int) (System.nanoTime() / 1000L);
        for (PeerState st : active) {
            noteStamps(st.primary, outMicros);
            noteStamps(st.catchUp, outMicros);
        }
        if (stampN > 0) {
            if (stampRowMillis == 0L) {
                stampRowMillis = nowMillis;
            } else if (nowMillis - stampRowMillis >= STAMP_ROW_MILLIS) {
                System.out.println("voice-relay stamps n=" + stampN
                        + " cap>in=" + stampText(stampN, stampCapInSum, stampCapInMin, stampCapInMax)
                        + " in>out=" + stampText(stampN, stampInOutSum, stampInOutMin, stampInOutMax));
                stampN = 0;
                stampRowMillis = nowMillis;
            }
        }

        // (ii) global slots.
        assignGlobalSlots(cfg, nowMillis, silenceMillis, active);

        // (iii) per listener.
        if (active.isEmpty()) {
            return;
        }
        List<TalkerFrame> bundle = new ArrayList<>(VoiceFrameCodec.MAX_TALKERS_WIRE);
        List<PeerState> chosen = new ArrayList<>(VoiceFrameCodec.MAX_TALKERS_WIRE);
        List<PeerState> proxCandidates = new ArrayList<>(active.size());
        for (Endpoint listener : eps) {
            bundle.clear();
            chosen.clear();
            proxCandidates.clear();
            Set<Long> muted = mutes.get(listener.sessionId());
            int listenerSlot = view.slotOfSeat(listener.seatId());
            float lx = listenerSlot < 0 ? Float.NaN : view.xOf(front, listenerSlot);
            float ly = listenerSlot < 0 ? Float.NaN : view.yOf(front, listenerSlot);
            PeerState selfEcho = null;
            for (PeerState st : active) {
                // Echo is BIT-only — the talker hears itself iff its own
                // frame carries FLAG_ECHO; the sheet's cfg.echo() is no longer consulted here
                // (it is served via VCFG as the DEFAULT the client adopts — server GUI = defaults).
                // A GLOBAL echo frame = a MIC TEST, never a
                // broadcast — it loops back to its SENDER ONLY (security law: no fan-out
                // amplification from a self-test bit).
                // A PROXIMITY echo frame is the one
                // exception — it reaches every OTHER listener exactly as an echo-OFF prox frame
                // does AND loops ONE copy back to its sender (the client mixer centres own-seat
                // frames by seatId — VoicePlayback — so it plays as a plain global-style echo).
                // Self is never a prox candidate ⇒ delivered exactly once (no d=0 double-play).
                boolean isSelf = st.endpoint.sessionId() == listener.sessionId();
                boolean echo = (st.primary.up().flags() & VoiceFrameCodec.FLAG_ECHO) != 0;
                boolean prox = st.primary.up().proximity();
                if (isSelf) {
                    // Own frame: looped back iff it asks (deferred below — after the slot
                    // holders, ahead of the prox candidates; an echo burst
                    // never HOLDS a global slot, so its loopback rides slot-free).
                    if (echo) {
                        selfEcho = st;
                    }
                    continue;
                }
                if (echo && !prox) {
                    continue;
                }
                if (muted != null && muted.contains(st.endpoint.sessionId())) {
                    continue;
                }
                if (!prox) {
                    if (st.slotted) {
                        chosen.add(st);
                    }
                } else if (!Float.isNaN(lx) && !Float.isNaN(ly)) {
                    int ts = view.slotOfSeat(st.endpoint.seatId());
                    if (ts < 0) {
                        continue;
                    }
                    float tx = view.xOf(front, ts);
                    float ty = view.yOf(front, ts);
                    if (Float.isNaN(tx) || Float.isNaN(ty)) {
                        continue;
                    }
                    double d = Math.hypot(tx - lx, ty - ly);
                    if (d <= cfg.proxCullSu()) {
                        proxCandidates.add(st);
                    }
                }
            }
            if (selfEcho != null) {
                // The sender's own loopback (global mic test or prox self-echo): one
                // slot-free entry that yields to the slot holders above but wins the maxTalkers
                // trim over far prox talkers below.
                chosen.add(selfEcho);
            }
            if (!proxCandidates.isEmpty()) {
                final float flx = lx;
                final float fly = ly;
                proxCandidates.sort(Comparator.comparingDouble(st -> {
                    int ts = view.slotOfSeat(st.endpoint.seatId());
                    return Math.hypot(view.xOf(front, ts) - flx, view.yOf(front, ts) - fly);
                }));
                for (PeerState st : proxCandidates) {
                    if (chosen.size() >= cfg.maxTalkers()) {
                        voiceProxTrims.incrementAndGet();
                        continue;
                    }
                    chosen.add(st);
                }
            }
            if (chosen.isEmpty()) {
                continue;
            }
            for (PeerState st : chosen) {
                if (bundle.size() >= VoiceFrameCodec.MAX_TALKERS_WIRE) {
                    // maxTalkers slot holders + the slot-free self loopback can reach
                    // MAX_TALKERS_WIRE + 1 only at maxTalkers == 8; the loopback is last in
                    // chosen, so it is the one that yields (encodeDownParts refuses 9).
                    break;
                }
                bundle.add(frameOf(st, st.primary, view, front, outMicros));
            }
            for (PeerState st : chosen) {
                if (st.catchUp != null && bundle.size() < VoiceFrameCodec.MAX_TALKERS_WIRE) {
                    bundle.add(frameOf(st, st.catchUp, view, front, outMicros));
                }
            }
            List<byte[]> parts = VoiceFrameCodec.encodeDownParts(bundle);
            PeerState ls = peers.computeIfAbsent(listener.sessionId(),
                    id -> new PeerState(listener));
            for (byte[] part : parts) {
                transport.sendVoice(listener.handle(), ++ls.listenerSeq, part);
                voicePartsOut.incrementAndGet();
                voiceBytesOut.addAndGet(part.length);
            }
            voiceFramesOut.addAndGet(bundle.size());
        }
    }

    /**
     * May {@code next} ride as {@code primary}'s
     * catch-up? Only when it routes the same — same {@link VoiceFrameCodec#FLAG_ECHO} bit and same
     * proximity. Why: every routing decision of a pump (self loopback, the echo skip, the slot
     * gate, the prox cull, the slot release) reads the PRIMARY's bits only, and the catch-up is
     * appended for every talker the primary chose — so a mixed-route second frame broke the
     * per-frame echo law (a mic-test frame fanned out) and skipped the prox cull. A frame that
     * routes differently stays queued and is the next pump's primary, routed by its own bits.
     */
    private static boolean sameRoute(Pending primary, Pending next) {
        return ((primary.up().flags() ^ next.up().flags()) & VoiceFrameCodec.FLAG_ECHO) == 0
                && primary.up().proximity() == next.up().proximity();
    }

    private void assignGlobalSlots(VoiceConfig cfg, long nowMillis, long silenceMillis,
            List<PeerState> active) {
        int held = 0;
        for (PeerState st : peers.values()) {
            if (st.slotted) {
                long last;
                synchronized (st) {
                    last = st.lastFrameMillis;
                }
                // A holder who switched to PROXIMITY (released B, holds Space)
                // keeps lastFrameMillis fresh yet no longer needs the slot — release it now, not
                // after the 20 s tenure while waiters count global_trim.
                // An ECHO (mic-test) primary releases it
                // the same way — its frames reach nobody else, so holding the slot only starved waiters.
                boolean switchedToProx = st.primary != null && st.primary.up().proximity();
                boolean echoPrimary = st.primary != null
                        && (st.primary.up().flags() & VoiceFrameCodec.FLAG_ECHO) != 0;
                if (nowMillis - last > silenceMillis || switchedToProx || echoPrimary) {
                    st.slotted = false;
                } else {
                    held++;
                }
            }
        }
        List<PeerState> waiting = new ArrayList<>();
        for (PeerState st : active) {
            // An echo (mic-test) burst reaches nobody else — it never competes for a
            // global slot a real global talker would then lose to it.
            boolean echo = (st.primary.up().flags() & VoiceFrameCodec.FLAG_ECHO) != 0;
            if (!st.primary.up().proximity() && !st.slotted && !echo) {
                waiting.add(st);
            }
        }
        if (waiting.isEmpty()) {
            return;
        }
        if (held >= cfg.maxTalkers()) {
            // Contested: long-tenured holders yield to the back of the line.
            for (PeerState st : peers.values()) {
                if (st.slotted && nowMillis - st.slotSince >= GLOBAL_TENURE_MILLIS) {
                    st.slotted = false;
                    held--;
                    synchronized (st) {
                        st.burstStartMillis = nowMillis;
                    }
                    boolean isActiveGlobal = st.primary != null && !st.primary.up().proximity()
                            && (st.primary.up().flags() & VoiceFrameCodec.FLAG_ECHO) == 0;
                    if (isActiveGlobal) {
                        waiting.add(st);
                    }
                }
            }
        }
        waiting.sort(Comparator.comparingLong(st -> {
            synchronized (st) {
                return st.burstStartMillis;
            }
        }));
        for (PeerState st : waiting) {
            if (held < cfg.maxTalkers()) {
                st.slotted = true;
                st.slotSince = nowMillis;
                held++;
            } else {
                voiceGlobalTrims.incrementAndGet();
            }
        }
    }

    private static TalkerFrame frameOf(PeerState st, Pending p, VoicePositionSlot.View view,
            int front, int outMicros) {
        int ts = view.slotOfSeat(st.endpoint.seatId());
        long anchor = ts < 0 ? 0L : view.anchorOf(front, ts);
        if (anchor < 0L || anchor > 0xFFFF_FFFFL) {
            anchor = 0L;
        }
        // FLAG_ECHO is client→server only: strip it so no listener ever sees it.
        int flags = p.up().flags() & ~VoiceFrameCodec.FLAG_ECHO;
        // The stamps ride DOWN iff the opus still fits one part with them
        // (best-effort probe data; the codec refuses a stamped frame over the cap).
        boolean stamped = (flags & VoiceFrameCodec.FLAG_STAMP) != 0
                && p.up().opus().length <= VoiceFrameCodec.MAX_STAMPED_OPUS_BYTES;
        if (!stamped) {
            flags &= ~VoiceFrameCodec.FLAG_STAMP;
        }
        return new TalkerFrame(st.endpoint.seatId(), flags, p.up().burstId(),
                (int) (p.envelopeSeq() & 0xFFFF), anchor, p.up().opus(),
                stamped ? p.up().capMicros() : 0, stamped ? outMicros : 0);
    }

    /** One popped stamped frame into the meter (relay thread). */
    private void noteStamps(Pending p, int outMicros) {
        if (p == null || !p.up().stamped()) {
            return;
        }
        long capIn = (int) (p.arrivalMicros() - p.up().capMicros());
        long inOut = (int) (outMicros - p.arrivalMicros());
        if (stampN++ == 0) {
            stampCapInMin = capIn;
            stampCapInSum = capIn;
            stampCapInMax = capIn;
            stampInOutMin = inOut;
            stampInOutSum = inOut;
            stampInOutMax = inOut;
        } else {
            stampCapInSum += capIn;
            stampCapInMin = Math.min(stampCapInMin, capIn);
            stampCapInMax = Math.max(stampCapInMax, capIn);
            stampInOutSum += inOut;
            stampInOutMin = Math.min(stampInOutMin, inOut);
            stampInOutMax = Math.max(stampInOutMax, inOut);
        }
    }

    /** µs accumulators as {@code min/avg/max} ms, one decimal. */
    static String stampText(int n, long sumMicros, long minMicros, long maxMicros) {
        return tenthsMs(minMicros) + "/" + tenthsMs(sumMicros / n) + "/" + tenthsMs(maxMicros);
    }

    static String tenthsMs(long micros) {
        long t = Math.abs(micros + (micros < 0 ? -50L : 50L)) / 100L;
        return (micros < 0 ? "-" : "") + (t / 10) + "." + (t % 10);
    }

    // ---- operator retune -------------------------------------------------------------------

    /**
     * {@code "<knob> <value>"} from the operator file (sim thread). Mints a new sheet and
     * re-sends VCFG to every live session; returns a one-line summary or the refusal reason
     * (operator input is fail-soft — the caller logs).
     */
    public String retune(String knobValueText) {
        Objects.requireNonNull(knobValueText, "knobValueText");
        String[] parts = knobValueText.trim().split("\\s+");
        if (parts[0].equals(SET_WORD)) {
            return retuneSet(parts);
        }
        if (parts.length != 2) {
            return "refused: expected '<knob> <value>' (knobs: " + VoiceConfig.KNOB_NAMES + ")";
        }
        VoiceConfig next;
        try {
            next = config.retune(parts[0], parts[1]);
        } catch (IllegalArgumentException refused) {
            return "refused: " + refused.getMessage();
        }
        int sent = install(next);
        return "applied " + parts[0] + "=" + parts[1] + " vcfg_sent=" + sent + " " + next.summary();
    }

    /** The first token of the atomic multi-knob
     *  form: {@link #retune} text {@code "set k=v k=v ..."} (the {@code voice set} operator verb). */
    public static final String SET_WORD = "set";

    /** {@code set k=v ...} — every pair parsed, ONE candidate sheet built
     *  and jointly validated ({@link VoiceConfig#retuneAll}); a refusal leaves the standing sheet
     *  whole (nothing applied), else the whole sheet swaps in one assignment + one VCFG fan. */
    private String retuneSet(String[] parts) {
        VoiceConfig next;
        try {
            next = config.retuneAll(VoiceConfig.parseSetPairs(parts, 1));
        } catch (IllegalArgumentException refused) {
            return "refused set (nothing applied): " + refused.getMessage();
        }
        int sent = install(next);
        StringBuilder pairs = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            pairs.append(i > 1 ? "," : "").append(parts[i]);
        }
        return "applied set " + pairs + " vcfg_sent=" + sent + " " + next.summary();
    }

    /** Swap in a validated sheet and re-send VCFG to every live session; returns the send count. */
    private int install(VoiceConfig next) {
        config = next;
        byte[] frame = VoiceConfigFrameCodec.encode(next);
        int sent = 0;
        for (Endpoint ep : live) {
            transport.sendCtrl(ep.handle(), frame);
            sent++;
        }
        return sent;
    }

    // ---- mute --------------------------------------------------------------------------------

    /** What one {@link #toggleMute} did — the NEW
     *  state, or {@link #REFUSED} = nothing written (an id was not in the live set). */
    public enum MuteToggle { MUTED, UNMUTED, REFUSED }

    /**
     * Toggle the listener's mute of the target; returns the NEW state, or {@link MuteToggle#REFUSED}
     * with NO write when either id is absent from the live set (read once, inside this monitor).
     * Why: {@link VoiceMuteCommand} resolves the
     * target from an unlocked {@link #liveEndpoints} snapshot, and another thread can drop either party in
     * between (the serve-thread ban, a timer-thread death); an unguarded toggle then wrote a DEAD id into a row after
     * {@link #detachMutes} had swept it — a "muted" reply for a player who returns audible, an
     * "unmute" that re-adds the dead id, or a dead listener's row nothing purges. ORDER LAW it relies
     * on: the door drops an id from the live set ({@link #setLive}) BEFORE it calls
     * {@link #detachMutes} for that id, and both this and the detach hold this monitor — so a toggle
     * that still sees the id live finishes before the detach sweeps it, and one that runs after the
     * drop is refused. Session ids are signed; compared with {@code ==}.
     */
    public synchronized MuteToggle toggleMute(long listenerSessionId, long targetSessionId) {
        Endpoint[] liveNow = live;
        boolean listenerLive = false;
        boolean targetLive = false;
        for (Endpoint e : liveNow) {
            listenerLive |= e.sessionId() == listenerSessionId;
            targetLive |= e.sessionId() == targetSessionId;
        }
        if (!listenerLive || !targetLive) {
            return MuteToggle.REFUSED;
        }
        Set<Long> set = mutes.computeIfAbsent(listenerSessionId, id -> ConcurrentHashMap.newKeySet());
        if (set.remove(targetSessionId)) {
            return MuteToggle.UNMUTED;
        }
        set.add(targetSessionId);
        return MuteToggle.MUTED;
    }

    public boolean isMuted(long listenerSessionId, long targetSessionId) {
        Set<Long> set = mutes.get(listenerSessionId);
        return set != null && set.contains(targetSessionId);
    }

    /** REPLACE the listener's mute-menu pin with the
     *  handles its latest {@code /mute} menu minted (empty = no observer row ⇒ no pin). Reader
     *  thread (the {@code /mute} handler). {@link #detachMutes} runs there for a table drop but on the
     *  SERVE thread for a ban, so a pin CAN be set after its listener's
     *  ban-time detach: nobody reads it (only that listener's own next {@code /mute #} would) and it
     *  goes at the dead session's table sweep, which detaches again. */
    synchronized void setMuteMenuPin(long listenerSessionId, Map<Long, Long> handles) {
        if (handles.isEmpty()) {
            muteMenuPins.remove(listenerSessionId);
        } else {
            muteMenuPins.put(listenerSessionId, Map.copyOf(handles));
        }
    }

    /** The listener's mute-menu pin (immutable), or
     *  {@code null} = none. */
    public Map<Long, Long> muteMenuPin(long listenerSessionId) {
        return muteMenuPins.get(listenerSessionId);
    }

    /** One session's mute rows lifted off the table — what it muted and who
     *  muted it — for the door to re-attach under a takeover successor. {@code ownerId} = the
     *  detached session; every id is a RAW session id, which the
     *  door rewrites with {@link #remap} when one of them is re-minted while the carry is stashed. */
    public record MuteCarry(long ownerId, Set<Long> mutedTargets, Set<Long> mutedBy) {

        /**
         * this carry with session id
         * {@code from} replaced by {@code to} in BOTH sets; {@code this} when neither holds it.
         * Why: a stashed carry names the other party by its raw session id, and a reconnect /
         * takeover mints a NEW id — without the rewrite, a muter who reconnected while the
         * muted player was banned found the mute silently dropped at that player's rejoin.
         */
        public MuteCarry remap(long from, long to) {
            if (!mutedTargets.contains(from) && !mutedBy.contains(from)) {
                return this;
            }
            return new MuteCarry(ownerId, swap(mutedTargets, from, to), swap(mutedBy, from, to));
        }

        /** The door's placeholder for a session that detached NO rows
         *  (its counterparty dropped first) while some OTHER stashed carry still names its id — stashed
         *  under its table key so its next fresh admit still {@link #remap remaps} the id in those stashes;
         *  it attaches nothing. */
        public static MuteCarry tombstone(long ownerId) {
            return new MuteCarry(ownerId, Set.of(), Set.of());
        }

        /** No rows either way = a {@link #tombstone}. */
        public boolean isTombstone() {
            return mutedTargets.isEmpty() && mutedBy.isEmpty();
        }

        /**
         * This STASHED carry plus one relation with {@code other}, a
         * session that re-attached while this carry's owner was away — {@code ownerMutes} = the owner muted
         * {@code other}, else {@code other} muted the owner. Why: the relation lived only in {@code other}'s
         * carry, and that carry is consumed at its attach; without the hand-over the mute died when
         * {@code other} returned first (the owner's later admit found no stash naming it).
         */
        public MuteCarry withRelation(long other, boolean ownerMutes) {
            Set<Long> grown = new HashSet<>(ownerMutes ? mutedTargets : mutedBy);
            grown.add(other);
            return ownerMutes ? new MuteCarry(ownerId, Set.copyOf(grown), mutedBy)
                    : new MuteCarry(ownerId, mutedTargets, Set.copyOf(grown));
        }

        /** This carry with only the targets {@code held} accepts (the muters untouched — {@link #attachMutes}
         *  skips and counts those itself); {@code this} when every target is held. */
        public MuteCarry heldTargetsOnly(LongPredicate held) {
            Set<Long> kept = new HashSet<>();
            for (long target : mutedTargets) {
                if (held.test(target)) {
                    kept.add(target);
                }
            }
            return kept.size() == mutedTargets.size() ? this : new MuteCarry(ownerId, Set.copyOf(kept), mutedBy);
        }

        private static Set<Long> swap(Set<Long> ids, long from, long to) {
            if (!ids.contains(from)) {
                return ids;
            }
            Set<Long> out = new HashSet<>(ids);
            out.remove(from);
            out.add(to);
            return Set.copyOf(out);
        }
    }

    /** What one {@link #attachMutes}
     *  did — targets added to the successor's row, muter rows the successor joined, muters
     *  skipped (not held by the door any more) — counts only, for the door's MUTE-CARRY row. */
    public record Attached(int targets, int mutedBy, int skippedBy) {
    }

    /**
     * Door thread, at the TABLE drop of {@code sessionId}: remove its listener row and its
     * membership in every other listener's set, returned as a carry (null when it had none).
     * The carry rides the door's dead PeerSession object: a same-key takeover hands it to
     * {@link #attachMutes}; any other drop (a ban detaches at the ban itself) stashes it under the
     * door's table key (carriedMutesByKey, cap 64) for that key's next fresh admission. ORDER LAW:
     * the door calls this only after {@link #setLive} dropped {@code sessionId},
     * so a racing {@link #toggleMute} is either swept here or refused.
     */
    public synchronized MuteCarry detachMutes(long sessionId) {
        muteMenuPins.remove(sessionId); // The pin dies here, never carried
        Set<Long> targets = mutes.remove(sessionId);
        Set<Long> mutedBy = new HashSet<>();
        for (Map.Entry<Long, Set<Long>> row : mutes.entrySet()) {
            if (row.getValue().remove(sessionId)) {
                mutedBy.add(row.getKey());
            }
        }
        if ((targets == null || targets.isEmpty()) && mutedBy.isEmpty()) {
            return null;
        }
        return new MuteCarry(sessionId, targets == null ? Set.of() : Set.copyOf(targets),
                Set.copyOf(mutedBy));
    }

    /**
     * Door thread, after a takeover or by-key admit: {@code successorId} inherits the detached
     * rows — its own mute list and its place in every muter's set. A muter joins only when the
     * door's {@code muterLive} still holds it: its row is
     * created if absent (a ban-time detach or the muter's own takeover may have dropped it — the
     * door remapped the carry to the muter's current id); a muter the door no longer holds is
     * skipped, never resurrected. {@code carry == null} = nothing to attach ⇒ {@code (0, 0, 0)}.
     */
    public synchronized Attached attachMutes(long successorId, MuteCarry carry, LongPredicate muterLive) {
        if (carry == null) {
            return new Attached(0, 0, 0);
        }
        int targets = 0;
        for (long target : carry.mutedTargets()) {
            if (target != successorId) {
                mutes.computeIfAbsent(successorId, id -> ConcurrentHashMap.newKeySet()).add(target);
                targets++;
            }
        }
        int mutedBy = 0;
        int skippedBy = 0;
        for (long listener : carry.mutedBy()) {
            if (listener != successorId && muterLive.test(listener)) {
                mutes.computeIfAbsent(listener, id -> ConcurrentHashMap.newKeySet()).add(successorId);
                mutedBy++;
            } else {
                skippedBy++;
            }
        }
        return new Attached(targets, mutedBy, skippedBy);
    }

    // ---- meters ------------------------------------------------------------------------------

    /** ONE line, counts only — never audio. Appended fields only (grammar rule). */
    public String meterLine() {
        return "voice-relay in=" + voiceFramesIn.get() + " out=" + voiceFramesOut.get()
                + " parts=" + voicePartsOut.get() + " bytes_out=" + voiceBytesOut.get()
                + " rate_drop=" + voiceRateDropped.get() + " seq_drop=" + voiceSeqDropped.get()
                + " stale_round=" + voiceStaleRound.get()
                + " over_cap=" + voiceOverCapDropped.get()
                + " fifo_drop=" + voiceFifoDropped.get()
                + " global_trim=" + voiceGlobalTrims.get()
                + " prox_trim=" + voiceProxTrims.get() + " talkers=" + talkersLastPump
                + " disabled_drop=" + voiceDisabledDropped.get()
                + " seat_unfit=" + voiceSeatUnfit.get() + " pump_err=" + voicePumpErrors.get()
                + " live=" + live.length;
    }

    public long framesIn() { return voiceFramesIn.get(); }
    public long framesOut() { return voiceFramesOut.get(); }
    public long partsOut() { return voicePartsOut.get(); }
    public long rateDropped() { return voiceRateDropped.get(); }
    public long seqDropped() { return voiceSeqDropped.get(); }
    public long staleRound() { return voiceStaleRound.get(); }
    public long overCapDropped() { return voiceOverCapDropped.get(); }
    public long fifoDropped() { return voiceFifoDropped.get(); }
    public long globalTrims() { return voiceGlobalTrims.get(); }
    public long proxTrims() { return voiceProxTrims.get(); }
    public long disabledDropped() { return voiceDisabledDropped.get(); }

    /** Test hook: the mute table view (read-only copy). */
    Map<Long, Set<Long>> mutesForTest() {
        return Map.copyOf(mutes);
    }
}
