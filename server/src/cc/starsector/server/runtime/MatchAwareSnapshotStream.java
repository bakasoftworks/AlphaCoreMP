package cc.starsector.server.runtime;

import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Reads a persistent-socket byte stream that INTERLEAVES {@code "AWSS"} world-snapshot
 * frames ({@link AuthoritativeWorldSnapshotCodec}) with explicit {@code "MRST"} MATCH_RESET
 * control frames ({@link MatchResetFrameCodec}) and demuxes them by frame magic.
 *
 * <p>This is the SERVER-SIDE reference/verification demux-oracle for the match-aware stream,
 * used by smokes, contracts, the client-mod verify oracle, and the AwssAnalyze tool —
 * NOT the reader the production predict client runs (that client demuxes via its own
 * {@code MultiplexedServerStreamReader} + {@code ClientMatchResetReader}, sandbox-safe
 * {@code DataInputStream}-free mirrors). It exists because the read-only persistent-socket reset
 * leaves the round boundary IMPLICIT in the entity-count sequence, which a predict
 * client (its own local engine + reconcile) cannot infer. With the server emitting an explicit
 * MATCH_RESET frame between rounds, the boundary is on the wire as a distinct framed
 * unit; this reader pulls one frame at a time, peeks its 4-byte magic, and routes an {@code "AWSS"}
 * frame to the snapshot decoder (and the caller's {@code onSnapshot}) and an {@code "MRST"} frame to
 * {@code onReset} — the explicit tear-down + rebuild signal.
 *
 * <p>Framing is fail-closed: an unknown magic, or any per-frame schema / identity / size / CRC
 * mismatch (delegated to each codec), throws {@link IOException}. A clean end-of-stream before
 * any frame byte returns normally.
 */
public final class MatchAwareSnapshotStream {

    /** One demuxed unit: exactly one of {@code snapshot} / {@code reset} is non-null. */
    public record Item(AuthoritativeWorldSnapshot snapshot, MatchResetSignal reset) {
        public boolean isReset() {
            return reset != null;
        }
    }

    private MatchAwareSnapshotStream() {
    }

    /**
     * Demux the whole stream, invoking {@code onSnapshot} for each decoded snapshot frame (in
     * stream order) and {@code onReset} for each explicit MATCH_RESET control frame (at the
     * exact point it appears between snapshot frames).
     */
    public static void read(
            InputStream input,
            Consumer<? super AuthoritativeWorldSnapshot> onSnapshot,
            Consumer<? super MatchResetSignal> onReset) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(onSnapshot, "onSnapshot");
        Objects.requireNonNull(onReset, "onReset");
        DataInputStream data = input instanceof DataInputStream di ? di : new DataInputStream(input);
        while (true) {
            int first = data.read();
            if (first < 0) {
                return; // clean EOF before any frame byte
            }
            int magic = readMagicAfterFirstByte(data, first);
            if (magic == AuthoritativeWorldSnapshotCodec.MAGIC) {
                onSnapshot.accept(AuthoritativeWorldSnapshotCodec.readFrameAfterMagic(data));
            } else if (magic == MatchResetFrameCodec.MAGIC) {
                onReset.accept(MatchResetFrameCodec.readFrameAfterMagic(data));
            } else {
                throw new IOException("unknown frame magic on persistent socket: "
                        + Integer.toHexString(magic));
            }
        }
    }

    /** Collect the whole stream into demuxed items, preserving stream order. */
    public static List<Item> readItems(InputStream input) throws IOException {
        List<Item> items = new ArrayList<>();
        read(input,
                snapshot -> items.add(new Item(snapshot, null)),
                reset -> items.add(new Item(null, reset)));
        return List.copyOf(items);
    }

    private static int readMagicAfterFirstByte(DataInputStream data, int first) throws IOException {
        return ((first & 0xff) << 24)
                | (data.readUnsignedByte() << 16)
                | (data.readUnsignedByte() << 8)
                | data.readUnsignedByte();
    }
}
