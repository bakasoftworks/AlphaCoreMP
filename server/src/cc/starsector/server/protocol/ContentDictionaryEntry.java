package cc.starsector.server.protocol;

import cc.starsector.server.runtime.WireTextLaw;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * One CONTENT-DICTIONARY announcement (schema v15): the mapping from a
 * wire {@code contentId} (the interned non-ship spec ordinal {@code ContentIdRegistry}
 * mints server-side) to its true content key (the engine projectile spec id, e.g.
 * {@code "pilum_second_stage"} — the {@code getProjectileSpecId()} string the id was
 * interned from). The dictionary tier is FRAME-GLOBAL (not entity-scoped): each v15 frame
 * carries a small cycling window (J entries, newest-first + round-robin re-announce) so a
 * client joining or rejoining mid-round converges on the full catalog in ~size/J frames
 * without any request channel. The client folds entries last-write-wins and uses the
 * catalog for PRESENTATION ONLY (spawn-by-true-spec); it is never trusted for outcomes.
 *
 * <p>{@code contentId} is ≥1 ({@code ContentIdRegistry} ids are 1-based; 0 is the
 * no-content sentinel and never announced). {@code key} is 1..255 bytes of UTF-8 —
 * fail-closed here AND at the codec so an oversize key can neither be constructed nor
 * decoded (the wire length field is one unsigned byte), and
 * it carries no char {@link WireTextLaw} refuses (the kit refuses such a key with the whole v15 frame; a throw
 * here is a caller bug — {@code ContentIdRegistry} refuses the key at intern, via {@link #carriable}).
 */
public record ContentDictionaryEntry(int contentId, String key) {

    /** Widest UTF-8 key the wire's one-byte length field can carry. */
    public static final int MAX_KEY_UTF8_BYTES = 255;

    public ContentDictionaryEntry {
        if (contentId < 1) {
            throw new IllegalArgumentException(
                    "contentId must be >= 1; got " + contentId);
        }
        Objects.requireNonNull(key, "key");
        int utf8Len = key.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Len < 1 || utf8Len > MAX_KEY_UTF8_BYTES) {
            throw new IllegalArgumentException(
                    "key must be 1.." + MAX_KEY_UTF8_BYTES + " UTF-8 bytes; got " + utf8Len);
        }
        int bad = WireTextLaw.firstUnprintableIndex(key);
        if (bad >= 0) {
            throw new IllegalArgumentException("key carries a char the wire text law refuses at index "
                    + bad + " of " + key.length());
        }
    }

    /** true iff this record's ctor admits {@code key} (both laws). */
    public static boolean carriable(String key) {
        if (key == null) {
            return false;
        }
        int utf8Len = key.getBytes(StandardCharsets.UTF_8).length;
        return utf8Len >= 1 && utf8Len <= MAX_KEY_UTF8_BYTES
                && WireTextLaw.firstUnprintableIndex(key) < 0;
    }
}
