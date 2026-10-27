package cc.starsector.server.bridge;

import cc.starsector.server.adapter.CombatEntityView;
import cc.starsector.server.adapter.CombatStepBoundary;
import cc.starsector.server.adapter.ShipAllegianceView;
import cc.starsector.server.adapter.ShipCrView;
import cc.starsector.server.adapter.ShipDurabilityView;
import cc.starsector.server.adapter.ShipEngineStateView;
import cc.starsector.server.adapter.ShipFluxView;
import cc.starsector.server.adapter.ShipIdentityView;
import cc.starsector.server.adapter.ShipLifecycleView;
import cc.starsector.server.adapter.ShipManeuverCommandView;
import cc.starsector.server.adapter.ShipMotionView;
import cc.starsector.server.adapter.ShipPhaseCloakView;
import cc.starsector.server.adapter.ShipSystemView;
import cc.starsector.server.adapter.WeaponMountView;
import cc.starsector.server.protocol.AuthoritativeEntitySnapshot;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.ContentDictionaryEntry;
import cc.starsector.server.protocol.NonShipEntitySnapshot;
import cc.starsector.server.protocol.ShipAllegianceSnapshot;
import cc.starsector.server.protocol.ShipCrSnapshot;
import cc.starsector.server.protocol.ShipEngineStateSnapshot;
import cc.starsector.server.protocol.ShipFluxSnapshot;
import cc.starsector.server.protocol.ShipManeuverCommandSnapshot;
import cc.starsector.server.protocol.ShipPhaseCloakStateSnapshot;
import cc.starsector.server.protocol.ShipSystemStateSnapshot;
import cc.starsector.server.protocol.ShipWeaponMountSnapshot;
import cc.starsector.server.runtime.ArmorCellBook;
import cc.starsector.server.runtime.ContentIdRegistry;
import cc.starsector.server.runtime.LiveWorldSnapshotSource;
import cc.starsector.server.runtime.ServerAuthoredProjectileField;
import cc.starsector.server.runtime.ServerConfig;
import cc.starsector.server.runtime.ShipNetworkIdRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

/**
 * {@link LiveWorldSnapshotSource} on top of the live combat engine.
 *
 * <p>This is the engine realization of the live per-tick publisher: each
 * {@link #publishNextTick()} drives one inner simulation step through the
 * engine-backed {@link CombatStepBoundary}, then reads the per-ship motion and
 * durability views off the live {@link EngineCombatBinding} and pairs them into one
 * {@link AuthoritativeWorldSnapshot} via
 * {@link AuthoritativeWorldSnapshot#fromViewLists}. The publisher writes that single
 * frame to the socket before this method is called again, so each snapshot is both
 * produced and replicated within the tick that created it — the
 * "server emits one per-entity snapshot per tick onto the socket" path,
 * as opposed to the older post-loop file capture.
 *
 * <p>Fail-closed: a missing/short view set or a mismatch against the expected ship
 * count throws, so a partial or stale snapshot can never be published.
 */
public final class EngineBackedLiveWorldSnapshotSource implements LiveWorldSnapshotSource {

    private final CombatStepBoundary step;
    private final EngineCombatBinding binding;
    private final int expectedFrames;
    /**
     * Position-mode: the exact ship count every tick must read (fail-closed equality).
     * Stable-roster mode: the upper bound on the roster size read each tick (the count
     * may vary tick to tick as ships leave/enter; only {@code [1, shipCountBound]} is
     * enforced).
     */
    private final int shipCountBound;
    private final float fixedDt;
    private final ContentIdRegistry contentRegistry; // nullable: snapshot stays content-neutral
    private final Map<Long, Integer> ownerByShipId;
    private final Map<Long, Long> spawnTickByShipId = new HashMap<>();
    private final ServerAuthoredProjectileField authoredProjectiles; // nullable: no server-authored tier
    // stable-roster mode: non-null => publishNextTick keys each
    // ship's network entityId on the engine's stable id and tolerates a changing roster.
    private final ShipNetworkIdRegistry stableIdRegistry; // null => position-keyed mode (old path)
    private final Map<String, Integer> ownerByStableKey;
    private final Map<String, Long> spawnTickByStableKey = new HashMap<>();
    private long simTick;
    // Content-dictionary tier fold state. Per-source (= per-round in
    // the serveNPeer fresh-source idiom): a new source restarts the announce cycle from
    // scratch, harmless under the client's last-write-wins catalog fold. highWater = the
    // registry size already announced at least once via the newest-first leg; cursor =
    // the round-robin re-announce position over ids 1..size.
    private int dictHighWater;
    private int dictCursor;
    /**
     * J (the cycling law): at most this many dictionary entries ride per frame —
     * newest-minted first, remaining slots round-robin over the whole catalog, so a
     * ~50-entry catalog re-announces every ~1.3 s at 60 Hz and rejoiners + datagram
     * loss converge by construction.
     */
    private static final int DICT_ENTRIES_PER_FRAME = 4;
    // Flipped true the first tick stable-roster mode reads ZERO live ships
    // (the symmetric 1v1 mutual-kill ~tick 547-565, or the last ship destroyed). That tick still
    // produces a valid roster-0 (empty-ships) frame — the faithful end-of-match signal, NOT an
    // error — so the read-only serve loop can publish it (the client observes the last death(s) as
    // the entity list empties) and then end the session cleanly, instead of the prior IOException
    // "dropped ... on I/O error". Only ever set in stable-roster mode.
    private boolean matchOver;
    // WHICH outcome latched matchOver, for the MOVR down fan — the
    // logRosterDump tag (first latch wins, the same first-writer property the !matchOver
    // guards give the dumps), the winning FleetSide (0/1; -1 = no victor — the roster-0
    // mutual kill), and the sim tick the latch fired on. The log stays the grading source;
    // these fields are the wire carry the log lines never had. Latched, never reset.
    private String matchOverTag;
    private int matchOverWinnerSide = -1;
    private long matchOverSimTick = -1L;
    // Default-OFF per-side (one-side-eliminated) round-end. When set, the
    // stable-roster matchOver flips as soon as only ONE fighting FleetSide still has live ships (a
    // normal piloted-1v1 outcome) instead of only at aliveCount==0 (the symmetric mutual-kill edge
    // that an asymmetric intent-up duel never reaches). Read once
    // here as a field initializer so NO constructor/factory signature changes and none of the 5
    // stableRoster(...) call sites move; OFF leaves the aliveCount==0 trigger byte-identical (no
    // extra binding read, no log, no flag write). Driven off the LIVE engine allegiance read
    // (the allegiance tier), not the static ownerByStableKey map (empty in the command lane) nor the
    // engine's getWinningSideId() (spurious early headless winner). Only ever consulted in
    // stable-roster mode (the only mode that flips matchOver).
    private final boolean perSideRoundEnd = ServerConfig.resolveBoolean(
            "cc.server.perSideRoundEnd", "CC_SERVER_PER_SIDE_ROUND_END", "server.perSideRoundEnd", false);
    // Default-OFF instrument: name the flux read-instant window. The flux
    // blip [observed ×3] = the flux fold saw PRE-fire flux while the same-tick post-capture walk
    // saw POST-fire, with no server-side statement between that mutates the engine — the ENGINE
    // moved the value between two same-tick read instants. This probe re-reads the SAME ships'
    // live currFlux through the SAME binding path at two capture-internal instants (p1 =
    // immediately after foldFluxTier, p2 = capture return) and logs a row when either disagrees
    // with the folded wire value — splitting the fold→walk gap into fold→p1 / p1→p2 / p2→walk
    // (the walker's own parity_walk row covers the last leg offline, so the probe also emits an
    // unconditional row at parity-walk tick multiples for that join). Armed by env row or
    // sysprop; fail-soft, read-only, row-capped; OFF = zero extra binding reads (production
    // capture path byte-identical).
    private final boolean fluxReadInstantProbe = Boolean.getBoolean("cc.probe.fluxReadInstant")
            || Boolean.parseBoolean(System.getenv("CC_PROBE_FLUX_READ_INSTANT"));
    // E3 ADJ-H: the full ServerConfig cascade — sysprop-only
    // Integer.getInteger diverged from the wrappers' env/config-row lane for the SAME knob.
    private final long fluxProbeWalkEvery = ServerConfig.resolveInt(
            "cc.parity.walkEvery", "CC_PARITY_WALK_EVERY", "parity.walkEvery", 0);
    private static final int FLUX_PROBE_ROW_CAP = 4000;
    private int fluxProbeRowsLogged;
    private boolean fluxProbeArmedLogged;
    // opt-in npeer match-over ARMING. matchOver LATCHES (set below,
    // never reset), so a gated zero-hull boot would flip it at frame 0 (aliveCount==0) and a
    // solo first fly-in would trip perSideRoundEnd — both before a match exists. In arming mode
    // BOTH triggers stay suppressed until >=2 fighting sides (owner != 100) have had a live ship
    // this round (CUMULATIVE across ticks — the sides need not be alive simultaneously, so a
    // side that joined and died still counts and a late second side can end the round on its
    // arrival tick). serveNPeer mints a fresh source per round (the PPICS fresh-source idiom), so arming
    // state is per-round by construction. Legacy sources never set the flag: control flow,
    // logs and matchOver semantics stay byte-identical (no allegiance read is added there).
    private final boolean matchOverArming;
    private boolean matchOverArmed;
    private final Set<Integer> fightingSidesSeen = new HashSet<>();
    // Roster-0 hold: while a respawn-armed serve lane reports a seat that will
    // remount (occupied, not dead-latched), a zero-live-ship tick does NOT latch matchOver — the
    // empty battlefield stays open so a mutual kill inside the fly-in overlap respawns BOTH sides
    // (reverses an earlier accepted residual: that kill ended the match). Null (the default — legacy callers, respawn
    // disabled) keeps the aliveCount==0 latch byte-identical. Set post-mint by the respawn-enabled
    // serve lanes only (setter below), never through the factories.
    private BooleanSupplier respawnAwaitingRemount;
    // The god-mode replica bit's source (setGodAppliedWire); null = never armed.
    private Supplier<LongPredicate> godAppliedWire;
    // The travel-drive fire-lock bit's source (setDriveLockWire); null = never armed.
    private Supplier<LongPredicate> driveLockWire;
    private boolean roster0HoldLogged; // once-per-empty-window log guard; resets at aliveCount>0
    // respawn-compatible wave-clear trigger (the tiny-wave leg's new trigger).
    // Set post-mint by wave-shape serves only (setter below — the setRespawnAwaitingRemount
    // idiom keeps the factory signatures); null (the default) = every non-wave serve
    // byte-identical. The armed-tick enemy-live FEED (the WaveSchedule spawn-on-clear input)
    // always rides; the matchOver LATCH — wave side's live hulls ZERO while the schedule
    // reports drained — fires only when latchRoundEnd was armed (the continuous default
    // feeds the chain without ever ending the round; latch=true = the per-clear reset option).
    // The latch is legal WITH respawn (unlike cc.server.perSideRoundEnd, whose
    // respawn boot-throw at the serve entry stays untouched): a human death never zeroes the
    // ENEMY side, so pending remounts cannot collide with this latch, and inter-wave
    // zero-enemy gaps cannot latch because drained() is false until the last wave spawned.
    private int waveClearEnemySide = -1;
    private LongConsumer waveClearEnemyLiveObserver;
    private BooleanSupplier waveClearScheduleDrained;
    private boolean waveClearLatchRoundEnd;
    // The logRosterDump tag the victory latch dumps under. Default = the
    // wave tag; the fortress serve passes its own so a DEFEND round's WIN is
    // greppable apart from its LOSE line (match-over-base-hulk) in one pass over a log.
    private String waveClearLatchTag = "match-over-wave-clear";
    // latch-side sustained-zero hold (ticks; 0 = instant legacy latch)
    // + the capture tick that started the current zero run (-1 = not counting).
    private long waveClearLatchHoldTicks;
    private long waveClearZeroSinceTick = -1L;
    // The STAR FORTRESS lose seam — the round's
    // deployed station BASE hull turning hulk ends the match. Set post-mint by fortress-shape
    // serves only (setter below — the setWaveClearRoundEnd idiom keeps the five
    // stableRoster(...) factory signatures); null (the default) = every non-station serve
    // byte-identical (no supplier read, no branch taken). The base is an EVER-LIVING hull in
    // wave-clear terms (it never departs and never wins), so this trigger — not the wave-clear
    // latch — is what a fortress round ends on; a hulk base still occupies the repository, so
    // neither the roster-0 nor the per-side trigger can see it.
    private BooleanSupplier stationLost;
    // Rider: the STATION's own FleetSide (0/1), armed with the
    // lose seam. The base-hulk winner derives from THIS (the side opposed to the station), not
    // from waveClearEnemySide — the lose-seam-alone shape (station armed, no wave trigger) is
    // reachable and must not latch a no-victor verdict the winning attacker reads as DEFEAT.
    private int stationOwnSide = -1;
    // hulk-fracture detector (stable-roster mode only — it pins each corpse
    // family's rootEntityId through the registry). Fed inside buildStableRosterSnapshot,
    // gated so the remnant walk costs nothing pre-lethal; a fresh source per round (the
    // serve loops' idiom) IS the round-wrap reset.
    // Non-final: setHulkPoseCadence/setHulkCutGeometry re-mint it post-mint pre-observe
    // (the setRespawnAwaitingRemount idiom keeps the five stableRoster factory
    // signatures). Both products default OFF at mint so nothing accumulates for lanes that
    // never drain them.
    private cc.starsector.server.runtime.HulkFractureDetector fractureDetector;
    private int hulkPoseCadenceTicks; // 0 = OFF
    // The serve-shared armor cell book (null = cells dark).
    private ArmorCellBook armorCellBook;
    private int hulkPoseBurstCadenceTicks; // 0 = impulse lane OFF at mint (window retired)
    private boolean hulkCutGeometryOn; // Capture OFF at mint
    // The hulk lane's sim-thread cost per observe tick (the
    // remnant read + the fold), the asteroid_observe_ns twin; per-round like every hkps meter
    // (fresh source per round). Read by the DownLaneSeams summary line.
    private long hulkObserveNsTotal;
    private long hulkObserveNsMax;
    private long hulkObserveTicks;

