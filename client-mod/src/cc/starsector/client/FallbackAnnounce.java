package cc.starsector.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import cc.starsector.server.runtime.WireTextLaw;

/**
 * One-shot FALLBACK-ANNOUNCE sink, client half (the
 * server twin = {@code cc.starsector.server.runtime.FallbackAnnounce}): a seam that silently
 * degrades to a lower tier/older behavior announces its FIRST engagement here — once per seam
 * key per boot — as a single greppable row through {@link PilotWatchLog#info(String)}:
 *
 * <pre>[CC-PILOT-WATCH] FALLBACK-ANNOUNCE seam=&lt;id&gt; key=value ...</pre>
 *
 * {@code watch-verdict.ps1} greps the {@code FALLBACK-ANNOUNCE} token across server.out and both
 * seat logs and lists every row in the verdict (zero rows = a green line). Expected-normal
 * micro-fallbacks stay meters
 * and only degraded-mode seams announce. Per-entity seams fold
 * the id into the seam string ({@code seam#entityId}) so dedupe is per (seam, entity).
 *
 * <p>Security laws: the emitted line
 * is single-line printable and bounded — seam and detail are sanitized HERE so call sites may
 * pass raw exception/value text (some of it wire-derived, e.g. a WELCOME reject reason); the sink
 * is the write-only {@link PilotWatchLog} lane; the key set is capped so no input-driven seam can
 * grow it unbounded.
 *
 * <p>Force-loaded at application load (from {@link CcClientModPlugin}, mirroring the
 * {@link PilotWatchLog} rationale): the in-combat script SecurityManager forbids NEW class
 * loading, and several seams (incl. {@link KnobParse} static-init parses) can first fire
 * mid-combat.
 */
public final class FallbackAnnounce {

    private static final int MAX_KEYS = 256;
    private static final int MAX_DETAIL = 200;
    private static final Set<String> announced = ConcurrentHashMap.newKeySet();

    private FallbackAnnounce() {
    }

    /** Force-load handle for the application-load pre-warm; deliberately NOT an announce (a
     * warm-up row would land in the verdict's FALLBACK section every run and defeat its
     * zero-rows-is-green law). */
    public static String describe() {
        return "armed (one-shot, key cap " + MAX_KEYS + ")";
    }

    /** Announce once per {@code seamId} per boot; every later call for the same seam is a no-op. */
    public static void announceOnce(String seamId, String detail) {
        try {
            String id = sanitize(seamId, 64);
            if (announced.size() >= MAX_KEYS) {
                if (announced.add("overflow")) {
                    PilotWatchLog.info("FALLBACK-ANNOUNCE seam=overflow key_cap=" + MAX_KEYS);
                }
                return;
            }
            if (announced.add(id)) {
                PilotWatchLog.info("FALLBACK-ANNOUNCE seam=" + id + " " + sanitize(detail, MAX_DETAIL));
            }
        } catch (Throwable ignore) {
            // announce is diagnostics; it must never break the seam it confesses for
        }
    }

    /** Printable single-line projection: control/non-ASCII chars become {@code ?}, length capped.
     *  Package-shared: the standing law is that any log line embedding
     *  wire-decoded text carries this guard at write time — call sites scrub HERE, never inline. */
    static String sanitize(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "-";
        }
        StringBuilder b = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            b.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        return b.toString();
    }

    /**
     * Quoted projection of PEER- or SERVER-AUTHORED text for a log row, quotes included: the
     * {@link #sanitize} fold (control / non-ASCII to {@code ?}, length cap) PLUS the delimiter
     * law — a {@code '} or {@code \} inside the value is backslash-escaped, so a seat name can
     * never close the {@code '…'} and plant forged {@code key=value} tokens in a debrief log
     * (the kit twin of the server's {@code ops/PeerText.quote}). Null / empty = {@code ''}.
     * Why: the glyph-geom, SNMR and officer
     * rows printed the name inside bare quotes.
     */
    public static String quote(String s, int max) {
        String folded = s == null || s.isEmpty() ? "" : sanitize(s, max);
        StringBuilder b = new StringBuilder(folded.length() + 4);
        b.append('\'');
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (c == '\'' || c == '\\') {
                b.append('\\');
            }
            b.append(c);
        }
        return b.append('\'').toString();
    }

    /** The SINK fold every {@link PilotWatchLog}
     *  row rides, kit twin of the server's {@code ops/PeerText.bound}: a char {@link WireTextLaw}
     *  rejects becomes {@code ?}, text past {@code max} is cut with a visible ellipsis, null reads "null".
     *  Unlike {@link #sanitize} it KEEPS non-ASCII printables (the em-dash rows); clean text under the cap
     *  returns {@code s} itself — one scan, zero allocation. */
    public static String singleLine(String s, int max) {
        if (s == null) {
            return "null";
        }
        int n = Math.min(s.length(), Math.max(1, max));
        StringBuilder b = null;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (!WireTextLaw.isPrintableWireChar(c)) {
                b = (b == null ? new StringBuilder(n + 1).append(s, 0, i) : b).append('?');
            } else if (b != null) {
                b.append(c);
            }
        }
        if (n == s.length()) {
            return b == null ? s : b.toString();
        }
        return (b == null ? new StringBuilder(n + 1).append(s, 0, n) : b).append((char) 0x2026).toString();
    }

    /** A {@code printStackTrace}-shaped render
     *  whose peer-reachable parts (every message) ride {@link #singleLine} (512 cap), so {@code PilotWatchLog.error}
     *  never hands log4j a raw Throwable (log4j 1.2 writes a message's line breaks verbatim). Frames stay
     *  verbatim (our code's names); causes cap at 8 behind an identity cycle guard; SUPPRESSED are omitted. */
    public static String throwableText(Throwable t) {
        String sep = System.lineSeparator();
        StringBuilder b = new StringBuilder(512);
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        int causes = 0;
        for (Throwable cur = t; cur != null && seen.add(cur); cur = cur.getCause()) {
            if (cur != t && ++causes > 8) {
                b.append(sep).append("\t... cause chain capped at 8");
                break;
            }
            String m = cur.getLocalizedMessage();
            b.append(cur == t ? "" : sep + "Caused by: ").append(cur.getClass().getName())
                    .append(m == null ? "" : ": " + singleLine(m, 512));
            for (StackTraceElement f : cur.getStackTrace()) {
                b.append(sep).append("\tat ").append(f);
            }
        }
        return b.toString();
    }
}
