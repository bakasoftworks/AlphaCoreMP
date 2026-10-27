package cc.starsector.client.join;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;

/**
 * Pure logic behind the joiner connection GUI — everything here is headless-testable
 * ({@link JoinGui} {@code --selftest}) and Swing-free.
 *
 * <p>Contract notes (recon 2026-07-06):
 * <ul>
 * <li>The mod consumes {@code CC_MATCH_DESCRIPTOR} as a FILE PATH ({@code MatchMissionBuilder}
 * FileReader), and requires the FULL descriptor to decode fail-closed — so a pasted credential is
 * validated with the game's own bundled {@link MatchDescriptorCodec} and written to
 * {@code match.txt}; the bat's autodetect then selects UDP exactly as before.</li>
 * <li>Pastes arrive CRLF-mangled (chat apps / Notepad); {@link #parseDescriptor} normalizes line
 * endings and trailing blanks BEFORE the fail-closed decode, and {@link #canonicalText} re-encodes
 * so the written file is byte-canonical (a paste of the standing descriptor's content reproduces
 * the kit-baked file exactly — no byte drift vs the server row).</li>
 * <li>The plan file is CRLF k=v (cmd {@code for /f} consumes it, same idiom as the bat's
 * kit-manifest parse).</li>
 * </ul>
 */
final class JoinLaunchPlan {

    /** Wire cap for a self-picked pilot name (join-cc.bat prompt rule). */
    static final int NAME_MAX = 24;

    /**
     * quote-strip + trim a ship pick; the combo's "(server default)" entry and a
     * blank both mean "no pick" (an EMPTY {@code ship=} row ⇒ no {@code -Dcc.predict.variantId}
     * append ⇒ the descriptor's own spawn, byte-identical to the pre-L3 JTO).
     */
    static String sanitizeShip(String raw) {
        String ship = raw == null ? "" : raw.replace("\"", "").trim();
        // ANY "(server default…" combo label — the plain
        // SERVER_DEFAULT_LABEL or the "(server default: <id>)" — persists as an EMPTY
        // pick, so the next window open re-fetches the server's current default. No real ship id
        // can start with '(' (offerable() requires an alnum first char), so no false positive.
        return ship.startsWith("(server default") ? "" : ship;
    }

    /**
     * The ship id the LAUNCH plan carries ({@code ship=} row → {@code -Dcc.predict
     * .variantId} via join-cc.bat). The SETTINGS file always persists the sanitized pick
     * ({@code ""} for the server-default entry, so the next open re-fetches), but the plan must
     * carry a CONCRETE id so the joiner spawns as the host-picked default from the first fly-in:
     * an empty pick resolves to the fetched default when the probe served one, else stays empty
     * (byte-identical to the plan before the fetched default existed); a real pick always wins. {@code fetchedDefault}
     * is null when no default was served (or the probe never ran).
     */
    //
    static String resolveLaunchShip(String sanitizedPick, String fetchedDefault) {
        if (sanitizedPick != null && !sanitizedPick.isEmpty()) {
            return sanitizedPick;
        }
        return fetchedDefault != null ? fetchedDefault : "";
    }

    /**
     * Returns a user-facing error for a (sanitized) ship pick, or null when launchable. Empty
     * is fine (server default). A non-empty pick must be {@link JoinProbe#offerable} — the
     * bat's {@code :vguard} charset the value rides bare into JAVA_TOOL_OPTIONS under, plus the
     * HELLO cap — so a typo bounces here, not as a silent INPUT REJECTED in the bat. Pickability
     * itself is the SERVER's call at admission (an unknown id spawns the default + a chat notice).
     */
    static String validateShip(String ship) {
        if (ship == null || ship.isEmpty() || JoinProbe.offerable(ship)) {
            return null;
        }
        return "A ship id is letters, digits, dot, underscore, colon and dash only (up to 64,"
                + " starting with a letter or digit) - pick one from the list, or leave it on"
                + " " + JoinProbe.SERVER_DEFAULT_LABEL + ".";
    }

    private JoinLaunchPlan() {
    }

    /** The bat's {@code :vguard} length cap (a value past 256 characters is refused there; a DNS name tops out at 253). */
    static final int HOST_MAX = 256;

