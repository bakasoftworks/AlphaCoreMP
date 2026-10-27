package cc.starsector.client.join;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.JTextComponent;

import cc.starsector.client.net.ClientListingReader;
import cc.starsector.client.net.ClientVariantReceipt;
import cc.starsector.client.net.ClientVariantReceiptReader;
import cc.starsector.client.net.DoorProbe;
import cc.starsector.client.net.DoorVariantCheck;
import cc.starsector.client.net.VariantRejectHints;
import cc.starsector.server.runtime.DoorRejectReasons;
import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.runtime.DatagramFrameCodec;
import cc.starsector.server.runtime.HandshakeCodec;

import static cc.starsector.client.join.JoinCore.*;
import static cc.starsector.client.join.JoinGui.WINDOW_TITLE;
import static cc.starsector.client.join.JoinGuiSelftest.*;

/**
 * The seven file-system batteries of the {@code JoinGui --selftest} run (launch gates, the lazy and
 * digest-once mod stage, current build, local customs, voice, ship pick): each one takes the run's temp
 * root ({@code Path tmp}). {@link JoinGuiSelftest#run()} calls them at the same points and in the same
 * order as before the carve; the shared helpers ({@code check}, the canned frames, the goldens) stay in
 * {@link JoinGuiSelftest} and are reached through its static import.
 *
 * <p>WHY a class of its own: {@code JoinGuiSelftest} sat at its line tier (1407 nb against WarnNb 1460)
 * and the next join-law rows land there, so these batteries moved
 * out first. Provenance: carved verbatim from {@code JoinGuiSelftest}
 * (proof = a verbatim-carve check + an ordered per-method diff); the only edited moved lines
 * are the seven declarations, which lost {@code private}. Pure test code, never on a sim or wire path.
 *
 * <p>Carve — the method bodies are main-tree code moved verbatim
 */
final class JoinGuiSelftestFs {

    private JoinGuiSelftestFs() {
    }

