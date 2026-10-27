package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.loading.ProjectileSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.loading.specs.BaseWeaponSpec;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/**
 * The WeaponScriptGate everyFrame leg — the once-per-JVM CLIENT sweep that
 * swaps the 2 audited-LIVE vanilla {@code everyFrameEffect} class NAMES for their {@code CcWrap*}
 * subclasses. The everyFrame slot has NO public-API setter (audit): the only B-legal lever
 * is the engine-typed {@code BaseWeaponSpec.setEveryFrameClassName} (a public method
 * on the shared base; ALL weapon specs — beam {@code specs.Object} and
 * projectile — extend it).
 * Engine-typed adapters are B-legal ({@code check-b-deny-refs} proves it; the
 * {@link FireSoundAdapter} precedent already rides this exact import in-game). Timing matters:
 * each weapon ENTITY latches {@code getSpec().getEveryFrameEffect()} — a FRESH ScriptStore
 * {@code newInstance()} through the mod-jar-visible loader — at construction (audit), so an
 * onApplicationLoad name swap reaches every combat weapon while an already-built (menu) weapon
 * is untouched.
 *
 * <p>Swap-only-what-was-audited, everyFrame edition: vanilla ships 20 distinct everyFrame
 * classes over 22 {@code .wpn} rows (a census re-run matched the audit; our mod data adds
 * none). The 2 LIVE ones swap; the other 18 were each graded DORMANT-on-held-replicas with the
 * deciding gate cited in the audit table and are listed here so they stay SILENT — only a name outside
 * the audited 20 (mod / game update) announces loudly, once, as an unaudited door. Fail-soft ×3
 * (the sweep discipline): server-JVM stand-down; per-spec failures leave that spec untouched;
 * the whole sweep is caught to a boolean. A non-{@code BaseWeaponSpec} spec would mean runtime
 * drift from the pinned engine build — counted AND announced, never cast blindly.
 */
final class ClientEveryFrameEffectSwap {

    /** Audited-LIVE original → wrapper (the audit table: riftbeam minelayer door under force-fire;
     *  swarm_launcher's own-advance force-fire door). */
    private static final String[][] SWAPS = {
        {"com.fs.starfarer.api.impl.combat.RiftBeamEffect",
         "cc.starsector.client.CcWrapRiftBeamEffect"},
        {"com.fs.starfarer.api.impl.combat.threat.SwarmLauncherEffect",
         "cc.starsector.client.CcWrapSwarmLauncherEffect"},
        // EMP-arc presenters — dormant-by-audit on held replicas, now the arc is
        // DERIVED on the client from the presented shot (visual overload only; the damaging
        // spawnEmpArc*/spawnMine doors stay shut). Wrapper javadocs carry the vanilla line refs.
        {"com.fs.starfarer.api.impl.combat.dweller.RiftLightningEffect",
         "cc.starsector.client.CcWrapRiftLightningEffect"},
        {"com.fs.starfarer.api.impl.combat.threat.VoltaicCannonEffect",
         "cc.starsector.client.CcWrapVoltaicCannonEffect"},
        {"com.fs.starfarer.api.impl.combat.threat.VoltaicDischargeOnFireEffect",
         "cc.starsector.client.CcWrapVoltaicDischargeOnFireEffect"},
        // Fragment carriers — the carrier sprite is graphics/fx/empty.png; the
        // shot's only visual is the swarm onFire builds, which the presented mint never ran
        // (FragmentCarrierPresentLaw). Relay from MissilePoseLane; damage/fuse/HP doors shut.
        {"com.fs.starfarer.api.impl.combat.threat.KineticFragmentMissileEffect",
         "cc.starsector.client.CcWrapKineticFragmentMissileEffect"},
        {"com.fs.starfarer.api.impl.combat.threat.SeekerFragmentMissileEffect",
         "cc.starsector.client.CcWrapSeekerFragmentMissileEffect"},
        {"com.fs.starfarer.api.impl.combat.threat.UnstableFragmentMissileEffect",
         "cc.starsector.client.CcWrapUnstableFragmentMissileEffect"},
        {"com.fs.starfarer.api.impl.combat.threat.DevouringSwarmMissileEffect",
         "cc.starsector.client.CcWrapDevouringSwarmMissileEffect"},
        // The three Shrouded onFire-built visuals — the
        // inimical arc (D-INIMPHASE), the pseudoparticle fuzz (D-PJETFX) and the rift glow trail
        // (D-HRIFTFX) — dormant was the DEFECT again; damage/nudge/fuse/heal doors shut.
        {"com.fs.starfarer.api.impl.combat.dweller.InimicalEmanationOnFireEffect",
         "cc.starsector.client.CcWrapInimicalEmanationOnFireEffect"},
        {"com.fs.starfarer.api.impl.combat.dweller.PseudoparticleJetEffect",
         "cc.starsector.client.CcWrapPseudoparticleJetEffect"},
        {"com.fs.starfarer.api.impl.combat.dweller.AssayingRiftEffect",
         "cc.starsector.client.CcWrapAssayingRiftEffect"},
    };

