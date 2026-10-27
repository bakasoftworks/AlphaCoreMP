package cc.starsector.server.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale; // the endless word fold
import java.util.regex.Pattern; //

import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.WaveSchedule;
import cc.starsector.server.runtime.ModSet; //
import cc.starsector.server.runtime.OperatorCommandFile;
import cc.starsector.server.runtime.WireKnob; //
import cc.starsector.server.transform.RngPinKnobs;

/**
 * The Start-form match-tuning rows of {@code cc-server.properties} carved out of
 * {@link ServerOpsCore} (the {@code deferred-refactors.md} ServerOpsCore proactive-carve
 * row: "the three props-row twins (fortressDp / joinerDefault / waveScale) = the only remaining
 * extraction"): {@code match.fortressDp}, {@code match.fortressWaveScale},
 * {@code match.waveRosterFullDp},
 * {@code match.joinerDefaultVariant}, {@code match.waveCategory}
 * and {@code match.endless} - per family the row reader,
 * the upsert/remove editor (a pure {@code List<String> -> List<String>} transform on the file's
 * lines; the console reads/writes the file), the reject-never-clamp parse mirroring the serve's own
 * resolve law, and the field prefill the console shows. Dependency-clean: nothing here calls back
 * into the core (the tick-rate / descriptor rows and the password sidecar family are a different
 * concern and stay there; the map-size row + descriptor re-author applies are {@link OpsMatchApply}); the callers
 * are the ops layer - the Start flow ({@link ServerOpsLaunch#runStartFlow}), the web console
 * ({@link OpsConsole}, {@link OpsConsoleForms}), the core and the launcher. Bodies moved
 * VERBATIM - provenance and law citations inside each javadoc are the originals. Selftest rows:
 * {@link ServerOpsCoreSelftest} (the fortressDp / wave-scale / joiner-default blocks).
 */
public final class OpsMatchRows {
    private OpsMatchRows() {
    }

    /**
     * A line is a writer's marker when it is the current text, or the pre-scrub form a host's
     * {@code cc-server.properties} may still carry from an older build: the ten row markers here
     * and {@link ServerOpsCore#TICK_RATE_COMMENT} once ended in {@code " - <development id>"}
     * (their stem is {@code marker + " - "}); {@link OpsVoicePanel} and {@link InstalledModScan}
     * pass an explicit id-free head of their old text. WHY: a writer that recognized only the new
     * text would leave the old line orphaned above its row on the first rewrite. No id is kept in
     * the jar.
     */
    static boolean isMarker(String line, String marker, String legacyStem) {
        return line.equals(marker) || line.startsWith(legacyStem);
    }

    /** The ONE fortress wave-DP source row (0/absent = the authored AUTO
     * chain — {@code PersistentPilotIntentCombatServer.resolveFortressDpTotal} is the serve's
     * one resolve of the SAME row). */
    public static final String FORTRESS_DP_KEY = "match.fortressDp";
    /** Marker the shells keep directly above their row so removal (0 = AUTO default) is clean. */
    public static final String FORTRESS_DP_COMMENT =
            "# fortress total scheduled-wave DP (ServerOpsGui set-before-start; 0/absent = the"
                    + " authored AUTO chain)";
    /** Reject-never-clamp bounds for {@link #parseFortressDp} (the serve's [0, 5000] law). */
    public static final int FORTRESS_DP_MAX = 5000;

    /**
     * The ONE fortress per-wave DP-scale row — the serve's
     * {@code PersistentPilotIntentCombatServer.resolveFortressWaveScale} resolves the SAME row
     * (blank/absent = x1, the waves from before this row byte-identical). */
    public static final String FORTRESS_WAVE_SCALE_KEY = "match.fortressWaveScale";
    /** Marker the shells keep directly above their row so removal (blank = x1) is clean. */
    public static final String FORTRESS_WAVE_SCALE_COMMENT =
            "# fortress per-wave DP scale w2..w8 (ServerOpsGui set-before-start; blank/absent ="
                    + " x1; needs match.fortressDp > 0)";

    //
    /** The ONE joiner-default-ship source row — the serve
     * boots its standing default from it ({@code ServerConfig.resolve(...,
     * "match.joinerDefaultVariant","")}; blank/absent = the descriptor roster row) and the
     * {@code set-joiner-default} operator verb overrides it live. */
    public static final String JOINER_DEFAULT_KEY = "match.joinerDefaultVariant";
    /** Marker the shells keep directly above their row so removal (blank = roster default) is
     * clean — the {@link #FORTRESS_DP_COMMENT} idiom. */
    public static final String JOINER_DEFAULT_COMMENT =
            "# default joiner ship variant id (ServerOpsGui set-before-start; blank/absent = the"
                    + " descriptor roster default)";

    // ---------------------------------------------- fortress wave DP

    /** Raw value of the LAST uncommented {@code match.fortressDp=} row (the
     * {@link ServerOpsCore#tickRateRowValue} grammar: line-start exact, last-wins, commented mentions
     * never match). */
    public static String fortressDpRowValue(List<String> lines) {
        return rowValue(lines, FORTRESS_DP_KEY);
    }

    /**
     * The file's lines with the {@code match.fortressDp} row upserted — the
     * {@link ServerOpsCore#withTickRateRow} surgery verbatim: 0 (= AUTO, the default) REMOVES every
     * uncommented row (+ the shells' marker directly above each) so the tracked file returns
     * to its canonical bytes; a positive total replaces the first row in place (dropping
     * Properties-shadowed duplicates) or appends marker + row at the end. Callers validate
     * first ({@link #parseFortressDp}).
     */
    public static List<String> withFortressDpRow(List<String> lines, int dpTotal) {
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (line.startsWith(FORTRESS_DP_KEY + "=")) {
                if (!out.isEmpty() && isMarker(out.get(out.size() - 1), FORTRESS_DP_COMMENT,
                        FORTRESS_DP_COMMENT + " - ")) {
                    out.remove(out.size() - 1);
                }
                if (dpTotal != 0 && !placed) {
                    out.add(FORTRESS_DP_COMMENT);
                    out.add(FORTRESS_DP_KEY + "=" + dpTotal);
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (dpTotal != 0 && !placed) {
            out.add(FORTRESS_DP_COMMENT);
            out.add(FORTRESS_DP_KEY + "=" + dpTotal);
        }
        return out;
    }

    /**
     * Strict fortress-DP parse (reject-never-clamp; blank = 0 = AUTO, the tick-field blank
     * law): whole int in [0, {@link #FORTRESS_DP_MAX}] — the SAME window the serve's
     * {@code resolveFortressDpTotal} enforces, so a value this shell accepts can never be
     * one the serve refuses to boot on.
     */
    public static int parseFortressDp(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(trimmed);
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("fortress wave DP must be a whole number in"
                    + " [0, " + FORTRESS_DP_MAX + "] (0 = the authored AUTO chain), got '"
                    + trimmed + "'");
        }
        if (parsed < 0 || parsed > FORTRESS_DP_MAX) {
            throw new IllegalArgumentException("fortress wave DP must be in [0, "
                    + FORTRESS_DP_MAX + "] (0 = the authored AUTO chain), got '" + trimmed
                    + "'");
        }
        return parsed;
    }

    /** Fortress-DP field prefill for both shells (the {@code tickRateField} prefill idiom):
     * the explicit row when present, else {@code "0"} (= AUTO). */
    public static String fortressDpFieldPrefill(List<String> lines) {
        return fieldPrefill(lines, FORTRESS_DP_KEY, "0");
    }

    // ---------------------------------------------- the generic row trio

    /**
     * Raw value of the LAST uncommented {@code key=} row, or {@code null} when the file has none
     * (line-start exact, last-wins, commented mentions never match: the grammar the serve's
     * Properties load agrees with, a later duplicate shadowing an earlier one).
     *
     */
    public static String rowValue(List<String> lines, String key) {
        String value = null;
        for (String line : lines) {
            if (line.startsWith(key + "=")) {
                value = line.substring(key.length() + 1);
            }
        }
        return value;
    }

    /**
     * The file's lines with ONE {@code key=value} row, always written (never removed, whatever the
     * value): the first existing row is replaced IN PLACE, so the comment a host or the shipped
     * template keeps above it survives untouched; later duplicates are dropped (each with the
     * {@code comment} marker directly above it, when it is that marker), since the serve reads the
     * last one and the file must say one thing. A file without the row gets {@code comment} (when
     * non-null) and the row appended at its end. WHY never removed: a row the template ships
     * explicitly would otherwise vanish on the first Start at its default, and the host's file
     * would stop stating the setting the window showed. Callers validate {@code value} first; a
     * {@code null} value = no field on that shell = the lines unchanged (the StartFields null law).
     *
     */
    public static List<String> upsertRow(List<String> lines, String key, String value, String comment) {
        if (value == null) {
            return new ArrayList<>(lines);
        }
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (!line.startsWith(key + "=")) {
                out.add(line);
            } else if (!placed) {
                out.add(key + "=" + value);
                placed = true;
            } else if (comment != null && !out.isEmpty() && out.get(out.size() - 1).equals(comment)) {
                out.remove(out.size() - 1);
            }
        }
        if (!placed) {
            if (comment != null) {
                out.add(comment);
            }
            out.add(key + "=" + value);
        }
        return out;
    }

