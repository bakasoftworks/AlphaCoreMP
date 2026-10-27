package cc.starsector.server.runtime;

import cc.starsector.server.ops.OpsStatusFile;
import cc.starsector.server.ops.PeerText;
import java.nio.file.Path;
import java.util.List;
import static cc.starsector.server.runtime.PersistentPilotIntentCombatServer.waveReceipt;

/**
 * deferred-refactors PSS carve: the P27 ban-operator block carved VERBATIM out of
 * {@link PilotServeSession} - the boot read ({@link #bootBans}), the ONE off-frame writer
 * ({@link #openSink} / {@link #persistBans} / {@link #closeSink}) and the operator ban verbs with
 * their receipts ({@link #operatorBan}). Serve (frame) thread only = the serve's command lane;
 * memory stays authoritative (the door's {@code BanSet}), the sink is the only thing that touches
 * the file, off-frame. Order of use = PSS serve(): bootBans -> door open -> openSink ->
 * per-command operatorBan / the fault-belt settle via sinkOpen + persistBans -> teardown closeSink.
 */
final class BanOperatorLane {
    /** The per-server bans file beside the config
     *  ({@code null} = memory-only: a file-less lane) and its ONE off-frame writer. */
    static final String BANS_FILE_NAME = "cc-server.bans";
    private Path bansFile;
    private BanPersistSink banSink;
    /** The set {@link #bootBans} loaded (= the door's, installed
     *  before its reader starts) — {@link #openSink} saves it once when the load dropped expired entries. */
    private BanSet bootSet;
    /** Bans a replay door added that {@link #persistBans} kept off disk. */
    private long bansUnpersisted;

    long bansUnpersisted() {
        return bansUnpersisted;
    }

    /** {@code <config dir>/cc-server.bans} via
     *  {@link BanSet#load} (throws on a present-but-unreadable file — the serve refuses to boot);
     *  no config file loaded = memory-only, logged once. */
    BanSet bootBans() {
        java.nio.file.Path dir = ServerConfig.configFileDirOrNull();
        if (dir == null) {
            bansFile = null;
            System.out.println("npeer-serve bans_file state=memory-only (no config file loaded;"
                    + " bans last for this serve only)");
            return new BanSet();
        }
        bansFile = dir.resolve(BANS_FILE_NAME).toAbsolutePath();
        // r4: a refused boot confesses on the log (path =
        // operator config + the SimpleName, NEVER the message — the rethrown chain carries it).
        try {
            bootSet = BanSet.load(bansFile, line -> System.out.println("npeer-serve " + line),
                    System.currentTimeMillis());
            return bootSet;
        } catch (RuntimeException refused) {
            System.out.println("npeer-serve bans_file state=refused path=" + bansFile
                    + " err=" + refused.getClass().getSimpleName());
            throw refused;
        }
    }

    /** Hand the current render to the off-frame writer (never I/O here). */
    String persistBans(PilotIntentDatagramServer door) {
        // A re-simulation re-runs a recorded ban in memory (the seat
        // vacates as recorded) but NEVER writes the host's bans file — its key is the replay door's inert one.
        // The discarded render clears the set's dirty bit, so the fault
        // belt's settle never re-counts the same unpersisted ban.
        if (door.isReplay()) {
            bansUnpersisted++;
            door.bans().render();
            return "replay";
        }
        if (banSink == null) {
            return "none";
        }
        banSink.persist(door.bans().render());
        return "queued";
    }

