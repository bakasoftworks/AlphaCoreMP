package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import org.lwjgl.util.vector.Vector2f;

/**
 * The Threat devouring-swarm
 * carrier's WIRE end-hook,
 * carved VERBATIM out of {@link SourcedMissileEndLane}. WHY a class of its own: SML crossed its
 * 1298 nb WARN tier (re-triggered) and this hook is the one self-contained
 * sub-family in it — its own verdict, its own armor-cell pick, its own meters, one entry.
 *
 * <p>Entry: {@link #onFoeEnd} from SML's foe twin arm ({@code consumeTwin}), BEFORE the twin is
 * retired (the body is still in play). Owns the {@code predict_missile_foe_devour_end*} meters;
 * SML splices {@link #meterTokens} into its own fragment at the old position, so the MPL meter
 * line kept its token order at the carve (a later commit then APPENDED {@code _fizzling_victim}
 * at this block's end). SML keeps thin delegates ({@code devourEndVerdict},
 * {@code DEVOUR_*}) for the headless rows that name them ({@code MineNativeFuseLawSmoke} F6).
 * Victim resolution stays SML's ({@code resolveShip}, 8 callers). Render-thread only.
 */
final class DevourEndLane {

    private final SourcedMissileEndLane sml;
    private final MissilePoseLane mpl;

    private int foeDevourEndHooks;   // predict_missile_foe_devour_endhook (D-THREATHARVEST)
    private int foeDevourEndSkips;   // predict_missile_foe_devour_end_skips (v15: named victim without a local body / no armor cell)
    private int foeDevourEndShield;  // predict_missile_foe_devour_end_shield (server said SHIELD_ABSORBED, no mint)
    private int foeDevourEndUnnamed; // predict_missile_foe_devour_end_unnamed (INTACT with no server-named victim, no mint)
    /** predict_missile_foe_devour_end_failures: {@link #presentDevourEnd} threw after a MINT verdict
     *  (the DoT was dropped). */
    private int foeDevourEndFailures;
    /** The first such throw is logged once (class + message). */
    private boolean foeDevourEndFailureLogged;
    private int foeDevourEndFizzlingVictim; // predict_missile_foe_devour_end_fizzling_victim (FIZZLING + named victim: counted, no mint)

    DevourEndLane(SourcedMissileEndLane sml, MissilePoseLane mpl) {
        this.sml = sml;
        this.mpl = mpl;
    }

    /** The Threat devouring-swarm carrier's projectile spec (fabricator_unit_Type450
     *  x4 + attack_swarm_Attack both fire it; the onHit slot rides CcWrapDevouringSwarmMissileEffect). */
    static final String DEVOUR_CARRIER_SPEC = "devouring_swarm_missile";

    static boolean isDevourCarrierSpec(String specId) {
        return DEVOUR_CARRIER_SPEC.equals(specId);
    }

    /** The hook site (SML {@code consumeTwin} foe arm): a mint counts on {@code predict_missile_foe_devour_endhook}. */
    void onFoeEnd(MissilePoseLane.Twin t, cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        if (presentDevourEnd(t, event)) {
            foeDevourEndHooks++;
        }
    }

    /** {@link #devourEndVerdict} verdicts (GPEE v15). */
    static final int DEVOUR_MINT = 0;
    /** The server's own shield verdict ({@code END_CAUSE_SHIELD_ABSORBED}): vanilla mints nothing. */
    static final int DEVOUR_SKIP_SHIELD = 1;
    /** An INTACT end the server could not pin on a ship ({@code TARGET_ABSENT}): fail-closed, no DoT. */
    static final int DEVOUR_SKIP_UNNAMED = 2;
    /** DESTROYED: not an impact; FIZZLING with a named victim = the armed-flameout contact, counted, never a DoT mint. */
    static final int DEVOUR_SKIP_CAUSE = 3;

