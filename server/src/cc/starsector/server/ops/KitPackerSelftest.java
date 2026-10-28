package cc.starsector.server.ops;

import cc.starsector.server.match.ContentFingerprintMint;
import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.MatchShapes;
import cc.starsector.server.protocol.SeatRole;
import cc.starsector.server.runtime.HandshakeCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The joiner-kit packer's build-gate rows, PASSWORD MODE only:
 * {@link KitPacker} driven over a
 * temp-dir FIXTURE per row - a release kit zip written by {@link KitZip#write} itself from a staged {@code CC_Client}
 * tree whose {@code kit.files} is minted by the REAL mint ({@link ContentFingerprintMint#mintSidecarLines}
 * = {@code ContentFingerprintTree.readContentSurface}'s walk, {@code data/missions/mission_list.csv} excluded), so the
 * packer's own surface rule ({@link KitPackRules#surfaceKeyOrNull}) is held to the mint's on every pack; a package
 * ({@code MANIFEST.txt} with {@code pack.kit} / {@code kit.jar.md5} / {@code kit.fingerprint}, {@code kits/<pack.kit>},
 * {@code match/match.txt} = the kit's shape A and {@code match/match-b.txt} = shape B with their sidecars, a
 * {@code .password} and a {@code -tokens} twin whose values must never surface); and a fake game root
 * (the game's core jar under {@code starsector-core/} + {@code data/variants/**}, {@code stations/} included).
 *
 * <p>WHY its own selftest: the packer re-packs a DOWNLOADED-class zip on a package host and writes a DISTRIBUTED
 * file, so every refusal must leave the disk byte-equal (each refusal snapshots the fixture tree before and after)
 * and every text the host reads is the {@link KitPackRules} text with its placeholders filled, never a path or a secret
 * ({@code kit-texts} grades every {@link KitPacker.Result} the other rows saw, so it runs LAST).
 *
 * <p>Each row prints {@code PASS <row>} or {@code FAIL <row>: <the regression that flipped it>}; a fixture that never
 * staged prints {@code FAIL SETUP-<row>: ...} (a setup ERROR for the mutation runner, never a mutant kill). Temp dirs
 * are deleted in {@code finally}; the swapped statics ({@link KitPacker#writeStep}; {@link OpsRoots#ROOTS} in
 * {@code kit-leak}) are restored in {@code finally}. Entry: {@link #run()} (the ops gate chain) and {@link #main} (exit 0 / 1).
 *
 *
 * The kit-keep-variants row and R13's two templates
 * DELETED with the strip; the kit-ships-every-variant row holds the whole-set pack.
 */
public final class KitPackerSelftest {

    private KitPackerSelftest() {
    }

    private static int failures;
    /** Every packer answer the rows saw, in order ({@code kit-texts} grades their texts). */
    private static final List<KitPacker.Result> SEEN = new ArrayList<>();
    /** Every fixture root of this run ({@code kit-texts}: no text may carry one). */
    private static final List<String> ROOTS = new ArrayList<>();

    private static final String PACK_KIT_NAME = "cc-joiner-kit-20261001-120000.zip";
    private static final String BASE_STAMP = "20261001-120000";
    private static final String STAMP = "20261009-171500";
    private static final String MOD_VERSION = "0.1.0";
    private static final String GAME_VERSION = "0.98a-RC8";
    private static final String MOD_INFO = "{\"id\":\"cc_client\",\"name\":\"fixture\",\"version\":{\"major\":\"0\","
            + "\"minor\":\"1\",\"patch\":\"0\"},\"gameVersion\":\"0.98a-RC8\"}\n";
    private static final String ID_A = "kitfx-shape-a-0001";
    private static final String ID_B = "kitfx-shape-b-0002";
    private static final String SECRET = "fixture-secret-pass-77";
    private static final String LIVE = "live-fixture-token-";
    private static final int HS = HandshakeCodec.HANDSHAKE_VERSION;
    private static final String MATCH = KitPackRules.MATCH_ENTRY;
    private static final String SIDECAR = KitPackRules.SIDECAR_ENTRY;
    private static final String MANIFEST = KitPackRules.MANIFEST_ENTRY;
    private static final String README = KitPackRules.README_ENTRY;
    private static final String JAR_ENTRY = KitPackRules.JAR_ENTRY;
    /** The reproducible write's pinned zip entry time, spelled here (not read off the product) so a moved product constant flips a row. */
    private static final LocalDateTime PINNED_TIME = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
    private static final byte[] JAR = fixtureJar(300, 7);

    // The kit packer's answer texts, VERBATIM (placeholders in <>)
    private static final String S_DONE = "Joiner kit made: kits\\<file> - a player needs it once; the join window"
            + " fetches every match from the server.";
    private static final String S_RESTART = "The match's fingerprint file was updated: restart the server if it is"
            + " running.";
    private static final String S_R1 = "No joiner kit to start from: put <name> (the kit this server came with;"
            + " MANIFEST.txt names it in pack.kit=) into <kits folder>, then run the kit pack again.";
    private static final String S_R2 = "<name> is not the kit this server came with (another client build or server"
            + " version) - use the kit MANIFEST.txt names in pack.kit=.";
    private static final String S_R3 = "<name> is damaged or was changed (<part>) - download it again.";
    private static final String S_R4 = "<name> holds an entry this server will not unpack (<80 chars>) - download it"
            + " again.";
    private static final String S_R5 = "<name> unpacks to more than <n> MB - it is not a joiner kit.";
    private static final String S_R6 = "The match file <shape> cannot be read: <reason>.";
    private static final String S_R7 = "The match <shape> names ships neither the joiner kit nor the game has: <6 ids>"
            + " - choose another match shape.";
    private static final String S_R8 = "Set the Game folder first: the kit check reads the game's own ship list.";
    private static final String S_R9 = "Not enough free disk space for the kit (<n> MB needed).";
    private static final String S_R11 = "Making the joiner kit took longer than <n> seconds - nothing was written."
            + " Run the kit pack again.";
    private static final String S_R12 = "The match file would show joiners a real seat code - nothing was"
            + " written.";

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** The ops gate's entry: every row, the summary line, the failure count returned. */
    public static int run() {
        failures = 0;
        SEEN.clear();
        ROOTS.clear();
        row("kit-repack-roundtrip", KitPackerSelftest::repackRoundtrip);
        row("kit-rekey-equals-mint", KitPackerSelftest::rekeyEqualsMint);
        row("kit-verify-before-write", KitPackerSelftest::verifyBeforeWrite);
        row("kit-leak", KitPackerSelftest::leak);
        row("kit-input-refusals", KitPackerSelftest::inputRefusals);
        row("kit-census", KitPackerSelftest::census);
        row("kit-check-zip", KitPackerSelftest::checkZip);
        row("kit-zip-reproducible", KitPackerSelftest::zipReproducible);
        row("kit-rules-twin", dir -> rulesTwin());
        row("kit-pack-dir", KitPackerSelftest::packDir);
        row("kit-commit-gate", KitPackerSelftest::commitGate);
        row("kit-ships-every-variant", KitPackerSelftest::shipsEveryVariant);
        row("kit-texts", dir -> texts());
        System.out.println("KitPackerSelftest: " + (failures == 0 ? "ALL PASS" : failures + " FAILED"));
        return failures;
    }

    // ---- the rows ----

    private static void repackRoundtrip(Path dir) throws Exception {
        Fx f = fx(dir, KitPackRules.SessionBlock.PASSWORD_ARMED);
        Path out = f.out("match-b");
        Map<String, byte[]> before = snap(f.dir);
        KitPacker.Result checked = seen(KitPacker.check(f.request(f.descB, out)));
        require(checked.ok() && KitPacker.CHECKED.equals(checked.token()) && out.equals(checked.file()),
                "check() answered " + checked.token() + " '" + checked.text() + "' - flips if the package-time proof"
                        + " refuses a good release kit + an offered shape");
        requireSame(before, snap(f.dir), "check() wrote - flips if the no-write proof writes the kit or the sidecar");
        KitPacker.Result r = seen(KitPacker.pack(f.request(f.descB, out)));
        require(r.ok() && KitPacker.MADE.equals(r.token()) && out.equals(r.file()) && !r.sidecarChanged(),
                "pack answered " + r.token() + " '" + r.text() + "' changed=" + r.sidecarChanged() + " - flips if a"
                        + " good release kit + an offered shape is refused, or an in-sync package sidecar is rewritten");
        requireAdded(before, snap(f.dir), List.of(rel(f, out)), "flips if the pack leaves a temp or writes beside the"
                + " kit (MANIFEST.txt, match/)");
        Map<String, byte[]> in = entries(f.kit);
        Map<String, byte[]> got = entries(out);
        require(new TreeSet<>(in.keySet()).equals(new TreeSet<>(got.keySet())), "entry names " + got.keySet()
                + " != the release kit's " + in.keySet() + " - flips if the re-pack drops, adds or renames an entry");
        Set<String> moved = new TreeSet<>();
        for (String name : in.keySet()) {
            if (!Arrays.equals(in.get(name), got.get(name))) {
                moved.add(name);
            }
        }
        Set<String> one = new TreeSet<>(List.of(MANIFEST));
        Set<String> two = new TreeSet<>(List.of(MANIFEST, README));
        require(moved.containsAll(one) && two.containsAll(moved), "changed entries " + moved + ", want " + one
                + " - flips if the re-pack leaves the kitStamp stale, re-keys kit.files per shape, or alters another"
                + " entry (the jar, data/**, the bat)");
        require(!got.containsKey(MATCH), "the packed kit holds " + MATCH + " - flips if the packer composes a match"
                + " entry again");
        require(Arrays.equals(got.get(MANIFEST), ascii(KitPackRules.manifestText(f.jarMd5, STAMP, MOD_VERSION,
                GAME_VERSION))), "the packed kit-manifest.txt is not manifestText(jar, the fresh stamp, mod_info's"
                        + " versions) - flips if the kitStamp row keeps the base stamp or the versions are misread");
        require(Arrays.equals(got.get(SIDECAR), KitPacker.sidecarBytes(KitPacker.kitSidecarLines(
                lines(Files.readAllBytes(sidecarOf(f.descB)))))), "the packed kit.files != the package's"
                + " match-b.txt.fingerprint with its match term stripped - flips if the kit and the door's sidecar"
                + " fork in content");
        require(in.containsKey("CC_Client/data/missions/mission_list.csv"), "SETUP: the fixture kit lost the"
                + " mint-excluded mission_list.csv (the surface-rule parity premise)");
        require(!moved.contains(README), README + " was rewritten in zip: mode - flips if the zip: re-pack stops"
                + " keeping the base README (a zip: re-pack of a current kit changes one entry: the kit manifest)");
        // a pre-wire base kit (still carrying match.txt): its match file never rides into the re-pack
        TreeMap<String, byte[]> prewire = new TreeMap<>(f.entries);
        prewire.put(MATCH, utf8(MatchDescriptorCodec.encode(MatchDescriptors.redact(f.a))));
        writeKit(f.kit, prewire);
        Path fromPrewire = f.out("match-prewire");
        KitPacker.Result pr = seen(KitPacker.pack(f.request(f.descB, fromPrewire)));
        restore(f);
        require(pr.ok(), "a pre-wire base kit re-packed answered " + pr.token() + " - flips if the zip: verify starts"
                + " refusing a base kit for its match entry instead of dropping it");
        require(!entries(fromPrewire).containsKey(MATCH), "a pre-wire base kit re-packed kept its " + MATCH
                + " - flips if the packer stops dropping a base kit's match file");
    }

    private static void rekeyEqualsMint(Path dir) throws Exception {
        Fx f = fx(dir, KitPackRules.SessionBlock.PASSWORD_ARMED);
        byte[] viaRekeyB = null;
        for (Object[] shape : new Object[][] {{f.descA, f.a, "match"}, {f.descB, f.b, "match-b"}}) {
            Path desc = (Path) shape[0];
            MatchDescriptor d = (MatchDescriptor) shape[1];
            String slug = (String) shape[2];
            Path out = f.out(slug);
            KitPacker.Result r = seen(KitPacker.pack(f.request(desc, out)));
            require(r.ok() && KitPacker.MADE.equals(r.token()), slug + ": answered " + r.token() + " '" + r.text()
                    + "' - flips if a shipped shape cannot be packed");
            byte[] packed = entries(out).get(SIDECAR);
            byte[] server = Files.readAllBytes(sidecarOf(desc));
            Path rekeyed = f.dir.resolve("rekeyed-" + slug + ".fingerprint");
            ContentFingerprintMint.writeSidecar(rekeyed,
                    ContentFingerprintMint.rekeySidecarLines(f.kitSidecar, d.matchId()));
            byte[] viaRekey = Files.readAllBytes(rekeyed);
            byte[] viaMint = freshMint(f, d, slug);
            require(Arrays.equals(server, viaRekey), slug + ": the package sidecar != writeSidecar(rekeySidecarLines("
                    + "mint, id)) - flips if the packer mints the door's sidecar by another route or another EOL / charset");
            require(Arrays.equals(viaRekey, viaMint), slug + ": the re-key != a fresh mint over the same surface"
                    + " - flips if rekeySidecarLines keeps the old match= or touches another component / row");
            require(d.matchId().equals(ContentFingerprints.componentOrNull(ContentFingerprints.parseSidecarFingerprint(
                    lines(server), "server"), "match")), slug + ": the package sidecar's match= is not the shape's"
                    + " match.id - flips if the server mint drops or misses the match= component");
            String kitComposite = ContentFingerprints.parseSidecarFingerprint(lines(packed), "packed");
            require(ContentFingerprints.componentOrNull(kitComposite, "match") == null
                    && kitComposite.equals(ContentFingerprints.stripMatch(ContentFingerprints.parseSidecarFingerprint(
                            lines(server), "server"))), slug + ": the packed kit.files composite is not the package"
                    + " sidecar's minus its match term - flips if kit.files carries the match term again or forks in content");
            require(Arrays.equals(packed, f.entries.get(SIDECAR)), slug + ": the packed kit.files != the release kit's"
                    + " - flips if kit.files is re-keyed per shape (a kit holds no match)");
            if (d == f.b) {
                viaRekeyB = viaRekey;
            }
        }
        // a torn package sidecar is put back by the one writer; the restart sentence follows the lock probe
        tear(sidecarOf(f.descB));
        KitPacker.Result fixed = seen(KitPacker.pack(f.request(f.descB, f.out("match-b-2"))));
        require(fixed.ok() && fixed.sidecarChanged() && !fixed.text().contains(KitPackRules.TEXT_RESTART),
                "a torn package sidecar: answered " + fixed.token() + " changed=" + fixed.sidecarChanged() + " '"
                        + fixed.text() + "' - flips if the sidecar is not rewritten or the restart sentence shows with"
                        + " no lock held");
        require(Arrays.equals(Files.readAllBytes(sidecarOf(f.descB)), viaRekeyB), "the rewritten package sidecar !="
                + " the kit's re-key - flips if the package sidecar write forks from the kit's bytes");
        tear(sidecarOf(f.descB));
        try (FileChannel ch = FileChannel.open(f.pkg.resolve(ServerOpsLaunch.STAGE_LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock held = ch.lock()) {
            KitPacker.Result locked = seen(KitPacker.pack(f.request(f.descB, f.out("match-b-3"))));
            require(locked.ok() && locked.sidecarChanged() && locked.text().endsWith(" " + KitPackRules.TEXT_RESTART),
                    "stage.lock held: '" + locked.text() + "' - flips if the restart sentence stops following a held"
                            + " stage.lock (a server starting on the old sidecar)");
        }
        tear(sidecarOf(f.descB));
        KitPacker.Result unknown = seen(KitPacker.pack(f.request(f.descB, f.out("match-b-4"),
                KitPacker.Posture.REDACT, null, f.game, 0)));
        require(unknown.ok() && unknown.sidecarChanged() && unknown.text().endsWith(" " + KitPackRules.TEXT_RESTART),
                "no MANIFEST (package root unknown): '" + unknown.text() + "' - flips if an unknown package root"
                        + " stops reading as 'a server may hold the old sidecar'");
    }

    private static void verifyBeforeWrite(Path dir) throws Exception {
        Fx f = fx(dir, KitPackRules.SessionBlock.PASSWORD_ARMED);
        String data = ContentFingerprints.componentOrNull(f.composite, "data");
        String r2 = KitPackRules.textR2(PACK_KIT_NAME);
        byte[] jar2 = fixtureJar(300, 99);
        TreeMap<String, byte[]> other = new TreeMap<>(f.entries);
        other.put(JAR_ENTRY, jar2);
        other.put(SIDECAR, writerBytes(f, withComposite(f.kitSidecar,
                ContentFingerprints.composite(ContentFingerprints.md5Hex(jar2), data, HS, ID_A))));
        refusedNothing(f, other, null, KitPacker.NOT_THIS_KIT, r2, "another client build (jar + its sidecar agree,"
                + " MANIFEST kit.jar.md5 does not)");
        TreeMap<String, byte[]> swapped = new TreeMap<>(f.entries);
        swapped.put(JAR_ENTRY, jar2);
        refusedNothing(f, swapped, null, KitPacker.NOT_THIS_KIT, r2, "a jar its own sidecar's jar= does not name");
        TreeMap<String, byte[]> edited = new TreeMap<>(f.entries);
        edited.put("CC_Client/data/variants/kitship_A.variant", utf8("{\"variantId\":\"kitship_A\",\"edited\":1}\n"));
        refusedNothing(f, edited, null, KitPacker.DAMAGED, KitPackRules.textR3(PACK_KIT_NAME, "data"),
                "a data-surface byte changed under the same sidecar");
        List<String> ghostRow = new ArrayList<>(f.kitSidecar);
        ghostRow.add("file=data/variants/ghost.variant");
        TreeMap<String, byte[]> ghost = new TreeMap<>(f.entries);
        ghost.put(SIDECAR, writerBytes(f, ghostRow));
        refusedNothing(f, ghost, null, KitPacker.DAMAGED, KitPackRules.textR3(PACK_KIT_NAME, "kit.files"),
                "a file= row the kit's surface does not hold");
        TreeMap<String, byte[]> nextHs = new TreeMap<>(f.entries);
        nextHs.put(SIDECAR, writerBytes(f, withComposite(f.kitSidecar,
                ContentFingerprints.composite(f.jarMd5, data, HS + 1, ID_A))));
        refusedNothing(f, nextHs, null, KitPacker.NOT_THIS_KIT, r2, "a kit sidecar minted for hs=" + (HS + 1));
        String otherKit = withoutMatch(ContentFingerprints.composite(f.jarMd5, ContentFingerprints.md5Hex(
                utf8("another kit")), HS, "x"));
        refusedNothing(f, null, manifestText(PACK_KIT_NAME, f.jarMd5, otherKit), KitPacker.NOT_THIS_KIT, r2,
                "MANIFEST kit.fingerprint of another kit (composite minus match= differs)");
        refusedNothing(f, null, manifestText("other-kit.zip", f.jarMd5, withoutMatch(f.composite)),
                KitPacker.NOT_THIS_KIT, r2, "MANIFEST pack.kit names another zip");
        Files.writeString(f.manifest, manifestText(PACK_KIT_NAME, f.jarMd5, f.composite), StandardCharsets.US_ASCII);
        KitPacker.Result full = seen(KitPacker.pack(f.request(f.descB, f.out("match-b"))));
        require(full.ok() && KitPacker.MADE.equals(full.token()), "kit.fingerprint carrying its match= ("
                + "PackageManifest's documented form) refused " + full.token() + " - flips if the compare stops"
                + " dropping match= on the MANIFEST side");
    }

    private static void leak(Path dir) throws Exception {
        Fx f = fx(dir, KitPackRules.SessionBlock.PASSWORD_ARMED);
        String[][] privates = {
            {"CC_Client/match.txt.password", "password=" + SECRET + "\n"},
            {"CC_Client/data/match-b-tokens.txt", "seat.1.token=" + LIVE + "twin\n"},
            {"CC_Client/cc-door-credentials.properties", "door=" + SECRET + "\n"},
            {"CC_Client/OPS.ADMINPASSWORD", SECRET + "\n"}};
        for (String[] leak : privates) {
            TreeMap<String, byte[]> withLeak = new TreeMap<>(f.entries);
            withLeak.put(leak[0], utf8(leak[1]));
            refusedNothing(f, withLeak, null, KitPacker.ENTRY, KitPackRules.textR4(PACK_KIT_NAME, leak[0]),
                    "a base kit carrying " + leak[0]);
        }
        MatchDescriptor live = descriptor("kitfx-live-0003", List.of("kitship_B", "vanilla_X"), "live");
        Path liveDesc = f.match.resolve("match-live.txt");
        Files.writeString(liveDesc, MatchDescriptorCodec.encode(live), StandardCharsets.UTF_8);
        Path out = f.out("match-live");
        KitPacker.Result r = seen(KitPacker.pack(f.request(liveDesc, out)));
        require(r.ok() && KitPacker.MADE.equals(r.token()), "a live-token match file under REDACT answered "
                + r.token() + " - flips if REDACT stops redacting before the R12 gate");
        Map<String, byte[]> got = entries(out);
        require(!got.containsKey(MATCH), "a live-token match file packed a " + MATCH + " entry - flips if the packer"
                + " composes the kit's match file again");
        for (Map.Entry<String, byte[]> e : got.entrySet()) {
            require(!has(e.getValue(), LIVE) && !has(e.getValue(), SECRET), e.getKey() + " carries a live seat token"
                    + " or the match password - flips if a secret reaches the distributed zip");
        }
        require(!r.text().contains(LIVE) && !r.text().contains(SECRET), "the Done text carries a secret");
        Path keepOut = f.out("match-keep");
        Map<String, byte[]> before = snap(f.dir);
        // the off-thread submit leg died with the executor; the zip: pack(KEEP) leg keeps its intent through run()
        KitPacker.Result kz = seen(KitPacker.pack(f.request(liveDesc, keepOut, KitPacker.Posture.KEEP, f.manifest,
                f.game, 0)));
        require(KitPacker.LIVE_TOKEN.equals(kz.token()) && KitPackRules.TEXT_R12.equals(kz.text()), "pack(KEEP) in"
                + " zip: mode answered " + kz.token() + " - flips if a host re-pack can keep live tokens");
        requireSame(before, snap(f.dir), "a KEEP refusal wrote - flips if R12 lands after the write");
        Path pw = f.match.resolve("match.txt.password");
        KitPacker.Result p = seen(KitPacker.pack(f.request(pw, f.out("match-pw"))));
        require(KitPacker.MATCH_READ.equals(p.token()) && KitPackRules.textR6("match.txt.password",
                "it is a private file and is never packed").equals(p.text()) && !p.text().contains(SECRET),
                "the .password offered as the match file answered " + p.token() + " - flips if the leak law stops"
                        + " refusing a private file before reading it");
        requireSame(before, snap(f.dir), "the .password refusal wrote");
    }

    private static void inputRefusals(Path dir) throws Exception {
        Fx f = fx(dir, KitPackRules.SessionBlock.PASSWORD_ARMED);
        Files.delete(f.kit);
        Map<String, byte[]> before = snap(f.dir);
        KitPacker.Result none = seen(KitPacker.pack(f.request(f.descB, f.out("match-b"))));
        require(KitPacker.NO_KIT.equals(none.token()) && KitPackRules.textR1(PACK_KIT_NAME, "kits\\")
                .equals(none.text()), "no kit at kits/<pack.kit>: " + none.token() + " '" + none.text() + "' - flips if"
                + " R1 stops naming the kit + the kits folder (never a path)");
        requireSame(before, snap(f.dir), "R1 wrote");
        writeKit(f.kit, f.entries);
        for (String slip : List.of("../x", "a\\b", "c:x", "/abs")) {
            refusedRaw(f, withExtra(f, slip, utf8("x")), KitPacker.ENTRY, KitPackRules.textR4(PACK_KIT_NAME, slip),
                    "a zip-slip entry " + slip);
        }
        List<String> many = new ArrayList<>(f.entries.keySet());
        Map<String, byte[]> manyBytes = new TreeMap<>(f.entries);
        for (int i = 0; many.size() <= KitPackRules.MAX_ENTRIES; i++) {
            String n = "CC_Client/data/n/" + i + ".txt";
            many.add(n);
            manyBytes.put(n, new byte[] {1});
        }
        rawZip(f.kit, many, manyBytes, null);
        KitPacker.Result count = seen(KitPacker.pack(f.request(f.descB, f.out("match-b"))));
        require(KitPacker.ENTRY.equals(count.token()) && count.text().startsWith(PACK_KIT_NAME + " holds an entry this"
                + " server will not unpack (CC_Client/data/n/"), many.size() + " entries: " + count.token() + " '"
                + count.text() + "' - flips if the 4096-entry cap is lifted");
        Map<String, byte[]> zeros = new TreeMap<>(f.entries);
        zeros.put("CC_Client/data/zeros.bin", new byte[1 << 20]);
        refusedRaw(f, new Object[] {new ArrayList<>(zeros.keySet()), zeros}, KitPacker.ENTRY,
                KitPackRules.textR4(PACK_KIT_NAME, "CC_Client/data/zeros.bin"), "a 1 MiB entry of zeros (ratio > 200)");
        refusedRaw(f, withExtra(f, "CC_Client/empty/", null), KitPacker.ENTRY,
                KitPackRules.textR4(PACK_KIT_NAME, "CC_Client/empty/"), "a directory entry that prefixes no file");
        refusedRaw(f, withExtra(f, "CC_Client/data/variants/KITSHIP_A.variant", utf8("{}")), KitPacker.ENTRY,
                KitPackRules.textR4(PACK_KIT_NAME, "CC_Client/data/variants/KITSHIP_A.variant"),
                "two names equal after case folding");
        Files.delete(f.kit);
        try (SeekableByteChannel ch = Files.newByteChannel(f.kit, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            ch.position(KitPackRules.MAX_INPUT_BYTES);
            ch.write(ByteBuffer.wrap(new byte[] {1}));
        }
        require(Files.size(f.kit) == KitPackRules.MAX_INPUT_BYTES + 1, "SETUP: the sparse over-cap kit did not stage");
        KitPacker.Result big = seen(KitPacker.pack(f.request(f.descB, f.out("match-b"))));
        require(KitPacker.TOO_BIG.equals(big.token()) && KitPackRules.textR5(PACK_KIT_NAME, 256).equals(big.text()),
                "a 256 MiB + 1 kit: " + big.token() + " '" + big.text() + "' - flips if the 256 MiB input cap moves or"
                        + " maps to another refusal");
        require(kitsNames(f).equals(List.of(PACK_KIT_NAME)), "kits/ after the over-cap refusal holds " + kitsNames(f)
                + " - flips if a refused pack leaves a file");
        Files.delete(f.kit);
        writeKit(f.kit, f.entries);
        KitZip.Refused inflated = null;
        try {
            KitZip.read(f.kit, new KitZip.Caps(Long.MAX_VALUE, KitPackRules.MAX_ENTRIES, 100, 1 << 20,
                    KitPackRules.MAX_RATIO));
        } catch (KitZip.Refused refused) {
            inflated = refused;
        }
        require(inflated != null && inflated.kind == KitZip.Kind.INFLATED, "the reader under a 100 B inflated cap"
                + " answered " + (inflated == null ? "no refusal" : inflated.kind) + " - flips if the inflated total"
                + " stops being counted on the bytes read (the pack-level R5 mapping has no caps seam)");
        for (String bad : List.of("../evil.zip", "a/b.zip", "a b.zip", "x.ZIP", "kit.zip.part")) {
            require(KitPacker.baseKitPath(f.kits, bad) == null, "baseKitPath accepted pack.kit=" + bad + " - flips if"
                    + " the pack.kit grammar or the resolved-parent check is dropped");
        }
        require(f.kit.toAbsolutePath().normalize().equals(KitPacker.baseKitPath(f.kits, PACK_KIT_NAME)),
                "baseKitPath refused the real pack.kit - flips if the grammar refuses a release kit name");
        Path badName = f.kits.resolve("bad name.zip");
        writeKit(badName, f.entries);
        before = snap(f.dir);
        KitPacker.Result named = seen(KitPacker.pack(new KitPacker.Request(badName, false, f.descB,
                KitPacker.Posture.REDACT, f.out("match-b"), sidecarOf(f.descB), STAMP, f.manifest, f.game, null, 0)));
        require(KitPacker.NOT_THIS_KIT.equals(named.token()) && KitPackRules.textR2("bad name.zip").equals(named.text()),
                "a base named outside the pack.kit grammar: " + named.token() + " - flips if the packer reads a kit"
                        + " the MANIFEST could not have named");
        requireSame(before, snap(f.dir), "the grammar refusal wrote");
    }

    private static void census(Path dir) throws Exception {
        Fx f = fx(dir, KitPackRules.SessionBlock.PASSWORD_ARMED);
        Set<String> kitIds = MatchShapes.shippedVariantIds(f.kitSidecar);
        require(kitIds.equals(Set.of("kitship_A", "kitship_B")), "the kit ships " + kitIds + " - flips if the"
                + " census's kit list stops reading the sidecar's data/variants file= rows");
        Path install = f.game;
        Path core = f.game.resolve("starsector-core");
        requireCensus(kitIds, List.of("kitship_B"), null, null, List.of(), "a kit-only roster with no game folder");
        requireCensus(kitIds, List.of("kitship_B", "vanilla_X"), install, null, List.of(), "vanilla under the install"
                + " root");
        requireCensus(kitIds, List.of("kitship_B", "vanilla_X"), core, null, List.of(), "vanilla under starsector-core");
        requireCensus(kitIds, List.of("vanilla_station_Y"), install, null, List.of(), "a station under"
                + " data/variants/stations/ (the walk must recurse)");
        requireCensus(kitIds, List.of("Upper_Z"), install, null, List.of(), "a vanilla file named .VARIANT");
        List<String> ghosts = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            ghosts.add("ghost_" + i);
        }
        requireCensus(kitIds, ghosts, install, KitPacker.CENSUS, ghosts, "seven unknown ids");
        requireCensus(kitIds, List.of("vanilla_X"), null, KitPacker.GAME_FOLDER, List.of("vanilla_X"),
                "a vanilla id with no game folder");
        Path notGame = f.dir.resolve("not-a-game");
        Files.createDirectories(notGame.resolve("data").resolve("variants"));
        Files.writeString(notGame.resolve("data").resolve("variants").resolve("vanilla_X.variant"), "{}\n");
        requireCensus(kitIds, List.of("vanilla_X"), notGame, KitPacker.GAME_FOLDER, List.of("vanilla_X"),
                "a folder without the core jar");
        List<String> roster = new ArrayList<>(List.of("kitship_B"));
        roster.addAll(ghosts);
        Path ghostDesc = f.match.resolve("match-ghost.txt");
        Files.writeString(ghostDesc, MatchDescriptorCodec.encode(MatchDescriptors.redact(descriptor("kitfx-ghost-0004",
                roster, "g"))), StandardCharsets.UTF_8);
        Map<String, byte[]> before = snap(f.dir);
        KitPacker.Result r7 = seen(KitPacker.pack(f.request(ghostDesc, f.out("match-ghost"))));
        require(KitPacker.CENSUS.equals(r7.token()) && KitPackRules.textR7("match-ghost", ghosts.subList(0, 6))
                .equals(r7.text()) && !r7.text().contains("ghost_7"), "seven unknown ids packed: " + r7.token() + " '"
                + r7.text() + "' - flips if R7 names more than six ids, the wrong ones, or lets the pack through");
        KitPacker.Result r8 = seen(KitPacker.pack(f.request(f.descB, f.out("match-b"), KitPacker.Posture.REDACT,
                f.manifest, null, 0)));
        require(KitPacker.GAME_FOLDER.equals(r8.token()) && KitPackRules.TEXT_R8.equals(r8.text()), "a vanilla id"
                + " with no game folder packed: " + r8.token() + " - flips if R8 stops gating the census");
        requireSame(before, snap(f.dir), "a census refusal wrote");
    }

    private static void zipReproducible(Path dir) throws Exception {
        Fx f = fx(dir, KitPackRules.SessionBlock.PASSWORD_ARMED);
        Path one = f.out("match-b");
        Path two = f.out("match-b-again");
        KitPacker.Result r1 = seen(KitPacker.pack(f.request(f.descB, one)));
        KitPacker.Result r2 = seen(KitPacker.pack(f.request(f.descB, two)));
        require(r1.ok() && r2.ok(), "SETUP: the two packs did not both land (" + r1.token() + ", " + r2.token() + ")");
        require(Arrays.equals(Files.readAllBytes(one), Files.readAllBytes(two)), "two packs of the same entries"
                + " differ - flips if the writer stamps a clock time, an unsorted order or a random byte");
        List<String> names = new ArrayList<>();
        try (ZipFile z = new ZipFile(one.toFile())) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                names.add(e.getName());
                require(!e.isDirectory(), e.getName() + " is a directory entry - flips if the writer emits folders");
                require(PINNED_TIME.equals(e.getTimeLocal()), e.getName() + " time " + e.getTimeLocal() + ", want"
                        + " 2026-01-01T00:00 - flips if KitZip.write's entry-time pin (setTimeLocal(ENTRY_TIME)) moves");
                require(e.getMethod() == ZipEntry.DEFLATED, e.getName() + " is not DEFLATED");
            }
        }
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        require(names.equals(sorted), "entries are not in ordinal name order: " + names + " - flips if the sort goes");
        List<String> layout = new ArrayList<>(List.of("CC_Client/", "CC_Client/data/", "CC_Client/data/variants/"));
        List<String> files = new ArrayList<>(f.entries.keySet());
        Collections.reverse(files);
        layout.addAll(files);
        rawZip(f.kit, layout, f.entries, FileTime.fromMillis(System.currentTimeMillis()));
        Path three = f.out("match-b-raw");
        KitPacker.Result r3 = seen(KitPacker.pack(f.request(f.descB, three)));
        require(r3.ok() && Arrays.equals(Files.readAllBytes(one), Files.readAllBytes(three)), "a Compress-Archive"
                + " shaped base (directory entries, host mtimes, reversed order) packed to other bytes (" + r3.token()
                + ") - flips if a base's layout or its directory entries leak into the output");
    }

    private static void rulesTwin() throws IOException {
        String measured = "kitStamp=20261007-104647\r\njarMd5=982EEC69225C69EA346C43AC0FBE3A19\r\nmodVersion=0.1.0\r\n"
                + "gameVersion=0.98a-RC8\r\nsource=kit\r\n";
        String m = KitPackRules.manifestText("982eec69225c69ea346c43ac0fbe3a19", "20261007-104647", MOD_VERSION,
                GAME_VERSION);
        require(m.equals(measured) && m.length() == 120, "manifestText != the release kit 20261007-104647's 120 B"
                + " - flips if a row, its order, the CRLF or the jarMd5 case moves");
        require(m.split("\r\n", -1).length == 6 && m.replace("\r\n", "").indexOf('\n') < 0
                && m.replace("\r\n", "").indexOf('\r') < 0, "the manifest is not 5 CRLF rows");
        Map<String, String> rows = new LinkedHashMap<>();
        for (String row : m.split("\r\n")) {
            rows.put(row.substring(0, row.indexOf('=')), row.substring(row.indexOf('=') + 1));
        }
        require(rows.get("jarMd5").matches("[0-9A-F]{32}"), "the jarMd5 row is not UPPERCASE hex");
        Pattern vguard = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._:-]{0,255}");
        for (String name : List.of("kitStamp", "jarMd5")) {
            require(rows.containsKey(name) && vguard.matcher(rows.get(name)).matches(), name + " is missing or fails"
                    + " join-cc.bat's vguard charset - flips if the bat would hard-stop on a fresh kit");
        }
        Path bat = Paths.get("client-mod", "mod", "join-cc.bat");
        if (Files.isRegularFile(bat)) {
            String src = Files.readString(bat, StandardCharsets.ISO_8859_1);
            int at = src.indexOf("kit-manifest.txt\") do (");
            int end = at < 0 ? -1 : src.indexOf("\n)", at);
            require(at >= 0 && end > at, "join-cc.bat no longer reads kit-manifest.txt in one for /f block - re-anchor"
                    + " this row");
            Matcher read = Pattern.compile("if /i \"%%A\"==\"([A-Za-z]+)\"").matcher(src.substring(at, end));
            Set<String> wanted = new TreeSet<>();
            while (read.find()) {
                wanted.add(read.group(1));
            }
            require(!wanted.isEmpty() && rows.keySet().containsAll(wanted), "join-cc.bat reads " + wanted + " from"
                    + " kit-manifest.txt but manifestText writes " + rows.keySet() + " - flips if a row the bat greps"
                    + " is renamed or dropped");
        } else {
            System.out.println("NOTE kit-rules-twin: client-mod/mod/join-cc.bat is not under the cwd - the bat's row"
                    + " names were checked against the fixed pair (kitStamp, jarMd5) only");
        }
        for (String[] bad : new String[][] {{"xyz", "20261007-104647", "0.1.0"}, {"982eec69225c69ea346c43ac0fbe3a19",
                "2026-10-07", "0.1.0"}, {"982eec69225c69ea346c43ac0fbe3a19", "20261007-104647", "0.1\n.0"}}) {
            boolean threw = false;
            try {
                KitPackRules.manifestText(bad[0], bad[1], bad[2], GAME_VERSION);
            } catch (IllegalArgumentException refused) {
                threw = true;
            }
            require(threw, "manifestText accepted " + Arrays.toString(bad).replace("\n", "\\n") + " - flips if a value"
                    + " the bat's vguard / md5 compare would choke on is written");
        }
        // Every posture tells a player the SAME thing - the join window fetches
        // the match - and no README carries a retired paragraph that hands a player a match or a credential text.
        // re-keyed to the Markdown README (one line per paragraph, "## " headings)
        String fetched = "That is all you need: the join window fetches the match from the server by itself.";
        String jreHeading = "\n## This kit brings its own Java\n\n";
        List<String> retired = List.of("this kit has no match password set up", "MATCH CREDENTIAL: it ships inside",
                "the host sends you a short credential text", "the match credential ships inside it",
                "If the host announces a new kit or");
        Set<String> distinct = new TreeSet<>();
        for (KitPackRules.SessionBlock v : KitPackRules.SessionBlock.values()) {
            String text = KitPackRules.readmeText(v, null);
            distinct.add(text);
            requireReadmeShape(text, v.name());
            require(text.contains(fetched), v + ": the README lacks the fetched-match paragraph - flips if a posture"
                    + " picks another block again");
            for (String gone : retired) {
                require(!text.contains(gone), v + " carries the retired text '" + gone + "' - flips if a per-posture"
                        + " paragraph or the kit-carries-the-match note comes back");
            }
            require(!text.contains(jreHeading) && !text.contains("(Eclipse Temurin"), v + ": a JRE block with no"
                    + " bundled runtime - flips if the null-runtime README stops leaving the JRE section out");
            require(text.contains("send it to the host.\n\n## Updating the kit\n"), v + ": the no-runtime README does"
                    + " not hold exactly ONE blank line between the last troubleshooting bullet and the updating"
                    + " section - flips if the null-runtime composer leaves an empty JRE gap");
        }
        require(distinct.size() == 1, "the " + KitPackRules.SessionBlock.values().length + " postures compose "
                + distinct.size() + " READMEs - flips if a posture's README differs again");
        String jre = KitPackRules.readmeText(KitPackRules.SessionBlock.PASSWORD_ARMED, "Temurin-25.0.1+8");
        requireReadmeShape(jre, "jre");
        int needAt = jre.indexOf("\n## What you need\n\n");
        int sessionAt = jre.indexOf(fetched);
        int joinAt = jre.indexOf("\n## Join a match\n\n");
        int troubleAt = jre.indexOf("\n## If something goes wrong\n\n");
        int jreHeadAt = jre.indexOf(jreHeading);
        int jreAt = jre.indexOf(jreHeading + "The kit's own Java runtime is `CC_Client\\jre-cc` (Eclipse Temurin"
                + " 25.0.1+8, about 50 MB): ");
        int updateAt = jre.indexOf("\n## Updating the kit\n\n");
        require(needAt > 0 && needAt < sessionAt && sessionAt < joinAt && joinAt < troubleAt && troubleAt < jreHeadAt
                && jreHeadAt == jreAt && jreAt < updateAt, "the JRE section is absent, keeps its Temurin- prefix or is"
                + " out of the README's order (## What you need, ## Join a match, ## If something goes wrong, ## This"
                + " kit brings its own Java, ## Updating the kit) - flips if the README composer moves a block");
        require(Arrays.equals(KitPackRules.modInfoVersions(MOD_INFO), new String[] {MOD_VERSION, GAME_VERSION}),
                "modInfoVersions misread the fixture mod_info.json");
        require("cc-joiner-kit-20261009-171500-match-b.zip".equals(KitPackRules.kitFileName(STAMP, "match-b"))
                && "match-b".equals(KitPackRules.slugOf("match-b.txt")) && KitPackRules.slugOf("Match.txt") == null,
                "the kit file name / shape slug grammar moved");
    }

    private static void packDir(Path dir) throws Exception {
        Fx f = fx(dir, KitPackRules.SessionBlock.REDACTED_NO_PASSWORD);
        Path zipOut = f.out("match-b");
        KitPacker.Result z = seen(KitPacker.pack(f.request(f.descB, zipOut)));
        require(z.ok(), "SETUP: the zip: pack did not land (" + z.token() + ")");
        List<String> names = new ArrayList<>(f.entries.keySet());
        Path dirOut = f.out("match-b-dir");
        KitPacker.Result d = seen(KitPacker.pack(new KitPacker.Request(f.stage, true, f.descB,
                KitPacker.Posture.REDACT, dirOut, null, STAMP, null, f.game, names, 0)));
        require(d.ok() && KitPacker.MADE.equals(d.token()), "dir: mode answered " + d.token() + " '" + d.text()
                + "' - flips if the roll's stage pack refuses the staged kit");
        require(Arrays.equals(Files.readAllBytes(zipOut), Files.readAllBytes(dirOut)), "dir: over the stage !="
                + " zip: over the equivalent kit - flips if the dir: mint, README or manifest forks from the zip:"
                + " re-key / kept README");
        Map<String, byte[]> dirKit = entries(dirOut);
        require(!dirKit.containsKey(MATCH), "the dir: kit holds " + MATCH + " - flips if the packer composes the"
                + " kit's match file again");
        require(ContentFingerprints.componentOrNull(ContentFingerprints.parseSidecarFingerprint(
                lines(dirKit.get(SIDECAR)), "dir kit"), "match") == null, "the dir: kit's kit.files composite carries"
                + " a match term - flips if kit.files is minted with the match (it must hold content only)");
        List<String> withMatch = new ArrayList<>(names);
        withMatch.add(MATCH);
        Path listedOut = f.out("match-b-listed");
        KitPacker.Result listed = seen(KitPacker.pack(new KitPacker.Request(f.stage, true, f.descB,
                KitPacker.Posture.REDACT, listedOut, null, STAMP, null, f.game, withMatch, 0)));
        require(listed.ok(), "SETUP: the dir: pack with the stage's match.txt listed did not land (" + listed.token()
                + ")");
        require(!entries(listedOut).containsKey(MATCH), "a stage listing its match.txt packed it into the kit -"
                + " flips if dir: mode stops dropping a staged match file");
        Path list = f.dir.resolve("entries.txt");
        Files.writeString(list, String.join("\n", names) + "\n", StandardCharsets.UTF_8);
        Path roll = f.dir.resolve("roll");
        Files.createDirectories(roll);
        Path mainOut = f.out("match-b-main");
        Path sidecarOut = roll.resolve("match.txt.fingerprint");
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        int rc = KitPacker.runMain(new String[] {"base=dir:" + f.stage, "descriptor=" + f.descB, "posture=redact",
            "out=" + mainOut, "sidecarOut=" + sidecarOut, "stamp=" + STAMP, "game=" + f.game, "entries=" + list},
                stream(so), stream(se));
        String stdout = so.toString(StandardCharsets.UTF_8);
        require(rc == 0 && stdout.equals("kit-packer: ok file=" + mainOut.getFileName() + " entries=" + names.size()
                + " jarMd5=" + f.jarMd5 + " stamp=" + STAMP + System.lineSeparator()), "the tool main: rc " + rc
                + " stdout '" + stdout.trim() + "' - flips if the roll's one ok row changes shape");
        require(!stdout.contains(f.dir.getFileName().toString()), "the tool main's stdout carries a path");
        require(Arrays.equals(Files.readAllBytes(mainOut), Files.readAllBytes(zipOut)), "the tool main's kit !="
                + " the in-process pack - flips if the main builds another Request");
        require(Arrays.equals(Files.readAllBytes(sidecarOut), Files.readAllBytes(sidecarOf(f.descB))), "the roll's"
                + " sidecarOut != the package's re-keyed sidecar - flips if the dir: mint and the zip: re-key fork");
        Path checkOut = f.out("match-b-check");
        Path checkSidecar = roll.resolve("check.txt.fingerprint");
        rc = KitPacker.runMain(new String[] {"base=dir:" + f.stage, "descriptor=" + f.descB, "posture=redact",
            "out=" + checkOut, "sidecarOut=" + checkSidecar, "stamp=" + STAMP, "game=" + f.game, "entries=" + list,
            "check=true"}, stream(new ByteArrayOutputStream()), stream(new ByteArrayOutputStream()));
        require(rc == 0 && !Files.exists(checkOut) && !Files.exists(checkSidecar), "check=true: rc " + rc + " or it"
                + " wrote - flips if the package-time proof writes");
        // The tool's zip: mode is check-only - a run without check=true is a
        // usage refusal that writes nothing, and the census run (check=true) over the same kit still passes.
        Path zipWrite = f.out("match-b-zipwrite");
        Path zipWriteSidecar = roll.resolve("zipwrite.txt.fingerprint");
        ByteArrayOutputStream zo = new ByteArrayOutputStream();
        rc = KitPacker.runMain(new String[] {"base=zip:" + zipOut, "descriptor=" + f.descB, "posture=redact",
            "out=" + zipWrite, "sidecarOut=" + zipWriteSidecar, "stamp=" + STAMP, "game=" + f.game},
                stream(zo), stream(new ByteArrayOutputStream()));
        require(rc == 2 && zo.toString(StandardCharsets.UTF_8).trim().equals("kit-packer: refused reason=usage")
                && !Files.exists(zipWrite) && !Files.exists(zipWriteSidecar), "zip: without check=true: rc " + rc
                + " '" + zo.toString(StandardCharsets.UTF_8).trim() + "' - flips if the tool's zip: mode re-packs a"
                + " release kit again (or writes its sidecar)");
        ByteArrayOutputStream zc = new ByteArrayOutputStream();
        rc = KitPacker.runMain(new String[] {"base=zip:" + zipOut, "descriptor=" + f.descB, "posture=redact",
            "out=" + zipWrite, "sidecarOut=" + zipWriteSidecar, "stamp=" + STAMP, "game=" + f.game, "check=true"},
                stream(zc), stream(new ByteArrayOutputStream()));
        require(rc == 0 && zc.toString(StandardCharsets.UTF_8).startsWith("kit-packer: ok file=")
                && !Files.exists(zipWrite) && !Files.exists(zipWriteSidecar), "zip: check=true: rc " + rc + " '"
                + zc.toString(StandardCharsets.UTF_8).trim() + "' - flips if the check-only gate also blocks the"
                + " package-time census, or the census writes");
        List<String> missing = new ArrayList<>(names);
        missing.add("CC_Client/data/variants/ghost.variant");
        Map<String, byte[]> before = snap(f.dir);
        KitPacker.Result m = seen(KitPacker.pack(new KitPacker.Request(f.stage, true, f.descB,
                KitPacker.Posture.REDACT, f.out("match-b-miss"), null, STAMP, null, f.game, missing, 0)));
        require(KitPacker.DAMAGED.equals(m.token()) && KitPackRules.textR3("stage",
                "CC_Client/data/variants/ghost.variant").equals(m.text()), "a listed entry missing on disk: "
                + m.token() + " '" + m.text() + "' - flips if dir: mode skips a listed file");
        List<String> slip = new ArrayList<>(names);
        slip.add("../outside.txt");
        KitPacker.Result s = seen(KitPacker.pack(new KitPacker.Request(f.stage, true, f.descB,
                KitPacker.Posture.REDACT, f.out("match-b-slip"), null, STAMP, null, f.game, slip, 0)));
        require(KitPacker.ENTRY.equals(s.token()), "a listed ../ name: " + s.token() + " - flips if dir: mode reads"
                + " outside the stage");
        requireSame(before, snap(f.dir), "a dir: refusal wrote");
    }

    /**
     * kit-check-zip: the package-time check (the tool main, {@code zip:}, {@code check=true}) over a release kit
     * holding NO match.txt passes the census for the standing shape ({@code match.txt}) and writes nothing - no
     * kit, no sidecar even where one would be new; a roster id neither the kit nor the game holds still refuses
     * R7 there, so the pass is the census's own, not a skipped one.
     *
     */
    private static void checkZip(Path dir) throws Exception {
        Fx f = fx(dir, KitPackRules.SessionBlock.PASSWORD_ARMED);
        require(!entries(f.kit).containsKey(MATCH), "SETUP: the fixture release kit carries " + MATCH);
        Path out = f.out("match");
        Path sidecarOut = f.dir.resolve("check-zip.txt.fingerprint");
        Map<String, byte[]> before = snap(f.dir);
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        int rc = KitPacker.runMain(new String[] {"base=zip:" + f.kit, "descriptor=" + f.descA, "posture=redact",
            "out=" + out, "sidecarOut=" + sidecarOut, "stamp=" + STAMP, "game=" + f.game, "check=true"},
                stream(so), stream(new ByteArrayOutputStream()));
        String stdout = so.toString(StandardCharsets.UTF_8).trim();
        require(rc == 0 && stdout.startsWith("kit-packer: ok file="), "zip: check=true on a kit with no match.txt:"
                + " rc " + rc + " '" + stdout + "' - flips if the zip: verify or the census needs the kit's own match"
                + " file");
        requireSame(before, snap(f.dir), "zip: check=true wrote - flips if the package-time proof writes the kit or"
                + " the sidecar");
        MatchDescriptor ghost = descriptor("kitfx-ghost-0004", List.of("ghost_Q", "vanilla_X"), "ghost");
        Path ghostDesc = f.match.resolve("match-ghost.txt");
        Files.writeString(ghostDesc, MatchDescriptorCodec.encode(MatchDescriptors.redact(ghost)), StandardCharsets.UTF_8);
        KitPacker.Result g = seen(KitPacker.check(f.request(ghostDesc, f.out("match-ghost"))));
        require(KitPacker.CENSUS.equals(g.token()), "a roster id neither the kit nor the game holds answered "
                + g.token() + " under check on a match-less kit - flips if the check passes without the census");
    }

    /**
     * kit-commit-gate: a pack whose write reaches the commit gate past its ceiling answers R11 and leaves nothing
     * behind (no kit, no temp), so "nothing was written" stays true; its R11 text feeds kit-texts.
     *
     */
    private static void commitGate(Path dir) throws Exception {
        Fx f = fx(dir, KitPackRules.SessionBlock.PASSWORD_ARMED);
        KitPacker.ZipWriter prior = KitPacker.writeStep;
        boolean[] entered = {false};
        try {
            KitPacker.writeStep = (out, list, stillInTime) -> KitZip.write(out, list, () -> {
                entered[0] = true;
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (stillInTime.getAsBoolean() && System.nanoTime() - until < 0) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException woken) {
                        System.out.println("NOTE kit-commit-gate: the held commit gate was interrupted");
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return stillInTime.getAsBoolean();
            });
            Path outB = f.out("match-b");
            KitPacker.Result late = seen(KitPacker.pack(f.request(f.descB, outB, KitPacker.Posture.REDACT, f.manifest,
                    f.game, 1)));
            require(entered[0], "SETUP: the pack never reached the write seam's commit gate (a 1 s ceiling passed"
                    + " before the write on a slow host?)");
            require(KitPacker.TIMEOUT.equals(late.token()) && KitPackRules.textR11(1).equals(late.text())
                    && !Files.exists(outB) && parts(f).isEmpty(), "a write past its ceiling answered " + late.token()
                    + " with kits/ = " + kitsNames(f) + " - flips if the commit gate lets a timed-out kit land (R11"
                    + " 'nothing was written' lies) or its temp survives");
            require(kitsNames(f).equals(List.of(PACK_KIT_NAME)), "kits/ after the timed-out pack holds " + kitsNames(f));
        } finally {
            KitPacker.writeStep = prior;
        }
    }

    /**
     * kit-ships-every-variant: a {@code dir:} pack ships EVERY custom variant def its entry list names - a root one
     * ({@code kitship_A}, no roster of the packed match names it), an {@code npc/} one and one in another subfolder -
     * as zip entries AND as {@code file=} rows of both kit.files and the package sidecar, rc 0, and stderr carries no
     * {@code variants kept=} counts line (the keep-list strip is gone, so nothing is dropped and nothing counted).
     *
     */
    private static void shipsEveryVariant(Path dir) throws Exception {
        Fx f = fx(dir, KitPackRules.SessionBlock.REDACTED_NO_PASSWORD);
        String root = "CC_Client/data/variants/kitship_A.variant";
        String npc = "CC_Client/data/variants/npc/kitship_C.variant";
        String sub = "CC_Client/data/variants/extra/kitship_D.variant";
        stageFile(f.stage.resolve(npc), utf8("{\"variantId\":\"kitship_C\"}\n"));
        stageFile(f.stage.resolve(sub), utf8("{\"variantId\":\"kitship_D\"}\n"));
        List<String> names = new ArrayList<>(f.entries.keySet());
        names.add(npc);
        names.add(sub);
        require(names.contains(root), "SETUP: the fixture stage lacks its root custom " + root);
        Path list = f.dir.resolve("entries.txt");
        Files.writeString(list, String.join("\n", names) + "\n", StandardCharsets.UTF_8);
        Path roll = f.dir.resolve("roll");
        Files.createDirectories(roll);
        Path out = f.out("match-b-every");
        Path sidecarOut = roll.resolve("every.txt.fingerprint");
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        int rc = KitPacker.runMain(new String[] {"base=dir:" + f.stage, "descriptor=" + f.descB, "posture=redact",
            "out=" + out, "sidecarOut=" + sidecarOut, "stamp=" + STAMP, "game=" + f.game, "entries=" + list},
                stream(so), stream(se));
        String stdout = so.toString(StandardCharsets.UTF_8);
        String stderr = se.toString(StandardCharsets.UTF_8);
        require(rc == 0 && stdout.startsWith("kit-packer: ok file=" + out.getFileName() + " entries=")
                && stdout.split("\r?\n").length == 1, "the whole-set pack: rc " + rc + " stdout '" + stdout.trim() + "'"
                        + " stderr '" + stderr.trim() + "' - flips if a stage carrying subfolder customs refuses, or the"
                        + " one stdout row grows");
        require(!stderr.contains("variants kept="), "stderr '" + stderr.trim() + "' carries a variants counts line -"
                + " flips if the keep-list strip (or its counts line) comes back");
        Set<String> zipped = entries(out).keySet();
        for (String v : List.of(root, npc, sub)) {
            require(zipped.contains(v), v + " is not in the kit - flips if the pack drops a listed custom variant"
                    + " (a strip by roster or keep-list, or a walk that skips subfolders)");
        }
        Set<String> kitRows = fileRowsOf(lines(entries(out).get(SIDECAR)));
        Set<String> pkgRows = fileRowsOf(lines(Files.readAllBytes(sidecarOut)));
        for (String v : List.of(root, npc, sub)) {
            String key = KitPackRules.surfaceKeyOrNull(v);
            require(key != null && kitRows.contains(key) && pkgRows.contains(key), "kit.files / the package sidecar"
                    + " lack the file= row of " + v + " - flips if a shipped custom variant leaves the fingerprint"
                    + " surface (the door would bounce the kit)");
        }
    }

    /** The {@code file=} rows of sidecar lines. */
    private static Set<String> fileRowsOf(List<String> sidecar) {
        Set<String> rows = new TreeSet<>();
        for (String line : sidecar) {
            if (line.startsWith("file=")) {
                rows.add(line.substring("file=".length()));
            }
        }
        return rows;
    }

    private static void texts() {
        Map<String, String> templates = new LinkedHashMap<>();
        templates.put(KitPacker.MADE, S_DONE);
        templates.put(KitPacker.NO_KIT, S_R1);
        templates.put(KitPacker.NOT_THIS_KIT, S_R2);
        templates.put(KitPacker.DAMAGED, S_R3);
        templates.put(KitPacker.ENTRY, S_R4);
        templates.put(KitPacker.TOO_BIG, S_R5);
        templates.put(KitPacker.MATCH_READ, S_R6);
        templates.put(KitPacker.CENSUS, S_R7);
        templates.put(KitPacker.GAME_FOLDER, S_R8);
        templates.put(KitPacker.DISK, S_R9);
        templates.put(KitPacker.TIMEOUT, S_R11);
        templates.put(KitPacker.LIVE_TOKEN, S_R12);
        Set<String> covered = new TreeSet<>();
        boolean restart = false;
        for (KitPacker.Result r : SEEN) {
            String t = r.text();
            require(t != null && !t.isBlank(), r.token() + " came with no text - flips if a refusal goes silent");
            require(t.indexOf('<') < 0 && t.indexOf('>') < 0 && t.indexOf('\n') < 0 && t.indexOf('\r') < 0,
                    r.token() + " text '" + t + "' has an unfilled placeholder or a line break");
            for (String root : ROOTS) {
                require(!t.contains(root), r.token() + " text carries a fixture path - flips if a text names a path");
            }
            require(!t.contains(":\\") && !t.contains(SECRET) && !t.contains(LIVE), r.token() + " text '" + t + "'"
                    + " carries a drive path or a secret - flips if the no-path / no-secret text rule breaks");
            String template = templates.get(r.token());
            if (template == null) {
                require(KitPacker.CHECKED.equals(r.token()) || KitPacker.WRITE.equals(r.token()), "token "
                        + r.token() + " has no text template and is neither checked nor write");
                continue;
            }
            String body = t;
            if (KitPacker.MADE.equals(r.token()) && t.endsWith(" " + S_RESTART)) {
                body = t.substring(0, t.length() - S_RESTART.length() - 1);
                restart = true;
            }
            require(templateOf(template).matcher(body).matches(), r.token() + " text '" + t + "' is not"
                    + " the template '" + template + "' - flips if a KitPackRules text drifts from the decided wording");
            covered.add(r.token());
        }
        Set<String> want = new TreeSet<>(templates.keySet());
        want.remove(KitPacker.DISK);
        Set<String> missing = new TreeSet<>(want);
        missing.removeAll(covered);
        require(missing.isEmpty(), "no row saw " + missing + " (a row that FAILED before its refusal never fed this"
                + " one) - flips if a refusal stops being reachable");
        require(restart, "no Done carried the restart sentence");
        require(templateOf(S_R9).matcher(KitPackRules.textR9(5)).matches(), "R9 drifted from its template (no pack reaches"
                + " it here: the free-space probe has no seam)");
    }

    // ---- the fixture ----

    /** A temp release kit (staged tree + zip), a package holding it, and a fake game root. */
    private static final class Fx {
        final Path dir;
        final Path stage;
        final Path mod;
        final Path pkg;
        final Path kits;
        final Path kit;
        final Path match;
        final Path descA;
        final Path descB;
        final Path manifest;
        final Path game;
        final TreeMap<String, byte[]> entries = new TreeMap<>();
        /** The mint over the kit's surface for shape A WITH its match term (shape A's package sidecar); the kit's
         *  own {@code kit.files} entry is these lines with the match term stripped. */
        List<String> kitSidecar;
        String composite;
        String jarMd5;
        MatchDescriptor a;
        MatchDescriptor b;

        Fx(Path dir) {
            this.dir = dir.toAbsolutePath().normalize();
            this.stage = this.dir.resolve("stage");
            this.mod = stage.resolve("CC_Client");
            this.pkg = this.dir.resolve("pkg");
            this.kits = pkg.resolve("kits");
            this.kit = kits.resolve(PACK_KIT_NAME);
            this.match = pkg.resolve("match");
            this.descA = match.resolve("match.txt");
            this.descB = match.resolve("match-b.txt");
            this.manifest = pkg.resolve("MANIFEST.txt");
            this.game = this.dir.resolve("game");
        }

        Path out(String slug) {
            return kits.resolve(KitPackRules.kitFileName(STAMP, slug));
        }

        KitPacker.Request request(Path descriptor, Path out) {
            return request(descriptor, out, KitPacker.Posture.REDACT, manifest, game, 0);
        }

        KitPacker.Request request(Path descriptor, Path out, KitPacker.Posture posture, Path manifestOrNull,
                Path gameOrNull, int ceilingS) {
            return new KitPacker.Request(kit, false, descriptor, posture, out, sidecarOf(descriptor), STAMP,
                    manifestOrNull, gameOrNull, null, ceilingS);
        }

        void put(String name, String text) throws IOException {
            put(name, utf8(text));
        }

        void put(String name, byte[] bytes) throws IOException {
            stageFile(stage.resolve(name), bytes);
            entries.put(name, bytes);
        }
    }

    /** {@link Fx#put}'s write, kept in this outer class (one residency row covers the selftest's I/O). */
    private static void stageFile(Path p, byte[] bytes) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
    }

    private static Fx fx(Path dir, KitPackRules.SessionBlock readme) {
        Fx f = new Fx(dir);
        try {
            f.a = descriptor(ID_A, List.of("kitship_A", "vanilla_X"), "a");
            f.b = descriptor(ID_B, List.of("kitship_B", "vanilla_X"), "b");
            f.jarMd5 = ContentFingerprints.md5Hex(JAR);
            f.put(JAR_ENTRY, JAR);
            f.put("CC_Client/mod_info.json", MOD_INFO);
            f.put("CC_Client/data/variants/kitship_A.variant", "{\"variantId\":\"kitship_A\"}\n");
            f.put("CC_Client/data/variants/kitship_B.variant", "{\"variantId\":\"kitship_B\"}\n");
            f.put("CC_Client/data/hulls/fixture.csv", "id,x\nfix,1\n");
            f.put("CC_Client/data/missions/mission_list.csv", "mission\nmp_test\n");
            f.put("CC_Client/join-cc.bat", "@echo off\r\nrem fixture\r\n");
            // the mint reads its match id off a match.txt on disk: staged for the mint only, never a kit entry
            stageFile(f.stage.resolve(MATCH), utf8(MatchDescriptorCodec.encode(MatchDescriptors.redact(f.a))));
            f.put(MANIFEST, KitPackRules.manifestText(f.jarMd5, BASE_STAMP, MOD_VERSION, GAME_VERSION));
            f.put(README, KitPackRules.readmeText(readme, null));
            f.kitSidecar = ContentFingerprintMint.mintSidecarLines(f.mod);
            ContentFingerprintMint.writeSidecar(f.stage.resolve(SIDECAR), KitPacker.kitSidecarLines(f.kitSidecar));
            f.entries.put(SIDECAR, Files.readAllBytes(f.stage.resolve(SIDECAR)));
            f.composite = ContentFingerprints.parseSidecarFingerprint(f.kitSidecar, "the fixture kit sidecar");
            Files.createDirectories(f.kits);
            Files.createDirectories(f.match);
            writeKit(f.kit, f.entries);
            Files.writeString(f.descA, MatchDescriptorCodec.encode(MatchDescriptors.redact(f.a)), StandardCharsets.UTF_8);
            ContentFingerprintMint.writeSidecar(sidecarOf(f.descA), f.kitSidecar);
            Files.writeString(f.descB, MatchDescriptorCodec.encode(MatchDescriptors.redact(f.b)), StandardCharsets.UTF_8);
            ContentFingerprintMint.writeSidecar(sidecarOf(f.descB),
                    ContentFingerprintMint.rekeySidecarLines(f.kitSidecar, ID_B));
            Files.writeString(f.match.resolve("match.txt.password"), "password=" + SECRET + "\n");
            Files.writeString(f.match.resolve("match-b-tokens.txt"), "seat.1.token=" + LIVE + "twin\n");
            Files.writeString(f.manifest, manifestText(PACK_KIT_NAME, f.jarMd5, withoutMatch(f.composite)),
                    StandardCharsets.US_ASCII);
            Path core = f.game.resolve("starsector-core");
            Path variants = core.resolve("data").resolve("variants");
            Files.createDirectories(variants.resolve("stations"));
            Files.write(core.resolve("starfarer_obf.jar"), new byte[] {0});
            Files.writeString(variants.resolve("vanilla_X.variant"), "{\"variantId\":\"vanilla_X\"}\n");
            Files.writeString(variants.resolve("Upper_Z.VARIANT"), "{\"variantId\":\"Upper_Z\"}\n");
            Files.writeString(variants.resolve("stations").resolve("vanilla_station_Y.variant"), "{}\n");
        } catch (IOException | RuntimeException e) {
            throw new SetupFailure("the fixture kit / package did not stage (" + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + ")");
        }
        return f;
    }

    /** One seat per roster row, each with a LIVE token (the redacted forms are what ship). */
    private static MatchDescriptor descriptor(String matchId, List<String> variants, String tag) {
        List<MatchDescriptor.RosterRow> roster = new ArrayList<>();
        List<MatchDescriptor.Seat> seats = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            roster.add(new MatchDescriptor.RosterRow(i % 2, variants.get(i), 1, MatchDescriptor.PilotKind.SEAT));
            seats.add(new MatchDescriptor.Seat(i + 1, SeatRole.PILOT, i % 2, i + 1L, LIVE + tag + "-" + (i + 1)));
        }
        return new MatchDescriptor(MatchDescriptor.VERSION, matchId, "1v1", 6000f, 6000f, 0.7f, "BUDGET",
                List.of(new MatchDescriptor.Side("Alpha", "", "ATTACK"), new MatchDescriptor.Side("Beta", "", "ATTACK")),
                roster, seats);
    }

    private static String manifestText(String packKit, String jarMd5, String kitFingerprint) {
        return "git.head=fixture\npack.kit=" + packKit + "\nkit.jar.md5=" + jarMd5 + "\nkit.fingerprint="
                + kitFingerprint + "\n";
    }

    /** The fixture's restore after a tampered case: the good kit + the good MANIFEST. */
    private static void restore(Fx f) throws IOException {
        writeKit(f.kit, f.entries);
        Files.writeString(f.manifest, manifestText(PACK_KIT_NAME, f.jarMd5, withoutMatch(f.composite)),
                StandardCharsets.US_ASCII);
    }

    /** A pack of shape B over {@code kitOrNull} / {@code manifestOrNull} refuses {@code token} + {@code text} and the
     *  whole fixture tree is byte-equal after (kits/ holds only the input, no temp). */
    private static void refusedNothing(Fx f, Map<String, byte[]> kitOrNull, String manifestOrNull, String token,
            String text, String what) throws IOException {
        if (kitOrNull != null) {
            writeKit(f.kit, kitOrNull);
        }
        if (manifestOrNull != null) {
            Files.writeString(f.manifest, manifestOrNull, StandardCharsets.US_ASCII);
        }
        expectRefusal(f, token, text, what);
        restore(f);
    }

    /** {@link #refusedNothing} over a RAW zip ({@code layout} = {names list, bytes map}) the KitZip writer refuses. */
    private static void refusedRaw(Fx f, Object[] layout, String token, String text, String what) throws IOException {
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) layout[0];
        @SuppressWarnings("unchecked")
        Map<String, byte[]> bytes = (Map<String, byte[]>) layout[1];
        rawZip(f.kit, names, bytes, null);
        expectRefusal(f, token, text, what);
        restore(f);
    }

    private static void expectRefusal(Fx f, String token, String text, String what) throws IOException {
        Map<String, byte[]> before = snap(f.dir);
        KitPacker.Result r = seen(KitPacker.pack(f.request(f.descB, f.out("match-b"))));
        require(!r.ok() && token.equals(r.token()) && text.equals(r.text()) && r.file() == null, what + ": answered "
                + r.token() + " '" + r.text() + "', want " + token + " '" + text + "' - flips if that gate is dropped"
                + " or reordered");
        requireSame(before, snap(f.dir), what + ": the refusal wrote (kits/ must hold only the input, no .part, the"
                + " package sidecar untouched) - flips if a write precedes the verify");
    }

    /** The good kit's names + one {@code extra} (a directory entry when {@code bytes} is null), for a raw zip. */
    private static Object[] withExtra(Fx f, String extra, byte[] bytes) {
        List<String> names = new ArrayList<>(f.entries.keySet());
        names.add(extra);
        Map<String, byte[]> all = new TreeMap<>(f.entries);
        if (bytes != null) {
            all.put(extra, bytes);
        }
        return new Object[] {names, all};
    }

    private static void requireCensus(Set<String> kitIds, List<String> roster, Path game, String token,
            List<String> unresolved, String what) {
        KitPacker.Census c = KitPacker.census(descriptor("kitfx-census-0009", roster, "c"), kitIds, game);
        require(java.util.Objects.equals(token, c.token()) && c.unresolvedIds().equals(unresolved), what + ": census "
                + c.token() + " " + c.unresolvedIds() + ", want " + token + " " + unresolved + " - flips if the kit /"
                + " vanilla lookup, the game-folder rule or the roster-order report moves");
    }

    /** A fresh mint over a copy of the kit's tree with {@code d}'s redacted form as match.txt. */
    private static byte[] freshMint(Fx f, MatchDescriptor d, String slug) throws IOException {
        Path root = f.dir.resolve("mint-" + slug).resolve("CC_Client");
        for (Map.Entry<String, byte[]> e : f.entries.entrySet()) {
            if (e.getKey().startsWith("CC_Client/") && !e.getKey().equals(SIDECAR)) {
                Path p = root.resolve(e.getKey().substring("CC_Client/".length()));
                Files.createDirectories(p.getParent());
                Files.write(p, e.getValue());
            }
        }
        Files.writeString(root.resolve("match.txt"), MatchDescriptorCodec.encode(MatchDescriptors.redact(d)),
                StandardCharsets.UTF_8);
        Path out = f.dir.resolve("mint-" + slug + ".fingerprint");
        ContentFingerprintMint.writeSidecar(out, ContentFingerprintMint.mintSidecarLines(root));
        return Files.readAllBytes(out);
    }

    /** The one writer's bytes for {@code lines} (written to a scratch file, read back). */
    private static byte[] writerBytes(Fx f, List<String> lines) throws IOException {
        Path scratch = f.dir.resolve("scratch.fingerprint");
        ContentFingerprintMint.writeSidecar(scratch, lines);
        byte[] bytes = Files.readAllBytes(scratch);
        Files.delete(scratch);
        return bytes;
    }

    private static List<String> withComposite(List<String> sidecar, String composite) {
        List<String> out = new ArrayList<>();
        for (String line : sidecar) {
            out.add(line.startsWith("fingerprint=") ? "fingerprint=" + composite : line);
        }
        return out;
    }

    private static String withoutMatch(String composite) {
        return composite.replaceFirst(";match=[^;]*", "");
    }

    private static void tear(Path sidecar) throws IOException {
        ContentFingerprintMint.writeSidecar(sidecar, ContentFingerprintMint.rekeySidecarLines(lines(
                Files.readAllBytes(sidecar)), "stale-fixture-id"));
    }

    private static Path sidecarOf(Path descriptor) {
        return Paths.get(descriptor + ".fingerprint");
    }

    private static void writeKit(Path out, Map<String, byte[]> entries) throws IOException {
        Files.deleteIfExists(out);
        List<KitZip.Entry> list = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            list.add(new KitZip.Entry(e.getKey(), e.getValue()));
        }
        KitZip.write(out, list);
    }

    /** A zip in {@code names} order with java.util.zip directly ({@code time} null = the stream's default). */
    private static void rawZip(Path out, List<String> names, Map<String, byte[]> bytes, FileTime time)
            throws IOException {
        Files.deleteIfExists(out);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            for (String n : names) {
                ZipEntry e = new ZipEntry(n);
                if (time != null) {
                    e.setLastModifiedTime(time);
                }
                zip.putNextEntry(e);
                byte[] b = bytes.get(n);
                if (b != null && !n.endsWith("/")) {
                    zip.write(b);
                }
                zip.closeEntry();
            }
        }
    }

    /** Every entry of {@code zip} (directory entries included) in central-directory order. */
    private static Map<String, byte[]> entries(Path zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                try (InputStream in = z.getInputStream(e)) {
                    out.put(e.getName(), in.readAllBytes());
                }
            }
        }
        return out;
    }

    private static List<String> kitsNames(Fx f) throws IOException {
        try (Stream<Path> list = Files.list(f.kits)) {
            return list.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static List<Path> parts(Fx f) throws IOException {
        try (Stream<Path> list = Files.list(f.kits)) {
            return list.filter(p -> KitZip.isTempName(p.getFileName().toString())).toList();
        }
    }

    private static String rel(Fx f, Path p) {
        return f.dir.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static List<String> lines(byte[] sidecar) {
        List<String> out = new ArrayList<>(Arrays.asList(new String(sidecar, StandardCharsets.ISO_8859_1)
                .split("\r?\n", -1)));
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static boolean has(byte[] hay, String needle) {
        return new String(hay, StandardCharsets.ISO_8859_1).contains(needle);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] fixtureJar(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 31 + seed);
        }
        return b;
    }

    private static PrintStream stream(ByteArrayOutputStream sink) {
        return new PrintStream(sink, true, StandardCharsets.UTF_8);
    }

    /** A spelled text template as an anchored regex: every {@code <placeholder>} = one or more chars. */
    private static Pattern templateOf(String template) {
        StringBuilder rx = new StringBuilder();
        Matcher ph = Pattern.compile("<[^>]+>").matcher(template);
        int at = 0;
        while (ph.find()) {
            rx.append(Pattern.quote(template.substring(at, ph.start()))).append("(.+?)");
            at = ph.end();
        }
        rx.append(Pattern.quote(template.substring(at)));
        return Pattern.compile(rx.toString());
    }

    /**
     * The kit README.md's bytes shape: ASCII, LF only (no CR at all),
     * ending in exactly ONE LF after the About paragraph's "risk.".
     * re-keyed from the retired plain-text README's one-trailing-CRLF shape.
     */
    private static void requireReadmeShape(String text, String what) {
        boolean ascii = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n' && (c < 0x20 || c > 0x7E)) {
                ascii = false;
            }
        }
        require(ascii && text.indexOf('\r') < 0 && text.endsWith("risk.\n") && !text.endsWith("\n\n"), what
                + ": the README is not ASCII + LF only + ONE trailing LF after \"risk.\" - flips if the composer adds"
                + " a CR, changes the line end, or adds a char the roll's ASCII encoder would turn into '?'");
    }

    private static KitPacker.Result seen(KitPacker.Result r) {
        SEEN.add(r);
        return r;
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

    private static void requireSame(Map<String, byte[]> before, Map<String, byte[]> after, String regression) {
        requireAdded(before, after, List.of(), regression);
    }

    /** {@code after} = {@code before} plus EXACTLY the {@code added} files (nothing else added, changed or removed). */
    private static void requireAdded(Map<String, byte[]> before, Map<String, byte[]> after, List<String> added,
            String regression) {
        List<String> moved = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            byte[] a = before.get(k);
            byte[] b = after.get(k);
            if (a == null || b == null || !Arrays.equals(a, b)) {
                moved.add(k + (a == null ? "" : b == null ? " (removed)" : " (changed)"));
            }
        }
        require(moved.equals(added), "files moved " + moved + ", want " + added + " - " + regression);
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
            dir = Files.createTempDirectory("cc-kit-selftest-");
        } catch (IOException e) {
            System.out.println("FAIL SETUP-" + name + ": no temp dir (" + e.getClass().getSimpleName() + ")");
            failures++;
            return;
        }
        ROOTS.add(dir.toAbsolutePath().normalize().toString());
        ROOTS.add(dir.getFileName().toString());
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
