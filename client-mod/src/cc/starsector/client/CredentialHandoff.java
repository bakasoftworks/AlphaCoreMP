package cc.starsector.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

/**
 * The kit credential HANDOFF reader — the in-game half of the read-once file the kit's {@code join-cc.bat} writes per
 * launch pass: {@code %LOCALAPPDATA%\cc-starsector\handoff\<id>.txt}, at most three {@code k=v} rows
 * ({@code password}, {@code cid}, {@code token}). Only the 16-hex file id rides the JVM options
 * ({@code -Dcc.kit.handoff=<id>}), so no credential sits in {@code JAVA_TOOL_OPTIONS}, its {@code HKCU\Environment}
 * copy, or a crash file's property dump.
 *
 * <p>WHY read-once + delete: the file IS the credential at rest. The read opens it {@code DELETE_ON_CLOSE}, reads at
 * most {@link #CAP_BYTES} + 1 bytes, truncates it to 0 (the belt if a delete ever fails) and closes it — the file is
 * gone the moment the game holds the values. {@link #take()} memoizes that one read;
 * {@code PredictDialPlanner.preparePredictDial} calls it FIRST, before any of its returns or throws, so the file is
 * consumed on every path through {@code preparePredictDial}; a throw in an earlier {@code onApplicationLoad} call
 * leaves the file to the launcher's pass-end delete or its start-of-run sweep (once per JVM, never on the frame loop).
 *
 *
 * <p>Fail-closed: an id present makes the handoff the SOLE source of all three credentials. A malformed id, or a
 * missing / oversized / refused / unreadable file, yields BLANK credentials (the planner's existing blank paths),
 * never a fall-through to a leftover {@code CC_PREDICT_*} environment value. No id = the environment twins, as before
 * the handoff existed. The grammar lives here (in-game code may not reference {@code join/**}): a duplicate key, an
 * unknown key, a line without {@code '='} or a bad value refuses the WHOLE file.
 *
 * <p>Classloader B: the reader uses only {@code FileChannel}, {@code Path}, {@code Paths}, {@code StandardOpenOption},
 * {@code ByteBuffer}, {@code StandardCharsets}, {@code IOException} and {@code Pattern} — never {@code Files},
 * {@code File} or any other B-denied {@code java.io.*} / {@code java.nio.file.File*} name (the set the probe
 * measured; a build gate checks it). The catch types are the allowlisted
 * {@code IOException} + {@code RuntimeException} only; a {@code NoSuchFileException} / {@code InvalidPathException} is
 * named off the in-flight exception OBJECT ({@code getSimpleName}). Log law: the one row carries
 * enum tokens, a count, fixed key names and an exception SimpleName — never a value, the id, or the path (it holds
 * the user name).
 *
 * <p>Design rulings: the cid
 * property read stays; a malformed non-blank id counts as present.
 *
 *
 */
final class CredentialHandoff {

    /** How the one read ended. Every outcome but {@link #NO_ID} logs ONE row from {@link #take()}. */
    enum Outcome {
        /** {@code cc.kit.handoff} unset or blank: a non-kit launch — no I/O, no row, the environment twins. */
        NO_ID,
        /** A non-blank id that is not 16 lowercase hex: no file opened; still PRESENT, so the credentials are blank. */
        BAD_ID,
        /** The file parsed: its values (any subset of the three keys, possibly none). */
        READ,
        /** Over {@link #CAP_BYTES}: refused unread, and deleted. */
        OVERSIZE,
        /** The grammar refused the whole file; deleted. */
        REFUSED,
        /** An {@code IOException} or {@code RuntimeException} (no file, no {@code LOCALAPPDATA}, a bad path). */
        FAILED
    }

    /** A file over this many bytes is refused unread (the largest legal file is 217 bytes). */
    static final int CAP_BYTES = 1024;

    /** The bat's own mint: 16 LOWERCASE hex, case-sensitive on purpose (Windows file names are not). */
    private static final Pattern ID = Pattern.compile("[0-9a-f]{16}");

