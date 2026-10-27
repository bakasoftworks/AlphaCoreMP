package cc.starsector.server.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The client-debug watch lease. A config FILE's {@code server.clientDebug=true} hands every joiner the debug
 * affordances, and the operator console writes that row for a debug watch; the row outlives the watch, so a
 * later plain serve of the same config would inherit it. The lease closes that gap: the debug affordances live
 * only while the console's lease lives - the {@value #WATCH_LEASE_FILE} beside the loaded config file carries
 * the stamp of the console that wrote the row, and a stamp whose console is gone (or whose pid was reused) leaves
 * debug off. Static and boot-only: the serve reads it once in its boot phase
 * ({@code PilotServeSession.announceAndAwait}), so the one {@link Pattern} compile at class init lands there too,
 * never in the frame loop. Public for {@link #leaseStampPid} alone (the console's stamp writer is pinned to that
 * parse); the rest is package-private or private.
 *
 */
public final class ClientDebugLease {

    private ClientDebugLease() {
    }

    /** The lease file whose live stamp beside the loaded config file lets that FILE's
     *  {@code server.clientDebug=true} take effect ({@link #clientDebugUnderLease}). */
    static final String WATCH_LEASE_FILE = "watch.lease";
    /** At most this many lease bytes are read, the console's own lease-reader bound (a planted long file
     *  never fills memory); a file longer than this is no lease. */
    static final int WATCH_LEASE_READ_MAX = 128;
    /** The console writer's exact stamp shape: {@code <pid>-<nanotime>}, the nanotime a signed long. */
    private static final Pattern LEASE_STAMP = Pattern.compile("(\\d+)-(-?\\d+)");
    /** The widest OS pid (a Windows DWORD); {@code ProcessHandle.of} narrows a wider value natively. */
    private static final long LEASE_PID_MAX = 0xFFFF_FFFFL;

    /**
     * The client-debug lease law: {@code resolved} is the {@code cc.server.clientDebug} resolve (sysprop &gt;
     * env &gt; file). When it is {@code true} and BOTH the sysprop and the env tier are blank, the config FILE
     * supplied it, and it takes effect only while the {@value #WATCH_LEASE_FILE} beside that loaded file
     * ({@link ServerConfig#configFileDirOrNull}) carries the stamp of a console process that is still alive
     * ({@link #leaseCause}); one {@code server_clientdebug} row states the lease ({@code present} /
     * {@code absent} / {@code stale} plus its bounded {@code cause}) and the effective value, never the pid or
     * the stamp. WHY: the console writes the file row for a debug watch and the row outlives the watch, so a
     * later plain serve of the same config would hand every joiner the debug affordances; the console's watch
     * creates the lease and deletes it at watch end, but a console that dies mid-watch (a crash, a kill, a
     * reboot) never deletes it, so the stamp's writer pid is probed: a lease whose console is gone is stale.
     * The writer's liveness plus its start instant against the lease's file time (a reused pid is stale). A
     * sysprop / env {@code true} (the test rigs and the watch tooling arm those tiers) stays true with no row,
     * so those boots print byte-identical output. A file-less serve has no directory = no lease = off
     * (fail-closed). Residency: one bounded file read + one process probe + one file-time read in the serve's
     * boot phase ({@code PilotServeSession.announceAndAwait}, before the first round's frame loop); never per tick.
     *
     */
    static boolean clientDebugUnderLease(boolean resolved) {
        String sysProp = System.getProperty("cc.server.clientDebug");
        String env = System.getenv("CC_SERVER_CLIENT_DEBUG");
        if (!resolved || (sysProp != null && !sysProp.isBlank()) || (env != null && !env.isBlank())) {
            return resolved;
        }
        Path dir = ServerConfig.configFileDirOrNull();
        String cause = dir == null ? "absent" : leaseCause(dir.resolve(WATCH_LEASE_FILE));
        boolean present = "live".equals(cause);
        String lease = present ? "present" : "absent".equals(cause) ? "absent" : "stale";
        System.out.println("npeer-serve server_clientdebug lease=" + lease + " effective=" + present
                + ("stale".equals(lease) ? " cause=" + cause : ""));
        return present;
    }

