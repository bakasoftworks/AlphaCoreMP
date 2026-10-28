package cc.starsector.server.ops;

import cc.starsector.server.match.ContentFingerprintMint;
import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.MatchShapes;
import cc.starsector.server.runtime.HandshakeCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipException;

/**
 * The joiner-kit packer — "Make joiner kit", PASSWORD MODE ONLY.
 * WHY: a package host must be able to send players a kit
 * for the match shape it picked, and the only kit it holds is the release kit it came with
 * ({@code <pkg>/kits/<pack.kit>}); so the SAME Java re-packs that kit ({@code zip:} mode: three entries
 * change — {@code match.txt}, its {@code .fingerprint}, the {@code kitStamp} row) and, in {@code dir:}
 * mode, builds the kit from the repo roll's / the exported source's stage (manifest, README, sidecar,
 * zip all from here — one writer per artifact, no pwsh twin to fork).
 *
 * <p>Order ({@link #pack}): (a) INPUT — the zip through {@link KitZip#read} (or the caller's entry list
 * under a stage dir: every listed name ships, every custom variant def the stage carries included);
 * (b) VERIFY in memory before anything is written — the base sidecar's {@code jar=}
 * equals the kit jar's md5 (and the package MANIFEST's {@code kit.jar.md5}), {@code hs=} equals
 * {@link HandshakeCodec#HANDSHAKE_VERSION} (else R2), {@code data=} equals {@code dataMd5} over the
 * kit's own surface and the {@code file=} rows list exactly that surface (else R3), and with a MANIFEST
 * the base composite minus {@code match=} equals {@code kit.fingerprint} (else R2: another kit under
 * the {@code pack.kit} name never rewrites the package sidecar's {@code data=}); (c) COMPOSE — the
 * descriptor's REDACTED form proven token-free (a live row = R12 before any write; the kit itself
 * carries NO match file, a base kit's own is dropped), the package sidecar minted WITH the match term
 * and the kit's {@code kit.files} the same lines with the composite's match term stripped
 * ({@link #kitSidecarLines}), the manifest (+ the README in {@code dir:} mode) from
 * {@link KitPackRules} with the caller's fresh stamp; (d) CENSUS — every roster variant in the kit or
 * in the game's own {@code data/variants/**} (R7 / R8); (e) FREE SPACE (R9); (f) WRITE — the zip via
 * the swappable {@link #writeStep} ({@link KitZip#write}: temp, round trip, commit gate, rename), then
 * the package sidecar via {@link ContentFingerprintMint#writeSidecar} only if its bytes differ. Never
 * {@code MANIFEST.txt}, never {@code serve-mods/**}, never a secret read: {@code .password} siblings are
 * only tested for EXISTENCE (the README's password paragraph, as the roll's posture test does).
 *
 * <p>Threads: {@link #pack} / {@link #check} run on the CALLING thread; the one product caller is
 * {@link #main}, the repo roll's / package gate's tool entry in its own JVM. A ceiling past its
 * deadline (R11) makes the commit gate refuse the rename, so "nothing was written" stays true.
 *
 *
 * The variant keep-list strip DELETED - a {@code dir:}
 * pack ships every entry its caller lists, every custom variant def included. WHY: the strip's two reasons are gone
 * (the kit already ships every skin; every release re-rolls the kit), so the kit carries the whole variant set.
 */
public final class KitPacker {

    private KitPacker() {
    }

    /** The descriptor's posture in the kit: REDACT always on a host; KEEP = the repo roll's token mode ONLY. */
    public enum Posture {
        REDACT,
        KEEP
    }

    /**
     * One pack. {@code base} = the release kit zip ({@code dirMode} false) or the stage dir ({@code dirMode}
     * true, {@code dirEntriesOrNull} = its file names relative to {@code base}, {@code /}-separated);
     * {@code descriptor} = the picked match file; {@code out} = the kit zip to create (never overwritten);
     * {@code sidecarOut} = the package / library sidecar to keep equal to the kit's (null = none);
     * {@code stamp} = {@code yyyyMMdd-HHmmss}; {@code manifestOrNull} = the package {@code MANIFEST.txt}
     * (its folder is the package root the restart probe reads); {@code gameRootOrNull} = the game folder
     * (the install root OR its {@code starsector-core}); {@code ceilingS} = the commit deadline in
     * seconds from the start (≤ 0 = none: the tool main).
     */
    public record Request(Path base, boolean dirMode, Path descriptor, Posture posture, Path out, Path sidecarOut,
            String stamp, Path manifestOrNull, Path gameRootOrNull, List<String> dirEntriesOrNull, int ceilingS) {
    }

    /**
     * A pack's answer: {@code token} ∈ {@link #MADE} | {@link #CHECKED} | the refusal tokens below;
     * {@code text} = the {@link KitPackRules} Done / R1–R12 text for the shells' note; {@code file} = the kit written
     * ({@link #MADE}), the kit that would be written ({@link #CHECKED}), else null (except a sidecar
     * failure after a written kit, which names it); {@code sidecarChanged} = the package sidecar was
     * (or, for {@link #check}, would be) rewritten.
     */
    public record Result(boolean ok, String token, String text, Path file, boolean sidecarChanged) {
    }

    /** {@link #census}'s answer: {@code token} null = every roster id resolves; else {@link #CENSUS} / {@link #GAME_FOLDER}. */
    public record Census(String token, List<String> unresolvedIds) {
        public boolean ok() {
            return token == null;
        }
    }

