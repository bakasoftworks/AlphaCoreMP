package cc.starsector.client.voice;

import java.util.function.Function;

/**
 * The client's voice settings: ONE shared
 * ASCII {@code k=v} file, logical name {@link #FILE_NAME}, on disk
 * {@code <Starsector>/saves/common/cc_client_settings.data} (the engine's common-file API
 * force-appends {@code .data}; the joiner window writes that on-disk name directly, the mod reads
 * the logical name through {@link VoiceSettingsStore}). {@code -Dcc.voice.*} sysprops WIN as dev
 * overrides ({@link #withSysprops}).
 *
 * <p>Immutable, GAME-FREE value (no engine import — the joiner window's JRE has no API jar, so
 * this class is shared by both writers). The parser is bounded (max {@value #MAX_LINES} lines,
 * {@value #MAX_LINE_CHARS} chars per line, unknown/malformed lines ignored, every number clamped
 * to its domain, {@code mic.device} printable ASCII of at most {@value #MAX_DEVICE_CHARS} chars)
 * because the file is user-authored data, never trusted shape. Keys: {@code voice.on} (default
 * true — the user-facing full opt-out, the settings-file path for {@code -Dcc.voice.off}),
 * {@code voice.volume} (0..2, default 1), {@code mic.device} (substring of a mixer name; empty =
 * default mixer), {@code mic.on} (default true), {@code mic.gain} (0..4, default 1),
 * {@code hud.talkRow} (default true), the HUD row toggles {@code hud.netRow} /
 * {@code hud.simRate} / {@code hud.clientSpeed} / {@code hud.serverSpeed} / {@code hud.clientFlux}
 * / {@code hud.serverFlux} (default true; {@link #hudRow}). {@link #serialize} is the canonical writer; both writers
 * emit it so {@code load(serialize())} round-trips exactly (selftest-pinned).
 *
 * <p><b>Server-default overrides (owner law):</b> the
 * server ops GUI sets DEFAULTS (the VCFG sheet); a player's own value is STICKY across sessions
 * and wins. {@code voice.complexity} = {@code server} (follow the sheet) or 0..10 — Opus
 * complexity is encoder-only, so a client pick needs no wire change; {@code voice.echo} =
 * {@code server}/{@code true}/{@code false} — hearing your own voice back (the relay echoes a
 * talker whose VCUP carries {@code FLAG_ECHO}, so the effective value rides the frame the client
 * already sends). {@link #effectiveComplexity}/{@link #effectiveEcho} resolve against the sheet;
 * {@link #withServerDefaults} is the {@code /settings} "reset to server default" row. Pure
 * presentation knobs (never on the wire): {@code voice.spatial} (default true; false =
 * distance-only, centred) and {@code voice.panWidth} (0..1, default 0.5 = a talker one
 * full-gain radius to the side reaches half pan — never 100 % into one ear).
 */
public final class VoiceClientSettings {
    /** Logical common-file name; on disk {@code saves/common/cc_client_settings.data}. */
    public static final String FILE_NAME = "cc_client_settings";
    /** The on-disk file name (engine {@code writeTextFileToCommon} appends {@code .data}). */
    public static final String DISK_FILE_NAME = FILE_NAME + ".data";
    public static final int MAX_LINES = 64;
    public static final int MAX_LINE_CHARS = 200;
    public static final int MAX_DEVICE_CHARS = 64;
    /** {@code voice.complexity}/{@code voice.echo}: follow the server sheet. */
    public static final int SERVER_DEFAULT = -1;
    public static final int MAX_COMPLEXITY = 10;
    public static final float DEFAULT_PAN_WIDTH = 0.5f;

    /** HUD toggles: one bit per left/bottom-right HUD row of ours,
     * keys {@code hud.<name>} in {@link #HUD_ROW_KEYS} order (bit i = key i); default ALL ON =
     * the pre-toggle visibility. Hidden rows collapse (an un-asserted vanilla status key fades
     * and the column repacks; the bottom-right stacker packs the rest). */
    public static final int HUD_NET_ROW = 1;
    public static final int HUD_SIM_RATE = 2;
    public static final int HUD_CLIENT_SPEED = 4;
    public static final int HUD_SERVER_SPEED = 8;
    public static final int HUD_CLIENT_FLUX = 16;
    public static final int HUD_SERVER_FLUX = 32;
    public static final int HUD_ROWS_ALL = 63;
    public static final String[] HUD_ROW_KEYS = {"hud.netRow", "hud.simRate", "hud.clientSpeed",
            "hud.serverSpeed", "hud.clientFlux", "hud.serverFlux"};

