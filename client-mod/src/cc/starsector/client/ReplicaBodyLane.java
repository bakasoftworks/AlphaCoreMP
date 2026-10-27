package cc.starsector.client;

import static cc.starsector.client.PredictKnobConfig.SAME_SIDE_HOLD_LEGACY_OFF;
import static cc.starsector.client.PredictReconcileCombatPlugin.SLEW_MOVED_DEG;

import cc.starsector.client.PredictReconcileCombatPlugin.RemoteEntity;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;

/**
 * The replica-body lane — THE ledgered replica-body treatment ({@link #neutralizeRemoteBody}: inert
 * AI, replay-driven hold-fire, break-prob / ordnance / module-detach suppression, AT MOST ONE
 * RemoteDamageCap per body), the wire-acked hit presenter ({@link #applyWireAckedHit}, its flux-window
 * counters and the armor snapshot/restore twin) and the replay-driven hold ({@link #registerReplayDriven}
 * / {@link #unregisterReplayDriven} / the per-frame {@link #reassertReplayDrivenHold} walk and its set
 * predicates).
 *
 * <p><b>Provenance.</b> Carved VERBATIM out of {@link PredictReconcileCombatPlugin}
 * (the plugin's whole-class size watch: 9399 nb vs
 * the 9386 WarnNb, 101 under the 9500 HARD BACKSTOP). Zero policy change: bodies moved byte-for-byte,
 * javadoc included, in source order, with only {@code plugin.} receiver qualifications, the ONE
 * {@code this} → {@code plugin} at the {@link SubstepHoldAI} mint, {@code private}→package on
 * {@code registerReplayDriven} / {@code reassertReplayDrivenHold} / {@code lastTreatmentFailed} (the
 * plugin still calls / reads them), and {@code registerReplayDriven}'s stranded javadoc re-homed above
 * it. The plugin keeps pure-forward package delegates for every outside caller; the counters moved
 * here, and their readers (the plugin's fluxflags meter line, {@code RemoteEntityCensus},
 * {@code MineNativeFuseLawSmoke}) read {@code replicaBody.}. Proof = a verbatim-carve check
 * + an ordered diff.
 *
 * <p><b>What stays plugin-side (read via {@code plugin.} AT the use site, never copied here —
 * {@code engine} is assigned at {@code init()}, a ctor copy would capture null):</b> the
 * {@code replayDrivenShips} set ({@code DamageWitness} takes it at field init) and the
 * {@code remoteBodyTreated} ledger (both cleared by the match reset; reflected by name in two
 * selftests), the same-side hold meters + {@code sameSideMountLastRel} ({@code logSameSideHoldMeter}),
 * {@code autofireSuppressLogged}, {@code remoteEntityIdOf} (a registry read), {@code engine}, the
 * lanes / gates the treatment calls, {@code mirrorHull} / {@code wireIdForLocalShip} /
 * {@code sideAuthority()}.
 *
 * <p><b>Threads / locks.</b> Render (combat) thread only — the plugin's advance / bind / spawn paths
 * and the lanes' per-frame paths; no lock, no {@code volatile}, no thread, no init-order move (the ctor
 * stores {@code plugin} and reads nothing). The one possible off-thread reader is pre-existing and
 * unchanged: the {@code RemoteEntityCensus} stream-end backstop's size / {@code replayDrivenPruned} read.
 *
 * <p><b>Growth.</b> THE growth home for replica-body / wire-acked-hit / replay-hold work —
 * the shared {@code replicaWireId} resolver at the armor restore lands HERE,
 * never in the plugin. Fresh-file watch: ONE concern (the replica body's local treatment); a lock, a
 * thread or a second concern here = the tell; re-adjudicate at the {@code 30-warn-tiers.ps1} WarnNb.
 *
 */
final class ReplicaBodyLane {

    private final PredictReconcileCombatPlugin plugin;

    ReplicaBodyLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * THE ledgered replica-body treatment: inert AI (no local decisions),
     * replay-driven hold-fire (no local autofire), local break-prob suppression (the split roll is
     * server-owned), and AT MOST ONE {@link RemoteDamageCap} per body.
     *
     * <p>The ledger is the whole point. A second cap on a body is never cleared by
     * {@link FoeStateLaneCoordinator#killRemote} (which clears {@code capActive} on ONE entry), so the survivor keeps
     * clamping every lethal hit — a locally IMMORTAL hull — and both caps emit their own
     * {@code dmgWitness} apply line for the same damage (a silent double count). The map value
     * records whether the CAP is already installed, so a body first neutralized without a registry
     * row (an unbound station module, a refused cluster) can still take its cap later, exactly
     * once, at the moment it becomes a real row.
     *
     * <p>Returns true when this call changed anything (the callers' log gate). Fail-soft per step:
     * treatment is presentation-side and must never break a bind path.
     */
    boolean neutralizeRemoteBody(ShipAPI ship, RemoteEntity capEntity) {
        lastTreatmentFailed = false;
        if (ship == null) {
            return false;
        }
        boolean applied = false;
        try {
            Boolean capped = plugin.remoteBodyTreated.get(ship);
            if (capped == null) {
                ship.setShipAI(new SubstepHoldAI(plugin, ship, plugin.engine)); // per-sub-step hold seam
                registerReplayDriven(ship); // hold local autofire from THIS frame on
                plugin.fractures.suppressLocalBreakProb(ship); // The split roll is server-owned
                plugin.spawnModGate.suppress(ship); // No replica mints its own RNG ordnance
                plugin.swarmSpriteGate.hide(ship); // A swarm-fighter body renders its cloud only, as vanilla
                // No replica rolls its own module detach — the server's kind-4
                // hulk edge is the one authority (the stuck-in-the-hulk class, ModuleDetachPin).
                ModuleDetachPin.pin(ship, "neutralize");
                ThreatHulkReclaimLaw.pin(ship); // No replica hulk mints a local reclamation wing
                HullCapBound.offerReplicaFallback(ship); // A mirrored dtf > 1 widens the cap fallback
                plugin.remoteBodyTreated.put(ship, Boolean.FALSE);
                capped = Boolean.FALSE;
                applied = true;
            }
            if (capEntity != null && !capped.booleanValue()) {
                ship.addListener(plugin.hullCap.new RemoteDamageCap(capEntity));
                plugin.remoteBodyTreated.put(ship, Boolean.TRUE);
                applied = true;
            }
        } catch (Throwable t) {
            lastTreatmentFailed = true;
            PilotWatchLog.error("predict neutralize: replica-body treatment FAILED on "
                    + Integer.toHexString(System.identityHashCode(ship))
                    + " (the body may still act locally)", t);
        }
        return applied;
    }

    /**
     * Did the LAST {@link #neutralizeRemoteBody} call swallow a treatment throw? The return value
     * cannot carry it ({@code false} already means "already treated", which is the normal
     * re-entry), so the failure rides this field — read it IMMEDIATELY after the call, on the same
     * (render) thread, or not at all. A spawn site that logs SPAWNED over a failed treatment
     * reports a bound, treated replica that is in fact still acting locally.
     */
    boolean lastTreatmentFailed;

    /** Has this body taken the FULL treatment (cap included)? The station
     *  lane's per-frame inert sweep short-circuits on it instead of re-scanning the registry for
     *  an entry it cannot use. */
    boolean remoteBodyFullyTreated(ShipAPI ship) {
        return Boolean.TRUE.equals(plugin.remoteBodyTreated.get(ship));
    }

    /** Present
     *  a SERVER-DEALT hit the local engine MISSED on a fully treated replica through the engine's
     *  OWN damage path — {@code applyDamage} at the hit point with {@code playSound}, so the
     *  damage numbers, armor flash, shield hit and impact sound are the engine's, never painted
     *  FX. The hit is already in the wire (the event that names it rides ≥ the pose grace behind
     *  the server's hull delta), so the hull ledger records it WIRE-ACKED: no replay entry, and
     *  the engine's hull subtraction is restored + un-recorded before this
     *  method returns — the presentation stays, the bar never dips on a hit the wire booked. The
     *  ARMOR grid is snapshotted before the apply and restored after it the same way (the old
     *  "fold at the next cell delivery" premise was false — the server's
     *  section for this hit is already in, so the local strip was a second one that the cell
     *  lane then HELD as a local bite; see {@link #restoreWireAckedArmor}). The cap listeners run
     *  unchanged (axis A/B: a lethal-looking hit lands at the floor, never a local kill). Returns
     *  the hull damage the engine applied (post-cap, may be 0 on a shield hit), or {@code -1} when
     *  the body is not a treated replica (nothing applied — a body with no reconcile would drift). Combat thread. First user: the terminator conversion's
     *  NOT-OWN impact end ({@code MineBombEndFxLane.presentConversionEndDet}). {@code hitTick} =
     *  the hit's SERVER tick (the end event's; -1 = unknown), the order bound of the ledger's credit
     *  consume. */
    float applyWireAckedHit(ShipAPI victim, Vector2f at, float damage, DamageType type,
            float emp, Object source, long hitTick) {
        return applyWireAckedHit(victim, at, damage, type, emp, source, false, true, hitTick);
    }

    /** The H4 clamped presenter — the same idiom with the
     *  engine door's two presentation switches exposed: {@code bypassShields} (a script DoT tick
     *  lands on the hull whatever the shield does — vanilla {@code DisintegratorEffect.dealDamage}
     *  never consults it — and a bypassing apply adds no shield flux, so the flux headroom
     *  arming is skipped) and {@code playSound} (a DoT tick is silent in vanilla; the swarm loop
     *  is its cue). */
    float applyWireAckedHit(ShipAPI victim, Vector2f at, float damage, DamageType type,
            float emp, Object source, boolean bypassShields, boolean playSound, long hitTick) {
        if (victim == null || plugin.engine == null || !remoteBodyFullyTreated(victim)) {
            return -1f;
        }
        float pre = victim.getHitpoints();
        // A "double overload" (the end event rides >= the
        // pose grace BEHIND the server's hit, so the replica's flux ALREADY carries it — Brawler 84: wire
        // flux 1387 -> 2642 at tick 11790, the apply at 11795 added the same hit AGAIN) — the wire-acked
        // FLUX idiom = WireAckedFluxWindow (carved OUT so the own-shield presenter
        // shares it; the headroom WHY, the one-shot law and the residue class live on that class). Unarmed
        // (null) the apply still runs — presentation lane.
        WireAckedFluxWindow flux = bypassShields ? null : WireAckedFluxWindow.arm(victim, damage, type);
        float dealt = 0f;
        plugin.hullLane.beginWireAckedApply(hitTick);
        boolean armorArmed = snapshotWireAckedArmor(victim); // The armor twin, restored below
        try {
            plugin.engine.applyDamage(victim, at, damage, type, emp, bypassShields, false, source, playSound);
        } finally {
            // The wire-acked idiom for HULL (the flux twin is below): the hit is
            // already in the wire, so the engine's subtraction is presentation only — the number,
            // flash and sound fired inside applyDamage (DamageWitness read hpB/hpA there, nothing
            // is concealed). Until now it stood until the next reconcile write = a one-frame bar dip
            // (162825: hpB=414.8 hpA=214.8, ~10 % of the bar), permanent at knob != apply. MEASURE
            // dealt FIRST (the conv-end row's apply= token), then restore through mirrorHull (alive
            // guard + [1,max] clamp) and reverse the ledger's record ONLY when the restore landed,
            // so the next absorbFrame folds nothing (a bare restore reads as an unexplained heal).
            try {
                float post = victim.getHitpoints();
                dealt = Math.max(0f, pre - post);
                if (dealt > 0f && victim.isAlive() && !victim.isHulk()) {
                    plugin.mirrorHull(victim, pre);
                    if (victim.getHitpoints() > post) {
                        plugin.hullLane.undoWireAckedApply(); // counts predict_foe_hull_wire_acked_restored
                    }
                }
            } catch (Throwable ignore) {
                // presentation lane: an unrestored dip is returned by the next reconcile write
            }
            plugin.hullLane.endWireAckedApply();
            if (flux != null) {
                boolean restored = flux.restore();
                if (flux.stoppedOverload) {
                    wireAckedOverloadResidue++;
                }
                if (restored) {
                    wireAckedFluxRestores++;
                }
            }
            if (armorArmed) {
                restoreWireAckedArmor(victim);
            }
        }
        return dealt; // measured BEFORE the restore — pre - getHitpoints() reads 0 after it
    }

    /** Wire-acked flux idiom counters (the foe-fluxflags-meter line): restores = applies
     *  that ran the headroom+restore window; residue = local overloads the window still had to
     *  stop (a max flux below the hit's shield flux — the engine's synchronous one-shots fired). */
    int wireAckedFluxRestores;
    int wireAckedOverloadResidue;

    /** The wire-acked ARMOR idiom's state and
     *  its round-line counters (the foe-fluxflags-meter line, beside the flux twin's): restored =
     *  applies whose restore wrote ≥ 1 cell back; restoredHp = Σ (pre − post) over those cells
     *  (a non-finite difference — a NaN post — adds 0, so the Σ is never poisoned); faults = a
     *  snapshot that threw (a ragged grid: never armed, the strip STOOD), a restore that found the
     *  grid reshaped or threw mid-loop (the strip stood, wholly or partly), or a decal sync that
     *  threw AFTER the writes (the grid WAS restored — that apply also counts {@code restored}, so
     *  a fault alone never reads as "restored"); nested = a snapshot refused while an outer one is
     *  armed (the outer restore rewrites the whole grid, so a same-victim inner strip is undone — a
     *  covered case, not a fault; a DIFFERENT inner victim's strip stands, visible here; re-entry is
     *  unreachable). The fault
     *  semantics + the nested split are as stated above. The scratch is ONE flat row-major copy
     *  ({@code x·h+y}, the {@link ArmorCellLane} ordinal) grown to the largest grid seen — no
     *  per-hit allocation. */
    private float[] wireAckedArmorScratch = new float[0];
    private int wireAckedArmorW;
    private int wireAckedArmorH;
    private boolean wireAckedArmorArmed;
    int wireAckedArmorRestored;
    double wireAckedArmorRestoredHp;
    int wireAckedArmorFaults;
    int wireAckedArmorNested;

    /**
     * Copy the victim's WHOLE armor grid into
     * the reused scratch before the wire-acked apply. Whole grid, not the engine's 21-cell mask
     * around {@code at}: the presenter's point is the facing-surface point, not the engine's
     * resolved hit cell (the armor step picks the cell itself), so only the whole grid is exact
     * for whatever cells the engine touches; the cost is one copy of ≤ a few hundred floats per
     * wire-acked hit (an own-missile / mine end — never per frame). Returns true when a
     * snapshot is armed for {@link #restoreWireAckedArmor}; false for no grid, or a nested call
     * while one is armed (counted {@code wireAckedArmorNested}, not a fault — the outer snapshot is
     * kept and its restore covers the inner strip). Total.
     */
    private boolean snapshotWireAckedArmor(ShipAPI victim) {
        if (wireAckedArmorArmed) {
            wireAckedArmorNested++;
            return false;
        }
        try {
            ArmorGridAPI grid = victim.getArmorGrid();
            float[][] cells = grid != null ? grid.getGrid() : null;
            if (cells == null || cells.length == 0 || cells[0] == null) {
                return false;
            }
            int w = cells.length;
            int h = cells[0].length;
            if (wireAckedArmorScratch.length < w * h) {
                wireAckedArmorScratch = new float[w * h];
            }
            for (int x = 0; x < w; x++) {
                System.arraycopy(cells[x], 0, wireAckedArmorScratch, x * h, h);
            }
            wireAckedArmorW = w;
            wireAckedArmorH = h;
            wireAckedArmorArmed = true;
            return true;
        } catch (Throwable t) {
            wireAckedArmorFaults++;
            return false;
        }
    }

    /**
     * WHY: the wire-acked apply is PRESENTATION
     * only (the engine's number, flash and sound), and its hull + flux were already restored to the
     * wire values; the armor grid was not. By the ≥ one-pose-grace design the server's AWSD cell
     * section for this very hit is already in, so the engine's strip was a SECOND strip of the same
     * hit — and {@link ArmorCellLane}'s per-frame diff then stamped it as a local bite and its
     * keep-local branch HELD it (the double-strip class; every own-missile / mine
     * end). Write back, through the engine's own {@code setArmorValue}, every cell the apply
     * changed; within the frame, so the cell lane's next diff sees no bite. Upward writes do not
     * repaint natively, so ONE decal batch follows — the same two ShipAPI calls
     * {@link ArmorCellLane#maintain} makes for its upward writes (the decal law; the lane has no
     * package hook to fold into, so this is its own batch, once per restoring hit). Total.
     */
    private void restoreWireAckedArmor(ShipAPI victim) {
        wireAckedArmorArmed = false;
        try {
            ArmorGridAPI grid = victim.getArmorGrid();
            float[][] cells = grid != null ? grid.getGrid() : null;
            int w = wireAckedArmorW;
            int h = wireAckedArmorH;
            if (cells == null || cells.length != w || cells[0] == null || cells[0].length != h) {
                wireAckedArmorFaults++; // the height too (the ACL twin's check) — refused before any write
                return;
            }
            float[] pre = wireAckedArmorScratch;
            double hp = 0d;
            boolean wrote = false;
            long ackId = plugin.wireIdForLocalShip(victim); // THIS apply's strip consumes the
            float mic = grid.getMaxArmorInCell();    // section stamps it restores over — else the
            // next LOCAL hit at the spot keys a false replay on a Δ the wire already spent
            for (int x = 0, i = 0; x < w; x++) {
                float[] col = cells[x];
                for (int y = 0; y < h; y++, i++) {
                    float post = col[y];
                    if (Float.compare(post, pre[i]) != 0) { // a NaN post is restored too (ACL's diffs never see it); a STANDING NaN (NaN → NaN) is no change
                        grid.setArmorValue(x, y, pre[i]);
                        double d = pre[i] - post;
                        hp += Double.isFinite(d) ? d : 0d;
                        if (d > 0d) {
                            plugin.hullLane.armorCells().consumeStrip(ackId, i, (float) d, mic); // consume the section stamp
                        }
                        wrote = true;
                    }
                }
            }
            if (wrote) {
                // counted BEFORE the syncs — a sync throw after the writes still restored the grid
                wireAckedArmorRestored++;
                wireAckedArmorRestoredHp += hp;
                victim.syncWithArmorGridState();
                victim.syncWeaponDecalsWithArmorDamage();
            }
        } catch (Throwable t) {
            wireAckedArmorFaults++;
        }
    }

    /** Forget a body's treatment ledger entry. The station band drop calls it
     *  after clearing the dropped row's {@code capActive}: the orphaned cap is then inert forever,
     *  so a fresh band may install exactly ONE new (live) cap on the same body. */
    void forgetRemoteBodyTreatment(ShipAPI ship) {
        if (ship != null) {
            plugin.remoteBodyTreated.remove(ship);
        }
    }

    /** The OWN-mount un-adopt's release: a player-piloted module must
     *  leave the replay-driven set or the same-side hold would fight the pilot ((g) carves out
     *  OTHER seats' mounted bodies instead — those stay wire-driven). One-frame hold flags
     *  already asserted this frame simply lapse. */
    void unregisterReplayDriven(ShipAPI ship) {
        if (ship == null || !plugin.replayDrivenShips.remove(ship)) {
            return;
        }
        unregisterReplayDrivenWeapons(ship);
    }

    /** Bodies pruned from the replay-driven walk (dead) — census/meter term. */
    int replayDrivenPruned;
    /** Own-team carriers registered replay-driven WITHOUT the per-body lock (the live bay kill switch held). */
    int carrierLockSkips;

    /**
     * The carrier-lock decision, pure: a carrier ({@code bays > 0}) on the authority's own
     * team takes NO lock while the window's bay kill switch is latched — the lock would not stop a bay
     * (the kill switch already does) and would mark the hull "mothballed" on the tab map, grey its
     * Transfer button and force RETREAT, so a vacated own carrier would stop being a transfer target.
     * Every other carrier (a foe's, or any carrier under an un-latched window) keeps the lock.
     */
    static boolean carrierLockSkipped(int bays, boolean killSwitchLatched, int owner, int sideAuthority) {
        return bays > 0 && killSwitchLatched && owner == sideAuthority;
    }

    /** {@link SubstepHoldAI}'s per-sub-step replay-driven predicate. */
    boolean isReplayDriven(ShipAPI ship) { return plugin.replayDrivenShips.contains(ship); }

    /** Census rider: the replay-driven set's size (the per-frame hold walk's length). */
    int replayDrivenSize() {
        return plugin.replayDrivenShips.size();
    }

    /** The slew meter's weapon-keyed WeakHashMap never self-clears — the engine
     *  retains every ever-deployed Ship (CFM all-ever-deployed list, never pruned), so a dead
     *  body's weapons stay strongly reachable. Release them once, O(weapons). */
    private void unregisterReplayDrivenWeapons(ShipAPI ship) {
        List<WeaponAPI> weapons = ship.getAllWeapons();
        if (weapons != null) {
            for (int w = 0; w < weapons.size(); w++) {
                plugin.sameSideMountLastRel.remove(weapons.get(w));
            }
        }
    }

    /**
     * Source fix — mark a replay-driven body: local autonomous fire is held from THIS frame
     * on (the bind-frame seam included; the per-frame loop in {@code advance()} re-asserts the
     * one-frame flag every live frame thereafter). See the {@code replayDrivenShips} field comment for
     * the full decode + faithfulness argument.
     */
    void registerReplayDriven(ShipAPI ship) {
        plugin.replayDrivenShips.add(ship);
        ship.setHoldFireOneFrame(true);
        // A replay-driven carrier's launch bays would otherwise mint LOCAL fighter
        // wings (the whole bay advance is gated on !controlsLocked, so the wing
        // mint never runs; server-born fighters arrive as growth RAPP rows instead). Latch ONCE at
        // mint: the getter short-circuits permanently, nothing engine-side unsets
        // it — no per-frame re-assert. SYSTEM drone launchers are NOT lock-gated (trigger-bound
        // residue: a puppet DRONE_LAUNCHER hull still births phantom drones).
        // The window-wide REFIT_MODE_ADVANCE latch (OWN_BAY_MINT_MODE) now covers
        // every bay including these; the lock stays as the per-body belt-and-braces for FOE carriers
        // and for any window WITHOUT the latch (ownBayMint=off / meter: there it is the only guard).
        // An OWN-team carrier under the LIVE latch takes no lock — under the kill switch
        // the lock buys nothing for bays and only costs: the tab-map "mothballed" X,
        // the greyed Transfer button, forced RETREAT orders
        // and the yellow band — so a vacated own carrier (a seat's transfer source) stays re-takeable.
        // Own-team test = getOwner() == sideAuthority() on every client (the north one is side 1); the
        // latch read is the plugin's live mirror, never the knob constant (an attach that skipped the
        // served init tail registers replicas with the latch OFF and must keep the lock).
        int bays = ship.getNumFighterBays();
        if (bays > 0) {
            String hull = ship.getHullSpec() != null ? ship.getHullSpec().getHullId() : "?";
            if (carrierLockSkipped(bays, PredictReconcileCombatPlugin.bayKillSwitchLatched,
                    ship.getOwner(), plugin.sideAuthority())) {
                carrierLockSkips++;
                PilotWatchLog.info("predict carrier-lock: SKIPPED own-side replay-driven carrier hull=" + hull
                        + " bays=" + bays + " (bay kill switch latched: no X, transfer open; skips="
                        + carrierLockSkips + ")");
            } else {
                ship.setControlsLocked(true);
                PilotWatchLog.info("predict carrier-lock: controlsLocked latched on replay-driven carrier"
                        + " hull=" + hull + " bays=" + bays + " owner=" + ship.getOwner()
                        + " (local launch-bay mint suppressed; fighters arrive server-born)");
            }
        }
        if (!plugin.autofireSuppressLogged) {
            plugin.autofireSuppressLogged = true;
            PilotWatchLog.info("predict autofire-suppress: ACTIVE (replay-driven ships fire only via"
                    + " replayed discharges; engine hold-fire gate re-asserted per frame)");
        }
    }

    /**
     * The per-frame hold-fire re-assert over every replay-driven body, plus the
     * SAME-SIDE fire-only hold. History: an earlier fix toggled every
     * autofiring same-side group OFF (the ship-level one-frame hold alone leaked 173
     * discharges at the {@code seenReplayShipProj} meter) — but an autofire-OFF group's turret
     * tracker is fed a null target every frame, so every same-side replica
     * turret froze at its slot rest angle while discharges replayed at wire bearings ("pointing
     * aside, shooting forward"). The engine's native per-group AutofireAIPlugin is
     * the ONLY aim channel for non-beam replica turrets, so suppression must block FIRE while
     * leaving autofire ON: per-frame, per-NON-BEAM-weapon {@code setForceNoFireOneFrame(true)} —
     * the flag is read-and-cleared at the top of weapon.advance, gates every
     * fire mode incl. chargeup start, is read by NO aim path (slew fully lives), and a held group
     * costs nothing (no flux/ammo/glow/sound). A committed non-interruptible burst
     * ignores both hold flags (the engine re-forces it to completion), so a weapon
     * caught {@code isFiring()} is truncated with the public {@code stopFiring()} — worst case a
     * slipped burst renders ≤1 frame. NON-BEAM ONLY: forceNoFire WINS over forceFire inside
     * weapon.advance, and the beam relay force-FIRES wire-FIRING beam mounts — the two
     * flag sets must stay disjoint. SAME-SIDE ONLY: the primary foe replica's
     * isAutofiring feeds the enemy-reticle AUTOFIRE rows and the maintainFoeAutofire
     * mirror; foe bodies keep the ship-level hold posture unchanged. Replayed fire is unaffected —
     * presented discharges ride spawnProjectile and the FLAG_FIRING beam relay, which bypass the
     * local weapon groups. A dead replica's owner reads 100, so corpses skip the weapon walk.
     * Accepted residual: W-FASTTIME (a time-dilated replica's extra Ship.advance sub-steps read
     * the flags cleared — no fortress hull/merlon system dilates [STALE: the
     * Tesseract temporalshell dilates up to 3×; passes 2..k are held by SubstepHoldAI on the
     * replica's AI seam], and any started burst truncates
     * next pass). Grade stays the {@code seenReplayShipProj} weapon-backed census.
     * The slew meter rides this exact walk (the held set IS the subject set) — see
     * the counter fields + {@code logSameSideHoldMeter}. Two later additions: the hold MECHANISM is
     * knob-selected ({@code cc.predict.sameSideHoldMode} — {@code legacyoff} restores the
     * earlier group toggle-off verbatim as a premise-A/B arm; the frozen
     * claim was never live-measured), and the {@link SameSideAimLane} SNAP steer
     * rides each weapon visit ({@code sameSideAim.steer} — suspend + setCurrAngle at the wire
     * discharge aim, write-wins; no-op for mounts with no discharge record yet).
     */
    void reassertReplayDrivenHold() {
        for (java.util.Iterator<ShipAPI> it = plugin.replayDrivenShips.iterator(); it.hasNext();) {
            ShipAPI replayShip = it.next();
            if (!replayShip.isAlive()) {
                // A dead body never fires again — drop it from the walk
                // instead of paying contains+hold+owner per frame forever (hulks read owner 100
                // and skipped the weapon walk, but still rode the set: 453 reaped by flight #6's
                // end). Removed-alive bodies leave via unregisterReplayDriven at the reap sites.
                it.remove();
                unregisterReplayDrivenWeapons(replayShip);
                replayDrivenPruned++;
                continue;
            }
            if (plugin.mountedBodies.contains(replayShip)) {
                // ANY seat's mounted module fires through the seat fire
                // lanes (relay/discharge presentation) — the same-side hold must not strangle
                // it. Keyed on the mounted table, never band arithmetic.
                continue;
            }
            replayShip.setHoldFireOneFrame(true);
            if (replayShip.getOwner() != plugin.sideAuthority()) {
                continue;
            }
            if (SAME_SIDE_HOLD_LEGACY_OFF) {
                // Premise arm — the earlier mechanism verbatim:
                // any autofiring group toggled OFF with the engine's own idiom, idempotent via
                // the isAutofiring guard (no strobe), NO beam exclusion (historical fidelity).
                List<WeaponGroupAPI> groups = replayShip.getWeaponGroupsCopy();
                if (groups != null) {
                    for (int g = 0; g < groups.size(); g++) {
                        WeaponGroupAPI wg = groups.get(g);
                        if (wg != null && wg.isAutofiring()) {
                            replayShip.giveCommand(ShipCommand.TOGGLE_AUTOFIRE, null, g);
                        }
                    }
                }
            }
            List<WeaponAPI> weapons = plugin.weaponLists.weapons(replayShip); // per-frame cache
            if (weapons == null) {
                continue;
            }
            // An AIMP-driven ship's rest facing is OWNED by the
            // RemoteAimLane native slew (one owner per quantity) — the discharge snap would
            // re-pin currAngle + re-set suspendAutomaticTurning every frame and defeat it.
            // Fire-hold + meters above stay; only the steer is skipped.
            boolean aimDriven = plugin.remoteAim.drives(replayShip);
            for (int w = 0; w < weapons.size(); w++) {
                WeaponAPI weapon = weapons.get(w);
                if (weapon == null || weapon.isBeam()) {
                    continue; // beams stay relay-owned — forceNoFire would override its forceFire
                }
                if (!SAME_SIDE_HOLD_LEGACY_OFF) {
                    weapon.setForceNoFireOneFrame(true);
                }
                plugin.sameSideHeldWeaponFrames++;
                // The slew meter — ship-relative so the base's own facing motion cancels out.
                float rel = ((weapon.getCurrAngle() - replayShip.getFacing()) % 360f + 540f) % 360f - 180f;
                Float prevRel = plugin.sameSideMountLastRel.put(weapon, rel);
                if (prevRel != null) {
                    float slewDelta = Math.abs(((rel - prevRel + 540f) % 360f) - 180f);
                    if (slewDelta > SLEW_MOVED_DEG) {
                        plugin.sameSideMountMovedFrames++;
                    }
                    if (slewDelta > plugin.sameSideMountMaxDelta) {
                        plugin.sameSideMountMaxDelta = slewDelta;
                    }
                }
                // The snap steer AFTER the meter sample — the meter reads the engine-left
                // angle, the steer is this frame's last write (render sees the steered angle).
                if (!aimDriven) {
                    plugin.sameSideAim.steer(weapon, plugin.lastSnapshotSimTick); // Tick = the hold-expiry clock
                }
                if (!SAME_SIDE_HOLD_LEGACY_OFF && weapon.isFiring()) {
                    weapon.stopFiring();
                    plugin.sameSideStopFireTruncations++;
                }
            }
        }
    }
}
