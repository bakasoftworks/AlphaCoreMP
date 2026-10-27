package cc.starsector.client.join;

import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

import cc.starsector.client.voice.CaptureFormatLadder;
import cc.starsector.client.voice.PcmResampler;
import cc.starsector.client.voice.VoiceClientSettings;

/**
 * The joiner's microphone laws, Swing-free (GUI v2):
 * the capture-mixer enumeration, the saved-device preselect, the file value of a pick, and the Test-level worker
 * — lifted VERBATIM out of {@link JoinVoicePanel} so the retained Swing panel and the
 * browser joiner's voice bridge ({@code api/voice/devices} / {@code api/voice/test} / {@code api/voice/level})
 * run ONE law. Lives in {@code join/**} (b-deny-EXEMPT, join-leak-gated); touches {@code javax.sound.sampled}
 * ({@code java.desktop}, present on the kit JRE) and the game-free {@link VoiceClientSettings} value only.
 *
 * <p>Device list = every mixer offering a {@link TargetDataLine} (the in-game capture opens the same line type
 * through the same {@link CaptureFormatLadder}; port-only mixers are hidden because the game could never open
 * them). The in-game pick is a case-insensitive substring of the mixer name ({@code CaptureFormatLadder.pickMixer}),
 * so the value written is the ASCII reduction ({@link JoinVoiceSettings#asciiDeviceName}). Index law, both shells:
 * 0 = the system default, {@code i} = {@code mixers.get(i - 1)}.
 *
 * <p>The {@link Tester} is a daemon thread reading 20 ms chunks (converted to 48 k mono by {@link PcmResampler}
 * exactly as in-game), applying the live {@link Tester#gain}, RMS → dBFS → bar (−60 dBFS = 0, 0 dBFS = 100)
 * through a {@link Listener} on the worker thread; a non-native format is named in the status line, a device that
 * opens no ladder format says so ({@link #CANNOT_OPEN}) and the run ends. Every callback carries the run id and
 * fires only while that run is current — a listener that hops threads (the Swing panel's {@code invokeLater})
 * re-checks {@link Tester#isCurrent} on arrival, the panel's generation guard kept.
 */
final class JoinVoiceCore {
    static final String DEFAULT_DEVICE = "System default";
    static final String LISTENING = "Listening... speak into the microphone";
    static final String CANNOT_OPEN =
            "This device opens no 16-bit PCM format - the game will run without a microphone";
    static final String RESAMPLED_SUFFIX = ", resampled to 48 kHz mono)... speak into the microphone";
    /** The empty-list wording: the default entry alone remains pickable. */
    static final String NO_DEVICES = "no microphone found - only the system default can be tried";
    /** One 20 ms frame at 48 kHz mono — the in-game capture's chunk. */
    static final int FRAME = 960;
    /** The gain slider's range in percent (the Swing slider's 0..200; the file stores the factor). */
    static final int GAIN_PERCENT_MAX = 200;
    static final int GAIN_PERCENT_DEFAULT = 100;

    private JoinVoiceCore() {
    }

    // ------------------------------------------------------------------ the device list

