package cc.starsector.server.protocol;

/**
 * The v1 taxonomy of tick-stamped gameplay edge events:
 * the stateful-window/discharge
 * facts a client must replay at the exact server tick instead of inferring from
 * sampled snapshot tiers. Beams/charge-up weapons and shield on/off are
 * explicitly out of v1 scope (the taxonomy is extensible; the demo loadout is
 * the ballistic Hellbore).
 */
public enum GameplayEdgeEventKind {

    /** A weapon mount discharged during the stamped tick (one-shot). */
    WEAPON_DISCHARGE(1),

    /** The engine's own accepted vent window opened/closed (the {@code venting} edge). */
    VENT_WINDOW(2),

    /** The primary ship system's engine-accepted activation toggled (the {@code isActive} edge). */
    SYSTEM_WINDOW(3),

    /**
     * A teleport-class primary system JUMPED this tick (schema v3): the
     * server-side state edge {@code prev=IN → now∈{ACTIVE,OUT}} on a hull whose system id
     * is in the teleport class ({@code GameplayEdgeEventDetector.TELEPORT_CLASS_SYSTEM_IDS}).
     * Payload: the two float slots carry the authoritative post-jump destination
     * ({@code destX}/{@code destY} — same-tick post-step pose), both REQUIRED finite.
     * Appended LAST: within-tick canonical order is enum order, and the wire code is
     * v3-only ({@code ServerGameplayEdgeEventMessage.SYSTEM_JUMP_MIN_SCHEMA}).
     */
    SYSTEM_JUMP(4),

    /**
     * A MIRV-class missile SPLIT this tick (schema v4): the parent
     * missile's stable network id left the snapshot's non-ship missile roster on the same
     * tick ≥1 new missile stable id was born within the split radius of its last position,
     * and the parent's content spec is MIRV-behaviored ({@code isMirv()}). Payload: the two
     * float slots carry the parent's last authoritative position ({@code splitX}/{@code splitY}),
     * both REQUIRED finite. {@code entityId} = the PARENT MISSILE network id (the v13a
     * stable-id partition ≥ 1e9 — never a ship id). Appended LAST: within-tick canonical
     * order is enum order, and the wire code is v4-only
     * ({@code ServerGameplayEdgeEventMessage.MISSILE_SPLIT_MIN_SCHEMA}).
     */
    MISSILE_SPLIT(5),

    /**
     * A SOURCELESS missile ENDED this tick (schema v5): its stable
     * network id left the snapshot's non-ship missile roster and the binding's post-mortem
     * end-cause triage read it. Payload: {@code mountIndex} carries the end-cause wire code
     * ({@code GameplayEdgeEvent.END_CAUSE_*}: 0=DESTROYED hp≤0, 1=FIZZLING, 2=INTACT clean
     * {@code removeObject} — an EOL mine self-destruct-detonates and delists INTACT
     * ({@code GuidedProximityFuseAI}); 3=SHIELD_ABSORBED, schema v15 = INTACT
     * whose last engine impact the victim's shield absorbed); the two float slots carry the
     * missile's last authoritative position ({@code endX}/{@code endY}), both REQUIRED finite;
     * since v15 {@code targetWireId} names that last impact's VICTIM ship or stays ABSENT.
     * {@code entityId} = the MISSILE network id (≥ 1e9). Scope since schema v7 is EVERY
     * vanished missile, SOURCED included (the
     * {@code MissileSplitEventDetector} emits per delisted id; client consumers: the sourced
     * twin retire + the lever C skip-set link/removal); v5-v6 peers saw sourceless
     * records only (mine/system-spawn family). Wire code min-schema v5
     * ({@code ServerGameplayEdgeEventMessage.MISSILE_END_MIN_SCHEMA}); sourced records gate
     * at v7 ({@code MISSILE_END_SOURCED_MIN_SCHEMA}).
     */
    MISSILE_END(6),

    /**
     * The server's engine-accepted AUTOFIRE TARGET PICK for one weapon mount changed this
     * tick (schema v9): the authoritative autofire plugin on
     * {@code (entityId=firer, mountIndex)} now tracks the SHIP whose stable network id is
     * {@code targetWireId} — or cleared its pick ({@code GameplayEdgeEvent.TARGET_NONE}).
     * Emitted on pick-CHANGE (including →null) plus a slow keepalive re-emit (~60 ticks)
     * while a non-null pick is held, so UDP loss staleness is bounded by the lane's own
     * transport. Scope = SEAT ships only (the client's own-ship autofire mounts are the
     * ones whose local pick can diverge from the server's — measured: >45° strict
     * pairs were 15/15 different-ship). Payload: the five float slots are canonical
     * {@code NaN}; {@code targetWireId} rides the v9 frame-tail long. Appended LAST:
     * within-tick canonical order is enum order, and the wire code is v9-only
     * ({@code ServerGameplayEdgeEventMessage.AUTOFIRE_TARGET_MIN_SCHEMA}).
     */
    AUTOFIRE_TARGET(7),