    /** GUI v2 step 3: the press-time law BOTH windows call — the gates in v1 order with v1
     *  words (pure on a fake kit folder), then the hand-off's bytes (the plan golden, canonical match.txt,
     *  the current build, the settings) in a temp root. */
    static void selftestLaunchGates(Path tmp) throws Exception {
        Path root = tmp.resolve("gates");
        Path install = root.resolve("install");
        Path modDir = install.resolve("mods").resolve("cc");
        Files.createDirectories(modDir);
        Path plan = root.resolve("plan.txt");
        Path settings = root.resolve("join-settings.properties");
        JoinLaunchGates.Verdict v;
        JoinLaunchGates.ProbeOutcome none = JoinLaunchGates.ProbeOutcome.NONE;
        v = JoinLaunchGates.check(new JoinLaunchGates.Form("", "7777", "", "", "", "", true), modDir, none);
        check(v.ready == null && v.refusal != null && "address".equals(v.refusal.gate)
                && JoinLaunchGates.TITLE_NOT_READY.equals(v.refusal.title) && !v.refusal.error
                && v.refusal.message.equals(JoinLaunchPlan.validate("", "7777")),
                "gates: empty host = the address gate, v1 title + text");
        v = JoinLaunchGates.check(new JoinLaunchGates.Form("1.2.3.4", "99999", "", "", "", "", true), modDir, none);
        check(v.refusal != null && "address".equals(v.refusal.gate), "gates: port out of range = the address gate");
        v = JoinLaunchGates.check(new JoinLaunchGates.Form("1.2.3.4", "7777", "", "bad pw!", "", "", true), modDir, none);
        check(v.refusal != null && "password".equals(v.refusal.gate)
                && JoinLaunchGates.TITLE_PASSWORD.equals(v.refusal.title)
                && v.refusal.message.equals(JoinLaunchPlan.validatePassword("bad pw!")), "gates: password charset");
        v = JoinLaunchGates.check(new JoinLaunchGates.Form("1.2.3.4", "7777", "", "ok-1pass", "bad ship!", "", true),
                modDir, none);
        check(v.refusal != null && "ship".equals(v.refusal.gate) && JoinLaunchGates.TITLE_SHIP.equals(v.refusal.title),
                "gates: ship charset");
        // The match gate. A staged document runs the window's receive law
        // (OUR reason + field, never its text); with none staged the LAST probe for the launch's pair decides, and the
        // kit's match.txt (garbage here) is never read.
        v = JoinLaunchGates.check(new JoinLaunchGates.Form("1.2.3.4", "7777", "", "ok-1pass", "", "garbage", true),
                modDir, none);
        check(v.refusal != null && "document".equals(v.refusal.gate)
                && JoinLaunchGates.TITLE_MATCH.equals(v.refusal.title)
                && v.refusal.message.startsWith("This server's match was refused ("
                        + cc.starsector.server.match.MatchDocumentLaws.REASON_MALFORMED + " ")
                && !v.refusal.message.contains("garbage"),
                "gates: a garbled staged document = the document gate, OUR reason only (flips if the codec's message"
                        + " or the text is echoed)");
        v = JoinLaunchGates.check(new JoinLaunchGates.Form("1.2.3.4", "7777", "", "ok-1pass", "", SELFTEST_DESCRIPTOR,
                true), modDir, none);
        check(v.refusal != null && "document".equals(v.refusal.gate) && v.refusal.message.contains("seat.0.token"),
                "gates: a staged document with live seat tokens is refused (flips if the window stops requiring the"
                        + " strict redaction)");
        Files.write(modDir.resolve("match.txt"), "not a descriptor".getBytes(StandardCharsets.US_ASCII));
        JoinLaunchGates.Form plain = new JoinLaunchGates.Form("1.2.3.4", "7777", "Pilot", "pw-1pass", "", "", true);
        v = JoinLaunchGates.check(plain, modDir, none);
        check(v.refusal != null && "probe".equals(v.refusal.gate) && JoinLaunchGates.TITLE_MATCH.equals(v.refusal.title)
                && JoinLaunchGates.MSG_NO_ANSWER.equals(v.refusal.message),
                "gates: nothing staged + no probe = the probe gate, the no-answer line (flips if check falls back to"
                        + " the kit's match.txt)");
        v = JoinLaunchGates.check(plain, modDir, outcome(JoinLaunchGates.ProbeOutcome.Kind.NO_REPLY, "1.2.3.4", 7777,
                false, ""));
        check(v.refusal != null && "probe".equals(v.refusal.gate) && JoinLaunchGates.MSG_NO_ANSWER.equals(v.refusal.message),
                "gates: an unanswered probe = the no-answer line (flips if no reply launches)");
        v = JoinLaunchGates.check(plain, modDir, outcome(JoinLaunchGates.ProbeOutcome.Kind.REJECTED, "1.2.3.4", 7777,
                false, ""));
        check(v.refusal != null && "door".equals(v.refusal.gate) && "probe-line 1.2.3.4".equals(v.refusal.message),
                "gates: a door refusal = that probe's own line again (flips if a refused probe launches or reads a"
                        + " generic line)");
        v = JoinLaunchGates.check(plain, modDir, outcome(JoinLaunchGates.ProbeOutcome.Kind.SERVED, "5.6.7.8", 7777,
                true, ""));
        check(v.refusal != null && "probe".equals(v.refusal.gate),
                "gates: a served probe of ANOTHER pair hands over nothing (flips if the outcome is not keyed)");
        v = JoinLaunchGates.check(plain, modDir, outcome(JoinLaunchGates.ProbeOutcome.Kind.SERVED, "1.2.3.4", 7777,
                false, ""));
        check(v.refusal != null && "document".equals(v.refusal.gate)
                && JoinLaunchGates.MSG_DOC_MISSING.equals(v.refusal.message),
                "gates: served without a usable match = the missing-match line (flips if a stale cache launches)");
        v = JoinLaunchGates.check(plain, modDir, outcome(JoinLaunchGates.ProbeOutcome.Kind.SERVED, "1.2.3.4", 7777,
                false, JoinLaunchGates.refusedLine("match_doc_out_of_range map.halfWidth")));
        check(v.refusal != null && "document".equals(v.refusal.gate)
                && v.refusal.message.contains("(match_doc_out_of_range map.halfWidth)"),
                "gates: served with a refused match = the recorded refusal line (flips if the line is dropped)");
        v = JoinLaunchGates.check(plain, modDir, outcome(JoinLaunchGates.ProbeOutcome.Kind.SERVED, "1.2.3.4", 7777,
                true, ""));
        check(v.refusal == null && v.ready != null && "1.2.3.4".equals(v.ready.host) && "7777".equals(v.ready.port)
                && "pw-1pass".equals(v.ready.password) && "".equals(v.ready.ship) && v.ready.pasted == null,
                "gates: served with the cache current = ready, nothing to write (flips if a current cache is refused)");
        // A served docOk that rested on a STAGE (not
        // the cache) is honoured only while the Form still carries the document; with the stage gone (a brick, an
        // emptied box, another pair's probe cleared it without re-recording the outcome) the Launch is refused
        // instead of booting whatever match.txt is on disk
        JoinLaunchGates.ProbeOutcome onStage = new JoinLaunchGates.ProbeOutcome(JoinLaunchGates.ProbeOutcome.Kind.SERVED,
                "1.2.3.4", 7777, "probe-line", true, "Match fetched from the server: 1v1, 2 seats.", false);
        v = JoinLaunchGates.check(plain, modDir, onStage);
        check(v.refusal != null && "document".equals(v.refusal.gate) && JoinLaunchGates.TITLE_MATCH.equals(v.refusal.title)
                && JoinLaunchGates.MSG_STAGE_GONE.equals(v.refusal.message) && !v.refusal.error,
                "gates F1: served on a stage the Form no longer carries = the document gate, the stage-gone line (flips"
                        + " if a frozen docOk launches with nothing to write)");
        v = JoinLaunchGates.check(new JoinLaunchGates.Form("1.2.3.4", "7777", "", "pw-1pass", "", lawfulDocument(), true),
                modDir, onStage);
        check(v.refusal == null && v.ready.pasted != null && "1v1-selftest0000000".equals(v.ready.pasted.matchId()),
                "gates F1: the same outcome with the stage still carried = ready, the document written (flips if the"
                        + " stage-gone refusal fires on a held stage)");
        v = JoinLaunchGates.check(new JoinLaunchGates.Form(" 1.2.3.4 ", " 7777 ", " \"My Pilot\" ", " \"pw-1pass\" ",
                "(server default: wolf_Assault)", lawfulDocument().replace("\n", "\r\n") + "\r\n\r\n", false),
                modDir, none);
        check(v.refusal == null && "1.2.3.4".equals(v.ready.host) && "7777".equals(v.ready.port)
                && " \"My Pilot\" ".equals(v.ready.name) && "pw-1pass".equals(v.ready.password) && "".equals(v.ready.ship)
                && v.ready.pasted != null && "1v1-selftest0000000".equals(v.ready.pasted.matchId()),
                "gates: trims + quote-strips, the server-default label = empty pick, a CRLF staged document parses");
        // the hand-off: plan golden, canonical match.txt, settings, no .tmp, an empty pick CLEARS a staged build
        CurrentBuildFile.write(install, "conquest_broadside");   // The 9/18 editor FLY the owner picked against
        Path master = settings.resolveSibling(JoinSettings.CID_MASTER_FILE);
        JoinLaunchGates.Handoff h = JoinLaunchGates.commit(v.ready, modDir, plan, settings, "hound_Standard",
                false, null);
        String cidA = h.cid;
        check(h.refusal == null && "hound_Standard".equals(h.resolvedShip) && cidA != null && cidA.matches("[0-9a-f]{32}")
                && h.warning == null && Files.isRegularFile(master)
                && cidA.equals(JoinSettings.hostCid(master, "1.2.3.4", 7777, false)),
                "gates: commit ok - an empty pick resolves to the served default, the cid derived for this host from"
                        + " the master beside the settings (flips if commit mints a free id or keeps the master elsewhere)");
        String planText = new String(Files.readAllBytes(plan), StandardCharsets.US_ASCII);
        check(planText.equals("host=1.2.3.4\r\nport=7777\r\nname=My Pilot\r\npassword=pw-1pass\r\ncid=" + h.cid
                + "\r\nlogs=off\r\nship=hound_Standard\r\n"),
                "gates: plan bytes = the planFileContent golden (CRLF rows, sanitized name, cid rides with a password)");
        check(!Files.exists(planTmp(plan)), "gates: no .tmp beside the plan");
        String matchTxt = new String(Files.readAllBytes(modDir.resolve("match.txt")), StandardCharsets.US_ASCII);
        check(matchTxt.equals(JoinLaunchPlan.canonicalText(v.ready.pasted)) && !matchTxt.contains("\r")
                && matchTxt.contains("seat.0.token=REDACTED-seat1"),
                "gates: the staged document lands as canonical match.txt through the one writer (LF; flips if the"
                        + " write is skipped or not canonical)");
        check("".equals(CurrentBuildFile.read(install)) && !Files.exists(CurrentBuildFile.file(install)),
                "gates: an empty (server-default) pick CLEARS the current build");
        JoinSettings back = JoinSettings.load(settings);
        check("1.2.3.4".equals(back.host) && "7777".equals(back.port) && "My Pilot".equals(back.name)
                && "pw-1pass".equals(back.password) && back.cid.isEmpty() && !back.logs && "".equals(back.ship),
                "gates: settings persisted (sanitized name, no cid row, logs off, server-default pick = empty)");
        check("1.2.3.4:7777".equals(back.passwordFor) && "pw-1pass".equals(back.rememberedPasswordFor("1.2.3.4", "7777")),
                "binding R5: the gates save binds the password to the launched host:port (flips if it drops passwordFor)");
        // a real pick rides the plan + becomes the current build; the cid is THIS host's; a voice failure only warns
        v = JoinLaunchGates.check(new JoinLaunchGates.Form("h.example", "1", "", "pw-1pass", "wolf_Assault", "", true),
                modDir, outcome(JoinLaunchGates.ProbeOutcome.Kind.SERVED, "H.example", 1, true, ""));
        check(v.refusal == null && v.ready.pasted == null,
                "gates: a served probe with the cache current, host case-blind = ready (flips if the pair compare is"
                        + " case-sensitive)");
        h = JoinLaunchGates.commit(v.ready, modDir, plan, settings, null, true,
                () -> {
                    throw new IOException("mic gone");
                });
        String cidH = JoinSettings.hostCid(master, "h.example", 1, false);
        check(h.refusal == null && "wolf_Assault".equals(h.resolvedShip) && cidH != null && cidH.equals(h.cid)
                && !cidH.equals(cidA) && h.warning != null
                && h.warning.startsWith("Voice settings not saved: ") && h.warning.endsWith(" - launching anyway"),
                "gates: the pick rides, the cid is this host's (another host's differs), a voice failure is a warning"
                        + " not a refusal (flips if one id rides to every host)");
        check(new String(Files.readAllBytes(plan), StandardCharsets.US_ASCII).equals("host=h.example\r\nport=1\r\n"
                + "name=\r\npassword=pw-1pass\r\ncid=" + cidH + "\r\nlogs=on\r\nship=wolf_Assault\r\n"),
                "gates: plan golden with a pick + the host's cid");
        check("wolf_Assault".equals(CurrentBuildFile.read(install)), "gates: the pick is the current build");
        // no password: the cid row stays empty and nothing is minted (the pre-password JTO stays byte-identical)
        Path noPw = root.resolve("nopw").resolve("join-settings.properties");
        v = JoinLaunchGates.check(new JoinLaunchGates.Form("h", "1", "", "", "", "", true), modDir,
                outcome(JoinLaunchGates.ProbeOutcome.Kind.SERVED, "h", 1, true, ""));
        h = JoinLaunchGates.commit(v.ready, modDir, plan, noPw, null, true, null);
        check(h.refusal == null && "".equals(h.cid) && new String(Files.readAllBytes(plan), StandardCharsets.US_ASCII)
                .equals("host=h\r\nport=1\r\nname=\r\npassword=\r\ncid=\r\nlogs=on\r\nship=\r\n")
                && !Files.exists(noPw.resolveSibling(JoinSettings.CID_MASTER_FILE)),
                "gates: a blank password suppresses the cid row and mints nothing (flips if a password-less launch"
                        + " mints the master)");
        // an unwritable plan = the v1 "Launch failed" (error) refusal
        h = JoinLaunchGates.commit(v.ready, modDir, root.resolve("nodir").resolve("x").resolve("plan.txt"), settings,
                null, true, null);
        check(h.refusal != null && "plan_write".equals(h.refusal.gate)
                && JoinLaunchGates.TITLE_LAUNCH_FAILED.equals(h.refusal.title) && h.refusal.error,
                "gates: an unwritable plan = the Launch failed refusal (error severity)");
        // R5b: an EMPTY-password launch to an unbound pair keeps the remembered
        // slot - its password AND its pair; seeded both ways: the escaped on-disk row, and a legacy file (no row)
        Path keep = root.resolve("keep-settings.properties");
        for (String row : new String[] {"passwordFor=1.2.3.4\\:7777\n", ""}) {
            Files.write(keep, ("host=1.2.3.4\nport=7777\npassword=keep-me-1\n" + row).getBytes(StandardCharsets.US_ASCII));
            v = JoinLaunchGates.check(new JoinLaunchGates.Form("5.6.7.8", "7777", "", "", "", "", true), modDir,
                    outcome(JoinLaunchGates.ProbeOutcome.Kind.SERVED, "5.6.7.8", 7777, true, ""));
            h = JoinLaunchGates.commit(v.ready, modDir, plan, keep, null, true, null);
            JoinSettings kept = JoinSettings.load(keep);
            check(h.refusal == null && "5.6.7.8".equals(kept.host) && "keep-me-1".equals(kept.password)
                    && "1.2.3.4:7777".equals(kept.passwordFor) && kept.rememberedPasswordFor("5.6.7.8", "7777").isEmpty()
                    && "keep-me-1".equals(kept.rememberedPasswordFor("1.2.3.4", "7777"))
                    && new String(Files.readAllBytes(plan), StandardCharsets.US_ASCII).contains("\r\npassword=\r\ncid=\r\n"),
                    "binding R5b: an empty launch to an unbound pair keeps the slot (" + (row.isEmpty() ? "legacy" : "bound")
                            + " seed; flips if the save wipes the slot or re-binds it to the new pair)");
        }
        // R5c: a malformed backslash-u escape makes Properties.load throw
        // IllegalArgumentException - the window opens on blanks instead of dying at load
        Path bad = root.resolve("bad-escape-settings.properties");
        Files.write(bad, "host=\\uZZZZ\nport=7777\npassword=never-read-1\n".getBytes(StandardCharsets.US_ASCII));
        JoinSettings blank;
        try { blank = JoinSettings.load(bad); } catch (RuntimeException e) { blank = null; }
        check(blank != null && blank.rememberedPasswordFor("1.2.3.4", "7777").isEmpty(),
                "binding R5c: a settings file with a malformed unicode escape loads without throwing (flips if load"
                        + " lets IllegalArgumentException out - the window would not open)");
        // The settings file moves to the LOCAL profile once. M1: an
        // old-path file is moved and read back; M2: a present new file is never overwritten; M3: a failed move reads
        // the old file in place; M4: the base order LOCALAPPDATA (absolute) > APPDATA > user.home.
        Path oldFile = root.resolve("roaming").resolve(JoinSettings.DIR_NAME).resolve(JoinSettings.FILE_NAME);
        Path newFile = root.resolve("local").resolve(JoinSettings.DIR_NAME).resolve(JoinSettings.FILE_NAME);
        new JoinSettings("m.example", "7777", "Mig Pilot", "mig-pass-1", "0123456789abcdef0123456789abcdef", true, "",
                "m.example:7777").save(oldFile);
        Path read = JoinSettings.migrateLegacy(newFile, oldFile);
        JoinSettings moved = JoinSettings.load(read);
        check(newFile.equals(read) && !Files.exists(oldFile) && "Mig Pilot".equals(moved.name)
                && "mig-pass-1".equals(moved.rememberedPasswordFor("m.example", "7777")),
                "settings M1: the old roaming file is moved to the local path and read back - name, binding (flips"
                        + " if the migration is skipped or copies a blank file)");
        new JoinSettings("old.example", "1", "Old Pilot", "", "", true, "", "").save(oldFile);
        byte[] before = Files.readAllBytes(newFile);
        check(newFile.equals(JoinSettings.migrateLegacy(newFile, oldFile))
                && Arrays.equals(before, Files.readAllBytes(newFile)) && !Files.exists(oldFile),
                "settings M2: a present local file is never overwritten by an old one, and the leftover old file is"
                        + " deleted (flips if the move runs anyway, or if the roaming leftover - it holds the"
                        + " password and the cid - is kept)");
        new JoinSettings("old.example", "1", "Old Pilot", "", "", true, "", "").save(oldFile);   // M3's input
        Path blockedNew = root.resolve("blocked").resolve(JoinSettings.DIR_NAME).resolve(JoinSettings.FILE_NAME);
        Files.createDirectories(blockedNew.getParent().getParent());
        Files.write(blockedNew.getParent(), new byte[] {1});   // a FILE where the folder must go: the move fails
        Path fallback = JoinSettings.migrateLegacy(blockedNew, oldFile);
        check(oldFile.equals(fallback) && "Old Pilot".equals(JoinSettings.load(fallback).name),
                "settings M3: a failed move reads the old file in place (flips if a failure starts from an empty file -"
                        + " the seat-key cid would be lost)");
        check(Paths.get("D:\\Lad").equals(JoinSettings.settingsBase("D:\\Lad", "D:\\Roam", "D:\\Home"))
                && Paths.get("D:\\Roam").equals(JoinSettings.settingsBase("Lad", "D:\\Roam", "D:\\Home"))
                && Paths.get("D:\\Roam").equals(JoinSettings.settingsBase(" ", "D:\\Roam", "D:\\Home"))
                && Paths.get("D:\\Home").equals(JoinSettings.settingsBase(null, "", "D:\\Home")),
                "settings M4: an absolute LOCALAPPDATA wins, else APPDATA, else user.home (flips if the roaming"
                        + " profile is read first or a relative LOCALAPPDATA is trusted)");
        // M5: two windows on one first run both pass migrateLegacy's
        // Files.exists(current) check and the loser's move throws. The catch's decision (afterFailedMove) must read
        // the file the winner moved - never the vanished legacy path - and print nothing. stderr is captured for
        // the one call only.
        Path raceNew = root.resolve("race-local").resolve(JoinSettings.DIR_NAME).resolve(JoinSettings.FILE_NAME);
        Path raceOld = root.resolve("race-roaming").resolve(JoinSettings.DIR_NAME).resolve(JoinSettings.FILE_NAME);
        new JoinSettings("r.example", "7777", "Race Pilot", "", "fedcba9876543210fedcba9876543210", true, "", "")
                .save(raceNew);
        java.io.PrintStream realErr = System.err;
        java.io.ByteArrayOutputStream errBytes = new java.io.ByteArrayOutputStream();
        Path raceRead;
        System.setErr(new java.io.PrintStream(errBytes, true));
        try {
            raceRead = JoinSettings.afterFailedMove(raceNew, raceOld, new java.nio.file.NoSuchFileException("race"));
        } finally {
            System.setErr(realErr);
        }
        check(raceNew.equals(raceRead) && errBytes.size() == 0 && !Files.exists(raceOld)
                && "Race Pilot".equals(JoinSettings.load(raceRead).name),
                "settings M5: a lost move race reads the winner's moved file and prints nothing (flips if the catch"
                        + " returns the vanished legacy path - a blank form, the roaming file re-created at Launch - or"
                        + " prints the failure line first)");
        // R5d: a malformed backslash-u row BETWEEN the password and its
        // passwordFor row. Properties.load keeps the rows read before the bad line; without the catch's remove the
        // password would take the legacy binding to THIS file's host/port and forLaunch would re-save that.
        Path partial = root.resolve("partial-settings.properties");
        Files.write(partial, ("host=b.example\nport=7777\nname=Partial Pilot\npassword=partial-pw-1\nship=\\uZZZZ\n"
                + "passwordFor=a.example\\:7777\n").getBytes(StandardCharsets.US_ASCII));
        JoinSettings part = JoinSettings.load(partial);
        check(part.password.isEmpty() && part.rememberedPasswordFor("b.example", "7777").isEmpty()
                && "b.example".equals(part.host) && "7777".equals(part.port) && "Partial Pilot".equals(part.name),
                "binding R5d: a partially read settings file offers no password and keeps its earlier rows (flips if"
                        + " the load catch keeps the password - it then binds to the file's own host:port - or drops"
                        + " the rows read before the bad line)");
        // C1/C2: the per-host cid. C1: one host's id is the same on every
        // launch (a rejoin keeps its seat), the host compares case-blind, another port is another id, and the id
        // never lands in the settings file. C2: a master that cannot be minted (a non-empty folder where the file
        // goes) refuses the launch at the cid gate, naming no value.
        Path cidSettings = root.resolve("cid-settings.properties");
        v = JoinLaunchGates.check(new JoinLaunchGates.Form("c.example", "7777", "", "pw-1pass", "", "", true), modDir,
                outcome(JoinLaunchGates.ProbeOutcome.Kind.SERVED, "c.example", 7777, true, ""));
        h = JoinLaunchGates.commit(v.ready, modDir, plan, cidSettings, null, true, null);
        String cidC = h.cid;
        h = JoinLaunchGates.commit(v.ready, modDir, plan, cidSettings, null, true, null);
        check(v.refusal == null && h.refusal == null && cidC != null && cidC.matches("[0-9a-f]{32}") && cidC.equals(h.cid)
                && cidC.equals(JoinSettings.hostCid(master, "C.EXAMPLE", 7777, false))
                && !cidC.equals(JoinSettings.hostCid(master, "c.example", 7778, false))
                && new String(Files.readAllBytes(plan), StandardCharsets.US_ASCII).contains("\r\ncid=" + cidC + "\r\n")
                && JoinSettings.load(cidSettings).cid.isEmpty(),
                "cid C1: one host's id is stable across launches and never saved as a row (flips if commit mints per"
                        + " launch, derives from the raw host case, ignores the port, or persists the id)");
        Path blocked = root.resolve("cid-blocked").resolve(JoinSettings.FILE_NAME);
        Files.createDirectories(blocked.resolveSibling(JoinSettings.CID_MASTER_FILE).resolve("x"));
        h = JoinLaunchGates.commit(v.ready, modDir, plan, blocked, null, true, null);
        check(h.refusal != null && "cid".equals(h.refusal.gate)
                && JoinLaunchGates.TITLE_LAUNCH_FAILED.equals(h.refusal.title)
                && h.refusal.message.contains("seat identity") && !h.refusal.message.matches("(?s).*[0-9a-f]{32}.*"),
                "cid C2: an unmintable master refuses at the cid gate with no value (flips if the launch proceeds"
                        + " without the host's id or falls back to a throwaway mint)");
        // The ONE codec both trees share is UTF-8 — the file the game
        // boots is the text the window checked, byte for byte, with a non-ASCII side name (lawful: the text law admits
        // non-ASCII printables; the stock mint is ASCII, a host-authored document need not be). US-ASCII wrote "Caf?".
        String accented = "Caf" + (char) 0xE9;
        MatchDescriptor utf = JoinLaunchGates.lawfulDocument(lawfulDocument().replace("side.0.name=ISS",
                "side.0.name=" + accented));
        JoinLaunchGates.writeMatchText(modDir, utf);
        byte[] written = Files.readAllBytes(modDir.resolve("match.txt"));
        MatchDescriptor reread = JoinLaunchGates.lawfulDocument(new String(written, StandardCharsets.UTF_8));
        // the server's half (its preflight text = the shared codec's encode, UTF-8) is pinned in the server suite (F13);
        // the preflight class is server-only, never in this jar, so this row pins the kit's half on the same codec
        check(accented.equals(utf.sides().get(0).name()) && accented.equals(reread.sides().get(0).name())
                && Arrays.equals(written, JoinLaunchPlan.canonicalText(utf).getBytes(StandardCharsets.UTF_8))
                && !new String(written, StandardCharsets.UTF_8).contains("Caf?")
                && accented.equals(JoinLaunchPlan.parseDescriptor(JoinFingerprint.readCappedKitText(
                        modDir.resolve("match.txt"))).sides().get(0).name()),
                "gates S2-2: a side name with U+00E9 lands through the one writer as the shared codec's UTF-8 bytes and"
                        + " re-reads intact through the cached read (flips if canonicalBytes or readCappedKitText is not"
                        + " UTF-8)");
    }

