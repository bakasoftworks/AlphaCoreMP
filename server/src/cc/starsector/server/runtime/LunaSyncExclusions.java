package cc.starsector.server.runtime;

import java.util.Locale;

/**
 * The LunaSettings rows that never ride the DOWN sync, shared by the serve (skips them at the mint, counts them
 * as {@code luna-sync excluded=<n>}) and the kit (REFUSES the whole sheet when one arrives: a modified serve
 * attacking players is in the threat model). Three sources, any one excludes:
 * <ol>
 * <li>the built-in table below — a declared KEY LIST, never a type filter: the corpus's
 *     per-player rows, named row by row from the twelve mods' {@code data/config/LunaSettings.csv} files, every
 *     row of the twelve swept: per-player PRESENTATION — UI anchors, positions and
 *     colours, HUD
 *     messages and warnings, the minimap, chatter and voice, tooltips and help pop-ups, refit-screen integration,
 *     title-screen effects, performance / potato modes, blur, the console's own UI, debug modes; the player's OWN
 *     data storage (AGC's weapon-mode save / load / share rows decide which of the player's files that mod
 *     writes); the key rows the name net misses (ArmaArmatura's String-typed repair-kit key; ConsoleCommands'
 *     hold-modifier Booleans, which ARE the console chord's modifiers); and
 *     MagicLib's three campaign toggles (achievements / paintjobs / bounty deadlines have no combat effect, and
 *     their listeners can write a common file). A row a combat reader uses — ship stats, AI, damage, weapons,
 *     systems, spawns — is never here: mirroring those is what the sync is for (Apex's eurobeat mode, for one, is
 *     read by its Travel Drive ship-system AI);</li>
 * <li>a WHOLE mod: {@code lunalib} (its rows are keybinds / debug, plus the LoadedSettings copy bug),
 *     {@code cc_client} (ours), {@code shaderLib} (GraphicsLib: every row is per-player graphics / accessibility
 *     and its listener reloads shader data on the game thread; the serve never loads it,
 *     so this is the kit-side guard against a modified serve);</li>
 * <li>the NAME safety net: a fieldId containing {@code key}, {@code bind} or {@code hold(ctrl|shift|alt)},
 *     case-insensitive — a false positive leaves a value unsynced (today's behaviour); a key row that slipped the
 *     list would overwrite a joiner's own keybind with the host's. The asymmetric cost decides.</li>
 * </ol>
 * The host's own list ({@code luna.sync.exclude=<modId>:<fieldId>,...} in {@code cc-server.properties})
 * is the serve's, applied beside this table, never inside it.
 *
 * <p>Shared: java.base only, hand-rolled (no regex, no collections to initialize), the {@code PilotControlNames}
 * shape; both jars pack it. Mod ids are case-sensitive, exactly as {@code mod_info.json} spells them.
 *
 */
public final class LunaSyncExclusions {

    /** Whole mods whose every row is excluded: LunaLib, ours and GraphicsLib. */
    private static final String[] EXCLUDED_MODS = {"lunalib", "cc_client", "shaderLib"};

