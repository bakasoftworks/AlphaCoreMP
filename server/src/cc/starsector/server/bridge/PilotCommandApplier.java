package cc.starsector.server.bridge;

import static cc.starsector.server.bridge.EngineCombatBinding.MOUNT_SELECTED_FLAG;
import static cc.starsector.server.bridge.EngineCombatBinding.asBoolean;
import static cc.starsector.server.bridge.EngineCombatBinding.enumName;
import static cc.starsector.server.bridge.EngineCombatBinding.numberAsFloat;
import static cc.starsector.server.bridge.EngineCombatBinding.numberAsInt;

import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.bridge.EngineCombatBinding.EngineBindingException;
import cc.starsector.server.bridge.EngineCombatBinding.PilotApplyResult;
import cc.starsector.server.transform.PilotShipWindow;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The pilot-command apply lane of {@link EngineCombatBinding}, carved into its own file:
 * one faithful
 * {@link cc.starsector.server.runtime.PilotIntentFrame} per owned ship per tick becomes engine commands
 * through the reflective handles ({@link #applyPilotIntent} and its {@code applyXBit} fan-out), plus the
 * group-identity / mount-readiness / fire-driver factories that {@link PilotApplyState} consumes. Every
 * method body is the binding's own (sed-extracted at the carve, never retyped); what changed is only WHERE
 * the handles live: the command/read handles this lane needs are copied straight from the
 * {@link EngineCombatBinding.Builder} (the binding no longer holds the 24 pilot-only ones), {@code engine}
 * and {@code reflection} come from the binding, and the binding-owned shared seams ({@code ownedShipIds},
 * {@code orbitDroneDrive}, {@code lastEffectiveStepAmount}, {@code reinjectShimByShip}, the
 * invoke / ship-lookup / AI-suppression / weapon-group helpers) are reached through {@code binding}.
 * <p>WHY a second reader of the Builder rather than a handle-holder record: the Builder already IS the handle
 * manifest, so a future pilot bit lands as one Builder setter + one field + one {@code applyXBit} HERE and
 * leaves the binding untouched (the growth axis: bits 9→12 each inflated the binding).
 * Constructed by the binding at the end of its own constructor (after {@code reflection} exists); serve
 * thread only, like the binding; the binding's public delegators keep every caller and contract row as is.
 */
final class PilotCommandApplier {

    private final EngineCombatBinding binding;
    private final Object engine;
    private final EngineReflectionBinding reflection;
    /** The mount-probe family (resolver, readiness / fire-lock probes, adopt driver) — own file. */
    private final MountProbeLane mountProbes;

    // ---- handles this lane reads (declarations verbatim from the binding; Builder-sourced in the ctor) ----
    private final Method fluxIsOverloaded; // nullable: flux view unavailable
    private final Object weaponCommandFire; // nullable: intent unsupported; on the real engine = ShipCommand.USE_SELECTED_GROUP (the name predates it)
    private final Method getPlayerShip;
    private final Method shipGetFluxTracker; // nullable: flux view unavailable
    private final Method fluxGetFluxLevel; // nullable: flux view unavailable
    private final Method fluxIsVenting; // nullable: flux view unavailable
    private final Method shipGetShield; // nullable: shield read optional (shieldless hull or unbound)
    private final Method shieldIsOn; // nullable: shield read optional
    private final Method shieldGetActiveArc; // nullable: shield-law idle read — null fails the shield APPLY lane closed
    private final Method shipGetAllWeapons; // nullable: weapon-mount view unavailable
    private final Method weaponGetSpec;
    private final Method weaponSpecGetWeaponId;
    private int fluxGateReadErrors; // Latch flux-gate read throws (log once, then count)
    private final Method shipGetSystem; // nullable: ship-system view unavailable
    private final Method systemGetState; // nullable: ship-system view unavailable
    private final Method systemIsActive; // nullable: ship-system view unavailable
    private final Method systemIsOn; // nullable: ship-system view unavailable
    private final Method systemGetCooldownRemaining; // nullable: ship-system view unavailable
    private final Method systemGetAmmo; // nullable: ship-system view unavailable
    private final Method playerShipGiveCommand; // nullable: command sink unavailable
    private final Object movementCommandAccelerate; // nullable: intent unsupported
    private final Object movementCommandDecelerate; // nullable: intent unsupported
    private final Object movementCommandStrafeLeft; // nullable: intent unsupported
    private final Object movementCommandStrafeRight; // nullable: intent unsupported
    private final Object movementCommandAccelerateBackwards; // nullable: reverse-thrust unsupported
    private final Object weaponCommandSelectGroup; // nullable: intent unsupported
    private final Object weaponCommandVent; // nullable: flux-vent command unsupported
    private final Object weaponCommandToggleShield; // nullable: shield hold-replay unsupported
    private final Method shipGetPhaseCloak; // nullable: phase guard unavailable => shield lane FAILS CLOSED (same verb toggles the cloak)
    private final Object weaponCommandPullBackFighters; // nullable: carrier fighter-mode mirror unsupported (lane fails closed)
    private final Method shipIsPullBackFighters; // nullable: ShipAPI.isPullBackFighters read (lane fails closed)
    private final Method shipGetCustomData; // nullable: ShipAPI.getCustomData read (the human-pilot mark is never written)
    private int pilotMarkReadErrors; // getCustomData reads that threw (logged once, then counted; each latches the state unbound)
    private final Method shipSetCustomData; // nullable: ShipAPI.setCustomData, the mark's first write per tenure (creates the hull's map; null = put into the getter's map)
    private int pilotMarkLostWrites; // first writes the getter did not read back by identity (logged once, counted per state; retried next frame)
    private Method shipGetChildModulesCopy; // ShipAPI.getChildModulesCopy, resolved at the first re-point on the hull's class (re-resolved for another class)
    private int pilotShipModuleFaults; // module walks that failed at a re-point (logged once, then counted; the root still answers itself)
    private final Method playerShipUseSystem; // nullable: system activation unsupported
    private final Method systemCanBeActivated; // nullable: ShipSystemAPI.canBeActivated guard unavailable
    private final Method shipSetFacing; // nullable: authoritative facing write unavailable
    private final Constructor<?> vector2fCtor; // nullable: Vector2f(float,float) arg unavailable
    private final Method shipGetMouseTarget; // nullable: aim-carry seam — live mouseTarget vector unavailable
    private final Method vector2fSet; // nullable: in-place Vector2f.set(float,float) (live field is ALIASED — mutate in place, never replace)
    private final Method shipIsTravelDriveOn; // nullable: travel-drive read (concrete Ship.isTravelDriveOn) unavailable => facing pin never suppressed
    /** The dwell fire-lock gate, built once (no per-tick allocation); unbound reads answer unlocked. */
    private final java.util.function.Predicate<Object> dwellFireLockGate =
            ship -> fireLockReadsBound() && fireLockedNow(ship);
    private final Method shipGetWeaponGroupsCopy; // nullable: group/autofire mirror unavailable
    private final Method shipGetSelectedGroupAPI; // nullable: group mirror unavailable
    private final Method weaponGroupIsAutofiring; // nullable: autofire mirror unavailable
    private final Method weaponGetSlot; // nullable: group-identity slot read unavailable
    private final Method weaponSlotGetId; // nullable: group-identity slot read unavailable
    // WeaponGroupAPI.getWeaponsCopy — already a Builder seam for the tube
    // mirror (EngineReflectionBinding); mirrored here for the group-identity member reads.
    private final Method groupGetWeaponsCopy;
    private final Object weaponCommandToggleAutofire; // nullable: autofire toggle unsupported
    private final Method shipSetRetreating; // nullable: retreat apply (ShipAPI.setRetreating) unavailable
    private final Method retreatManeuverEngage; // nullable: vanilla RetreatManeuver engage static (Ship, boolean) unavailable
    private final Method shipTurnOffTravelDrive; // nullable: falling-edge drive-off (concrete Ship.turnOffTravelDrive) unavailable
    private final Method shipIsRetreating; // nullable: hull retreat read (concrete Ship.isRetreating) unavailable => retreat lane falls back to the older edge-only setter law
    private final Method shipSetShipTarget; // nullable: R-key target apply (ShipAPI.setShipTarget) unavailable
    private final Method shipGetShipTarget; // nullable: R-key target read-compare unavailable
    private final Method shipIsFighter; // nullable: fighter-designation reject unavailable

    // ---- lane log counters + caps (moved with the lane; serve thread only) ----
    private int pullBackToggleLogs; // Capped greppable per-issue lines
    // fail-soft: group-count parity warn lines emitted (serve thread only; capped).
    private static final int MAX_GROUP_PARITY_WARNS = 5;
    private int groupParityWarnsEmitted;
    // autofire_mask_toggle attribution lines emitted (serve thread only; capped).
    private static final int MAX_AUTOFIRE_TOGGLE_LOGS = 64;
    private int autofireToggleLogsEmitted;
    // group_identity build/instrument lines emitted (serve thread only; capped —
    // builds are per hull tenure and dumps one-shot per tenure, so the cap survives long soaks).
    private static final int MAX_GROUP_IDENTITY_LOGS = 48;
    private int groupIdentityLogsEmitted;


    // ---- the faithful PilotIntentFrame -> engine apply-path ----
    // Wire bit order — each bit maps to the
    // PUBLIC ShipCommand of the SAME NAME, replayed verbatim. NOT the 6-value ShipCommandSink.Movement
    // enum (different order, no ACCELERATE_BACKWARDS).
    private static final int WIRE_BIT_ACCELERATE = 0;
    private static final int WIRE_BIT_ACCELERATE_BACKWARDS = 1;
    private static final int WIRE_BIT_DECELERATE = 2;
    private static final int WIRE_BIT_TURN_LEFT = 3;
    private static final int WIRE_BIT_TURN_RIGHT = 4;
    private static final int WIRE_BIT_STRAFE_LEFT = 5;
    static final int WIRE_BIT_STRAFE_RIGHT = 6; // package-private: EngineCombatBinding reinject pass bound
    private static final int WIRE_BIT_USE_SYSTEM = 7;
    private static final int WIRE_BIT_VENT_FLUX = 8;
    private static final int WIRE_BIT_RETREATING = 9; // PilotIntentFrame.RETREATING state-mirror
    // Consecutive bit-9 FALSE frames required after a seat re-key/hull
    // loss before a rising edge may engage the retreat drive (~83 ms at 60 Hz — far under human
    // command-UI latency, far over a measured 3-frame stale-flicker).
    static final int RETREAT_EDGE_QUARANTINE_FALSE_FRAMES = 5; // package-private: read by PilotApplyState (own file)
    /** The human-pilot mark's post-re-key ability quarantine: consecutive applied frames with NO ability bit a
     * fresh hull tenure needs before its mask may read non-zero — the bit-9 window, for the bit-9 reason (a
     * client's stale sample from its previous body must not fire an ability on the fresh one). */
    static final int ABILITY_EDGE_QUARANTINE_CLEAR_FRAMES = RETREAT_EDGE_QUARANTINE_FALSE_FRAMES;
    /**
     * The hull customData key the serve writes for a seat's OWN hull: {@code int[]{seatId, abilityMask, 0, flags}}
     * ({@code [2]} = role, 0 = the serve's own mark; {@code [3]} bit 0 = the pilot's MagicLib decision is followed).
     * Append-only: a reader of the first two slots takes length &gt;= 2.
     */
    public static final String PILOT_MARK_KEY = "cc_client.pilot";
    private static final int WIRE_BIT_SHIELD_ON = 10; // PilotIntentFrame.SHIELD_ON state-mirror (originally a law-split apply: OMNI hold / non-OMNI mismatch-edge — since superseded, see applyShieldBit)
    private static final int WIRE_BIT_PHASE_CLOAK = 11; // PilotIntentFrame.PHASE_CLOAK state-mirror (mismatch-edge toggle on cloak hulls)
    private static final int WIRE_BIT_PULL_BACK_FIGHTERS = 12; // PilotIntentFrame.PULL_BACK_FIGHTERS state-mirror (mismatch-edge toggle, carrier fighter mode)
    private static final int PULL_BACK_TOGGLE_LOG_MAX = 64;

    /**
     * Built by {@link EngineCombatBinding#EngineCombatBinding} as its LAST act: the handle copies below are
     * the binding's former constructor lines verbatim (same null policy — a fake binding with null handles
     * keeps its dark-lane contract), {@code engine}/{@code reflection} are read off the binding because the
     * binding builds {@code reflection} itself.
     */
    PilotCommandApplier(EngineCombatBinding binding, EngineCombatBinding.Builder b) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.engine = binding.engine;
        this.reflection = binding.reflection;
        this.fluxIsOverloaded = b.fluxIsOverloaded;
        this.weaponCommandFire = b.weaponCommandFire;
        this.getPlayerShip = Objects.requireNonNull(b.getPlayerShip, "getPlayerShip");
        this.shipGetFluxTracker = b.shipGetFluxTracker;
        this.fluxGetFluxLevel = b.fluxGetFluxLevel;
        this.fluxIsVenting = b.fluxIsVenting;
        this.shipGetShield = b.shipGetShield;
        this.shieldIsOn = b.shieldIsOn;
        this.shieldGetActiveArc = b.shieldGetActiveArc;
        this.shipGetAllWeapons = b.shipGetAllWeapons;
        this.weaponGetSpec = b.weaponGetSpec;
        this.weaponSpecGetWeaponId = b.weaponSpecGetWeaponId;
        this.shipGetSystem = b.shipGetSystem;
        this.systemGetState = b.systemGetState;
        this.systemIsActive = b.systemIsActive;
        this.systemIsOn = b.systemIsOn;
        this.systemGetCooldownRemaining = b.systemGetCooldownRemaining;
        this.systemGetAmmo = b.systemGetAmmo;
        this.playerShipGiveCommand = b.playerShipGiveCommand;
        this.movementCommandAccelerate = b.movementCommandAccelerate;
        this.movementCommandDecelerate = b.movementCommandDecelerate;
        this.movementCommandStrafeLeft = b.movementCommandStrafeLeft;
        this.movementCommandStrafeRight = b.movementCommandStrafeRight;
        this.movementCommandAccelerateBackwards = b.movementCommandAccelerateBackwards;
        this.weaponCommandSelectGroup = b.weaponCommandSelectGroup;
        this.weaponCommandVent = b.weaponCommandVent;
        this.weaponCommandToggleShield = b.weaponCommandToggleShield;
        this.shipGetPhaseCloak = b.shipGetPhaseCloak;
        this.weaponCommandPullBackFighters = b.weaponCommandPullBackFighters;
        this.shipIsPullBackFighters = b.shipIsPullBackFighters;
        this.shipGetCustomData = b.shipGetCustomData;
        this.shipSetCustomData = b.shipSetCustomData;
        this.playerShipUseSystem = b.playerShipUseSystem;
        this.systemCanBeActivated = b.systemCanBeActivated;
        this.shipSetFacing = b.shipSetFacing;
        this.shipIsTravelDriveOn = b.shipIsTravelDriveOn;
        this.shipGetWeaponGroupsCopy = b.shipGetWeaponGroupsCopy;
        this.shipGetSelectedGroupAPI = b.shipGetSelectedGroupAPI;
        this.weaponGroupIsAutofiring = b.weaponGroupIsAutofiring;
        this.weaponGetSlot = b.weaponGetSlot;
        this.weaponSlotGetId = b.weaponSlotGetId;
        this.groupGetWeaponsCopy = b.groupGetWeaponsCopy;
        this.weaponCommandToggleAutofire = b.weaponCommandToggleAutofire;
        this.vector2fCtor = b.vector2fCtor;
        this.shipGetMouseTarget = b.shipGetMouseTarget;
        this.vector2fSet = b.vector2fSet;
        this.shipSetRetreating = b.shipSetRetreating;
        this.retreatManeuverEngage = b.retreatManeuverEngage;
        this.shipTurnOffTravelDrive = b.shipTurnOffTravelDrive;
        this.shipIsRetreating = b.shipIsRetreating;
        this.shipSetShipTarget = b.shipSetShipTarget;
        this.shipGetShipTarget = b.shipGetShipTarget;
        this.shipIsFighter = b.shipIsFighter;
        this.mountProbes = new MountProbeLane(this, binding, b); // LAST: every handle copy above precedes it
    }

    /**
     * Apply one faithful {@link cc.starsector.server.runtime.PilotIntentFrame} to the
     * owned engine ship through the adapter boundary — a 1:1 port of the proven predict gate's per-tick
     * apply (scout {@code run-headless-bootstrap-scout.ps1:41284-41391},
     * {@code own-ship-facing-ad-driven-not-cursor}/{@code scout-as-reference-oracle}). Order matches the
     * gate exactly: replay the 5 TRANSLATION movement bits (DROP the TURN bits 3,4) via the engine's own
     * {@code giveCommand(ShipCommand)} → pin facing to the wire-carried {@code ownShipFacingDeg} (so thrust
     * integrates in the heading the human actually flew, not the cursor) → held-driven {@code useSystem()}
     * (re-armed each tick bit-7 is held while the system is not active, guarded by {@code canBeActivated()})
     * → rising-edge {@code VENT_FLUX} → aimed FIRE on {@code fireHeld} (the fire-discharge port,
     * see {@link #applyFireBit}: the velocity-desync
     * ROOT-CAUSE fix, since firing drops the zero-flux speed boost the client drops). Only the discharge
     * MECHANIC is modelled here; the damage/kill PROOF stays a separate deferred task. All reflection,
     * the bitmask fan-out, and the edge logic stay
     * behind this boundary; the runtime serve loop makes ONE call per owned ship per tick and owns the
     * {@link PilotApplyState}. Rejects (no engine touch) when {@code simTick<0}, the frame's ship is not
     * owned, the command sink is unbound, or the ship cannot be resolved. Every APPLIED frame (past the refield
     * gate) also pushes the pilot's held keys, mouse buttons and control keys into the player-ship holder for the
     * hull's redirected key polls; a rejected or gated frame pushes nothing.
     */
    public PilotApplyResult applyPilotIntent(
            cc.starsector.server.runtime.PilotIntentFrame frame, long simTick, PilotApplyState state) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(state, "state");
        if (simTick < 0L || playerShipGiveCommand == null
                || !binding.ownedShipIds.contains(frame.shipId())) {
            return PilotApplyResult.rejected();
        }
        Object ship = shipForWireIdOrNull(frame.shipId());
        if (ship == null) {
            return PilotApplyResult.rejected();
        }
        maybeSuppressShipAi(ship);
        // A frame sampled off the seat's DEAD hull presses nothing on the refielded
        // one — the whole apply is withheld (AI suppression above stays: a fake's build AI would
        // otherwise run the hold). Law + release proof = PilotApplyState.refieldGateHolds.
        if (state.refieldGateHolds(frame.frameSeq(), simTick, frame.shipId())) {
            return PilotApplyResult.rejected();
        }
        int held = frame.heldControls();
        // W3 drone-orbit Shape 1: while the station's own ring
        // drives this armed drone seat, the movement lane yields wholesale (mask zeroed
        // pre-replay, facing unpinned below — the all-turret drone hull rides the orbit while
        // its gyro'd turrets track player aim); system/vent/retreat/shield/fire/aim lanes stay
        // live. dt = the advance latch (fixed-step; NaN-safe inside the drive).
        boolean orbitDriven =
                binding.orbitDroneDrive.driveIfArmed(frame.shipId(), ship, binding.lastEffectiveStepAmount);
        int movementMask = orbitDriven ? 0 : replayTranslationBits(ship, held);
        int movement = Integer.bitCount(movementMask);
        // While the engine's travel drive is flying this hull (seat fly-in
        // burn / retreat), the ENGINE owns the heading — vanilla blocks player commands for the
        // whole drive window (CombatState.advance gates on getTravelDrive().isOn()/
        // shouldBlockCommands()), and Ship.advance latches the burn direction from getFacing() at
        // drive-ON. Pinning here would clobber the engine's away-from-entry-edge 270/90 with the
        // seat client's STALE (death-pose) facing — the client has not seen the fresh hull yet, so
        // its intent stream still reports the corpse's attitude and the pollution self-reinforces
        // (the "bizarre direction"). Facing lane ONLY is gated here: translation/aim/
        // system/vent are still issued through the burn (the engine's own command filter refuses
        // whatever the drive blocks). Fire is NOT live through the burn: the press
        // lanes issue USE_SELECTED_GROUP, which the drive's noFiring lock refuses inside the engine's
        // Ship, and the adopt lane waits on the fire-lock probe. Pilot facing authority
        // resumes the tick the drive ends.
        boolean facingPinned = !orbitDriven && !travelDriveOn(ship)
                && pinFacing(ship, frame.ownShipFacingDeg());
        // Pin the engine's own per-frame mount-slew target to the carried aim world point
        // BEFORE the world advance (the serve loop applies intents, then advances), so the engine's
        // Ship.advance weapon-group loop slews the manual groups toward the player's real aim instead
        // of the never-written default (world origin) — a measured 12° fault.
        boolean aimPinned = pinMouseTarget(ship, frame.aimWorldX(), frame.aimWorldY());
        // Track the last PRESENT wire mask (the gate's stale-baseline source), then
        // adjudicate the mask-staleness gate ONCE for this frame. While it holds, the shape TRIO
        // below (selection mirror / tube conform / autofire diff) is withheld as a SET — the three
        // desync together at a cross-variant refield (client samples the dead hull until its respawn
        // edge); movement/facing/aim/system/vent/retreat/shield/fire lanes stay live.
        if (frame.autofireGroupsMask() >= 0) {
            state.lastWireMask = frame.autofireGroupsMask();
        }
        // Instrument stamp 1: the tenure's FIRST applied frame — the hull
        // provably exists here (this frame resolved it), so the dump reads the live deploy-state
        // group identity the two-branch verdict needs. One-shot per tenure, capped in the sink.
        if (!state.groupIdentityDeployDumped) {
            state.groupIdentityDeployDumped = true;
            logGroupIdentity("first_apply", ship, frame.shipId(), simTick, state);
        }
        boolean maskGateHolds = maskStalenessGateHolds(frame, simTick, state);
        // Mirror the client's LIVE selected-group ordinal onto the hull via the
        // engine's own giveCommand(SELECT_GROUP) — read-compare-issue (select(true) has side effects in the
        // engine's WeaponGroup), and ONLY while no declaration is being served (the serve window
        // owns selection; the mirror re-asserting the tick after consume/expire IS the re-assert).
        // Fix (b): the serve window exists on the DECLARED lane only — under held
        // nothing serves the FIFO, so a pending declaration (unreachable post-fix (a); this is the
        // belt) must never freeze the hull's selection (a recorded lockup rode this gate).
        // The wire ordinal is CLIENT-space — translate through the
        // tenure's group-identity map ONCE at the seam (identity map = byte-identical).
        boolean groupMirrored = !maskGateHolds
                && !state.declaredServeOwnsSelection()
                && mirrorSelectedGroup(ship, mapWireGroupToServer(
                        ship, frame.shipId(), frame.selectedWeaponGroup(), state));
        // Conform the selected ALTERNATING group's active-tube pointer to
        // the wire-carried ordinal at fire-quiescent settled ticks — re-selects of the already-
        // selected group are wire-invisible EDGES the selection mirror above is structurally blind
        // to — the root cause. A void statement; observable =
        // state.tubeOrdinalConformed.
        if (!maskGateHolds) {
            reflection.mirrorActiveTubeOrdinal(ship, frame, state);
        }
        // Diff the client's per-group isAutofiring() bitmask against the hull and
        // issue the engine's own click-toggle per mismatch. mask < 0 = ABSENT (legacy/synthetic
        // producer) — never diff, or mask 0 would toggle OFF deploy-default autofire groups.
        // The desired state folds in the engine's personal-control law, keyed on the WIRE
        // selection (this call runs whenever the mask-staleness gate is not holding, and serveDeclaredFire
        // transiently forces the declared group selected on the hull — the wire value is the pilot's
        // real selection).
        int autofireToggles = maskGateHolds ? 0 : applyAutofireMask(ship, frame.shipId(),
                frame.autofireGroupsMask(), frame.selectedWeaponGroup(), simTick, state);
        // Before/after deltas of the press counters feed the fast-time
        // reinject record below — the shim must re-issue exactly what pass 1 actually pressed
        // (the engine's own capture/reinject filter law).
        int cancelPressesBefore = state.systemCancelPresses;
        int ventPressesBefore = state.ventsIssued + state.ventRePresses;
        applyPilotMark(ship, held, state.seatId, state); // pre-advance: a mod's every-frame plugin reads it this tick
        // The pilot's held keys, mouse buttons and control keys, AFTER the mark
        // (its first write publishes the mirror entry this lands in), so the hull's own redirected key polls hear
        // this frame's input inside this tick's advance window; the mark's clear edges drop it with the entry. The
        // seat's keymap (read once per hull tenure at the mark's re-point) rides with it.
        PilotShipWindow.pushPilotState(ship, frame.keyCodes(), frame.mouseButtons(), frame.controlKeys(),
                state.pilotKeymapLo, state.pilotKeymapHi);
        boolean systemActivated = applySystemBit(ship, held, simTick, frame.shipId(), state);
        boolean ventIssued = applyVentBit(ship, held, state);
        // Retreat bit-9 (edge-only drive engage + level re-assert of the flag)
        // and R-key ship target designation (read-compare-issue), BEFORE the fire lanes
        // (a designated target this frame is visible to the engine's own autofire preference).
        boolean retreatIssued = applyRetreatBit(ship, held, state);
        boolean targetApplied = applyShipTarget(ship, frame);
        // Shield bit-10 / bit-11 cloak dispatch (the phase-guard slot
        // routes cloak hulls to the mismatch-edge cloak lane, shield hulls to the shield press law).
        // A statement, deliberately NOT a PilotApplyResult widening — the state counters
        // (shieldHoldTicks / cloakToggleTicks) + round meter rows carry the diagnostics instead.
        applyShieldBit(ship, held, frame, state);
        applyPullBackBit(ship, held, state);
        OwnCarrierZeroFluxProbe.sample(ship, frame.shipId(), simTick); // probe row; OFF = one static test
        // Exactly ONE lane issues FIRE per tick (no double-fire). Declared lane: the wire
        // fireHeld bit stays carried + recorded but no longer presses the trigger — declarations do.
        // Class routing: the lane CHOICE is per-tick — a pending
        // (post-filter, therefore class-S) declaration wins the tick; else held serves, EXCLUDED
        // while the selected group is class-S (declared owns those discharges; the group-level
        // held press would double-fire real ammo/flux). A pending decl WINS the
        // tick but only PRESSES when its named mount is dischargeable (headDeclarationToPress) —
        // the earlier premise "a serve costs one yielded tick" was falsified in a live run
        // (the serve strobed group-wide every tick until consume/timeout).
        boolean fireIssued;
        if (state.classRouted()) {
            if (state.pendingDeclarationCount() > 0) {
                fireIssued = serveDeclaredFire(ship, simTick, state);
            } else {
                // selectedGroupClassS routes on SERVER-space ordinals
                // (resolver + demotedGroups both live there) — translate the wire ordinal ONCE
                // here, which also closes the demotedGroups space inconsistency.
                int beltGroup = mapWireGroupToServer(
                        ship, frame.shipId(), frame.selectedWeaponGroup(), state);
                if (state.selectedGroupClassS(beltGroup)) {
                    // Held excluded; seq still tracked so a later class-H selection never arms stale.
                    state.observeFireEdge(frame.fireEdgeSeq(), frame.selectedWeaponGroup(), simTick, false);
                    // decl-recency belt: this is exactly the held-excluded shape — a held
                    // trigger here with no declarations arriving would be a silent ship.
                    // the belt only counts ticks the engine's own press gate says could discharge
                    // (probe skipped entirely on released-trigger ticks — the belt resets there).
                    boolean beltHeld = cc.starsector.server.runtime.PilotFireMapper
                            .map(frame.toFireIntent()).fire();
                    state.noteClassSHeldTick(beltHeld, beltHeld && mountProbes.selectedClassSGroupDischargeable(
                            ship, beltGroup, frame.aimWorldX(), frame.aimWorldY()));
                    fireIssued = false;
                } else {
                    fireIssued = applyFireBit(ship, frame, simTick, state);
                }
            }
        } else {
            fireIssued = state.declaredFireLane()
                    ? serveDeclaredFire(ship, simTick, state)
                    : applyFireBit(ship, frame, simTick, state);
        }
        // Overwrite this ship's fast-time reinject record with what pass 1
        // ACTUALLY issued this tick — the engine's own playerShip capture/reinject filter set
        // (movement ordinals / VENT / USE_SYSTEM / FIRE+aim; NEVER the shield/cloak toggle,
        // hold-fire, or pull-back-fighters). The shim replays it on
        // passes 2+ only (isInFastTimeAdvance-gated). No shim installed = no record, zero cost.
        FastTimeReinjectShim shim = binding.reinjectShimByShip.get(ship);
        if (shim != null) {
            boolean systemPressed = systemActivated || state.systemCancelPresses > cancelPressesBefore;
            boolean ventPressed = (state.ventsIssued + state.ventRePresses) > ventPressesBefore;
            shim.record(movementMask, systemPressed, ventPressed, fireIssued,
                    frame.aimWorldX(), frame.aimWorldY());
        }
        return new PilotApplyResult(
                true, movement, facingPinned, aimPinned, systemActivated, ventIssued, fireIssued,
                groupMirrored, autofireToggles, retreatIssued, targetApplied);
    }

    /**
     * Replay each set TRANSLATION bit (0,1,2,5,6 — turn bits 3,4 dropped) via the public
     * giveCommand lane. Returns the MASK of bits actually replayed (the fast-time
     * shim re-issues exactly this set on passes 2+; bit count = the old issue tally).
     */
    private int replayTranslationBits(Object ship, int held) {
        int issuedMask = 0;
        for (int bit = 0; bit <= WIRE_BIT_STRAFE_RIGHT; bit++) {
            if ((held & (1 << bit)) == 0) {
                continue;
            }
            if (bit == WIRE_BIT_TURN_LEFT || bit == WIRE_BIT_TURN_RIGHT) {
                continue; // facing is pinned to ownShipFacingDeg below; replaying turn bits would double-rotate
            }
            Object command = wireMovementCommand(bit);
            if (command == null) {
                continue; // an unbound movement constant (e.g. reverse on a fake binding) is skipped
            }
            invoke(playerShipGiveCommand, ship, "ShipAPI.giveCommand", command, null, 0);
            issuedMask |= 1 << bit;
        }
        return issuedMask;
    }

    /** The PUBLIC {@code ShipCommand} constant for a wire movement bit (turn bits return null — dropped). */
    Object wireMovementCommand(int wireBit) { // package-private: EngineCombatBinding reinject pass
        switch (wireBit) {
            case WIRE_BIT_ACCELERATE:
                return movementCommandAccelerate;
            case WIRE_BIT_ACCELERATE_BACKWARDS:
                return movementCommandAccelerateBackwards;
            case WIRE_BIT_DECELERATE:
                return movementCommandDecelerate;
            case WIRE_BIT_STRAFE_LEFT:
                return movementCommandStrafeLeft;
            case WIRE_BIT_STRAFE_RIGHT:
                return movementCommandStrafeRight;
            default:
                return null;
        }
    }

    /**
     * True while the engine's travel drive is flying the ship (seat fly-in burn / retreat) — the
     * window vanilla blocks player commands for; false when the seam is unbound (fake bindings
     * never bind it, so their facing-pin behavior is byte-identical).
     */
    private boolean travelDriveOn(Object ship) {
        return shipIsTravelDriveOn != null
                && Boolean.TRUE.equals(invoke(shipIsTravelDriveOn, ship, "Ship.isTravelDriveOn"));
    }

    /** Pin the owned ship's heading to the wire-carried real facing; false when the seam is unbound. */
    private boolean pinFacing(Object ship, float ownShipFacingDeg) {
        if (shipSetFacing == null || !Float.isFinite(ownShipFacingDeg)) {
            return false;
        }
        invoke(shipSetFacing, ship, "CombatEntityAPI.setFacing", ownShipFacingDeg);
        return true;
    }

    /**
     * Aim pin: set the owned ship's LIVE {@code mouseTarget} vector to the wire-carried aim
     * world point — the vanilla idiom (player {@code CombatState} and every AI write this field via an
     * in-place {@code .set} each frame). The engine's own {@code Ship.advance} weapon-group loop then
     * slews the manual/selected groups toward it within arc/turn-rate limits (the engine aims
     * its own mounts; no {@code setCurrAngle} hack). MUST mutate in place — the engine's field is aliased
     * (shield.advance holds the same object), so replacing the reference would strand the aliases.
     * False when the seams are unbound (fake binding) or the live vector is unavailable this tick.
     */
    private boolean pinMouseTarget(Object ship, float aimWorldX, float aimWorldY) {
        if (shipGetMouseTarget == null || vector2fSet == null
                || !Float.isFinite(aimWorldX) || !Float.isFinite(aimWorldY)) {
            return false;
        }
        Object target = invoke(shipGetMouseTarget, ship, "ShipAPI.getMouseTarget");
        if (target == null) {
            return false;
        }
        invoke(vector2fSet, target, "Vector2f.set", aimWorldX, aimWorldY);
        return true;
    }

    /**
     * Held-driven ship-system activation (the system-engage fix; supersedes the rising-edge +
     * bounded-{@code retryBudgetTicks} window of the earlier design). The wire bit-7 reflects "the human's system
     * is engaged" — the predict client sets it while its OWN {@code isActive()} is true
     * ({@code PredictReconcileCombatPlugin}) — so the server drives its owned ship's system to MATCH:
     * every tick the bit is held while the system is not yet active, re-invoke {@code useSystem()} guarded
     * by {@code canBeActivated()} (when that handle is bound). This is the engine's own "hold the key"
     * buffered-input behavior, NOT a force-activate: {@code canBeActivated()} refuses during cooldown / an
     * unmet precondition, and once the system latches active we stop pressing until it ends and the held
     * bit re-arms the NEXT cooldown cycle. The prior finite window disarmed (~0.5 s) while bit-7 was still
     * held, so after the first jets cycle the server never re-engaged — a measured run showed the server
     * {@code isActive=false} at tick 3300 while the client's jets were boosting (+50 maxSpeed), forcing the
     * reconcile to fight. Returns true on a tick this method invoked {@code useSystem()} to ACTIVATE.
     *
     * <p><b>Cancel mirror:</b> bit-7 is now sampled from the client's
     * {@code isOn()} (= IN|ACTIVE exactly), so a LOW bit while the server's own system
     * {@code isOn()} means the human CANCELLED a toggle system mid-window (vanilla double-tap cancel:
     * ACTIVE + canBeDeactivated → OUT). The mirror is a
     * mismatch driver in the bit-10 shield shape: ONE {@code useSystem()} press per mismatched tick;
     * the ENGINE's own gates decide legality (a non-toggle system consumes presses only at IDLE —
     * mid-cycle presses are true no-ops, so jets are unaffected; OUT is never pressed because
     * {@code isOn()} is already false there, so the zero-cooldown-toggle restart class is unreachable;
     * a refused press clears the engine's edge latch — so one press per
     * tick is a fresh vanilla mash edge, safe). FAIL-CLOSED: {@code systemIsOn == null} (unbound fake /
     * partial binding) keeps the lane activation-only — without the isOn read there is no mismatch
     * basis and a blind press could cancel a window the client still holds. Cancel presses count in
     * {@code state.systemCancelPresses} (round meter {@code system_cancel_pressed=}), never in the
     * {@code systemActivated} result.
     */
    private boolean applySystemBit(Object ship, int held, long simTick, long wireId,
            PilotApplyState state) {
        if (playerShipUseSystem == null || shipGetSystem == null) {
            return false;
        }
        if ((held & (1 << WIRE_BIT_USE_SYSTEM)) == 0) {
            // Bit LOW: the human's system is NOT in IN|ACTIVE. Cancel mirror — if the
            // server's own system still isOn(), press once this tick (engine legality decides the rest).
            Object sys = null;
            boolean sysRead = false;
            if (state.sysHoldEdgeTick >= 0) {
                // Falling edge. A hold that never produced an activation is the silent-
                // refusal class this trace exists for — disclose it with the post-release state.
                long edgeTick = state.sysHoldEdgeTick;
                state.sysHoldEdgeTick = -1L;
                if (!state.sysHoldAccepted) {
                    sys = invoke(shipGetSystem, ship, "ShipAPI.getSystem");
                    sysRead = true;
                    if (sys != null) {
                        sysintRow(state, "died", simTick, wireId, ship, sys,
                                " held=" + (simTick - edgeTick));
                    }
                }
            }
            if (systemIsOn == null) {
                return false; // fail closed: no isOn read = no mismatch basis — activation-only lane
            }
            if (!sysRead) {
                sys = invoke(shipGetSystem, ship, "ShipAPI.getSystem");
            }
            if (sys == null) {
                return false; // no primary system on this ship
            }
            if (Boolean.TRUE.equals(invoke(systemIsOn, sys, "ShipSystemAPI.isOn"))) {
                // pre-press server system state on the row — the suspect is
                // exactly this press landing during a late server IN|ACTIVE and killing the lunge.
                sysintRow(state, "cancel", simTick, wireId, ship, sys, "");
                invoke(playerShipUseSystem, ship, "ShipAPI.useSystem");
                state.systemCancelPresses++;
            }
            return false; // a cancel press is metered, never reported as an activation
        }
        Object sys = invoke(shipGetSystem, ship, "ShipAPI.getSystem");
        if (sys == null) {
            return false; // no primary system on this ship
        }
        boolean pressEdge = state.sysHoldEdgeTick < 0;
        if (pressEdge) {
            state.sysHoldEdgeTick = simTick;
            state.sysHoldAccepted = false;
        }
        if (systemIsActive == null) {
            // fail-closed, symmetric with the isOn leg above: without the isActive read a
            // press could land during IN|ACTIVE — the held-button shape. Unreachable via the
            // factory's one-failure-domain resolve; guards hand-built Builders.
            if (pressEdge) {
                sysintRow(state, "edge", simTick, wireId, ship, sys, " canAct=?");
            }
            return false;
        }
        boolean active = Boolean.TRUE.equals(invoke(systemIsActive, sys, "ShipSystemAPI.isActive"));
        if (active) {
            state.sysHoldAccepted = true; // engaged during this hold — not a dead press
            if (pressEdge) {
                sysintRow(state, "edge", simTick, wireId, ship, sys, " canAct=n/a-active");
            }
            return false; // already engaged — let it run; the held bit re-arms after it ends + cooldown
        }
        boolean canActivate = systemCanBeActivated == null
                || Boolean.TRUE.equals(invoke(systemCanBeActivated, sys, "ShipSystemAPI.canBeActivated"));
        if (pressEdge) {
            sysintRow(state, "edge", simTick, wireId, ship, sys,
                    " canAct=" + (systemCanBeActivated == null ? "?" : String.valueOf(canActivate)));
        }
        if (!canActivate) {
            return false; // engine refuses now (cooldown / precondition) — wait; the bit is still held
        }
        // "accept" asserts DELIVERY-at-canAct (useSystem issued this tick),
        // NOT verified activation — a latency cd-marginal press can pass canAct yet produce no
        // ON transition/ammo drop; activation ground truth is the DOWN sampler's rows.
        sysintRow(state, "accept", simTick, wireId, ship, sys,
                " held=" + (simTick - state.sysHoldEdgeTick));
        invoke(playerShipUseSystem, ship, "ShipAPI.useSystem");
        state.sysHoldAccepted = true;
        return true;
    }

    /** Cap on pilot-sysint rows per seat-state (≈3 rows per human press + cancels — generous). */
    private static final int SYSINT_ROW_CAP = 240;

    /**
     * Per-press system-intent trace row (stdout, the {@code pilot-dmg-taken}
     * write-only shape). One single-line row per press EDGE (with the server-side refusal basis:
     * state/cooldown/ammo/flux/isOn/isActive/canBeActivated), per ACCEPT (activation press issued,
     * {@code held=} ticks from edge), per DIED hold (released with no activation — the silent-refusal
     * class), and per cancel-mirror press (pre-press server system state — the late-
     * accept-kill suspect). Bounded: {@link #SYSINT_ROW_CAP} rows then one {@code capped} row;
     * fail-soft: any reflective read failure disables the trace for this seat (one disclosed row),
     * never the apply path. No wire-derived text at all — the ship id is the numeric wire id.
     */
    private void sysintRow(PilotApplyState state, String kind, long simTick, long wireId,
            Object ship, Object sys, String extra) {
        if (state.sysintDead || state.sysintRows > SYSINT_ROW_CAP) {
            return;
        }
        if (state.sysintRows == SYSINT_ROW_CAP) {
            state.sysintRows++;
            System.out.println("pilot-sysint capped cap=" + SYSINT_ROW_CAP + " ship=" + wireId);
            return;
        }
        state.sysintRows++;
        StringBuilder sb = new StringBuilder(160);
        sb.append("pilot-sysint ").append(kind)
                .append(" tick=").append(simTick)
                .append(" ship=").append(wireId);
        try {
            sb.append(" state=").append(systemGetState == null
                    ? "?" : enumName(systemGetState.invoke(sys)));
            sb.append(" cd=").append(systemGetCooldownRemaining == null
                    ? "?" : String.valueOf(numberAsFloat(
                            systemGetCooldownRemaining.invoke(sys), "system.getCooldownRemaining")));
            sb.append(" ammo=").append(systemGetAmmo == null
                    ? "?" : String.valueOf(numberAsInt(systemGetAmmo.invoke(sys), "system.getAmmo")));
            Object tracker = shipGetFluxTracker == null
                    ? null : shipGetFluxTracker.invoke(ship);
            sb.append(" flux=").append(tracker == null || fluxGetFluxLevel == null
                    ? "?" : String.valueOf(numberAsFloat(
                            fluxGetFluxLevel.invoke(tracker), "getFluxLevel")));
            sb.append(" isOn=").append(systemIsOn == null
                    ? "?" : String.valueOf(Boolean.TRUE.equals(systemIsOn.invoke(sys))));
            sb.append(" isActive=").append(systemIsActive == null
                    ? "?" : String.valueOf(Boolean.TRUE.equals(systemIsActive.invoke(sys))));
        } catch (Throwable t) {
            state.sysintDead = true;
            System.out.println("pilot-sysint disabled err=" + t.getClass().getSimpleName());
            return;
        }
        sb.append(extra);
        System.out.println(sb);
    }

    /**
     * Vent mismatch law. KEEPS the rising-edge press (issue VENT_FLUX once on a
     * fresh press; the engine's {@code ventFlux()} self-guards; counted {@code state.ventsIssued}),
     * and ADDS a mismatch re-press: while bit 8 is HELD and the server hull is NOT venting
     * ({@code getFluxTracker().isVenting()}), press once per tick (counted
     * {@code state.ventRePresses}). Why: the client's bit mirrors its own engine's ACCEPTED vent
     * state, so a held bit with a non-venting server hull means the server's press was eaten (or a
     * refused first press) — the re-press is vanilla key-mash and converges when the engine's own
     * gate opens. <b>Observed in play: a vent works during phase,
     * but it takes the ship out of phase cloak</b> — the player lane vents while phased
     * and the vent force-drops the cloak (upkeep-refusal-while-venting forces OUT);
     *
     * there is no cloak-off-then-vent
     * ordering anywhere, so this law needs no ordering verb. Strobe-safe: vent is NOT a toggle — a
     * press while already venting is a strict engine no-op (vents end only at
     * flux==0 / API stopVenting), and there is NO falling-edge
     * verb (vanilla vent is non-cancellable). Return value semantics UNCHANGED: {@code ventIssued}
     * = rising-edge only (external meters/asserts keep meaning); re-presses live in the state
     * counters only. Flux handles null (fakes without flux seams) ⇒ fall back to today's pure
     * rising-edge law — fail-open to the proven behavior, byte-identity kept.
     */
    private boolean applyVentBit(Object ship, int held, PilotApplyState state) {
        if (weaponCommandVent == null) {
            return false;
        }
        boolean ventBit = (held & (1 << WIRE_BIT_VENT_FLUX)) != 0;
        boolean issued = false;
        if (ventBit && !state.prevVentBit) {
            invoke(playerShipGiveCommand, ship, "ShipAPI.giveCommand(VENT_FLUX)", weaponCommandVent, null, 0);
            state.ventsIssued++;
            issued = true;
        } else if (ventBit && shipGetFluxTracker != null && fluxIsVenting != null) {
            Object tracker = invoke(shipGetFluxTracker, ship, "ShipAPI.getFluxTracker");
            Boolean venting = tracker == null ? null
                    : asBoolean(invoke(fluxIsVenting, tracker, "FluxTrackerAPI.isVenting"));
            if (Boolean.FALSE.equals(venting)) {
                invoke(playerShipGiveCommand, ship, "ShipAPI.giveCommand(VENT_FLUX)",
                        weaponCommandVent, null, 0);
                state.ventRePresses++;
            }
        }
        state.prevVentBit = ventBit;
        return issued;
    }

    /**
     * Retreat apply, SPLIT LANE — bit-9 carries the wire-carried
     * engine-ACCEPTED retreat state (the client samples {@code own.isRetreating()}, never the
     * keypress, so the bit only rises on retreats its vanilla capture gate accepted —
     * in the engine's CombatState), and the wire value is a per-frame LEVEL. The old
     * edge-only apply consumed that LEVEL as an EDGE — the dual failure: two structurally
     * unconsumable disagreement paths ((a) a guard-refused clear latched the hull retreating
     * forever; (b) a server-side engine reversal — vent auto-cancel,
     * in the engine's Ship — left the hull stopped while the client predicted retreat
     * flight; no server→client snapshot path carries {@code retreating}, so nothing reconciled
     * either). The split:
     *
     * <p><b>DRIVE ENGAGE stays RISING-EDGE-ONLY</b> — the vanilla engage static
     * on RetreatManeuver, called with (ship, false), the EXACT vanilla
     * keypress call ({@code false} = engage unconditionally; the client's engine already ran the
     * reachability gate before its bit rose). Load-bearing, not decoration: retreat flight is
     * drive-powered with no held movement keys. Never re-issued per-tick — a re-issue would
     * re-arm {@code turnOnTravelDrive(100f)} and rewrite dir/wasOn every tick
     * (the dir/wasOn writes run outside the {@code turnOnTravelDrive}
     * self-guard). FALLING edge still calls {@code turnOffTravelDrive()} (zeroes
     * {@code remainingDriveDur}; the {@code isTravelDriveOn()} tracker flips on the NEXT advance
     * of the engine's Ship), vanilla's own call order.
     *
     * <p><b>THE FLAG is a LEVEL RE-ASSERT</b> — {@code setRetreating(<wire level>,false)} on
     * every tick the server hull's {@code isRetreating()} DISAGREES with the wire bit.
     * True-direction passes the engine's retreat-flag guard unconditionally
     * ({@code !drive || !retreating || requested}); false-direction is refused while the drive tracker
     * is still on — including on the faithful falling-edge tick itself, because the same-frame
     * {@code turnOffTravelDrive()} cannot flip the tracker (the "arrives with the
     * drive already off" premise was REFUTED) — and the re-assert then retries each
     * disagreeing tick until the drive is genuinely off, converging one advance later. Path (b)
     * heals the FLAG ONLY: the level re-assert restores
     * {@code retreating} the tick after an engine reversal with NO second drive engage —
     * the reversal's own {@code turnOffTravelDrive()} stands, and engage is rising-edge-only —
     * so retreat state and map-edge-removal semantics recover while the drive-powered flight
     * does not resume until a fresh wire rising edge. Non-edge re-asserts count in
     * {@code state.retreatReasserts} (round meter {@code retreat_reasserts=}; 0 = no disagreement
     * shape occurred); edge-tick setter calls are vanilla order, not counted. {@code retreatIssued}
     * stays EDGE-ONLY (the vent-lane precedent: external meters keep meaning). Map-edge removal
     * is the engine's own {@code CombatFleetManager.advance()} scan, never a server call.
     *
     * <p>Unbound seams: the 3 verb handles resolve as ONE failure domain (fakes = no-op, edge
     * memory untouched, byte-identical); a bound verb triple with a null/unreadable
     * {@code isRetreating} (hand-built Builder) falls back to the older edge-only setter law —
     * fail-open to the proven behavior, the vent-lane flux-null precedent.
     *
     * <p><b>DIVE FREEZE</b> — while {@link PilotApplyState#latchDiveOwnsRetreat}
     * is set (the seat hull's emergency dive started), the ENTIRE lane is frozen: a committed
     * dive is engine-imposed retreat state, and the level re-assert here was the live
     * non-removal driver — it cleared the dive script's per-frame {@code setRetreating(true)}
     * each tick BEFORE the reaper's read (the within-frame flap; a live run measured
     * {@code retreat_reasserts=2981} == dive-to-round-end ticks). Hull loss unfreezes.
     */
    private boolean applyRetreatBit(Object ship, int held, PilotApplyState state) {
        if (shipSetRetreating == null || retreatManeuverEngage == null || shipTurnOffTravelDrive == null) {
            return false;
        }
        boolean retreatBit = (held & (1 << WIRE_BIT_RETREATING)) != 0;
        if (state.diveOwnsRetreat) {
            // A committed emergency dive owns retreat state — NO writes (the
            // level re-assert was clearing the script's per-frame setRetreating(true) before
            // the CFM reaper's read every tick, so the dived hull was never removed; full
            // mechanism at latchDiveOwnsRetreat). Edge memory still follows the wire so a
            // latch lifted without hull loss can never mint a phantom edge.
            state.retreatDiveFreezeTicks++;
            if (state.retreatDiveFreezeTicks == 1) {
                System.out.println("EngineCombatBinding: retreat lane frozen (dive latch) — "
                        + "bit-9 writes withheld until hull loss");
            }
            state.prevRetreatBit = retreatBit;
            return false;
        }
        if (state.retreatRearmPending) {
            if (retreatBit) {
                // A held-TRUE level from the PRE-hull-loss body — honoring it would
                // setRetreating(true) the just-refielded hull and the engine's map-edge scan would
                // remove it next tick. Wait for one bit-false frame before retreat re-arms.
                state.retreatRearmSkips++;
                state.prevRetreatBit = false;
                return false;
            }
            state.retreatRearmPending = false;
        }
        if (state.retreatEdgeQuarantineFalseLeft > 0) {
            // post-re-key edge quarantine. The wire level is treated as
            // FALSE until K CONSECUTIVE bit-false frames arrive: rising edges cannot engage the
            // drive, and the level re-assert below drives the hull's inherited
            // retreating flag clear (the required next-tick re-assert — the commit-frame clear
            // refused by the engine's retreat-flag guard while the drive still reports on). A TRUE frame
            // restarts the window and bumps the loud retreat_edge_quarantined= round meter.
            if (retreatBit) {
                state.retreatEdgeQuarantined++;
                state.retreatEdgeQuarantineFalseLeft = RETREAT_EDGE_QUARANTINE_FALSE_FRAMES;
            } else {
                state.retreatEdgeQuarantineFalseLeft--;
            }
            retreatBit = false;
        }
        boolean rising = retreatBit && !state.prevRetreatBit;
        boolean falling = !retreatBit && state.prevRetreatBit;
        if (rising) {
            invoke(retreatManeuverEngage, null, "RetreatManeuver.o00000(ship,false)", ship, Boolean.FALSE);
        } else if (falling) {
            invoke(shipTurnOffTravelDrive, ship, "Ship.turnOffTravelDrive");
        }
        Boolean hullRetreating = shipIsRetreating == null ? null
                : asBoolean(invoke(shipIsRetreating, ship, "Ship.isRetreating"));
        if (hullRetreating == null) {
            // No hull truth read: the older edge-only setter law (proven, byte-identical).
            if (rising) {
                invoke(shipSetRetreating, ship, "ShipAPI.setRetreating(true,false)", Boolean.TRUE, Boolean.FALSE);
            } else if (falling) {
                invoke(shipSetRetreating, ship, "ShipAPI.setRetreating(false,false)", Boolean.FALSE, Boolean.FALSE);
            }
        } else if (hullRetreating.booleanValue() != retreatBit) {
            invoke(shipSetRetreating, ship,
                    retreatBit ? "ShipAPI.setRetreating(true,false)" : "ShipAPI.setRetreating(false,false)",
                    Boolean.valueOf(retreatBit), Boolean.FALSE);
            if (!rising && !falling) {
                state.retreatReasserts++; // the disagreement shape live: refused-clear retry or engine reversal
            }
        }
        state.prevRetreatBit = retreatBit;
        return rising || falling;
    }

    /**
     * Shield apply — bit-10 state-mirror of the wire-carried engine-ACCEPTED shield state
     * (the client samples {@code own.getShield().isOn()}, never the keypress, so the overload
     * lockout, the 0.2 s buffered raise, and AUTO_OMNI assist output all ran in the client's OWN
     * engine before the bit could rise). UNIFIED STATE-GATED PRESS LAW,
     * TYPE-INDEPENDENT — the engine pins EVERY shield's charge tracker to FRONT flip semantics
     * (the engine's shield system hardcodes {@code ShieldType.FRONT}; the tracker's OMNI-LEVEL
     * branch is dead code, superseding the falsified type split): a command-present tick
     * FLIPS desired, command-free ticks KEEP state, and raises
     * start only from IDLE (mid-retraction presses are swallowed while
     * the unfold fraction stays frozen). Press iff
     * {@code bit ? (!isOn() && getActiveArc()==0f) : isOn()} — raise only at idle (idle ⇔ that
     * exact predicate: unfold is 0 only at IDLE, frozen &gt;0 through the 0.35 s retraction),
     * lower only while on, NEVER press mid-retraction — the vanilla shield AI's own gating
     * driven by the wire bit. A press eaten by the
     * engine's own lockouts ({@code canUseShields}, overload force-off) self-heals: the
     * mismatch persists and re-presses at the next pressable state = vanilla key-mash
     * convergence. Bit absence with the shield down issues nothing.
     * The aim arg is the frame's carried aim world point: the dispatch passes the command param
     * into {@code shield.advance(true, param)} and {@code getShieldTarget()} is null on puppet
     * hulls, so the carried aim drives FRONT/OMNI facing exactly as the client's own engine did.
     * PHASE DISPATCH (load-bearing): hulls with a phase cloak route to
     * {@link #applyCloakBit} and return — the SAME verb phase-TOGGLES there
     * ({@code phaseCloak.fire()} wins the dispatch), so the shield law never touches a cloak hull
     * and the cloak law never touches a shield hull (verb partition, D-M2-4 both directions; the
     * phase-guard early-return became the cloak lane). {@code getShield()==null}
     * (shieldless hull) is skipped so {@code shieldHoldTicks} stays an issue meter. Unbound seams
     * = no-op byte-identical; an unavailable phase-cloak, isOn, or getActiveArc read FAILS CLOSED
     * (without them the lane could toggle a cloak hull or flip-cancel a mid-retraction shield).
     */
    private void applyShieldBit(
            Object ship, int held, cc.starsector.server.runtime.PilotIntentFrame frame,
            PilotApplyState state) {
        if (weaponCommandToggleShield == null || shipGetPhaseCloak == null
                || vector2fCtor == null) {
            return; // shared fail-closed: no verb / no hull-kind discrimination / no aim reconstruction
        }
        // Verb partition, BOTH directions: a hull with a phase cloak is
        // the CLOAK lane's — the same verb phase-toggles there, so the shield law below must never
        // touch it; and the cloak lane never touches shield hulls — it only ever runs behind this
        // non-null getPhaseCloak() gate. The phase-guard early-return IS this dispatch.
        Object cloak = invoke(shipGetPhaseCloak, ship, "Ship.getPhaseCloak");
        if (cloak != null) {
            applyCloakBit(ship, held, frame, state, cloak);
            return;
        }
        if (shipGetShield == null || shieldIsOn == null || shieldGetActiveArc == null) {
            return; // shield-law reads unavailable — the shield half fails closed
        }
        boolean bit = (held & (1 << WIRE_BIT_SHIELD_ON)) != 0;
        Object shield = invoke(shipGetShield, ship, "ShipAPI.getShield");
        if (shield == null) {
            return; // shieldless hull: keep shieldHoldTicks an issue meter, not a bit meter
        }
        Boolean on = asBoolean(invoke(shieldIsOn, shield, "ShieldAPI.isOn"));
        if (on == null) {
            return; // unreadable state: no press basis — fail closed this tick
        }
        boolean issue;
        if (bit) {
            // Raise gate: only from IDLE (idle ⇔ !isOn && activeArc==0 — the unfold fraction
            // stays frozen >0 through the 0.35s retraction, where a press flip-cancels desired).
            Object arcValue = invoke(shieldGetActiveArc, shield, "ShieldAPI.getActiveArc");
            if (!(arcValue instanceof Number)) {
                return; // unreadable arc: cannot prove idle — fail closed this tick
            }
            issue = !on && ((Number) arcValue).floatValue() == 0f;
        } else {
            issue = on; // Lower gate: only while on (command-free ticks KEEP state engine-side)
        }
        if (!issue) {
            return;
        }
        try {
            Object aimPoint = vector2fCtor.newInstance(frame.aimWorldX(), frame.aimWorldY());
            invoke(playerShipGiveCommand, ship, "ShipAPI.giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK)",
                    weaponCommandToggleShield, aimPoint, 0);
            state.shieldHoldTicks++;
        } catch (InvocationTargetException e) {
            throw new EngineBindingException(
                    "ShipAPI.giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK) aim reconstruction threw",
                    e.getCause() != null ? e.getCause() : e);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new EngineBindingException(
                    "ShipAPI.giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK) aim reconstruction failed", e);
        }
    }

    /**
     * Cloak apply — bit-11 state-mirror of the wire-carried
     * engine-ACCEPTED cloak state (the client samples its own cloak's {@code isOn()} = IN|ACTIVE
     * exactly — flips at toggle-acceptance, so the flux-capacity refusal
     * and cooldown gates already ran client-side; NOT {@code isPhased}, which lags ≤0.25 s through
     * OUT and would double-press down edges). Mismatch-edge law — the FRONT-shield shape of
     * {@link #applyShieldBit}, and the same law on BOTH sides of the wire because the cloak
     * operand is {@code isOn()} on both sides: ONE
     * {@code giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK, aimWorld, 0)} per tick while
     * {@code bit != cloak.isOn()}, raise AND drop. No strobe class: a toggle-during-OUT is
     * REFUSED engine-side (cooldown=2), so mismatch retries
     * through the chargedown are latch-clear no-ops that converge at COOLDOWN-exit (~2 s after
     * OUT ends). The cloak
     * {@code isOn} read invokes the shared {@code systemIsOn} handle ON THE CLOAK OBJECT — the
     * Move-1 precedent ({@link #shipPhaseCloakStatesOrNull}). Fail-closed: null
     * {@code systemIsOn} or an unreadable isOn ⇒ return, zero issues (the caller already guards
     * {@code shipGetPhaseCloak}/{@code weaponCommandToggleShield}/{@code vector2fCtor}). ORDER
     * (D-M3-1): runs after {@link #applyVentBit} by the existing {@code applyPilotIntent} call
     * order — the client's bit falls via the vent-forced OUT the same way the server's will
     * (same-tick semantics aligned). Meter: {@code state.cloakToggleTicks} (round line
     * {@code cloak_toggles=}); {@link PilotApplyResult} NOT widened. A latched
     * dive FREEZES the lane (no toggle, mismatch withheld): {@link PilotApplyState#noteCloakDiveFreeze}.
     */
    private void applyCloakBit(
            Object ship, int held, cc.starsector.server.runtime.PilotIntentFrame frame,
            PilotApplyState state, Object cloak) {
        if (systemIsOn == null) {
            return; // fail-closed: no isOn read = no mismatch basis
        }
        Boolean on = asBoolean(invoke(systemIsOn, cloak, "ShipSystemAPI.isOn(cloak)"));
        if (on == null) {
            return; // unreadable cloak state: no mismatch basis — fail closed this tick
        }
        boolean bit = (held & (1 << WIRE_BIT_PHASE_CLOAK)) != 0;
        if (bit == on) {
            return;
        }
        // the bit-9 freeze's sibling — a dive owns the cloak.
        if (state.noteCloakDiveFreeze()) {
            return;
        }
        try {
            Object aimPoint = vector2fCtor.newInstance(frame.aimWorldX(), frame.aimWorldY());
            invoke(playerShipGiveCommand, ship, "ShipAPI.giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK)",
                    weaponCommandToggleShield, aimPoint, 0);
            state.cloakToggleTicks++;
        } catch (InvocationTargetException e) {
            throw new EngineBindingException(
                    "ShipAPI.giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK) cloak aim reconstruction threw",
                    e.getCause() != null ? e.getCause() : e);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new EngineBindingException(
                    "ShipAPI.giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK) cloak aim reconstruction failed", e);
        }
    }

    /**
     * The carrier FIGHTER-MODE state mirror (first needed by the Anubis seat, the first carrier human seat).
     * Wire bit 12 carries the client's own engine-accepted
     * {@code ShipAPI.isPullBackFighters()} level (Z = {@code ShipCommand.PULL_BACK_FIGHTERS};
     * regroup = true, engage = false; every hull constructs at true). This driver issues ONE
     * {@code giveCommand(PULL_BACK_FIGHTERS, null, 0)} per tick while the hull's level differs
     * from the bit - the {@link #applyCloakBit} mismatch-edge idiom. Faithful + strobe-safe by
     * the engine's own shape: the command is a pure per-command flip,
     * duplicates within one frame collapse to one (type dedup), and stock
     * carrier AI drives the very same command only when current != desired
     * and a per-tick HOLD replay would strobe;
     * this level-diff converges in one tick and then issues nothing. Fighter/escort AIs read the
     * flag directly (and so does bay replacement depletion 0.5x
     * and the engage-mode zero-flux boost), so an
     * authoritative flag reproduces vanilla server-side. The player-only force-timer block
     * is consumed solely by {@code BasicShipAI}'s pullback module
     * - moot for a piloted seat on BOTH sides (null/shim AI).
     * Non-carriers agree at the shared engine default and issue nothing; hulks read false and
     * the engine drops commands on them natively. Never fast-time reinjected (the shim excludes
     * toggles by construction). Fail-closed: null constant/read handle or an unreadable level =>
     * zero issues. Meter: {@code state.pullBackToggleTicks} + a capped greppable line per issue.
     */
    private void applyPullBackBit(Object ship, int held, PilotApplyState state) {
        if (weaponCommandPullBackFighters == null || shipIsPullBackFighters == null
                || playerShipGiveCommand == null) {
            return; // fail-closed: lane unexpressed
        }
        Boolean pulledBack = asBoolean(invoke(shipIsPullBackFighters, ship, "ShipAPI.isPullBackFighters"));
        if (pulledBack == null) {
            return; // unreadable level: no mismatch basis this tick
        }
        boolean bit = (held & (1 << WIRE_BIT_PULL_BACK_FIGHTERS)) != 0;
        if (bit == pulledBack.booleanValue()) {
            return;
        }
        invoke(playerShipGiveCommand, ship, "ShipAPI.giveCommand(PULL_BACK_FIGHTERS)",
                weaponCommandPullBackFighters, null, 0);
        state.pullBackToggleTicks++;
        if (pullBackToggleLogs < PULL_BACK_TOGGLE_LOG_MAX) {
            pullBackToggleLogs++;
            System.out.println("pull-back-fighters mirror: issued PULL_BACK_FIGHTERS (wire regroup="
                    + bit + " hull was=" + pulledBack + " toggles=" + state.pullBackToggleTicks + ")");
        }
    }

    /**
     * R-key target designation apply — resolve the wire-carried target id and drive the
     * owned hull's {@code shipTarget} through the SETTER (never a field write: {@code setShipTarget}
     * re-derives {@code lastFriendlyTarget}, and every authoritative consumer — missile launch-lock,
     * autofire enemy-preference, carrier/system scripts — re-derives from {@code shipTarget}
     * deterministically). Read-compare-issue: an agreeing tick touches nothing.
     * {@code TARGET_ABSENT} (legacy/synthetic producer) = skip entirely (current behavior);
     * {@code TARGET_NONE} = clear iff the hull's target is non-null; unresolved id = no-op this
     * frame (latest-wins re-carries next frame); fighter ids REJECTED ({@code isFighter} — vanilla's
     * pick can never produce one; the engine's own target guard protects only the friendly cache,
     * so an unchecked apply would diverge). Wire is owner-agnostic — friendly designations carry
     * too; vanilla has no enemy/friendly branch at designation, autofire itself filters to enemies.
     * Unbound seams (fake bindings) = no-op, byte-identical.
     */
    private boolean applyShipTarget(Object ship, cc.starsector.server.runtime.PilotIntentFrame frame) {
        long wireId = frame.targetWireId();
        if (wireId == cc.starsector.server.runtime.PilotIntentFrame.TARGET_ABSENT
                || shipSetShipTarget == null || shipGetShipTarget == null) {
            return false;
        }
        Object current = invoke(shipGetShipTarget, ship, "ShipAPI.getShipTarget");
        if (wireId == cc.starsector.server.runtime.PilotIntentFrame.TARGET_NONE) {
            if (current == null) {
                return false;
            }
            invoke(shipSetShipTarget, ship, "ShipAPI.setShipTarget(null)", (Object) null);
            return true;
        }
        Object resolved = shipForWireIdOrNull(wireId);
        if (resolved == null) {
            return false; // unresolved this frame — no-op; latest-wins conflation re-carries it
        }
        if (shipIsFighter != null
                && Boolean.TRUE.equals(invoke(shipIsFighter, resolved, "ShipAPI.isFighter"))) {
            return false; // fighter designation rejected (vanilla's pick can never produce one)
        }
        if (resolved == current) {
            return false;
        }
        invoke(shipSetShipTarget, ship, "ShipAPI.setShipTarget", resolved);
        return true;
    }

    /**
     * The LEGACY held fire lane (the fire-discharge port): issue the
     * engine's own AIMED FIRE on every frame the client holds the trigger — a 1:1 port of the proven
     * predict gate's manual-fire lane (scout {@code run-headless-bootstrap-scout.ps1:41211-41243}).
     * <b>Superseded as the default by the declared-discharge lane</b> ({@link #serveDeclaredFire}):
     * level-sampling {@code fireHeld} misses in BOTH directions under adversarial timing (client fires,
     * server's sample window misses → client_only; server fires a held tick the client's own engine
     * gated → server_only — the meter history). Kept as the {@code cc.server.fireLane=held} lane,
     * the fail-open fallback for an old client that never declares, and the future beam lane.
     * Faithfulness ("copy the scout"): the engine's own
     * {@code giveCommand(USE_SELECTED_GROUP, aimPoint, 0)} (vanilla's click command —
     * the same fire body FIRE runs, and refused while a {@code noFiring} system lock blocks the
     * trigger) spawns the projectile + raises flux — NOT a server-authored shot; no force-fire /
     * cooldown crutch; the fire command on {@code fireHeld}, NOTHING on release (no HOLD_FIRE — the
     * FEEL-CONFIRMED-GOOD scout behaviour, micro-Q2). The fire DECISION still routes through the
     * engine-identity-free {@link cc.starsector.server.runtime.PilotFireMapper}; since the aim-carry change the
     * AIM is the frame's carried world point (v3), not the mapper's bearing. Returns true on a tick
     * the fire command was issued (an engine-side lock refusal still reads true here).
     */
    private boolean applyFireBit(Object ship, cc.starsector.server.runtime.PilotIntentFrame frame,
            long simTick, PilotApplyState state) {
        cc.starsector.server.runtime.PilotFireCommand cmd =
                cc.starsector.server.runtime.PilotFireMapper.map(frame.toFireIntent());
        // The fireEdgeSeq depth-1 latch — INSIDE the held lane, not
        // a third lane (the one-lane rule stands): observe the wire's wrapping rising-edge
        // counter each applied frame; on an advance the latch presses aimed FIRE until the seat's
        // discharge releases it (PilotIntentStep.onOwnShipDischarge) or the bounded window lapses.
        // Legacy producers carry seq 0 forever (.of default) — never an advance, latch never arms.
        state.observeFireEdge(frame.fireEdgeSeq(), frame.selectedWeaponGroup(), simTick);
        // The expiry flux-gate read — arm + expiry ticks only.
        if (state.latchGateReadDue(simTick)) {
            state.noteLatchFluxGate(readFluxGate(ship), frame.shipId());
        }
        boolean latchPressing = state.latchPressing(cmd.fire(), simTick);
        // The declared-shot pad is its OWN term, asked only on a
        // released, latch-idle tick (never fed into latchPressing as fireHeld, which would zero its budget).
        boolean padPressing = !cmd.fire() && !latchPressing && state.declPad().pressing(
                simTick, frame.selectedWeaponGroup(), frame.activeTubeOrdinal());
        if (!cmd.fire() && !latchPressing && !padPressing) {
            return false; // trigger released: aim only, discharge nothing (copy the scout — no HOLD_FIRE)
        }
        // FIRE aims at the frame's carried TRUE aim world point (v3), not a fixed-range
        // projection of the bearing, so the FIRE-tick aim agrees with the per-frame mouseTarget slew.
        boolean fired = issueAimedFire(ship, frame.aimWorldX(), frame.aimWorldY());
        // While the wire trigger is genuinely down (cmd.fire() only — a
        // latch tap-replay advances via the engine's own falling edge when its span ends), drive
        // vanilla's player-ship-only mid-hold ALTERNATING dwell advance (the root cause) through
        // the engine's own conform verb. Covers legacy-held, fail-open reverts, AND the
        // post-demote mixed-group hold (the class-routing dispatch's held branch — THE defect lane).
        // `fired` = the command was ISSUED; under the hull's fire lock
        // the engine drops it and vanilla's dwell accumulates only while the
        // group's fire argument is true — a refused press never walks
        // the DWELL. The pointer still moves ONCE at the lock's first tick: both engines run
        // vanilla's falling edge, and this hull never sets
        // justSwitched (player-ship-only), so it can end one tube ahead of a client whose
        // last pre-lock frame was itself a dwell advance. Under the travel-drive lock the kit's D4
        // reads the wire trigger neutral, so the quiescent conform heals it mid-burn; under a
        // system / cloak lock the wire still carries the raw press and it stays until trigger
        // release. The gate reads
        // the lock only past the advance's ALTERNATING / >= 2-tube gates; a
        // skipped tick resets the accumulator (noteAlternatingHeldTick's non-consecutive rule =
        // vanilla's zero on a non-fired frame).
        if (fired && cmd.fire()) {
            reflection.advanceAlternatingHeldDwell(ship, state, simTick, dwellFireLockGate);
        }
        return fired;
    }

    /** The armed fire-edge latch's flux gate — 2 overloaded / 1 venting / 0 neither; a null handle
     *  or tracker, or a throw (logged once), reads 0 = UNGATED (flags MORE, never hides). */
    private int readFluxGate(Object ship) {
        try {
            Object tracker = shipGetFluxTracker == null ? null : shipGetFluxTracker.invoke(ship);
            return tracker == null ? 0
                    : fluxIsOverloaded != null && Boolean.TRUE.equals(asBoolean(fluxIsOverloaded.invoke(tracker))) ? 2
                    : fluxIsVenting != null && Boolean.TRUE.equals(asBoolean(fluxIsVenting.invoke(tracker))) ? 1 : 0;
        } catch (Throwable t) {
            if (fluxGateReadErrors++ == 0) {
                System.out.println("pilot-fire-edge flux-gate read-error n=1 err=" + t.getClass().getSimpleName());
            }
            return 0;
        }
    }

    /**
     * The DECLARED-DISCHARGE fire lane: drive the engine's own aimed
     * {@code giveCommand(USE_SELECTED_GROUP)} (vanilla's click command, refused by the
     * engine under a {@code noFiring} system lock; ray heads instead WAIT on the adopt lane's
     * fire-lock probe) while a client-declared discharge is pending — pressing only on ticks the
     * head's named mount is dischargeable (waiting silently otherwise), until the server's engine serves
     * it (the serve loop consumes the head on its own observed own-ship {@code WEAPON_DISCHARGE}) or the
     * head expires ({@link PilotApplyState#DECLARED_FIRE_TIMEOUT_TICKS}). The aim is <em>the declaration's</em>
     * carried world POINT ({@code aimWorldX/Y} — the client's cursor point at ITS discharge instant;
     * PDCL v2), so the served shot goes where the human aimed even if the aim moved while the
     * declaration crossed the wire ({@code aimFacingDeg} rides the frame for offline audit/matching only —
     * it is the CURSOR world bearing by construction, never a mount ray). Faithful by
     * construction: the engine's cooldown/flux/malfunction/system-lock gates still
     * decide whether the press discharges — this lane only presses the trigger for shots the client's
     * engine already emitted, eliminating both miss directions of the level-sampled held lane.
     * Returns true on a tick the fire command was issued (unbound fake-binding seams → false, while the
     * timeout machinery still runs so game-free contracts can exercise expiry).
     */
    private boolean serveDeclaredFire(Object ship, long simTick, PilotApplyState state) {
        // adopt-first: a v4 ray-carrying head is served by NAMED-MOUNT
        // force-fire (validated fail-CLOSED in the driver closure — range/ε/arc/rate/seam;
        // the scoped exception to the faithfulness rule), never by the group-level FIRE press below
        // (ray heads refuse the press path outright in headDeclarationToPress). A served head
        // stays pending until the observed own-ship discharge consumes it (consume law
        // unchanged) or the ε-clock expires it — the engine-refused-force-fire retry.
        if (state.serveAdoptHead(simTick) != null) {
            return true;
        }
        // headDeclarationToPress = headDeclarationToServe's timeout machinery +
        // the mount-readiness press gate — a blocked head waits silently (no SELECT_GROUP churn, no
        // group-wide FIRE strobe re-committing autoCharge mounts) and presses ~once at readiness.
        cc.starsector.server.runtime.FireDischargeDeclaration head = state.headDeclarationToPress(simTick);
        if (head == null) {
            return false;
        }
        // The declaration names its discharging MOUNT — resolve mount → weapon
        // group and SELECT_GROUP to it for the serve window so FIRE discharges the group the client's
        // own engine discharged, not whatever group happened to be selected. Out-of-bounds mount /
        // unresolvable group / unbound seams / mountIndex -1 (legacy client) = today's path verbatim.
        int targetGroup = groupOrdinalForMountOnShip(ship, head.mountIndex());
        boolean selectionForced = false;
        boolean restoreKnown = false;
        int restoreOrdinal = -1;
        int deselectOrdinal = -1;
        if (targetGroup >= 0 && weaponCommandSelectGroup != null) {
            List<?> groups = shipGetSelectedGroupAPI == null ? null : weaponGroupsCopyOrNull(ship);
            int hullSelected = groups == null
                    ? -1
                    : groups.indexOf(invoke(shipGetSelectedGroupAPI, ship, "ShipAPI.getSelectedGroupAPI"));
            if (hullSelected != targetGroup) {
                invoke(playerShipGiveCommand, ship, "ShipAPI.giveCommand(SELECT_GROUP)",
                        weaponCommandSelectGroup, null, targetGroup);
                selectionForced = true;
                // Restore bookkeeping — only when the pre-serve selection was
                // actually READ (unbound seams keep today's no-restore path verbatim).
                if (MOUNT_SELECTED_FLAG && groups != null) {
                    restoreKnown = true;
                    restoreOrdinal = hullSelected;
                    deselectOrdinal = groups.size();
                }
            }
        }
        // Aim at the declaration's carried world POINT — the player's true aim at
        // ITS discharge instant — exact under latency (the pre-v2 fixed-range bearing projection is gone).
        boolean fired = issueAimedFire(ship, head.aimWorldX(), head.aimWorldY());
        // Queue the selection RESTORE behind the FIRE. The engine's Ship processes its
        // command queue IN ORDER during advance (FIRE consumes
        // this.selected at its own queue position), so the fire still discharges the declared
        // group while the hull ends the advance on the pilot's real selection — the post-step
        // FLAG_SELECTED mount encode never observes the serve transient. A -1 pre-serve read
        // restores DESELECTED via the deliberate out-of-bounds ordinal (the deselect idiom).
        if (selectionForced && restoreKnown) {
            invoke(playerShipGiveCommand, ship, "ShipAPI.giveCommand(SELECT_GROUP)",
                    weaponCommandSelectGroup, null,
                    restoreOrdinal >= 0 ? restoreOrdinal : deselectOrdinal);
        }
        return fired;
    }

    /**
     * Drive the hull's selected weapon group to the wire-carried live ordinal via
     * the engine's own {@code giveCommand(SELECT_GROUP, null, idx)}. Read-compare-issue: the command is
     * only issued when the hull's current ordinal differs ({@code WeaponGroup.select(true)} has side
     * effects), so an agreeing tick touches nothing. {@code wireGroup}
     * -1 (legacy/synthetic producer, intentSource seat) or unbound seams (every fake binding) = no-op;
     * a wire ordinal past the hull's group count = V4 fail-soft capped warn, never a throw.
     *
     * <p>{@code SELECTED_GROUP_DESELECTED} (-2) mirrors the client engine's own
     * DESELECTED state (its selection left the group list — the player picked an empty group) by
     * issuing {@code giveCommand(SELECT_GROUP)} with a deliberately OUT-OF-BOUNDS ordinal
     * ({@code groups.size()}): the engine's {@code Ship} command dispatch nulls {@code selected} on any
     * out-of-bounds index — the exact engine-own path the player's
     * empty-group keypress drives client-side (engine-faithful; no reflection selection write).
     * Releasing the hull's selection releases the engine's playerShip manual-control autofire
     * override — the cause of a live-run seat-1 bug. Read-compare-issue
     * like the ordinal path: an already-deselected hull is a no-op (no command strobe), and the
     * deliberate OOB bypasses the V4 parity warn (it is not a group-count fault).
     */
    private boolean mirrorSelectedGroup(Object ship, int wireGroup) {
        boolean deselect =
                wireGroup == cc.starsector.server.runtime.PilotIntentFrame.SELECTED_GROUP_DESELECTED;
        if ((wireGroup < 0 && !deselect) || weaponCommandSelectGroup == null
                || shipGetWeaponGroupsCopy == null || shipGetSelectedGroupAPI == null) {
            return false;
        }
        List<?> groups = weaponGroupsCopyOrNull(ship);
        if (groups == null) {
            return false;
        }
        if (!deselect && wireGroup >= groups.size()) {
            warnGroupParity("selected-group", wireGroup, groups.size());
            return false;
        }
        int hullSelected =
                groups.indexOf(invoke(shipGetSelectedGroupAPI, ship, "ShipAPI.getSelectedGroupAPI"));
        if (deselect ? hullSelected < 0 : hullSelected == wireGroup) {
            return false;
        }
        invoke(playerShipGiveCommand, ship, "ShipAPI.giveCommand(SELECT_GROUP)",
                weaponCommandSelectGroup, null, deselect ? groups.size() : wireGroup);
        return true;
    }

    /**
     * Mask-staleness gate adjudication — ONE call per applied frame, BEFORE the shape
     * trio. Armed at a variant-SWAP repoint ({@link PilotApplyState#armMaskStalenessGate}); while it
     * returns {@code true} the caller withholds the selection mirror, tube conform, and autofire diff
     * (the client is still sampling its DEAD hull's trio: {@code captureOwnCommands}
     * runs before the snapshot-edge-triggered {@code respawnOwn} ref swap, so stale frames survive the
     * refield). Release is frame-evidence-driven, no wire change ({@code frameSeq} = the client's
     * last-received snapshot {@code simTick} echo — see
     * {@code PredictReconcileClientSession.sendIntent}):
     * <ul>
     *   <li><b>(b) mask-change</b>: a PRESENT mask differing from the arm snapshot S — the sampler
     *   moved off the frozen baseline ⇒ fresh by content, release AND apply this same frame;</li>
     *   <li><b>(a) causality hold</b>: {@code frameSeq < refieldTick} — the client had not yet SEEN
     *   the fly-in snapshot when it sampled ⇒ stale, hold;</li>
     *   <li><b>(c) adopt echo</b>: the FIRST frame echoing {@code >= refieldTick} may still carry the
     *   pre-swap sample (capture-before-respawn order, exactly one frame) ⇒ record + hold;</li>
     *   <li><b>(d) later advance</b>: {@code frameSeq > adoptEcho} — any snapshot {@code >= refieldTick}
     *   triggers the client's respawn edge (AWSS full snapshots are self-contained), so a LATER echo
     *   proves a post-adopt sample ⇒ release even when the mask numerically equals S (kills the
     *   S==fresh-default coincidence deadlock without knowing the new variant's default mask; worst
     *   release lag ≈ 1-2 client frames, honoring the default-ON constraint).</li>
     * </ul>
     */
    private boolean maskStalenessGateHolds(cc.starsector.server.runtime.PilotIntentFrame frame,
            long simTick, PilotApplyState state) {
        if (!state.maskGateArmed) {
            return false;
        }
        int mask = frame.autofireGroupsMask();
        long frameSeq = frame.frameSeq();
        boolean maskFresh = mask >= 0 && mask != state.maskGateStaleMask;
        boolean laterAdvance = state.maskGateAdoptEcho >= 0 && frameSeq > state.maskGateAdoptEcho;
        if (maskFresh || laterAdvance) {
            System.out.println("npeer-serve mask_gate release wire_id=" + frame.shipId()
                    + " tick=" + simTick + " frames_held=" + state.maskGateSuppressedFrames
                    + " fresh_mask=" + (mask >= 0 ? "0x" + Integer.toHexString(mask) : "-1")
                    + " stale_mask=0x" + Integer.toHexString(state.maskGateStaleMask)
                    + " reason=" + (maskFresh ? "mask_change" : "later_advance"));
            state.clearMaskStalenessGate();
            return false;
        }
        if (frameSeq >= state.maskGateRefieldTick && state.maskGateAdoptEcho < 0) {
            state.maskGateAdoptEcho = frameSeq; // (c): the one capture-order residual candidate
        }
        state.maskGateSuppressedFrames++;
        return true;
    }

    /**
     * Diff the wire-carried autofire bitmask (bit g = client group g
     * {@code isAutofiring()}) against the hull's groups and issue the engine's own
     * {@code giveCommand(TOGGLE_AUTOFIRE, null, g)} per mismatching group — the same click-toggle a
     * player's ctrl+group press drives, so deploy defaults agree by construction and the diff is
     * normally empty. {@code mask} -1 = ABSENT (legacy/synthetic producer — mask 0 would toggle OFF
     * deploy-default autofire); unbound seams (fake bindings) = no-op. Wire bits set past the hull's
     * group count = V4 fail-soft capped warn, bits skipped, never a throw. Returns toggles issued.
     *
     * <p>The desired state folds in the engine's personal-control law (the manual-group
     * rule of {@code WeaponGroup.advance}: a SELECTED group with autofire ON is manual — the
     * playerShip override forceOffs the autofire AIs and skips {@code advanceAuto}). That override
     * is {@code getPlayerShip()}-gated so at most seat-1's hull gets it server-side (a live-run
     * seat-2 bug: the server's autofire AI fired the pilot's selected group while the client
     * suppressed locally). Uniform for every seat, the mirror derives
     * {@code desired(g) = maskBit(g) && g != wireSelectedGroup}: selecting an autofiring group
     * toggles its tracker OFF (so {@code WeaponGroup.advance} takes the manual branch — the engine's
     * own suppression outcome), and deselecting/switching re-toggles it ON (autofire resumes,
     * vanilla). The client tracker keeps the bit SET while selected (the override never clears it
     * in the engine's WeaponGroup), so suppression is derived here, never carried. Keyed on the
     * WIRE selection, not the hull's — {@link #serveDeclaredFire} transiently forces the declared
     * group selected inside the serve window. {@code wireSelectedGroup} -1 (never-sampled) and -2
     * (DESELECTED, the deselect release) suppress nothing.
     *
     * <p><b>CLICK, never HOLD (the stuck-autofire root cause).</b> {@code giveCommand
     * (TOGGLE_AUTOFIRE, g)} is not a one-shot command: the engine parks it in {@code Ship.commands}
     * and the frame drain turns its PRESENCE into a per-frame BUTTON LEVEL — commanded groups get
     * {@code WeaponGroup.autofire(true)}, every other group gets
     * {@code autofire(false)}. That level feeds the engine's button tracker in TOGGLE
     * mode which flips ONLY on a rising edge
     * ({@code down && !wasDown}) and latches {@code wasDown} until a released frame
     * clears it. A read-compare-issue mirror that re-issues every
     * tick while the states disagree therefore HOLDS the button: it toggles once, and every later
     * press is swallowed — the group sits OFF until the hull dies and rebuilds its groups. Measured:
     * six graded runs, stuck spans of 1051-7515 ticks each carrying exactly one issued toggle per tick
     * (one run = 7515 toggles across a 7515-tick OFF span), against 3 toggles total in the run that
     * recovered. So each group presses at most every OTHER tick; the skipped tick is
     * the release the engine's edge detector needs. Chargeup/chargedown are never set on this tracker
     * (0.0F defaults), so one press + one release converges in 2 ticks.
     */
    private int applyAutofireMask(Object ship, long shipId, int mask, int wireSelectedGroup,
            long simTick, PilotApplyState state) {
        if (mask < 0 || weaponCommandToggleAutofire == null
                || shipGetWeaponGroupsCopy == null || weaponGroupIsAutofiring == null) {
            return 0;
        }
        List<?> groups = weaponGroupsCopyOrNull(ship);
        if (groups == null) {
            return 0;
        }
        // The wire mask's bits are CLIENT group ordinals; the hull loop
        // below walks SERVER ordinals. Resolve this tenure's identity map, then steer each server
        // group from ITS client counterpart's bit — identity (inverse == null) keeps the pre-map
        // path byte-identical, and an unmatched server group is SKIPPED (fail-closed: it stays at
        // the build-time-disarmed state, so a skipped group can never mint Defect-I fire).
        ensureGroupIdentityResolved(ship, shipId, state);
        int[] inverse = state.groupIdentityInverse;
        int suppress = wireSelectedGroup >= 0 ? wireSelectedGroup : -1;
        int toggles = 0;
        int hullGroups = Math.min(groups.size(), 31);
        // CLICK LAW: bits pressed on the immediately-preceding apply tick (or earlier
        // in THIS tick, when a seat's catch-up applies two frames inside one engine frame) may not be
        // pressed again — the engine needs a released frame to re-arm the tracker's rising edge.
        // (Press bits are SERVER-space — the space giveCommand presses in — so the law is
        // untouched by the client→server translation above.)
        int pressBlock = simTick <= state.autofirePressTick + 1 ? state.autofirePressMask : 0;
        int pressedNow = simTick == state.autofirePressTick ? state.autofirePressMask : 0;
        for (int g = 0; g < hullGroups; g++) {
            int c = inverse == null ? g : (g < inverse.length ? inverse[g] : -1);
            if (c < 0) {
                state.groupIdentitySkips++;
                continue; // no client counterpart under the map — never guess (the skip law)
            }
            boolean desired = (mask & (1 << c)) != 0 && c != suppress;
            Boolean hullOn = asBoolean(
                    invoke(weaponGroupIsAutofiring, groups.get(g), "WeaponGroupAPI.isAutofiring"));
            if (hullOn == null || hullOn.booleanValue() == desired) {
                continue; // agreed (or unreadable): issue nothing, which IS the release frame
            }
            if ((pressBlock & (1 << g)) != 0) {
                state.autofireClickReleases++; // the mandatory release tick; re-press next tick
                continue;
            }
            invoke(playerShipGiveCommand, ship, "ShipAPI.giveCommand(TOGGLE_AUTOFIRE)",
                    weaponCommandToggleAutofire, null, g);
            toggles++;
            pressedNow |= 1 << g;
            // Attribute EVERY issued toggle (an earlier forensics pass had to
            // infer re-arms from volley shapes). Write-only sink, capped; counters keep counting.
            if (autofireToggleLogsEmitted < MAX_AUTOFIRE_TOGGLE_LOGS) {
                autofireToggleLogsEmitted++;
                System.out.println("npeer-serve autofire_mask_toggle wire_id=" + shipId
                        + " tick=" + simTick + " group=" + g + " wire_group=" + c
                        + " on=" + desired
                        + " wire_mask=0x" + Integer.toHexString(mask)
                        + " hull_groups=" + groups.size()
                        + (autofireToggleLogsEmitted == MAX_AUTOFIRE_TOGGLE_LOGS
                                ? " (log capped; toggles keep counting quietly)" : ""));
            }
            // Instrument stamp 2: the tenure's FIRST issued toggle — the exact
            // tick-10 moment whose mis-arm the map exists to prevent.
            if (!state.groupIdentityToggleDumped) {
                state.groupIdentityToggleDumped = true;
                logGroupIdentity("first_toggle", ship, shipId, simTick, state);
            }
        }
        state.autofirePressTick = simTick;
        state.autofirePressMask = pressedNow;
        int clientGroups = state.groupIdentityMap == null ? hullGroups
                : Math.min(state.groupIdentityMap.length, 31);
        if ((mask >>> clientGroups) != 0) {
            warnGroupParity("autofire-mask", 32 - Integer.numberOfLeadingZeros(mask) - 1, clientGroups);
        }
        return toggles;
    }

    /**
     * Decisive instrument: one capped line per hull-tenure edge (first applied
     * frame + first issued autofire toggle) dumping the LIVE hull's group identity — per-group
     * member {@code slotId:weaponId} sets, the {@code getAllWeapons} mount order, per-group
     * {@code isAutofiring}, the selected ordinal and the resolved client→server map. This is the
     * serve-boot ground truth that decides the group-order question: branch (a) = a real server-side group-order
     * divergence (the map line shows a non-identity remap), branch (b) = a forensic dossier's
     * cross-side ordinal attribution artifact (identity map, but the mount order here disagrees
     * with the client table the dossier mapped through), or faithful (identity + agreeing order).
     * Write-only sink, capped; never throws into the serve tick.
     */
    private void logGroupIdentity(String phase, Object ship, long shipId, long simTick,
            PilotApplyState state) {
        if (groupIdentityLogsEmitted >= MAX_GROUP_IDENTITY_LOGS) {
            return;
        }
        groupIdentityLogsEmitted++;
        ensureGroupIdentityResolved(ship, shipId, state);
        System.out.println("npeer-serve group_identity phase=" + phase + " wire_id=" + shipId
                + " tick=" + simTick + " map=" + describeGroupIdentityMap(state)
                + " " + groupIdentitySnapshotOnShip(ship));
    }

    /** The map field: {@code unresolved} / {@code identity[...]} / {@code [0->1,1->0]}. */
    private static String describeGroupIdentityMap(PilotApplyState state) {
        if (!state.groupIdentityResolved) {
            return "unresolved";
        }
        int[] map = state.groupIdentityMap;
        if (map == null) {
            return state.groupIdentityFileGroups == null ? "identity(no_file_truth)" : "identity";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int c = 0; c < map.length; c++) {
            if (c > 0) {
                sb.append(',');
            }
            sb.append(c).append("->").append(map[c]);
        }
        return sb.append(']').toString();
    }

    /**
     * Lazily build this tenure's client→server group identity map — the
     * variant FILE's declared slot-id sets (== the client's live group order)
     * matched by EXACT set equality against the live server groups' member slot ids. One-shot
     * per tenure once the hull is readable; every failure shape is deliberate:
     * <ul>
     *   <li>no file truth / slot seams unbound → resolved-identity (today's behavior; fakes and
     *   contracts byte-identical by construction);</li>
     *   <li>hull groups not yet live → stay UNRESOLVED, the next wire-group touch retries;</li>
     *   <li>live read THROWS with file truth present → ALL ordinals unmatched (fail-closed: never
     *   guess identity over a hull we failed to read — the build-time disarm keeps skipped
     *   groups silent), loud capped line;</li>
     *   <li>full order agreement → null map, the identity fast path (zero per-tick overhead).</li>
     * </ul>
     * A non-identity build always logs under the cap: a live remap IS the Defect-I smoking gun
     * (the index-keyed mirror armed the server's energy group from the client's missile
     * bit). Bijection law: each live group is claimed at most once (first file group wins), so two
     * client ordinals can never steer one server group.
     */
    private void ensureGroupIdentityResolved(Object ship, long shipId, PilotApplyState state) {
        if (state.groupIdentityResolved) {
            return;
        }
        java.util.List<java.util.Set<String>> declared = state.groupIdentityFileGroups;
        if (declared == null || declared.isEmpty()
                || weaponGetSlot == null || weaponSlotGetId == null
                || groupGetWeaponsCopy == null || shipGetWeaponGroupsCopy == null) {
            state.groupIdentityResolved = true; // identity — no truth to diverge from
            return;
        }
        List<?> groups = weaponGroupsCopyOrNull(ship);
        if (groups == null || groups.isEmpty()) {
            return; // hull not live yet — stay unresolved, the next touch retries
        }
        int[] map = new int[declared.size()];
        int[] inverse = new int[groups.size()];
        java.util.Arrays.fill(inverse, -1);
        java.util.List<java.util.Set<String>> live = new java.util.ArrayList<>(groups.size());
        try {
            for (Object group : groups) {
                java.util.Set<String> slots = new java.util.HashSet<>();
                Object weapons = groupGetWeaponsCopy.invoke(group);
                if (!(weapons instanceof List<?> weaponList)) {
                    throw new IllegalStateException("group weapons unreadable");
                }
                for (Object weapon : weaponList) {
                    Object slot = weaponGetSlot.invoke(weapon);
                    Object slotId = slot == null ? null : weaponSlotGetId.invoke(slot);
                    if (!(slotId instanceof String id)) {
                        throw new IllegalStateException("weapon slot id unreadable");
                    }
                    slots.add(id);
                }
                live.add(slots);
            }
        } catch (Throwable t) {
            java.util.Arrays.fill(map, -1);
            state.groupIdentityMap = map;
            state.groupIdentityInverse = inverse;
            state.groupIdentityResolved = true;
            if (groupIdentityLogsEmitted < MAX_GROUP_IDENTITY_LOGS) {
                groupIdentityLogsEmitted++;
                System.out.println("npeer-serve group_identity build_failed wire_id=" + shipId
                        + " error=" + t.getClass().getSimpleName()
                        + " (file truth present, live unreadable — ALL wire group ordinals skip)");
            }
            return;
        }
        int remapped = 0;
        int unmatched = 0;
        for (int c = 0; c < map.length; c++) {
            int match = -1;
            for (int s = 0; s < live.size(); s++) {
                if (inverse[s] < 0 && live.get(s).equals(declared.get(c))) {
                    match = s;
                    break;
                }
            }
            map[c] = match;
            if (match < 0) {
                unmatched++;
            } else {
                inverse[match] = c;
                if (match != c) {
                    remapped++;
                }
            }
        }
        state.groupIdentityResolved = true;
        if (remapped == 0 && unmatched == 0 && map.length == groups.size()) {
            return; // full agreement — identity fast path (map stays null)
        }
        state.groupIdentityMap = map;
        state.groupIdentityInverse = inverse;
        if (groupIdentityLogsEmitted < MAX_GROUP_IDENTITY_LOGS) {
            groupIdentityLogsEmitted++;
            System.out.println("npeer-serve group_identity built wire_id=" + shipId
                    + " map=" + describeGroupIdentityMap(state)
                    + " live_groups=" + groups.size() + " file_groups=" + map.length
                    + " remapped=" + remapped + " unmatched=" + unmatched);
        }
    }

    /**
     * Translate a wire (CLIENT-space) group ordinal to this hull's SERVER
     * ordinal through the tenure's identity map. Negative wire values (-1 never-sampled, -2
     * DESELECTED) pass through untranslated; an identity map returns the ordinal unchanged (the
     * pre-map byte-identical path); a mapped -1 (unmatched / past file truth) tells the caller
     * to SKIP — both consumers already treat a negative ordinal as their no-op shape.
     */
    private int mapWireGroupToServer(Object ship, long shipId, int wireGroup,
            PilotApplyState state) {
        if (wireGroup < 0) {
            return wireGroup;
        }
        ensureGroupIdentityResolved(ship, shipId, state);
        int[] map = state.groupIdentityMap;
        if (map == null) {
            return wireGroup;
        }
        int mapped = wireGroup < map.length ? map[wireGroup] : -1;
        if (mapped < 0) {
            state.groupIdentitySkips++;
        }
        return mapped;
    }

    /**
     * The live hull's group-identity snapshot behind a wire id — public for
     * the serve loop's deploy-site logging; read-fails degrade to '?' fields, never a throw.
     */
    public String groupIdentitySnapshotForShip(long shipId) {
        Object ship = shipForWireIdOrNull(shipId);
        return ship == null ? "ship=unresolved" : groupIdentitySnapshotOnShip(ship);
    }

    /** {@code sel=gN weapons=[m0=SLOT:id ...] g0[af=x]={SLOT:id,...} ...} — the fields. */
    private String groupIdentitySnapshotOnShip(Object ship) {
        try {
            StringBuilder line = new StringBuilder("sel=g").append(mountProbes.selectedGroupOrdinalOnShip(ship));
            Object rawWeapons = shipGetAllWeapons == null ? null
                    : invoke(shipGetAllWeapons, ship, "ShipAPI.getAllWeapons");
            line.append(" weapons=[");
            if (rawWeapons instanceof List<?> weapons) {
                for (int mount = 0; mount < weapons.size(); mount++) {
                    if (mount > 0) {
                        line.append(' ');
                    }
                    line.append('m').append(mount).append('=')
                            .append(describeWeaponIdentity(weapons.get(mount)));
                }
            } else {
                line.append('?');
            }
            line.append(']');
            List<?> groups = weaponGroupsCopyOrNull(ship);
            if (groups == null) {
                return line.append(" groups=?").toString();
            }
            for (int g = 0; g < groups.size(); g++) {
                Object group = groups.get(g);
                Object autofire = weaponGroupIsAutofiring == null ? null
                        : invoke(weaponGroupIsAutofiring, group, "WeaponGroupAPI.isAutofiring");
                line.append(" g").append(g).append("[af=").append(autofire).append("]={");
                Object weapons = groupGetWeaponsCopy == null ? null
                        : invoke(groupGetWeaponsCopy, group, "WeaponGroupAPI.getWeaponsCopy");
                if (weapons instanceof List<?> weaponList) {
                    for (int w = 0; w < weaponList.size(); w++) {
                        if (w > 0) {
                            line.append(',');
                        }
                        line.append(describeWeaponIdentity(weaponList.get(w)));
                    }
                } else {
                    line.append('?');
                }
                line.append('}');
            }
            return line.toString();
        } catch (Throwable t) {
            return "snapshot=failed";
        }
    }

    /** {@code slotId:weaponId} for one live weapon; '?' fields on any unreadable read. */
    private String describeWeaponIdentity(Object weapon) {
        String slotId = "?";
        String weaponId = "?";
        try {
            Object slot = weaponGetSlot == null || weapon == null ? null
                    : weaponGetSlot.invoke(weapon);
            Object id = slot == null || weaponSlotGetId == null ? null
                    : weaponSlotGetId.invoke(slot);
            if (id instanceof String s) {
                slotId = s;
            }
            Object spec = weaponGetSpec == null || weapon == null ? null
                    : weaponGetSpec.invoke(weapon);
            Object wid = spec == null || weaponSpecGetWeaponId == null ? null
                    : weaponSpecGetWeaponId.invoke(spec);
            if (wid instanceof String s) {
                weaponId = s;
            }
        } catch (Throwable ignore) {
            // '?' fields stand
        }
        return slotId + ":" + weaponId;
    }

    /**
     * Resolve entry for the serve loop's consume-by-mount guard: the weapon-group
     * ordinal (index into {@code getWeaponGroupsCopy()}) owning the given mount ordinal (index into
     * {@code getAllWeapons()}) on the owned ship, or -1 when unresolvable (unbound seams — every fake
     * binding —, unknown ship, out-of-bounds mount, ungrouped weapon). -1 tells the caller to keep the
     * legacy consume-on-any-discharge pairing, so contracts without the seams stay byte-identical.
     */
    public int weaponGroupOrdinalForMount(long shipId, int mountIndex) {
        Object ship = shipForWireIdOrNull(shipId);
        return ship == null ? -1 : groupOrdinalForMountOnShip(ship, mountIndex);
    }

    // ---- mount-probe family delegates: the bodies live in MountProbeLane; these keep
    // EngineCombatBinding and PCA's own callers source-compatible. ----

    /** Delegate — body + law in {@link MountProbeLane#fireClassResolverForShip}. */
    public PilotApplyState.FireClassResolver fireClassResolverForShip(final long shipId,
            final double rateMax,
            final Map<String, cc.starsector.server.runtime.FireClassLaw.FireClass> overrides) {
        return mountProbes.fireClassResolverForShip(shipId, rateMax, overrides);
    }

    /** Delegate — body + law in {@link MountProbeLane#mountReadinessProbeForShip}. */
    public PilotApplyState.MountReadinessProbe mountReadinessProbeForShip(final long shipId) {
        return mountProbes.mountReadinessProbeForShip(shipId);
    }

    /** Delegate — body + law in {@link MountProbeLane#fireLockProbeForShip}. */
    public PilotApplyState.FireLockProbe fireLockProbeForShip(final long shipId) {
        return mountProbes.fireLockProbeForShip(shipId);
    }

    /** Delegate — body + law in {@link MountProbeLane#driveLockWirePredicateOrNull}. */
    public java.util.function.LongPredicate driveLockWirePredicateOrNull() {
        return mountProbes.driveLockWirePredicateOrNull();
    }

    /**
     * Delegate — body + law in {@link MountProbeLane#fireLockReadsBound}. Kept (not a direct call) because
     * its one caller is the {@link #dwellFireLockGate} field lambda, and javac refuses a field initializer
     * that reads the blank-final {@code mountProbes} (assigned in the constructor body, which runs later).
     */
    private boolean fireLockReadsBound() {
        return mountProbes.fireLockReadsBound();
    }

    /** Delegate — body + law in {@link MountProbeLane#fireLockedNow} (kept for the same lambda reason). */
    private boolean fireLockedNow(Object ship) {
        return mountProbes.fireLockedNow(ship);
    }

    /** Delegate — body + law in {@link MountProbeLane#fireLockReadErrors}. */
    public int fireLockReadErrors() {
        return mountProbes.fireLockReadErrors();
    }

    /** Delegate — body + law in {@link MountProbeLane#mountFireDriverForShip}. */
    public PilotApplyState.MountFireDriver mountFireDriverForShip(final long shipId) {
        return mountProbes.mountFireDriverForShip(shipId);
    }

    /** Delegate — body + law in {@link MountProbeLane#fireClassSnapshotForShip}. */
    public String fireClassSnapshotForShip(long shipId, double rateMax,
            Map<String, cc.starsector.server.runtime.FireClassLaw.FireClass> overrides) {
        return mountProbes.fireClassSnapshotForShip(shipId, rateMax, overrides);
    }

    /** Delegate — body + law in {@link MountProbeLane#groupOrdinalForMountOnShip}. */
    private int groupOrdinalForMountOnShip(Object ship, int mountIndex) {
        return mountProbes.groupOrdinalForMountOnShip(ship, mountIndex);
    }

    /** V4 group-count parity fail-soft: capped warn, skip, never throw. */
    void warnGroupParity(String lane, int wireIdx, int hullGroupCount) {
        if (groupParityWarnsEmitted >= MAX_GROUP_PARITY_WARNS) {
            return;
        }
        groupParityWarnsEmitted++;
        System.out.println("pilot-group-mirror V4 parity warn lane=" + lane
                + " wire_idx=" + wireIdx + " hull_groups=" + hullGroupCount
                + (groupParityWarnsEmitted == MAX_GROUP_PARITY_WARNS
                        ? " (log capped; lane keeps skipping quietly)" : ""));
    }

    /**
     * Issue one engine-faithful aimed fire command: the wire-carried aim WORLD POINT into the
     * {@code Vector2f} arg of the engine's own {@code giveCommand(USE_SELECTED_GROUP, aimPoint, 0)}
     * ({@code weaponCommandFire} is bound to USE_SELECTED_GROUP on the real engine —
     * the same {@code fire(true, aim, dt)} body as FIRE, which reads its
     * own aim arg, no setShipTarget seam — micro-Q1 — and which a {@code noFiring} system lock
     * refuses). Shared by both fire lanes; since the aim-carry change the point is the
     * client's true {@code getMouseTarget()} at capture/discharge (v3/v2 wire), replacing the old
     * own-location + fixed-range bearing projection, so the FIRE-tick aim agrees with the per-frame
     * {@link #pinMouseTarget} slew target. Unbound seams (the fake binding leaves
     * {@code weaponCommandFire}/{@code vector2fCtor} null) → false (no fire), so every game-free
     * contract stays byte-identical.
     */
    private boolean issueAimedFire(Object ship, float aimWorldX, float aimWorldY) {
        if (weaponCommandFire == null || vector2fCtor == null) {
            return false;
        }
        try {
            Object aimPoint = vector2fCtor.newInstance(aimWorldX, aimWorldY);
            invoke(playerShipGiveCommand, ship, "ShipAPI.giveCommand(USE_SELECTED_GROUP)",
                    weaponCommandFire, aimPoint, 0);
            return true;
        } catch (InvocationTargetException e) {
            throw new EngineBindingException(
                    "ShipAPI.giveCommand(USE_SELECTED_GROUP) aim reconstruction threw",
                    e.getCause() != null ? e.getCause() : e);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new EngineBindingException(
                    "ShipAPI.giveCommand(USE_SELECTED_GROUP) aim reconstruction failed", e);
        }
    }


    // ---- binding-owned helpers the moved bodies call by their old bare names (delegators, not copies) ----
    // (invoke / shipForWireIdOrNull / weaponGroupsCopyOrNull are package-private: MountProbeLane delegates to them)

    Object invoke(Method m, Object target, String label, Object... args) {
        return binding.invoke(m, target, label, args);
    }

    /**
     * The human-pilot mark's ONE write site, called from {@link #applyPilotIntent} on every APPLIED frame, before
     * the world advance (so a mod's every-frame plugin reads it the same tick): the seat's own hull carries
     * {@code customData["cc_client.pilot"] = int[]{seatId, abilityMask, 0, flags}}, {@code abilityMask} = wire
     * bits 13..20 shifted to 0..7, {@code [2]} = 0 (the serve's own mark; a client's replica writes 1), and
     * {@code flags} bit 0 = FOLLOW: the serve follows this pilot's MagicLib decision on the hull (the seat's flags
     * source, read once per tenure at the re-point; the predicate lives in one place, the runtime's
     * MagicSeamFollow). The serve never applies those bits to the engine; the mark is the whole contract. One array
     * per hull tenure — put once at the re-point, {@code [1]} rewritten in place after that (no allocation and no
     * reflection on a steady tick). The first write goes through the hull's own {@code setCustomData} when bound —
     * the engine's getter hands back a FRESH detached map while the hull's field is still null, so a put into it
     * would vanish — and is read back by identity: a write the getter does not return counts
     * {@code pilotMarkLostWrites} and retries next frame, never a silent "written". A re-point (first apply,
     * refield, re-key, a new session) removes OUR array from the hull the seat left and arms the post-re-key
     * quarantine: the mask reads 0 until {@link #ABILITY_EDGE_QUARANTINE_CLEAR_FRAMES} consecutive frames carried
     * no ability bit; each non-zero mask withheld counts {@code abilityEdgeQuarantined}. {@code seatId < 1}
     * (unknown: probe mains, observers never reach here) or an unbound seam = no mark, latched once in
     * {@code pilotMarkUnbound}, never a throw. (the 4-slot mark, FOLLOW and
     * the bound first write)
     *
     * <p>Each read-back first write also publishes the player-ship mirror ({@link #mirrorPilotShip}: the hull
     * answers itself and each of its station modules answers the hull), so the ship-advance window scopes the
     * hull's own code from the same tick, and every edge that removes the mark unmarks it.
     *
     */
    void applyPilotMark(Object ship, int held, int seatId, PilotApplyState state) {
        if (seatId < 1 || shipGetCustomData == null) {
            state.pilotMarkUnbound = true;
            return;
        }
        int mask = (held >>> cc.starsector.server.runtime.PilotIntentFrame.ABILITY_SLOT_SHIFT) & 0xFF;
        if (state.pilotMarkShip != ship) {
            java.util.Map<String, Object> customData = customDataOrNull(ship, state);
            if (customData == null) {
                return; // unbound latched; retried next frame (pilotMarkShip unchanged)
            }
            removePilotMarkFrom(state.pilotMarkShip, state);
            int flags = state.pilotMarkFlagsSource == null ? 0 : state.pilotMarkFlagsSource.getAsInt() & 0xFF;
            // The seat's HELLO keymap, read here beside the flags and never per
            // frame (a null source = 0 = the serve's own map); every applied frame's held-state push carries it.
            state.pilotKeymapLo = state.pilotKeymapLoSource == null ? 0L : state.pilotKeymapLoSource.getAsLong();
            state.pilotKeymapHi = state.pilotKeymapHiSource == null ? 0L : state.pilotKeymapHiSource.getAsLong();
            int[] mark = new int[] {seatId, 0, 0, flags};
            try {
                if (shipSetCustomData != null) {
                    invoke(shipSetCustomData, ship, "ShipAPI.setCustomData(pilot mark)", PILOT_MARK_KEY, mark);
                } else {
                    customData.put(PILOT_MARK_KEY, mark);
                }
            } catch (RuntimeException putFault) { // a read-only map / a throwing setter: unexpressed, retried next frame
                countPilotMarkReadError(putFault, state);
                return;
            }
            java.util.Map<String, Object> readBack = customDataOrNull(ship, state);
            if (readBack == null || readBack.get(PILOT_MARK_KEY) != mark) {
                countPilotMarkLostWrite(state); // the getter does not hand our array back: retried next frame
                return;
            }
            state.pilotMark = mark;
            state.pilotMarkShip = ship;
            state.pilotMarkWrites++;
            state.abilityQuarantineClear = 0;
            mirrorPilotShip(ship);
        }
        boolean quarantined = state.abilityQuarantineClear < ABILITY_EDGE_QUARANTINE_CLEAR_FRAMES;
        if (quarantined) {
            if (mask == 0) {
                state.abilityQuarantineClear++;
            } else {
                state.abilityQuarantineClear = 0; // a set bit restarts the window (the bit-9 law)
                state.abilityEdgeQuarantined++;
            }
        }
        state.pilotMark[1] = quarantined ? 0 : mask;
    }

    /**
     * A seat-leaves-hull edge (hull loss, grace vacate, a new session on the wire id, a transit park, a full
     * staged clear): remove the mark from the live hull resolved for {@code wireId} AND, when it is a different
     * object, OUR array from the hull the state last marked (a hulk the engine may still hold). The next applied
     * frame re-points and re-arms the quarantine. {@code stateOrNull} null = the live hull only (no clear is
     * counted). Never throws: a failed read latches the state unbound.
     */
    public void clearPilotMark(long wireId, PilotApplyState stateOrNull) {
        if (shipGetCustomData == null) {
            return;
        }
        Object live;
        try {
            live = shipForWireIdOrNull(wireId);
        } catch (RuntimeException resolveFault) {
            live = null; // the state's own hull ref below still clears
            countPilotMarkReadError(resolveFault, stateOrNull);
        }
        if (live != null) {
            java.util.Map<String, Object> liveData = customDataOrNull(live, stateOrNull);
            try {
                if (liveData != null) {
                    liveData.remove(PILOT_MARK_KEY);
                }
            } catch (RuntimeException removeFault) {
                countPilotMarkReadError(removeFault, stateOrNull);
            }
            PilotShipWindow.unmarkAnswer(live); // the mark is gone (or unreadable): the hull and its modules lose the answer
        }
        if (stateOrNull == null) {
            return;
        }
        if (stateOrNull.pilotMarkShip != null && stateOrNull.pilotMarkShip != live) {
            removePilotMarkFrom(stateOrNull.pilotMarkShip, stateOrNull);
        }
        stateOrNull.pilotMarkShip = null;
        stateOrNull.pilotMarkClears++;
    }

    /**
     * Remove OUR array from {@code shipOrNull} — conditional, so a mark another seat wrote since stays, and with
     * it that hull's player-ship mirror entries; a hull left with no mark (or an unreadable map) is unmarked.
     */
    private void removePilotMarkFrom(Object shipOrNull, PilotApplyState state) {
        if (shipOrNull == null || state.pilotMark == null) {
            return;
        }
        java.util.Map<String, Object> data = customDataOrNull(shipOrNull, state);
        boolean otherSeatMark = false;
        try {
            if (data != null) {
                data.remove(PILOT_MARK_KEY, state.pilotMark);
                otherSeatMark = data.get(PILOT_MARK_KEY) != null;
            }
        } catch (RuntimeException removeFault) {
            countPilotMarkReadError(removeFault, state);
        }
        if (!otherSeatMark) {
            PilotShipWindow.unmarkAnswer(shipOrNull);
        }
    }

    /**
     * The player-ship mirror's ONE write site, at a re-point's read-back first write (never per frame): the hull
     * answers itself, and each station module under it (walked recursively off {@code getChildModulesCopy}, the
     * same walk {@link SeatHullParts#collect} makes, without its fighters and drones: they keep the engine's
     * answer) answers the hull, as module code sees the root as the player in single player. A module that
     * carries a seat's own mark keeps answering itself whichever seat re-pointed last. A failed walk logs once
     * and counts {@code pilotShipModuleFaults}; the hull's own entry stands. The holder ignores a call off the
     * bound sim thread.
     */
    private void mirrorPilotShip(Object root) {
        PilotShipWindow.mark(root, root);
        try {
            mirrorModules(root, root, 0);
        } catch (ReflectiveOperationException | RuntimeException walkFault) {
            if (pilotShipModuleFaults++ == 0) {
                System.out.println("pilot-ship module_map_error err=" + walkFault.getClass().getSimpleName()
                        + " (logged once; the hull answers itself, its modules keep the engine's answer)");
            }
        }
    }

    /** Depth-bounded (a station's modules are one level; the bound only stops a malformed cycle). */
    private void mirrorModules(Object hull, Object root, int depth) throws ReflectiveOperationException {
        if (depth >= 4) {
            return;
        }
        Method children = shipGetChildModulesCopy;
        if (children == null || !children.getDeclaringClass().isInstance(hull)) {
            children = hull.getClass().getMethod("getChildModulesCopy");
            shipGetChildModulesCopy = children;
        }
        Object modules = children.invoke(hull);
        if (!(modules instanceof List<?> list)) {
            return;
        }
        for (Object module : list) {
            if (module == null || module == root) {
                continue;
            }
            java.util.Map<String, Object> data = customDataOrNull(module, null);
            if (data == null || data.get(PILOT_MARK_KEY) == null) {
                PilotShipWindow.mark(module, root);
            }
            mirrorModules(module, root, depth + 1);
        }
    }

    /** {@code ship.getCustomData()}, or null (a null / non-map result or a throw latches the state unbound). */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> customDataOrNull(Object ship, PilotApplyState stateOrNull) {
        try {
            Object data = shipGetCustomData.invoke(ship);
            if (data instanceof java.util.Map<?, ?> map) {
                return (java.util.Map<String, Object>) map;
            }
            if (stateOrNull != null) {
                stateOrNull.pilotMarkUnbound = true;
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException fault) {
            countPilotMarkReadError(fault, stateOrNull);
            return null;
        }
    }

    /**
     * A first write the hull's getter did not hand back by identity (the detached-map case: a put into the fresh
     * map the getter returns while the hull's field is null): counted per state, logged once, never a throw — the
     * state keeps its old tenure, so the next applied frame retries.
     */
    private void countPilotMarkLostWrite(PilotApplyState state) {
        state.pilotMarkLostWrites++;
        if (pilotMarkLostWrites++ == 0) {
            System.out.println("pilot-mark write_lost setter=" + (shipSetCustomData != null ? "bound" : "unbound")
                    + " (the hull's getter did not return the written mark; retried next frame; logged once)");
        }
    }

    private void countPilotMarkReadError(Exception fault, PilotApplyState stateOrNull) {
        if (stateOrNull != null) {
            stateOrNull.pilotMarkUnbound = true;
        }
        if (pilotMarkReadErrors++ == 0) {
            System.out.println("pilot-mark read_error err=" + fault.getClass().getSimpleName()
                    + " (logged once; the mark stays unexpressed where it fails)");
        }
    }

    Object shipForWireIdOrNull(long shipId) {
        return binding.shipForWireIdOrNull(shipId);
    }

    private void maybeSuppressShipAi(Object ship) {
        binding.maybeSuppressShipAi(ship);
    }

    List<?> weaponGroupsCopyOrNull(Object ship) {
        return binding.weaponGroupsCopyOrNull(ship);
    }
}
