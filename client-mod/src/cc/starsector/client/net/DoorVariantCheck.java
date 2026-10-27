package cc.starsector.client.net;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLongArray;

import cc.starsector.server.runtime.DatagramFrameCodec;
import cc.starsector.server.runtime.DoorRejectReasons;
import cc.starsector.server.runtime.Handshake13;
import cc.starsector.server.runtime.Handshake13PairBudget;
import cc.starsector.server.runtime.VariantCheckFrameCodec;

/**
 * The custom-variant PRE-ADMISSION dry-run's client half (W15 v3 VCHK)
 * — {@link DoorProbe}'s B-scanned sibling. After a SERVED probe proved the credential, the
 * joiner window asks the door "would your policy take this local custom?" ONE datagram per local
 * custom: envelope channel {@code VCHK}, payload = a {@link VariantCheckFrameCodec} VCHK13 (nonce,
 * the door's cookie, fingerprint, the sealed wraps — the password never on the wire) sealing the very VSUB
 * frame the game would send at launch
 * ({@link VariantSubmitWriter#encodeFrame}: encoded against the id table the game
 * CACHED at its last boot — {@code JoinCore} loads it and presents the fingerprint FOLDED over that
 * table's digest, so a stale cache is a silent door drop (UNKNOWN), never a garbage verdict; no
 * cache yet = no check, "checked at launch"). The door answers the existing VACK shape on the VCHK
 * channel with envelope {@code sessionId == our nonce} and {@code seq == the submitSeq}; a policy
 * pass echoes the LOCAL id (nothing was minted — the words read "final check at submit").
 *
 * <p>Security posture:
 * fail-closed decode (an undecodable envelope / receipt is IGNORED and counted, never thrown
 * at the caller); bounded allocation (ONE {@link #RECEIVE_BUFFER_BYTES} buffer, at most
 * {@link #MAX_CUSTOMS} checks per fetch); only datagrams from the DIALED address count; a receipt
 * must echo our nonce AND a seq we sent AND the id we sent under that seq; nothing wire-derived
 * becomes a path, a command or a launch byte — a verdict is words beside a file the player already
 * has. FAIL-OPEN by design: silence, an old door (no channel 7), a lost datagram or any fault reads
 * {@link Status#UNKNOWN} ("checked at launch") — the launch-time submit is the real gate, this is
 * courtesy. NO storm: the door budgets one answered check per source per 100 ms
 * ({@code DoorSourceBudget.VARIANT_CHECK_PER_SOURCE_MIN_GAP_MILLIS}, hand-mirrored as
 * {@link #PACE_MILLIS} with margin — that class is not bundled) and drops the rest silently, so
 * the checks go out paced; a second round re-sends only the still-unanswered ones and only when
 * the first round proved the door answers at all.
 *
 * <p>Split for testability exactly as the probe: {@link Collector} is the pure datagram→verdict
 * accumulator (the joiner's {@code --selftest} goldens feed it canned bytes; it never touches
 * {@code org.json}); {@link #fetch13} is the only socket code (the password sealed).
 */
public final class DoorVariantCheck {
    /** Checks per fetch — the rest of a longer list stays UNKNOWN (32 × the pace ≈ 4 s of sends). */
    public static final int MAX_CUSTOMS = 32;
    /** ≥ the door's 100 ms per-source gap, with margin for arrival jitter. */
    public static final int PACE_MILLIS = 125;
    /** The listen window after the LAST send of a round. */
    public static final int RECEIVE_TIMEOUT_MILLIS = 1000;
    /** Rounds: the second re-sends only the unanswered checks, only after an answered first. */
    public static final int TRIES = 2;
    public static final int RECEIVE_BUFFER_BYTES = DatagramFrameCodec.MAX_DATAGRAM_BYTES;
    /** {@link #fetch13}: the F1 is sent once more this long after the first, if no COOKIE13 came. */
    public static final int F1_RESEND_MILLIS = 500;
    /** {@link #fetch13}: the whole COOKIE13 wait, from the first F1. */
    public static final int COOKIE13_WAIT_MILLIS = 1000;