    /** The kit was written. */
    public static final String MADE = "made";
    /** {@link #check} passed; no write happened. */
    public static final String CHECKED = "checked";
    /** R1: no release kit at {@code kits/<pack.kit>}. */
    public static final String NO_KIT = "no_kit";
    /** R2: another client build / server version, or another kit under the {@code pack.kit} name. */
    public static final String NOT_THIS_KIT = "not_this_kit";
    /** R3: the kit's parts disagree with each other. */
    public static final String DAMAGED = "damaged";
    /** R4: an entry the reader refuses. */
    public static final String ENTRY = "entry";
    /** R5: over an aggregate size cap. */
    public static final String TOO_BIG = "too_big";
    /** R6: the descriptor cannot be read. */
    public static final String MATCH_READ = "match_read";
    /** R7: a roster id neither the kit nor the game has. */
    public static final String CENSUS = "census";
    /** R8: the census needs the game folder. */
    public static final String GAME_FOLDER = "game_folder";
    /** R9: not enough free space. */
    public static final String DISK = "disk";
    /** R11: past the ceiling, nothing committed. */
    public static final String TIMEOUT = "timeout";
    /** R12: a live seat token would ship. */
    public static final String LIVE_TOKEN = "live_token";
    /** The shells' own refusal (a descriptor the picker does not offer); the packer never answers it. */
    public static final String UNOFFERED = "unoffered";
    /** The zip or the package sidecar could not be written (I/O, an existing target). */
    public static final String WRITE = "write";

    /** The zip write step (the rows swap it to block / fail the write; default {@link KitZip#write}). */
    @FunctionalInterface
    interface ZipWriter {
        void write(Path out, List<KitZip.Entry> entries, BooleanSupplier stillInTime) throws IOException;
    }

    /** The write seam: package-private, volatile, restored by the rows that swap it. */
    static volatile ZipWriter writeStep = KitZip::write;

    /** Quiet failures (a lock probe, an unexpected exception mapped to a {@link #WRITE} result). */
    static final AtomicLong QUIET_FAILURES = new AtomicLong();

    /** The three header comment rows of {@code ContentFingerprintMint.mintSidecarLines}, mirrored for the
     *  {@code dir:} mint (the mint reads its match id off a match.txt on disk; this one composes in memory). */
    static final List<String> MINT_HEADER = List.of(
            "# content-fingerprint sidecar (ContentFingerprintMint)",
            "# fingerprint= is the armed door's expected composite (re-read per serve);",
            "# file= rows are the client's digest surface (mod code cannot list data/** in-game).");

    /** The pack's outcome plus the two facts the main's stdout row prints. */
    record Packed(Result result, int entries, String jarMd5) {
        static Packed refused(Result result) {
            return new Packed(result, 0, null);
        }
    }

    /** A refusal unwinding {@link #runOrThrow} (carries its finished {@link Result}). */
    private static final class Refusal extends Exception {
        private static final long serialVersionUID = 1L;
        final transient Result result;

        Refusal(Result result) {
            super(result.token(), null, false, false);
            this.result = result;
        }
    }

    /** The commit deadline (absolute {@code System.nanoTime()}); {@code on} false = none. */
    private record Deadline(boolean on, long at) {
        static Deadline of(int ceilingS) {
            return ceilingS > 0 ? new Deadline(true, System.nanoTime() + TimeUnit.SECONDS.toNanos(ceilingS))
                    : new Deadline(false, 0L);
        }

        boolean passed() {
            return on && System.nanoTime() - at >= 0;
        }
    }

    // ---------------------------------------------------------------- the public verbs

    /** (a)–(f): packs {@code req} on the CALLING thread (the main, a row). */
    public static Result pack(Request req) {
        return run(req, true, Deadline.of(req == null ? 0 : req.ceilingS())).result();
    }

    /** (a)–(e) with NOTHING written (the package-time proof): {@link #CHECKED} or the refusal {@link #pack} would give. */
    public static Result check(Request req) {
        return run(req, false, Deadline.of(req == null ? 0 : req.ceilingS())).result();
    }

    /** {@code <pkg>/kits}: the ONE folder a host's release kit is read from and kits are written to. */
    public static Path kitsDir(Path pkgRoot) {
        return pkgRoot.resolve("kits");
    }

    /**
     * The release kit's path {@code kitsDir/<packKit>} iff {@code packKit} (the MANIFEST's {@code pack.kit=})
     * is a plain name in {@link KitPackRules#PACK_KIT} and resolves INSIDE {@code kitsDir}; else null.
     * WHY: the input is one fixed path, nothing wire- or file-derived becomes a free path.
     */
    public static Path baseKitPath(Path kitsDir, String packKit) {
        if (kitsDir == null || packKit == null || !KitPackRules.PACK_KIT.matcher(packKit).matches()) {
            return null;
        }
        Path dir = kitsDir.toAbsolutePath().normalize();
        Path kit = dir.resolve(packKit).normalize();
        return dir.equals(kit.getParent()) ? kit : null;
    }

