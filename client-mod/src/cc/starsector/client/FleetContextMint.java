package cc.starsector.client;

import cc.starsector.server.match.AllSkillsFleetLaw;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.CharacterStatsSkillEffect;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.characters.SkillEffectType;
import com.fs.starfarer.api.characters.SkillSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.skills.BaseSkillEffectDescription;
import com.fs.starfarer.api.impl.campaign.skills.PhaseCorps;

import com.fs.starfarer.campaign.fleet.FleetData;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * W4 all-skills side-0 fleet-context MINTER, CLIENT end: the TYPED
 * twin of the server's reflective {@code AllSkillsFleetData.mint} — java.lang.reflect is
 * classloader-B-DENIED in the mod jar, so this end mints with typed calls only.
 * The engine-typed {@link FleetData} reference is B-LEGAL (the adapter-class law,
 * as for {@code MissileSplitLane} et al): an UNOBFUSCATED campaign class name in
 * 0.98a-RC8 whose {@code (String, String)} ctor is campaign-free with both args null. Gate 2
 * rides the generated typed {@link FleetContextToken} instead of the server's Proxy.
 *
 * <p>The LAW (walk/echo/Mint shape) lives in the shared {@link AllSkillsFleetLaw}; the mint
 * DISCIPLINE here mirrors the server class step-for-step (pre-name, repair-tracker +
 * mothball guards, ONE lists-only sync + count assert, {@code setForceNoSync} freeze, token
 * install + {@code refreshCharacterStatsEffects(false)} re-derive, engine-statics echo).
 * The {@code fleet_ctx} echo EQUALITY assert at watch grading is the anti-drift guard across
 * the two mint vehicles — byte-identical echo bodies or the watch fails. FAIL-CLOSED
 * throughout: a half-built context is the defect re-minted (silent maxBonus).
 */
public final class FleetContextMint {

    private FleetContextMint() {
    }

    /** The engine-backed station read for the shared walk law ({@code getVariant(id).isStation()}). */
    public static AllSkillsFleetLaw.StationCheck stationCheck() {
        SettingsAPI settings = settingsOrThrow();
        return variantId -> {
            ShipVariantAPI variant = settings.getVariant(variantId);
            if (variant == null) {
                throw new IllegalStateException("getVariant('" + variantId + "') resolved null");
            }
            return variant.isStation();
        };
    }

    /**
     * Build the side-0 fleet context: shadow-mint the roster, fab the engine {@link FleetData},
     * freeze its sync, install the gate-2 token on {@code union}'s stats and re-derive its
     * character-stats effects — the typed mirror of {@code AllSkillsFleetData.mint} (that
     * class's javadoc carries the full design law; keep the two in step).
     */
    public static AllSkillsFleetLaw.Mint mint(int side, List<String> variantIds, PersonAPI union,
            Map<String, Float> sheet) {
        if (variantIds == null || variantIds.isEmpty()) {
            throw new IllegalStateException("all-skills fleet-context mint: empty variant list");
        }
        if (union == null) {
            throw new IllegalStateException("all-skills fleet-context mint: null union person");
        }
        SettingsAPI settings = settingsOrThrow();

        // The fab: the engine's OWN FleetData, campaign-free with null name sources.
        FleetDataAPI fab = new FleetData((String) null, (String) null);
        for (int i = 0; i < variantIds.size(); i++) {
            FleetMemberAPI shadow =
                    settings.createFleetMember(FleetMemberType.SHIP, variantIds.get(i));
            if (shadow == null) {
                throw new IllegalStateException("createFleetMember(SHIP, '"
                        + variantIds.get(i) + "') returned null");
            }
            // Pre-name: FleetData.addFleetMember mints a name for a null-named member via
            // ShipNameStore/faction sources — a campaign seam we keep unexercised headless.
            shadow.setShipName("CC-SHADOW-" + (i + 1));
            // Fail-closed twin of FleetData.isInInvalidStateDueToGameLoadOrder:
            // a tracker-less member makes the lists-sync EMPTY the roster silently.
            if (shadow.getRepairTracker() == null) {
                throw new IllegalStateException("shadow member '" + variantIds.get(i)
                        + "' has a null RepairTracker — the fab lists-sync would silently"
                        + " empty the roster (invalid-load-order guard)");
            }
            if (shadow.isMothballed()) {
                throw new IllegalStateException("shadow member '" + variantIds.get(i)
                        + "' minted MOTHBALLED — it would silently drop from every sum");
            }
            fab.addFleetMember(shadow);
        }

        // ONE members-lists-only sync (the getSortedMembers idiom), then freeze forever:
        // the full sync path re-crews/re-derives — campaign machinery the fab must not run.
        fab.setOnlySyncMemberLists(true);
        int synced = fab.getNumMembers();
        fab.setOnlySyncMemberLists(false);
        fab.setForceNoSync(true);
        if (synced != variantIds.size()) {
            throw new IllegalStateException("fab FleetData synced " + synced
                    + " members for " + variantIds.size() + " shadow mints — a short roster"
                    + " inflates every threshold bonus (fail-closed)");
        }

        // Gate 2: the fail-closed CampaignFleetAPI token on the union commander's stats.
        FleetContextToken token = new FleetContextToken(fab);
        installMatchFleetToken(union, token, sheet);

        return new AllSkillsFleetLaw.Mint(fab, token, sideEcho(side, engineEcho(fab, union, variantIds.size())));
    }

