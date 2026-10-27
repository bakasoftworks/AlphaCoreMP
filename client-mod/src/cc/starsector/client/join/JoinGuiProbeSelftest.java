package cc.starsector.client.join;

import static cc.starsector.client.join.JoinGuiSelftest.check;

// The in-memory settings round-trip of the binding rows
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import cc.starsector.client.editor.HttpExchange;
import cc.starsector.client.net.DoorProbe;
import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;

// The headless ShipListFetcher pin's unshown components
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * The probe-commit battery (rider r6): the {@link ProbeCommitGate} table pin and the headless
 * {@code ShipListFetcher} pin on UNSHOWN components, plus the remembered-password binding rows
 * ({@code selftestPasswordBinding}, added after the carve). Carved byte-verbatim out of {@link JoinGuiSelftest}
 * (proven by a verbatim-carve check + an
 * ordered diff) and run from its {@code run()} in the same order as before. WHY its own file:
 * {@code JoinGuiSelftest} sat past its size tier, and the remembered-password binding rows
 * land HERE, beside the probe rows they extend. Pure test code — never on a sim or wire path.
 */
final class JoinGuiProbeSelftest {

    private JoinGuiProbeSelftest() {
    }

    static void run() throws IOException {
        selftestProbeCommit();
        selftestProbeCommitFetcher();
        selftestPasswordBinding();
        selftestPasteReprobes();
        selftestSwingPasteBrickOnly();
        selftestStageClearedLaunch();
        // The adopt path through the real window and a fake door
        selftestAdoptFresh();
        selftestAdoptRefused();
        selftestAdoptIncomplete();
        selftestAdoptCacheCurrent();
        selftestAdoptOncePerProbe();
        // The same adopt through the Swing fallback's fetcher and a fake door
        selftestSwingFetchStages();
        selftestSwingFetchRefused();
        selftestSwingLaunchLastOutcome();
    }

    /**
     * Row {@code stage-cleared-launch}: a probe
     * that staged the server's document records {@code docOk} for the launch gate; a brick paste, an emptied box
     * with no committed pair (no re-probe) and another pair's probe still in flight each clear the stage WITHOUT
     * re-recording that outcome, so a Launch used to read the frozen {@code docOk}, write nothing and boot whatever
     * {@code match.txt} was on disk (an unarmed door admits it). The gate now honours a served {@code docOk} only
     * when it is {@code cacheCurrent} or the stage is still carried: each cleared stage refuses at the
     * {@code document} gate with the stage-gone line, and the kit file stays byte-identical. The REAL
     * {@link JoinBrowserUi#forSelftest} over the paste fixture and a fake door through the
     * {@link JoinBrowserUi#doorCall} seam: the kit file's id (A) is served WITH a lawful document (B), B's confirming
     * re-probe is served a listing only, and a second host throws (a probe that never lands). Fixture values only —
     * no check message carries a print, a password or the document.
     */
    private static void selftestStageClearedLaunch() throws IOException {
        String row = "stage-cleared-launch";
        Path modDir = pasteKitFolder(row);
        byte[] kitFile = Files.readAllBytes(modDir.resolve("match.txt"));
        String document = JoinGuiSelftest.SELFTEST_DESCRIPTOR
                .replace("seat.0.token=00112233445566778899aabbccddeeff", "seat.0.token=REDACTED-seat1")
                .replace("seat.1.token=ffeeddccbbaa99887766554433221100", "seat.1.token=REDACTED-seat2")
                .replace("match.id=" + PASTE_ID_A, "match.id=" + PASTE_ID_B);
        JoinBrowserUi.DoorCall real = JoinBrowserUi.doorCall;
        JoinBrowserUi.doorCall = (host, port, password, clientId, fingerprint) -> {
            if (!"127.0.0.1".equals(host)) {
                throw new IllegalStateException(row + ": the other pair's probe never lands");
            }
            DoorProbe.Result listing = DoorProbe.Result.served(List.of("selftest_ship"), 0, 1, null);
            return fingerprint.endsWith(";match=" + PASTE_ID_B) ? listing : listing.withDocument(document, true, null);
        };
        try {
            JoinBrowserUi ui = JoinBrowserUi.forSelftest(modDir, new JoinSettings("", "", "", "", "", true, "", ""));
            String served = ui.probe("127.0.0.1", "7777", "pw-1pass");
            JoinLaunchGates.ProbeOutcome staged = ui.lastProbe();
            check(served.contains("\"docOk\":true") && served.contains("\"staged\":{")
                    && staged.kind == JoinLaunchGates.ProbeOutcome.Kind.SERVED && staged.docOk && !staged.cacheCurrent,
                    row + ": a served document stages and records docOk on the STAGE, not the cache (flips if the"
                            + " fixture never stages or the outcome reads cacheCurrent)");
            // (i) a brick paste clears the stage: the frozen outcome must not launch
            ui.paste("127.0.0.1:7777:pw-1pass", "127.0.0.1", "7777", "pw-1pass");
            check(stageGone(ui.launch("127.0.0.1", "7777", "", "pw-1pass", "", true, null)),
                    row + ": (i) a brick paste after a staged probe refuses Launch at the document gate (flips if the"
                            + " frozen docOk launches on whatever match.txt is on disk)");
            // (ii) an emptied box with no committed pair re-probes nothing: the same stale outcome, the same refusal
            ui.probe("127.0.0.1", "7777", "pw-1pass");
            ui.paste("", null, null, null);
            check(ui.lastProbe().docOk && stageGone(ui.launch("127.0.0.1", "7777", "", "pw-1pass", "", true, null)),
                    row + ": (ii) an emptied box with no committed pair refuses Launch at the document gate (flips if"
                            + " the stale outcome launches)");
            // (iii) another pair's probe clears the stage before its door call lands: the same refusal for the first
            ui.probe("127.0.0.1", "7777", "pw-1pass");
            boolean inFlight = false;
            try {
                ui.probe("other.example", "7777", "pw-1pass");
            } catch (IllegalStateException neverLanded) {
                inFlight = true;
            }
            check(inFlight && stageGone(ui.launch("127.0.0.1", "7777", "", "pw-1pass", "", true, null)),
                    row + ": (iii) another pair's probe in flight refuses the first pair's Launch at the document gate"
                            + " (flips if the cleared stage's outcome launches)");
            check(Arrays.equals(kitFile, Files.readAllBytes(modDir.resolve("match.txt"))),
                    row + ": the kit file is untouched by the refused launches (flips if a refusal path writes)");
        } finally {
            JoinBrowserUi.doorCall = real;
        }
    }

    /** True when an api/launch reply is the document gate's stage-gone refusal. */
    private static boolean stageGone(String launchJson) {
        return launchJson.contains("\"ok\":false") && launchJson.contains("\"gate\":\"document\"")
                && launchJson.contains(JoinLaunchGates.MSG_STAGE_GONE);
    }

    /** The adopt rows' ids: X = the first document a fresh folder is served, Y = another lawful one. */
    private static final String ADOPT_ID_X = "1v1-adoptfirst0000x";
    private static final String ADOPT_ID_Y = "1v1-adoptsecond000y";
    /** The fetched line the window shows for {@link #adoptDocument}: its shape (not its id) and its seat count. */
    private static final String ADOPT_FETCHED_LINE = "Match fetched from the server: 1v1, 2 seats.";

