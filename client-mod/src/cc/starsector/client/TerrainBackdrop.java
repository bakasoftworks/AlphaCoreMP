package cc.starsector.client;

import cc.starsector.server.match.MatchDescriptor;

import com.fs.starfarer.api.mission.MissionDefinitionAPI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The descriptor's optional {@code map.terrain} backdrop word, authored as the vanilla
 * campaign-battle nebula/hyperspace calls on the {@link MissionDefinitionAPI}.
 *
 *
 * <p><b>WHY a port, not a new effect:</b> the blob loop is vanilla's own
 * {@code BattleCreationPluginImpl.createMap}:
 * N = 15 blobs, 100 inside a
 * nebula, 0 in hyperspace; per blob {@code x}, {@code y} uniform over the map, then
 * {@code radius = 100 + 400*U} (+ {@code 100 + 500*U} inside a nebula), all from ONE seeded
 * {@link Random} in that exact draw order. The textures are the {@code settings.json}
 * {@code graphics.terrain} pairs ({@code nebula_amber}/{@code nebula_amber_map},
 * {@code nebula_blue}/{@code nebula_blue_map}); vanilla sets the
 * background sprite + {@code setHyperspaceMode}, and the hyperspace background is
 * {@code backgrounds.defaultHyperBackground}.
 *
 *
 * <p><b>Presentation only:</b> every lever is DEFINE-time and the nebula tile grid has no SIM
 * reader under vanilla settings, so a per-seat backdrop cannot
 * desync the match. The seed is client-derived from the descriptor every seat already decodes
 * (client-side at equal quality, zero wire).
 *
 * <p><b>Game-free:</b> no {@code Global}, no engine call beyond the handed api, and never a
 * boolean/primitive-returning api method ({@code hasNebula()}) — the verify harness proxy
 * returns {@code null} for every call (unboxing NPE). Runs OUTSIDE
 * {@code MatchMissionBuilder.build()} so the V5/V6 golden call lists stay byte-identical.
 */
public final class TerrainBackdrop {

    /** {@code settings.json graphics.terrain.nebula_amber}. */
    public static final String NEBULA_TEX_AMBER = "graphics/terrain/nebula_amber.png";
    /** {@code settings.json graphics.terrain.nebula_amber_map}. */
    public static final String NEBULA_MAP_AMBER = "graphics/terrain/nebula_amber_map.png";
    /** {@code settings.json graphics.terrain.nebula_blue}. */
    public static final String NEBULA_TEX_BLUE = "graphics/terrain/nebula512_blue.png";
    /** {@code settings.json graphics.terrain.nebula_blue_map}. */
    public static final String NEBULA_MAP_BLUE = "graphics/terrain/nebula_blue_map.png";
    /** {@code settings.json graphics.backgrounds.defaultHyperBackground} — the campaign's own
     *  hyperspace background. */
    public static final String HYPER_BACKGROUND = "graphics/backgrounds/hyperspace_bg_cool.jpg";

    private TerrainBackdrop() {
    }

    /**
     * The blob seed: {@code d.matchId().hashCode()}. {@code String.hashCode} is specified by the
     * JLS/JDK contract and every seat decodes the SAME kit descriptor bytes, so every seat draws
     * the identical blob list with zero wire (client-derived at equal quality; the
     * backdrop is cosmetic only).
     */
    public static long seed(MatchDescriptor d) {
        return d.matchId().hashCode();
    }

    /**
     * Vanilla's blob count: {@code none}/{@code hyper}
     * ⇒ 0, {@code open} ⇒ 15, {@code nebula}/{@code nebula_blue} ⇒ 100. Any other word throws —
     * the descriptor ctor already guarantees the vocabulary; this is the belt.
     *
     */
    public static int blobCount(String word) {
        if (MatchDescriptor.TERRAIN_NONE.equals(word) || "hyper".equals(word)) {
            return 0;
        }
        if ("open".equals(word)) {
            return 15;
        }
        if (insideNebula(word)) {
            return 100;
        }
        throw new IllegalArgumentException("unknown terrain word: '" + word + "'");
    }

    /** Whether the word is an inside-a-nebula backdrop (the vanilla {@code inNebula} branch).
     * */
    public static boolean insideNebula(String word) {
        return "nebula".equals(word) || "nebula_blue".equals(word);
    }

    /**
     * The {@code {x, y, radius}} blob triples in vanilla's exact draw order
     * from one {@code Random(seed)} over a
     * {@code 2*halfWidth} x {@code 2*halfHeight} map. Unmodifiable.
     *
     */
    public static List<float[]> blobs(String word, long seed, float halfWidth, float halfHeight) {
        int n = blobCount(word);
        boolean inNebula = insideNebula(word);
        Random r = new Random(seed);
        float width = 2f * halfWidth;
        float height = 2f * halfHeight;
        List<float[]> out = new ArrayList<float[]>(n);
        for (int i = 0; i < n; i++) {
            float x = r.nextFloat() * width - width / 2f;
            float y = r.nextFloat() * height - height / 2f;
            float radius = 100f + r.nextFloat() * 400f;
            if (inNebula) {
                radius += 100f + 500f * r.nextFloat();
            }
            out.add(new float[] {x, y, radius});
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Author the descriptor's backdrop on the mission api: {@code none} ⇒ ZERO api calls;
     * {@code nebula}/{@code nebula_blue} ⇒ the texture pair; {@code hyper} ⇒ hyperspace mode +
     * the hyperspace background; then one {@code addNebula} per blob, in order. One
     * {@link PilotWatchLog} info line (the debrief ride-along) unless {@code none}.
     *
     */
    public static void apply(MissionDefinitionAPI api, MatchDescriptor d) {
        String word = d.terrain();
        if (MatchDescriptor.TERRAIN_NONE.equals(word)) {
            return;
        }
        if ("nebula".equals(word)) {
            api.setNebulaTex(NEBULA_TEX_AMBER);
            api.setNebulaMapTex(NEBULA_MAP_AMBER);
        } else if ("nebula_blue".equals(word)) {
            api.setNebulaTex(NEBULA_TEX_BLUE);
            api.setNebulaMapTex(NEBULA_MAP_BLUE);
        } else if ("hyper".equals(word)) {
            api.setHyperspaceMode(true);
            api.setBackgroundSpriteName(HYPER_BACKGROUND);
        }
        long seed = seed(d);
        List<float[]> list = blobs(word, seed, d.halfWidth(), d.halfHeight());
        for (float[] b : list) {
            api.addNebula(b[0], b[1], b[2]);
        }
        PilotWatchLog.info("terrain backdrop: word=" + word + " blobs=" + list.size()
                + " seed=" + seed);
    }
}