    /**
     * The devour end-hook's PURE verdict on the wire's word: a DoT is
     * minted ONLY on a server-named HULL contact (cause INTACT + victim {@code > 0}). A
     * {@code SHIELD_ABSORBED} cause is vanilla's {@code onHit !shieldHit} no-op (even with a
     * named victim); an unnamed INTACT end is "ended on nothing the server saw hit" — the
     * nearest-body geometric snap is RETIRED, never a fallback (7 of 7 DoTs were
     * phantoms from that guess). Headless-rowed ({@code MineNativeFuseLawSmoke} F6).
     */
    static int devourEndVerdict(int cause, long victim) {
        if (cause == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_SHIELD_ABSORBED) {
            return DEVOUR_SKIP_SHIELD;
        }
        if (cause != cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_INTACT) {
            return DEVOUR_SKIP_CAUSE;
        }
        if (victim <= 0L) {
            return DEVOUR_SKIP_UNNAMED;
        }
        return DEVOUR_MINT;
    }

    /**
     * The devouring-carrier disintegrator look minted at the
     * WIRE end seam. Why: the presented carrier twin is a pose puppet ~RTT behind the server's body;
     * the server's hull hit arrives as this INTACT MISSILE_END and {@code SourcedMissileEndLane.consumeTwin} retires the
     * twin the same call ({@code MissilePoseLane.retire} → {@code removeEntity}), so the local engine
     * almost never walks the collidable twin into the victim and the spec-slot {@code onHit}
     * ({@code CcWrapDevouringSwarmMissileEffect}) fires only on a lucky pose overlap (one census:
     * 29 presenters for ~600 presented carriers).
     *
     * <p>The VICTIM is the ship the server NAMED on the event
     * ({@code endVictimWireId}, resolved like {@code ArcHitFxLane.resolve}: own ship by session id,
     * else the replica registry, else the 1v1 foe) and the shield-vs-hull word is the server engine's
     * own {@code ApplyDamageResultAPI} verdict ({@link #devourEndVerdict}) — the client no longer
     * guesses either from geometry. The confessed pose only picks the armor CELL on that victim
     * ({@link #devourArmorCell}). Once per carrier across both seams
     * ({@code ReplicaDevourPresenter.LATCH_KEY}); {@code setDidDamage(true)} + the hit sound mirror the
     * wrapper's local branch (didDamage drives vanilla's STOP_AND_FADE of the other fragments).
     * Presentation only, 0 wire bytes. TRUE = minted.
     */
    boolean presentDevourEnd(MissilePoseLane.Twin t,
            cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        if (!MissilePoseLane.MISSILE_POSE_APPLY || t == null || t.missile == null
                || !isDevourCarrierSpec(t.specId)) {
            return false;
        }
        long victimWireId = event.endVictimWireId();
        int verdict = devourEndVerdict(event.endCauseCode(), victimWireId);
        if (verdict == DEVOUR_SKIP_SHIELD) {
            foeDevourEndShield++; // the server saw a shield absorb it (vanilla onHit `!shieldHit`)
            return false;
        }
        if (verdict == DEVOUR_SKIP_UNNAMED) {
            foeDevourEndUnnamed++; // INTACT on nothing the server saw hit — no geometric guess
            return false;
        }
        if (verdict != DEVOUR_MINT) {
            foeDevourEndFizzlingVictim += (event.endCauseCode()
                    == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_FIZZLING && victimWireId > 0L) ? 1 : 0;
            return false; // DESTROYED: not an impact; FIZZLING + victim: counted above, never a DoT mint
        }
        if (!(Math.abs(event.endX()) <= SourcedMissileEndLane.WIRE_END_POSE_CEIL_SU)
                || !(Math.abs(event.endY()) <= SourcedMissileEndLane.WIRE_END_POSE_CEIL_SU)) {
            return false; // wire-value guard: non-finite/absurd confessed pose never presents
        }
        try {
            if (ReplicaDevourPresenter.alreadyPresented(t.missile)) {
                return false; // the local onHit seam got there first (the 29-of-600 census case)
            }
            ShipAPI victim = sml.resolveShip(victimWireId);
            if (victim == null) {
                foeDevourEndSkips++; // a named victim this client has no body for (late/absent replica)
                return false;
            }
            Vector2f loc = new Vector2f(event.endX(), event.endY());
            Vector2f point = devourArmorCell(victim, loc);
            if (point == null) {
                foeDevourEndSkips++; // no armor cell resolves near the confessed pose (never a hull mint)
                return false;
            }
            if (!ReplicaDevourPresenter.presentAtEnd(mpl.plugin.engine, t.missile, victim, point)) {
                return false;
            }
            t.missile.setDidDamage(true);
            Global.getSoundPlayer().playSound("devouring_swarm_hit_ship", 1.0F, 1.0F, point,
                    t.missile.getVelocity());
            mpl.lane.log("predict v16 devour end-hook: id=" + t.wireId + " spec=" + t.specId
                    + " at=" + (int) point.x + "," + (int) point.y
                    + " victim=owner" + victim.getOwner()
                    + (victim == mpl.plugin.engine.getPlayerShip() ? "(own)" : "")
                    + " wire=" + victimWireId);
            return true;
        } catch (Throwable fail) {
            // presentation lane; never break the watch -- but every catch counts + logs once.
            // Was a silent `ignore` that dropped
            // a server-named, legitimate DoT with no meter.
            foeDevourEndFailures++;
            if (!foeDevourEndFailureLogged) {
                foeDevourEndFailureLogged = true;
                PilotWatchLog.info("predict devour end-hook failed (first; counted on"
                        + " predict_missile_foe_devour_end_failures): " + fail.getClass().getName()
                        + ": " + fail.getMessage());
            }
            return false;
        }
    }

