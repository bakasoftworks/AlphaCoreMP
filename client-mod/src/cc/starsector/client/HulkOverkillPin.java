package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;

/**
 * Hulk hit-FX: hold a wire piece's overkill break machine CLOSED with the
 * engine's own suppressor idiom, so the blanket hulk veto can be lifted off wire pieces and
 * vanilla's applied-damage-keyed per-hit presentation (damage-tiered impact sounds, per-hit
 * debris, full-size hit glow) returns under local fire.
 *
 * <p>Vanilla keys the per-hit presentation ENTIRELY to the APPLIED-damage result: the sound
 * selector silences a zero-total hit and per-hit debris keys
 * off post-veto hull damage — the blanket veto zeroed both on corpses
 * (the "wimpy" hulk hits). With damage flowing, a hulk (HP=0) accumulates
 * {@code overkill} on every hull hit and force-splits/vaporizes past
 * {@code 3×maxHitpoints} — local membership churn the server owns. The
 * engine's OWN idiom for exactly this: {@code setOverkill(-1.0E8F)} — PUBLIC
 * and first-party-used by the ship class itself and by {@code DroneAI}
 * to hold the machine closed while damage flows. Pinned at mint + ADD
 * cut BOTH halves ({@code splitShip} resets both halves' overkill to 0) +
 * every keep-alive sweep (belt): crossing the threshold would need &gt;1e8 hull damage in one
 * frame.
 *
 * <p>The {@link HulkBreakSfxAdapter} linkage posture: a direct compile-time engine-typed cast
 * ({@code instanceof} guard, no reflection — loader-B law); the build itself proves the method
 * (no smoke owed, unlike the VarHandle case). Fail-soft: any surprise latches
 * {@link #isDisabled()} + ONE log line — the veto reads it and reverts to the blanket hulk
 * veto (today's behavior, the safe direction), never a throw into the damage path.
 */
final class HulkOverkillPin {

    /** Latched on the first failure — one loud line, never per-hit spam; the veto reads this
     * and falls back to the blanket hulk veto (the fail-soft direction). */
    private static volatile boolean disabled;

    private HulkOverkillPin() {
    }

    /**
     * Pin one wire piece's overkill accumulator to the engine's own {@code -1e8} suppressor
     * value (idempotent; called at mint / ADD cut both halves / each keep-alive sweep).
     * Returns true iff the pin landed. Never throws.
     */
    static boolean pin(ShipAPI ship) {
        if (ship == null || disabled) {
            return false;
        }
        try {
            if (!(ship instanceof com.fs.starfarer.combat.entities.Ship)) {
                // every combat ship IS the engine's Ship — a miss means the engine shape moved,
                // and every other pin would miss identically: latch the blanket-veto fallback
                disable("not combat.entities.Ship: " + ship.getClass().getName());
                return false;
            }
            ((com.fs.starfarer.combat.entities.Ship) ship).setOverkill(-1.0E8F);
            return true;
        } catch (Throwable t) {
            disable(t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    /** True once any pin failed — the veto's signal to revert to the blanket hulk veto. */
    static boolean isDisabled() {
        return disabled;
    }

    private static void disable(String why) {
        if (!disabled) {
            disabled = true;
            PilotWatchLog.info("predict hulk-overkill-pin: DISABLED (" + why + ")");
        }
    }
}
