package cc.starsector.client;

import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.net.ServerWeaponMountState;

/**
 * The ADOPT / RE-BASELINE lane, born as its own collaborator per the NEW-LANE LAW
 * (the DeployFeedGate/TeleportJumpLane at-birth idiom; PRCP keeps thin relays only). Hosts the
 * one-shot round-start world adoption cluster extracted verbatim from the plugin
 * ({@link #adoptInitialWorld} / {@link #hardSnapToPose} / {@link #turnOffTravelDriveAtAdopt})
 * plus the two re-join defect fixes it exists for:
 *
 * <p><b>(56) re-join deploy-burn kill ({@link #onFirstOwnSight}):</b> on a kept-open re-pick the
 * visible burn-in is entirely the PRE-adopt window — vanilla mission auto-deploy engages the
 * hull's traveldrive at burn ≥ 1.0 and the kill only fires at
 * adopt (~130 ms later), so the ship full-burns from the WRONG (deploy) point until the snap;
 * every post-adopt re-latch mechanism is code-ruled-out.
 * Kill it at first own-hull sight instead — re-join-scoped via the consumed
 * {@link CcClientModPlugin#consumeReAdmissionArmed} latch, so the first-join fly-in (a tuned
 * 4 s burn) and respawn/replica fly-ins stay untouched. The adopt-time kill STAYS as the belt.
 * The sight OPENS a per-frame kill window ({@link #maintainPreAdoptBurnKill}, closed at
 * adopt) — the plain one-shot left a visible flare when the drive engaged around/after sight.
 *
 * <p><b>(57) one-shot own-vitals adopt ({@link #adoptOwnVitals}):</b> {@link #hardSnapToPose}
 * carries kinematics only — the fresh re-join hull deploys shields-off/0-flux/full-ammo, and the
 * FIRST resumed UP-intent frame would teach the server that lie (applyShieldBit lowers on the
 * first bit-10=0 frame; serve-step re-application means the server-side truth is still the
 * pre-exit state at adopt, measured first-hand). So BEFORE the (55) hold releases, conform the local
 * hull to the wire pose: shield + phase cloak via the engine-native press (the
 * maintainFoeShield/maintainFoeCloak raise-at-idle / toggle-at-mismatch law, own-scoped —
 * re-pressed at mismatch each held frame: a one-shot press is eaten by any same-frame
 * control-lock), flux via the curr-first pin, ammo/cooldown one-shot per carried mount (v4
 * order contract).
 * CR is deliberately untouched (maintainOwnCr self-heals) and system charge is not on the wire
 * (accepted-divergent residual). Because the shield/cloak press is a queued command — not a
 * synchronous state flip — the {@link cc.starsector.client.net.PredictSession#resumeOwnAuthority}
 * release is DEFERRED until the local state is observed conformed ({@link #maintainPendingResume},
 * bounded fail-open {@link #RESUME_CONFORM_MAX_FRAMES}); releasing at the press frame would let
 * one stale pre-conform UP frame lower the still-up server shield — the exact (57) defect. While
 * deferred the writer keeps dropping UP frames and the serve step keeps re-applying pre-exit
 * truth, so the extra held frames cost nothing server-side. No press issued ⇒ release is
 * immediate at adopt (the first-join path, byte-identical behavior).
 *
 * <p>Fail-soft throughout like every reconcile lane; wire UNCHANGED, server ZERO edits.
 */
final class RejoinRebaselineLane {

    /** Bounded fail-open for the deferred authority release: a swallowed conform press must
     *  never hold own UP intents hostage (~0.5 s at 60 fps; conform normally lands in 1-2). */
    private static final int RESUME_CONFORM_MAX_FRAMES = 30;

    private final PredictReconcileCombatPlugin plugin;

