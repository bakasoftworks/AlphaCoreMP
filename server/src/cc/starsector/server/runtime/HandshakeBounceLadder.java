package cc.starsector.server.runtime;

/**
 * The HELLO version-bounce LADDER — classifies a payload the strict v12 decoder refused as the
 * newest legacy HELLO grammar it FULLY matches, and mints that tier's version-SHAPED
 * {@code kit_outdated} WELCOME. Extracted VERBATIM from {@code PilotIntentDatagramServer}'s
 * {@code handleHello} at the v7 bump (the ladder
 * was the endpoint's recurring growth driver — every handshake bump added a near-identical
 * ~14-line tier). The endpoint keeps the counters, the stdout REJECT row, and the lock
 * discipline; this class is pure classify + encode (no state, no I/O), so a future bump adds
 * one {@code HandshakeCodec} helper pair + one line in each method here.
 *
 * <p>Server-only by design (only the door answers HELLOs) — deliberately NOT bundled into the
 * client-mod jar's {@code $pilotClasses} set, unlike {@link HandshakeCodec} itself.
 *
 * <p>Law (held by every tier): the gate is each version's COMPLETE grammar, never a
 * version-byte sniff — the bounce reply is an amplification surface, so garbage keeps the
 * silent-drop posture and only a genuine stale kit earns the readable REJECT.
 */
public final class HandshakeBounceLadder {

    private HandshakeBounceLadder() {
    }

    /** One classified stale-kit HELLO: the legacy version that fully matched + its nonce (a
     * SIGNED long, any value) + its cookie tail. {@code cookie} is non-empty only for tier 12
     * (the v12 grammar is the first with a cookie tail; {@link HandshakeBounceLadder#classifyV12OrNull}
     * is its one source) and is never null — tiers 1..11 carry the shared zero-length array. */
    public record StaleHello(int version, long nonce, byte[] cookie) {

        /** The shared zero-length cookie of every cookie-less tier. */
        private static final byte[] NO_COOKIE = new byte[0];

        /** A null cookie reads as none. */
        public StaleHello {
            cookie = cookie == null ? NO_COOKIE : cookie;
        }

        /** The pre-v12 tiers' shape (no cookie) — every {@link HandshakeBounceLadder#classifyOrNull}
         * tier. */
        public StaleHello(int version, long nonce) {
            this(version, nonce, NO_COOKIE);
        }
    }

    /**
     * Classify a HELLO payload that is not the door's own version ({@link Handshake13#VERSION}) as
     * the newest legacy grammar it fully matches — version 15 first ({@link #classifyV15OrNull}), then
     * 14 ({@link #classifyV14OrNull}), then
     * 13 ({@link #classifyV13OrNull}), then 12 ({@link #classifyV12OrNull}, wired here by THE FLIP),
     * then 11..1 — or
     * {@code null} for garbage (silent drop). Newest-first so a payload is always attributed to the
     * highest version whose complete grammar it satisfies.
     */
    public static StaleHello classifyOrNull(byte[] payload) {
        StaleHello v15 = classifyV15OrNull(payload);
        if (v15 != null) {
            return v15;
        }
        StaleHello v14 = classifyV14OrNull(payload);
        if (v14 != null) {
            return v14;
        }
        StaleHello v13 = classifyV13OrNull(payload);
        if (v13 != null) {
            return v13;
        }
        StaleHello v12 = classifyV12OrNull(payload);
        if (v12 != null) {
            return v12;
        }
        Long v11 = HandshakeCodec.HelloPayload.legacyV11NonceOrNull(payload);
        if (v11 != null) {
            return new StaleHello(11, v11);
        }
        Long v10 = HandshakeCodec.HelloPayload.legacyV10NonceOrNull(payload);
        if (v10 != null) {
            return new StaleHello(10, v10);
        }
        Long v9 = HandshakeCodec.HelloPayload.legacyV9NonceOrNull(payload);
        if (v9 != null) {
            return new StaleHello(9, v9);
        }
        Long v8 = HandshakeCodec.HelloPayload.legacyV8NonceOrNull(payload);
        if (v8 != null) {
            return new StaleHello(8, v8);
        }
        Long v7 = HandshakeCodec.HelloPayload.legacyV7NonceOrNull(payload);
        if (v7 != null) {
            return new StaleHello(7, v7);
        }
        Long v6 = HandshakeCodec.HelloPayload.legacyV6NonceOrNull(payload);
        if (v6 != null) {
            return new StaleHello(6, v6);
        }
        Long v5 = HandshakeCodec.HelloPayload.legacyV5NonceOrNull(payload);
        if (v5 != null) {
            return new StaleHello(5, v5);
        }
        Long v4 = HandshakeCodec.HelloPayload.legacyV4NonceOrNull(payload);
        if (v4 != null) {
            return new StaleHello(4, v4);
        }
        Long v3 = HandshakeCodec.HelloPayload.legacyV3NonceOrNull(payload);
        if (v3 != null) {
            return new StaleHello(3, v3);
        }
        Long v2 = HandshakeCodec.HelloPayload.legacyV2NonceOrNull(payload);
        if (v2 != null) {
            return new StaleHello(2, v2);
        }
        Long v1 = HandshakeCodec.HelloPayload.legacyV1NonceOrNull(payload);
        if (v1 != null) {
            return new StaleHello(1, v1);
        }
        return null;
    }

