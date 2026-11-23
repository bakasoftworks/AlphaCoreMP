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
 * <p>Swap-only-what-was-audited: the class map below is the verdict table's beam classes less the
 * graviton's (6 of its 7); {@link #AUDITED_VANILLA} names the graded classes that stay vanilla on purpose. A beam spec
 * carrying any OTHER effect name (mod / future game update) is left
 * untouched and announced loudly ONCE per name — unaudited sim doors must be visible, never
 * silently suppressed or silently trusted. Fail-soft ×3 (the sweep discipline): server-JVM
 * stand-down; per-spec swap failures leave that spec untouched; the whole sweep is caught to a
 * boolean. One receipt row per swap + one summary line ({@code wsg-swap …}).
 */
final class ClientBeamEffectSwap {

    /** Audited original → wrapper: the table's rows less the graviton's (6 classes / 6 vanilla weapon rows;
     *  {@code GravitonBeamEffect}, shared by gravitonbeam + gazer_payload, sits in {@link #AUDITED_VANILLA}). */
    private static final String[][] SWAPS = {
        {"com.fs.starfarer.api.impl.combat.IonBeamEffect",
         "cc.starsector.client.CcWrapIonBeamEffect"},
        {"com.fs.starfarer.api.impl.combat.TachyonLanceEffect",
         "cc.starsector.client.CcWrapTachyonLanceEffect"},
        {"com.fs.starfarer.api.impl.combat.PaladinEffect",
         "cc.starsector.client.CcWrapPaladinEffect"},
        {"com.fs.starfarer.api.impl.combat.RiftCascadeEffect",
         "cc.starsector.client.CcWrapRiftCascadeEffect"},
        {"com.fs.starfarer.api.impl.combat.RiftLanceEffect",
         "cc.starsector.client.CcWrapRiftLanceEffect"},
        {"com.fs.starfarer.api.impl.combat.dweller.AbyssalGlareEffect",
         "cc.starsector.client.CcWrapAbyssalGlareEffect"},
    };

    /**
     * AUDITED VANILLA: beam effects graded and deliberately LEFT RUNNING on replica beams (no wrapper, no announce).
     *
     * <p>{@code TacticalLaserEffect} (new at 0.98.5a-RC4): each tac laser that touches hull or armor raises the
     * victim's armor + hull damage-taken stat by 3 to 10 % for 1 s. WHY it stays vanilla: the effect is driven by
     * beam contact, and the client's beams are driven from the wire, so the client's own copy of the stat follows the
     * server's. Measured: the seat's local stat equalled the server's in 251 of 254
     * samples, 220 of 220 in steady state; the 3 misses sit at a debuff edge (client off, server on). A shut door
     * (the graviton shape) would have been wrong on 150 of those 254, leaving presented hits 3 to 10 % low. Residue,
     * parked: the edge cases close only with a wire-carried debuff tier.
     *
     * <p>{@code GravitonBeamEffect} (gravitonbeam + gazer_payload; un-shut): each graviton
     * beam on a shield raises the victim's shield damage-taken stat. It was wrapped shut on the premise that the
     * stat is server-owned and its suppression "visually lossless". That premise was wrong: the
     * engine's shield branch reads the stat for the shield damage number, the ripple and the flux a hit costs, no
     * wire field carries it, and under the shut door the client's copy read the base stat in 90 of 178 samples where
     * the server held x1.08 to x1.10. Same driver as the tac laser: beam contact, which the
     * client's wire-driven beams reproduce. {@link CcWrapGravitonBeamEffect} stays in the tree for its pure
     * {@code risingEdge} (the ion cadence calls it); nothing installs it.
     */
    private static final String[] AUDITED_VANILLA = {
        "com.fs.starfarer.api.impl.combat.TacticalLaserEffect",
        "com.fs.starfarer.api.impl.combat.GravitonBeamEffect",
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
        int auditedVanilla = 0;
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
            if (wrapper == null && isAuditedVanilla(prior)) {
                auditedVanilla++; // graded, left running (AUDITED_VANILLA javadoc = why)
                PilotWatchLog.info("wsg-swap row weapon=" + ws.getWeaponId() + " " + prior + " -> audited vanilla");
                continue;
            }
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
                + " no_effect=" + noEffect
                + " audited_vanilla=" + auditedVanilla);
        return swapped > 0;
    }

    /** True for a beam effect class named in {@link #AUDITED_VANILLA} (exact name; package-private for the row). */
    static boolean isAuditedVanilla(String effectClassName) {
        for (String audited : AUDITED_VANILLA) {
            if (audited.equals(effectClassName)) {
                return true;
            }
        }
        return false;
    }
}
