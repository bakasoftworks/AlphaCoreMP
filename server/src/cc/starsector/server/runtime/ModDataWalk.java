package cc.starsector.server.runtime;

import cc.starsector.server.match.ContentFingerprints;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.spi.FileSystemProvider;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The mod-set pin's DATA half: the per-mod {@code data/**} digest BOTH trees
 * mint for every {@link ModSet.Record} — so a same-id, same-jars copy whose CSV/JSON/.ship/.variant
 * bytes differ (an edited stat, a partially updated folder) folds a different {@code modset} and is
 * refused at the door like a jar skew (the mod-set decision's named residue, paid here).
 *
 * <p><b>The lane, as measured:</b>
 * the in-game script classloader B denies
 * {@code java.io.*} minus an allow list and {@code java.nio.file.File*} by PREFIX
 * — so {@code java.io.File}, {@code Files},
 * {@code FileSystem(s)}, {@code FileVisitor} are walled, but {@link FileSystemProvider}
 * ({@code java.nio.file.spi}), {@link Path}/{@link Paths}, {@link DirectoryStream},
 * {@link BasicFileAttributes} ({@code java.nio.file.attribute}), {@link LinkOption} and
 * {@link OpenOption} are NOT matched by name, and a B-parented subject walking the vanilla
 * {@code starsector-core/data} tree (1401 files, 8.1 MB, 79 ms) produced the unrestricted main's
 * md5 exactly. ONE class both trees ({@code ModSet} idiom): the server calls it from
 * {@code RealEngineModSetFacts}, the in-game client from {@code ClientModSetBoot}, the game-free
 * join page from {@code JoinModStage}. Nothing here may name {@code java.io.File},
 * {@code java.nio.file.Files}, {@code java.nio.file.FileSystem}, {@code java.nio.file.FileSystems}
 * or any {@code java.nio.file.FileSystemException} subclass (a catch type resolves DURING dispatch);
 * catch types stay {@link IOException} / {@link RuntimeException}.
 *
 * <p><b>Recipe</b> = {@link ContentFingerprints#dataMd5} streamed: every REGULAR file under
 * {@code <modRoot>/data} except OS junk ({@link #isOsJunk}: {@code .DS_Store}, {@code ._*},
 * {@code Thumbs.db}, {@code desktop.ini}, {@code __MACOSX/}; a stranger's zipped copy folds the
 * host's digest), with
 * symlinks/junctions followed (the kit stages junctions), keyed by its
 * {@code /}-separated MOD-ROOT-relative path ({@code data/...}) in ordinal order; digest input per
 * file = UTF-8 path bytes + {@code 0x00} + file bytes. No {@code data} directory = md5 of nothing
 * (a jars-only mod). FAIL-CLOSED: a root that is not a directory, more than {@value #MAX_FILES}
 * files or a directory nest deeper than {@value #MAX_DEPTH} (a junction cycle) THROW.
 */
public final class ModDataWalk {

    /** The one subtree the digest covers (the engine-loaded content surface; graphics/sounds are presentation). */
    public static final String DATA_DIR = "data";
    /** Sanity cap on files per mod (the largest real mods ship a few thousand). */
    public static final int MAX_FILES = 100_000;
    /** Sanity cap on directory nesting (a junction cycle would otherwise loop forever). */
    public static final int MAX_DEPTH = 64;

    private static final LinkOption[] FOLLOW_LINKS = new LinkOption[0];
    private static final OpenOption[] READ_DEFAULT = new OpenOption[0];

    private ModDataWalk() {
    }

    /**
     * True = an OS/archiver sidecar name that is never engine-loaded content, so the walk skips it
     * (a file, or a whole directory for {@code __MACOSX}). WHY: an honest stranger's
     * macOS- or Windows-zipped copy of the host's mod must fold the SAME digest as the host's copy;
     * before this, a {@code .DS_Store} under {@code data/} was a false DATA MISMATCH at the door.
     * The list, matched case-INSENSITIVELY ({@link Locale#ROOT}): {@code .DS_Store} (Finder),
     * {@code Thumbs.db} + {@code desktop.ini} (Explorer), {@code __MACOSX} (the Archive Utility
     * sidecar directory) and any name starting {@code ._} (AppleDouble). Null/empty = false.
     *
     *
     */
    public static boolean isOsJunk(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.equals(".ds_store") || n.equals("thumbs.db") || n.equals("desktop.ini")
                || n.equals("__macosx") || n.startsWith("._");
    }

    /**
     * Accepts every directory entry except OS junk ({@link #isOsJunk}) — a junk-named directory
     * ({@code __MACOSX}) is skipped whole. A named class, not a lambda — no invokedynamic under B.
     *
     *
     */
    static final class ContentEntries implements DirectoryStream.Filter<Path> {
        @Override
        public boolean accept(Path entry) {
            Path name = entry.getFileName();
            return !(name != null && isOsJunk(name.toString()));
        }
    }

    /** The one walk filter. */
    private static final DirectoryStream.Filter<Path> CONTENT_ENTRIES = new ContentEntries();

    /** The {@code file} scheme provider (the default filesystem's, reached without naming {@code FileSystems}). */
    static FileSystemProvider fileProvider() {
        for (FileSystemProvider p : FileSystemProvider.installedProviders()) {
            if ("file".equalsIgnoreCase(p.getScheme())) {
                return p;
            }
        }
        throw new IllegalStateException("mod-data: no 'file' FileSystemProvider installed");
    }

    /**
     * The mod's {@code data/**} digest (32 lowercase hex). {@code modRoot} = the launcher's
     * {@code ModSpec.getPath()} (a junction is fine). THROWS {@link IOException} on an unreadable
     * file (the caller's posture: server = boot fault, client = vtab-only fold, join page = an
     * "unreadable" row) and {@link IllegalArgumentException} on a non-directory root or a cap breach.
     */
    public static String dataMd5(String modRoot) throws IOException {
        if (modRoot == null || modRoot.isEmpty()) {
            throw new IllegalArgumentException("mod-data: null/empty mod root");
        }
        FileSystemProvider fs = fileProvider();
        Path root = Paths.get(modRoot);
        if (!isDirectory(fs, root)) {
            throw new IllegalArgumentException("mod-data: mod root is not a directory: " + modRoot);
        }
        Path data = root.resolve(DATA_DIR);
        TreeMap<String, Path> files = new TreeMap<String, Path>();
        if (isDirectory(fs, data)) {
            collect(fs, root, data, 1, files);
        }
        MessageDigest md5 = ContentFingerprints.newMd5();
        byte[] buf = new byte[16 * 1024];
        for (Map.Entry<String, Path> e : files.entrySet()) {
            md5.update(e.getKey().getBytes(StandardCharsets.UTF_8));
            md5.update((byte) 0);
            InputStream in = fs.newInputStream(e.getValue(), READ_DEFAULT);
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    md5.update(buf, 0, n);
                }
            } finally {
                in.close();
            }
        }
        return ContentFingerprints.hex(md5.digest());
    }

    private static boolean isDirectory(FileSystemProvider fs, Path p) {
        try {
            return fs.readAttributes(p, BasicFileAttributes.class, FOLLOW_LINKS).isDirectory();
        } catch (IOException absent) {
            return false;
        }
    }

    /**
     * Walk entries skipped as a DANGLING link/junction since boot — a stranger's mod folder with a
     * broken link used to throw out of {@link #dataMd5} (boot fault / door refuse). Every skip
     * prints one {@code mod-data: DANGLING-LINK skipped} line, numbered ({@code #n}); this count is
     * the meter behind it.
     *
     *
     */
    public static int danglingSkipped() {
        return DANGLING_SKIPPED.get();
    }

    /** Meter behind {@link #danglingSkipped}. */
    private static final AtomicInteger DANGLING_SKIPPED = new AtomicInteger();

    /**
     * The entry's attributes following links, or {@code null} when the follow read fails AND the
     * entry itself is a link/reparse point (a dangling symlink or junction: not content). Any other
     * failure rethrows the ORIGINAL exception. The catch type stays {@link IOException} — a
     * {@code NoSuchFileException} catch would name a B-denied {@code java.nio.file.File*} class.
     *
     *
     */
    private static BasicFileAttributes readFollowingOrNullIfDangling(FileSystemProvider fs, Path p)
            throws IOException {
        try {
            return fs.readAttributes(p, BasicFileAttributes.class, FOLLOW_LINKS);
        } catch (IOException followFailed) {
            BasicFileAttributes self;
            try {
                self = fs.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException gone) {
                throw followFailed; // the entry itself is unreadable/vanished: the caller's fail-closed posture
            }
            if (!(self.isSymbolicLink() || self.isOther())) {
                throw followFailed;
            }
            // review F3: one line PER skip (boot-time, bounded by the local directory listing —
            // never peer input) so the meter is exported where it counts; #n is the running count.
            System.out.println("mod-data: DANGLING-LINK skipped (not content) #" + DANGLING_SKIPPED.incrementAndGet()
                    + " " + p + ": " + followFailed.getClass().getSimpleName());
            return null;
        }
    }

    /** Depth-first over {@code dir}; regular files keyed by their {@code /}-separated root-relative path. */
    private static void collect(FileSystemProvider fs, Path root, Path dir, int depth, TreeMap<String, Path> out)
            throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("mod-data: directory nest deeper than " + MAX_DEPTH + " under "
                    + root + " (a junction cycle?)");
        }
        List<Path> subdirs = new ArrayList<Path>();
        DirectoryStream<Path> ds = fs.newDirectoryStream(dir, CONTENT_ENTRIES);
        try {
            for (Path p : ds) {
                BasicFileAttributes a = readFollowingOrNullIfDangling(fs, p); //
                if (a == null) {
                    continue; // a dangling link is not content (counted + one numbered line per skip in the helper)
                }
                if (a.isDirectory()) {
                    subdirs.add(p);
                } else if (a.isRegularFile()) {
                    if (out.size() >= MAX_FILES) {
                        throw new IllegalArgumentException("mod-data: more than " + MAX_FILES + " files under "
                                + root + "/" + DATA_DIR);
                    }
                    out.put(root.relativize(p).toString().replace('\\', '/'), p);
                }
                // anything else (a device, a socket) is not content
            }
        } finally {
            ds.close();
        }
        for (Path sub : subdirs) {
            collect(fs, root, sub, depth + 1, out);
        }
    }
}
