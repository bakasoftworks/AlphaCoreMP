package cc.starsector.client.join;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import cc.starsector.client.net.DoorProbe;
import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;

import static cc.starsector.client.join.JoinCore.LocalCustoms;
import static cc.starsector.client.join.JoinCore.localCustomReport;
import static cc.starsector.client.join.JoinCore.publishVariantIndex;
import static cc.starsector.client.join.JoinGui.showCustomsNotes;
import static cc.starsector.client.join.JoinGui.growWindowToFit;

// Carve — the nested JoinGui.ShipListFetcher moved verbatim (dedented), main-tree code
/**
 * The ship-list fetch choreography — EDT reads the fields and owns the widgets;
 * ONE daemon worker per fetch runs the blocking {@link JoinFingerprint#selfCompute} (cached per
 * staged match id, {@link #printFor}) + the door call ({@link #doorCall}, default {@link JoinProbe#fetch}) — a
 * reply carrying the server's match runs the shared adopt and ONE confirming re-probe — and hands the result back via
 * {@code invokeLater}, where the stage and {@link #lastProbe} land for the Launch gate; a
 * generation counter discards a stale result when a newer fetch was started meanwhile.
 * Field edits re-fetch through a 700 ms restartable {@link Timer} (a pasted connect brick
 * sets host+port+password in one burst ⇒ one probe, and the door's 1 s per-source budget
 * is never hammered by keystrokes). Nothing here throws at the EDT. The commit gate
 * ({@link ProbeCommitGate}): only HOST/PORT edits restart that Timer, and its fire probes only
 * while the password is COMMITTED — a password keystroke never probes (a prefix probe is a recorded
 * FAIL the door's no-reset backoff keeps for hours); a commit probes at once.
 */
// package-private (was private) so JoinGuiProbeSelftest's headless
// rider-r6 pin can construct one and read debounceRunning()
final class ShipListFetcher {
    static final String NOT_READY = JoinProbe.NOT_READY_LINE;   // Shared with the SPA (api/probe)

    /** this window's door call — the seam row {@code swing-paste-re-probes}
     *  swaps (restored in {@code finally}; production never writes it), twin of {@link JoinBrowserUi#doorCall}.
     *  {@link #fetchNow} reads it on the EDT, so a fetch started under one door finishes under that door. */
    static volatile JoinBrowserUi.DoorCall doorCall = JoinProbe::fetch;

    /** One cached print + the match id it was computed for (null = the kit file's own id) — the browser
     *  window's {@code KeyedPrint}, copied: a staged match changes the key, so the next probe rebuilds. */
    private static final class KeyedPrint {
        final String matchId;
        final JoinFingerprint.Computed computed;

        KeyedPrint(String matchId, JoinFingerprint.Computed computed) {
            this.matchId = matchId;
            this.computed = computed;
        }
    }

