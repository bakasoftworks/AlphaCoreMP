package cc.starsector.server.runtime;

import cc.starsector.server.match.ContentFingerprints;

import java.util.Objects;

/**
 * The armed door's TWO content-fingerprint
 * expectations, pure. {@code raw} = the sidecar's composite verbatim (what the GAME-FREE join
 * page can present: PROBE HELLOs and VCHK pre-checks — no engine, no id table); {@code session} =
 * the same composite with its {@code data=} component folded over the boot id-table digest
 * ({@link ContentFingerprints#foldVariantTable}) — what the IN-GAME plugin presents. A
 * pre-admission credential accepts EITHER (it proves "the right kit"); a session-claiming HELLO
 * accepts ONLY the folded one — the refusal-at-the-door law lives there: a client whose loaded
 * game data forks the dictionary never reaches the VSUB decoder. The reject token stays
 * {@code content_fingerprint_mismatch}; {@link #describeMismatch} names which expectation failed
 * and the server's own sub-digests so ops reads "kit drift" apart from "mod-set drift".
 *
 * <p>Unarmed door = {@link #OFF} (both null; every check passes — the v1 behavior). An armed door
 * with no id table (an engine-less rig boot) folds nothing: session == raw.
 */
public final class DoorFingerprintExpectations {

    public static final DoorFingerprintExpectations OFF = new DoorFingerprintExpectations(null, null, null, null);

    private final String raw;
    private final String session;
    private final String vtab;
    /** The boot mod-set digest folded beside vtab (null = a set-less door: rigs, no engine). */
    private final String modset;

    private DoorFingerprintExpectations(String raw, String session, String vtab, String modset) {
        this.raw = raw;
        this.session = session;
        this.vtab = vtab;
        this.modset = modset;
    }

    /** Arm over the sidecar composite; {@code vtabOrNull} null = no fold (session == raw). Set-less (vtab-only fold). */
    public static DoorFingerprintExpectations armed(String rawComposite, String vtabOrNull) {
        return armed(rawComposite, vtabOrNull, null);
    }

    /**
     * Arm with the boot MOD-SET digest too — the session print becomes the three-way fold
     * ({@link ContentFingerprints#foldContent}); a set-less rig passes null and folds vtab only. A
     * mod set without a table (no engine) folds nothing — the table is the fold's precondition.
     */
    public static DoorFingerprintExpectations armed(String rawComposite, String vtabOrNull, String modsetOrNull) {
        Objects.requireNonNull(rawComposite, "rawComposite");
        if (rawComposite.isBlank() || rawComposite.length() > ContentFingerprints.COMPOSITE_MAX_CHARS) {
            throw new IllegalArgumentException("requiredDoorFingerprint must be non-blank and <= "
                    + ContentFingerprints.COMPOSITE_MAX_CHARS + " chars when armed; got "
                    + rawComposite.length() + " chars");
        }
        // A sidecar COMPOSITE (the packer's "cc1;…" shape) MUST fold — a composite with
        // no data= component never arms a half-folded door (IllegalArgumentException, loud at
        // boot). An OPAQUE print (rigs / contract rows arm the door with a bare token) has nothing
        // to fold into: session == raw, exact compare as before; the bind line prints folded=false.
        boolean composite = rawComposite.startsWith(ContentFingerprints.FORMAT_PREFIX + ";");
        String folded = vtabOrNull == null || !composite ? rawComposite
                : ContentFingerprints.foldContent(rawComposite, vtabOrNull, modsetOrNull);
        // Only the RAW
        // length was bounded; bound the folded RESULT too, and (when the fold ran) assert its
        // data= is a clean 32-hex digest so a malformed fold never arms a door.
        if (folded.length() > ContentFingerprints.COMPOSITE_MAX_CHARS) {
            throw new IllegalArgumentException("the folded composite is " + folded.length()
                    + " chars — over the " + ContentFingerprints.COMPOSITE_MAX_CHARS + " cap");
        }
        if (composite && vtabOrNull != null) {
            String foldedData = ContentFingerprints.componentOrNull(folded, "data");
            if (foldedData == null || !foldedData.matches("[0-9a-f]{32}")) {
                throw new IllegalArgumentException("the folded data= component is not 32 lowercase hex");
            }
        }
        return new DoorFingerprintExpectations(rawComposite, folded, vtabOrNull, vtabOrNull == null ? null : modsetOrNull);
    }

    /** The mod-set digest the session fold carries (null = set-less door). */
    public String modsetOrNull() {
        return modset;
    }

    public boolean armed() {
        return raw != null;
    }

    /** The sidecar composite verbatim (null when off). */
    public String raw() {
        return raw;
    }

