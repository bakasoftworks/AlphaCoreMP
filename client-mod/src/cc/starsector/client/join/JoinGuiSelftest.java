package cc.starsector.client.join;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
// CRC32 repairs the canned mark-2 LSTD golden after flipping a mark
import java.util.zip.CRC32;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.JTextComponent;

import cc.starsector.client.net.ClientListingReader;
import cc.starsector.client.net.ClientVariantReceipt;
import cc.starsector.client.net.ClientVariantReceiptReader;
import cc.starsector.client.net.DoorProbe;
import cc.starsector.client.net.DoorVariantCheck;
import cc.starsector.client.net.VariantRejectHints;
import cc.starsector.server.runtime.DoorRejectReasons;
import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDocumentLaws;
import cc.starsector.server.runtime.DatagramFrameCodec;
import cc.starsector.server.runtime.HandshakeCodec;

// the battery's rows read exactly as they did inside JoinGui: JoinCore's Swing-free laws on demand
// (the carve's static import), plus the one JoinGui constant a face row checks
import static cc.starsector.client.join.JoinCore.*;
import static cc.starsector.client.join.JoinGui.WINDOW_TITLE;
import static cc.starsector.client.join.JoinGuiSelftestFs.*;

/**
 * The {@code JoinGui --selftest} battery: the headless logic-layer assertions for the join window
 * (settings round-trip, descriptor normalize/decode/canonicalize, seat-2 check, plan-file golden,
 * validation table, launch gates, mod stage, browser UI, voice, current build, local customs,
 * ship pick, the probe commit table), run by the {@code JoinGui --selftest} stanza of {@code client-mod/build.ps1} (the
 * {@code Join-GUI logic selftest PASS} line).
 *
 * <p>WHY a class of its own: the deferred-refactor list row "{@code JoinGui.java} (client
 * join window)" — JoinGui was ~2004 nb against a 2204 tier and this battery was half the file, so
 * the first act at the next JoinGui landing carved it out verbatim, mirroring the
 * JoinCore/JoinWindowDpi carve. The rows, javadoc and
 * helpers moved byte-verbatim; only {@code selftest()} became {@link #run()}.
 *
 * <p>Headless: no window is ever shown — the battery drives the Swing-free laws in {@link JoinCore} and the join /
 * net helpers, plus the UNSHOWN-component pin {@code selftestSwingPlainText}; the probe-commit battery (its own
 * unshown-component pin included) lives in {@link JoinGuiProbeSelftest}, run from {@link #run()}; the seven
 * scratch-directory rows (launch gates, mod stage, current build, local customs, voice, ship pick) live in the
 * sibling {@link JoinGuiSelftestFs}, same call lines.
 *
 *
 */
final class JoinGuiSelftest {

    private JoinGuiSelftest() {
    }

    // ------------------------------------------------------------- selftest

    /** Structure mirrors the standing descriptor (match-standing.txt, 2026-07-06); dummy tokens. */
    static final String SELFTEST_DESCRIPTOR = "ccmatch.v=1\n"
            + "match.id=1v1-selftest0000000\n"
            + "match.shape=1v1\n"
            + "map.halfWidth=6000.0\n"
            + "map.halfHeight=6000.0\n"
            + "rules.deployCr=0.7\n"
            + "rules.endCondition=BUDGET\n"
            + "side.count=2\n"
            + "side.0.name=ISS\n"
            + "side.0.tagline=selftest side A\n"
            + "side.0.goal=ATTACK\n"
            + "side.1.name=HSS\n"
            + "side.1.tagline=selftest side B\n"
            + "side.1.goal=ATTACK\n"
            + "roster.count=2\n"
            + "roster.0.side=0\n"
            + "roster.0.variant=mudskipper2_cc_Hellbore\n"
            + "roster.0.count=1\n"
            + "roster.0.pilot=SEAT\n"
            + "roster.1.side=1\n"
            + "roster.1.variant=mudskipper2_cc_Hellbore\n"
            + "roster.1.count=1\n"
            + "roster.1.pilot=SEAT\n"
            + "seat.count=2\n"
            + "seat.0.id=1\n"
            + "seat.0.role=PILOT\n"
            + "seat.0.side=0\n"
            + "seat.0.ship=1\n"
            + "seat.0.token=00112233445566778899aabbccddeeff\n"
            + "seat.1.id=2\n"
            + "seat.1.role=PILOT\n"
            + "seat.1.side=1\n"
            + "seat.1.ship=2\n"
            + "seat.1.token=ffeeddccbbaa99887766554433221100\n";

