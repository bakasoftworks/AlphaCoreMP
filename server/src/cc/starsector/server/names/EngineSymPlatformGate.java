package cc.starsector.server.names;

/**
 * The engine-name rows of the platform gate area only: the valid-key gate at serve start and the host-side game-build
 * reader name the game's launcher-settings class (hashed as a class file, then its static singleton accessor and its
 * instance activation check called), and the game's version holder with its compiled version constant. Same grammar
 * as {@link EngineSym} (one line per row, the key repeated as the first argument, the trailing home cite); the pin is
 * {@link EngineSym#PIN}.
 */
public final class EngineSymPlatformGate {

    private EngineSymPlatformGate() {
    }

    // The game's launcher-settings class: its class file is the drift guard's hashed entry; the gate calls exactly
    // the static singleton accessor (returns the class itself) and the instance activation check on it.
    public static final EngineRow PLATFORM_GATE_LAUNCHER_CLASS = EngineRow.cls("PLATFORM_GATE_LAUNCHER_CLASS", "com/fs/starfarer/launcher/Object"); //
    public static final EngineRow PLATFORM_GATE_LAUNCHER_SINGLETON_METHOD = EngineRow.staticMethod("PLATFORM_GATE_LAUNCHER_SINGLETON_METHOD", "com/fs/starfarer/launcher/Object", "\u00D300000", "()Lcom/fs/starfarer/launcher/Object;"); //
    public static final EngineRow PLATFORM_GATE_ACTIVATION_CHECK_METHOD = EngineRow.method("PLATFORM_GATE_ACTIVATION_CHECK_METHOD", "com/fs/starfarer/launcher/Object", "\u00D600000", "()Z"); //

    // The game's version holder (a non-renamed class) and the compiled version constant the drift guard compares.
    public static final EngineRow PLATFORM_GATE_VERSION_FIELD = EngineRow.staticField("PLATFORM_GATE_VERSION_FIELD", "com/fs/starfarer/Version", "versionOnly", "Ljava/lang/String;"); //
}
