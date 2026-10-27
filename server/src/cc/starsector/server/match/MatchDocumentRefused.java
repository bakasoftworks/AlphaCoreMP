package cc.starsector.server.match;

/**
 * The ONE refusal type of a match document (the {@code k=v} text {@link MatchDescriptorCodec} decodes
 * and the laws {@link MatchDocumentLaws#check} apply). WHY a type of its own: a document can arrive
 * from a hostile host, so a refusal must never carry the document's bytes back into a status line,
 * a log row or a window - its text is a fixed reason token plus OUR field name, row index or
 * count, and nothing else ({@link #getMessage()} = {@code reason + " " + field}). No cause is ever
 * chained: {@code Enum.valueOf} / {@code Float.parseFloat} messages quote the input.
 *
 * <p>It extends {@link IllegalArgumentException} so every caller that already catches that type
 * (or wider) keeps working unchanged; a caller that wants the structured view reads
 * {@link #reason()} (one of the {@code MatchDocumentLaws.REASON_*} tokens) and {@link #field()}.
 * Shared: bundled into the client mod jar (plain {@code String} code, classloader-B clean).
 *
 *
 */
public final class MatchDocumentRefused extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String reason;
    private final String field;

    /**
     * @param reason one of the {@code MatchDocumentLaws.REASON_*} tokens
     * @param field  OUR name for the refused place: a codec key literal, {@code line <n>}, a
     *               {@code <list>.<i>.<key>} path or a count - never text read from the input
     */
    public MatchDocumentRefused(String reason, String field) {
        super(reason + " " + field);
        this.reason = reason;
        this.field = field;
    }

    /** The fixed reason token ({@code match_doc_*}). */
    public String reason() {
        return reason;
    }

    /** OUR field / line index / count label - never input-derived text. */
    public String field() {
        return field;
    }
}