    static void run() throws Exception {
        // settings round-trip (isolated temp dir, never the real APPDATA)
        Path tmp = Files.createTempDirectory("cc-join-selftest");
        Path settingsFile = tmp.resolve("sub").resolve("join-settings.properties");
        new JoinSettings("vpn.example", "51820", "Pilot Two", "match-pass-1",
                "ffeeddccbbaa99887766554433221100", true, "onslaught_xao_Standard", "vpn.example:51820")
                .save(settingsFile);
        JoinSettings back = JoinSettings.load(settingsFile);
        java.util.Properties retired = new java.util.Properties();
        retired.setProperty("host", "h");
        retired.setProperty("cid", "ffeeddccbbaa99887766554433221100");
        // The cid row is retired — a constructed cid never reaches the
        // file, and an older file's cid= row is never read back (the per-host id is derived from cid-master)
        check("vpn.example".equals(back.host) && "51820".equals(back.port)
                && "Pilot Two".equals(back.name)
                && "match-pass-1".equals(back.password)
                && back.cid.isEmpty() && JoinSettings.fromProperties(retired).cid.isEmpty()
                && !new String(Files.readAllBytes(settingsFile), StandardCharsets.ISO_8859_1).contains("cid=")
                && back.logs,
                "settings round-trip (password 1239, logs on; flips if a save writes a cid row or a load reads one)");
        check("onslaught_xao_Standard".equals(back.ship), "settings ship round-trip (139 L3)");
        // Logs checkbox round-trip: off persists, and an absent row (every older
        // settings file) reads as on — the shipped default.
        new JoinSettings("h", "1", "", "", "", false, "", "").save(tmp.resolve("logsoff.properties"));
        check(!JoinSettings.load(tmp.resolve("logsoff.properties")).logs,
                "logs=off round-trip (83)");
        check(JoinSettings.load(tmp.resolve("absent.properties")).host.isEmpty()
                && JoinSettings.load(tmp.resolve("absent.properties")).password.isEmpty()
                && JoinSettings.load(tmp.resolve("absent.properties")).logs,
                "missing settings file reads as blanks (logs default on)");
        // pre-1239 settings file (no password/cid rows) reads as blanks, not a crash
        Files.write(tmp.resolve("old.properties"),
                "host=h\nport=1\nname=n\n".getBytes(StandardCharsets.US_ASCII));
        check(JoinSettings.load(tmp.resolve("old.properties")).password.isEmpty()
                && JoinSettings.load(tmp.resolve("old.properties")).cid.isEmpty()
                && JoinSettings.load(tmp.resolve("old.properties")).ship.isEmpty(),
                "pre-1239 settings file tolerated (blank password/cid; pre-L3 ship = default)");
        // pre-1241 settings file: its persisted token= row is ignored, everything else reads
        Files.write(tmp.resolve("b2.properties"),
                "host=h2\nport=2\nname=n2\ntoken=00112233445566778899aabbccddeeff\n"
                        .getBytes(StandardCharsets.US_ASCII));
        check("h2".equals(JoinSettings.load(tmp.resolve("b2.properties")).host)
                && "2".equals(JoinSettings.load(tmp.resolve("b2.properties")).port),
                "pre-1241 settings file tolerated (token= row ignored)");

        // paste normalization: CRLF + trailing blank lines decode, and re-encode is canonical
        String mangled = SELFTEST_DESCRIPTOR.replace("\n", "\r\n") + "\r\n\r\n";
        MatchDescriptor descriptor = JoinLaunchPlan.parseDescriptor(mangled);
        String canonical = JoinLaunchPlan.canonicalText(descriptor);
        check(canonical.equals(SELFTEST_DESCRIPTOR), "CRLF paste canonicalizes byte-identically");
        check(canonical.equals(JoinLaunchPlan.canonicalText(
                JoinLaunchPlan.parseDescriptor(canonical))), "canonicalize is idempotent");

        // the pilot-seat gate (AUTO seat: any pilot seat row qualifies — the
        // fixed seat-2 gate died with the -Dcc.predict.shipId=2 pin; a seatless descriptor
        // cannot decode, so the throw lane is a belt with no codec-reachable negative row)
        check(JoinLaunchPlan.requirePilotSeat(descriptor).shipId() >= 1L, "pilot seat row found");

        // fail-closed rejects
        checkThrows(() -> JoinLaunchPlan.parseDescriptor("not a credential"), "garbage paste");
        checkThrows(() -> JoinLaunchPlan.parseDescriptor(""), "empty paste");

        // plan-file golden (CRLF: cmd for /f consumes it; rows ride even when empty;
        // the token= row died with the GUI handout lane)
        check(JoinLaunchPlan.planFileContent(" 1.2.3.4 ", "7777", " \"My Pilot\" ", "", "", true,
                "")
                .equals("host=1.2.3.4\r\nport=7777\r\nname=My Pilot\r\n"
                        + "password=\r\ncid=\r\nlogs=on\r\nship=\r\n"),
                "plan-file golden (empty credential rows, no token= row, empty ship row)");
        check(JoinLaunchPlan.planFileContent("h", "1", "", " \"pass-1\" ",
                "ffeeddccbbaa99887766554433221100", true, JoinProbe.SERVER_DEFAULT_LABEL)
                .equals("host=h\r\nport=1\r\nname=\r\npassword=pass-1\r\n"
                        + "cid=ffeeddccbbaa99887766554433221100\r\nlogs=on\r\nship=\r\n"),
                "plan-file golden (password quote-strip + cid rides, 1239 P5; server-default"
                        + " combo entry writes an EMPTY ship row)");
        check(JoinLaunchPlan.planFileContent("h", "1", "", "",
                "ffeeddccbbaa99887766554433221100", false, " \"onslaught_xao_Standard\" ")
                .equals("host=h\r\nport=1\r\nname=\r\npassword=\r\ncid=\r\nlogs=off\r\n"
                        + "ship=onslaught_xao_Standard\r\n"),
                "plan-file golden (blank password suppresses cid; logs=off rides, 83; ship"
                        + " quote-strip, 139 L3)");
        // row (f): the D4 editor=1 row is RETIRED — the window
        // keeps the intent itself; the plan is the 7-row shape, ship= last
        check(JoinLaunchPlan.planFileContent("h", "1", "", "", "", true, "wolf_Assault")
                .equals("host=h\r\nport=1\r\nname=\r\npassword=\r\ncid=\r\nlogs=on\r\n"
                        + "ship=wolf_Assault\r\n"),
                "plan-file golden (ship= is the LAST row; no editor row, row (f))");
        // JAVACRED-1 (scan row 7): a name carrying CR / LF + "host=" adds NO plan
        // row (the bat's for /f keeps the LAST duplicate) — still seven rows, the form's host; and a
        // control character in any OTHER row value refuses the plan write outright
        String injected = JoinLaunchPlan.planFileContent("1.2.3.4", "7777", "ab\r\nhost=6.6.6.6\u001A", "", "",
                true, "");
        check(injected.equals("host=1.2.3.4\r\nport=7777\r\nname=abhost=6.6.6.6\r\n"
                + "password=\r\ncid=\r\nlogs=on\r\nship=\r\n") && injected.split("\r\n", -1).length == 8,
                "plan-file: a CR/LF name adds no row (seven rows, the form's host; JAVACRED-1)");
        checkPlanRefusal(() -> JoinLaunchPlan.planFileContent("1.2.3.4", "7777\nhost=6.6.6.6", "", "", "", true, ""),
                "port", "plan-file: a control character in the port row refuses the write");
        // Host and cid are the two rows whose value can REACH planRow with a control
        // character (interior ones — trim takes the ends); the refusal is planRow's own exception,
        // naming the row and never the value
        checkPlanRefusal(() -> JoinLaunchPlan.planFileContent("6.6.6.6\u0001x", "7777", "", "", "", true, ""),
                "host", "plan-file: a control character in the host row refuses the write");
        checkPlanRefusal(() -> JoinLaunchPlan.planFileContent("1.2.3.4", "7777", "", "pass-1",
                "ab\r\nhost=6.6.6.6", true, ""),
                "cid", "plan-file: a CR/LF cid refuses the write (planRow is the cid row's only gate)");
        // row (f) law 2: the endpoint file's nonce law (own / foreign / absent / legacy reader)
        String epTok = "00112233445566778899aabbccddeeff";
        check(("http://127.0.0.1:5000/" + epTok + "/").equals(
                endpointBase("5000|" + epTok + "|0badf00d", "0badf00d")),
                "endpoint: the window's own nonce is accepted");
        check(endpointBase("5000|" + epTok + "|deadbeef", "0badf00d") == null,
                "endpoint: a foreign nonce keeps the window waiting");
        check(endpointBase("5000|" + epTok, "0badf00d") == null,
                "endpoint: a two-field (nonce-less) file fails a window that has a nonce");
        check(endpointBase("5000|" + epTok, null) != null
                && endpointBase("5000|" + epTok + "|0badf00d", null) != null,
                "endpoint: a window without a nonce accepts both file shapes (tooling, pre-launch)");
        check(endpointBase("5000|" + epTok + "|ZZ", null) == null
                && endpointBase("5000|" + epTok + "|0badf00d|x", "0badf00d") == null
                && endpointBase("70000|" + epTok + "|0badf00d", "0badf00d") == null
                && endpointBase("", null) == null && endpointBase(null, null) == null,
                "endpoint: a malformed nonce, a fourth field, a bad port, an empty file all refuse");
        check(endpointBase(" 5000 | " + epTok + " | 0badf00d \r\n", "0badf00d") != null,
                "endpoint: whitespace around the fields is tolerated");
        check("0badf00d".equals(nonceArg("0badf00d")) && nonceArg("0BADF00D") == null
                && nonceArg("") == null && nonceArg("0badf00d9") == null && nonceArg(null) == null,
                "nonce arg: exactly 8 lowercase hex, else none");
        // row (f) law 3: the plan lands whole (no .tmp survives) and the cancel marker sits beside it
        Path facePlan = tmp.resolve("cc-join-plan-1.txt");
        writePlanAtomically(facePlan, "host=h\r\nport=1\r\n");
        check("host=h\r\nport=1\r\n".equals(
                new String(Files.readAllBytes(facePlan), StandardCharsets.US_ASCII))
                && !Files.exists(planTmp(facePlan)),
                "plan: written atomically, no .tmp left behind");
        writePlanAtomically(facePlan, "host=h2\r\n");
        check("host=h2\r\n".equals(new String(Files.readAllBytes(facePlan), StandardCharsets.US_ASCII)),
                "plan: a second write replaces the first");
        // A move that FAILS (the target is a non-empty
        // directory) throws AND leaves no .tmp - a plan's .tmp holds the password and the cid
        Path blockedPlan = tmp.resolve("cc-join-plan-2.txt");
        Files.createDirectories(blockedPlan);
        Files.write(blockedPlan.resolve("occupant"), new byte[] {1});
        boolean threw = false;
        try {
            writePlanAtomically(blockedPlan, "host=h\r\npassword=never-left-1\r\n");
        } catch (IOException expected) {
            threw = true;
        }
        check(threw && !Files.exists(planTmp(blockedPlan)),
                "plan: a failed move throws and deletes the .tmp (flips if the cleanup is dropped - the password stays"
                        + " behind in <plan>.tmp)");
        check("cc-join-plan-1.txt.cancel".equals(cancelMarker(facePlan).getFileName().toString())
                && cancelMarker(facePlan).getParent().equals(facePlan.getParent()),
                "plan: the cancel marker is <plan>.cancel beside the plan");
        check("cc-join-plan-1.txt.alive".equals(launcherAliveMarker(facePlan).getFileName().toString())
                && launcherAliveMarker(facePlan).getParent().equals(facePlan.getParent()),
                "plan: the launcher tick is <plan>.alive beside the plan (the bat's every-second rewrite)");
        writeCancelMarker(facePlan);
        check(Files.isRegularFile(cancelMarker(facePlan)) && Files.size(cancelMarker(facePlan)) == 0,
                "plan: the cancel marker is written empty");
        // row (f) law 4: the face says the whole flow; "helper" is gone from every string
        check(launchFacePurpose(true).contains("This window opens")
                && launchFacePurpose(false).contains("Open editor below opens")
                && launchFacePurpose(true).startsWith("Starsector is starting.")
                && LAUNCH_FACE_LIVE.startsWith("Starsector is running.")
                && LAUNCH_FACE_LIVE.contains("Open editor below opens"),
                "face: asked vs plain wording, the live line");
        check(!(launchFacePurpose(true) + launchFacePurpose(false) + LAUNCH_FACE_HINT
                + LAUNCH_FACE_LIVE + WINDOW_TITLE).toLowerCase().contains("helper")
                && LAUNCH_FACE_HINT.contains("Submit") && LAUNCH_FACE_HINT.contains("respawn"),
                "face: no 'helper' anywhere; the hint names the Submit path");
        // The hint + the title status say the newest save flies; the old
        // "under the ship id you picked" / "re-select the mission" law is gone
        check(LAUNCH_FACE_HINT.contains("newest save flies")
                && LAUNCH_FACE_HINT.contains("Multiplayer Test")
                && !LAUNCH_FACE_HINT.contains("re-select")
                && !LAUNCH_FACE_HINT.contains("ship id you picked"),
                "face: the hint says a saved build is the next spawn");
        check(launchFacePurpose(true).endsWith(LAUNCH_FACE_JOIN) && LAUNCH_FACE_LIVE.endsWith(LAUNCH_FACE_JOIN)
                && LAUNCH_FACE_JOIN.contains("To join the match, select Multiplayer Test"),
                "face: both purpose lines end with how to enter the match (D1)");
        check(companionStatusLine("TITLE", "none").contains("newest save flies")
                && companionStatusLine("TITLE", "none").contains("Multiplayer Test")
                && !companionStatusLine("COMBAT", "live").contains("newest save flies"),
                "status line: the title names the save-then-select path; a match does not");
        // the companion's state law (the Swing shell only renders it)
        check("live".equals(companionVerdict(true, 0, 0, 0L)), "companion: live on first answer");
        check("waiting".equals(companionVerdict(false, 0, 0, 5000L)),
                "companion: waiting before any answer");
        check("waiting".equals(companionVerdict(false, 3, COMPANION_MISSES_TO_EXIT - 1, 0L)),
                "companion: a ping blip after live is a wait, not an exit");
        check("exit".equals(companionVerdict(false, 3, COMPANION_MISSES_TO_EXIT, 0L)),
                "companion: the game closed (misses hit the cap) = exit");
        check("exit".equals(companionVerdict(false, 0, 0, COMPANION_NEVER_LIVE_EXIT_MS)),
                "companion: never answered for 30 min = exit");
        check("waiting".equals(companionVerdict(false, 0, 99, COMPANION_NEVER_LIVE_EXIT_MS - 1)),
                "companion: misses without a prior live answer never exit early");
        // the companion's status line + the narrow ping-body reader (iteration 5 row (6))
        String pingBody = "{\"ok\":true,\"state\":\"COMBAT\",\"thread\":\"cc-editor-http-1\","
                + "\"uptimeMs\":1234,\"defaultTier\":\"STANDARD\",\"tierSource\":\"default\","
                + "\"gate\":{\"ok\":1},\"session\":\"live\"}";
        check("COMBAT".equals(pingField(pingBody, "state")), "pingField: state");
        check("live".equals(pingField(pingBody, "session")), "pingField: session");
        check(pingField(pingBody, "uptimeMs") == null, "pingField: a number is not a word");
        check(pingField(pingBody, "missing") == null, "pingField: absent key");
        check(pingField("{\"state\":\"TI\\\"TLE\"}", "state") == null,
                "pingField: an escaped quote in the value yields null");
        check(pingField("{\"state\":\"<b>x</b>\"}", "state") == null,
                "pingField: non-word characters yield null");
        check(pingField(null, "state") == null, "pingField: null body");
        StringBuilder fat = new StringBuilder("{\"state\":\"TITLE\"");
        while (fat.length() <= PING_BODY_MAX) {
            fat.append(",\"pad\":\"x\"");
        }
        check(pingField(fat.append('}').toString(), "state") == null,
                "pingField: an oversized body is refused whole");
        check(companionStatusLine("TITLE", "none").startsWith("Starsector: at the title screen"),
                "status line: title");
        check(companionStatusLine("COMBAT", "live").contains("in a match")
                && companionStatusLine("COMBAT", "live").contains("respawn"),
                "status line: a match names the Submit path");
        check(companionStatusLine("COMBAT", "none").contains("no multiplayer session"),
                "status line: combat without a session is not a match");
        check(companionStatusLine("CAMPAIGN", "none").contains("campaign"),
                "status line: campaign");
        check(companionStatusLine(null, null).equals("Starsector: running - the editor is ready."),
                "status line: unknown state = neutral wording");
        check(companionStatusLine("unknown", "live").contains("in a match"),
                "status line: a live session outranks an unknown state");
        check(companionWaitingLine(0L).contains("(0:00)"), "waiting line: zero clock");
        check(companionWaitingLine(65_000L).contains("(1:05)"), "waiting line: m:ss padding");
        check(companionWaitingLine(-5L).contains("(0:00)"), "waiting line: never negative");

        // REDACTED preflight (password-only; the descriptor keeps its
        // seat.N.token rows — the codec REQUIRES them, token MODE is untouched wire format).
        // AUTO-seat semantics: fires only when EVERY pilot-seat token is
        // redacted — a single real token means the token lane can still admit somewhere.
        MatchDescriptor allRedactedD = JoinLaunchPlan.parseDescriptor(SELFTEST_DESCRIPTOR
                .replace("seat.0.token=00112233445566778899aabbccddeeff",
                        "seat.0.token=REDACTED-seat1")
                .replace("seat.1.token=ffeeddccbbaa99887766554433221100",
                        "seat.1.token=REDACTED-seat2"));
        check(JoinLaunchPlan.needsCredentialEntry(allRedactedD, ""),
                "all seats redacted + no password -> preflight fires");
        check(!JoinLaunchPlan.needsCredentialEntry(allRedactedD, "match-pass-1"),
                "all seats redacted + match password -> preflight clear (1239 primary lane)");
        MatchDescriptor mixedD = JoinLaunchPlan.parseDescriptor(SELFTEST_DESCRIPTOR
                .replace("seat.1.token=ffeeddccbbaa99887766554433221100",
                        "seat.1.token=REDACTED-seat2"));
        check(!JoinLaunchPlan.needsCredentialEntry(mixedD, ""),
                "one real token left -> preflight clear (66 AUTO: any seat can admit)");
        check(!JoinLaunchPlan.needsCredentialEntry(
                JoinLaunchPlan.parseDescriptor(SELFTEST_DESCRIPTOR), ""),
                "real-token descriptor -> preflight clear (keep-tokens watch kit)");

        // match-password validation table (the mint charset)
        check(JoinLaunchPlan.validatePassword("") == null, "empty password accepted (no lane)");
        check(JoinLaunchPlan.validatePassword("match-pass-1") == null,
                "charset password accepted");
        check(JoinLaunchPlan.validatePassword("a".repeat(64)) == null,
                "64-char password accepted");
        check(JoinLaunchPlan.validatePassword("a".repeat(65)) != null, ">64 password rejected");
        check(JoinLaunchPlan.validatePassword("has space") != null,
                "spaced password rejected");
        check(JoinLaunchPlan.validatePassword("under_score") != null,
                "non-charset password rejected");
        // The entropy floor + the no-leading-dash law
        String shortRefusal = JoinLaunchPlan.validatePassword("abc-123");
        check(shortRefusal != null && shortRefusal.contains("8 to 64"),
                "7-char password refused, the message names 8 to 64");
        check(JoinLaunchPlan.validatePassword("abc-1234") == null, "8-char password accepted");
        check(JoinLaunchPlan.validatePassword("-abc-1234") != null,
                "leading-dash password refused (server law [A-Za-z0-9][A-Za-z0-9-]{7,63})");
        check("pass-1".equals(JoinLaunchPlan.sanitizePassword(" \"pass-1\" ")),
                "password quote-strip + trim");

        // client-instance-id mint: 32-hex, unique per mint
        String cidA = JoinLaunchPlan.mintClientId();
        String cidB = JoinLaunchPlan.mintClientId();
        check(cidA.matches("[0-9a-f]{32}"), "minted cid is 32-hex");
        check(!cidA.equals(cidB), "two mints differ");

        // validation table
        check(JoinLaunchPlan.validate("", "7777") != null, "blank host rejected");
        check(JoinLaunchPlan.validate("a b", "7777") != null, "spaced host rejected");
        check(JoinLaunchPlan.validate("1.2.3.4\u0001x", "7777") != null,
                "host with an interior control character rejected at the gate, before any file write");
        check(JoinLaunchPlan.validate("h", "") != null, "blank port rejected");
        check(JoinLaunchPlan.validate("h", "x") != null, "non-numeric port rejected");
        check(JoinLaunchPlan.validate("h", "0") != null, "port 0 rejected");
        check(JoinLaunchPlan.validate("h", "65536") != null, "port 65536 rejected");
        check(JoinLaunchPlan.validate("h", "+7777") != null
                        && JoinLaunchPlan.validate("h", "７777") != null,
                "port with a sign or a non-ASCII digit rejected at the gate (parseInt admits both, the"
                        + " bat refuses them after the settings save)");
        check(JoinLaunchPlan.validate("10.2.0.2", "65535") == null, "valid pair accepted");
        // The host gate IS the bat's :vguard law (charset, first character,
        // the 256 cap) - a host the bat would refuse is refused here, before the settings save and the mods stage.
        check(JoinLaunchPlan.validate("1.2.3.4" + (char) 0x200B, "7777") != null
                && JoinLaunchPlan.validate("1.2.3.4" + (char) 0xA0, "7777") != null
                && JoinLaunchPlan.validate("a/b", "7777") != null
                && JoinLaunchPlan.validate("[fe80::1]", "7777") != null
                && JoinLaunchPlan.validate(":lead", "7777") != null
                && JoinLaunchPlan.validate("-lead", "7777") != null
                && JoinLaunchPlan.validate("h".repeat(JoinLaunchPlan.HOST_MAX + 1), "7777") != null,
                "host outside the bat's :vguard law rejected at the gate: zero-width space, no-break space, slash,"
                        + " bracket, leading colon / dash, 257 characters (flips if validate goes back to refusing"
                        + " only whitespace and control characters - the bat then refuses AFTER the settings save)");
        check(JoinLaunchPlan.validate("my-host.example.org", "7777") == null
                && JoinLaunchPlan.validate("fe80::1", "7777") == null
                && JoinLaunchPlan.validate("h_1", "7777") == null
                && JoinLaunchPlan.validate("h".repeat(JoinLaunchPlan.HOST_MAX), "7777") == null,
                "host inside the bat's :vguard law accepted: a DNS name, a colon form, an underscore, 256 characters"
                        + " (flips if the gate is tighter than the bat's)");

        // name sanitize: quotes stripped, capped at 24
        String longName = JoinLaunchPlan.sanitizeName("\"123456789012345678901234567890\"");
        check(longName.length() == 24 && !longName.contains("\""), "name sanitize");

        // Brick autofill: ip:port:password from the host GUI's Copy join brick
        String[] brick = JoinLaunchPlan.parseBrick(" \"203.0.113.9:51820:Fri-day-42\" ");
        check(brick != null && "203.0.113.9".equals(brick[0]) && "51820".equals(brick[1])
                && "Fri-day-42".equals(brick[2]), "brick parse + quote-strip/trim");
        check(JoinLaunchPlan.parseBrick("203.0.113.9:51820") == null, "2-part brick refused");
        check(JoinLaunchPlan.parseBrick("a:b:c:d") == null, "4-part brick refused");
        check(JoinLaunchPlan.parseBrick("203.0.113.9::pw") == null, "blank middle refused");
        check(JoinLaunchPlan.parseBrick("") == null && JoinLaunchPlan.parseBrick(null) == null
                && JoinLaunchPlan.parseBrick("   ") == null, "empty/null/blank brick refused");
        check(JoinLaunchPlan.parseBrick("not a brick") == null, "garbage brick refused");
        check(JoinLaunchPlan.parseBrick("1.2.3.4:51820:has space") == null,
                "inner-whitespace part refused");

        selftestShipPick(tmp);
        selftestVoice(tmp);
        selftestLocalCustoms(tmp);
        selftestCurrentBuild(tmp);
        selftestReservedDeviceStems();
        selftestBrowserUi();
        selftestModStage();
        selftestSwingPlainText();
        selftestShipChoiceNames(); //
        selftestShipChoiceHulls(tmp); //
        selftestModStageLazy(tmp);
        selftestModStageDigestOnce(tmp);
        selftestVoiceCore();
        selftestLaunchGates(tmp);
        selftestRefreshMatch(tmp);
        selftestOneAdopt();
        selftestKitDiffersOneCopy();
        selftestTeamPick(tmp);
        selftestTeamSides(tmp);
        JoinGuiProbeSelftest.run();
    }

