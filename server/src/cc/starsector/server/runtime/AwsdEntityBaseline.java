package cc.starsector.server.runtime;

import java.util.List;

/**
 * One ship's immutable QUANTIZED anchor state for the AWSD delta+keyframe family:
 * the exact grid-step values a KEYFRAME record carries, held
 * server-side as the shared per-entity baseline every DELTA is computed against,
 * and client-side as the rolling resolve state. Quantized-space truth (D4): the
 * server stores THIS struct at keyframe encode, computes deltas as grid steps vs
 * it, and the client's reconstruction is bit-exactly these values — there is no
 * float drift channel because floats never round-trip, grid steps do.
 *
 * <p>Every component is a WIRE-domain integer (u8/u16/i16 carriage enforced here;
 * {@code qx}/{@code qy} ride full i32 grid steps — the pose-grid widen —
 * so any int is legal there; {@code maxHp} rides a positive i32 — the v7
 * widen;
 * DOMAIN semantics are enforced at resolve time by the tier records' fail-closed
 * {@code fromWire} validators — see {@link AwsdSnapshotCodec} resolve methods).
 * Grids: 0.25 su position, 0.5 velocity, 0.5° facing
 * (720 steps/turn), 0.5°/s angular velocity, 1⁄255 fractions, 1 HP / 1 flux maxima,
 * 0.1 s system cooldown, 0.01 s mount cooldown-max.
 *
 * <p>{@code epoch} is the u8 anchor identity a DELTA must name to be applied
 * (D5); {@code anchorTick} is the simTick of the keyframe that minted this
 * baseline. {@code ownerWire} carries {@code ownerConnectionId} with the
 * 0xFFFF ↔ −1 NO_OWNER mapping; {@code sysAmmoWire} carries the
 * system ammo with the 0xFF ↔ NO_AMMO(−1) mapping; {@link #SYS_NONE_WIRE}
 * {@code sysStateCode} means no-system hull (v8 — canonical absent
 * shape enforced; armor modules carry no ship system). The v3 cloak block
 * rides {@code cloakStateWire}/{@code cloakEffectFrac}/{@code qCloakCd}/
 * {@code cloakAmmoWire} — {@link #CLOAK_NONE_WIRE} state means no-cloak ship
 * (canonical absent shape enforced); cloak cd shares the 0.1 s system grid.
 * The v6 CR block rides {@code crU8}/{@code depHalfSecU16}/
 * {@code peakHalfSecU16}: cr = 1⁄255 fraction, dep/peak = 0.5 s grid SATURATED
 * at 0xFFFF (pins P0 D1 — never-lose-CR hulls carry effective peaks ≥ 1e5 s by
 * design); {@code peakHalfSecU16 == 0} is the absent-CR sentinel (P0 D2 —
 * resolve does not mint, canonical absent shape 0/0/0).
 * The v10 armor field rides {@code armorFrac}: the ship's armor
 * pool as a 1⁄255 fraction — fraction family, but with NO min-1 clamp (armor
 * legitimately ablates to exactly 0; the min-1 floor is hull-only).
 * Mounts ride sorted ascending by unique {@code mountIndex} (the domain tier
 * order), at most 255.
 */
