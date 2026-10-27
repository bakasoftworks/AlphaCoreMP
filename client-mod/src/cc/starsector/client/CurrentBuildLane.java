package cc.starsector.client;

import cc.starsector.client.net.PredictSession;

/**
 * The CURRENT-BUILD + variant-pick lane: the {@code saves/common/cc_variants/current.data}
 * read / write / clear behind the editor's FLY and the join window's combo, the HELLO-pick
 * precedence (current build &gt; window pick &gt; server default), the launch-pick transport bound,
 * the pre-mission {@code VDEF} fold, the {@code VACK}-verdict peeks and the one-per-connect
 * {@code VSUB} submit.
 *
 * <p>WHY a class of its own: {@link CcClientModPlugin} had grown to 2612 non-blank lines with no
 * tier before the R6 carves (1801 nb at this leg C carve),
 * and
 * this lane holds no lock and spawns no thread. It reads the session registry through
 * {@link CcClientModPlugin#predictSession()} and nothing else of the plugin. Moved VERBATIM
 * (bodies, javadoc, every log / fallback line, every throw message).
 *
 * <p>Threading: {@code currentBuild} and its writers run on any thread (SettingsAPI); the
 * pre-mission fold and the verdict peeks run on the mission-build (game) thread.
 *
 * <p>Census law: the {@code cc.predict.variantId} / {@code CC_PREDICT_VARIANT_ID} /
 * {@code cc.editor} literals stay INLINE and byte-identical here (the knob inventory checker
 * sweeps all of {@code client-mod/src}; the census hand tool was retired).
 *
 * <p>Write law: {@code launchVariantPick} is written ONCE per process by
 * {@link PredictDialPlanner#preparePredictDial} at app load and read at every dial through
 * {@code nextConnectPick}; the plugin keeps 7 one-line public delegates so the 25 caller sites in
 * the editor / picker / routes stay untouched — a later pass may retarget them.
 *
 * <p>Provenance: the design read (VARIANT-PICK + CURRENT-BUILD row) +
 * the carve's leg C + the measured surface, following the leg A/B
 * precedents ({@link ClientLaunchKnobs} / {@link PilotSessionBoot} / {@link PredictDialPlanner}).
 *
 *
 */
final class CurrentBuildLane {

    private CurrentBuildLane() {
    }

    /**
     * D-FIRSTSHIP: the {@link JoinVariantPick#setWireResolver} body — fold a
     * join pick's server-delivered {@code VDEF} definition into the engine registry BEFORE the
     * mission builds, so a pickable the W7c B3 kit strip did not ship still fields at t=0 (the
     * failure this fixes: pick {@code grendel_Safetyoverride} → "does not resolve in this
     * kit" → conquest placeholder → L5 snap). Runs on the mission-build (game) thread; the
     * session's peek consumes nothing and the in-combat fold lane re-folds idempotently.
     * False = no session / not delivered yet / cannot fold — the builder fields the pod.
     */
    static boolean foldPickFromWirePreMission(String pick) {
        PredictSession session = CcClientModPlugin.predictSession();
        if (session == null) {
            // The pick may be wire-derived (the door's default id) — scrub at write.
            PilotWatchLog.info("mission build: join pick '" + FallbackAnnounce.sanitize(pick, 64)
                    + "' has no kit file and no predict session to fold it from -> placeholder");
            return false;
        }
        int peeked = 0;
        for (cc.starsector.client.net.ClientVariantDefinition def : session.peekVariantDefinitions()) {
            peeked++;
            if (pick.equals(def.variantId())) {
                return VariantDefinitionLane.foldIntoRegistry(def, "predict variant-fold (pre-mission)");
            }
        }
        PilotWatchLog.info("mission build: join pick '" + FallbackAnnounce.sanitize(pick, 64)
                + "' has no kit file and no VDEF definition delivered yet (" + peeked
                + " peeked); " + customVerdictOnHand() + " -> placeholder");
        return false;
    }

