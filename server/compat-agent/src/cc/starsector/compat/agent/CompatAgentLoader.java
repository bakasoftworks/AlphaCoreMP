package cc.starsector.compat.agent;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSource;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The agent's private loader: CHILD-FIRST for {@code org.objectweb.asm.} and {@code cc.starsector.compat.}, defining
 * those ONLY from this agent jar's own {@value #ROOT} entries (a name missing there is a
 * {@link ClassNotFoundException}, never a parent lookup); every other name delegates to the parent (the JDK, the
 * game). WHY: the system class path may also carry another ASM (a player's own agent); a parent-first lookup would
 * link that copy under our rewriter.
 *
 * <p>The bytes come from the jar the anchor class was loaded from (its code source), opened once and held for the
 * JVM's life, as the system loader holds the same file; a class entry is bounded at {@value #MAX_CLASS_BYTES} bytes.
 * {@link #close} exists for an in-process test.
 *
 *
 */
final class CompatAgentLoader extends ClassLoader implements Closeable {

    /** The jar folder the private classes sit under. */
    static final String ROOT = "META-INF/cc-compat/classes/";
    /** The child-first prefixes (binary names). */
    private static final String[] PRIVATE = {"org.objectweb.asm.", "cc.starsector.compat."};
    /** A class entry's byte bound (the largest private class is tens of KB). */
    static final int MAX_CLASS_BYTES = 1 << 20;

    static {
        registerAsParallelCapable();
    }

    private final JarFile jar;

    private CompatAgentLoader(JarFile jar, ClassLoader parent) {
        super("cc-compat-agent", parent);
        this.jar = jar;
    }

    /** The loader over {@code anchor}'s own jar, parented to {@code anchor}'s loader. */
    static CompatAgentLoader overOwnJar(Class<?> anchor) throws Exception {
        CodeSource source = anchor.getProtectionDomain().getCodeSource();
        URL location = source == null ? null : source.getLocation();
        if (location == null) {
            throw new IOException("no_code_source");
        }
        File file = new File(location.toURI());
        if (!file.isFile()) {
            throw new IOException("code_source_not_a_jar");
        }
        return new CompatAgentLoader(new JarFile(file, false), anchor.getClassLoader());
    }

    static boolean isPrivate(String name) {
        for (String p : PRIVATE) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!isPrivate(name)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                c = findClass(name);
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (!isPrivate(name)) {
            throw new ClassNotFoundException(name);
        }
        byte[] bytes;
        try {
            bytes = read(ROOT + name.replace('.', '/') + ".class");
        } catch (Throwable t) {
            throw new ClassNotFoundException(name, t);
        }
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }

    private byte[] read(String entryName) throws IOException {
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) {
            return null;
        }
        try (InputStream in = jar.getInputStream(entry)) {
            byte[] b = in.readNBytes(MAX_CLASS_BYTES + 1);
            if (b.length > MAX_CLASS_BYTES) {
                throw new IOException("class_entry_over_bound");
            }
            return b;
        }
    }

    @Override
    public void close() throws IOException {
        jar.close();
    }
}
