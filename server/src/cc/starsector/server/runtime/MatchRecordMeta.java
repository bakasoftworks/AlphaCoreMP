package cc.starsector.server.runtime;

import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Record completeness, the cheap-now items:
 * the ONE place that builds the {@link WireRecordSink}
 * {@code meta} map's record-completeness fields, so the four scout open-sites +
 * {@link ReplayPlaybackServer} can no longer drift (the same hand-mirrored-block drift hazard
 * a review flagged for the 64KB gate-method copies — every site previously hand-listed the four
 * codec schema versions + {@code git.head}).
 *
 * <p>This closes the two biggest gaps the verifiable-matches gap table named: the
 * <b>headline MISSING gap #4 (RNG seed never recorded)</b> and the PARTIAL→mostly-MISSING gap #6
 * (version + mod identity). Every field is resolved through {@link ServerConfig} (sysprop &gt; env &gt;
 * {@code cc-server.properties} &gt; default) so a launcher that already passes the value (e.g. the watch
 * launcher's {@code -Dcc.modset.hash} / {@code -Dcc.git.head}) is recorded verbatim, and a launcher that
 * does not yet supply one records the honest default ({@code unpinned} / {@code unasserted} /
 * {@code unknown}) rather than silently omitting the key. Stamping a value here adopts NO behaviour: it
 * only records a value the launcher already knows, so the historical {@code .ccwr} records are
 * re-sim-ready WHEN the product RNG pin lands (R7 step 2, gated on v2).
 *
 * <p>The {@code .ccwr} header carries an arbitrary {@code Map<String,String>} and the verifier iterates
 * it generically ignoring unknown keys ({@link WireRecordSink#verify}), so adding these keys is fully
 * back-compatible — no format-version bump, older readers are unaffected.
 *
 * <p>The descriptor↔record binding ({@code descriptor.id}/{@code descriptor.md5}/
 * {@code playedMatchId}) landed via {@link #bindMatch} (bound at the {@code serveNPeer}
 * descriptor-decode site; every other sink-opening JVM honestly records {@code unbound}).
 * <b>Still not covered</b> (deferred): the verdict's per-match record line
 * ({@code CombatOutcomeEvent} append + the timeout/draw terminal — an L1-build-adjacent
 * match-end hook).
 */
public final class MatchRecordMeta {

    /**
     * The RNG-seed knob's resolution triple + honest-default marker. Step 2 moved the
     * ONE definition to the premain-visible {@link cc.starsector.server.transform.RngPinKnobs}
     * (the premain must resolve the seed before any runtime class is reachable); these stay as
     * aliases so the record side, {@code CombatRngPinBoot} (drive side), and the premain all read
     * the same constants — a one-sided key rename is structurally inexpressible.
     */
    public static final String RNG_SEED_SYSPROP = cc.starsector.server.transform.RngPinKnobs.SEED_SYSPROP;
    public static final String RNG_SEED_ENV = cc.starsector.server.transform.RngPinKnobs.SEED_ENV;
    public static final String RNG_SEED_FILE_KEY = cc.starsector.server.transform.RngPinKnobs.SEED_FILE_KEY;
    public static final String RNG_SEED_UNPINNED = cc.starsector.server.transform.RngPinKnobs.UNPINNED;

    /**
     * The MagicLib seam's receipt as the premain publishes it: absent = no decision (recorded as
     * {@link #MAGIC_SEAM_UNSET}); {@code off} (the host row false); {@link #MAGIC_SEAM_ARMED} (the row
     * true, MagicSubsystem not defined yet); {@link #MAGIC_SEAM_APPLIED_PREFIX} + the window sha256 (16
     * lower hex) once the patch applied; {@code disarmed:<reason>} when it left MagicLib unchanged. Only
     * the applied state changes sim outcomes, so the recording stamps it and the re-simulation gate
     * ({@link ReplayBoot}) compares it. Aliased to the registry's one home
     * in the RNG_SEED_* shape above. {@code unset} is THIS stamp's rendering of an absent receipt, not a premain value.
     */
    public static final String MAGIC_SEAM_SYSPROP = cc.starsector.server.transform.TransformRegistry.MAGIC_SEAM_RECEIPT_SYSPROP;
    public static final String MAGIC_SEAM_UNSET = "unset";
    public static final String MAGIC_SEAM_ARMED = cc.starsector.server.transform.TransformRegistry.MAGIC_SEAM_RECEIPT_ARMED;
    public static final String MAGIC_SEAM_APPLIED_PREFIX =
            cc.starsector.server.transform.TransformRegistry.MAGIC_SEAM_RECEIPT_APPLIED_PREFIX;

    /**
     * The player-ship receipt as the premain (and, on a step-guard leak, the window holder) publishes it:
     * absent = no premain decided (recorded as {@link #PILOT_SHIP_UNSET}); {@code off}; {@code armed} (ON, no
     * class rewritten yet); {@code applied:<k>}; {@code refused:<reason>}; {@code latched:<reason>}. Only an
     * applied or latched receipt means the ship-advance window ran, so the recording stamps the receipt and the
     * re-simulation gate ({@link ReplayBoot}) compares its class. {@code unset} is THIS stamp's rendering of an
     * absent receipt, not a premain value.
     */
    public static final String PILOT_SHIP_SYSPROP = cc.starsector.server.transform.TransformRegistry.PILOT_SHIP_RECEIPT_SYSPROP;
    public static final String PILOT_SHIP_UNSET = "unset";

    /**
     * playedMatchId middle component: the JVM boot second — two serves of the same
     * descriptor in different boots mint distinct ids with no cross-boot persistence.
     */
    private static final long BOOT_EPOCH_SEC = System.currentTimeMillis() / 1000L;
    /** playedMatchId tail component: monotonic per-JVM serve counter. */
    private static final AtomicInteger MATCH_INDEX = new AtomicInteger();
    private static volatile String boundDescriptorId;
    private static volatile String boundDescriptorMd5;
    private static volatile String boundPlayedMatchId;

    private MatchRecordMeta() {
    }

    /**
     * Descriptor↔record binding + the playedMatchId mint:
     * bind the served match's descriptor identity for every subsequently-opened sink —
     * call BEFORE the sink opens ({@code serveNPeer} does, at its descriptor-decode site). Mints
     * {@code playedMatchId = <descriptorId>-<bootEpochSec>-<matchIndex>} (index monotonic per
     * JVM, so re-serves of one descriptor in one boot stay distinguishable).
     */
    public static void bindMatch(String descriptorId, String descriptorMd5) {
        boundDescriptorId = descriptorId;
        boundDescriptorMd5 = descriptorMd5;
        boundPlayedMatchId =
                descriptorId + "-" + BOOT_EPOCH_SEC + "-" + MATCH_INDEX.incrementAndGet();
    }

    private static volatile String boundShape;

    /**
     * Bind the served match's shape for every subsequently-opened serve record: its header's
     * {@code match.pvp} / {@code wave.pausedAtStart} are this build's predicates over it (null =
     * no serve bound one: both false). The serve's resolve calls it before the sink opens.
     *
     */
    public static void bindShape(String shapeOrNull) {
        boundShape = shapeOrNull;
    }

    private static String orUnbound(String bound) {
        return bound == null ? "unbound" : bound;
    }

    /**
     * The record-completeness base meta: the four payload-codec schema versions (the formerly
     * hand-copied block) plus the re-sim identity fields. Returned as a fresh mutable
     * {@link LinkedHashMap} so a caller can {@code putAll}/{@code put} its site-specific keys
     * ({@code mode}, {@code endpoint}, ...) around it; key iteration order is the .ccwr write order.
     */
    public static LinkedHashMap<String, String> base() {
        LinkedHashMap<String, String> meta = new LinkedHashMap<>();
        // The four payload-frame schema versions (each frame still carries its own magic+version;
        // this is the at-a-glance provenance the WATCH-forensics + regression-fixture consumers read).
        // awss = the TOP negotiable tier (ex-V5 fossil): writeFrame
        // negotiates V1..V9 per frame by top non-empty tier, so the honest single stamp is the
        // ceiling; awss.schema.negotiated points readers at the in-artifact truth.
        meta.put("awss.schema", Integer.toString(AuthoritativeWorldSnapshotCodec.SCHEMA_VERSION_MAX));
        meta.put("awss.schema.negotiated", "per-frame");
        meta.put("gpee.schema", Integer.toString(GameplayEdgeEventCodec.SCHEMA_VERSION));
        meta.put("pint.schema", Integer.toString(PilotIntentWireCodec.SCHEMA_VERSION));
        meta.put("pwvw.schema", Integer.toString(PilotWorldViewWireCodec.SCHEMA_VERSION));
        // Re-sim identity. Defaults are honest "not supplied" markers, never omitted.
        meta.put("git.head", ServerConfig.resolve("cc.git.head", "CC_GIT_HEAD", "git.head", "unknown"));
        meta.put("rng.seed", ServerConfig.resolve(RNG_SEED_SYSPROP, RNG_SEED_ENV, RNG_SEED_FILE_KEY, RNG_SEED_UNPINNED));
        // rng.keyed records the ACTUAL armed state of THIS JVM's HOOK-0044
        // Random.<init> rewrite (two-phase confirmed), NOT the request knob — a sink opened in a
        // scout/replay/contract JVM honestly records false, and a config-set-but-never-armed run
        // cannot record a lying true. CombatRngPinBoot separately fail-fasts request!=armed.
        meta.put("rng.keyed", Boolean.toString(cc.starsector.server.rngpin.CcRngKeyed.isArmed()));
        meta.put("modset.hash", ServerConfig.resolve("cc.modset.hash", "CC_MODSET_HASH", "modset.hash", "unset"));
        meta.put("content.fingerprint",
                ServerConfig.resolve("cc.content.fingerprint", "CC_CONTENT_FINGERPRINT", "content.fingerprint", "unasserted"));
        meta.put("engine.build", ServerConfig.resolve("cc.engine.build", "CC_ENGINE_BUILD", "engine.build", "unknown"));
        // Match identity: bound once per served match via bindMatch; every other
        // sink-opening JVM (replay/verdict/scout/contract) honestly records unbound — those
        // lanes load no descriptor, and an honest marker beats a silent omission.
        meta.put("descriptor.id", orUnbound(boundDescriptorId));
        meta.put("descriptor.md5", orUnbound(boundDescriptorMd5));
        meta.put("playedMatchId", orUnbound(boundPlayedMatchId));
        // knob.* block: the effective serve-shaping knob values as RAW resolved strings,
        // re-resolved via each consumer's EXACT tuple (check-knobs' name-keyed alias collapse
        // machine-checks the tuples — a mistyped pair surfaces as a WARN). Recording adopts NO
        // behaviour: values stamp raw-honest even where the consumer's strict parse would THROW
        // on the same string (that JVM boots RED anyway; the record tells the truth about why).
        meta.put("knob.tickRate", Integer.toString(SimTickRate.ticksPerSecond()));
        meta.put("knob.fireLane", ServerConfig.resolve(
                "cc.server.fireLane", "CC_SERVER_FIRE_LANE", "server.fireLane", "held"));
        // E3 hardwires: fireEdgeLatch/fireClassRateMax/snapChunking/
        // snapFragmentation stamps stripped WITH their compiled consumers (ADJ-C: a kept stamp
        // is a live read-site the census counts; strip rides the same commit as the hardwire).
        meta.put("knob.fireClassRouting", ServerConfig.resolve(
                "cc.server.fireClassRouting", "CC_SERVER_FIRE_CLASS_ROUTING", "server.fireClassRouting", "on"));
        meta.put("knob.declPad", ServerConfig.resolve( //
                "cc.server.declPad", "CC_SERVER_DECL_PAD", "server.declPad", "on"));
        meta.put("knob.fireClassOverride", ServerConfig.resolve(
                "cc.server.fireClassOverride", "CC_SERVER_FIRE_CLASS_OVERRIDE", "server.fireClassOverride", ""));
        meta.put("knob.respawnPolicy", ServerConfig.resolve(
                "cc.match.respawnPolicy", "CC_MATCH_RESPAWN_POLICY", "match.respawnPolicy", "disabled"));
        meta.put("knob.transport", ServerConfig.resolve(
                "cc.net.transport", "CC_NET_TRANSPORT", "net.transport", ""));
        meta.put("knob.sendMode", ServerConfig.resolve(
                "cc.net.sendMode", "CC_NET_SEND_MODE", "net.sendMode", ""));
        meta.put("knob.socketLiveness", ServerConfig.resolve(
                "cc.net.socketLiveness", "CC_NET_SOCKET_LIVENESS", "net.socketLiveness", "false"));
        meta.put("knob.doorFingerprint", ServerConfig.resolve(
                "cc.net.doorFingerprint", "CC_NET_DOOR_FINGERPRINT", "net.doorFingerprint", ""));
        meta.put("knob.npeer", ServerConfig.resolve(
                "cc.server.npeer", "CC_SERVER_NPEER", "server.npeer", ""));
        meta.put("knob.eventReplay", ServerConfig.resolve(
                "cc.predict.eventReplay", "CC_PREDICT_EVENT_REPLAY", "predict.eventReplay", "false"));
        meta.put("knob.correctivesOff", ServerConfig.resolve(
                "cc.predict.correctivesOff", "CC_PREDICT_CORRECTIVES_OFF", "predict.correctivesOff", "false"));
        meta.put("knob.mapHalf", ServerConfig.resolve(
                "cc.predict.mapHalf", "CC_PREDICT_MAP_HALF", "map.half", Float.toString(9000f)));
        meta.put("knob.flyinBurnSec", ServerConfig.resolve(
                "cc.match.flyinBurnSec", "CC_MATCH_FLYIN_BURN_SEC", "match.flyinBurnSec",
                Float.toString(4.0f)));
        meta.put("knob.onFireTruth", ServerConfig.resolve(
                "cc.server.onFireTruth", "CC_SERVER_ONFIRE_TRUTH", "server.onFireTruth", "true"));
        meta.put("knob.onFireShadow", ServerConfig.resolve(
                "cc.server.onFireShadow", "CC_SERVER_ONFIRE_SHADOW", "server.onFireShadow", "true"));
        meta.put("knob.hulkPoseEveryTicks", ServerConfig.resolve(
                "cc.server.hulkPoseEveryTicks", "CC_SERVER_HULK_POSE_EVERY_TICKS", "hulk.poseEveryTicks",
                Integer.toString(Math.round(SimTickRate.ticksPerSecond() / 2f))));
        meta.put("knob.snapPayloadCap", ServerConfig.resolve(
                "cc.net.snapPayloadCap", "CC_NET_SNAP_PAYLOAD_CAP", "net.snapPayloadCap",
                Integer.toString(DatagramFrameCodec.MAX_PAYLOAD_BYTES)));
        meta.put("knob.snapDeflate", ServerConfig.resolve(
                "cc.net.snapDeflate", "CC_NET_SNAP_DEFLATE", "net.snapDeflate", "true"));
        meta.put("knob.snapDelta", ServerConfig.resolve(
                "cc.net.snapDelta", "CC_NET_SNAP_DELTA", "net.snapDelta", "false"));
        meta.put("knob.snapKeyframeTicks", ServerConfig.resolve(
                "cc.net.snapKeyframeTicks", "CC_NET_SNAP_KEYFRAME_TICKS", "net.snapKeyframeTicks", "15"));
        meta.put("knob.downEveryTicks", ServerConfig.resolve(
                "cc.net.downEveryTicks", "CC_NET_DOWN_EVERY_TICKS", "net.downEveryTicks", "1"));
        // Basename only: the full path is machine-local noise; the file NAME is the operator's
        // handle on which match descriptor shaped the serve (kit-baked match.txt vs a rig mint).
        String descriptorPath = ServerConfig.resolvePath(
                "cc.match.descriptor", "CC_MATCH_DESCRIPTOR", "match.descriptor", "");
        int cut = Math.max(descriptorPath.lastIndexOf('/'), descriptorPath.lastIndexOf('\\'));
        meta.put("knob.descriptor", cut < 0 ? descriptorPath : descriptorPath.substring(cut + 1));
        return meta;
    }

    /**
     * {@link #base} with the per-endpoint keys put FIRST (so the familiar {@code mode}/{@code endpoint}
     * lead the header), then the record-completeness fields. {@code endpoint} is omitted when null.
     */
    public static LinkedHashMap<String, String> forEndpoint(String mode, String endpoint) {
        LinkedHashMap<String, String> meta = new LinkedHashMap<>();
        meta.put("mode", mode == null ? "" : mode);
        if (endpoint != null) {
            meta.put("endpoint", endpoint);
        }
        meta.putAll(base());
        return meta;
    }

    /**
     * The serve-only inputs of {@link #forNpeerRecord}: values the serve resolved that no static
     * here can read back. {@code waveShape} = the schedule's knob set the caller formats
     * ({@code quality=…;smods=…;dmods=…;qpw=…}, "" without a schedule); {@code tickHz} > 0;
     * {@code seatCount} = the descriptor's seats (0..255).
     */
    public record RecordContext(long waveSeedEffective, boolean waveEnabled, String waveShape, int asteroidSeed,
            boolean engineBornGrowth, int seatCount, int tickHz) {
    }

    /**
     * The row kinds a serve recording may emit (the END trailer included), ascending. 14 = TRANSFER
     * ({@link MatchRecordRows#KIND_TRANSFER}): recorded since the chat-command transfer rows landed, but
     * omitted from this census until a reel's header was checked against its row tally ({@code kind 14=2}
     * in the rows, absent from the header); the re-sim never injects it ({@link ReplayBoot#REQUIRED_KINDS}).
     */
    public static final String NPEER_RECORD_KINDS = "3,4,5,6,7,8,10,11,12,14,17";

    /**
     * The N-peer serve recording's header: {@link #forEndpoint forEndpoint("npeer_serve", "1")} plus
     * the re-sim block, every key in this fixed order — the container + row versions and the kinds
     * the writer may emit; the VSUB body stamp ({@code record.vsub.body}) and the installed id tables'
     * {@code idtables.vtab} ("unset" when none is installed); the per-round RNG re-arm (scheme "0" when
     * unarmed, armed, count at open);
     * the premain's postured pin decision ({@code rng.pin.enabled}, {@code rng.seed.source}: "false" /
     * "none" on a file-less boot); the four transform hooks' state from the premain's in-process receipts (the keyed-Random
     * pin's armed state is the two-phase-confirmed {@code CcRngKeyed.isArmed()}, its matched code
     * hash the gate-verified sha; the two call-site redirects ({@code hook.0049.*} / {@code hook.0050.*})
     * and the threat-map insertion-order swap ({@code hook.0051.*}) are "armed" when the premain armed
     * them and "applied" once their target class was rewritten at load — a JVM without the premain
     * records "false" / "unset", never a guess); the MagicLib seam receipt ({@code magic.seam}, "unset"
     * without one, and {@code magic.seam.sha}, "none" unless applied); the player-ship receipt
     * ({@code pilot.ship}, "unset" without one); the warm-up pass count (-1 never ran, 0 OFF: round 0 then
     * fields the pre-arm roster) and its tick total {@code warmup.ticks} (same -1 / 0 markers; a
     * re-simulation asserts its forced warm-up ran as many); the serve's wave / asteroid /
     * born-growth inputs from {@code ctx}; this build's PvP predicates over the shape
     * {@link #bindShape} bound ({@code match.pvp}, {@code wave.pausedAtStart}); the JVM build;
     * and the hash of the BEHAVIOR knob set
     * ({@link PostureRegistry#behaviorHash}). The {@code cc.wave.*} knobs sit outside that hash (no
     * BEHAVIOR row; the seed's row is INSTRUMENT-class), so the explicit {@code wave.*} keys carry
     * them. {@link #base} is UNCHANGED: the verdict record and the replay re-speak never carry this
     * block.
     */
    public static LinkedHashMap<String, String> forNpeerRecord(RecordContext ctx) {
        java.util.Objects.requireNonNull(ctx, "ctx");
        LinkedHashMap<String, String> meta = forEndpoint("npeer_serve", "1");
        meta.put("format.version", Integer.toString(WireRecordSink.FORMAT_VERSION));
        meta.put("record.rows.version", "1");
        meta.put("record.kinds", NPEER_RECORD_KINDS);
        // The capability stamp (every VSUB row this recorder writes
        // carries the submit frame + its author key, so a re-simulation may re-admit it) and the dictionary
        // that frame is indexed by (the re-simulation refuses a different installed table after its install).
        meta.put("record.vsub.body", "1");
        IdTables tables = IdTables.installedOrNull();
        meta.put("idtables.vtab", tables == null ? "unset" : tables.vtab());
        boolean rearmArmed = CombatRngRearm.isArmed();
        meta.put("rng.rearm.scheme", rearmArmed ? Integer.toString(CombatRngRearm.schemeId()) : "0");
        meta.put("rng.rearm.armed", Boolean.toString(rearmArmed));
        meta.put("rng.rearm.count", Long.toString(CombatRngRearm.rearmCount()));
        // The premain's postured pin decision, as its receipt
        // "<on|off>,<from>,<seedFrom>" states it; absent (a file-less boot) = not pinned, no source.
        // rng.seed.source differs record vs re-sim BY DESIGN (a minted seed is re-supplied explicitly).
        String pinReceipt = System.getProperty("cc.transform.rngpin", "");
        String[] pinParts = pinReceipt.split(",", -1);
        meta.put("rng.pin.enabled", Boolean.toString(pinParts[0].equals("on")));
        meta.put("rng.seed.source", pinParts.length >= 3 && !pinParts[2].isBlank() ? pinParts[2] : "none");
        meta.put("hook.0044.armed", Boolean.toString(cc.starsector.server.rngpin.CcRngKeyed.isArmed()));
        meta.put("hook.0044.sha", System.getProperty("cc.transform.sha.HOOK-0044", "unset"));
        String armed = System.getProperty("cc.transform.armed", "");
        meta.put("hook.0049.armed", Boolean.toString(listsId(armed, "HOOK-0049")));
        meta.put("hook.0050.armed", Boolean.toString(listsId(armed, "HOOK-0050")));
        meta.put("hook.0051.armed", Boolean.toString(listsId(armed, "HOOK-0051")));
        meta.put("hook.0049.applied", Boolean.toString(System.getProperty("cc.transform.applied.HOOK-0049") != null));
        meta.put("hook.0050.applied", Boolean.toString(System.getProperty("cc.transform.applied.HOOK-0050") != null));
        meta.put("hook.0051.applied", Boolean.toString(System.getProperty("cc.transform.applied.HOOK-0051") != null));
        // The MagicLib seam receipt as read at the sink open (after
        // the boot's define, so an applying serve reads applied:<sha>), and its window sha alone.
        String seam = System.getProperty(MAGIC_SEAM_SYSPROP);
        meta.put("magic.seam", seam == null ? MAGIC_SEAM_UNSET : seam);
        meta.put("magic.seam.sha", seam != null && seam.startsWith(MAGIC_SEAM_APPLIED_PREFIX)
                ? seam.substring(MAGIC_SEAM_APPLIED_PREFIX.length()) : "none");
        // The player-ship receipt as read at the sink open (after the
        // boot's defines, so an ON serve reads applied:<k>; a leak latched before the open reads latched:leak).
        String pilotShip = System.getProperty(PILOT_SHIP_SYSPROP);
        meta.put("pilot.ship", pilotShip == null ? PILOT_SHIP_UNSET : pilotShip);
        meta.put("warmup.passes", Integer.toString(ServeWarmupRound.lastPassesOrNeg()));
        meta.put("warmup.ticks", Integer.toString(ServeWarmupRound.lastTicksOrNeg()));
        meta.put("wave.seed.effective", Long.toString(ctx.waveSeedEffective()));
        meta.put("wave.enabled", Boolean.toString(ctx.waveEnabled()));
        meta.put("wave.shape", ctx.waveShape() == null ? "" : ctx.waveShape());
        meta.put("asteroid.seed", Integer.toString(ctx.asteroidSeed()));
        meta.put("engine.bornGrowth", Boolean.toString(ctx.engineBornGrowth()));
        meta.put("seat.count", Integer.toString(ctx.seatCount()));
        meta.put("tick.hz", Integer.toString(ctx.tickHz()));
        // this build's PvP predicates over the bound shape.
        // The descriptor digest and the behavior hash cannot see a code change over the same bytes,
        // so a re-simulation compares these against ITS build's predicates and refuses a mismatch.
        String shape = boundShape;
        meta.put("match.pvp", Boolean.toString(cc.starsector.server.match.MatchDescriptors.isPvpShape(shape)));
        meta.put("wave.pausedAtStart", Boolean.toString(cc.starsector.server.match.WaveSchedule.startsPaused(shape)));
        meta.put("java.runtime.version", System.getProperty("java.runtime.version", "unset"));
        meta.put("behavior.hash", PostureRegistry.behaviorHash());
        return meta;
    }

    /** True when the comma-joined id list names {@code id} exactly. */
    private static boolean listsId(String commaList, String id) {
        for (String part : commaList.split(",")) {
            if (part.trim().equals(id)) {
                return true;
            }
        }
        return false;
    }
}