    /** W15 v1: the FIRST {@link PredictSession#peekVariantReceipts} caller —
     *  a menu-thread (mission-build) read of the {@code VACK} verdicts on hand BEFORE combat
     *  drains CTRL, mirroring the {@code peekVariantDefinitions} line above. Two build sites log
     *  it: the W13 claim-at-play build ({@code MatchMissionBuilder}, the live lane — no pick is
     *  resolved there) and the D-FIRSTSHIP pre-mission pick resolver above. A local custom pick never resolves
     *  in the kit (the server repoints the seat to the minted canonical id), so THIS phrase is
     *  where a sidelog reader learns whether the submission was already answered when the
     *  mission built ("ACCEPTED as cc-v1-…" / "REJECTED token: id -> hint") or is still in
     *  flight. Consumes nothing; the combat lane's drain still toasts. Ids are wire-law printable
     *  ASCII, the token is the server's own vocabulary — nothing player-authored. */
    static String customVerdictOnHand() {
        PredictSession session = CcClientModPlugin.predictSession();
        if (session == null) {
            return "custom-variant receipts on hand: no predict session yet";
        }
        java.util.List<cc.starsector.client.net.ClientVariantReceipt> receipts;
        try {
            receipts = session.peekVariantReceipts();
        } catch (RuntimeException peekFailed) {
            return "custom-variant receipts: peek failed (" + peekFailed.getClass().getSimpleName() + ")";
        }
        if (receipts.isEmpty()) {
            return "custom-variant receipts on hand: none (no VSUB sent, or the VACK is still in flight)";
        }
        cc.starsector.client.net.ClientVariantReceipt last = receipts.get(receipts.size() - 1);
        // the REJECT id echoes the player-chosen file stem (ctor-bounded to printable no-space
        // ASCII <= 64); sanitize anyway, like the sibling pick rows
        String verdict = last.accepted()
                ? "ACCEPTED as " + last.variantId()
                : "REJECTED " + last.reasonToken() + ": " + FallbackAnnounce.sanitize(last.variantId(), 64);
        String hint = last.accepted() ? null : VariantReceiptLane.hintOrNull(last.reasonToken());
        return "custom-variant receipts on hand: " + receipts.size() + " (latest seq="
                + last.submitSeq() + " " + verdict + (hint == null ? "" : " -> " + hint) + ")";
    }

    /** Iteration 8 row (d) (the owner's blind spot — a stock spawn the editor never explained):
     *  the latest VSUB verdict as JSON for {@code api/ping.nextVerdict} — null with no predict session (title /
     *  headless), else {@code state} accepted | rejected | pending (+ {@code id}, {@code seq}, {@code token} and a
     *  ready-to-paint {@code line}). The same peek as {@link #customVerdictOnHand}; ids sanitized, the token is the
     *  server's own vocabulary — nothing player-authored reaches the line unsanitized. */
    /* NO org.json in THIS class: it must verify on the jar-only selftest classpath (FoePoseSeenLatchSmoke — the
     * catch-type lesson bit again). The editor route builds the JSON from these two raw accessors. */
    public static boolean hasPredictSession() {
        return CcClientModPlugin.predictSession() != null;
    }

    /** The latest VSUB receipt on hand, or null (no session, none yet, or the peek failed). */
    public static cc.starsector.client.net.ClientVariantReceipt latestVariantReceipt() {
        PredictSession session = CcClientModPlugin.predictSession();
        if (session == null) {
            return null;
        }
        try {
            java.util.List<cc.starsector.client.net.ClientVariantReceipt> receipts = session.peekVariantReceipts();
            return receipts.isEmpty() ? null : receipts.get(receipts.size() - 1);
        } catch (RuntimeException peekFailed) {
            return null;
        }
    }

    // ---------------------------------------------------------------- the CURRENT BUILD
    // The requirement: a button makes a variant the
    // player's current one, and the GUI shows that through some visible means — the connect's
    // HELLO pick, in precedence: (1) the CURRENT BUILD = saves/common/cc_variants/current.data (ONE id-law
    // stem line; set by the editor's FLY, by Save only when nothing is current, by the join window's combo
    // on Launch; cleared by deleting it; CurrentBuildLaw parses it, a torn/lawless file = none), (2) the join
    // window's launch pick (-Dcc.predict.variantId, frozen at load — rigs), (3) "" = the server's default.
    // Read at EVERY dial (startFreshDial) — a FLY at the title flies on the next Multiplayer Test select and
    // every reconnect re-reads it; a fresh game after a rejoin remembers it (the earlier in-memory stem did not).

