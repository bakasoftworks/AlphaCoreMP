package cc.starsector.server.ops;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The released server package zip read as the BASE of a source-built server folder:
 * a source host builds only {@code classes/} and the premain;
 * the JRE, Mesa, natives, the kit's mod, {@code serve.argv}, the scripts and the README come from
 * this zip. WHY every check here: the base is a DOWNLOAD, so it is data until proven —
 * <ul>
 * <li>it must be a server package: a {@code MANIFEST.txt} entry that {@link PackageManifest#parse}s
 * with a non-blank {@code pack.stamp} (else R12);</li>
 * <li>every entry name passes the manifest path law, none repeats (zip-slip; else R13);</li>
 * <li>Size caps: at most {@value #MAX_ENTRIES} entries, each at most
 * {@value #MAX_ENTRY_BYTES} B, all together at most {@value #MAX_TOTAL_BYTES} B — the declared sizes
 * at {@link #open}, then RE-COUNTED while inflating on every {@link #read} / {@link #copyTo}, since a
 * header's declared size can lie (R13);</li>
 * <li>a LISTED entry must hash to the base's OWN manifest row on every read and copy — the trust
 * anchor of a source-built folder: a damaged or partly edited download is refused, never re-accepted by the new
 * folder's manifest (R13).</li>
 * </ul>
 * Refusals are {@link IllegalArgumentException}s carrying the writer's R12 / R13 texts without its
 * prefix (zip path and entry name masked, never a value). {@code MANIFEST.txt} itself is never
 * copied: the folder's own manifest is written by the folder writer.
 *
 *
 */
public final class PackageBaseZip implements AutoCloseable {

    /** The base's own integrity list entry. */
    static final String MANIFEST_ENTRY = "MANIFEST.txt";
    /** The meta row that marks a released package (non-blank). */
    static final String PACK_STAMP = "pack.stamp";
    /** Size cap: entries in one base zip. */
    static final int MAX_ENTRIES = 20_000;
    /** Size cap: one entry, inflated. */
    static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;
    /** Size cap: every entry together, inflated. */
    static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;

    private final Path zipPath;
    private final ZipFile zip;
    private final Map<String, ZipEntry> entries;
    private final List<String> names;
    private final long entryCap;
    private final long totalCap;
    /** Set once by {@link #open}, before the instance is returned. */
    private PackageManifest manifest;
    /** Inflated bytes counted per entry name (a re-read of the same entry is not counted twice). */
    private final Map<String, Long> inflatedByName = new HashMap<>();
    private long inflatedTotal;

    private PackageBaseZip(Path zipPath, ZipFile zip, Map<String, ZipEntry> entries, List<String> names,
            long entryCap, long totalCap) {
        this.zipPath = zipPath;
        this.zip = zip;
        this.entries = entries;
        this.names = names;
        this.entryCap = entryCap;
        this.totalCap = totalCap;
    }

    /**
     * Opens {@code zip} as a base: {@link IOException} when it is not a readable zip; R13 on too many
     * entries, a declared size over a cap, a non-safe or repeated name (directories too); R12 when
     * there is no {@code MANIFEST.txt} entry, it does not parse, or its {@code pack.stamp} is blank.
     */
    public static PackageBaseZip open(Path zip) throws IOException {
        return open(zip, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES);
    }

    /**
     * {@link #open(Path)} with the per-entry and total inflate caps passed in: the selftest drives
     * the count-while-inflating legs (a lying header, the running total) with kilobytes instead of
     * the size caps' quarter- and full gibibyte. Every product caller uses {@link #open(Path)}.
     */
    static PackageBaseZip open(Path zip, long entryCap, long totalCap) throws IOException {
        ZipFile zf = new ZipFile(zip.toFile());
        boolean opened = false;
        try {
            if (zf.size() > MAX_ENTRIES) {
                throw PackageKitZip.r13(zip, "#" + (MAX_ENTRIES + 1) + " (over " + MAX_ENTRIES + " entries)");
            }
            Map<String, ZipEntry> entries = new LinkedHashMap<>();
            List<String> names = new ArrayList<>();
            long declaredTotal = 0;
            Enumeration<? extends ZipEntry> all = zf.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                String name = e.getName();
                String bare = e.isDirectory() ? name.substring(0, name.length() - 1) : name;
                if (!PackageManifest.safeRelative(bare) || entries.containsKey(name)) {
                    throw PackageKitZip.r13(zip, name);
                }
                long declared = Math.max(0, e.getSize());
                declaredTotal += declared;
                if (declared > entryCap || declaredTotal > totalCap) {
                    throw PackageKitZip.r13(zip, name);
                }
                entries.put(name, e);
                if (!e.isDirectory()) {
                    names.add(name);
                }
            }
            ZipEntry m = entries.get(MANIFEST_ENTRY);
            if (m == null || m.isDirectory()) {
                throw r12(zip);
            }
            PackageBaseZip base = new PackageBaseZip(zip, zf, entries, List.copyOf(names), entryCap, totalCap);
            try {
                base.manifest = PackageManifest.parse(base.inflate(MANIFEST_ENTRY, m, PackageManifest.MAX_MANIFEST_BYTES + 1L));
            } catch (IllegalArgumentException malformedOrOverCap) {
                // the R12 line is this failure's record (a manifest that does not parse = not a server package)
                throw r12(zip);
            }
            String stamp = base.manifest.meta.get(PACK_STAMP);
            if (stamp == null || stamp.isBlank()) {
                throw r12(zip);
            }
            opened = true;
            return base;
        } finally {
            if (!opened) {
                zf.close();
            }
        }
    }

    /** The base's own manifest (its meta rows: {@code git.head}, {@code pack.stamp}, ...). */
    public PackageManifest manifest() {
        return manifest;
    }

    /** Every non-directory entry name, zip order, each a safe relative path ({@code MANIFEST.txt} included). */
    public List<String> names() {
        return names;
    }

    /**
     * One entry's bytes: {@code null} when the base holds no such file; R13 when it inflates past
     * a cap or — a LISTED entry — its sha256 is not the base's own manifest row.
     */
    public byte[] read(String name) throws IOException {
        ZipEntry e = name == null ? null : entries.get(name);
        if (e == null || e.isDirectory()) {
            return null;
        }
        MessageDigest sha = sha256();
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(0, Math.min(e.getSize(), 1 << 20)));
        inflateTo(name, e, out, sha, entryCap);
        if (!ownRowMatches(name, sha.digest())) {
            throw PackageKitZip.r13(zipPath, name);
        }
        return out.toByteArray();
    }

    /**
     * Streams one entry to {@code target} ({@code CREATE_NEW}: an existing file is never
     * overwritten — {@link java.nio.file.FileAlreadyExistsException}; parent directories created),
     * hashing while inflating. R13 as {@link #read}; on ANY failure after the target was created the
     * partial target is deleted, so a refused entry never leaves bytes behind. R10 text when the
     * base holds no such file; {@code MANIFEST.txt} is refused (never copied by this class).
     */
    public void copyTo(String name, Path target) throws IOException {
        if (MANIFEST_ENTRY.equals(name)) {
            throw new IllegalArgumentException("base " + MANIFEST_ENTRY + " is read as manifest(), never copied");
        }
        ZipEntry e = name == null ? null : entries.get(name);
        if (e == null || e.isDirectory()) {
            throw new IllegalArgumentException("REFUSED base entry " + PackageManifest.mask(name) + " not found: "
                    + PackageManifest.mask(zipPath.toString()));
        }
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        MessageDigest sha = sha256();
        // CREATE_NEW throws before the target is ours, so the delete below only ever removes our own partial copy
        OutputStream os = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        boolean kept = false;
        try {
            try (os) {
                inflateTo(name, e, os, sha, entryCap);
            }
            byte[] copied = sha.digest();
            if (!ownRowMatches(name, copied)) {
                throw PackageKitZip.r13(zipPath, name);
            }
            kept = true;
        } finally {
            if (!kept) {
                Files.deleteIfExists(target);
            }
        }
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }

    /** True when {@code name} is unlisted (the writer routes it) or its sha256 equals its own row. */
    private boolean ownRowMatches(String name, byte[] sha256) {
        String row = manifest.sha256ByPath.get(name);
        return row == null || row.equals(HexFormat.of().formatHex(sha256));
    }

    private byte[] inflate(String name, ZipEntry e, long cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        inflateTo(name, e, out, null, cap);
        return out.toByteArray();
    }

    /**
     * Inflates one entry into {@code out}, counting every byte against {@code cap} and the base's
     * running total (each name counted once, so a re-read never trips the total by itself).
     */
    private void inflateTo(String name, ZipEntry e, OutputStream out, MessageDigest sha, long cap) throws IOException {
        if (e.getSize() > cap) {
            throw PackageKitZip.r13(zipPath, name);
        }
        long others = inflatedTotal - inflatedByName.getOrDefault(name, 0L);
        long count = 0;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = zip.getInputStream(e)) {
            for (int n; (n = in.read(buf)) != -1; ) {
                count += n;
                if (count > cap || others + count > totalCap) {
                    throw PackageKitZip.r13(zipPath, name);
                }
                if (sha != null) {
                    sha.update(buf, 0, n);
                }
                out.write(buf, 0, n);
            }
        }
        inflatedByName.put(name, count);
        inflatedTotal = others + count;
    }

    private static IllegalArgumentException r12(Path zip) {
        return new IllegalArgumentException("REFUSED " + PackageManifest.mask(zip.toString())
                + " is not a server package - pass the cc-server zip released with this source");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every Java runtime ships SHA-256", impossible);
        }
    }
}
