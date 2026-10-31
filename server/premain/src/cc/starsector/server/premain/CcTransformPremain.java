package cc.starsector.server.premain;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import cc.starsector.server.transform.PremainConfigFile;
import cc.starsector.server.transform.RngPinKnobs;
import cc.starsector.server.transform.TransformLaunchGate;
import cc.starsector.server.transform.TransformRegistry;

/**
 * The product-owned minimal premain — the ONLY product entrypoint for
 * class-load transformation, consuming the declarative {@link TransformRegistry} (registration,
 * not adoption). Replaces the dev trace agent's {@code displayNoopOnly} mode as the launch path
 * for HOOK-0043 so the trace agent returns to being the RE instrument, not the product's
 * load-bearing substrate dependency.
 *
 * <p>Fail-closed launch gate: for every enabled entry, BEFORE registering any
 * transformer, this premain resolves the target jar (explicit {@code -Dcc.transform.jar.<ID>=path}
 * override, else the {@code java.class.path} entry whose file name matches the registry's
 * {@code targetJarFileName}), reads the target classfile, and verifies every registered
 * fingerprint via {@link TransformLaunchGate}. Any mismatch, unknown ID, or unresolvable jar
 * refuses the launch by throwing — the JVM aborts during {@code -javaagent} processing, with the
 * boundary named on stderr first. Version drift surfaces as a refused launch, not a weird
 * mid-session failure.
 *
 * <p>Gate knob: {@code -Dcc.transform.enable=ID[,ID...]} (or env {@code CC_TRANSFORM_ENABLE}).
 * On a FILE-LESS boot (no config file named) absent/empty leaves the premain inert — launching
 * without it reproduces stock behavior exactly. On a POSTURED boot (a loadable
 * {@code -Dcc.server.config} / {@code CC_SERVER_CONFIG} file) this premain is the ONE decider of
 * the RNG pins: {@link #decidePinPosture} adds {@link RngPinKnobs#PIN_IDS} unless
 * {@code rngpin.enabled} resolves false, and mints the master seed when none is supplied (see
 * {@link #resolvePosturedSeed}). Wrappers migrate by SWAPPING the trace agent's {@code displayNoopOnly} attach for this premain,
 * never by stacking both: a prior transformer's rewrite changes the loaded bytes, which this
 * premain's transform-time re-verification correctly refuses (see {@link RegistryNoopApplier}).
 *
 * <p>In-memory only: transforms apply at class load on the
 * user's own jars; nothing on disk changes; no original bytecode sequences are embedded — targets
 * are matched by fingerprint/descriptor/hash only. The transformer injects no reference back into
 * this package, so it is safe across isolated game {@code URLClassLoader}s (same constraint the
 * trace agent's noop-only mode proved).
 */
public final class CcTransformPremain {

