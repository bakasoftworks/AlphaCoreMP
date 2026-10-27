package cc.starsector.server.ops;

import cc.starsector.server.runtime.WireTextLaw;

/**
 * peer-controlled text at PRINT sites.
 *
 * <p>The decode-time content laws ({@code cleanHandshakeText}, {@code validateSeatName},
 * {@code validateChatText}) bound the <em>characters</em> of a peer field to one printable line;
 * nothing bounded the <em>value</em>, so a {@code clientId} of {@code x reason=ok from=127.0.0.1}
 * planted forged {@code key=value} tokens AHEAD of the genuine ones in a stdout admission row and
 * any first-match reader took the attacker's. {@link #quote} delimits a peer value for a k=v stdout
 * row ({@code client="x reason=ok from=127.0.0.1"}) so the trust boundary is legible to every
 * reader, human or agent. {@link #bound}
 * is the file-sink half (B4): the ops status file is one value per line and never quoted, so it
 * folds every control character and caps the length instead.
 *
 * <p>Reading rule for logs written BEFORE this landed: read a {@code client=} / {@code name=} /
 * {@code text=} row from the LAST occurrence of each key (interim rule, kept in the security doc).
 */
public final class PeerText {

    /** Ops-status file values: one k=v per line, unquoted; longer than this = a forged/degenerate value. */
    public static final int OPS_VALUE_MAX_CHARS = 512;

    /** {@link #cidForLog}: a client id this long or longer prints as its prefix; shorter prints whole. */
    public static final int CID_LOG_MIN_CHARS = 16;

    /** {@link #cidForLog}: the printed prefix length (the kit's own credential row prints the same 8). */
    public static final int CID_LOG_PREFIX_CHARS = 8;

    private PeerText() {}

    /**
     * Delimit a peer-controlled value for a space-separated {@code key=value} stdout row: wraps
     * the value in double quotes, escapes embedded {@code "} and {@code \} with a backslash (the
     * content laws admit {@code 0x22}, so a bare wrapper would not be unambiguous), and folds any
     * character the shared content law ({@link WireTextLaw#isPrintableWireChar}: C0/DEL + C1/NEL/LS/PS/bidi)
     * rejects to one space. {@code null} prints as
     * {@code ""}. The result never contains a raw {@code "} except its two delimiters, so a reader
     * that takes {@code key="([^"\\]|\\.)*"} recovers the value exactly.
     */
    public static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (!WireTextLaw.isPrintableWireChar(c)) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /**
     * The PRINT form of a client id (OPERATOR-SERVE-SECRETS-1,
     * option A). In password mode the cid keys the seat — a HELLO with the same id under the shared
     * match password takes that seat over — and hosts post their stdout and ops-status file, so a
     * log row names an id of {@value #CID_LOG_MIN_CHARS}+ characters by its first
     * {@value #CID_LOG_PREFIX_CHARS} characters + {@code ...} (the kit's own credential row prints
     * the same prefix, {@code PredictDialPlanner}). A shorter id prints whole (rig ids are ≤ 13
     * characters and stay byte-stable); {@code null} stays {@code null}. A cut that would end on a
     * HIGH surrogate stops one character short: the HELLO decode admits valid pairs only, and a lone
     * surrogate makes the ops-status writer's strict UTF-8 encoder throw. Display text only — the
     * ban key is {@code BanSet.cidKey} of the FULL id, never of this prefix.
     */
    public static String cidForLog(String cid) {
        if (cid == null || cid.length() < CID_LOG_MIN_CHARS) {
            return cid;
        }
        int cut = Character.isHighSurrogate(cid.charAt(CID_LOG_PREFIX_CHARS - 1))
                ? CID_LOG_PREFIX_CHARS - 1 : CID_LOG_PREFIX_CHARS;
        return cid.substring(0, cut) + "...";
    }

    /** {@link #quote} of {@link #cidForLog}: the k=v stdout form of a client id ({@code null} prints {@code ""}). */
    public static String quoteCid(String cid) {
        return quote(cidForLog(cid));
    }

    /**
     * Bound a peer-controlled value for a one-value-per-line file sink (B4: the ops status file's
     * {@code sanitize} used to fold CR/LF only — no charset, no length bound): every character the
     * shared content law rejects (C0/DEL + C1/NEL/LS/PS/bidi, B7) folds to one space, and a value longer than {@code maxChars} is cut
     * there with a {@code …} marker so the truncation is visible. Non-control text passes
     * unchanged (the value is never quoted here — the file's readers split on the first {@code =}).
     */
    public static String bound(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        int limit = Math.max(1, maxChars);
        StringBuilder sb = null;
        int n = Math.min(value.length(), limit);
        for (int i = 0; i < n; i++) {
            char c = value.charAt(i);
            boolean control = !WireTextLaw.isPrintableWireChar(c);
            if (sb == null && !control) {
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(n + 1).append(value, 0, i);
            }
            sb.append(control ? ' ' : c);
        }
        if (value.length() <= limit) {
            return sb == null ? value : sb.toString();
        }
        return (sb == null ? value.substring(0, limit) : sb.toString()) + "…";
    }
}