    /** (56) — first non-null own-hull sight consumed (the bind-block relay may pass null early). */
    private boolean firstSightDone;
    /** (56) — the pre-adopt burn-kill WINDOW: armed at first sight on a re-join, closed at
     *  adopt. A live run proved the one-shot-at-sight kill insufficient — the drive was ALREADY
     *  burning at sight (driveWasOn=true; the registry anchor bind waits on the snapshot gate,
     *  ~106 ms after mission init), so a visible flare survived. While the window is open the
     *  per-frame maintain kills the drive whenever it is seen ON (engage-order-proof, ≤1 frame
     *  of plume). First-join never arms ⇒ real burn-ins untouched. */
    private boolean burnKillWindow;
    private int windowKills;
    /** The own hull the registry MATCH-RESET rebuild minted WITH the
     *  fly-in burn ({@code PredictReconcileCombatPlugin.applyMatchReset}, the only marking
     *  site), plus the facing its travel drive latched at spawn and the burn it armed.
     *  That mint stands at the WIRE facing while the server twin flies the same seat in through
     *  {@code midDeploySeat} ({@code FLY_IN_BURN_DUR}); a live run showed the
     *  kill on it as a "shorter fly-in after /wrap" — the kill's target is the
     *  MISSION deploy's latch at the local deploy axis, never a mint whose latch IS the wire facing.
     *  Consumed by the CLEAN adopt only: a failed adopt retries with the marker intact. */
    private ShipAPI flyinMintOwn;
    private float flyinMintFacing;
    private float flyinMintBurnSec;
    /** A mint latch within this many degrees of the adopted wire facing keeps
     *  its drive (the entry-turn excursion the kill exists for was 9°; a fresh-pose mint reads 0.0). */
    static final float FLYIN_LATCH_TOL_DEG = 2f;
    /** (57) — deferred-release state: pending target(s) + frames waited (see class doc). */
    private boolean resumePending;
    private Boolean pendingShieldOn;
    private Boolean pendingCloakOn;
    private int pendingFrames;

    RejoinRebaselineLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * (56) — the re-join deploy-burn kill, relayed from BOTH own-bind sites: the bindShips
     * own-bind block (non-registry) and the spawnRemoteEntities anchor bind (registry shapes —
     * the advance branch routes registry shapes past bindShips, so the original
     * bindShips-only relay was structurally unreachable on the product shape). Fires at
     * most once per mission, and ONLY when this mission init armed a kept-open re-admission
     * (read-and-clear; a first-ever join never set the latch). The hull is only sightable once
     * deployed, and deploy is what engaged the drive — so the kill lands inside the invisible
     * pre-render settle window (build-verified: residual ≤ 1 frame of plume if a render slips
     * ahead). Velocity zeroed with the drive (the SimulatorPluginImpl park idiom, the
     * maintainPreMatchHold precedent): a dead drive still coasts at full-burn speed otherwise.
     */
    void onFirstOwnSight(ShipAPI own) {
        if (own == null || firstSightDone) {
            return;
        }
        firstSightDone = true;
        if (!CcClientModPlugin.consumeReAdmissionArmed()) {
            return;
        }
        burnKillWindow = true; // Stays open until adopt — maintainPreAdoptBurnKill drives it
        try {
            boolean wasOn = own.getTravelDrive() != null && own.getTravelDrive().isOn();
            own.turnOffTravelDrive();
            own.getVelocity().set(0f, 0f);
            hardCutDriveFx(own);
            if (wasOn) {
                windowKills++;
            }
            PilotWatchLog.info("predict rejoin first-sight: PRE-adopt deploy burn KILLED"
                    + " (driveWasOn=" + wasOn + ") — re-join-scoped, adopt snap pending");
        } catch (Throwable ignore) {
            // presentation-side neutralization; never break the watch
        }
    }

    /**
     * (56) — the per-frame pre-adopt burn-kill window (field doc). Relayed from the plugin's
     * every-live-frame block ABOVE the snapshot gate (pre-snapshot hold frames included), with
     * the engine's own player-ship read as the pre-bind fallback — so the earliest possible
     * sight opens the window and any later vanilla drive engage dies the frame it appears,
     * regardless of engage-vs-sight-vs-snapshot ordering. No-op unless a re-join armed it.
     */
    void maintainPreAdoptBurnKill(ShipAPI own) {
        if (own != null && !firstSightDone) {
            onFirstOwnSight(own); // earliest sight = earliest kill; opens the window when armed
        }
        if (!burnKillWindow || own == null) {
            return;
        }
        try {
            if (own.getTravelDrive() != null && own.getTravelDrive().isOn()) {
                own.turnOffTravelDrive();
                own.getVelocity().set(0f, 0f);
                hardCutDriveFx(own);
                windowKills++;
                if (windowKills <= 3) {
                    PilotWatchLog.info("predict rejoin first-sight: pre-adopt burn RE-KILLED #"
                            + windowKills + " (drive engaged inside the window)");
                }
            }
        } catch (Throwable ignore) {
            // presentation-side neutralization; never break the watch
        }
    }