    /**
     * Content-neutral source (back-compat): motion + durability only, identity
     * fields left at neutral defaults. Equivalent to passing a null registry.
     */
    public EngineBackedLiveWorldSnapshotSource(
            CombatStepBoundary step,
            EngineCombatBinding binding,
            int expectedFrames,
            int expectedShipCount,
            float fixedDt) {
        this(step, binding, expectedFrames, expectedShipCount, fixedDt, null, null, null);
    }

    /**
     * Identity-aware source: when {@code contentRegistry} is non-null and the
     * bridge exposes a full-roster {@link ShipIdentityView} read, each
     * published snapshot carries the engine-sourced {@code contentId} (interned hull
     * id), a server-tracked {@code spawnTick} (first tick the entity is observed),
     * and the {@code ownerByShipId} command-authority assignment. If the identity
     * read is unavailable it degrades to motion+durability rather than failing.
     */
    public EngineBackedLiveWorldSnapshotSource(
            CombatStepBoundary step,
            EngineCombatBinding binding,
            int expectedFrames,
            int expectedShipCount,
            float fixedDt,
            ContentIdRegistry contentRegistry,
            Map<Long, Integer> ownerByShipId) {
        this(step, binding, expectedFrames, expectedShipCount, fixedDt,
                contentRegistry, ownerByShipId, null);
    }

    /**
     * Identity-aware source that also folds a {@link ServerAuthoredProjectileField}
     * onto each frame. The headless engine cannot spawn a projectile
     * (the spawn path inside the sim step touches OpenGL, which a
     * headless server has no context for), so the server authors projectile records
     * from the fire intent it already owns and this source folds the field's per-tick
     * live projectiles onto the same AWSS wire after the engine-read non-ship tier.
     * A null field means no server-authored projectile tier (the
     * engine-read behaviour is unchanged). The field's content keys are interned
     * through the same {@code contentRegistry} as the engine-read tier.
     */
    public EngineBackedLiveWorldSnapshotSource(
            CombatStepBoundary step,
            EngineCombatBinding binding,
            int expectedFrames,
            int expectedShipCount,
            float fixedDt,
            ContentIdRegistry contentRegistry,
            Map<Long, Integer> ownerByShipId,
            ServerAuthoredProjectileField authoredProjectiles) {
        this(step, binding, expectedFrames, expectedShipCount, fixedDt,
                contentRegistry, ownerByShipId, authoredProjectiles, null, null, false);
    }

    /**
     * All-args delegate. {@code stableIdRegistry != null} selects the
     * stable-roster mode (use {@link #stableRoster}); {@code null} keeps the
     * position-keyed mode every public constructor above produces.
     */
    private EngineBackedLiveWorldSnapshotSource(
            CombatStepBoundary step,
            EngineCombatBinding binding,
            int expectedFrames,
            int shipCountBound,
            float fixedDt,
            ContentIdRegistry contentRegistry,
            Map<Long, Integer> ownerByShipId,
            ServerAuthoredProjectileField authoredProjectiles,
            ShipNetworkIdRegistry stableIdRegistry,
            Map<String, Integer> ownerByStableKey,
            boolean matchOverArming) {
        this.step = Objects.requireNonNull(step, "step");
        this.binding = Objects.requireNonNull(binding, "binding");
        if (expectedFrames < 1) {
            throw new IllegalArgumentException("expectedFrames must be >= 1; got " + expectedFrames);
        }
        if (shipCountBound < 1) {
            throw new IllegalArgumentException("shipCountBound must be >= 1; got " + shipCountBound);
        }
        if (!Float.isFinite(fixedDt) || fixedDt <= 0f) {
            throw new IllegalArgumentException("fixedDt must be finite and > 0; got " + fixedDt);
        }
        this.expectedFrames = expectedFrames;
        this.shipCountBound = shipCountBound;
        this.fixedDt = fixedDt;
        this.contentRegistry = contentRegistry;
        this.ownerByShipId = ownerByShipId == null ? Map.of() : Map.copyOf(ownerByShipId);
        this.authoredProjectiles = authoredProjectiles;
        this.stableIdRegistry = stableIdRegistry;
        this.ownerByStableKey = ownerByStableKey == null ? Map.of() : Map.copyOf(ownerByStableKey);
        this.matchOverArming = matchOverArming;
        mintFractureDetector();
        this.simTick = 0L;
    }

    /**
     * Stable-roster source: each published snapshot keys a
     * ship's network {@code entityId} on the engine's <em>stable</em> id
     * ({@code ShipAPI.getId()}) interned through {@code stableIdRegistry}, and the
     * roster may shrink (ship destroyed/removed → dropped) or grow (reinforcement)
     * tick to tick. Survivors keep their {@code entityId} when another ship leaves —
     * the categorically new capability the position-keyed constructors cannot provide
     * (they assign {@code entityId = repository position}, which renumbers survivors,
     * and hard-throw on a ship-count change).
     *
     * <p>A factory (not another telescoping constructor) is required because the
     * stable-mode owner map is {@code Map<String,Integer>} while the position-mode
     * owner map is {@code Map<Long,Integer>}; those two erase to the same constructor
     * signature. {@code maxShipCount} bounds the full per-tick roster read to
     * {@code [1, maxShipCount]} (fail-closed sanity), replacing the position mode's
     * fixed-count equality. The command-authority / position path is untouched:
     * this mode is additive and opt-in.
     */
    public static EngineBackedLiveWorldSnapshotSource stableRoster(
            CombatStepBoundary step,
            EngineCombatBinding binding,
            int expectedFrames,
            int maxShipCount,
            float fixedDt,
            ContentIdRegistry contentRegistry,
            ShipNetworkIdRegistry stableIdRegistry,
            Map<String, Integer> ownerByStableKey,
            ServerAuthoredProjectileField authoredProjectiles) {
        Objects.requireNonNull(stableIdRegistry, "stableIdRegistry");
        return new EngineBackedLiveWorldSnapshotSource(
                step, binding, expectedFrames, maxShipCount, fixedDt,
                contentRegistry, Map.of(), authoredProjectiles, stableIdRegistry, ownerByStableKey,
                false);
    }

    /**
     * {@link #stableRoster} with match-over ARMING — the serveNPeer
     * per-round source. Both match-over triggers (the {@code aliveCount==0} roster-0 flip and the
     * {@code cc.server.perSideRoundEnd} one-side-eliminated end) stay SUPPRESSED until at least
     * two fighting sides ({@code owner != 100}) have had a live ship this round, observed
     * cumulatively across ticks off the live allegiance read — so a gated zero-hull boot's frame-0
     * {@code aliveCount==0} cannot latch {@code matchOver} before anyone joins, and a solo first
     * fly-in cannot end the round it starts. Once armed the triggers are exactly the legacy ones
     * (and {@code matchOver} still latches). Because the arming observation needs the allegiance
     * tier, a capture in arming mode fails closed ({@link IOException}) while unarmed if
     * {@link EngineCombatBinding#shipAllegiancesOrNull()} is null — an npeer serve whose rounds
     * could never end must fail loudly, not idle forever. Legacy callers keep {@link #stableRoster}
     * (byte-identical: no allegiance read, no arming log line).
     */
    public static EngineBackedLiveWorldSnapshotSource stableRosterMatchOverArmed(
            CombatStepBoundary step,
            EngineCombatBinding binding,
            int expectedFrames,
            int maxShipCount,
            float fixedDt,
            ContentIdRegistry contentRegistry,
            ShipNetworkIdRegistry stableIdRegistry,
            Map<String, Integer> ownerByStableKey,
            ServerAuthoredProjectileField authoredProjectiles) {
        Objects.requireNonNull(stableIdRegistry, "stableIdRegistry");
        return new EngineBackedLiveWorldSnapshotSource(
                step, binding, expectedFrames, maxShipCount, fixedDt,
                contentRegistry, Map.of(), authoredProjectiles, stableIdRegistry, ownerByStableKey,
                true);
    }

    /** The content catalog this source has interned so far (empty when neutral). */
    public ContentIdRegistry contentRegistry() {
        return contentRegistry;
    }

    /** The server-authored projectile tier this source folds in, or null if none. */
    public ServerAuthoredProjectileField authoredProjectiles() {
        return authoredProjectiles;
    }

    @Override
    public int expectedFrames() {
        return expectedFrames;
    }

    /**
     * True once a published stable-roster frame observed ZERO live ships — the faithful
     * end-of-match signal. The tick that flips this still produces a valid
     * roster-0 snapshot (every {@code !alive} ship dropped); a serve loop should publish that
     * final frame — so the client observes the last death(s) — and then end the session cleanly
     * rather than treating the next {@code alive==0} read as an error. Always false in
     * position-keyed mode (which fail-closes on any roster change before a match can reach zero
     * live ships through this source).
     */
    public boolean matchOver() {
        return matchOver;
    }

    /**
     * The {@code logRosterDump} tag the {@link #matchOver()} latch fired
     * under — {@code match-over-roster-0} / {@code match-over-base-hulk} /
     * {@code match-over-per-side} / the wave-clear latch's configured tag
     * ({@code match-over-fortress-cleared} on the fortress serve). {@code null} until latched;
     * first latch wins and is never reset (the {@link #matchOver()} latch semantics).
     */
    public String matchOverTag() {
        return matchOverTag;
    }