    /**
     * (d) The roster census: every roster {@code variantId()} must be one the kit ships ({@code kitVariantIds},
     * {@link MatchShapes#shippedVariantIds} over the kit sidecar's {@code file=} rows = every custom variant def
     * the kit ships) or a vanilla variant (a RECURSIVE walk of {@code data/variants/**} under
     * {@code ServeLauncher.resolveGame(gameRootOrNull)} — stations sit under {@code stations/} — of at most
     * {@link KitPackRules#MAX_VANILLA_FILES} files; ids = the file name minus a case-insensitive
     * {@code .variant}, compared ordinal). The game folder is read ONLY when some id is not in the kit:
     * none given / not a game folder / over the walk cap / unreadable = {@link #GAME_FOLDER} (R8); an id
     * found nowhere = {@link #CENSUS} (R7, every unresolved id in roster order).
     */
    public static Census census(MatchDescriptor descriptor, Set<String> kitVariantIds, Path gameRootOrNull) {
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        for (MatchDescriptor.RosterRow row : descriptor.roster()) {
            if (!kitVariantIds.contains(row.variantId())) {
                missing.add(row.variantId());
            }
        }
        if (missing.isEmpty()) {
            return new Census(null, List.of());
        }
        Path game = gameRootOrNull == null ? null : ServeLauncher.resolveGame(gameRootOrNull);
        Path variants = game == null ? null : game.resolve("data").resolve("variants");
        if (variants == null || !Files.isDirectory(variants)) {
            return new Census(GAME_FOLDER, List.copyOf(missing));
        }
        Set<String> found = vanillaIdsAmong(variants, missing);
        if (found == null) {
            return new Census(GAME_FOLDER, List.copyOf(missing));
        }
        missing.removeAll(found);
        return missing.isEmpty() ? new Census(null, List.of()) : new Census(CENSUS, List.copyOf(missing));
    }

    // ---------------------------------------------------------------- the pack

    static Packed run(Request req, boolean write, Deadline deadline) {
        try {
            return runOrThrow(req, write, deadline);
        } catch (Refusal refused) {
            return Packed.refused(refused.result);
        } catch (IllegalArgumentException malformed) {
            QUIET_FAILURES.incrementAndGet();
            return Packed.refused(fail(WRITE, KitPackRules.textWrite("request")));
        } catch (RuntimeException unexpected) {
            QUIET_FAILURES.incrementAndGet();
            return Packed.refused(fail(WRITE, KitPackRules.textWrite("internal")));
        }
    }

