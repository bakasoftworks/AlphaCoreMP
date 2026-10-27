package cc.starsector.client;

/**
 * The foe INTACT-end presentation DOOR law.
 * {@link SourcedMissileEndLane#presentImpactFxOwned} used to hand-paint
 * every foe INTACT end (hit particle + {@code spawnExplosion}); the engine's own detonation
 * ({@code MissileAPI.explode()}) now presents the SAFE subset, and every other end keeps today's
 * paint as a COUNTED, NAMED residue so a server-truth hit is never ABSENT (sync-gap doctrine).
 * Pure + engine-free: {@code FoeImpactDoorLawSmoke} rows every outcome and the order.
 *
 * <p>Engine truth: {@code explode()} with a NULL
 * {@code explosionSpec} and a non-{@code GuidedProximityFuseAI} AI draws ONLY the hit glow — and
 * only when {@code isUseHitGlowWhenDealingDamage()} AND {@code getExplosionRadius() > 0};
 * with an explosionSpec it spawns a {@code DamagingExplosion} that deals REAL local damage,
 * so that class stays the paint — except on the OWN ship's raised shield ({@link Door#PRESENT_OWN_SHIELD},
 * presented WITHOUT explode()). {@code explode()} does NOT remove the body (it holds no removal):
 * the caller's in-play-guarded retire / removeEntity that follows removes it.
 */
final class FoeImpactDoorLaw {

    enum Door {
        /** Pose-snap the twin to the server end pose, then the engine's own {@code explode()}. */
        EXPLODE,
        /** The local body dealt its hit natively on a
         *  DIFFERENT ship than the server-named victim, so the native glow sits on the wrong hull and
         *  the server-truth victim would be ABSENT. The {@link #EXPLODE} route presents it
         *  with the victim = the wire victim — the same engine {@code explode()}, never a paint,
         *  never a second damage. Only on the explode()-SAFE subset (no explosionSpec, glow on,
         *  radius > 0): {@code explode()} reads no in-play state (it
         *  draws via {@code CombatEngine.getInstance()}), so a
         *  body the native hit already consumed still presents. */
        EXPLODE_VICTIM_MISMATCH,
        /** The local body already dealt its hit ({@code didDamage()}): the engine presented it
         *  natively — present NOTHING (the double paint); counted + logged. A victim
         *  mismatch outside the safe subset stays here (counted; the still-ABSENT residue). */
        SKIP_NATIVE_DONE,
        /** No local body (the structural expiry caller): today's paint. */
        PAINT_RESIDUE_BODYLESS,
        /** The twin left play without a local hit (expiry/removal): no body to explode. */
        PAINT_RESIDUE_BODY_GONE,
        /** The spec carries an explosionSpec: {@code explode()} would blast the victim locally. */
        PAINT_RESIDUE_EXPLOSION_SPEC,
        /** The own-shield door: a foe
         *  missile the SERVER absorbed on the OWN ship's raised shield
         *  ({@code END_CAUSE_SHIELD_ABSORBED}, victim = the own ship) while the local body flew past. The
         *  caller PRESENTS it ({@code SourcedMissileEndLane.presentOwnShieldHit}): the direct hit through
         *  the engine's own {@code applyDamage} at the LOCAL ring contact, synchronous and flux-restored
         *  (the wire already booked the hit), plus the missile's OWN visual: an explosionSpec torpedo's
         *  blast with NO collision — never {@code explode()}, whose real DamagingExplosion lands a frame
         *  later as a second hit — or, for a spec with NO explosionSpec, the engine's hit glow via
         *  {@code explode()} (glow-only there). A local shield that is
         *  down or does not cover the bearing keeps today's door (the caller's contact is null).
         *  <p>The contact unlocks this door for EVERY spec, above
         *  the explosionSpec gate — the Locust class used to take {@link #EXPLODE} on the own shield = a
         *  bare glow at the ring with no engine shield hit: it exploded near the shield, not on it. */
        PRESENT_OWN_SHIELD,
        /** Hit glow off or radius 0: {@code explode()} would show nothing. */
        PAINT_RESIDUE_GLOW_OFF
    }

    private FoeImpactDoorLaw() {
    }

    /** Evaluated AFTER the caller's spec exclusions + wire pose ceiling; order is the law.
     *  {@code localVictimMismatch} = the local native hit's target resolved AND differs from the
     *  resolved wire victim (the caller's predicate); read only under {@code didDamage}. */
    static Door decide(boolean hasBody, boolean inPlay, boolean didDamage, boolean localVictimMismatch,
            boolean explosionSpecNull, boolean hitGlowFlag, float explosionRadius) {
        return decide(hasBody, inPlay, didDamage, localVictimMismatch, explosionSpecNull, hitGlowFlag,
                explosionRadius, false);
    }

    /** The same law with {@code ownShieldContact} = the caller resolved the wire
     *  victim to the OWN ship on an own-shield end AND found the local shield-ring contact (raised,
     *  covering the bearing, within slack). The contact unlocks
     *  {@link Door#PRESENT_OWN_SHIELD} for EVERY spec class — it sits right above the explosionSpec gate,
     *  below the native-hit and in-play gates (D21/D22 hold); with the contact false every row is
     *  byte-equal to the 7-arg form. */
    static Door decide(boolean hasBody, boolean inPlay, boolean didDamage, boolean localVictimMismatch,
            boolean explosionSpecNull, boolean hitGlowFlag, float explosionRadius, boolean ownShieldContact) {
        if (!hasBody) {
            return Door.PAINT_RESIDUE_BODYLESS;
        }
        if (didDamage) {
            // The explode()-safe gates bind here too — an
            // explosionSpec would blast a real DamagingExplosion (a second local damage), glow-off
            // would draw nothing yet count as presented (concealment).
            if (localVictimMismatch && explosionSpecNull && hitGlowFlag && explosionRadius > 0f) {
                return Door.EXPLODE_VICTIM_MISMATCH;
            }
            return Door.SKIP_NATIVE_DONE;
        }
        if (!inPlay) {
            return Door.PAINT_RESIDUE_BODY_GONE;
        }
        if (ownShieldContact) {
            // The own-shield present is the engine's own applyDamage at the local ring
            // + the spec's own visual (inert blast, else the glow-only explode()) — every spec class.
            return Door.PRESENT_OWN_SHIELD;
        }
        if (!explosionSpecNull) {
            return Door.PAINT_RESIDUE_EXPLOSION_SPEC;
        }
        if (!hitGlowFlag || !(explosionRadius > 0f)) {
            return Door.PAINT_RESIDUE_GLOW_OFF;
        }
        return Door.EXPLODE;
    }
}