    /**
     * The {@link #fetch13} meter (process-wide, monotonic; read through {@link #fetch13Count}): how each call ended,
     * plus the per-custom and per-datagram tallies. No production reader before THE FLIP — rows read the deltas.
     *
     */
    public enum Fetch13Count {
        /** Nothing sent: no / blank password, a print outside A-string(1..128), a bad address, the warm threw, no custom fits. */
        REFUSED_LOCAL,
        /** No usable COOKIE13 inside {@link #COOKIE13_WAIT_MILLIS} (silence, or only ignored datagrams). */
        NO_COOKIE,
        /** A datagram the F1 leg skipped: a foreign source, another channel, undecodable, a wrong nonce echo. */
        COOKIE13_IGNORED,
        /**
         * The COOKIE13 came but no custom fits a VCHK13 at ITS cookie length: nothing sealed, the pair budget untouched
         * (before this fix the exit spent a budget token + three X25519 and counted no ending).
         */
        NO_FIT,
        /** The process pair budget held the door's pair: nothing sealed. */
        BUDGET_HELD,
        /** {@code sealWrapsOrNull} refused the door's shares (null): nothing sealed. */
        SEAL_REFUSED,
        /** A socket or runtime fault ended the fetch, or every custom that FIT was refused at its seal / encode (nothing
         * sent); verdicts that landed before it still count. */
        FAULT,
        /** The paced rounds ran with at least one sealed VCHK13 (a fault mid-round ALSO counts {@link #FAULT}: two events). */
        SENT,
        /** A custom withheld after the COOKIE13 without a seal: too big for this cookie, or the writer refused it. */
        WITHHELD,
        /** A custom sealed, then refused by the encoder: its seq is spent and skipped (0 while size-before-seal holds). */
        BURNED_SEQ
    }

    private static final AtomicLongArray FETCH13_COUNTS = new AtomicLongArray(Fetch13Count.values().length);
    /** The match-lane key mix: a password door's wraps carry none (the typed secret rides the generator). */
    private static final byte[] NO_MIX = new byte[0];

    private DoorVariantCheck() {
    }

    /** The {@link Fetch13Count} tally so far in this process. */
    public static long fetch13Count(Fetch13Count which) {
        return FETCH13_COUNTS.get(which.ordinal());
    }

    private static void count(Fetch13Count which) {
        FETCH13_COUNTS.incrementAndGet(which.ordinal());
    }

    public enum Status {
        /** Policy pass — the final check is the launch-time submit. */
        OK,
        /** The door's policy refused it; {@link Verdict#token} names why ({@link VariantRejectHints}). */
        REJECT,
        /** Customs are off on this server ({@code custom_unavailable} / {@code custom_disabled}). */
        UNAVAILABLE,
        /** No verdict landed (silence, an old door, a lost datagram, an unencodable custom). */
        UNKNOWN
    }

    /** One id's verdict; {@code token} is the door's reject reason ("" unless REJECT/UNAVAILABLE). */
    public record Verdict(Status status, String token) {
        public static final Verdict OK = new Verdict(Status.OK, "");
        public static final Verdict UNKNOWN = new Verdict(Status.UNKNOWN, "");

        public Verdict {
            if (status == null) {
                throw new IllegalArgumentException("status is required");
            }
            token = token == null ? "" : token;
        }

        /** The lowercase status word the SPA keys its CSS class on. */
        public String word() {
            switch (status) {
                case OK:
                    return "ok";
                case REJECT:
                    return "reject";
                case UNAVAILABLE:
                    return "unavailable";
                default:
                    return "unknown";
            }
        }
    }

    /** The receipt → verdict mapping: accepted ⇒ OK; the two lane-off tokens ⇒ UNAVAILABLE; else REJECT. */
    public static Verdict verdictOf(ClientVariantReceipt receipt) {
        if (receipt.accepted()) {
            return Verdict.OK;
        }
        String token = receipt.reasonToken();
        if (DoorRejectReasons.CUSTOM_UNAVAILABLE.equals(token)
                || DoorRejectReasons.CUSTOM_DISABLED.equals(token)) {
            return new Verdict(Status.UNAVAILABLE, token);
        }
        return new Verdict(Status.REJECT, token);
    }