    /** A recorded probe outcome for the gate rows; its status line is a fixture naming the pair, never peer text.
     *  {@code docOk} here = the cache-current case (these rows stage nothing). */
    private static JoinLaunchGates.ProbeOutcome outcome(JoinLaunchGates.ProbeOutcome.Kind kind, String host, int port,
            boolean docOk, String docLine) {
        return new JoinLaunchGates.ProbeOutcome(kind, host, port, "probe-line " + host, docOk, docLine, docOk);
    }

    /** The selftest descriptor with its seat tokens as the strict placeholders the window's receive law requires. */
    private static String lawfulDocument() {
        return SELFTEST_DESCRIPTOR
                .replace("seat.0.token=00112233445566778899aabbccddeeff", "seat.0.token=REDACTED-seat1")
                .replace("seat.1.token=ffeeddccbbaa99887766554433221100", "seat.1.token=REDACTED-seat2");
    }

    /** M-lazy: {@code scan()} is identity only — a folder declaring a jar that does not
     *  exist scans clean (no digest, no note), is irrelevant to a diff that does not publish it, and only
     *  {@code digest()} / a publishing {@code diff()} surfaces the unreadable jar.
     * */
    static void selftestModStageLazy(Path tmp) throws Exception {
        Path install = tmp.resolve("modlazy-install");
        Path modDir = install.resolve("mods").resolve("Lazy Unpublished");
        Files.createDirectories(modDir.resolve("data"));
        Files.writeString(modDir.resolve("mod_info.json"),
                "{\"id\":\"lazy_unpub\",\"version\":\"1.0\",\"jars\":[\"jars/absent.jar\"]}", StandardCharsets.UTF_8);
        Files.writeString(modDir.resolve("data").resolve("x.csv"), "a,b\n", StandardCharsets.UTF_8);
        JoinModStage.Scan scan = JoinModStage.scan(install);
        JoinModStage.LocalMod have = scan.byId.get("lazy_unpub");
        check(have != null && have.jarsMd5 == null && have.dataMd5 == null && have.note == null
                        && have.jars.equals(List.of("jars/absent.jar"))
                        && scan.notes.stream().noneMatch(n -> n.contains("unreadable")),
                "M-lazy: scan() is identity only - a declared-but-absent jar is not read (no digest, no note)");
        JoinModStage.Diff other = JoinModStage.diff(
                List.of(new DoorProbe.ModRow("lw_lazylib", "2.8", 7L)), 1, true, scan);
        check(other.rows.size() == 1 && "missing".equals(other.rows.get(0).status)
                        && other.rows.stream().noneMatch(r -> JoinModStage.STATUS_UNREADABLE.equals(r.status)),
                "M-lazy: an unpublished folder is irrelevant to diff() (no UNREADABLE row)");
        JoinModStage.LocalMod dug = JoinModStage.digest(have);
        check(dug.jarsMd5 == null && dug.note != null
                        && dug.note.equals("declared jar jars/absent.jar is missing or unreadable"),
                "M-lazy: digest() reads the declared jar and notes it missing");
        JoinModStage.Diff pub = JoinModStage.diff(
                List.of(new DoorProbe.ModRow("lazy_unpub", "1.0", 7L)), 1, true, scan);
        check(pub.rows.size() == 1 && JoinModStage.STATUS_UNREADABLE.equals(pub.rows.get(0).status),
                "M-lazy: a PUBLISHED id is digested inside diff() (the absent jar = an UNREADABLE row)");
    }