    /**
     * The FleetSide (0/1) that WON the latched outcome; {@code -1} when the
     * outcome has no victor (roster-0 mutual kill, or a fail-soft side read). Meaningless until
     * {@link #matchOverTag()} is non-null.
     */
    public int matchOverWinnerSide() {
        return matchOverWinnerSide;
    }

    /** The sim tick the {@link #matchOver()} latch fired on; -1 until then. */
    public long matchOverSimTick() {
        return matchOverSimTick;
    }

    /** First-latch-wins outcome record; call sites sit inside the !matchOver guards, and the
     * method is ITSELF first-writer — the latched verdict is what the MOVR
     * fan puts on the wire, so a later mis-guarded call must lose here, not overwrite it. */
    private void latchMatchOverOutcome(String tag, int winnerSide, long simTick) {
        if (matchOverTag != null) {
            return;
        }
        this.matchOverTag = tag;
        this.matchOverWinnerSide = winnerSide;
        this.matchOverSimTick = simTick;
    }

    /**
     * Whether the match-over triggers are LIVE for this source (the observability
     * gauge for the serveNPeer rung-0 line). Trivially {@code true} outside arming mode (a legacy
     * source's triggers are live from tick 0); in arming mode
     * ({@link #stableRosterMatchOverArmed}), {@code true} only once two fighting sides have been
     * observed with live ships this round.
     */
    public boolean matchOverArmed() {
        return !matchOverArming || matchOverArmed;
    }

    /**
     * Arm the roster-0 hold. While {@code supplier} reports {@code true}, a
     * stable-roster tick reading ZERO live ships does <em>not</em> latch {@link #matchOver()} —
     * the serve loop keeps publishing roster-0 frames so seats awaiting a respawn fly-in get
     * their remount (the mutual-kill-in-overlap case: both hulls dead inside the cooldown window
     * must NOT end the round). The supplier is consulted only on zero-alive ticks; when it turns
     * {@code false} there (every seat vacated or dead-latched — a terminal-empty battlefield),
     * the tick latches exactly as before. Null (the default) is byte-identical to the pre-hold
     * source. Respawn-enabled serve lanes pass
     * {@code MatchSeatAuthority::anySeatAwaitingRemount}; set post-mint so the five
     * {@code stableRoster(...)} factory call sites keep their signatures.
     */
    public void setRespawnAwaitingRemount(BooleanSupplier supplier) {
        this.respawnAwaitingRemount = supplier;
    }

    /**
     * Arm the god-mode replica bit
     * ({@code ShipFluxSnapshot.FLAG_GOD}): {@link #foldFluxTier} calls {@code supplier} ONCE per
     * publish and stamps the bit on every flux record whose published wire id the returned
     * predicate accepts. The supplier (the serve passes
     * {@code AdminSeatDebugLane::godAppliedWirePredicateOrNull}) returns null when no hull carries
     * the god-mode mults, so the fold then does no per-ship work — a Supplier, not a
     * fixed predicate, because a fixed field could never return to null. Post-mint
     * setter idiom ({@link #setRespawnAwaitingRemount}); the serve re-arms it on EVERY round's
     * fresh source (a serve-once arm goes dark from round 2 on). Null (the default) =
     * flags byte-identical to the pre-bit fold.
     */
    public void setGodAppliedWire(Supplier<LongPredicate> supplier) {
        this.godAppliedWire = supplier;
    }

    /**
     * Arm the travel-drive fire-lock bit
     * ({@code ShipFluxSnapshot.FLAG_DRIVE_LOCK}, D3) — the {@link #setGodAppliedWire} idiom:
     * {@link #foldFluxTier} calls {@code supplier} ONCE per publish and stamps the bit on every
     * flux record whose published wire id the returned predicate accepts. The serve passes
     * {@code EngineCombatBinding::driveLockWirePredicateOrNull}, which covers seat hulls only and
     * returns null while no seat hull's travel drive blocks firing (the fold then does no
     * per-ship work). Re-armed on EVERY round's fresh source. Null (the default — every
     * non-pilot serve) = no lock bit, flags byte-identical to the pre-bit fold.
     */
    public void setDriveLockWire(Supplier<LongPredicate> supplier) {
        this.driveLockWire = supplier;
    }

    /**
     * Arm the respawn-compatible wave-clear trigger (feed/latch split).
     * Each stable-roster tick that reaches the trigger (armed, no latch yet, live ships
     * present) counts {@code enemySide}'s live hulls off the allegiance read and feeds the
     * count to {@code enemyLiveObserver} (the {@code WaveSchedule} spawn-on-clear input —
     * the continuous fight's wave pacing). Only when {@code latchRoundEnd} is true does a
     * ZERO count while {@code scheduleDrained} reports every scheduled wave spawned also
     * latch {@link #matchOver()} (the per-clear reset option); inter-wave zero-enemy
     * gaps can never end the round either way, and the arming suppression (≥2 fighting sides
     * seen) still gates the whole trigger including the feed. Null-free args; set post-mint
     * (the {@link #setRespawnAwaitingRemount} idiom — the five {@code stableRoster(...)}
     * factory call sites keep their signatures).
     */
    public void setWaveClearRoundEnd(int enemySide, LongConsumer enemyLiveObserver,
                                     BooleanSupplier scheduleDrained, boolean latchRoundEnd) {
        setWaveClearRoundEnd(enemySide, enemyLiveObserver, scheduleDrained, latchRoundEnd,
                "match-over-wave-clear", 0L);
    }

    /**
     * {@link #setWaveClearRoundEnd(int, LongConsumer, BooleanSupplier, boolean)}
     * with the roster-dump TAG the latch fires under. The fortress serve passes
     * {@code match-over-fortress-cleared} — its DEFEND victory and its
     * {@code match-over-base-hulk} defeat must never read alike in a log. Blank is refused
     * (a nameless round end is exactly what this exists to prevent).
     *
     * <p>{@code clearHoldTicks} makes the LATCH require the zero count
     * SUSTAINED that many capture ticks (the {@code WaveSchedule.CLEAR_HOLD_SEC} law, same
     * threaded constant) — a momentary zero the tick the last Omega parents die is not a won
     * field: their shard children fade in 2–3 s later, and an instant latch would declare the
     * DEFEND victory with live enemies about to spawn. 0 = the pre-1312 instant latch (the
     * 4-arg overload's legacy rows). The FEED is untouched — {@code WaveSchedule} applies its
     * own hold on the spawn side.
     */
    public void setWaveClearRoundEnd(int enemySide, LongConsumer enemyLiveObserver,
                                     BooleanSupplier scheduleDrained, boolean latchRoundEnd,
                                     String latchTag, long clearHoldTicks) {
        this.waveClearEnemySide = enemySide;
        this.waveClearEnemyLiveObserver = Objects.requireNonNull(enemyLiveObserver, "enemyLiveObserver");
        this.waveClearScheduleDrained = Objects.requireNonNull(scheduleDrained, "scheduleDrained");
        this.waveClearLatchRoundEnd = latchRoundEnd;
        Objects.requireNonNull(latchTag, "latchTag");
        if (latchTag.isBlank()) {
            throw new IllegalArgumentException("wave-clear latch tag must be non-blank");
        }
        this.waveClearLatchTag = latchTag;
        if (clearHoldTicks < 0L) {
            throw new IllegalArgumentException(
                    "wave-clear latch hold must be >= 0 ticks, got " + clearHoldTicks);
        }
        this.waveClearLatchHoldTicks = clearHoldTicks;
    }

    /**
     * Arm the STAR FORTRESS lose seam. Each stable-roster tick that
     * reaches the trigger (armed, no latch yet, no roster-0 hold) consults {@code supplier} —
     * the serve loop's {@code NPeerHooks.StationHook.baseLost()}, i.e. the deployed station
     * BASE hull's engine-native {@code isHulk()} — and latches {@link #matchOver()} on true,
     * dumping the repo roster under {@code match-over-base-hulk} first (the
     * {@code match-over-wave-clear} precedent). Module deaths are ordinary attrition and never
     * reach here: the base is the objective. The supplier THROWS rather than degrading (a
     * broken read must not keep a dead fortress's round alive), so it is deliberately consulted
     * only inside the guards. Null (the default) is byte-identical to the pre-station source;
     * set post-mint (the {@link #setRespawnAwaitingRemount} idiom — the five
     * {@code stableRoster(...)} factory call sites keep their signatures).
     *
     * <p>{@code stationSide} = the station's OWN FleetSide (0/1, the descriptor roster row's
     * side). A base-hulk latch records the opposed side as the winner (
     * derived from the station itself so the reachable lose-seam-alone shape, station armed
     * with no wave trigger, still names the attacker).
     */
    public void setStationLost(BooleanSupplier supplier, int stationSide) {
        this.stationLost = Objects.requireNonNull(supplier, "stationLost");
        if (stationSide != 0 && stationSide != 1) {
            throw new IllegalArgumentException(
                    "stationSide must be the station's FleetSide 0 or 1; got " + stationSide);
        }
        this.stationOwnSide = stationSide;
    }

    /**
     * Drain the hulk-fracture edges the detector emitted since the last drain
     * (the serve loop fans each as an {@code HKFR} ctrl record). Always empty in
     * position-keyed mode (no registry — no detector).
     */
    public List<cc.starsector.server.runtime.HulkFractureFrameCodec.FractureEdge>
            drainFractureEdges() {
        return fractureDetector == null ? List.of() : fractureDetector.drainEdges();
    }

    /**
     * Arm periodic corpse-pose keyframes ({@code HKPS}) on the fracture
     * detector — {@code poseKeyframeEveryTicks} between per-family keyframes, 0 (the mint
     * default) = OFF so no keyframes accumulate for lanes that never drain them. Re-mints the
     * detector, so call post-mint BEFORE the first observe (the
     * {@link #setRespawnAwaitingRemount} precedent — the five {@code stableRoster(...)}
     * factory call sites keep their signatures); no-op in position-keyed mode (no registry —
     * no detector).
     */
    public void setHulkPoseCadence(int poseKeyframeEveryTicks) {
        this.hulkPoseCadenceTicks = poseKeyframeEveryTicks;
        mintFractureDetector();
    }

    /**
     * Attach the serve's shared {@link ArmorCellBook} (post-mint
     * setter idiom — the source is fresh per round, the book lives per serve). Every
     * alive ship's quantized cell image is published per tick under its ENTITY id at
     * the stable-roster pairing. Null = cells dark (knob off), observe never runs.
     */
    public void setArmorCellBook(ArmorCellBook book) {
        this.armorCellBook = book;
    }

    /**
     * Arm the impulse burst on the fracture detector — a contact Δv (vs the
     * last EMITTED keyframe) emits that family's keyframes at {@code burstCadenceTicks} while
     * the deviation stands (change-gated; the time window is retired);
     * 0 (the mint default) = periodic cadence only. Re-mints the detector, so call post-mint
     * BEFORE the first observe (the {@link #setHulkPoseCadence} idiom); no-op in position-keyed
     * mode.
     */
    public void setHulkPoseBurst(int burstCadenceTicks) {
        this.hulkPoseBurstCadenceTicks = burstCadenceTicks;
        mintFractureDetector();
    }

    /**
     * Arm the cut-geometry capture rider ({@code HKCG}) on the fracture
     * detector — OFF at mint (the pose-cadence accumulate-guard precedent) so no records queue
     * for lanes that never drain them. Re-mints the detector, so call post-mint BEFORE the
     * first observe (the {@link #setHulkPoseCadence} idiom); no-op in position-keyed mode.
     * The server knob {@code cc.net.hulkCutGeometry} lands here.
     */
    public void setHulkCutGeometry(boolean on) {
        this.hulkCutGeometryOn = on;
        mintFractureDetector();
    }

