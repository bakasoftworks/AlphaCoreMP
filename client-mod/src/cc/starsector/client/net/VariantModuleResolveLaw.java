package cc.starsector.client.net;

import java.util.function.Predicate;

/**
 * The fold-time station-module law of the client's
 * {@code VDEF} lane: {@code VariantDefinitionLane} asks it, with the engine registry's
 * {@code BootVariantRegistry::isRegistered} as the predicate, before it puts a FOLD-verdict spec.
 * It lives in {@code net} and is java.lang/java.util-only ON PURPOSE — no {@code com.fs.*}, no
 * {@code org.json} — so the game-free {@code CcClientVdefVerifier} compile
 * drives the very method the lane calls, with a fake registry.
 */
public final class VariantModuleResolveLaw {

    private VariantModuleResolveLaw() {
    }

    /**
     * Moved game-free so the vdef verifier
     * drives it. The first station-module variant id of
     * {@code def} that is not {@code registered}, or that names {@code def} itself; {@code null}
     * when every module resolves (and for a module-free definition). WHY: the engine's module walk
     * ({@code HullVariantSpec.getSizeWithModules}) dereferences the spec
     * its by-id getter returns for each module slot with no null guard — a game-Fatal class seen
     * live — and a self-referencing id would recurse. The reader binds a module's SLOT through the
     * id table but its variant id is a free id-law string, so only a fold-time check keeps such a
     * spec from EVER being put. An honest server orders module bodies ahead of their parent
     * and never serves a custom module, so a hit is a hostile-server
     * signal.
     */
    public static String unresolvableModuleOrNull(ClientVariantDefinition def, Predicate<String> registered) {
        return unresolvableModuleOrNull(def, registered, id -> false);
    }

    /**
     * Fold: the law above plus a module-SHAPE test — a module whose
     * REGISTERED spec itself carries station modules is refused too. WHY: the self-reference test
     * alone leaves a 2-cycle open through the FOLD overwrite put (fold A module-free, fold B with
     * module A, re-deliver A with module B — every id registered, none its own parent), and the
     * engine's module walks recurse through non-stock specs. The server's own module-shape law
     * ({@code WaveLoadoutInflater}: "module body nests modules" is refused) means an honest server
     * never serves a nesting module, so a hit stays a hostile-server signal. {@code nestsModules}
     * = the registry's {@code BootVariantRegistry::nestsModules} (fail-CLOSED on a registry fault).
     */
    public static String unresolvableModuleOrNull(ClientVariantDefinition def, Predicate<String> registered,
            Predicate<String> nestsModules) {
        for (ClientVariantDefinition.ModuleMount mount : def.modules()) {
            String id = mount.moduleVariantId();
            if (!registered.test(id) || id.equals(def.variantId()) || nestsModules.test(id)) {
                return id;
            }
        }
        return null;
    }

    /**
     * The law above plus the OTHER half of "a module body never nests
     * modules" — a definition that is ALREADY mounted as a module ({@code isModuleBody} = the registry's
     * {@code BootVariantRegistry::isModuleBody}) may not arrive with modules of its own; the law then names the
     * definition itself. WHY: the 3-arg law tests only the incoming parent's modules, so re-sending module body A
     * (mounted by B) with a flat module C passed and made the chain B → A → C, one level deeper per re-send — acyclic,
     * but as deep as a server cares to make it, under engine module walks that recurse. A module-FREE re-send of a
     * module body still folds, and so does a definition nobody mounts.
     */
    public static String unresolvableModuleOrNull(ClientVariantDefinition def, Predicate<String> registered,
            Predicate<String> nestsModules, Predicate<String> isModuleBody) {
        if (!def.modules().isEmpty() && isModuleBody.test(def.variantId())) {
            return def.variantId();
        }
        return unresolvableModuleOrNull(def, registered, nestsModules);
    }
}
