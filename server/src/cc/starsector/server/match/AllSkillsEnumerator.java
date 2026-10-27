package cc.starsector.server.match;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The all-skills enumerator:
 * the ONE law both ends use to derive the two all-skills sets from
 * their OWN engine's skill table — stats never ride the wire, so the sets must be identical by
 * construction (same walk, same filters, same elite rule) or the client predicts different
 * stats than the authority simulates.
 *
 * <p>Walk = {@code SettingsAPI.getSortedSkillIds()} (deterministic order, probe-proven)
 * → {@code getSkillSpec(id)}. Two sets:
 * <ul>
 * <li><b>ship</b> (the SEAT captain's skills): every id with {@code isCombatOfficerSkill()} —
 *     the engine applies these per-ship via {@code applyPersonalToShip}.</li>
 * <li><b>commander</b> (the side-0 default commander's skills): every id with ANY effect typed
 *     {@code ALL_SHIPS_IN_FLEET} / {@code ALL_FIGHTERS_IN_FLEET} ({@code SkillEffectType} on
 *     {@code SkillEffectSpecAPI.getType()}; engine filter in {@code CharacterStats},
 *     SPOT-CHECKED) — applied fleetwide at ship-stats create through the manager-level
 *     default commander ({@code FleetMember.instantiateForCombat}).</li>
 * </ul>
 * Both sets take ONLY player-obtainable rows: any spec tagged {@code npc_only} is skipped
 * (a fix law — the A/B statdump proved the old unfiltered walk
 * granted {@code shield_modulation} (npc_only+deprecated, kept for save compatibility), an
 * illegit ×0.8 shield DR + 0.15 hardFluxFrac no campaign character can have; {@code deprecated}
 * always co-occurs with npc_only in 0.98a data, so one tag filters the whole over-grant).
 *
 * <p>Level (elite rule, re-derived from the save capture): {@code 2.0f} iff
 * {@code SkillSpecAPI.isElite()} — the .skill file's own {@code "elite":true} flag, present on
 * exactly the 14 player combat skills and absent on the fleetwide tier-1 writers
 * (flux_regulation & co.). The prior any-effect-{@code requiredSkillLevel >= 2} rule is DEAD:
 * it over-tiered fleetwide skills the player can never elite (flux_regulation pct 10 vs
 * vanilla 5).
 *
 * <p>Engine-free by construction (the {@code OfficerSkillCheck} idiom): the resolver is
 * injected — server reflective (SkillProbe idiom), client typed API. FAIL-CLOSED: a listed id
 * whose spec resolves null, or any resolver throw, is an {@link IllegalStateException} — a
 * half-enumerated set is a stats desync by construction.
 *
 * <p>{@code cc_}-prefixed ids are EXCLUDED from both sets: the client mod ships
 * display-only marker skills (e.g. {@code cc_skills_all}, the "SKILLS: ALL" codex row), and BOTH
 * engines load the mod's skill table ({@code RealEngineContentInit} registers the mod data dir
 * headless too) — without this shared skip each end would grant its own markers to the all-skills
 * captain, and a marker with any real effect would double it. Living here keeps the exclusion
 * symmetric by construction (this class is the one shared source, bundled compiled into the
 * client jar).
 */
public final class AllSkillsEnumerator {

    /**
     * The ONE all-skills marker portrait, both ends (pinned here — was a keep-in-sync
     * javadoc pact between {@code AllSkillsAttach.NON_DEFAULT_MARKER_PORTRAIT} and
     * {@code OfficerLoadout.ALLSKILLS_FALLBACK_PORTRAIT}): a real 0.98a sprite (zero-padded
     * {@code 01} = the DEFECT-2 lesson) whose presence flips {@code Person.isDefault()} —
     * a STATS lever ({@code SupportDoctrine.isNoOfficer}), so drift is not cosmetic.
     * String constant ⇒ compile-time inlined into both jars; this shared class is the pin.
     */
    public static final String ALLSKILLS_MARKER_PORTRAIT =
            "graphics/portraits/portrait_mercenary01.png";

    /** Effect-type names that put a skill in the commander (fleetwide) set. */
    private static final String TYPE_ALL_SHIPS = "ALL_SHIPS_IN_FLEET";
    private static final String TYPE_ALL_FIGHTERS = "ALL_FIGHTERS_IN_FLEET";

    /** Data-file tag marking a row no player character can obtain (skipped from BOTH sets). */
    private static final String TAG_NPC_ONLY = "npc_only";

    /** How the calling end reads ITS engine's skill table (see class doc for the two ends). */
    public interface SkillTable {

        List<String> sortedSkillIds() throws Exception;

        Object skillSpecOrNull(String skillId) throws Exception;

        boolean isCombatOfficerSkill(Object skillSpec) throws Exception;

        /** The spec's {@code getEffectsAPI()} list (elements stay opaque to this class). */
        List<?> effects(Object skillSpec) throws Exception;

        /** {@code SkillEffectSpecAPI.getType().name()} for one effect. */
        String effectTypeName(Object effect) throws Exception;

        /** {@code SkillSpecAPI.hasTag(tag)} — the data-file tags column. */
        boolean hasTag(Object skillSpec, String tag) throws Exception;

        /** {@code SkillSpecAPI.isElite()} — the .skill file's own {@code "elite":true} flag. */
        boolean isElite(Object skillSpec) throws Exception;
    }

    /** The derived skill→level maps, insertion-ordered by the engine's sorted walk. */
    public static final class Sets {
        public final Map<String, Float> ship;
        public final Map<String, Float> commander;

        /**
         * ship∪commander — the vanilla CHARACTER shape: the campaign
         * player is captain AND fleet commander in one person, so the commander the engine reads
         * back (e.g. {@code CyberneticAugmentation.getNumEliteSkillsOfFleetCommander} counting
         * elite skills via {@code member.getFleetCommanderForStats()}) carries the elite combat
         * set too. A commander-set-only person counts 0 elites (fleetwide writers are all tier
         * 1.0) and silently elides the CA ±14% family. Same walk order, same levels — a skill in
         * both sets carries one identical level by construction.
         */
        public final Map<String, Float> union;

        Sets(Map<String, Float> ship, Map<String, Float> commander, Map<String, Float> union) {
            this.ship = Collections.unmodifiableMap(ship);
            this.commander = Collections.unmodifiableMap(commander);
            this.union = Collections.unmodifiableMap(union);
        }
    }

    private AllSkillsEnumerator() {
    }

    public static Sets enumerate(SkillTable table) {
        Map<String, Float> ship = new LinkedHashMap<>();
        Map<String, Float> commander = new LinkedHashMap<>();
        Map<String, Float> union = new LinkedHashMap<>();
        List<String> ids;
        try {
            ids = table.sortedSkillIds();
        } catch (Exception e) {
            throw new IllegalStateException("all-skills enumerate: getSortedSkillIds failed ("
                    + e + ") — fail-closed, a partial set is a stats desync", e);
        }
        if (ids == null || ids.isEmpty()) {
            throw new IllegalStateException("all-skills enumerate: getSortedSkillIds returned "
                    + (ids == null ? "null" : "empty") + " — the engine skill table is not loaded");
        }
        for (String id : ids) {
            if (id != null && id.startsWith("cc_")) {
                continue; // display-only marker skills (class doc) — never on an all-skills captain
            }
            try {
                Object spec = table.skillSpecOrNull(id);
                if (spec == null) {
                    throw new IllegalStateException("all-skills enumerate: listed skill id '" + id
                            + "' resolved a null spec — engine table inconsistency, fail-closed");
                }
                if (table.hasTag(spec, TAG_NPC_ONLY)) {
                    continue; // not player-obtainable (class doc: the shield_modulation over-grant)
                }
                boolean combatOfficer = table.isCombatOfficerSkill(spec);
                boolean fleetwide = false;
                List<?> effects = table.effects(spec);
                if (effects != null) {
                    for (Object effect : effects) {
                        String type = table.effectTypeName(effect);
                        if (TYPE_ALL_SHIPS.equals(type) || TYPE_ALL_FIGHTERS.equals(type)) {
                            fleetwide = true;
                        }
                    }
                }
                Float level = Float.valueOf(table.isElite(spec) ? 2.0f : 1.0f);
                if (combatOfficer) {
                    ship.put(id, level);
                }
                if (fleetwide) {
                    commander.put(id, level);
                }
                if (combatOfficer || fleetwide) {
                    union.put(id, level);
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("all-skills enumerate: skill '" + id
                        + "' walk failed (" + e + ") — fail-closed", e);
            }
        }
        if (ship.isEmpty() || commander.isEmpty()) {
            throw new IllegalStateException("all-skills enumerate: derived an empty set (ship="
                    + ship.size() + " commander=" + commander.size() + " of " + ids.size()
                    + " ids) — the filters found nothing, which vanilla data never produces");
        }
        return new Sets(ship, commander, union);
    }
}