    /**
     * The P27 ban verbs on the
     * serve's command lane (INSIDE the frame loop — memory only; persistence = {@link #persistBans},
     * queued only after an ADDED or RETIMED entry, a successful unban or a src strip, never on a no-op). A chat deposit
     * ({@code receiptSessionId != 0}) is ADMIN origin (the door re-checks the target's admin bit
     * and the per-serve cap); every file / GUI mint is OP. ONE log row per verb + the chat receipt
     * (D5 law: the real outcome; "save queued" — the writer lands it off-frame, its own
     * {@code bans_persisted} / {@code bans_persist_failed} row says which).
     * The row below IS the one row —
     * the door's ban/unban calls are silent (this row carries its {@code origin=}/{@code added=}
     * and {@code requester=}/{@code reason=} tokens, appended); {@code ban}/{@code ban-src} carry
     * an expected-occupant cid KEY (the tail, else the frame-time occupant's key) and the chat
     * deposit's key rides to the door UNDECODED; {@code bans} lists at most
     * {@link OpsStatusFile#MAX_BAN_ROWS} rows on the frame.
     */
    void operatorBan(PilotIntentDatagramServer door, OperatorCommandFile.Command command,
            int round, int frame) {
        long now = System.currentTimeMillis();
        BanSet.Origin origin = command.receiptSessionId() != 0L ? BanSet.Origin.ADMIN : BanSet.Origin.OP;
        String at = " round=" + round + " frame=" + frame;
        if (command.verb() == OperatorCommandFile.Verb.BANS) {
            // LIVE entries only at the lane's one wall clock (an
            // unswept expired entry is absent), each row ending in its until= (epoch ms; 0 = permanent).
            List<BanSet.Entry> all = door.bans().snapshot(now);
            int shown = Math.min(all.size(), OpsStatusFile.MAX_BAN_ROWS);
            for (int i = 0; i < shown; i++) {
                BanSet.Entry e = all.get(i);
                System.out.println("npeer-serve bans_entry i=" + (i + 1) + " kind="
                        + BanSet.kindToken(e.kind()) + " key=" + PeerText.quote(logKey(e.kind(), e.key()))
                        + " at=" + e.atMillis() + " origin=" + BanSet.originToken(e.origin())
                        // The paired address, BEFORE until= (the row's end stays pinned)
                        + (e.src() == null ? "" : " src=" + PeerText.quote(e.src()))
                        + " until=" + e.until());
            }
            // A8 — the match's source holds AFTER the entries
            // (operator log only; server-minted keys, never a chat reply).
            // boot-hydrated holds are unbounded by the per-serve cap, so ≤ ADMIN_BAN_CAP rows,
            // NEWEST first — keysForLog(max) sweeps expired sources FIRST, then the RAW size()
            // makes holds_more exact; holds_more sits BEFORE holds= (the row's end is pinned).
            List<String> holds = door.sourceHold.keysForLog(PilotIntentDatagramServer.ADMIN_BAN_CAP);
            int holdsTotal = door.sourceHold.size();
            for (int j = 0; j < holds.size(); j++) {
                System.out.println("npeer-serve bans_entry i=" + (shown + j + 1) + " kind=hold src="
                        + PeerText.quote(holds.get(j)) + " origin=admin");
            }
            String where = banSink != null ? "full list in the bans file" : "no bans file on this server";
            System.out.println("npeer-serve operator_bans count=" + all.size() + " shown=" + shown
                    + " (" + where + ")" + at
                    + (holdsTotal > holds.size() ? " holds_more=" + (holdsTotal - holds.size()) : "")
                    + (holdsTotal == 0 ? "" : " holds=" + holdsTotal));
            waveReceipt(door, command, "bans: " + all.size() + ", " + shown + " listed in the server log ("
                    + where + ")");
            return;
        }
        if (command.verb() == OperatorCommandFile.Verb.UNBAN) {
            String[] kindKey = command.text().split(" ", 2);
            BanSet.Kind kind = BanSet.kindOrNull(kindKey[0]);
            String typed = kindKey.length == 2 ? kindKey[1] : "";
            // A cid key in either form lifts the digest-keyed entry AND its hold
            // pairs (the one key form both use); a key of neither form matches nothing (no_such_ban).
            String cidKey = kind == BanSet.Kind.CID ? BanSet.cidBanKeyOrNull(typed) : null;
            String key = kind == BanSet.Kind.CID ? (cidKey == null ? typed : cidKey) : typed;
            BanSet.Entry entry = kind == null ? null : door.bans().find(kind, key, now);
            boolean lifted = kind != null && door.unban(kind, key, origin, now);
            // A8 — a lifted cid entry lifts its PAIR in any hold
            // (the hold lifts with its last pair); a house `unban src <key>` lifts that source's hold
            // outright, entry or not. The pairs PERSIST now (src=
            // on the entries), so `unban src` first strips src= from every carrying entry, THEN lifts the
            // hold — two un-nested calls (no lock-order hazard) — and saves when it stripped (else the
            // boot hydrate re-bars the address); a cid lift whose address stays held says so (r1).
            int stripped = kind == BanSet.Kind.SRC && origin == BanSet.Origin.OP ? door.bans().stripSrc(key) : 0;
            int pairLift = kind == BanSet.Kind.SRC
                    ? (origin == BanSet.Origin.OP && door.sourceHold.liftBySrc(key) ? 2 : 0)
                    : lifted ? door.sourceHold.liftByCidCount(key) : 0;
            boolean holdLifted = pairLift == 2;
            String persist = lifted || stripped > 0 ? persistBans(door) : "unchanged";
            System.out.println("npeer-serve operator_unban kind=" + (kind == null ? "?" : BanSet.kindToken(kind))
                    + " key=" + PeerText.quote(logKey(kind, key)) + " lifted=" + lifted + " persist=" + persist + at
                    + " requester=" + BanSet.originToken(origin)
                    + (lifted || holdLifted || stripped > 0 ? ""
                            : entry == null ? " reason=no_such_ban" : " reason=operator_ban_house_file_only")
                    + (kind == BanSet.Kind.SRC ? " stripped=" + stripped : "")
                    + (holdLifted ? " hold_lifted=true" : ""));
            // The lift receipt names WHICH ban (hex abbrev).
            waveReceipt(door, command, lifted ? AdminBanCommand.liftReceipt(kind == BanSet.Kind.CID ? key : null,
                            AdminBanCommand.saveNote(persist), pairLift == 1)
                    : origin == BanSet.Origin.OP ? "unban refused: no such ban"
                            : "unban refused: no such admin ban");
            return;
        }
        String verb = switch (command.verb()) {
            case BAN -> "ban";
            case BAN_SRC -> "ban-src";
            case BAN_CID -> "ban-cid";
            case BAN_IP -> "ban-ip";
            default -> "ban-session";
        };
        BanSet.Kind kind = command.verb() == OperatorCommandFile.Verb.BAN_SRC
                || command.verb() == OperatorCommandFile.Verb.BAN_IP ? BanSet.Kind.SRC : BanSet.Kind.CID;
        String client = "";
        String refusal = null;
        boolean offline = false;
        PilotIntentDatagramServer.BanOutcome outcome = null;
        // The term the verb CARRIES — a chat (ADMIN) cid ban takes the
        // deposit's term (bare = BanTerm.DEFAULT); every other ban is PERMANENT (the BanSet.Entry law). The
        // row's term= names this carried term even on a refusal or a repeat (added=false); the RECEIPT names
        // it only when the entry was ADDED (AdminBanCommand).
        BanTerm term = origin == BanSet.Origin.ADMIN && kind == BanSet.Kind.CID ? BanTerm.DEFAULT : BanTerm.PERM;
        switch (command.verb()) {
            case BAN, BAN_SRC -> {
                PilotIntentDatagramServer.SeatOccupant occ = door.seatOccupantOrNull(command.seatId());
                // The operator's EXPECTED occupant
                // key (the line's tail — GUI/web always send it); a key-less house-file line keeps
                // "whoever holds the seat at frame time".
                String tailKey = command.text();
                if (occ == null || occ.clientId() == null) {
                    refusal = "no such seat";
                } else if (occ.live()) {
                    client = occ.clientId();
                    outcome = door.banSession(occ.sessionId(), tailKey.isEmpty() ? BanSet.cidKey(occ.clientId())
                            : tailKey, kind, origin, now, command.receiptSessionId(), term);
                } else if (kind == BanSet.Kind.SRC) {
                    client = occ.clientId();
                    refusal = "ban-src needs a live seat";
                } else if (occ.clientId().isBlank()) {
                    refusal = "no such seat";
                } else if (!tailKey.isEmpty() && !tailKey.equals(BanSet.cidKey(occ.clientId()))) {
                    // the dead occupant = the seat's LAST-admitted session, maybe not the one the
                    // operator saw: a mismatch adds + persists nothing
                    client = occ.clientId();
                    offline = true;
                    refusal = "target changed";
                } else {
                    // The seat's most recent (dead) session's cid — "offline"
                    client = occ.clientId();
                    offline = true;
                    // the entry key form = the digest the session built at admission: no hash on the serve thread.
                    //
                    // The offline ban pairs the seat's LAST peer address under
                    // the same policy a chat ban asks (loopback never; a seat occupant is never an observer; no
                    // requester ⇒ no self guard) — the id is one deletable file, the address is the bar.
                    String holdSrc = door.sourceHold.wantedSrcOrNull(true, false, occ.peerAddress(), null);
                    outcome = door.banKey(kind, occ.cidAuthorDigest(), origin, now, holdSrc, term);
                }
            }
            case BAN_CID -> {
                // Split the `term=` tail FIRST (surprise 6) — its
                // PRESENCE selects the /banned RETIME (never an add); key-only = the offline ban.
                OperatorCommandFile.BanCidDeposit parsed = OperatorCommandFile.parseBanCidDepositOrNull(command.text());
                // The deposit's key in either player-key form → the entry's digest
                // (a key of neither form rides on unchanged, and the door refuses it bad_key).
                String depKey = parsed == null ? null : BanSet.cidBanKeyOrNull(parsed.key());
                OperatorCommandFile.BanCidDeposit dep = parsed == null || depKey == null ? parsed
                        : new OperatorCommandFile.BanCidDeposit(depKey, parsed.termOrNull());
                if (dep != null && dep.termOrNull() != null) {
                    retime(door, command, dep, now, at);
                    return;
                }
                // A digest key carries no cid (one-way): the row's client= prints "", never "null".
                client = "";
                if (dep == null) {
                    refusal = "bad key";
                } else {
                    // A key some session here carried pairs that session's
                    // address (live, else its last peer) under the hold policy, as `ban <seat>` does; else none
                    String holdSrc = door.holdSrcForDigestOrNull(dep.key());
                    outcome = door.banKey(kind, dep.key(), origin, now, holdSrc, term);
                }
            }
            case BAN_IP -> {
                // The typed source key (the parse already holds it to a stored
                // form) — banKey adds the permanent SRC entry and sweeps every live session whose peer it covers; the
                // row below reads verb=ban-ip key=<key> ... added=<bool>, the key raw (an address, logKey).
                outcome = door.banKey(kind, command.text(), origin, now, term);
            }
            default -> {
                // BAN_SESSION: "<sessionId>" (house file) or "<sessionId> <cidKey>" (chat, A4)
                // the door compares KEYS — the
                // deposit's key rides UNDECODED; the decode = the grammar check + the log display.
                // The parse is the pure (public) OperatorCommandFile
                // parseBanSessionDepositOrNull so a row pins the NON-ZERO signed-id law at THIS site.
                OperatorCommandFile.BanSessionDeposit dep =
                        OperatorCommandFile.parseBanSessionDepositOrNull(command.text());
                if (dep == null) {
                    refusal = "target changed";
                } else {
                    client = dep.cidKeyOrNull() == null ? "" : BanSet.cidOfKeyOrNull(dep.cidKeyOrNull());
                    if (origin == BanSet.Origin.ADMIN && dep.termOrNull() != null) {
                        term = dep.termOrNull();
                    }
                    outcome = door.banSession(dep.sessionId(), dep.cidKeyOrNull(), kind, origin, now,
                            command.receiptSessionId(), term); // The requester = the self-hold guard
                }
            }
        }
        String status = outcome == null ? refusal.replace(' ', '_') : outcome.status();
        // The A1 no-entry ("held") arm is superseded — an ok ban writes its entry.
        // A pair attached to a standing entry is a change the file must keep too
        boolean srcAttached = outcome != null && outcome.srcAttached();
        String persist = outcome != null && (outcome.added() || srcAttached) ? persistBans(door) : "unchanged";
        int kicked = outcome == null ? 0 : outcome.kicked();
        System.out.println("npeer-serve operator_ban verb=" + verb + " seat=" + command.seatId()
                + " client=" + PeerText.quoteCid(client) + " kind=" + BanSet.kindToken(kind)
                + " key=" + PeerText.quote(outcome == null ? "" : logKey(kind, outcome.key())) + " status=" + status
                + (offline ? " offline=true" : "") + " kicked=" + kicked + " persist=" + persist
                // term= AFTER persist= — the status..persist token runs stay pinnable
                + " term=" + term.token + at + (srcAttached ? " src_attached=true" : "")
                // The door's silenced row tokens
                + " origin=" + BanSet.originToken(origin) + " added=" + (outcome != null && outcome.added()));
        waveReceipt(door, command, "ok".equals(status)
                ? AdminBanCommand.banReceipt(term.token, outcome.added(), offline, kicked, kind == BanSet.Kind.CID,
                        outcome.held(), AdminBanCommand.saveNote(persist))
                : banReceipt(status));
    }