    private static Packed runOrThrow(Request req, boolean write, Deadline deadline) throws Refusal {
        requireWellFormed(req);
        String name = PackageManifest.mask(String.valueOf(req.base().getFileName()));
        String shape = shapeName(req.descriptor());

        // (a) INPUT
        List<KitZip.Entry> entries;
        long inputBytes;
        if (!req.dirMode()) {
            if (!KitPackRules.PACK_KIT.matcher(String.valueOf(req.base().getFileName())).matches()) {
                throw refuse(NOT_THIS_KIT, KitPackRules.textR2(name));
            }
            if (!Files.isRegularFile(req.base())) {
                throw refuse(NO_KIT, KitPackRules.textR1(name, folderDisplay(req.base())));
            }
            try {
                inputBytes = Files.size(req.base());
                entries = KitZip.read(req.base(), KitZip.KIT_CAPS);
            } catch (KitZip.Refused r) {
                throw refuse(r, name);
            } catch (NoSuchFileException gone) {
                throw refuse(NO_KIT, KitPackRules.textR1(name, folderDisplay(req.base())));
            } catch (ZipException notAZip) {
                throw refuse(DAMAGED, KitPackRules.textR3(name, "zip"));
            } catch (IOException unreadable) {
                throw refuse(DAMAGED, KitPackRules.textR3(name, "read"));
            }
        } else {
            entries = readStage(req.base(), req.dirEntriesOrNull(), name);
            long sum = 0;
            for (KitZip.Entry e : entries) {
                sum += e.bytes().length;
            }
            inputBytes = sum;
        }
        for (KitZip.Entry e : entries) {
            if (KitPackRules.isLeakName(e.name())) {
                throw refuse(ENTRY, KitPackRules.textR4(name, PackageManifest.mask(e.name())));
            }
        }
        requireInTime(deadline, req);

        // (b) VERIFY, in memory
        Map<String, byte[]> byName = new LinkedHashMap<>();
        for (KitZip.Entry e : entries) {
            byName.put(e.name(), e.bytes());
        }
        byte[] jar = byName.get(KitPackRules.JAR_ENTRY);
        if (jar == null) {
            throw refuse(DAMAGED, KitPackRules.textR3(name, KitPackRules.JAR_ENTRY));
        }
        String jarMd5 = ContentFingerprints.md5Hex(jar);
        TreeMap<String, byte[]> surface = new TreeMap<>();
        long surfaceBytes = 0;
        for (KitZip.Entry e : entries) {
            String key = KitPackRules.surfaceKeyOrNull(e.name());
            if (key != null) {
                surfaceBytes += e.bytes().length;
                if (surfaceBytes > KitPackRules.MAX_SURFACE_BYTES) {
                    throw refuse(ENTRY, KitPackRules.textR4(name, PackageManifest.mask(e.name())));
                }
                surface.put(key, e.bytes());
            }
        }
        byte[] modInfo = byName.get(KitPackRules.MOD_DIR + "/mod_info.json");
        if (modInfo == null) {
            throw refuse(DAMAGED, KitPackRules.textR3(name, "mod_info.json"));
        }
        String dataMd5 = ContentFingerprints.dataMd5(surface);
        String hs = String.valueOf(HandshakeCodec.HANDSHAKE_VERSION);
        List<String> baseSidecar = null;
        String baseWithoutMatch;
        if (!req.dirMode()) {
            byte[] sidecar = byName.get(KitPackRules.SIDECAR_ENTRY);
            if (sidecar == null) {
                throw refuse(NOT_THIS_KIT, KitPackRules.textR2(name));
            }
            if (sidecar.length > KitPackRules.MAX_SIDECAR_BYTES) {
                throw refuse(DAMAGED, KitPackRules.textR3(name, KitPackRules.SIDECAR_NAME));
            }
            baseSidecar = sidecarLines(sidecar);
            String composite;
            try {
                composite = ContentFingerprints.parseSidecarFingerprint(baseSidecar, "the kit sidecar");
            } catch (IllegalArgumentException noRow) {
                throw refuse(DAMAGED, KitPackRules.textR3(name, KitPackRules.SIDECAR_NAME));
            }
            if (!jarMd5.equals(ContentFingerprints.componentOrNull(composite, "jar"))
                    || !hs.equals(ContentFingerprints.componentOrNull(composite, "hs"))) {
                throw refuse(NOT_THIS_KIT, KitPackRules.textR2(name));
            }
            if (!dataMd5.equals(ContentFingerprints.componentOrNull(composite, "data"))) {
                throw refuse(DAMAGED, KitPackRules.textR3(name, "data"));
            }
            if (!fileRows(baseSidecar).equals(surface.keySet())) {
                throw refuse(DAMAGED, KitPackRules.textR3(name, KitPackRules.SIDECAR_NAME));
            }
            baseWithoutMatch = withoutMatch(composite);
        } else {
            baseWithoutMatch = withoutMatch(ContentFingerprints.composite(jarMd5, dataMd5,
                    HandshakeCodec.HANDSHAKE_VERSION, "m"));
        }
        if (req.manifestOrNull() != null) {
            PackageManifest m;
            try {
                m = PackageManifest.read(req.manifestOrNull());
            } catch (IOException | IllegalArgumentException unreadable) {
                throw refuse(NOT_THIS_KIT, KitPackRules.textR2(name));
            }
            boolean sameKit = jarMd5.equals(m.kitJarMd5()) && m.kitFingerprint() != null
                    && baseWithoutMatch.equals(withoutMatch(m.kitFingerprint()))
                    && (req.dirMode() || m.packKit() == null
                            || m.packKit().equals(String.valueOf(req.base().getFileName())));
            if (!sameKit) {
                throw refuse(NOT_THIS_KIT, KitPackRules.textR2(name));
            }
        }
        requireInTime(deadline, req);

        // (c) COMPOSE
        MatchDescriptor live = readLive(req, shape);
        // The kit ships no match file; the redacted form is what the door serves a joiner, so a
        // descriptor whose redaction still leaves a live token is refused here, and a zip: pack never
        // keeps tokens (KEEP is the repo roll's dir: posture only).
        if (req.posture() == Posture.REDACT) {
            String text = MatchDescriptorCodec.encode(MatchDescriptors.redact(live));
            if (!MatchDescriptors.isStrictlyRedacted(text.lines().toList())) {
                throw refuse(LIVE_TOKEN, KitPackRules.TEXT_R12);
            }
        } else if (!req.dirMode()) {
            throw refuse(LIVE_TOKEN, KitPackRules.TEXT_R12);
        }
        // the package sidecar = the mint WITH the match term (both modes: zip: verified jar / data / hs /
        // file= rows above, so the mint equals the base's lines re-keyed); kit.files = the same lines
        // with the composite's match term stripped
        List<String> sidecarLines;
        try {
            sidecarLines = mintLines(jarMd5, dataMd5, live.matchId(), surface.keySet());
        } catch (IllegalArgumentException unfit) {
            throw refuse(MATCH_READ, KitPackRules.textR6(shape, "its match id does not fit the kit fingerprint"));
        }
        byte[] sidecarBytes = sidecarBytes(sidecarLines);
        byte[] kitSidecarBytes = sidecarBytes(kitSidecarLines(sidecarLines));
        String[] versions = KitPackRules.modInfoVersions(new String(modInfo, StandardCharsets.UTF_8));
        if (versions == null) {
            throw refuse(DAMAGED, KitPackRules.textR3(name, "mod_info.json"));
        }
        byte[] manifestBytes = KitPackRules.manifestText(jarMd5, req.stamp(), versions[0], versions[1])
                .getBytes(StandardCharsets.US_ASCII);
        Set<String> replaced = new HashSet<>(List.of(KitPackRules.MATCH_ENTRY, KitPackRules.SIDECAR_ENTRY,
                KitPackRules.MANIFEST_ENTRY));
        byte[] readmeBytes = null;
        if (req.dirMode()) {
            replaced.add(KitPackRules.README_ENTRY);
            readmeBytes = KitPackRules.readmeText(sessionBlock(req), jreVersionOrNull(byName, name))
                    .getBytes(StandardCharsets.US_ASCII);
        }
        List<KitZip.Entry> composed = new ArrayList<>();
        for (KitZip.Entry e : entries) {
            if (!replaced.contains(e.name())) {
                composed.add(e);
            }
        }
        composed.add(new KitZip.Entry(KitPackRules.SIDECAR_ENTRY, kitSidecarBytes));
        composed.add(new KitZip.Entry(KitPackRules.MANIFEST_ENTRY, manifestBytes));
        if (readmeBytes != null) {
            composed.add(new KitZip.Entry(KitPackRules.README_ENTRY, readmeBytes));
        }

        // (d) CENSUS
        Census census = census(live, MatchShapes.shippedVariantIds(sidecarLines), req.gameRootOrNull());
        if (!census.ok()) {
            if (GAME_FOLDER.equals(census.token())) {
                throw refuse(GAME_FOLDER, KitPackRules.TEXT_R8);
            }
            List<String> shown = new ArrayList<>();
            for (String id : census.unresolvedIds()) {
                shown.add(PackageManifest.mask(id));
            }
            throw refuse(CENSUS, KitPackRules.textR7(shape, shown));
        }
        requireInTime(deadline, req);

        // (e) FREE SPACE
        Path out = req.out().toAbsolutePath().normalize();
        long need = KitPackRules.KITS_PER_PACK * inputBytes + KitPackRules.FREE_HEADROOM_BYTES;
        long usable;
        try {
            usable = Files.getFileStore(out.getParent()).getUsableSpace();
        } catch (IOException | RuntimeException noFolder) {
            throw refuse(WRITE, KitPackRules.textWrite("folder"));
        }
        if (usable < need) {
            throw refuse(DISK, KitPackRules.textR9((need + (1L << 20) - 1) >> 20));
        }
        boolean sidecarChanged = req.sidecarOut() != null && !Arrays.equals(readOrNull(req.sidecarOut()), sidecarBytes);
        if (!write) {
            return new Packed(new Result(true, CHECKED, KitPackRules.textChecked(shape), out, sidecarChanged),
                    composed.size(), jarMd5);
        }

        // (f) WRITE
        try {
            writeStep.write(out, composed, () -> !deadline.passed());
        } catch (KitZip.Abandoned late) {
            throw refuse(TIMEOUT, KitPackRules.textR11(ceilingShown(req)));
        } catch (FileAlreadyExistsException exists) {
            throw refuse(WRITE, KitPackRules.textWrite("exists"));
        } catch (IOException | RuntimeException failed) {
            throw refuse(WRITE, KitPackRules.textWrite("io"));
        }
        if (sidecarChanged) {
            try {
                ContentFingerprintMint.writeSidecar(req.sidecarOut(), sidecarLines);
            } catch (IOException | RuntimeException failed) {
                throw new Refusal(new Result(false, WRITE, KitPackRules.textWrite("fingerprint"), out, false));
            }
            if (!Arrays.equals(readOrNull(req.sidecarOut()), sidecarBytes)) {
                // the kit's sidecar bytes and the one writer's bytes forked: the door would bounce the kit
                throw new Refusal(new Result(false, WRITE, KitPackRules.textWrite("fingerprint"), out, true));
            }
        }
        boolean restart = sidecarChanged && serverMayHoldOldSidecar(req.manifestOrNull());
        String display = folderName(out) + "\\" + out.getFileName();
        return new Packed(new Result(true, MADE, KitPackRules.textDone(PackageManifest.mask(display), restart), out,
                sidecarChanged), composed.size(), jarMd5);
    }

