package cc.starsector.client.join;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Properties;

import cc.starsector.server.runtime.HandshakeCrypto;

/**
 * Joiner-GUI field persistence: last-used server address / port / pilot name, so the
 * friend types them once. Lives OUTSIDE the mod folder — %LOCALAPPDATA%\cc-starsector\
 * ({@link #defaultPath}; %APPDATA% before, moved once by {@link #migrateLegacy}) — because (a) the
 * kit packager's Gate 1 asserts deployed==repo byte-identity with only kit-manifest.txt/match.txt
 * exempt, and (b) deploy.ps1 wipes the deployed folder and kit updates re-extract it; the profile
 * survives both, so a kit update keeps the friend's saved entries.
 */
final class JoinSettings {

    final String host;
    final String port;
    final String name;
    /** The shared match password — persisted like host/port so the joiner types
     * it once per match, not per launch; offered to a server ONLY through {@link #rememberedPasswordFor}
     * (bound to {@link #passwordFor}). (The persisted seat-token row died with the GUI
     * handout lane; a pre-1241 file's token= row is simply ignored on load.) */
    final String password;
    /** The seat identity a Launch hands on ({@link #forLaunch}) — never a settings row: it is derived per host
     * from this install's {@code cid-master} file ({@link #hostCid}), so {@link #store} writes no {@code cid} row
     * and {@link #fromProperties} reads none (an older file's row is ignored); {@code ""} from a file.
     * */
    final String cid;
    /** The report/logs tick. {@code "off"} suppresses the
     * Desktop session-report zip and the mod sidelog cc-client.log; a host's debug bit no longer
     * overrides it (a tooling-armed watch keeps its own sidelog by
     * CC_WATCH_SIDELOG instead). Anything else (absent
     * row incl. every file written before this row existed) reads as on, the shipped default. */
    final boolean logs;
    /** The PLAYER's initial-spawn ship pick — persisted like
     * {@code name} (a per-player preference, never match config: the list itself is
     * server-published each open). {@code ""} = server default; an absent row (every pre-L3
     * file) reads as server default. */
    final String ship;
    /** The {@code host:port} the {@link #password} was saved for — the
     * remembered password is offered to that pair ONLY ({@link #rememberedPasswordFor}), so a host edit can never
     * carry it to another server. A file with no {@code passwordFor} row (every pre-binding file) binds a
     * remembered password to its OWN {@code host}/{@code port} rows: a window-written file saved all of them
     * together; an ARM-written one (the arm script merge-writes host/port beside an older
     * password) is the arm script's to freeze BEFORE that write, never this read's.
     * {@code ""} = bound to nothing (no password remembered). */
    final String passwordFor;

    /** {@code passwordFor} rides LAST: a String inserted mid-list would mis-bind silently. */
    JoinSettings(String host, String port, String name, String password, String cid,
            boolean logs, String ship, String passwordFor) {
        this.host = host == null ? "" : host;
        this.port = port == null ? "" : port;
        this.name = name == null ? "" : name;
        this.password = password == null ? "" : password;
        this.cid = cid == null ? "" : cid;
        this.logs = logs;
        this.ship = ship == null ? "" : ship;
        this.passwordFor = passwordFor == null ? "" : passwordFor;
    }

    /**
     * THE one read of the remembered password — {@code ""} unless one is
     * remembered AND {@code host:port} (both trimmed, the host case-folded) is exactly the pair it was saved for.
     * No DNS, no number parse: another spelling of the same server ({@code localhost} vs {@code 127.0.0.1},
     * {@code 07777} vs {@code 7777}) retypes — the safe side. A whole-string compare is unambiguous because a
     * saved port never holds a colon ({@code JoinLaunchPlan.validate} parses it as an int). Never logged.
     */
    String rememberedPasswordFor(String host, String port) {
        if (password.isEmpty() || host == null || host.isBlank() || port == null || port.isBlank()) {
            return "";
        }
        String pair = foldAscii(host.trim()) + ":" + port.trim();
        return pair.equals(foldAscii(passwordFor.trim())) ? password : "";
    }

