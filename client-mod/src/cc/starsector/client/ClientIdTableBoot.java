package cc.starsector.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import cc.starsector.server.runtime.IdTables;
import cc.starsector.server.runtime.IdTablesJson;

/**
 * The client's boot ID TABLE — the
 * dictionary every VSUB body is encoded against and every VDEF body decoded against, plus the
 * {@code vtab} digest the HELLO content fingerprint FOLDS ({@code PredictDialPlanner
 * .selfComputeFingerprint}). Minted ONCE at the top of {@code onApplicationLoad} (every spec is
 * loaded before the plugin loop — the {@link BootVariantRegistry} idiom) and installed as the JVM
 * singleton ({@link IdTables#install}) the in-game callers reach ({@code IdTables.installedOrNull()}).
 *
 * <p>The universe MUST be the server's ({@code RealEngineVariantFacts.snapshot()}, the same
 * {@link SettingsAPI} enumerators, NO filter — the FULL loaded set, never a tier subset): every
 * {@code getAllShipHullSpecs()} hull with a non-empty id (last duplicate wins, as the server's
 * {@code LinkedHashMap.put}), its EVERY slot id in {@code getAllWeaponSlotsCopy()} order (built-in,
 * decorative, system, hidden, station-module and launch-bay slots included), its
 * {@code getBuiltInWeapons()} pairs (non-null key and value); every {@code getAllWeaponSpecs()} /
 * {@code getAllHullModSpecs()} / {@code getAllFighterWingSpecs()} id (SYSTEM-hinted weapons
 * INCLUDED). {@link IdTables#of} sorts and digests with the ONE recipe (the class itself
 * is bundled from the server tree — one law, never a mirror), so an equal universe yields an equal
 * {@code vtab}; a differing one is refused AT THE DOOR ({@code content_fingerprint_mismatch}).
 *
 * <p>The table is also CACHED for the game-free join page ({@code saves/common/cc_variants/
 * id-tables.json.data} via the public {@code writeTextFileToCommon} — loader-B-clean; the JSON
 * grammar is the bundled {@link IdTablesJson}): the joiner window's VCHK pre-admission dry-run
 * encodes its u16 bodies against the cache and presents the fingerprint folded over the cache's
 * digest, so a stale cache is a silent door drop ("checked at launch"), never a garbage verdict.
 *
 * <p>Failure posture: a mint fault installs NOTHING (loud ERROR line) — every VSUB/VDEF is then
 * refused locally and the HELLO print stays raw (an armed door refuses the session claim with
 * {@code raw_ok=true}, the readable diagnosis); a cache-write fault only costs the join page its
 * courtesy check.
 */
public final class ClientIdTableBoot {

    /** The common-file NAME the game resolves to {@code <saves>/common/<name>.data}. */
    public static final String CACHE_COMMON_NAME = "cc_variants/id-tables.json";

    private static volatile String lastDetail = "not minted";

    private ClientIdTableBoot() {
    }

    /** The FIRST statement after the boot-variant snapshot in {@code onApplicationLoad}; once per process. */
    public static void snapshotAtApplicationLoad() {
        if (IdTables.installedOrNull() != null) {
            return;
        }
        IdTables tables;
        try {
            tables = build(Global.getSettings());
            IdTables.install(tables);
        } catch (RuntimeException fault) {
            lastDetail = "mint FAILED: " + fault;
            PilotWatchLog.error("id-tables: boot mint FAILED — NO table installed: every VSUB/VDEF"
                    + " is refused locally and the HELLO print stays RAW (an armed door refuses the"
                    + " session claim: content_fingerprint_mismatch raw_ok=true)", fault);
            return;
        }
        lastDetail = tables.census();
        PilotWatchLog.info("id-tables: " + tables.census());
        try {
            Global.getSettings().writeTextFileToCommon(CACHE_COMMON_NAME, IdTablesJson.render(tables));
            PilotWatchLog.info("id-tables: cache written saves/common/" + CACHE_COMMON_NAME
                    + ".data (the join page's VCHK dictionary)");
        } catch (Throwable notCached) {
            PilotWatchLog.error("id-tables: cache write FAILED — the join page's customs pre-check"
                    + " reads 'checked at launch' until it lands", notCached);
        }
    }

    /** One line for the boot narration / the unfolded-print diagnosis. */
    public static String describe() {
        return lastDetail;
    }

    /** The server's enumeration, mirrored call for call (see the class note). */
    static IdTables build(SettingsAPI settings) {
        Map<String, List<String>> slots = new LinkedHashMap<String, List<String>>();
        Map<String, Map<String, String>> builtIns = new LinkedHashMap<String, Map<String, String>>();
        for (ShipHullSpecAPI h : settings.getAllShipHullSpecs()) {
            String id = h.getHullId();
            if (id == null || id.isEmpty()) {
                continue;
            }
            List<String> slotOrder = new ArrayList<String>();
            for (WeaponSlotAPI s : h.getAllWeaponSlotsCopy()) {
                String slotId = s.getId();
                if (slotId != null && !slotId.isEmpty()) {
                    slotOrder.add(slotId);
                }
            }
            Map<String, String> pairs = new TreeMap<String, String>();
            Map<String, String> biw = h.getBuiltInWeapons();
            if (biw != null) {
                for (Map.Entry<String, String> e : biw.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        pairs.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
            }
            // last duplicate wins (the server's LinkedHashMap.put over the same enumerator)
            slots.put(id, slotOrder);
            builtIns.put(id, pairs);
        }
        List<String> weapons = new ArrayList<String>();
        for (WeaponSpecAPI w : settings.getAllWeaponSpecs()) {
            String id = w.getWeaponId();
            if (id != null && !id.isEmpty()) {
                weapons.add(id);
            }
        }
        List<String> hullMods = new ArrayList<String>();
        for (HullModSpecAPI m : settings.getAllHullModSpecs()) {
            String id = m.getId();
            if (id != null && !id.isEmpty()) {
                hullMods.add(id);
            }
        }
        List<String> wings = new ArrayList<String>();
        for (FighterWingSpecAPI g : settings.getAllFighterWingSpecs()) {
            String id = g.getId();
            if (id != null && !id.isEmpty()) {
                wings.add(id);
            }
        }
        // IdTables.of refuses a DUPLICATE id; the server dedups by map key — mirror that here.
        return IdTables.of(slots.keySet(), dedup(weapons), dedup(hullMods), dedup(wings), slots, builtIns);
    }

    private static List<String> dedup(List<String> ids) {
        return new ArrayList<String>(new java.util.LinkedHashSet<String>(ids));
    }
}
