package cc.starsector.client;

import cc.starsector.client.net.ServerEntityPose;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import java.util.ArrayDeque;

/**
 * The press-shadow DIAGNOSTIC probe (knobless, hard-capped): the instrument that
 * shadows the foe-shield lane's counted TOGGLE presses — a SAME-frame line at the press (the
 * identity triple + the live engine-internal commands/blockedCommands/holdFire reads) plus a NEXT-frame
 * post-advance line — to name what eats ~99.5% of those presses between
 * {@code foe.giveCommand(...)} and the next frame's dedup filter. DIAGNOSTIC ONLY — no fix rides
 * it (the diagnosis-first law: no shield fix mint until the eater is named). Owns the probe's state (the run/entity
 * sample caps + counters, the pending deque, the once-per-runtime-class engine MethodHandles) and
 * every sampling/dump body.
 * Extracted VERBATIM from {@link PredictReconcileCombatPlugin} at the 9500-nb HARD-BACKSTOP fire
 * (the deferred-refactors PRCP cohesion seam): method
 * bodies byte-identical modulo receivers and the public-surface method renames. PRCP keeps the
 * touchpoints only — the shield-lane press pair ({@link #shouldSample} / {@link #commandsSize}
 * then {@link #samplePress}), the advance() {@link #flush}, the round-boundary
 * {@link #resetRound}, and the {@link #sampledCount} read on the foe-shield meter line.
 * Threading contract unchanged by the move: render-thread only.
 */
final class PressShadowProbe {

    /**
     * The owning plugin — the supplier of the mutable cross-lane state the moved bodies read (the
     * live {@code engine} ship list for the identity scan, the remote-entity registry row, and the
     * parity walk's {@code foeShip} subject), per the FireLaneCoordinator coupling model. The
     * constructor stores the backref and does nothing else: the PRCP field initializer that mints
     * this probe leaks {@code this} mid-construction exactly as the sibling collaborators do.
     */
    private final PredictReconcileCombatPlugin plugin;

    // press-shadow DIAGNOSTIC (knobless, hard-capped): one recorded run measured
    // ~99.5% of the shield lane's counted TOGGLE presses never arriving at the foe's command
    // block as command-present, with every recorded-data gate clean — the eater sits BETWEEN
    // foe.giveCommand(...) and the next frame's dedup filter. Candidates: (a) stale
    // object identity (the pressed ref is not the advanced/rendered body), (b) an unknown
    // runtime writer on the filter branches. This lane samples the first presses of a run
    // SAME-frame (identity triple + the live engine-internal commands/blockedCommands collections via
    // java.lang.invoke MethodHandles — NO java.lang.reflect, the loader-B law) plus a
    // next-frame post-advance shadow (surviving commands + shield isOn/activeArc + holdFire).
    // DIAGNOSTIC ONLY — no fix rides it (the diagnosis-first law: no shield fix mint until the eater is named).
    private static final int PRESS_SHADOW_MAX = 20;             // samples per session (run)
    private static final int PRESS_SHADOW_MAX_PER_ENTITY = 10;  // per entity per round
    private int pressShadowSampled;                             // session-lifetime; also the debrief counter
    private final java.util.Map<Long, Integer> pressShadowPerEntity = new java.util.HashMap<>(); // per-round
    /** Render-thread only: press samples awaiting their next-frame post-advance shadow line. */
    private final ArrayDeque<PressShadowPending> pressShadowPending = new ArrayDeque<>();
    private static java.lang.invoke.MethodHandle pressShadowCommandsHandle;  // engine Ship.getCommands()
    private static java.lang.invoke.MethodHandle pressShadowBlockedHandle;   // engine Ship.getBlockedCommands()
    private static java.lang.invoke.MethodHandle pressShadowHoldFireHandle;  // engine Ship.isHoldFireOneFrame()
    private static Class<?> pressShadowOwner;                   // runtime class the getters were minted for
    private static boolean pressShadowUnavailable;              // one-shot: engine getter mint failed
    private static java.lang.invoke.MethodHandle pressShadowVerbGetter;      // the command record's public verb field
    private static boolean pressShadowVerbUnavailable;          // one-shot: verb layer off (sizes still log)