    /**
     * Row {@code adopt-fresh}: a fresh kit folder (the file list, no {@code match.txt}) presents {@code match=none};
     * the fake door answers that print with a lawful strictly-redacted document beside the listing, and the
     * confirming re-probe (presenting the adopted id) with the listing only. The REAL {@link JoinBrowserUi#probe}
     * adopts it — served, docOk, the stage's summary, the fetched line, exactly two door calls, nothing written yet
     * — and the REAL {@link JoinBrowserUi#launch} writes {@code match.txt} as the shared codec's canonical bytes,
     * which the folder's next print presents. Fixture values only; no message carries a print or the document.
     *
     */
    private static void selftestAdoptFresh() throws IOException {
        String row = "adopt-fresh";
        Path modDir = freshKitFolder(row);
        String document = adoptDocument(ADOPT_ID_X);
        DoorProbe.Result listing = adoptListing();
        Map<String, DoorProbe.Result> byTerm = new HashMap<>();
        byTerm.put(ContentFingerprints.MATCH_NONE, listing.withDocument(document, true, null));
        byTerm.put(ADOPT_ID_X, listing);
        List<String> prints = new ArrayList<>();
        JoinBrowserUi.DoorCall real = JoinBrowserUi.doorCall;
        JoinBrowserUi.doorCall = termDoor(row, prints, byTerm);
        try {
            JoinBrowserUi ui = JoinBrowserUi.forSelftest(modDir, new JoinSettings("", "", "", "", "", true, "", ""));
            String served = ui.probe("127.0.0.1", "7777", "pw-1pass");
            JoinLaunchGates.ProbeOutcome last = ui.lastProbe();
            check(prints.size() == 2 && ContentFingerprints.MATCH_NONE.equals(term(prints.get(0)))
                    && ADOPT_ID_X.equals(term(prints.get(1))),
                    row + ": the fresh folder presents match=none, then ONE confirming re-probe presents the adopted id"
                            + " (flips if the folder presents a blank print or another term, or the re-probe is skipped,"
                            + " repeated or presents the old term)");
            check(served.contains("\"status\":\"served\"") && served.contains("\"docOk\":true")
                    && served.contains("\"staged\":{\"matchId\":" + quoted(ADOPT_ID_X) + ",\"seats\":2}")
                    && served.contains("\"docLine\":" + quoted(ADOPT_FETCHED_LINE)),
                    row + ": the reply is served with docOk, the stage's id + seat count and the fetched line naming the"
                            + " shape (flips if the adopt does not stage, or the line reads the id or drops the seats)");
            check(last.kind == JoinLaunchGates.ProbeOutcome.Kind.SERVED && last.docOk && !last.cacheCurrent
                    && ADOPT_FETCHED_LINE.equals(last.docLine),
                    row + ": the recorded outcome is served + docOk on the STAGE (flips if it reads cacheCurrent or"
                            + " another line)");
            check(!Files.exists(modDir.resolve("match.txt")),
                    row + ": the probe writes nothing (flips if the adopt saves the document before Launch)");
            String launched = ui.launch("127.0.0.1", "7777", "", "pw-1pass", "", true, null);
            MatchDescriptor d = JoinLaunchPlan.parseDescriptor(document);
            check(launched.contains("\"ok\":true") && launched.contains("\"launched\":true")
                    && Files.isRegularFile(modDir.resolve("match.txt"))
                    && Arrays.equals(JoinLaunchPlan.canonicalBytes(d), Files.readAllBytes(modDir.resolve("match.txt"))),
                    row + ": Launch writes match.txt as the codec's canonical bytes of the adopted document (flips if"
                            + " Launch refuses, writes nothing, or writes other bytes)");
            check(ADOPT_ID_X.equals(term(JoinFingerprint.selfCompute(modDir).print)),
                    row + ": the saved file is the folder's next print (flips if the written bytes do not re-read as"
                            + " the adopted id)");
        } finally {
            JoinBrowserUi.doorCall = real;
        }
    }

    /**
     * Row {@code adopt-refused}: the served document claims {@code none} as its id — the word that means "no
     * document" — so the id law refuses it: docOk false, nothing staged, NO confirming re-probe, the refused line
     * naming the law's reason + field, and Launch refused at the {@code document} gate with that same line, nothing
     * written.
     */
    private static void selftestAdoptRefused() throws IOException {
        String row = "adopt-refused";
        Path modDir = freshKitFolder(row);
        String refused = "This server's match was refused (match_doc_out_of_range match.id) - tell the host: their"
                + " server is not a matching CC release.";
        Map<String, DoorProbe.Result> byTerm = new HashMap<>();
        byTerm.put(ContentFingerprints.MATCH_NONE,
                adoptListing().withDocument(adoptDocument(ContentFingerprints.MATCH_NONE), true, null));
        List<String> prints = new ArrayList<>();
        JoinBrowserUi.DoorCall real = JoinBrowserUi.doorCall;
        JoinBrowserUi.doorCall = termDoor(row, prints, byTerm);
        try {
            JoinBrowserUi ui = JoinBrowserUi.forSelftest(modDir, new JoinSettings("", "", "", "", "", true, "", ""));
            String served = ui.probe("127.0.0.1", "7777", "pw-1pass");
            JoinLaunchGates.ProbeOutcome last = ui.lastProbe();
            check(prints.size() == 1,
                    row + ": a refused document gets no confirming re-probe (flips if the refusal re-probes)");
            check(served.contains("\"status\":\"served\"") && served.contains("\"docOk\":false")
                    && served.contains("\"staged\":null") && served.contains("\"docLine\":" + quoted(refused))
                    && last.kind == JoinLaunchGates.ProbeOutcome.Kind.SERVED && !last.docOk && !last.cacheCurrent
                    && refused.equals(last.docLine),
                    row + ": a document whose id is the no-document word is refused by reason + field, unstaged (flips"
                            + " if the id law admits it, the refusal stages, or the line names another reason)");
            String launch = ui.launch("127.0.0.1", "7777", "", "pw-1pass", "", true, null);
            check(launch.contains("\"ok\":false") && launch.contains("\"gate\":\"document\"")
                    && launch.contains("\"error\":" + quoted(refused)) && !Files.exists(modDir.resolve("match.txt")),
                    row + ": Launch is refused at the document gate with the refused line, nothing written (flips if"
                            + " a refused document launches or another gate answers)");
        } finally {
            JoinBrowserUi.doorCall = real;
        }
    }