public record AwsdEntityBaseline(
        int entityId,
        int epoch,
        long anchorTick,
        int contentId,
        int ownerWire,
        int qx,
        int qy,
        int qvx,
        int qvy,
        int qFacing,
        int qAngVel,
        int maxHp,
        int hullFrac,
        int armorFrac,
        int maxFlux,
        int fluxLevel,
        int hardFluxFrac,
        int fluxFlags,
        int shieldFacingU8,
        int fleetSide,
        int engineDisabledMask,
        int enginePermDisabledMask,
        int engineControllerState,
        int maneuverMask,
        int sysStateCode,
        int sysEffectLevel,
        int qSysCd,
        int sysAmmoWire,
        int cloakStateWire,
        int cloakEffectFrac,
        int qCloakCd,
        int cloakAmmoWire,
        int crU8,
        int depHalfSecU16,
        int peakHalfSecU16,
        int fighterRatePct,
        List<Mount> mounts) {

    /** Wire {@code ownerWire} value carrying {@code NO_OWNER_CONNECTION} (−1). */
    public static final int OWNER_NONE_WIRE = 0xFFFF;
    /** Wire {@code sysAmmoWire} value carrying {@code ShipSystemStateSnapshot.NO_AMMO} (−1). */
    public static final int SYS_AMMO_NONE_WIRE = 0xFF;
    /**
     * Wire {@code sysStateCode} value for a ship with NO ship system (v8): the
     * system tier is SPARSE — vanilla armor modules ship an EMPTY system id column in
     * {@code ship_data.csv} — so absence rides in-band as this sentinel with the canonical
     * absent shape (sysEffectLevel 0, qSysCd 0, sysAmmoWire {@link #SYS_AMMO_NONE_WIRE};
     * one bit pattern per meaning, ctor-enforced; the cloak-tier idiom).
     */
    public static final int SYS_NONE_WIRE = 0xFF;
    /**
     * Wire {@code cloakStateWire} value for a ship with NO phase cloak (v3): the
     * cloak tier is SPARSE — most hulls have no {@code getPhaseCloak()} slot — so absence
     * rides in-band as this sentinel with the canonical absent shape (effectFrac 0, qCloakCd
     * 0, ammoWire {@link #SYS_AMMO_NONE_WIRE}; one bit pattern per meaning, ctor-enforced).
     */
    public static final int CLOAK_NONE_WIRE = 0xFF;
    /**
     * Wire {@code fighterRatePct} value for a record with NO fighter-replacement percent
     * (v12): the byte carries the CR record's panel-arithmetic percent
     * 0..100 (keyframe-only, hold-last on deltas like the CR block) or this sentinel when
     * the CR record is absent / its read unbound. Independent of the CR-absent 0/0/0 shape.
     */
    public static final int FIGHTER_RATE_NONE_WIRE = 0xFF;

    /**
     * One mount's quantized keyframe fields. {@code qCooldownMax} @0.01 s,
     * {@code maxAmmo}/{@code ammo} are the usesAmmo-NORMALIZED u16 pair
     * (0/0 whenever the USES_AMMO flag is clear — the flag is the truth carrier),
     * {@code cdFrac}/{@code chargeFrac} are 1⁄255 fractions, {@code flags} the
     * ShipWeaponMountSnapshot status bits.
     */
    public record Mount(
            int mountIndex,
            int weaponContentId,
            int qCooldownMax,
            int maxAmmo,
            int ammo,
            int cdFrac,
            int chargeFrac,
            int flags) {

        public Mount {
            requireU8("mountIndex", mountIndex);
            requireU16("weaponContentId", weaponContentId);
            requireU16("qCooldownMax", qCooldownMax);
            requireU16("maxAmmo", maxAmmo);
            requireU16("ammo", ammo);
            requireU8("cdFrac", cdFrac);
            requireU8("chargeFrac", chargeFrac);
            requireU8("flags", flags);
        }
    }

    public AwsdEntityBaseline {
        if (entityId < 1 || entityId > 0xFFFF) {
            throw new IllegalArgumentException("entityId outside wire u16 [1,65535]: " + entityId);
        }
        requireU8("epoch", epoch);
        if (anchorTick < 0L) {
            throw new IllegalArgumentException("anchorTick must be >= 0; got " + anchorTick);
        }
        requireU16("contentId", contentId);
        requireU16("ownerWire", ownerWire);
        // qx/qy are full i32 carriers (the pose-grid widen): any int value is legal.
        requireI16("qvx", qvx);
        requireI16("qvy", qvy);
        if (qFacing < 0 || qFacing >= 720) {
            throw new IllegalArgumentException("qFacing outside [0,720): " + qFacing);
        }
        requireI16("qAngVel", qAngVel);
        // v7: maxHp rides a full i32 — u16 refused real hulls (station3
        // base = 160000 HP). Integer.MAX_VALUE is refused HERE too:
        // the codec's quantize treats it as its Math.round saturation sentinel, so
        // a baseline carrying it could never have come from a faithful encode.
        if (maxHp < 1 || maxHp == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maxHp outside wire i32 positive domain [1,2147483646]: " + maxHp);
        }
        requireU8("hullFrac", hullFrac);
        requireU8("armorFrac", armorFrac);
        requireU16("maxFlux", maxFlux);
        requireU8("fluxLevel", fluxLevel);
        requireU8("hardFluxFrac", hardFluxFrac);
        requireU8("fluxFlags", fluxFlags);
        requireU8("shieldFacingU8", shieldFacingU8);
        requireU8("fleetSide", fleetSide);
        // engine masks are full u32 carriers: any int bit pattern is legal.
        requireU8("engineControllerState", engineControllerState);
        requireU8("maneuverMask", maneuverMask);
        requireU8("sysStateCode", sysStateCode);
        requireU8("sysEffectLevel", sysEffectLevel);
        requireU16("qSysCd", qSysCd);
        requireU8("sysAmmoWire", sysAmmoWire);
        requireU8("cloakStateWire", cloakStateWire);
        requireU8("cloakEffectFrac", cloakEffectFrac);
        requireU16("qCloakCd", qCloakCd);
        requireU8("cloakAmmoWire", cloakAmmoWire);
        requireU8("crU8", crU8);
        requireU16("depHalfSecU16", depHalfSecU16);
        requireU16("peakHalfSecU16", peakHalfSecU16);
        if (peakHalfSecU16 == 0 && (crU8 != 0 || depHalfSecU16 != 0)) {
            // peakHalfSecU16 == 0 is the absent-CR sentinel (P0 D2): one bit pattern
            // per meaning — absent must ride the canonical 0/0/0 shape (cloak law).
            throw new IllegalArgumentException(
                    "CR-absent (peakHalfSecU16 0) must ride the canonical shape 0/0/0; got "
                            + crU8 + "/" + depHalfSecU16);
        }
        if (fighterRatePct != FIGHTER_RATE_NONE_WIRE && (fighterRatePct < 0 || fighterRatePct > 100)) {
            throw new IllegalArgumentException(
                    "fighterRatePct outside 0..100 | FIGHTER_RATE_NONE_WIRE: " + fighterRatePct);
        }
        if (sysStateCode == SYS_NONE_WIRE) {
            // Canonical absent shape (one bit pattern per meaning): a systemless ship's
            // remaining system fields are pinned so equality/delta comparisons stay exact.
            if (sysEffectLevel != 0 || qSysCd != 0 || sysAmmoWire != SYS_AMMO_NONE_WIRE) {
                throw new IllegalArgumentException(
                        "SYS_NONE must ride the canonical absent shape (0/0/0xFF); got "
                                + sysEffectLevel + "/" + qSysCd + "/" + sysAmmoWire);
            }
        } else if (sysStateCode > 4) {
            throw new IllegalArgumentException(
                    "sysStateCode outside the project-owned codes 0..4 (or 0xFF none): "
                            + sysStateCode);
        }
        if (cloakStateWire == CLOAK_NONE_WIRE) {
            // Canonical absent shape (one bit pattern per meaning): a cloakless ship's
            // remaining cloak fields are pinned so equality/delta comparisons stay exact.
            if (cloakEffectFrac != 0 || qCloakCd != 0 || cloakAmmoWire != SYS_AMMO_NONE_WIRE) {
                throw new IllegalArgumentException(
                        "CLOAK_NONE must ride the canonical absent shape (0/0/0xFF); got "
                                + cloakEffectFrac + "/" + qCloakCd + "/" + cloakAmmoWire);
            }
        } else if (cloakStateWire > 4) {
            throw new IllegalArgumentException(
                    "cloakStateWire outside the project-owned codes 0..4 (or 0xFF none): "
                            + cloakStateWire);
        }
        mounts = List.copyOf(mounts);
        if (mounts.size() > 255) {
            throw new IllegalArgumentException("mount count exceeds u8: " + mounts.size());
        }
        int previousIndex = -1;
        for (Mount mount : mounts) {
            if (mount.mountIndex() <= previousIndex) {
                throw new IllegalArgumentException(
                        "mounts must ride sorted ascending by unique mountIndex; got "
                                + mount.mountIndex() + " after " + previousIndex);
            }
            previousIndex = mount.mountIndex();
        }
    }

    private static void requireU8(String label, int value) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException(label + " outside wire u8 [0,255]: " + value);
        }
    }

    private static void requireU16(String label, int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException(label + " outside wire u16 [0,65535]: " + value);
        }
    }

    private static void requireI16(String label, int value) {
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                    label + " outside wire i16 [-32768,32767]: " + value);
        }
    }
}
