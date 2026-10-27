package cc.starsector.client;

import cc.starsector.server.match.ContentFingerprintClientSurface;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.runtime.Handshake13;
import cc.starsector.server.runtime.Handshake13Flight;
import cc.starsector.server.runtime.HandshakeCodec;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;

/**
 * The app-load dial PLANNER: the ONE-PER-PROCESS half of the predict dial — the
 * endpoint / ship / group / transport / posture resolve, the fail-closed credential mint and the
 * content-fingerprint self-compute — prepared into the immutable {@link PredictDialPlan} at
 * {@code onApplicationLoad}, WITHOUT connecting.
 *
 * <p>WHY a class of its own: {@link CcClientModPlugin} was 2612 non-blank lines with no tier, and
 * this half holds no lock and spawns no thread. The dial ITSELF ({@code dialPredictAtJoin} /
 * {@code dialPredictAtPlay}, {@code DIAL_LOCK}, the dial volatiles) lives in {@link PredictDialLane}.
 * Moved VERBATIM (bodies, javadoc, every log / fallback line, every throw message).
 *
 * <p>Census law: the {@code cc.predict.*} system-property and {@code CC_PREDICT_*} env literals
 * stay INLINE and byte-identical here (a knob-census script sweeps all of
 * {@code client-mod/src}; every ARMED {@code cc.predict.*} key is hashed at runtime by the
 * {@code ClientPostureRegistry} unknown-key scan — the census hand tool was retired).
 *
 * <p>Write law: this class writes {@code CurrentBuildLane.launchVariantPick} and the four dial
 * statics of {@link PredictDialLane} ({@code predictDialHost} / {@code predictDialPort} /
 * {@code predictDialAttempts} / {@code predictDialPlan}) at the plugin's ORIGINAL points and in the
 * original order — the variant pick before the fail-closed throws, the dial statics between the
 * PREPARED log line and {@code MatchMissionBuilder.setClaimAtPlayBuild}.
 *
 * <p>Provenance: the design read (DIAL-PLAN row) + carve leg B,
 * following the leg A precedent ({@link ClientLaunchKnobs} / {@link PilotSessionBoot}).
 *
 *
 */
final class PredictDialPlanner {

    private PredictDialPlanner() {
    }

