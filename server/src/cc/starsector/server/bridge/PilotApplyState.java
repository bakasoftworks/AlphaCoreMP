package cc.starsector.server.bridge;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Mutable per-owned-ship apply state the serve loop owns and threads into {@link EngineCombatBinding#applyPilotIntent}
 * across ticks. Holds the vent rising-edge memory. One instance per owned ship;
 * never shared across ships.
 *
 * <p>{@code retryBudgetTicks} is retained as a configured knob ({@code cc.server.retryBudgetTicks})
 * but is <em>no longer consulted</em>: the ship-system activation is now <em>held-driven</em> (see
 * {@link EngineCombatBinding#applySystemBit}) — it re-issues {@code useSystem()} every tick bit-7 is held while the system
 * is not active and {@code canBeActivated()}, with no finite window — which supersedes the prior
 * rising-edge + bounded-retry shape (that disarmed mid-hold and so never re-engaged across a
 * system's cooldown cycles). The field/ctor/config plumbing stays so the config surface and the
 * contracts that pass it positionally are unchanged; a future cleanup can remove the knob end-to-end.
 *
 * <p><b>Declared-discharge fire lane.</b> When {@link #useDeclaredFireLane selected} (the
 * serve loop sets it from {@code cc.server.fireLane}), FIRE is no longer level-sampled from the wire
 * {@code fireHeld} bit: the client declares each discharge its OWN engine actually emitted
 * ({@link cc.starsector.server.runtime.FireDischargeDeclaration}), the serve loop enqueues them here,
 * and {@link EngineCombatBinding#applyPilotIntent} issues the same aimed fire command
 * ({@code giveCommand(USE_SELECTED_GROUP)} on the real engine) every tick until the
 * server's engine serves the head declaration (consumed by the serve loop on its own observed own-ship
 * {@code WEAPON_DISCHARGE}) or it expires ({@link #DECLARED_FIRE_TIMEOUT_TICKS}). One declaration is
 * served at a time — the head's timeout clock starts when it becomes head. The default is the legacy
 * held lane so every existing direct constructor stays byte-identical.
 *
 * <p><b>Provenance.</b> Carved VERBATIM out of {@code EngineCombatBinding} (its former nested
 * {@code public static final class}) into this own file: the class is a
 * pure per-ship state machine (declared-fire FIFO, class routing belts, retreat/fire edge latches) that read
 * no reflective handle of the binding, so nothing behavioral moved — only the enclosing name. External
 * call sites that spelled {@code EngineCombatBinding.PilotApplyState} now spell {@code PilotApplyState}.
 */
public final class PilotApplyState {

    /**
     * ε-timeout (server ticks ≈ 1 s at the 60 Hz fixed step) before an unserved head declaration is
     * dropped as expired — must exceed the measured organic client↔server offset + latency
     * (≈ 200 ms) with margin, while still bounding a wedged lane.
     */
    public static final int DECLARED_FIRE_TIMEOUT_TICKS = 60;

    /** Pending-declaration FIFO bound; an enqueue past it drops the INCOMING declaration as expired. */
    public static final int MAX_PENDING_DECLARATIONS = 256;

    /**
     * CLASS-ROUTED FIFO bound: class-S rates are ≤ the knob
     * threshold by construction, so the FIFO drains by head timeout faster than it fills —
     * a deep buffer only masks starvation (one measured run showed it: 256 deep, aim seconds
     * stale). 32 still absorbs a full harpoonpod-class volley burst before the belts speak.
     */
    public static final int MAX_PENDING_DECLARATIONS_ROUTED = 32;

    /**
     * Backlog-demote belt (belt 2): consecutive timeout/overflow expiries on ONE mount —
     * with no intervening served shot from it — before that weapon demotes to class H for the
     * session. Streak-not-window by design: an expiry streak with zero serves is the wedge
     * signature regardless of wall spacing, and it needs no tick plumbed into the enqueue
     * seam. The belt is what makes {@code cc.server.fireClassRateMax} SAFE to tune: a
     * too-generous threshold degrades to a loud demotion, never a silent 114103-style jam.
     */
    public static final int CLASS_DEMOTE_EXPIRY_STREAK = 3;

    /**
     * Decl-recency belt default window (ticks; ≈2 s at the 60/s sim rate — the serve
     * loop re-derives from its effective tick rate at state creation). While a class-S group
     * is SELECTED under a held trigger, held is excluded and declarations are the only FIRE
     * path — a decl stream silent for this long reverts the ship to held LOUD (fail-open,
     * (83) law). The session-level zero-decl gate cannot see this shape: declarations may
     * still arrive for other ships (the mixing seam).
     * Only DECL-CAPABLE held ticks count toward the window (engine press-gate
     * verdict — {@link #noteClassSHeldTick}), so 2 s means 2 s of continuously-satisfiable
     * trigger, which also gives chargeup weapons (≤~1 s in the class-S rate band) + decl
     * RTT their margin inside one window.
     */
    public static final int DECL_RECENCY_WINDOW_TICKS_DEFAULT = 120;

    /** Expiry log lines emitted per state before the log caps (the counters keep counting). */
    private static final int MAX_EXPIRY_LOGS = 5;

    /** Distinct m&lt;mount&gt;:&lt;reason&gt; detail keys before overflow to "other". */
    private static final int MAX_EXPIRY_DETAIL_KEYS = 24;

    // Credit window: how recent a SERVED consume on a mount must be for an
    // UNMATCHED discharge to count as a burst continuation, and how long a minted credit stays
    // spendable. The fixed 60-tick constant is RETIRED — the
    // window now rides declaredFireTimeoutTicks (the ≈1 s rate carrier; = 60 at
    // 60 Hz, 30 at 30 Hz), the headIsFutile idiom, zero new plumbing. A sabotpod burst
    // (2 @ 0.25 s, its weapon-table row) fits with room; a chargedown re-fire (8.75 s) never does.

    /**
     * At most this many unspent
     * continuation credits stack on one mount; a further unmatched in-burst discharge counts
     * {@link #continuationCreditsCapped()} and mints nothing. A burst of N shots owes at most
     * N-1 continuations; the safer bound is burstSize-1, but burstSize (the BASE
     * spec read in {@code MountProbeLane.classifyWeaponClassSOrNull} feeding
     * {@code FireClassLaw.sustainedRate}) is NOT reachable at the mint site — neither the
     * resolver nor the probe exposes it, and exposing it is a new reflective seam — so a flat
     * 3 is the bound: headroom over the only class-S burst the codebase names (sabotpod,
     * burst 2 = 1 continuation).
     */
    public static final int CONTINUATION_CREDIT_CAP_PER_MOUNT = 3;

    /** credit-served log rows per state before the log caps (counters continue). */
    static final int MAX_CREDIT_LOGS = 32;

    /**
     * The fireEdgeSeq latch window (server ticks ≈ 0.5 s at the 60 Hz fixed step) — the
     * bounded press window after an observed rising-edge advance. Rate-aware like the declared
     * lane: the live window is {@code declaredFireTimeoutTicks / 2} (the serve lanes pass the
     * effective tick rate ≈ 1 s), so this constant is the frozen 60 Hz default.
     */
    public static final int FIRE_EDGE_LATCH_WINDOW_TICKS = DECLARED_FIRE_TIMEOUT_TICKS / 2;

    /**
     * press-span clamp: pressing ticks the latch may issue while {@code fireHeld}
     * is false when the press was never wire-visible (the true lost-tap case — the tap's
     * fireHeld frames dropped, only the cumulative fireEdgeSeq advance survived). Vanilla's
     * trigger law drops a press that begins and ends inside cooldown (a new charge starts only
     * from IDLE, the chargedown-restart flag is hardcoded false, no press buffering, Ship.commands
     * cleared per frame), and a READY
     * chargeup-0 weapon fires the same frame it is pressed — so a short synthetic span recovers
     * every lost tap a ready weapon would have fired while a cooldown-blocked one expires
     * unfired, exactly like the client's own engine dropped it.
     */
    public static final int FIRE_EDGE_MIN_TAP_TICKS = 3;

    /**
     * Pressing ticks the latch may add PAST the wire-observed release of a
     * visible press. 0 = strict span replay (vanilla is the reference — a recorded run showed the
     * cooldown-blocked forks discharging ~2 ticks past the client's release, so any pad ≥ 2
     * re-opens that cooldown-blocked fork class; the reconcile ADOPT/RETIRE lane owns residual
     * cooldown-boundary skew instead). Named so a re-watch can tune it in one line.
     */
    public static final int FIRE_EDGE_PRESS_PAD_TICKS = 0;

    private final int retryBudgetTicks;
    private final int declaredFireTimeoutTicks;
    boolean prevVentBit; // pkg-private: EngineCombatBinding reads it (former nestmate)
    boolean prevRetreatBit; // pkg-private: EngineCombatBinding reads it (former nestmate) // retreat bit-9 edge memory (the prevVentBit idiom)
    boolean retreatRearmPending; // pkg-private: EngineCombatBinding reads it (former nestmate) // set at hull loss — a held-TRUE bit-9 (sampled off the PRE-loss body, e.g. a client mid ghost-dive) is ignored until one bit-false frame proves the client samples its fresh hull (fly_in t22096 → HULL_DEPARTED t22097)
    long shieldHoldTicks; // pkg-private: EngineCombatBinding reads it (former nestmate) // ticks the bit-10 hold replay issued TOGGLE_SHIELD_OR_PHASE_CLOAK
    long cloakToggleTicks; // ticks the bit-11 mismatch-edge lane issued TOGGLE_SHIELD_OR_PHASE_CLOAK on a cloak hull
    long pullBackToggleTicks; // Ticks the bit-12 mismatch-edge lane issued PULL_BACK_FIGHTERS
    int ventsIssued; // bit-8 rising-edge VENT_FLUX presses (the meter's original meaning, now state-carried)
    // Autofire CLICK latch (law + evidence: applyAutofireMask's javadoc). The tick
    // the mask mirror last pressed TOGGLE_AUTOFIRE, and which group ordinals it pressed then — a
    // press is withheld for one tick per group so the engine's rising-edge tracker re-arms.
    long autofirePressTick = Long.MIN_VALUE / 2;
    int autofirePressMask;
    int autofireClickReleases; // ticks a press was withheld to give the engine its release frame
    int ventRePresses; // mismatch re-presses (bit-8 held && server !isVenting) — split from ventsIssued so external meters keep meaning
    int retreatReasserts; // non-edge setRetreating level re-asserts (wire bit-9 vs hull isRetreating disagreement ticks — refused-clear retries + engine reversals)
    int retreatRearmSkips; // held-TRUE bit-9 frames ignored while retreatRearmPending (round meter retreat_rearm_skips=; 0 = no stale-held retreat level hit a refield)
    int retreatEdgeQuarantineFalseLeft; // post-re-key edge quarantine — rising edges may not engage until this many CONSECUTIVE bit-false frames arrive (a TRUE frame restarts it); armed with retreatRearmPending
    int retreatEdgeQuarantined; // TRUE bit-9 frames absorbed inside the quarantine window (round meter retreat_edge_quarantined=; 0 = no spurious post-re-key retreat level) — LOUD by design, never silent suppression
    boolean diveOwnsRetreat; // pkg-private: EngineCombatBinding reads it (former nestmate) // emergency-dive latch — the whole bit-9 lane freezes (engine owns retreat state for a committed dive); set at the F4c dive-start edge, cleared at hull loss
    int retreatDiveFreezeTicks; // bit-9 apply ticks frozen while diveOwnsRetreat (proof the gate ENGAGED, vs "no fight happened")
    int cloakDiveFreezeTicks; // bit-11 cloak toggles WITHHELD while diveOwnsRetreat (the sibling; counts mismatched ticks only = proof the gate ENGAGED)
    // Mask-staleness gate (armed at a variant-SWAP refield only): while armed the
    // shape TRIO (selected-group mirror / active-tube conform / autofire-mask diff) is withheld so
    // the client's stale pre-death CROSS-VARIANT sample cannot re-arm the fresh hull (root
    // cause: the first applied frame after a repoint carries the dead variant's mask). Release law
    // + provenance = maskStalenessGateHolds.
    boolean maskGateArmed; // pkg-private: EngineCombatBinding reads it (former nestmate)
    long maskGateRefieldTick; // pkg-private: EngineCombatBinding reads it (former nestmate) // fly-in tick: frames echoing an older snapshot are stale by causality
    int maskGateStaleMask; // pkg-private: EngineCombatBinding reads it (former nestmate) // S = lastWireMask snapshot at arm — the frozen pre-death baseline
    long maskGateAdoptEcho = -1L; // pkg-private: EngineCombatBinding reads it (former nestmate) // first frameSeq >= refieldTick seen (the ONE capture-order residual candidate)
    int maskGateSuppressedFrames; // trio-withheld applied frames while armed (release log frames_held=)
    int lastWireMask = Integer.MIN_VALUE; // last PRESENT (>=0) wire autofire mask ever applied; MIN_VALUE = never tracked (boot fly-in ⇒ no-arm)
    // Refield stale-frame gate (armed at a despawn → respawn fly-in): while armed the
    // WHOLE apply is withheld, AI suppression excepted. Law + provenance = refieldGateHolds.
    private boolean refieldGatePending; // a hull loss happened on this wire id; the next fly-in arms
    private boolean refieldGateArmed;
    private long refieldGateTick; // the fly-in tick T = the first snapshot that carries the fresh hull
    private long refieldGateAdoptEcho = -1L; // first frameSeq >= T seen (the capture-before-respawn candidate)
    private int refieldGateHeldTicks; // apply ticks withheld under the current / last arm (release row ticks_held=)
    private boolean declaredFireLane;
    /** The fireEdgeSeq depth-1 latch (held lane): state, law and edge meters live in the class. */
    private final FireEdgeLatch fireEdge;
    int systemCancelPresses; // bit-LOW cancel-mirror useSystem() presses (double-tap cancel)
    int tubeOrdinalConformed; // active-tube pointer conforms (the wire-invisible re-select class)
    // Per-press system-intent trace (pilot-sysint stdout rows; bounded+capped,
    // write-only — the server could not explain its own refusals: system_activations=3 vs ~7 local
    // fires on one recorded run with zero per-press rows).
    long sysHoldEdgeTick = -1L; // rising-edge simTick of the current bit-7 hold; -1 = bit low
    boolean sysHoldAccepted; // this hold reached an activation (or found the system already engaged)
    int sysintRows; // pilot-sysint rows emitted (SYSINT_ROW_CAP, then one capped row, then silence)
    boolean sysintDead; // one-shot: a trace read threw — trace off this seat, apply path unaffected
    private final java.util.ArrayDeque<cc.starsector.server.runtime.FireDischargeDeclaration>
            pendingDeclarations = new java.util.ArrayDeque<>();
    /**
     * GPEE v16 declared wait: each ENQUEUED declaration's {@code applyTick} (the serve
     * tick its drain fed), keyed by INSTANCE — {@code FireDischargeDeclaration} is a record, so
     * value-equal duplicates are distinct declarations and a {@code HashMap} would merge them. Written
     * at the one FIFO add (only when {@code applyTick >= 0}), dropped at every FIFO removal
     * ({@link #forgetArrival}) and cleared with every FIFO clear, so its keys are always a subset of
     * {@link #pendingDeclarations} (bound = the FIFO's own cap). Bookkeeping only: the consume reads it
     * to stamp the discharge edge; nothing is served differently.
     */
    private final java.util.IdentityHashMap<cc.starsector.server.runtime.FireDischargeDeclaration, Long>
            declArrivalTickByDecl = new java.util.IdentityHashMap<>();
    private long headServeSinceTick = -1L;
    private int declarationsReceived;
    private int declarationsServed;
    private int declarationsExpired;
    private int declPressesIssued; // Serve presses actually issued (≈1/serve post-fix)
    private int declPressWaitTicks; // Head pending but mount not dischargeable — silent waits
    private int declLockWaitTicks; // Head pending while the hull's fire lock holds — silent waits
    private int declAdoptFireIssued; // named-mount force-fires issued for ray heads
    private int declAdoptBatchServed; // Volley mates force-fired in their head's own serve tick
    private int declAdoptRejects; // Ray heads dropped by adopt validation (LOUD)
    private final java.util.LinkedHashMap<String, Integer> adoptRejectsByReason =
            new java.util.LinkedHashMap<>(); // round-line reason detail
    private int expiryLogsEmitted;
    // reasoned-expiry detail — key m<mount>:<reason>, bounded
    // MAX_EXPIRY_DETAIL_KEYS + "other" overflow; fed by logExpiry, the SINGLE reasoned
    // funnel (lane-held/lane-class/overflow/timeout*/adopt-*). Bulk flushes (hull-loss,
    // routing revert/rebind strands) bypass logExpiry BY IDENTITY, so this map undercounts
    // declarationsExpired by exactly those bulk drops.
    private final java.util.LinkedHashMap<String, Integer> expiredByMountReason =
            new java.util.LinkedHashMap<>();
    // head-tenure split — what the CURRENT head's tenure actually did
    // (reset at each head-clock start); refines the bare ε-timeout reason into
    // timeout-pressed / timeout-waitheld / timeout-idle so the starvation [H]
    // adjudicates at the next organic watch without touching any serve-lane knob blind.
    private int headTenurePresses;
    private int headTenureWaitTicks;
    private int headTenureLockWaitTicks; // this tenure's fire-lock waits (⇒ timeout-locked, belt 2 skipped)
    private int headTenureAdoptFires;
    // alternating-group held-dwell machine — vanilla's mid-hold tube
    // advance is PLAYER-SHIP-ONLY, so a server-driven hull's
    // held FIRE can never alternate; the erb dwell verb drives the
    // engine's own setCurrentActive on vanilla's own dwell law. Dies with the round.
    private long altDwellLastTick = Long.MIN_VALUE / 2;
    private int altDwellGroupOrdinal = -1;
    private int altDwellHeldTicks;
    private int altTubeDwellAdvances;
    // Class routing: per-TICK lane
    // choice — mutually exclusive with the legacy whole-ship declaredFireLane by serve-loop
    // construction (enableClassRouting guards). The resolver is the injected class oracle
    // (production: EngineCombatBinding BASE-spec reads; contracts: synthetic).
    private boolean classRouted;
    private FireClassResolver classResolver;
    /** The declared-shot press pad (law, state and meters live in the class). */
    private final DeclaredShotPad declPad = new DeclaredShotPad();
    private final java.util.HashSet<Integer> demotedMounts = new java.util.HashSet<>();
    private final java.util.HashSet<Integer> demotedGroups = new java.util.HashSet<>();
    private final java.util.HashMap<Integer, Integer> expiryStreakByMount = new java.util.HashMap<>();
    // per-mount last driver-validation reject reason (the repeat-reject fast belt)
    private final java.util.HashMap<Integer, String> lastAdoptRejectByMount = new java.util.HashMap<>();
    // burst-continuation credit: the serve loop dispatches discharge
    // edges BEFORE it drains declarations, so a burst weapon's continuation discharge (fired by
    // the server engine as the burst's own tail) arrives with no matching decl and the client's
    // continuation decl lands one tick later as an ORPHAN head that wedges the class-routed FIFO
    // (120 ticks of hammer blackout). The unmatched discharge mints a credit on
    // its mount; the late decl spends it at intake as served. The sim clock is the last tick an
    // applied frame showed us (headDeclarationToServe / observeFireEdge — one of them runs on
    // every class-routed applied frame), so no serve-loop signature changes.
    long lastObservedSimTick = Long.MIN_VALUE / 2;
    private final java.util.HashMap<Integer, Long> lastServedTickByMount = new java.util.HashMap<>();
    private final java.util.HashMap<Integer, Integer> continuationCreditCountByMount = new java.util.HashMap<>();
    private final java.util.HashMap<Integer, Long> continuationCreditNewestTickByMount = new java.util.HashMap<>();
    private int servedByCredit;
    private int continuationCreditsMinted;
    // Credit meters, all on the round line (credit_expired /
    // credit_capped / credit_fence_refused / discharge_unmatched_nonburst):
    private int continuationCreditsExpiredUnspent; // dropped unspent: stale stack / hull loss / repoint
    private int continuationCreditsCapped; // in-burst unmatched discharges over the per-mount cap
    private int creditFenceRefusals; // credit live but the stateless fence refused the decl's ray
    private int dischargeUnmatchedNonburst; // in-window unmatched discharges NOT mid-burst before OR after the shot
    // The probe's isInBurst read taken POST-step at this mount's previous
    // discharge edge (served consume or in-window unmatched) = the PRE-tick burst state of the
    // next discharge — nothing but a shot changes isInBurst between ticks, and the engine clears
    // it inside the firing call of a burst's LAST shot (cr-r3-credit.md F1). Written only on
    // discharge edges (zero per-tick cost).
    private final java.util.HashMap<Integer, Boolean> inBurstAfterLastDischargeByMount = new java.util.HashMap<>();
    private int lastShotCreditsMinted; // r5: credits minted on a burst's LAST shot (was mid-burst, now not)
    private int creditLogsEmitted;
    private long lastCreditLagTicks; // the spent credit's age, for the capped credit-served row
    private int futileHeadExpiries; // Heads expired early as timeout-futile
    private int laneClassDrops;
    private int laneGroupDrops; // the group-law subset of laneClassDrops
    private int classDemotes;
    private int routingReverts; // fail-open: whole-ship routing→held reverts (recency/session)
    private int classResolverRebinds; // Repoint rebinds (fresh resolver + trio reset)
    private int classSHeldNoDeclTicks; // decl-recency belt counter (class-S selected + fireHeld, no decl)
    private int declRecencyWindowTicks = DECL_RECENCY_WINDOW_TICKS_DEFAULT;
    // GROUP IDENTITY MAP — Defect I root: the wire's group ordinals are
    // CLIENT-space (client live order == variant-file order) while every
    // mirror consumer indexes the SERVER hull's group list; an order divergence mis-arms
    // autofire (+44 server-minted post-release shots). The map
    // translates client→server ONCE at the wire seams (applyAutofireMask / mirrorSelected-
    // Group / selectedGroupClassS / latch release pairing); everything behind them stays
    // server-space. Built LAZILY per hull tenure from the variant FILE's slot-id sets
    // (declaredWeaponGroups, recorded at every fly-in) matched against the live groups'
    // member slot ids; unmatched ordinal = -1 ⇒ that bit/ordinal is SKIPPED, never guessed
    // (fail-closed: a skipped server group stays at its build-time-disarmed state).
    // null map after resolve = identity (no file truth / full agreement) — the pre-map
    // byte-identical path, so contracts and fakes are untouched by construction.
    java.util.List<java.util.Set<String>> groupIdentityFileGroups; // file truth; null = none
    int[] groupIdentityMap; // client ordinal -> server ordinal (-1 unmatched); null = identity
    int[] groupIdentityInverse; // server ordinal -> client ordinal (-1 unmatched); null = identity
    boolean groupIdentityResolved; // lazy build latch (false until the live hull was readable)
    boolean groupIdentityDeployDumped; // Instrument: first applied frame this tenure
    boolean groupIdentityToggleDumped; // Instrument: first autofire toggle this tenure
    int groupIdentitySkips; // wire ordinals dropped by the fail-closed -1 law (meter)

    /**
     * Install this hull tenure's FILE-declared weapon groups (slot-id
     * sets in declared order — the client's live group order by the order law) and
     * reset the lazily-built identity map + the one-shot instrument latches. Called
     * by the serve loop at EVERY seat fly-in (initial deploy, refield, repoint — each is a
     * new hull tenure); {@code null} = no file truth (legacy door / non-catalog variant) ⇒
     * the map stays identity, today's behavior.
     */
    public void declaredWeaponGroups(java.util.List<java.util.Set<String>> fileGroups) {
        this.groupIdentityFileGroups = fileGroups;
        this.groupIdentityMap = null;
        this.groupIdentityInverse = null;
        this.groupIdentityResolved = false;
        this.groupIdentityDeployDumped = false;
        this.groupIdentityToggleDumped = false;
    }

    /**
     * The class oracle the serve loop injects at {@link #enableClassRouting}. Mount/group
     * ordinals are the ship's {@code getAllWeapons()} / weapon-group ordering (the PDCL v3
     * cross-side contract). A group is class-S only when EVERY member weapon is class-S —
     * a mixed group must ride held ENTIRELY: ALL its members' declarations are dropped at
     * intake ({@link #enqueueDeclaration} — the H members as {@code lane-class}, the S
     * members as {@code lane-group}), so the FIFO stays empty for it and the per-tick
     * dispatch's held press fires the whole group.
     * Before the group gate, an S member's pending decl won the tick (declared-only,
     * no held press) nearly every tick of a sustained hold, so the H co-members (irpulse /
     * ioncannon beside a heavyblaster) never fired serverside for ~14 s — the observer saw
     * only the heavy blasters. Held-lane cost on the S member = jitter-sized, reconciled.
     */
    public interface FireClassResolver {
        /** Whether the weapon at this mount ordinal classifies S ({@link cc.starsector.server.runtime.FireClassLaw}). */
        boolean mountIsClassS(int mountIndex);

        /** Whether EVERY weapon in this group ordinal classifies S (mixed groups are H). */
        boolean groupIsClassS(int groupOrdinal);

        /** The group ordinal owning this mount, or -1 when unresolvable. */
        int groupForMount(int mountIndex);

        /**
         * The SERVER group ordinal of this mount iff the {@link DeclaredShotPad}
         * may press it (a weapon group, not ALTERNATING, every member the declared weapon, non-beam,
         * BASE burst size {@code <= 1}, BASE chargeup 0), else -1 (ineligible or unreadable). A
         * DEFAULT answering -1 so every existing fake stays pad-inert.
         */
        default int declPadGroupForMount(int mountIndex) {
            return -1;
        }
    }

    /** @param retryBudgetTicks retained config-compat knob (≥0); no longer gates system activation. */
    public PilotApplyState(int retryBudgetTicks) {
        this(retryBudgetTicks, DECLARED_FIRE_TIMEOUT_TICKS);
    }

    /**
     * rate-aware overload: the serve lanes pass the effective tick rate
     * ({@code Math.round(1.0 * rate)} ≈ 1 s) as {@code declaredFireTimeoutTicks}; the 1-arg
     * constructor keeps every existing direct construction byte-identical at the canonical
     * {@link #DECLARED_FIRE_TIMEOUT_TICKS}.
     */
    public PilotApplyState(int retryBudgetTicks, int declaredFireTimeoutTicks) {
        if (retryBudgetTicks < 0) {
            throw new IllegalArgumentException("retryBudgetTicks must be >= 0; got " + retryBudgetTicks);
        }
        if (declaredFireTimeoutTicks < 1) {
            throw new IllegalArgumentException(
                    "declaredFireTimeoutTicks must be >= 1; got " + declaredFireTimeoutTicks);
        }
        this.retryBudgetTicks = retryBudgetTicks;
        this.declaredFireTimeoutTicks = declaredFireTimeoutTicks;
        this.fireEdge = new FireEdgeLatch(this, Math.max(1, declaredFireTimeoutTicks / 2)); // LAST: its ctor only stores
    }

    /**
     * Select the FIRE lane {@link EngineCombatBinding#applyPilotIntent} serves for this ship: {@code true} = the
     * declared-discharge lane, {@code false} (the construction default) = the legacy
     * level-sampled {@code fireHeld} lane. The serve loop owns this (knob + fail-open fallback);
     * flipping declared→held only happens while zero declarations were ever received, so no
     * pending declaration is stranded by a flip.
     */
    public void useDeclaredFireLane(boolean declared) {
        if (declared && classRouted) {
            throw new IllegalStateException(
                    "class routing is enabled; the legacy whole-ship declared lane is exclusive with it");
        }
        this.declaredFireLane = declared;
    }

    /** Whether the declared-discharge lane is serving FIRE for this ship. */
    public boolean declaredFireLane() {
        return declaredFireLane;
    }

    /**
     * Enable per-TICK class routing for this ship — the
     * one-lane rule survives as ONE LANE PER TICK: a pending (post-filter, therefore class-S)
     * declaration wins the tick's FIRE; otherwise held serves, EXCLUDED while the selected
     * group is class-S (declared owns those discharges — a held press would double-fire real
     * ammo/flux). Exclusive with the legacy whole-ship declared lane (loud guard) — the serve
     * loop selects exactly one of {held, declared, routed} per session.
     */
    public void enableClassRouting(FireClassResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        if (declaredFireLane) {
            throw new IllegalStateException(
                    "legacy whole-ship declared lane is selected; class routing is exclusive with it");
        }
        this.classRouted = true;
        this.classResolver = resolver;
    }

    /**
     * A {@code seat_variant_repoint} swaps the hull's
     * roster under the SAME wire id, so every ordinal-keyed verdict here judged the DEAD
     * hull — the injected resolver's lazy caches AND the demote trio (demoted mounts,
     * demoted groups, expiry streaks). Swap in a FRESH resolver and clear the trio
     * JOINTLY: a half-reset either strands a fine new-hull mount on held or lets a stale
     * demote mask the very loop under test; the belts re-demote the new hull within
     * seconds when deserved. Pending declarations were minted for the dead roster and
     * flush to expired (the {@link #disableClassRouting} precedent — identity stays
     * closed). Returns the probe summary for the caller's repoint log, or null when
     * routing is not active on this state — a rebind must never re-enable routing past a
     * fail-open revert (exclusivity also keeps this null on the legacy declared lane).
     */
    public String rebindClassRoutingAtRepoint(FireClassResolver fresh) {
        Objects.requireNonNull(fresh, "fresh");
        if (!classRouted) {
            return null;
        }
        int demotesCleared = demotedMounts.size();
        int groupsCleared = demotedGroups.size();
        int streaksCleared = expiryStreakByMount.size();
        int stranded = pendingDeclarations.size();
        classResolver = fresh;
        demotedMounts.clear();
        demotedGroups.clear();
        expiryStreakByMount.clear();
        lastAdoptRejectByMount.clear(); // The repeat-reject memory resets with the trio
        clearContinuationCredits(); // Mount ordinals were the dead roster's
        declPad.clearForRebind(); // The pad's group facts were the dead roster's
        if (stranded > 0) {
            declarationsExpired += stranded;
            pendingDeclarations.clear();
            declArrivalTickByDecl.clear(); // The stamp store follows the FIFO
            headServeSinceTick = -1L;
        }
        classResolverRebinds++;
        return "demotes_cleared=" + demotesCleared + " groups_cleared=" + groupsCleared
                + " streaks_cleared=" + streaksCleared + " stranded_flushed=" + stranded;
    }

    /** Whether per-tick class routing serves this ship. */
    public boolean classRouted() {
        return classRouted;
    }

    /**
     * The serve window owns the hull's selection while a declaration is pending — true for
     * BOTH declaration-serving lanes (legacy whole-ship declared, class-routed); the
     * selection mirror re-asserting the tick after consume/expire IS the re-assert.
     */
    public boolean declaredServeOwnsSelection() {
        return (declaredFireLane || classRouted) && !pendingDeclarations.isEmpty();
    }

    /**
     * held-exclusion predicate: whether the wire-selected group is (still) class-S under
     * routing — every member weapon classifies S and none was session-demoted. False whenever
     * routing is off, the ordinal is unselected/DESELECTED, or the group carries a demoted
     * mount (held resumed for it, loud at the demote site).
     */
    public boolean selectedGroupClassS(int groupOrdinal) {
        return classRouted && groupOrdinal >= 0 && !demotedGroups.contains(groupOrdinal)
                && classResolver.groupIsClassS(groupOrdinal);
    }

    /**
     * The demote belts' shared sink — belt 1 (pose-lane-OWNED ordnance seen discharging on a
     * class-S mount: guided missiles / free-fall bombs gain ~nothing from declared serve) and
     * belt 2 (backlog: {@link #CLASS_DEMOTE_EXPIRY_STREAK}). Demotes the mount AND its whole
     * group to class H for the session (group law: mixed groups ride held), LOUD one-shot log.
     * Returns false when routing is off, the mount is unknown, or it already demoted.
     */
    public boolean demoteMountToHeld(int mountIndex, String reason) {
        if (!classRouted || mountIndex < 0 || !demotedMounts.add(mountIndex)) {
            return false;
        }
        int group = classResolver.groupForMount(mountIndex);
        if (group >= 0) {
            demotedGroups.add(group);
        }
        classDemotes++;
        System.out.println("pilot-fire-class demote mount=" + mountIndex
                + " group=" + group + " reason=" + reason
                + " (weapon reverts to the held lane for this session)");
        return true;
    }

    /** Meter: declarations dropped at intake because their weapon — or its GROUP
     *  (the group law) — classifies H; {@link #laneGroupDrops} is the group subset. */
    public int laneClassDrops() {
        return laneClassDrops;
    }

    /**
     * Group-law meter (subset of {@link #laneClassDrops}): class-S mount declarations
     * dropped because their GROUP is not class-S — mixed members or session-demoted. Non-zero
     * = the group law engaged for a mixed group this session (the D-FIREDROP shape rides
     * held by design); the regression signal is the OBSERVER-side silence, so a watch that
     * flies a mixed group expects this > 0 and its H members on the wire.
     */
    public int laneGroupDrops() {
        return laneGroupDrops;
    }

    /** Meter: session demotes issued by the belts (pose-owned / expiry-backlog). */
    public int classDemotes() {
        return classDemotes;
    }

    /** Meter: whole-ship routing→held reverts (decl-recency belt / session fallback). */
    public int routingReverts() {
        return routingReverts;
    }

    /** Meter: repoint rebinds (each = fresh resolver + joint trio reset). */
    public int classResolverRebinds() {
        return classResolverRebinds;
    }

    /** Serve-loop tuning: the decl-recency belt window in ticks (≥1; default ≈2 s at 60/s). */
    public void declRecencyWindowTicks(int ticks) {
        if (ticks < 1) {
            throw new IllegalArgumentException("declRecencyWindowTicks must be >= 1; got " + ticks);
        }
        this.declRecencyWindowTicks = ticks;
    }

    /**
     * Fail-open, loud ((83) law: meter + log): revert this ship from class routing to
     * the held lane for the session. Callers: the per-ship decl-recency belt (the mixing
     * seam) and the serve loop's session-level zero-decl fallback. Any stranded pending
     * declarations flush to expired so the accounting identity stays closed (the FIFO is
     * empty on both current paths — the flush is defensive). Idempotent; false when routing
     * was not on.
     */
    public boolean disableClassRouting(String reason) {
        if (!classRouted) {
            return false;
        }
        classRouted = false;
        routingReverts++;
        int stranded = pendingDeclarations.size();
        if (stranded > 0) {
            declarationsExpired += stranded;
            pendingDeclarations.clear();
            declArrivalTickByDecl.clear(); // The stamp store follows the FIFO
            headServeSinceTick = -1L;
        }
        System.out.println("pilot-fire-class routing-revert reason=" + reason
                + " stranded=" + stranded
                + " (held lane serves FIRE for this ship from here, latched for the session)");
        return true;
    }

    /**
     * Decl-recency belt tick (dispatch calls this ONLY on a routed tick whose selected
     * group is class-S with an empty FIFO — the held-excluded shape where a dead decl stream
     * means a silent ship): a held trigger advances the counter, a released trigger resets
     * it; {@link #enqueueDeclaration} resets it on any accepted declaration. At the window
     * the ship reverts to held LOUD ({@link #disableClassRouting}).
     *
     * <p>D-BELTREVERT amendment: the original premise "held trigger
     * + one window of no decls = dead decl stream" is FALSE under normal play — class-S
     * weapons are the SLOW class, so a held trigger legitimately rides out multi-second
     * refire/flux-lock/ammo-out/out-of-arc pauses that produce zero discharges and therefore
     * zero declarations (one measured run tripped ~100 s in and session-latched 93% of the match
     * onto held). {@code declCapable} carries the engine's own press-gate verdict for the
     * selected group at the pilot's carried aim
     * ({@link EngineCombatBinding#selectedClassSGroupDischargeable}): a tick whose silence
     * the engine explains (nothing could discharge) FREEZES the counter — never advances,
     * never resets, so an alive-but-paused stream cannot trip the belt while a genuinely
     * dead stream still accumulates its window the moment the trigger is satisfiable.
     * Freeze-not-reset keeps detection sound: under a dead stream the served weapons stay
     * ready (nothing ever discharges them), so capable ticks accumulate continuously; the
     * client declares EVERY selected-group discharge (autofire-skip excludes the selected
     * group — {@code EdgeEventLane.declareFireDischarge}), so a
     * capable-and-silent window has no benign explanation. Unreadable seams answer
     * capable=true upstream, so a degraded view degrades to the pre-amendment belt, never
     * to a silenced fail-safe.
     */
    public void noteClassSHeldTick(boolean fireHeld, boolean declCapable) {
        if (!classRouted) {
            return;
        }
        if (!fireHeld) {
            classSHeldNoDeclTicks = 0;
            return;
        }
        if (!declCapable) {
            return; // silence explained by the engine's own press gate — freeze, don't advance
        }
        classSHeldNoDeclTicks++;
        if (classSHeldNoDeclTicks >= declRecencyWindowTicks) {
            disableClassRouting("decl-recency capable_held_ticks=" + classSHeldNoDeclTicks
                    + " window=" + declRecencyWindowTicks);
        }
    }

    /** Belt-1 gate: whether this mount is routed class-S right now (routing on, not demoted,
     *  resolver answers S) — the pose-owned demote only fires on mounts the router owns. */
    public boolean mountRoutedClassS(int mountIndex) {
        return classRouted && effectiveMountClassS(mountIndex);
    }

    private boolean effectiveMountClassS(int mountIndex) {
        return mountIndex >= 0 && !demotedMounts.contains(mountIndex)
                && classResolver.mountIsClassS(mountIndex);
    }

    /**
     * group-law intake gate: whether the mount's GROUP is (still) class-S —
     * every member S ({@link FireClassResolver#groupIsClassS}) and not session-demoted
     * ({@link #demoteMountToHeld} demotes the whole group). An unresolvable group (-1:
     * ungrouped mount, unbound seams, fake bindings) keeps today's mount-level verdict —
     * the same tolerance {@code demoteMountToHeld} and {@code serveDeclaredFire} already
     * extend to -1, so contracts without the group seams stay byte-identical. Mirrors
     * {@link #selectedGroupClassS}'s predicate so intake and dispatch partition by the SAME
     * group class: a decl pends only for a group held is excluded from, never for one held
     * serves (the exclusive per-tick lane choice then cannot starve a co-member).
     */
    private boolean mountGroupRoutedClassS(int mountIndex) {
        int group = classResolver.groupForMount(mountIndex);
        if (group < 0) {
            return true;
        }
        return !demotedGroups.contains(group) && classResolver.groupIsClassS(group);
    }

    /**
     * Belt 2 accounting: one timeout/overflow expiry on a routed mount advances its streak
     * (lane-class drops do NOT — they are the router working as designed); a served shot
     * from the mount resets it ({@link #consumeHeadOnDischarge}).
     */
    private void noteClassExpiry(int mountIndex) {
        if (!classRouted || mountIndex < 0 || demotedMounts.contains(mountIndex)) {
            return;
        }
        int streak = expiryStreakByMount.merge(mountIndex, 1, Integer::sum);
        if (streak >= CLASS_DEMOTE_EXPIRY_STREAK) {
            demoteMountToHeld(mountIndex, "expiry-backlog streak=" + streak);
        }
    }

    /**
     * The reason-keyed FAST demote belt for driver-validation rejects.
     * A validation reject is DETERMINISTIC per regime: the client re-mints the same head
     * class at decl cadence and each re-mint re-rejects identically, so burning the full
     * {@link #CLASS_DEMOTE_EXPIRY_STREAK} on them holds the mount in a seconds-long reject
     * spin before held takes over (3 rejects at decl cadence = the ≈3.1 s
     * held-fire onset window). The SECOND consecutive IDENTICAL reject (same mount + same
     * reason, no intervening serve/consume) demotes immediately
     * ({@code repeat-reject <reason>}); a reason CHANGE only re-arms the memory, so a
     * one-off transient (an arc reject mid-turn) never hair-triggers. Timeout/overflow
     * expiries stay on the streak-3 belt, and every reject still ALSO advances it — a
     * mixed-reason reject run keeps the old 3-strike ceiling. A serve (validation pass)
     * or an observed-discharge consume clears the mount's memory.
     */
    private void noteAdoptReject(int mountIndex, String reason) {
        if (classRouted && mountIndex >= 0 && !demotedMounts.contains(mountIndex)
                && reason.equals(lastAdoptRejectByMount.put(mountIndex, reason))) {
            demoteMountToHeld(mountIndex, "repeat-reject " + reason);
            return;
        }
        noteClassExpiry(mountIndex);
    }

    /**
     * Enqueue one client-declared discharge (FIFO, never conflated). Past
     * {@link #MAX_PENDING_DECLARATIONS} the incoming declaration is dropped and counted BOTH
     * received and expired (capped log) — the organic rate is cooldown-gated by the client's
     * own engine, so a full FIFO means the serve side is wedged, not that the game got faster.
     * Counting overflow as received closes the accounting identity by construction
     * ({@code received == served + expired + pending}; a measured "+334 gap" was exactly
     * this path skipping the received counter). Returns whether enqueued.
     *
     * <p>While the state is on the legacy held lane the declaration is
     * dropped on intake (counted received AND expired, capped log reason {@code lane-held}) —
     * nothing ever serves or consumes a held-lane FIFO, so enqueueing wedges it full and a
     * non-empty FIFO suppressed the selected-group mirror, freezing the hull's selection at
     * round start (the TPC-only lockup). A declaring client at a held server is
     * mode-skew; the capped log is its loud signal.
     *
     * <p>After the lane filters and BEFORE the overflow check, a
     * declaration whose mount holds an unexpired burst-continuation credit (minted by
     * {@link #consumeDeclarationOnDischarge} on an unmatched discharge within
     * {@code declaredFireTimeoutTicks} of a served consume; only after the
     * stateless credit fence passes) is SERVED at intake —
     * the server engine already fired that shot as its burst's own tail. Counted served (+
     * {@link #servedByCredit()}), never enqueued (returns {@code false}), so the identity stays
     * closed and the late decl can never become the orphan head that wedged the FIFO.
     * {@code false} therefore means "not enqueued", not "dropped".
     */
    public boolean enqueueDeclaration(cc.starsector.server.runtime.FireDischargeDeclaration declaration) {
        return enqueueDeclaration(declaration, -1L);
    }

    /**
     * {@link #enqueueDeclaration(cc.starsector.server.runtime.FireDischargeDeclaration)}
     * with the serve tick its drain feeds ({@code applyTick >= 0}; -1 = the legacy 1-arg form, which
     * never arms the {@link DeclaredShotPad}). ONLY the {@code lane-class} branch offers the dropped
     * declaration to the pad; {@code lane-held} and {@code lane-group} never do.
     */
    public boolean enqueueDeclaration(cc.starsector.server.runtime.FireDischargeDeclaration declaration,
            long applyTick) {
        Objects.requireNonNull(declaration, "declaration");
        declarationsReceived++;
        if (!declaredFireLane && !classRouted) {
            declarationsExpired++;
            logExpiry(declaration, "lane-held", -1L);
            return false;
        }
        // Enqueue filter: under class routing ONLY class-S declarations enter the FIFO —
        // the client declares every selected-group discharge unconditionally (no client class
        // mirror), so class-H (and unclassifiable mount -1) decls drop here, reason lane-class
        // (the lane-held analog: expected traffic, counted, capped log). Drops do NOT feed the
        // belt-2 streak — they are the router working as designed, not a wedge.
        if (classRouted && !effectiveMountClassS(declaration.mountIndex())) {
            declarationsExpired++;
            laneClassDrops++;
            logExpiry(declaration, "lane-class", -1L);
            declPad.onClassHDeclaration(applyTick, declaration.clientTick(), declaration.mountIndex(),
                    classResolver, groupIdentityInverse, declaration.shipId());
            return false;
        }
        // The GROUP law at intake — a class-S mount inside a
        // mixed (or demoted) group drops too, reason lane-group, so the whole group rides
        // the held lane (see FireClassResolver javadoc). Same accounting shape as lane-class.
        if (classRouted && !mountGroupRoutedClassS(declaration.mountIndex())) {
            declarationsExpired++;
            laneClassDrops++;
            laneGroupDrops++;
            logExpiry(declaration, "lane-group", -1L);
            return false;
        }
        // A burst continuation the engine already fired spends its credit.
        // The credit path skips the serve-time fence,
        // so it runs the cheap STATELESS fence first — a finite declared ray AND the probe's
        // mount-range + arc read (fail-CLOSED in production). A refusal does NOT spend the
        // credit: the decl falls through to the ordinary enqueue, where the serve-time fence
        // (serveAdoptHead → forceFireNamedMount) rejects it LOUD.
        boolean creditServe = false;
        if (classRouted && continuationCreditLive(declaration.mountIndex())) {
            if (creditFencePasses(declaration)) {
                spendContinuationCredit(declaration.mountIndex());
                creditServe = true;
            } else {
                creditFenceRefusals++;
            }
        }
        if (creditServe) {
            int mount = declaration.mountIndex();
            declarationsServed++;
            servedByCredit++;
            expiryStreakByMount.remove(mount); // belt 2: a served shot resets the streak
            lastAdoptRejectByMount.remove(mount); // A serve clears
            classSHeldNoDeclTicks = 0; // decl-recency belt: the stream is alive
            if (creditLogsEmitted < MAX_CREDIT_LOGS) {
                creditLogsEmitted++;
                System.out.println("pilot-fire-decl credit-served mount=" + mount
                        + " lag=" + lastCreditLagTicks
                        + " ship=" + declaration.shipId()
                        + " decl_seq=" + declaration.declSeq()
                        + (creditLogsEmitted == MAX_CREDIT_LOGS ? " (log capped; counters continue)" : ""));
            }
            return false;
        }
        int cap = classRouted ? MAX_PENDING_DECLARATIONS_ROUTED : MAX_PENDING_DECLARATIONS;
        if (pendingDeclarations.size() >= cap) {
            declarationsExpired++;
            logExpiry(declaration, "overflow", -1L);
            noteClassExpiry(declaration.mountIndex());
            return false;
        }
        pendingDeclarations.addLast(declaration);
        if (applyTick >= 0L) {
            declArrivalTickByDecl.put(declaration, applyTick); // The v16 wait's origin
        }
        classSHeldNoDeclTicks = 0; // decl-recency belt: the stream is alive
        return true;
    }

    /**
     * The ship's hull is gone (a {@code seat_mount_loss} despawn): drop the WHOLE pending
     * FIFO at once. Without this, every declaration still
     * queued — or arriving in flight — for the dead hull waited the full head ε-timeout
     * SERIALLY against a hull no discharge can ever serve (a measured run's signature: ship-2
     * decls at 60 ticks each). Flushed declarations count expired, so
     * {@code received == served + expired + pending} stays closed. Returns the flushed count
     * (the caller logs it with seat/round/tick context; decls arriving AFTER the flush —
     * ≈RTT worth at most — still age out via the head ε-timeout, a bounded residual).
     */
    public int flushPendingOnHullLoss() {
        int flushed = pendingDeclarations.size();
        pendingDeclarations.clear();
        declArrivalTickByDecl.clear(); // The stamp store follows the FIFO
        declarationsExpired += flushed;
        headServeSinceTick = -1L;
        clearContinuationCredits(); // The dead hull's bursts are over
        return flushed;
    }

    /**
     * Drop every burst-continuation credit and served-tick memory (hull loss,
     * repoint rebind — the mount ordinals no longer name the same weapons).
     *
     */
    private void clearContinuationCredits() {
        // Every credit dropped here was never spent — count it.
        for (Integer stack : continuationCreditCountByMount.values()) {
            continuationCreditsExpiredUnspent += stack == null ? 0 : Math.max(0, stack);
        }
        lastServedTickByMount.clear();
        continuationCreditCountByMount.clear();
        continuationCreditNewestTickByMount.clear();
        // The dead roster's burst states go with its credits.
        //
        inBurstAfterLastDischargeByMount.clear();
    }

    /**
     * Whether this mount holds an unexpired continuation credit. A stack older
     * than the credit window ({@code declaredFireTimeoutTicks}, the ≈1 s rate carrier)
     * measured from its mount's NEWEST mint is dropped here and counted
     * into {@link #continuationCreditsExpiredUnspent()} (the whole stack, not 1). Records the
     * live credit's age in {@link #lastCreditLagTicks} (no per-decl allocation on the sim
     * thread).
     */
    private boolean continuationCreditLive(int mountIndex) {
        Integer count = continuationCreditCountByMount.get(mountIndex);
        if (count == null || count <= 0) {
            return false;
        }
        long newest = continuationCreditNewestTickByMount.getOrDefault(mountIndex, Long.MIN_VALUE / 2);
        long lag = lastObservedSimTick - newest;
        if (lag > declaredFireTimeoutTicks) {
            continuationCreditsExpiredUnspent += count; // finder 2: the stale stack, uncounted pre-r3
            continuationCreditCountByMount.remove(mountIndex);
            continuationCreditNewestTickByMount.remove(mountIndex);
            return false;
        }
        lastCreditLagTicks = lag;
        return true;
    }

    /**
     * The stateless fence a credit spend must pass — a
     * finite declared ray (a NaN/degraded ray never spends; the ray-less group-press
     * path is the echo class) AND, when a probe is installed, its fail-closed
     * {@link MountReadinessProbe#declaredRayInArc} read (mount range + the serve fence's own
     * arc predicate and tolerance). A null probe (fake/test path) checks the finite ray only.
     *
     */
    private boolean creditFencePasses(cc.starsector.server.runtime.FireDischargeDeclaration declaration) {
        if (!Float.isFinite(declaration.muzzleX()) || !Float.isFinite(declaration.muzzleY())
                || !Float.isFinite(declaration.bearingDeg())) {
            return false;
        }
        return readinessProbe == null || readinessProbe.declaredRayInArc(declaration.mountIndex(),
                declaration.muzzleX(), declaration.muzzleY(), declaration.bearingDeg());
    }

    /**
     * Spend one continuation credit on this mount (caller proved it live via
     * {@link #continuationCreditLive}).
     */
    private void spendContinuationCredit(int mountIndex) {
        Integer count = continuationCreditCountByMount.get(mountIndex);
        if (count == null || count <= 0) {
            return;
        }
        if (count == 1) {
            continuationCreditCountByMount.remove(mountIndex);
            continuationCreditNewestTickByMount.remove(mountIndex);
        } else {
            continuationCreditCountByMount.put(mountIndex, count - 1);
        }
    }

    /**
     * Declarations served at intake by a burst-continuation credit (never
     * enqueued; counted in {@link #declarationsServed()} too).
     */
    public int servedByCredit() {
        return servedByCredit;
    }

    /**
     * Continuation credits minted by unmatched discharges within the window of
     * a served consume (contract/diagnostic meter).
     */
    public int continuationCreditsMinted() {
        return continuationCreditsMinted;
    }

    /**
     * Credits dropped WITHOUT a spend — a stale stack at intake
     * (the whole stack), a hull-loss flush, a repoint rebind. Round line {@code credit_expired=}.
     *
     */
    public int continuationCreditsExpiredUnspent() {
        return continuationCreditsExpiredUnspent;
    }

    /**
     * in-burst unmatched discharges refused a credit by
     * {@link #CONTINUATION_CREDIT_CAP_PER_MOUNT}. Round line {@code credit_capped=}.
     *
     */
    public int continuationCreditsCapped() {
        return continuationCreditsCapped;
    }

    /**
     * Decls that found a live credit but failed the
     * stateless fence (non-finite ray / probe arc-range refusal) and fell through to the
     * ordinary enqueue unspent. Round line {@code credit_fence_refused=}.
     *
     */
    public int creditFenceRefusals() {
        return creditFenceRefusals;
    }

    /**
     * Unmatched discharges on a routed class-S mount inside the
     * served-consume window that the installed probe says are NOT mid-burst — the old law would
     * have minted a credit for them (the group-FIRE echo / server&gt;client divergence class);
     * now they mint nothing and surface here. Round line {@code discharge_unmatched_nonburst=}.
     * "not mid-burst" = not in burst NOW and not in burst at this mount's
     * previous discharge edge (the pre-tick state), so a burst's legit LAST shot is no longer
     * counted here — it mints and counts {@link #lastShotCreditsMinted()} instead.
     *
     */
    public int dischargeUnmatchedNonburst() {
        return dischargeUnmatchedNonburst;
    }

    /**
     * The subset of {@link #continuationCreditsMinted()} minted on a burst's
     * LAST shot — the probe read not-in-burst now but in-burst at the mount's previous discharge
     * edge (the engine clears isInBurst inside the last shot's firing call). Cumulative, no drain,
     * read on the sim thread (the {@code continuationCreditsMinted} pattern); for the round line.
     *
     */
    public int lastShotCreditsMinted() {
        return lastShotCreditsMinted;
    }

    /**
     * The unspent continuation credits stacked on this mount right now
     * (contract read; 0 when none).
     */
    public int continuationCreditsOnMount(int mountIndex) {
        Integer count = continuationCreditCountByMount.get(mountIndex);
        return count == null ? 0 : count;
    }

    /**
     * The hull despawned — forget the retreat bit-9 edge memory so the
     * NEXT applied frame (a fresh client frame sampled off the refield hull) starts from a
     * clean edge baseline. Without this, a stale {@code prevRetreatBit=true} fires a phantom
     * falling edge ({@code turnOffTravelDrive}) at the first bit-9=0 frame — during the
     * refield fly-in burn, which the ENGINE owns. The staged-frame
     * drop at the same seam ({@code PilotIntentStep.clearStagedIntentOnHullLoss}) is the
     * load-bearing half; this keeps the survivor state edge-consistent with it.
     *
     * <p>Adds the re-arm latch: the staged drop cannot stop a FRESH frame whose
     * bit-9 is still held TRUE (a client whose local hull is mid ghost-dive keeps sampling
     * {@code isRetreating()==true}) — measured evicting the refield hull one tick after fly-in
     * (t22096→t22097). Until a bit-false frame arrives, TRUE levels are skipped
     * (counted in {@code retreat_rearm_skips=}); a genuine post-refield retreat still works —
     * the client's fresh hull samples false first, clearing the latch.
     */
    public void resetRetreatEdgeOnHullLoss() {
        resetRetreatEdgeOnHullLoss(-1L);
    }

    /**
     * {@link #resetRetreatEdgeOnHullLoss()} plus the {@link DeclaredShotPad}
     * tenure clear stamped at {@code tenureStartTick} (the sim tick of the clear; -1 = unknown,
     * the no-arg form: the pad + history still clear, the stamp stays). Every hull-tenure edge
     * (hull loss, command transfer, module seat) reaches it via
     * {@code PilotIntentStep.clearStagedIntentOnHullLoss}.
     */
    public void resetRetreatEdgeOnHullLoss(long tenureStartTick) {
        declPad.clearForTenure(tenureStartTick);
        prevRetreatBit = false;
        retreatRearmPending = true;
        diveOwnsRetreat = false; // The dived hull is gone; the refield hull's lane is live again
        // the latch clears on ONE bit-false frame, which a recorded run
        // proved insufficient for a transfer re-key — the client's local AI leftovers flickered
        // bit-9 false→true right after the latch cleared and the rising edge engaged the travel
        // drive on the re-adopted hull (W3-J root). Require K consecutive FALSE frames
        // before any rising edge may engage; a real player retreat passes (command-UI latency
        // ≫ K frames). Shared with the hull-loss refield arm (strictly safer there too).
        retreatEdgeQuarantineFalseLeft = PilotCommandApplier.RETREAT_EDGE_QUARANTINE_FALSE_FRAMES;
        // The dead hull's input memory dies with it. The vent level and the
        // fire-edge seq + latch were sampled off / clicked on the old body: a click made while
        // dead would otherwise arm a synthetic press on the fresh hull (the onSessionBoundary
        // class — the next applied frame inits the seq, never arms). The refield gate itself is
        // armed by the fly-in seam (armRefieldGateIfPending), which knows the fly-in tick.
        prevVentBit = false;
        fireEdge.lastFireEdgeSeq = -1;
        fireEdge.latchArmed = false;
        fireEdge.latchSawLostTap = false;
        refieldGatePending = true;
        refieldGateArmed = false;
        refieldGateAdoptEcho = -1L;
    }

    /**
     * The seat's fresh hull flew in at {@code flyInTick} (the first snapshot
     * tick that carries it) after a hull loss on this wire id: arm the refield stale-frame gate.
     * Arms ONLY when a hull loss preceded it in this state's life ({@link
     * #resetRetreatEdgeOnHullLoss}), so a first mount or a late join never arms — and a round
     * open cannot: {@code frameSeq} is not round-qualified and the states die at the wrap.
     * Returns whether it armed (the caller's log token).
     */
    public boolean armRefieldGateIfPending(long flyInTick) {
        if (!refieldGatePending) {
            return false;
        }
        refieldGatePending = false;
        refieldGateArmed = true;
        refieldGateTick = flyInTick;
        refieldGateAdoptEcho = -1L;
        refieldGateHeldTicks = 0;
        return true;
    }

    /**
     * Refield stale-frame gate — ONE call per apply tick, before any lane.
     * Invariant: no frame sampled off a dead hull may press anything on the refielded hull.
     * Measured: the kit keeps sampling its DEAD own hull until its respawn edge
     * ({@code captureOwnCommands} runs before the swap), so for ≈ one round trip after a fly-in
     * the frames still carry the dead body's levels — vent held there pressed VENT on the fresh
     * hull on three ticks, and a venting hull cannot pay the travel drive's upkeep, so the
     * fly-in burn ended after one tick.
     * The vent / cloak / shield / system lanes act on LEVELS, so no
     * edge-memory reset can stop it: the frame itself is withheld.
     *
     * <p>Release is frame evidence, no wire change — {@code frameSeq} = the client's echo of the
     * last snapshot tick it received (the mask gate's proof, {@code
     * PilotCommandApplier.maskStalenessGateHolds}): an echo below the fly-in tick = sampled
     * before the client saw the fresh hull ⇒ hold; the FIRST echo at or above it may still be
     * the pre-swap sample ⇒ record + hold; a later echo ⇒ release for the tenure. Known limit:
     * a second dead sample can carry a later echo — a publish
     * race, a 250 ms heartbeat, a kit hold / retry on its respawn edge, a welded client; the
     * kit half (neutral frames while its {@code ownKilled} latch is set) closes those, this gate
     * is the server's floor. A client that never echoes the fresh hull stays held (AI-suppressed).
     * An echo at or above the tick being served is never the adopt candidate: an
     * honest echo trails the apply tick, and adopting a higher one held the seat to the round end.
     * Cost: fresh input is delayed, never lost, by at most ONE snapshot interval at the burn's
     * start (VENT, aim, target and the autofire mirror are engine-live there; movement, fire,
     * system, shield and cloak are engine-blocked). One bounded release row per refield.
     */
    boolean refieldGateHolds(long frameSeq, long simTick, long wireId) {
        if (!refieldGateArmed) {
            return false;
        }
        if (refieldGateAdoptEcho >= 0 && frameSeq > refieldGateAdoptEcho) {
            refieldGateArmed = false;
            System.out.println("npeer-serve refield_gate release wire_id=" + wireId
                    + " tick=" + simTick + " fly_in_tick=" + refieldGateTick
                    + " ticks_held=" + refieldGateHeldTicks + " adopt_echo=" + refieldGateAdoptEcho
                    + " echo=" + frameSeq);
            return false;
        }
        if (frameSeq >= refieldGateTick && frameSeq < simTick && refieldGateAdoptEcho < 0) {
            refieldGateAdoptEcho = frameSeq;
        }
        refieldGateHeldTicks++;
        return true;
    }

    /** Whether the refield gate is armed (contract read + the arm-site log). */
    public boolean refieldGateArmed() {
        return refieldGateArmed;
    }

    /** Apply ticks withheld under the current / last arm (contract read). */
    public int refieldGateHeldTicks() {
        return refieldGateHeldTicks;
    }

    /**
     * The seat's hull started an emergency dive (F4c rising edge of the
     * engine's own {@code PhaseAnchorScript.emergencyDive}): freeze the whole bit-9 lane
     * until hull loss. A committed dive is ENGINE-imposed retreat state — the script
     * re-asserts {@code setRetreating(true)} every frame
     * while the ghost client's bit-9 goes false, and the level re-assert then
     * clears the flag every tick BEFORE {@code advanceInner} runs the CFM reaper, so the
     * reaper (which reads the flag before the entity pass re-sets it) never sees
     * {@code isRetreating()==true} and the dived hull is never removed. Measured on a recorded
     * run: {@code retreat_reasserts=2981} == ticks from dive t2292 to round end 5274;
     * offline (no apply lane) the reaper fires 1 frame past terminus. The walk rows
     * sample POST-frame — after the script's re-set — which is why every row read
     * {@code retreating=true} while the reaper never did (the within-frame flap).
     */
    public void latchDiveOwnsRetreat() {
        diveOwnsRetreat = true;
    }

    /** Retreat dive-freeze ticks (test + one-shot log seam). */
    public int retreatDiveFreezeTicks() {
        return retreatDiveFreezeTicks;
    }

    /** bit-11 cloak toggles withheld by the dive freeze ({@link #noteCloakDiveFreeze}). */
    public int cloakDiveFreezeTicks() {
        return cloakDiveFreezeTicks;
    }

    /**
     * The bit-11 sibling of the retreat freeze. Vanilla's dive script
     * ({@code PhaseAnchorScript}) forces the diving hull's cloak
     * IN every frame and blocks only {@code USE_SYSTEM}; listeners advance before commands, so a
     * bit-11 mismatch toggle dropped the cloak OUT for a frame on every mismatched tick (the
     * phased flicker seen on a recorded flight is read as this class [I]). While {@link #diveOwnsRetreat}
     * is latched: count the withheld tick, log ONE line on the first, return true (the caller
     * sends nothing); else false. The caller reaches here only on a MISMATCHED tick, so the
     * counter = toggles withheld. Hull loss clears the latch ({@link #resetRetreatEdgeOnHullLoss})
     * = unfrozen exactly like the retreat freeze.
     */
    boolean noteCloakDiveFreeze() {
        if (!diveOwnsRetreat) {
            return false;
        }
        cloakDiveFreezeTicks++;
        if (cloakDiveFreezeTicks == 1) {
            System.out.println("EngineCombatBinding: cloak lane frozen (dive latch) — "
                    + "bit-11 toggles withheld until hull loss");
        }
        return true;
    }

    /**
     * The seat REFIELDED onto a DIFFERENT variant ({@code seat_variant_repoint}
     * fired): arm the mask-staleness gate. The client's intent sampler keeps reading the DEAD
     * hull's autofire/selected/tube trio until its own respawn edge (client {@code captureOwnCommands}
     * runs before the snapshot-edge {@code respawnOwn} ref swap), so the first
     * applied frame(s) after a cross-variant repoint would re-arm the fresh hull with the old
     * variant's mask (the phantom volleys). Same-variant refields do NOT arm —
     * their stale sample equals the hull's own preference baseline and the disarm +
     * re-convergence were measured benign (114848 controls t3042/t5804 silent).
     *
     * <p>Snapshot S = {@link #lastWireMask} (the frozen pre-death baseline the release predicate
     * diffs against). Returns {@code false} = no-arm: no PRESENT mask was ever tracked (boot
     * fly-ins — pre-adopt masks are 0/absent, benign against the all-off hull after its build-time disarm);
     * the caller logs the reason.
     */
    public boolean armMaskStalenessGate(long refieldTick) {
        if (lastWireMask == Integer.MIN_VALUE) {
            return false;
        }
        maskGateArmed = true;
        maskGateRefieldTick = refieldTick;
        maskGateStaleMask = lastWireMask;
        maskGateAdoptEcho = -1L;
        maskGateSuppressedFrames = 0;
        return true;
    }

    /**
     * Hull loss (or a release) drops the gate — each refield decides fresh at its own
     * repoint (the arm is edge-driven, never a level).
     */
    public void clearMaskStalenessGate() {
        maskGateArmed = false;
        maskGateAdoptEcho = -1L;
    }

    /** Mask-gate arm-site log seam ({@code stale_mask=0x}) + tests. */
    public int maskGateStaleMask() {
        return maskGateStaleMask;
    }

    /**
     * The declaration the declared lane should drive its aimed fire command with this tick, or
     * {@code null} when none is pending. Owns the head's ε-timeout: the clock starts the tick a
     * declaration becomes head; past {@link #DECLARED_FIRE_TIMEOUT_TICKS} it is dropped as expired
     * (counter + capped log) and the NEXT head starts a fresh clock this tick.
     * This is the probe-less form (no futile-head belt).
     */
    public cc.starsector.server.runtime.FireDischargeDeclaration headDeclarationToServe(long simTick) {
        return headDeclarationToServe(simTick, null);
    }

    /**
     * {@link #headDeclarationToServe(long)} plus the futile-head
     * EARLY expiry belt: after the ε-timeout branch, a head that has been head for ≥ 1 tick
     * expires NOW (reason {@code timeout-futile}, same accounting as a timeout: expired++,
     * capped log, next head starts a fresh clock this tick; NOT the
     * belt-2 streak — a futile orphan is not backlog evidence) when the probe
     * proves it cannot serve inside its remaining ε-window — its mount's cooldown reads
     * finite, the mount is NOT mid-burst/firing (a burst gap reads a 3.88→0.55
     * cooldown sawtooth that is not a clock — never futile), and {@code cooldownSec × rate}
     * exceeds the ticks left. Rate carrier = {@code declaredFireTimeoutTicks} (the ≈1 s tick
     * count; = 60 at 60 Hz), the same convention the alternating-dwell machine uses. A null
     * probe, or a NaN cooldown (unreadable seam), is fail-open: the head waits for the
     * ordinary timeout exactly as before. Also records {@link #lastObservedSimTick} (the credit
     * window's clock).
     */
    public cc.starsector.server.runtime.FireDischargeDeclaration headDeclarationToServe(
            long simTick, MountReadinessProbe probe) {
        lastObservedSimTick = simTick;
        cc.starsector.server.runtime.FireDischargeDeclaration head = pendingDeclarations.peekFirst();
        if (head == null) {
            headServeSinceTick = -1L;
            return null;
        }
        if (headServeSinceTick < 0L) {
            headServeSinceTick = simTick;
            resetHeadTenure(); // A fresh head starts a fresh tenure record
        } else if (simTick - headServeSinceTick >= declaredFireTimeoutTicks) {
            pendingDeclarations.pollFirst();
            forgetArrival(head);
            declarationsExpired++;
            logExpiry(head, refinedTimeoutReason(), simTick - headServeSinceTick);
            // A head that waited out a fire lock
            // (timeout-locked) is NOT backlog evidence — the futile-head precedent below.
            if (headTenureLockWaitTicks == 0) {
                noteClassExpiry(head.mountIndex()); // belt 2: unserved head = backlog evidence
            }
            head = pendingDeclarations.peekFirst();
            headServeSinceTick = head == null ? -1L : simTick;
            resetHeadTenure();
        }
        if (head != null && probe != null && headIsFutile(head, simTick - headServeSinceTick, probe)) {
            long tenure = simTick - headServeSinceTick;
            pendingDeclarations.pollFirst();
            forgetArrival(head);
            declarationsExpired++;
            futileHeadExpiries++;
            logExpiry(head, "timeout-futile", tenure);
            // NO noteClassExpiry — a futile orphan is not
            // backlog evidence (belt 2's purpose, :noteClassExpiry). Counting it let three
            // cooldown-divergent decls demote a class-S mount in ~6 ticks instead of ~180.
            head = pendingDeclarations.peekFirst();
            headServeSinceTick = head == null ? -1L : simTick;
            resetHeadTenure();
        }
        return head;
    }

    /**
     * Whether the probe proves this head cannot discharge inside its
     * remaining ε-window (see {@link #headDeclarationToServe(long, MountReadinessProbe)}).
     * Legacy mount -1 is never futile.
     */
    private boolean headIsFutile(cc.starsector.server.runtime.FireDischargeDeclaration head,
            long tenure, MountReadinessProbe probe) {
        int mount = head.mountIndex();
        if (tenure < 1L || mount < 0) {
            return false;
        }
        float cd = probe.mountCooldownRemainingSec(mount);
        if (!Float.isFinite(cd) || probe.mountInBurstOrFiring(mount)) {
            return false;
        }
        return cd * declaredFireTimeoutTicks > (declaredFireTimeoutTicks - tenure);
    }

    /** Heads expired early as {@code timeout-futile}. */
    public int futileHeadExpiries() {
        return futileHeadExpiries;
    }

    /**
     * Live per-tick readiness oracle for the declared serve — whether the
     * weapon at this mount ordinal could BEGIN a discharge if FIRE were pressed this tick
     * (idle: not in cooldown/chargeup/burst, not disabled, has ammo, ship not
     * overloaded/venting, flux headroom covers the shot — the engine's own press gate).
     * The
     * oracle is AIM-AWARE — it also refuses a mount whose LIVE arc excludes the head's
     * carried aim world point ({@code WeaponAPI.distanceFromArc(aim) > 0}): the engine's
     * group dispatch fires a member only when the aim is in-arc
     * ({@code weaponsThatWouldBeFiredAtPoint}),
     * so pressing for an out-of-arc named mount is provably
     * futile and only strobes the group's in-arc mounts (8 timeout heads
     * owned ~87% of 493 presses, 6-7 of 8 bearing-refused). The read MUST be the
     * live engine arc (static hull-arc data mis-verdicted all 8 heads) and re-runs every
     * tick — a head whose aim rotates into arc as the ship turns still serves. Production:
     * an {@link EngineCombatBinding#mountReadinessProbeForShip} closure over live WeaponAPI
     * reads; contracts: synthetic. Any unreadable seam answers {@code true} (press — the
     * ungated path), so the gate can only ever WITHHOLD a press it can prove is futile.
     */
    public interface MountReadinessProbe {
        /**
         * Whether the weapon at this mount ordinal could begin a discharge THIS tick,
         * aimed at the head's carried world point (out-of-arc = not dischargeable).
         */
        boolean mountDischargeableNow(int mountIndex, float aimWorldX, float aimWorldY);

        /**
         * The RESOURCE-only readiness question the adopt serve asks —
         * cooldown/chargeup/ammo/disabled/flux, NO arc term (the adopt serve validates the
         * DECLARED ray's bearing in its own driver; the head's cursor aim is irrelevant to
         * a named-mount force-fire). Default rides the aim-aware read with a NaN aim: the
         * production probe skips the arc gate on any non-finite aim by construction
         * ({@code mountDischargeableNow}'s finite-aim guard), so no implementor changes.
         */
        default boolean mountResourceReadyNow(int mountIndex) {
            return mountDischargeableNow(mountIndex, Float.NaN, Float.NaN);
        }

        /**
         * The weapon's remaining cooldown in seconds
         * ({@code WeaponAPI.getCooldownRemaining}), or NaN when unreadable — NaN is
         * fail-open (the futile-head belt never fires; the head waits for the ε-timeout).
         *
         */
        default float mountCooldownRemainingSec(int mountIndex) {
            return Float.NaN;
        }

        /**
         * Whether the weapon is mid-burst / firing — in a burst gap the
         * cooldown read is a sawtooth, not a clock, so the futile belt must stand
         * down. Default false; paired with the NaN cooldown default it never fires.
         *
         */
        default boolean mountInBurstOrFiring(int mountIndex) {
            return false;
        }

        /**
         * The stateless fence a continuation-credit
         * spend must pass — the mount ordinal is in range AND the DECLARED ray (a far point
         * down {@code muzzle + 1000·û(bearing)}) is within {@link #ADOPT_ARC_TOL_DEG} of the
         * weapon's live arc, the serve-time fence's own read. Production is fail-CLOSED (any
         * unreadable seam/weapon answers false ⇒ the decl falls through to the LOUD serve
         * fence). Default true: fakes keep today's behaviour.
         *
         */
        default boolean declaredRayInArc(int mountIndex, float muzzleX, float muzzleY, float bearingDeg) {
            return true;
        }
    }

    /**
     * The adopt-serve write seam — validate + force-fire one ray-carrying
     * declaration's NAMED mount. Production = the fail-CLOSED
     * {@link EngineCombatBinding#mountFireDriverForShip} closure; contracts install fakes.
     * Returns {@code null} = force-fire issued (the head stays pending for the observed
     * discharge consume / ε-clock retry); else the LOUD reject reason the state drops the
     * head with ({@code adopt-range/adopt-eps/adopt-arc/adopt-speed/adopt-seam}). Design
     * deviation: the driver takes the whole head (+ tick), not a
     * bare mount ordinal — muzzle-ε/arc/rate validation and the registry post need the
     * declared ray and the sim clock, which a one-int signature cannot carry.
     */
    public interface MountFireDriver {
        String forceFireNamedMount(
                long simTick, cc.starsector.server.runtime.FireDischargeDeclaration head);
    }

    private MountReadinessProbe readinessProbe; // null = always press (the ungated path / fake path)
    private MountFireDriver mountFireDriver; // null = adopt lane dormant (ray heads expire LOUD)

    /** Install the serve loop's live readiness oracle (null = always press). */
    public void mountReadinessProbe(MountReadinessProbe probe) {
        this.readinessProbe = probe;
    }

    /** Install the adopt-serve driver (null = ray heads expire, never press). */
    public void mountFireDriver(MountFireDriver driver) {
        this.mountFireDriver = driver;
    }

    /**
     * The hull-level fire-lock read the adopt serve asks
     * before force-firing (D2). The adopt lane's {@code setForceFireOneFrame} passes every
     * command block and hold-fire, so without this gate a ray head fires through the deploy burn
     * and every other {@code noFiring} system lock vanilla's trigger refuses. Production = the
     * {@link EngineCombatBinding#fireLockProbeForShip} closure: the UNION of the command filter's
     * refusal set ({@code getBlockedCommands()} holds the internal USE_SELECTED_GROUP)
     * and vanilla's click gate (ship
     * system / travel drive / phase cloak {@code shouldBlockCommands() && !isFiringAllowed()}),
     * read at apply time (before this tick's advance: it can only UNDER-report by one tick, never
     * strand a head — the ε-clock bounds every wait). Contracts install synthetic lambdas.
     */
    @FunctionalInterface
    public interface FireLockProbe {
        /** Whether the hull's own fire lock refuses a human trigger THIS tick. */
        boolean fireLockedNow();
    }

    private FireLockProbe fireLockProbe; // null = never locked (fake bindings: today's path verbatim)

    /** Install the serve loop's fire-lock probe (null = never locked). */
    public void fireLockProbe(FireLockProbe probe) {
        this.fireLockProbe = probe;
    }

    /**
     * The OPTION-A adopt serve: serve the head
     * declaration when it carries the client's committed ray, by validating fail-CLOSED and
     * force-firing its NAMED mount — no SELECT_GROUP, no group-level press, so the
     * strobe channel is closed by construction for v4 heads. Flow per tick: run
     * {@link #headDeclarationToServe}'s ε-timeout machinery verbatim; a ray-less head or a
     * missing driver returns null (the legacy press path / LOUD timeout own those); while the
     * installed {@link FireLockProbe} reports the hull's fire lock, the head waits silently
     * ({@link #declLockWaitTicks}; expiry reason {@code timeout-locked}, outside
     * belt 2); a head whose mount is not RESOURCE-ready waits silently ({@link #declPressWaitTicks} — same
     * meter as press waits, same ε-clock bound); a driver reject drops the head LOUD as
     * expired with the driver's reason (counted per-reason for the round line, plus the
     * belt-2 class-expiry note — an unserved head is backlog evidence regardless of
     * why it could not serve); a driver null return = force-fire issued
     * ({@link #declAdoptFireIssued}) and the head STAYS pending until the observed
     * own-ship discharge consumes it (consume law unchanged) or the ε-clock expires it
     * (the engine-refused-force-fire retry; the engine's own cooldown gate is the rate
     * floor). On the head's force-fire tick its SAME-{@code clientTick} volley
     * mates batch-serve in the same tick ({@link #serveVolleyMates}, the volley-mate
     * fix: the one-head-per-tick law alone serialized synced volleys 1 shot/tick).
     * Returns the head on the force-fire tick so the serve loop can report a serve was
     * driven; null otherwise. The head read passes the installed probe
     * (futile-head early expiry).
     */
    public cc.starsector.server.runtime.FireDischargeDeclaration serveAdoptHead(long simTick) {
        // The installed probe also arms the futile-head early expiry.
        cc.starsector.server.runtime.FireDischargeDeclaration head =
                headDeclarationToServe(simTick, readinessProbe);
        if (head == null || !head.hasCarriedRay() || mountFireDriver == null) {
            return null;
        }
        // D2 — the hull's fire lock holds ⇒ WAIT (never a
        // reject, no new number): the head stays pending, the ε-clock above bounds it, and a
        // lock tick is NOT also a resource wait (declPressWaitTicks untouched).
        if (fireLockProbe != null && fireLockProbe.fireLockedNow()) {
            declLockWaitTicks++;
            headTenureWaitTicks++;
            headTenureLockWaitTicks++;
            return null;
        }
        if (readinessProbe != null && !readinessProbe.mountResourceReadyNow(head.mountIndex())) {
            declPressWaitTicks++;
            headTenureWaitTicks++;
            return null; // resources not ready (cooldown/flux/ammo) — retry next tick, ε-bounded
        }
        String reject = mountFireDriver.forceFireNamedMount(simTick, head);
        if (reject == null) {
            declAdoptFireIssued++;
            headTenureAdoptFires++;
            lastAdoptRejectByMount.remove(head.mountIndex()); // Validation pass clears
            serveVolleyMates(simTick, head);
            return head;
        }
        long waited = headServeSinceTick < 0L ? 0L : simTick - headServeSinceTick;
        pendingDeclarations.pollFirst();
        forgetArrival(head);
        declarationsExpired++;
        declAdoptRejects++;
        adoptRejectsByReason.merge(reject, 1, Integer::sum);
        logExpiry(head, reject, waited);
        noteAdoptReject(head.mountIndex(), reject); // Deterministic reject = fast belt
        cc.starsector.server.runtime.FireDischargeDeclaration next = pendingDeclarations.peekFirst();
        headServeSinceTick = next == null ? -1L : simTick;
        resetHeadTenure(); // The reject ended the tenure; the next head starts fresh
        return null;
    }

    /**
     * The W5-D7 volley batch: on the tick the HEAD force-fires, also serve
     * every remaining SAME-{@code clientTick} ray-carrying declaration in the FIFO (the client
     * frame's synced-group volley — its engine discharged them all in ONE frame, so the faithful
     * serve re-emits them in ONE server tick; fix target = vanilla linked-group simultaneity).
     * Pre-batch, {@link #serveAdoptHead}'s one-head-per-tick law serialized a
     * 5-mount volley across 5 consecutive ticks in FIFO order — exactly the wire signature the
     * reel recon measured (52/59 volleys spread-4, fixed order = decl mint order).
     * Per-mate law = the head's own, unchanged: resource-readiness waits silently (shared
     * {@link #declPressWaitTicks} meter; a not-ready mate stays PENDING and serves at its own
     * head tenure — engine-faithful: vanilla too only discharges READY members), a driver
     * reject drops the mate LOUD mid-FIFO (same counters/log/belt-2 note as a head reject),
     * and a served mate stays pending until its own mount's observed discharge consumes it
     * ({@code consumeDeclarationOnDischarge} walks the whole FIFO by mount identity — same-tick
     * multi-discharge consume already holds). At most one force-fire per mount
     * per tick (a same-mount dup defers to its own head tenure; the driver's per-mount rate
     * law adjudicates it there). {@code clientTick} stays a GROUPING key only, never a serve
     * gate — a lone decl batches nothing and serves exactly as before.
     */
    private void serveVolleyMates(long simTick,
            cc.starsector.server.runtime.FireDischargeDeclaration head) {
        if (pendingDeclarations.size() < 2 || mountFireDriver == null) {
            return;
        }
        java.util.HashSet<Integer> mountsFiredThisTick = new java.util.HashSet<>();
        mountsFiredThisTick.add(head.mountIndex());
        long waited = headServeSinceTick < 0L ? 0L : simTick - headServeSinceTick;
        java.util.Iterator<cc.starsector.server.runtime.FireDischargeDeclaration> it =
                pendingDeclarations.iterator();
        boolean atHead = true;
        while (it.hasNext()) {
            cc.starsector.server.runtime.FireDischargeDeclaration mate = it.next();
            if (atHead) {
                atHead = false;
                continue; // the head already force-fired this tick
            }
            if (mate.clientTick() != head.clientTick() || !mate.hasCarriedRay()
                    || mountsFiredThisTick.contains(mate.mountIndex())) {
                continue;
            }
            if (readinessProbe != null
                    && !readinessProbe.mountResourceReadyNow(mate.mountIndex())) {
                declPressWaitTicks++;
                continue; // not ready — stays pending on the per-head law (serves at readiness)
            }
            String reject = mountFireDriver.forceFireNamedMount(simTick, mate);
            if (reject == null) {
                declAdoptFireIssued++;
                declAdoptBatchServed++;
                mountsFiredThisTick.add(mate.mountIndex());
                lastAdoptRejectByMount.remove(mate.mountIndex()); // Pass clears
                continue;
            }
            it.remove();
            forgetArrival(mate);
            declarationsExpired++;
            declAdoptRejects++;
            adoptRejectsByReason.merge(reject, 1, Integer::sum);
            logExpiry(mate, reject, waited);
            noteAdoptReject(mate.mountIndex(), reject); // Fast belt
        }
    }

    /** Volley mates force-fired in their head's serve tick (≈groupSize−1 per volley). */
    public int declAdoptBatchServed() {
        return declAdoptBatchServed;
    }

    /** adopt-serve validation constant: max |declared muzzle − server mount| (world units). */
    public static final float ADOPT_MUZZLE_EPS = 300f;
    /** adopt-serve validation constant: max off-arc degrees for the declared bearing. */
    public static final float ADOPT_ARC_TOL_DEG = 15f;
    /**
     * THE FIND fold: max |declared speed − server spec muzzle speed|
     * (world units/s). The adopted ray's SPEED crosses the wire into authoritative newborn
     * velocity, so it needs a component law like position (ε) and bearing (arc). Since PDCL
     * v5 the honest declared speed is the SHIP-frame launch speed — the
     * client engine's own launch·û(ray) with the ship velocity subtracted — so a bolt
     * deviates from the mount's stat-modified {@code WeaponAPI.getProjectileSpeed()} only by
     * spec variance, and a MISSILE by (max − launch) speed: that read is the missile's MAX
     * speed (csv "proj speed"), not its launch speed — Salamander 400 vs 50, Swarmer 300 vs
     * 100; the widest vanilla gap is amsrm 1000 vs 200 = 800 (outside this band — the
     * pre-v5 travel-frame speed sat there too, so no regression; the tightening path is
     * {@code MissileSpecAPI.getLaunchSpeed()} as the missile spec seam, queued). History:
     * the v4 travel-frame law measured via {@code AdoptServeReplay} over two recorded
     * reels — honest max non-missile deviation 611.7 wu/s, value = 700f ≈
     * measured max + 14% margin. A too-tight value drops REAL client shots (a minted
     * defect), and the per-reason LOUD reject meter surfaces any residual at the watch —
     * bump ONLY with a new measured receipt. A hostile declared magnitude (the review's
     * 1e30 vector) rejects {@code adopt-speed} LOUD.
     * The sub-50 "degenerate floor" skip is RETIRED: in the ship frame a
     * Salamander's honest speed IS 50.0, and the client owns the degenerate-read recompose
     * (facing + launch speed) — every declared speed now rides this one band.
     */
    public static final float ADOPT_SPEED_SLACK = 700f;

    /**
     * The adopt-serve validation LAW — pure static so contract row (i)
     * exercises it game-free; the production driver closure gathers the reflective inputs.
     * {@code NaN} muzzle deltas / arc distance / speeds and a false
     * {@code projectileSpec} (beam / unreadable spec — the class-S fence) mean an
     * UNREADABLE seam ⇒ {@code adopt-seam} (fail-CLOSED — the packet law, deliberately
     * opposite the press path's fail-open).
     * {@code declaredSpeed} vs {@code specSpeed} is the component law: every
     * adopted-vector component gets a law, and speed was the missing one ⇒
     * {@code adopt-speed} outside {@link #ADOPT_SPEED_SLACK} (one band, no degenerate
     * floor since PDCL v5). The tick-based per-mount rate term ({@code adopt-rate},
     * with its same-seq retry exemption) is RETIRED: the engine's cooldown
     * gate + the readiness probe are the physical floor; the spec-derived cap sat above the
     * live ROF-multiplied floor and dropped real volleys. Returns null = valid.
     */
    public static String adoptValidationFailure(
            int mountIndex, int mountCount,
            float muzzleDx, float muzzleDy,
            float arcDistDeg,
            float declaredSpeed, float specSpeed,
            boolean projectileSpec) {
        if (mountIndex < 0 || mountIndex >= mountCount) {
            return "adopt-range";
        }
        if (Float.isNaN(muzzleDx) || Float.isNaN(muzzleDy) || Float.isNaN(arcDistDeg)
                || Float.isNaN(declaredSpeed) || Float.isNaN(specSpeed)
                || !projectileSpec) {
            return "adopt-seam";
        }
        if (muzzleDx * muzzleDx + muzzleDy * muzzleDy > ADOPT_MUZZLE_EPS * ADOPT_MUZZLE_EPS) {
            return "adopt-eps";
        }
        if (arcDistDeg > ADOPT_ARC_TOL_DEG) {
            return "adopt-arc";
        }
        if (Math.abs(declaredSpeed - specSpeed) > ADOPT_SPEED_SLACK) {
            return "adopt-speed";
        }
        return null;
    }

    /** named-mount force-fires issued for ray-carrying heads. */
    public int declAdoptFireIssued() {
        return declAdoptFireIssued;
    }

    /** Ray heads dropped by adopt validation (counted into expired too). */
    public int declAdoptRejects() {
        return declAdoptRejects;
    }

    /** per-reason adopt reject counts (round-line detail), never null. */
    public java.util.Map<String, Integer> adoptRejectsByReason() {
        return java.util.Collections.unmodifiableMap(adoptRejectsByReason);
    }

    /** Meter: dwell-machine tube advances crossed this state's round. */
    public int altTubeDwellAdvances() {
        return altTubeDwellAdvances;
    }

    /**
     * The held-dwell machine tick. Called once per tick the HELD lane
     * actually issued FIRE with the wire trigger genuinely down ({@code fired && cmd.fire()})
     * on a hull whose selected group is ALTERNATING. Mirrors vanilla's mid-hold dwell advance
     * (player-ship-only in vanilla) and therefore
     * dead on a server-driven hull: accumulate CONSECUTIVE held ticks and
     * answer {@code true} exactly when the engine's own dwell law would advance the active
     * tube — the caller ({@code EngineReflectionBinding#advanceAlternatingHeldDwell}) then
     * conforms the pointer via the engine's own {@code setCurrentActive}. A non-consecutive
     * tick or a group change resets the accumulator (vanilla zeroes {@code elapsed} on every
     * non-fired frame); {@code thresholdSeconds} NaN/negative = NEVER
     * (non-burst beams, unreadable spec — fail-soft). The compare is
     * {@code heldTicks > seconds × effectiveTickRate} with the ctor's
     * {@code declaredFireTimeoutTicks} as the ≈1 s rate carrier (the rate-aware
     * idiom — zero new plumbing); the ≤1-tick phase skew vs vanilla is accepted,
     * healed at hold release by the engine's own falling-edge advance plus the quiescent-tick
     * {@code mirrorActiveTubeOrdinal}. On advance the accumulator re-arms at zero.
     */
    public boolean noteAlternatingHeldTick(long simTick, int groupOrdinal,
            float thresholdSeconds) {
        if (simTick != altDwellLastTick + 1L || groupOrdinal != altDwellGroupOrdinal) {
            altDwellHeldTicks = 0;
        }
        altDwellLastTick = simTick;
        altDwellGroupOrdinal = groupOrdinal;
        altDwellHeldTicks++;
        if (Float.isNaN(thresholdSeconds) || thresholdSeconds < 0f) {
            return false;
        }
        if (altDwellHeldTicks > thresholdSeconds * declaredFireTimeoutTicks) {
            altDwellHeldTicks = 0;
            altTubeDwellAdvances++;
            return true;
        }
        return false;
    }

    /**
     * Vanilla's alternating-group dwell threshold in SECONDS, mirrored
     * EXACTLY from vanilla's weapon-group dwell code: non-beam
     * base {@code (burstSize−1)·burstDelay + refireDelay + chargeTime}, REPLACED by 3.0 when
     * {@code burstSize > 50}; burst-beam base {@code burstDuration +
     * burstCooldown + chargeupTime + chargedownTime}; non-burst beams NEVER
     * dwell-advance ⇒ −1; then ÷groupSize ÷rofMult (BOTH branches — the burst&gt;50 replace
     * happens BEFORE the divisions). RoFMult ≤0/NaN reads 1.0 (the {@code getRoFMult}
     * default); any other NaN/negative input or result ⇒ −1 fail-soft-never (a wrong "never"
     * only preserves today's defect for that weapon; a wrong advance would mis-fire). Pure
     * static — the {@code adoptValidationFailure} precedent, game-free contract rows.
     */
    public static float alternatingDwellThresholdSeconds(boolean isBeam, boolean isBurstBeam,
            float burstDuration, float burstCooldown, float chargeupTime, float chargedownTime,
            float burstSize, float burstDelay, float refireDelay, float chargeTime,
            int groupSize, float rofMult) {
        if (groupSize < 1) {
            return -1f;
        }
        float mult = (Float.isNaN(rofMult) || rofMult <= 0f) ? 1.0f : rofMult;
        float threshold;
        if (isBeam) {
            if (!isBurstBeam) {
                return -1f;
            }
            if (badDwellInput(burstDuration) || badDwellInput(burstCooldown)
                    || badDwellInput(chargeupTime) || badDwellInput(chargedownTime)) {
                return -1f;
            }
            threshold = burstDuration + burstCooldown + chargeupTime + chargedownTime;
        } else {
            if (badDwellInput(burstSize) || badDwellInput(burstDelay)
                    || badDwellInput(refireDelay) || badDwellInput(chargeTime)) {
                return -1f;
            }
            threshold = (burstSize - 1f) * burstDelay + refireDelay + chargeTime;
            if (burstSize > 50f) {
                threshold = 3.0f;
            }
        }
        threshold = threshold / groupSize / mult;
        return (Float.isNaN(threshold) || threshold < 0f) ? -1f : threshold;
    }

    private static boolean badDwellInput(float v) {
        return Float.isNaN(v) || v < 0f;
    }

    /**
     * The strobe fix: the declaration {@code serveDeclaredFire} should PRESS
     * this tick, or {@code null} to stay silent. Runs {@link #headDeclarationToServe}'s ε-timeout
     * machinery verbatim (a blocked head still expires on the same clock), then gates the press
     * on the head's named MOUNT being dischargeable this tick AT THE HEAD'S CARRIED AIM
     * (the probe also refuses an aim outside the mount's live arc — a swept decl can be
     * un-fireable by its own named mount from birth): a blocked decl waits silently and
     * presses ~once at readiness, instead of strobing the whole group every tick through+past
     * trigger release (50 post-release tail shots — every strobe tick re-committed
     * the group's autoCharge mounts; 8 bearing-refused timeout heads owned
     * ~87% of 493 presses). Honest consequence of the arc gate: a never-in-arc head expires
     * PRESSLESS, so its client-fired shot stays unserved (the client_only fidelity gap — a
     * FIRST-CLASS graded bar at the 8th watch; the mount-ray/mount-scoped-press escalation is a
     * deferred design decision). Legacy {@code mountIndex} -1 and a missing probe press
     * unconditionally (the ungated path verbatim); presses/waits are counted
     * ({@link #declPressesIssued()}/{@link #declPressWaitTicks()}) for the round meter + contracts.
     * The head read passes the installed probe (futile-head early expiry).
     *
     */
    public cc.starsector.server.runtime.FireDischargeDeclaration headDeclarationToPress(long simTick) {
        // The installed probe also arms the futile-head early expiry.
        cc.starsector.server.runtime.FireDischargeDeclaration head =
                headDeclarationToServe(simTick, readinessProbe);
        if (head == null) {
            return null;
        }
        if (head.hasCarriedRay()) {
            return null; // Ray heads NEVER press — the adopt serve owns them
        }
        // serveAdoptHead's D2 gate on the press path — the engine
        // refuses every press under the hull's fire lock, so a ray-less head WAITS instead of
        // pressing into the refusal (it would expire timeout-pressed and feed belt 2). After the
        // ray return above: a locked ray head already counted its wait in serveAdoptHead.
        if (fireLockProbe != null && fireLockProbe.fireLockedNow()) {
            declLockWaitTicks++;
            headTenureWaitTicks++;
            headTenureLockWaitTicks++;
            return null;
        }
        if (readinessProbe != null && head.mountIndex() >= 0
                && !readinessProbe.mountDischargeableNow(
                        head.mountIndex(), head.aimWorldX(), head.aimWorldY())) {
            declPressWaitTicks++;
            headTenureWaitTicks++;
            return null; // wait silently — the mount cannot serve this head's aim this tick
        }
        declPressesIssued++;
        headTenurePresses++;
        return head;
    }

    /** declared-lane FIRE presses actually issued (the strobe made countable). */
    public int declPressesIssued() {
        return declPressesIssued;
    }

    /** Ticks a pending head waited silently (mount not dischargeable). */
    public int declPressWaitTicks() {
        return declPressWaitTicks;
    }

    /**
     * Ticks a pending head (ray: the adopt serve; ray-less: the press path)
     * waited silently under the hull's fire lock (the round line's
     * {@code fire_lock_waits=}); disjoint from {@link #declPressWaitTicks}.
     */
    public int declLockWaitTicks() {
        return declLockWaitTicks;
    }

    /**
     * Consume the head declaration because the server's engine served it (the serve loop observed
     * an own-ship {@code WEAPON_DISCHARGE} post-step). No-op returning {@code false} when nothing
     * is pending — an organic discharge with no declaration outstanding consumes nothing.
     *
     * <p>The serve loop now calls {@link #consumeDeclarationOnDischarge} (the
     * whole-FIFO mount-identity walk) instead — this head-only form remains for game-free
     * contracts that drive the FIFO directly and as the walk's depth-1 equivalent.
     */
    public boolean consumeHeadOnDischarge() {
        cc.starsector.server.runtime.FireDischargeDeclaration head = pendingDeclarations.pollFirst();
        if (head == null) {
            return false;
        }
        forgetArrival(head);
        declarationsServed++;
        headServeSinceTick = -1L;
        if (classRouted && head.mountIndex() >= 0) {
            expiryStreakByMount.remove(head.mountIndex()); // belt 2: a served shot resets the streak
            lastAdoptRejectByMount.remove(head.mountIndex()); // Consume clears
        }
        return true;
    }

    /**
     * the TAP-FIRE ECHO fix: consume the FIRST pending declaration
     * anywhere in the FIFO that {@link #dischargeConsumesHead} pairs with this observed
     * own-ship discharge, not just the head. The declared serve is GROUP-level FIRE
     * ({@code serveDeclaredFire}), so one tap on a group with several class-S mounts mints one
     * declaration per mount and ONE served FIRE discharges them all in the same tick — under
     * the head-only consume, any discharge edge whose mount differed from the head's consumed
     * NOTHING, stranding the sibling declarations. A stranded declaration kept pressing FIRE
     * and re-fired the whole group the moment its cooldown ended (+60 ticks = the felt echo;
     * every aurora tap double-fired, all 23 declarations still ended served).
     * The D-M3-6 mount-identity law is unchanged — a discharge still consumes only its OWN
     * mount's declaration (cross-mount theft stays impossible); this only drops the
     * head-position accident the group serve invalidated. Unknown mounts ({@code -1} either
     * side) keep the legacy consume-on-any pairing, which the walk satisfies at the head —
     * byte-identical to {@link #consumeHeadOnDischarge} for depth-≤1 FIFOs.
     *
     * <p>A routed consume records the mount's served tick
     * ({@link #lastObservedSimTick}); an UNMATCHED discharge on a routed class-S mount within
     * {@code declaredFireTimeoutTicks} (the ≈1 s rate carrier; gated on the probe's
     * mid-burst read now OR at the mount's previous discharge edge,
     * so a burst's last shot counts; capped per mount) of such a consume mints a burst-continuation
     * credit that {@link #enqueueDeclaration} spends. Return value unchanged (false = nothing
     * consumed).
     *
     * <p>The walk itself lives in {@link #consumeMatchingDeclaration}, shared with
     * {@link #consumeDeclarationOnDischargeWait} (the serve loop's form, which also returns the v16
     * declared wait); this form keeps its boolean for the game-free rows that drive it directly.
     */
    public boolean consumeDeclarationOnDischarge(int dischargeMountIndex) {
        return consumeMatchingDeclaration(dischargeMountIndex) != NOT_CONSUMED;
    }

    /**
     * GPEE v16 form of {@link #consumeDeclarationOnDischarge(int)} — the SAME walk, consume,
     * meters and credit mint — returning the DECLARED WAIT of the consumed declaration: raw ticks
     * {@code dischargeTick − applyTick} clamped to {@code GameplayEdgeEvent.DECLARED_WAIT_MAX_TICKS} (a
     * ready gun reads 0), or -1 when nothing was consumed, the consumed declaration carries no arrival
     * (the legacy 1-arg enqueue), or the difference is negative. A credit-served declaration was never
     * enqueued, so it can never be consumed here. {@code dischargeTick} = the discharge edge's
     * {@code simTick()}.
     */
    public int consumeDeclarationOnDischargeWait(int dischargeMountIndex, long dischargeTick) {
        long applyTick = consumeMatchingDeclaration(dischargeMountIndex); // NOT_CONSUMED < 0 too
        if (applyTick < 0L || dischargeTick < applyTick) {
            return DECLARED_WAIT_ABSENT;
        }
        return (int) Math.min(dischargeTick - applyTick,
                cc.starsector.server.protocol.GameplayEdgeEvent.DECLARED_WAIT_MAX_TICKS);
    }

    /** {@link #consumeMatchingDeclaration}'s "no declaration matched" answer
     *  (below every arrival, including the -1 "consumed, arrival unknown"). */
    private static final long NOT_CONSUMED = Long.MIN_VALUE;

    /** The absent declared wait (= {@code GameplayEdgeEvent.TARGET_ABSENT} as an int). */
    private static final int DECLARED_WAIT_ABSENT = -1;

    /**
     * Drop {@code decl}'s arrival stamp — called at EVERY single FIFO removal (the
     * three clears clear the store whole). Returns its {@code applyTick}, or -1 when it carried none.
     *
     */
    private long forgetArrival(cc.starsector.server.runtime.FireDischargeDeclaration decl) {
        Long applyTick = declArrivalTickByDecl.remove(decl);
        return applyTick == null ? -1L : applyTick;
    }

    /** Arrival stamps held (always ≤ {@link #pendingDeclarationCount()}). */
    public int declArrivalStampCount() {
        return declArrivalTickByDecl.size();
    }

    /**
     * The {@link #consumeDeclarationOnDischarge(int)} body (the mount-identity walk + the burst-continuation
     * credit mint), unchanged but for its answer: {@link #NOT_CONSUMED} when nothing matched, else the
     * consumed declaration's {@code applyTick} (-1 = none recorded).
     */
    private long consumeMatchingDeclaration(int dischargeMountIndex) {
        java.util.Iterator<cc.starsector.server.runtime.FireDischargeDeclaration> it =
                pendingDeclarations.iterator();
        for (boolean atHead = true; it.hasNext(); atHead = false) {
            cc.starsector.server.runtime.FireDischargeDeclaration decl = it.next();
            if (!dischargeConsumesHead(decl.mountIndex(), dischargeMountIndex)) {
                continue;
            }
            it.remove();
            long applyTick = forgetArrival(decl);
            declarationsServed++;
            if (atHead) {
                headServeSinceTick = -1L; // the next head starts a fresh ε-timeout clock
            }
            if (classRouted && decl.mountIndex() >= 0) {
                expiryStreakByMount.remove(decl.mountIndex()); // belt 2: served resets the streak
                lastAdoptRejectByMount.remove(decl.mountIndex()); // Consume clears
                lastServedTickByMount.put(decl.mountIndex(), lastObservedSimTick); //
                // Record the post-shot burst state (= the next discharge's
                // pre-tick state) on the class-S mounts the mint gate reads. One probe read per
                // served discharge edge, never per tick.
                if (readinessProbe != null && effectiveMountClassS(decl.mountIndex())) {
                    inBurstAfterLastDischargeByMount.put(decl.mountIndex(),
                            readinessProbe.mountInBurstOrFiring(decl.mountIndex()));
                }
            }
            return applyTick;
        }
        // Nothing matched. If this routed class-S mount had a SERVED
        // consume within the window, the discharge is its burst's continuation — the client's
        // continuation decl is (or will be) on the wire and would otherwise land as an orphan
        // head. Mint one credit for intake to spend. No recent served consume = autofire /
        // organic: mint nothing (the behaviour before continuation credits).
        if (classRouted && dischargeMountIndex >= 0 && effectiveMountClassS(dischargeMountIndex)) {
            Long served = lastServedTickByMount.get(dischargeMountIndex);
            // The window rides the ≈1 s rate carrier (was a fixed 60).
            if (served != null && lastObservedSimTick - served <= declaredFireTimeoutTicks) {
                // The mint gate: only a discharge the engine says is MID-BURST is a
                // continuation (production = WeaponAPI.isInBurst via mountInBurstNow; an
                // unreadable read answers true = today's mint, documented fail-open). A null
                // probe (fake/test path) keeps the earlier law. The
                // post-step read ALWAYS answers false on a burst's LAST shot (the engine clears
                // isInBurst inside that shot's firing call — cr-r3-credit.md F1), so the gate
                // also reads the PRE-tick state: the post-step read taken at this mount's
                // previous discharge edge. In burst before OR after the shot = a continuation
                // (the last shot mints and counts lastShotCreditsMinted). Neither = the group-FIRE
                // echo / server>client divergence class: counted, never minted (a credit would
                // conceal it — never-conceal-desync). The recorded state is always re-written by
                // the in-window served consume the gate requires, so it is never staler than the
                // window.
                // A stale stack is dropped (counted expired) before it can be revived or cap.
                int stack = continuationCreditLive(dischargeMountIndex)
                        ? continuationCreditsOnMount(dischargeMountIndex) : 0;
                boolean inBurstNow = readinessProbe == null
                        || readinessProbe.mountInBurstOrFiring(dischargeMountIndex);
                boolean inBurstBefore = Boolean.TRUE.equals(
                        inBurstAfterLastDischargeByMount.get(dischargeMountIndex));
                if (readinessProbe != null) {
                    inBurstAfterLastDischargeByMount.put(dischargeMountIndex, inBurstNow);
                }
                if (!inBurstNow && !inBurstBefore) {
                    dischargeUnmatchedNonburst++;
                } else if (stack >= CONTINUATION_CREDIT_CAP_PER_MOUNT) {
                    continuationCreditsCapped++; // The per-mount credit cap
                } else {
                    continuationCreditCountByMount.put(dischargeMountIndex, stack + 1);
                    continuationCreditNewestTickByMount.put(dischargeMountIndex, lastObservedSimTick);
                    continuationCreditsMinted++;
                    if (!inBurstNow) {
                        lastShotCreditsMinted++; // r5: the burst's last shot (was in burst, now not)
                    }
                }
            }
        }
        return NOT_CONSUMED; // organic/autofire discharge with no matching declaration outstanding
    }

    /**
     * The pending head declaration without consuming it, or {@code null} — the
     * consume-by-mount guard reads its {@code mountIndex} to decide whether an observed
     * discharge is the declared shot (same weapon group) or an unrelated (autofire-group)
     * discharge that must NOT consume the head.
     */
    public cc.starsector.server.runtime.FireDischargeDeclaration peekHeadOrNull() {
        return pendingDeclarations.peekFirst();
    }

    // ---- fire-edge latch delegates: bodies, laws and javadoc live in FireEdgeLatch;
    // these keep the binding, the serve loop and the contract rows source-compatible. ----
    public void fireEdgeLatch(boolean enabled) { fireEdge.fireEdgeLatch(enabled); }
    public void observeFireEdge(int fireEdgeSeq, int selectedWeaponGroup, long simTick) {
        fireEdge.observeFireEdge(fireEdgeSeq, selectedWeaponGroup, simTick);
    }
    public void observeFireEdge(int fireEdgeSeq, int selectedWeaponGroup, long simTick, boolean armAllowed) {
        fireEdge.observeFireEdge(fireEdgeSeq, selectedWeaponGroup, simTick, armAllowed);
    }
    public boolean latchGateReadDue(long simTick) { return fireEdge.latchGateReadDue(simTick); }
    public void noteLatchFluxGate(int gate, long wireId) { fireEdge.noteLatchFluxGate(gate, wireId); }
    public boolean latchPressing(boolean fireHeld, long simTick) { return fireEdge.latchPressing(fireHeld, simTick); }
    public void releaseLatchOnDischarge(int dischargeGroupOrdinal) { fireEdge.releaseLatchOnDischarge(dischargeGroupOrdinal); }
    public boolean fireEdgeLatchArmed() { return fireEdge.fireEdgeLatchArmed(); }
    public int edgeRecovered() { return fireEdge.edgeRecovered(); }
    public int edgeExpired() { return fireEdge.edgeExpired(); }
    public int edgeExpiredGated() { return fireEdge.edgeExpiredGated(); }
    public int edgeClampDropped() { return fireEdge.edgeClampDropped(); }
    public int edgeSwitchDisarmed() { return fireEdge.edgeSwitchDisarmed(); }

    /** this seat's declared-shot press pad (press, discharge, knob and meter surface). */
    public DeclaredShotPad declPad() {
        return declPad;
    }

    /**
     * Rejoin-edge adopt class:
     * a NEW client session now feeds this
     * wire id (grace reclaim, ban-lift, TAKEOVER — every admit mints a new session id). The rejoined
     * client's {@code fireEdgeSeq} restarted at 0 while this per-wire-id state kept the old session's seq,
     * so its first ungated frame read as a click — a synthetic {@link #FIRE_EDGE_MIN_TAP_TICKS} press
     * nobody made (three recorded flights: `adopt=` at every reclaim edge, `edge_recovered` +1 each).
     * Re-inits ONLY the client-seq-keyed memory: the edge seq (the next frame inits, never arms), the
     * latch (a stale arm would lapse as a spurious {@code edge_expired}), and the pending declaration
     * FIFO (old-session class-S shots would otherwise serve on the reclaimed hull; flushed = expired, so
     * {@code received == served + expired + pending} holds). Server-tick-keyed fields (autofire press,
     * sys hold, mask echo, vent/retreat levels, group identity, meters) stay. Returns the row tokens the
     * caller prints (the flight PROOF token).
     */
    public String onSessionBoundary() {
        return onSessionBoundary(-1L);
    }

    /**
     * {@link #onSessionBoundary()} plus the {@link DeclaredShotPad} tenure clear
     * stamped at {@code tenureStartTick} (the last stepped sim tick; -1 = unknown, stamp kept): an
     * old-session declaration drained after the boundary can never arm. Returned tokens unchanged.
     *
     */
    public String onSessionBoundary(long tenureStartTick) {
        declPad.clearForTenure(tenureStartTick);
        int prevSeq = fireEdge.lastFireEdgeSeq;
        boolean wasArmed = fireEdge.latchArmed;
        fireEdge.lastFireEdgeSeq = -1;
        fireEdge.latchArmed = false;
        fireEdge.latchSawLostTap = false;
        int flushed = flushPendingOnHullLoss();
        return "prev_seq=" + prevSeq + " latch_was_armed=" + wasArmed + " decls_flushed=" + flushed;
    }

    private void logExpiry(
            cc.starsector.server.runtime.FireDischargeDeclaration declaration,
            String reason, long waitedTicks) {
        noteExpiryDetail(declaration.mountIndex(), reason);
        if (expiryLogsEmitted >= MAX_EXPIRY_LOGS) {
            return;
        }
        expiryLogsEmitted++;
        System.out.println("pilot-fire-decl expired reason=" + reason
                + " ship=" + declaration.shipId()
                + " decl_seq=" + declaration.declSeq()
                + " waited_ticks=" + waitedTicks
                + " pending=" + pendingDeclarations.size()
                + (expiryLogsEmitted == MAX_EXPIRY_LOGS ? " (log capped; counters continue)" : ""));
    }

    /** Count one reasoned expiry into the bounded detail map. */
    private void noteExpiryDetail(int mountIndex, String reason) {
        String key = "m" + mountIndex + ":" + reason;
        if (!expiredByMountReason.containsKey(key)
                && expiredByMountReason.size() >= MAX_EXPIRY_DETAIL_KEYS) {
            key = "other";
        }
        expiredByMountReason.merge(key, 1, Integer::sum);
    }

    /** per-mount per-reason expiry counts (round-line detail), never null. */
    public java.util.Map<String, Integer> expiredByMountReason() {
        return java.util.Collections.unmodifiableMap(expiredByMountReason);
    }

    /**
     * The head-tenure refinement of the bare ε-timeout reason.
     * A tenure that lock-waited ≥ 1 tick (and never pressed / force-fired) reads
     * {@code timeout-locked}, outranking waitheld / idle — the head was refused by the hull's
     * own fire lock, not starved.
     */
    private String refinedTimeoutReason() {
        if (headTenurePresses > 0 || headTenureAdoptFires > 0) {
            return "timeout-pressed";
        }
        if (headTenureLockWaitTicks > 0) {
            return "timeout-locked";
        }
        return headTenureWaitTicks > 0 ? "timeout-waitheld" : "timeout-idle";
    }

    private void resetHeadTenure() {
        headTenurePresses = 0;
        headTenureWaitTicks = 0;
        headTenureLockWaitTicks = 0;
        headTenureAdoptFires = 0;
    }

    /**
     * Declarations received over this state's lifetime (one round — the serve loop's
     * per-round state), INCLUDING overflow-rejected ones (B4 step-2 accounting fix):
     * {@code received == served + expired + pending} by construction.
     */
    public int declarationsReceived() {
        return declarationsReceived;
    }

    /** Declarations consumed by an observed own-ship discharge. */
    public int declarationsServed() {
        return declarationsServed;
    }

    /** Declarations dropped by ε-timeout or FIFO overflow (acceptance bar: 0). */
    public int declarationsExpired() {
        return declarationsExpired;
    }

    /** Declarations pending (received, neither served nor expired). */
    public int pendingDeclarationCount() {
        return pendingDeclarations.size();
    }

    /** Meter: ticks the bit-10 hold replay issued the engine's own TOGGLE_SHIELD_OR_PHASE_CLOAK. */
    public long shieldHoldTicks() {
        return shieldHoldTicks;
    }

    /** Meter: ticks the bit-11 cloak mismatch-edge lane issued the toggle verb. */
    public long cloakToggleTicks() {
        return cloakToggleTicks;
    }

    /** Ticks the bit-12 lane issued PULL_BACK_FIGHTERS (a human Z press = one). */
    public long pullBackToggleTicks() {
        return pullBackToggleTicks;
    }

    /** Meter: bit-8 rising-edge VENT_FLUX presses (the classic vents meter). */
    public int ventsIssued() {
        return ventsIssued;
    }

    /** Meter: per-tick mismatch re-presses while bit-8 held and the server hull is not venting. */
    public int ventRePresses() {
        return ventRePresses;
    }

    /** Presses withheld so the engine's autofire edge detector could re-arm. */
    public int autofireClickReleases() {
        return autofireClickReleases;
    }

    /** Meter: non-edge retreat-flag level re-asserts (disagreement ticks healed). */
    public int retreatReasserts() {
        return retreatReasserts;
    }

    /** Meter: held-TRUE bit-9 frames ignored between a hull loss and the first bit-false frame. */
    public int retreatRearmSkips() {
        return retreatRearmSkips;
    }

    /** Meter: TRUE bit-9 frames absorbed inside the post-re-key edge quarantine. */
    public int retreatEdgeQuarantined() {
        return retreatEdgeQuarantined;
    }

    /**
     * The mount-identity consume gate: does an
     * observed own-ship discharge from {@code dischargeMountIndex} consume the pending head
     * declaration that named {@code headMountIndex}? Both mounts known ⇒ consume ONLY on exact
     * mount identity (same mount ⇒ same weapon group by construction, so this strictly tightens
     * the group gate — it also kills cross-mount decl theft INSIDE a group, the
     * practical latent: a same-group sibling's burst-extra discharge can no longer eat a pending
     * manual declaration). Either side unknown ({@code -1}: legacy declaration, unbound seams —
     * every fake binding —, unresolvable mount) ⇒ the legacy consume-on-any pairing, exactly
     * today's behavior (the old group gate also required both mounts, so its unknown-side
     * behavior was consume-on-any too — byte-identical fallback). Same-mount double-decl inside
     * ONE burst is engine-implausible (a bursting weapon consumes no new press until its charge
     * cycle ends; burst rows verified at build: lightdualmg 5@0.1s, heavymg
     * 4@0.1s, swarmer 4@0.2s on the grendel loadout, hellbore burst 1), so the exact-identity
     * consume cannot mis-pair adjacent bursts of the same mount.
     */
    public static boolean dischargeConsumesHead(int headMountIndex, int dischargeMountIndex) {
        if (headMountIndex < 0 || dischargeMountIndex < 0) {
            return true; // unknown side: the legacy consume-on-any pairing (fail-open to today)
        }
        return dischargeMountIndex == headMountIndex; // D-M3-6 mount identity
    }

    /** Meter: bit-LOW cancel-mirror {@code useSystem()} presses (double-tap cancel). */
    public int systemCancelPresses() {
        return systemCancelPresses;
    }

    /** Meter: active-tube pointer conforms (the re-select class healed). */
    public int tubeOrdinalConformed() {
        return tubeOrdinalConformed;
    }
}
