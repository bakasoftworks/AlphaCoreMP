package cc.starsector.client;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.loading.ProjectileSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

/**
 * Which projectile-spec OBJECT a presented mint reads.
 *
 * <p>The engine keeps TWO copies of every {@code .proj}/missile spec: the registry copy (by spec
 * id) and a private CLONE inside each weapon spec, made at load.
 * {@code ClientEveryFrameEffectSwap.swapOnHit}
 * rewrites the on-hit class through {@code WeaponSpecAPI.getProjectileSpec()} — the CLONE, the only
 * spec object the API exposes. {@code CombatEngine.spawnProjectile}
 * takes the spec from the launcher weapon's clone UNLESS arg-4 (the true spec id) is passed, in
 * which case it reads the pristine REGISTRY copy — vanilla's on-hit class,
 * whatever the swap did. Every missile the pose lane minted by true spec
 * therefore carried vanilla's on-hit; the sourceless (null-weapon) one met
 * {@code AssayingRiftEffect.onHit}'s weapon-spec dereference and Fatal'd every client.
 *
 * <p>Law: mint by the launcher RECIPE (6-arg) whenever the recipe yields the wire's true spec;
 * the true-spec overload is the MIRV-child path only (recipe spec id != true spec). A sourceless
 * body has no launcher — {@link #weaponIdForSpec} finds one whose clone IS that spec (the same
 * class-name rewrite has already landed on it). Zero wire bytes, zero server compute.
 *
 * <p><b>The recipe map walks {@code getActuallyAllWeaponSpecs()}.</b>
 * {@code getAllWeaponSpecs()} FILTERS every {@code AIHints.SYSTEM} / {@code WeaponType.SYSTEM}
 * weapon: the Rift Lightning minelayer, both Mine
 * Strike minelayers, every ship-system launcher. Their bodies had no recipe and fell to the carrier
 * mint by TRUE spec id, whose REGISTRY copy carries no weapon damage — the CSV damage lives on the
 * WEAPON's clone, and the engine's missile reads {@code spec.getDamage().clone()} — so one recorded
 * flight's 69 rift mines went through the engine door and exploded for 0 on every victim they
 * contacted (the rig's probe rows: {@code twin[base=0.0]}, {@code already} growing,
 * {@code lastDmg=0.0}). The SYSTEM-inclusive list is the one every swap layer already walks
 * ({@code ClientEveryFrameEffectSwap}, {@code ClientBeamEffectSwap}, {@code ClientOnFireAttachSweep}).
 * {@link #mountTypeOf} serves the recipe path's second vanilla half: vanilla's own null-weapon
 * spawners finish the mint with {@code applyDamageModifiersToSpawnedProjectileWithNullWeapon(source,
 * mountType, false, damage)} (api {@code RiftLightningEffect.spawnMine} ENERGY,
 * {@code MineStrikeStats} MISSILE). Pinned by {@code MineNativeFuseLawSmoke} F10 pre.
 */
final class PresentedMintRecipe {

    /** projectile spec id -> a registered weapon id whose recipe mints it (built once per JVM). */
    private static Map<String, String> weaponBySpec;
    /** projectile spec id -> that recipe weapon's mount type ({@code WeaponSpecAPI.getType()}), same build. */
    private static Map<String, WeaponAPI.WeaponType> typeBySpec;

    private PresentedMintRecipe() {
    }

    /** {@code true} iff the minted body's own spec id equals the wire's true spec id. */
    static boolean sameSpec(CombatEntityAPI spawned, String trueSpec) {
        if (trueSpec == null || !(spawned instanceof DamagingProjectileAPI)) {
            return false;
        }
        String id;
        try {
            id = ((DamagingProjectileAPI) spawned).getProjectileSpecId();
        } catch (Throwable t) {
            return false;
        }
        return trueSpec.equals(id);
    }

    /** A registered weapon id whose launcher recipe mints {@code specId}, or {@code null}. */
    static synchronized String weaponIdForSpec(String specId) {
        if (specId == null) {
            return null;
        }
        if (weaponBySpec == null) {
            build();
        }
        return weaponBySpec.get(specId);
    }