    public static final VoiceClientSettings DEFAULTS =
            new VoiceClientSettings(1f, "", true, 1f, true, true);

    public final float volume;
    public final String micDevice;
    public final boolean micOn;
    public final float micGain;
    public final boolean hudTalkRow;
    /** {@code voice.on}: false = no line opened, VOICE dropped — the user's full opt-out. */
    public final boolean voiceOn;
    /** Opus encoder complexity 0..10, or {@link #SERVER_DEFAULT}. */
    public final int complexity;
    /** Own-voice echo: 1 on / 0 off / {@link #SERVER_DEFAULT}. */
    public final int echo;
    /** Proximity voice spatialised (pan by camera-relative x) vs distance-only (centred). */
    public final boolean spatial;
    /** Pan width 0..1 — the pan magnitude multiplier ({@code 0} = mono, {@code 1} = full). */
    public final float panWidth;
    /** {@code voice.rttStats}: the HUD {@code voice rtt}
     * row as its OWN setting. Default ON like every
     * other readout and DECOUPLED from the echo toggle (the row shows regardless of echo; the
     * auto-flip is gone) — the menu row flips it alone. */
    public final boolean rttStats;
    /** The HUD row toggles as a bitmask of {@code HUD_*} bits ({@link #hudRow}). */
    public final int hudRows;
    /** Peer-build sharing v2:
     *  "Auto-share builds I use" — the client declares it to the server ({@code /share auto
     *  on|off silent}) at every admission and on toggle; default OFF.
     *
     * File key {@code builds.autoShare}. */
    public final boolean autoShareBuilds;

    public VoiceClientSettings(float volume, String micDevice, boolean micOn, float micGain,
            boolean hudTalkRow, boolean voiceOn) {
        this(volume, micDevice, micOn, micGain, hudTalkRow, voiceOn, SERVER_DEFAULT,
                SERVER_DEFAULT, true, DEFAULT_PAN_WIDTH, true);
    }

    public VoiceClientSettings(float volume, String micDevice, boolean micOn, float micGain,
            boolean hudTalkRow, boolean voiceOn, int complexity, int echo, boolean spatial,
            float panWidth) {
        this(volume, micDevice, micOn, micGain, hudTalkRow, voiceOn, complexity, echo, spatial,
                panWidth, true);
    }

    public VoiceClientSettings(float volume, String micDevice, boolean micOn, float micGain,
            boolean hudTalkRow, boolean voiceOn, int complexity, int echo, boolean spatial,
            float panWidth, boolean rttStats) {
        this(volume, micDevice, micOn, micGain, hudTalkRow, voiceOn, complexity, echo, spatial,
                panWidth, rttStats, HUD_ROWS_ALL);
    }

    public VoiceClientSettings(float volume, String micDevice, boolean micOn, float micGain,
            boolean hudTalkRow, boolean voiceOn, int complexity, int echo, boolean spatial,
            float panWidth, boolean rttStats, int hudRows) {
        this(volume, micDevice, micOn, micGain, hudTalkRow, voiceOn, complexity, echo, spatial,
                panWidth, rttStats, hudRows, false);
    }

    public VoiceClientSettings(float volume, String micDevice, boolean micOn, float micGain,
            boolean hudTalkRow, boolean voiceOn, int complexity, int echo, boolean spatial,
            float panWidth, boolean rttStats, int hudRows, boolean autoShareBuilds) {
        this.rttStats = rttStats;
        this.hudRows = hudRows & HUD_ROWS_ALL;
        this.autoShareBuilds = autoShareBuilds;
        this.volume = clamp(volume, 0f, 2f);
        this.micDevice = micDevice == null ? "" : micDevice;
        this.micOn = micOn;
        this.micGain = clamp(micGain, 0f, 4f);
        this.hudTalkRow = hudTalkRow;
        this.voiceOn = voiceOn;
        this.complexity = complexity < 0 ? SERVER_DEFAULT
                : (complexity > MAX_COMPLEXITY ? MAX_COMPLEXITY : complexity);
        this.echo = echo < 0 ? SERVER_DEFAULT : (echo > 0 ? 1 : 0);
        this.spatial = spatial;
        this.panWidth = clamp(panWidth, 0f, 1f);
    }