    /** One detector mint honoring both post-mint products (the pose cadence and the cut-geometry capture);
     * position-keyed mode (no registry) has no detector, matching every prior mint. */
    private void mintFractureDetector() {
        this.fractureDetector = stableIdRegistry == null
                ? null
                : new cc.starsector.server.runtime.HulkFractureDetector(
                        stableIdRegistry::networkIdForOrNull,
                        HulkDestroyedFlagReader::wasDestroyed,
                        hulkPoseCadenceTicks,
                        hulkCutGeometryOn ? binding::hulkRingsOrNull : null,
                        hulkPoseBurstCadenceTicks);
    }

    /**
     * Round-arm corpse-read warm on ONE live roster ship — call
     * post-roster-deploy, pre-frame-0; read-only (the binding helper's doc carries the
     * static-RE verdict) and fail-soft (compact receipt string, never throws). Position-keyed
     * mode has no fracture lane, but the reads are read-only regardless — always delegated.
     */
    public String warmRemnantReadPaths() {
        return binding.warmRemnantReadPaths();
    }

    /**
     * Round-arm capture-lane warm — pure-JVM classload +
     * accessor pre-resolution for the point-entity classes the per-tick capture
     * discriminates on (fail-soft receipt string, never throws; the binding helper's doc).
     */
    public String warmCaptureLaneClasses() {
        return binding.warmCaptureLaneClasses();
    }

    /** Meter: impulse detections on the fracture detector (0 with no detector). */
    public long hulkPoseImpulseTriggers() {
        return fractureDetector == null ? 0L : fractureDetector.impulseTriggers();
    }

    /** Meter: burst-cadence keyframe sets emitted (0 with no detector). */
    public long hulkPoseBurstEmits() {
        return fractureDetector == null ? 0L : fractureDetector.burstEmits();
    }

    /** Meter: the worst hulk observe tick (remnant read + fold), ns. */
    public long hulkObserveNsMax() {
        return hulkObserveNsMax;
    }

    /** Meter: the mean hulk observe tick over the ticks the lane ran, ns. */
    public long hulkObserveNsAvg() {
        return hulkObserveTicks == 0L ? 0L : hulkObserveNsTotal / hulkObserveTicks;
    }

    /**
     * Drain the corpse-pose keyframes the detector emitted since the last
     * drain — the serve loop fans each as an {@code HKPS} ctrl record AFTER the same tick's
     * {@code HKFR} edges (same reliable spool, rank-race-free). Always empty in position-keyed
     * mode or with the cadence unset (0 = OFF).
     */
    public List<cc.starsector.server.runtime.HulkPoseFrameCodec.PoseKeyframe>
            drainHulkPoseKeyframes() {
        return fractureDetector == null ? List.of() : fractureDetector.drainPoseKeyframes();
    }

    /**
     * Wire ids of corpse families the fracture detector saw born during THIS
     * tick's {@code publishNextTick} — non-destructive (unlike the drains above), read by the
     * serve loop between the despawn-detector observe and its two consumers (EDSP compose +
     * T3 seat loop) to correct a DEPARTED misread on a same-tick kill+despawn. Empty in
     * position-keyed mode (no detector) — the correction simply never engages there.
     */
    public java.util.Set<Long> hulkFamilyRootsBornThisTick() {
        return fractureDetector == null ? java.util.Set.of()
                : fractureDetector.familyRootsBornLastObserve();
    }

    /**
     * Drain the hulk-cut-geometry records the detector captured since the last
     * drain — the serve loop fans each as an {@code HKCG} ctrl record BEFORE the same tick's
     * {@code HKFR} edges (FIFO spool ⇒ the client latch is warm at edge arrival). Always empty
     * in position-keyed mode or with capture unset (OFF at mint).
     */
    public List<cc.starsector.server.runtime.HulkCutGeometryFrameCodec.CutGeometryRecord>
            drainCutGeometryRecords() {
        return fractureDetector == null ? List.of() : fractureDetector.drainCutGeometryRecords();
    }

    /**
     * Drain the cut-geometry capture SKIPS the detector recorded since the
     * last drain — the serve loop prints one {@code hulk_cut_geometry_skip} INFO row per
     * descriptor and sums them into the {@code hkcg_skips=} counter-tail field. Always empty
     * in position-keyed mode or with capture unset (OFF skips nothing).
     */
    public List<cc.starsector.server.runtime.HulkFractureDetector.CutGeometrySkip>
            drainCutGeometrySkips() {
        return fractureDetector == null ? List.of() : fractureDetector.drainCutGeometrySkips();
    }

    // The step bracket's advance/capture sub-stamps — the stall
    // witness prints them so an engine-internal spike and a capture-tree (reflective
    // read / print) spike stop sharing one bracket. Serve-thread-only write+read.
    private long lastAdvanceNanos;
    private long lastCaptureNanos;

    @Override
    public AuthoritativeWorldSnapshot publishNextTick() throws IOException {
        long advanceStartNanos = System.nanoTime();
        step.step(fixedDt, simTick);
        long advanceEndNanos = System.nanoTime();
        lastAdvanceNanos = advanceEndNanos - advanceStartNanos;
        AuthoritativeWorldSnapshot snapshot = captureCurrentTick();
        lastCaptureNanos = System.nanoTime() - advanceEndNanos;
        return snapshot;
    }

    /** The last {@link #publishNextTick}'s engine-advance nanos (0 before the first tick). */
    public long lastAdvanceNanos() {
        return lastAdvanceNanos;
    }

    // The advance bracket's own sub-split, straight from the step boundary —
    // adv= (above) = engine inner advance + project post-step; these name each part, and
    // ftreinj= is the reinject shim's slice INSIDE the engine advance (0 on game-free lanes).

    /** The last step's engine-inner-advance nanos ({@link CombatStepBoundary#lastEngineAdvanceNanos}). */
    public long lastEngineAdvanceNanos() {
        return step.lastEngineAdvanceNanos();
    }

    /** The last step's project post-step nanos ({@link CombatStepBoundary#lastPostStepNanos}). */
    public long lastPostStepNanos() {
        return step.lastPostStepNanos();
    }

    /** The last step's reinject-shim nanos ({@link CombatStepBoundary#lastReinjectNanos}). */
    public long lastReinjectNanos() {
        return step.lastReinjectNanos();
    }

    /** The last step's sim-thread CPU nanos inside the engine advance ({@link CombatStepBoundary#lastEngineAdvanceCpuNanos}; -1 = unmeasurable). */
    public long lastEngineAdvanceCpuNanos() {
        return step.lastEngineAdvanceCpuNanos();
    }

    /** The last {@link #publishNextTick}'s capture nanos (0 before the first tick). */
    public long lastCaptureNanos() {
        return lastCaptureNanos;
    }

    /** The last capture's position→network id map (empty before the first capture). */
    private Map<Long, Long> lastEntityIdByPositionShipId = Map.of();

    // Hold-last side-totals — pre-first capture (and across any failed
    // capture) the serve loop keeps announcing ZERO, net-identical to vanilla's no-bonus state.
    private cc.starsector.server.runtime.AwsdSnapshotCodec.SideTotalsBlock lastSideTotals =
            cc.starsector.server.runtime.AwsdSnapshotCodec.SideTotalsBlock.ZERO;

    /**
     * The side-totals block read at the LAST {@link #captureCurrentTick()}:
     * the serve loop feeds this to {@code DownEmissionComposer.setSideTotals} right after each
     * publish, so every AWSD v9 frame carries totals from the same read instant as its snapshot.
     * Hold-last on a failed capture; {@code ZERO} before the first.
     */
    public cc.starsector.server.runtime.AwsdSnapshotCodec.SideTotalsBlock lastSideTotals() {
        return lastSideTotals;
    }

    /**
     * The position-shipId→network-entityId map of the LAST captured tick:
     * identity in position mode, the stable-roster registry mapping
     * otherwise — the SAME map the tier folds key the wire by. A caller running the
     * {@code GameplayEdgeEventDetector} over the binding's raw positional views right
     * after a capture passes this so emitted GPEE entityIds are wire ids, not
     * deploy-order coincidences. Read-only; empty before the first capture (fail-closed:
     * every view skips).
     */
    public Map<Long, Long> lastEntityIdByPositionShipId() {
        return lastEntityIdByPositionShipId;
    }

    /**
     * Capture-only half of {@link #publishNextTick()}: read the
     * per-ship views off the live binding and assemble one frame WITHOUT stepping the
     * engine. For a caller that drives the engine itself (the fleet-bench loop steps
     * via its own timed {@code advanceInner} and must never double-step), this is the
     * wire-width sampling entry point: same views, same folds, same fail-closed
     * checks, same snapshot bytes as a published tick. Increments the internal
     * {@code simTick} per capture (the snapshot's tick counts captures, not engine
     * frames, when driven externally).
     */
    public AuthoritativeWorldSnapshot captureCurrentTick() throws IOException {
        List<ShipMotionView> motions = binding.shipMotionsOrNull();
        List<ShipDurabilityView> durabilities = binding.shipDurabilitiesOrNull();
        if (motions == null || durabilities == null) {
            throw new IOException("engine views unavailable at tick " + simTick
                    + " (motions=" + (motions == null ? "null" : motions.size())
                    + ", durabilities=" + (durabilities == null ? "null" : durabilities.size()) + ")");
        }
        cc.starsector.server.runtime.AwsdSnapshotCodec.SideTotalsBlock sideTotals =
                binding.captureSideTotalsOrNull();
        if (sideTotals != null) {
            lastSideTotals = sideTotals;
        }
        BuiltSnapshot built;
        if (stableIdRegistry != null) {
            built = buildStableRosterSnapshot(motions, durabilities);
        } else {
            if (motions.size() != shipCountBound || durabilities.size() != shipCountBound) {
                throw new IOException("engine view ship count mismatch at tick " + simTick
                        + ": expected " + shipCountBound
                        + ", motions=" + motions.size() + ", durabilities=" + durabilities.size());
            }
            built = buildSnapshot(motions, durabilities);
        }
        AuthoritativeWorldSnapshot snapshot = built.snapshot();
        lastEntityIdByPositionShipId = built.entityIdByPositionShipId();
        List<NonShipEntitySnapshot> nonShipEntities =
                readNonShipEntities(built.entityIdByPositionShipId());
        if (!nonShipEntities.isEmpty()) {
            snapshot = AuthoritativeWorldSnapshot.withNonShipEntities(snapshot, nonShipEntities);
        }
        // ONE phase-cloak read serves both the flux fold's FLAG_PHASED
        // cross-ref and the v10 phase-cloak tier fold below, so the per-frame collision
        // bit and the cloak state record are the same read instant's truth.
        List<ShipPhaseCloakView> cloakViews = binding.shipPhaseCloakStatesOrNull();
        snapshot = foldFluxTier(snapshot, built.entityIdByPositionShipId(), cloakViews);
        List<ShipFluxView> fluxAfterFold = fluxReadInstantProbe ? binding.shipFluxStatesOrNull() : null;
        long fluxAfterFoldNanos = fluxReadInstantProbe ? System.nanoTime() : 0L;
        snapshot = foldWeaponMountTier(snapshot, built.entityIdByPositionShipId());
        snapshot = foldAllegianceTier(snapshot, built.entityIdByPositionShipId());
        snapshot = foldEngineStateTier(snapshot, built.entityIdByPositionShipId());
        snapshot = foldManeuverCommandTier(snapshot, built.entityIdByPositionShipId());
        snapshot = foldSystemStateTier(snapshot, built.entityIdByPositionShipId());
        snapshot = foldPhaseCloakTier(snapshot, built.entityIdByPositionShipId(), cloakViews);
        snapshot = foldCrTier(snapshot, built.entityIdByPositionShipId());
        snapshot = foldContentDictionaryTier(snapshot);
        if (fluxReadInstantProbe) {
            probeFluxReadInstant(snapshot, built.entityIdByPositionShipId(),
                    fluxAfterFold, fluxAfterFoldNanos);
        }
        simTick++;
        return snapshot;
    }

