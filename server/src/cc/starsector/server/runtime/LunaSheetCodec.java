package cc.starsector.server.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

/**
 * The LunaSettings sheet's TEXT law, shared by the serve (mints the canonical sheet once at boot, the
 * {@code mod_set} census moment) and the kit (decodes the sheet it staged beside {@code match.txt} and applies
 * it at the top of {@code PredictReconcileCombatPlugin.init}): one grammar, one digest, one set of caps, so a
 * row the serve could not mint is a row the kit refuses, and the sixteen-hex digest the modset listing announces
 * ({@code luna=<digest16>:<count>}) is computed by the SAME code on both ends.
 *
 * <p><b>Canonical text</b> (UTF-8, LF-terminated lines, no trailing bytes):
 * <pre>
 * luna.v=1
 * luna.count=N
 * luna.&lt;i&gt;=&lt;modId&gt;\t&lt;fieldId&gt;\t&lt;T&gt;\t&lt;value&gt;      (i = 0..N-1, in order)
 * </pre>
 * Rows are SORTED by (modId, fieldId) in byte order — LunaLib's own {@code getSettingsData()} order follows a
 * display-name sort plus directory order, unpinned across filesystems, so the digest would otherwise differ
 * between two honest serves. Tab-separated so a value may hold {@code =}; a value never holds a tab
 * or a newline because {@link WireTextLaw#isPrintableWireChar} refuses every C0 control.
 *
 * <p><b>Type letters</b>: {@code B} = {@code true|false}; {@code I} = a canonical decimal int32 (no leading
 * zero, no {@code -0}, no sign but {@code -}); {@code D} = a FINITE canonical decimal ({@code -?digits(.digits)?
 * ([eE][+-]?digits)?} — org.json's {@code put} throws on NaN/Inf, which would break an apply half-way);
 * {@code S} = single-line printable wire text, no lone surrogate (a lone surrogate cannot survive the
 * UTF-8 file round trip the kit reads it through, so both digests would disagree), at most {@link #VALUE_MAX}
 * chars. Color and Radio ride as {@code S}; Keycode never rides: a keybind is per-player.
 *
 * <p><b>Decode law</b>: the count is checked BEFORE the rows, indices run 0..N-1 in order, every field is
 * judged by its own law, the sort order is enforced (a duplicate or a mis-ordered pair refuses), the mod and
 * per-mod caps hold, and a trailing or an unterminated line refuses — the WHOLE sheet on any breach.
 * A refusal is a class WORD plus our own index, never the offending text ({@link Decoded#refusal}); {@link #decode}
 * never throws. {@link #canonicalText} is the mint: it sorts, applies the same laws and THROWS
 * {@code IllegalArgumentException} naming the class and the row, so the serve drops the whole sheet at boot
 * (the host sees {@code luna-sync dropped=...}), and it self-checks by decoding its own output.
 *
 * <p><b>Carrier</b>: the sheet rides the password-proven PROBE reply as a SECOND sealed document beside DESC —
 * {@code MatchDocFrameCodec} parameterized by kind — under its own {@link #MAGIC}, its own wire type
 * {@link #MESSAGE_ID} / {@link #WIRE_TYPE_CODE} (the registry's CRC32 law, computed locally like DESC's) and its
 * own HKDF {@link #LABEL} (the AAD covers only the 24 B chunk header, so the label split is what keeps a LUNA
 * chunk from opening under the DESC opener). Probe-only: it joins no in-session frame family.
 *
 * <p>Shared: bundled into the client mod jar and run under the game's script classloader — java.base plus
 * {@link WireTextLaw} and {@link ModSet#lawfulId} only (both already kit-folded); no stream IO, no regex, no
 * reflection. {@code MessageDigest} is the same java.security seam {@code HandshakeCrypto} runs in the kit at
 * every probe.
 */
public final class LunaSheetCodec {

    /** Frame magic {@code "LUNA"}: the sheet's own document kind beside {@code MatchDocFrameCodec.MAGIC} ("DESC"). */
    public static final int MAGIC = 0x4C554E41; // "LUNA"
    /** Twin of the server-only registry entry {@code LunaSheetMessage.MESSAGE_ID}; a contract row pins equality. */
    public static final String MESSAGE_ID = "server.luna_sheet";
    /** CRC32 of {@link #MESSAGE_ID}'s UTF-8 bytes - the registry's wire-type law, computed locally. */
    public static final int WIRE_TYPE_CODE;
    /** The HKDF info label of a LUNA chunk's seal; DESC's is {@code "cc-hs14 match doc"} — never the same key. */
    public static final String LABEL = "cc-hs17 luna sheet";
    /** The sheet grammar version ({@code luna.v}). */
    public static final int VERSION = 1;

