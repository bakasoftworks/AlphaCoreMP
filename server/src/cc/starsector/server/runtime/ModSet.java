package cc.starsector.server.runtime;

import cc.starsector.server.match.ContentFingerprints;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

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
 * {@code presented_fold=vtab_only|other} beside {@code server_modset=}). Policy = the required set
 * plus ANY subset of the serve's published optional set: the door holds one lawful session print
 * per subset ({@link #lawfulSubsets}), each folded from the serve's own optional records, so a
 * joiner may run any of the optional mods or none, while a mod the serve never published, or a
 * same-id copy with another version, jar or data, folds to a print outside the table. The fold
 * recipe is unchanged.
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
    /**
     * The cap on the published OPTIONAL set ({@code mods.optional}). WHY: the door's lawful table
     * holds 2^k session prints ({@link #lawfulSubsets}) - 8 gives 256, worst case about 21 MB of md5
     * once at boot, while 16 would give 65,536 and up to 5.4 GB. A host can tick a mod optional
     * without the compatibility review an admitted optional mod gets ({@code OpsModsPanel}), so the
     * cap also bounds how far a host relaxes the door. Server-local: no joiner reads it, so raising
     * it needs no kit roll.
     */
    public static final int MAX_OPTIONAL = 8;
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

    /*
     * The mods ladder's rung 5, the client-optional lane: the serve PUBLISHES a second class of record —
     * presentation-only mods a joiner MAY run (the {@code mods.optional} row) — but never loads them
     * (GraphicsLib refuses a GL-less JVM at {@code onApplicationLoad}).
     * They ride the probe as {@code MARK_MODSET_OPTIONAL} rows, and the door holds the LAWFUL-SUBSET
     * table ({@link #lawfulSubsets}, built ONCE at {@link #installOptional}): one session print per
     * subset of the optional records, so a joiner whose enabled set is the required set plus any
     * subset of these exact records (same version, jars and data) is admitted and nothing else is.
     * WHY server-side: an id list the joiner reads to drop mods from its own fold trusts
     * the id (a same-id foreign mod rides through it), while a print in this table binds every
     * record it folds. Cost = the boot
     * build plus one set lookup per HELLO. The in-game client folds its FULL enabled set on every
     * boot ({@code ClientModSetBoot}); the id-list sidecar that once dropped the staged optional ids
     * from a kit boot's fold was deleted with this table (the ids it named
     * were never cross-checked, so any extra mod rode through it).
     */
    /** The kit-owned mods root under the install ({@code JoinModStage.ROOT_REL} = this): where the join
     * page stages the server's mods; the fold reads nothing from it - a staged mod folds like any other. */
    public static final String KIT_MODS_ROOT_REL = "saves/common/cc_mods";

    private static volatile ModSet installedOptional;
    /** The boot-built lawful-subset table over (installed, installedOptional); null = none installed. */
    private static volatile Map<String, List<String>> installedLawful;

    /**
     * Install the published optional set (idempotent for an EQUAL set) and, when the required set is
     * installed, build the door's lawful-subset table ONCE ({@link #installedLawfulSubsetsOrNull}).
     * THROWS - and the boot caller lets the throw refuse the boot, a set knob is never silently
     * inert - when the set is over {@link #MAX_OPTIONAL}, when an id is also in the installed
     * required set (a mod is required or optional, never both), or when required plus optional is
     * over {@link #MAX_MODS}. Every check runs before anything is installed.
     *
     */
    public static void installOptional(ModSet set) {
        Objects.requireNonNull(set, "set");
        if (set.size() > MAX_OPTIONAL) {
            throw new IllegalArgumentException("optional mod-set: " + set.size() + " ids - over the MAX_OPTIONAL cap of "
                    + MAX_OPTIONAL + " (mods.optional)");
        }
        ModSet required = installed;
        if (required != null) {
            requireDisjoint(required, set);
        }
        ModSet current = installedOptional;
        if (current != null && !current.digest.equals(set.digest)) {
            throw new IllegalStateException("optional mod-set already installed with " + current.census()
                    + "; refusing a different set (" + set.census() + ")");
        }
        Map<String, List<String>> lawful = installedLawful;
        if (required != null && lawful == null) {
            lawful = lawfulSubsets(required, set);
        }
        installedOptional = set;
        installedLawful = lawful;
    }

    /** The installed optional set, or null (no {@code mods.optional} row, rigs, tests). */
    public static ModSet installedOptionalOrNull() {
        return installedOptional;
    }

    /** The boot-built lawful-subset table ({@link #lawfulSubsets} over the installed required and
     * optional sets), or null: no {@code mods.optional} row, rigs, tests. Read once per door open. */
    public static Map<String, List<String>> installedLawfulSubsetsOrNull() {
        return installedLawful;
    }

    /**
     * The door's lawful-subset table: for EVERY subset S of {@code optionalOrNull}, the digest of
     * {@code of(required + S)} mapped to S's ids (ascending), in mask order - bit i = the i-th record
     * of the optional set's own sorted order, so mask 0 (S empty) is first and its key is
     * {@code required.digest()}. WHY: the joiner's print folds the mod set it presents, so a lawful
     * joiner presents exactly one of these 2^k digests; each is built through {@link #of}'s sorting
     * from the serve's own optional records - the record recipe the in-game client uses - so an
     * optional id that sorts between required ids lands where the client puts it. Null or empty optional = the one-entry
     * table {required digest: no ids}. PURE; THROWS when the sets share an id, when k is over
     * {@link #MAX_OPTIONAL}, or when required plus optional is over {@link #MAX_MODS} (checked before
     * any digest).
     */
    public static Map<String, List<String>> lawfulSubsets(ModSet required, ModSet optionalOrNull) {
        Objects.requireNonNull(required, "required");
        Map<String, List<String>> table = new LinkedHashMap<String, List<String>>();
        if (optionalOrNull == null || optionalOrNull.size() == 0) {
            table.put(required.digest(), Collections.<String>emptyList());
            return Collections.unmodifiableMap(table);
        }
        requireDisjoint(required, optionalOrNull);
        int k = optionalOrNull.size();
        if (k > MAX_OPTIONAL) {
            throw new IllegalArgumentException("optional mod-set: " + k + " ids - over the MAX_OPTIONAL cap of "
                    + MAX_OPTIONAL);
        }
        if (required.size() + k > MAX_MODS) {
            throw new IllegalArgumentException("mod-set: " + required.size() + " required + " + k
                    + " optional mods - over the " + MAX_MODS + " cap");
        }
        List<Record> optional = optionalOrNull.records();
        for (int mask = 0; mask < (1 << k); mask++) {
            List<Record> union = new ArrayList<Record>(required.size() + k);
            union.addAll(required.records());
            List<String> ids = new ArrayList<String>(Integer.bitCount(mask));
            for (int i = 0; i < k; i++) {
                if ((mask & (1 << i)) != 0) {
                    union.add(optional.get(i));
                    ids.add(optional.get(i).id());
                }
            }
            String digest = of(union).digest();
            if (table.put(digest, Collections.unmodifiableList(ids)) != null) {
                // two distinct subsets with one digest = an md5 collision; never arm a short table
                throw new IllegalStateException("mod-set: two optional subsets share digest " + digest);
            }
        }
        return Collections.unmodifiableMap(table);
    }

    /** The lawful table's digests in mask order (mask 0 = {@code required.digest()} first). */
    public static List<String> lawfulDigests(ModSet required, ModSet optionalOrNull) {
        return new ArrayList<String>(lawfulSubsets(required, optionalOrNull).keySet());
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

}