    /** Returns a user-facing error for bad host/port, or null when launchable. */
    static String validate(String host, String port) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) {
            return "Enter the server address the host read to you.";
        }
        // Whitespace OR any control character: this gate runs BEFORE the settings
        // save and the mods stage (JoinLaunchGates.check), so the refusal lands with nothing written;
        // planRow's throw alone fired only after both.
        if (h.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            return "The server address must not contain spaces or other invisible characters.";
        }
        // The bat's own :vguard law for CCHOST, asked HERE — the check above
        // let a zero-width space, a no-break space, "/", "[" or a leading ":" through, so the settings save (the
        // remembered password bound to that host), the mods stage and the plan write all ran before the bat
        // refused the launch, and the next window pre-filled a host that can never launch.
        if (h.length() > HOST_MAX || !JoinProbe.vguardShape(h)) {
            return "The server address is letters, digits, dot, underscore, colon and dash only, starting with a"
                    + " letter or digit - check it against what the host read to you.";
        }
        String p = port == null ? "" : port.trim();
        if (p.isEmpty()) {
            return "Enter the server port the host read to you.";
        }
        // parseInt admits "+7777" and non-ASCII digits (fullwidth, Arabic-Indic) that the bat's
        // :vguard refuses after every file write — ASCII digits only, the same before-any-write posture as the host.
        int value;
        try {
            if (p.chars().anyMatch(c -> c < '0' || c > '9')) {
                throw new NumberFormatException(p);
            }
            value = Integer.parseInt(p);
        } catch (NumberFormatException e) {
            return "The port must be a number (1-65535).";
        }
        if (value < 1 || value > 65535) {
            return "The port must be between 1 and 65535.";
        }
        return null;
    }

    /**
     * Quote-strip + control-strip + trim + cap at {@link #NAME_MAX} — mirrors the old bat prompt's
     * handling. The control strip is JAVACRED-1 (scan row 7): the name is the ONE
     * free-text plan row (the bat deliberately does not charset-gate it), and an interior CR / LF
     * used to ride into the CRLF plan file as extra rows — the bat's {@code for /f} keeps the LAST
     * duplicate, so a name posted straight at the window's API could re-point {@code host=} and
     * send the remembered password + cid around the D4 host binding. Stripped, not refused: a
     * pasted name with a stray line end still launches under the name it visibly reads as.
     */
    static String sanitizeName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '"' && !Character.isISOControl(c)) {
                kept.append(c);
            }
        }
        String name = kept.toString().trim();
        return name.length() <= NAME_MAX ? name : name.substring(0, NAME_MAX);
    }

    /**
     * JAVACRED-1: a plan row value never holds a control character — one CR / LF is one more row to
     * the bat's {@code for /f}. For host, port, name, password and ship this is a belt behind the
     * row's own gate ({@link #validate}, {@link #sanitizeName}, {@link #validatePassword},
     * {@link #validateShip}) — it holds if a future caller or row skips its gate. For the CID row
     * it is the ONLY gate: a settings-file cid is quote-stripped and trimmed, nothing more.
     * The message names the ROW, never the value (it surfaces in the window's "Launch
     * failed" text and the window log).
     */
    private static String planRow(String key, String value) {
        if (planRowRefuses(value)) {
            throw new IllegalArgumentException("the launch plan's " + key
                    + " row holds a control character - nothing was launched");
        }
        return key + "=" + value + "\r\n";
    }

    /** {@link #planRow}'s gate as a predicate: true when {@code value} holds a control character. Package-private
     *  so {@code JoinLaunchGates.commit} re-mints a saved cid this very gate would refuse, instead of keeping one
     *  that refuses every password-mode Launch — ONE charset, never a second copy.
     * */
    static boolean planRowRefuses(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Quote-strip + trim a typed match password — chat-app/Notepad copy often
     * adds either. Also sanitizes the cid plan row (same charset family). */
    static String sanitizePassword(String raw) {
        return raw == null ? "" : raw.replace("\"", "").trim();
    }

    /**
     * Returns a user-facing error for a typed match password, or null when acceptable.
     * Empty is acceptable — the token/classic lanes need none. A non-empty
     * password must match the server's law {@code [A-Za-z0-9][A-Za-z0-9-]{7,63}} (8 to 64
     * characters, no leading dash; bat/JTO-safe by construction), so a
     * typo bounces here instead of as a door reject mid-launch. An early, friendlier refusal
     * only — the door still decides.
     */
    static String validatePassword(String password) {
        if (password == null || password.isEmpty()) {
            return null;
        }
        if (!password.matches("[A-Za-z0-9][A-Za-z0-9-]{7,63}")) {
            return "The match password is a code of letters, digits and dashes (8 to 64"
                    + " characters, not starting with a dash) - type it exactly as the host"
                    + " announced it.";
        }
        return null;
    }

    /**
     * Parse the host GUI's one-click connect info —
     * {@code ip:port:password} pasted whole into the autofill field. Fail-closed: EXACTLY
     * three non-blank {@code ':'}-separated parts (quote-strip + trim first, the usual
     * chat-app/Notepad paste hazards) or {@code null}, and null NEVER touches the fields —
     * a partial or garbled paste autofills nothing. The parts are only PREFILL; the Launch
     * validators ({@link #validate}/{@link #validatePassword}) stay the boundary.
     */
    static String[] parseBrick(String pasted) {
        if (pasted == null) {
            return null;
        }
        String text = pasted.replace("\"", "").trim();
        if (text.isEmpty()) {
            return null;
        }
        String[] parts = text.split(":", -1);
        if (parts.length != 3) {
            return null;
        }
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
            if (parts[i].isEmpty() || parts[i].chars().anyMatch(Character::isWhitespace)) {
                return null;
            }
        }
        return parts;
    }

    /**
     * Mints this install's client instance id: 32-hex from SecureRandom — the
     * password-mode door keys the seat by it. Minted ONCE per install and persisted in
     * {@link JoinSettings}; runs OUTSIDE the game on the bundled JRE, so SecureRandom is safe
     * here (the in-game ephemeral mint deliberately avoids it — loader B may deny the
     * provider-SPI reflective lookups).
     */
    static String mintClientId() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(
                    Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * REDACTED-kit preflight (courtesy, not a boundary — the door bounces a placeholder as
     * {@code seat_token_unknown} regardless): true when EVERY pilot-seat token in the descriptor
     * this launch will use is a redaction placeholder and no match password was typed
     * (the ONE credential this window collects; the personal seat-token paste
     * lane died, its scripted escape hatch lives outside this window: the bat's
     * {@code CC_JOIN_TOKEN} env into the kit handoff's {@code token=} row, else the game's
     * {@code CC_PREDICT_SEAT_TOKEN} env, read in {@code PredictDialPlanner}).
     * Seats are AUTO-assigned server-side, so the launch is no longer pinned to one row — ANY
     * real token means the token lane can admit
     * (the keep-tokens watch kit), and only an all-redacted, password-less launch is doomed.
     */
    static boolean needsCredentialEntry(MatchDescriptor descriptor, String password) {
        if (password != null && !password.isEmpty()) {
            return false;
        }
        boolean sawSeat = false;
        for (MatchDescriptor.Seat seat : descriptor.seats()) {
            if (!MatchDescriptor.isRedactedToken(seat.seatToken())) {
                return false;
            }
            sawSeat = true;
        }
        return sawSeat;
    }

    /**
     * Fail-closed parse of pasted descriptor text with the game's own codec, after paste-transport
     * normalization (BOM, CRLF, trailing blank lines). Throws with the codec's reason on any
     * structural defect — the GUI surfaces it verbatim.
     */
    static MatchDescriptor parseDescriptor(String pastedText) {
        if (pastedText == null || pastedText.isBlank()) {
            throw new IllegalArgumentException("the pasted credential is empty");
        }
        String text = pastedText;
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return MatchDescriptorCodec.decode(lines);
    }

    /** A pilot seat must exist NOW, not as an in-game mint throw mid-session.
     * Seats are AUTO-assigned server-side ({@code cc.predict.shipId=0} in the bat), so ANY
     * pilot-seat row proves a joinable credential — the fixed seat-2 gate died with the pin.
     * (Belt: the codec requires seat rows, so a decoded descriptor normally has one.) */
    static MatchDescriptor.Seat requirePilotSeat(MatchDescriptor descriptor) {
        for (MatchDescriptor.Seat seat : descriptor.seats()) {
            return seat;
        }
        throw new IllegalArgumentException(
                "this credential has no pilot seats"
                        + " - ask the host for the right match credential");
    }

    /** Canonical descriptor text (codec encode: LF lines, canonical key order). */
    static String canonicalText(MatchDescriptor descriptor) {
        return MatchDescriptorCodec.encode(descriptor);
    }

    /** The bytes the one writer puts in {@code match.txt}: UTF-8, the ONE codec both trees share — the server's
     *  preflight serves {@code encode(redact(d)).getBytes(UTF_8)} ({@code MatchDocPreflight}), the kit's strict
     *  receive decodes UTF-8, the packer writes the kit's own copy UTF-8 and the game reads it UTF-8
     *  ({@code MatchMissionBuilder}). US-ASCII here wrote a non-ASCII side name / tagline (lawful: the text law admits
     *  non-ASCII printables) as {@code ?} AFTER the window had checked the served text. */
    static byte[] canonicalBytes(MatchDescriptor descriptor) {
        return canonicalText(descriptor).getBytes(StandardCharsets.UTF_8);
    }

    /** CRLF k=v plan file the bat parses back (host=, port=, name=, password=, cid=, logs= —
     * all but host/port may be empty; non-empty password / cid rows become rows of the bat's
     * read-once kit handoff file, and only that file's id rides its JTO as
     * {@code -Dcc.kit.handoff=}). The {@code token=} row died with the
     * GUI handout lane — the bat pre-clears CCTOKEN, so a rowless plan reads as
     * empty there and the JTO stays byte-identical; the bat's CC_JOIN_TOKEN env lane is the
     * surviving scripted token entry. The cid row rides ONLY with a password —
     * a blank-password launch keeps the pre-password JTO byte-identical. The logs row
     * is {@code on}/{@code off}; on = the shipped default, and the bat treats
     * an absent row (old plan) as on, so only {@code off} changes any launch byte. The ship row
     * is the initial-spawn variant pick: non-empty becomes
     * {@code -Dcc.predict.variantId=} in the JTO; empty/absent appends nothing. */
    static String planFileContent(String host, String port, String name, String password,
            String cid, boolean logs, String ship) {
        return planFileContent(host, port, name, password, cid, logs, ship, TEAM_SERVER_PICKS);
    }

    /** The team the window's Team choice names when nothing is picked: the server picks (the wire's 0). */
    static final int TEAM_SERVER_PICKS = 0;
    /** The highest team the window can name: 1 = South (the first side), 2 = North (the second side). */
    static final int TEAM_MAX = 2;

    /**
     * The plan with the window's Team choice. {@code team} 0 = the server picks = NO {@code team=} row (the plan is
     * byte-identical to the pre-team shape, and the bat appends no team option); 1 / 2 = a {@code team=1} /
     * {@code team=2} row just before {@code ship=} (ship stays the last row), which the bat turns into the game's
     * team option for that ONE launch only. Anything outside 0..2 refuses the write with the row's name (the window's
     * launch gate refuses it first; this is the belt).
     *
     */
    static String planFileContent(String host, String port, String name, String password,
            String cid, boolean logs, String ship, int team) {
        if (team < TEAM_SERVER_PICKS || team > TEAM_MAX) {
            throw new IllegalArgumentException("plan row team refused (not 0..2)");
        }
        // The editor=1 row is RETIRED: the join window keeps the Open-editor intent itself — it no longer
        // exits on Launch.
        String pw = sanitizePassword(password);
        return planRow("host", host.trim())
                + planRow("port", port.trim())
                + planRow("name", sanitizeName(name))
                + planRow("password", pw)
                + planRow("cid", pw.isEmpty() ? "" : sanitizePassword(cid))
                + planRow("logs", logs ? "on" : "off")
                + (team == TEAM_SERVER_PICKS ? "" : planRow("team", Integer.toString(team)))
                + planRow("ship", sanitizeShip(ship));
    }
}
