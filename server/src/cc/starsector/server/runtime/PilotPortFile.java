package cc.starsector.server.runtime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * The pilot-endpoint port-file contract for the two-window watch.
 * The scout watch-mode binds a {@link PilotIntentSocketServer} per pilot ship
 * and {@link #write writes} its bound {@code host}/{@code port} here; each game window's
 * {@code PilotIntentCombatPlugin} (and the headless self-test's {@link
 * cc.starsector.server.client.PilotIntentSocketClient}) {@link #read reads} it to learn where to connect.
 * This decouples the server's ephemeral (port 0 -> OS-assigned) bind from the windows: the owner does not
 * have to know the port in advance, only the agreed file path.
 *
 * <p><b>Format</b> — two lines, deliberately trivial so multiple independent readers agree:
 * <pre>
 * host=&lt;dotted-quad or hostname&gt;
 * port=&lt;integer&gt;
 * </pre>
 * This is byte-for-byte the format {@code PilotIntentCombatPlugin.readPortFile} and
 * {@code ClientLaunchKnobs.readPortFile} already parse (split on the first {@code =}, trim key+value,
 * read the {@code host}/{@code port} keys, integer-validate the port). Starsector 0.98a sandboxes mod
 * code and blocks {@code java.nio.file.*}, so this uses the allowlisted
 * {@code FileReader}/{@code FileWriter} both sides can call.
 */
public final class PilotPortFile {

    private PilotPortFile() {
    }

    /** Write the two-line {@code host=}/{@code port=} endpoint file the windows read. */
    public static void write(String path, String host, int port) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(path))) {
            w.write("host=" + host);
            w.newLine();
            w.write("port=" + port);
            w.newLine();
        }
    }

    /**
     * Read {@code {host, port}} from a port-file, or {@code null} when the file is missing, unreadable,
     * partial (one of the two keys absent), or carries a non-integer port — exactly the lenient,
     * fail-to-{@code null} contract the in-game readers implement, so a window started before the scout
     * has written the file simply leaves its session closed rather than crashing.
     */
    public static String[] read(String path) {
        String host = null;
        String port = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if ("host".equals(key)) {
                    host = value;
                } else if ("port".equals(key)) {
                    port = value;
                }
            }
        } catch (IOException e) {
            return null;
        }
        if (host == null || host.isBlank() || port == null || port.isBlank()) {
            return null;
        }
        try {
            Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return null;
        }
        return new String[] {host, port};
    }
}
