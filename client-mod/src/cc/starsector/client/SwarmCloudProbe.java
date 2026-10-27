package cc.starsector.client;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;

/**
 * The SWARM-CLOUD PROBE: a diagnostic-only lane that watches every freshly
 * minted swarm-fighter replica ({@code attack_swarm}, the {@link SwarmSpriteGate} predicate) for its
 * first {@link #WINDOW_FRAMES} frames and writes one row per {@link #EVERY_FRAMES} frames answering
 * the two open questions of that section: (a) does the {@code fragment_swarm} builtin's
 * {@code RoilingSwarmEffect} exist / hold members / reach brightness, and (b) where is the cloud
 * relative to the replica body, and how does the body's engine velocity compare with its
 * position-derived speed (the member chase cap is {@code max(362.5, |vel| + 100)},
 * vanilla's own). Row 0 ({@code MINT}) lands right after the engine's
 * {@code spawnFleetMember} returns and BEFORE the first-sight hard-snap, so its {@code drift} is the
 * displacement the engine's ten vanilla-AI pre-advances
 * (10 × 0.2 s) gave the body away from the requested wire pose.
 *
 * <p>Knob {@code cc.predict.swarmCloudProbe} = {@code off} (default, byte-for-byte dormant) |
 * {@code meter} (rows). Render-thread only; NEVER writes engine state; any throw disables the lane
 * for the round (one ERROR row). Row cap {@link #ROW_CAP} per session.
 */
final class SwarmCloudProbe {

    static final String KNOB = "cc.predict.swarmCloudProbe";
    static final boolean METER = "meter".equals(System.getProperty(KNOB, "off"))
            || "on".equals(System.getProperty(KNOB, "off"));

    /** TIME-based (the earlier 360-FRAME window read 0.9 s at the observer's ~440 fps). */
    static final float WINDOW_SEC = 3f;
    static final float EVERY_SEC = 0.05f;
    static final int ROW_CAP = 2400;
    static final int LIVE_CAP = 48;

    private static final class Entry {
        final ShipAPI ship;
        final long entityId;
        int frames;
        float elapsed;
        float sinceRow;
        final Vector2f lastLoc = new Vector2f();
        RoilingSwarmEffect lastSwarm;
        int generations;

        Entry(ShipAPI ship, long entityId) {
            this.ship = ship;
            this.entityId = entityId;
            this.lastLoc.set(ship.getLocation());
        }
    }

    private final List<Entry> live = new ArrayList<>();
    private final Map<ShipAPI, Boolean> seen = new IdentityHashMap<>();
    private int rows;
    private boolean disabled;

    /** Right after a successful replica mint (before the first-sight snap). Enrolls swarm fighters only. */
    void onMinted(ShipAPI ship, long entityId, float wantX, float wantY) {
        if (!METER || disabled || ship == null || seen.containsKey(ship)) {
            return;
        }
        try {
            ShipHullSpecAPI spec = ship.getHullSpec();
            if (spec == null || !SwarmSpriteGate.wantsHide(spec.getTags(), spec.getBaseHullId())) {
                return;
            }
            seen.put(ship, Boolean.TRUE);
            if (live.size() >= LIVE_CAP) {
                return;
            }
            Entry e = new Entry(ship, entityId);
            live.add(e);
            RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(ship);
            e.lastSwarm = swarm;
            Vector2f loc = ship.getLocation();
            float drift = (float) Math.hypot(loc.x - wantX, loc.y - wantY);
            row("predict swarm-cloud-probe MINT id=" + entityId + " on " + hex(ship)
                    + " drift=" + f1(drift) + " loc=(" + f1(loc.x) + "," + f1(loc.y) + ") want=(" + f1(wantX) + "," + f1(wantY)
                    + ") vel=" + f1(ship.getVelocity().length())
                    + " swarm=" + (swarm == null ? "none" : hex(swarm))
                    + " members=" + (swarm == null ? 0 : swarm.getMembers().size())
                    + " maxSpd=" + f1(ship.getMaxSpeedWithoutBoost())
                    + " hull=" + f2(ship.getHullLevel()) + " dnr=" + ship.isDoNotRender());
        } catch (Throwable t) {
            fail("onMinted", t);
        }
    }

