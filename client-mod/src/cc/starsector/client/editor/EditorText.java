package cc.starsector.client.editor;

import cc.starsector.client.net.LocalVariantIdLaw;

/**
 * Text laws of the loopback editor: every string that came from the
 * browser (a request path, a query value, a display name) is DATA — quoted/sanitized before it
 * reaches a log line, id-gated before it reaches the API, and NEVER a file name.
 * java.lang-only (classloader-B trivially clean).
 */
final class EditorText {
    private EditorText() {
    }

    /**
     * Printable-ASCII projection for log rows: every other char becomes {@code ?}; hard cap — the
     * output never exceeds {@code max} chars, a truncation ending in {@code ~} (it used to emit
     * {@code max + 1}, one over any reader law fed the same number).
     */
    static String sanitize(String s, int max) {
        if (s == null) {
            return "<null>";
        }
        boolean over = s.length() > max;
        int keep = over ? Math.max(0, max - 1) : s.length();
        StringBuilder sb = new StringBuilder(keep + 1);
        for (int i = 0; i < keep; i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c > 0x7e ? '?' : c);
        }
        if (over) {
            sb.append('~');
        }
        return sb.toString();
    }

    /** A request-supplied id that may name an engine spec: the local file-id law (strict). */
    static boolean isId(String s) {
        return LocalVariantIdLaw.isLawful(s);
    }

    /**
     * Decision (4): the {@code /save} file stem is OURS — {@code <hullId>_<slug>}
     * where the slug is the display name lower-cased with every run of non {@code [a-z0-9]}
     * collapsed to one {@code -}, empty ⇒ {@code custom}, whole stem cut to the id law's 64
     * chars and re-checked. The request never names a path; a stem starting {@code cc-v} (the
     * server's minted prefix) cannot arise because the hull id leads.
     */
    static String saveStem(String hullId, String displayName) {
        StringBuilder slug = new StringBuilder();
        boolean pendingDash = false;
        for (int i = 0; i < displayName.length(); i++) {
            char c = Character.toLowerCase(displayName.charAt(i));
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (ok) {
                if (pendingDash && slug.length() > 0) {
                    slug.append('-');
                }
                pendingDash = false;
                slug.append(c);
            } else {
                pendingDash = true;
            }
        }
        String s = slug.length() == 0 ? "custom" : slug.toString();
        String stem = hullId + "_" + s;
        if (stem.length() > LocalVariantIdLaw.MAX_CHARS) {
            stem = stem.substring(0, LocalVariantIdLaw.MAX_CHARS);
        }
        while (stem.endsWith("-") || stem.endsWith("_")) {
            stem = stem.substring(0, stem.length() - 1);
        }
        if (!LocalVariantIdLaw.isLawful(stem)) {
            throw new IllegalArgumentException("save stem violates the file-id law: "
                    + sanitize(stem, 80));
        }
        return stem;
    }
}
