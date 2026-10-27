package cc.starsector.server.runtime;

import cc.starsector.server.protocol.ContentDictionaryEntry;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Server-owned catalog that interns version-independent content keys (engine
 * hull-spec ids such as {@code "hound"}) into stable numeric {@code contentId}s
 * carried in {@link cc.starsector.server.protocol.AuthoritativeEntitySnapshot}.
 *
 * <p>The per-entity snapshot wire frame already reserves an {@code int contentId}
 * slot (see {@code AuthoritativeWorldSnapshotCodec.ENTITY_BYTES}); until a real
 * value was sourced it was always {@code 0}. This registry turns the bridge's
 * {@link cc.starsector.server.adapter.ShipIdentityView#contentKey() contentKey}
 * read into that numeric id so a client can tell ships apart by hull type and
 * correlate identity across ticks without ever seeing an internal engine class
 * or a variable-length string on the per-entity hot path.
 *
 * <p>Ids are assigned in first-seen order starting at 1 ({@code 0} stays reserved
 * for "no content / neutral"), so the mapping is deterministic for a given call
 * order. The registry itself is the small, sendable content table (a handshake
 * artifact); only the compact int travels on each snapshot frame. Not
 * thread-safe: a single publisher owns one registry.
 */
public final class ContentIdRegistry {

    /** Reserved id for an entity whose content key is unknown/neutral. */
    public static final int NO_CONTENT = 0;

    private final Map<String, Integer> idByKey = new LinkedHashMap<>();
    private final Map<Integer, String> keyById = new LinkedHashMap<>();
    private int nextId = 1;
    /** Keys {@link #contentIdFor} refused (never interned); bounded by the hull / weapon / projectile specs of the server's own install. */
    private final Set<String> refusedKeys = new HashSet<>();

    /**
     * Return the stable id for {@code contentKey}, assigning the next sequential id the first time
     * a key is seen. A key the v15 dictionary tier cannot
     * carry ({@link ContentDictionaryEntry#carriable}: over 255 UTF-8 bytes, or a char the wire text
     * law refuses) is NEVER interned and returns {@link #NO_CONTENT} (every caller's neutral slot),
     * so the per-frame dictionary fold never throws on it and ids stay 1..size(). One law scan per
     * key; {@code refusedKeys} stays small: keys are the ENGINE's own loaded spec ids, never peer text.
     */
    public int contentIdFor(String contentKey) {
        Objects.requireNonNull(contentKey, "contentKey");
        if (contentKey.isBlank()) {
            throw new IllegalArgumentException("contentKey must be non-blank");
        }
        Integer existing = idByKey.get(contentKey);
        if (existing != null) {
            return existing;
        }
        if (refusedKeys.contains(contentKey) || !ContentDictionaryEntry.carriable(contentKey)) {
            if (refusedKeys.add(contentKey)) {
                FallbackAnnounce.announceOnce("content-key-refused", "length=" + contentKey.length()
                        + " bad_index=" + WireTextLaw.firstUnprintableIndex(contentKey));
            }
            return NO_CONTENT;
        }
        int assigned = nextId++;
        idByKey.put(contentKey, assigned);
        keyById.put(assigned, contentKey);
        return assigned;
    }

    /** The content key previously interned under {@code contentId}, or null. */
    public String keyForOrNull(int contentId) {
        return keyById.get(contentId);
    }

    /** True if {@code contentKey} has already been interned. */
    public boolean contains(String contentKey) {
        return idByKey.containsKey(contentKey);
    }

    /** Number of distinct content keys interned so far. */
    public int size() {
        return idByKey.size();
    }

    /**
     * Immutable snapshot of the current key→id table, in assignment order — the
     * content catalog a server would replicate to a client at handshake.
     */
    public Map<String, Integer> snapshot() {
        return Map.copyOf(idByKey);
    }
}
