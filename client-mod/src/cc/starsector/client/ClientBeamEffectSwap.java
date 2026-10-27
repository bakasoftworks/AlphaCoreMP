package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WeaponScriptGate, beam leg — the once-per-JVM CLIENT sweep that swaps
 * every audited vanilla {@code beamEffect} class NAME for its {@code CcWrap*} subclass via the
 * supported {@link BeamWeaponSpecAPI#setEffectClassName} (public API; each
 * {@code BeamWeaponRay} mints a FRESH instance by name through the mod-jar-visible loader at
 * ray ctor — the {@link ClientOnFireAttachSweep} seam). Wrappers SUBCLASS their
 * originals, so a swapped spec behaves byte-identically for non-replica sources
 * ({@code super.advance()}) and the treatment activates only behind
 * {@link CcReplicaEffectGate#isTreatedReplica} — an unbound (serverless) window stays vanilla.
 *
 * <p>Swap-only-what-was-audited: the class map below is exactly the verdict table's 7 beam
 * classes. A beam spec carrying any OTHER effect name (mod / future game update) is left
 * untouched and announced loudly ONCE per name — unaudited sim doors must be visible, never
 * silently suppressed or silently trusted. Fail-soft ×3 (the sweep discipline): server-JVM
 * stand-down; per-spec swap failures leave that spec untouched; the whole sweep is caught to a
 * boolean. One receipt row per swap + one summary line ({@code wsg-swap …}).
 */
final class ClientBeamEffectSwap {

    /** Audited original → wrapper, the table verbatim (7 classes / 8 vanilla weapon rows —
     *  gravitonbeam + gazer_payload share GravitonBeamEffect). */
    private static final String[][] SWAPS = {
        {"com.fs.starfarer.api.impl.combat.IonBeamEffect",
         "cc.starsector.client.CcWrapIonBeamEffect"},
        {"com.fs.starfarer.api.impl.combat.TachyonLanceEffect",
         "cc.starsector.client.CcWrapTachyonLanceEffect"},
        {"com.fs.starfarer.api.impl.combat.PaladinEffect",
         "cc.starsector.client.CcWrapPaladinEffect"},
        {"com.fs.starfarer.api.impl.combat.GravitonBeamEffect",
         "cc.starsector.client.CcWrapGravitonBeamEffect"},
        {"com.fs.starfarer.api.impl.combat.RiftCascadeEffect",
         "cc.starsector.client.CcWrapRiftCascadeEffect"},
        {"com.fs.starfarer.api.impl.combat.RiftLanceEffect",
         "cc.starsector.client.CcWrapRiftLanceEffect"},
        {"com.fs.starfarer.api.impl.combat.dweller.AbyssalGlareEffect",
         "cc.starsector.client.CcWrapAbyssalGlareEffect"},
    };

    private ClientBeamEffectSwap() {
    }

    /** Run the sweep (client JVMs only). True = at least one spec swapped. */
    static boolean runIfEnabled() {
        // Server JVM loads this mod for content fixtures but runs the AUTHORITATIVE sim — its
        // beam effects must stay vanilla (same stand-down as ClientOnFireAttachSweep).
        if ("server".equals(System.getProperty("cc.jvm.role"))) {
            PilotWatchLog.info("wsg-swap skipped reason=server_jvm");
            return false;
        }
        try {
            return sweep();
        } catch (Throwable t) {
            PilotWatchLog.error("wsg-swap failed error=" + t.getClass().getName()
                    + ": " + t.getMessage(), t);
            return false;
        }
    }

    private static boolean sweep() {
        SettingsAPI settings = Global.getSettings();
        // getActuallyAllWeaponSpecs: includes system/hidden specs —
        // covers the darkened_gaze system split riding AbyssalGlareEffect.
        List<WeaponSpecAPI> weaponSpecs = settings == null ? null : settings.getActuallyAllWeaponSpecs();
        if (weaponSpecs == null || weaponSpecs.isEmpty()) {
            PilotWatchLog.info("wsg-swap swapped=0 skipped_dup=0 unlisted=0 swap_failed=0 (no weapon specs)");
            return false;
        }
        Map<String, String> wrapperByOriginal = new HashMap<String, String>();
        Map<String, String> originalByWrapper = new HashMap<String, String>();
        for (String[] pair : SWAPS) {
            wrapperByOriginal.put(pair[0], pair[1]);
            originalByWrapper.put(pair[1], pair[0]);
        }
        ClassLoader loader = ClientBeamEffectSwap.class.getClassLoader();
        int swapped = 0;
        int skippedDup = 0;
        int unlisted = 0;
        int swapFailed = 0;
        int noEffect = 0;
        for (WeaponSpecAPI ws : weaponSpecs) {
            if (!(ws instanceof BeamWeaponSpecAPI)) {
                continue;
            }
            BeamWeaponSpecAPI bs = (BeamWeaponSpecAPI) ws;
            String prior = bs.getEffectClassName();
            if (prior == null) {
                noEffect++;
                continue;
            }
            if (originalByWrapper.containsKey(prior)) {
                skippedDup++; // onApplicationLoad re-run — already ours
                continue;
            }
            String wrapper = wrapperByOriginal.get(prior);
            if (wrapper == null) {
                // An UNAUDITED beam effect class: leave vanilla (never suppress unaudited),
                // but say so loudly — this is a new verdict-table row waiting to be graded.
                unlisted++;
                FallbackAnnounce.announceOnce("wsg-unlisted-beam-effect#" + prior,
                        "unaudited beamEffect left vanilla: " + prior
                                + " (weapon " + ws.getWeaponId() + ")");
                continue;
            }
            try {
                // Prove the wrapper constructible+assignable BEFORE touching the spec (its ctor
                // implicitly constructs the vanilla parent, so this also proves the original).
                Class<?> cls = Class.forName(wrapper, true, loader);
                if (!BeamEffectPlugin.class.isAssignableFrom(cls)) {
                    throw new IllegalStateException(wrapper + " is not a BeamEffectPlugin");
                }
                MethodHandles.publicLookup()
                        .findConstructor(cls, MethodType.methodType(void.class)).invoke();
                bs.setEffectClassName(wrapper);
                swapped++;
                PilotWatchLog.info("wsg-swap row weapon=" + ws.getWeaponId()
                        + " " + prior + " -> " + wrapper);
            } catch (Throwable swapFailure) {
                swapFailed++; // spec untouched: unchanged-or-not-installed, vanilla ghosting stays
                FallbackAnnounce.announceOnce("wsg-swap-failed#" + prior, "beam swap failed: "
                        + swapFailure.getClass().getSimpleName());
            }
        }
        PilotWatchLog.info("wsg-swap swapped=" + swapped
                + " skipped_dup=" + skippedDup
                + " unlisted=" + unlisted
                + " swap_failed=" + swapFailed
                + " no_effect=" + noEffect);
        return swapped > 0;
    }
}
