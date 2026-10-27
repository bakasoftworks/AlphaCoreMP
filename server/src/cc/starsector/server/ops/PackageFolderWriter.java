package cc.starsector.server.ops;

import cc.starsector.server.match.ContentFingerprintMint;
import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.MatchShapes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The ONE writer of a server folder and its {@code MANIFEST.txt}:
 * the route table (which file goes where, from which input, listed in the manifest or not, and the
 * SEED law), the kit parity and the manifest grammar, for three layouts. WHY one writer: the owner
 * stage, the release packer
 * and a source host's build each laid the folder out their own way, so a folder a source host built
 * could carry a kit that turns every player away or a manifest the launcher refuses; every layout now
 * runs the same checks and the same post-write self-verify (G2).
 *
 * <p><b>Kinds and the inputs each reads</b> (every other {@link Inputs} component is ignored):
 * <table>
 * <caption>inputs per kind</caption>
 * <tr><th>kind</th><th>reads</th></tr>
 * <tr><td>{@link Kind#OWNER_STAGE}</td><td>{@code pkg}; {@code classesDir} + {@code clientModDir} = the
 * stage's link TARGETS ({@code <pkg>/classes} and {@code <pkg>/serve-mods/CC_Client} must be the same
 * files, {@link Files#isSameFile}); {@code premainJar}; {@code holderJar}; {@code c2File}; {@code gitHead} (7-40 hex,
 * else {@code unknown}); {@code kitJarMd5} (32 hex, kept as passed). No parity, no {@code pack.stamp},
 * no {@code stage.lock} (the console that staged holds it), no SEED; {@code serve.argv} is hashed in
 * place; the manifest lists today's row set: {@code serve.argv}, the premain, the RNG holder jar, the C2 file,
 * every file under the {@code classes} and {@code serve-mods} link targets (inner links skipped).</td></tr>
 * <tr><td>{@link Kind#PACKED}</td><td>everything but {@code baseZip}: {@code classesDir},
 * {@code premainJar}, {@code holderJar}, {@code c2File}, {@code clientModDir} ({@code mod_info.json}, {@code data/**},
 * {@code LICENSE.txt}), {@code kitZip} (its jar and sidecar, and the zip itself, listed, at
 * {@code kits/<its file name>} = {@code pack.kit}), {@code matchFile} (REQUIRED: the standing match file +
 * its {@code .fingerprint} beside it, LISTED as {@code match-defaults/match.txt} + its sidecar; a kit carries no
 * match file), {@code argvFile} (its folder also
 * holds the minted {@code launcher.template.properties} and the composed {@code THIRD-PARTY-NOTICES.txt}),
 * {@code jreDir}, {@code mesaDir} (optional, Windows: the Mesa pair replaces the image's own),
 * {@code nativeDir} (Linux), {@code hostFilesDir}, {@code platform}, {@code gitHead} (exactly 40 hex),
 * {@code stamp}. The folder must be empty. A PACKED folder holds NO live-named host file (no
 * {@code cc-server.properties}, {@code launcher.properties}, {@code serve-mods/enabled_mods.json} or {@code match/}):
 * every file it writes is a manifest row, the templates, scripts and README included; the first start creates the
 * live files from the listed templates.</td></tr>
 * <tr><td>{@link Kind#SOURCE}</td><td>{@code pkg}, {@code classesDir}, {@code premainJar}, {@code holderJar}, {@code baseZip}
 * (the released server package: everything else comes from it), {@code c2File} (optional: else the
 * base's), {@code kitZip} (optional: given, it ships listed at {@code kits/<its file name>} and {@code pack.kit}
 * names it; else the base's jar + {@code match/*} + the base's own {@code kits/<pack.kit>}, listed, and
 * {@code pack.kit} is the base's), {@code matchFile} (optional: given, the folder's standing pair; else the
 * base's own: its {@code match/match.txt} pair, or, from a base that lists every file, its
 * {@code match-defaults/match.txt} pair). The folder always seeds the LIVE names ({@link #SEED_RELS}).
 * {@code git.head} and {@code pack.jre} are the base's. The folder must be
 * empty or one this tool made (its {@code MANIFEST.txt} / {@code MANIFEST.failed} carries a {@code pack.stamp}); a re-run keeps every
 * SEED file and deletes only what the previous manifest listed in the writer's own trees.</td></tr>
 * </table>
 *
 * <p><b>CLI</b> ({@link #main}; the caller's child JVM): {@code --kind owner-stage|packed|source
 * --pkg <dir> [--base <zip>] [--kit <zip>] [--classes <dir>] [--premain <jar>] [--holder <jar>] [--c2 <file>]
 * [--client-mod <dir>] [--argv <file>] [--jre <dir>] [--mesa <dir>] [--native <dir>]
 * [--host-files <dir>] [--platform windows|linux] [--git-head <hex>] [--kit-jar-md5 <hex>]
 * [--stamp <s>] [--match <file>] [--shapes <dir> [--mod-variants <dir>]] [--produced]} ({@code --shapes}, PACKED only: the
 * shipped shape set, {@link Writer#writeShapes}). Exit 0 = the K
 * lines, the OK line, then (with {@code --produced}) one
 * {@code <64 hex>  <path>} line per manifest file row; exit 2 = REFUSED lines, nothing written (an
 * unknown flag, a repeated flag or a missing value too, one line); exit 1 = an I/O failure after the
 * first write (the R14 line with its EF tail). Callers:
 * the owner stage, the serve-template mint's {@code -Stage}
 * ({@code --kind owner-stage}, after it re-points its junctions), the packer
 * ({@code --kind packed --produced}, after its mint and notices steps), and a source host's
 * {@code build.ps1 -ServerFolder <dir> -Package <cc-server zip>} ({@code --kind source --base <zip>}).
 *
 * <p><b>Security</b>: S1 writes only routed paths under {@code pkg} ({@link Writer#target};
 * {@code --shapes} adds its LISTED {@code match-defaults/} pairs beside the routed {@code match-defaults/match.txt}),
 * a SEED file is never overwritten ({@link Writer#writeSeed}); S2 deletes = {@link Planner#deletesPhase()}
 * only; S3 sidecar {@code file=} rows and zip entry names are DATA, grammar-checked before use
 * ({@link PackageKitZip}, {@link PackageBaseZip}, {@link Planner#baseNamesCheck()},
 * {@link Planner#grammarPhase()}); S4 a copied match
 * file is STRICTLY redacted ({@link PackageKitZip#standingParity}, {@link Planner#standingChecks}) and an input named like a password or token
 * file refuses, never opened ({@link Planner#secretName}); refusal lines name files and hashes, never a value read
 * from a match, properties or password file; S5 this class starts no process and opens no socket.
 * Measured on a 1614-file PACKED fixture (plan + write + G2): 10.2 s in a cold
 * JVM, 6.4 s warm, against the stage's 180 s ceiling ({@link ServerOpsLaunch#STAGE_CEILING_S}).
 *
 *
 */
public final class PackageFolderWriter {

    /** The three layouts. */
    public enum Kind { OWNER_STAGE, PACKED, SOURCE }

    /** The host platform a PACKED folder is laid out for (a SOURCE folder takes its base's). */
    public enum Platform { WINDOWS, LINUX }

    /** Where a routed file's bytes come from. */
    public enum From {
        /** Already in the folder ({@code serve.argv} of an owner stage): hashed, never written. */
        IN_PLACE,
        /** Under an owner stage's link target: hashed through the target, never written. */
        LINKED,
        /** A file of an input tree, copied. */
        FILE,
        /** Bytes the plan holds (a kit entry, the rewritten JRE stamp, the minted enabled_mods.json). */
        BYTES,
        /** An entry of the SOURCE base zip, copied through {@link PackageBaseZip#copyTo}. */
        BASE
    }

    /**
     * The inputs; nullable per {@link Kind} (the class javadoc's table says which kind reads which).
     * {@code produced} = the CLI's {@code --produced}: {@link #main} prints the manifest's file rows
     * after the OK line.
     */
    public record Inputs(Kind kind, Path pkg, Path classesDir, Path premainJar, Path holderJar, Path c2File,
            Path clientModDir, Path kitZip, Path baseZip, Path argvFile, Path jreDir, Path mesaDir, Path nativeDir,
            Path hostFilesDir, Platform platform, String gitHead, String kitJarMd5, String stamp, boolean produced,
            Path shapesDir, Path modVariantsDir, Path matchFile) {

        /** The inputs without a standing match input ({@code --match} absent).
         * */
        public Inputs(Kind kind, Path pkg, Path classesDir, Path premainJar, Path holderJar, Path c2File,
                Path clientModDir, Path kitZip, Path baseZip, Path argvFile, Path jreDir, Path mesaDir, Path nativeDir,
                Path hostFilesDir, Platform platform, String gitHead, String kitJarMd5, String stamp, boolean produced,
                Path shapesDir, Path modVariantsDir) {
            this(kind, pkg, classesDir, premainJar, holderJar, c2File, clientModDir, kitZip, baseZip, argvFile, jreDir,
                    mesaDir, nativeDir, hostFilesDir, platform, gitHead, kitJarMd5, stamp, produced, shapesDir,
                    modVariantsDir, null);
        }

        /** The inputs without a shipped shape set ({@code --shapes} / {@code --mod-variants} absent): a run that
         *  is byte-identical to the writer's output before shipped shapes were added. */
        public Inputs(Kind kind, Path pkg, Path classesDir, Path premainJar, Path holderJar, Path c2File,
                Path clientModDir, Path kitZip, Path baseZip, Path argvFile, Path jreDir, Path mesaDir, Path nativeDir,
                Path hostFilesDir, Platform platform, String gitHead, String kitJarMd5, String stamp, boolean produced) {
            this(kind, pkg, classesDir, premainJar, holderJar, c2File, clientModDir, kitZip, baseZip, argvFile, jreDir,
                    mesaDir, nativeDir, hostFilesDir, platform, gitHead, kitJarMd5, stamp, produced, null, null, null);
        }
    }

    /** The standing match file's sidecar: {@code <match>.fingerprint} beside it ({@code null} for {@code null}).
     * */
    static Path sidecarOf(Path match) {
        return match == null || match.getFileName() == null ? null
                : match.resolveSibling(match.getFileName() + MatchShapes.SIDECAR_SUFFIX);
    }

    /**
     * One routed file's origin: {@code file} for {@link From#IN_PLACE} / {@link From#LINKED} /
     * {@link From#FILE}, {@code baseEntry} for {@link From#BASE}, {@code bytes} for {@link From#BYTES}
     * (shared, never copied: the kit jar is up to 64 MiB; nothing mutates it).
     */
    public record Source(From from, Path file, String baseEntry, byte[] bytes) {
        static Source inPlace(Path file) {
            return new Source(From.IN_PLACE, file, null, null);
        }

        static Source linked(Path file) {
            return new Source(From.LINKED, file, null, null);
        }

        static Source file(Path file) {
            return new Source(From.FILE, file, null, null);
        }

        static Source bytes(byte[] bytes) {
            return new Source(From.BYTES, null, null, bytes);
        }

        static Source base(String entry) {
            return new Source(From.BASE, null, entry, null);
        }
    }

    /**
     * One row of the route table: the folder-relative path, its origin, whether {@code MANIFEST.txt}
     * lists it, and whether it is a SEED (written only when absent: a host file).
     */
    public record Route(String rel, Source source, boolean listed, boolean seed) {
    }

    /** A finished write: the OK line, the manifest's file-row count, the test and tool classes left out. */
    public record Result(String okLine, int files, int skipped, Path manifest) {
    }

    // ---- numbers ----

    /** The manifest's own cap ({@link PackageManifest#render} refuses past it = R11). */
    static final int MAX_MANIFEST_BYTES = PackageManifest.MAX_MANIFEST_BYTES;
    /** A PACKED {@code pack.stamp} = 1-32 of {@code [0-9A-Za-z-]}. */
    static final Pattern STAMP = Pattern.compile("^[0-9A-Za-z-]{1,32}$");
    /** {@code git.head} = 7-40 hex or {@code unknown}; a PACKED head is exactly 40 hex. */
    static final Pattern GIT_HEAD = Pattern.compile("^[0-9a-f]{7,40}$");
    /** A PACKED head: exactly 40 hex. */
    static final Pattern GIT_HEAD_FULL = Pattern.compile("^[0-9a-f]{40}$");
    /** At most this many refusal lines; the rest are summarized in the last one. */
    static final int MAX_REFUSAL_LINES = 20;
    /** A host file (SEED) or a JRE stamp read whole: no such file is near this. */
    static final int MAX_HOST_FILE_BYTES = 8 * 1024 * 1024;
    /** The sidecar cap: a kept sidecar re-read for {@code kit.fingerprint}. */
    static final int MAX_SIDECAR_BYTES = 1024 * 1024;
    /** {@code --mod-variants}: at most 20000 regular files walked for {@code *.variant} stems (the mod ships ~400);
     *  past it the plan refuses. */
    static final int MAX_MOD_VARIANT_FILES = 20000;

    // ---- the route table's names ----

    static final String MANIFEST_REL = ServeLauncher.MANIFEST_FILE;
    /** A failed run's manifest: the previous file set for the next run's deletes AND the made-by-this-tool marker. */
    static final String FAILED_REL = "MANIFEST.failed";
    static final String ARGV_REL = "serve.argv";
    static final String PREMAIN_REL = "cc-premain.jar";
    /**
     * The RNG pin's bootstrap holder jar, routed BESIDE {@link #PREMAIN_REL} on every kind. WHY: when the keyed
     * {@code java.util.Random} pin arms, the premain appends this jar to the bootstrap class-loader search from the
     * directory of its OWN jar, by this exact name, and refuses the launch when it is missing; with the pins on by
     * default a folder without it would abort every start at agent load. Same-build law: the holder is packed from
     * the same compile as the {@code classes} it ships beside, so a caller passes it from the SAME build output as
     * {@code --premain} (a holder from another build fails to link at agent load).
     *
     */
    static final String HOLDER_REL = "cc-rngpin-holder.jar";
    static final String C2_REL = "jvm/c2-directives.json";
    static final String MOD_PREFIX = "serve-mods/CC_Client/";
    static final String MOD_INFO_REL = MOD_PREFIX + "mod_info.json";
    static final String DATA_PREFIX = MOD_PREFIX + "data/";
    static final String JAR_REL = MOD_PREFIX + "jars/cc-client-mod.jar";
    static final String ENABLED_REL = "serve-mods/enabled_mods.json";
    static final String MATCH_REL = "match/match.txt";
    static final String SIDECAR_REL = "match/match.txt.fingerprint";
    /**
     * The release kit's folder: the ONE folder a package's joiner kit is read from ({@link KitPacker#kitsDir}), and
     * the shipped zip keeps the exact name MANIFEST {@code pack.kit} gives it ({@link KitPacker#baseKitPath} resolves
     * only that name, directly in this folder). Never one of {@link #OWN_TREES}: host-made kits live here too
     * ({@code cc-joiner-kit-<stamp>-<slug>.zip}; the release kit's name has no slug), so a SOURCE re-run must
     * never delete them.
     *
     */
    static final String KITS_PREFIX = "kits/";
    /** The LIVE shape names a SOURCE folder seeds: {@code match/match-<name>.txt} + its {@code .fingerprint}
     *  (unlisted; a package from before the shipped defaults' folder carried its shapes here too;
     *  {@link Planner#baseShapeRoutes}). */
    static final String SHAPE_TXT_PREFIX = "match/" + MatchShapes.SHAPE_PREFIX;
    /**
     * The shipped DEFAULTS' folder: a PACKED folder's standing pair ({@link #DEFAULTS_MATCH_REL}) and its shape
     * library ({@link #DEFAULTS_SHAPE_PREFIX}) land here, LISTED. WHY not {@code match/}: the console offers every
     * {@code match/*.txt} and its Apply writes into the offered file, so a listed copy there would fail the
     * launcher's hash check after the host's first Apply; no reader of {@code match/} scans this sibling folder.
     * The first start copies each pair into {@code match/} when absent.
     */
    static final String DEFAULTS_PREFIX = "match-defaults/";
    static final String DEFAULTS_MATCH_REL = DEFAULTS_PREFIX + "match.txt";
    static final String DEFAULTS_SIDECAR_REL = DEFAULTS_MATCH_REL + MatchShapes.SIDECAR_SUFFIX;
    static final String DEFAULTS_SHAPE_PREFIX = DEFAULTS_PREFIX + MatchShapes.SHAPE_PREFIX;
    static final String PROPS_REL = "cc-server.properties";
    static final String PROPS_TEMPLATE = "cc-server.template.properties";
    static final String LAUNCHER_REL = "launcher.properties";
    /** The launcher settings' template (LISTED; the mint writes it beside {@link #LAUNCHER_REL}); the first start
     *  creates {@code launcher.properties} from it. */
    static final String LAUNCHER_TEMPLATE = "launcher.template.properties";
    static final String LICENSE_REL = "LICENSE.txt";
    static final String NOTICES_REL = "THIRD-PARTY-NOTICES.txt";
    static final String JRE_STAMP = "cc-serve-jre.txt";
    static final String NATIVE_PREFIX = "native/linux/";
    /** The classes route leaves this tree home (the export's allow-list). */
    static final String TOOLS_PREFIX = "cc/starsector/server/tools/";
    /** The minted mod list (the packer's {@code $PsEnabledMods}). */
    static final String ENABLED_MODS = "{\"enabledMods\": [\"cc_client\"]}";
    /** The Windows host scripts + README a PACKED folder LISTS from {@code hostFilesDir}: ONE console script,
     *  {@code ops-web.bat} (the browser console is the product's one console; each name here is a required host file,
     *  so a retired script left on this list would refuse every pack).
     *  The README is {@code README.md} (the vetted Markdown text). */
    static final List<String> HOST_WINDOWS = List.of("serve.bat", "stop.bat", "ops-web.bat", "update.bat", "README.md");
    /** The Linux host scripts + README ({@code README.md}); the console is {@code ops-web.sh}, as the packer's Linux
     *  routes carry it.
     *  {@code update.*} = the in-place update script. */
    static final List<String> HOST_LINUX = List.of("serve.sh", "stop.sh", "ops-web.sh", "update.sh", "README.md");
    /** Host scripts that must hold no CR byte (sh reads {@code set -eu<CR>} as a bad option). */
    static final Set<String> LF_ONLY = Set.of("serve.sh", "stop.sh", "ops-web.sh", "update.sh");
    /** Every SEED rel of every platform: the SOURCE kind's live names only (a PACKED folder seeds nothing; a SOURCE
     *  base decides its platform by what it holds). */
    static final Set<String> SEED_RELS = Set.of(PROPS_REL, LAUNCHER_REL, ENABLED_REL, MATCH_REL, SIDECAR_REL,
            "serve.bat", "stop.bat", "ops-web.bat", "serve.sh", "stop.sh", "ops-web.sh",
            // the base's root README; a base packed before the rename (README.txt) refuses R13: re-pack it.
            //
            "README.md");
    /** The listed single files a SOURCE base supplies (the two templates + the two update scripts ride a base that
     *  lists every file; an older base holds none of them). */
    static final Set<String> BASE_LISTED_SINGLES = Set.of(MOD_INFO_REL, ARGV_REL, LICENSE_REL, NOTICES_REL,
            NATIVE_PREFIX + "liblwjgl64.so", NATIVE_PREFIX + "libjawt.so", PROPS_TEMPLATE, LAUNCHER_TEMPLATE,
            "update.bat", "update.sh");
    /** The writer's OWN trees: the only places a SOURCE re-run deletes. */
    static final List<String> OWN_TREES = List.of("classes/", "jre/", DATA_PREFIX, MOD_PREFIX + "jars/", "native/");
    /** The Mesa pair a Windows serve JRE carries in {@code bin/}. */
    static final List<String> MESA_DLLS = List.of("opengl32.dll", "libgallium_wgl.dll");
    /** The two Linux natives and their sha256 pins ({@code package-server.ps1 $PsNativePins}). */
    static final Map<String, String> NATIVE_PINS = Collections.unmodifiableMap(new LinkedHashMap<>(Map.of(
            "liblwjgl64.so", "4622966cceee1c13df9e293fbae8fa402d1be84b3e1ade7256eca57892392f08",
            "libjawt.so", "26e8e5fee65c44182cf31ccb283296364e2ab0a0f8eea04c0c9a7c6e6c599ed8")));
    /** The manifest meta order ({@code package-server.ps1 $PsMetaOrder}). */
    static final List<String> META_ORDER = List.of(PackageManifest.GIT_HEAD, PackageManifest.KIT_JAR_MD5,
            "kit.fingerprint", OpsRoots.PACK_STAMP, "pack.kit", "pack.jre", PackageUpdateCheck.PROGRAM_SET_KEY);
    /** The JRE stamp rows a folder never ships (the box's JDK path, the link-time sum and clock). */
    private static final Pattern STAMP_DROP = Pattern.compile("^(source|bytes|minted)=");
    /** The portable path law ({@code package-server.ps1 $PsRowPathRx} + {@code $PsBadSegRx}). */
    private static final Pattern ROW_PATH = Pattern.compile("^[A-Za-z0-9._$+-]+(?:/[A-Za-z0-9._$+-]+)*$");
    private static final Pattern BAD_SEGMENT = Pattern.compile("[. ]$|^(?i:CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:\\.|$)");
    private static final Pattern SECRET_NAME = Pattern.compile("(?i)\\.password$|\\.admin|-tokens");
    private static final Pattern MD5 = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern MOD_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]*)\"");
    private static final DateTimeFormatter SOURCE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    // ---- texts (every printed line starts with PREFIX) ----

    static final String PREFIX = "package-folder: ";
    static final String OK = PREFIX + "wrote %s (%d files; %d test and tool classes left out) - start it with serve.bat"
            + " or ops-web.bat (Linux: sh serve.sh or sh ops-web.sh)";
    static final String K = PREFIX + "kept your %s - it differs from this build's copy; host files are never overwritten";
    static final String R1 = PREFIX + "REFUSED a server is running from %s - stop it (stop.bat, or Kill in the console),"
            + " then run this again; nothing was changed";
    static final String R2 = PREFIX + "REFUSED another build or Start is writing %s - wait for it, then run this again;"
            + " nothing was changed";
    static final String R3 = PREFIX + "REFUSED %s is not empty and was not made by this tool - pass an empty folder, or a"
            + " server folder this tool made";
    static final String R8 = PREFIX + "REFUSED %s/%s is a link - this tool never writes through a link; remove it, then"
            + " run this again";
    static final String R9 = PREFIX + "REFUSED %s cannot be listed (a space, a device name or an unusual character) -"
            + " rename it";
    static final String R10 = PREFIX + "REFUSED %s not found: %s";
    static final String R11 = PREFIX + "REFUSED the file list exceeds 8 MiB";
    static final String R12 = PREFIX + "REFUSED %s is not a server package - pass the cc-server zip released with this"
            + " source";
    static final String R14 = PREFIX + "REFUSED the folder does not match what was just written (%s: %s)";
    static final String EF = " - the folder was left without MANIFEST.txt, so it will not start until this command"
            + " finishes once";
    /**
     * A SOURCE re-run keeps the folder's {@code match/} sidecar (SEED) while placing another kit's jar:
     * {@code kit.jar.md5} would then disagree with the sidecar's {@code jar=} and the launcher refuses
     * {@code sidecar_stale} at Start, so the plan refuses first.
     */
    static final String R15 = PREFIX + "REFUSED match/ was made for another kit (its jar= %s, this build's jar %s) -"
            + " start from an empty folder, or remove match/ and run again";
    static final String OTHER_RELEASE = PREFIX + "REFUSED this folder was made from another release (%s vs the"
            + " package's %s) - start from an empty folder";
    /** The release packer's own refusals, carried over. */
    static final String NON_CLASS = PREFIX + "REFUSED %s holds %d non-.class file(s) (the classes route copies .class"
            + " files only): %s";
    static final String CR_BYTE = PREFIX + "REFUSED %s holds a CR byte at offset %d - a Linux host script must be LF"
            + " only (re-checkout server-package\\%s, or convert it)";
    static final String PIN = PREFIX + "REFUSED %s hashes to sha256 %s, not the pinned %s - another build of %s";
    static final String MOD_ID_TEXT = PREFIX + "REFUSED %s names mod id '%s', not cc_client (enabled_mods.json is"
            + " minted for cc_client)";
    /** The writer's own lines. */
    static final String SECRET = PREFIX + "REFUSED %s looks like a password or token file (*.password, *.admin*,"
            + " *-tokens*) - a server folder never copies one; move it out, then run this again";
    static final String UNREADABLE = PREFIX + "REFUSED %s cannot be read (%s)";
    /** A kit carries no match file, so a folder with no standing match pair to seed refuses: the one line.
     * */
    static final String NO_STANDING_MATCH = PREFIX + "REFUSED no standing match file (" + MATCH_REL + " + its"
            + " .fingerprint) to seed this folder - a kit carries none; give the released match file (--match, its"
            + " .fingerprint beside it), or lay the folder out from a server package that holds one";
    /** A standing pair whose sidecar is keyed to another match than its file: the door would expect a match id no
     *  player presents. Names the file only, never a value read from it. */
    static final String STANDING_SPLIT = PREFIX + "REFUSED %s and its .fingerprint name two different matches (the"
            + " fingerprint's match= is not the file's match.id) - every player would be turned away; use the match"
            + " file and the .fingerprint made with it";
    /** A SOURCE folder's standing sidecar named another build's content, so the folder's copy was re-keyed to the kit
     *  it serves (its match kept): the one note. Fixed words only - no path, no composite value.
     * */
    static final String REKEYED = PREFIX + "note: " + SIDECAR_REL + " was re-keyed to the kit this folder serves"
            + " (its jar and mod data), keeping its match - the standing copy named another build's content";
    static final String UNLISTABLE = PREFIX + "REFUSED the file list cannot be written (%s)";
    static final String MORE = PREFIX + "REFUSED %d more problem(s) not shown - fix the ones above, then run this again";
    static final String FINGERPRINT_NOTE = PREFIX + "note: the kept %s carries no usable fingerprint= row -"
            + " kit.fingerprint is left out of MANIFEST.txt";
    static final String MANIFEST_LEFT = " - MANIFEST.txt could not be removed (%s); delete it before starting this folder";
    static final String WRITTEN_ONCE = PREFIX + "REFUSED this plan was already written - plan again";
    /** The shipped-shape flags' plan refusal. */
    static final String SHAPES_ARGS = PREFIX + "REFUSED %s - a packed folder takes --shapes with its --kit;"
            + " --mod-variants needs --shapes";

    /** Every text template this class prints itself (the readers' R4-R7 / R12 / R13 arrive through their own). */
    static List<String> texts() {
        return List.of(OK, K, R1, R2, R3, R8, R9, R10, R11, R12, R14, EF, R15, OTHER_RELEASE, NON_CLASS, CR_BYTE, PIN,
                MOD_ID_TEXT, SECRET, UNREADABLE, UNLISTABLE, MORE, FINGERPRINT_NOTE, MANIFEST_LEFT, WRITTEN_ONCE,
                SHAPES_ARGS, NO_STANDING_MATCH, STANDING_SPLIT, REKEYED);
    }

    /** Best-effort steps that failed without a line of their own (temp cleanup, a lock or zip close). */
    static final AtomicInteger QUIET_FAILURES = new AtomicInteger();
    /** SEED files that appeared between the plan's probe and the write (kept, never overwritten). */
    static final AtomicInteger SEEDS_KEPT_LATE = new AtomicInteger();
    /** Library shapes {@code --shapes} wrote into a folder's {@code match/} (unlisted), summed over runs.
     * */
    static final AtomicInteger SHAPES_WRITTEN = new AtomicInteger();
    /**
     * Selftest seam ONLY (null in every product run): called with the folder after {@code MANIFEST.txt}
     * is placed and before the G2 self-verify, so a row can damage a placed file and watch G2 catch it.
     */
    static final AtomicReference<Consumer<Path>> BEFORE_SELF_VERIFY = new AtomicReference<>();
    /**
     * Selftest seam ONLY (null in every product run): called with each route's target path just before
     * that route is placed, so a row can throw an {@link Error} mid-routes (after real places) and watch
     * the made-by-this-tool marker hold at that instant and {@code failure()} record the rows placed.
     *
     */
    static final AtomicReference<Consumer<Path>> BEFORE_ROUTE = new AtomicReference<>();

    private PackageFolderWriter() {
    }

    /**
     * The plan: every refusal collected before any write, the ordered route table, the SOURCE re-run
     * deletes, the manifest meta in {@link #META_ORDER}. Immutable apart from the two resources it
     * holds for {@link #write}: the {@code stage.lock} hold (PACKED / SOURCE) and the open base zip
     * (SOURCE). A refused plan has released both already; {@link #close} releases them otherwise.
     */
    public static final class Plan implements AutoCloseable {
        private final Kind kind;
        private final Path pkg;
        private final List<String> refusals;
        private final List<Route> routes;
        private final List<String> deletes;
        private final Map<String, String> meta;
        private final List<String> notices;
        private final int skipped;
        private final PackageManifest previous;
        private final Map<String, String> pins;
        /** {@code --shapes}' library ({@code null} = no shipped set) + what the write hands
         *  {@link MatchShapes#writeShapeSet}: the STANDING server sidecar's lines (match-keyed; a kit's sidecar
         *  carries no match term to re-key), the standing decoded shape, the mod's ids. */
        private final Path shapesDir;
        private final List<String> standingSidecarLines;
        private final MatchDescriptor standingShape;
        private final Set<String> modVariantIds;
        private FileChannel stageHold;
        private PackageBaseZip base;
        private boolean claimed;

        private Plan(Planner pl, List<String> refusals) {
            this.kind = pl.kind;
            this.pkg = pl.pkg;
            this.refusals = List.copyOf(refusals);
            this.routes = List.copyOf(pl.table);
            this.deletes = List.copyOf(pl.deletes);
            this.meta = Collections.unmodifiableMap(new LinkedHashMap<>(pl.meta));
            this.notices = List.copyOf(pl.notices);
            this.skipped = pl.skipped;
            this.previous = pl.previous;
            this.pins = pl.pins;
            this.shapesDir = pl.standingShape == null ? null : pl.in.shapesDir();
            this.standingSidecarLines = pl.standingSidecarLines;
            this.standingShape = pl.standingShape;
            this.modVariantIds = pl.modVariantIds;
            this.stageHold = pl.hold;
            this.base = pl.baseZip;
        }

        public Kind kind() {
            return kind;
        }

        /** The folder (absolute, normalized). */
        public Path pkg() {
            return pkg;
        }

        /** Every refusal, fully prefixed, at most {@value #MAX_REFUSAL_LINES}; non-empty = {@link #write} refuses. */
        public List<String> refusals() {
            return refusals;
        }

        /** The route table in write order (PACKED / SOURCE sorted by path; an owner stage in today's order). */
        public List<Route> routes() {
            return routes;
        }

        /** SOURCE re-run only: files the previous manifest listed in the writer's own trees that this plan does not write. */
        public List<String> deletes() {
            return deletes;
        }

        /** The manifest meta, in {@link #META_ORDER} (absent rows skipped). */
        public Map<String, String> meta() {
            return meta;
        }

        /** The K lines (a kept SEED file that differs from this build's copy) and notes, fully prefixed. */
        public List<String> notices() {
            return notices;
        }

        /** Class files the classes route leaves out (tools + selftests); 0 for an owner stage. */
        public int skipped() {
            return skipped;
        }

        synchronized void claim() {
            if (!refusals.isEmpty()) {
                throw new IllegalStateException(String.join("\n", refusals));
            }
            if (claimed) {
                throw new IllegalStateException(WRITTEN_ONCE);
            }
            claimed = true;
        }

        /** Releases the {@code stage.lock} hold and closes the base zip (idempotent). */
        @Override
        public synchronized void close() {
            closeQuietly(stageHold);
            stageHold = null;
            if (base != null) {
                try {
                    base.close();
                } catch (IOException closeFailed) {
                    QUIET_FAILURES.incrementAndGet();
                }
                base = null;
            }
        }
    }

    /** {@link #plan(Inputs, Clock, Map)} with the system clock and the real native pins. */
    public static Plan plan(Inputs in) {
        return plan(in, Clock.systemDefaultZone(), NATIVE_PINS);
    }

    /**
     * Reads only (apart from taking {@code <pkg>/stage.lock} for PACKED / SOURCE when the folder
     * exists — creating that empty file if absent) and collects EVERY refusal, in the order:
     * locks, the folder law, inputs, links, the kit, the base, the route table, the grammar.
     * {@code clock} stamps a SOURCE folder ({@code source-<yyyyMMdd-HHmmss>}, its zone);
     * {@code nativePins} = the Linux natives' sha256 pins (the selftests pin their fixture bytes).
     */
    static Plan plan(Inputs in, Clock clock, Map<String, String> nativePins) {
        Objects.requireNonNull(in, "inputs");
        Planner pl = new Planner(in, clock, nativePins);
        try {
            pl.run();
        } catch (RuntimeException bug) {
            closeQuietly(pl.hold);
            closeBase(pl.baseZip);
            throw bug;
        }
        return pl.toPlan();
    }

    /**
     * Lays the folder out as {@code p} planned, in this order: routes, deletes,
     * {@code MANIFEST.txt} LAST (temp + {@code ATOMIC_MOVE}), then G2: the written manifest must
     * {@link PackageManifest#firstMismatch} to null, and {@link OpsRoots#packagedChecked} must RETURN
     * on a PACKED / SOURCE folder and THROW on an owner stage. PACKED / SOURCE write {@code MANIFEST.failed}
     * (and drop a previous {@code MANIFEST.txt}) before the first route, so even a killed run leaves the
     * made-by-this-tool marker. On a G2 failure or any failure after
     * the first write, the folder is left without {@code MANIFEST.txt} (renamed / composed into
     * {@code MANIFEST.failed}) and an {@link IOException} carries R14 + EF; an {@link Error}
     * is recorded the same way, then rethrown as is. A refused or
     * already-written plan, a lock lost since the plan, or a folder that cannot be created throws
     * {@link IllegalStateException} with REFUSED lines, nothing written. Releases the plan's lock.
     */
    public static Result write(Plan p) throws IOException {
        p.claim();
        try {
            return new Writer(p).run();
        } finally {
            p.close();
        }
    }

    // =====================================================================================
    // the plan
    // =====================================================================================

    /** One plan's state; the phase methods fill their own refusal bucket, emitted in phase order (locks first, the grammar last). */
    private static final class Planner {
        final Inputs in;
        final Kind kind;
        final Platform platform;
        final Clock clock;
        final Map<String, String> pins;
        final Path pkg;
        final List<String> locks = new ArrayList<>();
        final List<String> dir = new ArrayList<>();
        final List<String> inputs = new ArrayList<>();
        final List<String> links = new ArrayList<>();
        final List<String> kit = new ArrayList<>();
        final List<String> base = new ArrayList<>();
        final List<String> routes = new ArrayList<>();
        final List<String> grammar = new ArrayList<>();
        final List<Route> table = new ArrayList<>();
        final List<String> deletes = new ArrayList<>();
        final LinkedHashMap<String, String> meta = new LinkedHashMap<>();
        final List<String> notices = new ArrayList<>();
        /**
         * S4: the inputs {@link #secretName} flagged — every plan read site of an input ({@link #basePhase}'s
         * open, {@link #readKit}, {@link #addFile}) consults it first, so a flagged input is named, never opened.
         *
         */
        final Set<Path> secret = new HashSet<>();
        /** False once a base entry name failed {@link PackageFolderWriter#listable}: the base then contributes no route. */
        boolean baseNamesOk = true;
        /** The base's shape-library names: routed or refused by {@link #baseShapeRoutes}, never by the generic base route. */
        final Set<String> baseShapeNames = new HashSet<>();
        /**
         * The base's own release kit entry, {@code kits/<pack.kit>}, iff the base's {@code pack.kit} meets
         * {@link KitPackRules#PACK_KIT}; else null. The value is DATA from the base: {@link #routeBaseEntry} only
         * compares it by string equality with an entry name {@link #baseNamesCheck} already vetted, never
         * resolves it as a path.
         *
         */
        String baseKitRel;
        int skipped;
        FileChannel hold;
        PackageBaseZip baseZip;
        PackageManifest previous;
        PackageKitZip.Kit kitRead;
        /**
         * The STANDING pair the folder seeds as {@code match/match.txt} + its sidecar ({@code null} = none): a
         * kit carries no match file, so the pair comes from {@code --match} ({@link #readStandingFile}; PACKED
         * requires it, the kit bucket's {@link PackageFolderWriter#NO_STANDING_MATCH} line refusing a run without
         * it), else on SOURCE from the base's own pair ({@link #readStandingPair}); {@link #standingDecoded} = the
         * file decoded by {@link #standingChecks} ({@code null} = not redacted or not a match file: its line says why).
         *
         * On SOURCE the sidecar is re-keyed to the kit when it names another build ({@link #rekeyToKit}).
         */
        byte[] standingMatch;
        byte[] standingSidecar;
        MatchDescriptor standingDecoded;
        /** {@link #shapesPhase}'s products ({@code null} = no shipped set): the write's inputs. */
        List<String> standingSidecarLines;
        MatchDescriptor standingShape;
        Set<String> modVariantIds;
        String jreVersion;
        boolean folderOk = true;
        boolean classesOk;
        boolean modOk;

        Planner(Inputs in, Clock clock, Map<String, String> pins) {
            this.in = in;
            this.kind = in.kind();
            this.platform = in.platform() == null ? Platform.WINDOWS : in.platform();
            this.clock = clock == null ? Clock.systemDefaultZone() : clock;
            this.pins = pins == null ? NATIVE_PINS : pins;
            this.pkg = in.pkg() == null ? null : in.pkg().toAbsolutePath().normalize();
        }

        void run() {
            if (kind == null || pkg == null) {
                inputs.add(fmt(R10, "a kind and a server folder (--kind, --pkg)", "<none>"));
                return;
            }
            locksPhase();
            dirPhase();
            inputsPhase();
            if (kind == Kind.SOURCE) {
                basePhase();
            }
            if (kind != Kind.OWNER_STAGE) {
                kitPhase();
            }
            if (in.shapesDir() != null || in.modVariantsDir() != null) {
                shapesPhase();
            }
            routesPhase();
            if (kind == Kind.SOURCE && previous != null) {
                deletesPhase();
            }
            linksPhase();
            metaPhase();
            keptPhase();
            grammarPhase();
        }

        Plan toPlan() {
            List<String> all = new ArrayList<>();
            for (List<String> bucket : List.of(locks, dir, inputs, links, kit, base, routes, grammar)) {
                all.addAll(bucket);
            }
            if (all.size() > MAX_REFUSAL_LINES) {
                int more = all.size() - (MAX_REFUSAL_LINES - 1);
                all = new ArrayList<>(all.subList(0, MAX_REFUSAL_LINES - 1));
                all.add(fmt(MORE, more));
            }
            Plan p = new Plan(this, all);
            if (!all.isEmpty()) {
                p.close();
            }
            return p;
        }

        // ---- phase 1: locks ----

        /** R1 (a launcher holds {@code run/serve.lock}) and R2 ({@code stage.lock} held elsewhere); never for an owner stage. */
        void locksPhase() {
            if (kind == Kind.OWNER_STAGE) {
                return;
            }
            if (ServerOpsLaunch.lockHeld(pkg)) {
                locks.add(fmt(R1, mask(pkg)));
            }
            if (Files.isDirectory(pkg)) {
                hold = tryStageLock(pkg);
                if (hold == null) {
                    locks.add(fmt(R2, mask(pkg)));
                }
            }
        }

        // ---- phase 2: the folder law ----

        /** PACKED: empty (its own {@code stage.lock} aside); SOURCE: empty or made by this tool; owner stage: present. */
        void dirPhase() {
            boolean exists = Files.exists(pkg, LinkOption.NOFOLLOW_LINKS);
            if (exists && !Files.isDirectory(pkg)) {
                dir.add(fmt(R10, "a server folder (a directory)", mask(pkg)));
                folderOk = false;
                return;
            }
            if (kind == Kind.OWNER_STAGE) {
                if (!exists) {
                    dir.add(fmt(R10, "the owner stage folder", mask(pkg)));
                    folderOk = false;
                }
                return;
            }
            if (kind == Kind.SOURCE && exists) {
                previous = madeByThisTool(pkg);
            }
            if (exists && previous == null) {
                try {
                    if (nonEmpty(pkg)) {
                        dir.add(fmt(R3, mask(pkg)));
                    }
                } catch (IOException unlistable) {
                    dir.add(fmt(UNREADABLE, mask(pkg), unlistable.getClass().getSimpleName()));
                }
            }
        }

        // ---- phase 3: inputs present ----

        void inputsPhase() {
            switch (kind) {
                case OWNER_STAGE -> {
                    classesOk = requireDir(in.classesDir(), "the built server classes (--classes)");
                    modOk = requireDir(in.clientModDir(), "the client mod folder (--client-mod)");
                    requireFile(in.premainJar(), "the premain jar (--premain)");
                    requireFile(in.holderJar(), "the rng holder jar (--holder)");
                    requireFile(in.c2File(), "the C2 directives file (--c2)");
                    if (folderOk) {
                        requireFile(pkg.resolve(ARGV_REL), "the minted serve.argv");
                    }
                    if (!MD5.matcher(lower(in.kitJarMd5())).matches()) {
                        inputs.add(fmt(R10, "a 32-hex kit jar md5 (--kit-jar-md5)", mask(in.kitJarMd5())));
                    }
                }
                case PACKED -> {
                    classesOk = requireDir(in.classesDir(), "the built server classes (--classes)");
                    requireFile(in.premainJar(), "the premain jar (--premain)");
                    requireFile(in.holderJar(), "the rng holder jar (--holder)");
                    requireFile(in.c2File(), "the C2 directives file (--c2)");
                    modOk = requireDir(in.clientModDir(), "the built client mod folder (--client-mod)");
                    if (modOk) {
                        modOk = requireFile(in.clientModDir().resolve("mod_info.json"), "the client mod's mod_info.json")
                                & requireFile(in.clientModDir().resolve(LICENSE_REL), "the client mod's LICENSE.txt")
                                & requireDir(in.clientModDir().resolve("data"), "the client mod's data folder");
                    }
                    requireFile(in.kitZip(), "the player kit zip (--kit)");
                    if (requireFile(in.argvFile(), "the minted serve.argv (--argv)")) {
                        Path mint = in.argvFile().toAbsolutePath().getParent();
                        requireFile(mint.resolve(LAUNCHER_TEMPLATE), "the minted " + LAUNCHER_TEMPLATE + " beside --argv");
                        requireFile(mint.resolve(NOTICES_REL), "THIRD-PARTY-NOTICES.txt beside --argv");
                    }
                    if (requireDir(in.jreDir(), "the serve JRE image (--jre)")) {
                        requireFile(in.jreDir().resolve(JRE_STAMP), "the serve JRE image's " + JRE_STAMP);
                        if (platform == Platform.WINDOWS) {
                            requireFile(in.jreDir().resolve("bin/java.exe"), "the serve JRE image's bin/java.exe");
                        } else {
                            requireFile(in.jreDir().resolve("bin/java"), "the serve JRE image's bin/java");
                            requireFile(in.jreDir().resolve("lib/jspawnhelper"), "the serve JRE image's lib/jspawnhelper");
                        }
                    }
                    if (platform == Platform.WINDOWS && in.mesaDir() != null) {
                        requireDir(in.mesaDir(), "the Mesa folder (--mesa)");
                    }
                    if (platform == Platform.LINUX && requireDir(in.nativeDir(), "the Linux natives folder (--native)")) {
                        for (String n : pins.keySet()) {
                            requireFile(in.nativeDir().resolve(n), "the native " + n);
                        }
                    }
                    if (requireDir(in.hostFilesDir(), "the host files folder (--host-files)")) {
                        requireFile(in.hostFilesDir().resolve(PROPS_TEMPLATE), "the host files' " + PROPS_TEMPLATE);
                        for (String h : platform == Platform.WINDOWS ? HOST_WINDOWS : HOST_LINUX) {
                            requireFile(in.hostFilesDir().resolve(h), "the host files' " + h);
                        }
                    }
                    if (!GIT_HEAD_FULL.matcher(lower(in.gitHead())).matches()) {
                        inputs.add(fmt(R10, "a 40-hex git commit (--git-head)", mask(in.gitHead())));
                    }
                    if (in.stamp() == null || !STAMP.matcher(in.stamp()).matches()) {
                        inputs.add(fmt(R10, "a pack stamp of 1-32 letters, digits or - (--stamp)", mask(in.stamp())));
                    }
                }
                case SOURCE -> {
                    classesOk = requireDir(in.classesDir(), "the built server classes (--classes)");
                    requireFile(in.premainJar(), "the premain jar (--premain)");
                    requireFile(in.holderJar(), "the rng holder jar (--holder)");
                    if (in.c2File() != null) {
                        requireFile(in.c2File(), "the C2 directives file (--c2)");
                    }
                    requireFile(in.baseZip(), "the server package zip (--base)");
                    if (in.kitZip() != null) {
                        requireFile(in.kitZip(), "the player kit zip (--kit)");
                    }
                }
            }
            if (kind != Kind.OWNER_STAGE && in.matchFile() != null
                    && requireFile(in.matchFile(), "the standing match file (--match)")) {
                requireFile(sidecarOf(in.matchFile()), "the standing match file's .fingerprint beside --match");
            }
            if (kind != Kind.OWNER_STAGE) {
                for (Path p : Arrays.asList(in.premainJar(), in.holderJar(), in.c2File(), in.kitZip(), in.baseZip(),
                        in.argvFile(), in.matchFile(), sidecarOf(in.matchFile()))) {
                    secretName(p, inputs);
                }
            }
        }

        /**
         * The shipped shape set's PLAN half: {@code --shapes} only on a PACKED folder
         * with its {@code --kit}; {@code --mod-variants} only beside {@code --shapes}; both folders present and
         * not named like a secret; the mod's variant ids = the stems of every {@code *.variant} (ASCII-case-folded
         * extension, case kept) under {@code --mod-variants}, walked without following a link, at most
         * {@link #MAX_MOD_VARIANT_FILES} regular files; the standing match decodes ({@link #standingChecks}: it is
         * the dedupe key, and its match-keyed sidecar is what each shape's sidecar is re-keyed from). Every
         * problem is a REFUSED line, nothing written. The library itself is read by the WRITE
         * ({@link MatchShapes#writeShapeSet}: the ship law, the roster belt, N1), inside the marker window.
         *
         */
        void shapesPhase() {
            if (kind != Kind.PACKED || in.shapesDir() == null || in.kitZip() == null) {
                inputs.add(fmt(SHAPES_ARGS, in.shapesDir() == null ? "--mod-variants without --shapes"
                        : "--shapes with --kind " + String.valueOf(kind).toLowerCase(Locale.ROOT)
                                + (in.kitZip() == null ? " and no --kit" : "")));
                return;
            }
            secretName(in.shapesDir(), inputs);
            boolean ok = requireDir(in.shapesDir(), "the match-shape library (--shapes)")
                    && !secret.contains(in.shapesDir());
            Set<String> ids = new TreeSet<>();
            if (in.modVariantsDir() != null) {
                secretName(in.modVariantsDir(), inputs);
                ok &= requireDir(in.modVariantsDir(), "the mod's variants folder (--mod-variants)");
                if (ok && !secret.contains(in.modVariantsDir())) {
                    ok = modVariantIds(in.modVariantsDir(), ids);
                }
            }
            if (!ok || kitRead == null || standingDecoded == null) {
                return; // the kit's own refusals or the standing pair's line (kit bucket) already say why
            }
            standingShape = standingDecoded;
            standingSidecarLines = new String(standingSidecar, StandardCharsets.ISO_8859_1).lines().toList();
            modVariantIds = Collections.unmodifiableSet(ids);
        }

        /** The {@code *.variant} stems under {@code dir} into {@code ids}; a walk past the cap or a walk failure =
         *  one REFUSED line and {@code false}. */
        boolean modVariantIds(Path dir, Set<String> ids) {
            int files = 0;
            try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    if (++files > MAX_MOD_VARIANT_FILES) {
                        inputs.add(fmt(UNREADABLE, mask(dir), "over " + MAX_MOD_VARIANT_FILES + " files"));
                        return false;
                    }
                    String name = p.getFileName().toString();
                    if (name.toLowerCase(Locale.ROOT).endsWith(".variant") && name.length() > ".variant".length()) {
                        ids.add(name.substring(0, name.length() - ".variant".length()));
                    }
                }
            } catch (IOException | RuntimeException unwalkable) {
                inputs.add(fmt(UNREADABLE, mask(dir), unwalkable.getClass().getSimpleName()));
                return false;
            }
            return true;
        }

        boolean requireDir(Path p, String what) {
            if (p == null || !Files.isDirectory(p)) {
                inputs.add(fmt(R10, what, p == null ? "<none>" : mask(p)));
                return false;
            }
            return true;
        }

        boolean requireFile(Path p, String what) {
            if (p == null || !Files.isRegularFile(p)) {
                inputs.add(fmt(R10, what, p == null ? "<none>" : mask(p)));
                return false;
            }
            return true;
        }

        /**
         * S4: an input whose file NAME reads like a password or token file ({@code *.password},
         * {@code *.admin*}, {@code *-tokens*}) refuses, named by path only — its content is never opened:
         * the path joins {@link #secret}, which every later plan read site consults before it opens.
         */
        void secretName(Path p, List<String> sink) {
            if (p != null && p.getFileName() != null && SECRET_NAME.matcher(p.getFileName().toString()).find()) {
                sink.add(fmt(SECRET, mask(p)));
                secret.add(p);
            }
        }

        // ---- phase 6: the base (SOURCE; run before the kit, whose default it supplies; its lines print after the kit's) ----

        /**
         * R12 / R13 from the reader, the entry-name grammar (S3), the {@code git.head} check, the other-release refusal
         * and the entries a folder needs. A secret-named base (S4) is never opened.
         */
        void basePhase() {
            if (in.baseZip() == null || !Files.isRegularFile(in.baseZip()) || secret.contains(in.baseZip())) {
                return;
            }
            try {
                baseZip = PackageBaseZip.open(in.baseZip());
            } catch (IllegalArgumentException refused) {
                base.add(PREFIX + refused.getMessage());
                return;
            } catch (IOException notAZip) {
                base.add(fmt(R12, mask(in.baseZip())));
                return;
            }
            baseNamesCheck();
            String baseKit = baseZip.manifest().packKit();
            baseKitRel = baseKit != null && KitPackRules.PACK_KIT.matcher(baseKit).matches() ? KITS_PREFIX + baseKit : null;
            String head = baseZip.manifest().meta.get(PackageManifest.GIT_HEAD);
            if (head == null || !(head.equals("unknown") || GIT_HEAD.matcher(head).matches())) {
                base.add(fmt(R12, mask(in.baseZip())));
            }
            if (previous != null) {
                String was = previous.meta.get(PackageManifest.GIT_HEAD);
                if (!Objects.equals(was, head)) {
                    base.add(fmt(OTHER_RELEASE, mask(was), mask(head)));
                }
            }
            List<String> needed = new ArrayList<>(List.of(ARGV_REL, MOD_INFO_REL));
            needed.addAll(baseKitNeeds());
            if (in.c2File() == null) {
                needed.add(C2_REL);
            }
            Set<String> names = new HashSet<>(baseZip.names());
            for (String n : needed) {
                if (!names.contains(n)) {
                    base.add(fmt(R10, "base entry " + n, mask(in.baseZip())));
                }
            }
        }

        /**
         * S3: a base entry name is DATA, checked by the routed-path law ({@link PackageFolderWriter#listable},
         * portable) BEFORE any route, link probe or {@code pkg.resolve} sees it — a name such as
         * {@code jre/a*b} passes the reader's {@code safeRelative} yet throws {@code InvalidPathException}
         * from {@code resolve} on Windows. A failing name is one R9 line (masked; the plan's line cap
         * bounds a flood) and the base then contributes no route at all ({@link #baseNamesOk}).
         *
         */
        void baseNamesCheck() {
            for (String n : baseZip.names()) {
                if (!listable(n, true)) {
                    base.add(fmt(R9, mask(n)));
                    baseNamesOk = false;
                }
            }
        }

        // ---- phase 5: the kit ----

        /**
         * R5-R7 ({@link PackageKitZip#parity}) on every COPY kind, after the standing pair is read and held to
         * the kit. PACKED: the {@code --match} pair ({@link #readStandingFile}; none = the one
         * {@link PackageFolderWriter#NO_STANDING_MATCH} line: a kit carries no match file), then the kit zip against
         * the built mod tree + classes. SOURCE: the kit (given, else one composed from the base's jar and sidecar)
         * against the mod data the folder will hold (the base's, extracted to a temp folder for the comparison) + the
         * built classes, and the standing pair ({@code --match}, else the base's own, {@link #readStandingPair});
         * temp files are removed in finally.
         */
        void kitPhase() {
            if (kind == Kind.PACKED) {
                if (in.kitZip() == null || !Files.isRegularFile(in.kitZip())) {
                    return;
                }
                readKit(in.kitZip());
                if (in.matchFile() == null) {
                    kit.add(NO_STANDING_MATCH); // a kit carries no match file: the pair is a pack input of its own
                } else if (kitRead != null) {
                    readStandingFile();
                }
                if (kitRead != null && modOk && classesOk) {
                    parity(in.kitZip(), in.clientModDir());
                }
                return;
            }
            if (baseZip == null || !classesOk || (in.kitZip() != null && !Files.isRegularFile(in.kitZip()))) {
                return;
            }
            Set<String> names = new HashSet<>(baseZip.names());
            if (!names.containsAll(baseKitNeeds())) {
                return; // basePhase's R10 line names the missing entry
            }
            Path tmp = null;
            try {
                tmp = Files.createTempDirectory("cc-pfw-");
                Path kitPath = in.kitZip() != null ? in.kitZip() : composeKit(tmp.resolve("kit.zip"));
                readKit(kitPath);
                if (kitRead != null) {
                    if (in.matchFile() != null) {
                        readStandingFile();
                    } else {
                        readStandingPair(names);
                    }
                    parity(kitPath, extractModData(kitRead.sidecar(), tmp.resolve("mod")));
                }
            } catch (IllegalArgumentException refused) {
                kit.add(PREFIX + refused.getMessage());
            } catch (IOException unreadable) {
                kit.add(fmt(UNREADABLE, mask(in.kitZip() != null ? in.kitZip() : in.baseZip()),
                        unreadable.getClass().getSimpleName()));
            } finally {
                deleteTreeQuietly(tmp);
            }
        }

        void readKit(Path kitPath) {
            if (secret.contains(kitPath)) {
                return; // S4: its SECRET line is the record; a flagged kit is never opened (parity needs kitRead)
            }
            try {
                kitRead = PackageKitZip.read(kitPath);
            } catch (IllegalArgumentException refused) {
                kit.add(PREFIX + refused.getMessage());
            } catch (IOException notAZip) {
                kit.add(fmt(UNREADABLE, mask(kitPath), notAZip.getClass().getSimpleName()));
            }
        }

        void parity(Path kitPath, Path modDir) {
            try {
                for (String line : PackageKitZip.parity(kitRead, kitPath, modDir, in.classesDir())) {
                    kit.add(PREFIX + line);
                }
            } catch (IllegalArgumentException refused) {
                kit.add(PREFIX + refused.getMessage());
            } catch (IOException unreadable) {
                kit.add(fmt(UNREADABLE, mask(kitPath), unreadable.getClass().getSimpleName()));
            }
        }

        /**
         * SOURCE without {@code --match}: the base's own standing file ({@link #baseMatchRel}) + sidecar become the standing pair,
         * held to the kit ({@link #standingChecks}); a base without the pair = the one
         * {@link PackageFolderWriter#NO_STANDING_MATCH} line. A read failure throws to {@link #kitPhase}'s own
         * catch.
         */
        void readStandingPair(Set<String> names) throws IOException {
            String match = baseMatchRel();
            if (!names.contains(match) || !names.contains(match + MatchShapes.SIDECAR_SUFFIX)) {
                kit.add(NO_STANDING_MATCH);
                return;
            }
            standingMatch = baseZip.read(match);
            standingSidecar = baseZip.read(match + MatchShapes.SIDECAR_SUFFIX);
            standingChecks(match);
        }

        /**
         * The base entry the SOURCE standing match file is read from: {@code match/match.txt} when the base holds
         * it (a package from before the shipped defaults' folder), else {@code match-defaults/match.txt} when it
         * holds that (a package that lists every file); the sidecar is the name + {@code .fingerprint}. Either way
         * the folder seeds it as {@link #MATCH_REL} ({@link #kitRoutes}).
         */
        String baseMatchRel() {
            List<String> names = baseZip == null ? List.of() : baseZip.names();
            return !names.contains(MATCH_REL) && names.contains(DEFAULTS_MATCH_REL) ? DEFAULTS_MATCH_REL : MATCH_REL;
        }

        /**
         * {@code --match}: the file + its {@code .fingerprint} beside it ({@link PackageFolderWriter#sidecarOf}) become
         * the standing pair, each read under its cap and held to the kit ({@link #standingChecks}). A missing or
         * secret-named input already has its inputs-bucket line and is never opened; a read failure is one UNREADABLE
         * line naming the file and the exception class.
         */
        void readStandingFile() {
            Path match = in.matchFile();
            Path side = sidecarOf(match);
            if (!Files.isRegularFile(match) || side == null || !Files.isRegularFile(side) || secret.contains(match)
                    || secret.contains(side)) {
                return;
            }
            try {
                standingMatch = readBounded(match, MatchShapes.MAX_DESCRIPTOR_BYTES);
                standingSidecar = readBounded(side, MAX_SIDECAR_BYTES);
            } catch (IOException unreadable) {
                standingMatch = null;
                standingSidecar = null;
                kit.add(fmt(UNREADABLE, mask(match), unreadable.getClass().getSimpleName()));
                return;
            }
            standingChecks(mask(match));
        }

        /**
         * The standing pair held to the kit and to itself, the refusal lines in THIS order: R4 + the content half
         * ({@link PackageKitZip#standingParity}: strictly redacted; the sidecar's composite without its match term is
         * the kit's, so its {@code jar=} is the jar R5 holds to {@code kit.jar.md5}); then, for a redacted file only
         * (a live-token file is refused whole: its fix is the released file), it decodes (else one UNREADABLE line) and
         * the sidecar's {@code match=} is the file's {@code match.id} ({@link PackageFolderWriter#STANDING_SPLIT} - the
         * launcher's own pair-split check, read before the folder exists). {@code shown} = the masked name the lines
         * carry. Sets {@link #standingDecoded} when the file decodes.
         * <p>SOURCE re-keys the sidecar to the kit first ({@link #rekeyToKit}), so the content half is held to the
         * kit only when the re-key could not compose; PACKED holds it as read.
         */
        void standingChecks(String shown) {
            if (kind == Kind.SOURCE) {
                rekeyToKit();
            }
            for (String line : PackageKitZip.standingParity(standingMatch, standingSidecar, kitRead)) {
                kit.add(PREFIX + line);
            }
            if (!redacted(standingMatch)) {
                return;
            }
            try {
                standingDecoded = MatchDescriptorCodec.decode(
                        new String(standingMatch, StandardCharsets.UTF_8).lines().toList());
            } catch (RuntimeException undecodable) {
                kit.add(fmt(UNREADABLE, shown, "not a match file: " + undecodable.getClass().getSimpleName()));
                return;
            }
            String fp = fingerprintOrNull(standingSidecar);
            if (fp == null) {
                return; // standingParity's content line already names a sidecar with no fingerprint= row
            }
            String keyed = ContentFingerprints.componentOrNull(fp, "match");
            if (keyed == null || !keyed.trim().equals(standingDecoded.matchId())) {
                kit.add(fmt(STANDING_SPLIT, shown));
            }
        }

        /**
         * SOURCE: the standing sidecar becomes the one re-keyed to the kit this folder serves
         * ({@link PackageKitZip#rekeyedToKitOrNull}: that kit's content, the standing match term) when its content
         * half names another build, and the one {@link PackageFolderWriter#REKEYED} note says so. Every later reader
         * of {@link #standingSidecar} (the routes, {@code kit.fingerprint}, the shipped shapes' sidecars) then sees
         * the re-keyed bytes; the match file is never touched. No re-key = the bytes as read, so
         * {@link PackageKitZip#standingParity} refuses as before. WHY SOURCE only: a source host's own build is
         * another build than the package's by construction, while a packed folder's package and kit come as one
         * release, where a differ is a wrong pairing that stays refused.
         *
         */
        void rekeyToKit() {
            byte[] rekeyed = PackageKitZip.rekeyedToKitOrNull(standingSidecar, kitRead);
            if (rekeyed != null) {
                standingSidecar = rekeyed;
                notices.add(REKEYED);
            }
        }

        /** The base entries a SOURCE kit + standing pair are read from: the jar + sidecar when no {@code --kit}
         *  (the composed kit's), the match file too when no {@code --match}. */
        List<String> baseKitNeeds() {
            List<String> out = new ArrayList<>();
            if (in.kitZip() == null) {
                out.addAll(List.of(JAR_REL, baseMatchRel() + MatchShapes.SIDECAR_SUFFIX));
                if (in.matchFile() == null) {
                    out.add(baseMatchRel());
                }
            }
            return out;
        }

        /** A kit zip in the released shape from the base's own entries (each verified against the base's row);
         *  the base's match file stays out (a kit carries none, {@link PackageKitZip#read}). */
        Path composeKit(Path zip) throws IOException {
            byte[] sidecar = baseZip.read(baseMatchRel() + MatchShapes.SIDECAR_SUFFIX);
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip, StandardOpenOption.CREATE_NEW))) {
                put(zos, PackageKitZip.JAR_ENTRY, baseZip.read(JAR_REL));
                put(zos, PackageKitZip.SIDECAR_ENTRY, sidecar);
                for (String rel : fileRows(sidecar)) {
                    byte[] data = baseZip.names().contains(MOD_PREFIX + rel) ? baseZip.read(MOD_PREFIX + rel) : null;
                    if (data != null) {
                        put(zos, PackageKitZip.MOD_PREFIX + rel, data);
                    }
                }
            }
            return zip;
        }

        /** The base's mod files the sidecar's {@code file=} rows name, under {@code dir} (the folder-to-be's data). */
        Path extractModData(byte[] sidecar, Path dir) throws IOException {
            Files.createDirectories(dir);
            for (String rel : fileRows(sidecar)) {
                Path t = dir.resolve(rel).normalize();
                if (t.startsWith(dir) && baseZip.names().contains(MOD_PREFIX + rel)) {
                    baseZip.copyTo(MOD_PREFIX + rel, t);
                }
            }
            return dir;
        }

        // ---- phase 7: the route table ----

        void routesPhase() {
            if (!folderOk) {
                return;
            }
            switch (kind) {
                case OWNER_STAGE -> ownerRoutes();
                case PACKED -> packedRoutes();
                case SOURCE -> sourceRoutes();
            }
        }

        /** The owner-stage row set and order: argv, premain, the RNG holder jar, C2, classes, serve-mods. */
        void ownerRoutes() {
            table.add(new Route(ARGV_REL, Source.inPlace(pkg.resolve(ARGV_REL)), true, false));
            if (in.premainJar() != null && Files.isRegularFile(in.premainJar())) {
                table.add(new Route(PREMAIN_REL, Source.file(in.premainJar()), true, false));
            }
            if (in.holderJar() != null && Files.isRegularFile(in.holderJar())) {
                table.add(new Route(HOLDER_REL, Source.file(in.holderJar()), true, false));
            }
            if (in.c2File() != null && Files.isRegularFile(in.c2File())) {
                table.add(new Route(C2_REL, Source.file(in.c2File()), true, false));
            }
            if (classesOk) {
                for (Map.Entry<String, Path> e : walk(in.classesDir(), true).entrySet()) {
                    table.add(new Route("classes/" + e.getKey(), Source.linked(e.getValue()), true, false));
                }
            }
            Path mods = pkg.resolve("serve-mods");
            if (!Files.isDirectory(mods)) {
                routes.add(fmt(R10, "the stage's serve-mods folder", mask(mods)));
                return;
            }
            Path real;
            try {
                real = mods.toRealPath();
            } catch (IOException unresolvable) {
                routes.add(fmt(UNREADABLE, mask(mods), unresolvable.getClass().getSimpleName()));
                return;
            }
            for (Map.Entry<String, Path> e : walk(real, true).entrySet()) {
                table.add(new Route("serve-mods/" + e.getKey(), Source.linked(e.getValue()), true, false));
            }
        }

        /**
         * The release packer's routes, sorted by path. EVERY route is
         * LISTED and none is a seed: the folder holds program files only, so a hand extract of a newer zip over it
         * replaces nothing the host owns. The host's live files are created at the first start from the listed
         * templates ({@link #PROPS_TEMPLATE}, {@link #LAUNCHER_TEMPLATE}), the fixed {@link #ENABLED_MODS} bytes and
         * the {@link #DEFAULTS_PREFIX} pairs.
         */
        void packedRoutes() {
            List<Route> r = new ArrayList<>();
            jreRoutes(r);
            classesRoutes(r);
            addFile(r, PREMAIN_REL, in.premainJar(), true, false);
            addFile(r, HOLDER_REL, in.holderJar(), true, false);
            addFile(r, C2_REL, in.c2File(), true, false);
            if (modOk) {
                Path modInfo = in.clientModDir().resolve("mod_info.json");
                modIdCheck(modInfo);
                r.add(new Route(MOD_INFO_REL, Source.file(modInfo), true, false));
                for (Map.Entry<String, Path> e : walk(in.clientModDir().resolve("data"), false).entrySet()) {
                    r.add(new Route(DATA_PREFIX + e.getKey(), Source.file(e.getValue()), true, false));
                }
                r.add(new Route(LICENSE_REL, Source.file(in.clientModDir().resolve(LICENSE_REL)), true, false));
            }
            kitRoutes(r);
            if (in.argvFile() != null && Files.isRegularFile(in.argvFile())) {
                Path mint = in.argvFile().toAbsolutePath().getParent();
                addFile(r, ARGV_REL, in.argvFile(), true, false);
                addFile(r, LAUNCHER_TEMPLATE, mint.resolve(LAUNCHER_TEMPLATE), true, false);
                addFile(r, NOTICES_REL, mint.resolve(NOTICES_REL), true, false);
            }
            if (in.hostFilesDir() != null && Files.isDirectory(in.hostFilesDir())) {
                addFile(r, PROPS_TEMPLATE, in.hostFilesDir().resolve(PROPS_TEMPLATE), true, false);
                for (String h : platform == Platform.WINDOWS ? HOST_WINDOWS : HOST_LINUX) {
                    Path src = in.hostFilesDir().resolve(h);
                    if (addFile(r, h, src, true, false) && LF_ONLY.contains(h)) {
                        crCheck(h, readOrNull(src, mask(src)));
                    }
                }
            }
            if (platform == Platform.LINUX && in.nativeDir() != null && Files.isDirectory(in.nativeDir())) {
                for (String n : pins.keySet()) {
                    Path src = in.nativeDir().resolve(n);
                    if (addFile(r, NATIVE_PREFIX + n, src, true, false)) {
                        pinCheck(mask(src), n, fileSha(src));
                    }
                }
            }
            r.sort(Comparator.comparing(Route::rel));
            table.addAll(r);
        }

        /** G1's routes: classes + premain + the RNG holder jar from the build, the C2 file from the build or the base,
         *  the rest from the base. */
        void sourceRoutes() {
            List<Route> r = new ArrayList<>();
            classesRoutes(r);
            addFile(r, PREMAIN_REL, in.premainJar(), true, false);
            addFile(r, HOLDER_REL, in.holderJar(), true, false);
            if (in.c2File() != null) {
                addFile(r, C2_REL, in.c2File(), true, false);
            }
            if (baseZip != null && baseNamesOk) {
                baseShapeRoutes(r);
                for (String name : baseZip.names()) {
                    routeBaseEntry(r, name);
                }
                liveSeedsFromTemplates(r);
            }
            kitRoutes(r);
            r.sort(Comparator.comparing(Route::rel));
            table.addAll(r);
        }

        /**
         * A base that lists every file carries no live-named settings file, so the SOURCE folder seeds each live
         * name it lacks from the base's own listed template ({@link #PROPS_TEMPLATE} -> {@link #PROPS_REL},
         * {@link #LAUNCHER_TEMPLATE} -> {@link #LAUNCHER_REL}) and the mod list from the fixed {@link #ENABLED_MODS}
         * bytes - the seeds an older base carried under the live names. WHY here: a SOURCE folder's first start never
         * repairs it (its stamp is not a release stamp), so the writer seeds what the start needs. A base that holds
         * the live name routes it through {@link #routeBaseEntry} as before.
         */
        void liveSeedsFromTemplates(List<Route> r) {
            List<String> names = baseZip.names();
            if (!names.contains(PROPS_REL) && names.contains(PROPS_TEMPLATE)) {
                r.add(new Route(PROPS_REL, Source.base(PROPS_TEMPLATE), false, true));
            }
            if (!names.contains(LAUNCHER_REL) && names.contains(LAUNCHER_TEMPLATE)) {
                r.add(new Route(LAUNCHER_REL, Source.base(LAUNCHER_TEMPLATE), false, true));
            }
            if (!names.contains(ENABLED_REL) && baseMatchRel().equals(DEFAULTS_MATCH_REL)) {
                r.add(new Route(ENABLED_REL, Source.bytes(ENABLED_MODS.getBytes(StandardCharsets.US_ASCII)), false, true));
            }
        }

        /**
         * S3: a base entry name is DATA — it is routed only when the table names it; an entry the
         * table does not route refuses (R13), the base's classes / premain / RNG holder jar are replaced by the build's.
         * The base's own release kit ({@link #baseKitRel}) is copied LISTED when no {@code --kit} is given, and
         * dropped when one is ({@link #kitRoutes} ships the given kit, which {@code pack.kit} then names); every
         * other {@code kits/*} entry still refuses R13.
         *
         * <p>A base that lists every file: its two templates and its {@code update.bat} / {@code update.sh} route as
         * listed singles ({@link #BASE_LISTED_SINGLES}); its {@code match-defaults/match.txt} pair is the SOURCE
         * standing pair ({@link #baseMatchRel}: read once, seeded at {@link #MATCH_REL} and re-keyed by
         * {@link #kitRoutes}), never copied under its own name; its {@code match-defaults/match-*} pairs ride
         * {@link #baseShapeRoutes}.
         */
        void routeBaseEntry(List<Route> r, String name) {
            if (name.equals(PackageBaseZip.MANIFEST_ENTRY) || name.startsWith("classes/") || name.equals(PREMAIN_REL)
                    || name.equals(HOLDER_REL) || name.equals(JAR_REL) || name.equals(MATCH_REL)
                    || name.equals(SIDECAR_REL) || name.equals(DEFAULTS_MATCH_REL) || name.equals(DEFAULTS_SIDECAR_REL)
                    || baseShapeNames.contains(name)) {
                return;
            }
            if (name.equals(C2_REL)) {
                if (in.c2File() == null) {
                    r.add(new Route(name, Source.base(name), true, false));
                }
            } else if (name.startsWith("jre/") || name.startsWith(DATA_PREFIX) || BASE_LISTED_SINGLES.contains(name)) {
                r.add(new Route(name, Source.base(name), true, false));
                if (name.startsWith(NATIVE_PREFIX)) {
                    String n = name.substring(NATIVE_PREFIX.length());
                    String row = baseZip.manifest().sha256ByPath.get(name);
                    pinCheck(name, n, row != null ? row : baseSha(name));
                }
            } else if (SEED_RELS.contains(name)) {
                r.add(new Route(name, Source.base(name), false, true));
                if (LF_ONLY.contains(name)) {
                    crCheck(name, baseRead(name));
                }
            } else if (name.equals(baseKitRel)) {
                if (in.kitZip() == null) {
                    r.add(new Route(name, Source.base(name), true, false));
                }
            } else {
                routes.add(PREFIX + PackageKitZip.r13(in.baseZip(), name).getMessage());
            }
        }

        /**
         * The shipped shape library a SOURCE base carries (the pack's {@code --shapes} output: each
         * {@code match-defaults/match-<name>.txt} with its {@code .fingerprint}, listed in the base's manifest, or,
         * from an older base, {@code match/match-<name>.txt}, unlisted). Every pair lands at its LIVE name
         * {@code match/match-<name>.txt}, UNLISTED and as a SEED, like the standing pair: the descriptor copied from the base (it must
         * decode and be strictly redacted - a shipped shape never carries a live token), the sidecar NOT copied but
         * RE-KEYED from THIS run's STANDING sidecar to the shape's id ({@link ContentFingerprintMint#rekeySidecarLines},
         * the pack's own composition; the standing sidecar is match-keyed and held to the kit's content half, while a
         * kit's own sidecar carries no match term to re-key). WHY: the base's sidecars name the jar the PACKAGE was
         * packed with, and a source host's own kit may hash differently - a copied sidecar would leave every shape
         * refused at Start as stale (the shape the launcher starts is checked the way {@code match/match.txt}'s is). A
         * shape whose id is the standing match's is deduped ({@code match/match.txt} IS that shape). Refusals wear the R13 text (a damaged or
         * edited package: download it again): an orphan half of a pair, a text over the descriptor cap, one that
         * does not decode or is not redacted, more shapes than the pack ships; a name carrying {@code -tokens} is
         * not a library name and falls to the generic base route (R13, never opened). An unread kit adds nothing
         * here: its own lines say why. The folder cannot start before its first run completes (no manifest yet), so
         * the pair's write order is the route order, not the live folder's sidecar-first law.
         */
        void baseShapeRoutes(List<Route> r) {
            Set<String> names = new HashSet<>(baseZip.names());
            List<String> texts = new ArrayList<>();
            for (String name : baseZip.names()) {
                // a base that lists every file ships its library under match-defaults/; an older one under match/
                String prefix = name.startsWith(SHAPE_TXT_PREFIX) ? SHAPE_TXT_PREFIX
                        : name.startsWith(DEFAULTS_SHAPE_PREFIX) ? DEFAULTS_SHAPE_PREFIX : null;
                if (prefix == null) {
                    continue;
                }
                String txt = name.endsWith(MatchShapes.SIDECAR_SUFFIX)
                        ? name.substring(0, name.length() - MatchShapes.SIDECAR_SUFFIX.length()) : name;
                if (!txt.endsWith(MatchShapes.TXT) || txt.length() <= prefix.length() + MatchShapes.TXT.length()
                        || txt.contains(MatchShapes.TOKENS_MARK)) {
                    continue;
                }
                baseShapeNames.add(name);
                if (!names.contains(txt) || !names.contains(txt + MatchShapes.SIDECAR_SUFFIX)) {
                    routes.add(PREFIX + PackageKitZip.r13(in.baseZip(), name).getMessage());
                } else if (name.equals(txt)) {
                    texts.add(txt);
                }
            }
            if (texts.isEmpty()) {
                return;
            }
            if (texts.size() > MatchShapes.MAX_SHIPPED_SHAPES) {
                routes.add(PREFIX + PackageKitZip.r13(in.baseZip(), "#" + texts.size() + " match shapes (over "
                        + MatchShapes.MAX_SHIPPED_SHAPES + ")").getMessage());
                return;
            }
            if (kitRead == null || standingDecoded == null) {
                return; // the kit bucket's own line says why
            }
            String standingId = standingDecoded.matchId();
            String sidecarShown = in.matchFile() != null ? mask(sidecarOf(in.matchFile()))
                    : baseMatchRel() + MatchShapes.SIDECAR_SUFFIX;
            List<String> standingLines = new String(standingSidecar, StandardCharsets.ISO_8859_1).lines().toList();
            Set<String> seeded = new HashSet<>();
            for (String txt : texts) {
                // the LIVE name: a SOURCE folder seeds every shape into match/, whichever folder the base kept it in
                String live = "match/" + txt.substring(txt.lastIndexOf('/') + 1);
                if (!seeded.add(live)) {
                    continue; // the same shape under both folders: the first route stands
                }
                byte[] bytes = baseRead(txt);
                if (bytes == null) {
                    continue; // baseRead's own line is the record
                }
                String id = shapeIdOrNull(bytes);
                if (id == null) {
                    routes.add(PREFIX + PackageKitZip.r13(in.baseZip(), txt).getMessage());
                    continue;
                }
                if (id.equals(standingId)) {
                    continue; // deduped: match/match.txt is this shape
                }
                List<String> sidecar;
                try {
                    sidecar = ContentFingerprintMint.rekeySidecarLines(standingLines, id);
                } catch (IllegalArgumentException badSidecar) {
                    // the message names a key, never a value
                    kit.add(fmt(UNREADABLE, sidecarShown, badSidecar.getMessage()));
                    return;
                }
                r.add(new Route(live, Source.base(txt), false, true));
                r.add(new Route(live + MatchShapes.SIDECAR_SUFFIX,
                        Source.bytes(ContentFingerprintMint.sidecarBytes(sidecar)), false, true));
            }
        }

        /** A base shape text's {@code match.id}, or null when it is over the descriptor cap, is not strictly
         *  redacted, or does not decode. */
        static String shapeIdOrNull(byte[] bytes) {
            if (bytes.length > MatchShapes.MAX_DESCRIPTOR_BYTES) {
                return null;
            }
            try {
                List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
                return MatchDescriptors.isStrictlyRedacted(lines) ? MatchDescriptorCodec.decode(lines).matchId() : null;
            } catch (RuntimeException notAShape) {
                return null;
            }
        }

        /**
         * The kit's routes: its jar (listed), the STANDING match file + its sidecar (PACKED: LISTED at
         * {@link #DEFAULTS_MATCH_REL}; SOURCE: a SEED at {@link #MATCH_REL}; the bytes as read, the
         * sidecar re-keyed to the kit on SOURCE ({@link #rekeyToKit});
         * {@link #standingMatch}: {@code --match}'s pair, else a SOURCE base's own, never the kit's: a kit carries no
         * match file; none routed while there is no pair), and, when a kit zip was
         * GIVEN ({@code --kit}: PACKED, or SOURCE with {@code --kit}), that zip itself at
         * {@code kits/<its file name>}, LISTED, so the launcher hashes it before every start and the folder's
         * MANIFEST {@code pack.kit} (the same file name) resolves to it. A SOURCE run without {@code --kit} composes
         * its kit in a temp folder and ships none here: the base's own kit rides {@link #routeBaseEntry}. Shared by
         * PACKED and SOURCE and platform-blind, so a Linux package ships it too.
         *
         */
        void kitRoutes(List<Route> r) {
            if (kitRead == null) {
                return;
            }
            r.add(new Route(JAR_REL, Source.bytes(kitRead.jar()), true, false));
            if (standingMatch != null && standingSidecar != null) {
                // PACKED lists the shipped default (the first start copies it into match/); SOURCE seeds the live pair
                boolean packed = kind == Kind.PACKED;
                r.add(new Route(packed ? DEFAULTS_MATCH_REL : MATCH_REL, Source.bytes(standingMatch), packed, !packed));
                r.add(new Route(packed ? DEFAULTS_SIDECAR_REL : SIDECAR_REL, Source.bytes(standingSidecar), packed,
                        !packed));
            }
            if (in.kitZip() != null && in.kitZip().getFileName() != null) {
                addFile(r, KITS_PREFIX + in.kitZip().getFileName(), in.kitZip(), true, false);
            }
        }

        /**
         * The classes route for PACKED / SOURCE: a non-{@code .class}
         * file refuses; {@code tools/**} and every OUTER class whose simple name ends {@code selftest}
         * (any case) with its {@code $} inner classes stay home ({@link #skipped}).
         */
        void classesRoutes(List<Route> r) {
            if (!classesOk) {
                return;
            }
            List<String> nonClass = new ArrayList<>();
            for (Map.Entry<String, Path> e : walk(in.classesDir(), false).entrySet()) {
                String rel = e.getKey();
                if (!rel.endsWith(".class")) {
                    nonClass.add(rel);
                    continue;
                }
                String leaf = rel.substring(rel.lastIndexOf('/') + 1);
                String outer = leaf.substring(0, leaf.length() - ".class".length());
                int dollar = outer.indexOf('$');
                if (dollar >= 0) {
                    outer = outer.substring(0, dollar);
                }
                if (rel.startsWith(TOOLS_PREFIX) || outer.toLowerCase(Locale.ROOT).endsWith("selftest")) {
                    skipped++;
                    continue;
                }
                r.add(new Route("classes/" + rel, Source.file(e.getValue()), true, false));
            }
            if (!nonClass.isEmpty()) {
                routes.add(fmt(NON_CLASS, mask(in.classesDir()), nonClass.size(), firstFew(nonClass)));
            }
        }

        /**
         * The JRE image whole, its stamp rewritten without {@code source=} / {@code bytes=} /
         * {@code minted=} ({@code pack.jre} = its {@code implementorVersion=}); Windows: the Mesa pair in
         * {@code bin/} (from {@code mesaDir} when given), each checked against the stamp's
         * {@code mesa.<name>.sha256=} pin when the stamp carries one; Linux: a {@code target=linux} row.
         */
        void jreRoutes(List<Route> r) {
            if (in.jreDir() == null || !Files.isRegularFile(in.jreDir().resolve(JRE_STAMP))) {
                return;
            }
            Path stampFile = in.jreDir().resolve(JRE_STAMP);
            byte[] raw = readOrNull(stampFile, mask(stampFile));
            if (raw == null) {
                return;
            }
            List<String> lines = Arrays.asList(new String(raw, StandardCharsets.ISO_8859_1).split("\r\n|\n|\r"));
            StringBuilder kept = new StringBuilder();
            Map<String, String> mesaPins = new HashMap<>();
            for (String line : lines) {
                if (jreVersion == null && line.startsWith("implementorVersion=")) {
                    jreVersion = line.substring("implementorVersion=".length());
                }
                if (line.startsWith("mesa.") && line.contains(".sha256=")) {
                    mesaPins.put(line.substring("mesa.".length(), line.indexOf(".sha256=")),
                            line.substring(line.indexOf(".sha256=") + ".sha256=".length()));
                }
                if (!STAMP_DROP.matcher(line).find()) {
                    kept.append(line).append('\n');
                }
            }
            if (jreVersion == null || !jreVersion.matches("^[\\x21-\\x7E]+$")) {
                routes.add(fmt(R10, "a usable implementorVersion= row in the JRE stamp", mask(stampFile)));
                jreVersion = null;
            }
            if (platform == Platform.LINUX && !lines.contains("target=linux")) {
                routes.add(fmt(R10, "a target=linux row in the JRE stamp (an image linked for Linux)", mask(stampFile)));
            }
            if (!printableLines(kept)) {
                routes.add(fmt(R10, "an ASCII JRE stamp", mask(stampFile)));
            }
            LinkedHashMap<String, Route> jre = new LinkedHashMap<>();
            for (Map.Entry<String, Path> e : walk(in.jreDir(), false).entrySet()) {
                String rel = "jre/" + e.getKey();
                jre.put(rel, e.getKey().equals(JRE_STAMP)
                        ? new Route(rel, Source.bytes(kept.toString().getBytes(StandardCharsets.ISO_8859_1)), true, false)
                        : new Route(rel, Source.file(e.getValue()), true, false));
            }
            if (platform == Platform.WINDOWS) {
                for (String dll : MESA_DLLS) {
                    String rel = "jre/bin/" + dll;
                    Path src = in.mesaDir() != null ? in.mesaDir().resolve(dll) : in.jreDir().resolve("bin").resolve(dll);
                    if (!Files.isRegularFile(src)) {
                        routes.add(fmt(R10, "the Mesa " + dll, mask(src)));
                        continue;
                    }
                    jre.put(rel, new Route(rel, Source.file(src), true, false));
                    String pin = mesaPins.get(dll.substring(0, dll.lastIndexOf('.')));
                    String have = pin == null ? null : fileSha(src);
                    if (pin != null && have != null && !pin.equals(have)) {
                        routes.add(fmt(PIN, mask(src), have, mask(pin), dll));
                    }
                }
            }
            r.addAll(jre.values());
        }

        /** A file route when {@code src} is a regular file and not a secret-flagged input (S4: never a route, never opened). */
        boolean addFile(List<Route> r, String rel, Path src, boolean listed, boolean seed) {
            if (src == null || !Files.isRegularFile(src) || secret.contains(src)) {
                return false;
            }
            r.add(new Route(rel, Source.file(src), listed, seed));
            return true;
        }

        void modIdCheck(Path modInfo) {
            byte[] b = readOrNull(modInfo, mask(modInfo));
            if (b == null) {
                return;
            }
            Matcher m = MOD_ID.matcher(new String(b, StandardCharsets.UTF_8));
            String id = m.find() ? m.group(1) : null;
            if (!"cc_client".equals(id)) {
                routes.add(fmt(MOD_ID_TEXT, mask(modInfo), mask(id)));
            }
        }

        void crCheck(String name, byte[] bytes) {
            if (bytes == null) {
                return;
            }
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == '\r') {
                    routes.add(fmt(CR_BYTE, name, i, name));
                    return;
                }
            }
        }

        void pinCheck(String shown, String n, String have) {
            String pin = pins.get(n);
            if (have != null && (pin == null || !pin.equals(have))) {
                routes.add(fmt(PIN, shown, have, pin == null ? "<none>" : pin, n));
            }
        }

        // ---- deletes (planned here, run by the writer after the routes) ----

        /**
         * S2: a SOURCE re-run deletes a file only when it is in the
         * writer's OWN trees ({@link #OWN_TREES}) AND the PREVIOUS manifest ({@code MANIFEST.txt}, else
         * {@code MANIFEST.failed}) listed it AND this plan does not list it — a host file that was never
         * listed is never deleted, whatever folder it sits in.
         */
        void deletesPhase() {
            Set<String> listedNow = new HashSet<>();
            for (Route r : table) {
                if (r.listed()) {
                    listedNow.add(r.rel());
                }
            }
            TreeSet<String> out = new TreeSet<>();
            for (String rel : previous.sha256ByPath.keySet()) {
                if (inOwnTree(rel) && !listedNow.contains(rel)) {
                    out.add(rel);
                }
            }
            deletes.addAll(out);
        }

        // ---- phase 4: links ----

        /**
         * R8, REPARSE-AWARE: every existing path this plan would write or delete through —
         * each ancestor folder and the file itself — is refused when {@link #isLink} (a symlink OR a
         * reparse point such as a junction). An owner stage instead REQUIRES its two links to resolve
         * to the passed targets ({@link Files#isSameFile}) and checks only the files it writes.
         */
        void linksPhase() {
            if (!folderOk || !Files.isDirectory(pkg)) {
                return;
            }
            List<String> candidates = new ArrayList<>(List.of(MANIFEST_REL, FAILED_REL));
            if (kind == Kind.OWNER_STAGE) {
                sameFile(pkg.resolve("classes"), in.classesDir());
                sameFile(pkg.resolve("serve-mods/CC_Client"), in.clientModDir());
                candidates.addAll(List.of(PREMAIN_REL, HOLDER_REL, C2_REL));
            } else {
                for (Route r : table) {
                    candidates.add(r.rel());
                }
                candidates.addAll(deletes);
            }
            TreeSet<String> prefixes = new TreeSet<>();
            for (String rel : candidates) {
                if (!PackageManifest.safeRelative(rel)) {
                    continue;
                }
                for (int i = rel.indexOf('/'); i >= 0; i = rel.indexOf('/', i + 1)) {
                    prefixes.add(rel.substring(0, i));
                }
                prefixes.add(rel);
            }
            List<String> flagged = new ArrayList<>();
            for (String p : prefixes) {
                boolean under = false;
                for (String f : flagged) {
                    under |= p.startsWith(f + "/");
                }
                if (!under && isLink(pkg.resolve(p))) {
                    links.add(fmt(R8, mask(pkg), mask(p)));
                    flagged.add(p);
                }
            }
        }

        void sameFile(Path link, Path target) {
            if (target == null) {
                return;
            }
            boolean same;
            try {
                same = Files.isSameFile(link, target);
            } catch (IOException absent) {
                same = false; // the R10 line below is this failure's record
            }
            if (!same) {
                links.add(fmt(R10, "a stage link to " + mask(target), mask(link)));
            }
        }

        // ---- meta ----

        /** The manifest meta in {@link #META_ORDER}; a row whose value is unknown or blank is left out. */
        void metaPhase() {
            switch (kind) {
                case OWNER_STAGE -> {
                    String head = lower(in.gitHead());
                    meta.put(PackageManifest.GIT_HEAD, GIT_HEAD.matcher(head).matches() ? head : "unknown");
                    String md5 = lower(in.kitJarMd5());
                    if (MD5.matcher(md5).matches()) {
                        meta.put(PackageManifest.KIT_JAR_MD5, md5);
                    }
                }
                case PACKED -> {
                    putIf(PackageManifest.GIT_HEAD, GIT_HEAD_FULL.matcher(lower(in.gitHead())).matches()
                            ? lower(in.gitHead()) : null);
                    putIf(PackageManifest.KIT_JAR_MD5, kitRead == null ? null : kitRead.jarMd5());
                    // the composite the folder's door expects = the STANDING sidecar's (match-keyed), never the kit's
                    putIf("kit.fingerprint", kitRead == null || standingSidecar == null ? null
                            : fingerprintOrNull(standingSidecar));
                    putIf(OpsRoots.PACK_STAMP, in.stamp() != null && STAMP.matcher(in.stamp()).matches() ? in.stamp() : null);
                    putIf("pack.kit", in.kitZip() == null || in.kitZip().getFileName() == null ? null
                            : in.kitZip().getFileName().toString());
                    putIf("pack.jre", jreVersion);
                    // the zip's own program set, so an older install's frozen check admits a root file this release added
                    putIf(PackageUpdateCheck.PROGRAM_SET_KEY, PackageUpdateCheck.programSetRow());
                }
                case SOURCE -> {
                    Map<String, String> bm = baseZip == null ? Map.of() : baseZip.manifest().meta;
                    putIf(PackageManifest.GIT_HEAD, bm.get(PackageManifest.GIT_HEAD));
                    putIf(PackageManifest.KIT_JAR_MD5, kitRead == null ? null : kitRead.jarMd5());
                    putIf("kit.fingerprint", sourceFingerprint());
                    putIf(OpsRoots.PACK_STAMP, "source-" + SOURCE_STAMP.format(LocalDateTime.now(clock)));
                    // a SOURCE folder laid out with --kit names THAT kit, so
                    // the console's package-kit reader resolves kits/<pack.kit> to the jar kit.jar.md5 describes
                    // (PACKED's rule above); without --kit the base's kit name stays
                    putIf("pack.kit", in.kitZip() != null && in.kitZip().getFileName() != null
                            ? in.kitZip().getFileName().toString() : bm.get("pack.kit"));
                    putIf("pack.jre", bm.get("pack.jre"));
                }
            }
        }

        void putIf(String key, String value) {
            if (value != null && !value.isBlank()) {
                meta.put(key, value);
            }
        }

        /**
         * The composite of the sidecar the folder will hold: a kept one (a re-run) else the STANDING pair's
         * (match-keyed; a kit's own sidecar carries no match term). A kept
         * sidecar whose {@code jar=} is not the md5 of the jar this run places is R15 ((4): {@code kit.jar.md5}
         * = the sidecar's {@code jar=}, trimmed and case-sensitive as the launcher's {@code sidecar_stale}
         * check reads it); one with no {@code jar=} component draws no R15.
         *
         */
        String sourceFingerprint() {
            Path kept = pkg.resolve(SIDECAR_REL);
            if (Files.isRegularFile(kept, LinkOption.NOFOLLOW_LINKS)) {
                byte[] b = readCapped(kept, MAX_SIDECAR_BYTES);
                String fp = b == null ? null : fingerprintOrNull(b);
                if (fp == null) {
                    notices.add(fmt(FINGERPRINT_NOTE, SIDECAR_REL));
                }
                String keptJar = ContentFingerprints.componentOrNull(fp, "jar");
                if (keptJar != null && kitRead != null && !keptJar.trim().equals(kitRead.jarMd5())) {
                    kit.add(fmt(R15, mask(keptJar.trim()), kitRead.jarMd5()));
                }
                return fp;
            }
            return kitRead == null || standingSidecar == null ? null : fingerprintOrNull(standingSidecar);
        }

        // ---- K lines ----

        /** The K law: a SEED file already in the folder is kept; a K line names it when it differs from this build's copy. */
        void keptPhase() {
            if (kind == Kind.OWNER_STAGE || !Files.isDirectory(pkg)) {
                return;
            }
            for (Route r : table) {
                Path t = pkg.resolve(r.rel());
                if (!r.seed() || !Files.exists(t, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                byte[] have = Files.isRegularFile(t, LinkOption.NOFOLLOW_LINKS) ? readCapped(t, MAX_HOST_FILE_BYTES) : null;
                byte[] ours = switch (r.source().from()) {
                    case BYTES -> r.source().bytes();
                    case FILE -> readCapped(r.source().file(), MAX_HOST_FILE_BYTES);
                    case BASE -> baseRead(r.source().baseEntry());
                    default -> null;
                };
                if (have == null || ours == null || !Arrays.equals(have, ours)) {
                    notices.add(fmt(K, r.rel()));
                }
            }
        }

        // ---- phase 8: the grammar ----

        /**
         * S3 + the path law per kind ({@link PackageFolderWriter#listable}, the predicate a base entry
         * name meets too): every routed path must be printable ASCII and
         * {@link PackageManifest#safeRelative}; PACKED / SOURCE also the portable law
         * ({@code [A-Za-z0-9._$+-]} segments, none ending in a dot or
         * a space, no device name) and no password / token file name (S4); an owner stage lists the
         * owner mode's own mods, spaces included. Then {@link PackageManifest#render}
         * runs dry (a placeholder hash: the same length) for R11.
         */
        void grammarPhase() {
            boolean portable = kind != Kind.OWNER_STAGE;
            LinkedHashMap<String, String> rows = new LinkedHashMap<>();
            for (Route r : table) {
                String rel = r.rel();
                boolean ok = listable(rel, portable);
                if (!ok) {
                    grammar.add(fmt(R9, mask(rel)));
                }
                if (portable && SECRET_NAME.matcher(leaf(rel)).find()) {
                    grammar.add(fmt(SECRET, mask(rel)));
                }
                if (ok && r.listed()) {
                    rows.put(rel, "0".repeat(64));
                }
            }
            LinkedHashMap<String, String> dryMeta = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : meta.entrySet()) {
                if (printableAscii(e.getValue()) && e.getValue().equals(e.getValue().trim())) {
                    dryMeta.put(e.getKey(), e.getValue());
                } else {
                    grammar.add(fmt(R9, mask(e.getValue())));
                }
            }
            try {
                PackageManifest.render(dryMeta, rows);
            } catch (IllegalArgumentException refused) {
                String m = String.valueOf(refused.getMessage());
                grammar.add(m.startsWith("REFUSED the file list exceeds") ? R11 : fmt(UNLISTABLE, mask(m)));
            }
        }

        // ---- planner helpers ----

        /**
         * Every regular file under {@code root}, folder-relative with {@code /}, sorted ordinal. A link
         * or reparse point inside (a junction reads as a directory, so {@code walkFileTree} would
         * descend into it) is SKIPPED for an owner stage (today's walk) and REFUSED (R8, the
         * input named) for a COPY kind. An unreadable tree is a refusal line, never a partial list.
         */
        TreeMap<String, Path> walk(Path given, boolean skipLinks) {
            TreeMap<String, Path> out = new TreeMap<>();
            try {
                Path root = given.toRealPath();
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                        if (!d.equals(root) && (a.isSymbolicLink() || a.isOther())) {
                            linkInside(root, d, skipLinks);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                        if (a.isSymbolicLink() || a.isOther()) {
                            linkInside(root, f, skipLinks);
                        } else if (a.isRegularFile()) {
                            out.put(relOf(root, f), f);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path f, IOException e) throws IOException {
                        throw e;
                    }
                });
            } catch (IOException unreadable) {
                routes.add(fmt(UNREADABLE, mask(given), unreadable.getClass().getSimpleName()));
                out.clear();
            }
            return out;
        }

        void linkInside(Path root, Path p, boolean skipLinks) {
            if (!skipLinks) {
                routes.add(fmt(R8, mask(root), mask(relOf(root, p))));
            }
        }

        byte[] readOrNull(Path p, String shown) {
            try {
                return readBounded(p, MAX_HOST_FILE_BYTES);
            } catch (IOException unreadable) {
                routes.add(fmt(UNREADABLE, shown, unreadable.getClass().getSimpleName()));
                return null;
            }
        }

        String fileSha(Path p) {
            try {
                return PackageManifest.sha256Hex(p);
            } catch (IOException unreadable) {
                routes.add(fmt(UNREADABLE, mask(p), unreadable.getClass().getSimpleName()));
                return null;
            }
        }

        byte[] baseRead(String name) {
            try {
                return baseZip.read(name);
            } catch (IllegalArgumentException refused) {
                routes.add(PREFIX + refused.getMessage());
            } catch (IOException unreadable) {
                routes.add(fmt(UNREADABLE, mask(in.baseZip()), unreadable.getClass().getSimpleName()));
            }
            return null;
        }

        String baseSha(String name) {
            byte[] b = baseRead(name);
            return b == null ? null : HexFormat.of().formatHex(sha256().digest(b));
        }
    }

    // =====================================================================================
    // the write
    // =====================================================================================

    /** One write of one plan; {@link #rows} = the manifest's file rows as the files are placed. */
    private static final class Writer {
        /** The hash a {@code MANIFEST.failed} row carries before its file is placed (the all-zero placeholder). */
        static final String UNPLACED_SHA = "0".repeat(64);
        final Plan p;
        final Path pkg;
        final LinkedHashMap<String, String> rows = new LinkedHashMap<>();
        boolean manifestPlaced;
        String at = MANIFEST_REL;

        Writer(Plan p) {
            this.p = p;
            this.pkg = p.pkg;
        }

        /**
         * Preflight (refusals, nothing written), then the made-by-this-tool marker, the routes, the deletes,
         * {@code MANIFEST.txt}, G2; {@code MANIFEST.failed} goes LAST, after G2 passed. Any {@link Throwable}
         * after the preflight reaches {@link #failure} (the record); an {@link Error} is then rethrown as is,
         * anything else as the R14 {@link IOException}.
         */
        Result run() throws IOException {
            preflight();
            String problem;
            try {
                markUnfinished();
                for (Route r : p.routes) {
                    at = r.rel();
                    Consumer<Path> before = BEFORE_ROUTE.get();
                    if (before != null) {
                        before.accept(target(r.rel()));
                    }
                    place(r);
                }
                if (p.shapesDir != null) {
                    at = DEFAULTS_PREFIX;
                    writeShapes();
                }
                for (String rel : p.deletes) {
                    at = rel;
                    delete(rel);
                }
                at = MANIFEST_REL;
                byte[] manifest = PackageManifest.render(new LinkedHashMap<>(p.meta), rows);
                replace(target(MANIFEST_REL), manifest);
                manifestPlaced = true;
                Consumer<Path> hook = BEFORE_SELF_VERIFY.get();
                if (hook != null) {
                    hook.accept(pkg);
                }
                problem = selfVerify(manifest);
            } catch (Throwable failed) {
                // failure() is this failure's record (MANIFEST.failed + the R14 line); an Error propagates unchanged
                problem = at + ": " + reason(failed);
                IOException recorded = failure(problem, failed);
                if (failed instanceof Error error) {
                    throw error;
                }
                throw recorded;
            }
            if (problem != null) {
                throw failure(problem, null);
            }
            deleteQuietly(pkg.resolve(FAILED_REL));
            return new Result(fmt(OK, mask(pkg), rows.size(), p.skipped), rows.size(), p.skipped,
                    pkg.resolve(MANIFEST_REL));
        }

        /**
         * The shipped shape set's WRITE half: after the kit pair's routes, inside the
         * marker window and before the manifest seal, {@link MatchShapes#writeShapeSet} writes every OTHER
         * library shape straight into {@code match-defaults/} as {@code <stem>.txt} (REDACTED) + its re-keyed
         * {@code .fingerprint} (sidecar first). A belt / ship-law / N1 / re-key refusal (all checked before its first write)
         * becomes an {@link IllegalStateException} carrying the refusal's own words (a shape stem, a variant id,
         * a count - never a secret), so {@link #run}'s failure record prints it (exit 1, {@code MANIFEST.failed});
         * an I/O failure keeps its class.
         * <p>Every written pair is then a MANIFEST row: each file is hashed AFTER the write (the bytes on disk are the
         * row) and the rows are re-sorted by path, so the manifest stays in ordinal order with the shapes among the
         * routes' rows; a written name the portable path law refuses fails the run here (R9's words), before the
         * manifest is written. WHY listed: a hand extract of a newer zip must replace a shipped shape the release
         * changed, and the start's hash check must see one that was edited in place.
         */
        void writeShapes() throws IOException {
            Path matchTarget = target(DEFAULTS_MATCH_REL);
            ensureParent(matchTarget);
            MatchShapes.ShapeSetResult set;
            try {
                set = MatchShapes.writeShapeSet(p.standingSidecarLines, p.standingShape, p.shapesDir, p.modVariantIds,
                        matchTarget.getParent());
            } catch (FileSystemException io) {
                throw io;
            } catch (IOException | IllegalArgumentException refused) {
                throw new IllegalStateException("match shapes: " + refused.getMessage());
            }
            SHAPES_WRITTEN.addAndGet(set.written().size());
            for (String stem : set.written()) {
                String txt = DEFAULTS_PREFIX + stem + MatchShapes.TXT;
                for (String rel : List.of(txt + MatchShapes.SIDECAR_SUFFIX, txt)) {
                    at = rel;
                    if (!listable(rel, true)) {
                        throw new IllegalStateException(fmt(R9, mask(rel)));
                    }
                    rows.put(rel, PackageManifest.sha256Hex(target(rel)));
                }
            }
            TreeMap<String, String> sorted = new TreeMap<>(rows);
            rows.clear();
            rows.putAll(sorted);
        }

        /** PACKED / SOURCE: R1 re-asked; the {@code stage.lock} taken now when the folder did not exist at plan time. */
        void preflight() {
            if (p.kind == Kind.OWNER_STAGE) {
                return;
            }
            if (ServerOpsLaunch.lockHeld(pkg)) {
                throw new IllegalStateException(fmt(R1, mask(pkg)));
            }
            if (p.stageHold != null) {
                return;
            }
            try {
                Files.createDirectories(pkg);
            } catch (IOException uncreatable) {
                throw new IllegalStateException(fmt(R10, "a server folder that can be created", mask(pkg)), uncreatable);
            }
            FileChannel ch = tryStageLock(pkg);
            if (ch == null) {
                throw new IllegalStateException(fmt(R2, mask(pkg)));
            }
            synchronized (p) {
                p.stageHold = ch;
            }
            try {
                if (p.kind == Kind.PACKED && nonEmpty(pkg)) {
                    throw new IllegalStateException(fmt(R3, mask(pkg)));
                }
            } catch (IOException unlistable) {
                throw new IllegalStateException(fmt(UNREADABLE, mask(pkg), unlistable.getClass().getSimpleName()),
                        unlistable);
            }
        }

        /**
         * The "never a marker-less folder" law, for a run that dies where no {@code catch} runs (Ctrl-C,
         * taskkill, a power cut): PACKED / SOURCE write {@code MANIFEST.failed} BEFORE the first route — the
         * meta (its {@code pack.stamp} = the made-by-this-tool marker) and the previous manifest's rows
         * united with this plan's listed rows (the next run's delete scope covers whatever this run places);
         * a row not yet placed carries {@link #UNPLACED_SHA}, so the marker never vouches for a byte it did
         * not hash (its readers take only the stamp, the head and the row PATHS). Then the previous
         * {@code MANIFEST.txt} goes, so a re-run killed mid-routes never leaves a stamped manifest over
         * half-rewritten files. {@link #failure} later recomposes the marker from the rows actually placed.
         * An owner stage writes no marker here (its failure path is unchanged).
         *
         */
        void markUnfinished() throws IOException {
            if (p.kind == Kind.OWNER_STAGE) {
                return;
            }
            at = FAILED_REL;
            LinkedHashMap<String, String> marker = new LinkedHashMap<>();
            if (p.previous != null) {
                marker.putAll(p.previous.sha256ByPath);
            }
            for (Route r : p.routes) {
                if (r.listed()) {
                    marker.putIfAbsent(r.rel(), UNPLACED_SHA);
                }
            }
            replace(pkg.resolve(FAILED_REL), PackageManifest.render(new LinkedHashMap<>(p.meta), marker));
            at = MANIFEST_REL;
            Files.deleteIfExists(pkg.resolve(MANIFEST_REL));
        }

        /** G2: the placed files against the manifest just written, then the packaged gate's polarity per kind. */
        String selfVerify(byte[] manifest) {
            String mismatch = PackageManifest.firstMismatch(pkg, PackageManifest.parse(manifest));
            if (mismatch != null) {
                return mismatch;
            }
            try {
                OpsRoots.packagedChecked(pkg);
                return p.kind == Kind.OWNER_STAGE ? MANIFEST_REL + ": an owner stage reads as a packed server" : null;
            } catch (IllegalArgumentException notPacked) {
                return p.kind == Kind.OWNER_STAGE ? null : MANIFEST_REL + ": " + mask(notPacked.getMessage());
            }
        }

        void place(Route r) throws IOException {
            Path t = target(r.rel());
            Source s = r.source();
            switch (s.from()) {
                case IN_PLACE, LINKED -> {
                    if (r.listed()) {
                        rows.put(r.rel(), PackageManifest.sha256Hex(s.file()));
                    }
                }
                case FILE -> {
                    if (r.seed()) {
                        if (!present(t)) {
                            writeSeed(readBounded(s.file(), MAX_HOST_FILE_BYTES), t);
                        }
                    } else {
                        listed(r, copyReplace(s.file(), t));
                    }
                }
                case BYTES -> {
                    if (r.seed()) {
                        if (!present(t)) {
                            if (r.rel().equals(MATCH_REL) && !redacted(s.bytes())) {
                                throw new IllegalStateException("the match file is not strictly redacted");
                            }
                            writeSeed(s.bytes(), t);
                        }
                    } else {
                        if (r.rel().equals(DEFAULTS_MATCH_REL) && !redacted(s.bytes())) {
                            throw new IllegalStateException("the match file is not strictly redacted");
                        }
                        replace(t, s.bytes());
                        listed(r, HexFormat.of().formatHex(sha256().digest(s.bytes())));
                    }
                }
                case BASE -> {
                    if (r.seed()) {
                        if (!present(t)) {
                            baseSeed(s.baseEntry(), t);
                        }
                    } else {
                        listed(r, baseReplace(s.baseEntry(), t));
                    }
                }
            }
        }

        /** A copied native must hash to its pin at write time too (the source may change after the plan). */
        void listed(Route r, String sha) throws IOException {
            if (r.rel().startsWith(NATIVE_PREFIX)) {
                String pin = p.pins.get(r.rel().substring(NATIVE_PREFIX.length()));
                if (pin == null || !pin.equals(sha)) {
                    throw new FileSystemException(r.rel(), null, "sha256 is not the pinned one");
                }
            }
            if (r.listed()) {
                rows.put(r.rel(), sha);
            }
        }

        /**
         * S1: every write target is {@code pkg.resolve(rel).normalize()} and must stay strictly under
         * {@code pkg} (a route that leaves it is a writer bug and aborts the write).
         */
        Path target(String rel) {
            Path t = pkg.resolve(rel).normalize();
            if (!PackageManifest.safeRelative(rel) || !t.startsWith(pkg) || t.equals(pkg)) {
                throw new IllegalStateException("a route left the server folder: " + mask(rel));
            }
            return t;
        }

        /** The parent folders, made; a parent that is a link (one planted since the plan) aborts the write. */
        void ensureParent(Path t) throws IOException {
            Path parent = t.getParent();
            Files.createDirectories(parent);
            if (!parent.equals(pkg) && isLink(parent)) {
                throw new FileSystemException(parent.toString(), null, "is a link");
            }
        }

        /**
         * S1 SEED law, hazard (a): on Windows {@code ATOMIC_MOVE} REPLACES an existing target, so a SEED
         * (host) file is never moved into place — it is probed absent by the caller, then written with
         * {@code CREATE_NEW}, which fails (kept, counted in {@link #SEEDS_KEPT_LATE}) if the host made
         * the file meanwhile; a write that fails after creating it removes only that new file.
         */
        void writeSeed(byte[] bytes, Path t) throws IOException {
            ensureParent(t);
            OutputStream os;
            try {
                os = Files.newOutputStream(t, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException appeared) {
                SEEDS_KEPT_LATE.incrementAndGet();
                return;
            }
            boolean done = false;
            try {
                try (os) {
                    os.write(bytes);
                }
                done = true;
            } finally {
                if (!done) {
                    deleteQuietly(t);
                }
            }
        }

        /** A base SEED entry: {@link PackageBaseZip#copyTo} is {@code CREATE_NEW} itself (hazard (a) as {@link #writeSeed}). */
        void baseSeed(String entry, Path t) throws IOException {
            ensureParent(t);
            try {
                p.base.copyTo(entry, t);
            } catch (FileAlreadyExistsException appeared) {
                SEEDS_KEPT_LATE.incrementAndGet();
            }
        }

        /** A listed file: copied to a temp sibling while hashing, then {@code ATOMIC_MOVE}d over the target. */
        String copyReplace(Path src, Path t) throws IOException {
            ensureParent(t);
            Path tmp = Files.createTempFile(t.getParent(), ".pfw-", ".tmp");
            boolean moved = false;
            try {
                MessageDigest sha = sha256();
                try (InputStream in = new DigestInputStream(Files.newInputStream(src), sha);
                        OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE)) {
                    in.transferTo(out);
                }
                Files.move(tmp, t, StandardCopyOption.ATOMIC_MOVE);
                moved = true;
                return HexFormat.of().formatHex(sha.digest());
            } finally {
                if (!moved) {
                    deleteQuietly(tmp);
                }
            }
        }

        /** Bytes to a temp sibling, then {@code ATOMIC_MOVE}d over the target (the manifest too). */
        void replace(Path t, byte[] bytes) throws IOException {
            ensureParent(t);
            Path tmp = Files.createTempFile(t.getParent(), ".pfw-", ".tmp");
            boolean moved = false;
            try {
                Files.write(tmp, bytes);
                Files.move(tmp, t, StandardCopyOption.ATOMIC_MOVE);
                moved = true;
            } finally {
                if (!moved) {
                    deleteQuietly(tmp);
                }
            }
        }

        /** A listed base entry: copied (hash-checked against the base's own row) to a temp sibling, then moved. */
        String baseReplace(String entry, Path t) throws IOException {
            ensureParent(t);
            Path tmp = Files.createTempFile(t.getParent(), ".pfw-", ".tmp");
            Files.delete(tmp);
            boolean moved = false;
            try {
                p.base.copyTo(entry, tmp);
                String row = p.base.manifest().sha256ByPath.get(entry);
                String sha = row != null ? row : PackageManifest.sha256Hex(tmp);
                Files.move(tmp, t, StandardCopyOption.ATOMIC_MOVE);
                moved = true;
                return sha;
            } finally {
                if (!moved) {
                    deleteQuietly(tmp);
                }
            }
        }

        /**
         * S2: removes one planned delete ({@link Planner#deletesPhase} chose it: own tree AND previously
         * listed) when it is a regular file, then every folder it leaves empty, up to {@code pkg}.
         */
        void delete(String rel) throws IOException {
            Path t = target(rel);
            if (!Files.isRegularFile(t, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Files.delete(t);
            for (Path d = t.getParent(); d != null && d.startsWith(pkg) && !d.equals(pkg); d = d.getParent()) {
                if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS) || isLink(d)) {
                    return;
                }
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
                    if (ds.iterator().hasNext()) {
                        return;
                    }
                }
                try {
                    Files.delete(d);
                } catch (DirectoryNotEmptyException filledMeanwhile) {
                    QUIET_FAILURES.incrementAndGet();
                    return;
                }
            }
        }

        /**
         * The folder must not start until a run finishes, and the next run must still see a
         * made-by-this-tool marker: the manifest this run placed (or an owner stage's) is RENAMED to
         * {@code MANIFEST.failed}; a failure before that composes {@code MANIFEST.failed} from the
         * previous manifest's rows plus the rows placed so far (the next run's delete scope), then
         * removes {@code MANIFEST.txt}. Returns the exception to throw (R14 + EF, or + the
         * could-not-remove tail when {@code MANIFEST.txt} survived).
         */
        IOException failure(String problem, Throwable cause) {
            Path m = pkg.resolve(MANIFEST_REL);
            Path f = pkg.resolve(FAILED_REL);
            String left = null;
            try {
                if (manifestPlaced || p.kind == Kind.OWNER_STAGE) {
                    if (Files.exists(m, LinkOption.NOFOLLOW_LINKS)) {
                        Files.move(m, f, StandardCopyOption.ATOMIC_MOVE);
                    }
                } else {
                    LinkedHashMap<String, String> union = new LinkedHashMap<>();
                    if (p.previous != null) {
                        union.putAll(p.previous.sha256ByPath);
                    }
                    union.putAll(rows);
                    replace(f, PackageManifest.render(new LinkedHashMap<>(p.meta), union));
                    Files.deleteIfExists(m);
                }
            } catch (IOException | RuntimeException markFailed) {
                left = markFailed.getClass().getSimpleName();
            }
            if (Files.exists(m, LinkOption.NOFOLLOW_LINKS)) {
                left = left == null ? "it is still there" : left;
            } else {
                left = null;
            }
            int colon = problem.indexOf(": ");
            String path = colon < 0 ? problem : problem.substring(0, colon);
            String why = colon < 0 ? "-" : problem.substring(colon + 2);
            String text = fmt(R14, mask(path), mask(why)) + (left == null ? EF : fmt(MANIFEST_LEFT, left));
            return new IOException(text, cause);
        }

        boolean present(Path t) {
            return Files.exists(t, LinkOption.NOFOLLOW_LINKS);
        }
    }

    // =====================================================================================
    // the CLI
    // =====================================================================================

    /**
     * The CLI (class javadoc: the flag table). Returns normally on exit 0 (the selftests run it
     * in-process); exits 2 on a refusal and 1 on an I/O failure after the first write.
     */
    public static void main(String[] args) {
        int rc = run(args, System.out);
        if (rc != 0) {
            System.exit(rc);
        }
    }

    /** {@link #main} without the exit: the exit code, every line printed to {@code out}. */
    static int run(String[] args, PrintStream out) {
        Inputs in;
        try {
            in = parseArgs(args);
        } catch (IllegalArgumentException bad) {
            out.println(PREFIX + bad.getMessage());
            return 2;
        }
        try (Plan plan = plan(in)) {
            if (!plan.refusals().isEmpty()) {
                plan.refusals().forEach(out::println);
                return 2;
            }
            Result r;
            try {
                r = write(plan);
            } catch (IllegalStateException refused) {
                out.println(refused.getMessage());
                return 2;
            } catch (IOException failed) {
                out.println(failed.getMessage());
                return 1;
            }
            plan.notices().forEach(out::println);
            out.println(r.okLine());
            if (in.produced()) {
                PackageManifest m;
                try {
                    m = PackageManifest.read(r.manifest());
                } catch (IOException | IllegalArgumentException unreadable) {
                    out.println(fmt(UNREADABLE, mask(r.manifest()), unreadable.getClass().getSimpleName()));
                    return 1;
                }
                for (Map.Entry<String, String> e : m.sha256ByPath.entrySet()) {
                    out.println(e.getValue() + "  " + e.getKey());
                }
            }
            return 0;
        }
    }

    private static final Set<String> VALUE_FLAGS = Set.of("--kind", "--pkg", "--base", "--kit", "--classes",
            "--premain", "--holder", "--c2", "--client-mod", "--argv", "--jre", "--mesa", "--native", "--host-files",
            "--platform", "--git-head", "--kit-jar-md5", "--stamp", "--shapes", "--mod-variants", "--match");

    /** The flags as {@link Inputs}; an unknown or repeated flag, a missing value or a bad kind / platform throws (one line). */
    static Inputs parseArgs(String[] args) {
        Map<String, String> v = new HashMap<>();
        boolean produced = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--produced")) {
                if (produced) {
                    throw new IllegalArgumentException("REFUSED --produced is given twice");
                }
                produced = true;
                continue;
            }
            if (!VALUE_FLAGS.contains(a)) {
                throw new IllegalArgumentException("REFUSED " + mask(a) + " is not a flag of this tool");
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("REFUSED " + a + " needs a value");
            }
            if (v.put(a, args[++i]) != null) {
                throw new IllegalArgumentException("REFUSED " + a + " is given twice");
            }
        }
        Kind kind = switch (String.valueOf(v.get("--kind"))) {
            case "owner-stage" -> Kind.OWNER_STAGE;
            case "packed" -> Kind.PACKED;
            case "source" -> Kind.SOURCE;
            default -> throw new IllegalArgumentException("REFUSED --kind must be owner-stage, packed or source");
        };
        if (v.get("--pkg") == null) {
            throw new IllegalArgumentException("REFUSED --pkg needs a value");
        }
        Platform platform = switch (v.getOrDefault("--platform", "windows")) {
            case "windows" -> Platform.WINDOWS;
            case "linux" -> Platform.LINUX;
            default -> throw new IllegalArgumentException("REFUSED --platform must be windows or linux");
        };
        return new Inputs(kind, path(v, "--pkg"), path(v, "--classes"), path(v, "--premain"), path(v, "--holder"),
                path(v, "--c2"), path(v, "--client-mod"), path(v, "--kit"), path(v, "--base"), path(v, "--argv"),
                path(v, "--jre"), path(v, "--mesa"), path(v, "--native"), path(v, "--host-files"), platform, v.get("--git-head"),
                v.get("--kit-jar-md5"), v.get("--stamp"), produced, path(v, "--shapes"), path(v, "--mod-variants"),
                path(v, "--match"));
    }

    private static Path path(Map<String, String> v, String flag) {
        String s = v.get(flag);
        if (s == null) {
            return null;
        }
        try {
            return Path.of(s);
        } catch (InvalidPathException bad) {
            throw new IllegalArgumentException("REFUSED " + flag + " is not a usable path");
        }
    }

    // =====================================================================================
    // shared helpers
    // =====================================================================================

    /**
     * The one link test (R8): a symlink OR a reparse point ({@code isOther()} under
     * {@code NOFOLLOW_LINKS}: a Windows junction is not a symlink to {@link Files#isSymbolicLink}).
     * Absent = false; a path that cannot be read is treated as a link (never written through).
     */
    static boolean isLink(Path p) {
        try {
            return Files.isSymbolicLink(p)
                    || Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther();
        } catch (NoSuchFileException absent) {
            return false;
        } catch (IOException unreadable) {
            QUIET_FAILURES.incrementAndGet();
            return true;
        }
    }

    /**
     * Takes {@code <pkg>/stage.lock} ({@code ServerOpsLaunch}'s cross-JVM staging lock) with
     * {@code tryLock}: the held channel, or null when another JVM — or this one — holds it, or the
     * file cannot be opened (never assumed free).
     */
    static FileChannel tryStageLock(Path pkg) {
        FileChannel ch = null;
        try {
            ch = FileChannel.open(pkg.resolve(ServerOpsLaunch.STAGE_LOCK_FILE), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            FileLock won = ch.tryLock();
            if (won != null) {
                return ch;
            }
        } catch (OverlappingFileLockException heldByThisJvm) {
            QUIET_FAILURES.incrementAndGet();
        } catch (IOException unopenable) {
            QUIET_FAILURES.incrementAndGet();
        }
        closeQuietly(ch);
        return null;
    }

    /** {@code MANIFEST.txt}, else {@code MANIFEST.failed}, that parses with a non-blank {@code pack.stamp}; else null. */
    static PackageManifest madeByThisTool(Path pkg) {
        for (String name : List.of(MANIFEST_REL, FAILED_REL)) {
            Path m = pkg.resolve(name);
            if (!Files.isRegularFile(m, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                PackageManifest read = PackageManifest.read(m);
                String stamp = read.meta.get(OpsRoots.PACK_STAMP);
                if (stamp != null && !stamp.isBlank()) {
                    return read;
                }
            } catch (IOException | IllegalArgumentException malformed) {
                QUIET_FAILURES.incrementAndGet(); // not this tool's: the R3 line records it on a non-empty folder
            }
        }
        return null;
    }

    /** Any entry but the staging lock file. */
    static boolean nonEmpty(Path pkg) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(pkg)) {
            for (Path e : ds) {
                if (!e.getFileName().toString().equals(ServerOpsLaunch.STAGE_LOCK_FILE)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean inOwnTree(String rel) {
        for (String t : OWN_TREES) {
            if (rel.startsWith(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The routed-path law: printable ASCII and {@link PackageManifest#safeRelative}, plus
     * {@link #portableRel} when {@code portable} (PACKED / SOURCE). ONE predicate for a routed path
     * ({@link Planner#grammarPhase}) and a base entry name ({@link Planner#baseNamesCheck}), so a name the
     * grammar would refuse never reaches {@code pkg.resolve} first.
     */
    static boolean listable(String rel, boolean portable) {
        return printableAscii(rel) && PackageManifest.safeRelative(rel) && (!portable || portableRel(rel));
    }

    static boolean portableRel(String rel) {
        if (!ROW_PATH.matcher(rel).matches()) {
            return false;
        }
        for (String seg : rel.split("/")) {
            if (BAD_SEGMENT.matcher(seg).find()) {
                return false;
            }
        }
        return true;
    }

    static boolean printableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static boolean printableLines(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\n' && (c < 0x20 || c > 0x7E)) {
                return false;
            }
        }
        return true;
    }

    /** S4 re-asserted on the bytes a SEED match file is written from. */
    private static boolean redacted(byte[] match) {
        return MatchDescriptors.isStrictlyRedacted(
                Arrays.asList(new String(match, StandardCharsets.ISO_8859_1).split("\r?\n", -1)));
    }

    private static List<String> fileRows(byte[] sidecar) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : new String(sidecar, StandardCharsets.ISO_8859_1).split("\r?\n")) {
            if (line.startsWith("file=")) {
                String rel = line.substring("file=".length());
                if (PackageManifest.safeRelative(rel) && seen.add(rel)) {
                    out.add(rel);
                }
            }
        }
        return out;
    }

    private static String fingerprintOrNull(byte[] sidecar) {
        try {
            return ContentFingerprints.parseSidecarFingerprint(
                    Arrays.asList(new String(sidecar, StandardCharsets.ISO_8859_1).split("\r?\n", -1)), "the match sidecar");
        } catch (IllegalArgumentException noRow) {
            QUIET_FAILURES.incrementAndGet(); // R5 (a placed sidecar) or the FINGERPRINT_NOTE (a kept one) records it
            return null;
        }
    }

    private static void put(ZipOutputStream zos, String name, byte[] bytes) throws IOException {
        if (bytes == null) {
            return;
        }
        zos.putNextEntry(new ZipEntry(name));
        zos.write(bytes);
        zos.closeEntry();
    }

    private static byte[] readBounded(Path p, int cap) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] b = in.readNBytes(cap + 1);
            if (b.length > cap) {
                throw new FileSystemException(p.toString(), null, "over " + cap + " bytes");
            }
            return b;
        }
    }

    private static byte[] readCapped(Path p, int cap) {
        try {
            return readBounded(p, cap);
        } catch (IOException unreadable) {
            QUIET_FAILURES.incrementAndGet(); // the caller treats null as "differs" / "no fingerprint" and says so
            return null;
        }
    }

    private static String relOf(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private static String leaf(String rel) {
        return rel.substring(rel.lastIndexOf('/') + 1);
    }

    private static String firstFew(List<String> names) {
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < Math.min(6, names.size()); i++) {
            shown.add(mask(names.get(i)));
        }
        return String.join(", ", shown) + (names.size() > 6 ? " (+" + (names.size() - 6) + " more)" : "");
    }

    private static String reason(Throwable t) {
        if (t instanceof IllegalArgumentException || t instanceof IllegalStateException) {
            return mask(t.getMessage());
        }
        if (t instanceof FileSystemException fse && fse.getReason() != null) {
            return t.getClass().getSimpleName() + " " + mask(fse.getReason());
        }
        return t.getClass().getSimpleName();
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    static String mask(Object o) {
        return PackageManifest.mask(o == null ? null : o.toString());
    }

    static String fmt(String template, Object... args) {
        return String.format(Locale.ROOT, template, args);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every Java runtime ships SHA-256", impossible);
        }
    }

    private static void closeQuietly(FileChannel ch) {
        if (ch == null) {
            return;
        }
        try {
            ch.close();
        } catch (IOException closeFailed) {
            QUIET_FAILURES.incrementAndGet();
        }
    }

    private static void closeBase(PackageBaseZip b) {
        if (b == null) {
            return;
        }
        try {
            b.close();
        } catch (IOException closeFailed) {
            QUIET_FAILURES.incrementAndGet();
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException stuck) {
            QUIET_FAILURES.incrementAndGet();
        }
    }

    private static void deleteTreeQuietly(Path root) {
        if (root == null) {
            return;
        }
        try (java.util.stream.Stream<Path> all = Files.walk(root)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException stuck) {
            QUIET_FAILURES.incrementAndGet();
        }
    }

}
