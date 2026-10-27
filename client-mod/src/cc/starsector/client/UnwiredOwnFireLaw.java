package cc.starsector.client;

/**
 *
 * The pure verdict behind the unwired-own-fire detector in
 * {@link EdgeEventLane#declareFireDischarge}. java.lang-only ON PURPOSE so
 * {@code SyncDefectLawSelftest} drives it headless through {@link SyncDefectLawProbe} (rows
 * {@code uf-*}).
 *
 * <p>The hole: own fire rides the wire ONLY as the raw {@code Mouse.isButtonDown(0)} level sample
 * ({@code fireHeld}, the held lane) plus the autofire-group MASK mirror. A PDCL declaration is
 * ignored by the server unless its {@code declaredFireLane} is on (default off). So when the
 * vanilla ship-AI drives the own hull — the stranger pressed {@code U} (autopilot), or ordered
 * their own hull on the command map — every SELECTED-group shot the AI takes discharges LOCALLY,
 * the server's own twin holds fire, the foe reconciles undamaged, and until this law nothing
 * logged: a silent sibling of the refit hole ({@link OwnHullForkLaw}).
 *
 * <p>The verdict is the MEASUREMENT of the recon's one inferred hinge — does the vanilla AI fire
 * through autofire (mirrored) or the selected group (unwired)? The counters on the GAP2 fire-meter
 * line answer it from the next WATCH log with zero owner attention. A player-facing HUD line is
 * phase 2, AFTER that measurement (never tell a stranger something unproven).
 *
 * <p>Clocks are combat SIM SECONDS (the engine's per-frame {@code advance(amount)} dt, summed), never
 * frames: the design said "120 frames", which is 2 s at 60 fps but 0.22 s at the owner's
 * 540 fps — a frame grace would mis-file a wired burst tail as unwired on a fast box.
 * Sim time also stands still while paused, when nothing discharges.
 *
 * <p>Residuals (recorded, not hidden): beams are not projectile births (uncounted);
 * {@link Verdict#UNWIRED_OTHER} also catches a rebound fire key (the mod samples mouse-0 only) —
 * graded from the same counters; a manual pip within the grace of an AI shot files that shot as
 * held (grade the hands-off autopilot leg, not an interleaved one).
 */
final class UnwiredOwnFireLaw {

    /** What a local own-weapon discharge is, wire-wise. */
    enum Verdict {
        /** An autofiring NON-selected group: engine behaviour the autofire-mask mirror reproduces
         *  server-side (never declared; {@code fireDeclAutofireSkipped}). */
        WIRED_AUTOFIRE,
        /** The trigger is held now, or was released within {@link #GRACE_SECONDS}: the held lane
         *  carries it (a burst/beam tail after the release still rides the server's own weapon clock). */
        WIRED_HELD,
        /** A ship-SYSTEM slot weapon (the flare launchers, {@code canister_flak}): its trigger is the
         *  SYSTEM press, which rides the up-lane's system bit — a dwell system as the {@code isOn()}
         *  level, a zero-dwell one through {@link AcceptedPressHold} — never the fire level. Filed by
         *  the fire-button law it read UNWIRED_OTHER and told the player to "fire with the mouse
         *  button" on their 5th flare volley. */
        WIRED_SYSTEM,
        /** No trigger within the grace and the engine's UI autopilot is ON: the vanilla ship-AI fired
         *  the selected group locally — the server's own twin never fires this shot. */
        UNWIRED_AUTOPILOT,
        /** No trigger within the grace and autopilot OFF: a source the mouse-0 sample never sees
         *  (a rebound fire key, a script) — equally unwired. */
        UNWIRED_OTHER
    }