    /**
     * The ON_HIT rows. A projectile dispatches
     * {@code onHit} from ITS OWN spec's {@code onHitEffect} slot, latched as a FRESH instance at
     * construction
     * — never from the weapon's everyFrame plugin. So for the three
     * {@code .proj} rows that name a vanilla class we already wrap above, the wrapper's
     * {@code onHit} override was DEAD until this leg rewrote the projectile spec's slot: vanilla's
     * damaging voltaic arcs, the devouring disintegrator and the rift mine all ran on presented
     * shots. Public API on both spec faces ({@code ProjectileSpecAPI},
     * {@code MissileSpecAPI} — a missile carrier's spec is
     * the latter). Same wrapper CLASS as the everyFrame row: one class, two slots.
     */
    private static final String[][] ON_HIT_SWAPS = {
        {"com.fs.starfarer.api.impl.combat.dweller.RiftLightningEffect",
         "cc.starsector.client.CcWrapRiftLightningEffect"},
        {"com.fs.starfarer.api.impl.combat.threat.VoltaicCannonEffect",
         "cc.starsector.client.CcWrapVoltaicCannonEffect"},
        {"com.fs.starfarer.api.impl.combat.threat.DevouringSwarmMissileEffect",
         "cc.starsector.client.CcWrapDevouringSwarmMissileEffect"},
        // The assaying rift's .proj onHit slot carries the shroud-ally HEAL door.
        {"com.fs.starfarer.api.impl.combat.dweller.AssayingRiftEffect",
         "cc.starsector.client.CcWrapAssayingRiftEffect"},
        // The projectile ion family's onHit-ONLY class (ioncannon_shot + ionpulser_shot
        // share it; no everyFrame row) — the replica's local 25 % damaging-arc roll is the RN
        // divergence; the server's roll rides the wire as ARC_HIT flavor ION.
        {"com.fs.starfarer.api.impl.combat.IonCannonOnHitEffect",
         "cc.starsector.client.CcWrapIonCannonOnHitEffect"},
        // The sabot MIRV child's onHit-ONLY class (sabot_warhead2.proj — no weapon's own
        // projectile spec; reached through swapOnHit's child-spec leg). Same divergence class as the
        // ion row at dam 0; the server's roll rides the wire as ARC_HIT flavor SABOT.
        {"com.fs.starfarer.api.impl.combat.SabotOnHitEffect",
         "cc.starsector.client.CcWrapSabotOnHitEffect"},
    };

    /** The other 8 audited vanilla everyFrame classes — every one graded DORMANT on held
     *  replicas (no local fire dispatch / chargeLevel 0 / dweller tag / player-ship-only /
     *  decorative), left vanilla BY VERDICT, not by omission. (Later passes moved the three
     *  EMP-arc effects to SWAPS, then the four fragment carriers and the three Shrouded
     *  onFire-built visuals: dormant was the DEFECT — the visual never presented. Cryoflux stays
     *  here only because no Bastion variant mounts it; it is the same defect class as the jet.) */
    private static final Set<String> AUDITED_DORMANT = new HashSet<String>(Arrays.asList(
        "com.fs.starfarer.api.impl.combat.BlinkerEffect",
        "com.fs.starfarer.api.impl.combat.CryofluxTransducerEffect",
        "com.fs.starfarer.api.impl.combat.LightsEffect",
        "com.fs.starfarer.api.impl.combat.RealityDisruptorEffect",
        "com.fs.starfarer.api.impl.combat.SensorDishRotationEffect",
        "com.fs.starfarer.api.impl.combat.VPDriverEffect",
        "com.fs.starfarer.api.impl.combat.dweller.VortexLauncherEffect",
        "com.fs.starfarer.api.impl.combat.threat.VoidblasterEffect"));

