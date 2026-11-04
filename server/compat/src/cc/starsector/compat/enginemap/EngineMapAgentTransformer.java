package cc.starsector.compat.enginemap;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The kit agent's engine-name remap at class load: when the game runs on a separate build of the engine whose
 * internal classes carry other names (the Linux download of the same version), every client-mod class that references
 * one of them is renamed through the matching {@link EngineMap} as it is defined, so ONE mod jar (one md5, one
 * fingerprint) links on both builds.
 *
 * <p>ARMING = the engine's own identity, never {@code os.name} (a Linux box running the Windows jars IS the Windows
 * build), decided once at the game's start by {@link #decide}: (i) the Windows engine's marker class is on the system
 * class path ⇒ {@value #RECEIPT_IDENTITY}, no transform; (ii) else the sha-256 of the jar holding the game's launcher
 * class equals a map's {@code target} ⇒ armed, the receipt is that map's id; (iii) no map matches ⇒
 * {@code unknown:<16 hex>}, not armed; the launcher class outside any jar ⇒ {@code disarmed:no_engine_jar}. The agent's
 * premain publishes the receipt as {@value #RECEIPT_SYSPROP}; this class only answers it ({@link #get}) and whether to
 * register ({@link #getAsBoolean}) - both through JDK interfaces, because the premain links no class of this package.
 *
 * <p>THE TRANSFORM: only {@value EngineMap#SCOPE} classes, never a redefine, never the bootstrap loader; the shared
 * {@link EngineMapPrecheck} first (false ⇒ {@code null}, the original loads), then {@link EngineMapRemapper#remap}.
 * One stdout row per rewritten class {@code [cc-compat-agent] enginemap applied class=<name> refs=<n>}. FAIL-OPEN per
 * class: any Throwable ⇒ {@code null} + ONE stdout row + {@code <class>:<Kind>} appended to {@value #ERRORS_SYSPROP}
 * (bounded); the receipt {@value #RECEIPT_SYSPROP} is NEVER overwritten (the client mod's link probe reads the errors
 * row and refuses a half-linked start loudly).
 *
 * <p>Loaded ONLY through the agent's private child-first loader, so the ASM it links is the agent jar's own copy.
 * Reads no file but the engine jar's bytes (to hash them) and this agent jar's own map entries; no socket, env or
 * player text; every row is a bounded token.
 *
 *
 */
public final class EngineMapAgentTransformer implements ClassFileTransformer, Supplier<String>, BooleanSupplier {

    /** The receipt sysprop the premain sets from {@link #get}; the client mod's probe latches it once. */
    public static final String RECEIPT_SYSPROP = "cc.engine.map";
    /** The per-class failure row ({@code <class>:<Kind>,...}); never written by the premain. */
    public static final String ERRORS_SYSPROP = "cc.engine.map.errors";
    /** The receipt on the Windows build: no transformer is ever registered. */
    public static final String RECEIPT_IDENTITY = "identity";
    /** The receipt prefix when no map knows the engine jar: {@code unknown:<first 16 hex of its sha-256>}. */
    public static final String RECEIPT_UNKNOWN_PREFIX = "unknown:";
    /** The receipt when the launcher class sits outside any jar (never a player's start; a test or dev shape). */
    public static final String RECEIPT_NO_ENGINE_JAR = "disarmed:no_engine_jar";
    /** The agent jar folder the map files are embedded under, one {@code <id>.tsv} each. */
    public static final String MAP_DIR = "META-INF/cc-compat/engine-map/";
    /** Present on the Windows build only (its name differs in the other build): the identity test. */
    public static final String WINDOWS_MARKER = "com/fs/starfarer/loading/B.class";
    /** The game's launcher class: the jar holding it is the engine jar whose sha-256 picks the map. */
    public static final String LAUNCHER_RESOURCE = "com/fs/starfarer/StarfarerLauncher.class";
    /** The row prefix (the agent's own tag). */
    public static final String ROW_PREFIX = "[cc-compat-agent] enginemap ";
    /** The errors row's entry bound: past it one {@code +more} marks the rest. */
    static final int MAX_ERRORS = 32;

    /** The arming decision: the receipt and the map to apply ({@code null} = do not register). */
    public record Arming(String receipt, EngineMap map) {
    }

    private final String receipt;
    private final EngineMap map;
    private final EngineMapPrecheck precheck;

    /**
     * The premain's path: the decision from the real system class path and the maps embedded in the jar that holds
     * {@link #MAP_DIR} (this agent's own). Throws when an embedded map is unreadable or bad (the premain disarms with
     * the throw's name); the Windows build never reads the maps.
     */
    public EngineMapAgentTransformer() {
        this(decide(ClassLoader::getSystemResource, () -> {
            try {
                return loadMaps(EngineMapAgentTransformer.class.getClassLoader().getResource(MAP_DIR));
            } catch (IOException e) {
                throw new IllegalStateException("engine_map_unreadable", e);
            }
        }));
    }

    /** A transformer armed with {@code map} (the offline check and the contract rows). */
    public EngineMapAgentTransformer(EngineMap map) {
        this(new Arming(map.id(), map));
    }

    private EngineMapAgentTransformer(Arming arming) {
        this.receipt = arming.receipt();
        this.map = arming.map();
        this.precheck = map == null ? null : new EngineMapPrecheck(map);
    }

    /** The receipt this transformer's arming decided ({@code identity | <map id> | unknown:<16 hex> | disarmed:...}). */
    @Override
    public String get() {
        return receipt;
    }

    /** True when the premain should register this transformer (a map matched the engine jar). */
    @Override
    public boolean getAsBoolean() {
        return map != null;
    }

    /**
     * The arming decision of the class doc, over {@code lookup} (a resource name to its URL on the game's system class
     * path, {@code null} when absent) and the candidate maps (read only on branch (ii)). Throws when the engine jar
     * cannot be read or hashed.
     */
    public static Arming decide(Function<String, URL> lookup, Supplier<List<EngineMap>> maps) {
        if (lookup.apply(WINDOWS_MARKER) != null) {
            return new Arming(RECEIPT_IDENTITY, null);
        }
        Path jar = jarOf(lookup.apply(LAUNCHER_RESOURCE));
        if (jar == null) {
            return new Arming(RECEIPT_NO_ENGINE_JAR, null);
        }
        String sha = sha256(jar);
        for (EngineMap m : maps.get()) {
            if (m.targetSha().equals(sha)) {
                return new Arming(m.id(), m);
            }
        }
        return new Arming(RECEIPT_UNKNOWN_PREFIX + sha.substring(0, 16), null);
    }

    /**
     * Every {@code <id>.tsv} directly under {@code dir} (a {@code jar:} URL of {@link #MAP_DIR}), parsed; each map's
     * id must equal its file name and no two maps may share an id or a target. {@code null} ⇒ no maps.
     */
    public static List<EngineMap> loadMaps(URL dir) throws IOException {
        List<EngineMap> out = new ArrayList<>();
        if (dir == null) {
            return out;
        }
        URLConnection c = dir.openConnection();
        if (!(c instanceof JarURLConnection jc)) {
            throw new IOException("engine_map_dir_not_in_a_jar");
        }
        jc.setUseCaches(false);
        Set<String> ids = new HashSet<>();
        Set<String> targets = new HashSet<>();
        try (JarFile jar = jc.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (!name.startsWith(MAP_DIR) || !name.endsWith(".tsv") || name.indexOf('/', MAP_DIR.length()) >= 0) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = jar.getInputStream(e)) {
                    bytes = in.readNBytes(EngineMap.MAX_BYTES + 1);
                }
                EngineMap m = EngineMap.parse(bytes);
                String stem = name.substring(MAP_DIR.length(), name.length() - ".tsv".length());
                if (!m.id().equals(stem) || !ids.add(m.id()) || !targets.add(m.targetSha())) {
                    throw new IOException("engine_map_id_or_target_clash");
                }
                out.add(m);
            }
        }
        return out;
    }

    /** The jar file a {@code jar:} resource URL points into, or {@code null} for an absent or non-jar resource. */
    static Path jarOf(URL resource) {
        if (resource == null || !"jar".equals(resource.getProtocol())) {
            return null;
        }
        try {
            return Path.of(((JarURLConnection) resource.openConnection()).getJarFileURL().toURI());
        } catch (IOException | java.net.URISyntaxException e) {
            throw new IllegalStateException("engine_jar_unresolved", e);
        }
    }

    /** The lowercase hex sha-256 of a file's bytes. */
    static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int r; (r = in.read(buf)) > 0; ) {
                md.update(buf, 0, r);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : md.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("engine_jar_unreadable", e);
        }
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (map == null || className == null || !className.startsWith(EngineMap.SCOPE) || classBeingRedefined != null
                || loader == null) {
            return null;
        }
        try {
            if (!precheck.touches(classfileBuffer)) {
                return null;
            }
            EngineMapRemapper.Remapped r = EngineMapRemapper.remap(classfileBuffer, map);
            if (r == null) {
                return null;
            }
            System.out.println(ROW_PREFIX + "applied class=" + className + " refs=" + r.refs());
            return r.bytes();
        } catch (Throwable t) {
            recordError(className, t);
            return null;
        }
    }

    /**
     * Appends {@code <class>:<Kind>} to {@value #ERRORS_SYSPROP} (at most {@value #MAX_ERRORS} entries, then one
     * {@code +more}) and prints ONE row; never touches {@value #RECEIPT_SYSPROP}; nothing escapes a class define.
     */
    static void recordError(String className, Throwable t) {
        try {
            // the throw's own simple name, never a call into this package: its class may be what failed to link
            String kind = t.getClass().getSimpleName();
            kind = kind.isEmpty() ? "Throwable" : kind;
            synchronized (EngineMapAgentTransformer.class) {
                String prior = System.getProperty(ERRORS_SYSPROP);
                if (prior == null || prior.isEmpty()) {
                    System.setProperty(ERRORS_SYSPROP, className + ":" + kind);
                } else if (prior.split(",", -1).length < MAX_ERRORS) {
                    System.setProperty(ERRORS_SYSPROP, prior + "," + className + ":" + kind);
                } else if (!prior.endsWith(",+more")) {
                    System.setProperty(ERRORS_SYSPROP, prior + ",+more");
                }
            }
            System.out.println(ROW_PREFIX + "error class=" + className + " kind=" + kind);
        } catch (Throwable ignored) {
            // a failure to report must not escape a class define either; the original class loads
        }
    }
}
