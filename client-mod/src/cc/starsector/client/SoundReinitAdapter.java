package cc.starsector.client;

import com.fs.starfarer.settings.StarfarerSettings;

/**
 * Engine-typed adapter over the engine's sound singleton — the ONE
 * place the mod names it.
 * <p>
 * WHY: sound init runs once in {@code CombatMain};
 * {@code AL.create()} failing there (a joiner's box refusing the game's
 * own {@code OpenAL64.dll} by a RELATIVE path) leaves the listener unbound while the per-frame
 * {@code alListener} calls stay un-gated — the {@code Fatal nalListenerfv} crash. The engine's own
 * settings-screen re-init ({@code AL.destroy()} + latch clear +
 * init again) is the lever: call it once from {@code onApplicationLoad} (same thread, after
 * the {@code CombatMain} sound init, before sound sets load) with the absolute {@code org.lwjgl.librarypath} set.
 * Engine-typed adapter classes are classloader-B legal; the engine classes are on the game's
 * own classpath. Constants mirror the {@code CombatMain} init call verbatim.
 */
final class SoundReinitAdapter {
    private SoundReinitAdapter() { }

    /** The "AL created" flag {@code AL.create()} success sets. */
    static boolean isCreated() {
        return sound.C.ö00000().oO0000();
    }

    /** The settings' sound-enabled check = the sound-enabled setting {@code && !CombatMain.AL_DESTROYED} — the
     *  exact gate {@code CombatMain} puts in front of the init call and in front of the per-frame
     *  update. {@code false} = the engine never attempted init (player has sound off) or turned it off after an
     *  init Throwable ({@code while(false)} + alert). The init-attempted latch
     *  has no public getter; this flag separates "sound off" from "create failed" (the
     *  crash class: created=false while this stays true, so the un-gated listener calls hit a null device). */
    static boolean isEnabled() {
        return StarfarerSettings.õ00000();
    }

    /** The engine's re-init: {@code AL.destroy()} (null-guarded inside LWJGL) then the {@code CombatMain}
     *  init call verbatim. Throws whatever the engine throws ({@code AL10.alDistanceModel} is un-guarded
     *  after a failed create) — the caller reports it. */
    static void reinit() {
        sound.C.ö00000().Ò00000(200, com.fs.starfarer.D.M.Ô00000,
                (int) StarfarerSettings.ö00000("maxFXSources"), (int) StarfarerSettings.ö00000("maxUISources"));
    }
}
