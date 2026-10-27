package cc.starsector.server.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import cc.starsector.server.runtime.BanSet;
import cc.starsector.server.runtime.CidDigest;
import cc.starsector.server.runtime.OperatorCommandFile;
import cc.starsector.server.runtime.VoiceConfig;

/**
 * The pure rows over {@link OpsConsoleForms} - no server,
 * no file, no process. WHY: the console's HTTP shell is a thin caller of that layer, so
 * the Swing-parity laws it owes (the exact minted lines, the occupant binding, the confirm texts,
 * the Start tri-state) are graded here once, against two composed ops snapshots (the REAL
 * {@link OpsStatusFile#composeLines} writer read back by {@link OpsSnapshot#parse}).
 *
 * <p>Each row prints {@code PASS <row>} or {@code FAIL <row>: <why> (flips if ...)}; {@link #run}
 * returns the FAIL lines; {@code main} exits 1 on any, else prints
 * {@code ops-console-forms selftest PASS}.
 */
public final class OpsConsoleFormsSelftest {

    private OpsConsoleFormsSelftest() {
    }

    /** A row's own failure (its {@code why} names the regression that flips it). */
    private static final class RowFail extends RuntimeException {
        RowFail(String why) {
            super(why, null, false, false);
        }
    }

    private static final String K2 = BanSet.cidKey("c2");
    private static final String K3 = BanSet.cidKey("c3");
    private static final String K9 = BanSet.cidKey("c9");
    /** The seat keys above stay hex (the expected-occupant compare); a player
     *  BAN key is the cid's digest, the key a bans row carries and an unban form posts (6a6f65 = the hex of "joe"). */
    private static final String JOE = CidDigest.of("joe");

    /** Seat 2 = c2 flying custom cc-v2-brawl from loopback; seat 3 = c3 on a stock hull. */
    private static final OpsSnapshot SNAP_A = snapshot("c2");
    /** The same table after seat 2 changed hands to c9. */
    private static final OpsSnapshot SNAP_B = snapshot("c9");

    /** Runs every row (one PASS / FAIL line each) and returns the FAIL lines (empty = green). */
    static List<String> run() {
        List<String> fails = new ArrayList<>();
        row(fails, "forms-decode", OpsConsoleFormsSelftest::decode);
        row(fails, "forms-verb-grammar", OpsConsoleFormsSelftest::verbGrammar);
        row(fails, "forms-verb-occupant", OpsConsoleFormsSelftest::verbOccupant);
        row(fails, "forms-verb-confirm", OpsConsoleFormsSelftest::verbConfirm);
        row(fails, "forms-custom-reject", OpsConsoleFormsSelftest::customReject);
        row(fails, "forms-custom-typed-id", OpsConsoleFormsSelftest::customTypedId);
        row(fails, "forms-ship-hide-show", OpsConsoleFormsSelftest::shipHideShow);
        row(fails, "forms-start-tristate", OpsConsoleFormsSelftest::startTristate);
        row(fails, "forms-voice", OpsConsoleFormsSelftest::voice);
        row(fails, "forms-mods-path", OpsConsoleFormsSelftest::modsPath);
        return fails;
    }

    private static void row(List<String> fails, String name, Runnable check) {
        String why = null;
        try {
            check.run();
        } catch (RowFail f) {
            why = f.getMessage();
        } catch (RuntimeException e) {
            why = "threw " + e.getClass().getSimpleName() + ": "
                    + PeerText.bound(String.valueOf(e.getMessage()), 160);
        }
        if (why == null) {
            System.out.println("PASS " + name);
        } else {
            String line = "FAIL " + name + ": " + why;
            System.out.println(line);
            fails.add(line);
        }
    }

    // ---- rows ----------------------------------------------------------------------------------

    static void decode() {
        Map<String, List<String>> f = OpsConsoleForms.decodeForm(
                "verb=end-round&confirmed=a&confirmed=b%3Ac&key=6a%0Aend-match&x=a+b&&flag");
        need(List.of("a", "b:c").equals(f.get("confirmed")),
                "repeated confirmed= collect in order, decoded: " + f.get("confirmed")
                        + " (flips if a repeated key overwrites or reorders)");
        need("6a\nend-match".equals(OpsConsoleForms.first(f, "key"))
                        && "a b".equals(OpsConsoleForms.first(f, "x"))
                        && "".equals(OpsConsoleForms.first(f, "flag")),
                "%0A decodes INTO the value, + is a space, a bare key is \"\" (flips if the decoder"
                        + " drifts from URLDecoder)");
        need(OpsConsoleForms.first(f, "absent") == null && !OpsConsoleForms.has(f, "absent")
                        && OpsConsoleForms.has(f, "flag"),
                "first/has: absent = null/false (flips if absent reads as \"\")");
        need(OpsConsoleForms.CONTROL_CHAR.equals(refusal(() -> OpsConsoleForms.verbLine(f, SNAP_A))),
                "the decoded LF is refused by verbLine even on end-round, which reads no key (flips if"
                        + " the control-char law runs after the verb or only on read keys)");
        need(OpsConsoleForms.MALFORMED_BODY.equals(refusal(() -> OpsConsoleForms.decodeForm("a=%zz")))
                        && OpsConsoleForms.MALFORMED_BODY.equals(
                                refusal(() -> OpsConsoleForms.decodeForm("verb=kick&seat=%"))),
                "a malformed escape = '" + OpsConsoleForms.MALFORMED_BODY + "' (flips if the"
                        + " URLDecoder text leaks or the bad pair is skipped)");
        need(OpsConsoleForms.decodeForm(null).isEmpty() && OpsConsoleForms.decodeForm("").isEmpty(),
                "a null / empty body = an empty form");
    }

