package cc.starsector.server.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shipped server's frozen launch template ({@code <pkg>/serve.argv}): one JVM argument per
 * line, {@code #} comments, and a CLOSED list of {@code ${NAME}} tokens that {@link ServeLauncher}
 * resolves on the host's box. WHY a template and not a script: the release package ships no
 * PowerShell, so the argv the owner-mode wrapper emits ({@code run-real-pilot-server.ps1 -EmitCmdFile},
 * one argument per line) is frozen at pack time with its box-specific roots replaced by tokens.
 *
 *
 * <p>Three pure steps, each driven directly by the contract rows: {@link #read} (the line shape),
 * {@link #leakingLine} (a template that still names a box path never ships), {@link #substitute}
 * (every token resolved; an unknown one or a surviving <code>${</code> refuses). Pure ASCII console
 * text lives in {@link ServeLauncher}; this class only throws.
 *
 *
 */
public final class ArgvTemplate {

    /**
     * The CLOSED token list: {@code PKG} the package root, {@code JAVA} the runtime's
     * java executable, {@code GAME} the game folder holding the core engine jar, {@code NATIVE}
     * {@code windows}|{@code linux}, {@code SEP} the platform classpath separator, {@code RUN} this
     * launch's flight dir, {@code CONFIG} the serve config file, {@code HEAP_MIN} / {@code HEAP_MAX} /
     * {@code CI_COUNT} from {@code launcher.properties}. Nothing else substitutes.
     */
    public static final List<String> TOKENS = List.of(
            "PKG", "JAVA", "GAME", "NATIVE", "SEP", "RUN", "CONFIG", "HEAP_MIN", "HEAP_MAX", "CI_COUNT");

    /** The one line-dropping token: a line whose ONLY token is this one, with a blank value, is removed. */
    static final String LINE_DROPPING_TOKEN = "CI_COUNT";

    /** A template is a few KB; anything past this is not a template (bounded read). */
    static final int MAX_TEMPLATE_BYTES = 256 * 1024;

    private static final Pattern TOKEN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    /**
     * Box-path shapes a SHIPPED template must never carry: a drive root
     * {@code X:\}, a Windows profile segment, {@code AppData}, the owner-mode checkout walk-up
     * {@code local\..} (a {@code $PSScriptRoot}-joined path), {@code Program Files}, and the
     * forward-slash twins {@code /Users/} + {@code local/..}. Literal substrings except the drive root.
     */
    private static final Pattern LEAK = Pattern.compile(
            "[A-Za-z]:\\\\|\\\\Users\\\\|AppData|local\\\\\\.\\.|Program Files|/Users/|local/\\.\\.");

    private ArgvTemplate() {
    }

    /**
     * The template's argument lines: LF or CRLF, each line trimmed, blank lines and {@code #}
     * comment lines dropped, order kept. Throws {@link IOException} when the file is missing,
     * unreadable or over {@link #MAX_TEMPLATE_BYTES}.
     */
    static List<String> read(Path template) throws IOException {
        if (Files.size(template) > MAX_TEMPLATE_BYTES) {
            throw new IOException("template exceeds " + MAX_TEMPLATE_BYTES + " bytes");
        }
        String text = new String(Files.readAllBytes(template), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    /** The first line carrying a box-path shape ({@link #LEAK}), or {@code null} when the template is clean. */
    static String leakingLine(List<String> lines) {
        for (String line : lines) {
            if (LEAK.matcher(line).find()) {
                return line;
            }
        }
        return null;
    }

    /**
     * Every {@code ${NAME}} replaced by its value. A name outside {@link #TOKENS} or absent from
     * {@code values}, or a {@code ${} still present after substitution (a malformed token, or a
     * value that itself carried one), throws {@code IllegalArgumentException("line N: …")} with N
     * the 1-based index into {@code lines}. A line whose only token is {@link #LINE_DROPPING_TOKEN}
     * and whose value is blank is DROPPED (the optional {@code -XX:CICompilerCount} line).
     */
    static List<String> substitute(List<String> lines, Map<String, String> values) {
        List<String> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = TOKEN.matcher(line);
            Set<String> names = new LinkedHashSet<>();
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String name = m.group(1);
                if (!TOKENS.contains(name) || !values.containsKey(name) || values.get(name) == null) {
                    throw new IllegalArgumentException("line " + (i + 1) + ": unknown token ${" + name + "}");
                }
                names.add(name);
                m.appendReplacement(sb, Matcher.quoteReplacement(values.get(name)));
            }
            m.appendTail(sb);
            if (names.size() == 1 && names.contains(LINE_DROPPING_TOKEN)
                    && values.get(LINE_DROPPING_TOKEN).isBlank()) {
                continue;
            }
            String result = sb.toString();
            if (result.contains("${")) {
                throw new IllegalArgumentException("line " + (i + 1) + ": an unresolved ${ remains");
            }
            out.add(result);
        }
        return out;
    }
}
