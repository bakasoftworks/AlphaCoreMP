package cc.starsector.server.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-shot FALLBACK-ANNOUNCE sink:
 * a seam that silently degrades to a lower tier/older behavior announces its FIRST engagement
 * here — once per seam key per boot — as a single greppable stdout row:
 *
 * <pre>[cc-fallback] FALLBACK-ANNOUNCE seam=&lt;kebab-id&gt; key=value ...</pre>
 *
 * {@code watch-verdict.ps1} greps the {@code FALLBACK-ANNOUNCE} token across server.out and both
 * seat logs and lists every row in the verdict (zero rows = a green line) — that is what makes a
 * fallback impossible to miss at grading time. Expected-normal micro-fallbacks stay meters
 * — only degraded-mode seams announce.
 *
 * <p>Security laws: the emitted line is
 * single-line printable and bounded — detail is sanitized HERE so call sites may pass raw
 * exception/value text; the sink is write-only stdout; the key set is capped so no input-driven
 * seam can grow it unbounded.
 */
public final class FallbackAnnounce {

    private static final int MAX_KEYS = 256;
    private static final int MAX_DETAIL = 200;
    private static final Set<String> announced = ConcurrentHashMap.newKeySet();

    private FallbackAnnounce() {
    }

    /** Announce once per {@code seamId} per boot; every later call for the same seam is a no-op. */
    public static void announceOnce(String seamId, String detail) {
        String id = sanitize(seamId, 64);
        if (announced.size() >= MAX_KEYS) {
            if (announced.add("overflow")) {
                System.out.println("[cc-fallback] FALLBACK-ANNOUNCE seam=overflow key_cap=" + MAX_KEYS);
            }
            return;
        }
        if (announced.add(id)) {
            System.out.println("[cc-fallback] FALLBACK-ANNOUNCE seam=" + id
                    + " " + sanitize(detail, MAX_DETAIL));
        }
    }

    /** contract-row seam — the one-shot set is JVM-static,
     * so a row asserts the seam, never a stdout count. */
    public static boolean wasAnnounced(String seamId) { return announced.contains(sanitize(seamId, 64)); }

    /** Printable single-line projection: control/non-ASCII chars become {@code ?}, length capped. */
    private static String sanitize(String s, int max) {
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
}
