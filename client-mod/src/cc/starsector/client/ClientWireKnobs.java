package cc.starsector.client;

import cc.starsector.client.net.ClientPostureBlock;
import cc.starsector.server.runtime.WireKnob;
import java.util.ArrayList;
import java.util.List;

/**
 * The client's LIVE wire-delivered behavior knobs (lane-posture design P4 "wire wins over the
 * compiled default"; the {@link MatchConfigFromWire} volatile shape — B-legal: no file reads, no
 * reflection). Seeded from EVERY {@code PSTB} posture block the session drains
 * ({@code PredictReconcileCombatPlugin#drainPostureBlock}): the once-per-admission spool AND the
 * mid-match re-spool after an operator {@code /knob} flip (the owner's A/B
 * ruling: both branches on one seam, flipped mid-flight so both can be felt). Consumers read
 * LIVE per use (one volatile read at most per frame per twin). A schema-2 block always carries
 * the WHOLE {@link WireKnob} census, so an apply is a full re-seed and needs no absent-knob law.
 */
public final class ClientWireKnobs {

    /** {@link WireKnob#FOE_CLOAK_LEAD} value 0: branch A — keep the locally-begun overload through a grace. */
    public static final int FOE_CLOAK_LEAD = 0;
    /** {@link WireKnob#FOE_CLOAK_LEAD} value 1: branch B — stop it and force the cloak back to the wire's state. */
    public static final int FOE_CLOAK_WAIT = 1;

    private static volatile int foeCloakLead = FOE_CLOAK_LEAD;

    private ClientWireKnobs() {
    }

    /**
     * true = branch B ({@code wait}): the fluxflags lane stops a locally-begun
     * overload at once and re-forces the cloak to the wire's state; false = branch A
     * ({@code lead}, default): the local overload is kept for the lead grace.
     */
    public static boolean foeCloakWait() {
        return foeCloakLead == FOE_CLOAK_WAIT;
    }

    /** The live {@link WireKnob#FOE_CLOAK_LEAD} value (log rows / the smoke's assertions). */
    public static int foeCloakLeadValue() {
        return foeCloakLead;
    }

    /** Game-free seam for the smoke; the live path is {@link #applyBlock}. */
    static void setFoeCloakLead(int value) {
        foeCloakLead = value;
    }

    // ---- the battle terrain pair --------------------------------------

    private static volatile int terrain = WireKnob.TERRAIN.defaultChoice();
    private static volatile int terrainLevel = WireKnob.TERRAIN_LEVEL.defaultChoice();
    /** Bumped on EVERY change of either terrain knob — the stat lane compares it against the
     *  epoch it last applied (one volatile read per frame), so a re-spool that changes nothing
     *  costs nothing and a real flip re-applies once (never per frame). */
    private static volatile int terrainEpoch;

    /** The live {@link WireKnob#TERRAIN} choice index ({@code none|corona|pulsar|abyss}). */
    public static int terrainValue() {
        return terrain;
    }

    /** The live {@link WireKnob#TERRAIN} word. */
    public static String terrainWord() {
        return WireKnob.TERRAIN.choiceName(terrain);
    }

    /** The live {@link WireKnob#TERRAIN_LEVEL} in percent ({@code 0..100}). */
    public static int terrainLevelPercent() {
        return WireKnob.TERRAIN_LEVEL.choicePercent(terrainLevel);
    }

    /** See {@link #terrainEpoch}. */
    public static int terrainEpoch() {
        return terrainEpoch;
    }

    /** Game-free seam for selftests; the live path is {@link #applyBlock}. */
    static void setTerrain(int terrainChoice, int levelChoice) {
        boolean changed = terrain != terrainChoice || terrainLevel != levelChoice;
        terrain = terrainChoice;
        terrainLevel = levelChoice;
        if (changed) {
            terrainEpoch++;
        }
    }

    // ---- the asteroid field size -------------------------------------
    //

    /** The live {@link WireKnob#ASTEROIDS} choice index ({@code none|20|100|400}). RECEIPT-ONLY:
     *  the asteroid lane consumes the server's ASTP rows, never this knob (the server keeper owns
     *  the count); it rides the PSTB dump so a flight's sidelog names the field in force. */
    private static volatile int asteroids = WireKnob.ASTEROIDS.defaultChoice();

    /** The live {@link WireKnob#ASTEROIDS} word. */
    public static String asteroidsWord() {
        return WireKnob.ASTEROIDS.choiceName(asteroids);
    }

    /** Game-free seam for selftests; the live path is {@link #applyBlock}. */
    static void setAsteroids(int value) {
        asteroids = value;
    }

    // ---- the AIMP fan interval the serve delivers ----------------

    /** The longest glide a delivered fan may set, in
     *  seconds: a remote-aim glide is never stretched past half a second whatever a server sends
     *  (the cap the retired learned cadence carried as its sample ceiling). */
    static final float AIM_GLIDE_MAX_S = 0.5f;

    /** The live {@link WireKnob#AIM_FAN_TICKS} value: the server's AIMP fan in sim ticks
     *  ({@code 1..255}); {@code 0} = OFF / unknown — also before the first PSTB block lands, and again from
     *  each admission's WELCOME until that admission's first PSTB ({@link #resetDeliveredCadenceAtAdmit}). */
    private static volatile int aimFanTicks = WireKnob.AIM_FAN_TICKS.defaultChoice();