    /** The door's match-password charset; NO length floor — this is a parse bound, length policy stays the door's. */
    private static final Pattern PASSWORD = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,63}");

    /** cid / token: the bat's {@code :vguard} charset, at most 64 (a seat token is <= 64 by the descriptor law). */
    private static final Pattern CID_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    private static CredentialHandoff memo;

    final Outcome outcome;
    /** Accepted {@code k=v} rows: 0..3 on {@link Outcome#READ}, 0 otherwise (a refusal keeps nothing). */
    final int rows;
    /** The handoff values, "" when absent (and always "" unless {@link Outcome#READ}). */
    final String password;
    final String cid;
    final String token;
    /** The failing exception's SimpleName on {@link Outcome#FAILED}, else "". */
    final String failure;

    private CredentialHandoff(Outcome outcome, int rows, String password, String cid, String token, String failure) {
        this.outcome = outcome;
        this.rows = rows;
        this.password = password;
        this.cid = cid;
        this.token = token;
        this.failure = failure;
    }

    private static CredentialHandoff blank(Outcome outcome, String failure) {
        return new CredentialHandoff(outcome, 0, "", "", "", failure);
    }

    /**
     * The MEMO: the first call reads {@code cc.kit.handoff} + {@code LOCALAPPDATA} and consumes the file; every later
     * call returns that same instance (a second read would only find the file already deleted).
     */
    static CredentialHandoff take() {
        return take(System.getProperty("cc.kit.handoff"), System.getenv("LOCALAPPDATA"));
    }

    /**
     * The memo body with its inputs passed in, so the smoke drives the real memo + log row against a scratch
     * directory (never the real profile). The first call reads and logs; later calls ignore their arguments.
     */
    static synchronized CredentialHandoff take(String id, String localAppData) {
        if (memo == null) {
            memo = readFor(id, localAppData);
            if (memo.outcome != Outcome.NO_ID) {
                PilotWatchLog.info(memo.logRow());
            }
        }
        return memo;
    }

    /**
     * The read seam (package-private, never throws): {@code id} = the {@code cc.kit.handoff} value (null / blank =
     * {@link Outcome#NO_ID}); {@code localAppData} = the directory root (null, blank or relative = FAILED — the reader
     * never resolves the handoff against the game's working directory).
     */
    static CredentialHandoff readFor(String id, String localAppData) {
        if (id == null || id.isBlank()) {
            return blank(Outcome.NO_ID, "");
        }
        if (!ID.matcher(id).matches()) {
            return blank(Outcome.BAD_ID, "");
        }
        try {
            if (localAppData == null) {
                throw new NullPointerException("LOCALAPPDATA unset"); // named, not left to Paths.get's JDK-varying null handling
            }
            Path path = Paths.get(localAppData, "cc-starsector", "handoff", id + ".txt");
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("LOCALAPPDATA is blank or not an absolute path");
            }
            ByteBuffer buf = ByteBuffer.allocate(CAP_BYTES + 1);
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                    StandardOpenOption.DELETE_ON_CLOSE)) {
                try {
                    if (ch.size() > CAP_BYTES) {
                        return blank(Outcome.OVERSIZE, "");
                    }
                    while (buf.hasRemaining()) {
                        if (ch.read(buf) <= 0) {
                            break;
                        }
                    }
                } finally {
                    ch.truncate(0L);
                }
            }
            if (buf.position() > CAP_BYTES) {
                return blank(Outcome.OVERSIZE, ""); // it grew past the cap after the size check
            }
            return parse(new String(buf.array(), 0, buf.position(), StandardCharsets.ISO_8859_1));
        } catch (IOException | RuntimeException e) {
            String name = e.getClass().getSimpleName();
            return blank(Outcome.FAILED, name.isEmpty() ? e.getClass().getName() : name);
        }
    }

    /** The INTERFACE grammar over the decoded bytes: any refusal blanks all three (whole-file refusal). */
    private static CredentialHandoff parse(String text) {
        String password = null;
        String cid = null;
        String token = null;
        int rows = 0;
        for (String line : text.split("\n", -1)) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1); // ONE trailing CR per line (the bat's CRLF)
            }
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                return blank(Outcome.REFUSED, "");
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if ("password".equals(key) && password == null && PASSWORD.matcher(value).matches()) {
                password = value;
            } else if ("cid".equals(key) && cid == null && CID_TOKEN.matcher(value).matches()) {
                cid = value;
            } else if ("token".equals(key) && token == null && CID_TOKEN.matcher(value).matches()) {
                token = value;
            } else {
                return blank(Outcome.REFUSED, ""); // unknown key, duplicate key or bad value
            }
            rows++;
        }
        return new CredentialHandoff(Outcome.READ, rows, password == null ? "" : password, cid == null ? "" : cid,
                token == null ? "" : token, "");
    }

    /** {@code cc.kit.handoff} was set (well-formed or not): the handoff is then the SOLE credential source. */
    boolean idPresent() {
        return outcome != Outcome.NO_ID;
    }

    /** The match password: the handoff's when an id is present, else the trimmed non-blank env twin, else "". */
    String password(String env) {
        return idPresent() ? password : ClientLaunchKnobs.firstNonBlank(null, env, "");
    }

    /** The seat-token override: the handoff's when an id is present, else the trimmed non-blank env twin, else "". */
    String token(String env) {
        return idPresent() ? token : ClientLaunchKnobs.firstNonBlank(null, env, "");
    }

    /**
     * The client id: a set property / env twin (trimmed) FIRST — the one explicit override that beats a present
     * handoff: the console's second window rides the env twin while the kit handoff carries window 1's persisted id
     * — else the handoff's when an id is present, else "". The twin must be lawful (the planner throws on one that
     * is not: a dev / console knob, never typed by a player).
     */
    String cid(String prop, String env) {
        String explicit = ClientLaunchKnobs.firstNonBlank(prop, env, "");
        return !explicit.isEmpty() ? explicit : idPresent() ? cid : "";
    }

    /** Whether {@code picked} (a {@link #cid} result) is the handoff's own cid: told by VALUE, so the planner needs
     *  no further read of the twins; a twin set to the very same id counts as the handoff's (same seat, same law). */
    boolean cidIsHandoffs(String picked) {
        return idPresent() && !cid.isEmpty() && cid.equals(picked);
    }

    /**
     * The ONE log row (pure): {@code kit handoff: outcome=<OUTCOME> rows=<n>[ keys=<subset of password,cid,token>]
     * [ cause=<SimpleName>]} — never a value, the id or the path.
     */
    String logRow() {
        StringBuilder keys = new StringBuilder();
        for (String[] kv : new String[][] {{"password", password}, {"cid", cid}, {"token", token}}) {
            if (!kv[1].isEmpty()) {
                keys.append(keys.length() == 0 ? "" : ",").append(kv[0]);
            }
        }
        return "kit handoff: outcome=" + outcome + " rows=" + rows
                + (keys.length() == 0 ? "" : " keys=" + keys)
                + (failure.isEmpty() ? "" : " cause=" + failure);
    }

    /**
     * The planner's Fatal clause when no match password resolved (pure; {@code PredictDialPlanner.preparePredictDial}
     * wraps it in {@code "match password (" … ")"}). WHY it names the cause itself: with session logs OFF (the join
     * window's checkbox) the {@link #logRow()} row is never written, so a Fatal that only pointed at it lost a FAILED
     * read's cause. The text carries the same bounded tokens as that row — the outcome enum name and, only when a
     * failure was recorded, {@code cause=<SimpleName>} — never a value, the id or the path.
     * Pinned by {@code CredentialHandoffSmoke} C12.
     *
     */
    String fatalClause() {
        if (outcome == Outcome.NO_ID) {
            return "no kit handoff id, and env CC_PREDICT_MATCH_PASSWORD is empty";
        }
        String cause = failure.isEmpty() ? "" : " cause=" + failure;
        return "the kit handoff ended " + outcome + cause + " with no password - its 'kit handoff:' log row (written"
                + " when session logs are on) says more; the env twin is ignored once a handoff id is set";
    }
}