    /**
     * The v12 rung: a WELL-FORMED v12 HELLO (the complete v12 grammar at the
     * literal version byte 12, {@link HandshakeCodec.HelloPayload#legacyV12OrNull}) →
     * {@code StaleHello(12, nonce, cookie)}, else {@code null}. The credential slot never
     * reaches the result. {@link #classifyOrNull} tries this rung FIRST since THE FLIP:
     * no door decodes a version-12 HELLO as a claim any more. The door's cookie
     * leg for this tier lives in {@code PilotIntentDatagramServer.bounceStaleHello} (an
     * unverified cookie earns only {@link #encodeCookieChallengeV12}).
     *
     */
    public static StaleHello classifyV12OrNull(byte[] payload) {
        HandshakeCodec.LegacyV12Hello v12 = HandshakeCodec.HelloPayload.legacyV12OrNull(payload);
        return v12 == null ? null : new StaleHello(12, v12.nonce(), v12.cookie());
    }

    /** A version-13 kit's F1, exactly as its encoder wrote it (a version-14 F1 differs only in byte 0). */
    private static final int V13 = 13, V14 = 14, V13_OPEN_HEAD_BYTES = 11, V13_FLAGS_DEFINED = 0x07;

    /**
     * The v13 rung: a WELL-FORMED version-13 F1 = the complete grammar a version-13 kit's
     * {@code encodeOpen} writes — exactly {@link Handshake13#OPEN_PAYLOAD_BYTES} B, byte 0 = the
     * literal 13, flags within PROBE | OBSERVER | NO_SECRET, the frozen {@code [10] == 0} and every
     * pad byte 0 → {@code StaleHello(13, nonce)}, else {@code null}. The exact length keeps the
     * bounce a 1:1 reflector (the 57 B REJECT answers a 123 B request). There is no cookie leg
     * (an F1 is padded past the reply), and a version-13 F2 never matches (its {@code [10]} is the
     * cookie length, never 0) — that kit sends an F2 only after a COOKIE13 no newer door mints, so
     * a stray one is garbage = a silent drop.
     *
     */
    public static StaleHello classifyV13OrNull(byte[] payload) {
        return classifyOpenOrNull(payload, V13, V13_FLAGS_DEFINED);
    }

    /**
     * The v14 rung: a WELL-FORMED version-14 F1. The version-14 bump added only the {@code u8 team}
     * closing the SEALED F2 body; the F1 bytes stayed the version-13 grammar (bytes 0..10 are frozen
     * across versions, the length is the same 123 B), so this is the v13 rung with the version byte
     * compared against the literal 14 → {@code StaleHello(14, nonce)}, else {@code null}. A version-14
     * kit at a version-15 door meets this rung instead of being admitted and then dropped as corrupt at
     * its first ability press.
     */
    public static StaleHello classifyV14OrNull(byte[] payload) {
        return classifyOpenOrNull(payload, V14, V13_FLAGS_DEFINED);
    }

    /**
     * A version-15 kit's own F1 flag set, frozen: the version-14 three plus the two MagicLib report bits
     * ({@code 0x08}, {@code 0x10}) that came in place with 15 — so a later flag bit never widens this rung.
     */
    private static final int V15 = 15, V15_FLAGS_DEFINED = 0x1F;

    /**
     * The v15 rung: a WELL-FORMED version-15 F1. The version-16 bump changed no F1 byte (it gates the 74 B
     * schema-7 pilot frame and grows the sealed F2 body by the 16-byte pilot keymap); the F1 is
     * the version-14 grammar at byte 0 = the literal 15 with that kit's
     * flag set ({@link #V15_FLAGS_DEFINED}) → {@code StaleHello(15, nonce)}, else {@code null}. A version-15 kit
     * at a version-16 door meets this rung instead of being admitted and then dropped at its first 63 B frame.
     *
     */
    public static StaleHello classifyV15OrNull(byte[] payload) {
        return classifyOpenOrNull(payload, V15, V15_FLAGS_DEFINED);
    }

