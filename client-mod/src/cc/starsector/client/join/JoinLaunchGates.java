package cc.starsector.client.join;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import cc.starsector.client.net.DoorProbe;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDocumentLaws;
import cc.starsector.server.match.MatchDocumentRefused;

/**
 * The join window's PRESS-TIME LAUNCH LAW, Swing-free (GUI v2 design): the
 * gates the v1 Launch button ran inline ({@code JoinGui} before the carve) and the FILE hand-off
 * that follows, carved VERBATIM so the retained Swing form and the browser joiner ({@code JoinRoutes}
 * {@code api/launch}) share ONE copy — same order, same wording, same bytes on disk.
 *
 * <p>Two halves, deliberately: {@link #check} is PURE (it reads no file, writes nothing) and yields either a
 * {@link Refusal} — the gate that said no, with its title + text — or a {@link Ready} carrying the sanitized
 * values; {@link #commit} is the hand-off in order (the server's match document → {@code match.txt} through
 * {@link #writeMatchText}; the per-host cid; settings; the resolved ship; voice hook; the variant index; the PLAN
 * FILE, atomically; the current build). Both windows render a refusal in their own way (a modal dialog / an
 * inline red line) — the words are this class's.
 *
 * <p>Gate ids are fixed words the SPA keys on (never player text): {@code address}, {@code password},
 * {@code ship}, {@code probe}, {@code door}, {@code document}, {@code credential_write},
 * {@code cid}, {@code plan_write}. Player-authored input (host, name) is DATA here: sanitized by
 * {@link JoinLaunchPlan}, matched against fixed rules; a refused document is named by OUR reason + field only.
 */
final class JoinLaunchGates {
    private JoinLaunchGates() {
    }

    /** What the form holds at press time — RAW field text; the gates sanitize exactly as v1 did. */
    static final class Form {
        final String host;
        final String port;
        final String name;
        final String password;
        final String ship;
        /** The match document the window staged from the server's probe reply (its raw text, re-parsed and
         *  re-checked here); blank = none staged. */
        final String document;
        final boolean logs;
        /** The Team choice: 0 = the server picks, 1 = South, 2 = North; anything else is the {@code team} gate's
         *  refusal ({@link #check}). */
        final int team;

        /** The form with no Team choice (the server picks) — every gate row that is not about the team. */
        Form(String host, String port, String name, String password, String ship, String document,
                boolean logs) {
            this(host, port, name, password, ship, document, logs, JoinLaunchPlan.TEAM_SERVER_PICKS);
        }

        /** The form with the window's Team choice (raw, gated later). */
        Form(String host, String port, String name, String password, String ship, String document,
                boolean logs, int team) {
            this.host = host == null ? "" : host;
            this.port = port == null ? "" : port;
            this.name = name == null ? "" : name;
            this.password = password == null ? "" : password;
            this.ship = ship == null ? "" : ship;
            this.document = document == null ? "" : document;
            this.logs = logs;
            this.team = team;
        }
    }

    /**
     * What the LAST probe the window ran ended in, keyed to the host:port it asked — the launch gate's input when
     * no document is staged: a Launch then says why it cannot go (no answer, the door's own refusal, a document
     * that did not arrive or was refused) instead of booting a game that would fail at its first frame. A Launch
     * to any other pair reads {@link #NONE}. {@code docOk} = the game may load the cached match file as it stands
     * (a lawful document is staged, or the probe presented the cached file's id and the server sent none back:
     * the cache is current). Lines are this window's own words (fixed text + reason tokens), never peer bytes.
     *
     *
     */
    static final class ProbeOutcome {
        enum Kind { NONE, NO_REPLY, REJECTED, SERVED }

        static final ProbeOutcome NONE = new ProbeOutcome(Kind.NONE, "", -1, "", false, "", false);
        final Kind kind;
        private final String host;
        private final int port;
        final String statusLine;
        final boolean docOk;
        /** The {@code docOk} case that rests on NO stage: the probe presented the cached file's id and the server
         *  sent no document back (the cache is current). A {@code docOk} that is not {@code cacheCurrent} rested on
         *  a staged document, which a brick, an emptied box, another pair's probe or a refused re-probe clears
         *  WITHOUT re-recording this outcome — so {@link #check} honours it only while the Form still carries that
         *  document; else a Launch would go on a match the probe never confirmed. */
        final boolean cacheCurrent;
        final String docLine;

