package cc.starsector.server.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import cc.starsector.server.adapter.CombatEntityView;
import cc.starsector.server.adapter.ShipPhaseCloakView;
import cc.starsector.server.protocol.GameplayEdgeEvent;
import cc.starsector.server.protocol.GameplayEdgeEventKind;

/**
 * Canister-flak MEASURE instrument, server half (measure-first
 * — no fix rides this class). The chain to test: the invictus
 * defense system {@code canister_flak} lives in the engine's {@code getPhaseCloak()} FIELD; the
 * serve presses it only on a bit-11 mismatch edge, which a zero-duration WEAPON system
 * (near-)never raises; foe/AI hulls press it themselves but their ordnance has no presentation
 * path (AWSS PROJECTILE kind discarded client-side; GPEE WEAPON_DISCHARGE is mount-keyed and the
 * hidden system weapon is absent from {@code getAllWeapons()}). Rows this class emits:
 * <ul>
 *   <li>EDGE lines per canister-cloak hull on state/ammo/cooldown-window change (the seat hull
 *       under owner right-click spam should show edges IF the press lane works; foe AI hulls
 *       pressing themselves are the in-watch positive control);</li>
 *   <li>BIRTH lines when live {@code canister_flak_proj} count rises, carrying the SAME tick's
 *       raw WEAPON_DISCHARGE count — births with zero discharges = the mount-identity
 *       break confirmed;</li>
 *   <li>a periodic census row (cloak roster + cumulative counters).</li>
 * </ul>
 * Log-only (stdout write-only sink); single-line rows; edge/birth lines capped, counters continue.
 *
 * Own-file per the PersistentPilotIntentCombatServer growth-anchor law:
 * PPICS carries only the field + tick delegate call.
 */
final class CanisterFlakServeProbe {

    /** Census cadence in sim ticks (~5s at 60/s) — the serve-side meter idiom. */
    private static final int CADENCE_TICKS = 300;
    /**
     * Projectile-sweep sampling stride: the entity views reflect over EVERY live body, so the
     * probe pays them at ~12 Hz, not per tick. Births stay a LOWER bound (a canister born and
     * despawned inside one stride goes uncounted — canister flight times are ≥0.25× a
     * multi-second base, far above 5 ticks). The sweep covers BOTH views —
     * canister rounds live on the engine MISSILE list, and {@code projectileEntitiesOrNull()}'s
     * F1-dedupe identity-SKIPS every body also on it (EngineCombatBinding readPointEntities),
     * so a projectile-only sweep counted births_min=0 BY CONSTRUCTION (the blind spot).
     * Null sweeps (fail-soft view) are counted apart from empty — the second defect.
     */
    private static final int PROJ_SAMPLE_TICKS = 5;
    /** Cap on EDGE+BIRTH lines per run (spam safety); counters continue past it. */
    private static final int EDGE_LOG_MAX = 80;
    private static final String CLOAK_ID = "canister_flak";
    private static final String PROJ_KEY = "canister_flak_proj";
    private static final int CENSUS_ROSTER_MAX = 12;

    private final Map<Long, String> lastSig = new HashMap<>();
    private int edgeLines;
    private int canisterEdges;
    private int lastLive = -1;
    private int liveNow;
    private int projLiveNow;
    private int mslLiveNow;
    private int birthsMin;
    private int projNullSamples;
    private int mslNullSamples;
    private long discharges;
    private int dischargesSinceSample;

