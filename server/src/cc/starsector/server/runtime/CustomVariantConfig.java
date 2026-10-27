package cc.starsector.server.runtime;

import java.util.Locale;
import java.util.Objects;

/**
 * The custom-variant knobs: the {@code custom.*} family resolved
 * ONCE at serve boot — strict parse (an operator value outside the domain THROWS: the house rule,
 * a bad knob never serves silently), the {@link PostureRegistry} triples cross-checked by
 * {@code ServerConfig.resolve*} on every call (a consumer-less knob is refused by the registry
 * idiom, so this family lands WITH its consumer).
 *
 * <p>{@code submissions=false} (the compiled default) is BYTE-IDENTICAL OFF: the lane is never
 * installed, the door answers every {@code VSUB} {@code custom_unavailable} exactly as the
 * pre-submission tree did, no engine facts are snapshotted, no boot line beyond the one-word echo.
 *
 * @param submissions      arm the submission lane (default false)
 * @param tier             the custom-variant tier (default STANDARD)
 * @param perSeat          accepted customs per author (default 4; 1..{@link #PER_SEAT_MAX})
 * @param sModCap          s-mod cap: {@link #S_MOD_CAP_ENGINE} = ask the engine with the spawn
 *                         person's stats (default), else a fixed 0..16
 * @param dModsAllowed     {@code custom.dMods=allow|deny} (default allow)
 * @param policyFile       operator override file path, blank = none
 * @param persistDir       accepted-variant evidence root (default: the {@code PERSIST_DIR_DEFAULT} constant)
 * @param exportDir        {@code custom-export <id>} inbox — the standing mint flow's intake
 *                         (default {@code variants-inbox})
 */
