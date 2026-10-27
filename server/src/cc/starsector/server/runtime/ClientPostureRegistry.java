package cc.starsector.server.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * The CLIENT posture census + resolver.
 *
 * The client twin of {@link PostureRegistry}: one census of every client-side
 * behavior/instrument sysprop lane, resolved sysprop &gt; env twin &gt; compiled (no file tier —
 * clients have no config file by design), dumped in the EXACT server grammar
 * ({@code posture key=... value=... source=... class=...} + the trailer family) so the
 * check-lane-parity parse family reads both sides with one regex set.
 *
 * <p>WHY shared + bundled: this class is evaluated by BOTH sides — the client computes its LIVE
 * posture (sysprops as delivered) and echoes the hash post-admit (M4-A2 frames); the server
 * computes the EXPECTED baseline ({@link #baseline()}: every row untouched, zero unknowns) from
 * the SAME fingerprint-locked class, so echoed-hash != expected-hash is machine proof the client
 * was locally re-postured (or a real bug) — the server-settable law's detection half
 * (suppression arrives per-knob as lanes convert to
 * wire-block delivery). Bundled WHOLE into the mod jar like HandshakeCodec
 * ({@code client-mod/build.ps1} fold list).
 *
 * <p>WHY a hand-rolled SHA-256 ({@link #sha256}): {@code MessageDigest.getInstance} is
 * reflection-tainted at runtime under the mod's classloader B, so the digest
 * is a pure static FIPS 180-4 implementation with no java.io/java.security/reflect refs —
 * B-legal by construction; {@code ClientPostureContractRows} proves it equal to
 * {@code MessageDigest} on every suite run (typo = loud suite RED, never a wrong hash in prod).
 *
 * <p>Census provenance: a reconciled sweep of the source and the doc tables,
 * re-verified against source with exact keys + env twins;
 * corrections vs that census: {@code cc.match.flyinBurnSec} and {@code cc.watch.markerKey}
 * carry NO env twin ({@code KnobParse.floatOr}/{@code intOr} read only getProperty — of the
 * KnobParse family only {@code longOr} reads env). Untouched rows print {@code (compiled)}
 * (values are never duplicated here — the jar-locked compiled default is the truth), so the
 * BASELINE hash is a pure function of the census key list, contract-pinned as the
 * client-golden (minted at M4-A2).
 *
 * <p>Unknown-key scan (default-deny): any present {@code cc.predict.*} /
 * {@code cc.watch.*} / {@code cc.parity.*} / {@code cc.mtrack.*} sysprop that is neither
 * censused nor a known INFRA/credential launcher key dumps as a {@code posture-unknown-key=} row
 * and joins BOTH hashes — an un-censused new knob still flips the hash when armed. Credentials
 * are EXCLUDE-BY-LAW: never censused, scan-exempt, and any credential-SHAPED unknown value
 * prints {@code <set>} (the describeDeliveredPredictKnobs redaction predicate, verbatim).
 *
 * <p>The unknown-key scan reads SYSPROPS ONLY — env cannot smuggle an un-censused knob past it
 * (the env-side bound, verified): client lanes read env only through explicit
 * per-row literal twins (census {@code env} column; of the KnobParse family only {@code longOr}
 * reads env at all), so an env-armed unknown would need a NEW hand-rolled {@code System.getenv}
 * read in client code. The census build gate that once failed the client build on such a read
 * (M4-A2) was DELETED for zero catches, and its hand tool retired:
 * a NEW client {@code cc.*} behaviour knob belongs under a {@code SCAN_PREFIXES} family
 * (hashed whenever armed) or gets a KNOBS row here; a hand-rolled getenv is a code-review item.
 */
public final class ClientPostureRegistry {

    /** Client knob classes — BEHAVIOR moves the parity verdict; INSTRUMENT only the full hash. */
    public enum KnobClass { BEHAVIOR, INSTRUMENT }

    /** One censused client input: canonical sysprop key, optional env twin, class. */
    public record Knob(String key, String env, KnobClass cls) {
    }

    /**
     * One resolved posture: the dump lines (grammar above), both raw digests (32 bytes each —
     * the M4-A2 echo carries both; verdicts key on {@code behaviorHash}, {@code hash} is
     * informational drift), row/unknown counts, and whether ANY row resolved externally.
     */
    public record Posture(List<String> lines, byte[] hash, byte[] behaviorHash, int rows,
            int unknown, boolean postured) {
    }

    /**
     * The census, in the recon census's order (mode lanes; boolean lanes; sweep-only finds; puppet family —
     * dead-in-flow, rows print (compiled) until/unless re-armed; instruments). Read
     * sites + per-row provenance: the original recon record. 67 BEHAVIOR + 17 INSTRUMENT at the first census;
     * live counts (72 BEHAVIOR + 18 INSTRUMENT since cc.watch.cameraLockPlayer joined) are asserted by ClientPostureContractRows.censusHygiene.
     */
    private static final List<Knob> KNOBS = List.of(
            // --- BEHAVIOR: predict mode lanes (class-load-resolved static finals) ---
            new Knob("cc.predict.ownVelReconcile", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.foeEngineState", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.weaponState", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.beamPresentation", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.beamCycle", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.foeInterp", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.foeFacing", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.ownEngineState", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.foeSystemState", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.ownBayMint", null, KnobClass.BEHAVIOR), //
            new Knob("cc.predict.fighterHud", null, KnobClass.BEHAVIOR), //
            new Knob("cc.predict.fighterFx", null, KnobClass.BEHAVIOR), //
            new Knob("cc.predict.foeManeuver", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.foeFlux", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.foeFluxFlags", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.ownFlux", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.ownCr", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.ownCloak", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.ownDroneFacing", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.foeAutofire", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.foeSelection", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.foeShield", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.foeCloak", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.foeHullReconcile", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.foeArmorReconcile", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.ownArmorReconcile", null, KnobClass.BEHAVIOR), //
            new Knob("cc.predict.armorCells", null, KnobClass.BEHAVIOR), // v11 armor cells
            new Knob("cc.predict.cloakWall", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.ownFireLock", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.ownBeamCycle", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.dischargeReconcile", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.ownDetDamage", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.missilePose", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.ownMissile", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.ownMissilePolicy", "CC_PREDICT_OWN_MISSILE_POLICY",
                    KnobClass.BEHAVIOR),
            new Knob("cc.predict.phaseDive", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.weaponGlow", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.remoteAim", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.remoteAimSmooth", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.sameSideAim", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.teleportReconcile", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.flyinParity", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.sameSideHoldMode", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.deployFeedMute", null, KnobClass.BEHAVIOR),
            // --- BEHAVIOR: boolean/on-off lanes ---
            new Knob("cc.predict.stepRebase", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.burstExempt", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.velocityGlide", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.clockServo", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.leakRemove", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.eventReplay", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.chatBubbles", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.chatPaste", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.listPicker", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.droneStrikeFx", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.recallFx", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.bombPose", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.mineFx", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.bombEndFx", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.minePrimeFx", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.bombImpactFx", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.paladinDetFx", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.officerSkills", null, KnobClass.BEHAVIOR),
            new Knob("cc.predict.officerPortraits", null, KnobClass.BEHAVIOR),
            // --- BEHAVIOR: sweep-only finds (netcode reconcile levers + both-sides knobs) ---
            new Knob("cc.predict.snapTickConflate", "CC_PREDICT_SNAP_TICK_CONFLATE",
                    KnobClass.BEHAVIOR),
            new Knob("cc.predict.snapStaleFold", "CC_PREDICT_SNAP_STALE_FOLD", KnobClass.BEHAVIOR),
            new Knob("cc.predict.snapStalePassthrough", "CC_PREDICT_SNAP_STALE_PASSTHROUGH",
                    KnobClass.BEHAVIOR),
            new Knob("cc.predict.serverTickRate", "CC_PREDICT_SERVER_TICK_RATE",
                    KnobClass.BEHAVIOR),
            new Knob("cc.match.flyinBurnSec", null, KnobClass.BEHAVIOR),
            // --- BEHAVIOR: puppet/legacy family (dead-in-flow; env twins as coded) ---
            new Knob("cc.maneuver.shipId", "CC_MANEUVER_SHIP_ID", KnobClass.BEHAVIOR),
            new Knob("cc.puppet.playerPiloted", "CC_PUPPET_PLAYER_PILOTED", KnobClass.BEHAVIOR),
            new Knob("cc.puppet.serverEntityIndex", "CC_PUPPET_SERVER_ENTITY_INDEX",
                    KnobClass.BEHAVIOR),
            new Knob("cc.puppet.interpDelayMillis", "CC_PUPPET_INTERP_DELAY_MILLIS",
                    KnobClass.BEHAVIOR),
            // --- INSTRUMENT (full-hash only; the kit's sideLog checkbox must not flip verdicts) ---
            new Knob("cc.predict.dmgWitness", null, KnobClass.INSTRUMENT),
            new Knob("cc.predict.connectFailBanner", null, KnobClass.INSTRUMENT),
            new Knob("cc.predict.shotLogMax", null, KnobClass.INSTRUMENT),
            new Knob("cc.predict.intentSource", null, KnobClass.INSTRUMENT),
            new Knob("cc.predict.stallRead", null, KnobClass.INSTRUMENT),
            new Knob("cc.predict.stallWrite", null, KnobClass.INSTRUMENT),
            new Knob("cc.predict.smProbePath", "CC_PREDICT_SM_PROBE_PATH", KnobClass.INSTRUMENT),
            new Knob("cc.mtrack.every", "CC_MTRACK_EVERY", KnobClass.INSTRUMENT),
            new Knob("cc.mtrack.predict", "CC_MTRACK_PREDICT", KnobClass.INSTRUMENT),
            new Knob("cc.parity.frameLedger", "CC_PARITY_FRAME_LEDGER", KnobClass.INSTRUMENT),
            new Knob("cc.parity.walkEvery", "CC_PARITY_WALK_EVERY", KnobClass.INSTRUMENT),
            new Knob("cc.parity.walkMods", "CC_PARITY_WALK_MODS", KnobClass.INSTRUMENT),
            new Knob("cc.parity.roundBudget", "CC_PARITY_ROUND_BUDGET", KnobClass.INSTRUMENT),
            new Knob("cc.parity.lifetimeBudget", "CC_PARITY_LIFETIME_BUDGET",
                    KnobClass.INSTRUMENT),
            new Knob("cc.watch.markerKey", null, KnobClass.INSTRUMENT),
            new Knob("cc.watch.cameraLockPlayer", null, KnobClass.INSTRUMENT), // rig camera lock
            new Knob("cc.watch.sideLog", "CC_WATCH_SIDELOG", KnobClass.INSTRUMENT),
            new Knob("cc.debug.serverHullBar", null, KnobClass.INSTRUMENT));

    /** Prefix families the unknown-key scan watches (CENSUS.md rec 2). */
    private static final List<String> SCAN_PREFIXES =
            List.of("cc.predict.", "cc.watch.", "cc.parity.", "cc.mtrack.");

    /**
     * Scan-family keys that are legitimately per-client and NEVER posture (CENSUS.md EXCLUDE:
     * launcher-armed INFRA endpoint/identity keys + the credential family) — present on every
     * real joiner, so counting them unknown would make every joiner "mismatch". Credentials are
     * additionally never printed anywhere (redaction below backstops unknown NEW ones).
     */
    private static final Set<String> SCAN_EXEMPT = Set.of(
            "cc.predict.host", "cc.predict.port", "cc.predict.portFile", "cc.predict.shipId",
            "cc.predict.weaponGroup", "cc.predict.connectAttempts", "cc.predict.clientId",
            "cc.predict.transport", "cc.predict.duplex", "cc.predict.udpSilenceMs",
            "cc.predict.seatToken", "cc.predict.matchPassword", "cc.predict.fingerprint",
            // The join window's PvP team pick: per-player like the variant pick, never posture.
            "cc.predict.team",
            // The joiner's initial-spawn variant PICK (HELLO v8 tail) — per-player
            // by design like shipId/clientId, never posture.
            "cc.predict.variantId");

    private ClientPostureRegistry() {
    }

    /** The registered client census, for tests, the A2 codecs, and the census build gate. */
    public static List<Knob> knobs() {
        return KNOBS;
    }

    /**
     * The client's LIVE posture: System properties/env + the local experiment tag
     * ({@code cc.posture.experiment} sysprop else {@code CC_POSTURE_EXPERIMENT} env — the
     * server's {@code PostureRegistry.experimentTagOrNull} law; from M4-A2 the wire-block tag,
     * when present, is passed to {@link #resolve} directly and supersedes the local read).
     */
    public static Posture resolveLive() {
        String tag = System.getProperty("cc.posture.experiment");
        if (tag == null || tag.isBlank()) {
            tag = System.getenv("CC_POSTURE_EXPERIMENT");
        }
        return resolve(System.getProperties(), System.getenv(),
                (tag == null || tag.isBlank()) ? null : tag.trim());
    }

    /**
     * The server's EXPECTED posture for a baseline-clean client: every census row untouched,
     * zero unknowns — a pure function of the census, deterministic across both jars (same
     * fingerprint-locked class), contract-printed and pinned as the client-golden.
     */
    public static Posture baseline() {
        return resolve(new Properties(), Map.of(), null);
    }

    /**
     * Resolve a posture from explicit inputs (the contract-test seam; {@link #resolveLive} and
     * {@link #baseline} are the two production shapes). Tiers: sysprop &gt; env twin (only where
     * the lane itself reads one) &gt; compiled. Hash canonical: censused {@code key=value\n}
     * sorted by key, then unknown-scan {@code key=value\n} sorted by key; BEHAVIOR hash =
     * BEHAVIOR census rows + ALL unknown rows (unknown = presumptively BEHAVIOR, default-deny).
     * Trailers are never hashed (the server law), so the experiment tag is an A/B stamp only.
     */
    public static Posture resolve(Properties sysprops, Map<String, String> env,
            String experimentTagOrNull) {
        TreeMap<String, Knob> sorted = new TreeMap<>();
        for (Knob k : KNOBS) {
            sorted.put(k.key(), k);
        }
        List<String> lines = new ArrayList<>();
        StringBuilder hashed = new StringBuilder();
        StringBuilder behaviorHashed = new StringBuilder();
        boolean external = false;
        for (Knob k : sorted.values()) {
            String value;
            String sourceTier;
            String v = sysprops.getProperty(k.key());
            if (v != null && !v.isBlank()) {
                value = v.trim();
                sourceTier = "sysprop";
            } else if (k.env() != null && (v = env.get(k.env())) != null && !v.isBlank()) {
                value = v.trim();
                sourceTier = "env";
            } else {
                value = "(compiled)";
                sourceTier = "compiled";
            }
            external |= !"compiled".equals(sourceTier);
            value = sanitizeValue(value);
            lines.add("posture key=" + k.key() + " value=" + value + " source=" + sourceTier
                    + " class=" + k.cls());
            hashed.append(k.key()).append('=').append(value).append('\n');
            if (k.cls() == KnobClass.BEHAVIOR) {
                behaviorHashed.append(k.key()).append('=').append(value).append('\n');
            }
        }
        // Unknown-key scan: default-deny hashing of un-censused scan-family sysprops.
        TreeMap<String, String> unknowns = new TreeMap<>();
        for (String key : sysprops.stringPropertyNames()) {
            if (!hasScanPrefix(key) || sorted.containsKey(key) || SCAN_EXEMPT.contains(key)) {
                continue;
            }
            String v = sysprops.getProperty(key);
            if (v == null || v.isBlank()) {
                continue;
            }
            unknowns.put(key, redactIfCredentialShaped(key, sanitizeValue(v.trim())));
        }
        for (Map.Entry<String, String> e : unknowns.entrySet()) {
            lines.add("posture-unknown-key=" + e.getKey() + " value=" + e.getValue());
            hashed.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            behaviorHashed.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        boolean postured = external || !unknowns.isEmpty();
        lines.add("posture-rows=" + sorted.size());
        lines.add("posture-unknown=" + unknowns.size());
        byte[] hash = sha256(hashed.toString().getBytes(StandardCharsets.UTF_8));
        byte[] behaviorHash = sha256(behaviorHashed.toString().getBytes(StandardCharsets.UTF_8));
        lines.add("posture-hash=" + hex(hash));
        lines.add("posture-hash-behavior=" + hex(behaviorHash));
        lines.add("posture-mode=" + (postured ? "postured" : "unpostured"));
        lines.add("posture-experiment=" + (experimentTagOrNull == null ? "none"
                : sanitizeValue(experimentTagOrNull)));
        lines.add("posture-config-source=(client-sysprops)");
        return new Posture(List.copyOf(lines), hash, behaviorHash, sorted.size(), unknowns.size(),
                postured);
    }

    private static boolean hasScanPrefix(String key) {
        for (String p : SCAN_PREFIXES) {
            if (key.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** The describeDeliveredPredictKnobs redaction predicate, verbatim (credentials never log). */
    private static String redactIfCredentialShaped(String key, String sanitizedValue) {
        String lower = key.toLowerCase(Locale.ROOT);
        boolean redact = lower.contains("password") || lower.contains("token")
                || lower.contains("secret") || lower.contains("fingerprint");
        return redact ? "<set>" : sanitizedValue;
    }

    /** {@link PostureRegistry#sanitizeValue} verbatim: single-line printable law, cap 512. */
    static String sanitizeValue(String raw) {
        StringBuilder clean = new StringBuilder(Math.min(raw.length(), MAX_VALUE_CHARS));
        for (int i = 0; i < raw.length() && clean.length() < MAX_VALUE_CHARS; i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                clean.append(c);
            }
        }
        return clean.toString();
    }

    private static final int MAX_VALUE_CHARS = 512;

    /** Lowercase hex, the {@code PostureRegistry.sha256Hex} digit law. */
    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    // --- FIPS 180-4 SHA-256, pure static (no java.security/java.io/reflect: B-legal). ---
    // Correctness is contract-proven against MessageDigest every suite run — never edit one
    // without the other staying green (ClientPostureContractRows.shaMatchesMessageDigest).

    private static final int[] K = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
            0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
            0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
            0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
            0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
            0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
            0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

    /** SHA-256 of the whole message (posture canonicals are tiny; no streaming needed). */
    public static byte[] sha256(byte[] message) {
        int[] h = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c,
                0x1f83d9ab, 0x5be0cd19};
        long bitLength = (long) message.length * 8;
        int paddedLength = ((message.length + 8) / 64 + 1) * 64;
        byte[] padded = new byte[paddedLength];
        System.arraycopy(message, 0, padded, 0, message.length);
        padded[message.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            padded[paddedLength - 1 - i] = (byte) (bitLength >>> (8 * i));
        }
        int[] w = new int[64];
        for (int off = 0; off < paddedLength; off += 64) {
            for (int t = 0; t < 16; t++) {
                w[t] = ((padded[off + 4 * t] & 0xFF) << 24)
                        | ((padded[off + 4 * t + 1] & 0xFF) << 16)
                        | ((padded[off + 4 * t + 2] & 0xFF) << 8)
                        | (padded[off + 4 * t + 3] & 0xFF);
            }
            for (int t = 16; t < 64; t++) {
                int s0 = Integer.rotateRight(w[t - 15], 7) ^ Integer.rotateRight(w[t - 15], 18)
                        ^ (w[t - 15] >>> 3);
                int s1 = Integer.rotateRight(w[t - 2], 17) ^ Integer.rotateRight(w[t - 2], 19)
                        ^ (w[t - 2] >>> 10);
                w[t] = w[t - 16] + s0 + w[t - 7] + s1;
            }
            int a = h[0];
            int b = h[1];
            int c = h[2];
            int d = h[3];
            int e = h[4];
            int f = h[5];
            int g = h[6];
            int hh = h[7];
            for (int t = 0; t < 64; t++) {
                int bigS1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11)
                        ^ Integer.rotateRight(e, 25);
                int ch = (e & f) ^ (~e & g);
                int t1 = hh + bigS1 + ch + K[t] + w[t];
                int bigS0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13)
                        ^ Integer.rotateRight(a, 22);
                int maj = (a & b) ^ (a & c) ^ (b & c);
                int t2 = bigS0 + maj;
                hh = g;
                g = f;
                f = e;
                e = d + t1;
                d = c;
                c = b;
                b = a;
                a = t1 + t2;
            }
            h[0] += a;
            h[1] += b;
            h[2] += c;
            h[3] += d;
            h[4] += e;
            h[5] += f;
            h[6] += g;
            h[7] += hh;
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[4 * i] = (byte) (h[i] >>> 24);
            out[4 * i + 1] = (byte) (h[i] >>> 16);
            out[4 * i + 2] = (byte) (h[i] >>> 8);
            out[4 * i + 3] = (byte) h[i];
        }
        return out;
    }
}
