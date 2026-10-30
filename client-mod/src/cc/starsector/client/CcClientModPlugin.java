package cc.starsector.client;

import cc.starsector.client.editor.EditorHost;
import cc.starsector.client.net.PredictSession;
import cc.starsector.client.net.ServerSnapshotClient;
import cc.starsector.server.client.PilotIntentClientSession;
import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.campaign.CampaignPlugin;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * Mod entry point for the dedicated-server client.
 *
 * <p>Spike 1 needed no application-load wiring (the scripted puppet plugin was
 * registered per-mission). Spike 2 opens the real socket: on application load this
 * constructs a shared {@link ServerSnapshotClient} pointed at the running dedicated
 * server and starts its background reader. The per-mission
 * {@link ServerFedPuppetCombatPlugin} reads {@link #serverClient()} and feeds the
 * decoded server pose onto a live combat entity each frame.
 *
 * <p>The connection target is resolved in this order:
 * <ol>
 *   <li>a server <b>port-file</b> ({@code cc.server.portFile} / {@code CC_SERVER_PORT_FILE})
 *       — the {@code host=}/{@code port=} file
 *       {@code DedicatedCombatServerMain --port-file} writes when it binds its
 *       ephemeral socket, so the human never copies a port by hand;</li>
 *   <li>explicit {@code cc.server.host}/{@code cc.server.port} (then
 *       {@code CC_SERVER_HOST}/{@code CC_SERVER_PORT});</li>
 *   <li>the defaults {@value #DEFAULT_HOST}:{@value #DEFAULT_PORT}.</li>
 * </ol>
 * The reader is reconnect-tolerant, so a missing/late server never breaks the game —
 * the puppet simply stays idle until frames arrive (see
 * {@link ServerFedPuppetCombatPlugin}).
 */
public class CcClientModPlugin extends BaseModPlugin {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 52525;

    private static volatile ServerSnapshotClient serverClient;

    /**
     * The faithful-pilot watch session, opened at application load for the
     * in-combat {@link PilotIntentCombatPlugin} to consume. {@code null} when no pilot endpoint is
     * configured (an ordinary launch) or the app-load connect failed.
     */
    static volatile PilotIntentClientSession pilotSession;

    /**
     * The predict-and-reconcile watch session for the in-combat
     * {@link PredictReconcileCombatPlugin} to consume. W13:
     * dialed at the player's CLICK on the Multiplayer Test
     * mission ({@link #dialPredictAtJoin}), never at launch — a launched-but-not-joining window
     * holds no seat, slot or fan-out. {@code null} when no predict endpoint ({@code cc.predict.*} /
     * {@code CC_PREDICT_*}) is configured, before the first click, mid-dial, or after a dial gave
     * up. Distinct from {@link #pilotSession} so the proven puppet/pilot path stays an untouched
     * regression baseline.
     * Package-private: {@link PredictDialLane} writes it (a fresh dial and a give-up clear it, a connect publishes it)
     * and reads it.
     */
    static volatile PredictSession predictSession;

    @Override
    public void onApplicationLoad() {
        // Law A: the engine's variant registry is complete HERE (SpecStore load precedes
        // the plugin loop) and no VDEF has folded yet — snapshot the boot keyset FIRST, once.
        BootVariantRegistry.snapshotAtApplicationLoad();
        // The boot ID TABLE — the dictionary every VSUB/VDEF body is
        // indexed against and the vtab the HELLO fingerprint folds; minted here, once, with every
        // spec loaded (the same enumerators the server's RealEngineVariantFacts walks).
        ClientIdTableBoot.snapshotAtApplicationLoad();
        // The mod-set pin: the boot MOD SET (every enabled mod but cc_client, off the
        // game's own ModManagerAPI) the HELLO fingerprint folds beside vtab; the server mints the
        // same set from the launcher's list, so a joiner whose mods differ is refused at the door.
        ClientModSetBoot.snapshotAtApplicationLoad();
        magicSeamAtApplicationLoad();
        // Watch-socket placement: the legacy faithful-pilot watch
        // socket opens HERE at application load and PilotIntentCombatPlugin only consumes it. The
        // original reason ("combat's SecurityManager denies socket opens") was a misread — the
        // script classloader B denies by class NAME only, always, every phase, every thread (the
        // ATTEMPT-2 Fatal was the mod's own java.io.BufferedOutputStream ref; the build gate
        // check-b-deny-refs.ps1 keeps the jar allowlist-clean). The PREDICT lane
        // therefore dials at the player's Multiplayer Test click, never at launch — see
        // PredictDialPlanner.preparePredictDial + dialPredictAtJoin.
        // The duel ship is now standardised by MOD DATA, not a runtime hack — the
        // cc_predict mission spawns the dmod-free `mudskipper2_cc_Hellbore` variant (skin `mudskipper2_cc`,
        // which omits the RNG `ill_advised` malfunction d-mod). The old runtime
        // getHullSpec("mudskipper2").getBuiltInMods().remove("ill_advised") strip is removed; the server
        // scout builds the same dmod-free hull by reflection (run-headless-bootstrap-scout
        // prepareMudskipperDemoVariant). [[prefer-mod-content-over-runtime-hacks]].
        PilotSessionBoot.openPilotSession();
        PredictDialPlanner.preparePredictDial(); //
        // The quit-BYE JVM shutdown hook — covers EVERY System.exit path
        // (honored window close, title-screen Exit, fatal exit(1)); registered once, here at
        // application load per the spec. See registerQuitByeHook.
        registerQuitByeHook();
        // force-load the fallback-announce sink + knob parser HERE — announce
        // seams (incl. KnobParse static-init parses) can first fire mid-combat, where the script
        // SecurityManager forbids NEW class loading. Same pre-warm pattern as PilotWatchLog above.
        // ObserverMode rides the same pre-warm — the mission builder reads
        // ObserverMode.ON mid-mission (defineMission), under the script SecurityManager.
        PilotWatchLog.info("onApplicationLoad: fallback announce " + FallbackAnnounce.describe()
                + "; knob parse " + KnobParse.describe() + "; observer " + ObserverMode.describe());
        // The observer frame dump arms HERE — encoder thread, ring, nested classes,
        // ImageIO's JPEG writer and the FileChannel seam all first-touched outside the combat
        // SecurityManager (a real warmup.jpg proves the launcher-created dir). No-op unless observer + dir.
        ObserverFrameDump.startAtApplicationLoad();
        PlayerCameraLock.startAtApplicationLoad(); // rig-window camera lock, default OFF
        if (ObserverMode.ON || ObserverMode.frameDumpDir() != null) {
            PilotWatchLog.info("onApplicationLoad: frame dump " + ObserverFrameDump.describe());
        }
        // The F9 mark-frame ring arms HERE — encoder +
        // poller daemons, in-memory JPEG warm-up, warmup.jpg, every nested class, outside the combat
        // SecurityManager. One OFF row unless -Dcc.markframes.dir names a launcher-created dir.
        MarkFrameRing.startAtApplicationLoad();
        PilotWatchLog.info("onApplicationLoad: mark-frames " + MarkFrameRing.describe());
        // force-load the parity walker (and resolve its config) HERE at application load —
        // config + any loader-B denial (a NAME-based predicate on java.lang.reflect/java.io, NOT a
        // class-loading gate) surface at load, never inside advance(). Same pre-warm
        // pattern as PilotWatchLog above.
        PilotWatchLog.info("onApplicationLoad: parity walker " + ClientParityWalker.describeConfig());
        // Polish: force the keep-alive adapter's static init (privateLookupIn +
        // VarHandle resolve) HERE so the feasibility verdict is on record at load — the in-sweep
        // one-shot line still confirms first combat use.
        PilotWatchLog.info("onApplicationLoad: hulk-keepalive " + (HulkKeepAliveAdapter.isEnabled()
                ? "ENABLED " + HulkKeepAliveAdapter.describe() : "DISABLED"));
        // force-load the match-descriptor builder AND perform its one-time descriptor
        // file read + decode HERE at application load (allowlisted FileReader phase) —
        // the Janino mission script later consumes only the cached result, never file I/O or a
        // first class load mid-mission. Same pre-warm pattern as the parity walker above.
        PilotWatchLog.info("onApplicationLoad: match builder " + MatchMissionBuilder.describeConfig());
        // officer-table pre-warm — first-touch + one-time loadText/decode HERE
        // (classloader-B phase discipline), so mission build + replica spawns consume the cache.
        PilotWatchLog.info("onApplicationLoad: officer loadout " + OfficerLoadout.describeConfig());
        // Probe: force the privateLookupIn+VarHandle static init HERE
        // (the HulkKeepAliveAdapter pattern) — resolve verdict on record at load, never a
        // first-touch under the combat SecurityManager — and wire the OfficerLoadout seam
        // (seam, not a direct call: the verify-match gate compiles OfficerLoadout api-only).
        PilotWatchLog.info("onApplicationLoad: codex captain probe " + CodexCaptainProbe.describe());
        OfficerLoadout.twinProbe = CodexCaptainProbe::probeAfterTwinInstall;
        // Install the client onFire birth emitter onto every projectile spec
        // HERE, pre-combat (specs are Settings-global; weapon ctors latch the plugin at build).
        // ScriptStore resolves the emitter NAME through this jar's own URLClassLoader chain, so
        // its statics ARE ClientFireBirthSink's (statically confirmed). One receipt
        // line inside; fail-soft boolean — a failed sweep = today's scan-only decl behavior.
        ClientOnFireAttachSweep.runIfEnabled();
        // Swap the 7 audited vanilla beamEffect classes for their CcWrap*
        // subclasses HERE, pre-combat (same Settings-global spec seam as the onFire sweep; each
        // BeamWeaponRay minting a wrapper by name gets vanilla behavior until the combat window
        // binds CcReplicaEffectGate). Receipt rows inside; fail-soft = that spec stays vanilla.
        ClientBeamEffectSwap.runIfEnabled();
        // P12, everyFrame leg: same discipline for the everyFrame slot — its
        // 2 audited-LIVE classes (riftbeam's minelayer, swarm_launcher's self-force-fire) swap
        // to CcWrap* subclasses via the engine-typed BaseWeaponSpec name setter (B-legal; weapon
        // entities latch the plugin instance at build, so pre-combat timing covers combat).
        ClientEveryFrameEffectSwap.runIfEnabled();
        // force-load the fuse-burst meter (and its nested shell record) HERE —
        // same classloader-B pre-warm pattern as the parity walker above; its first real touch
        // is otherwise mid-combat inside advance().
        PilotWatchLog.info("onApplicationLoad: fuse meter " + FuseBurstMeter.describe());
        // Knob echo: catalog-only specials (stationMineAdopt /
        // stationMineIntangible, MissilePoseLane) are read silently at their class load, so
        // without this line a dropped JAVA_TOOL_OPTIONS channel is indistinguishable from a
        // default run in the archived client log (the exact 133110 off-arm failure). Absent
        // key here = that knob at its compiled default.
        PilotWatchLog.info("onApplicationLoad: predict knob echo " + ClientLaunchKnobs.describeDeliveredPredictKnobs());
        PilotWatchLog.info("onApplicationLoad: kit launch " + ClientLaunchKnobs.describeKitLaunch());
        // The OpenAL second chance — if the engine's one-shot sound init failed
        // (the joiner's LoadLibrary 126 on a relative path), re-init once with an absolute
        // org.lwjgl.librarypath; one sidelog row either way (the debrief zip ships it).
        KitSoundSecondChance.run();
        // W15 v1 THE EDITOR: the loopback responder starts HERE — a
        // daemon thread binds 127.0.0.1:0, writes saves/common/cc_editor_endpoint.data
        // (port|token) and serves the browser editor's oracle off the public API. Drill-proven
        // at load AND at the title screen. -Dcc.editor=off disables it.
        EditorHost.startAtApplicationLoad();
        PilotWatchLog.info("onApplicationLoad: editor " + EditorHost.describe());
        // window-position memory — restore this window's
        // saved desktop position (Display exists here), warm the save seam, start the poller.
        WindowPosMemory.startAtApplicationLoad();
        // SM passenger probe: pre-open the probe sink HERE at application
        // load — the pre-open half of the far-end-black-box B1 question; the combat plugin's per-tick
        // half does the combat-time writes. Also force-loads the probe class (B pre-warm pattern).
        SmPassengerProbe.openAtApplicationLoad();
        if (ClientLaunchKnobs.connectPerCombat()) {
            // Per-combat connection mode: the per-mission
            // ServerFedPuppetCombatPlugin opens its OWN client at combat start and closes it at
            // combat end, so the fresh-binding-per-match server forms THIS match at combat start
            // and its scheduled roster-shrink despawn lands ~despawnAtAdvance ticks into THAT
            // combat (a staged vanish a player can watch). Skip the menu auto-connect entirely
            // so no idle menu match consumes the staged despawn during menu navigation — the root
            // cause of an INCONCLUSIVE live test (despawn timed from connect, not combat).
            return;
        }
        synchronized (CcClientModPlugin.class) {
            if (serverClient == null) {
                serverClient = PilotSessionBoot.newServerClient();
                serverClient.start();
            }
        }
    }

    /** MagicLib's mod id (its {@code mod_info.json}). */
    static final String MAGICLIB_MOD_ID = "MagicLib";
    /** The MagicLib class a load-time patch rewrites; defined here, never initialized. */
    static final String MAGIC_SUBSYSTEM_CLASS = "org.magiclib.subsystems.MagicSubsystem";

    /**
     * The MagicLib seam's application-load step. It runs on BOTH JVMs: this plugin also loads on the server, in its
     * boot's plugin loop, long before any door opens. Only when MagicLib is enabled, in this order:
     * <ol>
     *   <li>the FORCED DEFINE: {@code Class.forName(MagicSubsystem, false, scriptLoader)} defines MagicLib's
     *       subsystem class WITHOUT running its static initializer (that reads MagicLib's text table). A load-time
     *       patch of that class runs at this define, off the frame, so its receipt is settled before any HELLO and
     *       before any mark is written; a roster with no MagicLib hull would otherwise never define it;</li>
     *   <li>the reader's guarded probe ({@link cc.starsector.client.magic.MagicLocalStateReader#probe()}): arms the
     *       per-frame report and the HELLO {@code 0x08} claim, or neither;</li>
     *   <li>the hook PRE-TOUCH: loads and initializes the hook (and its slot law), so the first armed call on the
     *       sim thread pays no class load;</li>
     *   <li>the ADOPTER SET mint ({@link cc.starsector.client.magic.MagicAdopters}): one read of every enabled mod
     *       jar, so the hook's adopter test never does I/O on the sim thread.</li>
     * </ol>
     * Each step is guarded on its own (a failure is its SimpleName in the row; the next step still runs) and the
     * whole method never throws. ONE row, bounded tokens only (no path, no mod text).
     *
     */
    private static void magicSeamAtApplicationLoad() {
        long t0 = System.nanoTime();
        String lib = "absent";
        String define = "skipped";
        String probe = "skipped";
        String hook = "skipped";
        String adopters = "adopters=0";
        boolean applied = false;
        try {
            if (Global.getSettings().getModManager().isModEnabled(MAGICLIB_MOD_ID)) {
                lib = "enabled";
                try {
                    Class.forName(MAGIC_SUBSYSTEM_CLASS, false, Global.getSettings().getScriptClassLoader());
                    define = "defined";
                } catch (Throwable t) {
                    define = t.getClass().getSimpleName();
                }
                try {
                    probe = cc.starsector.client.magic.MagicLocalStateReader.probe();
                } catch (Throwable t) {
                    probe = t.getClass().getSimpleName();
                }
                try {
                    cc.starsector.client.magic.CcMagicSeam.describe();
                    hook = "pretouched";
                } catch (Throwable t) {
                    hook = t.getClass().getSimpleName();
                }
                try {
                    adopters = cc.starsector.client.magic.MagicAdopters.mintFromJars(
                            cc.starsector.client.magic.CcMagicSeam.class.getClassLoader());
                } catch (Throwable t) {
                    adopters = "adopters=0 mint=" + t.getClass().getSimpleName();
                }
            }
            applied = cc.starsector.client.magic.MagicCapabilities.seamApplied();
        } catch (Throwable t) {
            lib = "error:" + t.getClass().getSimpleName();
        }
        PilotWatchLog.info("onApplicationLoad: magic seam MagicLib=" + lib + " define=" + define + " probe=" + probe
                + " hook=" + hook + " " + adopters + " seam_applied=" + applied
                + " ms=" + ((System.nanoTime() - t0) / 1_000_000L));
    }

    /** Reentrancy guard for {@link #pickWeaponAutofireAI}: the vanilla-acquire below re-invokes
     *  EVERY mod plugin's hook (ModManager.getPriorityPlugin walks them all) — ours must answer
     *  null while acquiring or it recurses. Combat is single-threaded (the hook fires inside
     *  WeaponGroup.addWeapon on the game thread), so a plain boolean is the whole guard. */
    private static boolean acquiringVanillaAutofireAi;

    /** One-shot install-receipt latch for {@link #pickWeaponAutofireAI} (a WATCH log line per
     *  weapon would be hundreds of rows; one proves the door fired). */
    private static boolean autofireDoorLogged;

    /**
     * The autofire designation-bracket install door. Fires once per
     * weapon at group-add for ALL ships: acquire the
     * plugin the engine would have used (mod picks first, else the vanilla keyword-named class —
     * unconstructable from Java source, hence the engine-typed {@code ModManager.pickWeaponAIPlugin}
     * acquisition) and wrap it in an
     * {@link AutofireTargetAiPlugin}. The wrapper is a PURE PASS-THROUGH unless the active
     * combat plugin's {@link AutofireTargetCoordinator} serves a live server pick for
     * (own ship, that mount) — offline play/sim are zero-delta. Fail-soft: ANY trouble returns
     * {@code null} = the engine's own vanilla mint path, byte-identical to no hook.
     */
    @Override
    public PluginPick<AutofireAIPlugin> pickWeaponAutofireAI(WeaponAPI weapon) {
        if (acquiringVanillaAutofireAi) {
            return null; // reentrant self-call during the acquire below
        }
        // The SERVER JVM loads this mod for content fixtures and
        // runs the AUTHORITATIVE sim — its weapon autofire AI must stay vanilla (the same
        // stand-down as ClientBeamEffectSwap / ClientOnFireAttachSweep). Without this guard the
        // server's autofire was wrapped by the client's AutofireTargetAiPlugin whenever the
        // engine routed the pick through the mod-plugin list. Proof = the serve log carries NO
        // `autofire designation-bracket door INSTALLED` receipt.
        if ("server".equals(System.getProperty("cc.jvm.role"))) {
            if (!autofireDoorLogged) {
                autofireDoorLogged = true;
                PilotWatchLog.info("autofire designation-bracket door SKIPPED reason=server_jvm");
            }
            return null;
        }
        try {
            AutofireAIPlugin vanilla;
            acquiringVanillaAutofireAi = true;
            try {
                vanilla = com.fs.starfarer.launcher.ModManager.getInstance()
                        .pickWeaponAIPlugin(weapon);
            } finally {
                acquiringVanillaAutofireAi = false;
            }
            if (vanilla == null) {
                return null;
            }
            if (!autofireDoorLogged) {
                autofireDoorLogged = true;
                PilotWatchLog.info("autofire designation-bracket door INSTALLED (first wrap:"
                        + " delegate=" + vanilla.getClass().getName() + ")");
            }
            return new PluginPick<AutofireAIPlugin>(new AutofireTargetAiPlugin(vanilla, weapon),
                    CampaignPlugin.PickPriority.MOD_SPECIFIC);
        } catch (Throwable t) {
            if (!autofireDoorLogged) {
                autofireDoorLogged = true;
                PilotWatchLog.error("autofire designation-bracket door FAILED to wrap ->"
                        + " vanilla autofire (fail-soft, no wire-target apply this process)", t);
            }
            return null;
        }
    }

    /** The shared server snapshot client, or {@code null} if application load has not run. */
    public static ServerSnapshotClient serverClient() {
        return serverClient;
    }

    /**
     * The faithful-pilot session opened at application load for {@link PilotIntentCombatPlugin} to
     * consume, or {@code null} if no pilot endpoint is configured / the connect failed.
     */
    public static PilotIntentClientSession pilotSession() {
        return pilotSession;
    }

    /**
     * The predict-and-reconcile session opened at application load for
     * {@link PredictReconcileCombatPlugin} to consume, or {@code null} if no {@code cc.predict.*} endpoint
     * is configured / the connect failed.
     */
    public static PredictSession predictSession() {
        return predictSession;
    }

    // ---------------------------------------------------------------- predict join dial delegates → PredictDialLane
    // the predict join dial moved VERBATIM to PredictDialLane; these
    // one-line delegates keep every caller outside it (the mission definition, the combat plugins, the fail
    // banner) on the names it always called. The session registry predictSession stays here (accessor + quit hook).

    /** {@link PredictDialLane#armMatchFileRefusal(String)}. */
    static void armMatchFileRefusal(String refusal) {
        PredictDialLane.armMatchFileRefusal(refusal);
    }

    /** {@link PredictDialLane#predictConnectGaveUp()}. */
    public static boolean predictConnectGaveUp() {
        return PredictDialLane.predictConnectGaveUp();
    }

    /** {@link PredictDialLane#predictConnectRefusedReason()}. */
    public static String predictConnectRefusedReason() {
        return PredictDialLane.predictConnectRefusedReason();
    }

    /** {@link PredictDialLane#predictConnectLocalRefusal()}. */
    public static boolean predictConnectLocalRefusal() {
        return PredictDialLane.predictConnectLocalRefusal();
    }

    /** {@link PredictDialLane#missionSessionRefused()}. */
    public static boolean missionSessionRefused() {
        return PredictDialLane.missionSessionRefused();
    }

    /** {@link PredictDialLane#autoSeatStaleBuild()}. */
    public static boolean autoSeatStaleBuild() {
        return PredictDialLane.autoSeatStaleBuild();
    }

    /** {@link PredictDialLane#setAutoSeatStaleBuild(boolean)}. */
    static void setAutoSeatStaleBuild(boolean value) {
        PredictDialLane.setAutoSeatStaleBuild(value);
    }

    /** {@link PredictDialLane#consumeReAdmissionArmed()}. */
    static boolean consumeReAdmissionArmed() {
        return PredictDialLane.consumeReAdmissionArmed();
    }

    /** {@link PredictDialLane#predictSessionForMissionInit()}. */
    public static PredictSession predictSessionForMissionInit() {
        return PredictDialLane.predictSessionForMissionInit();
    }

    /** {@link PredictDialLane#dialPredictAtJoin()}. */
    public static void dialPredictAtJoin() {
        PredictDialLane.dialPredictAtJoin();
    }

    /** {@link PredictDialLane#dialPredictAtPlay()}. */
    public static boolean dialPredictAtPlay() {
        return PredictDialLane.dialPredictAtPlay();
    }

    /** {@link PredictDialLane#noteClaimPending(long)}. */
    static void noteClaimPending(long seat) {
        PredictDialLane.noteClaimPending(seat);
    }

    /** {@link PredictDialLane#noteClaimSettled()}. */
    static void noteClaimSettled() {
        PredictDialLane.noteClaimSettled();
    }

    /** {@link PredictDialLane#claimPendingSinceMillis()}. */
    public static long claimPendingSinceMillis() {
        return PredictDialLane.claimPendingSinceMillis();
    }

    /** {@link PredictDialLane#claimSeat()}. */
    public static long claimSeat() {
        return PredictDialLane.claimSeat();
    }

    /** {@link PredictDialLane#noteRoundStartedWithoutSession(boolean)}. */
    static void noteRoundStartedWithoutSession(boolean value) {
        PredictDialLane.noteRoundStartedWithoutSession(value);
    }

    /** {@link PredictDialLane#roundStartedWithoutSession()}. */
    public static boolean roundStartedWithoutSession() {
        return PredictDialLane.roundStartedWithoutSession();
    }

    /** {@link PredictDialLane#noteLateAttachHold(boolean)}. */
    static void noteLateAttachHold(boolean value) {
        PredictDialLane.noteLateAttachHold(value);
    }

    /** {@link PredictDialLane#lateAttachHold()}. */
    public static boolean lateAttachHold() {
        return PredictDialLane.lateAttachHold();
    }

    /** {@link PredictDialLane#predictDialLastRefusal()}. */
    public static String predictDialLastRefusal() {
        return PredictDialLane.predictDialLastRefusal();
    }

    /** {@link PredictDialLane#predictDialInFlight()}. */
    public static boolean predictDialInFlight() {
        return PredictDialLane.predictDialInFlight();
    }

    /** {@link PredictDialLane#predictDialAttempt()}. */
    public static int predictDialAttempt() {
        return PredictDialLane.predictDialAttempt();
    }

    /** {@link PredictDialLane#predictDialAttempts()}. */
    public static int predictDialAttempts() {
        return PredictDialLane.predictDialAttempts();
    }

    /** {@link PredictDialLane#markRoundEntered()}. */
    static void markRoundEntered() {
        PredictDialLane.markRoundEntered();
    }

    /** {@link PredictDialLane#markCombatFrame()}. */
    public static void markCombatFrame() {
        PredictDialLane.markCombatFrame();
    }

    /** {@link PredictDialLane#joinBriefingRows()}. */
    public static String[] joinBriefingRows() {
        return PredictDialLane.joinBriefingRows();
    }

    // ---------------------------------------------------------------- current-build delegates → CurrentBuildLane
    // the current-build + variant-pick lane moved VERBATIM to
    // CurrentBuildLane; these one-line delegates keep the 25 editor/picker/route caller sites untouched.

    /** Delegate — body + doc live in {@link CurrentBuildLane#hasPredictSession()}. */
    public static boolean hasPredictSession() {
        return CurrentBuildLane.hasPredictSession();
    }

    /** Delegate — body + doc live in {@link CurrentBuildLane#latestVariantReceipt()}. */
    public static cc.starsector.client.net.ClientVariantReceipt latestVariantReceipt() {
        return CurrentBuildLane.latestVariantReceipt();
    }

    /** Delegate — body + doc live in {@link CurrentBuildLane#currentBuild()}. */
    public static String currentBuild() {
        return CurrentBuildLane.currentBuild();
    }

    /** Delegate — body + doc live in {@link CurrentBuildLane#setCurrentBuild(String)}. */
    public static boolean setCurrentBuild(String stem) {
        return CurrentBuildLane.setCurrentBuild(stem);
    }

    /** Delegate — body + doc live in {@link CurrentBuildLane#clearCurrentBuildIf(String)}. */
    public static void clearCurrentBuildIf(String stem) {
        CurrentBuildLane.clearCurrentBuildIf(stem);
    }

    /** Delegate — body + doc live in {@link CurrentBuildLane#nextConnectPick()}. */
    public static String nextConnectPick() {
        return CurrentBuildLane.nextConnectPick();
    }

    /** Delegate — body + doc live in {@link CurrentBuildLane#nextConnectSource()}. */
    public static String nextConnectSource() {
        return CurrentBuildLane.nextConnectSource();
    }

    /**
     * Construct (but do <em>not</em> start) a {@link ServerSnapshotClient} pointed at the running
     * dedicated server, resolving host/port via the same port-file / property / env / default chain
     * as the shared menu client. Used by the per-combat connection mode: the
     * per-mission {@link ServerFedPuppetCombatPlugin} starts and owns one of these per combat and is
     * responsible for {@link ServerSnapshotClient#start()} and {@link ServerSnapshotClient#close()}.
     */
    public static ServerSnapshotClient newServerClient() {
        return PilotSessionBoot.newServerClient(); //
    }

    /**
     * Whether the client connects <em>per combat</em> instead of once at the menu,
     * from {@code cc.client.connectPerCombat} (then {@code CC_CLIENT_CONNECT_PER_COMBAT}); default
     * {@code false} keeps the proven shared-menu-client two-window {@code cc_puppet_proof} path
     * exactly as before. Any of {@code 1/true/yes/on} (case-insensitive) enables it. The
     * despawn-watch demo launcher sets it on so the fresh-binding-per-match despawn aligns with
     * combat start rather than client connect.
     */
    static boolean connectPerCombat() {
        return ClientLaunchKnobs.connectPerCombat(); //
    }

    /** One-shot guard for {@link #registerQuitByeHook} (onApplicationLoad can in principle re-run). */
    private static volatile boolean quitByeHookRegistered;

    /**
     * The quit-BYE JVM shutdown hook. Recon: the engine close
     * path always ends in {@code System.exit(0)} ({@code CombatState} quit-throw →
     * {@code CombatMain} {@code Display.destroy()} → {@code exit(0)}) with NO combat-end plugin
     * callback, so a window close mid-round left the server holding a live seat until its own
     * silence deadline. The hook is SELF-CONTAINED and bounded: it closes the app-load predict
     * session — the SAME object the combat plugin consumes ({@code this.session = s}), so one
     * close covers menu-idle and mid-combat alike — via the session's own goodbye law (bounded
     * CTRL flush + BYE resent until the peer echo, ≤5 s, idempotent, never throws). NO game-API
     * calls in here: the hook runs unordered against the engine's own AL-destroy shutdown hook,
     * so engine objects are off-limits (the combat plugin's meters/census stay on its normal
     * teardown paths). A healthy round end already closed the session — close() is then a no-op.
     * Worst case adds ≤5 s to JVM exit; the log lines are best-effort (log4j's own shutdown is
     * also unordered vs this hook).
     */
    private static void registerQuitByeHook() {
        if (quitByeHookRegistered) {
            return;
        }
        quitByeHookRegistered = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    PredictSession s = predictSession;
                    if (s != null) {
                        PilotWatchLog.info("quit-BYE hook: JVM exiting with a predict session ->"
                                + " close (bounded goodbye; BYE vacates the seat)");
                        s.close();
                        PilotWatchLog.info("quit-BYE hook: session closed");
                    }
                } catch (Throwable ignore) {
                    // shutdown is best-effort by nature; never block or fail the JVM exit
                } finally {
                    // (38) drain + close the async sidelog wrapper LAST —
                    // after every bye row — so the ring's tail lands in the file at JVM exit.
                    PilotWatchLog.closeSidelog();
                }
            }, "cc-quit-bye"));
            PilotWatchLog.info("quit-BYE hook: REGISTERED (covers every System.exit path;"
                    + " ESC-exit leak is the advance-staleness watchdog's)");
        } catch (Throwable t) {
            PilotWatchLog.error("quit-BYE hook: registration FAILED -> window-close quits fall"
                    + " back to the server silence deadline / launcher reaper", t);
        }
    }
}
