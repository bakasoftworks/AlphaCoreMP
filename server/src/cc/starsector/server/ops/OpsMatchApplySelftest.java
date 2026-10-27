package cc.starsector.server.ops;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import cc.starsector.server.match.ContentFingerprintClientSurface;
import cc.starsector.server.match.ContentFingerprintMint;
import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.MatchPasswords;
import cc.starsector.server.ops.OpsMatchApply.ApplyRefused;
import cc.starsector.server.ops.OpsMatchApply.ApplyResult;
import cc.starsector.server.ops.OpsMatchApply.Op;
import cc.starsector.server.runtime.HandshakeCodec;

/**
 * The package match-setting Apply rows:
 * {@link OpsMatchApply#applyPackageMatchSetting}
 * driven against a temp PACKAGE per row - {@code match/} holding TWO redacted shapes (a stationless 1v1 and a
 * stationed fortress, each at non-default half / CR / backdrop so a reset-to-default flips a row), each with a
 * {@code .fingerprint} sidecar minted over a fixture KIT tree by {@link ContentFingerprintMint#mintSidecarLines}
 * (the packer's composer), a {@code .password} per shape, a {@code -tokens} file, {@code serve-mods/CC_Client} with one
 * variant, {@code cc-server.properties} and a packed {@code MANIFEST.txt}. The fixture builder is this class's own
 * ({@link ServerOpsCoreSelftest}'s apply fixtures are inline, not reusable statics).
 *
 * <p>Each row prints {@code PASS <row>} or {@code FAIL <row>: <the regression that flipped it>}; a fixture that never
 * staged prints {@code FAIL SETUP-<row>: ...} (an ERROR for the mutant runner, never a kill). Temp dirs are deleted in
 * {@code finally}. Rows {@code pkg-sidecar-one-writer} and {@code pkg-redact-moved} read product SOURCE from the cwd
 * (the repo root; elsewhere they read SETUP) - a source scan never flips under the mutant runner: it
 * shadows classes, the tracked source stays pristine. Entry: {@link #run()} (chained by the ops gate) and
 * {@link #main} (exit 0 / 1; the mutant runner's entry).
 *
 *
 */
final class OpsMatchApplySelftest {

    private OpsMatchApplySelftest() {
    }

    private static int failures;

    private static final String PLAIN = "match-plain.txt";
    private static final String FORT = "match-fort.txt";
    private static final String TOKENS = "match-fort-tokens.txt";
    private static final byte[] JAR = {1, 2, 3, 4, 5};
    private static final String FIXTURE_PASSWORD = "password=fixture-pass-word-1\n";
    private static final String SIDECAR_SUFFIX = ".fingerprint";

    // The shipped texts, VERBATIM (the oracle the text row holds the core's constants to; placeholders in <>)
    private static final String S_CONFIRM_TITLE = "Match settings";
    // The changed note: one plain sentence, then when it lands and that nothing is sent
    //
    private static final String S_CHANGED = "<what> set to <value>. It applies from the next round, when you press"
            + " Start; nothing is sent now - every player's next Join or Refresh fetches the new match.";
    private static final String S_UNCHANGED = "<shape>: <what> is already <value>. Nothing changed; players' current"
            + " match still works.";
    private static final String S_REPAIRED = "<shape>: its two match files did not belong together; now they do. It"
            + " applies from the next round, when you press Start; nothing is sent now - every player's next Join or"
            + " Refresh fetches the new match.";
    private static final String S_R1 = "Stop the server first (Kill).";
    private static final String S_R2 = "<shape> has no station.";
    private static final String S_R3 = "Map size must be a whole number from 2000 to 60000 (got '<x>').";
    private static final String S_R4 = "The file match\\<name>.fingerprint is missing or damaged. Delete match\\<name> and"
            + " its .fingerprint and start again (a shipped shape is copied back; a shape you made: make it again with New"
            + " match shape). Your .password files are kept.";
    private static final String S_R5 = "This match file was made for another server version. Delete it and its"
            + " .fingerprint and start again (a shipped shape is copied back; a shape you made: make it again with New"
            + " match shape).";
    private static final String S_R6 = "Pick a match shape from the list.";
    private static final String S_R7 = "Writing the match files failed (<class>); the old files were kept. Press Apply"
            + " again before you Start.";
    private static final String S_R7B = "Writing the match files failed (<class>) and could not be undone: press Apply"
            + " again - Start refuses until it works.";
    private static final String S_R8 = "Deploy CR must be a whole number from 1 to 100 (got '<x>').";
    private static final String S_R9 = "Players must be a whole number from 1 to 255 (got '<x>').";
    private static final String S_R10 = "<shape> takes <min> to <max> players (got '<x>').";
    private static final String S_LAUNCHER = "the fingerprint file <path> belongs to another match text - press Apply"
            + " again on the console's Setup tab, or delete both match files and start again (a shipped shape is copied"
            + " back)";
    private static final String S_RIDER = "this server takes the map size from the match file, not the map.half row";
    /** The CR rider: the map-size sentence with the CR nouns. */
    private static final String S_RIDER_CR = "this server takes the deploy CR from the match file, not the"
            + " match.deployCr row";

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** The ops gate's entry: every row, the summary line, the failure count returned. */
    static int run() {
        failures = 0;
        row("pkg-apply-size", dir -> applyChanged(dir, Op.SIZE, PLAIN, Paths.get("match", PLAIN), "8000", "8000",
                (before, after, r) -> {
                    require(after.halfWidth() == 8000f && after.halfHeight() == 8000f && "8000".equals(r.settings().half()),
                            "the half is " + after.halfWidth() + "/" + after.halfHeight() + ", want 8000 - flips if the"
                                    + " SIZE value is not threaded into the re-mint");
                    requireThreaded(before, after, false, true, true, true);
                }));
        row("pkg-apply-station", dir -> applyChanged(dir, Op.STATION, FORT, null, "HighTech", "hightech",
                (before, after, r) -> {
                    require("hightech".equals(family(after)) && "hightech".equals(r.settings().station()),
                            "the station family is " + family(after) + ", want hightech - flips if the STATION value"
                                    + " is not folded (case) or not threaded into the re-mint");
                    requireThreaded(before, after, true, false, true, true);
                }));
        row("pkg-apply-backdrop", dir -> applyChanged(dir, Op.BACKDROP, FORT, null, " Nebula ", "nebula",
                (before, after, r) -> {
                    require("nebula".equals(after.terrain()) && "nebula".equals(r.settings().backdrop()),
                            "the backdrop is " + after.terrain() + ", want nebula - flips if the BACKDROP word is not"
                                    + " folded or not threaded into the re-mint");
                    requireThreaded(before, after, true, true, false, true);
                }));
        row("pkg-apply-cr", dir -> applyChanged(dir, Op.DEPLOY_CR, PLAIN, null, "55", "55%",
                (before, after, r) -> {
                    require(after.deployCr() == 0.55f && "55".equals(r.settings().crPercent()),
                            "the deploy CR is " + after.deployCr() + ", want 0.55 - flips if the percent is not"
                                    + " pct/100f or not threaded into the re-mint");
                    requireThreaded(before, after, true, true, true, false);
                }));
        row("pkg-sidecar-rematch", OpsMatchApplySelftest::sidecarRematch);
        row("pkg-stripped-kit-gap", OpsMatchApplySelftest::strippedKitGap);
        row("pkg-nochange", OpsMatchApplySelftest::noChange);
        row("pkg-cr-percent-equal", OpsMatchApplySelftest::crPercentEqual);
        row("pkg-pair-torn-repairs", OpsMatchApplySelftest::pairTornRepairs);
        row("pkg-sidecar-first-rollback", OpsMatchApplySelftest::sidecarFirstRollback);
        row("pkg-write-nothing-refusals", OpsMatchApplySelftest::writeNothingRefusals);
        row("pkg-sidecar-one-writer", OpsMatchApplySelftest::sidecarOneWriter);
        row("pkg-redact-moved", OpsMatchApplySelftest::redactMoved);
        row("pkg-apply-texts", OpsMatchApplySelftest::applyTexts);
        row("pkg-prefill-standing", OpsMatchApplySelftest::prefillStanding);
        row("parse-players", OpsMatchApplySelftest::parsePlayers);
        row("pkg-apply-keeps-players", OpsMatchApplySelftest::pkgApplyKeepsPlayers);
        row("pkg-apply-players", OpsMatchApplySelftest::pkgApplyPlayers);
        row("pkg-apply-players-refused", OpsMatchApplySelftest::pkgApplyPlayersRefused);
        row("pkg-apply-players-unchanged", OpsMatchApplySelftest::pkgApplyPlayersUnchanged);
        row("pkg-apply-players-live", OpsMatchApplySelftest::pkgApplyPlayersLive);
        row("owner-apply-keeps-players", OpsMatchApplySelftest::ownerApplyKeepsPlayers);
        row("apply-props-locked", OpsMatchApplySelftest::applyPropsLocked);
        row("pkg-prefill-overcap", OpsMatchApplySelftest::prefillOverCap);
        System.out.println(failures == 0 ? "OpsMatchApplySelftest: every row PASS"
                : "OpsMatchApplySelftest: " + failures + " row(s) did not pass");
        return failures;
    }

    // ---- rows ----

    @FunctionalInterface
    private interface FieldCheck {
        void check(MatchDescriptor before, MatchDescriptor after, ApplyResult result);
    }