    /**
     * The {@code /banned #n <term>} retime (a BAN_CID deposit
     * with a {@code term=} tail) — the door's {@link PilotIntentDatagramServer#retimeKey} at the
     * lane's ONE {@code wallNow}; saved on RETIMED (the entry is dirty, the receipt says
     * "save queued"); ONE {@code operator_retime} row (the server-minted key's {@link #logKey} digest +
     * term tokens only).
     */
    private void retime(PilotIntentDatagramServer door, OperatorCommandFile.Command command,
            OperatorCommandFile.BanCidDeposit dep, long wallNow, String at) {
        PilotIntentDatagramServer.RetimeOutcome o = door.retimeKey(dep.key(), dep.termOrNull(), wallNow);
        String persist = o.result() == BanSet.RetimeResult.RETIMED ? persistBans(door) : "unchanged";
        System.out.println("npeer-serve operator_retime key=" + PeerText.quote(logKey(BanSet.Kind.CID, dep.key()))
                + " term=" + dep.termOrNull().token + " status=" + o.result().name().toLowerCase(java.util.Locale.ROOT)
                + " hold_moved=" + o.holdMoved() + " persist=" + persist + at);
        waveReceipt(door, command, AdminBanCommand.retimeReceipt(o.result(), dep.key(), dep.termOrNull().token,
                AdminBanCommand.saveNote(persist)));
    }

