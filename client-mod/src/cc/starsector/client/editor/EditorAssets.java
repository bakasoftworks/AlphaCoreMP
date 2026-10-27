package cc.starsector.client.editor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Set;

import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

/**
 * The editor's BINARY-ASSET lane — the sprite / font / sound bytes streamed read-only from the
 * install through the B-legal {@code file:} URL lane, never copied into the jar or the repo
 *
 * (moved out of {@link EditorOracle} unchanged).
 */
final class EditorAssets {
    static final int MAX_SPRITE_BYTES = 8 * 1024 * 1024;   // shared with EditorCodex's sprite lanes

    private EditorAssets() {
    }

    /** Sprite bytes via the B-legal URL lane; null when unreadable. */
    static byte[] spritePng(ShipHullSpecAPI spec) {
        String rel = spec.getSpriteName();
        if (rel == null || rel.isEmpty() || rel.contains("..") || rel.startsWith("/")) {
            return null;
        }
        return readGameFile(rel, MAX_SPRITE_BYTES);
    }

    /**
     * A weapon's sprite bytes: {@code turret}/{@code hardpoint} (+ {@code _under}) come from the public
     * {@code WeaponSpecAPI} getters; {@code gun}/{@code glow} (the recoil / energy overlays the refit
     * composites on top) are NOT exposed by the API, so they are read from the weapon's own
     * {@code data/weapons/<id>.wpn} through {@code SettingsAPI.loadJSON} (comment-tolerant, public).
     * Null when the weapon, the kind or the file is unknown.
     */
    static byte[] weaponSpritePng(String id, String kind) {
        WeaponSpecAPI spec;
        try {
            spec = Global.getSettings().getWeaponSpec(id);
        } catch (RuntimeException missing) {
            return null;
        }
        if (spec == null) {
            return null;
        }
        String rel = null;
        if ("turret".equals(kind)) {
            rel = spec.getTurretSpriteName();
        } else if ("hardpoint".equals(kind)) {
            // Vanilla falls an EMPTY hardpoint sprite back to the turret sprite (the Onslaught's tpc
            // mounts on hardpoints with "hardpointSprite":"" — the SPA drew its grey fallback disc at the LARGE radius)
            rel = spec.getHardpointSpriteName();
            if (!safeSpritePath(rel)) {
                rel = spec.getTurretSpriteName();
            }
        } else if ("turret_under".equals(kind)) {
            rel = spec.getTurretUnderSpriteName();
        } else if ("hardpoint_under".equals(kind)) {
            rel = spec.getHardpointUnderSpriteName();
        } else if ("gun".equals(kind) || "glow".equals(kind)) {
            rel = weaponOverlayPath(id, "gun".equals(kind) ? "turretGunSprite" : "turretGlowSprite");
        } else if ("hardpoint_gun".equals(kind) || "hardpoint_glow".equals(kind)) {
            // the hardpoint barrel/glow layers (drawn on the same h/4 pivot as the base)
            rel = weaponOverlayPath(id, "hardpoint_gun".equals(kind) ? "hardpointGunSprite" : "hardpointGlowSprite");
        }
        return safeSpritePath(rel) ? readGameFile(rel, MAX_SPRITE_BYTES) : null;
    }

