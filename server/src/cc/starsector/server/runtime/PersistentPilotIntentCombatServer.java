package cc.starsector.server.runtime;

import cc.starsector.server.adapter.CombatStepBoundary;
import cc.starsector.server.adapter.CombatWorldState;
import cc.starsector.server.adapter.ShipFluxView;
import cc.starsector.server.adapter.ShipManeuverView;
import cc.starsector.server.adapter.ShipSystemView;
import cc.starsector.server.adapter.WeaponMountView;
import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineBackedLiveWorldSnapshotSource;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.PilotApplyState;
import cc.starsector.server.bridge.RealEngineDemoRoster;
import cc.starsector.server.bridge.TransferPodSpawner;
import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.MatchDocumentRefused;
import cc.starsector.server.match.MatchGracePolicy;
import cc.starsector.server.match.MatchPasswords;
import cc.starsector.server.match.MatchRespawnPolicy;
import cc.starsector.server.match.MatchSeatAuthority;
import cc.starsector.server.match.PasswordSeatLedger;
import cc.starsector.server.match.WaveSchedule;
import cc.starsector.server.platform.WaveShipNamer;
import cc.starsector.server.ops.OpsStatusFile;
import cc.starsector.server.ops.PhaseWindowStats;
import cc.starsector.server.ops.ServerOpsCore;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.EntityDespawnEvent;
import cc.starsector.server.protocol.EntityDespawnReason;
import cc.starsector.server.protocol.GameplayEdgeEvent;
import cc.starsector.server.protocol.GameplayEdgeEventKind;
import cc.starsector.server.protocol.SeatStateCause;
import cc.starsector.server.protocol.SeatStateEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;

/**
 * The PILOT-lane persistent serve loop core — the product
 * {@code server/src} serve loop that gives RENDERED predict clients (Stack A, the faithful
 * {@link PilotIntentFrame} up-codec) authoritative rounds with the reset the dedicated lane
 * (Stack B) proved. The ONE live entry point is {@link #serveNPeer}: the
 * N-peer UDP door ({@link PilotIntentDatagramServer}, duplex SEND/RECV loops, credentialed
 * admission, fly-in on claim). The historical single/two-session TCP {@code serve()}
 * lane (fused {@code exchangeSnapshot} ping-pong over {@link PilotIntentSocketServer}, MRST/MRER
 * reentry, seat-2 second endpoint) was FULLY RETIRED —
 * git history keeps the code; {@link PilotServeConfig}/{@link PilotServeReceipt} and the shared
 * helpers below are the surviving lane-agnostic surface.
 *
 * <p>The serve loop takes and stages PRE-STEP: a frame deposited while tick <i>i</i>'s
 * frame sleeps applies at tick <i>i</i>'s advance ({@link PilotIntentStep}, the proven
 * {@code CommandDrivenStep} decorator 1:1) — the input's age is one tick (R+1), never two; still
 * harmless for predict-and-reconcile (the client predicts locally; the server is sole authority);
 * {@code matchOver} is computed inside capture.
 *
 * <p><b>Fire damage/kill PROOF stays out of scope here</b> (a recorded gap, not a silent drop: the
 * hit/kill PROOF stays deferred, β-fire-lane). The DOWN {@code GameplayEdgeEvent} lane IS served
 * when {@code cc.predict.eventReplay} is on (default OFF = the 1-arg
 * {@link PilotIntentSocketServer#exchangeSnapshot}, byte-identical for every pre-lane consumer): each
 * tick's detected edge events ride the 2-arg exchange ahead of the AWSS — the same multiplex order the
 * scout gate serves. Without this lane the client's discharge meter has no authoritative discharges
 * to pair against, so the acceptance bar is unmeasurable on this stack (wired 2026-07-01).
 * The FIRE mechanic itself is served per the fire-lane decision as amended at its step-6 flip
 * (2026-07-13): the default HELD lane —
 * level-sampled {@code fireHeld} ({@link EngineCombatBinding#applyFireBit}) + the fireEdgeSeq
 * depth-1 tap latch and the press-span clamp — shipped with the client-side ownFireLock /
 * dischargeReconcile apply treatments. The DECLARED-DISCHARGE lane (client-declared discharges demuxed
 * off the up stream drive the aimed fire command (USE_SELECTED_GROUP) until the server's own post-step
 * {@code WEAPON_DISCHARGE} consumes them; ε-timeout + counters) stays built as the
 * {@code cc.server.fireLane=declared} selection — the dormant per-group escape hatch (the fire-lane design's
 * Option C) — whose fail-open fallback for a never-declaring client flips back to held.
 *
 * <p>Like the rest of the project this class carries no game identity: the caller supplies the already-promoted
 * {@link EngineCombatBinding} (the real-engine factory binding, or a game-free fake for the contract).
 */
public final class PersistentPilotIntentCombatServer {

    /** Grace the decoupled-send writer thread gets to finish an in-flight exchange before the socket is aborted. */
    private static final long DECOUPLED_SHUTDOWN_GRACE_MILLIS = 2_000L;

    /**
     * fail-open fallback trigger: with the declared fire lane selected, if ZERO declarations have
     * ever arrived this session and the wire {@code fireHeld} bit has been continuously true this many
     * consecutive frames (~2 s at 60 Hz), the session flips ONCE to the legacy held lane — an old client
     * that never declares still gets its trigger served (the netcode design's fail-open doctrine).
     *
     * <p>CAVEAT (open question, unproven on real links): the streak RESETS on
     * any tick whose latest-wins take is empty or non-held, so latch time is delivery-jitter-sensitive —
     * production geometry is ~1 intent per tick, and a lossy/jittery WAN link can repeatedly reset the
     * streak and delay the fallback well past ~2 s (it needs one clean 120-tick delivery stretch per
     * serve, once). Likely benign; but if old-kit held-fire ever misbehaves on real WAN, look HERE first.
     * The gate row covering this latch deliberately does NOT probe marginal arrival (npeer contract
     * axis D, 16 ms window — see its comment before re-tightening anything).
     */
    static final double FIRE_LANE_FALLBACK_HELD_SECONDS = 2.0; // ticks derive per round: round(2.0 * effective rate) == the historical 120 at 60 Hz

    /**
     * The cadence of the {@code round_teardown
     * reason=heartbeat} receipt — every 60 s of served sim frames at the 60 Hz fixed step, so
     * the freshest round counters are never more than a minute stale when the serve dies
     * unflushable (a TerminateProcess hard kill runs no hooks) or a session never dies at all
     * (a zombie client JVM's datagrams kept seat 2's silence deadline fed past the
     * player's walk-away — liveness measures datagram arrival, not human presence, so the
     * last-death transition structurally cannot cover that class). Frame-keyed on purpose:
     * game-free contract legs cross it without wall-clock waits, and paced serves make it
     * wall-clock 60 s. The receipt law: receipts key on cadence or round-END only, NEVER
     * {@code matchOver} arming (arming happens at first contact — its counters are non-final).
     */
    static final int ROUND_RECEIPT_HEARTBEAT_FRAMES = 3600;

    /** The stall witness: a served frame whose WORK phase (or the
     * post-measure ops-file tail) exceeds this emits ONE loud {@code work_frame_stall} line
     * naming its dominant phase. Constant on purpose — a witness, not behavior; no knob. */
    static final long STALL_FRAME_NANOS = 100_000_000L;

    /**
     * socket-liveness cluster CORE: the read deadline armed on each connected
     * pilot transport when {@code cc.net.socketLiveness} is on — the codebase's standing accepted-socket
     * timeout (every OTHER accepted-socket transport already sets 10 s, e.g.
     * {@code ConcurrentMultiplayerSessionRunner.SOCKET_TIMEOUT_MILLIS}; the pilot serve read was the lone
     * opt-out). Lifecycle-aware: {@code awaitReentry()} relaxes it across the results-screen idle, and
     * the FIRST exchange after connect is bounded by {@code clientWaitMillis} (the join
     * grace) instead — a connected human sits in the lobby/mission-brief far longer than 10 s (VPN
     * live test reaped seat 1 at round 0 frame 0), so this deadline prices only mid-round silence
     * from a peer whose first frame already proved it live.
     */
    public static final int SOCKET_LIVENESS_READ_DEADLINE_MILLIS = 10_000;

    /**
     * per-exchange PDCL consume budget when {@code cc.net.socketLiveness} is on — the demux
     * FIFO bound doubles as the read-loop liveness bound (a strict-ping-pong client queues a handful of
     * declarations per tick; only a flood peer can reach FIFO scale in ONE exchange).
     */
    public static final int SOCKET_LIVENESS_DECLARATION_BUDGET =
            PilotIntentSocketServer.UpLaneDemux.MAX_PENDING;

    private PersistentPilotIntentCombatServer() {
    }

    /** Invoked once, right after the pilot transport binds, with its resolved address (host/port file write). */
    @FunctionalInterface
    public interface ListenerReady {
        void onReady(String host, int port) throws IOException;
    }

    /**
     * How each peer's DOWN/UP lanes are driven — three mutually exclusive
     * modes (an enum, not a boolean pair: a pair has an invalid combination).
     * <ul>
     *   <li>{@code WELDED} — the proven strict blocking ping-pong on the sim thread (the default;
     *       every pre-gate consumer, byte-identical).</li>
     *   <li>{@code DECOUPLED} — v1: one {@link PeerSendGate} writer thread per peer runs
     *       the fused exchange; the sim never blocks, but delivery is still request-response
     *       (ceiling = tickPeriod/RTT, measured 44 % on the VPN seat).</li>
     *   <li>{@code DUPLEX} — SEND + RECV loops per peer over the split transport
     *       halves; RTT affects staleness only, never delivery rate.</li>
     * </ul>
     */
    public enum SendMode {
        WELDED, DECOUPLED, DUPLEX;

        /**
         * The ONE sendMode resolver (conflict-THROW lives here, nowhere
         * else). Primary knob: {@code cc.net.sendMode} / {@code CC_NET_SEND_MODE} /
         * {@code net.sendMode} = {@code welded|decoupled|duplex}. The legacy boolean
         * sysprop/env/config alias is RETIRED (shape in git).
         * {@code cliDecoupledSend} is the Main's {@code --decoupled-send} flag (null = not
         * passed) — the surviving launcher CLI surface. Every SET source must agree; any
         * contradiction THROWS (house rule: an invalid/ambiguous knob never serves silently).
         * Nothing set ⇒ {@code WELDED}.
         */
        public static SendMode resolve(Boolean cliDecoupledSend) {
            String raw = ServerConfig.resolve(
                    "cc.net.sendMode", "CC_NET_SEND_MODE", "net.sendMode", "");
            SendMode primary = switch (raw) {
                case "" -> null;
                case "welded" -> WELDED;
                case "decoupled" -> DECOUPLED;
                case "duplex" -> DUPLEX;
                default -> throw new IllegalArgumentException(
                        "cc.net.sendMode must be 'welded', 'decoupled' or 'duplex'; got '" + raw + "'");
            };
            SendMode resolved = primary;
            SendMode cli = cliDecoupledSend == null ? null : (cliDecoupledSend ? DECOUPLED : WELDED);
            if (resolved != null && cli != null && resolved != cli) {
                throw new IllegalArgumentException(
                        "--decoupled-send " + cliDecoupledSend + " contradicts the resolved sendMode "
                                + resolved.name().toLowerCase(java.util.Locale.ROOT)
                                + " (cc.net.sendMode); fix the launch config");
            }
            if (resolved != null) {
                return resolved;
            }
            return cli != null ? cli : WELDED;
        }
    }

    /**
     * The pilot transport knob. {@code UDP}
     * ({@link PilotIntentDatagramServer}, the N-peer door) is the ONE transport — the TCP lane
     * (single-session {@code serve()} over the socket server) is FULLY RETIRED. The enum + knob
     * survive as the fail-closed guard seam: an explicit {@code tcp} THROWS loudly rather than
     * silently serving a retired lane.
     */
    public enum Transport {
        UDP;

        /**
         * The ONE transport resolver: {@code cc.net.transport} / {@code CC_NET_TRANSPORT} /
         * {@code net.transport} = {@code udp}; unset = {@code udp}. {@code tcp} THROWS a
         * retirement message (never a silent default-away); unknown or non-lowercase values
         * THROW as before (launchers normalize upstream, the {@code SendMode.resolve} posture).
         */
        public static Transport resolve() {
            String raw = ServerConfig.resolve(
                    "cc.net.transport", "CC_NET_TRANSPORT", "net.transport", "");
            return switch (raw) {
                case "", "udp" -> UDP;
                case "tcp" -> throw new IllegalArgumentException(
                        "TCP lane retired: cc.net.transport=tcp no longer"
                                + " serves — the npeer UDP door is the ONE serve lane (unset the"
                                + " knob or set 'udp')");
                default -> throw new IllegalArgumentException(
                        "cc.net.transport must be 'udp'; got '" + raw + "'");
            };
        }
    }

    /**
     * Mint the UDP admission ledger from the {@code cc.match.descriptor} / {@code CC_MATCH_DESCRIPTOR}
     * file — the EXISTING credential-provisioning ferry ({@link MatchDescriptorCodec} text; the same
     * file {@code RealEngineDemoRoster} reads {@code deployCr} from). Fail-CLOSED, unlike that
     * deployCr read's fail-soft: a udp serve without a readable, decodable descriptor THROWS — the
     * null-authority door on {@link PilotIntentDatagramServer#openMultiPeer} stays test-only, so an
     * internet-exposed datagram port never serves perimeter-trust silently. Seat-2
     * mode shares this ONE ledger across both endpoints (one match, one occupancy truth).
     *
     * <p>Resolution is the standard {@link ServerConfig#resolvePath} cascade — sysprop &gt; env &gt;
     * the {@code match.descriptor} config row (the standing kit-baked descriptor: the row names
     * the SAME file the joiner kit bakes as {@code match.txt}, so a config-driven udp serve accepts
     * the kit's seat tokens; rotation = re-mint + re-pack). Fail-closed on empty stays.
     */
    private static MatchSeatAuthority mintUdpSeatAuthority() {
        return new MatchSeatAuthority(decodeUdpSeatDescriptor());
    }