    /** Per render frame from the plugin's advance relay. */
    void advance(CombatEngineAPI engine, float amount) {
        if (!METER || disabled || live.isEmpty() || amount <= 0f) {
            return;
        }
        try {
            for (int i = live.size() - 1; i >= 0; i--) {
                Entry e = live.get(i);
                e.frames++;
                e.sinceRow += amount;
                e.elapsed += amount;
                if (e.sinceRow >= EVERY_SEC) {
                    rowFor(engine, e);
                    e.lastLoc.set(e.ship.getLocation());
                    e.sinceRow = 0f;
                }
                if (e.elapsed >= WINDOW_SEC) {
                    live.remove(i);
                }
            }
        } catch (Throwable t) {
            fail("advance", t);
        }
    }

    private void rowFor(CombatEngineAPI engine, Entry e) {
        ShipAPI ship = e.ship;
        Vector2f loc = ship.getLocation();
        float posSpd = e.sinceRow > 0f
                ? (float) (Math.hypot(loc.x - e.lastLoc.x, loc.y - e.lastLoc.y) / e.sinceRow) : 0f;
        RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(ship);
        if (swarm != e.lastSwarm) {
            e.generations++;
            e.lastSwarm = swarm;
        }
        int members = 0;
        int active = 0;
        float brightSum = 0f;
        float cx = 0f;
        float cy = 0f;
        float spread = 0f;
        if (swarm != null) {
            List<RoilingSwarmEffect.SwarmMember> ms = swarm.getMembers();
            members = ms == null ? 0 : ms.size();
            active = swarm.getNumActiveMembers();
            if (members > 0) {
                for (RoilingSwarmEffect.SwarmMember m : ms) {
                    brightSum += m.fader == null ? 0f : m.fader.getBrightness();
                    cx += m.loc.x;
                    cy += m.loc.y;
                }
                cx /= members;
                cy /= members;
                for (RoilingSwarmEffect.SwarmMember m : ms) {
                    float dx = m.loc.x - cx;
                    float dy = m.loc.y - cy;
                    spread += dx * dx + dy * dy;
                }
                spread = (float) Math.sqrt(spread / members);
            }
        }
        float cloudOff = members > 0 ? (float) Math.hypot(cx - loc.x, cy - loc.y) : Float.NaN;
        row("predict swarm-cloud-probe id=" + e.entityId + " f=" + e.frames + " t=" + f2(e.elapsed)
                + " swarm=" + (swarm == null ? "none" : hex(swarm)) + " gen=" + e.generations
                + " members=" + members + " active=" + active
                + " bright=" + (members > 0 ? f2(brightSum / members) : "-")
                + " cloudOff=" + (members > 0 ? f1(cloudOff) : "-") + " spread=" + f1(spread)
                + " velSpd=" + f1(ship.getVelocity().length()) + " posSpd=" + f1(posSpd)
                + " alive=" + engine.isShipAlive(ship) + " inPlay=" + engine.isEntityInPlay(ship)
                + " hull=" + f2(ship.getHullLevel()) + " dnr=" + ship.isDoNotRender()
                + " loc=(" + f1(loc.x) + "," + f1(loc.y) + ")");
    }

    void resetRound() {
        live.clear();
        seen.clear();
    }

    String meterTokens() {
        return METER ? " predict_swarm_cloud_probe_rows=" + rows : "";
    }

    private void row(String text) {
        if (rows >= ROW_CAP) {
            if (rows == ROW_CAP) {
                rows++;
                PilotWatchLog.info("predict swarm-cloud-probe [row cap " + ROW_CAP + " reached; lane quiet]");
            }
            return;
        }
        rows++;
        PilotWatchLog.info(text);
    }

    private void fail(String where, Throwable t) {
        disabled = true;
        live.clear();
        PilotWatchLog.error("predict swarm-cloud-probe " + where + " FAILED -> lane disabled for this round", t);
    }

    private static String hex(Object o) {
        return Integer.toHexString(System.identityHashCode(o));
    }

    private static String f1(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static String f2(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