    /** One press-shadow sample awaiting its next-frame post-advance line. */
    private static final class PressShadowPending {
        final int seq;
        final long entityId;
        final ShipAPI ship;
        final long pressTick;

        PressShadowPending(int seq, long entityId, ShipAPI ship, long pressTick) {
            this.seq = seq;
            this.entityId = entityId;
            this.ship = ship;
            this.pressTick = pressTick;
        }
    }

    PressShadowProbe(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * press-shadow sampling gate: first {@link #PRESS_SHADOW_MAX} shield-lane
     * presses of the run, per-entity sub-cap so a multi-foe wave splits the budget. Knobless by
     * design — the cap IS the cost bound, and the one watch this rides must not be losable to a
     * forgotten arm switch. NOT gated on {@link #pressShadowUnavailable}: the identity triple +
     * engine-list scan are pure API and stay valuable when the engine getter layer is down.
     */
    boolean shouldSample(long entityId) {
        if (pressShadowSampled >= PRESS_SHADOW_MAX) {
            return false;
        }
        Integer per = pressShadowPerEntity.get(entityId);
        return per == null || per.intValue() < PRESS_SHADOW_MAX_PER_ENTITY;
    }

    /** Live engine commands-list size, self-swallowing: -1 = unavailable (the press must never pay). */
    int commandsSize(ShipAPI ship) {
        try {
            java.util.List<?> cmds = pressShadowCommandsList(ship);
            return cmds == null ? -1 : cmds.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Mint (once per runtime class) + invoke the PUBLIC engine getters. {@code getCommands()} /
     * {@code getBlockedCommands()} return the LIVE backing collections (not copies),
     * so membership reads see exactly what the dedup filter will see.
     * publicLookup only — the loader-B java.lang.reflect denial never applies to
     * java.lang.invoke (the {@link PredictReconcileCombatPlugin#settleBoostClockAfterFluxPin} precedent).
     */
    private static java.util.List<?> pressShadowCommandsList(ShipAPI ship) throws Throwable {
        if (pressShadowUnavailable || ship == null) {
            return null;
        }
        Class<?> cls = ship.getClass();
        if (pressShadowOwner != cls) {
            try {
                java.lang.invoke.MethodHandles.Lookup lu = java.lang.invoke.MethodHandles.publicLookup();
                pressShadowCommandsHandle = lu.findVirtual(cls, "getCommands",
                        java.lang.invoke.MethodType.methodType(java.util.List.class));
                pressShadowBlockedHandle = lu.findVirtual(cls, "getBlockedCommands",
                        java.lang.invoke.MethodType.methodType(java.util.EnumSet.class));
                pressShadowHoldFireHandle = lu.findVirtual(cls, "isHoldFireOneFrame",
                        java.lang.invoke.MethodType.methodType(boolean.class));
                pressShadowOwner = cls;
            } catch (Throwable t) {
                pressShadowUnavailable = true;
                PilotWatchLog.info("predict probe press-shadow engine getters UNAVAILABLE (identity"
                        + " legs still log; membership legs off this session): " + t);
                return null;
            }
        }
        return (java.util.List<?>) pressShadowCommandsHandle.invoke(ship);
    }

    /**
     * Render a live commands collection as an ASCII-safe verb list: {@code T} = the TOGGLE verb
     * (the shield/cloak press), anything else the enum constant
     * name with non-ASCII engine identifier chars sanitized to '#'. Falls back to size-only when the verb
     * field getter is unavailable. Cap 8 entries — the replica lists are tiny (maneuver +
     * shield lanes); anything longer is itself a finding. The verb field is PUBLIC on the
     * PUBLIC nested command class, read via findGetter; the
     * enum class resolves by name through the mod loader's parent chain (java.lang.Class,
     * never java.lang.reflect).
     */
    private static String pressShadowCmdsDump(java.util.List<?> cmds) {
        if (cmds == null) {
            return "n/a";
        }
        StringBuilder sb = new StringBuilder("n=").append(cmds.size());
        if (pressShadowVerbUnavailable || cmds.isEmpty()) {
            return sb.toString();
        }
        sb.append('[');
        int shown = 0;
        try {
            for (Object cmd : cmds) {
                if (cmd == null) {
                    continue;
                }
                if (shown >= 8) {
                    sb.append(",..");
                    break;
                }
                java.lang.invoke.MethodHandle g = pressShadowVerbGetter;
                if (g == null) {
                    Class<?> verbEnum = Class.forName("com.fs.starfarer.combat.entities.Ship$oo");
                    // Verb FIELD identifier built from
                    // char codes — ASCII-only source, immune to any javac/source-encoding drift.
                    // A field lookup legitimately takes the engine's internal identifier (unlike an
                    // enum NAME — see below).
                    g = java.lang.invoke.MethodHandles.publicLookup()
                            .findGetter(cmd.getClass(), ((char) 0xD2) + "00000", verbEnum);
                    pressShadowVerbGetter = g;
                }
                Object verb = g.invoke(cmd);
                String name = verb instanceof Enum ? ((Enum<?>) verb).name() : String.valueOf(verb);
                if (shown > 0) {
                    sb.append(',');
                }
                // `Enum.name()` returns the ctor NAME string, and the
                // shipped binary kept those ORIGINAL — the command-verb enum's toggle-shield constant is named
                // "TOGGLE_SHIELD" even though its FIELD identifier differs. Testing
                // the field identifier here could never match, so the "T" shorthand was dead
                // and the full name printed instead. Same defect class as the C7 valueOf bug.
                sb.append("TOGGLE_SHIELD".equals(name) ? "T" : pressShadowAscii(name));
                shown++;
            }
            sb.append(']');
        } catch (Throwable t) {
            pressShadowVerbUnavailable = true;
            sb.append("|verbs-off:").append(t.getClass().getSimpleName());
            PilotWatchLog.info("predict probe press-shadow verb layer UNAVAILABLE (size-only"
                    + " dumps from here): " + t);
        }
        return sb.toString();
    }

    /** Non-ASCII engine identifier chars → '#' so the watch log stays greppable single-byte. */
    private static String pressShadowAscii(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 0x20 && c < 0x7f ? c : '#');
        }
        return sb.toString();
    }

    /** Live blockedCommands EnumSet rendered ASCII-safe (enum toString = constant names). */
    private static String pressShadowBlockedDump(ShipAPI ship) {
        try {
            if (pressShadowUnavailable || pressShadowBlockedHandle == null) {
                return "n/a";
            }
            Object set = pressShadowBlockedHandle.invoke(ship);
            return set == null ? "null" : pressShadowAscii(String.valueOf(set));
        } catch (Throwable t) {
            return "err";
        }
    }

    private static String pressShadowHoldFire(ShipAPI ship) {
        try {
            if (pressShadowUnavailable || pressShadowHoldFireHandle == null) {
                return "n/a";
            }
            return String.valueOf((boolean) pressShadowHoldFireHandle.invoke(ship));
        } catch (Throwable t) {
            return "err";
        }
    }

    /** Identity hash in the log idiom's hex (the identity-triple legs). */
    private static String pressShadowIhash(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }

    /** TRUE when the exact reference rides the engine's live ship list (identity, not equals). */
    private boolean pressShadowInEngineList(ShipAPI ship) {
        try {
            if (plugin.engine == null || ship == null) {
                return false;
            }
            for (ShipAPI s : plugin.engine.getShips()) {
                if (s == ship) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // fall through
        }
        return false;
    }

    /**
     * The SAME-frame press shadow, logged immediately after the counted
     * giveCommand. Identity triple: the pressed ref vs the registry row's bound ship vs the
     * parity walk's subject (the plugin's {@link PredictReconcileCombatPlugin#foeShip} — the walk samples exactly that
     * instance), plus the engine-list identity scan — candidate (a) is a pressed ref missing
     * from some of these. preCmds vs the dump brackets our own add on the live engine-internal list;
     * blocked/holdFire/defDis re-measure the filter branches live (candidate (b)).
     */
    void samplePress(ShipAPI pressed, ServerEntityPose pose, long simTick,
            int preCmds, ShieldAPI shield, boolean omni) {
        try {
            long eid = pose.entityId();
            pressShadowSampled++;
            Integer per = pressShadowPerEntity.get(eid);
            pressShadowPerEntity.put(eid, per == null ? 1 : per.intValue() + 1);
            java.util.List<?> cmds = null;
            try {
                cmds = pressShadowCommandsList(pressed);
            } catch (Throwable t) {
                // dump stays n/a
            }
            PredictReconcileCombatPlugin.RemoteEntity row = plugin.remoteEntities.get(eid);
            PilotWatchLog.info("predict probe press_shadow_press seq=" + pressShadowSampled + "/"
                    + PRESS_SHADOW_MAX + " tick=" + simTick + " eid=" + eid
                    + " pressed=" + pressShadowIhash(pressed)
                    + " registry=" + pressShadowIhash(row == null ? null : row.ship)
                    + " walkFoe=" + pressShadowIhash(plugin.foeShip)
                    + " inEngine=" + pressShadowInEngineList(pressed)
                    + " preCmds=" + preCmds
                    + " cmds=" + pressShadowCmdsDump(cmds)
                    + " blocked=" + pressShadowBlockedDump(pressed)
                    + " defDis=" + pressed.isDefenseDisabled()
                    + " holdFire=" + pressShadowHoldFire(pressed)
                    + " alive=" + pressed.isAlive() + " hulk=" + pressed.isHulk()
                    + " shieldOn=" + shield.isOn() + " shieldOff=" + shield.isOff()
                    + " arc=" + shield.getActiveArc() + " omni=" + omni);
            pressShadowPending.addLast(
                    new PressShadowPending(pressShadowSampled, eid, pressed, simTick));
        } catch (Throwable t) {
            // diagnostic only — never break the lane
        }
    }

    /**
     * The NEXT-frame post-advance shadow: by this plugin advance the engine has
     * run the pressed ship's own advance since the press (one entity advance per frame;
     * the fast-time carve-out measured dead), so the pressed command has met the dedup filter
     * and the command block. What survived on the live list — and what the shield did — names
     * the eater. Runs from advance() BEFORE this frame's lanes press again.
     */
    void flush(long nowTick) {
        while (!pressShadowPending.isEmpty()) {
            PressShadowPending p = pressShadowPending.pollFirst();
            try {
                java.util.List<?> cmds = null;
                try {
                    cmds = pressShadowCommandsList(p.ship);
                } catch (Throwable t) {
                    // dump stays n/a
                }
                ShieldAPI shield = p.ship.getShield();
                PredictReconcileCombatPlugin.RemoteEntity row = plugin.remoteEntities.get(p.entityId);
                PilotWatchLog.info("predict probe press_shadow_post seq=" + p.seq
                        + " pressTick=" + p.pressTick + " nowTick=" + nowTick
                        + " eid=" + p.entityId
                        + " pressed=" + pressShadowIhash(p.ship)
                        + " registry=" + pressShadowIhash(row == null ? null : row.ship)
                        + " walkFoe=" + pressShadowIhash(plugin.foeShip)
                        + " inEngine=" + pressShadowInEngineList(p.ship)
                        + " cmds=" + pressShadowCmdsDump(cmds)
                        + " blocked=" + pressShadowBlockedDump(p.ship)
                        + " defDis=" + p.ship.isDefenseDisabled()
                        + " holdFire=" + pressShadowHoldFire(p.ship)
                        + " alive=" + p.ship.isAlive() + " hulk=" + p.ship.isHulk()
                        + (shield == null ? " shield=null"
                                : " shieldOn=" + shield.isOn() + " shieldOff=" + shield.isOff()
                                        + " arc=" + shield.getActiveArc()));
            } catch (Throwable t) {
                // diagnostic only
            }
        }
    }

    /**
     * The round boundary (PRCP {@code finishMatchReset}): the per-entity sub-cap
     * is per-round while the ≤{@link #PRESS_SHADOW_MAX}/run cap survives, and the pending pressed
     * refs must not leak across the round rebuild. Clear order = the pre-extraction order.
     */
    void resetRound() {
        pressShadowPerEntity.clear(); // Diag: per-entity sub-cap is per-round; the ≤20/run cap survives
        pressShadowPending.clear();   // Diag: pressed refs must not leak across the round rebuild
    }

    /** Session-lifetime samples taken — the debrief counter the foe-shield meter line reads. */
    int sampledCount() {
        return pressShadowSampled;
    }
}