    /** Every mixer that offers a capture line; an empty list when there is no audio subsystem. */
    static List<Mixer.Info> captureMixers() {
        List<Mixer.Info> out = new ArrayList<>();
        Line.Info want = new Line.Info(TargetDataLine.class);
        try {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                try {
                    Mixer m = AudioSystem.getMixer(info);
                    if (m.getTargetLineInfo(want).length > 0) {
                        out.add(info);
                    }
                } catch (RuntimeException ignored) {
                    // a mixer that refuses to describe itself is not a pickable mic
                }
            }
        } catch (RuntimeException ignored) {
            // no audio subsystem: an empty list, the default entry stays
        }
        return out;
    }

    /** The panel's preselect law: the first mixer whose name contains the saved device, case-insensitive
     *  (the in-game substring match); 0 = the default entry when nothing is saved or nothing matches. */
    static int preselect(List<Mixer.Info> mixers, String savedDevice) {
        if (savedDevice == null || savedDevice.isEmpty()) {
            return 0;
        }
        String want = savedDevice.toLowerCase();
        for (int i = 0; i < mixers.size(); i++) {
            if (mixers.get(i).getName().toLowerCase().contains(want)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** The picked mixer's file value: "" for the default entry or an index off the list, else the ASCII reduction. */
    static String deviceValue(List<Mixer.Info> mixers, int index) {
        if (index <= 0 || index > mixers.size()) {
            return "";
        }
        return JoinVoiceSettings.asciiDeviceName(mixers.get(index - 1).getName());
    }

    /** True when a non-default pick reduced to "" (the one-line note: the default mixer will be used). */
    static boolean deviceNameLost(List<Mixer.Info> mixers, int index) {
        return index > 0 && index <= mixers.size() && deviceValue(mixers, index).isEmpty();
    }

    static Mixer.Info mixerAt(List<Mixer.Info> mixers, int index) {
        return index > 0 && index <= mixers.size() ? mixers.get(index - 1) : null;
    }

    /**
     * The {@code api/voice/devices} body (pure over the list, selftested): {@code devices} = {@code [{index, name,
     * value, lost}]} (name = the mixer's own name, DATA the SPA lands via textContent; value = what Launch would
     * write; lost = a name that reduces to the default), {@code selected} = {@link #preselect}, {@code line} =
     * the count or {@link #NO_DEVICES}.
     */
    static String devicesJson(List<Mixer.Info> mixers, String savedDevice) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < mixers.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            String value = deviceValue(mixers, i + 1);
            arr.append(new JoinJson().put("index", i + 1).put("name", mixers.get(i).getName())
                    .put("value", value).put("lost", value.isEmpty()).toString());
        }
        arr.append(']');
        return new JoinJson().put("ok", true).put("defaultLabel", DEFAULT_DEVICE).putRaw("devices", arr.toString())
                .put("selected", preselect(mixers, savedDevice))
                .put("line", mixers.isEmpty() ? NO_DEVICES : mixers.size() + " microphone(s) found").toString();
    }

    // ------------------------------------------------------------------ the browser joiner's pick

    /** The browser joiner's voice pick out of its Launch fields: the mixer index, enable, gain percent, quality. */
    static final class Pick {
        final int device;
        final boolean on;
        final int gainPercent;
        /** {@code VoiceClientSettings.SERVER_DEFAULT} (−1) or complexity 0..10. */
        final int complexity;

        Pick(int device, boolean on, int gainPercent, int complexity) {
            this.device = device;
            this.on = on;
            this.gainPercent = gainPercent;
            this.complexity = complexity;
        }

        float gainFactor() {
            return gainPercent / 100f;
        }
    }

    /**
     * Strict parse of the four {@code api/launch} voice fields ("" = the default for each: device 0, off, 100 %,
     * server default). A value outside its law is an {@link IllegalArgumentException} naming the FIELD (the
     * routes' 422 path) — never its content.
     */
    static Pick parsePick(String device, String on, String gain, String quality) {
        int d = parseBounded(device, "voiceDevice", 0, 0, 999);
        boolean o;
        if (on == null || on.isEmpty() || "off".equalsIgnoreCase(on)) {
            o = false;
        } else if ("on".equalsIgnoreCase(on)) {
            o = true;
        } else {
            throw new IllegalArgumentException("field voiceOn is malformed");
        }
        int g = parseBounded(gain, "voiceGain", GAIN_PERCENT_DEFAULT, 0, GAIN_PERCENT_MAX);
        int q = parseBounded(quality, "voiceQuality", VoiceClientSettings.SERVER_DEFAULT,
                VoiceClientSettings.SERVER_DEFAULT, VoiceClientSettings.MAX_COMPLEXITY);
        return new Pick(d, o, g, q);
    }

    /** An integer field: "" / null = {@code dflt}; a non-integer or a value off {@code [min, max]} names the field. */
    static int parseBounded(String raw, String field, int dflt, int min, int max) {
        if (raw == null || raw.trim().isEmpty()) {
            return dflt;
        }
        int v;
        try {
            v = Integer.parseInt(raw.trim());
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("field " + field + " is malformed");
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException("field " + field + " is out of range");
        }
        return v;
    }

    // ------------------------------------------------------------------ the Test worker

    /** Callbacks from the worker thread; {@code runId} = the run they belong to ({@link Tester#isCurrent}). */
    interface Listener {
        void level(long runId, int percent);

        void status(long runId, String line);

        /** The device opened no ladder format: the run is over ({@link #CANNOT_OPEN}). */
        void failed(long runId, String line);
    }

    /** One Test worker per window; {@link #start} / {@link #stop} are safe from any thread and repeatable. */
    static final class Tester {
        /** The live gain factor (the slider's value / 100), read per chunk. */
        volatile float gain = 1f;
        private volatile Thread worker;
        private volatile TargetDataLine line;
        private volatile long generation;

        /** Stops any earlier run, starts a new one on {@code mixer} (null = the system default); returns its id. */
        synchronized long start(Mixer.Info mixer, Listener listener) {
            stop();
            final long gen = ++generation;
            listener.status(gen, LISTENING);
            Thread t = new Thread(() -> run(gen, mixer, listener), "cc-join-mic-test");
            t.setDaemon(true);
            worker = t;
            t.start();
            return gen;
        }

        /** Stop the worker and close the line; safe to call repeatedly. */
        synchronized void stop() {
            generation++;
            Thread t = worker;
            worker = null;
            TargetDataLine l = line;
            line = null;
            if (l != null) {
                try {
                    l.stop();
                    l.close();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            if (t != null) {
                t.interrupt();
            }
        }

        boolean isCurrent(long runId) {
            return runId == generation;
        }

        /** True while a run's worker is alive (negotiating or reading); false after {@link #stop} or a failure. */
        boolean running() {
            Thread t = worker;
            return t != null && t.isAlive();
        }

        private void run(long gen, Mixer.Info mixer, Listener listener) {
            TargetDataLine l;
            PcmResampler rs;
            String opened;
            try {
                // Same ladder as the in-game capture, so the status names the match's format.
                CaptureFormatLadder.Opened o = CaptureFormatLadder.open(mixer, 48000 / 5);
                l = o.line;
                rs = o.resampler();
                opened = rs.identity() ? null : CaptureFormatLadder.describe(o.format);
            } catch (Exception e) {
                if (gen == generation) {
                    listener.failed(gen, CANNOT_OPEN);
                }
                return;
            }
            if (gen != generation) {
                // stopped while negotiating: never clobber a newer worker's line
                try {
                    l.close();
                } catch (RuntimeException ignored) {
                    // best effort
                }
                return;
            }
            line = l;
            if (opened != null && gen == generation) {
                listener.status(gen, "Listening (" + opened + RESAMPLED_SUFFIX);
            }
            byte[] raw = new byte[rs.maxInputFor(FRAME) * rs.bytesPerFrame];
            short[] pcm = new short[FRAME];
            try {
                while (gen == generation && !Thread.currentThread().isInterrupted()) {
                    int inFrames = rs.inputNeeded(FRAME);
                    int bytes = inFrames * rs.bytesPerFrame;
                    int off = 0;
                    while (off < bytes && gen == generation) {
                        int n = l.read(raw, off, bytes - off);
                        if (n <= 0) {
                            break;
                        }
                        off += n;
                    }
                    if (off < bytes) {
                        break;
                    }
                    rs.convert(raw, inFrames, pcm, FRAME);
                    int bar = JoinVoiceSettings.levelPercent(pcm, FRAME, gain);
                    if (gen == generation) {
                        listener.level(gen, bar);
                    }
                }
            } finally {
                try {
                    l.stop();
                    l.close();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
        }
    }
}