    /** The descriptor, gated and decoded (R6 on a private name, an unreadable file or a non-match file). */
    private static MatchDescriptor readLive(Request req, String shape) throws Refusal {
        if (KitPackRules.isLeakName(String.valueOf(req.descriptor().getFileName()))) {
            throw refuse(MATCH_READ, KitPackRules.textR6(shape, "it is a private file and is never packed"));
        }
        byte[] liveBytes = readDescriptor(req.descriptor(), shape);
        try {
            return MatchDescriptorCodec.decode(new String(liveBytes, StandardCharsets.UTF_8).lines().toList());
        } catch (RuntimeException undecodable) {
            throw refuse(MATCH_READ, KitPackRules.textR6(shape, "it is not a match file"));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void requireWellFormed(Request req) {
        if (req == null || req.base() == null || req.descriptor() == null || req.posture() == null
                || req.out() == null || req.stamp() == null || req.out().toAbsolutePath().normalize().getParent() == null) {
            throw new IllegalArgumentException("kit packer: a required request field is missing");
        }
        if (!KitPackRules.STAMP.matcher(req.stamp()).matches()) {
            throw new IllegalArgumentException("kit packer: the stamp is not yyyyMMdd-HHmmss");
        }
        String outName = String.valueOf(req.out().getFileName());
        if (!outName.toLowerCase(Locale.ROOT).endsWith(".zip") || KitPackRules.isLeakName(outName)) {
            throw new IllegalArgumentException("kit packer: the out file is not a .zip name");
        }
        if (req.dirMode() && req.dirEntriesOrNull() == null) {
            throw new IllegalArgumentException("kit packer: dir mode needs the caller's entry list");
        }
    }

    private static void requireInTime(Deadline deadline, Request req) throws Refusal {
        if (deadline.passed()) {
            throw refuse(TIMEOUT, KitPackRules.textR11(ceilingShown(req)));
        }
    }

    /** The ceiling an R11 names: the request's, or {@link KitPackRules#CEILING_S}. */
    private static int ceilingShown(Request req) {
        return req.ceilingS() > 0 ? req.ceilingS() : KitPackRules.CEILING_S;
    }

    private static Refusal refuse(String token, String text) {
        return new Refusal(fail(token, text));
    }

    private static Refusal refuse(KitZip.Refused r, String name) {
        return switch (r.kind) {
            case INPUT -> refuse(TOO_BIG, KitPackRules.textR5(name, KitPackRules.MAX_INPUT_BYTES >> 20));
            case INFLATED -> refuse(TOO_BIG, KitPackRules.textR5(name, KitPackRules.MAX_INFLATED_BYTES >> 20));
            default -> refuse(ENTRY, KitPackRules.textR4(name, PackageManifest.mask(r.entry)));
        };
    }

    private static Result fail(String token, String text) {
        return new Result(false, token, text, null, false);
    }

    /**
     * The {@code dir:} input: each caller-listed name (the staged-name allowlist belt is the caller's) read from under
     * {@code base} with the zip reader's grammar and caps (count, per entry, inflated total); a name
     * that is no regular file under the stage (a link is not followed) = R3 naming it.
     */
    private static List<KitZip.Entry> readStage(Path base, List<String> names, String display) throws Refusal {
        Path root = base.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw refuse(NO_KIT, KitPackRules.textR1(display, folderDisplay(root)));
        }
        if (names.size() > KitPackRules.MAX_ENTRIES) {
            throw refuse(ENTRY, KitPackRules.textR4(display, PackageManifest.mask(names.get(KitPackRules.MAX_ENTRIES))));
        }
        List<KitZip.Entry> out = new ArrayList<>();
        Set<String> folded = new HashSet<>();
        long total = 0;
        for (String n : names) {
            if (!KitZip.nameOk(n) || !folded.add(n.toLowerCase(Locale.ROOT))) {
                throw refuse(ENTRY, KitPackRules.textR4(display, PackageManifest.mask(n)));
            }
            Path p = root.resolve(n).normalize();
            if (!p.startsWith(root) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                throw refuse(DAMAGED, KitPackRules.textR3(display, PackageManifest.mask(n)));
            }
            byte[] bytes;
            try (InputStream in = Files.newInputStream(p)) {
                bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 16L, KitPackRules.MAX_ENTRY_BYTES + 1));
            } catch (IOException unreadable) {
                throw refuse(DAMAGED, KitPackRules.textR3(display, PackageManifest.mask(n)));
            }
            if (bytes.length > KitPackRules.MAX_ENTRY_BYTES) {
                throw refuse(ENTRY, KitPackRules.textR4(display, PackageManifest.mask(n)));
            }
            total += bytes.length;
            if (total > KitPackRules.MAX_INFLATED_BYTES) {
                throw refuse(TOO_BIG, KitPackRules.textR5(display, KitPackRules.MAX_INFLATED_BYTES >> 20));
            }
            out.add(new KitZip.Entry(n, bytes));
        }
        return out;
    }

    /** The descriptor, bounded (≤ {@link KitPackRules#MAX_MATCH_TXT_BYTES}); R6 with a bounded reason, never content. */
    private static byte[] readDescriptor(Path descriptor, String shape) throws Refusal {
        if (!Files.isRegularFile(descriptor)) {
            throw refuse(MATCH_READ, KitPackRules.textR6(shape, "it is missing"));
        }
        try (InputStream in = Files.newInputStream(descriptor)) {
            byte[] bytes = in.readNBytes(KitPackRules.MAX_MATCH_TXT_BYTES + 1);
            if (bytes.length > KitPackRules.MAX_MATCH_TXT_BYTES) {
                throw refuse(MATCH_READ, KitPackRules.textR6(shape, "it is larger than "
                        + KitPackRules.MAX_MATCH_TXT_BYTES + " bytes"));
            }
            return bytes;
        } catch (IOException unreadable) {
            throw refuse(MATCH_READ, KitPackRules.textR6(shape, "it is unreadable"));
        }
    }

    /** {@code dir:} mint = {@code ContentFingerprintMint.mintSidecarLines} over the in-memory surface. */
    private static List<String> mintLines(String jarMd5, String dataMd5, String matchId, Set<String> surfaceKeys) {
        List<String> lines = new ArrayList<>(MINT_HEADER);
        lines.add("fingerprint=" + ContentFingerprints.composite(jarMd5, dataMd5, HandshakeCodec.HANDSHAKE_VERSION, matchId));
        for (String rel : surfaceKeys) {
            lines.add("file=" + rel);
        }
        return lines;
    }

    /**
     * The kit's own {@code kit.files}: {@code serverLines} with every {@code fingerprint=} composite through
     * {@link ContentFingerprints#stripMatch}, every other line as is. WHY: a kit carries content, never a
     * match - the match id rides the server's own sidecar and the wire, so one kit stays valid across every
     * match its server stages; the client reads only the {@code file=} rows.
     *
     */
    static List<String> kitSidecarLines(List<String> serverLines) {
        List<String> out = new ArrayList<>(serverLines.size());
        for (String line : serverLines) {
            out.add(line.startsWith(FINGERPRINT_ROW)
                    ? FINGERPRINT_ROW + ContentFingerprints.stripMatch(line.substring(FINGERPRINT_ROW.length()))
                    : line);
        }
        return out;
    }

    /** The sidecar's composite row prefix ({@link #mintLines} writes it, {@link #kitSidecarLines} strips its match term). */
    private static final String FINGERPRINT_ROW = "fingerprint=";

    /** The bytes {@link ContentFingerprintMint#writeSidecar} writes for {@code lines} (its pinned EOL, latin-1). */
    static byte[] sidecarBytes(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append(ContentFingerprintMint.SIDECAR_EOL);
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static List<String> sidecarLines(byte[] sidecar) {
        List<String> lines = new ArrayList<>(Arrays.asList(new String(sidecar, StandardCharsets.ISO_8859_1).split("\r?\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static Set<String> fileRows(List<String> sidecar) {
        Set<String> rows = new TreeSet<>();
        for (String line : sidecar) {
            if (line.startsWith("file=")) {
                rows.add(line.substring("file=".length()));
            }
        }
        return rows;
    }

    /** A composite without its {@code ;match=} component (the package MANIFEST's {@code kit.fingerprint} carries the kit's). */
    static String withoutMatch(String composite) {
        int at = composite.indexOf(";match=");
        if (at < 0) {
            return composite;
        }
        int end = composite.indexOf(';', at + 1);
        return composite.substring(0, at) + (end < 0 ? "" : composite.substring(end));
    }

    /**
     * The README's session block in {@code dir:} mode, the roll's order ({@code package-joiner-kit.ps1:413}):
     * a {@code <descriptor>.password} sibling EXISTS (an existence test, the file is never opened) = password-armed;
     * else REDACT = redacted-no-password; else KEEP = keep-tokens. Every value composes the same README
     * ({@link KitPackRules.SessionBlock}: the join window fetches the match, so no posture changes the text).
     */
    private static KitPackRules.SessionBlock sessionBlock(Request req) {
        boolean armed = Files.isRegularFile(Paths.get(req.descriptor() + ".password"));
        if (armed) {
            return KitPackRules.SessionBlock.PASSWORD_ARMED;
        }
        return req.posture() == Posture.REDACT ? KitPackRules.SessionBlock.REDACTED_NO_PASSWORD
                : KitPackRules.SessionBlock.KEEP_TOKENS;
    }

    /** The bundled runtime's {@code implementorVersion=} (first row; "" when absent), or null with no JRE stamp. */
    private static String jreVersionOrNull(Map<String, byte[]> byName, String display) throws Refusal {
        byte[] stamp = byName.get(KitPackRules.JRE_STAMP_ENTRY);
        if (stamp == null) {
            return null;
        }
        for (String row : new String(stamp, StandardCharsets.US_ASCII).split("\r?\n")) {
            if (row.startsWith("implementorVersion=")) {
                String v = row.substring("implementorVersion=".length());
                for (int i = 0; i < v.length(); i++) {
                    if (v.charAt(i) < 0x20 || v.charAt(i) > 0x7E) {
                        throw refuse(DAMAGED, KitPackRules.textR3(display, "cc-kit-jre.txt"));
                    }
                }
                return v;
            }
        }
        return "";
    }

    /** The current bytes at {@code file} (≤ the sidecar cap + 1), or null when absent / unreadable. */
    private static byte[] readOrNull(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(KitPackRules.MAX_SIDECAR_BYTES + 1);
        } catch (IOException unreadable) {
            QUIET_FAILURES.incrementAndGet();
            return null;
        }
    }

    /**
     * Whether a server may still hold the sidecar the pack just replaced: a package root unknown (no
     * MANIFEST given) = yes; else a PROBE (never a hold) of {@code <pkg>/stage.lock} (a start in flight)
     * and {@code <pkg>/run/serve.lock} (a running launcher) through {@code ServerOpsLaunch.lockFileHeld},
     * only when the lock file exists (no lock file is created). The door caches the composite at serve boot.
     */
    private static boolean serverMayHoldOldSidecar(Path manifestOrNull) {
        if (manifestOrNull == null) {
            return true;
        }
        Path pkg = manifestOrNull.toAbsolutePath().normalize().getParent();
        if (pkg == null) {
            return true;
        }
        return held(pkg.resolve(ServerOpsLaunch.STAGE_LOCK_FILE))
                || held(pkg.resolve(ServeLauncher.RUN_DIR).resolve(ServeLauncher.LOCK_FILE));
    }

    private static boolean held(Path lock) {
        return Files.isRegularFile(lock) && ServerOpsLaunch.lockFileHeld(lock);
    }

    /** Vanilla ids among {@code wanted} under {@code variants} (recursive, ≤ the walk cap); null = over the cap. */
    private static Set<String> vanillaIdsAmong(Path variants, Set<String> wanted) {
        Set<String> found = new HashSet<>();
        long[] files = {0};
        boolean[] over = {false};
        try {
            Files.walkFileTree(variants, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++files[0] > KitPackRules.MAX_VANILLA_FILES) {
                        over[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    String leaf = String.valueOf(file.getFileName());
                    if (attrs.isRegularFile() && leaf.toLowerCase(Locale.ROOT).endsWith(".variant")) {
                        String id = leaf.substring(0, leaf.length() - ".variant".length());
                        if (wanted.contains(id)) {
                            found.add(id);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failed) {
                    QUIET_FAILURES.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException unwalkable) {
            QUIET_FAILURES.incrementAndGet();
            return null;
        }
        return over[0] ? null : found;
    }

    private static String shapeName(Path descriptor) {
        String file = String.valueOf(descriptor.getFileName());
        String slug = KitPackRules.slugOf(file);
        return PackageManifest.mask(slug != null ? slug : file);
    }

    private static String folderName(Path p) {
        Path parent = p.toAbsolutePath().normalize().getParent();
        return parent == null || parent.getFileName() == null ? "" : parent.getFileName().toString();
    }

    private static String folderDisplay(Path p) {
        return PackageManifest.mask(folderName(p) + "\\");
    }

    // ---------------------------------------------------------------- the tool main

    /**
     * {@code base=zip:<kit zip>|dir:<stage> descriptor=<file> posture=redact|keep out=<zip>
     * sidecarOut=<file> stamp=<yyyyMMdd-HHmmss> [manifest=<MANIFEST.txt>] [game=<root>]
     * [entries=<listfile>] [check=true]} ({@code entries=} required with {@code dir:}: one
     * stage-relative name per line, UTF-8, ≤ 1 MiB, every listed name packed; {@code check=true} required with
     * {@code zip:}, else {@code reason=usage}: the tool never re-packs a release kit). Exit 0 + ONE stdout row
     * {@code kit-packer: ok file=<out name> entries=<n> jarMd5=<lowercase hex> stamp=<stamp>} (the same row for
     * {@code check=true}, nothing written), nothing on stderr; exit 2 + {@code kit-packer: refused reason=<token>}
     * with the R text on stderr
     * ({@code reason=usage} = a malformed command line). stdout never carries a path beyond the out NAME
     * and never a secret. No ceiling: a slow disk finishes.
     */
    public static void main(String[] kv) {
        int code;
        try {
            code = runMain(kv, System.out, System.err);
        } catch (RuntimeException unexpected) {
            System.err.println("kit-packer: internal " + unexpected.getClass().getSimpleName());
            System.out.println("kit-packer: refused reason=" + WRITE);
            code = 2;
        }
        System.exit(code);
    }

    /** The main's body (the rows drive it with their own streams). */
    static int runMain(String[] kv, PrintStream out, PrintStream err) {
        Map<String, String> args = new LinkedHashMap<>();
        Set<String> keys = Set.of("base", "descriptor", "posture", "out", "sidecarOut", "stamp", "manifest", "game",
                "entries", "check");
        for (String arg : kv == null ? new String[0] : kv) {
            int eq = arg == null ? -1 : arg.indexOf('=');
            if (eq <= 0 || !keys.contains(arg.substring(0, eq)) || args.put(arg.substring(0, eq), arg.substring(eq + 1)) != null) {
                return usage(out, err);
            }
        }
        String base = args.get("base");
        String posture = args.get("posture");
        String check = args.getOrDefault("check", "false");
        if (base == null || !(base.startsWith("zip:") || base.startsWith("dir:")) || base.length() == 4
                || blank(args.get("descriptor")) || blank(args.get("out")) || blank(args.get("sidecarOut"))
                || blank(args.get("stamp")) || !("redact".equals(posture) || "keep".equals(posture))
                || !("true".equals(check) || "false".equals(check))
                || !args.get("sidecarOut").endsWith(".fingerprint")) {
            return usage(out, err);
        }
        boolean dirMode = base.startsWith("dir:");
        // The tool's zip: mode is the package-time census only (check=true) -
        // a kit is code and carries no match file, so no tool run re-packs a release kit.
        if (!dirMode && !"true".equals(check)) {
            return usage(out, err);
        }
        List<String> entries = null;
        if (dirMode) {
            if (blank(args.get("entries"))) {
                return usage(out, err);
            }
            try (InputStream in = Files.newInputStream(Paths.get(args.get("entries")))) {
                byte[] list = in.readNBytes(KitPackRules.MAX_SIDECAR_BYTES + 1);
                if (list.length > KitPackRules.MAX_SIDECAR_BYTES) {
                    return usage(out, err);
                }
                entries = new ArrayList<>();
                for (String line : new String(list, StandardCharsets.UTF_8).split("\r?\n")) {
                    if (!line.isBlank()) {
                        entries.add(line);
                    }
                }
            } catch (IOException | RuntimeException unreadable) {
                QUIET_FAILURES.incrementAndGet();
                return usage(out, err);
            }
        } else if (args.containsKey("entries")) {
            return usage(out, err);
        }
        Request req;
        try {
            req = new Request(Paths.get(base.substring(4)), dirMode, Paths.get(args.get("descriptor")),
                    "keep".equals(posture) ? Posture.KEEP : Posture.REDACT, Paths.get(args.get("out")),
                    Paths.get(args.get("sidecarOut")), args.get("stamp"),
                    args.containsKey("manifest") ? Paths.get(args.get("manifest")) : null,
                    args.containsKey("game") ? Paths.get(args.get("game")) : null, entries, 0);
            requireWellFormed(req);
        } catch (RuntimeException malformed) {
            QUIET_FAILURES.incrementAndGet();
            return usage(out, err);
        }
        Packed p = run(req, !"true".equals(check), Deadline.of(0));
        Result r = p.result();
        if (!r.ok()) {
            err.println("kit-packer: " + r.text());
            out.println("kit-packer: refused reason=" + r.token());
            return 2;
        }
        out.println("kit-packer: ok file=" + PackageManifest.mask(String.valueOf(req.out().getFileName()))
                + " entries=" + p.entries() + " jarMd5=" + p.jarMd5() + " stamp=" + req.stamp());
        return 0;
    }

    private static int usage(PrintStream out, PrintStream err) {
        err.println("kit-packer: usage: base=zip:<kit zip>|dir:<stage> descriptor=<file> posture=redact|keep out=<zip>"
                + " sidecarOut=<file.fingerprint> stamp=<yyyyMMdd-HHmmss> [manifest=<MANIFEST.txt>] [game=<root>]"
                + " [entries=<listfile> (dir: only, required)]"
                + " [check=true (zip: only, required; dir: optional)]");
        out.println("kit-packer: refused reason=usage");
        return 2;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
