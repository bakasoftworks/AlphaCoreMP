package cc.starsector.server.runtime;

import cc.starsector.server.match.ContentFingerprints;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The armed door's content-fingerprint
 * expectations, pure: {@code raw} plus the SET of lawful session prints. {@code raw} = the
 * sidecar's composite verbatim (what the GAME-FREE join page can present: PROBE HELLOs and VCHK
 * pre-checks — no engine, no id table); {@code session} = the same composite with its {@code data=}
 * component folded over the boot id-table digest and the required mod set
 * ({@link ContentFingerprints#foldContent}) — what the IN-GAME plugin presents when it folds no
 * optional mod. The lawful set = that print plus one fold per subset of the serve's published optional
 * mods ({@link ModSet#lawfulSubsets}). WHY a set: a joiner may run any subset of the published
 * optional mods, while a mod the serve never published, or a same-id copy with another version,
 * jar or data, folds to a print outside the set. A pre-admission credential
 * accepts raw OR any lawful print (it proves "the right kit"); a session-claiming HELLO accepts
 * ONLY a lawful print — the refusal-at-the-door law lives there: a client whose loaded game data
 * forks the dictionary never reaches the VSUB decoder. The reject token stays
 * {@code content_fingerprint_mismatch}; {@link #describeMismatch} names which expectation failed
 * and the server's own sub-digests so ops reads "kit drift" apart from "mod-set drift".
 *
 * <p>Unarmed door = {@link #OFF} (all null, no lawful print; every check passes — the v1 behavior).
 * An armed door with no id table (an engine-less rig boot) folds nothing: the one lawful print is
 * raw. A door armed without a lawful table holds exactly one lawful print, {@code session}.
 */
public final class DoorFingerprintExpectations {

    public static final DoorFingerprintExpectations OFF = new DoorFingerprintExpectations(null, null, null, null,
            Collections.<String>emptySet(), Collections.<String, List<String>>emptyMap(), 0);

    private final String raw;
    private final String session;
    private final String vtab;
    /** The boot mod-set digest folded beside vtab (null = a set-less door: rigs, no engine). */
    private final String modset;
    /** Every lawful session print ({@link #session} included; empty only on {@link #OFF}). HashSet-backed:
     * a null probe reads false, never a throw. */
    private final Set<String> sessions;
    /** {@link ContentFingerprints#stripMatch} of each lawful print - the pre-admission and match-stale compares. */
    private final Set<String> sessionsStripped;
    /** Each lawful print's folded {@code data=} value mapped to the optional ids that subset runs. */
    private final Map<String, List<String>> optionalByFoldedData;
    /** k = the number of published optional records folded into the table (0 with no table). */
    private final int optionalPublished;

    private DoorFingerprintExpectations(String raw, String session, String vtab, String modset, Set<String> sessions,
            Map<String, List<String>> optionalByFoldedData, int optionalPublished) {
        this.raw = raw;
        this.session = session;
        this.vtab = vtab;
        this.modset = modset;
        this.sessions = sessions;
        Set<String> stripped = new HashSet<String>();
        for (String print : sessions) {
            stripped.add(ContentFingerprints.stripMatch(print));
        }
        this.sessionsStripped = Collections.unmodifiableSet(stripped);
        this.optionalByFoldedData = optionalByFoldedData;
        this.optionalPublished = optionalPublished;
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
        return armed(rawComposite, vtabOrNull, modsetOrNull, null);
    }

    /**
     * Arm with the boot LAWFUL-SUBSET table too ({@link ModSet#lawfulSubsets}: modset digest of
     * required + S mapped to S's ids, for every subset S of the published optional set): the door
     * then holds one session print per table key, so a joiner running any subset of the optional
     * mods is admitted. A null or EMPTY table is the 3-argument door exactly (one lawful print,
     * {@code session}). When the fold runs, {@code modsetOrNull} MUST be a key whose subset is empty
     * - the required set's own print, which {@link #session} keeps meaning - else
     * {@link IllegalArgumentException}; every folded print obeys the length and 32-hex law. When no
     * fold runs (an opaque print, or no id table) the table is ignored as the mod set is.
     *
     */
    public static DoorFingerprintExpectations armed(String rawComposite, String vtabOrNull, String modsetOrNull,
            Map<String, List<String>> lawfulSubsetsOrNull) {
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
        boolean folds = composite && vtabOrNull != null;
        String folded = folds ? foldChecked(rawComposite, vtabOrNull, modsetOrNull) : rawComposite;
        Set<String> sessions = new HashSet<String>();
        sessions.add(folded);
        Map<String, List<String>> byData = new HashMap<String, List<String>>();
        int k = 0;
        if (folds && lawfulSubsetsOrNull != null && !lawfulSubsetsOrNull.isEmpty()) {
            List<String> canonical = modsetOrNull == null ? null : lawfulSubsetsOrNull.get(modsetOrNull);
            if (canonical == null || !canonical.isEmpty()) {
                throw new IllegalArgumentException("the door's mod-set digest is not the lawful table's"
                        + " empty-subset entry - the table was built over another required set");
            }
            if (lawfulSubsetsOrNull.size() > (1 << ModSet.MAX_OPTIONAL)) {
                throw new IllegalArgumentException("the lawful table holds " + lawfulSubsetsOrNull.size()
                        + " prints - over 2^" + ModSet.MAX_OPTIONAL);
            }
            for (Map.Entry<String, List<String>> lawful : lawfulSubsetsOrNull.entrySet()) {
                if (lawful.getKey() == null) {
                    throw new IllegalArgumentException("the lawful table carries a null mod-set digest");
                }
                String print = foldChecked(rawComposite, vtabOrNull, lawful.getKey());
                List<String> ids = Collections.unmodifiableList(new ArrayList<String>(lawful.getValue()));
                sessions.add(print);
                byData.put(ContentFingerprints.componentOrNull(print, "data"), ids);
                k = Math.max(k, ids.size());
            }
        } else if (folds) {
            byData.put(ContentFingerprints.componentOrNull(folded, "data"), Collections.<String>emptyList());
        }
        return new DoorFingerprintExpectations(rawComposite, folded, vtabOrNull, vtabOrNull == null ? null : modsetOrNull,
                Collections.unmodifiableSet(sessions), Collections.unmodifiableMap(byData), k);
    }

    /**
     * {@link ContentFingerprints#foldContent} plus the arm-time law on its RESULT, run over every
     * lawful print: the folded length is bounded too (not only the raw one) and its data= must be a
     * clean 32-hex digest, so a malformed fold never arms a door.
     *
     */
    private static String foldChecked(String rawComposite, String vtab, String modsetOrNull) {
        String folded = ContentFingerprints.foldContent(rawComposite, vtab, modsetOrNull);
        if (folded.length() > ContentFingerprints.COMPOSITE_MAX_CHARS) {
            throw new IllegalArgumentException("the folded composite is " + folded.length()
                    + " chars — over the " + ContentFingerprints.COMPOSITE_MAX_CHARS + " cap");
        }
        String foldedData = ContentFingerprints.componentOrNull(folded, "data");
        if (foldedData == null || !foldedData.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("the folded data= component is not 32 lowercase hex");
        }
        return folded;
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

    /** The folded composite over the required set - the empty-subset lawful print (== raw when no id
     * table was installed; null when off). */
    public String session() {
        return session;
    }

    /** The number of lawful session prints: 2^k with a lawful table, 1 on an armed door without one, 0 when off. */
    public int lawfulPrints() {
        return sessions.size();
    }

    /** k = the published optional mods the lawful table folds (0 without a table, or when no fold ran). */
    public int optionalPublished() {
        return optionalPublished;
    }

    public String vtabOrNull() {
        return vtab;
    }

    /** True when the fold changed the composite (an id table was installed). */
    public boolean folded() {
        return raw != null && !raw.equals(session);
    }

    /**
     * PROBE HELLO / VCHK: the game-free join page's raw print OR any lawful in-game print, each compared WITHOUT
     * its {@code match=} term ({@link ContentFingerprints#stripMatch}): jar, data and hs still bind. The match term
     * is ignored here BECAUSE a stale-term or {@code match=none} probe, and the VCHK pre-check that rides the same
     * window, are ANSWERED - the join window holds no current document yet, and that probe's password-proven reply
     * is what carries it. A null {@code presented} on an armed door is false. EVERY lawful print counts, not only
     * the required set's: else a joiner running an optional mod has its VCHK pre-check silently dropped.
     *
     */
    public boolean acceptsPreAdmission(String presented) {
        if (raw == null) {
            return true;
        }
        String content = ContentFingerprints.stripMatch(presented);
        return content != null && (ContentFingerprints.stripMatch(raw).equals(content)
                || sessionsStripped.contains(content));
    }

    /** A session-claiming HELLO: a lawful folded print ONLY (the door refusal the dictionary-id design asks for). */
    public boolean acceptsSession(String presented) {
        return raw == null || sessions.contains(presented);
    }

    /**
     * True iff a session claim's {@code presented} print fails {@link #acceptsSession} ONLY in its {@code match=}
     * term (some lawful print's jar, data and hs all equal): the host changed the match since the player's window
     * fetched it, so the door answers {@link DoorRejectReasons#MATCH_STALE} and the window fetches the new one.
     * False on an unarmed door, for an accepted print, for any other difference and for null. It runs over
     * EVERY lawful print: else a joiner running an optional mod with a stale match term reads mismatch and
     * never refetches.
     */
    public boolean sessionStaleOnlyInMatch(String presented) {
        return raw != null && presented != null && !acceptsSession(presented)
                && sessionsStripped.contains(ContentFingerprints.stripMatch(presented));
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
        sb.append(" session_ok=").append(sessions.contains(presented));
        String jar = ContentFingerprints.componentOrNull(raw, "jar");
        String data = ContentFingerprints.componentOrNull(raw, "data");
        String presentedJar = ContentFingerprints.componentOrNull(presented, "jar");
        String presentedData = ContentFingerprints.componentOrNull(presented, "data");
        String presentedHs = ContentFingerprints.componentOrNull(presented, "hs");
        String presentedMatch = ContentFingerprints.componentOrNull(presented, "match");
        sb.append(" jar_match=").append(jar != null && jar.equals(presentedJar));
        sb.append(" data_raw_match=").append(data != null && data.equals(presentedData));
        sb.append(" data_folded_match=").append(folded() && optionalByFoldedData.containsKey(presentedData));
        sb.append(" hs_match=").append(Objects.equals(ContentFingerprints.componentOrNull(raw, "hs"), presentedHs));
        sb.append(" match_id_match=").append(Objects.equals(ContentFingerprints.componentOrNull(raw, "match"),
                presentedMatch));
        sb.append(" server_data=").append(data).append(" server_vtab=").append(vtab == null ? "none" : vtab);
        // Name the presented fold's SHAPE — a print folded over vtab ONLY is a pre-rung-1
        // kit or an in-game client whose mod-set mint failed (its own log says so); "other" = a
        // different table or a different mod set (the probe listing / the kit's own set tells which).
        sb.append(" server_modset=").append(modset == null ? "none" : modset);
        // The lawful table's size beside the required set - k optional
        // mods published, 2^k prints admitted; "full" = the presented data= is SOME lawful subset's fold, and
        // on a door that publishes optional mods the subset is named (ids are bounded tokens, never player text).
        sb.append(" optional_published=").append(optionalPublished).append(" lawful_prints=").append(sessions.size());
        if (data != null && vtab != null && presentedData != null) {
            List<String> presentedOptional = optionalByFoldedData.get(presentedData);
            String shape = presentedData.equals(ContentFingerprints.foldedDataMd5(data, vtab)) ? "vtab_only"
                    : presentedOptional != null ? "full"
                    : presentedData.equals(data) ? "raw" : "other";
            sb.append(" presented_fold=").append(shape);
            if (shape.equals("full") && optionalPublished > 0) {
                sb.append(" presented_optional=").append(presentedOptional.isEmpty() ? "none"
                        : String.join(",", presentedOptional));
            }
        }
        return sb.toString();
    }
}