    private ClientEveryFrameEffectSwap() {
    }

    /** Selftest face (game-free): the wrapper the sweep installs for a vanilla class, or null. */
    static String wrapperFor(String original) {
        for (String[] pair : SWAPS) {
            if (pair[0].equals(original)) {
                return pair[1];
            }
        }
        return null;
    }

    /** Selftest face (game-free): the onHit wrapper the sweep writes into a projectile spec whose
     *  slot names this vanilla class, or null. */
    static String onHitWrapperFor(String original) {
        for (String[] pair : ON_HIT_SWAPS) {
            if (pair[0].equals(original)) {
                return pair[1];
            }
        }
        return null;
    }

    /** Selftest face (game-free): is the vanilla class on the audited-DORMANT (left vanilla) list? */
    static boolean auditedDormant(String original) {
        return AUDITED_DORMANT.contains(original);
    }

    /** Run the sweep (client JVMs only). True = at least one spec swapped. */
    static boolean runIfEnabled() {
        // Server JVM loads this mod for content fixtures but runs the AUTHORITATIVE sim — its
        // weapon effects must stay vanilla (same stand-down as ClientBeamEffectSwap).
        if ("server".equals(System.getProperty("cc.jvm.role"))) {
            PilotWatchLog.info("wsg-efswap skipped reason=server_jvm");
            return false;
        }
        try {
            return sweep();
        } catch (Throwable t) {
            PilotWatchLog.error("wsg-efswap failed error=" + t.getClass().getName()
                    + ": " + t.getMessage(), t);
            return false;
        }
    }

