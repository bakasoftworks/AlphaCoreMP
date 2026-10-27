//
package cc.starsector.server.ops;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import cc.starsector.server.runtime.VoiceConfig;
import cc.starsector.server.runtime.VoiceFrameCodec;
import cc.starsector.server.runtime.VoiceRelay;

/**
 * The {@code voice.*} rows of {@code cc-server.properties} as pure statics: the knobs the web
 * console's Voice section edits ({@link #PANEL_KNOBS}), the effective sheet a file holds
 * ({@link #fromProps}), the ONE consolidated row block a save writes ({@link #withVoiceRows}: every
 * prior panel row and marker stripped, a fresh {@link #VOICE_COMMENT} plus the knob rows appended,
 * every unrelated row kept, so a re-save is idempotent), the ONE atomic live-retune verb
 * ({@link #applyVerb}), the bandwidth readout ({@link #readout}) and the knob names a
 * {@link VoiceConfig} refusal carries ({@link #offendingKnobs}). The validation is single-sourced
 * in {@link VoiceConfig}: nothing here re-implements a range.
 *
 * <p>WHY the name and the marker text stay: hosts' existing config files carry the marker line this
 * class writes and strips ({@link #VOICE_COMMENT}, {@link #VOICE_LEGACY_STEM}); re-wording it is the
 * legacy-stem migration's job.
 *
 */
public final class OpsVoicePanel {

    private OpsVoicePanel() {
    }

    /** The nine knobs this panel edits = a subset of {@link VoiceConfig#KNOB_NAMES}:
     * {@code lowDelay}/{@code echo} are the latency-rig knobs and stay file-only (carried through
     * Apply untouched). Order = the file-row + verb emission order. */
    static final String[] PANEL_KNOBS = {"enabled", "bitrateKbps", "frameMs", "complexity", "fec",
            "maxTalkers", "proxFullSu", "proxCullSu", "relayTickMs",
            // W12 (v1) jitter-aggression trio (the ask:
            // "settable in the server-host GUI Voice tab" + an "aggression setting").
            "jitterMinFrames", "jitterMaxMs", "jitterShrinkMs"};

    /** The single marker comment written above the panel's row block (stripped + re-added each
     * Apply so the block stays consolidated + idempotent). */
    static final String VOICE_COMMENT =
            "# voice relay knobs (ServerOpsGui Voice panel)";

    /** The id-free head of the marker an older build wrote (it cited a design-doc path after the
     * {@code ;}); {@link #withVoiceRows} strips a line starting with it like the current marker, so a
     * host file's old header never survives beside the new block.
     * */
    static final String VOICE_LEGACY_STEM = "# voice relay knobs (ServerOpsGui Voice panel;";

    /** Per-listener per-frame framing overhead, BYTES (the bandwidth sizing's "64 B overhead" — the
     * VCDN header {@link VoiceFrameCodec#DOWN_HEADER_BYTES} plus UDP/IP + datagram framing). */
    static final int LISTENER_FRAME_OVERHEAD_BYTES = 64;

    /** The egress-readout player count (the bandwidth sizing's "egress at N=32 ≈ 11 Mbit/s"). */
    static final int EGRESS_PLAYERS = 32;

    // ---- pure helpers (graded headless by the voice rows of OpsPressSelftest) ----------------

    /** Bytes one encoded talker frame occupies DOWN the wire ≈ {@code kbps×frameMs/8} payload +
     * the {@link VoiceFrameCodec#DOWN_TAG_BYTES 11 B} per-talker tag. */
    //
    static double talkerFrameBytes(int kbps, int frameMs) {
        return kbps * frameMs / 8.0 + VoiceFrameCodec.DOWN_TAG_BYTES;
    }

