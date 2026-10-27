package cc.starsector.client;

import cc.starsector.server.match.AllSkillsEnumerator;
import cc.starsector.server.match.AllSkillsFleetLaw;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.OfficerRosterCodec;
import cc.starsector.server.match.OfficerRouting;
import cc.starsector.server.match.OfficerSkillCheck;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.SkillSpecAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;

/**
 * Client half of the officer-skills wiring: the
 * parity attach — stats do NOT ride the wire, so every locally-materialized hull (own mission
 * ship, replica spawns) must carry the SAME json-assigned officer the server attaches, or the
 * client predicts with different stats than the authority simulates (e.g. the grendel officer's
 * ballistic_mastery ELITE = +33% ballistic projectile speed). Officer table = mod data
 * {@code data/cc/officers.txt} via the engine's own {@code SettingsAPI.loadText}, decoded by the
 * shared {@link OfficerRosterCodec} (the server's parser — one grammar, zero drift). The attach
 * recipe is the probe-proven sequence, typed against the public API (classloader-B safe:
 * no reflection, no java.io).
 *
 * <p>Phase discipline: table read + decode ONCE at application load
 * ({@code CcClientModPlugin.onApplicationLoad} logs {@link #describeConfig()}) — mission build
 * and combat-time replica spawns consume only the cached map. Failure shape: settings/file
 * unavailable (game-free harness, pre-roll mod data) ⇒ empty table, loud once, every attach a
 * no-op — fail-soft SYMMETRIC (the server's absent-table lane is the same no-op). A table that
 * DECODES badly logs ERROR + empty table (fail-soft to the certified no-officer shape, loudly).
 *
 * <p>Knobs (catalog-only specials, the HulkPose idiom — no launcher plumbing):
 * {@code cc.predict.officerSkills} on|off DEFAULT ON (the recorded design choice:
 * seat-piloted ships carry their json-assigned officer's skills; default-ON confirm rides the
 * debrief ask #2; flip together with the server twin {@code cc.server.officerSkills}) and
 * {@code cc.predict.officerPortraits} on|off DEFAULT ON (the
 * toggle for the above-flux-bar officer portraits — off skips {@code setPortraitSprite},
 * leaving {@code Person.isDefault()} true (the portrait IS the non-default marker).
 * CORRECTED: NOT presentation-only after all — a measured defect proved isDefault is a
 * STATS lever ({@code SupportDoctrine.isNoOfficer} + the officer-branch skill class), and the
 * server marker is UNCONDITIONAL since the fix, so OFF re-arms SD on THIS end only =
 * a known parity-breaker under lever-ON. Keep ON for any graded watch; a null-portrait
 * "marker without a face" variant is unproven (HUD null-safety unmeasured)).
 */
public final class OfficerLoadout {

    /** W11 public-facing rename: the minted officers' FIRST name —
     *  LOCKSTEP with the server's {@code AllSkillsAttach.OFFICER_FIRST_NAME}: both sides hash
     *  {@code getFullName} into the stat signature ({@code TargetStatSig} ⇔ {@code ShipStatSig}),
     *  so a one-sided rename diverges parity whenever {@code cc.server.officerSkillsAll} is ON.
     *  {@code ClientServerLockstepSmoke} pins the two equal. */
    public static final String OFFICER_FIRST_NAME = "AlphaCoreMP";

    /** on|off, DEFAULT ON — malformed THROWS at class load (knob discipline). */
    static final boolean OFFICER_SKILLS = parseOnOff(
            "cc.predict.officerSkills", System.getProperty("cc.predict.officerSkills", "on"));

    /** on|off, DEFAULT ON — the portrait-presentation toggle. */
    static final boolean OFFICER_PORTRAITS = parseOnOff(
            "cc.predict.officerPortraits", System.getProperty("cc.predict.officerPortraits", "on"));

    private static boolean loadAttempted;
    private static Map<String, OfficerRosterCodec.OfficerSpec> byVariant = Collections.emptyMap();
    private static String configSummary = "officer table not loaded yet";
    private static int attachedCount;
    /** Wire-attach counters — every substitution/skip is ONE line plus a running total. */
    private static int wireAttachFailures;
    private static int personalitySubstitutions;
    private static int portraitSubstitutions;

    private OfficerLoadout() {
    }

