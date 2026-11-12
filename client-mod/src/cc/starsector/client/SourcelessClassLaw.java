package cc.starsector.client;

/**
 * The SOURCELESS CLASS law — pure statics, no imports.
 *
 * <p>WHY: a missile body adopted with no weapon mount on the wire (the sourceless pair) is sorted into ONE class
 * at adopt — a script bomb, a canister round, a drone-strike conversion, a mote, a fuse-burst bomb, a native mine,
 * a mine whose engine fuse a mod replaced, a prox charge, an unstable fragment, or plain ordnance — and that class
 * decides its spawn dressing, whether its body is intangible, and which END presentation owns it. The sort used to
 * be a chain of inline booleans at the adopt, and three end gates in two other classes read the flag that chain
 * wrote ({@code Twin.sourcelessMine}). Nothing pinned the routing: a small edit to that flag once silently dropped a
 * swarmer's own-hull damage relay and sent a prox charge through the engine door on the engine's stand-in source,
 * with every build green. Each decision is now ONE pure function of named inputs here, and one row table
 * ({@code SourcelessClassLawSmoke}) pins and prints the whole routing.
 *
 * <p>Only a real mine is a mine: plain ordnance with no mount on the wire (a fighter's flare, a swarmer or a torpedo
 * whose launcher died) takes neither the minefield spawn dressing nor a mine det at its END.
 *
 * <p>The decisions routed through this class: the adopt's intangible / dressing / {@code sourcelessMine} /
 * {@code sourcelessClass} writes ({@code MissilePoseLane.adoptSourceless}), the mine END arm
 * ({@code MineBombEndFxLane.presentMineEndFx}), the plain END ({@code MineBombEndFxLane.presentPlainEnd}), the bomb
 * END guard and its stand-in paint route ({@code MineBombEndFxLane.presentBombEndFx}) and the same-tick serve gate
 * ({@code SourcedMissileEndLane.consumeTwin}).
 *
 * <p>The OTHER readers of {@code Twin.sourcelessMine}, NOT routed through this class (a change to the flag's
 * meaning must check them too): {@code MissilePoseLane.laneFor}, the own-twin absence arm of
 * {@code MissilePoseLane.tickAbsence}, {@code MineBombEndFxLane.trackMinePrime}, the own-twin arm of
 * {@code SourcedMissileEndLane.consumeTwin} and {@code SourcedMissileEndLane.ownPuppetImpactDoor}. The second
 * WRITER of the flag is {@code OwnMissileHandoffLane.enrollOwnMine}.
 *
 * <p>LOADING LIMIT: no imports and no static state beyond {@code static final int} constants — every knob and
 * every engine read arrives as a parameter, so the row table loads this class with nothing else on its path.
 */
final class SourcelessClassLaw {

    private SourcelessClassLaw() {
    }

    /** The classes ({@link #classOf}). NOT_SOURCELESS is never returned by the sort; it names "no class".
     *  MINE_PAINTED = a PHASE_MINE spec whose body did NOT keep the engine's mine fuse (a mod's AI override);
     *  PROX_CHARGE = a fuse-det spec that is not a script bomb; UNSTABLE = the Threat unstable fragment. */
    static final int NOT_SOURCELESS = 0, SCRIPT_BOMB = 1, CANISTER = 2, CONVERSION = 3, MOTE = 4, FUSE_FAMILY = 5,
            MINE = 6, PLAIN = 7, MINE_PAINTED = 8, PROX_CHARGE = 9, UNSTABLE = 10;

    /** Slots for a per-class array indexed by class (0 = NOT_SOURCELESS, unused). */
    static final int CLASS_SLOTS = UNSTABLE + 1;

    /** The adopt's spawn dressing ({@link #dressing}). */
    static final int DRESS_NONE = 0, DRESS_SCRIPT_FADE = 1, DRESS_CANISTER = 2, DRESS_MINE_SPAWN = 3;

    /** The mine END arms ({@link #mineEndArm}). */
    static final int MINE_END_NONE = 0, MINE_END_QUIET = 1, MINE_END_ENGINE_DOOR = 2, MINE_END_PAINTED = 3;

    /** The class at adopt: the first true input wins, in the order scriptBomb, canister, conversion, mote,
     *  fuseFamily, nativeMine, specMine, fuseDetSpec, unstableSpec (so a script bomb beats the fuse-det test);
     *  none true = PLAIN. Pure (rows T0, T1-T54). */
    static int classOf(boolean scriptBomb, boolean canister, boolean conversion, boolean mote,
            boolean fuseFamily, boolean nativeMine, boolean specMine, boolean fuseDetSpec, boolean unstableSpec) {
        if (scriptBomb) {
            return SCRIPT_BOMB;
        }
        if (canister) {
            return CANISTER;
        }
        if (conversion) {
            return CONVERSION;
        }
        if (mote) {
            return MOTE;
        }
        if (fuseFamily) {
            return FUSE_FAMILY;
        }
        if (nativeMine) {
            return MINE;
        }
        if (specMine) {
            return MINE_PAINTED;
        }
        if (fuseDetSpec) {
            return PROX_CHARGE;
        }
        if (unstableSpec) {
            return UNSTABLE;
        }
        return PLAIN;
    }