    /**
     * The flux read-instant probe — see {@link #fluxReadInstantProbe}. Compares, per ship, the folded
     * wire {@code currFlux} against two later same-tick live re-reads (p1 = immediately after the
     * flux fold, p2 = capture return) and prints one {@code flux-probe} row per ship-tick where
     * any pair differs (exact float compare — the engine's own decay slope IS the signal here,
     * not noise) plus an unconditional row at parity-walk tick multiples (the offline p2→walk
     * join needs the fold/p1/p2 values at every walked tick even when they agree). Never throws;
     * the row cap bounds a worst-case every-tick logger (walk-cadence rows stay uncapped — they
     * are bounded by the frame budget).
     */
    private void probeFluxReadInstant(AuthoritativeWorldSnapshot snapshot,
            Map<Long, Long> entityIdByPositionShipId,
            List<ShipFluxView> afterFold, long afterFoldNanos) {
        try {
            List<ShipFluxView> atReturn = binding.shipFluxStatesOrNull();
            long atReturnNanos = System.nanoTime();
            if (!fluxProbeArmedLogged) {
                fluxProbeArmedLogged = true;
                System.out.println("flux-probe armed cap=" + FLUX_PROBE_ROW_CAP
                        + " walkEvery=" + fluxProbeWalkEvery);
            }
            List<ShipFluxSnapshot> folded = snapshot.fluxStates();
            if (afterFold == null || atReturn == null || folded.isEmpty()) {
                return;
            }
            boolean walkTick = fluxProbeWalkEvery > 0 && snapshot.simTick() % fluxProbeWalkEvery == 0L;
            Map<Long, ShipFluxView> returnByShipId = new HashMap<>();
            for (ShipFluxView view : atReturn) {
                returnByShipId.put(view.shipId(), view);
            }
            Map<Long, ShipFluxSnapshot> foldedByEntityId = new HashMap<>();
            for (ShipFluxSnapshot state : folded) {
                foldedByEntityId.put(state.entityId(), state);
            }
            for (ShipFluxView p1 : afterFold) {
                Long entityId = entityIdByPositionShipId.get(p1.shipId());
                if (entityId == null) {
                    continue;
                }
                ShipFluxSnapshot fold = foldedByEntityId.get(entityId);
                ShipFluxView p2 = returnByShipId.get(p1.shipId());
                if (fold == null || p2 == null) {
                    continue;
                }
                boolean differs = fold.currFlux() != p1.currFlux() || p1.currFlux() != p2.currFlux();
                if (differs && fluxProbeRowsLogged >= FLUX_PROBE_ROW_CAP) {
                    differs = false; // delta rows capped; walk-cadence rows still emit below
                }
                if (differs || walkTick) {
                    if (differs) {
                        fluxProbeRowsLogged++;
                    }
                    System.out.println("flux-probe tick=" + snapshot.simTick()
                            + " entity=" + entityId
                            + " fold=" + fold.currFlux()
                            + " p1=" + p1.currFlux()
                            + " p2=" + p2.currFlux()
                            + " dns=" + (atReturnNanos - afterFoldNanos)
                            + " walkTick=" + walkTick);
                    if (differs && fluxProbeRowsLogged == FLUX_PROBE_ROW_CAP) {
                        System.out.println("flux-probe cap-reached tick=" + snapshot.simTick());
                    }
                }
            }
        } catch (RuntimeException ignore) {
            // the instrument must never break the serve loop
        }
    }

    /**
     * Fold the per-ship engine-disabled/flameout tier (schema v6) onto
     * {@code snapshot}, fail-soft, after the allegiance tier. The bridge's
     * {@link EngineCombatBinding#shipEngineStatesOrNull()} returns one
     * {@link ShipEngineStateView} per installed ship (per-engine disabled/perm-disabled
     * bitmasks off the public {@code getShipEngines()} surface plus the controller's
     * derived flameout code), each tagged with its owning ship's within-tick repository
     * {@code shipId}. Each view is keyed to its ship's network {@code entityId} via
     * {@code entityIdByPositionShipId}; a state whose ship is not in the snapshot — a
     * ship dropped as {@code !alive} this tick — is skipped so an engine state never
     * resurrects a despawned ship (mirroring the lower folds). A null read (binding
     * without the engine-state handles) or an empty result leaves the frame at its prior
     * schema. This is what lets a predictive client apply the real per-engine
     * {@code disable(perm)}/{@code repair()} mechanism to its local foe instead of
     * diverging on locally-derived flameout (the WATCH desync).
     */
    private AuthoritativeWorldSnapshot foldEngineStateTier(
            AuthoritativeWorldSnapshot snapshot, Map<Long, Long> entityIdByPositionShipId) {
        List<ShipEngineStateView> engineViews = binding.shipEngineStatesOrNull();
        if (engineViews == null || engineViews.isEmpty()) {
            return snapshot;
        }
        List<ShipEngineStateSnapshot> engineStates = new ArrayList<>(engineViews.size());
        for (ShipEngineStateView engineView : engineViews) {
            if (engineView == null) {
                continue;
            }
            Long entityId = entityIdByPositionShipId.get(engineView.shipId());
            if (entityId == null) {
                continue; // ship absent from this frame (dropped/dead): no engine-state record
            }
            engineStates.add(ShipEngineStateSnapshot.fromView(engineView, entityId));
        }
        if (engineStates.isEmpty()) {
            return snapshot;
        }
        return AuthoritativeWorldSnapshot.withEngineStates(snapshot, engineStates);
    }

    /**
     * Fold the per-ship maneuver-command tier (schema v7) onto {@code snapshot},
     * fail-soft, after the engine-state tier. The bridge's
     * {@link EngineCombatBinding#shipManeuverCommandsOrNull()} returns one
     * {@link ShipManeuverCommandView} per installed ship (the 7 engine-controller maneuver
     * booleans packed in canonical wire-bit order), each tagged with its owning ship's
     * within-tick repository {@code shipId}. Each view is keyed to its ship's network
     * {@code entityId} via {@code entityIdByPositionShipId}; a state whose ship is not in
     * the snapshot — a ship dropped as {@code !alive} this tick — is skipped so a maneuver
     * record never resurrects a despawned ship (mirroring the lower folds). A null read
     * (binding without the maneuver-command handles) or an empty result leaves the frame
     * at its prior schema. This is what lets a predictive client re-issue the foe's real
     * maneuver commands through the local {@code giveCommand} path, so the engine's own
     * controller drives thrust-plume/glow/contrail (and engine audio) presentation instead
     * of idling on a pose-snapped replica.
     */
    private AuthoritativeWorldSnapshot foldManeuverCommandTier(
            AuthoritativeWorldSnapshot snapshot, Map<Long, Long> entityIdByPositionShipId) {
        List<ShipManeuverCommandView> maneuverViews = binding.shipManeuverCommandsOrNull();
        if (maneuverViews == null || maneuverViews.isEmpty()) {
            return snapshot;
        }
        List<ShipManeuverCommandSnapshot> maneuverCommands = new ArrayList<>(maneuverViews.size());
        for (ShipManeuverCommandView maneuverView : maneuverViews) {
            if (maneuverView == null) {
                continue;
            }
            Long entityId = entityIdByPositionShipId.get(maneuverView.shipId());
            if (entityId == null) {
                continue; // ship absent from this frame (dropped/dead): no maneuver record
            }
            maneuverCommands.add(ShipManeuverCommandSnapshot.fromView(maneuverView, entityId));
        }
        if (maneuverCommands.isEmpty()) {
            return snapshot;
        }
        return AuthoritativeWorldSnapshot.withManeuverCommands(snapshot, maneuverCommands);
    }

    /**
     * Fold the per-ship primary ship-system tier (schema v8) onto
     * {@code snapshot}, fail-soft, after the maneuver-command tier. The bridge's
     * {@link EngineCombatBinding#shipSystemStatesOrNull()} returns one
     * {@link ShipSystemView} per installed ship that owns a primary system (the
     * public {@code getSystem()} surface), each tagged with its owning ship's within-tick
     * repository {@code shipId}. Each view is keyed to its ship's network {@code entityId}
     * via {@code entityIdByPositionShipId}; a state whose ship is not in the snapshot — a
     * ship dropped as {@code !alive} this tick — is skipped so a system state never
     * resurrects a despawned ship (mirroring the lower folds). A null read (binding
     * without the system handles) or an empty result leaves the frame at its prior
     * schema. The view's engine state NAME and ammo are mapped to PROJECT-OWNED wire
     * values inside {@link ShipSystemStateSnapshot#fromView} — never an engine ordinal.
     * This is what lets a predictive client seed a mid-window replica system through the
     * engine's own faithful {@code forceState} path instead of idling a
     * system whose activation edge it never saw.
     */
    private AuthoritativeWorldSnapshot foldSystemStateTier(
            AuthoritativeWorldSnapshot snapshot, Map<Long, Long> entityIdByPositionShipId) {
        List<ShipSystemView> systemViews = binding.shipSystemStatesOrNull();
        if (systemViews == null || systemViews.isEmpty()) {
            return snapshot;
        }
        List<ShipSystemStateSnapshot> systemStates = new ArrayList<>(systemViews.size());
        for (ShipSystemView systemView : systemViews) {
            if (systemView == null) {
                continue;
            }
            Long entityId = entityIdByPositionShipId.get(systemView.shipId());
            if (entityId == null) {
                continue; // ship absent from this frame (dropped/dead): no system-state record
            }
            systemStates.add(ShipSystemStateSnapshot.fromView(systemView, entityId));
        }
        if (systemStates.isEmpty()) {
            return snapshot;
        }
        return AuthoritativeWorldSnapshot.withSystemStates(snapshot, systemStates);
    }

    /**
     * Fold the per-ship phase-cloak tier (schema v10) onto {@code snapshot},
     * fail-soft, after the system-state tier. {@code cloakViews} is the SAME
     * {@link EngineCombatBinding#shipPhaseCloakStatesOrNull()} read the flux fold's
     * {@code FLAG_PHASED} cross-ref consumed (one entry per cloak-bearing ship off the
     * separate {@code getPhaseCloak()} slot — a Grendel's primary system is its
     * ammofeed), read ONCE per capture so the bit and the record agree. Each view is
     * keyed to its ship's network {@code entityId} via {@code entityIdByPositionShipId};
     * a state whose ship is not in the snapshot — a ship dropped as {@code !alive} this
     * tick — is skipped so a cloak state never resurrects a despawned ship (mirroring
     * the lower folds). A null read (binding without the cloak handles) or an empty
     * result leaves the frame at its prior schema. The view's engine state NAME is
     * mapped to PROJECT-OWNED wire values inside
     * {@link ShipPhaseCloakStateSnapshot#fromView} — never an engine ordinal. This is
     * what lets a predictive client seed a mid-window replica cloak through the engine's
     * own faithful {@code forceState} path (D-M2-4's seed half).
     */
    private AuthoritativeWorldSnapshot foldPhaseCloakTier(
            AuthoritativeWorldSnapshot snapshot, Map<Long, Long> entityIdByPositionShipId,
            List<ShipPhaseCloakView> cloakViews) {
        if (cloakViews == null || cloakViews.isEmpty()) {
            return snapshot;
        }
        List<ShipPhaseCloakStateSnapshot> phaseCloakStates = new ArrayList<>(cloakViews.size());
        for (ShipPhaseCloakView cloakView : cloakViews) {
            if (cloakView == null) {
                continue;
            }
            Long entityId = entityIdByPositionShipId.get(cloakView.shipId());
            if (entityId == null) {
                continue; // ship absent from this frame (dropped/dead): no cloak record
            }
            phaseCloakStates.add(ShipPhaseCloakStateSnapshot.fromView(cloakView, entityId));
        }
        if (phaseCloakStates.isEmpty()) {
            return snapshot;
        }
        return AuthoritativeWorldSnapshot.withPhaseCloakStates(snapshot, phaseCloakStates);
    }