    /** The binding's case fold, A-Z ONLY: {@code toLowerCase} also folds U+212A
     *  KELVIN SIGN to {@code k}, so a non-ASCII spelling would have matched an ASCII pair — it retypes instead. */
    private static String foldAscii(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return b.toString();
    }

    /**
     * The settings a Launch persists ({@code JoinLaunchGates.commit}). A
     * non-empty {@code password} (typed, or resolved for this very pair) is remembered for {@code host:port}, the
     * pair it was launched to. An EMPTY one (nothing typed, nothing remembered for this pair) leaves the remembered
     * slot UNTOUCHED: the file at {@code path} keeps its password AND its pair (a pre-binding file arrives already
     * bound to its own rows by {@link #fromProperties}), so a launch to a second server neither forgets nor
     * re-binds the first one's. host / port / name / cid / logs / ship update either way.
     */
    static JoinSettings forLaunch(Path path, String host, String port, String name, String password, String cid,
            boolean logs, String ship) {
        if (!password.isEmpty()) {
            return new JoinSettings(host, port, name, password, cid, logs, ship, host + ":" + port);
        }
        JoinSettings prior = load(path);
        return new JoinSettings(host, port, name, prior.password, cid, logs, ship, prior.passwordFor);
    }

    static final String DIR_NAME = "cc-starsector";
    static final String FILE_NAME = "join-settings.properties";
    /** The per-install cid master's OWN file beside {@link #FILE_NAME} in the LOCAL profile ({@link #defaultPath}'s
     *  folder, never the legacy roaming one): not a row of the settings file, which a save rewrites whole and a roaming
     *  profile copies off the box. 32 hex (16 B, SecureRandom), minted once by {@link #hostCid(Path, String, int, boolean)}. */
    static final String CID_MASTER_FILE = "cid-master";
    private static final byte[] CID_HOST_LABEL = "cc-cid-host-v1".getBytes(StandardCharsets.US_ASCII);

    static Path cidMasterPath() {
        return defaultPath().resolveSibling(CID_MASTER_FILE);
    }

    /** {@link #hostCid(Path, String, int, boolean)} on this install's master, minting it when absent. */
    static String hostCid(String host, int port) throws IOException {
        return hostCid(cidMasterPath(), host, port, true);
    }

