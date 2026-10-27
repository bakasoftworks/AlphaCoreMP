package cc.starsector.server.runtime;

import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.MatchDocumentLaws;
import cc.starsector.server.match.MatchDocumentRefused;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * The match document's PREFLIGHT: the server runs the client's OWN receive path over the exact bytes the door
 * will serve, so a document no join window could adopt is refused at Start (the console's one sentence, H2) and
 * at the serve's boot (a throw like the sibling door mints) instead of meeting a stranger's probe as a dead end
 * (the design lens's JUDGE-BOOT ruling: REFUSE). The pipeline, in the receive order: redact -> encode -> the
 * receive laws R5-R9 ({@link MatchDocumentLaws#check} with redaction required) -> strictly redacted (every seat
 * token a placeholder; live tokens are every seat's key and never ride the wire) -> the text cap -> raw deflate
 * -> the deflated / chunk caps (the R2 header bounds) -> the R4 exact inflate round-trip. The door's sender
 * ({@code ProbeMatchDoc}) serves {@link Prepared#deflated()} as-is, so the bytes judged here ARE the bytes sent.
 *
 * <p>Server-only (never bundled): the boot and the Start path call it; a token-mode serve (no match password)
 * never does - it serves no document.
 *
 * <p>Main-written pre-launch seam (the L5a / L5b lanes build on it)
 */
public final class MatchDocPreflight {

    /** The refusal token the console and the boot print: {@code match_doc_unservable reason=<reason> field=<field>}. */
    public static final String REASON_UNSERVABLE = "match_doc_unservable";

    /** The served document: its id, the UTF-8 text and the raw-deflated stream (copies in, copies out). */
    public record Prepared(String matchId, byte[] text, byte[] deflated) {
        public Prepared {
            if (matchId == null || text == null || deflated == null) {
                throw new IllegalArgumentException("prepared");
            }
            text = text.clone();
            deflated = deflated.clone();
        }

        @Override
        public byte[] text() {
            return text.clone();
        }

        @Override
        public byte[] deflated() {
            return deflated.clone();
        }
    }

    private MatchDocPreflight() {
    }

    /**
     * Prepare the document a password-mode door serves for {@code d}, or throw {@link MatchDocumentRefused} naming
     * the receive law broken (its reason + OUR field; never the document's bytes - the no-echo law). A null
     * descriptor is a caller bug ({@code IllegalArgumentException}).
     */
    public static Prepared prepare(MatchDescriptor d) {
        if (d == null) {
            throw new IllegalArgumentException("descriptor");
        }
        MatchDescriptor redacted = MatchDescriptors.redact(d);
        MatchDocumentLaws.check(redacted, true);
        String encoded = MatchDescriptorCodec.encode(redacted);
        if (!MatchDescriptors.isStrictlyRedacted(List.of(encoded.split("\n", -1)))) {
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_MALFORMED, "redaction");
        }
        byte[] text = encoded.getBytes(StandardCharsets.UTF_8);
        if (text.length < 1 || text.length > MatchDocumentLaws.TEXT_MAX) {
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OVERSIZE, "text");
        }
        byte[] deflated = MatchDocFrameCodec.deflate(text);
        if (deflated.length > MatchDocumentLaws.DEFLATED_MAX) {
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OVERSIZE, "deflated");
        }
        if (!Arrays.equals(MatchDocFrameCodec.inflateExact(deflated, text.length), text)) {
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_MALFORMED, "inflate");
        }
        return new Prepared(redacted.matchId(), text, deflated);
    }

    /** The one refusal sentence fragment both the console and the boot print for a {@link #prepare} refusal. */
    public static String unservable(MatchDocumentRefused refused) {
        return REASON_UNSERVABLE + " reason=" + refused.reason() + " field=" + refused.field();
    }
}