    private static String weaponOverlayPath(String id, String key) {
        try {
            JSONObject wpn = Global.getSettings().loadJSON("data/weapons/" + id + ".wpn");
            return wpn == null ? null : wpn.optString(key, null);
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** A hullmod's picker icon ({@code hull_mods.csv} sprite column); null when unknown. */
    static byte[] hullmodSpritePng(String id) {
        HullModSpecAPI spec;
        try {
            spec = Global.getSettings().getHullModSpec(id);
        } catch (RuntimeException missing) {
            return null;
        }
        String rel = spec == null ? null : spec.getSpriteName();
        return safeSpritePath(rel) ? readGameFile(rel, MAX_SPRITE_BYTES) : null;
    }

    /** A wing's icon = its fighter hull's top-down sprite (the API has no dedicated wing icon); null when unknown. */
    static byte[] wingSpritePng(String id) {
        FighterWingSpecAPI spec;
        try {
            spec = Global.getSettings().getFighterWingSpec(id);
        } catch (RuntimeException missing) {
            return null;
        }
        String rel = null;
        try {
            rel = spec == null || spec.getVariant() == null || spec.getVariant().getHullSpec() == null ? null
                    : spec.getVariant().getHullSpec().getSpriteName();
        } catch (RuntimeException odd) {
            rel = null;
        }
        return safeSpritePath(rel) ? readGameFile(rel, MAX_SPRITE_BYTES) : null;
    }

    /** The game's own TrueType faces the SPA may use (streamed from the install, never shipped by the mod). */
    static final Set<String> GAME_FONTS = Set.of("orbitron-bold", "orbitron-light", "orbitron-black");

    /** {@code graphics/fonts/<name>.ttf} bytes for an allowlisted name; null otherwise. */
    static byte[] fontTtf(String name) {
        if (name == null || !GAME_FONTS.contains(name)) {
            return null;
        }
        return readGameFile("graphics/fonts/" + name + ".ttf", 2 * 1024 * 1024);
    }

    /**
     * Sound effects like the game's own variant editor has:
     * the refit screen's own cues the SPA plays — a STATIC id → file map (the vanilla
     * ids of {@code data/config/sounds.json}, triggers and files RE'd),
     * streamed read-only from the install by
     * {@code GET sound/<id>}. Never copied into the jar or the repo (the game's own assets); a request
     * names an id, never a path; anything off the map is a 404.
     */
    static final java.util.Map<String, String> SOUNDS = java.util.Map.ofEntries(
            java.util.Map.entry("ui_refit_slot_filled_ballistic_small", "sounds/sfx_refit/refit_ballistic_01.ogg"),
            java.util.Map.entry("ui_refit_slot_filled_ballistic_medium", "sounds/sfx_refit/refit_ballistic_02.ogg"),
            java.util.Map.entry("ui_refit_slot_filled_ballistic_large", "sounds/sfx_refit/refit_ballistic_03.ogg"),
            java.util.Map.entry("ui_refit_slot_filled_energy_small", "sounds/sfx_refit/refit_energy_01.ogg"),
            java.util.Map.entry("ui_refit_slot_filled_energy_medium", "sounds/sfx_refit/refit_energy_02.ogg"),
            java.util.Map.entry("ui_refit_slot_filled_energy_large", "sounds/sfx_refit/refit_energy_03.ogg"),
            java.util.Map.entry("ui_refit_slot_filled_missile_small", "sounds/sfx_refit/refit_missile_01.ogg"),
            java.util.Map.entry("ui_refit_slot_filled_missile_medium", "sounds/sfx_refit/refit_missile_02.ogg"),
            java.util.Map.entry("ui_refit_slot_filled_missile_large", "sounds/sfx_refit/refit_missile_03.ogg"),
            java.util.Map.entry("ui_refit_slot_cleared_small", "sounds/sfx_refit/refit_clear_01.ogg"),
            java.util.Map.entry("ui_refit_slot_cleared_medium", "sounds/sfx_refit/refit_clear_01.ogg"),
            java.util.Map.entry("ui_refit_slot_cleared_large", "sounds/sfx_refit/refit_clear_01.ogg"),
            java.util.Map.entry("ui_refit_slot_filled_fighter", "sounds/sfx_refit/refit_fighter_02_install.ogg"),
            java.util.Map.entry("ui_refit_slot_cleared_fighter", "sounds/sfx_refit/refit_fighter_01_clear.ogg"),
            java.util.Map.entry("ui_button_pressed", "sounds/sfx_interface/ui_button_pressed.ogg"),
            java.util.Map.entry("ui_button_mouseover", "sounds/sfx_interface/ui_button_mouseover.ogg"),
            java.util.Map.entry("ui_button_disabled_pressed", "sounds/sfx_interface/ui_button_disabled_pressed.ogg"),
            java.util.Map.entry("ui_char_spent_story_point_technology", "sounds/sfx_charscreen/story_point_use_tech.ogg"));
    /** The largest mapped cue is 65 KB; the cap is the read law, not a budget. */
    private static final int MAX_SOUND_BYTES = 256 * 1024;

    /** The Ogg bytes of a mapped cue id; null for anything off the map or unreadable. */
    static byte[] soundOgg(String id) {
        String rel = id == null ? null : SOUNDS.get(id);
        return rel == null ? null : readGameFile(rel, MAX_SOUND_BYTES);
    }

    static boolean safeSpritePath(String rel) {
        return rel != null && !rel.isEmpty() && !rel.contains("..") && !rel.startsWith("/") && rel.endsWith(".png");
    }

    /**
     * A game data file by the B-legal {@code file:} URL lane: an API path that is already absolute
     * (drive letter, the {@code getShipFilePath()} shape) is opened as-is; a data-relative one is
     * tried under the core, this mod and the install root. Null when no base has it.
     */
    static byte[] readGameFile(String path, int cap) {
        String p = path.replace('\\', '/');
        String[] candidates;
        if (p.length() > 2 && p.charAt(1) == ':') {
            candidates = new String[] { p };
        } else {
            String root = System.getProperty("user.dir").replace('\\', '/');
            candidates = new String[] { root + "/starsector-core/" + p, root + "/mods/CC_Client/" + p, root + "/" + p };
        }
        for (String full : candidates) {
            try {
                InputStream in = new URL("file:/" + full).openStream();
                try {
                    return EditorTier.readAll(in, cap);
                } finally {
                    in.close();
                }
            } catch (IOException next) {
                // try the next base
            } catch (RuntimeException bad) {
                return null;
            }
        }
        return null;
    }
}
