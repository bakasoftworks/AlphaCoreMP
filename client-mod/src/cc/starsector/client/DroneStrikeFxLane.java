package cc.starsector.client;

import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The DRONE-STRIKE conversion FX collaborator, client half
 * ({@code MissilePoseLane}-owned, NEW-LANE LAW; zero PRCP touch by the margin-2 mandate).
 *
 * <p><b>Why the conversion missile is SOURCELESS on the wire.</b> Vanilla converts a terminator
 * drone via {@code DroneStrikeStats.convertDrones}: {@code spawnProjectile(ship, fakeWeapon,
 * "terminator_missile", droneLoc, ...)} with a {@code createFakeWeapon} carrier. The server's
 * source-pair attribution is an IDENTITY scan of the emitter's {@code getAllWeapons()}
 * ({@code EngineCombatBinding}) — a fake weapon is in no slot list, so the pair stays
 * the {@code (0,-1)} sentinel and the track crosses sourceless, exactly like the station
 * mines. Without this lane the sourceless arm would present every drone strike AS a mine:
 * {@code mine_spawn} + fade-in at conversion, {@code sourcelessMine} end-classification, and a
 * phantom {@code mine_explosion} at strike end (a hit always lands inside the det envelope).
 * {@code adoptSourceless} therefore carves the {@code terminator_missile_proj} spec OUT of the mine
 * treatment and hands the twin here.
 *
 * <p><b>What plays (vanilla {@code DroneStrikeStats}, presentation lines ONLY).</b> At adopt:
 * the drone's death-explosion pre-sets (scale 0.67, zero velocity, orange flash), the
 * conversion EMP arc (thickness 26, core 0.67×, fringe (255,100,100)/white core,
 * single flicker) and {@code setNoFlameoutOnFizzling}. Per frame (via vanilla's own
 * driver seam, {@code addLayeredRenderingPlugin} + a self-expiring plugin, the
 * {@code DroneMissileScript} shape): missile sprite alpha-0 (the drone IS the visual),
 * engine flames fade to transparent, orange jitter ramping with
 * flight fraction. The ride itself (loc/vel/facing), owner, collision, damage and removal stay
 * server-authoritative — none of the script's behavior lines are mirrored. Impact damage vs
 * the OWN ship presents via the det relay
 * ({@code MineBombEndFxLane.presentConversionEndDet} → {@code OwnDetLane}), not here.
 *
 * <p><b>Pairing.</b> The wire carries no source and no drone linkage, so the drone is found by
 * the heuristic: nearest live replica with base hull {@code drone_terminator} within
 * collision radius + slack of the adopted body (server-side the drone rides the missile exactly,
 * so the true pair is near-coincident; the wing sibling is off-path — nearest wins). The EMP-arc
 * anchor is the drone's CARRIER — its RAPP row names the carrier wire id (own seat ⇒ the piloted
 * hull, else the replica body); the original heuristic (nearest live
 * {@code apex}-hulled replica in range) stays the fallback for an untagged drone; no anchor = arc
 * skipped, counted. No candidate drone = no FX at all (counted {@code _nopair}): the visible
 * presented missile is then the only honest visual. The former "own-ship conversion lands in
 * {@code _nopair}" residue is CLOSED: own-ship fighters are wire puppets in the replica registry
 * (RAPP tags every bay-born fighter, both sides), the piloted carrier's press now reaches the
 * serve ({@link CcDroneStrikeStats}), and its conversion pairs + anchors exactly like a foe's.
 *
 * <p><b>Jitter-ramp basis.</b> The client body's {@code getMaxRange()} is the SPEC base — the
 * server rescales its missile to the system range ({@code DroneStrikeStats}) — and puppet
 * installs pin {@code maxFlightTime} at 1e9, so the flight-time term is dead and the distance
 * term ramps somewhat fast; the fraction is capped at 1 (also keeps the jitter Color alpha
 * legal). Presentation-only divergence, accepted.
 *
 * <p>Knob {@code cc.predict.droneStrikeFx} = {@code on|off}, DEFAULT ON; invalid THROWS at
 * class load. {@code off} = the earlier sourceless-arm behavior byte-for-byte (mine FX + mine
 * end-classification on conversions — the A/B lever). Render-thread only, fail-soft per entry.
 */
