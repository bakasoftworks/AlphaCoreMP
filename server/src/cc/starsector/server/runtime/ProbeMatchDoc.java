package cc.starsector.server.runtime;

import java.security.SecureRandom;

/**
 * The match document a password-mode door serves inside its PROBE reply, so a player who holds only the address
 * and the password fetches the host's CURRENT match before launch and never handles a match file.
 *
 * <p>WHY the probe reply: it is the one door state where the sender has PROVEN the match password (its sealed proof
 * opened under this exchange) but claims no seat. A source without the password never reaches it (its probe is
 * answered with the small REJECT, so the reply-no-larger-than-the-request floor holds for every unproven source),
 * and nothing about the document leaks before that proof but the fact that the presented match id differs.
 *
 * <p>WHY sealed per reply: {@link #reply} draws a FRESH salt from the one {@link SecureRandom} held here and seals
 * every chunk under a key derived from THIS exchange's root ({@link MatchDocFrameCodec#encodeReply}), so only the
 * prover can read the chunks, and a chunk from another try, a forged or a replayed frame fails its tag at the kit
 * (the kit holds chunks per salt; two replies never share one).
 *
 * <p>Built ONCE at boot from the preflight's {@link MatchDocPreflight.Prepared}, the exact bytes the receive laws
 * judged; the door's reader thread only draws a salt and seals (no deflate, no encode, no new {@code SecureRandom}
 * there). Server-only: a token-mode serve builds none (live seat tokens never ride the wire).
 *
 *
 */
public final class ProbeMatchDoc {

    private final String matchId;
    private final byte[] deflated;
    private final int textLength;
    /** Made at boot by {@link #of}, never on the door's reader thread; thread-safe by the JDK contract. */
    private final SecureRandom random;

    private ProbeMatchDoc(String matchId, byte[] deflated, int textLength, SecureRandom random) {
        this.matchId = matchId;
        this.deflated = deflated;
        this.textLength = textLength;
        this.random = random;
    }

    /** The served document for {@code p}; a null {@code p} is a caller bug ({@code IllegalArgumentException}). */
    public static ProbeMatchDoc of(MatchDocPreflight.Prepared p) {
        if (p == null) {
            throw new IllegalArgumentException("prepared");
        }
        return new ProbeMatchDoc(p.matchId(), p.deflated(), p.text().length, new SecureRandom());
    }

    /** The served document's {@code match.id}: a probe presenting this exact term gets no document frames. */
    public String matchId() {
        return matchId;
    }

    /** The UTF-8 text length the kit inflates to exactly (1..{@link MatchDocFrameCodec#TEXT_MAX}). */
    public int textLength() {
        return textLength;
    }

    /** The raw-deflated document (a copy, for rows). */
    public byte[] deflated() {
        return deflated.clone();
    }

    /**
     * One reply's sealed DESC frames, in index order: a fresh 16 B salt, then {@link MatchDocFrameCodec#encodeReply}
     * under {@code root}. A null root or one shorter than {@link MatchDocFrameCodec#ROOT_MIN_BYTES} throws
     * {@code IllegalArgumentException} from the codec = the caller's bug (the exchange root is always longer).
     */
    public byte[][] reply(byte[] root) {
        byte[] salt = new byte[MatchDocFrameCodec.SALT_BYTES];
        random.nextBytes(salt);
        return MatchDocFrameCodec.encodeReply(root, salt, deflated, textLength);
    }
}