    private final Path modDir;
    private final String clientId;
    private final JTextField hostField;
    private final JTextField portField;
    // The masked field, read only as getPassword() in effectivePassword()
    private final JPasswordField passwordField;
    private final JComboBox<String> shipBox;
    // The combo's id -> display-name pair (ShipChoiceNames), installed on the
    // box at construction; filled from each served probe's names and hull ids in apply
    private final ShipChoiceNames shipNames;
    private final JButton refresh;
    private final JLabel status;
    private final AtomicInteger generation = new AtomicInteger();
    /** The probe print + the match id it was computed for (null = none yet); written by the workers. */
    private final AtomicReference<KeyedPrint> fingerprint = new AtomicReference<>();
    /** The server's match document a probe adopted (its raw served text, re-checked at Launch, which writes it as
     *  {@code match.txt}), its {@code match.id} and the pair whose probe carried it — the browser window's stage,
     *  copied. Null / "" / -1 = nothing staged ⇒ the probe presents the kit file's own id. Written on the EDT only
     *  ({@link #apply}, {@link #fetchNow}); a worker gets the id as a value read beside its generation bump.
     * */
    private volatile String stagedText;
    private volatile String stagedMatchId;
    private volatile String stagedHost = "";
    private volatile int stagedPort = -1;
    /** Every probe's outcome past its generation guard, keyed to its pair — the Launch gate's input when nothing
     *  is staged ({@link JoinLaunchGates#check}); NONE = no probe has landed. EDT-written. */
    private volatile JoinLaunchGates.ProbeOutcome lastProbe = JoinLaunchGates.ProbeOutcome.NONE;
    /** One task run on the EDT after the next probe lands (null = none): the armed auto-press waits on it. */
    private Runnable afterLanded;
    /** Connect-code box texts refused as not a connect code (EDT-confined; the row reads it) — never logged. */
    private int pasteRefusals;
    private final Timer debounce;
    // Rider r6's commit table (EDT-confined, like every field here)
    private final ProbeCommitGate gate;
    // The last SERVED probe's server-default id + mod diff,
    // keyed to the pair that probe asked — the Launch path reads them through it, so an
    // unchanged "(server default…)" pick spawns as the host-picked ship of THAT host only.
    private JoinLaunchGates.Probed probed = JoinLaunchGates.Probed.NONE;
    // The last probe's landed result (null = none yet) —
    // an unchanged Enter reads it so the "already checked" hint never overwrites a REJECT / held line
    private DoorProbe.Result lastResult;
    // W15 v1: the local-customs status rows (null = none attached)
    private JLabel customsNote;
    // The settings read at open (null = none); read ONLY via effectivePassword()
    private final JoinSettings remembered;

    void customsNote(JLabel label) {
        this.customsNote = label;
    }

    ShipListFetcher(Path modDir, String savedCid, JTextField hostField, JTextField portField,
            JPasswordField passwordField, JComboBox<String> shipBox, JButton refresh,
            JLabel status, boolean passwordCommittedAtOpen, JoinSettings remembered) {
        this.modDir = modDir;
        this.remembered = remembered;
        // The door requires a client-instance id even for a probe (client_id_required); a
        // probe never claims, so an install without one yet uses a throwaway mint here — the
        // Launch path still mints + persists its own, untouched.
        this.clientId = savedCid == null || savedCid.isEmpty()
                ? JoinLaunchPlan.mintClientId() : savedCid;
        this.hostField = hostField;
        this.portField = portField;
        this.passwordField = passwordField;
        this.shipBox = shipBox;
        this.shipNames = ShipChoiceNames.install(shipBox); //
        this.refresh = refresh;
        this.status = status;
        this.gate = new ProbeCommitGate(passwordCommittedAtOpen);
        this.debounce = new Timer(700, e -> debounceFired());
        this.debounce.setRepeats(false);
    }

