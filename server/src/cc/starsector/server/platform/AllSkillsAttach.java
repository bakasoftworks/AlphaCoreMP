package cc.starsector.server.platform;

import cc.starsector.server.match.AllSkillsEnumerator;
import cc.starsector.server.match.AllSkillsFleetData;
import cc.starsector.server.match.AllSkillsFleetLaw;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.OfficerSkillCheck;
import cc.starsector.server.names.EngineSym;
import cc.starsector.server.names.EngineSymBridgeRoster;
import cc.starsector.server.runtime.ServerConfig;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
 *     all-skills SEAT member gets the commander PAIR ({@code setFleetCommanderForStats}) with
 *     the union person of the side it is FIELDED on, plus that side's fleet data as the member's
 *     own {@code fleetData} (the carrier, {@link #installPairAndCarrier}; a side-1 seat pairs
 *     with side 1's own union person, minted on demand, while side 1's manager default stays
 *     bare): {@code CyberneticAugmentation$Level3} (and family) counts the commander's elite skills
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
    /** Side 0's union person: the side-0 manager default AND side 0's fleet-context commander. */
    private static Object unionCommander;
    /** One fleet context a FIELDED side, keyed by that side's live fleet key (class lock). */
    private static final FleetContexts CONTEXTS = new FleetContexts(new EngineContextOps());
    private static int captainsAttached;
    /** Per-ship echoes printed since the round's document entry; the first {@link #SHIP_ECHO_CAP}. */
    private static int shipEchoes;
    private static final int SHIP_ECHO_CAP = 64;
    /** Logged once per process: a per-ship echo read failed (the echo never throws into a deploy). */
    private static boolean shipEchoFailLogged;
    private static ShipEchoHandles shipEchoHandles;
    /** Has a fab=1 per-ship row whose fab no held context minted (no key tail) been logged? */
    private static boolean shipEchoKeyUnknownLogged;

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
     * need a second law to stay in step with the mint. Every side's union person carries the marker too, so each is excluded by
     * identity against the cached instances (none cached = none exists in this process). Read by method NAME and
     * ARITY ({@code getPortraitSprite}/0) on the object's own runtime class, so a contract row
     * drives it with a plain fake. PURE apart from one failure line: null, a missing getter or any
     * throw reads false (the caller then announces the bare row).
     */
    public static boolean isMintedPerson(Object person) {
        return isMintedPerson(person, unionToExclude(person));
    }

    /**
     * The union person to exclude by identity for {@code person}: {@code person} itself when it is
     * ANY side's union person (side 1's carries the marker too), else side 0's (null = none minted).
     *
     */
    private static Object unionToExclude(Object person) {
        synchronized (AllSkillsAttach.class) {
            return CONTEXTS.isUnion(person) ? person : unionCommander;
        }
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
        return isMintedPersonOrThrow(person, unionToExclude(person));
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
        Object person = freshAllSkillsCaptain();
        captainsAttached++;
        return person;
    }

    /**
     * The one recipe of the all-skills seat captain: the ship sheet on a steady person with the
     * non-default marker portrait. No counter moves here; each caller counts its own attach.
     *
     */
    private static Object freshAllSkillsCaptain() throws Exception {
        AllSkillsEnumerator.Sets s = setsOrEnumerate();
        return PersonMint.mint("steady", s.ship, OFFICER_FIRST_NAME, "AllSkills",
                s.ship.size(), NON_DEFAULT_MARKER_PORTRAIT);
    }

    /**
     * Attach the all-skills captain to a member that is NEVER fielded through a deploy lane —
     * the registrar's throwaway refit member and the probe mains: the commander PAIR with side
     * 0's union person and NO fleet data, NEVER the carrier (a throwaway member must not hold a
     * live fab). The pair setter drops its fab argument in the engine, so this is the same
     * member state the earlier pair-with-fab call left. Deploy lanes call
     * {@link #attachAllSkillsCaptain(Object, String, int)} with the FIELDED side. Only when
     * {@link #enabled()}; fail-closed THROWS on any failure.
     */
    public static synchronized void attachAllSkillsCaptain(Object memberApi, String shipLabel) {
        requireAttachable(memberApi, shipLabel);
        try {
            attachCaptain(memberApi, shipLabel, unionCommanderOrMint(), null);
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
     * Attach the all-skills captain to a SEAT member a deploy lane fields on absolute side
     * {@code fieldedSide} (0 or 1) — call BEFORE {@code spawnFleetMember}, only when
     * {@link #enabled()} (callers route: seat+knob ⇒ here, else the curated
     * {@code OfficerAttach} lane). The member takes the pair AND the carrier of the side it is
     * FIELDED on (a seat switched onto side 1 reads side 1's fab, never side 0's); a side whose
     * fleet key is empty at this call gives a pair with no fleet data (unthrottled, one row per
     * change). Fail-closed
     * THROWS on any failure.
     */
    public static synchronized void attachAllSkillsCaptain(Object memberApi, String shipLabel,
            int fieldedSide) {
        requireAttachable(memberApi, shipLabel);
        if (fieldedSide < 0) {
            throw new IllegalStateException("attachAllSkillsCaptain('" + shipLabel
                    + "'): fielded side " + fieldedSide + " — a deploy lane fields side 0 or 1");
        }
        try {
            FleetContexts.Context ctx = CONTEXTS.contextFor(fieldedSide);
            attachCaptain(memberApi, shipLabel, ctx.union(), ctx.fabOrNull());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = (e instanceof InvocationTargetException && e.getCause() != null)
                    ? e.getCause() : e;
            throw new IllegalStateException("all-skills captain attach failed for '" + shipLabel
                    + "': " + cause, cause);
        }
    }

    private static void requireAttachable(Object memberApi, String shipLabel) {
        if (!ALL_SKILLS) {
            throw new IllegalStateException("attachAllSkillsCaptain('" + shipLabel
                    + "') with the knob OFF — the deploy lanes must route on enabled()");
        }
        if (memberApi == null) {
            throw new IllegalStateException("attachAllSkillsCaptain('" + shipLabel
                    + "'): null member");
        }
    }

    /**
     * The shared captain body: pair (+ carrier when {@code fabOrNull} is non-null) FIRST, then
     * the captain. CA visibility BEFORE any captain-derived stats exist: Level3-family
     * elite counts read the MEMBER fields, so the union commander goes on the member itself.
     */
    private static void attachCaptain(Object memberApi, String shipLabel, Object commander,
            Object fabOrNull) throws Exception {
        Handles h = handlesOrResolve();
        AllSkillsEnumerator.Sets s = setsOrEnumerate();
        PairOutcome outcome = installPairAndCarrier(memberApi, commander, fabOrNull, shipLabel);
        Object person = freshAllSkillsCaptain();
        h.setCaptain.invoke(memberApi, person);
        captainsAttached++;
        System.out.println("cc-allskills captain ship='" + shipLabel + "' skills="
                + s.ship.size() + " fc_for_stats=union carrier=" + outcome.token()
                + " total_attached=" + captainsAttached);
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
     * The round's document entry, descriptor lane: called by the round deploy
     * BEFORE any member mints. It names the present battle's document ids and the round's fleet
     * RECORD, resets the per-ship echo budget and brings side 0's context up to date at once.
     * Every later lookup reads each side's fleet key AS IT STANDS then (the side's non-seat
     * document rows, stations counted, then the record's entries of that side), so
     * {@code record} is held by REFERENCE, never copied: its owner writes it before each attach,
     * and the next lookup re-mints only when the key moved (an equal key keeps the same context;
     * a moved one mints a new fab and re-tokens the side's SAME union person). Side 1's context
     * is minted on demand at its first lookup. No-op when the knob is OFF; a null argument
     * THROWS.
     */
    public static synchronized void maybeMintSideZeroFleetContext(
            AllSkillsFleetLaw.DocumentIds ids, AllSkillsFleetLaw.FleetRecord record) {
        if (!ALL_SKILLS) {
            return;
        }
        try {
            beginRoundDocument(new FleetContexts.KeyDocument(ids, record));
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
     * The roster-only form of the descriptor lane: {@code roster}'s document ids
     * (its SEAT rows name the seat ids) with one FRESH empty record nobody writes
     * ({@link FleetContexts.KeyDocument#ofRoster}), so each side's key is its non-seat document
     * rows alone. Kept for a caller that holds no round record; no-op when the knob is OFF.
     * per-round document.
     */
    public static synchronized void maybeMintSideZeroFleetContext(
            List<MatchDescriptor.RosterRow> roster) {
        if (!ALL_SKILLS) {
            return;
        }
        FleetContexts.KeyDocument document = FleetContexts.KeyDocument.ofRoster(roster);
        maybeMintSideZeroFleetContext(document.ids(), document.record());
    }

    /**
     * Fallback-lane twin for the descriptor-less certified 1v1: side 0's key is
     * the literal variant list (one demo hull), each entry {@code A:<id>} in list order; every
     * other side's key is empty. Both keys are fixed for the round (no record). A null id
     * THROWS.
     */
    public static synchronized void maybeMintSideZeroFleetContextFromVariantIds(
            List<String> variantIds) {
        if (!ALL_SKILLS) {
            return;
        }
        try {
            List<String> entries = new ArrayList<>();
            if (variantIds != null) {
                for (String id : variantIds) {
                    if (id == null) {
                        throw new IllegalArgumentException("all-skills fleet-context mint"
                                + " (variant-id lane): variant id " + entries.size() + " is null");
                    }
                    entries.add(AllSkillsFleetLaw.ENTRY_AI + id);
                }
            }
            AllSkillsFleetLaw.FleetKey sideZero = AllSkillsFleetLaw.FleetKey.of(entries);
            beginRoundDocument(side -> side == 0 ? sideZero : FleetContexts.EMPTY_KEY);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = (e instanceof InvocationTargetException && e.getCause() != null)
                    ? e.getCause() : e;
            throw new IllegalStateException(
                    "all-skills fleet-context mint (variant-id lane) failed: " + cause, cause);
        }
    }

    /**
     * Name the round's document, reset the per-ship echo budget, and bring side 0's context up
     * to date now (fail-closed at round start, before any member mints).
     *
     */
    private static void beginRoundDocument(FleetContexts.Document document) throws Exception {
        shipEchoes = 0;
        CONTEXTS.beginDocument(document);
        CONTEXTS.contextFor(0);
    }

    /**
     * The member install for a deploy lane's side-0 AI member (a grunt): side 0's union
     * person as the commander pair plus side 0's fleet data as the member's own fleet data (the
     * carrier, {@link #installPairAndCarrier}), so the engine's threshold throttle resolves BOTH
     * gates at every stats derive. Seats never come here (their captain attach carries the
     * fielded side's context); side-1 AI members take nothing (side 1's manager default stays
     * bare). Appended hulls ({@code midDeployCore}: waves, /spawnfoe, /spawnfriend, pods) take no
     * pair and no fab; a station takes neither. No-op when the knob is OFF; an install before the
     * round's document entry with the knob ON is a deploy ORDERING bug and THROWS — fail-closed,
     * never a silent maxBonus.
     */
    public static synchronized void installFleetContext(Object memberApi) {
        if (!ALL_SKILLS) {
            return;
        }
        if (memberApi == null) {
            throw new IllegalStateException("all-skills fleet-context install: null member");
        }
        if (!CONTEXTS.hasDocument()) {
            throw new IllegalStateException("all-skills fleet-context install before mint —"
                    + " the deploy lane must maybeMintSideZeroFleetContext* before minting"
                    + " members (ordering bug)");
        }
        try {
            FleetContexts.Context ctx = CONTEXTS.contextFor(0);
            installPairAndCarrier(memberApi, ctx.union(), ctx.fabOrNull(), "side0_ai");
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
     * SIDE 0's union-sheet commander person, one per boot: the same instance serves the
     * side-0 manager default AND every side-0 member's {@code fleetCommanderForStats} — vanilla
     * fields one commander for the whole fleet, and one shared instance keeps every engine
     * read (elite counts, fleetwide apply) trivially identical across hulls. Its identity is kept
     * across a fleet-context re-mint (only its token changes): the manager default must never be
     * orphaned. Side 1's union person is a SEPARATE instance ({@link FleetContexts}).
     */
    private static Object unionCommanderOrMint() throws Exception {
        if (unionCommander != null) {
            return unionCommander;
        }
        unionCommander = mintUnionPerson(0);
        return unionCommander;
    }

    /**
     * Mint one union-sheet person for absolute side {@code side}. W15 editor D1:
     * the mint leaves the CHARACTER stats bare (skipRefresh — the engine's refresh
     * NPEs headless), but the refit rule the server enforces on custom variants (max vents/caps,
     * RealEngineVariantRegistrar #4) reads side 0's person's character stats, exactly as the
     * vanilla refit reads the player's REFRESHED ones. Apply the sheet's CHARACTER_STATS effects
     * headless, fail-closed: a throw hands out no half-refreshed person. Every later token install
     * re-applies them ({@link #reapplyCharacterStats}).
     */
    private static Object mintUnionPerson(int side) throws Exception {
        AllSkillsEnumerator.Sets s = setsOrEnumerate();
        Object person = PersonMint.mint("steady", s.union, OFFICER_FIRST_NAME, "Commander",
                s.union.size(), NON_DEFAULT_MARKER_PORTRAIT);
        PersonStatsRefresh.Result refreshed =
                PersonStatsRefresh.applyCharacterStatsEffects(person, s.union);
        System.out.println("cc-allskills union commander character-stats refresh (headless, D1) "
                + (side == 0 ? "" : "side=" + side + " ") + refreshed);
        return person;
    }

    /**
     * Gate 2: re-apply the union sheet's CHARACTER_STATS effects to
     * {@code union} after a token install (or clear). The engine's own re-derive is a no-op for a
     * skipRefresh person, so without this every character-level threshold effect (FieldRepairs)
     * keeps the value it computed with NO fleet = its maximum. The same modifier ids as the mint's
     * apply ({@code PersonStatsRefresh}'s id law), so it overwrites and never stacks.
     *
     */
    private static void reapplyCharacterStats(Object union, int side) throws Exception {
        PersonStatsRefresh.Result refreshed =
                PersonStatsRefresh.applyCharacterStatsEffects(union, setsOrEnumerate().union);
        System.out.println("cc-allskills union commander character-stats re-apply side=" + side
                + " after token " + refreshed);
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
        // The pair-and-carrier law's handles — the ONLY reflective lookups of the two member
        // setters in the server tree (a source-scan row counts them). The carrier is the engine
        // class's public setter: the member API does not expose it.
        h.pairSetter = memberApi.getMethod("setFleetCommanderForStats", personApi, fleetDataApi);
        h.carrierSetter = EngineSym.FLEET_MEMBER_CLASS.load(loader).getMethod("setFleetData",
                EngineSymBridgeRoster.BRIDGE_ROSTER_FLEET_DATA_CLASS.load(loader));
        h.memberUpdateStats = memberApi.getMethod("updateStats");
        h.memberIsStation = memberApi.getMethod("isStation");
        h.memberIsFlagship = memberApi.getMethod("isFlagship");
        h.memberGetFleetData = memberApi.getMethod("getFleetData");
        Class<?> shipApi = Class.forName("com.fs.starfarer.api.combat.ShipAPI", false, loader);
        h.shipGetMutableStats = shipApi.getMethod("getMutableStats");
        h.shipIsAlive = shipApi.getMethod("isAlive");
        h.statsGetFleetMember = Class.forName("com.fs.starfarer.api.combat.MutableShipStatsAPI", false,
                loader).getMethod("getFleetMember");
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
        Method pairSetter;
        Method carrierSetter;
        Method memberUpdateStats;
        Method memberIsStation;
        Method memberIsFlagship;
        Method memberGetFleetData;
        Method shipGetMutableStats;
        Method shipIsAlive;
        Method statsGetFleetMember;
    }

    // ------------------------------------------------------------------ the pair and the carrier

    /** What {@link #installPairAndCarrier} did — a bounded token for the attach row. */
    public enum PairOutcome {
        /** No commander: nothing was written. */
        NONE_NO_COMMANDER("none"),
        /** A station: nothing was written (a station is never a campaign fleet member). */
        NONE_STATION("station"),
        /** The pair with no fleet data: the side has none, or the caller never carries. */
        PAIR_ONLY_NO_FAB("pair_only"),
        /** The pair only: the member is flagged a flagship, the carrier is refused (one row). */
        PAIR_ONLY_FLAGSHIP("skipped_flagship"),
        /** The pair, the carrier, one stats update. */
        PAIR_AND_CARRIER("set");

        private final String token;

        PairOutcome(String token) {
            this.token = token;
        }

        /** The bounded log token. */
        public String token() {
            return token;
        }
    }

    /**
     * The member writes the pair-and-carrier law drives: reflective on the server
     * ({@link #installPairAndCarrier(Object, Object, Object, String)}), a recording stand-in in a
     * contract row.
     */
    public interface MemberOps {
        boolean isStation(Object member) throws Exception;

        boolean isFlagship(Object member) throws Exception;

        /** {@code member.setFleetCommanderForStats(commander, fabOrNull)}. */
        void pair(Object member, Object commander, Object fabOrNull) throws Exception;

        /** {@code member.setFleetData(fab)} — the engine class's public setter. */
        void carrier(Object member, Object fab) throws Exception;

        void updateStats(Object member) throws Exception;

        void log(String line);
    }

    /**
     * THE one server site that writes a member's commander pair and its fleet data (the
     * carrier) — reflective, through the once-resolved handles. Call BEFORE the ship is created.
     * The law is {@link #installPairAndCarrier(Object, Object, Object, String, MemberOps)}. The
     * carrier's one release, {@link #dropRemovedHullFleetData}, writes null through the same
     * {@link MemberOps#carrier} handle once the hull is gone.
     *
     */
    public static synchronized PairOutcome installPairAndCarrier(Object member, Object commander,
            Object fabOrNull, String site) throws Exception {
        return installPairAndCarrier(member, commander, fabOrNull, site,
                new ReflectiveMemberOps(handlesOrResolve()));
    }

    /**
     * The pair-and-carrier law. A null commander writes nothing; a station writes nothing (as
     * the deploy lanes always skipped it). Otherwise the member's flagship flag is read FIRST —
     * with no fleet data yet the engine answers the plain field, while a flagged member holding
     * fleet data resolves its captain through the campaign engine, whose lazy creation would
     * replace the live combat engine — then the PAIR is written ({@code
     * setFleetCommanderForStats(commander, fabOrNull)}; the engine stores the commander only).
     * The CARRIER ({@code setFleetData(fab)}) follows only when the fab is non-null and the
     * member is not flagged; a flagged member gets one loud row and the pair only (the server
     * never flags one). Pair FIRST: a member holding fleet data without the pair would resolve
     * its commander through the fab, a bare stand-in person. ONE {@code updateStats()} closes a
     * carrier write ({@code setFleetData} raises no stat update). NEVER {@code fab.setCommander}:
     * it clears the commander's stats fleet and erases the gate-2 token. No {@code
     * setFlagship(true)} ever runs after this (it would swap the captain for the fab's commander).
     *
     */
    public static PairOutcome installPairAndCarrier(Object member, Object commander,
            Object fabOrNull, String site, MemberOps ops) throws Exception {
        if (member == null) {
            throw new IllegalStateException("pair-and-carrier: null member at " + site);
        }
        if (commander == null) {
            return PairOutcome.NONE_NO_COMMANDER;
        }
        if (ops.isStation(member)) {
            return PairOutcome.NONE_STATION;
        }
        boolean flagship = ops.isFlagship(member);
        ops.pair(member, commander, fabOrNull);
        if (fabOrNull == null) {
            return PairOutcome.PAIR_ONLY_NO_FAB;
        }
        if (flagship) {
            ops.log("fleet_ctx carrier=skipped reason=flagship_flagged id=" + site);
            return PairOutcome.PAIR_ONLY_FLAGSHIP;
        }
        ops.carrier(member, fabOrNull);
        ops.updateStats(member);
        return PairOutcome.PAIR_AND_CARRIER;
    }

    /** Removed hulls whose member's fleet data was released (process lifetime). */
    private static long removedHullFabDrops;
    /** Removed hulls whose release was skipped (no member, still alive) or failed (process lifetime). */
    private static long removedHullFabDropFailures;
    /** The first this-many skips / failures each print one line; the rest only count. */
    private static final int FAB_DROP_LOUD_CAP = 16;

    /**
     * Release a REMOVED hull's fleet data: {@code setFleetData(null)} on the member of a seat hull
     * the engine has already dropped (destroyed or departed), through the same carrier handle
     * {@link #installPairAndCarrier} writes with. Knob OFF = a no-op (no fab was ever carried).
     *
     * <p>WHY: the engine books a destroyed, disabled or retreated hull's member for the battle's
     * life (the combat fleet manager's destroyed / disabled / retreated sets; only a capture
     * removes one), and the member keeps the fab it was fielded under. Every fleet-key change
     * mints a fresh fab, so on a standing match that never wraps each superseded fab — its |K|
     * shadow members and their captains — stayed reachable through the dead members: measured on
     * the real engine, headless, about 18.9 KB + 24.4 KB x |K| kept per key change (995 KB at
     * |K| = 40), the fleet-data instance count growing one per change; with this release it
     * stayed at two over 1,000 changes. The dead hull itself (about 0.4 MB a death, the engine's
     * own battle state) is NOT released here.
     *
     *
     * <p>The member is read from the hull's STATS link ({@code getMutableStats().getFleetMember()}),
     * a plain field the engine sets once at hull construction — never {@code ShipAPI.getFleetMember()},
     * which on its first call resolves through the fleet manager's DEPLOYED set: a dead hull whose
     * member was never asked for while it lived answers null there. A hull the engine still holds
     * alive ({@code ShipAPI.isAlive()}: in the engine, not a hulk, hull above zero) is never
     * stripped: a live member's next stats derive would read its fleet-scaled skills with no
     * fleet. A member with no fleet data (a pair-only fielding) is left untouched. Nothing the
     * server reads afterwards needs the dead member's fab: its captain and commander reads answer
     * the same without it (the fab's commander is never set, the server never flags a member),
     * and a dead member is never re-instantiated (a re-fielding mints a fresh member).
     *
     * <p>Sim thread (the serve's despawn hook); NEVER throws: a hull with no member (a bare
     * escape pod), a hull still alive, or a reflective failure counts and prints one capped line.
     *
     *
     * @return true when a held fab was released
     */
    public static synchronized boolean dropRemovedHullFleetData(Object ship, int seatId) {
        if (!ALL_SKILLS) {
            return false;
        }
        try {
            Handles h = handlesOrResolve();
            Object member = ship == null ? null
                    : h.statsGetFleetMember.invoke(h.shipGetMutableStats.invoke(ship));
            if (member == null) {
                fabDropLoud("no_member", seatId);
                return false;
            }
            if (Boolean.TRUE.equals(h.shipIsAlive.invoke(ship))) {
                fabDropLoud("hull_alive", seatId);
                return false;
            }
            if (h.memberGetFleetData.invoke(member) == null) {
                return false;
            }
            new ReflectiveMemberOps(h).carrier(member, null);
            removedHullFabDrops++;
            return true;
        } catch (Exception | LinkageError e) {
            Throwable cause = (e instanceof InvocationTargetException && e.getCause() != null)
                    ? e.getCause() : e;
            fabDropLoud(cause.getClass().getSimpleName(), seatId);
            return false;
        }
    }

    /** {released, skipped-or-failed} removed-hull fab drops this process. */
    public static synchronized long[] removedHullFabDropMeter() {
        return new long[] {removedHullFabDrops, removedHullFabDropFailures};
    }

    private static void fabDropLoud(String reason, int seatId) {
        removedHullFabDropFailures++;
        if (removedHullFabDropFailures <= FAB_DROP_LOUD_CAP) {
            System.out.println("cc-allskills fab_drop=SKIPPED reason=" + reason + " seat=" + seatId
                    + " count=" + removedHullFabDropFailures
                    + (removedHullFabDropFailures == FAB_DROP_LOUD_CAP ? " (cap: later ones count only)"
                            : "")
                    + " (the removed hull's fab, if any, stays reachable)");
        }
    }

    /** The server's reflective {@link MemberOps}: the handles resolved once. */
    private static final class ReflectiveMemberOps implements MemberOps {
        private final Handles h;

        ReflectiveMemberOps(Handles h) {
            this.h = h;
        }

        @Override
        public boolean isStation(Object member) throws Exception {
            return Boolean.TRUE.equals(h.memberIsStation.invoke(member));
        }

        @Override
        public boolean isFlagship(Object member) throws Exception {
            return Boolean.TRUE.equals(h.memberIsFlagship.invoke(member));
        }

        @Override
        public void pair(Object member, Object commander, Object fabOrNull) throws Exception {
            h.pairSetter.invoke(member, commander, fabOrNull);
        }

        @Override
        public void carrier(Object member, Object fab) throws Exception {
            h.carrierSetter.invoke(member, fab);
        }

        @Override
        public void updateStats(Object member) throws Exception {
            h.memberUpdateStats.invoke(member);
        }

        @Override
        public void log(String line) {
            System.out.println("cc-allskills " + line);
        }
    }

    // ------------------------------------------------------------------ per-side contexts

    /**
     * One fleet context a FIELDED absolute side = {the side's fleet key as read at the lookup,
     * one fab, one union person, one token}. The key is read from the round's {@link Document}
     * at EVERY {@link #contextFor}, never cached: it moves inside a round with each record
     * write. A key equal to the held context's ({@link AllSkillsFleetLaw.FleetKey#equals}: the
     * canonical text, never the digest alone, which can collide) answers the SAME context; a
     * differing key RE-MINTS the fab over the key's entries and re-tokens the side's SAME union
     * person (identity kept: side 0's is the manager default), then re-applies its character
     * stats (gate 2). A side whose key is EMPTY (no non-seat document row and no record entry:
     * a wave-fed side, or no document at all) gets NO fab and one row per change; a previously
     * tokened union person is un-tokened then. Two sides never share a fab or a union person.
     * Every method runs under the instance lock; the production instance is reached only under
     * the {@link AllSkillsAttach} class lock (lock order: class, then instance).
     *
     */
    public static final class FleetContexts {

        /** The empty key: no entries, so no fab. */
        public static final AllSkillsFleetLaw.FleetKey EMPTY_KEY =
                AllSkillsFleetLaw.FleetKey.of(Collections.<String>emptyList());

        /**
         * A round's document: the PRESENT fleet key of one side, read at every lookup (null
         * reads as {@link #EMPTY_KEY}). May throw on a side the key law refuses (not 0 or 1).
         */
        public interface Document {
            AllSkillsFleetLaw.FleetKey key(int side) throws Exception;
        }

        /**
         * The descriptor lane's document: {@code side -> ids.key(side, record)}, the record
         * read LIVE by reference (its owner writes it before each attach, so the key a lookup
         * reads holds every entry written so far). Holds no other state.
         *
         */
        public static final class KeyDocument implements Document {
            private final AllSkillsFleetLaw.DocumentIds ids;
            private final AllSkillsFleetLaw.FleetRecord record;

            /** THROWS on a null argument: a document with no ids or no record is a wiring bug. */
            public KeyDocument(AllSkillsFleetLaw.DocumentIds ids,
                    AllSkillsFleetLaw.FleetRecord record) {
                if (ids == null || record == null) {
                    throw new IllegalArgumentException("fleet context document: null "
                            + (ids == null ? "document ids" : "fleet record"));
                }
                this.ids = ids;
                this.record = record;
            }

            /**
             * The roster-only document: {@code roster}'s ids (a null roster = no rows; its
             * SEAT rows name the seat ids) and a FRESH empty record per call that nobody
             * writes, so a side's key is its non-seat document rows alone and no round's
             * entries leak into another's.
             */
            public static KeyDocument ofRoster(List<MatchDescriptor.RosterRow> roster) {
                return new KeyDocument(AllSkillsFleetLaw.DocumentIds.ofRoster(roster),
                        new AllSkillsFleetLaw.FleetRecord());
            }

            public AllSkillsFleetLaw.DocumentIds ids() {
                return ids;
            }

            /** The record this document reads: the caller's own instance, never a copy. */
            public AllSkillsFleetLaw.FleetRecord record() {
                return record;
            }

            @Override
            public AllSkillsFleetLaw.FleetKey key(int side) {
                return ids.key(side, record);
            }
        }

        /** The engine-side work a context needs: reflective on the server, stand-ins in a row. */
        public interface Ops {
            /** A fresh union person for {@code side}. */
            Object mintUnion(int side) throws Exception;

            /**
             * Mint {@code side}'s fab over the fleet key's {@code entries} (each {@code A:<id>}
             * or {@code S:<id>}, in key order, never empty) and install its token on
             * {@code union}.
             */
            AllSkillsFleetLaw.Mint mintFab(int side, List<String> entries, Object union)
                    throws Exception;

            /** Take the token off {@code union}'s stats. */
            void clearToken(Object union) throws Exception;

            /** Gate 2: re-apply {@code union}'s character-stats effects after a token change. */
            void reapply(Object union, int side) throws Exception;

            void log(String line);
        }

        /** One side's context. */
        public static final class Context {
            private final int side;
            private final AllSkillsFleetLaw.FleetKey key;
            private final Object union;
            private final AllSkillsFleetLaw.Mint mintOrNull;

            Context(int side, AllSkillsFleetLaw.FleetKey key, Object union,
                    AllSkillsFleetLaw.Mint mintOrNull) {
                this.side = side;
                this.key = key;
                this.union = union;
                this.mintOrNull = mintOrNull;
            }

            public int side() {
                return side;
            }

            /** The fleet key this context was minted under (never null; empty = no fab). */
            public AllSkillsFleetLaw.FleetKey key() {
                return key;
            }

            /**
             * {@link #key()}'s digest (signed int, any value; 0 is lawful): the mint row's
             * {@code key=<hex8>} tail.
             */
            public int keyDigest() {
                return key.digest();
            }

            public Object union() {
                return union;
            }

            /** The fab, or null when the key is empty. */
            public Object fabOrNull() {
                return mintOrNull == null ? null : mintOrNull.fleetData;
            }

            /** The gate-2 token installed on {@link #union()}, or null with no fab. */
            public Object tokenOrNull() {
                return mintOrNull == null ? null : mintOrNull.fleetToken;
            }
        }

        private final Ops ops;
        private Document document;
        private final Map<Integer, Context> contextBySide = new HashMap<>();
        private final Map<Integer, Object> unionBySide = new HashMap<>();
        private final Map<Object, Integer> sideByUnion = new IdentityHashMap<>();

        public FleetContexts(Ops ops) {
            this.ops = ops;
        }

        /**
         * Name the round's document (the round deploy, before any member mints). The held
         * contexts stay: the next lookup of each side compares them with the new document's key.
         */
        public synchronized void beginDocument(Document doc) {
            document = doc;
        }

        /** Has any round named a document yet? */
        public synchronized boolean hasDocument() {
            return document != null;
        }

        /** Is {@code person} any side's union person? */
        public synchronized boolean isUnion(Object person) {
            return person != null && sideByUnion.containsKey(person);
        }

        /**
         * The context of FIELDED absolute side {@code side} under the side's key as it stands
         * NOW (read from the document on every call).
         */
        public synchronized Context contextFor(int side) throws Exception {
            if (side < 0) {
                throw new IllegalStateException("fleet context for side " + side
                        + " — a fielded side is 0 or 1");
            }
            AllSkillsFleetLaw.FleetKey key = keyFor(side);
            Context held = contextBySide.get(side);
            if (held != null && held.key.equals(key)) {
                return held;
            }
            Object union = unionFor(side);
            Context fresh;
            if (key.entries().isEmpty()) {
                if (held != null && held.mintOrNull != null) {
                    ops.clearToken(union);
                    ops.reapply(union, side);
                }
                ops.log(AllSkillsFleetLaw.noDocumentRosterLine(side));
                fresh = new Context(side, key, union, null);
            } else {
                AllSkillsFleetLaw.Mint mint = ops.mintFab(side, key.entries(), union);
                ops.reapply(union, side);
                // The both-ends parity/grading lane: byte-identical echo body on the client
                // sidelog; the key tail lets the grader join both ends' mint rows on (side, key).
                ops.log(keyedEcho(mint.echo, key));
                fresh = new Context(side, key, union, mint);
            }
            contextBySide.put(side, fresh);
            return fresh;
        }

        /**
         * The mint row: the mint's echo plus the key tail, the bytes of the law's keyed
         * {@code echoLine} overload for the same fields and {@code key.digest()}.
         */
        static String keyedEcho(String echo, AllSkillsFleetLaw.FleetKey key) {
            return echo + " key=" + AllSkillsFleetLaw.hex8(key.digest());
        }

        /**
         * The digest (signed int, any value) of the key whose mint made {@code fab}, matched by
         * IDENTITY among the sides' held contexts; null for a null fab or one no held context
         * minted. A per-ship echo asks right after the hull spawns, inside the deploy call that
         * minted the hull's member, so the minting context is still the one held: a hull's row
         * names the key its fleet data was minted for, never the side's key at some later read.
         *
         */
        public synchronized Integer keyDigestOfFabOrNull(Object fab) {
            if (fab == null) {
                return null;
            }
            for (Context held : contextBySide.values()) {
                if (held.mintOrNull != null && held.mintOrNull.fleetData == fab) {
                    return held.keyDigest();
                }
            }
            return null;
        }

        private AllSkillsFleetLaw.FleetKey keyFor(int side) throws Exception {
            AllSkillsFleetLaw.FleetKey key = document == null ? null : document.key(side);
            return key == null ? EMPTY_KEY : key;
        }

        private Object unionFor(int side) throws Exception {
            Object union = unionBySide.get(side);
            if (union == null) {
                union = ops.mintUnion(side);
                if (union == null || sideByUnion.containsKey(union)) {
                    throw new IllegalStateException("fleet context: side " + side + " union "
                            + (union == null ? "minted null" : "already serves another side")
                            + " — two sides never share a union person");
                }
                unionBySide.put(side, union);
                sideByUnion.put(union, side);
            }
            return union;
        }
    }

    /**
     * The entries mint at the {@code mintFab} level: one side's fab over the fleet key's ENTRY
     * list, each shadow built as its live twin ({@link AllSkillsFleetData#mintEntries} with the
     * server's own captain kinds and pair handle). {@link EngineContextOps#mintFab} calls it
     * with the context's key entries. The caller re-applies {@code union}'s character-stats
     * effects after it returns, as after every token install.
     *
     */
    public static synchronized AllSkillsFleetLaw.Mint mintFabFromEntries(int side,
            List<String> entries, Object union) {
        return AllSkillsFleetData.mintEntries(AllSkillsAttach.class.getClassLoader(), side,
                entries, union, SHADOW_TWIN);
    }

    /**
     * The server's shadow twin: a seat shadow's captain is the live seat recipe, an AI shadow's
     * is the officer attach's detached person, and the pair rides the one pair handle.
     *
     */
    private static final AllSkillsFleetData.ShadowTwin SHADOW_TWIN =
            new AllSkillsFleetData.ShadowTwin() {
                @Override
                public Object seatCaptain() throws Exception {
                    return freshAllSkillsCaptain();
                }

                @Override
                public Object aiCaptainOrNull(String variantId) {
                    return OfficerAttach.captainPersonOrNull(variantId);
                }

                @Override
                public void pair(Object shadow, Object commander, Object fab) throws Exception {
                    handlesOrResolve().pairSetter.invoke(shadow, commander, fab);
                }
            };

    /** The production {@link FleetContexts.Ops}: the entries mint, side 0's union kept. */
    private static final class EngineContextOps implements FleetContexts.Ops {
        @Override
        public Object mintUnion(int side) throws Exception {
            return side == 0 ? unionCommanderOrMint() : mintUnionPerson(side);
        }

        @Override
        public AllSkillsFleetLaw.Mint mintFab(int side, List<String> entries, Object union) {
            return mintFabFromEntries(side, entries, union);
        }

        @Override
        public void clearToken(Object union) {
            AllSkillsFleetData.clearCommanderFleetToken(AllSkillsAttach.class.getClassLoader(),
                    union);
        }

        @Override
        public void reapply(Object union, int side) throws Exception {
            reapplyCharacterStats(union, side);
        }

        @Override
        public void log(String line) {
            System.out.println("cc-allskills " + line);
        }
    }

    // ------------------------------------------------------------------ the per-ship echo

    /**
     * The per-ship proof row ({@link AllSkillsFleetLaw#formatShipEcho}): right after a deploy lane
     * created {@code ship}, read FROM THE SHIP whether its member carries fleet data, whether
     * the captain is that fleet data's commander, the phase-cloak speed mod and the percent sum
     * on flux dissipation; a fab=1 row also carries the key tail of the context that minted the
     * fab ({@link #shipEchoText}). {@code idOrNull} = the wire entity id when the caller holds one, else
     * the ship's own engine id ({@code ShipAPI.getId()}, the stable key the deploy tables join
     * on); {@code side} = the absolute side. The first {@link #SHIP_ECHO_CAP} ships a round (the
     * budget resets at the round's document entry); knob OFF = silent. Spawn-time only, never on
     * the frame loop. NEVER throws into the deploy: a failed read logs once per process.
     *
     */
    public static synchronized void echoShip(Object ship, String idOrNull, int side) {
        if (!ALL_SKILLS || ship == null || shipEchoes >= SHIP_ECHO_CAP) {
            return;
        }
        try {
            ShipEchoHandles e = shipEchoHandlesOrResolve();
            String id = idOrNull;
            if (id == null) {
                Object engineId = e.shipGetId.invoke(ship);
                id = engineId instanceof String ? ((String) engineId).trim() : "unknown";
            }
            Object member = e.shipGetFleetMember.invoke(ship);
            Object fleetData = member == null ? null : e.memberGetFleetData.invoke(member);
            boolean fab = fleetData != null;
            boolean captDefault = fab
                    && e.shipGetCaptain.invoke(ship) == e.fleetDataGetCommander.invoke(fleetData);
            Object stats = e.shipGetMutableStats.invoke(ship);
            Object dynamic = e.statsGetDynamic.invoke(stats);
            Object pcMod = e.dynamicGetMod.invoke(dynamic, "phase_cloak_speed");
            float pc = pcMod == null ? 0f
                    : ((Number) e.bonusComputeEffective.invoke(pcMod, 0f)).floatValue();
            Object dissipation = e.statsGetFluxDissipation.invoke(stats);
            Map<?, ?> percent = (Map<?, ?>) e.statGetPercentMods.invoke(dissipation);
            float fdp = 0f;
            if (percent != null) {
                for (Object mod : percent.values()) {
                    fdp += e.statModValue.getFloat(mod);
                }
            }
            Integer keyDigest = fab ? CONTEXTS.keyDigestOfFabOrNull(fleetData) : null;
            if (fab && keyDigest == null && !shipEchoKeyUnknownLogged) {
                shipEchoKeyUnknownLogged = true;
                System.out.println("cc-allskills fleet_ctx_ship key_unknown side=" + side
                        + " (a fab no held context minted; its row prints untailed; logged once)");
            }
            shipEchoes++;
            System.out.println("cc-allskills "
                    + shipEchoText(id, side, fab, captDefault, pc, fdp, keyDigest));
        } catch (Exception | LinkageError err) {
            if (!shipEchoFailLogged) {
                shipEchoFailLogged = true;
                Throwable cause = (err instanceof InvocationTargetException
                        && err.getCause() != null) ? err.getCause() : err;
                System.out.println("cc-allskills fleet_ctx_ship read_failed err="
                        + cause.getClass().getSimpleName() + " (logged once; the deploy goes on)");
            }
        }
    }

    /**
     * The per-ship row's text, pure: the shared base row, plus {@code " key=<hex8>"} exactly when
     * {@code fab} is set and {@code keyDigestOrNull} names the minting context's key digest (signed
     * int, any value; 0 prints eight zero digits, a real digest). A fab=0 row never carries a tail,
     * whatever key is passed; a fab=1 row with no known key prints untailed, which the grader joins
     * on (wire id, occurrence) alone.
     *
     */
    public static String shipEchoText(String id, int side, boolean fab, boolean captDefault, float pc,
            float fdp, Integer keyDigestOrNull) {
        return fab && keyDigestOrNull != null
                ? AllSkillsFleetLaw.formatShipEcho(id, side, fab, captDefault, pc, fdp, keyDigestOrNull)
                : AllSkillsFleetLaw.formatShipEcho(id, side, fab, captDefault, pc, fdp);
    }

    private static ShipEchoHandles shipEchoHandlesOrResolve() throws Exception {
        if (shipEchoHandles != null) {
            return shipEchoHandles;
        }
        ClassLoader loader = AllSkillsAttach.class.getClassLoader();
        Class<?> shipApi = Class.forName("com.fs.starfarer.api.combat.ShipAPI", false, loader);
        Class<?> memberApi = Class.forName(FLEET_MEMBER_API, false, loader);
        Class<?> fleetDataApi = Class.forName(FLEET_DATA_API, false, loader);
        Class<?> statsApi =
                Class.forName("com.fs.starfarer.api.combat.MutableShipStatsAPI", false, loader);
        Class<?> dynamicApi = Class.forName("com.fs.starfarer.api.util.DynamicStatsAPI", false, loader);
        Class<?> statBonus = Class.forName("com.fs.starfarer.api.combat.StatBonus", false, loader);
        Class<?> mutableStat = Class.forName("com.fs.starfarer.api.combat.MutableStat", false, loader);
        Class<?> statMod =
                Class.forName("com.fs.starfarer.api.combat.MutableStat$StatMod", false, loader);
        ShipEchoHandles e = new ShipEchoHandles();
        e.shipGetId = shipApi.getMethod("getId");
        e.shipGetFleetMember = shipApi.getMethod("getFleetMember");
        e.shipGetCaptain = shipApi.getMethod("getCaptain");
        e.shipGetMutableStats = shipApi.getMethod("getMutableStats");
        e.memberGetFleetData = memberApi.getMethod("getFleetData");
        e.fleetDataGetCommander = fleetDataApi.getMethod("getCommander");
        e.statsGetDynamic = statsApi.getMethod("getDynamic");
        e.statsGetFluxDissipation = statsApi.getMethod("getFluxDissipation");
        e.dynamicGetMod = dynamicApi.getMethod("getMod", String.class);
        e.bonusComputeEffective = statBonus.getMethod("computeEffective", float.class);
        e.statGetPercentMods = mutableStat.getMethod("getPercentMods");
        e.statModValue = statMod.getField("value");
        shipEchoHandles = e;
        return shipEchoHandles;
    }

    private static final class ShipEchoHandles {
        Method shipGetId;
        Method shipGetFleetMember;
        Method shipGetCaptain;
        Method shipGetMutableStats;
        Method memberGetFleetData;
        Method fleetDataGetCommander;
        Method statsGetDynamic;
        Method statsGetFluxDissipation;
        Method dynamicGetMod;
        Method bonusComputeEffective;
        Method statGetPercentMods;
        java.lang.reflect.Field statModValue;
    }
}
