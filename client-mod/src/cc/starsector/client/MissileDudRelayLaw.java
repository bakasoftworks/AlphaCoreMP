package cc.starsector.client;

/**
 * The local missile body mirrors the SERVER twin's dud verdict.
 *
 *
 * <p>WHY: vanilla rolls a missile's {@code armedWhileFizzling} ONCE at
 * birth ({@code Math.random() > dudProbabilityOnFlameout}, 0.5 for every
 * foe missile the player meets) and a burnt-out UNARMED missile takes the engine's physics bounce and
 * deals nothing ({@code isArmed}). The
 * client never showed that bounce: a pose puppet stayed un-fizzled until the wire FIZZLING relay and
 * then went intangible one fresh frame later (the fizzle-fade belt), and every local body kept its
 * OWN coin, never the twin's — one local-only burnt Hammer hit the server's twin never dealt. The
 * server now mints {@code POSE_FLAG_DUD} ({@link cc.starsector.client.net.ServerMissileState#dud()})
 * on FIZZLING frames = its engine's {@code !isArmed()}; these statics are the one law the relays
 * ({@link MissilePoseLane}, {@link OwnMissileHandoffLane}, {@link BallisticEndLinkLane}) and the
 * headless smoke share.
 *
 * <p>THE BIT IS {@code !isArmed()}, NOT THE COIN:
 * {@code isArmed() = forceAlwaysArmed || (elapsed >= armingTime && (!isFizzling() || armedWhileFizzling))}
 * — the ARMING CLOCK folds in. A body flamed out inside its {@code armingTime} (a 0.5 s Hammer / Reaper
 * / Atropos cut down by an EMP arc, any dud-probability) is minted DUD, then arms and the bit CLEARS;
 * the server re-mints every frame, so a relayed twin re-samples every fresh FIZZLING record and
 * re-applies only when the bit moved ({@link #rearmOnFresh}; the field write is idempotent).
 *
 * <p>ENGINE FACTS the law leans on:
 * {@code setArmedWhileFizzling(false)} is a NO-OP when the spec's {@code dudProbabilityOnFlameout <= 0}
 * (the server still mints DUD for such a spec while it fizzles inside its armingTime; the no-op keeps
 * own/bolt bodies armed = pre-existing, and a puppet goes NONE via the belt), {@code true} always lands;
 * {@code flameOut()} = {@code flightTime := maxFlightTime + 1}, idempotent on an
 * already-fizzling body ({@code timeSinceFizzling} is its own counter, and the
 * flameout FX branch runs only at {@code timeSinceFizzling == 0}). A missing DUD bit
 * = armed = the path from before the DUD bit; nothing here ever holds, delays or freezes a body.
 *
 * <p>Pure Java, no engine types; called once per twin on the relay latch, and per fresh pose frame
 * by the belt (O(1)).
 */
final class MissileDudRelayLaw {

    private MissileDudRelayLaw() {
    }

    /** {@link #beltAct}: leave the body as it is (not a fizzled, still-collidable body). */
    static final int BELT_SKIP = 0;
    /** {@link #beltAct}: an ARMED fading puppet — hold it at {@code CollisionClass.NONE} (today's
     *  fizzle-fade belt: the server's twin owns the hit, one owner). */
    static final int BELT_NONE = 1;
    /** {@link #beltAct}: an UNARMED (dud) fading puppet — keep it collidable so the ENGINE's own
     *  bounce plays; it deals nothing (vanilla {@code isArmed}). */
    static final int BELT_KEEP = 2;

    /** What the relay hands {@code setArmedWhileFizzling} BEFORE {@code flameOut()}: the server's
     *  verdict — armed unless the record is FIZZLING and carries DUD (an absent bit ⇒ armed). */
    static boolean armedOnRelay(boolean fizzling, boolean dud) {
        return !(fizzling && dud);
    }

    /** Whether a fresh record of an ALREADY-relayed twin ({@code fizzled})
     *  re-applies {@link #armedOnRelay} — only while the record is still FIZZLING and its DUD bit
     *  differs from the last one written ({@code dudApplied}); the latch path owns the first write, a
     *  non-fizzling record never re-arms, an unchanged bit costs no engine write. */
    static boolean rearmOnFresh(boolean fizzling, boolean fizzled, boolean dud, boolean dudApplied) {
        return fizzling && fizzled && dud != dudApplied;
    }

    /** The fizzle-fade belt's act for one fresh pose frame of a relayed twin: {@link #BELT_SKIP}
     *  unless the twin was relayed ({@code fizzled}), its body is fizzling and still collidable;
     *  then {@link #BELT_NONE} for an armed body, {@link #BELT_KEEP} for a dud. The caller passes the
     *  body's arming while fizzling ({@code isArmedWhileFizzling() || isForceAlwaysArmed()} — a
     *  force-armed body still pops, so it must never be KEPT collidable). */
    static int beltAct(boolean fizzled, boolean bodyFizzling, boolean collidable, boolean armedWhileFizzling) {
        if (!fizzled || !bodyFizzling || !collidable) {
            return BELT_SKIP;
        }
        return armedWhileFizzling ? BELT_NONE : BELT_KEEP;
    }

    /** Whether the no-victim FIZZLING END fallback ({@code BallisticEndLinkLane.flameOutDud}) still
     *  calls {@code flameOut()}: only for a bolt the served FIZZLING edge never reached — a relayed
     *  body is already fizzling and only gets DISARMED at its no-victim end. */
    static boolean consumeFlameOut(boolean alreadyRelayed) {
        return !alreadyRelayed;
    }
}
