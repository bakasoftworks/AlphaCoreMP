package cc.starsector.server.runtime;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * The LunaSettings sheet a password-mode door serves inside its PROBE reply beside the match document
 * ({@link ProbeMatchDoc}'s twin), so a joiner's kit runs the host's effective mod settings (applied as its
 * combat plugin initializes) instead of its own.
 *
 * <p>WHY the probe reply: the same door state that serves DESC - the sender has PROVEN the match password but
 * claims no seat - so only a prover reads the sheet, and nothing about it leaks before that proof.
 *
 * <p>WHY sealed per reply: {@link #reply} draws a FRESH salt from the one {@link SecureRandom} held here and seals
 * every chunk under {@link MatchDocFrameCodec.Kind#LUNA}'s key derived from THIS exchange's root, so a LUNA chunk
 * never opens under the DESC opener (its own magic, wire type and HKDF label) and a chunk from another try, a
 * forged or a replayed frame fails its tag at the kit.
 *
 * <p>Built ONCE at boot from the canonical sheet text ({@link LunaSheetCodec#canonicalText}): {@link #of} judges it
 * under the SAME decode law the kit runs (a sheet the kit would refuse is refused here), deflates it once under the
 * LUNA caps and computes the announce digest once; the door's reader thread only draws a salt and seals (no
 * deflate, no decode, no digest, no new {@code SecureRandom} there). Server-only: the kit decodes, never builds one.
 *
 *
 */
public final class ProbeLunaSheet {

    private final byte[] deflated;
    private final int textLength;
    private final String digest16;
    private final int rows;
    /** Made at boot by {@link #of}, never on the door's reader thread; thread-safe by the JDK contract. */
    private final SecureRandom random;

    private ProbeLunaSheet(byte[] deflated, int textLength, String digest16, int rows, SecureRandom random) {
        this.deflated = deflated;
        this.textLength = textLength;
        this.digest16 = digest16;
        this.rows = rows;
        this.random = random;
    }

    /**
     * The served sheet for {@code canonicalText}. A null or empty text, a text {@link LunaSheetCodec#decode} refuses
     * (the message is its class word, never the text), one over {@link LunaSheetCodec#TEXT_MAX} UTF-8 bytes, or a
     * deflated stream over {@link LunaSheetCodec#DEFLATED_MAX} = {@code IllegalArgumentException}: the serve drops
     * the whole sheet at boot, never a sheet the kit would refuse on the wire.
     */
    public static ProbeLunaSheet of(String canonicalText) {
        if (canonicalText == null || canonicalText.isEmpty()) {
            throw new IllegalArgumentException("luna sheet empty");
        }
        LunaSheetCodec.Decoded judged = LunaSheetCodec.decode(canonicalText);
        if (!judged.ok()) {
            throw new IllegalArgumentException("luna sheet " + judged.refusal());
        }
        byte[] utf8 = canonicalText.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MatchDocFrameCodec.Kind.LUNA.textMax()) {
            throw new IllegalArgumentException("luna sheet oversize bytes=" + utf8.length);
        }
        byte[] deflated = MatchDocFrameCodec.deflate(MatchDocFrameCodec.Kind.LUNA, utf8);
        if (deflated.length > LunaSheetCodec.DEFLATED_MAX || deflated.length > MatchDocFrameCodec.DEFLATED_MAX) {
            throw new IllegalArgumentException("luna sheet deflated bytes=" + deflated.length);
        }
        return new ProbeLunaSheet(deflated, utf8.length, LunaSheetCodec.digest16(canonicalText),
                judged.rows().size(), new SecureRandom());
    }

    /** The UTF-8 text length the kit inflates to exactly (1..{@link LunaSheetCodec#TEXT_MAX}). */
    public int textLength() {
        return textLength;
    }

    /** The raw-deflated sheet (a copy, for rows). */
    public byte[] deflated() {
        return deflated.clone();
    }

    /** {@link LunaSheetCodec#digest16} of the canonical text: sixteen lowercase hex chars, computed once at boot. */
    public String digest16() {
        return digest16;
    }

    /** The sheet's {@code luna.count}: its row count (0..{@link LunaSheetCodec#ROWS_MAX}). */
    public int rows() {
        return rows;
    }

    /**
     * One reply's sealed LUNA frames, in index order: a fresh 16 B salt, then
     * {@link MatchDocFrameCodec#encodeReply(MatchDocFrameCodec.Kind, byte[], byte[], byte[], int)} under {@code root}
     * as {@link MatchDocFrameCodec.Kind#LUNA}. A null root or one shorter than {@link MatchDocFrameCodec#ROOT_MIN_BYTES}
     * throws {@code IllegalArgumentException} from the codec = the caller's bug (the exchange root is always longer).
     */
    public byte[][] reply(byte[] root) {
        byte[] salt = new byte[MatchDocFrameCodec.SALT_BYTES];
        random.nextBytes(salt);
        return MatchDocFrameCodec.encodeReply(MatchDocFrameCodec.Kind.LUNA, root, salt, deflated, textLength);
    }
}