    /**
     * A ban key in the form this lane's stdout rows print it ({@code bans_entry}, {@code operator_unban},
     * {@code operator_ban}, {@code operator_retime}). A CID key is the target player's client id hex-encoded,
     * and on a password door that id is the player's seat key, so a host who pastes the server log would hand
     * it over: it prints as {@link OperatorCommandFile#digestOfKey}, the one-way digest the reel records for
     * the same key, so the log and the reel name one player alike. A key whose kind did not parse takes the
     * same path (digestOfKey returns a token that decodes to no client id unchanged). A SRC key is an address
     * the operator must read to act on it, so it prints raw. A ban entry's CID key IS that digest (the door and
     * the bans file hold it), so it prints unchanged, and the printed key is the one {@code unban cid} takes.
     *
     */
    static String logKey(BanSet.Kind kind, String key) {
        return kind == BanSet.Kind.SRC ? key : OperatorCommandFile.digestOfKey(key);
    }

    /** The chat receipt for one REFUSED ban verb (constants + counts only; ≤ 120 chars, ASCII); the
     *  ok receipt = {@link AdminBanCommand#banReceipt}. */
    static String banReceipt(String status) {
        return switch (status) {
            case "target_changed" -> "ban refused: that player left or changed";
            case "target_admin" -> AdminBanCommand.TARGET_ADMIN;
            case "cid_in_token_mode" -> "ban refused: cid bans need a password server - use ban-src";
            case "admin_cap" -> "ban refused: the admin ban cap ("
                    + PilotIntentDatagramServer.ADMIN_BAN_CAP + " per match) is reached";
            case "cap" -> "ban refused: the ban list is full (" + BanSet.MAX_ENTRIES + ")";
            default -> "ban refused: " + status.replace('_', ' ');
        };
    }
    // parseLongOr0 deleted — the BAN_SESSION parse is the ONE pure grammar,
    // OperatorCommandFile.parseBanSessionDepositOrNull; a dead second helper invites a re-wire.

    /** PSS serve(), right after the door opens (the boot read already set {@link #bansFile}). */
    void openSink() {
        banSink = bansFile == null ? null
                : BanPersistSink.open(bansFile, line -> System.out.println("npeer-serve " + line));
        // The boot purge's save (surprise 5: persistBans answers
        // "none" before the sink opens) — ONCE, only when the load dropped expired entries or converted
        // legacy hex cid keys to their digests (dirty).
        if (banSink != null && bootSet != null && bootSet.dirty()) {
            banSink.persist(bootSet.render());
        }
    }

    /** The fault-belt settle guard (PSS handleOperatorCommand belt): a memory-only serve has nothing to settle. */
    boolean sinkOpen() {
        return banSink != null;
    }

    /** PSS teardown(). */
    void closeSink() {
        if (banSink != null) {
            banSink.close(); // drains a pending rewrite (≤ 2 s), then stops
            banSink = null;
        }
    }
}