    /**
     * Fold the per-ship COMBAT-READINESS tier (schema v14) onto
     * {@code snapshot}, fail-soft, after the phase-cloak tier. The bridge's
     * {@link EngineCombatBinding#shipCrStatesOrNull()} enrolls EVERY ship (the flux-fold
     * ship set — CR is universal, not slot-gated like the cloak); each view is keyed to
     * its ship's network {@code entityId} via {@code entityIdByPositionShipId}, and a
     * state whose ship is not in the snapshot — a ship dropped as {@code !alive} this
     * tick — is skipped so a CR state never resurrects a despawned ship (mirroring the
     * lower folds). A null read (binding without the CR handles) or an empty result
     * leaves the frame at its prior schema.
     */
    private AuthoritativeWorldSnapshot foldCrTier(
            AuthoritativeWorldSnapshot snapshot, Map<Long, Long> entityIdByPositionShipId) {
        List<ShipCrView> crViews = binding.shipCrStatesOrNull();
        if (crViews == null || crViews.isEmpty()) {
            return snapshot;
        }
        List<ShipCrSnapshot> crStates = new ArrayList<>(crViews.size());
        for (ShipCrView crView : crViews) {
            if (crView == null) {
                continue;
            }
            Long entityId = entityIdByPositionShipId.get(crView.shipId());
            if (entityId == null) {
                continue; // ship absent from this frame (dropped/dead): no CR record
            }
            crStates.add(ShipCrSnapshot.fromView(crView, entityId));
        }
        if (crStates.isEmpty()) {
            return snapshot;
        }
        return AuthoritativeWorldSnapshot.withCrStates(snapshot, crStates);
    }

    /**
     * Fold the content-dictionary tier (schema v15) onto {@code snapshot},
     * after the CR tier: up to {@link #DICT_ENTRIES_PER_FRAME} {@code (contentId, key)}
     * entries from {@link #contentRegistry} so a client can resolve the per-record
     * {@code contentId} ints into version-independent spec keys. Selection: ids minted
     * since the last capture first (ids are CONTIGUOUS 1..size() in mint order —
     * {@link ContentIdRegistry} assigns sequentially from 1 and never deletes — so
     * {@code (dictHighWater, size]} IS the new-mint set), newest first; remaining slots
     * round-robin over the whole catalog via {@link ContentIdRegistry#keyForOrNull}
     * (NEVER {@code snapshot()} — its {@code Map.copyOf} is unordered). A null registry
     * (content-neutral source) or an empty catalog leaves the frame at its prior
     * schema; the tier is frame-global (no entity keying).
     */
    private AuthoritativeWorldSnapshot foldContentDictionaryTier(
            AuthoritativeWorldSnapshot snapshot) {
        if (contentRegistry == null) {
            return snapshot;
        }
        int size = contentRegistry.size();
        if (size == 0) {
            return snapshot;
        }
        List<ContentDictionaryEntry> entries = new ArrayList<>(DICT_ENTRIES_PER_FRAME);
        for (int id = size; id > dictHighWater && entries.size() < DICT_ENTRIES_PER_FRAME;
                id--) {
            String key = contentRegistry.keyForOrNull(id);
            if (key != null) {
                entries.add(new ContentDictionaryEntry(id, key));
            }
        }
        dictHighWater = size;
        // Attempts bounded by one full cycle; skip ids the newest-first leg already
        // carries this frame (the model compact-ctor rejects duplicate contentIds).
        for (int attempts = 0;
                entries.size() < DICT_ENTRIES_PER_FRAME && attempts < size; attempts++) {
            dictCursor = dictCursor >= size ? 1 : dictCursor + 1;
            if (carriesContentId(entries, dictCursor)) {
                continue;
            }
            String key = contentRegistry.keyForOrNull(dictCursor);
            if (key != null) {
                entries.add(new ContentDictionaryEntry(dictCursor, key));
            }
        }
        if (entries.isEmpty()) {
            return snapshot;
        }
        return snapshot.toBuilder().contentDictionary(entries).build();
    }

