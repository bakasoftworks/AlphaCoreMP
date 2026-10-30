package cc.starsector.server.ops;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import cc.starsector.server.runtime.OperatorCommandFile;
import cc.starsector.server.runtime.VoiceConfig;

/**
 * The ops web console's PURE request layer - form-body
 * decode, the JSON reply shapes, the verb grammar (form -&gt; the exact operator-file line a Swing
 * press mints), the occupant-bound confirm specs, the Start tri-state secrets and the
 * Voice / Mods tab forms. No HTTP, no
 * filesystem, no process: the console's HTTP shell calls these statics by their pinned
 * signatures, so every rule here runs in {@link OpsConsoleFormsSelftest} without a server.
 *
 * <p>WHY one layer: two operator shells once drifted apart,
 * so every line, confirm text and refusal below has ONE home (these statics over the
 * {@link ServerOpsCore} / {@link OpsMatchRows} minters), never a paraphrase.
 *
 *
 * <p>Value domains: a seat id is {@code [0-9]{1,9}} on the wire (0..999999999; 0 = the observers'
 * seat, never a seat row, so it refuses as "no longer seated"); {@code writerPid} &gt; 0 = a live
 * writer process, &lt;= 0 = none. A refusal is an {@link IllegalArgumentException} whose message is
 * the operator-facing text; form-borne text is echoed only bounded ({@link PeerText#bound}:
 * {@link #ECHO_MAX} chars, a mods path {@link #MODS_PATH_ECHO_MAX}) and never after a CR / LF / NUL
 * (those refuse first). This class's own refusals bound at their source; a SHARED minter's refusal
 * (the unban kind, the joiner-default / custom id, the dial words - each echoes the raw text) is
 * re-echoed bounded by {@link #echoBounded}.
 */
final class OpsConsoleForms {

    private OpsConsoleForms() {
    }

    /** The decode refusal (a malformed {@code %} escape); the body itself is never echoed. */
    static final String MALFORMED_BODY = "malformed form body";
    /** The refusal for a CR / LF / NUL anywhere in the form - before any other rule runs. */
    static final String CONTROL_CHAR = "refused: control character";
    /** A {@code replace} with blank text; the admin flow reads {@code ""} as REMOVE. */
    static final String BLANK_PASSWORD = "A blank password cannot be saved - use Remove";
    /** The admin {@code remove} needs its explicit box. */
    static final String ADMIN_REMOVE_BOX = "Remove the admin password needs its box ticked";
    /** At most this many chars of a form-borne word ride a refusal (an unknown verb, a bad value). */
    static final int ECHO_MAX = 40;
    /** The longest mods folder path the form accepts (chars). */
    static final int MODS_PATH_MAX = 1024;
    /** At most this many chars of a typed mods path ride its refusal. */
    static final int MODS_PATH_ECHO_MAX = 80;

    private static final Pattern SEAT = Pattern.compile("[0-9]{1,9}");
    /** The Swing Ban custom confirm's tail after the {@code ?} (shared by the seat and typed-id asks). */
    private static final String CUSTOM_BAN_TAIL = " It becomes unpickable this serve and re-submitting the same"
            + " body is refused (custom_banned); the author may still submit a different variant.";

    /** One server-minted confirm: {@code key} is what the page re-POSTs as {@code confirmed=}. */
    record ConfirmSpec(String key, String title, String message) {
    }

    // ---- (a) body decode ---------------------------------------------------------------------

    /** {@code application/x-www-form-urlencoded} -&gt; key -&gt; values in arrival order (a repeated
     * key collects in order - the {@code confirmed=} list rides that; the July
     * {@code ServerOpsWeb.parseParams} idiom). A malformed escape throws {@link #MALFORMED_BODY};
     * the URLDecoder exception is rethrown as that fixed text, never swallowed. */
    static Map<String, List<String>> decodeForm(String body) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        try {
            for (String pair : body.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                out.computeIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        k -> new ArrayList<>()).add(URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException(MALFORMED_BODY);
        }
        return out;
    }