    /** The sheet text's UTF-8 size at most (binds first: a max-size row is about 262 B). */
    public static final int TEXT_MAX = 16384;
    /** The raw-deflated sheet at most ({@code CHUNKS_MAX * 1104}, the document chunk law). */
    public static final int DEFLATED_MAX = 8832;
    /** Chunks per reply at most. */
    public static final int CHUNKS_MAX = 8;
    /** Rows per sheet at most. */
    public static final int ROWS_MAX = 256;
    /** Distinct mods per sheet at most. */
    public static final int MODS_MAX = 32;
    /** Rows per mod at most. */
    public static final int ROWS_PER_MOD_MAX = 64;
    /** A modId's or a fieldId's length at most ({@code ModSet.MAX_ID_CHARS}). */
    public static final int ID_MAX = 64;
    /** A value's length at most, in chars, after {@code String.valueOf} (over = the serve skips the row, never truncates). */
    public static final int VALUE_MAX = 120;

    public static final char TYPE_BOOLEAN = 'B';
    public static final char TYPE_INT = 'I';
    public static final char TYPE_DOUBLE = 'D';
    public static final char TYPE_STRING = 'S';

    /** The modset listing's {@code luna=} term when the serve holds no sheet. */
    public static final String NO_SHEET_TERM = "-:0";

    /**
     * The announce row's token on the probe reply's modset listing: a reserved FIRST row whose token starts with
     * {@code '~'} — token-lawful (spaceless printable ASCII) but never a mod id (an id starts alphanumeric,
     * {@code ModSet#lawfulId}), so it collides with no served mod. Its version label carries
     * {@link #announceTerm} (or {@link #NO_SHEET_TERM}); the kit strips the row before the mod diff. Sent by every
     * password-proven reply at handshake 17, a set-less door included, so a lost sheet chunk reads
     * {@code refused:incomplete} and never "no sheet": a lost sheet is reported as lost, never passed off as none.
     */
    public static final String ANNOUNCE_TOKEN = "~luna";

    private static final String LINE_VERSION = "luna.v=" + VERSION;
    private static final String PREFIX_COUNT = "luna.count=";
    private static final String PREFIX_ROW = "luna.";
    private static final int DIGEST_BYTES = 8;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static {
        byte[] id = MESSAGE_ID.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(id, 0, id.length);
        WIRE_TYPE_CODE = (int) crc.getValue();
    }

    private LunaSheetCodec() {
    }

    /** One pushed setting: the serve's effective value of {@code fieldId} declared by {@code modId}, typed by one letter. */
    public record Row(String modId, String fieldId, char type, String value) {
    }

    /** A decoded sheet: {@code rows} (unmodifiable, in canonical order) when {@code refusal} is null, else the class word. */
    public record Decoded(List<Row> rows, String refusal) {
        public boolean ok() {
            return refusal == null;
        }
    }

    /** The modset listing's {@code luna=} term for a minted sheet: {@code <digest16>:<rows>}. */
    public static String announceTerm(String canonicalText, int rows) {
        return digest16(canonicalText) + ":" + rows;
    }

