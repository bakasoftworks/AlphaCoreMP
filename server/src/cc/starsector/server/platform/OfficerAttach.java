package cc.starsector.server.platform;

import cc.starsector.server.match.OfficerRosterCodec;
import cc.starsector.server.match.OfficerRouting;
import cc.starsector.server.match.OfficerSkillCheck;
import cc.starsector.server.runtime.ServerConfig;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server half of the officer-skills wiring: attaches
 * each fielded variant's json-assigned officer to the FleetMember BEFORE {@code spawnFleetMember},
 * so the engine applies the skills through its OWN chain — {@code instantiateForCombat} → Ship
 * ctor → {@code CharacterStats.applyPersonalToShip} (no piloted-ness gate). The attach
 * recipe is the probe-proven sequence, since the fold living in the ONE shared
 * {@link PersonMint} (portrait REQUIRED there — the non-default marker:
 * {@code Person.isDefault()} is ONLY the portrait, and it is STATS, not presentation) →
 * {@code member.setCaptain} here.
 *
 * <p>Officer table = mod data {@code data/cc/officers.txt}, read through the engine's own
 * {@code SettingsAPI.loadText} (the resource registry already carries the cc_client data dir —
 * the same content pass that resolves the fielded variants) and decoded by the shared
 * {@link OfficerRosterCodec} the client parity attach uses. Table ABSENT (pre-roll mod data)
 * = loud WARN once, no officers — fail-soft symmetric (an old client jar has no attach either).
 * Table MALFORMED = fail-closed THROW (a half-applied table is a stats desync by construction).
 *
 * <p>Skill ids are FAIL-CLOSED (folded into): every id
 * resolves through {@code SettingsAPI.getSkillSpec} before a single {@code setSkillLevel} lands,
 * and an unknown id THROWS naming variant + skill. The earlier lane failed OPEN — the unknown id
 * surfaced as a misattributed NPE deep in the engine's apply chain, mid-round, on whichever hull
 * happened to carry the row. {@link #validateRowsForFieldedVariants} runs the same check EAGERLY
 * at serve boot over every variant the shape can field, so a bad row kills the boot instead. A
 * known-but-non-combat id WARNs once (inert on a captain, but not a broken table).
 *
 * <p>Knob {@code cc.server.officerSkills} / {@code CC_SERVER_OFFICER_SKILLS} /
 * {@code server.officerSkills}: STRICT bool, DEFAULT ON (the recorded design choice:
 * seat-piloted ships carry their json-assigned officer's skills, owner-fleet-faithful; the
 * default-ON confirm rides the debrief ask #2). Flip BOTH ends together — the client twin
 * is {@code cc.predict.officerSkills} (stats do not ride the wire).
 */
public final class OfficerAttach {

    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    private static final String PERSON_API = "com.fs.starfarer.api.characters.PersonAPI";
    private static final String FLEET_MEMBER_API = "com.fs.starfarer.api.fleet.FleetMemberAPI";
    private static final String OFFICERS_PATH = "data/cc/officers.txt";

    /** STRICT bool, default ON (empty/true → on, false → off, anything else throws). */
    private static final boolean OFFICER_SKILLS = parseOfficerSkills(ServerConfig.resolve(
            "cc.server.officerSkills", "CC_SERVER_OFFICER_SKILLS", "server.officerSkills", ""));

    private static Map<String, OfficerRosterCodec.OfficerSpec> tableByVariant;
    private static Recipe recipe;
    private static int attachedCount;
    /** Variant rows whose skill ids already passed the settings check (idempotent, per boot). */
    private static final Set<String> skillCheckedVariants = new LinkedHashSet<>();
    /** Skill ids already WARNed as inert (non-combat-officer) — loud ONCE per boot, never spam. */
    private static final Set<String> inertSkillWarned = new LinkedHashSet<>();

    private OfficerAttach() {
    }

    private static boolean parseOfficerSkills(String raw) {
        switch (raw) {
            case "":
            case "true":
                return true;
            case "false":
                return false;
            default:
                throw new IllegalArgumentException(
                        "cc.server.officerSkills must be true|false, got: " + raw);
        }
    }

    /**
     * Attach the variant's officer (if the table has a row and the knob is ON) to {@code member}
     * — call BEFORE {@code spawnFleetMember}. Returns true when a captain was attached. Never
     * throws for "no row"/"table absent"; throws on a malformed table or a failed attach step
     * (fail-closed: a half-attached captain must not spawn).
     */
    public static synchronized boolean maybeAttach(Object memberApi, String variantId) {
        return maybeAttach(memberApi, variantId, null);
    }

    /**
     * The row-carried overload — a schedule row's own officer spec
     * ({@code WaveSchedule.WaveRow.officer()}: the faction tables' per-wave officer rule, the
     * Ziggurat boss captain) WINS over the variant-keyed table row; null = the table lookup
     * above, byte-identical. Same fail-closed skill check, keyed by the spec's own id (the
     * boot sweep {@link #validateRowSpecs} clears every row spec a shape can field first).
     */
    public static synchronized boolean maybeAttach(Object memberApi, String variantId,
            OfficerRosterCodec.OfficerSpec rowSpecOrNull) {
        return attachOrNull(memberApi, variantId, rowSpecOrNull) != null;
    }

    /**
     * The variant-keyed table row for {@code variantId} (null = none / knob off) —
     * the wave lane resolves it for the GOAL id when a row fields an INFLATED id (the table never
     * knows a server-minted {@code cc_wave_*} id) and passes it as the row spec.
     */
    public static synchronized OfficerRosterCodec.OfficerSpec tableSpecOrNull(String variantId) {
        if (!OFFICER_SKILLS || variantId == null) {
            return null;
        }
        return tableOrLoad().get(variantId);
    }

    /**
     * The spec-returning twin of {@link #maybeAttach(Object, String,
     * OfficerRosterCodec.OfficerSpec)} — returns the captain ACTUALLY attached (the row spec,
     * or the variant-keyed table row), null when none was (knob off / no row). The wave lane
     * puts exactly this spec on the RAPP v3 row so the client twin fields the same captain.
     */
    public static synchronized OfficerRosterCodec.OfficerSpec attachOrNull(Object memberApi,
            String variantId, OfficerRosterCodec.OfficerSpec rowSpecOrNull) {
        if (!OFFICER_SKILLS || memberApi == null || variantId == null) {
            return null;
        }
        OfficerRosterCodec.OfficerSpec spec = rowSpecOrNull;
        String checkKey = variantId;
        if (spec == null) {
            Map<String, OfficerRosterCodec.OfficerSpec> table = tableOrLoad();
            spec = table.get(variantId);
            if (spec == null) {
                return null;
            }
        } else {
            checkKey = rowSpecKey(spec);
        }
        // Every skill id is resolved
        // against the server-published SettingsAPI BEFORE a single setSkillLevel lands. The
        // earlier lane failed OPEN — an unknown id degraded into a misattributed NPE deep in
        // the engine's apply chain, mid-round, on whichever wave hull happened to carry the row.
        // Table growth (the 6 AI-core twins) is exactly that defect's trigger.
        validateSkillIds(checkKey, spec);
        try {
            Recipe r = recipeOrResolve();
            // Mint through the ONE shared recipe (PersonMint — the fold of the previously
            // byte-identical inline mint; portrait REQUIRED there, the isDefault stats marker).
            Object person = PersonMint.mint(spec.personality, spec.skills, spec.firstName,
                    spec.lastName, spec.level, spec.portraitPath, spec.aiCoreId); //
            r.setCaptain.invoke(memberApi, person);
            attachedCount++;
            System.out.println("cc-officer attach variant=" + variantId + " name='"
                    + spec.firstName + " " + spec.lastName + "' personality=" + spec.personality
                    + " level=" + spec.level + " skills=" + spec.skills.size()
                    + " ai_core=" + (spec.aiCoreId.isEmpty() ? "-" : spec.aiCoreId)
                    + " total_attached=" + attachedCount);
            return spec;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = (e instanceof InvocationTargetException && e.getCause() != null)
                    ? e.getCause() : e;
            throw new IllegalStateException("officer attach failed for variant '" + variantId
                    + "': " + cause, cause);
        }
    }

    /**
     * SEAT-hull overload — the officer-TRANSFER law (the design rule: "the player's skills should
     * stay attached wherever the seat goes"): try the FIELDED variant's curated row first (a
     * {@code /ship} pick that has its own row keeps the variant-tuned officer — t=0 behavior
     * unchanged), and when the fielded variant has NO row, fall back to the SEAT's
     * descriptor-variant row so the seat's officer follows the player onto out-of-table picks
     * (player-exported variants, e.g. grendel_Hypervelocity — the defect). Client twin =
     * {@code OfficerLoadout.captainOrNull(fielded, seatDescriptor, seat)}: same law, stats are
     * off-wire so the ends must route identically — both iterate the ONE
     * {@link OfficerRouting#curatedLookupOrder} law (the parity contract:
     * {@code OfficerRoutingContractRows}).
     */
    public static boolean maybeAttachSeat(Object memberApi, String fieldedVariantId,
            String seatDescriptorVariantId) {
        List<String> order = OfficerRouting.curatedLookupOrder(fieldedVariantId,
                seatDescriptorVariantId, true);
        for (int i = 0; i < order.size(); i++) {
            if (maybeAttach(memberApi, order.get(i))) {
                if (i > 0) {
                    System.out.println("cc-officer TRANSFER fielded=" + fieldedVariantId
                            + " has no curated row -> seat descriptor row " + order.get(i));
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Variant-object overload for the roster deploy lane ({@code mintCrewedMember} holds the
     * HullVariantSpec, not the id): resolves the id via the spec's own public
     * {@code getHullVariantId()} and delegates. Fail-soft on an unresolvable id (loud once
     * per boot would be noise — the roster lane logs per-ship already).
     */
    public static boolean maybeAttachByVariant(Object memberApi, Object variantSpec) {
        if (!OFFICER_SKILLS || variantSpec == null) {
            return false;
        }
        try {
            Object id = variantSpec.getClass().getMethod("getHullVariantId").invoke(variantSpec);
            return maybeAttach(memberApi, id instanceof String ? (String) id : null);
        } catch (Exception e) {
            System.out.println("cc-officer attach WARN: getHullVariantId failed ("
                    + e + ") -> no officer for this member");
            return false;
        }
    }

    /**
     * Validate EVERY officer row a round can field, at serve boot,
     * before any hull deploys — a bad row must kill the boot, not the mid-round wave that first
     * carries it. {@code variantIds} is the shape's full fielded set (descriptor rows + every
     * scheduled wave hull); ids with no table row are legal and skipped (the "SOME hulls" law —
     * a bare vanilla variant is deliberately unofficered). THROWS on the first unknown skill id,
     * naming variant + skill; logs one greppable summary line.
     *
     * <p>RETURNS the subset of {@code variantIds} that actually CARRIED a row. A shape whose
     * design REQUIRES officers (the AI-core twins exist for nothing else) folds
     * that set against its own required ids and fail-closes on a miss — this sweep cannot do it
     * alone, because "no row" is legal here and an absent/stale deployed {@code officers.txt}
     * fail-softs to an EMPTY table ({@link #tableOrLoad}), which would otherwise pass silently.
     *
     * <p>Symmetric with the attach-site check by construction: both call
     * {@link #validateSkillIds}, and the attach site re-checks nothing this sweep already
     * cleared. Knob OFF = no attach lane at all, so the sweep is a no-op — but a LOGGED one,
     * never silent ({@link #officerSkillsEnabled} lets a caller tell "the operator turned it off"
     * from "the table is gone").
     */
    public static synchronized Set<String> validateRowsForFieldedVariants(
            Collection<String> variantIds) {
        if (variantIds == null) {
            return Collections.emptySet();
        }
        Set<String> distinct = new LinkedHashSet<>(variantIds);
        if (!OFFICER_SKILLS) {
            System.out.println("cc-officer boot_skill_check SKIPPED knob_on=false"
                    + " fielded_variants=" + distinct.size()
                    + " -> no captain is attached this boot");
            return Collections.emptySet();
        }
        Map<String, OfficerRosterCodec.OfficerSpec> table = tableOrLoad();
        Set<String> officered = new LinkedHashSet<>();
        int skills = 0;
        for (String variantId : distinct) {
            OfficerRosterCodec.OfficerSpec spec = table.get(variantId);
            if (spec == null) {
                continue;
            }
            validateSkillIds(variantId, spec);
            officered.add(variantId);
            skills += spec.skills.size();
        }
        System.out.println("cc-officer boot_skill_check fielded_variants=" + distinct.size()
                + " rows_checked=" + officered.size() + " skill_ids=" + skills
                + " unofficered=" + (distinct.size() - officered.size()));
        return Collections.unmodifiableSet(officered);
    }

    /**
     * A fieldable spec must survive the RAPP v3 wire (encode → decode, same
     * stats) — checked at BOOT so a table/row spec the grammar cannot carry fails the serve
     * start, never a mid-round drain. Throws IllegalStateException (fail-closed).
     *
     * <p>PUBLIC because boot is no longer the only caller. An INFLATED wave
     * row re-keys its spec to the fielded {@code cc_wave_<goal>_<hex8>} id, which is exactly 17
     * chars longer than the line boot proved (prefix 8 + '_' + hex8), so a goal-keyed line of
     * 496..512 passes boot and blows the 512-char cap at the drain's mint;
     * {@code WaveOfficerRowGuard} re-runs this on the RE-KEYED spec and drops the captain
     * instead of killing the frame.
     *
     * <p>Both messages carry LENGTHS, never the table's id or
     * line (the boot sweep propagates them and the guard logs them; the security law: table text is
     * data, never echoed raw).
     */
    public static void requireWireRoundTrip(OfficerRosterCodec.OfficerSpec spec) {
        try {
            String line = OfficerRosterCodec.encodeLine(spec);
            OfficerRosterCodec.OfficerSpec back = OfficerRosterCodec.decodeOne(line, spec.variantId);
            if (!back.skills.equals(spec.skills) || back.level != spec.level
                    || !back.personality.equals(spec.personality)
                    || !back.portraitPath.equals(spec.portraitPath)
                    || !back.fullName().equals(spec.fullName())
                    || !back.aiCoreId.equals(spec.aiCoreId)) {
                throw new IllegalStateException("officer spec (variantId " + spec.variantId.length()
                        + " chars) does not round-trip the RAPP v3 officer line (line " + line.length()
                        + " chars)");
            }
        } catch (IllegalArgumentException bad) {
            throw new IllegalStateException("officer spec (variantId " + spec.variantId.length()
                    + " chars) cannot ride the RAPP v3 officer line: " + bad.getMessage(),
                    bad);
        }
    }

    /** The skill-check cache key of a row-carried spec (its own id — never a variant's). */
    private static String rowSpecKey(OfficerRosterCodec.OfficerSpec spec) {
        return "row:" + spec.variantId + ":" + spec.level + ":" + spec.personality;
    }

    /**
     * The boot-time twin of {@link #validateRowsForFieldedVariants} for
     * ROW-CARRIED specs — every officer a wave table can field (all categories: a live
     * {@code /wave <faction>} switch fields them mid-round with no second boot check) resolves
     * its skill ids eagerly; an unknown id THROWS at boot naming the spec. Knob OFF = logged
     * no-op like the table sweep. Returns the number of distinct specs checked.
     */
    public static synchronized int validateRowSpecs(
            Collection<OfficerRosterCodec.OfficerSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return 0;
        }
        if (!OFFICER_SKILLS) {
            System.out.println("cc-officer boot_row_spec_check SKIPPED knob_on=false"
                    + " row_specs=" + specs.size());
            return 0;
        }
        Set<String> seen = new LinkedHashSet<>();
        int skills = 0;
        for (OfficerRosterCodec.OfficerSpec spec : specs) {
            if (spec == null || !seen.add(rowSpecKey(spec))) {
                continue;
            }
            validateSkillIds(rowSpecKey(spec), spec);
            requireWireRoundTrip(spec);
            skills += spec.skills.size();
        }
        System.out.println("cc-officer boot_row_spec_check row_specs=" + seen.size()
                + " skill_ids=" + skills);
        return seen.size();
    }

    /**
     * Is the officer-attach knob ON for this boot (class doc's knob paragraph)? Lets a caller
     * whose shape requires officers distinguish an explicit operator OFF from a table that went
     * missing — the first is a decision, the second is the fail-closed case.
     */
    public static boolean officerSkillsEnabled() {
        return OFFICER_SKILLS;
    }

    /**
     * Unconditional officer-table boot warm — force the table read
     * (classload + {@code loadText} + codec decode, cached static) at serve boot for EVERY
     * shape. The fortress shape already pays this via
     * {@code FortressServeBootChecks#validateRowsForFieldedVariants}; every other shape paid
     * it inside the first served frame that attached an officer instead (part of the measured
     * first-wave-mint stall). Same laws as the attach path: knob OFF = no-op,
     * game-free/absent table fail-softs to the empty table inside {@link #tableOrLoad}.
     */
    public static synchronized void bootWarmTable() {
        if (!OFFICER_SKILLS) {
            return;
        }
        try {
            // Every table row must ride the RAPP v3 officer line — fail at boot.
            for (OfficerRosterCodec.OfficerSpec spec : tableOrLoad().values()) {
                requireWireRoundTrip(spec);
            }
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof ClassNotFoundException) {
                // Game-free CLASSPATH (no starfarer.api jar at all — the contract rigs):
                // the attach lane structurally cannot run there either, so the warm is
                // moot, not an error. Everything else (malformed table, resolvable-but-
                // broken Global) stays fail-closed — the fortress boot-check law.
                System.out.println("cc-officer boot_warm skipped (no engine API on the"
                        + " classpath: " + e.getCause() + ")");
                return;
            }
            throw e;
        }
    }

    /**
     * Resolve every skill id in {@code spec} through the server-published
     * {@code SettingsAPI.getSkillSpec} (the RealEngineContentInit-loaded skill table). UNKNOWN
     * (null spec, or a resolve throw) = fail-CLOSED IllegalStateException naming variant + skill.
     * KNOWN but not a combat-officer skill = inert on a ship captain: WARN once per id and carry
     * on (an admin/admiral skill applies nothing, but it is not a broken table).
     *
     * <p>Game-free contexts skip the check entirely when {@code Global.getSettings()} returns
     * null — the same fail-soft the table load takes there, and no attach happens in that
     * context either (the table is empty, so no row ever reaches here). A {@code Global} that
     * cannot be resolved at all is NOT that case and stays fail-CLOSED.
     * HEADLESS LAW: nothing here touches {@code createPersonalitySection} or
     * {@code Person.getFaction()}; a skill-spec read is a pure settings lookup.
     */
    private static void validateSkillIds(String variantId, OfficerRosterCodec.OfficerSpec spec) {
        if (skillCheckedVariants.contains(variantId)) {
            return;
        }
        final SkillProbe probe = skillProbeOrNull();
        if (probe == null) {
            return;
        }
        // Law body hoisted to the shared OfficerSkillCheck (the client twin
        // runs the SAME check at table load); this end feeds it the reflective probe.
        Set<String> inert = OfficerSkillCheck.validate(variantId, spec,
                new OfficerSkillCheck.SkillResolver() {
                    public Object skillSpecOrNull(String skillId) throws Exception {
                        return probe.getSkillSpec.invoke(probe.settings, skillId);
                    }

                    public boolean isCombatOfficerSkill(Object skillSpec) throws Exception {
                        return Boolean.TRUE.equals(probe.isCombatOfficerSkill.invoke(skillSpec));
                    }
                });
        for (String skillId : inert) {
            if (inertSkillWarned.add(skillId)) {
                System.out.println("cc-officer skill WARN: '" + skillId + "' (first seen on"
                        + " variant '" + variantId + "') exists but is NOT a combat-officer"
                        + " skill — it applies nothing to a ship captain");
            }
        }
        skillCheckedVariants.add(variantId);
    }

    /** The settings + skill-spec handles for {@link #validateSkillIds}, or null game-free. */
    private static SkillProbe skillProbeOrNull() {
        if (skillProbe != null) {
            return skillProbe;
        }
        try {
            ClassLoader loader = OfficerAttach.class.getClassLoader();
            Object settings = Class.forName(GLOBAL_CLASS, true, loader)
                    .getMethod("getSettings").invoke(null);
            if (settings == null) {
                return null; // game-free context: the table is empty there too
            }
            // Resolve off the PUBLIC interfaces, NEVER settings.getClass() (the probe
            // lesson: the impl is the inaccessible anonymous StarfarerSettings$1).
            SkillProbe p = new SkillProbe();
            p.settings = settings;
            p.getSkillSpec = Class.forName("com.fs.starfarer.api.SettingsAPI", false, loader)
                    .getMethod("getSkillSpec", String.class);
            p.isCombatOfficerSkill =
                    Class.forName("com.fs.starfarer.api.characters.SkillSpecAPI", false, loader)
                            .getMethod("isCombatOfficerSkill");
            skillProbe = p;
            return skillProbe;
        } catch (Exception e) {
            throw new IllegalStateException("officer skill-id check could not resolve the"
                    + " SettingsAPI skill table: " + e + " — refusing to attach captains with"
                    + " UNVALIDATED skill ids (a fail-CLOSED check)", e);
        }
    }

    private static Map<String, OfficerRosterCodec.OfficerSpec> tableOrLoad() {
        if (tableByVariant != null) {
            return tableByVariant;
        }
        try {
            ClassLoader loader = OfficerAttach.class.getClassLoader();
            Object settings = Class.forName(GLOBAL_CLASS, true, loader)
                    .getMethod("getSettings").invoke(null);
            if (settings == null) {
                System.out.println("cc-officer table: Global.getSettings() null (game-free"
                        + " context) -> no officers");
                tableByVariant = Collections.emptyMap();
                return tableByVariant;
            }
            String text;
            try {
                // Resolve off the PUBLIC SettingsAPI interface, NEVER settings.getClass() —
                // the impl is the anonymous StarfarerSettings$1 (inaccessible: reflective
                // invoke through it throws IllegalAccessException; the probe lesson).
                Class<?> settingsApi =
                        Class.forName("com.fs.starfarer.api.SettingsAPI", false, loader);
                text = (String) settingsApi
                        .getMethod("loadText", String.class).invoke(settings, OFFICERS_PATH);
            } catch (InvocationTargetException e) {
                // Absent file (pre-roll deployed mod data) = fail-soft loud; the deployed old
                // client jar has no attach lane either, so absence stays SYMMETRIC.
                System.out.println("cc-officer table: loadText('" + OFFICERS_PATH + "') threw "
                        + e.getCause() + " -> table ABSENT, no officers attached (deploy the"
                        + " mod data folder to arm)");
                tableByVariant = Collections.emptyMap();
                return tableByVariant;
            }
            List<OfficerRosterCodec.OfficerSpec> specs = OfficerRosterCodec.decode(text);
            Map<String, OfficerRosterCodec.OfficerSpec> byVariant = new LinkedHashMap<>();
            for (OfficerRosterCodec.OfficerSpec s : specs) {
                byVariant.put(s.variantId, s);
            }
            tableByVariant = Collections.unmodifiableMap(byVariant);
            System.out.println("cc-officer table: " + tableByVariant.size() + " officer row(s)"
                    + " loaded from " + OFFICERS_PATH + " knob_on=" + OFFICER_SKILLS);
            return tableByVariant;
        } catch (RuntimeException e) {
            throw e; // malformed table (codec throw) stays fail-closed
        } catch (Exception e) {
            throw new IllegalStateException("officer table load failed: " + e, e);
        }
    }

    private static Recipe recipeOrResolve() throws Exception {
        if (recipe != null) {
            return recipe;
        }
        ClassLoader loader = OfficerAttach.class.getClassLoader();
        Recipe r = new Recipe();
        Class<?> personApi = Class.forName(PERSON_API, false, loader);
        Class<?> memberApi = Class.forName(FLEET_MEMBER_API, false, loader);
        r.setCaptain = memberApi.getMethod("setCaptain", personApi);
        recipe = r;
        return recipe;
    }

    private static SkillProbe skillProbe;

    /** SettingsAPI.getSkillSpec + SkillSpecAPI.isCombatOfficerSkill handles (resolved once). */
    private static final class SkillProbe {
        Object settings;
        Method getSkillSpec;
        Method isCombatOfficerSkill;
    }

    /** The one non-mint attach handle (the mint itself lives in {@link PersonMint}). */
    private static final class Recipe {
        Method setCaptain;
    }
}
