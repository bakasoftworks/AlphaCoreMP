package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.hullmods.MissileAutoloader;
import java.util.Arrays;

/**
 * The OWN-ship Missile-Autoloader RELOAD-HOLD mirror, knob {@code cc.predict.reloadMirror} =
 * {@code off|meter|apply} (default {@code apply}; invalid THROWS at class load).
 *
 *
 * <p>WHY: a live run's F9 mark "Hammer bounce". The vanilla
 * {@code missile_autoloader} hullmod reloads an empty small missile mount on its own random clock and
 * pins its cooldown at full for 5 s; the server's reload landed first, the ownlock ammo lane mirrored
 * the refilled ammo, so the LOCAL hullmod never saw {@code ammo <= 0}, never held the mount, and the
 * player's press inside the server's hold fired locally and was retired ({@code timeout-futile}).
 * The law ({@link OwnReloadHoldLaw}) detects the SERVER's reload from the two-signal
 * wire edge; this lane writes it into the vanilla hullmod's OWN {@code MissileAutoloaderData}
 * {@code TimeoutTracker}, so the hullmod's own {@code advanceInCombat} pins the local cooldown each
 * frame for the hold exactly as the server's does (the tracker
 * advances by {@code amount × getMissileRoFMult()} on both sides). Engine-native seam, no new wire
 * field (the wire already carries per-mount {@code ammo} + {@code cooldownRemaining} +
 * {@code cooldown}). A confirmed reload the local tracker does not hold = HOLD + the capacity debit
 * ({@code opLeft −= getReloadCost}, as the server's hullmod does); one it already holds (the client's
 * own interval reloaded first) = REBASE to the full hold, no second debit. {@code meter} mode decides
 * and counts, writes nothing.
 *
 * <p>Frame residency: runs only inside {@code FireLaneCoordinator.maintainOwnFireLock}'s
 * FRESH-frame per-carried-mount pass (so {@code cc.predict.ownFireLock=off} also silences it), BEFORE
 * that pass's ammo/cooldown laws; per carried mount = one array read + one {@code decide}, and the
 * {@code isAffected} / {@code hasHullMod} / {@code getCustomData().get} reads run only when METER and
 * the mount is carried (short-circuited: no hullmod read for an unaffected mount). No per-tick work
 * rides outside the fresh-frame pass. Never delays a foe/body — the hold is the vanilla
 * hullmod's own on our own hull. Tell rows carry ints/floats only (no player text), capped per JVM.
 */
final class OwnReloadHoldLane {

    static final String MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.reloadMirror", System.getProperty("cc.predict.reloadMirror", "apply"));
    static final boolean METER = !"off".equals(MODE);
    static final boolean APPLY = "apply".equals(MODE);
    /** The vanilla hullmod id ({@code gryphon_Hammer.variant} {@code hullMods}). */
    static final String HULLMOD_ID = "missile_autoloader";
    /** Per-JVM tell-row cap (reloads are sparse: capacity-bounded, ~6 per hull per fight). */
    static final int LOG_MAX = 60;
    private static int logLines;

    private ShipAPI body;
    private final int[] prevAuthAmmo = new int[64];
    private final boolean[] pending = new boolean[64];
    /** Mount whose live tracker entry THIS lane applied: a rise inside it never re-bases. */
    private final boolean[] mirrored = new boolean[64];
    long holds;
    long rebased;
    long unconfirmed;
    long nodata;
    long exhaustedHolds;
    /** Holds this lane applied that the wire's first unpinned read ended early ({@code RELEASE}; the tell's
     *  {@code hold=} field = the local seconds it would have run on = the overshoot instrument). Counted,
     *  not printed: its round token was deleted unread; a RELEASE tell row shows one only while the
     *  lane's {@link #LOG_MAX} tell budget (shared by all four tell kinds) lasts — past it the count is smoke-only. */
    long wireReleased;
    /** OUR applied holds expired locally before a wire RELEASE (APPLY-only; identity
     *  {@code holds >= wireReleased + localExpired}, checked by {@code OwnReloadHoldLawSmoke} only — no log row carries both terms: a rebind drop / a live hold counts in neither) / holds never pinned. */
    long localExpired;
    long deadHolds;
    private long faults;

    OwnReloadHoldLane() {
        Arrays.fill(prevAuthAmmo, -1);
    }

    /**
     * The per-mount state's body binding — IDENTITY, not equals (the
     * {@code FireLaneCoordinator.bindSeedBody} idiom). Every own-body change resets it through this ONE
     * check, no per-path hook: first join (null → body), respawn (new body), round wrap / MRST (new
     * body or a new plugin instance), the post-lift rejoin (a fresh mission = a new plugin), the
     * RosterAppend re-point (new body). A RESUME after a stale feed keeps the SAME body ⇒ the state is
     * kept (the mount's wire history is continuous).
     */
    private void bind(ShipAPI own) {
        if (own != body) {
            body = own;
            Arrays.fill(prevAuthAmmo, -1);
            Arrays.fill(pending, false);
            Arrays.fill(mirrored, false);
        }
    }