    /** A text field's prefill for both shells: the explicit row (trimmed) when present and
     * non-blank, else {@code defaultText} (the value the window offers for an absent row; the
     * caller states whether that is the serve's own absent default).
     * */
    public static String fieldPrefill(List<String> lines, String key, String defaultText) {
        String row = rowValue(lines, key);
        return row == null || row.trim().isEmpty() ? defaultText : row.trim();
    }

    /** The shortest echo of a refused field: a host's typo is quoted back so they can see it, but
     * a pasted page of text is clipped (the refusal is a dialog / a JSON error, never a log row). */
    private static String echo(String trimmed) {
        return trimmed.length() <= 16 ? trimmed : trimmed.substring(0, 16) + "...";
    }

    // ---------------------------------------------- seats per address

    /**
     * The join door's per-address seat allowance row: at most N live seats per internet address,
     * 0 = no limit, this computer exempt by its own address. The serve resolves absent as
     * {@link #SEATS_PER_SOURCE_DEFAULT}.
     *
     */
    public static final String SEATS_PER_SOURCE_KEY = "moderation.maxSeatsPerSource";
    /** The comment a file without the row gets above it when Start appends it. */
    public static final String SEATS_PER_SOURCE_COMMENT =
            "# seats per internet address (0 = no limit; this computer is never limited by its own address;"
                    + " a tunnel gateway's address counts unless listed in moderation.sourceCapExempt)";
    /** The compiled default the serve uses for an absent row: one seat per address. */
    public static final String SEATS_PER_SOURCE_DEFAULT = "1";
    /** The largest allowance: the match's own seat cap (a larger number limits nothing more). */
    public static final int SEATS_PER_SOURCE_MAX = MatchDescriptors.MAX_SEATS;
    /** The refusal's stem; the message appends the typed text. */
    public static final String R_SEATS_PER_SOURCE =
            "Seats per address must be a whole number from 0 to " + SEATS_PER_SOURCE_MAX;