    /** One changed Apply on {@code shape}: the id rotated, strict redaction kept, the field threaded, ONLY the pair
     *  written, the note the filled Changed text, the sidecar re-keyed to the new id. */
    private static void applyChanged(Path dir, Op op, String shape, Path offeredOrNull, String value, String shown,
            FieldCheck check) throws Exception {
        Fx f = fx(dir);
        Path file = f.match(shape);
        MatchDescriptor before = decode(file);
        Map<String, byte[]> snapBefore = snap(f.dir);
        ApplyResult r = applied(f, offeredOrNull == null ? file : offeredOrNull, op, value);
        MatchDescriptor after = decode(file);
        require(r.changed() && r.matchTextChanged() && !r.repaired(), "changed=" + r.changed() + " matchTextChanged="
                + r.matchTextChanged() + " repaired=" + r.repaired() + " - flips if a changed Apply stops reporting a new"
                + " match (the Swing shell focuses the Match shape picker on it, the page re-reads its config) or"
                + " calls a consistent pair torn");
        require(!after.matchId().equals(before.matchId()), "match.id did not rotate - flips if the Apply keeps the old"
                + " id (a game holding the old match would still get in to the new map)");
        require(after.shape().equals(before.shape())
                && (op == Op.PLAYERS || after.seats().size() == before.seats().size()),
                "the shape / seat count changed (" + before.shape() + " -> " + after.shape() + ") - flips if the re-mint"
                        + " stops reading the shape from the file (only PLAYERS may move the count)");
        require(MatchDescriptors.isStrictlyRedacted(lines(file)), "a live seat token reached match/" + shape
                + " - flips if redact(minted) is dropped on the package leg (redacted in must be redacted out)");
        check.check(before, after, r);
        String note = OpsMatchApply.CHANGED.replace("<shape>", shape).replace("<what>", op.what())
                .replace("<value>", shown);
        require(note.equals(r.note()), "note '" + r.note() + "', want '" + note + "' - flips if the Changed text or a"
                + " placeholder fill (<shape> <what> <value>) is wrong");
        require(after.matchId().equals(keyedId(sidecarOf(file))), "the sidecar's match= is "
                + keyedId(sidecarOf(file)) + ", want the new " + after.matchId() + " - flips if the sidecar is not"
                + " re-keyed with the descriptor (the door would refuse every game holding the new match)");
        requireDiff(snapBefore, snap(f.dir), List.of("pkg/match/" + shape, "pkg/match/" + shape + SIDECAR_SUFFIX),
                "flips if the Apply writes the props, a .password, a -tokens file, serve-mods/, the other shape or"
                        + " its sidecar, or leaves a temp sibling");
    }

    /** The values the op did NOT set thread through unchanged (the never-silently-reset law). */
    private static void requireThreaded(MatchDescriptor before, MatchDescriptor after, boolean half, boolean station,
            boolean backdrop, boolean cr) {
        if (half) {
            require(after.halfWidth() == before.halfWidth() && after.halfHeight() == before.halfHeight(),
                    "the half moved " + before.halfWidth() + " -> " + after.halfWidth() + " - flips if the re-mint"
                            + " resets the map size");
        }
        if (station) {
            require(String.valueOf(family(before)).equals(String.valueOf(family(after))), "the station family moved "
                    + family(before) + " -> " + family(after) + " - flips if the re-mint resets the station");
        }
        if (backdrop) {
            require(before.terrain().equals(after.terrain()), "the backdrop moved " + before.terrain() + " -> "
                    + after.terrain() + " - flips if the re-mint resets the backdrop word");
        }
        if (cr) {
            require(before.deployCr() == after.deployCr(), "the deploy CR moved " + before.deployCr() + " -> "
                    + after.deployCr() + " - flips if the re-mint resets the deploy CR");
        }
    }

    private static void sidecarRematch(Path dir) throws Exception {
        Fx f = fx(dir);
        Path file = f.match(FORT);
        Path sidecar = sidecarOf(file);
        Path other = sidecarOf(f.match(PLAIN));
        byte[] otherBefore = Files.readAllBytes(other);
        MatchDescriptor before = decode(file);
        applied(f, file, Op.SIZE, "8000");
        MatchDescriptor after = decode(file);
        String composite = ContentFingerprints.parseSidecarFingerprint(lines(sidecar), "the re-keyed sidecar");
        require(after.matchId().equals(ContentFingerprints.componentOrNull(composite, "match")), "the composite's"
                + " match= is not the new match.id - flips if the re-key misses the match= component");
        // mint B: the packer's own composer over the SAME kit with the new match = the re-keyed bytes
        Files.writeString(f.kit.resolve("match.txt"), MatchDescriptorCodec.encode(after), StandardCharsets.UTF_8);
        byte[] mintB = joined(ContentFingerprintMint.mintSidecarLines(f.kit));
        require(Arrays.equals(mintB, Files.readAllBytes(sidecar)), "the re-keyed sidecar is not byte-equal to a fresh"
                + " mint over the kit with the new match - flips if the re-key touches any row but match= (jar= /"
                + " data= / hs= / a file= row / a comment) or changes the line shape");
        // the client's own check: its self-compute over the KIT it holds, with the match its join window fetched
        Path jar = f.kit.resolve("jars").resolve("cc-client-mod.jar");
        String client = ContentFingerprintClientSurface.selfCompute(f.kit.toString(), sidecar.toString(),
                jar.toString(), HandshakeCodec.HANDSHAKE_VERSION, after.matchId());
        require(composite.equals(client), "the client's selfCompute over the kit (" + client + ") is not the server"
                + " composite (" + composite + ") - flips if the re-key breaks the door expectation for a player"
                + " holding the unchanged kit and the new match");
        String stale = ContentFingerprintClientSurface.selfCompute(f.kit.toString(), sidecar.toString(),
                jar.toString(), HandshakeCodec.HANDSHAKE_VERSION, before.matchId());
        require(!composite.equals(stale), "a player holding the OLD match still matches the composite - flips if"
                + " the sidecar keeps the old match= (the old match would get in to the new map)");
        require(Arrays.equals(otherBefore, Files.readAllBytes(other)), "the OTHER shape's sidecar changed - flips if"
                + " the re-key lands on the wrong file");
    }

    private static void strippedKitGap(Path dir) throws Exception {
        Fx f = fx(dir);
        Path file = f.match(PLAIN);
        Path sidecar = sidecarOf(file);
        // a stripped kit's sidecar: no file= rows, no jar= component (comments, data=, hs=, match= stay)
        List<String> stripped = new ArrayList<>();
        for (String line : lines(sidecar)) {
            if (line.startsWith("file=")) {
                continue;
            }
            stripped.add(line.startsWith("fingerprint=") ? line.replaceFirst(";jar=[^;]*", "") : line);
        }
        require(stripped.stream().noneMatch(l -> l.contains(";jar=") || l.startsWith("file=")),
                "SETUP: the stripped fixture still carries jar= / file=");
        Files.write(sidecar, stripped, StandardCharsets.US_ASCII);
        ApplyResult r = applied(f, file, Op.SIZE, "8000");
        MatchDescriptor after = decode(file);
        // the core's reading [OpsMatchApply#applyPackageMatchSetting javadoc]: R4 = no match= component only; a stripped
        // sidecar re-keys match= alone and answers the Changed note (no jar= / file= is invented, none is required)
        List<String> want = new ArrayList<>();
        for (String line : stripped) {
            want.add(line.startsWith("fingerprint=") ? line.replaceFirst(";match=[^;]*", ";match=" + after.matchId())
                    : line);
        }
        require(r.changed() && Arrays.equals(joined(want), Files.readAllBytes(sidecar)), "the stripped sidecar after"
                + " the Apply is not the input with ONLY match= re-keyed - flips if the re-key re-mints, invents a"
                + " jar= / file= row or drops a row");
        require(r.note().equals(OpsMatchApply.CHANGED.replace("<shape>", PLAIN).replace("<what>", Op.SIZE.what())
                .replace("<value>", "8000")), "note '" + r.note() + "' - flips if a stripped-kit Apply stops saying"
                + " Changed (the core reads a stripped sidecar as re-keyable, not R4)");
        // the boundary: no match= component at all = R4, nothing written
        List<String> noMatch = new ArrayList<>();
        for (String line : lines(sidecar)) {
            noMatch.add(line.startsWith("fingerprint=") ? line.replaceFirst(";match=[^;]*", "") : line);
        }
        Files.write(sidecar, noMatch, StandardCharsets.US_ASCII);
        refusesNothing(f, file, Op.SIZE, "9000", false, "sidecar_damaged",
                OpsMatchApply.R4_SIDECAR.replace("<name>", PLAIN), "a sidecar with no match= component");
    }

    private static void noChange(Path dir) throws Exception {
        Fx f = fx(dir);
        Object[][] legs = {
            {Op.SIZE, PLAIN, "6000", "6000"},
            {Op.STATION, FORT, "lowtech", "lowtech"},
            {Op.BACKDROP, FORT, "hyper", "hyper"},
            {Op.DEPLOY_CR, PLAIN, "80", "80%"},
        };
        for (Object[] leg : legs) {
            Op op = (Op) leg[0];
            String shape = (String) leg[1];
            Map<String, byte[]> before = snap(f.dir);
            ApplyResult r = applied(f, f.match(shape), op, (String) leg[2]);
            require(!r.changed() && !r.repaired() && !r.matchTextChanged(), op + " " + leg[2] + " again: changed="
                    + r.changed() + " repaired=" + r.repaired() + " - flips if an unchanged value re-mints (a new"
                    + " match.id turns every player away for nothing)");
            requireDiff(before, snap(f.dir), List.of(), op + " " + leg[2] + " again - flips if a no-change Apply"
                    + " writes any byte");
            String note = OpsMatchApply.UNCHANGED.replace("<shape>", shape).replace("<what>", op.what())
                    .replace("<value>", (String) leg[3]);
            require(note.equals(r.note()), "note '" + r.note() + "', want '" + note + "' - flips if the Unchanged"
                    + " text or its fill is wrong");
        }
    }

