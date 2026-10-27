package cc.starsector.server.ops;

/**
 * The rebuilt ops console's PAGE assembler - the three text
 * blocks the console's HTTP shell serves as {@code /} ({@link #html()}), {@code /ops.css}
 * ({@link #css()}) and {@code /ops.js} ({@link #js()}).
 *
 * <p>WHY Java text blocks: the build compiles {@code server/src} into
 * {@code server/classes} and copies no resources, so a page file kept beside the sources would never
 * reach a server package; a text block ships inside its class. WHY three blocks and nothing inline:
 * the console answers with {@code Content-Security-Policy: default-src 'self'},
 * so the page carries no inline script, no inline
 * style, no {@code on*=} handler, no CDN and no {@code url()} - {@link OpsConsolePageSelftest}'s
 * {@code page-csp-clean} row holds that. The page is secret-free and static: the console key
 * reaches it only through the address fragment, never through these blocks.
 *
 * <p>Parity: every control the Swing package window shows carries {@code data-cc="<checklist id>"};
 * the suite row {@code parity-web-twin} compares
 * the served ids with the Swing list. The script is three parts (core; players + bar;
 * setup + voice + mods), joined here in that order; each binds to the ids
 * {@link OpsConsolePageHtml#HTML} pins.
 */
public final class OpsConsolePage {

    private OpsConsolePage() {
    }

    /** The page served at {@code /} (HTML; links {@code ops.css} and {@code ops.js} relatively). */
    public static String html() {
        return OpsConsolePageHtml.HTML;
    }

    /** The stylesheet served at {@code /ops.css}. */
    public static String css() {
        return OpsConsolePageCss.CSS;
    }

    /** The script served at {@code /ops.js}: the core, players and setup parts, in that order, with
     * every whole-line {@code //} comment dropped ({@link #stripCommentLines}).
     * WHY {@code String.join} and not {@code +}: the three parts are compile-time constants, so
     * {@code +} would FOLD them into one class-file constant, and a single constant is capped at
     * 65535 UTF-8 bytes ({@code javac: constant string too long} - seen once the three parts were first
     * built together, each having compiled alone with the others still stubs). {@code join} runs at call time. */
    public static String js() {
        return stripCommentLines(String.join("\n", OpsConsoleJsCore.JS, OpsConsoleJsPlayers.JS, OpsConsoleJsSetup.JS));
    }

    /**
     * {@code js} minus every line whose trimmed form starts
     * with {@code //} (the whole line and its newline dropped); every other line, blank ones included,
     * is kept byte-for-byte.
     *
     * <p>WHY: {@code /ops.js} goes to a host's browser and this source is exported, so both reach
     * strangers; the parts' comments carry maintainer notes (task / review cites, design-doc paths) that
     * are not for them. The source keeps the comments for the maintainer; only the served form drops
     * them. WHY whole lines only: a line of code never starts with {@code //}, while a {@code //} inside
     * a code line (a URL, a regex) is code and must stay, so the cut never looks past a line's first
     * token. {@link OpsConsolePageSelftest}'s {@code page-js-strip-exact} row holds that nothing else
     * changes; {@code page-js-no-comment-lines} holds that none survive.
     */
    static String stripCommentLines(String js) {
        StringBuilder out = new StringBuilder(js.length());
        int from = 0;
        while (from <= js.length()) {
            int nl = js.indexOf('\n', from);
            int end = nl < 0 ? js.length() : nl + 1;
            if (!js.substring(from, end).strip().startsWith("//")) {
                out.append(js, from, end);
            }
            if (nl < 0) {
                break;
            }
            from = nl + 1;
        }
        return out.toString();
    }
}