        ProbeOutcome(Kind kind, String host, int port, String statusLine, boolean docOk, String docLine,
                boolean cacheCurrent) {
            this.kind = kind;
            this.host = host == null ? "" : host;
            this.port = port;
            this.statusLine = statusLine == null ? "" : statusLine;
            this.docOk = docOk;
            this.cacheCurrent = cacheCurrent;
            this.docLine = docLine == null ? "" : docLine;
        }

        /** This outcome when it was recorded for {@code host:port} (host case-blind, port numeric), else NONE. */
        ProbeOutcome forPair(String host, String port) {
            return samePair(this.host, this.port, host, port) ? this : NONE;
        }
    }

    /** True when {@code host:port} names the pair {@code askedHost:askedPort} (host case-blind, port numeric). */
    static boolean samePair(String askedHost, int askedPort, String host, String port) {
        try {
            return host != null && port != null && askedHost.equalsIgnoreCase(host.trim())
                    && askedPort == Integer.parseInt(port.trim());
        } catch (NumberFormatException unparsed) {
            return false;
        }
    }

    /** The fetched line: a lawful document was fetched and staged (the shape's name, else its id; the seat count). */
    static String fetchedLine(MatchDescriptor d) {
        String what = d.shape() == null || d.shape().isBlank() ? d.matchId() : d.shape();
        return "Match fetched from the server: " + what + ", " + d.seats().size() + " seats.";
    }

    /** The line for a refused document: OUR reason token + field, never the document's bytes. */
    static String refusedLine(String reasonAndField) {
        return "This server's match was refused (" + reasonAndField + ") - tell the host: their server is not a"
                + " matching CC release.";
    }

    /**
     * The ONE receive law for a served document's text in the join window (the probe's adopt and the Launch's
     * re-check run the same): the shared codec, a pilot seat, then the document laws with strict redaction. A
     * refusal is rendered as OUR reason + field ({@link MatchDocumentRefused}); any other failure reads
     * {@code match_doc_malformed decode} — never an exception message (the codec's text could carry peer bytes).
     *
     *
     */
    static MatchDescriptor lawfulDocument(String text) {
        try {
            MatchDescriptor d = JoinLaunchPlan.parseDescriptor(text);
            JoinLaunchPlan.requirePilotSeat(d);
            MatchDocumentLaws.check(d, true);
            return d;
        } catch (MatchDocumentRefused r) {
            throw new DocumentRefused(r.reason() + " " + r.field());
        } catch (RuntimeException other) {
            throw new DocumentRefused(MatchDocumentLaws.REASON_MALFORMED + " decode");
        }
    }

