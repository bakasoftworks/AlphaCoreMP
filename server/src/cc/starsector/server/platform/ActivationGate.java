package cc.starsector.server.platform;

import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * The valid-key gate at serve start: the server as we ship it starts only on a computer whose own
 * Starsector copy the GAME ITSELF reports as activated, for the OS user account running the server
 * ("it should require a valid key"; design ruled +
 * lensed).
 *
 * <p><b>What it does.</b> It CALLS the game's own check and reads its boolean: the launcher-settings
 * singleton accessor plus its activation check (exactly those two members; the class also holds a
 * raw getter and a writable settings handle, which nothing here ever touches). It never reads,
 * copies, hashes, logs or re-implements a key or the validator, and it never writes to the game's
 * settings. Every probe method returns a class name, the game's compiled version string, a hash of
 * a CLASS FILE, or a boolean — no value from the game's settings store ever reaches this class.
 * It is a default and a statement of how this server is meant to run — nothing more.
 *
 * <p><b>Two seats, one in-JVM memo.</b> {@code PilotIntentCombatServerMain} calls {@link #enforce()}
 * before its first socket and the ~50 s engine boot (the refusal a host reads, exit
 * {@link #EXIT_REFUSED}); {@link RealEngineContentInit#ensureInitialized()} calls it again as the
 * FLOOR every real-engine boot passes (other serve mains and the probe mains never pass the first
 * seat). The first {@link Decision} of this JVM is memoized in memory only — never a file, never
 * across JVMs — so the floor costs nothing on the serve path.
 *
 * <p><b>Order inside {@link #decide}</b> (each step refuses before the next one runs):
 * (1) the prefs root must be one of the JDK's platform stores — a {@code PreferencesFactory} set by
 * system property OR by a ServiceLoader file would otherwise answer the engine's read from a store
 * that is not the OS one; (2) the drift guard: the game's {@code versionOnly} must equal
 * {@link #SUPPORTED_GAME_VERSION} AND the SHA-256 of the check's class file must equal
 * {@link #LAUNCHER_CHECK_CLASS_SHA256} (a rebuilt game jar under the same version string could
 * hand the check's name to another {@code ()Z} method that answers true with no key); (3) the key
 * node must already exist — a pure read, so the engine object is never built on a box that never
 * ran the game; (4) the engine's own check; (5) ANY {@link Throwable} refuses as ERROR, named by
 * its SimpleName only (never its message, never a stack trace).
 *
 * <p><b>No bypass seam.</b> No system property, env var, config row, CLI flag or cache file reads
 * as "skip". The contract rows drive {@link #decide} and a private {@link Memo} instance with fake
 * probes; the JVM's own memo is reachable only through {@link #enforce()} over the real
 * {@link EngineProbe}.
 *
 *
 */
public final class ActivationGate {

    /** Exit code of a refused serve; the supervisor script treats it as FINAL (no relaunch). */
    public static final int EXIT_REFUSED = 78;
    /** The ONE stdout line a VALID decision prints (once per JVM) — no version, no hash, no value. */
    public static final String OK_LINE = "key-gate ok";

    // ---- PINNED 0.98a-RC8 (the drift guard; a new game version = a new server build). ----

    /** The game's compiled {@code com.fs.starfarer.Version.versionOnly} constant this build supports. */
    public static final String SUPPORTED_GAME_VERSION = "0.98a-RC8";
    /**
     * SHA-256 (lowercase hex) of the launcher-settings class file as the
     * pinned install ships it inside the game's core engine jar (that jar's own
     * SHA-256 = the pin {@code 5DD222B9...4CC8}; 9457 bytes).
     * Computed by reading that jar entry and hashing its bytes (PowerShell:
     * {@code [IO.Compression.ZipFile]::OpenRead(jar).GetEntry(<entry>).Open()} into
     * {@code SHA256.ComputeHash}; any {@code unzip -p <jar> <entry> | sha256sum} gives the same).
     * {@code ActivationGateEngineSmoke} row E2b recomputes it through {@link EngineProbe} on the install.
     */
    public static final String LAUNCHER_CHECK_CLASS_SHA256 =
            "f6ef064a76d1b08fddcbb095caab14a2b71997e646e8c258e4a983e961fa546e";
    /** The game's launcher-settings class holding the activation check. */
    public static final String LAUNCHER_CHECK_CLASS = "com.fs.starfarer.launcher.ooOO";
    /** The same class as a class-path resource (hashed without initializing the class). */
    public static final String LAUNCHER_CHECK_RESOURCE = "com/fs/starfarer/launcher/ooOO.class";
    /** Static singleton accessor, returning the launcher-settings class. */
    public static final String SINGLETON_ACCESSOR_NAME = (char) 0xD3 + "00000";
    /** Instance activation check, descriptor {@code ()Z}. */
    public static final String ACTIVATION_CHECK_NAME = (char) 0xD6 + "00000";
    /** The game's version holder (a non-renamed class) and its compiled version constant. */
    public static final String VERSION_CLASS = "com.fs.starfarer.Version";
    public static final String VERSION_FIELD = "versionOnly";
    /** The prefs node the engine's check reads; on Windows {@code HKCU\Software\JavaSoft\Prefs\com\fs\starfarer}. */
    public static final String KEY_NODE_PATH = "/com/fs/starfarer";
    /** The JDK's own platform prefs stores (Windows registry, Linux/BSD file store, macOS plist). */
    public static final Set<String> PLATFORM_PREFS_ROOT_CLASSES = Set.of(
            "java.util.prefs.WindowsPreferences",
            "java.util.prefs.FileSystemPreferences",
            "java.util.prefs.MacOSXPreferences");
    /** ERROR name of step (1): the prefs root is not a platform store. */
    public static final String PREFS_STORE_REPLACED = "PrefsStoreReplaced";

    /** DRIFT detail when the version or the check's class file cannot be read. */
    static final String UNREADABLE = "unreadable";
    private static final int MAX_CLASS_RESOURCE_BYTES = 1 << 20;
    private static final int MAX_NAME_CHARS = 80;

    private static final String TEXT_NO_KEY = "This server needs an activated copy of Starsector on this"
            + " computer, under this user account. The game's own check found no accepted game key."
            + " Start the game from its launcher once, enter your key, then start the server again.";

    private static final EngineProbe ENGINE_PROBE = new EngineProbe(ActivationGate.class.getClassLoader());
    private static final Memo JVM_MEMO = new Memo();

    private ActivationGate() {
    }

    /** The gate's verdicts; only {@link #VALID} lets a serve start. */
    public enum Outcome { VALID, ABSENT, NOT_ACCEPTED, DRIFT, ERROR }

    /** One verdict + its host-facing text ({@link #OK_LINE} for VALID, else the refusal text). */
    public record Decision(Outcome outcome, String text) {
        public Decision {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * The five reads the gate makes, in {@link #decide}'s order. Public so the contract rows in
     * {@code cc.starsector.server.tests} can build fakes (a different package: package-private would
     * not reach it); the real one is {@link EngineProbe}. A drift-class failure is any
     * {@link ReflectiveOperationException} other than {@link InvocationTargetException}.
     */
    public interface ActivationProbe {
        /** {@code Preferences.userRoot().getClass().getName()}. */
        String prefsRootClassName() throws Throwable;

        /** The game's compiled version string, or null. */
        String gameVersion() throws Throwable;

        /** Lowercase-hex SHA-256 of the check's class file, read WITHOUT initializing the class. */
        String launcherCheckClassSha256() throws Throwable;

        /** Whether {@link #KEY_NODE_PATH} exists in the prefs store (a pure read). */
        boolean keyNodePresent() throws Throwable;

        /** The engine's own activation check (singleton accessor, then the check — nothing else). */
        boolean engineSaysActivated() throws Throwable;
    }

    /** The refusal a non-VALID decision raises; message = the refusal text; no stack trace. */
    public static final class ActivationRefusedException extends RuntimeException {
        private final transient Decision decision;

        ActivationRefusedException(Decision decision) {
            super(decision.text());
            this.decision = decision;
        }

        public Decision decision() {
            return decision;
        }

        /** No stack trace: the refusal is a statement to the host, never a crash dump. */
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /**
     * Gate this JVM: decide once over the real {@link EngineProbe} (memoized in memory), print
     * {@link #OK_LINE} on the first VALID decision, otherwise throw.
     *
     * @throws ActivationRefusedException on every non-VALID decision (each call, from the memo)
     */
    public static void enforce() {
        JVM_MEMO.enforce(ENGINE_PROBE, System.out);
    }

    /** The ONE stderr line a refusing serve main prints before {@code System.exit(EXIT_REFUSED)}. */
    public static String refusalLine(ActivationRefusedException refused) {
        return "ActivationRefusedException: " + refused.getMessage();
    }

    /**
     * The pure verdict over a probe, in the class-doc order. Never throws: any {@link Throwable}
     * from any probe step becomes DRIFT (a drift-class reflective failure) or ERROR (anything else,
     * named by SimpleName only).
     */
    public static Decision decide(ActivationProbe probe) {
        try {
            String rootClass = probe.prefsRootClassName();
            if (rootClass == null || !PLATFORM_PREFS_ROOT_CLASSES.contains(rootClass)) {
                return error(PREFS_STORE_REPLACED);
            }
            String version = probe.gameVersion();
            if (!SUPPORTED_GAME_VERSION.equals(version)) {
                return drift(boundedVersion(version));
            }
            if (!LAUNCHER_CHECK_CLASS_SHA256.equals(probe.launcherCheckClassSha256())) {
                return drift(SUPPORTED_GAME_VERSION + ", a different build");
            }
            if (!probe.keyNodePresent()) {
                return new Decision(Outcome.ABSENT, TEXT_NO_KEY);
            }
            return probe.engineSaysActivated()
                    ? new Decision(Outcome.VALID, OK_LINE)
                    : new Decision(Outcome.NOT_ACCEPTED, TEXT_NO_KEY);
        } catch (InvocationTargetException engineThrew) {
            Throwable cause = engineThrew.getCause();
            return error(boundedName(cause == null ? engineThrew : cause));
        } catch (ReflectiveOperationException missing) {
            return drift(UNREADABLE);
        } catch (Throwable failed) {
            return error(boundedName(failed));
        }
    }

    private static Decision drift(String found) {
        // The last sentence: the game's Linux download carries a DIFFERENT engine build of the
        // same version (the pinned class is absent from its engine jar), so a Linux host lands here with the right version.
        // The pointer names the package README.md's "Linux (x86-64)" section.
        return new Decision(Outcome.DRIFT, "This server build supports Starsector " + SUPPORTED_GAME_VERSION
                + " and cannot check the installed game (" + found + ")."
                + " Use a server build that matches your game version."
                + " On a Linux host, game.root must hold the WINDOWS game files: the Linux download is a different"
                + " build (README.md, Linux).");
    }

    private static Decision error(String name) {
        return new Decision(Outcome.ERROR, "The game's own key check could not run (" + name + ")."
                + " The server will not start.");
    }

    /** A version string from the game jar rides the refusal only as a short plain token. */
    private static String boundedVersion(String version) {
        return version != null && version.matches("[0-9A-Za-z._-]{1,32}") ? version : UNREADABLE;
    }

    /** SimpleName only (an anonymous class falls back to its binary name), length-capped. */
    private static String boundedName(Throwable t) {
        String name = t.getClass().getSimpleName();
        if (name.isEmpty()) {
            name = t.getClass().getName();
        }
        return name.length() > MAX_NAME_CHARS ? name.substring(0, MAX_NAME_CHARS) : name;
    }

    /**
     * A first-decision memo: the probe is consulted by the FIRST call only; every later call
     * answers from memory (VALID returns, a refusal re-throws). The JVM's own instance is private
     * to this class; a test builds its own instance, which can never feed the JVM's.
     */
    public static final class Memo {
        private volatile Decision first;

        /**
         * @param okSink where the first VALID decision prints {@link #OK_LINE} (stdout on the serve path)
         * @return the memoized VALID decision
         * @throws ActivationRefusedException when the memoized decision is not VALID
         */
        public Decision enforce(ActivationProbe probe, PrintStream okSink) {
            Decision decision = first;
            if (decision == null) {
                synchronized (this) {
                    decision = first;
                    if (decision == null) {
                        decision = decide(probe);
                        first = decision;
                        if (decision.outcome() == Outcome.VALID) {
                            okSink.println(OK_LINE);
                        }
                    }
                }
            }
            if (decision.outcome() != Outcome.VALID) {
                throw new ActivationRefusedException(decision);
            }
            return decision;
        }
    }

    /** The two resolved engine members (exact name + zero parameters + exact return type + kind). */
    public record CheckMembers(Method singletonAccessor, Method activationCheck) {
    }

    /**
     * Resolve the singleton accessor (public static, returns the class itself) and the activation
     * check (public instance, returns {@code boolean}) on a class that may still be UNINITIALIZED.
     * Exactly one zero-parameter method of each name must exist with that shape.
     *
     * @throws NoSuchMethodException on any other shape (= DRIFT)
     */
    public static CheckMembers resolveCheckMembers(Class<?> launcherCheck) throws NoSuchMethodException {
        return resolveCheckMembers(launcherCheck, SINGLETON_ACCESSOR_NAME, ACTIVATION_CHECK_NAME);
    }

    /**
     * The shape guard over explicit names — the gate passes the pinned pair; the contract rows pass
     * plain-named fixture classes to drive every drift shape. Resolves only; calls nothing.
     */
    public static CheckMembers resolveCheckMembers(Class<?> launcherCheck, String singletonName,
            String checkName) throws NoSuchMethodException {
        Method singleton = null;
        Method check = null;
        int singletonHits = 0;
        int checkHits = 0;
        for (Method m : launcherCheck.getDeclaredMethods()) {
            if (m.getParameterCount() != 0) {
                continue;
            }
            if (singletonName.equals(m.getName())) {
                singletonHits++;
                singleton = m;
            } else if (checkName.equals(m.getName())) {
                checkHits++;
                check = m;
            }
        }
        if (singletonHits != 1 || checkHits != 1
                || !Modifier.isPublic(singleton.getModifiers()) || !Modifier.isStatic(singleton.getModifiers())
                || singleton.getReturnType() != launcherCheck
                || !Modifier.isPublic(check.getModifiers()) || Modifier.isStatic(check.getModifiers())
                || check.getReturnType() != boolean.class) {
            throw new NoSuchMethodException("launcher check members do not match the pinned shape");
        }
        return new CheckMembers(singleton, check);
    }

    /**
     * The real probe over the serve JVM's one app loader (the server compiles against the API jar
     * only; the engine jars are on the serve classpath). Missing pieces surface as
     * {@link ReflectiveOperationException} (= DRIFT in {@link #decide}).
     */
    public static final class EngineProbe implements ActivationProbe {
        private final ClassLoader loader;

        public EngineProbe(ClassLoader loader) {
            this.loader = Objects.requireNonNull(loader, "loader");
        }

        @Override
        public String prefsRootClassName() {
            return Preferences.userRoot().getClass().getName();
        }

        /** Reads the compiled constant (initializes only the light version holder class). */
        @Override
        public String gameVersion() throws ReflectiveOperationException {
            Class<?> version = Class.forName(VERSION_CLASS, false, loader);
            Field field = version.getDeclaredField(VERSION_FIELD);
            int mods = field.getModifiers();
            if (!Modifier.isPublic(mods) || !Modifier.isStatic(mods) || field.getType() != String.class) {
                throw new NoSuchFieldException("version field does not match the pinned shape");
            }
            Object value = field.get(null);
            return value instanceof String s ? s : null;
        }

        /** Hashes the class FILE through the loader (no class init); bounded read. */
        @Override
        public String launcherCheckClassSha256() throws Exception {
            try (InputStream in = loader.getResourceAsStream(LAUNCHER_CHECK_RESOURCE)) {
                if (in == null) {
                    throw new ClassNotFoundException("launcher check class file not on the class path");
                }
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[8192];
                long total = 0;
                for (int n = in.read(buf); n > 0; n = in.read(buf)) {
                    total += n;
                    if (total > MAX_CLASS_RESOURCE_BYTES) {
                        throw new ClassNotFoundException("launcher check class file over the size bound");
                    }
                    sha.update(buf, 0, n);
                }
                return HexFormat.of().formatHex(sha.digest());
            }
        }

        /** A pure read: lists existing children only, creates no node. */
        @Override
        public boolean keyNodePresent() throws Exception {
            return Preferences.userRoot().nodeExists(KEY_NODE_PATH);
        }

        /**
         * Resolves both members on the not-yet-initialized class, then calls the accessor (which
         * initializes the class and builds the engine's own singleton) and the check. VALID only
         * on exactly {@code Boolean.TRUE}.
         */
        @Override
        public boolean engineSaysActivated() throws ReflectiveOperationException {
            Class<?> launcherCheck = Class.forName(LAUNCHER_CHECK_CLASS, false, loader);
            CheckMembers members = resolveCheckMembers(launcherCheck);
            Object launcher = members.singletonAccessor().invoke(null);
            if (!launcherCheck.isInstance(launcher)) {
                throw new NoSuchMethodException("launcher singleton accessor returned no instance");
            }
            return Boolean.TRUE.equals(members.activationCheck().invoke(launcher));
        }
    }
}