    /** The join window's launch pick as resolved at load ("" = none) — precedence (2). */
    static volatile String launchVariantPick = "";
    /** The SettingsAPI name of the current-build file ({@code saves/common/cc_variants/current.data}). */
    static final String CURRENT_BUILD_NAME = cc.starsector.client.net.CurrentBuildLaw.COMMON_NAME;

    /** The CURRENT BUILD's stem ("" = none): {@code current.data} read NOW and id-law gated — a torn, lawless,
     *  oversize or absent file is none. Any thread (SettingsAPI; the editor's HTTP thread and the menu thread read). */
    public static String currentBuild() {
        try {
            com.fs.starfarer.api.SettingsAPI settings = com.fs.starfarer.api.Global.getSettings();
            if (settings == null || !settings.fileExistsInCommon(CURRENT_BUILD_NAME)) {
                return "";
            }
            return cc.starsector.client.net.CurrentBuildLaw.parse(settings.readTextFileFromCommon(CURRENT_BUILD_NAME));
        } catch (Exception unreadable) {
            return "";
        }
    }

    /** FLY / Save-when-none: {@code stem} becomes the current build. Refused (false) when the stem fails the id
     *  law; the caller has checked the build's file exists. ONE SettingsAPI write — java.io is B-denied here, so
     *  no mod-visible temp+rename (the join window has one); the gated read above tolerates a torn file. */
    public static boolean setCurrentBuild(String stem) {
        if (!cc.starsector.client.net.LocalVariantIdLaw.isLawful(stem)) {
            PilotWatchLog.info("current build: stem refused (id law) -> unchanged");
            return false;
        }
        try {
            com.fs.starfarer.api.Global.getSettings().writeTextFileToCommon(CURRENT_BUILD_NAME, stem + "\n");
        } catch (Exception failed) {
            PilotWatchLog.info("current build: write failed (" + failed.getClass().getSimpleName() + ") -> unchanged");
            return false;
        }
        PilotWatchLog.info("current build = '" + stem + "' (current.data)");
        return true;
    }

    /** The editor deleted {@code stem}: when it was the current build the file goes too (nobody's next spawn). */
    public static void clearCurrentBuildIf(String stem) {
        if (stem == null || stem.isEmpty() || !stem.equals(currentBuild())) {
            return;
        }
        try {
            com.fs.starfarer.api.Global.getSettings().deleteTextFileFromCommon(CURRENT_BUILD_NAME);
            PilotWatchLog.info("current build '" + stem + "' deleted -> "
                    + (launchVariantPick.isEmpty() ? "server default" : "window pick '" + launchVariantPick + "'"));
        } catch (Exception failed) {
            PilotWatchLog.info("current build: clear failed (" + failed.getClass().getSimpleName() + ")");
        }
    }

    /** Does THIS launch host the editor ({@code -Dcc.editor} not {@code off} — the EditorHost's own switch)?
     *  The current build (the editor's product) is consumed only by such a launch:
     *  a rig's local seat shares the install with the joiner and flew the joiner's build. */
    static boolean editorHostedThisLaunch() {
        return !"off".equalsIgnoreCase(System.getProperty("cc.editor", "on"));
    }

    /** One line per launch when a present current build is NOT consumed (editor off). */
    private static volatile boolean currentBuildSkipAnnounced;

    /** The pick the NEXT connect's HELLO carries ("" = server default): the current build (editor-hosted
     *  launches only), else the window's pick. */
    public static String nextConnectPick() {
        String current = currentBuild();
        boolean hosted = editorHostedThisLaunch();
        if (!hosted && !current.isEmpty() && !currentBuildSkipAnnounced) {
            currentBuildSkipAnnounced = true;
            PilotWatchLog.info("current build '" + FallbackAnnounce.sanitize(current, 64)
                    + "' NOT consumed (this launch does not host the editor, -Dcc.editor=off) -> "
                    + (launchVariantPick.isEmpty() ? "server default" : "window pick '" + launchVariantPick + "'"));
        }
        return cc.starsector.client.net.CurrentBuildLaw.pick(current, launchVariantPick, hosted);
    }