    /** A READABLE published mod
     *  diffs OK through OS junk ({@code .DS_Store} / {@code ._x} / {@code __MACOSX/} under data/ —
     *  the ModDataWalk skip list), an edited {@code a.csv} diffs {@code STATUS_DATA}, and two identical
     *  published rows cost ONE digest walk (the {@code diff()} per-id memo; counted through the
     *  package-private {@code JoinModStage.DIGEST_WALKS}). */
    static void selftestModStageDigestOnce(Path tmp) throws Exception {
        Path install = tmp.resolve("moddigest-install");
        Path modDir = install.resolve("mods").resolve("Readable Mod");
        Path data = modDir.resolve("data");
        Files.createDirectories(data);
        Files.createDirectories(modDir.resolve("jars"));
        Files.writeString(modDir.resolve("mod_info.json"),
                "{\"id\":\"readable_mod\",\"version\":\"1.0\",\"jars\":[\"jars/a.jar\"]}", StandardCharsets.UTF_8);
        Files.write(modDir.resolve("jars").resolve("a.jar"), new byte[] {0x50, 0x4b, 0x05, 0x06, 1, 2, 3});
        Files.writeString(data.resolve("a.csv"), "a,b\n1,2\n", StandardCharsets.UTF_8);
        // the publisher's view = the CLEAN copy's digest
        JoinModStage.LocalMod clean = JoinModStage.digest(JoinModStage.scan(install).byId.get("readable_mod"));
        check(clean.note == null && clean.jarsMd5 != null && clean.dataMd5 != null,
                "M-digest: a readable mod digests clean (jars + data/**)");
        long prefix = cc.starsector.server.runtime.ModSet.filesPrefix(clean.jarsMd5, clean.dataMd5);
        String label = cc.starsector.server.runtime.ModSet.versionLabel(clean.version);
        // a stranger's unzipped copy: OS junk under data/ never moves the digest
        Files.writeString(data.resolve(".DS_Store"), "finder", StandardCharsets.UTF_8);
        Files.writeString(data.resolve("._x"), "appledouble", StandardCharsets.UTF_8);
        Files.createDirectories(data.resolve("__MACOSX"));
        Files.writeString(data.resolve("__MACOSX").resolve("y.csv"), "junk\n", StandardCharsets.UTF_8);
        JoinModStage.Scan scan = JoinModStage.scan(install);
        JoinModStage.Diff ok = JoinModStage.diff(
                List.of(new DoorProbe.ModRow("readable_mod", label, prefix)), 1, true, scan);
        check(ok.ok && ok.rows.size() == 1 && JoinModStage.STATUS_OK.equals(ok.rows.get(0).status),
                "M-digest: .DS_Store / ._x / __MACOSX under data/ diff OK (OS junk skipped)");
        // two identical published rows (duplicate frames) = ONE walk
        int before = JoinModStage.DIGEST_WALKS.get();
        JoinModStage.Diff dup = JoinModStage.diff(List.of(new DoorProbe.ModRow("readable_mod", label, prefix),
                new DoorProbe.ModRow("readable_mod", label, prefix)), 1, true, scan);
        int walks = JoinModStage.DIGEST_WALKS.get() - before;
        check(walks == 1 && dup.rows.size() == 2
                        && dup.rows.stream().allMatch(r -> JoinModStage.STATUS_OK.equals(r.status)),
                "M-digest: two identical published rows cost ONE digest walk (got " + walks + ")");
        // an edited data file: jars agree, data/** does not
        Files.writeString(data.resolve("a.csv"), "a,b\n1,3\n", StandardCharsets.UTF_8);
        JoinModStage.Diff edited = JoinModStage.diff(
                List.of(new DoorProbe.ModRow("readable_mod", label, prefix)), 1, true, JoinModStage.scan(install));
        check(edited.rows.size() == 1 && JoinModStage.STATUS_DATA.equals(edited.rows.get(0).status),
                "M-digest: an edited a.csv diffs STATUS_DATA");
    }