    // ---- copies (the /settings picker + the joiner window's merge) ----------------------------

    public VoiceClientSettings withVolume(float v) {
        return new VoiceClientSettings(v, micDevice, micOn, micGain, hudTalkRow, voiceOn,
                complexity, echo, spatial, panWidth, rttStats, hudRows, autoShareBuilds);
    }

    public VoiceClientSettings withMicGain(float g) {
        return new VoiceClientSettings(volume, micDevice, micOn, g, hudTalkRow, voiceOn,
                complexity, echo, spatial, panWidth, rttStats, hudRows, autoShareBuilds);
    }

    public VoiceClientSettings withMicOn(boolean on) {
        return new VoiceClientSettings(volume, micDevice, on, micGain, hudTalkRow, voiceOn,
                complexity, echo, spatial, panWidth, rttStats, hudRows, autoShareBuilds);
    }

    public VoiceClientSettings withVoiceOn(boolean on) {
        return new VoiceClientSettings(volume, micDevice, micOn, micGain, hudTalkRow, on,
                complexity, echo, spatial, panWidth, rttStats, hudRows, autoShareBuilds);
    }

    public VoiceClientSettings withHudTalkRow(boolean on) {
        return new VoiceClientSettings(volume, micDevice, micOn, micGain, on, voiceOn,
                complexity, echo, spatial, panWidth, rttStats, hudRows, autoShareBuilds);
    }

    /** Opus complexity 0..10, or {@link #SERVER_DEFAULT} (any negative). */
    public VoiceClientSettings withComplexity(int c) {
        return new VoiceClientSettings(volume, micDevice, micOn, micGain, hudTalkRow, voiceOn,
                c, echo, spatial, panWidth, rttStats, hudRows, autoShareBuilds);
    }

    /** Echo 1 on / 0 off / {@link #SERVER_DEFAULT} (any negative). */
    public VoiceClientSettings withEcho(int e) {
        return new VoiceClientSettings(volume, micDevice, micOn, micGain, hudTalkRow, voiceOn,
                complexity, e, spatial, panWidth, rttStats, hudRows, autoShareBuilds);
    }

    public VoiceClientSettings withSpatial(boolean on) {
        return new VoiceClientSettings(volume, micDevice, micOn, micGain, hudTalkRow, voiceOn,
                complexity, echo, on, panWidth, rttStats, hudRows, autoShareBuilds);
    }

    public VoiceClientSettings withPanWidth(float w) {
        return new VoiceClientSettings(volume, micDevice, micOn, micGain, hudTalkRow, voiceOn,
                complexity, echo, spatial, w, rttStats, hudRows, autoShareBuilds);
    }

    /** The rtt-stats HUD row on/off. */
    public VoiceClientSettings withRttStats(boolean on) {
        return new VoiceClientSettings(volume, micDevice, micOn, micGain, hudTalkRow, voiceOn,
                complexity, echo, spatial, panWidth, on, hudRows, autoShareBuilds);
    }

    /** One HUD row's toggle ({@code bit} = a {@code HUD_*} constant). */
    public boolean hudRow(int bit) {
        return (hudRows & bit) != 0;
    }

    public VoiceClientSettings withHudRow(int bit, boolean on) {
        int next = on ? (hudRows | bit) : (hudRows & ~bit);
        return new VoiceClientSettings(volume, micDevice, micOn, micGain, hudTalkRow, voiceOn,
                complexity, echo, spatial, panWidth, rttStats, next, autoShareBuilds);
    }

    /** v2: the "Auto-share builds I use" toggle. */
    public VoiceClientSettings withAutoShareBuilds(boolean on) {
        return new VoiceClientSettings(volume, micDevice, micOn, micGain, hudTalkRow, voiceOn,
                complexity, echo, spatial, panWidth, rttStats, hudRows, on);
    }

    /** The "reset to server default" row: drop every server-default override. */
    public VoiceClientSettings withServerDefaults() {
        return new VoiceClientSettings(volume, micDevice, micOn, micGain, hudTalkRow, voiceOn,
                SERVER_DEFAULT, SERVER_DEFAULT, spatial, panWidth, rttStats, hudRows, autoShareBuilds);
    }