    private static boolean parseOnOff(String knob, String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(v)) {
            return true;
        }
        if ("off".equals(v)) {
            return false;
        }
        throw new IllegalArgumentException(knob + " must be on|off, got: " + raw);
    }

    /** One-line config/load state for the app-load pre-warm log (first-touch + cache). */
    public static synchronized String describeConfig() {
        loadOnce();
        return configSummary;
    }

    /**
     * Is the all-skills twin ON (the mission builder's commander-lane predicate)?
     * WIRE-DELIVERED: the v5 WELCOME's {@code matchFlags}
     * bit0 via {@link MatchConfigFromWire}, seeded at admit BEFORE mission build; the old
     * {@code cc.predict.officerSkillsAll} sysprop is RETIRED (an old kit's {@code -D} line is
     * inert). ON ⇒ every SEAT hull's captain is the all-skills person (REPLACES the curated
     * row) and the PLAYER mission commander carries the fleetwide set
     * (manager-level side 0, DECIDED). Volatile read — every call site evaluates at its
     * own mint time, so a re-admit delta self-applies at the next respawn mint.
     */
    public static boolean allSkillsEnabled() {
        return MatchConfigFromWire.officerSkillsAll();
    }

    /**
     * Attach the variant's officer to {@code member} (no-op when the knobs are off / no curated
     * row and not a seat) — call BEFORE the member instantiates for combat (mission build /
     * pre-spawn). {@code seat} = this hull holds a descriptor SEAT row (W4: under
     * the all-skills knob a seat's captain is the all-skills person, REPLACING the curated row;
     * non-seat hulls always stay on the curated lane). Returns true when a captain was attached.
     */
    public static synchronized boolean attach(FleetMemberAPI member, String variantId,
            boolean seat) {
        return attach(member, variantId, null, seat);
    }

    /**
     * Transfer-aware overload for the combat-time spawn sites: {@code seatDescriptorVariantIdOrNull}
     * = the seat's DESCRIPTOR variant (the officer-TRANSFER fallback key; null = no
     * descriptor / not a roster ship — strict variant lookup, the t=0 shape). The replica spawn never calls it
     * for a seat's flying transfer pod ({@link #isSeatTransferPod}): the server builds that pod with no captain.
     */
    public static synchronized boolean attach(FleetMemberAPI member, String variantId,
            String seatDescriptorVariantIdOrNull, boolean seat) {
        PersonAPI captain = captainOrNull(variantId, seatDescriptorVariantIdOrNull, seat);
        if (captain == null || member == null) {
            return false;
        }
        if (seat && allSkillsEnabled()) {
            // CA visibility (server twin AllSkillsAttach): Level3-family
            // elite counts read the MEMBER fields, never the manager default — install the
            // union commander BEFORE the captain so it is visible at every stats derive.
            // The pair carries the side-0 fleet context FAIL-CLOSED — a null here is
            // a mission build-order bug (mintSideZeroFleetContext must run first), and a quiet
            // null pair re-mints the defect (engine throttle short-circuits to maxBonus).
            member.setFleetCommanderForStats(unionCommanderOrMint(),
                    fleetContextFleetDataOrThrow());
        }
        member.setCaptain(captain);
        return true;
    }

    /**
     * The WIRE-carried captain — attach the spec the SERVER reports it attached
     * to this hull (the RAPP v3 officer tail, decoded through the ONE shared
     * {@code OfficerRosterCodec}) so the local twin's damage-taken stats derive from the same
     * skills (the "boss heals / snaps back" overshoot: an officered wave foe's twin had
     * no captain, its hull bar ran ~2× ahead, the wire rebased it up). Wins over the
     * variant-keyed table lookup for an appended row; the server is the authority on what the
     * hull carries (server-settable). Skill ids resolve through THIS engine's
     * {@code SettingsAPI} first via the shared {@code OfficerSkillCheck} (fail-CLOSED, never a
     * half captain): an id this engine lacks = a kit/server content mismatch — the captain is
     * SKIPPED with a loud line, the round goes on (the desync is then the debrief's to show,
     * never a mid-round NPE deep in the engine's apply chain). Portrait rides the same
     * {@code cc.predict.officerPortraits} rider as the curated lane — the path is a key into
     * the engine's own sprite registry, never opened by this class. Returns true when attached.
     */
    public static synchronized boolean attach(FleetMemberAPI member,
            OfficerRosterCodec.OfficerSpec spec, long wireId) {
        if (member == null || spec == null) {
            return false;
        }
        try {
            OfficerSkillCheck.SkillResolver resolver = new OfficerSkillCheck.SkillResolver() {
                public Object skillSpecOrNull(String skillId) {
                    return Global.getSettings().getSkillSpec(skillId);
                }

                public boolean isCombatOfficerSkill(Object skillSpec) {
                    return ((SkillSpecAPI) skillSpec).isCombatOfficerSkill();
                }
            };
            for (String inertId : OfficerSkillCheck.validate(spec.variantId, spec, resolver)) {
                PilotWatchLog.info("officer skill WARN: wire captain for entity " + wireId
                        + " (variant '" + spec.variantId + "') names '" + inertId
                        + "' which exists but is NOT a combat-officer skill — inert on a captain");
            }
            // The OTHER two wire strings get the same treatment as the skill ids,
            // INSIDE this guard. An unknown personality is NOT a throw — the engine's setter
            // stores a spec-registry lookup, which returns NULL for an unknown id
            // (the lookup's throw branch is not taken) — so the token would poison
            // the Person and NPE later in the engine's own AI derive, off our stack.
            String personality = personalityOrDefault(spec.personality, wireId);
            String portrait = OFFICER_PORTRAITS
                    ? portraitOrStock(spec.portraitPath, wireId) : null;
            PersonAPI person = mintPerson(personality, spec.skills,
                    new FullName(spec.firstName, spec.lastName, FullName.Gender.ANY), spec.level,
                    portrait, spec.aiCoreId);
            member.setCaptain(person);
            attachedCount++;
            PilotWatchLog.info("officer attach WIRE entity=" + wireId + " variant=" + spec.variantId
                    + " name=" + FallbackAnnounce.quote(spec.fullName(), 64) + " personality=" + personality + " level="
                    + spec.level + " skills=" + spec.skills.size() + " portraits="
                    + (OFFICER_PORTRAITS ? "on" : "off") + " ai_core="
                    + (spec.aiCoreId.isEmpty() ? "none" : spec.aiCoreId)
                    + " total_attached=" + attachedCount);
            return true;
        } catch (RuntimeException unattachable) {
            // Fail-closed, bare hull: the member keeps NO captain and the caller spawns it anyway
            // (PredictReconcileCombatPlugin ignores the return and spawns the member) — the
            // round goes on and the debrief shows the stat gap, never a mid-frame kill out of
            // advance() (which has try/finally and NO catch). attachedCount is bumped only after
            // setCaptain returned, so total_attached never counts a failed attach.
            wireAttachFailures++;
            PilotWatchLog.error("officer attach WIRE entity " + wireId + " variant '"
                    + spec.variantId + "' SKIPPED (bare hull, round goes on): this engine could not"
                    + " resolve/mint the captain (kit/server content mismatch) — wire_attach_fails="
                    + wireAttachFailures + " — " + unattachable.getMessage(), null);
            return false;
        }
    }

    /**
     * Mint the variant's officer as a fresh {@link PersonAPI} (null when the knobs are off / no
     * curated row and not a seat) — the replica-spawn arg for the engine's own captain-bearing
     * {@code spawnShipOrWing(variant, loc, facing, burn, captain)} overload (a null captain is
     * the exact pre-officer call shape). Fresh per call: replicas respawn across rounds and a
     * shared Person across hulls is a shape campaign play never produces. {@code seat} routes
     * the W4 all-skills exclusion exactly like the server's deploy lanes (guard (e)).
     */
    public static synchronized PersonAPI captainOrNull(String variantId, boolean seat) {
        return captainOrNull(variantId, null, seat);
    }

    /**
     * Transfer-aware twin of the server's {@code OfficerAttach.maybeAttachSeat} (the transfer law:
     * "the player's skills should stay attached wherever the seat goes"): a SEAT hull whose
     * FIELDED variant has no curated row falls back to the seat's DESCRIPTOR-variant row, so a
     * {@code /ship} pick of an out-of-table (operator-exported) variant keeps the seat's officer.
     * Non-seat hulls stay strict-variant (waves keep their exact twin rows). Stats are off-wire —
     * both ends iterate the ONE {@link OfficerRouting#curatedLookupOrder} law (the
     * parity contract: {@code OfficerRoutingContractRows}).
     */
    public static synchronized PersonAPI captainOrNull(String variantId,
            String seatDescriptorVariantIdOrNull, boolean seat) {
        if (seat && allSkillsEnabled()) {
            return mintAllSkillsCaptain(variantId, seatDescriptorVariantIdOrNull);
        }
        java.util.List<String> order = OfficerRouting.curatedLookupOrder(variantId,
                seatDescriptorVariantIdOrNull, seat);
        for (int i = 0; i < order.size(); i++) {
            PersonAPI person = curatedCaptainOrNull(order.get(i));
            if (person != null) {
                if (i > 0) {
                    PilotWatchLog.info("officer TRANSFER fielded=" + variantId
                            + " has no curated row -> seat descriptor row " + order.get(i));
                }
                return person;
            }
        }
        return null;
    }

    /**
     * The ONE client mint recipe (the deferred-refactors "person-mint duplication" fold, taken
     * at the lever landing): createPerson → personality → skipRefresh batch skill-set →
     * name → level → optional portrait. ENGINE LAW: the engine's own batch-set idiom;
     * the SHIP apply chain (applyPersonalToShip) reads the skill map directly — same recipe as
     * the server end. The portrait IS Person.isDefault()'s non-default marker; a null
     * portrait leaves the default ⇒ no above-flux-bar portrait drawn.
     */
    private static PersonAPI mintPerson(String personality, Map<String, Float> skills,
            FullName name, int level, String portraitOrNull, String aiCoreIdOrEmpty) {
        PersonAPI person = Global.getSettings().createPerson();
        person.setPersonality(personality);
        MutableCharacterStatsAPI stats = person.getStats();
        stats.setSkipRefresh(true);
        for (Map.Entry<String, Float> e : skills.entrySet()) {
            stats.setSkillLevel(e.getKey(), e.getValue().floatValue());
        }
        person.setName(name);
        stats.setLevel(level);
        if (portraitOrNull != null) {
            person.setPortraitSprite(portraitOrNull);
        }
        if (aiCoreIdOrEmpty != null && !aiCoreIdOrEmpty.isEmpty()) {
            // The RAPP v4 officer line carries the core id (closed set, decoded
            // fail-closed by OfficerRosterCodec) so this twin reads isAICore() exactly like the
            // server member — CyberneticAugmentation's per-ship bonus keys off it; the vanilla
            // AICoreOfficerPluginImpl recipe sets it the same way.
            person.setAICoreId(aiCoreIdOrEmpty);
        }
        return person;
    }

    /**
     * The wire personality, canonicalized against THIS engine (the same fail-closed
     * shape as the skill-id check, and the "canonicalize against a local allowlist, store the
     * canonical id, never the wire string" doctrine). Engine seam FIRST so a mod-added personality
     * passes: {@code SettingsAPI.getPersonaltySpec} (the engine's own spelling) is a registry read
     * that returns NULL for an id this install lacks and never throws; the vanilla
     * five
     * are the backstop when settings are unavailable (game-free harness). Anything else falls
     * back to the ENGINE DEFAULT "steady" — the exact value {@code createPerson()} already mints
     * — with a counted line.
     */
    private static String personalityOrDefault(String wire, long wireId) {
        if (wire != null && (OfficerWireBounds.isVanillaPersonality(wire)
                || engineKnowsPersonality(wire))) {
            return wire;
        }
        personalitySubstitutions++;
        PilotWatchLog.info("officer WIRE entity=" + wireId + " personality '"
                + OfficerWireBounds.forLog(wire) + "' is unknown to this engine -> default '"
                + OfficerWireBounds.DEFAULT_PERSONALITY + "' (personality_substitutions="
                + personalitySubstitutions + ")");
        return OfficerWireBounds.DEFAULT_PERSONALITY;
    }

    /** Engine registry read, null-safe in a game-free harness (no settings ⇒ false, never a throw). */
    private static boolean engineKnowsPersonality(String id) {
        try {
            return Global.getSettings() != null
                    && Global.getSettings().getPersonaltySpec(id) != null;
        } catch (RuntimeException noRegistry) {
            return false;
        }
    }

    /**
     * The wire portrait path, bounded. NOT dropped to null on a miss: a null
     * portrait leaves {@code Person.isDefault()} TRUE (the ctor already
     * stamps the default sprite) and isDefault is a STATS lever on this end (the class
     * javadoc's portrait-marker note) — dropping the face would trade a cosmetic mismatch for a
     * parity break. Stock fallback = the same marker sprite the all-skills lane uses, pinned to
     * the server twin through {@link AllSkillsEnumerator}.
     */
    private static String portraitOrStock(String wire, long wireId) {
        if (OfficerWireBounds.isSafePortraitPath(wire)) {
            return wire;
        }
        portraitSubstitutions++;
        PilotWatchLog.info("officer WIRE entity=" + wireId + " portrait '"
                + OfficerWireBounds.forLog(wire) + "' is outside the portrait bound -> stock '"
                + ALLSKILLS_FALLBACK_PORTRAIT + "' (portrait_substitutions="
                + portraitSubstitutions + ")");
        return ALLSKILLS_FALLBACK_PORTRAIT;
    }

    /** The curated-lane mint (fresh Person from the variant's table row, or null). */
    private static PersonAPI curatedCaptainOrNull(String variantId) {
        if (!OFFICER_SKILLS || variantId == null) {
            return null;
        }
        loadOnce();
        OfficerRosterCodec.OfficerSpec spec = byVariant.get(variantId);
        if (spec == null) {
            return null;
        }
        PersonAPI person = mintPerson(spec.personality, spec.skills,
                new FullName(spec.firstName, spec.lastName, FullName.Gender.ANY), spec.level,
                OFFICER_PORTRAITS ? spec.portraitPath : null, spec.aiCoreId);
        attachedCount++;
        PilotWatchLog.info("officer attach variant=" + variantId + " name="
                + FallbackAnnounce.quote(spec.firstName + " " + spec.lastName, 64) + " personality=" + spec.personality + " level="
                + spec.level + " skills=" + spec.skills.size() + " portraits="
                + (OFFICER_PORTRAITS ? "on" : "off") + " total_attached=" + attachedCount);
        return person;
    }

    /**
     * The W4 all-skills SEAT captain (fresh per call, the curated-mint discipline): every
     * combat-officer skill from THIS engine's table at the shared elite rule — identical to the
     * server's {@code AllSkillsAttach} person by construction (same {@link AllSkillsEnumerator}
     * law; stats are off-wire). Portrait rides the existing presentation knob and carries the
     * PRIOR officer's face: the curated row's portraitPath for the
     * fielded variant, else the seat-descriptor row (the same fielded→descriptor order as the
     * transfer law), else a real stock sprite — local-table lookup on each end, nothing on the
     * wire, both windows agree because they key the same table by the same variant ids.
     */
    private static PersonAPI mintAllSkillsCaptain(String variantId,
            String seatDescriptorVariantIdOrNull) {
        AllSkillsEnumerator.Sets s = allSkillsSets();
        String portraitSource = "off";
        String portrait = null;
        if (OFFICER_PORTRAITS) {
            loadOnce();
            OfficerRosterCodec.OfficerSpec spec = null;
            for (String id : OfficerRouting.curatedLookupOrder(variantId,
                    seatDescriptorVariantIdOrNull, true)) {
                spec = byVariant.get(id);
                if (spec != null) {
                    portraitSource = "curated:" + id;
                    break;
                }
            }
            portrait = spec != null ? spec.portraitPath : ALLSKILLS_FALLBACK_PORTRAIT;
            if (spec == null) {
                portraitSource = "fallback";
            }
        }
        PersonAPI person = mintPerson("steady", s.ship,
                new FullName(OFFICER_FIRST_NAME, "AllSkills", FullName.Gender.ANY), s.ship.size(), portrait,
                "");
        attachedCount++;
        PilotWatchLog.info("allskills captain minted skills=" + s.ship.size()
                + " portraits=" + (OFFICER_PORTRAITS ? "on" : "off")
                + " portrait_source=" + portraitSource
                + " total_attached=" + attachedCount);
        return person;
    }

    /**
     * The flagship stats lever + the short-list display.
     * Ground truth: the own MISSION-BUILT flagship's local
     * stats never took the attached captain's skills (client window 175 vs authority 185), and
     * NO per-frame path re-derives ship stats from the captain — applied MutableStat modifiers
     * (skill-id-keyed, {@code CharacterStats.getShipEffects} `skillId_ship_N`) persist until the
     * next explicit unapply. So: (1) STATS — the engine's own {@code ShipAPI.setCaptain}
     * unapply+apply re-derive with an EXPLICITLY minted person (never {@code getCaptain()},
     * the standing law); idempotent, safe at t=0 / respawn / match-reset alike. (2) DISPLAY — the
     * combat officer surface is the CODEX:
     * the R chain calls {@code CodexDialog.showTemp(ship.getFleetMember())},
     * whose detail panel reads {@code FleetMember.getCaptain()},
     * NOT the combat Ship field — so in the all-skills lane a zero-skill DISPLAY TWIN
     * ({@link #applySeatDisplayTwin}) goes onto the MEMBER and the 19-row skill wall disappears
     * with no reflection and no sim effect. TRAP: {@code FleetMember.getCaptain()}
     * short-circuits the player FLAGSHIP to the character-data person when
     * {@code getPlayerFleet()!=null} — expected DEAD in title-launched missions (re-check row
     * rides every officer watch).
     */
    /**
     * First-frame twin: derives the FIELDED variant id from the live hull itself (null-safe —
     * a game-free harness ship may carry no variant; the lever then no-ops on the curated
     * lane's own null-variant law).
     */
    public static boolean applySeatStatsLever(ShipAPI ship,
            String seatDescriptorVariantIdOrNull, boolean seat, String lane, boolean adopted) {
        if (ship == null) {
            return false;
        }
        com.fs.starfarer.api.combat.ShipVariantAPI v = ship.getVariant();
        return applySeatStatsLever(ship, v == null ? null : v.getHullVariantId(),
                seatDescriptorVariantIdOrNull, seat, lane, adopted);
    }

    /**
     * The lever line carries the settled-speed instrumentation — a
     * measured run saw every logged maxSpeed as a fly-in transient (both t=0 levers fired
     * pre-adoption; the respawn lever fired 1 ms before fielding), so the bare modified value
     * reads zero-flux/warp-in numbers (772 = 722+50). {@code withoutBoost} = the engine's own
     * boost-less derive ({@code ShipEngineControllerAPI.getMaxSpeedWithoutBoost}), {@code base}
     * = the stat's base value, {@code lane} = t0|respawn|settled, {@code adopted} = the
     * caller's initial-world-adopted flag at fire time, {@code player} = ship==getPlayerShip
     * (the wrong-Ship lead's discriminator). Both probes fail-soft (game-free harness:
     * withoutBoost=NaN / player=false, never a throw).
     */
    public static synchronized boolean applySeatStatsLever(ShipAPI ship, String fieldedVariantId,
            String seatDescriptorVariantIdOrNull, boolean seat, String lane, boolean adopted) {
        if (ship == null) {
            return false;
        }
        PersonAPI real = captainOrNull(fieldedVariantId, seatDescriptorVariantIdOrNull, seat);
        if (real == null) {
            return false; // knobs off / no row — the attach() no-op lane, quiet by its law
        }
        if (seat && allSkillsEnabled()) {
            installSeatCommanderVisibility(ship); // Member field before the re-derive
        }
        ship.setCaptain(real);
        float maxSpeed = ship.getMutableStats().getMaxSpeed().getModifiedValue();
        float base = ship.getMutableStats().getMaxSpeed().getBaseValue();
        float withoutBoost = Float.NaN;
        try {
            if (ship.getEngineController() != null) {
                withoutBoost = ship.getEngineController().getMaxSpeedWithoutBoost();
            }
        } catch (RuntimeException e) {
            // game-free mock ship — the NaN row IS the "unmeasurable here" marker
        }
        boolean player = false;
        try {
            player = Global.getCombatEngine() != null
                    && Global.getCombatEngine().getPlayerShip() == ship;
        } catch (RuntimeException e) {
            // Global unavailable in the game-free harness — false, never a throw
        }
        PilotWatchLog.info("flagship stats lever setCaptain variant=" + fieldedVariantId
                + " level=" + real.getStats().getLevel()
                + " maxSpeed=" + maxSpeed
                + " withoutBoost=" + withoutBoost
                + " base=" + base
                + " lane=" + lane
                + " adopted=" + adopted
                + " player=" + player);
        if (seat) {
            applySeatDisplayTwin(ship);
        }
        return true;
    }

    /**
     * The display twin, factored out later so the REPLICA spawn lanes run it too
     * (a surviving suspect: rival-seat replicas were never twinned, and the codex targets
     * the RETICLE ship — an R on the rival flagship read the real 19-skill person). Puts a
     * marker-skill twin (same name/face/level; skills = the one {@code cc_skills_all}
     * "SKILLS: ALL" row) of the ship's CURRENT captain onto the deployed
     * MEMBER via the public {@code FleetMemberAPI.setCaptain}: the codex chain reads the
     * member's captain while the combat Ship keeps the REAL person (fighter launches
     * read the Ship field), and {@code FleetMember.updateStats} REPLACES
     * the member's stats object — it cannot touch the live Ship's captured one.
     * No-op outside the all-skills lane —
     * curated officers' short real lists ARE the display. Fail-soft: an unresolvable member
     * skips only the cosmetic twin, loudly; the sim captain is untouched.
     *
     * <p><b>Re-ground-truth — the member IS the codex's source, and a SHIP-side swap is
     * REJECTED:</b> R in combat = the reticle
     * Ship → {@code getFleetMember()} (LIVE member; it lazy-resolves the
     * SAME manager member this class twins; null ⇒ a synthetic captain-less member) →
     * {@code CodexDataV2.createTempFleetMemberEntry} stores that member itself as the entry
     * param (no copy) → the detail panel's portrait + hover tooltip off
     * {@code member.getCaptain()}. NO skill-list surface reads {@code Ship.getCaptain()} — and
     * swapping it anyway would be a STAT mutation, not display: {@code Ship.setCaptain}
     * unapplies/applies personal skills, fighter launches inherit the
     * carrier's ship captain per launch, OperationsCenter gates a CP
     * bonus on {@code ship.getCaptain()==commander} every frame,
     * and AI personality derives from it live. So an observed giant
     * list over a probe-proven twinned member means the twin got DISPLACED after install —
     * {@link #maintainSeatDisplayTwin} re-asserts it per frame and NAMES the displacer.
     *
     * <p><b>Re-ground-truth:</b> the GIANT list is
     * NOT the codex at all —
     * R sets a TARGET, and the list is the target-info HUD:
     * a skill-list panel built from {@code ship.getCaptain()} + {@code setWithSkillList(true)}, gated on
     * {@code !captain.isDefault()} and skipped for the player's own ship. That reads the combat
     * {@code Ship}'s CACHED captain field — a surface no member-side twin can touch. The codex
     * FLEET_MEMBER panel renders no skill list on ANY surface (the member captain feeds only its
     * 48x48 portrait, no tooltip), so this twin is codex-portrait cosmetics only; the actual
     * replica display fix is a separate lane, never more member twinning.
     * capSkills=5 in probe rows is BENIGN: every {@code createPerson()} mint carries a constant
     * +4 baseline rows (1+4=5 twin, 19+4=23 all-skills).
     */
    public static synchronized boolean applySeatDisplayTwin(ShipAPI ship) {
        if (ship == null || !allSkillsEnabled()) {
            return false;
        }
        PersonAPI real = ship.getCaptain();
        if (real == null) {
            PilotWatchLog.info("allskills display twin SKIPPED: ship carries no captain"
                    + " (cosmetic only; sim captain untouched)");
            return false;
        }
        FleetMemberAPI member = deployedMemberOrNull(ship);
        if (member == null) {
            PilotWatchLog.info("allskills display twin SKIPPED: deployed member unresolved"
                    + " (cosmetic only; sim captain untouched)");
            return false;
        }
        FullName n = real.getName();
        // "steady" verbatim: this lane only runs for mintAllSkillsCaptain persons
        // (PersonAPI exposes no personality getter in this API).
        PersonAPI twin = mintPerson("steady",
                markerSkillsOrEmpty(),
                new FullName(n.getFirst(), n.getLast(), n.getGender()),
                real.getStats().getLevel(), real.getPortraitSprite(), "");
        member.setCaptain(twin);
        expectedTwinByMember.put(member, twin);
        // ASK-3 probe rides here — the ONLY scope holding twin+real+member together (the twin
        // is retained nowhere); read-only, fail-soft, capped. SEAM, not a
        // direct call: verify-match-descriptor compiles THIS file standalone against the api
        // jar only, and the probe references engine-internal classes — the plugin wires it at app load.
        TwinProbe probe = twinProbe;
        if (probe != null) {
            try {
                probe.afterTwinInstall(ship, member, real, twin);
            } catch (Throwable t) {
                PilotWatchLog.info("twin probe seam WARN: " + t.getClass().getSimpleName()
                        + " (cosmetic lane unaffected)");
            }
        }
        com.fs.starfarer.api.combat.ShipVariantAPI v = ship.getVariant();
        PilotWatchLog.info("allskills display twin on member skills="
                + twin.getStats().getSkillLevel(MARKER_SKILL_ID) + "x" + MARKER_SKILL_ID
                + " variant=" + (v == null ? "null" : v.getHullVariantId())
                + " (ASK-3 short-list lane; combat Ship keeps the real person)");
        return true;
    }

    /** The "SKILLS: ALL" marker skill (mod data {@code data/characters/skills/});
     * {@code cc_}-prefixed ⇒ the shared {@link AllSkillsEnumerator} exclusion keeps it
     * off every all-skills captain on BOTH ends. */
    static final String MARKER_SKILL_ID = "cc_skills_all";

    /** The display twin's skill map: the one marker row — or empty (the zero-skill
     * shape) when THIS engine's table lacks the marker (mismatched mod data; display lane
     * stays fail-soft, loudly once). */
    private static boolean markerSkillWarned;

    private static Map<String, Float> markerSkillsOrEmpty() {
        try {
            if (Global.getSettings().getSkillSpec(MARKER_SKILL_ID) != null) {
                return Collections.singletonMap(MARKER_SKILL_ID, Float.valueOf(1.0f));
            }
        } catch (RuntimeException e) {
            // unknown id may throw instead of returning null — same law as skillSpecOrNull
        }
        if (!markerSkillWarned) {
            markerSkillWarned = true;
            PilotWatchLog.info("marker skill WARN: '" + MARKER_SKILL_ID + "' not in this"
                    + " engine's table -> zero-skill twin fallback (mod data mismatch?)");
        }
        return Collections.emptyMap();
    }

    /** Per-member expected-twin anchor (a fix: the earlier SINGLE-slot anchor produced BOTH
     * logged "DISPLACED" rows as cross-member artifacts — a replica twin install overwrote
     * the own seat's slot, so the guard "healed" members that were never displaced and once
     * mislabeled a first install as a displacement). Weak keys: members die with their
     * round/respawn and entries follow. All access under the class lock (every caller is a
     * synchronized static). Never read for stats. */
    private static final Map<FleetMemberAPI, PersonAPI> expectedTwinByMember =
            new WeakHashMap<>();

    /** Displacement-log latch PER MEMBER: log each displacing person once, not per frame. */
    private static final Map<FleetMemberAPI, PersonAPI> lastDisplacerByMember =
            new WeakHashMap<>();

    /**
     * The sticky guard, called per frame on the OWN seat hull from the predict
     * plugin's advance. Revision: the earlier shape anchored on ONE static slot, and BOTH
     * logged "DISPLACED" rows were cross-member artifacts of that slot (the aurora-replica
     * install overwrote the grendel own anchor; the seat2 row was a first install mislabeled) —
     * no real displacement has ever been observed. The guard now anchors on the member's OWN
     * expected twin, so a future DISPLACED row is REAL displacement of THAT member; a member
     * never twinned by an install lane is left alone (installs belong to the install lanes).
     * Cost: one manager map lookup + two map reads per frame; display-only (member captain),
     * the sim captain is never touched. No-op outside the all-skills lane / for non-seat hulls.
     */
    public static synchronized void maintainSeatDisplayTwin(ShipAPI ship, boolean seat) {
        if (ship == null || !seat || !allSkillsEnabled() || expectedTwinByMember.isEmpty()) {
            return;
        }
        FleetMemberAPI member = deployedMemberOrNull(ship);
        if (member == null) {
            return; // fail-soft quiet: transient (e.g. mid-respawn); install lanes re-fire
        }
        PersonAPI expected = expectedTwinByMember.get(member);
        if (expected == null) {
            return; // never twinned via an install lane — not this guard's member
        }
        PersonAPI current = member.getCaptain();
        if (current == expected) {
            return;
        }
        if (current != lastDisplacerByMember.get(member)) {
            lastDisplacerByMember.put(member, current);
            String who;
            if (current == null) {
                who = "null";
            } else {
                who = "'" + (current.getName() == null ? "?" : current.getName().getFullName())
                        + "' skills=" + current.getStats().getSkillsCopy().size()
                        + " sameAsShipCaptain=" + (current == ship.getCaptain());
            }
            PilotWatchLog.info("allskills display twin DISPLACED on member by " + who
                    + " -> re-installing (per-member sticky guard)");
        }
        applySeatDisplayTwin(ship);
    }

    /**
     * ASK-3 twin-install probe seam: wired by {@code CcClientModPlugin} at application
     * load to {@code CodexCaptainProbe} — a SEAM (not a direct call) because the game-free
     * {@code verify-match-descriptor} gate compiles this file standalone against the api jar
     * only, while the probe references engine-internal classes (FleetMember/CampaignEngine). Null = no
     * probe (the gate's standalone compile, or a future removal). Implementations must not
     * throw; the call site guards anyway (cosmetic lane).
     */
    interface TwinProbe {
        void afterTwinInstall(ShipAPI ship, FleetMemberAPI member, PersonAPI real,
                PersonAPI twin);
    }

    static volatile TwinProbe twinProbe;

    /** The ship's deployed FleetMember, or null (fail-soft — display-twin consumer only). */
    private static FleetMemberAPI deployedMemberOrNull(ShipAPI ship) {
        try {
            CombatFleetManagerAPI mgr =
                    Global.getCombatEngine().getFleetManager(ship.getOwner());
            DeployedFleetMemberAPI dfm = mgr == null ? null : mgr.getDeployedFleetMember(ship);
            return dfm == null ? null : dfm.getMember();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * DEFECT-2 fallback for an all-skills seat with NO curated row either key (the old
     * hardcode `portrait_mercenary1.png` was a typo — blank portrait box) — pinned to
     * the server twin through the shared law.
     */
    private static final String ALLSKILLS_FALLBACK_PORTRAIT =
            AllSkillsEnumerator.ALLSKILLS_MARKER_PORTRAIT;

    /**
     * W4, client half: skill-up the PLAYER mission commander
     * with the UNION sheet (ship∪commander — the vanilla character shape; the campaign player is
     * captain and commander in one person) — the engine itself wires this person into the side-0
     * {@code CombatFleetManager} {@code setDefaultCommander} at combat start (SPOT-CHECKED
     * in the engine), so every side-0 fleetData-less hull takes the
     * {@code ALL_SHIPS_IN_FLEET}/{@code ALL_FIGHTERS_IN_FLEET} buffs at stats create — symmetric
     * with the server's install-seam commander. The person is ALSO cached as this end's union
     * commander for the member-level {@code setFleetCommanderForStats} visibility installs:
     * {@code CyberneticAugmentation$Level3} counts the commander's elite skills through the
     * MEMBER fields, and only a union-sheet person carries the 14 elites an A/B on a real save
     * measured. Guard (a) unchanged: the engine applies fleetwide itself. Old guard (b)
     * (never touch {@code setFleetCommanderForStats}) is FALSIFIED by the static proof —
     * combat ship stats are factory-fresh, the member field is combat-safe.
     */
    public static synchronized void skillUpMissionCommander(PersonAPI commander) {
        AllSkillsEnumerator.Sets s = allSkillsSets();
        MutableCharacterStatsAPI stats = commander.getStats();
        stats.setSkipRefresh(true);
        for (Map.Entry<String, Float> e : s.union.entrySet()) {
            stats.setSkillLevel(e.getKey(), e.getValue().floatValue());
        }
        stats.setLevel(s.union.size());
        unionCommanderCached = commander;
        PilotWatchLog.info("allskills commander skill-up: " + s.union.size()
                + " union-sheet skills on the PLAYER mission commander (cached for member-level"
                + " CA visibility)");
    }

    /**
     * W15 v1 editor oracle: a FRESH, DETACHED person carrying the union sheet
     * (ship + commander skills, the same {@link AllSkillsEnumerator} law as the server's
     * {@code AllSkillsAttach}) so the editor's s-mod cap / vent bonus / stats answer for the
     * all-skills match person. Never cached, never installed on a fleet or member the match
     * uses — the caller's detached fleet member only. Stats refreshed before return.
     */
    public static synchronized PersonAPI mintUnionPersonDetached() {
        AllSkillsEnumerator.Sets s = allSkillsSets();
        PersonAPI person = Global.getSettings().createPerson();
        MutableCharacterStatsAPI stats = person.getStats();
        stats.setSkipRefresh(true);
        for (Map.Entry<String, Float> e : s.union.entrySet()) {
            stats.setSkillLevel(e.getKey(), e.getValue().floatValue());
        }
        stats.setLevel(s.union.size());
        stats.setSkipRefresh(false);
        stats.refreshCharacterStatsEffects();
        return person;
    }

    /**
     * The editor's skills toggle: a FRESH, DETACHED person carrying the SHIP-scope all-skills set — the
     * bastion seat captain's sheet ({@link #mintAllSkillsCaptain}: {@code Sets.ship}, accessible skills only, never the
     * deprecated ones) — for the editor's one-member fab. Same discipline as {@link #mintUnionPersonDetached}: never
     * cached here, never installed on a match fleet or member. Stats refreshed before return.
     */
    public static synchronized PersonAPI mintShipSkillsPersonDetached() {
        AllSkillsEnumerator.Sets s = allSkillsSets();
        PersonAPI person = Global.getSettings().createPerson();
        person.setPersonality("steady");
        MutableCharacterStatsAPI stats = person.getStats();
        stats.setSkipRefresh(true);
        for (Map.Entry<String, Float> e : s.ship.entrySet()) {
            stats.setSkillLevel(e.getKey(), e.getValue().floatValue());
        }
        stats.setLevel(s.ship.size());
        stats.setSkipRefresh(false);
        stats.refreshCharacterStatsEffects();
        return person;
    }

    /** The one union commander this end installs on seat members. Prefer the engine's
     * own mission commander (cached by {@link #skillUpMissionCommander} — the same person the
     * engine wires into the side-0 manager, so member reads and manager reads agree); a lane
     * that runs before the mission build mints a stats-identical stand-in (same union law). */
    private static PersonAPI unionCommanderCached;

    private static synchronized PersonAPI unionCommanderOrMint() {
        if (unionCommanderCached != null) {
            return unionCommanderCached;
        }
        AllSkillsEnumerator.Sets s = allSkillsSets();
        unionCommanderCached = mintPerson("steady", s.union,
                new FullName(OFFICER_FIRST_NAME, "Commander", FullName.Gender.ANY), s.union.size(),
                ALLSKILLS_FALLBACK_PORTRAIT, "");
        PilotWatchLog.info("allskills union commander MINTED pre-mission-build ("
                + s.union.size() + " skills; stand-in, stats-identical to the mission person)");
        return unionCommanderCached;
    }

    /**
     * Member-level CA visibility for a LIVE seat hull (the spawnShipOrWing lanes, where no
     * member exists pre-spawn): puts the union commander on the deployed member's
     * {@code fleetCommanderForStats}. The engine REUSES reserves members by spec id,
     * so this heals every SUBSEQUENT instantiate of the member
     * (respawns re-derive CA at create); the CURRENT hull's create-time CA writers are already
     * fixed where the member existed pre-spawn (mission build / appended lane), and on the
     * first-ever spawnShipOrWing of a spec they stay elided locally until the next respawn —
     * a KNOWN bounded residual (local presentation sim only, server-reconciled).
     * Fail-soft: an unresolved member skips quietly (transient mid-respawn; lanes re-fire).
     */
    public static synchronized void installSeatCommanderVisibility(ShipAPI ship) {
        if (ship == null || !allSkillsEnabled()) {
            return;
        }
        FleetMemberAPI member = deployedMemberOrNull(ship);
        if (member == null) {
            return;
        }
        if (member.getFleetCommanderForStats() == null) {
            // Pair with the side-0 fleet context, fail-closed like attach() — this
            // lane only runs mid-combat, long after the mission build minted the context.
            member.setFleetCommanderForStats(unionCommanderOrMint(),
                    fleetContextFleetDataOrThrow());
            PilotWatchLog.info("allskills CA visibility: union commander installed on deployed"
                    + " member (fleetCommanderForStats + fleet context; heals from next"
                    + " instantiate)");
        }
    }

    /**
     * Side-0 fleet context, client end (server twin
     * {@code AllSkillsAttach}): the engine's commander-skill threshold throttle
     * short-circuits to FULL effect on a null
     * member {@code FleetDataAPI} or a fleet-less commander — so every threshold-scaled
     * commander skill granted at maximum regardless of the deployed fleet. The shared
     * {@link AllSkillsFleetLaw} walk law + the typed {@link FleetContextMint} mint the
     * engine-real fleet data from the DESCRIPTOR's side-0 roster (identical set both ends ⇒
     * identical throttle off-wire by construction) and gate-2 token the union commander
     * (the reflective server minter is B-denied here, so each end mints with
     * its own vehicle and grading asserts {@code fleet_ctx} echo EQUALITY). Minted ONCE per
     * boot at mission build, BEFORE any attach/skill-up.
     */
    private static AllSkillsFleetLaw.Mint fleetContextCached;

    public static synchronized void mintSideZeroFleetContext(MatchDescriptor descriptor) {
        if (!allSkillsEnabled() || fleetContextCached != null) {
            return;
        }
        try {
            java.util.List<String> ids = AllSkillsFleetLaw.sideZeroShipVariantIds(
                    descriptor.roster(), FleetContextMint.stationCheck());
            PersonAPI union = unionCommanderOrMint();
            fleetContextCached = FleetContextMint.mint(ids, union);
            // The both-ends parity/grading lane: byte-identical echo body on the server stdout.
            PilotWatchLog.info("allskills " + fleetContextCached.echo);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "allskills fleet-context mint failed (client end): " + e, e);
        }
    }

    /** The cached context's fleet data — fail-closed: a null cache under the knob is a mission
     * build-order bug, and a silent null pair is the throttle short-circuit defect re-minted. */
    private static FleetDataAPI fleetContextFleetDataOrThrow() {
        if (fleetContextCached == null) {
            throw new IllegalStateException("allskills fleet context not minted —"
                    + " mintSideZeroFleetContext must run at mission build before any"
                    + " attach/install (ordering bug)");
        }
        return (FleetDataAPI) fleetContextCached.fleetData;
    }

    /**
     * Gate 2 for the ENGINE's own mission default commander: the mission build
     * skill-ups that person with the union sheet ({@link #skillUpMissionCommander}), so its
     * stats ALSO need the fleet token or every fleetwide apply through the side-0 manager
     * default short-circuits. Fail-closed on ordering like the pair installs.
     */
    public static synchronized void installMissionCommanderFleetToken(PersonAPI commander) {
        if (!allSkillsEnabled()) {
            return;
        }
        if (fleetContextCached == null) {
            throw new IllegalStateException("allskills mission-commander fleet token before"
                    + " mint (ordering bug)");
        }
        FleetContextMint.installCommanderFleetToken(fleetContextCached, commander);
        PilotWatchLog.info("allskills mission commander fleet token installed (gate 2;"
                + " engine throttle reaches the side-0 manager-default fleetwide apply)");
    }

    /** commander-lane adoption race: the mission-build stash the
     * post-adoption recovery replays. Captured at EVERY build regardless of the wire flag —
     * the race is precisely a build under matchFlags still 0x0. */
    private static MatchDescriptor missionBuildDescriptor;
    private static boolean commanderLanesDone;
    private static boolean recoveryWaitLogged;

    /** Mission-build receipt ({@code MatchMissionBuilder} side-0 leg) — arms
     * {@link #maybeRecoverCommanderLanes}; {@code fired} = the build itself ran the
     * skill-up + token (adoption won the race), so nothing is owed.
     * <p>side-free — the commander lanes skill up
     * LOCAL side 0 (the own team) whatever the own absolute side, and every non-seat row carries
     * its own absolute-side pair or the descriptor side-0 fleet-context one
     * ({@link #attachAppendedCommander}, {@link #attachMissionStationCommander}), so no own side is
     * stashed and none is refused. Also re-arms the one {@code allskills own_abs_side} line for
     * this build. */
    public static synchronized void noteMissionBuildCommanderLanes(MatchDescriptor descriptor,
            boolean fired) {
        missionBuildDescriptor = descriptor;
        commanderLanesDone = fired;
        recoveryWaitLogged = false;
        appendedPairEchoedSide = -1;
    }

    /**
     * Idempotent post-adoption recovery: the three commander/
     * fleet-context lanes ({@link #mintSideZeroFleetContext} / {@link #skillUpMissionCommander}
     * / {@link #installMissionCommanderFleetToken}) fire ONE-SHOT at mission build on the
     * volatile wire flag, while every per-ship captain lane re-fires post-adoption — so a build
     * that lost the admit race (WELCOME matchFlags seeded AFTER the build read 0x0) missed the
     * fleetwide set for the whole boot: CoordinatedManeuvers/PhaseCorps silently absent on THIS
     * end (the server install is unconditional ⇒ client-low own-speed desync) and every
     * fail-closed install primed to throw on the null fleet context. Called per frame
     * from the predict plugin's advance BEFORE the seat levers; cost until the race case goes
     * live = one volatile read + a null check. The engine wires the mission's PLAYER default
     * commander into the side-0 manager at combat start —
     * the SAME person the build lane would have skilled, read back typed via
     * {@code getDefaultCommander()}. Replays the build's exact lane order (mint → skill-up →
     * token); every leg is idempotent, so a flag-ON build that hit the null-commander SKIP
     * lane heals here too (the mint no-ops on its cache).
     */
    public static synchronized boolean maybeRecoverCommanderLanes() {
        if (commanderLanesDone || !allSkillsEnabled() || missionBuildDescriptor == null) {
            return false;
        }
        PersonAPI commander = null;
        try {
            CombatFleetManagerAPI mgr = Global.getCombatEngine() == null ? null
                    : Global.getCombatEngine().getFleetManager(0);
            commander = mgr == null ? null : mgr.getDefaultCommander();
        } catch (RuntimeException e) {
            // game-free harness / pre-combat transient — keep polling, quiet
        }
        if (commander == null) {
            if (!recoveryWaitLogged) {
                recoveryWaitLogged = true;
                PilotWatchLog.info("cc-cmdlane recovery WAITING: matchFlags adopted post-build,"
                        + " side-0 manager default commander not readable yet (retries each"
                        + " frame; the union-commander watch bar is the backstop)");
            }
            return false;
        }
        mintSideZeroFleetContext(missionBuildDescriptor);
        skillUpMissionCommander(commander);
        installMissionCommanderFleetToken(commander);
        commanderLanesDone = true;
        PilotWatchLog.info("cc-cmdlane RECOVERED post-adoption: mission build lost the admit"
                + " race (matchFlags 0x0 at build) — fleet context + union commander skill-up +"
                + " fleet token replayed on the manager default commander");
        return true;
    }

    /**
     * GRUNT coverage, side-aware (guard (d): descriptor-side-0 ONLY — a side-1 pair
     * would buff the enemy fleet with the union commander): put the (union, fleet-context)
     * pair on a descriptor-side-0 member so its stats derive reaches the engine throttle.
     * Station members skip, mirroring the server's {@code !spec.station} gate and the shared
     * walk law (a station is never a campaign fleet member). {@code descSide} < 0 = not a
     * roster ship (appended waves) — no-op.
     */
    public static synchronized void attachFleetContext(FleetMemberAPI member, int descSide) {
        if (!allSkillsEnabled() || member == null || descSide != 0) {
            return;
        }
        com.fs.starfarer.api.combat.ShipVariantAPI variant = member.getVariant();
        if (variant != null && variant.isStation()) {
            return;
        }
        member.setFleetCommanderForStats(unionCommanderOrMint(),
                fleetContextFleetDataOrThrow());
    }

    /**
     * Post-spawn twin for the {@code spawnShipOrWing} replica lane (no member exists
     * pre-spawn): resolves the deployed member like {@link #installSeatCommanderVisibility}
     * and heals from the next instantiate (the known residual class — first-spawn derive stays
     * short-circuited locally; server-reconciled). Fail-soft on an
     * unresolved member (transient mid-spawn; lanes re-fire).
     */
    public static synchronized void attachFleetContext(ShipAPI ship, int descSide) {
        if (ship == null || !allSkillsEnabled() || descSide != 0) {
            return;
        }
        FleetMemberAPI member = deployedMemberOrNull(ship);
        if (member != null && member.getFleetCommanderForStats() == null) {
            attachFleetContext(member, descSide);
        }
    }

    /** {@link #appendedPairKind} — NO member pair is installed; the
     *  row keeps what it already carries (a seat's side-blind pair, a descriptor side-0 grunt's fleet-context
     *  pair) or, with the knob off, resolves its manager default. */
    static final int APPENDED_PAIR_NONE = 0;
    /** {@link #appendedPairKind} — the union commander with NO fleet data. */
    static final int APPENDED_PAIR_UNION = 1;
    /** {@link #appendedPairKind} — a plain, skill-less commander. */
    static final int APPENDED_PAIR_BARE = 2;

    /** The one plain commander every absolute-side-1 non-seat row shares, minted on first use and never
     *  skilled: {@code createPerson()} is the same bare person the server's side-1 manager holds as its default.
     *One shared person keeps a side's
     *  commander set at a single plain entry however many waves spawn. */
    private static PersonAPI bareCommanderCached;

    /** The own absolute side the last {@code allskills own_abs_side} line named; -1 = none since the last
     *  mission build ({@link #noteMissionBuildCommanderLanes} re-arms it). */
    private static int appendedPairEchoedSide = -1;

    /**
     * Is this seat row's body the command-shuttle pod a transfer is
     * flying on the seat's own wire id? Pure.
     *
     * <p>WHY: a transfer re-keys the seat's wire id onto a pod the server builds bare, with no captain and no
     * member pair, owned by the seat's side, so it resolves that side's commander default. The replica twin of
     * that pod therefore takes the POD rule, never the seat rule: the replica spawn attaches no captain and no
     * seat or fleet-context pair, and {@link #appendedPairKind} reads it as a pod on its absolute side.
     *
     * <p>The pod variant alone does not decide it: a seat row on the pod variant with no transfer flying is a
     * seat the server deployed through its seat path, captain and pair included (the fortress roster's own pod
     * seat, a picked pod). A transfer from such a pod body re-flies that same body, and the transfer lane flips
     * its bound replica in place, so it never mints here. {@code podVariant}: the row's variant is the pod's
     * wire variant; {@code podFlying}: the transfer lane's flying mark for the seat, the same mark the pod
     * replica's collision class keys on. A seat still on its hull while the mark is set (the re-point not yet
     * folded) stays a seat.
     */
    static boolean isSeatTransferPod(boolean seat, boolean podVariant, boolean podFlying) {
        return seat && podVariant && podFlying;
    }

    /**
     * The member commander pair a NON-SEAT replica row carries,
     * keyed on the row's ABSOLUTE side, pure.
     *
     * <p>WHY: the two ends hold their all-skills manager default on different sides. The server installs it on
     * the absolute side-0 manager only, so a pair-less side-1 member there resolves to that manager's plain
     * default; this end's mission build skills up LOCAL side 0, which is the own team whatever its absolute
     * side. A pair-less row therefore resolved to the union on a north client's own-team rows (the server:
     * plain) and to a plain person on its other-team rows (the server: the union). The engine reads the
     * member pair BEFORE the manager default, for the ship's create and for
     * the carrier's fighter writer alike, so an explicit pair keyed on the absolute side resolves the server's
     * commander on every client.
     *
     * <p>The law ({@link #pairKindForAbsoluteSide} on the inverse of {@code relSide}): knob off, a seat row
     * (its side-blind pair rides {@link #attach}; the install hands a seat's flying transfer pod in as a pod,
     * {@link #isSeatTransferPod}) or a non-combat side = {@link #APPENDED_PAIR_NONE}; absolute
     * side 1 = {@link #APPENDED_PAIR_BARE}; absolute side 0 = {@link #APPENDED_PAIR_UNION} with NO fleet data
     * for an appended row, a pod or a station (the server leaves each pair-less, so it derives its threshold
     * skills at full effect off the side-0 default, where the side-0 fleet-context pair would throttle them),
     * and {@link #APPENDED_PAIR_NONE} for a descriptor non-station row, which already carries that
     * fleet-context pair ({@link #attachFleetContext}, the twin of the server's grunt install). A pod takes the
     * pair like a wave: the server builds it with no pair on its seat's side, so it resolves that side's
     * manager default. A south client (own side 0) therefore resolves every row to the commander it resolved
     * before. {@code relSide}: the registry's relative side (0 = own team, 1 = the other team); {@code ownAbs}:
     * the own team's absolute side (0 = south, 1 = north).
     */
    static int appendedPairKind(boolean allSkills, boolean appended, boolean seat, boolean pod, boolean station,
            int relSide, int ownAbs) {
        return pairKindForAbsoluteSide(allSkills, appended, seat, pod, station,
                absoluteSideOrMinusOne(relSide, ownAbs));
    }

    /** {@link #appendedPairKind}'s law on a side that is already
     *  absolute (a descriptor row's own side, as the mission build reads it), pure. */
    static int pairKindForAbsoluteSide(boolean allSkills, boolean appended, boolean seat, boolean pod,
            boolean station, int absSide) {
        if (!allSkills || seat) {
            return APPENDED_PAIR_NONE;
        }
        if (absSide == 1) {
            return APPENDED_PAIR_BARE;
        }
        if (absSide != 0) {
            return APPENDED_PAIR_NONE;
        }
        return appended || pod || station ? APPENDED_PAIR_UNION : APPENDED_PAIR_NONE;
    }

    /** The inverse of {@code TeamPerspectiveLaw.relSide} — the own
     *  team (relative 0) is the own absolute side, the other team its opposite; -1 when either input is not a
     *  fighting side (0 or 1). Spelled out here, not delegated: the match-descriptor verifier compiles this
     *  class without the perspective law. */
    static int absoluteSideOrMinusOne(int relSide, int ownAbs) {
        if ((relSide != 0 && relSide != 1) || (ownAbs != 0 && ownAbs != 1)) {
            return -1;
        }
        return relSide == 0 ? ownAbs : 1 - ownAbs;
    }

    /** The bounded key line — returned once per mission build per
     *  own absolute side (a team change mid-round names the new side once), else null. It states the key the
     *  pairs were cut on, never the stats they derive (a both-ends stats diff grades those). */
    static synchronized String appendedPairEchoOrNull(int ownAbs) {
        if (ownAbs == appendedPairEchoedSide) {
            return null;
        }
        appendedPairEchoedSide = ownAbs;
        return "allskills own_abs_side=" + ownAbs + " (non-seat rows and pods: absolute side 0 = the union pair"
                + " with no fleet data, side 1 = a plain pair; side-0 grunts keep theirs)";
    }

    /**
     * Install {@link #appendedPairKind}'s pair on a
     * member-first replica's member BEFORE the engine instantiates it (the spawn path calls this for every such
     * replica, appended or descriptor, after the captain and the descriptor fleet-context installs, ahead of
     * {@code spawnFleetMember}). The station flag is the member's own variant, the same test
     * {@link #attachFleetContext} skips a station on. {@code seat} / {@code pod}: the row holds a descriptor seat
     * id / the row's variant is the pod's wire variant; {@code podFlying}: the transfer lane's flying mark for the
     * row's seat. A seat row that {@link #isSeatTransferPod} names is handed to the law as a pod, never a seat.
     * Returns the kind installed; {@link #APPENDED_PAIR_NONE} leaves the member untouched.
     */
    public static synchronized int attachAppendedCommander(FleetMemberAPI member, boolean appended, boolean seat,
            boolean pod, boolean podFlying, int relSide, int ownAbs) {
        if (member == null) {
            return APPENDED_PAIR_NONE;
        }
        com.fs.starfarer.api.combat.ShipVariantAPI variant = member.getVariant();
        return attachAppendedCommander(member::setFleetCommanderForStats, OfficerLoadout::appendedPairPerson,
                appended, seat, pod, podFlying, variant != null && variant.isStation(), relSide, ownAbs);
    }

    /** The install with its two engine touches passed in
     *  (the member's pair setter, the person for a kind), so a game-free row drives the whole path, the seat flag
     *  the law reads included. The fleet data is ALWAYS null: no server row this pair twins carries one. */
    static synchronized int attachAppendedCommander(BiConsumer<PersonAPI, FleetDataAPI> pairSetter,
            IntFunction<PersonAPI> personForKind, boolean appended, boolean seat, boolean pod, boolean podFlying,
            boolean station, int relSide, int ownAbs) {
        int kind = appendedPairKind(allSkillsEnabled(), appended, seat && !isSeatTransferPod(seat, pod, podFlying),
                pod, station, relSide, ownAbs);
        if (kind == APPENDED_PAIR_NONE) {
            return kind;
        }
        String echo = appendedPairEchoOrNull(ownAbs);
        if (echo != null) {
            PilotWatchLog.info(echo);
        }
        pairSetter.accept(personForKind.apply(kind), null);
        return kind;
    }

    /**
     * The mission build's twin of {@link #attachAppendedCommander}
     * for a station it fields straight into the local reserves (a registry shape's non-own station: the engine
     * deploys it at combat start, so its pair must ride the member before then). {@code descSide} is the row's
     * descriptor side, already absolute: side 0 = the union with no fleet data (both ends skip a station in the
     * fleet-context pair, so the server's station resolves its side-0 default), side 1 = the plain pair. No own
     * side is read and no {@code own_abs_side} line is spent: the build may not know its seat yet (claim at
     * play builds on the side-0 fallback). Returns the kind installed.
     */
    public static synchronized int attachMissionStationCommander(FleetMemberAPI station, int descSide) {
        if (station == null) {
            return APPENDED_PAIR_NONE;
        }
        return attachMissionStationCommander(station::setFleetCommanderForStats,
                OfficerLoadout::appendedPairPerson, descSide);
    }

    /** The station install with its two engine touches passed in,
     *  so a game-free row drives it; fleet data ALWAYS null, like every pair this law installs. */
    static synchronized int attachMissionStationCommander(BiConsumer<PersonAPI, FleetDataAPI> pairSetter,
            IntFunction<PersonAPI> personForKind, int descSide) {
        int kind = pairKindForAbsoluteSide(allSkillsEnabled(), false, false, false, true, descSide);
        if (kind != APPENDED_PAIR_NONE) {
            pairSetter.accept(personForKind.apply(kind), null);
        }
        return kind;
    }

    /** The person for a non-NONE kind — the union commander
     *  ({@link #unionCommanderOrMint}: the same person this end's seat pairs carry) or the shared plain one. */
    private static synchronized PersonAPI appendedPairPerson(int kind) {
        if (kind == APPENDED_PAIR_UNION) {
            return unionCommanderOrMint();
        }
        if (bareCommanderCached == null) {
            bareCommanderCached = Global.getSettings().createPerson();
        }
        return bareCommanderCached;
    }

    private static AllSkillsEnumerator.Sets allSkillsSetsCached;

    /**
     * The cached all-skills sets from THIS engine's table (typed resolver — classloader-B: no
     * reflection). FAIL-CLOSED: with the knob ON, an unreadable table throws — a silent skip
     * would predict unbuffed stats against a buffed authority (the exact desync the shared law
     * exists to prevent). Runs the {@code OfficerSkillCheck} invariant assert like the server.
     */
    private static synchronized AllSkillsEnumerator.Sets allSkillsSets() {
        if (allSkillsSetsCached != null) {
            return allSkillsSetsCached;
        }
        AllSkillsEnumerator.Sets s = AllSkillsEnumerator.enumerate(new AllSkillsEnumerator.SkillTable() {
            public java.util.List<String> sortedSkillIds() {
                return Global.getSettings().getSortedSkillIds();
            }

            public Object skillSpecOrNull(String skillId) {
                try {
                    return Global.getSettings().getSkillSpec(skillId);
                } catch (RuntimeException e) {
                    return null; // a missing id may throw instead of returning null — same law
                }
            }

            public boolean isCombatOfficerSkill(Object skillSpec) {
                return ((SkillSpecAPI) skillSpec).isCombatOfficerSkill();
            }

            public java.util.List<?> effects(Object skillSpec) {
                return ((SkillSpecAPI) skillSpec).getEffectsAPI();
            }

            public String effectTypeName(Object effect) {
                com.fs.starfarer.api.characters.SkillEffectType type =
                        ((SkillSpecAPI.SkillEffectSpecAPI) effect).getType();
                return type == null ? "" : type.name();
            }

            public boolean hasTag(Object skillSpec, String tag) {
                return ((SkillSpecAPI) skillSpec).hasTag(tag);
            }

            public boolean isElite(Object skillSpec) {
                return ((SkillSpecAPI) skillSpec).isElite();
            }
        });
        OfficerSkillCheck.SkillResolver resolver = new OfficerSkillCheck.SkillResolver() {
            public Object skillSpecOrNull(String skillId) {
                return Global.getSettings().getSkillSpec(skillId);
            }

            public boolean isCombatOfficerSkill(Object skillSpec) {
                return ((SkillSpecAPI) skillSpec).isCombatOfficerSkill();
            }
        };
        OfficerSkillCheck.validateIds("<all-skills ship set>", s.ship.keySet(), resolver);
        OfficerSkillCheck.validateIds("<all-skills commander set>", s.commander.keySet(), resolver);
        PilotWatchLog.info("allskills sets ship=" + s.ship.size() + " commander="
                + s.commander.size() + " knob_on=true");
        allSkillsSetsCached = s;
        return allSkillsSetsCached;
    }

    private static void loadOnce() {
        if (loadAttempted) {
            return;
        }
        loadAttempted = true;
        if (!OFFICER_SKILLS) {
            configSummary = "cc.predict.officerSkills=off -> no officers attached";
            return;
        }
        String text;
        try {
            text = Global.getSettings().loadText("data/cc/officers.txt");
        } catch (LinkageError e) {
            // Build defect (a class missing from the mod jar) — NEVER data. Fail-soft here is
            // how a test match ran a silent no-officer client; a loud boot Fatal is correct.
            throw e;
        } catch (Throwable t) {
            // Settings null (game-free verify harness) or file absent (pre-roll mod data):
            // fail-soft SYMMETRIC — the server's absent-table lane is the same no-op.
            configSummary = "officer table UNAVAILABLE (" + t.getClass().getSimpleName()
                    + ") -> no officers attached";
            PilotWatchLog.info("officer table: " + configSummary);
            return;
        }
        try {
            Map<String, OfficerRosterCodec.OfficerSpec> map = new LinkedHashMap<String, OfficerRosterCodec.OfficerSpec>();
            for (OfficerRosterCodec.OfficerSpec s : OfficerRosterCodec.decode(text)) {
                map.put(s.variantId, s);
            }
            byVariant = Collections.unmodifiableMap(map);
            configSummary = byVariant.size() + " officer row(s) loaded, portraits="
                    + (OFFICER_PORTRAITS ? "on" : "off");
        } catch (LinkageError e) {
            // The same lesson: OfficerRosterCodec missing from the jar surfaced HERE
            // as a "DECODE FAILED" no-op. A build defect must be a loud boot Fatal, not fail-soft.
            throw e;
        } catch (Throwable t) {
            byVariant = Collections.emptyMap();
            configSummary = "officer table DECODE FAILED: " + t.getMessage()
                    + " -> no officers attached";
            // 1290 (81) C7: a decode failure that silently drops the WHOLE officer roster was
            // buried at INFO — promoted to the error lane (2-arg; no 1-arg overload exists).
            PilotWatchLog.error("officer table ERROR: " + configSummary, t);
        }
        // The client twin of the server's boot-time skill-id law, via the
        // shared OfficerSkillCheck: fail-CLOSED at LOAD with a named IllegalStateException
        // (deliberately OUTSIDE the decode catch above — this throw must never fall into the
        // DECODE-FAILED fail-soft lane, because a table THIS engine cannot apply would
        // otherwise NPE opaquely at replica spawn mid-round). Typed resolver by classloader-B
        // law (no reflection); the server end feeds the same law its reflective probe. Runs
        // only when settings were reachable — the game-free UNAVAILABLE lane returned above,
        // symmetric with the server's null-settings skip.
        OfficerSkillCheck.SkillResolver resolver = new OfficerSkillCheck.SkillResolver() {
            public Object skillSpecOrNull(String skillId) {
                return Global.getSettings().getSkillSpec(skillId);
            }

            public boolean isCombatOfficerSkill(Object skillSpec) {
                return ((SkillSpecAPI) skillSpec).isCombatOfficerSkill();
            }
        };
        for (OfficerRosterCodec.OfficerSpec s : byVariant.values()) {
            for (String inertId : OfficerSkillCheck.validate(s.variantId, s, resolver)) {
                PilotWatchLog.info("officer skill WARN: '" + inertId + "' (variant '"
                        + s.variantId + "') exists but is NOT a combat-officer skill — it"
                        + " applies nothing to a ship captain");
            }
        }
    }
}