    /** The key's first value, {@code null} when absent. */
    static String first(Map<String, List<String>> form, String key) {
        List<String> values = form.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    static boolean has(Map<String, List<String>> form, String key) {
        return first(form, key) != null;
    }

    // ---- (b) JSON shapes (the July shapes, HTTP 200) -----------------------------------------

    /** {@link ServerOpsCore#jsonEscape}; {@code null} = {@code ""}. */
    static String jsonEscape(String s) {
        return ServerOpsCore.jsonEscape(s == null ? "" : s);
    }

    static String okJson(String detail) {
        return "{\"ok\":true,\"detail\":\"" + jsonEscape(detail) + "\"}";
    }

    /** The Setup answer ({@code /api/shape-new}): {@code shape} = the stem, {@code text} = the New match
     *  shape's created sentence. */
    static String shapeTextJson(String shape, String text) {
        return "{\"ok\":true,\"shape\":\"" + jsonEscape(shape) + "\",\"text\":\"" + jsonEscape(text) + "\"}";
    }

    static String errorJson(String title, String error) {
        return "{\"error\":\"" + jsonEscape(error) + "\",\"title\":\"" + jsonEscape(title) + "\"}";
    }

    /** ALWAYS an array: a verb confirm is one element, Start's are the collected list. */
    static String needsConfirmJson(List<ConfirmSpec> specs) {
        StringBuilder sb = new StringBuilder("{\"needsConfirm\":[");
        for (int i = 0; i < specs.size(); i++) {
            ConfirmSpec c = specs.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"key\":\"").append(jsonEscape(c.key()))
                    .append("\",\"title\":\"").append(jsonEscape(c.title()))
                    .append("\",\"message\":\"").append(jsonEscape(c.message())).append("\"}");
        }
        return sb.append("]}").toString();
    }

    // ---- (c) the verb grammar ----------------------------------------------------------------

