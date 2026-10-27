package cc.starsector.client;

import java.lang.ref.WeakReference;

/**
 * Fighter flame-out COLLISION-CLASS law: vanilla's "a fully flamed-out fighter collides as a SHIP"
 * rule applied to the SERVER's carried engine state.
 *
 *
 * <p>WHY: the engine's own {@code Ship.getCollisionClass()} returns
 * SHIP for a fighter whose LOCAL engine controller reads {@code isDisabled()}; a ship-fired RAY/MISSILE
 * shot then hits that same-side fighter instead of
 * passing through. The replica's local controller only reaches DISABLED after vanilla's local glow
 * ramp settles, so in one recorded match three server-side friendly
 * intercepts (fighters 36/302/389, every one at wire {@code ctrl=2}) were missed locally — the
 * replica stayed FIGHTER-class and the bolt flew through into the ship behind it (726.5 hp of
 * divergence). The SOURCE is the replica's collision state diverging from the server's; this law
 * writes the class the server's own getter reads, so the engine's own collision + damage path
 * reproduces the intercept (never-conceal; engine-faithful: no FX, no damage formula; no latency;
 * {@code engineControllerState()} is already on the wire).
 *
 * <p>Rules per remote fighter per FRESH snapshot ({@link #decide}): wire {@code ctrl==2} and the
 * local controller NOT disabled and the class reads FIGHTER-like (neither SHIP nor NONE) ⇒ remember
 * the prior class and set SHIP. Wire back to 0/1 ⇒ restore the prior class ONLY if this law set it
 * AND the class still reads SHIP; a class that reads NONE (landing / lift-off / phased — the engine's
 * own NONE rules) defers the restore; any other class means someone else rewrote the field ⇒ forget
 * the entry without writing (never clobber). A local-disabled replica under a non-flamed-out wire
 * (the reverse split, forced SHIP by the engine override whatever the field says) is COUNTED only —
 * the engine-state repair-edge path owns it.
 *
 * <p>Knob {@code cc.predict.fighterFlameoutClass} = {@code off|meter|apply}, default {@code meter}
 * (measure first): {@code meter} computes every decision and counts it as if written (a virtual
 * write, so sets/restores count EDGES exactly as {@code apply} would) but never writes. Client-only,
 * not a WireKnob. Game-free (class tokens are opaque {@code Object}s; the caller maps them to
 * {@code CollisionClass}) so {@code FighterFlameoutClassLawSmoke} drives it with no engine jars.
 *
 * <p>Cost: per fighter per fresh snapshot a handful of int/boolean compares; with no live
 * entry no table scan at all; with entries a scan of at most {@link #CAPACITY} longs. An entry whose
 * fighter died or was reaped while set is never dropped (no reap hook; {@code isAlive} is guarded at
 * the caller), so {@code live} stays &gt; 0 until {@link #claim} reuses the stalest slot — counted
 * {@code predict_fighter_class_evicted}. No allocation
 * on the step path (the memory is fixed parallel arrays; an edge LOG row builds one String, capped at
 * {@link #LOG_MAX}). Caller: {@code FoeStateLaneCoordinator.maintainRemoteEngineState}; meters ride
 * the PRCP {@code predict v6 engine-meter} line via {@link #meterTokens(Object)}.
 */
final class FighterFlameoutClassLaw {

    static final String KNOB = "cc.predict.fighterFlameoutClass";
    static final String MODE_NAME =
            PredictReconcileCombatPlugin.PredictLane.parseMode(KNOB, System.getProperty(KNOB, "meter"));
    static final int MODE_OFF = 0;
    static final int MODE_METER = 1;
    static final int MODE_APPLY = 2;
    static final int MODE = modeOf(MODE_NAME);

    /** No action. */
    static final int ACT_NONE = 0;
    /** Remember the prior class, set SHIP. */
    static final int ACT_SET = 1;
    /** Write the remembered prior back, drop the entry. */
    static final int ACT_RESTORE = 2;
    /** The field moved off our SHIP (someone else wrote it) — drop the entry, write nothing. */
    static final int ACT_FORGET = 3;

    /** {@link #step} result bit: the caller must write {@link #writeClass()}. */
    static final int R_WRITE = 1;
    /** {@link #step} result bit: the caller must emit {@link #edgeRow()} to its sink. */
    static final int R_LOG = 2;

    /** Remembered entries (simultaneously SHIP-set fighters); far above any seen flame-out burst. */
    static final int CAPACITY = 64;
    /** An entry untouched this long (30 s at 60 t/s) is evictable, only when the table is FULL. */
    static final long STALE_TICKS = 1800L;
    /** Edge-log row cap per owner (its own refused counter continues past it). */
    static final int LOG_MAX = 200;

