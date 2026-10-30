package cc.starsector.server.ops;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.MatchPasswords;
import cc.starsector.server.match.WaveSchedule;
import cc.starsector.server.runtime.OperatorCommandFile;
import cc.starsector.server.runtime.SimTickRate;
import cc.starsector.server.match.ContentFingerprintMint;
import static cc.starsector.server.ops.OpsMatchRows.*;
import static cc.starsector.server.ops.ServerOpsCore.*;

/**
 * The Start / W10 LAUNCH trio carved out of {@link ServerOpsCore}:
 *
 * the exact Start-button vectors
 * ({@link #stageCommand} then {@link #launchCommand}), the shell-agnostic Start flow ({@link #runStartFlow} - every confirm
 * gathered BEFORE the first write, then props row + password sidecar + spawn), the real spawns
 * both shells pass as {@link ServerOpsCore.SpawnFn}, and the W10 debug-WATCH press
 * ({@link #watchCommand} / {@link #runWatchFlow}, D-BRIEFGONE brief forward). One-way: this class
 * reads the core's row editors, lane constants and {@code Ui}/{@code SpawnFn} vocabulary
 * (static import); the core never references it. Bodies moved VERBATIM - provenance and law
 * citations inside each javadoc are the originals.
 * Selftest rows: {@link ServerOpsCoreSelftest} (ui1..ui17, uw1..uw4).
 */
public final class ServerOpsLaunch {
    private ServerOpsLaunch() {
    }

