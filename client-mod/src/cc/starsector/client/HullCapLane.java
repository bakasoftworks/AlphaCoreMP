package cc.starsector.client;

import cc.starsector.client.PredictReconcileCombatPlugin.RemoteEntity;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.combat.listeners.DamageListener;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import org.lwjgl.util.vector.Vector2f;

/**
 * The hull-cap family — the ONE shared axis-A/B cap core ({@link HullCapCore}) and its three thin
 * listener shells ({@link FoeDamageCap} 1v1 foe / {@link OwnDamageCap} own / {@link RemoteDamageCap}
 * per registry entity), the engine-level own-missile hit feed ({@link OwnMissileHitFeed}) and the
 * premature-local-kill meter ({@link #notePrematureKill}) with the {@code fx_restores} counter the
 * plugin's debrief line reads.
 *
 * <p><b>Provenance.</b> Carved VERBATIM out of {@link PredictReconcileCombatPlugin}
 * (the plugin's size budget — the 9500-nb HARD BACKSTOP
 * crossed). Zero policy change: bodies moved byte-for-byte with only
 * {@code plugin.} / {@code PredictReconcileCombatPlugin.} receiver qualifications and javadoc
 * requalifications; the four listener classes went {@code private}→package (the plugin instantiates
 * them as {@code hullCap.new X()} at the SAME four registration sites, same order ⇒ listener
 * identity/order + the clamp→restore one-shot unchanged) and {@code fxRestores}
 * {@code private}→package (the debrief reader). Proof = a verbatim-carve check.
 *
 * <p><b>What stays plugin-side (reached via {@code plugin.}):</b> {@code ownShip} / {@code foeShip},
 * the axis-C flags {@code capFoeDamage} / {@code capOwnDamage} (cleared by the kill paths),
 * {@code hullSourceClass} (né {@code serverDealtSource}) + its {@code deadSrc*} meters (2 callers, both here), {@code isModuleOf},
 * the witness / hull-ledger / det / missile lanes and {@code lastSnapshotSimTick}; the knob statics
 * {@code FOE_HULL_FLOOR_FRAC} / {@code LOW_HP_FX_RESTORE} went {@code private}→package.
 *
 * <p>Every member runs on the render thread inside ONE synchronous {@code Ship.applyDamage} call
 * (the {@link HullCapCore} javadoc) — no lock, no thread, no init-order move here. Fresh-file growth
 * watch: ONE concern (the cap family); re-adjudicate at the {@code 30-warn-tiers.ps1} WarnNb.
 */
final class HullCapLane implements ReplicaOverflowGuard.Host {

    private final PredictReconcileCombatPlugin plugin;

    HullCapLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** Cumulative cap-clamped hits whose presentation was restored (speed-meter debrief; re-watch bar >0). */
    int fxRestores;
    /** One-shot detail line on the first restore (the autofireSuppressLogged idiom). */
    private boolean fxRestoreLogged;

    /** D1: the replica caps' {@link ReplicaOverflowGuard} outcome counters, one set per
     *  plugin; exported by {@link #rogTokens} on the speed-meter line. */
    final ReplicaOverflowGuard.Meters rog = new ReplicaOverflowGuard.Meters();

    /** The speed-meter tokens of {@link #rog} (reader + delete trigger =
     *  the replica-overflow-guard watch-bar script). */
    String rogTokens() {
        return " rog_land=" + rog.land + " rog_drop=" + rog.drop + " rog_residue=" + rog.residue
                + " rog_stale=" + rog.stale + " rog_fail=" + rog.fail + " rog_own_arm=" + rog.ownArm
                + " rog_unarmed_over=" + rog.unarmedOver;
    }

    @Override
    public boolean isOwnShip(ShipAPI s) {
        return s == plugin.ownShip;
    }