    /** Sim seconds after the trigger's release during which a discharge still counts as held: the
     *  server bursts on its own clock after the level sample drops, so the tail is the held lane
     *  finishing, not new input. Sized to vanilla's LONGEST single-trigger burst span — squall
     *  20 × 0.5 s = 9.5 s (gazer_payload 4.5 s, locust 2.9 s; {@code weapon_data.csv})
     *  — because the mod is public and any loadout must be assumed; the price is that an AI
     *  shot within 10 s of a manual pip files as held, so the measurement leg is hands-off. */
    static final float GRACE_SECONDS = 10f;

    /** One {@code PilotWatchLog.error} at most per this many sim seconds while unwired discharges accrue. */
    static final float ERROR_EVERY_SECONDS = 5f;

    /** {@code secondsSinceFireHeld} sentinel: the trigger has NEVER been held this combat (stays infinite). */
    static final float NEVER_HELD = Float.POSITIVE_INFINITY;

    /** {@code lastErrorSec} sentinel: no error line yet. */
    static final float NO_ERROR_YET = Float.NaN;

    private UnwiredOwnFireLaw() {
    }

    /**
     * @param autofireNonSelected  the discharging weapon's group is autofiring AND is not the selected group
     * @param secondsSinceFireHeld sim seconds since the held-fire level sample last read true (0 = held this
     *                             frame; {@link #NEVER_HELD} = never)
     * @param autopilot            the vanilla ship-AI is driving the own hull at the discharge — read as
     *                             {@code ownShip.getShipAI() != null} (the engine's own control test,
     *                             U attaches an AI AND sets the autopilot
     *                             field, a command-map order on the own hull attaches an AI without it;
     *                             the mod nulls the own AI for native player control). NOT the API's
     *                             {@code isUIAutopilotOn()}: it returns
     *                             that field NEGATED.
     */
    static Verdict classify(boolean autofireNonSelected, float secondsSinceFireHeld, boolean autopilot) {
        if (autofireNonSelected) {
            return Verdict.WIRED_AUTOFIRE;
        }
        if (secondsSinceFireHeld <= GRACE_SECONDS) {
            return Verdict.WIRED_HELD;
        }
        return autopilot ? Verdict.UNWIRED_AUTOPILOT : Verdict.UNWIRED_OTHER;
    }

    /**
     * The discharge-site entry: a SYSTEM-slot weapon is the system lane's shot whatever the trigger,
     * the group or the autopilot say (it has no weapon group; the fire level never fires it) — checked
     * FIRST; every other weapon falls to the fire-button law above.
     */
    static Verdict classify(boolean systemSlot, boolean autofireNonSelected, float secondsSinceFireHeld,
            boolean autopilot) {
        return systemSlot ? Verdict.WIRED_SYSTEM : classify(autofireNonSelected, secondsSinceFireHeld, autopilot);
    }

    /** Is the verdict one the server's own twin never reproduces? */
    static boolean unwired(Verdict v) {
        return v == Verdict.UNWIRED_AUTOPILOT || v == Verdict.UNWIRED_OTHER;
    }

    /**
     * The per-frame step of {@code secondsSinceFireHeld}: held resets to 0, released adds this frame's
     * sim dt ({@code advance(amount)}; 0 while paused). A non-finite or negative dt adds nothing; an
     * unreadable prior reads as {@link #NEVER_HELD}; infinity stays infinite.
     */
    static float tickSecondsSinceHeld(boolean fireHeld, float prior, float dtSeconds) {
        if (fireHeld) {
            return 0f;
        }
        if (Float.isNaN(prior)) {
            return NEVER_HELD;
        }
        if (!(dtSeconds > 0f) || Float.isInfinite(dtSeconds)) {
            return prior;
        }
        return prior + dtSeconds;
    }

    /** Is the capped error line due at sim time {@code nowSec}? {@code lastErrorSec} NaN = never logged. */
    static boolean errorDue(float nowSec, float lastErrorSec) {
        if (Float.isNaN(lastErrorSec)) {
            return true;
        }
        return nowSec - lastErrorSec >= ERROR_EVERY_SECONDS;
    }
}
