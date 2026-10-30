package cc.starsector.server.runtime;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The server's posture census + boot dump — migration step M1 of the lane-posture design.
 *
 *
 * <p>WHY: the lane-parity audit proved every serve lane's effective posture was an emergent
 * sum of per-lane wrapper ferries — a missed ferry silently rode the compiled default
 * (D-NORESPAWN / D-OFFSKILLS / snapDelta). This registry is the ONE
 * census of every input that can reach the serve JVM ({@code cc.*} sysprop / {@code CC_*} env /
 * config-file row), and {@link #dump} prints the complete resolved posture at boot — one
 * machine-parseable line per knob plus a hash — so "what is this lane running" is a printed fact,
 * never log archaeology. Row source of truth: the audit's {@code jvm-truth.md} census
 * (ServerConfig-resolved rows) plus the direct-sysprop reads greppable as
 * {@code get(Integer|Boolean|Property)\("cc\.} under {@code server/src} (live serve path only).
 *
 * <p>Dump semantics: {@code value} is printed only when an EXTERNAL tier supplied it
 * (sysprop/env/file); an untouched knob prints {@code value=(compiled) source=compiled} — the
 * compiled default is jar-locked (door fingerprint), so (jar, external-input set) fully determines
 * posture and the dump deliberately does NOT duplicate default values (a second copy would drift).
 * {@code posture-hash} covers the sorted {@code key=value} lines, so it is invariant to WHERE a
 * value came from — that invariance is the M2 cutover proof (ferry → file moves must keep the
 * hash identical).
 *
 * <p>Classes: BEHAVIOR = lane-invariant match behavior;
 * INSTRUMENT = observation arms lanes may delta (registered); INFRA = endpoints/paths/budgets.
 * Default for new knobs is BEHAVIOR (default-deny). M3 rides here:
 * {@link #enforce()} refuses unknown {@code cc.*}/{@code CC_*} inputs and tagless
 * BEHAVIOR overrides on POSTURED boots (a config file configured); file-less boots stay dump-only.
 */
public final class PostureRegistry {

    /** Knob input classes — see the design doc P2. */
    public enum KnobClass { BEHAVIOR, INSTRUMENT, INFRA }

    /** One registered input: canonical sysprop key, optional env twin, optional config-file row. */
    public record Knob(String key, String env, String fileKey, KnobClass cls) {
    }

    private static final List<Knob> KNOBS = List.of(
            // --- meta (the resolver's own switches; sysprop/env only by design) ---
            new Knob("cc.server.config", "CC_SERVER_CONFIG", null, KnobClass.INFRA),
            new Knob("cc.config.strict", "CC_CONFIG_STRICT", null, KnobClass.INFRA),
            // --- sim / match behavior ---
            new Knob("cc.sim.tickRate", "CC_SIM_TICK_RATE", "sim.tickRate", KnobClass.BEHAVIOR),
            new Knob("cc.sim.autoSpeed", "CC_SIM_AUTO_SPEED", "sim.autoSpeed", KnobClass.BEHAVIOR),
            new Knob("cc.sim.autoSpeedFloor", "CC_SIM_AUTO_SPEED_FLOOR", "sim.autoSpeedFloor", KnobClass.BEHAVIOR),
            new Knob("cc.sim.autoSpeedCeiling", "CC_SIM_AUTO_SPEED_CEILING", "sim.autoSpeedCeiling", KnobClass.BEHAVIOR),
            new Knob("cc.match.respawnPolicy", "CC_MATCH_RESPAWN_POLICY", "match.respawnPolicy", KnobClass.BEHAVIOR),
            new Knob("cc.match.graceSeconds", "CC_MATCH_GRACE_SECONDS", "match.graceSeconds", KnobClass.BEHAVIOR),
            new Knob("cc.match.parkPolicy", "CC_MATCH_PARK_POLICY", "match.parkPolicy", KnobClass.BEHAVIOR),
            new Knob("cc.match.flyinBurnSec", "CC_MATCH_FLYIN_BURN_SEC", "match.flyinBurnSec", KnobClass.BEHAVIOR),
            new Knob("cc.match.lingerSec", "CC_MATCH_LINGER_SEC", "match.lingerSec", KnobClass.BEHAVIOR),
            // W12 voice L2 (VoiceConfig.resolve reads exactly
            // these triples). echo = latency-rig instrument (a talker hears its own frames back).
            new Knob("cc.voice.enabled", "CC_VOICE_ENABLED", "voice.enabled", KnobClass.BEHAVIOR),
            new Knob("cc.voice.bitrateKbps", "CC_VOICE_BITRATE_KBPS", "voice.bitrateKbps", KnobClass.BEHAVIOR),
            new Knob("cc.voice.frameMs", "CC_VOICE_FRAME_MS", "voice.frameMs", KnobClass.BEHAVIOR),
            new Knob("cc.voice.complexity", "CC_VOICE_COMPLEXITY", "voice.complexity", KnobClass.BEHAVIOR),
            new Knob("cc.voice.fec", "CC_VOICE_FEC", "voice.fec", KnobClass.BEHAVIOR),
            new Knob("cc.voice.lowDelay", "CC_VOICE_LOW_DELAY", "voice.lowDelay", KnobClass.BEHAVIOR),
            new Knob("cc.voice.echo", "CC_VOICE_ECHO", "voice.echo", KnobClass.INSTRUMENT),
            new Knob("cc.voice.maxTalkers", "CC_VOICE_MAX_TALKERS", "voice.maxTalkers", KnobClass.BEHAVIOR),
            new Knob("cc.voice.proxFullSu", "CC_VOICE_PROX_FULL_SU", "voice.proxFullSu", KnobClass.BEHAVIOR),
            new Knob("cc.voice.proxCullSu", "CC_VOICE_PROX_CULL_SU", "voice.proxCullSu", KnobClass.BEHAVIOR),
            new Knob("cc.voice.relayTickMs", "CC_VOICE_RELAY_TICK_MS", "voice.relayTickMs", KnobClass.BEHAVIOR),
            // W12 (v1) jitter-aggression trio: the client TalkerStream's floor /
            // ceiling / relax window, server-owned + VCFG-delivered (never client-baked).
            new Knob("cc.voice.jitterMinFrames", "CC_VOICE_JITTER_MIN_FRAMES", "voice.jitterMinFrames", KnobClass.BEHAVIOR),
            new Knob("cc.voice.jitterMaxMs", "CC_VOICE_JITTER_MAX_MS", "voice.jitterMaxMs", KnobClass.BEHAVIOR),
            new Knob("cc.voice.jitterShrinkMs", "CC_VOICE_JITTER_SHRINK_MS", "voice.jitterShrinkMs", KnobClass.BEHAVIOR),
            new Knob("cc.match.deployCr", "CC_MATCH_DEPLOY_CR", "match.deployCr", KnobClass.BEHAVIOR),
            new Knob("cc.match.fortressDp", "CC_MATCH_FORTRESS_DP", "match.fortressDp", KnobClass.BEHAVIOR),
            // Per-wave DP scale on the fortress curve
            new Knob("cc.match.fortressWaveScale", "CC_MATCH_FORTRESS_WAVE_SCALE", "match.fortressWaveScale", KnobClass.BEHAVIOR),
            // live-roster wave DP — the roster seat DP at
            // which SCALED fortress waves reach full size (blank/0 = off = static).
            new Knob("cc.match.waveRosterFullDp", "CC_MATCH_WAVE_ROSTER_FULL_DP", "match.waveRosterFullDp", KnobClass.BEHAVIOR),
            // The host's scaling modifier on that live-roster share
            // (blank = 1 = as computed; needs waveRosterFullDp above 0 on a fortress, else the boot refuses).
            new Knob("cc.match.waveRosterMultiplier", "CC_MATCH_WAVE_ROSTER_MULTIPLIER", "match.waveRosterMultiplier", KnobClass.BEHAVIOR),
            // Wave-category modifier on the fortress chain (blank = remnant)
            new Knob("cc.match.waveCategory", "CC_MATCH_WAVE_CATEGORY", "match.waveCategory", KnobClass.BEHAVIOR),
            // Endless mode — the wave chain wraps after its last clear (default ON).
            new Knob("cc.match.endless", "CC_MATCH_ENDLESS", "match.endless", KnobClass.BEHAVIOR),
            // The client-OPTIONAL mod ids (comma list) the serve publishes on the probe
            // (MARK_MODSET_OPTIONAL) but never loads nor folds; the wrapper mirrors them (serve-mods-lib).
            new Knob("cc.mods.optional", "CC_MODS_OPTIONAL", "mods.optional", KnobClass.BEHAVIOR),
            // The foe-cloak lead/wait A/B (WireKnob.FOE_CLOAK_LEAD) — the boot value of
            // the wire-delivered client knob (WireKnobState); /knob flips it live.
            new Knob("cc.predict.foeCloakLead", "CC_PREDICT_FOE_CLOAK_LEAD", "predict.foeCloakLead", KnobClass.BEHAVIOR),
            // The battle terrain word + level (WireKnob.TERRAIN / TERRAIN_LEVEL) —
            // boot values of the wire-delivered knobs; /terrain (or /knob) flips them live.
            new Knob("cc.match.terrain", "CC_MATCH_TERRAIN", "match.terrain", KnobClass.BEHAVIOR),
            new Knob("cc.match.terrainLevel", "CC_MATCH_TERRAIN_LEVEL", "match.terrainLevel", KnobClass.BEHAVIOR),
            // The asteroid-field population (WireKnob.ASTEROIDS) — boot value of
            // the wire-delivered knob; /knob asteroids flips it live (the keeper re-targets next tick).
            new Knob("cc.match.asteroids", "CC_MATCH_ASTEROIDS", "match.asteroids", KnobClass.BEHAVIOR),
            // The asteroid keeper's replay seed (per-round Random =
            // seed ^ round*golden) + the at-rest round-robin REFRESH cadence (ticks; 0 = OFF, default 6).
            new Knob("cc.server.asteroidSeed", "CC_SERVER_ASTEROID_SEED", "server.asteroidSeed", KnobClass.BEHAVIOR),
            new Knob("cc.server.asteroidRefreshTicks", "CC_SERVER_ASTEROID_REFRESH_TICKS", "server.asteroidRefreshTicks", KnobClass.BEHAVIOR),
            new Knob("cc.predict.mapHalf", "CC_PREDICT_MAP_HALF", "map.half", KnobClass.BEHAVIOR),
            new Knob("cc.predict.correctivesOff", "CC_PREDICT_CORRECTIVES_OFF", "predict.correctivesOff", KnobClass.BEHAVIOR),
            new Knob("cc.predict.eventReplay", "CC_PREDICT_EVENT_REPLAY", "predict.eventReplay", KnobClass.BEHAVIOR),
            new Knob("cc.server.officerSkills", "CC_SERVER_OFFICER_SKILLS", "server.officerSkills", KnobClass.BEHAVIOR),
            new Knob("cc.server.officerSkillsAll", "CC_SERVER_OFFICER_SKILLS_ALL", "server.officerSkillsAll", KnobClass.BEHAVIOR),
            // demo/fallback-lane hull variant; descriptor rows stay authored truth
            new Knob("cc.server.demoVariant", "CC_SERVER_DEMO_VARIANT", "server.demoVariant", KnobClass.BEHAVIOR),
            new Knob("cc.server.deployFlyIn", "CC_SERVER_DEPLOY_FLYIN", "server.deployFlyIn", KnobClass.BEHAVIOR),
            new Knob("cc.server.engineBornGrowth", "CC_SERVER_ENGINE_BORN_GROWTH", "server.engineBornGrowth", KnobClass.BEHAVIOR),
            // Wave-side engine admiral (default ON; "false" = no commander)
            new Knob("cc.server.waveAdmiral", "CC_SERVER_WAVE_ADMIRAL", "server.waveAdmiral", KnobClass.BEHAVIOR),
            new Knob("cc.server.waveClearRoundEnd", "CC_SERVER_WAVE_CLEAR_ROUND_END", "server.waveClearRoundEnd", KnobClass.BEHAVIOR),
            new Knob("cc.server.pilotedCloakWallClock", "CC_SERVER_PILOTED_CLOAK_WALL_CLOCK", "server.pilotedCloakWallClock", KnobClass.BEHAVIOR),
            new Knob("cc.server.mountSelectedFlag", "CC_SERVER_MOUNT_SELECTED_FLAG", "server.mountSelectedFlag", KnobClass.BEHAVIOR),
            new Knob("cc.server.fireLane", "CC_SERVER_FIRE_LANE", "server.fireLane", KnobClass.BEHAVIOR),
            new Knob("cc.server.fireClassRouting", "CC_SERVER_FIRE_CLASS_ROUTING", "server.fireClassRouting", KnobClass.BEHAVIOR),
            // The declared-shot press pad's boot kill switch (default on)
            new Knob("cc.server.declPad", "CC_SERVER_DECL_PAD", "server.declPad", KnobClass.BEHAVIOR),
            new Knob("cc.server.fireClassOverride", "CC_SERVER_FIRE_CLASS_OVERRIDE", "server.fireClassOverride", KnobClass.BEHAVIOR),
            new Knob("cc.server.onFireTruth", "CC_SERVER_ONFIRE_TRUTH", "server.onFireTruth", KnobClass.BEHAVIOR),
            new Knob("cc.server.onFireShadow", "CC_SERVER_ONFIRE_SHADOW", "server.onFireShadow", KnobClass.BEHAVIOR),
            new Knob("cc.server.hulkPoseEveryTicks", "CC_SERVER_HULK_POSE_EVERY_TICKS", "hulk.poseEveryTicks", KnobClass.BEHAVIOR),
            new Knob("cc.server.hulkPoseBurstCadenceTicks", "CC_SERVER_HULK_POSE_BURST_CADENCE_TICKS", "hulk.poseBurstCadenceTicks", KnobClass.BEHAVIOR),
            // The asteroid lane's bump/hp-row cadence cap, parted from the hulk knob (default = it)
            new Knob("cc.server.asteroidBumpCadenceTicks", "CC_SERVER_ASTEROID_BUMP_CADENCE_TICKS", "server.asteroidBumpCadenceTicks", KnobClass.BEHAVIOR),
            new Knob("cc.server.aimFanEveryTicks", "CC_SERVER_AIM_FAN_EVERY_TICKS", "aim.fanEveryTicks", KnobClass.BEHAVIOR),
            new Knob("cc.server.roundStartGate", "CC_SERVER_ROUND_START_GATE", "server.roundStartGate", KnobClass.BEHAVIOR),
            // Paced late-joiner RAPP catch-up, frames/tick/session (default 32; <= 0 = legacy one-shot)
            new Knob("cc.server.rappCatchupPerTick", "CC_SERVER_RAPP_CATCHUP_PER_TICK", "server.rappCatchupPerTick", KnobClass.BEHAVIOR),
            new Knob("cc.serve.waveSpawnPerTick", "CC_SERVE_WAVE_SPAWN_PER_TICK", "serve.waveSpawnPerTick", KnobClass.BEHAVIOR),
            // Per-hull fly-in stagger window, s; 0 = off (WaveSchedule.DEFAULT_FLY_IN_STAGGER_SEC)
            new Knob("cc.serve.waveFlyInStaggerSec", "CC_SERVE_WAVE_FLYIN_STAGGER_SEC", "serve.waveFlyInStaggerSec", KnobClass.BEHAVIOR),
            // Boot warm-up round, ticks; 0 = off (ServeWarmupRound)
            new Knob("cc.serve.warmupRound", "CC_SERVE_WARMUP_ROUND", "serve.warmupRound", KnobClass.BEHAVIOR),
            new Knob("cc.transfer.enabled", "CC_TRANSFER_ENABLED", "transfer.enabled", KnobClass.BEHAVIOR),
            new Knob("cc.transfer.contest", "CC_TRANSFER_CONTEST", "transfer.contest", KnobClass.BEHAVIOR),
            new Knob("cc.transfer.beginFloorSeconds", "CC_TRANSFER_BEGIN_FLOOR_SECONDS", "transfer.beginFloorSeconds", KnobClass.BEHAVIOR),
            new Knob("cc.transfer.hullCooldownSeconds", "CC_TRANSFER_HULL_COOLDOWN_SECONDS", "transfer.hullCooldownSeconds", KnobClass.BEHAVIOR),
            new Knob("cc.transfer.podTimeoutSeconds", "CC_TRANSFER_POD_TIMEOUT_SECONDS", "transfer.podTimeoutSeconds", KnobClass.BEHAVIOR),
            // The chat-ban SOURCE HOLD arm (off | observer | match; consumer
            // PilotServeResolver, loud parse). Compiled default match.
            new Knob("cc.moderation.chatSourceHold", "CC_MODERATION_CHAT_SOURCE_HOLD", "moderation.chatSourceHold", KnobClass.BEHAVIOR),
            // The per-address seat allowance of the join door: at most N live seats per source address
            // (0 = no limit; the host's own box is exempt). Compiled default 1; a host setting, consumer
            // the door.
            new Knob("cc.moderation.maxSeatsPerSource", "CC_MODERATION_MAX_SEATS_PER_SOURCE", "moderation.maxSeatsPerSource", KnobClass.BEHAVIOR),
            // Extra exempt addresses beside the allowance: literal IPv4 / IPv6, comma-separated (a VPN hairpin's
            // tunnel gateway no interface holds). Empty default; consumer the door.
            new Knob("cc.moderation.sourceCapExempt", "CC_MODERATION_SOURCE_CAP_EXEMPT", "moderation.sourceCapExempt", KnobClass.BEHAVIOR),
            // --- W15 custom variant maker (consumer CustomVariantConfig) ---
            new Knob("cc.custom.submissions", "CC_CUSTOM_SUBMISSIONS", "custom.submissions", KnobClass.BEHAVIOR),
            new Knob("cc.custom.tier", "CC_CUSTOM_TIER", "custom.tier", KnobClass.BEHAVIOR),
            new Knob("cc.custom.perSeat", "CC_CUSTOM_PER_SEAT", "custom.perSeat", KnobClass.BEHAVIOR),
            new Knob("cc.custom.sModCap", "CC_CUSTOM_S_MOD_CAP", "custom.sModCap", KnobClass.BEHAVIOR),
            new Knob("cc.custom.dMods", "CC_CUSTOM_D_MODS", "custom.dMods", KnobClass.BEHAVIOR),
            new Knob("cc.custom.policyFile", "CC_CUSTOM_POLICY_FILE", "custom.policyFile", KnobClass.INFRA),
            new Knob("cc.custom.persistDir", "CC_CUSTOM_PERSIST_DIR", "custom.persistDir", KnobClass.INFRA),
            new Knob("cc.custom.exportDir", "CC_CUSTOM_EXPORT_DIR", "custom.exportDir", KnobClass.INFRA),
            // The dev catalog's live promote dir — SYSPROP ONLY (no properties key, no
            // CC_* env: the public operator surfaces cannot teach it); consumer CatalogPromotionLane
            new Knob("cc.catalog.promoteDir", null, null, KnobClass.INFRA),
            // --- wire shape (both ends coupled) ---
            new Knob("cc.net.transport", "CC_NET_TRANSPORT", "net.transport", KnobClass.BEHAVIOR),
            new Knob("cc.net.sendMode", "CC_NET_SEND_MODE", "net.sendMode", KnobClass.BEHAVIOR),
            new Knob("cc.net.decoupledSend", null, null, KnobClass.BEHAVIOR),
            new Knob("cc.net.socketLiveness", "CC_NET_SOCKET_LIVENESS", "net.socketLiveness", KnobClass.BEHAVIOR),
            new Knob("cc.net.doorFingerprint", "CC_NET_DOOR_FINGERPRINT", "net.doorFingerprint", KnobClass.BEHAVIOR),
            new Knob("cc.net.snapDelta", "CC_NET_SNAP_DELTA", "net.snapDelta", KnobClass.BEHAVIOR),
            new Knob("cc.net.snapDeflate", "CC_NET_SNAP_DEFLATE", "net.snapDeflate", KnobClass.BEHAVIOR),
            new Knob("cc.net.snapKeyframeTicks", "CC_NET_SNAP_KEYFRAME_TICKS", "net.snapKeyframeTicks", KnobClass.BEHAVIOR),
            new Knob("cc.net.armorCells", "CC_NET_ARMOR_CELLS", "net.armorCells", KnobClass.BEHAVIOR),
            new Knob("cc.net.armorCellsTickBytes", "CC_NET_ARMOR_CELLS_TICK_BYTES", "net.armorCellsTickBytes", KnobClass.BEHAVIOR),
            new Knob("cc.net.armorCellsRedundancy", "CC_NET_ARMOR_CELLS_REDUNDANCY", "net.armorCellsRedundancy", KnobClass.BEHAVIOR),
            new Knob("cc.net.armorCellsRepairBytes", "CC_NET_ARMOR_CELLS_REPAIR_BYTES", "net.armorCellsRepairBytes", KnobClass.BEHAVIOR),
            new Knob("cc.net.snapPayloadCap", "CC_NET_SNAP_PAYLOAD_CAP", "net.snapPayloadCap", KnobClass.BEHAVIOR),
            new Knob("cc.net.downEveryTicks", "CC_NET_DOWN_EVERY_TICKS", "net.downEveryTicks", KnobClass.BEHAVIOR),
            // --- round/reset family (ServerConfig cascade since M2b S3; the
            // demBeamSource hand-cascade keeps sysprop+env only — its resolver stays throw-on-invalid
            // and reads no file tier, so the row registers no fileKey) ---
            new Knob("cc.server.roundsPerSocket", "CC_SERVER_ROUNDS_PER_SOCKET", "server.roundsPerSocket", KnobClass.BEHAVIOR),
            new Knob("cc.server.retryBudgetTicks", "CC_SERVER_RETRY_BUDGET_TICKS", "server.retryBudgetTicks", KnobClass.BEHAVIOR),
            new Knob("cc.server.matchReset", "CC_SERVER_MATCH_RESET", "server.matchReset", KnobClass.BEHAVIOR),
            new Knob("cc.server.emitMatchResetFrame", "CC_SERVER_EMIT_MATCH_RESET_FRAME", "server.emitMatchResetFrame", KnobClass.BEHAVIOR),
            new Knob("cc.server.awaitReentry", "CC_SERVER_AWAIT_REENTRY", "server.awaitReentry", KnobClass.BEHAVIOR),
            new Knob("cc.server.perSideRoundEnd", "CC_SERVER_PER_SIDE_ROUND_END", "server.perSideRoundEnd", KnobClass.BEHAVIOR),
            new Knob("cc.server.stableRoster", "CC_SERVER_STABLE_ROSTER", "server.stableRoster", KnobClass.BEHAVIOR),
            new Knob("cc.server.crReadback", "CC_SERVER_CR_READBACK", "server.crReadback", KnobClass.BEHAVIOR),
            new Knob("cc.server.demBeamSource", "CC_SERVER_DEM_BEAM_SOURCE", null, KnobClass.BEHAVIOR),
            // --- instruments (lanes may delta; registered deltas only from M3) ---
            new Knob("cc.parity.walkEvery", "CC_PARITY_WALK_EVERY", "parity.walkEvery", KnobClass.INSTRUMENT),
            new Knob("cc.parity.walkMods", null, null, KnobClass.INSTRUMENT),
            new Knob("cc.net.sinkPath", "CC_NET_SINK_PATH", "net.sinkPath", KnobClass.INSTRUMENT),
            // public-serve .ccwr byte cap (MiB; 0 = unbounded)
            new Knob("cc.net.sinkMaxMiB", "CC_NET_SINK_MAX_MIB", "net.sinkMaxMiB", KnobClass.INSTRUMENT),
            // per-run GPEE watch log (retire at the collimator verdict)
            new Knob("cc.server.gpeeLogPath", "CC_SERVER_GPEE_LOG_PATH", "server.gpeeLogPath", KnobClass.INSTRUMENT),
            // The type=shielded hull-source row (the specimen arena's
            // decoy law) — a headless -ArenaSeat delta, never config.
            new Knob("cc.server.shieldedRow", "CC_SERVER_SHIELDED_ROW", "server.shieldedRow", KnobClass.INSTRUMENT),
            // The dmg-taken hit* tokens (DamageTakenModifier tap), default off.
            new Knob("cc.server.hitCellRow", "CC_SERVER_HIT_CELL_ROW", "server.hitCellRow", KnobClass.INSTRUMENT),
            new Knob("cc.server.clientDebug", "CC_SERVER_CLIENT_DEBUG", "server.clientDebug", KnobClass.INSTRUMENT),
            new Knob("cc.server.shieldContactLog", "CC_SERVER_SHIELD_CONTACT_LOG", "server.shieldContactLog", KnobClass.INSTRUMENT),
            new Knob("cc.rngpin.seed", "CC_RNGPIN_SEED", "rngpin.seed", KnobClass.INSTRUMENT),
            new Knob("cc.rngpin.keyed", "CC_RNGPIN_KEYED", "rngpin.keyed", KnobClass.INSTRUMENT),
            // The host's RNG pin switch, decided by the premain on a
            // postured boot. BEHAVIOR, not INSTRUMENT: the pins move the sim's RNG streams, so the behavior
            // hash carries the mode and the FILE row is the OFF lever (a tagless sysprop override refuses).
            // LITERALS, never RngPinKnobs' constants: run-real-pilot-server.ps1's
            // posture census regex reads this file as TEXT and fails closed on an unparsed row.
            new Knob("cc.rngpin.enabled", "CC_RNGPIN_ENABLED", "rngpin.enabled", KnobClass.BEHAVIOR),
            // The host's MagicLib seam switch, HOOK-0053, decided by the premain
            // through the SAME tier walk as rngpin.enabled (default ON; the FILE row false = the OFF lever). BEHAVIOR:
            // an applied seam changes which MagicLib abilities fire on a piloted hull, so the behavior hash carries it.
            // LITERALS, never TransformRegistry's constants (the posture census regex reads this file as TEXT).
            new Knob("cc.magicseam.enabled", "CC_MAGICSEAM_ENABLED", "magicseam.enabled", KnobClass.BEHAVIOR),
            // the player-ship window's host row (DEFAULT OFF): registered BEFORE its premain lands, so no postured
            // serve refuses it as an unknown sysprop (the magicseam row's first merged build did exactly that)
            new Knob("cc.pilotship.enabled", "CC_PILOTSHIP_ENABLED", "pilotship.enabled", KnobClass.BEHAVIOR),
            // The Math.random() caller census window (sim ticks) — premain-read
            // (sysprop / env, no file tier), a diagnostic re-sim delta; the stream is untouched.
            new Knob("cc.rngpin.census", "CC_RNGPIN_CENSUS", null, KnobClass.INSTRUMENT),
            // The census's per-site stack capture (site-name substrings) —
            // premain-read, refused without the census window; the stream is untouched.
            new Knob("cc.rngpin.censusStack", "CC_RNGPIN_CENSUS_STACK", null, KnobClass.INSTRUMENT),
            // The reel a re-simulation boot replays (ReplayBoot);
            // INSTRUMENT, never BEHAVIOR: the re-run must hash-equal the recording it replays.
            new Knob("cc.resim.record", "CC_RESIM_RECORD", "resim.record", KnobClass.INSTRUMENT),
            // cc.wave.seed is NOT a registry row (an INSTRUMENT row here let a postured live boot accept an
            // untagged sysprop / env wave seed that enforce() refuses): the wave inflater reads it through
            // ServerConfig.resolve, and a re-simulation boot (ReplayBoot) FORCES the recorded seed as a
            // sysprop AFTER enforce() ran, so the force never needed the row.
            new Knob("cc.measure.boostSample", "CC_MEASURE_BOOST_SAMPLE", "measure.boostSample", KnobClass.INSTRUMENT),
            new Knob("cc.measure.systemSample", "CC_MEASURE_SYSTEM_SAMPLE", "measure.systemSample", KnobClass.INSTRUMENT),
            new Knob("cc.measure.afGateSample", "CC_MEASURE_AF_GATE_SAMPLE", "measure.afGateSample", KnobClass.INSTRUMENT),
            // sysprop-or-env read (EngineBackedLiveWorldSnapshotSource field init); no file tier.
            new Knob("cc.probe.fluxReadInstant", "CC_PROBE_FLUX_READ_INSTANT", null, KnobClass.INSTRUMENT),
            // sysprop-only (OwnCarrierZeroFluxProbe.ARMED, Boolean.getBoolean); a one-flight
            // -ExtraJvmFlags delta — unregistered it REFUSED the postured boot.
            new Knob("cc.probe.ownCarrierZeroFlux", null, null, KnobClass.INSTRUMENT),
            // sysprop-or-env read (FragmentSwarmCensusProbe.ARMED); the
            // headless fragment-swarm census — a one-serve -ExtraJvmFlags delta, never config.
            new Knob("cc.probe.fragmentSwarmCensus", "CC_PROBE_FRAGMENT_SWARM_CENSUS", null, KnobClass.INSTRUMENT),
            // sysprop-or-env read (MoteCensusProbe.ARMED); the headless mote
            // census for the Ziggurat generator-stuck defect — a one-serve -ExtraJvmFlags delta, never config.
            new Knob("cc.probe.moteCensus", "CC_PROBE_MOTE_CENSUS", null, KnobClass.INSTRUMENT),
            // sysprop-or-env read (DwellerCensusProbe.ARMED); the headless
            // Shrouded Dweller census for the fidelity rig — a one-serve -ExtraJvmFlags delta, never config.
            new Knob("cc.probe.dwellerCensus", "CC_PROBE_DWELLER_CENSUS", null, KnobClass.INSTRUMENT),
            // sysprop-or-env read (FlareGlowProbe.ARMED); the headless flare glow
            // census (ACCELERATE command flag vs the engine glow's own flag + flame level) — never config.
            new Knob("cc.probe.flareGlow", "CC_PROBE_FLARE_GLOW", null, KnobClass.INSTRUMENT),
            // env-only read today (PPICS getenv site); sysprop key reserved for the M2 cascade
            new Knob("cc.rig.waveProbe", "CC_RIG_WAVE_PROBE", null, KnobClass.INSTRUMENT),
            new Knob("cc.serve.overrunLog", "CC_SERVE_OVERRUN_LOG", "serve.overrunLog", KnobClass.INSTRUMENT),
            new Knob("cc.git.head", "CC_GIT_HEAD", "git.head", KnobClass.INSTRUMENT),
            new Knob("cc.modset.hash", "CC_MODSET_HASH", "modset.hash", KnobClass.INSTRUMENT),
            new Knob("cc.content.fingerprint", "CC_CONTENT_FINGERPRINT", "content.fingerprint", KnobClass.INSTRUMENT),
            new Knob("cc.engine.build", "CC_ENGINE_BUILD", "engine.build", KnobClass.INSTRUMENT),
            new Knob("cc.server.peerGrade", "CC_SERVER_PEER_GRADE", "server.peerGrade", KnobClass.INSTRUMENT),
            new Knob("cc.match.seat1Name", null, null, KnobClass.INSTRUMENT),
            new Knob("cc.match.seat2Name", null, null, KnobClass.INSTRUMENT),
            // --- infra (per-lane by nature; registered shapes, not fixed values) ---
            new Knob("cc.match.descriptor", "CC_MATCH_DESCRIPTOR", "match.descriptor", KnobClass.INFRA),
            new Knob("cc.server.bindHost", "CC_SERVER_BIND_HOST", "server.bindHost", KnobClass.INFRA),
            new Knob("cc.server.bindPort", "CC_SERVER_BIND_PORT", "server.bindPort", KnobClass.INFRA),
            new Knob("cc.server.npeer", "CC_SERVER_NPEER", "server.npeer", KnobClass.INFRA),
            new Knob("cc.server.seat2PortFile", "CC_SERVER_SEAT2_PORT_FILE", "server.seat2PortFile", KnobClass.INFRA),
            new Knob("cc.server.commandFile", "CC_SERVER_COMMAND_FILE", "server.commandFile", KnobClass.INFRA),
            new Knob("cc.server.opsFile", "CC_SERVER_OPS_FILE", "server.opsFile", KnobClass.INFRA),
            // D-SEATRACE: ships first-free admission skips — the seat
            // a lane's own pinned client claims (render watch: window-1's -ShipId); blank = none.
            new Knob("cc.server.reservedShipIds", "CC_SERVER_RESERVED_SHIP_IDS", "server.reservedShipIds", KnobClass.INFRA),
            new Knob("cc.io.decoupled", "CC_IO_DECOUPLED", "io.decoupled", KnobClass.INFRA),
            // JFR block-catcher destination (per-run path by nature; blank/off = OFF)
            new Knob("cc.jfr.blocks", "CC_JFR_BLOCKS", "jfr.blocks", KnobClass.INFRA),
            new Knob("cc.bind.strict", "CC_BIND_STRICT", "bind.strict", KnobClass.INFRA),
            new Knob("cc.jvm.role", null, null, KnobClass.INFRA),
            // env twin registered at M3-A: the premain genuinely reads
            // CC_TRANSFORM_ENABLE; real lanes set the sysprop.
            new Knob("cc.transform.enable", "CC_TRANSFORM_ENABLE", null, KnobClass.INFRA));

    private PostureRegistry() {
    }

    /** The registered census, for tests and (from M3) the unknown-key scan. */
    public static List<Knob> knobs() {
        return KNOBS;
    }

    /** Key-indexed census behind the lookup below. */
    private static final java.util.Map<String, Knob> BY_KEY;
    static {
        java.util.Map<String, Knob> byKey = new java.util.HashMap<>();
        for (Knob k : KNOBS) {
            byKey.put(k.key(), k);
        }
        BY_KEY = java.util.Map.copyOf(byKey);
    }

    /**
     * The registered knob for a sysprop key, or null when the key is not in the census —
     * {@link ServerConfig}'s F4 cross-check refuses a consumer resolve whose (env, fileKey) forks
     * from the registered triple; unregistered keys are not this census's to police.
     */
    static Knob byKey(String key) {
        return BY_KEY.get(key);
    }

    /**
     * Print the complete resolved posture: one {@code posture key=... value=... source=...
     * class=...} line per registered knob (sorted by key), then {@code posture-rows=N} and
     * {@code posture-hash=<sha256>}. External values print raw; untouched knobs print
     * {@code (compiled)}. Never throws past a strict-config refuse (which any resolve would hit
     * anyway) — resolution here reads raw strings only, no consumer parses run.
     */
    public static void dump(PrintStream out) {
        List<Knob> sorted = sortedKnobs();
        StringBuilder hashed = new StringBuilder();
        for (Knob k : sorted) {
            String[] resolved = resolveForDump(k);
            String value = resolved[0];
            out.println("posture key=" + k.key() + " value=" + value + " source=" + resolved[1]
                    + " class=" + k.cls());
            hashed.append(k.key()).append('=').append(value).append('\n');
        }
        out.println("posture-rows=" + sorted.size());
        out.println("posture-hash=" + sha256Hex(hashed.toString()));
        // M3-A: the BEHAVIOR-rows-only hash — lane-invariant by construction, so it
        // is the machine parity proof across serve lanes (INSTRUMENT/INFRA deltas move only the
        // full posture-hash above, which stays the roll golden).
        out.println("posture-hash-behavior=" + behaviorHash());
        out.println("posture-mode=" + (ServerConfig.postured() ? "postured" : "unpostured"));
        String tag = experimentTagOrNull();
        out.println("posture-experiment=" + (tag == null ? "none" : tag));
        out.println("posture-config-source=" + ServerConfig.sourceDescription());
    }

    /**
     * The BEHAVIOR-rows-only posture hash — the exact value {@link #dump} prints as
     * {@code posture-hash-behavior} (SHA-256 hex over {@code key=value\n} of every BEHAVIOR knob,
     * sorted by key, values resolved + sanitized as the dump does), so a recording header can carry
     * the behavior identity without parsing the dump.
     */
    public static String behaviorHash() {
        StringBuilder behaviorHashed = new StringBuilder();
        for (Knob k : sortedKnobs()) {
            if (k.cls() == KnobClass.BEHAVIOR) {
                behaviorHashed.append(k.key()).append('=').append(resolveForDump(k)[0]).append('\n');
            }
        }
        return sha256Hex(behaviorHashed.toString());
    }

    private static List<Knob> sortedKnobs() {
        List<Knob> sorted = new ArrayList<>(KNOBS);
        sorted.sort(Comparator.comparing(Knob::key));
        return sorted;
    }

    /** {@code {value, sourceTier}} for the dump: the raw tier value (sysprop > env > file), else {@code (compiled)}. */
    private static String[] resolveForDump(Knob k) {
        String value;
        String sourceTier;
        String v = System.getProperty(k.key());
        if (v != null && !v.isBlank()) {
            value = v.trim();
            sourceTier = "sysprop";
        } else if (k.env() != null && (v = System.getenv(k.env())) != null && !v.isBlank()) {
            value = v.trim();
            sourceTier = "env";
        } else if (k.fileKey() != null && (v = ServerConfig.peekFile(k.fileKey())) != null
                && !v.isBlank()) {
            value = v.trim();
            sourceTier = "file";
        } else {
            value = "(compiled)";
            sourceTier = "compiled";
        }
        // Env/file values are operator-supplied bytes headed for a log
        // line AND the hash input — single-line printables only, bounded (the security doc's
        // printable-text law). Identity on every sane value, so hash-invariance is unaffected.
        return new String[] {sanitizeValue(value), sourceTier};
    }

    /**
     * The deliberate-experiment tag: {@code cc.posture.experiment} sysprop,
     * else {@code CC_POSTURE_EXPERIMENT} env; blank = absent; sanitized (it rides a dump line).
     * NOT a registered row and never hashed — an A/B stamp, not posture.
     */
    static String experimentTagOrNull() {
        String v = System.getProperty("cc.posture.experiment");
        if (v == null || v.isBlank()) {
            v = System.getenv("CC_POSTURE_EXPERIMENT");
        }
        return (v == null || v.isBlank()) ? null : sanitizeValue(v.trim());
    }

    /** M3 enforcement refusal: all violations of one boot, collected. */
    public static final class PostureViolationException extends RuntimeException {
        PostureViolationException(String message) {
            super(message);
        }
    }

    /**
     * Exempt families for the unknown-key scan — the adjudicated list, VERBATIM.
     * Sysprop side: the tag family, the premain's dynamic per-jar keys, the
     * client-predict family, the premain jar pointer, and the client-mod HUD knob that reaches
     * serve JVMs only as an unread JTO-injected sysprop. Env side: tag + client-predict +
     * harness families (wrapper/client-side by convention) and the named harness channels.
     * Amended after the first live refusal: {@code CC_LOOP_*} + the usage-meter dir —
     * the dev-loop harness env that rides EVERY shell a loop
     * session spawns, so a postured serve launched from a session would otherwise always refuse.
     * {@code CC_SERVER_SEAT2_BIND_HOST/PORT} stay deliberately UN-exempt — a retired seam,
     * asserted-empty; a set value SHOULD refuse loudly.
     * Amended: {@code cc.transform.armed} + the
     * {@code cc.transform.sha.*} / {@code cc.transform.applied.*} families are the premain's IN-PROCESS
     * receipts (set by the agent at transform time, read by {@code MatchRecordMeta} into the recording
     * header) — never operator input; un-exempt, every postured boot with the premain armed refused as
     * "unknown sysprop" (no contract row drove a postured child JVM with a config file, so none caught it).
     * Amended: {@code cc.transform.rngpin} is the premain's pin-decision receipt
     * on a postured boot (the recording header's {@code rng.pin.enabled} / {@code rng.seed.source}); the
     * holder jar's hash {@code cc.transform.sha.holder} rides the {@code cc.transform.sha.} family.
     */
    private static final List<String> EXEMPT_SYSPROP_PREFIXES =
            List.of("cc.posture.", "cc.transform.jar.", "cc.transform.sha.", "cc.transform.applied.", "cc.predict.");
    // cc.magic.seam: the premain's MagicLib seam RECEIPT (off | armed | applied:<sha> |
    // disarmed:<reason>), set on EVERY postured boot before this census runs — a receipt, never a knob (the knob is
    // cc.magicseam.enabled above). The first merged stage-1 build refused every postured serve here (S1a's HAZARD S1).
    // cc.pilot.ship: the premain's player-ship RECEIPT (off | armed | applied:<k> | refused:<reason> |
    // latched:leak), set on EVERY postured boot too (the row defaults OFF, so the receipt reads "off") — the same S1a hazard
    // caught at the pre-mint read, before the fleet merged (the knob is cc.pilotship.enabled above).
    private static final java.util.Set<String> EXEMPT_SYSPROPS =
            java.util.Set.of("cc.premain.jar", "cc.debug.serverHullBar", "cc.transform.armed", "cc.transform.rngpin",
                    "cc.magic.seam", "cc.pilot.ship");
    // CC_CLIENT_ (widening an earlier single-name exemption): the arm's CLIENT-ONLY ferries
    // (CC_CLIENT_JVM_FLAGS, CC_CLIENT_JRE_CC) — window launchers read them, the serve never
    // does, yet the detached armer's env reaches this JVM too. The first -ClientJfr arm AND
    // the first -ClientJreCc arm both died here as "unknown env" — prefix-exempt the whole
    // CC_CLIENT_ family so the next client-window ferry cannot repeat it. Inert on the serve by design.
    private static final List<String> EXEMPT_ENV_PREFIXES =
            List.of("CC_POSTURE_", "CC_PREDICT_", "CC_WATCH_", "CC_JOIN_", "CC_LOOP_", "CC_CLIENT_");
    // CC_KIT_ENVSYNC: the joiner kit's join-cc.bat uses `setx CC_KIT_ENVSYNC
    // explorer|cleared` purely as the WM_SETTINGCHANGE broadcast that makes Explorer reload
    // HKCU\Environment for its explorer-launched game window; the bat then
    // `reg delete`s the key WITHOUT a broadcast, so Explorer keeps `cleared` LIVE until the next
    // broadcast and every process started from Explorer/Start after any kit launch inherits it —
    // the loop launcher, the ops GUI, a hand-started serve. The first postured serve under such a
    // shell (drop self-test) died here as "unknown env". Nothing server-side reads it.
    // CC_RUN_SNAP_SSD / CC_SERVE_JDK_SSD / CC_SSD_ROOT / CC_JDK_PIN: the launch wrappers'
    // OWN levers (where the serve JDK and the run snapshot live, which JDK is pinned) — read by
    // PowerShell only, never by this JVM. The Ops GUI's Start always boots SUPERVISED and the supervisor starts
    // every life on the inherited environment, so one of them set in the GUI's parent shell refused every postured
    // Start as "unknown env" (the scrub covered one name on the direct branch only; it is deleted for this
    // line — one mechanism, both branches). Rows: PostureEnforceContractRows.exemptFamiliesPass,
    // verify-ops-serve-preflight.ps1 r5.
    private static final java.util.Set<String> EXEMPT_ENVS = java.util.Set.of(
            "CC_STAND_HOLDS_BUILD_MUTEX", "CC_PARITY_FRAME_LEDGER", "CC_SERVER_EXTRA_JVM_FLAGS",
            "CC_MATCH_SEAT1NAME", "CC_MATCH_SEAT2NAME", "CC_USAGE_METER_DATA_DIR", "CC_KIT_ENVSYNC",
            "CC_RUN_SNAP_SSD", "CC_SERVE_JDK_SSD", "CC_SSD_ROOT", "CC_JDK_PIN");

    /** Registered env twins — an env var in this set is census-known, never "unknown". */
    private static final java.util.Set<String> REGISTERED_ENVS;
    static {
        java.util.Set<String> envs = new java.util.HashSet<>();
        for (Knob k : KNOBS) {
            if (k.env() != null) {
                envs.add(k.env());
            }
        }
        REGISTERED_ENVS = java.util.Set.copyOf(envs);
    }

    /**
     * Lane-posture M3 enforcement:
     * on a POSTURED boot (a config file is configured — both real serve lanes, by wrapper
     * construction since M2a) refuse (i) any {@code cc.*} sysprop / {@code CC_*} env var that is
     * neither registered in this census nor on the exempt lists, and (ii) any BEHAVIOR-class row
     * resolved from the sysprop/env tier without an experiment tag — behavior posture rides the
     * config file; {@code cc.posture.experiment=<tag>} lifts (ii) for a deliberate A/B (the dump's
     * un-hashed {@code posture-experiment} line is the stamp). The tag never lifts (i): an
     * experiment on an unregistered knob registers it first. File-less boots (rigs, A/B debug
     * harnesses, contract-test children) are unpostured &rArr; this is inert. Violations are
     * COLLECTED — one throw names them all.
     */
    public static void enforce() {
        enforce(System.getProperties(), System.getenv(), ServerConfig.postured(),
                experimentTagOrNull());
    }

    /** Injectable overload of {@link #enforce()} — the contract-test seam. */
    public static void enforce(java.util.Properties sysprops, java.util.Map<String, String> env,
            boolean postured, String experimentTag) {
        if (!postured) {
            return;
        }
        List<String> violations = new ArrayList<>();
        for (String key : sysprops.stringPropertyNames()) {
            String v = sysprops.getProperty(key);
            if (!key.startsWith("cc.") || v == null || v.isBlank() || BY_KEY.containsKey(key)
                    || EXEMPT_SYSPROPS.contains(key) || hasPrefix(key, EXEMPT_SYSPROP_PREFIXES)) {
                continue;
            }
            violations.add("unknown sysprop " + key + "=" + sanitizeValue(v.trim())
                    + " (not in the PostureRegistry census, not exempt)");
        }
        for (java.util.Map.Entry<String, String> e : env.entrySet()) {
            String name = e.getKey();
            String v = e.getValue();
            if (!name.startsWith("CC_") || v == null || v.isBlank() || REGISTERED_ENVS.contains(name)
                    || EXEMPT_ENVS.contains(name) || hasPrefix(name, EXEMPT_ENV_PREFIXES)) {
                continue;
            }
            violations.add("unknown env " + name + "=" + sanitizeValue(v.trim())
                    + " (not a registered env twin, not exempt)");
        }
        if (experimentTag == null) {
            for (Knob k : KNOBS) {
                if (k.cls() != KnobClass.BEHAVIOR) {
                    continue;
                }
                String v = sysprops.getProperty(k.key());
                String tier = (v != null && !v.isBlank()) ? "sysprop" : null;
                if (tier == null && k.env() != null) {
                    v = env.get(k.env());
                    tier = (v != null && !v.isBlank()) ? "env" : null;
                }
                if (tier != null) {
                    violations.add("BEHAVIOR override " + k.key() + " via " + tier
                            + " without an experiment tag (behavior posture rides the config file)");
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new PostureViolationException("posture enforcement refused this postured boot"
                    + " — " + violations.size()
                    + " violation(s): " + String.join("; ", violations)
                    + ". Fix: register the knob / move the value to the config file / set"
                    + " cc.posture.experiment=<tag> for a deliberate A/B"
                    + " (unpostured lanes are exempt by construction).");
        }
    }

    private static boolean hasPrefix(String name, List<String> prefixes) {
        for (String p : prefixes) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** Strip control chars (single-line law) and cap length; identity on every sane knob value. */
    static String sanitizeValue(String raw) {
        StringBuilder clean = new StringBuilder(Math.min(raw.length(), MAX_VALUE_CHARS));
        for (int i = 0; i < raw.length() && clean.length() < MAX_VALUE_CHARS; i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                clean.append(c);
            }
        }
        return clean.toString();
    }

    /** A posture value is a short token/path; anything near this is garbage input, not config. */
    private static final int MAX_VALUE_CHARS = 512;

    static String sha256Hex(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