    /**
     * Row {@code adopt-incomplete}: (i) the door saw document frames that never arrived whole (no text, the
     * collector's incomplete token) — docOk false, nothing staged, no re-probe, the missing line, Launch refused at
     * the {@code document} gate; (ii) a stage adopted on one probe is CLEARED by a later probe whose document did
     * not arrive whole (the server's match moved), so the held older document never launches.
     *
     */
    private static void selftestAdoptIncomplete() throws IOException {
        String row = "adopt-incomplete";
        Path modDir = freshKitFolder(row);
        DoorProbe.Result listing = adoptListing();
        DoorProbe.Result incomplete = listing.withDocument(null, true, "match_doc_incomplete chunks");
        Map<String, DoorProbe.Result> byTerm = new HashMap<>();
        byTerm.put(ContentFingerprints.MATCH_NONE, incomplete);
        List<String> prints = new ArrayList<>();
        JoinBrowserUi.DoorCall real = JoinBrowserUi.doorCall;
        JoinBrowserUi.doorCall = termDoor(row, prints, byTerm);
        try {
            JoinBrowserUi ui = JoinBrowserUi.forSelftest(modDir, new JoinSettings("", "", "", "", "", true, "", ""));
            String served = ui.probe("127.0.0.1", "7777", "pw-1pass");
            JoinLaunchGates.ProbeOutcome last = ui.lastProbe();
            check(prints.size() == 1 && served.contains("\"docOk\":false") && served.contains("\"staged\":null")
                    && served.contains("\"docLine\":" + quoted(JoinLaunchGates.MSG_DOC_MISSING))
                    && !last.docOk && !last.cacheCurrent && JoinLaunchGates.MSG_DOC_MISSING.equals(last.docLine),
                    row + ": (i) a document that did not arrive whole reads the missing line, unstaged, no re-probe"
                            + " (flips if a partial document reads as none sent, or stages, or re-probes)");
            String launch = ui.launch("127.0.0.1", "7777", "", "pw-1pass", "", true, null);
            check(launch.contains("\"ok\":false") && launch.contains("\"gate\":\"document\"")
                    && launch.contains("\"error\":" + quoted(JoinLaunchGates.MSG_DOC_MISSING))
                    && !Files.exists(modDir.resolve("match.txt")),
                    row + ": (i) Launch is refused at the document gate with the missing line (flips if a partial"
                            + " document launches)");
            // (ii) adopt X, then the next probe of X sees frames of a moved match that never arrive whole
            byTerm.put(ContentFingerprints.MATCH_NONE, listing.withDocument(adoptDocument(ADOPT_ID_X), true, null));
            byTerm.put(ADOPT_ID_X, listing);
            check(ui.probe("127.0.0.1", "7777", "pw-1pass").contains("\"docOk\":true"),
                    "SETUP-" + row + ": (ii) the first probe stages the document");
            byTerm.put(ADOPT_ID_X, incomplete);
            String moved = ui.probe("127.0.0.1", "7777", "pw-1pass");
            check(term(prints.get(prints.size() - 1)).equals(ADOPT_ID_X) && moved.contains("\"docOk\":false")
                    && moved.contains("\"staged\":null")
                    && JoinLaunchGates.MSG_DOC_MISSING.equals(ui.lastProbe().docLine),
                    row + ": (ii) a probe presenting the staged id whose document does not arrive whole clears the"
                            + " stage (flips if the held older document stays staged)");
            launch = ui.launch("127.0.0.1", "7777", "", "pw-1pass", "", true, null);
            check(launch.contains("\"ok\":false") && launch.contains("\"gate\":\"document\"")
                    && !Files.exists(modDir.resolve("match.txt")),
                    row + ": (ii) Launch is refused and writes nothing after the stage was cleared (flips if the older"
                            + " document launches)");
        } finally {
            JoinBrowserUi.doorCall = real;
        }
    }

    /**
     * Row {@code adopt-cache-current}: the folder's cached {@code match.txt} (id A) is the server's — the door
     * serves the listing only on {@code match=A} — so the probe is docOk with {@code cacheCurrent} and nothing
     * staged, and Launch is ready with no document: the kit file stays byte-identical and is not rewritten (its
     * modified time is pinned before the probe).
     */
    private static void selftestAdoptCacheCurrent() throws IOException {
        String row = "adopt-cache-current";
        Path modDir = pasteKitFolder(row);
        Path match = modDir.resolve("match.txt");
        byte[] kitFile = Files.readAllBytes(match);
        FileTime pinned = FileTime.fromMillis(1_500_000_000_000L);
        Files.setLastModifiedTime(match, pinned);
        Map<String, DoorProbe.Result> byTerm = new HashMap<>();
        byTerm.put(PASTE_ID_A, adoptListing());
        List<String> prints = new ArrayList<>();
        JoinBrowserUi.DoorCall real = JoinBrowserUi.doorCall;
        JoinBrowserUi.doorCall = termDoor(row, prints, byTerm);
        try {
            JoinBrowserUi ui = JoinBrowserUi.forSelftest(modDir, new JoinSettings("", "", "", "", "", true, "", ""));
            String served = ui.probe("127.0.0.1", "7777", "pw-1pass");
            JoinLaunchGates.ProbeOutcome last = ui.lastProbe();
            check(prints.size() == 1 && PASTE_ID_A.equals(term(prints.get(0))) && served.contains("\"docOk\":true")
                    && served.contains("\"staged\":null") && served.contains("\"docLine\":\"\"")
                    && last.docOk && last.cacheCurrent,
                    row + ": the cached id the server confirms is docOk + cacheCurrent with nothing staged (flips if"
                            + " the cached id is not presented, or a current cache reads as missing or staged)");
            String launched = ui.launch("127.0.0.1", "7777", "", "pw-1pass", "", true, null);
            check(launched.contains("\"ok\":true") && launched.contains("\"launched\":true")
                    && Arrays.equals(kitFile, Files.readAllBytes(match))
                    && pinned.equals(Files.getLastModifiedTime(match)),
                    row + ": Launch on a current cache is ready and leaves match.txt byte-identical, unwritten (flips"
                            + " if the gate demands a document or the hand-off rewrites the cached file)");
        } finally {
            JoinBrowserUi.doorCall = real;
        }
    }

    /**
     * Row {@code adopt-once-per-probe}: the confirming re-probe's reply carries ANOTHER lawful document (Y) — it is
     * not adopted: one adopt per probe, so the stage stays the first (X), no third door call, and Launch writes X.
     *
     */
    private static void selftestAdoptOncePerProbe() throws IOException {
        String row = "adopt-once-per-probe";
        Path modDir = freshKitFolder(row);
        String first = adoptDocument(ADOPT_ID_X);
        DoorProbe.Result listing = adoptListing();
        Map<String, DoorProbe.Result> byTerm = new HashMap<>();
        byTerm.put(ContentFingerprints.MATCH_NONE, listing.withDocument(first, true, null));
        byTerm.put(ADOPT_ID_X, listing.withDocument(adoptDocument(ADOPT_ID_Y), true, null));
        List<String> prints = new ArrayList<>();
        JoinBrowserUi.DoorCall real = JoinBrowserUi.doorCall;
        JoinBrowserUi.doorCall = termDoor(row, prints, byTerm);
        try {
            JoinBrowserUi ui = JoinBrowserUi.forSelftest(modDir, new JoinSettings("", "", "", "", "", true, "", ""));
            String served = ui.probe("127.0.0.1", "7777", "pw-1pass");
            check(prints.size() == 2 && served.contains("\"docOk\":true")
                    && served.contains("\"staged\":{\"matchId\":" + quoted(ADOPT_ID_X) + ","),
                    row + ": the re-probe's own document is not adopted, the stage stays the first (flips if the"
                            + " confirming reply re-stages or chains another re-probe)");
            String launched = ui.launch("127.0.0.1", "7777", "", "pw-1pass", "", true, null);
            check(launched.contains("\"ok\":true") && Arrays.equals(
                    JoinLaunchPlan.canonicalBytes(JoinLaunchPlan.parseDescriptor(first)),
                    Files.readAllBytes(modDir.resolve("match.txt"))),
                    row + ": Launch writes the first document (flips if the second document replaced the stage)");
        } finally {
            JoinBrowserUi.doorCall = real;
        }
    }

