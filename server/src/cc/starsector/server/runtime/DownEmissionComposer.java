package cc.starsector.server.runtime;

import cc.starsector.server.protocol.AuthoritativeEntitySnapshot;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.EntityDespawnEvent;
import cc.starsector.server.protocol.ShipAllegianceSnapshot;
import cc.starsector.server.protocol.ShipCrSnapshot;
import cc.starsector.server.protocol.ShipEngineStateSnapshot;
import cc.starsector.server.protocol.ShipFluxSnapshot;
import cc.starsector.server.protocol.ShipManeuverCommandSnapshot;
import cc.starsector.server.protocol.ShipPhaseCloakStateSnapshot;
import cc.starsector.server.protocol.ShipSystemStateSnapshot;
import cc.starsector.server.protocol.ShipWeaponMountSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * The ONE server
 * DOWN-emission composition seam —
 * emission knobs resolved once at door/serve open, then per tick chunk-under-cap +
 * encode-once compose — sole consumer today: the npeer serve loop's above-the-fan compose
 * (the original second consumer, the retired legacy door's {@code sendSnapshot}, is deleted).
 * Before this class the knob-resolve triplet + chunk-before-compose pair was
 * 2-drawn across the then-two lanes, so a DOWN-path capability landing in one lane could
 * silently miss the sibling. A new DOWN-emission
 * capability (e.g. the wire-cost L1 per-datagram deflate) lands
 * HERE, once.
 *
 * <p>Law: selection-not-minting — this class only sequences the existing
 * {@link SnapshotChunker} and {@link DownTickComposition} codecs in the existing wire order;
 * it never invents bytes, reorders frames, or varies content per consumer. Boot-resolve
 * discipline: knobs resolve ONCE at open ({@link #resolveAtOpen()}); a mid-serve knob flip
 * never changes emission shape.
 *
 * <p>With the {@code cc.net.snapDelta} knob ON <em>and</em>
 * the lane armed by the door ({@link #resolveAtOpen(boolean)}), the SNAP wire lane is replaced
 * per tick by the {@code AWSD} delta+keyframe family derived from the DOMAIN snapshot — the
 * composer owns the per-entity quantized baselines, the epoch counters, and the round-robin
 * keyframe scheduler (state lives HERE because the composer's per-serve lifecycle matches the
 * baselines': cleared at round wrap via {@link #clearBaselines()}, evicted per despawn via
 * {@link #onEntityDespawns(List)}). The raw absolute AWSS composition stays the recorder truth:
 * {@code rawSnapFrames()}/{@code composedDownRecord()} are untouched by the
 * lane swap.
 */
public final class DownEmissionComposer implements AutoCloseable {

    /** AWSD frame fixed overhead: 16-B header + 12-B body prefix + CRC32. */
    private static final int AWSD_FRAME_OVERHEAD_BYTES = AwsdSnapshotCodec.HEADER_BYTES
            + AwsdSnapshotCodec.BODY_PREFIX_BYTES + Integer.BYTES;

    private final boolean snapChunking;
    private final int snapPayloadCap;
    private final boolean snapFragmentation;
    private final boolean snapDeflate;
    /** EFFECTIVE delta-lane state: {@code cc.net.snapDelta} knob AND the door armed the lane. */
    private final boolean snapDelta;
    /** Keyframe cadence E in ticks ({@code cc.net.snapKeyframeTicks}; a measured sweet spot). */
    private final int snapKeyframeTicks;
    /** One deflater per serve loop, reset per chunk; null unless deflate ON. */
    private final java.util.zip.Deflater snapDeflater;

    /**
     * per-entity quantized AWSD anchors — the value set every DELTA is
     * computed against. Evicted per despawn, cleared at round wrap.
     */
    private final HashMap<Long, AwsdEntityBaseline> awsdBaselines = new HashMap<>();
    /**
     * Epoch the NEXT keyframe of an id will carry ({@code getOrDefault 0}). PERSISTS across
     * both eviction and {@link #clearBaselines()} — a reused wire id resumes the epoch
     * sequence, so a client holding a dead hull's anchor under the reused id can never
     * accept an equal-epoch delta against it (the lost-EDSP + reused-id corner, D5; the
     * round-wrap analog is a missed MRST with a cross-round-stale cell).
     */
    private final HashMap<Long, Integer> awsdNextEpoch = new HashMap<>();
    /**
     * entityIds owed an OUT-OF-TURN keyframe — the AWSD NACK answer (D7
     * as landed: a NACK schedules a shared re-key, never an old-epoch resend; ANY fresh
     * keyframe re-anchors). Sim-thread-confined like every composer field; fed per tick from
     * the door's per-session deposits via {@link #requestKeyframes(Collection)}. STAGED
     * DISCIPLINE: compose phase 1 only READS this set; the phase-2 commit removes exactly the
     * ids actually keyframed, so an {@code EncodeRefusedException} fallback leaves the asks
     * ARMED for the next good tick.
     */
    private final HashSet<Long> pendingKeyframeRequests = new HashSet<>();
    /** Cumulative per-serve AWSD meters (the {@code send_failures=} round-line posture). */
    private long awsdKeyframes;
    private long awsdDeltas;
    private long awsdFallbackTicks;
    private long awsdNackKeyframes;
    /** Per-lane WIRE byte meters — see {@link #meterWireLanes}. */
    private long wireGpeeBytes;
    private long wireEdspBytes;
    private long wireDiveBytes;
    private long wireSnapBytes;
    private long wireCarrierBytes;
    private long wireAwsdBytes;
    /**
     * Cumulative CONTENT-DICTIONARY entries this composer put on the AWSD
     * carrier lane. The blocker it exists to make gradeable was invisible precisely because
     * nothing server-side ever counted announcements (the fold and the registry carry zero
     * printlns), so a watch could not tell "never built" from "built but stripped on the
     * wire". Counted in the phase-2 commit, so an EncodeRefusedException tick — which falls
     * back to the absolute AWSS lane, itself dictionary-bearing — is excluded by
     * construction. A round-line 0 with a non-zero missile population now MEANS the tier is
     * not crossing.
     */
    private long snapDictEntries;
    /** over-cap SNAP wire payloads dropped by the never-die guard. */
    private long snapOverCapDropped;
    /**
     * The announced sim-speed every AWSD frame carries (v5 body-prefix
     * u16, 1000 = 1.0×). LIVE state fed by the {@code set-sim-speed} dispatch — data, not
     * an emission-shape knob, so the boot-resolve discipline is untouched (the
     * {@link #requestKeyframes(Collection)} per-tick-feed precedent). Sim-thread-confined
     * like every composer field (the verb dispatch runs on the serve thread).
     */
    private int simSpeedPermille = AwsdSnapshotCodec.SIM_SPEED_PERMILLE_ONE;
    /**
     * The authoritative side-totals every AWSD frame carries (v9
     * trailing block). LIVE state fed per tick by the serve loop from the snapshot
     * source's engine capture — data, not an emission-shape knob (the
     * {@link #setSimSpeedPermille(int)} precedent). Sim-thread-confined like every
     * composer field. ZERO until the first capture lands — net-identical to vanilla's
     * no-bonus state on the client override lane.
     */
    private AwsdSnapshotCodec.SideTotalsBlock sideTotals =
            AwsdSnapshotCodec.SideTotalsBlock.ZERO;
    /**
     * The shared per-cell armor scheduler (v11 cell sections) —
     * null = cells dark (knob off or the caller wired no book), every record then writes
     * runCount 0. The serve loop constructs ONE book and hands it to BOTH this composer
     * and the snapshot source ({@code observe} feed); the composer's job is
     * stage-per-record + the phase-2 commit/rollback law.
     */
    private final ArmorCellBook armorCellBook;

    private DownEmissionComposer(boolean snapChunking, int snapPayloadCap,
            boolean snapFragmentation, boolean snapDeflate,
            boolean snapDelta, int snapKeyframeTicks, ArmorCellBook armorCellBook) {
        this.snapChunking = snapChunking;
        this.snapPayloadCap = snapPayloadCap;
        this.snapFragmentation = snapFragmentation;
        this.snapDeflate = snapDeflate;
        this.snapDelta = snapDelta;
        this.snapKeyframeTicks = snapKeyframeTicks;
        this.snapDeflater = snapDeflate ? SnapDeflateCodec.newDeflater() : null;
        this.armorCellBook = armorCellBook;
    }

    /**
     * Resolve the emission knobs + the L1 {@code cc.net.snapDeflate} knob ONCE —
     * call at door/serve open, before any resource binds, so an invalid knob fails closed
     * holding nothing (each {@link SnapshotChunker} resolver and
     * {@link #deflateEnabled()} THROW on a malformed/out-of-range value).
     *
     * <p>The no-arg overload leaves the AWSD delta lane UNARMED — the fail-closed
     * default for any consumer that has not wired the baseline lifecycle (despawn evict +
     * round clear): it stays absolute-AWSS under any knob state. Today BOTH product
     * doors arm (serveNPeer in-loop; the legacy door wires the lifecycle door-internally),
     * so the no-arg path is the contract-pinned law, not a live product lane.
     */
    public static DownEmissionComposer resolveAtOpen() {
        return resolveAtOpen(false);
    }

    /**
     * The AWSD delta lane is armed PER DOOR — only a caller that wires the
     * baseline lifecycle ({@link #onEntityDespawns(List)} + {@link #clearBaselines()}) may pass
     * {@code true}; effective delta state = knob AND armed. Fail-closed: a lane that has not
     * wired evict/round-clear cannot emit stale-baseline deltas. Both AWSD knobs still resolve
     * STRICTLY here regardless of arming — an invalid value fails the open holding nothing.
     */
    public static DownEmissionComposer resolveAtOpen(boolean armDeltaLane) {
        return resolveAtOpen(armDeltaLane, null);
    }

    /**
     * {@link #resolveAtOpen(boolean)} + the shared
     * {@link ArmorCellBook} handle. Cells are effective only when {@code
     * cc.net.armorCells} is on AND a book is wired (fail-closed like the delta lane's
     * knob-AND-armed law); a null book or knob-off composer writes runCount 0 on every
     * record. All four {@code cc.net.armorCells*} knobs resolve STRICTLY in EVERY open
     * regardless — an invalid value fails the open holding nothing.
     */
    public static DownEmissionComposer resolveAtOpen(boolean armDeltaLane, ArmorCellBook book) {
        boolean deltaKnob = snapDeltaEnabled();
        int keyframeTicks = snapKeyframeTicksKnob();
        boolean cellsKnob = armorCellsEnabled();
        armorCellsTickBytesKnob();
        armorCellsRedundancyKnob();
        armorCellsRepairBytesKnob();
        return new DownEmissionComposer(
                SnapshotChunker.emissionEnabled(),
                SnapshotChunker.payloadCapBytes(),
                SnapshotChunker.fragmentationEnabled(),
                deflateEnabled(),
                armDeltaLane && deltaKnob,
                keyframeTicks,
                cellsKnob ? book : null);
    }

    /**
     * Resolve the {@code cc.net.snapDeflate} knob: default ON since the
     * 2026-07-11 D8-precedent flip — kits pre {@code cc-joiner-kit-20260711-024216}
     * cannot unwrap CAWZ and must not join a default-ON server (stale-kit hazard, knobs.md).
     * Lives HERE (not on the bundled {@link SnapDeflateCodec}) so the mod jar's codec references
     * nothing server-side. Strict parse: an unparseable value THROWS — fail closed at open,
     * holding nothing.
     */
    public static boolean deflateEnabled() {
        String raw = ServerConfig.resolve("cc.net.snapDeflate",
                "CC_NET_SNAP_DEFLATE", "net.snapDeflate", "true").trim();
        if (raw.equalsIgnoreCase("true") || raw.equals("1")
                || raw.equalsIgnoreCase("yes") || raw.equalsIgnoreCase("on")) {
            return true;
        }
        if (raw.equalsIgnoreCase("false") || raw.equals("0")
                || raw.equalsIgnoreCase("no") || raw.equalsIgnoreCase("off")) {
            return false;
        }
        throw new IllegalArgumentException(
                "cc.net.snapDeflate must be a boolean (true/false/1/0/yes/no/on/off); got \""
                        + raw + "\"");
    }

    /**
     * Resolve the {@code cc.net.snapDelta} knob: the L3 AWSD delta+keyframe
     * SNAP lane. Ships {@code false} — a LOSSY lever (quantization grids); the live
     * flip waits on a feel-confirming live test. Stale-kit hazard: pre-AWSD kits cannot
     * decode the lane. Strict parse: an unparseable value THROWS — fail closed at open.
     */
    public static boolean snapDeltaEnabled() {
        String raw = ServerConfig.resolve("cc.net.snapDelta",
                "CC_NET_SNAP_DELTA", "net.snapDelta", "false").trim();
        if (raw.equalsIgnoreCase("true") || raw.equals("1")
                || raw.equalsIgnoreCase("yes") || raw.equalsIgnoreCase("on")) {
            return true;
        }
        if (raw.equalsIgnoreCase("false") || raw.equals("0")
                || raw.equalsIgnoreCase("no") || raw.equalsIgnoreCase("off")) {
            return false;
        }
        throw new IllegalArgumentException(
                "cc.net.snapDelta must be a boolean (true/false/1/0/yes/no/on/off); got \""
                        + raw + "\"");
    }

    /**
     * Resolve the {@code cc.net.snapKeyframeTicks} knob: keyframe cadence E in
     * TICKS (rate-derived law — never a literal 60; measured sweet spot 15–30). STRICT parse:
     * a malformed value or one outside [1,600] THROWS — fail closed at open (deliberately NOT
     * the fail-soft {@code ServerConfig.resolveInt}, which swallows garbage strings).
     */
    public static int snapKeyframeTicksKnob() {
        String raw = ServerConfig.resolve("cc.net.snapKeyframeTicks",
                "CC_NET_SNAP_KEYFRAME_TICKS", "net.snapKeyframeTicks", "15").trim();
        int ticks;
        try {
            ticks = Integer.parseInt(raw);
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException(
                    "cc.net.snapKeyframeTicks must be an integer tick count in [1,600]; got \""
                            + raw + "\"");
        }
        if (ticks < 1 || ticks > 600) {
            throw new IllegalArgumentException(
                    "cc.net.snapKeyframeTicks outside [1,600]: " + ticks);
        }
        return ticks;
    }

    /**
     * Resolve the {@code cc.net.armorCells} knob: the v11
     * per-cell armor stream. Default ON — off writes runCount 0 on every record (wire
     * shape unchanged, zero cell bytes). Strict parse: an unparseable value THROWS —
     * fail closed at open, holding nothing.
     */
    public static boolean armorCellsEnabled() {
        String raw = ServerConfig.resolve("cc.net.armorCells",
                "CC_NET_ARMOR_CELLS", "net.armorCells", "on").trim();
        if (raw.equalsIgnoreCase("true") || raw.equals("1")
                || raw.equalsIgnoreCase("yes") || raw.equalsIgnoreCase("on")) {
            return true;
        }
        if (raw.equalsIgnoreCase("false") || raw.equals("0")
                || raw.equalsIgnoreCase("no") || raw.equalsIgnoreCase("off")) {
            return false;
        }
        throw new IllegalArgumentException(
                "cc.net.armorCells must be a boolean (true/false/1/0/yes/no/on/off); got \""
                        + raw + "\"");
    }

    /** Resolve {@code cc.net.armorCellsTickBytes}: per-tick soft byte budget
     * across all records' cell sections. Strict parse, [0,4096], default 256. */
    public static int armorCellsTickBytesKnob() {
        return parseArmorCellsInt("cc.net.armorCellsTickBytes",
                ServerConfig.resolve("cc.net.armorCellsTickBytes",
                        "CC_NET_ARMOR_CELLS_TICK_BYTES", "net.armorCellsTickBytes", "256"),
                0, 4096);
    }

    /** Resolve {@code cc.net.armorCellsRedundancy}: consecutive emissions per
     * changed cell (loss redundancy R). Strict parse, [1,8], default 2. */
    public static int armorCellsRedundancyKnob() {
        return parseArmorCellsInt("cc.net.armorCellsRedundancy",
                ServerConfig.resolve("cc.net.armorCellsRedundancy",
                        "CC_NET_ARMOR_CELLS_REDUNDANCY", "net.armorCellsRedundancy", "2"),
                1, 8);
    }

    /** Resolve {@code cc.net.armorCellsRepairBytes}: the repair cursor's
     * per-tick sub-budget, spent UNDER the tick budget. Strict parse, [0,1024], default 32. */
    public static int armorCellsRepairBytesKnob() {
        return parseArmorCellsInt("cc.net.armorCellsRepairBytes",
                ServerConfig.resolve("cc.net.armorCellsRepairBytes",
                        "CC_NET_ARMOR_CELLS_REPAIR_BYTES", "net.armorCellsRepairBytes", "32"),
                0, 1024);
    }

    private static int parseArmorCellsInt(String key, String resolved, int min, int max) {
        String raw = resolved.trim();
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException(
                    key + " must be an integer in [" + min + "," + max + "]; got \""
                            + raw + "\"");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    key + " outside [" + min + "," + max + "]: " + value);
        }
        return value;
    }

    /**
     * The emission-shape fragment both doors stamp on their open evidence line —
     * {@code run-npeer-boot.ps1} asserts these exact tokens; keep byte-stable
     * (appended fields only).
     */
    public String openLine() {
        return "snap_chunking=" + snapChunking
                + " snap_payload_cap=" + snapPayloadCap
                + " snap_fragmentation=" + snapFragmentation
                + " snap_deflate=" + snapDeflate
                + " snap_delta=" + snapDelta
                + " snap_keyframe_ticks=" + snapKeyframeTicks
                + " armor_cells=" + (armorCellBook != null);
    }

    /**
     * One tick's DOWN composition: split the AWSS under the datagram payload cap (the
     * chunker's K=1 identity when it fits), then encode every frame ONCE in wire order —
     * peer-independent bytes a serve loop fans/records as-is. With {@code cc.net.snapDeflate}
     * ON, each SNAP wire payload is additionally CAWZ-wrapped HERE, once above the fan
     * (raw-fallback keeps incompressible frames unchanged) — the composition's
     * raw frames and {@code composedDownRecord()} sink truth are untouched.
     *
     * <p>With the AWSD delta lane effective (knob AND armed), the SNAP wire lane is then
     * REPLACED per tick: one ship-empty AWSS frame (non-ship carriage + per-tick AWSS
     * heartbeat) followed by the tick's AWSD keyframe/delta frames. Any encode
     * refusal falls the WHOLE tick's ships back to the normal absolute composition — never
     * a half-converted tick.
     */
    public DownTickComposition compose(
            AuthoritativeWorldSnapshot snapshot,
            List<cc.starsector.server.protocol.GameplayEdgeEvent> events,
            List<EntityDespawnEvent> despawns)
            throws IOException {
        return compose(snapshot, events, despawns,
                List.<cc.starsector.server.protocol.PhaseDiveEvent>of());
    }

    /** The dive-carrying variant — see {@link DownTickComposition}. */
    public DownTickComposition compose(
            AuthoritativeWorldSnapshot snapshot,
            List<cc.starsector.server.protocol.GameplayEdgeEvent> events,
            List<EntityDespawnEvent> despawns,
            List<cc.starsector.server.protocol.PhaseDiveEvent> dives)
            throws IOException {
        List<AuthoritativeWorldSnapshot> chunks =
                SnapshotChunker.chunkForEmission(
                        snapshot, snapPayloadCap, snapChunking, snapFragmentation);
        DownTickComposition comp = DownTickComposition.compose(chunks, events, despawns, dives);
        if (snapDeflate) {
            List<byte[]> wire = new ArrayList<>(comp.rawSnapFrames().size());
            for (byte[] raw : comp.rawSnapFrames()) {
                wire.add(SnapDeflateCodec.wrapOrRaw(snapDeflater, raw));
            }
            comp = comp.withWireSnapFrames(wire);
        }
        if (snapDelta) {
            comp = composeAwsdLaneOrFallBack(snapshot, comp);
        }
        DownTickComposition out = guardSnapOverCap(comp, snapshot.simTick());
        meterWireLanes(out);
        return out;
    }

    /**
     * Cumulative per-lane WIRE bytes this composer minted — the bytes
     * EVERY session receives one copy of (the snap lane is fanned identically; the ctrl-lane
     * frames ride one fan). Exists because the round line carried only the door's total
     * {@code bytes_sent=}: the ladder's +17 KB/s per-peer step at N≥2 could not be
     * named from the meters, and the {@code .ccwr} sink records the RAW absolute lane, never
     * the AWSD replacement ({@code composedDownRecord()}), so the wire split was unmeasured.
     * Runs ON the sim thread ({@code PilotServeSession} calls {@code compose} per tick, which
     * calls this): a few long adds per tick, no I/O — cost trivial, UNMEASURED.
     *
     */
    private void meterWireLanes(DownTickComposition out) {
        for (byte[] frame : out.gpeeFrames()) {
            wireGpeeBytes += frame.length;
        }
        for (byte[] frame : out.edspFrames()) {
            wireEdspBytes += frame.length;
        }
        for (byte[] frame : out.diveFrames()) {
            wireDiveBytes += frame.length;
        }
        for (byte[] frame : out.snapFrames()) {
            wireSnapBytes += frame.length;
        }
    }

    /**
     * never-die guard at the ONE DOWN seam: any SNAP wire payload still
     * over the payload cap is DROPPED and counted — never fanned, never thrown. SNAP is
     * unreliable latest-wins, so a deliberate drop is protocol-identical to datagram loss
     * (the next tick refreshes); raw frames + {@code composedDownRecord()} sink truth ride
     * untouched (record-as-composed). Every lane now mints
     * raw-≤cap frames and CAWZ never widens, so this is belt-and-braces against the NEXT
     * unbounded frame — the serve death (uncaught MTU-floor throw at
     * {@code DatagramFrameCodec.encode}, round 1) must stay unreachable from SNAP; CTRL
     * keeps fail-fast (reliable-ordered cannot drop). {@link #compose} applies this on
     * every return path; public ONLY so the contract can feed a SYNTHETIC over-cap wire
     * frame (unreachable through {@code compose} today) — product callers never call it.
     */
    public DownTickComposition guardSnapOverCap(DownTickComposition comp, long simTick) {
        boolean anyOver = false;
        for (byte[] frame : comp.snapFrames()) {
            if (frame.length > snapPayloadCap) {
                anyOver = true;
                break;
            }
        }
        if (!anyOver) {
            return comp;
        }
        List<byte[]> kept = new ArrayList<>(comp.snapFrames().size());
        for (byte[] frame : comp.snapFrames()) {
            if (frame.length <= snapPayloadCap) {
                kept.add(frame);
                continue;
            }
            if (snapOverCapDropped == 0) {
                System.out.println("snap-over-cap-drop tick=" + simTick + " frame="
                        + frame.length + " B > cap " + snapPayloadCap + " B — dropped, not"
                        + " sent (never-die guard; latest-wins lane refreshes next tick;"
                        + " cumulative count on snap_over_cap_dropped=)");
            }
            snapOverCapDropped++;
        }
        return comp.withWireSnapSubset(kept);
    }

    /**
     * The AWSD lane, TWO-PHASE from the DOMAIN snapshot (never from AWSS
     * bytes). Phase 1 quantizes + encodes every ship with ZERO composer-state mutation
     * (staged puts only); phase 2 commits baselines/epochs/meters ONLY on full success. ANY
     * refusal (checked codec refusal, or a single record over the payload cap) discards the
     * staged state, counts one fallback tick, prints ONE loud line, and returns the normal
     * absolute composition — baselines stay untouched by a failed tick, so the next good
     * tick resumes DELTAs vs the pre-fallback anchors.
     */
    private DownTickComposition composeAwsdLaneOrFallBack(
            AuthoritativeWorldSnapshot snapshot, DownTickComposition comp) throws IOException {
        long simTick = snapshot.simTick();
        long refusedEntity = -1L;
        try {
            // Phase 1: merge-walk the parallel sorted-ascending per-tier lists by entityId
            // (ctor-validated ordering; mounts = contiguous (entityId, mountIndex) runs). A
            // missing tier row passes null — quantize refuses the DENSE tiers (fail-closed
            // → AWSS fallback) and wires the absent sentinel for the sparse three
            // (system/cloak/CR, v8).
            List<AuthoritativeEntitySnapshot> ships = snapshot.entities();
            List<ShipFluxSnapshot> fluxes = snapshot.fluxStates();
            List<ShipWeaponMountSnapshot> mounts = snapshot.weaponMounts();
            List<ShipAllegianceSnapshot> allegiances = snapshot.allegianceStates();
            List<ShipEngineStateSnapshot> engines = snapshot.engineStates();
            List<ShipManeuverCommandSnapshot> maneuvers = snapshot.maneuverCommands();
            List<ShipSystemStateSnapshot> systems = snapshot.systemStates();
            List<ShipPhaseCloakStateSnapshot> cloaks = snapshot.phaseCloakStates();
            List<ShipCrSnapshot> crStates = snapshot.crStates();
            int fluxAt = 0;
            int mountAt = 0;
            int allegAt = 0;
            int engineAt = 0;
            int maneuverAt = 0;
            int systemAt = 0;
            int cloakAt = 0;
            int crAt = 0;
            List<byte[]> records = new ArrayList<>(ships.size());
            HashMap<Long, AwsdEntityBaseline> stagedBaselines = new HashMap<>();
            HashMap<Long, Integer> stagedNextEpoch = new HashMap<>();
            long stagedKeyframes = 0L;
            long stagedDeltas = 0L;
            long stagedNackKeyframes = 0L;
            long stagedCarrierBytes = 0L;   // wire-lane meters (committed below)
            long stagedAwsdBytes = 0L;
            for (AuthoritativeEntitySnapshot pose : ships) {
                long id = pose.entityId();
                refusedEntity = id;
                while (fluxAt < fluxes.size() && fluxes.get(fluxAt).entityId() < id) {
                    fluxAt++;
                }
                ShipFluxSnapshot flux = fluxAt < fluxes.size()
                        && fluxes.get(fluxAt).entityId() == id ? fluxes.get(fluxAt) : null;
                while (mountAt < mounts.size() && mounts.get(mountAt).entityId() < id) {
                    mountAt++;
                }
                List<ShipWeaponMountSnapshot> mountRun = new ArrayList<>();
                for (int m = mountAt; m < mounts.size() && mounts.get(m).entityId() == id; m++) {
                    mountRun.add(mounts.get(m));
                }
                while (allegAt < allegiances.size()
                        && allegiances.get(allegAt).entityId() < id) {
                    allegAt++;
                }
                ShipAllegianceSnapshot allegiance = allegAt < allegiances.size()
                        && allegiances.get(allegAt).entityId() == id
                                ? allegiances.get(allegAt) : null;
                while (engineAt < engines.size() && engines.get(engineAt).entityId() < id) {
                    engineAt++;
                }
                ShipEngineStateSnapshot engineState = engineAt < engines.size()
                        && engines.get(engineAt).entityId() == id ? engines.get(engineAt) : null;
                while (maneuverAt < maneuvers.size()
                        && maneuvers.get(maneuverAt).entityId() < id) {
                    maneuverAt++;
                }
                ShipManeuverCommandSnapshot maneuver = maneuverAt < maneuvers.size()
                        && maneuvers.get(maneuverAt).entityId() == id
                                ? maneuvers.get(maneuverAt) : null;
                while (systemAt < systems.size() && systems.get(systemAt).entityId() < id) {
                    systemAt++;
                }
                // Matched-or-NULL: the system tier is sparse per-entity (v8;
                // armor modules carry no ship system) — null wires SYS_NONE.
                ShipSystemStateSnapshot systemState = systemAt < systems.size()
                        && systems.get(systemAt).entityId() == id ? systems.get(systemAt) : null;
                while (cloakAt < cloaks.size() && cloaks.get(cloakAt).entityId() < id) {
                    cloakAt++;
                }
                // Matched-or-NULL: the v10 cloak tier is sparse (quantize's one nullable
                // tier) — null = no-cloak ship, never a refusal.
                ShipPhaseCloakStateSnapshot phaseCloak = cloakAt < cloaks.size()
                        && cloaks.get(cloakAt).entityId() == id ? cloaks.get(cloakAt) : null;
                while (crAt < crStates.size() && crStates.get(crAt).entityId() < id) {
                    crAt++;
                }
                // Matched-or-NULL: the v14 CR tier is sparse per-entity like cloak
                // (quantize's second nullable tier) — null wires the D2 absent shape.
                ShipCrSnapshot crState = crAt < crStates.size()
                        && crStates.get(crAt).entityId() == id ? crStates.get(crAt) : null;
                // ONE quantize serves both paths: its epoch is the id's NEXT-keyframe
                // epoch — a keyframe carries it; encodeDelta writes the ANCHOR's epoch and
                // never compares cur.epoch.
                int epoch = awsdNextEpoch.getOrDefault(id, 0);
                AwsdEntityBaseline cur = AwsdSnapshotCodec.quantize(pose, flux, mountRun,
                        allegiance, engineState, maneuver, systemState, phaseCloak,
                        crState, epoch, simTick);
                AwsdEntityBaseline anchor = awsdBaselines.get(id);
                // Deterministic round-robin re-key: ids are sequential from 1, so the
                // scheduled keyframes spread across ticks instead of bursting. 4th disjunct:
                // a client NACK forces an out-of-turn keyframe — READ-only here,
                // the phase-2 commit clears exactly the ids actually keyframed.
                boolean nackForced = pendingKeyframeRequests.contains(id);
                boolean keyframe = anchor == null
                        || AwsdSnapshotCodec.needsKeyframe(cur, anchor)
                        || Math.floorMod(simTick, snapKeyframeTicks)
                                == Math.floorMod(id, snapKeyframeTicks)
                        || nackForced;
                if (nackForced) {
                    stagedNackKeyframes++;
                }
                // v11 cell tail: the book plans the whole tick at the pass's first
                // stage() and marks this id's section STAGED; commit/rollback below is
                // the phase-2 half of that contract.
                List<AwsdSnapshotCodec.CellRun> cellRuns = armorCellBook != null
                        ? armorCellBook.stage(id) : List.of();
                byte[] record;
                if (keyframe) {
                    record = AwsdSnapshotCodec.encodeKeyframe(cur, cellRuns);
                    stagedBaselines.put(id, cur);
                    stagedNextEpoch.put(id, (epoch + 1) & 0xFF);
                    stagedKeyframes++;
                } else {
                    record = AwsdSnapshotCodec.encodeDelta(cur, anchor, cellRuns);
                    stagedDeltas++;
                }
                if (AWSD_FRAME_OVERHEAD_BYTES + record.length > snapPayloadCap) {
                    throw new AwsdSnapshotCodec.EncodeRefusedException(
                            "single AWSD record " + record.length
                                    + " B cannot fit under snapPayloadCap " + snapPayloadCap);
                }
                records.add(record);
            }
            refusedEntity = -1L;
            // Lane order: ship-empty AWSS carrier FIRST — non-ship carriage + the
            // per-tick AWSS heartbeat (always emitted) — then greedy-packed AWSD frames.
            // The carrier rides the SAME chunker as the absolute lane — a
            // big non-ship population (the swarmer-volley serve death: ~100 non-ships,
            // raw 36+28·N B, CAWZ hid it until deflate output crossed the cap) splits into
            // K raw-≤cap frames instead of the tree's ONE unbounded SNAP frame. Zero ships =
            // pure greedy 28-B packing; K=1 identity is byte-identical to the unchunked
            // carrier; CAWZ never widens, so raw-bounded chunks stay wire-bounded.
            // The carrier MUST also carry the frame-global CONTENT-DICTIONARY
            // tier. It has no AWSD home (the delta family carries per-entity contentId ints,
            // never key strings — AwsdSnapshotCodec has no dictionary section at all), and
            // withWireSnapLane REPLACES the absolute lane, so a carrier minted without it
            // strips every announcement off the wire while the .ccwr recording still shows
            // them. Measured consequence: the client catalog stayed EMPTY for
            // the whole run — dict_defer_ok=0 across 4294 defers — so every contentId-keyed
            // presentation (station mines, and any sourceless body) failed at
            // MissilePoseLane (trueSpec==null). Build via the Builder, never a telescoping
            // back-compat ctor: those bind low and DROP higher tiers by design (see the Builder's javadoc).
            List<byte[]> lane = new ArrayList<>();
            for (AuthoritativeWorldSnapshot carrier : SnapshotChunker.chunkForEmission(
                    new AuthoritativeWorldSnapshot.Builder()
                            .simTick(simTick)
                            .nonShipEntities(snapshot.nonShipEntities())
                            .contentDictionary(snapshot.contentDictionary())
                            .build(),
                    snapPayloadCap, snapChunking, snapFragmentation)) {
                ByteArrayOutputStream carrierFrame = new ByteArrayOutputStream(256);
                AuthoritativeWorldSnapshotCodec.writeFrame(carrierFrame, carrier);
                byte[] carrierWire = wrapLaneFrame(carrierFrame.toByteArray());
                stagedCarrierBytes += carrierWire.length;
                lane.add(carrierWire);
            }
            List<byte[]> pending = new ArrayList<>();
            int pendingBytes = 0;
            for (byte[] record : records) {
                if (!pending.isEmpty()
                        && AWSD_FRAME_OVERHEAD_BYTES + pendingBytes + record.length
                                > snapPayloadCap) {
                    byte[] awsdWire = wrapLaneFrame(encodeAwsdFrame(simTick, pending));
                    stagedAwsdBytes += awsdWire.length;
                    lane.add(awsdWire);
                    pending = new ArrayList<>();
                    pendingBytes = 0;
                }
                pending.add(record);
                pendingBytes += record.length;
            }
            if (!pending.isEmpty()) {
                byte[] awsdWire = wrapLaneFrame(encodeAwsdFrame(simTick, pending));
                stagedAwsdBytes += awsdWire.length;
                lane.add(awsdWire);
            }
            // Phase 2: commit ONLY on full success. Pending keyframe asks clear by what was
            // actually keyframed: an id absent from the snapshot stays pending
            // (harmless — phase 1 reads it only for ships present; despawn/clear purge it).
            awsdBaselines.putAll(stagedBaselines);
            awsdNextEpoch.putAll(stagedNextEpoch);
            awsdKeyframes += stagedKeyframes;
            awsdDeltas += stagedDeltas;
            awsdNackKeyframes += stagedNackKeyframes;
            wireCarrierBytes += stagedCarrierBytes;
            wireAwsdBytes += stagedAwsdBytes;
            snapDictEntries += snapshot.contentDictionary().size();
            pendingKeyframeRequests.removeAll(stagedBaselines.keySet());
            if (armorCellBook != null) {
                armorCellBook.commit();
            }
            return comp.withWireSnapLane(lane);
        } catch (AwsdSnapshotCodec.EncodeRefusedException refusal) {
            if (armorCellBook != null) {
                armorCellBook.rollback();
            }
            awsdFallbackTicks++;
            System.out.println("awsd-fallback tick=" + simTick
                    + " entity=" + (refusedEntity >= 0 ? String.valueOf(refusedEntity) : "-")
                    + " reason=" + refusal.getMessage());
            return comp;
        }
    }

    private byte[] encodeAwsdFrame(long simTick, List<byte[]> records) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(256);
        AwsdSnapshotCodec.writeFrame(frame, simTick, simSpeedPermille, records, sideTotals);
        return frame.toByteArray();
    }

    /**
     * Whether the AWSD delta lane is EFFECTIVE this serve (knob AND
     * armed) — the {@code set-sim-speed} verb refuses on an AWSS-only serve (the absolute
     * lane has no announcement carrier, so a client could never learn the speed).
     */
    public boolean deltaLaneOn() {
        return snapDelta;
    }

    /**
     * Feed the announced sim-speed (permille, 1000 = 1.0×) — every AWSD
     * frame from the next compose carries it (mid-round change + late-join adopt ride the
     * same u16). Out-of-carrier-range REJECTS ({@link AwsdSnapshotCodec#writeFrame} bounds;
     * the verb grammar already enforces [0.25, 2.0] so a throw here is a wiring bug).
     */
    public void setSimSpeedPermille(int permille) {
        if (permille < AwsdSnapshotCodec.SIM_SPEED_PERMILLE_MIN
                || permille > AwsdSnapshotCodec.SIM_SPEED_PERMILLE_MAX) {
            throw new IllegalArgumentException("simSpeedPermille outside ["
                    + AwsdSnapshotCodec.SIM_SPEED_PERMILLE_MIN + ", "
                    + AwsdSnapshotCodec.SIM_SPEED_PERMILLE_MAX + "]: " + permille);
        }
        this.simSpeedPermille = permille;
    }

    /**
     * Feed the authoritative side-totals — every AWSD frame from the
     * next compose carries them in the v9 trailing block (memoryless; a late joiner
     * converges in 1 frame, the {@link #setSimSpeedPermille(int)} law). Null REFUSES
     * (caller bug): a serve with no capture yet simply never calls this and the
     * composer keeps announcing {@link AwsdSnapshotCodec.SideTotalsBlock#ZERO}.
     */
    public void setSideTotals(AwsdSnapshotCodec.SideTotalsBlock totals) {
        this.sideTotals = Objects.requireNonNull(totals, "totals");
    }

    private byte[] wrapLaneFrame(byte[] frame) {
        return snapDeflate ? SnapDeflateCodec.wrapOrRaw(snapDeflater, frame) : frame;
    }

    /**
     * Evict despawned entities' AWSD anchors — despawn is ground truth
     * every tick, so the serve loop calls this UNGATED (unconditional no-op when the lane
     * never armed or the list is empty; callers never gate). The NEXT-epoch counter is
     * deliberately KEPT — see {@link #awsdNextEpoch}.
     */
    public void onEntityDespawns(List<EntityDespawnEvent> despawns) {
        for (EntityDespawnEvent despawn : despawns) {
            awsdBaselines.remove(despawn.entityId());
            pendingKeyframeRequests.remove(despawn.entityId());
            if (armorCellBook != null) {
                armorCellBook.forget(despawn.entityId());
            }
        }
    }

    /**
     * Schedule OUT-OF-TURN keyframes for NACKed entityIds — sim thread
     * only, called by the serve loop per tick (per session, BEFORE compose) with the door's
     * drained per-session deposits. Coalesces by id; unknown/absent ids are harmless (compose
     * reads the set only for ships present; despawn evict + round clear purge). No-op on an
     * empty collection, so callers never gate.
     */
    public void requestKeyframes(Collection<Long> entityIds) {
        pendingKeyframeRequests.addAll(entityIds);
    }

    /**
     * Round wrap — drop every AWSD anchor (the reset re-points seat wire
     * ids, SNIR), forcing fresh keyframes for every ship. Epoch counters persist — the next
     * keyframes CONTINUE each id's epoch sequence (see {@link #awsdNextEpoch}).
     */
    public void clearBaselines() {
        awsdBaselines.clear();
        pendingKeyframeRequests.clear();
        if (armorCellBook != null) {
            armorCellBook.clear();
        }
    }

    /** Cumulative per-serve AWSD keyframe records emitted (round-line meter). */
    public long awsdKeyframes() {
        return awsdKeyframes;
    }

    /** Wire-lane meters (round-line {@code wire_*_bytes=}): GPEE ctrl frames. */
    public long wireGpeeBytes() {
        return wireGpeeBytes;
    }

    /** EDSP ctrl frames minted (wire bytes, cumulative per serve). */
    public long wireEdspBytes() {
        return wireEdspBytes;
    }

    /** DIVE ctrl frames minted (wire bytes, cumulative per serve). */
    public long wireDiveBytes() {
        return wireDiveBytes;
    }

    /** The whole SNAP wire lane as fanned (carrier + AWSD when the delta lane is on, else the
     * absolute AWSS frames; deflate applied), cumulative per serve. */
    public long wireSnapBytes() {
        return wireSnapBytes;
    }

    /** Of {@link #wireSnapBytes()}: the ship-empty AWSS carrier frames (non-ship carriage +
     * content dictionary, ABSOLUTE every tick) — 0 with the delta lane off. */
    public long wireCarrierBytes() {
        return wireCarrierBytes;
    }

    /** Of {@link #wireSnapBytes()}: the AWSD keyframe/delta frames — 0 with the delta lane off. */
    public long wireAwsdBytes() {
        return wireAwsdBytes;
    }

    /** Cumulative per-serve AWSD delta records emitted (round-line meter). */
    public long awsdDeltas() {
        return awsdDeltas;
    }

    /** Cumulative per-serve ticks that fell back to absolute AWSS (round-line meter). */
    public long awsdFallbackTicks() {
        return awsdFallbackTicks;
    }

    /** Cumulative content-dictionary entries carried on the AWSD lane. */
    public long snapDictEntries() {
        return snapDictEntries;
    }

    /** Cumulative per-serve NACK-forced out-of-turn keyframes committed (round-line
     * {@code awsd_nack_kf=}). */
    public long awsdNackKeyframes() {
        return awsdNackKeyframes;
    }

    /** Cumulative committed cell-section bytes past the presence byte (round-line
     * {@code awsd_cell_bytes=}; 0 when cells are dark). */
    public long awsdCellBytes() {
        return armorCellBook == null ? 0L : armorCellBook.cellBytes();
    }

    /** Cumulative committed cell runs (round-line {@code awsd_cell_runs=}). */
    public long awsdCellRuns() {
        return armorCellBook == null ? 0L : armorCellBook.cellRuns();
    }

    /** Cumulative pending cells deferred past their tick by the byte budget (round-line
     * {@code awsd_cell_deferred=}; sustained growth = budget too small for the fight). */
    public long awsdCellsDeferred() {
        return armorCellBook == null ? 0L : armorCellBook.cellsDeferred();
    }

    /** Cumulative max deferral AGE in ticks of a wholly-deferred dirty entity (round-line
     * {@code awsd_cell_defer_age_max=}; 0 when cells are dark) — tells a 1-tick defer from
     * the oldest-first starvation edge ({@link ArmorCellBook#deferAgeMaxTicks}).
     * */
    public long awsdCellDeferAgeMax() {
        return armorCellBook == null ? 0L : armorCellBook.deferAgeMaxTicks();
    }

    /** Cumulative per-serve over-cap SNAP wire payloads dropped by the never-die
     * guard (round-line {@code snap_over_cap_dropped=}; steady state 0 — any nonzero means
     * a lane minted an unbounded frame and the guard saved the serve). */
    public long snapOverCapDropped() {
        return snapOverCapDropped;
    }

    /** Out-of-turn keyframe asks not yet served (contract pin; steady state drains to 0 —
     * a lingering entry means the id never re-appeared before despawn/round clear). */
    public int pendingKeyframeRequestCount() {
        return pendingKeyframeRequests.size();
    }

    /** Release the deflater's native zlib state (no-op with deflate OFF). Idempotent. */
    @Override
    public void close() {
        if (snapDeflater != null) {
            snapDeflater.end();
        }
    }
}
