package cc.starsector.server.runtime;

import java.io.IOException;

/**
 * The HELLO/WELCOME handshake payload codec — the version tiers, both payload records, and the
 * WELCOME status bytes. Extracted VERBATIM from {@code PilotIntentDatagramServer} (the
 * endpoint crossed its ~2.6k nb growth bound at the hs=5 land, and the
 * handshake payloads were its one self-contained seam). Wire-identical to the nested-record
 * era — byte layouts, version tiers, and the fail-closed decode laws are pinned by
 * {@code DatagramFrameCodecContract} + {@code CodecContractRows} goldens.
 *
 * <p>Bundled WHOLE into the client-mod jar ({@code client-mod/build.ps1} {@code $pilotClasses}):
 * both ends author/decode HELLO/WELCOME through this ONE class, while the server endpoint stays
 * server-only. This file must therefore remain game-free and classloader-B-legal — byte packing
 * goes through {@link DatagramFrameCodec}'s helpers only (allowlisted {@link IOException} is the
 * single {@code java.io} touch), never {@code Data*Stream}.
 */
public final class HandshakeCodec {

    private HandshakeCodec() {
    }

    /** HELLO/WELCOME handshake payload version (the envelope version is separate).
     * v2: HELLO += tail {@code u64 desiredShipId}, WELCOME += tail
     * {@code u64 boundShipId}; a well-formed v1 HELLO gets a readable v1-SHAPED
     * {@code kit_outdated} REJECT instead of the silent drop.
     * v3: WELCOME += tail {@code u16 tickRate} (the server's per-match sim
     * tick rate in Hz — the client seeds {@code ServerTickRate} from it, retiring the kit-carried
     * {@code cc.predict.serverTickRate} line); HELLO grammar unchanged beyond the version byte +
     * control-character rejection at decode.
     * v4: WELCOME += tail {@code u8 serveSchemaMax} (the highest
     * AWSS schema this server serves — the client's admission-time skew truth, CL-1); HELLO
     * grammar unchanged beyond the version byte.
     * v5: WELCOME += tail {@code u8 matchFlags} (bit0 =
     * officerSkillsAll, bit1 = clientDebug (same-u8 bit — no version bump by the
     * tail law: bumps are for new tail FIELDS), bit2 = voice.enabled (the voice feature —
     * defined at the v9 bump beside the VCFG CTRL frame), bit3 = seatAdmin (the
     * ONLY per-SEAT bit: set on the one admit encode whose HELLO presented the admin credential;
     * bits 0–2 are match-global) — server-owned match config, wire-delivered;
     * the client seeds {@code MatchConfigFromWire} at admit, retiring the kit-carried
     * {@code cc.predict.officerSkillsAll} twin; undefined bits REJECT at decode, fail-closed);
     * HELLO grammar unchanged beyond the version byte.
     * v6 (L-R1): WELCOME += tail {@code u64
     * serverLife} (the server's per-BOOT life nonce, minted once per door, never 0 — decode
     * rejects 0 like tickRate/serveSchemaMax, fail-closed; the client's fresh-server-life
     * detector: a re-admit WELCOME whose serverLife differs from the bound one triggers the
     * full net-world epoch rebaseline, while an equal one keeps today's blackout-glide hold
     * path untouched); HELLO grammar unchanged beyond the version byte.
     * v7 (M4 posture kit-compat gate): NO new
     * field — both grammars are byte-identical to v6 beyond the version byte. The bump exists so
     * a pre-posture kit bounces loudly at HELLO ({@code kit_outdated}, v6 tier) instead of dying
     * post-admit on the unknown PSTB CTRL magic (unknown down magic THROWS client-side by law).
     * Well-formed v6..v1 HELLOs get version-SHAPED {@code kit_outdated} REJECTs (seven-tier: v7
     * admit / v6+v5+v4+v3+v2+v1 bounce / garbage silent); strict version equality retained both
     * sides (fail-closed decode).
     * v8 (the joiner-GUI initial-variant picker): HELLO += tail
     * {@code UTF variantId} (≤64, "" = server default — the joiner's initial-spawn pick; the
     * door seeds the ONE {@code /ship} override store on a FRESH claim only) + {@code u8
     * helloFlags} (bit0 = PROBE — the pre-launch list fetch, server path lands L2; any other
     * bit REJECTS at decode, fail-closed). {@code desiredShipId} is untouched (the SEAT lane —
     * a variant pick is orthogonal to the seat pick). WELCOME grammar unchanged beyond the
     * version byte. Well-formed v7..v1 HELLOs get version-SHAPED {@code kit_outdated} REJECTs
     * (eight-tier: v8 admit / v7..v1 bounce / garbage silent).
     * v9 (W12 voice L2): NO new field — both grammars byte-identical to v8
     * beyond the version byte. The bump exists because L2 spools a {@code VCFG} voice-config
     * CTRL frame at admit (unknown down magic THROWS client-side by law — the v7 precedent),
     * and WELCOME {@code matchFlags} defines bit 2 = {@code voice.enabled}. Well-formed v8..v1
     * HELLOs get version-SHAPED {@code kit_outdated} REJECTs (nine-tier: v9 admit / v8..v1
     * bounce / garbage silent).
     * v10 (observer admission): NO new field — both grammars
     * byte-identical to v9 beyond the version byte. The bump exists because HELLO
     * {@code helloFlags} defines bit1 = OBSERVER (a v9 door REJECTS that bit at decode, so a
     * v10 observer client against a v9 door would silently time out instead of bouncing) and
     * WELCOME {@code matchFlags} defines bit4 = seatObserver (per-SEAT, the bit3 precedent).
     * Well-formed v9..v1 HELLOs get version-SHAPED {@code kit_outdated} REJECTs (ten-tier: v10
     * admit / v9..v1 bounce / garbage silent).
     * v11 (W15 custom variants): WELCOME += tail {@code u8
     * customTier} ({@link #CUSTOM_TIER_STRICT}/{@link #CUSTOM_TIER_STANDARD}/
     * {@link #CUSTOM_TIER_PERMISSIVE}) + {@code u8 customPerSeat} (1..16) + {@code i8
     * customSModCap} (-1 = engine, else 0..16) + {@code u8 customFlags} (bit0 =
     * {@code custom.submissions}; other bits REJECT at decode) — the server-OWNED custom-variant
     * policy the SPA editor mirrors so a player builds against the LIVE tier instead of a
     * hardcoded STANDARD and eats the reject at submit. HELLO grammar unchanged beyond the
     * version byte. Well-formed v10..v1 HELLOs get version-SHAPED {@code kit_outdated} REJECTs
     * (eleven-tier: v11 admit / v10..v1 bounce / garbage silent).
     * v12 (the door's stateless return-routability cookie):
     * HELLO += tail {@code u8 cookieLen + cookie bytes} (0 = none; ≤
     * {@link #HELLO_COOKIE_MAX_BYTES}, else REJECT at decode — opaque to the client, which only
     * echoes what the door minted; never text, never logged). WELCOME gains status
     * {@link #WELCOME_COOKIE} = 2 with its OWN compact grammar {@code {u8 version, u8 status,
     * u64 nonceEcho, u8 cookieLen, cookie bytes}} (64 B framed for the door's 17 B cookie — the
     * codec does NOT bound it by the request: the 1:1-reflector property is enforced at the
     * door's SEND site, {@code reply.length <= FRAMING_BYTES + payload.length} else silence +
     * {@code cookie_undersized}); the ADMITTED/REJECTED grammar is byte-identical to v11 beyond the
     * version byte. A HELLO without a valid cookie is answered ONLY with a COOKIE reply (no
     * fingerprint check, no credential compare, no budget spend); the client re-sends the SAME
     * HELLO (same nonce) with the cookie. Well-formed v11..v1 HELLOs get version-SHAPED
     * {@code kit_outdated} REJECTs (twelve-tier: v12 admit / v11..v1 bounce / garbage silent).
     * v13 (THE FLIP): the sealed handshake. HELLO13 / WELCOME13 /
     * COOKIE13 and their grammar live in {@link Handshake13} (this constant ==
     * {@code Handshake13.VERSION}); the version-12 HELLO, WELCOME-ADMITTED and readable
     * variant-check ENCODERS are deleted from the product (a kit jar holds no cleartext-credential
     * encoder), what stays here at a literal 12 is the stale-kit rung: {@link
     * HelloPayload#legacyV12OrNull}, {@link WelcomePayload#encodeCookieV12}, {@link
     * WelcomePayload#encodeRejectedKitOutdatedV12}. This class still authors the one REJECTED
     * WELCOME grammar ({@link WelcomePayload#encodeRejected}, at this version byte) and the
     * {@code hs=} term of the content fingerprint.
     * v14: the sealed F2 body closes with a {@code u8 team} (the
     * joiner's side pick; {@link Handshake13#TEAM_MAX}); a version-13 kit's well-formed F1 earns the
     * version-13-SHAPED {@code kit_outdated} ({@link WelcomePayload#encodeRejectedKitOutdatedV13}).
     *
     * Fourteen-tier: v14 admit / v13..v1 bounce / garbage silent. */
    public static final int HANDSHAKE_VERSION = 14;
    /** HELLO v12 cookie tail cap (the door's {@code DoorCookie.BYTES} is 17; the cap leaves room
     * for a future shape without another bump). A longer tail REJECTS at decode, fail-closed. */
    public static final int HELLO_COOKIE_MAX_BYTES = 32;
    /** WELCOME v11 {@code customTier} byte values — the {@code VariantPolicy.Tier} order
     * mirrored as wire literals (the client's {@code EditorTier.Tier} maps them back; neither
     * side ships the other's enum). Any other value REJECTS at decode. */
    public static final int CUSTOM_TIER_STRICT = 0;
    public static final int CUSTOM_TIER_STANDARD = 1;
    public static final int CUSTOM_TIER_PERMISSIVE = 2;
    /** WELCOME v11 {@code customPerSeat} domain = {@code CustomVariantConfig} 1..PER_SEAT_MAX. */
    public static final int CUSTOM_PER_SEAT_MAX = 16;
    /** WELCOME v11 {@code customSModCap}: -1 = the engine cap ({@code custom.sModCap=engine}),
     * else 0..{@code VariantDefinitionFrameCodec.MAX_S_MODS}. */
    public static final int CUSTOM_S_MOD_CAP_ENGINE = -1;
    public static final int CUSTOM_S_MOD_CAP_MAX = 16;
    /** WELCOME v11 {@code customFlags} bit0: this serve accepts custom-variant submissions
     * ({@code custom.submissions}); the door still answers every VSUB {@code custom_unavailable}
     * when OFF — the bit is presentation-only (the SPA greys its Submit honestly). */
    public static final int CUSTOM_FLAG_SUBMISSIONS = 0x01;
    /** Every WELCOME v11 {@code customFlags} bit with a meaning — the decode's fail-closed mask. */
    public static final int CUSTOM_FLAGS_DEFINED = CUSTOM_FLAG_SUBMISSIONS;
    /** HELLO v8 {@code helloFlags} bit0: the pre-launch door PROBE (list fetch,
     * no claim). The server-side path lands at L2; at L1 the door treats the bit as
     * decodable-but-unsupported. */
    public static final int HELLO_FLAG_PROBE = 0x01;
    /** HELLO v10 {@code helloFlags} bit1: OBSERVER admission — the
     * joiner asks for a session with NO seat and NO hull: it receives the full DOWN fan-out
     * (world snapshots, SEAT/CTRL frames, chat) and its UP intents are dropped at ingest. The
     * client renders server truth with vanilla fx beside the play window — two windows
     * disagreeing = desync made visible. Password-mode door only; the
     * credential is the match password (no ledger claim, no seat token). Seat-name/chat still
     * ride (an observer can talk); seat-scoped verbs bounce. */
    public static final int HELLO_FLAG_OBSERVER = 0x02;
    /** Every HELLO v10 flag bit with a meaning — any other bit set fails decode closed. */
    public static final int HELLO_FLAGS_DEFINED = HELLO_FLAG_PROBE | HELLO_FLAG_OBSERVER;
    /** The v8/v9 defined-flag set (bit0 only) — the bounce ladder's exact-grammar gates for
     * those tiers keep THEIR law, not the current one (a v9 HELLO carrying bit1 was never
     * well-formed v9, so it stays a silent drop, never a readable bounce). */
    static final int HELLO_FLAGS_DEFINED_V8_V9 = HELLO_FLAG_PROBE;
    /** WELCOME v9 {@code matchFlags} bit3: the per-SEAT admin bit — set on the one
     * admit encode whose HELLO presented the admin credential; bits 0–2 are match-global. The
     * client mirrors it as {@code MatchConfigFromWire.FLAG_SEAT_ADMIN}. */
    public static final int WELCOME_FLAG_SEAT_ADMIN = 0x08;
    /** WELCOME v10 {@code matchFlags} bit4: the per-SEAT observer bit — set on
     * the one admit encode that answered an OBSERVER HELLO ({@code boundShipId} 0 rides with it;
     * a non-observer client keeps its fail-closed "ship 0" reject). The client mirrors it as
     * {@code MatchConfigFromWire.FLAG_SEAT_OBSERVER}. Bits 5–7 stay undefined (REJECT). */
    public static final int WELCOME_FLAG_SEAT_OBSERVER = 0x10;
    /** Every WELCOME v10 {@code matchFlags} bit with a meaning (bits 0–2 match-global, bit3 +
     * bit4 per-seat) — the decode's fail-closed mask, both ends. */
    public static final int WELCOME_FLAGS_DEFINED = 0x1F;
    /** WELCOME status byte: admitted. */
    public static final int WELCOME_ADMITTED = 0;
    /** WELCOME status byte: rejected (reason string carries the minted machine reason). */
    public static final int WELCOME_REJECTED = 1;
    /** WELCOME status byte (v12): a return-routability COOKIE challenge — the compact
     * grammar, no session, no reason; the client re-sends its HELLO with the cookie attached.
     * Every consumer branches on this status BEFORE any "admitted" reading (sessionId is 0). */
    public static final int WELCOME_COOKIE = 2;

