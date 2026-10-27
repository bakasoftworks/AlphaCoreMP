package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBornGrowthSeam;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.FinaleCreepAuthor;
import cc.starsector.server.bridge.FortressServeBootChecks;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.bridge.RealEngineDemoRoster;
import cc.starsector.server.bridge.SeatRebind;
import cc.starsector.server.platform.ActivationGate;
import cc.starsector.server.runtime.PersistentPilotIntentCombatServer.NPeerHooks;
import cc.starsector.server.runtime.PersistentPilotIntentCombatServer.PilotRoundSummary;
import cc.starsector.server.runtime.PersistentPilotIntentCombatServer.PilotServeConfig;
import cc.starsector.server.runtime.PersistentPilotIntentCombatServer.PilotServeReceipt;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Standalone-process entrypoint for the PILOT-lane persistent serve loop
 * ({@link PersistentPilotIntentCombatServer}) — its CLI/launch step. The ONE
 * serve lane is {@link PersistentPilotIntentCombatServer#serveNPeer} (the multi-peer UDP door): the
 * legacy single-session TCP {@code serve()} lane was FULLY RETIRED
 * — an explicit {@code cc.server.npeer=false} THROWS readable rather than serving a
 * retired lane.
 *
 * <p>It is the Pilot-lane sibling of {@link DedicatedCombatServerMain}, kept SEPARATE so that main stays
 * thin: the pilot serve loop is structurally different (bind → admit credentialed sessions → serve
 * up to {@code roundsPerSocket} lethal rounds → return), so it has no {@code --shutdown-file} poll loop,
 * no {@code --max-sessions}, and its own {@link PilotServeConfig} record. The structural bits come from CLI
 * flags; the round/reset knobs come from {@code -D} system properties, exactly the split
 * {@code DedicatedCombatServerMain} + {@code run-real-dedicated-server.ps1} already use, so the pilot
 * launch wrapper reuses the same property conventions.
 *
 * <p>The server carries no game identity: the {@link EngineCombatBinding} is supplied by a pluggable
 * factory named with {@code --binding-factory <fqcn>} (npeer requires the GATED zero-hull
 * {@code create(SortedSet<Integer>)} overload — S9), the same injection model
 * {@code DedicatedCombatServerMain} uses. The real-engine run injects
 * {@code cc.starsector.server.bridge.RealEngineCombatBindingFactory} under the L3 substrate the launch
 * wrapper brings up.
 *
 * <pre>
 *   java ... cc.starsector.server.runtime.PilotIntentCombatServerMain \
 *       --binding-factory cc.starsector.server.bridge.RealEngineCombatBindingFactory \
 *       --frames-per-session 3600 --fixed-dt 0.0166667 \
 *       --port-file run/pilot-port.properties \
 *       [--ship-count 2 --client-wait-ms 120000 --frame-pace-ms 0 --pace-wall-clock false]
 *   # round/reset knobs (default OFF / single-round):
 *   #   -Dcc.server.matchReset=true -Dcc.server.roundsPerSocket=N -Dcc.server.retryBudgetTicks=30
 * </pre>
 *
 * <p><b>{@code emitMatchResetFrame} + {@code awaitReentry} default OFF</b> ({@link Boolean#getBoolean}).
 * {@code awaitReentry} is welded-only history (MRER died with the TCP lane): the read stays so an
 * explicit {@code -Dcc.server.awaitReentry=true} still fail-LOUDS — the {@link PilotServeConfig} ctor
 * THROWS on any non-welded sendMode with it set, and npeer is duplex-only — never a silent ignore.
 */
public final class PilotIntentCombatServerMain {

    private static final float DEFAULT_DT = SimTickRate.fixedDt();

    private PilotIntentCombatServerMain() {
    }

    public static void main(String[] args) {
        // Stamp this JVM's role BEFORE the engine boots (the mod's
        // onApplicationLoad fires inside the binding build below). The client mod's own
        // onFire attach sweep reads this and stands down, keeping the
        // projectile specs bare for OnFireAttachSweep. Without it the client emitter lands
        // on all specs first and the server wrap lane cannot resolve a mod-jar class
        // through the app loader: wrap_skipped=53 covered=0, FireBirthSink never latches,
        // and the whole chargedown-0 discharge class (foe irpulse/ioncannon) never reaches
        // the wire — the Remnant-autofire anomaly.
        System.setProperty("cc.jvm.role", "server");
        try {
            String bindingFactory = null;
            int framesPerSession = 3600;
            float fixedDt = DEFAULT_DT;
            // Fallback ship count for a fake/other binding that deployed no demo roster; the real-engine
            // path overrides this from the deploy ledger (see below). PilotServeConfig requires >= 1.
            int shipCount = 2;
            String portFile = null;
            long clientWaitMillis = 120_000L;
            long framePaceMillis = 0L;
            // Real-time "watch pacer": throttle each tick to its absolute wall-clock deadline (target derived
            // from --fixed-dt) so the rendered client's ship doesn't explode in a free-running 23-26x feed.
            // Default OFF keeps the headless measurement free-running fast; the WATCH wrapper sets it true.
            boolean paceToWallClock = false;
            // Send-mode selection: the authoritative knob is the ServerConfig
            // cascade cc.net.sendMode / CC_NET_SEND_MODE / net.sendMode = welded|decoupled|duplex (with
            // cc.net.decoupledSend as the boolean alias). The legacy --decoupled-send flag is
            // kept for the existing wrappers; a flag that CONTRADICTS a resolved sendMode throws inside
            // SendMode.resolve (the ONE resolver — null = flag not passed). Nothing set = welded, the
            // proven blocking ping-pong.
            Boolean decoupledSend = null;
            boolean printPostureOnly = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--binding-factory" -> bindingFactory = args[++i];
                    case "--frames-per-session" -> framesPerSession = Integer.parseInt(args[++i]);
                    case "--fixed-dt" -> fixedDt = Float.parseFloat(args[++i]);
                    case "--ship-count" -> shipCount = Integer.parseInt(args[++i]);
                    case "--port-file" -> portFile = args[++i];
                    case "--client-wait-ms" -> clientWaitMillis = Long.parseLong(args[++i]);
                    case "--frame-pace-ms" -> framePaceMillis = Long.parseLong(args[++i]);
                    case "--pace-wall-clock" -> paceToWallClock = Boolean.parseBoolean(args[++i]);
                    case "--decoupled-send" -> decoupledSend = Boolean.parseBoolean(args[++i]);
                    case "--print-posture" -> printPostureOnly = true;
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            // Lane-posture M1: the dump is the machine ground truth
            // of this JVM's effective posture. --print-posture = resolve-and-exit for gates (needs
            // no binding/port args); every REAL boot also dumps unconditionally below, so each
            // supervised life leaves its posture on record. Infra CLI args ride as posture-arg
            // rows because they never pass through the sysprop/env/file cascade.
            if (printPostureOnly) {
                PostureRegistry.dump(System.out);
                // M3: enforce AFTER the dump so a refusal is diagnosable from the
                // printed posture; the outer catch exits 1 with the dump already on stdout.
                PostureRegistry.enforce();
                return;
            }
            if (bindingFactory == null || portFile == null) {
                throw new IllegalArgumentException(
                        "required: --binding-factory <fqcn> --port-file <path>"
                                + " [--frames-per-session N --fixed-dt F --ship-count K"
                                + " --client-wait-ms N --frame-pace-ms N --pace-wall-clock true|false"
                                + " --decoupled-send true|false]"
                                + " [-Dcc.net.sendMode=welded|decoupled|duplex]"
                                + " [-Dcc.server.matchReset=true -Dcc.server.roundsPerSocket=N"
                                + " -Dcc.server.retryBudgetTicks=30]");
            }

            // Round/reset knobs ride the ServerConfig cascade (sysprop > env > config file > compiled)
            // since M2b S3 — sysprop-only Integer/Boolean.getInteger reads were the
            // file-tier gap the lane-posture audit named. emitMatchResetFrame + awaitReentry default OFF
            // -- the required posture against the rendered client (class-level CRITICAL note); step-5-coupled.
            int roundsPerSocket = ServerConfig.resolveInt(
                    "cc.server.roundsPerSocket", "CC_SERVER_ROUNDS_PER_SOCKET", "server.roundsPerSocket", 1);
            int retryBudgetTicks = ServerConfig.resolveInt(
                    "cc.server.retryBudgetTicks", "CC_SERVER_RETRY_BUDGET_TICKS", "server.retryBudgetTicks", 30);
            boolean emitMatchResetFrame = ServerConfig.resolveBoolean(
                    "cc.server.emitMatchResetFrame", "CC_SERVER_EMIT_MATCH_RESET_FRAME", "server.emitMatchResetFrame", false);
            boolean awaitReentry = ServerConfig.resolveBoolean(
                    "cc.server.awaitReentry", "CC_SERVER_AWAIT_REENTRY", "server.awaitReentry", false);

            // The ONE sendMode resolver — ServerConfig cascade + the alias
            // row + the legacy CLI flag, contradictions THROW there. Resolved before the binding is
            // built so a misconfigured launch dies fast, without touching the engine.
            PersistentPilotIntentCombatServer.SendMode sendMode =
                    PersistentPilotIntentCombatServer.SendMode.resolve(decoupledSend);
            System.out.println("pilot-intent-combat-server send_mode="
                    + sendMode.name().toLowerCase(java.util.Locale.ROOT));

            // Npeer is the ONE serve lane. The knob survives
            // as a fail-closed guard — resolve the RAW string so an explicit false-y value THROWS
            // readable instead of silently selecting a retired lane; unset/truthy serve npeer.
            String npeerRaw = ServerConfig.resolve(
                    "cc.server.npeer", "CC_SERVER_NPEER", "server.npeer", "");
            switch (npeerRaw) {
                case "", "true", "1", "yes", "on" -> { }
                case "false", "0", "no", "off" -> throw new IllegalArgumentException(
                        "legacy serve() retired; the npeer UDP door is the"
                                + " ONE serve lane — unset cc.server.npeer (got cc.server.npeer="
                                + npeerRaw + ")");
                default -> throw new IllegalArgumentException(
                        "cc.server.npeer must be a boolean; got '" + npeerRaw + "'");
            }
            // Scripts grep this line (the npeer boot print stays unconditional).
            System.out.println("pilot-intent-combat-server npeer=true (multi-peer UDP door)");

            // Lane-posture M1: unconditional boot dump — the serve's effective posture on record
            // in every life's out.log, before the engine binding is built.
            PostureRegistry.dump(System.out);
            System.out.println("posture-arg key=--frames-per-session value=" + framesPerSession);
            System.out.println("posture-arg key=--fixed-dt value=" + fixedDt);
            System.out.println("posture-arg key=--client-wait-ms value=" + clientWaitMillis);
            System.out.println("posture-arg key=--pace-wall-clock value=" + paceToWallClock);

            // Lane-posture M3: postured boots refuse unknown cc.*/CC_* inputs
            // and tagless BEHAVIOR overrides BEFORE the engine binding is built; file-less boots
            // (rigs, A/B harnesses, contract-test children) are unpostured => inert.
            PostureRegistry.enforce();

            // The key gate's EARLY seat — the game's own activation
            // check, before the first socket and the ~50 s engine boot; a refusal exits EXIT_REFUSED below.
            ActivationGate.enforce();

            // The re-simulation boot gate. A blank cc.resim.record
            // is the normal serve (null, nothing touched). Else the reel is checked against this JVM
            // BEFORE the bind rehearsal and the engine boot (a refusal exits EXIT_REFUSED below), and
            // the forced inputs (wave seed, loopback host, ephemeral port) are set before anything
            // resolves them.
            ReplayBoot replay = ReplayBoot.openOrNull(fixedDt);
            if (replay != null) {
                replay.warnings().forEach(System.out::println);
                replay.throwIfRefused();
                replay.applyForces();
                System.out.println(replay.receiptLine());
            }

            // Rehearse the door's bind BEFORE the ~50 s engine boot —
            // the same cc.server.bindHost/bindPort keys serveNPeer resolves. A dead VPN lease /
            // taken port fails this life in ~1 s with the endpoint NAMED (friend test #2,
            // five lives × 50 s of "STARTING" over a bare BindException).
            PilotIntentDatagramServer.probeBind(
                    ServerConfig.resolve("cc.server.bindHost", "CC_SERVER_BIND_HOST",
                            "server.bindHost", ""),
                    ServerConfig.resolveInt("cc.server.bindPort", "CC_SERVER_BIND_PORT",
                            "server.bindPort", 0));

            // Build the binding FIRST via the GATED zero-hull create(SortedSet) (S9: deploy
            // NOTHING; every seat flies in mid-round through the serve loop's seat machine) —
            // the deploy ledger is only populated after this.
            EngineCombatBinding binding = buildNPeerBinding(bindingFactory);

            // The confessing-gun onFire sweep runs INSIDE createInternal
            // now — post-content-init and STRICTLY pre-t=0-deploy. A call here ran AFTER the
            // t=0 AI roster rows built their weapons, so round-1 AI mounts latched a null
            // plugin and never confessed (the boot-order latch race). Do not re-add.

            // Real-engine path: bound the stable-roster read by the deploy-ledger count (the pre-drain
            // getObjects() read is 0 until the first advance drains the pending-add queue -- the
            // fix in DedicatedCombatServerMain applies). A fake/other binding that deployed no roster
            // keeps the --ship-count fallback. The zero-hull boot leaves the deploy ledger
            // EMPTY, so the override applies only when something deployed, and the read must still
            // bound the full descriptor seat band the fly-ins will fill.
            int deployedCount = RealEngineDemoRoster.deployedShipCount();
            if (deployedCount > 0) {
                shipCount = deployedCount;
            }
            shipCount = Math.max(shipCount, RealEngineDemoRoster.descriptorSeatShipIds().length);

            // The STAR FORTRESS pre-flight — static descriptor-derived
            // DP curve + the eager officer-row skill check. Runs AFTER the binding build (engine
            // content initialized, the descriptor decoded and held by the roster) and BEFORE the
            // serve door opens, so a mis-authored chain or a bad officer row kills the BOOT
            // instead of a mid-round wave. A no-op for every non-fortress shape.
            // Grade the SAME match.fortressDp the serve will compile with (0 = auto).
            FortressServeBootChecks.runIfFortress(
                    RealEngineDemoRoster.deployedDescriptorOrNull(),
                    PersistentPilotIntentCombatServer.resolveFortressDpTotal(),
                    PersistentPilotIntentCombatServer.resolveFortressWaveScale(),
                    PersistentPilotIntentCombatServer.resolveWaveCategory());

            NPeerHooks npeerHooks = buildNPeerHooks();
            if (roundsPerSocket > 1 && npeerHooks.reset() == null) {
                throw new IllegalArgumentException(
                        "-Dcc.server.roundsPerSocket=" + roundsPerSocket + " (>1) under cc.server.npeer"
                                + " requires the gated reset hook: set -Dcc.server.matchReset=true"
                                + " (RealEngineCombatBindingFactory.resetForRedeploy(occupiedSeatIds))");
            }

            PilotServeConfig config = new PilotServeConfig(
                    framesPerSession,
                    fixedDt,
                    shipCount,
                    roundsPerSocket,
                    retryBudgetTicks,
                    emitMatchResetFrame,
                    awaitReentry,
                    framePaceMillis,
                    paceToWallClock,
                    clientWaitMillis,
                    sendMode,
                    replay == null ? null : replay.reel());

            // The WARM-UP ROUND — real seatless AI-flown combat on the
            // real engine BEFORE the door binds (the once-per-JVM first-combat compile storm
            // + ChunkPool prune paid here, never in the served round), each pass followed by the
            // proven between-rounds reset + a compile-quiesce wait (2 passes, the knob =
            // ticks PER PASS). AFTER the RNG re-arm arms (the call just below; on a pinned serve each
            // pass re-arms round 0 before its reset) and BEFORE serveNPeer (no per-serve registry/composer exists yet). 0 = off
            // (file-less rigs' A/B control). The knob is BEHAVIOR-class: every lane warms.
            //
            // Arm the per-round RNG re-arm BEFORE the warm-up.
            // Every handle (the Math holder, the JDK shuffle Random, Misc.random, the wave namer,
            // the keyed holder) resolves HERE, on the boot thread, so no re-arm site is a first
            // reflective resolve. Default OFF (no seed) = null, nothing resolved, byte-identical boot;
            // a set-but-bad seed or a missing handle THROWS (exit 1, never a silent unarmed run).
            String rearmReceipt = CombatRngRearm.armOrNull();
            if (rearmReceipt != null) {
                System.out.println("pilot-intent-combat-server " + rearmReceipt);
                // The frame-0 interval-timer restart's reflective resolve once ran
                // FIRST on the sim thread at round 0's frame 0 — the pacer's anchor frame. Resolve it here with the
                // re-arm's handles; a failure refuses the pinned boot like any other handle.
                int timerSlots = binding.prelinkEngineIntervalTimers();
                System.out.println("pilot-intent-combat-server rng_interval_prelink timers=" + timerSlots);
            }
            int warmupTicks = ServerConfig.resolveInt("cc.serve.warmupRound",
                    "CC_SERVE_WARMUP_ROUND", "serve.warmupRound", ServeWarmupRound.DEFAULT_TICKS);
            if (warmupTicks > 0 && replay != null) {
                // A re-simulation runs the RECORDED pass count
                // (no adaptive stop, no wall cut), then asserts it ran what the recording ran — a
                // mismatch is a SETUP line (the run continues; the differ grades the result), never
                // a divergence. The redirects' applied receipts are compared now, after the load.
                ServeWarmupRound.runOnRealEngine(binding, npeerHooks, fixedDt, warmupTicks,
                        replay.warmupPasses(), ReplayBoot.FORCED_QUIET_COMPILE_MILLIS,
                        ReplayBoot.FORCED_WALL_CAP_MILLIS);
                String warmupSetup = replay.warmupMismatchOrNull(ServeWarmupRound.lastPassesOrNeg(),
                        ServeWarmupRound.lastTicksOrNeg());
                if (warmupSetup != null) {
                    System.out.println(warmupSetup);
                }
                replay.appliedWarningsNow().forEach(System.out::println);
            } else if (warmupTicks > 0) {
                ServeWarmupRound.runOnRealEngine(binding, npeerHooks, fixedDt, warmupTicks);
            } else {
                if (replay != null) {
                    // Unreachable past the gate (a recorded warm-up of >= 1 pass under an equal
                    // behavior hash implies this knob > 0); a belt, never a silent unforced re-run.
                    throw new IllegalStateException("re-simulation boot with the warm-up OFF ("
                            + ServeWarmupRound.SYSPROP + "=" + warmupTicks + ")");
                }
                ServeWarmupRound.recordWarmupOff(); // Fold: META/END warmup.passes=0, not -1
                System.out.println("npeer-serve warmup_round OFF (" + ServeWarmupRound.SYSPROP
                        + "=" + warmupTicks + ")");
                if (rearmReceipt != null) {
                    // With the warm-up OFF, round 0's roster is the create-time
                    // deploy made BEFORE the arm, so no re-arm precedes it — a pinned serve in this posture is
                    // NOT replayable for round 0. Loud, never silent; the re-sim refuses it from the META's
                    // warm-up pass count (R3), and the A/B control rigs keep running.
                    System.out.println("pilot-intent-combat-server rng_rearm WARNING warmup_round OFF: round 0's"
                            + " roster was fielded before the arm (not replayable); set " + ServeWarmupRound.SYSPROP
                            + " > 0 for a recording serve");
                }
            }
            // Resolve the Remnant wave-name pools OFF-frame, before bind — the
            // first pick otherwise lands a JSON/spec load on the served sim thread at wave spawn.
            System.out.println("npeer-serve wave_namer_prewarm ok="
                    + cc.starsector.server.platform.WaveShipNamer.prewarm());

            // Wake the dormant shallow RNG pin on the serve boot.
            // Default OFF (cc.rngpin.seed absent/'unpinned') = no-op, byte-identical boot. It keeps
            // two jobs: its boot receipt and the keyed request-vs-armed enforcement (a mismatch
            // fail-fasts). Its Math.random setSeed here is SUPERSEDED on a pinned serve: the
            // re-arm reseeds that same generator at the top of every round's frame 0, after the
            // lobby wait and the round-start gate. A set-but-bad seed or missing --add-opens
            // fail-fasts to exit 1 (never a silent unpinned run).
            String rngPinReceipt = CombatRngPinBoot.pinFromConfigOrNull();
            if (rngPinReceipt != null) {
                System.out.println("pilot-intent-combat-server " + rngPinReceipt);
            }

            // W7 B1b: the /ship pickable allowlist — FIRST enabled-mod data/variants
            // root, root-only scan ∩ engine resolvability. Runs after the binding build (engine
            // content initialized, registry live). Empty/missing root ⇒ picking unavailable,
            // LOUD — a /ship nicety never fails a serve boot.
            //
            // The ONE id-table mint — the engine is loaded (the binding
            // build above ran content init), BEFORE the catalog scan (its VDEF pre-encode indexes
            // against it) and before serveNPeer (the door folds vtab into its fingerprint
            // expectation; the lane wiring reads the installed singleton). An engine-less binding
            // (a rig's fake factory) leaves the table UNINSTALLED: the door stays UNFOLDED and every
            // VSUB/VCHK decode is refused by requireIdTables() as malformed —
            // never IdTables.empty() folded into a real vtab (an empty table is a LOCAL scan input
            // only, never the installed singleton).
            IdTables idTables = null;
            try {
                idTables = cc.starsector.server.bridge.RealEngineVariantFacts.snapshot().idTables();
            } catch (RuntimeException noEngineFacts) {
                System.out.println("pilot-intent-combat-server id_tables=NONE (no engine facts: "
                        + noEngineFacts.getMessage() + ") — door UNFOLDED, every VSUB/VCHK refused as"
                        + " malformed");
            }
            if (idTables != null) {
                IdTables.install(idTables);
                System.out.println("pilot-intent-combat-server id_tables " + idTables.census());
                // The mod-set pin: the ENABLED MOD SET (every mod but cc_client) the door
                // folds into data= beside vtab — minted from the launcher's own enabled list, jar bytes
                // digested. A fault leaves the door SET-LESS (vtab-only fold): every in-game kit folds
                // its set, so each is refused with presented_fold=full — loud here AND at the door.
                boolean modSetMinted = false;
                try {
                    ModSet modSet = cc.starsector.server.bridge.RealEngineModSetFacts.snapshot();
                    ModSet.install(modSet);
                    modSetMinted = true;
                    System.out.println("pilot-intent-combat-server mod_set " + modSet.census());
                } catch (RuntimeException noModSet) {
                    System.out.println("pilot-intent-combat-server mod_set=NONE (" + noModSet.getMessage()
                            + ") — the door folds vtab ONLY; every in-game kit (which folds its mod set) is"
                            + " REFUSED at the door with presented_fold=full");
                }
                // The client-OPTIONAL set (mods.optional row; the wrapper mirrors the
                // folders but never enables them) — published on the probe as MARK_MODSET_OPTIONAL rows,
                // never folded. An operator-named id that cannot be minted REFUSES THE BOOT (a set knob is
                // never silently inert; a mod in both rows is refused too).
                String optionalRow = ServerConfig.resolve("cc.mods.optional", "CC_MODS_OPTIONAL", "mods.optional", "");
                java.util.List<String> optionalIds = new java.util.ArrayList<String>();
                for (String id : optionalRow.split("[,;]")) {
                    if (!id.trim().isEmpty()) {
                        optionalIds.add(id.trim());
                    }
                }
                if (!optionalIds.isEmpty()) {
                    if (!modSetMinted) {
                        throw new IllegalStateException("mods.optional=" + optionalRow + " but the required mod set"
                                + " did not mint — refusing to boot a serve that publishes optional mods over a set-less door");
                    }
                    ModSet optional = cc.starsector.server.bridge.RealEngineModSetFacts.optionalSnapshot(optionalIds);
                    ModSet.installOptional(optional);
                    System.out.println("pilot-intent-combat-server mod_set_optional " + optional.census()
                            + " (published on the probe, NOT loaded, NOT folded)");
                }
                // The dump the game-free rigs read (the synthetic client's -Dcc.client.idTables, the
                // parity verifiers): beside the port file, which every launcher already knows.
                java.io.File idTablesFile = new java.io.File(portFile + ".id-tables.json");
                try {
                    IdTablesJsonFile.write(idTablesFile, idTables);
                    System.out.println("pilot-intent-combat-server id_tables_file=" + idTablesFile);
                } catch (IOException dumpFailed) {
                    System.out.println("pilot-intent-combat-server id_tables_file=FAILED " + idTablesFile
                            + " (" + dumpFailed.getMessage() + ") — rigs cannot encode customs against this serve");
                }
            }
            // The re-simulation's id-tables belt, now that the tables
            // are installed (or known absent): a reel whose VSUB bodies were indexed by a different table
            // exits through the one refusal path (the catch below prints the line, exit EXIT_REFUSED).
            if (replay != null) {
                String vtabRefusal = replay.vtabRefusalOrNull(idTables == null ? null : idTables.vtab());
                if (vtabRefusal != null) {
                    throw new ReplayBoot.ReplayRefusedException(java.util.List.of(vtabRefusal));
                }
            }

            java.util.List<java.io.File> variantRoots = RealEngineDemoRoster.enabledModVariantRoots();
            PickableVariants pickable;
            VariantCatalogStore variantCatalog;
            if (variantRoots.isEmpty()) {
                System.out.println("pilot-intent-combat-server pickable_variants=unavailable"
                        + " (no enabled-mod data/variants root)");
                pickable = PickableVariants.empty();
                variantCatalog = VariantCatalogStore.empty();
            } else {
                if (variantRoots.size() > 1) {
                    System.out.println("pilot-intent-combat-server pickable_variants"
                            + " extra_roots_ignored=" + (variantRoots.size() - 1)
                            + " scanned=" + variantRoots.get(0));
                }
                pickable = PickableVariants.scan(
                        variantRoots.get(0), RealEngineDemoRoster::variantResolvable);
                // W7c B2: the SAME root feeds the wire catalog — root ∪ npc/
                // (waves need npc ids client-side too), pre-encoded once at boot.
                // A LOCAL empty table for the scan only when
                // engine-less — never installed (the door stays unfolded).
                variantCatalog = VariantCatalogStore.scan(variantRoots.get(0),
                        idTables == null ? IdTables.empty() : idTables);
            }

            final String portFilePath = portFile;
            PilotServeReceipt receipt = PersistentPilotIntentCombatServer.serveNPeer(
                    config,
                    binding,
                    npeerHooks,
                    (host, port) -> PilotPortFile.write(portFilePath, host, port),
                    pickable,
                    variantCatalog);

            for (PilotRoundSummary round : receipt.rounds()) {
                System.out.println("pilot-round"
                        + " index=" + round.roundIndex()
                        + " frames=" + round.framesServed()
                        + " match_over=" + round.matchOver()
                        + " frames_applied=" + round.framesApplied()
                        + " movement_commands=" + round.movementCommandsIssued()
                        + " facing_pins=" + round.facingPins()
                        + " aim_pins=" + round.aimPins() // mouseTarget pinned from the carried aim
                        + " system_activations=" + round.systemActivations()
                        + " vents=" + round.ventsIssued()
                        + " fires=" + round.firesIssued()
                        + " fire_decl_received=" + round.fireDeclReceived()
                        + " fire_decl_served=" + round.fireDeclServed()
                        + " fire_decl_served_credit=" + round.fireDeclServedCredit() //
                        + " credit_minted=" + round.fireDeclCreditMinted() // credit ledger
                        + " credit_expired=" + round.fireDeclCreditExpired()
                        + " credit_capped=" + round.fireDeclCreditCapped()
                        + " credit_fence_refused=" + round.fireDeclCreditFenceRefused()
                        + " discharge_unmatched_nonburst=" + round.fireDischargeUnmatchedNonburst()
                        + " credit_last_shot=" + round.fireLastShotCreditsMinted() // last-shot credits minted
                        + " fire_decl_expired=" + round.fireDeclExpired()
                        + " fire_decl_presses=" + round.fireDeclPresses() //
                        + " fire_decl_wait_ticks=" + round.fireDeclWaitTicks()
                        + " fire_lock_waits=" + round.fireLockWaitTicks() // read by the arena rig's lock row
                        + " fire_lock_read_errors=" + round.fireLockReadErrors() // fail-open fire-lock read errors
                        + " adopt_serves=" + round.adoptServes() // the OPTION-A adopt lane
                        + " adopt_batched=" + round.adoptBatched() // volley batch
                        + " adopt_preadvanced=" + round.adoptPreadvanced() //
                        + " adopt_rejects=" + round.adoptRejects()
                        + ("-".equals(round.adoptRejectReasons()) ? ""
                                : "(" + round.adoptRejectReasons() + ")")
                        + " adopt_capture_miss=" + round.adoptCaptureMiss()
                        + " overrun_frames=" + round.overrunFrames()
                        + " overrun_max_ms="
                        + String.format(java.util.Locale.ROOT, "%.2f", round.overrunMaxMillis()));
            }
            System.out.println("pilot-intent-combat-server DONE"
                    + " host=" + receipt.host()
                    + " port=" + receipt.port()
                    + " off_loopback=" + receipt.offLoopback()
                    + " client_connected=" + receipt.clientConnected()
                    + " rounds_served=" + receipt.roundsServed()
                    + " reset_frames=" + receipt.resetFramesEmitted()
                    + " reentries_awaited=" + receipt.reentriesAwaited()
                    + " last_match_over=" + receipt.lastRoundMatchOver()
                    + " held_lane_fallback=" + receipt.heldLaneFallback());
            System.exit(0);
        } catch (ActivationGate.ActivationRefusedException refused) {
            // ONE stderr line, no stack trace, and the FINAL exit
            // code (run-supervised.ps1 never relaunches 78) — before the generic catch's exit 1.
            System.err.println(ActivationGate.refusalLine(refused));
            System.exit(ActivationGate.EXIT_REFUSED);
        } catch (ReplayBoot.ReplayRefusedException refused) {
            // One stdout line per refusal (the pinned REFUSED
            // shape, values sanitized), ONE stderr line, no stack trace, the same FINAL exit code.
            refused.lines().forEach(System.out::println);
            System.err.println("ReplayRefusedException: " + refused.getMessage());
            System.exit(ReplayBoot.EXIT_REFUSED);
        } catch (Exception e) {
            e.printStackTrace();
            // The LAST stderr line = the top-level cause as one sentence.
            // The release launcher's supervisor shows a dead life's last err line to the host
            // (ServeSupervisor; README.md "its last error line"); a stack frame told them nothing,
            // while e.toString() keeps the bind probe's endpoint text and the config refusals' words.
            // [README.md rename:]
            System.err.println("pilot-intent-server failed: " + e);
            System.exit(1);
        }
    }

    /**
     * Zero-hull boot: build the binding via the factory's GATED
     * {@code create(SortedSet<Integer>)} with the EMPTY occupied set — deploy NOTHING; every seat
     * flies in mid-round through the serve loop's seat machine. A factory without the gated
     * overload cannot serve the N-peer lane: fail-closed, naming the requirement.
     */
    private static EngineCombatBinding buildNPeerBinding(String factoryClassName) throws Exception {
        Class<?> factory = Class.forName(factoryClassName);
        Method create;
        try {
            create = factory.getMethod("create", SortedSet.class);
        } catch (NoSuchMethodException gatedCreateMissing) {
            throw new IllegalArgumentException("cc.server.npeer=true requires the binding factory "
                    + factoryClassName + " to expose the gated create(SortedSet<Integer>) zero-hull"
                    + " boot; the no-arg create() is the legacy full-deploy lane",
                    gatedCreateMissing);
        }
        Object binding = create.invoke(null, new TreeSet<Integer>());
        if (!(binding instanceof EngineCombatBinding engineBinding)) {
            throw new IllegalArgumentException("binding factory " + factoryClassName
                    + ".create(SortedSet) did not return an EngineCombatBinding");
        }
        return engineBinding;
    }

    /**
     * The N-peer game-identity hooks off the real factory/roster.
     * The gated reset is minted only under {@code -Dcc.server.matchReset=true} (legacy knob
     * semantics; absent = the single-round posture, serveNPeer fail-fasts multi-round without
     * it). The fly-in hook diffs {@code seatStableIdByWireId()} around
     * {@code midDeploySeat(seatId)} — the added-or-repointed entry IS the (wireId, stableId)
     * pair (a REMOUNT re-points the SAME wireId to a fresh stableId); no change is a
     * fail-closed IOException, never a silent guess. The
     * gated reset fields its seats through the SAME {@code midDeploySeat} lane as every
     * join/respawn (one fly-in shape for the whole match) and builds each reset
     * {@code FlyInResult} from the returned {@code SeatDeployResult} exactly as the fly-in hook
     * does; a result count ≠ occupied count is a fail-closed IOException.
     */
    private static NPeerHooks buildNPeerHooks() {
        NPeerHooks.GatedMatchResetHook reset = null;
        if (ServerConfig.resolveBoolean(
                "cc.server.matchReset", "CC_SERVER_MATCH_RESET", "server.matchReset", false)) {
            // The wrap hands each seat's CURRENT pick
            // through to the redeploy (override ?? descriptor); the result reports the FIELDED id.
            // + each seat's kept team side (the PvP shape's sticky teams).
            reset = (occupiedSeatIds, variantOverrideBySeat, sideBySeat) -> {
                java.util.List<RealEngineDemoRoster.SeatDeployResult> results;
                try {
                    results = RealEngineCombatBindingFactory.resetForRedeploy(occupiedSeatIds,
                            variantOverrideBySeat, sideBySeat);
                } catch (RuntimeException resetFailure) {
                    throw new IOException("npeer match reset failed: " + resetFailure.getMessage(),
                            resetFailure);
                }
                // The reset fielded each occupied seat
                // through midDeploySeat — each result carries its own (wireId, stableId) and
                // fielded identity, built exactly as the fly-in hook builds it. Fail-closed on a
                // count mismatch (the next round's registry pre-seed must cover every seat).
                if (results.size() != occupiedSeatIds.size()) {
                    throw new IOException("npeer match reset fielded " + results.size()
                            + " seat fly-ins for " + occupiedSeatIds.size()
                            + " occupied seats — refusing a partial pre-seed");
                }
                java.util.List<NPeerHooks.FlyInResult> flyIns =
                        new java.util.ArrayList<>(results.size());
                for (RealEngineDemoRoster.SeatDeployResult result : results) {
                    flyIns.add(new NPeerHooks.FlyInResult(result.wireId(), result.stableId(),
                            result.variantId(), result.descriptorVariantId(), result.side(),
                            result.shipName(), result.crPerMille()));
                }
                return flyIns;
            };
        }
        return new NPeerHooks(reset, buildSeatFlyInHook(),
                RealEngineCombatBindingFactory::runDeferredMessageWidgetShipPostSelfCheck,
                buildWaveSpawnHook(), buildGrowthSeamHook(), buildStationHook(),
                buildWaveSkipHook(),
                // The engine getSuppliesToRecover fold — consulted only when a
                // fortress serve compiles with match.fortressDp set (lazy, memoized per boot).
                FortressServeBootChecks.engineDpResolver(),
                buildSeatSuicideHook(),
                buildModuleMountHook(),
                buildTransferRekeyHook(),
                // The /debugships+/spawnfoe catalogue source — the engine
                // variant-registry keyset, snapshotted ONCE at door wiring (fail-soft:
                // empty list + loud line on any engine miss, never a boot failure).
                RealEngineDemoRoster::variantRegistryIds,
                // The derelict finale creep author (armed only on a
                // scheduled/probe finale row with a positive wave.finaleCreepSu).
                buildFinaleCreepHook());
        // (The pilotable-hull classifier hook was DELETED with the debug-door
        // pick widening; every door lists the mod scan.)
    }

    /**
     * The ONE real finale-creep hook — fronts {@link FinaleCreepAuthor}
     * (the reflective PRE-STEP velocity author; seam + laws in its class javadoc). Bound
     * unconditionally like the wave hooks: serveNPeer arms it only when the scheduled or probe
     * drain fields the derelict finale mothership with a positive knob, ticks it through the
     * round's step decorator, disarms it at the round boundary. Never throws past {@code arm}
     * — the author is fail-soft by law (any failure = vanilla drift, logged once).
     */
    private static NPeerHooks.FinaleCreepHook buildFinaleCreepHook() {
        FinaleCreepAuthor author = new FinaleCreepAuthor();
        return new NPeerHooks.FinaleCreepHook() {
            @Override
            public String arm(String rootStableId, float creepSu) {
                return author.arm(rootStableId, creepSu);
            }

            @Override
            public void tick(float dtSeconds) {
                author.tick(dtSeconds);
            }

            @Override
            public void disarm() {
                author.disarm();
            }

            @Override
            public void enlistEscort(String escortStableId) {
                author.enlistEscort(escortStableId);
            }

            @Override
            public void postStep() {
                author.postStep();
            }
        };
    }

    /**
     * W2b E3: the ONE real module-mount hook — fronts
     * {@code SeatRebind.mountStationModule}/{@code dismountStationModule}
     * (MOUNT-EXISTING re-key + its dismount reverse). Re-throws as {@code IOException} (the
     * hook-layer idiom, the seatSuicide twin) — an engine re-key failure is fatal, never
     * per-session.
     */
    private static ModuleMountHook buildModuleMountHook() {
        return new ModuleMountHook() {
            @Override
            public SeatRebind.ModuleMountResult mount(int seatId, long seatWireId,
                    String slotId, String expectedModuleVariantId) throws IOException {
                try {
                    return SeatRebind.mountStationModule(
                            seatId, seatWireId, slotId, expectedModuleVariantId);
                } catch (RuntimeException mountFailure) {
                    throw new IOException("module mount failed: "
                            + mountFailure.getMessage(), mountFailure);
                }
            }

            @Override
            public String dismount(int seatId, long seatWireId) throws IOException {
                try {
                    return SeatRebind.dismountStationModule(seatId, seatWireId);
                } catch (RuntimeException dismountFailure) {
                    throw new IOException("module dismount failed: "
                            + dismountFailure.getMessage(), dismountFailure);
                }
            }
        };
    }

    /**
     * W3 L1b-ii: the ONE real transfer-rekey hook — fronts
     * {@code SeatRebind.transferSeatToHull} (source AI restore + target yield +
     * live/stable map re-point; normal-race ineligibility returns a typed DENY inside the
     * result). Re-throws as {@code IOException} (the hook-layer idiom, the moduleMount twin) —
     * an engine re-key FAILURE is fatal, never per-session.
     */
    private static TransferRekeyHook buildTransferRekeyHook() {
        return new TransferRekeyHook() {
            @Override
            public SeatRebind.TransferRekeyResult transferSeatToHull(int seatId,
                    long seatWireId, long targetHullWireId, String targetStableId)
                    throws IOException {
                try {
                    return SeatRebind.transferSeatToHull(
                            seatId, seatWireId, targetHullWireId, targetStableId);
                } catch (RuntimeException rekeyFailure) {
                    throw new IOException("transfer re-key failed: "
                            + rekeyFailure.getMessage(), rekeyFailure);
                }
            }

            @Override
            public SeatRebind.TransferRekeyResult mountSeatOnPod(int seatId, long seatWireId,
                    String podStableId, Object podHandleOrNull) throws IOException {
                try {
                    return SeatRebind.mountSeatOnPod(seatId, seatWireId, podStableId,
                            podHandleOrNull);
                } catch (RuntimeException rekeyFailure) {
                    throw new IOException("pod-mount re-key failed: "
                            + rekeyFailure.getMessage(), rekeyFailure);
                }
            }

            @Override
            public java.util.List<SeatRebind.StationModuleCensusRow> stationModuleCensus(
                    java.util.Collection<String> slotIds) throws IOException {
                try {
                    return SeatRebind.stationModuleCensus(slotIds);
                } catch (RuntimeException censusFailure) {
                    throw new IOException("station-module census failed: "
                            + censusFailure.getMessage(), censusFailure);
                }
            }

            @Override
            public SeatRebind.TransferRekeyResult transferSeatToStationModule(int seatId,
                    long seatWireId, long targetModuleWireId, String targetStableId)
                    throws IOException {
                try {
                    return SeatRebind.transferSeatToStationModule(
                            seatId, seatWireId, targetModuleWireId, targetStableId);
                } catch (RuntimeException rekeyFailure) {
                    throw new IOException("station-module transfer re-key failed: "
                            + rekeyFailure.getMessage(), rekeyFailure);
                }
            }
        };
    }

    /**
     * W7b: the ONE real seat-suicide hook — fronts
     * {@code SeatRebind.forceKillLiveShipForWireId} (the engine's own
     * {@code applyDamage}, bypass-shields, single wire id; false = already dead/unmounted).
     * Re-throws as {@code IOException} (the hook-layer idiom) — a damage-apply failure is
     * fatal, never per-session (the wave-skip law).
     */
    private static NPeerHooks.SeatSuicideHook buildSeatSuicideHook() {
        return wireId -> {
            try {
                return SeatRebind.forceKillLiveShipForWireId(wireId);
            } catch (RuntimeException killFailure) {
                throw new IOException("seat-suicide force-kill failed: "
                        + killFailure.getMessage(), killFailure);
            }
        };
    }

    /**
     * The ONE real wave-skip hook — fronts
     * {@code SeatRebind.forceKillSideHulls} (the engine's own
     * {@code applyDamage}, bypass-shields, per live wave-side hull) and
     * {@code SeatRebind.removeSideHulks} (the deferred corpse half). Bound unconditionally
     * like the wave/station hooks: serveNPeer consults it only on an operator
     * {@code skip-wave} verb, which itself refuses on a schedule-less serve. Re-throws as
     * {@code IOException} (the hook-layer idiom) — a damage-apply failure is fatal, never
     * per-session.
     */
    private static NPeerHooks.WaveSkipHook buildWaveSkipHook() {
        return new NPeerHooks.WaveSkipHook() {
            @Override
            public int killSideHulls(int side) throws IOException {
                try {
                    return SeatRebind.forceKillSideHulls(side);
                } catch (RuntimeException killFailure) {
                    throw new IOException("wave-skip force-kill failed: "
                            + killFailure.getMessage(), killFailure);
                }
            }

            // The deferred corpse half (dead ShardSpawner parents leave
            // play before their 2-3 s child delay; see SeatRebind.removeSideHulks).
            @Override
            public int removeSideHulks(int side) throws IOException {
                try {
                    return SeatRebind.removeSideHulks(side);
                } catch (RuntimeException sweepFailure) {
                    throw new IOException("wave-skip hulk sweep failed: "
                            + sweepFailure.getMessage(), sweepFailure);
                }
            }
        };
    }

    /**
     * The ONE real station hook — module variant ids off the engine's own
     * variant registry ({@code EngineBornGrowthSeam.stationModuleVariantIds}, the
     * {@code capacityFor} resolve idiom) and the base-hulk lose seam off the deploy-held base
     * reference ({@code RealEngineDemoRoster.stationBaseHulk}). Bound unconditionally like the
     * wave/growth hooks: serveNPeer consults it only when the descriptor fields a STATION row.
     * Both bodies re-throw as {@code IOException} (the hook-layer idiom) — neither degrades.
     */
    private static NPeerHooks.StationHook buildStationHook() {
        return new NPeerHooks.StationHook() {
            @Override
            public java.util.List<String> moduleVariantIds(String variantId) throws IOException {
                try {
                    return EngineBornGrowthSeam.stationModuleVariantIds(variantId);
                } catch (RuntimeException moduleFailure) {
                    throw new IOException("station module resolve failed for variant '"
                            + variantId + "': " + moduleFailure.getMessage(), moduleFailure);
                }
            }

            @Override
            public boolean baseLost() throws IOException {
                try {
                    return RealEngineDemoRoster.stationBaseHulk();
                } catch (RuntimeException readFailure) {
                    throw new IOException("station base-hulk read failed: "
                            + readFailure.getMessage(), readFailure);
                }
            }
        };
    }

    /**
     * The ONE real growth-seam hook — fronts the reflective
     * {@code bridge.EngineBornGrowthSeam} (repository listener + loader capacity reads).
     * Bound unconditionally like the wave hook: serveNPeer consults it only under
     * {@code cc.server.engineBornGrowth=true} (strict bool, default OFF). The
     * {@code crPerMille} clamp lives HERE (the wave-hook idiom, as in {@code buildWaveSpawnHook}):
     * the seam records the raw engine read; {@code GrowthEvent} is fail-closed on range.
     */
    private static NPeerHooks.GrowthSeamHook buildGrowthSeamHook() {
        return new NPeerHooks.GrowthSeamHook() {
            @Override
            public NPeerHooks.GrowthCapacity capacityFor(String variantId) throws IOException {
                try {
                    EngineBornGrowthSeam.WingDroneCapacity capacity =
                            EngineBornGrowthSeam.capacityFor(variantId);
                    return new NPeerHooks.GrowthCapacity(capacity.fighters(), capacity.drones());
                } catch (RuntimeException capacityFailure) {
                    throw new IOException("growth capacity resolve failed for variant '"
                            + variantId + "': " + capacityFailure.getMessage(), capacityFailure);
                }
            }

            @Override
            public void install() throws IOException {
                try {
                    EngineBornGrowthSeam.install();
                } catch (RuntimeException installFailure) {
                    throw new IOException("growth seam install failed: "
                            + installFailure.getMessage(), installFailure);
                }
            }

            @Override
            public java.util.List<NPeerHooks.GrowthEvent> drain() throws IOException {
                try {
                    java.util.List<EngineBornGrowthSeam.CapturedEdge> edges =
                            EngineBornGrowthSeam.drain();
                    java.util.List<NPeerHooks.GrowthEvent> events =
                            new java.util.ArrayList<>(edges.size());
                    for (EngineBornGrowthSeam.CapturedEdge edge : edges) {
                        events.add(new NPeerHooks.GrowthEvent(edge.added(), edge.stableKey(),
                                edge.side(), edge.variantId(), edge.shipName(),
                                Math.max(0, Math.min(1000, edge.crPerMille())),
                                edge.removedHealthy(), edge.carrierStableKey(), edge.bayIndex(),
                                edge.officerLine()));
                    }
                    return events;
                } catch (RuntimeException drainFailure) {
                    throw new IOException("growth seam drain failed: "
                            + drainFailure.getMessage(), drainFailure);
                }
            }

            @Override
            public void uninstall() throws IOException {
                try {
                    EngineBornGrowthSeam.uninstall();
                } catch (RuntimeException uninstallFailure) {
                    throw new IOException("growth seam uninstall failed: "
                            + uninstallFailure.getMessage(), uninstallFailure);
                }
            }

            @Override
            public java.util.List<String> deployedHullStableIds() {
                // The round's t=0 deploy-ledger identities (fresh per gated-reset
                // redeploy) — the serve loop's growth known-keys seed.
                return RealEngineDemoRoster.deployedHullStableIds();
            }
        };
    }

    /**
     * The ONE real-roster wave-spawn hook — routes each due {@code WaveSchedule}
     * row to {@code RealEngineDemoRoster.midDeployWaveShip} (the engine's own reinforcement
     * edge) and returns the deploy-recorded identity for the serve loop's mint-time intern +
     * {@code RAPP} roster-append fan. Bound unconditionally: serveNPeer consults it
     * only when the descriptor shape compiled a schedule.
     */
    private static NPeerHooks.WaveSpawnHook buildWaveSpawnHook() {
        return new NPeerHooks.WaveSpawnHook() {
            @Override
            public NPeerHooks.WaveSpawnResult spawn(int side, String variantId, String shipName)
                    throws IOException {
                return spawn(side, variantId, shipName, null);
            }

            @Override
            public NPeerHooks.WaveSpawnResult spawn(int side, String variantId, String shipName,
                    float[] poseOrNull) throws IOException {
                return spawn(side, variantId, shipName, poseOrNull, null);
            }

            @Override
            public NPeerHooks.WaveSpawnResult spawn(int side, String variantId, String shipName,
                    float[] poseOrNull,
                    cc.starsector.server.match.OfficerRosterCodec.OfficerSpec officerOrNull)
                    throws IOException {
                // LEG C pose pass-through (null = edge slot) + LEG B module ids
                // (the engine-minted STATION_MODULE children of a variant.isStation() hull —
                // empty otherwise) carried to the serve loop's band intern.
                // the row-carried officer rides through to the roster's attach site.
                try {
                    RealEngineDemoRoster.WaveDeployResult deployed =
                            RealEngineDemoRoster.midDeployWaveShip(side, variantId, shipName,
                                    poseOrNull, officerOrNull);
                    int crPerMille = Math.max(0, Math.min(1000,
                            Math.round(deployed.deployCr() * 1000f)));
                    // The ATTACHED captain rides to the RAPP v3 officer tail.
                    // + the hull's collision radius (the
                    // adhoc cluster spacing source; never a wire byte).
                    return new NPeerHooks.WaveSpawnResult(deployed.stableId(), crPerMille,
                            deployed.moduleStableIds(), deployed.officerOrNull(),
                            deployed.collisionRadius(), deployed.atRest()); // RAPP v5 bit
                } catch (RuntimeException spawnFailure) {
                    throw new IOException("wave spawn failed for " + shipName + " (" + variantId
                            + ", side " + side + "): " + spawnFailure.getMessage(), spawnFailure);
                }
            }

            @Override
            public String probeSampleLine(String stableId) {
                // engine-truth wave-probe sample (never throws).
                return RealEngineDemoRoster.waveProbeSampleLine(stableId);
            }

            @Override
            public void wavePosture(String category) {
                // The /wave switch flips the engine context's
                // fightToTheLast for the wave admiral (no-op when the knob is OFF).
                RealEngineDemoRoster.applyWaveAdmiralPosture(category);
            }
        };
    }

    /**
     * The ONE real-roster seat fly-in hook (wired into {@link NPeerHooks}). Diffs
     * {@code seatStableIdByWireId()} around the override-consulting
     * {@code midDeploySeat(seatId, overrideOrNull)} (W7 B1b — null = descriptor) — the added-or-repointed
     * entry IS the (wireId, stableId) pair (a REMOUNT re-points the SAME wireId to a fresh
     * stableId); no change is a fail-closed IOException, never a silent guess. {@code vacate}
     * routes the serve loops' T3 mount-loss to {@code RealEngineDemoRoster.vacateSeatHull} —
     * without it the dead hull's live-map entry survives the cooldown and the remount fly-in dies
     * on {@code midDeploySeat}'s fail-closed "already live-mapped" guard.
     */
    private static NPeerHooks.SeatFlyInHook buildSeatFlyInHook() {
        return new NPeerHooks.SeatFlyInHook() {
            @Override
            public NPeerHooks.FlyInResult deploy(int seatId) throws IOException {
                return deploy(seatId, null);
            }

            @Override
            public NPeerHooks.FlyInResult deploy(int seatId, String overrideOrNull)
                    throws IOException {
                return deploy(seatId, overrideOrNull, -1);
            }

            @Override
            public NPeerHooks.FlyInResult deploy(int seatId, String overrideOrNull,
                    int sideOrNeg) throws IOException {
                Map<Long, String> before = RealEngineDemoRoster.seatStableIdByWireId();
                RealEngineDemoRoster.SeatDeployResult deployResult;
                try {
                    deployResult = RealEngineDemoRoster.midDeploySeat(seatId, overrideOrNull,
                            sideOrNeg);
                } catch (RuntimeException deployFailure) {
                    throw new IOException("seat fly-in deploy failed for seat " + seatId + ": "
                            + deployFailure.getMessage(), deployFailure);
                }
                Map<Long, String> after = RealEngineDemoRoster.seatStableIdByWireId();
                for (Map.Entry<Long, String> entry : after.entrySet()) {
                    if (!entry.getValue().equals(before.get(entry.getKey()))) {
                        return new NPeerHooks.FlyInResult(entry.getKey(), entry.getValue(),
                                deployResult.variantId(), deployResult.descriptorVariantId(),
                                deployResult.side(), deployResult.shipName(),
                                deployResult.crPerMille());
                    }
                }
                throw new IOException("seat fly-in for seat " + seatId
                        + " changed no seatStableIdByWireId entry (the deploy recorded no stable id)");
            }

            @Override
            public void vacate(int seatId, long wireId) throws IOException {
                try {
                    RealEngineDemoRoster.vacateSeatHull(seatId, wireId);
                } catch (RuntimeException vacateFailure) {
                    throw new IOException("seat hull vacate failed for seat " + seatId
                            + " wire id " + wireId + ": " + vacateFailure.getMessage(),
                            vacateFailure);
                }
            }
        };
    }

}