    /** Same law as the file key: non-printable / over-long ⇒ "" (the default mixer). */
    public VoiceClientSettings withMicDevice(String device) {
        String d = device == null ? "" : device.trim();
        if (!printableAscii(d) || d.length() > MAX_DEVICE_CHARS) {
            d = "";
        }
        return new VoiceClientSettings(volume, d, micOn, micGain, hudTalkRow, voiceOn,
                complexity, echo, spatial, panWidth, rttStats, hudRows, autoShareBuilds);
    }

    // ---- effective values against the server sheet ----------------------------------------

    /** The encoder complexity to run: the override, else the sheet's (5 with no sheet). */
    public int effectiveComplexity(VoiceSheet sheet) {
        if (complexity != SERVER_DEFAULT) {
            return complexity;
        }
        return sheet == null ? 5 : sheet.complexity;
    }

    /** Whether own frames come back: the override, else the sheet's echo flag. */
    public boolean effectiveEcho(VoiceSheet sheet) {
        if (echo != SERVER_DEFAULT) {
            return echo == 1;
        }
        return sheet != null && sheet.echo;
    }

    // ---- text form ----------------------------------------------------------------------------

    /** Canonical ASCII text (fixed key order, {@code \n} line ends); {@code load} of it is exact. */
    public String serialize() {
        StringBuilder b = new StringBuilder(256);
        b.append("# CC client settings (voice) - written by the joiner window / in-game /settings\n");
        b.append("voice.on=").append(voiceOn).append('\n');
        b.append("voice.volume=").append(volume).append('\n');
        b.append("mic.on=").append(micOn).append('\n');
        b.append("mic.device=").append(micDevice).append('\n');
        b.append("mic.gain=").append(micGain).append('\n');
        b.append("hud.talkRow=").append(hudTalkRow).append('\n');
        b.append("voice.complexity=")
                .append(complexity == SERVER_DEFAULT ? "server" : String.valueOf(complexity))
                .append('\n');
        b.append("voice.echo=")
                .append(echo == SERVER_DEFAULT ? "server" : String.valueOf(echo == 1))
                .append('\n');
        b.append("voice.spatial=").append(spatial).append('\n');
        b.append("voice.panWidth=").append(panWidth).append('\n');
        b.append("voice.rttStats=").append(rttStats).append('\n');
        for (int i = 0; i < HUD_ROW_KEYS.length; i++) {
            b.append(HUD_ROW_KEYS[i]).append('=').append(hudRow(1 << i)).append('\n');
        }
        b.append("builds.autoShare=").append(autoShareBuilds).append('\n');
        return b.toString();
    }