    private static final FighterFlameoutClassLaw LIVE = new FighterFlameoutClassLaw(MODE);
    private static final FighterFlameoutClassLaw IDLE = new FighterFlameoutClassLaw(MODE);
    private static WeakReference<Object> liveOwner;

    private final int mode;
    private final long[] keys = new long[CAPACITY];
    private final int[] bodies = new int[CAPACITY];
    private final long[] touched = new long[CAPACITY];
    private final Object[] priors = new Object[CAPACITY];
    private final boolean[] used = new boolean[CAPACITY];
    private int live;

    long forkEntTicks;
    long sets;
    long restores;
    long forgets;
    long reverseSplitTicks;
    long memFull;
    long logRefused;
    long evicted; // a stale slot reused in place by claim
    private int logRows;

    private Object writeClass;
    private int edgeAct;
    private long edgeEntity;
    private long edgeTick;
    private int edgeWire;
    private int edgeLocal;
    private Object edgeCls;

    FighterFlameoutClassLaw(int mode) {
        this.mode = mode;
    }

    /** {@code off|meter|apply} (already validated by {@code PredictLane.parseMode}) to a MODE_* code. */
    static int modeOf(String name) {
        if ("apply".equals(name)) {
            return MODE_APPLY;
        }
        if ("meter".equals(name)) {
            return MODE_METER;
        }
        if ("off".equals(name)) {
            return MODE_OFF;
        }
        throw new IllegalArgumentException(KNOB + " must be off|meter|apply, got: " + name);
    }

    /** The knob parse (the shared {@code off|meter|apply} validator: anything else THROWS). */
    static int parseKnob(String raw) {
        return modeOf(PredictReconcileCombatPlugin.PredictLane.parseMode(KNOB, raw));
    }

    /**
     * The process's live law for {@code owner} (the PRCP instance): a different owner (a new combat
     * plugin) resets memory, counters and the log cap. One WeakReference per owner change, never per call.
     */
    static FighterFlameoutClassLaw live(Object owner) {
        WeakReference<Object> ref = liveOwner;
        if (ref == null || ref.get() != owner) {
            LIVE.reset();
            liveOwner = new WeakReference<Object>(owner);
        }
        return LIVE;
    }

    /** The meter tokens for {@code owner}; read-only (a flush never rebinds) — zeros for an unbound owner. */
    static String meterTokens(Object owner) {
        WeakReference<Object> ref = liveOwner;
        return (ref != null && ref.get() == owner ? LIVE : IDLE).tokens();
    }

    /**
     * The pure per-fresh-snapshot decision. {@code effShip}/{@code effNone} = the class as the engine's
     * getter reads it (meter mode folds the virtual write into {@code effShip}); {@code remembered} =
     * this law set SHIP on this very body and has not restored it.
     */
    static int decide(boolean isFighter, int wireCtrl, boolean localDisabled, boolean remembered,
            boolean effShip, boolean effNone) {
        if (!isFighter) {
            return ACT_NONE;
        }
        if (wireCtrl == 2) {
            if (localDisabled || effShip || effNone) {
                return ACT_NONE;
            }
            return ACT_SET;
        }
        if (wireCtrl != 0 && wireCtrl != 1) {
            return ACT_NONE; // UNKNOWN_ENGINE_STATE (-1) or an out-of-domain code: never act
        }
        if (!remembered || effNone) {
            return ACT_NONE; // not ours / deferred while the engine's NONE rule reads
        }
        return effShip ? ACT_RESTORE : ACT_FORGET;
    }

