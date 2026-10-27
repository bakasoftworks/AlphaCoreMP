package cc.starsector.client;

/**
 * The pure
 * law behind SERVING autopilot fire through the EXISTING held lane — zero wire change. java.lang-only
 * ON PURPOSE so {@code SyncDefectLawSelftest} drives it headless through {@link SyncDefectLawProbe}
 * (rows {@code uf-derive-*} / {@code uf-aim-*}); the engine reads live in {@link AutopilotFireLane}.
 *
 * <p>The hole it closes (one measured match: 241 of 312 own discharges
 * unserved): under a ship-AI (U autopilot, or a command-map order on the own hull) the AI fires the
 * SELECTED group locally, but the wire's fire level is {@code Mouse.isButtonDown(0)} alone, the server
 * strips the twin's AI ({@code maybeSuppressShipAi}) and drops PDCL at intake under the default held
 * lane — so those shots fire on the shooter's screen only. The law: the wire level is mouse-0 OR
 * (AI-driven AND the selected group shows fire), and under an AI the aim world point is the AI's own
 * aim (the firing weapon's slewed ray, else its ship target), never the idle physical cursor.
 *
 * <p>Trust: the SAME class as the held sample it feeds (a patched client already owns
 * {@code fireHeld}; the twin's engine cooldown gate stays the rate floor) — no new input surface, no
 * row in the wire-surface inventory.
 *
 * <p>"Shows fire" = the engine's {@code WeaponAPI.isFiring()} = weapon state != IDLE (charging,
 * firing/burst AND cooldown — cooldown is its own state, as noted in
 * {@code FireLaneCoordinator}). Reading the non-idle span, not the birth frame, is deliberate: a
 * single-frame FIRING state could fall between two 20 tps intent samples, while idle→charge→fire→
 * cooldown→idle is continuous for as long as the AI keeps engaging, so the level reads like a human
 * holding the trigger and the twin fires at its own engine-gated rate. Residual (recorded, not
 * hidden): the level drops one cooldown span after the AI's LAST shot, so the twin may fire one
 * extra shot at an engagement's end — the same tail the manual held lane already has.
 *
 * <p>The FIRST shot of an engagement: the input capture runs BEFORE the engine frame that births it,
 * so that frame's wire sample still reads false; the NEXT capture sees the weapon non-idle (level
 * true + a {@code fireEdgeSeq} rising edge, which the server's depth-1 latch presses on) — the twin's
 * first shot lands one frame late, then the level carries the burst. The unwired-own-fire verdict
 * therefore reads the derived level AT the discharge instant ({@link AutopilotFireLane#heldRecency}),
 * not the frame-start sample, or every engagement would log one false UNWIRED_AUTOPILOT.
 */
final class AutopilotFireServeLaw {

    /** Where this frame's wire aim world point comes from. */
    enum AimSource {
        /** No ship-AI (or nothing better): the physical cursor's {@code getMouseTarget()}. */
        CURSOR,
        /** A non-idle selected-group weapon's own ray — its slewed angle projected to its range. */
        WEAPON_RAY,
        /** The AI's {@code getShipTarget()} location (no selected-group weapon non-idle yet). */
        SHIP_TARGET
    }

    /** Unwired own discharges in one combat before the ONE player-facing notice line. */
    static final int NOTICE_AFTER_UNWIRED = 5;

    private AutopilotFireServeLaw() {
    }

    /**
     * The held-fire LEVEL the wire carries this frame.
     *
     * @param mouseHeld           mouse-0 down (AFTER the (re)entry swallow latch — the latch guards
     *                            the mouse, never the AI)
     * @param aiDriven            a ship-AI drives the own hull ({@code ownShip.getShipAI() != null})
     * @param selectedGroupFiring any weapon of the SELECTED group is non-idle
     * @return mouse-0, or the AI's selected-group fire; a non-idle group WITHOUT an AI never
     *         synthesizes (that is the mouse's own burst tail, or a rebound key the
     *         {@code UnwiredOwnFireLaw} measurement must keep seeing as UNWIRED_OTHER)
     */
    static boolean deriveHeld(boolean mouseHeld, boolean aiDriven, boolean selectedGroupFiring) {
        return mouseHeld || (aiDriven && selectedGroupFiring);
    }

    /** The aim source for this frame: AI-derived ONLY under an AI, the weapon ray first, else the target, else the cursor. */
    static AimSource aimSource(boolean aiDriven, boolean rayFinite, boolean targetFinite) {
        if (!aiDriven) {
            return AimSource.CURSOR;
        }
        if (rayFinite) {
            return AimSource.WEAPON_RAY;
        }
        return targetFinite ? AimSource.SHIP_TARGET : AimSource.CURSOR;
    }

    /**
     * The world point {@code range} ahead of ({@code x},{@code y}) along {@code angleDeg} (engine
     * convention: degrees, 0 = +x, counter-clockwise). {@code null} unless every input is finite and
     * the range is positive — the caller then falls through to the next aim source.
     */
    static float[] projectRay(float x, float y, float angleDeg, float range) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(angleDeg)
                || !(range > 0f) || Float.isInfinite(range)) {
            return null;
        }
        double rad = Math.toRadians(angleDeg);
        float px = (float) (x + range * Math.cos(rad));
        float py = (float) (y + range * Math.sin(rad));
        if (!Float.isFinite(px) || !Float.isFinite(py)) {
            return null;
        }
        return new float[] {px, py};
    }

    /** Is the one-per-combat player notice due: {@code unwiredTotal} own discharges filed UNWIRED, none shown yet. */
    static boolean noticeDue(boolean shown, int unwiredTotal) {
        return !shown && unwiredTotal >= NOTICE_AFTER_UNWIRED;
    }
}