    private static void crPercentEqual(Path dir) throws Exception {
        Fx f = fx(dir);
        Path file = f.match(PLAIN);
        // a descriptor at deployCr = 0.705: round(70.5) = 71 is its whole-% grain (truncation would read 70)
        seat(f, file, MatchDescriptors.redact(MatchDescriptors.withTerrain(
                MatchDescriptors.mint("1v1", 6000f, 0.705f), "open")));
        Map<String, byte[]> before = snap(f.dir);
        ApplyResult same = applied(f, file, Op.DEPLOY_CR, "71");
        require(!same.changed(), "0.705 + '71' re-minted - flips if the equality grain is not round(cr*100)"
                + " (truncation reads 70)");
        requireDiff(before, snap(f.dir), List.of(), "0.705 + '71' - flips if an equal-grain CR writes");
        require(same.note().equals(OpsMatchApply.UNCHANGED.replace("<shape>", PLAIN)
                .replace("<what>", Op.DEPLOY_CR.what()).replace("<value>", "71%")), "note '" + same.note()
                + "' - flips if the Unchanged CR fill is not the whole percent");
        ApplyResult did = applied(f, file, Op.DEPLOY_CR, "70");
        require(did.changed() && decode(file).deployCr() == 0.70f, "0.705 + '70' gave changed=" + did.changed()
                + " cr=" + decode(file).deployCr() + ", want changed + 0.70 - flips if the grain reads 70.5 as 70"
                + " (truncation) or the percent is not pct/100f");
        require(OpsMatchApply.parseDeployCrPercent("100") == 1.0f && OpsMatchApply.parseDeployCrPercent(" 1 ") == 0.01f,
                "parseDeployCrPercent 100 / ' 1 ' - flips if the 1..100 bounds or the trim move");
        for (String bad : List.of("0", "101", "7.5", "abc", "-5", "+5")) {
            refusesNothing(f, file, Op.DEPLOY_CR, bad, false, "deploy_cr",
                    OpsMatchApply.R8_DEPLOY_CR.replace("<x>", bad), "deploy CR '" + bad + "'");
        }
    }

    private static void pairTornRepairs(Path dir) throws Exception {
        Fx f = fx(dir);
        Path file = f.match(PLAIN);
        Path sidecar = sidecarOf(file);
        byte[] s0 = Files.readAllBytes(sidecar);
        byte[] d0 = Files.readAllBytes(file);
        tear(sidecar);
        require(ServeLauncher.sidecarPairSplit(file, sidecar, quiet()), "SETUP: the launcher's pair check does not see"
                + " the fixture tear");
        Map<String, byte[]> torn = snap(f.dir);
        ApplyResult r = applied(f, file, Op.SIZE, "6000");
        require(!r.changed() && r.repaired() && !r.matchTextChanged(), "a torn pair + the same value: changed="
                + r.changed() + " repaired=" + r.repaired() + " - flips if the torn check or the repair branch is lost");
        require(Arrays.equals(s0, Files.readAllBytes(sidecar)), "the torn sidecar was not re-keyed back to the"
                + " descriptor's match.id (bytes differ from the minted pair) - flips if the torn-pair repair is dropped");
        require(Arrays.equals(d0, Files.readAllBytes(file)), "the descriptor changed on a repair - flips if the repair"
                + " re-mints (a new match.id) instead of re-keying the sidecar alone");
        requireDiff(torn, snap(f.dir), List.of("pkg/match/" + PLAIN + SIDECAR_SUFFIX), "flips if the repair writes"
                + " anything but the sidecar");
        require(!ServeLauncher.sidecarPairSplit(file, sidecar, quiet()), "the launcher still sees a split pair after"
                + " the repair - flips if the repair keys the wrong id");
        String note = OpsMatchApply.REPAIRED.replace("<shape>", PLAIN);
        require(note.equals(r.note()), "note '" + r.note() + "', want '" + note + "' - flips if the Repaired text or"
                + " its fill is wrong");
        // torn + a CHANGED value: the new pair is consistent and the result says it repaired too
        tear(sidecar);
        ApplyResult both = applied(f, file, Op.SIZE, "8000");
        require(both.changed() && both.repaired() && decode(file).matchId().equals(keyedId(sidecar)),
                "torn + a new value: changed=" + both.changed() + " repaired=" + both.repaired() + " - flips if a"
                        + " changed Apply on a torn pair leaves it split");
    }

