package cc.starsector.server.match;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * The package's library of match shapes.
 * WHY one class: the pack ({@link #writeShapeSet}), New
 * ({@link #create}) and the shells' authoring ({@link #mint}) must agree on ONE ship law and ONE write order, or a
 * shipped shape and its re-keyed sidecar drift apart and the armed door refuses every player of that shape.
 *
 * <ul>
 * <li><b>Ship law</b> ({@link #library}): {@code match-<n>.txt} ships iff its {@code match-<n>-tokens.txt} twin
 * EXISTS (never opened) and it decodes; a twin-ed file that does not decode refuses the pack, naming it.</li>
 * <li><b>Shipped set</b> ({@link #writeShapeSet}): every library shape but the server's standing one (deduped by
 * the SHAPE STRING {@code match.shape}, never by {@code match.id}: the packer stages {@code match/match.txt} at the
 * host's player count under a NEW id, so an id compare would ship the standing shape's library
 * twin at its own count beside it and the console would offer the fortress twice; {@code match/match.txt} = the
 * standing shape, written elsewhere) lands REDACTED as
 * {@code match/<stem>.txt} with a {@code <stem>.txt.fingerprint} re-keyed from the STANDING server sidecar (the
 * match-keyed one beside {@code match/match.txt}; a kit holds no match file and its own sidecar no match term) -
 * the sidecar first, then the descriptor (while split, the sidecar names an id nobody holds).</li>
 * <li><b>New</b> ({@link #create}): a fresh identity of a base shape at new values, redacted, beside a re-key of
 * the standing sidecar; refusals = the {@code T6_*} texts verbatim.</li>
 * </ul>
 *
 * <p>Never depends on {@code ops/}. Never opens a {@code -tokens} file, a {@code .password} or a door
 * credential; never writes a live seat token (every descriptor written here passes
 * {@link MatchDescriptors#isStrictlyRedacted}); every sidecar byte goes through
 * {@link ContentFingerprintMint#writeSidecar}. Operator console / packer JVM only, never a serve sim thread.
 *
 *
 */
public final class MatchShapes {

    private MatchShapes() {
    }

    /** Descriptor read cap, 262144 B = {@code ServeLauncher.MAX_CONFIG_BYTES} (the ONE cap set): a file
     *  past it is refused (library) or skipped (the New folder scan), never truncated. */
    public static final int MAX_DESCRIPTOR_BYTES = 262144;
    /** N1: at most 32 library shapes are written at pack (more refuses the pack, nothing written). */
    public static final int MAX_SHIPPED_SHAPES = 32;
    /** N2: New refuses when {@code match/} already holds 64 decodable shapes. */
    public static final int MAX_FOLDER_SHAPES = 64;
    /** A listing bound (never a read): more than 256 candidate {@code .txt} names = the library refuses / the New
     *  folder counts as full (the {@code OpsMatchApply.MAX_OFFERED} bound). */
    public static final int MAX_LISTED = 256;
    /** N7: a New shape name. */
    public static final Pattern NAME_RULE = Pattern.compile("[a-z0-9-]{1,32}");
    /** New's map half bounds, whole su - the same law as {@code OpsMatchApply.MAP_HALF_MIN / MAX} (copied: match/
     *  never calls ops/). */
    public static final float MAP_HALF_MIN = 2000f;
    public static final float MAP_HALF_MAX = 60000f;

    /** Every shape file name starts so; the stem is the name without {@link #TXT} ({@code match-<name>}). */
    public static final String SHAPE_PREFIX = "match-";
    public static final String TXT = ".txt";
    /** The twin marker: {@code <stem>-tokens.txt} is a shape's live-token SOURCE twin; any name carrying
     *  {@code -tokens} is never opened (the {@code OpsMatchApply.offeredDescriptors} skip). */
    public static final String TOKENS_MARK = "-tokens";
    public static final String SIDECAR_SUFFIX = ".fingerprint";
    /** The descriptor write's temp sibling suffix (not {@code *.txt}, so never offered). */
    public static final String DESCRIPTOR_TEMP_SUFFIX = ".shape-tmp";
    /** The kit sidecar's variant surface: {@code file=data/variants/**}{@code /<id>.variant} (ASCII-case-folded like
     *  the Windows packer's {@code Get-ChildItem -Filter '*.variant'}). */
    static final String VARIANTS_PREFIX = "data/variants/";
    static final String VARIANT_EXT = ".variant";

    // The New-shape refusal / result texts, VERBATIM (placeholders in <>)
    public static final String CREATE_REFUSED = "Create refused: ";
    public static final String T6_NAME = "names use a-z, 0-9 and - (1 to 32)";
    public static final String T6_EXISTS = "<name> already exists";
    public static final String T6_SHIP = "it fields the ship <id>, which the players' kit lacks - pick another base";
    public static final String T6_FULL = "the match folder is full (64)";
    /** A Players value outside the base shape's {@link MatchDescriptors#playersRange}.
     * */
    public static final String T6_PLAYERS = "<shape> takes <min> to <max> players";
    public static final String T6_CREATED = "created <name> - pick it and press Start; every join window fetches it.";

    private static final AtomicLong UNDECODABLE_SKIPS = new AtomicLong();

    /** A shippable library shape: {@code stem} = {@code match-<name>}, {@code live} = its decoded SOURCE (live
     *  tokens - never written as is). */
    public record Shape(String stem, Path file, MatchDescriptor live) {
    }

    /** {@link #writeShapeSet}'s outcome: the stems written, and the stems skipped as the standing shape's own
     *  {@code match.shape} string (at any seat count or id). */
    public record ShapeSetResult(List<String> written, List<String> deduped) {
    }

    /** {@link #create}'s outcome: {@code ok} + the T6 text; {@code file} = the new descriptor, null on a refusal. */
    public record CreateResult(boolean ok, String text, Path file) {
    }

    /** Process meter: {@code .txt} files New's folder scan skipped because they did not decode (a hand-made or
     *  damaged file is not a shape; counted, never logged - its name is operator data). */
    public static long undecodableSkips() {
        return UNDECODABLE_SKIPS.get();
    }

    /**
     * THE SHIP LAW over a library folder (the maintainer's joiner-kit folder): every
     * {@code match-*.txt} whose name carries no {@code -tokens}; a candidate without a
     * {@code <stem>-tokens.txt} twin is skipped (the {@code fixed-*} / {@code vanilla-*} rig shapes); the twin's
     * EXISTENCE alone decides ({@code NOFOLLOW_LINKS}: a directory or a link counts, nothing is opened); a twin-ed
     * candidate that is not a regular file or does not decode within {@link #MAX_DESCRIPTOR_BYTES} THROWS naming
     * the file (refuses the pack - a release never drops a shape silently). Sorted by stem (ordinal).
     */
    public static List<Shape> library(Path dir) throws IOException {
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path p : entries) {
                String name = p.getFileName().toString();
                if (!name.startsWith(SHAPE_PREFIX) || !name.endsWith(TXT) || name.contains(TOKENS_MARK)) {
                    continue;
                }
                if (candidates.size() >= MAX_LISTED) {
                    throw new IOException("the match-shape library lists over " + MAX_LISTED
                            + " match-*.txt names - the pack refuses");
                }
                candidates.add(p);
            }
        }
        candidates.sort(Comparator.comparing(p -> p.getFileName().toString()));
        List<Shape> shapes = new ArrayList<>();
        for (Path p : candidates) {
            String name = p.getFileName().toString();
            String stem = name.substring(0, name.length() - TXT.length());
            if (!Files.exists(p.resolveSibling(stem + TOKENS_MARK + TXT), LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            MatchDescriptor live;
            try {
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("not a regular file");
                }
                live = MatchDescriptorCodec.decode(readDescriptorLines(p));
            } catch (IOException | RuntimeException notAShape) {
                // the cause is NOT chained: a decode message may echo a line of a live-token file
                throw new IOException("match shape " + name + " has a -tokens twin but is not a readable match"
                        + " file (" + notAShape.getClass().getSimpleName() + ") - the pack refuses");
            }
            shapes.add(new Shape(stem, p, live));
        }
        return shapes;
    }

    /**
     * The variant ids the kit ships, read off its sidecar's {@code file=} rows (the grammar
     * {@link ContentFingerprintMint#mintSidecarLines} writes: {@code file=<mod-root-relative, /-separated path>}).
     * The id rule: a row under {@code data/variants/} (any depth) ending {@code .variant} (both
     * ASCII-case-insensitive, as the Windows walk) is keyed by its last path segment minus {@code .variant}, case
     * kept (ids compare ordinal). Comments and other rows ignored. re-worded
     * when the kit packer's keep-list strip was deleted - the kit ships every custom variant def it stages.
     */
    public static Set<String> shippedVariantIds(List<String> kitSidecarLines) {
        Set<String> ids = new TreeSet<>();
        for (String line : kitSidecarLines) {
            if (!line.startsWith("file=")) {
                continue;
            }
            String rel = line.substring("file=".length());
            String folded = rel.toLowerCase(Locale.ROOT);
            if (!folded.startsWith(VARIANTS_PREFIX) || !folded.endsWith(VARIANT_EXT)) {
                continue;
            }
            String leaf = rel.substring(rel.lastIndexOf('/') + 1);
            String id = leaf.substring(0, leaf.length() - VARIANT_EXT.length());
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * The pack's shipped set: every {@link #library} shape whose {@code match.shape} STRING
     * differs from {@code kitShape}'s - the server's STANDING shape, the one {@code match/match.txt} holds (an equal
     * shape string = DEDUPED at ANY seat count or {@code match.id}, listed, never written here: the packer's
     * {@code -Players} stage re-mints match.txt under a new id, the host sets the count in the console's Players
     * box, and every join window fetches the serve's match from the server). {@code kitSidecarLines}
     * = the STANDING server sidecar's lines (match-keyed, its content half the kit's: a kit's own sidecar carries no
     * match term to re-key). Checked for ALL shapes before the first write (a refusal writes nothing): the ROSTER
     * BELT - a roster variant id in {@code modVariantIds} (the mod's own variants) that those {@code file=}
     * rows (the kit's data surface) do not ship THROWS naming the shape + the id (a joiner would field
     * {@code errorShipVariant}; an id outside {@code modVariantIds} is vanilla, admitted); N1
     * ({@link #MAX_SHIPPED_SHAPES}); that sidecar's re-key per shape ({@link ContentFingerprintMint#rekeySidecarLines};
     * its IllegalArgumentException propagates). Then per
     * shape: the re-keyed sidecar FIRST ({@code <stem>.txt.fingerprint}, the one writer), then the descriptor
     * {@code <stem>.txt} = {@code encode(redact(live))} (LF text, temp + ATOMIC_MOVE). Twins are never opened.
     */
    public static ShapeSetResult writeShapeSet(List<String> kitSidecarLines, MatchDescriptor kitShape, Path library,
            Set<String> modVariantIds, Path matchDir) throws IOException {
        Objects.requireNonNull(kitShape, "kitShape");
        Objects.requireNonNull(modVariantIds, "modVariantIds");
        Set<String> shipped = shippedVariantIds(kitSidecarLines);
        List<Shape> toWrite = new ArrayList<>();
        List<String> deduped = new ArrayList<>();
        for (Shape shape : library(library)) {
            if (shape.live().shape().equals(kitShape.shape())) {
                deduped.add(shape.stem());
                continue;
            }
            for (MatchDescriptor.RosterRow row : shape.live().roster()) {
                String id = row.variantId();
                if (modVariantIds.contains(id) && !shipped.contains(id)) {
                    throw new IOException("match shape " + shape.stem() + " fields the mod variant " + id
                            + ", which the kit stripped (no file= row) - the pack refuses");
                }
            }
            toWrite.add(shape);
        }
        if (toWrite.size() > MAX_SHIPPED_SHAPES) {
            throw new IOException(toWrite.size() + " match shapes would ship, over the " + MAX_SHIPPED_SHAPES
                    + " cap - the pack refuses");
        }
        List<List<String>> sidecars = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Shape shape : toWrite) {
            sidecars.add(ContentFingerprintMint.rekeySidecarLines(kitSidecarLines, shape.live().matchId()));
            texts.add(redactedText(shape.live()));
        }
        List<String> written = new ArrayList<>();
        for (int i = 0; i < toWrite.size(); i++) {
            String stem = toWrite.get(i).stem();
            ContentFingerprintMint.writeSidecar(matchDir.resolve(stem + TXT + SIDECAR_SUFFIX), sidecars.get(i));
            writeDescriptor(matchDir.resolve(stem + TXT), texts.get(i));
            written.add(stem);
        }
        return new ShapeSetResult(List.copyOf(written), List.copyOf(deduped));
    }

    /**
     * A fresh identity (new {@code match.id} + seat tokens) of {@code base}'s shape at these values, through THE
     * re-mint {@link MatchDescriptors#remint(MatchDescriptor, float, float, String, String)}: {@code base}'s CURRENT
     * station variant threaded (a station-less mint would reset it to the family default), the backdrop word set,
     * and {@code base}'s own seat count kept (a 40-player file re-mints at 40, never at the spec's stock count).
     * Values are not range-checked here ({@link #create} does).
     *
     *
     */
    public static MatchDescriptor mint(MatchDescriptor base, float half, float cr, String terrain) {
        return MatchDescriptors.remint(base, half, cr, stationVariantIdOrNull(base), terrain);
    }

    /** {@link #mint(MatchDescriptor, float, float, String)} at {@code players} seats (New's Players leg; out of
     *  {@link MatchDescriptors#playersRange} THROWS - {@link #create} refuses it first as {@link #T6_PLAYERS}). */
    public static MatchDescriptor mint(MatchDescriptor base, float half, float cr, String terrain, int players) {
        return MatchDescriptors.remint(base, players, half, cr, stationVariantIdOrNull(base), terrain);
    }

    /**
     * New: {@code match/match-<name>.txt} = a {@link #mint} of {@code base} at these values, REDACTED,
     * beside {@code standingSidecarLines} re-keyed to its id. Refusals, in this order, as T6 texts: N7 name (a name
     * carrying {@code -tokens} too - such a file would never be offered); the file exists; the {@code file=} belt
     * (every roster id of {@code base}, and of the minted shape, must be shipped by the standing sidecar's
     * {@code file=} rows or fielded by a decodable shape already in {@code matchDir} - those passed the pack's
     * belt); N2 ({@link #MAX_FOLDER_SHAPES} decodable shapes, or over {@link #MAX_LISTED} {@code .txt} names);
     * {@link #T6_PLAYERS} (this overload asks for {@code base}'s own seat count; the Players overload below takes
     * the host's).
     * Out-of-range values ({@code half} whole in {@link #MAP_HALF_MIN}..{@link #MAP_HALF_MAX}, {@code cr} in
     * (0, 1], {@code terrain} a {@link MatchDescriptor#TERRAIN_WORDS} word) THROW IllegalArgumentException: the
     * shells parse with the match-setting Apply's parsers and own those texts; {@code T6_*} has none. A standing sidecar that cannot re-key throws
     * IllegalArgumentException; an I/O failure throws IOException (a descriptor-write failure deletes the sidecar it
     * just placed). The sidecar lands FIRST, then the descriptor; no live token reaches disk.
     */
    public static CreateResult create(Path matchDir, String name, MatchDescriptor base, float half, float cr,
            String terrain, List<String> standingSidecarLines) throws IOException {
        Objects.requireNonNull(base, "base");
        return create(matchDir, name, base, half, cr, terrain, standingSidecarLines, base.seats().size());
    }

    /**
     * {@link #create(Path, String, MatchDescriptor, float, float, String, List)} at {@code players} seats (the New
     * dialog's Players field): a count outside {@link MatchDescriptors#playersRange} of {@code base}'s shape is the
     * refusal {@link #T6_PLAYERS} (a {@link CreateResult}, never an IllegalArgumentException - an exception here
     * would land on the shells' wrong-text path), checked after N2 and before the value ranges; nothing is written.
     *
     *
     */
    public static CreateResult create(Path matchDir, String name, MatchDescriptor base, float half, float cr,
            String terrain, List<String> standingSidecarLines, int players) throws IOException {
        if (name == null || !NAME_RULE.matcher(name).matches() || ("-" + name).contains(TOKENS_MARK)) {
            return refused(T6_NAME);
        }
        String stem = SHAPE_PREFIX + name;
        Path file = matchDir.resolve(stem + TXT);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return refused(T6_EXISTS.replace("<name>", name));
        }
        Set<String> kitIds = new HashSet<>(shippedVariantIds(standingSidecarLines));
        int decodable = 0;
        boolean overListed = false;
        List<Path> txt = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(matchDir)) {
            for (Path p : entries) {
                String n = p.getFileName().toString();
                if (!n.endsWith(TXT) || n.contains(TOKENS_MARK)) {
                    continue;
                }
                if (txt.size() >= MAX_LISTED) {
                    overListed = true;
                    break;
                }
                txt.add(p);
            }
        }
        for (Path p : txt) {
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            MatchDescriptor d;
            try {
                d = MatchDescriptorCodec.decode(readDescriptorLines(p));
            } catch (IOException | RuntimeException notAShape) {
                UNDECODABLE_SKIPS.incrementAndGet();
                continue;
            }
            decodable++;
            for (MatchDescriptor.RosterRow row : d.roster()) {
                kitIds.add(row.variantId());
            }
        }
        String missing = firstMissing(base, kitIds);
        if (missing != null) {
            return refused(T6_SHIP.replace("<id>", missing));
        }
        if (overListed || decodable >= MAX_FOLDER_SHAPES) {
            return refused(T6_FULL);
        }
        int[] range = MatchDescriptors.playersRange(base.shape());
        if (players < range[0] || players > range[1]) {
            return refused(T6_PLAYERS.replace("<shape>", base.shape())
                    .replace("<min>", Integer.toString(range[0])).replace("<max>", Integer.toString(range[1])));
        }
        if (!(half >= MAP_HALF_MIN && half <= MAP_HALF_MAX) || half != Math.rint(half)) {
            throw new IllegalArgumentException("map half outside the whole 2000..60000 range (key half)");
        }
        if (!(cr > 0f && cr <= 1f)) {
            throw new IllegalArgumentException("deploy CR outside (0, 1] (key cr)");
        }
        if (terrain == null || !MatchDescriptor.TERRAIN_WORDS.contains(terrain)) {
            throw new IllegalArgumentException("backdrop is not a descriptor terrain word (key terrain)");
        }
        MatchDescriptor minted = MatchDescriptors.redact(mint(base, half, cr, terrain, players));
        missing = firstMissing(minted, kitIds);
        if (missing != null) {
            return refused(T6_SHIP.replace("<id>", missing));
        }
        List<String> sidecar = ContentFingerprintMint.rekeySidecarLines(standingSidecarLines, minted.matchId());
        String text = redactedText(minted);
        Path sidecarFile = matchDir.resolve(stem + TXT + SIDECAR_SUFFIX);
        ContentFingerprintMint.writeSidecar(sidecarFile, sidecar);
        try {
            writeDescriptor(file, text);
        } catch (IOException | RuntimeException failed) {
            try {
                Files.deleteIfExists(sidecarFile);
            } catch (IOException | RuntimeException leak) {
                failed.addSuppressed(leak);
            }
            throw failed;
        }
        return new CreateResult(true, T6_CREATED.replace("<name>", name), file);
    }

    private static CreateResult refused(String why) {
        return new CreateResult(false, CREATE_REFUSED + why, null);
    }

    private static String firstMissing(MatchDescriptor d, Set<String> admitted) {
        for (MatchDescriptor.RosterRow row : d.roster()) {
            if (!admitted.contains(row.variantId())) {
                return row.variantId();
            }
        }
        return null;
    }

    /** {@code encode(redact(d))}, refused unless strictly redacted (a guard on the no-live-token law: a shape with
     *  no seat rows has nothing to redact and is no playable shape either). */
    private static String redactedText(MatchDescriptor d) throws IOException {
        String text = MatchDescriptorCodec.encode(MatchDescriptors.redact(d));
        if (!MatchDescriptors.isStrictlyRedacted(text.lines().toList())) {
            throw new IOException("a shape text is not strictly redacted - refusing to write it");
        }
        return text;
    }

    /** The descriptor lands via the temp sibling {@code <file>.shape-tmp} + ATOMIC_MOVE (UTF-8, the codec's LF). */
    private static void writeDescriptor(Path file, String text) throws IOException {
        Path descriptorTemp = Paths.get(file + DESCRIPTOR_TEMP_SUFFIX);
        try {
            Files.writeString(descriptorTemp, text, StandardCharsets.UTF_8);
            Files.move(descriptorTemp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failed) {
            try {
                Files.deleteIfExists(descriptorTemp);
            } catch (IOException | RuntimeException leak) {
                failed.addSuppressed(leak);
            }
            throw failed;
        }
    }

    /** A bounded descriptor read (≤ {@link #MAX_DESCRIPTOR_BYTES}; past it = IOException, never truncated). Public:
     * the ONE descriptor-file read law, shared with {@code MatchTokenRedact}. */
    public static List<String> readDescriptorLines(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
            if (bytes.length > MAX_DESCRIPTOR_BYTES) {
                throw new IOException("over the " + MAX_DESCRIPTOR_BYTES + " B descriptor read cap");
            }
            return new String(bytes, StandardCharsets.UTF_8).lines().toList();
        }
    }

    /** The descriptor's station roster-row variant id, or null (the {@code OpsMatchApply} helper, copied). */
    private static String stationVariantIdOrNull(MatchDescriptor descriptor) {
        for (MatchDescriptor.RosterRow row : descriptor.roster()) {
            if (MatchDescriptors.isStationVariantId(row.variantId())) {
                return row.variantId();
            }
        }
        return null;
    }
}