    /** A bounded log / table / meter token for a class; anything unknown (NOT_SOURCELESS included) = "none". Pure
     *  (rows T1-T54). */
    static String className(int cls) {
        switch (cls) {
            case SCRIPT_BOMB: return "script_bomb";
            case CANISTER: return "canister";
            case CONVERSION: return "conversion";
            case MOTE: return "mote";
            case FUSE_FAMILY: return "fuse";
            case MINE: return "mine";
            case PLAIN: return "plain";
            case MINE_PAINTED: return "mine_painted";
            case PROX_CHARGE: return "prox_charge";
            case UNSTABLE: return "unstable";
            default: return "none";
        }
    }

    /** Collision NONE at adopt: the station-mine knob, or a script bomb / canister / conversion (each must
     *  survive to its wire end without a local collision pruning it first). Pure (rows T0, T1-T54). */
    static boolean intangible(int cls, boolean stationMineIntangibleKnob) {
        return stationMineIntangibleKnob || cls == SCRIPT_BOMB || cls == CANISTER || cls == CONVERSION;
    }

    /** The spawn dressing: a script bomb fades in, a canister plays its fire sound, a mine (native or painted)
     *  takes the minefield spawn fade + sound when the mine FX knob is on, else none — plain ordnance, a prox
     *  charge and an unstable fragment take none. Pure (rows T0, T1-T54). */
    static int dressing(int cls, boolean mineFx) {
        if (cls == SCRIPT_BOMB) {
            return DRESS_SCRIPT_FADE;
        }
        if (cls == CANISTER) {
            return DRESS_CANISTER;
        }
        if ((cls == MINE || cls == MINE_PAINTED) && mineFx) {
            return DRESS_MINE_SPAWN;
        }
        return DRESS_NONE;
    }

    /** The {@code Twin.sourcelessMine} flag: a real mine only (MINE or MINE_PAINTED). PLAIN, PROX_CHARGE and
     *  UNSTABLE leave it, so no ordinary sourceless body takes the mine det at its END. Pure (rows T0, T1-T54). */
    static boolean sourcelessMine(int cls) {
        return cls == MINE || cls == MINE_PAINTED;
    }

    /** A wire END is served on its own tick when the body names a source hull, when it is a sourceless
     *  fuse-family burst, or when it is a foe unstable fragment's det ({@link #unstableDetEnd}); every other end
     *  waits for the absence confirm. Pure (rows T1-T54). */
    static boolean servedSameTick(boolean hasSourceHull, boolean sourcelessBurst, boolean unstableDet) {
        return hasSourceHull || sourcelessBurst || unstableDet;
    }

    /** A foe unstable fragment adopted sourceless whose wire END is its det (an INTACT-class or FIZZLING end):
     *  served on its own tick, where the unstable presenter paints the blast and relays its ONE own-hull hit. A
     *  shot-down (DESTROYED) fragment never dets in vanilla; an own twin keeps the own arms. Pure (rows T37-T39). */
    static boolean unstableDetEnd(int cls, boolean own, boolean intactClass, boolean fizzling) {
        return cls == UNSTABLE && !own && (intactClass || fizzling);
    }

    /** A fuse-det body (prox charge or script bomb) minted on the engine's stand-in source never takes the engine
     *  door: {@code getSource()} is non-null there (the engine substitutes its own ship)
     * but wrong-sided, and the door's blast would deal local damage the
     *  server never dealt. It paints instead. Pure (rows T13-T24, B5). */
    static boolean fuseDetEndPainted(boolean fuseDetSpec, boolean mintSourceNull) {
        return fuseDetSpec && mintSourceNull;
    }

    /** The plain END's relay: an ordinary sourceless body whose spec owns a plain-END damage relay (the swarmer),
     *  not fizzled and not shot down, relays its own-hull damage number ONCE — no explosion, no sound. Pure (rows
     *  T40-T42). */
    static boolean plainRelays(boolean apply, int cls, boolean relaySpec, boolean fizzled, boolean wireDestroyed) {
        return apply && cls == PLAIN && relaySpec && !fizzled && !wireDestroyed;
    }

    /** The plain quiet END: every other ordinary sourceless body whose non-fizzled, non-shot-down END has a wire
     *  record draws nothing (a meter only). Pure (rows T43-T51). */
    static boolean plainQuietEnd(boolean apply, int cls, boolean relaySpec, boolean fizzled, boolean hasWireEnd,
            boolean wireDestroyed) {
        return apply && cls == PLAIN && !relaySpec && !fizzled && hasWireEnd && !wireDestroyed;
    }

    /** The mine END arm: a knob off, a non-mine flag or an already-fizzled twin = NONE; a wire DESTROYED end =
     *  QUIET (the shot-down death rode its own arc); a native mine = the ENGINE DOOR (its own explode()); else
     *  the PAINTED det (reached by MINE_PAINTED alone). Pure (rows T1-T54). */
    static int mineEndArm(boolean mineFx, boolean apply, boolean sourcelessMine, boolean fizzled,
            boolean wireDestroyed, boolean nativeMine) {
        if (!mineFx || !apply || !sourcelessMine || fizzled) {
            return MINE_END_NONE;
        }
        if (wireDestroyed) {
            return MINE_END_QUIET;
        }
        if (nativeMine) {
            return MINE_END_ENGINE_DOOR;
        }
        return MINE_END_PAINTED;
    }

    /** The bomb END guard: the knobs on, NOT a sourceless mine, and a fuse-det spec (a sourceless prox charge
     *  passes: it is no mine). Pure (rows T1-T54). */
    static boolean bombEndAdmits(boolean bombEndFx, boolean apply, boolean sourcelessMine, boolean fuseDetSpec) {
        return bombEndFx && apply && !sourcelessMine && fuseDetSpec;
    }
}