    private static void sidecarFirstRollback(Path dir) throws Exception {
        Fx f = fx(dir);
        Path file = f.match(PLAIN);
        Path sidecar = sidecarOf(file);
        byte[] s0 = Files.readAllBytes(sidecar);
        byte[] d0 = Files.readAllBytes(file);
        MatchDescriptor fresh = MatchDescriptors.redact(MatchDescriptors.withTerrain(
                MatchDescriptors.mint("1v1", 6000f, 0.8f), "open"));
        String text = MatchDescriptorCodec.encode(fresh);
        List<String> rekeyed = ContentFingerprintMint.rekeySidecarLines(lines(sidecar), fresh.matchId());
        // the hook the core exposes: writeSidecar's injectable mover - this one records the move targets and fails
        // the SECOND move (whichever file it lands)
        List<Path> targets = new ArrayList<>();
        OpsMatchApply.AtomicMover failSecond = (from, to) -> {
            targets.add(to);
            if (targets.size() == 2) {
                throw new IOException("fixture: the second move fails");
            }
            OpsMatchApply.ATOMIC.move(from, to);
        };
        OpsMatchApply.PairWriteFailed failed = null;
        try {
            OpsMatchApply.writeSidecar(file, text, rekeyed, failSecond);
        } catch (OpsMatchApply.PairWriteFailed expected) {
            failed = expected;
        }
        require(failed != null && failed.undone, "a failed second move: " + (failed == null ? "no throw"
                : "undone=false") + " - flips if the failure is swallowed or the old sidecar is not restored");
        require(targets.equals(List.of(sidecar, file)), "move order " + targets + ", want the SIDECAR then the"
                + " descriptor - flips if the descriptor moves first (a tear would admit old-text holders to the new"
                + " map)");
        require(Arrays.equals(d0, Files.readAllBytes(file)) && Arrays.equals(s0, Files.readAllBytes(sidecar)),
                "after the failed descriptor move the pair is not the old pair - flips if the descriptor moves first or"
                        + " the old sidecar bytes are not written back");
        require(temps(f).isEmpty(), "temp siblings left: " + temps(f) + " - flips if a failure path keeps a temp");
        require(!ServeLauncher.sidecarPairSplit(file, sidecar, quiet()), "the pair is torn after the rollback");
        // no old sidecar: the failed second move deletes the new one
        Files.delete(sidecar);
        targets.clear();
        failed = null;
        try {
            OpsMatchApply.writeSidecar(file, text, rekeyed, failSecond);
        } catch (OpsMatchApply.PairWriteFailed expected) {
            failed = expected;
        }
        require(failed != null && failed.undone && !Files.exists(sidecar) && Arrays.equals(d0, Files.readAllBytes(file))
                && temps(f).isEmpty(), "no old sidecar + a failed second move left a sidecar, a temp or a new"
                + " descriptor - flips if the rollback does not delete the sidecar it placed");
        Files.write(sidecar, s0);
        // the success path moves in the same order
        targets.clear();
        OpsMatchApply.writeSidecar(file, text, rekeyed, (from, to) -> {
            targets.add(to);
            OpsMatchApply.ATOMIC.move(from, to);
        });
        require(targets.equals(List.of(sidecar, file)) && fresh.matchId().equals(decode(file).matchId())
                && fresh.matchId().equals(keyedId(sidecar)) && temps(f).isEmpty(), "the success path moved " + targets
                + " - flips if the descriptor moves first or the pair is not landed");
        // the real Apply on Windows: a reader holding the descriptor without delete-sharing fails the descriptor's
        // ATOMIC_MOVE (the sidecar moved first) = R7, the old pair kept
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            System.out.println("NOTE pkg-sidecar-first-rollback: the held-descriptor leg is Windows-graded");
            return;
        }
        // the premise, staged on a scratch pair first: a held reader fails an ATOMIC_MOVE onto its file
        Path heldScratch = f.dir.resolve("premise.txt");
        Path moverScratch = f.dir.resolve("premise.tmp");
        Files.writeString(heldScratch, "held\n");
        Files.writeString(moverScratch, "moved\n");
        boolean premise = false;
        try (FileInputStream held = new FileInputStream(heldScratch.toFile())) {
            OpsMatchApply.ATOMIC.move(moverScratch, heldScratch);
        } catch (IOException expected) {
            premise = true;
        }
        if (!premise) {
            throw new SetupFailure("a held reader did not fail an ATOMIC_MOVE onto its file (the share-lock premise"
                    + " did not stage)");
        }
        Files.deleteIfExists(heldScratch);
        Files.deleteIfExists(moverScratch);
        byte[] s1 = Files.readAllBytes(sidecar);
        byte[] d1 = Files.readAllBytes(file);
        ApplyRefused refused = null;
        try (FileInputStream held = new FileInputStream(file.toFile())) {
            OpsMatchApply.applyPackageMatchSetting(f.roots, file, Op.SIZE, "8000", false);
        } catch (ApplyRefused expected) {
            refused = expected;
        }
        require(refused != null, "the Apply on a held descriptor returned instead of refusing R7 - flips if the Apply"
                + " lands on another file or a failed descriptor move is swallowed");
        Matcher m = Pattern.compile(Pattern.quote(OpsMatchApply.R7_WRITE_KEPT).replace("<class>", "\\E[A-Za-z]+\\Q"))
                .matcher(refused.getMessage());
        require("write_failed".equals(refused.token()) && m.matches(), "the held-descriptor Apply refused "
                + refused.token() + " '" + refused.getMessage() + "' - flips if a failed descriptor move is not R7");
        require(Arrays.equals(d1, Files.readAllBytes(file)) && Arrays.equals(s1, Files.readAllBytes(sidecar))
                && temps(f).isEmpty() && !ServeLauncher.sidecarPairSplit(file, sidecar, quiet()),
                "the held-descriptor Apply left a changed file, a temp or a torn pair - flips if the sidecar is not"
                        + " restored after the descriptor's move fails");
    }

    private static void writeNothingRefusals(Path dir) throws Exception {
        Fx f = fx(dir);
        Path plain = f.match(PLAIN);
        Path fort = f.match(FORT);
        refusesNothing(f, plain, Op.SIZE, "8000", true, "server_live", OpsMatchApply.R1_LIVE, "a live server");
        refusesNothing(f, null, Op.SIZE, "8000", true, "server_live", OpsMatchApply.R1_LIVE,
                "a live server + no shape (R1 first)");
        for (String bad : List.of("1999", "60001", "7000.5")) {
            refusesNothing(f, plain, Op.SIZE, bad, false, "map_size", OpsMatchApply.R3_MAP_SIZE.replace("<x>", bad),
                    "map size '" + bad + "'");
        }
        // 17 chars that WOULD parse to 8000: only the length cap refuses it
        String longValue = "00000000000008000";
        refusesNothing(f, plain, Op.SIZE, longValue, false, "map_size",
                OpsMatchApply.R3_MAP_SIZE.replace("<x>", "0000000000000800..."), "a 17-char value");
        refusesNothing(f, plain, Op.STATION, "hightech", false, "no_station",
                OpsMatchApply.R2_NO_STATION.replace("<shape>", PLAIN), "a station on the stationless shape");
        refusesNothing(f, fort, Op.STATION, "startech", false, "station_word",
                OpsMatchApply.STATION_WORD.replace("<x>", "startech"), "station word 'startech'");
        refusesNothing(f, fort, Op.BACKDROP, "fog", false, "backdrop_word",
                OpsMatchApply.BACKDROP_WORD.replace("<x>", "fog"), "backdrop word 'fog'");
        // R6: offered = a NAME under <pkg>/match, never a read first
        Path outside = f.dir.resolve("outside");
        Files.createDirectories(outside);
        Files.copy(plain, outside.resolve(PLAIN));
        Files.copy(sidecarOf(plain), outside.resolve(PLAIN + SIDECAR_SUFFIX));
        for (Path p : Arrays.asList(null, f.pkg.resolve("match").resolve("absent.txt"),
                Paths.get("match", "..", "cc-server.properties"), Paths.get("match", TOKENS),
                outside.resolve(PLAIN), Paths.get("..", "outside", PLAIN), Paths.get("..\\outside\\" + PLAIN))) {
            refusesNothing(f, p, Op.SIZE, "8000", false, "not_offered", OpsMatchApply.R6_NOT_OFFERED,
                    "offered path " + (p == null ? "null" : p.toString().replace('\\', '/')));
        }
        // R4 / R5 on the files themselves (each restored after its leg)
        Path sidecar = sidecarOf(plain);
        byte[] s0 = Files.readAllBytes(sidecar);
        Files.delete(sidecar);
        refusesNothing(f, plain, Op.SIZE, "8000", false, "sidecar_damaged",
                OpsMatchApply.R4_SIDECAR.replace("<name>", PLAIN), "a missing sidecar");
        String hs = ";hs=" + HandshakeCodec.HANDSHAKE_VERSION + ";";
        List<String> otherHs = new ArrayList<>();
        for (String line : new String(s0, StandardCharsets.US_ASCII).lines().toList()) {
            otherHs.add(line.replace(hs, ";hs=" + (HandshakeCodec.HANDSHAKE_VERSION - 1) + ";"));
        }
        Files.write(sidecar, otherHs, StandardCharsets.US_ASCII);
        refusesNothing(f, plain, Op.SIZE, "8000", false, "other_version", OpsMatchApply.R5_VERSION,
                "a sidecar of another handshake version");
        Files.write(sidecar, s0);
        byte[] d0 = Files.readAllBytes(plain);
        Files.writeString(plain, "not a match descriptor\n", StandardCharsets.UTF_8);
        refusesNothing(f, plain, Op.SIZE, "8000", false, "other_version", OpsMatchApply.R5_VERSION,
                "an undecodable descriptor");
        Files.write(plain, d0);
    }

    /** Observable = a comment-stripped SOURCE scan of {@code OpsMatchApply.java} from the cwd (the core exposes no
     *  counting hook): the {@code .fingerprint} path is derived only in {@code writeSidecar} (write) and
     *  {@code applyPackageMatchSetting} (read); the package entry has no write primitive; {@code Files.move} lives only
     *  in {@code ATOMIC}; {@code writeSidecar} is called by the owner leg and the package leg. */
    private static void sidecarOneWriter(Path dir) throws Exception {
        String code = stripComments(source("server/src/cc/starsector/server/ops/OpsMatchApply.java"));
        List<String> fingerprintAt = membersOf(code, Pattern.compile(Pattern.quote("\"" + SIDECAR_SUFFIX + "\"")));
        require(fingerprintAt.equals(List.of("writeSidecar", "applyPackageMatchSetting"))
                || fingerprintAt.equals(List.of("applyPackageMatchSetting", "writeSidecar")), "the \".fingerprint\""
                + " path is derived in " + fingerprintAt + ", want writeSidecar (the write) + applyPackageMatchSetting"
                + " (the read) - flips if another member builds a sidecar path (a second writer)");
        Pattern write = Pattern.compile("\\bFiles\\s*\\.\\s*(write|writeString|move|copy|newOutputStream"
                + "|newBufferedWriter|createFile|delete|deleteIfExists)\\s*\\(");
        List<String> writers = membersOf(code, write);
        for (String noWrite : List.of("applyPackageMatchSetting", "writePairOrRefuse", "rekeyOrRefuse")) {
            require(!writers.contains(noWrite), noWrite + " holds a Files write primitive - flips if the package leg"
                    + " writes a match file outside writeSidecar");
        }
        require(membersOf(code, Pattern.compile("\\bFiles\\s*\\.\\s*move\\s*\\(")).equals(List.of("ATOMIC")),
                "Files.move sits in " + membersOf(code, Pattern.compile("\\bFiles\\s*\\.\\s*move\\s*\\(")) + ", want"
                        + " ATOMIC only - flips if a second mover lands a match file");
        List<String> callers = membersOf(code, Pattern.compile("(?<![\\w.])writeSidecar\\s*\\((?![^)]*\\)\\s*(throws|\\{))"));
        require(new TreeSet<>(callers).equals(new TreeSet<>(List.of("writeSidecar", "reauthorDescriptors",
                "writePairOrRefuse"))), "writeSidecar is called from " + callers + ", want the 3-arg delegate, the"
                        + " owner leg (reauthorDescriptors) and the package leg (writePairOrRefuse) - flips if a leg"
                        + " stops landing its pair through the one writer");
        require(membersOf(code, Pattern.compile("\\bmintSidecarLines\\s*\\(")).equals(List.of("reauthorDescriptors")),
                "mintSidecarLines is called outside the owner leg - flips if a minted sidecar reaches disk another way");
    }

    private static void redactMoved(Path dir) throws Exception {
        List<MatchDescriptor> live = List.of(MatchDescriptors.mint("1v1", 6000f),
                MatchDescriptors.withTerrain(MatchDescriptors.mint(MatchDescriptors.FORTRESS_SHAPE, 7000f, 0.6f,
                        MatchDescriptors.stationVariantForFamily("lowtech")), "nebula"));
        for (MatchDescriptor d : live) {
            require(!MatchPasswords.carriesRedactedTokens(d), "SETUP: a fresh mint already carries placeholders");
            String moved = MatchDescriptorCodec.encode(MatchDescriptors.redact(d));
            MatchDescriptor viaTool = toolRedactOrNull(d);
            String tool = viaTool == null ? moved : MatchDescriptorCodec.encode(viaTool);
            if (viaTool == null) {
                System.out.println("NOTE redact-moved: the packer CLI twin (tools.MatchTokenRedact) is not in this tree"
                        + " (an exported source build) - the moved body is graded by the placeholder rows alone");
            } else {
                require(moved.equals(tool), d.shape() + ": MatchDescriptors.redact and MatchTokenRedact.redact differ"
                        + " through the codec - flips if the moved body and the packer's CLI disagree");
            }
            for (MatchDescriptor.Seat seat : d.seats()) {
                require(!moved.contains(seat.seatToken()) && !tool.contains(seat.seatToken()), d.shape() + ": seat "
                        + seat.seatId() + "'s live token survived a redact - flips if a seat is not replaced");
            }
            MatchDescriptor back = MatchDescriptorCodec.decode(moved.lines().toList());
            require(MatchDescriptors.isStrictlyRedacted(moved.lines().toList())
                    && MatchPasswords.carriesRedactedTokens(back) && back.matchId().equals(d.matchId())
                    && back.terrain().equals(d.terrain()) && back.deployCr() == d.deployCr(), d.shape()
                    + ": the redacted copy is not strictly redacted or lost match.id / backdrop / CR - flips if the"
                    + " redaction law changes anything but the seat tokens (the door composite reads match.id)");
            require(MatchDescriptorCodec.encode(MatchDescriptors.redact(back)).equals(moved), d.shape()
                    + ": redact is not idempotent");
        }
        String tool = toolRedactOrNull(live.get(0)) == null ? null
                : stripComments(source("server/src/cc/starsector/server/tools/MatchTokenRedact.java"));
        require(tool == null || tool.contains("return MatchDescriptors.redact(descriptor);") && !tool.contains(
                "new MatchDescriptor.Seat("), "tools/MatchTokenRedact.redact is not a delegate - flips if its body is copied back");
    }

    private static void applyTexts(Path dir) {
        String[][] verbatim = {
            {"CONFIRM_TITLE", OpsMatchApply.CONFIRM_TITLE, S_CONFIRM_TITLE},
            {"CHANGED", OpsMatchApply.CHANGED, S_CHANGED},
            {"UNCHANGED", OpsMatchApply.UNCHANGED, S_UNCHANGED},
            {"REPAIRED", OpsMatchApply.REPAIRED, S_REPAIRED},
            {"R1_LIVE", OpsMatchApply.R1_LIVE, S_R1},
            {"R2_NO_STATION", OpsMatchApply.R2_NO_STATION, S_R2},
            {"R3_MAP_SIZE", OpsMatchApply.R3_MAP_SIZE, S_R3},
            {"R4_SIDECAR", OpsMatchApply.R4_SIDECAR, S_R4},
            {"R5_VERSION", OpsMatchApply.R5_VERSION, S_R5},
            {"R6_NOT_OFFERED", OpsMatchApply.R6_NOT_OFFERED, S_R6},
            {"R7_WRITE_KEPT", OpsMatchApply.R7_WRITE_KEPT, S_R7},
            {"R7B_WRITE_TORN", OpsMatchApply.R7B_WRITE_TORN, S_R7B},
            {"R8_DEPLOY_CR", OpsMatchApply.R8_DEPLOY_CR, S_R8},
            {"R9_PLAYERS", OpsMatchApply.R9_PLAYERS, S_R9},
            {"R10_PLAYERS_RANGE", OpsMatchApply.R10_PLAYERS_RANGE, S_R10},
            {"SIDECAR_PAIR_SENTENCE", ServeLauncher.SIDECAR_PAIR_SENTENCE, S_LAUNCHER},
            {"RIDER_MAP_HALF", ServeLauncher.RIDER_MAP_HALF, S_RIDER},
            {"RIDER_DEPLOY_CR", ServeLauncher.RIDER_DEPLOY_CR, S_RIDER_CR},
        };
        List<String> texts = OpsMatchApply.texts();
        for (String[] t : verbatim) {
            require(t[2].equals(t[1]), t[0] + " = '" + t[1] + "', want '" + t[2] + "' - flips if the"
                    + " host-facing sentence drifts from the design");
            require(texts.contains(t[1]), t[0] + " is missing from texts() - flips if a text escapes the text-law row");
        }
        // + the eight PvP mode texts, each named
        List<String> pvpTexts = List.of(OpsMatchApply.PVP_WORD, OpsMatchApply.R10_PVP_PLAYERS,
                OpsMatchApply.R11_PVP_SHAPE, OpsMatchApply.PVP_CHANGED, OpsMatchApply.PVP_STATION_BACK,
                OpsMatchApply.PVP_START_LATCH, OpsMatchApply.PVP_START_STATION, OpsMatchApply.PVP_START_OWNER);
        require(texts.contains(OpsMatchApply.STATION_WORD) && texts.contains(OpsMatchApply.BACKDROP_WORD)
                && texts.containsAll(pvpTexts) && texts.size() == verbatim.length + 2 + pvpTexts.size()
                && new TreeSet<>(texts).size() == texts.size(), "texts() holds " + texts.size() + " entries, want the "
                        + (verbatim.length + 2 + pvpTexts.size()) + " distinct templates - flips if a text escapes"
                        + " the text-law row");
        for (String family : MatchDescriptors.STATION_FAMILIES) {
            require(OpsMatchApply.STATION_WORD.contains(family), "STATION_WORD does not name '" + family
                    + "' - flips if the family vocabulary grows past the refusal text");
        }
        for (String word : MatchDescriptor.TERRAIN_WORDS) {
            require(OpsMatchApply.BACKDROP_WORD.contains(word), "BACKDROP_WORD does not name '" + word
                    + "' - flips if the backdrop vocabulary grows past the refusal text");
        }
        Pattern internal = Pattern.compile("\\bs\\d{3,5}\\b|TASK-|REVIEW-|ADR-|" + (char) 0xA7 + "|\\bL\\.3\\b");
        for (String t : texts) {
            require(!internal.matcher(t).find(), "a host-facing text carries an internal token: '" + t + "'");
        }
        require(List.of("map size", "station type", "battle background", "deploy CR", "player count").equals(List.of(
                Op.SIZE.what(), Op.STATION.what(), Op.BACKDROP.what(), Op.DEPLOY_CR.what(), Op.PLAYERS.what())),
                "the <what> words moved - flips if an op stops naming its field the way the Setup tab row does");
    }

    /** A package window's backdrop + station prefills read the config's own standing
     *  descriptor (the fixture's {@code match.descriptor=match/<FORT>}: backdrop hyper, family lowtech);
     *  a config with no row yields an empty list and the code defaults, never a throw. */
    private static void prefillStanding(Path dir) throws IOException {
        Fx f = fx(dir);
        Path config = f.pkg.resolve("cc-server.properties");
        List<Path> standing = OpsMatchApply.standingDescriptorFiles(
                Files.readAllLines(config, StandardCharsets.UTF_8), config);
        if (standing.size() != 1 || !standing.get(0).equals(f.match(FORT))) {
            throw new AssertionError("standingDescriptorFiles = " + standing + ", want [" + f.match(FORT) + "]");
        }
        String backdrop = OpsMatchApply.terrainBackdropPrefill(standing);
        if (!"hyper".equals(backdrop)) {
            throw new AssertionError("backdrop prefill = " + backdrop + ", want hyper");
        }
        String family = OpsMatchApply.stationFamilyPrefill(standing);
        if (!"lowtech".equals(family)) {
            throw new AssertionError("station family prefill = " + family + ", want lowtech");
        }
        List<Path> none = OpsMatchApply.standingDescriptorFiles(List.of("# fixture", "map.half=9000"), config);
        if (!none.isEmpty()) {
            throw new AssertionError("no match.descriptor row gave " + none + ", want []");
        }
        if (!MatchDescriptor.TERRAIN_NONE.equals(OpsMatchApply.terrainBackdropPrefill(none))) {
            throw new AssertionError("an empty list must prefill the backdrop at " + MatchDescriptor.TERRAIN_NONE);
        }
        String defaultFamily = OpsMatchApply.stationFamilyPrefill(none);
        if (defaultFamily == null || defaultFamily.isBlank()) {
            throw new AssertionError("an empty list must prefill the station family at the code default");
        }
    }

    /** {@link OpsMatchApply#parsePlayers} - trimmed digits from 1 to 255
     *  parse (both ends of the range); blank, 0, 256, a letter, a sign, four digits, an inner space, null and a
     *  17-char value are the {@code players} refusal carrying R9 with the shown value. */
    private static void parsePlayers(Path dir) {
        String[] good = {" 7 ", "1", "255", "40"};
        int[] want = {7, 1, 255, 40};
        for (int i = 0; i < good.length; i++) {
            int got = OpsMatchApply.parsePlayers(good[i]);
            require(got == want[i], "'" + good[i] + "' parsed to " + got + ", want " + want[i] + " - flips if the"
                    + " Players parse drops the trim or moves a range end");
        }
        String[] bad = {"", "   ", "0", "256", "abc", "+1", "-1", "1234", "4 0", null, "12345678901234567"};
        for (String text : bad) {
            ApplyRefused refused = null;
            try {
                int got = OpsMatchApply.parsePlayers(text);
                require(false, "'" + text + "' parsed to " + got + " - flips if the Players grammar admits a blank,"
                        + " a sign, a 4th digit or a count outside 1..255");
            } catch (ApplyRefused expected) {
                refused = expected;
            }
            String sentence = S_R9.replace("<x>", OpsMatchApply.shown(text));
            require("players".equals(refused.token()) && sentence.equals(refused.getMessage()), "'" + text
                    + "' refused " + refused.token() + " '" + refused.getMessage() + "', want players '" + sentence
                    + "' - flips if the refusal token or its R9 fill moves");
        }
    }

    /** A 40-seat fortress (the shape string keeps the stock spec, so only the SEAT LIST carries the count: a
     *  re-mint from the spec would land the stock 3) at 7000 su / 60 % / lowtech / hyper. */
    private static MatchDescriptor fortressAt40() {
        return MatchDescriptors.withTerrain(MatchDescriptors.mintPlayers(MatchDescriptors.FORTRESS_SHAPE, 40,
                7000f, 0.6f, MatchDescriptors.stationVariantForFamily("lowtech")), "hyper");
    }

    /** The Apply {@code op} re-minted {@code file} (a new match.id) and the seat count is still 40. */
    private static void requireKept40(String leg, MatchDescriptor before, Path file, boolean changed)
            throws IOException {
        MatchDescriptor after = decode(file);
        require(changed && !after.matchId().equals(before.matchId()), "SETUP: the " + leg + " Apply did not re-mint"
                + " (changed=" + changed + ")");
        require(after.seats().size() == 40 && after.shape().equals(MatchDescriptors.FORTRESS_SHAPE), "the " + leg
                + " Apply left " + after.seats().size() + " seats of " + after.shape() + " - flips if that re-mint"
                + " site mints from the shape spec instead of MatchDescriptors.remint (the stock 3 seats)");
    }

    /** The package Applies (the four non-PLAYERS ops through
     *  {@link OpsMatchApply#applyPackageMatchSetting}) on a 40-seat fortress each re-mint at 40 seats. */
    private static void pkgApplyKeepsPlayers(Path dir) throws Exception {
        Fx f = fx(dir);
        Path file = f.match(FORT);
        seat(f, file, MatchDescriptors.redact(fortressAt40()));
        for (Op op : Op.values()) {
            if (op == Op.PLAYERS || op == Op.PVP) {
                continue; // PLAYERS changes the count by design; PVP changes the shape (its own rows)
            }
            String value = switch (op) {
                case SIZE -> "8000";
                case STATION -> "hightech";
                case BACKDROP -> "nebula";
                case DEPLOY_CR -> "55";
                case PLAYERS, PVP -> "40";
            };
            MatchDescriptor before = decode(file);
            requireKept40(op.what(), before, file, applied(f, file, op, value).changed());
        }
    }

    /** A PLAYERS Apply on the 3-seat fortress (typed " 5 ", shown "5")
     *  re-mints it at 5 seats - seat ids 1..5 ascending, the station row still LAST, half / station / backdrop / CR
     *  threaded - then a second Apply of "1" on the SAME staged package shrinks it to one seat, the station still
     *  last (the shuttlepod rows are trimmed first). */
    private static void pkgApplyPlayers(Path dir) throws Exception {
        applyChanged(dir, Op.PLAYERS, FORT, null, " 5 ", "5", (before, after, r) -> {
            requireSeatList(after, 5, "the grown fortress");
            requireThreaded(before, after, true, true, true, true);
        });
        Fx f = staged(dir);
        Path file = f.match(FORT);
        MatchDescriptor five = decode(file);
        ApplyResult r = applied(f, file, Op.PLAYERS, "1");
        MatchDescriptor one = decode(file);
        require(r.changed() && !one.matchId().equals(five.matchId()) && one.shape().equals(five.shape()), "the shrink"
                + " to 1 changed=" + r.changed() + " shape " + one.shape() + " - flips if a smaller count is read as"
                + " unchanged or the bastion shape string is rewritten");
        requireSeatList(one, 1, "the shrunk fortress");
        requireThreaded(five, one, true, true, true, true);
        require(MatchDescriptors.isStrictlyRedacted(lines(file)) && one.matchId().equals(keyedId(sidecarOf(file))),
                "the shrunk pair is not redacted or its sidecar not re-keyed - flips if the second Apply skips redact"
                        + " or the sidecar re-key");
    }

    /** The package {@link #fx} already staged under {@code dir}: the paths only, nothing re-written (a second
     *  {@code fx(dir)} would re-stage the stock files over the Apply under test). */
    private static Fx staged(Path dir) {
        Fx f = new Fx(dir);
        require(Files.isRegularFile(f.match(FORT)) && Files.isRegularFile(f.match(PLAIN)),
                "SETUP: no staged package under the row's dir");
        return f;
    }

    /** {@code d} holds {@code players} seats with ids 1..P ascending (ship id == seat id) and a station as its LAST
     *  roster row. */
    private static void requireSeatList(MatchDescriptor d, int players, String leg) {
        require(d.seats().size() == players, leg + " holds " + d.seats().size() + " seats, want " + players
                + " - flips if the PLAYERS count is not threaded into the re-mint (the 5-arg remint keeps the file's"
                + " count)");
        for (int i = 0; i < d.seats().size(); i++) {
            require(d.seats().get(i).seatId() == i + 1, leg + ": seat #" + i + " has id " + d.seats().get(i).seatId()
                    + ", want " + (i + 1) + " - flips if the regrow re-orders seats (ship id == seat id)");
        }
        List<MatchDescriptor.RosterRow> roster = d.roster();
        require(!roster.isEmpty() && MatchDescriptors.isStationVariantId(roster.get(roster.size() - 1).variantId()),
                leg + ": the last roster row is not a station - flips if the re-mint drops the station row or moves"
                        + " it before a SEAT row");
    }

    /** Every PLAYERS refusal writes nothing and answers token
     *  {@code players} - off the grammar = R9 (a padded 17-char value that WOULD parse is the length cap's alone),
     *  a grammar-legal count outside the file's shape range = R10 in the New-shape wording, and that range gate
     *  sits after the sidecar gate (a damaged sidecar answers R4 first). */
    private static void pkgApplyPlayersRefused(Path dir) throws Exception {
        Fx f = fx(dir);
        Path fort = f.match(FORT);
        Path plain = f.match(PLAIN);
        for (String bad : List.of("0", "256", "x", "")) {
            refusesNothing(f, fort, Op.PLAYERS, bad, false, "players",
                    OpsMatchApply.R9_PLAYERS.replace("<x>", OpsMatchApply.shown(bad)), "players '" + bad + "'");
        }
        refusesNothing(f, fort, Op.PLAYERS, "00000000000000005", false, "players",
                OpsMatchApply.R9_PLAYERS.replace("<x>", "0000000000000000..."), "a 17-digit players value");
        // 17 chars that WOULD parse to 5 (the trim leaves one digit): only the length cap refuses it
        String padded = "        5        ";
        require(padded.length() == 17 && OpsMatchApply.parsePlayers(padded) == 5, "SETUP: the padded value is not a"
                + " 17-char legal count");
        refusesNothing(f, fort, Op.PLAYERS, padded, false, "players", OpsMatchApply.R9_PLAYERS.replace("<x>", "5"),
                "a padded 17-char players value");
        String range = S_R10.replace("<shape>", "1v1").replace("<min>", "2").replace("<max>", "255")
                .replace("<x>", "1");
        refusesNothing(f, plain, Op.PLAYERS, "1", false, "players", range, "players '1' on the 1v1 (takes 2..255)");
        Path sidecar = sidecarOf(plain);
        byte[] s0 = Files.readAllBytes(sidecar);
        Files.delete(sidecar);
        refusesNothing(f, plain, Op.PLAYERS, "1", false, "sidecar_damaged",
                OpsMatchApply.R4_SIDECAR.replace("<name>", PLAIN), "players '1' on the 1v1 without its sidecar");
        Files.write(sidecar, s0);
    }

    /** A PLAYERS Apply at the file's OWN seat count writes nothing and
     *  answers the Unchanged note - the stock fortress at 3, the 1v1 at 2, then the fortress re-seated at 40 seats
     *  (its spec's stock count is still 3) asked for 40. */
    private static void pkgApplyPlayersUnchanged(Path dir) throws Exception {
        Fx f = fx(dir);
        Object[][] legs = {{FORT, " 3 ", "3"}, {PLAIN, "2", "2"}, {FORT, "40", "40"}};
        for (Object[] leg : legs) {
            String shape = (String) leg[0];
            Path file = f.match(shape);
            if ("40".equals(leg[1])) {
                seat(f, file, MatchDescriptors.redact(fortressAt40()));
            }
            MatchDescriptor before = decode(file);
            Map<String, byte[]> snapBefore = snap(f.dir);
            ApplyResult r = applied(f, file, Op.PLAYERS, (String) leg[1]);
            MatchDescriptor after = decode(file);
            require(!r.changed() && !r.repaired() && !r.matchTextChanged() && after.matchId().equals(before.matchId())
                    && after.seats().size() == before.seats().size(), "players '" + leg[1] + "' on " + shape + " ("
                    + before.seats().size() + " seats): changed=" + r.changed() + " repaired=" + r.repaired()
                    + " - flips if unchanged is not keyed on the file's own seat list (a no-op Apply would re-mint"
                    + " and turn every player away)");
            requireDiff(snapBefore, snap(f.dir), List.of(), "players '" + leg[1] + "' on " + shape + " - flips if a"
                    + " no-change PLAYERS Apply writes any byte");
            String note = OpsMatchApply.UNCHANGED.replace("<shape>", shape).replace("<what>", Op.PLAYERS.what())
                    .replace("<value>", (String) leg[2]);
            require(note.equals(r.note()), "note '" + r.note() + "', want '" + note + "' - flips if the Unchanged"
                    + " fill is not the parsed count");
        }
        require(decode(f.match(FORT)).seats().size() == 40, "SETUP: the 40-seat leg did not seat 40");
    }

    /** A live server refuses every PLAYERS value with R1 before any
     *  parse - a legal count, an off-grammar one and no shape at all. */
    private static void pkgApplyPlayersLive(Path dir) throws Exception {
        Fx f = fx(dir);
        Path fort = f.match(FORT);
        refusesNothing(f, fort, Op.PLAYERS, "5", true, "server_live", OpsMatchApply.R1_LIVE, "a live server + 5");
        refusesNothing(f, fort, Op.PLAYERS, "0", true, "server_live", OpsMatchApply.R1_LIVE,
                "a live server + 0 (R1 before the Players parse)");
        refusesNothing(f, null, Op.PLAYERS, "x", true, "server_live", OpsMatchApply.R1_LIVE,
                "a live server + no shape + 'x' (R1 first)");
    }

    /** The owner-mode Applies ({@link OpsMatchApply#applyMapSize},
     *  {@code applyStationVariant}, {@code applyTerrainBackdrop}, {@code applyDeployCr}) over a 40-seat fortress,
     *  each against the fixture kit as the deployed client tree, each re-mint at 40 seats. */
    private static void ownerApplyKeepsPlayers(Path dir) throws Exception {
        Fx f = fx(dir);
        Path file = f.match(FORT);
        seat(f, file, fortressAt40());
        Path props = f.pkg.resolve("cc-server.properties");
        List<Path> files = List.of(file);
        MatchDescriptor before = decode(file);
        requireKept40("map size", before, file, OpsMatchApply.applyMapSize(props, files, f.kit, "8000").changed());
        before = decode(file);
        requireKept40("station", before, file,
                OpsMatchApply.applyStationVariant(props, files, f.kit, "hightech").changed());
        before = decode(file);
        requireKept40("backdrop", before, file,
                OpsMatchApply.applyTerrainBackdrop(props, files, f.kit, "nebula").changed());
        before = decode(file);
        requireKept40("deploy CR", before, file, OpsMatchApply.applyDeployCr(props, files, f.kit, "55").changed());
    }

    /**
     * {@code apply-props-locked} - the owner map size Apply's config write
     * ({@link OpsMatchApply#applyMapSize}: the read of the config file, the no-change test, the compose and the
     * {@code map.half} row write) holds {@link ServerOpsLaunch#PROPS_LOCK}, the lock the WATCH lease's clear and a
     * Start's posture write rewrite that same file under. This thread takes the lock; a second thread runs the
     * Apply (fixture {@code map.half=9000} -> 8000, so the row must change); the row waits until that thread is
     * BLOCKED on the lock itself (its {@code ThreadInfo} lock identity) while the file's bytes stay as staged; once
     * released, the Apply returns changed with {@code map.half=8000} written. Flips if the Apply's config write
     * escapes the lock (the second thread finishes inside the hold and the bytes change) or stops writing the row
     * (the second check - the hold check would then prove nothing).
     */
    private static void applyPropsLocked(Path dir) throws Exception {
        Fx f = fx(dir);
        Path props = f.pkg.resolve("cc-server.properties");
        List<Path> files = List.of(f.match(FORT));
        byte[] staged = Files.readAllBytes(props);
        require(new String(staged, StandardCharsets.UTF_8).lines().anyMatch("map.half=9000"::equals),
                "SETUP: the fixture config carries no map.half=9000 row");
        OpsMatchApply.MapSizeResult[] result = {null};
        Throwable[] thrown = {null};
        Thread apply = new Thread(() -> {
            try {
                result[0] = OpsMatchApply.applyMapSize(props, files, f.kit, "8000");
            } catch (IOException | RuntimeException e) {
                thrown[0] = e;
            }
        }, "cc-apply-lock-row");
        apply.setDaemon(true);
        int lockId = System.identityHashCode(ServerOpsLaunch.PROPS_LOCK);
        java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
        boolean blockedOnLock = false;
        boolean finishedInHold = false;
        boolean unchangedInHold;
        synchronized (ServerOpsLaunch.PROPS_LOCK) {
            apply.start();
            long until = System.nanoTime() + 10_000_000_000L;
            while (!blockedOnLock && !finishedInHold && System.nanoTime() < until) {
                java.lang.management.ThreadInfo ti = mx.getThreadInfo(apply.getId());
                blockedOnLock = ti != null && ti.getThreadState() == Thread.State.BLOCKED
                        && ti.getLockInfo() != null && ti.getLockInfo().getIdentityHashCode() == lockId;
                finishedInHold = !apply.isAlive();
                if (!blockedOnLock && !finishedInHold) {
                    Thread.sleep(2);
                }
            }
            unchangedInHold = Arrays.equals(staged, Files.readAllBytes(props));
        }
        apply.join(15_000);
        require(blockedOnLock && !finishedInHold && unchangedInHold, "while this thread held PROPS_LOCK the Apply"
                + " blocked on it=" + blockedOnLock + " finished=" + finishedInHold + " file unchanged="
                + unchangedInHold + (thrown[0] == null ? "" : " threw " + thrown[0].getClass().getSimpleName())
                + " - flips if the Apply's config write runs without taking PROPS_LOCK");
        String after = Files.readString(props, StandardCharsets.UTF_8);
        require(!apply.isAlive() && thrown[0] == null && result[0] != null && result[0].changed()
                && after.lines().anyMatch("map.half=8000"::equals) && after.lines().noneMatch("map.half=9000"::equals),
                "after the release the Apply alive=" + apply.isAlive() + " threw="
                        + (thrown[0] == null ? "-" : thrown[0].getClass().getSimpleName()) + " changed="
                        + (result[0] != null && result[0].changed()) + " - flips if the Apply stops writing the"
                        + " map.half row (the hold check would then prove nothing)");
    }

    /** The station + backdrop prefills read through the capped read - a
     *  decodable fortress (a non-default family, nebula) padded to exactly the cap prefills its own values; the
     *  same text one byte past the cap prefills the defaults (refused, never truncated). */
    private static void prefillOverCap(Path dir) throws Exception {
        String defaultFamily = OpsMatchApply.stationFamilyPrefill(List.of(dir.resolve("absent.txt")));
        String family = null;
        for (String candidate : MatchDescriptors.STATION_FAMILIES) {
            if (family == null && !candidate.equals(defaultFamily)) {
                family = candidate;
            }
        }
        require(family != null, "SETUP: no station family other than the default " + defaultFamily);
        byte[] text = MatchDescriptorCodec.encode(MatchDescriptors.withTerrain(MatchDescriptors.mint(
                MatchDescriptors.FORTRESS_SHAPE, 7000f, 0.6f, MatchDescriptors.stationVariantForFamily(family)),
                "nebula")).getBytes(StandardCharsets.US_ASCII);
        int cap = (int) ServeLauncher.MAX_CONFIG_BYTES;
        Path file = dir.resolve("big.txt");
        List<Path> files = List.of(file);
        Files.write(file, blankPadded(text, cap));
        String atFamily = OpsMatchApply.stationFamilyPrefill(files);
        String atWord = OpsMatchApply.terrainBackdropPrefill(files);
        require(family.equals(atFamily) && "nebula".equals(atWord), "a file AT the cap prefilled " + atFamily + " / "
                + atWord + ", want " + family + " / nebula - flips if the prefill read refuses a cap-sized file");
        Files.write(file, blankPadded(text, cap + 1));
        PrintStream err = System.err;
        String overFamily;
        String overWord;
        System.setErr(quiet());
        try {
            overFamily = OpsMatchApply.stationFamilyPrefill(files);
            overWord = OpsMatchApply.terrainBackdropPrefill(files);
        } finally {
            System.setErr(err);
        }
        require(defaultFamily.equals(overFamily) && MatchDescriptor.TERRAIN_NONE.equals(overWord), "a file past the"
                + " cap prefilled " + overFamily + " / " + overWord + ", want " + defaultFamily + " / "
                + MatchDescriptor.TERRAIN_NONE + " - flips if a prefill reads the whole file (an uncapped read)");
    }

    /** {@code head} then spaces up to {@code size} bytes (blank text the decoder skips). */
    private static byte[] blankPadded(byte[] head, int size) {
        byte[] out = Arrays.copyOf(head, size);
        Arrays.fill(out, head.length, size, (byte) ' ');
        return out;
    }

    // ---- the fixture ----

    /** A temp PACKAGE + the KIT its sidecars were minted over. */
    private static final class Fx {
        final Path dir;
        final Path pkg;
        final Path kit;
        final OpsRoots roots;

        Fx(Path dir) {
            this.dir = dir;
            this.pkg = dir.resolve("pkg");
            this.kit = dir.resolve("kit").resolve("CC_Client");
            this.roots = OpsRoots.packaged(pkg);
        }

        Path match(String name) {
            return pkg.resolve("match").resolve(name).toAbsolutePath().normalize();
        }
    }

    /** Writes {@code d} as {@code file} and mints its sidecar over the kit (the packer's composer). The I/O stays in
     *  this outer class (one residency row). */
    private static void seat(Fx f, Path file, MatchDescriptor d) throws IOException {
        String text = MatchDescriptorCodec.encode(d);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        Files.writeString(f.kit.resolve("match.txt"), text, StandardCharsets.UTF_8);
        Files.write(sidecarOf(file), ContentFingerprintMint.mintSidecarLines(f.kit), StandardCharsets.US_ASCII);
    }

    private static Fx fx(Path dir) {
        Fx f = new Fx(dir);
        try {
            Files.createDirectories(f.kit.resolve("jars"));
            Files.createDirectories(f.kit.resolve("data").resolve("hulls"));
            Files.writeString(f.kit.resolve("mod_info.json"), "{\"id\":\"cc_client\",\"name\":\"fixture\"}\n");
            Files.write(f.kit.resolve("jars").resolve("cc-client-mod.jar"), JAR);
            Files.writeString(f.kit.resolve("data").resolve("hulls").resolve("fixture.csv"), "id,x\nfix,1\n");
            Path match = f.pkg.resolve("match");
            Files.createDirectories(match);
            seat(f, f.match(PLAIN), MatchDescriptors.redact(MatchDescriptors.withTerrain(
                    MatchDescriptors.mint("1v1", 6000f, 0.8f), "open")));
            seat(f, f.match(FORT), MatchDescriptors.redact(MatchDescriptors.withTerrain(
                    MatchDescriptors.mint(MatchDescriptors.FORTRESS_SHAPE, 7000f, 0.6f,
                            MatchDescriptors.stationVariantForFamily("lowtech")), "hyper")));
            Files.writeString(match.resolve(PLAIN + ".password"), FIXTURE_PASSWORD);
            Files.writeString(match.resolve(FORT + ".password"), FIXTURE_PASSWORD);
            Files.writeString(match.resolve(TOKENS), "seat.1.token=live-fixture-token\n");
            Path variant = f.pkg.resolve("serve-mods").resolve("CC_Client").resolve("data").resolve("variants");
            Files.createDirectories(variant);
            Files.writeString(variant.resolve("one.variant"), "{\"variantId\":\"one\"}\n");
            Files.writeString(f.pkg.resolve("cc-server.properties"), "# fixture\nmatch.descriptor=match/" + FORT
                    + "\nnet.doorFingerprint=true\nmap.half=9000\n");
            Files.writeString(f.pkg.resolve("MANIFEST.txt"), "git.head=test\npack.stamp=fixture-1\n");
        } catch (IOException | RuntimeException e) {
            throw new SetupFailure("the fixture package did not stage (" + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + ")");
        }
        return f;
    }

    private static Path sidecarOf(Path descriptor) {
        return Paths.get(descriptor + SIDECAR_SUFFIX);
    }

    /** Re-keys the sidecar to an id nobody holds (a torn pair). */
    private static void tear(Path sidecar) throws IOException {
        Files.write(sidecar, ContentFingerprintMint.rekeySidecarLines(lines(sidecar), "stale-fixture-id"),
                StandardCharsets.US_ASCII);
    }

    private static ApplyResult applied(Fx f, Path offered, Op op, String value) {
        try {
            return OpsMatchApply.applyPackageMatchSetting(f.roots, offered, op, value, false);
        } catch (ApplyRefused refused) {
            throw new AssertionError(op + " '" + value + "' refused " + refused.token() + " ('" + refused.getMessage()
                    + "') where it must apply - flips if a legal Apply is refused");
        }
    }

    /** The Apply refuses with {@code token} + {@code sentence} and the whole temp tree is byte-equal after. */
    private static void refusesNothing(Fx f, Path offered, Op op, String value, boolean live, String token,
            String sentence, String what) throws IOException {
        Map<String, byte[]> before = snap(f.dir);
        ApplyRefused refused = null;
        try {
            OpsMatchApply.applyPackageMatchSetting(f.roots, offered, op, value, live);
        } catch (ApplyRefused expected) {
            refused = expected;
        }
        require(refused != null, what + ": the Apply returned instead of refusing " + token + " - flips if that gate"
                + " is dropped");
        require(token.equals(refused.token()) && sentence.equals(refused.getMessage()), what + ": refused "
                + refused.token() + " '" + refused.getMessage() + "', want " + token + " '" + sentence + "' - flips if"
                + " the gate order or the refusal text moves");
        requireDiff(before, snap(f.dir), List.of(), what + " - flips if a refusal writes anything");
    }

    private static String keyedId(Path sidecar) throws IOException {
        return ContentFingerprints.componentOrNull(ContentFingerprints.parseSidecarFingerprint(lines(sidecar),
                sidecar.getFileName().toString()), "match");
    }

    private static String family(MatchDescriptor d) {
        for (MatchDescriptor.RosterRow row : d.roster()) {
            if (MatchDescriptors.isStationVariantId(row.variantId())) {
                return MatchDescriptors.familyForStationVariant(row.variantId());
            }
        }
        return null;
    }

    private static MatchDescriptor decode(Path file) throws IOException {
        return MatchDescriptorCodec.decode(lines(file));
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
    }

    /** The bytes {@code Files.write(lines, US_ASCII)} writes (each line + the platform separator). */
    private static byte[] joined(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (String line : lines) {
            b.append(line).append(System.lineSeparator());
        }
        return b.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static List<String> temps(Fx f) throws IOException {
        try (Stream<Path> s = Files.list(f.pkg.resolve("match"))) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(OpsMatchApply.TEMP_SUFFIX)).toList();
        }
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    /** Every regular file under {@code root} ('/'-separated relative path to bytes). */
    private static Map<String, byte[]> snap(Path root) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.put(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return out;
    }

    /** {@code after} differs from {@code before} in EXACTLY the {@code changed} paths (written, never added / removed). */
    private static void requireDiff(Map<String, byte[]> before, Map<String, byte[]> after, List<String> changed,
            String regression) {
        List<String> moved = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            byte[] a = before.get(k);
            byte[] b = after.get(k);
            if (a == null || b == null || !Arrays.equals(a, b)) {
                moved.add(k + (a == null ? " (added)" : b == null ? " (removed)" : ""));
            }
        }
        require(moved.equals(changed), "files changed " + moved + ", want " + changed + " - " + regression);
    }

    private static String source(String rel) {
        Path p = Paths.get(rel);
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new SetupFailure(rel + " is not readable from the cwd (" + e.getClass().getSimpleName()
                    + "): run from the repo root");
        }
    }

    /** Java source with comments blanked (string / char literals kept), so a javadoc mention never counts. */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < src.length() && src.charAt(j) != c && src.charAt(j) != '\n') {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j + 1, src.length());
                out.append(src, i, j);
                i = j;
            } else if (src.startsWith("//", i)) {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                int stop = end < 0 ? src.length() : end + 2;
                for (int k = i; k < stop; k++) {
                    out.append(src.charAt(k) == '\n' ? '\n' : ' ');
                }
                i = stop;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static final Pattern MEMBER_LINE = Pattern.compile("^    [A-Za-z<][^\\n]*", Pattern.MULTILINE);
    private static final Pattern MEMBER_NAME = Pattern.compile("^    (?:[\\w<>\\[\\],.? ]+?\\s)?(\\w+)\\s*[(={;]");

    /** The class-level member (a 4-space-indented declaration line) enclosing each match of {@code p}, in order. */
    private static List<String> membersOf(String code, Pattern p) {
        List<Integer> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Matcher line = MEMBER_LINE.matcher(code);
        while (line.find()) {
            Matcher name = MEMBER_NAME.matcher(line.group());
            if (name.find()) {
                starts.add(line.start());
                names.add(name.group(1));
            }
        }
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(code);
        while (m.find()) {
            String owner = "<class>";
            for (int k = 0; k < starts.size() && starts.get(k) <= m.start(); k++) {
                owner = names.get(k);
            }
            out.add(owner);
        }
        return out;
    }

    // ---- the row harness ----

    @FunctionalInterface
    private interface Body {
        void run(Path dir) throws Exception;
    }

    /** A fixture that never staged: {@code FAIL SETUP-<row>}, never counted as a kill. */
    private static final class SetupFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SetupFailure(String message) {
            super(message);
        }
    }

    private static void row(String name, Body body) {
        Path dir;
        try {
            dir = Files.createTempDirectory("cc-match-apply-");
        } catch (IOException e) {
            System.out.println("FAIL SETUP-" + name + ": no temp dir (" + e.getClass().getSimpleName() + ")");
            failures++;
            return;
        }
        try {
            body.run(dir);
            System.out.println("PASS " + name);
        } catch (SetupFailure setup) {
            System.out.println("FAIL SETUP-" + name + ": " + setup.getMessage());
            failures++;
        } catch (AssertionError failed) {
            System.out.println("FAIL " + name + ": " + failed.getMessage());
            failures++;
        } catch (Exception | Error unexpected) {
            System.out.println("FAIL " + name + ": unexpected " + unexpected.getClass().getSimpleName() + ": "
                    + unexpected.getMessage());
            failures++;
        } finally {
            deleteTree(name, dir);
        }
    }

    /**
     * The packer CLI's own redact ({@code tools.MatchTokenRedact.redact}), reached by reflection. WHY: the tools
     * package stays home (the source export's allow-list excludes it), so a compile-time reference here made the
     * exported tree fail javac; reflection keeps the twin compare in this tree and lets an exported build record a
     * NOTE instead. Returns null when the class is absent.
     */
    private static MatchDescriptor toolRedactOrNull(MatchDescriptor d) throws Exception {
        Class<?> tool;
        try {
            tool = Class.forName("cc.starsector.server.tools.MatchTokenRedact");
        } catch (ClassNotFoundException absent) {
            return null;
        }
        return (MatchDescriptor) tool.getMethod("redact", MatchDescriptor.class).invoke(null, d);
    }

    private static void require(boolean ok, String regression) {
        if (!ok) {
            if (regression.startsWith("SETUP: ")) {
                throw new SetupFailure(regression.substring("SETUP: ".length()));
            }
            throw new AssertionError(regression);
        }
    }

    private static void deleteTree(String row, Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException leftover) {
            System.out.println("NOTE " + row + ": temp fixtures left behind (" + leftover.getClass().getSimpleName()
                    + ")");
        }
    }
}
