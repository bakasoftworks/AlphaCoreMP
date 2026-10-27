package cc.starsector.server.runtime;

import cc.starsector.server.match.ContentFingerprints;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The mods ladder's rung 1 — the stranger-safe MOD-SET PIN, built on the door's fold
 * idiom, no handshake bump: the ENABLED MOD SET both trees mint at boot from the engine's own
 * launcher {@code ModManager} — every enabled mod EXCEPT {@code cc_client} (already pinned by the
 * composite's {@code jar=}/{@code data=}), one record per mod = its id, its declared version, the
 * md5 over its declared jars' bytes and the md5 over its {@code data/**} files
 * ({@link ModDataWalk}) — and the set's {@link #digest() modset} digest, which the
 * HELLO content fingerprint FOLDS into {@code data=} beside {@code vtab}
 * ({@link ContentFingerprints#foldContent}). Equal sets fold equal; a joiner MISSING a served mod,
 * carrying an EXTRA enabled mod, or running a version- or jar-skewed copy presents a different fold
 * and is refused AT THE DOOR ({@code content_fingerprint_mismatch}, the door log naming
 * {@code presented_fold=vtab_only|other} beside {@code server_modset=}). Policy today =
 * required-IDENTICAL sets (the client-optional relaxation for presentation-only mods is an
 * open design question; it would filter this set by a server-published list,
 * the fold recipe unchanged).
 *
 * <p>PURE by construction ({@code java.util} + {@link ContentFingerprints} only — no file, socket or
 * {@code java.nio.file} reference) so the client-mod jar bundles it sandbox-clean under classloader
 * B; the per-side enumerators are {@code RealEngineModSetFacts} (server: reflective launcher walk +
 * nio jar bytes) and {@code ClientModSetBoot} (in-game: {@code ModManagerAPI} + the measured
 * {@code FileReader} byte lane). ONE class in both trees, never a mirror.
 *
 * <p>Canonical text — ALSO the join page's cache file
 * ({@code saves/common/cc_variants/mod-set.txt.data}, game-written at boot, join-page-read for the
 * VCHK dry-run fold) — is
 * <pre>
 * cc-modset2
 * &lt;id&gt;\t&lt;version&gt;\t&lt;jarsMd5&gt;\t&lt;dataMd5&gt;      (one line per mod, strictly ascending ordinal id order)
 * </pre>
 * every line {@code \n}-terminated; {@code modset = md5(UTF-8 bytes of that text)}. FAIL-CLOSED:
 * an unlawful id ({@code [0-9A-Za-z][0-9A-Za-z._:-]{0,63}}, the kit's {@code :vguard} law), a
 * version over {@value #MAX_VERSION_CHARS} chars or carrying a control character, a jars or data
 * digest that is not 32 lowercase hex, a duplicate id, the excluded id, more than
 * {@value #MAX_MODS} mods, or a NON-canonical text at {@link #parse} all THROW
 * {@link IllegalArgumentException} — a forked or silently-normalised set never folds (the id-table
 * law, {@code ContentFingerprints.updateSortedList}). {@code cc-modset1} (three columns)
 * is REFUSED at parse: a pre-P26 cache reads "checked at launch" and a pre-P26 kit dies at the
 * door — the fold moved, by design (wq P26 "rides a roll").
 */
public final class ModSet {

    /** The canonical text's first line + the digest recipe's format tag (bumped 1 → 2: the data column). */
    public static final String FORMAT_PREFIX = "cc-modset2";
    /** The one mod the set never lists (pinned by {@code jar=}/{@code data=} already). */
    public static final String EXCLUDED_ID = "cc_client";
    /** Sanity cap on the set (a real install enables tens, never hundreds). */
    public static final int MAX_MODS = 256;
    public static final int MAX_ID_CHARS = 64;
    public static final int MAX_VERSION_CHARS = 64;
    /** The common-file NAME the game resolves to {@code <saves>/common/<name>.data} (the join page's cache). */
    public static final String CACHE_COMMON_NAME = "cc_variants/mod-set.txt";
    /** The join page's read guard on the cache file (a set of 256 maximal lines is ~50 KB). */
    public static final int MAX_CACHE_BYTES = 64 * 1024;

    /** One enabled mod's identity: id + declared version + md5 over its declared jars + md5 over its {@code data/**}. */
    public static final class Record {
        private final String id;
        private final String version;
        private final String jarsMd5;
        private final String dataMd5;

        private Record(String id, String version, String jarsMd5, String dataMd5) {
            this.id = id;
            this.version = version;
            this.jarsMd5 = jarsMd5;
            this.dataMd5 = dataMd5;
        }

        /** Validates every field (see the class law); {@code version} null = "" (a mod_info without one);
         * {@code dataMd5} = {@link ModDataWalk#dataMd5} (the empty-md5 for a mod without a {@code data/} tree). */
        public static Record of(String id, String version, String jarsMd5, String dataMd5) {
            if (id == null || id.isEmpty() || id.length() > MAX_ID_CHARS || !lawfulId(id)) {
                throw new IllegalArgumentException("mod-set: unlawful mod id '" + id
                        + "' (law [0-9A-Za-z][0-9A-Za-z._:-]{0," + (MAX_ID_CHARS - 1) + "})");
            }
            if (EXCLUDED_ID.equals(id)) {
                throw new IllegalArgumentException("mod-set: '" + EXCLUDED_ID + "' is never a record (pinned by jar=/data=)");
            }
            String v = version == null ? "" : version;
            if (v.length() > MAX_VERSION_CHARS) {
                throw new IllegalArgumentException("mod-set: mod '" + id + "' version is " + v.length()
                        + " chars — over the " + MAX_VERSION_CHARS + " cap");
            }
            // The version law (pinned by rows): a plain SPACE 0x20 is
            // LAWFUL ("2.8 beta" round-trips, ModSetContractRows M2/M3); what refuses is C0 (tab/LF/CR included —
            // the canonical text's own separators), DEL, C1, and the two Unicode line breaks U+2028/U+2029,
            // written as numerics so no reader mistakes them for spaces again.
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F) || c == 0x2028 || c == 0x2029) {
                    throw new IllegalArgumentException("mod-set: mod '" + id + "' version carries a control"
                            + " character at index " + i);
                }
            }
            if (jarsMd5 == null || !jarsMd5.matches("[0-9a-f]{32}")) {
                throw new IllegalArgumentException("mod-set: mod '" + id + "' jars digest is not 32 lowercase hex");
            }
            if (dataMd5 == null || !dataMd5.matches("[0-9a-f]{32}")) {
                throw new IllegalArgumentException("mod-set: mod '" + id + "' data digest is not 32 lowercase hex");
            }
            return new Record(id, v, jarsMd5, dataMd5);
        }

        public String id() {
            return id;
        }

        public String version() {
            return version;
        }

        public String jarsMd5() {
            return jarsMd5;
        }

        /** The {@code data/**} digest ({@link ModDataWalk#dataMd5}). */
        public String dataMd5() {
            return dataMd5;
        }

        /** The canonical line WITHOUT its terminator. */
        String line() {
            return id + "\t" + version + "\t" + jarsMd5 + "\t" + dataMd5;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Record && ((Record) o).line().equals(line());
        }

        @Override
        public int hashCode() {
            return line().hashCode();
        }

        @Override
        public String toString() {
            return id + "@" + (version.isEmpty() ? "-" : version);
        }
    }

    private final List<Record> records;
    private final String text;
    private final String digest;

    private ModSet(List<Record> sorted) {
        this.records = Collections.unmodifiableList(sorted);
        StringBuilder sb = new StringBuilder(FORMAT_PREFIX).append('\n');
        for (Record r : sorted) {
            sb.append(r.line()).append('\n');
        }
        this.text = sb.toString();
        this.digest = ContentFingerprints.md5Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Build from records in ANY order (sorted here by ordinal id); a duplicate id THROWS. */
    public static ModSet of(List<Record> records) {
        Objects.requireNonNull(records, "records");
        if (records.size() > MAX_MODS) {
            throw new IllegalArgumentException("mod-set: " + records.size() + " mods — over the " + MAX_MODS + " cap");
        }
        TreeMap<String, Record> byId = new TreeMap<String, Record>();
        for (Record r : records) {
            Objects.requireNonNull(r, "record");
            if (byId.put(r.id(), r) != null) {
                throw new IllegalArgumentException("mod-set: duplicate mod id '" + r.id() + "'");
            }
        }
        return new ModSet(new ArrayList<Record>(byId.values()));
    }

    /**
     * Parse the canonical text (the cache file) STRICTLY: the format line, then {@code \n}-terminated
     * four-column tab lines in strictly ascending id order, nothing else — the re-rendered text must
     * equal the input byte for byte (fail-closed against a hand-edited or CRLF-mangled cache).
     */
    public static ModSet parse(String text) {
        if (text == null || text.length() > MAX_CACHE_BYTES) {
            throw new IllegalArgumentException("mod-set: text null or over " + MAX_CACHE_BYTES + " chars");
        }
        if (!text.startsWith(FORMAT_PREFIX + "\n")) {
            throw new IllegalArgumentException("mod-set: text does not start with the '" + FORMAT_PREFIX + "' line");
        }
        if (!text.endsWith("\n")) {
            throw new IllegalArgumentException("mod-set: text is not newline-terminated");
        }
        List<Record> records = new ArrayList<Record>();
        int lineStart = FORMAT_PREFIX.length() + 1;
        String prevId = null;
        while (lineStart < text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            String line = text.substring(lineStart, lineEnd);
            int t1 = line.indexOf('\t');
            int t2 = t1 < 0 ? -1 : line.indexOf('\t', t1 + 1);
            int t3 = t2 < 0 ? -1 : line.indexOf('\t', t2 + 1);
            if (t1 < 0 || t2 < 0 || t3 < 0 || line.indexOf('\t', t3 + 1) >= 0) {
                throw new IllegalArgumentException("mod-set: a line is not four tab-separated columns");
            }
            Record r = Record.of(line.substring(0, t1), line.substring(t1 + 1, t2), line.substring(t2 + 1, t3),
                    line.substring(t3 + 1));
            if (prevId != null && prevId.compareTo(r.id()) >= 0) {
                throw new IllegalArgumentException("mod-set: lines not in strictly ascending id order ('"
                        + prevId + "' then '" + r.id() + "')");
            }
            records.add(r);
            prevId = r.id();
            lineStart = lineEnd + 1;
        }
        ModSet parsed = of(records);
        if (!parsed.text.equals(text)) {
            throw new IllegalArgumentException("mod-set: text is not canonical (re-render differs)");
        }
        return parsed;
    }

    /**
     * The per-mod jars digest: md5 over, for each declared jar IN DECLARATION ORDER, the lowercase-hex
     * md5 of its bytes followed by {@code \n}. No jars = md5 of nothing. Callers supply the bytes
     * (the IO lane differs per side).
     */
    public static String jarsMd5(List<byte[]> jarBytesInOrder) {
        Objects.requireNonNull(jarBytesInOrder, "jarBytesInOrder");
        StringBuilder sb = new StringBuilder();
        for (byte[] jar : jarBytesInOrder) {
            sb.append(ContentFingerprints.md5Hex(Objects.requireNonNull(jar, "jar bytes"))).append('\n');
        }
        return ContentFingerprints.md5Hex(sb.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /** Blank-version label on the wire (a mod_info without a version). */
    public static final String VERSION_LABEL_BLANK = "-";
    /** The listing row's target-label width (mirrors {@code ListingFrameCodec.TARGET_LABEL_BUFFER_BYTES}). */
    public static final int VERSION_LABEL_MAX = 24;

    /**
     * A record's version as the KIND_MODSET probe row carries it — the listing's
     * 24-char printable-ASCII target-label law applied to the declared version: trimmed, every
     * non-printable / non-ASCII char becomes {@code ?}, over 24 chars cut to 24, blank becomes
     * {@link #VERSION_LABEL_BLANK}. BOTH sides compare labels (the join page runs its local scan's
     * version through this same function), so a long or odd version still diffs exactly.
     */
    public static String versionLabel(String version) {
        String v = version == null ? "" : version.trim();
        if (v.isEmpty()) {
            return VERSION_LABEL_BLANK;
        }
        StringBuilder sb = new StringBuilder(Math.min(v.length(), VERSION_LABEL_MAX));
        for (int i = 0; i < v.length() && sb.length() < VERSION_LABEL_MAX; i++) {
            char c = v.charAt(i);
            sb.append(c >= 0x21 && c <= 0x7E ? c : (c == ' ' ? '_' : '?'));
        }
        return sb.toString();
    }

    /** The probe row's hullWireId slot = the jars digest's leading 4
     * bytes (high word) beside the data digest's leading 4 bytes (low word), as a signed long — the
     * join page's "same version, different jars" / "different data files" tells (32 + 32 of the
     * 2 × 128 bits; the door still folds the full digests). Pre-P26 kits read the whole slot as a
     * jars prefix and so diff every mod as "different files" — they are dead at the door anyway. */
    public static long filesPrefix(String jarsMd5, String dataMd5) {
        if (jarsMd5 == null || !jarsMd5.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("mod-set: jars digest is not 32 lowercase hex");
        }
        if (dataMd5 == null || !dataMd5.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("mod-set: data digest is not 32 lowercase hex");
        }
        return Long.parseUnsignedLong(jarsMd5.substring(0, 8) + dataMd5.substring(0, 8), 16);
    }

    /** The jars word of a {@link #filesPrefix} (the high 32 bits). */
    public static int jarsWord(long filesPrefix) {
        return (int) (filesPrefix >>> 32);
    }

    /** The data word of a {@link #filesPrefix} (the low 32 bits). */
    public static int dataWord(long filesPrefix) {
        return (int) filesPrefix;
    }

    public List<Record> records() {
        return records;
    }

    public int size() {
        return records.size();
    }

    /** The canonical text (the cache file's content). */
    public String text() {
        return text;
    }

    /** The 32-hex {@code modset} digest the fingerprint folds. */
    public String digest() {
        return digest;
    }

    /** One line for boot narration: digest, count, the ids. */
    public String census() {
        StringBuilder sb = new StringBuilder("modset=").append(digest).append(" n=").append(records.size()).append(" [");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(records.get(i));
        }
        return sb.append(']').toString();
    }

    @Override
    public String toString() {
        return census();
    }

    /** The mod-id character law (ASCII alnum first, then alnum / {@code . _ : -}) — the ONE
     * home; public so the Ops window's installed-mod scan
     * ({@code InstalledModScan.readModInfo}) refuses an unlawful id at SCAN time, before the Mods
     * tab could write it into {@code cc-server.properties} (a newline inside a foreign
     * {@code mod_info.json} id = a properties row injection). Length is the caller's check
     * ({@link #MAX_ID_CHARS}). */
    public static boolean lawfulId(String id) {
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean alnum = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (i == 0 ? !alnum : !(alnum || c == '.' || c == '_' || c == ':' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ the boot singleton

    private static volatile ModSet installed;

    /** Install the boot set. Idempotent for an EQUAL set; a DIFFERENT set over an installed one THROWS. */
    public static void install(ModSet set) {
        Objects.requireNonNull(set, "set");
        ModSet current = installed;
        if (current != null && !current.digest.equals(set.digest)) {
            throw new IllegalStateException("mod-set already installed with " + current.census()
                    + "; refusing a different set (" + set.census() + ")");
        }
        installed = set;
    }

    /** The installed set, or null when no boot mint ran (rigs, tests, a failed mint). */
    public static ModSet installedOrNull() {
        return installed;
    }

    // ------------------------------------------------------------------ the OPTIONAL set

    /**
     * The mods ladder's rung 5, the client-optional lane: the serve PUBLISHES a second class of record —
     * presentation-only mods a joiner MAY run (the {@code mods.optional} row) — but never loads them
     * (GraphicsLib refuses a GL-less JVM at {@code onApplicationLoad}).
     * They ride the probe as {@code MARK_MODSET_OPTIONAL} rows and are EXCLUDED from the door's fold:
     * the server folds its required set only, and the in-game client ({@code ClientModSetBoot}) drops
     * from its own boot set every id the join page staged as optional — the id list the page writes
     * into the kit-owned mods root ({@link #OPTIONAL_CACHE_COMMON_NAME}, honoured ONLY on a boot from
     * that root, {@link #KIT_MODS_ROOT_REL}) — so both sides fold the same required set. Prefer
     * client-side at equal quality: no server compute beyond the mark.
     */
    public static final String OPTIONAL_FORMAT_PREFIX = "cc-optional1";
    /** The common-file NAME of the staged-optional-ids sidecar the join page writes into the kit's
     * mods root and the game reads back ({@code <saves>/common/cc_mods/optional-mods.txt.data}). */
    public static final String OPTIONAL_CACHE_COMMON_NAME = "cc_mods/optional-mods.txt";
    /** The kit-owned mods root under the install ({@code JoinModStage.ROOT_REL} = this); a boot whose
     * {@code cc_client} spec path lies under it is a KIT boot — the only boot the sidecar binds. */
    public static final String KIT_MODS_ROOT_REL = "saves/common/cc_mods";

    private static volatile ModSet installedOptional;

    /** Install the published optional set (idempotent for an EQUAL set); THROWS when an id is also in
     * the installed required set — a mod is required or optional, never both. */
    public static void installOptional(ModSet set) {
        Objects.requireNonNull(set, "set");
        ModSet required = installed;
        if (required != null) {
            requireDisjoint(required, set);
        }
        ModSet current = installedOptional;
        if (current != null && !current.digest.equals(set.digest)) {
            throw new IllegalStateException("optional mod-set already installed with " + current.census()
                    + "; refusing a different set (" + set.census() + ")");
        }
        installedOptional = set;
    }

    /** The installed optional set, or null (no {@code mods.optional} row, rigs, tests). */
    public static ModSet installedOptionalOrNull() {
        return installedOptional;
    }

    /** THROWS when the two sets share an id (the required-or-optional law). Pure, for the contract rows. */
    public static void requireDisjoint(ModSet required, ModSet optional) {
        for (Record r : optional.records()) {
            for (Record q : required.records()) {
                if (q.id().equals(r.id())) {
                    throw new IllegalArgumentException("mod-set: '" + r.id() + "' is listed as REQUIRED and"
                            + " OPTIONAL — a mod is one or the other (mods.enabled vs mods.optional)");
                }
            }
        }
    }

    /** This set WITHOUT the named ids (absent ids are ignored) — the client's fold input on a kit boot. */
    public ModSet excluding(Collection<String> ids) {
        Objects.requireNonNull(ids, "ids");
        List<Record> kept = new ArrayList<Record>(records.size());
        for (Record r : records) {
            if (!ids.contains(r.id())) {
                kept.add(r);
            }
        }
        return kept.size() == records.size() ? this : of(kept);
    }

    /** The sidecar text: the format line, then one lawful id per line in strictly ascending order,
     * every line {@code \n}-terminated; {@code cc_client} or an unlawful id THROWS. */
    public static String optionalIdsText(Collection<String> ids) {
        Objects.requireNonNull(ids, "ids");
        TreeSet<String> sorted = new TreeSet<String>();
        for (String id : ids) {
            if (id == null || id.isEmpty() || id.length() > MAX_ID_CHARS || !lawfulId(id) || EXCLUDED_ID.equals(id)) {
                throw new IllegalArgumentException("optional mod-set: unlawful id '" + id + "'");
            }
            sorted.add(id);
        }
        if (sorted.size() > MAX_MODS) {
            throw new IllegalArgumentException("optional mod-set: " + sorted.size() + " ids — over the " + MAX_MODS + " cap");
        }
        StringBuilder sb = new StringBuilder(OPTIONAL_FORMAT_PREFIX).append('\n');
        for (String id : sorted) {
            sb.append(id).append('\n');
        }
        return sb.toString();
    }

    /** Parse the sidecar STRICTLY (re-render must equal the input); null/oversize/unlawful THROWS. */
    public static List<String> parseOptionalIds(String text) {
        if (text == null || text.length() > MAX_CACHE_BYTES) {
            throw new IllegalArgumentException("optional mod-set: text null or over " + MAX_CACHE_BYTES + " chars");
        }
        if (!text.startsWith(OPTIONAL_FORMAT_PREFIX + "\n") || !text.endsWith("\n")) {
            throw new IllegalArgumentException("optional mod-set: not a '" + OPTIONAL_FORMAT_PREFIX
                    + "' text with a final newline");
        }
        List<String> ids = new ArrayList<String>();
        int lineStart = OPTIONAL_FORMAT_PREFIX.length() + 1;
        while (lineStart < text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            ids.add(text.substring(lineStart, lineEnd));
            lineStart = lineEnd + 1;
        }
        if (!optionalIdsText(ids).equals(text)) {
            throw new IllegalArgumentException("optional mod-set: text is not canonical (re-render differs)");
        }
        return ids;
    }
}