    /**
     * The canonical sheet text for {@code rows} (any order; sorted here). Throws {@code IllegalArgumentException}
     * naming the breached law and the row ({@code "value row=3"}) — never the text — on any breach: the serve
     * drops the WHOLE sheet. An empty list mints a zero-row sheet (the caller decides whether to send one).
     */
    public static String canonicalText(List<Row> rows) {
        if (rows == null) {
            throw new IllegalArgumentException("rows");
        }
        if (rows.size() > ROWS_MAX) {
            throw new IllegalArgumentException("rows count=" + rows.size());
        }
        List<Row> sorted = new ArrayList<>(rows);
        for (int i = 0; i < sorted.size(); i++) {
            Row r = sorted.get(i);
            if (r == null) {
                throw new IllegalArgumentException("row row=" + i);
            }
            String law = rowLawOrNull(r.modId(), r.fieldId(), r.type(), r.value());
            if (law != null) {
                throw new IllegalArgumentException(law + " row=" + i);
            }
        }
        sorted.sort(LunaSheetCodec::compareRows);
        StringBuilder sb = new StringBuilder(64 + sorted.size() * 48);
        sb.append(LINE_VERSION).append('\n');
        sb.append(PREFIX_COUNT).append(sorted.size()).append('\n');
        String prevMod = null;
        int mods = 0;
        int perMod = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Row r = sorted.get(i);
            if (i > 0 && compareRows(sorted.get(i - 1), r) == 0) {
                throw new IllegalArgumentException("duplicate row=" + i);
            }
            if (!r.modId().equals(prevMod)) {
                prevMod = r.modId();
                mods++;
                perMod = 0;
                if (mods > MODS_MAX) {
                    throw new IllegalArgumentException("mods row=" + i);
                }
            }
            perMod++;
            if (perMod > ROWS_PER_MOD_MAX) {
                throw new IllegalArgumentException("per_mod row=" + i);
            }
            sb.append(PREFIX_ROW).append(i).append('=')
                    .append(r.modId()).append('\t').append(r.fieldId()).append('\t').append(r.type()).append('\t')
                    .append(r.value()).append('\n');
        }
        String text = sb.toString();
        if (utf8Length(text) > TEXT_MAX) {
            throw new IllegalArgumentException("oversize bytes=" + utf8Length(text));
        }
        Decoded check = decode(text);
        if (!check.ok()) {
            // the mint and the decode law drifted apart: loud at boot, never a sheet the kit refuses silently
            throw new IllegalStateException("luna sheet self-check " + check.refusal());
        }
        return text;
    }

    /**
     * Decode a sheet text under the whole decode law; never throws. A refusal names the class word (and our own index),
     * never the text: {@code empty, oversize, truncated, trailing, version, count, index, fields, mod, field,
     * type, value, duplicate, order, mods, per_mod}.
     */
    public static Decoded decode(String text) {
        if (text == null || text.isEmpty()) {
            return refused("empty");
        }
        if (text.length() > TEXT_MAX || utf8Length(text) > TEXT_MAX) {
            return refused("oversize");
        }
        if (text.charAt(text.length() - 1) != '\n') {
            return refused("truncated");
        }
        int pos = 0;
        int nl = text.indexOf('\n', pos);
        if (!LINE_VERSION.equals(text.substring(pos, nl))) {
            return refused("version");
        }
        pos = nl + 1;
        if (pos >= text.length()) {
            return refused("truncated");
        }
        nl = text.indexOf('\n', pos);
        String countLine = text.substring(pos, nl);
        if (!countLine.startsWith(PREFIX_COUNT)) {
            return refused("count");
        }
        int count = canonicalNonNegativeIntOrMinusOne(countLine.substring(PREFIX_COUNT.length()));
        if (count < 0 || count > ROWS_MAX) {
            return refused("count");
        }
        pos = nl + 1;
        List<Row> rows = new ArrayList<>(count);
        String prevMod = null;
        String prevField = null;
        int mods = 0;
        int perMod = 0;
        for (int i = 0; i < count; i++) {
            if (pos >= text.length()) {
                return refused("truncated index=" + i);
            }
            nl = text.indexOf('\n', pos);
            String line = text.substring(pos, nl);
            pos = nl + 1;
            String prefix = PREFIX_ROW + i + "=";
            if (!line.startsWith(prefix)) {
                return refused("index index=" + i);
            }
            int t1 = line.indexOf('\t', prefix.length());
            int t2 = t1 < 0 ? -1 : line.indexOf('\t', t1 + 1);
            int t3 = t2 < 0 ? -1 : line.indexOf('\t', t2 + 1);
            if (t3 < 0 || line.indexOf('\t', t3 + 1) >= 0 || t3 - t2 != 2) {
                return refused("fields index=" + i);
            }
            String modId = line.substring(prefix.length(), t1);
            String fieldId = line.substring(t1 + 1, t2);
            char type = line.charAt(t2 + 1);
            String value = line.substring(t3 + 1);
            String law = rowLawOrNull(modId, fieldId, type, value);
            if (law != null) {
                return refused(law + " index=" + i);
            }
            if (prevMod != null) {
                int cmp = modId.compareTo(prevMod);
                if (cmp == 0) {
                    cmp = fieldId.compareTo(prevField);
                }
                if (cmp == 0) {
                    return refused("duplicate index=" + i);
                }
                if (cmp < 0) {
                    return refused("order index=" + i);
                }
            }
            if (!modId.equals(prevMod)) {
                mods++;
                perMod = 0;
                if (mods > MODS_MAX) {
                    return refused("mods index=" + i);
                }
            }
            perMod++;
            if (perMod > ROWS_PER_MOD_MAX) {
                return refused("per_mod index=" + i);
            }
            prevMod = modId;
            prevField = fieldId;
            rows.add(new Row(modId, fieldId, type, value));
        }
        if (pos != text.length()) {
            return refused("trailing");
        }
        return new Decoded(Collections.unmodifiableList(rows), null);
    }

    /** {@code [A-Za-z0-9_.-]{1,64}}, hand-rolled (no regex in a shared class). */
    public static boolean lawfulFieldId(String fieldId) {
        if (fieldId == null || fieldId.isEmpty() || fieldId.length() > ID_MAX) {
            return false;
        }
        for (int i = 0; i < fieldId.length(); i++) {
            char c = fieldId.charAt(i);
            boolean alnum = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (!(alnum || c == '_' || c == '.' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    /** {@code ModSet.lawfulId} at 1..{@link #ID_MAX} chars. */
    public static boolean lawfulModId(String modId) {
        return modId != null && !modId.isEmpty() && modId.length() <= ID_MAX && ModSet.lawfulId(modId);
    }

    /** The value grammar for {@code type}: an unknown letter, a null value or one over {@link #VALUE_MAX} is unlawful. */
    public static boolean lawfulValue(char type, String value) {
        if (value == null || value.length() > VALUE_MAX) {
            return false;
        }
        switch (type) {
            case TYPE_BOOLEAN:
                return value.equals("true") || value.equals("false");
            case TYPE_INT:
                return canonicalInt32(value);
            case TYPE_DOUBLE:
                return finiteCanonicalDouble(value);
            case TYPE_STRING:
                return WireTextLaw.firstUnprintableIndex(value) < 0 && !WireTextLaw.hasLoneSurrogate(value);
            default:
                return false;
        }
    }

    /** SHA-256 of the text's UTF-8 bytes, the first 8 bytes as 16 lowercase hex chars (the META / announce digest). */
    public static String digest16(String text) {
        byte[] hash = sha256().digest(text.getBytes(StandardCharsets.UTF_8));
        char[] out = new char[DIGEST_BYTES * 2];
        for (int i = 0; i < DIGEST_BYTES; i++) {
            out[2 * i] = HEX[(hash[i] >> 4) & 0xF];
            out[2 * i + 1] = HEX[hash[i] & 0xF];
        }
        return new String(out);
    }

    /** The UTF-8 byte length of {@code s} without allocating it (a lone surrogate counts 3; lawful sheets hold none, so this is exact). */
    public static int utf8Length(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                n += 1;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 4;
                i++;
            } else {
                n += 3;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ private

    private static String rowLawOrNull(String modId, String fieldId, char type, String value) {
        if (!lawfulModId(modId)) {
            return "mod";
        }
        if (!lawfulFieldId(fieldId)) {
            return "field";
        }
        if (type != TYPE_BOOLEAN && type != TYPE_INT && type != TYPE_DOUBLE && type != TYPE_STRING) {
            return "type";
        }
        if (!lawfulValue(type, value)) {
            return "value";
        }
        return null;
    }

    /** (modId, fieldId) in {@code String.compareTo} order = byte order for these ASCII-only ids. */
    private static int compareRows(Row a, Row b) {
        int cmp = a.modId().compareTo(b.modId());
        return cmp != 0 ? cmp : a.fieldId().compareTo(b.fieldId());
    }

    private static Decoded refused(String refusal) {
        return new Decoded(null, refusal);
    }

    /** A canonical non-negative decimal (no sign, no leading zero, at most 10 digits) as an int, else -1. */
    private static int canonicalNonNegativeIntOrMinusOne(String s) {
        if (s.isEmpty() || s.length() > 10 || (s.length() > 1 && s.charAt(0) == '0')) {
            return -1;
        }
        long v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            v = v * 10 + (c - '0');
        }
        return v > Integer.MAX_VALUE ? -1 : (int) v;
    }

    /** {@code -?digits}, canonical (no leading zero but "0", no "-0"), within int32. */
    private static boolean canonicalInt32(String s) {
        boolean neg = !s.isEmpty() && s.charAt(0) == '-';
        String digits = neg ? s.substring(1) : s;
        if (digits.isEmpty() || digits.length() > 10 || (digits.length() > 1 && digits.charAt(0) == '0')) {
            return false;
        }
        long v = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            v = v * 10 + (c - '0');
        }
        if (neg) {
            return v > 0 && v <= -(long) Integer.MIN_VALUE;
        }
        return v <= Integer.MAX_VALUE;
    }

    /** {@code -?digits(.digits)?([eE][+-]?digits)?} AND finite after {@code Double.parseDouble}. */
    private static boolean finiteCanonicalDouble(String s) {
        int i = 0;
        int n = s.length();
        if (i < n && s.charAt(i) == '-') {
            i++;
        }
        int start = i;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        if (i == start) {
            return false;
        }
        if (i < n && s.charAt(i) == '.') {
            i++;
            int frac = i;
            while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
            if (i == frac) {
                return false;
            }
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            int exp = i;
            while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
            if (i == exp) {
                return false;
            }
        }
        if (i != n) {
            return false;
        }
        try {
            return Double.isFinite(Double.parseDouble(s));
        } catch (NumberFormatException unreachableByGrammar) {
            return false;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException brokenInstall) {
            throw new IllegalStateException("SHA-256");
        }
    }
}
