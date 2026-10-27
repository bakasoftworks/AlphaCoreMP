package cc.starsector.server.platform;

import cc.starsector.server.match.AllSkillsEnumerator;
import cc.starsector.server.match.AllSkillsFleetData;
import cc.starsector.server.match.AllSkillsFleetLaw;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.OfficerSkillCheck;
import cc.starsector.server.runtime.ServerConfig;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Server half of the all-skills lane.
 * Two levers,
 * one knob:
 * <ul>
 * <li><b>SEAT captain</b>: every SEAT hull gets a fresh captain carrying EVERY combat-officer
 *     skill (elite rule per {@link AllSkillsEnumerator}) — mutually exclusive with the curated
 *     {@code OfficerAttach} row (guard (e): REPLACES, never stacks; the deploy lanes route
 *     seat specs here and everything else to {@code maybeAttachByVariant}). NEVER the station
 *     member (guard (c): {@code setCaptain} fans to child modules and would replace the curated
 *     AI-core row) — a station is structurally not a seat, so the seat routing enforces it.</li>
 * <li><b>Side-0 UNION commander</b> (manager-level side 0, BOTH ends):
 * ONE cached Person per boot carrying the ship∪commander UNION sheet —
 *     the vanilla character shape (the campaign player is captain and commander in one person).
 *     Installed via the public {@code CombatFleetManagerAPI.setDefaultCommander} on the side-0
 *     manager at the addFleet install seam — fleetData-less members pick it up inside
 *     {@code FleetMember.instantiateForCombat} and the ship factory applies the
 *     {@code ALL_SHIPS_IN_FLEET}/{@code ALL_FIGHTERS_IN_FLEET} sets at stats create. Side 0
 *     ONLY (guard (d): a side-1 commander buffs the Remnant waves). ADDITIONALLY every
 *     all-skills SEAT member gets {@code setFleetCommanderForStats(union, null)} at attach:
 *     {@code CyberneticAugmentation$Level3} (and family) counts the commander's elite skills
 *     through the MEMBER fields ({@code getFleetCommanderForStats() ?? getFleetCommander()}),
 *     never through the manager default — without the member field the count is 0 and the CA
 *     ±14% writers elide (the residual). Union ⇒ count = the reference save's 14. Guard
 *     (a) unchanged: the engine applies fleetwide itself, nothing here re-applies manually.
 *     Old guard (b) ("member pre-apply would double-apply") is
 *     FALSIFIED by static proof: the ship factory creates COMBAT stats fresh from the
 *     variant and applies fleetwide exactly once there;
 *     {@code FleetMember.updateStats} touches only the member's own stats object, which
 *     never flows into combat — the member field is combat-safe by construction.</li>
 * </ul>
 *
 * <p>Knob {@code cc.server.officerSkillsAll} / {@code CC_SERVER_OFFICER_SKILLS_ALL} /
 * {@code server.officerSkillsAll}: STRICT bool, DEFAULT OFF (opt-in power-up — the
 * curated default-ON lane stays the certified baseline). ONE knob for both ends: the server's
 * value seeds the WELCOME {@code matchFlags} bit0 and every client reads its all-skills state
 * from that bit at admit (stats themselves do not ride the wire), so there is no client knob
 * to flip; the old client sysprop {@code cc.predict.officerSkillsAll} is retired.
 *
 *
 * <p>Sets come from THIS engine's own table via the shared {@link AllSkillsEnumerator} law
 * (reflective resolve, the {@code OfficerAttach} SkillProbe idiom), cached per boot, and re-run
 * through {@link OfficerSkillCheck#validateIds} as the cheap engine-sourced invariant assert.
 * FAIL-CLOSED throughout: with the knob ON, any resolve/enumerate/attach failure throws — a
 * half-applied power-up is a stats desync by construction.
 *
 * <p>The union commander's CHARACTER stats are refreshed headless right after the mint
 * ({@link PersonStatsRefresh}, W15 editor D1): the refit rule the
 * registrar enforces on custom variants reads them (max vents/caps = size table + the person's
 * bonus), and a bare-stats person said 30 where the vanilla refit and the client editor said 35.
 */
public final class AllSkillsAttach {

    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    private static final String SETTINGS_API = "com.fs.starfarer.api.SettingsAPI";
    private static final String PERSON_API = "com.fs.starfarer.api.characters.PersonAPI";
    private static final String FLEET_MEMBER_API = "com.fs.starfarer.api.fleet.FleetMemberAPI";
    private static final String SKILL_SPEC_API = "com.fs.starfarer.api.characters.SkillSpecAPI";
    private static final String EFFECT_SPEC_API =
            "com.fs.starfarer.api.characters.SkillSpecAPI$SkillEffectSpecAPI";
    private static final String FLEET_DATA_API = "com.fs.starfarer.api.campaign.FleetDataAPI";

    /**
     * The non-default marker sprite for every all-skills mint (DEFECT-1 fix) — pinned
     * to the client twin through the shared law. Headless-safe: the path is a
     * string on the Person; nothing loads it without render.
     */
    private static final String NON_DEFAULT_MARKER_PORTRAIT =
            AllSkillsEnumerator.ALLSKILLS_MARKER_PORTRAIT;

    /** W11 public-facing rename: the minted officers' FIRST name —
     *  LOCKSTEP with the client's {@code OfficerLoadout.OFFICER_FIRST_NAME} (both sides hash
     *  {@code getFullName} into the stat signature); the client build's
     *  {@code ClientServerLockstepSmoke} pins the two equal. */
    public static final String OFFICER_FIRST_NAME = "AlphaCoreMP";

    /** STRICT bool, DEFAULT OFF (empty/false → off, true → on, anything else throws). */
    private static final boolean ALL_SKILLS = parseAllSkills(ServerConfig.resolve(
            "cc.server.officerSkillsAll", "CC_SERVER_OFFICER_SKILLS_ALL",
            "server.officerSkillsAll", ""));

    private static Handles handles;
    private static AllSkillsEnumerator.Sets sets;
    private static Object unionCommander;
    private static AllSkillsFleetLaw.Mint fleetContext;
    private static int captainsAttached;

    private AllSkillsAttach() {
    }

    private static boolean parseAllSkills(String raw) {
        switch (raw) {
            case "":
            case "false":
                return false;
            case "true":
                return true;
            default:
                throw new IllegalArgumentException(
                        "cc.server.officerSkillsAll must be true|false, got: " + raw);
        }
    }

    /** Is the all-skills knob ON for this boot (the deploy lanes' routing predicate)? */
    public static boolean enabled() {
        return ALL_SKILLS;
    }

    /** Logged once per process: a person whose portrait read threw (it reads not-minted). */
    private static volatile boolean mintedReadFailLogged;

    /**
     * Is {@code person} an all-skills CAPTAIN this
     * process minted ({@link #attachAllSkillsCaptain} at a seat spawn, {@link #mintSeatCaptain}
     * on a transfer target)? WHY the portrait: it is the one field every captain mint shares that
     * no other fielded person carries — the mint REQUIRES the {@link #NON_DEFAULT_MARKER_PORTRAIT}
     * (the non-default marker: {@code Person.isDefault()} is only the portrait, and it is stats),
     * a curated table or wave row names its own portrait (none names the marker), and a vanilla
     * default person carries the settings default sprite; level, name and skill reads would each
     * need a second law to stay in step with the mint. The union commander carries the marker too, so it is excluded by identity against
     * the ONE cached instance (none cached = none exists in this process). Read by method NAME and
     * ARITY ({@code getPortraitSprite}/0) on the object's own runtime class, so a contract row
     * drives it with a plain fake. PURE apart from one failure line: null, a missing getter or any
     * throw reads false (the caller then announces the bare row).
     */
    public static boolean isMintedPerson(Object person) {
        Object union;
        synchronized (AllSkillsAttach.class) {
            union = unionCommander;
        }
        return isMintedPerson(person, union);
    }

    /**
     * The {@link #isMintedPerson(Object)} law with the union commander passed in ({@code null} =
     * none minted) — the seam a contract row drives with its own stand-in union. Swallows a
     * failed read (one line per process); a caller that must COUNT every loss takes
     * {@link #isMintedPersonOrThrow(Object)}.
     *
     */
    public static boolean isMintedPerson(Object person, Object unionCommanderOrNull) {
        try {
            return isMintedPersonOrThrow(person, unionCommanderOrNull);
        } catch (Exception e) {
            if (!mintedReadFailLogged) {
                mintedReadFailLogged = true;
                Throwable cause = (e instanceof InvocationTargetException && e.getCause() != null)
                        ? e.getCause() : e;
                System.out.println("cc-allskills minted_person_read_failed err="
                        + cause.getClass().getSimpleName() + " (logged once; reads not-minted)");
            }
            return false;
        }
    }

    /**
     * The THROWING form of {@link #isMintedPerson(Object)}: the same
     * portrait law, but a failed read (no getter, an inaccessible one, a getter that threw)
     * propagates instead of latching one line per process. WHY: the transfer verbs' source-row
     * facts ({@code SeatRebind.sourceAllSkillsCaptain} / {@code sourceCaptainFallback}) are
     * fail-closed, and a fail-closed path must COUNT every loss — their own catch prints a
     * {@code npeer-serve transfer_source_*_read_failed} line per failure (R3b pins it).
     */
    public static boolean isMintedPersonOrThrow(Object person) throws ReflectiveOperationException {
        Object union;
        synchronized (AllSkillsAttach.class) {
            union = unionCommander;
        }
        return isMintedPersonOrThrow(person, union);
    }

    /** The {@link #isMintedPersonOrThrow(Object)} law with the union commander passed in. */
    public static boolean isMintedPersonOrThrow(Object person, Object unionCommanderOrNull)
            throws ReflectiveOperationException {
        if (person == null || person == unionCommanderOrNull) {
            return false;
        }
        Object sprite = person.getClass().getMethod("getPortraitSprite").invoke(person);
        return NON_DEFAULT_MARKER_PORTRAIT.equals(sprite);
    }

    /**
     * The captain-ONLY mint for a LIVE hull the
     * seat moves onto mid-round (a transfer target, a station module) — the {@link
     * #attachAllSkillsCaptain} person shape verbatim (same personality, ship-set skills, elite
     * rule, level, first name, marker portrait), WITHOUT the union commander pair: vanilla's
     * transfer moves only the captain, fleet-wide skills apply only at ship creation, and the
     * pair on a live carrier target would split its fighters from the client's plain-commander
     * replica. No row of its own: the caller's re-key row carries the {@code captain=} token
     * (one sim-thread line per commit). Counts into {@code total_attached}. THROWS with the knob
     * OFF (the caller routes on {@link #enabled()}) and on any mint failure.
     */
    public static synchronized Object mintSeatCaptain(String shipLabel) throws Exception {
        if (!ALL_SKILLS) {
            throw new IllegalStateException("mintSeatCaptain('" + shipLabel
                    + "') with the knob OFF — the re-key verbs must route on enabled()");
        }
        AllSkillsEnumerator.Sets s = setsOrEnumerate();
        Object person = PersonMint.mint("steady", s.ship, OFFICER_FIRST_NAME, "AllSkills",
                s.ship.size(), NON_DEFAULT_MARKER_PORTRAIT);
        captainsAttached++;
        return person;
    }

    /**
     * Attach the all-skills captain to a SEAT member — call BEFORE {@code spawnFleetMember},
     * only when {@link #enabled()} (callers route: seat+knob ⇒ here, else the curated
     * {@code OfficerAttach} lane). Fail-closed THROWS on any failure.
     */
    public static synchronized void attachAllSkillsCaptain(Object memberApi, String shipLabel) {
        if (!ALL_SKILLS) {
            throw new IllegalStateException("attachAllSkillsCaptain('" + shipLabel
                    + "') with the knob OFF — the deploy lanes must route on enabled()");
        }
        if (memberApi == null) {
            throw new IllegalStateException("attachAllSkillsCaptain('" + shipLabel
                    + "'): null member");
        }
        try {
            Handles h = handlesOrResolve();
            AllSkillsEnumerator.Sets s = setsOrEnumerate();
            // CA visibility BEFORE any captain-derived stats exist: Level3-family elite
            // counts read the MEMBER fields, so the union commander goes on the member itself.
            // The pair carries the side-0 fleet context when minted — the engine's
            // threshold throttle resolves fleet data through this same member field. Probe
            // mains (RealEngineAllSkillsProbeMain/RealEnginePhaseCooldownProbeMain) never mint
            // a context and keep the fab-less null shape deliberately.
            h.setFleetCommanderForStats.invoke(memberApi, unionCommanderOrMint(),
                    fleetContext == null ? null : fleetContext.fleetData);
            Object person = PersonMint.mint("steady", s.ship, OFFICER_FIRST_NAME, "AllSkills",
                    s.ship.size(), NON_DEFAULT_MARKER_PORTRAIT);
            h.setCaptain.invoke(memberApi, person);
            captainsAttached++;
            System.out.println("cc-allskills captain ship='" + shipLabel + "' skills="
                    + s.ship.size() + " fc_for_stats=union total_attached=" + captainsAttached);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = (e instanceof InvocationTargetException && e.getCause() != null)
                    ? e.getCause() : e;
            throw new IllegalStateException("all-skills captain attach failed for '" + shipLabel
                    + "': " + cause, cause);
        }
    }

    /**
     * Install the all-skills default commander on the SIDE-0 manager at the addFleet install
     * seam — no-op when the knob is OFF (the seam calls unconditionally). The engine reads it
     * at every {@code instantiateForCombat} of a fleetData-less member on that side, t=0 and
     * mid-round alike, so one install per round covers fly-ins and re-deploys.
     */
    public static synchronized void maybeInstallSideZeroCommander(Object manager) {
        if (!ALL_SKILLS) {
            return;
        }
        if (manager == null) {
            throw new IllegalStateException("all-skills commander install: null side-0 manager");
        }
        try {
            Handles h = handlesOrResolve();
            Object person = unionCommanderOrMint();
            Method setDefaultCommander =
                    manager.getClass().getMethod("setDefaultCommander", h.personApiClass);
            setDefaultCommander.invoke(manager, person);
            System.out.println("cc-allskills commander side=0 skills="
                    + setsOrEnumerate().union.size()
                    + " (union sheet) installed (manager-level; fleetwide applies at"
                    + " instantiateForCombat)");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = (e instanceof InvocationTargetException && e.getCause() != null)
                    ? e.getCause() : e;
            throw new IllegalStateException("all-skills commander install failed: " + cause, cause);
        }
    }

    /**
     * Side-0 fleet context, descriptor lane: mint the engine-real {@code FleetData}
     * carrying the present battle's side-0 roster (shared walk law + engine station check) and
     * gate-2 token the union commander — no-op when the knob is OFF or already minted (one
     * context per boot; the descriptor is static across rounds). Union minted FIRST: the mint
     * installs the fleet token on its stats and re-derives them.
     */
    public static synchronized void maybeMintSideZeroFleetContext(
            List<MatchDescriptor.RosterRow> roster) {
        if (!ALL_SKILLS || fleetContext != null) {
            return;
        }
        try {
            ClassLoader loader = AllSkillsAttach.class.getClassLoader();
            List<String> ids = AllSkillsFleetLaw.sideZeroShipVariantIds(roster,
                    AllSkillsFleetData.engineStationCheck(loader));
            mintFleetContext(loader, ids);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = (e instanceof InvocationTargetException && e.getCause() != null)
                    ? e.getCause() : e;
            throw new IllegalStateException(
                    "all-skills fleet-context mint (descriptor lane) failed: " + cause, cause);
        }
    }

    /**
     * Fallback-lane twin for the descriptor-less certified 1v1: the side-0 fleet is the
     * literal variant list (one demo hull). Same no-op/caching law as the descriptor lane.
     */
    public static synchronized void maybeMintSideZeroFleetContextFromVariantIds(
            List<String> variantIds) {
        if (!ALL_SKILLS || fleetContext != null) {
            return;
        }
        try {
            mintFleetContext(AllSkillsAttach.class.getClassLoader(), variantIds);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = (e instanceof InvocationTargetException && e.getCause() != null)
                    ? e.getCause() : e;
            throw new IllegalStateException(
                    "all-skills fleet-context mint (variant-id lane) failed: " + cause, cause);
        }
    }

    private static void mintFleetContext(ClassLoader loader, List<String> ids) throws Exception {
        Object union = unionCommanderOrMint();
        fleetContext = AllSkillsFleetData.mint(loader, ids, union);
        // The both-ends parity/grading lane: byte-identical echo body on the client sidelog.
        System.out.println("cc-allskills " + fleetContext.echo);
    }

    /**
     * Member pair install for the battle deploy lanes: put (union commander, side-0
     * fleet data) on {@code memberApi}'s ForStats fields so the engine's threshold throttle
     * resolves BOTH gates at every stats derive. EVERY side-0 non-station member needs this —
     * grunts and seats alike — or its derive short-circuits to full bonus (the defect).
     * No-op when the knob is OFF; a null cached context with the knob ON is a deploy ORDERING
     * bug (the mint must precede member minting) and THROWS — fail-closed, never a silent
     * maxBonus.
     */
    public static synchronized void installFleetContext(Object memberApi) {
        if (!ALL_SKILLS) {
            return;
        }
        if (memberApi == null) {
            throw new IllegalStateException("all-skills fleet-context install: null member");
        }
        if (fleetContext == null) {
            throw new IllegalStateException("all-skills fleet-context install before mint —"
                    + " the deploy lane must maybeMintSideZeroFleetContext* before minting"
                    + " members (ordering bug)");
        }
        try {
            Handles h = handlesOrResolve();
            h.setFleetCommanderForStats.invoke(memberApi, unionCommanderOrMint(),
                    fleetContext.fleetData);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = (e instanceof InvocationTargetException && e.getCause() != null)
                    ? e.getCause() : e;
            throw new IllegalStateException(
                    "all-skills fleet-context install failed: " + cause, cause);
        }
    }

    /**
     * The ONE union-sheet commander person per boot: the same instance serves the
     * side-0 manager default AND every seat member's {@code fleetCommanderForStats} — vanilla
     * fields one commander for the whole fleet, and one shared instance keeps every engine
     * read (elite counts, fleetwide apply) trivially identical across hulls.
     */
    private static Object unionCommanderOrMint() throws Exception {
        if (unionCommander != null) {
            return unionCommander;
        }
        AllSkillsEnumerator.Sets s = setsOrEnumerate();
        Object person = PersonMint.mint("steady", s.union, OFFICER_FIRST_NAME, "Commander",
                s.union.size(), NON_DEFAULT_MARKER_PORTRAIT);
        // W15 editor D1: the mint leaves the CHARACTER stats bare
        // (skipRefresh — the engine's refresh NPEs headless), but the refit rule the server
        // enforces on custom variants (max vents/caps, RealEngineVariantRegistrar #4) reads THIS
        // person's character stats, exactly as the vanilla refit reads the player's REFRESHED
        // ones. Apply the sheet's CHARACTER_STATS effects headless, once, fail-closed: a throw
        // here leaves unionCommander null (no half-refreshed commander is ever handed out).
        PersonStatsRefresh.Result refreshed =
                PersonStatsRefresh.applyCharacterStatsEffects(person, s.union);
        System.out.println("cc-allskills union commander character-stats refresh (headless, D1) "
                + refreshed);
        unionCommander = person;
        return unionCommander;
    }

    /** The cached sets, enumerated once per boot through the shared law + invariant assert. */
    private static AllSkillsEnumerator.Sets setsOrEnumerate() throws Exception {
        if (sets != null) {
            return sets;
        }
        final Handles h = handlesOrResolve();
        AllSkillsEnumerator.SkillTable table = new AllSkillsEnumerator.SkillTable() {
            public List<String> sortedSkillIds() throws Exception {
                Object ids = h.getSortedSkillIds.invoke(h.settings);
                @SuppressWarnings("unchecked")
                List<String> cast = (List<String>) ids;
                return cast;
            }

            public Object skillSpecOrNull(String skillId) throws Exception {
                try {
                    return h.getSkillSpec.invoke(h.settings, skillId);
                } catch (InvocationTargetException e) {
                    return null; // a missing id may throw instead of returning null — same law
                }
            }

            public boolean isCombatOfficerSkill(Object skillSpec) throws Exception {
                return Boolean.TRUE.equals(h.isCombatOfficerSkill.invoke(skillSpec));
            }

            public List<?> effects(Object skillSpec) throws Exception {
                return (List<?>) h.getEffectsAPI.invoke(skillSpec);
            }

            public String effectTypeName(Object effect) throws Exception {
                Object type = h.effectGetType.invoke(effect);
                return type == null ? "" : ((Enum<?>) type).name();
            }

            public boolean hasTag(Object skillSpec, String tag) throws Exception {
                return Boolean.TRUE.equals(h.specHasTag.invoke(skillSpec, tag));
            }

            public boolean isElite(Object skillSpec) throws Exception {
                return Boolean.TRUE.equals(h.specIsElite.invoke(skillSpec));
            }
        };
        AllSkillsEnumerator.Sets s = AllSkillsEnumerator.enumerate(table);
        // The cheap engine-sourced invariant assert: both sets re-validate through
        // the shared curated-lane law; inert ids in the SHIP set are impossible by construction
        // (isCombatOfficerSkill IS the filter), commander-set non-combat ids are expected and
        // live on the commander, so only resolvability is asserted there.
        OfficerSkillCheck.SkillResolver resolver = new OfficerSkillCheck.SkillResolver() {
            public Object skillSpecOrNull(String skillId) throws Exception {
                return h.getSkillSpec.invoke(h.settings, skillId);
            }

            public boolean isCombatOfficerSkill(Object skillSpec) throws Exception {
                return Boolean.TRUE.equals(h.isCombatOfficerSkill.invoke(skillSpec));
            }
        };
        OfficerSkillCheck.validateIds("<all-skills ship set>", s.ship.keySet(), resolver);
        OfficerSkillCheck.validateIds("<all-skills commander set>", s.commander.keySet(), resolver);
        int elite = 0;
        for (Float level : s.ship.values()) {
            if (level.floatValue() >= 2.0f) {
                elite++;
            }
        }
        System.out.println("cc-allskills sets ship=" + s.ship.size() + " (elite=" + elite
                + ") commander=" + s.commander.size() + " knob_on=true");
        sets = s;
        return sets;
    }

    private static Handles handlesOrResolve() throws Exception {
        if (handles != null) {
            return handles;
        }
        ClassLoader loader = AllSkillsAttach.class.getClassLoader();
        Handles h = new Handles();
        h.settings = Class.forName(GLOBAL_CLASS, true, loader).getMethod("getSettings").invoke(null);
        if (h.settings == null) {
            throw new IllegalStateException("Global.getSettings() null at all-skills resolve —"
                    + " the knob is ON, so a game-free context cannot honor it (fail-closed)");
        }
        // Resolve off the PUBLIC interfaces, NEVER settings.getClass() (the probe lesson:
        // the impl is the inaccessible anonymous StarfarerSettings$1).
        Class<?> settingsApi = Class.forName(SETTINGS_API, false, loader);
        Class<?> personApi = Class.forName(PERSON_API, false, loader);
        Class<?> memberApi = Class.forName(FLEET_MEMBER_API, false, loader);
        Class<?> skillSpecApi = Class.forName(SKILL_SPEC_API, false, loader);
        Class<?> effectSpecApi = Class.forName(EFFECT_SPEC_API, false, loader);
        Class<?> fleetDataApi = Class.forName(FLEET_DATA_API, false, loader);
        h.personApiClass = personApi;
        h.getSortedSkillIds = settingsApi.getMethod("getSortedSkillIds");
        h.getSkillSpec = settingsApi.getMethod("getSkillSpec", String.class);
        h.isCombatOfficerSkill = skillSpecApi.getMethod("isCombatOfficerSkill");
        h.getEffectsAPI = skillSpecApi.getMethod("getEffectsAPI");
        h.effectGetType = effectSpecApi.getMethod("getType");
        h.specHasTag = skillSpecApi.getMethod("hasTag", String.class);
        h.specIsElite = skillSpecApi.getMethod("isElite");
        h.setCaptain = memberApi.getMethod("setCaptain", personApi);
        h.setFleetCommanderForStats =
                memberApi.getMethod("setFleetCommanderForStats", personApi, fleetDataApi);
        handles = h;
        return handles;
    }

    private static final class Handles {
        Object settings;
        Class<?> personApiClass;
        Method getSortedSkillIds;
        Method getSkillSpec;
        Method isCombatOfficerSkill;
        Method getEffectsAPI;
        Method effectGetType;
        Method specHasTag;
        Method specIsElite;
        Method setCaptain;
        Method setFleetCommanderForStats;
    }
}