    /** A fresh kit extract under a new temp install: the file list, its one data file and a stand-in jar, and NO
     *  {@code match.txt} (the folder a first join holds). */
    private static Path freshKitFolder(String row) throws IOException {
        Path modDir = Files.createTempDirectory("cc-join-" + row).resolve("install").resolve("mods").resolve("cc");
        Files.createDirectories(modDir.resolve("jars"));
        Files.createDirectories(modDir.resolve("data"));
        Files.write(modDir.resolve("kit.files"), "file=data/x.csv\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(modDir.resolve("data").resolve("x.csv"), "x\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(modDir.resolve("jars").resolve("cc-client-mod.jar"), new byte[] {1, 2, 3});
        return modDir;
    }

    /** The selftest descriptor as a server sends it (seat tokens = the strict placeholders) with {@code match.id}
     *  = {@code id}. */
    private static String adoptDocument(String id) {
        return JoinGuiSelftest.SELFTEST_DESCRIPTOR
                .replace("seat.0.token=00112233445566778899aabbccddeeff", "seat.0.token=REDACTED-seat1")
                .replace("seat.1.token=ffeeddccbbaa99887766554433221100", "seat.1.token=REDACTED-seat2")
                .replace("match.id=" + PASTE_ID_A, "match.id=" + id);
    }

    /** A served listing with no mod set published (the mods gate passes). */
    private static DoorProbe.Result adoptListing() {
        return DoorProbe.Result.served(List.of("selftest_ship"), 0, 1, null);
    }

    /** The adopt rows' fake door: records every print and answers by its {@code match=} term; a term the row does
     *  not serve fails the row (the probe presented an id it should not). */
    private static JoinBrowserUi.DoorCall termDoor(String row, List<String> prints,
            Map<String, DoorProbe.Result> byTerm) {
        return (host, port, password, clientId, fingerprint) -> {
            prints.add(fingerprint);
            DoorProbe.Result answer = byTerm.get(term(fingerprint));
            check("127.0.0.1".equals(host) && answer != null,
                    row + ": the door was asked with a pair or a match term the row never serves (flips if the probe"
                            + " presents the wrong id or dials another pair)");
            return answer;
        };
    }

    /** A print's {@code match=} term, or null. */
    private static String term(String print) {
        return ContentFingerprints.componentOrNull(print, "match");
    }

    /** {@code s} as the window's JSON renders a string value. */
    private static String quoted(String s) {
        StringBuilder out = new StringBuilder();
        JoinJson.quote(out, s);
        return out.toString();
    }

    /** The paste rows' shared fixture — A = the kit file's
     *  {@code match.id}, B = the pasted credential's. Fixture values only (no real token, no password). */
    private static final String PASTE_ID_A = "1v1-selftest0000000";
    private static final String PASTE_ID_B = "1v1-pastereprobe0b";

    /** The selftest credential with its seat tokens redacted and {@code match.id} = {@code id}. */
    private static String pasteCredential(String id) {
        return JoinGuiSelftest.SELFTEST_DESCRIPTOR
                .replace("seat.0.token=00112233445566778899aabbccddeeff", "seat.0.token=REDACTED-0")
                .replace("seat.1.token=ffeeddccbbaa99887766554433221100", "seat.1.token=REDACTED-1")
                .replace("match.id=" + PASTE_ID_A, "match.id=" + id);
    }

    /** A fake kit folder under a fresh temp install: match.txt = A, a one-file sidecar, a stand-in jar. */
    private static Path pasteKitFolder(String row) throws IOException {
        Path modDir = Files.createTempDirectory("cc-join-" + row).resolve("install").resolve("mods").resolve("cc");
        Files.createDirectories(modDir.resolve("jars"));
        Files.createDirectories(modDir.resolve("data"));
        String a = pasteCredential(PASTE_ID_A);
        check(!a.equals(pasteCredential(PASTE_ID_B)) && a.contains("match.id=" + PASTE_ID_A),
                "SETUP-" + row + ": the A/B fixture differs by match.id");
        Files.write(modDir.resolve("match.txt"), a.getBytes(StandardCharsets.US_ASCII));
        Files.write(modDir.resolve("kit.files"), "file=data/x.csv\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(modDir.resolve("data").resolve("x.csv"), "x\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(modDir.resolve("jars").resolve("cc-client-mod.jar"), new byte[] {1, 2, 3});
        return modDir;
    }

    /** The fake door: records every print it is asked with; serves ONLY {@code match=B} (one published mod this
     *  install lacks), refuses everything else as {@code content_fingerprint_mismatch}. */
    private static JoinBrowserUi.DoorCall pasteDoor(List<String> prints) {
        return (host, port, password, clientId, fingerprint) -> {
            prints.add(fingerprint);
            return fingerprint.endsWith(";match=" + PASTE_ID_B)
                    ? DoorProbe.Result.served(List.of("selftest_ship"), 0, 1, null,
                            List.of(new DoorProbe.ModRow("selftest_mod_absent", "1.0", 0L)), 1, true)
                    : DoorProbe.Result.rejected("content_fingerprint_mismatch");
        };
    }

    /** A COMMITTED Swing fetcher on unshown fields for 127.0.0.1:7777 (the password remembered for that pair, saved
     *  at open) over {@code modDir}; the row reads {@code refresh} + {@code status}. */
    private static ShipListFetcher swingFetcher(Path modDir, JTextField port, JPasswordField password, JButton refresh,
            JLabel status) {
        return new ShipListFetcher(modDir, null, new JTextField("127.0.0.1"), port, password,
                new JComboBox<String>(), refresh, status, true,
                new JoinSettings("127.0.0.1", "7777", "", "pw-1pass", "", true, "", "127.0.0.1:7777"));
    }

    /**
     * Row {@code swing-fetch-stages}: the REAL {@link ShipListFetcher} (unshown fields, its {@link ShipListFetcher#doorCall}
     * seam swapped for the adopt rows' fake door, restored in {@code finally}) over a fresh kit folder: the served
     * lawful document is adopted — staged for its pair, the fetched line on the status, exactly ONE confirming re-probe
     * presenting the adopted id, the outcome served + docOk on the stage, nothing written — then the Launch verdict is
     * ready on that document and the one writer saves its canonical bytes. Every entry runs on the EDT; a probe leg
     * waits (bounded) for the door calls AND the landed result. Fixture values only; no message carries a print, a
     * password or the document.
     */
    private static void selftestSwingFetchStages() throws IOException {
        String row = "swing-fetch-stages";
        Path modDir = freshKitFolder(row);
        String document = adoptDocument(ADOPT_ID_X);
        DoorProbe.Result listing = adoptListing();
        Map<String, DoorProbe.Result> byTerm = new HashMap<>();
        byTerm.put(ContentFingerprints.MATCH_NONE, listing.withDocument(document, true, null));
        byTerm.put(ADOPT_ID_X, listing);
        List<String> prints = Collections.synchronizedList(new ArrayList<>());
        JoinBrowserUi.DoorCall real = ShipListFetcher.doorCall;
        ShipListFetcher.doorCall = termDoor(row, prints, byTerm);
        JButton refresh = new JButton("Refresh list");
        JLabel status = new JLabel();
        ShipListFetcher fetcher = swingFetcher(modDir, new JTextField("7777"), new JPasswordField(), refresh, status);
        try {
            check(startsProbe(row, refresh, fetcher::fetchNow), "SETUP-" + row + ": committed probeable fields probe");
            awaitProbe(row, prints, 2, refresh);
            Object[] seen = new Object[3];
            onEdt(row, () -> {
                seen[0] = status.getText();
                seen[1] = fetcher.lastProbe();
                seen[2] = fetcher.stagedDocumentFor("127.0.0.1", "7777");
            });
            JoinLaunchGates.ProbeOutcome last = (JoinLaunchGates.ProbeOutcome) seen[1];
            check(prints.size() == 2 && ContentFingerprints.MATCH_NONE.equals(term(prints.get(0)))
                    && ADOPT_ID_X.equals(term(prints.get(1))),
                    row + ": the Swing probe presents match=none, then ONE confirming re-probe presents the adopted id"
                            + " (flips if the Swing path stops adopting, re-probes twice, or presents the old term)");
            check(String.valueOf(seen[0]).contains(ADOPT_FETCHED_LINE) && document.equals(seen[2])
                    && last.kind == JoinLaunchGates.ProbeOutcome.Kind.SERVED && last.docOk && !last.cacheCurrent
                    && ADOPT_FETCHED_LINE.equals(last.docLine),
                    row + ": the status shows the fetched line, the served text is staged for its pair, the outcome is"
                            + " served + docOk on the stage (flips if the fetcher stops staging or recording its probe)");
            check(!Files.exists(modDir.resolve("match.txt")),
                    row + ": the probe writes nothing (flips if the Swing adopt saves the document before Launch)");
            JoinLaunchGates.Verdict[] verdict = new JoinLaunchGates.Verdict[1];
            onEdt(row, () -> verdict[0] = fetcher.launchVerdict("", "", true));
            check(verdict[0].ready != null && verdict[0].ready.pasted != null
                    && ADOPT_ID_X.equals(verdict[0].ready.pasted.matchId()),
                    row + ": Launch is ready on the staged match (flips if the Swing Launch reads another document or"
                            + " the outcome alone)");
            JoinLaunchGates.Handoff handoff = JoinLaunchGates.commit(verdict[0].ready, modDir,
                    modDir.resolve("selftest-plan.txt"), modDir.resolve("selftest-settings.properties"), null, true, null);
            check(handoff.refusal == null && Arrays.equals(
                    JoinLaunchPlan.canonicalBytes(JoinLaunchPlan.parseDescriptor(document)),
                    Files.readAllBytes(modDir.resolve("match.txt"))),
                    row + ": the one writer saves the staged document as the codec's canonical bytes (flips if the"
                            + " hand-off writes other bytes or nothing)");
        } finally {
            ShipListFetcher.doorCall = real;
            fetcher.stopDebounce();
        }
    }

    /**
     * Row {@code swing-fetch-refused}: the served document claims {@code none} as its id, so the id law refuses it —
     * the refused line on the status, nothing staged, NO confirming re-probe, the outcome served without docOk, and
     * the Launch verdict refused at the {@code document} gate with that same line, nothing written. The REAL fetcher
     * over the adopt rows' fake door.
     */
    private static void selftestSwingFetchRefused() throws IOException {
        String row = "swing-fetch-refused";
        Path modDir = freshKitFolder(row);
        String refused = JoinLaunchGates.refusedLine("match_doc_out_of_range match.id");
        Map<String, DoorProbe.Result> byTerm = new HashMap<>();
        byTerm.put(ContentFingerprints.MATCH_NONE,
                adoptListing().withDocument(adoptDocument(ContentFingerprints.MATCH_NONE), true, null));
        List<String> prints = Collections.synchronizedList(new ArrayList<>());
        JoinBrowserUi.DoorCall real = ShipListFetcher.doorCall;
        ShipListFetcher.doorCall = termDoor(row, prints, byTerm);
        JButton refresh = new JButton("Refresh list");
        JLabel status = new JLabel();
        ShipListFetcher fetcher = swingFetcher(modDir, new JTextField("7777"), new JPasswordField(), refresh, status);
        try {
            check(startsProbe(row, refresh, fetcher::fetchNow), "SETUP-" + row + ": committed probeable fields probe");
            awaitProbe(row, prints, 1, refresh);
            Object[] seen = new Object[4];
            onEdt(row, () -> {
                seen[0] = status.getText();
                seen[1] = fetcher.lastProbe();
                seen[2] = fetcher.stagedDocumentFor("127.0.0.1", "7777");
                seen[3] = fetcher.launchVerdict("", "", true);
            });
            JoinLaunchGates.ProbeOutcome last = (JoinLaunchGates.ProbeOutcome) seen[1];
            JoinLaunchGates.Verdict verdict = (JoinLaunchGates.Verdict) seen[3];
            check(prints.size() == 1,
                    row + ": a refused document gets no confirming re-probe (flips if the Swing refusal re-probes)");
            check(String.valueOf(seen[0]).contains(refused) && "".equals(seen[2])
                    && last.kind == JoinLaunchGates.ProbeOutcome.Kind.SERVED && !last.docOk && !last.cacheCurrent
                    && refused.equals(last.docLine),
                    row + ": the refused line shows and nothing is staged (flips if the Swing path stages a refused"
                            + " document or drops its line)");
            check(verdict.refusal != null && "document".equals(verdict.refusal.gate)
                    && JoinLaunchGates.TITLE_MATCH.equals(verdict.refusal.title) && refused.equals(verdict.refusal.message)
                    && !Files.exists(modDir.resolve("match.txt")),
                    row + ": Launch is refused at the document gate with the refused line, nothing written (flips if a"
                            + " refused document launches or another line answers)");
        } finally {
            ShipListFetcher.doorCall = real;
            fetcher.stopDebounce();
        }
    }

    /**
     * Row {@code swing-launch-last-outcome}: a folder holding a cached {@code match.txt} (A) is no launch ticket by
     * itself any more — (i) before any probe the Launch verdict is the no-answer line at the {@code probe} gate; (ii)
     * after the door refuses the folder's print, Launch shows THAT refusal's own line at the {@code door} gate (the
     * status line, word for word); (iii) a probe the server answers with no document on A (the cache is current)
     * launches on the cached file, nothing to write. The REAL fetcher, its door seam swapped per leg (no fetch in
     * flight at a swap).
     */
    private static void selftestSwingLaunchLastOutcome() throws IOException {
        String row = "swing-launch-last-outcome";
        Path modDir = pasteKitFolder(row);
        List<String> prints = Collections.synchronizedList(new ArrayList<>());
        JoinBrowserUi.DoorCall real = ShipListFetcher.doorCall;
        ShipListFetcher.doorCall = pasteDoor(prints);
        JButton refresh = new JButton("Refresh list");
        JLabel status = new JLabel();
        ShipListFetcher fetcher = swingFetcher(modDir, new JTextField("7777"), new JPasswordField(), refresh, status);
        JoinLaunchGates.Verdict[] verdict = new JoinLaunchGates.Verdict[1];
        try {
            onEdt(row, () -> verdict[0] = fetcher.launchVerdict("", "", true));
            check(verdict[0].refusal != null && "probe".equals(verdict[0].refusal.gate)
                    && JoinLaunchGates.MSG_NO_ANSWER.equals(verdict[0].refusal.message),
                    row + ": (i) before any probe Launch says no answer yet (flips if the Swing Launch goes on the cached"
                            + " file's presence again)");
            check(startsProbe(row, refresh, fetcher::fetchNow), "SETUP-" + row + ": committed probeable fields probe");
            awaitProbe(row, prints, 1, refresh);
            String[] line = new String[1];
            onEdt(row, () -> {
                line[0] = status.getText();
                verdict[0] = fetcher.launchVerdict("", "", true);
            });
            String kitDiffers = "Ship list: " + JoinLaunchGates.MSG_KIT_DIFFERS;
            check(verdict[0].refusal != null && "door".equals(verdict[0].refusal.gate)
                    && JoinLaunchGates.TITLE_MATCH.equals(verdict[0].refusal.title)
                    && kitDiffers.equals(verdict[0].refusal.message) && kitDiffers.equals(line[0]),
                    row + ": (ii) after a door refusal Launch shows that refusal's own line (flips if a generic line"
                            + " returns, or the cached file launches past the refusal)");
            Map<String, DoorProbe.Result> byTerm = new HashMap<>();
            byTerm.put(PASTE_ID_A, adoptListing());
            ShipListFetcher.doorCall = termDoor(row, prints, byTerm);
            check(startsProbe(row, refresh, fetcher::fetchNow), "SETUP-" + row + ": (iii) the re-probe starts");
            awaitProbe(row, prints, 2, refresh);
            JoinLaunchGates.ProbeOutcome[] last = new JoinLaunchGates.ProbeOutcome[1];
            onEdt(row, () -> {
                last[0] = fetcher.lastProbe();
                verdict[0] = fetcher.launchVerdict("", "", true);
            });
            check(last[0].cacheCurrent && verdict[0].ready != null && verdict[0].ready.pasted == null,
                    row + ": (iii) a cache-current probe launches on the cached file with nothing to write (flips if a"
                            + " current cache is refused or a document is demanded)");
        } finally {
            ShipListFetcher.doorCall = real;
            fetcher.stopDebounce();
        }
    }

    /**
     * Row {@code swing-paste-brick-only}: the Swing window's lower box is a connect-code box like the browser's — (i)
     * a pasted {@code address:port:password} fills the three fields and probes ONCE with them; (ii) a pasted match
     * document is refused with the browser's one line, counted and NOT staged (the next probe presents the cached
     * file's id); (iii) a text over the browser's paste cap is refused unparsed and counted; (iv) a blank box does
     * nothing; (v) a TYPED code fills the fields but never probes (its password is not committed). The REAL fetcher
     * over the paste fixture; the fake door records the port + the presented term (never the password).
     *
     */
    private static void selftestSwingPasteBrickOnly() throws IOException {
        String row = "swing-paste-brick-only";
        Path modDir = pasteKitFolder(row);
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        boolean[] brickPassword = new boolean[1];
        JoinBrowserUi.DoorCall real = ShipListFetcher.doorCall;
        ShipListFetcher.doorCall = (host, port, password, clientId, fingerprint) -> {
            brickPassword[0] = "pw-2pass".equals(password);
            asked.add(port + " " + term(fingerprint));
            return adoptListing();
        };
        JButton refresh = new JButton("Refresh list");
        JLabel status = new JLabel();
        JTextField portField = new JTextField("7777");
        JPasswordField passwordField = new JPasswordField();
        ShipListFetcher fetcher = swingFetcher(modDir, portField, passwordField, refresh, status);
        try {
            boolean[] filled = new boolean[1];
            check(startsProbe(row, refresh, () -> filled[0] = fetcher.pasteChanged("127.0.0.1:7778:pw-2pass", true)),
                    row + ": (i) a pasted connect code starts a probe (flips if the box stops probing a pasted code)");
            awaitProbe(row, asked, 1, refresh);
            String[] fields = new String[2];
            onEdt(row, () -> {
                fields[0] = portField.getText();
                fields[1] = new String(passwordField.getPassword());
            });
            check(filled[0] && "7778".equals(fields[0]) && "pw-2pass".equals(fields[1]) && asked.size() == 1
                    && asked.get(0).equals("7778 " + PASTE_ID_A) && brickPassword[0],
                    row + ": (i) the code fills the fields and the ONE probe asks its pair with its password (flips if"
                            + " the box stops filling the fields, probes twice, or probes the old pair)");
            int[] refusals = new int[2];
            onEdt(row, () -> refusals[0] = fetcher.pasteRefusals());
            boolean[] documentFilled = new boolean[1];
            boolean documentProbed = startsProbe(row, refresh,
                    () -> documentFilled[0] = fetcher.pasteChanged(pasteCredential(PASTE_ID_B), true));
            String[] after = new String[2];
            onEdt(row, () -> {
                refusals[1] = fetcher.pasteRefusals();
                after[0] = status.getText();
                after[1] = fetcher.stagedDocumentFor("127.0.0.1", "7778");
            });
            check(!documentProbed && !documentFilled[0] && refusals[1] == refusals[0] + 1
                    && JoinBrowserUi.MSG_NOT_A_CONNECT_CODE.equals(after[0]) && after[1].isEmpty(),
                    row + ": (ii) a pasted match document is refused with the one line, counted, unstaged, no probe"
                            + " (flips if the box parses or stages document text again)");
            check(startsProbe(row, refresh, fetcher::fetchNow), "SETUP-" + row + ": (ii) the next probe starts");
            awaitProbe(row, asked, 2, refresh);
            check(asked.size() == 2 && asked.get(1).equals("7778 " + PASTE_ID_A),
                    row + ": (ii) the probe after a pasted document presents the cached file's id (flips if the pasted"
                            + " document's id reaches the door)");
            onEdt(row, () -> refusals[0] = fetcher.pasteRefusals());
            boolean overCap = startsProbe(row, refresh,
                    () -> fetcher.pasteChanged("x".repeat(JoinRoutes.PASTE_MAX + 1), true));
            boolean blank = startsProbe(row, refresh, () -> fetcher.pasteChanged("  ", true));
            onEdt(row, () -> refusals[1] = fetcher.pasteRefusals());
            check(!overCap && !blank && asked.size() == 2 && refusals[1] == refusals[0] + 1,
                    row + ": (iii)/(iv) an over-cap text is refused unparsed and counted, a blank box does nothing (flips"
                            + " if the box loses the browser's paste cap, or a blank settle counts or probes)");
            boolean typed = startsProbe(row, refresh, () -> filled[0] = fetcher.pasteChanged("127.0.0.1:7779:pw-3", false));
            onEdt(row, () -> fields[0] = portField.getText());
            check(!typed && filled[0] && "7779".equals(fields[0]) && asked.size() == 2,
                    row + ": (v) a typed code fills the fields and probes nothing (flips if a typed code commits its"
                            + " half-typed password and probes)");
        } finally {
            ShipListFetcher.doorCall = real;
            fetcher.stopDebounce();
        }
    }

    /** Runs {@code entry} on the EDT and reports, from the SAME EDT task, whether it started a fetch (Refresh
     *  disabled — {@code fetchNow} disables it synchronously). Refresh must be enabled (nothing in flight) before. */
    private static boolean startsProbe(String row, JButton refresh, Runnable entry) {
        boolean[] started = new boolean[1];
        onEdt(row, () -> {
            entry.run();
            started[0] = !refresh.isEnabled();
        });
        return started[0];
    }

    /** Waits (bounded, 5 s) until the fake door has recorded {@code calls} prints AND the latest fetch's
     *  {@code apply} has landed (Refresh re-enabled on the EDT). */
    private static void awaitProbe(String row, List<String> prints, int calls, JButton refresh) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        boolean[] landed = new boolean[1];
        while (true) {
            onEdt(row, () -> landed[0] = refresh.isEnabled());
            if (prints.size() >= calls && landed[0]) {
                return;
            }
            check(System.nanoTime() < deadline, row + ": a started probe did not reach the door and land within 5 s"
                    + " (flips if the worker skips the doorCall seam or apply never lands)");
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                check(false, row + ": interrupted while waiting for a probe");
            }
        }
    }