    void tick(long simTick, List<ShipPhaseCloakView> cloaks,
            Supplier<List<CombatEntityView>> projectiles,
            Supplier<List<CombatEntityView>> missiles, List<GameplayEdgeEvent> tickEdges) {
        try {
            int dischargesThisTick = 0;
            if (tickEdges != null) {
                for (GameplayEdgeEvent e : tickEdges) {
                    if (e != null && e.kind() == GameplayEdgeEventKind.WEAPON_DISCHARGE) {
                        dischargesThisTick++;
                    }
                }
            }
            discharges += dischargesThisTick;
            dischargesSinceSample += dischargesThisTick;

            if (simTick % PROJ_SAMPLE_TICKS == 0) {
                List<CombatEntityView> projView = projectiles.get();
                List<CombatEntityView> mslView = missiles.get();
                if (projView == null) {
                    projNullSamples++;
                }
                if (mslView == null) {
                    mslNullSamples++;
                }
                projLiveNow = countCanisters(projView);
                mslLiveNow = countCanisters(mslView);
                // Views are disjoint by construction (the F1-dedupe removes missile-list
                // members from the projectile view), so the total is a plain sum.
                int live = projLiveNow + mslLiveNow;
                if (lastLive >= 0 && live > lastLive) {
                    birthsMin += live - lastLive;
                    if (edgeLines < EDGE_LOG_MAX) {
                        edgeLines++;
                        System.out.println("canister-serve BIRTH tick=" + simTick
                                + " live=" + lastLive + "->" + live
                                + " (proj=" + projLiveNow + " msl=" + mslLiveNow + ")"
                                + " discharges_since_sample=" + dischargesSinceSample
                                + " births_min=" + birthsMin);
                    }
                }
                lastLive = live;
                liveNow = live;
                dischargesSinceSample = 0;
            }

            if (cloaks != null) {
                for (ShipPhaseCloakView c : cloaks) {
                    if (c == null || !CLOAK_ID.equals(c.cloakId())) {
                        continue;
                    }
                    // Cooldown folded to a boolean window: cooldownRemaining counts down every
                    // tick and would mint a per-tick "edge" otherwise.
                    String sig = c.state() + "|" + c.ammo() + "|" + (c.cooldownRemaining() > 0f);
                    String prev = lastSig.put(c.shipId(), sig);
                    if (prev != null && !prev.equals(sig)) {
                        canisterEdges++;
                        if (edgeLines < EDGE_LOG_MAX) {
                            edgeLines++;
                            System.out.println(String.format(Locale.ROOT,
                                    "canister-serve EDGE tick=%d ship=%d state=%s ammo=%d cd=%.2f on=%b edges=%d%s",
                                    simTick, c.shipId(), c.state(), c.ammo(),
                                    c.cooldownRemaining(), c.on(), canisterEdges,
                                    edgeLines == EDGE_LOG_MAX
                                            ? " [edge log cap reached; counters continue]" : ""));
                        }
                    }
                }
            }

            if (simTick % CADENCE_TICKS == 0) {
                StringBuilder roster = new StringBuilder();
                int listed = 0;
                int total = 0;
                if (cloaks != null) {
                    for (ShipPhaseCloakView c : cloaks) {
                        if (c == null) {
                            continue;
                        }
                        total++;
                        if (listed < CENSUS_ROSTER_MAX) {
                            listed++;
                            if (roster.length() > 0) {
                                roster.append(',');
                            }
                            roster.append(c.shipId()).append(':').append(c.cloakId())
                                    .append(':').append(c.state()).append(":a=").append(c.ammo());
                        }
                    }
                }
                if (total > listed) {
                    roster.append(",+").append(total - listed);
                }
                System.out.println("canister-serve probe tick=" + simTick
                        + " cloaks=[" + roster + "]"
                        + " canister_edges=" + canisterEdges
                        + " proj_live=" + liveNow
                        + " proj_view_live=" + projLiveNow + " msl_view_live=" + mslLiveNow
                        + " proj_births_min=" + birthsMin
                        + " proj_null_samples=" + projNullSamples
                        + " msl_null_samples=" + mslNullSamples
                        + " discharges_total=" + discharges);
            }
        } catch (RuntimeException ignore) {
            // instrument is read-only; never let it break the serve loop
        }
    }

    /** Canister count in one entity view; {@code null} (fail-soft sweep) counts 0 here and is
     *  tallied separately by the caller — a null view must never read as "empty engine". */
    private static int countCanisters(List<CombatEntityView> entities) {
        if (entities == null) {
            return 0;
        }
        int live = 0;
        for (CombatEntityView v : entities) {
            if (v != null && PROJ_KEY.equals(v.contentKey())) {
                live++;
            }
        }
        return live;
    }
}