    /**
     * A stale v12 kit's HELLO as the v12 bounce rung sees it ({@link HelloPayload#legacyV12OrNull}):
     * the nonce (the bounce's echo,
     * a SIGNED long — any value) and the cookie tail (0..{@link #HELLO_COOKIE_MAX_BYTES} bytes,
     * zero-length = none; never null) — and nothing else: the HELLO's credential and ids never
     * reach this record. {@code cookie} is a defensive copy both in and out.
     *
     */
    public record LegacyV12Hello(long nonce, byte[] cookie) {

        public LegacyV12Hello {
            cookie = cookie == null ? new byte[0] : cookie.clone();
        }

        /** A fresh copy per call (mutating it never changes this record). */
        @Override
        public byte[] cookie() {
            return cookie.clone();
        }
    }

    /**
     * HELLO payload —{@code {u8 version, u64 nonce, UTF clientId, UTF seatToken, UTF fingerprint}}.
     * The envelope channel byte is the discriminator (no payload magic); the fingerprint FIELD is
     * carried from day one so the wire never churns when the R8c door arms.
     * Malformed = null (pre-admission spoofable input, silent-drop posture). Public: the game-free
     * {@code client/PilotIntentDatagramClient} sibling authors HELLOs through this same codec.
     *
     * <p><b>Sandbox-clean internals (B3):</b> the client-mod datagram sibling authors HELLOs
     * IN-GAME, where the script classloader denies all of {@code java.io} but a small allowlist
     * (no {@code Data*Stream}/{@code ByteArrayOutputStream}) — so this codec packs bytes through
     * {@link DatagramFrameCodec}'s modified-UTF-8 helpers, wire-identical to the pre-B3
     * {@code writeUTF} bytes (pinned by {@code DatagramFrameCodecContract} axis H).
     */
    public record HelloPayload(long nonce, String clientId, String seatToken, String fingerprint,
            long desiredShipId, String variantId, int helloFlags, byte[] cookie) {

        /** Shared empty tail — the pre-cookie HELLO shape every legacy caller still authors. */
        private static final byte[] NO_COOKIE = new byte[0];

        /** v12: a null cookie reads as none (the record never carries null). */
        public HelloPayload {
            cookie = cookie == null ? NO_COOKIE : cookie;
        }

        // THE FLIP stage Y: the version-12 HELLO ENCODERS and the
        // at-the-constant decode are DELETED — the product (and the kit jar that bundles this
        // class) authors no cleartext-credential HELLO. The literal-12 fixture a test needs is
        // server/test V12Wire.hello; the version-13 HELLO is Handshake13's.

        /**
         * The v12 stale-kit rung's HELLO gate (the door's ladder calls it
         * since stage X): the COMPLETE v12 HELLO grammar at the LITERAL version byte 12 — every
         * field, cap, charset, flag and cookie-length law, nothing after — so a
         * payload earns the readable bounce only when it IS a well-formed v12 HELLO (the bounce is
         * a reply surface; the gate is the full grammar, never a version-byte sniff).
         * The credential slot of a stale kit's HELLO is parsed for that gate and then DROPPED
         * here: the result carries the nonce (the bounce's echo) and the cookie (the door's
         * return-routability check) ONLY — nothing a caller could compare, store or log.
         * {@code null} = not a well-formed v12 HELLO.
         *
         */
        public static LegacyV12Hello legacyV12OrNull(byte[] payload) {
            HelloPayload hello = decodeAtVersionOrNull(payload, 12);
            return hello == null ? null : new LegacyV12Hello(hello.nonce(), hello.cookie());
        }

        /** The v12 HELLO grammar at {@code versionByte}. ONE caller since THE FLIP's stage Y:
         * {@link #legacyV12OrNull} at the literal 12 (the at-the-constant reader is deleted).
         *
         * */
        private static HelloPayload decodeAtVersionOrNull(byte[] payload, int versionByte) {
            try {
                DatagramFrameCodec.PayloadReader in = new DatagramFrameCodec.PayloadReader(payload);
                if (in.readU8() != versionByte) {
                    return null;
                }
                long nonce = in.readU64();
                String clientId = in.readUtf();
                String seatToken = in.readUtf();
                String fingerprint = in.readUtf();
                long desiredShipId = in.readU64();
                String variantId = in.readUtf();
                int helloFlags = in.readU8();
                // v12 tail: an opaque cookie of bounded length; over the cap is a
                // codec/spoof symptom (reject, never truncate). Validity is the door's business
                // (DoorCookie.verify) — the codec only bounds the bytes.
                int cookieLen = in.readU8();
                if (cookieLen > HELLO_COOKIE_MAX_BYTES) {
                    return null;
                }
                byte[] cookie = in.readBytes(cookieLen);
                // v8 tail law: the variant pick obeys the same ≤64 printable bound as
                // the ids beside it (it reaches a RAW stdout admission row + a bounded CHTD
                // echo); an undefined flag bit is a codec/spoof symptom — reject, never guess.
                if (clientId.length() > 64 || seatToken.length() > 64
                        || fingerprint.length() > 128 || desiredShipId < 0L
                        || variantId.length() > 64
                        || (helloFlags & ~HELLO_FLAGS_DEFINED) != 0
                        || in.remaining() != 0
                        || !cleanHandshakeText(clientId) || !cleanHandshakeText(seatToken)
                        || !cleanHandshakeText(fingerprint) || !cleanHandshakeText(variantId)) {
                    return null;
                }
                return new HelloPayload(nonce, clientId, seatToken, fingerprint, desiredShipId,
                        variantId, helloFlags, cookie);
            } catch (IOException malformed) {
                return null;
            }
        }

        /** True iff a cookie tail rides (the door still has to VERIFY it). */
        public boolean hasCookie() {
            return cookie.length > 0;
        }

        /** Record equality over the cookie BYTES (a record compares arrays by reference). */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof HelloPayload other)) {
                return false;
            }
            return nonce == other.nonce && desiredShipId == other.desiredShipId
                    && helloFlags == other.helloFlags && clientId.equals(other.clientId)
                    && seatToken.equals(other.seatToken) && fingerprint.equals(other.fingerprint)
                    && variantId.equals(other.variantId)
                    && java.util.Arrays.equals(cookie, other.cookie);
        }

        @Override
        public int hashCode() {
            int h = java.util.Objects.hash(nonce, clientId, seatToken, fingerprint,
                    desiredShipId, variantId, helloFlags);
            return 31 * h + java.util.Arrays.hashCode(cookie);
        }

        /**
         * Never prints the cookie bytes (only their count) — a cookie is a door secret's MAC —
         * nor the seat token (only its length, {@code absent} when null): a seat token is a
         * credential the door compares.
         *
         */
        @Override
        public String toString() {
            return "HelloPayload[nonce=" + nonce + ", clientId=" + clientId + ", seatToken="
                    + (seatToken == null ? "absent" : seatToken.length() + " chars")
                    + ", fingerprint=" + fingerprint + ", desiredShipId="
                    + desiredShipId + ", variantId=" + variantId + ", helloFlags=" + helloFlags
                    + ", cookie=" + cookie.length + "B]";
        }

        /** True iff bit0 (the pre-launch door PROBE) is set. */
        public boolean probe() {
            return (helloFlags & HELLO_FLAG_PROBE) != 0;
        }

        /** True iff bit1 (OBSERVER admission) is set. A HELLO carrying BOTH bits is
         * a probe first (the door's frozen branch order: probe answers before any admission
         * shape is considered). */
        public boolean observer() {
            return (helloFlags & HELLO_FLAG_OBSERVER) != 0;
        }

        /**
         * The HELLO content law (the seat-name/chat validator family, applied AT
         * DECODE): no C0 control characters, no DEL — these strings reach RAW stdout admission
         * lines, and decode is the ONE bound covering every downstream site. Fail-closed null in
         * decode context (pre-admission spoofable input), never a throw. Deliberately admits the
         * password charset {@code [A-Za-z0-9-]} and every legal fingerprint composite —
         * printable-only is a strict superset of both. The predicate is the shared
         * {@link WireTextLaw#isPrintableWireChar} (C0/DEL + C1/NEL/LS/PS/bidi), never a local test —
         * the family stays skew-free by construction.
         * + NO lone surrogate
         * ({@link WireTextLaw#hasLoneSurrogate}) — the ONE deliberate HELLO-only skew (the
         * WireTextLaw residual note says why): a HELLO whose clientId / seatToken / fingerprint /
         * variantId carries one fails decode (silent drop, {@code malformed_hello=}; a v1..v11
         * shape is garbage, never a {@code kit_outdated} bounce), so {@code BanSet.cidKey} is
         * exact on every admitted cid. Also binds the WELCOME reject reason (client decode).
         */
        private static boolean cleanHandshakeText(String s) {
            return WireTextLaw.firstUnprintableIndex(s) < 0 && !WireTextLaw.hasLoneSurrogate(s);
        }

        /**
         * Full-shape v1 HELLO validation: version byte 1, nonce, the three
         * capped UTFs, nothing after. Returns the v1 nonce, or {@code null} for anything that
         * is not a WELL-FORMED v1 HELLO — garbage keeps the silent-drop posture; only a genuine
         * stale-kit client earns the readable {@code kit_outdated} bounce (the reply is an
         * amplification surface, so the gate is the complete v1 grammar, not the version byte).
         */
        public static Long legacyV1NonceOrNull(byte[] payload) {
            try {
                DatagramFrameCodec.PayloadReader in = new DatagramFrameCodec.PayloadReader(payload);
                if (in.readU8() != 1) {
                    return null;
                }
                long nonce = in.readU64();
                String clientId = in.readUtf();
                String seatToken = in.readUtf();
                String fingerprint = in.readUtf();
                if (clientId.length() > 64 || seatToken.length() > 64
                        || fingerprint.length() > 128 || in.remaining() != 0
                        || !cleanHandshakeText(clientId) || !cleanHandshakeText(seatToken)
                        || !cleanHandshakeText(fingerprint)) {
                    return null;
                }
                return nonce;
            } catch (IOException malformed) {
                return null;
            }
        }

        /**
         * Full-shape v3 HELLO validation ((83) SV-6, the v1/v2 recipe re-run at the v4 bump):
         * version byte 3, nonce, the three capped clean UTFs, the {@code u64 desiredShipId}
         * tail, nothing after (the v3 HELLO grammar equals v2's beyond the version byte).
         * Returns the v3 nonce, or {@code null} for anything that is not a WELL-FORMED v3
         * HELLO — the bounce reply is an amplification surface, so the gate is the complete
         * grammar, and only a genuine stale-v3-kit client earns the readable
         * {@code kit_outdated} REJECT.
         */
        public static Long legacyV3NonceOrNull(byte[] payload) {
            return legacyTailedNonceOrNull(payload, 3);
        }

        /**
         * Full-shape v2 HELLO validation: the same tailed grammar as v3 at
         * version byte 2 — see {@link #legacyV3NonceOrNull} for the bounce-gate rationale.
         */
        public static Long legacyV2NonceOrNull(byte[] payload) {
            return legacyTailedNonceOrNull(payload, 2);
        }

        /**
         * Full-shape v4 HELLO validation (W4-SS, the v1/v2/v3 recipe re-run at the v5 bump):
         * the same tailed grammar at version byte 4 — the v2..v5 HELLO grammars are identical
         * beyond the version byte; see {@link #legacyV3NonceOrNull} for the bounce-gate
         * rationale.
         */
        public static Long legacyV4NonceOrNull(byte[] payload) {
            return legacyTailedNonceOrNull(payload, 4);
        }

        /**
         * Full-shape v5 HELLO validation (L-R1, the v1..v4 recipe re-run at the v6 bump):
         * the same tailed grammar at version byte 5 — the v2..v7 HELLO grammars are identical
         * beyond the version byte; see {@link #legacyV3NonceOrNull} for the bounce-gate
         * rationale.
         */
        public static Long legacyV5NonceOrNull(byte[] payload) {
            return legacyTailedNonceOrNull(payload, 5);
        }

        /**
         * Full-shape v6 HELLO validation (M4 posture kit-compat gate, the v1..v5 recipe re-run
         * at the v7 bump): the same tailed grammar at version byte 6 — the v2..v7 HELLO grammars
         * are identical beyond the version byte; see {@link #legacyV3NonceOrNull} for the
         * bounce-gate rationale.
         */
        public static Long legacyV6NonceOrNull(byte[] payload) {
            return legacyTailedNonceOrNull(payload, 6);
        }

        /**
         * Full-shape v7 HELLO validation (the v1..v6 recipe re-run at the v8 bump):
         * the same tailed grammar at version byte 7 — the v2..v7 HELLO grammars are identical
         * beyond the version byte; v8 is the first tier to ADD a HELLO tail (variantId +
         * helloFlags), so a v7 payload is exactly the pre-picker kit's bytes; see
         * {@link #legacyV3NonceOrNull} for the bounce-gate rationale.
         */
        public static Long legacyV7NonceOrNull(byte[] payload) {
            return legacyTailedNonceOrNull(payload, 7);
        }

        /**
         * Full-shape v8 HELLO validation (W12 voice L2, the v1..v7 recipe re-run at the v9 bump)
         * — but v8 is NOT part of the shared v2..v7 tailed grammar: it ADDED the {@code UTF
         * variantId} + {@code u8 helloFlags} tail. So this gate mirrors the v8 decoder's exact
         * layout (version byte 8, nonce, three capped clean UTFs, {@code u64 desiredShipId},
         * {@code UTF variantId} ≤64 clean, {@code u8 helloFlags} = defined bits only, nothing
         * after). Returns the v8 nonce, or {@code null} for anything that is not a WELL-FORMED
         * v8 HELLO; see {@link #legacyV3NonceOrNull} for the bounce-gate rationale.
         */
        public static Long legacyV8NonceOrNull(byte[] payload) {
            return legacyFlaggedNonceOrNull(payload, 8, HELLO_FLAGS_DEFINED_V8_V9);
        }

        /**
         * Full-shape v9 HELLO validation (the v1..v8 recipe re-run at the v10
         * bump): the v8 layout at version byte 9 — v9 added no HELLO field. Returns the v9
         * nonce, or {@code null} for anything that is not a WELL-FORMED v9 HELLO.
         */
        public static Long legacyV9NonceOrNull(byte[] payload) {
            return legacyFlaggedNonceOrNull(payload, 9, HELLO_FLAGS_DEFINED_V8_V9);
        }

        /**
         * Full-shape v10 HELLO validation (the recipe re-run at the v11 bump): the
         * v8 layout at version byte 10 with the v10 flag set ({@link #HELLO_FLAGS_DEFINED} —
         * bit1 OBSERVER was defined at v10, unlike v8/v9). Returns the v10 nonce, or
         * {@code null} for anything that is not a WELL-FORMED v10 HELLO.
         */
        public static Long legacyV10NonceOrNull(byte[] payload) {
            return legacyFlaggedNonceOrNull(payload, 10, HELLO_FLAGS_DEFINED);
        }

        /**
         * Full-shape v11 HELLO validation (the recipe re-run at the v12 bump): the v8
         * layout at version byte 11 with the v10 flag set (v11 added no HELLO field; v12 added
         * the cookie tail, so a v11 HELLO ends at helloFlags). Returns the v11 nonce, or
         * {@code null} for anything that is not a WELL-FORMED v11 HELLO.
         */
        public static Long legacyV11NonceOrNull(byte[] payload) {
            return legacyFlaggedNonceOrNull(payload, 11, HELLO_FLAGS_DEFINED);
        }

        /** The shared v8/v9/v10/v11 flagged-HELLO grammar (identical beyond the version byte): three
         * capped clean UTFs, {@code u64 desiredShipId}, {@code UTF variantId} ≤64 clean,
         * {@code u8 helloFlags} = that tier's defined bits only ({@link #HELLO_FLAGS_DEFINED_V8_V9}
         * for v8/v9 — bit1 was undefined there, so a payload carrying it is garbage, never a
         * bounce; {@link #HELLO_FLAGS_DEFINED} for v10), nothing after. */
        private static Long legacyFlaggedNonceOrNull(byte[] payload, int versionByte,
                int definedFlags) {
            try {
                DatagramFrameCodec.PayloadReader in = new DatagramFrameCodec.PayloadReader(payload);
                if (in.readU8() != versionByte) {
                    return null;
                }
                long nonce = in.readU64();
                String clientId = in.readUtf();
                String seatToken = in.readUtf();
                String fingerprint = in.readUtf();
                long desiredShipId = in.readU64();
                String variantId = in.readUtf();
                int helloFlags = in.readU8();
                if (clientId.length() > 64 || seatToken.length() > 64
                        || fingerprint.length() > 128 || desiredShipId < 0L
                        || variantId.length() > 64
                        || (helloFlags & ~definedFlags) != 0
                        || in.remaining() != 0
                        || !cleanHandshakeText(clientId) || !cleanHandshakeText(seatToken)
                        || !cleanHandshakeText(fingerprint) || !cleanHandshakeText(variantId)) {
                    return null;
                }
                return nonce;
            } catch (IOException malformed) {
                return null;
            }
        }

        /** The shared v2..v7 tailed-HELLO grammar (identical beyond the version byte). */
        private static Long legacyTailedNonceOrNull(byte[] payload, int versionByte) {
            try {
                DatagramFrameCodec.PayloadReader in = new DatagramFrameCodec.PayloadReader(payload);
                if (in.readU8() != versionByte) {
                    return null;
                }
                long nonce = in.readU64();
                String clientId = in.readUtf();
                String seatToken = in.readUtf();
                String fingerprint = in.readUtf();
                long desiredShipId = in.readU64();
                if (clientId.length() > 64 || seatToken.length() > 64
                        || fingerprint.length() > 128 || desiredShipId < 0L
                        || in.remaining() != 0
                        || !cleanHandshakeText(clientId) || !cleanHandshakeText(seatToken)
                        || !cleanHandshakeText(fingerprint)) {
                    return null;
                }
                return nonce;
            } catch (IOException malformed) {
                return null;
            }
        }
    }

    /**
     * WELCOME payload — {@code {u8 version, u8 status, u64 nonceEcho, u64 sessionId, u8 roundId,
     * UTF reason}}, pre-framed as a complete datagram (header sessionId stays 0 in HELLO/WELCOME —
     * the minted session id is DATA here, adopted by the client only when the nonce echo matches a
     * HELLO it sent). Admitted: sessionId minted, reason empty. Rejected: sessionId 0, reason =
     * the {@code SeatAdmission.SeatAdmissionRejected} machine reason. Public: the client sibling
     * decodes WELCOMEs through this same codec.
     *
     * <p><b>Sandbox-clean internals (B3):</b> the client-mod datagram sibling decodes WELCOMEs
     * IN-GAME under the script classloader's {@code java.io} allowlist (no {@code Data*Stream}) —
     * packed through {@link DatagramFrameCodec}'s helpers, wire-identical to the pre-B3
     * {@code writeUTF} bytes ({@code DatagramFrameCodecContract} axis H).
     */
    public record WelcomePayload(int status, long nonceEcho, long sessionId, int startingRoundId,
            String reason, long boundShipId, int tickRate, int serveSchemaMax, int matchFlags,
            long serverLife, int customTier, int customPerSeat, int customSModCap,
            int customFlags, byte[] cookie) {

        private static final byte[] NO_COOKIE = new byte[0];

        /** v12: the cookie is non-empty ONLY on a {@link #WELCOME_COOKIE} status; every other
         * status carries none (the record never carries null). */
        public WelcomePayload {
            cookie = cookie == null ? NO_COOKIE : cookie;
        }

        /** The pre-v12 14-field shape (no cookie) — every legacy caller's constructor. */
        public WelcomePayload(int status, long nonceEcho, long sessionId, int startingRoundId,
                String reason, long boundShipId, int tickRate, int serveSchemaMax, int matchFlags,
                long serverLife, int customTier, int customPerSeat, int customSModCap,
                int customFlags) {
            this(status, nonceEcho, sessionId, startingRoundId, reason, boundShipId, tickRate,
                    serveSchemaMax, matchFlags, serverLife, customTier, customPerSeat,
                    customSModCap, customFlags, NO_COOKIE);
        }

        /** True iff this is the v12 return-routability challenge: re-send the
         * HELLO with {@link #cookie()} attached, same nonce; nothing else about the reply is
         * meaningful (sessionId 0, no reason, no tail). */
        public boolean isCookie() {
            return status == WELCOME_COOKIE;
        }

        /**
         * The v12 stale-kit rung's COOKIE challenge: the compact
         * grammar {@code {u8 version, u8 status=2, u64 nonceEcho, u8 cookieLen, cookie}} at the
         * LITERAL version byte 12, framed as a complete datagram — 36 + 11 + len bytes (64 B for
         * the door's 17 B cookie) — the reply a v12 kit can read, so a stale v12 HELLO whose
         * cookie does not verify still earns the return-routability round before the bounce.
         * Nothing here bounds it by the HELLO it answers (a degenerate 63 B HELLO is
         * smaller): the 1:1-reflector property lives at the door's SEND site
         * ({@code PilotIntentDatagramServer}: {@code reply.length <= FRAMING_BYTES +
         * payload.length}, else silence + {@code cookie_undersized}). Cookie length
         * 1..{@link #HELLO_COOKIE_MAX_BYTES} (THROW outside — the door mints a fixed size). The
         * at-the-constant sibling is deleted (THE FLIP stage Y): the version-13
         * challenge is {@code Handshake13}'s COOKIE13.
         *
         */
        public static byte[] encodeCookieV12(long nonceEcho, byte[] cookie) {
            return encodeCookieAt(12, nonceEcho, cookie);
        }

        /** The compact COOKIE grammar at {@code versionByte} (ONE caller: {@link #encodeCookieV12}
         * = the literal 12).
         * */
        private static byte[] encodeCookieAt(int versionByte, long nonceEcho, byte[] cookie) {
            if (cookie == null || cookie.length < 1 || cookie.length > HELLO_COOKIE_MAX_BYTES) {
                throw new IllegalArgumentException("COOKIE WELCOME cookie length must be 1.."
                        + HELLO_COOKIE_MAX_BYTES);
            }
            byte[] payload = new byte[1 + 1 + 8 + 1 + cookie.length];
            payload[0] = (byte) versionByte;
            payload[1] = (byte) WELCOME_COOKIE;
            DatagramFrameCodec.putU64(payload, 2, nonceEcho);
            payload[10] = (byte) cookie.length;
            System.arraycopy(cookie, 0, payload, 11, cookie.length);
            return DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.WELCOME, 0, 0L,
                    0L, 0L, 0, 0L, payload);
        }

        /** Record equality over the cookie BYTES; never prints them (see {@link HelloPayload}). */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof WelcomePayload other)) {
                return false;
            }
            return status == other.status && nonceEcho == other.nonceEcho
                    && sessionId == other.sessionId && startingRoundId == other.startingRoundId
                    && reason.equals(other.reason) && boundShipId == other.boundShipId
                    && tickRate == other.tickRate && serveSchemaMax == other.serveSchemaMax
                    && matchFlags == other.matchFlags && serverLife == other.serverLife
                    && customTier == other.customTier && customPerSeat == other.customPerSeat
                    && customSModCap == other.customSModCap && customFlags == other.customFlags
                    && java.util.Arrays.equals(cookie, other.cookie);
        }

        @Override
        public int hashCode() {
            int h = java.util.Objects.hash(status, nonceEcho, sessionId, startingRoundId, reason,
                    boundShipId, tickRate, serveSchemaMax, matchFlags, serverLife, customTier,
                    customPerSeat, customSModCap, customFlags);
            return 31 * h + java.util.Arrays.hashCode(cookie);
        }

        @Override
        public String toString() {
            return "WelcomePayload[status=" + status + ", nonceEcho=" + nonceEcho + ", sessionId="
                    + sessionId + ", startingRoundId=" + startingRoundId + ", reason=" + reason
                    + ", boundShipId=" + boundShipId + ", tickRate=" + tickRate
                    + ", serveSchemaMax=" + serveSchemaMax + ", matchFlags=" + matchFlags
                    + ", serverLife=" + serverLife + ", customTier=" + customTier
                    + ", customPerSeat=" + customPerSeat + ", customSModCap=" + customSModCap
                    + ", customFlags=" + customFlags + ", cookie=" + cookie.length + "B]";
        }

        /** v11 tail: the server-owned custom-variant policy — {@code customTier} =
         * {@link #CUSTOM_TIER_STRICT}..{@link #CUSTOM_TIER_PERMISSIVE}, {@code customPerSeat} =
         * 1..{@link #CUSTOM_PER_SEAT_MAX}, {@code customSModCap} =
         * {@link #CUSTOM_S_MOD_CAP_ENGINE} or 0..{@link #CUSTOM_S_MOD_CAP_MAX}, {@code customFlags}
         * ⊆ {@link #CUSTOM_FLAGS_DEFINED}. The encoders THROW on a domain breach (the door's
         * setter is the one caller and validates first); the decoder returns {@code null}.
         *
         * <p>The door's ONE readable REJECT: the plaintext REJECTED grammar at
         * the current version byte ({@link #HANDSHAKE_VERSION}; THE FLIP's stage Y, folded the
         * dual-stack {@code encodeRejected13} into this method and deleted the version-12
         * {@code encodeAdmitted} (an admission is the sealed WELCOME13, {@code Handshake13}).
         * Unauthenticated, as before; the kit reads it only through
         * {@link #decodeRejectedOrNull}{@code (payload, Handshake13.VERSION)}. */
        public static byte[] encodeRejected(long nonceEcho, String reason, int startingRoundId,
                int tickRate, int matchFlags, long serverLife, int customTier,
                int customPerSeat, int customSModCap, int customFlags) {
            return frame(HANDSHAKE_VERSION, WELCOME_REJECTED, nonceEcho, 0L, startingRoundId,
                    reason == null ? "" : reason, 0L, tickRate, matchFlags, serverLife,
                    customTier, customPerSeat, customSModCap, customFlags);
        }

        /**
         * The v11-SHAPED {@code kit_outdated} bounce (the recipe re-run at the v12
         * bump): version byte <b>11</b>, the exact v11 layout = the current ADMITTED/REJECTED
         * grammar (v12 changed it by the version byte only; the cookie rides a separate status).
         * Real tickRate/schemaMax/serverLife, matchFlags 0; the custom tail carries the
         * in-domain defaults (STANDARD / 4 / engine / 0 — a v11 client throws on REJECTED before
         * it stores the custom config, so the values are never read). Deliberate legacy-shape
         * emitter; every non-bounce WELCOME is v12.
         */
        public static byte[] encodeRejectedKitOutdatedV11(long nonceEcho, int startingRoundId,
                int tickRate, long serverLife) {
            return frame(11, WELCOME_REJECTED, nonceEcho, 0L, startingRoundId,
                    DoorRejectReasons.KIT_OUTDATED, 0L, tickRate, 0, serverLife,
                    CUSTOM_TIER_STANDARD, 4, CUSTOM_S_MOD_CAP_ENGINE, 0);
        }

        /**
         * The v12-SHAPED {@code kit_outdated} bounce (the door's v12 rung
         * since stage X): version byte <b>12</b>, the v11/v12 ADMITTED/REJECTED layout
         * with the v11 emitter's values (real tickRate/schemaMax/serverLife, matchFlags 0, the
         * STANDARD / 4 / engine / 0 custom tail — a v12 client throws on REJECTED before it
         * stores the custom config). {@link #encodeRejected} with those values, but for the
         * version byte.
         *
         */
        public static byte[] encodeRejectedKitOutdatedV12(long nonceEcho, int startingRoundId,
                int tickRate, long serverLife) {
            return frame(12, WELCOME_REJECTED, nonceEcho, 0L, startingRoundId,
                    DoorRejectReasons.KIT_OUTDATED, 0L, tickRate, 0, serverLife,
                    CUSTOM_TIER_STANDARD, 4, CUSTOM_S_MOD_CAP_ENGINE, 0);
        }

        /**
         * The v13-SHAPED {@code kit_outdated} bounce: version byte <b>13</b>, the same REJECTED
         * layout and values as {@link #encodeRejectedKitOutdatedV12} — a version-13 kit reads its
         * one plaintext REJECT at its own version byte, so this frame is what turns its F1 at a
         * newer door into "kit outdated" instead of a silent timeout. 57 B payload: never larger
         * than the 123 B F1 it answers.
         *
         */
        public static byte[] encodeRejectedKitOutdatedV13(long nonceEcho, int startingRoundId,
                int tickRate, long serverLife) {
            return frame(13, WELCOME_REJECTED, nonceEcho, 0L, startingRoundId,
                    DoorRejectReasons.KIT_OUTDATED, 0L, tickRate, 0, serverLife,
                    CUSTOM_TIER_STANDARD, 4, CUSTOM_S_MOD_CAP_ENGINE, 0);
        }

        /** The v11 custom-tail domain law, shared by the encoders (THROW) and the decoder
         * (null): one predicate so the two ends can never disagree on what is well-formed. */
        public static boolean customTailValid(int customTier, int customPerSeat,
                int customSModCap, int customFlags) {
            return customTier >= CUSTOM_TIER_STRICT && customTier <= CUSTOM_TIER_PERMISSIVE
                    && customPerSeat >= 1 && customPerSeat <= CUSTOM_PER_SEAT_MAX
                    && customSModCap >= CUSTOM_S_MOD_CAP_ENGINE
                    && customSModCap <= CUSTOM_S_MOD_CAP_MAX
                    && (customFlags & ~CUSTOM_FLAGS_DEFINED) == 0;
        }

        /**
         * The v1-SHAPED {@code kit_outdated} bounce: version byte <b>1</b>, the
         * exact v1 layout (no {@code boundShipId} tail) — the only frame a v1 client's strict
         * decoder can read, so a stale kit dies on "admission rejected: kit_outdated" instead
         * of a silent HELLO timeout. Deliberate legacy-shape emitter; every other WELCOME is v2.
         */
        public static byte[] encodeRejectedKitOutdatedV1(long nonceEcho, int startingRoundId) {
            String reason = DoorRejectReasons.KIT_OUTDATED;
            byte[] payload = new byte[1 + 1 + 8 + 8 + 1
                    + 2 + DatagramFrameCodec.modifiedUtfLength(reason)];
            payload[0] = (byte) 1;
            payload[1] = (byte) WELCOME_REJECTED;
            DatagramFrameCodec.putU64(payload, 2, nonceEcho);
            DatagramFrameCodec.putU64(payload, 10, 0L);
            payload[18] = (byte) startingRoundId;
            DatagramFrameCodec.putModifiedUtf(payload, 19, reason);
            return DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.WELCOME, 0, 0L,
                    0L, 0L, 0, 0L, payload);
        }

        /**
         * The v2-SHAPED {@code kit_outdated} bounce (the v1 recipe re-run at the
         * v3 bump): version byte <b>2</b>, the exact v2 layout ({@code u64 boundShipId} tail, no
         * {@code u16 tickRate}) — the only frame a v2 client's strict decoder can read, so a
         * stale v2 kit dies on "admission rejected: kit_outdated" instead of a silent HELLO
         * timeout. Deliberate legacy-shape emitter; every non-bounce WELCOME is v3.
         */
        public static byte[] encodeRejectedKitOutdatedV2(long nonceEcho, int startingRoundId) {
            String reason = DoorRejectReasons.KIT_OUTDATED;
            byte[] payload = new byte[1 + 1 + 8 + 8 + 1
                    + 2 + DatagramFrameCodec.modifiedUtfLength(reason)
                    + 8];
            payload[0] = (byte) 2;
            payload[1] = (byte) WELCOME_REJECTED;
            DatagramFrameCodec.putU64(payload, 2, nonceEcho);
            DatagramFrameCodec.putU64(payload, 10, 0L);
            payload[18] = (byte) startingRoundId;
            int off = DatagramFrameCodec.putModifiedUtf(payload, 19, reason);
            DatagramFrameCodec.putU64(payload, off, 0L);
            return DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.WELCOME, 0, 0L,
                    0L, 0L, 0, 0L, payload);
        }

        /**
         * The v3-SHAPED {@code kit_outdated} bounce ((83) SV-6, the v1/v2 recipe re-run at the
         * v4 bump): version byte <b>3</b>, the exact v3 layout ({@code u64 boundShipId} +
         * {@code u16 tickRate} tail, no {@code u8 serveSchemaMax}) — the only frame a v3
         * client's strict decoder can read, so a stale v3 kit dies on "admission rejected:
         * kit_outdated" instead of a silent HELLO timeout. The tickRate is real (a v3 decoder
         * rejects 0). Deliberate legacy-shape emitter; every non-bounce WELCOME is v4.
         */
        public static byte[] encodeRejectedKitOutdatedV3(long nonceEcho, int startingRoundId,
                int tickRate) {
            String reason = DoorRejectReasons.KIT_OUTDATED;
            byte[] payload = new byte[1 + 1 + 8 + 8 + 1
                    + 2 + DatagramFrameCodec.modifiedUtfLength(reason)
                    + 8
                    + 2];
            payload[0] = (byte) 3;
            payload[1] = (byte) WELCOME_REJECTED;
            DatagramFrameCodec.putU64(payload, 2, nonceEcho);
            DatagramFrameCodec.putU64(payload, 10, 0L);
            payload[18] = (byte) startingRoundId;
            int off = DatagramFrameCodec.putModifiedUtf(payload, 19, reason);
            DatagramFrameCodec.putU64(payload, off, 0L);
            DatagramFrameCodec.putU16(payload, off + 8, tickRate);
            return DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.WELCOME, 0, 0L,
                    0L, 0L, 0, 0L, payload);
        }

        /**
         * The v4-SHAPED {@code kit_outdated} bounce (W4-SS, the v1/v2/v3 recipe re-run at the
         * v5 bump): version byte <b>4</b>, the exact v4 layout ({@code u64 boundShipId} +
         * {@code u16 tickRate} + {@code u8 serveSchemaMax} tail, no {@code u8 matchFlags}) —
         * the only frame a v4 client's strict decoder can read, so a stale v4 kit dies on
         * "admission rejected: kit_outdated" instead of a silent HELLO timeout. The tickRate
         * and schema max are real (a v4 decoder rejects 0 for either). Deliberate legacy-shape
         * emitter; every non-bounce WELCOME is v5.
         */
        public static byte[] encodeRejectedKitOutdatedV4(long nonceEcho, int startingRoundId,
                int tickRate) {
            String reason = DoorRejectReasons.KIT_OUTDATED;
            byte[] payload = new byte[1 + 1 + 8 + 8 + 1
                    + 2 + DatagramFrameCodec.modifiedUtfLength(reason)
                    + 8
                    + 2
                    + 1];
            payload[0] = (byte) 4;
            payload[1] = (byte) WELCOME_REJECTED;
            DatagramFrameCodec.putU64(payload, 2, nonceEcho);
            DatagramFrameCodec.putU64(payload, 10, 0L);
            payload[18] = (byte) startingRoundId;
            int off = DatagramFrameCodec.putModifiedUtf(payload, 19, reason);
            DatagramFrameCodec.putU64(payload, off, 0L);
            DatagramFrameCodec.putU16(payload, off + 8, tickRate);
            payload[off + 10] =
                    (byte) cc.starsector.server.runtime.AuthoritativeWorldSnapshotCodec
                            .SCHEMA_VERSION_MAX;
            return DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.WELCOME, 0, 0L,
                    0L, 0L, 0, 0L, payload);
        }

        /**
         * The v5-SHAPED {@code kit_outdated} bounce (L-R1, the v1..v4 recipe re-run at the
         * v6 bump): version byte <b>5</b>, the exact v5 layout ({@code u64 boundShipId} +
         * {@code u16 tickRate} + {@code u8 serveSchemaMax} + {@code u8 matchFlags} tail, no
         * {@code u64 serverLife}) — the only frame a v5 client's strict decoder can read, so
         * a stale v5 kit dies on "admission rejected: kit_outdated" instead of a silent HELLO
         * timeout. The tickRate and schema max are real (a v5 decoder rejects 0 for either);
         * matchFlags 0 passes the v5 undefined-bits law (bits 0/1 defined, 0 legal).
         * Deliberate legacy-shape emitter; every non-bounce WELCOME is v7.
         */
        public static byte[] encodeRejectedKitOutdatedV5(long nonceEcho, int startingRoundId,
                int tickRate) {
            String reason = DoorRejectReasons.KIT_OUTDATED;
            byte[] payload = new byte[1 + 1 + 8 + 8 + 1
                    + 2 + DatagramFrameCodec.modifiedUtfLength(reason)
                    + 8
                    + 2
                    + 1
                    + 1];
            payload[0] = (byte) 5;
            payload[1] = (byte) WELCOME_REJECTED;
            DatagramFrameCodec.putU64(payload, 2, nonceEcho);
            DatagramFrameCodec.putU64(payload, 10, 0L);
            payload[18] = (byte) startingRoundId;
            int off = DatagramFrameCodec.putModifiedUtf(payload, 19, reason);
            DatagramFrameCodec.putU64(payload, off, 0L);
            DatagramFrameCodec.putU16(payload, off + 8, tickRate);
            payload[off + 10] =
                    (byte) cc.starsector.server.runtime.AuthoritativeWorldSnapshotCodec
                            .SCHEMA_VERSION_MAX;
            payload[off + 11] = (byte) 0;
            return DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.WELCOME, 0, 0L,
                    0L, 0L, 0, 0L, payload);
        }

        /**
         * The v6-SHAPED {@code kit_outdated} bounce (M4 posture kit-compat gate, the v1..v5
         * recipe re-run at the v7 bump): version byte <b>6</b>, the exact v6 layout — which is
         * the FULL current tail ({@code u64 boundShipId} + {@code u16 tickRate} +
         * {@code u8 serveSchemaMax} + {@code u8 matchFlags} + {@code u64 serverLife}; v7 added
         * no field, only the version gate) — the only frame a v6 client's strict decoder can
         * read, so a stale pre-posture v6 kit dies on "admission rejected: kit_outdated"
         * instead of a silent HELLO timeout (or, worse, admitting into the unknown-PSTB fatal).
         * The tickRate, schema max, and serverLife are real (a v6 decoder rejects 0 for each);
         * matchFlags 0 passes the v6 undefined-bits law. Deliberate legacy-shape emitter;
         * every non-bounce WELCOME is v7.
         */
        public static byte[] encodeRejectedKitOutdatedV6(long nonceEcho, int startingRoundId,
                int tickRate, long serverLife) {
            return encodeRejectedKitOutdatedFullTail(6, nonceEcho, startingRoundId, tickRate,
                    serverLife);
        }

        /**
         * The v7-SHAPED {@code kit_outdated} bounce (the v1..v6 recipe re-run at
         * the v8 bump): version byte <b>7</b>, the exact v7 layout — the FULL current tail
         * (v8 changed the WELCOME by its version byte only) — the only frame a v7 client's
         * strict decoder can read, so a stale pre-picker v7 kit dies on "admission rejected:
         * kit_outdated" instead of a silent HELLO timeout. The tickRate, schema max, and
         * serverLife are real (a v7 decoder rejects 0 for each); matchFlags 0 passes the
         * undefined-bits law. Deliberate legacy-shape emitter; every non-bounce WELCOME is v8.
         */
        public static byte[] encodeRejectedKitOutdatedV7(long nonceEcho, int startingRoundId,
                int tickRate, long serverLife) {
            return encodeRejectedKitOutdatedFullTail(7, nonceEcho, startingRoundId, tickRate,
                    serverLife);
        }

        /**
         * The v8-SHAPED {@code kit_outdated} bounce (W12 voice L2, the v1..v7 recipe re-run at
         * the v9 bump): version byte <b>8</b>, the exact v8 layout — the FULL current tail
         * (v8 AND v9 changed the WELCOME by their version byte only) — the only frame a v8
         * client's strict decoder can read, so a stale pre-voice v8 kit dies on "admission
         * rejected: kit_outdated" instead of a silent HELLO timeout (or, worse, admitting into
         * the unknown-VCFG fatal). The tickRate, schema max, and serverLife are real (a v8
         * decoder rejects 0 for each); matchFlags 0 passes the undefined-bits law. Deliberate
         * legacy-shape emitter; every non-bounce WELCOME is v10.
         */
        public static byte[] encodeRejectedKitOutdatedV8(long nonceEcho, int startingRoundId,
                int tickRate, long serverLife) {
            return encodeRejectedKitOutdatedFullTail(8, nonceEcho, startingRoundId, tickRate,
                    serverLife);
        }

        /**
         * The v9-SHAPED {@code kit_outdated} bounce (the recipe re-run at the
         * v10 bump): version byte <b>9</b>, the exact v9 layout = the full current tail (v10
         * changed the WELCOME by its version byte only) — the only frame a v9 client's strict
         * decoder can read, so a stale pre-observer v9 kit dies on "admission rejected:
         * kit_outdated" instead of a silent HELLO timeout. Real tickRate/schemaMax/serverLife;
         * matchFlags 0. Deliberate legacy-shape emitter; every non-bounce WELCOME is v10.
         */
        public static byte[] encodeRejectedKitOutdatedV9(long nonceEcho, int startingRoundId,
                int tickRate, long serverLife) {
            return encodeRejectedKitOutdatedFullTail(9, nonceEcho, startingRoundId, tickRate,
                    serverLife);
        }

        /**
         * The v10-SHAPED {@code kit_outdated} bounce (the recipe re-run at the v11
         * bump): version byte <b>10</b>, the exact v10 layout = the pre-custom-tail full tail
         * (serverLife last; v11 ADDED the 4-byte custom tail) — the only frame a v10 client's
         * strict decoder can read, so a stale pre-tier-ping v10 kit dies on "admission
         * rejected: kit_outdated" instead of a silent HELLO timeout. Real
         * tickRate/schemaMax/serverLife; matchFlags 0. Deliberate legacy-shape emitter; every
         * non-bounce WELCOME is v11.
         */
        public static byte[] encodeRejectedKitOutdatedV10(long nonceEcho, int startingRoundId,
                int tickRate, long serverLife) {
            return encodeRejectedKitOutdatedFullTail(10, nonceEcho, startingRoundId, tickRate,
                    serverLife);
        }

        /** The v6/v7/v8/v9/v10 shared full-tail {@code kit_outdated} layout (identical beyond
         * the leading version byte — v7, v8, v9, and v10 changed the WELCOME by the version byte
         * only; v11 appended the custom tail AFTER serverLife, so this pre-v11 shape ends at
         * serverLife). */
        private static byte[] encodeRejectedKitOutdatedFullTail(int versionByte, long nonceEcho,
                int startingRoundId, int tickRate, long serverLife) {
            String reason = DoorRejectReasons.KIT_OUTDATED;
            byte[] payload = new byte[1 + 1 + 8 + 8 + 1
                    + 2 + DatagramFrameCodec.modifiedUtfLength(reason)
                    + 8
                    + 2
                    + 1
                    + 1
                    + 8];
            payload[0] = (byte) versionByte;
            payload[1] = (byte) WELCOME_REJECTED;
            DatagramFrameCodec.putU64(payload, 2, nonceEcho);
            DatagramFrameCodec.putU64(payload, 10, 0L);
            payload[18] = (byte) startingRoundId;
            int off = DatagramFrameCodec.putModifiedUtf(payload, 19, reason);
            DatagramFrameCodec.putU64(payload, off, 0L);
            DatagramFrameCodec.putU16(payload, off + 8, tickRate);
            payload[off + 10] =
                    (byte) cc.starsector.server.runtime.AuthoritativeWorldSnapshotCodec
                            .SCHEMA_VERSION_MAX;
            payload[off + 11] = (byte) 0;
            DatagramFrameCodec.putU64(payload, off + 12, serverLife);
            return DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.WELCOME, 0, 0L,
                    0L, 0L, 0, 0L, payload);
        }

        /** The v11/v12 ADMITTED/REJECTED layout at {@code versionByte}. Since THE FLIP's stage Y
         * every product caller writes status REJECTED: 13 = {@link #encodeRejected}, 12 / 11 =
         * the stale-kit {@code kit_outdated} bounces. */
        private static byte[] frame(int versionByte, int status, long nonceEcho, long sessionId,
                int startingRoundId, String reason, long boundShipId, int tickRate,
                int matchFlags, long serverLife, int customTier, int customPerSeat,
                int customSModCap, int customFlags) {
            if (!customTailValid(customTier, customPerSeat, customSModCap, customFlags)) {
                throw new IllegalArgumentException("WELCOME v11 custom tail out of domain: tier="
                        + customTier + " perSeat=" + customPerSeat + " sModCap=" + customSModCap
                        + " flags=0x" + Integer.toHexString(customFlags));
            }
            byte[] payload = new byte[1 + 1 + 8 + 8 + 1
                    + 2 + DatagramFrameCodec.modifiedUtfLength(reason)
                    + 8
                    + 2
                    + 1
                    + 1
                    + 8
                    + 4];
            payload[0] = (byte) versionByte;
            payload[1] = (byte) status;
            DatagramFrameCodec.putU64(payload, 2, nonceEcho);
            DatagramFrameCodec.putU64(payload, 10, sessionId);
            payload[18] = (byte) startingRoundId;
            int off = DatagramFrameCodec.putModifiedUtf(payload, 19, reason);
            DatagramFrameCodec.putU64(payload, off, boundShipId);
            DatagramFrameCodec.putU16(payload, off + 8, tickRate);
            // (83) SV-6 v4 tail: the highest AWSS schema this server serves — one byte per
            // admission, the client's CL-1 skew truth.
            payload[off + 10] =
                    (byte) cc.starsector.server.runtime.AuthoritativeWorldSnapshotCodec
                            .SCHEMA_VERSION_MAX;
            // W4-SS v5 tail: the server-owned match-config bits (bit0 = officerSkillsAll,
            // bit1 = clientDebug, bit3 = seatAdmin, per-seat) — the client seeds
            // MatchConfigFromWire at BOTH admit lanes.
            payload[off + 11] = (byte) matchFlags;
            // L-R1 v6 tail: the server's per-BOOT life nonce — the client's
            // fresh-life detector at re-admit; never 0 (decode rejects it, fail-closed).
            DatagramFrameCodec.putU64(payload, off + 12, serverLife);
            // W15 v11 tail: the server-owned custom-variant policy — the client
            // seeds CustomConfigFromWire at BOTH admit lanes; the SPA editor locks its tier
            // dropdown to it while connected. sModCap rides as a signed byte (-1 = engine).
            payload[off + 20] = (byte) customTier;
            payload[off + 21] = (byte) customPerSeat;
            payload[off + 22] = (byte) customSModCap;
            payload[off + 23] = (byte) customFlags;
            return DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.WELCOME, 0, 0L,
                    0L, 0L, 0, 0L, payload);
        }

        /**
         * The reject-only WELCOME reader — the ONE plaintext WELCOME
         * reader left since THE FLIP's stage Y deleted the at-the-constant {@code decodeOrNull}:
         * the plain v11/v12 grammar at {@code versionByte}, returned ONLY when its
         * status is {@link #WELCOME_REJECTED}, else {@code null}. At version 13 an ADMITTED is
         * sealed and a COOKIE has its own grammar ({@code Handshake13}), so this plaintext grammar
         * is trusted for a REJECT and nothing else — a plaintext ADMITTED or COOKIE at that
         * version byte reads as nothing here. Every decode law of the grammar binds.
         *
         */
        public static WelcomePayload decodeRejectedOrNull(byte[] payload, int versionByte) {
            WelcomePayload welcome = decodeAtVersionOrNull(payload, versionByte);
            return welcome != null && welcome.status() == WELCOME_REJECTED ? welcome : null;
        }

        /** The v12 WELCOME grammar (status law, COOKIE grammar, ADMITTED/REJECTED domain laws) at
         * {@code versionByte}. ONE caller: {@link #decodeRejectedOrNull}, at its caller's version,
         * keeping REJECTED only (the COOKIE branch below is parsed and then filtered out there —
         * kept whole so the grammar stays fail-closed, never half a reader).
         * */
        private static WelcomePayload decodeAtVersionOrNull(byte[] payload, int versionByte) {
            try {
                DatagramFrameCodec.PayloadReader in = new DatagramFrameCodec.PayloadReader(payload);
                if (in.readU8() != versionByte) {
                    return null;
                }
                int status = in.readU8();
                // v12 status law: 0/1 = the v11 grammar below; 2 = the compact
                // COOKIE grammar; anything else is a codec/spoof symptom (pre-v12 every value
                // decoded and read as "admitted" — fail-closed now, both ends).
                if (status > WELCOME_COOKIE) {
                    return null;
                }
                if (status == WELCOME_COOKIE) {
                    long cookieNonce = in.readU64();
                    int cookieLen = in.readU8();
                    if (cookieLen < 1 || cookieLen > HELLO_COOKIE_MAX_BYTES) {
                        return null;
                    }
                    byte[] cookie = in.readBytes(cookieLen);
                    if (in.remaining() != 0) {
                        return null;
                    }
                    return new WelcomePayload(WELCOME_COOKIE, cookieNonce, 0L, 0, "", 0L, 0, 0, 0,
                            0L, CUSTOM_TIER_STANDARD, 1, CUSTOM_S_MOD_CAP_ENGINE, 0, cookie);
                }
                long nonceEcho = in.readU64();
                long sessionId = in.readU64();
                int startingRoundId = in.readU8();
                String reason = in.readUtf();
                long boundShipId = in.readU64();
                int tickRate = in.readU16();
                int serveSchemaMax = in.readU8();
                int matchFlags = in.readU8();
                long serverLife = in.readU64();
                // W15 v11 tail: bounded integers, sModCap signed (-1 = engine); any value outside
                // the customTailValid domain is a codec/spoof symptom (reject, don't clamp).
                int customTier = in.readU8();
                int customPerSeat = in.readU8();
                int customSModCap = (byte) in.readU8();
                int customFlags = in.readU8();
                // W4-SS fail-closed law: bits 5–7 are undefined at v10 (bit0 = officerSkillsAll,
                // bit1 = clientDebug, bit2 = voice.enabled, bit3 =
                // seatAdmin, per-seat, bit4 = seatObserver, per-seat;
                // WELCOME_FLAGS_DEFINED) — a nonzero unknown bit is a codec/spoof symptom, never
                // a config to guess at (reject, don't mask). L-R1 v6: a zero serverLife is likewise a codec symptom (the
                // server mints non-zero by law). The reject
                // {@code reason} is door wire text that rides RAW into the client sidelog through
                // the AdmissionRejected throwable message — the HELLO content law (no C0/DEL,
                // {@link HelloPayload#cleanHandshakeText}) binds at THIS decode too, fail-closed.
                // + the HELLO ids' 64-char cap — the
                // charset law alone let a hostile door put ~1.97 KB into one kit log line; our door's
                // longest DoorRejectReasons token is 28 chars and it never sends free text here.
                if (boundShipId < 0L || tickRate == 0 || serveSchemaMax == 0
                        || (matchFlags & ~WELCOME_FLAGS_DEFINED) != 0
                        || serverLife == 0L
                        || !customTailValid(customTier, customPerSeat, customSModCap, customFlags)
                        || reason.length() > 64
                        || !HelloPayload.cleanHandshakeText(reason)
                        || in.remaining() != 0) {
                    return null;
                }
                return new WelcomePayload(status, nonceEcho, sessionId, startingRoundId, reason,
                        boundShipId, tickRate, serveSchemaMax, matchFlags, serverLife,
                        customTier, customPerSeat, customSModCap, customFlags);
            } catch (IOException malformed) {
                return null;
            }
        }
    }
}
