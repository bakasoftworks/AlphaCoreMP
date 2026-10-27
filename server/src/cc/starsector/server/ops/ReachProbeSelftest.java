package cc.starsector.server.ops;

import cc.starsector.server.runtime.DatagramFrameCodec;
import cc.starsector.server.runtime.Handshake13;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Rows for {@link ReachProbe} (the reachability self-dial): the probe datagram's shape, the answer test, the dial's
 * bounds against a scripted loopback responder (shrunk {@link ReachProbe.Dials}: 20 ms gap, 300 ms wait, so the
 * class runs in well under 5 s), the refused-port behaviour this OS gives, the no-credential-input law and the
 * text law. The REAL door's side (its COOKIE13 and meters) = {@code ReachProbeDoorRows} in server/test. Every nonce
 * runs on BOTH signs. Output: {@code PASS <row>} / {@code FAIL <row>: <the regression that flips it>} /
 * {@code SETUP-<row>: ...} (a fixture that never staged - never a kill); {@link #run} returns the failure count.
 *
 *
 */
public final class ReachProbeSelftest {

    /** The rows' clock: 3 sends 20 ms apart, 300 ms after the last; the design's 2048 B buffer and 32 foreign. */
    static final ReachProbe.Dials FAST = new ReachProbe.Dials(3, 20L, 300L, 2_048, 32);
    private static final String LOOPBACK = "127.0.0.1";
    private static final String FIXTURE_PASSWORD = "reach-Fixture-Pw-1462";
    private static final long[] SIGNS = {1L, -1L};
    private static final SecureRandom RNG = new SecureRandom();
    private static final List<Pattern> TOKENS = List.of(
            Pattern.compile("TASK-|REVIEW-|\\b(?:ADR|INV|CAP|SOL|TRACE|RE)-\\d{2,}"),
            Pattern.compile("\\bs(?!(?:16|32|64)\\b)\\d{2,4}\\b"),
            Pattern.compile("\\bwq|wf_|\\bdocs[\\\\/]|\\bgenerated[\\\\/]|\\blocal[\\\\/]"));
    private static int failures;

    private ReachProbeSelftest() {
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    /** Every row, the summary line, the failure count returned. */
    public static int run() {
        failures = 0;
        row("reach-open-shape", ReachProbeSelftest::openShape);
        row("reach-wrong-nonce", ReachProbeSelftest::wrongNonce);
        row("reach-silent-bound", ReachProbeSelftest::silentBound);
        row("reach-foreign-ignored", ReachProbeSelftest::foreignIgnored);
        row("reach-local", ReachProbeSelftest::local);
        row("reach-refused", ReachProbeSelftest::refused);
        row("reach-no-password-input", ReachProbeSelftest::noPasswordInput);
        row("reach-text-law", ReachProbeSelftest::textLaw);
        System.out.println(failures == 0 ? "ReachProbeSelftest: every row PASS"
                : "ReachProbeSelftest: " + failures + " row(s) did not pass");
        return failures;
    }

    // ---- rows ----

    private static void openShape() {
        for (long nonce : nonces(0x1462_0001L)) {
            byte[] b = ReachProbe.open(nonce);
            require(b.length == 159 && ReachProbe.OPEN_DATAGRAM_BYTES == 159, "the probe is " + b.length
                    + " B, not the client's 159 B F1 (flips if the OPEN pad or the framing changes)");
            DatagramFrameCodec.Frame f = DatagramFrameCodec.decode(b, b.length);
            require(f != null && f.channel() == DatagramFrameCodec.WireChannel.HELLO && f.roundId() == 0
                    && f.sessionId() == 0L && f.seq() == 0L && f.ctrlAck() == 0L && f.ctrlAckBits() == 0
                    && f.snapHiSeen() == 0L, "the probe no longer decodes as the client flight's F1 frame (HELLO,"
                    + " round 0, session 0, seq/acks 0) - flips if the probe stops framing as Handshake13Flight does");
            Handshake13.Open open = Handshake13.decodeOpenOrNull(f.payload());
            require(open != null && open.nonce() == nonce && open.flags() == 0 && f.payload()[10] == 0,
                    "the probe's OPEN does not carry nonce " + nonce + " with flags 0 and byte [10] = 0 (flips if a"
                    + " flag or a credential byte is added, or the nonce is lost)");
            require(indexOf(b, FIXTURE_PASSWORD.getBytes(StandardCharsets.US_ASCII)) < 0,
                    "the probe datagram holds the fixture password's bytes (flips if any credential is framed in)");
        }
    }

    private static void wrongNonce() {
        for (long nonce : nonces(0x1462_0002L)) {
            byte[] good = cookie(nonce);
            require(ReachProbe.answers(good, nonce), "a WELCOME COOKIE13 echoing the nonce is not an answer (flips"
                    + " if answers() stops accepting the door's reply)");
            require(!ReachProbe.answers(cookie(nonce + 1L), nonce), "a COOKIE13 for nonce+1 counts as the answer"
                    + " (flips if answers() stops comparing the echo)");
            require(!ReachProbe.answers(onHello(good), nonce), "a cookie payload on the HELLO channel counts as the"
                    + " answer (flips if answers() stops checking the WELCOME channel)");
            byte[] crc = good.clone();
            crc[crc.length - 1] ^= 1;
            require(!ReachProbe.answers(crc, nonce) && !ReachProbe.answers(null, nonce)
                    && !ReachProbe.answers(ReachProbe.open(nonce), nonce), "a bad-CRC cookie, null or the probe's"
                    + " own OPEN counts as the answer (flips if answers() skips the codec's frame checks)");
        }
        long nonce = RNG.nextLong();
        Run r = dialScript(nonce, List.of(cookie(nonce + 1L)));
        require(r.result.verdict() == ReachProbe.Verdict.SILENT && "silent".equals(r.result.reason()),
                "a dial answered only by a nonce+1 cookie ended " + r.result.verdict() + "/" + r.result.reason()
                        + " (flips if the dial takes a wrong-nonce cookie as the answer)");
        require(r.probes.size() == FAST.sends(), "an unanswered dial sent " + r.probes.size() + " probes, not "
                + FAST.sends() + " (flips if the dial stops resending or sends past its bound)");
        for (byte[] p : r.probes) {
            require(Arrays.equals(p, ReachProbe.open(nonce)), "the dial sent a datagram that is not open(nonce)");
        }
    }

    private static void silentBound() {
        long nonce = RNG.nextLong();
        Run flood = dialScript(nonce, foreign(40));
        require(flood.result.verdict() == ReachProbe.Verdict.SILENT && "silent".equals(flood.result.reason())
                && flood.result.ms() < FAST.waitMs(), "40 foreign datagrams ended the dial "
                + flood.result.verdict() + "/" + flood.result.reason() + " after " + flood.result.ms()
                + " ms, not SILENT before the " + FAST.waitMs() + " ms wait (flips if the foreign cap is dropped)");
        List<byte[]> edge = new ArrayList<>(foreign(FAST.foreignMax() + 1));
        edge.add(cookie(nonce));
        Run past = dialScript(nonce, edge);
        require(past.result.verdict() == ReachProbe.Verdict.SILENT, "33 foreign datagrams then the answer ended "
                + past.result.verdict() + " - the dial read a 34th datagram (flips if the cap of 32 foreign widens)");
        boolean refused;
        try {
            new ReachProbe.Dials(4, 400L, 3_000L, 2_048, 32);
            refused = false;
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        require(refused, "Dials accepts 4 sends (flips if the 3-datagram outbound bound per dial is lifted)");
    }

    private static void foreignIgnored() {
        for (long nonce : nonces(0x1462_0004L)) {
            List<byte[]> script = new ArrayList<>(foreign(FAST.foreignMax() - 3));
            script.add(cookie(nonce + 1L));
            script.add(onHello(cookie(nonce)));
            script.add(cookie(nonce - 1L));
            script.add(cookie(nonce));
            Run r = dialScript(nonce, script);
            require(r.result.verdict() == ReachProbe.Verdict.REACHED_LOCAL, "32 foreign datagrams then the answer"
                    + " ended " + r.result.verdict() + "/" + r.result.reason() + " (flips if a foreign datagram -"
                    + " junk, a wrong-nonce cookie, a HELLO-channel cookie - ends the dial, or the cap counts 32 as"
                    + " too many)");
        }
    }

    private static void local() throws Exception {
        long nonce = RNG.nextLong();
        // a 200 ms gap (an answer always lands first) and a 450 ms linger (any later send reaches the responder)
        Run r = dialScript(nonce, List.of(cookie(nonce)), new ReachProbe.Dials(3, 200L, 300L, 2_048, 32), 450L);
        String want = "Reached, but 127.0.0.1:" + r.port + " is on this computer or your home network: players on"
                + " the internet cannot dial it.";
        require(r.result.verdict() == ReachProbe.Verdict.REACHED_LOCAL && "local".equals(r.result.reason()),
                "a loopback answer ended " + r.result.verdict() + "/" + r.result.reason() + ", not REACHED_LOCAL/local"
                        + " (flips if the global check is inverted or skipped)");
        require(want.equals(r.result.note()), "the REACHED_LOCAL note drifted from the design sentence: "
                + r.result.note());
        require(r.probes.size() == 1, "an answered dial sent " + r.probes.size() + " probes (flips if the dial"
                + " bursts its sends before listening or keeps sending after the first answer)");
        for (String dotted : List.of("127.0.0.1", "10.1.2.3", "192.168.0.1", "100.64.0.1", "169.254.1.1",
                "172.16.0.1")) {
            require(ReachProbe.local(InetAddress.getByName(dotted)), dotted + " reads as global (flips if the"
                    + " REACHED_LOCAL split stops using VpnLeaseProbe.globalIpv4)");
        }
        for (String dotted : List.of("8.8.8.8", "1.1.1.1", "203.0.114.7")) {
            require(!ReachProbe.local(InetAddress.getByName(dotted)), dotted + " reads as local (flips if a global"
                    + " answer is reported as REACHED_LOCAL)");
        }
        String reached = ReachProbe.note(ReachProbe.Verdict.REACHED, "8.8.8.8:27015", 27015, 12L, false);
        require("Reachable: the server answered at 8.8.8.8:27015 in 12 ms.".equals(reached),
                "the REACHED note drifted from the design sentence: " + reached);
    }

    private static void refused() {
        int closed = stage("a closed loopback port", () -> {
            try (DatagramSocket s = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(LOOPBACK), 0))) {
                return s.getLocalPort();
            }
        });
        ReachProbe.Result r = ReachProbe.dial(LOOPBACK, closed, RNG.nextLong(), FAST);
        // Measured on Windows 11 (Temurin 17): the connected socket's receive throws PortUnreachableException.
        require(r.verdict() == ReachProbe.Verdict.REFUSED && "refused".equals(r.reason()) && r.ms() < FAST.waitMs(),
                "a closed loopback port ended " + r.verdict() + "/" + r.reason() + " after " + r.ms() + " ms, not"
                        + " REFUSED/refused inside the wait (os=" + System.getProperty("os.name") + "; flips if the"
                        + " ICMP refusal is no longer caught as REFUSED)");
        require(r.note().equals(ReachProbe.note(ReachProbe.Verdict.SILENT, LOOPBACK + ":" + closed, closed, r.ms(),
                false)), "a REFUSED note is not the typed No-answer sentence (flips if REFUSED loses its note)");
        // "[::zz]" = a bracketed literal the resolver refuses WITHOUT a network lookup (the catch path, offline-safe).
        ReachProbe.Result dns = ReachProbe.dial("[::zz]", 27015, 1L, FAST);
        ReachProbe.Result blank = ReachProbe.dial(" ", 27015, -1L, FAST);
        ReachProbe.Result bind = ReachProbe.dial(LOOPBACK, 0, -1L, FAST);
        require(dns.verdict() == ReachProbe.Verdict.SILENT && "dns".equals(dns.reason())
                && blank.verdict() == ReachProbe.Verdict.SILENT && "dns".equals(blank.reason())
                && bind.verdict() == ReachProbe.Verdict.SILENT && "bind".equals(bind.reason()),
                "an unparseable host / a blank host / port 0 ended " + dns.reason() + " / " + blank.reason() + " / "
                        + bind.reason() + ", not dns / dns / bind (flips if a lookup or socket failure escapes as a"
                        + " throw or the wrong token)");
    }

    private static void noPasswordInput() {
        Pattern banned = Pattern.compile("password|passwd|token|descriptor|secret|credential", Pattern.CASE_INSENSITIVE);
        List<Class<?>> types = List.of(ReachProbe.class, ReachProbe.Dials.class, ReachProbe.Result.class,
                ReachProbe.Verdict.class);
        Set<String> api = new TreeSet<>();
        for (Class<?> t : types) {
            for (Field f : t.getDeclaredFields()) {
                require(!banned.matcher(f.getName()).find(), t.getSimpleName() + "." + f.getName() + " is a"
                        + " credential-named field (flips if the probe gains a password / token / descriptor input)");
            }
            if (t.isRecord()) {
                for (RecordComponent c : t.getRecordComponents()) {
                    require(!banned.matcher(c.getName()).find(), t.getSimpleName() + " gained component "
                            + c.getName());
                }
            }
            for (Method m : t.getDeclaredMethods()) {
                if (m.isSynthetic()) {
                    continue;
                }
                require(!banned.matcher(m.getName()).find(), t.getSimpleName() + "." + m.getName()
                        + " is a credential-named method");
                for (Parameter p : m.getParameters()) {
                    Class<?> pt = p.getType();
                    require(!(p.isNamePresent() && banned.matcher(p.getName()).find()) && pt != char[].class
                            && !pt.getName().startsWith("cc.starsector.server.match.")
                            && !pt.getName().startsWith("java.nio.file.") && pt != java.io.File.class,
                            t.getSimpleName() + "." + m.getName() + " takes a " + pt.getSimpleName() + " (a char[]"
                                    + " secret, a match type or a file path - flips if a credential input is added)");
                }
                if (t == ReachProbe.class && !Modifier.isPrivate(m.getModifiers())) {
                    api.add(m.getName() + Arrays.toString(Arrays.stream(m.getParameterTypes())
                            .map(Class::getSimpleName).toArray()));
                }
            }
        }
        // javac runs without -parameters here, so a parameter NAME is invisible: the non-private signature set is
        // pinned instead - a new String / byte[] argument (a password) changes it.
        Set<String> want = new TreeSet<>(List.of("answers[byte[], long]", "dial[String, int, long, Dials]",
                "dial[String, int, long, Dials, boolean]", "local[InetAddress]", "note[Verdict, String, int, long,"
                        + " boolean]", "open[long]", "texts[]"));
        require(want.equals(api), "ReachProbe's non-private methods changed: " + api + " (flips if a method gains"
                + " an input - review it for a credential, then re-pin)");
    }

    private static void textLaw() {
        for (String t : ReachProbe.texts()) {
            textOk("template", t);
        }
        for (ReachProbe.Verdict v : ReachProbe.Verdict.values()) {
            for (boolean vpn : new boolean[] {false, true}) {
                String n = ReachProbe.note(v, "203.0.114.7:27015", 27015, 3_800L, vpn);
                textOk(v + (vpn ? " vpn" : " typed") + " note", n);
                require(!n.contains("<advertise>") && !n.contains("<n>") && !n.contains("<port>")
                        && n.contains("203.0.114.7:27015"), v + " note left a placeholder or lost the address: " + n
                        + " (flips if a template gains a placeholder note() does not fill)");
            }
        }
        String vpn = ReachProbe.note(ReachProbe.Verdict.SILENT, "a.example:1", 1, 0L, true);
        String typed = ReachProbe.note(ReachProbe.Verdict.SILENT, "a.example:1", 27016, 0L, false);
        require(vpn.contains("ProtonVPN") && typed.contains("forward UDP port 27016 ") && !typed.contains("ProtonVPN"),
                "the SILENT sentence no longer splits ProtonVPN vs typed (flips if the mode switch is lost)");
        require(ReachProbe.Dials.DEFAULT.ceilingMs() <= 4_000L && ReachProbe.Dials.DEFAULT.sends() == 3
                && ReachProbe.Dials.DEFAULT.bufferBytes() == 2_048 && ReachProbe.Dials.DEFAULT.foreignMax() == 32,
                "the default dial (" + ReachProbe.Dials.DEFAULT + ") breaks the texts' \"4 seconds\" or the"
                        + " design's 3 x 159 B / 2048 B / 32-foreign bounds (flips if a default widens)");
        require(ReachProbe.REASONS.stream().allMatch(s -> s.matches("[a-z]{3,7}")), "a reason token is not a bare"
                + " lower-case word (flips if an address or free text becomes a reason)");
    }

    // ---- the scripted responder ----

    /** One dial against a loopback responder that answers its FIRST probe with {@code script}. */
    private static final class Run {
        ReachProbe.Result result;
        List<byte[]> probes;
        int port;
    }

    private static Run dialScript(long nonce, List<byte[]> script) {
        return dialScript(nonce, script, FAST, 0L);
    }

    /** {@code lingerMs} keeps the responder open after the dial returns, so a late send is still recorded. */
    private static Run dialScript(long nonce, List<byte[]> script, ReachProbe.Dials dials, long lingerMs) {
        Responder responder = stage("a loopback responder", () -> new Responder(script));
        Run r = new Run();
        try {
            r.port = responder.sock.getLocalPort();
            r.result = ReachProbe.dial(LOOPBACK, r.port, nonce, dials);
            if (lingerMs > 0L) {
                stage("the linger", () -> {
                    Thread.sleep(lingerMs);
                    return null;
                });
            }
        } finally {
            responder.close();
        }
        if (responder.fault.get() != null) {
            throw new SetupFailure("the responder failed: " + responder.fault.get());
        }
        require(ReachProbe.REASONS.contains(r.result.reason()) && !r.result.reason().contains(LOOPBACK),
                "reason '" + r.result.reason() + "' is not a bounded token (flips if an address leaks into it)");
        synchronized (responder.probes) {
            r.probes = new ArrayList<>(responder.probes);
        }
        return r;
    }

    private static final class Responder {
        final DatagramSocket sock;
        final List<byte[]> script;
        final List<byte[]> probes = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<String> fault = new AtomicReference<>();
        final Thread thread;
        volatile boolean closed;

        Responder(List<byte[]> script) throws IOException {
            this.sock = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(LOOPBACK), 0));
            this.script = script;
            sock.setSoTimeout(20);
            thread = new Thread(this::serve, "reach-selftest-responder");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            byte[] buf = new byte[2_048];
            while (!closed) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException idle) {
                    continue;
                } catch (IOException e) {
                    if (!closed) {
                        fault.compareAndSet(null, "receive " + e.getClass().getSimpleName());
                    }
                    return;
                }
                probes.add(Arrays.copyOf(p.getData(), p.getLength()));
                if (probes.size() == 1) {
                    for (byte[] d : script) {
                        try {
                            sock.send(new DatagramPacket(d, d.length, p.getSocketAddress()));
                        } catch (IOException e) {
                            if (!closed) { // a burst cut short by close() = the dial already ended (the 40-flood)
                                fault.compareAndSet(null, "send " + e.getClass().getSimpleName());
                            }
                            return;
                        }
                    }
                }
            }
        }

        void close() {
            closed = true;
            sock.close();
            try {
                thread.join(1_000L);
            } catch (InterruptedException e) {
                fault.compareAndSet(null, "join interrupted");
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- fixtures ----

    /** A fixed nonce and a random one, each on BOTH signs (VALUE-DOMAIN: a nonce is any signed long). */
    private static long[] nonces(long fixed) {
        long random = (RNG.nextLong() >>> 1) | 1L;
        return new long[] {fixed * SIGNS[0], fixed * SIGNS[1], random * SIGNS[0], random * SIGNS[1]};
    }

    /** The door's reply shape: a framed WELCOME COOKIE13 (a 17 B cookie, as the door mints). */
    private static byte[] cookie(long nonceEcho) {
        byte[] c = new byte[17];
        byte[] ybM = new byte[32];
        byte[] ybA = new byte[32];
        byte[] salt = new byte[16];
        RNG.nextBytes(c);
        RNG.nextBytes(ybM);
        RNG.nextBytes(ybA);
        RNG.nextBytes(salt);
        return Handshake13.encodeCookie(nonceEcho, c, ybM, ybA, salt);
    }

    /** The same payload re-framed on the HELLO channel. */
    private static byte[] onHello(byte[] welcomeFrame) {
        DatagramFrameCodec.Frame f = DatagramFrameCodec.decode(welcomeFrame, welcomeFrame.length);
        return DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.HELLO, 0, 0L, 0L, 0L, 0, 0L, f.payload());
    }

    /** {@code n} small junk datagrams (8 B: well inside any loopback receive buffer, so none is dropped). */
    private static List<byte[]> foreign(int n) {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byte[] d = new byte[8];
            RNG.nextBytes(d);
            out.add(d);
        }
        return out;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void textOk(String what, String t) {
        require(t.length() <= 400, what + " is over 400 chars (" + t.length() + ")");
        require(t.endsWith("."), what + " no longer ends as a sentence: " + t);
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            require(c >= 0x20 && c <= 0x7E, what + " holds a non-ASCII or control char at " + i
                    + " (flips if a template gains a typographic char)");
        }
        for (Pattern p : TOKENS) {
            require(!p.matcher(t).find(), what + " carries an internal token (" + p.pattern() + "): " + t);
        }
    }

    // ---- harness ----

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface Fixture<T> {
        T get() throws Exception;
    }

    /** A fixture that never staged: prints {@code SETUP-<row>}, never counted as a kill. */
    private static final class SetupFailure extends RuntimeException {
        SetupFailure(String message) {
            super(message);
        }
    }

    private static void row(String name, Body body) {
        try {
            body.run();
            System.out.println("PASS " + name);
        } catch (SetupFailure setup) {
            System.out.println("SETUP-" + name + ": " + setup.getMessage());
            failures++;
        } catch (AssertionError failed) {
            System.out.println("FAIL " + name + ": " + failed.getMessage());
            failures++;
        } catch (Exception | Error unexpected) {
            System.out.println("FAIL " + name + ": unexpected " + unexpected.getClass().getSimpleName());
            failures++;
        }
    }

    private static <T> T stage(String what, Fixture<T> fixture) {
        try {
            return fixture.get();
        } catch (Exception e) {
            throw new SetupFailure(what + ": " + e.getClass().getSimpleName());
        }
    }

    private static void require(boolean ok, String regression) {
        if (!ok) {
            throw new AssertionError(regression);
        }
    }
}