    /**
     * The descriptor decode HALF of {@link #mintUdpSeatAuthority}, extracted
     * so {@code serveNPeer} can decode ONCE and keep the descriptor OBJECT — the authority
     * drops it at mint, but the ops file's seat→ship names live on its roster rows
     * ({@link OpsStatusFile#seatShipNames}). Behavior (fail-closed reads, decode THROWs, the
     * {@code udp_seat_authority} line) is byte-identical on both callers.
     *
     * <p>The read is the ops tree's ONE bounded read ({@link ServerOpsCore#readCappedLines}: at most
     * 262144 B, no symlink followed), so an over-cap file THROWS {@code UNREADABLE} - never a
     * truncated or unbounded read of a host file. A decoded file fielding more than
     * {@link MatchDescriptors#MAX_SEATS} seats THROWS before the {@code udp_seat_authority} line:
     * seat ids 1..255 are all the wire carries, and every mint already refuses more, so such a file
     * was hand-made or damaged (fail-closed, the mint's own law).
     *
     */
    static MatchDescriptor decodeUdpSeatDescriptor() {
        String raw = ServerConfig.resolvePath(
                "cc.match.descriptor", "CC_MATCH_DESCRIPTOR", "match.descriptor", "");
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "cc.net.transport=udp requires cc.match.descriptor / CC_MATCH_DESCRIPTOR /"
                            + " config row match.descriptor to name the match descriptor file (seat"
                            + " credentials); the no-descriptor datagram door is test-only");
        }
        String path = raw.trim();
        List<String> lines;
        try {
            lines = ServerOpsCore.readCappedLines(Path.of(path));
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "cc.match.descriptor=" + path + " UNREADABLE for the udp seat authority: " + e, e);
        }
        MatchDescriptor descriptor;
        try {
            descriptor = MatchDescriptorCodec.decode(lines);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "cc.match.descriptor=" + path + " DECODE FAILED for the udp seat authority: "
                            + e, e);
        }
        if (descriptor.seats().size() > MatchDescriptors.MAX_SEATS) {
            throw new IllegalArgumentException("cc.match.descriptor=" + path + " fields "
                    + descriptor.seats().size() + " seats, over the MatchDescriptors.MAX_SEATS ceiling of "
                    + MatchDescriptors.MAX_SEATS + " for the udp seat authority");
        }
        System.out.println("pilot-intent-server udp_seat_authority descriptor=" + path
                + " seats=" + descriptor.seats().size());
        return descriptor;
    }

    /**
     * The descriptor↔record binding: re-resolve the descriptor path (the
     * {@link #decodeUdpSeatDescriptor} tuple), digest the raw file bytes, and
     * {@link MatchRecordMeta#bindMatch} the identity for the .ccwr headers this serve opens.
     * FAIL-SOFT — a pure-record feature must never add a boot-failure mode: a file raced away
     * since the decode records {@code descriptor.md5=unreadable} and the serve proceeds. Called
     * at the {@code serveNPeer} call-site, NOT inside {@code decodeUdpSeatDescriptor} (its second
     * caller, {@link #mintUdpSeatAuthority}, would double-mint the match index).
     */
    static void bindNpeerMatchIdentity(MatchDescriptor descriptor) {
        MatchRecordMeta.bindMatch(descriptor.matchId(), launchedDescriptorMd5());
    }

    /** That digest alone, no bind: ONE recipe for the serve's bind and the re-sim boot gate. */
    static String launchedDescriptorMd5() {
        String path = ServerConfig.resolvePath("cc.match.descriptor", "CC_MATCH_DESCRIPTOR", "match.descriptor", "");
        try {
            return ContentFingerprints.md5Hex(Files.readAllBytes(Path.of(path.trim())));
        } catch (IOException unreadable) {
            return "unreadable";
        }
    }

    /**
     * The door-fingerprint mint: resolve the arming
     * knob {@code cc.net.doorFingerprint} / {@code CC_NET_DOOR_FINGERPRINT} /
     * {@code net.doorFingerprint} (STRICT boolean — house rule, an invalid knob never serves
     * silently), and when armed re-read the expected composite PER SERVE from the
     * descriptor-adjacent sidecar {@code <descriptor>.fingerprint} ({@code fingerprint=} row) —
     * the {@link #mintUdpSeatAuthority} pattern, D4-persistence-law compliant. Returns null when
     * off (the door's v1 behavior, byte-identical). Armed ⇒ fail-closed: no readable sidecar
     * value THROWS; blank-sending stale kits then bounce at the door with a readable WELCOME
     * reason (the exposure fix). Default OFF until the D4.8 kit ships — arming
     * earlier bounces the CURRENT kit (it sends blank).
     */
    static String mintDoorFingerprint() {
        String raw = ServerConfig.resolve(
                "cc.net.doorFingerprint", "CC_NET_DOOR_FINGERPRINT", "net.doorFingerprint", "");
        boolean armed = switch (raw) {
            case "", "false" -> false;
            case "true" -> true;
            default -> throw new IllegalArgumentException(
                    "cc.net.doorFingerprint must be 'true' or 'false'; got '" + raw + "'");
        };
        if (!armed) {
            return null;
        }
        String descriptor = ServerConfig.resolvePath(
                "cc.match.descriptor", "CC_MATCH_DESCRIPTOR", "match.descriptor", "");
        if (descriptor == null || descriptor.trim().isEmpty()) {
            throw new IllegalArgumentException("cc.net.doorFingerprint=true requires"
                    + " cc.match.descriptor — the expected composite lives in the"
                    + " descriptor-adjacent sidecar <descriptor>.fingerprint");
        }
        Path sidecar = Path.of(descriptor.trim() + ".fingerprint");
        List<String> lines;
        try {
            lines = Files.readAllLines(sidecar);
        } catch (IOException e) {
            throw new IllegalArgumentException("cc.net.doorFingerprint=true but the sidecar "
                    + sidecar + " is UNREADABLE (the armed door serves fail-closed): " + e, e);
        }
        return ContentFingerprints.parseSidecarFingerprint(lines, sidecar.toString());
    }

    /**
     * match-password mint: re-read the descriptor-adjacent sidecar
     * {@code <descriptor>.password} PER SERVE (the {@link #mintDoorFingerprint} pattern,
     * D4-persistence-law compliant). Unlike the fingerprint (knob-armed), password mode is armed
     * by the sidecar's PRESENCE: file absent ⇒ null ⇒ token mode, bit-identical to today;
     * present ⇒ the parsed password ({@link MatchPasswords#parseSidecarPassword} — malformed/
     * blank/over-cap/charset THROWS) — and present-but-unreadable ALSO throws, because an armed
     * door must never silently serve token-mode while the host believes a password guards it.
     * No descriptor configured ⇒ null (the udp lanes fail closed on that long before this).
     */
    static String mintMatchPassword() {
        String descriptor = ServerConfig.resolvePath(
                "cc.match.descriptor", "CC_MATCH_DESCRIPTOR", "match.descriptor", "");
        if (descriptor == null || descriptor.trim().isEmpty()) {
            return null;
        }
        Path sidecar = Path.of(descriptor.trim() + ".password");
        if (!Files.exists(sidecar)) {
            return null; // ABSENT = token mode; token mode = delete the sidecar
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(sidecar);
        } catch (IOException e) {
            throw new IllegalArgumentException("match-password sidecar " + sidecar + " exists but"
                    + " is UNREADABLE (a present sidecar arms password mode fail-closed): " + e, e);
        }
        return MatchPasswords.parseSidecarPassword(lines, sidecar.toString());
    }

    /**
     * admin-credential mint: the {@link #mintMatchPassword} twin over the
     * {@code <descriptor>.adminpassword} sibling ({@link MatchPasswords#ADMIN_SIDECAR_SUFFIX};
     * same {@code password=} row grammar — the FILE NAME is the distinction; re-read per serve;
     * never in a kit; parsed by {@link MatchPasswords#parseAdminSidecarPassword} — floor 12 +
     * the common-password blocklist). Absent ⇒ null ⇒ no admin
     * seat this serve. Boot-refusal laws (fail-closed,
     * a half-configured door never serves): present in TOKEN mode (no match password) ⇒ THROW —
     * the admin secret rides the match-password slot, so without a match door it could never be
     * presented; equal to the match password ⇒ THROW — everyone would be admin.
     */
    static String mintAdminPassword(String matchPassword) {
        String descriptor = ServerConfig.resolvePath(
                "cc.match.descriptor", "CC_MATCH_DESCRIPTOR", "match.descriptor", "");
        if (descriptor == null || descriptor.trim().isEmpty()) {
            return null;
        }
        Path sidecar = Path.of(descriptor.trim() + MatchPasswords.ADMIN_SIDECAR_SUFFIX);
        if (!Files.exists(sidecar)) {
            return null; // ABSENT = no admin credential (the common case)
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(sidecar);
        } catch (IOException e) {
            throw new IllegalArgumentException("admin-password sidecar " + sidecar + " exists but"
                    + " is UNREADABLE (a present sidecar arms the admin seat fail-closed): " + e, e);
        }
        String adminPassword = MatchPasswords.parseAdminSidecarPassword(lines, sidecar.toString());
        if (matchPassword == null) {
            throw new IllegalArgumentException("admin-password sidecar " + sidecar + " is present"
                    + " but the match-password sidecar is not — the admin credential rides the"
                    + " match-password slot and needs a match password (set one, or delete the"
                    + " admin sidecar)");
        }
        if (MatchPasswords.constantTimeEquals(adminPassword, matchPassword)) {
            throw new IllegalArgumentException("admin-password sidecar " + sidecar + " equals the"
                    + " match password — every joiner would be admin; choose a different admin"
                    + " password");
        }
        System.out.println("pilot-intent-server admin_credential=armed sidecar=" + sidecar);
        return adminPassword;
    }

    /**
     * The match-document boot preflight, the sibling of the door mints above: a password-mode door hands the
     * match document to every join window that probes it, so the serve runs the client's own receive path over
     * the exact bytes it would serve ({@link MatchDocPreflight#prepare}, the one pipeline the served document
     * comes from) and THROWS when no join window could adopt them. WHY at the boot: a document that fails here
     * would otherwise meet a stranger's probe as a dead end (no window can fetch a usable match, and nothing the
     * player does fixes it) - the fail-closed boot law the door mints already follow. The message is
     * {@link MatchDocPreflight#unservable} alone: a reason token plus OUR field, no cause chained and never a
     * descriptor line (the no-echo law: a descriptor may come from an untrusted host). Token mode (a null
     * {@code matchPassword}) serves no document and returns at once; a null descriptor in password mode is a
     * caller bug and throws {@link IllegalArgumentException} from {@code prepare}.
     *
     *
     */
    public static void preflightMatchDoc(MatchDescriptor descriptor, String matchPassword) {
        if (matchPassword == null) {
            return;
        }
        try {
            MatchDocPreflight.prepare(descriptor);
        } catch (MatchDocumentRefused refused) {
            throw new IllegalArgumentException(MatchDocPreflight.unservable(refused));
        }
    }

    /**
     * Mint one round's {@link ShipNetworkIdRegistry}: when a real roster is deployed,
     * pre-claim the reserved band {@code [1, maxSeatWireId]} with each seat's deploy-recorded stable
     * engine id, so a seat's replicated {@code entityId} equals its descriptor {@code seat.ship} by
     * construction (AI-row ships intern above the band first-seen). No deployed roster (the
     * game-free fake-binding contracts, or a caller that never deploys) yields the plain
     * first-seen-from-1 registry — byte-identical to the pre-D7 behavior.
     */
    private static ShipNetworkIdRegistry newSeatReservedRegistry() {
        Map<Long, String> seatStableIds = RealEngineDemoRoster.seatStableIdByWireId();
        // The reserved-band ceiling is the max DESCRIPTOR seat wire id when a
        // descriptor deployed (occupancy-independent — an occupancy-gated deploy may have mounted
        // only a subset, or none at all on the N-boot zero-hull battlefield, yet later fly-ins
        // internReserved into the full band mid-round); no descriptor = the legacy deployed-map
        // ceiling, byte-identical. Pre-claims stay deployed-map-only on both paths.
        long[] descriptorSeatIds = RealEngineDemoRoster.descriptorSeatShipIds();
        long ceiling = 0L;
        if (descriptorSeatIds.length > 0) {
            for (long wireId : descriptorSeatIds) {
                ceiling = Math.max(ceiling, wireId);
            }
        } else {
            if (seatStableIds.isEmpty()) {
                return new ShipNetworkIdRegistry();
            }
            for (Long wireId : seatStableIds.keySet()) {
                ceiling = Math.max(ceiling, wireId);
            }
        }
        ShipNetworkIdRegistry registry = new ShipNetworkIdRegistry(ceiling);
        for (Map.Entry<Long, String> seat : seatStableIds.entrySet()) {
            registry.internReserved(seat.getValue(), seat.getKey());
        }
        return registry;
    }

    /**
     * Immutable pilot serve configuration. {@code framesPerSession} bounds one round's ticks (a round ends
     * earlier on {@code matchOver}); {@code roundsPerSocket} is how many lethal rounds are served to the one
     * persistent client over the SAME socket (1 = the single-round path, no reset); {@code retryBudgetTicks}
     * is the rising-edge held-bit retry window for ship-system activation ({@link PilotApplyState});
     * {@code shipCount} bounds the stable-roster read (the real-engine path passes the deploy-ledger count, see
     * {@link DedicatedCombatServerMain}); {@code emitMatchResetFrame}/{@code awaitReentry} mirror the dedicated
     * lane's round-boundary control frames; {@code framePaceMillis} optionally throttles the loop with a flat
     * per-frame sleep; {@code paceToWallClock} instead throttles each tick to its ABSOLUTE real-wall-clock
     * deadline (the faithful "watch pacer" ported from the scout — the per-frame target derives from
     * {@code fixedDt}, the clock starts at the round's first exchange, and it supersedes {@code framePaceMillis}
     * when on); {@code clientWaitMillis} bounds the wait for the remote pilot to connect.
     *
     * <p>{@code decoupledSend} (default OFF; the B2 WAN-dilation fix) selects
     * the per-peer {@link PeerSendGate} send path instead of the strict blocking ping-pong: with it on the sim
     * thread DEPOSITS each tick's AWSS + durable GPEE/EDSP (never blocking on the socket) and TAKES the latest
     * received intent, while a per-peer writer thread owns the blocking exchange — so the authoritative sim
     * advances independent of the peer's read-rate (the decided "simulation never waits for a peer's socket"
     * invariant) and a slow WAN peer feels only their own input delay
     * instead of dilating the whole match. Gates live exactly ONE round (mint → frame-0 deposit → first-intent
     * barrier → paced deposit/take → {@code shutdown(grace)}): the shutdown hands the socket back
     * single-threaded, so the round-boundary MRST writes, multi-round serve, and seat-2 mode (one gate per
     * transport) all compose. Only {@code awaitReentry} stays refused — MRER is the
     * synthetic lane with no rendered-client consumer (it WOULD compose on the post-shutdown single-threaded
     * boundary; liftable when a consumer exists). Off keeps the proven ping-pong byte-for-byte.
     *
     * <p>{@code sendMode} generalizes the boolean: {@link SendMode#DUPLEX}
     * mints the same per-round gates over the split transport halves ({@code sendSnapshot} /
     * {@code receiveIntent}) so delivery rate decouples from RTT; the barrier/quiesce/terminal serve
     * sites are gate-API-identical across both gate modes. Duplex inherits decoupled's whole guard
     * matrix ({@code awaitReentry} refused; MRST/multi-round/seat-2/wireSink compose).
     *
     * <p>{@code replayReelOrNull} names the recorded reel this serve RE-SIMULATES (the boot gate
     * {@link ReplayBoot} admitted it and forced its inputs); null = a live serve. The config only
     * carries the path; no check runs here (the boot gate already ran them).
     *
     */
    public record PilotServeConfig(
            int framesPerSession,
            float fixedDt,
            int shipCount,
            int roundsPerSocket,
            int retryBudgetTicks,
            boolean emitMatchResetFrame,
            boolean awaitReentry,
            long framePaceMillis,
            boolean paceToWallClock,
            long clientWaitMillis,
            SendMode sendMode,
            Path replayReelOrNull) {

        /**
         * The live-serve spelling (no replay reel): every 11-arg {@code sendMode} call site keeps
         * compiling unchanged.
         */
        public PilotServeConfig(
                int framesPerSession, float fixedDt, int shipCount, int roundsPerSocket,
                int retryBudgetTicks, boolean emitMatchResetFrame, boolean awaitReentry,
                long framePaceMillis, boolean paceToWallClock, long clientWaitMillis,
                SendMode sendMode) {
            this(framesPerSession, fixedDt, shipCount, roundsPerSocket, retryBudgetTicks,
                    emitMatchResetFrame, awaitReentry, framePaceMillis, paceToWallClock,
                    clientWaitMillis, sendMode, null);
        }

        /**
         * A copy with {@code replayReelOrNull} = {@code reel} and every other component unchanged
         * (the re-sim rigs build a live config, then name the reel).
         */
        public PilotServeConfig withReplayReel(Path reel) {
            return new PilotServeConfig(framesPerSession, fixedDt, shipCount, roundsPerSocket,
                    retryBudgetTicks, emitMatchResetFrame, awaitReentry, framePaceMillis,
                    paceToWallClock, clientWaitMillis, sendMode, reel);
        }

        /**
         * Backward-compatible convenience constructor: the proven strict-ping-pong path
         * ({@code SendMode.WELDED}). Existing 10-arg call sites keep compiling unchanged.
         */
        public PilotServeConfig(
                int framesPerSession, float fixedDt, int shipCount, int roundsPerSocket,
                int retryBudgetTicks, boolean emitMatchResetFrame, boolean awaitReentry,
                long framePaceMillis, boolean paceToWallClock, long clientWaitMillis) {
            this(framesPerSession, fixedDt, shipCount, roundsPerSocket, retryBudgetTicks,
                    emitMatchResetFrame, awaitReentry, framePaceMillis, paceToWallClock,
                    clientWaitMillis, SendMode.WELDED);
        }

        /**
         * Backward-compatible convenience constructor: the boolean spelling
         * ({@code decoupledSend} true ⇒ {@link SendMode#DECOUPLED}). Existing 11-arg call sites
         * (the decoupled contract axes) keep compiling unchanged.
         */
        public PilotServeConfig(
                int framesPerSession, float fixedDt, int shipCount, int roundsPerSocket,
                int retryBudgetTicks, boolean emitMatchResetFrame, boolean awaitReentry,
                long framePaceMillis, boolean paceToWallClock, long clientWaitMillis,
                boolean decoupledSend) {
            this(framesPerSession, fixedDt, shipCount, roundsPerSocket, retryBudgetTicks,
                    emitMatchResetFrame, awaitReentry, framePaceMillis, paceToWallClock,
                    clientWaitMillis, decoupledSend ? SendMode.DECOUPLED : SendMode.WELDED);
        }

        /** The boolean view of {@link #sendMode} (kept for its existing consumers). */
        public boolean decoupledSend() {
            return sendMode == SendMode.DECOUPLED;
        }

        public PilotServeConfig {
            if (framesPerSession < 1) {
                throw new IllegalArgumentException("framesPerSession must be >= 1; got " + framesPerSession);
            }
            if (!Float.isFinite(fixedDt) || fixedDt <= 0.0f) {
                throw new IllegalArgumentException("fixedDt must be a positive finite step; got " + fixedDt);
            }
            if (shipCount < 1) {
                throw new IllegalArgumentException("shipCount must be >= 1; got " + shipCount);
            }
            if (roundsPerSocket < 1) {
                throw new IllegalArgumentException("roundsPerSocket must be >= 1; got " + roundsPerSocket);
            }
            if (retryBudgetTicks < 0) {
                throw new IllegalArgumentException("retryBudgetTicks must be >= 0; got " + retryBudgetTicks);
            }
            if (framePaceMillis < 0L) {
                throw new IllegalArgumentException("framePaceMillis must be >= 0; got " + framePaceMillis);
            }
            if (clientWaitMillis < 1L) {
                throw new IllegalArgumentException("clientWaitMillis must be >= 1; got " + clientWaitMillis);
            }
            Objects.requireNonNull(sendMode, "sendMode");
            // Both gate modes compose with multi-round + MRST + seat-2 (per-round
            // gate lifecycle; shutdown hands each socket back single-threaded before any boundary
            // write). Only the MRER reentry gate stays refused: the synthetic lane has no
            // rendered-client consumer (it WOULD compose on the post-shutdown boundary; lift when
            // one exists).
            if (sendMode != SendMode.WELDED && awaitReentry) {
                throw new IllegalArgumentException(
                        "sendMode=" + sendMode + " does not compose with awaitReentry (MRER ="
                                + " synthetic lane, no rendered-client consumer);"
                                + " got awaitReentry=true");
            }
        }
    }

    /**
     * Per-round record of what the pilot serve loop applied + served (diagnostics / game-free assertions).
     *
     * <p>{@code overrunFrames} / {@code overrunMaxNanos} are the watch-pacer KEEP-UP METER (the
     * accounting half of the real-time pacer; faithful port of the scout's {@code watchOverrunFrames}
     * / {@code watchOverrunMaxNanos}, {@code run-headless-bootstrap-scout.ps1:40748/40762-40764}). An overrun is a
     * frame whose absolute deadline had already passed when the loop reached the pacer (the engine step took longer
     * than the frame budget). Deadlines are ABSOLUTE so the schedule self-heals — these counters do NOT change
     * pacing, they only MEASURE it: a sustained nonzero count means the headless engine cannot hold the configured
     * tickrate for a rendered client. Both are 0 when {@code paceToWallClock} is off (the headless measurement path).
     *
     * <p>{@code fireDeclReceived}/{@code fireDeclServed}/{@code fireDeclExpired} are the
     * declared-discharge lane meters (all 0 on the held lane): declarations enqueued, consumed on the
     * server's own observed own-ship discharge, and dropped by ε-timeout/overflow. Acceptance bar:
     * {@code fire_decl_expired=0} with {@code served} tracking {@code received}.
     *
     * <p>{@code workFrames}/{@code workNanosTotal}/{@code workMaxNanos} are the
     * capacity-headroom UTILIZATION meter: loop-body time OUTSIDE the pacer sleep (engine step +
     * detectors + compose + send fan + intent drains), accumulated over the measured frames —
     * utilization = workNanosTotal / (workFrames × frameTargetNanos), the "how close to crushed"
     * number the overrun meter only sees past 100%% of. The npeer lane measures EVERY served frame
     * (its body never blocks on a peer); the legacy lane skips frame 0 (it carries the
     * first-exchange client await) and its welded ping-pong still blocks per exchange, so legacy
     * utilization includes peer-wait — the npeer number is the capacity truth.
     */
    public record PilotRoundSummary(
            int roundIndex,
            int framesServed,
            boolean matchOver,
            int framesApplied,
            int movementCommandsIssued,
            int facingPins,
            int aimPins,
            int systemActivations,
            int ventsIssued,
            int firesIssued,
            int fireDeclReceived,
            int fireDeclServed,
            int fireDeclExpired,
            int overrunFrames,
            long overrunMaxNanos,
            int workFrames,
            long workNanosTotal,
            long workMaxNanos,
            // Carry-cell meters (tail-appended): retreat bit-9 edge applies, R-key target
            // applies, and the fireEdgeSeq latch bars (recovered lost taps / lapsed windows).
            int retreatsIssued,
            int targetApplies,
            int edgeRecovered,
            int edgeExpired,
            // Tail-appended: latches disarmed by a selected-group switch inside
            // the armed window (the group-switch over-fire fix's observable outcome).
            int edgeSwitchDisarmed,
            // Tail-appended: ticks the shield bit-10 hold replay issued the engine's
            // own TOGGLE_SHIELD_OR_PHASE_CLOAK, summed across owned ships.
            long shieldHolds,
            // Tail-appended: latches dropped by the press-span clamp — the tap the
            // client's own engine also dropped (cooldown-blocked), replayed faithfully unfired.
            int edgeClampDropped,
            // Tail-appended: bit-LOW cancel-mirror useSystem() presses (the vanilla
            // double-tap toggle cancel, mirrored server-side), summed across owned ships.
            int systemCancelPresses,
            // Tail-appended: selected-ALTERNATING active-tube pointer conforms (the
            // wire-invisible re-select divergence class), summed across owned ships.
            int tubeOrdinalConformed,
            // Sprint-debt meters (tail-appended): stall→sprint episode census on
            // the live pacer — episodes = maximal runs of consecutive in-debt frames;
            // sprintRunMaxFrames = longest run; sprintWallMaxNanos = longest run's wall span
            // (first→last in-debt frame, pacer-derived); debtEndNanos = unrepaid debt at the
            // round boundary (the only wall-time truly lost — the re-anchor forgives it).
            int sprintEpisodes,
            int sprintRunMaxFrames,
            long sprintWallMaxNanos,
            long debtEndNanos,
            // Tail-appended: ticks whose SNAP fan was withheld by the
            // cc.net.downEveryTicks cadence divisor (0 at the default k=1).
            int snapFanSkippedTicks,
            // Tail-appended: ticks the cloak bit-11 mismatch-edge lane issued
            // the engine's own TOGGLE_SHIELD_OR_PHASE_CLOAK on a cloak hull, summed across owned
            // ships (the shieldHolds twin for the phase half of the verb).
            long cloakToggles,
            // Tail-appended: vent mismatch re-presses (bit-8 held while the
            // server hull was not venting), summed across owned ships — split from ventsIssued
            // (which stays rising-edge-only) so the classic vents meter keeps its meaning.
            int ventRePresses,
            // Tail-appended: non-edge retreat-flag level re-asserts (wire
            // bit-9 vs hull isRetreating() disagreement ticks healed — refused-clear retries +
            // engine reversals), summed across owned ships; 0 = that disagreement never occurred.
            int retreatReasserts,
            // Tail-appended: held-TRUE bit-9 frames ignored between a hull loss
            // and the first bit-false frame, summed across owned ships; 0 = no stale-held retreat
            // level (a client mid ghost-dive) hit a refield hull.
            int retreatRearmSkips,
            // Tail-appended: declared-lane FIRE presses actually issued vs
            // ticks a pending head waited silently (mount not dischargeable this tick) — the
            // live-test strobe made countable. Post-fix bar: presses ≈ served (~1 press per serve).
            int fireDeclPresses,
            int fireDeclWaitTicks,
            // Tail-appended: the OPTION-A adopt-serve lane — named-mount
            // force-fires issued for v4 ray heads / validation drops (reason:count detail,
            // "-" when none) / posted rays the onFire capture never consumed (engine refusal
            // or identity miss; round-end drain, reset-on-read).
            int adoptServes,
            int adoptRejects,
            String adoptRejectReasons,
            long adoptCaptureMiss,
            // Tail-appended: volley batch — same-clientTick ray mates
            // force-fired in their head's own serve tick (≈groupSize−1 per synced volley; 0 under
            // the pre-fix one-head-per-tick serialization).
            int adoptBatched,
            // Tail-appended: TRUE bit-9 frames absorbed inside the
            // post-re-key retreat edge quarantine, summed across owned ships; 0 = no spurious
            // post-transfer retreat level (the W3-J flicker class) hit a re-keyed hull.
            int retreatEdgeQuarantined,
            // Tail-appended: adopt serves whose mount's chargeup was pre-paid
            // on the engine's own state machine (the client already ran it before declaring);
            // 0 on a chargeup-0 roster, ≈ adopt_serves on a chargeup-weapon roster, and 0 with a
            // "preadvance UNBOUND" row = the seam fail-closed (the double-pay is back).
            long adoptPreadvanced,
            // Tail-appended: the subset of fireDeclServed served at
            // intake by a burst-continuation credit — a continuation decl whose shot the server
            // engine already fired as its burst's tail (the orphan-head blackout fix). 0 on a
            // roster with no burst weapons.
            int fireDeclServedCredit,
            // Tail-appended: the credit ledger — minted, dropped
            // unspent (stale/hull-loss/repoint), refused by the per-mount cap, refused by the
            // stateless intake fence, and in-window unmatched discharges the probe read as NOT
            // mid-burst (the echo/divergence class the old law concealed as credits).
            //
            int fireDeclCreditMinted,
            int fireDeclCreditExpired,
            int fireDeclCreditCapped,
            int fireDeclCreditFenceRefused,
            int fireDischargeUnmatchedNonburst,
            // Tail-appended: credits minted on a burst's LAST shot
            // (the pre-tick burst read) — the lane-review law: a lane's meter is EXPORTED on a
            // round line or it does not exist.
            int fireLastShotCreditsMinted,
            // Tail-appended: the edgeExpired subset whose window saw a vent/overload.
            int edgeExpiredGated,
            // Tail-appended: bit-11 cloak toggles WITHHELD under the dive latch (the gate
            // ENGAGED) + the bit-9 sibling that was never on a round line.
            int cloakDiveFreezeTicks,
            int retreatDiveFreezeTicks,
            // Tail-appended: ticks a pending ray head WAITED under its hull's
            // fire lock (PilotApplyState.declLockWaitTicks, summed across owned ships) — printed
            // as fire_lock_waits= on both round lines. Reader = the arena rig's R-LOCK INFO row
            // (that exact token). Delete trigger = the fire-lock fix's own done-when met on two live runs.
            int fireLockWaitTicks,
            // Tail-appended: fire-lock reads that THREW (fail-open:
            // each answered unlocked) — fire_lock_read_errors= on both round lines. SESSION-
            // cumulative (the binding is the session's, PilotServeSession; nothing drains it per
            // round). Reader = the same R-LOCK INFO row; deleted with the token above.
            int fireLockReadErrors,
            // tail-appended human-pilot mark meters summed
            // across owned ships — mark arrays written / removed, and ability-bit frames absorbed
            // by the post-re-point edge quarantine. Printed as pilot_mark_writes=,
            // pilot_mark_clears=, ability_edge_quarantined= on the round line.
            //
            int pilotMarkWrites,
            int pilotMarkClears,
            int abilityEdgeQuarantined) {

        /** Worst single-frame overrun this round, in milliseconds (derived from {@link #overrunMaxNanos()}). */
        public double overrunMaxMillis() {
            return overrunMaxNanos / 1_000_000.0d;
        }

        /** Longest sprint episode's wall span this round, in milliseconds. */
        public double sprintWallMaxMillis() {
            return sprintWallMaxNanos / 1_000_000.0d;
        }

        /** Unrepaid pacer debt at the round boundary, in milliseconds. */
        public double debtEndMillis() {
            return debtEndNanos / 1_000_000.0d;
        }

        /** Worst single measured frame's work this round, in milliseconds. */
        public double workMaxMillis() {
            return workMaxNanos / 1_000_000.0d;
        }

        /** Utilization percent against the given frame budget; 0 when nothing was measured. */
        public double utilizationPercent(long frameTargetNanos) {
            return workFrames > 0 && frameTargetNanos > 0L
                    ? workNanosTotal * 100.0d / ((double) workFrames * frameTargetNanos)
                    : 0.0d;
        }
    }

    /**
     * Per-socket {@link PeerSendGate} accounting for the decoupled send path (null on the blocking path) — the
     * observability + proof surface for port-audit OWED #7. {@code conflatedSnapshots} is the lag meter: each
     * unsent snapshot the sim overwrote because the writer had not yet drained it (a direct, buffer-independent
     * measure that the sim outran the peer — i.e. it did NOT wait). {@code depositsRefused} is nonzero only once
     * the gate goes terminal (kicked/peer-dead); {@code terminalRung} is the final ladder state by name.
     *
     * <p>Duplex fields (all 0 under mode {@code decoupled} — v1 gates never run the split
     * loops): {@code downsSent} = completed SEND-loop writes; {@code staleUps} = pre-barrier UP frames
     * rejected by the round-freshness rule (design pt 5 — never fed to intent state);
     * {@code sendStalls} = completed sends that blocked ≥ one idle slice (the near-backpressure
     * meter); {@code stalePdcls} = declarations dropped pre-barrier by the transport demux gate.
     * {@code upFramesReceived} counts ACCEPTED (post-barrier) intent frames under duplex — the pinned
     * identity is: total wire UP records = {@code upFramesReceived} + {@code staleUps} + PDCL records
     * (design pt 8), so the "UP rate ≈ client rate" law reads {@code upFramesReceived}, never the
     * sink's record count. {@code exchangesCompleted} stays v1-only (0 under duplex).
     */
    public record DecoupledSendStats(
            long deposits,
            long depositsRefused,
            long conflatedSnapshots,
            long exchangesCompleted,
            long upFramesReceived,
            long downsSent,
            long staleUps,
            long sendStalls,
            long stalePdcls,
            String terminalRung) {
    }

    /**
     * The ONE {@code match.fortressDp} resolve —
     * host-GUI DP input, "vanilla ordo size" semantics; 0/absent = AUTO (the authored chain,
     * byte-identical). STRICT whole-number parse, reject-never-clamp (the engineBornGrowth
     * idiom): range [0, 5000] — the ceiling keeps a typo'd total from minting a runaway
     * schedule (the scaled compile ALSO hard-caps rows per wave, fail-closed). Public because
     * the serve-boot pre-flight ({@code PilotIntentCombatServerMain} →
     * {@code FortressServeBootChecks}) must grade the SAME value this serve compiles with
     * (the both-call-sites law).
     */
    public static double resolveFortressDpTotal() {
        String raw = ServerConfig.resolve(
                "cc.match.fortressDp", "CC_MATCH_FORTRESS_DP", "match.fortressDp", "");
        if (raw.isBlank()) {
            return 0.0d;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("match.fortressDp must be a whole DP total in"
                    + " [0, 5000] (0 = auto); got '" + raw + "'");
        }
        if (parsed < 0 || parsed > 5000) {
            throw new IllegalArgumentException("match.fortressDp must be in [0, 5000]"
                    + " (0 = auto); got '" + raw + "'");
        }
        return parsed;
    }

    /**
     * The ONE resolve of {@code match.fortressWaveScale} — the
     * per-wave factors layered on the ratified curve in SCALED mode (law + parse =
     * {@link WaveSchedule#parseFortressWaveScale}; null = blank = unit). The serve compile
     * and the fortress boot check both read THIS, so they grade the same chain.
     */
    public static double[] resolveFortressWaveScale() {
        return WaveSchedule.parseFortressWaveScale(ServerConfig.resolve(
                "cc.match.fortressWaveScale", "CC_MATCH_FORTRESS_WAVE_SCALE",
                "match.fortressWaveScale", ""));
    }

    /**
     * The ONE {@code match.waveRosterFullDp} resolve —
     * the connected roster's total seat DP at which fortress waves reach their full (static,
     * SCALED) size; 0/blank/absent = OFF = no live-roster supplier installed (the drain
     * byte-identical). STRICT whole int [0, 5000], reject-never-clamp (parse law =
     * {@link cc.starsector.server.match.LiveRosterDp#parseFullDp}); set with fortressDp=0 (AUTO)
     * THROWS at boot ({@link WaveSchedule#requireRosterFullDpMode}). Public for the
     * both-call-sites law: the serve install and the fortress boot narration read THIS.
     */
    public static int resolveWaveRosterFullDp() {
        return cc.starsector.server.match.LiveRosterDp.parseFullDp(ServerConfig.resolve(
                "cc.match.waveRosterFullDp", "CC_MATCH_WAVE_ROSTER_FULL_DP",
                "match.waveRosterFullDp", ""));
    }

    /**
     * The ONE {@code match.waveRosterMultiplier} resolve —
     * the host's scaling modifier on the live-roster share (blank/absent = 1.0 = no change;
     * parse law + window = {@link cc.starsector.server.match.LiveRosterDp#parseMultiplier},
     * reject-never-clamp). Set with the roster sizing off on a fortress THROWS at boot
     * ({@link WaveSchedule#requireRosterMultiplierMode}). Public for the both-call-sites law:
     * the serve install and the fortress boot pre-flight read THIS.
     */
    public static double resolveWaveRosterMultiplier() {
        return cc.starsector.server.match.LiveRosterDp.parseMultiplier(ServerConfig.resolve(
                "cc.match.waveRosterMultiplier", "CC_MATCH_WAVE_ROSTER_MULTIPLIER",
                "match.waveRosterMultiplier", ""));
    }

    /**
     * The ONE {@code match.waveCategory} resolve — WHICH faction's authored
     * wave tables the fortress compiles (a MODIFIER on the standing fortress, never a shape;
     * law + parse = {@link WaveSchedule#parseWaveCategory}: blank = remnant, unknown THROWS).
     * Public for the both-call-sites law — the serve compile, the warmup re-mint and
     * the fortress boot narration must all read the SAME category.
     */
    public static String resolveWaveCategory() {
        return WaveSchedule.parseWaveCategory(ServerConfig.resolve(
                "cc.match.waveCategory", "CC_MATCH_WAVE_CATEGORY", "match.waveCategory", ""));
    }

    /**
     * The ONE {@code match.endless}
     * resolve — does the wave chain WRAP after its last wave clears (waves 2..N again, same
     * plan) instead of latching the DEFEND victory? Server-owned match config, no client twin
     * (the client renders whatever roster the wire carries). Returns {@code null} when the knob
     * is ABSENT (the caller applies the default — ON,
     * proven by the headless cat-serve) so an explicit {@code true} can be told
     * apart from the default where it collides with {@code cc.server.waveClearRoundEnd}. Law =
     * the mintDoorFingerprint STRICT boolean: anything but blank/true/false THROWS at serve
     * entry (a set knob is never silently inert).
     */
    public static Boolean resolveMatchEndlessOrNull() {
        String raw = ServerConfig.resolve(
                "cc.match.endless", "CC_MATCH_ENDLESS", "match.endless", "");
        return switch (raw.trim()) {
            case "" -> null;
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException(
                    "cc.match.endless must be 'true' or 'false' (blank = default ON); got '"
                            + raw + "'");
        };
    }

    /** F1: the compiled default when {@code match.endless} is absent (owner: default ON). */
    public static final boolean MATCH_ENDLESS_DEFAULT = true;

    /** The finale creep speed, su/s: 40 graded better than the first value on the 2026-09-02 derelict flight. */
    public static final float FINALE_CREEP_SU_DEFAULT = 40.0f;

    /**
     * The ONE {@code wave.finaleCreepSu} resolve — how fast the derelict
     * FINALE mothership creeps toward the fortress (su/s; default 40 ≈ 3 min edge→midline;
     * {@code 0} = creep OFF, the finale row still spawns and drifts vanilla). Server-owned
     * match config, no client twin (the client renders whatever pose the wire carries).
     * Law + parse = {@link #parseFinaleCreepSu}: a SET knob is never silently inert —
     * negative / NaN / unparsable THROW at serve entry (the {@code parseWaveCategory}
     * precedent), never fall back.
     */
    public static float resolveFinaleCreepSu() {
        return parseFinaleCreepSu(ServerConfig.resolve(
                "cc.wave.finaleCreepSu", "CC_WAVE_FINALE_CREEP_SU", "wave.finaleCreepSu", ""));
    }

    /** {@link #resolveFinaleCreepSu}'s parse law, game-free for the contract row. */
    public static float parseFinaleCreepSu(String raw) {
        if (raw == null || raw.isBlank()) {
            return FINALE_CREEP_SU_DEFAULT;
        }
        float parsed;
        try {
            parsed = Float.parseFloat(raw.trim());
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("wave.finaleCreepSu must be a finite su/s speed"
                    + " >= 0 (0 = creep off, default " + FINALE_CREEP_SU_DEFAULT + "); got '"
                    + raw + "'");
        }
        if (!Float.isFinite(parsed) || parsed < 0.0f) {
            throw new IllegalArgumentException("wave.finaleCreepSu must be a finite su/s speed"
                    + " >= 0 (0 = creep off, default " + FINALE_CREEP_SU_DEFAULT + "); got '"
                    + raw + "'");
        }
        return parsed;
    }

    /**
     * The whole pilot serve outcome: the bound address and every round served to the one persistent client.
     * {@code heldLaneFallback} is the fail-open flag: the declared fire lane was selected but the
     * session flipped once to the legacy held lane (zero declarations ever + fireHeld continuously held).
     */
    public record PilotServeReceipt(
            String host,
            int port,
            boolean offLoopback,
            boolean clientConnected,
            int roundsServed,
            int resetFramesEmitted,
            int reentriesAwaited,
            boolean lastRoundMatchOver,
            boolean heldLaneFallback,
            List<PilotRoundSummary> rounds,
            DecoupledSendStats decoupledSendStats) {

        public PilotServeReceipt {
            rounds = List.copyOf(rounds);
        }
    }


    /**
     * The N-peer serve loop's game-identity seams — {@link #serveNPeer}
     * stays game-free; {@code PilotIntentCombatServerMain} builds these off the real
     * factory/roster, the contract fakes them. {@code reset} is the GATED between-rounds redeploy
     * (EXACTLY the occupied seats; null is legal when {@code roundsPerSocket == 1}) and returns
     * the redeployed seats' fresh identities — the next round's registry pre-interns;
     * {@code flyIn} deploys ONE seat's ship mid-round and returns the deploy-recorded
     * (wireId, stableId) pair the loop interns the SAME frame (D5); {@code firstFlyInSelfCheck}
     * runs once after each round's FIRST successful fly-in (the zero-hull boot defers the
     * factory's message-widget self-check to that moment; the hook no-ops once checked).
     */
    public record NPeerHooks(
            GatedMatchResetHook reset,
            SeatFlyInHook flyIn,
            Runnable firstFlyInSelfCheck,
            WaveSpawnHook waveSpawn,
            GrowthSeamHook growthSeam,
            StationHook station,
            WaveSkipHook waveSkip,
            java.util.function.ToDoubleFunction<String> fortressDp,
            SeatSuicideHook seatSuicide,
            ModuleMountHook moduleMount,
            TransferRekeyHook transferRekey,
            java.util.function.Supplier<List<String>> debugVariantIds,
            FinaleCreepHook finaleCreep,
            CustomsInstaller customsInstaller) {

        public NPeerHooks {
            Objects.requireNonNull(flyIn, "flyIn");
            Objects.requireNonNull(firstFlyInSelfCheck, "firstFlyInSelfCheck");
        }
        // (The `pilotableHulls` classifier component + its back-compat mint were
        // DELETED with the debug-door pick widening.)

        /**
         * The mint with NO customs installer (every caller older than the seam,
         * the real Main included): a null {@code customsInstaller} = the engine-bound
         * {@code CustomVariantWiring.install}, so a customs-on serve composes exactly as before; the ctor chain
         * extends, it never re-orders.
         */
        public NPeerHooks(GatedMatchResetHook reset, SeatFlyInHook flyIn,
                          Runnable firstFlyInSelfCheck, WaveSpawnHook waveSpawn,
                          GrowthSeamHook growthSeam, StationHook station,
                          WaveSkipHook waveSkip,
                          java.util.function.ToDoubleFunction<String> fortressDp,
                          SeatSuicideHook seatSuicide, ModuleMountHook moduleMount,
                          TransferRekeyHook transferRekey,
                          java.util.function.Supplier<List<String>> debugVariantIds,
                          FinaleCreepHook finaleCreep) {
            this(reset, flyIn, firstFlyInSelfCheck, waveSpawn, growthSeam, station,
                    waveSkip, fortressDp, seatSuicide, moduleMount, transferRekey,
                    debugVariantIds, finaleCreep, null);
        }

        /**
         * Back-compat mint with NO finale-creep author (every caller older than the author, incl.
         * the contract fakes): a null {@code finaleCreep} = creep OFF — the derelict finale
         * row still spawns and drifts vanilla (the serve loop guards every consult; the ctor
         * chain extends, it never re-orders).
         */
        public NPeerHooks(GatedMatchResetHook reset, SeatFlyInHook flyIn,
                          Runnable firstFlyInSelfCheck, WaveSpawnHook waveSpawn,
                          GrowthSeamHook growthSeam, StationHook station,
                          WaveSkipHook waveSkip,
                          java.util.function.ToDoubleFunction<String> fortressDp,
                          SeatSuicideHook seatSuicide, ModuleMountHook moduleMount,
                          TransferRekeyHook transferRekey,
                          java.util.function.Supplier<List<String>> debugVariantIds) {
            this(reset, flyIn, firstFlyInSelfCheck, waveSpawn, growthSeam, station,
                    waveSkip, fortressDp, seatSuicide, moduleMount, transferRekey,
                    debugVariantIds, null);
        }

        /**
         * Back-compat mint with NO debug-variants catalogue (every caller older than the catalogue, incl.
         * the contract fakes): a null {@code debugVariantIds} keeps {@code /debugships} +
         * {@code /spawnfoe} honestly "unavailable" at the door (the pickableVariants nullable
         * law; the ctor chain extends, it never re-orders). The real Main supplies the engine
         * variant-registry keyset — a SUPPLIER so the reflective read happens once at door
         * wiring (registry load-time writes quiescent), never at hooks-mint time.
         */
        public NPeerHooks(GatedMatchResetHook reset, SeatFlyInHook flyIn,
                          Runnable firstFlyInSelfCheck, WaveSpawnHook waveSpawn,
                          GrowthSeamHook growthSeam, StationHook station,
                          WaveSkipHook waveSkip,
                          java.util.function.ToDoubleFunction<String> fortressDp,
                          SeatSuicideHook seatSuicide, ModuleMountHook moduleMount,
                          TransferRekeyHook transferRekey) {
            this(reset, flyIn, firstFlyInSelfCheck, waveSpawn, growthSeam, station,
                    waveSkip, fortressDp, seatSuicide, moduleMount, transferRekey, null);
        }

        /**
         * Back-compat mint with NO transfer-rekey lane (every pre-W3-D10(3) caller, incl. the
         * contract fakes): a null {@code transferRekey} is legal — the serve drain aborts an
         * in-flight transfer LOUD "unavailable" when unbound (a player verb is fail-soft like
         * {@code seatSuicide}, never a serve-entry fail-fast; the ctor chain extends, it never
         * re-orders).
         */
        public NPeerHooks(GatedMatchResetHook reset, SeatFlyInHook flyIn,
                          Runnable firstFlyInSelfCheck, WaveSpawnHook waveSpawn,
                          GrowthSeamHook growthSeam, StationHook station,
                          WaveSkipHook waveSkip,
                          java.util.function.ToDoubleFunction<String> fortressDp,
                          SeatSuicideHook seatSuicide, ModuleMountHook moduleMount) {
            this(reset, flyIn, firstFlyInSelfCheck, waveSpawn, growthSeam, station,
                    waveSkip, fortressDp, seatSuicide, moduleMount, null);
        }

        /**
         * Back-compat mint with NO module-mount lane (every pre-W2b caller, incl. the contract
         * fakes): a null {@code moduleMount} keeps the module-seats arbiter un-armed —
         * {@code /module} honestly replies "unavailable" (the pickableVariants nullable law;
         * the ctor chain extends, it never re-orders).
         */
        public NPeerHooks(GatedMatchResetHook reset, SeatFlyInHook flyIn,
                          Runnable firstFlyInSelfCheck, WaveSpawnHook waveSpawn,
                          GrowthSeamHook growthSeam, StationHook station,
                          WaveSkipHook waveSkip,
                          java.util.function.ToDoubleFunction<String> fortressDp,
                          SeatSuicideHook seatSuicide) {
            this(reset, flyIn, firstFlyInSelfCheck, waveSpawn, growthSeam, station,
                    waveSkip, fortressDp, seatSuicide, null);
        }

        /**
         * Back-compat mint with NO seat-suicide lane (every pre-W7b caller, incl. the contract
         * fakes): a null {@code seatSuicide} is legal — the serve loop's {@code /suicide}
         * drain REFUSES with a log line when unbound (a player verb is fail-soft like the
         * operator skip-wave verb, never a serve-entry fail-fast; the ctor chain extends, it
         * never re-orders).
         */
        public NPeerHooks(GatedMatchResetHook reset, SeatFlyInHook flyIn,
                          Runnable firstFlyInSelfCheck, WaveSpawnHook waveSpawn,
                          GrowthSeamHook growthSeam, StationHook station,
                          WaveSkipHook waveSkip,
                          java.util.function.ToDoubleFunction<String> fortressDp) {
            this(reset, flyIn, firstFlyInSelfCheck, waveSpawn, growthSeam, station,
                    waveSkip, fortressDp, null);
        }

        /**
         * Back-compat mint with NO fortress-DP resolver (every caller older than the resolver, incl.
         * the contract fakes): a null {@code fortressDp} is legal until a serve compiles the
         * fortress shape with {@code match.fortressDp} set — the scaled compile fail-fasts
         * inside {@code WaveSchedule.forShape} (the engineBornGrowth nullity precedent; the
         * ctor chain extends, it never re-orders).
         */
        public NPeerHooks(GatedMatchResetHook reset, SeatFlyInHook flyIn,
                          Runnable firstFlyInSelfCheck, WaveSpawnHook waveSpawn,
                          GrowthSeamHook growthSeam, StationHook station,
                          WaveSkipHook waveSkip) {
            this(reset, flyIn, firstFlyInSelfCheck, waveSpawn, growthSeam, station,
                    waveSkip, null);
        }

        /**
         * Back-compat mint with NO wave-skip lane (every caller older than the lane, incl. the
         * contract fakes): a null {@code waveSkip} is legal everywhere — the operator
         * {@code skip-wave} verb REFUSES with a log line when unbound (an operator verb is
         * fail-soft by the freeze law, unlike the fail-fast serve-entry hooks).
         */
        public NPeerHooks(GatedMatchResetHook reset, SeatFlyInHook flyIn,
                          Runnable firstFlyInSelfCheck, WaveSpawnHook waveSpawn,
                          GrowthSeamHook growthSeam, StationHook station) {
            this(reset, flyIn, firstFlyInSelfCheck, waveSpawn, growthSeam, station, null, null);
        }

        /**
         * Back-compat mint with NO wave lane (every pre-1266 caller, incl. the contract fakes):
         * a null {@code waveSpawn} is legal for every shape without a {@link WaveSchedule} —
         * serveNPeer fail-fasts a wave shape whose caller bound no hook.
         */
        public NPeerHooks(GatedMatchResetHook reset, SeatFlyInHook flyIn,
                          Runnable firstFlyInSelfCheck) {
            this(reset, flyIn, firstFlyInSelfCheck, null, null, null);
        }

        /**
         * Back-compat mint with NO growth seam (every pre-1283 caller, incl. the contract
         * fakes): a null {@code growthSeam} is legal while {@code cc.server.engineBornGrowth}
         * stays default-OFF — serveNPeer fail-fasts a growth-enabled serve whose caller bound
         * no hook (the waveSpawn nullity precedent one row up).
         */
        public NPeerHooks(GatedMatchResetHook reset, SeatFlyInHook flyIn,
                          Runnable firstFlyInSelfCheck, WaveSpawnHook waveSpawn) {
            this(reset, flyIn, firstFlyInSelfCheck, waveSpawn, null, null);
        }

        /**
         * Back-compat mint with NO station lane (every pre-1286 caller, incl. the contract
         * fakes and the pre-fortress real Main): a null {@code station} is legal for every
         * shape without a STATION roster row — serveNPeer fail-fasts a station shape whose
         * caller bound no hook (the waveSpawn/growthSeam nullity
         * precedents one row up — the ctor chain extends, it never re-orders).
         */
        public NPeerHooks(GatedMatchResetHook reset, SeatFlyInHook flyIn,
                          Runnable firstFlyInSelfCheck, WaveSpawnHook waveSpawn,
                          GrowthSeamHook growthSeam) {
            this(reset, flyIn, firstFlyInSelfCheck, waveSpawn, growthSeam, null, null);
        }

        /**
         * The gated between-rounds engine redeploy: reset with EXACTLY the occupied seat set and
         * return the redeployed seats' fresh (wireId, stableId) identities in seatId order — the
         * NEXT round's registry pre-interns them (the hook law: identity ALWAYS rides the hooks; the loop
         * never reads roster statics). A null return is fail-closed at the round wrap.
         * {@code variantOverrideBySeat} = each occupied
         * seat's CURRENT pick ({@code /ship} or admit-seeded override; a seat with no pick is
         * ABSENT — the descriptor row is the callee's fallback), never null. The wrap keeps every
         * seat's pick (the W7 B1b v1 descriptor-pure law is replaced); the callee reports the
         * FIELDED variant in each result so the repoint announce compares truthfully. No
         * defaulting overload exists on purpose — a dropped map would be fail-open.
         * {@code sideBySeat} = each seat's kept team side
         * ({@code seatId -> 0 / 1}; a seat ABSENT = its descriptor side), never null: the PvP shape keeps its
         * teams across the wrap, so the callee redeploys such a seat on that side's edge; every other shape
         * clears the sides at the wrap and hands an empty map (the descriptor side, as before). No
         * defaulting overload here either, for the same fail-open reason.
         */
        @FunctionalInterface
        public interface GatedMatchResetHook {
            List<FlyInResult> reset(SortedSet<Integer> occupiedSeatIds,
                                    Map<Integer, String> variantOverrideBySeat,
                                    Map<Integer, Integer> sideBySeat) throws IOException;
        }

        /** Deploy one seat's ship mid-round (a THROW is fatal: an engine deploy failure is never per-session). */
        @FunctionalInterface
        public interface SeatFlyInHook {
            FlyInResult deploy(int seatId) throws IOException;

            /**
             * W7 B1b: deploy honoring the seat's {@code /ship} next-respawn override
             * ({@code overrideOrNull} = the door's stored canonical id, or null = descriptor).
             * Default IGNORES the override (the contract fakes + legacy hooks keep 1-arg
             * behavior); the ONE real hook overrides with the roster's override-consulting twin.
             */
            default FlyInResult deploy(int seatId, String overrideOrNull) throws IOException {
                return deploy(seatId);
            }

            /**
             * Deploy on the seat's {@code /switchteams} side ({@code sideOrNeg} =
             * {@code SeatTeamLane.nextFlyInSideOrNeg}: 0/1, or negative = the descriptor's baked
             * side). Default IGNORES the side (the contract fakes + legacy hooks keep 2-arg
             * behavior); the ONE real hook overrides with the roster's side-consulting twin.
             */
            default FlyInResult deploy(int seatId, String overrideOrNull, int sideOrNeg)
                    throws IOException {
                return deploy(seatId, overrideOrNull);
            }

            /**
             * The seat's hull left the engine world (T3 despawn — DESTROYED or
             * DEPARTED). A roster-backed hook MUST drop its live wire-map entry here, or the
             * seat's next fly-in dies on the roster's fail-closed "already live-mapped" guard
             * (the cooldown-expiry fly-in THREW on the dead hull's never-vacated
             * entry and killed the serve). Default no-op: a hook with no live roster state —
             * the contract fakes, whose fake world despawns its own hulls — has nothing to
             * vacate; the ONE real hook ({@code PilotIntentCombatServerMain.buildSeatFlyInHook})
             * overrides with the fail-closed {@code RealEngineDemoRoster.vacateSeatHull}.
             */
            default void vacate(int seatId, long wireId) throws IOException {
            }
        }

        /**
         * Deploy ONE scheduled wave row's AI hull mid-round (the engine
         * reinforcement edge — {@code RealEngineDemoRoster.midDeployWaveShip} on the real
         * path). The return is the deploy-recorded identity: the serve loop
         * interns the stable id AT MINT (so the wire id is deterministic before the next
         * capture — capture's first-sight intern finds it already present, same id) and fans
         * the {@code RAPP} roster-append row clients mint replica entries from. A THROW is
         * fatal (an engine deploy failure is never per-session).
         */
        @FunctionalInterface
        public interface WaveSpawnHook {
            WaveSpawnResult spawn(int side, String variantId, String shipName) throws IOException;

            /**
             * The pose-aware twin — {@code poseOrNull} = {@code {x, y,
             * facing}} places the hull AT that point with no travel drive
             * ({@code RealEngineDemoRoster.midDeployWaveShip} 4-arg); null = the 3-arg edge
             * slot. Default: null delegates, a pose THROWS (fail-closed — a contract fake or
             * legacy hook must never silently drop the operator's placement).
             */
            default WaveSpawnResult spawn(int side, String variantId, String shipName,
                    float[] poseOrNull) throws IOException {
                if (poseOrNull == null) {
                    return spawn(side, variantId, shipName);
                }
                throw new IOException("pose-placed spawn unsupported by this wave hook ("
                        + variantId + ")");
            }

            /**
             * The officer-aware twin — {@code officerOrNull} = the schedule
             * row's own captain ({@code WaveSchedule.WaveRow.officer()}: the faction tables'
             * per-wave officer rule, the Ziggurat boss). Default: null delegates, a spec THROWS
             * (fail-closed — a contract fake or legacy hook must never silently field an
             * officered row bare, which would erase the whole escalation axis).
             */
            default WaveSpawnResult spawn(int side, String variantId, String shipName,
                    float[] poseOrNull,
                    cc.starsector.server.match.OfficerRosterCodec.OfficerSpec officerOrNull)
                    throws IOException {
                if (officerOrNull == null) {
                    return spawn(side, variantId, shipName, poseOrNull);
                }
                throw new IOException("officered spawn unsupported by this wave hook ("
                        + variantId + ")");
            }

            /**
             * One engine-truth sample line for a wave-probe hull
             * (raw owner / travel-drive / controls-lock / bay census by stable id) — probe
             * instrumentation only, consulted only while the {@code CC_RIG_WAVE_PROBE} env
             * knob is set. Default null = hook has no engine to sample (the contract fakes);
             * the serve loop logs the miss and continues (instrumentation is fail-soft).
             */
            default String probeSampleLine(String stableId) {
                return null;
            }

            /**
             * ADMIRAL: the wave side's admiral posture for {@code category}
             * (vanilla's Shrouded fights to the last, {@code WaveSchedule.fightToTheLast}),
             * applied when a {@code /wave} switch retargets the chain. Default no-op (the
             * contract fakes have no engine); the real hook writes the engine context flag.
             */
            default void wavePosture(String category) {
            }
        }

        /** One wave spawn's identity: the engine ship's deploy-recorded stable id + the CR it
         * deployed at (per-mille — the RAPP row unit). */
        public record WaveSpawnResult(String stableId, int crPerMille,
                List<String> moduleStableIds,
                cc.starsector.server.match.OfficerRosterCodec.OfficerSpec officerOrNull,
                float collisionRadius, boolean atRest) {
            public WaveSpawnResult {
                // collisionRadius = the spawned hull's engine
                // collision radius (the adhoc cluster spacing source); VALUE-DOMAIN finite >= 0,
                // anything else (and every contract fake / legacy shape) reads 0 = unread.
                collisionRadius = Float.isFinite(collisionRadius) && collisionRadius > 0f
                        ? collisionRadius : 0f;
                Objects.requireNonNull(stableId, "stableId");
                // The engine-minted STATION_MODULE children's stable
                // ids (empty for a non-station hull) — interned as the contiguous band right
                // after the root, never RAPP-announced (internWaveHull).
                moduleStableIds = moduleStableIds == null ? List.of() : List.copyOf(moduleStableIds);
                // officerOrNull = the captain the engine roster ACTUALLY attached
                // (row spec or table row) — the RAPP v3 officer tail; null on a bare hull and
                // from every contract fake (the 2/3-arg shapes).
            }

            /**
             * The older shape — {@code atRest=false}.
             * {@code atRest} = {@code WaveDeployResult#atRest} (the mint's own burn arg
             * {@code < 1}: placed AT its pose, no travel drive) → the RAPP v5 flags bit.
             * VALUE-DOMAIN: every contract fake and legacy shape reads {@code false} = "burns"
             * (the pre-v5 client behavior), so a fake can never mint a burn-0 twin by accident.
             */
            public WaveSpawnResult(String stableId, int crPerMille, List<String> moduleStableIds,
                    cc.starsector.server.match.OfficerRosterCodec.OfficerSpec officerOrNull,
                    float collisionRadius) {
                this(stableId, crPerMille, moduleStableIds, officerOrNull, collisionRadius, false);
            }

            /** The older shape — no collision radius reported (0 = unread). */
            public WaveSpawnResult(String stableId, int crPerMille, List<String> moduleStableIds,
                    cc.starsector.server.match.OfficerRosterCodec.OfficerSpec officerOrNull) {
                this(stableId, crPerMille, moduleStableIds, officerOrNull, 0f);
            }

            /** The older shape — no captain reported. */
            public WaveSpawnResult(String stableId, int crPerMille, List<String> moduleStableIds) {
                this(stableId, crPerMille, moduleStableIds, null);
            }

            /** The oldest shape — a hull with no module cluster. */
            public WaveSpawnResult(String stableId, int crPerMille) {
                this(stableId, crPerMille, List.of());
            }
        }

        /**
         * The operator {@code skip-wave} verb's kill half — apply a lethal
         * engine-resolved bypass-shields hit to EVERY live hull on {@code side} (the wave
         * side) through the engine's OWN {@code applyDamage}, so each death rides
         * the NORMAL death/wire machinery and the existing wave-clear trigger sees a genuinely
         * cleared field next capture. Returns the number of hulls hit. The ONE real hook
         * fronts {@code SeatRebind.forceKillSideHulls}; a THROW is fatal (an engine
         * damage-apply failure is never per-session — the wave-spawn law).
         *
         * <p>{@code removeSideHulks} is the verb's deferred CORPSE half —
         * the serve loop fires it {@code WaveSchedule.SKIP_HULK_SWEEP_DELAY_SEC} after a
         * killing skip so dead Omega parents leave play before their 2–3 s ShardSpawner child
         * delay elapses (fronts {@code SeatRebind.removeSideHulks}; same fatality law).
         */
        public interface WaveSkipHook {
            int killSideHulls(int side) throws IOException;

            int removeSideHulks(int side) throws IOException;
        }

        /**
         * The derelict FINALE creep author.
         *
         * {@code arm} binds the freshly-spawned finale ROOT (by its
         * deploy-recorded stable id) to the side-0 fortress root and returns the target's
         * pose text ({@code "x,y"}) — or null when the author stayed DISARMED (no fortress
         * root in play = vanilla drift, never an error). {@code tick} runs PRE-STEP every sim
         * tick while armed (authors the root's velocity toward the target; the engine then
         * integrates the parent and re-pins its modules in the same step);
         * a no-op when disarmed. {@code disarm} is the
         * round boundary's reset. NULLABLE like {@link #waveSkip()}: the contract fakes bind
         * none and the serve loop guards every consult — a missing hook = creep OFF.
         * Fail-soft end to end: the real hook never throws past {@code arm} (a creep failure
         * must never abort the wave drain the finale rides — the row still counts toward the
         * round's clear).
         */
        public interface FinaleCreepHook {
            String arm(String rootStableId, float creepSu) throws IOException;

            void tick(float dtSeconds);

            void disarm();

            /**
             * A finale ESCORT row drained — the author resolves the hull
             * lazily and gives it a LIGHT_ESCORT order on the finale root once both are in
             * play. Default no-op keeps the contract fakes + back-compat mints unbound.
             */
            default void enlistEscort(String escortStableId) {}

            /**
             * The POST-STEP half of the facing law. The engine
             * step integrates {@code facing += ω·dt} BEFORE {@code ship.advance} re-runs the
             * hull's axial-spin hullmod, which re-arms ω every tick; the foe pose the wire
             * carries is sampled after the step, so with the pre-step zero alone the replica
             * still received ω≈2°/s and spun between facing glides (R2). Called by the step
             * decorator right after the engine step; default no-op (fakes, back-compat mints).
             */
            default void postStep() {}
        }

        /**
         * Who composes the custom-variant lane when a serve boots with custom
         * submissions on. WHY a seam: the engine-bound install snapshots the engine's variant facts and registers
         * through the engine, which a serve over the fake world cannot do - a contract serve passes the wiring's
         * caller-supplied install (a facts catalog + a registrar of its own) so the REAL customs path boots. NULLABLE:
         * null = the engine-bound install. Called once, at door wiring, before the port publishes; it may THROW
         * (a policy-file fault refuses the boot, as the engine-bound install does).
         */
        @FunctionalInterface
        public interface CustomsInstaller {
            CustomVariantLane install(PilotIntentDatagramServer door, CustomVariantConfig config,
                    PickableVariants pickableOrNull);
        }

        /**
         * The {@code /suicide} kill half: the lethal
         * bypass-shields self-hit for ONE live seat hull by wire id — death rides the NORMAL
         * death/wire machinery like the wave-skip sibling. Returns false when the hull is
         * already dead/unmounted (a benign no-op, never an error). The ONE real hook fronts
         * {@code SeatRebind.forceKillLiveShipForWireId}; a THROW is fatal (an
         * engine damage-apply failure is never per-session — the wave-skip law).
         */
        @FunctionalInterface
        public interface SeatSuicideHook {
            boolean killSeatHull(long wireId) throws IOException;
        }

        /**
         * The engine-born growth seam — per-variant wing/drone capacity
         * (the stable-roster bound term) + the per-round {@code ObjectRepository} listener
         * capturing engine-born spawns/removals (bay fighters, system drones, engine-spawned
         * ships) for the RAPP announce + despawn-reason lanes. The ONE real hook fronts the
         * reflective {@code bridge.EngineBornGrowthSeam}; contract fakes bind none (null =
         * growth stays OFF for that serve, byte-identical). Lifecycle per round: the deploy
         * mints a FRESH repository instance, so {@code install()} runs per round on the
         * CURRENT one; {@code drain()} runs after each tick's capture; {@code uninstall()}
         * runs at the round wrap AND the serve-finally backstop (idempotent — teardown's
         * {@code clear()} fires no removal callbacks).
         */
        public interface GrowthSeamHook {

            /**
             * Wing/drone capacity for one variant id, loader-resolved (built-in wings are
             * merged into the variant wing list, so Tempest-class built-ins count). A THROW
             * is fatal at serve entry — the bound must never silently under-count.
             */
            GrowthCapacity capacityFor(String variantId) throws IOException;

            /** Install the listener on the round's CURRENT repository (readback-verified). */
            void install() throws IOException;

            /** Drain captured add/remove edges since the last drain (sim-thread only). */
            List<GrowthEvent> drain() throws IOException;

            /** Remove the listener, restoring any prior one (idempotent). */
            void uninstall() throws IOException;

            /**
             * Stable engine ids of THIS round's t=0 roster-deploy hulls
             * (the deploy ledger — seats AND AI rows; the gated reset redeploys fresh
             * objects, so the list is fresh per round). The serve loop seeds its growth
             * known-keys from this at the per-round install — without it the seam's first
             * drain RAPP-announces descriptor hulls the client already minted from the
             * contract (the carrier-rig catch: five t=0 AI hulls fanned as engine-born).
             * Default empty: the contract fakes deploy no ledger-backed roster; the ONE
             * real hook overrides with {@code RealEngineDemoRoster.deployedHullStableIds()}.
             */
            default List<String> deployedHullStableIds() {
                return List.of();
            }
        }

        /**
         * One variant's engine-born growth capacity: Σ launched-wing fighters + system
         * drone cap. The bound contribution is {@link #boundTerm()} = 2×fighters + drones:
         * peak alive EXCEEDS Σ numFighters — returning fighters stay
         * engine-alive while replacements spawn, and extra-deployments systems raise the
         * per-bay gate — so err on headroom; the runaway throw stays the backstop (C9).
         */
        public record GrowthCapacity(int fighters, int drones) {
            public GrowthCapacity {
                if (fighters < 0 || drones < 0) {
                    throw new IllegalArgumentException("growth capacity must be >= 0; got"
                            + " fighters=" + fighters + " drones=" + drones);
                }
            }

            /** This variant's contribution to the stable-roster bound headroom. */
            public int boundTerm() {
                return 2 * fighters + drones;
            }
        }

        /**
         * One captured engine-born lifecycle edge. {@code added} = repository PROMOTION
         * (spawn — feeds the first-sight intern + RAPP announce); else a live REMOVAL,
         * where {@code removedHealthy} ({@code !isHulk && hitpoints>0} at the callback) is
         * the despawn-reason seam truth — healthy = DEPARTED (docked/landed), else
         * DESTROYED (fighter max hull sits under the detector's 800
         * threshold, so snapshot-absence alone misclassifies EVERY fighter dock as a kill).
         * {@code stableKey} = the engine ship's {@code ShipAPI.getId()}; blank ids and
         * sides outside {0,1} are skipped LOUD at capture, never minted here.
         */
        public record GrowthEvent(
                boolean added,
                String stableKey,
                int side,
                String variantId,
                String shipName,
                int crPerMille,
                boolean removedHealthy,
                /** Carrier stable key of a bay-born fighter, {@code ""} = none. */
                String carrierStableKey,
                /** Bay index in the carrier's launch-bay order; 0xFF = none. */
                int bayIndex,
                /** The engine captain as ONE {@code OfficerRosterCodec} grammar
                 *  line (the RAPP v3 {@code officerLine} it rides verbatim); {@code ""} = none. */
                String officerLine) {
            /** The older shape (no carrier placement) — contract rows + removal edges. */
            public GrowthEvent(boolean added, String stableKey, int side, String variantId,
                    String shipName, int crPerMille, boolean removedHealthy) {
                this(added, stableKey, side, variantId, shipName, crPerMille, removedHealthy,
                        "", 0xFF);
            }

            /**
             * The older shape (no captain). Since the captain capture landed
             * the ONE real producer ({@code PilotIntentCombatServerMain.buildGrowthSeamHook})
             * mints the 10-arg record from {@code CapturedEdge.officerLine()} (the capture
             * {@code bridge.EngineBornGrowthSeam.observe} reads {@code getCaptain()}); this
             * 9-arg ctor stays for removal edges + captain-less contract rows.
             *
             */
            public GrowthEvent(boolean added, String stableKey, int side, String variantId,
                    String shipName, int crPerMille, boolean removedHealthy,
                    String carrierStableKey, int bayIndex) {
                this(added, stableKey, side, variantId, shipName, crPerMille, removedHealthy,
                        carrierStableKey, bayIndex, "");
            }

            public GrowthEvent {
                Objects.requireNonNull(stableKey, "stableKey");
                Objects.requireNonNull(variantId, "variantId");
                Objects.requireNonNull(shipName, "shipName");
                Objects.requireNonNull(carrierStableKey, "carrierStableKey");
                if (bayIndex < 0 || bayIndex > 0xFF) {
                    throw new IllegalArgumentException("bayIndex must be 0..255; got " + bayIndex);
                }
                if (carrierStableKey.isEmpty() != (bayIndex == 0xFF)) {
                    throw new IllegalArgumentException("carrierStableKey/bayIndex must be present"
                            + " together or absent together; got '" + carrierStableKey + "'/"
                            + bayIndex);
                }
                if (stableKey.isBlank()) {
                    throw new IllegalArgumentException("stableKey must be non-blank");
                }
                if (side != 0 && side != 1) {
                    throw new IllegalArgumentException("side must be 0 or 1; got " + side);
                }
                if (crPerMille < 0 || crPerMille > 1000) {
                    throw new IllegalArgumentException(
                            "crPerMille must be 0..1000; got " + crPerMille);
                }
                // The line is validated HERE, at the
                // producer's edge, so the drain's AppendRow ctor (the same two laws:
                // RosterAppendFrameCodec.AppendRow) can never throw inside serveFrame. A captain
                // rides a non-carrier ADD only — a bay-born fighter's captain is its carrier's,
                // and a removal carries the add identity, never a stat.
                Objects.requireNonNull(officerLine, "officerLine");
                if (!officerLine.isEmpty()) {
                    if (!added || !carrierStableKey.isEmpty()) {
                        throw new IllegalArgumentException("officerLine rides a non-carrier ADD"
                                + " edge only; got added=" + added + " carrier='"
                                + carrierStableKey + "'");
                    }
                    cc.starsector.server.match.OfficerRosterCodec.decodeOne(officerLine, variantId);
                }
            }
        }

        /**
         * One fly-in's identity: the seat's wire id + the engine ship's deploy-recorded stable
         * id. W7 B1b widened it with the deploy-recorded HULL identity the variant-repoint fan
         * needs ({@code variantId} = deployed, {@code descriptorVariantId} = the seat's
         * descriptor truth, + the RAPP row fields). The 2-arg ctor keeps every legacy mint
         * compiling: {@code variantId == null} marks a legacy result — the fan check SKIPS it
         * (nothing truthful to announce).
         */
        public record FlyInResult(long wireId, String stableId, String variantId,
                String descriptorVariantId, int side, String shipName, int crPerMille) {
            public FlyInResult {
                Objects.requireNonNull(stableId, "stableId");
            }

            /** Legacy identity-only mint (null variantId = the fan check skips this fly-in). */
            public FlyInResult(long wireId, String stableId) {
                this(wireId, stableId, null, null, -1, null, 0);
            }
        }

        /**
         * The STATION identity seam — the two engine
         * reads the fortress serve needs and the serve loop cannot make game-free. The ONE
         * real hook fronts {@code bridge.EngineBornGrowthSeam.stationModuleVariantIds} +
         * {@code bridge.RealEngineDemoRoster.stationBaseHulk}; contract fakes bind none (null
         * = no station lane for that serve, byte-identical — the {@link WaveSpawnHook} /
         * {@link GrowthSeamHook} nullity precedents).
         *
         * <p>Both methods THROW rather than degrade. {@code moduleVariantIds} feeds the
         * stable-roster bound's 4th term at serve entry, where an under-count is a mid-round
         * roster-bound-exceeded kill; {@code baseLost} is the round's LOSE condition, where a
         * silently-false read would keep a dead fortress's round alive forever.
         */
        public interface StationHook {

            /**
             * Module variant ids the station variant {@code variantId} will mint at deploy —
             * the engine's own {@code ShipVariantAPI.getStationModules()} values
             * (slot id → module variant id), loader-resolved. Empty for a non-station
             * variant. A THROW is fatal at serve entry by design: the module bound term must
             * never silently under-count.
             */
            List<String> moduleVariantIds(String variantId) throws IOException;

            /**
             * True once this round's deployed station BASE hull reads {@code isHulk()} — the
             * engine-native lose seam (the base dies, the round is over; modules dying is
             * ordinary attrition). Consulted once per stable-roster tick. A read failure
             * THROWS: a broken lose seam must never keep the round alive silently.
             */
            boolean baseLost() throws IOException;
        }
    }

    /**
     * The N-peer UDP serve loop — ONE multi-peer datagram door admits up to the
     * descriptor's seat count of credentialed sessions; seats FLY IN on claim (the gated zero-hull
     * boot deployed nothing, so EVERY mount arrives mid-round via {@code hooks.flyIn}) and the
     * round ends on the armed {@code matchOver} (both triggers suppressed until ≥2 fighting sides
     * were seen alive this round). Reuses {@link PilotServeConfig}/{@link PilotServeReceipt}
     * verbatim: {@code clientConnected} = ≥1 session ever admitted, {@code reentriesAwaited} = 0
     * (no MRER at N), {@code decoupledSendStats} = null (no gates — the door's reader/timer threads
     * own the wire), {@code heldLaneFallback} = the AGGREGATE latch (any session's held bit feeds
     * one streak; the per-session variant is deferred).
     *
     * <p>DOWN compose is encode-once: one {@link DownTickComposition} per tick above the
     * session fan-out, AWSS split by {@link SnapshotChunker} under the datagram payload cap (
     * emission knob default-OFF = over-cap REFUSES loud), SEAT frames appended to the ctrl lane
     * but NOT to the ONE per-tick {@code KIND_DOWN} sink record ({@code composedDownRecord()},
     * v1 record contract). Ticks are served (and recorded) even with ZERO live sessions — the
     * endpoint-level DOWN record is the authoritative round truth.
     */
    public static PilotServeReceipt serveNPeer(
            PilotServeConfig config,
            EngineCombatBinding binding,
            NPeerHooks hooks,
            ListenerReady onReady) throws IOException {
        return serveNPeer(config, binding, hooks, onReady, null);
    }

    /** The N-peer serve with the W7 B1b {@code /ship} pickable allowlist threaded to the door
     * ({@code pickableOrNull} nullable = the 4-param overload's "picking unavailable"). */
    public static PilotServeReceipt serveNPeer(
            PilotServeConfig config,
            EngineCombatBinding binding,
            NPeerHooks hooks,
            ListenerReady onReady,
            PickableVariants pickableOrNull) throws IOException {
        return serveNPeer(config, binding, hooks, onReady, pickableOrNull, null);
    }

    /** The N-peer serve with the W7c B2 variant catalog threaded to the door
     * ({@code variantCatalogOrNull} nullable = the 5-param overload's "no admission burst"). */
    public static PilotServeReceipt serveNPeer(
            PilotServeConfig config,
            EngineCombatBinding binding,
            NPeerHooks hooks,
            ListenerReady onReady,
            PickableVariants pickableOrNull,
            VariantCatalogStore variantCatalogOrNull) throws IOException {
        return new PilotServeSession(config, binding, hooks, onReady, pickableOrNull,
                variantCatalogOrNull).run();
    }

    /**
     *
     *
     * Mail a drained admin-wave verb's REAL effect back to its depositing
     * session as the chat reply — only chat deposits carry a receipt target (file/GUI mints
     * = 0, no receipt lane). Texts = {@link AdminWaveQueue}'s builders; delivery is the
     * door's thread-agnostic CTRL send, dead-session-safe ({@code sendAdminReceipt}).
     */
    static void waveReceipt(PilotIntentDatagramServer door,
            OperatorCommandFile.Command command, String line) {
        if (command.receiptSessionId() != 0L) {
            door.sendAdminReceipt(command.receiptSessionId(), line);
        }
    }

    /**
     * One ops-file seat row per session — connection truth (endpoint, liveness),
     * per-seat bandwidth twins, spool depth, and the descriptor-derived ship name ({@code "?"}
     * when the map has no unambiguous answer). Invoked only on an actual ops write (1/s).
     */
    static List<OpsStatusFile.SeatRow> opsSeatRows(
            List<PilotIntentDatagramServer.PeerSession> sessions, Map<Integer, String> shipBySeat,
            PilotIntentDatagramServer door) {
        return opsSeatRows(sessions, shipBySeat, door, null);
    }

    /** {@link #opsSeatRows} + the W15 v0 step 3(f) custom block: when the seat's FIELDED id is a
     * custom the ledger knows, the row carries it (hull / unused OP / display name — a pure
     * synchronized in-memory read on the sim thread; {@code ledgerOrNull} = lane OFF ⇒ blanks). */
    static List<OpsStatusFile.SeatRow> opsSeatRows(
            List<PilotIntentDatagramServer.PeerSession> sessions, Map<Integer, String> shipBySeat,
            PilotIntentDatagramServer door, SubmittedVariantLedger ledgerOrNull) {
        List<OpsStatusFile.SeatRow> rows = new ArrayList<>(sessions.size());
        for (PilotIntentDatagramServer.PeerSession session : sessions) {
            // The FIELDED variant (recorded at every seat
            // fly-in, the /ship listing's "(piloting)" mark) is the ship truth — a /ship switch
            // shows at its re-mint; the descriptor-derived map is only the pre-fly-in guess
            // ("?" on a side with several SEAT rows, the fortress).
            String fielded = door == null ? null : door.seatFieldedVariantOrNull(session.seatId());
            String ship = session.observer()
                    // An observer session (seat 0, no hull) renders "obs", never "?".
                    ? "obs"
                    : fielded != null
                            ? fielded : shipBySeat.getOrDefault(session.seatId(), "?");
            // D3 (#3): the wire-announced player name beside the client id ("" until announced).
            String name = session.announcedSeatNameOrNull();
            SubmittedVariantLedger.Accepted custom = ledgerOrNull == null || fielded == null
                    || !fielded.startsWith(SubmittedVariantLedger.ID_PREFIX)
                            ? null : ledgerOrNull.acceptedOrNull(fielded);
            // The seat's ACCEPTED not-banned ids (author key = the door's customAuthorKey —
            // the cid's h1: digest in password mode, seat#<n> in token mode — the
            // lane's onSubmit contract) — the operator can ban/export a held-but-unflown custom.
            String customs = ledgerOrNull == null ? ""
                    : String.join(",", ledgerOrNull.ownAcceptedIds(
                            door == null ? session.cidAuthorDigest() : door.customAuthorKey(session)));
            rows.add(new OpsStatusFile.SeatRow(
                    session.seatId(), session.clientId(),
                    session.peerAddress().getAddress().getHostAddress()
                            + ":" + session.peerAddress().getPort(),
                    ship,
                    session.isLive(), session.bytesSent(), session.bytesReceived(),
                    session.ctrlSpoolDepth(), name == null ? "" : name,
                    custom == null ? "" : custom.canonicalId(),
                    custom == null ? "" : custom.canonical().hullId(),
                    custom == null ? -1 : custom.unusedOp(),
                    custom == null ? "" : custom.canonical().displayName(),
                    customs));
        }
        return rows;
    }

    /**
     * One rig-only wave-probe row — an env-knob direct spawn on the EXACT
     * {@code NPeerHooks.waveSpawn} lane (side 1), NOT a schedule (the fortress rig shape is
     * schedule-LESS by design). One-shot: {@code spawned} latches at the drain.
     */
    static final class WaveProbeRow {
        final String variantId;
        final int dueTick;
        final String shipName;
        boolean spawned;
        String stableId;

        WaveProbeRow(String variantId, int dueTick, String shipName) {
            this.variantId = variantId;
            this.dueTick = dueTick;
            this.shipName = shipName;
        }
    }

    /**
     * The station-module share of the stable-roster bound.
     * {@code moduleVariantIds} = one entry per module the SCHEDULED hulls can mint (roster rows
     * × count + every category twin's variant ids — the growth fold consumes the contents);
     * {@code adhocLargestModules} = the largest cluster any {@code /spawnfoe}-admissible
     * catalogue id mints; {@code adhocCatalogue} = the admissible ids (the catalogue MINUS
     * {@code adhocUnbudgetable}: ids whose cluster read threw — fail-closed: an unbudgetable
     * hull is unspawnable, never a silent under-count). {@link #boundTerm} adds the adhoc
     * worst case (32 roots + 32 × largest cluster) on top of the scheduled modules.
     */
    public record StationModuleBudget(List<String> moduleVariantIds, int adhocLargestModules,
            String adhocLargestVariantId, List<String> adhocCatalogue,
            List<String> adhocUnbudgetable) {

        public int adhocRootTerm() {
            return adhocCatalogue.isEmpty() ? 0 : AdhocFoeSpawner.MAX_PENDING;
        }

        public int boundTerm() {
            return moduleVariantIds.size() + adhocRootTerm()
                    + AdhocFoeSpawner.MAX_PENDING * adhocLargestModules;
        }
    }

    /**
     * Fold EVERY variant the round can field through the station
     * hook (engine {@code variant.isStation()} + {@code getStationModules()} — never the
     * {@code MatchDescriptors} fortress allowlist, which the Survey Mothership is not on).
     * Scheduled ids (roster + twins) keep the hook's fail-closed THROW (a station with no
     * cluster at serve entry is a content boundary). Catalogue ids are folded one by one and
     * a throwing id is EXCLUDED from the returned adhoc catalogue with one loud line — the
     * bound stays honest because the id can no longer be spawned. {@code station == null}
     * (contract fakes) ⇒ no fold, the empty budget; the real Main binds the hook always
     * ({@code PilotIntentCombatServerMain} buildStationHook). Public only for the contract
     * row ({@code StationWaveModuleContractRows}).
     */
    public static StationModuleBudget foldStationModuleBudget(List<MatchDescriptor.RosterRow> roster,
            List<WaveSchedule> categoryTwins, List<String> adhocCatalogueOrNull,
            NPeerHooks.StationHook station) throws IOException {
        List<String> moduleVariantIds = new ArrayList<>();
        List<String> adhocCatalogue = new ArrayList<>();
        List<String> adhocUnbudgetable = new ArrayList<>();
        int largest = 0;
        String largestId = "-";
        if (station == null) {
            return new StationModuleBudget(List.of(), 0, largestId,
                    adhocCatalogueOrNull == null ? List.of() : List.copyOf(adhocCatalogueOrNull),
                    List.of());
        }
        for (MatchDescriptor.RosterRow row : roster) {
            List<String> modules = station.moduleVariantIds(row.variantId());
            for (int i = 0; i < row.count(); i++) {
                moduleVariantIds.addAll(modules);
            }
        }
        for (WaveSchedule twin : categoryTwins) {
            for (String scheduledVariantId : twin.variantIds()) {
                moduleVariantIds.addAll(station.moduleVariantIds(scheduledVariantId));
            }
        }
        if (adhocCatalogueOrNull != null) {
            for (String catalogueId : adhocCatalogueOrNull) {
                int modules;
                try {
                    modules = station.moduleVariantIds(catalogueId).size();
                } catch (IOException | RuntimeException unbudgetable) {
                    adhocUnbudgetable.add(catalogueId);
                    System.out.println("npeer-serve adhoc_station_unbudgetable variant="
                            + catalogueId + " err=" + unbudgetable.getMessage()
                            + " (excluded from the /spawnfoe catalogue)");
                    continue;
                }
                adhocCatalogue.add(catalogueId);
                if (modules > largest) {
                    largest = modules;
                    largestId = catalogueId;
                }
            }
        }
        return new StationModuleBudget(List.copyOf(moduleVariantIds), largest, largestId,
                List.copyOf(adhocCatalogue), List.copyOf(adhocUnbudgetable));
    }

    /**
     * The ONE finale-creep ARM decision shared by the (c2) scheduled and (c2b)
     * probe drains (the (c2c) adhoc {@code /spawnfoe} drain never calls it — an adhoc
     * mothership is not a finale). A null hook (the contract fakes), a zero knob or a
     * non-finale row = nothing, no NPE. Fail-soft by law: an arm failure LOGS and returns
     * false (vanilla drift) — the finale row must still count toward the round's clear, so
     * nothing here may abort the drain. Returns true only when the author armed on a target.
     * Public for the contract row ({@code FinaleCreepContractRows}).
     */
    public static boolean armFinaleCreepIfDue(NPeerHooks.FinaleCreepHook hookOrNull,
            WaveSchedule.WaveRow row, float creepSu, String rootStableId, long rootWireId,
            String lane, long tick) {
        if (hookOrNull == null || !(creepSu > 0.0f) || !WaveSchedule.isFinaleCreepRow(row)) {
            return false;
        }
        String target;
        try {
            target = hookOrNull.arm(rootStableId, creepSu);
        } catch (IOException | RuntimeException armFailure) {
            System.out.println("npeer-serve finale_creep arm_fail root=" + rootWireId
                    + " stable=" + rootStableId + " lane=" + lane + " tick=" + tick
                    + " err=" + armFailure.getClass().getSimpleName() + ": "
                    + armFailure.getMessage() + " (vanilla drift)");
            return false;
        }
        System.out.println("npeer-serve finale_creep " + (target != null ? "armed" : "disarmed")
                + " root=" + rootWireId + " stable=" + rootStableId
                + " creep_su=" + creepSu + " target=" + (target != null ? target : "none")
                + " lane=" + lane + " tick=" + tick
                + (target != null ? "" : " reason=author_stayed_disarmed (no side-0 fortress"
                        + " root in play unless a finale_creep arm_error/arm_skip row precedes"
                        + " - vanilla drift)"));
        return target != null;
    }

    /**
     * The finale-ESCORT sibling of {@link #armFinaleCreepIfDue} — same
     * caller-owned lane law (scheduled + probe drains only), same fail-soft shape: a null
     * hook, a zero knob (creep OFF = no finale behaviour at all, escorts then fly vanilla) or
     * a non-escort row = nothing; the hook's enlist never throws past here (logged, vanilla
     * drift). Returns true when the row was handed to the author.
     */
    public static boolean enlistFinaleEscortIfDue(NPeerHooks.FinaleCreepHook hookOrNull,
            WaveSchedule.WaveRow row, float creepSu, String escortStableId, long escortWireId,
            String lane, long tick) {
        if (hookOrNull == null || !(creepSu > 0.0f) || !WaveSchedule.isFinaleEscortRow(row)) {
            return false;
        }
        try {
            hookOrNull.enlistEscort(escortStableId);
        } catch (RuntimeException enlistFailure) {
            System.out.println("npeer-serve finale_escort enlist_fail escort=" + escortWireId
                    + " stable=" + escortStableId + " lane=" + lane + " tick=" + tick
                    + " err=" + enlistFailure.getClass().getSimpleName() + ": "
                    + enlistFailure.getMessage() + " (vanilla drift)");
            return false;
        }
        System.out.println("npeer-serve finale_escort enlisted escort=" + escortWireId
                + " stable=" + escortStableId + " slot=" + row.shipName()
                + " lane=" + lane + " tick=" + tick);
        return true;
    }

    /**
     * The ONE wave-hull intern shared by the (c2) scheduled, (c2b)
     * probe and (c2c) adhoc drains. Interns the ROOT's stable id, then — for a station-shaped
     * result — every engine-minted MODULE stable id IMMEDIATELY after it, so the wire ids form
     * the contiguous band {@code root+1..root+N} the client's station adopt/bind lane keys on,
     * and seeds every id into {@code growthKnownKeysOrNull}: the growth drain
     * ({@code EngineBornGrowthSeam.observe}) has no module filter and would otherwise
     * announce the six children as engine-born RAPP rows — the client then mints six
     * free-floating duplicate module bodies beside the engine-built ones. Modules get NO RAPP
     * row and are never announced as wave hulls: only the root's row ships. Returns the root
     * wire id. Public only for the contract row ({@code StationWaveModuleContractRows}).
     */
    public static long internWaveHull(ShipNetworkIdRegistry registry, Set<String> growthKnownKeysOrNull,
            NPeerHooks.WaveSpawnResult spawned, String variantId) {
        long rootWireId = registry.networkIdFor(spawned.stableId());
        if (growthKnownKeysOrNull != null) {
            growthKnownKeysOrNull.add(spawned.stableId());
        }
        List<String> modules = spawned.moduleStableIds();
        if (!modules.isEmpty()) {
            long lo = -1L;
            long hi = -1L;
            for (String moduleStableId : modules) {
                long moduleWireId = registry.networkIdFor(moduleStableId);
                lo = lo < 0L ? moduleWireId : Math.min(lo, moduleWireId);
                hi = Math.max(hi, moduleWireId);
                if (growthKnownKeysOrNull != null) {
                    growthKnownKeysOrNull.add(moduleStableId);
                }
            }
            System.out.println("npeer-serve wave_station_modules root=" + rootWireId
                    + " modules=" + modules.size() + " band=" + lo + ".." + hi
                    + " variant=" + variantId);
        }
        return rootWireId;
    }

    /**
     * Parse {@code CC_RIG_WAVE_PROBE=variantId@tick[,variantId@tick...]}.
     * Null/blank = probe off (the only production state). Malformed rows fail fast — a probe
     * run with a silently-dropped row would read as a clean no-spawn and mis-pin the defect.
     * The token {@code finale@tick} expands to the category's REAL finale
     * rows ({@link WaveSchedule#finaleRows}) — real variants AND real slot names (the escort
     * enlist keys on the {@code CC WAVE 9.} prefix), staggered one row per tick from
     * {@code tick} exactly like the (c2) drain (mothership first, so its arm precedes every
     * escort enlist). Fail-fast on a category with no finale.
     */
    static final String WAVE_PROBE_FINALE_TOKEN = "finale";

    static List<WaveProbeRow> parseWaveProbeRows(String raw, String waveCategory) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        List<WaveProbeRow> rows = new ArrayList<>();
        for (String piece : raw.trim().split(",")) {
            String row = piece.trim();
            if (row.isEmpty()) {
                continue;
            }
            int at = row.lastIndexOf('@');
            if (at <= 0 || at == row.length() - 1) {
                throw new IllegalArgumentException(
                        "CC_RIG_WAVE_PROBE row '" + row + "' is not variantId@tick");
            }
            int dueTick;
            try {
                dueTick = Integer.parseInt(row.substring(at + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "CC_RIG_WAVE_PROBE row '" + row + "' has a non-numeric tick");
            }
            if (dueTick < 1) {
                throw new IllegalArgumentException(
                        "CC_RIG_WAVE_PROBE row '" + row + "' tick must be >= 1");
            }
            String variantId = row.substring(0, at);
            if (WAVE_PROBE_FINALE_TOKEN.equals(variantId)) {
                List<WaveSchedule.WaveRow> finale = WaveSchedule.finaleRows(waveCategory);
                if (finale.isEmpty()) {
                    throw new IllegalArgumentException("CC_RIG_WAVE_PROBE row '" + row
                            + "': category '" + waveCategory + "' has no finale wave");
                }
                for (int i = 0; i < finale.size(); i++) {
                    rows.add(new WaveProbeRow(finale.get(i).variantId(), dueTick + i,
                            finale.get(i).shipName()));
                }
                continue;
            }
            rows.add(new WaveProbeRow(variantId, dueTick, "WPROBE-" + (rows.size() + 1)));
        }
        return rows.isEmpty() ? null : rows;
    }

    /** One encoded 38-B SEAT frame per event, in the authority's emission order (ctrl lane). */
    /**
     * The ONE RAPP chunk+fan+record loop — chunk under
     * {@code MAX_ROWS_PER_FRAME}, fan each frame, and append it AS SENT to the round's
     * late-joiner replay log. Shared by the wave lane (its original home) and the growth
     * drain; extracted so the copy never mints (tripwire (iii) adjudication).
     *
     * <p><b>The reset-stamp tripwire (load-bearing client invariant):</b> the client stamps every
     * RAPP frame with the count of MRSTs it has drained from the SAME per-session CTRL FIFO and
     * defers rows stamped past its applied reset. So a NON-seat round-N+1 row must be spooled
     * AFTER round N+1's {@code fanMatchReset} (today: closeRound → wrapRound(MRST) → mintRound
     * → the frame-0 growth drain); a producer that fans a next-round row BEFORE the MRST re-opens
     * the tick-0 wing drop. The seat re-announce at the reset deploy
     * no longer fans here at the wrap: it is collected ({@link SeatAnnounceTracker#announceSeatVariantIfRepointed}'s
     * defer list) and rides the next round-open fan AFTER the MRST.
     */
    static void fanRosterAppendChunks(PilotIntentDatagramServer door,
            List<RosterAppendFrameCodec.AppendRow> appendRows,
            List<byte[]> roundRosterAppendFrames) throws IOException {
        // Chunk BY BYTES (an officered v3 row is ~300 B; the count-only split
        // would fail the encode closed on a 4-officer wave) — the codec owns the law.
        for (List<RosterAppendFrameCodec.AppendRow> chunk : RosterAppendFrameCodec.chunk(appendRows)) {
            byte[] appendFrame = RosterAppendFrameCodec.encode(chunk);
            door.fanRosterAppend(appendFrame, chunk.size());
            roundRosterAppendFrames.add(appendFrame);
        }
    }

    /**
     * The FILE-declared weapon groups (slot-id sets, declared order — the
     * client's live group order by the order law) for one variant, or null when there is
     * no catalog / the variant is not catalog-resident — the group-identity map then stays
     * identity for that hull tenure (the older behavior), which the {@code no_catalog} capped log
     * at the record site makes visible.
     */
    static List<java.util.Set<String>> declaredGroupSlotSetsFor(
            VariantCatalogStore catalogOrNull, String variantId) {
        return catalogOrNull == null ? null : catalogOrNull.declaredGroupSlotSetsOrNull(variantId);
    }

    static List<byte[]> encodeSeatEvents(List<SeatStateEvent> events) {
        List<byte[]> frames = new ArrayList<>(events.size());
        for (SeatStateEvent event : events) {
            frames.add(SeatStateFrameCodec.encodeSeatState(event));
        }
        return frames;
    }

    /** One resolved seat name + which rung of the wire → config → default chain supplied it. */
    private record SeatNameResolution(String name, String src) { }

    /**
     * seat-name resolution — the authoritative fallback chain: the client's wire announce
     * when it actually picked (nameLen &gt; 0; the codec already enforced the parseSeatName contract),
     * else the server-side {@code cc.match.seatNName} config (trimmed; validated loud at roster
     * deploy, and {@code SeatNameFrameCodec.encodeSeatNames} re-validates before it rides the wire),
     * else the long-standing deploy default. Never null/empty.
     */
    private static SeatNameResolution resolveSeatName(
            SeatNameFrameCodec.SeatNameAnnounce announce, String rawConfig, String deployDefault) {
        if (announce != null && announce.hasName()) {
            return new SeatNameResolution(announce.name(), "wire");
        }
        if (rawConfig != null && !rawConfig.trim().isEmpty()) {
            return new SeatNameResolution(rawConfig.trim(), "config");
        }
        return new SeatNameResolution(deployDefault, "default");
    }

    /**
     * One {@code pilot-seat-names announce} line per connected seat (observability — the
     * handshake is pre-sink by design, so log lines are its only record). A shipId that contradicts
     * the transport WARNs and the transport is trusted (the transport IS the seat; a
     * shipId-misconfigured client already breaks the session per the joiner runbook).
     */
    private static void logSeatNameAnnounce(
            int seat, long expectedShipId, SeatNameFrameCodec.SeatNameAnnounce announce) {
        if (announce == null) {
            System.out.println("pilot-seat-names announce seat=" + seat
                    + " none (no SNAM within window; config fallback)");
            return;
        }
        System.out.println("pilot-seat-names announce seat=" + seat
                + " ship_id=" + announce.shipId()
                + " name='" + announce.name() + "'"
                + (announce.hasName() ? "" : " (no-pick sentinel)")
                + (announce.shipId() == expectedShipId
                        ? "" : " WARN ship_id!=" + expectedShipId + " (transport trusted)"));
    }

    /**
     * The {@link ShipSystemView} owned by {@code shipId} (the system list skips ships without a primary
     * system, so the view is keyed by its tagged {@code shipId()} — NOT list index, which would mis-map
     * past a system-less ship or shift on death), or null when the list is null/empty or has no such ship.
     * Used by the gated system-engage measurement only.
     */
    static ShipSystemView viewForShipId(List<ShipSystemView> systems, long shipId) {
        if (systems == null) {
            return null;
        }
        for (ShipSystemView v : systems) {
            if (v != null && v.shipId() == shipId) {
                return v;
            }
        }
        return null;
    }

    /**
     * The effective {@code maxSpeed} cap of the ship with {@code shipId} from the maneuver-profile list, or
     * {@code NaN} when the list is null/empty or has no such ship. Keyed by {@code shipId()} for the same
     * death-stable reason as {@link #viewForShipId}. Gated system-engage measurement only.
     */
    static float maxSpeedForShipId(List<ShipManeuverView> maneuver, long shipId) {
        if (maneuver == null) {
            return Float.NaN;
        }
        for (ShipManeuverView v : maneuver) {
            if (v != null && v.shipId() == shipId) {
                return v.maxSpeed();
            }
        }
        return Float.NaN;
    }

    /**
     * Per-round {@link CombatStepBoundary} decorator that applies the latest STAGED
     * {@link PilotIntentFrame} via {@link EngineCombatBinding#applyPilotIntent} BEFORE forwarding the world
     * advance to the engine — the Pilot-lane analogue of {@code CommandDrivenStep}. Owns one
     * {@link PilotApplyState} per owned ship (the rising-edge / retry memory threaded
     * across ticks); the serve loop creates a fresh one of these per round, so the state never leaks across a
     * reset. Tallies are per-round for the round summary. {@code stage(null)} clears every staged frame (the
     * step then applies nothing); the serve loop never calls it and stages pre-step.
     */
    // Package-private for the W2b ModuleSeatServeLane collaborator (the dispatch precedent).
    static final class PilotIntentStep implements CombatStepBoundary {
        private final CombatStepBoundary baseStep;
        private final EngineCombatBinding binding;
        private final int retryBudgetTicks;
        // The head-declaration ε-timeout in ticks (≈1 s = the effective tick rate).
        private final int declaredFireTimeoutTicks;
        // cc.predict.fireEdgeLatch — threaded into every per-ship apply state at creation.
        private final boolean fireEdgeLatchEnabled;
        private final Map<Long, PilotApplyState> applyStateByShipId = new HashMap<>();
        private boolean declaredFireLane;
        // The class-routing arm/repoint cluster → own-file FireClassRoutingLane (the owed
        // 5000-nb whole-class-trip bite). The
        // lane shares THIS live map by reference; applyStateFor enrolls each creation there.
        private final FireClassRoutingLane fireClassLane;
        // Staged per shipId so both seats' latest frames apply on the next advance.
        // Single-client behavior is unchanged (one key = one entry, replaced each tick). Sorted (TreeMap)
        // so the apply ORDER is deterministic (ship 1 before ship 2) — the cross-run RNG-pin A/B
        // harness compares serve bytes, and an order-unstable apply would inject engine-call divergence.
        private final java.util.TreeMap<Long, PilotIntentFrame> stagedByShipId = new java.util.TreeMap<>();
        // The finale creep author, ticked PRE-STEP (null = creep OFF).
        private NPeerHooks.FinaleCreepHook finaleCreep;
        private int framesApplied;
        private int movementCommandsIssued;
        private int facingPins;
        private int aimPins; // Ticks the carried aim world point pinned the engine's mouseTarget
        private int systemActivations;
        private int ventsIssued;
        private int firesIssued;
        private int groupMirrors; // Ticks the live selected-group mirror issued SELECT_GROUP
        private int autofireToggles; // TOGGLE_AUTOFIRE commands issued by the mask diff
        private int retreatsIssued; // Retreat bit-9 edge applies (engage + cancel both count)
        private int targetApplies; // Ticks the R-key designation drove setShipTarget
        /** First-apply timing latch: three live runs stalled 74-151 ms at round-0
         *  tick 1 (~96 ms in the apply loop), never at r1 tick 1. Minted fresh per round (PSS mintRound) ⇒ r0 row =
         *  cold, r1 = the warm twin; cold >> warm ⇒ fix = a boot warm-up, never a wider stall exemption.
         * */
        private boolean firstApplyTimed;

        /** cc.server.declPad — handed to every per-ship apply state's pad at creation. */
        private final boolean declPadEnabled;
        /**
         * The sim tick of the last {@link #step} (-1 before the first). Read
         * by the post-step discharge dispatch (= that step's tick, the tick applyFireBit pressed
         * on) and stamped as the pad's tenure start at every clear.
         */
        private long lastStepTick = -1L;

        PilotIntentStep(CombatStepBoundary baseStep, EngineCombatBinding binding, int retryBudgetTicks,
                int declaredFireTimeoutTicks, boolean fireEdgeLatchEnabled) {
            this(baseStep, binding, retryBudgetTicks, declaredFireTimeoutTicks, fireEdgeLatchEnabled,
                    PilotServeResolver.resolveDeclPadKnob());
        }

        /** Explicit pad switch (rows); the 5-arg form resolves the boot knob. */
        PilotIntentStep(CombatStepBoundary baseStep, EngineCombatBinding binding, int retryBudgetTicks,
                int declaredFireTimeoutTicks, boolean fireEdgeLatchEnabled, boolean declPadEnabled) {
            this.declPadEnabled = declPadEnabled;
            this.baseStep = Objects.requireNonNull(baseStep, "baseStep");
            this.binding = Objects.requireNonNull(binding, "binding");
            this.retryBudgetTicks = retryBudgetTicks;
            this.declaredFireTimeoutTicks = declaredFireTimeoutTicks;
            this.fireEdgeLatchEnabled = fireEdgeLatchEnabled;
            this.fireClassLane = new FireClassRoutingLane(binding, applyStateByShipId);
        }

        /** Bind (or null = unbind) the finale creep author for this round. */
        void setFinaleCreep(NPeerHooks.FinaleCreepHook hookOrNull) {
            this.finaleCreep = hookOrNull;
        }

        /**
         * The seat a wire id belongs to (the seat's FIXED wire id inverse; -1 = not a seat), read ONCE per apply
         * state when it is minted, so the apply path can write the human-pilot mark {@code int[]{seatId, mask}}
         * into that seat's own hull. Unset (null) = every state stays -1 = no mark (every row and probe that never
         * binds one). Install it right after the step is minted, before the first apply.
         *
         */
        private java.util.function.LongToIntFunction seatIdForWireId;

        void setSeatIdResolver(java.util.function.LongToIntFunction resolverOrNull) {
            this.seatIdForWireId = resolverOrNull;
        }

        /**
         * The pilot mark's flags for a seat ({@code [3]}; bit 0 = the serve follows that pilot's MagicLib decision),
         * evaluated by the session at call time from its own per-seat facts. Each apply state with a seat gets a
         * source over it when minted; the applier reads it once per hull tenure. Unset (null) = flags 0. Installed
         * beside {@link #setSeatIdResolver}.
         */
        private java.util.function.IntUnaryOperator pilotMarkFlagsForSeat;

        void setPilotMarkFlagsResolver(java.util.function.IntUnaryOperator resolverOrNull) {
            this.pilotMarkFlagsForSeat = resolverOrNull;
        }

        private int pilotMarkFlagsOrZero(int seatId) {
            java.util.function.IntUnaryOperator resolver = pilotMarkFlagsForSeat;
            return resolver == null ? 0 : resolver.applyAsInt(seatId);
        }

        /**
         * The seat's admitted HELLO keymap pair ({@code {lo, hi}}: control bytes 0..7 / 8..15 packed, any sign; null
         * = none recorded), looked up by the session at call time from its per-seat store; the pair is never mutated.
         * Each apply state with a seat gets two sources over it when minted
         * ({@code PilotApplyState.pilotKeymapSources}, whose javadoc names when they are read). Unset (null) = 0/0 =
         * the serve's own table answers. Installed beside {@link #setPilotMarkFlagsResolver}.
         */
        private java.util.function.IntFunction<long[]> pilotKeymapForSeat;

        void setPilotKeymapResolver(java.util.function.IntFunction<long[]> resolverOrNull) {
            this.pilotKeymapForSeat = resolverOrNull;
        }

        /** Half {@code half} (0 = lo, 1 = hi) of the seat's keymap pair; 0 with no resolver or no pair. */
        private long pilotKeymapHalfOrZero(int seatId, int half) {
            java.util.function.IntFunction<long[]> resolver = pilotKeymapForSeat;
            long[] pair = resolver == null ? null : resolver.apply(seatId);
            return pair == null ? 0L : pair[half];
        }

        /** Pilot-mark meters summed across this round's apply states (the round-line feed). */
        int pilotMarkWrites() {
            return sumApplyStates(PilotApplyState::pilotMarkWrites);
        }

        int pilotMarkClears() {
            return sumApplyStates(PilotApplyState::pilotMarkClears);
        }

        int abilityEdgeQuarantined() {
            return sumApplyStates(PilotApplyState::abilityEdgeQuarantined);
        }

        // The advance sub-stamps come from the engine-backed base step; without
        // this delegation the interface defaults (0) reach the stall row (the first proof flight
        // printed adv_eng=0.00 while adv=9.63 — this decorator was the missing hop).
        @Override
        public long lastEngineAdvanceNanos() {
            return baseStep.lastEngineAdvanceNanos();
        }

        @Override
        public long lastPostStepNanos() {
            return baseStep.lastPostStepNanos();
        }

        @Override
        public long lastReinjectNanos() {
            return baseStep.lastReinjectNanos();
        }

        @Override
        public long lastEngineAdvanceCpuNanos() {
            return baseStep.lastEngineAdvanceCpuNanos();
        }

        /**
         * Stage the latest received intent for its ship; applied on the NEXT {@link #step} — this frame's
         * own step, since the serve loop stages pre-step. {@code stage(null)} clears ALL
         * staged intents — the pre-map single-field semantics (a decoupled-path tick whose
         * {@code takeIntent()} was null applies nothing next step).
         */
        void stage(PilotIntentFrame frame) {
            if (frame == null) {
                stagedByShipId.clear();
                // Nothing applies next step, so no seat flies its hull: every human-pilot mark goes.
                for (Map.Entry<Long, PilotApplyState> e : applyStateByShipId.entrySet()) {
                    binding.clearPilotMark(e.getKey(), e.getValue());
                }
                return;
            }
            stagedByShipId.put(frame.shipId(), frame);
        }

        /**
         * read-only view of the staged latest-wins intent frames keyed by the
         * seat's fixed round wire id — the AIMP aim-fan lane's per-tick source (aim = each
         * frame's {@code aimWorldX/Y}; hull loss drops the entry ONCE at the despawn drain AND
         * the serve loop refuses to re-stage while the seat holds no mounted hull —
         * {@code PilotServeSession}'s (j) take gate on {@code MatchSeatAuthority.isMounted},
         * so the view only holds seats with a live hull).
         */
        java.util.SortedMap<Long, PilotIntentFrame> stagedIntentsView() {
            return java.util.Collections.unmodifiableSortedMap(stagedByShipId);
        }

        /**
         * Select the fire lane for every per-ship apply state (existing + future). The serve
         * loop only ever flips declared→held while zero declarations were received, so no pending
         * declaration is stranded by the flip.
         */
        void setDeclaredFireLane(boolean declared) {
            this.declaredFireLane = declared;
            for (PilotApplyState state : applyStateByShipId.values()) {
                state.useDeclaredFireLane(declared);
            }
        }

        /**
         * Route one demuxed client declaration to its ship's pending FIFO (the declared lane).
         * {@code applyTick} = the serve tick this pre-step drain feeds (>= 0); a
         * class-H drop offers it to the seat's declared-shot pad.
         */
        void enqueueDeclaration(FireDischargeDeclaration declaration, long applyTick) {
            applyStateFor(declaration.shipId()).enqueueDeclaration(declaration, applyTick);
        }

        /**
         * The class-routing arm/repoint lane, own-file
         * cluster (arm/enroll/disableAll/demote sink + the repoint gate/rebind twins + the
         * class-meter sums). Serve-loop and module-seat call sites route through this accessor.
         */
        FireClassRoutingLane fireClassLane() {
            return fireClassLane;
        }

        /**
         * The server's engine served a discharge on {@code shipId} (post-step {@code WEAPON_DISCHARGE}
         * observed): consume that ship's pending head declaration. A ship with no apply state (the foe;
         * a ship that never declared) consumes nothing.
         *
         * <p>Consume pairing by mount: when the head declaration names its
         * mount AND both the head's and the discharge's mount→group ordinals resolve, the head is
         * consumed only by a discharge from the SAME weapon group (an autofire group's discharge no
         * longer eats a pending manual-shot declaration, which then expired unserved). Any -1 (legacy
         * declaration, unbound seams — every fake binding, unresolvable mount) = the pre-B1
         * consume-on-any-discharge pairing, byte-identical.
         */
        /** The serve loops widen their edge-observe gate on this knob (held lane). */
        boolean fireEdgeLatchEnabled() {
            return fireEdgeLatchEnabled;
        }

        /**
         * Route this tick's post-step {@code WEAPON_DISCHARGE} edges to
         * {@link #onOwnShipDischarge}: the ONE leaf both serve loops call, lane-UNGATED. The old
         * per-loop {@code if (declaredLaneActive)} dispatch gate inverted the latch's
         * lifecycle — the latch arms only under the HELD lane, so its discharge release could
         * never run; every tap pressed FIRE for the full bounded window (
         * {@code t_edge_expired}=66/67 taps, short TPC taps served 3×). Under held the consume
         * half stays a structural no-op (the intake gate keeps the decl FIFO empty); under
         * declared, behavior is identical to the old gated dispatch.
         *
         * <p>GPEE v16: returns the list to fan out — each discharge that consumed a
         * stamped declaration is replaced by {@link GameplayEdgeEvent#withDeclaredWait(int)} carrying
         * its declared wait, in a COPY (the input is never mutated); the SAME {@code tickEdges}
         * instance when nothing was stamped (a tick that stamps nothing allocates nothing).
         * Bookkeeping only — the consume itself is unchanged.
         */
        List<GameplayEdgeEvent> dispatchDischargeEdges(List<GameplayEdgeEvent> tickEdges) {
            List<GameplayEdgeEvent> stamped = null;
            int index = 0;
            for (GameplayEdgeEvent edge : tickEdges) {
                if (edge.kind() == GameplayEdgeEventKind.WEAPON_DISCHARGE) {
                    // The discharge's mount ordinal pairs the consume to
                    // the declared group (autofire discharges stop eating manual declarations).
                    int wait = onOwnShipDischarge(edge.entityId(), edge.mountIndex(), edge.simTick());
                    if (wait >= 0) {
                        if (stamped == null) {
                            stamped = new ArrayList<>(tickEdges);
                        }
                        stamped.set(index, edge.withDeclaredWait(wait));
                    }
                }
                index++;
            }
            return stamped == null ? tickEdges : stamped;
        }

        /**
         * One own-ship discharge edge at {@code dischargeTick} (the edge's {@code simTick()}): release
         * the latch, feed the pad, consume the matching declaration. Returns the v16 declared wait
         * ({@link PilotApplyState#consumeDeclarationOnDischargeWait}), -1 when the ship
         * has no apply state or nothing stamped was consumed.
         */
        int onOwnShipDischarge(long shipId, int dischargeMountIndex, long dischargeTick) {
            PilotApplyState state = applyStateByShipId.get(shipId);
            if (state == null) {
                return -1;
            }
            // Release the fireEdgeSeq latch (held lane) with the same fix-(5) group
            // pairing — an unresolvable ordinal (-1: legacy, unbound seams, fakes) releases on any.
            int dischargeGroup = dischargeMountIndex >= 0
                    ? binding.weaponGroupOrdinalForMount(shipId, dischargeMountIndex)
                    : -1;
            state.releaseLatchOnDischarge(dischargeGroup);
            // The pad's cadence history + bridge pairing (server-space group, step tick).
            state.declPad().onOwnDischarge(lastStepTick, dischargeGroup);
            // Mount-identity consume, widened to the whole FIFO (the
            // tap-fire echo fix): the declared serve is GROUP-level FIRE, so one served tap can
            // discharge several declared mounts in the same tick — each discharge consumes ITS
            // OWN mount's pending declaration wherever it sits in the FIFO. Head-only consume
            // stranded the sibling declarations, which kept FIRE pressed and re-fired the group
            // at cooldown end (every multi-mount tap double-fired). Identity law +
            // rationale live at PilotApplyState.consumeDeclarationOnDischarge; the Wait form is the
            // same consume and also answers the declared wait.
            return state.consumeDeclarationOnDischargeWait(dischargeMountIndex, dischargeTick);
        }

        /**
         * The ship's hull despawned ({@code seat_mount_loss}): flush its WHOLE pending
         * declaration FIFO (otherwise each queued/in-flight
         * decl waits the 60-tick head ε-timeout SERIALLY against a hull no discharge can
         * serve). Returns the flushed count (0 when the ship
         * never declared / has no apply state — the foe, an AI ship).
         */
        int flushDeclarationsOnHullLoss(long shipId) {
            PilotApplyState state = applyStateByShipId.get(shipId);
            return state == null ? 0 : state.flushPendingOnHullLoss();
        }

        /**
         * The ship's hull despawned: drop its STAGED frame and reset its
         * retreat edge memory. The staged map replays each wire id's LAST received frame every
         * tick until replaced; a frame captured while the client's dying hull was mid-dive/
         * retreat carries bit-9=1, survives the vacate, and — the tick the refield re-binds the
         * wire id to the fresh edge-spawned hull — level-re-asserts {@code setRetreating(true)}
         * onto it, so the engine's edge scan removes it next tick (measured: fly_in t21681 →
         * HULL_DEPARTED t21682, and a second run's twin t28475→t28476). Dropping the frame
         * means NOTHING applies for the seat until the client sends a frame sampled off a hull it
         * has actually seen — the same reason the facing lane pins nothing during the fly-in
         * burn. Returns true when a staged frame was actually dropped (operator-line signal).
         */
        boolean clearStagedIntentOnHullLoss(long shipId) {
            boolean dropped = stagedByShipId.remove(shipId) != null;
            PilotApplyState state = applyStateByShipId.get(shipId);
            if (state != null) {
                state.resetRetreatEdgeOnHullLoss(lastStepTick); // + the pad's tenure stamp
                // A still-armed gate dies with the hull — each refield decides
                // fresh at its own repoint (edge-driven arm, never a carried level).
                state.clearMaskStalenessGate();
            }
            binding.clearPilotMark(shipId, state); // the seat left this hull: its human-pilot mark goes
            return dropped;
        }

        /**
         * The seat's fresh hull flew in at {@code flyInTick} (the despawn →
         * respawn fly-in seam): arm the refield stale-frame gate on its apply state, when a
         * hull loss preceded it (law = {@code PilotApplyState.refieldGateHolds}). {@code get},
         * not {@code computeIfAbsent}: a seat that never applied a frame has no dead-hull
         * sample to withhold. Returns whether it armed (the fly-in row's token).
         */
        boolean armRefieldGateAtFlyIn(long shipId, long flyInTick) {
            PilotApplyState state = applyStateByShipId.get(shipId);
            return state != null && state.armRefieldGateIfPending(flyInTick);
        }

        /**
         * The seat VACATED (grace entry) with its hull still ALIVE: drop its
         * staged frame. The staged map replays each wire id's LAST received frame every tick until
         * replaced, and a vacated seat never replaces it — so the departed pilot's final sample
         * kept applying forever: stripping the fresh grace AI at command time (the flown
         * self-check's {@code ai-suppressed} two ticks after attach — the "vacant seats see
         * no command-time path" assumption was FALSE), re-pinning facing/aim to the stale sample
         * each tick (the pilotless-ballistics motion series), and replaying a held FIRE bit at
         * weapon cadence (run-1's post-drop hellbore residue). Deliberately NOT
         * {@link #clearStagedIntentOnHullLoss}: the hull lives on under the grace AI, so the
         * hull-loss resets (retreat edge memory, mask-staleness gate) stay untouched for a
         * possible reclaim. Returns whether a frame was actually dropped (grace_enter log field).
         */
        boolean clearStagedIntentOnGraceVacate(long shipId) {
            // The hull lives on under the grace AI: a stale human-pilot mark would idle a mod's AI branch.
            binding.clearPilotMark(shipId, applyStateByShipId.get(shipId));
            return stagedByShipId.remove(shipId) != null;
        }

        /**
         * A NEW peer session now feeds an EXISTING wire id — called from the serve loop's
         * session_live diff, which runs BEFORE this tick's step, intent take and declaration drain, so
         * the reset lands before the new session's first frame or declaration reaches the state. Drops
         * the OLD session's staged frame first (the latest-wins replay at the next step would re-init
         * the seq to the old value and re-arm the phantom — a live TAKEOVER is
         * VACATE_DISPLACED, which neither the grace ENTER drop nor the seat_vacate clear reaches, grace
         * ON or OFF). The drop runs BEFORE the state guard: a frame staged but never applied
         * (transit-parked) has no state yet and would mint one at the next step through
         * {@link #applyStateFor} with the OLD seq. {@code get}, never {@link #applyStateFor}, for the
         * reset itself: first contact and observers have no state and must not enroll one. Prints ONE
         * event row = the flight PROOF token (`fire_edge_session_reset`).
         */
        void onSessionBoundary(long shipId, long sessionId) {
            boolean stagedDropped = stagedByShipId.remove(shipId) != null;
            PilotApplyState state = applyStateByShipId.get(shipId);
            // The old session's mark dies BEFORE the new session's first frame (which re-points + quarantines).
            binding.clearPilotMark(shipId, state);
            if (state == null && !stagedDropped) {
                return;
            }
            System.out.println("npeer-serve fire_edge_session_reset wire_id=" + shipId
                    + " session=" + sessionId + " "
                    + (state != null ? state.onSessionBoundary(lastStepTick) : "prev_seq=none latch_was_armed=false decls_flushed=0")
                    + " staged_dropped=" + stagedDropped);
        }

        // W3 L4 S1: seat wire ids whose staged-intent APPLICATION is
        // transit-PARKED — the pod flies while the source hull rides the cautious grace AI;
        // the client still SENDS intents at S1 (its TRANSIT mode is S2) and the grace pin
        // only protects the AI from the strip, not the hull from maneuver application, so
        // the apply loop below skips parked ids (park = the upstream gate). PER-ROUND set: the
        // step is re-minted every round, so it dies with the step; the wrap's clear is a belt.
        private final java.util.Set<Long> transitParkedWireIds = new java.util.HashSet<>();

        /** Transit-park the seat's staged application (serve lane's park engage). */
        void parkTransit(long shipId) {
            transitParkedWireIds.add(shipId);
            // The pod flies and the source hull is AI-flown: a stale human-pilot mark would idle its abilities.
            binding.clearPilotMark(shipId, applyStateByShipId.get(shipId));
        }

        /** End the seat's transit park (abort restore / commit re-key). */
        void unparkTransit(long shipId) {
            transitParkedWireIds.remove(shipId);
        }

        /** Round wrap: parks must never outlive the round (the transit-park leak law). */
        void clearTransitParks() {
            transitParkedWireIds.clear();
        }

        /**
         * The seat hull's emergency dive STARTED (F4c edge, engine-truth):
         * freeze its bit-9 retreat lane until hull loss. Wired off the drained
         * {@link cc.starsector.server.protocol.PhaseDiveEvent}s (the EDGE, deliberately not the
         * {@code diveLatchedWireIds} LEVEL: wire ids rebind to the refield hull at respawn, and
         * a level push would re-freeze the fresh hull's lane — the rebind finding).
         * {@code get} not {@code computeIfAbsent}: no state means the seat never applied a
         * frame, so no re-assert flap exists to freeze (foe/AI dives reap natively).
         */
        void latchRetreatLaneOnDive(long shipId) {
            PilotApplyState state = applyStateByShipId.get(shipId);
            if (state != null) {
                state.latchDiveOwnsRetreat();
            }
        }

        // Per wire id, the FILE-declared weapon groups of the hull tenure's
        // variant (slot-id sets, declared order) — recorded at every seat fly-in, consumed at
        // apply-state creation AND re-installed on the existing state at each fly-in (a fly-in IS
        // the tenure edge, so the identity map rebuilds exactly at the FireClassResolver rebind
        // edges). Null value = no file truth ⇒ identity map (the older behavior).
        private final Map<Long, List<java.util.Set<String>>> declaredGroupsByWireId =
                new HashMap<>();
        private int declaredGroupsNoCatalogLogs;

        /**
         * Record one seat fly-in's file-truth weapon groups + reset the
         * (possibly existing) apply state's identity map for the fresh tenure. Called for EVERY
         * fly-in with a known variant, repointed or not — a same-variant refield still swaps the
         * hull object, so the live group order must be re-proven each tenure.
         */
        void recordDeclaredGroups(long shipId, String variantId,
                List<java.util.Set<String>> fileGroups) {
            declaredGroupsByWireId.put(shipId, fileGroups);
            if (fileGroups == null && variantId != null && declaredGroupsNoCatalogLogs < 8) {
                declaredGroupsNoCatalogLogs++;
                System.out.println("npeer-serve group_identity no_catalog wire_id=" + shipId
                        + " variant=" + variantId
                        + " (map stays identity for this tenure)");
            }
            PilotApplyState state = applyStateByShipId.get(shipId);
            if (state != null) {
                state.declaredWeaponGroups(fileGroups);
            }
        }

        private int seatIdForWireIdOrMinusOne(long wireId) {
            if (seatIdForWireId == null) {
                return -1;
            }
            try {
                return seatIdForWireId.applyAsInt(wireId);
            } catch (RuntimeException fault) {
                if (seatIdResolveErrors++ == 0) {
                    System.out.println("npeer-serve pilot_mark seat_resolve_error err="
                            + fault.getClass().getSimpleName() + " (logged once; that state carries no mark)");
                }
                return -1;
            }
        }

        private int seatIdResolveErrors;

        private PilotApplyState applyStateFor(long shipId) {
            return applyStateByShipId.computeIfAbsent(shipId, id -> {
                PilotApplyState state =
                        new PilotApplyState(retryBudgetTicks, declaredFireTimeoutTicks);
                state.useDeclaredFireLane(declaredFireLane);
                state.fireEdgeLatch(fireEdgeLatchEnabled);
                state.declPad().enabled(declPadEnabled); //
                // The human-pilot mark's seat, resolved once (the wire id is the seat's FIXED id); -1 = no mark.
                state.seatId(seatIdForWireIdOrMinusOne(id));
                int markSeat = state.seatId();
                if (markSeat >= 1) { // the mark's flags, re-read per hull tenure through the step's live resolver
                    state.pilotMarkFlagsSource(() -> pilotMarkFlagsOrZero(markSeat));
                    state.pilotKeymapSources(() -> pilotKeymapHalfOrZero(markSeat, 0),
                            () -> pilotKeymapHalfOrZero(markSeat, 1)); // the HELLO keymap, read the same way
                }
                // The declared serve's press gate — cache-free live reads, so
                // one install covers every hull tenure on this wire id (no repoint rebind needed).
                // On fake bindings every read fails open to "press", today's path verbatim.
                state.mountReadinessProbe(binding.mountReadinessProbeForShip(id));
                // The adopt-serve driver — validates a v4 ray head
                // fail-CLOSED (range/ε/arc/rate/seam) and force-fires the NAMED mount
                // (a scoped exception to the engine-authoritative-first rule). On fake bindings every seam is
                // unbound ⇒ every ray head rejects adopt-seam LOUD; ray-less heads keep
                // the press path above and never reach it.
                state.mountFireDriver(binding.mountFireDriverForShip(id));
                // D2 — the adopt serve's fire-lock wait.
                // Cache-free like the readiness probe (one install covers every hull tenure on
                // this wire id); null on fake bindings ⇒ never locked, today's path verbatim.
                state.fireLockProbe(binding.fireLockProbeForShip(id));
                // Install the tenure's file-truth groups recorded at fly-in
                // (absent key/null = identity map — legacy doors and contract fakes unchanged).
                state.declaredWeaponGroups(declaredGroupsByWireId.get(id));
                // Enroll AFTER useDeclaredFireLane — the exclusivity guards are
                // two-directional, and declaredFireLane is false whenever routing is on
                // (boot THROW + the fallbacks only ever flip lanes off).
                fireClassLane.enrollAtCreation(id, state);
                return state;
            });
        }

        @Override
        public CombatWorldState worldState() {
            return baseStep.worldState();
        }

        @Override
        public float effectiveStepAmount(float fixedDt) {
            return baseStep.effectiveStepAmount(fixedDt);
        }

        @Override
        public void step(float fixedDt, long simTick) {
            lastStepTick = simTick; // this step's tick, read by the post-step discharge dispatch
            // Open the stall sampler's live window BEFORE this pre-step work.
            baseStep.beginLiveStep();
            // Clear every installed DD1 shim's reinject record BEFORE applying
            // staged intents — a seat that applies no frame this tick reinjects nothing on the
            // fast-time passes (exactly what its pass 1 issued). No shims (every game-free lane,
            // the legacy serve) = no-op.
            binding.beginReinjectTick();
            // Bracket the apply loop until one tick accepts.
            final boolean timeFirst = !firstApplyTimed;
            final long firstApplyT0 = timeFirst ? System.nanoTime() : 0L;
            int firstApplyAccepted = 0;
            // Ascending-shipId apply order (see stagedByShipId): every staged seat's latest frame applies
            // before the world advance. Tallies sum across seats (per-round, per-step accounting unchanged
            // for the single-client lane — one staged entry).
            for (PilotIntentFrame frame : stagedByShipId.values()) {
                if (transitParkedWireIds.contains(frame.shipId())) {
                    // W3 L4 S1 transit park: the seat's hull is AI-flown while its
                    // pod flies — frames stage (latest-wins) but never apply until unpark.
                    continue;
                }
                PilotApplyState state = applyStateFor(frame.shipId());
                EngineCombatBinding.PilotApplyResult result = binding.applyPilotIntent(frame, simTick, state);
                if (result.accepted()) {
                    framesApplied++;
                    firstApplyAccepted += timeFirst ? 1 : 0;
                    movementCommandsIssued += result.movementCommandsIssued();
                    if (result.facingPinned()) {
                        facingPins++;
                    }
                    if (result.aimPinned()) {
                        aimPins++;
                    }
                    if (result.systemActivated()) {
                        systemActivations++;
                    }
                    if (result.ventIssued()) {
                        ventsIssued++;
                    }
                    if (result.fireIssued()) {
                        firesIssued++;
                    }
                    if (result.groupMirrored()) {
                        groupMirrors++;
                    }
                    autofireToggles += result.autofireTogglesIssued();
                    if (result.retreatIssued()) {
                        retreatsIssued++;
                    }
                    if (result.targetApplied()) {
                        targetApplies++;
                    }
                }
            }
            // ONE row per step instance; an all-rejected tick stays armed.
            if (timeFirst && firstApplyAccepted > 0) {
                firstApplyTimed = true;
                System.out.println("npeer-serve first_apply tick=" + simTick + " ms=" + String.format(
                        java.util.Locale.ROOT, "%.2f", (System.nanoTime() - firstApplyT0) / 1_000_000.0d)
                        + " seats=" + firstApplyAccepted);
            }
            // The finale creep author runs PRE-STEP — it writes the root's
            // velocity, then the engine step integrates the parent and re-pins its modules
            // in the same step. A no-op while disarmed; never throws.
            if (finaleCreep != null) {
                finaleCreep.tick(fixedDt);
            }
            baseStep.step(fixedDt, simTick);
            // The facing law's POST-STEP half — the step's hullmod pass
            // re-armed the root's axial ω after the integrate; zero it again so the post-step
            // pose sample (what the wire carries) reads ω=0. No-op while disarmed.
            if (finaleCreep != null) {
                finaleCreep.postStep();
            }
        }

        @Override
        public boolean inputCouplingBypassed() {
            return baseStep.inputCouplingBypassed();
        }

        int framesApplied() {
            return framesApplied;
        }

        int movementCommandsIssued() {
            return movementCommandsIssued;
        }

        int facingPins() {
            return facingPins;
        }

        int aimPins() {
            return aimPins;
        }

        int systemActivations() {
            return systemActivations;
        }

        int ventsIssued() {
            return ventsIssued;
        }

        int firesIssued() {
            return firesIssued;
        }

        int groupMirrors() {
            return groupMirrors;
        }

        int autofireToggles() {
            return autofireToggles;
        }

        /** Declared-lane meters, summed across this round's per-ship apply states. */
        int declarationsReceived() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.declarationsReceived();
            }
            return total;
        }

        int declarationsServed() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.declarationsServed();
            }
            return total;
        }

        /**
         * The subset of {@link #declarationsServed()} served at intake by a
         * burst-continuation credit (never enqueued).
         */
        int declarationsServedByCredit() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.servedByCredit();
            }
            return total;
        }

        /**
         * One of the credit-ledger meters summed across this round's
         * per-ship apply states (the {@link #declarationsServedByCredit()} pattern; cumulative,
         * no drain).
         */
        int sumApplyStates(java.util.function.ToIntFunction<PilotApplyState> meter) {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += meter.applyAsInt(state);
            }
            return total;
        }

        /** The binding's fail-open fire-lock read errors (cumulative, no drain). */
        int fireLockReadErrors() {
            return binding.fireLockReadErrors();
        }

        int declarationsExpired() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.declarationsExpired();
            }
            return total;
        }

        /** Press-gate meters, summed like the decl meters above. */
        int declPressesIssued() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.declPressesIssued();
            }
            return total;
        }

        int declPressWaitTicks() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.declPressWaitTicks();
            }
            return total;
        }

        /** dwell-machine tube advances, summed like the decl meters above. */
        int altDwellAdvances() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.altTubeDwellAdvances();
            }
            return total;
        }

        /**
         * reasoned-expiry detail across ships —
         * {@code s<ship>m<mount>:<reason>=<n>} comma-joined in shipId order, {@code "-"} when
         * empty, bounded ~480 chars ({@code ,+more} marks truncation). Reasons are the fixed
         * logExpiry vocabulary, so the field is single-line printable by construction — a
         * write-only round-line diagnostic (log laws conform; no wire surface).
         */
        String fireDeclExpiryDetail() {
            StringBuilder detail = new StringBuilder();
            boolean truncated = false;
            walk:
            for (Map.Entry<Long, PilotApplyState> byShip
                    : new java.util.TreeMap<>(applyStateByShipId).entrySet()) {
                for (Map.Entry<String, Integer> e
                        : byShip.getValue().expiredByMountReason().entrySet()) {
                    String piece = "s" + byShip.getKey() + e.getKey() + "=" + e.getValue();
                    if (detail.length() + piece.length() + 1 > 480) {
                        truncated = true;
                        break walk;
                    }
                    if (detail.length() > 0) {
                        detail.append(',');
                    }
                    detail.append(piece);
                }
            }
            if (truncated) {
                detail.append(",+more");
            }
            return detail.length() == 0 ? "-" : detail.toString();
        }

        /** Adopt-serve meters, summed like the decl meters above. */
        int declAdoptServes() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.declAdoptFireIssued();
            }
            return total;
        }

        /** Volley mates served in their head's tick, summed like the above. */
        int declAdoptBatched() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.declAdoptBatchServed();
            }
            return total;
        }

        int declAdoptRejects() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.declAdoptRejects();
            }
            return total;
        }

        /** Reason detail merged across states: {@code reason:count,…} or {@code "-"}. */
        String declAdoptRejectReasons() {
            java.util.LinkedHashMap<String, Integer> merged = new java.util.LinkedHashMap<>();
            for (PilotApplyState state : applyStateByShipId.values()) {
                for (java.util.Map.Entry<String, Integer> e
                        : state.adoptRejectsByReason().entrySet()) {
                    merged.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }
            if (merged.isEmpty()) {
                return "-";
            }
            StringBuilder detail = new StringBuilder();
            for (java.util.Map.Entry<String, Integer> e : merged.entrySet()) {
                if (detail.length() > 0) {
                    detail.append(',');
                }
                detail.append(e.getKey()).append(':').append(e.getValue());
            }
            return detail.toString();
        }

        int retreatsIssued() {
            return retreatsIssued;
        }

        int targetApplies() {
            return targetApplies;
        }

        /** fireEdgeSeq latch meters (recovered lost taps / lapsed windows), summed across per-ship apply states. */
        int edgeRecovered() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.edgeRecovered();
            }
            return total;
        }

        int edgeExpired() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.edgeExpired();
            }
            return total;
        }

        /** Meter: group-switch latch disarms, summed across per-ship states. */
        int edgeSwitchDisarmed() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.edgeSwitchDisarmed();
            }
            return total;
        }

        /** Meter: press-span clamp drops, summed across per-ship apply states. */
        int edgeClampDropped() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.edgeClampDropped();
            }
            return total;
        }

        /** Meter: system cancel-mirror presses, summed across per-ship apply states. */
        int systemCancelPresses() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.systemCancelPresses();
            }
            return total;
        }

        /** Meter: active-tube pointer conforms, summed across per-ship apply states. */
        int tubeOrdinalConformed() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.tubeOrdinalConformed();
            }
            return total;
        }

        /** Meter: shield bit-10 hold-replay issues, summed across per-ship apply states. */
        long shieldHolds() {
            long total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.shieldHoldTicks();
            }
            return total;
        }

        /** Cloak bit-11 mismatch-edge toggles, summed across per-ship apply states. */
        long cloakToggles() {
            long total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.cloakToggleTicks();
            }
            return total;
        }

        /** Vent mismatch re-presses, summed across per-ship apply states. */
        int ventRePresses() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.ventRePresses();
            }
            return total;
        }

        /** Meter: non-edge retreat-flag level re-asserts, summed across per-ship apply states. */
        int retreatReasserts() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.retreatReasserts();
            }
            return total;
        }

        /** Meter: post-hull-loss held-TRUE bit-9 skips, summed across per-ship apply states. */
        int retreatRearmSkips() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.retreatRearmSkips();
            }
            return total;
        }

        /** Meter: quarantined post-re-key TRUE bit-9 frames, summed across per-ship apply states. */
        int retreatEdgeQuarantined() {
            int total = 0;
            for (PilotApplyState state : applyStateByShipId.values()) {
                total += state.retreatEdgeQuarantined();
            }
            return total;
        }
    }
}