    /**
     * (56) — the OUT-state FX hard-cut. {@code turnOffTravelDrive} toggle-offs the system
     * from ACTIVE into a 2 s OUT chargedown whose every frame keeps feeding the engine glow
     * (4×len/3×wid/2×glow) while {@code isOn()} reads false throughout — the machine-clean
     * yet visibly burning window. {@link ShipSystemAPI#deactivate()} is the engine's OWN
     * flameout hard-cut (state→COOLDOWN, effectLevel→0, drive stats unapplied), and the
     * extendFlame to=0 under the SAME source key zeroes the flame extension the same frame
     * (fader residue multiplies zero). Every caller is armed-re-join-window scoped — the
     * first-join fly-in, respawns and replica lanes never reach it.
     */
    private void hardCutDriveFx(ShipAPI ship) {
        try {
            ShipSystemAPI drive = ship.getTravelDrive();
            if (drive == null) {
                return;
            }
            drive.deactivate();
            ship.getEngineController().extendFlame(drive, 0f, 0f, 0f);
            PilotWatchLog.info("predict rejoin first-sight: drive FX HARD-CUT"
                    + " (deactivate + flame extension zeroed)");
        } catch (Throwable ignore) {
            // presentation-side neutralization; never break the watch
        }
    }

    /**
     * Adopt the server's exact initial world on the first authoritative frame (see
     * the plugin's {@code initialWorldAdopted}): hard-snap the own ship (and, on non-bots shapes,
     * the foe) to their first authoritative pose so the client starts where the scout-faithful
     * server deployed, neutralizing the mission deploy warp-in. After this one shot the normal
     * input-predicted reconcile resumes. The (57) own-vitals adopt runs here, BEFORE the
     * (55) hold release — which is immediate when no conform press was needed, else deferred to
     * {@link #maintainPendingResume}.
     */
    boolean adoptInitialWorld(ShipAPI own, ServerEntityPose ownPose, ShipAPI foe, ServerEntityPose foePose) {
        // (83) CL-8: success is REPORTED now, not assumed — every attempted snap plus the vitals
        // adopt must come back clean or the caller keeps the latch down and retries on the next
        // fresh snapshot (adopt is idempotent absolute-state; the announces name the throw).
        boolean adopted = hardSnapToPose(own, ownPose);
        if (!plugin.registryShape && foe != null && foePose != null) {
            adopted &= hardSnapToPose(foe, foePose);
        }
        // Entry-turn fix: the mission deploy burned a travel drive whose heading
        // LATCHED at the LOCAL deploy facing before the wire connected; the snap overwrites pose
        // only, so the burning drive auto-steers toward the stale latch with player input blocked
        // (the seat-2 9° entry excursion — any seat whose server facing differs from the local
        // PLAYER deploy axis). The server world being adopted has no active drive: turning it off
        // is the last piece of the same warp-in neutralization. Mod-spawned fly-in burns are
        // untouched (they spawn AT the wire facing, so their latch is correct by construction) —
        // that sentence was never code until the fly-in mint marker landed; the match-reset mint is
        // one of them (flyinMintOwn), and the server twin is mid fly-in at the same adopt.
        if (own != null && own == flyinMintOwn) {
            keepOrKillFlyinMintAtAdopt(own, ownPose);
        } else {
            turnOffTravelDriveAtAdopt(own, "own");
        }
        if (!plugin.registryShape && foe != null) {
            turnOffTravelDriveAtAdopt(foe, "foe");
        }
        if (burnKillWindow) {
            // Adopt closes the burn-kill window — post-adopt re-latch is code-ruled-out
            // and the adopt-time kill above stays as the belt (hard-cut included,
            // for a drive that re-engaged and toggled back into OUT right before adopt).
            hardCutDriveFx(own);
            burnKillWindow = false;
            PilotWatchLog.info("predict rejoin first-sight: burn-kill window CLOSED at adopt"
                    + " (kills=" + windowKills + ")");
        }
        adopted &= adoptOwnVitals(own, ownPose); // (57) — must precede the release below
        if (resumePending) {
            PilotWatchLog.info("predict rejoin vitals-adopt: own-authority release DEFERRED"
                    + " until conform (shield=" + pendingShieldOn + " cloak=" + pendingCloakOn
                    + "; fail-open " + RESUME_CONFORM_MAX_FRAMES + " frames)");
        } else if (plugin.session != null) {
            plugin.session.resumeOwnAuthority(); // Release the re-baseline UP-intent hold
        }
        if (adopted) {
            PilotWatchLog.info("predict initial-world: ADOPTED server spawn (scout-faithful) own=("
                    + ownPose.locX() + "," + ownPose.locY() + ")@" + ownPose.facing()
                    + (foePose != null ? " foe=(" + foePose.locX() + "," + foePose.locY() + ")@" + foePose.facing() : "")
                    + " -> mission warp-in neutralized; both engines start in the SAME world");
        } else {
            // (83) CL-8: the old unconditional ADOPTED line printed over a swallowed snap/vitals
            // throw — a never-conceal violation (the r1/r2 announces are the truth rows).
            PilotWatchLog.info("predict initial-world: adopt INCOMPLETE (a snap or vitals throw was"
                    + " announced) -> latch stays down; retrying on the next fresh snapshot");
        }
        if (adopted && own != null && own == flyinMintOwn) {
            flyinMintOwn = null; // Consumed by the CLEAN adopt
        }
        return adopted;
    }

