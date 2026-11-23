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
 * guard; the keyword-named facade class binds once by name; NO {@code java.lang.reflect.*} — loader-B law), every surprise caught as
 * {@code Throwable}. Fallback = the API {@code playSound("explosion_ship", 1f, 0.5f, loc, vel)}
 * (the {@code AuthoritativePresenter} sound idiom) — loses only a hull style's per-style
 * override. Never throws into the presentation path.
 */
final class HulkBreakSfxAdapter {

    private HulkBreakSfxAdapter() {
    }

    /**
     * On 0.98.5a-RC4 the facade class was {@code combat.entities.ship.A.int}, a Java
     * keyword no source can name (on RC7 it is {@code combat.entities.ship.A.I}), so it binds by name through {@link EngineNames} (Windows-build owner + descriptor),
     * resolved ONCE on the first {@link #play} (holder idiom). A resolve failure fails this holder's init, which
     * {@link #play}'s {@code Throwable} catch takes as a facade miss (announce + API fallback), as before.
     */
    private static final class Facade {
        private static final String OWNER = "com.fs.starfarer.combat.entities.ship.A.int";
        /** The facade's hulk-break sound call. */
        static final java.lang.invoke.MethodHandle BREAK_SFX;

        static {
            try {
                BREAK_SFX = java.lang.invoke.MethodHandles.lookup().findStatic(
                        Class.forName(EngineNames.cls(OWNER)),
                        EngineNames.member(OWNER, "super", "(Lcom/fs/starfarer/loading/specs/A;"
                                + "Lcom/fs/starfarer/api/combat/ShipAPI$HullSize;Lorg/lwjgl/util/vector/Vector2f;"
                                + "Lorg/lwjgl/util/vector/Vector2f;FF)V"),
                        java.lang.invoke.MethodType.methodType(void.class,
                                com.fs.starfarer.loading.specs.A.class, ShipAPI.HullSize.class,
                                org.lwjgl.util.vector.Vector2f.class, org.lwjgl.util.vector.Vector2f.class,
                                float.class, float.class));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
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
                com.fs.starfarer.loading.specs.A hullStyle =
                        ((com.fs.starfarer.combat.entities.Ship) piece).getHullStyle();
                if (hullStyle != null) {
                    Facade.BREAK_SFX.invokeExact(hullStyle, size,
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
