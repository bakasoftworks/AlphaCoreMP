package cc.starsector.server.platform;

import java.lang.reflect.InvocationTargetException;

/**
 * Defensive deep copy of a registry-resolved
 * {@code HullVariantSpec} for every engine deploy lane that mints a {@code FleetMember}.
 *
 * <p><b>Why:</b> the engine's
 * {@code FleetMember(int, HullVariantSpec, Type)} ctor stores a non-null passed variant BY
 * REFERENCE (it skips its defensive clone) — and the engine's
 * OWN station path always {@code clone()}s first. Engine build
 * normalization ({@code HullVariantSpec.autoGenerateWeaponGroupsV2})
 * can REWRITE the handed spec (rebuild groups, re-derive
 * {@code autofireOnByDefault}), so handing the shared REGISTRY singleton lets per-ship build
 * state write back into the spec every other resolve of that id then reads.
 * {@link #cloneVariant} hands the engine a private deep copy instead —
 * {@code HullVariantSpec.clone()} deep-copies weaponGroups, so the
 * copy shares nothing mutable with the registry.
 *
 * <p><b>Provenance:</b> extracted from
 * {@code VariantAutofireHygiene} when its autofire strip was reaped from the product lanes
 * (strip attribution OVERTURNED; the runtime disarm is the
 * product fly-in bar). This defensive copy is a SEPARATE concern from that overturned
 * attribution and is kept on its own merits: the write-back hazard above is proven from the engine's own code
 * and independent of autofire — the wave lane always cloned WITHOUT stripping for exactly
 * this reason.
 *
 * <p>Fail-closed: any reflective failure throws {@link IllegalStateException} (an engine API
 * drift here must be loud — a silent fallback would re-open the shared-spec corruption).
 */
public final class RegistryVariantClone {

    private RegistryVariantClone() {
    }

    /**
     * Deep-clone a registry {@code HullVariantSpec} for one engine deploy ({@code lane} tags
     * the failure message). Never returns the passed object.
     */
    public static Object cloneVariant(Object variant, String lane) {
        if (variant == null) {
            throw new IllegalStateException("cloneVariant(" + lane + "): null variant");
        }
        Object clone;
        try {
            clone = variant.getClass().getMethod("clone").invoke(variant);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cloneVariant(" + lane + "): HullVariantSpec.clone()"
                    + " failed (refusing to hand the engine the shared registry variant): "
                    + unwrap(e), unwrap(e));
        }
        if (clone == null || !variant.getClass().isInstance(clone) || clone == variant) {
            throw new IllegalStateException("cloneVariant(" + lane + "): clone() returned "
                    + (clone == null ? "null" : clone.getClass().getName()));
        }
        return clone;
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
    }
}