    private static boolean carriesContentId(List<ContentDictionaryEntry> entries, int id) {
        for (ContentDictionaryEntry entry : entries) {
            if (entry.contentId() == id) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fold the per-ship flux/shield tier (schema v3) onto {@code snapshot},
     * fail-soft. The bridge's {@link EngineCombatBinding#shipFluxStatesOrNull()} is
     * read with the same within-tick repository {@code shipId} the motion/durability
     * views used; each flux view is keyed to its ship's network {@code entityId} via
     * {@code entityIdByPositionShipId} (identity in position mode, the stable-roster
     * map in stable mode). A flux view whose ship is not in the snapshot — e.g. a ship
     * dropped as {@code !alive} this tick — is skipped so flux never resurrects a
     * despawned ship. A null read (binding without a flux handle) or an empty result
     * leaves the frame at v1/v2: this is the live-publish grounding of the
     * real-engine flux read, not a hard requirement.
     */
    private AuthoritativeWorldSnapshot foldFluxTier(
            AuthoritativeWorldSnapshot snapshot, Map<Long, Long> entityIdByPositionShipId,
            List<ShipPhaseCloakView> cloakViews) {
        List<ShipFluxView> fluxViews = binding.shipFluxStatesOrNull();
        if (fluxViews == null || fluxViews.isEmpty()) {
            return snapshot;
        }
        // The FLAG_PHASED bit is the engine's per-frame collision truth
        // (ShipAPI.isPhased()), cross-referenced from the SAME tick's cloak views by the
        // shared within-tick repository shipId. A null cloak read (binding without the
        // cloak handles) or a cloakless ship reads not-phased — the faithful degrade.
        Set<Long> phasedShipIds = null;
        if (cloakViews != null && !cloakViews.isEmpty()) {
            phasedShipIds = new HashSet<>();
            for (ShipPhaseCloakView cloakView : cloakViews) {
                if (cloakView != null && cloakView.phased()) {
                    phasedShipIds.add(cloakView.shipId());
                }
            }
        }
        // One supplier read per publish; null = nothing applied, no per-ship test.
        LongPredicate god = godAppliedWire == null ? null : godAppliedWire.get();
        // The same one-read law for the travel-drive fire-lock bit.
        LongPredicate driveLock = driveLockWire == null ? null : driveLockWire.get();
        List<ShipFluxSnapshot> fluxStates = new ArrayList<>(fluxViews.size());
        for (ShipFluxView fluxView : fluxViews) {
            if (fluxView == null) {
                continue;
            }
            Long entityId = entityIdByPositionShipId.get(fluxView.shipId());
            if (entityId == null) {
                continue; // ship absent from this frame (dropped/dead): no flux record
            }
            boolean phased = phasedShipIds != null && phasedShipIds.contains(fluxView.shipId());
            fluxStates.add(ShipFluxSnapshot.fromView(fluxView, entityId, phased,
                    god != null && god.test(entityId),
                    driveLock != null && driveLock.test(entityId)));
        }
        if (fluxStates.isEmpty()) {
            return snapshot;
        }
        return AuthoritativeWorldSnapshot.withFluxStates(snapshot, fluxStates);
    }

    /**
     * Fold the per-mount weapon-readiness tier (schema v4) onto
     * {@code snapshot}, fail-soft, after the flux tier. The bridge's
     * {@link EngineCombatBinding#weaponMountStatesOrNull()} returns a flat list across
     * the roster, each {@link WeaponMountView} tagged with its owning ship's within-tick
     * repository {@code shipId} (the same id the motion/durability/flux reads used) and a
     * per-ship {@code mountIndex}. Each view is keyed to its ship's network
     * {@code entityId} via {@code entityIdByPositionShipId}; a mount whose ship is not in
     * the snapshot — a ship dropped as {@code !alive} this tick — is skipped so a mount
     * never resurrects a despawned ship (mirroring the flux fold). The weapon spec id is
     * interned through {@link #contentRegistry} (when present and non-blank), else carried
     * as {@link ShipWeaponMountSnapshot#NO_CONTENT}, mirroring {@link #appendNonShip}. A
     * null read (binding without weapon handles) or an empty result leaves the frame at
     * its prior schema: this is the live-publish grounding of the
     * real-engine weapon-mount read, not a hard requirement.
     */
    private AuthoritativeWorldSnapshot foldWeaponMountTier(
            AuthoritativeWorldSnapshot snapshot, Map<Long, Long> entityIdByPositionShipId) {
        List<WeaponMountView> mountViews = binding.weaponMountStatesOrNull();
        if (mountViews == null || mountViews.isEmpty()) {
            return snapshot;
        }
        List<ShipWeaponMountSnapshot> mounts = new ArrayList<>(mountViews.size());
        for (WeaponMountView mountView : mountViews) {
            if (mountView == null) {
                continue;
            }
            Long entityId = entityIdByPositionShipId.get(mountView.shipId());
            if (entityId == null) {
                continue; // owning ship absent from this frame (dropped/dead): no mount record
            }
            int weaponContentId = ShipWeaponMountSnapshot.NO_CONTENT;
            String weaponId = mountView.weaponId();
            if (contentRegistry != null && weaponId != null && !weaponId.isBlank()) {
                weaponContentId = contentRegistry.contentIdFor(weaponId);
            }
            mounts.add(ShipWeaponMountSnapshot.fromView(mountView, entityId, weaponContentId));
        }
        if (mounts.isEmpty()) {
            return snapshot;
        }
        return AuthoritativeWorldSnapshot.withWeaponMounts(snapshot, mounts);
    }

    /**
     * Fold the per-ship combat-side allegiance tier (schema v5) onto
     * {@code snapshot}, fail-soft, after the weapon-mount tier. The bridge's
     * {@link EngineCombatBinding#shipAllegiancesOrNull()} returns one
     * {@link ShipAllegianceView} per installed ship (its {@code owner()} is the combat
     * {@code FleetSide} ordinal), each tagged with its owning ship's within-tick repository
     * {@code shipId} (the same id the motion/durability/flux/weapon reads used). Each view is
     * keyed to its ship's network {@code entityId} via {@code entityIdByPositionShipId}; an
     * allegiance whose ship is not in the snapshot — a ship dropped as {@code !alive} this
     * tick — is skipped so an allegiance never resurrects a despawned ship (mirroring the flux
     * and weapon-mount folds). A null read (binding without the allegiance handles) or an
     * empty result leaves the frame at its prior schema: this is the live-publish grounding of
     * the real-engine allegiance read, not a hard requirement. This is what
     * lets a client render friend vs foe.
     */
    private AuthoritativeWorldSnapshot foldAllegianceTier(
            AuthoritativeWorldSnapshot snapshot, Map<Long, Long> entityIdByPositionShipId) {
        List<ShipAllegianceView> allegianceViews = binding.shipAllegiancesOrNull();
        if (allegianceViews == null || allegianceViews.isEmpty()) {
            return snapshot;
        }
        List<ShipAllegianceSnapshot> allegianceStates = new ArrayList<>(allegianceViews.size());
        for (ShipAllegianceView allegianceView : allegianceViews) {
            if (allegianceView == null) {
                continue;
            }
            Long entityId = entityIdByPositionShipId.get(allegianceView.shipId());
            if (entityId == null) {
                continue; // ship absent from this frame (dropped/dead): no allegiance record
            }
            allegianceStates.add(ShipAllegianceSnapshot.fromView(allegianceView, entityId));
        }
        if (allegianceStates.isEmpty()) {
            return snapshot;
        }
        return AuthoritativeWorldSnapshot.withAllegiances(snapshot, allegianceStates);
    }

    /**
     * A built per-tick snapshot plus the map from each ship's within-tick repository
     * {@code shipId} (the id the motion/durability/flux views share) to its replicated
     * network {@code entityId}. The flux fold needs this map because a flux view is
     * read by repository position but must be keyed to the same network identity as the
     * ship's {@link AuthoritativeEntitySnapshot}.
     */
    private record BuiltSnapshot(
            AuthoritativeWorldSnapshot snapshot, Map<Long, Long> entityIdByPositionShipId) {
    }

    private BuiltSnapshot buildSnapshot(
            List<ShipMotionView> motions, List<ShipDurabilityView> durabilities) {
        // Position mode keys each ship's network entityId on its repository shipId
        // (AuthoritativeEntitySnapshot.fromViews uses motion.shipId() as the entityId),
        // so the flux map is the identity over the motion shipIds.
        Map<Long, Long> entityIdByPositionShipId = new HashMap<>();
        for (ShipMotionView motion : motions) {
            entityIdByPositionShipId.put(motion.shipId(), motion.shipId());
        }
        List<ShipIdentityView> identities =
                contentRegistry == null ? null : binding.shipIdentitiesOrNull();
        if (identities == null || identities.size() != shipCountBound) {
            // Identity read unavailable/partial: fall back to content-neutral state.
            return new BuiltSnapshot(
                    AuthoritativeWorldSnapshot.fromViewLists(simTick, motions, durabilities),
                    entityIdByPositionShipId);
        }
        Map<Long, Integer> contentIdByShipId = new HashMap<>();
        for (ShipIdentityView identity : identities) {
            contentIdByShipId.put(
                    identity.shipId(), contentRegistry.contentIdFor(identity.contentKey()));
            spawnTickByShipId.putIfAbsent(identity.shipId(), simTick);
        }
        return new BuiltSnapshot(
                AuthoritativeWorldSnapshot.fromIdentifiedViewLists(
                        simTick, motions, durabilities, contentIdByShipId, spawnTickByShipId,
                        ownerByShipId),
                entityIdByPositionShipId);
    }

    /**
     * Build one snapshot in stable-roster mode: the engine's
     * stable id ({@code ShipLifecycleView.stableKey()}) drives a network
     * {@code entityId} that survives the roster shrinking or growing, and
     * {@code !alive} ships are dropped (snapshot-authoritative despawn).
     *
     * <p>Pairing within the tick is still by repository-position {@code shipId} (the
     * motion, durability, identity, and lifecycle views were all read in this tick in
     * the same order); the position id is used <em>only</em> to pair the per-ship
     * views, never as a replicated id. Fail-closed: the lifecycle read must be present
     * and the four view lists must agree in size, every alive ship must pair, and the
     * full roster size must fall in {@code [1, shipCountBound]} — otherwise a partial
     * or runaway read throws rather than publishing.
     */
    private BuiltSnapshot buildStableRosterSnapshot(
            List<ShipMotionView> motions, List<ShipDurabilityView> durabilities) throws IOException {
        List<ShipLifecycleView> lifecycles = binding.shipLifecyclesOrNull();
        if (lifecycles == null) {
            throw new IOException("stable-roster mode requires the lifecycle read"
                    + " (shipLifecyclesOrNull) but it was null at tick " + simTick);
        }
        if (lifecycles.size() != motions.size() || durabilities.size() != motions.size()) {
            throw new IOException("stable-roster view size mismatch at tick " + simTick
                    + ": motions=" + motions.size() + ", durabilities=" + durabilities.size()
                    + ", lifecycles=" + lifecycles.size());
        }
        int rosterSize = lifecycles.size();
        // The fail-closed roster bound applies to the LIVE combatant roster, not the raw
        // repository Ship count. Once combat turns lethal the engine keeps each destroyed ship in the
        // Ship.class repository as a dead remnant — an intact hulk plus, when it breaks apart, one or
        // more !alive "pieces" (all isAlive()=false, owner reassigned to neutral 100; a measured
        // mutual Mudskipper kill left 3 such remnants and zero live ships;
        // a second run's 4 was the both-broke-into-2-pieces pattern). The builder already DROPS every !alive
        // ship from the published snapshot (the !lifecycle.alive() skip below), so these remnants never
        // reach the wire; counting them in the bound only tripped a spurious "size out of bounds" throw
        // mid-fight. Bound the ALIVE count: more live ships than configured is still
        // a runaway (throw); zero live ships is the match-over / roster-0 edge (the still-owed B3
        // clean-EOF end — for now a graceful session drop, exit 0).
        long aliveCount = lifecycles.stream().filter(ShipLifecycleView::alive).count();
        if (aliveCount > shipCountBound) {
            // MORE live ships than configured is a genuine runaway: fail closed. Dump the repo
            // Ship list (identity + classification) so it is fully characterized before failing.
            binding.logRosterDump("roster-bound-exceeded", simTick);
            throw new IOException("stable-roster live size out of bounds [1," + shipCountBound
                    + "] at tick " + simTick + ": alive=" + aliveCount + " (raw=" + rosterSize + ")");
        }
        if (simTick % 300L == 0L) {
            // Unfading-wreck census heartbeat —
            // the repository keeps dead remnants forever and the builder drops them from
            // the wire, so only a cadence count makes their growth visible.
            binding.logRemnantCensus(simTick);
        }
        if (fractureDetector != null && (aliveCount < rosterSize || fractureDetector.tracking())) {
            // Remnants exist — feed the fracture detector (edges drain via
            // drainFractureEdges() into the HKFR ctrl fan). The tracking() leg is a build
            // delta to the frozen aliveCount<rosterSize gate: when the LAST remnants are
            // explode-removed the repository shrinks back to aliveCount==rosterSize on the
            // very tick the vanish cascade must emit, so tracked families keep the walk
            // alive until they close out (idle again right after). A null sightings read
            // is a no-observation tick inside the detector — never a fabricated edge.
            // Metered — the read AND the fold ride the sim thread.
            long t0 = System.nanoTime();
            fractureDetector.observe(simTick, binding.remnantSightingsOrNull());
            long dt = System.nanoTime() - t0;
            hulkObserveNsTotal += dt;
            hulkObserveTicks++;
            if (dt > hulkObserveNsMax) {
                hulkObserveNsMax = dt;
            }
        }
        if (matchOverArming && !matchOverArmed) {
            // Observe the fighting sides (owner != 100) that have had a live
            // ship this round, cumulatively; arm the match-over triggers at the second side.
            // Runs BEFORE the trigger blocks so the arming tick evaluates them normally (a late
            // second side whose arrival leaves <2 fighting sides ends the round on that tick).
            // Fail-closed: arming without the allegiance read would suppress match-over forever
            // (rounds that can never end), so a null read while unarmed throws.
            List<ShipAllegianceView> allegiances = binding.shipAllegiancesOrNull();
            if (allegiances == null) {
                throw new IOException("match-over arming requires the allegiance read"
                        + " (shipAllegiancesOrNull) but it was null at tick " + simTick);
            }
            for (ShipAllegianceView allegiance : allegiances) {
                if (allegiance != null && allegiance.alive() && allegiance.owner() != 100) {
                    fightingSidesSeen.add(allegiance.owner());
                }
            }
            if (fightingSidesSeen.size() >= 2) {
                matchOverArmed = true;
                System.out.println("snapshot-source match-over ARMED tick=" + simTick
                        + " sides=" + new TreeSet<>(fightingSidesSeen));
            }
        }
        // Arming mode (npeer): both triggers below stay suppressed until armed; legacy sources
        // (matchOverArming=false) evaluate them unconditionally, byte-identical to pre-arming.
        boolean matchOverSuppressed = matchOverArming && !matchOverArmed;
        boolean roster0Held = false;
        if (aliveCount == 0 && !matchOverSuppressed) {
            // ZERO live ships = match OVER (the symmetric 1v1 mutual-kill ~tick 547-565, or the
            // last ship destroyed). This is NOT an error — it is the faithful end of the match.
            // Fall through and build the roster-0 frame: the lifecycle loop below skips every
            // !alive ship, so `entities` ends empty and the published snapshot carries zero ships,
            // letting the client observe the last death(s) as the entity list empties. Flag
            // matchOver so the serve loop ends the session cleanly after publishing this final
            // frame, instead of the prior IOException "size out of bounds" drop.
            // Dump the repo Ship list ONCE for diagnostics, mirroring the runaway path.
            // Exception — the roster-0 HOLD: while the respawn supplier reports
            // a seat that will remount, keep the empty battlefield open (no latch) so the fly-in
            // lands; log once per empty window. The supplier is consulted HERE, before this tick's
            // seat mount-losses are recorded (the serve loop's T3 runs after the publish), which
            // is why anySeatAwaitingRemount deliberately keys occupancy + dead-latch only: on the
            // exact same-tick mutual kill both seats still read "mounted" at this instant.
            if (!matchOver && respawnAwaitingRemount != null && respawnAwaitingRemount.getAsBoolean()) {
                roster0Held = true;
                if (!roster0HoldLogged) {
                    roster0HoldLogged = true;
                    binding.logRosterDump("roster0-held", simTick);
                    System.out.println(
                            "snapshot-source roster0_held tick=" + simTick + " (respawn pending)");
                }
            } else {
                if (!matchOver) {
                    binding.logRosterDump("match-over-roster-0", simTick);
                    latchMatchOverOutcome("match-over-roster-0", -1, simTick);
                }
                matchOver = true;
            }
        } else if (aliveCount > 0) {
            // A live ship closes any held empty window: the NEXT roster-0 window logs again.
            roster0HoldLogged = false;
        }
        if (stationLost != null && !matchOver && !matchOverSuppressed && !roster0Held) {
            // The STAR FORTRESS lose seam. Evaluated BEFORE the per-side and
            // wave-clear victory latches (the tie-tick precedence rule): on a
            // tick where the last attacker dies AND the base hulks, the round grades base-hulk
            // LOSE — a victory latched the same tick would claim a defense the fortress did not
            // survive. The !matchOver guards on all three triggers keep the no-double-dump
            // property in either order (pre-ruling this block sat LAST, so the wave-clear latch
            // silently won the tie tick). Same guards as the triggers below: the arming
            // suppression (≥2 fighting sides seen) keeps a pre-arrival tick from ending the
            // round, and a held roster-0 window stays open for its pending remount. Deliberately
            // NOT gated on aliveCount: a fortress round whose LAST live hull is the hulking base
            // is exactly the shape this must catch, and the roster-0 branch above owns the
            // genuinely-empty tick.
            if (stationLost.getAsBoolean()) {
                binding.logRosterDump("match-over-base-hulk", simTick);
                // The base hulked ⇒ the side OPPOSED to the station won. Derived from the
                // station's own armed side (never waveClearEnemySide): the lose-seam-alone
                // shape is reachable, and -1 there would show the winning attacker a DEFEAT
                // banner. setStationLost validated the side to 0/1.
                latchMatchOverOutcome("match-over-base-hulk",
                        stationOwnSide == 0 ? 1 : 0, simTick);
                matchOver = true;
            }
        }
        if (perSideRoundEnd && !matchOver && !matchOverSuppressed && !roster0Held) {
            // per-side (one-side-eliminated) round-end. A piloted 1v1 normally
            // ends with ONE side eliminated (aliveCount lands on 1, never 0), so the roster-0 trigger
            // above never fires for an asymmetric intent-up duel (the pilot's BasicShipAI is stripped
            // and the continuous channel carries no fire intent, so whoever dies, the other survives).
            // Default-OFF: when ON, end the match as soon as only one fighting FleetSide (0/1; the
            // neutral 100 and dead-remnant reassignments cannot win) still has live ships. aliveCount>0
            // is implied here -- a zero-alive tick either flipped matchOver above (the !matchOver
            // guard skips this, no double log) or was HELD for a pending respawn (the !roster0Held
            // guard keeps the held window from latching through this trigger).
            List<ShipAllegianceView> allegiances = binding.shipAllegiancesOrNull();
            if (allegiances != null) {
                Set<Integer> fightingSides = new HashSet<>();
                for (ShipAllegianceView allegiance : allegiances) {
                    if (allegiance != null && allegiance.alive() && allegiance.owner() != 100) {
                        fightingSides.add(allegiance.owner());
                    }
                }
                if (fightingSides.size() <= 1) {
                    binding.logRosterDump("match-over-per-side", simTick);
                    latchMatchOverOutcome("match-over-per-side",
                            fightingSides.size() == 1 ? fightingSides.iterator().next() : -1,
                            simTick);
                    matchOver = true;
                }
            }
        }
        if (waveClearScheduleDrained != null && !matchOver && !matchOverSuppressed && !roster0Held
                && aliveCount > 0) {
            // The respawn-compatible wave-clear round end. aliveCount>0 is real
            // here (the roster-0/hold branch above owns the zero-alive tick); the arming
            // suppression already gated us, so the fighting-sides-seen requirement holds. The
            // allegiance read is fail-soft like the per-side trigger (a null tier just skips
            // the tick — the arming path above already fail-closes a permanently absent read),
            // and the observer feed doubles as the WaveSchedule's spawn-on-clear input, so a
            // suppressed round can never clear-spawn either.
            //
            // The count is side-keyed, so a DEFEND fortress (station cluster
            // ALLIED on side 0, waves on side 1) is excluded from "enemy live" by SIDE — base,
            // modules and the engine-born Merlon drones alike. That exclusion is the design:
            // no station-exemption branch exists here, and adding one would re-open the very
            // never-clears bug the side flip closed.
            //
            // A PLAYER on the wave side (/switchteams) is not a wave ship —
            // its hull, modules, fighters and drones are flagged seat() by the side-scoped read
            // and never counted, or one living player would hold the next wave back forever
            // (and, latched, deny the defenders their victory).
            List<ShipAllegianceView> allegiances = binding.shipAllegiancesOrNull(waveClearEnemySide);
            if (allegiances != null) {
                long enemyLive = 0;
                for (ShipAllegianceView allegiance : allegiances) {
                    if (allegiance != null && allegiance.alive()
                            && allegiance.owner() == waveClearEnemySide
                            && !allegiance.seat()) {
                        enemyLive++;
                    }
                }
                waveClearEnemyLiveObserver.accept(enemyLive);
                // The latch's own sustained-zero hold — a momentary zero
                // the tick the last Omega parents die must not declare the DEFEND victory
                // (their shard children fade in 2-3 s later and reset this run).
                if (enemyLive != 0) {
                    waveClearZeroSinceTick = -1L;
                } else if (waveClearZeroSinceTick < 0L) {
                    waveClearZeroSinceTick = simTick;
                }
                if (waveClearLatchRoundEnd && enemyLive == 0
                        && waveClearZeroSinceTick >= 0L
                        && simTick - waveClearZeroSinceTick >= waveClearLatchHoldTicks
                        && waveClearScheduleDrained.getAsBoolean()) {
                    binding.logRosterDump(waveClearLatchTag, simTick);
                    // Wave side cleared ⇒ the OTHER fighting side won (the fortress DEFEND
                    // victory: waves on enemySide, defenders on the opposite 0/1 side).
                    latchMatchOverOutcome(waveClearLatchTag,
                            waveClearEnemySide == 0 ? 1 : 0, simTick);
                    matchOver = true;
                }
            }
        }
        Map<Long, ShipMotionView> motionByShipId = new HashMap<>();
        for (ShipMotionView motion : motions) {
            motionByShipId.put(motion.shipId(), motion);
        }
        Map<Long, ShipDurabilityView> durabilityByShipId = new HashMap<>();
        for (ShipDurabilityView durability : durabilities) {
            durabilityByShipId.put(durability.shipId(), durability);
        }
        Map<Long, Integer> contentIdByShipId = new HashMap<>();
        if (contentRegistry != null) {
            List<ShipIdentityView> identities = binding.shipIdentitiesOrNull();
            if (identities != null && identities.size() == rosterSize) {
                for (ShipIdentityView identity : identities) {
                    contentIdByShipId.put(
                            identity.shipId(), contentRegistry.contentIdFor(identity.contentKey()));
                }
            }
        }
        List<AuthoritativeEntitySnapshot> entities = new ArrayList<>(rosterSize);
        Map<Long, Long> entityIdByPositionShipId = new HashMap<>();
        for (ShipLifecycleView lifecycle : lifecycles) {
            if (!lifecycle.alive()) {
                continue; // destroyed/removed: its entityId simply stops appearing
            }
            long positionId = lifecycle.shipId();
            ShipMotionView motion = motionByShipId.get(positionId);
            ShipDurabilityView durability = durabilityByShipId.get(positionId);
            if (motion == null || durability == null) {
                throw new IOException("stable-roster pairing gap at tick " + simTick
                        + " for position id " + positionId);
            }
            String stableKey = lifecycle.stableKey();
            long entityId = stableIdRegistry.networkIdFor(stableKey);
            long spawnTick = spawnTickByStableKey.computeIfAbsent(stableKey, k -> simTick);
            int contentId = contentIdByShipId.getOrDefault(positionId, 0);
            int owner = ownerByStableKey.getOrDefault(
                    stableKey, AuthoritativeEntitySnapshot.NO_OWNER_CONNECTION);
            entities.add(AuthoritativeEntitySnapshot.fromViewsWithEntityId(
                    entityId, motion, durability, contentId, spawnTick, owner));
            // Publish this tick's quantized armor-cell image under
            // the ENTITY id (position ids recycle across respawns);
            // the book copies, null cells/book no-op.
            if (armorCellBook != null) {
                armorCellBook.observe(entityId, durability.armorCellsOrNull(), simTick);
            }
            // Only alive ships make the snapshot, so the flux map carries only those:
            // a flux view for a !alive (dropped) ship will find no entityId and is skipped.
            entityIdByPositionShipId.put(positionId, entityId);
        }
        return new BuiltSnapshot(AuthoritativeWorldSnapshot.of(simTick, entities), entityIdByPositionShipId);
    }

    /**
     * Read the live non-ship entity lists (projectiles, missiles, beams)
     * off the bridge and pair them into ordered {@link NonShipEntitySnapshot}s.
     * Projectiles, beams, and the authored tier carry server-assigned per-frame
     * ordinals (1..n across those kinds, projectiles then beams then authored);
     * MISSILE records carry the view's server-lifetime STABLE id
     * (partitioned at {@code CombatEntityView.MISSILE_NETWORK_ID_BASE} — never
     * colliding with the ordinal chain, which missiles do not consume). Missiles
     * therefore emit LAST, their segment sorted by stable id, so the whole list
     * satisfies the snapshot's ascending-unique networkId law (an interleaved
     * emit crashed the first live missile+beam frame — strike-rig). Fail-soft:
     * an unbound/failed list reads as empty, so a frame with no live transient
     * entities degrades to ships-only rather than failing. Content keys are interned
     * into {@link #contentRegistry} when present and non-blank.
     * {@code entityIdByPositionShipId} (this same capture's ship-pass map) translates
     * a beam or missile view's position-domain source shipId into the wire entityId
     * the v12 beam-source / v13b missile-source pair carries; an
     * unmapped source degrades that one record's pair to the sentinel, never the
     * record.
     */
    private List<NonShipEntitySnapshot> readNonShipEntities(Map<Long, Long> entityIdByPositionShipId) {
        List<NonShipEntitySnapshot> entities = new ArrayList<>();
        long nextNetworkId = 1L;
        nextNetworkId = appendNonShip(
                entities, binding.projectileEntitiesOrNull(), nextNetworkId, entityIdByPositionShipId);
        nextNetworkId = appendNonShip(
                entities, binding.beamEntitiesOrNull(), nextNetworkId, entityIdByPositionShipId);
        if (authoredProjectiles != null) {
            // The engine cannot spawn a projectile headless (
            // render-coupled spawn needs a GL context), so the server authors the
            // projectile tier and folds it onto the same wire AFTER the engine-read
            // non-ship tier, continuing the ascending-unique networkId sequence.
            entities.addAll(authoredProjectiles.liveSnapshotsAt(simTick, contentRegistry, nextNetworkId));
        }
        // Missiles append LAST — stable ids sit above every ordinal by
        // the MISSILE_NETWORK_ID_BASE partition, and the engine list is not
        // allocation-ordered, so the segment sort keeps the ascending-id law honest.
        int missileStart = entities.size();
        appendNonShip(entities, binding.missileEntitiesOrNull(), nextNetworkId, entityIdByPositionShipId);
        entities.subList(missileStart, entities.size())
                .sort(Comparator.comparingLong(NonShipEntitySnapshot::networkId));
        return entities;
    }

    private long appendNonShip(
            List<NonShipEntitySnapshot> out, List<CombatEntityView> views, long nextNetworkId,
            Map<Long, Long> entityIdByPositionShipId) {
        if (views == null) {
            return nextNetworkId;
        }
        for (CombatEntityView view : views) {
            if (view == null) {
                continue;
            }
            int contentId = NonShipEntitySnapshot.NO_CONTENT;
            String key = view.contentKey();
            if (contentRegistry != null && key != null && !key.isBlank()) {
                contentId = contentRegistry.contentIdFor(key);
            }
            long sourceEntityId = NonShipEntitySnapshot.NO_SOURCE;
            int sourceMountIndex = NonShipEntitySnapshot.NO_SOURCE_MOUNT;
            long sourcePositionShipId = view.sourcePositionShipId();
            if (sourcePositionShipId >= CombatEntityView.MISSILE_NETWORK_ID_BASE) {
                // A DEM payload beam attributed to its owning child missile
                // carries that child's v13a STABLE wire id in the source slot, not a
                // position-domain ship id — it is already a wire id, so it passes through
                // verbatim rather than through the position→network map (which holds ships
                // only and would miss it back to the sentinel pair). The partition at
                // MISSILE_NETWORK_ID_BASE makes the two id spaces unambiguous with no
                // discriminator bit; position ship ids are small ordinals from 1.
                sourceEntityId = sourcePositionShipId;
                sourceMountIndex = view.sourceMountIndex();
            } else if (sourcePositionShipId != 0L && entityIdByPositionShipId != null) {
                Long wireEntityId = entityIdByPositionShipId.get(sourcePositionShipId);
                if (wireEntityId != null) {
                    sourceEntityId = wireEntityId.longValue();
                    sourceMountIndex = view.sourceMountIndex();
                }
            }
            if (view.kind() == CombatEntityView.Kind.MISSILE) {
                // A missile record carries the ECB-stamped stable id and
                // does NOT consume the per-frame ordinal chain.
                out.add(NonShipEntitySnapshot.fromView(
                        view, view.networkId(), contentId, sourceEntityId, sourceMountIndex));
                continue;
            }
            out.add(NonShipEntitySnapshot.fromView(
                    view, nextNetworkId, contentId, sourceEntityId, sourceMountIndex));
            nextNetworkId++;
        }
        return nextNetworkId;
    }
}
