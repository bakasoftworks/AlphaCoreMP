package cc.starsector.client.voice;

import cc.starsector.client.MatchConfigFromWire;
import cc.starsector.client.PilotWatchLog;
import cc.starsector.client.net.VoiceConfigReader;
import cc.starsector.server.runtime.VoiceFrameCodec;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The client voice orchestrator:
 * owns the published sheet, the enable gate, the net-to-audio queue, the meters, the position
 * slot and the uplink. Game-free (no render/audio code here — those ride {@code VoiceCapture} /
 * {@code VoicePlayback}, started from {@link #maybeStart}) so the whole lifecycle runs under a
 * plain {@code main} selftest. Owned by the datagram session (PRDS) so its lifetime IS the
 * session's: constructed with the session, closed in the session's {@code close()}; a B4
 * re-admit keeps the same instance and just re-seeds the WELCOME flag.
 *
 * <p><b>Enable gate (D10):</b> {@link #enabled()} = WELCOME matchFlags bit 2
 * ({@code FLAG_VOICE_ENABLED} 0x04, pushed by the session at both admit sites) AND a VCFG sheet
 * has arrived AND the {@code -Dcc.voice.off} sysprop is not set (any value other than
 * {@code false}, so a bare {@code -Dcc.voice.off} works). A later VCFG = retune: it replaces the
 * sheet and bumps {@link #configGeneration()} so capture rebuilds its encoder and playback resets
 * streams when {@code frameMs} changes.
 *
 * <p><b>Threads (D5):</b> {@link #onVoiceDown} runs on the session's net reader thread: decode
 * ({@code VoiceFrameCodec.decodeDown}), push each talker frame into the bounded
 * {@link TalkerFrameQueue} (drop-oldest, never blocks), done. A malformed VCDN/VCFG is a SERVER
 * bug the client must survive: count + drop + ONE log line per session (never the session's
 * fail-closed default, which is for contract changes on the reliable lane). Opus bytes are never
 * logged.
 */
public final class VoiceClient {

    private final AtomicReference<VoiceSheet> sheet = new AtomicReference<>();
    private final AtomicInteger configGeneration = new AtomicInteger();
    private final AtomicBoolean welcomeVoice = new AtomicBoolean();
    private final boolean off;
    private final TalkerFrameQueue queue = new TalkerFrameQueue();
    private final VoiceMeters meters = new VoiceMeters();
    private final AtomicReference<VoicePositions> positions =
            new AtomicReference<>(VoicePositions.EMPTY);
    private final VoiceUplink uplink;

    private final AtomicLong downFrames = new AtomicLong();
    private final AtomicLong downRefused = new AtomicLong();
    private final AtomicLong configRefused = new AtomicLong();
    private final AtomicBoolean downRefusedLogged = new AtomicBoolean();
    private final AtomicBoolean configRefusedLogged = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** L5: the one lock every start/stop of the audio halves holds (net + render threads). */
    private final Object lifecycle = new Object();

    // ---- integration state (brief D8/D9/D10/D11) — set by the host BEFORE
    // the gate opens (settings at session bind, render thread) or updated later (own seat id).
    /** Match-start settings (D11); DEFAULTS until the presence lane hands the loaded file over. */
    private volatile VoiceClientSettings settings = VoiceClientSettings.DEFAULTS;
    /** Own seat id (VCDN seatId domain) or -1 unknown; the playback echo gate reads it live. */
    private final AtomicInteger ownSeatId = new AtomicInteger(-1);
    /** Send-nanos by VCUP envelope seq, stamped by the session's {@code sendVoice} (D8 echo rtt). */
    private final EchoClock echo = new EchoClock();
    /** The pure PTT key law (D9); fed by the render-thread input relay, read by capture. */
    private final VoicePtt ptt = VoicePtt.fromSysprops();
    private volatile VoicePlayback playback;
    private volatile VoiceCapture capture;
    /** The FFM loopback ear (kit Java 25 only); null = off / unavailable. */
    private volatile AutoCloseable probeEar;

    /** @param uplink the session's thread-safe {@code sendVoice} (VOICE channel, own seq). */
    public VoiceClient(VoiceUplink uplink) {
        this(uplink, System.getProperty("cc.voice.off"), cc.starsector.client.ObserverMode.ON);
    }

    /** Selftest seam: the sysprop value injected, never an observer. */
    public VoiceClient(VoiceUplink uplink, String offProperty) {
        this(uplink, offProperty, false);
    }

    /**
     * Selftest seam: sysprop + observer injected. An observer window once played voice twice:
     * the OBSERVER joined the lane like
     * a seat — {@code maybeStart} opened playback unconditionally and capture per {@code micOn},
     * the observer bit was never consulted — so it captured a second mic on the same box and
     * rendered everyone's playback. Observer folds into {@code off}, the existing full kill:
     * {@code enabled()} false ⇒ neither half opens, no VCUP leaves, no VCDN queues; every
     * presence/HUD call site is already {@code started()}/null-gated (recon).
     * Server needs nothing.
     */
    public VoiceClient(VoiceUplink uplink, String offProperty, boolean observer) {
        this.uplink = uplink;
        boolean sysOff = offProperty != null && !"false".equalsIgnoreCase(offProperty.trim());
        this.off = sysOff || observer;
        if (observer) {
            PilotWatchLog.info("voice: observer => capture OFF playback OFF");
        }
        if (VoiceProbe.toneMode() != VoiceProbe.TONE_OFF || VoiceProbe.earMode() != VoiceProbe.EAR_OFF) {
            // The mouth-to-ear meter's rows ride the mod sidelog.
            VoiceProbe.bindLog(PilotWatchLog::info);
            PilotWatchLog.info("voice probe: " + VoiceProbe.describe());
        }
    }

    // ------------------------------------------------------------------ inbound (net thread)

    /** WELCOME matchFlags (admit + B4 re-admit) — only bit 2 is ours. */
    public void onWelcomeFlags(int matchFlags) {
        welcomeVoice.set((matchFlags & MatchConfigFromWire.FLAG_VOICE_ENABLED) != 0);
        maybeStart();
    }

    /** One raw VCFG CTRL payload off the route: decode under the count-and-drop law. */
    public void onVoiceConfigFrame(byte[] payload) {
        VoiceSheet next;
        try {
            next = VoiceConfigReader.read(payload);
        } catch (RuntimeException refused) { // IAE today; the VCDN sibling's breadth
            configRefused.incrementAndGet();
            if (configRefusedLogged.compareAndSet(false, true)) {
                PilotWatchLog.info("voice: VCFG refused (server sheet bug, dropped): "
                        + oneLine(refused.getMessage()));
            }
            return;
        }
        onVoiceConfig(next);
    }

    /** A decoded sheet: publish + bump the generation (a later sheet = retune). */
    public void onVoiceConfig(VoiceSheet next) {
        if (next == null || closed.get()) {
            return;
        }
        sheet.set(next);
        configGeneration.incrementAndGet();
        maybeStart();
    }

    /** One VOICE-channel envelope payload (a VCDN part) off the reader thread. */
    public void onVoiceDown(byte[] payload) {
        if (closed.get()) {
            return;
        }
        VoiceFrameCodec.VoiceDown down;
        try {
            down = VoiceFrameCodec.decodeDown(payload);
        } catch (RuntimeException refused) { // IllegalArgumentException and any sibling
            downRefused.incrementAndGet();
            if (downRefusedLogged.compareAndSet(false, true)) {
                PilotWatchLog.info("voice: VCDN refused (server relay bug, dropped): "
                        + oneLine(refused.getMessage()));
            }
            return;
        }
        downFrames.incrementAndGet();
        if (!enabled()) {
            return; // no sheet / voice off: nothing can play it, do not queue
        }
        for (VoiceFrameCodec.TalkerFrame frame : down.talkers()) {
            queue.offer(frame);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Called whenever an input of {@link #enabled()} flips; starts the audio halves the FIRST
     * time the gate opens (and again after a {@code /settings} voice OFF→ON, which resets
     * {@code started}). Idempotent; net thread (WELCOME/VCFG) or render thread (applySettings) —
     * every start/stop of the audio halves runs under {@link #lifecycle}.
     */
    private void maybeStart() {
        synchronized (lifecycle) {
            if (!enabled() || closed.get()) {
                return;
            }
            if (!started.compareAndSet(false, true)) {
                return;
            }
            // L3(d) integration (brief D1/D2/D10): playback ALWAYS (mic.on gates capture only);
            // the sink comes from VoiceSinkFactory (the FFM WASAPI dragon on the
            // kit's Java-25 JRE, the pure-JDK SourceDataLine on the stock 17 JVM); positions/
            // volume/seat/mic gain are read live through suppliers so the render thread's
            // per-frame publish and a /settings pick never need a restart. Retune on a later
            // VCFG rides configGeneration inside both threads.
            VoicePlayback pb = new VoicePlayback(queue, this::sheet, this::configGeneration,
                    VoiceSinkFactory.create(PilotWatchLog::info), positions::get, meters, ownSeatId::get,
                    () -> settings, echo, PilotWatchLog::info);
            playback = pb;
            pb.start();
            startCapture(settings);
            if (VoiceProbe.earMode() == VoiceProbe.EAR_BOTH) {
                // The loopback ear (one invokestatic into ffm; null when unavailable).
                probeEar = VoiceProbeEarFactory.start(PilotWatchLog::info);
            }
        }
    }

    /** Under {@link #lifecycle}: the capture half for {@code s} (mic.on gates it). */
    private void startCapture(VoiceClientSettings s) {
        if (s.micOn) {
            // Echo RTT is stamped at the ENVELOPE seq by the session's sendVoice (echo().noteSent),
            // never here — the capture has no envelope seq to key it on.
            VoiceCapture cap = new VoiceCapture(uplink, this::sheet, this::configGeneration, ptt,
                    meters, () -> settings, PilotWatchLog::info);
            capture = cap;
            cap.start();
        } else {
            meters.captureState.set(VoiceMeters.CAPTURE_OFF);
            PilotWatchLog.info("voice: playback only (mic.on=false)");
        }
    }

    /**
     * L5: a {@code /settings} pick, render thread. Stores the value, then
     * reconciles the audio halves under {@link #lifecycle}: voice OFF while started ⇒ both
     * threads stop, queue/meters clear, {@code started} resets so a later ON restarts; ON ⇒
     * {@link #maybeStart}; a mic.on / mic.device change while started ⇒ capture only restarts.
     * Volume, mic gain and the HUD row are read live by their consumers — no restart.
     * A closed client only stores the value.
     */
    public void applySettings(VoiceClientSettings next) {
        if (next == null) {
            return;
        }
        synchronized (lifecycle) {
            VoiceClientSettings prev = settings;
            settings = next;
            if (closed.get()) {
                return;
            }
            if (!started.get()) {
                maybeStart();
                return;
            }
            if (!next.voiceOn) {
                closeThreads();
                queue.clear();
                meters.clear();
                started.set(false);
                PilotWatchLog.info("voice: stopped (voice.on=false)");
                return;
            }
            if (prev.micOn != next.micOn || !prev.micDevice.equals(next.micDevice)) {
                VoiceCapture cap = capture;
                capture = null;
                if (cap != null) {
                    cap.close();
                }
                startCapture(next);
            }
        }
    }

    /** Idempotent; stops the audio halves, drops held PTT keys, clears the queue/meters. Safe from any thread. */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ptt.releaseAll();
        synchronized (lifecycle) {
            closeThreads();
        }
        queue.clear();
        meters.clear();
        positions.set(VoicePositions.EMPTY);
    }

    /** Under {@link #lifecycle}: stop + forget both halves. */
    private void closeThreads() {
        AutoCloseable ear = probeEar;
        probeEar = null;
        if (ear != null) {
            try {
                ear.close();
            } catch (Exception e) {
                // best effort (the ear's close never throws by contract)
            }
        }
        VoiceCapture cap = capture;
        capture = null;
        if (cap != null) {
            cap.close();
        }
        VoicePlayback pb = playback;
        playback = null;
        if (pb != null) {
            pb.close();
        }
    }

    // ------------------------------------------------------------------ integration seams

    /** D11: the match-start settings; call BEFORE the gate can open (session bind) — capture
     * reads its device at open + gain live, playback reads volume live. Later changes go
     * through {@link #applySettings} (which also reconciles the running audio halves). */
    public void setSettings(VoiceClientSettings next) {
        if (next != null) {
            settings = next;
        }
    }

    public VoiceClientSettings settings() {
        return settings;
    }

    /** Own seat id in the VCDN seatId domain (-1 = unknown); refreshed by the presence lane. */
    public void setOwnSeatId(int seatId) {
        ownSeatId.set(seatId);
    }

    public int ownSeatId() {
        return ownSeatId.get();
    }

    public EchoClock echo() {
        return echo;
    }

    public VoicePtt ptt() {
        return ptt;
    }

    /** Playback's granted line buffer in bytes (-1 before open) — the one-line debrief number. */
    public int playbackGrantedBufferBytes() {
        VoicePlayback pb = playback;
        return pb == null ? -1 : pb.grantedBufferBytes();
    }

    // ------------------------------------------------------------------ reads

    /** WELCOME voice bit AND a sheet AND not {@code -Dcc.voice.off} AND settings {@code voice.on}. */
    public boolean enabled() {
        return welcomeVoice.get() && sheet.get() != null && !off && settings.voiceOn;
    }

    /** The current sheet or null before the first VCFG. */
    public VoiceSheet sheet() {
        return sheet.get();
    }

    /** Bumped on every accepted VCFG (0 = none yet). */
    public int configGeneration() {
        return configGeneration.get();
    }

    public boolean voiceOff() {
        return off;
    }

    public boolean welcomeVoiceEnabled() {
        return welcomeVoice.get();
    }

    public boolean started() {
        return started.get();
    }

    public boolean closed() {
        return closed.get();
    }

    public TalkerFrameQueue queue() {
        return queue;
    }

    public VoiceMeters meters() {
        return meters;
    }

    public AtomicReference<VoicePositions> positions() {
        return positions;
    }

    public VoiceUplink uplink() {
        return uplink;
    }

    public long downFrames() {
        return downFrames.get();
    }

    public long downRefused() {
        return downRefused.get();
    }

    public long configRefused() {
        return configRefused.get();
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(s.length(), 120));
        for (int i = 0; i < s.length() && sb.length() < 120; i++) {
            char c = s.charAt(i);
            sb.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return sb.toString();
    }
}