    /**
     * The client id this install presents to ONE host: 32 hex =
     * HKDF-SHA256(master, info = {@code cc-cid-host-v1} + host folded A-Z to a-z + {@code :} + port)[0..16). One master
     * per install, one id per host: an id a server learns claims no seat at another server, and the master never leaves
     * this method — never a settings row, a plan or handoff row, an argument, a property, a log line or an exception
     * text. The door keys the seat by the id, so it is the same on every relaunch (a file, not a mint per run).
     * {@code mintIfAbsent} false (the headless match refresh writes nothing but the match file) returns null when no
     * master exists; a present file that is not 32 hex is replaced like an absent one — the event is printed, never
     * a value. Another spelling of the same host ({@code localhost} vs its address) is another id: the safe side.
     */
    static String hostCid(Path master, String host, int port, boolean mintIfAbsent) throws IOException {
        byte[] key = readMasterOrNull(master);
        if (key == null) {
            if (!mintIfAbsent) {
                return null;
            }
            Files.deleteIfExists(master); // only an unreadable file is still there; an absent one is simply minted
            key = mintMaster(master);
        }
        byte[] hostPart = (foldAscii(host.trim()) + ":" + port).getBytes(StandardCharsets.UTF_8);
        byte[] info = Arrays.copyOf(CID_HOST_LABEL, CID_HOST_LABEL.length + hostPart.length);
        System.arraycopy(hostPart, 0, info, CID_HOST_LABEL.length, hostPart.length);
        try {
            return hex(HandshakeCrypto.hkdf(key, null, info, 16));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** The master's 16 bytes, or null when the file is absent or not 32 hex (printed without its content). */
    private static byte[] readMasterOrNull(Path master) throws IOException {
        if (!Files.isRegularFile(master)) {
            return null;
        }
        byte[] raw = Files.readAllBytes(master);
        String text = raw.length > 64 ? "" : new String(raw, StandardCharsets.US_ASCII).trim();
        if (!text.matches("[0-9a-f]{32}")) {
            System.err.println("join-settings: cid-master is unreadable - a new one is minted"
                    + " (every server sees a new client id from this install)");
            return null;
        }
        byte[] key = new byte[16];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return key;
    }

    /** {@code CREATE_NEW}: two windows minting at once both keep the FIRST file (the loser re-reads it). */
    private static byte[] mintMaster(Path master) throws IOException {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        Files.createDirectories(master.getParent());
        try {
            Files.write(master, hex(key).getBytes(StandardCharsets.US_ASCII),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            System.err.println("join-settings: cid-master minted");
            return key;
        } catch (FileAlreadyExistsException raced) {
            Arrays.fill(key, (byte) 0);
            byte[] theirs = readMasterOrNull(master);
            if (theirs == null) {
                throw new IOException("cid-master: unreadable after a concurrent mint");
            }
            return theirs;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /**
     * {@code %LOCALAPPDATA%\cc-starsector\join-settings.properties} — the
     * LOCAL (non-roaming) profile: the file holds the remembered match password and its pair (an older one also a
     * retired cid row), and a roaming profile copies it off the box. {@code LOCALAPPDATA} unset, blank or not absolute
     * ⇒ {@code %APPDATA%}, else {@code user.home} (the old order). A run reads it through {@link #pathForRun}.
     */
    static Path defaultPath() {
        return settingsBase(System.getenv("LOCALAPPDATA"), System.getenv("APPDATA"), System.getProperty("user.home"))
                .resolve(DIR_NAME).resolve(FILE_NAME);
    }

    /** {@link #defaultPath}'s base folder, pure (selftested): an absolute {@code localAppData}, else a non-blank
     *  {@code appData}, else {@code userHome}. */
    static Path settingsBase(String localAppData, String appData, String userHome) {
        Path local = absoluteEnvDir(localAppData);
        if (local != null) {
            return local;
        }
        return appData == null || appData.isBlank() ? Paths.get(userHome) : Paths.get(appData);
    }

    /** {@code value} as a folder path when it is set, non-blank and ABSOLUTE; else null (a relative or drive-relative
     *  value would resolve against whatever folder the process started in). Also {@link JoinHost#endpointFile}'s gate. */
    static Path absoluteEnvDir(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Path p = Paths.get(value);
            return p.isAbsolute() ? p : null;
        } catch (InvalidPathException notAPath) {
            return null;   // a value that is no path on this OS = unset: the caller's documented fallback runs
        }
    }

    /** The settings path THIS run reads and a Launch writes — {@link #defaultPath},
     *  after the one-time move of an older {@code %APPDATA%} file ({@link #migrateLegacy}). */
    static Path pathForRun() {
        Path appData = absoluteEnvDir(System.getenv("APPDATA"));
        return migrateLegacy(defaultPath(), appData == null ? null : appData.resolve(DIR_NAME).resolve(FILE_NAME));
    }

    /**
     * The one-time move. {@code current} ABSENT and {@code legacy} a file ⇒ move
     * it to {@code current} and return {@code current}; a present {@code current} is returned untouched and never
     * overwritten. A failed move returns {@code legacy}: this run reads (and a Launch writes) the old file in place —
     * never an empty start, the file holds the remembered password — and the next run tries again; unless {@code current}
     * is a file by then (a second window won the move), which is returned instead ({@link #afterFailedMove}). A plain
     * {@link Files#move}: on one volume a rename that REFUSES an existing target; {@code ATOMIC_MOVE} is not used
     * because on Windows it replaces one (JDK {@code WindowsFileCopy.move}: {@code MOVEFILE_REPLACE_EXISTING}).
     * BOTH files present (delta scan kit-join-window-2) ⇒ the old one is a LEFTOVER — a window that lost the
     * move race, or an older kit, saved it again at Launch — and is deleted: it holds the password and the cid in
     * the roaming profile, and nothing else ever retired it. {@code current} stays the truth.
     */
    static Path migrateLegacy(Path current, Path legacy) {
        if (legacy == null || legacy.equals(current) || !Files.isRegularFile(legacy)) {
            return current;
        }
        if (Files.exists(current)) {
            if (Files.isRegularFile(current)) {
                try {
                    // APPDATA and LOCALAPPDATA naming ONE folder through two strings (a junction, an
                    // 8.3 name, a portable launcher) must never delete the only copy — the equals() above is a string test
                    if (!Files.isSameFile(legacy, current)) {
                        Files.delete(legacy);
                    }
                } catch (IOException | RuntimeException failed) {
                    System.err.println("join-settings: the leftover old settings file could not be removed ("
                            + failed.getClass().getSimpleName() + ") - the next run tries again");
                }
            }
            return current;
        }
        try {
            Files.createDirectories(current.getParent());
            Files.move(legacy, current);
            return current;
        } catch (IOException | RuntimeException failed) {
            return afterFailedMove(current, legacy, failed);
        }
    }

    /**
     * {@link #migrateLegacy}'s failed-move decision,
     * split out so the selftest drives it (row M5). {@code current} a file ⇒ another window opened in the same first
     * run passed the same {@code Files.exists(current)} check and finished the move first: return {@code current},
     * print nothing. Returning {@code legacy} there read a path that no longer exists — a blank form, a freshly minted
     * seat-key cid, and the old roaming file re-created by the next Launch. Else (unchanged) one stderr line naming the
     * exception's class only, and this run reads {@code legacy} in place.
     */
    static Path afterFailedMove(Path current, Path legacy, Exception failed) {
        if (Files.isRegularFile(current)) {
            return current;
        }
        System.err.println("join-settings: the one-time move to the local profile failed ("
                + failed.getClass().getSimpleName() + ") - this run reads the old file in place");
        return legacy;
    }

    /** Missing or unreadable file is not an error — first run has no settings. */
    static JoinSettings load(Path path) {
        Properties props = new Properties();
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException | IllegalArgumentException ignored) {
                // prefill is best-effort (a malformed backslash-u escape in a hand-edited file throws
                // IllegalArgumentException, and the window must still open). Properties.load keeps
                // every row read BEFORE the bad line, so a partially read file never offers a password: its
                // passwordFor row may sit after the bad line, and the legacy binding would then tie it to this
                // file's host/port.
                props.remove("password");
            }
        }
        return fromProperties(props);
    }

    /** {@link #load}'s row read, split out so the selftest drives it in
     *  memory. An absent {@code passwordFor} row = the LEGACY binding: a remembered password belongs to the file's
     *  own host/port rows; with no password there is nothing to bind ({@code ""}). A {@code cid} row is never read
     *  (the per-host id is derived, {@link #hostCid}). */
    static JoinSettings fromProperties(Properties props) {
        String host = props.getProperty("host", "");
        String port = props.getProperty("port", "");
        String password = props.getProperty("password", "");
        return new JoinSettings(host, port, props.getProperty("name", ""), password, "",
                !"off".equalsIgnoreCase(props.getProperty("logs", "on")),
                props.getProperty("ship", ""),
                props.getProperty("passwordFor", password.isEmpty() ? "" : host.trim() + ":" + port.trim()));
    }

    void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path)) {
            store(out);
        }
    }

    /** {@link #save}'s bytes (java.util.Properties: a {@code :} in a value is written {@code \:}), split out so the
     *  selftest round-trips them in memory. */
    void store(OutputStream out) throws IOException {
        Properties props = new Properties();
        props.setProperty("host", host);
        props.setProperty("port", port);
        props.setProperty("name", name);
        props.setProperty("password", password);
        props.setProperty("logs", logs ? "on" : "off");
        props.setProperty("ship", ship);
        props.setProperty("passwordFor", passwordFor);
        props.store(out, "CC joiner launcher — last-used connection entries");
    }
}