    private CcTransformPremain() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        Set<String> enabledIds = parseEnabledIds();
        // BEFORE the inert return: a product serve passes no enable list at all, so a postured boot's
        // pin set must be added here or every product serve would boot inert.
        PinPosture pins = decidePinPosture(enabledIds);
        // BEFORE the inert return too: the seam's host row arms on every boot that loads this premain
        SwitchRow magicSeam = armMagicSeam(pins.file(), instrumentation);
        // AFTER the seam, so the seam's window match still sees MagicLib's original bytes
        SwitchRow pilotShip = armPilotShip(pins.file(), instrumentation);
        if (enabledIds.isEmpty()) {
            // a census request on an inert premain is a refusal, never a silent no-op
            String census = System.getProperty(RngPinKnobs.CENSUS_SYSPROP);
            if (census == null || census.isBlank()) {
                census = System.getenv(RngPinKnobs.CENSUS_ENV);
            }
            if (census != null && !census.isBlank()) {
                refuse(List.of("-D" + RngPinKnobs.CENSUS_SYSPROP + " set but no transforms enabled — the census needs the"
                        + " keyed Random pin (HOOK-0044) in -Dcc.transform.enable"));
            }
            publishPinReceipt(pins, null, null);
            log("inert: no transforms enabled (set -Dcc.transform.enable=HOOK-0043 or CC_TRANSFORM_ENABLE)");
            return;
        }
        List<TransformRegistry.Entry> enabled = new ArrayList<>();
        for (String id : enabledIds) {
            TransformRegistry.Entry entry = TransformRegistry.byId(id);
            if (entry == null) {
                refuse(List.of("unknown transform id '" + id + "' — not in the product registry"
                        + " (registered: " + TransformRegistry.entries().stream()
                                .map(TransformRegistry.Entry::id).toList() + ")"));
            }
            enabled.add(entry);
        }
        refuseMissingRedirectPrerequisites(enabled);
        List<TransformRegistry.Entry> noopEntries = new ArrayList<>();
        List<TransformRegistry.Entry> redirectEntries = new ArrayList<>();
        List<TransformRegistry.Entry> typeSwapEntries = new ArrayList<>();
        TransformRegistry.Entry keyedPin = null;
        for (TransformRegistry.Entry entry : enabled) {
            switch (entry.kind()) {
                case METHOD_NOOP -> {
                    eagerJarGate(entry);
                    noopEntries.add(entry);
                }
                case RNG_KEYED_CTOR_PIN -> keyedPin = entry;
                case CALL_SITE_REDIRECT -> {
                    eagerJarGate(entry);
                    redirectEntries.add(entry);
                }
                case NEW_TYPE_SWAP -> {
                    eagerJarGate(entry);
                    typeSwapEntries.add(entry);
                }
                case FOREIGN_LOAD_TIME_SEAM -> {
                    // NO eagerJarGate - a mod jar is never on
                    // java.class.path, so the gate would refuse a healthy serve. The host row arms the seam
                    // (armMagicSeam, above); naming it here may only agree with that row.
                    if (!magicSeam.on()) {
                        refuse(List.of(entry.id() + " is in -Dcc.transform.enable but "
                                + TransformRegistry.MAGIC_SEAM_ENABLED_FILE_KEY + " resolves false (from "
                                + magicSeam.from() + ") — remove it from -Dcc.transform.enable or set "
                                + TransformRegistry.MAGIC_SEAM_ENABLED_FILE_KEY + "=true"));
                    }
                }
                case SCOPED_WINDOW, FOREIGN_CALL_SITE_REDIRECT -> {
                    // Armed (and eager-gated) by the host row in
                    // armPilotShip, above; naming either here may only agree with that row.
                    if (!pilotShip.on()) {
                        refuse(List.of(entry.id() + " is in -Dcc.transform.enable but "
                                + TransformRegistry.PILOT_SHIP_ENABLED_FILE_KEY + " resolves false (from "
                                + pilotShip.from() + ") — remove it from -Dcc.transform.enable or set "
                                + TransformRegistry.PILOT_SHIP_ENABLED_FILE_KEY + "=true"));
                    }
                }
            }
        }
        // Arming order is load-bearing, whatever the enable list's order: the keyed pin FIRST (its
        // bootstrap holder append + the master seed), then the call-site redirects, whose rewritten
        // classes link the holder and so must resolve the appended copy. The type swaps need neither
        // (they link only bootstrap JDK classes) and arm on their own.
        PosturedSeed armedSeed = null;
        if (keyedPin != null) {
            // A postured boot reaches here only with the pins ON (OFF + a pin id refused above).
            armedSeed = pins.postured() && pins.on() ? resolvePosturedSeed(pins.file()) : null;
            long seed = armedSeed != null ? armedSeed.value() : resolveMasterSeed(keyedPin);
            armKeyedCtorPin(keyedPin, seed, instrumentation);
            if (armedSeed != null) {
                // The arming JVM's own truth for every runtime reader (ServerConfig.resolve's sysprop tier):
                // the boot pin's request == armed check, the per-round re-arm, the recording header, the
                // re-sim gate. Never on a file-less boot, whose children expect request != armed to throw.
                System.setProperty(RngPinKnobs.SEED_SYSPROP, Long.toString(seed));
                System.setProperty(RngPinKnobs.KEYED_SYSPROP, "true");
            }
            if (!redirectEntries.isEmpty()) {
                armCallSiteRedirects(redirectEntries, seed, instrumentation);
            }
        }
        if (!typeSwapEntries.isEmpty()) {
            // The applier's construction refuses a malformed
            // entry (unknown or non-subclass replacement, undeclared constructor) before it is armed.
            instrumentation.addTransformer(new NewTypeSwapApplier(typeSwapEntries), false);
            log("armed " + typeSwapEntries.stream().map(TransformRegistry.Entry::id).toList()
                    + ": type swap at class load, no prerequisite");
        }
        if (!noopEntries.isEmpty()) {
            instrumentation.addTransformer(new RegistryNoopApplier(noopEntries), false);
        }
        // LAST, so every fingerprint-gated applier above still sees the original bytes it verifies.
        armRngCensusIfRequested(keyedPin, instrumentation);
        List<String> armedIds = enabled.stream().map(TransformRegistry.Entry::id).toList();
        // The in-process receipt the app-side recording header
        // reads (a JVM-global sysprop is the one seam this bootstrap-side premain and the app share).
        System.setProperty("cc.transform.armed", String.join(",", armedIds));
        publishPinReceipt(pins, armedSeed, keyedPin == null ? null : System.getProperty(HOLDER_SHA_SYSPROP));
        log("armed: " + armedIds);
    }

    /** The in-process receipt of a postured boot's pin decision: {@code <on|off>,<from>,<seedFrom>} (META reads it). */
    static final String PIN_RECEIPT_SYSPROP = "cc.transform.rngpin";
    /** The sha256 of the holder jar bytes appended to the bootstrap search, set on every HOOK-0044 arm. */
    static final String HOLDER_SHA_SYSPROP = "cc.transform.sha.holder";

    /**
     * A postured boot's pin decision: {@code postured} = a config file loaded; {@code on} = the
     * resolved {@code rngpin.enabled}; {@code from} = the tier that decided it
     * ({@code default|sysprop|env|file}); {@code file} = the loaded rows (null when not postured).
     */
    private record PinPosture(boolean postured, boolean on, String from, java.util.Properties file) {
    }

    /** The master seed a postured ON boot arms with, and where it came from ({@code sysprop|env|file|fresh}). */
    private record PosturedSeed(long value, String from) {
    }

    /**
     * The postured pin decision. A file-less boot changes
     * nothing (the contract children, the registry verifier and the rig lanes stay byte-identical). A
     * named file that cannot be loaded refuses the launch, unless {@code cc.config.strict} is false
     * (then: not postured, one line, nothing added — the runtime's own serve-on-defaults shape). On a
     * postured boot {@code rngpin.enabled} resolves sysprop &gt; env &gt; file &gt; {@code true}, every
     * present tier parsed STRICT (exact {@code true} / {@code false}): this premain is the only parser
     * on some launch paths, and a loose value would flip the mode silently. ON adds the pin set (the
     * enable set dedupes an explicit list) and refuses an explicit keyed request that is not
     * {@code true}; OFF adds nothing and refuses an explicit list that names a pin id (incoherent).
     */
    private static PinPosture decidePinPosture(Set<String> enabledIds) {
        PremainConfigFile.Result config = readConfigOrNull();
        if (config == null) {
            return new PinPosture(false, false, "none", null);
        }
        if (config.props() == null) {
            if (PremainConfigFile.configStrict()) {
                refuse(List.of("the config file named by -D" + PremainConfigFile.CONFIG_PATH_PROPERTY + " / "
                        + PremainConfigFile.CONFIG_PATH_ENV + " is " + config.failure() + ": " + bounded(config.path())
                        + " — fix the path, or set " + PremainConfigFile.CONFIG_STRICT_PROPERTY
                        + "=false to serve on defaults (the RNG pins then stay off)"));
            }
            log("rngpin: config file " + config.failure() + " (" + bounded(config.path()) + ") under "
                    + PremainConfigFile.CONFIG_STRICT_PROPERTY + "=false — not postured, no pin added");
            return new PinPosture(false, false, "none", null);
        }
        java.util.Properties file = config.props();
        SwitchRow row = resolveStrictSwitch(RngPinKnobs.ENABLED_SYSPROP, RngPinKnobs.ENABLED_ENV,
                RngPinKnobs.ENABLED_FILE_KEY, file, true, "RNG pin switch");
        boolean on = row.on();
        String from = row.from();
        if (on) {
            String[][] keyed = {
                    {"sysprop", RngPinKnobs.KEYED_SYSPROP, System.getProperty(RngPinKnobs.KEYED_SYSPROP)},
                    {"env", RngPinKnobs.KEYED_ENV, System.getenv(RngPinKnobs.KEYED_ENV)},
                    {"file", RngPinKnobs.KEYED_FILE_KEY, file.getProperty(RngPinKnobs.KEYED_FILE_KEY)}};
            for (String[] tier : keyed) {
                if (tier[2] != null && !tier[2].isBlank() && !tier[2].trim().equalsIgnoreCase("true")) {
                    refuse(List.of(tier[1] + " (" + tier[0] + " tier) is '" + bounded(tier[2]) + "' but the RNG pins"
                            + " are on (" + RngPinKnobs.ENABLED_FILE_KEY + " resolves true, from " + from + ") — the"
                            + " pins always arm keyed; " + RngPinKnobs.ENABLED_FILE_KEY + "=false is the OFF lever"));
                }
            }
            enabledIds.addAll(RngPinKnobs.PIN_IDS);
        } else {
            for (String id : RngPinKnobs.PIN_IDS) {
                if (enabledIds.contains(id)) {
                    refuse(List.of(id + " is in -Dcc.transform.enable but " + RngPinKnobs.ENABLED_FILE_KEY
                            + " resolves false (from " + from + ") — remove the pin ids from -Dcc.transform.enable"
                            + " or set " + RngPinKnobs.ENABLED_FILE_KEY + "=true"));
                }
            }
        }
        return new PinPosture(true, on, from, file);
    }

    /** A strict on/off host row's resolved value and the tier that decided it ({@code default|sysprop|env|file}). */
    private record SwitchRow(boolean on, String from) {
    }

    /**
     * ONE strict on/off host row, walked sysprop &gt; env &gt; file &gt;
     * {@code dflt}; extracted from the replay-pin walk so the MagicLib seam row shares its tiers and its refusal
     * shape. A blank tier is absent, as the launcher and the keyed loop read it; every PRESENT
     * tier, a shadowed lower one included, must be exactly {@code true} or {@code false}, else the launch is
     * refused: this premain is the only parser on some launch paths, and a loose value would flip a mode
     * silently. {@code file} null = not postured (no file tier).
     */
    private static SwitchRow resolveStrictSwitch(String sysprop, String env, String fileKey,
            java.util.Properties file, boolean dflt, String switchName) {
        String[][] tiers = {
                {"sysprop", sysprop, System.getProperty(sysprop)},
                {"env", env, System.getenv(env)},
                {"file", fileKey, file == null ? null : file.getProperty(fileKey)}};
        String[] decided = null;
        for (String[] tier : tiers) {
            if (tier[2] == null || tier[2].isBlank()) {
                continue;
            }
            String v = tier[2].trim();
            if (!v.equals("true") && !v.equals("false")) {
                refuse(List.of(tier[1] + " (" + tier[0] + " tier) must be exactly true or false, got '"
                        + bounded(tier[2]) + "' — the " + switchName + " never guesses a mode"));
            }
            if (decided == null) {
                decided = new String[] {v, tier[0]};
            }
        }
        return decided == null ? new SwitchRow(dflt, "default") : new SwitchRow(decided[0].equals("true"), decided[1]);
    }

    /**
     * The MagicLib pilot seam's host row {@code magicseam.enabled}
     * (sysprop {@code cc.magicseam.enabled} &gt; env {@code CC_MAGICSEAM_ENABLED} &gt; the config file's row &gt;
     * true): ON registers {@link MagicSeamApplier} and sets the receipt {@code cc.magic.seam=armed}; OFF registers
     * nothing and sets {@code off}; a non-boolean tier refuses the launch. Decided here, never by a launch argument,
     * so the frozen serve command line stays unchanged. The applier is load-time only and fail-open (a third-party
     * target never refuses a launch), so a file-less boot arms it too: it costs one name compare per class define.
     * The log line avoids the {@code "armed:"} token the enable-list receipt prints.
     */
    private static SwitchRow armMagicSeam(java.util.Properties file, Instrumentation instrumentation) {
        SwitchRow row = resolveStrictSwitch(TransformRegistry.MAGIC_SEAM_ENABLED_SYSPROP,
                TransformRegistry.MAGIC_SEAM_ENABLED_ENV, TransformRegistry.MAGIC_SEAM_ENABLED_FILE_KEY, file, true,
                "MagicLib seam switch");
        if (row.on()) {
            instrumentation.addTransformer(new MagicSeamApplier(), false);
        }
        System.setProperty(TransformRegistry.MAGIC_SEAM_RECEIPT_SYSPROP,
                row.on() ? MagicSeamApplier.RECEIPT_ARMED : MagicSeamApplier.RECEIPT_OFF);
        log("magicseam: " + (row.on() ? "ON" : "OFF") + " from=" + row.from());
        return row;
    }

    /**
     * The player-ship window's host row {@code pilotship.enabled} (sysprop
     * {@code cc.pilotship.enabled} &gt; env {@code CC_PILOTSHIP_ENABLED} &gt; the config file's row &gt; TRUE;
     * FALSE while new):
     * OFF registers nothing and sets the receipt {@code cc.pilot.ship=off}, so every class loads as shipped. ON
     * runs the eager gate first, fail-closed because the wrap edits an engine method: the ship-advance fingerprint
     * on the engine jar ({@link #eagerJarGate}), then the holder and typed-target probe; a miss sets
     * {@code refused:<reason>}, prints the refusal row and refuses the launch naming the row. A pass registers
     * {@link PilotShipApplier} with the file-only {@code pilotship.exclude} list and sets {@code armed}. Decided
     * here, never by a launch argument, so the frozen serve command line stays unchanged. The switch's own rows
     * (ON / OFF, refused) carry the {@code [cc-premain] pilotship} prefix, on stderr beside the launch decisions.
     * <p>The shipped default is ON, and the default tier arms only a JVM that CARRIES the
     * engine jar (on java.class.path or the explicit override): a JVM without one has no ship advance to wrap — a
     * contract child, a tool JVM — so the window stays OFF with its own row and receipt, never a refusal the
     * host did not ask for. An EXPLICIT {@code true} tier keeps the refusal (the host asked; the gate is the law).
     */
    private static SwitchRow armPilotShip(java.util.Properties file, Instrumentation instrumentation) {
        SwitchRow row = resolveStrictSwitch(TransformRegistry.PILOT_SHIP_ENABLED_SYSPROP,
                TransformRegistry.PILOT_SHIP_ENABLED_ENV, TransformRegistry.PILOT_SHIP_ENABLED_FILE_KEY, file, true,
                "player-ship switch");
        TransformRegistry.Entry window = TransformRegistry.byId(TransformRegistry.PILOT_SHIP_WINDOW_ID);
        if (row.on() && "default".equals(row.from()) && findTargetJar(window) == null) {
            System.err.println("[cc-premain] pilotship: OFF from=default (no " + window.targetJarFileName()
                    + " on this JVM; the shipped default arms only a serve that carries the engine)");
            System.setProperty(TransformRegistry.PILOT_SHIP_RECEIPT_SYSPROP, TransformRegistry.PILOT_SHIP_RECEIPT_OFF);
            return new SwitchRow(false, row.from());
        }
        System.err.println("[cc-premain] pilotship: " + (row.on() ? "ON" : "OFF") + " from=" + row.from());
        if (!row.on()) {
            System.setProperty(TransformRegistry.PILOT_SHIP_RECEIPT_SYSPROP, TransformRegistry.PILOT_SHIP_RECEIPT_OFF);
            return row;
        }
        try {
            eagerJarGate(window);
        } catch (IllegalStateException gateRefused) {
            // the gate already printed its boundary (jar unresolvable, class absent or fingerprint drift)
            refusePilotShip(row, "ship_fingerprint", window.id() + " launch gate refused (the boundary above)");
        }
        String[] holder = PilotShipApplier.holderProbeRefusal();
        if (holder != null) {
            refusePilotShip(row, holder[0], holder[1]);
        }
        instrumentation.addTransformer(new PilotShipApplier(window, PilotShipApplier.parseExclude(file)), false);
        System.setProperty(TransformRegistry.PILOT_SHIP_RECEIPT_SYSPROP, TransformRegistry.PILOT_SHIP_RECEIPT_ARMED);
        return row;
    }

    /** The player-ship refusal: receipt + refusal row, then the launch refusal naming the host row (throws). */
    private static void refusePilotShip(SwitchRow row, String reason, String boundary) {
        PilotShipApplier.publishRefused(reason);
        refuse(List.of(boundary + " — " + TransformRegistry.PILOT_SHIP_ENABLED_FILE_KEY + " resolves true (from "
                + row.from() + "); set " + TransformRegistry.PILOT_SHIP_ENABLED_FILE_KEY + "=false to start"));
    }

    /**
     * A postured ON boot's master seed: sysprop &gt; env &gt;
     * file {@code rngpin.seed} &gt; a FRESH {@link java.security.SecureRandom} draw (varied waves
     * every serve; never a clock or a seedless {@code Random}, whose ctor this very pin rewrites).
     * Every present tier must be a long: a blank or {@code unpinned} value under ON refuses (the
     * operator asked for an unpinned run the switch says is pinned — no silent mode flip).
     */
    private static PosturedSeed resolvePosturedSeed(java.util.Properties file) {
        String[][] tiers = {
                {"sysprop", RngPinKnobs.SEED_SYSPROP, System.getProperty(RngPinKnobs.SEED_SYSPROP)},
                {"env", RngPinKnobs.SEED_ENV, System.getenv(RngPinKnobs.SEED_ENV)},
                {"file", RngPinKnobs.SEED_FILE_KEY, file.getProperty(RngPinKnobs.SEED_FILE_KEY)}};
        PosturedSeed decided = null;
        for (String[] tier : tiers) {
            if (tier[2] == null) {
                continue;
            }
            String v = tier[2].trim();
            if (v.isEmpty() || RngPinKnobs.UNPINNED.equalsIgnoreCase(v)) {
                refuse(List.of(tier[1] + " (" + tier[0] + " tier) is '" + bounded(tier[2]) + "' but the RNG pins are"
                        + " on — " + RngPinKnobs.ENABLED_FILE_KEY + "=false is the OFF lever; remove "
                        + tier[1] + " to take a fresh seed"));
            }
            long parsed;
            try {
                parsed = Long.parseLong(v);
            } catch (NumberFormatException e) {
                refuse(List.of(tier[1] + " (" + tier[0] + " tier) must be a long master seed, got: " + bounded(tier[2])));
                throw new AssertionError("unreachable");
            }
            if (decided == null) {
                decided = new PosturedSeed(parsed, tier[0]);
            }
        }
        return decided != null ? decided : new PosturedSeed(new java.security.SecureRandom().nextLong(), "fresh");
    }

    /**
     * A postured boot's ONE receipt line + the in-process
     * {@link #PIN_RECEIPT_SYSPROP} the recording header reads; a file-less boot prints and sets nothing
     * (the registry verifier's output holds). {@code seed} null = nothing armed with a seed;
     * {@code holderSha} null = no holder appended.
     */
    private static void publishPinReceipt(PinPosture pins, PosturedSeed seed, String holderSha) {
        if (!pins.postured()) {
            return;
        }
        String seedFrom = seed == null ? "none" : seed.from();
        System.setProperty(PIN_RECEIPT_SYSPROP, (pins.on() ? "on," : "off,") + pins.from() + "," + seedFrom);
        log("rngpin: " + (pins.on() ? "ON" : "OFF") + " from=" + pins.from()
                + " seed=" + (seed == null ? "none" : Long.toString(seed.value())) + " seedFrom=" + seedFrom
                + " holderSha256=" + (holderSha == null ? "none" : holderSha));
    }

    /**
     * The named config file's read, or {@code null} when none is
     * named: plain {@link java.util.Properties#load(InputStream)} (ISO-8859-1, the runtime's own call)
     * over a read bounded by {@link PremainConfigFile#MAX_CONFIG_BYTES}, so a file that grows past the
     * cap is refused even when it grew after a size check. A failure is returned, never thrown: the
     * caller owns the strict / non-strict verdict.
     */
    private static PremainConfigFile.Result readConfigOrNull() {
        String named = PremainConfigFile.configuredPathOrNull();
        if (named == null) {
            return null;
        }
        Path path;
        try {
            path = Path.of(named);
        } catch (java.nio.file.InvalidPathException e) {
            return new PremainConfigFile.Result(named, null, "unreadable: " + e.getClass().getSimpleName());
        }
        if (!Files.isReadable(path)) {
            return new PremainConfigFile.Result(named, null, "missing");
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes((int) PremainConfigFile.MAX_CONFIG_BYTES + 1);
            if (bytes.length > PremainConfigFile.MAX_CONFIG_BYTES) {
                return new PremainConfigFile.Result(named, null, "over the " + PremainConfigFile.MAX_CONFIG_BYTES + " B cap");
            }
            java.util.Properties props = new java.util.Properties();
            props.load(new java.io.ByteArrayInputStream(bytes));
            return new PremainConfigFile.Result(named, props, null);
        } catch (IOException | RuntimeException e) {
            return new PremainConfigFile.Result(named, null, "unreadable: " + e.getClass().getSimpleName());
        }
    }

    /** An operator value as a refusal / log line may echo it: at most 64 chars, printable ASCII only. */
    private static String bounded(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && i < 64; i++) {
            char c = raw.charAt(i);
            sb.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        return raw.length() > 64 ? sb + "+len=" + raw.length() : sb.toString();
    }

    /** The eager jar-site gate: resolve the target jar, read the target class, verify every fingerprint (refuses on any miss). */
    private static void eagerJarGate(TransformRegistry.Entry entry) {
        Path jar = resolveTargetJar(entry);
        byte[] classBytes = readTargetClassBytes(entry, jar);
        List<String> refusals = TransformLaunchGate.verify(entry, classBytes);
        if (!refusals.isEmpty()) {
            refuse(refusals);
        }
        log("launch gate PASS: " + entry.id() + " — " + entry.methods().size()
                + " fingerprints verified on " + entry.targetClassName() + " in " + jar);
    }

    /**
     * Refuses, before any jar is read or anything armed, a
     * call-site redirect enabled without the keyed constructor pin (the redirects link the holder
     * only that pin appends to the bootstrap search, and take its master seed), and the landing
     * redirect enabled without the id redirect (the landing key is the fighter's ship id, which is
     * deterministic only while the id mint is pinned).
     */
    private static void refuseMissingRedirectPrerequisites(List<TransformRegistry.Entry> enabled) {
        Set<String> ids = new LinkedHashSet<>();
        boolean keyedPinEnabled = false;
        for (TransformRegistry.Entry entry : enabled) {
            ids.add(entry.id());
            keyedPinEnabled |= entry.kind() == TransformRegistry.Kind.RNG_KEYED_CTOR_PIN;
        }
        List<String> keyedPinIds = TransformRegistry.entries().stream()
                .filter(e -> e.kind() == TransformRegistry.Kind.RNG_KEYED_CTOR_PIN)
                .map(TransformRegistry.Entry::id).toList();
        for (TransformRegistry.Entry entry : enabled) {
            if (entry.kind() == TransformRegistry.Kind.CALL_SITE_REDIRECT && !keyedPinEnabled) {
                refuse(List.of(entry.id() + ": needs " + String.join("/", keyedPinIds) + " enabled — a call-site"
                        + " redirect links the rng holder, which only the keyed Random pin appends to the bootstrap"
                        + " search, and takes its master seed; enable both (with -D" + RngPinKnobs.SEED_SYSPROP + ")"));
            }
        }
        if (ids.contains(TransformRegistry.LANDING_REDIRECT_ID) && !ids.contains(TransformRegistry.ID_REDIRECT_ID)) {
            refuse(List.of(TransformRegistry.LANDING_REDIRECT_ID + ": needs " + TransformRegistry.ID_REDIRECT_ID
                    + " enabled — the landing key is the fighter's ship id, deterministic only while the id mint"
                    + " is pinned"));
        }
    }

    /**
     * Arms the call-site redirects AFTER
     * {@link #armKeyedCtorPin} appended the holder jar to the bootstrap search: builds the applier
     * (its construction refuses a malformed entry before anything is armed), inits
     * {@code CcRngRedirect} with the SAME master seed — the first executed reference to it, so it
     * resolves the bootstrap copy — confirms it did, then registers the class-load transformer. The
     * targets ({@code Misc}, the landing-bay class) load later through the game's loaders and are
     * rewritten then.
     */
    private static void armCallSiteRedirects(List<TransformRegistry.Entry> redirects, long seed,
            Instrumentation instrumentation) {
        CallSiteRedirectApplier applier = new CallSiteRedirectApplier(redirects);
        // First executed CcRngRedirect reference — resolves through armKeyedCtorPin's bootstrap append.
        cc.starsector.server.rngpin.CcRngRedirect.init(seed);
        if (cc.starsector.server.rngpin.CcRngRedirect.class.getClassLoader() != null) {
            refuse(List.of(redirects.get(0).id() + ": CcRngRedirect resolved from "
                    + cc.starsector.server.rngpin.CcRngRedirect.class.getClassLoader()
                    + ", not the bootstrap holder — the rewritten game classes would link an uninitialised copy"));
        }
        instrumentation.addTransformer(applier, false);
        for (TransformRegistry.Entry entry : redirects) {
            TransformRegistry.Redirect r = entry.redirect();
            log("armed " + entry.id() + ": " + entry.targetClassName() + "." + entry.methods().get(0).name()
                    + " " + r.fromOwner() + "." + r.fromName() + " -> CcRngRedirect." + r.toName()
                    + " (masterSeed=" + seed + ", rewritten when the class loads)");
        }
    }

    /**
     * The {@code Math.random()} caller CENSUS — a
     * diagnostic, default OFF: {@code -Dcc.rngpin.census=<ticks>} (or env) arms
     * {@link MathRandomCensusApplier} and the holder's window, AFTER every registry applier (the
     * gated appliers verify the ORIGINAL bytes, so the census must rewrite last). Needs the keyed
     * pin: its bootstrap append is what lets every game loader link the holder the rewritten sites
     * call. Not a registry entry by design — it changes no behavior (one draw per call, the stream
     * untouched) and fingerprints nothing; the knob is registered as an INSTRUMENT posture row.
     */
    private static void armRngCensusIfRequested(TransformRegistry.Entry keyedPin, Instrumentation instrumentation) {
        String raw = System.getProperty(RngPinKnobs.CENSUS_SYSPROP);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(RngPinKnobs.CENSUS_ENV);
        }
        if (raw == null || raw.isBlank()) {
            if (censusStackMatchOrNull() != null) {
                refuse(List.of("-D" + RngPinKnobs.CENSUS_STACK_SYSPROP + " set without -D" + RngPinKnobs.CENSUS_SYSPROP
                        + "=<ticks> — the stack capture prints only inside a census window"));
            }
            return;
        }
        if (keyedPin == null) {
            refuse(List.of("-D" + RngPinKnobs.CENSUS_SYSPROP + " needs the keyed Random pin enabled — the census"
                    + " sites link the rng holder, which only that pin appends to the bootstrap search"));
        }
        int ticks = 0;
        try {
            ticks = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            refuse(List.of("-D" + RngPinKnobs.CENSUS_SYSPROP + " must be a positive tick count, got: " + raw));
        }
        if (ticks <= 0) {
            refuse(List.of("-D" + RngPinKnobs.CENSUS_SYSPROP + " must be > 0, got: " + raw));
        }
        if (cc.starsector.server.rngpin.CcRngRedirect.class.getClassLoader() != null) {
            refuse(List.of("rng census: CcRngRedirect resolved from "
                    + cc.starsector.server.rngpin.CcRngRedirect.class.getClassLoader()
                    + ", not the bootstrap holder — the rewritten sites would count into an unarmed copy"));
        }
        cc.starsector.server.rngpin.CcRngRedirect.censusArm(ticks);
        String[] stackMatch = censusStackMatchOrNull();
        if (stackMatch != null) {
            cc.starsector.server.rngpin.CcRngRedirect.censusArmStack(stackMatch);
        }
        instrumentation.addTransformer(new MathRandomCensusApplier(), false);
        log("armed rng census: every Math.random() call site outside the JDK -> CcRngRedirect.random(site), one draw"
                + " per call (the stream untouched); window = " + ticks + " sim ticks from the frame-0 re-arm (diagnostic)"
                + (stackMatch == null ? "" : "; stack capture at sites matching " + String.join(",", stackMatch)));
    }

    /**
     * The census's per-site STACK capture request — {@code -Dcc.rngpin.censusStack=
     * <sub[,sub]>} (or env), comma-split, blanks dropped; null when unset. Called only with a census window
     * armed; the caller without one refuses below (a stack request that could never print is a misconfig).
     */
    private static String[] censusStackMatchOrNull() {
        String raw = System.getProperty(RngPinKnobs.CENSUS_STACK_SYSPROP);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(RngPinKnobs.CENSUS_STACK_ENV);
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        java.util.List<String> subs = new java.util.ArrayList<>();
        for (String s : raw.split(",")) {
            if (!s.isBlank()) {
                subs.add(s.trim());
            }
        }
        if (subs.isEmpty()) {
            refuse(List.of("-D" + RngPinKnobs.CENSUS_STACK_SYSPROP + " has no site-name substring, got: " + raw));
        }
        return subs.toArray(new String[0]);
    }

    /**
     * The master seed of a boot that is NOT postured (sysprop/env ONLY — no config file loaded, so
     * there is no file row and no mint; a postured boot takes {@link #resolvePosturedSeed}); refuses
     * when absent, unpinned or not a long, naming {@code entry}. The ONE parse both the keyed pin and
     * the call-site redirects arm with.
     */
    private static long resolveMasterSeed(TransformRegistry.Entry entry) {
        String raw = System.getProperty(RngPinKnobs.SEED_SYSPROP);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(RngPinKnobs.SEED_ENV);
        }
        if (raw == null || raw.isBlank() || RngPinKnobs.UNPINNED.equalsIgnoreCase(raw.trim())) {
            refuse(List.of(entry.id() + ": enabled but no master seed — set -D" + RngPinKnobs.SEED_SYSPROP
                    + "=<long> or env " + RngPinKnobs.SEED_ENV + " (no config file is loaded: the '"
                    + RngPinKnobs.SEED_FILE_KEY + "' row and the fresh seed are read only on a postured boot);"
                    + " a keyed run without a seed would be a silent-nondeterminism boundary"));
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            refuse(List.of(entry.id() + ": -D" + RngPinKnobs.SEED_SYSPROP
                    + " must be a long master seed, got: " + raw));
            throw new AssertionError("unreachable");
        }
    }

    /**
     * Verify + arm the keyed {@code java.util.Random.<init>()V} pin.
     * Order is load-bearing: the seed is resolved first ({@link #resolveMasterSeed}, or
     * {@link #resolvePosturedSeed} on a postured boot), then
     * ANY-OF-fingerprint the RUNNING JVM's own Random bytes, append {@code cc-rngpin-holder.jar} to
     * the bootstrap search BEFORE the first executed {@code CcRngKeyed} reference (else the
     * app-classpath copy loads and the armed state splits from the copy the rewritten bootstrap
     * Random links against), init, one-shot retransform, verify the structural fail-closed anchor
     * (exactly 1 nanoTime replaced + exactly 1 lxor tapped), and only then {@code confirmArmed()}.
     * Any miss REFUSES the launch.
     */
    private static void armKeyedCtorPin(TransformRegistry.Entry entry, long seed, Instrumentation instrumentation) {
        byte[] classBytes = readRunningJvmClassBytes(entry);
        List<String> refusals = TransformLaunchGate.verify(entry, classBytes);
        if (!refusals.isEmpty()) {
            refuse(refusals);
        }
        log("launch gate PASS: " + entry.id() + " — running-JVM " + entry.targetClassName()
                + " fingerprint in the accepted set (java " + System.getProperty("java.runtime.version") + ")");
        // The matched code hash, over the SAME bytes the gate
        // just verified, as an in-process receipt for the recording header.
        System.setProperty("cc.transform.sha." + entry.id(),
                cc.starsector.server.transform.MethodBytecodeFingerprint.sha256OfMethodCode(classBytes, "<init>", "()V"));

        File holderJar = new File(premainJarDirectory(entry), "cc-rngpin-holder.jar");
        if (!holderJar.isFile()) {
            refuse(List.of(entry.id() + ": cc-rngpin-holder.jar missing next to cc-premain.jar ("
                    + holderJar + ") — run server\\build.ps1"));
        }
        // The holder's identity beside the Random hash: the bytes ride the same
        // build snapshot as this premain, so a mismatched holder is visible in the arming JVM itself.
        try {
            System.setProperty(HOLDER_SHA_SYSPROP, java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(holderJar.toPath()))));
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            refuse(List.of(entry.id() + ": cannot hash the holder jar " + holderJar + " — " + e));
        }
        try {
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(holderJar));
        } catch (IOException e) {
            refuse(List.of(entry.id() + ": cannot append holder jar to the bootstrap search — " + e));
        }
        // First executed CcRngKeyed reference — resolves through the bootstrap append above.
        cc.starsector.server.rngpin.CcRngKeyed.init(seed);

        RngKeyedCtorPinApplier applier = new RngKeyedCtorPinApplier(seed);
        instrumentation.addTransformer(applier, true);
        try {
            // The class literal loads Random if it is not loaded yet (through the registered
            // transformer); the retransform then covers the already-loaded case. Either way the
            // LAST transformer invocation is the one whose site counts we verify below.
            instrumentation.retransformClasses(java.util.Random.class);
        } catch (UnmodifiableClassException e) {
            refuse(List.of(entry.id() + ": java.util.Random not retransformable on this JVM — " + e));
        } finally {
            instrumentation.removeTransformer(applier);
        }
        if (applier.lastRunSites != 1) {
            refuse(List.of(entry.id() + ": FAIL-CLOSED — expected exactly 1 System.nanoTime() site in "
                    + "java.util.Random.<init>()V, last transform saw " + applier.lastRunSites
                    + " — JDK Random shape changed; do NOT trust this run as pinned"));
        }
        if (applier.lastRunTaps != 1) {
            refuse(List.of(entry.id() + ": FAIL-CLOSED — expected exactly 1 lxor tap in "
                    + "java.util.Random.<init>()V, last transform saw " + applier.lastRunTaps
                    + " — JDK Random shape changed; do NOT trust this run as pinned"));
        }
        cc.starsector.server.rngpin.CcRngKeyed.confirmArmed();
        log("armed " + entry.id() + ": java.util.Random.<init>() nanoTime -> masterSeed=" + seed
                + "; KEYED order-independent seeds (masterSeed + callerClass + per-caller index)");
    }

    /** The running JVM's OWN bytes for a JDK-site target ({@code java.base} module, not a jar). */
    private static byte[] readRunningJvmClassBytes(TransformRegistry.Entry entry) {
        try (InputStream in = ClassLoader.getSystemResourceAsStream(entry.targetClassResource())) {
            if (in == null) {
                refuse(List.of(entry.id() + ": running JVM does not expose " + entry.targetClassResource()));
            }
            return in.readAllBytes();
        } catch (IOException e) {
            refuse(List.of(entry.id() + ": cannot read running-JVM bytes for "
                    + entry.targetClassResource() + " — " + e));
            throw new AssertionError("unreachable");
        }
    }

    /** The directory holding cc-premain.jar (CodeSource; scout precedent for sibling-jar lookup). */
    private static File premainJarDirectory(TransformRegistry.Entry entry) {
        try {
            return new File(CcTransformPremain.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).getParentFile();
        } catch (URISyntaxException | RuntimeException e) {
            refuse(List.of(entry.id() + ": cannot locate cc-premain.jar's own directory — " + e));
            throw new AssertionError("unreachable");
        }
    }

    private static Set<String> parseEnabledIds() {
        String value = System.getProperty("cc.transform.enable");
        if (value == null || value.isBlank()) {
            value = System.getenv("CC_TRANSFORM_ENABLE");
        }
        Set<String> ids = new LinkedHashSet<>();
        if (value != null) {
            for (String part : value.split(",")) {
                if (!part.isBlank()) {
                    ids.add(part.trim());
                }
            }
        }
        return ids;
    }

    /** Explicit {@code -Dcc.transform.jar.<ID>} override, else the matching java.class.path entry; else refuses. */
    private static Path resolveTargetJar(TransformRegistry.Entry entry) {
        Path jar = findTargetJar(entry);
        if (jar == null) {
            refuse(List.of(entry.id() + ": cannot resolve target jar '" + entry.targetJarFileName()
                    + "' on java.class.path — pass -Dcc.transform.jar." + entry.id() + "=<path>"));
        }
        return jar;
    }

    /**
     * The non-refusing half of {@link #resolveTargetJar}: the explicit override (a missing file there still refuses:
     * the operator named it), else the first java.class.path entry named like the target jar, else null (the
     * player-ship default tier reads null as "not a serve").
     */
    private static Path findTargetJar(TransformRegistry.Entry entry) {
        String override = System.getProperty("cc.transform.jar." + entry.id());
        if (override != null && !override.isBlank()) {
            Path jar = Path.of(override.trim());
            if (!Files.isRegularFile(jar)) {
                refuse(List.of(entry.id() + ": -Dcc.transform.jar." + entry.id()
                        + " names a missing file: " + jar));
            }
            return jar;
        }
        String classPath = System.getProperty("java.class.path", "");
        for (String element : classPath.split(File.pathSeparator)) {
            if (element.isBlank()) {
                continue;
            }
            Path candidate = Path.of(element.trim());
            if (candidate.getFileName() != null
                    && candidate.getFileName().toString().equalsIgnoreCase(entry.targetJarFileName())
                    && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static byte[] readTargetClassBytes(TransformRegistry.Entry entry, Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry classEntry = zip.getEntry(entry.targetClassResource());
            if (classEntry == null) {
                refuse(List.of(entry.id() + ": " + entry.targetClassResource()
                        + " not found in " + jar));
            }
            try (InputStream in = zip.getInputStream(classEntry)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            refuse(List.of(entry.id() + ": cannot read target class from " + jar + " — " + e));
            throw new AssertionError("unreachable");
        }
    }

    /** Prints every named boundary, then throws — the JVM refuses to launch during -javaagent processing. */
    private static void refuse(List<String> boundaries) {
        for (String boundary : boundaries) {
            System.err.println("[cc-transform] REFUSING LAUNCH: " + boundary);
        }
        throw new IllegalStateException("cc-transform launch gate refused: " + boundaries.get(0));
    }

    static void log(String message) {
        System.err.println("[cc-transform] " + message);
    }
}