    /** Parse the file text (null/empty ⇒ defaults). Never throws. */
    public static VoiceClientSettings load(String text) {
        VoiceClientSettings s = DEFAULTS;
        if (text == null || text.isEmpty()) {
            return s;
        }
        int lines = 0;
        int start = 0;
        int n = text.length();
        while (start <= n && lines < MAX_LINES) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = n;
            }
            lines++;
            if (end - start <= MAX_LINE_CHARS) {
                s = applyLine(s, text.substring(start, end));
            }
            start = end + 1;
        }
        return s;
    }

    /** Sysprops win over the file; {@code propLookup} is injectable for the selftest. */
    public static VoiceClientSettings withSysprops(VoiceClientSettings base,
            Function<String, String> propLookup) {
        VoiceClientSettings s = base == null ? DEFAULTS : base;
        s = applyKey(s, "voice.volume", propLookup.apply("cc.voice.volume"));
        s = applyKey(s, "mic.device", propLookup.apply("cc.voice.micDevice"));
        s = applyKey(s, "mic.on", propLookup.apply("cc.voice.micOn"));
        s = applyKey(s, "mic.gain", propLookup.apply("cc.voice.micGain"));
        s = applyKey(s, "hud.talkRow", propLookup.apply("cc.voice.hudTalkRow"));
        s = applyKey(s, "voice.complexity", propLookup.apply("cc.voice.clientComplexity"));
        s = applyKey(s, "voice.echo", propLookup.apply("cc.voice.clientEcho"));
        s = applyKey(s, "voice.spatial", propLookup.apply("cc.voice.spatial"));
        s = applyKey(s, "voice.panWidth", propLookup.apply("cc.voice.panWidth"));
        s = applyKey(s, "voice.rttStats", propLookup.apply("cc.voice.rttStats"));
        s = applyKey(s, "builds.autoShare", propLookup.apply("cc.builds.autoShare"));
        for (String key : HUD_ROW_KEYS) {
            s = applyKey(s, key, propLookup.apply("cc." + key)); // -Dcc.hud.netRow=false …
        }
        return s;
    }

    private static VoiceClientSettings applyLine(VoiceClientSettings s, String line) {
        String l = line.trim();
        if (l.isEmpty() || l.charAt(0) == '#') {
            return s;
        }
        int eq = l.indexOf('=');
        if (eq <= 0) {
            return s;
        }
        return applyKey(s, l.substring(0, eq).trim(), l.substring(eq + 1).trim());
    }

    private static VoiceClientSettings applyKey(VoiceClientSettings s, String key, String value) {
        if (value == null) {
            return s;
        }
        String v = value.trim();
        switch (key) {
            case "voice.on": {
                Boolean b = parseBool(v);
                return b == null ? s : s.withVoiceOn(b);
            }
            case "voice.volume": {
                Float f = parseFloat(v);
                return f == null ? s : s.withVolume(f);
            }
            case "mic.device": {
                if (!printableAscii(v) || v.length() > MAX_DEVICE_CHARS) {
                    return s;
                }
                return s.withMicDevice(v);
            }
            case "mic.on": {
                Boolean b = parseBool(v);
                return b == null ? s : s.withMicOn(b);
            }
            case "mic.gain": {
                Float f = parseFloat(v);
                return f == null ? s : s.withMicGain(f);
            }
            case "hud.talkRow": {
                Boolean b = parseBool(v);
                return b == null ? s : s.withHudTalkRow(b);
            }
            case "voice.complexity": {
                if (v.equalsIgnoreCase("server")) {
                    return s.withComplexity(SERVER_DEFAULT);
                }
                Float f = parseFloat(v);
                return f == null ? s : s.withComplexity(Math.round(f));
            }
            case "voice.echo": {
                if (v.equalsIgnoreCase("server")) {
                    return s.withEcho(SERVER_DEFAULT);
                }
                Boolean b = parseBool(v);
                return b == null ? s : s.withEcho(b ? 1 : 0);
            }
            case "voice.spatial": {
                Boolean b = parseBool(v);
                return b == null ? s : s.withSpatial(b);
            }
            case "voice.panWidth": {
                Float f = parseFloat(v);
                return f == null ? s : s.withPanWidth(f);
            }
            case "voice.rttStats": {
                Boolean b = parseBool(v);
                return b == null ? s : s.withRttStats(b);
            }
            case "builds.autoShare": {
                Boolean b = parseBool(v);
                return b == null ? s : s.withAutoShareBuilds(b);
            }
            default: {
                for (int i = 0; i < HUD_ROW_KEYS.length; i++) {
                    if (HUD_ROW_KEYS[i].equals(key)) {
                        Boolean b = parseBool(v);
                        return b == null ? s : s.withHudRow(1 << i, b);
                    }
                }
                return s;
            }
        }
    }

    private static Float parseFloat(String v) {
        if (v.isEmpty() || v.length() > 16 || !printableAscii(v)) {
            return null;
        }
        try {
            float f = Float.parseFloat(v);
            return (f != f || Float.isInfinite(f)) ? null : f;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean parseBool(String v) {
        if (v.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (v.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    static boolean printableAscii(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static float clamp(float v, float lo, float hi) {
        if (v != v) {
            return lo;
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public String toString() {
        return "VoiceClientSettings{voiceOn=" + voiceOn + ",volume=" + volume + ",micDevice='"
                + micDevice + "',micOn=" + micOn + ",micGain=" + micGain + ",hudTalkRow="
                + hudTalkRow + ",complexity=" + (complexity == SERVER_DEFAULT ? "server"
                        : String.valueOf(complexity))
                + ",echo=" + (echo == SERVER_DEFAULT ? "server" : String.valueOf(echo == 1))
                + ",spatial=" + spatial + ",panWidth=" + panWidth + ",rttStats=" + rttStats
                + ",hudRows=" + hudRows + ",autoShareBuilds=" + autoShareBuilds + "}";
    }
}
