package cc.starsector.client.join;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import cc.starsector.client.voice.VoiceClientSettings;

/**
 * The joiner window's half of the shared voice settings file
 * — the PURE helpers, Swing-free so {@code JoinGui --selftest} pins them headless.
 * The file is {@code <Starsector>/saves/common/cc_client_settings.data}: the engine's common-file
 * API resolves the mod's logical name {@code cc_client_settings} to exactly that path
 * ({@code StarfarerSettings.readTextFileFromCommon}), so this window writes
 * the on-disk name directly (it runs outside the game, {@code java.nio.file} is fine here —
 * {@code join/**} is b-deny-EXEMPT) and the mod reads it back through the engine at match start.
 *
 * <p>Merge law: this window owns ONLY {@code mic.device}/{@code mic.on}/{@code mic.gain} +
 * {@code voice.complexity} (the "Voice quality" box); the
 * in-game {@code /settings} picker owns the other {@code voice.*}/{@code hud.*}, so a launch re-reads the
 * existing file and rewrites it with the mic keys replaced (a fresh install starts from DEFAULTS).
 * {@code mic.device} is the picked mixer name reduced to printable ASCII (the file law) — the
 * in-game match is a case-insensitive substring, so any surviving prefix still selects the mixer.
 */
final class JoinVoiceSettings {
    /** Below this many surviving ASCII chars the name is too ambiguous — write "" (default). */
    static final int MIN_DEVICE_CHARS = 3;

    private JoinVoiceSettings() {
    }

    /** {@code <install>} = {@code modDir/../..} (the bat's {@code %~dp0..\..}). */
    static Path installRoot(Path modDir) {
        Path abs = modDir.toAbsolutePath().normalize();
        Path mods = abs.getParent();
        Path install = mods == null ? null : mods.getParent();
        return install == null ? abs : install;
    }

    static Path settingsFile(Path installRoot) {
        return installRoot.resolve("saves").resolve("common")
                .resolve(VoiceClientSettings.DISK_FILE_NAME);
    }

    /**
     * The mixer name reduced to the file's {@code mic.device} law: printable ASCII only (other
     * chars dropped), trimmed, capped at {@link VoiceClientSettings#MAX_DEVICE_CHARS}; fewer
     * than {@link #MIN_DEVICE_CHARS} survivors ⇒ "" (= the default mixer). Null ⇒ "".
     */
    static String asciiDeviceName(String mixerName) {
        if (mixerName == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(mixerName.length());
        for (int i = 0; i < mixerName.length(); i++) {
            char c = mixerName.charAt(i);
            if (c >= 0x20 && c <= 0x7E) {
                b.append(c);
            }
        }
        String s = b.toString().trim();
        if (s.length() > VoiceClientSettings.MAX_DEVICE_CHARS) {
            s = s.substring(0, VoiceClientSettings.MAX_DEVICE_CHARS).trim();
        }
        return s.length() < MIN_DEVICE_CHARS ? "" : s;
    }

    /** The window's mic keys over the existing file's value (null existing = DEFAULTS);
     *  {@code voice.complexity} is carried from the existing file. */
    static VoiceClientSettings merge(VoiceClientSettings existing, String micDevice, boolean micOn,
            float micGain) {
        VoiceClientSettings base = existing == null ? VoiceClientSettings.DEFAULTS : existing;
        return merge(base, micDevice, micOn, micGain, base.complexity);
    }

    /** Mic keys + the quality pick ({@code complexity} &lt; 0 = server default) over the file. */
    static VoiceClientSettings merge(VoiceClientSettings existing, String micDevice, boolean micOn,
            float micGain, int complexity) {
        VoiceClientSettings base = existing == null ? VoiceClientSettings.DEFAULTS : existing;
        return base.withMicDevice(micDevice).withMicOn(micOn).withMicGain(micGain)
                .withComplexity(complexity);
    }

    /** The existing file parsed (bounded parser), DEFAULTS when absent/unreadable. */
    static VoiceClientSettings read(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return VoiceClientSettings.DEFAULTS;
            }
            byte[] bytes = Files.readAllBytes(file);
            return VoiceClientSettings.load(new String(bytes, StandardCharsets.US_ASCII));
        } catch (IOException | RuntimeException e) {
            return VoiceClientSettings.DEFAULTS;
        }
    }

    /**
     * The Test bar law: RMS of gain-applied 16-bit LE mono samples → dBFS → 0..100
     * (−60 dBFS = 0, 0 dBFS = 100). Pure so the selftest pins silence / full scale / gain 0.
     */
    static int levelPercent(byte[] pcm16le, float gainFactor) {
        int samples = pcm16le.length / 2;
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            pcm[i] = (short) ((pcm16le[2 * i] & 0xFF) | (pcm16le[2 * i + 1] << 8));
        }
        return levelPercent(pcm, samples, gainFactor);
    }

    /** {@link #levelPercent(byte[], float)} over already-decoded mono samples (for the Test bar). */
    static int levelPercent(short[] pcm, int samples, float gainFactor) {
        if (samples <= 0) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < samples; i++) {
            int v = pcm[i];
            double g = v * gainFactor;
            if (g > 32767) {
                g = 32767;
            } else if (g < -32768) {
                g = -32768;
            }
            sum += g * g;
        }
        double rms = Math.sqrt(sum / samples);
        if (rms < 1) {
            return 0;
        }
        double dbfs = 20 * Math.log10(rms / 32768.0);
        int pct = (int) Math.round((dbfs + 60) / 60 * 100);
        return pct < 0 ? 0 : (pct > 100 ? 100 : pct);
    }

    /** Create {@code saves/common} as needed and write the canonical text as US-ASCII. */
    static void write(Path file, VoiceClientSettings s) throws IOException {
        Path dir = file.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Files.write(file, s.serialize().getBytes(StandardCharsets.US_ASCII));
    }
}