    /** Where {@link #nextConnectPick} came from: {@code current} / {@code pick} / {@code none}
     *  (the SPA footer + the join window's status read it off {@code api/ping}). */
    public static String nextConnectSource() {
        if (editorHostedThisLaunch() && !currentBuild().isEmpty()) {
            return "current";
        }
        return launchVariantPick.isEmpty() ? "none" : "pick";
    }

    /** {@code cc.predict.variantId} / {@code CC_PREDICT_VARIANT_ID} — the joiner's
     * initial-spawn variant pick, bounded HERE to the version-13 A-string law (≤ 64 chars, every
     * char 0x21..0x7E — {@code Handshake13.VARIANT_MAX}; narrower than the HELLO v8 decode, so one
     * value carries at both versions) so a bad value announces at load and drops to the server
     * default instead of a terminal local refusal at the version-13 flight. The server owns
     * pickability (allowlist canonicalization + the not-pickable CHTD notice) — this is the
     * transport bound.
     * <p>Narrowed from v8's single-line printable, which let
     * an inner space and every char ≥ 0x80 (C1 controls included) through. */
    static String resolveVariantPick() {
        String raw = ClientLaunchKnobs.firstNonBlank(System.getProperty("cc.predict.variantId"),
                System.getenv("CC_PREDICT_VARIANT_ID"), "").trim();
        if (raw.isEmpty()) {
            return "";
        }
        boolean clean = raw.length() <= cc.starsector.server.runtime.Handshake13.VARIANT_MAX;
        for (int i = 0; clean && i < raw.length(); i++) {
            char c = raw.charAt(i);
            clean = c >= 0x21 && c <= 0x7e;
        }
        if (!clean) {
            PilotWatchLog.info("onApplicationLoad: cc.predict.variantId ignored (>64 chars, or a char"
                    + " outside printable ASCII 0x21..0x7E; the version-13 A-string law) -> server default");
            return "";
        }
        return raw;
    }

    /** W15 v0: the ONE {@code VSUB} per connect. The HELLO pick names a client-local
     *  id; when {@code saves/common/cc_variants/<id>.variant.data} exists it is parsed under the
     *  server's own JSON law and submitted on the reliable CTRL lane (the door holds the pick
     *  <= 30 s and resolves it on ACCEPT). Everything here is
     *  FAIL-SOFT: a bad id, no file, a malformed file, an over-budget body or a dead lane logs
     *  one line and leaves the server default (the hold expires server-side). */
    static void submitLocalCustomVariant(PredictSession session, String pick) {
        if (pick == null || pick.isEmpty()) {
            return;
        }
        String shown = FallbackAnnounce.sanitize(pick, 64);
        try {
            if (!LocalCustomVariantSource.isLocalIdLawful(pick)) {
                PilotWatchLog.info("predict custom-variant: pick '" + shown
                        + "' is not a local-file id (charset) -> no VSUB");
                return;
            }
            cc.starsector.client.net.ClientVariantDefinition def = LocalCustomVariantSource.load(pick);
            if (def == null) {
                PilotWatchLog.info("predict custom-variant: no local file for pick '" + shown
                        + "' -> no VSUB (server-catalog pick or default)");
                return;
            }
            int bodyBytes = cc.starsector.client.net.VariantSubmitWriter.bodyBytes(def,
                    cc.starsector.server.runtime.IdTables.installedOrNull());
            session.sendVariantSubmit(def);
            PilotWatchLog.info("predict custom-variant: vsub_sent local_id=" + def.variantId()
                    + " hull=" + def.hullId() + " body_bytes=" + bodyBytes + "/"
                    + cc.starsector.client.net.VariantSubmitWriter.MAX_BODY_BYTES
                    + " groups=" + def.weaponGroups().size() + " mods=" + def.hullMods().size());
        } catch (java.io.IOException | RuntimeException notSubmitted) {
            FallbackAnnounce.announceOnce("vsub-" + shown, "custom variant '" + shown
                    + "' NOT submitted: " + notSubmitted.getMessage());
            PilotWatchLog.error("predict custom-variant: local '" + shown
                    + "' NOT submitted -> server default (the door's held pick expires)",
                    notSubmitted);
        }
    }
}