    /**
     * The (modId, fieldId) table, parallel arrays; keep the two in step — one line per mod in BOTH arrays, in the
     * same order, the row count in the trailing comment (the static check below catches a length skew; a contract
     * row checks every pair against its mod's CSV, so a misspelt id fails there).
     *
     */
    private static final String[] MOD = {
            "MagicLib", "MagicLib", "MagicLib", "MagicLib", "MagicLib", "MagicLib", "MagicLib", // 7
            "armaa", "armaa", "armaa", "armaa", "armaa", // 5
            "lw_console", "lw_console", "lw_console", "lw_console", "lw_console", "lw_console", "lw_console",
            "lw_console", "lw_console", "lw_console", "lw_console", "lw_console", "lw_console", "lw_console",
            "lw_console", "lw_console", "lw_console", // 17
            "advanced_gunnery_control_dbeaa06e", "advanced_gunnery_control_dbeaa06e",
            "advanced_gunnery_control_dbeaa06e", "advanced_gunnery_control_dbeaa06e",
            "advanced_gunnery_control_dbeaa06e", "advanced_gunnery_control_dbeaa06e",
            "advanced_gunnery_control_dbeaa06e", "advanced_gunnery_control_dbeaa06e",
            "advanced_gunnery_control_dbeaa06e", "advanced_gunnery_control_dbeaa06e",
            "advanced_gunnery_control_dbeaa06e", "advanced_gunnery_control_dbeaa06e",
            "advanced_gunnery_control_dbeaa06e", // 13
            "aitweaks", "aitweaks", "aitweaks", "aitweaks", "aitweaks", // 5
            "apex_design", "apex_design", // 2
            "assortment_of_things", "assortment_of_things", "assortment_of_things", "assortment_of_things",
            "assortment_of_things", "assortment_of_things", "assortment_of_things", "assortment_of_things",
            "assortment_of_things", "assortment_of_things", "assortment_of_things", "assortment_of_things",
            "assortment_of_things", "assortment_of_things", // 14
            "secretsofthefrontier", "secretsofthefrontier", "secretsofthefrontier", // 3
    };
    private static final String[] FIELD = {
            // MagicLib: the subsystem widget / info rows, then the three campaign toggles
            "magiclib_subsystems_widgetOffsetX", "magiclib_subsystems_widgetOffsetY",
            "magiclib_subsystems_infoTextFadeout", "magiclib_subsystems_showInfoDefault",
            "magiclib_enableAchievements", "magiclib_enablePaintjobs", "magiclib_enableBountyDeadlines",
            // armaa: the String-typed key, the effects level, Dawn's voice and chatter rate, WINGCOM chatter
            "armaa_repairKitKey", "armaa_performanceMode", "armaa_enableDawnVoice", "armaa_dawnChatterFrequency",
            "armaa_enableWingcom",
            // lw_console: the chord modifiers, then every row of the console's own UI
            "console_holdCTRL", "console_holdSHIFT", "console_holdALT", "console_enableBackgroundBlur",
            "console_inputTextColor", "console_outputTextColor", "console_matchColor", "console_backgroundDarkening2",
            "console_enableAutocomplete", "console_contextSensitiveSuggestions", "console_enableTabCycling",
            "console_autocompletionsCount", "console_textfieldResizeBehaviour", "console_showCommandInfo",
            "console_showCompileErrors", "console_syntaxHighlightRuncode", "console_showRAMAndVram",
            // AGC: message / combat-UI placement, HUD messages, refit-screen UI, the player's weapon-mode storage
            "agc_messagePositionX", "agc_messagePositionY", "agc_combatUiAnchorX", "agc_combatUiAnchorY",
            "agc_messageDisplayDuration", "agc_suppressHudWarning", "agc_enableRefitScreenIntegration",
            "agc_showRefitScreenButton", "agc_enablePersistentFireModes", "agc_enableAutoSaveLoad",
            "agc_persistChangesInCombat", "agc_tagStorageMode", "agc_shareGlobalTags",
            // aitweaks: the combat map grid, the dev-mode highlight, dev mode, title-screen fire, refit hullmod display
            "aitweaks_remove_combat_map_grid", "aitweaks_highlight_custom_ai", "aitweaks_enable_devmode",
            "aitweaks_enable_title_screen_fire", "aitweaks_hide_hullmods",
            // apex_design: the effects level, the repair-number popup
            "apex_potatomode", "apex_repair_floaty",
            // assortment_of_things: the minimap, the abyss brightness, the which-mod tooltips, help pop-ups
            "rat_enableMinimap", "rat_minimapShape", "rat_minimapStarscape", "rat_minimapFuel", "rat_abyssBrighter",
            "rat_whichModShips", "rat_whichModWeapons", "rat_whichModFighters", "rat_whichModHullmods",
            "rat_whichModCargo", "rat_whichModSkills", "rat_whichModIndustries", "rat_whichModConditions",
            "rat_forceDisableHelp",
            // secretsofthefrontier: the battle-start officer chatter, the dream text speed and skipping
            "sotf_convoChance", "sotf_dreamSpeed", "sotf_fastDreams",
    };

    /** The name net's needles, matched against the lower-cased fieldId ({@code hotkey} is covered by {@code key}). */
    private static final String[] NAME_NET = {"key", "bind", "holdctrl", "holdshift", "holdalt"};

    static {
        if (MOD.length != FIELD.length) {
            throw new IllegalStateException("LunaSyncExclusions table skew " + MOD.length + " vs " + FIELD.length);
        }
    }

    private LunaSyncExclusions() {
    }

    /** True when the row never rides the sync: a whole-mod entry, a table row or a name-net hit. Null = excluded. */
    public static boolean excluded(String modId, String fieldId) {
        if (modId == null || fieldId == null) {
            return true;
        }
        if (excludedMod(modId)) {
            return true;
        }
        for (int i = 0; i < MOD.length; i++) {
            if (MOD[i].equals(modId) && FIELD[i].equals(fieldId)) {
                return true;
            }
        }
        return keyLike(fieldId);
    }

    /** True for {@code lunalib}, {@code cc_client} and {@code shaderLib}: no row of theirs ever rides. */
    public static boolean excludedMod(String modId) {
        for (String m : EXCLUDED_MODS) {
            if (m.equals(modId)) {
                return true;
            }
        }
        return false;
    }

    /** The name safety net: {@code (?i)key|bind|hotkey|hold(ctrl|shift|alt)}, hand-rolled. */
    public static boolean keyLike(String fieldId) {
        if (fieldId == null) {
            return false;
        }
        String lower = fieldId.toLowerCase(Locale.ROOT);
        for (String needle : NAME_NET) {
            if (lower.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** The table's row count (a contract row pins it against the CSV sweep). */
    public static int tableSize() {
        return MOD.length;
    }

    /**
     * The table's {@code i}-th modId, {@code i} in 0..{@link #tableSize()}-1 (outside = the array's own
     * IndexOutOfBounds): the contract row checks every (modId, fieldId) pair against that mod's own CSV, so a
     * misspelt id — which would exclude nothing — fails loudly.
     */
    public static String tableModId(int i) {
        return MOD[i];
    }

    /** The table's {@code i}-th fieldId, paired with {@link #tableModId}. */
    public static String tableFieldId(int i) {
        return FIELD[i];
    }

    /** The whole-mod count ({@link #excludedMod}). */
    public static int excludedModCount() {
        return EXCLUDED_MODS.length;
    }
}
