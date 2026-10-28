package cc.starsector.server.match;

import cc.starsector.server.runtime.HandshakeCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Build-gate rows for {@link MatchShapes} and the one sidecar writer:
 * {@code shapes-law},
 * {@code shapes-redact}, {@code shapes-roster-belt}, {@code shapes-dedupe}, {@code shapes-cap}, {@code shape-new},
 * {@code shape-new-players}, {@code pkg-rewrite-no-live-token}, {@code sidecar-rekey-equals-mint}, {@code rekey-refusals},
 * {@code one-sidecar-writer}. Fixtures are temp dirs (deleted in {@code finally}); {@code one-sidecar-writer} reads
 * {@code server/src/**}{@code /*.java} under the cwd (the repo root; elsewhere it prints a named SKIP, not a failure).
 *
 * <p>Each row prints {@code PASS <row>} or {@code FAIL <row>: <the regression that flipped it>}; a fixture that never
 * staged prints {@code FAIL SETUP-<row>: ...} (an ERROR for the mutant runner, never a kill). Entry: {@link #run()}
 * (the failure count; chained by the ops gate) and {@link #main} (exit 0 / 1).
 *
 *
 */
public final class MatchShapesSelftest {

    private MatchShapesSelftest() {
    }

    private static int failures;

    private static final String JAR = "0123456789abcdef0123456789abcdef";
    private static final String DATA = "fedcba9876543210fedcba9876543210";
    private static final int HS = HandshakeCodec.HANDSHAKE_VERSION;

    private static final class SetupFailure extends Exception {
        private static final long serialVersionUID = 1L;

        SetupFailure(String message) {
            super(message);
        }
    }

    private interface Row {
        void run(Path dir) throws Exception;
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** Runs every row; returns the failure count (0 = green). */
    public static int run() {
        failures = 0;
        row("shapes-law", MatchShapesSelftest::shapesLaw);
        row("shapes-redact", MatchShapesSelftest::shapesRedact);
        row("shapes-roster-belt", MatchShapesSelftest::shapesRosterBelt);
        row("shapes-dedupe", MatchShapesSelftest::shapesDedupe);
        row("shapes-cap", MatchShapesSelftest::shapesCap);
        row("shape-new", MatchShapesSelftest::shapeNew);
        row("shape-new-players", MatchShapesSelftest::shapeNewPlayers);
        row("pkg-rewrite-no-live-token", MatchShapesSelftest::pkgRewriteNoLiveToken);
        row("sidecar-rekey-equals-mint", MatchShapesSelftest::sidecarRekeyEqualsMint);
        row("rekey-refusals", MatchShapesSelftest::rekeyRefusals);
        oneSidecarWriter();
        System.out.println("MatchShapesSelftest: " + (failures == 0 ? "OK" : failures + " FAILED"));
        return failures;
    }

    private static void row(String name, Row body) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("cc-shapes-");
            body.run(dir);
            System.out.println("PASS " + name);
        } catch (SetupFailure setup) {
            failures++;
            System.out.println("FAIL SETUP-" + name + ": " + setup.getMessage());
        } catch (AssertionError | Exception failed) {
            failures++;
            System.out.println("FAIL " + name + ": " + failed);
        } finally {
            deleteTree(dir);
        }
    }

    // ------------------------------------------------------------------ rows

    /** The ship law: twin existence alone decides (a twin DIRECTORY counts, nothing opened); twin-less, sidecar and
     *  password files are skipped; a twin-ed non-descriptor refuses naming the file. */
    private static void shapesLaw(Path dir) throws Exception {
        Path lib = mkdir(dir, "lib");
        MatchDescriptor a = live();
        MatchDescriptor b = live();
        put(lib, "match-a", a, true);
        put(lib, "match-b", b, false);
        Files.createDirectory(lib.resolve("match-b-tokens.txt"));
        put(lib, "match-c", live(), false);
        Files.writeString(lib.resolve("match-a.txt.fingerprint"), "fixture\n");
        Files.writeString(lib.resolve("match-a.txt.password"), "password=fixture\n");
        Files.writeString(lib.resolve("notes.txt"), "not a shape\n");
        List<MatchShapes.Shape> shapes = MatchShapes.library(lib);
        List<String> stems = new ArrayList<>();
        for (MatchShapes.Shape s : shapes) {
            stems.add(s.stem());
        }
        require(stems.equals(List.of("match-a", "match-b")), "library stems " + stems + ", want [match-a, match-b] -"
                + " flips if a twin-less shape ships, a twin that is a directory stops counting, or the order drifts");
        require(shapes.get(0).live().matchId().equals(a.matchId()) && shapes.get(1).live().matchId().equals(b.matchId()),
                "the library's decoded shapes are not the files' - flips if library pairs a stem with another file");
        Path bad = mkdir(dir, "bad");
        Files.writeString(bad.resolve("match-d.txt"), "not a match descriptor\n");
        Files.writeString(bad.resolve("match-d-tokens.txt"), "seat.1.token=x\n");
        String refused = refusal(() -> MatchShapes.library(bad));
        require(refused != null && refused.contains("match-d.txt"), "a twin-ed non-descriptor: " + refused
                + " - flips if it is skipped silently (a release would drop the shape) or the refusal stops naming it");
        Path odd = mkdir(dir, "odd");
        Files.createDirectory(odd.resolve("match-e.txt"));
        Files.writeString(odd.resolve("match-e-tokens.txt"), "seat.1.token=x\n");
        String dirShape = refusal(() -> MatchShapes.library(odd));
        require(dirShape != null && dirShape.contains("match-e.txt"), "a twin-ed directory named like a shape: "
                + dirShape + " - flips if a twin-ed non-file is skipped instead of refused");
    }

    /** Every written descriptor is strictly redacted and decodes to its live id; its sidecar is the kit's re-keyed to
     *  that id (jar= / data= kept) and ends CRLF; no twin, password or match.txt lands. */
    private static void shapesRedact(Path dir) throws Exception {
        Path lib = mkdir(dir, "lib");
        Path out = mkdir(dir, "out");
        MatchDescriptor a = live();
        MatchDescriptor b = fortress();
        put(lib, "match-a", a, true);
        put(lib, "match-b", b, true);
        Files.writeString(lib.resolve("match-a.txt.password"), "password=fixture\n");
        MatchDescriptor kit = pvp();   // a third shape string: neither library entry is deduped against it
        MatchShapes.ShapeSetResult r = MatchShapes.writeShapeSet(kitLines(kit.matchId()), kit, lib, Set.of(), out);
        require(r.written().equals(List.of("match-a", "match-b")) && r.deduped().isEmpty(), "written " + r.written()
                + " deduped " + r.deduped() + " - flips if a shippable shape is not written");
        for (MatchDescriptor d : List.of(a, b)) {
            String stem = d == a ? "match-a" : "match-b";
            List<String> text = Files.readAllLines(out.resolve(stem + ".txt"), StandardCharsets.UTF_8);
            require(MatchDescriptors.isStrictlyRedacted(text), stem + " is not strictly redacted - flips if the pack"
                    + " writes a live token");
            require(MatchDescriptorCodec.decode(text).matchId().equals(d.matchId()), stem + " decodes to another id -"
                    + " flips if the written text is not the library shape");
            String body = String.join("\n", text);
            for (MatchDescriptor.Seat seat : d.seats()) {
                require(!body.contains(seat.seatToken()), stem + " carries a live seat token - flips if redact is"
                        + " skipped");
            }
            byte[] sidecar = Files.readAllBytes(out.resolve(stem + ".txt.fingerprint"));
            String composite = ContentFingerprints.parseSidecarFingerprint(
                    new String(sidecar, StandardCharsets.ISO_8859_1).lines().toList(), stem);
            require(d.matchId().equals(ContentFingerprints.componentOrNull(composite, "match"))
                    && JAR.equals(ContentFingerprints.componentOrNull(composite, "jar"))
                    && DATA.equals(ContentFingerprints.componentOrNull(composite, "data")), stem + " sidecar composite "
                    + composite + " - flips if the sidecar is not the kit's re-keyed to this shape's id");
            requireCrlf(sidecar, stem + " sidecar");
        }
        try (Stream<Path> s = Files.list(out)) {
            List<String> names = s.map(p -> p.getFileName().toString()).sorted().toList();
            require(names.equals(List.of("match-a.txt", "match-a.txt.fingerprint", "match-b.txt",
                    "match-b.txt.fingerprint")), "the shipped folder holds " + names + " - flips if a twin, a password,"
                    + " a temp or match.txt is written");
        }
    }

    /** The roster belt: a MOD variant the kit stripped refuses naming shape + id (nothing written); the same id
     *  shipped by a file= row, or a vanilla id (not a mod id), is admitted. */
    private static void shapesRosterBelt(Path dir) throws Exception {
        Path lib = mkdir(dir, "lib");
        MatchDescriptor a = live();
        put(lib, "match-a", a, true);
        String id = a.roster().get(0).variantId();
        MatchDescriptor kit = fortress();   // another shape string than match-a's: the belt, not the dedupe, decides
        Path out1 = mkdir(dir, "out1");
        String refused = refusal(() -> MatchShapes.writeShapeSet(kitLines(kit.matchId()), kit, lib, Set.of(id), out1));
        require(refused != null && refused.contains("match-a") && refused.contains(id), "a stripped mod variant: "
                + refused + " - flips if the belt admits a shape whose mod variant the kit does not ship, or stops"
                + " naming the shape and the id");
        require(listing(out1).isEmpty(), "a belt refusal wrote " + listing(out1) + " - flips if the belt runs after a"
                + " write");
        Path out2 = mkdir(dir, "out2");
        MatchShapes.ShapeSetResult shipped = MatchShapes.writeShapeSet(
                kitLines(kit.matchId(), "data/variants/sub/" + id + ".variant"), kit, lib, Set.of(id), out2);
        require(shipped.written().equals(List.of("match-a")), "a mod variant the kit ships (file= row in a subfolder)"
                + " was refused - flips if the file= id rule stops keying a subfolder row by its BaseName");
        Path out3 = mkdir(dir, "out3");
        MatchShapes.ShapeSetResult vanilla = MatchShapes.writeShapeSet(kitLines(kit.matchId()), kit, lib, Set.of(),
                out3);
        require(vanilla.written().equals(List.of("match-a")), "a vanilla variant (not a mod id) was refused - flips if"
                + " the belt demands a file= row for vanilla hulls");
        require(MatchShapes.shippedVariantIds(List.of("# c", "file=data/variants/x.variant",
                "file=DATA/Variants/deep/Y.VARIANT", "file=data/hulls/z.variant", "file=data/variants/w.skin",
                "fingerprint=x")).equals(new TreeSet<>(List.of("x", "Y"))), "shippedVariantIds - flips if the"
                + " data/variants + .variant rule (case-folded, BaseName, case kept) drifts");
    }

    /** The standing shape's SHAPE STRING dedupes: the kit's own file (equal id) AND a library twin
     *  of its shape at ANOTHER seat count (a new id - what the packer's -Players stage makes of match.txt) are listed
     *  as deduped and never written; a different shape still ships. */
    private static void shapesDedupe(Path dir) throws Exception {
        Path lib = mkdir(dir, "lib");
        Path out = mkdir(dir, "out");
        MatchDescriptor a = fortress();
        MatchDescriptor twin = MatchShapes.mint(a, 7000f, 0.6f, "nebula", 32);
        require(twin.shape().equals(a.shape()) && !twin.matchId().equals(a.matchId()) && twin.seats().size() == 32,
                "the fixture twin is not the kit's shape at 32 seats under a new id - flips if mint drifts");
        put(lib, "match-a", a, true);
        put(lib, "match-b", live(), true);
        put(lib, "match-twin", twin, true);
        MatchShapes.ShapeSetResult r = MatchShapes.writeShapeSet(kitLines(a.matchId()), a, lib, Set.of(), out);
        require(r.deduped().equals(List.of("match-a", "match-twin")) && r.written().equals(List.of("match-b")),
                "deduped " + r.deduped() + " written " + r.written() + " - flips if the dedupe keys on match.id (a"
                + " 32-player match.txt would ship the fortress twice) or a different shape is dropped");
        require(listing(out).equals(List.of("match-b.txt", "match-b.txt.fingerprint")), "the folder holds "
                + listing(out) + " - flips if a deduped shape or match.txt lands");
    }

    /** N1: 33 shippable shapes refuse the pack (nothing written); 32 ship. */
    private static void shapesCap(Path dir) throws Exception {
        Path lib = mkdir(dir, "lib");
        for (int i = 0; i < 33; i++) {
            put(lib, String.format(Locale.ROOT, "match-s%02d", i), live(), true);
        }
        MatchDescriptor kit = fortress();   // another shape string than the 33: every one of them counts
        Path out = mkdir(dir, "out");
        String refused = refusal(() -> MatchShapes.writeShapeSet(kitLines(kit.matchId()), kit, lib, Set.of(), out));
        require(refused != null && listing(out).isEmpty(), "33 shippable shapes: " + refused + ", wrote "
                + listing(out).size() + " - flips if N1 (32) is not enforced before the first write");
        Files.delete(lib.resolve("match-s32.txt"));
        Path out2 = mkdir(dir, "out2");
        MatchShapes.ShapeSetResult r = MatchShapes.writeShapeSet(kitLines(kit.matchId()), kit, lib, Set.of(), out2);
        require(r.written().size() == 32, "32 shippable shapes wrote " + r.written().size() + " - flips if the cap is"
                + " below 32");
    }

    /** New: each T6 refusal (writing nothing), the range IllegalArgumentExceptions, and the ok path. */
    private static void shapeNew(Path dir) throws Exception {
        Path match = mkdir(dir, "match");
        MatchDescriptor base = MatchDescriptors.redact(fortress());
        Files.writeString(match.resolve("match-base.txt"), MatchDescriptorCodec.encode(base), StandardCharsets.UTF_8);
        List<String> standing = kitLines(base.matchId());
        ContentFingerprintMint.writeSidecar(match.resolve("match-base.txt.fingerprint"), standing);
        List<String> before = listing(match);
        for (String bad : Arrays.asList(null, "", "Bad", "a_b", "a".repeat(33), "tokens", "x-tokens", "x-tokens-y")) {
            expectRefused(MatchShapes.create(match, bad, base, 7000f, 0.6f, "nebula", standing),
                    MatchShapes.T6_NAME, "name " + (bad == null ? "null" : "'" + bad + "'"));
        }
        expectRefused(MatchShapes.create(match, "base", base, 7000f, 0.6f, "nebula", standing),
                "base already exists", "an existing name");
        Path empty = mkdir(dir, "empty");
        String id = base.roster().get(0).variantId();
        expectRefused(MatchShapes.create(empty, "n1", base, 7000f, 0.6f, "nebula", standing),
                MatchShapes.T6_SHIP.replace("<id>", id), "a base whose ship neither the kit nor a shipped shape fields");
        require(listing(empty).isEmpty(), "a belt refusal wrote into the folder - flips if New writes before refusing");
        Path full = mkdir(dir, "full");
        for (int i = 0; i < 64; i++) {
            Files.writeString(full.resolve(String.format(Locale.ROOT, "match-f%02d.txt", i)),
                    MatchDescriptorCodec.encode(MatchDescriptors.redact(fortress())), StandardCharsets.UTF_8);
        }
        expectRefused(MatchShapes.create(full, "n2", base, 7000f, 0.6f, "nebula", standing), MatchShapes.T6_FULL,
                "64 decodable shapes");
        Files.delete(full.resolve("match-f63.txt"));
        require(MatchShapes.create(full, "n2", base, 7000f, 0.6f, "nebula", standing).ok(), "63 decodable shapes"
                + " refused - flips if N2 counts below 64");
        float[][] ranges = {{1999f, 0.6f}, {60001f, 0.6f}, {2000.5f, 0.6f}, {Float.NaN, 0.6f}, {7000f, 0f},
                {7000f, 1.01f}, {7000f, Float.NaN}};
        for (float[] r : ranges) {
            expectIae(() -> MatchShapes.create(match, "r1", base, r[0], r[1], "nebula", standing),
                    "half " + r[0] + " cr " + r[1]);
        }
        expectIae(() -> MatchShapes.create(match, "r1", base, 7000f, 0.6f, "fog", standing), "backdrop 'fog'");
        expectIae(() -> MatchShapes.create(match, "r1", base, 7000f, 0.6f, null, standing), "backdrop null");
        require(listing(match).equals(before), "a refusal changed the folder: " + listing(match) + " - flips if a"
                + " refused New writes anything");
        // the file= branch admits a ship the folder does not field
        List<String> kitShips = new ArrayList<>(standing);
        for (MatchDescriptor.RosterRow row : base.roster()) {
            kitShips.add("file=data/variants/" + row.variantId() + ".variant");
        }
        require(MatchShapes.create(empty, "n3", base, 7000f, 0.6f, "nebula", kitShips).ok(), "a base whose ships the"
                + " kit's file= rows carry was refused - flips if the belt ignores the standing sidecar's file= rows");
        // the ok path
        MatchShapes.CreateResult ok = MatchShapes.create(match, "new-one", base, 9000f, 0.75f, "hyper", standing);
        Path file = match.resolve("match-new-one.txt");
        require(ok.ok() && ok.text().equals("created new-one - pick it and press Start; every join window fetches it.")
                && file.equals(ok.file()), "the ok path answered " + ok + " - flips if the T6 created text or the file"
                + " drifts");
        List<String> text = Files.readAllLines(file, StandardCharsets.UTF_8);
        MatchDescriptor made = MatchDescriptorCodec.decode(text);
        require(MatchDescriptors.isStrictlyRedacted(text) && !made.matchId().equals(base.matchId())
                && made.shape().equals(base.shape()) && made.halfWidth() == 9000f && made.halfHeight() == 9000f
                && made.deployCr() == 0.75f && "hyper".equals(made.terrain()), "the created shape is " + made.shape()
                + " half " + made.halfWidth() + " cr " + made.deployCr() + " " + made.terrain() + " - flips if New keeps"
                + " the base identity, drops a value or writes live tokens");
        require(rosterIds(made).equals(rosterIds(base)), "the created roster " + rosterIds(made) + " is not the base's "
                + rosterIds(base) + " - flips if mint stops threading the station");
        byte[] sidecar = Files.readAllBytes(match.resolve("match-new-one.txt.fingerprint"));
        String composite = ContentFingerprints.parseSidecarFingerprint(
                new String(sidecar, StandardCharsets.ISO_8859_1).lines().toList(), "new");
        require(made.matchId().equals(ContentFingerprints.componentOrNull(composite, "match"))
                && JAR.equals(ContentFingerprints.componentOrNull(composite, "jar")), "the created sidecar " + composite
                + " - flips if New's sidecar is not the standing one re-keyed to the new id");
        requireCrlf(sidecar, "the created sidecar");
        require(listing(match).stream().noneMatch(n -> n.endsWith("-tmp")), "a temp sibling was left: "
                + listing(match));
    }

    /**
     * New's Players leg: a fortress base at 40 players writes a 40-seat file (shape string kept, ship id == seat id,
     * the base's station still LAST); 0 and 256 players are the {@code T6_PLAYERS} refusal with nothing written; New
     * from that 40-seat file without a Players value keeps 40 (the re-mint's seat-count leg).
     *
     *
     */
    private static void shapeNewPlayers(Path dir) throws Exception {
        Path match = mkdir(dir, "match");
        MatchDescriptor base = MatchDescriptors.redact(fortress());
        List<String> kitShips = new ArrayList<>(kitLines(base.matchId()));
        for (MatchDescriptor.RosterRow row : base.roster()) {
            kitShips.add("file=data/variants/" + row.variantId() + ".variant");
        }
        String range = MatchShapes.T6_PLAYERS.replace("<shape>", base.shape()).replace("<min>", "1")
                .replace("<max>", "255");
        for (int bad : new int[] {0, 256}) {
            expectRefused(MatchShapes.create(match, "p" + bad, base, 7000f, 0.6f, "nebula", kitShips, bad), range,
                    bad + " players");
        }
        require(listing(match).isEmpty(), "a Players refusal wrote " + listing(match) + " - flips if New writes"
                + " before the T6_PLAYERS check");
        MatchShapes.CreateResult ok = MatchShapes.create(match, "forty", base, 7000f, 0.6f, "nebula", kitShips, 40);
        require(ok.ok(), "New at 40 players answered " + ok + " - flips if the Players leg refuses an in-range count");
        List<String> text = Files.readAllLines(ok.file(), StandardCharsets.UTF_8);
        MatchDescriptor made = MatchDescriptorCodec.decode(text);
        require(made.seats().size() == 40 && made.shape().equals(base.shape())
                && MatchDescriptors.isStrictlyRedacted(text), "the 40-player file holds " + made.seats().size()
                + " seats of " + made.shape() + " - flips if create drops the players value or the shape string");
        for (MatchDescriptor.Seat seat : made.seats()) {
            require(seat.shipId() == seat.seatId(), "seat " + seat.seatId() + " flies ship " + seat.shipId()
                    + " - flips if the regrown roster breaks ship id == seat id");
        }
        MatchDescriptor.RosterRow last = made.roster().get(made.roster().size() - 1);
        require(last.variantId().equals(base.roster().get(base.roster().size() - 1).variantId()),
                "the 40-player file's last row is " + last.variantId() + " - flips if the station leg is dropped or"
                        + " the station leaves the LAST row");
        MatchShapes.CreateResult again = MatchShapes.create(match, "again", made, 7000f, 0.6f, "nebula", kitShips);
        require(again.ok() && MatchDescriptorCodec.decode(Files.readAllLines(again.file(), StandardCharsets.UTF_8))
                .seats().size() == 40, "New from the 40-seat file answered " + again + " - flips if the re-mint resets"
                + " the seat count to the spec's stock 3");
    }

    /** New over a LIVE-token base writes zero live tokens: none of the base's, and every written token is a
     *  placeholder. */
    private static void pkgRewriteNoLiveToken(Path dir) throws Exception {
        Path match = mkdir(dir, "match");
        MatchDescriptor base = fortress();
        require(!MatchPasswords.carriesRedactedTokens(base), "SETUP: the base is not live");
        List<String> kitShips = new ArrayList<>(kitLines(base.matchId()));
        for (MatchDescriptor.RosterRow row : base.roster()) {
            kitShips.add("file=data/variants/" + row.variantId() + ".variant");
        }
        require(MatchShapes.create(match, "live-base", base, 7000f, 0.6f, "open", kitShips).ok(), "SETUP: New refused");
        for (String name : listing(match)) {
            String body = new String(Files.readAllBytes(match.resolve(name)), StandardCharsets.ISO_8859_1);
            for (MatchDescriptor.Seat seat : base.seats()) {
                require(!body.contains(seat.seatToken()), name + " carries a base live token - flips if New copies"
                        + " the base's seats");
            }
        }
        List<String> text = Files.readAllLines(match.resolve("match-live-base.txt"), StandardCharsets.UTF_8);
        require(MatchDescriptors.isStrictlyRedacted(text), "the created descriptor carries a live token - flips if New"
                + " writes the fresh mint unredacted");
    }

    /** The byte law: writeSidecar(rekey(mint(root, A), B)) == writeSidecar(mint(root, B)), CRLF-terminated. */
    private static void sidecarRekeyEqualsMint(Path dir) throws Exception {
        Path root = mkdir(dir, "CC_Client");
        Files.writeString(root.resolve("mod_info.json"), "{\"id\":\"fixture\"}\n");
        Files.createDirectories(root.resolve("jars"));
        Files.write(root.resolve("jars").resolve("cc-client-mod.jar"), new byte[] {1, 2, 3, 4, 5});
        Files.createDirectories(root.resolve("data").resolve("variants"));
        Files.writeString(root.resolve("data").resolve("variants").resolve("fx.variant"), "{}\n");
        MatchDescriptor a = live();
        MatchDescriptor b = live();
        Files.writeString(root.resolve("match.txt"), MatchDescriptorCodec.encode(a), StandardCharsets.UTF_8);
        List<String> mintA = ContentFingerprintMint.mintSidecarLines(root);
        Files.writeString(root.resolve("match.txt"), MatchDescriptorCodec.encode(b), StandardCharsets.UTF_8);
        List<String> mintB = ContentFingerprintMint.mintSidecarLines(root);
        if (mintA.stream().filter(l -> l.startsWith("file=")).count() != 2) {
            throw new SetupFailure("the scratch mod root is not a 2-file surface: " + mintA);
        }
        Path rekeyed = dir.resolve("rekeyed.fingerprint");
        Path minted = dir.resolve("minted.fingerprint");
        ContentFingerprintMint.writeSidecar(rekeyed, ContentFingerprintMint.rekeySidecarLines(mintA, b.matchId()));
        ContentFingerprintMint.writeSidecar(minted, mintB);
        byte[] r = Files.readAllBytes(rekeyed);
        byte[] m = Files.readAllBytes(minted);
        require(Arrays.equals(r, m), "rekey(mint(A), B) bytes != mint(B) bytes - flips if the re-key touches any row"
                + " but match= or the writer's bytes depend on the input path");
        requireCrlf(r, "the written sidecar");
        // the writer replaces an existing file and leaves no temp
        ContentFingerprintMint.writeSidecar(rekeyed, mintA);
        require(Arrays.equals(Files.readAllBytes(rekeyed), joinedCrlf(mintA)), "a second writeSidecar did not replace"
                + " the file with the new lines - flips if the move does not replace");
        require(listing(dir).stream().noneMatch(n -> n.endsWith(ContentFingerprintMint.SIDECAR_TEMP_SUFFIX)),
                "a temp sibling was left - flips if the writer skips its temp cleanup");
    }

    /** The strengthened re-key refuses a half-formed input naming the KEY, never a value; a jar-less (stripped kit)
     *  sidecar still re-keys (the package Apply's contract). */
    private static void rekeyRefusals(Path dir) throws Exception {
        String id = "fixture-match-1";
        String full = ContentFingerprints.composite(JAR, DATA, HS, "old-id");
        require(ContentFingerprintMint.rekeySidecarLines(List.of("fingerprint=" + full), id).get(0)
                .equals("fingerprint=" + full.replace(";match=old-id", ";match=" + id)), "a full composite did not"
                + " re-key to exactly match= swapped - flips if the strengthened checks refuse a whole composite");
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("data", full.replace(";data=" + DATA, ""));
        bad.put("hs", full.replace(";hs=" + HS, ""));
        bad.put("match", full.replace(";match=old-id", ""));
        for (Map.Entry<String, String> e : bad.entrySet()) {
            String why = iae(() -> ContentFingerprintMint.rekeySidecarLines(List.of("# c", "fingerprint=" + e.getValue(),
                    "file=mod_info.json"), id));
            require(why != null && why.contains("key " + e.getKey()) && !why.contains(DATA) && !why.contains(JAR),
                    "a composite without " + e.getKey() + "= : " + why + " - flips if the re-key ships it or the"
                            + " refusal stops naming the key (or echoes a value)");
        }
        String otherHs = iae(() -> ContentFingerprintMint.rekeySidecarLines(List.of("fingerprint="
                + full.replace(";hs=" + HS + ";", ";hs=" + (HS - 1) + ";")), id));
        require(otherHs != null && otherHs.contains("key hs"), "an hs=" + (HS - 1) + " sidecar: " + otherHs
                + " - flips if a sidecar of another handshake version re-keys");
        String none = iae(() -> ContentFingerprintMint.rekeySidecarLines(List.of("# only a comment",
                "file=mod_info.json"), id));
        require(none != null && none.contains("key fingerprint"), "a sidecar with no fingerprint= row: " + none
                + " - flips if it passes through unchanged");
        for (String badId : Arrays.asList("a;b", "a=b", "a b", "", null)) {
            require(iae(() -> ContentFingerprintMint.rekeySidecarLines(List.of("fingerprint=" + full), badId)) != null,
                    "match id '" + badId + "' re-keyed - flips if a separator-bearing id forges a component");
        }
        String jarless = full.replace(";jar=" + JAR, "");
        require(ContentFingerprintMint.rekeySidecarLines(List.of("fingerprint=" + jarless), id).get(0)
                .equals("fingerprint=" + jarless.replace(";match=old-id", ";match=" + id)), "a jar-less sidecar did"
                + " not re-key - flips if jar= becomes required (the package Apply re-keys a stripped kit's sidecar)");
    }

    // ------------------------------------------------------------------ one-sidecar-writer (static)

    /** Writer statements allowed to mention a sidecar, by {@code Class.method} -> the statement shape allowed there. */
    private static final Map<String, Pattern> ALLOWED = Map.of(
            // THE writer
            "ContentFingerprintMint.writeSidecar", Pattern.compile("(?s).*"),
            // the pair writer's rollback writes the OLD bytes (read from disk) back after a failed descriptor move
            "OpsMatchApply.writeSidecar", Pattern.compile("(?s)\\s*Files\\s*\\.\\s*write\\s*\\(\\s*sidecar\\s*,"
                    + "\\s*oldSidecar\\s*\\)\\s*"),
            // the .password / .adminpassword sidecars (secret files, not fingerprints)
            "ServerOpsLaunch.runStartFlowHeld", Pattern.compile("(?s).*(?:passwordSidecarContent"
                    + "|adminPasswordSidecarContent)\\s*\\(.*"));
    private static final Pattern WRITER = Pattern.compile(
            "\\bFiles\\s*\\.\\s*write(?:String)?\\s*\\(|\\bnewBufferedWriter\\s*\\(|\\bnew\\s+PrintWriter\\s*\\(");
    private static final Pattern METHOD_TAIL = Pattern.compile(
            "(?:^|[^\\w.])(\\w+)\\s*\\((?:[^()]|\\([^()]*\\))*\\)\\s*(?:throws\\s+[\\w.,\\s<>]+)?$");
    private static final Set<String> NOT_METHODS = Set.of("if", "for", "while", "switch", "catch", "synchronized",
            "try", "return", "new", "else", "do", "super", "this");

    /** Every {@code Files.write*} / {@code newBufferedWriter} / {@code new PrintWriter} statement in
     *  {@code server/src/**}{@code /*.java} that mentions a sidecar or a fingerprint lies in
     *  {@link ContentFingerprintMint#writeSidecar} or an {@link #ALLOWED} shape. Build-gate rigs ({@code *Selftest},
     *  {@code *Smoke}) are listed OUT (their fixture sidecars are test bytes, not shipped ones); the pwsh writers are
     *  checked by the roll script's own self-test. */
    private static void oneSidecarWriter() {
        String name = "one-sidecar-writer";
        Path src = Paths.get("server", "src");
        if (!Files.isDirectory(src)) {
            System.out.println("SKIP " + name + ": server/src is absent under the cwd (the row reads source)");
            return;
        }
        try {
            List<String> stray = new ArrayList<>();
            Set<String> seen = new TreeSet<>();
            List<Path> files;
            try (Stream<Path> walk = Files.walk(src)) {
                files = walk.filter(p -> p.toString().endsWith(".java")).sorted(Comparator.comparing(Path::toString))
                        .toList();
            }
            for (Path file : files) {
                String cls = file.getFileName().toString().replace(".java", "");
                if (cls.endsWith("Selftest") || cls.endsWith("Smoke")) {
                    continue;
                }
                String[] cm = stripped(Files.readString(file, StandardCharsets.UTF_8));
                String code = cm[0];
                String mask = cm[1];
                Matcher m = WRITER.matcher(mask);
                while (m.find()) {
                    String statement = code.substring(statementStart(mask, m.start()), statementEnd(mask, m.end()));
                    String folded = statement.toLowerCase(Locale.ROOT);
                    if (!folded.contains("sidecar") && !folded.contains("fingerprint")) {
                        continue;
                    }
                    String key = cls + "." + enclosingMethod(mask, m.start());
                    Pattern allowed = ALLOWED.get(key);
                    if (allowed != null && allowed.matcher(statement.trim()).matches()) {
                        seen.add(key);
                    } else {
                        stray.add(key + " `" + statement.trim().replaceAll("\\s+", " ") + "`");
                    }
                }
            }
            require(stray.isEmpty(), "sidecar/fingerprint writes outside the one writer: " + stray + " - flips if a"
                    + " second sidecar writer (or the join moved back into OpsMatchApply) lands");
            require(seen.contains("ContentFingerprintMint.writeSidecar"), "the scan never saw the one writer itself"
                    + " (seen " + seen + ") - flips if the scan goes vacuous (pattern / statement / method resolution)");
            for (String key : ALLOWED.keySet()) {
                if (!seen.contains(key)) {
                    System.out.println("NOTE " + name + ": allow-list entry " + key + " matched nothing (stale?)");
                }
            }
            System.out.println("PASS " + name);
        } catch (AssertionError | Exception failed) {
            failures++;
            System.out.println("FAIL " + name + ": " + failed);
        }
    }

    /** [comment-stripped code with literals kept, the same with literals blanked] - equal lengths, newlines kept. */
    static String[] stripped(String src) {
        StringBuilder code = new StringBuilder(src.length());
        StringBuilder mask = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            int end;
            boolean keepCode;
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                end = src.indexOf('\n', i);
                end = end < 0 ? n : end;
                keepCode = false;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                keepCode = false;
            } else if (src.startsWith("\"\"\"", i)) {
                end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                keepCode = true;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                end = Math.min(n, j + 1);
                keepCode = true;
            } else {
                code.append(c);
                mask.append(c);
                i++;
                continue;
            }
            for (; i < end; i++) {
                char k = src.charAt(i);
                code.append(keepCode || k == '\n' ? k : ' ');
                mask.append(k == '\n' ? '\n' : ' ');
            }
        }
        return new String[] {code.toString(), mask.toString()};
    }

    private static int statementStart(String mask, int at) {
        for (int k = at - 1; k >= 0; k--) {
            char c = mask.charAt(k);
            if (c == ';' || c == '{' || c == '}') {
                return k + 1;
            }
        }
        return 0;
    }

    private static int statementEnd(String mask, int from) {
        int depth = 1;
        for (int k = from; k < mask.length(); k++) {
            char c = mask.charAt(k);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if ((c == ';' || c == '{') && depth <= 0) {
                return k;
            }
        }
        return mask.length();
    }

    /** The innermost enclosing block whose header reads as a method / constructor declaration. */
    private static String enclosingMethod(String mask, int at) {
        int depth = 0;
        for (int k = at - 1; k >= 0; k--) {
            char c = mask.charAt(k);
            if (c == '}') {
                depth++;
            } else if (c == '{') {
                if (depth > 0) {
                    depth--;
                    continue;
                }
                String header = mask.substring(statementStart(mask, k), k).trim();
                Matcher m = METHOD_TAIL.matcher(header);
                if (m.find() && !NOT_METHODS.contains(m.group(1))
                        && !header.substring(0, m.start(1)).trim().endsWith("new")) {
                    return m.group(1);
                }
            }
        }
        return "<class-body>";
    }

    // ------------------------------------------------------------------ fixtures + helpers

    private static MatchDescriptor live() {
        return MatchDescriptors.withTerrain(MatchDescriptors.mint("1v1", 6000f, 0.8f), "open");
    }

    private static MatchDescriptor fortress() {
        return MatchDescriptors.withTerrain(MatchDescriptors.mint(MatchDescriptors.FORTRESS_SHAPE, 7000f, 0.6f,
                MatchDescriptors.stationVariantForFamily("lowtech")), "nebula");
    }

    /** A third shape string (the dedupe key): a kit of this shape dedupes neither {@link #live} nor {@link #fortress}. */
    private static MatchDescriptor pvp() {
        return MatchDescriptors.withTerrain(MatchDescriptors.mint(MatchDescriptors.PVP_SHAPE, 7000f, 0.6f), "nebula");
    }

    /** A library entry: {@code <stem>.txt} = the LIVE text (the library is the source), + a twin file. */
    private static void put(Path dir, String stem, MatchDescriptor d, boolean twin) throws IOException {
        Files.writeString(dir.resolve(stem + ".txt"), MatchDescriptorCodec.encode(d), StandardCharsets.UTF_8);
        if (twin) {
            Files.writeString(dir.resolve(stem + "-tokens.txt"), "seat.1.token=fixture\n");
        }
    }

    private static List<String> kitLines(String matchId, String... fileRows) {
        List<String> lines = new ArrayList<>();
        lines.add("# fixture kit sidecar");
        lines.add("fingerprint=" + ContentFingerprints.composite(JAR, DATA, HS, matchId));
        lines.add("file=mod_info.json");
        for (String row : fileRows) {
            lines.add("file=" + row);
        }
        return lines;
    }

    private static List<String> rosterIds(MatchDescriptor d) {
        List<String> ids = new ArrayList<>();
        for (MatchDescriptor.RosterRow row : d.roster()) {
            ids.add(row.side() + ":" + row.variantId() + "x" + row.count() + ":" + row.pilot());
        }
        return ids;
    }

    private static byte[] joinedCrlf(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (String line : lines) {
            b.append(line).append("\r\n");
        }
        return b.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void requireCrlf(byte[] bytes, String what) {
        boolean crlf = bytes.length >= 2 && bytes[bytes.length - 2] == 0x0D && bytes[bytes.length - 1] == 0x0A;
        for (int i = 0; i < bytes.length && crlf; i++) {
            if (bytes[i] == 0x0A && (i == 0 || bytes[i - 1] != 0x0D)) {
                crlf = false;
            }
        }
        require(crlf, what + " is not CRLF throughout with a trailing 0D 0A - flips if the writer joins with LF"
                + " (System.lineSeparator() on a Linux host) or drops the trailing terminator");
    }

    private interface Throwing {
        Object call() throws Exception;
    }

    /** The message of the IOException {@code action} refused with, or null when it returned. */
    private static String refusal(Throwing action) throws Exception {
        try {
            action.call();
            return null;
        } catch (IOException refused) {
            return refused.getMessage();
        }
    }

    /** The message of the IllegalArgumentException {@code action} threw, or null when it returned. */
    private static String iae(Throwing action) throws Exception {
        try {
            action.call();
            return null;
        } catch (IllegalArgumentException refused) {
            return String.valueOf(refused.getMessage());
        }
    }

    private static void expectIae(Throwing action, String what) throws Exception {
        require(iae(action) != null, "New accepted " + what + " - flips if a range / word check is dropped");
    }

    private static void expectRefused(MatchShapes.CreateResult r, String why, String what) {
        require(!r.ok() && r.file() == null && (MatchShapes.CREATE_REFUSED + why).equals(r.text()), what + ": "
                + r + " - flips if this T6 refusal drifts (text, order) or New accepts it");
    }

    private static List<String> listing(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static Path mkdir(Path parent, String name) throws IOException {
        return Files.createDirectories(parent.resolve(name));
    }

    private static void deleteTree(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException leak) {
            System.out.println("NOTE a temp fixture dir could not be removed (" + leak.getClass().getSimpleName() + ")");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