    /** The REPLICA test of {@link PredictReconcileCombatPlugin#hullSourceClass} without its counters:
     *  the 1v1 foe or a registered {@link RemoteEntity}'s body — a dead one included. One map walk per
     *  replica shield hit, the cost class the report seam already pays. */
    @Override
    public boolean isWireDrivenShip(ShipAPI s) {
        if (s == null || s == plugin.ownShip) {
            return false;
        }
        if (s == plugin.foeShip) {
            return true;
        }
        for (RemoteEntity re : plugin.remoteEntities.values()) {
            if (re.ship == s) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean wireAckedOpen() {
        return plugin.hullLane.wireAckedOpen();
    }

    @Override
    public void settleBoostClock(ShipAPI body, com.fs.starfarer.api.combat.FluxTrackerAPI ft, float curr) {
        PredictReconcileCombatPlugin.settleBoostClockAfterFluxPin(body, ft, curr);
    }

    /** Meter (a), PREMATURE local kills: a cap's
     *  {@code reportDamageApplied} saw its body at hull &le; 0 while that cap's flag was still set.
     *  The axis-C authoritative kill blow clears the flag FIRST ({@code FoeStateLaneCoordinator}
     *  killRemote, {@code OwnSeatLifecycleLane} killFoe / killOwn), so a kill with the flag set =
     *  the local engine killed the body before the server (the class). Debrief line exports. */
    int localKillPrematureRemote, localKillPrematureFoe, localKillPrematureOwn;
    private boolean localKillPrematureRemoteLogged, localKillPrematureFoeLogged, localKillPrematureOwnLogged;
    /** The foe / own body already counted (one count per body; the RemoteEntity twin is
     *  {@code prematureKillBody}). */
    private ShipAPI prematureFoeBody, prematureOwnBody;
    private static final int PREMATURE_REMOTE = 0, PREMATURE_FOE = 1, PREMATURE_OWN = 2;

    /** Meter (a) body: TRUE (and counted) when {@code ship}
     *  is at hull &le; 0; prints ONE detail row per cap class (the string is built only inside that
     *  one-shot). A throw announces once ({@code FallbackAnnounce}) and returns false; the hit path
     *  never breaks. Callers latch the body on TRUE so a corpse's later hits never re-count. */
    private boolean notePrematureKill(int cls, long entityId, ShipAPI ship, Object source,
            ApplyDamageResultAPI result, float clampUpper) {
        try {
            if (ship == null || !(ship.getHitpoints() <= 0f)) {
                return false;
            }
            boolean first;
            if (cls == PREMATURE_REMOTE) {
                localKillPrematureRemote++;
                first = !localKillPrematureRemoteLogged;
                localKillPrematureRemoteLogged = true;
            } else if (cls == PREMATURE_FOE) {
                localKillPrematureFoe++;
                first = !localKillPrematureFoeLogged;
                localKillPrematureFoeLogged = true;
            } else {
                localKillPrematureOwn++;
                first = !localKillPrematureOwnLogged;
                localKillPrematureOwnLogged = true;
            }
            if (first) {
                PilotWatchLog.info("predict local-kill-premature ["
                        + (cls == PREMATURE_REMOTE ? "remote:" + entityId : cls == PREMATURE_FOE ? "foe" : "own")
                        + "]: dealtHull=" + result.getDamageToHull() + " clampUpper=" + clampUpper
                        + " src=" + plugin.dmgWitness.classifySource(source)
                        + " dsig=" + DealtStatSig.compact(HullCapBound.shooterOf(source))
                        + " hp=" + ship.getHitpoints() + " (first; later ones count only)");
            }
            return true;
        } catch (Throwable t) {
            FallbackAnnounce.announceOnce("local-kill-premature",
                    "notePrematureKill threw " + t.getClass().getSimpleName() + " (meter row skipped)");
            return false;
        }
    }

    /**
     * The replica caps' wire-acked armor REPLAY glue.
     * MODIFY side, after the cap's gate and BEFORE {@code capIfLethal} (which still runs: the
     * report fires after the engine's death branch): an arm from an EARLIER frame whose report never
     * came is restored first (the orphan), then the FOE {@link ArmorCellLane} keys + writes the mask up.
     * The arm is the cap's own one-shot — modify → report is ONE synchronous {@code Ship.applyDamage}, the
     * {@code HullCapCore.pending} carriage. {@code entityId == Long.MIN_VALUE} = resolve the 1v1 foe's id.
     */
    private void replayArm(ArmorCellLane.ReplayArm arm, long entityId, ShipAPI ship, DamageAPI damage,
            Object param, Vector2f point) {
        ArmorCellLane cells = plugin.hullLane.armorCells();
        if (arm.armed && !plugin.hullLane.wireAckedOpen()) {
            // A held arm at a NEW modify always means its report never came (modify → report
            // is one synchronous applyDamage) — the old same-frame test refused the new arm and let THIS report pair
            // with the stale one (its snapshot over this strip, the hull mirrored to a stale preHull). Never
            // inside the presenter's window — PRCP applyWireAckedHit's whole-grid restore would revert the orphan
            // restore, leaving the write-up surplus on the grid; the arm waits for the next modify outside it.
            cells.restoreOrphan(arm);
        }
        if (point == null || damage.isDps()) {
            return; // the lane refuses both too; this spares the 1v1 id scan per beam tick
        }
        long id = entityId != Long.MIN_VALUE ? entityId : foeWireId(ship);
        cells.armReplay(arm, id, ship, damage, param, point, plugin.lastSnapshotSimTick, plugin.advFrames,
                plugin.ownShip, plugin.hullLane.wireAckedOpen());
    }

    /** The 1v1 foe's wire id, memoized per BODY — the
     *  {@link PredictReconcileCombatPlugin#wireIdForLocalShip} scan allocates a {@code remoteEntities}
     *  iterator per gated non-dps hit on the render thread. Validated per hit by ONE
     *  {@code TreeMap.get} on the pre-boxed key + the body identity (zero allocation): a rebind, an
     *  eviction or a match reset (the entry gone, or bound to another body) re-scans; an unmapped body
     *  (a pre-bind frame) is never memoized. */
    private Long foeWireKey;
    private ShipAPI foeWireBody;

    private long foeWireId(ShipAPI ship) {
        Long key = foeWireKey;
        if (key != null && ship == foeWireBody) {
            RemoteEntity re = plugin.remoteEntities.get(key);
            if (re != null && re.ship == ship) {
                return key;
            }
        }
        long id = plugin.wireIdForLocalShip(ship);
        if (id != cc.starsector.server.runtime.PilotIntentFrame.TARGET_NONE) {
            foeWireKey = Long.valueOf(id);
            foeWireBody = ship;
        }
        return id;
    }

    /** REPORT head: opens the FHRL wire-acked window on the replay twins iff THIS cap holds a
     *  same-frame arm for this body — {@code recordApplied} then pushes no replay entry; a stale arm is
     *  restored as an orphan and nothing opens. */
    private boolean replayOpen(ArmorCellLane.ReplayArm arm, CombatEntityAPI target) {
        if (!arm.armed || arm.ship != target) {
            return false;
        }
        if (arm.frame != plugin.advFrames) {
            if (!plugin.hullLane.wireAckedOpen()) { // not inside the presenter's window
                plugin.hullLane.armorCells().restoreOrphan(arm);
            }
            return false;
        }
        plugin.hullLane.beginWireAckedApply(arm.hitTick, true);
        return true;
    }

    /** REPORT tail, inside the window after {@code recordApplied} + {@code emitApply} + the premature-kill
     *  meter: the POST ({@link ArmorCellLane#finishReplay}), then the hull back to its pre-hit value and the
     *  window's ledger record reversed only when it rose (PRCP {@code applyWireAckedHit}'s idiom). */
    private void replayClose(ArmorCellLane.ReplayArm arm, ApplyDamageResultAPI result) {
        ShipAPI ship = arm.ship;
        float preHull = arm.preHull;
        boolean alive = ship.isAlive() && !ship.isHulk();
        plugin.hullLane.armorCells().finishReplay(arm, ship, result, alive);
        if (alive) {
            float before = ship.getHitpoints();
            plugin.mirrorHull(ship, preHull);
            if (ship.getHitpoints() > before) {
                plugin.hullLane.undoWireAckedApply();
            }
        }
    }

    /**
     * The ONE shared axis-A/B hull-cap core.
     * {@link FoeDamageCap}/{@link OwnDamageCap}/{@link RemoteDamageCap} carried this math verbatim ×3
     * (the 0173 RemoteDamageCap bug's untracked root), differing only in ship source + modifier key;
     * the activation/corpse-identity gates stay in the thin per-class shells. Non-static: bumps the
     * enclosing plugin's {@code fx_restores} debrief counter.
     */
    private final class HullCapCore {
        private final String modifierKey;
        /**
         * Pending record — set when the cap CLAMPS a hit, consumed one-shot by
         * {@link #restoreFx} at the engine's own {@code DamageListener} seam. The whole
         * modify → applyDamageInner → report chain is ONE synchronous {@code Ship.applyDamage}
         * call on the render thread, so plain instance fields carry it hit-to-seam with no
         * cross-thread hazard. {@code pendingMult} is kept for the first-restore log only.
         */
        private boolean pending;
        private float pendingMult;
        private float pendingUpperHull;
        private boolean pendingHasPoint;
        private final Vector2f pendingPoint = new Vector2f();
        /** ALWAYS-armed clamp record (independent of {@code LOW_HP_FX_RESTORE},
         *  which gates {@code pending*} — do NOT reuse those): the uncapped would-be hull figure of
         *  the LAST clamped hit, consumed one-shot by the cap listeners' ledger record so
         *  {@code rawHullUnc} carries the client-would-have-killed scalar. Same synchronous
         *  hit-to-seam carriage as {@code pending}. */
        private boolean clampArmed;
        private float clampUpperHull;

        HullCapCore(String modifierKey) {
            this.modifierKey = modifierKey;
        }

        /**
         * The axis-A/B cap: a hit whose CONSERVATIVE worst-case hull damage cannot reach the floor is
         * left untouched (axis A, applies in FULL); a would-be-lethal hit is scaled via
         * {@code modifyMult} so the ship lands AT the floor, alive (axis B). Returns the modifier key
         * iff the cap engaged.
         * <p>The worst case is {@code max(getDamage(),
         * getBaseDamage())} (armor only ever REDUCES) TIMES what the engine folds AFTER this seam —
         * the shooter's size-class stat and the target's hull-path taken mults
         * (plus the armor-grid hull step and the dealt hull mult; all in
         * {@link HullCapBound}). The pre-fix bound omitted them: the own ship's {@code dtf=1.5}
         * landed 1.5× the allowance and killed remote fighter 511 locally ~48 s before the server.
         * The fold may over-estimate (benign); it under-estimates only for a source
         * ship the seam cannot see AND whose size-class stat exceeds the own ship's (the
         * residual: script {@code applyDamage} without a param, ship collisions — bounded by the
         * larger of the OWN-ship fallback and the replica dtf slot in {@link HullCapBound#upperBound};
         * meter (a) reads any survivor).
         * {@code param} = the modify seam's source (projectile / beam / ship / arc / String / null).
         */
        String capIfLethal(ShipAPI ship, DamageAPI damage, Vector2f point, Object param) {
            try {
                float maxHull = ship.getMaxHitpoints();
                float hull = ship.getHitpoints();
                if (!(maxHull > 0f) || !(hull > 0f)) {
                    return null;
                }
                float floor = Math.max(1f, PredictKnobConfig.FOE_HULL_FLOOR_FRAC * maxHull);
                // Conservative upper bound on the hull damage this hit can deal (>= the real post-armor value).
                // The composed bound: param shooter vs the record's
                // own stats (the larger), the OWN ship as the worst case when neither resolves.
                float upperHullDamage = HullCapBound.upperBound(ship, damage, param, plugin.ownShip);
                float mult = HullCapBound.capMult(hull, floor, upperHullDamage);
                if (Float.isNaN(mult)) {
                    return null; // even worst-case this hit cannot reach the floor -> apply in FULL (axis A)
                }
                damage.getModifier().modifyMult(modifierKey, mult);
                // always-armed (unlike pending*, which stays LOW_HP_FX_RESTORE-gated).
                clampArmed = true;
                clampUpperHull = upperHullDamage;
                if (PredictKnobConfig.LOW_HP_FX_RESTORE) {
                    // Remember the uncapped would-be hull figure for the report-seam restore.
                    pending = true;
                    pendingMult = mult;
                    pendingUpperHull = upperHullDamage;
                    pendingHasPoint = point != null;
                    if (point != null) {
                        pendingPoint.set(point);
                    }
                }
                return modifierKey;
            } catch (Throwable ignore) {
                // presentation-side cap — never break the watch on a damage-modifier hiccup
                return null;
            }
        }

        /** one-shot consume of the always-armed clamp record; {@code NaN} = the
         *  last applied hit was not clamped. Callers consume UNCONDITIONALLY (before any identity
         *  gate) so a stale arm can never mis-pair a later hit. */
        float consumeClampUpper() {
            if (!clampArmed) {
                return Float.NaN;
            }
            clampArmed = false;
            return clampUpperHull;
        }

        /**
         * Restore the presentation the clamp ate, at the engine's OWN post-state
         * pre-presentation seam: {@code Ship.applyDamage} fires {@code reportDamageApplied}
         * AFTER hull/overkill are final but BEFORE the same result record
         * reaches the collision handler's impact-sound facade, hit glow,
         * and onHit — so inflating {@code setDamageToHull} back to the uncapped figure makes the
         * engine itself play the would-be hit's damage-tiered sound. At {@code mult=0} (ship
         * parked AT the floor inside the death window — the MAIN case) the applied record is
         * all-zero = vanilla silence, and this SYNTHESIZES the presentation outright. Armor/EMP
         * stay as-applied (hull &gt; armor ⇒ the hull-family sound — the right read for a
         * hull-threatening hit). Debris is topped up with the engine's own debris shape
         * (viewport-gated, no-ops at 0). Ship STATE is untouched: every post-seam reader of the
         * record is presentation (per the census — the sole api-impl field reader is a dweller-only
         * hullmod); beams are harmless (the facade is {@code isDps()}-gated off). Uncapped hits
         * never arm {@code pending}, so vanilla presentation is byte-identical for them.
         */
        void restoreFx(ShipAPI ship, CombatEntityAPI target, ApplyDamageResultAPI result) {
            if (!pending) {
                return;
            }
            pending = false; // one-shot: consumed by the FIRST report after the clamped hit
            if (ship == null || target != ship || result == null) {
                return;
            }
            try {
                float appliedHull = result.getDamageToHull();
                float presentedHull = Math.max(appliedHull, pendingUpperHull);
                float eaten = presentedHull - appliedHull;
                if (!(eaten > 0f)) {
                    return; // clamp engaged but ate ~nothing — the applied record is already faithful
                }
                result.setDamageToHull(presentedHull);
                if (pendingHasPoint) {
                    // The engine's debris shape (3rd arg = ship-center -> hit-point angle in DEGREES, not distance).
                    com.fs.starfarer.combat.CombatEngine.getInstance().getDebrisSystem().spawnDebris(
                            pendingPoint, ship.getVelocity(),
                            com.fs.starfarer.api.util.Misc.getAngleInDegrees(ship.getLocation(), pendingPoint),
                            ship.getHitpoints(), eaten);
                }
                fxRestores++;
                if (!fxRestoreLogged) {
                    fxRestoreLogged = true;
                    PilotWatchLog.info("predict low-HP fx-restore ACTIVE (first restore): key=" + modifierKey
                            + " applied=" + appliedHull + " presented=" + presentedHull
                            + " mult=" + pendingMult + " (record inflation)");
                }
            } catch (Throwable ignore) {
                // presentation-side restore — never break the hit path on an FX hiccup
            }
        }
    }

    /**
     * The damage-CAP — a {@link DamageTakenModifier} on the dead-reckoned foe that resolves the
     * hull-bar TRILEMMA (show the local hit / no client-side death / server-authoritative death). It
     * supersedes the FALSIFIED {@code min(local,auth)} reconcile (double-counted real collision damage ->
     * single-shot client death) and the rejected {@code FoeDamageSuppressor} (erased the local FX the
     * design required).
     * <ul>
     *   <li><b>axis A — show the hit.</b> A NON-lethal local hit is returned UNTOUCHED, so the engine applies
     *       it in full: the floating damage number, the impact FX, and the real hull-bar drop all render.</li>
     *   <li><b>axis B — no client-side death.</b> A hit that could bring the foe to/below the hull floor is
     *       reduced (via {@code modifyMult}) so the foe lands AT the floor, alive. The cap uses
     *       {@code max(getDamage(), getBaseDamage())} (the BASE, PRE-armor damage; armor only ever REDUCES)
     *       as a CONSERVATIVE upper bound on the hull damage
     *       TIMES the factors the engine folds AFTER this seam (the shooter's
     *       size-class stat, the target's taken mults; see {@link HullCapBound}). Without them the bound
     *       UNDER-estimated (a dtf=1.5 shooter killed remote fighter 511 locally);
     *       under-estimating lets a lethal hit through = a client-side death, the dangerous failure the
     *       design guards against (residual: a seam-invisible source ship above the own ship's stat).
     *       Otherwise at worst the bound over-caps the FINAL near-lethal hit slightly
     *       (a benign, slightly-low number on the shot right before the foe dies). The common in-range hit
     *       (hull &gt; base damage, e.g. the FIRST Hellbore on a full 1000-hull Mudskipper) is untouched, so
     *       axis A is fully faithful there.</li>
     *   <li><b>axis C — server-authoritative death.</b> {@link OwnSeatLifecycleLane#killFoe} clears {@link PredictReconcileCombatPlugin#capFoeDamage}, so the
     *       1,000,000 HE authoritative kill blow passes the cap untouched and the engine runs the real death.</li>
     * </ul>
     * Shield hits are left alone (they cannot kill the hull). Non-static so it reads the enclosing
     * {@link PredictReconcileCombatPlugin#capFoeDamage} / {@link PredictReconcileCombatPlugin#foeShip}.
     */
    final class FoeDamageCap implements DamageTakenModifier, DamageListener {
        private final HullCapCore core = new HullCapCore("cc_foe_hull_cap");
        /** this cap's one-shot armor-replay arm (reused — zero allocation per hit). */
        private final ArmorCellLane.ReplayArm replay = new ArmorCellLane.ReplayArm();
        /** D1: a replica-source shield hit never authors this body's overload. */
        private final ReplicaOverflowGuard overflow = new ReplicaOverflowGuard(HullCapLane.this, rog);

        @Override
        public void reportDamageApplied(Object source, CombatEntityAPI target, ApplyDamageResultAPI result) {
            String rogTok = overflow.settle(plugin.foeShip, source, target, result); // D1: FIRST — before the replay window and the witness row
            boolean acked = replayOpen(replay, target); // The window opens at the head
            try {
                // raw-ledger record — as-applied hull damage read BEFORE the
                // restoreFx inflation below rewrites the record; identity-gated (a corpse's stale
                // listener never records into the fresh hull's ledger).
                float clampUpper = core.consumeClampUpper();
                if (plugin.hullLane.foeHullLedger != null && result != null && target == plugin.foeShip) {
                    plugin.hullLane.foeHullLedger.recordApplied(result.getDamageToHull(), clampUpper,
                            plugin.hullSourceClass(source, target)); // dead body; kill-window + door det; + REPLICA (credit)
                }
                // as-applied emit BEFORE restoreFx rewrites the record.
                plugin.dmgWitness.rogTok = rogTok;
                plugin.dmgWitness.emitApply(target == plugin.foeShip ? "foe" : "foe-stale", source, target, result, clampUpper,
                        acked ? replay : null);
                if (plugin.capFoeDamage && target == plugin.foeShip && result != null && prematureFoeBody != plugin.foeShip //
                        && notePrematureKill(PREMATURE_FOE, 0L, plugin.foeShip, source, result, clampUpper)) {
                    prematureFoeBody = plugin.foeShip;
                }
                if (acked) {
                    replayClose(replay, result);
                }
            } finally {
                if (acked) {
                    plugin.hullLane.endWireAckedApply();
                }
            }
            core.restoreFx(plugin.foeShip, target, result); // one-shot, no-ops unless THIS cap just clamped
        }

        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage,
                                        Vector2f point, boolean shieldHit) {
            plugin.dmgWitness.armApply(target, param, point, shieldHit); // Stash BEFORE the gate below
            if (!plugin.capFoeDamage || shieldHit || damage == null || plugin.foeShip == null) {
                if (shieldHit) {
                    overflow.arm(plugin.foeShip, target, param); // D1: the shield hit applies in full unless it would overflow
                }
                // authoritative kill blow / shield hit / nothing to cap -> apply unchanged. No
                // corpse-identity gate here, deliberately: 1v1 has no
                // respawnFoe path — capFoeDamage re-arms only in finishMatchReset, which sweeps
                // the old world.
                return null;
            }
            if (target == plugin.foeShip) { // a stale listener's hit on ANOTHER body never arms the foe's mask
                replayArm(replay, Long.MIN_VALUE, plugin.foeShip, damage, param, point); // Key + PRE; the cap still runs
            }
            return core.capIfLethal(plugin.foeShip, damage, point, param);
        }
    }

    /** The local-hit feed of the ballistic
     *  lane's recent own-hit ring (the engine's own damage seam; one instanceof per hit, never per
     *  frame). The lane filters to the seat's own missiles and meters its own failures. Fail-closed:
     *  a throw lands on {@code noteOwnLocalHitFailed} (own_local_hit_note_fail + announceOnce).
     *  <p>Sole caller is now
     *  {@link OwnMissileHitFeed} (engine-level, every damage event), so the early return also drops
     *  non-ship targets — an own missile striking a missile/asteroid can never match an END's
     *  ship victim and must not evict ship hits from the 64-slot ring (the per-cap feeds it replaces
     *  only ever saw ship targets). A {@code DamagingExplosion} param (prox-fuse / explosion-spec
     *  splash) is not a {@link MissileAPI} and carries no missile identity — it never reaches the
     *  ring (an open question). */
    private void noteOwnMissileHit(Object param, CombatEntityAPI target) {
        if (!(param instanceof MissileAPI) || !(target instanceof ShipAPI)) {
            return;
        }
        try { // the damage seam must never break: a throw is counted by the lane, the hit applies
            // The early return above already
            // requires a ShipAPI target — the dead instanceof ternary is gone, the cast stays.
            Object parent = ((ShipAPI) target).getParentStation();
            plugin.missileLane.ballistic.noteOwnLocalHit((MissileAPI) param, target, parent,
                    plugin.lastSnapshotSimTick);
        } catch (Throwable t) {
            plugin.missileLane.ballistic.noteOwnLocalHitFailed(t);
        }
    }

    /** The ENGINE-level feed of
     *  the ballistic lane's own-hit ring. {@code CombatListenerUtil} consults engine-level
     *  {@link DamageTakenModifier}s for EVERY damage taken by every ship/module/missile
     *  (ahead of the per-ship ones), so a hit on a foe MODULE (a separate
     *  ShipAPI) or on a cap-less remote now reaches the ring. Frame-loop law: the body is
     *  the one early-returning call; it modifies nothing (null). No catch here —
     *  {@link #noteOwnMissileHit} is already fail-closed. Registered once per served combat init. */
    final class OwnMissileHitFeed implements DamageTakenModifier {
        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage,
                                        Vector2f point, boolean shieldHit) {
            noteOwnMissileHit(param, target);
            return null;
        }
    }