    /**
     * The verdict on the {@value #WATCH_LEASE_FILE} at {@code lease}, one bounded token: {@code absent} (no
     * regular file), {@code live} (the stamp's console pid has a live process that started no later than the
     * lease's last-modified time + 1,000 ms), {@code reused} (the pid has a live process that started after
     * that, or whose start instant is unreadable), {@code dead} (a well-formed stamp whose pid has none),
     * {@code malformed} (empty, not the writer's {@link #leaseStampPid} shape, or longer than
     * {@value #WATCH_LEASE_READ_MAX} bytes) or {@code fault} (the file probe, the file-time read, the read or
     * the process lookup threw; the caller's one row is its log line). Every verdict but {@code live} leaves
     * debug off: the lease's bytes are untrusted, so a stamp the console never wrote is never a lease.
     *
     *
     * <p>The start-instant check. A console writes its lease after it
     * started, so a process born after the lease was written wears a dead console's recycled pid. The slack
     * covers a second-granular file time and the start instant's own rounding. An unreadable start instant is
     * {@code reused} - fail-closed, the opposite of the Kill order's default (there an unreadable start counts
     * as alive, so a kill stays conservative): here a false {@code live} hands every joiner the debug keys,
     * while a false stale only turns one watch's debug off. The file time is read BEFORE the stamp, so a lease
     * rewritten between the two reads pairs the new stamp with the older time (the stale side). Known bound: a
     * pid reused within the slack of the lease's write reads {@code live} until the next Start rewrites or
     * deletes the lease.
     */
    private static String leaseCause(Path lease) {
        try {
            if (!Files.isRegularFile(lease)) {
                return "absent";
            }
            long writtenMs = Files.getLastModifiedTime(lease).toMillis();
            byte[] head;
            try (InputStream in = Files.newInputStream(lease)) {
                head = in.readNBytes(WATCH_LEASE_READ_MAX + 1);
            }
            long pid = head.length > WATCH_LEASE_READ_MAX ? -1
                    : leaseStampPid(new String(head, StandardCharsets.US_ASCII));
            if (pid < 0) {
                return "malformed";
            }
            ProcessHandle writer = ProcessHandle.of(pid).orElse(null);
            if (writer == null) {
                return "dead";
            }
            long slackMs = 1_000L;
            long startedMs = writer.info().startInstant().map(t -> t.toEpochMilli()).orElse(Long.MAX_VALUE);
            return startedMs <= writtenMs + slackMs ? "live" : "reused";
        } catch (IOException | RuntimeException fault) {
            return "fault";
        }
    }

    /**
     * The console pid in a {@value #WATCH_LEASE_FILE} stamp, or {@code -1} when the text is not the writer's
     * exact shape. The console's debug WATCH Start writes ONE line {@code <pid>-<nanotime>}: its own
     * {@code ProcessHandle.current()} pid and a {@code System.nanoTime} (a signed long, which may be
     * negative). Trimmed, the text must match {@code (\d+)-(-?\d+)} (ASCII digits) with both numbers parsing as
     * a long, so a foreign file that merely starts with digits, a second line, a sign on the pid or a
     * non-ASCII digit is no lease. Range: the pid is in [1, {@value #LEASE_PID_MAX}] (the widest OS pid, a
     * Windows DWORD; on Windows {@code ProcessHandle.of} narrows a wider value natively, so 2^32 plus a live
     * pid would probe that live pid); {@code -1} = malformed (null, blank, no {@code -}, a non-digit, an
     * overflow, a pid of 0 or past the cap). Pure.
     *
     */
    public static long leaseStampPid(String stampOrNull) {
        if (stampOrNull == null) {
            return -1;
        }
        Matcher m = LEASE_STAMP.matcher(stampOrNull.trim());
        if (!m.matches()) {
            return -1;
        }
        try {
            Long.parseLong(m.group(2));
            long pid = Long.parseLong(m.group(1));
            return pid > 0 && pid <= LEASE_PID_MAX ? pid : -1;
        } catch (NumberFormatException overflow) {
            return -1; // an overflowing number is malformed; the caller's row logs it as cause=malformed
        }
    }
}
