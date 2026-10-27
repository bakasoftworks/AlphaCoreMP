package cc.starsector.server.runtime;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * W12 L2 — the server's
 * voice sheet: every {@code cc.voice.*} knob resolved once at boot through {@link ServerConfig}
 * (sysprop / env / config-file triples registered in {@link PostureRegistry}), validated as ONE
 * joint record, and retuned LIVE by the operator {@code /voice <knob> <value>} verb
 * ({@link #retune}) — every retune mints a NEW validated record, the old one is never mutated.
 *
 * <p><b>Boot-refusal law:</b> the canonical constructor THROWS {@link IllegalArgumentException} on
 * any range breach, {@code proxFullSu >= proxCullSu}, or a bitrate × frame combination whose
 * config-derived frame cap no longer fits one datagram ({@link VoiceFrameCodec#fitsOneDatagram};
 * ceilings 510/301/150/100 kbps at 10/20/40/60 ms) — the {@code WaveSchedule} precedent of
 * refusing a knob combination loud rather than clamping silently. The same constructor guards
 * {@link #retune} (operator input is fail-soft: the caller logs the reason and keeps the
 * standing record) and the {@link VoiceConfigFrameCodec} decode (wire input is fail-closed).
 *
 * <p>{@code lowDelay} = the Opus RESTRICTED_LOWDELAY application (benched ≈3× cheaper
 * encode than VOIP at equal bytes); {@code echo} = the latency rig only (the relay bundles a
 * talker's OWN frames back to it); {@code relayTickMs == 0} means {@code frameMs / 2}
 * ({@link #effectiveRelayTickMs}: halves the bundler wait).
 *
 * <p><b>W12 (v1) jitter "aggression" trio:</b>
 * the CLIENT's
 * per-talker jitter buffer ({@code TalkerStream}) is server-owned + wire-delivered like every
 * other voice number (never client-baked): {@code jitterMinFrames} = the FLOOR the target starts
 * at and shrinks back to (1..4 frames; 1 = "try down to 10 ms"), {@code jitterMaxMs} = the
 * CEILING as a time budget (a late frame while primed grows the target one frame up to it),
 * {@code jitterShrinkMs} = the relax window (ms of clean pops before the target shrinks one
 * frame). Joint law: {@code jitterMaxMs >= jitterMinFrames * frameMs} (a ceiling below the floor
 * is refused loud). Defaults = the pre-knob constants (2 / 80 / 2000) so an unset sheet is
 * byte-for-byte the pre-knob behaviour.
 */
public record VoiceConfig(boolean enabled, int kbps, int frameMs, int complexity, boolean fec,
        boolean lowDelay, boolean echo, int maxTalkers, int proxFullSu, int proxCullSu,
        int relayTickMs, int jitterMinFrames, int jitterMaxMs, int jitterShrinkMs) {

    public static final int DEFAULT_KBPS = 48;
    /** 10: in a live test the operator flipped 20→10 and heard the
     *  gain; the relay tick follows ({@code frameMs / 2} = 5 ms). Client-side the jitter ceiling is a
     *  TIME budget ({@code jitterMaxMs}) so the shorter frame halves only the floor. */
    public static final int DEFAULT_FRAME_MS = 10;
    public static final int DEFAULT_COMPLEXITY = 5;
    public static final int DEFAULT_MAX_TALKERS = 6;
    public static final int DEFAULT_PROX_FULL_SU = 600;
    public static final int DEFAULT_PROX_CULL_SU = 2500;
    public static final int MAX_COMPLEXITY = 10;
    public static final int MAX_PROX_SU = 0xFFFF;
    public static final int MAX_RELAY_TICK_MS = 60;
    /** The relay bundler wakes every 2 ms
     *  (was 0 = frameMs/2 = 5 ms; measured -1.5..-1.9 ms per listener frame). The serve baseline
     *  row is {@code voice.relayTickMs=2}; this is the code default behind it. */
    public static final int DEFAULT_RELAY_TICK_MS = 2;
    /** (v1) jitter floor default = the old {@code TalkerStream.TARGET_MIN} (2 frames). */
    public static final int DEFAULT_JITTER_MIN_FRAMES = 2;
    public static final int MIN_JITTER_MIN_FRAMES = 1;
    public static final int MAX_JITTER_MIN_FRAMES = 4;
    /** (v1) jitter ceiling default = the old {@code TalkerStream.TARGET_MAX_MS} (80 ms). */
    public static final int DEFAULT_JITTER_MAX_MS = 80;
    public static final int MIN_JITTER_MAX_MS = 20;
    /** 160 ms = 16 frames at 10 ms = the client ring's half ({@code TalkerStream.MAX_BUFFERED} 32). */
    public static final int MAX_JITTER_MAX_MS = 160;
    /** (v1) relax window default = the old {@code TalkerStream.SHRINK_AFTER_CLEAN_MS} (2 s). */
    public static final int DEFAULT_JITTER_SHRINK_MS = 2000;
    public static final int MIN_JITTER_SHRINK_MS = 200;
    public static final int MAX_JITTER_SHRINK_MS = 20000;

    /** The knob names {@link #retune} accepts = the config-file key suffix after {@code voice.}. */
    public static final String KNOB_NAMES =
            "enabled|bitrateKbps|frameMs|complexity|fec|lowDelay|echo|maxTalkers|proxFullSu"
            + "|proxCullSu|relayTickMs|jitterMinFrames|jitterMaxMs|jitterShrinkMs";

    public VoiceConfig {
        validate(kbps, frameMs, complexity, maxTalkers, proxFullSu, proxCullSu, relayTickMs,
                jitterMinFrames, jitterMaxMs, jitterShrinkMs);
    }

    /** The pre-(v1) eleven-knob shape: the jitter trio at its defaults (tests + the L5 panel's
     *  refusal vectors keep their wording). */
    public VoiceConfig(boolean enabled, int kbps, int frameMs, int complexity, boolean fec,
            boolean lowDelay, boolean echo, int maxTalkers, int proxFullSu, int proxCullSu,
            int relayTickMs) {
        this(enabled, kbps, frameMs, complexity, fec, lowDelay, echo, maxTalkers, proxFullSu,
                proxCullSu, relayTickMs, DEFAULT_JITTER_MIN_FRAMES, DEFAULT_JITTER_MAX_MS,
                DEFAULT_JITTER_SHRINK_MS);
    }

    /** The house defaults (every knob unset). */
    public static VoiceConfig defaults() {
        return new VoiceConfig(true, DEFAULT_KBPS, DEFAULT_FRAME_MS, DEFAULT_COMPLEXITY, true,
                false, false, DEFAULT_MAX_TALKERS, DEFAULT_PROX_FULL_SU, DEFAULT_PROX_CULL_SU,
                DEFAULT_RELAY_TICK_MS, // Was 0 (= frameMs/2), != resolve()'s default
                DEFAULT_JITTER_MIN_FRAMES, DEFAULT_JITTER_MAX_MS, DEFAULT_JITTER_SHRINK_MS);
    }

    /**
     * Resolve every knob through the three-tier resolver (each triple is cross-checked against
     * its {@link PostureRegistry} row — a consumer typo throws at boot) and validate jointly.
     * Throws {@link IllegalArgumentException} on a bad combination: the boot refuses loud.
     */
    public static VoiceConfig resolve() {
        return new VoiceConfig(
                ServerConfig.resolveBoolean("cc.voice.enabled", "CC_VOICE_ENABLED",
                        "voice.enabled", true),
                ServerConfig.resolveInt("cc.voice.bitrateKbps", "CC_VOICE_BITRATE_KBPS",
                        "voice.bitrateKbps", DEFAULT_KBPS),
                ServerConfig.resolveInt("cc.voice.frameMs", "CC_VOICE_FRAME_MS",
                        "voice.frameMs", DEFAULT_FRAME_MS),
                ServerConfig.resolveInt("cc.voice.complexity", "CC_VOICE_COMPLEXITY",
                        "voice.complexity", DEFAULT_COMPLEXITY),
                ServerConfig.resolveBoolean("cc.voice.fec", "CC_VOICE_FEC", "voice.fec", true),
                ServerConfig.resolveBoolean("cc.voice.lowDelay", "CC_VOICE_LOW_DELAY",
                        "voice.lowDelay", false),
                ServerConfig.resolveBoolean("cc.voice.echo", "CC_VOICE_ECHO", "voice.echo", false),
                ServerConfig.resolveInt("cc.voice.maxTalkers", "CC_VOICE_MAX_TALKERS",
                        "voice.maxTalkers", DEFAULT_MAX_TALKERS),
                ServerConfig.resolveInt("cc.voice.proxFullSu", "CC_VOICE_PROX_FULL_SU",
                        "voice.proxFullSu", DEFAULT_PROX_FULL_SU),
                ServerConfig.resolveInt("cc.voice.proxCullSu", "CC_VOICE_PROX_CULL_SU",
                        "voice.proxCullSu", DEFAULT_PROX_CULL_SU),
                ServerConfig.resolveInt("cc.voice.relayTickMs", "CC_VOICE_RELAY_TICK_MS",
                        "voice.relayTickMs", DEFAULT_RELAY_TICK_MS),
                ServerConfig.resolveInt("cc.voice.jitterMinFrames", "CC_VOICE_JITTER_MIN_FRAMES",
                        "voice.jitterMinFrames", DEFAULT_JITTER_MIN_FRAMES),
                ServerConfig.resolveInt("cc.voice.jitterMaxMs", "CC_VOICE_JITTER_MAX_MS",
                        "voice.jitterMaxMs", DEFAULT_JITTER_MAX_MS),
                ServerConfig.resolveInt("cc.voice.jitterShrinkMs", "CC_VOICE_JITTER_SHRINK_MS",
                        "voice.jitterShrinkMs", DEFAULT_JITTER_SHRINK_MS));
    }

    /** The ingress per-frame byte cap derived from THIS sheet ({@link VoiceFrameCodec#configMaxOpusBytes}). */
    public int frameBytesCap() {
        return VoiceFrameCodec.configMaxOpusBytes(kbps, frameMs);
    }

    /** The relay thread's tick: the knob, or {@code frameMs / 2} when the knob is 0. */
    public int effectiveRelayTickMs() {
        return relayTickMs == 0 ? Math.max(1, frameMs / 2) : relayTickMs;
    }

    /**
     * Mint a NEW record with ONE knob changed ({@code knob} = a {@link #KNOB_NAMES} entry, case-
     * sensitive; booleans accept {@code true/false/on/off/1/0}). Throws
     * {@link IllegalArgumentException} with a one-line reason on an unknown knob, an unparsable
     * value, or a combination the joint validation refuses — the standing record is untouched.
     */
    public VoiceConfig retune(String knob, String value) {
        Objects.requireNonNull(knob, "knob");
        Objects.requireNonNull(value, "value");
        return retuneAll(Map.of(knob, value));
    }

    /**
     * Mint a NEW record with EVERY listed knob
     * changed at once and validated JOINTLY by ONE canonical-constructor build — the atomic
     * twin of {@link #retune} for the operator {@code voice set k=v ...} verb. WHY: a jointly
     * valid multi-knob change can cross an invalid intermediate state (proxFull 1000→3000 with
     * proxCull 2500→4000 applied one knob at a time refuses the first), so per-knob retunes
     * half-applied the Voice panel's Apply. Same parse + refusal wording as {@link #retune};
     * any refusal (unknown knob, unparsable value, joint breach) throws and NOTHING changes.
     */
    public VoiceConfig retuneAll(Map<String, String> knobValues) {
        Objects.requireNonNull(knobValues, "knobValues");
        if (knobValues.isEmpty()) {
            throw new IllegalArgumentException("voice set: no knob=value pairs (knobs: "
                    + KNOB_NAMES + ")");
        }
        boolean nEnabled = enabled, nFec = fec, nLowDelay = lowDelay, nEcho = echo;
        int nKbps = kbps, nFrameMs = frameMs, nComplexity = complexity, nMaxTalkers = maxTalkers,
                nProxFull = proxFullSu, nProxCull = proxCullSu, nRelayTick = relayTickMs,
                nJitterMin = jitterMinFrames, nJitterMax = jitterMaxMs, nJitterShrink = jitterShrinkMs;
        for (Map.Entry<String, String> kv : knobValues.entrySet()) {
            String knob = Objects.requireNonNull(kv.getKey(), "knob");
            String value = Objects.requireNonNull(kv.getValue(), "value");
            switch (knob) {
                case "enabled": nEnabled = parseBool(knob, value); break;
                case "bitrateKbps": nKbps = parseInt(knob, value); break;
                case "frameMs": nFrameMs = parseInt(knob, value); break;
                case "complexity": nComplexity = parseInt(knob, value); break;
                case "fec": nFec = parseBool(knob, value); break;
                case "lowDelay": nLowDelay = parseBool(knob, value); break;
                case "echo": nEcho = parseBool(knob, value); break;
                case "maxTalkers": nMaxTalkers = parseInt(knob, value); break;
                case "proxFullSu": nProxFull = parseInt(knob, value); break;
                case "proxCullSu": nProxCull = parseInt(knob, value); break;
                case "relayTickMs": nRelayTick = parseInt(knob, value); break;
                case "jitterMinFrames": nJitterMin = parseInt(knob, value); break;
                case "jitterMaxMs": nJitterMax = parseInt(knob, value); break;
                case "jitterShrinkMs": nJitterShrink = parseInt(knob, value); break;
                default:
                    throw new IllegalArgumentException("unknown voice knob '" + knob
                            + "' (knobs: " + KNOB_NAMES + ")");
            }
        }
        // ONE joint validation over the whole candidate (the canonical constructor).
        return new VoiceConfig(nEnabled, nKbps, nFrameMs, nComplexity, nFec, nLowDelay, nEcho,
                nMaxTalkers, nProxFull, nProxCull, nRelayTick, nJitterMin, nJitterMax,
                nJitterShrink);
    }

    /**
     * The {@code k=v} tokens of a
     * {@code voice set k=v k=v ...} verb (from index {@code from}) as an insertion-ordered map
     * for {@link #retuneAll}. Throws {@link IllegalArgumentException} on no pairs, more pairs
     * than knobs, a token without exactly one {@code =} / an empty side, or a repeated knob
     * (a doubled knob is ambiguous — refused, never last-wins).
     */
    public static Map<String, String> parseSetPairs(String[] tokens, int from) {
        Objects.requireNonNull(tokens, "tokens");
        int n = tokens.length - from;
        if (n < 1 || n > KNOB_COUNT) {
            throw new IllegalArgumentException("voice set: expected 1.." + KNOB_COUNT
                    + " knob=value pairs, got " + Math.max(0, n));
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (int i = from; i < tokens.length; i++) {
            String tok = tokens[i];
            int eq = tok.indexOf('=');
            if (eq <= 0 || eq == tok.length() - 1 || tok.indexOf('=', eq + 1) >= 0) {
                throw new IllegalArgumentException("voice set: malformed pair '" + tok
                        + "' (want knob=value)");
            }
            if (out.put(tok.substring(0, eq), tok.substring(eq + 1)) != null) {
                throw new IllegalArgumentException("voice set: knob '" + tok.substring(0, eq)
                        + "' repeated");
            }
        }
        return out;
    }

    /** How many knobs {@link #KNOB_NAMES} lists
     *  (the {@code voice set} pair-count bound). */
    public static final int KNOB_COUNT = KNOB_NAMES.split("\\|").length;

    /** One-line operator/boot summary (numbers only, never audio). */
    public String summary() {
        return "enabled=" + enabled + " kbps=" + kbps + " frame_ms=" + frameMs
                + " complexity=" + complexity + " fec=" + fec + " low_delay=" + lowDelay
                + " echo=" + echo + " max_talkers=" + maxTalkers + " prox_full_su=" + proxFullSu
                + " prox_cull_su=" + proxCullSu + " relay_tick_ms=" + effectiveRelayTickMs()
                + " frame_cap_bytes=" + frameBytesCap()
                + " jitter_min_frames=" + jitterMinFrames + " jitter_max_ms=" + jitterMaxMs
                + " jitter_shrink_ms=" + jitterShrinkMs;
    }

    // ---- laws -------------------------------------------------------------------------------

    private static void validate(int kbps, int frameMs, int complexity, int maxTalkers,
            int proxFullSu, int proxCullSu, int relayTickMs, int jitterMinFrames,
            int jitterMaxMs, int jitterShrinkMs) {
        // Bitrate domain + frameMs membership throw inside configMaxOpusBytes.
        if (!VoiceFrameCodec.fitsOneDatagram(kbps, frameMs)) {
            throw new IllegalArgumentException("voice bitrateKbps=" + kbps + " at frameMs="
                    + frameMs + " derives a frame cap of "
                    + VoiceFrameCodec.configMaxOpusBytes(kbps, frameMs) + " B > "
                    + VoiceFrameCodec.MAX_OPUS_BYTES + " (one frame must fit one datagram)");
        }
        if (complexity < 0 || complexity > MAX_COMPLEXITY) {
            throw new IllegalArgumentException("voice complexity must be 0.." + MAX_COMPLEXITY
                    + ": " + complexity);
        }
        if (maxTalkers < 1 || maxTalkers > VoiceFrameCodec.MAX_TALKERS_WIRE) {
            throw new IllegalArgumentException("voice maxTalkers must be 1.."
                    + VoiceFrameCodec.MAX_TALKERS_WIRE + ": " + maxTalkers);
        }
        if (proxFullSu < 1 || proxFullSu > MAX_PROX_SU) {
            throw new IllegalArgumentException("voice proxFullSu must be 1.." + MAX_PROX_SU
                    + ": " + proxFullSu);
        }
        if (proxCullSu < 1 || proxCullSu > MAX_PROX_SU) {
            throw new IllegalArgumentException("voice proxCullSu must be 1.." + MAX_PROX_SU
                    + ": " + proxCullSu);
        }
        if (proxFullSu >= proxCullSu) {
            throw new IllegalArgumentException("voice proxFullSu (" + proxFullSu
                    + ") must be < proxCullSu (" + proxCullSu + ")");
        }
        if (relayTickMs < 0 || relayTickMs > MAX_RELAY_TICK_MS) {
            throw new IllegalArgumentException("voice relayTickMs must be 0 (= frameMs/2) or 1.."
                    + MAX_RELAY_TICK_MS + ": " + relayTickMs);
        }
        if (jitterMinFrames < MIN_JITTER_MIN_FRAMES || jitterMinFrames > MAX_JITTER_MIN_FRAMES) {
            throw new IllegalArgumentException("voice jitterMinFrames must be "
                    + MIN_JITTER_MIN_FRAMES + ".." + MAX_JITTER_MIN_FRAMES + ": " + jitterMinFrames);
        }
        if (jitterMaxMs < MIN_JITTER_MAX_MS || jitterMaxMs > MAX_JITTER_MAX_MS) {
            throw new IllegalArgumentException("voice jitterMaxMs must be " + MIN_JITTER_MAX_MS
                    + ".." + MAX_JITTER_MAX_MS + ": " + jitterMaxMs);
        }
        if (jitterMaxMs < jitterMinFrames * frameMs) {
            throw new IllegalArgumentException("voice jitterMaxMs (" + jitterMaxMs
                    + ") must be >= jitterMinFrames x frameMs (" + jitterMinFrames + " x "
                    + frameMs + " = " + (jitterMinFrames * frameMs) + ")");
        }
        if (jitterShrinkMs < MIN_JITTER_SHRINK_MS || jitterShrinkMs > MAX_JITTER_SHRINK_MS) {
            throw new IllegalArgumentException("voice jitterShrinkMs must be "
                    + MIN_JITTER_SHRINK_MS + ".." + MAX_JITTER_SHRINK_MS + ": " + jitterShrinkMs);
        }
    }

    private static int parseInt(String knob, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notAnInt) {
            throw new IllegalArgumentException("voice " + knob + " wants an integer, got '"
                    + value + "'");
        }
    }

    private static boolean parseBool(String knob, String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "true": case "on": case "1":
                return true;
            case "false": case "off": case "0":
                return false;
            default:
                throw new IllegalArgumentException("voice " + knob
                        + " wants true/false/on/off/1/0, got '" + value + "'");
        }
    }
}