    /**
     * The OWN-ship axis A/B damage cap: {@link FoeDamageCap}'s exact math reading
     * the own ship. Needed the moment foe fire is PRESENTED locally
     * ({@link AuthoritativePresenter#presentFoeDischarge}): a
     * real spawned projectile deals real local damage, and without this cap a single local hit could
     * KILL the player's ship ahead of (or against) the server's authority — the engine's death is
     * irreversible, so the hull mirror could not undo it. Non-lethal local hits apply in FULL (real
     * FX + bar drop, axis A; the per-frame {@link PredictReconcileCombatPlugin#mirrorHull} returns hull to truth); a would-be-lethal
     * hit lands at the floor instead (axis B). {@link PredictReconcileCombatPlugin#capOwnDamage} is cleared only by
     * {@link OwnSeatLifecycleLane#killOwn} (axis C) so the server-decided kill blow passes untouched.
     * It also runs the witness arm/emit pair (victim=own), det-gated
     * via {@link OwnDetLane#isApplying()}.
     */
    final class OwnDamageCap implements DamageTakenModifier, DamageListener {
        private final HullCapCore core = new HullCapCore("cc_own_hull_cap");

        @Override
        public void reportDamageApplied(Object source, CombatEntityAPI target, ApplyDamageResultAPI result) {
            // The foe/remote-cap witness emit, own-side twin — every real
            // local own-hit (presented foe projectile / replica beam sweep) now mints its
            // victim=own dmg-apply row, so felt own-damage is auditable (the dmg-audit GAP
            // semantics become "server damage with no FELT client presentation" — the standing rule
            // is that the silent hull mirror never excuses a desync). OwnDetLane's det applies are
            // its own single row (isApplying gate); clamp record consumed unconditionally so
            // stale arms never mis-pair (the RemoteDamageCap discipline).
            float clampUpper = core.consumeClampUpper();
            if (!plugin.ownDetLane.isApplying()) {
                plugin.dmgWitness.emitApply(target == plugin.ownShip ? "own" : "own-stale", source, target, result, clampUpper);
            }
            if (plugin.capOwnDamage && target == plugin.ownShip && result != null && prematureOwnBody != plugin.ownShip //
                    && notePrematureKill(PREMATURE_OWN, 0L, plugin.ownShip, source, result, clampUpper)) {
                prematureOwnBody = plugin.ownShip;
            }
            core.restoreFx(plugin.ownShip, target, result); // one-shot, no-ops unless THIS cap just clamped
        }

        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage,
                                        Vector2f point, boolean shieldHit) {
            if (!plugin.ownDetLane.isApplying()) {
                plugin.dmgWitness.armApply(target, param, point, shieldHit); // Stash BEFORE the gate below
            }
            if (!plugin.capOwnDamage || shieldHit || damage == null || plugin.ownShip == null || target != plugin.ownShip) {
                // authoritative kill blow / shield hit / nothing to cap -> apply unchanged. The
                // target identity gate: after a fly-in respawn the CORPSE keeps
                // this stale listener — without the gate it would cap wreck damage with the FRESH
                // hull's numbers. Cap only ever applies to the current own ship.
                return null;
            }
            return core.capIfLethal(plugin.ownShip, damage, point, param);
        }
    }

    /**
     * The per-entity axis A/B damage cap: {@link FoeDamageCap}'s exact math (the
     * shared {@link HullCapCore}), holding its {@link RemoteEntity} instead of
     * reading the enclosing single-foe fields ({@code FoeDamageCap} stays UNTOUCHED for 1v1).
     * {@link FoeStateLaneCoordinator#killRemote} clears {@code entity.capActive} so the authoritative kill blow passes
     * untouched (axis C). Non-static: the core bumps the enclosing
     * {@code fx_restores} counter.
     */
    final class RemoteDamageCap implements DamageTakenModifier, DamageListener {
        private final RemoteEntity entity;
        private final HullCapCore core = new HullCapCore("cc_remote_hull_cap");
        /** this cap's one-shot armor-replay arm (reused — zero allocation per hit). */
        private final ArmorCellLane.ReplayArm replay = new ArmorCellLane.ReplayArm();
        /** D1: a replica-source shield hit never authors this body's overload. */
        private final ReplicaOverflowGuard overflow = new ReplicaOverflowGuard(HullCapLane.this, rog);

        RemoteDamageCap(RemoteEntity entity) {
            this.entity = entity;
        }

        @Override
        public void reportDamageApplied(Object source, CombatEntityAPI target, ApplyDamageResultAPI result) {
            String rogTok = overflow.settle(entity.ship, source, target, result); // D1: FIRST — before the replay window and the witness row
            boolean acked = replayOpen(replay, target); // The window opens at the head
            try {
                // raw-ledger record — as-applied, pre-restoreFx, identity-gated (the
                // FoeDamageCap twin; clamp record consumed unconditionally so stale arms never mis-pair).
                float clampUpper = core.consumeClampUpper();
                if (entity.ledger != null && result != null && target == entity.ship) {
                    entity.ledger.recordApplied(result.getDamageToHull(), clampUpper,
                            plugin.hullSourceClass(source, target)); // dead body; kill-window + door det; + REPLICA (credit)
                }
                // as-applied emit BEFORE restoreFx rewrites the record.
                plugin.dmgWitness.rogTok = rogTok;
                plugin.dmgWitness.emitApply((target == entity.ship ? "remote:" : "remote-stale:") + entity.entityId,
                        source, target, result, clampUpper, acked ? replay : null);
                if (entity.capActive && target == entity.ship && result != null //
                        && entity.prematureKillBody != entity.ship
                        && notePrematureKill(PREMATURE_REMOTE, entity.entityId, entity.ship, source, result, clampUpper)) {
                    entity.prematureKillBody = entity.ship;
                }
                if (acked) {
                    replayClose(replay, result);
                }
            } finally {
                if (acked) {
                    plugin.hullLane.endWireAckedApply();
                }
            }
            core.restoreFx(entity.ship, target, result); // one-shot, no-ops unless THIS cap just clamped
        }

        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage,
                                        Vector2f point, boolean shieldHit) {
            plugin.dmgWitness.armApply(target, param, point, shieldHit); // Stash BEFORE the gate below
            if (!entity.capActive || shieldHit || damage == null || entity.ship == null
                    || target != entity.ship) {
                if (shieldHit) {
                    overflow.arm(entity.ship, target, param); // D1: the shield hit applies in full unless it would overflow
                }
                // authoritative kill blow / shield hit / nothing to cap -> apply unchanged. Target
                // identity gate (mirrors the OwnDamageCap gate): after respawnRemote
                // re-arms capActive on the shared RemoteEntity, the CORPSE still carries its stale
                // listener — without the gate it would cap wreck damage with the FRESH hull's numbers.
                return null;
            }
            replayArm(replay, entity.entityId, entity.ship, damage, param, point); // Key + PRE; the cap still runs
            return core.capIfLethal(entity.ship, damage, point, param);
        }
    }
}
