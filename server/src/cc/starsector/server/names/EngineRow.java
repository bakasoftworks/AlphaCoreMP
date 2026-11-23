package cc.starsector.server.names;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ONE engine name the server binds by reflection: a class, a package, or a member (owner + name + JVM descriptor +
 * static bit), keyed by OUR symbolic key.
 *
 * <p>WHY: the server compiles against no engine jar, so every engine name it uses is a string, and a game update
 * renames them all. Holding each name as a row in one table ({@link EngineSym}) means an update is ONE re-key of
 * that table, proven against the new jars by the build's resolve leg, instead of a hunt through every call site. A row
 * is an OBJECT, never a {@code static final String}: javac would inline a String constant into each user's constant
 * pool, where neither the resolve leg nor the literal gate could tell it from a stray literal.
 *
 * <p>INVARIANTS: immutable; no I/O, no logging, no cache, never {@code setAccessible}. A site opens and caches the
 * {@link Method} / {@link Field} where it does today (once, at bind), so nothing new rides a sim tick. A member lookup
 * matches on name + full descriptor + static bit: a name alone is ambiguous across overloads, and engine-internal names
 * are reused by unrelated members of one class. Owners are JVM internal names ({@code /} between packages, {@code $}
 * before a nested class); descriptors are JVM descriptors.
 *
 *
 */
public final class EngineRow {

    /** What a row names. */
    public enum Kind {
        CLASS(false, false, false),
        FIELD(true, false, false),
        METHOD(true, true, false),
        STATIC_FIELD(true, false, true),
        STATIC_METHOD(true, true, true),
        PACKAGE(false, false, false);

        private final boolean member;
        private final boolean method;
        private final boolean isStatic;

        Kind(boolean member, boolean method, boolean isStatic) {
            this.member = member;
            this.method = method;
            this.isStatic = isStatic;
        }

        /** True for the four member kinds (a name + descriptor on an owner class). */
        public boolean isMember() {
            return member;
        }

        /** True for METHOD and STATIC_METHOD. */
        public boolean isMethod() {
            return method;
        }

        /** True for STATIC_FIELD and STATIC_METHOD. */
        public boolean isStatic() {
            return isStatic;
        }
    }

