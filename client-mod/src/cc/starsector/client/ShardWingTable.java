package cc.starsector.client;

import com.fs.starfarer.api.impl.hullmods.ShardSpawner;
import com.fs.starfarer.api.util.WeightedRandomPicker;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The omega shard-WING filter over vanilla's own split table.
 *
 *
 * <p>Vanilla's shard split picks each child id from {@code ShardSpawner.variantData}
 * (a public static map filled by {@code <clinit>} with no
 * {@code Global} touch) and spawns an id ending in {@code _wing} as a fighter WING.
 * {@link #wingIds} returns exactly those ids, so {@code FractureFamilyCoordinator} can tell a
 * shard-wing child from a bay fighter / drone / threat wing binding near an omega hulk.
 *
 * <p>Pure and read-only: it walks the {@code variants} MAP's values (never
 * {@code ShardTypeVariants.get()}, which INSERTS a missing type) and each picker's
 * {@code getItems()} (the LIVE list) without writing
 * either. No {@code Global}, no engine: loads with the api + json + lwjgl_util jars only, so the
 * selftest runs headless.
 */
final class ShardWingTable {

    private ShardWingTable() {
    }

    /** Every {@code _wing} id in {@code table} (vanilla's own name-suffix test),
     *  sorted (deterministic log rows). Null maps / entries / items are skipped. */
    static Set<String> wingIds(Map<?, ShardSpawner.ShardTypeVariants> table) {
        TreeSet<String> out = new TreeSet<String>();
        if (table == null) {
            return out;
        }
        for (ShardSpawner.ShardTypeVariants t : table.values()) {
            if (t == null || t.variants == null) {
                continue;
            }
            for (WeightedRandomPicker<String> p : t.variants.values()) {
                List<String> items = p == null ? null : p.getItems();
                if (items == null) {
                    continue;
                }
                for (int i = 0; i < items.size(); i++) {
                    String id = items.get(i);
                    if (id != null && id.endsWith("_wing")) {
                        out.add(id);
                    }
                }
            }
        }
        return out;
    }
}