    /**
     * A Paladin-PD script detonation was BORN server-side this tick
     * (schema v10): the engine's {@code PaladinEffect} spawned a
     * {@code DamagingExplosion} whose immutable spec fingerprints as the Paladin det
     * (radius 75 / maxDamage 100 / FRAGMENTATION — {@code PaladinEffect.createExplosionSpec},
     * whitelist law: any other new script det is counted+skipped, never guessed). The det
     * never crosses classifiably otherwise: it rides the replaceable snapshot lane as a
     * content-LESS point row, and the replica-side local gate can never open
     * ({@code CollisionClass.NONE} ⇒ {@code getDamageTarget()==null}).
     * Payload: {@code entityId} = the SOURCE SHIP network id; {@code mountIndex} = the
     * firing mount ordinal or {@code NO_MOUNT} (script dets carry no weapon —
     * {@code getWeapon()==null} — so {@code NO_MOUNT} is the live case); the two float
     * slots carry the det position ({@code detX}/{@code detY}), both REQUIRED finite
     * (the producer always read the birth pose). Client consumption is damage-LESS FX
     * only (hull/flux/armor stay wire-authoritative).
     * Appended LAST: within-tick canonical order is enum order, and the wire code is
     * v10-only ({@code ServerGameplayEdgeEventMessage.PALADIN_DET_MIN_SCHEMA}).
     */
    PALADIN_DET(8),

    /**
     * An EMP arc DAMAGED a ship server-side this tick (schema v13): the
     * engine's {@code EmpArcEntity.doDamage} (its FIRST advance after the OnFire spawn)
     * ran {@code Ship.applyDamage}, whose {@code CombatListenerUtil.reportDamageApplied}
     * the server's global {@code DamageListener} probe captured with the arc still readable
     * as {@code target.getParamAboutToApplyDamage()}. The client's local wrapper used to
     * re-run vanilla {@code findTarget} (an INVENTED target) and print the NOMINAL damage
     * (D-INIMPHASEDMG / D-INIMDMGNUM); now the server names the hit.
     * Payload: {@code entityId} = the SOURCE ship network id; {@code targetWireId} = the
     * VICTIM ship network id (REQUIRED {@code > 0}); {@code mountIndex} = the arc FLAVOR
     * code ({@code ARC_FLAVOR_OTHER/INIMICAL/VOLTAIC}); the two float slots carry the arc's
     * node world point ({@code hitX}/{@code hitY}, both REQUIRED finite); the remaining
     * three floats carry the applied split ({@code dmgArmor}/{@code dmgHull}/{@code dmgShield})
     * under the shield pair law (hull hit: armor+hull finite, shield NaN; shield hit: shield
     * finite, armor+hull NaN). Client consumption is presentation only (arc visual + the
     * vanilla-shaped floaty; hull/flux/armor stay wire-authoritative).
     * Appended LAST: within-tick canonical order is enum order, and the wire code is
     * v13-only ({@code ServerGameplayEdgeEventMessage.ARC_HIT_MIN_SCHEMA}).
     */
    ARC_HIT(9),

    /**
     * A CRITICAL MALFUNCTION struck a ship server-side this tick (schema v14):
     * the engine's nested crit roll
     * asked the {@code CombatReadinessPlugin}
     * singleton {@code isOkToPermanentlyDisable(ship, module)} — the one
     * engine-native seam naming the module — and then ran its own
     * {@code Ship.applyCriticalMalfunction} on the authoritative body; the server's
     * {@code CritMalfunctionServeProbe} captured it there. Payload: {@code entityId} = the
     * VICTIM ship network id; {@code targetWireId} = {@code TARGET_ABSENT}; {@code mountIndex}
     * = module code (weapon ordinal 0..255, or {@code CRIT_MODULE_ENGINE_BASE} + engine
     * ordinal 0..31); {@code aimFacingDeg} = permanent flag 0/1; {@code flightBearingDeg} =
     * branch code (DIRECT/DAMAGE/LETHAL); {@code birthSpeed} = hull amount (finite, 0..1e6);
     * muzzle pair NaN. DOWN-only; client replays vanilla's own crit script, never a second roll.
     * Appended LAST: within-tick canonical order is enum order, and the wire code is
     * v14-only ({@code ServerGameplayEdgeEventMessage.CRIT_MALFUNCTION_MIN_SCHEMA}).
     */
    CRIT_MALFUNCTION(10);

    private final int wireCode;

    GameplayEdgeEventKind(int wireCode) {
        this.wireCode = wireCode;
    }

    /** Stable on-wire code for this kind (never the ordinal). */
    public int wireCode() {
        return wireCode;
    }

    /** Resolve a kind from its {@link #wireCode()}; fail-closed on an unknown code. */
    public static GameplayEdgeEventKind fromWireCode(int wireCode) {
        for (GameplayEdgeEventKind kind : values()) {
            if (kind.wireCode == wireCode) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown gameplay edge event kind wire code: " + wireCode);
    }
}