    /**
     * Strict Seats-per-address parse (reject-never-clamp): the trimmed text is 1..3 ASCII digits
     * whose value is 0..{@link #SEATS_PER_SOURCE_MAX}. A BLANK field is REFUSED, never read as the
     * default or as off: a cleared field silently turning the cap off (or back on) is the
     * mistake this refuses. Returns the canonical value ({@code "007"} = 7).
     *
     */
    public static int parseSeatsPerSource(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() || trimmed.length() > 3 || !trimmed.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException(R_SEATS_PER_SOURCE + " (got '" + echo(trimmed) + "').");
        }
        int parsed = Integer.parseInt(trimmed);
        if (parsed > SEATS_PER_SOURCE_MAX) {
            throw new IllegalArgumentException(R_SEATS_PER_SOURCE + " (got '" + echo(trimmed) + "').");
        }
        return parsed;
    }

    /** Seats-per-address field prefill: the row, else {@link #SEATS_PER_SOURCE_DEFAULT}.
     * */
    public static String seatsPerSourceFieldPrefill(List<String> lines) {
        return fieldPrefill(lines, SEATS_PER_SOURCE_KEY, SEATS_PER_SOURCE_DEFAULT);
    }

    // ---------------------------------------------- all skills

    /**
     * The all-skills captain row: {@code true} = every player's ship gets a captain with every
     * combat skill. The serve's reader is a STRICT {@code true|false} switch (anything else stops
     * the boot), so the window writes exactly those two words.
     *
     */
    public static final String ALL_SKILLS_KEY = "server.officerSkillsAll";
    /** The comment a file without the row gets above it when Start appends it. */
    public static final String ALL_SKILLS_COMMENT =
            "# true = every player's ship gets a captain with every combat skill";
    /** The refusal's stem; the message appends the typed text. */
    public static final String R_ALL_SKILLS = "All skills must be on or off";

    /** All-skills combo word ({@code on} / {@code off}, case-folded, trimmed) to the row value
     * ({@code "true"} / {@code "false"}); anything else is refused. */
    public static String parseAllSkills(String text) {
        String trimmed = text == null ? "" : text.trim();
        String folded = trimmed.toLowerCase(Locale.ROOT);
        if (folded.equals("on")) {
            return "true";
        }
        if (folded.equals("off")) {
            return "false";
        }
        throw new IllegalArgumentException(R_ALL_SKILLS + " (got '" + echo(trimmed) + "').");
    }

    /** All-skills combo prefill: {@code off} for an explicit {@code false} row, else {@code on}
     * (the shipped template's value, offered for an absent row too, although the serve alone reads
     * an absent row as off; Start always writes the row, so after a Start the file says what the
     * window showed). */
    public static String allSkillsFieldPrefill(List<String> lines) {
        return "false".equals(fieldPrefill(lines, ALL_SKILLS_KEY, "true")) ? "off" : "on";
    }

    // ---------------------------------------------- deterministic replay pins

    /**
     * The host's replay-pin switch, {@link RngPinKnobs#ENABLED_FILE_KEY} (never a second
     * literal): {@code true} = the server pins its random numbers to a per-serve seed, so a
     * recorded match can be replayed and re-simulated exactly; {@code false} = no pins, and that
     * serve's recording cannot be re-simulated. The window only WRITES the row: the serve JVM's
     * own startup reads the file and decides (no launch argument is composed from it anywhere),
     * and the launcher refuses anything but exactly {@code true|false}, so the window writes
     * exactly those two words. Label on both shells: {@link #RNG_PINS_LABEL}.
     *
     */
    public static final String RNG_PINS_KEY = RngPinKnobs.ENABLED_FILE_KEY;
    /** The control's label, one string for the Swing form and the web page. */
    public static final String RNG_PINS_LABEL = "Deterministic replay pins (RNG)";
    /** The comment a file without the row gets above it when Start appends it. */
    public static final String RNG_PINS_COMMENT =
            "# true = random numbers are pinned to a per-serve seed so a recorded match can be replayed and"
                    + " re-simulated; false = no pins (that serve's recording cannot be re-simulated)";
    /** The refusal's stem; the message appends the typed text. */
    public static final String R_RNG_PINS = RNG_PINS_LABEL + " must be on or off";

    /**
     * The debug-watch posture row a "Launch debug WATCH" press writes {@code true} and every plain
     * Start writes {@code false} ({@link StartFields#debugWatch()}; ALWAYS written in a Start's
     * Kill, write, spawn order, so no serve life reads a stale word). The serve JVM reads it from
     * the file the console passes as {@code --config} — the registered INSTRUMENT knob
     * (PostureRegistry {@code server.clientDebug}; consumers PilotServeSession + the datagram
     * server + the chat verbs); no launch argument is composed from it. Same literal as
     * PostureRegistry's row: the knob table is the spelling's home, this is its ops-window name.
     *
     */
    public static final String CLIENT_DEBUG_KEY = "server.clientDebug";
    /** The comment a file without the row gets above it when the upsert appends it. */
    public static final String CLIENT_DEBUG_COMMENT =
            "# true = a test match: every player gets hull bars and the debug keys; false = normal play."
                    + " Launch debug watch sets true; Start sets false.";

    /** Replay-pins combo word ({@code on} / {@code off}, case-folded, trimmed) to the row value
     * ({@code "true"} / {@code "false"}); anything else is refused. */
    public static String parseRngPins(String text) {
        String trimmed = text == null ? "" : text.trim();
        String folded = trimmed.toLowerCase(Locale.ROOT);
        if (folded.equals("on")) {
            return "true";
        }
        if (folded.equals("off")) {
            return "false";
        }
        throw new IllegalArgumentException(R_RNG_PINS + " (got '" + echo(trimmed) + "').");
    }

    /** Replay-pins combo prefill: {@code off} for an explicit {@code false} row, else {@code on}.
     * Unlike All skills (whose absent row the serve reads as off), an ABSENT row reads {@code on}
     * here AND in the serve: the compiled default is true, the shipped template says
     * {@code true}, and the launcher's receipt stamps an absent row as on — three places that
     * agree. */
    public static String rngPinsFieldPrefill(List<String> lines) {
        return "false".equals(fieldPrefill(lines, RNG_PINS_KEY, "true")) ? "off" : "on";
    }

    // ---------------------------------------------- fortress wave scale

    /** Raw value of the LAST uncommented {@code match.fortressWaveScale=} row (the
     * {@link #fortressDpRowValue} grammar). */
    public static String fortressWaveScaleRowValue(List<String> lines) {
        String value = null;
        for (String line : lines) {
            if (line.startsWith(FORTRESS_WAVE_SCALE_KEY + "=")) {
                value = line.substring(FORTRESS_WAVE_SCALE_KEY.length() + 1);
            }
        }
        return value;
    }

    /**
     * The file's lines with the {@code match.fortressWaveScale} row upserted — the
     * {@link #withFortressDpRow} surgery verbatim on the CANONICAL csv from
     * {@link #parseFortressWaveScaleRow}: blank REMOVES every uncommented row (+ the shells'
     * marker directly above each); a value replaces the first row in place or appends
     * marker + row. {@code null} = "this shell has no scale field" and returns the lines
     * untouched — a web-shell Start must never strip the file's live tuning.
     */
    public static List<String> withFortressWaveScaleRow(List<String> lines, String scaleCsv) {
        if (scaleCsv == null) {
            return lines;
        }
        boolean keep = !scaleCsv.isEmpty();
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (line.startsWith(FORTRESS_WAVE_SCALE_KEY + "=")) {
                if (!out.isEmpty() && isMarker(out.get(out.size() - 1),
                        FORTRESS_WAVE_SCALE_COMMENT, FORTRESS_WAVE_SCALE_COMMENT + " - ")) {
                    out.remove(out.size() - 1);
                }
                if (keep && !placed) {
                    out.add(FORTRESS_WAVE_SCALE_COMMENT);
                    out.add(FORTRESS_WAVE_SCALE_KEY + "=" + scaleCsv);
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (keep && !placed) {
            out.add(FORTRESS_WAVE_SCALE_COMMENT);
            out.add(FORTRESS_WAVE_SCALE_KEY + "=" + scaleCsv);
        }
        return out;
    }

    /**
     * Strict wave-scale parse for the shells: blank = {@code ""} (= x1, row removed); otherwise
     * the serve's own {@link WaveSchedule#parseFortressWaveScale} law (exactly 7 finite decimals
     * in [0.5, 4.0], reject-never-clamp) must accept it and the row lands as the typed text
     * minus whitespace — so a value this shell accepts is never one the serve refuses to boot
     * on, and a prefilled field re-Started writes the file's own bytes back.
     */
    public static String parseFortressWaveScaleRow(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        if (compact.isEmpty()) {
            return "";
        }
        WaveSchedule.parseFortressWaveScale(compact);
        return compact;
    }

    /** The serve's cross-field law mirrored at apply: a scale row with {@code match.fortressDp}
     * at 0 (AUTO) REFUSES at boot ({@code WaveSchedule.forShape}), so the shell rejects it
     * BEFORE the write instead of spawning a serve that dies on its own config. */
    public static void requireFortressWaveScaleAllowed(int fortressDp, String scaleCsv) {
        if (scaleCsv != null && !scaleCsv.isEmpty() && fortressDp == 0) {
            throw new IllegalArgumentException("wave scale needs Total wave DP > 0 (the serve"
                    + " refuses a scale row on the authored AUTO chain): clear the scale or set"
                    + " a total");
        }
    }

    /** Wave-scale field prefill: the explicit row when present, else blank (= x1). */
    public static String fortressWaveScaleFieldPrefill(List<String> lines) {
        String row = fortressWaveScaleRowValue(lines);
        return row == null ? "" : row.trim();
    }

    // ------------------------------------ live-roster wave DP
    //

    /** The ONE live-roster wave-DP source row — the
     * connected roster's total ship DP at which scheduled waves reach full size (waves shrink
     * below it, floor 25%). The serve resolves the SAME key at boot
     * ({@code cc.match.waveRosterFullDp}; blank/0/absent = static waves, the pre-1408 drain
     * byte-identical); a non-zero value on the AUTO chain ({@code match.fortressDp} 0) THROWS at
     * boot, so the shells refuse it pre-write ({@link #requireWaveRosterFullDpAllowed}). */
    public static final String WAVE_ROSTER_FULL_DP_KEY = "match.waveRosterFullDp";
    /** Marker the shells keep directly above their row so removal (0 = static) is clean — the
     * {@link #FORTRESS_WAVE_SCALE_COMMENT} idiom. */
    public static final String WAVE_ROSTER_FULL_DP_COMMENT =
            "# fortress live-roster wave DP - roster DP at which waves reach full size"
                    + " (ServerOpsGui set-before-start; 0/absent = static waves; needs"
                    + " match.fortressDp > 0)";
    /** Reject-never-clamp upper bound (the knob's [0, 5000] window). */
    public static final int WAVE_ROSTER_FULL_DP_MAX = 5000;

    /** Raw value of the LAST uncommented
     * {@code match.waveRosterFullDp=} row (null = absent; the {@link #fortressDpRowValue}
     * grammar). */
    public static String waveRosterFullDpRowValue(List<String> lines) {
        return lastRowValue(lines, WAVE_ROSTER_FULL_DP_KEY);
    }

    /**
     * The file's lines with the
     * {@code match.waveRosterFullDp} row upserted — {@link #withFortressWaveScaleRow}'s surgery
     * verbatim on the CANONICAL value from {@link #parseWaveRosterFullDpRow}: {@code ""} (0 =
     * static) REMOVES every uncommented row (+ the marker directly above each) so the tracked
     * file returns to its canonical bytes; a value replaces the first row in place or appends
     * marker + row. {@code null} = "this shell has no roster-DP field" and returns the SAME list
     * instance — a shell without the field never strips the file's live tuning.
     */
    public static List<String> withWaveRosterFullDpRow(List<String> lines, String value) {
        if (value == null) {
            return lines;
        }
        boolean keep = !value.isEmpty();
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (line.startsWith(WAVE_ROSTER_FULL_DP_KEY + "=")) {
                if (!out.isEmpty() && isMarker(out.get(out.size() - 1),
                        WAVE_ROSTER_FULL_DP_COMMENT, WAVE_ROSTER_FULL_DP_COMMENT + " - ")) {
                    out.remove(out.size() - 1);
                }
                if (keep && !placed) {
                    out.add(WAVE_ROSTER_FULL_DP_COMMENT);
                    out.add(WAVE_ROSTER_FULL_DP_KEY + "=" + value);
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (keep && !placed) {
            out.add(WAVE_ROSTER_FULL_DP_COMMENT);
            out.add(WAVE_ROSTER_FULL_DP_KEY + "=" + value);
        }
        return out;
    }

    /**
     * Strict roster-DP parse for the shells (the
     * {@link #parseFortressDp} reject idiom — reject-never-clamp): blank or 0 = {@code ""} (=
     * static waves, row removed); otherwise a STRICT whole int in [1,
     * {@link #WAVE_ROSTER_FULL_DP_MAX}] returned in canonical decimal (so the row the serve reads
     * is always plain digits). Anything else THROWS {@link IllegalArgumentException}.
     */
    public static String parseWaveRosterFullDpRow(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int parsed;
        try {
            parsed = Integer.parseInt(trimmed);
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("roster wave DP must be a whole number in [0, "
                    + WAVE_ROSTER_FULL_DP_MAX + "] (0 = static waves), got '" + trimmed + "'");
        }
        if (parsed < 0 || parsed > WAVE_ROSTER_FULL_DP_MAX) {
            throw new IllegalArgumentException("roster wave DP must be in [0, "
                    + WAVE_ROSTER_FULL_DP_MAX + "] (0 = static waves), got '" + trimmed + "'");
        }
        return parsed == 0 ? "" : Integer.toString(parsed);
    }

    /** The serve's cross-field law mirrored at apply
     * (the {@link #requireFortressWaveScaleAllowed} idiom): a non-zero roster-DP row with
     * {@code match.fortressDp} at 0 (AUTO) THROWS at boot, so the shell rejects it BEFORE the
     * write instead of spawning a serve that dies on its own config. {@code null}/{@code ""}
     * (no field / static) always pass. */
    public static void requireWaveRosterFullDpAllowed(int fortressDp, String value) {
        if (value != null && !value.isEmpty() && fortressDp == 0) {
            throw new IllegalArgumentException("roster wave DP needs Total wave DP > 0 (the"
                    + " serve refuses a live-roster row on the authored AUTO chain): set it to 0"
                    + " or set a total");
        }
    }

    /** The roster-DP row the file carries, LENIENT
     * (never throws, no parse): {@code ""} when absent, blank or a literal zero (= static),
     * else the trimmed row text — a lawful value OR a garbage one, both of which a serve on the
     * AUTO chain refuses at boot. Feeds the Start flow's cross-field check when the shell has no
     * field (the row it leaves untouched), so that check never needs a catch. */
    public static String waveRosterFullDpRowLenient(List<String> lines) {
        String row = waveRosterFullDpRowValue(lines);
        String t = row == null ? "" : row.trim();
        return t.isEmpty() || t.matches("[+-]?0+") ? "" : t;
    }

    /** roster-DP field prefill — the explicit row
     * trimmed when present, else {@code "0"} (= static waves; the fortressDp prefill idiom). */
    public static String waveRosterFullDpFieldPrefill(List<String> lines) {
        String row = waveRosterFullDpRowValue(lines);
        return row == null || row.trim().isEmpty() ? "0" : row.trim();
    }

    // ------------------------------- live-roster wave multiplier

    /** The ONE host-set multiplier on the live-roster
     * wave share (the connected roster's DP over {@link #WAVE_ROSTER_FULL_DP_KEY}), so a host can
     * make a small roster face bigger (above 1) or smaller (below 1) waves. The serve reads the
     * SAME key at boot with the SAME grammar; absent / {@code 1} = no change; a non-unit value with
     * the live roster off ({@link #WAVE_ROSTER_FULL_DP_KEY} absent / 0) THROWS at boot, so the
     * shells refuse it pre-write ({@link #requireWaveRosterMultiplierAllowed}). */
    public static final String WAVE_ROSTER_MULTIPLIER_KEY = "match.waveRosterMultiplier";
    /** Marker the shells keep directly above their row so removal (unit = no change) is clean —
     * the {@link #WAVE_ROSTER_FULL_DP_COMMENT} idiom. */
    public static final String WAVE_ROSTER_MULTIPLIER_COMMENT =
            "# fortress live-roster wave multiplier - scales the connected roster's share of each"
                    + " wave (ServerOpsGui set-before-start; absent/1 = no change; needs"
                    + " match.waveRosterFullDp > 0)";
    /** Reject-never-clamp lower bound of the multiplier window [0.1, 10.0]. */
    public static final double WAVE_ROSTER_MULTIPLIER_MIN = 0.1;
    /** Reject-never-clamp upper bound of the multiplier window [0.1, 10.0]. */
    public static final double WAVE_ROSTER_MULTIPLIER_MAX = 10.0;

    /** Raw value of the LAST uncommented
     * {@code match.waveRosterMultiplier=} row (null = absent; the {@link #fortressDpRowValue}
     * grammar). */
    public static String waveRosterMultiplierRowValue(List<String> lines) {
        return lastRowValue(lines, WAVE_ROSTER_MULTIPLIER_KEY);
    }

    /**
     * The file's lines with the
     * {@code match.waveRosterMultiplier} row upserted — {@link #withWaveRosterFullDpRow}'s surgery
     * verbatim on the CANONICAL value from {@link #parseWaveRosterMultiplierRow}: {@code ""} (unit =
     * no change) REMOVES every uncommented row (+ the marker directly above each) so the tracked
     * file returns to its canonical bytes; a value replaces the first row in place or appends
     * marker + row. {@code null} = "this shell has no multiplier field" and returns the SAME list
     * instance — a shell without the field never strips the file's live tuning.
     */
    public static List<String> withWaveRosterMultiplierRow(List<String> lines, String value) {
        if (value == null) {
            return lines;
        }
        boolean keep = !value.isEmpty();
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (line.startsWith(WAVE_ROSTER_MULTIPLIER_KEY + "=")) {
                if (!out.isEmpty() && isMarker(out.get(out.size() - 1),
                        WAVE_ROSTER_MULTIPLIER_COMMENT, WAVE_ROSTER_MULTIPLIER_COMMENT + " - ")) {
                    out.remove(out.size() - 1);
                }
                if (keep && !placed) {
                    out.add(WAVE_ROSTER_MULTIPLIER_COMMENT);
                    out.add(WAVE_ROSTER_MULTIPLIER_KEY + "=" + value);
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (keep && !placed) {
            out.add(WAVE_ROSTER_MULTIPLIER_COMMENT);
            out.add(WAVE_ROSTER_MULTIPLIER_KEY + "=" + value);
        }
        return out;
    }

    /**
     * Strict multiplier parse for the shells (reject-never-
     * clamp, the serve's own grammar): blank or a unit value = {@code ""} (no change, row removed);
     * otherwise the trimmed text must be plain decimal digits ({@code [0-9]+(\.[0-9]+)?}: no sign,
     * no exponent, no bare leading dot) parsing to a finite double in
     * [{@link #WAVE_ROSTER_MULTIPLIER_MIN}, {@link #WAVE_ROSTER_MULTIPLIER_MAX}], returned as its
     * canonical {@link Double#toString} (so the row the serve reads is one spelling per value).
     * Anything else THROWS {@link IllegalArgumentException}.
     */
    public static String parseWaveRosterMultiplierRow(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String why = "roster wave multiplier must be a plain decimal from "
                + WAVE_ROSTER_MULTIPLIER_MIN + " to " + WAVE_ROSTER_MULTIPLIER_MAX
                + " (1 = no change), got '" + trimmed + "'";
        if (!trimmed.matches("[0-9]+(\\.[0-9]+)?")) {
            throw new IllegalArgumentException(why);
        }
        double parsed = Double.parseDouble(trimmed);
        if (!Double.isFinite(parsed) || parsed < WAVE_ROSTER_MULTIPLIER_MIN
                || parsed > WAVE_ROSTER_MULTIPLIER_MAX) {
            throw new IllegalArgumentException(why);
        }
        return parsed == 1.0 ? "" : Double.toString(parsed);
    }

    /** The serve's cross-field law mirrored at apply (the
     * {@link #requireWaveRosterFullDpAllowed} idiom): a non-unit multiplier while the EFFECTIVE
     * roster DP is off THROWS at boot (static waves have no roster share to scale), so the shell
     * rejects it BEFORE the write. {@code effectiveRosterDp} = what the file WILL carry for
     * {@link #WAVE_ROSTER_FULL_DP_KEY}: the canonical field value, or the lenient file row when the
     * shell has no roster-DP field; {@code ""} or {@code null} = off. A {@code null} / {@code ""}
     * multiplier (no field / unit) always passes. */
    public static void requireWaveRosterMultiplierAllowed(String effectiveRosterDp, String multiplier) {
        if (multiplier != null && !multiplier.isEmpty()
                && (effectiveRosterDp == null || effectiveRosterDp.isEmpty())) {
            throw new IllegalArgumentException("Roster x needs Roster DP above 0 (the server refuses"
                    + " a multiplier with static waves): set Roster DP, or set Roster x to 1");
        }
    }

    /** The multiplier row the file carries, LENIENT (never
     * throws): {@code ""} when absent, blank or a literal unit ({@code 1}, {@code 1.0},
     * {@code 01.00}: every spelling the strict grammar reads as exactly 1), else the trimmed row
     * text — a lawful value OR a garbage one, both of which a serve with static waves refuses at
     * boot. Feeds the Start flow's cross-field check when the shell has no multiplier field (the
     * row it leaves untouched), so that check never needs a catch. */
    public static String waveRosterMultiplierRowLenient(List<String> lines) {
        String row = waveRosterMultiplierRowValue(lines);
        String t = row == null ? "" : row.trim();
        return t.isEmpty() || t.matches("0*1(\\.0+)?") ? "" : t;
    }

    /** Multiplier field prefill — the explicit row trimmed
     * when present, else {@code "1"} (= no change; the roster-DP prefill idiom). */
    public static String waveRosterMultiplierFieldPrefill(List<String> lines) {
        String row = waveRosterMultiplierRowValue(lines);
        return row == null || row.trim().isEmpty() ? "1" : row.trim();
    }

    // ---------------------------------------------- joiner default ship
    //

    /** Raw value of the LAST uncommented {@code match.joinerDefaultVariant=} row ({@code null}
     * when absent) — the {@link #fortressDpRowValue} grammar: line-start exact, last-wins,
     * commented mentions never match. */
    public static String joinerDefaultRowValue(List<String> lines) {
        String value = null;
        for (String line : lines) {
            if (line.startsWith(JOINER_DEFAULT_KEY + "=")) {
                value = line.substring(JOINER_DEFAULT_KEY.length() + 1);
            }
        }
        return value;
    }

    /**
     * Charset/length gate for a typed joiner-default ship id, single-sourced through the REAL
     * {@link OperatorCommandFile#parse} grammar (the {@link ServerOpsCore#sanitizedMatchPassword} idiom —
     * whatever this accepts the operator file parses identically): blank ⇒ {@code ""} (the
     * descriptor roster default — no row; the literal {@code none} folds to {@code ""} too, the
     * verb path's fold), else a 1..64-char {@code [A-Za-z0-9._:-]} token
     * returned verbatim (case preserved; the door canonicalizes + refuses an unpickable id loud
     * in the server log, so PICKABILITY is not checked here). A charset/length violation throws.
     */
    public static String parseJoinerDefault(String text) {
        String token = text == null ? "" : text.trim();
        if (token.isEmpty()) {
            return "";
        }
        OperatorCommandFile.ParseResult r =
                OperatorCommandFile.parse(List.of("set-joiner-default " + token));
        if (r.commands().size() != 1
                || r.commands().get(0).verb() != OperatorCommandFile.Verb.SET_JOINER_DEFAULT) {
            throw new IllegalArgumentException("joiner default ship id must be 1.."
                    + OperatorCommandFile.JOINER_DEFAULT_MAX_CHARS + " chars of letters, digits,"
                    + " . _ : - (blank = the descriptor roster default), got '" + token + "'");
        }
        return r.commands().get(0).text(); // "" for none (parser-folded), else verbatim
    }

    /**
     * The exact operator-file line for {@code set-joiner-default}:
     * a blank field clears to the descriptor roster default ({@code none}); a typed
     * id rides through {@link #parseJoinerDefault} (charset/length law) — exactly one token after
     * the verb, so the serve-side {@code OperatorCommandFile.parse} (which rejects any multi-token
     * line) accepts it by construction. Pickability is the door's call, refused loud in its log.
     */
    public static String joinerDefaultLine(String text) {
        String token = parseJoinerDefault(text);
        return "set-joiner-default " + (token.isEmpty() ? "none" : token);
    }

    /**
     * The file's lines with the {@code match.joinerDefaultVariant} row upserted — the
     * {@link #withFortressDpRow} surgery verbatim: a blank value (the roster default) REMOVES
     * every uncommented row (+ the shells' marker directly above each) so the tracked file
     * returns to its canonical bytes; a non-blank id replaces the first row in place (dropping
     * Properties-shadowed duplicates) or appends marker + row at the end. Callers validate first
     * ({@link #parseJoinerDefault}).
     */
    public static List<String> withJoinerDefaultRow(List<String> lines, String value) {
        String token = value == null ? "" : value.trim();
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (line.startsWith(JOINER_DEFAULT_KEY + "=")) {
                if (!out.isEmpty() && isMarker(out.get(out.size() - 1), JOINER_DEFAULT_COMMENT,
                        JOINER_DEFAULT_COMMENT + " - ")) {
                    out.remove(out.size() - 1);
                }
                if (!token.isEmpty() && !placed) {
                    out.add(JOINER_DEFAULT_COMMENT);
                    out.add(JOINER_DEFAULT_KEY + "=" + token);
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (!token.isEmpty() && !placed) {
            out.add(JOINER_DEFAULT_COMMENT);
            out.add(JOINER_DEFAULT_KEY + "=" + token);
        }
        return out;
    }

    /** Joiner-default field prefill for both shells (the {@code fortressDpFieldPrefill} idiom):
     * the explicit row when present, else {@code ""} (blank field = the roster default). */
    public static String joinerDefaultFieldPrefill(List<String> lines) {
        String row = joinerDefaultRowValue(lines);
        return row == null ? "" : row.trim();
    }

    // ---------------------------------------------- wave category

    /** The ONE fortress wave-category source row — the serve resolves the SAME
     * row ({@code PersistentPilotIntentCombatServer.resolveWaveCategory}; blank/absent =
     * {@code remnant}, the standing chain byte-identical) and the {@code wave-category}
     * operator verb retargets a RUNNING serve at its next round boundary. */
    public static final String WAVE_CATEGORY_KEY = "match.waveCategory";
    /** Marker the shells keep directly above their row so removal (remnant = default) is clean. */
    public static final String WAVE_CATEGORY_COMMENT =
            "# fortress wave category/faction (ServerOpsGui set-before-start; blank/absent ="
                    + " remnant, the standing chain)";

    /** Raw value of the LAST uncommented {@code match.waveCategory=} row (the
     * {@link #fortressDpRowValue} grammar). */
    public static String waveCategoryRowValue(List<String> lines) {
        String value = null;
        for (String line : lines) {
            if (line.startsWith(WAVE_CATEGORY_KEY + "=")) {
                value = line.substring(WAVE_CATEGORY_KEY.length() + 1);
            }
        }
        return value;
    }

    /**
     * The file's lines with the {@code match.waveCategory} row upserted — the
     * {@link #withFortressWaveScaleRow} surgery verbatim on the canonical value from
     * {@link #parseWaveCategoryRow}: blank (= remnant, the default — the parse folds the
     * literal {@code remnant} to blank) REMOVES every uncommented row (+ the shells' marker
     * directly above each) so the tracked file returns to its canonical bytes; a category
     * replaces the first row in place or appends marker + row. {@code null} = "this shell has
     * no category control" and returns the lines untouched — a web-shell Start must never
     * strip the file's live tuning.
     */
    public static List<String> withWaveCategoryRow(List<String> lines, String category) {
        if (category == null) {
            return lines;
        }
        boolean keep = !category.isEmpty();
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (line.startsWith(WAVE_CATEGORY_KEY + "=")) {
                if (!out.isEmpty() && isMarker(out.get(out.size() - 1), WAVE_CATEGORY_COMMENT,
                        WAVE_CATEGORY_COMMENT + " - ")) {
                    out.remove(out.size() - 1);
                }
                if (keep && !placed) {
                    out.add(WAVE_CATEGORY_COMMENT);
                    out.add(WAVE_CATEGORY_KEY + "=" + category);
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (keep && !placed) {
            out.add(WAVE_CATEGORY_COMMENT);
            out.add(WAVE_CATEGORY_KEY + "=" + category);
        }
        return out;
    }

    /**
     * Strict wave-category parse for the shells: the serve's own
     * {@link WaveSchedule#parseWaveCategory} law first (blank = remnant; unknown THROWS,
     * reject-never-clamp), then {@code remnant} folds to {@code ""} (= the default, row
     * removed — the fortressDp 0-removes-row identity, so a default Start leaves the tracked
     * file byte-identical).
     */
    public static String parseWaveCategoryRow(String text) {
        String category = WaveSchedule.parseWaveCategory(text);
        return "remnant".equals(category) ? "" : category;
    }

    /**
     * The exact operator-file line for the LIVE {@code wave-category} verb (the
     * {@link #joinerDefaultLine} two-channel idiom): the argument passes the serve's own
     * category law, and {@code remnant} stays a REAL target here (switching a running serve
     * back to the standing chain), never folded away like the row's default identity.
     */
    public static String waveCategoryLine(String text) {
        return "wave-category " + WaveSchedule.parseWaveCategory(text);
    }

    /** Wave-category prefill for the shells: the explicit row when present, else
     * {@code remnant} (the combo's default identity). */
    public static String waveCategoryFieldPrefill(List<String> lines) {
        String row = waveCategoryRowValue(lines);
        return row == null || row.trim().isEmpty() ? "remnant" : row.trim();
    }

    // ------------------------------------------- endless chain

    /**
     * The ONE {@code match.endless} source row — does the
     * wave chain WRAP after its last wave clears instead of latching the DEFEND victory? The
     * serve reads the SAME key at boot ({@code cc.match.endless}, resolved once by
     * {@code PersistentPilotIntentCombatServer.resolveMatchEndlessOrNull}; absent = the compiled
     * default ON) and the {@code endless} operator verb flips a RUNNING serve live. TWO
     * vocabularies, mapped in ONE place here: the ROW is the knob's STRICT boolean
     * ({@code true}/{@code false} — anything else throws at serve entry), the combo and the LIVE
     * verb speak {@code on}/{@code off}.
     */
    public static final String ENDLESS_KEY = "match.endless";
    /** Marker the shells keep directly above their row (the {@link #WAVE_CATEGORY_COMMENT}
     * idiom) so a removal is clean. */
    public static final String ENDLESS_COMMENT =
            "# endless wave chain - wrap after the last wave clears (ServerOpsGui"
                    + " set-before-start; the serve reads cc.match.endless, absent = ON)";

    /** Raw value of the LAST uncommented
     * {@code match.endless=} row (the {@link #waveCategoryRowValue} grammar). */
    public static String endlessRowValue(List<String> lines) {
        String value = null;
        for (String line : lines) {
            if (line.startsWith(ENDLESS_KEY + "=")) {
                value = line.substring(ENDLESS_KEY.length() + 1);
            }
        }
        return value;
    }

    /**
     * The file's lines with the {@code match.endless} row
     * upserted — {@link #withWaveCategoryRow}'s surgery verbatim on the BOOLEAN row value from
     * {@link #parseEndlessRow}: blank removes every uncommented row (+ the shells' marker
     * directly above each) so the tracked file returns to its canonical bytes (absent = the
     * serve's default ON); a value replaces the first row in place or appends marker + row.
     * {@code null} = "this shell has no endless control" and returns the lines untouched — a
     * web-shell Start must never strip the file's live tuning.
     */
    public static List<String> withEndlessRow(List<String> lines, String value) {
        if (value == null) {
            return lines;
        }
        boolean keep = !value.isEmpty();
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (line.startsWith(ENDLESS_KEY + "=")) {
                if (!out.isEmpty() && isMarker(out.get(out.size() - 1), ENDLESS_COMMENT,
                        ENDLESS_COMMENT + " - ")) {
                    out.remove(out.size() - 1);
                }
                if (keep && !placed) {
                    out.add(ENDLESS_COMMENT);
                    out.add(ENDLESS_KEY + "=" + value);
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (keep && !placed) {
            out.add(ENDLESS_COMMENT);
            out.add(ENDLESS_KEY + "=" + value);
        }
        return out;
    }

    /**
     * The strict endless gate BOTH channels share —
     * exactly {@code on}/{@code off}, ASCII-folded, blank/null = {@code on} (the serve's
     * compiled default); anything else THROWS (reject-never-clamp, the
     * {@link #parseWaveCategoryRow} law). Returns the folded word the combo and the operator
     * verb speak.
     */
    public static String parseEndlessWord(String text) {
        String word = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (word.isEmpty() || word.equals("on")) {
            return "on";
        }
        if (word.equals("off")) {
            return "off";
        }
        throw new IllegalArgumentException("endless must be 'on' or 'off' (blank = on, the"
                + " serve's default); got '" + text + "'");
    }

    /**
     * The {@code match.endless} ROW value for a picked
     * word — the knob is a STRICT boolean
     * ({@code PersistentPilotIntentCombatServer.resolveMatchEndlessOrNull} takes only
     * {@code true}/{@code false}, blank = default ON), so {@code on} -> {@code true} and
     * {@code off} -> {@code false}. The word law (and its throw) is
     * {@link #parseEndlessWord}'s.
     */
    public static String parseEndlessRow(String text) {
        return "on".equals(parseEndlessWord(text)) ? "true" : "false";
    }

    /**
     * The exact operator-file line for the LIVE
     * {@code endless} verb (the {@link #waveCategoryLine} two-channel idiom) —
     * {@code "endless on"} / {@code "endless off"}, the grammar
     * {@code OperatorCommandFile.Verb.SET_ENDLESS} parses. The serve owns every semantic (an
     * {@code on} beside the per-clear reset lane comes back as a refused receipt, never a
     * silent flip).
     */
    public static String endlessLine(String text) {
        return "endless " + parseEndlessWord(text);
    }

    /** Endless prefill for the shells — the explicit row
     * read back in the COMBO's vocabulary ({@code false} -> {@code off}; absent, blank or a
     * garbage row -> {@code on}, the serve's default: a prefill never throws in a constructor,
     * the bad row bites at serve entry). */
    public static String endlessFieldPrefill(List<String> lines) {
        String row = endlessRowValue(lines);
        return row != null && "false".equals(row.trim().toLowerCase(Locale.ROOT)) ? "off" : "on";
    }

    // --------------------------------------------- battle terrain
    //

    /**
     * The TWO terrain source rows — {@code match.terrain}
     * (the {@link WireKnob#TERRAIN} word) and {@code match.terrainLevel} (the
     * {@link WireKnob#TERRAIN_LEVEL} percent). The serve reads both at its FIRST admission
     * ({@code WireKnobState} via {@code PostureRegistry}'s file-key column; absent = {@code none}
     * / {@code 100}); the {@code terrain} operator verb flips a RUNNING serve live
     * ({@code AdminTerrainVerb}: both knobs, one stat re-apply, one posture respool). The words
     * are the WireKnob choice tables and nothing else — never a literal copy here, so a table
     * edit moves the GUI with it. The endless two-channel idiom (Start writes the rows, 'Set
     * LIVE' appends the verb), one marker above the two-row stanza.
     */
    public static final String TERRAIN_KEY = "match.terrain";
    /** The terrain strength row (percent word from the level table). */
    public static final String TERRAIN_LEVEL_KEY = "match.terrainLevel";
    /** Marker the shells keep directly above the two-row stanza so a removal ({@code none}) is
     * clean — the {@link #ENDLESS_COMMENT} idiom. */
    public static final String TERRAIN_COMMENT =
            "# battle terrain - stat terrain word + strength percent (ServerOpsGui"
                    + " set-before-start; the serve reads cc.match.terrain /"
                    + " cc.match.terrainLevel, absent = none / 100)";

    /** Raw value of the LAST uncommented {@code match.terrain=} row (null =
     * absent; the {@link #endlessRowValue} grammar). */
    public static String terrainRowValue(List<String> lines) {
        return lastRowValue(lines, TERRAIN_KEY);
    }

    /** Raw value of the LAST uncommented {@code match.terrainLevel=} row
     * (null = absent). */
    public static String terrainLevelRowValue(List<String> lines) {
        return lastRowValue(lines, TERRAIN_LEVEL_KEY);
    }

    private static String lastRowValue(List<String> lines, String key) {
        String value = null;
        for (String line : lines) {
            if (line.startsWith(key + "=")) {
                value = line.substring(key.length() + 1);
            }
        }
        return value;
    }

    /**
     * The file's lines with the terrain stanza upserted —
     * {@link #withEndlessRow}'s surgery on TWO rows under ONE marker. {@code word == null} = "this
     * shell has no terrain control" and returns the SAME list instance (a web-shell Start never
     * strips the file's terrain); {@code none} removes both rows + every marker directly above
     * one (back to canonical bytes, absent = none); any other word replaces the stanza at the
     * first row's position (else appends it) as marker + {@code match.terrain=<word>} +
     * {@code match.terrainLevel=<level>}. Callers pass words already through
     * {@link #parseTerrainWord} / {@link #parseTerrainLevel}; a null level writes the table
     * default {@code 100}.
     */
    public static List<String> withTerrainRows(List<String> lines, String word, String level) {
        if (word == null) {
            return lines;
        }
        boolean keep = !"none".equals(word);
        String lvl = level == null ? parseTerrainLevel(null) : level;
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (line.startsWith(TERRAIN_KEY + "=") || line.startsWith(TERRAIN_LEVEL_KEY + "=")) {
                if (!out.isEmpty() && isMarker(out.get(out.size() - 1), TERRAIN_COMMENT,
                        TERRAIN_COMMENT + " - ")) {
                    out.remove(out.size() - 1);
                }
                if (keep && !placed) {
                    out.add(TERRAIN_COMMENT);
                    out.add(TERRAIN_KEY + "=" + word);
                    out.add(TERRAIN_LEVEL_KEY + "=" + lvl);
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (keep && !placed) {
            out.add(TERRAIN_COMMENT);
            out.add(TERRAIN_KEY + "=" + word);
            out.add(TERRAIN_LEVEL_KEY + "=" + lvl);
        }
        return out;
    }

    /**
     * The strict terrain-word gate both channels share — null/blank =
     * {@code none} (the knob's default), else trimmed + ASCII-folded and REQUIRED to be a
     * {@link WireKnob#TERRAIN} choice; anything else THROWS (reject-never-clamp, the
     * {@link #parseEndlessWord} law). Returns the folded word.
     */
    public static String parseTerrainWord(String text) {
        return parseKnobWord(WireKnob.TERRAIN, text, "terrain");
    }

    /** The strict level gate — null/blank = {@code 100} (the
     * {@link WireKnob#TERRAIN_LEVEL} default: a picked terrain alone is the full vanilla effect),
     * else REQUIRED to be a level-table word; anything else throws. */
    public static String parseTerrainLevel(String text) {
        return parseKnobWord(WireKnob.TERRAIN_LEVEL, text, "terrain level");
    }

    private static String parseKnobWord(WireKnob knob, String text, String what) {
        String word = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (word.isEmpty()) {
            return knob.choiceName(knob.defaultChoice());
        }
        if (knob.choiceIndex(word) < 0) {
            throw new IllegalArgumentException(what + " must be one of "
                    + String.join(", ", knob.choices()) + " (got '" + text + "')");
        }
        return word;
    }

    /**
     * The exact operator-file line for the LIVE {@code terrain} verb — ALWAYS
     * three tokens ({@code "terrain corona 50"}, {@code "terrain none 100"}), the grammar
     * {@code OperatorCommandFile.Verb.SET_TERRAIN} parses (a bare two-token line would leave the
     * level to the serve; the GUI always states both). The serve owns every semantic.
     */
    public static String terrainLine(String wordText, String levelText) {
        return "terrain " + parseTerrainWord(wordText) + " " + parseTerrainLevel(levelText);
    }

    /** Terrain prefill — the row's word when it is a lawful table word, else
     * {@code none} (a prefill never throws in a constructor; a bad row bites at the serve). */
    public static String terrainFieldPrefill(List<String> lines) {
        return knobPrefill(WireKnob.TERRAIN, terrainRowValue(lines));
    }

    /** Level prefill — the row's lawful level, else {@code 100}. */
    public static String terrainLevelFieldPrefill(List<String> lines) {
        return knobPrefill(WireKnob.TERRAIN_LEVEL, terrainLevelRowValue(lines));
    }

    private static String knobPrefill(WireKnob knob, String row) {
        String word = row == null ? "" : row.trim().toLowerCase(Locale.ROOT);
        return knob.choiceIndex(word) >= 0 ? word : knob.choiceName(knob.defaultChoice());
    }

    // ------------------------------------------ asteroid field
    //

    /**
     * The ONE asteroid-field source row {@code match.asteroids}
     * (the {@link WireKnob#ASTEROIDS} word: none / 20 / 100 / 400). The serve reads it at its
     * FIRST admission ({@code WireKnobState} via {@code PostureRegistry}'s file-key column;
     * absent = {@code none}); the generic {@code knob asteroids <word>} operator verb flips a
     * RUNNING serve live. The words are the WireKnob choice table and nothing else, so a table
     * edit moves the GUI with it. The endless two-channel idiom (Start writes the row, 'Set
     * LIVE' appends the verb).
     *
     */
    public static final String ASTEROIDS_KEY = "match.asteroids";
    /** Marker the shells keep directly above the row so a removal ({@code none}) is clean — the
     * {@link #TERRAIN_COMMENT} idiom. */
    public static final String ASTEROIDS_COMMENT =
            "# asteroid field - none / 20 / 100 / 400 (ServerOpsGui set-before-start; the serve"
                    + " reads cc.match.asteroids, absent = none)";

    /** Raw value of the LAST uncommented {@code match.asteroids=} row (null =
     * absent). */
    public static String asteroidsRowValue(List<String> lines) {
        return lastRowValue(lines, ASTEROIDS_KEY);
    }

    /**
     * The file's lines with the {@code match.asteroids} row upserted —
     * {@link #withEndlessRow}'s surgery. {@code word == null} = "this shell has no asteroids
     * control" and returns the SAME list instance (an absent control leaves the row alone);
     * {@code none} removes every row + the marker directly above each (absent = none); any other
     * word replaces the first row in place (else appends marker + row). Callers pass words
     * already through {@link #parseAsteroidsWord}.
     */
    public static List<String> withAsteroidsRow(List<String> lines, String word) {
        if (word == null) {
            return lines;
        }
        boolean keep = !"none".equals(word);
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (line.startsWith(ASTEROIDS_KEY + "=")) {
                if (!out.isEmpty() && isMarker(out.get(out.size() - 1), ASTEROIDS_COMMENT,
                        ASTEROIDS_COMMENT + " - ")) {
                    out.remove(out.size() - 1);
                }
                if (keep && !placed) {
                    out.add(ASTEROIDS_COMMENT);
                    out.add(ASTEROIDS_KEY + "=" + word);
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (keep && !placed) {
            out.add(ASTEROIDS_COMMENT);
            out.add(ASTEROIDS_KEY + "=" + word);
        }
        return out;
    }

    /** The strict asteroids-word gate both channels share — null/blank =
     * {@code none}, else REQUIRED to be a {@link WireKnob#ASTEROIDS} choice; anything else
     * THROWS before any write (reject-never-clamp). Returns the folded word. */
    public static String parseAsteroidsWord(String text) {
        return parseKnobWord(WireKnob.ASTEROIDS, text, "asteroids");
    }

    /** The exact operator-file line for the LIVE flip — {@code "knob
     * asteroids <word>"}, the generic {@code OperatorCommandFile.Verb.SET_KNOB} grammar (never
     * appended to the three-token terrain line). The serve owns every semantic. */
    public static String asteroidsLine(String text) {
        return "knob asteroids " + parseAsteroidsWord(text);
    }

    /** Asteroids prefill — the row's lawful word, else {@code none} (a prefill
     * never throws in a constructor; a bad row bites at the serve). */
    public static String asteroidsFieldPrefill(List<String> lines) {
        return knobPrefill(WireKnob.ASTEROIDS, asteroidsRowValue(lines));
    }

    // ------------------------------------------------ serve mod set
    //

    /**
     * The ONE {@code mods.enabled} source row — the comma-separated mod
     * ids the serve loads BEYOND {@code cc_client} (the serve launcher
     * resolves each id to a folder under {@code mods.roots}, FAIL-CLOSED; this GUI gates only the
     * TEXT law, never folder existence). Absent/blank = a vanilla-only serve (today's default).
     * START-ONLY: a running serve can never change its mod set, so there is NO live-verb twin
     * (unlike the joiner-default / wave-category / endless two-channel rows) — the field rides the
     * set-before-start group, disabled while a serve runs.
     */
    public static final String MODS_ENABLED_KEY = "mods.enabled";
    /** Marker the shells keep directly above their row so a removal (blank = vanilla only) is
     * clean — the {@link #JOINER_DEFAULT_COMMENT} idiom. */
    public static final String MODS_ENABLED_COMMENT =
            "# extra mod ids the serve loads beyond cc_client (ServerOpsGui set-before-start;"
                    + " comma-separated; blank/absent = vanilla only)";
    /** The ModSet id law ({@code ModSet.lawfulId}, the kit's
     * {@code :vguard} law) as a matcher — a leading alnum then up to {@link ModSet#MAX_ID_CHARS}-1
     * more of {@code [0-9A-Za-z._:-]}; single-sources the charset + length cap off {@link ModSet}
     * so a token this field accepts is one {@code ModSet.Record.of} would too. */
    private static final Pattern MOD_ID =
            Pattern.compile("[0-9A-Za-z][0-9A-Za-z._:-]{0," + (ModSet.MAX_ID_CHARS - 1) + "}");

    //
    /** Raw value of the LAST uncommented {@code mods.enabled=} row
     * ({@code null} when absent) — the {@link #joinerDefaultRowValue} grammar (line-start exact,
     * last-wins, commented mentions never match). */
    public static String modsEnabledRowValue(List<String> lines) {
        String value = null;
        for (String line : lines) {
            if (line.startsWith(MODS_ENABLED_KEY + "=")) {
                value = line.substring(MODS_ENABLED_KEY.length() + 1);
            }
        }
        return value;
    }

    //
    /**
     * The strict serve-mod-set parse for the shell — split on {@code ,}
     * or {@code ;}, trim each token, drop empties; every surviving id must pass the ModSet id law
     * ({@link #MOD_ID}), {@code cc_client} ({@link ModSet#EXCLUDED_ID}) is REFUSED (always enabled,
     * pinned by the kit) and a duplicate id is REFUSED; the survivors are returned comma-joined
     * (the row's canonical value, no spaces). Blank/null text -> {@code ""} (the row removed = a
     * vanilla-only serve). Folder existence under {@code mods.roots} is the launcher's fail-closed
     * call, not gated here. Reject-never-clamp: a violation THROWS
     * {@link IllegalArgumentException} (the fortressDp reject idiom), so a value this shell accepts
     * is never one the launcher chokes on for SHAPE.
     */
    public static String parseModsEnabled(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) {
            return "";
        }
        List<String> ids = new ArrayList<>();
        for (String token : raw.split("[,;]")) {
            String id = token.trim();
            if (id.isEmpty()) {
                continue;
            }
            if (!MOD_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("mod id '" + id + "' is unlawful (law"
                        + " [0-9A-Za-z][0-9A-Za-z._:-]{0," + (ModSet.MAX_ID_CHARS - 1) + "});"
                        + " comma-separated ids the serve loads beyond " + ModSet.EXCLUDED_ID);
            }
            if (ModSet.EXCLUDED_ID.equals(id)) {
                throw new IllegalArgumentException(ModSet.EXCLUDED_ID + " is always enabled"
                        + " (pinned by the kit); list only the mods BEYOND it");
            }
            if (ids.contains(id)) {
                throw new IllegalArgumentException("duplicate mod id '" + id + "' in the mods"
                        + " field");
            }
            ids.add(id);
        }
        return String.join(",", ids);
    }

    //
    /**
     * The file's lines with the {@code mods.enabled} row upserted — the
     * {@link #withWaveCategoryRow} surgery verbatim on the CANONICAL value from
     * {@link #parseModsEnabled}: blank (= vanilla only, the default) REMOVES every uncommented row
     * (+ the shells' marker directly above each) so the tracked file returns to its canonical
     * bytes; a value replaces the first row in place (dropping Properties-shadowed duplicates) or
     * appends marker + row. {@code null} = "this shell has no mods control" and returns the lines
     * untouched — a shell without the field (a web Start) must never strip the file's live set.
     */
    public static List<String> withModsEnabledRow(List<String> lines, String value) {
        if (value == null) {
            return lines;
        }
        boolean keep = !value.isEmpty();
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (line.startsWith(MODS_ENABLED_KEY + "=")) {
                if (!out.isEmpty() && isMarker(out.get(out.size() - 1), MODS_ENABLED_COMMENT,
                        MODS_ENABLED_COMMENT + " - ")) {
                    out.remove(out.size() - 1);
                }
                if (keep && !placed) {
                    out.add(MODS_ENABLED_COMMENT);
                    out.add(MODS_ENABLED_KEY + "=" + value);
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (keep && !placed) {
            out.add(MODS_ENABLED_COMMENT);
            out.add(MODS_ENABLED_KEY + "=" + value);
        }
        return out;
    }

    //
    /** Mods-enabled field prefill for the shell (the
     * {@link #joinerDefaultFieldPrefill} idiom): the explicit row when present (trimmed, its inner
     * spacing kept), else {@code ""} (blank field = a vanilla-only serve). */
    public static String modsEnabledFieldPrefill(List<String> lines) {
        String row = modsEnabledRowValue(lines);
        return row == null ? "" : row.trim();
    }

    //
    /**
     * The CLIENT-OPTIONAL mod-set row — mod ids a
     * joiner MAY run (presentation-only: the serve mirrors them for the join page but never loads
     * or folds them; {@code PilotIntentCombatServerMain} reads the row, {@code ModSet} refuses an
     * id that is both). Written by the console's Mods tab ({@link OpsConsole}) beside
     * {@link #MODS_ENABLED_KEY}; START-ONLY like it. A mod is required OR optional, never both
     * ({@link #modsBothRefusalOrNull}).
     */
    public static final String MODS_OPTIONAL_KEY = "mods.optional";
    /** Marker above the optional row (the {@link #MODS_ENABLED_COMMENT} idiom). */
    public static final String MODS_OPTIONAL_COMMENT =
            "# client-optional mod ids joiners MAY run (ServerOpsGui mods panel; comma-separated;"
                    + " blank/absent = none; never also in mods.enabled)";

    //
    /** Raw value of the LAST uncommented {@code mods.optional=} row ({@code null} when absent) —
     * the {@link #modsEnabledRowValue} grammar. */
    public static String modsOptionalRowValue(List<String> lines) {
        String value = null;
        for (String line : lines) {
            if (line.startsWith(MODS_OPTIONAL_KEY + "=")) {
                value = line.substring(MODS_OPTIONAL_KEY.length() + 1);
            }
        }
        return value;
    }

    //
    /** The strict optional-set parse: the {@link #parseModsEnabled} id law verbatim (split
     * {@code ,}/{@code ;}, ModSet id law, {@code cc_client} and duplicates REFUSED by throw),
     * canonical comma-joined; blank/null -> {@code ""} (row removed = no optional mods). */
    public static String parseModsOptional(String text) {
        try {
            return parseModsEnabled(text);
        } catch (IllegalArgumentException bad) {
            // same law, the refusal names the optional set so the host knows which list it hit
            throw new IllegalArgumentException("optional mods: " + bad.getMessage(), bad);
        }
    }

    //
    /** The lines with the {@code mods.optional} row upserted — {@link #withModsEnabledRow}'s
     * surgery verbatim (null = leave the lines alone, blank REMOVES row + marker, a value replaces
     * the first row in place dropping shadowed duplicates, else appends marker + row). */
    public static List<String> withModsOptionalRow(List<String> lines, String value) {
        if (value == null) {
            return lines;
        }
        boolean keep = !value.isEmpty();
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (line.startsWith(MODS_OPTIONAL_KEY + "=")) {
                if (!out.isEmpty() && isMarker(out.get(out.size() - 1), MODS_OPTIONAL_COMMENT,
                        MODS_OPTIONAL_COMMENT + " - ")) {
                    out.remove(out.size() - 1);
                }
                if (keep && !placed) {
                    out.add(MODS_OPTIONAL_COMMENT);
                    out.add(MODS_OPTIONAL_KEY + "=" + value);
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (keep && !placed) {
            out.add(MODS_OPTIONAL_COMMENT);
            out.add(MODS_OPTIONAL_KEY + "=" + value);
        }
        return out;
    }

    //
    /** The optional-row prefill: the explicit row trimmed, else {@code ""}. */
    public static String modsOptionalFieldPrefill(List<String> lines) {
        String row = modsOptionalRowValue(lines);
        return row == null ? "" : row.trim();
    }

    //
    /**
     * The "a mod is required or optional, never both" law as a pre-write refusal (the serve's
     * own refusal: {@code RealEngineModSetFacts} optional-also-enabled, {@code ModSet}; the arm's:
     * kit staging). Inputs are the raw or canonical lists (split on
     * {@code ,}/{@code ;}, trimmed); null = no list. Returns the refusal text naming the first id
     * in both, else {@code null}.
     */
    public static String modsBothRefusalOrNull(String enabledCsv, String optionalCsv) {
        if (enabledCsv == null || optionalCsv == null) {
            return null;
        }
        List<String> enabled = new ArrayList<>();
        for (String t : enabledCsv.split("[,;]")) {
            if (!t.trim().isEmpty()) {
                enabled.add(t.trim());
            }
        }
        for (String t : optionalCsv.split("[,;]")) {
            String id = t.trim();
            if (!id.isEmpty() && enabled.contains(id)) {
                return "mod '" + id + "' is both required (mods.enabled) and optional"
                        + " (mods.optional) - a mod is required or optional, never both;"
                        + " pick one for it.";
            }
        }
        return null;
    }
}
