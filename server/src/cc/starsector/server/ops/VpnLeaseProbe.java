package cc.starsector.server.ops;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ProtonVPN forward probe behind "Fill from ProtonVPN" and the owner-mode pull - ONE
 * truth for BOTH console modes, in-process, never a child process. WHY: the package console
 * ships without PowerShell and the repo script, yet needs the same answer the owner-mode
 * path gets; one Java grammar replaces the spawn for both, so the two modes cannot drift.
 *
 * <p>Provenance: the grammar of the lease script, each constant naming
 * the script step it twins, with three deliberate changes: the gateway is asked FIRST (a silent
 * gateway opens no file), the log read is BOUNDED (≤ {@link #MAX_LOG_FILES} files ×
 * {@link #TAIL_BYTES} tails, lines ≤ {@link #MAX_LINE_CHARS}), and a non-global answer is
 * refused ({@link #N2}). What leaves the log parser is a port (1..65535) and a stamp - never
 * a line's text.
 *
 * <p>Threads: none of its own. {@link #pull} runs on the caller's thread (the Swing lease
 * thread, a web pool thread), sends ONE 2-byte datagram per call, holds the caller at most
 * {@link Env#timeoutMs} on the gateway, and never asks for a mapping (the ProtonVPN app owns
 * the forward and its renewal).
 *
 *
 */
public final class VpnLeaseProbe {

    private VpnLeaseProbe() {
    }

    /** The ProtonVPN TUN gateway (the lease script's {@code $Gateway}). */
    static final String GATEWAY_HOST = "10.2.0.1";

    /** The NAT-PMP port the gateway answers on (the port the lease script dials). */
    static final int GATEWAY_PORT = 5351;

    /** The ONE receive's ceiling in ms (the lease script's {@code ReceiveTimeout = 3000}). */
    static final int ASK_TIMEOUT_MS = 3000;

    /** The external-address reply's length: ver, op, result[2], epoch[4], IPv4[4] (the lease script's reply check). */
    static final int REPLY_MIN_BYTES = 12;

    /** Log files read, newest first (the script walks every file; the bound is the console's). */
    static final int MAX_LOG_FILES = 2;

    /** Bytes read from the END of each log file (the script reads whole files; the bound is the console's). */
    static final int TAIL_BYTES = 512 * 1024;

    /** A log line longer than this is skipped unread (no script twin: the parse-surface bound). */
    static final int MAX_LINE_CHARS = 8192;

    /** A port line older than this many minutes = the port NOT found (the lease script's {@code $MaxLeaseAgeMin}). */
    static final int MAX_AGE_MIN = 10;

    /** The ProtonVPN app's log files (the lease script's {@code -Filter}). */
    static final String LOG_GLOB = "client-logs*.txt";

    /** The forwarded-port line; group 2 = the port (the lease script's pattern; CASE_INSENSITIVE = Select-String's default). */
    static final Pattern PORT_PAIR = Pattern.compile("Port pair (\\d+)->(\\d+)", Pattern.CASE_INSENSITIVE);

    /** The line's leading stamp, as the lease script reads it. */
    static final Pattern STAMP = Pattern.compile("^(\\S+) \\|");

    /** The gateway did not answer. */
    static final String N1 = "ProtonVPN's gateway (10.2.0.1) did not answer within 3 seconds."
            + " Connect ProtonVPN with port forwarding on and press again, or type the address"
            + " and port yourself. Nothing was changed.";

    /** The gateway answered, but not a NAT-PMP success carrying a public IPv4. */
    static final String N2 = "The gateway at 10.2.0.1 answered, but not with a public address -"
            + " this button works with ProtonVPN only. Nothing was changed.";

    /** The socket's own local address (the TUN bind host) was unreadable. */
    static final String N3 = "This computer's VPN address could not be read. Reconnect ProtonVPN"
            + " and press again. Nothing was changed.";

    /** The ask when no port was found and none was typed. */
    static final String P1 = "Type the port ProtonVPN shows into port (1 to 65535), then press again.";

    /** Port-not-found why: a log directory is named but absent (no ProtonVPN Windows app). */
    static final String WHY_NO_APP_LOG = "no ProtonVPN Windows app log on this computer";

    /** Port-not-found why: the logs hold no port line. */
    static final String WHY_NO_PORT_LINE = "the ProtonVPN log names no forwarded port - turn on port"
            + " forwarding and reconnect";

    /** Port-not-found why: the newest port line is over {@link #MAX_AGE_MIN} old; {@code %d} = whole minutes. */
    static final String WHY_STALE = "ProtonVPN last logged its port %d min ago - if players can still"
            + " join, only the log stopped";

    /** Port-not-found why: no log directory at all ({@code LOCALAPPDATA} unset = not Windows). */
    static final String WHY_LINUX = "on Linux the console cannot read the port";

    /** A fill that found the port: {@code tun, port, exit, port}. */
    static final String NOTE_FOUND = "ProtonVPN: listening on %s:%d, players connect to %s:%d";

    /** A fill without a port: {@code %s} = the why. */
    static final String NOTE_ADDRESS_ONLY = "ProtonVPN address filled; its forwarded port was not"
            + " found (%s) - type the port ProtonVPN shows into port";

    /* The shells' texts for a press that waits on, or falls back from, the gateway:
     * ONE home for the Swing window and the web console; owner mode's console
     * keeps its own words. */

    /** A package press is waiting on the gateway (the Swing note; the page shows its button busy). */
    static final String NOTE_ASKING = "Asking ProtonVPN - this press runs on its result";
    /** A package press while a probe already runs (the Swing one-waiter law): it
     * runs on that probe's answer; a third press is ignored. */
    static final String NOTE_QUEUED = "Asking ProtonVPN - this press runs on its answer";
    static final String NOTE_QUEUED_IGNORED = "Asking ProtonVPN and a press already waits on it"
            + " - ignored";

    /** Start in the ProtonVPN mode with the gateway silent: the typed pair stands unchecked;
     * {@code %s} = the host:port the server was started on. */
    static final String NOTE_START_UNCHECKED = "ProtonVPN did not answer, so the address was not"
            + " checked again: started on %s. If players cannot join, reconnect ProtonVPN, then Kill"
            + " and Start.";

    /** Copy join brick in the ProtonVPN mode with the gateway silent: the saved address stands. */
    static final String NOTE_BRICK_UNCHECKED = "ProtonVPN did not answer, so this code carries the"
            + " address from the last check.";

    /** The probe could not even ask - the socket would not open, or the log folder could not be
     * listed ({@link #pull}'s {@link IOException}); {@code %s} = the exception's simple name. */
    static final String N4 = "The ProtonVPN gateway could not be asked (%s). Nothing was changed.";

    /** Log stamps that matched {@link #STAMP} but parsed as no date (the hit keeps a null stamp = no age gate);
     * read by {@code VpnLeaseSelftest}'s {@code lease-log-line} row. */
    static final AtomicLong STAMPS_UNPARSED = new AtomicLong();

    /**
     * What a pull reads from - the seam the selftest swaps (a loopback gateway, a temp log dir,
     * a fixed clock). {@code gatewayPort} 1..65535; {@code timeoutMs} > 0 (0 would block
     * forever); {@code logDir} null = no log to read (not Windows).
     */
    public record Env(InetAddress gateway, int gatewayPort, int timeoutMs, Path logDir, Clock clock) {
        public Env {
            if (gateway == null || clock == null) {
                throw new IllegalArgumentException("gateway and clock are required");
            }
            if (gatewayPort < 1 || gatewayPort > 65535 || timeoutMs <= 0) {
                throw new IllegalArgumentException("gatewayPort " + gatewayPort + " / timeoutMs "
                        + timeoutMs + " out of range");
            }
        }

        /** The real host: {@link #GATEWAY_HOST} as bytes (never a name lookup), the
         * {@code %LOCALAPPDATA%\Proton\Proton VPN\Logs} dir (null when unset), the system clock. */
        public static Env live() {
            InetAddress gateway;
            try {
                gateway = InetAddress.getByAddress(new byte[] {10, 2, 0, 1});
            } catch (UnknownHostException impossible) {
                // getByAddress throws only for a length other than 4 or 16
                throw new IllegalStateException(impossible);
            }
            String appData = System.getenv("LOCALAPPDATA");
            Path logDir = appData == null || appData.isBlank()
                    ? null : Paths.get(appData, "Proton", "Proton VPN", "Logs");
            return new Env(gateway, GATEWAY_PORT, ASK_TIMEOUT_MS, logDir, Clock.systemDefaultZone());
        }
    }

    /**
     * One pull's result. {@code port} 1..65535 = found ({@code portWhy} null); 0 = NOT found and
     * {@code portWhy} is the sentence why (a stale stamp included). {@code ageMin} = minutes
     * since the port line's stamp ({@code >= 0}; a future stamp reads 0), -1 = no stamp read.
     */
    public record Probe(String exitIp, String tunIp, int port, String portWhy, double ageMin) {
        public boolean portFound() {
            return port > 0;
        }

        /** Owner mode: the lease, or the script's refusal kept by mechanism - port 0 throws its why. */
        public ServerOpsCore.VpnLease toLease() {
            if (port == 0) {
                throw new IllegalStateException(portWhy);
            }
            return new ServerOpsCore.VpnLease(exitIp, port, tunIp);
        }
    }

    /** What a package fill puts in the form: {@code bindPort} may be blank (no port found, none typed). */
    public record Fill(String bindHost, String bindPort, String advertise, boolean portFound, String note) {
    }

    /** The gateway's answer: the public exit address and this socket's own (TUN-side) address. */
    record Answer(String exitIp, String tunIp) {
    }

    /** A port line: {@code port} 1..65535; {@code stamp} null when absent or unparsable. */
    record LogHit(int port, Instant stamp) {
    }

    /** One file's tail as lines; {@code whole} = the read started at byte 0. */
    private record Tail(List<String> lines, boolean whole) {
    }

    /** The package fill - pure: the found port wins, else the typed field (trimmed, may be blank). */
    public static Fill fill(Probe p, String typedPortField) {
        String typed = typedPortField == null ? "" : typedPortField.trim();
        if (p.portFound()) {
            String port = Integer.toString(p.port());
            return new Fill(p.tunIp(), port, p.exitIp() + ":" + port, true, String.format(Locale.ROOT,
                    NOTE_FOUND, p.tunIp(), p.port(), p.exitIp(), p.port()));
        }
        String note = String.format(Locale.ROOT, NOTE_ADDRESS_ONLY, p.portWhy());
        if (typed.isEmpty()) {
            note = note + " " + P1;
        }
        return new Fill(p.tunIp(), typed, p.exitIp() + ":" + typed, false, note);
    }

    /**
     * The pull: the gateway FIRST ({@link #ask} - a silent one throws and no file is opened),
     * then the log scan. Throws {@link IllegalStateException} N1-N3; an {@link IOException} =
     * the socket could not open or the log directory could not be listed.
     */
    public static Probe pull(Env env) throws IOException {
        Answer answer = ask(env);
        Path dir = env.logDir();
        LogHit hit = scanDir(dir);
        if (hit == null) {
            String why = dir == null ? WHY_LINUX
                    : Files.isDirectory(dir) ? WHY_NO_PORT_LINE : WHY_NO_APP_LOG;
            return new Probe(answer.exitIp(), answer.tunIp(), 0, why, -1);
        }
        double age = hit.stamp() == null ? -1 : Math.max(0, minutesSince(hit.stamp(), env.clock()));
        String why = ageWhy(hit, env.clock());
        if (why != null) {
            return new Probe(answer.exitIp(), answer.tunIp(), 0, why, age);
        }
        return new Probe(answer.exitIp(), answer.tunIp(), hit.port(), null, age);
    }

    /**
     * ONE NAT-PMP external-address request (RFC 6886 ver 0 / op 0, READ-ONLY) on a connected
     * socket: send exactly {@code 00 00}, receive ONE reply within {@code timeoutMs}, no
     * retransmit. The tun address is the socket's local address read before close.
     */
    static Answer ask(Env env) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(env.timeoutMs());
            byte[] reply = new byte[64];
            DatagramPacket in = new DatagramPacket(reply, reply.length);
            try {
                socket.connect(new InetSocketAddress(env.gateway(), env.gatewayPort()));
                socket.send(new DatagramPacket(new byte[] {0, 0}, 2));
                socket.receive(in);
            } catch (IOException silent) {
                // a timeout, a refused port or no route: the gateway is not there
                throw new IllegalStateException(N1, silent);
            }
            String exit = decodeExitIp(reply, in.getLength());
            if (exit == null || !globalIpv4(exit)) {
                throw new IllegalStateException(N2);
            }
            InetAddress local = socket.getLocalAddress();
            if (local == null || local.isAnyLocalAddress()) {
                throw new IllegalStateException(N3);
            }
            return new Answer(exit, local.getHostAddress());
        }
    }

    /** The reply's IPv4 as a dotted quad, or null: short, not op 128, or a nonzero result.
     * Structural only - the version byte is not checked (parity with the lease script's reply check). */
    static String decodeExitIp(byte[] reply, int len) {
        if (reply == null || len < REPLY_MIN_BYTES || reply.length < REPLY_MIN_BYTES) {
            return null;
        }
        if (reply[1] != (byte) 128) {
            return null;
        }
        if ((((reply[2] & 0xFF) << 8) | (reply[3] & 0xFF)) != 0) {
            return null;
        }
        return (reply[8] & 0xFF) + "." + (reply[9] & 0xFF) + "." + (reply[10] & 0xFF) + "."
                + (reply[11] & 0xFF);
    }

    /** True for a public IPv4: false for 0/8, 10/8, 100.64/10, 127/8, 169.254/16, 172.16/12,
     * 192.168/16, first octet ≥ 224, and anything that is not a dotted quad. */
    static boolean globalIpv4(String dotted) {
        if (dotted == null) {
            return false;
        }
        String[] parts = dotted.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        int[] q = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3
                    || !parts[i].chars().allMatch(c -> c >= '0' && c <= '9')) {
                return false;
            }
            q[i] = Integer.parseInt(parts[i]);
            if (q[i] > 255) {
                return false;
            }
        }
        int a = q[0];
        int b = q[1];
        boolean special = a == 0 || a == 10 || a == 127 || a >= 224
                || (a == 100 && b >= 64 && b <= 127)
                || (a == 169 && b == 254)
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168);
        return !special;
    }

    /** The LAST port line (scanning from the end): a line over {@link #MAX_LINE_CHARS} is
     * skipped, a port outside 1..65535 is no hit (the scan goes on); null = no line. Pure. */
    static LogHit scanLines(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line == null || line.length() > MAX_LINE_CHARS) {
                continue;
            }
            Matcher m = PORT_PAIR.matcher(line);
            if (!m.find()) {
                continue;
            }
            String digits = m.group(2);
            int port = digits.length() > 5 ? -1 : Integer.parseInt(digits);
            if (port < 1 || port > 65535) {
                continue;
            }
            return new LogHit(port, stampOf(line));
        }
        return null;
    }

    /** The line's {@link #STAMP} as an instant: an offset date-time, else a local one at this
     * zone; null when absent or unparsable (the latter counted in {@link #STAMPS_UNPARSED}). */
    static Instant stampOf(String line) {
        Matcher m = STAMP.matcher(line);
        if (!m.find()) {
            return null;
        }
        try {
            TemporalAccessor t = DateTimeFormatter.ISO_DATE_TIME.parseBest(m.group(1),
                    OffsetDateTime::from, LocalDateTime::from);
            return t instanceof OffsetDateTime o ? o.toInstant()
                    : ((LocalDateTime) t).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeException unparsable) {
            STAMPS_UNPARSED.incrementAndGet();
            return null;
        }
    }

    /**
     * The newest port line under {@code dir}: the ≤ {@link #MAX_LOG_FILES} newest
     * {@link #LOG_GLOB} files (by modified time), the last {@link #TAIL_BYTES} of each. The next
     * file is read only when this one was read WHOLE without a line (a 0 B newest file = the
     * rotation case the lease script names); a tail miss on a longer file ends the scan with
     * null. A per-file read failure (a file rotating or locked under the read) skips that file:
     * the scan goes on, and a port found nowhere reaches the host as its why sentence - no meter
     * (an earlier counter had no reader).
     */
    static LogHit scanDir(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        record Dated(Path path, long modified) {
        }
        List<Dated> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, LOG_GLOB)) {
            for (Path p : stream) {
                try {
                    if (Files.isRegularFile(p)) {
                        files.add(new Dated(p, Files.getLastModifiedTime(p).toMillis()));
                    }
                } catch (IOException oneFile) {
                    // this file vanished or locked between the listing and its stat: skipped
                }
            }
        } catch (DirectoryIteratorException listing) {
            throw listing.getCause();
        }
        files.sort(Comparator.comparingLong(Dated::modified).reversed());
        for (int i = 0; i < Math.min(MAX_LOG_FILES, files.size()); i++) {
            Tail tail;
            try {
                tail = readTail(files.get(i).path());
            } catch (IOException oneFile) {
                // unreadable under the read (rotating / locked): the next newest file, if any
                continue;
            }
            LogHit hit = scanLines(tail.lines());
            if (hit != null) {
                return hit;
            }
            if (!tail.whole()) {
                return null;
            }
        }
        return null;
    }

    /** The last {@link #TAIL_BYTES} of {@code file} as UTF-8 lines (malformed bytes replaced);
     * a read that starts mid-file drops its first, partial line. */
    private static Tail readTail(Path file) throws IOException {
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = ch.size();
            long start = Math.max(0, size - TAIL_BYTES);
            ch.position(start);
            ByteBuffer buf = ByteBuffer.allocate((int) (size - start));
            while (buf.hasRemaining() && ch.read(buf) > 0) {
                // fill the tail; a file cut shorter mid-read ends at its new end
            }
            buf.flip();
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)
                        .decode(buf).toString();
            } catch (CharacterCodingException cannotWithReplace) {
                // REPLACE never throws; a throw here is a read failure of this file
                throw new IOException(cannotWithReplace);
            }
            List<String> lines = new ArrayList<>(text.lines().toList());
            if (start > 0 && !lines.isEmpty()) {
                lines.remove(0);
            }
            return new Tail(lines, start == 0);
        }
    }

    /** The stale why, or null: no stamp, a future stamp, or ≤ {@link #MAX_AGE_MIN} minutes old. */
    static String ageWhy(LogHit hit, Clock clock) {
        if (hit == null || hit.stamp() == null) {
            return null;
        }
        double minutes = minutesSince(hit.stamp(), clock);
        if (minutes < 0) {
            return null;
        }
        if (minutes > MAX_AGE_MIN) {
            return String.format(Locale.ROOT, WHY_STALE, Math.round(minutes));
        }
        return null;
    }

    /** Minutes from {@code stamp} to the clock's now; negative = a future stamp. */
    private static double minutesSince(Instant stamp, Clock clock) {
        return Duration.between(stamp, clock.instant()).toMillis() / 60_000.0;
    }
}
