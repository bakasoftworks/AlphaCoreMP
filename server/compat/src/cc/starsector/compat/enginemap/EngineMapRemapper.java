package cc.starsector.compat.enginemap;

import java.util.HashSet;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * The engine-name remap as an ASM {@link Remapper}: class rows feed {@link #map}, member rows feed
 * {@link #mapMethodName} / {@link #mapFieldName} keyed EXACTLY by the Windows owner + name + descriptor (the names the
 * mod was compiled against: ASM asks with the ORIGINAL owner and descriptor). Every descriptor, signature, frame type,
 * class literal and method handle follows from the class rows through ASM itself; string constants are never touched
 * (a member-name string like {@code "new"} is unsafe to rewrite blindly; class-name strings are the mod's own lookups).
 *
 * <p>One instance per class file (it counts that file's hits); {@link #remap} is the whole transform: a
 * {@link ClassRemapper} into a {@code ClassWriter(reader, 0)} - no frame recompute (a rename changes no stack shape),
 * and the input constant pool is kept, so the old names stay as dead pool entries nothing resolves.
 *
 *
 */
public final class EngineMapRemapper extends Remapper {

    /** The rewritten class file and the number of distinct map rows its references went through ({@code > 0}). */
    public record Remapped(byte[] bytes, int refs) {
    }

    private final EngineMap map;
    private final Set<String> hits = new HashSet<>();

    public EngineMapRemapper(EngineMap map) {
        super(Opcodes.ASM9);
        this.map = map;
    }

    /**
     * The remapped class file, or {@code null} when no reference changed (an identity map, or a class the pre-check
     * let through that names nothing the map renames). Throws on a malformed class file (the caller fails open).
     */
    public static Remapped remap(byte[] classfile, EngineMap map) {
        ClassReader reader = new ClassReader(classfile);
        ClassWriter writer = new ClassWriter(reader, 0);
        EngineMapRemapper remapper = new EngineMapRemapper(map);
        reader.accept(new ClassRemapper(writer, remapper), 0);
        int refs = remapper.refs();
        return refs == 0 ? null : new Remapped(writer.toByteArray(), refs);
    }

    /** The distinct map rows this instance renamed through so far (a class row and a member row count once each). */
    public int refs() {
        return hits.size();
    }

    @Override
    public String map(String internalName) {
        String target = map.targetClass(internalName);
        if (target == null || target.equals(internalName)) {
            return internalName;
        }
        hits.add("class\t" + internalName);
        return target;
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        return member(map.method(owner, name, descriptor), name);
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        return member(map.field(owner, name, descriptor), name);
    }

    private String member(EngineMap.Member row, String name) {
        if (row == null || row.targetName().equals(name)) {
            return name;
        }
        hits.add(row.kind().token() + "\t" + row.owner() + "\t" + row.name() + "\t" + row.desc());
        return row.targetName();
    }
}