    /** Bytes/s ONE listener receives ≈ {@code (overhead + talkers×talkerFrame) × frames-per-sec}
     * (worked case: 48 kbps / 20 ms / 6 talkers ⇒ 42500 B/s ≈ 42 kB/s). */
    //
    static double perListenerBytesPerSec(int kbps, int frameMs, int talkers) {
        double perFrame = LISTENER_FRAME_OVERHEAD_BYTES + talkers * talkerFrameBytes(kbps, frameMs);
        return perFrame * (1000.0 / frameMs);
    }

    /** Server egress bytes/s at {@code players} connected: each of the {@code players−1} listeners
     * gets the full per-listener bundle (the worked case above at 32 players ⇒ ≈1.32 MB/s ≈ 11
     * Mbit/s). */
    //
    static double egressBytesPerSec(int kbps, int frameMs, int talkers, int players) {
        return Math.max(0, players - 1) * perListenerBytesPerSec(kbps, frameMs, talkers);
    }

    /** The live readout line. */
    //
    static String readout(int kbps, int frameMs, int talkers) {
        double perListenerKb = perListenerBytesPerSec(kbps, frameMs, talkers) / 1000.0;
        double egressMbit =
                egressBytesPerSec(kbps, frameMs, talkers, EGRESS_PLAYERS) * 8.0 / 1_000_000.0;
        return String.format(Locale.ROOT,
                "per listener ≈ %.1f kB/s at %d talkers; egress at %d players ≈ %.1f Mbit/s",
                perListenerKb, talkers, EGRESS_PLAYERS, egressMbit);
    }

    /** The file's effective voice sheet: {@link VoiceConfig#defaults()} with every present
     * {@code voice.*} row applied, read directly (NOT via successive {@code retune}, whose
     * one-knob-at-a-time joint check could trip on an intermediate state) and joint-validated
     * once. A malformed combination opens the panel at house defaults (fail-soft prefill). */
    //
    static VoiceConfig fromProps(List<String> lines) {
        VoiceConfig d = VoiceConfig.defaults();
        try {
            return new VoiceConfig(
                    boolProp(lines, "voice.enabled", d.enabled()),
                    intProp(lines, "voice.bitrateKbps", d.kbps()),
                    intProp(lines, "voice.frameMs", d.frameMs()),
                    intProp(lines, "voice.complexity", d.complexity()),
                    boolProp(lines, "voice.fec", d.fec()),
                    boolProp(lines, "voice.lowDelay", d.lowDelay()),
                    boolProp(lines, "voice.echo", d.echo()),
                    intProp(lines, "voice.maxTalkers", d.maxTalkers()),
                    intProp(lines, "voice.proxFullSu", d.proxFullSu()),
                    intProp(lines, "voice.proxCullSu", d.proxCullSu()),
                    intProp(lines, "voice.relayTickMs", d.relayTickMs()),
                    intProp(lines, "voice.jitterMinFrames", d.jitterMinFrames()),
                    intProp(lines, "voice.jitterMaxMs", d.jitterMaxMs()),
                    intProp(lines, "voice.jitterShrinkMs", d.jitterShrinkMs()));
        } catch (IllegalArgumentException malformed) {
            return d;
        }
    }

    /** Raw value of the LAST uncommented {@code key=} row (the {@code tickRateRowValue} grammar:
     * line-start exact, last-wins). */
    static String voiceRowValue(List<String> lines, String key) {
        String value = null;
        for (String line : lines) {
            if (line.startsWith(key + "=")) {
                value = line.substring(key.length() + 1);
            }
        }
        return value;
    }

