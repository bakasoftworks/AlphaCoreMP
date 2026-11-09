package cc.starsector.client;

import cc.starsector.server.match.ContentFingerprintClientSurface;
import cc.starsector.server.runtime.ModDataWalk;
import cc.starsector.server.runtime.ModSet;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModManagerAPI;
import com.fs.starfarer.api.ModSpecAPI;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The mod-set pin: the client's boot MOD SET — the FULL enabled set,
 * every mod but {@code cc_client}, read off the game's own {@link ModManagerAPI} (id, declared version,
 * md5 over the declared jars' bytes via the measured {@code FileReader} byte lane, and md5 over
 * {@code data/**} via the measured {@code java.nio.file.spi} walk lane,
 * {@link ModDataWalk}), digested by the ONE bundled recipe ({@link ModSet}) and installed as
 * the JVM singleton the HELLO print folds ({@code PredictDialPlanner.selfComputeFingerprint}:
 * {@code data = md5(dataMd5|vtab|modset)}). No enabled mod is dropped from the fold: the server
 * mints its required set from the launcher's enabled list ({@code RealEngineModSetFacts}) and its
 * door holds a lawful print for EVERY subset of the optional set it publishes, each minted from the
 * server's own optional records through the same {@link ModSet} recipe. So an optional mod the join
 * page staged is admitted, while a mod the server did not publish — or a same-id copy with another
 * version, jar or data — is refused at the door ({@code content_fingerprint_mismatch}, door log
 * {@code presented_fold=other}). WHY no client-side exclusion list: any list of ids to drop from the
 * fold is player-writable, so it could launder an unpublished mod past the door; only the server's
 * own table decides which subsets are lawful.
 *
 *
 * <p>Minted right after {@link ClientIdTableBoot} in {@code onApplicationLoad} (the launcher list
 * is complete before any plugin runs), once per process. Also CACHED for the game-free join page
 * ({@code saves/common/cc_variants/mod-set.txt.data} via {@code writeTextFileToCommon}, the
 * {@link ClientIdTableBoot} idiom): the joiner window's VCHK dry-run folds the cache's digest so a
 * stale cache is a silent door drop ("checked at launch"), never a garbage verdict.
 *
 * <p>Failure posture: a mint fault installs NOTHING (loud ERROR line) — the HELLO print then folds
 * vtab ONLY and an armed door refuses the session claim with {@code presented_fold=vtab_only}, the
 * readable diagnosis (a mod whose id breaks the {@code ModSet} id law, or an unreadable jar, lands
 * here — the fix is per mod). Catch types are
 * B-allowlisted names only ({@code IOException}, {@code RuntimeException}, {@code Throwable}).
 */
public final class ClientModSetBoot {

    private static volatile String lastDetail = "not minted";

    private ClientModSetBoot() {
    }

    /** Right after the id-table mint in {@code onApplicationLoad}; once per process. */
    public static void snapshotAtApplicationLoad() {
        if (ModSet.installedOrNull() != null) {
            return;
        }
        ModSet set;
        try {
            ModManagerAPI mods = Global.getSettings().getModManager();
            set = build(mods);
            ModSet.install(set);
        } catch (Throwable fault) {
            lastDetail = "mint FAILED: " + fault;
            PilotWatchLog.error("mod-set: boot mint FAILED — NO set installed: the HELLO print folds vtab"
                    + " ONLY (an armed door refuses the session claim: content_fingerprint_mismatch"
                    + " presented_fold=vtab_only)", fault);
            return;
        }
        lastDetail = set.census();
        PilotWatchLog.info("mod-set: " + set.census());
        try {
            Global.getSettings().writeTextFileToCommon(ModSet.CACHE_COMMON_NAME, set.text());
            PilotWatchLog.info("mod-set: cache written saves/common/" + ModSet.CACHE_COMMON_NAME
                    + ".data (the join page's VCHK fold input)");
        } catch (Throwable notCached) {
            PilotWatchLog.error("mod-set: cache write FAILED — the join page's customs pre-check reads"
                    + " 'checked at launch' until it lands", notCached);
        }
    }

    /** One line for the boot narration / the vtab-only-fold diagnosis. */
    public static String describe() {
        return lastDetail;
    }

    /** The server's enumeration mirrored: every enabled mod but the excluded one, jars in declaration order. */
    static ModSet build(ModManagerAPI mods) throws IOException {
        if (mods == null) {
            throw new IllegalStateException("getModManager() is null");
        }
        List<ModSet.Record> records = new ArrayList<ModSet.Record>();
        for (ModSpecAPI spec : mods.getEnabledModsCopy()) {
            String id = spec.getId();
            if (id == null || ModSet.EXCLUDED_ID.equals(id)) {
                continue;
            }
            String root = spec.getPath();
            List<byte[]> jarBytes = new ArrayList<byte[]>();
            List<String> jars = spec.getJars();
            if (jars != null) {
                for (String jar : jars) {
                    if (jar == null) {
                        continue;
                    }
                    // exact launcher composition (forward slash); an absolute jar path is honoured
                    boolean absolute = jar.indexOf(':') >= 0 || jar.startsWith("/") || jar.startsWith("\\");
                    jarBytes.add(ContentFingerprintClientSurface.readBytes(absolute ? jar : root + "/" + jar));
                }
            }
            // The data/** half — the in-game walk rides the spi lane B never names
            // (ModDataWalk); an unreadable file throws IOException = the mint fault above.
            records.add(ModSet.Record.of(id, spec.getVersion(), ModSet.jarsMd5(jarBytes), ModDataWalk.dataMd5(root)));
        }
        return ModSet.of(records);
    }
}