    /** The end-hook's inward walk: 8 su steps, ≤ 8 (≈ the RTT replica-pose lag, 5–20 su). */
    static final float DEVOUR_INWARD_STEP_SU = 8f;
    static final int DEVOUR_INWARD_STEPS = 8;

    /**
     * The armor CELL on the
     * server-named victim for the end-hook, or {@code null}. The shield-ring test that used to live
     * here is GONE: the server's own {@code ApplyDamageResultAPI} verdict now rides the wire
     * ({@code END_CAUSE_SHIELD_ABSORBED}, decided BEFORE this call by {@link #devourEndVerdict}) and
     * the local replica's shield/pose state is ~RTT stale (the ring guess failed
     * whenever the confessed pose landed inside the collision radius). What stays: the presenter's
     * offset is fixed at mint and {@code DisintegratorEffect.dealDamage} treats a null cell
     * as no yield forever, so the point must resolve to an armor cell — the raw confessed pose
     * first (already on-grid a frame before contact), else the facing-surface point walked inward
     * toward the hull centre until a cell resolves ⇒ null when none. Read-only.
     */
    static Vector2f devourArmorCell(ShipAPI victim, Vector2f loc) {
        com.fs.starfarer.api.combat.ArmorGridAPI grid = victim.getArmorGrid();
        if (grid == null) {
            return null;
        }
        if (grid.getCellAtLocation(loc) != null) {
            return new Vector2f(loc);
        }
        Vector2f p = MineBombEndFxLane.surfaceToward(victim, loc);
        Vector2f centre = victim.getLocation();
        float dx = centre.x - p.x;
        float dy = centre.y - p.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (!(len > 0f)) {
            return grid.getCellAtLocation(p) != null ? p : null;
        }
        float sx = dx / len * DEVOUR_INWARD_STEP_SU;
        float sy = dy / len * DEVOUR_INWARD_STEP_SU;
        for (int i = 0; i <= DEVOUR_INWARD_STEPS; i++) {
            if (grid.getCellAtLocation(p) != null) {
                return p;
            }
            p = new Vector2f(p.x + sx, p.y + sy);
        }
        return null;
    }

    /** Spliced into {@link SourcedMissileEndLane#meterTokens} at the end-hook's pre-carve position
     *  (the MPL meter line stays byte-identical; new tokens append at this block's end). */
    String meterTokens() {
        return " predict_missile_foe_devour_endhook=" + foeDevourEndHooks
                + " predict_missile_foe_devour_end_skips=" + foeDevourEndSkips
                + " predict_missile_foe_devour_end_shield=" + foeDevourEndShield
                + " predict_missile_foe_devour_end_unnamed=" + foeDevourEndUnnamed
                // The end-hook's counted throws.
                + " predict_missile_foe_devour_end_failures=" + foeDevourEndFailures
                + " predict_missile_foe_devour_end_fizzling_victim=" + foeDevourEndFizzlingVictim;
    }
}
