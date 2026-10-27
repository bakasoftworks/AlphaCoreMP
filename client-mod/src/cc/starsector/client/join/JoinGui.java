package cc.starsector.client.join;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import cc.starsector.client.net.DoorProbe;
import cc.starsector.client.net.DoorVariantCheck;

// The Swing-free laws live in JoinCore (shared with the v2 browser joiner);
// the on-demand static import keeps every call site + selftest row reading as before the carve
import static cc.starsector.client.join.JoinCore.*;

/**
 * Friend-grade joiner connection dialog: replaces join-cc.bat's
 * console prompts with a window for server address + port + pilot name + match password
 * (the ONE credential this window collects; the legacy per-seat token
 * field was removed, leaving the scripted env escape hatch — the bat's
 * {@code CC_JOIN_TOKEN} into the kit handoff, else the game's {@code CC_PREDICT_SEAT_TOKEN} —
 * as the only out-of-descriptor token entry), and fetches the match from the server: the probe reply carries the
 * host's match document, which this window checks under the game's own descriptor codec and the document laws,
 * stages, and writes as match.txt at Launch — the player handles no match file.
 *
 * <p>Runs OUTSIDE the game on the install's bundled JRE 17 ({@code java.desktop} verified present
 * 2026-07-06) from inside cc-client-mod.jar — so the kit's jarMd5 hard-stop version-gates the GUI
 * too. The bat stays the orchestrator: it starts this class DETACHED with (modDir, planFile,
 * launch nonce) and polls; this dialog writes a CRLF k=v plan file on Launch (atomically; and
 * match.txt when the probe fetched a new match), then FLIPS to the launch face ({@link #showLaunchFace}:
 * status / Open editor / Close, bound to the game the bat launches by the nonce — row (f))
 * while the bat's UNCHANGED transport-autodetect /
 * JAVA_TOOL_OPTIONS / launch / debrief lines take over. Cancel or window-close before Launch
 * writes {@code <plan>.cancel} and the bat aborts without launching.
 *
 * <p>{@code --selftest} runs the headless logic-layer assertions (no window, safe in CI/build
 * gates): settings round-trip, descriptor normalize/decode/canonicalize, seat-2 check, plan-file
 * golden, validation table. No window opens during selftest; Swing is otherwise only touched inside {@link #runGui} —
 * the two headless pins build unshown components: rider-r6 ShipListFetcher (a javax.swing.Timer + unshown fields)
 * and SwingPlainText (a JOptionPane tree).
 * The battery itself lives in {@link JoinGuiSelftest} (carved out verbatim).
 */
public final class JoinGui {