    /**
     * Row {@code team-pick}: the window's Team choice (0 = the server picks, 1 = South, 2 = North) through the three
     * layers it crosses before the bat — the plan writer (team 0 = NO row, the plan byte-identical to the pre-team
     * shape; 1 / 2 = a {@code team=N} row just before {@code ship=}; 3 = a refusal naming the row, never the value),
     * the launch gate (team 3 = the {@code team} gate, ahead of the match gate; 0..2 pass it) and the api/launch form
     * field (absent / empty = 0, one digit 0..2, anything else = the unlawful marker the gate refuses).
     *
     */
    private static void selftestTeamPick(Path tmp) {
        String row = "team-pick";
        String noTeam = JoinLaunchPlan.planFileContent("h", "1", "", "", "", true, "wolf_Assault");
        check(noTeam.equals(JoinLaunchPlan.planFileContent("h", "1", "", "", "", true, "wolf_Assault", 0))
                && noTeam.equals("host=h\r\nport=1\r\nname=\r\npassword=\r\ncid=\r\nlogs=on\r\nship=wolf_Assault\r\n"),
                row + ": team 0 writes NO team row - the plan stays byte-identical to the pre-team shape (flips if a"
                        + " team=0 row rides, or the seven-arg plan stops meaning 'the server picks')");
        for (int team = 1; team <= 2; team++) {
            String want = "host=h\r\nport=1\r\nname=\r\npassword=\r\ncid=\r\nlogs=on\r\nteam=" + team
                    + "\r\nship=wolf_Assault\r\n";
            check(want.equals(JoinLaunchPlan.planFileContent("h", "1", "", "", "", true, "wolf_Assault", team)),
                    row + ": team " + team + " writes team=" + team + " just before ship= (flips if the row drops,"
                            + " moves, carries another value, or ship= stops being the last row)");
        }
        String refusal = null;
        try {
            JoinLaunchPlan.planFileContent("h", "1", "", "", "", true, "wolf_Assault", 3);
        } catch (IllegalArgumentException expected) {
            refusal = String.valueOf(expected.getMessage());
        }
        check(refusal != null && refusal.contains("team") && !refusal.contains("3"),
                row + ": team 3 refuses the plan write naming the team row, never the value (flips if 3 writes a"
                        + " row the bat would refuse later, or the refusal echoes the value)");
        JoinLaunchGates.ProbeOutcome none = JoinLaunchGates.ProbeOutcome.NONE;
        JoinLaunchGates.Verdict v = JoinLaunchGates.check(new JoinLaunchGates.Form("1.2.3.4", "7777", "", "ok-1pass",
                "", "garbage", true, 3), tmp, none);
        check(v.ready == null && v.refusal != null && "team".equals(v.refusal.gate)
                && JoinLaunchGates.TITLE_TEAM.equals(v.refusal.title)
                && JoinLaunchGates.MSG_TEAM.equals(v.refusal.message),
                row + ": team 3 is the team gate's refusal, ahead of the match gate (flips if the gate drops, moves"
                        + " after the match gate, or lets 3 through)");
        for (int team = 0; team <= 2; team++) {
            v = JoinLaunchGates.check(new JoinLaunchGates.Form("1.2.3.4", "7777", "", "ok-1pass", "", "garbage",
                    true, team), tmp, none);
            check(v.refusal != null && "document".equals(v.refusal.gate),
                    row + ": team " + team + " passes the team gate (the garbled document is the next refusal; flips"
                            + " if the gate refuses a lawful pick)");
        }
        String[][] fields = {
            {"", "0"}, {"team=", "0"}, {"name=x", "0"}, {"team=0", "0"}, {"name=x&team=1", "1"}, {"team=2", "2"},
            {"team=3", "-1"}, {"team=02", "-1"}, {"team=-1", "-1"}, {"team=%31", "1"}, {"team=%33", "-1"},
            {"team=x", "-1"},
        };
        for (String[] f : fields) {
            int got = JoinRoutes.teamField(f[0].getBytes(StandardCharsets.UTF_8));
            check(got == Integer.parseInt(f[1]) && (got != -1 || JoinRoutes.TEAM_UNLAWFUL == -1),
                    row + ": the api/launch field '" + f[0] + "' reads " + f[1] + " (got " + got + "; flips if the"
                            + " field accepts a value outside one digit 0..2, or an absent field stops meaning 0)");
        }
    }

    /**
     * Row {@code team-sides}: the probe reply's {@code sides} — the one input the browser page shows its Team choice
     * under ({@code sides == 2}) — through the REAL {@link JoinBrowserUi#probe} over a temp kit folder and a fake door
     * on the {@link JoinBrowserUi#doorCall} seam (restored in {@code finally}). A cached current match seating pilots
     * on one side = 1, the cached two-sided 1v1 = 2, a staged two-sided server document = 2, a staged one-sided one =
     * 1. Fixture values only — no message carries a print, a password or document text.
     *
     */
    private static void selftestTeamSides(Path tmp) throws IOException {
        String row = "team-sides";
        String oneSided = SELFTEST_DESCRIPTOR.replace("seat.1.side=1", "seat.1.side=0")
                .replace("match.id=1v1-selftest0000000", "match.id=1v1-teamsides0000one");
        check(!MatchDescriptor.seatsPilotOnBothSides(JoinLaunchPlan.parseDescriptor(oneSided))
                && MatchDescriptor.seatsPilotOnBothSides(JoinLaunchPlan.parseDescriptor(SELFTEST_DESCRIPTOR)),
                "SETUP-" + row + ": the one-sided fixture decodes one-sided and the 1v1 two-sided");
        check(sidesOf(row + " (cached one-sided)", sidesKit(tmp, "cached-one", oneSided), null).equals("1"),
                row + ": a cached current match with every pilot on one side answers sides 1 (flips if the cache"
                        + " path ignores the file and reads two-sided)");
        check(sidesOf(row + " (cached 1v1)", sidesKit(tmp, "cached-two", SELFTEST_DESCRIPTOR), null).equals("2"),
                row + ": the cached current 1v1 answers sides 2 (flips if the cache path never reads the file, or"
                        + " reads it one-sided)");
        check(sidesOf(row + " (staged 1v1)", sidesKit(tmp, "staged-two", null),
                redactedSides(SELFTEST_DESCRIPTOR, "1v1-teamsides0stagetwo")).equals("2"),
                row + ": a staged two-sided server document answers sides 2 (flips if the stage's sides are dropped"
                        + " or a fresh folder reads one-sided)");
        check(sidesOf(row + " (staged one-sided)", sidesKit(tmp, "staged-one", null),
                redactedSides(oneSided, "1v1-teamsides0stageone")).equals("1"),
                row + ": a staged one-sided server document answers sides 1 (flips if the stage reads two-sided"
                        + " whatever it holds)");
    }

    /** The selftest descriptor {@code text} as a server sends it (strict seat-token placeholders), id = {@code id}.
     * */
    private static String redactedSides(String text, String id) {
        return text.replace("seat.0.token=00112233445566778899aabbccddeeff", "seat.0.token=REDACTED-seat1")
                .replace("seat.1.token=ffeeddccbbaa99887766554433221100", "seat.1.token=REDACTED-seat2")
                .replaceFirst("match\\.id=[^\\n]*", "match.id=" + id);
    }