    /**
     * One remote fighter on one fresh snapshot. {@code cls} = {@code ship.getCollisionClass()};
     * {@code shipCls}/{@code noneCls} = the SHIP/NONE tokens; {@code bodyKey} = the body's identity
     * hash (a reused wire id on a NEW body never inherits an entry). Returns {@link #R_WRITE} |
     * {@link #R_LOG} bits; counts every meter.
     */
    int step(boolean isFighter, long entityId, int bodyKey, int wireCtrl, boolean localDisabled,
            boolean localFlamingOut, Object cls, Object shipCls, Object noneCls, long simTick) {
        if (mode == MODE_OFF || !isFighter) {
            return 0;
        }
        if (wireCtrl == 2) {
            if (!localDisabled) {
                forkEntTicks++;
            }
        } else if ((wireCtrl == 0 || wireCtrl == 1) && localDisabled) {
            reverseSplitTicks++;
        }
        int slot = live == 0 ? -1 : find(entityId);
        if (slot >= 0 && bodies[slot] != bodyKey) {
            drop(slot); // a previous body at this wire id — never ours to restore on the new one
            forgets++;
            slot = -1;
        }
        boolean remembered = slot >= 0;
        if (remembered) {
            touched[slot] = simTick;
        }
        boolean realShip = cls == shipCls;
        boolean realNone = cls == noneCls;
        boolean effShip = realShip || (mode == MODE_METER && remembered && !realNone);
        int act = decide(true, wireCtrl, localDisabled, remembered, effShip, realNone);
        int out = 0;
        if (act == ACT_SET) {
            if (slot < 0) {
                slot = claim(simTick);
                if (slot < 0) {
                    memFull++; // cannot remember ⇒ never write (a set we could not restore)
                    return 0;
                }
                keys[slot] = entityId;
                bodies[slot] = bodyKey;
                touched[slot] = simTick;
            }
            priors[slot] = cls;
            sets++;
            writeClass = shipCls;
            out = mode == MODE_APPLY ? R_WRITE : 0;
        } else if (act == ACT_RESTORE) {
            writeClass = priors[slot];
            drop(slot);
            restores++;
            out = mode == MODE_APPLY ? R_WRITE : 0;
        } else if (act == ACT_FORGET) {
            drop(slot);
            forgets++;
        } else {
            return 0;
        }
        if (logRows < LOG_MAX) {
            logRows++;
            edgeAct = act;
            edgeEntity = entityId;
            edgeTick = simTick;
            edgeWire = wireCtrl;
            edgeLocal = localFlamingOut ? 1 : (localDisabled ? 2 : 0);
            edgeCls = cls;
            out |= R_LOG;
        } else {
            logRefused++;
        }
        return out;
    }

    /** The class the caller writes on {@link #R_WRITE} (SHIP on a set, the remembered prior on a restore). */
    Object writeClass() {
        return writeClass;
    }

    /** The last edge as one bounded row (enum names, ids, counts — no player text). */
    String edgeRow() {
        String act = edgeAct == ACT_SET ? "SET" : edgeAct == ACT_RESTORE ? "RESTORE" : "FORGET";
        return "predict fighter-class " + act + " @tick=" + edgeTick + ": entity=" + edgeEntity
                + " wire=" + edgeWire + " local=" + edgeLocal + " cls=" + edgeCls
                + (edgeAct == ACT_FORGET ? "" : " write=" + writeClass) + " mode=" + nameOf(mode);
    }

    /** Remembered entries right now. */
    int live() {
        return live;
    }

    String tokens() {
        return " fighter_class_mode=" + nameOf(mode)
                + " predict_fighter_ctrl_fork_ent_ticks=" + forkEntTicks
                + " predict_fighter_class_sets=" + sets
                + " predict_fighter_class_restores=" + restores
                + " predict_fighter_reverse_split_ticks=" + reverseSplitTicks
                + " predict_fighter_class_forgets=" + forgets
                + " predict_fighter_class_mem_full=" + memFull
                + " predict_fighter_class_evicted=" + evicted
                + " predict_fighter_class_log_refused=" + logRefused;
    }

    private static String nameOf(int mode) {
        return mode == MODE_APPLY ? "apply" : mode == MODE_METER ? "meter" : "off";
    }

    private void reset() {
        java.util.Arrays.fill(used, false);
        java.util.Arrays.fill(priors, null);
        live = 0;
        forkEntTicks = 0;
        sets = 0;
        restores = 0;
        forgets = 0;
        reverseSplitTicks = 0;
        memFull = 0;
        logRefused = 0;
        evicted = 0; // per-combat like its siblings
        logRows = 0;
        writeClass = null;
        edgeCls = null;
    }

    private int find(long entityId) {
        for (int i = 0; i < CAPACITY; i++) {
            if (used[i] && keys[i] == entityId) {
                return i;
            }
        }
        return -1;
    }

    /** A free slot, else the stalest entry past {@link #STALE_TICKS} (a clock that went backward counts), else -1. */
    private int claim(long simTick) {
        int stalest = -1;
        for (int i = 0; i < CAPACITY; i++) {
            if (!used[i]) {
                used[i] = true;
                live++;
                return i;
            }
            long age = simTick - touched[i];
            if ((age > STALE_TICKS || age < 0) && (stalest < 0 || touched[i] < touched[stalest])) {
                stalest = i;
            }
        }
        if (stalest >= 0) {
            evicted++; // the memory drop is counted
        }
        return stalest; // reused in place: used stays true, live unchanged
    }

    private void drop(int slot) {
        used[slot] = false;
        priors[slot] = null;
        live--;
    }
}