    /** The recipe weapon's mount type for {@code specId} — the type vanilla's null-weapon spawners hand
     *  {@code applyDamageModifiersToSpawnedProjectileWithNullWeapon} (class javadoc); {@code null} when the
     *  spec has no recipe or its type was unreadable (the caller then leaves the base amount). */
    static synchronized WeaponAPI.WeaponType mountTypeOf(String specId) {
        if (specId == null) {
            return null;
        }
        if (typeBySpec == null) {
            build();
        }
        return typeBySpec.get(specId);
    }

    private static void build() {
        Map<String, String> map = new HashMap<String, String>();
        Map<String, WeaponAPI.WeaponType> types = new HashMap<String, WeaponAPI.WeaponType>();
        try {
            // The SYSTEM-inclusive list (class javadoc) — getAllWeaponSpecs() hides every ship-system launcher.
            for (WeaponSpecAPI ws : Global.getSettings().getActuallyAllWeaponSpecs()) {
                String specId = projectileSpecId(ws);
                if (specId == null) {
                    continue;
                }
                String prior = map.get(specId);
                // Prefer the weapon that carries the spec's own id (e.g. weapon 'assaying_rift'
                // for spec 'assaying_rift'); otherwise first registered wins — every weapon's
                // clone of one spec received the same class-name rewrite.
                if (prior == null || specId.equals(ws.getWeaponId())) {
                    map.put(specId, ws.getWeaponId());
                    types.put(specId, mountType(ws));
                }
            }
        } catch (Throwable t) {
            PilotWatchLog.info("presented-mint-recipe map FAILED " + t.getClass().getSimpleName()
                    + " — sourceless mints fall to the registry spec");
        }
        PilotWatchLog.info("presented-mint-recipe map specs=" + map.size());
        weaponBySpec = map;
        typeBySpec = types;
    }

    /** The type vanilla's null-weapon spawner hands the modifier call where it differs from the recipe weapon's own
     *  {@code .wpn} type: the rift lightning / lance / beam minelayers are {@code "type":"MISSILE"} in
     *  their {@code .wpn}, yet api {@code RiftLightningEffect}, {@code RiftLanceEffect}, {@code RiftBeamEffect}
     *  and {@code RiftCascadeEffect} all pass ENERGY (the engine then reads the ENERGY damage mult).
     * Every other recipe uses its spec's own type ({@code minelayer1} / {@code 2} =
     *  MISSILE = {@code MineStrikeStats} / {@code StealthMinefield}). */
    private static final Map<String, WeaponAPI.WeaponType> SPAWNER_TYPE_BY_WEAPON = new HashMap<String, WeaponAPI.WeaponType>();
    static {
        for (String w : new String[] { "rift_lightning_minelayer", "riftlance_minelayer", "riftbeam_minelayer",
                "riftcascade_minelayer" }) {
            SPAWNER_TYPE_BY_WEAPON.put(w, WeaponAPI.WeaponType.ENERGY);
        }
    }

    private static WeaponAPI.WeaponType mountType(WeaponSpecAPI ws) {
        try {
            WeaponAPI.WeaponType vanilla = SPAWNER_TYPE_BY_WEAPON.get(ws.getWeaponId());
            return vanilla != null ? vanilla : ws.getType();
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static String projectileSpecId(WeaponSpecAPI ws) {
        try {
            Object ps = ws.getProjectileSpec();
            if (ps instanceof MissileSpecAPI) {
                MissileSpecAPI ms = (MissileSpecAPI) ps;
                return ms.getHullSpec() == null ? null : ms.getHullSpec().getHullId();
            }
            if (ps instanceof ProjectileSpecAPI) {
                return ((ProjectileSpecAPI) ps).getId();
            }
        } catch (Throwable ignore) {
            // a spec this client cannot read — leave it unmapped
        }
        return null;
    }
}
