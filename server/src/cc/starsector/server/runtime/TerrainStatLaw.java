package cc.starsector.server.runtime;

/**
 * Battle TERRAIN on the serve: the ONE pure law
 * both JVMs derive the terrain stat mults from — "one owner per quantity": the mults are a
 * deterministic function of ({@link WireKnob#TERRAIN} choice, {@link WireKnob#TERRAIN_LEVEL}
 * percent, the ship's own {@code corona_resistance}), so the server applies them on the
 * authoritative ships and every client derives the SAME numbers locally (own ship + replicas)
 * from the knob — no per-tick wire. Bundled into the mod jar like {@link WireKnob}
 * (B-legal by construction: floats in, floats out; no I/O, no reflection, no engine types).
 *
 * <p>Provenance: the api's
 * {@code StarCoronaTerrainPlugin.applyEffect}, byte-identical in
 * {@code PulsarBeamTerrainPlugin}: {@code pf = 1/max(1.3333, 1 + C*I)};
 * {@code peakMult = 1 - (1 - pf)*res}; {@code lossMult = 1 + C*I*res/2}, applied as keyed mults
 * on {@code PeakCRDuration} / {@code CRLossPerSecondPercent}. {@code C} = the star's
 * {@code crLossMult} ({@code star_gen_data.csv} col 19): {@code corona} = a yellow star 3,
 * {@code pulsar} = the neutron beam 15. Vanilla returns early at {@code intensity <= 0}
 * so level 0 = OFF (unmodify), never "I=0 through the formula".
 * Abyss ({@code BattleCreationPluginImpl}): {@code m = 1 - d*20*0.01} on
 * {@code MaxSpeed} / {@code MissileWeaponRangeBonus} / {@code MissileMaxSpeedBonus} under
 * vanilla's own id {@code "abyssal"}. Target set (both twins, {@link #coronaApplies}): the
 * corona family skips fighters (vanilla = campaign FleetMember buffs, fighters are not members);
 * abyss = every non-hulk ship, fighters included. Hulks are skipped by
 * the caller on both sides.
 */
public final class TerrainStatLaw {

    /** Keyed-mod ids — the SAME strings on both JVMs; {@code none} = {@code unmodify(key)}. */
    public static final String PEAK_KEY = "cc_terrain_peak";
    public static final String LOSS_KEY = "cc_terrain_crloss";
    /** Vanilla's own abyss mod id — kept so the parity walker reads one key. */
    public static final String ABYSS_KEY = "abyssal";

    /** {@code BattleCreationPluginImpl}: the abyss speed / missile penalty in percent at depth 1. */
    public static final float ABYSS_PENALTY_PERCENT = 20f;
    /** {@code BattleCreationPluginImpl}: the abyss glow overlay alpha at depth 1. */
    public static final float ABYSS_OVERLAY_ALPHA = 0.2f;
    /** {@code HyperspaceAbyssPluginImpl}: the starfield goes off strictly above this depth. */
    public static final float ABYSS_STARFIELD_OFF_DEPTH = 0.5f;
    /** {@code StarCoronaTerrainPlugin}: the peak-fraction floor. */
    static final float PEAK_FRACTION_FLOOR = 1.3333f;

    /** {@link WireKnob#TERRAIN} choice indexes (pinned by the enum's fixed table). */
    public static final int NONE = 0;
    public static final int CORONA = 1;
    public static final int PULSAR = 2;
    public static final int ABYSS = 3;

    private TerrainStatLaw() {
    }

    /** The star's {@code crLossMult} preset {@code C} for a corona-family choice; 0 otherwise. */
    public static float crLossMult(int terrainChoice) {
        switch (terrainChoice) {
            case CORONA:
                return 3f;   // star_yellow (star_gen_data.csv row 7)
            case PULSAR:
                return 15f;  // star_neutron -> pulsar_beam (row 13)
            default:
                return 0f;
        }
    }

    public static boolean isCoronaFamily(int terrainChoice) {
        return terrainChoice == CORONA || terrainChoice == PULSAR;
    }

    public static boolean isAbyss(int terrainChoice) {
        return terrainChoice == ABYSS;
    }