    /**
     * PREPARE the predict-and-reconcile dial at application load — resolve
     * the endpoint ({@code cc.predict.portFile} / {@code CC_PREDICT_PORT_FILE}, then explicit
     * {@code cc.predict.port} / {@code CC_PREDICT_PORT}), ship + group ({@code cc.predict.shipId} /
     * {@code cc.predict.weaponGroup}), transport, posture, the fail-closed credential mint and the
     * content-fingerprint self-compute — into {@link PredictDialLane#predictDialPlan}, WITHOUT connecting. The
     * connect itself runs at the player's Multiplayer Test click ({@link CcClientModPlugin#dialPredictAtJoin}), so
     * a window that only launched holds no seat, slot, ledger claim or DOWN fan.
     * Loader-B is a class-NAME predicate, so
     * nothing here needs the load phase — it stays here because it is one-per-process (an
     * ephemeral client id must survive re-dials for the same-cid takeover lane) and because a
     * misconfiguration should THROW loud at launch, never at the click. Fail-closed: any error
     * leaves the plan null and the mission loads with locally-controlled, un-reconciled ships.
     */
    static void preparePredictDial() {
        // The kit credential handoff is consumed FIRST — above
        // every return and throw below — so its read-once file is deleted on every path; the memo is
        // the one source of the password / cid / token reads further down (no id = the env twins).
        final CredentialHandoff handoff = CredentialHandoff.take();
        ClientLaunchKnobs.PredictEndpoint endpoint = ClientLaunchKnobs.resolvePredictEndpoint();
        if (endpoint == null) {
            // (83) CL-7a: distinguish "nothing configured" (ordinary launch, silent) from
            // "cc.predict.* present but no endpoint resolved" — the latter used to fall
            // through to LOCAL VANILLA with ZERO output (the felt silently-un-networked
            // window class).
            if (ClientLaunchKnobs.predictEndpointKnobPresent()) {
                FallbackAnnounce.announceOnce("predict-endpoint-unresolved",
                        "cc.predict.* set but no endpoint resolved -> LOCAL VANILLA launch");
                PilotWatchLog.info("onApplicationLoad: cc.predict.* configured but no predict"
                        + " endpoint resolved (portFile unreadable/partial, or port"
                        + " unset/non-positive) -> ordinary launch, NO predict session");
            }
            return; // no predict endpoint configured -> an ordinary (non-predict) launch
        }
        final int port;
        try {
            port = Integer.parseInt(endpoint.port);
        } catch (NumberFormatException e) {
            PilotWatchLog.error("onApplicationLoad: predict endpoint port not parseable: '" + endpoint.port + "'", e);
            return;
        }
        if (port <= 0) {
            return; // port 0 = unconfigured sentinel
        }
        final String host = endpoint.host;
        // The OBSERVER posture forces the AUTO seat (0) — the door binds seat 0
        // / ship 0 to it; a pinned id would only IAE in connect (an observer holds no seat).
        final boolean observer = ObserverMode.ON;
        final long shipId = observer ? 0L
                : ClientLaunchKnobs.resolvePilotLong("cc.predict.shipId", "CC_PREDICT_SHIP_ID", 1L);
        final int group = (int) ClientLaunchKnobs.resolvePilotLong("cc.predict.weaponGroup", "CC_PREDICT_WEAPON_GROUP", 0L);
        // The joiner's initial-spawn variant pick — rides the
        // HELLO v8 tail ("" = server default); the JoinGui writes it (L3) from the server's own
        // pickable list, the server canonicalizes + seeds /ship on a FRESH claim (fallback =
        // descriptor default + a CHTD notice). NOT match config — the player's pick.
        final String variantId = CurrentBuildLane.resolveVariantPick();
        CurrentBuildLane.launchVariantPick = variantId; // precedence (2): the join window's pick for THIS launch
        JoinVariantPick.set(variantId); // The mission builder fields it at t=0
        JoinVariantPick.setWireResolver(CurrentBuildLane::foldPickFromWirePreMission); //
        JoinVariantPick.setCustomVerdictPeek(CurrentBuildLane::customVerdictOnHand); //
        // the join window's Team choice for THIS launch (the bat's -Dcc.predict.team, absent on a relaunch): armed
        // once per process, sent by the first dial a door admits, 0 on every later dial (the ONE-SHOT law)
        cc.starsector.client.net.PredictDatagramDial.offerFirstHelloTeam(readTeamPick());
        final int attempts = ClientLaunchKnobs.resolvePredictConnectAttempts();
        // Transport select + udp credential mint, BEFORE the connect thread spawns —
        // invalid values THROW at application load (loud, fail-closed; the server-side mirror).
        final String transport = ClientLaunchKnobs.resolvePredictTransport();
        if (observer && !"udp".equals(transport)) {
            // The observer bit is a HELLO (datagram door) flag — no other lane has it.
            throw new IllegalStateException("cc.observer=on needs cc.predict.transport=udp (the"
                    + " observer HELLO bit rides the datagram door only); got transport="
                    + transport);
        }
        final String clientId;
        final String seatToken;
        // The credential FIELD seatToken came from — the
        // version-13 envelope slot; set beside each seatToken assignment below.
        final Handshake13Flight.Lane lane;
        final String fingerprint;
        // The match password when on the password lane (null otherwise) —
        // the credential the in-game door PROBE sends when the pick is EMPTY (see
        // probeServerDefaultPick). Token lane (scripted-only) has no password ⇒ no probe.
        final String probePassword;
        if ("udp".equals(transport)) {
            // The composition rule, client half: udp REQUIRES the duplex net loop.
            String duplex = System.getProperty("cc.predict.duplex", "false");
            if (!"true".equals(duplex)) {
                throw new IllegalStateException("cc.predict.transport=udp requires"
                        + " cc.predict.duplex=true (composition rule); got"
                        + " cc.predict.duplex=" + duplex);
            }
            // Fail-closed real-credential mint (the server-side mintUdpSeatAuthority mirror):
            // the SAME cc.match.descriptor file, own seat row = the seat token; no descriptor or
            // no row for this ship id THROWS — the mod has no perimeter-trust datagram door.
            MatchDescriptor descriptor = MatchMissionBuilder.cachedDescriptor();
            if (descriptor == null) {
                // No buildable match = NO dial plan (no socket, no probe, no
                // claim), and the give-up latches armed the way the dial loop's terminal refusal arms them, so the
                // briefing and the in-combat banner name the fix. Throwing here was a Fatal at application load.
                // A knob that names no file at all reads as the laws' own null-descriptor refusal.
                MatchMissionBuilder.Failure failure = MatchMissionBuilder.loadFailure();
                String refusal = failure != null ? failure.token()
                        : cc.starsector.server.match.MatchDocumentLaws.REASON_MALFORMED + " descriptor";
                PilotWatchLog.error("onApplicationLoad: cc.predict.transport=udp needs a usable cc.match.descriptor ("
                        + MatchMissionBuilder.describeConfig() + ") -> NO predict dial (fail-closed: no socket, no"
                        + " probe, no claim); the banner names the fix: " + refusal, null);
                PredictDialLane.armMatchFileRefusal(refusal);
                return;
            }
            // The match-password lane (the kit handoff when cc.kit.handoff is set,
            // else env CC_PREDICT_MATCH_PASSWORD) BEATS the token lane when
            // non-blank — password mode client-side. The credential slot carries the PASSWORD on
            // the wire (the HELLO seatToken field); the door validates it against its sidecar and
            // keys the seat by clientId instead (P3/P4), desiredShipId = the standing shipId pin
            // (threaded at both HELLO compose sites, leg 2). Blank ⇒ the token lane below, byte-identical.
            // The password VALUE is never logged — lane only. (Read here, ABOVE the seat-row
            // walk, so the AUTO gate below can fire before a doomed row lookup.)
            String matchPassword = handoff.password(System.getenv("CC_PREDICT_MATCH_PASSWORD"));
            // shipId 0 = AUTO ("server
            // assigns", the kit bat's pin) — legal ONLY on the password lane. Token-mode
            // credentials are inherently per-seat (the descriptor row below is keyed by ship
            // id and the handout is per-person), so AUTO there is a config error: fail fast
            // and clear at application load, never a confusing door bounce.
            if (shipId == 0L && matchPassword.isEmpty()) {
                // The observer is seat 0 by construction (the door's password lane
                // only — observer_password_mode_only) — name the posture, not the AUTO knob.
                throw new IllegalStateException((observer
                        ? "cc.observer=on (observer = seat 0, password door only) needs the"
                        : "cc.predict.shipId=0 (auto seat) needs the")
                        // Name what the handoff DID - with an id set the env
                        // twin is ignored, so "or env …" sent a refused-file joiner to the wrong knob.
                        // The clause (outcome enum + a FAILED read's SimpleName cause,
                        // never a value) is CredentialHandoff.fatalClause - with session logs off the
                        // 'kit handoff:' row it points at is never written.
                        + " match password (" + handoff.fatalClause() + ")" + (observer ? ""
                        : " — or set an explicit shipId (token-mode seat credentials are"
                                + " per-seat)"));
            }
            MatchDescriptor.Seat ownSeat = null;
            for (MatchDescriptor.Seat seat : descriptor.seats()) {
                if (seat.shipId() == shipId) {
                    ownSeat = seat;
                    break;
                }
            }
            if (ownSeat == null && shipId != 0L) {
                throw new IllegalStateException("cc.predict.transport=udp: descriptor carries no"
                        + " seat row for shipId=" + shipId + " (seats="
                        + descriptor.seats().size() + ") — fail-closed, no blank-token door");
            }
            if (!matchPassword.isEmpty()) {
                seatToken = matchPassword;
                lane = Handshake13Flight.Lane.PASSWORD;
                // Password mode keys the seat by a UNIQUE client-instance id — the
                // kit handoff's cid carries JoinGui's persisted mint (the cc.predict.clientId
                // property / CC_PREDICT_CLIENT_ID env lane serves non-kit launches); absent (env
                // drills, hand launches) ⇒ mint an EPHEMERAL per-process id, NEVER the shared
                // "predict-seat"+shipId default: two joiners under one match password on that
                // default would cid-takeover each other. Ephemeral cost (accepted, P4): a
                // mid-match relaunch rejoins only after the liveness death vacates the seat —
                // no instant same-cid takeover.
                String cidOverride = handoff.cid(System.getProperty("cc.predict.clientId"),
                        System.getenv("CC_PREDICT_CLIENT_ID"));
                // A set twin BEATS a present kit handoff: the host console's second window rides the env twin
                // while the handoff carries window 1's persisted id (the no-roll alternative would cost the window
                // its JoinGui). The handoff's own cid is told apart by VALUE — no further read of the twins, which
                // the planner-wiring pin counts.
                boolean cidFromHandoff = handoff.cidIsHandoffs(cidOverride);
                // A cid the version-13 flight refuses never reaches the
                // plan — a property / env one THROWS here, a kit-handoff one is treated as absent (the ephemeral mint).
                // An observer mints regardless (below), so its override is never vetted.
                if (!observer) {
                    cidOverride = lawfulCidOrAbsent(cidOverride, cidFromHandoff, "EPHEMERAL mint");
                }
                // An OBSERVER always mints an EPHEMERAL cid — join-cc.bat
                // passes the joiner's PERSISTED cid, and the door refuses a cid holding a LIVE
                // pilot session (observer_cid_in_use): the owner's second window must never
                // displace the owner's own seat. The ephemeral cost (no instant same-cid
                // takeover) is moot — an observer's re-HELLO is the takeover lane on ITS OWN cid.
                boolean ephemeralCid = observer || cidOverride.isEmpty();
                clientId = ephemeralCid ? ClientLaunchKnobs.mintEphemeralClientId() : cidOverride;
                // The property / env source keeps its
                // suffixes byte-stable; a kit handoff (an id present) names itself. The rig lanes
                // (override, ephemeral mint) keep the FULL cid — run-pw-ingame.ps1's identity row
                // parses it. On the KIT path the
                // cid is the joiner's PERSISTED seat key (a same-cid HELLO under the shared match
                // password takes the seat over), and this row lands in the cc-client.log a debrief
                // zips whole — so a kit handoff prints its first 8 characters only, enough to match
                // the server's ADMIT row (the host's rows print the same 8-character prefix). The console override
                // (a twin beside a present handoff) is the host's own seat key in the same sidelog: 8 characters too.
                boolean kitCid = !observer && !cidOverride.isEmpty() && handoff.idPresent();
                PilotWatchLog.info("credential source: match password (password mode); client id"
                        + " = " + (kitCid
                                ? clientId.substring(0, Math.min(8, clientId.length() / 2)) + "..."
                                : clientId) + (observer
                                ? " (EPHEMERAL mint — observer posture)"
                                : cidOverride.isEmpty()
                                        ? (handoff.idPresent()
                                                ? " (EPHEMERAL mint — no cid in the kit handoff)"
                                                : " (EPHEMERAL mint — no cc.predict.clientId)")
                                        : cidFromHandoff
                                                ? " (kit handoff)"
                                                : handoff.idPresent()
                                                        ? " (console override)"
                                                        : " (cc.predict.clientId override)"));
            } else {
                // The seat-token override lane (the kit
                // handoff when cc.kit.handoff is set, else env CC_PREDICT_SEAT_TOKEN) BEATS
                // descriptor extraction. It is now SCRIPTED-ONLY (watch lanes, drills)
                // — the JoinGui paste feeder is gone; real-joiner matches use the match password.
                // Blank ⇒ descriptor extraction, byte-identical (the keep-tokens watch
                // kit). The token VALUE is never logged — lane only.
                String tokenOverride = handoff.token(System.getenv("CC_PREDICT_SEAT_TOKEN"));
                seatToken = tokenOverride.isEmpty() ? ownSeat.seatToken() : tokenOverride;
                lane = Handshake13Flight.Lane.TOKEN;
                PilotWatchLog.info("seat token source: " + (tokenOverride.isEmpty()
                        ? "descriptor seat row"
                        : handoff.idPresent() ? "kit handoff" : "CC_PREDICT_SEAT_TOKEN override"));
                if (MatchDescriptor.isRedactedToken(seatToken)) {
                    // Courtesy diagnosis, not a boundary (the door bounces seat_token_unknown as a
                    // readable WELCOME REJECTED): a placeholder here means a redacted kit launched
                    // without the pasted personal token.
                    PilotWatchLog.error("seat token is the kit's REDACTED placeholder — the door"
                            + " will bounce seat_token_unknown; type the match password the host"
                            + " announced into the join window (or set CC_PREDICT_SEAT_TOKEN for"
                            + " a scripted token-mode lane)",
                            new IllegalStateException("redacted placeholder seat token"));
                }
                String tokenLaneCid = handoff.cid(System.getProperty("cc.predict.clientId"),
                        System.getenv("CC_PREDICT_CLIENT_ID"));
                tokenLaneCid = lawfulCidOrAbsent(tokenLaneCid, handoff.cidIsHandoffs(tokenLaneCid),
                        "predict-seat" + shipId); // (f) 84; a set twin beats the handoff here too
                clientId = tokenLaneCid.isEmpty() ? "predict-seat" + shipId : tokenLaneCid;
            }
            // The sysprop/env lane survives as an
            // OVERRIDE (tests/doctoring); blank ⇒ self-compute the real composite from the
            // mod's own content. Failure = LOG + blank, fail-open client / fail-closed door
            // (an armed door bounces blank as content_fingerprint_required; an unarmed door
            // ignores the field — every pre-D4.8 lane is byte-identical on the wire).
            String fingerprintOverride = ClientLaunchKnobs.firstNonBlank(
                    System.getProperty("cc.predict.fingerprint"),
                    System.getenv("CC_PREDICT_FINGERPRINT"), "");
            requireLawfulPrintOverride(fingerprintOverride); // An unsendable override THROWS
            fingerprint = fingerprintOverride.isEmpty()
                    ? selfComputeFingerprint(descriptor) : fingerprintOverride;
            probePassword = matchPassword.isEmpty() ? null : matchPassword;
        } else {
            clientId = null;
            seatToken = null;
            lane = Handshake13Flight.Lane.TOKEN; // unused: no datagram dial without udp
            fingerprint = null;
            probePassword = null;
        }
        // The plan is BUILT from here on — a reel-tagged
        // endpoint (the in-process replay door) gets a blank seat credential and no probe password, so the typed
        // match password never reaches it. The AUTO gate and the token arm above see the typed value on purpose.
        final PredictDialPlan.Credentials credentials =
                PredictDialPlan.credentialsFor(endpoint, seatToken, probePassword);
        if (endpoint.reelDoor) {
            PilotWatchLog.info("credential: endpoint = the in-process reel replay door -> the dial carries NO"
                    + " credential (blank seat credential, no door probe); the typed match password is not sent");
        }
        PilotWatchLog.info("onApplicationLoad: predict dial PREPARED (W13: the connect runs at the"
                + " Multiplayer Test click, never at launch; retrying up to "
                + attempts + "x every " + PredictDialLane.PREDICT_CONNECT_BACKOFF_MILLIS + "ms from the click) host=" + host
                + " port=" + port + " shipId=" + shipId + " group=" + group
                + " transport=" + transport
                + (observer ? " posture=OBSERVER (seat 0 / ship 0, no own hull)" : "")
                + " variant=" + (variantId.isEmpty() ? "(server default)" : variantId)
                + (variantId.isEmpty() && credentials.probePassword != null
                        ? " (EMPTY pick on the password lane: the door's published default"
                                + " is PROBED on the connect thread and adopted)"
                        : ""));
        PredictDialLane.predictDialHost = host;
        PredictDialLane.predictDialPort = port;
        PredictDialLane.predictDialAttempts = attempts;
        // THE FLIP - production dials version 13 (the constant, never a knob); the kit has no
        // version-12 sender left.
        PredictDialLane.predictDialPlan = new PredictDialPlan(host, port, shipId, group, attempts, transport,
                clientId, credentials.seatToken, lane, Handshake13.VERSION, fingerprint, variantId,
                credentials.probePassword, observer, endpoint.reelDoor);
        // W13 leg 2b: a served PILOT window builds its registry mission with zero
        // own hulls — the seat is claimed at Play (the observer already fields none itself).
        MatchMissionBuilder.setClaimAtPlayBuild(!observer);
    }

