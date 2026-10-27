package cc.starsector.server.runtime;

import cc.starsector.server.platform.ActivationGate;
import cc.starsector.server.rngpin.CcRngKeyed;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The re-simulation BOOT gate: when {@value #SYSPROP} names a recorded serve reel, this JVM boots as
 * that reel's re-simulation, and everything that would make the re-run a different match is refused
 * BEFORE the first socket and the ~50 s engine boot. The gate reads the reel's header (the META the
 * recorder wrote) and ONE payload-skipping pre-scan to the END trailer (its pairs + a per-kind record
 * census), compares them with this JVM's own facts ({@link JvmFacts}, read from the very sources the
 * recorder's META writer reads), and either refuses or forces the three inputs a re-run must not draw
 * afresh.
 *
 * <p>Boot order (the serve main): the posture enforcement and the key gate first, then
 * {@link #openOrNull} (a blank knob returns null and touches NOTHING — the normal serve boot), the
 * WARN lines, {@link #throwIfRefused}, {@link #applyForces}, {@link #receiptLine}; then the bind
 * rehearsal (which now resolves the forced loopback host + ephemeral port), the engine boot and the
 * warm-up run with the recorded pass count FORCED ({@link #warmupPasses},
 * {@link #FORCED_QUIET_COMPILE_MILLIS}, {@link #FORCED_WALL_CAP_MILLIS}), whose outcome
 * {@link #warmupMismatchOrNull} asserts as a SETUP line (the run continues: a JIT-timing-driven pass
 * count is a rig fault, never a sim divergence). The forced passes re-run the recording's passes
 * one for one, so any id the warm-up minted and the served round re-minted aliases identically in
 * the re-run; the compile-quiesce wait between passes still rides the wall clock, which moves no
 * sim state.
 *
 * <p>Every refusal is ONE stdout line {@code replay-boot REFUSED key=<k> reel=<v> jvm=<v>} and the
 * boot exits {@link #EXIT_REFUSED}; a check whose JVM side is a requirement rather than a JVM value
 * spells the requirement ({@code min:2}, {@code superset:<kinds>}, {@code readable}, {@code 0}). Every
 * reel value (and every JVM value, an operator path among them) passes ONE sanitizer before it
 * reaches stdout: at most {@value #VALUE_CAP} chars of {@code [A-Za-z0-9._:,;=+-]}, any other char
 * replaced by {@code ?}, a longer value cut and suffixed {@code +len=<n>} — a reel is an operator-
 * supplied file, and no byte of it reaches a log raw from these checks (a replayed command's text
 * reaches the serve's rows only after the live grammar re-built it, {@link ReplayFeed#commandsAt}).
 *
 * <p>The refusal table, in check order: (1) {@code header} — the cursor refuses the header or the
 * pre-scan throws (a refused record length, a malformed END body): ONE refusal, nothing else
 * checked; (2) {@code format.version} below 2 in the header or the META (a version-1 reel holds no
 * rows); (3) {@code record.rows.version} not 1; (4) {@code record.kinds} missing a
 * {@link #REQUIRED_KINDS} member; (5) {@code modset.hash} / {@code content.fingerprint} /
 * {@code engine.build} unequal — EQUALITY only: both sides at the unasserted default pass with one
 * {@code replay-boot WARN key=<k> both=<v>} line each, because today's rig launchers assert none of
 * the three; (6) {@code behavior.hash}; (7) {@code descriptor.md5} (a reel {@code unbound} /
 * {@code unreadable} refuses even when equal: nothing pins the match then); (8) {@code rng.seed}
 * (asserted, never forced — the launcher passes the same seed to the premain); (9)
 * {@code rng.keyed} and {@code hook.0044.armed} against the keyed pin's armed state,
 * {@code hook.0044.sha}, {@code hook.0049.armed} / {@code hook.0050.armed} / {@code hook.0051.armed}
 * against the premain's armed list; (10) {@code rng.rearm.armed} not true, {@code rng.rearm.scheme} not
 * {@link CombatRngRearm#SCHEME_ID}; (11) {@code warmup.passes} not an int of at least 1 (0 = the
 * warm-up was OFF and round 0 fielded the roster deployed before the re-arm; -1 = no warm-up ran);
 * (12) {@code java.runtime.version} (the JDK internals the re-arm reseeds); (13) {@code tick.hz}
 * against this JVM's EFFECTIVE rate; (14) the END {@code record.row.faults} present and not 0 (the
 * recorder dropped rows); (15) {@code kind.8} — a variant-submit row recorded WITHOUT the body tail
 * (META {@code record.vsub.body} absent: the row holds no submitted body nor its author key, so the
 * re-run cannot re-admit it); a reel stamped {@code record.vsub.body=1} is fed to the custom lane,
 * and its id-tables belt runs after the tables install ({@link #vtabRefusalOrNull}); (16)
 * {@code cc.net.sinkPath} normalizing to the reel itself (the re-run's own recording would truncate its input); (17)
 * {@code wave.seed.effective} absent or not a long (the force below would have no source); (18)
 * {@code match.pvp} / {@code wave.pausedAtStart} unequal to THIS build's predicates over the served
 * descriptor's shape (an absent key reads false: a reel older than the keys).
 *
 * <p>WARN only ({@code replay-boot WARN key=<k> reel=<v> jvm=<v>}): {@code git.head} unequal (a
 * different build may still replay equal; the differ grades the result); {@code end.reason}
 * {@code absent} or {@code capped} (the recorded prefix replays); {@code warmup.ticks} absent (an
 * older reel: the post-warm-up tick assert is skipped).
 *
 * <p>NOT checked here: {@code hook.0049.applied} / {@code hook.0050.applied} /
 * {@code hook.0051.applied} — the premain marks
 * them when the target class is rewritten at load, DURING the engine boot, so a pre-boot read is
 * always false; {@link #appliedWarningsNow} compares them after the warm-up, WARN only (the
 * recorder's META snapshots them later still, at the sink open). {@code wave.enabled},
 * {@code wave.shape}, {@code seat.count}, {@code asteroid.seed}, {@code engine.bornGrowth} —
 * implied by {@code behavior.hash} / {@code descriptor.md5}, and paired again by the re-sim differ.
 * The variant catalog poller — never refused (it is not installed in a re-run; a recording that
 * promoted mid-flight diverges there and the differ names it).
 *
 * <p>The forces ({@link #applyForces}, exactly these sysprops, in this order): {@code cc.wave.seed}
 * = META {@code wave.seed.effective} (a blank seed would draw afresh); {@code cc.server.bindHost} =
 * {@value #FORCED_BIND_HOST}; {@code cc.server.bindPort} = {@value #FORCED_BIND_PORT} (a re-run
 * serves no remote peer; the port file still carries the ephemeral port). NOTHING premain-side
 * ({@code cc.rngpin.*}, {@code cc.transform.*}) is forced: the premain armed before {@code main},
 * so those are asserted only.
 */
public final class ReplayBoot {

    /** The knob naming the reel to re-simulate (blank = a normal serve boot). */
    public static final String SYSPROP = "cc.resim.record";
    public static final String ENV = "CC_RESIM_RECORD";
    public static final String FILE_KEY = "resim.record";

    /** Exit code of a refused re-sim boot: the key gate's FINAL code (a supervisor never relaunches it). */
    public static final int EXIT_REFUSED = ActivationGate.EXIT_REFUSED;

    /**
     * The warm-up's quiet threshold in a re-run: a pass's compile delta is never negative, so
     * {@code delta <= -1} never holds and the adaptive stop never fires — every recorded pass runs.
     */
    public static final long FORCED_QUIET_COMPILE_MILLIS = -1L;

    /**
     * The warm-up's per-pass wall cap in a re-run: unbounded in effect. The warm-up multiplies the
     * cap by 1e6 (to nanos); {@code Long.MAX_VALUE} itself would overflow negative and end every pass
     * at tick 0.
     */
    public static final long FORCED_WALL_CAP_MILLIS = Long.MAX_VALUE / 1_000_000L;

    /**
     * The kinds the re-sim injects or asserts (TAKES, SESS, CMD, FLYIN, SUIC, FSPN, WRAP, ROSTER, END).
     * Kind 14 (TRANSFER) is RECORDED, never injected: the header's {@code record.kinds} census lists it
     * ({@link MatchRecordMeta#NPEER_RECORD_KINDS}), this list does not.
     */
    public static final List<Integer> REQUIRED_KINDS = List.of(3, 4, 5, 6, 7, 10, 11, 12, 17);

    /** The forced sysprops, in force order (the receipt line's {@code forced=} list). */
    public static final List<String> FORCED_KEYS = List.of("cc.wave.seed", "cc.server.bindHost", "cc.server.bindPort");

    public static final String FORCED_BIND_HOST = "127.0.0.1";
    public static final String FORCED_BIND_PORT = "0";

    /** The sanitizer's per-value cap (chars kept before the {@code +len=<n>} suffix). */
    public static final int VALUE_CAP = 64;

    private static final String LINE = "replay-boot ";
    private static final String ABSENT = "absent";
    private static final int KIND_VSUB = 8;

    /**
     * This JVM's side of every comparison, read from the SAME sources the recorder's META writer
     * reads (the {@link MatchRecordMeta} base block and its serve-record block), so an unchanged
     * posture compares equal by construction.
     */
    public record JvmFacts(String behaviorHash, String descriptorMd5, String rngSeed, boolean rngKeyed,
            String transformArmed, String hook0044Sha, String javaRuntimeVersion, String modsetHash,
            String contentFingerprint, String engineBuild, String gitHead, int tickHz,
            String sinkPathOrEmpty, String descriptorShapeOrNull) {

        /**
         * The facts without a descriptor shape (null): this build's PvP predicates then read false,
         * the value every pre-PvP reel implies.
         */
        public JvmFacts(String behaviorHash, String descriptorMd5, String rngSeed, boolean rngKeyed,
                String transformArmed, String hook0044Sha, String javaRuntimeVersion, String modsetHash,
                String contentFingerprint, String engineBuild, String gitHead, int tickHz,
                String sinkPathOrEmpty) {
            this(behaviorHash, descriptorMd5, rngSeed, rngKeyed, transformArmed, hook0044Sha,
                    javaRuntimeVersion, modsetHash, contentFingerprint, engineBuild, gitHead, tickHz,
                    sinkPathOrEmpty, null);
        }

        /**
         * The facts as this JVM resolves them now — valid before the engine boot: the knob reads are
         * pure, and the keyed pin's armed state is premain-confirmed before {@code main}.
         * {@code tickHz} is the EFFECTIVE rate {@code round(1 / fixedDt)} the serve will step at.
         * The descriptor digest is the serve's own bind recipe, the one shared method (the md5 of
         * the raw descriptor file bytes; {@code unreadable} when the file cannot be read).
         */
        public static JvmFacts current(float fixedDt) {
            return new JvmFacts(
                    PostureRegistry.behaviorHash(),
                    PersistentPilotIntentCombatServer.launchedDescriptorMd5(),
                    ServerConfig.resolve(MatchRecordMeta.RNG_SEED_SYSPROP, MatchRecordMeta.RNG_SEED_ENV,
                            MatchRecordMeta.RNG_SEED_FILE_KEY, MatchRecordMeta.RNG_SEED_UNPINNED),
                    CcRngKeyed.isArmed(),
                    System.getProperty("cc.transform.armed", ""),
                    System.getProperty("cc.transform.sha.HOOK-0044", "unset"),
                    System.getProperty("java.runtime.version", "unset"),
                    ServerConfig.resolve("cc.modset.hash", "CC_MODSET_HASH", "modset.hash", "unset"),
                    ServerConfig.resolve("cc.content.fingerprint", "CC_CONTENT_FINGERPRINT",
                            "content.fingerprint", "unasserted"),
                    ServerConfig.resolve("cc.engine.build", "CC_ENGINE_BUILD", "engine.build", "unknown"),
                    ServerConfig.resolve("cc.git.head", "CC_GIT_HEAD", "git.head", "unknown"),
                    (int) Math.round(1.0 / fixedDt),
                    ServerConfig.resolvePath("cc.net.sinkPath", "CC_NET_SINK_PATH", "net.sinkPath", ""),
                    launchedDescriptorShapeOrNull());
        }

        /**
         * The served descriptor's shape, read the way the serve reads it (the same path knob, the
         * capped read, the codec); null when the file cannot be read or decoded — the descriptor
         * digest check refuses that reel on its own, and the serve's own decode fails the boot.
         *
         */
        private static String launchedDescriptorShapeOrNull() {
            String path = ServerConfig.resolvePath("cc.match.descriptor", "CC_MATCH_DESCRIPTOR",
                    "match.descriptor", "");
            if (path == null || path.isBlank()) {
                return null;
            }
            try {
                return cc.starsector.server.match.MatchDescriptorCodec.decode(
                        cc.starsector.server.ops.ServerOpsCore.readCappedLines(Path.of(path.trim())))
                        .shape();
            } catch (IOException | RuntimeException unreadable) {
                // The predicate refusal line (and the digest's) is this miss's one log row.
                return null;
            }
        }
    }

    /** A refused re-sim boot; {@link #lines} = one pinned REFUSED line per refusal, in check order. */
    public static final class ReplayRefusedException extends RuntimeException {
        private final transient List<String> lines;

        ReplayRefusedException(List<String> lines) {
            super(lines.size() + " refusal(s)");
            this.lines = List.copyOf(lines);
        }

        public List<String> lines() {
            return lines;
        }
    }

    private final Path reel;
    private final JvmFacts jvm;
    private final CcwrPullCursor.Header header;
    private final LinkedHashMap<String, String> end;
    private final int[] kindCounts;
    private final List<String> refusals = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private ReplayBoot(Path reel, JvmFacts jvm, CcwrPullCursor.Header header,
            LinkedHashMap<String, String> end, int[] kindCounts) {
        this.reel = reel;
        this.jvm = jvm;
        this.header = header;
        this.end = end;
        this.kindCounts = kindCounts;
    }

    /**
     * The serve boot's entry: null when {@value #SYSPROP} is blank (and NOTHING else is read or
     * set); else {@link #open(Path, JvmFacts)} over the named reel with this JVM's {@link JvmFacts}.
     */
    public static ReplayBoot openOrNull(float fixedDt) throws IOException {
        String knob = ServerConfig.resolvePath(SYSPROP, ENV, FILE_KEY, "");
        if (knob == null || knob.isBlank()) {
            return null;
        }
        return open(Path.of(knob.trim()), JvmFacts.current(fixedDt));
    }

    /**
     * The pure core (never sets a sysprop): the header via {@link CcwrPullCursor#open}, the END
     * trailer + per-kind census via ONE {@link CcwrPullCursor#scanEndOrNull}, then the refusal table
     * and the WARN lines against {@code jvm}. An unreadable reel is refusal (1), never a throw.
     */
    public static ReplayBoot open(Path reel, JvmFacts jvm) throws IOException {
        Objects.requireNonNull(reel, "reel");
        Objects.requireNonNull(jvm, "jvm");
        CcwrPullCursor.Header header;
        try (CcwrPullCursor cursor = CcwrPullCursor.open(reel)) {
            header = cursor.header();
        } catch (IOException | RuntimeException unreadable) {
            // A hostile or damaged reel: the ONE refusal line names the failure (its log row).
            return headerRefused(reel, jvm, unreadable);
        }
        int[] counts = new int[256];
        LinkedHashMap<String, String> end;
        try {
            end = CcwrPullCursor.scanEndOrNull(reel, counts);
        } catch (IOException | RuntimeException unreadable) {
            // Same posture as the header: the refusal line is the failure's one log row.
            return headerRefused(reel, jvm, unreadable);
        }
        ReplayBoot boot = new ReplayBoot(reel, jvm, header, end, counts);
        boot.evaluate();
        return boot;
    }

    private static ReplayBoot headerRefused(Path reel, JvmFacts jvm, Exception unreadable) {
        ReplayBoot boot = new ReplayBoot(reel, jvm, null, null, new int[256]);
        String why = unreadable.getMessage() == null ? "" : ":" + unreadable.getMessage();
        boot.refuse("header", unreadable.getClass().getSimpleName() + why, "readable");
        return boot;
    }

    private void evaluate() {
        // (2) the container version, header first, then the META's own stamp.
        Integer metaFormat = intOrNull(meta("format.version"));
        if (header.formatVersion() < 2) {
            refuse("format.version", Short.toString(header.formatVersion()), "min:2");
        } else if (metaFormat == null || metaFormat < 2) {
            refuse("format.version", meta("format.version"), "min:2");
        }
        // (3), (4) the row contract the re-sim reads.
        mustEqual("record.rows.version", "1");
        if (!kindsCover(meta("record.kinds"))) {
            refuse("record.kinds", meta("record.kinds"), "superset:" + joinKinds());
        }
        // (5) provenance: equality only, a WARN when both sides sit at the unasserted default.
        provenance("modset.hash", jvm.modsetHash(), "unset");
        provenance("content.fingerprint", jvm.contentFingerprint(), "unasserted");
        provenance("engine.build", jvm.engineBuild(), "unknown");
        // (6) - (9) the knob set, the match, the pinned seed, the premain's state.
        mustEqual("behavior.hash", jvm.behaviorHash());
        String md5 = meta("descriptor.md5");
        if ("unbound".equals(md5) || "unreadable".equals(md5) || !Objects.equals(md5, jvm.descriptorMd5())) {
            refuse("descriptor.md5", md5, jvm.descriptorMd5());
        }
        mustEqual("rng.seed", jvm.rngSeed());
        mustEqual("rng.keyed", Boolean.toString(jvm.rngKeyed()));
        mustEqual("hook.0044.armed", Boolean.toString(jvm.rngKeyed()));
        mustEqual("hook.0044.sha", jvm.hook0044Sha());
        mustEqual("hook.0049.armed", Boolean.toString(listsId(jvm.transformArmed(), "HOOK-0049")));
        mustEqual("hook.0050.armed", Boolean.toString(listsId(jvm.transformArmed(), "HOOK-0050")));
        mustEqual("hook.0051.armed", Boolean.toString(listsId(jvm.transformArmed(), "HOOK-0051")));
        // (10) the per-round re-arm must have run (nothing replays deterministically without it).
        mustEqual("rng.rearm.armed", "true");
        mustEqual("rng.rearm.scheme", Integer.toString(CombatRngRearm.SCHEME_ID));
        // (11) the warm-up must have run (its pass count is forced below).
        Integer passes = intOrNull(meta("warmup.passes"));
        if (passes == null || passes < 1) {
            refuse("warmup.passes", meta("warmup.passes"), "min:1");
        }
        // (12), (13) the JDK build and the effective tick rate.
        mustEqual("java.runtime.version", jvm.javaRuntimeVersion());
        mustEqual("tick.hz", Integer.toString(jvm.tickHz()));
        // (14) a recorder that faulted dropped rows: the reel cannot be trusted.
        String faults = end == null ? null : end.get("record.row.faults");
        if (faults != null && !"0".equals(faults)) {
            refuse("record.row.faults", faults, "0");
        }
        // (15) a variant-submit row recorded without its body tail cannot be re-admitted.
        if (kindCount(KIND_VSUB) > 0 && !"1".equals(meta("record.vsub.body"))) {
            refuse("kind." + KIND_VSUB, Integer.toString(kindCount(KIND_VSUB)), "0");
        }
        // (16) the re-run's own recording must never overwrite its input.
        String sink = jvm.sinkPathOrEmpty();
        if (sink != null && !sink.isBlank()) {
            Path sinkPath = null;
            try {
                sinkPath = Path.of(sink.trim()).toAbsolutePath().normalize();
            } catch (InvalidPathException unparsable) {
                // The sink open fails on its own later; said once here.
                warn("cc.net.sinkPath", reelName(), "unparsable");
            }
            if (sinkPath != null && sinkPath.equals(reel.toAbsolutePath().normalize())) {
                refuse("cc.net.sinkPath", reel.toString(), sink);
            }
        }
        // (17) the wave-seed force needs its source.
        if (longOrNull(meta("wave.seed.effective")) == null) {
            refuse("wave.seed.effective", meta("wave.seed.effective"), "long");
        }
        // (18) the shape's behaviour predicates: the digest and the behavior hash cannot see a code
        // change over the same descriptor bytes, so the recorded predicates must equal THIS build's
        // over the bound shape.
        String shape = jvm.descriptorShapeOrNull();
        predicate("match.pvp", cc.starsector.server.match.MatchDescriptors.isPvpShape(shape));
        predicate("wave.pausedAtStart", cc.starsector.server.match.WaveSchedule.startsPaused(shape));
        // WARN only: provenance of the build, a cut recording, an older reel without the tick count.
        if (!Objects.equals(meta("git.head"), jvm.gitHead())) {
            warn("git.head", meta("git.head"), jvm.gitHead());
        }
        if (end == null) {
            warn("end.reason", ABSENT, "closed");
        } else if ("capped".equals(end.get("end.reason"))) {
            warn("end.reason", "capped", "closed");
        }
        if (warmupTicksOrNeg() < 0) {
            warn("warmup.ticks", meta("warmup.ticks"), "unchecked");
        }
    }

    public Path reel() {
        return reel;
    }

    /** The reel's header, or null when refusal (1) fired. */
    public CcwrPullCursor.Header header() {
        return header;
    }

    /** The END trailer's pairs (read-only), or null when the reel holds none (or refusal (1) fired). */
    public Map<String, String> endOrNull() {
        return end == null ? null : Collections.unmodifiableMap(end);
    }

    /** Records of {@code kind} the pre-scan walked (0 outside 0..255 or after refusal (1)). */
    public int kindCount(int kind) {
        return kind < 0 || kind >= kindCounts.length ? 0 : kindCounts[kind];
    }

    public List<String> refusals() {
        return List.copyOf(refusals);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /**
     * The id-tables belt for a reel that re-admits variant submissions, evaluated AFTER this JVM installs
     * its tables (the boot checks run before that install, so this line never joins {@link #refusals}):
     * the {@code REFUSED} line when the reel holds a VSUB row and its META {@code idtables.vtab} (absent =
     * {@code unset}) differs from {@code jvmVtabOrNull} (null = {@code unset}), else null. The recorded
     * body is dictionary-indexed and the re-run re-derives its CRC under its OWN tables, so a tables
     * drift is invisible to that CRC and would mis-decode silently: this is the only belt. It is partial
     * by design: module ids sit outside the vtab digest, so a drift there passes it.
     *The caller throws a {@link ReplayRefusedException}
     * carrying the line, the one refusal exit.
     */
    public String vtabRefusalOrNull(String jvmVtabOrNull) {
        if (kindCount(KIND_VSUB) == 0) {
            return null;
        }
        String recorded = meta("idtables.vtab");
        String reelVtab = recorded == null ? "unset" : recorded;
        String jvmVtab = jvmVtabOrNull == null ? "unset" : jvmVtabOrNull;
        return reelVtab.equals(jvmVtab) ? null : refusedLine("idtables.vtab", reelVtab, jvmVtab);
    }

    public void throwIfRefused() {
        if (!refusals.isEmpty()) {
            throw new ReplayRefusedException(refusals);
        }
    }

    /**
     * Set the three forced sysprops ({@link #FORCED_KEYS}, in order) and return their keys. Called
     * after the posture enforcement and before the bind rehearsal, so every later resolve reads the
     * forced value. Throws {@link IllegalStateException} on a refused gate: nothing is forced then.
     */
    public List<String> applyForces() {
        if (!refusals.isEmpty()) {
            throw new IllegalStateException("replay boot refused (" + refusals.size()
                    + " refusal(s)): no force applies");
        }
        System.setProperty(FORCED_KEYS.get(0), Long.toString(longOrNull(meta("wave.seed.effective"))));
        System.setProperty(FORCED_KEYS.get(1), FORCED_BIND_HOST);
        System.setProperty(FORCED_KEYS.get(2), FORCED_BIND_PORT);
        return FORCED_KEYS;
    }

    public List<String> forcedKeys() {
        return FORCED_KEYS;
    }

    /** The recorded warm-up pass count (-1 when the META value is not an int; refusal (11) covers it). */
    public int warmupPasses() {
        Integer passes = intOrNull(meta("warmup.passes"));
        return passes == null ? -1 : passes;
    }

    /** The recorded warm-up tick total, or -1 when the reel predates the key (or it does not parse). */
    public long warmupTicksOrNeg() {
        Long ticks = longOrNull(meta("warmup.ticks"));
        return ticks == null || ticks < 0 ? -1L : ticks;
    }

    /**
     * The post-warm-up assert: null when this JVM's warm-up ran the recorded passes (and, when the
     * reel carries it, the recorded tick total); else ONE {@code replay-boot SETUP key=warmup.<passes|ticks>
     * reel=<v> jvm=<v>} line — the pass count first, since the tick total follows from it.
     */
    public String warmupMismatchOrNull(int passesRun, long ticksRun) {
        if (passesRun != warmupPasses()) {
            return LINE + "SETUP key=warmup.passes reel=" + warmupPasses() + " jvm=" + passesRun;
        }
        long recorded = warmupTicksOrNeg();
        if (recorded >= 0 && ticksRun != recorded) {
            return LINE + "SETUP key=warmup.ticks reel=" + recorded + " jvm=" + ticksRun;
        }
        return null;
    }

    /**
     * The two call-site redirects' and the threat-map swap's {@code applied} receipts compared NOW
     * (after the engine boot and the warm-up loaded their target classes) with the reel's; one WARN
     * line per unequal key.
     */
    public List<String> appliedWarningsNow() {
        List<String> out = new ArrayList<>(3);
        for (String id : new String[] {"0049", "0050", "0051"}) {
            String key = "hook." + id + ".applied";
            String now = Boolean.toString(System.getProperty("cc.transform.applied.HOOK-" + id) != null);
            if (!Objects.equals(meta(key), now)) {
                out.add(warnLine(key, meta(key), now));
            }
        }
        return out;
    }

    /** {@code replay-boot ok reel=<file name> forced=<k,k,k> warmup.passes=<n> warmup.ticks=<n>}. */
    public String receiptLine() {
        return LINE + "ok reel=" + sanitize(reelName())
                + " forced=" + String.join(",", FORCED_KEYS)
                + " warmup.passes=" + warmupPasses()
                + " warmup.ticks=" + warmupTicksOrNeg();
    }

    private String reelName() {
        Path name = reel.getFileName();
        return name == null ? reel.toString() : name.toString();
    }

    private String meta(String key) {
        return header == null ? null : header.meta().get(key);
    }

    private void mustEqual(String key, String jvmValue) {
        if (!Objects.equals(meta(key), jvmValue)) {
            refuse(key, meta(key), jvmValue);
        }
    }

    /** A recorded predicate against this build's: an absent key is a reel older than the key, whose
     *  build had no PvP shape and paused nothing at start, so it reads false. */
    private void predicate(String key, boolean jvmValue) {
        String recorded = meta(key);
        if (!Boolean.toString(jvmValue).equals(recorded == null ? "false" : recorded)) {
            refuse(key, recorded == null ? ABSENT : recorded, Boolean.toString(jvmValue));
        }
    }

    private void provenance(String key, String jvmValue, String unassertedDefault) {
        if (!Objects.equals(meta(key), jvmValue)) {
            refuse(key, meta(key), jvmValue);
        } else if (unassertedDefault.equals(jvmValue)) {
            warnings.add(LINE + "WARN key=" + key + " both=" + sanitize(jvmValue));
        }
    }

    private void refuse(String key, String reelValue, String jvmValue) {
        refusals.add(refusedLine(key, reelValue, jvmValue));
    }

    private static String refusedLine(String key, String reelValue, String jvmValue) {
        return LINE + "REFUSED key=" + key + " reel=" + sanitize(reelValue) + " jvm=" + sanitize(jvmValue);
    }

    private void warn(String key, String reelValue, String jvmValue) {
        warnings.add(warnLine(key, reelValue, jvmValue));
    }

    private static String warnLine(String key, String reelValue, String jvmValue) {
        return LINE + "WARN key=" + key + " reel=" + sanitize(reelValue) + " jvm=" + sanitize(jvmValue);
    }

    private static boolean kindsCover(String commaList) {
        if (commaList == null) {
            return false;
        }
        Set<Integer> listed = new HashSet<>();
        for (String part : commaList.split(",")) {
            Integer kind = intOrNull(part.trim());
            if (kind != null) {
                listed.add(kind);
            }
        }
        return listed.containsAll(REQUIRED_KINDS);
    }

    private static String joinKinds() {
        StringBuilder b = new StringBuilder();
        for (Integer kind : REQUIRED_KINDS) {
            b.append(b.length() == 0 ? "" : ",").append(kind);
        }
        return b.toString();
    }

    /** True when the comma-joined id list names {@code id} exactly (the META writer's own rule). */
    private static boolean listsId(String commaList, String id) {
        for (String part : commaList.split(",")) {
            if (part.trim().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static Integer intOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException notInt) {
            // Every caller turns a null into a refusal or WARN line naming the raw value: logged once.
            return null;
        }
    }

    private static Long longOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException notLong) {
            // As intOrNull: the caller's refusal or WARN line is the one log row.
            return null;
        }
    }

    /**
     * The ONE stdout sanitizer: null = {@code absent}; at most {@link #VALUE_CAP} chars kept, each
     * outside {@code [A-Za-z0-9._:,;=+-]} replaced by {@code ?}; a longer value gets {@code +len=<n>}
     * (its full length). No reel byte reaches a log line unfiltered — a newline or a space can never
     * forge a second token or a second line.
     */
    private static String sanitize(String raw) {
        if (raw == null) {
            return ABSENT;
        }
        int keep = Math.min(raw.length(), VALUE_CAP);
        StringBuilder b = new StringBuilder(keep + 16);
        for (int i = 0; i < keep; i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || "._:,;=+-".indexOf(c) >= 0;
            b.append(ok ? c : '?');
        }
        if (raw.length() > VALUE_CAP) {
            b.append("+len=").append(raw.length());
        }
        return b.toString();
    }
}
