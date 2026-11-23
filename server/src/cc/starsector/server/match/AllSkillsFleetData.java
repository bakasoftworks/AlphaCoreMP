package cc.starsector.server.match;

import cc.starsector.server.names.EngineSymBridgeRoster;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * All-skills PER-SIDE FLEET CONTEXT: the engine's
 * commander-skill throttle
 * short-circuits to FULL effect when a ship's member resolves null {@code FleetDataAPI} or a
 * commander whose stats carry no fleet — exactly our fabricated contexts, so every
 * threshold-scaled commander skill (phase_corps & family) granted at maximum regardless of the
 * deployed fleet. The chosen shape is the ENGINE's own adapts-to-the-fleet scaling,
 * so this class builds a REAL {@code com.fs.starfarer.campaign.fleet.FleetData} carrying ONE
 * side's document roster (one fab a fielded side) and satisfies both gates:
 * <ol>
 * <li><b>Gate 1 (member fleet data)</b>: shadow {@code FleetMember}s are minted from the
 *     DESCRIPTOR's SHIP rows of that side via the engine's own context-free seam
 *     ({@code SettingsAPI.createFleetMember(SHIP, variantId)}, which is
 *     {@code new FleetMember(0, variantId, type)}) and added to
 *     the fab FleetData. Descriptor-derived, NOT live-fielded members: the client registry shape
 *     fields only the own seat locally (rosters spawn at wire sight) and the server's t=0 specs
 *     are occupancy-gated, so live members would give the two ends DIFFERENT sums — the
 *     descriptor is the one roster law both ends already share (stats are off-wire; identical
 *     throttle input by construction). Shadow members never enter combat, so the fab's
 *     member-side effects (fleetData rebind at lists-sync) touch nothing live. The fab reaches a
 *     REAL member through the engine's own public {@code FleetMember.setFleetData(fab)} (the
 *     carrier; the server's one call site is {@code AllSkillsAttach.installPairAndCarrier}): the
 *     pair setter {@code setFleetCommanderForStats(union, fab)} stores the commander ONLY and
 *     drops its fab argument, so a pair alone leaves the
 *     member fleet-data-less and the throttle at maximum. The real member never enters the
 *     fab's member list.</li>
 * <li><b>Gate 2 (commander stats fleet)</b>: the throttle only null-checks
 *     {@code cStats.getFleet()}; the real reads go through {@code getFleet().getFleetData()}
 *     (FieldRepairs is the live consumer in the granted sheet;
 *     AuxiliarySupport reads the same way but is npc_only, never granted).
 *     A full {@code CampaignFleet} is campaign-context-bound headless (ctor: sprite + fleet
 *     view + {@code CampaignEngine} name sources), so the
 *     union commander's stats get a fail-closed {@link Proxy} token that answers
 *     {@code getFleetData()} with the REAL fab and THROWS loudly on any other engine read — no
 *     faked values, and an unexpected read surfaces as a crash naming the method instead of a
 *     silent wrong stat. The engine re-derive this class runs after the token install is a
 *     NO-OP for our persons (every match person is minted with skipRefresh),
 * so the caller re-applies the character-stats effects headless
 *     after EVERY token install ({@code AllSkillsAttach} through {@code PersonStatsRefresh}: the
 *     same modifier ids, so the re-apply overwrites and never stacks).
 *     </li>
 * </ol>
 *
 * <p><b>Sync discipline</b> (why the fab never full-syncs): {@code FleetData.syncIfNeeded}'s
 * full path re-crews members, re-applies fleetwide to MEMBER stats and re-derives logistics —
 * campaign machinery with live side effects. The fab does ONE members-lists-only sync
 * ({@code setOnlySyncMemberLists}, the engine's own {@code getSortedMembers} idiom)
 * and is then frozen with {@code setForceNoSync(true)} — the roster
 * is static per battle (vanilla neither live-decrements the sums on combat losses: the cache
 * clears on campaign sync only). Known residue: shadow-member DP costs
 * are BASE variant values (the campaign path where fleetwide skills can modify member DP never
 * runs headless) — symmetric both ends, second-order only.
 *
 * <p><b>The entries mint</b> ({@link #mintEntries}; no caller is wired to it yet, the variant-id
 * {@link #mint} above stays the one in use): its input is the fleet key's ENTRY list ({@code S:<variantId>} = a seat
 * hull, {@code A:<variantId>} = an AI hull, stations included), and each shadow is built as its LIVE twin: the
 * twin's captain kind (a seat's all-skills person; an AI hull's officers-table person, else the engine's default
 * person) and the twin's commander (the side's union person on side 0 and on every seat; none for a side-1 AI or
 * station hull, whose live twin stays bare), then the engine's OWN stats derive per shadow. So each shadow's cost is
 * the engine's stat-modified deployment cost: Support Doctrine's discount lands on a default-captained hull through
 * the union's fleetwide skills, as the base game computes it. ORDER: the members-lists-only hold is set before the
 * first member is added and held across every shadow's derive, then {@code syncMemberLists()} clears the threshold
 * cache the half-built derive filled, then the count and the freeze, and only then the hold is released. WHY the
 * hold: on a re-mint the union person still carries the previous fleet token, so a threshold skill inside a shadow's
 * derive reads this fab's members, and without the hold that read would run FleetData's FULL campaign sync headless.
 *
 *
 * <p>SERVER-ONLY: this class is java.lang.reflect throughout and
 * classloader B DENIES java.lang.reflect in the mod jar (the B-deny gate measured the
 * refs), so it must never ride the client allowlist. The shared pure law (walk/echo/Mint shape)
 * lives in {@link AllSkillsFleetLaw} (both jars); the client mints through its typed twin
 * (an engine-typed {@code new FleetData(null,null)} under the adapter-class law + a generated typed
 * fail-closed {@code CampaignFleetAPI} stub for gate 2 — the ONLY vehicle: the engine's sole
 * implementor {@code CampaignFleet} is campaign-bound AND its fleetData field is ctor-assigned
 * with no setter, so an engine fleet can never answer {@code getFleetData()} with our fab).
 * The {@code fleet_ctx} echo EQUALITY assert at watch grading is the anti-drift guard across the
 * two mint vehicles. FAIL-CLOSED throughout: a half-built fleet context is the defect
 * re-minted (silent maxBonus), so every miss THROWS.
 */
public final class AllSkillsFleetData {

    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    private static final String SETTINGS_API = "com.fs.starfarer.api.SettingsAPI";
    private static final String SHIP_VARIANT_API = "com.fs.starfarer.api.combat.ShipVariantAPI";
    private static final String FLEET_MEMBER_API = "com.fs.starfarer.api.fleet.FleetMemberAPI";
    private static final String FLEET_MEMBER_TYPE = "com.fs.starfarer.api.fleet.FleetMemberType";
    private static final String FLEET_DATA_API = "com.fs.starfarer.api.campaign.FleetDataAPI";
    private static final String CAMPAIGN_FLEET_API = "com.fs.starfarer.api.campaign.CampaignFleetAPI";
    private static final String PERSON_API = "com.fs.starfarer.api.characters.PersonAPI";
    private static final String MUTABLE_CHAR_STATS_API =
            "com.fs.starfarer.api.characters.MutableCharacterStatsAPI";
    private static final String BASE_SKILL_DESC =
            "com.fs.starfarer.api.impl.campaign.skills.BaseSkillEffectDescription";
    private static final String PHASE_CORPS =
            "com.fs.starfarer.api.impl.campaign.skills.PhaseCorps";

    /** The entry prefix of a seat hull in the fleet key's canonical entry form. */
    public static final String SEAT_ENTRY_PREFIX = "S:";
    /** The entry prefix of an AI hull (stations included) in the fleet key's canonical entry form. */
    public static final String AI_ENTRY_PREFIX = "A:";
    /** An unknown prefix is named in the refusal clipped to this many characters (a bounded token). */
    private static final int PREFIX_TOKEN_MAX = 16;

    private AllSkillsFleetData() {
    }

    /**
     * What only the server's attach code may do to a shadow member: mint the captain kinds its live twins carry, and
     * write the commander pair through the server's one pair handle (that setter is looked up by name in exactly one
     * place in the server tree, so this class never names it).
     *
     */
    public interface ShadowTwin {
        /** A fresh captain of the kind a seat hull's live attach gives: the all-skills sheet, a non-default person. */
        Object seatCaptain() throws Exception;

        /**
         * A fresh, detached captain the live officer attach would give an AI hull of {@code variantId}, or null when
         * it would give none (the shadow keeps the engine's own default person).
         */
        Object aiCaptainOrNull(String variantId) throws Exception;

        /** Pair {@code shadow} with {@code commander}; the engine keeps the commander and drops the fab argument. */
        void pair(Object shadow, Object commander, Object fab) throws Exception;
    }

    /**
     * Build one side's fleet context from the key's ENTRY list, each shadow its live twin (class doc, "The entries
     * mint"): {@code entries} = the canonical entries in key order, each {@code S:<variantId>} or
     * {@code A:<variantId>}; any other prefix THROWS naming it, before any engine work. {@code side} = the absolute
     * fielded side (0 or 1): it picks the commander rule and names the echo. Then exactly as {@link #mint}: the gate-2
     * token goes on {@code unionPersonApi}'s stats and the CALLER re-applies its character-stats effects. Fail-closed:
     * a seat captain that cannot be minted, a short roster or any engine miss THROWS.
     *
     */
    public static AllSkillsFleetLaw.Mint mintEntries(ClassLoader loader, int side, List<String> entries,
            Object unionPersonApi, ShadowTwin twin) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalStateException("all-skills fleet-context entries mint: empty entry list");
        }
        int n = entries.size();
        boolean[] seat = new boolean[n];
        String[] variantIds = new String[n];
        for (int i = 0; i < n; i++) {
            String entry = entries.get(i);
            if (entry != null && entry.startsWith(SEAT_ENTRY_PREFIX)) {
                seat[i] = true;
            } else if (entry == null || !entry.startsWith(AI_ENTRY_PREFIX)) {
                throw new IllegalArgumentException("all-skills fleet-context entries mint: entry " + i
                        + " has the unknown prefix '" + prefixToken(entry) + "' (" + SEAT_ENTRY_PREFIX
                        + " = a seat hull, " + AI_ENTRY_PREFIX + " = an AI hull)");
            }
            variantIds[i] = entry.substring((seat[i] ? SEAT_ENTRY_PREFIX : AI_ENTRY_PREFIX).length());
            if (variantIds[i].isEmpty()) {
                throw new IllegalArgumentException("all-skills fleet-context entries mint: entry " + i
                        + " names no variant id");
            }
        }
        if (side != 0 && side != 1) {
            throw new IllegalStateException("all-skills fleet-context entries mint: side " + side
                    + " - a fielded side is 0 or 1");
        }
        if (unionPersonApi == null || twin == null) {
            throw new IllegalStateException("all-skills fleet-context entries mint: null "
                    + (unionPersonApi == null ? "union person" : "shadow twin"));
        }
        try {
            Object settings = settingsOrThrow(loader);
            Class<?> settingsApi = Class.forName(SETTINGS_API, false, loader);
            Class<?> memberApi = Class.forName(FLEET_MEMBER_API, false, loader);
            Class<?> memberType = Class.forName(FLEET_MEMBER_TYPE, false, loader);
            Class<?> fleetDataApi = Class.forName(FLEET_DATA_API, false, loader);
            Class<?> campaignFleetApi = Class.forName(CAMPAIGN_FLEET_API, false, loader);
            Class<?> personApi = Class.forName(PERSON_API, false, loader);
            Class<?> charStatsApi = Class.forName(MUTABLE_CHAR_STATS_API, false, loader);

            Method createFleetMember =
                    settingsApi.getMethod("createFleetMember", memberType, String.class);
            Object shipType = memberType.getField("SHIP").get(null);
            Method setShipName = memberApi.getMethod("setShipName", String.class);
            Method getRepairTracker = memberApi.getMethod("getRepairTracker");
            Method isMothballed = memberApi.getMethod("isMothballed");
            Method setCaptain = memberApi.getMethod("setCaptain", personApi);
            Method updateStats = memberApi.getMethod("updateStats");

            Object fab = EngineSymBridgeRoster.BRIDGE_ROSTER_FLEET_DATA_CLASS.load(loader)
                    .getConstructor(String.class, String.class)
                    .newInstance((String) null, (String) null);
            Method addFleetMember = fleetDataApi.getMethod("addFleetMember", memberApi);
            Method setOnlySync = fleetDataApi.getMethod("setOnlySyncMemberLists", boolean.class);
            Method syncMemberLists = fleetDataApi.getMethod("syncMemberLists");
            Method setForceNoSync = fleetDataApi.getMethod("setForceNoSync", boolean.class);
            Method getNumMembers = fleetDataApi.getMethod("getNumMembers");

            // THE HOLD, before the first add: every sync this fab runs until the freeze is members-lists-only.
            setOnlySync.invoke(fab, Boolean.TRUE);
            for (int i = 0; i < n; i++) {
                Object shadow = createFleetMember.invoke(settings, shipType, variantIds[i]);
                if (shadow == null) {
                    throw new IllegalStateException("createFleetMember(SHIP, '" + variantIds[i]
                            + "') returned null");
                }
                setShipName.invoke(shadow, "CC-SHADOW-" + (i + 1));
                if (getRepairTracker.invoke(shadow) == null) {
                    throw new IllegalStateException("shadow member '" + variantIds[i]
                            + "' has a null RepairTracker - the fab lists-sync would silently"
                            + " empty the roster (invalid-load-order guard)");
                }
                if (Boolean.TRUE.equals(isMothballed.invoke(shadow))) {
                    throw new IllegalStateException("shadow member '" + variantIds[i]
                            + "' minted MOTHBALLED - it would silently drop from every sum");
                }
                Object captain = seat[i] ? twin.seatCaptain() : twin.aiCaptainOrNull(variantIds[i]);
                if (seat[i] && captain == null) {
                    throw new IllegalStateException("seat shadow '" + variantIds[i] + "' got no all-skills"
                            + " captain - a default person would take the officerless discounts (fail-closed)");
                }
                if (captain != null) {
                    setCaptain.invoke(shadow, captain);
                }
                if (seat[i] || side == 0) {
                    twin.pair(shadow, unionPersonApi, fab);
                }
                addFleetMember.invoke(fab, shadow);
                // The engine's own derive: captain personal skills, the union's fleetwide skills, hullmods.
                updateStats.invoke(shadow);
            }
            // Clear what the half-built derive cached, count under the hold, freeze, and only then release it.
            syncMemberLists.invoke(fab);
            int synced = ((Number) getNumMembers.invoke(fab)).intValue();
            setForceNoSync.invoke(fab, Boolean.TRUE);
            setOnlySync.invoke(fab, Boolean.FALSE);
            if (synced != n) {
                throw new IllegalStateException("fab FleetData synced " + synced + " members for " + n
                        + " entries - a short roster inflates every threshold bonus (fail-closed)");
            }

            Object token = Proxy.newProxyInstance(loader, new Class<?>[] {campaignFleetApi},
                    new FleetTokenHandler(fab));
            installFleetToken(loader, unionPersonApi, token);

            String echo = engineEcho(loader, side, fab, unionPersonApi, personApi, charStatsApi,
                    fleetDataApi, n);
            return new AllSkillsFleetLaw.Mint(fab, token, echo);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException
                    && e.getCause() != null) ? e.getCause() : e;
            throw new IllegalStateException(
                    "all-skills fleet-context entries mint failed: " + cause, cause);
        }
    }

    /** The text before an entry's first ':' (the whole entry when it has none), clipped: a bounded token. */
    private static String prefixToken(String entry) {
        if (entry == null) {
            return "null";
        }
        int colon = entry.indexOf(':');
        String head = colon < 0 ? entry : entry.substring(0, colon);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < head.length() && out.length() < PREFIX_TOKEN_MAX; i++) {
            char c = head.charAt(i);
            out.append((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ? c : '?');
        }
        return out.toString();
    }

    /**
     * The engine-backed {@link AllSkillsFleetLaw.StationCheck}
     * ({@code getVariant(id).isStation()}) — reflective, so server-side only.
     */
    public static AllSkillsFleetLaw.StationCheck engineStationCheck(ClassLoader loader)
            throws Exception {
        Object settings = settingsOrThrow(loader);
        Method getVariant = Class.forName(SETTINGS_API, false, loader)
                .getMethod("getVariant", String.class);
        Method isStation = Class.forName(SHIP_VARIANT_API, false, loader).getMethod("isStation");
        return variantId -> {
            Object variant = getVariant.invoke(settings, variantId);
            if (variant == null) {
                throw new IllegalStateException("getVariant('" + variantId + "') resolved null");
            }
            return Boolean.TRUE.equals(isStation.invoke(variant));
        };
    }

    /**
     * Build one side's fleet context: shadow-mint that side's roster, fab the engine FleetData,
     * freeze its sync, install the gate-2 token on {@code unionPersonApi}'s stats (replacing any
     * earlier token) and run the engine's own re-derive ({@code refreshCharacterStatsEffects(false)}
     * — a no-op for a skipRefresh person, so the CALLER
     * re-applies the character-stats effects after this returns). {@code side} = the absolute
     * side (0 or 1) the echo names. Side parameter.
     */
    public static AllSkillsFleetLaw.Mint mint(ClassLoader loader, int side,
            List<String> variantIds, Object unionPersonApi) {
        if (variantIds == null || variantIds.isEmpty()) {
            throw new IllegalStateException("all-skills fleet-context mint: empty variant list");
        }
        if (unionPersonApi == null) {
            throw new IllegalStateException("all-skills fleet-context mint: null union person");
        }
        try {
            Object settings = settingsOrThrow(loader);
            Class<?> settingsApi = Class.forName(SETTINGS_API, false, loader);
            Class<?> memberApi = Class.forName(FLEET_MEMBER_API, false, loader);
            Class<?> memberType = Class.forName(FLEET_MEMBER_TYPE, false, loader);
            Class<?> fleetDataApi = Class.forName(FLEET_DATA_API, false, loader);
            Class<?> campaignFleetApi = Class.forName(CAMPAIGN_FLEET_API, false, loader);
            Class<?> personApi = Class.forName(PERSON_API, false, loader);
            Class<?> charStatsApi = Class.forName(MUTABLE_CHAR_STATS_API, false, loader);

            Method createFleetMember =
                    settingsApi.getMethod("createFleetMember", memberType, String.class);
            Object shipType = memberType.getField("SHIP").get(null);
            Method setShipName = memberApi.getMethod("setShipName", String.class);
            Method getRepairTracker = memberApi.getMethod("getRepairTracker");
            Method isMothballed = memberApi.getMethod("isMothballed");

            // The fab: the engine's OWN FleetData, campaign-free with null name sources. Its
            // (String namePrefix, String nameSourceFactionId) ctor takes BOTH args null: the faction id
            // is its only CampaignEngine touch.
            Object fab = EngineSymBridgeRoster.BRIDGE_ROSTER_FLEET_DATA_CLASS.load(loader)
                    .getConstructor(String.class, String.class)
                    .newInstance((String) null, (String) null);
            Method addFleetMember = fleetDataApi.getMethod("addFleetMember", memberApi);
            Method setOnlySync = fleetDataApi.getMethod("setOnlySyncMemberLists", boolean.class);
            Method setForceNoSync = fleetDataApi.getMethod("setForceNoSync", boolean.class);
            Method getNumMembers = fleetDataApi.getMethod("getNumMembers");

            for (int i = 0; i < variantIds.size(); i++) {
                Object shadow = createFleetMember.invoke(settings, shipType, variantIds.get(i));
                if (shadow == null) {
                    throw new IllegalStateException("createFleetMember(SHIP, '"
                            + variantIds.get(i) + "') returned null");
                }
                // Pre-name: FleetData.addFleetMember mints a name for a null-named member via
                // ShipNameStore/faction sources — a campaign seam we keep unexercised headless.
                setShipName.invoke(shadow, "CC-SHADOW-" + (i + 1));
                // Fail-closed twin of FleetData.isInInvalidStateDueToGameLoadOrder:
                // a tracker-less member makes the lists-sync EMPTY the roster silently.
                if (getRepairTracker.invoke(shadow) == null) {
                    throw new IllegalStateException("shadow member '" + variantIds.get(i)
                            + "' has a null RepairTracker — the fab lists-sync would silently"
                            + " empty the roster (invalid-load-order guard)");
                }
                if (Boolean.TRUE.equals(isMothballed.invoke(shadow))) {
                    throw new IllegalStateException("shadow member '" + variantIds.get(i)
                            + "' minted MOTHBALLED — it would silently drop from every sum");
                }
                addFleetMember.invoke(fab, shadow);
            }

            // ONE members-lists-only sync (the getSortedMembers idiom), then freeze forever:
            // the full sync path re-crews/re-derives — campaign machinery the fab must not run.
            setOnlySync.invoke(fab, Boolean.TRUE);
            int synced = ((Number) getNumMembers.invoke(fab)).intValue();
            setOnlySync.invoke(fab, Boolean.FALSE);
            setForceNoSync.invoke(fab, Boolean.TRUE);
            if (synced != variantIds.size()) {
                throw new IllegalStateException("fab FleetData synced " + synced
                        + " members for " + variantIds.size() + " shadow mints — a short roster"
                        + " inflates every threshold bonus (fail-closed)");
            }

            // Gate 2: the fail-closed CampaignFleetAPI token on the union commander's stats.
            Object token = Proxy.newProxyInstance(loader, new Class<?>[] {campaignFleetApi},
                    new FleetTokenHandler(fab));
            installFleetToken(loader, unionPersonApi, token);

            String echo = engineEcho(loader, side, fab, unionPersonApi, personApi, charStatsApi,
                    fleetDataApi, variantIds.size());
            return new AllSkillsFleetLaw.Mint(fab, token, echo);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException
                    && e.getCause() != null) ? e.getCause() : e;
            throw new IllegalStateException(
                    "all-skills fleet-context mint failed: " + cause, cause);
        }
    }

    /**
     * Install {@code mint}'s gate-2 token on another commander person's stats (the client's
     * engine-default mission commander is a SECOND person carrying the union sheet) and
     * re-derive its character-stats effects.
     */
    public static void installCommanderFleetToken(ClassLoader loader, AllSkillsFleetLaw.Mint mint,
            Object personApi) {
        if (mint == null || personApi == null) {
            throw new IllegalStateException("all-skills fleet-context: token install with "
                    + (mint == null ? "no mint" : "no person") + " — fail-closed");
        }
        try {
            installFleetToken(loader, personApi, mint.fleetToken);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException
                    && e.getCause() != null) ? e.getCause() : e;
            throw new IllegalStateException(
                    "all-skills fleet-context token install failed: " + cause, cause);
        }
    }

    /**
     * Take the gate-2 token OFF {@code personApi}'s stats ({@code setFleet(null)}) and run the
     * engine re-derive — for a side whose new document has no roster: its union person must not
     * keep answering the previous fab. The caller re-applies the character-stats effects after.
     *
     */
    public static void clearCommanderFleetToken(ClassLoader loader, Object personApi) {
        if (personApi == null) {
            throw new IllegalStateException("all-skills fleet-context: token clear with no person"
                    + " — fail-closed");
        }
        try {
            installFleetToken(loader, personApi, null);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException
                    && e.getCause() != null) ? e.getCause() : e;
            throw new IllegalStateException(
                    "all-skills fleet-context token clear failed: " + cause, cause);
        }
    }

    private static void installFleetToken(ClassLoader loader, Object personApi, Object token)
            throws Exception {
        Object stats = Class.forName(PERSON_API, false, loader).getMethod("getStats")
                .invoke(personApi);
        if (stats == null) {
            throw new IllegalStateException("union person getStats() returned null");
        }
        Class<?> charStatsApi = Class.forName(MUTABLE_CHAR_STATS_API, false, loader);
        charStatsApi.getMethod("setFleet", Class.forName(CAMPAIGN_FLEET_API, false, loader))
                .invoke(stats, token);
        // The engine's own re-derive on a fleet change (FleetData.setCommander runs exactly this).
        //Every match person carries skipRefresh, so for ours it does nothing: the
        // caller re-applies the character-stats effects headless after every token install.
        charStatsApi.getMethod("refreshCharacterStatsEffects", boolean.class)
                .invoke(stats, Boolean.FALSE);
    }

    /**
     * The canonical echo, computed by the ENGINE's own statics over the fab (never re-derived
     * project-side): the absolute side, member count, the phase/combat DP sums, bays, the phase
     * threshold constant and the resulting phase_corps speed/peak bonuses. Byte-identical across
     * ends for the same descriptor side — the watch parity assert consumes it verbatim.
     */
    private static String engineEcho(ClassLoader loader, int side, Object fab,
            Object unionPersonApi, Class<?> personApi, Class<?> charStatsApi,
            Class<?> fleetDataApi, int members) throws Exception {
        Class<?> base = Class.forName(BASE_SKILL_DESC, true, loader);
        Class<?> phaseCorps = Class.forName(PHASE_CORPS, true, loader);
        Object unionStats = personApi.getMethod("getStats").invoke(unionPersonApi);
        float phaseDp = ((Number) base.getMethod("getPhaseOP", fleetDataApi, charStatsApi)
                .invoke(null, fab, unionStats)).floatValue();
        float combatDp = ((Number) base.getMethod("getTotalCombatOP", fleetDataApi, charStatsApi)
                .invoke(null, fab, unionStats)).floatValue();
        float bays = ((Number) base.getMethod("getNumFighterBays", fleetDataApi)
                .invoke(null, fab)).floatValue();
        float phaseThr = base.getField("PHASE_OP_THRESHOLD").getFloat(null);
        float speedMax = phaseCorps.getField("PHASE_SPEED_BONUS").getFloat(null);
        float peakMax = phaseCorps.getField("PEAK_TIME_BONUS").getFloat(null);
        Object calc = base.getConstructor().newInstance();
        Method bonus = base.getMethod("getThresholdBasedRoundedBonus",
                float.class, float.class, float.class);
        float pcSpeed = ((Number) bonus.invoke(calc, speedMax, phaseDp, phaseThr)).floatValue();
        float pcPeak = ((Number) bonus.invoke(calc, peakMax, phaseDp, phaseThr)).floatValue();
        return AllSkillsFleetLaw.echoLine(side, members, phaseDp, phaseThr, pcSpeed, pcPeak,
                combatDp, bays);
    }

    private static Object settingsOrThrow(ClassLoader loader) throws Exception {
        Object settings = Class.forName(GLOBAL_CLASS, true, loader)
                .getMethod("getSettings").invoke(null);
        if (settings == null) {
            throw new IllegalStateException("Global.getSettings() null at all-skills"
                    + " fleet-context mint — a game-free context cannot honor the knob");
        }
        return settings;
    }

    /**
     * Gate-2 token semantics: {@code getFleetData()} answers with the REAL fab; {@code Object}
     * methods behave; EVERYTHING else throws naming the method — the engine only null-checks
     * the commander's fleet on the throttle path and the real skill reads go through
     * {@code getFleetData()} (class doc), so any other read is an unmapped engine
     * path that must surface loudly, never get a faked value.
     */
    public static final class FleetTokenHandler implements InvocationHandler {
        private final Object fleetData;

        public FleetTokenHandler(Object fleetData) {
            this.fleetData = fleetData;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("getFleetData".equals(name) && (args == null || args.length == 0)) {
                return fleetData;
            }
            if (method.getDeclaringClass() == Object.class) {
                switch (name) {
                    case "toString":
                        return "cc-allskills-fleet-token";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                }
            }
            throw new IllegalStateException("cc-allskills fleet token: unexpected engine read '"
                    + name + "' — map this path and extend the token with the REAL answer"
                    + " (never a fake)");
        }
    }
}