    /** The current-build file law — parse (first line, id-law gated), write (temp+rename,
     *  no .tmp left), read; a lawless stem is refused before any file access. */
    static void selftestCurrentBuild(Path tmp) throws Exception {
        Path install = tmp.resolve("install-current");
        check(CurrentBuildFile.read(install).isEmpty(), "current build: no current.data -> none");
        // The current build is consumed only by an editor-hosting launch
        check("brawler_x".equals(cc.starsector.client.net.CurrentBuildLaw.pick("brawler_x", "", true)),
                "current build pick: editor hosted -> the current build wins");
        check("wolf_A".equals(cc.starsector.client.net.CurrentBuildLaw.pick("", "wolf_A", true)),
                "current build pick: editor hosted, none current -> the launch pick");
        check("".equals(cc.starsector.client.net.CurrentBuildLaw.pick("brawler_x", "", false)),
                "current build pick: editor OFF -> the current build is NOT consumed (server default)");
        check("wolf_A".equals(cc.starsector.client.net.CurrentBuildLaw.pick("brawler_x", "wolf_A", false)),
                "current build pick: editor OFF -> the launch pick, never the current build");
        check("".equals(cc.starsector.client.net.CurrentBuildLaw.pick(null, null, true)),
                "current build pick: nulls -> server default");
        check("wolf_abc-1".equals(cc.starsector.client.net.CurrentBuildLaw.parse("wolf_abc-1\r\n")),
                "current build: parse takes the first trimmed line");
        check("".equals(cc.starsector.client.net.CurrentBuildLaw.parse("../evil"))
                && "".equals(cc.starsector.client.net.CurrentBuildLaw.parse(""))
                && "".equals(cc.starsector.client.net.CurrentBuildLaw.parse("a b")),
                "current build: a lawless / empty line -> none");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            big.append('x');
        }
        check("".equals(cc.starsector.client.net.CurrentBuildLaw.parse(big.toString())), "current build: oversize -> none");
        CurrentBuildFile.write(install, "onslaught_xiv_kgi7tygk7");
        check("onslaught_xiv_kgi7tygk7".equals(CurrentBuildFile.read(install)), "current build: write then read round-trips");
        check(!Files.exists(CurrentBuildFile.file(install).resolveSibling("current.data.tmp")), "current build: no .tmp left behind");
        CurrentBuildFile.write(install, "wolf_x");
        check("wolf_x".equals(CurrentBuildFile.read(install)), "current build: a second write replaces");
        boolean refused = false;
        try {
            CurrentBuildFile.write(install, "../evil");
        } catch (IOException expected) {
            refused = true;
        }
        check(refused && "wolf_x".equals(CurrentBuildFile.read(install)), "current build: a lawless stem is refused, the file stays");
        Files.writeString(CurrentBuildFile.file(install), "no spaces allowed\n", StandardCharsets.UTF_8);
        check(CurrentBuildFile.read(install).isEmpty(), "current build: a lawless file reads as none");
    }

    /** W15 v0: the local custom-variant listing law (stem law, self-naming, caps). */
    static void selftestLocalCustoms(Path tmp) throws Exception {
        Path install = tmp.resolve("install");
        check(localCustomIds(install).isEmpty(), "no cc_variants folder -> empty list");
        Path dir = install.resolve("saves").resolve("common").resolve("cc_variants");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("my_aurora.variant.data"),
                "{\"variantId\": \"my_aurora\", \"hullId\":\"aurora\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("zeta-2.variant.data"),
                "{\"hullId\":\"wolf\",\"variantId\":\"zeta-2\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("mismatch.variant.data"),
                "{\"variantId\":\"other\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("-lead.variant.data"),
                "{\"variantId\":\"-lead\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("a..b.variant.data"),
                "{\"variantId\":\"a..b\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("notes.txt"), "{\"variantId\":\"notes\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("plain.variant"), "{\"variantId\":\"plain\"}", StandardCharsets.UTF_8);
        // W15 v1: the displayName law rows + the per-file status notes
        Files.writeString(dir.resolve("good.variant.data"),
                "{\"variantId\":\"good\",\"hullId\":\"wolf\",\"displayName\": \"Good Ship\"}",
                StandardCharsets.UTF_8);
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 49; i++) {
            tooLong.append('n');
        }
        Files.writeString(dir.resolve("longname.variant.data"),
                "{\"variantId\":\"longname\",\"displayName\":\"" + tooLong + "\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("esc.variant.data"),
                "{\"variantId\":\"esc\",\"displayName\":\"Say \\\"hi\\\"\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("nonascii.variant.data"),
                "{\"variantId\":\"nonascii\",\"displayName\":\"Caf" + (char) 0xe9 + "\"}",
                StandardCharsets.UTF_8);
        // JSON escapes decode before grading (an escaped non-ASCII / control
        // char is refused in-game exactly like its raw twin)
        Files.writeString(dir.resolve("uesc.variant.data"),
                "{\"variantId\":\"uesc\",\"displayName\":\"Caf\\" + "u00e9\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("nl.variant.data"),
                "{\"variantId\":\"nl\",\"displayName\":\"two\\nlines\"}", StandardCharsets.UTF_8);
        LocalCustoms report = localCustomReport(install);
        check(Arrays.asList("esc", "good", "longname", "my_aurora", "nl", "nonascii", "uesc", "zeta-2")
                .equals(report.ids) && report.ids.equals(localCustomIds(install)),
                "local customs: self-naming lawful stems only, sorted (got " + report.ids + ")");
        String notes = String.join("\n", report.notes);
        check(report.notes.size() == 10, "local customs: 10 status notes (got " + report.notes + ")");
        check(notes.contains("uesc: \"displayName\" must be 1-48")
                && notes.contains("nl: \"displayName\" must be 1-48"),
                "local customs: escaped non-ASCII / newline displayName graded like raw");
        check("Say \"hi\"".equals(decodeJsonString("Say \\\"hi\\\""))
                && "A/B".equals(decodeJsonString("A\\/B"))
                && decodeJsonString("bad\\x") == null && decodeJsonString("trail\\") == null
                && decodeJsonString("short\\u00") == null,
                "json string decode: RFC escapes decode, malformed -> null");
        check(notes.contains("plain.variant: wrong suffix - the game reads plain.variant.data"),
                "local customs: bare .variant gets the rename note");
        check(notes.contains("mismatch.variant.data: its \"variantId\" must be \"mismatch\""),
                "local customs: variantId/stem mismatch note");
        check(notes.contains("-lead.variant.data: the file name must be")
                && notes.contains("a..b.variant.data: the file name must be"),
                "local customs: unlawful stems get the rename note");
        check(notes.contains("my_aurora: add \"displayName\"")
                && notes.contains("zeta-2: add \"displayName\""),
                "local customs: missing displayName note (id still offered)");
        check(notes.contains("longname: \"displayName\" must be 1-48")
                && notes.contains("nonascii: \"displayName\" must be 1-48"),
                "local customs: displayName law note for >48 / non-ASCII");
        check(report.notes.stream().noneMatch(n -> n.startsWith("good:") || n.startsWith("esc:")
                || n.contains("notes.txt")),
                "local customs: lawful / escaped displayName + a non-variant file get no note");
        check(displayNameNote("x", "{\"displayName\":\"\"}") != null
                && displayNameNote("x", "{\"displayName\":\"   \"}") != null
                && displayNameNote("x", "{\"displayName\":\"ok\"}") == null,
                "displayName law: blank refused, plain accepted");
        // W15 iteration 6 row (e): the published index — the editor's own order kept for the
        // files that still exist, a vanished / unlawful stem pruned, the foreign files appended in name order,
        // the oracle's exact {"stems":[…]} shape, no rewrite when nothing changed
        Path index = dir.resolve("index.data");
        Files.writeString(index, "{\"stems\":[\"zeta-2\",\"gone\",\"good\",\"-lead\",\"zeta-2\"]}",
                StandardCharsets.UTF_8);
        check(publishVariantIndex(install, report), "index publish: a changed list is written");
        String published = Files.readString(index, StandardCharsets.UTF_8);
        check(published.equals("{\"stems\":[\"zeta-2\",\"good\",\"esc\",\"longname\",\"my_aurora\",\"nl\","
                + "\"nonascii\",\"uesc\"]}"),
                "index publish: editor order kept, gone/unlawful pruned, foreign appended (got " + published + ")");
        check(!publishVariantIndex(install, report)
                && published.equals(Files.readString(index, StandardCharsets.UTF_8)),
                "index publish: an unchanged list is not rewritten");
        Files.writeString(index, "not json at all", StandardCharsets.UTF_8);
        check(publishVariantIndex(install, report) && Files.readString(index, StandardCharsets.UTF_8)
                .equals("{\"stems\":[\"esc\",\"good\",\"longname\",\"my_aurora\",\"nl\",\"nonascii\",\"uesc\","
                        + "\"zeta-2\"]}"),
                "index publish: an unreadable index is rebuilt from the scan");
        // iteration 9 (d) (owner #4): the server-builds carrier — lawful ids only, the default gated too, no secret
        Path sb = dir.resolve("server-builds.data");
        check(JoinCore.publishServerBuilds(install, java.util.Arrays.asList("aurora_Heavyblaster", "../evil", "a b", "wolf_Assault"),
                "aurora_Heavyblaster", 1234L) && Files.readString(sb, StandardCharsets.UTF_8)
                .equals("{\"ids\":[\"aurora_Heavyblaster\",\"wolf_Assault\"],\"defaultId\":\"aurora_Heavyblaster\",\"stamp\":1234}"),
                "server builds publish: lawless ids dropped, the exact shape (got " + Files.readString(sb, StandardCharsets.UTF_8) + ")");
        check(JoinCore.publishServerBuilds(install, java.util.Collections.<String>emptyList(), "../x", -5L)
                && Files.readString(sb, StandardCharsets.UTF_8).equals("{\"ids\":[],\"defaultId\":\"\",\"stamp\":0}"),
                "server builds publish: an empty list + a lawless default + a negative stamp land as empties");
        check(!publishVariantIndex(tmp.resolve("nowhere"), report), "index publish: no folder -> nothing written");
    }

    /** W12 VOICE L5: the Voice box's pure helpers (JoinVoiceSettings). */
    static void selftestVoice(Path tmp) throws Exception {
        check("Blue Yeti USB".equals(JoinVoiceSettings.asciiDeviceName("Blue Yeti USB")),
                "voice: ascii device name passes through");
        check("Mikrofon (Realtek)".equals(
                JoinVoiceSettings.asciiDeviceName("Mikrofoné (Realtek)™ ")),
                "voice: non-ASCII chars dropped, trimmed");
        check(JoinVoiceSettings.asciiDeviceName("麦克风").isEmpty()
                && JoinVoiceSettings.asciiDeviceName(null).isEmpty()
                && JoinVoiceSettings.asciiDeviceName(" ab ").isEmpty(),
                "voice: fewer than 3 survivors => default (\"\")");
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            longName.append('x');
        }
        check(JoinVoiceSettings.asciiDeviceName(longName.toString()).length()
                == cc.starsector.client.voice.VoiceClientSettings.MAX_DEVICE_CHARS,
                "voice: device name capped at MAX_DEVICE_CHARS");
        cc.starsector.client.voice.VoiceClientSettings existing =
                cc.starsector.client.voice.VoiceClientSettings.load(
                        "voice.on=false\nvoice.volume=0.5\nhud.talkRow=false\nmic.gain=3\n");
        cc.starsector.client.voice.VoiceClientSettings merged =
                JoinVoiceSettings.merge(existing, "Blue Yeti", false, 1.25f);
        check(!merged.voiceOn && merged.volume == 0.5f && !merged.hudTalkRow
                && "Blue Yeti".equals(merged.micDevice) && !merged.micOn && merged.micGain == 1.25f,
                "voice: merge keeps /settings-owned keys, replaces the mic keys");
        check(JoinVoiceSettings.merge(null, "", true, 1f).micDevice.isEmpty()
                && JoinVoiceSettings.merge(null, "", true, 1f).voiceOn,
                "voice: merge over no file = DEFAULTS + mic keys");
        check(JoinVoiceSettings.merge(existing, "Blue Yeti", false, 1.25f, 7).complexity == 7
                && JoinVoiceSettings.merge(merged.withComplexity(3), "x", true, 1f).complexity == 3
                && JoinVoiceSettings.merge(existing, "", true, 1f, -1).complexity
                        == cc.starsector.client.voice.VoiceClientSettings.SERVER_DEFAULT,
                "voice: merge carries the quality pick (-1 = server default; 4-arg keeps the file's)");
        Path install = tmp.resolve("Starsector");
        Path modDir = install.resolve("mods").resolve("cc-client");
        check(JoinVoiceSettings.installRoot(modDir).equals(install.toAbsolutePath().normalize()),
                "voice: installRoot = modDir/../..");
        check(JoinVoiceSettings.settingsFile(install).equals(
                install.resolve("saves").resolve("common").resolve("cc_client_settings.data")),
                "voice: on-disk file = saves/common/cc_client_settings.data");
        Path file = JoinVoiceSettings.settingsFile(install);
        check(JoinVoiceSettings.read(file) == cc.starsector.client.voice.VoiceClientSettings.DEFAULTS,
                "voice: absent file reads as DEFAULTS");
        JoinVoiceSettings.write(file, merged);
        cc.starsector.client.voice.VoiceClientSettings back = JoinVoiceSettings.read(file);
        check(back.serialize().equals(merged.serialize()),
                "voice: write creates saves/common and round-trips exactly");
        check(new String(Files.readAllBytes(file), StandardCharsets.US_ASCII)
                .startsWith("# CC client settings (voice)"), "voice: canonical header");
        byte[] silent = new byte[960 * 2];
        check(JoinVoiceSettings.levelPercent(silent, 1f) == 0, "voice: silence = 0%");
        byte[] full = new byte[960 * 2];
        for (int i = 0; i < 960; i++) {
            full[2 * i] = (byte) 0xFF;
            full[2 * i + 1] = (byte) 0x7F;
        }
        check(JoinVoiceSettings.levelPercent(full, 1f) == 100, "voice: full scale = 100%");
        check(JoinVoiceSettings.levelPercent(full, 0f) == 0, "voice: gain 0 = 0%");
    }

    /** Selftest goldens: the ship pick's charset/plan lanes, the
     * canned-LSTD collector (a version-12 WELCOME decides nothing in it since THE FLIP),
     * the REJECT-reason mapping, and the GUI-side fingerprint self-compute. Swing-free; sockets never opened. */
    static void selftestShipPick(Path tmp) throws Exception {
        // pick sanitize + charset (the bat's :vguard charset + the HELLO v8 cap)
        check("".equals(JoinLaunchPlan.sanitizeShip(null))
                && "".equals(JoinLaunchPlan.sanitizeShip("   "))
                && "".equals(JoinLaunchPlan.sanitizeShip(JoinProbe.SERVER_DEFAULT_LABEL))
                && "x_y".equals(JoinLaunchPlan.sanitizeShip(" \"x_y\" ")), "ship sanitize");
        check(JoinLaunchPlan.validateShip("") == null
                && JoinLaunchPlan.validateShip("onslaught_xao_Standard") == null
                && JoinLaunchPlan.validateShip("a.b:c-d_9") == null
                && JoinLaunchPlan.validateShip("a".repeat(64)) == null, "ship charset accepted");
        check(JoinLaunchPlan.validateShip("weird#id") != null
                && JoinLaunchPlan.validateShip("-lead") != null
                && JoinLaunchPlan.validateShip("has space") != null
                && JoinLaunchPlan.validateShip("a".repeat(65)) != null,
                "ship charset rejected (vguard first-char/charset + HELLO cap)");
        check(!JoinProbe.offerable("") && !JoinProbe.offerable(null)
                && !JoinProbe.offerable(".lead") && JoinProbe.offerable("Z9"), "offerable table");

        // The host-picked-default lanes: the combo's
        // "(server default: <id>)" label persists as an EMPTY pick, and the Launch path resolves
        // the concrete id (empty pick -> fetched default; a real pick wins; no default -> empty).
        check("".equals(JoinLaunchPlan.sanitizeShip("(server default: conquest_Broadside)")),
                "server-default-with-id combo label sanitizes to an empty pick");
        check("conquest_Broadside".equals(
                        JoinLaunchPlan.resolveLaunchShip("", "conquest_Broadside"))
                && "hyperion_cc_Overdriven".equals(JoinLaunchPlan.resolveLaunchShip(
                        "hyperion_cc_Overdriven", "conquest_Broadside"))
                && "".equals(JoinLaunchPlan.resolveLaunchShip("", null)),
                "resolveLaunchShip: empty pick takes the fetched default, a real pick wins,"
                        + " no default => empty");
        check("(server default: x)".equals(JoinProbe.serverDefaultLabel("x")),
                "serverDefaultLabel format");

        // THE FLIP: the version-12 probe HELLO goldens (the readable password in the seat-token
        // slot, the cookied re-send) were deleted with their senders; the version-13 probe is pinned by Hs13KitSenderRows
        // R4a / R4b / R4d (fetch13 at a real armed door: version-13 HELLOs only, no secret on the wire).

        // the collector on a canned LSTD frame (bytes minted by the SERVER's ListingEmitter)
        InetSocketAddress door = new InetSocketAddress("127.0.0.1", 51820);
        InetSocketAddress other = new InetSocketAddress("127.0.0.1", 51821);
        byte[] lstd = hex(SELFTEST_LSTD_HEX);
        byte[] ctrl = DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.CTRL, 3, 0L, 0L,
                0L, 0, 0L, lstd);
        DoorProbe.Collector c = new DoorProbe.Collector(door);
        check(!c.offer(other, ctrl, ctrl.length) && !c.done() && c.ignored() == 1,
                "collector ignores a foreign source");
        check(!c.offer(door, ctrl, 10) && !c.done(), "collector ignores a truncated datagram");
        byte[] bound = DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.CTRL, 3, 7L, 0L,
                0L, 0, 0L, lstd);
        check(!c.offer(door, bound, bound.length) && !c.done(),
                "collector ignores a session-bound CTRL frame");
        byte[] wrongSeq = DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.CTRL, 3, 0L,
                1L, 0L, 0, 0L, lstd);
        check(!c.offer(door, wrongSeq, wrongSeq.length) && !c.done(),
                "collector ignores envelope seq != listing seqIndex");
        byte[] junk = new byte[200];
        check(!c.offer(door, junk, junk.length) && !c.done(), "collector ignores junk bytes");
        check(c.offer(door, ctrl, ctrl.length) && c.done(),
                "collector completes on the served frame (seqCount 1)");
        DoorProbe.Result served = c.result("no reply");
        check(served.served()
                && served.ids.equals(List.of("onslaught_xao_Standard", "shuttlepod_cc_Bare"))
                && served.hidden == 1 && served.totalAvailable == 3,
                "served result: offerable ids in server order, weird#id hidden, total 3");
        check(served.statusLine().startsWith("Ship list: 2 ships from the server")
                && served.statusLine().contains("1 not listed"), "served status line");

        // The host-picked default: a plain listing (every
        // entry mark 0) names none; the same listing with entry 0 flipped to MARK_SHIP_NEXT_RESPAWN
        // (==2) yields that offerable id as Result.defaultId, and the status line appends it.
        check(served.defaultId == null, "no mark-2 entry => no server default (unchanged today)");
        byte[] lstdDefault = withEntry0Mark(lstd, ClientListingReader.MARK_SHIP_NEXT_RESPAWN);
        byte[] ctrlDefault = DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.CTRL, 3, 0L,
                0L, 0L, 0, 0L, lstdDefault);
        DoorProbe.Collector cd = new DoorProbe.Collector(door);
        check(cd.offer(door, ctrlDefault, ctrlDefault.length) && cd.done(),
                "collector completes on the mark-2 default frame");
        DoorProbe.Result withDefault = cd.result("no reply");
        check(withDefault.served()
                && "onslaught_xao_Standard".equals(withDefault.defaultId)
                && withDefault.ids.equals(List.of("onslaught_xao_Standard", "shuttlepod_cc_Bare")),
                "mark-2 offerable entry => Result.defaultId is it");
        check(withDefault.statusLine().endsWith(" Server default: onslaught_xao_Standard."),
                "server-default status line");

        // THE FLIP: a WELCOME-channel datagram decides NOTHING in the collector — the version-13
        // flight alone reads WELCOMEs (fetch13's listen13 never offers one). A version-12 REJECT / ADMITTED / COOKIE for
        // our nonce (the stale door's plaintext grammar at the literal byte 12) is ignored and counted, one each, and
        // the collector still reads silence; a version-12 COOKIE is never a challenge (no fallback to 12 on any reply).
        byte[] reject = welcome(HandshakeCodec.WELCOME_REJECTED, 77L, "match_password_mismatch");
        byte[] admitted = welcome(HandshakeCodec.WELCOME_ADMITTED, 77L, "");
        byte[] cookieWelcome = cookieWelcome(77L, new byte[] {9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 1, 2, 3, 4, 5, 6, 7});
        DoorProbe.Collector cw = new DoorProbe.Collector(door);
        check(!cw.offer(door, reject, reject.length) && !cw.done() && cw.ignored() == 1,
                "collector: a version-12 REJECT for our nonce is ignored and counted (+1), never decisive");
        check(!cw.offer(door, admitted, admitted.length) && !cw.done() && cw.ignored() == 2,
                "collector: a version-12 ADMITTED is ignored and counted (+1), never decisive");
        check(!cw.offer(door, cookieWelcome, cookieWelcome.length) && !cw.done() && cw.ignored() == 3
                && cw.cookieChallenges() == 0,
                "collector: a version-12 COOKIE is ignored and counted (+1), never a challenge");
        DoorProbe.Result afterV12 = cw.result("no reply from h:1");
        check(!afterV12.served() && afterV12.rejectReason == null && afterV12.error != null
                && afterV12.error.startsWith("no reply"),
                "collector: after three version-12 WELCOMEs the outcome is still the no-reply failure");
        // the REJECT wording home (fetch13's flight REJECTED lands in the same Result.rejected)
        DoorProbe.Result rejected = DoorProbe.Result.rejected("match_password_mismatch");
        check(!rejected.served() && "match_password_mismatch".equals(rejected.rejectReason)
                && rejected.statusLine().contains("Wrong match password"),
                "rejected result + player-facing text");
        check(new DoorProbe.Collector(door).result("no reply from h:1").error
                .startsWith("no reply"), "silence => the no-reply failure");

        // The VCHK verdict collector on canned VACK bytes — the
        // consumer law (dialed source, channel VCHK, our nonce, a sent seq, that seq's id), the
        // receipt→verdict mapping, and the ONE wording home. No org.json here (the -cp jar law).
        java.util.Map<Integer, String> sent = new java.util.LinkedHashMap<>();
        sent.put(0, "my_local");
        sent.put(1, "second");
        DoorVariantCheck.Collector vc = new DoorVariantCheck.Collector(door, 99L, sent);
        byte[] okVack = vack(0, true, "", "my_local");
        byte[] okFrame = vchkFrame(99L, 0L, okVack);
        check(!vc.offer(other, okFrame, okFrame.length) && !vc.done() && vc.ignored() == 1,
                "verdict collector ignores a foreign source");
        byte[] foreignNonce = vchkFrame(98L, 0L, okVack);
        check(!vc.offer(door, foreignNonce, foreignNonce.length) && vc.ignored() == 2,
                "verdict collector ignores a receipt echoing a foreign nonce");
        byte[] seqMismatch = vchkFrame(99L, 1L, okVack);
        check(!vc.offer(door, seqMismatch, seqMismatch.length) && vc.ignored() == 3,
                "verdict collector ignores envelope seq != receipt submitSeq");
        byte[] otherId = vchkFrame(99L, 1L, vack(1, true, "", "not_sent"));
        check(!vc.offer(door, otherId, otherId.length) && vc.ignored() == 4,
                "verdict collector ignores a receipt whose id is not the one sent under that seq");
        byte[] onCtrl = DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.CTRL, 0, 99L, 0L,
                0L, 0, 0L, okVack);
        check(!vc.offer(door, onCtrl, onCtrl.length) && vc.ignored() == 5,
                "verdict collector ignores the VACK shape on another channel");
        check(!vc.offer(door, junk, junk.length) && vc.ignored() == 6,
                "verdict collector ignores junk bytes");
        check(!vc.offer(door, okFrame, okFrame.length) && !vc.done()
                && vc.verdicts().get("my_local") == DoorVariantCheck.Verdict.OK
                && vc.pendingSeqs().equals(Set.of(1)),
                "an accepted receipt = OK; the second check still pending");
        check(!vc.offer(door, okFrame, okFrame.length) && vc.ignored() == 7,
                "a duplicate receipt for an answered seq is ignored");
        byte[] rejFrame = vchkFrame(99L, 1L, vack(1, false, "custom_hull_denied", "second"));
        check(vc.offer(door, rejFrame, rejFrame.length) && vc.done()
                && vc.verdicts().get("second").status() == DoorVariantCheck.Status.REJECT
                && "custom_hull_denied".equals(vc.verdicts().get("second").token()),
                "a rejected receipt = REJECT carrying its token; 2/2 answered => done");
        check(vc.offer(door, junk, junk.length) && vc.ignored() == 7, "a done collector absorbs silently");
        check(DoorVariantCheck.verdictOf(new ClientVariantReceipt(3, false, "custom_unavailable", "x"))
                .status() == DoorVariantCheck.Status.UNAVAILABLE
                && DoorVariantCheck.verdictOf(new ClientVariantReceipt(3, false, "custom_disabled", "x"))
                        .status() == DoorVariantCheck.Status.UNAVAILABLE,
                "the two lane-off tokens read UNAVAILABLE (customs off on this server)");
        String hullHint = VariantRejectHints.hintOrNull("custom_hull_denied");
        check(JoinCore.verdictLine("a", DoorVariantCheck.Verdict.OK)
                        .equals("a: policy OK - final check at submit")
                && JoinCore.verdictLine("a", DoorVariantCheck.Verdict.UNKNOWN).equals("a: checked at launch")
                && JoinCore.verdictLine("a", new DoorVariantCheck.Verdict(
                        DoorVariantCheck.Status.UNAVAILABLE, "custom_disabled"))
                        .equals("a: customs off on this server")
                && JoinCore.verdictLine("a", new DoorVariantCheck.Verdict(
                        DoorVariantCheck.Status.REJECT, "custom_hull_denied"))
                        .equals("a: " + (hullHint != null ? hullHint : "custom_hull_denied")),
                "verdict words: the ONE wording home (SPA line == Swing row)");
        String vj = JoinCore.verdictsJson(vc.verdicts());
        check(vj.contains("\"my_local\":{\"status\":\"ok\",\"token\":\"\",\"line\":\"my_local: policy OK - final check at submit\"}")
                && vj.contains("\"second\":{\"status\":\"reject\",\"token\":\"custom_hull_denied\","),
                "customsVerdicts JSON shape: id-keyed {status, token, line}");

        // reject-reason mapping
        check(JoinProbe.rejectMessage("kit_outdated").contains("NEWEST kit")
                && JoinProbe.rejectMessage("content_fingerprint_mismatch").contains("is not the host's kit")
                && JoinProbe.rejectMessage("content_fingerprint_required").contains("is not the host's kit")
                && JoinProbe.rejectMessage("probe_unsupported").contains("match default")
                && JoinProbe.rejectMessage("probe_no_pickables").contains("server default")
                && JoinProbe.rejectMessage("match_password_required").contains("Enter the match")
                && JoinProbe.rejectMessage("client_id_required").contains("no player id")
                && JoinProbe.rejectMessage("zzz_new_reason").contains("zzz_new_reason"),
                "reject-reason mapping table");
        // The WIDENED debug-door probe cap is
        // retired server-side — the client pins the public door's 4-frame cap again.
        check(DoorProbe.MAX_FRAMES == 4, "DoorProbe.MAX_FRAMES mirrors PROBE_MAX_FRAMES (4)");

        // fingerprint self-compute: mirrors the plugin's composite + posture (blank on any miss)
        JoinFingerprint.Computed none = JoinFingerprint.selfCompute(tmp.resolve("nokit"));
        check(none.print.isEmpty() && none.note.contains("no match.txt"),
                "fingerprint: no kit => blank");
        Path kit = tmp.resolve("kit");
        Files.createDirectories(kit.resolve("jars"));
        Files.createDirectories(kit.resolve("data"));
        Files.write(kit.resolve("match.txt"), SELFTEST_DESCRIPTOR.getBytes(StandardCharsets.US_ASCII));
        byte[] jarBytes = "not-a-jar".getBytes(StandardCharsets.US_ASCII);
        Files.write(kit.resolve("jars").resolve("cc-client-mod.jar"), jarBytes);
        byte[] modInfo = "{\"id\":\"cc_client\"}".getBytes(StandardCharsets.US_ASCII);
        Files.write(kit.resolve("mod_info.json"), modInfo);
        byte[] dataFile = new byte[] {0, 1, 2, (byte) 0xff};
        Files.write(kit.resolve("data").resolve("x.bin"), dataFile);
        JoinFingerprint.Computed noSidecar = JoinFingerprint.selfCompute(kit);
        check(noSidecar.print.isEmpty() && noSidecar.note.contains("no fingerprint sidecar"),
                "fingerprint: kit without sidecar => blank (pre-D4.8 posture)");
        Files.write(kit.resolve("kit.files"), ("# kit file list\n"
                + "file=mod_info.json\nfile=data/x.bin\n")
                .getBytes(StandardCharsets.US_ASCII));
        TreeMap<String, byte[]> content = new TreeMap<>();
        content.put("mod_info.json", modInfo);
        content.put("data/x.bin", dataFile);
        String expected = ContentFingerprints.composite(ContentFingerprints.md5Hex(jarBytes),
                ContentFingerprints.dataMd5(content), HandshakeCodec.HANDSHAKE_VERSION,
                "1v1-selftest0000000");
        JoinFingerprint.Computed real = JoinFingerprint.selfCompute(kit);
        check(expected.equals(real.print) && real.print.startsWith("cc1;jar=")
                && real.print.contains(";hs=" + HandshakeCodec.HANDSHAKE_VERSION + ";"),
                "fingerprint: synthetic kit => the plugin's composite (jar+data+hs+match.id)");
        Files.delete(kit.resolve("data").resolve("x.bin"));
        JoinFingerprint.Computed stale = JoinFingerprint.selfCompute(kit);
        check(stale.print.isEmpty() && stale.note.contains("FAILED"),
                "fingerprint: a listed file missing => blank + FAILED note (stale-kit lane)");
        selftestFingerprintMatchNone(tmp);
    }

    /**
     * Rows {@code fp-none-kit-only}, {@code fp-undecodable}, {@code fp-over-cap}: a folder holding the kit's file
     * list presents a print whatever its cached match is — the composite over the same jar + data with
     * {@code match=none}, the why in the note — when there is no {@code match.txt}, when it does not decode, and
     * when it is one byte over the match cap (refused by its length, never handed to the codec); a file of exactly
     * the cap is read and decoded. The undecodable note is NOT checked for the file's bytes: the note still quotes
     * the codec's message here.
     */
    private static void selftestFingerprintMatchNone(Path tmp) throws Exception {
        Path kit = tmp.resolve("fp-none");
        Files.createDirectories(kit.resolve("jars"));
        Files.createDirectories(kit.resolve("data"));
        byte[] jarBytes = "not-a-jar".getBytes(StandardCharsets.US_ASCII);
        byte[] dataFile = new byte[] {3, 1, 4, (byte) 0xfe};
        Files.write(kit.resolve("jars").resolve("cc-client-mod.jar"), jarBytes);
        Files.write(kit.resolve("data").resolve("x.bin"), dataFile);
        Files.write(kit.resolve("kit.files"), "file=data/x.bin\n".getBytes(StandardCharsets.US_ASCII));
        TreeMap<String, byte[]> content = new TreeMap<>();
        content.put("data/x.bin", dataFile);
        String noneComposite = ContentFingerprints.composite(ContentFingerprints.md5Hex(jarBytes),
                ContentFingerprints.dataMd5(content), HandshakeCodec.HANDSHAKE_VERSION, ContentFingerprints.MATCH_NONE);
        JoinFingerprint.Computed kitOnly = JoinFingerprint.selfCompute(kit);
        check(noneComposite.equals(kitOnly.print)
                && ContentFingerprints.MATCH_NONE.equals(ContentFingerprints.componentOrNull(kitOnly.print, "match"))
                && kitOnly.note.contains("no match fetched yet"),
                "fp-none-kit-only: the file list without match.txt presents the composite with match=none and says no"
                        + " match was fetched yet (flips if a fresh folder presents a blank print, another term, or a"
                        + " silent note)");
        Path match = kit.resolve("match.txt");
        Files.write(match, "this is not a match document\n".getBytes(StandardCharsets.US_ASCII));
        JoinFingerprint.Computed garbage = JoinFingerprint.selfCompute(kit);
        check(noneComposite.equals(garbage.print) && garbage.note != null && !garbage.note.isBlank()
                && !"content fingerprint self-computed".equals(garbage.note),
                "fp-undecodable: a match.txt that does not decode presents match=none with a why in the note (flips if"
                        + " it blanks the print, presents another term, or reads as a clean self-compute)");
        byte[] over = new byte[32_769];
        Arrays.fill(over, (byte) 'x');
        Files.write(match, over);
        JoinFingerprint.Computed big = JoinFingerprint.selfCompute(kit);
        check(noneComposite.equals(big.print) && big.note.contains("32768-byte match cap")
                && !big.note.contains("did not decode"),
                "fp-over-cap: a 32,769-byte match.txt presents match=none and the note names the cap, never a decode"
                        + " (flips if the cap moves or the over-cap bytes reach the codec)");
        Files.write(match, Arrays.copyOf(over, 32_768));
        JoinFingerprint.Computed atCap = JoinFingerprint.selfCompute(kit);
        check(noneComposite.equals(atCap.print) && !atCap.note.contains("-byte match cap"),
                "fp-over-cap: a match.txt of exactly the cap is read and decoded, never refused by its length (flips"
                        + " if the cap is one byte low)");
    }

}
