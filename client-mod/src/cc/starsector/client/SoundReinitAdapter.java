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
    /**
     * On 0.98.5a-RC4 the three bound methods were named {@code if} / {@code do} /
     * {@code int}, Java keywords no source can call, so each
     * binds by name through {@link EngineNames} (Windows-build owner + descriptor), resolved ONCE in the static block. First touch = {@code onApplicationLoad}, after the link
     * probe latches; a resolve failure fails this class's init, which the caller's {@code Throwable} catch reports.
     */
    private static final String SOUND_OWNER = "sound.ooOO";
    private static final String SETTINGS_OWNER = "com.fs.starfarer.settings.StarfarerSettings";
    /** The singleton getter. */
    private static final java.lang.invoke.MethodHandle INSTANCE;
    /** The "AL created" flag. */
    private static final java.lang.invoke.MethodHandle IS_CREATED;
    /** {@code StarfarerSettings.int()}, the sound-enabled check. */
    private static final java.lang.invoke.MethodHandle IS_ENABLED;

    static {
        java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
        try {
            INSTANCE = lookup.findStatic(sound.ooOO.class,
                    EngineNames.member(SOUND_OWNER, "ö00000", "()Lsound/ooOO;"),
                    java.lang.invoke.MethodType.methodType(sound.ooOO.class));
            IS_CREATED = lookup.findVirtual(sound.ooOO.class,
                    EngineNames.member(SOUND_OWNER, "oO0000", "()Z"),
                    java.lang.invoke.MethodType.methodType(boolean.class));
            IS_ENABLED = lookup.findStatic(StarfarerSettings.class,
                    EngineNames.member(SETTINGS_OWNER, "õ00000", "()Z"),
                    java.lang.invoke.MethodType.methodType(boolean.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private SoundReinitAdapter() { }

    /** The engine's sound singleton. */
    private static sound.ooOO engine() throws Throwable {
        return (sound.ooOO) INSTANCE.invokeExact();
    }

    /** The "AL created" flag {@code AL.create()} success sets. */
    static boolean isCreated() throws Throwable {
        return (boolean) IS_CREATED.invokeExact(engine());
    }

    /** The settings' sound-enabled check = the sound-enabled setting {@code && !CombatMain.AL_DESTROYED} — the
     *  exact gate {@code CombatMain} puts in front of the init call and in front of the per-frame
     *  update. {@code false} = the engine never attempted init (player has sound off) or turned it off after an
     *  init Throwable (the sound-enabled setter called with false + alert). The init-attempted latch
     *  has no public getter; this flag separates "sound off" from "create failed" (the
     *  crash class: created=false while this stays true, so the un-gated listener calls hit a null device). */
    static boolean isEnabled() throws Throwable {
        return (boolean) IS_ENABLED.invokeExact();
    }

    /** The engine's re-init: {@code AL.destroy()} (null-guarded inside LWJGL) then the {@code CombatMain}
     *  init call verbatim. Throws whatever the engine throws ({@code AL10.alDistanceModel} is un-guarded
     *  after a failed create) — the caller reports it. */
    static void reinit() throws Throwable {
        engine().new_(200, com.fs.starfarer.String.do_.Ô00000,
                (int) StarfarerSettings.if_("maxFXSources"), (int) StarfarerSettings.if_("maxUISources"));
    }
}