    /** The entry prefix of a seat hull's shadow (the all-skills captain kind). */
    public static final String SEAT_ENTRY = "S:";
    /** The entry prefix of an AI hull's shadow (its own officer, else the
     *  engine's default captain); station rows ride this prefix too. */
    public static final String AI_ENTRY = "A:";

    /**
     * The side's fleet context minted from the key's ENTRY LIST, each shadow
     * built like its LIVE twin. WHY: the variant-id {@link #mint} builds every shadow alike (no captain, no
     * commander, no stats derive), so a shadow's deployment cost is its bare hull's while the live hull it stands for
     * is discounted by the engine (Support Doctrine on an officerless hull, a hullmod's DP mod); the summed points
     * the threshold skills read then sit above the base game's. Here each shadow carries its live twin's captain
     * kind and commander and runs the engine's OWN stats derive, so the engine applies every per-member effect
     * and the sum comes out as the base game computes it, with no wire field.
     *
     * <p>{@code entries} = the canonical entry form, in key order: {@code S:<variantId>} a seat hull (the captain
     * the client's own officer attach gives a seat: the all-skills captain), {@code A:<variantId>} an AI hull or
     * a station (that variant's curated officer, else none and the engine's default captain); any other prefix
     * throws naming it, before any shadow is built. Commander: {@code union} on every shadow of side 0 and on
     * every seat shadow; a side-1 AI or station shadow stays bare, as its live twin does. {@code side} = the
     * absolute side (0 or 1) the echo names. The token, the character-stats pass and the echo are
     * {@link #mint}'s. Its server twin is {@code AllSkillsFleetData}'s entries mint; the two must stay
     * sequence-identical. Not wired yet: {@link #mint} stays the one in use.
     */
    public static AllSkillsFleetLaw.Mint mintEntries(int side, List<String> entries, PersonAPI union,
            Map<String, Float> sheet) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalStateException("all-skills fleet-context entries mint: empty entry list");
        }
        if (union == null) {
            throw new IllegalStateException("all-skills fleet-context entries mint: null union person");
        }
        SettingsAPI settings = settingsOrThrow();
        FleetDataAPI fab = new FleetData((String) null, (String) null);
        int members = shadowEntries(fab, side, entries, union, new EngineShadowTouches(settings));
        FleetContextToken token = new FleetContextToken(fab);
        installMatchFleetToken(union, token, sheet);
        return new AllSkillsFleetLaw.Mint(fab, token, sideEcho(side, engineEcho(fab, union, members)));
    }

    /**
     * The member calls the shadow pass makes (and its two asks), passed in so a
     * game-free row drives the whole order on recording stand-ins: the api's member type names an lwjgl type, so a
     * JVM without the lwjgl jars cannot stand one in. {@code M} = {@code FleetMemberAPI} on the engine. The fab's
     * own calls (the hold, the list sync, the count, the freeze) stay direct on {@link FleetDataAPI}.
     */
    interface ShadowTouches<M> {
        /** The engine's member for {@code variantId}; null = the mint throws. */
        M createShadow(String variantId);

        /** The captain a live hull of {@code variantId} gets; null = none, the engine's default goes on. */
        PersonAPI captainOrNull(String variantId, boolean seat);

        void setShipName(M shadow, String name);

        boolean hasRepairTracker(M shadow);

        boolean isMothballed(M shadow);

        void setCaptain(M shadow, PersonAPI captain);

        /** The commander pair, through the one place this end pairs a member (no fab: the fab rides the add). */
        void pair(M shadow, PersonAPI commander);

        void addFleetMember(FleetDataAPI fab, M shadow);

        void updateStats(M shadow);
    }

    /** The engine's touches, each one typed call. */
    private static final class EngineShadowTouches implements ShadowTouches<FleetMemberAPI> {
        private final SettingsAPI settings;

        EngineShadowTouches(SettingsAPI settings) {
            this.settings = settings;
        }

        public FleetMemberAPI createShadow(String variantId) {
            return settings.createFleetMember(FleetMemberType.SHIP, variantId);
        }

        public PersonAPI captainOrNull(String variantId, boolean seat) {
            return OfficerLoadout.shadowCaptainOrNull(variantId, seat);
        }

        public void setShipName(FleetMemberAPI shadow, String name) {
            shadow.setShipName(name);
        }

        public boolean hasRepairTracker(FleetMemberAPI shadow) {
            return shadow.getRepairTracker() != null;
        }

        public boolean isMothballed(FleetMemberAPI shadow) {
            return shadow.isMothballed();
        }

        public void setCaptain(FleetMemberAPI shadow, PersonAPI captain) {
            shadow.setCaptain(captain);
        }

        public void pair(FleetMemberAPI shadow, PersonAPI commander) {
            OfficerLoadout.installPairAndCarrier(shadow, commander, null, "fleet-ctx-shadow");
        }

        public void addFleetMember(FleetDataAPI fab, FleetMemberAPI shadow) {
            fab.addFleetMember(shadow);
        }

        public void updateStats(FleetMemberAPI shadow) {
            shadow.updateStats();
        }
    }

    /** One parsed entry (seat flag + variant id). */
    static final class Entry {
        final boolean seat;
        final String variantId;

        Entry(boolean seat, String variantId) {
            this.seat = seat;
            this.variantId = variantId;
        }
    }

    /** An entry's prefix law; anything but {@code S:} / {@code A:} followed by
     *  a variant id throws, naming the prefix as a bounded token (never the whole entry). Pure. */
    static Entry parseEntry(String entry) {
        if (entry != null && entry.length() > SEAT_ENTRY.length()) {
            if (entry.startsWith(SEAT_ENTRY)) {
                return new Entry(true, entry.substring(SEAT_ENTRY.length()));
            }
            if (entry.startsWith(AI_ENTRY)) {
                return new Entry(false, entry.substring(AI_ENTRY.length()));
            }
        }
        throw new IllegalStateException("fleet-context entry prefix " + prefixToken(entry)
                + " is not S: or A: followed by a variant id (fail-closed)");
    }

    /** The entry's text up to and including its first ':', at most 4 chars, quoted; every char outside
     *  [A-Za-z0-9:_] reads '?'. */
    static String prefixToken(String entry) {
        if (entry == null) {
            return "null";
        }
        int colon = entry.indexOf(':');
        int end = Math.min(colon < 0 ? entry.length() : colon + 1, 4);
        StringBuilder out = new StringBuilder("'");
        for (int i = 0; i < end; i++) {
            char c = entry.charAt(i);
            out.append(c < 128 && (Character.isLetterOrDigit(c) || c == ':' || c == '_') ? c : '?');
        }
        return out.append('\'').toString();
    }

    /**
     * The entries mint's shadow pass over {@code fab}: per entry, in list
     * order, create, pre-name, the tracker and mothball guards, the captain, the commander pair,
     * {@code addFleetMember}, the engine's own {@code updateStats}; then {@code syncMemberLists}, the member-count
     * assert, the freeze. Returns the member count.
     *
     * <p>THE ORDER: {@code setOnlySyncMemberLists(true)} is held from before the first create until after the
     * count. A RE-mint's union person still carries the previous fleet token, so a threshold skill inside a
     * shadow's derive reads the fab's members, and a members read on a fab that needs a sync takes FleetData's FULL
     * sync (re-crew, hullmod campaign advance, repair rates: campaign machinery a fab must never run) unless the
     * lists-only flag is up. {@code syncMemberLists} then clears what the
     * half-built pass cached; the count reads under the same flag; the freeze follows.
     */
    static <M> int shadowEntries(FleetDataAPI fab, int side, List<String> entries, PersonAPI union,
            ShadowTouches<M> touches) {
        if (side != 0 && side != 1) {
            throw new IllegalStateException("fleet-context entries mint: side " + side + " is not 0 or 1");
        }
        Entry[] parsed = new Entry[entries.size()];
        for (int i = 0; i < parsed.length; i++) {
            parsed[i] = parseEntry(entries.get(i));
        }
        fab.setOnlySyncMemberLists(true);
        for (int i = 0; i < parsed.length; i++) {
            Entry entry = parsed[i];
            M shadow = touches.createShadow(entry.variantId);
            if (shadow == null) {
                throw new IllegalStateException("createFleetMember(SHIP, '" + entry.variantId + "') returned null");
            }
            // Pre-name: addFleetMember mints a name for a null-named member via ShipNameStore/faction sources.
            touches.setShipName(shadow, "CC-SHADOW-" + (i + 1));
            if (!touches.hasRepairTracker(shadow)) {
                throw new IllegalStateException("shadow member '" + entry.variantId + "' has a null RepairTracker"
                        + " - the fab lists-sync would silently empty the roster (invalid-load-order guard)");
            }
            if (touches.isMothballed(shadow)) {
                throw new IllegalStateException("shadow member '" + entry.variantId
                        + "' minted MOTHBALLED - it would silently drop from every sum");
            }
            PersonAPI captain = touches.captainOrNull(entry.variantId, entry.seat);
            if (captain != null) {
                touches.setCaptain(shadow, captain);
            }
            if (entry.seat || side == 0) {
                touches.pair(shadow, union);
            }
            touches.addFleetMember(fab, shadow);
            touches.updateStats(shadow);
        }
        fab.syncMemberLists();
        int synced = fab.getNumMembers();
        fab.setOnlySyncMemberLists(false);
        fab.setForceNoSync(true);
        if (synced != parsed.length) {
            throw new IllegalStateException("fab FleetData synced " + synced + " members for " + parsed.length
                    + " entries - a short roster inflates every threshold bonus (fail-closed)");
        }
        return synced;
    }

    /**
     * The shared echo with its side field set to {@code side} (the shared
     * law prints side 0); every other field byte for byte the law's, so the server twin's line for the same
     * side matches. Pure.
     */
    static String sideEcho(int side, String lawEcho) {
        String prefix = "fleet_ctx side=0 ";
        return lawEcho.startsWith(prefix) ? "fleet_ctx side=" + side + " " + lawEcho.substring(prefix.length())
                : lawEcho;
    }

    /** A match person's token install: the token, the engine's own refresh
     *  (a no-op under the mint's skipRefresh), then the typed character-stats pass that re-applies the sheet's
     *  effects now that the token answers the fleet. */
    private static void installMatchFleetToken(PersonAPI person, CampaignFleetAPI token, Map<String, Float> sheet) {
        installFleetToken(person, token);
        applyCharacterStatsEffects(person, sheet, settingsOrThrow()::getSkillSpec);
    }

    /**
     * The typed twin of the server's headless character-stats pass
     * ({@code PersonStatsRefresh}): for every skill on {@code sheet}, each effect of type CHARACTER_STATS whose
     * governing skill meets its required level is applied with the id {@code <skill>_stats_<i>}, {@code i}
     * counting EVERY effect of the skill (the engine's id law), so a second pass OVERWRITES the first, never
     * stacks. WHY: every match person is minted under skipRefresh, which turns the engine's own refresh into a
     * no-op, so without this the character-level threshold effects never see the token. Fail-closed like the
     * server: a missing spec, a CHARACTER_STATS effect with no stats effect, or a pass that applied nothing
     * throws. Returns the applied count.
     */
    static int applyCharacterStatsEffects(PersonAPI person, Map<String, Float> sheet,
            Function<String, SkillSpecAPI> specs) {
        if (person == null || sheet == null || sheet.isEmpty()) {
            throw new IllegalStateException("character-stats pass: " + (person == null ? "null person" : "empty sheet"));
        }
        MutableCharacterStatsAPI stats = person.getStats();
        if (stats == null) {
            throw new IllegalStateException("character-stats pass: getStats() returned null");
        }
        int applied = 0;
        for (Map.Entry<String, Float> skill : sheet.entrySet()) {
            String skillId = skill.getKey();
            SkillSpecAPI spec = specs.apply(skillId);
            if (spec == null) {
                throw new IllegalStateException("character-stats pass: no skill spec for a sheet skill");
            }
            List<SkillSpecAPI.SkillEffectSpecAPI> effects = spec.getEffectsAPI();
            if (effects == null) {
                continue;
            }
            int index = 0;
            for (SkillSpecAPI.SkillEffectSpecAPI effect : effects) {
                String id = skillId + "_stats_" + index;
                index++;
                if (effect.getType() != SkillEffectType.CHARACTER_STATS) {
                    continue;
                }
                String governing = effect.getGoverningSkill() == null ? skillId : effect.getGoverningSkill();
                float level = stats.getSkillLevel(governing);
                if (level < effect.getRequiredSkillLevel()) {
                    continue;
                }
                CharacterStatsSkillEffect statsEffect = effect.getAsStatsEffect();
                if (statsEffect == null) {
                    throw new IllegalStateException("character-stats pass: a CHARACTER_STATS effect has no stats effect");
                }
                statsEffect.apply(stats, id, level);
                applied++;
            }
        }
        if (applied == 0) {
            throw new IllegalStateException("character-stats pass applied NOTHING over " + sheet.size() + " skills");
        }
        return applied;
    }

    /**
     * For the editor's skills toggle ({@code EditorStats}): a campaign-free ONE-member fab around a DETACHED
     * member the editor built from an in-memory variant — {@link #mint}'s steps on the member itself (pre-name, the
     * tracker + mothball guards, ONE lists-only sync + count assert, the {@code setForceNoSync} freeze, the gate-2
     * token on {@code commander}) so {@code FleetMember.updateStats} applies the captain's personal set (its gate is
     * a member with a fleet-data member list) and the commander's fleet-wide set resolves its thresholds over this
     * one ship — the bastion seat shape (a one-ship side). Never a match fleet: the member and the commander are the
     * editor's own detached objects; {@code addFleetMember} puts a default steady captain on a captain-less member
     * (the caller's {@code setCaptain} replaces it).
     */
    public static FleetDataAPI fabForMember(FleetMemberAPI member, PersonAPI commander) {
        if (member == null) {
            throw new IllegalStateException("editor fleet-context fab: null member");
        }
        if (commander == null) {
            throw new IllegalStateException("editor fleet-context fab: null commander person");
        }
        settingsOrThrow();
        FleetDataAPI fab = new FleetData((String) null, (String) null);
        if (member.getShipName() == null) {
            member.setShipName("CC-EDITOR");   // pre-name: a null name walks ShipNameStore (a campaign seam)
        }
        if (member.getRepairTracker() == null) {
            throw new IllegalStateException("editor member has a null RepairTracker — the fab lists-sync would"
                    + " silently empty the roster (invalid-load-order guard)");
        }
        if (member.isMothballed()) {
            throw new IllegalStateException("editor member minted MOTHBALLED — it would silently drop from every sum");
        }
        fab.addFleetMember(member);
        fab.setOnlySyncMemberLists(true);
        int synced = fab.getNumMembers();
        fab.setOnlySyncMemberLists(false);
        fab.setForceNoSync(true);
        if (synced != 1) {
            throw new IllegalStateException("editor fab FleetData synced " + synced
                    + " members for one editor member (fail-closed)");
        }
        installFleetToken(commander, new FleetContextToken(fab));
        return fab;
    }

    /**
     * Install {@code mint}'s gate-2 token on another commander person's stats (the mission
     * build's engine-default commander is a SECOND person carrying the union sheet) and
     * re-derive its character-stats effects.
     */
    public static void installCommanderFleetToken(AllSkillsFleetLaw.Mint mint, PersonAPI person,
            Map<String, Float> sheet) {
        if (mint == null || person == null) {
            throw new IllegalStateException("all-skills fleet-context: token install with "
                    + (mint == null ? "no mint" : "no person") + " — fail-closed");
        }
        installMatchFleetToken(person, (CampaignFleetAPI) mint.fleetToken, sheet);
    }

    private static void installFleetToken(PersonAPI person, CampaignFleetAPI token) {
        MutableCharacterStatsAPI stats = person.getStats();
        if (stats == null) {
            throw new IllegalStateException("union person getStats() returned null");
        }
        stats.setFleet(token);
        // Character-level threshold effects (AuxiliarySupport family) first applied at skill
        // set with a null fleet — re-derive them now that both gates resolve. Engine idiom:
        // FleetData.setCommander does exactly this re-derive on commander change.
        stats.refreshCharacterStatsEffects(false);
    }

    /**
     * The canonical echo, computed by the ENGINE's own statics over the fab (never re-derived
     * project-side) and folded through the shared {@link AllSkillsFleetLaw#echoLine} — the
     * server twin computes the identical line reflectively, and grading asserts equality.
     */
    private static String engineEcho(FleetDataAPI fab, PersonAPI union, int members) {
        MutableCharacterStatsAPI unionStats = union.getStats();
        float phaseDp = BaseSkillEffectDescription.getPhaseOP(fab, unionStats);
        float combatDp = BaseSkillEffectDescription.getTotalCombatOP(fab, unionStats);
        float bays = BaseSkillEffectDescription.getNumFighterBays(fab);
        float phaseThr = BaseSkillEffectDescription.PHASE_OP_THRESHOLD;
        BaseSkillEffectDescription calc = new BaseSkillEffectDescription();
        float pcSpeed = calc.getThresholdBasedRoundedBonus(
                PhaseCorps.PHASE_SPEED_BONUS, phaseDp, phaseThr);
        float pcPeak = calc.getThresholdBasedRoundedBonus(
                PhaseCorps.PEAK_TIME_BONUS, phaseDp, phaseThr);
        return AllSkillsFleetLaw.echoLine(members, phaseDp, phaseThr, pcSpeed, pcPeak,
                combatDp, bays);
    }

    private static SettingsAPI settingsOrThrow() {
        SettingsAPI settings = Global.getSettings();
        if (settings == null) {
            throw new IllegalStateException("Global.getSettings() null at all-skills"
                    + " fleet-context mint — a game-free context cannot honor the knob");
        }
        return settings;
    }
}