    /**
     * The EXACT operator-file line the verb's press mints, or an
     * {@link IllegalArgumentException} carrying the refusal. Order: a CR / LF / NUL anywhere
     * refuses first; then the verb word (unknown = refused, at most {@link #ECHO_MAX} chars echoed);
     * then a SEAT verb's seat resolution against {@code snap} (the Kick pick law:
     * the id re-resolved, never a row index) and the occupant
     * check (a form {@code key=} that is not the row's cid key = {@code Seat N changed hands -
     * nothing sent}); only then the shared minter, which carries the ROW's key,
     * never the form's. The CMD gate (live server + a command file) is the HTTP shell's.
     * A form-text minter runs inside
     * {@link #echoBounded}, so its refusal echoes the typed text bounded.
     */
    static String verbLine(Map<String, List<String>> form, OpsSnapshot snap) {
        refuseControlChars(form);
        String verb = first(form, "verb");
        switch (verb == null ? "" : verb) {
            case "end-round":
                return ServerOpsCore.verbLine(OperatorCommandFile.Verb.END_ROUND, 0);
            case "end-match":
                return ServerOpsCore.verbLine(OperatorCommandFile.Verb.END_MATCH, 0);
            case "set-sim-speed":
                return simSpeedLine(first(form, "value"));
            case "sim-speed-auto":
                return ServerOpsCore.simSpeedAutoLine(onOff(form, verb));
            case "custom-submissions":
                return ServerOpsCore.customSubmissionsLine(onOff(form, verb));
            case "kick": {
                OpsSnapshot.SeatRow row = seatRow(form, snap);
                return ServerOpsCore.kickSeatLine(row.seatId(), row.cidKey());
            }
            case "ban":
            case "ban-src": {
                OpsSnapshot.SeatRow row = seatRow(form, snap);
                return ServerOpsCore.banSeatLine("ban".equals(verb) ? OperatorCommandFile.Verb.BAN
                        : OperatorCommandFile.Verb.BAN_SRC, row.seatId(), row.cidKey());
            }
            case "custom-allow":
                return ServerOpsCore.customAllowLine(seatRow(form, snap).seatId());
            case "custom-reject":
                return ServerOpsCore.customRejectLine(seatRow(form, snap).seatId());
            case "custom-ban":
            case "custom-export": {
                // A non-blank typed id= wins (July's box:
                // a held-but-unflown id is banned / exported by typing it), minted the custom-unban way
                // (the minter judges the raw text, its refusal echo bounded); else the picked seat's
                // flying custom under the pick law, as before.
                boolean ban = "custom-ban".equals(verb);
                String typed = typedCustomId(form);
                if (typed != null) {
                    return echoBounded(() -> ban ? ServerOpsCore.customBanLine(typed)
                            : ServerOpsCore.customExportLine(typed), typed);
                }
                String flying = flyingCustom(seatRow(form, snap));
                return ban ? ServerOpsCore.customBanLine(flying) : ServerOpsCore.customExportLine(flying);
            }
            case "custom-unban": {
                String id = first(form, "id");
                return echoBounded(() -> ServerOpsCore.customUnbanLine(id == null ? "" : id), id);
            }
            case "custom-keep": {
                // Keep for everyone - the custom-ban law: a non-blank
                // typed id= first, else the picked seat's flying custom; the minter judges the typed text
                // (its charset law) before any line exists, its refusal echo bounded.
                String typed = typedCustomId(form);
                if (typed != null) {
                    return echoBounded(() -> ServerOpsCore.customKeepLine(typed), typed);
                }
                return ServerOpsCore.customKeepLine(flyingCustom(seatRow(form, snap)));
            }
            case "custom-keep-export": {
                // the page's per-row Keep under "Exported, not kept yet": exportName= names the inbox file
                String name = first(form, "exportName");
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("custom-keep-export: pick an exported ship first");
                }
                return echoBounded(() -> ServerOpsCore.customKeepExportLine(name), name);
            }
            case "custom-forget": {
                // the page's Forget kept / per-row Forget: stem= names the kept ship
                String stem = first(form, "stem");
                if (stem == null || stem.isBlank()) {
                    throw new IllegalArgumentException("custom-forget: pick a kept ship first");
                }
                return echoBounded(() -> ServerOpsCore.customForgetLine(stem), stem);
            }
            case "ship-hide":
            case "ship-show": {
                // The page's per-row Hide / Show under "Ships that come with
                // the server": id= names the shipped ship; the minter judges the raw text, its refusal echo bounded.
                String id = first(form, "id");
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException(verb + ": pick a ship that comes with the server first");
                }
                boolean hide = "ship-hide".equals(verb);
                return echoBounded(() -> hide ? ServerOpsCore.shipHideLine(id) : ServerOpsCore.shipShowLine(id), id);
            }
            case "unban": {
                String kind = first(form, "kind");
                return echoBounded(() -> ServerOpsCore.unbanLine(kind, first(form, "key")), kind);
            }
            case "ban-ip": {
                // The Bans section's typed address or range; key= is judged
                // whole by the one normalizer, and its refusal names the accepted forms, never the typed text.
                String key = required(form, "key");
                return echoBounded(() -> ServerOpsCore.banIpLine(key), key);
            }
            case "set-joiner-default": {
                String value = required(form, "value");
                return echoBounded(() -> OpsMatchRows.joinerDefaultLine(value), value);
            }
            case "wave-category": {
                String value = required(form, "value");
                return echoBounded(() -> OpsMatchRows.waveCategoryLine(value), value);
            }
            case "endless": {
                String value = required(form, "value");
                return echoBounded(() -> OpsMatchRows.endlessLine(value), value);
            }
            case "terrain": {
                String value = required(form, "value");
                String level = required(form, "level");
                return echoBounded(() -> OpsMatchRows.terrainLine(value, level), value, level);
            }
            case "knob-asteroids": {
                String value = required(form, "value");
                return echoBounded(() -> OpsMatchRows.asteroidsLine(value), value);
            }
            default:
                throw new IllegalArgumentException("unknown verb '"
                        + PeerText.bound(verb == null ? "" : verb, ECHO_MAX) + "'");
        }
    }

    // ---- (d) the confirm specs ---------------------------------------------------------------

    /**
     * The confirm for this verb, or {@code null} when the press asks none (end-round,
     * sim-speed, auto, customs toggle, custom-export, unban, custom-unban, ship-show, the live dials). Every
     * {@link #verbLine} refusal throws HERE first (the line is minted before the confirm asks), except
     * {@code kill} (no line: its text names {@code writerPid}, &gt; 0 = the live writer, else the
     * launcher). A seat key is OCCUPANT-BOUND
     * ({@code <verb>:<seat>:<cidKey>}; {@code custom-ban} adds {@code :<custom id>}, the id its text
     * names): a changed occupant mints a different key, so a re-POST carrying the old
     * {@code confirmed=} asks again instead of acting on someone else.
     */
    static ConfirmSpec verbConfirm(Map<String, List<String>> form, OpsSnapshot snap, long writerPid) {
        refuseControlChars(form);
        String verb = first(form, "verb");
        if ("kill".equals(verb)) {
            return new ConfirmSpec("kill", "Kill", writerPid > 0
                    ? "Stop server pid " + writerPid
                            + "? (writes the stop file, then ends the process)"
                    : "Stop the server launcher? (no live server process right now - writes"
                            + " the stop file, then ends the launcher if it does not stop)");
        }
        verbLine(form, snap); // a refused line never reaches a confirm; a null verb throws here
        switch (verb) {
            case "end-match":
                return new ConfirmSpec("end-match", "End match",
                        "End the match? (clean goodbye fan, server exits)");
            case "kick": {
                OpsSnapshot.SeatRow row = seatRow(form, snap);
                return new ConfirmSpec(occupantKey(verb, row), "Kick",
                        "Kick seat " + row.seatId() + " (" + row.clientId() + ")?");
            }
            case "ban": {
                OpsSnapshot.SeatRow row = seatRow(form, snap);
                return new ConfirmSpec(occupantKey(verb, row), "Ban player",
                        ServerOpsCore.banConfirmText(row.seatId(), row.clientId()));
            }
            case "ban-src": {
                OpsSnapshot.SeatRow row = seatRow(form, snap);
                return new ConfirmSpec(occupantKey(verb, row), "Ban source",
                        ServerOpsCore.banSrcConfirmText(row.seatId(), row.clientId(), row.endpoint()));
            }
            case "ban-ip": {
                // A typed address names no seat, so the key binds the MINTED
                // stored key (ban-ip:<key>, the typed custom-ban shape): a changed box mints another key and asks again.
                String key = verbLine(form, snap).substring("ban-ip ".length());
                return new ConfirmSpec("ban-ip:" + key, "Ban address", ServerOpsCore.banIpConfirmText(key));
            }
            case "custom-allow": {
                OpsSnapshot.SeatRow row = seatRow(form, snap);
                return new ConfirmSpec(occupantKey(verb, row), "Allow seat",
                        "Allow seat " + row.seatId() + " (" + row.clientId() + ") to submit customs again"
                                + " and un-ban its banned customs?");
            }
            case "custom-reject": {
                // The page-only Reject seat (Swing has no
                // button; July's page asked first). CustomVariantLane.rejectSeat drops the seat's pending
                // deposits, bans every custom its author holds and blocks the author for the serve - the
                // Allow seat confirm's inverse, occupant-bound the same way.
                OpsSnapshot.SeatRow row = seatRow(form, snap);
                return new ConfirmSpec(occupantKey(verb, row), "Reject seat",
                        "Reject seat " + row.seatId() + " (" + row.clientId() + ")? Its player may not submit"
                                + " customs for the rest of this serve and every custom they hold is banned;"
                                + " Allow seat reverses it.");
            }
            case "custom-ban": {
                if (typedCustomId(form) != null) {
                    // A typed id names no seat, so the key
                    // binds the minted id itself (custom-ban:id:<id>, never a seat key's shape)
                    String id = verbLine(form, snap).substring("custom-ban ".length());
                    return new ConfirmSpec("custom-ban:id:" + id, "Ban custom",
                            "Ban custom id '" + id + "'?" + CUSTOM_BAN_TAIL);
                }
                OpsSnapshot.SeatRow row = seatRow(form, snap);
                return new ConfirmSpec(occupantKey(verb, row) + ":" + row.custom(), "Ban custom",
                        "Ban custom id '" + row.custom() + "' (seat " + row.seatId() + ")?" + CUSTOM_BAN_TAIL);
            }
            case "custom-keep":
            case "custom-keep-export": {
                // The Swing Keep confirm, server-minted (v2):
                // the key binds the MINTED id / file name (a changed pick mints another key and asks again).
                String line = verbLine(form, snap);
                String id = line.substring(line.indexOf(' ') + 1);
                return new ConfirmSpec(verb + ":" + id, ServerOpsCore.CUSTOM_KEEP_LABEL,
                        ServerOpsCore.keepConfirmText(id));
            }
            case "custom-forget": {
                // the page asks before a Forget (the Swing pick dialog's OK is its confirm); keyed by the stem
                String stem = verbLine(form, snap).substring("custom-forget ".length());
                return new ConfirmSpec("custom-forget:" + stem, ServerOpsCore.CUSTOM_FORGET_LABEL,
                        ServerOpsCore.forgetConfirmText(stem));
            }
            case "ship-hide": {
                // Hide asks first, keyed by the MINTED shipped id (another
                // row's Hide mints another key and asks again); ship-show asks nothing (the unban idiom).
                String id = verbLine(form, snap).substring("ship-hide ".length());
                return new ConfirmSpec("ship-hide:" + id, ServerOpsCore.SHIP_HIDE_TITLE,
                        ServerOpsCore.shipHideConfirmText(id));
            }
            default:
                return null;
        }
    }

    // ---- (e) the Start fields ----------------------------------------------------------------

    /**
     * The 21-component {@link OpsWindowModel.StartFields} from the Start POST. NON-NULLABLE:
     * {@code bindHost bindPort tickRate fortressDp descriptor} - absent =
     * {@code missing field: <name>}, never a silent default; every other field absent = {@code null}
     * (that row untouched, the StartFields null law). Secrets are tri-state:
     * {@code passwordAction} keep (the default) = {@code keptMatchPassword} ({@code null} = {@code ""};
     * the web Start passes {@code null} here and substitutes the kept password under its start lock,
     * {@code OpsConsole.keptPassword}), replace = the typed text (blank refused), remove = refused (a package launcher
     * refuses a blank match password, {@link ServerOpsLaunch#PACKAGE_PASSWORD_REQUIRED}); {@code adminAction} keep =
     * {@code null} (the admin null law), replace = the typed text (blank refused), remove = {@code ""}
     * only with {@code adminRemove=on}. Blank = empty after the {@code sanitizedMatchPassword}
     * quote-strip + trim. {@code selDescriptor} / {@code selInfo} are the caller's resolve.
     *
     * <p>{@code seatsPerSource} / {@code allSkills} follow the
     * null law too: ABSENT = {@code null} = the row untouched (a page that predates the fields never
     * resets a host's cap); PRESENT = the text as sent, a blank one included, which the Start parse
     * refuses ({@link OpsMatchRows#parseSeatsPerSource}, {@link OpsMatchRows#parseAllSkills}).
     *
     * <p>{@code rngPins} (the replay-pins word, on|off) obeys
     * the same law: absent = {@code null} = the {@code rngpin.enabled} row untouched; present =
     * the text as sent, refused by {@link OpsMatchRows#parseRngPins} unless on|off.
     *
     * <p>{@code pvp} (PvP mode, on|off) and {@code pvpStation} (the
     * page's Station pick) obey it too: absent = {@code null} = the picked file's mode stands; present = the
     * text as sent, refused by {@link OpsMatchApply#parsePvpMode} unless on|off.
     *
     * <p>{@code magicSeam} (the MagicLib word, on|off) obeys it
     * too: absent = {@code null} = the {@code magicseam.enabled} row untouched; present = the text as
     * sent, refused by {@link OpsMatchRows#parseMagicSeam} unless on|off.
     *
     * <p>{@code pilotShip} (the player-ship word, on|off) obeys it
     * too: absent = {@code null} = the {@code pilotship.enabled} row untouched; present = the text as
     * sent, refused by {@link OpsMatchRows#parsePilotShip} unless on|off.
     */
    static OpsWindowModel.StartFields startFields(Map<String, List<String>> form, String selDescriptor,
            ServerOpsCore.DescriptorInfo selInfo, String keptMatchPassword) {
        refuseControlChars(form);
        String bindHost = required(form, "bindHost");
        String bindPort = required(form, "bindPort");
        String tick = required(form, "tickRate");
        String fortressDp = required(form, "fortressDp");
        required(form, "descriptor");
        String password = matchPasswordText(form, keptMatchPassword);
        String admin = adminPasswordText(form);
        return new OpsWindowModel.StartFields(bindHost, bindPort, tick, fortressDp, password,
                selDescriptor, selInfo, first(form, "joinerDefault"), first(form, "fortressWaveScale"),
                admin, first(form, "waveCategory"), first(form, "endless"), first(form, "modsEnabled"),
                first(form, "modsOptional"), first(form, "terrain"), first(form, "terrainLevel"),
                first(form, "asteroids"), first(form, "waveRosterFullDp"),
                first(form, "waveRosterMultiplier"), first(form, "seatsPerSource"), first(form, "allSkills"),
                first(form, "rngPins"), false, first(form, "pvp"), first(form, "pvpStation"),
                first(form, "magicSeam"), first(form, "pilotShip"));
    }

    static String matchPasswordText(Map<String, List<String>> form, String kept) {
        String action = first(form, "passwordAction");
        switch (action == null ? "keep" : action) {
            case "keep":
                return kept == null ? "" : kept;
            case "replace": {
                String typed = first(form, "password");
                if (blankPassword(typed)) {
                    throw new IllegalArgumentException(BLANK_PASSWORD);
                }
                return typed;
            }
            case "remove":
                throw new IllegalArgumentException(ServerOpsLaunch.PACKAGE_PASSWORD_REQUIRED);
            default:
                throw new IllegalArgumentException("passwordAction must be keep, replace or remove");
        }
    }

    private static String adminPasswordText(Map<String, List<String>> form) {
        String action = first(form, "adminAction");
        switch (action == null ? "keep" : action) {
            case "keep":
                return null;
            case "replace": {
                String typed = first(form, "adminPassword");
                if (blankPassword(typed)) {
                    throw new IllegalArgumentException(BLANK_PASSWORD);
                }
                return typed;
            }
            case "remove":
                if (!"on".equals(first(form, "adminRemove"))) {
                    throw new IllegalArgumentException(ADMIN_REMOVE_BOX);
                }
                return "";
            default:
                throw new IllegalArgumentException("adminAction must be keep, replace or remove");
        }
    }

    // ---- (f) the voice form ------------------------------------------------------------------

    /**
     * The Voice tab's Apply as a form - the
     * {@code OpsVoicePanel.onApply} build with the form in place
     * of the Swing controls. Every {@link OpsVoicePanel#PANEL_KNOBS} knob is read by its file-row
     * name; an ABSENT knob keeps {@code current}'s value; {@code enabled} / {@code fec} accept exactly
     * {@code on|off} (no case fold, no trim), the integers {@link Integer#parseInt} (a 32-bit signed
     * int; the RANGE is the ctor's); {@code lowDelay} / {@code echo} are the latency-rig knobs and are
     * carried from {@code current} (never form-steered, the panel's own law). The {@link VoiceConfig}
     * ctor's joint validation is the ONE range law: its {@link IllegalArgumentException} propagates
     * untouched (its message names the knob(s); {@link OpsVoicePanel#offendingKnobs} reads them).
     * A CR / LF / NUL anywhere in the form refuses first.
     */
    static VoiceConfig voiceConfig(Map<String, List<String>> form, VoiceConfig current) {
        refuseControlChars(form);
        boolean enabled = current.enabled();
        int kbps = current.kbps();
        int frameMs = current.frameMs();
        int complexity = current.complexity();
        boolean fec = current.fec();
        int maxTalkers = current.maxTalkers();
        int proxFullSu = current.proxFullSu();
        int proxCullSu = current.proxCullSu();
        int relayTickMs = current.relayTickMs();
        int jitterMinFrames = current.jitterMinFrames();
        int jitterMaxMs = current.jitterMaxMs();
        int jitterShrinkMs = current.jitterShrinkMs();
        for (String knob : OpsVoicePanel.PANEL_KNOBS) {
            String typed = first(form, knob);
            switch (knob) {
                case "enabled": enabled = voiceFlag(knob, typed, enabled); break;
                case "bitrateKbps": kbps = voiceInt(knob, typed, kbps); break;
                case "frameMs": frameMs = voiceInt(knob, typed, frameMs); break;
                case "complexity": complexity = voiceInt(knob, typed, complexity); break;
                case "fec": fec = voiceFlag(knob, typed, fec); break;
                case "maxTalkers": maxTalkers = voiceInt(knob, typed, maxTalkers); break;
                case "proxFullSu": proxFullSu = voiceInt(knob, typed, proxFullSu); break;
                case "proxCullSu": proxCullSu = voiceInt(knob, typed, proxCullSu); break;
                case "relayTickMs": relayTickMs = voiceInt(knob, typed, relayTickMs); break;
                case "jitterMinFrames": jitterMinFrames = voiceInt(knob, typed, jitterMinFrames); break;
                case "jitterMaxMs": jitterMaxMs = voiceInt(knob, typed, jitterMaxMs); break;
                case "jitterShrinkMs": jitterShrinkMs = voiceInt(knob, typed, jitterShrinkMs); break;
                default: // a knob added to PANEL_KNOBS without a form rule fails loud, never ignored
                    throw new IllegalStateException("voice form has no rule for panel knob " + knob);
            }
        }
        return new VoiceConfig(enabled, kbps, frameMs, complexity, fec, current.lowDelay(),
                current.echo(), maxTalkers, proxFullSu, proxCullSu, relayTickMs, jitterMinFrames,
                jitterMaxMs, jitterShrinkMs);
    }

    /** The Voice tab's live bandwidth line for
     * {@code c} - the Swing readout itself ({@link OpsVoicePanel#readout}: kbps, frame, talkers). */
    static String voiceBandwidthText(VoiceConfig c) {
        return OpsVoicePanel.readout(c.kbps(), c.frameMs(), c.maxTalkers());
    }

    private static boolean voiceFlag(String knob, String typed, boolean kept) {
        if (typed == null) {
            return kept;
        }
        if (!"on".equals(typed) && !"off".equals(typed)) {
            throw new IllegalArgumentException("voice " + knob + " wants on or off, got '"
                    + PeerText.bound(typed, ECHO_MAX) + "'");
        }
        return "on".equals(typed);
    }

    private static int voiceInt(String knob, String typed, int kept) {
        if (typed == null) {
            return kept;
        }
        try {
            return Integer.parseInt(typed);
        } catch (NumberFormatException notAnInt) { // translated to the refusal, never swallowed
            throw new IllegalArgumentException("voice " + knob + " wants an integer, got '"
                    + PeerText.bound(typed, ECHO_MAX) + "'");
        }
    }

    // ---- (g) the mods form -------------------------------------------------------------------

    /**
     * The TEXT law of a typed mods folder path -
     * {@code null} = acceptable, else the refusal. PURE: no filesystem call (existence and the
     * folder's contents are the HTTP shell's {@code OpsModsPanel} step). Rules in order: non-blank;
     * no CR / LF / NUL (no echo); at most {@link #MODS_PATH_MAX} chars; no leading / trailing blank;
     * no {@code ;} (the {@code mods.roots} separator, the Swing {@code addFolder} refusal); not a
     * network path (the first two chars both a slash of EITHER kind: the JDK's Windows path parser reads
     * any two leading slashes as UNC, the device namespace included);
     * a valid ABSOLUTE path
     * ({@link Paths#get} on this host). The typed text rides a refusal bounded at
     * {@link #MODS_PATH_ECHO_MAX} chars.
     */
    static String modsPathRefusal(String typed) {
        if (typed == null || typed.trim().isEmpty()) {
            return "a mods folder path is required";
        }
        if (hasControl(typed)) {
            return CONTROL_CHAR;
        }
        if (typed.length() > MODS_PATH_MAX) {
            return "path too long (max " + MODS_PATH_MAX + ")";
        }
        String bound = PeerText.bound(typed, MODS_PATH_ECHO_MAX);
        if (!typed.trim().equals(typed) || !typed.strip().equals(typed)) {
            return "Cannot list '" + bound + "': leading or trailing blank";
        }
        if (typed.indexOf(';') >= 0) {
            return "Cannot list " + bound + ": a ';' in the path (the mods.roots separator)."
                    + "\nMove or rename the folder first.";
        }
        // Any two leading slashes, of either kind in any mix, parse as a UNC / device path on Windows
        // ("/\\host\\share", "\\/srv/x", "/\\.\\pipe\\x"); a lone "/" falls through to the absolute-path rule.
        if (typed.length() >= 2 && (typed.charAt(0) == '\\' || typed.charAt(0) == '/')
                && (typed.charAt(1) == '\\' || typed.charAt(1) == '/')) {
            return "Cannot list " + bound + ": network paths are not searched.";
        }
        try {
            if (!Paths.get(typed).isAbsolute()) {
                return "Cannot list " + bound + ": the path must be absolute.";
            }
        } catch (InvalidPathException invalid) { // the refusal IS the outcome, never swallowed
            return "Cannot list " + bound + ": not a valid path.";
        }
        return null;
    }

    /** A mod row's Use value - exactly one of
     * {@link OpsModsPanel#MOD_CHOICES} ({@code off|required|optional}, no case fold), else refused. */
    static String modsUseValue(String v) {
        if (v == null || !OpsModsPanel.MOD_CHOICES.contains(v)) {
            throw new IllegalArgumentException("use must be off, required or optional");
        }
        return v;
    }

    // ---- shared helpers ----------------------------------------------------------------------

    /**
     * Runs a shared minter and, on its
     * refusal, re-echoes each form value its message carries BOUNDED ({@link PeerText#bound} at
     * {@link #ECHO_MAX}) - the minters ({@code ServerOpsCore.unbanLine}'s kind,
     * {@code OpsMatchRows.parseJoinerDefault} for the joiner default and a custom id, the dial-word
     * parsers) echo the raw text. WHY after, not before: a value bounded BEFORE the minter would
     * change what it judges (a valid 41..64-char id cut and refused; a C1 / bidi char folded to a
     * space and trimmed away, turning a refusal into a mint), so the minter judges the form's own
     * text and only the echo is bounded. The minter's wording is otherwise kept verbatim, and the
     * raw-text cause is NOT chained (it carries the unbounded echo).
     */
    private static String echoBounded(Supplier<String> minter, String... formValues) {
        try {
            return minter.get();
        } catch (IllegalArgumentException refused) { // translated to the bounded refusal, never swallowed
            String message = String.valueOf(refused.getMessage());
            for (String value : formValues) {
                message = boundEcho(message, value);
            }
            throw new IllegalArgumentException(message);
        }
    }

    /** {@code message} with {@code value} - as typed, else trimmed (the minters echo either) -
     * replaced by its {@link PeerText#bound} form; unchanged when the value is already bounded. */
    private static String boundEcho(String message, String value) {
        if (value == null) {
            return message;
        }
        for (String echo : new String[] {value, value.trim()}) {
            String bounded = PeerText.bound(echo, ECHO_MAX);
            if (!echo.isEmpty() && !bounded.equals(echo) && message.contains(echo)) {
                return message.replace(echo, bounded);
            }
        }
        return message;
    }

    private static boolean blankPassword(String typed) {
        return typed == null || typed.replace("\"", "").trim().isEmpty();
    }

    /** A CR / LF / NUL in any key or value refuses the whole form (a line / row injection). */
    static void refuseControlChars(Map<String, List<String>> form) {
        for (Map.Entry<String, List<String>> e : form.entrySet()) {
            if (hasControl(e.getKey())) {
                throw new IllegalArgumentException(CONTROL_CHAR);
            }
            for (String v : e.getValue()) {
                if (hasControl(v)) {
                    throw new IllegalArgumentException(CONTROL_CHAR);
                }
            }
        }
    }

    private static boolean hasControl(String s) {
        return s != null && (s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\0') >= 0);
    }

    private static String required(Map<String, List<String>> form, String key) {
        String v = first(form, key);
        if (v == null) {
            throw new IllegalArgumentException("missing field: " + key);
        }
        return v;
    }

    /** The Set sim-speed refusal ({@code verb.simSpeedSet}): a non-number AND an
     * out-of-range number get the one text (the minter's IAE is translated, never swallowed). */
    private static String simSpeedLine(String text) {
        String t = text == null ? "" : text.trim();
        try {
            return ServerOpsCore.simSpeedLine(Float.parseFloat(t));
        } catch (IllegalArgumentException bad) { // NumberFormatException included (the Swing catch)
            throw new IllegalArgumentException("Sim-speed must be a number in ["
                    + OperatorCommandFile.SIM_SPEED_MIN + ", " + OperatorCommandFile.SIM_SPEED_MAX
                    + "], got '" + PeerText.bound(t, ECHO_MAX) + "'.");
        }
    }

    /** {@code auto=on|off}, strict (no case fold, no trim). */
    private static boolean onOff(Map<String, List<String>> form, String verb) {
        String auto = first(form, "auto");
        if (!"on".equals(auto) && !"off".equals(auto)) {
            throw new IllegalArgumentException(verb + " needs auto=on|off, got '"
                    + PeerText.bound(auto == null ? "" : auto, ECHO_MAX) + "'");
        }
        return "on".equals(auto);
    }

    /** The seat carrying {@code seatId} in {@code seats}, or {@code null} (vacated): a verb resolves its
     * seat by id, never by row index, so a join or leave between the pick and the press never retargets it.
     * */
    static OpsSnapshot.SeatRow seatById(List<OpsSnapshot.SeatRow> seats, int seatId) {
        for (OpsSnapshot.SeatRow s : seats) {
            if (s.seatId() == seatId) {
                return s;
            }
        }
        return null;
    }

    /** The pick law (a picked seat id, re-resolved by id) + the occupant binding (see {@link #verbLine}). */
    private static OpsSnapshot.SeatRow seatRow(Map<String, List<String>> form, OpsSnapshot snap) {
        String seatText = first(form, "seat");
        if (seatText == null || seatText.isEmpty()) {
            throw new IllegalArgumentException("Select a seat row first.");
        }
        if (!SEAT.matcher(seatText).matches()) {
            throw new IllegalArgumentException("seat must be a seat number (1 to 9 digits)");
        }
        int seatId = Integer.parseInt(seatText);
        OpsSnapshot.SeatRow row = snap == null ? null : seatById(snap.seats(), seatId);
        if (row == null) {
            throw new IllegalArgumentException("Seat " + seatId + " is no longer seated.");
        }
        String formKey = first(form, "key");
        if (formKey != null && !foldKey(formKey).equals(foldKey(row.cidKey()))) {
            throw new IllegalArgumentException("Seat " + seatId + " changed hands - nothing sent");
        }
        return row;
    }

    /** The form's {@code id=} when it carries a
     * non-blank one (the page's Custom id box), else {@code null} = the picked seat's flying custom. */
    private static String typedCustomId(Map<String, List<String>> form) {
        String id = first(form, "id");
        return id == null || id.isBlank() ? null : id;
    }

    /** Ban / Export custom need a FLYING custom (a seat on a stock hull is refused; the answer = the custom id). */
    private static String flyingCustom(OpsSnapshot.SeatRow row) {
        if (row.custom() == null || row.custom().isEmpty()) {
            throw new IllegalArgumentException("Seat " + row.seatId()
                    + " is flying a stock hull, not a custom.");
        }
        return row.custom();
    }

    private static String occupantKey(String verb, OpsSnapshot.SeatRow row) {
        return verb + ":" + row.seatId() + ":" + foldKey(row.cidKey());
    }

    private static String foldKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }
}