    /** A temp kit extract: the file list, its one data file, a stand-in jar, and {@code match.txt} = {@code cached}
     *  (null = none, the folder a first join holds). */
    private static Path sidesKit(Path tmp, String name, String cached) throws IOException {
        Path modDir = tmp.resolve("team-sides-" + name).resolve("install").resolve("mods").resolve("cc");
        Files.createDirectories(modDir.resolve("jars"));
        Files.createDirectories(modDir.resolve("data"));
        Files.write(modDir.resolve("kit.files"), "file=data/x.csv\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(modDir.resolve("data").resolve("x.csv"), "x\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(modDir.resolve("jars").resolve("cc-client-mod.jar"), new byte[] {1, 2, 3});
        if (cached != null) {
            Files.write(modDir.resolve("match.txt"), cached.getBytes(StandardCharsets.US_ASCII));
        }
        return modDir;
    }

    /**
     * One served probe of the REAL window over {@code modDir}: the fake door serves a one-ship listing, plus
     * {@code staged} as the server's document when the print presents no match ({@code null} = never a document).
     * Returns the reply's {@code sides} value as text; a probe that is not served with {@code docOk} fails SETUP.
     *
     */
    private static String sidesOf(String what, Path modDir, String staged) {
        DoorProbe.Result listing = DoorProbe.Result.served(List.of("selftest_ship"), 0, 1, null);
        JoinBrowserUi.DoorCall real = JoinBrowserUi.doorCall;
        JoinBrowserUi.doorCall = (host, port, password, clientId, fingerprint) -> staged != null
                && ContentFingerprints.MATCH_NONE.equals(ContentFingerprints.componentOrNull(fingerprint, "match"))
                ? listing.withDocument(staged, true, null) : listing;
        try {
            JoinBrowserUi ui = JoinBrowserUi.forSelftest(modDir, new JoinSettings("", "", "", "", "", true, "", ""));
            String reply = ui.probe("127.0.0.1", "7777", "pw-1pass");
            check(reply.contains("\"status\":\"served\"") && reply.contains("\"docOk\":true"),
                    "SETUP-" + what + ": the probe is served with docOk (the cache is current or the stage landed)");
            int at = reply.indexOf("\"sides\":");
            check(at >= 0, what + ": the served reply carries sides (flips if the probe reply drops the field)");
            int from = at + "\"sides\":".length();
            int to = from;
            while (to < reply.length() && Character.isDigit(reply.charAt(to))) {
                to++;
            }
            return reply.substring(from, to);
        } finally {
            JoinBrowserUi.doorCall = real;
        }
    }

    /**
     * Rows {@code refresh-current}, {@code refresh-updated} and {@code refresh-refused-or-silent}: the headless match
     * refresh ({@link JoinGui#runRefreshMatch}) driven through its door seam ({@link JoinGui#refreshDoor}) over a
     * temp kit folder, with a temp settings file + cid master ({@link JoinGui#refreshSettingsPath}); both seams are
     * restored in {@code finally}. "Nothing written" = the digest of every file under the row's root, before vs
     * after. Fixture values only — no message carries a print, a password or document text.
     *
     *
     */
    private static void selftestRefreshMatch(Path tmp) throws IOException {
        Path rowRoot = tmp.resolve("refresh-match");
        Path modDir = rowRoot.resolve("install").resolve("mods").resolve("CC_Client");
        Files.createDirectories(modDir.resolve("jars"));
        Files.write(modDir.resolve("jars").resolve("cc-client-mod.jar"), "not-a-jar".getBytes(StandardCharsets.US_ASCII));
        Files.write(modDir.resolve("mod_info.json"), "{\"id\":\"cc_client\"}".getBytes(StandardCharsets.US_ASCII));
        Files.write(modDir.resolve("kit.files"), "file=mod_info.json\n".getBytes(StandardCharsets.US_ASCII));
        String cachedId = "1v1-selftest0000000";
        Files.write(modDir.resolve("match.txt"), refreshDocument(cachedId).getBytes(StandardCharsets.US_ASCII));
        Path settings = rowRoot.resolve("settings").resolve("join-settings.properties");
        new JoinSettings("127.0.0.1", "7777", "", "pw-1pass", "", true, "", "127.0.0.1:7777").save(settings);
        JoinSettings.hostCid(settings.resolveSibling(JoinSettings.CID_MASTER_FILE), "127.0.0.1", 7777, true);
        List<String> prints = new ArrayList<>();
        DoorProbe.Result[] reply = new DoorProbe.Result[1];
        JoinBrowserUi.DoorCall realDoor = JoinGui.refreshDoor;
        Path realSettings = JoinGui.refreshSettingsPath;
        JoinGui.refreshDoor = (host, port, password, clientId, fingerprint) -> {
            prints.add(fingerprint);
            return reply[0];
        };
        JoinGui.refreshSettingsPath = settings;
        try {
            DoorProbe.Result listing = DoorProbe.Result.served(List.of("selftest_ship"), 0, 1);
            reply[0] = listing;
            TreeMap<String, String> before = treeDigest(rowRoot);
            int code = JoinGui.runRefreshMatch("127.0.0.1", "7777", modDir);
            check(code == JoinGui.REFRESH_CURRENT && prints.size() == 1 && prints.get(0) != null
                    && prints.get(0).endsWith(";match=" + cachedId) && before.equals(treeDigest(rowRoot)),
                    "refresh-current: a listing with no document for the presented cached id = exit 0 after ONE probe,"
                            + " nothing written (got exit " + code + "; flips if the refresh bypasses its door seam,"
                            + " presents another id, or writes on a current cache)");
            String newId = "1v1-selftest0000001";
            String served = refreshDocument(newId);
            reply[0] = listing.withDocument(served, true, null);
            code = JoinGui.runRefreshMatch("127.0.0.1", "7777", modDir);
            TreeMap<String, String> after = treeDigest(rowRoot);
            String matchKey = "install/mods/CC_Client/match.txt";
            TreeMap<String, String> beforeRest = new TreeMap<>(before);
            TreeMap<String, String> afterRest = new TreeMap<>(after);
            beforeRest.remove(matchKey);
            afterRest.remove(matchKey);
            check(code == JoinGui.REFRESH_UPDATED && prints.size() == 2
                    && Arrays.equals(Files.readAllBytes(modDir.resolve("match.txt")),
                            JoinLaunchPlan.canonicalBytes(JoinLaunchPlan.parseDescriptor(served)))
                    && after.keySet().equals(before.keySet()) && afterRest.equals(beforeRest),
                    "refresh-updated: a lawful served document = exit 10, match.txt = its canonical bytes and no other"
                            + " file added or changed (got exit " + code + "; flips if the refresh skips the one writer,"
                            + " writes the raw text, or touches settings / plan / index files)");
            TreeMap<String, String> held = treeDigest(rowRoot);
            reply[0] = listing.withDocument(refreshDocument(ContentFingerprints.MATCH_NONE), true, null);
            int refusedDoc = JoinGui.runRefreshMatch("127.0.0.1", "7777", modDir);
            reply[0] = DoorProbe.Result.rejected("banned");
            int doorRefused = JoinGui.runRefreshMatch("127.0.0.1", "7777", modDir);
            reply[0] = DoorProbe.Result.failed("selftest: no reply");
            int silent = JoinGui.runRefreshMatch("127.0.0.1", "7777", modDir);
            check(refusedDoc == JoinGui.REFRESH_REFUSED && doorRefused == JoinGui.REFRESH_REFUSED
                    && silent == JoinGui.REFRESH_NO_ANSWER && prints.size() == 5 && held.equals(treeDigest(rowRoot)),
                    "refresh-refused-or-silent: a document the laws refuse = exit 1 with no write, a door refusal = 1,"
                            + " no answer = 2 (got " + refusedDoc + "/" + doorRefused + "/" + silent + "; flips if an"
                            + " unlawful document is written, or a refusal and silence share an exit code)");
            Path noPassword = rowRoot.resolve("no-password").resolve("join-settings.properties");
            JoinGui.refreshSettingsPath = noPassword;
            int noPw = JoinGui.runRefreshMatch("127.0.0.1", "7777", modDir);
            Path noMaster = rowRoot.resolve("no-master").resolve("join-settings.properties");
            new JoinSettings("127.0.0.1", "7777", "", "pw-1pass", "", true, "", "127.0.0.1:7777").save(noMaster);
            JoinGui.refreshSettingsPath = noMaster;
            int noCid = JoinGui.runRefreshMatch("127.0.0.1", "7777", modDir);
            check(noPw == JoinGui.REFRESH_REFUSED && noCid == JoinGui.REFRESH_REFUSED && prints.size() == 5
                    && !Files.exists(noMaster.resolveSibling(JoinSettings.CID_MASTER_FILE)),
                    "refresh-refused-or-silent: no remembered password, or no seat identity = exit 1 BEFORE any door call,"
                            + " and no master minted (got " + noPw + "/" + noCid + ", door calls " + prints.size()
                            + " of 5; flips if the refresh probes without a password, or mints an id)");
        } finally {
            JoinGui.refreshDoor = realDoor;
            JoinGui.refreshSettingsPath = realSettings;
        }
    }

    /**
     * Row {@code one-adopt}: the shared adopt ({@link JoinLaunchGates#adopt}) on fixture probe replies — a lawful
     * document, one the laws refuse, undecodable text, frames that never made a whole document, and no document.
     *
     *
     */
    private static void selftestOneAdopt() {
        DoorProbe.Result listing = DoorProbe.Result.served(List.of("selftest_ship"), 0, 1);
        String lawful = refreshDocument("1v1-selftest0000000");
        JoinLaunchGates.Adopted fetched = JoinLaunchGates.adopt(listing.withDocument(lawful, true, null));
        check(fetched.kind == JoinLaunchGates.Adopted.Kind.FETCHED && fetched.descriptor != null
                && "1v1-selftest0000000".equals(fetched.descriptor.matchId()) && lawful.equals(fetched.text)
                && "Match fetched from the server: 1v1, 2 seats.".equals(fetched.line) && fetched.refusal == null,
                "one-adopt: a lawful served document = FETCHED with its descriptor, the served text and the fetched line"
                        + " (flips if the adopt skips the receive law, carries another text, or the fetched wording drifts)");
        JoinLaunchGates.Adopted refused = JoinLaunchGates.adopt(
                listing.withDocument(refreshDocument(ContentFingerprints.MATCH_NONE), true, null));
        String r7 = MatchDocumentLaws.REASON_OUT_OF_RANGE + " match.id";
        check(refused.kind == JoinLaunchGates.Adopted.Kind.REFUSED && refused.descriptor == null && refused.text == null
                && r7.equals(refused.refusal) && JoinLaunchGates.refusedLine(r7).equals(refused.line),
                "one-adopt: a document whose match.id is none = REFUSED, no descriptor, the refused line naming OUR"
                        + " reason + field (flips if a refused document is adopted, or its line drops the reason)");
        JoinLaunchGates.Adopted garbled = JoinLaunchGates.adopt(listing.withDocument("garbage ccmatch", true, null));
        check(garbled.kind == JoinLaunchGates.Adopted.Kind.REFUSED && garbled.descriptor == null
                && garbled.refusal != null && garbled.refusal.startsWith(MatchDocumentLaws.REASON_MALFORMED + " ")
                && !garbled.refusal.contains("garbage") && !garbled.line.contains("garbage"),
                "one-adopt: undecodable text = REFUSED as malformed + OUR field, never echoed (flips if a codec message"
                        + " or the text reaches the refusal or the line)");
        JoinLaunchGates.Adopted missing = JoinLaunchGates.adopt(
                listing.withDocument(null, true, "match_doc_incomplete chunks"));
        check(missing.kind == JoinLaunchGates.Adopted.Kind.MISSING && missing.descriptor == null
                && JoinLaunchGates.MSG_DOC_MISSING.equals(missing.line),
                "one-adopt: frames seen with no whole document = MISSING with the did-not-arrive line (flips if a partial"
                        + " document reads as no document, which would keep an older stage)");
        JoinLaunchGates.Adopted none = JoinLaunchGates.adopt(listing);
        check(none.kind == JoinLaunchGates.Adopted.Kind.NONE && none.descriptor == null && none.line.isEmpty(),
                "one-adopt: a listing with no document = NONE with an empty line (flips if a plain listing clears or"
                        + " replaces a held stage)");
    }

    /**
     * Row {@code w3-one-copy}: the door's content-print refusal reads ONE text everywhere — the window constant, the
     * door probe's reject text and the join delegate are the same string, a refused probe's Swing status equals the
     * browser's composed line, and that text sends the player to the host's kit with Refresh (the join window's
     * button name), never to a match paste box that no longer exists.
     *
     *
     */
    private static void selftestKitDiffersOneCopy() {
        String door = DoorProbe.rejectMessage("content_fingerprint_mismatch");
        DoorProbe.Result refused = DoorProbe.Result.rejected("content_fingerprint_mismatch");
        check(JoinLaunchGates.MSG_KIT_DIFFERS.equals(door) && door.equals(JoinProbe.rejectMessage(
                "content_fingerprint_mismatch")) && refused.statusLine().equals(JoinBrowserUi.windowLine(refused)),
                "w3-one-copy: the window constant, the door's reject text and both windows' lines are one string (flips"
                        + " if either copy is re-worded alone)");
        check(door.contains("is not the host's kit") && door.contains("press Refresh")
                && !door.contains("Refresh list")
                && !door.contains("credential") && !door.toLowerCase(java.util.Locale.ROOT).contains("paste"),
                "w3-one-copy: the text names the host's kit and Refresh, no match paste (flips if the dead paste"
                        + " hint returns or the old button name 'Refresh list' comes back)");
    }

    /** The selftest descriptor under {@code matchId}, its seat tokens as the strict placeholders the receive law
     *  requires. */
    private static String refreshDocument(String matchId) {
        return SELFTEST_DESCRIPTOR.replace("match.id=1v1-selftest0000000", "match.id=" + matchId)
                .replace("seat.0.token=00112233445566778899aabbccddeeff", "seat.0.token=REDACTED-seat1")
                .replace("seat.1.token=ffeeddccbbaa99887766554433221100", "seat.1.token=REDACTED-seat2");
    }

    /** Every regular file under {@code root} (slash-separated relative path) with its md5: the refresh rows'
     *  "nothing else written" witness. */
    private static TreeMap<String, String> treeDigest(Path root) throws IOException {
        TreeMap<String, String> out = new TreeMap<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                out.put(root.relativize(f).toString().replace('\\', '/'),
                        ContentFingerprints.md5Hex(Files.readAllBytes(f)));
            }
        }
        return out;
    }

    /** The mods-ladder joiner half, game-free — the engine's version recipe, the
     *  mod_info reader (comments, object versions), the diff wording, the cmd-argument law. */
    private static void selftestModStage() {
        Object parsed = JoinModStage.MiniJson.parse(JoinModStage.stripHashComments(
                "{\n  \"id\": \"lw_lazylib\", # the id\n  'name': 'LazyLib',\n  version: {\"major\": 2, \"minor\": \"8\"},\n"
                        + "  \"jars\": [\"jars/LazyLib.jar\",], // trailing comma\n  /* block */ \"utility\": true\n}"));
        check(parsed instanceof java.util.Map && "lw_lazylib".equals(((java.util.Map<?, ?>) parsed).get("id"))
                        && "LazyLib".equals(((java.util.Map<?, ?>) parsed).get("name"))
                        && ((java.util.Map<?, ?>) parsed).get("jars") instanceof List
                        && ((List<?>) ((java.util.Map<?, ?>) parsed).get("jars")).size() == 1,
                "mod_info reader: # // /* comments, single quotes, bare keys, trailing commas");
        check("2.8".equals(JoinModStage.versionOf(((java.util.Map<?, ?>) parsed).get("version"))),
                "version object major/minor -> 2.8 (numbers render without .0)");
        check("1.2.3".equals(JoinModStage.versionOf(java.util.Map.of("major", "1", "minor", "2", "patch", "3")))
                        && "1".equals(JoinModStage.versionOf(java.util.Map.of("major", "1")))
                        && "0.9.1a-RC8".equals(JoinModStage.versionOf("0.9.1a-RC8"))
                        && "".equals(JoinModStage.versionOf(null)),
                "version recipe = ModManager.VersionInfo.getString: string verbatim, object joined, absent = empty");
        check("{\"a\": \"x#y\"} \n".equals(JoinModStage.stripHashComments("{\"a\": \"x#y\"} # c\n")),
                "# inside a string survives; # outside comments to end of line (the engine keeps the blank before it)");
        JoinModStage.Scan empty = new JoinModStage.Scan();
        JoinModStage.Diff none = JoinModStage.diff(List.of(), 0, true, empty);
        check(none.ok && none.rows.isEmpty() && none.line.isEmpty(), "nothing published = nothing required");
        JoinModStage.Diff missing = JoinModStage.diff(
                List.of(new DoorProbe.ModRow("lw_lazylib", "2.8", 7L)), 1, true, empty);
        check(!missing.ok && missing.rows.size() == 1 && "missing".equals(missing.rows.get(0).status)
                        && missing.rows.get(0).line().contains("MISSING")
                        && missing.line.startsWith("Mods: the server runs 1 mod - 1 problem"),
                "a published mod the joiner lacks = a MISSING row and a failing verdict");
        JoinModStage.Diff partial = JoinModStage.diff(List.of(), 3, false, empty);
        check(!partial.ok && !partial.complete && partial.line.contains("list incomplete"),
                "a cut listing never passes the gate (Refresh)");
        check(JoinModStage.rowsJson(missing).startsWith("[{\"id\":\"lw_lazylib\"")
                        && JoinModStage.rowsJson(none).equals("[]"), "rows json shape");
        check(JoinModStage.stageableName("C:\\Games\\Starsector\\mods\\LazyLib 2.8") == null
                        && JoinModStage.stageableName("mods\\bad%name") != null
                        && JoinModStage.stageableName("mods\\a&b") != null,
                "cmd-argument law: spaces fine, % & refused");
        check(JoinModStage.isAbsoluteJar("C:/x.jar") && JoinModStage.isAbsoluteJar("/x.jar")
                        && !JoinModStage.isAbsoluteJar("jars/x.jar"), "the engine's jar-path rule");
        // An OPTIONAL row is never a launch problem; the required one still is
        JoinModStage.Diff mixed = JoinModStage.diff(List.of(new DoorProbe.ModRow("lw_lazylib", "3.0.0", 7L),
                new DoorProbe.ModRow("shaderLib", "1.12.1", 9L, true)), 2, true, empty);
        check(!mixed.ok && mixed.rows.size() == 2 && mixed.rows.get(1).optional
                        && "missing".equals(mixed.rows.get(1).status)
                        && mixed.rows.get(1).line().contains("optional - not installed")
                        && mixed.line.contains("(1 optional)") && mixed.line.contains("1 problem"),
                "an absent OPTIONAL mod is not a problem; the absent required one is");
        JoinModStage.Diff optionalOnly = JoinModStage.diff(
                List.of(new DoorProbe.ModRow("shaderLib", "1.12.1", 9L, true)), 1, true, empty);
        check(optionalOnly.ok && JoinModStage.rowsJson(optionalOnly).contains("\"optional\":true")
                        && JoinModStage.rowsJson(missing).contains("\"optional\":false"),
                "optional-only publish with nothing local = launchable; rows json carries the flag");
        check("cc-optional1\nshaderLib\n".equals(cc.starsector.server.runtime.ModSet.optionalIdsText(List.of("shaderLib")))
                        && cc.starsector.server.runtime.ModSet.parseOptionalIds("cc-optional1\nshaderLib\n").equals(List.of("shaderLib")),
                "the optional sidecar text the stage writes = what the game parses (ONE recipe)");
        // A published id that opens with the Swing HTML opener
        // stays raw DATA at the head of its MISSING line and of its own Mods-refusal line (the dialog renders it
        // plain: selftestSwingPlainText). Built like the rows above (ModRow direct, no token-law pass): the spaceless
        // id is what validateToken admits, the version label carries the attribute text after Row.line()'s space.
        // The gate runs before commit reads its Ready or any path, so the nulls make a reorder fail loud, not write.
        String markupId = "<html><img";
        JoinModStage.Diff markup = JoinModStage.diff(List.of(new DoorProbe.ModRow(markupId, "src=x>", 7L)), 1, true, empty);
        String markupLine = markup.rows.size() == 1 ? markup.rows.get(0).line() : "";
        check(!markup.ok && markup.rows.size() == 1 && JoinModStage.STATUS_MISSING.equals(markup.rows.get(0).status)
                        && markupLine.startsWith(markupId + " src=x>") && BasicHTML.isHTMLString(markupLine),
                "flips if the join GUI stops showing a server mod id as data (the MISSING line leads with the raw id)");
        JoinLaunchGates.Handoff markupGate = JoinLaunchGates.commit(null, null, null, null, null, true, null, markup);
        check(markupGate.refusal != null && JoinModStage.GATE.equals(markupGate.refusal.gate)
                        && markupGate.refusal.message.contains("\n" + markupLine + "\n"),
                "flips if the Mods refusal stops carrying the raw MISSING line as a dialog line of its own");
        // A served probe's default ship + mod diff reach ONLY a Launch
        // to the pair that probe asked (host case-blind, port numeric); any other pair = a never-probed Launch.
        JoinLaunchGates.Probed asked = new JoinLaunchGates.Probed("Example.org", 7777, "hound_Standard", markup);
        JoinLaunchGates.Ready samePair = new JoinLaunchGates.Ready("example.ORG", "07777", "n", "pw", "", null);
        JoinLaunchGates.Ready otherHost = new JoinLaunchGates.Ready("example.net", "7777", "n", "pw", "", null);
        JoinLaunchGates.Ready otherPort = new JoinLaunchGates.Ready("example.org", "7778", "n", "pw", "", null);
        check("hound_Standard".equals(asked.defaultFor(samePair)) && asked.modDiffFor(samePair) == markup,
                "probed P1: a Launch to the probed pair carries that probe's default + mod diff");
        check(asked.defaultFor(otherHost) == null && asked.modDiffFor(otherHost).ok
                        && asked.defaultFor(otherPort) == null && asked.modDiffFor(otherPort).ok
                        && JoinLaunchGates.Probed.NONE.defaultFor(samePair) == null
                        && JoinLaunchGates.Probed.NONE.modDiffFor(samePair).ok,
                "probed P2: host A's default ship / mod set rode a Launch to another host or port (flips if the"
                        + " window hands commit the last probe's values unkeyed)");
    }

    /**
     * The join window's dialogs show server text as DATA.
     * Builds a real {@code JOptionPane} around {@link SwingPlainText#message} (components only, no window is shown)
     * and walks its tree: no component carries an HTML view, the message keeps every char, the message is marked
     * {@code html.disable}; a marked label builds no HTML view on {@code setText}. Two controls prove the probe sees
     * an HTML view where one exists: the pre-fix String-message pane, and the label before it is marked.
     */
    private static void selftestSwingPlainText() {
        String text = "<html><img src=x>first\nsecond";
        List<JComponent> legacy = componentTree(new JOptionPane("<html><b>x</b>", JOptionPane.ERROR_MESSAGE));
        check(legacy.stream().anyMatch(c -> c.getClientProperty(BasicHTML.propertyKey) != null),
                "control: a String-message pane builds an HTML view for an opener-led line (the probe below sees it)");
        JComponent m = SwingPlainText.message(text);
        List<JComponent> tree = componentTree(new JOptionPane(m, JOptionPane.ERROR_MESSAGE));
        for (JComponent c : tree) {
            check(c.getClientProperty(BasicHTML.propertyKey) == null,
                    "flips if any dialog line is rendered through an HTML view (" + c.getClass().getSimpleName() + ")");
        }
        check(tree.contains(m) && m instanceof JTextComponent && text.equals(((JTextComponent) m).getText()),
                "flips if the dialog message stops being the plain text component holding the server text verbatim");
        check(m.getClientProperty(SwingPlainText.HTML_DISABLE) == Boolean.TRUE,
                "flips if SwingPlainText.message stops marking its component html.disable");
        JLabel label = new JLabel("<html>x");
        check(label.getClientProperty(BasicHTML.propertyKey) != null,
                "control: an unmarked opener-led label builds an HTML view");
        SwingPlainText.plain(label).setText("<html>y");
        check(label.getClientProperty(BasicHTML.propertyKey) == null,
                "flips if html.disable stops suppressing the label's HTML view");
    }

    /**
     * The ship combo shows ids (plus the hull when served), never names, and
     * launches ids. The pure mapping first: a named id with no hull reads the id alone, the editor's shown text is
     * the id, a typed name still maps back to its id by fold, a typed id and the server-default label pass through, a
     * name two ids share never maps back by text; then one UNSHOWN editable combo with the pair installed: a selected
     * id shows itself, the editor hands back the id, the renderer paints hull + id suffix (never the name) through
     * html-disabled labels, and an opener-led hull or name builds no HTML view.
     *
     */
    private static void selftestShipChoiceNames() {
        String id = "kept-standard-alphaco-8a9cee";
        String name = "Standard (AlphaCoreMP)";
        ShipChoiceNames n = new ShipChoiceNames();
        Map<String, String> served = new LinkedHashMap<>();
        served.put(id, name);
        n.replace(served, null);
        check(id.equals(n.rowText(id)),
                "ship names: a named id with no hull reads the id alone (flips if the display name paints in the row)");
        check("wolf_Assault".equals(n.rowText("wolf_Assault")),
                "ship names: an unnamed id's row is the id alone (flips if an unknown id gets a guessed label)");
        check(id.equals(n.shown(id)) && "wolf_Assault".equals(n.shown("wolf_Assault")) && "".equals(n.shown(null)),
                "ship names: the editor shows the id even when a name is known (flips if the editor paints the name)");
        check(id.equals(n.toId(name, null)) && id.equals(n.toId("  standard (alphacoremp) ", null)),
                "ship names: a typed exact name maps back to its id by ASCII fold (flips if Launch gets the name)");
        check("wolf_Assault".equals(n.toId("wolf_Assault", null))
                        && JoinProbe.SERVER_DEFAULT_LABEL.equals(n.toId(JoinProbe.SERVER_DEFAULT_LABEL, null)),
                "ship names: a typed id and the server-default label pass through untouched (flips if the fallback breaks)");
        check("wolf_Assault".equals(n.toId("wolf_Assault", id)) && id.equals(n.toId(id, id)),
                "ship names: an edit over a last-shown pick is the typed text, an unedited pick is its id (flips if"
                        + " the stale pick wins over an edit)");
        n.put("standard_copy", name);
        check(name.equals(n.toId(name, null)) && "standard_copy".equals(n.toId(n.shown("standard_copy"), null))
                        && id.equals(n.toId(n.shown(id), null)),
                "ship names: a name two ids share never maps back by text, and each ship's shown text launches it with"
                        + " no last-shown help (flips if a shared name launches the wrong ship or the editor paints it)");
        n.replace(Map.of(), Map.of());
        check(n.nameOrNull(id) == null && name.equals(n.toId(name, null)),
                "ship names: replace drops the last probe's names (flips if a stale server's names linger)");

        javax.swing.JComboBox<String> box = new javax.swing.JComboBox<>();
        box.setEditable(true);
        ShipChoiceNames installed = ShipChoiceNames.install(box);
        installed.replace(served, Map.of(id, "hammerhead"));
        box.addItem(JoinProbe.SERVER_DEFAULT_LABEL);
        box.addItem(id);
        box.addItem("wolf_Assault");
        box.setSelectedItem(id);
        Component editor = box.getEditor().getEditorComponent();
        check(editor instanceof JTextComponent && id.equals(((JTextComponent) editor).getText()),
                "ship names: the combo editor shows the selected id itself (flips if the installed editor paints the"
                        + " display name or the hull)");
        check(id.equals(box.getEditor().getItem())
                        && id.equals(JoinLaunchPlan.sanitizeShip(String.valueOf(box.getEditor().getItem()))),
                "ship names: the Launch read of the editor is the id, through sanitizeShip (flips if Launch gets the name)");
        box.setSelectedItem("wolf_Assault");
        check("wolf_Assault".equals(((JTextComponent) editor).getText())
                        && "wolf_Assault".equals(box.getEditor().getItem()),
                "ship names: an unnamed id shows and launches as itself");
        javax.swing.JList<String> list = new javax.swing.JList<>();
        List<JComponent> painted = componentTree(box.getRenderer().getListCellRendererComponent(list, id, 1, false,
                false));
        List<String> texts = new ArrayList<>();
        boolean plain = true;
        for (JComponent c : painted) {
            if (c instanceof JLabel) {
                texts.add(((JLabel) c).getText());
                plain &= c.getClientProperty(SwingPlainText.HTML_DISABLE) == Boolean.TRUE;
            }
        }
        check(texts.size() == 2 && texts.contains("hammerhead") && texts.contains(ShipChoiceNames.SEPARATOR + id)
                        && plain,
                "ship names: a named, hulled popup row paints the hull and the id suffix only, in html-disabled labels"
                        + " (flips if the renderer paints the name, drops either half or paints through an HTML-capable"
                        + " label)");
        installed.put("evil_ship", "<html><img src=x>");
        installed.putHull("evil_ship", "<html><b>x</b>");
        boolean noView = true;
        for (JComponent c : componentTree(box.getRenderer().getListCellRendererComponent(list, "evil_ship", 2, false,
                false))) {
            noView &= c.getClientProperty(BasicHTML.propertyKey) == null;
        }
        check(noView, "ship names: an opener-led hull (or name) builds no HTML view in the popup (flips if a server"
                + " label renders markup)");
    }

    /**
     * Rows {@code join_choice_shows_name_hull_id} and {@code fetcher_carries_hull_from_target_label} (the first row's
     * name predates the no-name law and is kept as its stable id): a ship row reads {@code hull  —  id} when the
     * server sent the hull id (the ship row's optional targetLabel), named or not, and the id alone when it sent none;
     * a display name never paints. The pure mapping and an unshown combo's renderer first (the typed-text pick included);
     * then one probe result reaching BOTH join windows through their door seams — the REAL Swing fetcher paints the
     * hull in its combo rows, the REAL browser probe reply carries {@code hulls} — present, then absent; then the
     * collector on the canned no-label listing names no hull. Fixture values only.
     *
     *
     */
    private static void selftestShipChoiceHulls(Path tmp) throws IOException {
        String row = "join_choice_shows_name_hull_id";
        String id = "kept-standard-alphaco-8a9cee";
        String name = "Standard (AlphaCoreMP)";
        String hull = "hammerhead";
        String dash = "  " + (char) 0x2014 + "  ";
        Map<String, String> names = Map.of(id, name);
        Map<String, String> hulls = new LinkedHashMap<>();
        hulls.put(id, hull);
        hulls.put("lasher_CS", "lasher");
        ShipChoiceNames n = new ShipChoiceNames();
        n.replace(names, hulls);
        check((hull + dash + id).equals(n.rowText(id)) && dash.equals(ShipChoiceNames.SEPARATOR),
                row + ": a named, hulled id's row is the hull, the dash and the id, no name (flips if the display name"
                        + " paints, the hull drops or reorders, or the separator drifts)");
        check(("lasher" + dash + "lasher_CS").equals(n.rowText("lasher_CS")) && "wolf_Assault".equals(n.rowText("wolf_Assault")),
                row + ": an unnamed hulled id reads hull + dash + id as well, an id the server sent nothing for reads the id"
                        + " alone (flips if a hull without a name is dropped or put after the id, or a label is guessed)");
        check(id.equals(n.shown(id)) && id.equals(n.toId(name, null)) && id.equals(n.toId(id, null))
                        && hull.equals(n.toId(hull, null)),
                row + ": typed text: the name and the id both pick the ship, the editor shows the id, a typed hull id"
                        + " stays raw text (flips if a hull many ships share launches a guessed one, or the editor shows"
                        + " the name or the hull)");
        n.replace(names, null);
        check(id.equals(n.rowText(id)) && n.hullOrNull(id) == null,
                row + ": a probe with no hulls drops the last one's and a named row reads the id alone (flips if a"
                        + " stale hull lingers or the name paints in its place)");
        javax.swing.JComboBox<String> box = new javax.swing.JComboBox<>();
        ShipChoiceNames.install(box).replace(names, hulls);
        check(labelTexts(box, id).equals(List.of(hull, dash + id))
                        && labelTexts(box, "lasher_CS").equals(List.of("lasher", dash + "lasher_CS"))
                        && labelTexts(box, "wolf_Assault").equals(List.of("wolf_Assault")),
                row + ": the popup paints the hull bright and dash + id dim on every hulled row, named or not, and a"
                        + " hull-less row as the id alone (flips if the renderer splits on the name again, paints the"
                        + " name, or drops the hull)");

        String frow = "fetcher_carries_hull_from_target_label";
        Path modDir = sidesKit(tmp, "ship-hulls", null);
        List<String> ids = List.of(id, "lasher_CS", "wolf_Assault");
        DoorProbe.Result[] reply = {DoorProbe.Result.served(ids, 0, 3, null, List.of(), 0, true, names, hulls)};
        List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        JoinBrowserUi.DoorCall realSwing = ShipListFetcher.doorCall;
        JoinBrowserUi.DoorCall realBrowser = JoinBrowserUi.doorCall;
        JoinBrowserUi.DoorCall fake = (host, port, password, clientId, fingerprint) -> {
            calls.add(String.valueOf(fingerprint));
            return reply[0];
        };
        ShipListFetcher.doorCall = fake;
        JoinBrowserUi.doorCall = fake;
        javax.swing.JButton refresh = new javax.swing.JButton("Refresh list");
        javax.swing.JComboBox<String> fbox = new javax.swing.JComboBox<>();
        fbox.setEditable(true);
        ShipListFetcher fetcher = new ShipListFetcher(modDir, null, new javax.swing.JTextField("127.0.0.1"),
                new javax.swing.JTextField("7777"), new javax.swing.JPasswordField(), fbox, refresh, new JLabel(), true,
                new JoinSettings("127.0.0.1", "7777", "", "pw-1pass", "", true, "", "127.0.0.1:7777"));
        try {
            List<String> present = fetchedRowTexts(frow, fetcher, refresh, calls, 1, fbox, id);
            reply[0] = DoorProbe.Result.served(ids, 0, 3, null, List.of(), 0, true, names, Map.of());
            List<String> absent = fetchedRowTexts(frow, fetcher, refresh, calls, 2, fbox, id);
            check(present.equals(List.of(hull, dash + id)) && absent.equals(List.of(id)),
                    frow + ": the Swing fetcher's combo row carries the probe's hull, and a probe without one reads the"
                            + " id alone (got " + present.size() + "/" + absent.size() + " labels; flips if apply drops"
                            + " the result's hulls, keeps a stale one, or paints the name)");
            JoinBrowserUi ui = JoinBrowserUi.forSelftest(modDir, new JoinSettings("", "", "", "", "", true, "", ""));
            reply[0] = DoorProbe.Result.served(ids, 0, 3, null, List.of(), 0, true, names, hulls);
            String withHull = ui.probe("127.0.0.1", "7777", "pw-1pass");
            reply[0] = DoorProbe.Result.served(ids, 0, 3, null, List.of(), 0, true, names, Map.of());
            String noHull = ui.probe("127.0.0.1", "7777", "pw-1pass");
            check(withHull.contains("\"status\":\"served\"") && noHull.contains("\"status\":\"served\""),
                    "SETUP-" + frow + ": both browser probes are served by the fake door");
            check(withHull.contains("\"hulls\":{\"" + id + "\":\"" + hull + "\",\"lasher_CS\":\"lasher\"}")
                            && withHull.contains("\"names\":{\"" + id + "\":\"" + name + "\"}")
                            && noHull.contains("\"hulls\":null"),
                    frow + ": the browser probe reply carries {id: hull} beside the names, and hulls null when the"
                            + " server sent none (flips if the served reply drops or invents the hull map)");
        } finally {
            ShipListFetcher.doorCall = realSwing;
            JoinBrowserUi.doorCall = realBrowser;
            fetcher.stopDebounce();
        }
        InetSocketAddress door = new InetSocketAddress("127.0.0.1", 51820);
        byte[] ctrl = DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.CTRL, 3, 0L, 0L, 0L, 0, 0L,
                hex(SELFTEST_LSTD_HEX));
        DoorProbe.Collector c = new DoorProbe.Collector(door);
        check(c.offer(door, ctrl, ctrl.length), "SETUP-" + frow + ": the collector completes on the canned listing");
        DoorProbe.Result plain = c.result("no reply");
        check(plain.served() && plain.hulls.isEmpty() && plain.names.isEmpty() && plain.ids.size() == 2,
                frow + ": a ship listing with no targetLabel names no hull (flips if the collector invents a hull from"
                        + " another field)");
    }

    /** The JLabel texts the combo's renderer paints for {@code value} (row 0, unselected): the component itself, else
     *  its direct children in layout order (the named row's panel = the bright head, then the dim tail). */
    private static List<String> labelTexts(javax.swing.JComboBox<String> box, String value) {
        Component painted = box.getRenderer().getListCellRendererComponent(new javax.swing.JList<>(), value, 0, false,
                false);
        List<String> out = new ArrayList<>();
        for (Component c : painted instanceof JLabel ? new Component[] {painted}
                : ((Container) painted).getComponents()) {
            if (c instanceof JLabel) {
                out.add(((JLabel) c).getText());
            }
        }
        return out;
    }

    /** One REAL fetch of {@code fetcher} on the EDT, awaited (bounded, 5 s) until the fake door saw {@code calls}
     *  probes and apply landed (Refresh re-enabled); returns the combo's painted texts for {@code id}. A fetch that
     *  never starts or lands fails SETUP, never the row. */
    private static List<String> fetchedRowTexts(String row, ShipListFetcher fetcher, javax.swing.JButton refresh,
            List<String> calls, int want, javax.swing.JComboBox<String> box, String id) {
        boolean[] state = new boolean[1];
        edt(row, () -> {
            fetcher.fetchNow();
            state[0] = !refresh.isEnabled();
        });
        check(state[0], "SETUP-" + row + ": the committed fields start a Swing probe");
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (true) {
            edt(row, () -> state[0] = refresh.isEnabled());
            if (calls.size() >= want && state[0]) {
                break;
            }
            check(System.nanoTime() < deadline, "SETUP-" + row + ": the Swing probe did not reach the fake door and"
                    + " land within 5 s");
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                check(false, "SETUP-" + row + ": interrupted while waiting for the Swing probe");
            }
        }
        List<List<String>> texts = new ArrayList<>();
        edt(row, () -> texts.add(labelTexts(box, id)));
        return texts.get(0);
    }

    /** One EDT task, awaited; a throw inside it fails the row by name (the exception's SimpleName only). */
    private static void edt(String row, Runnable task) {
        try {
            javax.swing.SwingUtilities.invokeAndWait(task);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            check(false, row + ": an EDT entry threw " + cause.getClass().getSimpleName());
        }
    }

    /** Every JComponent under {@code root}, root included (depth-first). */
    private static List<JComponent> componentTree(Component root) {
        List<JComponent> out = new ArrayList<>();
        java.util.ArrayDeque<Component> todo = new java.util.ArrayDeque<>(List.of(root));
        while (!todo.isEmpty()) {
            Component c = todo.pop();
            if (c instanceof JComponent) {
                out.add((JComponent) c);
            }
            if (c instanceof Container) {
                for (Component child : ((Container) c).getComponents()) {
                    todo.push(child);
                }
            }
        }
        return out;
    }

    /** GUI v2 step 2: the responder's pure laws — endpoint text, host/origin allow-lists, JSON, forms. */
    private static void selftestBrowserUi() {
        // The editor auto-opens once the game is live; -Dcc.join.autoeditor / CC_JOIN_AUTOEDITOR=off opts out
        check(JoinCore.autoEditorEnabled(null, null) && JoinCore.autoEditorEnabled("on", null)
                && JoinCore.autoEditorEnabled("", "off") == false && !JoinCore.autoEditorEnabled("off", null)
                && !JoinCore.autoEditorEnabled("OFF", "on") && !JoinCore.autoEditorEnabled(null, "off")
                && JoinCore.autoEditorEnabled(null, "on"), "auto-editor: default on; the property wins, then the env; off = off");
        Path noInstall = Paths.get(System.getProperty("java.io.tmpdir")).resolve("cc-join-selftest-no-install");
        check(JoinBrowserUi.fontBytes(noInstall, "orbitron-black") == null && JoinBrowserUi.fontBytes(noInstall, "../x") == null
                && JoinBrowserUi.fontBytes(noInstall, null) == null && JoinBrowserUi.fontBytes(noInstall, "orbitron-bold") == null,
                "join font route: only the allowlisted faces are ever named; a missing file is null, never a throw");
        String tok = JoinHost.mintToken();
        check(tok.matches("[0-9a-f]{64}") && !tok.equals(JoinHost.mintToken()), "join token: 64 hex, per mint");
        check(("40001|" + tok + "|0badf00d").equals(JoinHost.endpointFileText(40001, tok, "0badf00d"))
                && ("40001|" + tok).equals(JoinHost.endpointFileText(40001, tok, null)), "join endpoint text: 2/3 fields");
        check(("http://127.0.0.1:40001/" + tok + "/").equals(JoinHost.parseEndpoint("40001|" + tok + "|0badf00d\n"))
                && ("http://127.0.0.1:40001/" + tok + "/").equals(JoinHost.parseEndpoint("40001|" + tok)),
                "join endpoint parse: both shapes");
        check(JoinHost.parseEndpoint(null) == null && JoinHost.parseEndpoint("") == null
                && JoinHost.parseEndpoint("0|" + tok) == null && JoinHost.parseEndpoint("70000|" + tok) == null
                && JoinHost.parseEndpoint("40001|" + tok.substring(0, 32)) == null
                && JoinHost.parseEndpoint("40001|" + tok + "|zz") == null
                && JoinHost.parseEndpoint("40001|" + tok + "|0badf00d|x") == null
                && JoinHost.parseEndpoint("x|" + tok) == null, "join endpoint parse: refusals");
        // The endpoint file lives under LOCALAPPDATA, tagged per install; the
        // golden tag in cc_join_endpoint-ecf39d82.data is the scripts' own formula over "c:\games\starsector" (SHA-256, first 4 bytes)
        Path game = Paths.get("C:\\Games\\Starsector");
        check(Paths.get("D:\\Lad\\cc-starsector\\join\\cc_join_endpoint-ecf39d82.data")
                        .equals(JoinHost.endpointFile("D:\\Lad", game))
                        && JoinHost.installTag(Paths.get("c:\\GAMES\\Starsector\\mods\\..")).equals("ecf39d82"),
                "join endpoint file: LOCALAPPDATA\\cc-starsector\\join, the per-install tag over the lower-cased"
                        + " normalized root (flips if the folder, the hash, the case fold or normalize changes)");
        check(JoinHost.endpointFile(null, game) == null && JoinHost.endpointFile("  ", game) == null
                        && JoinHost.endpointFile("Lad", game) == null && JoinHost.endpointFile("\\Lad", game) == null,
                "join endpoint file: no LOCALAPPDATA, a blank, relative or drive-relative one = NO file (flips on a"
                        + " fallback into the install, %TEMP% or the start folder)");
        check(JoinRoutes.isOurHost("127.0.0.1:40001", 40001) && JoinRoutes.isOurHost("LOCALHOST:40001", 40001)
                && JoinRoutes.isOurHost("[::1]:40001", 40001) && !JoinRoutes.isOurHost("127.0.0.1:40002", 40001)
                && !JoinRoutes.isOurHost("127.0.0.1", 40001) && !JoinRoutes.isOurHost("evil.example:40001", 40001),
                "join host allow-list");
        check(JoinRoutes.isOurOrigin("http://127.0.0.1:40001", 40001) && JoinRoutes.isOurOrigin("http://[::1]:40001", 40001)
                && !JoinRoutes.isOurOrigin("https://127.0.0.1:40001", 40001)
                && !JoinRoutes.isOurOrigin("http://evil.example", 40001), "join origin allow-list");
        check("{\"ok\":true}".equals(JoinJson.ok()) && "{\"ok\":false,\"error\":\"x\"}".equals(JoinJson.error("x")),
                "join json: ok/error idiom");
        check("{\"a\":\"q\\\"b\\\\\\n\\u003cs\\u003e\\u0026\\u0000\",\"n\":null,\"b\":true,\"i\":-5,\"l\":[\"x\",\"\"]}"
                .equals(new JoinJson().put("a", "q\"b\\\n<s>&" + (char) 0).put("n", (String) null).put("b", true)
                        .put("i", -5L).putStrings("l", java.util.Arrays.asList("x", null)).toString()),
                "join json: escaping (quotes, backslash, control, angle brackets, ampersand)");
        byte[] form = "host=1.2.3.4&password=p%40ss+word&empty=&ship".getBytes(StandardCharsets.UTF_8);
        check("1.2.3.4".equals(JoinRoutes.formField(form, "host", 64))
                && "p@ss word".equals(JoinRoutes.formField(form, "password", 64))
                && "".equals(JoinRoutes.formField(form, "empty", 64)) && "".equals(JoinRoutes.formField(form, "ship", 64))
                && JoinRoutes.formField(form, "absent", 64) == null
                && JoinRoutes.formField(form, "host", 3) == null
                && JoinRoutes.formField("x=%zz".getBytes(StandardCharsets.UTF_8), "x", 8) == null,
                "join form field: decode, plus-space, empty, absent, over-long, bad escape");
        check("ab?c".equals(JoinRoutes.bounded("abéc", 10)) && "ab".equals(JoinRoutes.bounded("abcdef", 2))
                && "".equals(JoinRoutes.bounded(null, 5)), "join bounded projection");
        check("browser".equals(JoinBrowserUi.DEFAULT_UI) && System.getProperty("cc.join.ui") == null
                && JoinBrowserUi.selected() == !"1".equals(System.getenv("CC_JOIN_AUTOLAUNCH")),
                "join ui: browser unless -Dcc.join.ui=swing or CC_JOIN_AUTOLAUNCH (the default)");
        check(JoinRoutes.hasField("a=1&voiceOn=&b".getBytes(StandardCharsets.UTF_8), "voiceOn")
                && JoinRoutes.hasField("voiceOn".getBytes(StandardCharsets.UTF_8), "voiceOn")
                && !JoinRoutes.hasField("a=1&voiceOnx=1".getBytes(StandardCharsets.UTF_8), "voiceOn"),
                "join form field presence (the voice fields' opt-in)");
    }

    /** GUI v2 step 4: the Swing-free voice core the panel and the browser bridge share — the index
     *  law, the preselect substring law, the file value, the devices body, the strict launch pick. No real mixer is
     *  opened here (a box without a mic must pass); the smoke's run G drives the Test worker. */
    private static void selftestVoiceCore() {
        javax.sound.sampled.Mixer.Info yeti = new javax.sound.sampled.Mixer.Info("Blue Yeti USB", "v", "d", "1") { };
        javax.sound.sampled.Mixer.Info cjk = new javax.sound.sampled.Mixer.Info("麦克风", "v", "d", "1") { };
        java.util.List<javax.sound.sampled.Mixer.Info> list = java.util.Arrays.asList(yeti, cjk);
        java.util.List<javax.sound.sampled.Mixer.Info> none = java.util.Collections.emptyList();
        check(JoinVoiceCore.preselect(list, "") == 0 && JoinVoiceCore.preselect(list, null) == 0
                && JoinVoiceCore.preselect(list, "yeti") == 1 && JoinVoiceCore.preselect(list, "Realtek") == 0
                && JoinVoiceCore.preselect(none, "yeti") == 0, "voice core: preselect = first substring match, 0 = default");
        check("".equals(JoinVoiceCore.deviceValue(list, 0)) && "Blue Yeti USB".equals(JoinVoiceCore.deviceValue(list, 1))
                && "".equals(JoinVoiceCore.deviceValue(list, 2)) && "".equals(JoinVoiceCore.deviceValue(list, 3))
                && JoinVoiceCore.mixerAt(list, 1) == yeti && JoinVoiceCore.mixerAt(list, 0) == null
                && JoinVoiceCore.mixerAt(list, 9) == null, "voice core: index law (0 = default, off-list = default)");
        check(!JoinVoiceCore.deviceNameLost(list, 0) && !JoinVoiceCore.deviceNameLost(list, 1)
                && JoinVoiceCore.deviceNameLost(list, 2), "voice core: a name that reduces to nothing is LOST");
        check(("{\"ok\":true,\"defaultLabel\":\"System default\",\"devices\":[{\"index\":1,\"name\":\"Blue Yeti USB\","
                + "\"value\":\"Blue Yeti USB\",\"lost\":false},{\"index\":2,\"name\":\"麦克风\",\"value\":\"\",\"lost\":true}],"
                + "\"selected\":1,\"line\":\"2 microphone(s) found\"}").equals(JoinVoiceCore.devicesJson(list, "blue")),
                "voice core: devices body (index/name/value/lost, preselect, count line)");
        check(JoinVoiceCore.devicesJson(none, "x").contains("\"devices\":[],\"selected\":0,\"line\":\""
                + JoinVoiceCore.NO_DEVICES + "\""), "voice core: empty list wording");
        JoinVoiceCore.Pick dflt = JoinVoiceCore.parsePick("", "", "", "");
        check(dflt.device == 0 && !dflt.on && dflt.gainPercent == 100 && dflt.gainFactor() == 1f
                && dflt.complexity == cc.starsector.client.voice.VoiceClientSettings.SERVER_DEFAULT,
                "voice core: pick defaults (device 0, off, 100 %, server default)");
        JoinVoiceCore.Pick set = JoinVoiceCore.parsePick(" 2 ", "ON", "150", "7");
        check(set.device == 2 && set.on && set.gainPercent == 150 && set.gainFactor() == 1.5f && set.complexity == 7,
                "voice core: pick parses (trimmed, case-free on/off)");
        check(!JoinVoiceCore.parsePick("0", "off", "0", "-1").on && JoinVoiceCore.parsePick("0", "off", "0", "-1").complexity == -1,
                "voice core: pick off / gain 0 / quality -1");
        checkThrows(() -> JoinVoiceCore.parsePick("x", "on", "100", ""), "voice core: device must be a number");
        checkThrows(() -> JoinVoiceCore.parsePick("0", "maybe", "100", ""), "voice core: on is on|off|empty");
        checkThrows(() -> JoinVoiceCore.parsePick("0", "on", "201", ""), "voice core: gain caps at 200");
        checkThrows(() -> JoinVoiceCore.parsePick("0", "on", "100", "11"), "voice core: quality caps at 10");
        try {
            JoinVoiceCore.parsePick("0", "on", "999", "");
            check(false, "voice core: an unlawful field names the FIELD");
        } catch (IllegalArgumentException named) {
            check("field voiceGain is out of range".equals(named.getMessage()), "voice core: an unlawful field names the FIELD");
        }
        JoinVoiceCore.Tester t = new JoinVoiceCore.Tester();
        check(!t.running() && t.isCurrent(0), "voice core: a fresh tester idles at run 0");
        t.stop();
        t.stop();
        check(!t.running() && !t.isCurrent(0) && t.isCurrent(2), "voice core: stop is repeatable and bumps the run");
    }

    /**
     * The local id law refuses a Windows
     * reserved device STEM (the part before the first dot, any case) and nothing else — the
     * server-build cache names a file after a server-sent id. Every reserved name runs bare,
     * lower-cased and with the cache's extension; the stem-not-substring neighbours stay lawful;
     * {@code CurrentBuildLaw.parse} is the reachable real caller ({@code EditorText.isId} is
     * package-private to the editor package).
     */
    private static void selftestReservedDeviceStems() {
        String[] refused = {"CON", "con", "Com1", "com0", "LPT0", "nul.variant", "LPT9.x.y", "prn."};
        String[] lawful = {"CONSOLE", "con1", "COM10", "LPT10", "aux_wolf", "wolf.con"};
        for (String id : refused) {
            check(!cc.starsector.client.net.LocalVariantIdLaw.isLawful(id),
                    "a reserved device stem passed the id law: '" + id + "'");
        }
        for (String id : lawful) {
            check(cc.starsector.client.net.LocalVariantIdLaw.isLawful(id),
                    "a lawful id was refused by the reserved-stem rule: '" + id + "'");
        }
        List<String> names = new java.util.ArrayList<>(List.of("CON", "PRN", "AUX", "NUL"));
        for (int n = 0; n <= 9; n++) {
            names.add("COM" + n);
            names.add("LPT" + n);
        }
        for (String name : names) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            check(!cc.starsector.client.net.LocalVariantIdLaw.isLawful(name)
                            && !cc.starsector.client.net.LocalVariantIdLaw.isLawful(lower)
                            && !cc.starsector.client.net.LocalVariantIdLaw.isLawful(lower + ".variant.data"),
                    "a reserved device stem passed the id law: '" + name + "' (bare / lower / with extension)");
            check(cc.starsector.client.net.LocalVariantIdLaw.isLawful(name + "_wolf")
                            && cc.starsector.client.net.LocalVariantIdLaw.isLawful("wolf." + lower),
                    "a lawful id was refused by the reserved-stem rule: '" + name + "_wolf' / 'wolf." + lower + "'");
        }
        check("".equals(cc.starsector.client.net.CurrentBuildLaw.parse("CON\r\n"))
                        && "CONSOLE".equals(cc.starsector.client.net.CurrentBuildLaw.parse("CONSOLE\r\n")),
                "a reserved device stem passed the id law through CurrentBuildLaw.parse (or a lawful one was refused)");
        // Fold: the rename note names the rule that refused (a reserved stem is letters, so
        // the character wording would mislead); a character-law refusal keeps the character wording. The
        // note is composed, never a fixture file — a CON-stem file is unsafe to create on Windows.
        check(JoinCore.idLawNote("con").contains("reserved device name")
                        && JoinCore.idLawNote("nul.variant").contains("reserved device name")
                        && JoinCore.idLawNote("-lead").contains("letters/digits")
                        && !JoinCore.idLawNote("-lead").contains("reserved"),
                "the id-law rename note does not name the rule that refused (reserved stem vs character law)");
    }

    /** One {@code /ship} LSTD frame as the SERVER's {@code ListingEmitter.encodeListing(0,
     * KIND_SHIP, 1, shipEntries([onslaught_xao_Standard, shuttlepod_cc_Bare, weird#id]))}
     * emitted it (minted 2026-08-24 against server/classes; 477 bytes, CRC included) —
     * the client tree bundles the READER only, so the golden is bytes, not a call. */
    static final String SELFTEST_LSTD_HEX =
            // magic, typeCode, schema 1 | seatId 0, KIND_SHIP, listingId 1, seqIndex 0,
            // seqCount 1, totalEntries 3, totalAvailable 3, entryCount 3
            "4c5354444894236d0001" + "00000000" + "01" + "00000001" + "00" + "01" + "0003"
            + "0003" + "03"
            // 149 B/entry: u8 len + token, zero-padded (plain mark, no labels, hull 0)
            + "16" + "6f6e736c61756768745f78616f5f5374616e64617264" + "00".repeat(126)
            + "12" + "73687574746c65706f645f63635f42617265" + "00".repeat(130)
            + "08" + "7765697264236964" + "00".repeat(140)
            + "ec34e6ee"; // CRC32 as emitted

    /** A canned VACK frame (the server's VariantReceiptFrameCodec shape, hand-minted: magic,
     *  schema 1, header 16, wire type code, body length, body, CRC32 of the BODY only). */
    static byte[] vack(int submitSeq, boolean accepted, String reasonToken, String id) {
        byte[] idBytes = id.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] body = new byte[5 + idBytes.length];
        body[0] = (byte) (submitSeq >>> 8);
        body[1] = (byte) submitSeq;
        body[2] = (byte) (accepted ? ClientVariantReceiptReader.VERDICT_ACCEPTED
                : ClientVariantReceiptReader.VERDICT_REJECTED);
        body[3] = (byte) (accepted ? 0 : DoorRejectReasons.customCode(reasonToken));
        body[4] = (byte) idBytes.length;
        System.arraycopy(idBytes, 0, body, 5, idBytes.length);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(
                ClientVariantReceiptReader.HEADER_BYTES + body.length + 4);
        b.putInt(ClientVariantReceiptReader.MAGIC);
        b.putShort((short) ClientVariantReceiptReader.SCHEMA_VERSION);
        b.putShort((short) ClientVariantReceiptReader.HEADER_BYTES);
        b.putInt(ClientVariantReceiptReader.MESSAGE_TYPE_CODE);
        b.putInt(body.length);
        b.put(body);
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        b.putInt((int) crc.getValue());
        return b.array();
    }

    /** The door's VCHK reply envelope: channel VCHK, sessionId = the echoed nonce, seq = submitSeq. */
    static byte[] vchkFrame(long nonce, long seq, byte[] vackPayload) {
        return DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.VCHK, 0, nonce, seq, 0L,
                0, 0L, vackPayload);
    }

    /** A v12 COOKIE WELCOME hand-packed per the compact
     * grammar {@code {u8 version, u8 status=2, u64 nonceEcho, u8 len, cookie}} at the LITERAL version
     * byte 12 (a stale door's reply; never {@code HANDSHAKE_VERSION}, past 12 since stage Y, now 14).
     * */
    static byte[] cookieWelcome(long nonceEcho, byte[] cookie) {
        byte[] p = new byte[1 + 1 + 8 + 1 + cookie.length];
        p[0] = (byte) 12;
        p[1] = (byte) HandshakeCodec.WELCOME_COOKIE;
        DatagramFrameCodec.putU64(p, 2, nonceEcho);
        p[10] = (byte) cookie.length;
        System.arraycopy(cookie, 0, p, 11, cookie.length);
        return DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.WELCOME, 0, 0L, 0L, 0L,
                0, 0L, p);
    }

    /** A version-12 WELCOME datagram (ADMITTED / REJECTED; the layout is unchanged since v11) hand-packed at the
     * LITERAL version byte 12 — a stale door's reply, never {@code HANDSHAKE_VERSION} (past 12 since stage Y, now 14).
     * Since THE FLIP the kit decodes none: the collector rows feed it to pin that it decides nothing.
     * tickRate 60, schema 1, flags 0, serverLife 1 (all non-zero by the v12 decode law); v11 custom tail = the
     * server defaults (STANDARD / 4 per seat / engine cap / submissions OFF). */
    static byte[] welcome(int status, long nonceEcho, String reason) {
        boolean admitted = status == HandshakeCodec.WELCOME_ADMITTED;
        byte[] p = new byte[1 + 1 + 8 + 8 + 1 + 2 + DatagramFrameCodec.modifiedUtfLength(reason)
                + 8 + 2 + 1 + 1 + 8 + 4];
        p[0] = (byte) 12;
        p[1] = (byte) status;
        DatagramFrameCodec.putU64(p, 2, nonceEcho);
        DatagramFrameCodec.putU64(p, 10, admitted ? 5L : 0L); // sessionId
        p[18] = 3; // startingRoundId
        int off = DatagramFrameCodec.putModifiedUtf(p, 19, reason);
        DatagramFrameCodec.putU64(p, off, admitted ? 2L : 0L); // boundShipId
        off += 8;
        DatagramFrameCodec.putU16(p, off, 60); // tickRate
        off += 2;
        p[off++] = 1; // serveSchemaMax
        p[off++] = 0; // matchFlags
        DatagramFrameCodec.putU64(p, off, 1L); // serverLife
        off += 8;
        p[off++] = (byte) HandshakeCodec.CUSTOM_TIER_STANDARD; // v11 customTier
        p[off++] = 4; // customPerSeat
        p[off++] = (byte) HandshakeCodec.CUSTOM_S_MOD_CAP_ENGINE; // customSModCap (-1 = engine)
        p[off] = 0; // customFlags (submissions OFF)
        return DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.WELCOME, 0, 0L, 0L, 0L,
                0, 0L, p);
    }

    /**
     * Mint a mark-N variant of a canned LSTD frame — flips entry
     * 0's markCode byte (the byte after its 64-byte token buffer) and repairs the trailing CRC32
     * (big-endian, the reader's law), so the mark-2 host-default path has a golden the mod tree
     * can build without a server-side encoder (the client bundles the READER only).
     */
    static byte[] withEntry0Mark(byte[] lstd, int mark) {
        byte[] copy = lstd.clone();
        int markOff = ClientListingReader.HEADER_BYTES + ClientListingReader.LISTING_HEADER_BYTES
                + 1 + ClientListingReader.TOKEN_BUFFER_BYTES;
        copy[markOff] = (byte) mark;
        CRC32 crc = new CRC32();
        crc.update(copy, 0, copy.length - Integer.BYTES);
        long v = crc.getValue();
        int n = copy.length;
        copy[n - 4] = (byte) (v >>> 24);
        copy[n - 3] = (byte) (v >>> 16);
        copy[n - 2] = (byte) (v >>> 8);
        copy[n - 1] = (byte) v;
        return copy;
    }

    static byte[] hex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("join-gui selftest FAILED: " + what);
        }
    }

    private static void checkThrows(Runnable action, String what) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("join-gui selftest FAILED (expected throw): " + what);
    }

    /** The plan-row refusal: {@code planRow}'s own IllegalArgumentException, whose message names the
     * ROW ({@code key}) and never the offending value (every case above plants {@code 6.6.6.6}). */
    private static void checkPlanRefusal(Runnable action, String key, String what) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            String msg = String.valueOf(expected.getMessage());
            check(msg.contains("plan's " + key + " row") && !msg.contains("6.6.6.6"),
                    what + " (the message names the row, never the value)");
            return;
        }
        throw new AssertionError("join-gui selftest FAILED (expected the plan-row refusal): " + what);
    }
}
