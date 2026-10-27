package cc.starsector.client;

import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDocumentLaws;
import cc.starsector.server.match.MatchDocumentRefused;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * GENERIC mission author from a server-minted {@link MatchDescriptor}: when
 * {@code cc.match.descriptor} / {@code CC_MATCH_DESCRIPTOR} names a readable descriptor file,
 * {@link #buildFromConfiguredDescriptor} replays the descriptor as the exact
 * {@code MissionDefinitionAPI} call sequence the certified hard-coded 1v1 makes
 * (initFleet → setFleetTagline → addToFleet per side, then initMap), for any of the three
 * authored shapes; outside that list {@link TerrainBackdrop} adds the optional {@code map.terrain}
 * backdrop. Default OFF: knob unset ⇒ {@code false} with ZERO api calls, and the
 * mission's certified body runs untouched.
 *
 * <p><b>Phase discipline (classloader B):</b> the descriptor FILE is read + decoded ONCE at
 * application load — {@link CcClientModPlugin#onApplicationLoad()} logs
 * {@link #describeConfig()}, which both first-touches this class and caches the decode — through
 * the B-legal {@code FileChannel}/{@code Paths} seam ({@code java.nio.file.Files} is B-denied).
 * {@code defineMission} (the Janino mission script, mission entry) then consumes only the cached
 * result: no file I/O and no new class loading mid-mission.
 *
 * <p>Failure shape: knob unset ⇒ silent {@code false} (an ordinary launch, the certified body). A
 * SET knob is FAIL-CLOSED ({@link #readAndCheck}): a file that is unreadable, larger than
 * {@link MatchDocumentLaws#FILE_READ_MAX}, undecodable, refused by {@link MatchDocumentLaws#check}
 * or naming a roster variant this game lacks leaves {@link #cachedDescriptor()} {@code null} and
 * {@link #loadFailure()} naming the refusal (a {@code match_doc_*} reason + OUR field, never file
 * text). The certified body is then the mission that opens, only so the in-combat banner can say
 * why: the dial planner arms that banner instead of dialing, so a match this game cannot build is
 * never joined on a guessed shape.
 *
 *
 */
public final class MatchMissionBuilder {

    /**
     * Verify seam: the game-free V6s rows cannot resolve a real variant
     * ({@link #isStationVariant} fail-softs {@code false} without {@code Global}), so the harness
     * injects the station predicate here via a verify-only bridge class that is compiled into the
     * verify-match set ONLY, never the mod jar. Package-private and {@code null} in production —
     * nothing in the shipped jar reaches it.
     */
    static volatile java.util.Set<String> stationVariantsForVerify;

    private static boolean loadAttempted;
    private static MatchDescriptor cached;
    private static String descriptorPath;
    private static String configSummary = "cc.match.descriptor unset -> certified mission shape";
    private static Failure loadFailure;

    private MatchMissionBuilder() {
    }

    /**
     * Why a SET {@code cc.match.descriptor} built nothing: a {@code match_doc_*} reason token (the
     * {@code MatchDocumentLaws.REASON_*} vocabulary) and OUR field for the refused place ({@code file},
     * {@code decode}, a law's {@code <list>.<i>.<key>} path, or the missing-variant COUNT) — never text
     * read from the file. {@link #token()} is the one form the banner and the dial planner carry.
     *
     *
     */
    record Failure(String reason, String field) {
        /** {@code reason + " " + field}: the latched give-up reason the banner renders. */
        String token() {
            return reason + " " + field;
        }
    }

    /** {@link #readAndCheck}'s result: exactly one of {@code descriptor} / {@code failure} is non-null. */
    record Outcome(MatchDescriptor descriptor, Failure failure) {
        static Outcome loaded(MatchDescriptor descriptor) {
            return new Outcome(descriptor, null);
        }

        static Outcome refused(String reason, String field) {
            return new Outcome(null, new Failure(reason, field));
        }
    }

    /**
     * The refusal behind a SET knob's {@code null} {@link #cachedDescriptor()} (loadOnce-backed), or
     * {@code null} when the knob is unset or the file loaded. The dial planner arms the give-up banner from it
     * instead of dialing.
     *
     *
     */
    static synchronized Failure loadFailure() {
        loadOnce();
        return loadFailure;
    }

    /**
     * One-line config/load state for the app-load pre-warm log (the call that performs the
     * one-time file read + decode — same first-touch pattern as {@code ClientParityWalker}).
     */
    public static synchronized String describeConfig() {
        loadOnce();
        return configSummary;
    }

    /**
     * The cached descriptor (loadOnce-backed), or {@code null} when unset or refused ({@link #loadFailure()}) —
     * the predict plugin's shape key: {@code shape().startsWith("bots")} engages
     * the remote-entity registry; null/1v1 leaves the certified single-foe path byte-identical.
     */
    public static synchronized MatchDescriptor cachedDescriptor() {
        loadOnce();
        return cached;
    }

    /**
     * The resolved {@code cc.match.descriptor} path (loadOnce-backed), or {@code null} when the
     * knob is unset — the B1b fingerprint file list is derived from it: the kit's {@code kit.files}
     * in the same folder, else the sibling {@code <path>.fingerprint} (zero new knobs).
     *
     */
    public static synchronized String cachedDescriptorPath() {
        loadOnce();
        return descriptorPath;
    }

    /**
     * Author the mission from the cached descriptor, or return {@code false} (zero api calls)
     * when no descriptor is configured/loaded. The caller (the cc_predict mission prelude) adds
     * the combat plugin itself on both paths — this builder never instantiates plugins.
     */
    public static synchronized boolean buildFromConfiguredDescriptor(MissionDefinitionAPI api) {
        loadOnce();
        if (cached == null) {
            return false;
        }
        build(api, cached);
        addBastionBackdrop(api, cached);
        // Outside build() — the V5/V6 golden call lists stay byte-identical
        TerrainBackdrop.apply(api, cached);
        return true;
    }

    /**
     * Chicomoztoc as the combat-background planet for every
     * Bastion-family shape — the fortress defends that world, so its battles
     * carry the vanilla near-planet flavor. Presentation-only and physics-inert by three
     * independent engine guarantees: a background planet gets NO collision proxy
     * (CollisionClass.PLANET has no pair-table entry and projectiles/rays skip instanceof
     * Planet), gravity short-circuits to 0 with getGravityBonus
     * caller-less tree-wide, and advance() spins texture angles
     * only. Client-local: no server twin exists or is needed, and no census/parity/
     * AWSS instrument sees it (all getShips()-scoped).
     *
     * <p>Deliberately OUTSIDE {@link #build}: the game-free verify harness (V5/V6 golden
     * call-list) replays build() and must not see backdrop calls. Numbers mirror vanilla
     * BattleCreationPluginImpl.addClosestPlanet for a battle AT the planet (dist=0 ⇒ f=1.0):
     * bg canvas 2048, display radius = 200 (Chicomoztoc's campaign radius)
     * × 1.5 scaleFactor = 300, yOffset −512 = the own-side-has-a-station shift. Type =
     * {@link #backdropType()}: the mod-carried {@code cc_chicomoztoc} city-lights glow spec
     * when the store carries it, else {@code barren-desert} — Chicomoztoc's
     * own campaign type (its planets.json entry), the pre-glow behavior.
     */
    private static void addBastionBackdrop(MissionDefinitionAPI api, MatchDescriptor descriptor) {
        if (!descriptor.shape().startsWith("bastion:")) {
            return;
        }
        api.setPlanetBgSize(2048f, 2048f);
        api.addPlanet(0f, -512f, 300f, backdropType(), 0f, true);
    }

    /**
     * W8 glow rider: {@code cc_chicomoztoc} — barren-desert + the campaign's
     * Chicomoztoc glow mutation, shipped in the mod's
     * {@code data/config/planets.json} — selected ONLY when the spec store actually carries it.
     * FAIL-CLOSED BY CONSTRUCTION: mission {@code addPlanet(String)} defers spec resolution to
     * battle-finalize (the mission stores the string, CombatEngine.init drains it
     * into {@code new Planet}), where an unknown id NPEs uncatchably
     * (a store miss returns null into the Planet ctor). So
     * the id is pre-flighted here against the SAME map that resolution reads:
     * {@code getAllPlanetSpecs()} enumerates the identical SpecStore map
     * and {@code getPlanetType()} IS the store key == the planets.json top-level key
     * — a spec seen here cannot miss there. No
     * {@code Global} (game-free harness) or unmerged spec ⇒ barren-desert, byte-identical to the
     * pre-glow landing. One info line either way = the watch-debrief instrument for the ride-along
     * visual confirm.
     */
    private static String backdropType() {
        try {
            for (com.fs.starfarer.api.campaign.PlanetSpecAPI spec
                    : com.fs.starfarer.api.Global.getSettings().getAllPlanetSpecs()) {
                if ("cc_chicomoztoc".equals(spec.getPlanetType())) {
                    PilotWatchLog.info("bastion backdrop: cc_chicomoztoc glow spec SELECTED"
                            + " (mod planets.json merged)");
                    return "cc_chicomoztoc";
                }
            }
            PilotWatchLog.info("bastion backdrop: cc_chicomoztoc NOT in the spec store ->"
                    + " barren-desert fallback (mod planets.json unmerged?)");
        } catch (Throwable t) {
            return "barren-desert"; // game-free harness: no Global -> the pre-glow type
        }
        return "barren-desert";
    }

    /**
     * The deterministic core, exposed for the game-free verify harness (rows V5/V6): replay
     * {@code descriptor} onto {@code api} with EXACTLY the certified call shape — per side in
     * order: initFleet, setFleetTagline, then that side's roster rows expanded {@code count}
     * times; the FIRST seat-bound player-side ship is the flagship; initMap last. Registry
     * shapes (bots:/seats:/gauntlet:/teams:/bastion:) instead field ONLY the own seat's ship as
     * PLAYER flagship — see the body comment.
     */
    /**
     * The registry-shape key list, single-sourced: a registry shape fields ONLY
     * the own seat locally — every other body spawns at first wire sight — so the three
     * consumers (build()'s roster suppression, the plugin's engage path, and the plugin's
     * connect-fail hold-open) must never disagree on it: a shape keyed in one site but not the
     * others either fields fake vanilla-AI combatants or presents a connect failure as a frame-0
     * native victory. Lives HERE (not on the plugin) because the game-free verify harness
     * (verify-match-descriptor.ps1 rows V5/V6) compiles this class without the plugin.
     * bastion:A:B joined the list: seat-1 skipper + A× skipper AI (side 0) +
     * B× grendel AI (side 1) — without its key the non-registry fallback would field every
     * roster row locally with vanilla AI.
     */
    static boolean isNpeerShape(String shape) {
        return shape.startsWith("seats:") || shape.startsWith("gauntlet:")
                || shape.startsWith("teams:") || shape.startsWith("bastion:");
    }

    /** Registry = bots (D-0 canonicalizes bots:0:N -> bots:N, so startsWith covers both) + npeer. */
    static boolean isRegistryShape(String shape) {
        return shape.startsWith("bots") || isNpeerShape(shape);
    }

    public static void build(MissionDefinitionAPI api, MatchDescriptor descriptor) {
        // Spawn-on-sight: registry shapes
        // (bots:/seats:/gauntlet:/teams:/bastion: — the plugin's registryShape key) field ONLY the own seat's ship
        // locally — every other body is spawned at first wire sight by the predict plugin's
        // registry, so roster rows must NOT materialize here (a local roster races the engine's
        // deploy ramp, and a surplus local ship would fight as a fake combatant). Both fleets
        // still init (taglines, map); the plugin holds the otherwise-empty-enemy mission open via
        // setDoNotEndCombat. The own ship is ALWAYS the PLAYER flagship whatever its descriptor
        // side — the plugin maps wire sides relative to ownDescSide.
        String shape = descriptor.shape();
        boolean registryShape = isRegistryShape(shape);
        // The OBSERVER posture fields NO own hull — only a registry shape has
        // that freedom (its own-seat add is the ONE local hull; every other row spawns at wire
        // sight). A local/vanilla shape would field its whole reserves list with no flagship =
        // a fake combatant fleet nobody pilots; refuse at build, never author it.
        if (ObserverMode.ON && !registryShape) {
            throw new IllegalStateException("cc.observer=on needs a registry-shape descriptor"
                    + " (shape '" + FallbackAnnounce.sanitize(shape, 64) + "' fields local hulls;"
                    + " an observer fields none)");
        }
        // Vanilla-compare deploy law: the engine auto-deploys a side at combat
        // start ONLY when its reserves count is exactly 1 (CombatState.traverse);
        // otherwise deployment falls to the deploy dialog (player) or the side's AdmiralAI —
        // and initFleet's 4th arg (useDefaultAI) wires that admiral's allyMode:
        // false = ally-mode admiral that NEVER deploys the side's own reserves. Vanilla
        // convention (API javadoc + every vanilla mission): ENEMY true, PLAYER false. Gated to
        // vanilla: shapes so the certified 1v1 and every served/registry lane stay byte-identical
        // (their local sides never hold >1 reserve, so the old false/false never bit them).
        boolean vanillaCompare = shape.startsWith("vanilla:");
        // The own seat's DESCRIPTOR side, resolved from the same dense-id walk the
        // plugin uses (PredictReconcileCombatPlugin's ownDescSide) — NOT the absolute
        // index 0. "Own side" is relative to the seat: a side-1 seat with a side-1 station would
        // otherwise field its own ally into FleetSide.ENEMY while the plugin's lane refuses it.
        // Resolve the own seat ONCE per build and latch it — every ship-id-
        // relative read in this build (side, variant, seat-ness) must agree even if a late
        // WELCOME seeds the AUTO assignment mid-build, and the predict plugin's init race
        // guard compares its session against exactly what this build fielded.
        // An OBSERVER fields no own hull — own seat 0 (the id no descriptor row
        // carries), so lastBuiltOwnShipId stays 0 and the PRCP init guard ("built != 0 &&
        // session != built") never refuses the seat-0 session's bind.
        // W13 leg 2b: CLAIM AT PLAY — a served pilot
        // window builds its registry mission with ZERO own hulls, the observer shape: the click no
        // longer dials, so no seat is known here; the seat hull fields at combat init's first wire
        // sight (PredictReconcileCombatPlugin.claimOwnSeatAtPose). Zero, not "all pilot hulls": N>1
        // reserves pops the deploy dialog, a flagship auto-claims at deploy, and un-chosen hulls
        // would be local fake combatants. built==0 keeps the init guard's observer latch.
        boolean claimAtPlay = registryShape && !ObserverMode.ON && claimAtPlayBuild;
        long ownShipId = (ObserverMode.ON || claimAtPlay) ? 0L : resolveOwnShipId();
        lastBuiltOwnShipId = ownShipId;
        claimedSessionSeat = 0L; // a new build voids the previous round's session seat
        if (claimAtPlay) {
            PilotWatchLog.info("match build: CLAIM AT PLAY -> no seat hull at the click; ONE placeholder"
                    + " flagship keeps reserves==1 (the WELCOME's seat hull re-fields it at combat init)");
            // W15 v1: the menu-thread VACK peek — the live build lane resolves
            // no pick, so this is the pre-combat line naming the custom verdict already on hand.
            PilotWatchLog.info("match build: " + JoinVariantPick.customVerdictOnHand());
        }
        int ownDescSide = registryShape ? sideForShipId(descriptor, ownShipId) : 0;
        boolean flagshipPlaced = false;
        // Mint the side-0 fleet context FIRST — every attach/skill-up below installs
        // it fail-closed (knob-gated no-op inside).
        OfficerLoadout.mintSideZeroFleetContext(descriptor);
        for (int s = 0; s < descriptor.sides().size(); s++) {
            MatchDescriptor.Side side = descriptor.sides().get(s);
            FleetSide fleetSide = (s == 0) ? FleetSide.PLAYER : FleetSide.ENEMY;
            // Descriptor v1 validates goal == ATTACK at construction; map it explicitly here.
            api.initFleet(fleetSide, side.name(), FleetGoal.ATTACK,
                    vanillaCompare && fleetSide == FleetSide.ENEMY);
            api.setFleetTagline(fleetSide, side.tagline());
            boolean commanderLanesFired = false;
            if (s == 0 && OfficerLoadout.allSkillsEnabled()) {
                // Client half (manager-level side 0, BOTH ends):
                // skill-up the mission's PLAYER default commander — the engine wires that person
                // into the side-0 CombatFleetManager at combat start itself, zero new plumbing.
                // Any own side builds. Local side 0 is
                // the own team here while the server's default sits on absolute side 0, so the two
                // defaults differ for a north seat (a registry shape; every other shape builds as
                // side 0). A seat carries its own side-blind pair, a descriptor side-0 grunt the
                // fleet-context pair, and every other member-first non-seat row (a wave, a pod, a
                // station, a side-1 descriptor row) an explicit pair keyed on its absolute side:
                // OfficerLoadout.attachAppendedCommander at the replica spawn, and
                // attachMissionStationCommander for the stations the second pass below fields.
                PersonAPI commander = api.getDefaultCommander(FleetSide.PLAYER);
                if (commander == null) {
                    PilotWatchLog.info("allskills commander: getDefaultCommander(PLAYER)"
                            + " returned null -> SKIPPED loud (the server side still buffs —"
                            + " expect a stat desync until this is explained)");
                } else {
                    OfficerLoadout.skillUpMissionCommander(commander);
                    // The engine's mission default commander is a SECOND
                    // union-sheeted person — its fleetwide applies need the fleet token too.
                    OfficerLoadout.installMissionCommanderFleetToken(commander);
                    commanderLanesFired = true;
                }
            }
            if (s == 0) {
                // Arm the post-adoption recovery — a build under
                // matchFlags still 0x0 (slow admit) skips the commander lanes above SILENTLY
                // while every captain lane re-fires post-adoption; the predict plugin replays
                // them at adoption. fired=false also covers the null-commander SKIP above
                // (the recovery retries against the combat-side manager each frame).
                OfficerLoadout.noteMissionBuildCommanderLanes(descriptor, commanderLanesFired);
            }
            if (registryShape) {
                // Under ObserverMode.ON the own flagship add is SKIPPED — the
                // engine then lazily mints its invisible shuttlepod "Command Shuttle" as the
                // player ship (never in getShips(), commands dropped
                // by CombatState) and the plugin's setDoNotEndCombat(true) holds the
                // EMPTY player side open (an empty deployed list would otherwise end combat at
                // once). ownT0VariantId(descriptor, 0) has no row — so
                // the skip wraps the whole block.
                if (s == 0 && !ObserverMode.ON && !claimAtPlay) { // W13 leg 2b: claim-at-play fields none
                    String ownVariantId = ownT0VariantId(descriptor, ownShipId);
                    if (isStationVariant(ownVariantId)) {
                        // Mission-side half of the flagship law: a
                        // wrong cc.predict.shipId naming the station row must fail LOUD here —
                        // fielding it would mint a station PLAYER flagship (getCombatUI NPE) and
                        // the plugin-side SABL refusal runs far too late to un-author the fleet.
                        throw new IllegalStateException("cc.predict.shipId resolves to STATION"
                                + " row variant " + ownVariantId + " — a station can never be"
                                + " the seat (flagship law); fix the seat id knob");
                    }
                    FleetMemberAPI own = api.addToFleet(FleetSide.PLAYER, ownVariantId,
                            FleetMemberType.SHIP, true);
                    // Parity attach: the own seat's ship carries its json-assigned
                    // officer (the server roster deploy attaches the same one).
                    // W4: seat-ness from THIS descriptor's dense-id walk (uniform law both ends).
                    OfficerLoadout.attach(own, ownVariantId,
                            isSeatShipId(descriptor, ownShipId));
                } else if (s == 0 && claimAtPlay) {
                    // The fix for the modal "No ships deployed"
                    // dialog at every Play: the ZERO-hull build left the PLAYER side with 0
                    // reserves, and the engine auto-deploys a side ONLY at reserves==1 (CombatState
                    // .traverse) — 0 pops the deploy dialog exactly like the
                    // starved-seat case above. Field exactly ONE placeholder flagship (never a
                    // station): the seat is still claimed at Play — PredictReconcileCombatPlugin
                    // .claimOwnSeatAtPose adopts this hull IN PLACE when the WELCOME's seat variant
                    // already matches, else re-fields it through the respawn core with this body as
                    // the stale one (the D-FIRSTSHIP t=0 placeholder law, now on the claim lane).
                    String placeholder = claimAtPlayPlaceholderVariant(descriptor);
                    FleetMemberAPI own = api.addToFleet(FleetSide.PLAYER, placeholder,
                            FleetMemberType.SHIP, true);
                    OfficerLoadout.attach(own, placeholder, true); // a seat hull by construction
                    PilotWatchLog.info("match build: CLAIM AT PLAY -> ONE placeholder flagship "
                            + placeholder + " fielded (reserves==1 auto-deploy, no deploy dialog);"
                            + " the claim at combat init adopts it in place or re-fields the seat hull");
                }
                continue; // roster rows spawn at first wire sight; STATION rows ride the second pass below
            }
            for (MatchDescriptor.RosterRow row : descriptor.roster()) {
                if (row.side() != s) {
                    continue;
                }
                if (s == 0 && isStationVariant(row.variantId())) {
                    if (vanillaCompare) {
                        // The twin once hit the refusal below and wrote
                        // zero frames: the fortress's vanilla TWIN needs its own-side station too,
                        // but mission-fielding it starves the seat. Route it the way the served side
                        // gets it — a first-frame spawnShipOrWing at the server's (0,0)/facing-0 pin,
                        // module cluster included, never in reserves (VanillaTwinStationSpawn) — so
                        // the reserves==1 auto-deploy holds. vanilla: shapes only; every served/
                        // registry lane below is byte-identical.
                        for (int i = 0; i < row.count(); i++) {
                            api.addPlugin(new VanillaTwinStationSpawn(row.variantId()));
                        }
                        PilotWatchLog.info("predict vanilla-twin station: own-side row "
                                + FallbackAnnounce.sanitize(row.variantId(), 64) + " x" + row.count()
                                + " routed to a first-frame spawn at (0,0) facing 0 (never"
                                + " mission-fielded)");
                        continue;
                    }
                    // Review fix: a NON-registry shape fielding a station into the
                    // local PLAYER reserves hits the full chain (isDeployedStation
                    // starves the seat auto-deploy ⇒ modal deploy dialog)
                    // with NO plugin lane armed to catch it. No current shape does this —
                    // fail loud, never author the starved seat. (An ENEMY-side station in a
                    // local shape stays legal: that is just fighting a vanilla station.)
                    throw new IllegalStateException("non-registry shape '" + shape + "' fields a"
                            + " STATION row (" + FallbackAnnounce.sanitize(row.variantId(), 64)
                            + ") into the local PLAYER"
                            + " reserves — refused (seat starve + deploy dialog)");
                }
                for (int i = 0; i < row.count(); i++) {
                    boolean flagship = s == 0 && !flagshipPlaced
                            && row.pilot() == MatchDescriptor.PilotKind.SEAT;
                    if (flagship) {
                        flagshipPlaced = true;
                    }
                    FleetMemberAPI added = api.addToFleet(fleetSide, row.variantId(),
                            FleetMemberType.SHIP, flagship);
                    // Parity attach; seat-ness = the row's own pilot kind.
                    OfficerLoadout.attach(added, row.variantId(),
                            row.pilot() == MatchDescriptor.PilotKind.SEAT);
                    // Grunt coverage: every descriptor-side-0 member carries the
                    // fleet-context pair (server twin: mintCrewedMember's install).
                    OfficerLoadout.attachFleetContext(added, row.side());
                }
            }
        }
        if (registryShape) {
            // STATION rows are the ONE
            // registry-shape exception to "field only the own seat" — and they field in a SECOND
            // PASS after the side loop, so BOTH fleets are init'd before any station addToFleet.
            // In-loop, a side-0 station facing a side-1 seat called addToFleet(ENEMY) during the
            // s==0 iteration, BEFORE initFleet(ENEMY) had run (a latent NPE). NO
            // initFleet hoist instead: hoisting would reorder every golden call list (V5 = parity
            // row vs the certified mission body).
            //
            // A NON-OWN station cannot be spawned at first wire sight the way a hull is: vanilla
            // mints the base AND its whole module cluster in one engine deploy (CombatState init
            // -> deployStationAndRelatedIfNeeded), so the row is FIELDED here —
            // always into ENEMY: the own seat is the PLAYER flagship whatever its descriptor side
            // — and the client's StationAdoptBindLane ADOPTS the engine-minted bodies for their
            // wire ids (base by variant, modules by slot-position natural key). Never a flagship
            // (a station flagship NPEs getCombatUI).
            for (MatchDescriptor.RosterRow row : descriptor.roster()) {
                if (!isStationVariant(row.variantId())) {
                    continue;
                }
                if (row.side() == ownDescSide) {
                    // This supersedes the reserves-count rationale:
                    // an OWN-SIDE station is NEVER mission-fielded. A station in the local side's
                    // reserves is engine-deployed at CombatState init, whose isDeployedStation
                    // flag kills the seat's reserves==1 auto-deploy
                    // REGARDLESS of reserves count, and the starved seat then gets the modal
                    // deploy dialog at round start. The own-side base rides
                    // the direct spawn lane instead (spawnRemoteEntities -> spawnShipOrWing ->
                    // the same engine deploy body, module cluster included; StationAdoptBindLane
                    // accepts the row and adopts the spawned body) — so this skip is the DESIGNED
                    // routing under DEFEND, logged info, not a defect.
                    PilotWatchLog.info("predict station_bind: mission fielding SKIPPED by design"
                            + " — OWN-SIDE station row ("
                            + FallbackAnnounce.sanitize(row.variantId(), 64) + ", descriptor side "
                            + ownDescSide + ") is never mission-fielded (an own-side deployed"
                            + " station kills the seat's reserves==1 auto-deploy and pops the deploy"
                            + " dialog); the base is EXPECTED from the"
                            + " registry spawn lane — see 'predict station_bind: ACCEPTED/ADOPTED' for"
                            + " whether it actually arrived");
                    continue;
                }
                for (int i = 0; i < row.count(); i++) {
                    FleetMemberAPI fielded = api.addToFleet(FleetSide.ENEMY, row.variantId(),
                            FleetMemberType.SHIP, false);
                    // The commander the server's
                    // station resolves, keyed on the row's descriptor (absolute) side, never on
                    // the local ENEMY manager it lands in; a no-op with the all-skills knob off.
                    OfficerLoadout.attachMissionStationCommander(fielded, row.side());
                }
            }
        }
        api.initMap(-descriptor.halfWidth(), descriptor.halfWidth(),
                -descriptor.halfHeight(), descriptor.halfHeight());
    }

    /**
     * THE station predicate, shared by the mission mint above and the
     * plugin's descriptor acceptance ({@code StationAdoptBindLane.acceptDescriptorRows}): the
     * ENGINE's own seam {@code ShipVariantAPI.isStation()}, resolved through the settings spec
     * store. The server keeps a game-free twin ({@code MatchDescriptors.isStationVariantId}) that
     * agrees by construction — that class is deliberately NOT bundled into the mod jar, and this
     * seam is the one the game-side deploy lanes read anyway.
     *
     * <p>Lives HERE (not on the lane) so this file's closure stays exactly the game-free
     * verify-match compile set. Fail-soft to {@code false}: outside a game (the descriptor
     * verifier drives {@link #build} with no {@code Global} settings) a variant is simply not a
     * station, which is the byte-identical pre-station behaviour for every non-station shape.
     */
    static boolean isStationVariant(String variantId) {
        if (variantId == null || variantId.isBlank()) {
            return false;
        }
        java.util.Set<String> verifySeam = stationVariantsForVerify;
        if (verifySeam != null) {
            return verifySeam.contains(variantId); // V6s game-free injection (see the field doc)
        }
        try {
            com.fs.starfarer.api.combat.ShipVariantAPI variant =
                    com.fs.starfarer.api.Global.getSettings().getVariant(variantId);
            return variant != null && variant.isStation();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The dense id-contract walk: ship ids {@code 1..N} assigned in roster
     * build order × count — the same contract the server registry and the predict plugin's
     * {@code variantById} walk share. An out-of-range id THROWS (fail-closed knob discipline:
     * a wrong {@code cc.predict.shipId} must not author a wrong-hulled mission silently).
     */
    /**
     * The mission spawn follows the gui selection:
     * the OWN seat's t=0 hull = the join-window pick when one rode
     * ({@code cc.predict.variantId}, a pick from the SERVER-PUBLISHED probe list — validated
     * at pick time) AND the kit resolves it locally, else the seat's descriptor row (the pre-L6
     * hull). The server stays authoritative: it fields the canonical pick (or its fallback) and
     * announces whenever what it fielded differs from the descriptor row OR from this requested
     * pick ({@code announceSeatVariantIfRepointed}), and the L5 alive re-field reconciles the
     * live hull to the announcement (equal ⇒ one info row). So a pick that resolves here but is
     * rejected there, or resolves there but not here, converges within the first RAPP row —
     * never a silent fork. A pick naming a station falls back too (player input never throws;
     * the descriptor-row station law below still guards the seat-id knob).
     *
     * <p><b>D-FIRSTSHIP:</b> a pick with no kit-local
     * {@code .variant} (the W7c B3 kit strip ships only descriptor-referenced customs; every
     * other server-published pickable rides the admission {@code VDEF} burst) is folded from the
     * wire FIRST ({@link JoinVariantPick#resolveFromWire}) so the t=0 hull IS the pick — the
     * observed snap (pick {@code grendel_Safetyoverride} → conquest at t=0 → L5 re-field).
     * If it still does not resolve, the t=0 hull is the neutral {@link #T0_PLACEHOLDER_VARIANT}
     * pod, never a random warship: the server published the pick as
     * pickable and WILL field it, so a descriptor-row placeholder is exactly the flash.
     */
    private static String ownT0VariantId(MatchDescriptor descriptor, long shipId) {
        String descriptorRow = variantForShipId(descriptor, shipId);
        String pick = JoinVariantPick.orEmpty(); // the plugin-set holder (never the plugin itself)
        if (pick.isEmpty() || pick.equals(descriptorRow)) {
            return descriptorRow;
        }
        boolean resolvable = variantResolves(pick);
        boolean wireFolded = false;
        if (!resolvable && JoinVariantPick.resolveFromWire(pick)) {
            resolvable = variantResolves(pick);
            wireFolded = resolvable;
        }
        if (!resolvable) {
            String placeholder = variantResolves(T0_PLACEHOLDER_VARIANT)
                    ? T0_PLACEHOLDER_VARIANT : descriptorRow;
            PilotWatchLog.info("mission build: join pick " + FallbackAnnounce.quote(pick, 64)
                    + " does not resolve in this kit (no local file, no VDEF definition folded)"
                    + " -> own t=0 hull = placeholder " + placeholder
                    + " (the server's announcement re-fields to the pick)");
            return placeholder;
        }
        if (isStationVariant(pick)) {
            PilotWatchLog.info("mission build: join pick " + FallbackAnnounce.quote(pick, 64)
                    + " is a STATION variant -> own t=0 hull = descriptor row " + descriptorRow
                    + " (the server's announcement re-fields if it fielded the pick)");
            return descriptorRow;
        }
        PilotWatchLog.info("mission build: own t=0 hull = join pick " + pick
                + " (descriptor row " + descriptorRow
                + (wireFolded ? "; folded from the wire catalog pre-mission" : "") + ")");
        return pick;
    }

    /** D-FIRSTSHIP: the neutral t=0 placeholder for a pick this kit cannot resolve;
     * a bare
     *  0-weapon pod that ships in every kit whose descriptor seats it (the standing fortress
     *  roster.2). Guarded at use: absent from a kit ⇒ the descriptor row (the shape before this placeholder). */
    static final String T0_PLACEHOLDER_VARIANT = "shuttlepod_cc_pod";

    /** Does {@code variantId} resolve in THIS install's variant registry? Some engine builds
     *  throw on an unknown id — same answer as null. */
    private static boolean variantResolves(String variantId) {
        try {
            return Global.getSettings().getVariant(variantId) != null;
        } catch (RuntimeException notAVariant) {
            return false;
        }
    }

    private static String variantForShipId(MatchDescriptor descriptor, long shipId) {
        long id = 0;
        for (MatchDescriptor.RosterRow row : descriptor.roster()) {
            for (int i = 0; i < row.count(); i++) {
                id++;
                if (id == shipId) {
                    return row.variantId();
                }
            }
        }
        throw new IllegalArgumentException("cc.predict.shipId=" + shipId
                + " is outside the descriptor's dense id contract 1.." + id
                + " (shape=" + descriptor.shape() + ")");
    }

    /**
     * W4 seat-ness by the dense id contract (the ONE law both ends key the all-skills captain
     * exclusion on): does {@code shipId} land on a {@code PilotKind.SEAT} roster row of the
     * CACHED descriptor? No descriptor / out-of-range ⇒ false — descriptor-less lanes (the
     * certified 1v1) stay on the curated captain BOTH ends even knob-ON, symmetric by
     * construction (the server's non-descriptor roster mints seat-less specs).
     */
    static boolean isSeatShipId(long shipId) {
        MatchDescriptor descriptor = cachedDescriptor();
        return descriptor != null && isSeatShipId(descriptor, shipId);
    }

    /** The own seat's id through the same walk — the plugin's own-respawn sites' predicate. */
    static boolean isOwnSeatShipId() {
        return isSeatShipId(ownShipIdForMissionLanes());
    }

    /**
     * The ship's DESCRIPTOR variant id through the same dense walk (null = no descriptor /
     * not a roster ship) — the officer-TRANSFER fallback key at the combat-time spawn
     * sites (server twin: {@code SeatSlot.descriptorVariantId()} at {@code midDeploySeat}).
     */
    static String descriptorVariantForShipIdOrNull(long shipId) {
        MatchDescriptor descriptor = cachedDescriptor();
        if (descriptor == null) {
            return null;
        }
        // Own walk, NOT variantForShipId: out-of-range must be null here (appended WAVE hulls
        // share the spawn site and live beyond the dense roster range), never a throw.
        long id = 0;
        for (MatchDescriptor.RosterRow row : descriptor.roster()) {
            for (int i = 0; i < row.count(); i++) {
                id++;
                if (id == shipId) {
                    return row.variantId();
                }
            }
        }
        return null;
    }

    /** The own seat's descriptor variant through the same walk (null = descriptor-less lane). */
    static String ownDescriptorVariantOrNull() {
        return descriptorVariantForShipIdOrNull(ownShipIdForMissionLanes());
    }

    /**
     * The ship's DESCRIPTOR side through the same dense walk (-1 = no descriptor / not a
     * roster ship) — the fleet-context side gate at the combat-time spawn sites
     * (guard (d): only descriptor-side-0 hulls take the pair; -1 keeps appended WAVE hulls
     * off it, symmetric with the server's {@code spec.owner == 0} gate).
     */
    static int descriptorSideForShipIdOrMinusOne(long shipId) {
        MatchDescriptor descriptor = cachedDescriptor();
        if (descriptor == null) {
            return -1;
        }
        long id = 0;
        for (MatchDescriptor.RosterRow row : descriptor.roster()) {
            for (int i = 0; i < row.count(); i++) {
                id++;
                if (id == shipId) {
                    return row.side();
                }
            }
        }
        return -1;
    }

    private static boolean isSeatShipId(MatchDescriptor descriptor, long shipId) {
        long id = 0;
        for (MatchDescriptor.RosterRow row : descriptor.roster()) {
            for (int i = 0; i < row.count(); i++) {
                id++;
                if (id == shipId) {
                    return row.pilot() == MatchDescriptor.PilotKind.SEAT;
                }
            }
        }
        return false;
    }

    /**
     * The own seat's DESCRIPTOR side, walked exactly like {@link #variantForShipId} — the builder's
     * copy of the plugin's {@code ownDescSide}. Fail-soft to side 0 for an id outside the dense
     * contract (the plugin defaults the same way when the walk finds no row), so the station
     * predicate stays at the pre-station absolute-index behaviour rather than throwing here.
     */
    private static int sideForShipId(MatchDescriptor descriptor, long shipId) {
        long id = 0;
        for (MatchDescriptor.RosterRow row : descriptor.roster()) {
            for (int i = 0; i < row.count(); i++) {
                id++;
                if (id == shipId) {
                    return row.side();
                }
            }
        }
        return 0;
    }

    /** The own seat the LAST {@link #build} actually fielded (0 = no build
     * yet). The predict plugin's init race guard compares its session's seat against this —
     * a mismatch means the mission was built on the AUTO fallback before a late WELCOME
     * landed, and binding would drive a hull the local fleet did not field. */
    private static volatile long lastBuiltOwnShipId;

    /** The race-guard read: what the last mission build fielded as the own seat (0 = none). */
    public static long lastBuiltOwnShipId() { // public: harness row V10
        return lastBuiltOwnShipId;
    }

    /** The own seat as MISSION-relative lanes must see it: what the running mission's build
     * actually fielded when one exists, else a fresh resolve — under AUTO the seed can land
     * BETWEEN build and a combat-time read, and a re-resolve there would disagree with the
     * fleet on the field. */
    private static long ownShipIdForMissionLanes() {
        if (ObserverMode.ON) {
            // No own seat — the dense walks answer false/null, the descriptor-less
            // shape's already-exercised values (never ship 1 via the AUTO fallback: that would
            // dress the engine's Command Shuttle pod in seat 1's officer).
            return 0L;
        }
        long built = lastBuiltOwnShipId;
        if (built != 0L) {
            return built;
        }
        // W13 leg 2: a zero-hull claim-at-play build fielded no seat — the running
        // round's seat is the bound session's (noted by the predict plugin at init), never a
        // per-frame re-resolve (which could re-enter the AUTO wait).
        long claimed = claimedSessionSeat;
        return claimed != 0L ? claimed : resolveOwnShipId();
    }

    /** W13 leg 2: does this window build registry missions with ZERO own hulls
     *  (claim at Play)? Pushed by {@code PredictDialPlanner.preparePredictDial} for every served
     *  pilot window (a dial plan exists, not the observer posture); the game-free verify harness
     *  sets it directly (V11). Default false = the byte-identical legacy build. */
    private static volatile boolean claimAtPlayBuild;

    public static void setClaimAtPlayBuild(boolean value) {
        claimAtPlayBuild = value;
    }

    /** The hull this window LAST fielded for its seat (claim / re-field / respawn), the
     *  best placeholder guess for the next Play on the kept-open session (a re-Play re-claims the
     *  same seat). Null until the first field this launch. */
    private static volatile String lastFieldedOwnVariant;
    /** The click probe's published default hull, noted by the plugin's probe thread (never
     *  read from the plugin class itself — the verify harness builds without it). */
    private static volatile String probedDefaultHull;

    static void noteFieldedOwnVariant(String variantId) {
        // A pod field (strand / observer mint / transit) never becomes the next Play's
        // placeholder — the seat's last REAL hull stays the guess; the pod remains the chain's own
        // t=0 fallback below (claimAtPlayPlaceholderVariant).
        if (variantId != null && !variantId.isEmpty() && !T0_PLACEHOLDER_VARIANT.equals(variantId)) {
            lastFieldedOwnVariant = variantId;
        }
    }

    static void noteProbedDefaultHull(String variantId) {
        if (variantId != null && !variantId.isEmpty()) {
            probedDefaultHull = variantId;
        }
    }

    /**
     * The ONE placeholder flagship a claim-at-play build fields so the
     * engine's reserves==1 auto-deploy runs (no modal deploy dialog). Preference = the hull most
     * likely to BE the seat hull (so the claim adopts in place, no swap): the last hull this window
     * fielded for its seat (a re-Play), else the join pick, else the click probe's published
     * default, else the D-FIRSTSHIP t=0 placeholder pod, else the descriptor's first SEAT
     * row. Every candidate must resolve in this kit and never be a station (flagship law).
     */
    private static String claimAtPlayPlaceholderVariant(MatchDescriptor descriptor) {
        String[] candidates = { lastFieldedOwnVariant, JoinVariantPick.orEmpty(), probedDefaultHull,
                T0_PLACEHOLDER_VARIANT };
        for (String c : candidates) {
            if (c != null && !c.isEmpty() && !isStationVariant(c) && variantResolves(c)) {
                return c;
            }
        }
        for (MatchDescriptor.RosterRow row : descriptor.roster()) {
            if (row.pilot() == MatchDescriptor.PilotKind.SEAT && !isStationVariant(row.variantId())) {
                return row.variantId();
            }
        }
        throw new IllegalStateException("claim-at-play build: no placeholder flagship resolves (no"
                + " prior own hull, no join pick, no probed default, no t=0 placeholder, no SEAT row)");
    }

    /** W13 leg 2: the bound session's seat under a zero-hull build (0 = none this round). */
    private static volatile long claimedSessionSeat;

    static void noteSessionSeat(long shipId) {
        claimedSessionSeat = shipId;
    }

    /**
     * How long the AUTO-seat build waits for connect attempt 1 to settle. On record the
     * WELCOME landed 79 ms / 379 ms after the thread start (hairpin VPN); the cap only binds when
     * the door never answers attempt 1 (server down at launch = a fast fail, not a full wait).
     */
    public static final long AUTO_SEAT_FIRST_ATTEMPT_WAIT_MILLIS = 4000L; // public: harness row V10

    /**
     * The own-seat ship id — {@code cc.predict.shipId} / {@code CC_PREDICT_SHIP_ID}, default 1
     * (the {@code PilotWatchLog.resolveSeat} idiom). Read per {@link #build} call, NOT cached:
     * the game-free verify harness varies the property between builds, and referencing
     * {@code PilotWatchLog.SEAT} would freeze it at class init. 0 = AUTO
     * ("server assigns") — the WELCOME-seeded assignment when present, else the FIRST PILOT
     * seat ascending — a deterministic local-vanilla fallback with no wire consequence (a
     * down/gave-up server round is local anyway, and the plugin init guard refuses a bind that
     * disagrees). The engine builds this mission AT app load, 0.1–1 s after the connect
     * thread starts (the title screen's {@code defineMission}) — so the AUTO read waits, bounded,
     * for connect attempt 1 to settle instead of racing it ({@link AssignedSeatFromWire}).
     */
    private static long resolveOwnShipId() {
        try {
            String raw = System.getProperty("cc.predict.shipId");
            if (raw == null || raw.isBlank()) {
                raw = System.getenv("CC_PREDICT_SHIP_ID");
            }
            long id = (raw == null || raw.isBlank()) ? 1L : Long.parseLong(raw.trim());
            if (id != 0L) {
                return id;
            }
            long seed = AssignedSeatFromWire.boundShipIdOrZeroAfterFirstAttempt(
                    AUTO_SEAT_FIRST_ATTEMPT_WAIT_MILLIS);
            return seed > 0L ? seed : firstPilotSeatShipId();
        } catch (Throwable t) {
            return 1L; // unreadable id = ship 1 (fail-closed: the byte-identical legacy shape)
        }
    }

    /** AUTO fallback: the first {@code PilotKind.SEAT} roster row's dense id ascending —
     * the same walk every seat predicate uses — else 1 (descriptor-less lane). */
    private static long firstPilotSeatShipId() {
        MatchDescriptor descriptor = cachedDescriptor();
        if (descriptor == null) {
            return 1L;
        }
        long id = 0;
        for (MatchDescriptor.RosterRow row : descriptor.roster()) {
            for (int i = 0; i < row.count(); i++) {
                id++;
                if (row.pilot() == MatchDescriptor.PilotKind.SEAT) {
                    return id;
                }
            }
        }
        return 1L;
    }

    /** The one-time knob resolve + file read + decode (callers hold the class lock). */
    private static void loadOnce() {
        if (loadAttempted) {
            return;
        }
        loadAttempted = true;
        String raw = System.getProperty("cc.match.descriptor");
        if (raw == null || raw.trim().isEmpty()) {
            raw = System.getenv("CC_MATCH_DESCRIPTOR");
        }
        if (raw == null || raw.trim().isEmpty()) {
            return; // knob unset -> the default-OFF certified launch; summary keeps its default
        }
        String path = raw.trim();
        descriptorPath = path;
        // FAIL-CLOSED - any refusal leaves cached null and names itself in
        // loadFailure; the sink below carries the reason + field tokens only, never an exception message or file text.
        Path file = null;
        try {
            file = Paths.get(path);
        } catch (RuntimeException notAPath) {
            // the knob names no lawful path: the same refusal as an unreadable file, counted by the sink below
        }
        Outcome outcome = file == null ? Outcome.refused(MatchDocumentLaws.REASON_MALFORMED, "file")
                : readAndCheck(file, MatchMissionBuilder::variantResolves);
        Failure failure = outcome.failure();
        if (failure != null) {
            cached = null;
            loadFailure = failure;
            configSummary = "cc.match.descriptor=" + path + " REFUSED (" + failure.token()
                    + ") -> no predict dial; the certified mission shape opens so the banner can name the fix";
            PilotWatchLog.error("match descriptor REFUSED path=" + path + " reason=" + failure.reason()
                    + " field=" + failure.field() + " -> no predict dial (fail-closed); the certified mission"
                    + " shape opens so the in-combat banner names the fix", null);
            return;
        }
        cached = outcome.descriptor();
        configSummary = loadedSummary(path, cached);
    }

    /**
     * The laws half of the one-time load, pure (no knob, no latch, no log, no engine): read the match file
     * BOUNDED, decode it, apply {@link MatchDocumentLaws#check} and resolve every roster variant, returning the
     * descriptor or the FIRST refusal. {@code variantExists} is the engine's variant registry in the game
     * ({@link #variantResolves}) and a fixture in the game-free rows.
     * <ol>
     *   <li>Unreadable (missing, a directory, an I/O fault) - {@code match_doc_malformed file}; the exception
     *       text is never kept (an OS message can carry the path's user name).</li>
     *   <li>Larger than {@link MatchDocumentLaws#FILE_READ_MAX} by its size, BEFORE any byte is read; the read
     *       itself stops at that many bytes (a file that grew after the size check stays bounded) and more than
     *       {@link MatchDocumentLaws#TEXT_MAX} read is refused too - {@code match_doc_oversize file}, never
     *       decoded. The same caps as the join window's own read of this file.</li>
     *   <li>The codec throws - {@code match_doc_malformed decode} (its message is never kept).</li>
     *   <li>The laws refuse - their own reason + field ({@code requireRedacted = false}: a dev document may
     *       carry live seat tokens; the redaction law belongs to the join window).</li>
     *   <li>Every roster row's variant checked in ONE pass BEFORE any {@code addToFleet}: the engine's
     *       {@code addToFleet} looks a missing variant up again outside its own try, so a missing hull is a
     *       Fatal at mission build - {@code match_doc_variant_missing <n>}, n = the DISTINCT missing ids.</li>
     * </ol>
     * UTF-8 decode (the window and the packer both write UTF-8); lines split like {@code BufferedReader.readLine}.
     *
     *
     */
    static Outcome readAndCheck(Path path, Predicate<String> variantExists) {
        String text;
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            if (ch.size() > MatchDocumentLaws.FILE_READ_MAX) {
                return Outcome.refused(MatchDocumentLaws.REASON_OVERSIZE, "file");
            }
            ByteBuffer buf = ByteBuffer.allocate(MatchDocumentLaws.FILE_READ_MAX);
            int n;
            do {
                n = ch.read(buf);
            } while (n >= 0 && buf.hasRemaining());
            if (buf.position() > MatchDocumentLaws.TEXT_MAX) {
                return Outcome.refused(MatchDocumentLaws.REASON_OVERSIZE, "file");
            }
            text = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return Outcome.refused(MatchDocumentLaws.REASON_MALFORMED, "file");
        }
        MatchDescriptor d;
        try {
            d = MatchDescriptorCodec.decode(splitLines(text));
        } catch (RuntimeException undecodable) {
            return Outcome.refused(MatchDocumentLaws.REASON_MALFORMED, "decode");
        }
        try {
            MatchDocumentLaws.check(d, false);
        } catch (MatchDocumentRefused refused) {
            return Outcome.refused(refused.reason(), refused.field());
        }
        Set<String> missing = new LinkedHashSet<String>();
        for (MatchDescriptor.RosterRow row : d.roster()) {
            if (!variantExists.test(row.variantId())) {
                missing.add(row.variantId());
            }
        }
        if (!missing.isEmpty()) {
            return Outcome.refused(MatchDocumentLaws.REASON_VARIANT_MISSING, Integer.toString(missing.size()));
        }
        return Outcome.loaded(d);
    }

    /** {@code BufferedReader.readLine}'s split ({@code \n}, {@code \r}, {@code \r\n}; no trailing empty line). */
    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<String>();
        int start = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                out.add(text.substring(start, i));
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < n) {
            out.add(text.substring(start));
        }
        return out;
    }

    /**
     * The app-load summary of a loaded file. {@code shape} and {@code matchId} are host-authored document text,
     * so each rides inside double quotes with any {@code "} or backslash escaped: a value can never close its
     * quote and plant a forged {@code key=value} token in a debrief log.
     *
     *
     */
    static String loadedSummary(String path, MatchDescriptor d) {
        return "cc.match.descriptor=" + path + " loaded: shape=" + doubleQuoted(d.shape(), MatchDocumentLaws.SHAPE_MAX)
                + " matchId=" + doubleQuoted(d.matchId(), MatchDocumentLaws.MATCH_ID_MAX)
                + " roster=" + d.roster().size() + " seats=" + d.seats().size() + " mapHalf=" + d.halfWidth()
                + " terrain=" + d.terrain();
    }

    private static String doubleQuoted(String s, int max) {
        String folded = FallbackAnnounce.sanitize(s, max);
        StringBuilder b = new StringBuilder(folded.length() + 4).append('"');
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\');
            }
            b.append(c);
        }
        return b.append('"').toString();
    }
}