final class DroneStrikeFxLane {

    static final boolean ON = parseOnOff(System.getProperty("cc.predict.droneStrikeFx", "on"));

    /** The conversion missile's WIRE content key — the .proj id, NOT the weapon id
     *  ("terminator_missile", {@code DroneStrikeStats}): the server keys a live missile as
     *  {@code getProjectileSpecId()} = the missile HULL id = the proj-spec id (the engine's missile;
     * {@code terminator_missile.wpn} {@code projectileSpecId}),
     *  the same law that crosses a station mine as {@code minelayer_mine} [MEASURED].
     * The weapon-id fallback ({@code EngineCombatBinding}) never
     *  runs here because the spec id reads non-blank — keying the weapon id starved every
     *  conversion site across two watches. */
    static final String CONVERSION_SPEC = "terminator_missile_proj";
    /** The convertible drone's base hull (wing_data.csv {@code terminator_wing}). */
    private static final String DRONE_HULL_ID = "drone_terminator";
    /** The converting ship's base hull (the fielded apex; arc anchor heuristic). */
    private static final String APEX_HULL_ID = "apex";
    /** Pair-scan slack past the drone's collision radius (su): covers wire quantization plus a
     *  few frames of replica-interp lag behind a fast missile; the wing sibling flies formation
     *  well off-path, and nearest-wins breaks any tie. */
    private static final float PAIR_RADIUS_SLACK = 150f;
    /** Arc-anchor search radius (su; ~the system's effective reach — presentation heuristic). */
    private static final float ARC_SOURCE_RANGE = 2500f;
    private static final float ARC_THICKNESS = 26f;          // vanilla DroneStrikeStats
    private static final float ARC_CORE_WIDTH_MULT = 0.67f;  // vanilla DroneStrikeStats
    private static final Color ARC_FRINGE = new Color(255, 100, 100, 255);
    private static final Color EXPLOSION_FLASH = new Color(255, 100, 50, 255);
    private static final Color ENGINE_FADE = new Color(0, 0, 0, 0);

    private final MissilePoseLane mpl;
    /** Live conversion FX plugins (render-thread only; entries self-remove at expiry). */
    private final List<ConversionFx> active = new ArrayList<ConversionFx>();
    /** Conversion wire id → the drone's CARRIER (RAPP-resolved only, never the apex
     *  proximity fallback), kept from PAIR until the impact end takes it: the conversion missile is
     *  sourceless on the wire, so this is the only honest {@code applyDamage} source (the engine's
     *  floaty rule shows numbers for the player's own carrier). Bounded by live conversions. */
    private final java.util.Map<Long, ShipAPI> carrierByWireId = new java.util.HashMap<Long, ShipAPI>();

    private int paired;     // predict_missile_droneconv_paired
    private int noPair;     // predict_missile_droneconv_nopair
    private int arcs;       // predict_missile_droneconv_arc
    private int arcSkipped; // predict_missile_droneconv_arc_skipped
    private int ended;      // predict_missile_droneconv_end
    /** A tripwire (incremented by {@code MissilePoseLane.pruneLocalGone}): a conversion
     *  twin died LOCALLY mid-track — must stay 0 under the intangible-body law. */
    int localGoneConv;      // predict_missile_droneconv_local_gone

    DroneStrikeFxLane(MissilePoseLane mpl) {
        this.mpl = mpl;
    }

    /** The adoptSourceless carve key: knob-aware so {@code off} restores the mine treatment
     *  byte-for-byte (the B2/B3 A/B idiom). */
    static boolean isConversionSpec(String trueSpec) {
        return ON && CONVERSION_SPEC.equals(trueSpec);
    }