    /**
     * The delivered fan interval in seconds, the
     * {@link RemoteAimLane} glide length. Computed at EVERY call from the live tick rate (the
     * WELCOME re-seeds {@link ServerTickRate} on every admit, the block re-spools the ticks), so
     * nothing is cached at {@link #applyBlock}. {@code 0f} = unknown (ticks 0) or past
     * {@link #AIM_GLIDE_MAX_S}: the caller applies the latest sample raw.
     */
    public static float aimFanSeconds() {
        int ticks = aimFanTicks;
        if (ticks <= 0) {
            return 0f;
        }
        float s = ticks / (float) ServerTickRate.ticksPerSecond();
        return s > AIM_GLIDE_MAX_S ? 0f : s;
    }

    /** The live {@link WireKnob#AIM_FAN_TICKS} value in ticks (log rows / the smoke's restore). */
    public static int aimFanTicksValue() {
        return aimFanTicks;
    }

    /** Game-free seam for selftests; the live path is {@link #applyBlock}. */
    static void setAimFanTicks(int ticks) {
        aimFanTicks = ticks;
    }

    // ---- the snapshot stride the serve delivers -------------------------

    /** The live {@link WireKnob#SNAP_STRIDE_TICKS} value: the serve's snapshot interval in sim ticks
     *  ({@code 1..255}); {@code 0} = unknown — also before the first PSTB block lands, and again from each
     *  admission's WELCOME until that admission's first PSTB ({@link #resetDeliveredCadenceAtAdmit}). */
    private static volatile int snapStrideTicks = WireKnob.SNAP_STRIDE_TICKS.defaultChoice();

    /**
     * The delivered snapshot stride in seconds, the
     * {@link WireTermAutofireShim} beam-terminus glide length. Computed at EVERY call from the live
     * tick rate (the WELCOME re-seeds {@link ServerTickRate} on every admit, the block re-spools the
     * ticks), so nothing is cached at {@link #applyBlock}. {@code 0f} = unknown (ticks 0): the caller
     * applies the newest terminus raw. NO cap here — the shim owns its {@code GLIDE_MAX_S}.
     * VALUE-DOMAIN: ticks are a u8 off the wire, {@code 0..255}; the rate is {@code 10..600} Hz, so the
     * division is safe and the result lies in {@code [0, 25.5]} s.
     */
    public static float snapStrideSeconds() {
        int ticks = snapStrideTicks;
        if (ticks <= 0) {
            return 0f;
        }
        return ticks / (float) ServerTickRate.ticksPerSecond();
    }

    /** The live {@link WireKnob#SNAP_STRIDE_TICKS} value in ticks (the shim's gap test, the rows' restore). */
    public static int snapStrideTicksValue() {
        return snapStrideTicks;
    }

    /** Game-free seam for selftests; the live path is {@link #applyBlock}. */
    static void setSnapStrideTicks(int ticks) {
        snapStrideTicks = ticks;
    }

    /**
     * BOTH delivered
     * cadences back to {@code 0} (unknown = raw) at every admission: the AIMP fan and the snapshot stride.
     * Called right after the WELCOME's tick-rate seed in {@code PredictDatagramDial.admit} (a first join, or a
     * new dial to another serve) and in {@code PredictReconcileDatagramSession.adoptWelcomeLocked} (every
     * re-admission and late-generation adopt). WHY (fan): {@link RemoteAimLane} reads the fan in
     * {@code PredictReconcileCombatPlugin.advance} BEFORE that frame's {@code drainPostureBlock}, so a fan
     * left from the previous serve would govern the new serve's glide and gap test until the new admission's
     * first PSTB is applied (lens fa F3). WHY (stride): likewise, from a new admission's WELCOME until
     * its first PSTB the previous serve's stride would govern the beam glide and gap test of a serve that may
     * run another one. {@code 0} is the cold path for both — the newest sample / terminus applies raw — so the
     * reset adds no delay. A match reset / round wrap / stale-feed resume keeps both (same serve,
     * no admit).
     */
    public static void resetDeliveredCadenceAtAdmit() {
        aimFanTicks = 0;
        snapStrideTicks = 0;
    }

    /**
     * Apply every knob the block carries (volatile writes) and return one sidelog row per knob
     * ({@code posture wire-knob key=... value=<word> id=<n>}) — the PSTB receipt's dump appends
     * them so a flight's sidelog names the branch in force at every (re-)spool.
     */
    public static List<String> applyBlock(ClientPostureBlock block) {
        List<String> rows = new ArrayList<String>();
        if (block == null) {
            return rows;
        }
        int[] pairs = block.knobPairs();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            WireKnob knob = WireKnob.byId(pairs[i]);
            int value = pairs[i + 1];
            if (knob == null) {
                continue; // the reader already refused unknown ids; belt and braces
            }
            switch (knob) {
                case FOE_CLOAK_LEAD:
                    foeCloakLead = value;
                    break;
                case TERRAIN: // Both rows land in one block; one epoch bump
                    if (terrain != value) {
                        terrain = value;
                        terrainEpoch++;
                    }
                    break;
                case TERRAIN_LEVEL:
                    if (terrainLevel != value) {
                        terrainLevel = value;
                        terrainEpoch++;
                    }
                    break;
                case ASTEROIDS: // receipt-only (the lane reads ASTP rows)
                    asteroids = value;
                    break;
                // The delivered AIMP fan in ticks (the remote-aim glide)
                case AIM_FAN_TICKS:
                    aimFanTicks = value;
                    break;
                case SNAP_STRIDE_TICKS: // The delivered snapshot stride (the beam glide)
                    snapStrideTicks = value;
                    break;
                default:
                    break;
            }
            rows.add("posture wire-knob key=" + knob.key() + " value=" + knob.choiceName(value)
                    + " id=" + knob.id());
        }
        return rows;
    }
}