    static void verbGrammar() {
        String[][] ok = {
                {"verb=end-round", "end-round"},
                {"verb=end-match", "end-match"},
                {"verb=set-sim-speed&value=1.5", "set-sim-speed 1.5"},
                {"verb=sim-speed-auto&auto=on", "sim-speed-auto on"},
                {"verb=sim-speed-auto&auto=off", "sim-speed-auto off"},
                {"verb=custom-submissions&auto=off", "custom-submissions off"},
                {"verb=kick&seat=2", "kick 2 " + K2},
                {"verb=ban&seat=2", "ban 2 " + K2},
                {"verb=ban-src&seat=2", "ban-src 2 " + K2},
                {"verb=custom-allow&seat=3", "custom-allow 3"},
                {"verb=custom-ban&seat=2", "custom-ban cc-v2-brawl"},
                {"verb=custom-export&seat=2", "custom-export cc-v2-brawl"},
                {"verb=custom-unban&id=cc-v2-brawl", "custom-unban cc-v2-brawl"},
                {"verb=unban&kind=cid&key=6A6F65", "unban cid " + JOE}, // an older hex key forwards its digest
                {"verb=unban&kind=cid&key=" + JOE, "unban cid " + JOE},
                {"verb=ban-ip&key=1.2.3.0%2F24", "ban-ip 1.2.3.0/24"},
                {"verb=ban-ip&key=1.2.3.4", "ban-ip 1.2.3.4"},
                {"verb=ban-ip&key=+2A0B%3AF4C0%3A%3A%2F32+", "ban-ip 2a:b:f4:c0::/32"}, // typed RFC form -> stored key
                {"verb=set-joiner-default&value=aurora_Balanced", "set-joiner-default aurora_Balanced"},
                {"verb=set-joiner-default&value=", "set-joiner-default none"},
                {"verb=wave-category&value=derelict", "wave-category derelict"},
                {"verb=endless&value=off", "endless off"},
                {"verb=terrain&value=corona&level=50", "terrain corona 50"},
                {"verb=knob-asteroids&value=100", "knob asteroids 100"}};
        List<String> minted = new ArrayList<>();
        for (String[] c : ok) {
            String line = OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(c[0]), SNAP_A);
            need(c[1].equals(line), c[0] + " minted '" + line + "', the Swing press mints '" + c[1]
                    + "' (flips if the form-to-minter map drifts)");
            minted.add(line);
        }
        OperatorCommandFile.ParseResult parsed = OperatorCommandFile.parse(minted);
        need(parsed.rejected().isEmpty() && parsed.commands().size() == minted.size(),
                "every minted line parses in the REAL operator-file grammar; rejected "
                        + parsed.rejected() + " (flips if a minted shape is one the serve refuses)");
        // the July refused shapes, re-homed from the retired ServerOpsWebSelftest, + the v2 grammar's own
        String[][] refused = {
                {"verb=kick&seat=0", "Seat 0 is no longer seated."},
                {"verb=custom-allow&seat=abc", "seat must be a seat number (1 to 9 digits)"},
                {"verb=ban&seat=1234567890", "seat must be a seat number (1 to 9 digits)"},
                {"verb=ban", "Select a seat row first."},
                {"verb=kick&seat=9", "Seat 9 is no longer seated."},
                {"verb=custom-ban&seat=3", "Seat 3 is flying a stock hull, not a custom."},
                {"verb=custom-submissions&auto=maybe", "custom-submissions needs auto=on|off, got 'maybe'"},
                {"verb=sim-speed-auto&auto=ON", "sim-speed-auto needs auto=on|off, got 'ON'"},
                {"verb=set-sim-speed&value=fast", "Sim-speed must be a number in ["
                        + OperatorCommandFile.SIM_SPEED_MIN + ", " + OperatorCommandFile.SIM_SPEED_MAX
                        + "], got 'fast'."},
                {"verb=set-sim-speed&value=9", "Sim-speed must be a number in ["
                        + OperatorCommandFile.SIM_SPEED_MIN + ", " + OperatorCommandFile.SIM_SPEED_MAX
                        + "], got '9'."},
                {"verb=custom-nuke", "unknown verb 'custom-nuke'"},
                {"verb=ban-cid&key=6a6f65", "unknown verb 'ban-cid'"},
                {"verb=bans", "unknown verb 'bans'"},
                {"seat=2", "unknown verb ''"},
                {"verb=terrain&value=corona", "missing field: level"},
                {"verb=set-joiner-default", "missing field: value"},
                {"verb=ban-ip", "missing field: key"},
                {"verb=ban-ip&key=1.2.3.4%0Aend-match", OpsConsoleForms.CONTROL_CHAR},
                {"verb=unban&kind=cid&key=6a%0Aend-match", OpsConsoleForms.CONTROL_CHAR},
                {"verb=kick&seat=2&key=6a%00", OpsConsoleForms.CONTROL_CHAR},
                {"verb=end-round%0Aend-match", OpsConsoleForms.CONTROL_CHAR}};
        for (String[] c : refused) {
            String why = refusal(() -> OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(c[0]), SNAP_A));
            need(c[1].equals(why), c[0] + " answered '" + why + "', want '" + c[1]
                    + "' (flips if the refusal text drifts from the Swing / v2 law or the shape mints)");
        }
        String[] minterRefused = {"verb=custom-unban&id=", "verb=custom-unban",
                "verb=unban&kind=src&key=1.2.3.4%20x", "verb=unban&kind=seat&key=1",
                "verb=wave-category&value=nonsense", "verb=terrain&value=lava&level=50",
                "verb=ban-ip&key=1.2.3.4%20x", "verb=ban-ip&key=", "verb=ban-ip&key=1.2.3.4%2F24"};
        for (String form : minterRefused) {
            String why = refusal(() -> OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(form), SNAP_A));
            need(why != null && !why.isEmpty(), form + " minted a line (flips if a blank id / a bad"
                    + " unban kind or key / a bad dial word reaches the command file)");
        }
        String longVerb = "x".repeat(100);
        String why = refusal(() -> OpsConsoleForms.verbLine(
                OpsConsoleForms.decodeForm("verb=" + longVerb), SNAP_A));
        need(why != null && why.startsWith("unknown verb '") && !why.contains("x".repeat(41)),
                "an unknown verb echoes at most " + OpsConsoleForms.ECHO_MAX + " chars; got " + why
                        + " (flips if the echo is unbounded)");
        minterEchoBounded();
    }

    /** A SHARED minter's refusal echoes
     * the typed text bounded, and the bound never changes what the minter judges. */
    private static void minterEchoBounded() {
        String z = "z".repeat(100);
        String[] longForms = {"verb=set-joiner-default&value=" + z, "verb=custom-unban&id=" + z,
                "verb=unban&kind=" + z + "&key=6a6f65", "verb=wave-category&value=" + z,
                "verb=endless&value=" + z, "verb=terrain&value=" + z + "&level=50",
                "verb=terrain&value=corona&level=" + z, "verb=knob-asteroids&value=" + z};
        for (String form : longForms) {
            String why = refusal(() -> OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(form), SNAP_A));
            need(why != null && why.contains("z".repeat(OpsConsoleForms.ECHO_MAX) + "…")
                            && !why.contains("z".repeat(OpsConsoleForms.ECHO_MAX + 1)),
                    PeerText.bound(form, 48) + " answered '" + PeerText.bound(why, 160)
                            + "' (flips if a shared minter's refusal echoes the typed text unbounded)");
        }
        // The ban-ip minter never quotes the typed key at all (the one
        // normalizer's law), so its long form is pinned here rather than in the bounded-echo loop above.
        String ipLong = refusal(() -> OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm("verb=ban-ip&key=" + z),
                SNAP_A));
        need(ipLong != null && ipLong.startsWith("ban-ip needs an address or range: " + BanSet.SRC_KEY_FORM)
                        && !ipLong.contains("zz"),
                "a 100-char ban-ip key answered '" + PeerText.bound(String.valueOf(ipLong), 160)
                        + "' (flips if a long token mints, or the refusal drops the accepted forms or echoes the key)");
        String id64 = "y".repeat(64);
        need(("set-joiner-default " + id64).equals(OpsConsoleForms.verbLine(
                        OpsConsoleForms.decodeForm("verb=set-joiner-default&value=" + id64), SNAP_A)),
                "a valid 64-char joiner id mints verbatim (flips if the echo bound cuts the value BEFORE"
                        + " the minter judges it)");
        String bidi = refusal(() -> OpsConsoleForms.verbLine(
                OpsConsoleForms.decodeForm("verb=set-joiner-default&value=%E2%80%AEabc"), SNAP_A));
        need(bidi != null && bidi.contains("' abc'") && bidi.indexOf((char) 0x202E) < 0,
                "a bidi-led id is REFUSED and its echo folded (flips if the fold runs before the minter,"
                        + " which trims the folded space and mints 'abc', or the raw char rides the echo);"
                        + " got " + (bidi == null ? "a minted line" : PeerText.bound(bidi, 160)));
    }

    static void verbOccupant() {
        String moved = refusal(() -> OpsConsoleForms.verbLine(
                OpsConsoleForms.decodeForm("verb=kick&seat=2&key=" + K2), SNAP_B));
        need("Seat 2 changed hands - nothing sent".equals(moved),
                "kick with the c2 key on a seat now held by c9 answered '" + moved
                        + "' (flips if the occupant compare is dropped)");
        for (String verb : List.of("ban", "ban-src", "custom-allow", "custom-ban", "custom-export")) {
            String w = refusal(() -> OpsConsoleForms.verbLine(
                    OpsConsoleForms.decodeForm("verb=" + verb + "&seat=2&key=" + K9), SNAP_A));
            need("Seat 2 changed hands - nothing sent".equals(w), verb + " with a foreign key answered '"
                    + w + "' (flips if a seat verb skips the occupant compare)");
        }
        String folded = OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(
                "verb=ban&seat=2&key=+" + K2.toUpperCase(Locale.ROOT) + "+"), SNAP_A);
        need(("ban 2 " + K2).equals(folded), "an upper-cased, padded form key matches the row's key and"
                + " the line carries the ROW's folded key; got " + folded
                + " (flips if the compare is case-sensitive or the form's text is minted)");
        String rowKey = OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm("verb=kick&seat=2"), SNAP_B);
        need(("kick 2 " + K9).equals(rowKey), "a key-less kick on the new occupant carries c9's key from"
                + " the ROW; got " + rowKey + " (flips if the line is minted without the snapshot key)");
    }

    static void verbConfirm() {
        for (String form : List.of("verb=end-round", "verb=set-sim-speed&value=1.5",
                "verb=sim-speed-auto&auto=on", "verb=custom-submissions&auto=on",
                "verb=custom-export&seat=2", "verb=custom-unban&id=cc-v2-brawl",
                "verb=unban&kind=cid&key=" + JOE, "verb=set-joiner-default&value=",
                "verb=wave-category&value=remnant", "verb=endless&value=on",
                "verb=terrain&value=none&level=100", "verb=knob-asteroids&value=none")) {
            OpsConsoleForms.ConfirmSpec spec = OpsConsoleForms.verbConfirm(
                    OpsConsoleForms.decodeForm(form), SNAP_A, 0L);
            need(spec == null, form + " asked a confirm " + spec + " (flips if a Swing no-confirm press"
                    + " gains a dialog)");
        }
        expect("verb=end-match", 0L, "end-match", "End match",
                "End the match? (clean goodbye fan, server exits)");
        expect("verb=kill", 4242L, "kill", "Kill",
                "Stop server pid 4242? (writes the stop file, then ends the process)");
        expect("verb=kill", 0L, "kill", "Kill", "Stop the server launcher? (no live server process right"
                + " now - writes the stop file, then ends the launcher if it does not stop)");
        expect("verb=kick&seat=2", 0L, "kick:2:" + K2, "Kick", "Kick seat 2 (c2)?");
        expect("verb=ban&seat=2", 0L, "ban:2:" + K2, "Ban player", ServerOpsCore.banConfirmText(2, "c2"));
        expect("verb=ban-src&seat=2", 0L, "ban-src:2:" + K2, "Ban source",
                ServerOpsCore.banSrcConfirmText(2, "c2", "127.0.0.1:2"));
        // a typed address binds the STORED key, never the typing: the padded RFC form confirms its byte key
        expect("verb=ban-ip&key=1.2.3.0%2F24", 0L, "ban-ip:1.2.3.0/24", "Ban address",
                ServerOpsCore.banIpConfirmText("1.2.3.0/24"));
        expect("verb=ban-ip&key=+2A0B%3AF4C0%3A%3A%2F32", 0L, "ban-ip:2a:b:f4:c0::/32", "Ban address",
                "Ban address 2a:b:f4:c0::/32? Every player connecting from it is kicked now and refused at the door,"
                        + " and cc-server.bans keeps it across server starts.");
        expect("verb=custom-allow&seat=3", 0L, "custom-allow:3:" + K3, "Allow seat",
                "Allow seat 3 (c3) to submit customs again and un-ban its banned customs?");
        expect("verb=custom-ban&seat=2", 0L, "custom-ban:2:" + K2 + ":cc-v2-brawl", "Ban custom",
                "Ban custom id 'cc-v2-brawl' (seat 2)? It becomes unpickable this serve and re-submitting"
                        + " the same body is refused (custom_banned); the author may still submit a"
                        + " different variant.");
        OpsConsoleForms.ConfirmSpec moved = OpsConsoleForms.verbConfirm(
                OpsConsoleForms.decodeForm("verb=kick&seat=2"), SNAP_B, 0L);
        need(moved != null && ("kick:2:" + K9).equals(moved.key()) && "Kick seat 2 (c9)?".equals(moved.message()),
                "the kick key on the new occupant is c9's, never c2's: " + moved
                        + " (flips if the key is not occupant-bound)");
        need("Seat 9 is no longer seated.".equals(refusal(() -> OpsConsoleForms.verbConfirm(
                        OpsConsoleForms.decodeForm("verb=ban&seat=9"), SNAP_A, 0L))),
                "a refused seat throws before any confirm (flips if a confirm is minted for a gone seat)");
        String json = OpsConsoleForms.needsConfirmJson(List.of(
                new OpsConsoleForms.ConfirmSpec("kick:2:" + K2, "Kick", "Kick seat 2 (\"c2\")?")));
        need(("{\"needsConfirm\":[{\"key\":\"kick:2:" + K2 + "\",\"title\":\"Kick\",\"message\":"
                        + "\"Kick seat 2 (\\\"c2\\\")?\"}]}").equals(json)
                        && "{\"needsConfirm\":[]}".equals(OpsConsoleForms.needsConfirmJson(List.of())),
                "needsConfirm is ALWAYS an array of escaped {key,title,message}; got " + json
                        + " (flips if a single confirm is sent bare or a quote breaks the JSON)");
        need("{\"ok\":true,\"detail\":\"sent 'end-round'\"}".equals(OpsConsoleForms.okJson("sent 'end-round'"))
                        && "{\"error\":\"a\\nb\",\"title\":\"Verb refused\"}".equals(
                                OpsConsoleForms.errorJson("Verb refused", "a\nb"))
                        && "{\"error\":\"\",\"title\":\"\"}".equals(OpsConsoleForms.errorJson(null, null)),
                "ok/error keep the July shapes, escaped, null = \"\" (flips if a key is renamed or reordered)");
    }

    /**
     * Reject seat. The verb the
     * grammar refused until now ({@code unknown verb 'custom-reject'}, the deleted refused-shape row)
     * mints {@code ServerOpsCore.customRejectLine} under the Swing pick law + occupant binding that
     * {@code custom-allow} uses, and asks its occupant-bound confirm.
     */
    static void customReject() {
        String line = OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm("verb=custom-reject&seat=3"), SNAP_A);
        need(ServerOpsCore.customRejectLine(3).equals(line) && "custom-reject 3".equals(line),
                "custom-reject seat=3 minted '" + line + "', want customRejectLine(3) (flips if the case is"
                        + " dropped - the verb reads 'unknown verb' again - or it mints another seat's line)");
        OperatorCommandFile.ParseResult parsed = OperatorCommandFile.parse(List.of(line));
        need(parsed.rejected().isEmpty() && parsed.commands().size() == 1
                        && parsed.commands().get(0).verb() == OperatorCommandFile.Verb.CUSTOM_REJECT,
                "the minted line parses as CUSTOM_REJECT in the REAL operator-file grammar; rejected "
                        + parsed.rejected() + " (flips if the page mints a shape the serve refuses)");
        String[][] refused = {
                {"verb=custom-reject", "Select a seat row first."},
                {"verb=custom-reject&seat=", "Select a seat row first."},
                {"verb=custom-reject&seat=x3", "seat must be a seat number (1 to 9 digits)"},
                {"verb=custom-reject&seat=9", "Seat 9 is no longer seated."},
                {"verb=custom-reject&seat=2&key=" + K9, "Seat 2 changed hands - nothing sent"}};
        for (String[] c : refused) {
            String why = refusal(() -> OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(c[0]), SNAP_A));
            need(c[1].equals(why), c[0] + " answered '" + why + "', want '" + c[1] + "' (flips if Reject seat"
                    + " skips the pick law or the occupant compare custom-allow keeps)");
        }
        expect("verb=custom-reject&seat=3", 0L, "custom-reject:3:" + K3, "Reject seat",
                "Reject seat 3 (c3)? Its player may not submit customs for the rest of this serve and every"
                        + " custom they hold is banned; Allow seat reverses it.");
        OpsConsoleForms.ConfirmSpec moved = OpsConsoleForms.verbConfirm(
                OpsConsoleForms.decodeForm("verb=custom-reject&seat=2"), SNAP_B, 0L);
        need(moved != null && ("custom-reject:2:" + K9).equals(moved.key()),
                "the Reject confirm on seat 2's new occupant keys on c9, never c2: " + moved
                        + " (flips if the Reject confirm is not occupant-bound)");
    }

    /**
     * The typed Custom id. For
     * BOTH custom-ban and custom-export: a non-blank {@code id=} mints that id with no seat (and wins
     * over a picked stock seat); a blank / whitespace {@code id=} falls back to the picked seat's
     * flying custom; a bad id is refused with the shared minter's own text, its echo bounded.
     */
    static void customTypedId() {
        for (String verb : List.of("custom-ban", "custom-export")) {
            String typed = OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(
                    "verb=" + verb + "&id=cc-v7-typed"), SNAP_A);
            String overStock = OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(
                    "verb=" + verb + "&id=cc-v7-typed&seat=3"), SNAP_A);
            need((verb + " cc-v7-typed").equals(typed) && typed.equals(overStock),
                    verb + " id=cc-v7-typed minted '" + typed + "' / with stock seat 3 '" + overStock
                            + "' (flips if the typed id is ignored or the picked seat still wins)");
            for (String blank : List.of("&id=", "&id=+++")) {
                String line = OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(
                        "verb=" + verb + blank + "&seat=2"), SNAP_A);
                need((verb + " cc-v2-brawl").equals(line), verb + blank + " minted '" + line
                        + "', want seat 2's flying custom (flips if a blank box stops falling back to the pick)");
            }
            String bad = refusal(() -> OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(
                    "verb=" + verb + "&id=bad%20id%21"), SNAP_A));
            String minter = refusal(() -> ServerOpsCore.customBanLine("bad id!"));
            need(bad != null && bad.equals(minter), verb + " id='bad id!' answered '" + bad + "', want the"
                    + " minter's '" + minter + "' (flips if a bad typed id mints or is refused with other text)");
            String z = "z".repeat(100);
            String longWhy = refusal(() -> OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(
                    "verb=" + verb + "&id=" + z), SNAP_A));
            need(longWhy != null && !longWhy.contains("z".repeat(OpsConsoleForms.ECHO_MAX + 1)),
                    verb + " a 100-char id answered " + (longWhy == null ? "a minted line" : PeerText.bound(longWhy, 120))
                            + " (flips if the typed id's refusal echoes it unbounded or a 100-char id mints)");
            OperatorCommandFile.ParseResult parsed = OperatorCommandFile.parse(List.of(typed));
            need(parsed.rejected().isEmpty() && parsed.commands().size() == 1, verb + " typed line rejected by the"
                    + " REAL operator-file grammar: " + parsed.rejected() + " (flips if the typed path mints a bad shape)");
        }
        expect("verb=custom-ban&id=cc-v7-typed", 0L, "custom-ban:id:cc-v7-typed", "Ban custom",
                "Ban custom id 'cc-v7-typed'? It becomes unpickable this serve and re-submitting the same body is"
                        + " refused (custom_banned); the author may still submit a different variant.");
        need(OpsConsoleForms.verbConfirm(OpsConsoleForms.decodeForm("verb=custom-export&id=cc-v7-typed"),
                        SNAP_A, 0L) == null,
                "a typed Export custom asks no confirm (flips if the Swing no-confirm Export gains a dialog)");
    }

    /**
     * Row {@code forms-ship-hide-show} - the per-row Hide / Show under
     * "Ships that come with the server" over decoded bodies. WHY: a row names its ship by {@code id=} and by nothing
     * else (a {@code stem=} / {@code exportName=} riding along is never read); the text is trimmed and judged by the
     * shared minter, whose refusal IS the answer; a blank id is refused before any line exists; Hide asks a confirm
     * keyed by the MINTED id ({@code ship-hide:<id>}, so another row's Hide asks again), Show asks none.
     */
    static void shipHideShow() {
        String[][] ok = {
                {"verb=ship-hide&id=wolf_Assault", "ship-hide wolf_Assault"},
                {"verb=ship-hide&id=+wolf_Assault+", "ship-hide wolf_Assault"},
                {"verb=ship-show&id=wolf_Assault%09", "ship-show wolf_Assault"},
                {"verb=ship-hide&stem=kept-a-1a2b3c&exportName=cc-v1-x&id=Doom_antimatter-srm",
                        "ship-hide Doom_antimatter-srm"},
                {"verb=ship-show&id=astral_Flash&stem=kept-a-1a2b3c", "ship-show astral_Flash"}};
        List<String> minted = new ArrayList<>();
        for (String[] c : ok) {
            String line = OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(c[0]), SNAP_A);
            need(c[1].equals(line), c[0] + " minted '" + line + "', want '" + c[1] + "' (flips if the arm reads"
                    + " another form key than id=, drops the trim, or the two verbs swap minters)");
            minted.add(line);
        }
        OperatorCommandFile.ParseResult parsed = OperatorCommandFile.parse(minted);
        need(parsed.rejected().isEmpty() && parsed.commands().size() == minted.size()
                        && parsed.commands().get(0).verb() == OperatorCommandFile.Verb.SHIP_HIDE
                        && parsed.commands().get(2).verb() == OperatorCommandFile.Verb.SHIP_SHOW,
                "the minted lines parse as SHIP_HIDE / SHIP_SHOW in the REAL operator-file grammar; rejected "
                        + parsed.rejected() + " (flips if the page mints a shape the serve refuses)");
        String pick = ": pick a ship that comes with the server first";
        String[][] refused = {
                {"verb=ship-hide&id=", "ship-hide" + pick},
                {"verb=ship-hide&id=+++", "ship-hide" + pick},
                {"verb=ship-show", "ship-show" + pick},
                {"verb=ship-hide&stem=wolf_Assault", "ship-hide" + pick},
                {"verb=ship-hide&id=wolf_Assault%0Aend-match", OpsConsoleForms.CONTROL_CHAR}};
        for (String[] c : refused) {
            String why = refusal(() -> OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(c[0]), SNAP_A));
            need(c[1].equals(why), c[0] + " answered '" + why + "', want '" + c[1] + "' (flips if a blank or"
                    + " id-less Hide / Show mints, reads another key, or a control char reaches the command file)");
        }
        String[][] minterRefused = {
                {"verb=ship-hide&id=cc-v1-assault", "cc-v1-assault"},
                {"verb=ship-show&id=CC-V2-brawl", "CC-V2-brawl"},
                {"verb=ship-hide&id=bad%20id%21", "bad id!"},
                {"verb=ship-hide&id=wolf%3AAssault", "wolf:Assault"},
                {"verb=ship-show&id=_wolf", "_wolf"}};
        for (String[] c : minterRefused) {
            String why = refusal(() -> OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm(c[0]), SNAP_A));
            String minter = c[0].startsWith("verb=ship-hide") ? refusal(() -> ServerOpsCore.shipHideLine(c[1]))
                    : refusal(() -> ServerOpsCore.shipShowLine(c[1]));
            need(minter != null && minter.equals(why), c[0] + " answered '" + why + "', want the minter's '" + minter
                    + "' (flips if a custom id / a bad charset / a token outside the shipped-stem law mints, or the"
                    + " form answers other text than the minter)");
        }
        String z = "z".repeat(100);
        String longWhy = refusal(() -> OpsConsoleForms.verbLine(OpsConsoleForms.decodeForm("verb=ship-hide&id=" + z),
                SNAP_A));
        need(longWhy != null && !longWhy.contains("z".repeat(OpsConsoleForms.ECHO_MAX + 1)),
                "a 100-char id answered " + (longWhy == null ? "a minted line" : PeerText.bound(longWhy, 120))
                        + " (flips if a 100-char id mints or its refusal echoes it unbounded)");
        expect("verb=ship-hide&id=wolf_Assault", 0L, "ship-hide:wolf_Assault", ServerOpsCore.SHIP_HIDE_TITLE,
                ServerOpsCore.shipHideConfirmText("wolf_Assault"));
        expect("verb=ship-hide&id=+wolf_Assault+&stem=kept-a-1a2b3c", 0L, "ship-hide:wolf_Assault",
                ServerOpsCore.SHIP_HIDE_TITLE, ServerOpsCore.shipHideConfirmText("wolf_Assault"));
        OpsConsoleForms.ConfirmSpec other = OpsConsoleForms.verbConfirm(
                OpsConsoleForms.decodeForm("verb=ship-hide&id=astral_Flash"), SNAP_A, 0L);
        need(other != null && "ship-hide:astral_Flash".equals(other.key())
                        && other.message().contains("'astral_Flash'"),
                "another row's Hide keys on its own id: " + other + " (flips if the key stops binding the minted id, so"
                        + " one row's confirm would pass another's Hide)");
        need(OpsConsoleForms.verbConfirm(OpsConsoleForms.decodeForm("verb=ship-show&id=wolf_Assault"), SNAP_A, 0L)
                        == null,
                "Show asks no confirm (flips if the unban-idiom Show gains a dialog)");
        String refusedAsk = refusal(() -> OpsConsoleForms.verbConfirm(
                OpsConsoleForms.decodeForm("verb=ship-hide&id=cc-v1-assault"), SNAP_A, 0L));
        need(refusedAsk != null && refusedAsk.equals(refusal(() -> ServerOpsCore.shipHideLine("cc-v1-assault"))),
                "a refused Hide throws the minter's text before any confirm, got " + refusedAsk
                        + " (flips if a confirm is minted for an id the minter refuses)");
    }

    static void startTristate() {
        ServerOpsCore.DescriptorInfo info = new ServerOpsCore.DescriptorInfo("m-1", "1v1", 2);
        String full = "bindHost=0.0.0.0&bindPort=51337&tickRate=60&fortressDp=400&descriptor=match%2Fa.txt"
                + "&joinerDefault=aurora_Balanced&fortressWaveScale=1.5&waveCategory=derelict&endless=off"
                + "&modsEnabled=m1&modsOptional=m2&terrain=corona&terrainLevel=50&asteroids=100"
                + "&waveRosterFullDp=300";
        OpsWindowModel.StartFields s = OpsConsoleForms.startFields(
                OpsConsoleForms.decodeForm(full), "match/a.txt", info, "hunter2");
        List<Object> want = List.of("0.0.0.0", "51337", "60", "400", "hunter2", "match/a.txt", info,
                "aurora_Balanced", "1.5", "<null>", "derelict", "off", "m1", "m2", "corona", "50", "100", "300");
        List<Object> got = new ArrayList<>(List.of(s.bindHost(), s.bindPort(), s.tickText(),
                s.fortressDpText(), s.passwordText(), s.selDescriptor(), s.selInfo(), s.joinerDefaultText(),
                s.fortressWaveScaleText()));
        got.add(s.adminPasswordText() == null ? "<null>" : s.adminPasswordText());
        got.addAll(List.of(s.waveCategoryText(), s.endlessText(), s.modsEnabledText(), s.modsOptionalText(),
                s.terrainText(), s.terrainLevelText(), s.asteroidsText(), s.waveRosterFullDpText()));
        need(want.equals(got), "the 18 components land in StartFields order (keep = the kept text, admin"
                + " keep = null); got " + got + " (flips if a field maps to the wrong component)");
        String req = "bindHost=0.0.0.0&bindPort=51337&tickRate=60&fortressDp=0&descriptor=match%2Fa.txt";
        OpsWindowModel.StartFields bare = start(req + "&passwordAction=keep", null);
        need("".equals(bare.passwordText()) && bare.adminPasswordText() == null
                        && bare.joinerDefaultText() == null && bare.terrainText() == null
                        && bare.waveRosterFullDpText() == null,
                "keep with no stored text = \"\", an omitted optional field = null, never blank (flips if"
                        + " omitted reads as \"\" - that row would be rewritten)");
        need("pw-new".equals(start(req + "&passwordAction=replace&password=pw-new", "old").passwordText())
                        && "adm-new".equals(start(req + "&adminAction=replace&adminPassword=adm-new", "old")
                                .adminPasswordText())
                        && "".equals(start(req + "&adminAction=remove&adminRemove=on", "old").adminPasswordText()),
                "replace = the typed text, admin remove with its box = \"\" (flips if replace keeps the"
                        + " stored text or remove is not the empty string)");
        String[][] refused = {
                {req + "&passwordAction=replace&password=+++", OpsConsoleForms.BLANK_PASSWORD},
                {req + "&passwordAction=replace&password=%22%22", OpsConsoleForms.BLANK_PASSWORD},
                {req + "&passwordAction=replace", OpsConsoleForms.BLANK_PASSWORD},
                {req + "&passwordAction=remove", ServerOpsLaunch.PACKAGE_PASSWORD_REQUIRED},
                {req + "&adminAction=replace&adminPassword=", OpsConsoleForms.BLANK_PASSWORD},
                {req + "&adminAction=remove", OpsConsoleForms.ADMIN_REMOVE_BOX},
                {req + "&adminAction=remove&adminRemove=yes", OpsConsoleForms.ADMIN_REMOVE_BOX},
                {req + "&passwordAction=wipe", "passwordAction must be keep, replace or remove"},
                {req + "&adminAction=drop", "adminAction must be keep, replace or remove"},
                {"bindPort=51337&tickRate=60&fortressDp=0&descriptor=a", "missing field: bindHost"},
                {"bindHost=h&tickRate=60&fortressDp=0&descriptor=a", "missing field: bindPort"},
                {"bindHost=h&bindPort=1&fortressDp=0&descriptor=a", "missing field: tickRate"},
                {"bindHost=h&bindPort=1&tickRate=60&descriptor=a", "missing field: fortressDp"},
                {"bindHost=h&bindPort=1&tickRate=60&fortressDp=0", "missing field: descriptor"},
                {req + "&joinerDefault=a%0Dmatch.x%3D1", OpsConsoleForms.CONTROL_CHAR}};
        for (String[] c : refused) {
            String why = refusal(() -> start(c[0], "old"));
            need(c[1].equals(why), c[0] + " answered '" + why + "', want '" + c[1] + "' (flips if a blank"
                    + " replace / a box-less admin remove / a match remove / an omitted non-nullable field"
                    + " reaches the Start flow)");
        }
    }

    /** {@code forms-voice} - the Voice tab's Apply as
     * a form: the ctor's refusal untouched, the strict on/off + integer refusals, absent = kept,
     * the latency-rig pair carried, a full form lands field for field, the Swing bandwidth line. */
    static void voice() {
        VoiceConfig cur = new VoiceConfig(true, 64, 10, 7, true, true, true, 5, 700, 2600, 3, 2, 90, 3000);
        String complexity = refusal(() -> voiceOf("complexity=11", VoiceConfig.defaults()));
        need("voice complexity must be 0..10: 11".equals(complexity)
                        && Set.of("complexity").equals(OpsVoicePanel.offendingKnobs(complexity)),
                "complexity=11 answered '" + complexity + "' (flips if the ctor's refusal is re-worded /"
                        + " swallowed, or its message stops naming only the offending knob)");
        String[][] refused = {
                {"enabled=yes", "voice enabled wants on or off, got 'yes'"},
                {"fec=ON", "voice fec wants on or off, got 'ON'"},
                {"enabled=on%20", "voice enabled wants on or off, got 'on '"},
                {"bitrateKbps=abc", "voice bitrateKbps wants an integer, got 'abc'"},
                {"jitterMaxMs=" + "9".repeat(60), "voice jitterMaxMs wants an integer, got '"
                        + "9".repeat(OpsConsoleForms.ECHO_MAX) + "…'"},
                {"maxTalkers=4&enabled=on&x=a%0Ab", OpsConsoleForms.CONTROL_CHAR}};
        for (String[] c : refused) {
            String why = refusal(() -> voiceOf(c[0], cur));
            need(c[1].equals(why), c[0] + " answered '" + why + "', want '" + c[1] + "' (flips if the"
                    + " on/off law folds case / trims, an integer parse drifts, the echo is unbounded, or a"
                    + " control char is not refused first)");
        }
        VoiceConfig fecOnly = voiceOf("fec=off&lowDelay=off&echo=off", cur);
        need(new VoiceConfig(true, 64, 10, 7, false, true, true, 5, 700, 2600, 3, 2, 90, 3000).equals(fecOnly),
                "fec=off alone moved only fec; got " + fecOnly + " (flips if an absent knob takes the"
                        + " default instead of current's value, or the form steers lowDelay / echo)");
        VoiceConfig full = voiceOf("enabled=off&bitrateKbps=32&frameMs=20&complexity=3&fec=off"
                + "&maxTalkers=4&proxFullSu=800&proxCullSu=3000&relayTickMs=5&jitterMinFrames=3"
                + "&jitterMaxMs=120&jitterShrinkMs=4000", cur);
        need(new VoiceConfig(false, 32, 20, 3, false, true, true, 4, 800, 3000, 5, 3, 120, 4000).equals(full),
                "a full valid form lands field for field; got " + full + " (flips if a knob maps to the"
                        + " wrong ctor slot or is dropped)");
        String line = OpsConsoleForms.voiceBandwidthText(full);
        need(OpsVoicePanel.readout(32, 20, 4).equals(line) && !OpsVoicePanel.readout(64, 10, 5).equals(line)
                        && line.contains("at 4 talkers"),
                "the bandwidth line = the Swing readout of (kbps, frameMs, maxTalkers); got '" + line
                        + "' (flips if it reads the wrong knobs or re-implements the formula)");
    }

    /** {@code forms-mods-path} - one case per text
     * rule, in rule order (Windows path semantics: the console host), plus the Use value law. */
    static void modsPath() {
        String longPath = "C:\\" + "a".repeat(OpsConsoleForms.MODS_PATH_MAX - 3); // exactly 1024 chars
        String moveFirst = "\nMove or rename the folder first."; // Swing's second line, in OpsModsPanel
        String[][] cases = {
                {"", "a mods folder path is required"},
                {"   ", "a mods folder path is required"},
                {"C:\\x\ny", OpsConsoleForms.CONTROL_CHAR},
                {"C:\\x" + (char) 0, OpsConsoleForms.CONTROL_CHAR},
                {longPath + "x", "path too long (max 1024)"},
                {" C:\\x", "Cannot list ' C:\\x': leading or trailing blank"},
                {"C:\\x ", "Cannot list 'C:\\x ': leading or trailing blank"},
                {"C:\\a;b", "Cannot list C:\\a;b: a ';' in the path (the mods.roots separator)." + moveFirst},
                {"\\\\wsl$\\x", "Cannot list \\\\wsl$\\x: network paths are not searched."},
                {"//x", "Cannot list //x: network paths are not searched."},
                // the mixed-slash UNC forms and the device namespace: refused the same
                {"/\\wsl$\\x", "Cannot list /\\wsl$\\x: network paths are not searched."},
                {"\\/srv/share", "Cannot list \\/srv/share: network paths are not searched."},
                {"/\\.\\pipe\\x", "Cannot list /\\.\\pipe\\x: network paths are not searched."},
                {"//x;y", "Cannot list //x;y: a ';' in the path (the mods.roots separator)." + moveFirst},
                {"C:\\x<y", "Cannot list C:\\x<y: not a valid path."},
                {"mods", "Cannot list mods: the path must be absolute."},
                {"C:mods", "Cannot list C:mods: the path must be absolute."},
                {longPath.substring(0, 1000) + ";", "Cannot list " + PeerText.bound(longPath.substring(0, 1000)
                        + ";", OpsConsoleForms.MODS_PATH_ECHO_MAX) + ": a ';' in the path (the mods.roots"
                        + " separator)." + moveFirst}};
        for (String[] c : cases) {
            String why = OpsConsoleForms.modsPathRefusal(c[0]);
            need(c[1].equals(why), PeerText.bound(c[0], 48) + " answered '" + PeerText.bound(why, 160)
                    + "', want '" + PeerText.bound(c[1], 160) + "' (flips if a rule is dropped, reordered or"
                    + " re-worded, or the echo exceeds " + OpsConsoleForms.MODS_PATH_ECHO_MAX + " chars)");
        }
        need(OpsConsoleForms.modsPathRefusal(longPath) == null
                        && OpsConsoleForms.modsPathRefusal("C:\\Users\\x\\mods") == null
                        && OpsConsoleForms.modsPathRefusal(null) != null,
                "a 1024-char absolute path and C:\\Users\\x\\mods are accepted (flips if the length bound is"
                        + " off by one or a lawful absolute path is refused)");
        for (String use : OpsModsPanel.MOD_CHOICES) {
            need(use.equals(OpsConsoleForms.modsUseValue(use)), "Use '" + use + "' accepted verbatim");
        }
        for (String bad : new String[] {"ON", "Required", "", null}) {
            need("use must be off, required or optional".equals(refusal(() -> OpsConsoleForms.modsUseValue(bad))),
                    "Use '" + bad + "' refused (flips if the Use law folds case or accepts a non-choice)");
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static VoiceConfig voiceOf(String body, VoiceConfig current) {
        return OpsConsoleForms.voiceConfig(OpsConsoleForms.decodeForm(body), current);
    }

    private static OpsWindowModel.StartFields start(String body, String kept) {
        return OpsConsoleForms.startFields(OpsConsoleForms.decodeForm(body), "match/a.txt", null, kept);
    }

    private static void expect(String form, long writerPid, String key, String title, String message) {
        OpsConsoleForms.ConfirmSpec spec = OpsConsoleForms.verbConfirm(
                OpsConsoleForms.decodeForm(form), SNAP_A, writerPid);
        need(spec != null && key.equals(spec.key()) && title.equals(spec.title())
                        && message.equals(spec.message()),
                form + " confirm " + spec + ", want key=" + key + " title=" + title
                        + " (flips if the Swing confirm key / title / text drifts)");
    }

    private static void need(boolean ok, String why) {
        if (!ok) {
            throw new RowFail(why);
        }
    }

    /** The IllegalArgumentException message {@code action} throws, or {@code null} when it does not. */
    private static String refusal(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
        return null;
    }

    private static OpsSnapshot snapshot(String seat2Client) {
        List<OpsStatusFile.SeatRow> seats = List.of(
                new OpsStatusFile.SeatRow(2, seat2Client, "127.0.0.1:2", "wolf_Assault", true, 0L, 0L, 0,
                        "Ann", "cc-v2-brawl", "wolf", 10, "Brawl"),
                new OpsStatusFile.SeatRow(3, "c3", "203.0.113.9:3", "lasher_CS", true, 0L, 0L, 0, "Bob"));
        return OpsSnapshot.parse(OpsStatusFile.composeLines(1L, 1L, "run", "127.0.0.1", 51337, 7, 1, 2,
                2L, 4, 60.0, 12.5, 3.2, 1000L, 500L, 100L, 50L, 0, 0.0, 0L, "cmd", seats,
                new long[][] {{0L, 0L}, {0L, 0L}}, PhaseWindowStats.Snapshot.EMPTY));
    }

    public static void main(String[] args) {
        List<String> fails = run();
        if (!fails.isEmpty()) {
            System.out.println("ops-console-forms selftest FAILED (" + fails.size() + " row(s))");
            System.exit(1);
        }
        System.out.println("ops-console-forms selftest PASS");
    }
}