    /**
     * The pure accumulator. {@code expected} = submitSeq → the local id sent under it; a receipt
     * counts only from the dialed door, on channel VCHK, echoing the nonce, a pending seq and that
     * seq's id. Ids never answered are simply absent from {@link #verdicts()} — the caller's
     * fail-open default is UNKNOWN.
     */
    public static final class Collector {
        private final SocketAddress server;
        private final long nonce;
        private final Map<Integer, String> pending;
        private final Map<String, Verdict> verdicts = new LinkedHashMap<>();
        private int ignored;

        public Collector(SocketAddress server, long nonce, Map<Integer, String> expected) {
            this.server = server;
            this.nonce = nonce;
            this.pending = new LinkedHashMap<>(expected);
        }

        public boolean offer(SocketAddress from, byte[] data, int length) {
            if (done()) {
                return true;
            }
            if (from == null || !from.equals(server)) {
                ignored++;
                return false;
            }
            DatagramFrameCodec.Frame frame = DatagramFrameCodec.decode(data, length);
            if (frame == null || frame.channel() != DatagramFrameCodec.WireChannel.VCHK
                    || frame.sessionId() != nonce) {
                ignored++;
                return false;
            }
            ClientVariantReceipt receipt;
            try {
                receipt = ClientVariantReceiptReader.readFrame(
                        new ByteArrayInputStream(frame.payload()));
            } catch (IOException | RuntimeException malformed) {
                ignored++;
                return false;
            }
            if (receipt == null || receipt.submitSeq() != frame.seq()) {
                ignored++;
                return false;
            }
            String id = pending.get(receipt.submitSeq());
            if (id == null || !id.equals(receipt.variantId())) {
                ignored++; // an unsent / already-answered seq, or an id that is not the one sent
                return false;
            }
            pending.remove(receipt.submitSeq());
            verdicts.put(id, verdictOf(receipt));
            return done();
        }

        public boolean done() {
            return pending.isEmpty();
        }

        public int ignored() {
            return ignored;
        }

        /** The seqs still unanswered (the second round re-sends exactly these). */
        public Set<Integer> pendingSeqs() {
            return Collections.unmodifiableSet(pending.keySet());
        }

