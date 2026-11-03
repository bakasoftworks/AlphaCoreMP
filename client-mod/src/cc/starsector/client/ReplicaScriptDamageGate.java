package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.listeners.DamageDealtModifier;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.loading.ProjectileSpecAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The replica script-damage door. A mod script bound to a REPLICA body (a treated remote ship whose truth
 * the server owns) can mint DAMAGING engine entities on this client: an every-frame tether plugin's
 * {@code spawnEmpArc}, its {@code explode()} blast. That is local damage the server never dealt, at a
 * frame-rate count (a frame-count arc timer fires far more arcs at a high client frame rate than at the
 * server's fixed tick). On a replica victim it is the dip-and-snap class the hull mirrors fold back; on the
 * OWN ship it is EMP disables and armor dips the server never applied. EMP is wire-authoritative, so this
 * door zeroes the arc's local damage while the script, its body and everything it draws (cable, arcs) keep
 * running — nothing is hidden, nothing waits, no effect is painted in its place.
 *
 * <p><b>Seam: DEALT on the replica's own listener list, not an engine-level TAKEN listener.</b> The engine
 * hands every damage listener the damaging param, never the dealing ship; an EMP arc exposes no source on
 * the API. The engine resolves the dealing
 * ship itself and consults THAT ship's {@link DamageDealtModifier} list with the hit.
 *
 * So one {@link Door} rides each treated replica ({@link #install}, called after every
 * replica-body treatment), and the door's own ship IS the dealing ship.
 *
 * <p><b>Classes</b> ({@link #classify}): {@link #ARC} = an {@link EmpArcEntityAPI} dealt by a treated
 * replica: ZEROED through the damage modifier's mult, the returned id lets the engine un-modify it after
 * the hit. The mult already scales the arc's EMP (flux) component, so the flux component is never written
 * (the damage object is fresh per arc, but an explosion's is shared across its victims).
 *
 * {@link #BLAST} = a weaponless, non-missile damaging projectile (a damaging explosion) whose source is an
 * ALIVE treated replica: COUNTED ONLY, never zeroed — a presented foe missile's own explosion-spec burst
 * carries the very same identity (the replica as source, no weapon), and that burst is the predicted-hit
 * class. The replica's own death explosion already reads not
 * alive at its first apply.
 *
 *
 * <p><b>Never zeroed</b>: a damaging projectile WITH a weapon (a presented foe shot = the predicted hit), a
 * missile, a beam, a null param, anything dealt by the OWN ship (its own tether arcs keep their local
 * damage, so the pilot still sees the hit), anything dealt by a ship that is not (or no longer) a treated
 * replica ({@link #treatedReplica}). Vanilla's on-hit arc effects are already visual-only on a client, so
 * {@link #ARC} fires only on unaudited mod scripts — the lawful direction (server truth wins).
 *
 * <p>Also home of the script-spawn spec discriminator ({@link #scriptSpawnSpec}): the same class of mod
 * script, seen at its spawn rather than at its damage.
 *
 * <p>Threads: combat (render) thread only — the engine's damage apply and the plugin's bind paths. No lock.
 *
 */
public final class ReplicaScriptDamageGate {

    /** The modifier id: the engine un-modifies a returned id after the hit, so the zero never outlives it. */
    public static final String ID = "cc_replica_script_dmg";

    /** {@link #classify} verdicts. */
    public static final int NONE = 0;
    public static final int ARC = 1;
    public static final int BLAST = 2;

    /** {@link #scriptSpawnSpec} verdicts. */
    public static final int SPEC_UNREAD = -1;
    public static final int SPEC_OWN = 0;
    public static final int SPEC_SCRIPT = 1;

    private static final int LOG_MAX = 12;

    private final PredictReconcileCombatPlugin plugin;
    /** Ships already carrying a door: weak keys, so a swept body drops out with no reset hook. */
    private final Set<ShipAPI> doored = Collections.newSetFromMap(new WeakHashMap<ShipAPI, Boolean>());
    private int zeroedArc;
    private int blastSeen;
    private int installs;
    private int faults;
    private int logLines;
    private boolean faultLogged;

    ReplicaScriptDamageGate(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Is {@code ship} a treated replica right now? Present in the treatment ledger and NOT the own ship (a
     * former replica the seat took command of may keep a stale ledger key; its door must go quiet). Pure.
     */
    public static boolean treatedReplica(Object ship, Object ownShip, Map<?, ?> treatedLedger) {
        return ship != null && ship != ownShip && treatedLedger != null && treatedLedger.containsKey(ship);
    }

    /**
     * The door's predicate, pure over narrow inputs: the engine's damage param, whether the dealing ship is
     * a treated replica, and whether that ship is alive. Returns {@link #ARC}, {@link #BLAST} or
     * {@link #NONE}.
     */
    public static int classify(Object param, boolean sourceTreatedReplica, boolean sourceAlive) {
        if (!sourceTreatedReplica || param == null) {
            return NONE;
        }
        if (param instanceof EmpArcEntityAPI) {
            return ARC;
        }
        if (param instanceof DamagingProjectileAPI && !(param instanceof MissileAPI) && sourceAlive
                && ((DamagingProjectileAPI) param).getWeapon() == null) {
            return BLAST;
        }
        return NONE;
    }

    /**
     * The door's action for a verdict: {@link #ARC} zeroes the hit's mult and returns {@link #ID} (the engine
     * un-modifies it after the apply); every other verdict leaves the damage untouched and returns null.
     */
    public static String act(int verdict, DamageAPI damage) {
        if (verdict != ARC || damage == null || damage.getModifier() == null) {
            return null;
        }
        damage.getModifier().modifyMult(ID, 0f);
        return ID;
    }

    /**
     * The script-spawn discriminator: does a missile's spec differ from its weapon's OWN projectile spec? A
     * weapon's own fire never carries another spec, so a difference means a script minted the missile
     * through that weapon (an on-hit rod, an every-frame spawn). The weapon's own id is read the way the
     * engine composes a missile's spec id (its missile spec's hull id) or a plain projectile spec's id.
     *Fail-soft: a null or unknown spec, a null missile
     * spec id or a throwing getter = {@link #SPEC_UNREAD} (the caller keeps today's path and counts it).
     */
    public static int scriptSpawnSpec(String missileSpecId, Object weaponProjectileSpec) {
        String own = null;
        try {
            if (weaponProjectileSpec instanceof MissileSpecAPI) {
                ShipHullSpecAPI hull = ((MissileSpecAPI) weaponProjectileSpec).getHullSpec();
                own = hull == null ? null : hull.getHullId();
            } else if (weaponProjectileSpec instanceof ProjectileSpecAPI) {
                own = ((ProjectileSpecAPI) weaponProjectileSpec).getId();
            }
        } catch (Throwable unread) {
            return SPEC_UNREAD; // counted by the caller (script_spawn_spec_unread)
        }
        if (own == null || missileSpecId == null) {
            return SPEC_UNREAD;
        }
        return own.equals(missileSpecId) ? SPEC_OWN : SPEC_SCRIPT;
    }

    /**
     * Put a {@link Door} on {@code ship} once, if it is a treated replica now. Idempotent and O(1) on a
     * re-entry (a per-frame treatment sweep may call it every frame). A throw is counted and logged once; the
     * next treatment call retries.
     */
    void install(ShipAPI ship) {
        if (ship == null || doored.contains(ship)
                || !treatedReplica(ship, plugin.ownShip, plugin.remoteBodyTreated)) {
            return;
        }
        try {
            ship.addListener(new Door(ship));
            doored.add(ship);
            installs++;
        } catch (Throwable t) {
            noteFault("install", t);
        }
    }

    /** Rides the eventReplay-meter line. */
    String meterToken() {
        return " script_dmg_zeroed_arc=" + zeroedArc
                + " script_dmg_blast_seen=" + blastSeen
                + " script_dmg_doors=" + installs
                + " script_dmg_faults=" + faults;
    }

    private void noteFault(String where, Throwable t) {
        faults++;
        if (!faultLogged) {
            faultLogged = true;
            PilotWatchLog.error("predict script-dmg door " + where + " threw (first only; script_dmg_faults counts"
                    + " the rest): a replica script's local damage may apply unzeroed", t);
        }
    }

    /** One per treated replica: the engine consults it on every hit that ship deals. */
    final class Door implements DamageDealtModifier {
        private final ShipAPI ship;

        Door(ShipAPI ship) {
            this.ship = ship;
        }

        @Override
        public String modifyDamageDealt(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point,
                                        boolean shieldHit) {
            int verdict;
            String id;
            try {
                boolean treated = treatedReplica(ship, plugin.ownShip, plugin.remoteBodyTreated);
                verdict = classify(param, treated, treated && ship.isAlive());
                id = act(verdict, damage);
            } catch (Throwable t) {
                noteFault("classify", t);
                return null;
            }
            if (verdict == ARC) {
                zeroedArc++;
                if (logLines < LOG_MAX) {
                    logLines++;
                    PilotWatchLog.info("predict script-dmg ZERO arc #" + zeroedArc + " src="
                            + Integer.toHexString(System.identityHashCode(ship)) + " victim="
                            + (target == null ? "null" : Integer.toHexString(System.identityHashCode(target)))
                            + (target == plugin.ownShip ? " (own)" : "") + " shield=" + shieldHit
                            + " tick=" + plugin.lastSnapshotSimTick
                            + (logLines == LOG_MAX ? " [log cap reached; counter continues]" : ""));
                }
            } else if (verdict == BLAST) {
                blastSeen++;
            }
            return id;
        }
    }
}