    /** One conversion twin, once per track (the adoptSourceless registration tail). */
    void onConversionAdopt(MissilePoseLane.Twin t) {
        try {
            MissileAPI missile = t.missile;
            ShipAPI drone = nearest(missile.getLocation(), DRONE_HULL_ID, 0f, true);
            if (drone == null) {
                noPair++;
                mpl.convLog("predict v13 droneconv NO-PAIR: id=" + t.wireId
                        + " (no live drone_terminator replica in radius; body left visible)");
                return;
            }
            // DroneStrikeStats — the death-explosion pre-sets (the server kills the drone
            // at strike end; the client kill plays with these).
            drone.setExplosionScale(0.67f);
            drone.setExplosionVelocityOverride(new org.lwjgl.util.vector.Vector2f());
            drone.setExplosionFlashColorOverride(EXPLOSION_FLASH);
            missile.setNoFlameoutOnFizzling(true); // as DroneStrikeStats does
            // The arc's true anchor is the drone's carrier (RAPP carrier wire id);
            // the apex-hull proximity heuristic is the fallback for an untagged drone.
            ShipAPI apex = carrierOf(drone);
            String anchorKind = apex != null ? "carrier" : "apex";
            if (apex != null) {
                carrierByWireId.put(t.wireId, apex); // The impact end's applyDamage source
            }
            if (apex == null) {
                apex = nearest(drone.getLocation(), APEX_HULL_ID, ARC_SOURCE_RANGE, false);
            }
            if (apex != null) {
                // DroneStrikeStats verbatim (anchor choice aside — see class doc).
                EmpArcEntityAPI arc = mpl.plugin.engine.spawnEmpArcVisual(apex.getLocation(),
                        apex, missile.getLocation(), missile, ARC_THICKNESS, ARC_FRINGE,
                        Color.white, null);
                arc.setCoreWidthOverride(ARC_THICKNESS * ARC_CORE_WIDTH_MULT);
                arc.setSingleFlickerMode();
                arcs++;
            } else {
                arcSkipped++;
            }
            ConversionFx fx = new ConversionFx(drone, missile, t.wireId);
            mpl.plugin.engine.addLayeredRenderingPlugin(fx);
            active.add(fx);
            paired++;
            mpl.convLog("predict v13 droneconv PAIR: id=" + t.wireId
                    + " drone=" + drone.getId() + " arc=" + (apex != null)
                    + " anchor=" + (apex != null ? anchorKind : "none"));
        } catch (Throwable ignore) {
            // presentation lane; the adopt itself already stands
            // 1290 (81) X8: adopt-side FX lost (the END sibling is loud) -> raw ghost missile.
            FallbackAnnounce.announceOnce("x8-drone-conv-adopt-threw#" + t.wireId,
                    "onConversionAdopt threw " + ignore.getClass().getSimpleName());
        }
    }

    /** Nearest live replica of the given base hull: pair mode ({@code maxDist} 0) uses
     *  per-candidate collision radius + slack and skips already-paired drones. */
    private ShipAPI nearest(org.lwjgl.util.vector.Vector2f loc, String baseHullId, float maxDist,
            boolean pairMode) {
        ShipAPI best = null;
        float bestD = Float.MAX_VALUE;
        for (PredictReconcileCombatPlugin.RemoteEntity re : mpl.plugin.remoteEntities.values()) {
            ShipAPI s = re.ship;
            if (s == null || re.killed || !s.isAlive() || s.isHulk() || s.getHullSpec() == null
                    || !baseHullId.equals(s.getHullSpec().getBaseHullId())) {
                continue;
            }
            if (pairMode && isPaired(s)) {
                continue;
            }
            float d = Misc.getDistance(loc, s.getLocation());
            float limit = pairMode ? s.getCollisionRadius() + PAIR_RADIUS_SLACK : maxDist;
            if (d <= limit && d < bestD) {
                best = s;
                bestD = d;
            }
        }
        return best;
    }

    /** The drone's carrier body via its RAPP row ({@code appendCarrierWireId}: own seat id ⇒ the
     *  piloted hull, else the remote body); null when untagged, unbound or dead. One O(N) scan per
     *  conversion (rare). */
    private ShipAPI carrierOf(ShipAPI drone) {
        for (PredictReconcileCombatPlugin.RemoteEntity re : mpl.plugin.remoteEntities.values()) {
            if (re.ship != drone) {
                continue;
            }
            if (re.appendCarrierWireId == 0L) {
                return null;
            }
            ShipAPI carrier = FighterWingHudLane.resolveCarrier(mpl.plugin, re.appendCarrierWireId);
            return carrier != null && carrier.isAlive() && !carrier.isHulk() ? carrier : null;
        }
        return null;
    }

    private boolean isPaired(ShipAPI drone) {
        for (ConversionFx fx : active) {
            if (fx.drone == drone) {
                return true;
            }
        }
        return false;
    }

