package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;

/**
 * The engine's own hulk-break/explosion SFX, replayed for a
 * server-authoritative corpse vaporize ({@code HKFR} kind 3) or mid-life overkill break (ADD
 * past the death window). Vanilla plays exactly ONE sound for both lanes:
 * the engine's combat-sound facade call
 * {@code (hullStyle, hullSize, loc, vel, pitch=1f, vol=0.5f)}, which resolves the
 * hull style's {@code explosionShipSet} (default {@code "explosion_ship"}) through the SpecStore
 * {@code K} set — with the size argument CAPPED at FRIGATE unless the ship is a fighter (the
 * engine's own cap).
 *
 * <p>The {@link FireSoundAdapter} idiom: direct compile-time engine links ({@code instanceof}
 * guard, NO {@code java.lang.reflect.*} — loader-B law), every surprise caught as
 * {@code Throwable}. Fallback = the API {@code playSound("explosion_ship", 1f, 0.5f, loc, vel)}
 * (the {@code AuthoritativePresenter} sound idiom) — loses only a hull style's per-style
 * override. Never throws into the presentation path.
 */
final class HulkBreakSfxAdapter {

    private HulkBreakSfxAdapter() {
    }

    /**
     * Play the vanilla hulk-break SFX at the piece's pose; {@code true} iff a sound was played
     * (native or fallback). The caller gates on {@code getExplosionScale() > 0} — the vanilla
     * gate at both call sites — and owns the debounce; this only makes the noise.
     */
    static boolean play(ShipAPI piece) {
        if (piece == null) {
            return false;
        }
        ShipAPI.HullSize size = piece.isFighter()
                ? ShipAPI.HullSize.FIGHTER : ShipAPI.HullSize.FRIGATE;
        try {
            if (piece instanceof com.fs.starfarer.combat.entities.Ship) {
                com.fs.starfarer.loading.specs.OoOO hullStyle =
                        ((com.fs.starfarer.combat.entities.Ship) piece).getHullStyle();
                if (hullStyle != null) {
                    com.fs.starfarer.combat.entities.ship.A.I.o00000(hullStyle, size,
                            piece.getLocation(), piece.getVelocity(), 1f, 0.5f);
                    return true;
                }
            }
        } catch (Throwable ignore) {
            // engine shape moved or the facade threw — fall through to the API fallback
            // Engine-drift signal, even when the API fallback below still plays.
            FallbackAnnounce.announceOnce("x4-hulk-sfx-engine-moved", "native facade miss ("
                    + ignore.getClass().getSimpleName() + ") -> generic API sound");
        }
        try {
            com.fs.starfarer.api.Global.getSoundPlayer().playSound(
                    "explosion_ship", 1f, 0.5f, piece.getLocation(), piece.getVelocity());
            return true;
        } catch (Throwable ignore) {
            // 1290 (81) X4: both paths failed — hulk-break SFX silently absent.
            FallbackAnnounce.announceOnce("x4-hulk-sfx-all-paths-failed",
                    "native + API sound both failed");
            return false;
        }
    }
}