    /** The shared F1 grammar of the v13..v15 rungs at one literal version byte and that version's frozen flag set
     * (the body of the v13 rung, moved verbatim when the v14 rung joined). */
    private static StaleHello classifyOpenOrNull(byte[] payload, int version, int flagsDefined) {
        if (payload == null || payload.length != Handshake13.OPEN_PAYLOAD_BYTES || (payload[0] & 0xFF) != version
                || payload[10] != 0 || (payload[9] & ~flagsDefined) != 0) {
            return null;
        }
        for (int i = V13_OPEN_HEAD_BYTES; i < payload.length; i++) {
            if (payload[i] != 0) {
                return null;
            }
        }
        long nonce = 0L;
        for (int i = 1; i <= 8; i++) {
            nonce = (nonce << 8) | (payload[i] & 0xFF);
        }
        return new StaleHello(version, nonce);
    }

    /**
     * The v12 rung's return-routability challenge (called by the door's
     * stale-kit path since THE FLIP): the compact COOKIE WELCOME at version byte 12
     * ({@link HandshakeCodec.WelcomePayload#encodeCookieV12}) echoing the stale HELLO's nonce
     * with {@code freshCookie} — the reply a stale v12 HELLO whose cookie does not verify earns.
     * {@code stale.version() != 12} = {@link IllegalArgumentException} (only a v12 kit reads a
     * COOKIE status; tiers 1..11 predate it).
     *
     */
    public static byte[] encodeCookieChallengeV12(StaleHello stale, byte[] freshCookie) {
        if (stale.version() != 12) {
            throw new IllegalArgumentException("no COOKIE challenge for classified version "
                    + stale.version());
        }
        return HandshakeCodec.WelcomePayload.encodeCookieV12(stale.nonce(), freshCookie);
    }

    /**
     * The version-SHAPED {@code kit_outdated} WELCOME for a classified stale HELLO — the exact
     * per-tier emitter signatures are the wire law (a v1/v2 WELCOME predates the tickRate tail;
     * v6 carries the full current tail incl. a REAL serverLife, which its decoder rejects at 0).
     */
    public static byte[] encodeReject(StaleHello stale, int startingRoundId, int tickRate,
            long serverLife) {
        return switch (stale.version()) {
            case 15 -> HandshakeCodec.WelcomePayload.encodeRejectedKitOutdatedV15(
                    stale.nonce(), startingRoundId, tickRate, serverLife);
            case 14 -> HandshakeCodec.WelcomePayload.encodeRejectedKitOutdatedV14(
                    stale.nonce(), startingRoundId, tickRate, serverLife);
            case 13 -> HandshakeCodec.WelcomePayload.encodeRejectedKitOutdatedV13(
                    stale.nonce(), startingRoundId, tickRate, serverLife);
            case 12 -> HandshakeCodec.WelcomePayload.encodeRejectedKitOutdatedV12(
                    stale.nonce(), startingRoundId, tickRate, serverLife);
            case 11 ->HandshakeCodec.WelcomePayload.encodeRejectedKitOutdatedV11(
                    stale.nonce(), startingRoundId, tickRate, serverLife);
            case 10 -> HandshakeCodec.WelcomePayload.encodeRejectedKitOutdatedV10(
                    stale.nonce(), startingRoundId, tickRate, serverLife);
            case 9 -> HandshakeCodec.WelcomePayload.encodeRejectedKitOutdatedV9(
                    stale.nonce(), startingRoundId, tickRate, serverLife);
            case 8 -> HandshakeCodec.WelcomePayload.encodeRejectedKitOutdatedV8(
                    stale.nonce(), startingRoundId, tickRate, serverLife);
            case 7 -> HandshakeCodec.WelcomePayload.encodeRejectedKitOutdatedV7(
                    stale.nonce(), startingRoundId, tickRate, serverLife);
            case 6 -> HandshakeCodec.WelcomePayload.encodeRejectedKitOutdatedV6(
                    stale.nonce(), startingRoundId, tickRate, serverLife);
            case 5 -> HandshakeCodec.WelcomePayload.encodeRejectedKitOutdatedV5(
                    stale.nonce(), startingRoundId, tickRate);
            case 4 -> HandshakeCodec.WelcomePayload.encodeRejectedKitOutdatedV4(
                    stale.nonce(), startingRoundId, tickRate);
            case 3 -> HandshakeCodec.WelcomePayload.encodeRejectedKitOutdatedV3(
                    stale.nonce(), startingRoundId, tickRate);
            case 2 -> HandshakeCodec.WelcomePayload.encodeRejectedKitOutdatedV2(
                    stale.nonce(), startingRoundId);
            case 1 -> HandshakeCodec.WelcomePayload.encodeRejectedKitOutdatedV1(
                    stale.nonce(), startingRoundId);
            default -> throw new IllegalArgumentException(
                    "no bounce tier for classified version " + stale.version());
        };
    }
}
