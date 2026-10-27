package cc.starsector.client.join;

/**
 * A minimal JSON OBJECT writer for the join responder's replies ({@code JoinRoutes}). The window JVM has
 * no {@code org.json} on its classpath (the bat's {@code -cp} is the mod jar alone) and the replies are
 * flat: strings, numbers, booleans, nulls, and string arrays. Every string is escaped per RFC 8259 —
 * control characters, quotes, backslashes, and {@code <}/{@code >}/{@code &} too (belt-and-braces: the SPA
 * only ever lands wire text through {@code textContent}, but a reply must never be able to break a
 * {@code <script>} context either). Never used to PARSE — request bodies are form-shaped
 * ({@code JoinRoutes.formField}), never JSON.
 */
final class JoinJson {
    private final StringBuilder sb = new StringBuilder(256);
    private boolean first = true;

    JoinJson() {
        sb.append('{');
    }

    private void key(String name) {
        if (!first) {
            sb.append(',');
        }
        first = false;
        quote(sb, name);
        sb.append(':');
    }

    JoinJson put(String name, String value) {
        key(name);
        if (value == null) {
            sb.append("null");
        } else {
            quote(sb, value);
        }
        return this;
    }

    JoinJson put(String name, boolean value) {
        key(name);
        sb.append(value ? "true" : "false");
        return this;
    }

    JoinJson put(String name, long value) {
        key(name);
        sb.append(value);
        return this;
    }

    JoinJson putStrings(String name, Iterable<String> values) {
        key(name);
        sb.append('[');
        boolean firstItem = true;
        for (String v : values) {
            if (!firstItem) {
                sb.append(',');
            }
            firstItem = false;
            quote(sb, v == null ? "" : v);
        }
        sb.append(']');
        return this;
    }

    /** A nested object already rendered by another {@code JoinJson} (or {@code null}). */
    JoinJson putRaw(String name, String renderedObjectOrNull) {
        key(name);
        sb.append(renderedObjectOrNull == null ? "null" : renderedObjectOrNull);
        return this;
    }

    @Override
    public String toString() {
        return sb.toString() + "}";
    }

    /** {@code {"ok":true}} / {@code {"ok":false,"error":"…"}} — the reply idiom every route shares. */
    static String ok() {
        return new JoinJson().put("ok", true).toString();
    }

    static String error(String message) {
        return new JoinJson().put("ok", false).put("error", message).toString();
    }

    static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '<': out.append("\\u003c"); break;
                case '>': out.append("\\u003e"); break;
                case '&': out.append("\\u0026"); break;
                default:
                    if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == 0x2028 || c == 0x2029) {
                        out.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            out.append('0');
                        }
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