public record CustomVariantConfig(
        boolean submissions,
        VariantPolicy.Tier tier,
        int perSeat,
        int sModCap,
        boolean dModsAllowed,
        String policyFile,
        String persistDir,
        String exportDir) {

    /** {@code custom.sModCap=engine}: the cap is {@code Misc.getMaxPermanentMods(member, spawnPersonStats)}. */
    public static final int S_MOD_CAP_ENGINE = -1;
    public static final int S_MOD_CAP_MAX = VariantDefinitionFrameCodec.MAX_S_MODS;
    public static final int PER_SEAT_DEFAULT = 4;
    /** Per-author ceiling — the global cap ({@code SubmittedVariantLedger.GLOBAL_CAP}) bounds the rest. */
    public static final int PER_SEAT_MAX = 16;
    public static final String PERSIST_DIR_DEFAULT = "generated/customs";
    /** The inbox layout: {@code variants-inbox/<id>.variant + .meta}; repo-relative like {@link #PERSIST_DIR_DEFAULT}
     * ({@code ServerConfig.resolvePath} resolves both against the config file's directory). */
    public static final String EXPORT_DIR_DEFAULT = "variants-inbox";

    public CustomVariantConfig {
        Objects.requireNonNull(tier, "tier");
        if (perSeat < 1 || perSeat > PER_SEAT_MAX) {
            throw new IllegalArgumentException("custom.perSeat must be 1.." + PER_SEAT_MAX + ", got " + perSeat);
        }
        if (sModCap != S_MOD_CAP_ENGINE && (sModCap < 0 || sModCap > S_MOD_CAP_MAX)) {
            throw new IllegalArgumentException("custom.sModCap must be engine|0.." + S_MOD_CAP_MAX
                    + ", got " + sModCap);
        }
        policyFile = policyFile == null ? "" : policyFile.trim();
        persistDir = persistDir == null || persistDir.isBlank() ? PERSIST_DIR_DEFAULT : persistDir.trim();
        exportDir = exportDir == null || exportDir.isBlank() ? EXPORT_DIR_DEFAULT : exportDir.trim();
    }

    /** The compiled defaults (what an unconfigured serve runs). */
    public static CustomVariantConfig defaults() {
        return new CustomVariantConfig(false, VariantPolicy.Tier.STANDARD, PER_SEAT_DEFAULT,
                S_MOD_CAP_ENGINE, true, "", PERSIST_DIR_DEFAULT, EXPORT_DIR_DEFAULT);
    }

    /**
     * Resolve the family from sysprop &gt; env &gt; {@code cc-server.properties} &gt; default.
     * Every triple is the {@link PostureRegistry} row verbatim (the cross-check THROWS on a
     * fork). Domain violations THROW {@link IllegalArgumentException} — the boot refuses.
     */
    public static CustomVariantConfig resolve() {
        boolean submissions = ServerConfig.resolveBoolean(
                "cc.custom.submissions", "CC_CUSTOM_SUBMISSIONS", "custom.submissions", false);
        VariantPolicy.Tier tier = VariantPolicy.Tier.parse(ServerConfig.resolve(
                "cc.custom.tier", "CC_CUSTOM_TIER", "custom.tier", "standard"));
        int perSeat = ServerConfig.resolveInt(
                "cc.custom.perSeat", "CC_CUSTOM_PER_SEAT", "custom.perSeat", PER_SEAT_DEFAULT);
        int sModCap = parseSModCap(ServerConfig.resolve(
                "cc.custom.sModCap", "CC_CUSTOM_S_MOD_CAP", "custom.sModCap", "engine"));
        boolean dMods = parseDMods(ServerConfig.resolve(
                "cc.custom.dMods", "CC_CUSTOM_D_MODS", "custom.dMods", "allow"));
        String policyFile = ServerConfig.resolvePath(
                "cc.custom.policyFile", "CC_CUSTOM_POLICY_FILE", "custom.policyFile", "");
        String persistDir = ServerConfig.resolvePath(
                "cc.custom.persistDir", "CC_CUSTOM_PERSIST_DIR", "custom.persistDir", PERSIST_DIR_DEFAULT);
        String exportDir = ServerConfig.resolvePath(
                "cc.custom.exportDir", "CC_CUSTOM_EXPORT_DIR", "custom.exportDir", EXPORT_DIR_DEFAULT);
        return new CustomVariantConfig(submissions, tier, perSeat, sModCap, dMods, policyFile, persistDir,
                exportDir);
    }

    /** {@code engine} or a decimal 0..16; anything else THROWS (strict). */
    static int parseSModCap(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || v.equals("engine")) {
            return S_MOD_CAP_ENGINE;
        }
        try {
            int cap = Integer.parseInt(v);
            if (cap < 0 || cap > S_MOD_CAP_MAX) {
                throw new IllegalArgumentException("custom.sModCap must be engine|0.." + S_MOD_CAP_MAX
                        + ", got " + raw);
            }
            return cap;
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("custom.sModCap must be engine|0.." + S_MOD_CAP_MAX
                    + ", got " + raw, bad);
        }
    }

    /** {@code allow|deny}; anything else THROWS (strict). */
    static boolean parseDMods(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "": case "allow": return true;
            case "deny": return false;
            default:
                throw new IllegalArgumentException("custom.dMods must be allow|deny, got: " + raw);
        }
    }

    /** The one boot echo row (the {@code shieldedRow} precedent; printed on every serve). */
    /** The {@code tier} as the WELCOME v11 {@code customTier} wire byte
     * ({@code HandshakeCodec.CUSTOM_TIER_*}) — an explicit switch, never {@code ordinal()}, so
     * an enum reorder can't silently re-label the wire. */
    public int wireTier() {
        return switch (tier) {
            case STRICT -> HandshakeCodec.CUSTOM_TIER_STRICT;
            case STANDARD -> HandshakeCodec.CUSTOM_TIER_STANDARD;
            case PERMISSIVE -> HandshakeCodec.CUSTOM_TIER_PERMISSIVE;
        };
    }

    public String bootLine() {
        return "npeer-serve custom_submissions armed=" + submissions
                + " tier=" + tier.name().toLowerCase(Locale.ROOT)
                + " per_seat=" + perSeat
                + " smod_cap=" + (sModCap == S_MOD_CAP_ENGINE ? "engine" : String.valueOf(sModCap))
                + " dmods=" + (dModsAllowed ? "allow" : "deny")
                + " policy_file=" + (policyFile.isEmpty() ? "-" : "set")
                + " knob=cc.custom.submissions";
    }
}