    private static boolean sweep() {
        SettingsAPI settings = Global.getSettings();
        List<WeaponSpecAPI> weaponSpecs = settings == null ? null : settings.getActuallyAllWeaponSpecs();
        if (weaponSpecs == null || weaponSpecs.isEmpty()) {
            PilotWatchLog.info("wsg-efswap swapped=0 (no weapon specs)");
            return false;
        }
        Map<String, String> wrapperByOriginal = new HashMap<String, String>();
        Map<String, String> originalByWrapper = new HashMap<String, String>();
        for (String[] pair : SWAPS) {
            wrapperByOriginal.put(pair[0], pair[1]);
            originalByWrapper.put(pair[1], pair[0]);
        }
        ClassLoader loader = ClientEveryFrameEffectSwap.class.getClassLoader();
        Map<String, String> onHitWrapperByOriginal = new HashMap<String, String>();
        Map<String, String> onHitOriginalByWrapper = new HashMap<String, String>();
        for (String[] pair : ON_HIT_SWAPS) {
            onHitWrapperByOriginal.put(pair[0], pair[1]);
            onHitOriginalByWrapper.put(pair[1], pair[0]);
        }
        int[] onHit = new int[5]; // swapped, skipped_dup, swap_failed, child_swapped, child_unresolved
        int swapped = 0;
        int skippedDup = 0;
        int dormant = 0;
        int unlisted = 0;
        int swapFailed = 0;
        int notBase = 0;
        for (WeaponSpecAPI ws : weaponSpecs) {
            // The projectile-spec onHit leg rides the public API, so it runs for EVERY
            // weapon spec, before the everyFrame leg's BaseWeaponSpec pin.
            swapOnHit(ws, onHitWrapperByOriginal, onHitOriginalByWrapper, loader, onHit);
            if (!(ws instanceof BaseWeaponSpec)) {
                // Runtime type outside the pinned engine build's hierarchy — never cast blindly; if such
                // a spec carried a LIVE everyFrame class we could not even read the name.
                notBase++;
                FallbackAnnounce.announceOnce("wsg-efswap-notbase#" + ws.getClass().getName(),
                        "weapon spec outside the BaseWeaponSpec pin: " + ws.getClass().getName()
                                + " (weapon " + ws.getWeaponId() + ")");
                continue;
            }
            BaseWeaponSpec bs = (BaseWeaponSpec) ws;
            String prior = bs.getEveryFrameClassName();
            if (prior == null || prior.isEmpty()) {
                continue; // the common case: no everyFrame effect on this weapon
            }
            if (originalByWrapper.containsKey(prior)) {
                skippedDup++; // onApplicationLoad re-run — already ours
                continue;
            }
            String wrapper = wrapperByOriginal.get(prior);
            if (wrapper == null) {
                if (AUDITED_DORMANT.contains(prior)) {
                    dormant++; // audited, verdict DORMANT — vanilla by design, stay silent
                } else {
                    // An UNAUDITED everyFrame class: leave vanilla (never suppress unaudited),
                    // but say so loudly — a new audit-table row waiting to be graded.
                    unlisted++;
                    FallbackAnnounce.announceOnce("wsg-unlisted-everyframe-effect#" + prior,
                            "unaudited everyFrameEffect left vanilla: " + prior
                                    + " (weapon " + ws.getWeaponId() + ")");
                }
                continue;
            }
            try {
                // Prove the wrapper constructible AND a subtype of its audited original BEFORE
                // touching the spec — original-assignability covers every interface the engine
                // (and sibling SwarmLaunchingWeapon readers) may downcast the plugin to.
                Class<?> cls = Class.forName(wrapper, true, loader);
                if (!Class.forName(prior, true, loader).isAssignableFrom(cls)) {
                    throw new IllegalStateException(wrapper + " does not extend " + prior);
                }
                MethodHandles.publicLookup()
                        .findConstructor(cls, MethodType.methodType(void.class)).invoke();
                bs.setEveryFrameClassName(wrapper);
                swapped++;
                PilotWatchLog.info("wsg-efswap row weapon=" + ws.getWeaponId()
                        + " " + prior + " -> " + wrapper);
            } catch (Throwable swapFailure) {
                swapFailed++; // spec untouched: vanilla ghosting stays, loudly
                FallbackAnnounce.announceOnce("wsg-efswap-failed#" + prior,
                        "everyFrame swap failed: " + swapFailure.getClass().getSimpleName());
            }
        }
        PilotWatchLog.info("wsg-efswap swapped=" + swapped
                + " skipped_dup=" + skippedDup
                + " dormant=" + dormant
                + " unlisted=" + unlisted
                + " swap_failed=" + swapFailed
                + " not_base=" + notBase
                + " onhit_swapped=" + onHit[0]
                + " onhit_skipped_dup=" + onHit[1]
                + " onhit_swap_failed=" + onHit[2]
                + " onhit_child_swapped=" + onHit[3]
                + " onhit_child_unresolved=" + onHit[4]);
        return swapped > 0 || onHit[0] > 0 || onHit[3] > 0;
    }