        /** Answered ids → verdicts, in answer order. */
        public Map<String, Verdict> verdicts() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(verdicts));
        }
    }

    /**
     * The socket half (since THE FLIP,
     * the ONLY one — the version-12 {@code fetch}, whose preamble carried the password readable, is deleted):
     * the {@link Collector}, pace and rounds, and the typed password NEVER rides the wire — it
     * only decides which door share a wrap opens under ({@code Handshake13.sealWrapsOrNull}), and each VSUB frame rides
     * sealed ({@code VariantCheckFrameCodec.encode13}). On ONE ephemeral socket (the door's cookie binds its address +
     * port):
     * <ol>
     * <li>Local refusals, ZERO datagrams: a null / blank password (the door has no blank bit), a print that is not 1..128
     * chars of 0x21..0x7E, a bad address, the hs-13 warm throwing ({@code PredictDatagramDial.warmHs13Once}), no
     * custom the writer encodes small enough for even a 1 B cookie (saves the F1 + the COOKIE13 wait; never a token).</li>
     * <li>{@link #awaitCookie13}: F1 on the HELLO channel; the FIRST valid COOKIE13 is the fetch's only door unit. The
     * fit is re-checked at ITS cookie length BEFORE the budget and the seal: nothing fits = {@link Fetch13Count#NO_FIT},
     * no token and no X25519 spent.</li>
     * <li>The PROCESS pair budget under the dial's and the probe's host key ({@code PredictDatagramDial.budgetHostKey}):
     * a held pair = no seal, so a fake door minting a fresh pair per F1 earns no fresh guesses per fetch.</li>
     * <li>{@link #seal13}: ONE wrap seal per fetch, then each custom sized BEFORE its body seal.</li>
     * <li>The paced rounds (one VCHK13 per custom every {@link #PACE_MILLIS}, then {@link #RECEIVE_TIMEOUT_MILLIS} of
     * listen; a second round only for the unanswered, only after an answered first), read by the {@link Collector}
     * (the VACK stays plaintext); round 2 resends the cached bytes.</li>
     * </ol>
     * Every failure reads {@link Status#UNKNOWN} ("checked at launch") and NO version-12 datagram leaves on any path —
     * never a fallback. Never throws; every exit counts into {@link #fetch13Count}. Blocking (≤ {@value
     * #COOKIE13_WAIT_MILLIS} ms + {@link #TRIES} × (n × {@link #PACE_MILLIS} + {@link #RECEIVE_TIMEOUT_MILLIS}) ms);
     * callers run it OFF the EDT with no lock held (three X25519s run inside). Production caller: the join window's
     * dry-run ({@code JoinCore}).
     *
     *
     */
    public static Map<String, Verdict> fetch13(String host, int port, String password,
            String fingerprint, List<ClientVariantDefinition> customs,
            cc.starsector.server.runtime.IdTables tables) {
        Map<String, Verdict> out = new LinkedHashMap<>();
        if (customs == null) {
            return out;
        }
        for (ClientVariantDefinition custom : customs) {
            out.put(custom.variantId(), Verdict.UNKNOWN);
        }
        if (out.isEmpty()) {
            return out;
        }
        InetSocketAddress server;
        try {
            server = new InetSocketAddress(host, port);
        } catch (IllegalArgumentException badPort) {
            count(Fetch13Count.REFUSED_LOCAL);
            return out;
        }
        if (server.isUnresolved() || password == null || password.isBlank() || !isPrint13(fingerprint)
                || !anyFits13(customs, 1, fingerprint.length(), tables)) {
            count(Fetch13Count.REFUSED_LOCAL);
            return out;
        }
        try {
            PredictDatagramDial.warmHs13Once();
        } catch (RuntimeException | LinkageError brokenCrypto) { // a broken provider: never a version-12 check
            count(Fetch13Count.REFUSED_LOCAL);
            return out;
        }
        SecureRandom rng = new SecureRandom();
        long nonce = rng.nextLong();
        Collector collector = null;
        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] buf = new byte[RECEIVE_BUFFER_BYTES];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            Handshake13.Cookie13 unit = awaitCookie13(socket, server, packet, buf, nonce);
            if (unit == null) {
                count(Fetch13Count.NO_COOKIE);
                return out;
            }
            if (!anyFits13(customs, unit.cookie().length, fingerprint.length(), tables)) {
                count(Fetch13Count.NO_FIT); // the pre-F1 probe sized for a 1 B cookie; this door's leaves less room
                return out;
            }
            if (!Handshake13PairBudget.process().host(PredictDatagramDial.budgetHostKey(server))
                    .admit(unit.ybM(), unit.ybA(), System.nanoTime() / 1_000_000L)) {
                count(Fetch13Count.BUDGET_HELD);
                return out;
            }
            Map<Integer, byte[]> requests = new LinkedHashMap<>();
            Map<Integer, String> expected = new LinkedHashMap<>();
            if (!seal13(nonce, unit, fingerprint, password, customs, tables, rng, requests, expected)) {
                count(Fetch13Count.SEAL_REFUSED);
                return out;
            }
            if (requests.isEmpty()) {
                count(Fetch13Count.FAULT); // a custom that FIT was refused at its seal / encode (each counted too)
                return out;
            }
            count(Fetch13Count.SENT);
            collector = new Collector(server, nonce, expected);
            for (int round = 0; round < TRIES && !collector.done(); round++) {
                if (round > 0 && collector.verdicts().isEmpty()) {
                    break; // a silent first round = a deaf door; do not double the wait
                }
                for (Integer s : new ArrayList<>(collector.pendingSeqs())) {
                    if (!collector.pendingSeqs().contains(s)) {
                        continue; // answered while we paced
                    }
                    byte[] request = requests.get(s);
                    socket.send(new DatagramPacket(request, request.length, server));
                    listen(socket, packet, buf, collector, PACE_MILLIS);
                }
                listen(socket, packet, buf, collector, RECEIVE_TIMEOUT_MILLIS);
            }
        } catch (IOException | RuntimeException network) {
            count(Fetch13Count.FAULT); // whatever landed before the fault still counts; the rest stays UNKNOWN
        }
        if (collector != null) {
            out.putAll(collector.verdicts());
        }
        return out;
    }

    /**
     * {@link #fetch13}'s F1 leg: sends {@code Handshake13.encodeOpen(nonce, 0)} framed on the HELLO channel, once more at
     * {@value #F1_RESEND_MILLIS} ms, and returns the FIRST frame that is from the dialed source, on the WELCOME channel,
     * a clean COOKIE13 and echoes {@code nonce} — or {@code null} once {@value #COOKIE13_WAIT_MILLIS} ms pass without
     * one. Only the first is used (one seal per fetch); a later one is left to the paced rounds' {@link Collector},
     * which ignores it. Every skipped datagram counts {@link Fetch13Count#COOKIE13_IGNORED}.
     *
     */
    private static Handshake13.Cookie13 awaitCookie13(DatagramSocket socket, InetSocketAddress server,
            DatagramPacket packet, byte[] buf, long nonce) throws IOException {
        byte[] f1 = DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.HELLO, 0, 0L, 0L, 0L, 0, 0L,
                Handshake13.encodeOpen(nonce, 0));
        long startNanos = System.nanoTime();
        socket.send(new DatagramPacket(f1, f1.length, server));
        boolean resent = false;
        while (true) {
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            if (elapsedMillis >= COOKIE13_WAIT_MILLIS) {
                return null;
            }
            if (!resent && elapsedMillis >= F1_RESEND_MILLIS) {
                socket.send(new DatagramPacket(f1, f1.length, server));
                resent = true;
            }
            long edgeMillis = resent ? COOKIE13_WAIT_MILLIS : F1_RESEND_MILLIS;
            socket.setSoTimeout((int) Math.max(1L, edgeMillis - elapsedMillis));
            packet.setLength(buf.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException idle) {
                continue; // the loop re-reads the clock: resend, or give up
            }
            if (!server.equals(packet.getSocketAddress())) {
                count(Fetch13Count.COOKIE13_IGNORED); // another source's COOKIE13 is never the door's
                continue;
            }
            DatagramFrameCodec.Frame frame = DatagramFrameCodec.decode(packet.getData(), packet.getLength());
            Handshake13.Cookie13 cookie = frame == null || frame.channel() != DatagramFrameCodec.WireChannel.WELCOME
                    ? null : Handshake13.decodeCookieOrNull(frame.payload());
            if (cookie != null && cookie.nonceEcho() == nonce) {
                return cookie;
            }
            count(Fetch13Count.COOKIE13_IGNORED);
        }
    }

    /**
     * {@link #fetch13}'s seal: ONE {@code sealWrapsOrNull(secretBytes(password), no mix, ADa, ADb, YbM, YbA, rng)} with
     * {@code ADa} from {@code VariantCheckFrameCodec.ada13} and {@code ADb} from {@code Handshake13.adb} (never cut
     * here); {@code false} = the wraps refused (nothing sealed). Then per custom, in order, up to {@link #MAX_CUSTOMS}
     * sealed: the VSUB frame at the current {@code seq}, the size check against {@code maxSubmitFrame13} BEFORE
     * {@code sealBody} (a withheld custom never burns a {@code (ck, seq)}), the body seal under
     * {@code bodyAad13}, then {@code encode13}. A custom the writer, the size check or the codec refuses stays UNKNOWN;
     * {@code seq} advances for every SEALED body, so a sealed {@code (ck, seq)} is never reused even when its encode
     * then fails ({@code Handshake13.sealBody}'s caller law). Each request is framed once into {@code requests}. The
     * secret bytes are zeroed after the wrap seal; {@code ck} and both roots after the last body ({@code finally}).
     * Throws only what the wrap seal throws ({@code fetch13}'s catch reads it as a fault).
     *
     */
    private static boolean seal13(long nonce, Handshake13.Cookie13 unit, String print, String password,
            List<ClientVariantDefinition> customs, cc.starsector.server.runtime.IdTables tables,
            SecureRandom rng, Map<Integer, byte[]> requests, Map<Integer, String> expected) {
        byte[] cookie = unit.cookie();
        byte[] secret = Handshake13.secretBytes(password);
        Handshake13.ClientWraps wraps;
        try {
            wraps = Handshake13.sealWrapsOrNull(secret, NO_MIX, VariantCheckFrameCodec.ada13(nonce, cookie, print),
                    Handshake13.adb(cookie, unit.ybM(), unit.ybA(), unit.laneSalt()), unit.ybM(), unit.ybA(), rng);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        if (wraps == null) {
            return false;
        }
        try {
            int cap = VariantCheckFrameCodec.maxSubmitFrame13(cookie.length, print.length());
            int seq = 0;
            for (ClientVariantDefinition custom : customs) {
                if (seq >= MAX_CUSTOMS) {
                    break;
                }
                int s = seq;
                byte[] payload = null;
                try {
                    byte[] vsub = VariantSubmitWriter.encodeFrame(s, custom, tables);
                    if (vsub.length <= cap) {
                        byte[] sealedBody = Handshake13.sealBody(wraps.ck(), s, VariantCheckFrameCodec.bodyAad13(
                                nonce, cookie, print, s, wraps.ya(), wraps.wrapM(), wraps.wrapA()), vsub);
                        seq++; // this (ck, seq) is spent from here on, whatever encode13 does
                        payload = VariantCheckFrameCodec.encode13(nonce, cookie, print, s, wraps.ya(), wraps.wrapM(),
                                wraps.wrapA(), sealedBody);
                    }
                } catch (RuntimeException refused) {
                    // the writer / the codec / the crypto refused this custom: counted just below
                }
                if (payload == null) {
                    count(seq > s ? Fetch13Count.BURNED_SEQ : Fetch13Count.WITHHELD);
                    continue;
                }
                requests.put(s, DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.VCHK, 0,
                        0L, s, 0L, 0, 0L, payload));
                expected.put(s, custom.variantId());
            }
        } finally {
            Arrays.fill(wraps.ck(), (byte) 0);
            Arrays.fill(wraps.rootM(), (byte) 0);
            Arrays.fill(wraps.rootA(), (byte) 0);
        }
        return true;
    }

    /** A print the VCHK13 head carries: 1..128 chars, each 0x21..0x7E (the codec's A-string, non-empty). */
    private static boolean isPrint13(String print) {
        if (print == null || print.isEmpty() || print.length() > VariantCheckFrameCodec.PRINT13_MAX_CHARS) {
            return false;
        }
        for (int i = 0; i < print.length(); i++) {
            char c = print.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when some custom encodes AND fits a VCHK13 at a {@code cookieLen} B cookie. Called twice: before the F1 at
     * 1 B (the most room any door gives), then at the COOKIE13's real length before the budget + the seal. The probe's
     * refusals are not counted here: the seal loop re-meets and counts each, or the caller counts the fetch's ending.
     */
    private static boolean anyFits13(List<ClientVariantDefinition> customs, int cookieLen, int printLen,
            cc.starsector.server.runtime.IdTables tables) {
        int widest = VariantCheckFrameCodec.maxSubmitFrame13(cookieLen, printLen);
        for (ClientVariantDefinition custom : customs) {
            try {
                if (VariantSubmitWriter.encodeFrame(0, custom, tables).length <= widest) {
                    return true;
                }
            } catch (RuntimeException unencodable) {
                // this one cannot ride at all (fetch13 never throws); the next may
            }
        }
        return false;
    }

    private static void listen(DatagramSocket socket, DatagramPacket packet, byte[] buf,
            Collector collector, int windowMillis) throws IOException {
        long deadline = System.nanoTime() + windowMillis * 1_000_000L;
        while (!collector.done()) {
            long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
            if (leftMillis <= 0) {
                break;
            }
            socket.setSoTimeout((int) Math.max(1L, leftMillis));
            packet.setLength(buf.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException idle) {
                break;
            }
            collector.offer(packet.getSocketAddress(), packet.getData(), packet.getLength());
        }
    }
}
