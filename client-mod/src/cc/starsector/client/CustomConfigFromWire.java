package cc.starsector.client;

import cc.starsector.server.runtime.HandshakeCodec;

/**
 * The client-side mirror of the
 * SERVER-OWNED custom-variant policy — seeded from the v11 WELCOME's custom tail ({@code u8
 * customTier} + {@code u8 customPerSeat} + {@code i8 customSModCap} + {@code u8 customFlags})
 * at BOTH admit lanes (initial connect + B4 re-admit), the {@link MatchConfigFromWire#seedFromWire}
 * discipline. The wire is the ONE source: no sysprop, no kit line, no io (loader-B-legal by
 * construction). Pre-seed = the server's own defaults (STANDARD / 4 per seat / engine s-mod cap /
 * submissions OFF), so anything reading before admit sees the public baseline — and
 * {@link #seeded()} stays false so the editor's ping reports {@code tierSource=default} rather
 * than claiming a server said so. Consumer: the SPA editor's {@code api/ping}
 * ({@code EditorRoutes}) — it locks the tier dropdown to the live tier while connected, so a
 * player never builds green in STANDARD against a STRICT serve and eats the reject at submit.
 */
public final class CustomConfigFromWire {

    private static volatile int tier = HandshakeCodec.CUSTOM_TIER_STANDARD;
    private static volatile int perSeat = 4;
    private static volatile int sModCap = HandshakeCodec.CUSTOM_S_MOD_CAP_ENGINE;
    private static volatile int flags;
    private static volatile boolean seeded;

    private CustomConfigFromWire() {
    }

    /** Adopt the WELCOME custom tail. The shared decoder already refused any out-of-domain
     * value (fail-closed, {@code WelcomePayload.customTailValid}); the re-check here keeps
     * the twin self-defending anyway, the {@link MatchConfigFromWire#seedFromWire} discipline. */
    public static void seedFromWire(int wireTier, int wirePerSeat, int wireSModCap,
            int wireFlags) {
        if (!HandshakeCodec.WelcomePayload.customTailValid(wireTier, wirePerSeat, wireSModCap,
                wireFlags)) {
            FallbackAnnounce.announceOnce("customconfig-wire-refused", "tier=" + wireTier
                    + " perSeat=" + wirePerSeat + " sModCap=" + wireSModCap + " flags=0x"
                    + Integer.toHexString(wireFlags) + " out of domain -> keeping " + describe());
            return;
        }
        boolean changed = tier != wireTier || perSeat != wirePerSeat || sModCap != wireSModCap
                || flags != wireFlags;
        String prior = describe();
        tier = wireTier;
        perSeat = wirePerSeat;
        sModCap = wireSModCap;
        flags = wireFlags;
        seeded = true;
        if (changed) {
            PilotWatchLog.info("cc-customconfig wire_seed " + describe() + " prior=" + prior);
        }
    }

    /** Has a WELCOME seeded this holder in this process (false = the pre-seed baseline)? */
    public static boolean seeded() {
        return seeded;
    }

    /** The live tier as the {@code VariantPolicy.Tier} / {@code EditorTier.Tier} NAME
     * ({@code STRICT}/{@code STANDARD}/{@code PERMISSIVE}) — the SPA's {@code defaultTier}. */
    public static String tierName() {
        int t = tier;
        if (t == HandshakeCodec.CUSTOM_TIER_STRICT) {
            return "STRICT";
        }
        if (t == HandshakeCodec.CUSTOM_TIER_PERMISSIVE) {
            return "PERMISSIVE";
        }
        return "STANDARD";
    }

    /** The server's per-author custom cap ({@code custom.perSeat}). */
    public static int perSeat() {
        return perSeat;
    }

    /** The server's s-mod cap: -1 = the engine cap ({@code custom.sModCap=engine}), else 0..16. */
    public static int sModCap() {
        return sModCap;
    }

    /** Does the connected serve accept custom-variant submissions ({@code custom.submissions})?
     * Presentation-only — the door still answers {@code custom_unavailable} on its own. */
    public static boolean submissions() {
        return (flags & HandshakeCodec.CUSTOM_FLAG_SUBMISSIONS) != 0;
    }

    private static String describe() {
        return "tier=" + tierName() + " perSeat=" + perSeat + " sModCap=" + sModCap
                + " submissions=" + submissions();
    }
}
