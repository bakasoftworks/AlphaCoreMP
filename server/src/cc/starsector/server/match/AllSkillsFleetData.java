package cc.starsector.server.match;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * All-skills SIDE-0 FLEET CONTEXT: the engine's
 * commander-skill throttle
 * short-circuits to FULL effect when a ship's member resolves null {@code FleetDataAPI} or a
 * commander whose stats carry no fleet — exactly our fabricated contexts, so every
 * threshold-scaled commander skill (phase_corps & family) granted at maximum regardless of the
 * deployed fleet. The chosen shape is the ENGINE's own adapts-to-the-fleet scaling,
 * so this class builds a REAL {@code com.fs.starfarer.campaign.fleet.FleetData} carrying the
 * present battle's side-0 roster and satisfies both gates:
 * <ol>
 * <li><b>Gate 1 (member fleet data)</b>: shadow {@code FleetMember}s are minted from the
 *     DESCRIPTOR's side-0 SHIP rows via the engine's own context-free seam
 *     ({@code SettingsAPI.createFleetMember(SHIP, variantId)}, which is
 *     {@code new FleetMember(0, variantId, type)}) and added to
 *     the fab FleetData. Descriptor-derived, NOT live-fielded members: the client registry shape
 *     fields only the own seat locally (rosters spawn at wire sight) and the server's t=0 specs
 *     are occupancy-gated, so live members would give the two ends DIFFERENT sums — the
 *     descriptor is the one roster law both ends already share (stats are off-wire; identical
 *     throttle input by construction). Shadow members never enter combat, so the fab's
 *     member-side effects (fleetData rebind at lists-sync) touch nothing live. Callers hand the
 *     fab to every REAL side-0 member via
 *     {@code setFleetCommanderForStats(union, fab)} — the same member field the engine itself
 *     prefers at every resolution site ({@code getFleetData}, {@code getCommanderStats} and the combat
 *     fleet manager).</li>
 * <li><b>Gate 2 (commander stats fleet)</b>: the throttle only null-checks
 *     {@code cStats.getFleet()}; the sole real reads in the granted family go through
 *     {@code getFleet().getFleetData()} (the AuxiliarySupport and FieldRepairs skills).
 *     A full {@code CampaignFleet} is campaign-context-bound headless (ctor: sprite + fleet
 *     view + {@code CampaignEngine} name sources), so the
 *     union commander's stats get a fail-closed {@link Proxy} token that answers
 *     {@code getFleetData()} with the REAL fab and THROWS loudly on any other engine read — no
 *     faked values, and an unexpected read surfaces as a crash naming the method instead of a
 *     silent wrong stat.</li>
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
    /**
     * The engine's own campaign FleetData class — an UNOBFUSCATED name in 0.98a-RC8 (verified
     * against the engine bytecode); its
     * {@code (String namePrefix, String nameSourceFactionId)} ctor is campaign-free with BOTH
     * args null (the faction id is the only {@code CampaignEngine} touch).
     */
    private static final String OBF_FLEET_DATA = "com.fs.starfarer.campaign.fleet.FleetData";
    private static final String BASE_SKILL_DESC =
            "com.fs.starfarer.api.impl.campaign.skills.BaseSkillEffectDescription";
    private static final String PHASE_CORPS =
            "com.fs.starfarer.api.impl.campaign.skills.PhaseCorps";

    private AllSkillsFleetData() {
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
     * Build the side-0 fleet context: shadow-mint the roster, fab the engine FleetData, freeze
     * its sync, install the gate-2 token on {@code unionPersonApi}'s stats and re-derive its
     * character-stats effects (they first applied fleet-less at skill set — the engine's own
     * {@code refreshCharacterStatsEffects(false)} idiom).
     */
    public static AllSkillsFleetLaw.Mint mint(ClassLoader loader, List<String> variantIds,
            Object unionPersonApi) {
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

            // The fab: the engine's OWN FleetData, campaign-free with null name sources.
            Object fab = Class.forName(OBF_FLEET_DATA, true, loader)
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

            String echo = engineEcho(loader, fab, unionPersonApi, personApi, charStatsApi,
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
        // Character-level threshold effects (AuxiliarySupport family) first applied at skill
        // set with a null fleet — re-derive them now that both gates resolve. Engine idiom:
        // FleetData.setCommander does exactly this re-derive on commander change.
        charStatsApi.getMethod("refreshCharacterStatsEffects", boolean.class)
                .invoke(stats, Boolean.FALSE);
    }

    /**
     * The canonical echo, computed by the ENGINE's own statics over the fab (never re-derived
     * project-side): member count, the phase/combat DP sums, bays, the phase threshold constant
     * and the resulting phase_corps speed/peak bonuses. Byte-identical across ends for the same
     * descriptor — the watch parity assert consumes it verbatim.
     */
    private static String engineEcho(ClassLoader loader, Object fab, Object unionPersonApi,
            Class<?> personApi, Class<?> charStatsApi, Class<?> fleetDataApi, int members)
            throws Exception {
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
        return AllSkillsFleetLaw.echoLine(members, phaseDp, phaseThr, pcSpeed, pcPeak,
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
     * the commander's fleet on the throttle path and the two real reads in the granted family
     * go through {@code getFleetData()} (class doc), so any other read is an unmapped engine
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