    private static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9_]*");

    private final String key;
    private final Kind kind;
    /** The owner's internal name; for a PACKAGE row the internal package name (no trailing separator). */
    private final String owner;
    /** Member name; null unless {@link Kind#isMember()}. */
    private final String name;
    /** JVM descriptor; null unless {@link Kind#isMember()}. */
    private final String descriptor;

    private EngineRow(String key, Kind kind, String owner, String name, String descriptor) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("engine row key must match [A-Z][A-Z0-9_]*: " + key);
        }
        if (!validInternalName(owner)) {
            throw new IllegalArgumentException("engine row " + key + ": owner is not a JVM internal name: " + owner);
        }
        if (kind.isMember()) {
            if (!validMemberName(name)) {
                throw new IllegalArgumentException("engine row " + key + ": member name is not a JVM name: " + name);
            }
            boolean shapeOk = kind.isMethod() ? isMethodDescriptor(descriptor) : isFieldDescriptor(descriptor);
            if (!shapeOk) {
                throw new IllegalArgumentException("engine row " + key + ": not a " + (kind.isMethod() ? "method" : "field")
                        + " descriptor: " + descriptor);
            }
        }
        this.key = key;
        this.kind = kind;
        this.owner = owner;
        this.name = name;
        this.descriptor = descriptor;
    }

    /** A class row; {@code owner} = the JVM internal name ({@code com/fs/x/A$B}). */
    public static EngineRow cls(String key, String owner) {
        return new EngineRow(key, Kind.CLASS, owner, null, null);
    }

    /** An instance-field row: owner internal name, field name, field descriptor ({@code F}, {@code Lcom/fs/x/A;}). */
    public static EngineRow field(String key, String owner, String name, String descriptor) {
        return new EngineRow(key, Kind.FIELD, owner, name, descriptor);
    }

    /** An instance-method row: owner internal name, method name, method descriptor ({@code (F)Z}). */
    public static EngineRow method(String key, String owner, String name, String descriptor) {
        return new EngineRow(key, Kind.METHOD, owner, name, descriptor);
    }

    /** A static-field row (see {@link #field}). */
    public static EngineRow staticField(String key, String owner, String name, String descriptor) {
        return new EngineRow(key, Kind.STATIC_FIELD, owner, name, descriptor);
    }

    /** A static-method row (see {@link #method}). */
    public static EngineRow staticMethod(String key, String owner, String name, String descriptor) {
        return new EngineRow(key, Kind.STATIC_METHOD, owner, name, descriptor);
    }

    /** A package row: {@code prefix} = the internal package name without a trailing separator ({@code com/fs/x}). */
    public static EngineRow pkg(String key, String prefix) {
        return new EngineRow(key, Kind.PACKAGE, prefix, null, null);
    }

    public String key() {
        return key;
    }

    public Kind kind() {
        return kind;
    }

    /** The class's (a member row: the owner's) JVM internal name, {@code com/fs/x/A$B}. */
    public String internalName() {
        requireNotPackage("internalName");
        return owner;
    }

    /** The dotted binary name {@link Class#forName} takes and {@link Class#getName} returns, {@code com.fs.x.A$B}. */
    public String className() {
        requireNotPackage("className");
        return owner.replace('/', '.');
    }

    /** The class's entry name in a jar or class directory, {@code com/fs/x/A$B.class}. */
    public String classEntry() {
        requireNotPackage("classEntry");
        return owner + ".class";
    }

    /** The class as a JVM type descriptor, {@code Lcom/fs/x/A$B;}. */
    public String typeDescriptor() {
        requireNotPackage("typeDescriptor");
        return "L" + owner + ";";
    }

    /** The member name (a member row only). */
    public String name() {
        requireMember("name");
        return name;
    }

    /** The member's JVM descriptor (a member row only). */
    public String descriptor() {
        requireMember("descriptor");
        return descriptor;
    }

    /** A package row's dotted prefix, {@code com.fs.x.} (trailing dot: a class name starts with it). */
    public String dottedPrefix() {
        requirePackage("dottedPrefix");
        return owner.replace('/', '.') + ".";
    }

    /** A package row's internal prefix, {@code com/fs/x/} (trailing separator: an internal name starts with it). */
    public String internalPrefix() {
        requirePackage("internalPrefix");
        return owner + "/";
    }

    /**
     * Loads (and initializes, as {@code Class.forName(name, true, loader)} does at the sites this replaces) the row's
     * class; a member row loads its owner.
     */
    public Class<?> load(ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(className(), true, loader);
    }

    /** True iff {@code c}'s binary name is exactly this row's class (a member row: its owner). */
    public boolean names(Class<?> c) {
        return c != null && c.getName().equals(className());
    }

    /**
     * The method this row names, as declared in {@code lookupOwner} or up its hierarchy (superclasses first, then
     * interfaces). The row's owner must BE {@code lookupOwner} or one of its supertypes, compared by name: a site that
     * hands the wrong class fails loudly instead of matching a same-named member elsewhere. Never sets accessibility.
     *
     * @throws IllegalStateException not a method row, or the owner is not {@code lookupOwner} / a supertype of it
     * @throws NoSuchMethodException no declared method with this name + descriptor + static bit on the walk
     */
    public Method method(Class<?> lookupOwner) throws NoSuchMethodException {
        if (!kind.isMethod()) {
            throw new IllegalStateException("engine row " + key + " is a " + kind + " row, not a method row");
        }
        for (Class<?> c : walk(lookupOwner)) {
            for (Method m : c.getDeclaredMethods()) {
                if (matches(m)) {
                    return m;
                }
            }
        }
        throw new NoSuchMethodException("engine row " + key + ": no " + (kind.isStatic() ? "static " : "") + name
                + descriptor + " from " + lookupOwner.getName());
    }

    /**
     * The field this row names, found as {@link #method} finds a method (same owner assertion, same walk).
     *
     * @throws IllegalStateException not a field row, or the owner is not {@code lookupOwner} / a supertype of it
     * @throws NoSuchFieldException no declared field with this name + descriptor + static bit on the walk
     */
    public Field field(Class<?> lookupOwner) throws NoSuchFieldException {
        if (!kind.isMember() || kind.isMethod()) {
            throw new IllegalStateException("engine row " + key + " is a " + kind + " row, not a field row");
        }
        for (Class<?> c : walk(lookupOwner)) {
            for (Field f : c.getDeclaredFields()) {
                if (matches(f)) {
                    return f;
                }
            }
        }
        throw new NoSuchFieldException("engine row " + key + ": no " + (kind.isStatic() ? "static " : "") + name
                + " " + descriptor + " from " + lookupOwner.getName());
    }

    /** True iff {@code m} has this method row's name, full descriptor and static bit (for a site that enumerates). */
    public boolean matches(Method m) {
        if (!kind.isMethod()) {
            throw new IllegalStateException("engine row " + key + " is a " + kind + " row, not a method row");
        }
        return m.getName().equals(name) && staticBitMatches(m) && descriptorOf(m).equals(descriptor);
    }

    /** True iff {@code f} has this field row's name, descriptor and static bit (for a site that enumerates). */
    public boolean matches(Field f) {
        if (!kind.isMember() || kind.isMethod()) {
            throw new IllegalStateException("engine row " + key + " is a " + kind + " row, not a field row");
        }
        return f.getName().equals(name) && staticBitMatches(f) && typeDescriptorOf(f.getType()).equals(descriptor);
    }

    @Override
    public String toString() {
        return kind.isMember() ? key + " " + kind + " " + owner + "." + name + descriptor : key + " " + kind + " " + owner;
    }

    private boolean staticBitMatches(Member m) {
        return Modifier.isStatic(m.getModifiers()) == kind.isStatic();
    }

    /**
     * {@code lookupOwner}, its superclasses, then every interface they implement (breadth-first, each once), after
     * asserting the row's owner is among them by name.
     */
    private List<Class<?>> walk(Class<?> lookupOwner) {
        if (lookupOwner == null) {
            throw new IllegalStateException("engine row " + key + ": null lookup owner");
        }
        List<Class<?>> order = new ArrayList<>();
        Deque<Class<?>> interfaces = new ArrayDeque<>();
        for (Class<?> c = lookupOwner; c != null; c = c.getSuperclass()) {
            order.add(c);
            for (Class<?> i : c.getInterfaces()) {
                interfaces.addLast(i);
            }
        }
        Set<Class<?>> seen = new HashSet<>(order);
        while (!interfaces.isEmpty()) {
            Class<?> i = interfaces.removeFirst();
            if (seen.add(i)) {
                order.add(i);
                for (Class<?> up : i.getInterfaces()) {
                    interfaces.addLast(up);
                }
            }
        }
        String ownerName = className();
        for (Class<?> c : order) {
            if (c.getName().equals(ownerName)) {
                return order;
            }
        }
        throw new IllegalStateException("engine row " + key + ": owner " + ownerName + " is not "
                + lookupOwner.getName() + " or a supertype of it");
    }

    private static String descriptorOf(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(typeDescriptorOf(p));
        }
        return sb.append(')').append(typeDescriptorOf(m.getReturnType())).toString();
    }

    private static String typeDescriptorOf(Class<?> c) {
        if (c.isArray()) {
            return c.getName().replace('.', '/'); // an array's binary name is already its descriptor, dotted
        }
        if (!c.isPrimitive()) {
            return "L" + c.getName().replace('.', '/') + ";";
        }
        if (c == int.class) {
            return "I";
        } else if (c == boolean.class) {
            return "Z";
        } else if (c == float.class) {
            return "F";
        } else if (c == void.class) {
            return "V";
        } else if (c == long.class) {
            return "J";
        } else if (c == double.class) {
            return "D";
        } else if (c == byte.class) {
            return "B";
        } else if (c == char.class) {
            return "C";
        }
        return "S";
    }

    private void requireNotPackage(String accessor) {
        if (kind == Kind.PACKAGE) {
            throw new IllegalStateException("engine row " + key + " is a PACKAGE row: " + accessor
                    + "() names a class (use dottedPrefix / internalPrefix)");
        }
    }

    private void requirePackage(String accessor) {
        if (kind != Kind.PACKAGE) {
            throw new IllegalStateException("engine row " + key + " is a " + kind + " row: " + accessor
                    + "() names a package");
        }
    }

    private void requireMember(String accessor) {
        if (!kind.isMember()) {
            throw new IllegalStateException("engine row " + key + " is a " + kind + " row: " + accessor
                    + "() names a member");
        }
    }

    private static boolean validInternalName(String s) {
        if (s == null || s.isEmpty() || s.startsWith("/") || s.endsWith("/") || s.contains("//")) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.' || c == ';' || c == '[' || c == '<' || c == '>') {
                return false;
            }
        }
        return true;
    }

    /**
     * A member name the engine can carry. A DOT IS ACCEPTED, against the letter of JVMS 4.2.2: the engine's own
     * class files carry dotted member names ({@code super.super}, {@code Object.new}, {@code String.Object$class}; 85
     * member rows of the 0.98.5a-RC4 to RC7 rename map hold one, on both sides), the game's JVM loads them, and
     * reflection hands the name back verbatim. Refusing the dot made the re-keyed table unloadable at RC7 (there
     * {@code AF_TARGET_FIELD} is {@code super.super}). The other descriptor separators stay refused:
     * no engine member carries one, and each would be ambiguous in a row's printed {@code owner.name+descriptor}.
     */
    private static boolean validMemberName(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ';' || c == '[' || c == '/' || c == '<' || c == '>') {
                return false;
            }
        }
        return true;
    }

    /** Index just past one field type starting at {@code i}, or -1 when none starts there. */
    private static int typeEnd(String d, int i) {
        while (i < d.length() && d.charAt(i) == '[') {
            i++;
        }
        if (i >= d.length()) {
            return -1;
        }
        char c = d.charAt(i);
        if ("ZBCSIJFD".indexOf(c) >= 0) {
            return i + 1;
        }
        if (c == 'L') {
            int semi = d.indexOf(';', i);
            return semi > i + 1 && validInternalName(d.substring(i + 1, semi)) ? semi + 1 : -1;
        }
        return -1;
    }

    private static boolean isFieldDescriptor(String d) {
        return d != null && typeEnd(d, 0) == d.length();
    }

    private static boolean isMethodDescriptor(String d) {
        if (d == null || !d.startsWith("(")) {
            return false;
        }
        int i = 1;
        while (i < d.length() && d.charAt(i) != ')') {
            i = typeEnd(d, i);
            if (i < 0) {
                return false;
            }
        }
        if (i >= d.length()) {
            return false;
        }
        i++;
        if (i < d.length() && d.charAt(i) == 'V') {
            return i + 1 == d.length();
        }
        return typeEnd(d, i) == d.length();
    }
}
