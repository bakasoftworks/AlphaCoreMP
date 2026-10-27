package cc.starsector.server.match;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * D4.7 B1 content-fingerprint contract — the ONE
 * digest recipe every producer implements, so the UDP door's exact string compare is meaningful:
 *
 * <pre>cc1;jar=&lt;jarMd5&gt;;data=&lt;dataMd5&gt;;hs=&lt;HANDSHAKE_VERSION&gt;;match=&lt;match.id&gt;</pre>
 *
 * <ul>
 *   <li>{@code jarMd5} = md5 of the client-mod jar bytes (the same value the kit's
 *       {@code join-cc.bat} certutil hard-stop checks).</li>
 *   <li>{@code dataMd5} = md5 over EXACTLY the engine-loaded content surface — a WHITELIST of
 *       {@code mod_info.json} + every regular file under {@code data/**} except
 *       {@code data/missions/mission_list.csv} (kit tooling, per-user JoinGui persistence, and
 *       kit-transform-skewed files live inside the mod root and must NEVER fork the value;
 *       enumeration law + the mission_list exclusion: {@link ContentFingerprintTree});
 *       files in ordinal order of their {@code /}-separated relative paths, digest input per
 *       file = UTF-8 path bytes + {@code 0x00} + file bytes.</li>
 *   <li>md5 because this is content IDENTITY, not security (matches all existing
 *       content-identity tooling); hex is lowercase.</li>
 * </ul>
 *
 * <p>This class is PURE (strings/bytes in, strings out — no file or socket IO) so the client-mod
 * jar can bundle it sandbox-clean (the in-game script classloader denies most of {@code java.io};
 * IO adapters live caller-side: {@link ContentFingerprintTree} server-side, the mod's allowlisted
 * reader client-side at B1b). Goldens pin the recipe in {@code ServerContractTests} — any drift
 * here is a contract break, not a refactor.
 */
public final class ContentFingerprints {

    /** Composite format tag — the extension point (a knob-digest component rides a {@code cc2}
     * rev if two-sided knob drift ever needs door enforcement). */
    public static final String FORMAT_PREFIX = "cc1";
    /** The HELLO codec's fingerprint field cap — the mint-time assert bound. */
    public static final int COMPOSITE_MAX_CHARS = 128;
    /** The variant id-table digest's format tag. */
    public static final String VTAB_FORMAT_PREFIX = "cc-vtab1";
    /** The composite component the id-table digest FOLDS into (the 128-char cap leaves no room
     * for a fifth component: {@code cc1} + the standing 33-char match id = 124 chars). */
    private static final String DATA_COMPONENT = ";data=";

    /**
     * The {@code match=} word a kit presents when it holds NO staged match document: the kit's
     * print still has a full composite shape (jar, data and hs are real), only the match term says
     * "none yet". A real {@code match.id} may never be this word (the document laws refuse it), so
     * the door can never mistake a document-less kit for a match.
     */
    public static final String MATCH_NONE = "none";

    /** The composite's {@code ;match=} separator ({@link #composite} writes it last). */
    private static final String MATCH_COMPONENT = ";match=";

    private ContentFingerprints() {
    }

    /**
     * True iff {@code composite}'s {@code match=} term is exactly {@link #MATCH_NONE} - a kit that
     * holds no staged document. A null composite or one with no {@code match=} term is false.
     *
     */
    public static boolean isMatchNone(String composite) {
        return MATCH_NONE.equals(componentOrNull(composite, "match"));
    }

    /**
     * {@code composite} without its match term: the text before {@code ;match=}, the composite
     * itself when it carries no such term, null for null. WHY: the pre-admission door compares the
     * CONTENT half of two prints (jar, data, hs) apart from the match id - a kit with no or a stale
     * document still proves it runs the same content, and the match term is then judged on its own.
     *
     */
    public static String stripMatch(String composite) {
        if (composite == null) {
            return null;
        }
        int at = composite.indexOf(MATCH_COMPONENT);
        return at < 0 ? composite : composite.substring(0, at);
    }

    /**
     * The VARIANT ID-TABLE digest both trees compute
     * from the loaded game data at boot — the FULL universe (never a tier subset), FOUR kind-separated
     * sorted id lists, then per hull (hull-table order) its slot order ({@code getAllWeaponSlotsCopy()}
     * order, every slot) and its built-in (slot → weapon) pairs in ordinal slot order. Recipe bytes:
     * the format tag, then per list a u32 count and per string a u16 length + UTF-8 bytes. Every
     * list MUST be strictly ascending ordinal String order (fail-closed like {@link #dataMd5}: an
     * unsorted or duplicated list forks the digest silently). Folding slot order + built-ins in
     * costs nothing and kills the silent slot-drift and built-in-drift classes (vanilla game data is
     * NOT under {@code data=}, so a game-version skew is only caught here).
     */
    public static String variantTableMd5(List<String> hulls, List<String> weapons,
            List<String> hullMods, List<String> wings, Map<String, List<String>> slotOrderByHull,
            Map<String, Map<String, String>> builtInWeaponsByHull) {
        MessageDigest md5 = newMd5();
        md5.update(VTAB_FORMAT_PREFIX.getBytes(StandardCharsets.US_ASCII));
        updateSortedList(md5, "hull", hulls);
        updateSortedList(md5, "weapon", weapons);
        updateSortedList(md5, "hullmod", hullMods);
        updateSortedList(md5, "wing", wings);
        for (String hull : hulls) {
            List<String> slots = slotOrderByHull.get(hull);
            updateCount(md5, slots == null ? 0 : slots.size());
            if (slots != null) {
                for (String slot : slots) {
                    updateString(md5, slot);
                }
            }
            Map<String, String> builtIns = builtInWeaponsByHull.get(hull);
            TreeMap<String, String> sorted = builtIns == null ? new TreeMap<>() : new TreeMap<>(builtIns);
            updateCount(md5, sorted.size());
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                updateString(md5, e.getKey());
                updateString(md5, e.getValue());
            }
        }
        return hex(md5.digest());
    }

    /** The folded {@code data=} value: {@code md5(dataMd5 + "|" + vtab)} — same 32-hex width, so the
     * composite length (and the 128-char cap arithmetic) is unchanged. */
    public static String foldedDataMd5(String dataMd5, String vtab) {
        requireComponent("dataMd5", dataMd5);
        requireComponent("vtab", vtab);
        return md5Hex((dataMd5 + "|" + vtab).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * The mod-set pin: the folded {@code data=} value with the
     * boot MOD-SET digest ({@code cc.starsector.server.runtime.ModSet#digest}) beside the table —
     * {@code md5(dataMd5 + "|" + vtab + "|" + modset)}, same 32-hex width. A joiner whose enabled
     * mod set differs from the serve's (missing / extra / version- or jar-skewed) folds differently
     * and is refused at the door; the composite's length and the 128-char cap arithmetic are unchanged.
     */
    public static String foldedDataMd5(String dataMd5, String vtab, String modset) {
        requireComponent("dataMd5", dataMd5);
        requireComponent("vtab", vtab);
        requireComponent("modset", modset);
        return md5Hex((dataMd5 + "|" + vtab + "|" + modset).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Rewrite a composite's {@code data=} component to its {@link #foldedDataMd5} fold. THROWS on a
     * composite without exactly the {@code ;data=<v>;} shape (a sidecar row is owner-authored — a
     * malformed one never arms a half-folded door). Length-preserving.
     */
    public static String foldVariantTable(String composite, String vtab) {
        return foldContent(composite, vtab, null);
    }

    /**
     * The THREE-way fold ({@code data=} over vtab AND the mod-set digest) — what every
     * in-game print and every engine-backed door present since rung 1; {@code modsetOrNull} null =
     * the vtab-only fold ({@link #foldVariantTable}: rigs and contract rows with no boot mod set).
     * Same shape law and length preservation as the vtab fold.
     */
    public static String foldContent(String composite, String vtab, String modsetOrNull) {
        if (composite == null) {
            throw new IllegalArgumentException("composite must be non-null for the vtab fold");
        }
        int start = composite.indexOf(DATA_COMPONENT);
        if (start < 0) {
            throw new IllegalArgumentException("composite carries no data= component to fold the"
                    + " variant table into: " + composite);
        }
        int valueStart = start + DATA_COMPONENT.length();
        int end = composite.indexOf(';', valueStart);
        if (end < 0) {
            throw new IllegalArgumentException("composite data= component is not ';'-terminated: "
                    + composite);
        }
        String data = composite.substring(valueStart, end);
        String folded = modsetOrNull == null ? foldedDataMd5(data, vtab) : foldedDataMd5(data, vtab, modsetOrNull);
        return composite.substring(0, valueStart) + folded + composite.substring(end);
    }

    /** The value of one {@code key=} component of a composite, or null when absent (door log use). */
    public static String componentOrNull(String composite, String key) {
        if (composite == null) {
            return null;
        }
        String marker = ";" + key + "=";
        int start = composite.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int valueStart = start + marker.length();
        int end = composite.indexOf(';', valueStart);
        return end < 0 ? composite.substring(valueStart) : composite.substring(valueStart, end);
    }

    private static void updateSortedList(MessageDigest md5, String kind, List<String> ids) {
        if (ids == null) {
            throw new IllegalArgumentException("variantTableMd5 " + kind + " list must be non-null");
        }
        updateCount(md5, ids.size());
        String prev = null;
        for (String id : ids) {
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("variantTableMd5 " + kind + " list carries a"
                        + " null/empty id");
            }
            if (prev != null && prev.compareTo(id) >= 0) {
                throw new IllegalArgumentException("variantTableMd5 " + kind + " list must be strictly"
                        + " ascending ordinal order (got '" + prev + "' then '" + id
                        + "') — an unsorted/duplicated list forks the digest");
            }
            updateString(md5, id);
            prev = id;
        }
    }

    private static void updateCount(MessageDigest md5, int count) {
        md5.update((byte) (count >>> 24));
        md5.update((byte) (count >>> 16));
        md5.update((byte) (count >>> 8));
        md5.update((byte) count);
    }

    private static void updateString(MessageDigest md5, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) {
            throw new IllegalArgumentException("variantTableMd5 string over the u16 length bound: "
                    + bytes.length + " B");
        }
        md5.update((byte) (bytes.length >>> 8));
        md5.update((byte) bytes.length);
        md5.update(bytes);
    }

    /**
     * Compose the door composite. THROWS (mint-time assert) when a component is
     * blank, carries a separator/whitespace, or the composite would overflow the
     * {@link #COMPOSITE_MAX_CHARS} HELLO codec cap — a packer minting an overflowing
     * {@code match.id} must fail loud, never truncate.
     */
    public static String composite(String jarMd5, String dataMd5, int handshakeVersion,
            String matchId) {
        requireComponent("jarMd5", jarMd5);
        requireComponent("dataMd5", dataMd5);
        requireComponent("matchId", matchId);
        String composed = FORMAT_PREFIX + ";jar=" + jarMd5 + ";data=" + dataMd5
                + ";hs=" + handshakeVersion + ";match=" + matchId;
        if (composed.length() > COMPOSITE_MAX_CHARS) {
            throw new IllegalArgumentException("content-fingerprint composite is "
                    + composed.length() + " chars — over the " + COMPOSITE_MAX_CHARS
                    + " HELLO codec cap (shorten match.id; mint-time assert): "
                    + composed);
        }
        return composed;
    }

    /**
     * The {@code dataMd5} recipe over an already-enumerated content surface: keys are
     * {@code /}-separated mod-root-relative paths, natural (ordinal) String order — the map MUST
     * be naturally ordered (fail-closed on a custom comparator, which would silently fork the
     * digest). Digest input per entry = UTF-8 path bytes + {@code 0x00} + file bytes.
     */
    public static String dataMd5(SortedMap<String, byte[]> contentByRelPath) {
        if (contentByRelPath.comparator() != null) {
            throw new IllegalArgumentException("dataMd5 requires natural (ordinal) key order —"
                    + " a custom comparator forks the digest");
        }
        MessageDigest md5 = newMd5();
        for (Map.Entry<String, byte[]> entry : contentByRelPath.entrySet()) {
            String path = entry.getKey();
            if (path.isBlank() || path.indexOf('\\') >= 0 || path.startsWith("/")
                    || path.contains("..")) {
                throw new IllegalArgumentException("dataMd5 paths must be clean /-separated"
                        + " mod-root-relative paths; got '" + path + "'");
            }
            md5.update(path.getBytes(StandardCharsets.UTF_8));
            md5.update((byte) 0);
            md5.update(entry.getValue());
        }
        return hex(md5.digest());
    }

    /** Lowercase-hex md5 of one byte array (the {@code jarMd5} half). */
    public static String md5Hex(byte[] bytes) {
        return hex(newMd5().digest(bytes));
    }

    /**
     * Parse the expected composite out of a descriptor-adjacent sidecar's lines: k=v rows,
     * {@code #} comments and unknown rows tolerated (future rows are allowed), the
     * {@code fingerprint=} row REQUIRED. Fail-closed: missing/blank/over-cap THROWS — an armed
     * door never serves with a half-configured expectation. {@code source} labels the error.
     */
    public static String parseSidecarFingerprint(List<String> lines, String source) {
        String found = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || !"fingerprint".equals(trimmed.substring(0, eq).trim())) {
                continue;
            }
            found = trimmed.substring(eq + 1).trim();
        }
        if (found == null || found.isBlank()) {
            throw new IllegalArgumentException(source + " carries no non-blank fingerprint= row"
                    + " (the armed door's expected composite; sidecar contract)");
        }
        if (found.length() > COMPOSITE_MAX_CHARS) {
            throw new IllegalArgumentException(source + " fingerprint= row is " + found.length()
                    + " chars — over the " + COMPOSITE_MAX_CHARS + " HELLO codec cap (a client"
                    + " could never present it)");
        }
        return found;
    }

    private static void requireComponent(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank for the"
                    + " content-fingerprint composite");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ';' || c == '=' || Character.isWhitespace(c)) {
                throw new IllegalArgumentException(name + " must not carry ';'/'='/whitespace"
                        + " (composite separators); got '" + value + "'");
            }
        }
    }

    /** A fresh MD5 digest (public so the streaming {@code ModDataWalk} shares the ONE hex law). */
    public static MessageDigest newMd5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("MD5 MessageDigest unavailable", impossible);
        }
    }

    /** Lowercase hex of a finished digest (public, see {@link #newMd5}). */
    public static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