    /**
     * (57) — the one-shot own-vitals adopt (class doc). Every write is the already-proven lane
     * idiom applied once: shield/cloak = the engine-native toggle press (raise only from IDLE,
     * lower only while on; verb partition — the press verb phase-toggles on cloak hulls, so the
     * shield half skips them); flux = curr-first pin + boost-clock settle; ammo/cooldown = the
     * ownlock setters under the v4 mount order contract. Runs at EVERY adopt — on a first join
     * the pose IS the fresh deploy state, so every branch no-ops (behavior unchanged there).
     */
    private boolean adoptOwnVitals(ShipAPI own, ServerEntityPose ownPose) {
        if (own == null || ownPose == null || !own.isAlive()) {
            return true; // nothing adoptable (dead/absent own) — not a failure, no retry value
        }
        try {
            StringBuilder did = new StringBuilder();
            if (ownPose.hasFluxFlags() && own.getPhaseCloak() == null) {
                boolean authOn = (ownPose.fluxFlags() & PredictReconcileCombatPlugin.FLUX_FLAG_SHIELD_ON) != 0;
                ShieldAPI sh = own.getShield();
                if (sh != null && authOn != sh.isOn()
                        && (!authOn || sh.getActiveArc() == 0f)) {
                    Vector2f aim = own.getMouseTarget();
                    if (aim == null) {
                        aim = own.getLocation();
                    }
                    own.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, aim, 0);
                    pendingShieldOn = authOn;
                    resumePending = true;
                    did.append(" shield->").append(authOn ? "ON" : "OFF");
                    // Unfold FACING is not restored (an accepted residual) — self-corrects with aim.
                }
            }
            if (ownPose.hasPhaseCloakState()) {
                ShipSystemAPI cloak = own.getPhaseCloak();
                ShipSystemAPI.SystemState auth = cloak == null ? null
                        : PredictReconcileCombatPlugin.systemStateForCode(ownPose.cloakStateCode());
                if (auth != null) {
                    boolean authOn = auth == ShipSystemAPI.SystemState.IN
                            || auth == ShipSystemAPI.SystemState.ACTIVE;
                    if (authOn != cloak.isOn()) {
                        Vector2f aim = own.getMouseTarget();
                        if (aim == null) {
                            aim = own.getLocation();
                        }
                        own.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, aim, 0);
                        pendingCloakOn = authOn;
                        resumePending = true;
                        did.append(" cloak->").append(authOn ? "ON" : "OFF");
                    }
                }
            }
            if (ownPose.hasFlux()) {
                FluxTrackerAPI ft = own.getFluxTracker();
                if (ft != null) {
                    ft.setCurrFlux(ownPose.currFlux()); // curr-first
                    if (ownPose.hasHardFlux()) {
                        ft.setHardFlux(ownPose.hardFlux());
                    }
                    PredictReconcileCombatPlugin.settleBoostClockAfterFluxPin(own, ft, ownPose.currFlux());
                    did.append(" flux=").append(Math.round(ownPose.currFlux()));
                }
            }
            if (ownPose.hasWeaponMounts()) {
                List<WeaponAPI> weapons = own.getAllWeapons();
                if (weapons != null) {
                    int wrote = 0;
                    for (ServerWeaponMountState mount : ownPose.weaponMounts()) {
                        int idx = mount.mountIndex();
                        if (idx < 0 || idx >= weapons.size()) {
                            continue;
                        }
                        WeaponAPI weapon = weapons.get(idx);
                        if (weapon == null) {
                            continue;
                        }
                        boolean touched = false;
                        if (mount.usesAmmo() && weapon.usesAmmo() && mount.ammo() >= 0) {
                            weapon.setAmmo(Math.min(mount.ammo(), weapon.getMaxAmmo()));
                            touched = true;
                        }
                        float cd = mount.cooldownRemaining();
                        if (Float.isFinite(cd) && cd > 0f) {
                            weapon.setRemainingCooldownTo(Math.max(0f, Math.min(cd, weapon.getCooldown())));
                            touched = true;
                        }
                        if (touched) {
                            wrote++;
                        }
                    }
                    if (wrote > 0) {
                        did.append(" mounts=").append(wrote);
                    }
                }
            }
            // CR: NO work — maintainOwnCr (compiled apply) self-heals. System charge: not on the
            // wire; stays fresh-default (an accepted-divergent residual, revisited only if noticed).
            if (did.length() > 0) {
                PilotWatchLog.info("predict rejoin vitals-adopt:" + did
                        + " (one-shot from ownPose @adopt)");
            }
            return true;
        } catch (Throwable ignore) {
            // presentation-side adopt; never break the watch
            // a thrown vitals-adopt leaves the deploy-lie vitals standing, silently.
            FallbackAnnounce.announceOnce("r1-rejoin-vitals-adopt-threw", "vitals-adopt threw "
                    + ignore.getClass().getSimpleName() + " -> deploy vitals stand");
            return false; // (83) CL-8: reported up — the adopt latch stays down and retries
        }
    }

    /**
     * (57) — the deferred {@code resumeOwnAuthority} release (class doc): per-frame no-op unless
     * a conform press deferred it; releases on observed conformance, on the bounded fail-open, or
     * on the own hull dying under it (the respawn lanes own that path). The re-fix: while
     * deferred, RE-PRESS at mismatch each frame (the maintainFoeShield/maintainFoeCloak
     * toggle-at-mismatch law) — the adopt press is a QUEUED command any same-frame control-lock
     * silently clears (the travel-drive burn lock ate it and
     * the one-shot press fail-opened unconformed). The press frame itself is skipped
     * ({@code pendingFrames == 1}: the adopt press is still queued — the engine consumes
     * commands after this advance, so the earliest a re-press can matter is the next frame).
     */
    void maintainPendingResume(ShipAPI own) {
        if (!resumePending) {
            return;
        }
        pendingFrames++;
        boolean conformed = true;
        try {
            if (pendingShieldOn != null) {
                ShieldAPI sh = own != null ? own.getShield() : null;
                conformed = sh != null && sh.isOn() == pendingShieldOn;
                if (conformed && pendingShieldOn) {
                    // (57) — instant unfold: the engine-native press raises through the
                    // normal 0→full ramp, but server + other clients already carry the pre-exit
                    // FULL arc, so the visible deploy-rate unfold is a purely local lie. One
                    // write of the unfold fraction closes it (the engine never calls
                    // setActiveArc itself; at ==full the ramp never touches it again).
                    // LAW: only AFTER isOn()==true — the OFF path zeroes the fraction per frame.
                    sh.setActiveArc(sh.getArc());
                }
                // Re-press under the state-gated law: raise only from IDLE, lower only while
                // on, never mid-retraction (a skipped frame self-heals at the next idle frame).
                if (!conformed && pendingFrames > 1 && sh != null
                        && (pendingShieldOn ? sh.getActiveArc() == 0f : sh.isOn())) {
                    pressConformToggle(own, "shield");
                }
            }
            if (conformed && pendingCloakOn != null) {
                ShipSystemAPI cloak = own != null ? own.getPhaseCloak() : null;
                conformed = cloak != null && cloak.isOn() == pendingCloakOn;
                if (!conformed && pendingFrames > 1 && cloak != null) {
                    pressConformToggle(own, "cloak"); // same verb — phase hulls toggle the cloak
                }
            }
        } catch (Throwable ignore) {
            conformed = true; // fail-open: a broken read must not hold authority hostage
        }
        if (conformed || pendingFrames >= RESUME_CONFORM_MAX_FRAMES
                || own == null || !own.isAlive()) {
            resumePending = false;
            pendingShieldOn = null;
            pendingCloakOn = null;
            if (plugin.session != null) {
                plugin.session.resumeOwnAuthority();
            }
            PilotWatchLog.info("predict rejoin vitals-adopt: own authority RESUMED after "
                    + pendingFrames + " frame(s) ("
                    + (conformed ? "conformed" : "fail-open timeout — releasing unconformed") + ")");
        }
    }

    /** (57) — the conform re-press (the adoptOwnVitals press verbatim, own-scoped). */
    private void pressConformToggle(ShipAPI own, String what) {
        Vector2f aim = own.getMouseTarget();
        if (aim == null) {
            aim = own.getLocation();
        }
        own.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, aim, 0);
        PilotWatchLog.info("predict rejoin vitals-adopt: conform RE-PRESS (" + what
                + ") at frame " + pendingFrames);
    }

    /**
     * Hard-snap one ship's full kinematic state (location, velocity, facing, angular velocity) to an
     * authoritative pose — the one-shot round-start adoption ({@link #adoptInitialWorld}). Unlike the
     * per-frame position-only reconcile glide, this overwrites velocity + facing too,
     * which is exactly what neutralizes the mission deploy warp-in the native control would otherwise keep.
     * Fail-soft; returns whether the snap ran clean ((83) CL-8 — external callers may ignore it;
     * {@link #adoptInitialWorld} folds it into its latch-worthiness).
     */
    boolean hardSnapToPose(ShipAPI ship, ServerEntityPose pose) {
        if (ship == null || pose == null || !ship.isAlive()) {
            return true; // nothing snappable — not a failure, no retry value
        }
        try {
            Vector2f loc = ship.getLocation();
            if (Float.isFinite(pose.locX()) && Float.isFinite(pose.locY())) {
                loc.x = pose.locX();
                loc.y = pose.locY();
            }
            Vector2f vel = ship.getVelocity();
            if (Float.isFinite(pose.velX()) && Float.isFinite(pose.velY())) {
                vel.x = pose.velX();
                vel.y = pose.velY();
            }
            if (Float.isFinite(pose.facing())) {
                ship.setFacing(pose.facing());
                // An adoption hard-snap is a discontinuity by definition —
                // carry the presented angle across it so it never mints a facing STEP sample
                // (same contract as the teleport hatch).
                plugin.foeFacing.noteSnap(pose.entityId(), pose.facing());
            }
            if (Float.isFinite(pose.angularVelocity())) {
                ship.setAngularVelocity(pose.angularVelocity());
            }
            return true;
        } catch (Throwable ignore) {
            // presentation-side; never break the watch on a correction hiccup
            // 1290 (81) R2: swallowed snap = warp-in NOT neutralized. (83) CL-8: now also
            // reported up, so adoptInitialWorld's ADOPTED line can no longer false-succeed.
            FallbackAnnounce.announceOnce("r2-hard-snap-threw#" + pose.entityId(), "hardSnap threw "
                    + ignore.getClass().getSimpleName() + " -> pose not adopted");
            return false;
        }
    }

    /**
     * fail-soft {@link ShipAPI#turnOffTravelDrive} at world adoption: zeroes the
     * engine's {@code remainingDriveDur} so the next advance fires the drive's ON→OFF edge before
     * its stale-latch auto-steer can turn the snapped hull. Idempotent
     * when the drive is already off; the was-ON log line is the confirming watch's machine signal.
     */
    private void turnOffTravelDriveAtAdopt(ShipAPI ship, String who) {
        if (ship == null) {
            return;
        }
        try {
            boolean wasOn = ship.getTravelDrive() != null && ship.getTravelDrive().isOn();
            ship.turnOffTravelDrive();
            if (wasOn) {
                PilotWatchLog.info("predict initial-world: travel drive was ON at adopt (" + who
                        + ") -> turned OFF; stale deploy heading latch neutralized");
            }
        } catch (Throwable ignore) {
            // presentation-side neutralization; never break the watch
        }
    }

    /**
     * The registry match-reset rebuild records its burn-armed own mint
     * ({@code burnSec >= 1}: the engine's {@code deploy()} travel-drive branch fired, its auto-steer
     * latched at {@code facing}). The next {@link #adoptInitialWorld} on this body keeps the drive
     * when that latch is the wire facing; any other adopt (no marker, another body) kills as before.
     */
    void noteOwnFlyinMint(ShipAPI own, float facing, float burnSec) {
        flyinMintOwn = own;
        flyinMintFacing = facing;
        flyinMintBurnSec = burnSec;
    }

    /** The mint's drive latch is the adopted wire facing (within
     *  {@link #FLYIN_LATCH_TOL_DEG}), so the burning drive auto-steers where the authority is
     *  already heading: nothing stale to kill. Non-finite on either side = stale. */
    static boolean flyinLatchHolds(float mintFacing, float wireFacing) {
        if (!Float.isFinite(mintFacing) || !Float.isFinite(wireFacing)) {
            return false;
        }
        float gap = Math.abs(((mintFacing - wireFacing) % 360f + 540f) % 360f - 180f);
        return gap <= FLYIN_LATCH_TOL_DEG;
    }

    /** The one-shot read of the mint marker at adopt: keep the drive (the wrap fly-in a live run
     *  saw go missing) or fall through to the kill with the reason named. */
    private void keepOrKillFlyinMintAtAdopt(ShipAPI own, ServerEntityPose ownPose) {
        float wireFacing = ownPose != null ? ownPose.facing() : Float.NaN;
        boolean latchHolds = flyinLatchHolds(flyinMintFacing, wireFacing);
        boolean driveOn = false;
        try {
            driveOn = own.getTravelDrive() != null && own.getTravelDrive().isOn();
        } catch (Throwable ignore) {
            // fail-soft read; a throw here reads as OFF and the kill path below stays idempotent
        }
        if (latchHolds && driveOn) {
            PilotWatchLog.info("predict initial-world: travel drive kept ON at adopt (own) — wire-facing"
                    + " fly-in mint, burn=" + flyinMintBurnSec + "s latch=" + flyinMintFacing
                    + " wire=" + wireFacing + " (the server twin is mid fly-in)");
        } else {
            PilotWatchLog.info("predict initial-world: fly-in mint marker present but "
                    + (latchHolds ? "its drive is already OFF" : "its latch is STALE (mint="
                    + flyinMintFacing + " wire=" + wireFacing + " tol=" + FLYIN_LATCH_TOL_DEG + ")")
                    + " -> the travel-drive kill applies");
            turnOffTravelDriveAtAdopt(own, "own");
        }
        // The marker is consumed by adoptInitialWorld on a CLEAN adopt only: a
        // failed snap/vitals adopt retries next snapshot and must re-read it, or the retry's
        // unmarked path kills the kept burn mid fly-in.
    }
}
