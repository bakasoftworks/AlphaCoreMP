package cc.starsector.server.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Encode-once DOWN-tick compose: one tick's DOWN
 * payload — every {@code GPEE} event frame, every {@code EDSP} despawn frame, the {@code AWSS}
 * snapshot frame(s) — encoded ONCE into per-frame byte arrays, independent of any peer session.
 * Payload bytes are peer-independent by design: per-peer sequencing/acks live only in the 32-byte
 * {@link DatagramFrameCodec} envelope (and TCP has no per-peer payload state at all), so N
 * sessions' same-tick payloads are byte-equal and a serve loop composes once above its session
 * fan-out instead of re-encoding per peer.
 *
 * <p>Law: selection-not-minting. This class only runs the existing codecs in the existing wire
 * order ({@code [GPEE*][EDSP*][AWSS…]}, the multiplex contract) — it never invents
 * bytes, reorders frames, or varies content per consumer.
 *
 * <p>{@link #snapFrames()} is a list hosting the {@link SnapshotChunker}
 * output: the K-snapshot compose overload mints one AWSS frame per chunk; the single-snapshot
 * overload is the K=1 identity (byte-identical to the pre-hoist inline encoding, pinned by the
 * AWSS goldens and the transport contracts).
 *
 * <p>{@link #composedDownRecord()} is the {@code .ccwr} sink truth: the tick's whole multiplexed
 * frame set concatenated in wire order as ONE explicit-stamp {@code KIND_DOWN} record,
 * content-identical to the bytes every session was sent (mirrors TCP's {@code writeDown}) —
 * except under a {@code cc.net.downEveryTicks} &gt; 1 cadence divisor, where off-ticks record
 * the composed SNAP frames that were deliberately not fanned (record-as-composed).
 *
 * <p>Returned lists are unmodifiable and the byte arrays are the encoder's only copies — callers
 * hand them to send/record paths as-is and never mutate them.
 *
 * <p>STAGED form (send-first): {@link #composeCtrl} encodes only the ctrl frames and holds the
 * SNAP chunks un-encoded, so a tick whose wire lane is something else (the delta lane) can be
 * fanned before the raw absolute frames — needed only by the DOWN record — are encoded by
 * {@link #withAbsoluteFrames}. While that raw lane is pending, {@link #rawSnapFrames()} and
 * {@link #composedDownRecord()} THROW rather than answer with a partial record; while no wire
 * lane is staged, {@link #snapFrames()} throws. The finished form is byte-identical to the
 * one-shot {@link #compose} (which is exactly {@code composeCtrl} then {@code withAbsoluteFrames}).
 */
public final class DownTickComposition {

    private final List<byte[]> gpeeFrames;
    private final List<byte[]> edspFrames;
    private final List<byte[]> diveFrames;
    /** Null while the raw lane is pending ({@link #pendingChunks} non-null). */
    private final List<byte[]> rawSnapFrames;
    /** Null until a wire SNAP lane is staged. */
    private final List<byte[]> snapFrames;
    /** The chunker's output awaiting its raw encode; null once encoded. */
    private final List<cc.starsector.server.protocol.AuthoritativeWorldSnapshot> pendingChunks;

    private DownTickComposition(List<byte[]> gpeeFrames, List<byte[]> edspFrames,
            List<byte[]> diveFrames, List<byte[]> rawSnapFrames, List<byte[]> snapFrames,
            List<cc.starsector.server.protocol.AuthoritativeWorldSnapshot> pendingChunks) {
        this.gpeeFrames = Collections.unmodifiableList(gpeeFrames);
        this.edspFrames = Collections.unmodifiableList(edspFrames);
        this.diveFrames = Collections.unmodifiableList(diveFrames);
        this.rawSnapFrames = rawSnapFrames == null ? null
                : Collections.unmodifiableList(rawSnapFrames);
        this.snapFrames = snapFrames == null ? null : Collections.unmodifiableList(snapFrames);
        this.pendingChunks = pendingChunks;
    }

    /** Encode one tick's DOWN frame set exactly once, in wire order (K=1 snapshot). */
    public static DownTickComposition compose(
            cc.starsector.server.protocol.AuthoritativeWorldSnapshot snapshot,
            List<cc.starsector.server.protocol.GameplayEdgeEvent> events,
            List<cc.starsector.server.protocol.EntityDespawnEvent> despawns)
            throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        return compose(List.of(snapshot), events, despawns,
                List.<cc.starsector.server.protocol.PhaseDiveEvent>of());
    }

    /**
     * K-snapshot variant for the S5 {@link SnapshotChunker} output: one {@code AWSS} frame
     * per chunk, encoded once each, in chunk order. K=1 is byte-identical to the
     * single-snapshot overload (which delegates here).
     */
    public static DownTickComposition compose(
            List<cc.starsector.server.protocol.AuthoritativeWorldSnapshot> snapshots,
            List<cc.starsector.server.protocol.GameplayEdgeEvent> events,
            List<cc.starsector.server.protocol.EntityDespawnEvent> despawns)
            throws IOException {
        return compose(snapshots, events, despawns,
                List.<cc.starsector.server.protocol.PhaseDiveEvent>of());
    }

    /**
     * The dive-carrying variant — {@code DIVE} frames join the CTRL
     * lane between {@code EDSP} and the SEAT frames at the serve loop's concat. Like SEAT
     * frames they ride the ctrl fan but NOT the v1 {@code KIND_DOWN} record contract
     * ({@link #composedDownRecord()} is unchanged — the
     * {@code [GPEE*][EDSP*][AWSS…]} record order stays pinned). The despawn-less overloads
     * above are the empty-dive identity, byte-identical to the pre-F4 compose.
     */
    public static DownTickComposition compose(
            List<cc.starsector.server.protocol.AuthoritativeWorldSnapshot> snapshots,
            List<cc.starsector.server.protocol.GameplayEdgeEvent> events,
            List<cc.starsector.server.protocol.EntityDespawnEvent> despawns,
            List<cc.starsector.server.protocol.PhaseDiveEvent> dives)
            throws IOException {
        return composeCtrl(snapshots, events, despawns, dives).withAbsoluteFrames();
    }

    /**
     * The staged first half of {@link #compose}: every ctrl frame ({@code GPEE}, {@code EDSP},
     * {@code DIVE}) encoded once, the SNAP chunks validated and held un-encoded — the raw lane
     * is PENDING and no wire SNAP lane is staged yet. Argument checks are the one-shot
     * compose's, so a bad tick still throws here, before anything is sent.
     *
     */
    public static DownTickComposition composeCtrl(
            List<cc.starsector.server.protocol.AuthoritativeWorldSnapshot> snapshots,
            List<cc.starsector.server.protocol.GameplayEdgeEvent> events,
            List<cc.starsector.server.protocol.EntityDespawnEvent> despawns,
            List<cc.starsector.server.protocol.PhaseDiveEvent> dives)
            throws IOException {
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(despawns, "despawns");
        Objects.requireNonNull(dives, "dives");
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException("a DOWN tick carries at least one AWSS frame");
        }
        List<byte[]> gpee = new ArrayList<>(events.size());
        for (cc.starsector.server.protocol.GameplayEdgeEvent event : events) {
            ByteArrayOutputStream cap = new ByteArrayOutputStream(GameplayEdgeEventCodec.FRAME_BYTES);
            GameplayEdgeEventCodec.writeEvent(cap, event);
            gpee.add(cap.toByteArray());
        }
        List<byte[]> edsp = new ArrayList<>(despawns.size());
        for (cc.starsector.server.protocol.EntityDespawnEvent despawn : despawns) {
            ByteArrayOutputStream cap = new ByteArrayOutputStream(EntityDespawnEventCodec.FRAME_BYTES);
            EntityDespawnEventCodec.writeEvent(cap, despawn);
            edsp.add(cap.toByteArray());
        }
        List<byte[]> dive = new ArrayList<>(dives.size());
        for (cc.starsector.server.protocol.PhaseDiveEvent diveEvent : dives) {
            ByteArrayOutputStream cap = new ByteArrayOutputStream(PhaseDiveEventCodec.FRAME_BYTES);
            PhaseDiveEventCodec.writeEvent(cap, diveEvent);
            dive.add(cap.toByteArray());
        }
        List<cc.starsector.server.protocol.AuthoritativeWorldSnapshot> chunks =
                new ArrayList<>(snapshots.size());
        for (cc.starsector.server.protocol.AuthoritativeWorldSnapshot snapshot : snapshots) {
            chunks.add(Objects.requireNonNull(snapshot, "snapshot"));
        }
        return new DownTickComposition(gpee, edsp, dive, null, null,
                Collections.unmodifiableList(chunks));
    }

    /**
     * The staged second half of {@link #compose}: the pending SNAP chunks encoded as RAW
     * {@code AWSS} frames, once each, in chunk order — the codec truth the DOWN record carries.
     * A staged wire lane (the delta lane) is kept as-is; with none staged the raw frames ARE
     * the wire lane (the same arrays, as the one-shot compose returns them). Already encoded:
     * returns this.
     *
     */
    public DownTickComposition withAbsoluteFrames() throws IOException {
        if (pendingChunks == null) {
            return this;
        }
        List<byte[]> snaps = new ArrayList<>(pendingChunks.size());
        for (cc.starsector.server.protocol.AuthoritativeWorldSnapshot snapshot : pendingChunks) {
            ByteArrayOutputStream cap = new ByteArrayOutputStream(256);
            AuthoritativeWorldSnapshotCodec.writeFrame(cap, snapshot);
            snaps.add(cap.toByteArray());
        }
        return new DownTickComposition(gpeeFrames, edspFrames, diveFrames, snaps,
                snapFrames != null ? snapFrames : snaps, null);
    }

    /** Whether the raw SNAP lane still awaits {@link #withAbsoluteFrames}. */
    public boolean rawPending() {
        return pendingChunks != null;
    }

    private int rawCount() {
        return pendingChunks != null ? pendingChunks.size() : rawSnapFrames.size();
    }

    private void requireRawEncoded(String what) {
        if (pendingChunks != null) {
            throw new IllegalStateException(what + " read while the raw SNAP lane is pending: "
                    + pendingChunks.size() + " chunk(s) are encoded only by withAbsoluteFrames,"
                    + " after the send");
        }
    }

    /**
     * The same tick with the SNAP lane's WIRE payloads replaced by a per-frame
     * LOSSLESS transport encoding (the {@link SnapDeflateCodec} CAWZ wrap; a raw-fallback
     * element is the raw frame array itself). The RAW frames stay the codec truth —
     * {@link #rawSnapFrames()} and {@link #composedDownRecord()} are unchanged, so the
     * {@code .ccwr} sink keeps recording raw multiplexed bytes (the selection-not-minting rule
     * audits the ONE DOWN artifact; compression is per-datagram transport dressing below it).
     */
    public DownTickComposition withWireSnapFrames(List<byte[]> wireSnapFrames) {
        Objects.requireNonNull(wireSnapFrames, "wireSnapFrames");
        if (wireSnapFrames.size() != rawCount()) {
            throw new IllegalArgumentException("wire SNAP lane must carry exactly one payload"
                    + " per raw AWSS frame: raw=" + rawCount()
                    + " wire=" + wireSnapFrames.size());
        }
        return new DownTickComposition(gpeeFrames, edspFrames, diveFrames, rawSnapFrames,
                wireSnapFrames, pendingChunks);
    }

    /**
     * The same tick with the SNAP lane REPLACED by a different frame
     * family's payload list (the AWSD delta+keyframe lane). Unlike
     * {@link #withWireSnapFrames} there is deliberately NO 1:1 raw↔wire invariant —
     * AWSD emits ~1 frame per tick where the AWSS chunker minted K chunks — so only
     * non-null/non-empty is enforced. The RAW frames stay the codec truth:
     * {@link #rawSnapFrames()} and {@link #composedDownRecord()} are unchanged, so the
     * {@code .ccwr} sink keeps recording raw absolute AWSS bytes.
     */
    public DownTickComposition withWireSnapLane(List<byte[]> laneFrames) {
        Objects.requireNonNull(laneFrames, "laneFrames");
        if (laneFrames.isEmpty()) {
            throw new IllegalArgumentException("SNAP lane swap requires at least one frame");
        }
        for (byte[] frame : laneFrames) {
            Objects.requireNonNull(frame, "laneFrames element");
        }
        return new DownTickComposition(gpeeFrames, edspFrames, diveFrames, rawSnapFrames,
                laneFrames, pendingChunks);
    }

    /**
     * The same tick with the wire SNAP lane reduced to {@code keptFrames}
     * — the never-die over-cap drop guard's output ({@link DownEmissionComposer}), a subset
     * of the current wire lane. EMPTY is legal: the tick then fans only CTRL frames,
     * indistinguishable to a peer from whole-lane datagram loss (SNAP is latest-wins; the
     * next tick refreshes). Raw frames / {@link #composedDownRecord()} stay the codec truth
     * (record-as-composed). Only shrink is enforced — dropping never
     * grows a lane.
     */
    public DownTickComposition withWireSnapSubset(List<byte[]> keptFrames) {
        Objects.requireNonNull(keptFrames, "keptFrames");
        if (keptFrames.size() > snapFrames().size()) {
            throw new IllegalArgumentException("guarded wire SNAP lane cannot grow: have "
                    + snapFrames.size() + " frame(s), got " + keptFrames.size());
        }
        for (byte[] frame : keptFrames) {
            Objects.requireNonNull(frame, "keptFrames element");
        }
        return new DownTickComposition(gpeeFrames, edspFrames, diveFrames, rawSnapFrames,
                keptFrames, pendingChunks);
    }

    /** {@code GPEE} frames in event order, one encoded frame per element (CTRL lane). */
    public List<byte[]> gpeeFrames() {
        return gpeeFrames;
    }

    /** {@code EDSP} frames in despawn order, one encoded frame per element (CTRL lane). */
    public List<byte[]> edspFrames() {
        return edspFrames;
    }

    /** {@code DIVE} frames in event order, one per phase-dive start (CTRL lane;
     * ctrl-fan-only like SEAT frames — never part of {@link #composedDownRecord()}). */
    public List<byte[]> diveFrames() {
        return diveFrames;
    }

    /**
     * SNAP-lane WIRE payloads, one per {@link SnapshotChunker} chunk — what a serve loop fans
     * to sessions. Identical to {@link #rawSnapFrames()} (the same arrays) unless
     * {@link #withWireSnapFrames} applied the L1 transport wrap. Throws while no wire lane
     * is staged (a {@link #composeCtrl} result not yet given one).
     */
    public List<byte[]> snapFrames() {
        if (snapFrames == null) {
            throw new IllegalStateException("no wire SNAP lane staged on this composition");
        }
        return snapFrames;
    }

    /** RAW {@code AWSS} frame(s) — the codec truth the {@code .ccwr} record concatenates.
     * Throws while the raw lane is pending (see {@link #withAbsoluteFrames}). */
    public List<byte[]> rawSnapFrames() {
        requireRawEncoded("rawSnapFrames");
        return rawSnapFrames;
    }

    /** The tick's RAW frame set concatenated in wire order — the ONE {@code KIND_DOWN} record
     * body (unchanged by the L1 wire wrap; see {@link #withWireSnapFrames}). Throws while the
     * raw lane is pending: a record without its AWSS frames would be a different record. */
    public byte[] composedDownRecord() {
        requireRawEncoded("composedDownRecord");
        int total = 0;
        for (byte[] frame : gpeeFrames) {
            total += frame.length;
        }
        for (byte[] frame : edspFrames) {
            total += frame.length;
        }
        for (byte[] frame : rawSnapFrames) {
            total += frame.length;
        }
        ByteArrayOutputStream record = new ByteArrayOutputStream(total);
        for (byte[] frame : gpeeFrames) {
            record.writeBytes(frame);
        }
        for (byte[] frame : edspFrames) {
            record.writeBytes(frame);
        }
        for (byte[] frame : rawSnapFrames) {
            record.writeBytes(frame);
        }
        return record.toByteArray();
    }
}