    /**
     * Rewrite ONE weapon's projectile-spec {@code onHitEffect} class when it names an
     * {@link #ON_HIT_SWAPS} original. A {@code .proj} spec is shared by every weapon that fires
     * it, so the second weapon over the same spec counts {@code skipped_dup}. onHit classes
     * outside the table stay vanilla SILENTLY: rows sharing an audited everyFrame class were
     * graded there (the everyFrame leg's unlisted announce covers the population); the ion row
     * is the first onHit-ONLY class, audited by the cluster-1 recon. Same guards as
     * the everyFrame leg — constructible, assignable to the original — before the spec is
     * touched; a failure leaves that spec vanilla, loudly. {@code counters} = {swapped,
     * skipped_dup, swap_failed, child_swapped, child_unresolved}.
     *
     * <p><b>The MIRV-child leg:</b> a MIRV stage's children are minted from a spec that
     * is NO weapon's own projectile spec — it is named only inside the stage's {@code behaviorSpec}
     * params ({@code projectileSpec}, e.g. {@code sabot_srm.proj} → {@code sabot_warhead2}) and
     * lives in the engine's projectile-spec store. Every child mint path copies that store spec's
     * onHit class NAME (the split lane's per-split clone + its by-id fallback, vanilla's own
     * {@code MirvAI} clone) and the engine's {@code BallisticProjectile} ctor latches a FRESH instance
     * from the name (a ScriptStore lookup by string), so
     * rewriting the STORE spec's slot once here reaches them all. Resolved through
     * {@link MissileSplitLane#storeSpec} (the same store the 7-arg {@code spawnProjectile} reads);
     * a store miss counts {@code child_unresolved} and announces once — that child keeps vanilla's
     * roll, loudly, never a throw out of the sweep.
     */
    private static void swapOnHit(WeaponSpecAPI ws, Map<String, String> wrapperByOriginal,
            Map<String, String> originalByWrapper, ClassLoader loader, int[] counters) {
        Object spec = ws.getProjectileSpec();
        if (!(spec instanceof ProjectileSpecAPI) && !(spec instanceof MissileSpecAPI)) {
            return; // beams and the like: no projectile spec
        }
        swapOnHitSlot(spec, ws.getWeaponId(), wrapperByOriginal, originalByWrapper, loader, counters, 0);
        String childId = null;
        try {
            JSONObject behavior = spec instanceof MissileSpecAPI
                    ? ((MissileSpecAPI) spec).getBehaviorJSON()
                    : ((ProjectileSpecAPI) spec).getBehaviorJSON();
            childId = behavior == null ? null : behavior.optString("projectileSpec", null);
            if (childId == null || childId.isEmpty()) {
                return; // not a MIRV stage (or a stage with no child spec)
            }
            Object child = MissileSplitLane.storeSpec(childId);
            if (!(child instanceof ProjectileSpecAPI) && !(child instanceof MissileSpecAPI)) {
                counters[4]++;
                FallbackAnnounce.announceOnce("wsg-onhitswap-child-unresolved#" + childId,
                        "MIRV child spec unresolved in the store: " + childId);
                return;
            }
            swapOnHitSlot(child, ws.getWeaponId() + ">" + childId, wrapperByOriginal,
                    originalByWrapper, loader, counters, 3);
        } catch (Throwable childFailure) {
            counters[4]++; // the child keeps vanilla's roll, loudly
            FallbackAnnounce.announceOnce("wsg-onhitswap-child-failed#" + ws.getWeaponId(),
                    "MIRV child onHit leg failed: " + childFailure.getClass().getSimpleName());
        }
    }

    /** One spec's onHit slot ({@code ProjectileSpecAPI} or {@code MissileSpecAPI} face): rewrite it
     *  when it names an {@link #ON_HIT_SWAPS} original; {@code row} labels the log line;
     *  {@code swappedSlot} = the counter a swap increments (0 = a weapon's own spec, 3 = a MIRV
     *  child spec). */
    private static void swapOnHitSlot(Object spec, String row, Map<String, String> wrapperByOriginal,
            Map<String, String> originalByWrapper, ClassLoader loader, int[] counters, int swappedSlot) {
        String prior = spec instanceof ProjectileSpecAPI
                ? ((ProjectileSpecAPI) spec).getOnHitClassName()
                : ((MissileSpecAPI) spec).getOnHitClassName();
        if (prior == null || prior.isEmpty()) {
            return;
        }
        if (originalByWrapper.containsKey(prior)) {
            counters[1]++; // shared .proj already rewritten (or an onApplicationLoad re-run)
            return;
        }
        String wrapper = wrapperByOriginal.get(prior);
        if (wrapper == null) {
            return;
        }
        try {
            Class<?> cls = Class.forName(wrapper, true, loader);
            if (!Class.forName(prior, true, loader).isAssignableFrom(cls)) {
                throw new IllegalStateException(wrapper + " does not extend " + prior);
            }
            MethodHandles.publicLookup()
                    .findConstructor(cls, MethodType.methodType(void.class)).invoke();
            if (spec instanceof ProjectileSpecAPI) {
                ((ProjectileSpecAPI) spec).setOnHitClassName(wrapper);
            } else {
                ((MissileSpecAPI) spec).setOnHitClassName(wrapper);
            }
            counters[swappedSlot]++;
            PilotWatchLog.info("wsg-onhitswap row weapon=" + row + " " + prior + " -> " + wrapper);
        } catch (Throwable swapFailure) {
            counters[2]++; // spec untouched: vanilla's damaging onHit stays, loudly
            FallbackAnnounce.announceOnce("wsg-onhitswap-failed#" + prior,
                    "onHit swap failed: " + swapFailure.getClass().getSimpleName());
        }
    }
}
