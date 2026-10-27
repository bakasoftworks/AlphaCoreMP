package cc.starsector.server.ops;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The joiner-kit zip reader and writer.
 * WHY its own class: a package host re-packs a DOWNLOADED-class file (the release kit: data,
 * never trusted), so the read is a bounded grammar ({@code ZipFile}, never {@code jdk.zipfs}; every
 * cap in {@link KitPackRules}) and the write is reproducible (the roll's {@code Compress-Archive}
 * kit stamped each entry with its local mtime and carried 4 directory entries, so no two rolls were
 * byte-equal) and crash-safe (a temp {@code .cc-kit-<rand>.part} in the target folder, a round-trip
 * re-read, then one rename; an existing target is refused, never overwritten).
 *
 *
 */
public final class KitZip {

    private KitZip() {
    }

    /** One file entry: a {@code /}-separated relative name and its inflated bytes. */
    public record Entry(String name, byte[] bytes) {
        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(bytes, "bytes");
        }
    }

    /** The read caps (byte counts and counts are non-negative; see {@link KitPackRules} for the kit's). */
    public record Caps(long maxInputBytes, int maxEntries, long maxInflatedBytes, long maxEntryBytes, int maxRatio) {
    }

    /** The release kit's caps. */
    public static final Caps KIT_CAPS = new Caps(KitPackRules.MAX_INPUT_BYTES, KitPackRules.MAX_ENTRIES,
            KitPackRules.MAX_INFLATED_BYTES, KitPackRules.MAX_ENTRY_BYTES, KitPackRules.MAX_RATIO);

    /** The round trip re-reads what THIS writer just wrote: the grammar still applies, the size caps do not. */
    private static final Caps ROUND_TRIP_CAPS = new Caps(Long.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE,
            Integer.MAX_VALUE - 16, Integer.MAX_VALUE);

    /** The temp name's prefix + suffix ({@code .cc-kit-<16 hex>.part}); {@link KitPacker#sweepTemps} matches them. */
    public static final String TEMP_PREFIX = ".cc-kit-";
    /** See {@link #TEMP_PREFIX}. */
    public static final String TEMP_SUFFIX = ".part";

    /** Failed temp deletes (each counted once; the write's own failure is what the caller sees). */
    static final AtomicLong QUIET_FAILURES = new AtomicLong();
    /** The temps a write in THIS JVM is still filling (absolute, normalized): {@link KitPacker#sweepTemps} spares them. */
    static final Set<Path> LIVE_TEMPS = ConcurrentHashMap.newKeySet();

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Why {@link #read} refused (the kind picks the packer's R4 / R5 text). */
    public enum Kind {
        /** The zip file itself is over {@link Caps#maxInputBytes}. */
        INPUT,
        /** More than {@link Caps#maxEntries} entries. */
        COUNT,
        /** A name outside the grammar ({@code /}-only, no {@code ..} {@code \} {@code :} or leading {@code /}, printable ASCII). */
        NAME,
        /** Two names equal after ASCII case folding, or a file name that is also a parent folder of another. */
        DUPLICATE,
        /** A directory entry that prefixes no file entry. */
        DIRECTORY,
        /** One entry over {@link Caps#maxEntryBytes}. */
        ENTRY,
        /** One entry inflating past {@link Caps#maxRatio} x its compressed size. */
        RATIO,
        /** The inflated total over {@link Caps#maxInflatedBytes}. */
        INFLATED
    }

    /** {@link #read}'s refusal: the kind and the entry name it names (raw; the caller masks it for a text). */
    public static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;
        public final Kind kind;
        public final String entry;

        Refused(Kind kind, String entry) {
            super("kit zip refused: " + kind);
            this.kind = kind;
            this.entry = entry;
        }
    }

    /** {@link #write}'s commit gate said no (the caller's deadline passed): the temp is gone, nothing was written. */
    public static final class Abandoned extends IOException {
        private static final long serialVersionUID = 1L;

        Abandoned() {
            super("kit zip write abandoned before the commit");
        }
    }

    /**
     * Reads every FILE entry of {@code zip} in central-directory order, under {@code caps}. Grammar:
     * names are printable ASCII, {@code /}-separated, with no empty / {@code .} / {@code ..} segment, no
     * {@code \}, no {@code :}, no leading {@code /} ({@code PackageManifest.safeRelative}); no two names
     * equal after ASCII case folding and no file name that is a parent folder of another (a Windows
     * extraction would collide); a {@code /}-suffixed DIRECTORY entry is SKIPPED iff it prefixes a file
     * entry, else refused (the real kit carries 4, all prefixes). Every entry's bytes are inflated with
     * the caps counted on the bytes ACTUALLY read (a lying header cannot widen them) and checked against
     * the entry's CRC-32 and size ({@code ZipFile} itself checks neither). THROWS {@link Refused} (a cap or
     * the grammar), {@link ZipException} (not a zip / a CRC or size mismatch), or {@link IOException}.
     */
    public static List<Entry> read(Path zip, Caps caps) throws IOException {
        if (Files.size(zip) > caps.maxInputBytes()) {
            throw new Refused(Kind.INPUT, null);
        }
        List<Entry> files = new ArrayList<>();
        List<String> dirs = new ArrayList<>();
        Set<String> folded = new HashSet<>();
        long total = 0;
        int count = 0;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> all = zf.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                String name = e.getName();
                if (++count > caps.maxEntries()) {
                    throw new Refused(Kind.COUNT, name);
                }
                boolean dir = name.endsWith("/");
                if (!nameOk(dir ? name.substring(0, name.length() - 1) : name)) {
                    throw new Refused(Kind.NAME, name);
                }
                if (!folded.add(name.toLowerCase(Locale.ROOT))) {
                    throw new Refused(Kind.DUPLICATE, name);
                }
                if (dir) {
                    if (e.getSize() > 0) {
                        throw new Refused(Kind.NAME, name);
                    }
                    dirs.add(name);
                    continue;
                }
                long compressed = Math.max(1L, e.getCompressedSize());
                if (e.getSize() > caps.maxEntryBytes()) {
                    throw new Refused(Kind.ENTRY, name);
                }
                if (e.getSize() > 0 && e.getSize() / compressed > caps.maxRatio()) {
                    throw new Refused(Kind.RATIO, name);
                }
                long room = Math.min(caps.maxEntryBytes(), caps.maxInflatedBytes() - total);
                byte[] bytes;
                try (InputStream in = zf.getInputStream(e)) {
                    bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 16L, room + 1));
                }
                if (bytes.length > caps.maxEntryBytes()) {
                    throw new Refused(Kind.ENTRY, name);
                }
                total += bytes.length;
                if (total > caps.maxInflatedBytes()) {
                    throw new Refused(Kind.INFLATED, name);
                }
                if (bytes.length / compressed > caps.maxRatio()) {
                    throw new Refused(Kind.RATIO, name);
                }
                if ((e.getSize() >= 0 && bytes.length != e.getSize()) || (e.getCrc() >= 0 && crc(bytes) != e.getCrc())) {
                    throw new ZipException("kit zip entry size or CRC mismatch");
                }
                files.add(new Entry(name, bytes));
            }
        }
        requireNoParentCollision(files);
        for (String dir : dirs) {
            boolean prefixes = false;
            for (Entry f : files) {
                if (f.name().startsWith(dir)) {
                    prefixes = true;
                    break;
                }
            }
            if (!prefixes) {
                throw new Refused(Kind.DIRECTORY, dir);
            }
        }
        return files;
    }

    /** {@link #write(Path, List, BooleanSupplier)} with no commit gate. */
    public static void write(Path out, List<Entry> entries) throws IOException {
        write(out, entries, () -> true);
    }

    /**
     * The reproducible write: the entries sorted by name (ordinal), NO directory entries, every entry
     * DEFLATEd at the default level with its time = {@link KitPackRules#ENTRY_TIME} set through
     * {@code ZipEntry.setTimeLocal} (the DOS date/time fields only, no extended-timestamp field = the
     * mechanism of the {@code jar --date} pin, so the bytes do not depend on the host's time zone), no
     * comment; two writes of the same entries are byte-equal. Lands through {@code <dir>/.cc-kit-<16
     * hex>.part} (CREATE_NEW) → a ROUND TRIP ({@link #read} of the temp: every name and every byte equal
     * to what was meant) → {@code commitOk} (false = {@link Abandoned}) → an existing {@code out} refused
     * ({@link FileAlreadyExistsException}) → ONE {@code ATOMIC_MOVE} rename. Any failure deletes the temp
     * (a failed delete counted in {@link #QUIET_FAILURES}). THROWS {@link IllegalArgumentException} on a
     * name outside {@link #read}'s grammar or a duplicate (a programming error: the packer composes them).
     */
    public static void write(Path out, List<Entry> entries, BooleanSupplier commitOk) throws IOException {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(Entry::name));
        Set<String> folded = new HashSet<>();
        for (Entry e : sorted) {
            if (!nameOk(e.name()) || !folded.add(e.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("kit zip write: an entry name is outside the grammar or repeated");
            }
        }
        try {
            requireNoParentCollision(sorted);
        } catch (Refused collision) {
            throw new IllegalArgumentException("kit zip write: a file name is also a folder of another entry");
        }
        Path target = out.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(String.valueOf(target.getFileName()));
        }
        byte[] rand = new byte[8];
        RANDOM.nextBytes(rand);
        Path temp = target.resolveSibling(TEMP_PREFIX + HexFormat.of().formatHex(rand) + TEMP_SUFFIX);
        LIVE_TEMPS.add(temp);
        boolean moved = false;
        try {
            try (OutputStream file = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(file, 1 << 16))) {
                zip.setMethod(ZipOutputStream.DEFLATED);
                zip.setLevel(Deflater.DEFAULT_COMPRESSION);
                for (Entry e : sorted) {
                    ZipEntry ze = new ZipEntry(e.name());
                    ze.setTimeLocal(KitPackRules.ENTRY_TIME);
                    zip.putNextEntry(ze);
                    zip.write(e.bytes());
                    zip.closeEntry();
                }
            }
            List<Entry> back = read(temp, ROUND_TRIP_CAPS);
            if (back.size() != sorted.size()) {
                throw new IOException("kit zip round trip: the entry count differs");
            }
            for (int i = 0; i < back.size(); i++) {
                if (!back.get(i).name().equals(sorted.get(i).name())
                        || !Arrays.equals(back.get(i).bytes(), sorted.get(i).bytes())) {
                    throw new IOException("kit zip round trip: an entry differs");
                }
            }
            if (!commitOk.getAsBoolean()) {
                throw new Abandoned();
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(String.valueOf(target.getFileName()));
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException | RuntimeException leak) {
                    QUIET_FAILURES.incrementAndGet();
                }
            }
            LIVE_TEMPS.remove(temp);
        }
    }

    /** True iff {@code fileName} is a temp name {@link #write} mints. */
    static boolean isTempName(String fileName) {
        return fileName != null && fileName.startsWith(TEMP_PREFIX) && fileName.endsWith(TEMP_SUFFIX);
    }

    /** The entry-name grammar (a file name, or a directory name without its trailing {@code /}). */
    static boolean nameOk(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return PackageManifest.safeRelative(name);
    }

    /** Refuses a FILE name that is also a parent folder of another entry ({@code a} beside {@code a/b}). */
    private static void requireNoParentCollision(List<Entry> files) throws Refused {
        Set<String> parents = new HashSet<>();
        for (Entry f : files) {
            String n = f.name().toLowerCase(Locale.ROOT);
            for (int at = n.indexOf('/'); at >= 0; at = n.indexOf('/', at + 1)) {
                parents.add(n.substring(0, at));
            }
        }
        for (Entry f : files) {
            if (parents.contains(f.name().toLowerCase(Locale.ROOT))) {
                throw new Refused(Kind.DUPLICATE, f.name());
            }
        }
    }

    private static long crc(byte[] bytes) {
        CRC32 c = new CRC32();
        c.update(bytes);
        return c.getValue();
    }
}