    /** One EDT task, awaited; a throw inside it fails the row by name (only the exception's SimpleName). */
    private static void onEdt(String row, Runnable task) {
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            check(false, row + ": an EDT entry threw " + cause.getClass().getSimpleName());
        }
    }

    /**
     * Row {@code paste-re-probes}: a
     * probe refused on the kit file's id stays {@code probed} NONE, renders the kit-differs line and is recorded
     * REJECTED for the launch gate (which then refuses with that line instead of booting without the server's
     * mods); the paste box takes the connect code only, so a pasted match text is refused unparsed through the
     * REAL api/paste route and probes nothing, even with the committed pair riding (the match arrives with the
     * probe). The REAL browser session ({@link JoinBrowserUi#forSelftest}) over a fake kit folder (match.txt = A,
     * a one-file sidecar, a stand-in jar) and a fake door through the {@link JoinBrowserUi#doorCall} seam that
     * refuses every print but {@code match=B}, restored in {@code finally}. No socket, no window; fixture values
     * only — no check message carries a print or a password.
     *
     */
    private static void selftestPasteReprobes() throws IOException {
        Path modDir = pasteKitFolder("paste-re-probes");
        String idA = PASTE_ID_A;
        String b = pasteCredential(PASTE_ID_B);
        List<String> prints = Collections.synchronizedList(new ArrayList<>());
        JoinBrowserUi.DoorCall real = JoinBrowserUi.doorCall;
        JoinBrowserUi.doorCall = pasteDoor(prints);
        try {
            JoinBrowserUi ui = JoinBrowserUi.forSelftest(modDir,
                    new JoinSettings("", "", "", "", "", true, "", ""));
            // the refused first probe: the kit file's id (A) -> rejected, NONE, the paste hint on the line
            String first = ui.probe("127.0.0.1", "7777", "pw-1pass");
            check(prints.size() == 1 && prints.get(0).startsWith("cc1;") && prints.get(0).endsWith(";match=" + idA),
                    "paste-re-probes: the first probe prints the kit file's id (flips if the fixture print is blank"
                            + " or the unstaged print stops reading match.txt)");
            // The window's line for a content-print refusal is the
            // kit-differs sentence, recorded for the launch gate; the box takes the connect code only
            check(first.contains("\"status\":\"rejected\"") && first.contains(JoinLaunchGates.MSG_KIT_DIFFERS)
                    && ui.probed() == JoinLaunchGates.Probed.NONE
                    && ui.lastProbe().kind == JoinLaunchGates.ProbeOutcome.Kind.REJECTED,
                    "paste-re-probes: a refused probe stays NONE, reads the kit-differs line and is recorded REJECTED"
                            + " (flips if the line or the launch-state record is dropped)");
            // through the REAL api/paste route with the committed pair riding: still refused, still no probe
            String garbled = postPaste(new JoinRoutes(ui), "text=" + URLEncoder.encode(b, StandardCharsets.UTF_8)
                    + "&host=127.0.0.1&port=7777&password=pw-1pass");
            check(prints.size() == 1 && garbled.contains(JoinBrowserUi.MSG_NOT_A_CONNECT_CODE)
                    && !garbled.contains("\"probe\"") && !garbled.contains("match.id"),
                    "paste-re-probes: a pasted match text is refused unparsed and probes nothing (flips if the"
                            + " credential half comes back)");
        } finally {
            JoinBrowserUi.doorCall = real;
        }
    }

    /** One loopback POST {@code /<token>/api/paste} through {@link JoinRoutes#handle} (in memory, no socket):
     *  the whole reply (status line + headers + JSON body) as text. */
    private static String postPaste(JoinRoutes routes, String form) throws IOException {
        byte[] body = form.getBytes(StandardCharsets.UTF_8);
        String head = "POST /tok/api/paste HTTP/1.1\r\nHost: 127.0.0.1:7777\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: " + body.length + "\r\n\r\n";
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(head.getBytes(StandardCharsets.US_ASCII));
        raw.write(body);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        routes.handle(HttpExchange.read(new ByteArrayInputStream(raw.toByteArray())), out, "tok", 7777);
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * The remembered password is BOUND to the host:port it was saved for —
     * {@link JoinSettings#rememberedPasswordFor} is its one read, and the Swing fetcher resolves an empty field at
     * each use against the fields' CURRENT pair. In memory: the settings bytes round-trip through {@code store} +
     * {@code fromProperties}; the fetcher rows run on unshown fields with no listener attached (no Timer starts).
     * Fixture passwords only — no check message carries a value.
     */
    private static void selftestPasswordBinding() throws IOException {
        // R1: the binding persists, in the on-disk form the arm tooling reads and writes (Properties escapes ':').
        // The bound pair is NOT the fixture's own host/port rows: a load that ignored the
        // row would fall to the legacy rule and still read the file's own pair — this fixture flips on it.
        JoinSettings s = new JoinSettings("last.example", "7777", "Pilot Two", "match-pass-1", "", true, "",
                "vpn.example:51820");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        s.store(bytes);
        Properties props = new Properties();
        props.load(new ByteArrayInputStream(bytes.toByteArray()));
        JoinSettings back = JoinSettings.fromProperties(props);
        check("vpn.example:51820".equals(back.passwordFor) && bytes.toString(StandardCharsets.ISO_8859_1)
                .contains("\npasswordFor=vpn.example\\:51820" + System.lineSeparator()),
                "binding R1: settings round-trip carries passwordFor (flips if store drops the row or load ignores it)");
        // R2: the bound pair resolves through trim + host case-fold
        check("match-pass-1".equals(back.rememberedPasswordFor(" VPN.Example ", " 51820 ")),
                "binding R2: the bound pair resolves (flips if the compare is raw or case-sensitive)");
        // R2b: the STORED side folds too - a hand-typed or arm-written pair in
        // upper case still binds (R2's fixture stores lower case only, so it cannot see the stored-side fold)
        JoinSettings upper = new JoinSettings("", "", "", "upper-pass-1", "", true, "", "VPN.Example:51820");
        check("upper-pass-1".equals(upper.rememberedPasswordFor("vpn.example", "51820")),
                "binding R2b: a stored upper-case pair resolves (flips if the stored side's foldAscii is dropped)");
        // R3: every other pair (the file's OWN host/port rows included), a blank half, a non-ASCII spelling that
        // only a Unicode case fold maps onto the pair (U+212A KELVIN SIGN lower-cases to k), resolves blank
        String kelvin = "vpn.e" + (char) 0x212A + "ample";
        JoinSettings kBound = new JoinSettings("", "", "", "kelvin-pass-1", "", true, "", "vpn.ekample:51820");
        String[][] others = {{"other.example", "51820"}, {"vpn.example", "51821"}, {"", "51820"},
                {"vpn.example", " "}, {null, null}, {"last.example", "7777"}};
        for (String[] pair : others) {
            check(back.rememberedPasswordFor(pair[0], pair[1]).isEmpty(),
                    "binding R3: an unbound pair resolves blank (flips if the password is offered to any host)");
        }
        check(kBound.rememberedPasswordFor(kelvin, "51820").isEmpty()
                && "kelvin-pass-1".equals(kBound.rememberedPasswordFor("VPN.EKAMPLE", "51820")),
                "binding R3: the case fold is A-Z only (flips on a Unicode toLowerCase - KELVIN SIGN would match k)");
        JoinSettings loop = new JoinSettings("localhost", "7777", "", "loop-pass-1", "", true, "", "localhost:7777");
        check(loop.rememberedPasswordFor("127.0.0.1", "7777").isEmpty()
                && loop.rememberedPasswordFor("localhost", "07777").isEmpty(),
                "binding R3: another spelling of the same server retypes (flips on a DNS or number-parse match)");
        // R4: a LEGACY file (no passwordFor row) binds to its OWN host/port rows
        Properties legacy = new Properties();
        legacy.setProperty("host", " Old.Example ");
        legacy.setProperty("port", "7777");
        legacy.setProperty("password", "legacy-pass-1");
        JoinSettings old = JoinSettings.fromProperties(legacy);
        check("legacy-pass-1".equals(old.rememberedPasswordFor("old.example", "7777"))
                && old.rememberedPasswordFor("new.example", "7777").isEmpty(),
                "binding R4: a legacy file binds to its own host/port (flips if legacy reads as any-host or never-bound)");
        // R6 / R7: the Swing fetcher on unshown fields, remembered bound to 127.0.0.1:7777, the password field EMPTY
        JTextField host = new JTextField("127.0.0.1");
        JTextField port = new JTextField("7777");
        JPasswordField password = new JPasswordField();
        JoinSettings remembered = new JoinSettings("127.0.0.1", "7777", "", "bound-pass-1", "", true, "",
                "127.0.0.1:7777");
        ShipListFetcher fetcher = new ShipListFetcher(Paths.get("selftest-unused-moddir"), null,
                host, port, password, new JComboBox<String>(), new JButton("Refresh list"), new JLabel(), true,
                remembered);
        try {
            check("bound-pass-1".equals(fetcher.effectivePassword()),
                    "binding R7: an empty field resolves the password remembered for the bound pair (flips if"
                            + " resolution is missing - the probe and the Launch would carry nothing)");
            password.setText(" \"typed-pass-1\" ");
            check("typed-pass-1".equals(fetcher.effectivePassword()),
                    "binding R7: a typed password wins, sanitized (flips if the remembered one overrides the field)");
            password.setText("");
            host.setText("10.9.9.9");
            check(fetcher.effectivePassword().isEmpty(),
                    "binding R6: a host edit resolves blank (flips if the fetcher falls back unbound, or resolves at"
                            + " open instead of at each use)");
        } finally {
            fetcher.stopDebounce();
        }
    }

    /**
     * The HEADLESS ShipListFetcher pin for rider r6 — the real
     * fetcher's own Document listeners on unshown fields (no window, no display): (i) a password keystroke leaves
     * the 700 ms debounce stopped; (ii) a host keystroke starts it. The Timer is stopped in {@code finally} so no
     * live Timer outlives the battery.
     */
    private static void selftestProbeCommitFetcher() {
        JTextField host = new JTextField();
        JTextField port = new JTextField();
        JPasswordField password = new JPasswordField();
        JButton refresh = new JButton("Refresh list");
        ShipListFetcher fetcher = new ShipListFetcher(Paths.get("selftest-unused-moddir"), null,
                host, port, password, new JComboBox<String>(), refresh, new JLabel(), false, null);
        host.getDocument().addDocumentListener(fetcher.debouncedListener());
        port.getDocument().addDocumentListener(fetcher.debouncedListener());
        password.getDocument().addDocumentListener(fetcher.passwordListener());
        try {
            check(!fetcher.debounceRunning(), "probe commit fetcher: a fresh fetcher has no debounce running");
            password.setText("s");
            password.setText("se");
            check(!fetcher.debounceRunning(), "probe commit fetcher: a password keystroke starts no debounce");
            host.setText("h");
            check(fetcher.debounceRunning(), "probe commit fetcher: a host keystroke starts the debounce");
        } finally {
            fetcher.stopDebounce();
        }
        check(!fetcher.debounceRunning(), "probe commit fetcher: the debounce is stopped at teardown");
    }

    /**
     * Rider r6's commit table — the ship-list probe fires on a password
     * COMMIT, never per keystroke. A probe = a {@code true} from a commit call or a debounce fire; the four
     * commit-table rows + "saved password at open ⇒ committed" + the focus-loss and blocked-line rows.
     */
    private static void selftestProbeCommit() {
        // row 1: typing → no probe (keystrokes schedule nothing; a pending host debounce fires blocked)
        ProbeCommitGate g = new ProbeCommitGate(false);
        int probes = 0;
        // The vacuous 'probes == 0' after this loop is gone
        // (nothing in it could count); the in-loop assertion is the row-1 pin
        for (int i = 0; i < 12; i++) {
            g.passwordEdited();
            check(!g.debouncePending() && !g.probeAllowed(), "probe commit: a password keystroke schedules nothing");
        }
        // row 2: Enter → exactly one
        probes += g.passwordEnter() ? 1 : 0;
        check(probes == 1 && g.probeAllowed() && !g.debouncePending(), "probe commit: Enter -> one probe");
        // row 3: a host edit after a commit → one (the debounce fire probes)
        g.otherFieldEdited();
        check(g.debouncePending(), "probe commit: a host edit arms the debounce");
        probes += g.debounceFired() ? 1 : 0;
        check(probes == 2 && !g.debouncePending(), "probe commit: host edit after a commit -> one probe");
        // row 4: a host edit after a keystroke → none, with the press-Enter status
        g.passwordEdited();
        g.otherFieldEdited();
        probes += g.debounceFired() ? 1 : 0;
        check(probes == 2 && !g.probeAllowed(), "probe commit: host edit after a keystroke -> no probe");
        check(ProbeCommitGate.BLOCKED_STATUS.equals(g.blockedStatusLine())
                && g.blockedStatusLine().contains("press Enter"), "probe commit: the blocked status line");
        // focus loss: commits once after an edit, then nothing on an untouched tab-through
        probes += g.passwordFocusLost() ? 1 : 0;
        check(probes == 3 && g.probeAllowed(), "probe commit: focus loss after an edit -> one probe");
        probes += g.passwordFocusLost() ? 1 : 0;
        check(probes == 3, "probe commit: focus loss with no edit since the commit -> no probe");
        // ONE Enter law with the web — Refresh re-probes; a
        // repeat Enter on an unchanged committed field does NOT; an edit then Enter does
        probes += g.passwordCommitted() ? 1 : 0;
        check(probes == 4, "probe commit: Refresh re-probes");
        probes += g.passwordEnter() ? 1 : 0;
        probes += g.passwordEnter() ? 1 : 0;
        check(probes == 4 && g.probeAllowed(), "probe commit: a repeat Enter on an unchanged field -> no probe");
        g.passwordEdited();
        probes += g.passwordEnter() ? 1 : 0;
        check(probes == 5 && g.probeAllowed(), "probe commit: an edit then Enter -> one probe");
        check(ProbeCommitGate.ENTER_UNCHANGED_STATUS.length() <= 60
                && ProbeCommitGate.ENTER_UNCHANGED_STATUS.contains("Launch"), "probe commit: the unchanged-Enter hint");
        // saved password at open ⇒ committed (a host edit probes with no typing); none at open ⇒ uncommitted
        ProbeCommitGate saved = new ProbeCommitGate(true);
        saved.otherFieldEdited();
        check(saved.probeAllowed() && saved.debounceFired(), "probe commit: saved password at open -> committed");
        check(!saved.passwordFocusLost(), "probe commit: focus loss on an untyped saved password -> no probe");
        check(!saved.passwordEnter(), "probe commit: Enter on an untyped saved password -> no probe");   // Enter with nothing typed
        ProbeCommitGate blank = new ProbeCommitGate(false);
        blank.otherFieldEdited();
        check(!blank.probeAllowed() && !blank.debounceFired(), "probe commit: no saved password -> uncommitted at open");
    }
}