    /** {@code I} (corona intensity) or {@code d} (abyssal depth) from the level percent, clamped to [0,1]. */
    public static float fraction(int levelPercent) {
        if (levelPercent <= 0) {
            return 0f;
        }
        return levelPercent >= 100 ? 1f : levelPercent / 100f;
    }

    /** The corona family's target law: vanilla member buffs never reach fighters. */
    public static boolean coronaApplies(boolean isFighter) {
        return !isFighter;
    }

    /** The five keyed mults for one ship (all 1 when the terrain is off for that stat). */
    public static final class Mults {
        public final float peak;
        public final float loss;
        public final float speed;
        public final float missileRange;
        public final float missileSpeed;
        /** True when {@link #peak}/{@link #loss} are a real corona-family effect to INSTALL
         *  (else the caller unmodifies the two keys). */
        public final boolean coronaActive;
        /** True when the three abyss mults are to INSTALL (else the caller unmodifies). */
        public final boolean abyssActive;

        Mults(float peak, float loss, float speed, float missileRange, float missileSpeed,
                boolean coronaActive, boolean abyssActive) {
            this.peak = peak;
            this.loss = loss;
            this.speed = speed;
            this.missileRange = missileRange;
            this.missileSpeed = missileSpeed;
            this.coronaActive = coronaActive;
            this.abyssActive = abyssActive;
        }

        @Override
        public String toString() {
            return "peak=" + peak + " loss=" + loss + " speed=" + speed + " mrange=" + missileRange
                    + " mspeed=" + missileSpeed + " corona=" + coronaActive + " abyss=" + abyssActive;
        }
    }

    /**
     * The law. {@code coronaResistance} = the ship's own
     * {@code getMutableStats().getDynamic().getValue("corona_resistance")} (1.0 unset; Solar
     * Shielding 0.25 / 0 s-modded — already on the variant-built stats of BOTH JVMs), read at
     * apply time, never hard-coded. Both JVMs evaluate the same float arithmetic ⇒ identical mults.
     */
    public static Mults compute(int terrainChoice, int levelPercent, float coronaResistance) {
        float f = fraction(levelPercent);
        if (f <= 0f) {
            return OFF;
        }
        if (isCoronaFamily(terrainChoice)) {
            float c = crLossMult(terrainChoice);
            float res = Float.isFinite(coronaResistance) ? Math.max(0f, coronaResistance) : 1f;
            float pf = 1f / Math.max(PEAK_FRACTION_FLOOR, 1f + c * f);
            float peak = 1f - (1f - pf) * res;
            float loss = 1f + c * f * res / 2f;
            return new Mults(peak, loss, 1f, 1f, 1f, true, false);
        }
        if (isAbyss(terrainChoice)) {
            float m = 1f - f * ABYSS_PENALTY_PERCENT * 0.01f;
            return new Mults(1f, 1f, m, m, m, false, true);
        }
        return OFF;
    }

    private static final Mults OFF = new Mults(1f, 1f, 1f, 1f, 1f, false, false);

    // ---- presentation constants the CLIENT reads (pure data; the server never paints) ---------

    /** Glow alpha {@code 80*(0.4 + 0.6*I)} — floors at 32 for any corona. */
    public static int glowAlpha(int levelPercent) {
        return (int) (80f * (0.4f + 0.6f * fraction(levelPercent)));
    }

    /** {@code planets.json starCoronaColor}: yellow star {255,255,150}; neutron {170,190,255}; else white. */
    public static int[] glowRgb(int terrainChoice) {
        switch (terrainChoice) {
            case CORONA:
                return new int[] {255, 255, 150};
            case PULSAR:
                return new int[] {170, 190, 255};
            default:
                return new int[] {255, 255, 255};
        }
    }

    /** The vanilla status-row title. */
    public static String statusTitle(int terrainChoice) {
        switch (terrainChoice) {
            case CORONA:
                return "Corona";
            case PULSAR:
                return "Pulsar Beam";
            case ABYSS:
                return "Abyssal hyperspace";
            default:
                return "";
        }
    }
}
