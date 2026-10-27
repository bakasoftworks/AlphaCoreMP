package cc.starsector.client.voice;

/**
 * One immutable snapshot of the world positions the mixer needs (build brief D5/D8/
 * D12): own BODY (proximity distance is body-to-anchor, matching the server cull exactly), the
 * CAMERA (pan is camera-relative), and each live talker's anchor. Published by the render-thread
 * {@code VoicePresenceLane} through an {@code AtomicReference<VoicePositions>} and read by the audio
 * thread; immutable so neither side ever blocks on the other. Arrays are owned by the snapshot —
 * callers hand over fresh arrays and never mutate them afterwards.
 */
public final class VoicePositions {
    public static final VoicePositions EMPTY = new VoicePositions(0f, 0f, 0f, 0f, false, new long[0],
            new float[0], new byte[0]);

    /** Anchor resolution kinds — how the publisher found the
     *  spot, carried per anchor so the audio thread's per-burst {@code ended} line can confess
     *  which body positioned a talker (LIVE hull / dead HULK ghost spot / LAST-known fallback). */
    public static final byte KIND_NONE = 0;
    public static final byte KIND_LIVE = 1;
    public static final byte KIND_HULK = 2;
    public static final byte KIND_LAST = 3;

    public final float ownX;
    public final float ownY;
    public final float camX;
    public final float camY;
    /** False when the own body is not resolvable this frame (no ship at all: pre-spawn /
     * spectating — a dead hulk still counts as known): the mixer then plays
     * proximity talkers as if global (unity gain, centred) rather than guessing a distance. */
    public final boolean ownKnown;
    /** Anchor wire ids (u32 as long), parallel to {@link #anchorXY} (x,y pairs). */
    public final long[] anchorIds;
    public final float[] anchorXY;
    /** Resolution kind per anchor ({@link #KIND_LIVE} / {@link #KIND_HULK} / {@link #KIND_LAST}),
     *  parallel to {@link #anchorIds}; a short/missing array reads as {@link #KIND_NONE}. */
    public final byte[] anchorKind;

    public VoicePositions(float ownX, float ownY, float camX, float camY, boolean ownKnown,
            long[] anchorIds, float[] anchorXY, byte[] anchorKind) {
        this.ownX = ownX;
        this.ownY = ownY;
        this.camX = camX;
        this.camY = camY;
        this.ownKnown = ownKnown;
        this.anchorIds = anchorIds == null ? new long[0] : anchorIds;
        this.anchorXY = anchorXY == null ? new float[0] : anchorXY;
        this.anchorKind = anchorKind == null ? new byte[0] : anchorKind;
    }

    /** The resolution kind of anchor index {@code idx} ({@link #KIND_NONE} when out of range). */
    public byte kindAt(int idx) {
        return idx >= 0 && idx < anchorKind.length ? anchorKind[idx] : KIND_NONE;
    }

    /** Index into {@link #anchorIds} for {@code anchorWireId}, or -1 when unresolved. */
    public int indexOf(long anchorWireId) {
        for (int i = 0; i < anchorIds.length; i++) {
            if (anchorIds[i] == anchorWireId) {
                return i;
            }
        }
        return -1;
    }
}
