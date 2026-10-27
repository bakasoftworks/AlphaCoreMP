package cc.starsector.server.ops;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.prefs.BackingStoreException;
import java.util.prefs.InvalidPreferencesFormatException;
import java.util.prefs.Preferences;

/**
 * Carries ONE Java-preferences node of the running account between computers by stdout / stdin: {@code export [<node>]}
 * writes the node's XML to stdout (default node {@value #DEFAULT_NODE}), {@code import} reads one from stdin and flushes
 * it into this account's store. WHY: the engine reads its activation from the preferences store of the account that
 * runs it; a screenless Linux host cannot run the game's launcher to create that node, and the JDK's own export / import
 * carries the account's node byte-exact. It reads and writes ONLY the local store of the account that runs it, on an
 * explicit command - no network, no file of its own, no log row; the XML it emits is the operator's own activation
 * (README: delete any file it is redirected into). Never on the serve path; an operator main in the package's classes.
 *
 *
 */
public final class PrefsNode {
    static final String DEFAULT_NODE = "/com/fs/starfarer";

    private PrefsNode() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out, System.in, System.err));
    }

    static int run(String[] args, OutputStream out, InputStream in, PrintStream err) throws Exception {
        if (args.length >= 1 && args.length <= 2 && "export".equals(args[0])) {
            String node = args.length == 2 ? args[1] : DEFAULT_NODE;
            try {
                if (export(node, out)) {
                    return 0;
                }
            } catch (IllegalArgumentException e) {
                err.println("not a preferences node path: " + node);
                return 2;
            }
            err.println("no preferences node " + node + " for this account");
            return 2;
        }
        if (args.length == 1 && "import".equals(args[0])) {
            importFrom(in);
            out.write("imported\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return 0;
        }
        err.println("usage: PrefsNode export [<node>]  |  PrefsNode import   (default node " + DEFAULT_NODE + ")");
        return 2;
    }

    /** Writes {@code node}'s XML to {@code out}; false (nothing written) when the node is absent - it is NEVER created. */
    static boolean export(String node, OutputStream out) throws IOException, BackingStoreException {
        Preferences root = Preferences.userRoot();
        if (!root.nodeExists(node)) {
            return false;
        }
        root.node(node).exportNode(out);
        out.flush();
        return true;
    }

    /** Reads one preferences XML from {@code in} into this account's store and flushes it. */
    static void importFrom(InputStream in) throws IOException, InvalidPreferencesFormatException, BackingStoreException {
        Preferences.importPreferences(in);
        Preferences.userRoot().flush();
    }
}