    /** The folded composite (== raw when no table was installed; null when off). */
    public String session() {
        return session;
    }

    public String vtabOrNull() {
        return vtab;
    }

    /** True when the fold changed the composite (an id table was installed). */
    public boolean folded() {
        return raw != null && !raw.equals(session);
    }

    /**
     * PROBE HELLO / VCHK: the game-free join page's raw print OR the in-game folded print, each compared WITHOUT
     * its {@code match=} term ({@link ContentFingerprints#stripMatch}): jar, data and hs still bind. The match term
     * is ignored here BECAUSE a stale-term or {@code match=none} probe, and the VCHK pre-check that rides the same
     * window, are ANSWERED - the join window holds no current document yet, and that probe's password-proven reply
     * is what carries it. A null {@code presented} on an armed door is false.
     */
    public boolean acceptsPreAdmission(String presented) {
        if (raw == null) {
            return true;
        }
        String content = ContentFingerprints.stripMatch(presented);
        return content != null && (ContentFingerprints.stripMatch(raw).equals(content)
                || ContentFingerprints.stripMatch(session).equals(content));
    }

    /** A session-claiming HELLO: the folded print ONLY (the door refusal the dictionary-id design asks for). */
    public boolean acceptsSession(String presented) {
        return raw == null || session.equals(presented);
    }

    /**
     * True iff a session claim's {@code presented} print fails {@link #acceptsSession} ONLY in its {@code match=}
     * term (the folded print's jar, data and hs all equal): the host changed the match since the player's window
     * fetched it, so the door answers {@link DoorRejectReasons#MATCH_STALE} and the window fetches the new one.
     * False on an unarmed door, for an accepted print, for any other difference and for null.
     *
     */
    public boolean sessionStaleOnlyInMatch(String presented) {
        return raw != null && !acceptsSession(presented)
                && ContentFingerprints.stripMatch(session).equals(ContentFingerprints.stripMatch(presented));
    }

    /**
     * One log fragment naming the failed expectation + the server's own components (the client's
     * fold is opaque by construction — that is fine, the token is the same). Never echoes the
     * presented string unquoted: it is wire input ({@code PeerText.quote} at the call site).
     */
    public String describeMismatch(String presented, boolean sessionClaim) {
        if (raw == null) {
            return "door=off";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("expected=").append(sessionClaim ? "session" : "session|raw");
        // raw_ok is printed on a session claim too: "true" there = a kit that presented the RAW
        // composite where the fold was due (an older kit / the game-free join page) — ops reads
        // that apart from mod-set drift (data_raw_match=false) at a glance.
        sb.append(" raw_ok=").append(raw.equals(presented));
        sb.append(" session_ok=").append(session.equals(presented));
        String jar = ContentFingerprints.componentOrNull(raw, "jar");
        String data = ContentFingerprints.componentOrNull(raw, "data");
        String presentedJar = ContentFingerprints.componentOrNull(presented, "jar");
        String presentedData = ContentFingerprints.componentOrNull(presented, "data");
        String presentedHs = ContentFingerprints.componentOrNull(presented, "hs");
        String presentedMatch = ContentFingerprints.componentOrNull(presented, "match");
        sb.append(" jar_match=").append(jar != null && jar.equals(presentedJar));
        sb.append(" data_raw_match=").append(data != null && data.equals(presentedData));
        sb.append(" data_folded_match=").append(folded()
                && Objects.equals(ContentFingerprints.componentOrNull(session, "data"), presentedData));
        sb.append(" hs_match=").append(Objects.equals(ContentFingerprints.componentOrNull(raw, "hs"), presentedHs));
        sb.append(" match_id_match=").append(Objects.equals(ContentFingerprints.componentOrNull(raw, "match"),
                presentedMatch));
        sb.append(" server_data=").append(data).append(" server_vtab=").append(vtab == null ? "none" : vtab);
        // Name the presented fold's SHAPE — a print folded over vtab ONLY is a pre-rung-1
        // kit or an in-game client whose mod-set mint failed (its own log says so); "other" = a
        // different table or a different mod set (the probe listing / the kit's own set tells which).
        sb.append(" server_modset=").append(modset == null ? "none" : modset);
        if (data != null && vtab != null && presentedData != null) {
            String shape = presentedData.equals(ContentFingerprints.foldedDataMd5(data, vtab)) ? "vtab_only"
                    : modset != null && presentedData.equals(ContentFingerprints.foldedDataMd5(data, vtab, modset)) ? "full"
                    : presentedData.equals(data) ? "raw" : "other";
            sb.append(" presented_fold=").append(shape);
        }
        return sb.toString();
    }
}