    static int intProp(List<String> lines, String key, int def) {
        String v = voiceRowValue(lines, key);
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException notAnInt) {
            return def;
        }
    }

    static boolean boolProp(List<String> lines, String key, boolean def) {
        String v = voiceRowValue(lines, key);
        if (v == null) {
            return def;
        }
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "true": case "on": case "1":
                return true;
            case "false": case "off": case "0":
                return false;
            default:
                return def;
        }
    }

    /** The file's lines with the nine panel {@code voice.*} rows re-written as ONE consolidated
     * block (the {@link OpsMatchRows} upsert idiom): every prior panel row + our marker is
     * stripped and a fresh marker + nine rows appended, so every UNRELATED row — including the
     * file's {@code voice.lowDelay}/{@code voice.echo} rows — is preserved and re-Apply is
     * idempotent. */
    //
    static List<String> withVoiceRows(List<String> lines, VoiceConfig cfg) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (OpsMatchRows.isMarker(line, VOICE_COMMENT, VOICE_LEGACY_STEM)
                    || isPanelVoiceRow(line)) {
                continue;
            }
            out.add(line);
        }
        out.add(VOICE_COMMENT);
        for (String knob : PANEL_KNOBS) {
            out.add("voice." + knob + "=" + knobValue(cfg, knob));
        }
        return out;
    }

    static boolean isPanelVoiceRow(String line) {
        for (String knob : PANEL_KNOBS) {
            if (line.startsWith("voice." + knob + "=")) {
                return true;
            }
        }
        return false;
    }

    /** The string form of one panel knob — the SAME text for the file row and the operator verb
     * (single source, so the row and the live retune never disagree). */
    //
    static String knobValue(VoiceConfig c, String knob) {
        switch (knob) {
            case "enabled": return Boolean.toString(c.enabled());
            case "bitrateKbps": return Integer.toString(c.kbps());
            case "frameMs": return Integer.toString(c.frameMs());
            case "complexity": return Integer.toString(c.complexity());
            case "fec": return Boolean.toString(c.fec());
            case "maxTalkers": return Integer.toString(c.maxTalkers());
            case "proxFullSu": return Integer.toString(c.proxFullSu());
            case "proxCullSu": return Integer.toString(c.proxCullSu());
            case "relayTickMs": return Integer.toString(c.relayTickMs());
            case "jitterMinFrames": return Integer.toString(c.jitterMinFrames());
            case "jitterMaxMs": return Integer.toString(c.jitterMaxMs());
            case "jitterShrinkMs": return Integer.toString(c.jitterShrinkMs());
            default: throw new IllegalArgumentException("not a panel voice knob: " + knob);
        }
    }

    /** The ONE atomic {@code voice set k=v k=v ...}
     * verb carrying every panel knob whose value CHANGED ({@code current} → {@code desired}),
     * or {@code null} when nothing moved. WHY one line: per-knob {@code voice <knob> <value>}
     * verbs were each jointly validated ALONE, so a jointly valid change crossing an invalid
     * intermediate (proxFull 1000→3000 + proxCull 2500→4000, PANEL_KNOBS order) half-applied
     * the live relay. Pairs satisfy the {@code OperatorCommandFile} {@code voice set} law
     * ({@code [A-Za-z]{1,24}=[A-Za-z0-9.]{1,8}}, no knob twice) by construction. */
    static String applyVerb(VoiceConfig current, VoiceConfig desired) {
        StringBuilder out = new StringBuilder();
        for (String knob : PANEL_KNOBS) {
            String want = knobValue(desired, knob);
            if (!knobValue(current, knob).equals(want)) {
                out.append(' ').append(knob).append('=').append(want);
            }
        }
        return out.length() == 0 ? null : "voice " + VoiceRelay.SET_WORD + out;
    }

    /** The panel knob(s) a {@link VoiceConfig} refusal message names — the field(s) the Apply
     * reddens. Single-sourced off the real messages (which always contain the offending knob
     * token, e.g. {@code proxFullSu}/{@code proxCullSu} for a crossing, {@code bitrateKbps} +
     * {@code frameMs} for a fit breach), so the panel colours without re-implementing a range. */
    //
    static Set<String> offendingKnobs(String message) {
        Set<String> hit = new LinkedHashSet<>();
        if (message == null) {
            return hit;
        }
        for (String knob : PANEL_KNOBS) {
            if (message.contains(knob)) {
                hit.add(knob);
            }
        }
        return hit;
    }
}