    /** {@link #lawfulDocument}'s refusal: {@link #getMessage} = OUR reason token + field only. */
    static final class DocumentRefused extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        DocumentRefused(String reasonAndField) {
            super(reasonAndField);
        }
    }

    /**
     * What one served probe reply's match document came to under {@link #lawfulDocument}: {@link Kind#FETCHED}
     * carries the lawful descriptor + the served text it was parsed from (the text a window stages and its Launch
     * re-checks) + the fetched line; {@link Kind#MISSING} (document frames seen, no whole document) and
     * {@link Kind#REFUSED} (a whole document the law refused) carry their line and no descriptor, and mean that any
     * document a window holds for that pair is no longer the server's; {@link Kind#NONE} = the reply carried no
     * document at all (an empty line; a held stage stands). Lines are this window's words; {@code refusal} = OUR
     * reason token + field, never the document's bytes.
     *
     *
     */
    static final class Adopted {
        enum Kind { NONE, FETCHED, MISSING, REFUSED }

        static final Adopted NONE = new Adopted(Kind.NONE, null, null, "", null);
        final Kind kind;
        final MatchDescriptor descriptor;
        final String text;
        final String line;
        final String refusal;

        private Adopted(Kind kind, MatchDescriptor descriptor, String text, String line, String refusal) {
            this.kind = kind;
            this.descriptor = descriptor;
            this.text = text;
            this.line = line;
            this.refusal = refusal;
        }
    }

    /**
     * The ONE adopt of a probe reply's match document: the whole text through {@link #lawfulDocument} (codec, pilot
     * seat, the document laws) ⇒ {@link Adopted.Kind#FETCHED} with {@link #fetchedLine}, or
     * {@link Adopted.Kind#REFUSED} with {@link #refusedLine}; document frames that never made a whole text ⇒
     * {@link Adopted.Kind#MISSING} with {@link #MSG_DOC_MISSING}; no document ⇒ {@link Adopted#NONE}. Pure: it
     * stages, writes and logs nothing — the browser window's probe and the headless match refresh act on the result.
     *
     *
     */
    static Adopted adopt(DoorProbe.Result r) {
        if (r.matchDocText != null) {
            try {
                MatchDescriptor d = lawfulDocument(r.matchDocText);
                return new Adopted(Adopted.Kind.FETCHED, d, r.matchDocText, fetchedLine(d), null);
            } catch (DocumentRefused refused) {
                return new Adopted(Adopted.Kind.REFUSED, null, null, refusedLine(refused.getMessage()),
                        refused.getMessage());
            }
        }
        return r.sawDocument ? new Adopted(Adopted.Kind.MISSING, null, null, MSG_DOC_MISSING, null) : Adopted.NONE;
    }

    /**
     * The ONE writer of {@code CC_Client\match.txt}: the descriptor's canonical bytes (codec encode, LF lines). The
     * Launch hand-off and the headless match refresh both write through here, so the file the game loads is the
     * same bytes whichever path fetched it.
     *
     *
     */
    static void writeMatchText(Path modDir, MatchDescriptor d) throws IOException {
        Files.write(modDir.resolve("match.txt"), JoinLaunchPlan.canonicalBytes(d));
    }

    /** A gate that refused: its id, the v1 dialog title, the v1 dialog text; {@code error} = v1 ERROR_MESSAGE. */
    static final class Refusal {
        final String gate;
        final String title;
        final String message;
        final boolean error;

        Refusal(String gate, String title, String message, boolean error) {
            this.gate = gate;
            this.title = title;
            this.message = message;
            this.error = error;
        }
    }

    /** Every gate passed: the sanitized values the hand-off writes (host/port trimmed; name RAW as v1 — the
     *  plan writer sanitizes it; password + ship sanitized; the parsed paste or null). */
    static final class Ready {
        final String host;
        final String port;
        final String name;
        final String password;
        final String ship;
        final MatchDescriptor pasted;
        /** The gated Team choice, 0..2 (0 = the server picks = no plan row). */
        final int team;

        Ready(String host, String port, String name, String password, String ship, MatchDescriptor pasted) {
            this(host, port, name, password, ship, pasted, JoinLaunchPlan.TEAM_SERVER_PICKS);
        }

        /** The passed gates with the Team choice the plan writes. */
        Ready(String host, String port, String name, String password, String ship, MatchDescriptor pasted,
                int team) {
            this.host = host;
            this.port = port;
            this.name = name;
            this.password = password;
            this.ship = ship;
            this.pasted = pasted;
            this.team = team;
        }
    }

    /**
     * What the last SERVED probe published, KEYED to the host:port
     * it asked. Both windows used to hand {@link #commit} the last probe's default ship + mod diff whatever pair
     * the Launch named — probe A, edit the host, Launch: the plan carried A's {@code ship=} to B and staged A's
     * mod set. A Launch to any other pair now gets what a never-probed Launch always got: no default (the door
     * picks) and {@link JoinModStage.Diff#none()}. The host compares case-blind (DNS), the port as a number.
     */
    static final class Probed {
        static final Probed NONE = new Probed("", -1, null, null);
        private final String host;
        private final int port;
        private final String defaultId;
        private final JoinModStage.Diff modDiff;

        Probed(String host, int port, String defaultId, JoinModStage.Diff modDiff) {
            this.host = host;
            this.port = port;
            this.defaultId = defaultId;
            this.modDiff = modDiff;
        }

        private boolean asked(Ready r) {
            try {
                return host.equalsIgnoreCase(r.host) && port == Integer.parseInt(r.port);
            } catch (NumberFormatException unparsed) {
                return false;
            }
        }

        String defaultFor(Ready r) {
            return asked(r) ? defaultId : null;
        }

        JoinModStage.Diff modDiffFor(Ready r) {
            return asked(r) && modDiff != null ? modDiff : JoinModStage.Diff.none();
        }
    }

    /** Exactly one of the two is non-null. */
    static final class Verdict {
        final Refusal refusal;
        final Ready ready;

        private Verdict(Refusal refusal, Ready ready) {
            this.refusal = refusal;
            this.ready = ready;
        }

        static Verdict refuse(String gate, String title, String message) {
            return new Verdict(new Refusal(gate, title, message, false), null);
        }
    }

    static final String TITLE_NOT_READY = "Not ready to launch";
    static final String TITLE_PASSWORD = "Match password rejected";
    static final String TITLE_SHIP = "Ship pick rejected";
    static final String TITLE_LAUNCH_FAILED = "Launch failed";
    static final String TITLE_VOICE = "Voice settings";
    /** The mods gate (a required mod missing / wrong version / different files)
     *  and the mods-root staging failure share this title. */
    static final String TITLE_MODS = "Mods";
    /** The probe / door / document gates: the server's match is not ready for this launch. */
    static final String TITLE_MATCH = "Match not ready";

    /** The team gate's word, title and text: the Team choice is 0 / 1 / 2 only. */
    static final String GATE_TEAM = "team";
    static final String TITLE_TEAM = "Team pick rejected";
    static final String MSG_TEAM = "Pick a team: let the server pick, South team or North team.";

    /** No probe answered (or none ran) for the launch's pair. */
    static final String MSG_NO_ANSWER = "No answer from the server yet - press Refresh; if it repeats,"
            + " check the address and port with the host.";
    /** The door refused this folder's content print ({@code content_fingerprint_mismatch}): ONE copy, the door
     *  probe's own reject text (the join window reads the net layer's words, never the reverse), so the line a
     *  refused probe shows and the line a window composes cannot drift apart. */
    static final String MSG_KIT_DIFFERS = DoorProbe.rejectMessage("content_fingerprint_mismatch");
    /** The probe was served but its document did not arrive whole. */
    static final String MSG_DOC_MISSING = "The server's match did not arrive, or only in part - press Refresh;"
            + " if this repeats, tell the host: their server is not sending its match.";
    /** The probe staged the server's match, but the stage was cleared before Launch (a brick, an emptied box,
     *  another pair's probe): nothing lawful to write, so the document is fetched again. */
    static final String MSG_STAGE_GONE = "The server's match was fetched but is no longer staged - press Refresh,"
            + " then Launch.";

    /**
     * The gates in order: host/port → password → ship charset → the match. A staged document is re-parsed and
     * re-checked ({@link #lawfulDocument}) and becomes the launch's {@code match.txt}; with none staged the LAST
     * probe for this pair decides: no answer ⇒ {@code probe}, the door's refusal ⇒ {@code door} (its own
     * line), served with the cache current ⇒ ready with nothing to write, served on a stage this Form no longer
     * carries ⇒ {@code document} ({@link #MSG_STAGE_GONE}), served otherwise ⇒ {@code document} (the recorded
     * missing / refused line). Reads no file — the kit's cached {@code match.txt} is never a launch input here.
     */
    static Verdict check(Form f, Path modDir, ProbeOutcome probe) {
        String host = f.host.trim();
        String port = f.port.trim();
        String err = JoinLaunchPlan.validate(host, port);
        if (err != null) {
            return Verdict.refuse("address", TITLE_NOT_READY, err);
        }
        String password = JoinLaunchPlan.sanitizePassword(f.password);
        String passwordErr = JoinLaunchPlan.validatePassword(password);
        if (passwordErr != null) {
            return Verdict.refuse("password", TITLE_PASSWORD, passwordErr);
        }
        String ship = JoinLaunchPlan.sanitizeShip(f.ship);
        String shipErr = JoinLaunchPlan.validateShip(ship);
        if (shipErr != null) {
            return Verdict.refuse("ship", TITLE_SHIP, shipErr);
        }
        // The team gate — the window sends 0 / 1 / 2 only; any other
        // value (a raw client, a bad field) refuses before any file changes
        if (f.team < JoinLaunchPlan.TEAM_SERVER_PICKS || f.team > JoinLaunchPlan.TEAM_MAX) {
            return Verdict.refuse(GATE_TEAM, TITLE_TEAM, MSG_TEAM);
        }
        // The match gate. A staged document is parsed (never yet
        // written) under the same laws its adopt ran, so a refusal lands BEFORE any file changes.
        if (!f.document.isBlank()) {
            try {
                return new Verdict(null, new Ready(host, port, f.name, password, ship, lawfulDocument(f.document),
                        f.team));
            } catch (DocumentRefused r) {
                return Verdict.refuse("document", TITLE_MATCH, refusedLine(r.getMessage()));
            }
        }
        ProbeOutcome last = probe == null ? ProbeOutcome.NONE : probe.forPair(host, port);
        switch (last.kind) {
            case REJECTED:
                return Verdict.refuse("door", TITLE_MATCH, last.statusLine);
            case SERVED:
                if (last.cacheCurrent) {
                    return new Verdict(null, new Ready(host, port, f.name, password, ship, null, f.team));
                }
                if (last.docOk) {
                    // The outcome rested on a stage this Form no longer carries (cleared since the
                    // probe) — nothing lawful to write, so the Launch never boots whatever match.txt is on disk
                    return Verdict.refuse("document", TITLE_MATCH, MSG_STAGE_GONE);
                }
                // Both windows record their probes now, so a served outcome
                // with no usable match always names the document (the recorded missing / refused line)
                return Verdict.refuse("document", TITLE_MATCH, last.docLine.isEmpty() ? MSG_DOC_MISSING : last.docLine);
            default:
                return Verdict.refuse("probe", TITLE_MATCH, MSG_NO_ANSWER);
        }
    }

    /** The window's voice settings write (the Swing panel's merge; null = nothing to write). A throw is a
     *  WARNING, never a refusal — the settings are a nicety, the launch proceeds. */
    interface VoiceWriter {
        void write() throws Exception;
    }

    /** The hand-off's result: {@code refusal} non-null = aborted (nothing launched); else the plan is on disk. */
    static final class Handoff {
        final Refusal refusal;
        /** The v1 "Voice settings not saved … - launching anyway" text, or null. */
        final String warning;
        final String resolvedShip;
        final String cid;

        private Handoff(Refusal refusal, String warning, String resolvedShip, String cid) {
            this.refusal = refusal;
            this.warning = warning;
            this.resolvedShip = resolvedShip;
            this.cid = cid;
        }
    }

    /**
     * The FILE hand-off, in order: (1) a staged server document becomes {@code match.txt} ({@link #writeMatchText});
     * (2) the per-host client id is derived from this install's master beside {@code settingsPath} (minted on first
     * use) — the door keys the seat by it; (3) the settings file (best-effort); (4) the plan's ship = the pick, else
     * the served default; (5) the voice hook; (6) the variant index publish (row (e)); (7) the PLAN FILE,
     * written beside itself and moved into place (row (f) law 3) — the bat polls for it; (8) the current build
     * (best-effort). {@code fetchedDefault} = the last served server default or null.
     * The pre-rung-2 shape (the {@code --selftest} goldens): no mods diff, no mods-root staging.
     */
    static Handoff commit(Ready r, Path modDir, Path planFile, Path settingsPath,
            String fetchedDefault, boolean logs, VoiceWriter voice) {
        return commit(r, modDir, planFile, settingsPath, fetchedDefault, logs, voice, null);
    }

    /**
     * {@code modDiff} = the last served probe's mod-set diff
     * ({@link JoinModStage#diff}; {@link JoinModStage.Diff#none()} when nothing was published —
     * the root then holds the kit alone); null = skip the mods gate and the staging entirely
     * (legacy callers). The gate runs FIRST (before any file write) and names every problem row;
     * the staging runs right before the plan write, so a failed stage launches nothing.
     */
    static Handoff commit(Ready r, Path modDir, Path planFile, Path settingsPath,
            String fetchedDefault, boolean logs, VoiceWriter voice, JoinModStage.Diff modDiff) {
        if (modDiff != null && !modDiff.ok) {
            StringBuilder msg = new StringBuilder(modDiff.line);
            for (JoinModStage.Row row : modDiff.rows) {
                if (!JoinModStage.STATUS_OK.equals(row.status)) {
                    msg.append('\n').append(row.line());
                }
            }
            msg.append("\nThe server admits only its exact mod set - fix the rows above, press"
                    + " Refresh, then Launch. (We never redistribute other people's mods.)");
            return new Handoff(new Refusal(JoinModStage.GATE, TITLE_MODS, msg.toString(), false), null, null, null);
        }
        if (r.pasted != null) {
            try {
                writeMatchText(modDir, r.pasted);
            } catch (IOException ex) {
                return new Handoff(new Refusal("credential_write", TITLE_LAUNCH_FAILED,
                        "Could not save the server's match as match.txt: " + ex.getClass().getSimpleName(), false),
                        null, null, null);
            }
        }
        // The id this install presents to THIS host, derived from the
        // master beside the settings file in use (the same id on every relaunch, so a rejoin keeps its seat; another
        // host never learns it). Password mode only — a blank password keeps the cid row empty and mints nothing.
        String cid = "";
        if (!r.password.isEmpty()) {
            try {
                cid = JoinSettings.hostCid(settingsPath.resolveSibling(JoinSettings.CID_MASTER_FILE), r.host,
                        Integer.parseInt(r.port), true);
            } catch (IOException | RuntimeException ex) {
                return new Handoff(new Refusal("cid", TITLE_LAUNCH_FAILED, "Could not mint this install's seat"
                        + " identity (" + ex.getClass().getSimpleName() + ") - the folder next to"
                        + " join-settings.properties must be writable", false), null, null, null);
            }
        }
        // The password is remembered FOR r.host:r.port; an empty one keeps the slot (forLaunch)
        try {
            JoinSettings.forLaunch(settingsPath, r.host, r.port, JoinLaunchPlan.sanitizeName(r.name), r.password, cid,
                    logs, r.ship).save(settingsPath);
        } catch (IOException | RuntimeException ignored) {
            // prefill persistence is best-effort, never blocks a launch
        }
        // SETTINGS above keep the sanitized pick ("" for the server-default
        // entry, so the next open re-fetches), but the PLAN carries a CONCRETE id so entry 0 spawns as the
        // host-picked default from the first fly-in; no default served => resolves to the same value as today.
        String resolvedShip = JoinLaunchPlan.resolveLaunchShip(r.ship, fetchedDefault);
        // L5: the voice keys ride the shared settings file BEFORE the plan is written; a write failure warns
        // and launches anyway (the settings are a nicety).
        String warning = null;
        if (voice != null) {
            try {
                voice.write();
            } catch (Exception ex) {
                warning = "Voice settings not saved: " + ex + " - launching anyway";
            }
        }
        // row (e): a file dropped in while the window sat open still reaches the editor's list
        Path installRoot = JoinVoiceSettings.installRoot(modDir);
        JoinCore.publishVariantIndex(installRoot, JoinCore.localCustomReport(installRoot));
        // The kit's own mods root = the server's set as links + enabled_mods.json
        // (the player's mods\enabled_mods.json is never touched; the bat points the game here).
        if (modDiff != null) {
            try {
                JoinModStage.stage(installRoot, modDir, modDiff.rows);
            } catch (IOException | RuntimeException ex) {
                return new Handoff(new Refusal("mods_stage", TITLE_MODS,
                        "Could not prepare the mod set the server needs: " + ex.getMessage()
                                + "\n(The kit links the required mods under saves\\common\\cc_mods"
                                + " - nothing in your mods folder is changed.)", true), warning, null, cid);
            }
        }
        // row (f) law 3: the bat POLLS for this file (the window no longer exits to hand it over) — written
        // beside itself as .tmp and moved into place, so a half-written plan is never read
        try {
            JoinCore.writePlanAtomically(planFile, JoinLaunchPlan.planFileContent(r.host, r.port, r.name,
                    r.password, cid, logs, resolvedShip, r.team));
        } catch (IOException | RuntimeException ex) {
            return new Handoff(new Refusal("plan_write", TITLE_LAUNCH_FAILED,
                    "Could not hand the launch plan back to the launcher: " + ex, true), warning, null, cid);
        }
        // A pick here IS the current build (the same state the editor's FLY sets). The
        // server-default entry ("" after sanitizeShip) CLEARS it ("leave an
        // editor selection alone" let a 9/18 FLY override the player's explicit server-default pick at dial
        // time, CurrentBuildLaw.pick — the combo opens ON the current build, so empty = the player chose
        // against it). Best-effort like the settings above — the plan already carries the pick for THIS launch.
        try {
            if (r.ship.isEmpty()) {
                CurrentBuildFile.clear(installRoot);
            } else if (cc.starsector.client.net.LocalVariantIdLaw.isLawful(r.ship)) {
                CurrentBuildFile.write(installRoot, r.ship);
            }
        } catch (IOException | RuntimeException ex) {
            // the launch proceeds on the plan; the next open falls back to the remembered pick
        }
        return new Handoff(null, warning, resolvedShip, cid);
    }
}