    /** The launch's {@code cc.predict.team} read ({@link #teamPickOf}). */
    static int readTeamPick() {
        return teamPickOf(System.getProperty("cc.predict.team"));
    }

    /**
     * {@code cc.predict.team} as the dial's team byte: absent / empty = 0 (the server picks); exactly {@code 0},
     * {@code 1} (South) or {@code 2} (North) = that team; anything else = 0 and ONE log line naming the property,
     * never its value (the bat refuses such a value first; a hand launch lands here). Pure but for the one log line.
     *
     */
    static int teamPickOf(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        if (raw.length() == 1 && raw.charAt(0) >= '0' && raw.charAt(0) <= '2') {
            return raw.charAt(0) - '0';
        }
        PilotWatchLog.info("cc.predict.team is not 0, 1 or 2 - ignored; the server picks the team");
        return 0;
    }

    /**
     * The version-13 A-string law ({@code Handshake13}: {@code length <= max},
     * every char 0x21..0x7E — the flight constructor's own check) on a launch override. {@code null} = no override
     * (null / empty) or lawful; else a text naming {@code source} and the law, NEVER the value (a cid is a seat key).
     * WHY at launch: the flight refuses such a value at every click (a terminal LOCAL refusal), and version 12 carries
     * an inner space / non-ASCII that version 13 cannot. Pure.
     *
     *
     */
    static String overrideRefusalOrNull(String value, int max, String source) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String refusal = source + " is not version-14 sendable (at most " + max
                + " chars of printable ASCII 0x21..0x7E; the value is not echoed)";
        if (value.length() > max) {
            return refusal;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                return refusal;
            }
        }
        return null;
    }

    /**
     * (f) 84 split by SOURCE: an unlawful cid from the property / env twin THROWS (a dev knob —
     * this planner's "throw loud at launch" law); one from the KIT HANDOFF ({@code fromHandoff}: its id beats both
     * twins) is treated as ABSENT — {@code ""} back + ONE log row naming the source and {@code absentMeans} (the lane's
     * no-cid default), never the value: the handoff cid is JoinGui's persisted mint, never typed, so a damaged id file
     * must not become a launch Fatal. Lawful or empty: {@code cid} back unchanged. The property / env pair is named
     * together: the planner-wiring pin ({@code client-mod/build.ps1} PDP-WIRING) allows no third raw read to tell them apart.
     *
     *
     */
    static String lawfulCidOrAbsent(String cid, boolean fromHandoff, String absentMeans) {
        String refusal = overrideRefusalOrNull(cid, Handshake13.CID_MAX, fromHandoff ? "the kit handoff's client id"
                : "cc.predict.clientId (or its env twin CC_PREDICT_CLIENT_ID)");
        if (refusal == null) {
            return cid;
        }
        if (!fromHandoff) {
            throw new IllegalStateException(refusal + " - fix or unset it");
        }
        PilotWatchLog.info("credential: " + refusal + " -> treated as absent (" + absentMeans + ")");
        return "";
    }

    /** (f) 84: an unlawful {@code cc.predict.fingerprint} / {@code CC_PREDICT_FINGERPRINT} override THROWS at launch,
     *  naming the knob pair, never the value (the flight refuses it at every click). */
    static void requireLawfulPrintOverride(String print) {
        String refusal = overrideRefusalOrNull(print, Handshake13.PRINT_MAX,
                "cc.predict.fingerprint (or its env twin CC_PREDICT_FINGERPRINT)");
        if (refusal != null) {
            throw new IllegalStateException(refusal + " - fix or unset it");
        }
    }

    /**
     * D4.7 B1b producer: self-compute the
     * content-fingerprint composite at app load. The client cannot enumerate {@code data/**}
     * under classloader B, so the file LIST comes from the packer-minted sidecar (the kit's
     * {@code kit.files} in the {@code cc.match.descriptor}'s folder, else the derived sibling
     * {@code <cc.match.descriptor>.fingerprint} of a source descriptor); the BYTES are the mod's own real local
     * files, read via the measured {@code FileReader} ISO_8859_1 char≡byte lane.
     * {@code hs} = {@link HandshakeCodec#HANDSHAKE_VERSION}
     * (bundled with the mod jar since the extraction — the jar keeps ZERO dep on the
     * server-only {@code PilotIntentDatagramServer} endpoint; the build's jar-content gate
     * asserts it stays absent).
     * ANY failure ⇒ blank: the client stays fail-open (launches, presents blank), the armed door
     * stays fail-closed (bounces {@code content_fingerprint_required}, a readable reason). A
     * missing sidecar/file logs INFO (the EXPECTED pre-D4.8-kit state), everything else ERROR.
     */
    private static String selfComputeFingerprint(MatchDescriptor descriptor) {
        // Phase 1 — the sidecar read, its OWN catch lanes: a missing sidecar is the EXPECTED
        // pre-D4.8 state (INFO, keeps watch logs clean); once past this, any failure below is
        // a real anomaly (ERROR — e.g. a missing LISTED file = the stale-kit diagnosis lane).
        String sidecarPath;
        java.util.List<String> sidecarLines;
        try {
            String descriptorPath = MatchMissionBuilder.cachedDescriptorPath();
            if (descriptorPath == null) {
                throw new IllegalStateException("no cc.match.descriptor path (unreachable: the"
                        + " udp branch requires a loaded descriptor)");
            }
            // The file list is the kit's own kit.files beside
            // the match file (a kit extract: CC_Client\kit.files next to CC_Client\match.txt). A
            // match file outside a kit (a host window handed the source descriptor) has none beside
            // it; there the list is the door's own <descriptor>.fingerprint, the same packer bytes.
            // The path is built by string ops only: no java.nio / java.io type under classloader B.
            int cut = Math.max(descriptorPath.lastIndexOf('/'), descriptorPath.lastIndexOf('\\'));
            sidecarPath = descriptorPath.substring(0, cut + 1) + "kit.files";
            try {
                sidecarLines = ContentFingerprintClientSurface.readLines(sidecarPath);
            } catch (java.io.IOException noKitFiles) {
                // the same by-NAME miss test as the lane below (the catch type stays IOException)
                if (!"java.io.FileNotFoundException".equals(noKitFiles.getClass().getName())) {
                    throw noKitFiles;
                }
                sidecarPath = descriptorPath + ".fingerprint";
                sidecarLines = ContentFingerprintClientSurface.readLines(sidecarPath);
            }
        } catch (java.io.IOException noSidecar) {
            // Catch types here MUST be B-allowlisted names: classloader B name-denies
            // java.io.FileNotFoundException, and a denied CATCH TYPE detonates during exception
            // dispatch itself — the deny SecurityException escapes every handler in this frame
            // (incl. the Throwable lane below) and kills the window at boot. That was the
            // rung-2 Fatal: the sidecar-MISS branch is the only path that resolves the
            // FNFE catch type. IOException IS allowlisted; the in-flight FNFE OBJECT
            // (bootstrap-defined) crosses fine and stays diagnosable by NAME (java.lang.Class is
            // allowlisted). Measured: run-b-fp-probe.ps1 miss-branch pair, 23/23.
            boolean miss = "java.io.FileNotFoundException"
                    .equals(noSidecar.getClass().getName());
            if (miss) {
                PilotWatchLog.info("content fingerprint: no kit.files and no sidecar (" + noSidecar.getMessage()
                        + ") -> presenting blank (EXPECTED pre-D4.8: kits carry no sidecar yet; an"
                        + " armed door bounces content_fingerprint_required)");
            } else {
                PilotWatchLog.error("content fingerprint: sidecar read FAILED -> presenting blank"
                        + " (an armed door bounces content_fingerprint_required)", noSidecar);
            }
            return "";
        } catch (Throwable t) {
            PilotWatchLog.error("content fingerprint: sidecar read FAILED -> presenting blank"
                    + " (an armed door bounces content_fingerprint_required)", t);
            return "";
        }
        try {
            ModSpecAPI mod = Global.getSettings().getModManager().getModSpec("cc_client");
            String modRoot = mod.getPath();
            String jarRel = null;
            for (String jar : mod.getJars()) {
                if (jar != null && jar.replace('\\', '/').endsWith("cc-client-mod.jar")) {
                    jarRel = jar;
                    break;
                }
            }
            if (jarRel == null) {
                throw new IllegalStateException("cc_client jars list carries no cc-client-mod.jar:"
                        + " " + mod.getJars());
            }
            String jarPath = jarRel.indexOf(':') >= 0 || jarRel.startsWith("/")
                    || jarRel.startsWith("\\") ? jarRel : modRoot + "/" + jarRel;
            String print = ContentFingerprintClientSurface.selfCompute(modRoot, sidecarLines,
                    sidecarPath, jarPath,
                    HandshakeCodec.HANDSHAKE_VERSION, descriptor.matchId());
            // FOLD the boot id-table digest into data= — the
            // print a SESSION-claiming HELLO must present (PROBE/VCHK take the raw one too). No
            // table (the mint failed) = the raw print, logged: an armed door refuses the session
            // with content_fingerprint_mismatch raw_ok=true, the readable diagnosis.
            cc.starsector.server.runtime.IdTables tables = cc.starsector.server.runtime.IdTables.installedOrNull();
            if (tables == null) {
                PilotWatchLog.error("content fingerprint self-computed UNFOLDED (no boot id table —"
                        + " an armed door refuses the session claim): " + print,
                        new IllegalStateException("id-tables: " + ClientIdTableBoot.describe()));
                return print;
            }
            // The boot MOD SET folds beside vtab (ClientModSetBoot). No set (the mint
            // failed, its own ERROR line says why) = the vtab-only fold, logged: an armed door refuses
            // the session claim with presented_fold=vtab_only — the readable diagnosis.
            cc.starsector.server.runtime.ModSet modSet = cc.starsector.server.runtime.ModSet.installedOrNull();
            if (modSet == null) {
                PilotWatchLog.error("content fingerprint: no boot mod set — folding vtab ONLY (an armed door"
                        + " refuses the session claim: content_fingerprint_mismatch presented_fold=vtab_only)",
                        new IllegalStateException("mod-set: " + ClientModSetBoot.describe()));
            }
            String folded = cc.starsector.server.match.ContentFingerprints.foldContent(print, tables.vtab(),
                    modSet == null ? null : modSet.digest());
            PilotWatchLog.info("content fingerprint self-computed: " + print + " -> folded over vtab="
                    + tables.vtab() + " modset=" + (modSet == null ? "none" : modSet.digest()) + ": " + folded);
            return folded;
        } catch (Throwable t) {
            PilotWatchLog.error("content fingerprint self-compute FAILED (a missing LISTED file"
                    + " = stale/broken install) -> presenting blank (an armed door bounces"
                    + " content_fingerprint_required)", t);
            return "";
        }
    }
}
