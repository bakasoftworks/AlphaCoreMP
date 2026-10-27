package cc.starsector.server.ops;

import cc.starsector.server.runtime.DatagramFrameCodec;
import cc.starsector.server.runtime.Handshake13;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The reachability self-dial behind "Check from the internet": the console sends the host's OWN advertised address
 * the credential-free hs-13 F1 OPEN - the first datagram every joining client sends - and waits for the running
 * door's COOKIE13. WHY that message: the door answers any well-formed OPEN with a cookie and runs no per-source
 * budget, ban or credential check on it ({@code PidsHello13.onHello} to {@code answerCookie}), so one answer proves
 * the port forward, the real door and the firewall rule for the server's Java at once, while this class never
 * reads a password, token or descriptor - it has no such input ({@code ReachProbeSelftest}
 * {@code reach-no-password-input}; the door side = {@code ReachProbeDoorRows} {@code reach-loopback-cookie}).
 *
 * <p>Bounds: at most {@link Dials#sends} (1..3) datagrams of {@link #OPEN_DATAGRAM_BYTES} B out per dial, ONE
 * connected ephemeral socket, at most ONE DNS lookup on the CALLER's thread, a {@link Dials#bufferBytes} receive
 * buffer, and more than {@link Dials#foreignMax} foreign datagrams ends the dial as {@link Verdict#SILENT}. A reply
 * is data: only the {@link Result} leaves. Nothing is logged here - a caller prints {@link Result#reason} (a bounded
 * token, never the address); {@link Result#note} carries the address for the host's own screen.
 *
 * <p>Threads: none of its own. {@link #dial} blocks its caller for the DNS lookup plus at most
 * {@code (sends - 1) * gapMs + waitMs} (3.8 s at {@link Dials#DEFAULT}) - an operator-console thread, never a serve
 * JVM thread.
 *
 *
 */
public final class ReachProbe {

    /** The framed F1 on the wire: {@code DatagramFrameCodec} framing + the 123 B OPEN payload = 159 B. */
    public static final int OPEN_DATAGRAM_BYTES = DatagramFrameCodec.FRAMING_BYTES + Handshake13.OPEN_PAYLOAD_BYTES;

    /** The host-facing sentences ({@code <advertise>} / {@code <n>} / {@code <port>} filled by {@link #note}). */
    static final String REACHED_TEXT = "Reachable: the server answered at <advertise> in <n> ms.";
    static final String LOCAL_TEXT = "Reached, but <advertise> is on this computer or your home network: players"
            + " on the internet cannot dial it.";
    static final String SILENT_VPN_TEXT = "No answer from <advertise> in 4 seconds. Check that port forwarding is on"
            + " in ProtonVPN, press Fill from ProtonVPN, then End match and Start server again; allow Java through"
            + " the firewall if Windows asked.";
    static final String SILENT_TYPED_TEXT = "No answer from <advertise> in 4 seconds. Many home routers cannot loop a"
            + " packet back in, so this does not prove players cannot join: ask a player to try. If they cannot"
            + " either, forward UDP port <port> to this computer and allow Java through the firewall.";
    /** Shown INSTEAD of dialing when no server is running (the caller's gate; no datagram leaves). */
    public static final String START_FIRST = "Start the server first: this check talks to the running server.";
    /** Shown INSTEAD of dialing when Advertise is blank (the caller's gate; no datagram leaves). */
    public static final String TYPE_ADDRESS = "Type the address players use into the Players connect to box first,"
            + " or press Fill from ProtonVPN.";

    /** Every {@link Result#reason} value: a bounded token, never an address. */
    public static final List<String> REASONS = List.of("reached", "local", "silent", "refused", "dns", "bind");

    /** What one dial found. */
    public enum Verdict {
        /** The door answered and the dialed address is a global IPv4 (or a global IPv6). */
        REACHED,
        /** The door answered, but at a loopback / private / CGNAT / link-local address: strangers cannot dial it. */
        REACHED_LOCAL,
        /** No answering datagram within the window, too many foreign ones, or the dial could not be made. */
        SILENT,
        /** The far end said "nothing listens on that port" (an ICMP port unreachable on the connected socket). */
        REFUSED
    }

    /**
     * One dial's outcome. {@code ms} = elapsed wall time from the call (DNS included), >= 0; {@code note} = the
     * host-facing sentence (holds the advertise address); {@code reason} = one of {@link #REASONS}.
     */
    public record Result(Verdict verdict, long ms, String note, String reason) {
        public Result {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(note, "note");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The dial's numbers; a record so a row can shrink the clock. {@code sends} 1..3 (the per-dial outbound bound),
     * {@code gapMs} 1..1000 between sends, {@code waitMs} 1..5000 after the last send, {@code bufferBytes}
     * {@link DatagramFrameCodec#MAX_DATAGRAM_BYTES}..65536 (a longer datagram still reads as foreign: the codec
     * refuses past the MTU floor), {@code foreignMax} 0..1024. Out of range = {@code IllegalArgumentException}.
     */
    public record Dials(int sends, long gapMs, long waitMs, int bufferBytes, int foreignMax) {
        /** 3 sends 400 ms apart, 3000 ms after the last (a 3.8 s ceiling under the texts' "4 seconds"). */
        public static final Dials DEFAULT = new Dials(3, 400L, 3_000L, 2_048, 32);

        public Dials {
            if (sends < 1 || sends > 3 || gapMs < 1L || gapMs > 1_000L || waitMs < 1L || waitMs > 5_000L
                    || bufferBytes < DatagramFrameCodec.MAX_DATAGRAM_BYTES || bufferBytes > 65_536
                    || foreignMax < 0 || foreignMax > 1_024) {
                throw new IllegalArgumentException("reach dials out of range");
            }
        }

        /** The longest a dial waits after its DNS lookup: {@code (sends - 1) * gapMs + waitMs}. */
        public long ceilingMs() {
            return (sends - 1L) * gapMs + waitMs;
        }
    }

    private ReachProbe() {
    }

    /**
     * The probe datagram: {@code Handshake13.encodeOpen(nonce, 0)} framed exactly as the client's
     * {@code Handshake13Flight} frames its F1 (HELLO channel, round 0, session 0, seq / acks 0) - flags 0, byte
     * [10] = 0 ("no credential follows"), {@link #OPEN_DATAGRAM_BYTES} long. {@code nonce} = any signed long.
     */
    public static byte[] open(long nonce) {
        return DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.HELLO, 0, 0L, 0L, 0L, 0, 0L,
                Handshake13.encodeOpen(nonce, 0));
    }

    /** True only for a WELCOME-channel frame whose payload is a COOKIE13 echoing {@code nonce}; else foreign. */
    public static boolean answers(byte[] datagram, long nonce) {
        return datagram != null && answers(datagram, datagram.length, nonce);
    }

    private static boolean answers(byte[] buf, int length, long nonce) {
        DatagramFrameCodec.Frame frame = DatagramFrameCodec.decode(buf, length);
        if (frame == null || frame.channel() != DatagramFrameCodec.WireChannel.WELCOME) {
            return false;
        }
        Handshake13.Cookie13 cookie = Handshake13.decodeCookieOrNull(frame.payload());
        return cookie != null && cookie.nonceEcho() == nonce;
    }

    /** {@link #dial(String, int, long, Dials, boolean)} with the typed-address SILENT sentence. */
    public static Result dial(String host, int port, long nonce, Dials d) {
        return dial(host, port, nonce, d, false);
    }

    /**
     * Dial {@code host:port} once: resolve (one lookup, an IPv4 answer preferred), connect one ephemeral socket,
     * send {@link #open} {@code d.sends()} times {@code d.gapMs()} apart while listening, and return at the first
     * datagram that {@link #answers} (REACHED, or REACHED_LOCAL when the resolved address is not global), at an ICMP
     * refusal (REFUSED), at more than {@code d.foreignMax()} foreign datagrams or {@code d.ceilingMs()} after the
     * first send (SILENT). Never throws for a network event: a failed lookup / a blank or over-253-char host =
     * SILENT {@code dns}; a port outside 1..65535 or a socket that cannot open or connect = SILENT {@code bind}; a
     * send / receive failure other than the refusal = SILENT {@code silent}. {@code protonVpn} picks the SILENT /
     * REFUSED sentence (the ProtonVPN one vs the typed-address one); the note's address = {@code host:port}, the
     * Advertise text the caller passed in.
     */
    public static Result dial(String host, int port, long nonce, Dials d, boolean protonVpn) {
        Objects.requireNonNull(d, "d");
        long t0 = System.nanoTime();
        String advertise = (host == null ? "" : host) + ":" + port;
        if (host == null || host.isBlank() || host.length() > 253) {
            return done(Verdict.SILENT, t0, advertise, port, protonVpn, "dns");
        }
        if (port < 1 || port > 65_535) {
            return done(Verdict.SILENT, t0, advertise, port, protonVpn, "bind");
        }
        InetAddress to;
        try {
            to = pick(InetAddress.getAllByName(host));
        } catch (UnknownHostException | SecurityException noAddress) {
            return done(Verdict.SILENT, t0, advertise, port, protonVpn, "dns");
        }
        try (DatagramSocket sock = new DatagramSocket()) {
            try {
                sock.connect(to, port);
            } catch (UncheckedIOException | IllegalArgumentException | SecurityException notConnected) {
                return done(Verdict.SILENT, t0, advertise, port, protonVpn, "bind");
            }
            return exchange(sock, to, nonce, d, t0, advertise, port, protonVpn);
        } catch (SocketException noSocket) {
            return done(Verdict.SILENT, t0, advertise, port, protonVpn, "bind");
        }
    }

    /** The send / listen loop on a connected socket; every outcome is a Result (see {@link #dial}). */
    private static Result exchange(DatagramSocket sock, InetAddress to, long nonce, Dials d, long t0,
            String advertise, int port, boolean protonVpn) {
        byte[] probe = open(nonce);
        byte[] buf = new byte[d.bufferBytes()];
        DatagramPacket in = new DatagramPacket(buf, buf.length);
        long gapNs = TimeUnit.MILLISECONDS.toNanos(d.gapMs());
        long start = System.nanoTime();
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(d.ceilingMs());
        long nextSend = start;
        int sent = 0;
        int foreign = 0;
        while (true) {
            long now = System.nanoTime();
            if (sent < d.sends() && now - nextSend >= 0L) {
                try {
                    sock.send(new DatagramPacket(probe, probe.length));
                } catch (PortUnreachableException refused) {
                    return done(Verdict.REFUSED, t0, advertise, port, protonVpn, "refused");
                } catch (IOException sendFailed) {
                    return done(Verdict.SILENT, t0, advertise, port, protonVpn, "silent");
                }
                sent++;
                nextSend += gapNs;
                continue;
            }
            if (now - deadline >= 0L) {
                return done(Verdict.SILENT, t0, advertise, port, protonVpn, "silent");
            }
            long until = sent < d.sends() && nextSend - deadline < 0L ? nextSend : deadline;
            int timeoutMs = (int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(until - now + 999_999L));
            try {
                sock.setSoTimeout(timeoutMs);
                in.setLength(buf.length);
                sock.receive(in);
            } catch (SocketTimeoutException quiet) {
                continue;
            } catch (PortUnreachableException refused) {
                return done(Verdict.REFUSED, t0, advertise, port, protonVpn, "refused");
            } catch (IOException readFailed) {
                return done(Verdict.SILENT, t0, advertise, port, protonVpn, "silent");
            }
            if (answers(buf, in.getLength(), nonce)) {
                return local(to) ? done(Verdict.REACHED_LOCAL, t0, advertise, port, protonVpn, "local")
                        : done(Verdict.REACHED, t0, advertise, port, protonVpn, "reached");
            }
            if (++foreign > d.foreignMax()) {
                return done(Verdict.SILENT, t0, advertise, port, protonVpn, "silent");
            }
        }
    }

    private static Result done(Verdict v, long t0, String advertise, int port, boolean protonVpn, String reason) {
        long ms = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
        return new Result(v, ms, note(v, advertise, port, ms, protonVpn), reason);
    }

    /** The first IPv4 answer (the door binds IPv4), else the first answer. */
    private static InetAddress pick(InetAddress[] all) throws UnknownHostException {
        if (all == null || all.length == 0) {
            throw new UnknownHostException("no address");
        }
        for (InetAddress a : all) {
            if (a instanceof Inet4Address) {
                return a;
            }
        }
        return all[0];
    }

    /**
     * True when strangers on the internet cannot dial {@code a}: an IPv4 that {@link VpnLeaseProbe#globalIpv4}
     * refuses (loopback, private, CGNAT, link-local, multicast / reserved, 0/8); an IPv6 loopback / link- or
     * site-local / wildcard / multicast / unique-local (fc00::/7).
     */
    static boolean local(InetAddress a) {
        if (a instanceof Inet4Address) {
            return !VpnLeaseProbe.globalIpv4(a.getHostAddress());
        }
        byte[] b = a.getAddress();
        return a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress()
                || a.isMulticastAddress() || (b.length == 16 && (b[0] & 0xFE) == 0xFC);
    }

    /**
     * The host-facing sentence for {@code v}: REACHED names the address and the elapsed ms, REACHED_LOCAL the
     * address; SILENT and REFUSED share the "No answer" sentence (no separate refusal text was designed) - the
     * ProtonVPN one when {@code protonVpn}, else the typed-address one naming {@code port}.
     */
    public static String note(Verdict v, String advertise, int port, long ms, boolean protonVpn) {
        String template = switch (Objects.requireNonNull(v, "v")) {
            case REACHED -> REACHED_TEXT;
            case REACHED_LOCAL -> LOCAL_TEXT;
            case SILENT, REFUSED -> protonVpn ? SILENT_VPN_TEXT : SILENT_TYPED_TEXT;
        };
        return template.replace("<n>", Long.toString(ms)).replace("<port>", Integer.toString(port))
                .replace("<advertise>", advertise == null ? "" : advertise);
    }

    /** Every host-facing template this class owns (the text-law row's input). */
    public static List<String> texts() {
        return List.of(REACHED_TEXT, LOCAL_TEXT, SILENT_VPN_TEXT, SILENT_TYPED_TEXT, START_FIRST, TYPE_ADDRESS);
    }
}