    /**
     * One carried own mount on a fresh frame (caller: {@code maintainOwnFireLock}'s per-mount loop,
     * before the ammo/cooldown laws). Decides via {@link OwnReloadHoldLaw#decide}, records the wire
     * ammo + the ARM flag, then HOLDs/REBASEs the local hullmod tracker under APPLY.
     */
    void onCarriedMount(ShipAPI own, WeaponAPI weapon, int idx,
            cc.starsector.client.net.ServerWeaponMountState mount, long simTick) {
        if (!METER || idx < 0 || idx >= 64) {
            return;
        }
        try {
            bind(own);
            boolean affected = MissileAutoloader.isAffected(weapon);
            ShipVariantAPI variant = affected ? own.getVariant() : null;
            boolean hasMod = variant != null && variant.hasHullMod(HULLMOD_ID);
            MissileAutoloader.MissileAutoloaderData data = hasMod
                    ? (MissileAutoloader.MissileAutoloaderData) own.getCustomData().get(MissileAutoloader.MA_DATA_KEY)
                    : null;
            boolean alreadyHeld = data != null && data.cooldown.contains(weapon);
            if (!alreadyHeld) {
                // ONCE per hold (reset below; a RELEASE cleared it in its own call)
                if (mirrored[idx]) {
                    localExpired++;
                }
                mirrored[idx] = false; // our hold expired (or was never applied): the next rise may arm again
            }
            int authAmmo = mount.ammo();
            int v = OwnReloadHoldLaw.decide(affected, hasMod, prevAuthAmmo[idx], authAmmo, pending[idx],
                    mount.cooldownRemaining(), mount.cooldown(), alreadyHeld, alreadyHeld && mirrored[idx]);
            prevAuthAmmo[idx] = authAmmo;
            pending[idx] = v == OwnReloadHoldLaw.ARM;
            if (v == OwnReloadHoldLaw.HOLD_DEBIT || v == OwnReloadHoldLaw.HOLD_REBASE) {
                boolean sMod = variant.getSMods().contains(HULLMOD_ID)
                        || variant.getSModdedBuiltIns().contains(HULLMOD_ID);
                float holdSeconds = sMod ? MissileAutoloader.SMOD_COOLDOWN : MissileAutoloader.BASIC_COOLDOWN;
                if (data == null) {
                    nodata++;
                    tell("NODATA", simTick, idx, mount, holdSeconds, null);
                    return;
                }
                holds++;
                if (v == OwnReloadHoldLaw.HOLD_REBASE) {
                    rebased++;
                }
                if (data.opLeft <= 0.05f) {
                    // The hullmod's early return still pins while its
                    // exhausted-status window runs; drained too = DEAD (an S1 lead: the opLeft drift, never a re-arm)
                    exhaustedHolds++;
                    if (data.showExhaustedStatus <= 0f) {
                        deadHolds++;
                    }
                }
                if (APPLY) {
                    applyHold(data, weapon, holdSeconds,
                            v == OwnReloadHoldLaw.HOLD_DEBIT ? MissileAutoloader.getReloadCost(weapon, own) : 0f);
                    mirrored[idx] = true;
                }
                tell(v == OwnReloadHoldLaw.HOLD_REBASE ? "REBASE" : "HOLD", simTick, idx, mount, holdSeconds, data);
            } else if (v == OwnReloadHoldLaw.UNCONFIRMED) {
                unconfirmed++;
                tell("UNCONFIRMED", simTick, idx, mount, 0f, data);
            } else if (v == OwnReloadHoldLaw.RELEASE) {
                // The server released, ours would have run on for `left`
                // more seconds (= the measured overshoot the fixed-duration hold adds; graded per flight).
                // mirroredLive => alreadyHeld => data != null.
                wireReleased++;
                float left = data.cooldown.getRemaining(weapon);
                if (APPLY) {
                    data.cooldown.remove(weapon); // the local cooldown now decays from full, as the server's does
                }
                mirrored[idx] = false;
                tell("RELEASE", simTick, idx, mount, left, data);
            }
        } catch (Throwable t) {
            faults++;
        }
    }

    /**
     * The act: hold {@code weapon} in the vanilla hullmod's own tracker for {@code holdSeconds} (its
     * {@code advanceInCombat} then pins the cooldown at full each frame) and debit the shared reload
     * capacity by {@code debitCost} clamped at 0 (as the server's hullmod does); {@code debitCost 0} =
     * the REBASE shape. Package-private static so the smoke drives it on a real {@code MissileAutoloaderData}.
     */
    static void applyHold(MissileAutoloader.MissileAutoloaderData data, WeaponAPI weapon, float holdSeconds,
            float debitCost) {
        data.cooldown.set(weapon, holdSeconds);
        if (debitCost > 0f) {
            data.opLeft = Math.max(0f, data.opLeft - debitCost);
        }
    }

    private void tell(String kind, long simTick, int idx, cc.starsector.client.net.ServerWeaponMountState mount,
            float holdSeconds, MissileAutoloader.MissileAutoloaderData data) {
        if (logLines >= LOG_MAX) {
            return;
        }
        logLines++;
        PilotWatchLog.info("predict reload-mirror " + kind + " @tick=" + simTick + " mount=" + idx
                + " auth_ammo=" + mount.ammo() + " auth_cd=" + mount.cooldownRemaining()
                + " hold=" + holdSeconds + " opLeft=" + (data == null ? -1f : data.opLeft)
                + (APPLY ? "" : " (meter)"));
    }

    /** The meter tokens, appended to the {@code predict ownlock-meter} line (leading space). */
    String meterTokens() {
        return " reload_mirror_mode=" + MODE
                + " reload_mirror_holds=" + holds
                + " reload_mirror_rebased=" + rebased
                + " reload_mirror_unconfirmed=" + unconfirmed
                + " reload_mirror_nodata=" + nodata
                + " reload_mirror_exhausted_holds=" + exhaustedHolds
                + " reload_mirror_faults=" + faults
                + " reload_mirror_local_expired=" + localExpired
                + " reload_mirror_dead_holds=" + deadHolds;
    }
}