    /** Round wrap: expire every live FX plugin (bodies are the reset's problem, not ours). */
    void resetForMatchReset() {
        for (ConversionFx fx : active) {
            fx.done = true;
        }
        active.clear();
        carrierByWireId.clear();
    }

    /** The conversion's carrier body at its impact end (one take per wire id; null when
     *  the drone was untagged, the pair never formed, or the carrier is gone/dead). */
    ShipAPI takeCarrier(long wireId) {
        ShipAPI carrier = carrierByWireId.remove(wireId);
        return carrier != null && carrier.isAlive() && !carrier.isHulk() ? carrier : null;
    }

    /** Appended to the MPL meter line (the split/own meterTokens position discipline). */
    String meterTokens() {
        return " predict_missile_droneconv_paired=" + paired
                + " predict_missile_droneconv_nopair=" + noPair
                + " predict_missile_droneconv_arc=" + arcs
                + " predict_missile_droneconv_arc_skipped=" + arcSkipped
                + " predict_missile_droneconv_end=" + ended
                + " predict_missile_droneconv_local_gone=" + localGoneConv;
    }

    private static boolean parseOnOff(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(value)) {
            return true;
        }
        if ("off".equals(value)) {
            return false;
        }
        throw new IllegalStateException("cc.predict.droneStrikeFx must be on|off; got '"
                + raw + "'");
    }

    /**
     * The per-frame conversion presentation — vanilla's own driver seam
     * ({@code DroneMissileScript} rides {@code addLayeredRenderingPlugin}; the engine advances
     * it every render frame and reaps it at {@link #isExpired}). Presentation lines only; any
     * throw expires the plugin (fail-soft, never break the watch).
     */
    private final class ConversionFx extends BaseCombatLayeredRenderingPlugin {
        final ShipAPI drone;
        final MissileAPI missile;
        final long wireId;
        boolean done;

        ConversionFx(ShipAPI drone, MissileAPI missile, long wireId) {
            this.drone = drone;
            this.missile = missile;
            this.wireId = wireId;
        }

        @Override
        public void advance(float amount) {
            super.advance(amount);
            if (done) {
                return;
            }
            try {
                CombatEngineAPI engine = mpl.plugin.engine;
                if (engine == null || !engine.isEntityInPlay(missile)
                        || !engine.isEntityInPlay(drone) || drone.isHulk()
                        || drone.getHitpoints() <= 0f) {
                    // The reason discriminates missile-body death from the normal
                    // shell-kill unpair (droneconv_end alone was ambiguous).
                    end(engine == null ? "engine"
                            : !engine.isEntityInPlay(missile) ? "missile-gone"
                            : !engine.isEntityInPlay(drone) ? "drone-gone"
                            : drone.isHulk() ? "drone-hulk" : "drone-hp0");
                    return;
                }
                // DroneMissileScript — the drone IS the visual; the presented missile
                // sprite hides while paired (the double-image suppression).
                missile.setSpriteAlphaOverride(0f);
                drone.getEngineController().fadeToOtherColor(this, ENGINE_FADE, ENGINE_FADE,
                        1f, 1f);
                float dist = Misc.getDistance(missile.getLocation(), missile.getStart());
                float f = dist / Math.max(1f, missile.getMaxRange());
                float mft = missile.getMaxFlightTime();
                if (mft > 0f && mft < 100000f) {
                    // dead under a puppet install (maxFlightTime pinned 1e9); kept so the
                    // formula stays the vanilla pair verbatim if that pin ever changes
                    f = Math.max(f, missile.getFlightTime() / mft);
                }
                if (f > 1f) {
                    f = 1f; // spec-base range vs the server's rescale — see class doc
                }
                drone.setJitter(this, new Color(255, 100, 50, (int) (25f + 50f * f)), 1f, 10,
                        1f, 1f + 10f * f);
            } catch (Throwable ignore) {
                end("threw");
            }
        }

        private void end(String reason) {
            if (!done) {
                done = true;
                ended++;
                active.remove(this);
                mpl.convLog("predict v16 droneconv FX-END: id=" + wireId
                        + " reason=" + reason);
            }
        }

        @Override
        public boolean isExpired() {
            return done;
        }
    }
}