    /** The host/port fields' listener: every edit restarts the 700 ms debounce. */
    DocumentListener debouncedListener() {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                otherFieldEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                otherFieldEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                otherFieldEdited();
            }
        };
    }

    private void otherFieldEdited() {
        gate.otherFieldEdited();
        debounce.restart();
    }

    /** The selftest pin's read of the 700 ms host/port debounce. */
    boolean debounceRunning() {
        return debounce.isRunning();
    }

    /** The selftest pin's teardown — no live Timer outlives it. */
    void stopDebounce() {
        debounce.stop();
    }

    /**
     * The password field's listener — a keystroke marks it
     * UNCOMMITTED and schedules nothing (rider r6); an idle window says how to commit.
     */
    DocumentListener passwordListener() {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                passwordEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                passwordEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                passwordEdited();
            }
        };
    }

    private void passwordEdited() {
        gate.passwordEdited();
        if (refresh.isEnabled()) {   // no fetch in flight: its result line would overwrite this one
            showBlocked();
        }
    }

    /**
     * A COMMIT that probes at once (the Refresh click; Enter rides
     * {@link #commitOnEnter}). EDT only.
     */
    void commitAndFetch() {
        if (gate.passwordCommitted()) {
            fetchNow();
        }
    }

    /**
     * Enter in the password field — probe iff edited since
     * the last commit; unchanged + never committed (empty at open) = the blocked/not-ready line. Unchanged +
     * committed: the "already checked" hint ONLY when the last probe
     * was SERVED; a REJECT line stays as it is (edit the password); a held / no-reply / failed line gains
     * "press Refresh to retry" (Refresh re-probes on demand); a committed password never probed yet (saved at
     * open, the host debounce not fired) takes its FIRST probe — that is no re-probe. EDT only.
     */
    void commitOnEnter() {
        if (gate.passwordEnter()) {
            fetchNow();
            return;
        }
        if (!gate.probeAllowed()) {
            showBlocked();
            return;
        }
        DoorProbe.Result last = lastResult;
        if (last == null) {
            fetchNow();
        } else if (last.served()) {
            status.setText(ProbeCommitGate.ENTER_UNCHANGED_STATUS);
        } else if (last.rejectReason == null) {
            status.setText(last.statusLine() + ProbeCommitGate.ENTER_RETRY_SUFFIX);
        }
    }

    /** Focus left the password field — probe iff edited since the last commit. */
    void commitOnFocusLost() {
        if (gate.passwordFocusLost()) {
            fetchNow();
        }
    }

    /**
     * A COMMIT with no probe of its own — the Launch press (its
     * HELLO is the check) and a pasted connect code (in the top field the host/port debounce its setText calls
     * restarted fires the one probe; the lower box probes at once, {@link #pasteChanged}). EDT only.
     */
    void commitUntyped() {
        gate.passwordCommitted();
    }

    private void debounceFired() {
        if (gate.debounceFired()) {
            fetchNow();
        } else {
            showBlocked();
        }
    }

    /** The uncommitted status: NOT_READY while the fields could not probe anyway, else the press-Enter line. */
    private void showBlocked() {
        status.setText(fieldsProbeable() ? gate.blockedStatusLine() : NOT_READY);
    }

    /**
     * The ONE password this window sends (probe, custom dry runs,
     * Launch) — the typed field (sanitized) when non-empty, else the password remembered for the fields'
     * CURRENT host:port, resolved at each use, never at open ({@code ""} once either is edited off the pair).
     */
    String effectivePassword() {
        String typed = JoinLaunchPlan.sanitizePassword(new String(passwordField.getPassword()));
        return !typed.isEmpty() || remembered == null ? typed
                : remembered.rememberedPasswordFor(hostField.getText(), portField.getText());
    }

    /** The one NOT_READY predicate {@link #fetchNow} and {@link #showBlocked} share. */
    private boolean fieldsProbeable() {
        String password = effectivePassword();
        return JoinLaunchPlan.validate(hostField.getText().trim(), portField.getText().trim()) == null
                && !password.isEmpty() && JoinLaunchPlan.validatePassword(password) == null;
    }

    /**
     * The connect-code box, the twin of the browser's api/paste: a settled {@code address:port:password} fills the
     * three fields. A PASTED code commits its password and probes once; a TYPED one fills them uncommitted, like any
     * keystroke (its prefixes parse as codes too, and a prefix probe is a failure the door keeps), so the next commit
     * probes. Any other text — a match document included — is refused with the browser's one line and counted: never
     * parsed, staged or echoed (the match itself arrives in the server's probe reply). The browser's paste cap bounds
     * this box too, so an over-cap text is refused unparsed. Blank = nothing. EDT only.
     *
     * <p>
     *
     * @return true when a code filled the fields (the window then empties a pasted one: the password leaves the
     *         plain-text box)
     */
    boolean pasteChanged(String text, boolean pasted) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String[] brick = text.length() > JoinRoutes.PASTE_MAX ? null : JoinLaunchPlan.parseBrick(text);
        if (brick == null) {
            pasteRefusals++;
            status.setText(JoinBrowserUi.MSG_NOT_A_CONNECT_CODE);
            return false;
        }
        hostField.setText(brick[0]);
        portField.setText(brick[1]);
        passwordField.setText(brick[2]);
        if (pasted) {
            commitUntyped();
            fetchNow();
        }
        return true;
    }

    /** The row's read of the refused-paste count (EDT). */
    int pasteRefusals() {
        return pasteRefusals;
    }

    /** The print for {@code matchId} (null = the kit file's), cached
     *  per id (it was computed ONCE from the on-disk {@code match.txt}). Blocking file reads: the worker only. */
    private JoinFingerprint.Computed printFor(String matchId) {
        KeyedPrint cached = fingerprint.get();
        if (cached != null && Objects.equals(cached.matchId, matchId)) {
            return cached.computed;
        }
        JoinFingerprint.Computed print = JoinFingerprint.selfCompute(modDir, matchId);
        fingerprint.set(new KeyedPrint(matchId, print));
        return print;
    }

    /** EDT only. */
    void fetchNow() {
        debounce.stop();
        String host = hostField.getText().trim();
        String port = portField.getText().trim();
        String password = effectivePassword();
        if (!fieldsProbeable()) {   // rider r6 (4): a COMMIT on invalid fields keeps the NOT_READY path
            status.setText(NOT_READY);
            return;
        }
        int gen = generation.incrementAndGet();
        int portNo = Integer.parseInt(port);
        // Another server's staged match is never presented here, nor launched
        // there; the staged id + the door are read HERE on the EDT beside the generation bump, so this worker probes
        // the stage it was started for (a newer probe is a newer generation, and apply drops this one)
        if (stagedText != null && !JoinLaunchGates.samePair(stagedHost, stagedPort, host, port)) {
            clearStage();
        }
        String matchId = stagedMatchId;
        JoinBrowserUi.DoorCall door = doorCall;
        status.setText("Ship list: asking " + host + ":" + port + " ...");
        refresh.setEnabled(false);
        Thread worker = new Thread(() -> {
            JoinFingerprint.Computed asked = printFor(matchId);
            String presented = asked.print == null || asked.print.isEmpty() ? null
                    : ContentFingerprints.componentOrNull(asked.print, "match");
            boolean presentedCachedId = matchId == null && presented != null
                    && !ContentFingerprints.MATCH_NONE.equals(presented);
            DoorProbe.Result first = door.fetch(host, portNo, password, clientId, asked.print);
            // the shared adopt, ONE per probe: a fetched document is confirmed by ONE re-probe presenting its id, whose
            // listing replaces the first (its own document, if any, waits for the next probe); a re-probe that went
            // unanswered keeps the first listing
            JoinLaunchGates.Adopted adopted = first.served() ? JoinLaunchGates.adopt(first)
                    : JoinLaunchGates.Adopted.NONE;
            JoinFingerprint.Computed print = asked;
            DoorProbe.Result listing = first;
            if (adopted.kind == JoinLaunchGates.Adopted.Kind.FETCHED) {
                if (gen != generation.get()) {
                    return; // superseded before the re-probe: the newer fetch lands instead
                }
                print = printFor(adopted.descriptor.matchId());
                DoorProbe.Result again = door.fetch(host, portNo, password, clientId, print.print);
                if (again.served()) {
                    listing = again;
                }
            }
            DoorProbe.Result result = listing;
            String finalPrint = print.print;
            // the cache is current when the probe presented the cached file's id and the server sent no document back
            boolean cacheCurrent = first.served() && !first.sawDocument && presentedCachedId;
            // W15 v0: the local scan; the
            // per-custom SERVER verdict after a SERVED probe — both off the EDT (file IO +
            // the door's paced dry-run sends), landed together under the generation guard.
            LocalCustoms localReport = result.served()
                    ? localCustomReport(JoinVoiceSettings.installRoot(modDir)) : null;
            List<String> verdictLines = localReport == null || localReport.ids.isEmpty()
                    ? List.of()
                    : JoinCore.verdictLines(JoinCore.customVerdicts(localReport, host, portNo,
                            password, finalPrint));
            // The published mod set vs. the joiner's mods folder (file IO)
            JoinModStage.Diff modDiff = result.served()
                    ? JoinModStage.diff(result.mods, result.modsAvailable, result.modsComplete,
                            JoinModStage.scan(JoinVoiceSettings.installRoot(modDir)))
                    : null;
            SwingUtilities.invokeLater(() -> {
                if (gen != generation.get()) {
                    return; // a newer fetch is in flight; its result will land
                }
                apply(host, portNo, result, adopted, cacheCurrent, localReport, verdictLines, modDiff);
                Runnable then = afterLanded;
                afterLanded = null;
                if (then != null) {
                    then.run();
                }
            });
        }, "cc-join-ship-list");
        worker.setDaemon(true);
        worker.start();
    }

    /** The staged pair's state, emptied: the next probe presents the kit file's own id. EDT only. */
    private void clearStage() {
        stagedText = null;
        stagedMatchId = null;
        stagedHost = "";
        stagedPort = -1;
    }

    /** A lawful served document becomes the stage of the pair whose probe carried it. EDT only. */
    private void stage(String text, MatchDescriptor d, String host, int port) {
        stagedText = text;
        stagedMatchId = d.matchId();
        stagedHost = host;
        stagedPort = port;
    }

    /** A probe's result, past its generation guard (the caller's). EDT only. */
    private void apply(String host, int portNo, DoorProbe.Result result, JoinLaunchGates.Adopted adopted,
            boolean cacheCurrent, LocalCustoms localReport, List<String> verdictLines, JoinModStage.Diff modDiff) {
        refresh.setEnabled(true);
        status.setText(result.statusLine());
        lastResult = result; // read by the unchanged-Enter hint
        if (!result.served()) {
            // The door's refusal (its own line) or no answer is what a Launch
            // with nothing staged for this pair shows; a held stage stands
            lastProbe = new JoinLaunchGates.ProbeOutcome(result.rejectReason != null
                    ? JoinLaunchGates.ProbeOutcome.Kind.REJECTED : JoinLaunchGates.ProbeOutcome.Kind.NO_REPLY,
                    host, portNo, result.statusLine(), false, "", false);
            return; // keep whatever the box holds (remembered/typed pick stays launchable)
        }
        // The adopt lands with its listing — a fetched document is this pair's
        // stage; a refused one, or frames that never made a whole document, mean a held older stage is not the
        // server's match any more. docOk = a stage for this pair, or the cache is current.
        if (adopted.kind == JoinLaunchGates.Adopted.Kind.FETCHED) {
            stage(adopted.text, adopted.descriptor, host, portNo);
        } else if (adopted.kind != JoinLaunchGates.Adopted.Kind.NONE) {
            clearStage();
        }
        boolean stagedHere = stagedText != null
                && JoinLaunchGates.samePair(stagedHost, stagedPort, host, String.valueOf(portNo));
        String line = localReport.ids.isEmpty() ? result.statusLine()
                : result.statusLine() + " + " + localReport.ids.size() + " local custom(s)";
        lastProbe = new JoinLaunchGates.ProbeOutcome(JoinLaunchGates.ProbeOutcome.Kind.SERVED, host, portNo, line,
                stagedHere || cacheCurrent, adopted.line, cacheCurrent);
        // Remember the server's host-picked default so the
        // Launch path can carry it, and label combo entry 0 with the concrete id when named.
        probed = new JoinLaunchGates.Probed(host, portNo, result.defaultId, modDiff); // the Launch gate + staging read it
        String defaultLabel = result.defaultId != null
                ? JoinProbe.serverDefaultLabel(result.defaultId)
                : JoinProbe.SERVER_DEFAULT_LABEL;
        String current = JoinLaunchPlan.sanitizeShip(
                String.valueOf(shipBox.getEditor().getItem()));
        // The served names replace the last probe's (read AFTER current, so the
        // old shown name maps back to its id first); local customs keep their bare ids (LocalCustoms has no names).
        // The hull ids ride the same replace: the rows read name · hull, none = as before
        shipNames.replace(result.names, result.hulls);
        shipBox.removeAllItems();
        shipBox.addItem(defaultLabel);
        for (String id : result.ids) {
            shipBox.addItem(id);
        }
        // W15 v0: local custom files keep their rows across a refresh (the scan
        // ran on the worker; served ⇒ never null)
        publishVariantIndex(JoinVoiceSettings.installRoot(modDir), localReport); // row (e)
        JoinCore.publishServerBuilds(JoinVoiceSettings.installRoot(modDir), result.ids, result.defaultId, System.currentTimeMillis()); // iteration 9 (owner #4)
        List<String> local = localReport.ids;
        if (customsNote != null) {
            showCustomsNotes(customsNote, localReport.notes, verdictLines);
            growWindowToFit(customsNote); // Review: notes that appear/grow after pack()
        }
        for (String id : local) {
            if (!result.ids.contains(id)) {
                shipBox.addItem(id);
            }
        }
        if (!current.isEmpty() && !result.ids.contains(current) && !local.contains(current)) {
            shipBox.addItem(current); // a remembered/typed id the server no longer lists
        }
        shipBox.setSelectedItem(current.isEmpty() ? defaultLabel : current);
        // the match's line (fetched / did not arrive / refused) follows the list's, empty when no document came
        status.setText(adopted.line.isEmpty() ? line : line + "  " + adopted.line);
        if (modDiff != null && !modDiff.line.isEmpty()) {
            StringBuilder sb = new StringBuilder(status.getText()).append("  ").append(modDiff.line);
            for (JoinModStage.Row row : modDiff.rows) {
                if (!JoinModStage.STATUS_OK.equals(row.status)) {
                    sb.append("  ").append(row.line());
                }
            }
            status.setText(sb.toString());
        }
    }

    /** The last served probe's default ship + mod diff, keyed to the pair it asked (NONE = no served probe yet). */
    JoinLaunchGates.Probed probed() {
        return probed;
    }

    /** The last probe's outcome as the Launch gate reads it (NONE = none landed yet). */
    JoinLaunchGates.ProbeOutcome lastProbe() {
        return lastProbe;
    }

    /** The staged document's text when the fields' pair is the one that served it, else "" (the browser's Launch
     *  rule: a staged match rides only to its own server). */
    String stagedDocumentFor(String host, String port) {
        String text = stagedText;
        return text != null && JoinLaunchGates.samePair(stagedHost, stagedPort, host, port) ? text : "";
    }

    /**
     * The Launch press's verdict for this window's fields: the staged document for the fields' pair (re-checked under
     * the same laws its adopt ran), else the last probe's outcome — no answer, the door's own refusal, a match that
     * did not arrive or was refused, or a current cached file. The one writer of {@code match.txt} stays
     * {@link JoinLaunchGates#commit}, which writes the staged document's canonical bytes. EDT only.
     *
     *
     */
    JoinLaunchGates.Verdict launchVerdict(String name, String ship, boolean logs) {
        String host = hostField.getText();
        String port = portField.getText();
        return JoinLaunchGates.check(new JoinLaunchGates.Form(host, port, name, effectivePassword(), ship,
                stagedDocumentFor(host, port), logs, JoinLaunchPlan.TEAM_SERVER_PICKS), modDir, lastProbe);
    }

    /** Runs {@code task} once on the EDT after the next probe lands (replacing any task not yet run). EDT only. */
    void afterNextProbe(Runnable task) {
        afterLanded = task;
    }
}