    /**
     * The Start button's bind validation (the throws of the retired pwsh {@code startCommand},
     * kept verbatim). Bind host/port come from the Network fields (lease-filled or
     * manual) and ride {@link #stageCommand} into owner mode's {@code launcher.properties} BOX rows
     * (never a config row); package mode's Start writes the
     * {@code server.bindHost} / {@code server.bindPort} config rows instead ({@link #PACKAGE_BIND_HOST_KEY}).
     * Rejoin lifecycle: this lane is the HOSTING
     * lane — the launcher's supervisor always relaunches a crashed life (the SAME template; clean
     * exit 0 stops) — so it REFUSES port 0/auto: the client's re-HELLO endpoint is resolved once
     * into a {@code final} address (PredictReconcileDatagramSession), so a rebirth on a fresh
     * ephemeral port would strand every standing client; the pinned port is the one hard glide
     * requirement. Validation throws — a typo becomes a dialog, never a server bound to the wrong spot.
     */
    static void validateBind(String bindHost, String bindPortText) {
        String host = bindHost == null ? "" : bindHost.trim();
        if (host.isEmpty() || host.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "bind host must be one non-empty token, got '" + bindHost + "'");
        }
        // The host lands raw in a properties row, where a backslash or a non-ASCII char folds or breaks it.
        if (host.length() > 253 || host.chars().anyMatch(c -> c == '\\' || c < 0x21 || c > 0x7e)) {
            throw new IllegalArgumentException("bind host must be a plain ASCII name or address of at most"
                    + " 253 characters, with no backslash");
        }
        int port;
        try {
            port = Integer.parseInt(bindPortText == null ? "" : bindPortText.trim());
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException(
                    "bind port must be an integer 1..65535, got '" + bindPortText + "'");
        }
        if (port == 0) {
            // A package pulls no owner lease (its button
            // is Fill from ProtonVPN), so its sentence names only the port to type.
            throw new IllegalArgumentException("bind port 0/auto is refused on this lane: a"
                    + " supervised rebirth re-binds a NEW ephemeral port and standing clients"
                    + " glide only onto the SAME port."
                    + (OpsRoots.get().mode() == OpsRoots.Mode.PACKAGE
                            ? " Type the forwarded/LAN port."
                            : " Type the forwarded/LAN port, or Pull VPN lease."));
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("bind port must be 1..65535, got " + port);
        }
    }

    /** A password sidecar is a secret at rest, so a POSIX box makes it owner-only
     * (rw-------, like the console key file) BEFORE the secret is written; Windows keeps its ACLs. */
    static void writeOwnerOnly(Path file, String content) throws IOException {
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            if (!Files.exists(file)) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** The config rows package
     * mode's Start writes its bind pair into — the serve's own file keys ({@code PostureRegistry}'s
     * {@code cc.server.bindHost} / {@code cc.server.bindPort} knobs), the rows a stranger's
     * {@code serve.bat} reads too (README step 2). */
    static final String PACKAGE_BIND_HOST_KEY = "server.bindHost";
    static final String PACKAGE_BIND_PORT_KEY = "server.bindPort";

    /** The refusal a package-mode Start with a blank
     * match password gets ({@link #runStartFlow}); it names the launcher's own refusal token so the
     * host can match it to the launcher's console line. */
    static final String PACKAGE_PASSWORD_REQUIRED = "A package serve needs a match password: the"
            + " launcher refuses to start without one (" + ServeLauncher.R_PASSWORD_MISSING
            + "). Type a match password.";

    /** The refusal a package-mode Start gets when the
     * package's {@code launcher.properties} sets a {@link ServeLauncher#ROW_BIND_HOST} /
     * {@link ServeLauncher#ROW_BIND_PORT} box row ({@link #runStartFlow}): the launcher lets a set
     * box port win over the config row and exports a set box host as the serve's env twin, which
     * outranks the config file, so the serve would bind the box pair while the console shows and
     * hands out its own. */
    static final String PACKAGE_BOX_ROWS_SET = "launcher.properties has bind.host= or bind.port= set."
            + " In this package the console's Start owns the bind: it writes server.bindHost /"
            + " server.bindPort into cc-server.properties, and a set launcher row would silently"
            + " override them. Blank both rows (keep the lines) and Start again.";

    /**
     * The config lines with ONE {@code key=value} row: {@link ServerOpsCore#withDescriptorRow}'s
     * surgery (the first row replaced in place, Properties-shadowed duplicates dropped, appended
     * when absent; no marker and no remove branch — the launcher refuses a package without a fixed
     * port) over a {@code java.util.Properties} key match ({@link ServerOpsCore#propertiesRowValue}:
     * leading blanks skipped, the key ends at {@code =}, {@code :} or a blank), so a host's
     * hand-typed {@code server.bindPort = 51337} is the SAME row, never a second one Properties'
     * last-wins would let shadow the console's. Callers pass a validated value (one token, no line
     * break: {@link #validateBind}).
     */
    static List<String> withPackageBindRow(List<String> lines, String key, String value) {
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            // The ONE key grammar descriptorRowValue
            // and withDescriptorRow use (leading blanks = Properties' own space / tab / form feed)
            if (propertiesRowValue(line, key) != null) {
                if (!placed) {
                    out.add(key + "=" + value);
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (!placed) {
            out.add(key + "=" + value);
        }
        return out;
    }

    // ---------------------------------------------- the Start chain

    /** Set at the stage spawn, cleared when the chain ends (ALWAYS, {@link StartChain#advance}'s
     * {@code finally}): a Start already staging refuses the next one ({@link #serveBusy}) and
     * keeps a shell's Start disabled ({@link OpsWindowModel#apply}). Process-wide. The
     * chain holds it past the launcher spawn until that
     * launcher is SEEN holding {@code run/serve.lock} ({@link #awaitLauncherLock}): before, a
     * second Start in the launcher's boot window passed every probe leg and re-staged the
     * package under its feet. */
    static final AtomicBoolean STAGING = new AtomicBoolean();

    /**
     * The debug WATCH lease beside the config file. Its STAMP (the writing console's pid) tells a serve that
     * reads {@code server.clientDebug=true} from that file that the console which wrote the row is still LIVE
     * ({@code ClientDebugLease.leaseStampPid} + one {@code ProcessHandle} probe at boot); the
     * serve reads a file-tier true with no / a stale / a malformed lease as false (its lease law), so the row cannot
     * outlive the watch into a later plain serve and hand every joiner the debug keys. The debug WATCH
     * Start writes it (a fresh stamp) in the same locked slot as the row, before the spawn; a plain Start
     * deletes it with its false; the console's lease daemon ({@code ServerOpsCore.armWatchLease}) deletes
     * it and writes the row false once the serve it covered has exited.
     */
    public static final String WATCH_LEASE_FILE = "watch.lease";
    /** Serializes every console write of the config file (a read-compose-write under the lock, so no writer
     *  clobbers another's bytes): the Start's posture write, the lease daemon's clear at watch end, the web
     *  voice + mods handlers ({@code OpsConsole}), the Swing Mods tab ({@code OpsModsPanel}) and Apply's
     *  map.half row ({@code OpsMatchApply.applyMapSize}). */
    static final Object PROPS_LOCK = new Object();

    /** The lease path beside {@code propsFile} (the serve probes the same sibling of the file it loaded). */
    public static Path watchLeaseFile(Path propsFile) {
        return propsFile.toAbsolutePath().resolveSibling(WATCH_LEASE_FILE);
    }

    /** A debug WATCH Start writes the lease (one stamp line: the console pid + a nanotime, the stamp its
     *  daemon recognises as its own), a plain Start deletes any. Caller holds {@link #PROPS_LOCK}. */
    static void writeOrClearWatchLease(Path propsFile, boolean debugWatch) throws IOException {
        Path lease = watchLeaseFile(propsFile);
        if (debugWatch) {
            Files.writeString(lease, ProcessHandle.current().pid() + "-" + System.nanoTime() + "\n",
                    StandardCharsets.US_ASCII);
        } else {
            Files.deleteIfExists(lease);
        }
    }

    /** The stage's wall-clock ceiling (a 20–40 s stage; past this the chain ends it, exit −1). */
    static final long STAGE_CEILING_S = 180L;
    /** How long the chain waits for the spawned launcher to hold the package lock before it
     * clears {@link #STAGING} anyway (SAID in the start log): a launcher JVM takes 0.2–0.5 s to
     * reach its {@code tryLock} on a warm box; this is the cold-disk margin.
     * Settable by the selftests only (a live {@code FakeProcess} never locks anything). */
    static final AtomicLong LAUNCHER_LOCK_WAIT_MS = new AtomicLong(20_000L);
    /** The poll grain of {@link #awaitLauncherLock} (a daemon thread, never the EDT). */
    static final long LAUNCHER_LOCK_POLL_MS = 100L;
    /** The cross-JVM twin of {@link #STAGING}:
     * {@code <pkg>/stage.lock}, HELD by the staging console's JVM from its probe's pass until its
     * chain ends ({@link #beginStaging} / {@link #endStaging}) — the flag alone is per JVM, and a
     * second console (the Swing GUI beside a web shell, or two shells) saw no signal at all during
     * the first one's 20–40 s stage: nothing else locks anything before the launcher boots. */
    static final String STAGE_LOCK_FILE = "stage.lock";
    /** This JVM's hold on {@link #STAGE_LOCK_FILE} (null = not staging / fell back to the flag). */
    static final AtomicReference<FileChannel> STAGE_HOLD = new AtomicReference<>();

    /**
     * Owner mode's
     * running STAGE ({@link StartChain#start} sets it, {@link StartChain#advance}'s {@code finally}
     * clears it with {@code compareAndSet}, so an older chain never clears a newer stage) and the ONE
     * JVM shutdown hook over it ({@link #armStageHook}, registered once): a console closed mid-stage
     * ({@code System.exit} runs hooks) kills the stage's {@code descendants()} FIRST (the nested
     * {@code pwsh … -Preflight}) and then the stage, so no orphan mints a package nobody launches.
     * Accept-why: a HARD kill of the console runs no hook and still leaks a ≤ 40 s stage — the next
     * console's stage re-mints, and no serve starts without a console. Package mode has no stage.
     * {@link #STAGE_HOOKS} counts real registrations (the selftest's once-only pin).
     */
    static final AtomicReference<Process> CURRENT_STAGE = new AtomicReference<>();
    private static final AtomicBoolean STAGE_HOOK_ARMED = new AtomicBoolean();
    static final AtomicInteger STAGE_HOOKS = new AtomicInteger();

    static void armStageHook() {
        if (STAGE_HOOK_ARMED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(ServerOpsLaunch::reapStage,
                    "cc-ops-stage-reaper"));
            STAGE_HOOKS.incrementAndGet();
        }
    }

    /** The hook's body: an alive {@link #CURRENT_STAGE}'s tree, children first. */
    static void reapStage() {
        Process stage = CURRENT_STAGE.get();
        if (stage != null && stage.isAlive()) {
            try {
                stage.descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (RuntimeException noTree) {
                CHAIN_QUIET_FAILURES.incrementAndGet();
            }
            stage.destroyForcibly();
        }
    }

    /**
     * Sets {@link #STAGING} and takes the cross-JVM hold. False = another console won the hold
     * between our probe and here (the flag is reset; the caller refuses). A hold that cannot be
     * taken for an I/O reason falls back to the flag alone (true, counted) — the launcher's own
     * lock still refuses a twin. The held file is the live record's {@link OpsRoots#stageLock}.
     */
    static boolean beginStaging(Path pkgRoot) {
        STAGING.set(true);
        try {
            Files.createDirectories(pkgRoot);
            FileChannel ch = FileChannel.open(OpsRoots.get().stageLock(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock won;
            try {
                won = ch.tryLock();
            } catch (OverlappingFileLockException heldInThisJvm) {
                won = null;
            }
            if (won == null) {
                ch.close();
                STAGING.set(false);
                return false;
            }
            FileChannel stale = STAGE_HOLD.getAndSet(ch);
            if (stale != null) {
                stale.close(); // cannot happen while STAGING guards; closed rather than leaked
            }
        } catch (IOException | RuntimeException noHold) {
            CHAIN_QUIET_FAILURES.incrementAndGet();
        }
        return true;
    }

    /** Releases the cross-JVM hold (closing the channel releases its lock) and clears the flag;
     * idempotent, so every end-of-staging path (the chain's {@code finally}, a refused or failed
     * flow, a recording spawn) may call it. */
    static void endStaging() {
        FileChannel ch = STAGE_HOLD.getAndSet(null);
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException | RuntimeException noClose) {
                CHAIN_QUIET_FAILURES.incrementAndGet();
            }
        }
        STAGING.set(false);
    }
    /** The stage tool's receipt under the package root ({@code snaproot=} = the run snapshot). */
    static final String STAGE_RECEIPT = "stage.txt";
    /** Probe / chain catches that read as "not busy" or a chain line that could not be appended —
     * counted, never thrown (the probe fails OPEN only for unreadable files: a live launcher
     * still holds the lock, which the launcher itself refuses on). */
    static final AtomicLong CHAIN_QUIET_FAILURES = new AtomicLong();

    /** The package root the probe, the stage and the launcher run against: {@link OpsRoots#pkg}
     * of the live record (owner mode's {@link ServerOpsCore#OPS_PKG_ROOT} made absolute, a temp
     * root under a selftest, the unpacked zip in package mode). */
    static Path pkgRoot() {
        return OpsRoots.get().pkg();
    }

    /** Where the chain's own lines go: {@link OpsRoots#startLog} of the live record (owner
     * mode's {@link ServerOpsCore#START_LOG}, whose tail the failure dialog shows). */
    static Path chainLog() {
        return OpsRoots.get().startLog();
    }

    /**
     * The STAGE spawn — the owner-mode pre-step
     * through {@link ServerOpsCore#STAGE_TOOL}'s {@code -Stage} mode, which REQUIRES the
     * bind pair + the owner pid (the stage tool's contract: the bind rows become BOX rows of
     * {@code launcher.properties}; {@code -OwnerPid} re-stamps the run snapshot's owner to this GUI).
     * {@code pkgRoot} / {@code configAbs} are made absolute here.
     */
    static List<String> stageCommand(Path pkgRoot, Path configAbs, String host, int port,
            long ownerPid) {
        return new ArrayList<>(List.of("pwsh", "-NoProfile", "-File", STAGE_TOOL,
                "-Stage", pkgRoot.toAbsolutePath().toString(),
                "-ServerConfig", configAbs.toAbsolutePath().toString(),
                "-BindHost", host, "-BindPort", Integer.toString(port),
                "-OwnerPid", Long.toString(ownerPid)));
    }

    /**
     * The LAUNCH spawn — the SAME {@code ServeLauncher} a
     * stranger's {@code serve.bat} runs, on THIS JVM's runtime
     * ({@code java.home}; no {@code pwsh}). {@code --pkg} is ALWAYS explicit (the launcher's default
     * = the parent of its code source = the run snapshot), {@code --config} absolute (every
     * config-relative read and write stays at the config's dir).
     */
    static List<String> launchCommand(Path pkgRoot, Path configAbs) {
        return launchCommand(pkgRoot, configAbs, System.getProperty(TimerOptOut.KNOB_PROPERTY));
    }

    /**
     * The pure half; {@code timerOptOutKnob} = this console JVM's {@value TimerOptOut#KNOB_PROPERTY}
     * text (null = unset). The package README promises a host who objects
     * to the timer opt-out can skip it, so a console started with {@code -Dcc.ops.timerOptOut=off}
     * ({@code ops-gui.bat} / {@code ops-web.bat}) passes the CONSTANT {@code off} on as the launcher
     * JVM's own {@code -D} before {@code -cp} - never the host's text; any other value adds nothing.
     * Pinned by {@code ServerOpsCoreSelftest} {@code launch-vector-optout}.
     */
    static List<String> launchCommand(Path pkgRoot, Path configAbs, String timerOptOutKnob) {
        Path pkg = pkgRoot.toAbsolutePath();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .startsWith("windows");
        Path java = Paths.get(System.getProperty("java.home"), "bin",
                windows ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        if (TimerOptOut.knobOff(timerOptOutKnob)) {
            command.add("-D" + TimerOptOut.KNOB_PROPERTY + "=off");
        }
        // Like the serve, a launcher crash leaves no memory dump (it can hold the passwords) and reports under run/.
        command.addAll(List.of("-XX:-CreateCoredumpOnCrash",
                "-XX:ErrorFile=" + pkg.resolve(ServeLauncher.RUN_DIR).resolve("hs_err_pid%p.log")));
        command.addAll(List.of("-cp", pkg.resolve("classes").toString(),
                ServeLauncher.class.getName(), "--pkg", pkg.toString(),
                "--config", configAbs.toAbsolutePath().toString()));
        return command;
    }

    /** The {@code launcher.properties} the package
     * launcher reads - {@link #launchCommand} passes {@code --pkg} = {@link #pkgRoot} and never
     * {@code --launcher-properties}, so it is {@link ServeLauncher}'s own default under that root. */
    static Path launcherPropertiesFile() {
        return pkgRoot().resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES);
    }

    /** The package console's saved Advertise - a
     * {@code launcher.properties} row the LAUNCHER never reads. Safe there:
     * {@link ServeLauncher#readLauncherRows} keeps any key silently (no unknown-row warning or
     * refusal; the unknown-key warnings cover {@code cc-server.properties} only). Written by a
     * successful package Copy join brick, read by the Setup form at open. */
    static final String LAUNCHER_ROW_ADVERTISE = "advertise";

    /** The "Fill from ProtonVPN" mode rows - like
     * {@link #LAUNCHER_ROW_ADVERTISE}, rows the LAUNCHER never reads. A fill SAVES its equality
     * reference here ({@link #writeFillRows}: the bind host, the advertise, the mode), so both
     * shells and a console restart compare a submission against the same saved values
     * ({@link #protonMode}) and neither shell keeps in-memory mode state. The mode row is never
     * blank ({@link #writeLauncherRow} skips blanks): {@link #ADVERTISE_FROM_PROTONVPN} or
     * {@link #ADVERTISE_FROM_TYPED}. */
    static final String LAUNCHER_ROW_ADVERTISE_FROM = "advertise.from";
    /** The fill's TUN bind host (the saved reference {@link #protonMode} compares the bind host against). */
    static final String LAUNCHER_ROW_ADVERTISE_BIND = "advertise.bind";
    /** {@link #LAUNCHER_ROW_ADVERTISE_FROM}: the addresses came from a ProtonVPN fill. */
    static final String ADVERTISE_FROM_PROTONVPN = "protonvpn";
    /** {@link #LAUNCHER_ROW_ADVERTISE_FROM}: the host typed or edited the addresses. */
    static final String ADVERTISE_FROM_TYPED = "typed";

    /** The UTF-8 BOM as its three ISO-8859-1 chars ({@link #withLauncherRow} walks bytes as chars). */
    private static final String BOM_LATIN1 = new String(new byte[] {(byte) 0xEF, (byte) 0xBB,
            (byte) 0xBF}, StandardCharsets.ISO_8859_1);
    private static final Pattern LINE_BREAK = Pattern.compile("\r\n|\n|\r");

    /**
     * {@code file} (a {@code launcher.properties}'s
     * bytes) with ONE row set to {@code key=value}, every other byte kept. WHY bytes: a host's
     * hand-edited file keeps its comments, its line separators, a BOM and any byte a UTF-8 decode
     * would replace - the bytes are walked as ISO-8859-1 (one char per byte) and only the new row's
     * value is UTF-8. The row rewritten is the one {@link ServeLauncher#readLauncherRows} reads: a
     * trimmed non-comment line whose text before its FIRST {@code =} trims to {@code key}, the LAST
     * such line (its map keeps the last). No such row = {@code key=value} appended with the file's
     * first line separator (none = this platform's). A row whose launcher-read value (trimmed,
     * Explorer quotes dropped) already equals {@code value} = {@code file} itself, unchanged. Pure.
     *
     * @param value trimmed, non-blank, no line break ({@link #writeLauncherRow} refuses the rest)
     */
    static byte[] withLauncherRow(byte[] file, String key, String value) {
        String text = new String(file, StandardCharsets.ISO_8859_1);
        String row = key + "=" + new String(value.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.ISO_8859_1);
        int pos = text.startsWith(BOM_LATIN1) ? BOM_LATIN1.length() : 0;
        int hitStart = -1;
        int hitEnd = -1;
        String hitValue = null;
        String firstBreak = null;
        Matcher br = LINE_BREAK.matcher(text);
        while (true) {
            boolean more = br.find(pos);
            int end = more ? br.start() : text.length();
            String line = text.substring(pos, end).trim();
            int eq = line.indexOf('=');
            if (!line.startsWith("#") && !line.startsWith("!") && eq > 0
                    && line.substring(0, eq).trim().equals(key)) {
                hitStart = pos;
                hitEnd = end;
                hitValue = line.substring(eq + 1).trim();
                if (hitValue.length() >= 2 && hitValue.startsWith("\"") && hitValue.endsWith("\"")) {
                    hitValue = hitValue.substring(1, hitValue.length() - 1).trim();
                }
            }
            if (!more) {
                break;
            }
            if (firstBreak == null) {
                firstBreak = br.group();
            }
            pos = br.end();
        }
        String out;
        if (hitStart >= 0) {
            if (row.substring(key.length() + 1).equals(hitValue)) {
                return file;
            }
            out = text.substring(0, hitStart) + row + text.substring(hitEnd);
        } else {
            String nl = firstBreak != null ? firstBreak : System.lineSeparator();
            boolean open = !text.isEmpty() && !text.equals(BOM_LATIN1)
                    && !text.endsWith("\n") && !text.endsWith("\r");
            out = text + (open ? nl : "") + row + nl;
        }
        return out.getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Sets ONE {@code launcher.properties} row from a
     * form field ({@link #withLauncherRow}). A blank field = the file untouched (no read, no write:
     * the launcher's own {@code game_root} refusal then names the row, as before); a value equal to
     * the row's = no write; a missing file = created holding the one row. Refused (the file
     * untouched): a line break in the value (it would forge a second row) or a file over the
     * launcher's {@link ServeLauncher#MAX_LAUNCHER_PROPERTIES_BYTES} read cap.
     *
     * <p>A missing file beside a {@value #LAUNCHER_TEMPLATE_FILE}
     * is first created from the template's bytes ({@link #seedLauncherFromTemplate}), THEN the row is upserted
     * into it, so a package host's first saved field lands in the whole commented file the package ships; no
     * template (owner mode) keeps the one-row file.
     *
     * @return true = the file was written (a template seed counts, even when the row then needed no change)
     */
    static boolean writeLauncherRow(Path file, String key, String fieldText) throws IOException {
        String value = fieldText == null ? "" : fieldText.trim();
        if (value.isEmpty()) {
            return false;
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IOException("the " + key + " text holds a line break");
        }
        boolean seeded = false;
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            // On a PACKED folder the repair seeds the
            // missing file itself (every seed, not this one alone); the template seed below then finds it and
            // returns false, and the row is upserted into it. An unpacked folder reaches the seed as before.
            PackageRepair.repairIfPacked(file.getParent(), System.out);
            seeded = seedLauncherFromTemplate(file);
        }
        byte[] before = new byte[0];
        if (Files.exists(file)) {
            if (Files.size(file) > ServeLauncher.MAX_LAUNCHER_PROPERTIES_BYTES) {
                throw new IOException(file + " is over " + ServeLauncher.MAX_LAUNCHER_PROPERTIES_BYTES
                        + " bytes");
            }
            before = Files.readAllBytes(file);
        }
        byte[] after = withLauncherRow(before, key, value);
        if (Arrays.equals(before, after)) {
            return seeded;
        }
        Files.write(file, after);
        return true;
    }

    /** The launcher-row template a package ships beside {@code launcher.properties} (the first start's seed source). */
    static final String LAUNCHER_TEMPLATE_FILE = "launcher.template.properties";

    /**
     * Creates a MISSING {@code file} from the bytes of the
     * {@value #LAUNCHER_TEMPLATE_FILE} beside it. A direct {@code CREATE_NEW} write: never over a file, never
     * through a link at the name; the template is read only when it is a regular file reached without a link,
     * and under the launcher's own {@link ServeLauncher#MAX_LAUNCHER_PROPERTIES_BYTES} read cap (a larger one
     * refuses: the launcher could not read the file it would seed). No template = nothing written.
     *
     * @return true = the file was created here; false = no template, or the file exists (a writer that made it
     *     between the caller's check and this create - the caller then edits the file as found)
     */
    static boolean seedLauncherFromTemplate(Path file) throws IOException {
        Path template = file.resolveSibling(LAUNCHER_TEMPLATE_FILE);
        if (!Files.isRegularFile(template, LinkOption.NOFOLLOW_LINKS)
                || !Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.size(template) > ServeLauncher.MAX_LAUNCHER_PROPERTIES_BYTES) {
            throw new IOException(template + " is over " + ServeLauncher.MAX_LAUNCHER_PROPERTIES_BYTES + " bytes");
        }
        byte[] bytes = Files.readAllBytes(template);
        try {
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException appeared) { // the one race outcome: said once, then edited as found
            System.err.println("console: " + file.getFileName() + " appeared while it was being created from "
                    + LAUNCHER_TEMPLATE_FILE + "; the row is written into that file");
            return false;
        }
    }

    /**
     * Field: {@link #writeLauncherRow} for a row whose BLANK field means
     * "no row" - a blank {@code fieldText} REMOVES every {@code key=} row from the file (every other byte kept,
     * {@link #withoutLauncherRow}); a non-blank one is {@link #writeLauncherRow} verbatim. WHY: the debug WATCH's
     * second game folder ({@link ServeLauncher#ROW_GAME_ROOT_SECOND}) is optional - with the blank-skips law a host
     * who cleared the field could never get back to one window (the row would stay). A missing file + a blank =
     * nothing (no file minted). Same read cap and line-break refusal as the writer.
     *
     * @return true = the file was written
     */
    static boolean writeOrClearLauncherRow(Path file, String key, String fieldText) throws IOException {
        String value = fieldText == null ? "" : fieldText.trim();
        if (!value.isEmpty()) {
            return writeLauncherRow(file, key, fieldText);
        }
        if (!Files.exists(file)) {
            return false;
        }
        if (Files.size(file) > ServeLauncher.MAX_LAUNCHER_PROPERTIES_BYTES) {
            throw new IOException(file + " is over " + ServeLauncher.MAX_LAUNCHER_PROPERTIES_BYTES + " bytes");
        }
        byte[] before = Files.readAllBytes(file);
        byte[] after = withoutLauncherRow(before, key);
        if (Arrays.equals(before, after)) {
            return false;
        }
        Files.write(file, after);
        return true;
    }

    /** {@code file} without EVERY row {@link ServeLauncher#readLauncherRows} would read as {@code key} (each with
     *  its own line break; the same ISO-8859-1 walk as {@link #withLauncherRow}); no such row = {@code file} itself.
     *  Every row goes, not only the last the reader keeps: a surviving earlier duplicate would silently become live. Pure. */
    static byte[] withoutLauncherRow(byte[] file, String key) {
        String text = new String(file, StandardCharsets.ISO_8859_1);
        StringBuilder out = new StringBuilder(text.length());
        int pos = text.startsWith(BOM_LATIN1) ? BOM_LATIN1.length() : 0;
        out.append(text, 0, pos);
        Matcher br = LINE_BREAK.matcher(text);
        boolean dropped = false;
        while (true) {
            boolean more = br.find(pos);
            int end = more ? br.start() : text.length();
            int next = more ? br.end() : text.length();
            String line = text.substring(pos, end).trim();
            int eq = line.indexOf('=');
            boolean hit = !line.startsWith("#") && !line.startsWith("!") && eq > 0
                    && line.substring(0, eq).trim().equals(key);
            if (hit) {
                dropped = true;
            } else {
                out.append(text, pos, next);
            }
            if (!more) {
                break;
            }
            pos = next;
        }
        return dropped ? out.toString().getBytes(StandardCharsets.ISO_8859_1) : file;
    }

    /** A ProtonVPN fill's saved reference - three
     * {@link #writeLauncherRow} calls in this order: {@link #LAUNCHER_ROW_ADVERTISE_BIND} =
     * {@code bindHost}, {@link #LAUNCHER_ROW_ADVERTISE} = {@code advertise},
     * {@link #LAUNCHER_ROW_ADVERTISE_FROM} = {@link #ADVERTISE_FROM_PROTONVPN} (the mode LAST, so a
     * write that fails midway leaves a reference {@link #protonMode} no longer matches). Each call
     * is a whole-file rewrite; a blank value leaves its row as it was. The caller holds the file's
     * lock (the shells' job). */
    static void writeFillRows(Path file, String bindHost, String advertise) throws IOException {
        writeLauncherRow(file, LAUNCHER_ROW_ADVERTISE_BIND, bindHost);
        writeLauncherRow(file, LAUNCHER_ROW_ADVERTISE, advertise);
        writeLauncherRow(file, LAUNCHER_ROW_ADVERTISE_FROM, ADVERTISE_FROM_PROTONVPN);
    }

    /** The mode row alone - {@link #ADVERTISE_FROM_PROTONVPN}
     * or {@link #ADVERTISE_FROM_TYPED} (a Start / brick whose {@link #protonMode} is false writes
     * {@code typed}). */
    static void writeModeRow(Path file, boolean proton) throws IOException {
        writeLauncherRow(file, LAUNCHER_ROW_ADVERTISE_FROM,
                proton ? ADVERTISE_FROM_PROTONVPN : ADVERTISE_FROM_TYPED);
    }

    /**
     * Is a Start / brick submission still IN the ProtonVPN
     * mode? Pure. True iff the saved {@link #LAUNCHER_ROW_ADVERTISE_FROM} row (trimmed) is
     * {@link #ADVERTISE_FROM_PROTONVPN}, the submitted bind host (trimmed) equals the saved
     * {@link #LAUNCHER_ROW_ADVERTISE_BIND} row, and the submitted advertise's ADDRESS part
     * ({@link #addressPart}) equals the saved {@link #LAUNCHER_ROW_ADVERTISE} row's - both saved
     * values non-blank (no saved fill = no mode). WHY value equality against the SAVED fill:
     * the SERVER decides - a page flag is a hint it never trusts, and
     * the saved rows survive a console restart in both shells. A port edit alone stays in the
     * mode; an address edit leaves it; a Start / brick that finds this false writes
     * {@link #ADVERTISE_FROM_TYPED} ({@link #writeModeRow}).
     *
     * @param launcherRows the launcher file's rows ({@link ServeLauncher#readLauncherRows}); null = none
     */
    static boolean protonMode(Map<String, String> launcherRows, String submittedBindHost,
            String submittedAdvertise) {
        if (launcherRows == null) {
            return false;
        }
        String from = launcherRows.get(LAUNCHER_ROW_ADVERTISE_FROM);
        if (!ADVERTISE_FROM_PROTONVPN.equals(from == null ? "" : from.trim())) {
            return false;
        }
        String savedBind = launcherRows.get(LAUNCHER_ROW_ADVERTISE_BIND);
        savedBind = savedBind == null ? "" : savedBind.trim();
        String savedAddress = addressPart(launcherRows.get(LAUNCHER_ROW_ADVERTISE));
        if (savedBind.isEmpty() || savedAddress.isEmpty()) {
            return false;
        }
        String bind = submittedBindHost == null ? "" : submittedBindHost.trim();
        return bind.equals(savedBind) && addressPart(submittedAdvertise).equals(savedAddress);
    }

    /** The address of {@code host:port} text - the text
     * before the LAST colon (the whole text when none), trimmed; null = "". */
    static String addressPart(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        String t = endpoint.trim();
        int colon = t.lastIndexOf(':');
        return (colon < 0 ? t : t.substring(0, colon)).trim();
    }

    /**
     * {@code spawn} preceded by
     * {@link #writeLauncherRow}. WHY the spawn seam: a package Start's ONE spawn is the launcher
     * ({@link StartChain#startPackage}), reached only after the busy probe, every refusal and the
     * config writes - so a refused Start saves nothing, and the row is on disk BEFORE the launcher
     * reads it. The web console's package Start wraps its spawn with the Game folder field
     * ({@code OpsConsole}); a failed write fails the press (the console's "Start failed" answer).
     */
    static SpawnFn savingLauncherRow(SpawnFn spawn, Path file, String key, String fieldText) {
        return cmd -> {
            try {
                writeLauncherRow(file, key, fieldText);
            } catch (IOException e) {
                throw new IOException("could not save " + key + " into " + file + " ("
                        + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
            }
            return spawn.spawn(cmd);
        };
    }

    /**
     * The FRONT-OF-FLOW probe, shell-agnostic, run
     * before any validation or write — true = busy, {@code why} names which: (iii) a Start of this
     * JVM is still staging ({@link #STAGING}; checked FIRST so a press during our own chain never
     * touches the lock its launcher is about to take); (i) a launcher holds
     * {@code <pkg>/run/serve.lock} ({@code FileChannel.tryLock}; a won lock is released at once,
     * the launcher's own lock is {@code ServeLauncher}'s {@code CREATE}+{@code tryLock} twin);
     * (ii) an orphaned serve still writes {@code <pkg>/run/ops/ops-status.txt} — the raw
     * {@code pid=} / {@code stamp_ms=} rows of its first 64 KB under {@link
     * ServerOpsCore#writerAlive} (the writer-liveness rule; never {@code OpsSnapshot.parse}, which
     * throws with raw row text). Missing / unreadable files = not busy (counted).
     */
    static boolean serveBusy(Path pkgRoot, StringBuilder why) {
        if (STAGING.get()) {
            why.append("a Start is already staging the server package - wait for it to launch"
                    + " or fail (log: ").append(OpsRoots.get().startLogText()).append(")");
            return true;
        }
        if (stageHeldElsewhere(pkgRoot)) {
            why.append("another console is staging this server package (")
                    .append(OpsRoots.get().stageLock())
                    .append(" is held) - wait for it to launch or fail");
            return true;
        }
        Path run = pkgRoot.resolve(ServeLauncher.RUN_DIR);
        if (lockHeld(pkgRoot)) {
            why.append("launcher running: the package lock ")
                    .append(run.resolve(ServeLauncher.LOCK_FILE))
                    .append(" is held - Kill it (or run stop.bat / stop.sh) first");
            return true;
        }
        long pid = -1;
        long stamp = -1;
        String head = LaneFollow.readHead(run.resolve(ServeLauncher.OPS_DIR)
                .resolve(ServeLauncher.OPS_STATUS_FILE), 65_536);
        for (String line : head.lines().toList()) {
            if (pid < 0 && PID_ROW.matcher(line).matches()) {
                pid = Long.parseLong(line.substring(4));
            } else if (stamp < 0 && STAMP_ROW.matcher(line).matches()) {
                stamp = Long.parseLong(line.substring(9));
            }
        }
        if (pid > 0 && stamp >= 0 && writerAlive(pid, stamp)) {
            why.append("serve writing (pid ").append(pid).append("): a server from an earlier"
                    + " Start is still running - Kill it first");
            return true;
        }
        return false;
    }

    /**
     * Leg (i) of {@link #serveBusy} as its own question (so the chain can ask it too):
     * does a launcher HOLD {@code <pkg>/run/serve.lock}? {@code FileChannel.tryLock} on a
     * {@code CREATE}+{@code WRITE} channel = {@code ServeLauncher}'s own twin; a lock won here
     * is released at once (the channel closes); a hold by THIS JVM
     * ({@code OverlappingFileLockException}: the selftests' way of playing the launcher) reads as
     * held. No {@code run/} folder yet (the launcher creates it right before it locks) or an
     * unopenable file = NOT held, counted in {@link #CHAIN_QUIET_FAILURES} — the probe fails
     * open there on purpose: a live launcher still holds the lock the launcher itself refuses on.
     */
    static boolean lockHeld(Path pkgRoot) {
        Path run = pkgRoot.resolve(ServeLauncher.RUN_DIR);
        return Files.isDirectory(run) && lockFileHeld(run.resolve(ServeLauncher.LOCK_FILE));
    }

    /** Leg (iv) of {@link #serveBusy}: another console's {@link #STAGE_LOCK_FILE}
     * hold — the same question on the live record's {@link OpsRoots#stageLock}. */
    static boolean stageHeldElsewhere(Path pkgRoot) {
        return Files.isDirectory(pkgRoot) && lockFileHeld(OpsRoots.get().stageLock());
    }

    /** The one lock question: {@code tryLock} on a {@code CREATE}+{@code WRITE} channel over
     * {@code file}; a won lock is released at once (the channel closes). */
    static boolean lockFileHeld(Path file) {
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock won;
            try {
                won = ch.tryLock();
            } catch (OverlappingFileLockException heldInThisJvm) {
                won = null;
            }
            if (won == null) {
                return true;
            }
            won.release();
            return false;
        } catch (IOException | RuntimeException unopenable) {
            CHAIN_QUIET_FAILURES.incrementAndGet();
            return false;
        }
    }

    /**
     * The chain's bounded wait, after the launcher spawn
     * and before {@link #STAGING} clears, for the launcher to HOLD the package lock — the moment
     * from which {@link #serveBusy}'s lock leg (and the stage tool's own step-0 probe) refuse a
     * second Start without this flag. Ends, in this order of checks each {@link
     * #LAUNCHER_LOCK_POLL_MS}: the lock is seen held (true, quiet: the start log already names
     * the launcher); the launcher is dead (false; its exit is SAID — the FAILED render carries
     * the rest); {@link #LAUNCHER_LOCK_WAIT_MS} passed (false, SAID: from here only the lock
     * guards). An interrupt ends it as the ceiling would, without the line.
     */
    static boolean awaitLauncherLock(Process launcher, Path pkgRoot, Consumer<String> publish) {
        long waitMs = LAUNCHER_LOCK_WAIT_MS.get();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        while (true) {
            if (lockHeld(pkgRoot)) {
                return true;
            }
            if (!launcher.isAlive()) {
                publish.accept("launcher " + pidText(launcher) + " exited "
                        + launcher.exitValue()
                        + " before taking the package lock - no server started"
                        + " (its last lines above say why)");
                return false;
            }
            if (System.nanoTime() >= deadline) {
                publish.accept("launcher " + pidText(launcher) + " has not taken "
                        + ServeLauncher.RUN_DIR + "/" + ServeLauncher.LOCK_FILE + " within "
                        + waitMs + " ms - a second Start is refused only while that lock is"
                        + " held");
                return false;
            }
            try {
                Thread.sleep(LAUNCHER_LOCK_POLL_MS);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static final Pattern PID_ROW = Pattern.compile("pid=\\d{1,18}");
    private static final Pattern STAMP_ROW = Pattern.compile("stamp_ms=\\d{1,18}");
    private static final Pattern SNAPROOT_ROW = Pattern.compile("snaproot=(.+)");

    /**
     * ONE daemon thread per Start — the stage's
     * {@code waitFor} ({@link #STAGE_CEILING_S}; past it {@code destroyForcibly}, exit −1) then
     * {@link #advance}. A thread because Swing's {@code pressStart} runs ON the EDT (the lease
     * pull's {@code invokeLater} callback) and the web's under its start lock: a 20–40 s stage
     * must block neither; {@code runStartFlow} itself stays synchronous for the selftest rows.
     */
    static final class StartChain {
        private StartChain() {
        }

        static void start(Process stage, SpawnFn spawn, Path pkgRoot, Path configAbs) {
            CURRENT_STAGE.set(stage); // the exit hook's target until advance ends
            armStageHook();
            LaneFollow.LaneLaunch launch = LaneFollow.LaneLaunch.publicServe(
                    System.currentTimeMillis(), stage);
            Path log = chainLog();
            Consumer<String> publish = line -> appendChainLine(log, line);
            Thread t = new Thread(() -> {
                Integer exit;
                try {
                    if (stage.waitFor(STAGE_CEILING_S, TimeUnit.SECONDS)) {
                        exit = stage.exitValue();
                    } else {
                        stage.destroyForcibly();
                        exit = -1;
                    }
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    stage.destroyForcibly();
                    exit = -1;
                } catch (RuntimeException odd) {
                    exit = -1;
                }
                advance(launch, stage, exit, spawn, pkgRoot, configAbs, publish);
            }, "cc-ops-start-chain");
            t.setDaemon(true);
            t.start();
        }

        /**
         * The chain's decision, synchronous + fake-driven from the rows: exit 0 → the stage
         * receipt's {@code snaproot=} (an absolute snapshot root holding {@code build.stamp};
         * missing / bad = a published failure, NO launcher) → spawn {@link #launchCommand}
         * through the SAME {@code spawn} → {@code launch.current()} = the launcher → write
         * {@code <snaproot>/owner.pid} = its pid (ASCII, no newline: {@code
         * Set-CcRunSnapshotOwner}'s twin, the SECOND stamp after the stage's GUI-pid one)
         * → publish {@code launcher pid N}. Exit ≠ 0 (−1 = the ceiling): publish the failure only
         * (the FAILED render reads {@code exitCodeOrNull()} off the stage). A spawned launcher
         * is then WAITED on until it holds the package lock ({@link #awaitLauncherLock}).
         * {@link #STAGING} is cleared in {@code finally}, every path.
         */
        static void advance(LaneFollow.LaneLaunch launch, Process stage, Integer stageExit,
                SpawnFn spawn, Path pkgRoot, Path configAbs, Consumer<String> publish) {
            try {
                if (stageExit == null || stageExit != 0) {
                    publish.accept(stageExit != null && stageExit == -1
                            ? "the stage (" + pidText(stage) + ") ran past " + STAGE_CEILING_S
                                    + " s and was ended - no launcher started"
                            : "the stage (" + pidText(stage) + ") exited " + stageExit
                                    + " - no launcher started (its last lines above say why)");
                    return;
                }
                Path receipt = pkgRoot.resolve(STAGE_RECEIPT);
                Path snap = null;
                for (String line : LaneFollow.readHead(receipt, 16_384).lines().toList()) {
                    Matcher m = SNAPROOT_ROW.matcher(line.trim());
                    if (m.matches()) {
                        try {
                            snap = Paths.get(m.group(1).trim());
                        } catch (RuntimeException badPath) {
                            snap = null;
                        }
                        break;
                    }
                }
                if (snap == null || !snap.isAbsolute() || !isSnapshotRoot(snap)) {
                    publish.accept("the stage exited 0 but " + receipt + " names no run snapshot"
                            + " (a snaproot= row at a root holding build.stamp) - no launcher"
                            + " started");
                    return;
                }
                Process launcher;
                try {
                    launcher = spawn.spawn(launchCommand(pkgRoot, configAbs));
                } catch (IOException | RuntimeException noLaunch) {
                    publish.accept("the launcher did not start ("
                            + noLaunch.getClass().getSimpleName() + ")");
                    return;
                }
                if (launcher == null) {
                    return; // a recording spawn: nothing to follow or stamp
                }
                launch.current().set(launcher);
                try {
                    stampSnapshotOwner(snap, launcher.pid());
                    publish.accept("launcher " + pidText(launcher));
                } catch (IOException | RuntimeException noStamp) {
                    publish.accept("launcher " + pidText(launcher) + " started, but the snapshot"
                            + " owner stamp failed (" + noStamp.getClass().getSimpleName()
                            + ") - the snapshot reaper may treat it as unowned once this window"
                            + " closes");
                }
                // STAGING stays set until the launcher holds run/serve.lock (or died,
                // or the wait ran out) - the probe's lock leg takes over from here.
                awaitLauncherLock(launcher, pkgRoot, publish);
            } finally {
                CURRENT_STAGE.compareAndSet(stage, null); // only THIS chain's stage
                endStaging();
            }
        }

        /**
         * Package mode's Start — a
         * package has no stage tool and no repo, so the press spawns {@link #launchCommand} (pwsh-free)
         * SYNCHRONOUSLY through {@code spawn}: the shells' spawn wrappers capture that launcher as the
         * launch's {@code current} themselves (the only spawn, nothing to swap). A {@code null} process
         * (a recording seam) ends the staging hold at once. Otherwise ONE daemon thread
         * ({@code cc-ops-start-chain}, never the EDT, never under the web's start lock: the press
         * returns after the spawn) waits for the launcher to hold {@code run/serve.lock}
         * ({@link #awaitLauncherLock}, its {@link #LAUNCHER_LOCK_WAIT_MS} budget) and ends the
         * staging hold in {@code finally}, every path. NO {@code stage.txt}, NO {@code owner.pid}
         * stamp (no run snapshot exists), NO provenance gate, no stage-side mods sync (the LAUNCHER
         * mirrors the host's Mods rows into serve-mods/ before life 1: {@link PackageModSync}),
         * NEVER a {@code serve.argv} re-mint (a rewrite trips the launcher's exit 3 parity refusal).
         *
         * @throws IOException the launcher could not be spawned (the caller fails the press and its
         *                     wrapper ends the staging hold)
         */
        static void startPackage(SpawnFn spawn, Path pkgRoot, Path configAbs) throws IOException {
            Path log = chainLog();
            Consumer<String> publish = line -> appendChainLine(log, line);
            Process launcher = spawn.spawn(launchCommand(pkgRoot, configAbs));
            if (launcher == null) {
                endStaging();
                publish.accept("launcher spawn recorded (no process) - staging released");
                return;
            }
            publish.accept("launcher " + pidText(launcher) + " (package mode: no stage)");
            try {
                Thread t = new Thread(() -> {
                    try {
                        awaitLauncherLock(launcher, pkgRoot, publish);
                    } finally {
                        endStaging();
                    }
                }, "cc-ops-start-chain");
                t.setDaemon(true);
                t.start();
            } catch (RuntimeException | OutOfMemoryError noThread) {
                endStaging(); // the launcher runs; only the lock-wait window is lost (said)
                publish.accept("launcher " + pidText(launcher) + " started, but its lock wait did"
                        + " not (" + noThread.getClass().getSimpleName() + ") - a second Start is"
                        + " refused only once it holds the package lock");
            }
        }
    }

    /** A run-snapshot root = it holds {@code build.stamp} ({@code Set-CcRunSnapshotOwner}'s own
     * check). The chain's file I/O lives on this outer class: the sim-residency gate allows
     * {@code ServerOpsLaunch} (the operator console), a nested class would need its own row. */
    static boolean isSnapshotRoot(Path snap) {
        return Files.isRegularFile(snap.resolve("build.stamp"));
    }

    /** {@code <snap>/owner.pid} = {@code pid} (ASCII, no newline: the pwsh twin's bytes). */
    static void stampSnapshotOwner(Path snap, long pid) throws IOException {
        Files.write(snap.resolve("owner.pid"),
                Long.toString(pid).getBytes(StandardCharsets.US_ASCII));
    }

    /** {@code pid N}, or {@code pid ?} for a process that cannot say. */
    static String pidText(Process p) {
        try {
            return "pid " + p.pid();
        } catch (RuntimeException unsupported) {
            return "pid ?";
        }
    }

    /** One {@code ops-start: <line>} appended to {@code log} (ASCII-bounded, ≤ 400 chars: the
     * texts are ours, but the receipt path inside may be a local path; the log is the operator's). */
    static void appendChainLine(Path log, String line) {
        StringBuilder sb = new StringBuilder("ops-start: ");
        for (int i = 0; i < line.length() && sb.length() < 400; i++) {
            char c = line.charAt(i);
            sb.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        sb.append(System.lineSeparator());
        try {
            Path parent = log.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(log, sb, StandardCharsets.US_ASCII, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException unwritable) {
            CHAIN_QUIET_FAILURES.incrementAndGet();
        }
    }

    /**
     * The ENTIRE Start orchestration, moved verbatim from the Swing listener (it
     * used to live there un-gated; the selftest now pins decisions AND written bytes):
     * [the {@link #serveBusy} probe FIRST, then the stage-tool exists check]
     * validation (bind / tick / password — a typo becomes a
     * {@code ui.fail}, never a half-started server), ONE fresh properties read feeding the
     * {@code withTickRateRow → withDescriptorRow} compose, the sidecar decision resolved off
     * {@code propsWant} (the switched row: password follows the shape pick by construction),
     * the no-descriptor-row password refusal (never mint an orphaned armed sidecar), the
     * REMOVE and armed-door-missing-fingerprint confirms — ALL gathered BEFORE the first
     * write, so a Cancel/refused confirm aborts with the tree byte-untouched — then the
     * no-change-no-write props landing (line separator preserved), the sidecar landing, the
     * STAGE spawn + its {@link StartChain} (which spawns the launcher when the stage succeeds),
     * and the start-requested detail via {@code ui.info}.
     *
     * @param selDescriptor selected descriptor path ({@code null} = boot the row as-is)
     * @param selInfo       the selection's parsed identity ({@code null} exactly when
     *                      {@code selDescriptor} is — the {@link DescriptorOption} invariant)
     * @return true iff the spawn was requested
     */
    public static boolean runStartFlow(Ui ui, SpawnFn spawn, Path propsFile, String bindHost,
            String bindPort, String tickText, String fortressDpText, String passwordText,
            String selDescriptor, DescriptorInfo selInfo) {
        // The older 10-arg Start surface (the
        // existing goldens call this) delegates with a blank joiner default - the descriptor
        // roster row IS the standing default, so a blank write leaves a ROW-LESS file untouched.
        // A blank REMOVES a present match.joinerDefaultVariant row (withJoinerDefaultRow),
        // so a shell with no such field passes null through the record entry instead (OpsConsole:
        // a Start form without joinerDefault = null, OpsConsoleForms.first).
        return runStartFlow(ui, spawn, propsFile, bindHost, bindPort, tickText, fortressDpText,
                passwordText, selDescriptor, selInfo, "");
    }

    /** Overload: the same Start flow, plus the joiner-default-ship field (a blank field
     * clears to the descriptor roster default; a typed id passes {@link OpsMatchRows#parseJoinerDefault}'s
     * charset/length law and lands the {@code match.joinerDefaultVariant} row before the spawn -
     * a bad token aborts pre-write via the fortressDp reject idiom). */
    public static boolean runStartFlow(Ui ui, SpawnFn spawn, Path propsFile, String bindHost,
            String bindPort, String tickText, String fortressDpText, String passwordText,
            String selDescriptor, DescriptorInfo selInfo, String joinerDefaultText) {
        // Shells without a wave-scale field pass null = leave that row alone.
        return runStartFlow(ui, spawn, propsFile, bindHost, bindPort, tickText, fortressDpText,
                passwordText, selDescriptor, selInfo, joinerDefaultText, null);
    }

    /** Overload: the same Start flow, plus the fortress
     * wave-scale field — {@code null} = no field on this shell (row untouched); blank clears
     * the row (x1); a typed list passes {@link OpsMatchRows#parseFortressWaveScaleRow} + the AUTO
     * cross-field refusal ({@link OpsMatchRows#requireFortressWaveScaleAllowed}) and lands the
     * {@code match.fortressWaveScale} row right after the fortress-DP row — a bad value aborts
     * pre-write via the fortressDp reject idiom. */
    public static boolean runStartFlow(Ui ui, SpawnFn spawn, Path propsFile, String bindHost,
            String bindPort, String tickText, String fortressDpText, String passwordText,
            String selDescriptor, DescriptorInfo selInfo, String joinerDefaultText,
            String fortressWaveScaleText) {
        // Shells without an admin-password value (OpsConsole: adminAction keep) pass null = the
        // admin sidecar is KEPT as-is while the match password stays set (a web Start never
        // deletes a Swing-set admin credential then). A blank match password removes it (D2: no
        // admin secret without a match door), and the confirm that Start asks names the file.
        //
        return runStartFlow(ui, spawn, propsFile, bindHost, bindPort, tickText, fortressDpText,
                passwordText, selDescriptor, selInfo, joinerDefaultText, fortressWaveScaleText,
                null);
    }

    /**
     * Overload: the same Start flow, plus the admin-password field — {@code null}
     * = no field on this shell (admin sidecar untouched); blank = REMOVE a present
     * {@code <descriptor>.adminpassword} with NO confirm (blank admin = no admin seat, harmless);
     * a typed secret passes {@link ServerOpsCore#sanitizedAdminPassword} + two cross-field
     * refusals (needs a match password; must differ from it — the serve's {@code
     * mintAdminPassword} D2 laws, surfaced as dialogs instead of a boot refusal) and lands the
     * sidecar beside the match one, same KEEP/WRITE/REMOVE idiom. A token-mode Start (match
     * blank) always removes a present admin sidecar whatever the field says — the serve refuses
     * to boot an admin secret with no match door — and its token-mode confirm names that file.
     */
    public static boolean runStartFlow(Ui ui, SpawnFn spawn, Path propsFile, String bindHost,
            String bindPort, String tickText, String fortressDpText, String passwordText,
            String selDescriptor, DescriptorInfo selInfo, String joinerDefaultText,
            String fortressWaveScaleText, String adminPasswordText) {
        // Shells without a wave-category control pass null =
        // leave that row alone (the wave-scale null law).
        return runStartFlow(ui, spawn, propsFile, bindHost, bindPort, tickText, fortressDpText,
                passwordText, selDescriptor, selInfo, joinerDefaultText, fortressWaveScaleText,
                adminPasswordText, null);
    }

    /** Overload: the same Start flow, plus the wave-category pick — {@code null}
     * = no control on this shell (row untouched); {@code remnant} (the combo default) clears
     * the row via {@link OpsMatchRows#parseWaveCategoryRow}'s default-identity fold; any other
     * category lands the {@code match.waveCategory} row after the wave-scale row — a bad value
     * aborts pre-write via the fortressDp reject idiom (unreachable from the fixed-set combo,
     * live for any future text caller). */
    public static boolean runStartFlow(Ui ui, SpawnFn spawn, Path propsFile, String bindHost,
            String bindPort, String tickText, String fortressDpText, String passwordText,
            String selDescriptor, DescriptorInfo selInfo, String joinerDefaultText,
            String fortressWaveScaleText, String adminPasswordText, String waveCategoryText) {
        // Shells without an endless control
        // pass null = leave that row alone (the wave-category null law).
        return runStartFlow(ui, spawn, propsFile, bindHost, bindPort, tickText, fortressDpText,
                passwordText, selDescriptor, selInfo, joinerDefaultText, fortressWaveScaleText,
                adminPasswordText, waveCategoryText, null);
    }

    /** Overload: the same Start flow, plus the endless
     * pick — {@code null} = no control on this shell (row untouched); {@code on}/{@code off}
     * (blank = {@code on}) land the BOOLEAN {@code match.endless} row via
     * {@link OpsMatchRows#parseEndlessRow} (on -> {@code true}, off -> {@code false}: the knob
     * the serve resolves at boot is strictly boolean), any other text aborts pre-write through
     * the fortressDp reject idiom (unreachable from the fixed-set combo, live for any future
     * text caller). */
    public static boolean runStartFlow(Ui ui, SpawnFn spawn, Path propsFile, String bindHost,
            String bindPort, String tickText, String fortressDpText, String passwordText,
            String selDescriptor, DescriptorInfo selInfo, String joinerDefaultText,
            String fortressWaveScaleText, String adminPasswordText, String waveCategoryText,
            String endlessText) {
        // Shells without a mods control (the older
        // goldens) pass null = leave the mods.enabled row alone (the endless null law).
        // + null mods.optional (row left alone, same law).
        return runStartFlow(ui, spawn, propsFile, bindHost, bindPort, tickText, fortressDpText,
                passwordText, selDescriptor, selInfo, joinerDefaultText, fortressWaveScaleText,
                adminPasswordText, waveCategoryText, endlessText, null, null);
    }

    /** Overload: the same Start flow, plus the serve
     * mod-set field — {@code null} = no control on this shell (the {@code mods.enabled} row
     * untouched); blank REMOVES the row (a vanilla-only serve, the default); a comma/semicolon
     * list passes {@link OpsMatchRows#parseModsEnabled} (the ModSet id law; {@code cc_client} and
     * duplicates refused) and lands the canonical row before the spawn — a bad token aborts
     * pre-write via the fortressDp reject idiom. The launcher {@code serve-mods-lib.ps1} resolves
     * each id to a folder under {@code mods.roots} fail-closed; only the text law is gated here.
     *
     * <p>+ {@code modsOptionalText} (the host mods
     * panel's "optional" picks) — {@code null} = no control (row untouched), blank removes the
     * {@code mods.optional} row, a list passes {@link OpsMatchRows#parseModsOptional}; an id in
     * BOTH lists is refused pre-write ({@link OpsMatchRows#modsBothRefusalOrNull}); both rows ride
     * the ONE props write. */
    public static boolean runStartFlow(Ui ui, SpawnFn spawn, Path propsFile, String bindHost,
            String bindPort, String tickText, String fortressDpText, String passwordText,
            String selDescriptor, DescriptorInfo selInfo, String joinerDefaultText,
            String fortressWaveScaleText, String adminPasswordText, String waveCategoryText,
            String endlessText, String modsEnabledText, String modsOptionalText) {
        // The widest positional tier is now a
        // golden-preserving DELEGATE (the ServerOpsCoreSelftest goldens keep their
        // shape); null terrain = no control on this shell = the terrain rows stay untouched.
        return runStartFlow(ui, spawn, propsFile, new OpsWindowModel.StartFields(bindHost,
                bindPort, tickText, fortressDpText, passwordText, selDescriptor, selInfo,
                joinerDefaultText, fortressWaveScaleText, adminPasswordText, waveCategoryText,
                endlessText, modsEnabledText, modsOptionalText, null, null,
                null)); // null asteroids = row untouched
    }

    /**
     * The ONE full-arity Start entry — it HOLDS the
     * body every positional overload above delegates to. WHY: the telescoping ladder
     * ({@code deferred-refactors.md} "runStartFlow telescoping-overload ladder",
     * FIRE = the next arg addition) is RETIRED at the terrain addition: {@link
     * OpsWindowModel.StartFields} is the params object, so a future Start field is one record
     * component + one read here, never a new positional tier. The positional overloads survive
     * ONLY as golden-preserving delegates (the core selftest goldens, their only caller since the
     * July web shell's deletion); new callers pass the record. Field laws are the
     * overloads' javadocs above (each {@code null} = no control on that shell = its row untouched).
     *
     * <p>{@code terrainText} / {@code terrainLevelText} — {@code null} word =
     * no control (the {@code match.terrain} / {@code match.terrainLevel} rows untouched); a word
     * passes {@link OpsMatchRows#parseTerrainWord} (+ the level {@link
     * OpsMatchRows#parseTerrainLevel}, blank = 100) and lands both rows ({@code none} removes
     * them) in the ONE props write; a bad word aborts pre-write via the fortressDp reject idiom.
     *
     * <p>{@code asteroidsText} — {@code null} = no control (the
     * {@code match.asteroids} row untouched); a word passes {@link OpsMatchRows#parseAsteroidsWord}
     * (blank = none) and lands the row ({@code none} removes it) in the same ONE props write; a bad
     * word aborts pre-write.
     *
     *
     * <p>{@code waveRosterFullDpText} — {@code null} = no field (the
     * {@code match.waveRosterFullDp} row untouched, but a SET row beside a fortressDp-0 Start is
     * still refused pre-write: the serve would die on it); text passes
     * {@link OpsMatchRows#parseWaveRosterFullDpRow} (strict whole int 0..5000, 0/blank removes
     * the row) + {@link OpsMatchRows#requireWaveRosterFullDpAllowed} and lands in the ONE props
     * write after the wave-scale row. No new positional tier (the ladder stays retired): the
     * pre-1408 record shape delegates with {@code null}.
     *
     *
     * <p>{@code waveRosterMultiplierText} — {@code null} = no field (the
     * {@code match.waveRosterMultiplier} row untouched, but a SET row is still refused pre-write
     * when the roster DP the file WILL carry is off: the serve would die on it); text passes
     * {@link OpsMatchRows#parseWaveRosterMultiplierRow} (a plain decimal 0.1..10, 1/blank removes
     * the row) + {@link OpsMatchRows#requireWaveRosterMultiplierAllowed} against the EFFECTIVE
     * roster DP (the typed field, else the file's lenient row) and lands in the ONE props write
     * right after the roster-DP row.
     *
     *
     * <p>{@code seatsPerSourceText} / {@code allSkillsText} — {@code null} = no field (the
     * {@code moderation.maxSeatsPerSource} / {@code server.officerSkillsAll} row untouched); text
     * passes {@link OpsMatchRows#parseSeatsPerSource} (a whole 0..255, blank refused) /
     * {@link OpsMatchRows#parseAllSkills} (on|off) and is UPSERTED ({@link OpsMatchRows#upsertRow}:
     * written at every value, never removed) in the ONE props write; a bad text aborts pre-write.
     *
     *
     * <p>{@code rngPinsText} — the same law for the replay-pins row
     * ({@link OpsMatchRows#RNG_PINS_KEY}, {@link OpsMatchRows#parseRngPins} on|off): the row is
     * only WRITTEN here; the serve JVM's startup reads the file and decides, so no launch argument
     * is composed from it ({@code serve.argv} stays as packed).
     *
     *
     * <p>{@code magicSeamText} — the same law for the MagicLib row
     * ({@link OpsMatchRows#MAGIC_SEAM_KEY}, {@link OpsMatchRows#parseMagicSeam} on|off): parsed
     * before any write, upserted in the ONE props write, read only by the serve JVM's startup.
     *
     *
     * <p>{@code pilotShipText} — the same law for the player-ship row
     * ({@link OpsMatchRows#PILOT_SHIP_KEY}, {@link OpsMatchRows#parsePilotShip} on|off): parsed
     * before any write, upserted in the ONE props write, read only by the serve JVM's startup.
     *
     */
    public static boolean runStartFlow(Ui ui, SpawnFn spawn, Path propsFile,
            OpsWindowModel.StartFields f) {
        // The
        // busy probe FIRST - a running launcher, an orphaned writer, a Start still staging in this
        // JVM or ANOTHER console's stage.lock hold refuses BEFORE any validation or write (the
        // relaunch gap would boot the next life on a rewritten file). Then STAGING
        // + the cross-JVM hold are taken HERE, before the writes; the body hands them to the
        // chain on success, and every refusal, failure or throw after the probe returns them in
        // the finally (never another Start's: a probe refusal returns before beginStaging).
        Path pkg = pkgRoot();
        StringBuilder busy = new StringBuilder();
        if (serveBusy(pkg, busy)) {
            ui.fail("Start refused", busy.toString());
            return false;
        }
        if (!beginStaging(pkg)) {
            ui.fail("Start refused", "another console began staging this server package just"
                    + " now - wait for it to launch or fail");
            return false;
        }
        boolean handed = false;
        try {
            handed = runStartFlowHeld(ui, spawn, propsFile, f, pkg);
            return handed;
        } finally {
            if (!handed) {
                endStaging();
            }
        }
    }

    /** The flow after the probe + the staging hold ({@link #runStartFlow}'s body): validation,
     * confirms, the props + sidecar writes, the stage spawn, the chain (package mode: the bind
     * rows in the props write, then {@link StartChain#startPackage} instead of the stage). True =
     * the chain (or the null-spawn path) now owns the staging state; false = the wrapper ends it. */
    private static boolean runStartFlowHeld(Ui ui, SpawnFn spawn, Path propsFile,
            OpsWindowModel.StartFields f, Path pkg) {
        String bindHost = f.bindHost();
        String bindPort = f.bindPort();
        String tickText = f.tickText();
        String fortressDpText = f.fortressDpText();
        String passwordText = f.passwordText();
        String selDescriptor = f.selDescriptor();
        DescriptorInfo selInfo = f.selInfo();
        String joinerDefaultText = f.joinerDefaultText();
        String fortressWaveScaleText = f.fortressWaveScaleText();
        String adminPasswordText = f.adminPasswordText();
        String waveCategoryText = f.waveCategoryText();
        String endlessText = f.endlessText();
        String modsEnabledText = f.modsEnabledText();
        String modsOptionalText = f.modsOptionalText();
        // ONE mode read per press -
        // owner mode checks for its stage tool; a package has neither the tool nor a repo.
        boolean owner = OpsRoots.get().mode() == OpsRoots.Mode.OWNER;
        Path script = Paths.get(STAGE_TOOL);
        if (owner && !Files.isRegularFile(script)) {
            ui.fail("Start failed", "Cannot find " + script.toAbsolutePath()
                    + "\nRun this window from the repo root.");
            return false;
        }
        int port;
        int tickRate;
        int fortressDp;
        String matchPassword;
        String adminPassword; // null = no field on this shell
        String joinerDefault; //
        String fortressWaveScale;
        String waveCategory; // null = no control on this shell
        String endless; // null = no control on this shell
        String modsEnabled; // (null = no control here)
        String modsOptional; // (null = no control here)
        String terrainWord; // (null = no control here)
        String terrainLevel; //
        String asteroidsWord; // (null = no control here)
        String waveRosterFullDp; // (null = no field here)
        String waveRosterMultiplier; // null = no field here
        Integer seatsPerSource; // null = no field here (the row untouched)
        String allSkills; // "true" | "false"; null = no control here (the row untouched)
        String rngPins; // "true" | "false"; null = no control here (the row untouched)
        String magicSeam; // "true" | "false"; null = no control here (the row untouched)
        String pilotShip; // "true" | "false"; null = no control here (the row untouched)
        Boolean pvpWant; // the PvP select; null = no control here (the picked file's mode stands)
        try {
            validateBind(bindHost, bindPort);
            port = Integer.parseInt(bindPort.trim());
            tickRate = SimTickRate.parse(tickText);
            fortressDp = parseFortressDp(fortressDpText);
            matchPassword = sanitizedMatchPassword(passwordText);
            // A package has ONE match password - a blank
            // field resolves to the KEPT one (the standing row's sidecar, ServerOpsCore.packageKeptPassword), which
            // the sidecar decision below then writes beside the PICK; a kept "" still meets the refusal below.
            if (!owner && matchPassword.isEmpty()) {
                matchPassword = sanitizedMatchPassword(ServerOpsCore.packageKeptPassword(propsFile, null));
            }
            // A package serve needs a
            // match password - its shipped descriptor is the REDACTED public copy, so the launcher
            // refuses a password-less Start (ServeLauncher.R_PASSWORD_MISSING, its redacted-descriptor
            // refusal). Refused HERE, before the sidecar decision: the owner
            // flow's blank-password REMOVE confirm would otherwise DELETE the host's own .password
            // after a yes, and the launcher would then refuse every serve. Nothing is written, no
            // confirm asked; every Start reaches this (OpsConsole.handleStart, the press rehearsal via
            // OpsWindowModel.pressStart). Owner mode keeps its token-mode confirms.
            if (!owner && matchPassword.isEmpty()) {
                ui.fail("Start refused", PACKAGE_PASSWORD_REQUIRED);
                return false;
            }
            // A package's set launcher.properties
            // bind row overrides the bind pair this Start writes into the config (the box port
            // wins, the box host rides the env tier), so it is refused HERE, before any write or
            // confirm. The rows are read the way the launcher reads them (readLauncherRows: UTF-8,
            // BOM, quotes; a missing or unreadable file = no rows) from the package root this
            // press staged (pkg = OpsRoots.get().pkg()), never this JVM's cwd. Blank rows pass.
            if (!owner) {
                Map<String, String> box = ServeLauncher.readLauncherRows(
                        pkg.resolve(ServeLauncher.DEFAULT_LAUNCHER_PROPERTIES), System.out);
                // "set" = the launcher's own test (getOrDefault(row, "").trim() non-empty)
                if (!box.getOrDefault(ServeLauncher.ROW_BIND_HOST, "").trim().isEmpty()
                        || !box.getOrDefault(ServeLauncher.ROW_BIND_PORT, "").trim().isEmpty()) {
                    ui.fail("Start refused", PACKAGE_BOX_ROWS_SET);
                    return false;
                }
            }
            adminPassword = adminPasswordText == null ? null
                    : sanitizedAdminPassword(adminPasswordText);
            // The admin twin - a package's KEEP (null field)
            // carries the kept admin password beside the pick; "" stays the explicit remove.
            // A kept "" is ALSO the kept value - it becomes the explicit remove for the
            // pick, so a stale <pick>.adminpassword never survives ARMED while the standing row has no admin
            // (the one-password law's twin: every pick reads the ONE kept admin, absent included).
            if (!owner && adminPassword == null) {
                adminPassword = sanitizedAdminPassword(ServerOpsCore.packageKeptAdminPassword(propsFile, null));
            }
            if (adminPassword != null && !adminPassword.isEmpty()) {
                // The admin-password boot laws as field-facing refusals (the serve would refuse to boot).
                if (matchPassword.isEmpty()) {
                    throw new IllegalArgumentException("The admin password needs a match"
                            + " password: the admin joins by typing the admin password where the"
                            + " match password goes, so the door must be in password mode."
                            + "\nSet a match password or blank the admin field.");
                }
                if (adminPassword.equals(matchPassword)) {
                    throw new IllegalArgumentException("The admin password must differ from the"
                            + " match password (equal = everyone who joins is admin).");
                }
            }
            // null = no joiner-default field on this shell (an OpsConsole form without it)
            // = the match.joinerDefaultVariant row untouched (the wave-scale null law); blank
            // still clears to the roster default.
            joinerDefault = joinerDefaultText == null ? null : parseJoinerDefault(joinerDefaultText);
            fortressWaveScale = fortressWaveScaleText == null ? null
                    : parseFortressWaveScaleRow(fortressWaveScaleText);
            requireFortressWaveScaleAllowed(fortressDp, fortressWaveScale);
            waveCategory = waveCategoryText == null ? null
                    : parseWaveCategoryRow(waveCategoryText); //
            // The picked word -> the knob's boolean row.
            endless = endlessText == null ? null : parseEndlessRow(endlessText);
            // The typed list -> the canonical mods.enabled
            // value (null = no control on this shell; blank = row removed = vanilla only).
            modsEnabled = modsEnabledText == null ? null : parseModsEnabled(modsEnabledText);
            // The optional set, the same id law.
            modsOptional = modsOptionalText == null ? null : parseModsOptional(modsOptionalText);
            // The terrain word + strength, strict
            // against the WireKnob tables (a bad word is a dialog, never a half-started serve).
            terrainWord = f.terrainText() == null ? null : parseTerrainWord(f.terrainText());
            terrainLevel = f.terrainLevelText() == null ? null
                    : parseTerrainLevel(f.terrainLevelText());
            // The asteroids word, strict against the
            // WireKnob table (thrown BEFORE any write).
            asteroidsWord = f.asteroidsText() == null ? null : parseAsteroidsWord(f.asteroidsText());
            // The live-roster wave DP, strict whole int
            // 0..5000 (0/blank = "" = static, row removed); the AUTO cross-field refusal against
            // a TYPED value runs here, a field-less shell's file row is checked after the read.
            waveRosterFullDp = f.waveRosterFullDpText() == null ? null
                    : parseWaveRosterFullDpRow(f.waveRosterFullDpText());
            requireWaveRosterFullDpAllowed(fortressDp, waveRosterFullDp);
            // The roster multiplier, a strict plain decimal
            // 0.1..10 (1/blank = "" = no change, row removed); refused against a TYPED roster DP
            // here, against the file's roster row (a field-less shell) after the read.
            waveRosterMultiplier = f.waveRosterMultiplierText() == null ? null
                    : parseWaveRosterMultiplierRow(f.waveRosterMultiplierText());
            if (waveRosterFullDp != null) {
                requireWaveRosterMultiplierAllowed(waveRosterFullDp, waveRosterMultiplier);
            }
            // Seats per address (whole 0..255, blank REFUSED -
            // a cleared field never turns the cap off or resets it) and All skills (on|off), both
            // thrown BEFORE any write; null = no field on this shell = the row untouched.
            seatsPerSource = f.seatsPerSourceText() == null ? null : parseSeatsPerSource(f.seatsPerSourceText());
            allSkills = f.allSkillsText() == null ? null : parseAllSkills(f.allSkillsText());
            rngPins = f.rngPinsText() == null ? null : parseRngPins(f.rngPinsText());
            magicSeam = f.magicSeamText() == null ? null : parseMagicSeam(f.magicSeamText());
            pilotShip = f.pilotShipText() == null ? null : parsePilotShip(f.pilotShipText());
            // PvP mode (on|off), thrown BEFORE any write like its neighbours
            pvpWant = f.pvpText() == null ? null : OpsMatchApply.parsePvpMode(f.pvpText());
        } catch (IllegalArgumentException bad) {
            ui.fail("Start failed", bad.getMessage());
            return false;
        }
        String propsRaw;
        try {
            propsRaw = Files.isRegularFile(propsFile)
                    ? Files.readString(propsFile, StandardCharsets.UTF_8) : null;
        } catch (IOException ex) {
            ui.fail("Start failed", "Could not read " + propsFile + ": " + ex);
            return false;
        }
        List<String> propsNow = propsRaw == null ? List.of() : propsRaw.lines().toList();
        // A shell with no roster-DP field (null) leaves
        // the file's row untouched, so the serve reads THAT row - a set row beside a Start that
        // lands fortressDp 0 (AUTO) would die at boot; refused HERE, before any write (the mods
        // required-vs-optional check's what-the-file-WILL-carry idiom).
        if (waveRosterFullDp == null) {
            try {
                requireWaveRosterFullDpAllowed(fortressDp, waveRosterFullDpRowLenient(propsNow));
            } catch (IllegalArgumentException bad) {
                ui.fail("Start failed", bad.getMessage() + " (the file's "
                        + WAVE_ROSTER_FULL_DP_KEY + " row is set)");
                return false;
            }
        }
        // The multiplier against what the file WILL carry -
        // a field-less side (null) reads the file's row leniently, so a SET multiplier row beside a
        // roster DP that lands off (typed 0, or no field over an absent row) is refused HERE,
        // before any write, instead of booting a serve that dies on its own config.
        if (waveRosterMultiplier == null || waveRosterFullDp == null) {
            try {
                requireWaveRosterMultiplierAllowed(
                        waveRosterFullDp != null ? waveRosterFullDp : waveRosterFullDpRowLenient(propsNow),
                        waveRosterMultiplier != null ? waveRosterMultiplier
                                : waveRosterMultiplierRowLenient(propsNow));
            } catch (IllegalArgumentException bad) {
                ui.fail("Start failed", bad.getMessage() + (waveRosterMultiplier == null
                        ? " (the file's " + WAVE_ROSTER_MULTIPLIER_KEY + " row is set)"
                        : " (the file has no " + WAVE_ROSTER_FULL_DP_KEY + " row above 0)"));
                return false;
            }
        }
        // The SAME law for the wave-scale row. A shell with no scale
        // field (null: an OpsConsole form without it) leaves the file's row untouched, so a Start that lands
        // fortressDp 0 (AUTO) beside a SET scale row boots a serve that dies on its own config
        // (WaveSchedule.forShape) - it shows as a six-life relaunch loop
        // that reads as a "stall". Refused HERE, before any write; the TYPED-scale check
        // above (line requireFortressWaveScaleAllowed(fortressDp, fortressWaveScale)) covers the
        // shells that carry the field.
        if (fortressWaveScale == null) {
            try {
                requireFortressWaveScaleAllowed(fortressDp, fortressWaveScaleFieldPrefill(propsNow));
            } catch (IllegalArgumentException bad) {
                ui.fail("Start failed", bad.getMessage() + " (the file's "
                        + FORTRESS_WAVE_SCALE_KEY + " row is set)");
                return false;
            }
        }
        // A mod is required OR optional, never both
        // (the serve refuses the boot, RealEngineModSetFacts / ModSet) - refused HERE, before any
        // write, against what the file WILL carry (a null list = that row untouched, so its
        // standing value is the one the serve reads).
        if (modsEnabled != null || modsOptional != null) {
            String both = modsBothRefusalOrNull(
                    modsEnabled != null ? modsEnabled : modsEnabledFieldPrefill(propsNow),
                    modsOptional != null ? modsOptional : modsOptionalFieldPrefill(propsNow));
            if (both != null) {
                ui.fail("Start failed", both);
                return false;
            }
        }
        PvpPlan pvp = pvpPlan(propsFile, selDescriptor != null ? selDescriptor : descriptorRowValue(propsNow), pvpWant);
        String pvpRefusal = pvpStartRefusalOrNull(pvp, pvpWant, owner, rowValue(propsNow, WAVE_CLEAR_ROUND_END_KEY));
        if (pvpRefusal != null) {
            ui.fail("Start refused", pvpRefusal);
            return false;
        }
        // Land the selected shape in the ONE match.descriptor row alongside the
        // tick edit — same one-write discipline; selection == current row = no edit (a
        // default start leaves the tracked file byte-identical, gate-pinned below).
        boolean shapeSwitch = descriptorSwitchNeeded(selDescriptor, descriptorRowValue(propsNow));
        List<String> propsWant = withTickRateRow(propsNow, tickRate);
        // The fortress-DP row composes right after the tick row — same
        // one-write discipline, same 0-removes-row default identity (a default Start leaves
        // the tracked file byte-identical, gate-pinned below).
        propsWant = withFortressDpRow(propsWant, fortressDp);
        // The wave-scale row composes right after the fortress-DP row it scales —
        // same one-write discipline; null (no field) or a prefilled field leaves the file's bytes.
        propsWant = withFortressWaveScaleRow(propsWant, fortressWaveScale);
        // The live-roster wave-DP row rides the SAME
        // single write right after the scale row; null (no field) leaves the file's bytes, ""
        // (0 = static) removes row + marker.
        propsWant = withWaveRosterFullDpRow(propsWant, waveRosterFullDp);
        // The multiplier row right after the roster-DP row it
        // scales; null (no field) leaves the file's bytes, "" (unit) removes row + marker.
        propsWant = withWaveRosterMultiplierRow(propsWant, waveRosterMultiplier);
        // The wave-category row composes right after the wave rows it retargets —
        // same one-write discipline, same default-identity (remnant leaves the file's bytes).
        propsWant = withWaveCategoryRow(propsWant, waveCategory);
        // The endless row composes right after the wave
        // rows it wraps — same one-write discipline; null (no control on this shell) leaves the
        // file's bytes, an explicit on/off always states the dial in the file the serve reads.
        propsWant = withEndlessRow(propsWant, endless);
        // The terrain stanza rides the SAME single
        // write; null (no control on this shell) leaves the file's bytes, none removes the rows.
        propsWant = withTerrainRows(propsWant, terrainWord, terrainLevel);
        // The asteroids row rides the SAME single write;
        // null (no control) leaves the file's bytes, none removes the row.
        propsWant = withAsteroidsRow(propsWant, asteroidsWord);
        // The joiner-default-ship row composes right after
        // the fortress row - same one-write discipline, same blank-removes-row default identity
        // (a blank field leaves a ROW-LESS tracked file byte-identical; it REMOVES a present row).
        // null (no field on this shell) leaves the file's row.
        if (joinerDefault != null) {
            propsWant = withJoinerDefaultRow(propsWant, joinerDefault);
        }
        // The serve mod-set row composes after the match
        // rows - same one-write discipline; null (no control here) leaves the file's bytes, blank
        // removes the row (vanilla only), a list states mods.enabled the launcher resolves.
        propsWant = withModsEnabledRow(propsWant, modsEnabled);
        // The optional row rides the SAME single write.
        propsWant = withModsOptionalRow(propsWant, modsOptional);
        // The seat allowance + all-skills rows ride the SAME
        // single write, ALWAYS written when the shell has the field (never removed at the default,
        // so the template's own explicit row survives; 0 is written for off); null leaves the bytes.
        propsWant = upsertRow(propsWant, SEATS_PER_SOURCE_KEY,
                seatsPerSource == null ? null : Integer.toString(seatsPerSource), SEATS_PER_SOURCE_COMMENT);
        propsWant = upsertRow(propsWant, ALL_SKILLS_KEY, allSkills, ALL_SKILLS_COMMENT);
        propsWant = upsertRow(propsWant, RNG_PINS_KEY, rngPins, RNG_PINS_COMMENT);
        propsWant = upsertRow(propsWant, MAGIC_SEAM_KEY, magicSeam, MAGIC_SEAM_COMMENT);
        propsWant = upsertRow(propsWant, PILOT_SHIP_KEY, pilotShip, PILOT_SHIP_COMMENT);
        // The debug-watch posture row rides the SAME single write,
        // ALWAYS, in both modes: a plain Start writes false, so no later serve life inherits a debug watch's
        // true (Kill, then this write, then the spawn). The serve reads it from this file as its own config
        // key; it is never composed into serve.argv or the serve's environment.
        propsWant = upsertRow(propsWant, OpsMatchRows.CLIENT_DEBUG_KEY, f.debugWatch() ? "true" : "false",
                OpsMatchRows.CLIENT_DEBUG_COMMENT);
        if (shapeSwitch) {
            propsWant = withDescriptorRow(propsWant, selDescriptor);
        }
        // Package mode's bind pair
        // rides THIS one config write as server.bindHost / server.bindPort - the rows the launcher
        // reads when its launcher.properties box rows are blank (ServeLauncher ROW_BIND_PORT's
        // package law; a set box row refuses the Start, PACKAGE_BOX_ROWS_SET, before any write);
        // launcher.properties is never touched here and serve.argv never re-minted.
        // Owner mode writes NO bind row (one in the tracked config is an arm NO-GO; the stage's
        // box rows carry the bind there).
        if (!owner) {
            propsWant = withPackageBindRow(propsWant, PACKAGE_BIND_HOST_KEY, bindHost.trim());
            propsWant = withPackageBindRow(propsWant, PACKAGE_BIND_PORT_KEY, Integer.toString(port));
        }
        if (!propsWant.equals(propsNow) && propsRaw == null) {
            ui.fail("Start failed", "Cannot find " + propsFile.toAbsolutePath()
                    + (owner ? "\nRun this window from the repo root."
                            : "\nThe server package ships it beside serve.bat / serve.sh - restore it from"
                                    + " the zip."));
            return false;
        }
        // Resolve the sidecar decision off the lines Start is about to land.
        // No descriptor row ⇒ the wrapper refuses to start at all, so a password Start
        // refuses HERE instead of minting an orphaned armed sidecar beside a path no serve
        // will read.
        String sidecarText = passwordSidecarPath(propsWant);
        if (sidecarText == null && !matchPassword.isEmpty()) {
            ui.fail("Start failed", propsFile + " carries no match.descriptor row - the serve"
                    + " wrapper refuses to start without it, so there is no descriptor to hang"
                    + " the password sidecar on.\nRestore the row or blank the password field.");
            return false;
        }
        // The match sidecar, the admin twin and
        // the door .fingerprint resolve against the CONFIG file's dir (configRelative = the serve's
        // rule), never this JVM's cwd; the writes below land on these SAME Path objects, so the
        // file Start writes is the one the serve opens (ServerOpsCoreSelftest reroot-config-dir).
        Path sidecar = sidecarText == null ? null : configRelative(propsFile, sidecarText);
        PasswordAction action = PasswordAction.KEEP;
        if (sidecar != null) {
            try {
                boolean present = Files.isRegularFile(sidecar);
                String currentRow = present ? sidecarPasswordRowValue(
                        Files.readAllLines(sidecar, StandardCharsets.UTF_8)) : null;
                action = passwordStartAction(matchPassword, present, currentRow);
            } catch (IOException ex) {
                ui.fail("Start failed", "Could not read " + sidecar + ": " + ex);
                return false;
            }
        }
        // The admin sidecar decision — the match sidecar's twin, resolved PRE-WRITE
        // like it; no confirm on REMOVE (blank admin = no admin seat). A token-mode Start (match
        // blank) removes a present admin sidecar regardless of the field (D2: the serve refuses
        // an admin secret with no match door); a null field otherwise = KEEP.
        // Decided BEFORE the two match confirms
        // below, so a blank-password Start that deletes the admin file names it in the confirm the
        // host answers (a web Start passes a null field and still reaches that delete).
        Path adminSidecar = sidecar == null ? null
                : configRelative(propsFile, adminSidecarOf(sidecarText));
        PasswordAction adminAction = PasswordAction.KEEP;
        if (adminSidecar != null) {
            try {
                boolean present = Files.isRegularFile(adminSidecar);
                if (matchPassword.isEmpty()) {
                    adminAction = present ? PasswordAction.REMOVE : PasswordAction.KEEP;
                } else if (adminPassword != null) {
                    String currentRow = present ? sidecarPasswordRowValue(
                            Files.readAllLines(adminSidecar, StandardCharsets.UTF_8)) : null;
                    adminAction = passwordStartAction(adminPassword, present, currentRow);
                }
            } catch (IOException ex) {
                ui.fail("Start failed", "Could not read " + adminSidecar + ": " + ex);
                return false;
            }
        }
        // The admin-delete note: appended to BOTH token-mode confirms (both run with a blank match password, so
        // REMOVE here means this Start deletes the admin file); "" when no admin file goes.
        String adminDeleteNote = adminAction == PasswordAction.REMOVE
                ? "\nThis also DELETES " + adminSidecar.getFileName() + " (the admin password)." : "";
        if (sidecar != null) {
            if (action == PasswordAction.REMOVE && !ui.confirm(CONFIRM_REMOVE_PASSWORD,
                    "Remove match password", "Match password is blank - DELETE " + sidecar
                            + " and serve TOKEN mode?" + adminDeleteNote)) {
                return false; // nothing written yet — Cancel is side-effect-free
            }
            // Blank + no sidecar = a silent TOKEN-mode Start — the
            // "forgotten password" shape on the public lane (kit joiners can't join; a leaked
            // descriptor token could). Confirm it, like REMOVE does; Cancel is side-effect-free.
            // The text used to say "the kit ships no tokens" - false for a
            // keep-tokens roll (the default in TOKEN mode; a password-armed roll packs REDACTED),
            // whose match.txt carries the live seat tokens. It now states both
            // halves: the join window refuses, and the tokens admit whoever holds them (the packer's
            // "TRUSTED testers only" line is the twin). ServerOpsCoreSelftest pins the wording.
            if (action == PasswordAction.KEEP && matchPassword.isEmpty()
                    && !ui.confirm(CONFIRM_TOKEN_MODE_START, "No match password - token mode",
                            "Match password is blank and " + sidecar.getFileName()
                                    + " does not exist - this Start serves TOKEN mode:"
                                    + " public-kit joiners are REFUSED (their join window"
                                    + " needs a password), while the seat tokens in the match"
                                    + " file admit whoever holds them - a kit packed without"
                                    + " -RedactTokens carries them."
                                    + "\nType a match password for a hosted match."
                                    + "\nStart in token mode anyway (debug WATCH / solo testing)?"
                                    + adminDeleteNote)) {
                return false; // nothing written yet — Cancel is side-effect-free
            }
        }
        // A switched Start under the ARMED door with no
        // .fingerprint sidecar beside the selected descriptor would land the row edit and
        // then die in the wrapper's fail-closed preflight — readable only in the start log,
        // with the tracked row left switched for every other row consumer. Confirm BEFORE the
        // first write. Stale jar= currency stays the wrapper's call (its authority; env
        // door overrides are equally its business).
        if (shapeSwitch && doorFingerprintArmedRow(propsWant)
                && !Files.isRegularFile(configRelative(propsFile, selDescriptor + ".fingerprint"))
                && !ui.confirm(CONFIRM_ARMED_DOOR, "Armed door - missing fingerprint sidecar",
                        ServerOpsCore.armedDoorConfirmText(selDescriptor,
                                OpsRoots.get().mode() == OpsRoots.Mode.PACKAGE))) {
            return false; // nothing written yet — Cancel is side-effect-free
        }
        // The PvP switch runs AFTER the last confirm (a Cancel above
        // writes nothing) and IMMEDIATELY BEFORE the props write: a refusal here leaves every file as it was,
        // and a failure after it leaves a consistent re-minted pair whose mode the next Start's select prefills,
        // so that Start finds no flip. Live = false: this flow's wrapper already refused a live server under
        // the staging hold it still holds.
        OpsMatchApply.ApplyResult pvpApplied = null;
        if (pvp.flip()) {
            try {
                pvpApplied = OpsMatchApply.applyPvp(OpsRoots.get(), pvp.fileOrNull(), pvpWant ? "on" : "off",
                        f.pvpStationText(), false);
            } catch (OpsMatchApply.ApplyRefused refused) {
                ui.fail("Start refused", refused.getMessage());
                return false;
            }
        }
        // Land the validated rate (+ any switched row) BEFORE the spawn — the
        // wrapper resolves the file, never a second carrier. No-change = no write.
        try {
            synchronized (PROPS_LOCK) {
                if (!propsWant.equals(propsNow)) {
                    String sep = propsRaw.contains("\r\n") ? "\r\n" : "\n";
                    Files.writeString(propsFile, String.join(sep, propsWant) + sep,
                            StandardCharsets.UTF_8);
                }
                // The WATCH lease rides the row's own locked slot, before the spawn, so the serve's boot read
                // never finds the row true with the lease missing; a plain Start's false removes the lease.
                writeOrClearWatchLease(propsFile, f.debugWatch());
            }
        } catch (IOException ex) {
            ui.fail("Start failed", "Could not write " + propsFile + ": " + ex);
            return false;
        }
        // Land the password sidecar BEFORE the spawn — the serve re-reads it at
        // open. Unchanged value = no write (a hand-authored sidecar keeps its bytes).
        try {
            if (action == PasswordAction.REMOVE) {
                Files.delete(sidecar);
            } else if (action == PasswordAction.WRITE) {
                Path parent = sidecar.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                writeOwnerOnly(sidecar, passwordSidecarContent(matchPassword));
            }
        } catch (IOException ex) {
            ui.fail("Start failed", "Could not update " + sidecar + ": " + ex);
            return false;
        }
        // The admin sidecar lands beside the match one, the same idiom.
        try {
            if (adminAction == PasswordAction.REMOVE) {
                Files.delete(adminSidecar);
            } else if (adminAction == PasswordAction.WRITE) {
                Path parent = adminSidecar.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                writeOwnerOnly(adminSidecar, adminPasswordSidecarContent(adminPassword));
            }
        } catch (IOException ex) {
            ui.fail("Start failed", "Could not update " + adminSidecar + ": " + ex);
            return false;
        }
        boolean adminArmed = adminSidecar != null && !matchPassword.isEmpty()
                && Files.isRegularFile(adminSidecar);
        // Owner mode's STAGE spawn, then the chain
        // thread that launches ServeLauncher when the stage succeeds (a null Process = a recording
        // spawn: no chain, the flag cleared at once); package mode spawns the launcher itself.
        Path configAbs = propsFile.toAbsolutePath().normalize();
        // STAGING + the stage.lock hold were taken by runStartFlow's wrapper right after the
        // probe; a false return below hands them back there (its finally), a null spawn ends them here.
        if (!owner) {
            // Package mode - no stage, the launcher
            // straight away; the staging hold passes to startPackage's lock-wait thread.
            try {
                StartChain.startPackage(spawn, pkg, configAbs);
            } catch (IOException | RuntimeException ex) {
                ui.fail("Start failed", "Could not launch the server launcher: " + ex);
                return false;
            }
        } else {
            Process stage;
            try {
                stage = spawn.spawn(stageCommand(pkg, configAbs, bindHost.trim(), port,
                        ProcessHandle.current().pid()));
            } catch (IOException | RuntimeException ex) {
                ui.fail("Start failed", "Could not launch " + STAGE_TOOL + ": " + ex);
                return false;
            }
            if (stage == null) {
                endStaging();
            } else {
                try {
                    StartChain.start(stage, spawn, pkg, configAbs);
                } catch (RuntimeException | OutOfMemoryError noThread) {
                    ui.fail("Start failed", "the stage started but its follow-up thread did not ("
                            + noThread.getClass().getSimpleName() + ") - no launcher will start;"
                            + " see " + OpsRoots.get().startLogText());
                    return false;
                }
            }
        }
        ui.info("start requested (bind " + bindHost.trim() + ":" + bindPort.trim() + ", tick "
                + tickRate + " Hz"
                + (tickRate != SimTickRate.DEFAULT_TICKS_PER_SECOND
                        ? " - joiners adopt it at join" : "")
                + (fortressDp > 0 ? ", fortress DP " + fortressDp : "")
                + (fortressWaveScale == null || fortressWaveScale.isEmpty() ? ""
                        : ", wave scale " + fortressWaveScale) //
                // Name a live-roster wave DP.
                + (waveRosterFullDp == null || waveRosterFullDp.isEmpty() ? ""
                        : ", roster wave DP " + waveRosterFullDp)
                + (waveRosterMultiplier == null || waveRosterMultiplier.isEmpty() ? ""
                        : ", roster wave x " + waveRosterMultiplier)
                + (joinerDefault == null || joinerDefault.isEmpty() ? ""
                        : ", joiner default " + joinerDefault)
                // Name the extra mods where the owner looks.
                + (modsEnabled == null || modsEnabled.isEmpty() ? "" : ", mods " + modsEnabled)
                // Name a picked terrain.
                + (terrainWord == null || "none".equals(terrainWord) ? ""
                        : ", terrain " + terrainWord + " "
                                + (terrainLevel == null ? parseTerrainLevel(null) : terrainLevel)
                                + "%")
                // Name a picked asteroid field.
                + (asteroidsWord == null || "none".equals(asteroidsWord) ? ""
                        : ", asteroids " + asteroidsWord)
                + (selInfo != null ? ", shape " + selInfo.shape() : "")
                + (shapeSwitch ? " - SWITCHED DESCRIPTOR: REMOTE JOINERS NEED A KIT PACKED"
                        + " AGAINST IT" : "")
                + ", admission " + (matchPassword.isEmpty() ? "token" : "password")
                + (adminArmed ? ", admin credential armed (the admin joiner types the admin"
                        + " password: F11/F12 waves, /kick)" : "") //
                // The public
                // serve is HEADLESS by design — say so where the operator looks.
                + ") - headless: no game window opens, joiners connect with the kit;"
                + (owner ? " staging the server package, then launching (log: "
                        : " launching (log: ")
                + OpsRoots.get().startLogText() + ")"
                + (pvpApplied == null ? "" : " " + pvpApplied.note())); // a PvP switch says so (+ the station back)
        return true;
    }

    /** The props row whose {@code true} the serve refuses beside the PvP shape (its per-clear round end). */
    static final String WAVE_CLEAR_ROUND_END_KEY = "server.waveClearRoundEnd";

    /** What the Start's match file carries for PvP mode -
     *  {@code fileOrNull} = the descriptor row it will boot, resolved beside the config (null without a row);
     *  {@code decodedOrNull} = that file decoded (null when unreadable: the serve's own checks answer it);
     *  {@code flip} = the select asks the other mode than the file has. */
    record PvpPlan(Path fileOrNull, MatchDescriptor decodedOrNull, boolean flip) {
        boolean filePvp() {
            return decodedOrNull != null && MatchDescriptors.isPvpShape(decodedOrNull.shape());
        }
    }

    /** {@link PvpPlan} of the row {@code descriptorRow} (one bounded
     *  read, the console readers' cap); an unreadable file logs ONE line naming its exception class. */
    static PvpPlan pvpPlan(Path propsFile, String descriptorRow, Boolean pvpWant) {
        if (descriptorRow == null || descriptorRow.isBlank()) {
            return new PvpPlan(null, null, Boolean.TRUE.equals(pvpWant));
        }
        Path file = configRelative(propsFile, descriptorRow);
        MatchDescriptor decoded = null;
        try {
            decoded = MatchDescriptorCodec.decode(readCappedLines(file));
        } catch (IOException | RuntimeException unreadable) {
            System.out.println("[ops] start pvp: the match file cannot be read ("
                    + unreadable.getClass().getSimpleName() + ")");
        }
        boolean filePvp = decoded != null && MatchDescriptors.isPvpShape(decoded.shape());
        return new PvpPlan(file, decoded, pvpWant != null && pvpWant != filePvp);
    }

    /**
     * The Start's PvP refusals, asked BEFORE any confirm or write
     * against what the files WILL carry (the mods check's idiom), so a host sees a dialog instead of a serve that
     * refuses its own boot and relaunches: owner mode cannot switch (its Start stages the repo's match file,
     * not a package's {@code match/} one); a switch ON needs the fortress file ({@link OpsMatchApply#R11_PVP_SHAPE})
     * at 2 or more players ({@link OpsMatchApply#R10_PVP_PLAYERS}); PvP (the select's mode, else the file's) beside
     * a {@code true} {@link #WAVE_CLEAR_ROUND_END_KEY} row, or beside a station row the file keeps (a PvP file not
     * switched), is the serve's two boot refusals. Null = none; the switch itself re-checks every refusal.
     */
    static String pvpStartRefusalOrNull(PvpPlan plan, Boolean pvpWant, boolean owner, String waveClearRow) {
        if (plan.flip() && owner) {
            return OpsMatchApply.PVP_START_OWNER;
        }
        MatchDescriptor d = plan.decodedOrNull();
        if (plan.flip() && Boolean.TRUE.equals(pvpWant) && d != null) {
            if (!MatchDescriptors.FORTRESS_SHAPE.equals(d.shape())) {
                return OpsMatchApply.R11_PVP_SHAPE;
            }
            if (d.seats().size() < 2) {
                return OpsMatchApply.R10_PVP_PLAYERS;
            }
        }
        boolean willPvp = pvpWant != null ? pvpWant : plan.filePvp();
        if (willPvp && waveClearRow != null && "true".equals(waveClearRow.trim())) {
            return OpsMatchApply.PVP_START_LATCH;
        }
        if (willPvp && !plan.flip() && OpsMatchApply.carriesStation(d)) {
            return OpsMatchApply.PVP_START_STATION;
        }
        return null;
    }

    /** The real spawn both shells pass as {@link SpawnFn}: merged output appended to the live
     * record's {@link OpsRoots#startLog} (owner mode's {@link ServerOpsCore#START_LOG}, the file
     * {@link ServerOpsCore#goneDetailText} points at) — the stage AND the launcher spawn ride it. */
    public static Process spawnStartScript(List<String> cmd) throws IOException {
        return spawnAppendingTo(cmd, startLogRedirect().toString());
    }

    /** {@link #spawnStartScript}'s
     * redirect target, split out so a row pins it without spawning a real {@code java}:
     * package mode's launcher output (its REFUSED sentence, which {@code refusalSentence} tails)
     * must land in {@code <pkg>/run/ops-start.out.log}, never a cwd-relative {@code START_LOG}. */
    static Path startLogRedirect() {
        return OpsRoots.get().startLog();
    }

    /** The real spawn behind {@link #runWatchFlow} (W10): merged output appended to
     * {@link ServerOpsCore#WATCH_LOG}; the watch itself detaches from this child. */
    public static Process spawnWatchScript(List<String> cmd) throws IOException {
        return spawnAppendingTo(cmd, WATCH_LOG);
    }

    /** The "Dev editor" button — {@link ServerOpsCore#DEV_EDITOR_SCRIPT}
     * spawned as-is (it boots the kit-env stage if none is alive, then opens the owner's tab);
     * merged output appended to {@link ServerOpsCore#DEV_EDITOR_LOG}. The GUI's cwd is the
     * repo root (the watch button's script path rides the same convention). */
    public static Process spawnDevEditor() throws IOException {
        return spawnAppendingTo(List.of("pwsh", "-NoProfile", "-File", DEV_EDITOR_SCRIPT),
                DEV_EDITOR_LOG);
    }

    /** The "Joiner GUI" button — {@link ServerOpsCore#JOINER_GUI_SCRIPT}
     * spawned as-is (re-opens the live join window's tab, else arms a fresh join-cc.bat);
     * merged output appended to {@link ServerOpsCore#JOINER_GUI_LOG}. Same cwd convention. */
    public static Process spawnJoinerGui() throws IOException {
        return spawnAppendingTo(List.of("pwsh", "-NoProfile", "-File", ServerOpsCore.JOINER_GUI_SCRIPT),
                ServerOpsCore.JOINER_GUI_LOG);
    }

    private static Process spawnAppendingTo(List<String> cmd, String logPath) throws IOException {
        File log = new File(logPath);
        File parent = log.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        return new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .start();
    }

    // ---------------------------------------------- lease-first Start + brick

    /** The bind-host field DEFAULT beside {@link ServerOpsCore#DEFAULT_BIND_PORT}
     * — the loopback dev pair a public Start must never bind (a live test showed:
     * the serve bound {@code 127.0.0.1:51337} under a VPN brick, joiners dialled a dead port). */
    public static final String DEFAULT_BIND_HOST = "127.0.0.1";

    /** What a Start press binds after the lease pre-step ({@link #resolveStartBind}):
     * {@code advertise} = the joiner endpoint when a lease resolved it, else {@code null}
     * (the typed fields stand). Texts are trimmed, port is the digits {@link #validateBind} parses. */
    public record StartBind(String bindHost, String bindPort, String advertise) {
    }

    /**
     * Pure, selftest-pinned (fixture address sets, never the real NICs):
     * the Start bind decision AFTER the lease pull. A pulled lease WINS: bind = tun:port,
     * advertise = exit:port (the form was refreshed by the caller). A failed pull leaves the
     * typed fields — unless they are still the dev default ({@link #DEFAULT_BIND_HOST}:
     * {@link ServerOpsCore#DEFAULT_BIND_PORT}), which no joiner can reach: REFUSED with the
     * pull's reason. Then every non-wildcard host must sit on a local interface
     * ({@link #localAddresses}; {@code 0.0.0.0} passes) — a live test showed:
     * a stale-lease {@code 10.2.0.2} with the VPN down burned 5 lives × 50 s of engine boot
     * before a bare {@code BindException}. Throws {@link IllegalArgumentException} = a dialog.
     * {@link #validateBind} (inside {@link #runStartFlow}) still runs AFTER this.
     * The not-on-an-interface refusal speaks
     * {@code words} ({@link BindWords}); the dev-default refusal stays the owner's sentence (a
     * package never reaches it - with no answer it binds through {@code PackageBind.resolve}).
     */
    public static StartBind resolveStartBind(VpnLease lease, String leaseFailure, String typedHost,
            String typedPort, Set<String> localAddrs, BindWords words) {
        String host = typedHost == null ? "" : typedHost.trim();
        String port = typedPort == null ? "" : typedPort.trim();
        String advertise = null;
        if (lease != null) {
            host = lease.tunIp();
            port = Integer.toString(lease.port());
            advertise = lease.exitIp() + ":" + lease.port();
        } else if (DEFAULT_BIND_HOST.equals(host) && DEFAULT_BIND_PORT.equals(port)) {
            throw new IllegalArgumentException("VPN lease pull failed (" + oneLine(leaseFailure)
                    + ") and bind is still the dev default " + host + ":" + port
                    + " - joiners could never reach it; connect the VPN and press Start again"
                    + " (it re-pulls the lease), or type a LAN/forwarded host:port");
        }
        if (!host.isEmpty() && !"0.0.0.0".equals(host)
                && !localAddrs.contains(canonicalHost(host))) {
            List<String> v4 = localAddrs.stream().filter(a -> !a.contains(":")).sorted().toList();
            throw new IllegalArgumentException(words.notOnInterface(host, lease, v4));
        }
        return new StartBind(host, port, advertise);
    }

    /** {@link #resolveStartBind(VpnLease, String, String,
     * String, Set, BindWords)} in the owner-mode words ({@link BindWords#OWNER}) - the owner-mode
     * Start and the bind rows. */
    public static StartBind resolveStartBind(VpnLease lease, String leaseFailure, String typedHost,
            String typedPort, Set<String> localAddrs) {
        return resolveStartBind(lease, leaseFailure, typedHost, typedPort, localAddrs,
                BindWords.OWNER);
    }

    /**
     * The two lease refusals whose WORDS differ by console
     * mode - the sentences, never the rules. {@link #OWNER} = the owner console's texts, byte-
     * identical to the pre-probe literals (moved here, pinned by {@code VpnLeaseSelftest}'s
     * {@code lease-bind-words}); {@link #PACKAGE} = the release console's, naming ProtonVPN and
     * the buttons a host presses (no owner-only word).
     */
    public enum BindWords {
        OWNER {
            @Override
            public String notOnInterface(String host, VpnLease lease, List<String> v4) {
                return "bind host " + host + " is not on any local"
                        + " interface (VPN down / stale lease?)"
                        + (lease == null ? "" : " - the lease's tun " + lease.tunIp() + " is gone")
                        + "; local IPv4: " + (v4.isEmpty() ? "(none)" : String.join(", ", v4))
                        + " - reconnect the VPN and press Start again, or type a host this box holds";
            }

            @Override
            public String deadPort(OpsSnapshot live, VpnLease lease) {
                String endpoint = lease.exitIp() + ":" + lease.port();
                return "server is on " + live.host() + ":" + live.port()
                        + ", lease is " + endpoint + " (tun " + lease.tunIp() + ") - joiners would"
                        + " dial a dead port; End match, then Start server, then Copy join brick";
            }
        },
        PACKAGE {
            @Override
            public String notOnInterface(String host, VpnLease lease, List<String> v4) {
                return "The listen address " + host + " is not on this computer any more"
                        + " (ProtonVPN disconnected?); its IPv4: "
                        + (v4.isEmpty() ? "(none)" : String.join(", ", v4))
                        + ". Reconnect ProtonVPN and press Start again, or type an address this"
                        + " computer holds.";
            }

            @Override
            public String deadPort(OpsSnapshot live, VpnLease lease) {
                return "ProtonVPN now forwards " + lease.exitIp() + ":" + lease.port()
                        + " but the server listens on " + live.host() + ":" + live.port()
                        + " - players would dial a dead port. Press End match, then Start server,"
                        + " then Copy join brick.";
            }
        };

        /** {@code host} is on no local interface; {@code lease} may be null; {@code v4} = this
         * computer's IPv4 addresses, sorted. */
        public abstract String notOnInterface(String host, VpnLease lease, List<String> v4);

        /** The live serve ({@code live}, non-null) is not where {@code lease} (non-null) forwards. */
        public abstract String deadPort(OpsSnapshot live, VpnLease lease);
    }

    /**
     * Pure, selftest-pinned: the endpoint the brick carries AFTER the lease
     * pull, compared against the RUNNING serve ({@code live} = the followed status file's
     * snapshot while its writer is alive, {@code null} = no server; its {@code host=/port=} rows
     * are the door's OS-bound endpoint, {@code PersistentPilotIntentCombatServer}). A live
     * serve on another port — or a non-wildcard host that is not the lease's tun — means the
     * brick would name a DEAD port (friend test #1: a re-pulled lease moved the port under a
     * serve still on the old one): REFUSED, restart is the fix. No lease: the form's Advertise
     * (the LAST pulled lease) stands only when a live serve is on exactly its port; otherwise
     * refused — never a dead brick. Throws {@link IllegalArgumentException} = a dialog.
     * The dead-port refusal speaks {@code words}
     * ({@link BindWords}); the three no-lease refusals stay the owner's sentences (a package
     * never reaches them - with no answer it bricks through {@link #packageBrickEndpoint}).
     */
    public static String brickEndpoint(VpnLease lease, String leaseFailure, String advertiseText,
            OpsSnapshot live, BindWords words) {
        if (lease == null) {
            String typed = advertiseText == null ? "" : advertiseText.trim();
            String why = "VPN lease pull failed (" + oneLine(leaseFailure) + ")";
            if (typed.isEmpty() || "-".equals(typed)) {
                throw new IllegalArgumentException(why + " and no lease was ever pulled - the"
                        + " brick carries the ip:port joiners dial; connect the VPN and press"
                        + " Copy join brick again (it re-pulls)");
            }
            if (live == null) {
                throw new IllegalArgumentException(why + " and no server is running to confirm"
                        + " the form's " + typed + " against - connect the VPN, Start, then"
                        + " Copy join brick (never a dead brick)");
            }
            if (portOf(typed) != live.port()) {
                throw new IllegalArgumentException(why + "; the form's " + typed + " is not the"
                        + " RUNNING server's port " + live.port() + " - restart it (Kill ->"
                        + " Start -> Copy join brick) once the VPN is back");
            }
            return typed;
        }
        String endpoint = lease.exitIp() + ":" + lease.port();
        if (live != null && (live.port() != lease.port()
                || (!"0.0.0.0".equals(live.host()) && !live.host().equals(lease.tunIp())))) {
            throw new IllegalArgumentException(words.deadPort(live, lease));
        }
        return endpoint;
    }

    /** {@link #brickEndpoint(VpnLease, String, String,
     * OpsSnapshot, BindWords)} in the owner-mode words ({@link BindWords#OWNER}) - the owner-mode
     * brick and the brick rows. */
    public static String brickEndpoint(VpnLease lease, String leaseFailure, String advertiseText,
            OpsSnapshot live) {
        return brickEndpoint(lease, leaseFailure, advertiseText, live, BindWords.OWNER);
    }

    /** {@link #packageBrickEndpoint}'s two fixed
     * refusals (the third names both ports). */
    static final String PKG_BRICK_TYPE_FIRST = "type the address players dial (your public IP or host"
            + " name) and the forwarded port into Advertise first";
    static final String PKG_BRICK_START_FIRST = "Start the server first, then Copy join brick (the code"
            + " carries the running server's port)";
    /** The address part of Advertise holds a colon, whitespace or a quote - the
     * joiner's parser ({@code JoinLaunchPlan.parseBrick}) takes exactly three colon-split, whitespace-free
     * parts, so such a brick would paste as nothing; refused here, in both shells, before it is minted,
     * copied, saved or re-served. */
    static final String PKG_BRICK_HOST_SHAPE = "Advertise must be <address>:<port> with no http://, no"
            + " second colon, no spaces and no quotes in the address part";

    /**
     * Package mode's Copy join brick endpoint - the
     * lease-free rules of {@link #brickEndpoint} with PACKAGE texts. WHY: a stranger's host has no
     * VPN lease, so the endpoint is what the host TYPED into Advertise, and it stands only against a
     * RUNNING serve on exactly its port (never a dead brick). Refused, in order: blank / {@code -} /
     * still the {@link OpsSetupPrefill#PKG_PUBLIC_IP} placeholder / no host or no numeric port after
     * the last colon ({@link #PKG_BRICK_TYPE_FIRST}); an address part holding a colon, whitespace or
     * a quote ({@link #PKG_BRICK_HOST_SHAPE} - the joiner cannot parse such a
     * brick); no live serve ({@link #PKG_BRICK_START_FIRST}); a port other than {@code live.port()}.
     * The host part is not COMPARED to the bind: a package binds a LAN or wildcard address, never
     * the public one joiners dial. Pure; pinned by {@code OpsPressSelftest} {@code pkg-brick-lease-free}.
     *
     * @param live the followed status snapshot while its writer is alive, {@code null} = no server
     * @return the trimmed typed endpoint
     * @throws IllegalArgumentException the refusal sentence the shell's dialog shows
     */
    public static String packageBrickEndpoint(String advertiseText, OpsSnapshot live) {
        String typed = advertiseText == null ? "" : advertiseText.trim();
        int port = portOf(typed);
        if (typed.isEmpty() || "-".equals(typed) || typed.startsWith(OpsSetupPrefill.PKG_PUBLIC_IP)
                || typed.lastIndexOf(':') <= 0 || port < 0) {
            throw new IllegalArgumentException(PKG_BRICK_TYPE_FIRST);
        }
        String host = typed.substring(0, typed.lastIndexOf(':'));
        if (host.indexOf(':') >= 0 || host.indexOf('"') >= 0
                || host.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(PKG_BRICK_HOST_SHAPE);
        }
        if (live == null) {
            throw new IllegalArgumentException(PKG_BRICK_START_FIRST);
        }
        if (port != live.port()) {
            throw new IllegalArgumentException("Advertise names port " + port + " but the running"
                    + " server listens on " + live.port() + " - fix Advertise or restart the server");
        }
        return typed;
    }

    /** The port after the LAST colon of {@code host:port} text; -1 when absent/non-numeric. */
    static int portOf(String endpoint) {
        int colon = endpoint.lastIndexOf(':');
        if (colon < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(endpoint.substring(colon + 1));
        } catch (NumberFormatException bad) {
            return -1;
        }
    }

    /** The host as {@link InetAddress#getHostAddress} spells it (an IP literal round-trips
     * without DNS; a name resolves; unresolvable = the text itself, which no interface holds). */
    static String canonicalHost(String host) {
        try {
            return InetAddress.getByName(host).getHostAddress();
        } catch (UnknownHostException | RuntimeException unresolvable) {
            return host;
        }
    }

    /** Every address on an UP interface of this box (the JVM the serve spawns on), as
     * {@link InetAddress#getHostAddress} strings — the real set {@link #resolveStartBind} checks
     * against. Enumeration failure = empty set (fail-closed: only {@code 0.0.0.0} passes). */
    public static Set<String> localAddresses() {
        Set<String> out = new HashSet<>();
        List<NetworkInterface> nics;
        try {
            nics = Collections.list(NetworkInterface.getNetworkInterfaces());
        } catch (SocketException | RuntimeException enumerationFailed) {
            return out; // fail-closed: an empty set refuses every explicit host, list shown
        }
        for (NetworkInterface nic : nics) {
            // per-NIC: one adapter's isUp() throwing must not truncate the
            // set mid-loop and falsely refuse a tun that sits later in the enumeration.
            try {
                if (!nic.isUp()) {
                    continue;
                }
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    out.add(a.getHostAddress());
                }
            } catch (SocketException | RuntimeException oneNicFailed) {
                // skip this adapter only
            }
        }
        return out;
    }

    /** A failure reason as ONE printable line for a refusal text (a probe refusal sentence or an
     * exception's text can run to several lines; the dialog leads with the first line, bounded). */
    static String oneLine(String text) {
        if (text == null || text.isBlank()) {
            return "no reason given";
        }
        String first = text.strip().lines().findFirst().orElse("").strip();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < first.length() && sb.length() < 200; i++) {
            char c = first.charAt(i);
            sb.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return sb.toString();
    }

    // ---------------------------------------------- debug WATCH launch (W10)

    /** The fixed local command the watch button runs — no user text rides it (the recipe lives
     * in the script; a fixed repo-relative path, never wire/field-derived). {@code -NoOpsGui} =
     * D-GUIREUSE: the lib arms the files without the sweep+spawn; this window follows them. */
    public static List<String> watchCommand() {
        // The W10 press arms the SAME shape as the automated sessions
        // -> -Observer rides every press.
        return new ArrayList<>(List.of("pwsh", "-NoProfile", "-File", WATCH_SCRIPT, "-NoOpsGui", "-Observer"));
    }

    /** D-BRIEFGONE: the same fixed command plus
     * {@code -OwnerBriefFile <path>} when the pressing window is showing a brief — the chain
     * re-arms that brief (a NEW {@code current.txt} stamp) instead of the brief-less arm's
     * delete hiding the panel mid-press. The path is ours (a file this JVM just wrote under
     * {@link #BRIEF_FORWARD_DIR}), never field/wire text. */
    public static List<String> watchCommand(Path briefForwardOrNull) {
        List<String> cmd = watchCommand();
        if (briefForwardOrNull != null) {
            cmd.add("-OwnerBriefFile");
            cmd.add(briefForwardOrNull.toString());
        }
        return cmd;
    }

    /** Where {@link #runWatchFlow(Ui, SpawnFn, OwnerBrief, Path)} drops the forwarded brief body
     * ({@code forward-<epochMs>.txt}); {@code arm-standing-watch.ps1 -OwnerBriefFile} reads it. */
    public static final String BRIEF_FORWARD_DIR = "generated/owner-brief";

    /**
     * W10 with the window's brief forwarded (D-BRIEFGONE): writes {@code brief.body()} to a
     * fresh file under {@code forwardDir} and spawns {@link #watchCommand(Path)} with it; a
     * {@code null} brief is the plain brief-less press. An unwritable forward file is a fail
     * dialog before any spawn (never a silent brief-less arm the owner reads as "vanished").
     */
    public static boolean runWatchFlow(Ui ui, SpawnFn spawn, OwnerBrief brief, Path forwardDir) {
        Path forward = null;
        if (brief != null) {
            try {
                Files.createDirectories(forwardDir);
                forward = forwardDir.resolve("forward-" + System.currentTimeMillis() + ".txt");
                Files.writeString(forward, brief.body() + "\n", StandardCharsets.UTF_8);
            } catch (IOException ex) {
                ui.fail("Watch failed", "Could not forward the operator brief to " + forwardDir
                        + ": " + ex);
                return false;
            }
        }
        return runWatchFlow(ui, spawn, watchCommand(forward));
    }

    /**
     * W10: the "Launch debug WATCH" flow both shells could run (Swing today). STOPPED-only is the
     * shell's enable gate (a watch owns its own serve); here: script present → spawn
     * {@link #watchCommand()} → one info line that tells the owner what happens next (lease,
     * kit-env asserts, server + host window + kit join window, and that THIS window hands over
     * to the watch's own ops window after the bind). Missing script / spawn failure = a
     * fail dialog, nothing launched.
     */
    public static boolean runWatchFlow(Ui ui, SpawnFn spawn) {
        return runWatchFlow(ui, spawn, watchCommand());
    }

    private static boolean runWatchFlow(Ui ui, SpawnFn spawn, List<String> cmd) {
        Path script = Paths.get(WATCH_SCRIPT);
        if (!Files.isRegularFile(script)) {
            ui.fail("Watch failed", "Cannot find " + script.toAbsolutePath()
                    + "\nRun this window from the repo root.");
            return false;
        }
        try {
            spawn.spawn(cmd);
        } catch (IOException ex) {
            ui.fail("Watch failed", "Could not launch " + WATCH_SCRIPT + ": " + ex);
            return false;
        }
        ui.info("debug WATCH handed to " + WATCH_SCRIPT + " (standing fortress public shape,"
                + " briefless): VPN lease pull, kit-env asserts, then the server + your host window"
                + " + the kit join window come up; this window now follows the watch's own status"
                + " file (" + WATCH_OPS_FILE + "). Parent log: " + WATCH_LOG
                + "; run log: generated/pilot-render-watch/arm-detached-<stamp>.log");
        return true;
    }
}
