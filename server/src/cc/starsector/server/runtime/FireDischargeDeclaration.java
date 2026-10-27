package cc.starsector.server.runtime;

/**
 * One declared own-gun discharge from the predicting client — the declared-discharge fire
 * lane's wire payload ({@code client.fire_discharge.declared},
 * {@link cc.starsector.server.protocol.ClientFireDischargeDeclarationMessage}). Emitted by the
 * client's projectile-scan detector the frame its OWN engine actually discharged; the server
 * issues the same aimed fire command ({@code USE_SELECTED_GROUP} — FIRE's
 * engine body plus the engine's own noFiring refusal) until its engine serves it (consume on the
 * server's own observed own-ship discharge), so the trigger-level sampling misses of the held lane
 * cannot occur in either direction.
 *
 * <p>{@code declSeq} is monotonic per client session (a fresh session restarts at 0);
 * {@code clientTick} is the client's last-known authoritative sim tick at the discharge — a
 * logging/matching stamp only, never a serve condition. {@code aimFacingDeg} is the client's aim
 * facing AT the discharge instant, so a served shot points where the human's shot pointed even if
 * the aim moved while the declaration crossed the wire. {@code aimWorldX}/{@code aimWorldY} (v2)
 * carry the aim WORLD POINT at the same instant ({@code getMouseTarget()}), so the served
 * fire command aims at the player's TRUE point — exact under latency — instead of a
 * fixed-range projection of the bearing.
 *
 * <p>{@code mountIndex} (v3): the discharging weapon's ordinal in the ship's
 * {@code getAllWeapons()} list — the SAME cross-side order contract the AWSS v4 weapon lane
 * live-proved (disagree=0). The serve path resolves mount→group
 * ({@code getWeaponGroupFor}) and SELECT_GROUPs to it for the serve window; consume pairing is
 * MOUNT-IDENTITY (both mounts known ⇒ consume only the exact mount —
 * {@code PilotApplyState.dischargeConsumesHead}). Sentinel {@code -1} =
 * UNKNOWN: legacy serve (no SELECT_GROUP; consume on any own-ship discharge) — use {@link #of}
 * for producers with no real mount identity.
 *
 * <p>{@code muzzleX}/{@code muzzleY}/{@code bearingDeg}/{@code speed} (v4):
 * the committed projectile's OWN ray at the client's discharge instant — spawn point, flight
 * bearing (degrees, from the projectile's velocity; facing fallback for near-zero speed), and
 * speed — so the server can serve EXACTLY the shot the client's engine emitted (named-mount
 * force-fire + newborn re-pose) instead of guessing via a group-level FIRE press. Ray law
 * (fail-closed, the ctor law): each ray field is finite or NaN (±Inf always rejected);
 * EITHER all four are finite — which requires a real {@code mountIndex >= 0} (a ray without mount
 * identity is malformed) — OR all four are NaN (no carried ray: v3-era producers, failed client
 * capture, legacy serve). {@link #hasCarriedRay} is the discriminator the serve path branches on.
 */
public record FireDischargeDeclaration(
        long shipId,
        int declSeq,
        long clientTick,
        float aimFacingDeg,
        float aimWorldX,
        float aimWorldY,
        int mountIndex,
        float muzzleX,
        float muzzleY,
        float bearingDeg,
        float speed) {

    public FireDischargeDeclaration {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (declSeq < 0) {
            throw new IllegalArgumentException("declSeq must be >= 0; got " + declSeq);
        }
        if (clientTick < 0L) {
            throw new IllegalArgumentException("clientTick must be >= 0; got " + clientTick);
        }
        if (!Float.isFinite(aimFacingDeg)) {
            throw new IllegalArgumentException("aimFacingDeg must be finite; got " + aimFacingDeg);
        }
        if (!Float.isFinite(aimWorldX)) {
            throw new IllegalArgumentException("aimWorldX must be finite; got " + aimWorldX);
        }
        if (!Float.isFinite(aimWorldY)) {
            throw new IllegalArgumentException("aimWorldY must be finite; got " + aimWorldY);
        }
        if (mountIndex < -1) {
            throw new IllegalArgumentException(
                    "mountIndex must be >= -1 (-1 = unknown); got " + mountIndex);
        }
        // v4 ray law: each ray field finite-or-NaN (±Inf REJECT); all four
        // finite (requires a real mount) or all four NaN — a partial/mixed ray is malformed.
        requireFiniteOrNaN("muzzleX", muzzleX);
        requireFiniteOrNaN("muzzleY", muzzleY);
        requireFiniteOrNaN("bearingDeg", bearingDeg);
        requireFiniteOrNaN("speed", speed);
        boolean anyFinite = !Float.isNaN(muzzleX) || !Float.isNaN(muzzleY)
                || !Float.isNaN(bearingDeg) || !Float.isNaN(speed);
        boolean allFinite = !Float.isNaN(muzzleX) && !Float.isNaN(muzzleY)
                && !Float.isNaN(bearingDeg) && !Float.isNaN(speed);
        if (anyFinite && !allFinite) {
            throw new IllegalArgumentException("carried ray must be all-finite or all-NaN; got"
                    + " muzzle=(" + muzzleX + "," + muzzleY + ") bearingDeg=" + bearingDeg
                    + " speed=" + speed);
        }
        if (allFinite && mountIndex < 0) {
            throw new IllegalArgumentException(
                    "carried ray requires a real mountIndex (>= 0); got " + mountIndex);
        }
    }

    private static void requireFiniteOrNaN(String name, float v) {
        if (Float.isInfinite(v)) {
            throw new IllegalArgumentException(name + " must be finite or NaN; got " + v);
        }
    }

    /** v4: whether this declaration carries the committed shot's ray. */
    public boolean hasCarriedRay() {
        return !Float.isNaN(muzzleX); // ctor law: all-finite or all-NaN — one probe decides
    }

    /**
     * Legacy/synthetic-producer factory — the pre-v3 6-arg shape with {@code mountIndex} defaulted
     * to {@code -1} (UNKNOWN: today's serve path verbatim — no SELECT_GROUP, consume on any
     * own-ship discharge) and no carried ray. The live client detector passes the real mount
     * ordinal + ray via the canonical constructor.
     */
    public static FireDischargeDeclaration of(
            long shipId,
            int declSeq,
            long clientTick,
            float aimFacingDeg,
            float aimWorldX,
            float aimWorldY) {
        return new FireDischargeDeclaration(
                shipId, declSeq, clientTick, aimFacingDeg, aimWorldX, aimWorldY, -1);
    }

    /**
     * v3-shape compatibility constructor — the 7-arg form every pre-v4 caller
     * used, delegating with an all-NaN ray so mount-known-but-ray-less producers (contract rows,
     * failed client ray capture) keep compiling and keep the press-path serve.
     */
    public FireDischargeDeclaration(
            long shipId,
            int declSeq,
            long clientTick,
            float aimFacingDeg,
            float aimWorldX,
            float aimWorldY,
            int mountIndex) {
        this(shipId, declSeq, clientTick, aimFacingDeg, aimWorldX, aimWorldY, mountIndex,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN);
    }
}
