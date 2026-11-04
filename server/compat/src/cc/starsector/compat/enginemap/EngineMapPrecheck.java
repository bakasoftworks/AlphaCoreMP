package cc.starsector.compat.enginemap;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

import org.objectweb.asm.ClassReader;

/**
 * THE one predicate that decides whether a class file needs the engine-name remap at all: two passes over its
 * constant pool (which entries point at each Utf8, then the tests), true iff
 * <ul>
 * <li>a CONSTANT_Utf8 that is not a string literal alone (below) EQUALS a class key, CONTAINS {@code L<key>;} (a
 * descriptor or signature) or STARTS WITH {@code <key>$} (an inner class of a mapped class; each inner class still
 * keeps its own exact key), or</li>
 * <li>a Fieldref / Methodref / InterfaceMethodref's (class, name, descriptor) EQUALS a member row's Windows key (a
 * Fieldref against the field rows, a method ref against the method rows: the same exact lookup the remapper
 * makes).</li>
 * </ul>
 * WHY the member clause: a class may name no mapped class at all yet hold field refs into an UNmapped engine owner
 * whose member names differ in the other build; a class-key test alone would skip it and the field access would fail
 * at run time. WHY never the owner alone: every class calling an unchanged method of a shared engine class would then
 * be rewritten for nothing.
 *
 * <p>A CONSTANT_Utf8 that only CONSTANT_String entries point at (an LDC string literal) never counts for the three
 * class-key tests. WHY: the remapper never rewrites a string literal, so a class whose only engine naming is a literal
 * would be "touched" for a no-op rewrite. The case that forced it: the generated client class {@code EngineNameTable}
 * holds Windows member descriptors as string data, some containing {@code L<key>;}, and was selected while the map's
 * touched rows (and the offline check that counts them) say it is not. A Utf8 that any other pool entry points at (a
 * Class, NameAndType, MethodType, Module or Package entry) stays in, and so does one that no pool entry points at (a
 * field or method descriptor, a Signature attribute: attribute bodies index Utf8 directly). Limit: a literal whose
 * text is ALSO the exact descriptor or signature of one of the class's own members shares that Utf8 and is skipped
 * when no pool entry also points at it; the offline link check (every engine reference the code reaches must resolve
 * to its counterpart on the other build) catches such a class.
 *
 * <p>Shared verbatim by the agent's transformer and the offline build-time check, so the set of classes the agent
 * touches at load and the set the check proves are one set. Reads only; a malformed class file throws (the caller's
 * fail-open path).
 *
 *
 */
public final class EngineMapPrecheck {

    private static final int UTF8 = 1;
    private static final int CLASS = 7;
    private static final int STRING = 8;
    private static final int FIELDREF = 9;
    private static final int METHODREF = 10;
    private static final int INTERFACE_METHODREF = 11;
    private static final int NAME_AND_TYPE = 12;
    private static final int METHOD_TYPE = 16;
    private static final int MODULE = 19;
    private static final int PACKAGE = 20;

    /** Referrer bits per pool index: a CONSTANT_String points at it / any other pool entry points at it. */
    private static final byte BY_STRING = 1;
    private static final byte BY_OTHER = 2;

    private final EngineMap map;
    private final String[] keys;
    private final String[] descNeedles;
    private final String[] innerPrefixes;

    public EngineMapPrecheck(EngineMap map) {
        this.map = map;
        this.keys = map.classes().keySet().toArray(new String[0]);
        this.descNeedles = new String[keys.length];
        this.innerPrefixes = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            descNeedles[i] = "L" + keys[i] + ";";
            innerPrefixes[i] = keys[i] + "$";
        }
    }

    /** True when {@code classfile} references a mapped class or a mapped member (see the class doc). */
    public boolean touches(byte[] classfile) {
        ClassReader cr = new ClassReader(classfile);
        char[] buf = new char[cr.getMaxStringLength()];
        int count = cr.getItemCount();
        byte[] referrers = utf8Referrers(cr, count);
        for (int i = 1; i < count; i++) {
            int off = cr.getItem(i);
            if (off == 0) {
                continue; // the unusable second slot of a long / double
            }
            int tag = cr.readByte(off - 1);
            if (tag == UTF8) {
                if (referrers[i] != BY_STRING && namesClassKey(utf8At(classfile, off))) {
                    return true;
                }
            } else if (tag == FIELDREF || tag == METHODREF || tag == INTERFACE_METHODREF) {
                String owner = cr.readClass(off, buf);
                int nameAndType = cr.getItem(cr.readUnsignedShort(off + 2));
                String name = cr.readUTF8(nameAndType, buf);
                String desc = cr.readUTF8(nameAndType + 2, buf);
                if ((tag == FIELDREF ? map.field(owner, name, desc) : map.method(owner, name, desc)) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Which pool entries point at each Utf8 index: {@link #BY_STRING}, {@link #BY_OTHER}, both, or 0 (none: the Utf8
     * is reached only from a member or attribute body). Exactly {@link #BY_STRING} = a string literal and nothing
     * else, the one case {@link #touches} skips for its class-key tests. An index past the pool throws (the caller's
     * fail-open path).
     *
     *
     */
    private static byte[] utf8Referrers(ClassReader cr, int count) {
        byte[] by = new byte[count];
        for (int i = 1; i < count; i++) {
            int off = cr.getItem(i);
            if (off == 0) {
                continue;
            }
            switch (cr.readByte(off - 1)) {
                case STRING -> by[cr.readUnsignedShort(off)] |= BY_STRING;
                case CLASS, METHOD_TYPE, MODULE, PACKAGE -> by[cr.readUnsignedShort(off)] |= BY_OTHER;
                case NAME_AND_TYPE -> {
                    by[cr.readUnsignedShort(off)] |= BY_OTHER;
                    by[cr.readUnsignedShort(off + 2)] |= BY_OTHER;
                }
                default -> {
                    // a number, a member ref, a handle or a dynamic entry: none points at a Utf8 directly
                }
            }
        }
        return by;
    }

    private boolean namesClassKey(String s) {
        for (int k = 0; k < keys.length; k++) {
            if (s.equals(keys[k]) || s.startsWith(innerPrefixes[k]) || s.contains(descNeedles[k])) {
                return true;
            }
        }
        return false;
    }

    /** The CONSTANT_Utf8 whose u2 length sits at {@code off} (the class file's modified UTF-8). */
    private static String utf8At(byte[] classfile, int off) {
        int len = ((classfile[off] & 0xFF) << 8) | (classfile[off + 1] & 0xFF);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classfile, off, len + 2))) {
            return in.readUTF();
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed CONSTANT_Utf8 at " + off, e);
        }
    }
}