    private JoinGui() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && "--selftest".equals(args[0])) {
            JoinGuiSelftest.run(); // The battery lives in JoinGuiSelftest
            System.out.println("join-gui selftest PASS");
            return;
        }
        if (args.length >= 4 && "--probe".equals(args[0])) {
            System.exit(runProbeCli(args));
            return;
        }
        if (args.length >= 4 && "--refresh-match".equals(args[0])) {
            System.exit(runRefreshMatch(args[1], args[2], Paths.get(args[3])));
            return;
        }
        if (args.length >= 2 && "--launched".equals(args[0])) {
            // Row (f) law 3: the launch FACE alone — a REJOIN pass
            // of join-cc.bat (the first pass's window left when the game closed) and
            // the screenshot companion tool. args[2] = the launch nonce the bat minted for this pass.
            System.exit(runLaunched(Paths.get(args[1]), args.length >= 3 ? nonceArg(args[2]) : null));
            return;
        }
        if (args.length < 2) {
            System.err.println("usage: JoinGui <modDir> <planFile> [nonce] | JoinGui --selftest"
                    + " | JoinGui --probe <host> <port> <password> [modDir [customFile...]]"
                    + " | JoinGui --launched <modDir> [nonce]"
                    + " | JoinGui --refresh-match <host> <port> <modDir>");
            System.exit(64);
            return;
        }
        Path modDir = Paths.get(args[0]);
        Path planFile = Paths.get(args[1]);
        String nonce = args.length >= 3 ? nonceArg(args[2]) : null;
        String fallbackNote = null;
        if (JoinBrowserUi.selected()) {
            // GUI v2 ("Fallback + switch"): -Dcc.join.ui=browser = the responder + the
            // browser SPA; a bind/browse/never-loaded failure falls back to THIS Swing form with a one-line note.
            JoinBrowserUi.Outcome browser = JoinBrowserUi.run(modDir, planFile, nonce);
            if (browser.exitCode != null) {
                System.exit(browser.exitCode);
                return;
            }
            fallbackNote = browser.fallbackNote;
        }
        int code = runGui(modDir, planFile, nonce, fallbackNote);
        System.exit(code);
    }


    /** {@link #runRefreshMatch}'s exit codes; the launcher tests them highest first. */
    static final int REFRESH_CURRENT = 0;
    static final int REFRESH_REFUSED = 1;
    static final int REFRESH_NO_ANSWER = 2;
    static final int REFRESH_UPDATED = 10;

    /**
     * {@link #runRefreshMatch}'s door call and settings file (its {@code cid-master} sits beside it): production =
     * {@link DoorProbe#fetch13} and {@link JoinSettings#defaultPath} (null here); the selftest rows swap both and
     * restore them in {@code finally} — production never writes them. Read once per refresh.
     *
     *
     */
    static volatile JoinBrowserUi.DoorCall refreshDoor = DoorProbe::fetch13;
    static volatile Path refreshSettingsPath;

    /**
     * {@code --refresh-match <host> <port> <modDir>}: the launcher's headless match refresh before a rejoin — ONE
     * probe to that server ({@link #refreshDoor}) with the password remembered for exactly that pair and this
     * install's id for that host (read, never minted: no master ⇒ refused), presenting the cached match file's id;
     * the reply's document goes through the shared adopt ({@link JoinLaunchGates#adopt}). {@link #REFRESH_CURRENT}
     * = no document came back for a cached id (the cache is current); {@link #REFRESH_UPDATED} = the server's
     * document passed the window's receive law and the server's mod set matches this install, and it was written
     * through the one match.txt writer; {@link #REFRESH_REFUSED} = anything unusable (no remembered password or id,
     * a door refusal, a partial or refused document, nothing cached and nothing sent, another mod set, a failed
     * write) — the join window must open; {@link #REFRESH_NO_ANSWER} = the server did not answer. Writes match.txt
     * and nothing else (no settings, plan, handoff, index or marker). One stdout line per exit, in plain words — no
     * password, no id, no document bytes, no server-sent text.
     *
     *
     */
    static int runRefreshMatch(String host, String port, Path modDir) {
        try {
            String h = host == null ? "" : host.trim();
            String p = port == null ? "" : port.trim();
            if (JoinLaunchPlan.validate(h, p) != null) {
                return refreshExit(REFRESH_REFUSED, "the address or port is not usable");
            }
            int portNo = Integer.parseInt(p);
            Path settings = refreshSettingsPath != null ? refreshSettingsPath : JoinSettings.defaultPath();
            String pw = JoinSettings.load(settings).rememberedPasswordFor(h, p);
            if (pw.isEmpty()) {
                return refreshExit(REFRESH_REFUSED, "no match password is remembered for this server");
            }
            String cid = JoinSettings.hostCid(settings.resolveSibling(JoinSettings.CID_MASTER_FILE), h, portNo, false);
            if (cid == null) {
                return refreshExit(REFRESH_REFUSED, "this install has no seat identity yet");
            }
            JoinFingerprint.Computed print = JoinFingerprint.selfCompute(modDir, null);
            String presented = print.print == null || print.print.isEmpty() ? null
                    : cc.starsector.server.match.ContentFingerprints.componentOrNull(print.print, "match");
            DoorProbe.Result r = refreshDoor.fetch(h, portNo, pw, cid, print.print);
            if (!r.served()) {
                return r.rejectReason == null ? refreshExit(REFRESH_NO_ANSWER, "the server did not answer")
                        : refreshExit(REFRESH_REFUSED, "the server refused the request");
            }
            JoinLaunchGates.Adopted adopted = JoinLaunchGates.adopt(r);
            switch (adopted.kind) {
                case MISSING:
                    return refreshExit(REFRESH_REFUSED, "the server's match did not arrive whole");
                case REFUSED:
                    return refreshExit(REFRESH_REFUSED, "the server's match was refused (" + adopted.refusal + ")");
                case NONE:
                    return presented != null
                            && !cc.starsector.server.match.ContentFingerprints.MATCH_NONE.equals(presented)
                            ? refreshExit(REFRESH_CURRENT, "the cached match is current")
                            : refreshExit(REFRESH_REFUSED, "the server sent no match and none is cached");
                default:
                    break;
            }
            if (!JoinModStage.diff(r.mods, r.modsAvailable, r.modsComplete,
                    JoinModStage.scan(JoinVoiceSettings.installRoot(modDir))).ok) {
                return refreshExit(REFRESH_REFUSED, "the server's mod set differs from this install's");
            }
            JoinLaunchGates.writeMatchText(modDir, adopted.descriptor);
            return refreshExit(REFRESH_UPDATED, "the server's new match was saved as match.txt");
        } catch (IOException | RuntimeException ex) {
            return refreshExit(REFRESH_REFUSED, "the refresh failed (" + ex.getClass().getSimpleName() + ")");
        }
    }

    private static int refreshExit(int code, String line) {
        System.out.println("refresh-match: " + line + " (exit " + code + ")");
        return code;
    }

    /** ONE window, one title: the form and the launch face it flips to share it (row (f) law 3). */
    static final String WINDOW_TITLE = "AlphaCoreMP - Join Multiplayer Match";

    // ------------------------------------------------------------------ GUI

    /**
     * {@code fallbackNote} = null for the v1 lane (byte-for-byte today's window); GUI v2's browser mode passes its
     * one-line reason ("browser unavailable - native window (…)") and the form shows it above the fields.
     */
    private static int runGui(Path modDir, Path planFile, String nonce, String fallbackNote) throws Exception {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // default L&F is fine
        }
        Path settingsPath = JoinSettings.pathForRun();
        JoinSettings saved = JoinSettings.load(settingsPath);
        boolean credentialPresent = hasKitCredential(modDir);
        // The HAIRPIN-window ADMIN AUTO-AUTH lane.
        // A TOOLING arm (arm-watch-join.ps1 -AutoLaunch) sets CC_JOIN_AUTOLAUNCH=1 in the
        // inherited env (the CC_WATCH_SIDELOG lane: cmd -> JoinGui -> game JVM) after it
        // merge-wrote the armed credential into join-settings — this window then drives its OWN
        // Launch, so the kit game window comes up already authenticating (as ADMIN when the
        // armed row is the admin secret) with ZERO operator clicks. One-shot by construction (env,
        // never a settings row, so a later manual open is interactive as ever; a public-kit
        // stranger has no such env). Guarded on a password remembered FOR the saved host:port:
        // with none the press would only pop a warning dialog — stay interactive. Any validation dialog on
        // the auto press fails OPEN to the interactive window (modal, then the operator decides).
        // -Dcc.join.autopress=1 (GUI v2 step 4): the SAME armed press for the game-free smoke's
        // forced browse-fail row (run-join-smoke.ps1 run H) — a launch-line knob, unset in every shipped line,
        // guarded by the same remembered-password rule; it never reaches the browser joiner (runGui only).
        boolean rememberedAtOpen = !saved.rememberedPasswordFor(saved.host, saved.port).isEmpty();
        boolean autoLaunch = ("1".equals(System.getenv("CC_JOIN_AUTOLAUNCH"))
                || "1".equals(System.getProperty("cc.join.autopress")))
                && rememberedAtOpen;

        AtomicInteger exitCode = new AtomicInteger(2);
        // D4: set by "Open editor" → "Launch now" — the launch face then opens the browser at the
        // title screen without another click (row (f): the intent stays in THIS window, no plan row).
        AtomicBoolean editorRequested = new AtomicBoolean(false);
        // row (f) law 3: the plan has been handed over — a close from here on is the face's Close,
        // never a cancel (the bat is already launching / running the game)
        AtomicBoolean launched = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame(WINDOW_TITLE);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            JTextField hostField = new JTextField(saved.host, 24);
            JTextField portField = new JTextField(saved.port, 6);
            JTextField nameField = new JTextField(saved.name, 24);
            // Opens EMPTY, never pre-filled; an empty field = fetcher.effectivePassword()'s resolve.
            // MASKED - its one read is ShipListFetcher's getPassword()
            JPasswordField passwordField = new JPasswordField("", 34);
            // The initial-spawn ship picker.
            // Entry 0 = server default; the rest is the SERVER's own pickable list,
            // fetched pre-launch through the door PROBE (JoinProbe) once address, port and
            // password are all present — never kit-baked (the server-settable boundary). Editable: a typed
            // id is the fallback when the list is unavailable; Launch re-validates the charset,
            // the server decides pickability at admission. The last pick is remembered like name.
            JComboBox<String> shipBox = new JComboBox<>();
            shipBox.setEditable(true);
            shipBox.addItem(JoinProbe.SERVER_DEFAULT_LABEL);
            // The CURRENT BUILD (saves/common/cc_variants/current.data — the editor's FLY /
            // Save-when-none, or this window's own last Launch) pre-selects the combo ahead of the remembered pick
            String currentBuild = CurrentBuildFile.read(JoinVoiceSettings.installRoot(modDir));
            String initialShip = currentBuild.isEmpty() ? saved.ship : currentBuild;
            if (!initialShip.isEmpty()) {
                shipBox.addItem(initialShip);
                shipBox.setSelectedItem(initialShip);
            }
            // W15 v0: the player's LOCAL custom variants (saves\common\cc_variants\
            // <id>.variant.data) join the picker as plain ids — picking one rides the ordinary
            // ship= -> HELLO path and the mod submits the file (VSUB) right after the connect.
            LocalCustoms localCustoms = localCustomReport(JoinVoiceSettings.installRoot(modDir));
            publishVariantIndex(JoinVoiceSettings.installRoot(modDir), localCustoms); // row (e): the editor's list
            for (String id : localCustoms.ids) {
                if (!id.equals(saved.ship)) {
                    shipBox.addItem(id);
                }
            }
            JButton refreshShips = new JButton("Refresh list");
            // W15 v1: the in-game variant editor. The RUNNING game writes
            // saves\common\cc_editor_endpoint.data (port|token|nonce); the button pings it and opens the
            // default browser ONLY for the game THIS window launched (the launch nonce) — no game answering
            // = the Launch offer
            // (openEditorOrOfferLaunch), never a browse of some other live game.
            JButton openEditor = new JButton("Open editor");
            openEditor.setToolTipText("Build a custom variant in your browser (in the game this window launches; "
                    + "offers to launch it if none is running yet)");
            JLabel shipStatus = new JLabel(ShipListFetcher.NOT_READY);
            // W15 v1: per-file statuses for local customs the game would
            // otherwise skip silently (wrong suffix, stem law, displayName law) — re-graded on
            // every Refresh so a fixed file clears its row.
            JLabel customsNote = new JLabel();
            showCustomsNotes(customsNote, localCustoms.notes);
            // No settings cid exists any more and the fetcher holds ONE id
            // for whatever pair the fields name, so it takes its throwaway probe mint (a probe claims no seat) — a
            // host-derived id fixed here would show one server another's; the Launch derives the per-host id
            ShipListFetcher fetcher = new ShipListFetcher(modDir, null, hostField, portField,
                    passwordField, shipBox, refreshShips, shipStatus, rememberedAtOpen, saved);
            fetcher.customsNote(customsNote);
            // ProbeCommitGate: the probe fires on a password
            // COMMIT, never per keystroke — a Refresh click commits and probes at once.
            refreshShips.addActionListener(e -> fetcher.commitAndFetch());
            // openEditor is wired below, after the Launch button exists (D4: with no game
            // answering it offers to Launch now with editor=1 in the plan).
            DocumentListener refetch = fetcher.debouncedListener();
            hostField.getDocument().addDocumentListener(refetch);
            portField.getDocument().addDocumentListener(refetch);
            // rider r6: a password keystroke marks the field UNCOMMITTED and restarts no debounce;
            // Enter and a focus loss after an edit are the field's two COMMIT events
            passwordField.getDocument().addDocumentListener(fetcher.passwordListener());
            // Enter commits only while Refresh is enabled — fetchNow
            // disables it for the probe in flight, and a held/repeated Enter on an unchanged field would re-probe
            // every press (each one a recorded door guess)
            // ONE Enter law with the web twin — Enter probes
            // only a password EDITED since the last commit; over an unchanged committed one it shows a hint and
            // sends nothing (never auto-launches). Refresh still re-probes on demand.
            passwordField.addActionListener(e -> {
                if (refreshShips.isEnabled()) {
                    fetcher.commitOnEnter();
                }
            });
            passwordField.addFocusListener(new FocusAdapter() {
                /**
                 * Only a PERMANENT focus loss inside the window commits.
                 * {@code !isTemporary()}: alt-tab / window deactivation is a TEMPORARY loss and must never commit a
                 * half-typed prefix; {@code getOppositeComponent() != null}: a null opposite = focus left the window.
                 */
                @Override
                public void focusLost(FocusEvent e) {
                    if (!e.isTemporary() && e.getOppositeComponent() != null) {
                        fetcher.commitOnFocusLost();
                    }
                }
            });
            JTextArea pasteArea = new JTextArea(6, 44);
            pasteArea.setLineWrap(false);
            // The CONNECT-CODE box, the browser's paste box — its text, settled
            // 250 ms by its OWN timer (a paste or a typed burst = one parse; not the host/port debounce), goes to
            // ShipListFetcher.pasteChanged, which owns the commit law: a pasted code fills the fields and probes, a
            // typed one fills them uncommitted, anything else is refused with one line. A pasted code then leaves
            // this plain box (set from the timer, never inside the box's own listener). Launch never reads it.
            boolean[] pastedBurst = new boolean[1];
            Timer pasteSettle = new Timer(250, e -> {
                boolean pasted = pastedBurst[0];
                pastedBurst[0] = false;
                if (fetcher.pasteChanged(pasteArea.getText(), pasted) && pasted) {
                    pasteArea.setText("");
                }
            });
            pasteSettle.setRepeats(false);
            pasteArea.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    if (e.getLength() > 1) {
                        pastedBurst[0] = true;   // a paste lands as one insert; a keystroke is one character
                    }
                    pasteSettle.restart();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    pasteSettle.restart();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    pasteSettle.restart();
                }
            });
            // Paste-to-autofill — the host GUI's "Copy
            // join brick" mints ip:port:password; pasting it here fills address, port and
            // password in one go. Parse is fail-closed (JoinLaunchPlan.parseBrick): anything
            // that is not exactly a brick autofills nothing, and Launch validation is
            // unchanged (this field is never read at Launch).
            JTextField brickField = new JTextField("", 34);
            brickField.getDocument().addDocumentListener(new DocumentListener() {
                private void autofill(boolean pasted) {
                    String[] parts = JoinLaunchPlan.parseBrick(brickField.getText());
                    if (parts == null) {
                        return;
                    }
                    hostField.setText(parts[0]);
                    portField.setText(parts[1]);
                    passwordField.setText(parts[2]);
                    if (pasted) {
                        // rider r6: a PASTED code carries the whole password — a commit (the host/port
                        // debounce just restarted by the setText calls probes it once); a TYPED code
                        // stays uncommitted like any keystroke (its prefixes parse as bricks too)
                        fetcher.commitUntyped();
                        // The pasted code leaves this plain box once its three
                        // parts are in the fields - later, never inline (a document cannot be mutated inside its
                        // own listener); the "" that removeUpdate then parses fills nothing. A TYPED code is not
                        // cleared: its first password character already parses, and the rest is still coming.
                        SwingUtilities.invokeLater(() -> brickField.setText(""));
                    }
                }

                @Override
                public void insertUpdate(DocumentEvent e) {
                    autofill(e.getLength() > 1);
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    autofill(false);
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    autofill(false);
                }
            });

            JLabel sessionLabel = new JLabel(credentialPresent
                    ? "Session: UDP - a saved copy of the match is in this folder; this window asks the server"
                            + " whether it is current."
                    : "Session: UDP - no match saved yet; this window fetches the match from the server once the"
                            + " password checks out.");

            // this tick governs the player's Desktop
            // session-report zip AND the mod sidelog (cc-client.log) — off = neither is written,
            // and a host's debug bit no longer overrides it. The
            // "a debug watch needs its own sidelog" is served by the tooling
            // lane: an ARMED kit is handed CC_WATCH_SIDELOG and the launcher then never sends
            // sideLog=none. Remembered like the other fields (persisted on
            // Launch, reloaded into this checkbox). Default ON.
            JCheckBox logsBox = new JCheckBox(
                    "Save a session report zip on the Desktop (helps the host debug)",
                    saved.logs);

            JPanel form = new JPanel();
            form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
            form.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
            form.add(row("Got a connect code from the host? Paste it here to fill the"
                    + " fields below (ip:port:password):", brickField));
            form.add(Box.createVerticalStrut(10));
            form.add(row("Server address (ask the host):", hostField));
            form.add(Box.createVerticalStrut(6));
            form.add(row("Server port (ask the host):", portField));
            form.add(Box.createVerticalStrut(6));
            form.add(row("Your pilot name (optional, shown over your ship):", nameField));
            form.add(Box.createVerticalStrut(6));
            // A FIXED hint (never the value, never the host)
            form.add(row("Match password (ask the host - one password admits everyone;" + (rememberedAtOpen
                    ? " leave empty to use the one remembered for this address and port):"
                    : " it is remembered for this address and port):"), passwordField));
            form.add(Box.createVerticalStrut(6));
            form.add(shipRow(shipBox, refreshShips, openEditor));
            form.add(Box.createVerticalStrut(2));
            form.add(leftAlign(shipStatus));
            form.add(leftAlign(customsNote));
            form.add(Box.createVerticalStrut(6));
            logsBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(logsBox);
            // W12 VOICE L5: the "Voice" box — mic pick / enable /
            // gain / Test level bar; prefilled from the install's shared settings file and
            // merged back into it on Launch (JoinVoiceSettings carries the file law).
            Path voiceFile = JoinVoiceSettings.settingsFile(JoinVoiceSettings.installRoot(modDir));
            JoinVoicePanel voicePanel = new JoinVoicePanel(JoinVoiceSettings.read(voiceFile));
            form.add(Box.createVerticalStrut(8));
            form.add(voicePanel);
            form.add(Box.createVerticalStrut(10));
            form.add(leftAlign(sessionLabel));
            form.add(Box.createVerticalStrut(10));
            form.add(leftAlign(new JLabel(
                    "Connect code (address:port:password) - paste it here, or type the fields above:")));
            form.add(Box.createVerticalStrut(4));
            JScrollPane scroll = new JScrollPane(pasteArea);
            scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(scroll);

            JButton launch = new JButton("Launch game");
            JButton cancel = new JButton("Cancel");
            JPanel buttons = new JPanel();
            buttons.add(launch);
            buttons.add(cancel);
            openEditor.addActionListener(e -> openEditorOrOfferLaunch(frame, modDir, nonce, launch,
                    editorRequested, openEditor));

            launch.addActionListener(e -> {
                // rider r6: the Launch press COMMITS the password before the gates read the field; no
                // probe here — the launch's own HELLO is the check (a refusal leaves it committed, so
                // the next host/port debounce or Refresh probes it)
                fetcher.commitUntyped();
                // The press-time gates + the FILE hand-off are
                // JoinLaunchGates — ONE law with the browser joiner's api/launch (v1 order, v1 words, v1
                // bytes). This listener only renders the verdict the v1 way: a modal dialog per refusal.
                // The fetcher's verdict — the match its probe staged for these
                // fields' server, else that probe's own outcome (the browser window's launch law)
                JoinLaunchGates.Verdict verdict = fetcher.launchVerdict(nameField.getText(),
                        String.valueOf(shipBox.getEditor().getItem()), logsBox.isSelected());
                if (verdict.refusal != null) {
                    SwingPlainText.showMessage(frame, verdict.refusal.message, verdict.refusal.title,
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
                // L5: the voice keys ride the shared settings file BEFORE the plan is written; a write
                // failure warns and launches anyway (the settings are a nicety) — the dialog is shown
                // from inside the hook so it lands where v1 showed it (before the plan write).
                voicePanel.stopTest();
                JoinLaunchGates.VoiceWriter voice = () -> {
                    try {
                        JoinVoiceSettings.write(voiceFile, JoinVoiceSettings.merge(
                                JoinVoiceSettings.read(voiceFile), voicePanel.deviceValue(),
                                voicePanel.micOnValue(), voicePanel.micGainValue(),
                                voicePanel.complexityValue()));
                    } catch (IOException | RuntimeException ex) {
                        SwingPlainText.showMessage(frame,
                                "Voice settings not saved: " + ex + " - launching anyway",
                                JoinLaunchGates.TITLE_VOICE, JOptionPane.WARNING_MESSAGE);
                    }
                };
                JoinLaunchGates.Probed probed = fetcher.probed(); // keyed: another pair's probe hands over nothing
                JoinLaunchGates.Handoff handoff = JoinLaunchGates.commit(verdict.ready, modDir, planFile,
                        settingsPath, probed.defaultFor(verdict.ready), logsBox.isSelected(), voice,
                        probed.modDiffFor(verdict.ready));
                if (handoff.refusal != null) {
                    SwingPlainText.showMessage(frame, handoff.refusal.message, handoff.refusal.title,
                            handoff.refusal.error ? JOptionPane.ERROR_MESSAGE : JOptionPane.WARNING_MESSAGE);
                    return;
                }
                exitCode.set(0);
                launched.set(true);
                if (autoLaunch) {
                    // the ownerless zero-click rig (arm-watch-join -AutoLaunch): no window outlives
                    // the launch, so the sweep learns nothing new (the retired CC_JOIN_NOCOMPANION)
                    frame.dispose();
                    return;
                }
                voicePanel.stopTest();
                // iteration 9 (c): the editor auto-opens on the first live ping unless opted out
                showLaunchFace(frame, editorEndpointFile(modDir), nonce, editorRequested.get() || JoinCore.autoEditorEnabled());
            });
            cancel.addActionListener(e -> frame.dispose());
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    voicePanel.stopTest();
                    if (!launched.get()) {
                        // Cancel, or the window closed before Launch: the polling bat reads this
                        // marker as its old "errorlevel 1" and prints its cancelled line
                        writeCancelMarker(planFile);
                    }
                    done.countDown();
                }
            });

            frame.getContentPane().setLayout(new BorderLayout());
            if (fallbackNote != null) {
                // GUI v2 fallback: the one line that says why the native window opened instead
                JLabel note = new JLabel(fallbackNote);
                note.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 8));
                frame.getContentPane().add(note, BorderLayout.NORTH);
            }
            frame.getContentPane().add(form, BorderLayout.CENTER);
            frame.getContentPane().add(buttons, BorderLayout.SOUTH);
            frame.pack();
            // The minimum FOLLOWS the live measure (JoinWindowDpi re-pins it on every
            // DPI cross); the old setMinimumSize(frame.getSize()) froze the pack-time monitor's metrics
            frame.setMinimumSize(frame.getPreferredSize());
            JoinWindowDpi.arm(frame, true);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            // auto-fetch on open: a remembered address/port + the password remembered for
            // them fill the list before the player touches anything; a blank window just shows NOT_READY.
            // rider r6: a password remembered for the saved pair is COMMITTED at open (the gate's ctor).
            fetcher.fetchNow();
            if (autoLaunch) {
                // The armed press follows the open probe's landing — the Launch
                // gate reads that probe's outcome (the fetched match, or why there is none), so a press before it
                // lands could only say "no answer yet". Fields that could not probe (Refresh still enabled) press
                // at once and the gate says why; the plan's ship still falls back to the remembered pick / server
                // default (resolveLaunchShip).
                if (refreshShips.isEnabled()) {
                    launch.doClick();
                } else {
                    fetcher.afterNextProbe(launch::doClick);
                }
            }
        });

        done.await();
        return exitCode.get();
    }

    /**
     * Drill lane: {@code --probe <host> <port> <password> [modDir]} — the window's
     * list fetch, headless, printed as k=v rows (row 4 of the drill probe script twins
     * it against the server-tree drill client's receipt on the same live door). {@code modDir}
     * present ⇒ the fingerprint self-computes exactly as the window's does; absent ⇒ blank
     * (an unarmed door accepts blank). Exit 0 served / 1 rejected / 2 failed.
     * {@code args[5..]} custom files dry-run against the
     * door using the game-written caches under {@code -Dcc.join.cacheDir} (default
     * {@code installRoot(modDir)/saves/common/cc_variants}), printed as {@code probe_cache_dir=} /
     * {@code probe_cache_ready=}.
     */
    private static int runProbeCli(String[] args) {
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        String password = args[3];
        JoinFingerprint.Computed print = args.length >= 5
                ? JoinFingerprint.selfCompute(Paths.get(args[4]))
                : new JoinFingerprint.Computed("", "no modDir given - no content fingerprint"
                        + " presented");
        DoorProbe.Result r = JoinProbe.fetch(host, port, password, "join-gui-probe", print.print);
        System.out.println("probe_fingerprint_carried=" + !print.print.isEmpty());
        System.out.println("probe_fingerprint_note=" + print.note);
        System.out.println("probe_served=" + r.served());
        System.out.println("probe_reject=" + (r.rejectReason == null ? "" : r.rejectReason));
        System.out.println("probe_error=" + (r.error == null ? "" : r.error));
        System.out.println("probe_ids=" + String.join(",", r.ids));
        System.out.println("probe_hidden=" + r.hidden);
        System.out.println("probe_total_available=" + r.totalAvailable);
        System.out.println("probe_status=" + r.statusLine());
        if (r.served() && args.length >= 6) {
            // args[5..] = local .variant.data files to dry-run
            // on the same door after the served listing (drill-join-probe row 5); needs org.json
            // on the classpath (the drill adds starsector-core\json.jar), else the fail-open row.
            LocalCustoms local = new LocalCustoms();
            for (int i = 5; i < args.length; i++) {
                Path file = Paths.get(args[i]);
                String name = file.getFileName().toString();
                String id = name.endsWith(".variant.data")
                        ? name.substring(0, name.length() - ".variant.data".length()) : name;
                local.ids.add(id);
                local.files.put(id, file);
            }
            // The dry-run needs the id table + mod set the GAME cached
            // at its last boot — the window reads them from installRoot(modDir); this CLI
            // runs from a repo modDir with no such cache, so -Dcc.join.cacheDir=<dir> names the game-written
            // customs dir explicitly (the drill points it at the kit env's); absent/blank = the window's default.
            String cacheDirProp = System.getProperty("cc.join.cacheDir");
            Path cacheDir = cacheDirProp != null && !cacheDirProp.trim().isEmpty()
                    ? Paths.get(cacheDirProp.trim())
                    : JoinCore.customsDir(JoinVoiceSettings.installRoot(Paths.get(args[4])));
            JoinCore.attachBootCaches(local, cacheDir);
            System.out.println("probe_cache_dir=" + cacheDir);
            System.out.println("probe_cache_ready=" + (java.nio.file.Files.isRegularFile(local.idTablesFile)
                    && java.nio.file.Files.isRegularFile(local.modSetFile)));
            java.util.Map<String, DoorVariantCheck.Verdict> verdicts =
                    JoinCore.customVerdicts(local, host, port, password, print.print);
            StringBuilder rows = new StringBuilder();
            for (java.util.Map.Entry<String, DoorVariantCheck.Verdict> e : verdicts.entrySet()) {
                if (rows.length() > 0) {
                    rows.append(',');
                }
                rows.append(e.getKey()).append('=').append(e.getValue().word());
                if (!e.getValue().token().isEmpty()) {
                    rows.append(':').append(e.getValue().token());
                }
            }
            System.out.println("probe_verdicts=" + rows);
        }
        return r.served() ? 0 : r.rejectReason != null ? 1 : 2;
    }

    /** Render the local-customs notes under the ship status (HTML JLabel, entities escaped,
     *  at most 4 rows + a "+N more" tail); hidden when there is nothing to say. */
    static void showCustomsNotes(JLabel label, List<String> notes) {
        showCustomsNotes(label, notes, List.of());
    }

    /** The Refresh twin also carries the per-custom SERVER
     *  verdict rows ({@code JoinCore.verdictLines}) under their own head, same 4-row cap. */
    static void showCustomsNotes(JLabel label, List<String> notes, List<String> verdictLines) {
        if (notes.isEmpty() && verdictLines.isEmpty()) {
            label.setText("");
            label.setVisible(false);
            return;
        }
        // a CSS body width makes Swing's HTML view WRAP long notes instead of widening the frame
        StringBuilder html = new StringBuilder("<html><body style='width: 440px'>");
        appendNoteBlock(html, "Local custom files:", notes);
        if (!verdictLines.isEmpty()) {
            if (!notes.isEmpty()) {
                html.append("<br>");
            }
            appendNoteBlock(html, "Server check:", verdictLines);
        }
        label.setText(html.append("</body></html>").toString());
        label.setVisible(true);
    }

    private static void appendNoteBlock(StringBuilder html, String head, List<String> rows) {
        if (rows.isEmpty()) {
            return;
        }
        html.append(head);
        int shown = 0;
        for (String row : rows) {
            if (shown == 4) {
                html.append("<br>+").append(rows.size() - shown).append(" more");
                break;
            }
            html.append("<br>- ").append(row.replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;"));
            shown++;
        }
    }

    /** The frame packs ONCE at build; a notes label that first appears or grows after a Refresh
     *  would otherwise be squeezed inside the fixed frame (BoxLayout). Grow-only, never shrink
     *  (the player may have sized the window); no-op with no window (the selftest). */
    static void growWindowToFit(JLabel label) {
        java.awt.Window window = SwingUtilities.getWindowAncestor(label);
        if (window == null) {
            return;
        }
        Dimension pref = window.getPreferredSize();
        Dimension cur = window.getSize();
        if (pref.width > cur.width || pref.height > cur.height) {
            window.setSize(Math.max(pref.width, cur.width), Math.max(pref.height, cur.height));
            window.validate();
        }
    }

    private static JPanel shipRow(JComboBox<String> shipBox, JButton refresh, JButton openEditor) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel label = new JLabel("Ship for your first spawn (the list comes from the server;"
                + " leave it on " + JoinProbe.SERVER_DEFAULT_LABEL + " if unsure):");
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel line = new JPanel();
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        line.add(JoinWindowDpi.heightToPreferred(shipBox));   // Live height cap, not a pack-time pin
        line.add(Box.createHorizontalStrut(6));
        line.add(refresh);
        line.add(Box.createHorizontalStrut(6));
        line.add(openEditor);
        panel.add(label);
        panel.add(line);
        return panel;
    }

    /** Open {@code live} in the default browser; a browser-less box gets the address to paste. */
    static void browseEditor(Component parent, String live) {
        try {
            Desktop.getDesktop().browse(URI.create(live));
        } catch (IOException | RuntimeException cannot) {
            SwingPlainText.showMessage(parent,
                    "Could not open a browser here. Paste this address into one:\n" + live,
                    "Open editor", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * W15 editor D4: the join window's Open editor. A live game BOUND TO
     * THIS WINDOW by the launch nonce opens the browser
     * (as in the browser joiner — the endpoint file sits in a folder other local accounts
     * can write, so a nonce-less or foreign file is never browsed; a null nonce = dev tooling, the
     * old any-game shape). Before Launch no game carries the nonce, so the press OFFERS TO LAUNCH
     * — it runs this window's own Launch, and the launch face
     * this window flips to ({@link #showLaunchFace}) opens the browser at the title screen.
     * A playtest found the window closed on Launch with no way left to reach the
     * editor; a later one found the separate "helper" window that fixed it confusing —
     * row (f) is the ONE-window shape.
     */
    static void openEditorOrOfferLaunch(JFrame frame, Path modDir, String nonce, JButton launch,
            AtomicBoolean editorRequested, JButton openEditor) {
        // the ping (2 s connect + 2 s read on a stale endpoint file) used to run on the EDT and froze the
        // window; it runs on a daemon thread now, the button greyed meanwhile, and the
        // browse / the offer come back to the EDT
        openEditor.setEnabled(false);
        Thread ping = new Thread(() -> {
            JoinCore.EditorPing answer = liveEditorPing(editorEndpointFile(modDir), nonce);
            String live = answer == null ? null : answer.base;
            SwingUtilities.invokeLater(() -> {
                openEditor.setEnabled(true);
                if (live != null) {
                    browseEditor(frame, live);
                } else {
                    offerLaunch(frame, launch, editorRequested);
                }
            });
        }, "cc-join-editor-ping");
        ping.setDaemon(true);
        ping.start();
    }

    /** The "Launch now / Not now" offer when no game answers (EDT). */
    static void offerLaunch(JFrame frame, JButton launch, AtomicBoolean editorRequested) {
        Object[] options = {"Launch now", "Not now"};
        int pick = JOptionPane.showOptionDialog(frame,
                "The editor runs inside the game this window launches, and none is answering yet.\n\n"
                + "Launch Starsector now with the settings in this window? This window stays open\n"
                + "and opens the editor in your browser as soon as the title screen is up.",
                "Open editor", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                options, options[0]);
        if (pick != 0) {
            return;
        }
        editorRequested.set(true);
        launch.doClick();
        if (frame.isDisplayable()) {
            // the Launch press stopped at one of its own dialogs (host/port/password) — the
            // window is still here, so the request must not leak into a later plain Launch.
            editorRequested.set(false);
        }
    }

    /** The face never shrinks below this: the purpose line + status + a wrapped hint all fit. */
    static final int COMPANION_MIN_W = 460;
    static final int COMPANION_MIN_H = 260;


    /**
     * Row (f) law 3: the launch face ALONE —
     * {@code --launched <modDir> [nonce]}. A REJOIN pass of join-cc.bat (the first pass's window
     * left when the game closed) starts one with the fresh nonce it minted for that pass;
     * the screenshot companion tool eye-grades it game-free. Same frame, same title, same face
     * the join window flips to on Launch.
     */
    private static int runLaunched(Path modDir, String nonce) throws Exception {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // default L&F is fine
        }
        CountDownLatch done = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame(WINDOW_TITLE);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    done.countDown();
                }
            });
            showLaunchFace(frame, editorEndpointFile(modDir), nonce, false);
        });
        done.await();
        return 0;
    }

    /**
     * Row (f) law 3 (EDT): the join window's LAUNCH FACE. The plan is handed over and the bat is
     * starting the game, so the form gives way to the status line (starting with a clock / at the
     * title screen / in a match), Open editor (enabled on the first live ping; {@code autoOpen}
     * browses ONCE, unasked — the player pressed Open editor → Launch now) and Close. Polls
     * {@code api/ping} through the endpoint file off the EDT, bound to THIS launch by {@code nonce}
     * (law 2: a foreign or nonce-less file keeps it waiting); leaves by itself when the game closes
     * after having answered, or never shows up ({@link #companionVerdict}).
     */
    static void showLaunchFace(JFrame frame, Path endpoint, String nonce, boolean autoOpen) {
        // Windows L&F gives JTextArea a monospaced font; every text block takes the label font
        Font labelFont = UIManager.getFont("Label.font");
        if (labelFont == null) {
            labelFont = new JLabel().getFont();
        }
        JTextArea purpose = wrappingText(launchFacePurpose(autoOpen), labelFont.deriveFont(Font.BOLD));
        JLabel status = new JLabel(companionWaitingLine(0L));
        status.setFont(labelFont);
        JButton open = new JButton("Open editor");
        open.setEnabled(false);
        open.setToolTipText("Build a custom variant in your browser (enabled once the title"
                + " screen is up)");
        JButton close = new JButton("Close");
        JTextArea hint = wrappingText(LAUNCH_FACE_HINT, labelFont);
        // the hint scrolls rather than truncates should the player shrink the window to the
        // minimum with a large system font
        JScrollPane hintScroll = new JScrollPane(hint,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        hintScroll.setBorder(null);
        hintScroll.setOpaque(false);
        hintScroll.getViewport().setOpaque(false);

        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        purpose.setAlignmentX(Component.LEFT_ALIGNMENT);
        purpose.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(purpose);
        head.add(Box.createVerticalStrut(8));
        head.add(status);
        JPanel body = new JPanel(new BorderLayout());
        body.setBorder(BorderFactory.createEmptyBorder(0, 12, 4, 12));
        body.add(hintScroll, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        buttons.add(open);
        buttons.add(close);

        AtomicReference<String> live = new AtomicReference<>();
        open.addActionListener(e -> {
            String url = live.get();
            if (url != null) {
                browseEditor(frame, url);
            }
        });
        close.addActionListener(e -> frame.dispose());

        // the flip: the form (or an empty new frame) gives way to the face
        Container content = frame.getContentPane();
        content.removeAll();
        content.setLayout(new BorderLayout());
        content.add(head, BorderLayout.NORTH);
        content.add(body, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        frame.setMinimumSize(new Dimension(COMPANION_MIN_W, COMPANION_MIN_H));
        frame.setSize(new Dimension(COMPANION_MIN_W + 60, COMPANION_MIN_H + 70));
        frame.setResizable(true);
        // bottom-right of the primary screen's usable area: out of the launcher's way, in
        // view when the game runs windowed, found by alt-tab when it runs full-screen.
        try {
            Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            frame.setLocation(usable.x + usable.width - frame.getWidth() - 24,
                    usable.y + usable.height - frame.getHeight() - 24);
        } catch (RuntimeException headless) {
            frame.setLocationRelativeTo(null);
        }
        frame.revalidate();
        frame.repaint();
        JoinWindowDpi.arm(frame, false);   // DPI relayout (fixed minimum — the text areas wrap); idempotent on the flip
        frame.setVisible(true);

        Thread poller = new Thread(() -> {
            int seenLive = 0;
            int misses = 0;
            boolean opened = false;
            long started = System.currentTimeMillis();
            while (true) {
                final EditorPing ping = liveEditorPing(endpoint, nonce);
                final String url = ping == null ? null : ping.base;
                final boolean isLive = url != null;
                if (isLive) {
                    seenLive++;
                    misses = 0;
                } else if (seenLive > 0) {
                    misses++;
                }
                final long elapsed = System.currentTimeMillis() - started;
                final String verdict = companionVerdict(isLive, seenLive, misses, elapsed);
                final boolean browseNow = isLive && autoOpen && !opened;
                if (browseNow) {
                    opened = true;
                }
                final boolean hadLive = seenLive > 0;
                // the live line reads the ping body the poll already fetched: the game's
                // state (title / match / campaign) + whether a Submit has a session to land in
                final String line = isLive
                        ? companionStatusLine(pingField(ping.body, "state"),
                                pingField(ping.body, "session"))
                        : hadLive ? "Starsector: not answering (this window closes if it"
                                + " stays quiet)..."
                        : companionWaitingLine(elapsed);
                final boolean firstLive = isLive && seenLive == 1;
                SwingUtilities.invokeLater(() -> {
                    live.set(url);
                    open.setEnabled(isLive);
                    if (firstLive) {
                        purpose.setText(LAUNCH_FACE_LIVE);
                    }
                    status.setText(line);
                    if (browseNow) {
                        browseEditor(frame, url);
                    }
                    if ("exit".equals(verdict)) {
                        frame.dispose();
                    }
                });
                if ("exit".equals(verdict)) {
                    return;
                }
                try {
                    Thread.sleep(isLive ? COMPANION_POLL_LIVE_MS : COMPANION_POLL_WAITING_MS);
                } catch (InterruptedException stop) {
                    return;
                }
            }
        }, "cc-editor-face-poll");
        poller.setDaemon(true);
        poller.start();
    }


    /** A non-editable, wrapping, transparent text block in the given font (the companion's copy). */
    private static JTextArea wrappingText(String text, Font font) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setBorder(null);
        area.setFont(font);
        return area;
    }


    private static JPanel row(String label, JTextField field) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel jLabel = new JLabel(label);
        jLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(jLabel);
        panel.add(JoinWindowDpi.heightToPreferred(field));   // Live height cap, not a pack-time pin
        return panel;
    }

    private static Component leftAlign(JLabel label) {
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }
}
