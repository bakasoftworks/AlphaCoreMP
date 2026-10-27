package cc.starsector.client.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import cc.starsector.server.match.MatchDocumentLaws;
import cc.starsector.server.runtime.DatagramFrameCodec;
import cc.starsector.server.runtime.Handshake13;
import cc.starsector.server.runtime.Handshake13Flight;
import cc.starsector.server.runtime.Handshake13PairBudget;
import cc.starsector.server.runtime.MatchDocFrameCodec;

/**
 * The door PROBE's client half — the SOCKET + COLLECTOR of the pre-claim {@code /ship} list fetch
 * (server side: {@code PilotIntentDatagramServer.handleProbeLocked}). One version-13
 * PROBE flight ({@link Handshake13#FLAG_PROBE}; the match password SEALED, never readable on the
 * wire; fingerprint = the joiner's self-computed composite) goes to the
 * dialed door; the door runs its EXISTING pre-claim checks and either answers the existing REJECT
 * WELCOME (a readable reason — {@code kit_outdated} / {@code match_password_mismatch} /
 * {@code content_fingerprint_mismatch} …) or, without claiming anything, the {@code /ship} listing
 * RAW: channel CTRL, sessionId 0, seq = frame index, capped at {@link #MAX_FRAMES}. Twin of the
 * server-tree drill client's {@code probe()} (the L2 ownerless proof).
 *
 * <p><b>Provenance:</b> relocated VERBATIM out of
 * {@code cc.starsector.client.join.JoinProbe} so the fetch is legal INSIDE the
 * game JVM: {@code join/**} is exempt from the classloader-B deny scan because it runs only in the
 * JoinGui JVM, and by the same law an in-game class may reference NO {@code join/**} class
 * (latent in-game Fatal; a build-time scan enforces both directions).
 * This class is B-scanned at every build — its deps are {@code java.net.*}, {@link SecureRandom},
 * {@link ClientListingReader}, the bundled codecs (the match document's included) and a strict
 * {@code java.nio.charset} UTF-8 decoder; {@link IOException} is on B's io-allow
 * list; nothing here touches Swing, paths or reflection. Two consumers: the joiner GUI's picker
 * (through the thin {@code JoinProbe} facade — call sites and the {@code --selftest} goldens
 * unchanged) and {@code CcClientModPlugin}'s in-game fallback: an EMPTY pick on
 * the password lane adopts the door's published default ({@link Result#defaultId}) as THE pick
 * before the HELLO, so the mission's t=0 hull IS the server default (no descriptor fly-in followed
 * by the RAPP re-field swap — the host-window hull swap seen live). Server-owned
 * value, wire-delivered live, never baked into the kit.
 *
 * <p>Security posture (the standing wire-security rules): fail-closed decode
 * (an undecodable envelope / WELCOME / listing frame is IGNORED and counted, never thrown at the
 * caller); bounded allocation (ONE {@link #RECEIVE_BUFFER_BYTES} buffer, at most {@link #MAX_FRAMES}
 * frames × 7 entries retained); only datagrams from the DIALED address count; a WELCOME is read by
 * the try's flight only; nothing wire-derived becomes a path, a command or a launch byte — ship ids pass
 * {@link #offerable} (the bat's own {@code :vguard} charset) before anyone sees them (an unlawful one is
 * hidden and counted), an unlawful MOD id (the same law) refuses the whole listing, and a pick
 * names a hull only after the door's own canonicalization at admit. NO retransmit storm: the door
 * budgets one reply per source per second and drops the rest silently by design, so this sends at
 * most {@link #TRIES} HELLOs {@link #RECEIVE_TIMEOUT_MILLIS} apart; the door keys that budget per
 * source {@code ip:port}, so this ephemeral socket never starves the real HELLO's budget.
 *
 * <p>Split for testability: {@link Collector} is the pure datagram→result accumulator (the GUI's
 * {@code --selftest} goldens feed it canned bytes); {@link #fetch13} (the match password sealed, never readable on
 * the wire) is the only socket code.
 */
public final class DoorProbe {

    /** Mirror of {@code PilotIntentDatagramServer.PROBE_MAX_FRAMES} (4 — the public-door cap; the
     * WIDENED 128-frame debug-door reply was retired server-side with the join-window widening).
     * A door serves at most its own cap; this is only how many
     * frames the client will accept and hold: 4 × 7 ids = 28 ids. The endpoint class is deliberately
     * NOT bundled into the mod jar — the build's jar-content gate asserts that). */
    public static final int MAX_FRAMES = 4;
    /** Mirror of {@code PilotIntentDatagramServer.PROBE_MODSET_MAX_FRAMES} —
     * how many KIND_MODSET frames the collector holds (8 × 7 = 56 mods). */
    public static final int MAX_MODSET_FRAMES = 8;
    /** One try = one HELLO + this long listening; {@link #TRIES} tries total. */
    public static final int RECEIVE_TIMEOUT_MILLIS = 1500;
    public static final int TRIES = 2;
    /** A cookied probe HELLO the door holds (per-source gap) is retransmitted on
     * this cadence inside the try — just past the door's 250 ms gap, like the dial's retransmit. */
    public static final int COOKIED_RETRANSMIT_MILLIS = 300;
    public static final int RECEIVE_BUFFER_BYTES = DatagramFrameCodec.MAX_DATAGRAM_BYTES;
    /** ROW SEAM (read only by {@code Hs13KitSenderRows} R4a): the flight of the
     * last {@link #fetch13} try on any thread, stored AFTER that try's {@code finally} abandoned it — an abandoned
     * flight holds a zeroed secret and no root, so this pins nothing live. Product code never reads it. */
    static volatile Handshake13Flight lastFetch13Flight;
    /** The HELLO v8 {@code variantId} decode cap — a longer id could never ride the join. */
    public static final int OFFER_ID_MAX = 64;

    private DoorProbe() {
    }

    /**
     * Only ids the launch path can carry are ever offered/adopted: join-cc.bat's {@code :vguard}
     * charset {@code ^[0-9A-Za-z][0-9A-Za-z._:-]*$} (the value rides a bare {@code %VAR%} into the
     * JAVA_TOOL_OPTIONS line), capped at the HELLO decode law. Every stock id passes today; a
     * wire-legal but bat-illegal id is counted as hidden, never shown, never launched.
     */
    public static boolean offerable(String id) {
        return id != null && id.length() <= OFFER_ID_MAX && vguardShape(id);
    }

    /**
     * The bat's {@code :vguard} shape alone, no cap of its own: non-empty, first character a letter or digit,
     * then {@code [0-9A-Za-z._:-]}. {@link #offerable} adds the HELLO cap; the join window's host gate
     * ({@code JoinLaunchPlan.validate}) adds the bat's 256 (delta scan kit-join-window-1: ONE predicate).
     */
    public static boolean vguardShape(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        if (!alnum(id.charAt(0))) {
            return false;
        }
        for (int i = 1; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(alnum(c) || c == '.' || c == '_' || c == ':' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static boolean alnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /** Player-facing text for a door REJECT reason; unknown reasons show verbatim (the WELCOME
     * decode law already bounds them to single-line printable text). */
    public static String rejectMessage(String reason) {
        String r = reason == null ? "" : reason;
        switch (r) {
            case "match_password_mismatch":
                return "Wrong match password - check it with the host (list unavailable).";
            case "match_password_required":
                return "Enter the match password to fetch the server's ship list.";
            case "kit_outdated":
                return "This kit is outdated - get the NEWEST kit zip from the host.";
            case "content_fingerprint_mismatch":
            case "content_fingerprint_required":
                // The ONE copy of the kit-differs line (both join windows read it
                // through here); the fix is the host's kit again — the match itself comes from the server, never a paste
                return "This CC_Client folder is not the host's kit (a file differs) - extract the host's kit zip"
                        + " again, then press Refresh.";
            case "client_id_required":
                // The door's pre-claim HELLO checks can mint this on
                // a probe too (blank cid) — a sentence, never the raw token.
                return "This kit sent no player id - re-extract the newest kit zip and retry.";
            // The P27 ban token is in probe() too.
            case "banned":
                return "You are banned from this server.";
            // THE FLIP stage Y: handshake_refused is in probe() too (see describeRefusal).
            case "handshake_refused":
                return "The server could not read this kit's request - re-extract the newest kit zip and retry.";
            case "probe_unsupported":
                return "This server does not publish a ship list before launch - you will spawn"
                        + " in the match default.";
            case "probe_no_pickables":
                return "The server offers no ship choices for this match - server default only.";
            default:
                return "The server declined the ship-list request: "
                        + (r.isEmpty() ? "(no reason)" : r.length() > 64 ? r.substring(0, 64) : r);
        }
    }

    /** One published mod-set row — the serve's boot {@code ModSet} record as the
     * KIND_MODSET listing carries it (id, the 24-char version label, the files prefix = jars word +
     * data word, {@code ModSet.filesPrefix}). */
    public static final class ModRow {
        public final String id;
        public final String versionLabel;
        public final long filesPrefix;
        /** True = the server published this mod as client-OPTIONAL (mark 27):
         * staged only when the joiner has it at the pinned version, never a launch problem, and
         * excluded from the game's own fold. */
        public final boolean optional;

        public ModRow(String id, String versionLabel, long filesPrefix) {
            this(id, versionLabel, filesPrefix, false);
        }

        public ModRow(String id, String versionLabel, long filesPrefix, boolean optional) {
            this.id = id;
            this.versionLabel = versionLabel;
            this.filesPrefix = filesPrefix;
            this.optional = optional;
        }
    }

    /** Outcome of one fetch: served (ids in server order, offerable only), rejected (reason), or
     * failed (no reply / network error). Exactly one of the three. */
    public static final class Result {
        public final List<String> ids;
        /** The server's published mod set (empty = none published: a
         * vanilla serve, an older server, or the modset frames all lost). */
        public final List<ModRow> mods;
        /** The server's full mod count ({@code totalAvailable} of the modset listing; 0 = none). */
        public final int modsAvailable;
        /** True when every modset frame the cap allows landed AND nothing was cut by the cap —
         * false = the diff is partial (the page says so and the door still decides). */
        public final boolean modsComplete;
        /** Ids the server listed but the picker cannot carry (see {@link #offerable}). */
        public final int hidden;
        /** The server's full count ({@code totalAvailable}) — more than shown when capped. */
        public final int totalAvailable;
        //
        /** The SERVER's host-picked default ship id (probe mark
         * {@link ClientListingReader#MARK_SHIP_NEXT_RESPAWN}), or null when none was named — the
         * join window shows + pre-selects it as combo entry 0; the in-game fallback adopts it as
         * the pick. Always {@link #offerable}. */
        public final String defaultId;
        public final String rejectReason;
        public final String error;
        /** True only for THIS build's own refusal of the server's listing
         * (the mod-id law in {@link Collector#result}) — an answer, not a silence: a re-probe
         * gets the same list, so the in-game probe loop stops and the briefing says so. */
        public final boolean listRefused;
        /** True only for a version-13 list request THIS build could
         * not form ({@link #fetch13}: an input the version-13 frame cannot carry, or its crypto provider is broken)
         * — NOTHING left the socket and a retry forms the same request, so the in-game probe loop stops and the
         * briefing says so. Never a server answer, and never set with {@link #listRefused}. */
        public final boolean localRefusal;
        /** The match document: the server's own text when a COMPLETE, authenticated
         *  set of document frames arrived this fetch (the receive laws up to the inflate passed), else null. The
         *  window parses and stages it; a null with {@link #sawDocument} true reads {@link #matchDocRefusal}. */
        public final String matchDocText;
        /** True when at least one document frame was seen this fetch, complete or not — the in-game click probe's
         *  tell that the host changed the match after launch. */
        public final boolean sawDocument;
        /** The receive law a seen-but-unusable document failed: a {@code match_doc_*} token + OUR field (never the
         *  bytes), else null. */
        public final String matchDocRefusal;
        //
        /** Ship id -> the server's display label for it (the ship row's optional occupantLabel,
         *  already past the seat-name law at decode); unmodifiable, empty when none. DISPLAY ONLY:
         *  never a path, a command or a launch byte — the launch path still carries the id. */
        public final Map<String, String> names;
        /**
         * Ship id -> the hull id the server named for it (the ship row's optional targetLabel: at most 24 printable
         * ASCII by the decode law, never cut — a longer hull id rides absent from the server); unmodifiable, empty
         * when none. WHY: a player searches and recognises ships by hull ("retri"), not only by name or id; an older
         * server sends none and the join lists read as before. DISPLAY + SEARCH only, like {@link #names}.
         *
         */
        public final Map<String, String> hulls;

        private Result(List<String> ids, int hidden, int totalAvailable, String defaultId,
                String rejectReason, String error, List<ModRow> mods, int modsAvailable,
                boolean modsComplete, boolean listRefused, boolean localRefusal,
                String matchDocText, boolean sawDocument, String matchDocRefusal,
                Map<String, String> names, Map<String, String> hulls) {
            this.names = Collections.unmodifiableMap(new LinkedHashMap<>(names));
            this.hulls = Collections.unmodifiableMap(new LinkedHashMap<>(hulls));
            this.listRefused = listRefused;
            this.localRefusal = localRefusal;
            this.matchDocText = matchDocText;
            this.sawDocument = sawDocument;
            this.matchDocRefusal = matchDocRefusal;
            this.ids = Collections.unmodifiableList(new ArrayList<>(ids));
            this.hidden = hidden;
            this.totalAvailable = totalAvailable;
            this.defaultId = defaultId;
            this.rejectReason = rejectReason;
            this.error = error;
            this.mods = Collections.unmodifiableList(new ArrayList<>(mods));
            this.modsAvailable = modsAvailable;
            this.modsComplete = modsComplete;
        }

        public static Result served(List<String> ids, int hidden, int totalAvailable) {
            // Legacy 3-arg overload: no server default named
            return served(ids, hidden, totalAvailable, null);
        }

        public static Result served(List<String> ids, int hidden, int totalAvailable,
                String defaultId) {
            return served(ids, hidden, totalAvailable, defaultId, List.of(), 0, true);
        }

        /** The served shape with the published mod set. */
        public static Result served(List<String> ids, int hidden, int totalAvailable,
                String defaultId, List<ModRow> mods, int modsAvailable, boolean modsComplete) {
            return served(ids, hidden, totalAvailable, defaultId, mods, modsAvailable, modsComplete,
                    Map.of(), Map.of());
        }

        //
        /** The served shape with the ship rows' display labels ({@link #names}) and hull ids ({@link #hulls}).
         * */
        public static Result served(List<String> ids, int hidden, int totalAvailable,
                String defaultId, List<ModRow> mods, int modsAvailable, boolean modsComplete,
                Map<String, String> names, Map<String, String> hulls) {
            return new Result(ids, hidden, totalAvailable, defaultId, null, null, mods,
                    modsAvailable, modsComplete, false, false, null, false, null, names, hulls);
        }

        public static Result rejected(String reason) {
            return new Result(List.of(), 0, 0, null, reason, null, List.of(), 0, true, false, false,
                    null, false, null, Map.of(), Map.of());
        }

        public static Result failed(String error) {
            return new Result(List.of(), 0, 0, null, null, error, List.of(), 0, true, false, false,
                    null, false, null, Map.of(), Map.of());
        }

        /** A {@link #failed} whose cause is the listing itself ({@link #listRefused}). */
        public static Result listRefused(String error) {
            return new Result(List.of(), 0, 0, null, null, error, List.of(), 0, true, true, false,
                    null, false, null, Map.of(), Map.of());
        }

        /** A {@link #failed} whose cause is this build's own un-sendable request ({@link #localRefusal}). */
        public static Result localRefusal(String error) {
            return new Result(List.of(), 0, 0, null, null, error, List.of(), 0, true, false, true,
                    null, false, null, Map.of(), Map.of());
        }

        /** This outcome with the fetch's document state attached (the collector's one call; every other field kept). */
        public Result withDocument(String text, boolean saw, String refusal) {
            return new Result(ids, hidden, totalAvailable, defaultId, rejectReason, error, mods,
                    modsAvailable, modsComplete, listRefused, localRefusal, text, saw, refusal, names, hulls);
        }

        public boolean served() {
            return rejectReason == null && error == null;
        }

        /** The one line under the picker. */
        public String statusLine() {
            if (rejectReason != null) {
                return "Ship list: " + rejectMessage(rejectReason);
            }
            if (error != null) {
                return "Ship list: " + error + " - you can still type a ship id.";
            }
            StringBuilder s = new StringBuilder("Ship list: ").append(ids.size())
                    .append(ids.size() == 1 ? " ship" : " ships").append(" from the server");
            int received = ids.size() + hidden;
            if (totalAvailable > received) {
                s.append(" (showing ").append(received).append(" of ").append(totalAvailable)
                        .append(" - type an id for another)");
            }
            if (hidden > 0) {
                s.append(" (").append(hidden).append(" not listed: unusual id)");
            }
            s.append('.');
            // The host-picked default, as its own trailing sentence
            if (defaultId != null) {
                s.append(" Server default: ").append(defaultId).append('.');
            }
            return s.toString();
        }
    }

    /**
     * Pure datagram→result accumulator. Feed every received datagram to {@link #offer}; it
     * returns true once the outcome is decided ({@code min(seqCount, MAX_FRAMES)} distinct listing
     * frames, or a decisive WELCOME event {@link #fetch13}'s flight hands over). Everything else is
     * ignored and counted — the silent-drop posture. A WELCOME-channel datagram offered here is
     * ignored and counted at EVERY version: the flight alone reads WELCOMEs ({@link #listen13} never
     * offers one), so a version-12 REJECT / ADMITTED / COOKIE decides nothing.
     *
     * <p>The match document: with a {@link DocumentOpener} set, a CTRL frame the opener authenticates is a chunk of the
     * server's sealed document — held per salt in ONE {@link MatchDocFrameCodec.Reassembler}, never deciding
     * {@link #done()}, never counted ignored; a frame the opener refuses falls to the listing decode exactly as before
     * (counted ignored once there when it is not a listing). Past the listing, while {@link #wantsHold()}, only a
     * document chunk is still taken; {@link #documentResult} turns the held set into the text on the {@link Result}.
     *
     */
    public static final class Collector {
        private final SocketAddress server;
        private final TreeMap<Integer, ClientListingRecord> frames = new TreeMap<>();
        /** The KIND_MODSET frames, keyed by their own seqIndex (the server
         * sends them BEFORE the ship frames; they never decide {@link #done()}). */
        private final TreeMap<Integer, ClientListingRecord> modFrames = new TreeMap<>();
        private int expected = -1;
        private String rejectReason;
        private String protocolError;
        private int ignored;
        private int cookieChallenges;
        private int budgetHeld;
        private int capHeld;
        /** The match document's chunks, every salt of this fetch (all tries); the first complete set wins. */
        private final MatchDocFrameCodec.Reassembler doc = new MatchDocFrameCodec.Reassembler();
        private DocumentOpener opener;
        private boolean sawDocument;
        private int docDropped;
        private int docChunksHeld;

        public Collector(SocketAddress server) {
            this.server = server;
        }

        /**
         * Opens one CTRL payload as a match-document chunk: the authenticated chunk, or null for anything that is not
         * one this fetch's live flight sealed. WHY a seam: the document is sealed under the exchange root, which only
         * the try's {@link Handshake13Flight} holds ({@link Handshake13Flight#openDocumentOrNull}) — this collector
         * never sees a root, and stays a pure byte accumulator its rows can drive with a fixed-root opener.
         *
         *
         */
        public interface DocumentOpener {
            MatchDocFrameCodec.Chunk openOrNull(byte[] payload);
        }

        /** The opener of the CURRENT try's flight; null = none, and every document frame then falls to the listing
         * decode and is counted ignored there, as a collector that knows no document. */
        public void opener(DocumentOpener o) {
            this.opener = o;
        }

        public boolean offer(SocketAddress from, byte[] data, int length) {
            if (done()) {
                // The listing decided, but a seen document is still
                // incomplete - its late chunks are taken until the receive loop's hold ends; nothing else is
                if (wantsHold()) {
                    offerLate(from, data, length);
                }
                return true;
            }
            if (from == null || !from.equals(server)) {
                ignored++;
                return false;
            }
            DatagramFrameCodec.Frame frame = DatagramFrameCodec.decode(data, length);
            if (frame == null) {
                ignored++;
                return false;
            }
            // a WELCOME (any version) is the flight's alone - never decisive here (the class doc)
            if (frame.channel() != DatagramFrameCodec.WireChannel.CTRL || frame.sessionId() != 0L) {
                ignored++;
                return false;
            }
            // A document chunk is held (or dropped, counted) and never
            // decides done - the listing does; a frame the opener refuses is judged by the listing decode below
            if (offerDocument(frame.payload())) {
                return done();
            }
            ClientListingRecord listing;
            try {
                listing = ClientListingReader.decodeFrame(frame.payload());
            } catch (IOException malformed) {
                ignored++;
                return false;
            }
            if (listing.seqIndex() != frame.seq()) {
                ignored++;
                return false;
            }
            if (listing.kind() == ClientListingReader.KIND_MODSET) {
                if (listing.seqIndex() < MAX_MODSET_FRAMES) {
                    modFrames.put(listing.seqIndex(), listing);
                } else {
                    ignored++;
                }
                return done();
            }
            if (listing.kind() != ClientListingReader.KIND_SHIP) {
                ignored++;
                return false;
            }
            frames.put(listing.seqIndex(), listing);
            expected = Math.min(listing.seqCount(), MAX_FRAMES);
            return done();
        }

        public boolean done() {
            return rejectReason != null || protocolError != null
                    || (expected > 0 && frames.size() >= expected);
        }

        /**
         * True while a document was seen and no set of it is complete: the receive loop then listens on past
         * {@link #done()} for the late chunks, bounded by {@link #holdDeadline}. False with no document seen, so a
         * fetch that carries none ends exactly where the listing decides it.
         *
         *
         */
        public boolean wantsHold() {
            return sawDocument && doc.textTotal() < 0; // -1 until a set completes; no copy of the held bytes
        }

        /** True when at least one authenticated document chunk arrived this fetch, held or dropped. */
        public boolean sawDocument() {
            return sawDocument;
        }

        /** Document chunks the reassembler refused (a disagreeing chunk, a salt past its cap, another salt once a
         * set is complete). */
        public int docDropped() {
            return docDropped;
        }

        /** Document chunk offers the reassembler held (an identical repeat counts again). */
        public int docChunksHeld() {
            return docChunksHeld;
        }

        /**
         * The fetch's document state on {@code r}: no document seen = {@code r} unchanged; a complete set = the text
         * after the exact-length inflate and a STRICT UTF-8 decode; else the refused law as {@code <match_doc_* token>
         * <our field>} with no text (an incomplete set, an inflate that broke the declared length, bytes that are not
         * UTF-8). A refusal never carries the document's bytes or a length, and never changes {@link Result#served()}:
         * the listing stays usable while its document is refused. The grammar and the value laws are the caller's.
         *
         *
         */
        public Result documentResult(Result r) {
            if (!sawDocument) {
                return r;
            }
            byte[] deflated = doc.deflatedOrNull();
            if (deflated == null) {
                return r.withDocument(null, true, MatchDocumentLaws.REASON_INCOMPLETE + " chunks");
            }
            byte[] bytes = MatchDocFrameCodec.inflateExact(deflated, doc.textTotal());
            if (bytes == null) {
                return r.withDocument(null, true, MatchDocumentLaws.REASON_OVERSIZE + " text");
            }
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException notUtf8) { // the refusal on the Result is its one record
                return r.withDocument(null, true, MatchDocumentLaws.REASON_MALFORMED + " utf8");
            }
            return r.withDocument(text, true, null);
        }

        /** {@code true} = the payload was a document chunk this fetch's opener authenticated (held or dropped, both
         * counted); {@code false} = not one (no opener, or the opener refused it).
         *
         * */
        private boolean offerDocument(byte[] payload) {
            MatchDocFrameCodec.Chunk c = opener == null ? null : opener.openOrNull(payload);
            if (c == null) {
                return false;
            }
            sawDocument = true;
            if (doc.offer(c)) {
                docChunksHeld++;
            } else {
                docDropped++;
            }
            return true;
        }

        /** A datagram past the listing while {@link #wantsHold()}: the same source / envelope / CTRL gate as
         * {@link #offer}, then a document chunk or nothing — every other datagram is counted ignored, and none can
         * change the decided outcome (a late WELCOME or listing frame decides nothing here).
         *
         * */
        private void offerLate(SocketAddress from, byte[] data, int length) {
            DatagramFrameCodec.Frame frame = from != null && from.equals(server)
                    ? DatagramFrameCodec.decode(data, length) : null;
            if (frame == null || frame.channel() != DatagramFrameCodec.WireChannel.CTRL || frame.sessionId() != 0L
                    || !offerDocument(frame.payload())) {
                ignored++;
            }
        }

        public int ignored() {
            return ignored;
        }

        /** {@link #fetch13}'s COOKIE13 challenges its flight answered with a sealed re-send. */
        public int cookieChallenges() {
            return cookieChallenges;
        }

        /** {@link #fetch13}'s flight {@code HELD}s — this build's own
         * join-safety budget refused to seal against the door's answer (the door DID answer; nothing was sealed). */
        public int budgetHeld() {
            return budgetHeld;
        }

        /** {@link #fetch13}'s flight {@code HELD}s at the flight's OWN seal cap (the door kept re-challenging one try and
         * the flight stopped sealing) — a different cause than {@link #budgetHeld()}: nothing refills, a fresh try is a
         * fresh flight. */
        public int capHeld() {
            return capHeld;
        }

        /**
         * The decisive WELCOME outcomes, ONE text each: {@link #fetch13}'s flight events (a {@code REJECTED} / an
         * {@code ADMITTED}) land here. A door REJECT keeps its
         * reason ({@code null} / empty = {@code (no reason)}); an admission is the protocol break (a probe never joins).
         * The three counters below are {@link #fetch13}'s only: a COOKIE13 its flight answered with a sealed re-send
         * (counted where {@link #cookieChallenges()} reads, so the "held" closing text still fires), a flight
         * {@code HELD} ({@link #budgetHeld()} / {@link #capHeld()}, its own closing text by cause) and a datagram it ignored (foreign, undecodable,
         * or a flight {@code IGNORE}).
         *
         *
         */
        private void noteReject(String reason) {
            rejectReason = reason == null || reason.isEmpty() ? "(no reason)" : reason;
        }

        private void noteAdmission() {
            protocolError = "the server answered the list request with an admission"
                    + " (protocol break - a probe never joins)";
        }

        private void noteCookieChallenge() {
            cookieChallenges++;
        }

        /** HELD by CAUSE: the flight's own seal cap, else the pair budget. */
        private void noteHeld(boolean atSealCap) {
            if (atSealCap) {
                capHeld++;
            } else {
                budgetHeld++;
            }
        }

        private void noteIgnored() {
            ignored++;
        }

        /** {@code noReplyError} = the failure text when nothing decisive arrived. A partial
         * listing (some frames lost) still serves what landed — the picker's text fallback
         * covers the rest and the status line says so via {@code totalAvailable}. */
        public Result result(String noReplyError) {
            if (rejectReason != null) {
                return Result.rejected(rejectReason);
            }
            if (protocolError != null) {
                return Result.failed(protocolError);
            }
            if (frames.isEmpty()) {
                return Result.failed(noReplyError);
            }
            List<String> ids = new ArrayList<>();
            int hidden = 0;
            // The FIRST offerable entry the server marked
            // MARK_SHIP_NEXT_RESPAWN is its host-picked default; nothing marked => null.
            String defaultId = null;
            // The ship rows' optional display labels, kept
            // only for offerable ids (decode already ran the seat-name law on each label).
            Map<String, String> names = new LinkedHashMap<>();
            // The ship rows' optional hull ids (targetLabel), kept only
            // for offerable ids like the names; absent = an older server or a hull id too long to ride, never a guess
            Map<String, String> hulls = new LinkedHashMap<>();
            for (ClientListingRecord frame : frames.values()) {
                for (ClientListingRecord.Entry entry : frame.entries()) {
                    if (offerable(entry.token())) {
                        ids.add(entry.token());
                        if (entry.occupantLabel() != null) {
                            names.put(entry.token(), entry.occupantLabel());
                        }
                        if (entry.targetLabel() != null) {
                            hulls.put(entry.token(), entry.targetLabel());
                        }
                        if (defaultId == null
                                && entry.markCode() == ClientListingReader.MARK_SHIP_NEXT_RESPAWN) {
                            defaultId = entry.token();
                        }
                    } else {
                        hidden++;
                    }
                }
            }
            // The published mod set — rows in server (ordinal id) order across the
            // frames that landed; complete only when every frame under the cap is here and the cap
            // cut nothing (totalAvailable == totalEntries == rows).
            List<ModRow> mods = new ArrayList<>();
            int modsAvailable = 0;
            boolean modsComplete = true;
            if (!modFrames.isEmpty()) {
                ClientListingRecord first = modFrames.firstEntry().getValue();
                modsAvailable = first.totalAvailable();
                int wanted = Math.min(first.seqCount(), MAX_MODSET_FRAMES);
                for (ClientListingRecord frame : modFrames.values()) {
                    for (ClientListingRecord.Entry entry : frame.entries()) {
                        // The honest serve's own mod-id law
                        // (ModSet.lawfulId == offerable, char for char; the decode law admits <>/=) -
                        // an unlawful id refuses the WHOLE listing, by row index, never echoed.
                        if (!offerable(entry.token())) {
                            return Result.listRefused("the server's mod list carries an unlawful mod id (mod row "
                                    + mods.size() + ") - list refused");
                        }
                        mods.add(new ModRow(entry.token(), entry.targetLabel(), entry.hullWireId(),
                                entry.markCode() == ClientListingReader.MARK_MODSET_OPTIONAL));
                    }
                }
                modsComplete = modFrames.size() >= wanted && mods.size() == first.totalEntries()
                        && first.totalEntries() == first.totalAvailable();
            }
            return Result.served(ids, hidden,
                    frames.firstEntry().getValue().totalAvailable(), defaultId, mods, modsAvailable,
                    modsComplete, names, hulls);
        }
    }

    /**
     * The socket half (since THE FLIP the ONLY one — the version-12
     * {@code fetch} and its readable-password probe HELLO are deleted): an ephemeral UDP socket, {@link #TRIES} tries,
     * {@link #RECEIVE_TIMEOUT_MILLIS} each, the {@link #COOKIED_RETRANSMIT_MILLIS} resend cadence, and every
     * HELLO is a {@link Handshake13Flight} on the PASSWORD lane with {@code FLAG_PROBE}: the match password is sealed,
     * never readable on the wire, and NO version-12 datagram leaves on any path — never a fallback (item 44: a sender
     * that hears silence does not retry at 12). Per call: the hs-13 warm FIRST on this thread (item 56; a broken
     * provider = {@link Result#localRefusal}, never a version-12 probe), ONE {@link SecureRandom}, then ONE flight per
     * try. The door's unit ({@code Cookie13}) is carried across tries on this socket — adopted before the next try's
     * {@code start}, replaced only by a non-null newer one (item 17). A flight-constructor refusal (a cid / fingerprint
     * the version-13 codec cannot carry) = {@link Result#localRefusal} naming the field, never its value, nothing sent
     * (both local refusals are DECISIVE for the caller's retry loop: the same inputs refuse the same way). Every
     * flight is {@link Handshake13Flight#abandon()}ed on every exit (item 68: a passing probe is answered by plaintext
     * CTRL listing frames, so nothing else ends it). Clock = {@code PredictReconcileDatagramSession.nowMillis()}
     * (item 31, monotonic). Blocking (≤ TRIES × RECEIVE_TIMEOUT_MILLIS) and never throws; callers run it OFF the frame
     * thread with no lock held (the flight's CALLER LAW: X25519 runs inside). No result text carries the password, the
     * cid or the fingerprint. Nothing decisive + a flight {@code HELD} = the HELD text BY CAUSE
     * ({@link #heldTextOrNull}: the pair budget declined to seal = "held its reply … wait ~30 s"; the flight's seal cap
     * = "kept re-challenging … retry", no wait named), ahead of the sealed-resend "held" text. Callers: {@code CcClientModPlugin}'s two probe sites and the join window ({@code JoinProbe.fetch}).
     * Each try's flight is the collector's {@link Collector.DocumentOpener} for that try only (set once the flight is
     * built, cleared before its abandon); every collector outcome returns through {@link Collector#documentResult}.
     *
     *
     */
    public static Result fetch13(String host, int port, String password, String clientId,
            String fingerprint) {
        InetSocketAddress server;
        try {
            server = new InetSocketAddress(host, port);
        } catch (IllegalArgumentException badPort) {
            return Result.failed("bad port " + port);
        }
        if (server.isUnresolved()) {
            return Result.failed("could not resolve " + host);
        }
        try {
            PredictDatagramDial.warmHs13Once();
        } catch (RuntimeException brokenProvider) { // the warm's IllegalStateException, or worse: never a v12 probe
            return Result.localRefusal("this build's handshake crypto is unavailable - list request not sent");
        }
        Collector collector = new Collector(server);
        SecureRandom random = new SecureRandom();
        byte[] secret = Handshake13.secretBytes(password);
        String print = fingerprint == null ? "" : fingerprint;
        String cid = clientId == null ? "" : clientId;
        try (DatagramSocket socket = new DatagramSocket()) {
            Handshake13PairBudget.Host budget =
                    Handshake13PairBudget.process().host(PredictDatagramDial.budgetHostKey(server));
            DatagramPacket packet = new DatagramPacket(new byte[RECEIVE_BUFFER_BYTES], RECEIVE_BUFFER_BYTES);
            Handshake13.Cookie13 held = null; // the fetch's heldCookie twin: public wire data, scoped to this socket
            for (int attempt = 0; attempt < TRIES && !collector.done(); attempt++) {
                Handshake13Flight flight;
                try {
                    // team 0: a probe never carries a team pick (the door ignores one on a probe anyway)
                    flight = new Handshake13Flight(Handshake13Flight.Lane.PASSWORD, secret,
                            Handshake13.FLAG_PROBE, print, cid, 0L, "", 0, budget, random);
                } catch (IllegalArgumentException refused) {
                    return Result.localRefusal("this build cannot send the list request: its "
                            + refusedField(refused) + " is not version-14 sendable (nothing sent)");
                }
                try {
                    // The document opens under THIS try's live roots only;
                    // the opener is cleared before the abandon zeroes them
                    collector.opener(flight::openDocumentOrNull);
                    if (held != null) {
                        flight.adoptCookie(held);
                    }
                    listen13(socket, server, packet, flight, collector);
                } finally {
                    collector.opener(null);
                    flight.abandon();
                    lastFetch13Flight = flight;
                    Handshake13.Cookie13 unit = flight.heldCookieOrNull();
                    if (unit != null) {
                        held = unit;
                    }
                }
            }
        } catch (IOException network) {
            return Result.failed("network error: " + network.getMessage());
        } catch (RuntimeException broken) {
            // the class name only: a crypto-path message is never echoed into a result text
            return Result.failed("network error: " + broken.getClass().getSimpleName());
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        // A flight HELD means the door answered and THIS build declined to
        // seal — never "server not up"; it wins over the sealed-resend text below. The text is picked BY CAUSE.
        // Every outcome the collector decides carries the fetch's
        // document state (a fetch that saw none is returned unchanged)
        String held = heldTextOrNull(collector.budgetHeld(), collector.capHeld(), host, port);
        if (held != null) {
            return collector.documentResult(collector.result(held));
        }
        if (collector.cookieChallenges() > 0) {
            return collector.documentResult(collector.result("the server at " + host + ":" + port
                    + " held: another join from this address, or several recent wrong passwords"
                    + " - wait ~10 s and retry"));
        }
        return collector.documentResult(collector.result("no reply from " + host + ":" + port
                + " (server not up yet, or wrong address/port)"));
    }

    /**
     * The HELD closing text BY CAUSE; {@code null} = no HELD. The pair budget refills, so its text says wait — and it
     * wins when both happened (a retry would be HELD again); the flight's seal cap is per flight, so a fresh try is not
     * held by it and the text names no wait. Pure; pinned by {@code DoorProbeHeldRows} P-HELD-CAUSE.
     */
    static String heldTextOrNull(int budgetHeld, int capHeld, String host, int port) {
        if (budgetHeld > 0) {
            return "the server at " + host + ":" + port + " answered, but this build held its reply"
                    + " (its join-safety budget for this server) - wait ~30 s and retry";
        }
        if (capHeld > 0) {
            return "the server at " + host + ":" + port + " kept re-challenging this build until it stopped answering"
                    + " (the network path to the server may be changing) - retry";
        }
        return null;
    }

    /**
     * One {@link #fetch13} try: {@code start} (sent when non-null), then a bounded listen. The source gate
     * and the frame decode run FIRST ({@link Collector#offer}'s rule), so the flight never sees a foreign or
     * undecodable datagram; a WELCOME-channel frame goes to the flight only — {@code SEND} = the sealed F2 sent at once
     * (a challenge, counted) and the in-try resend armed; {@code REJECTED} = the door's reject; {@code ADMITTED} = the
     * protocol break (nothing of {@code Admitted13} is read); {@code HELD} = counted by cause, {@link Collector#capHeld()}
     * when the flight's seal-cap counter moved, else {@link Collector#budgetHeld()} (nothing sealed), {@code IGNORE} = counted — and every other
     * frame goes to {@link Collector#offer} unchanged (the CTRL listing decides {@link Collector#done()}). The resend
     * ({@code retransmitOrNull()}, null = nothing) runs every {@link #COOKIED_RETRANSMIT_MILLIS} once the try holds a
     * sealed F2 — from its first {@code SEND}, or from {@code start} when an adopted unit sealed there; an
     * un-challenged F1 goes once per try. The loop runs while {@link #keepListening}: when the listing decides while a
     * seen match document is still incomplete, the try HOLDS — receiving only, no resend, every datagram to
     * {@link Collector#offer} (which takes only a document chunk then) — until {@link #holdDeadline} or a complete set.
     *
     *
     */
    private static void listen13(DatagramSocket socket, InetSocketAddress server, DatagramPacket packet,
            Handshake13Flight flight, Collector collector) throws IOException {
        byte[] first = flight.start(PredictReconcileDatagramSession.nowMillis());
        if (first != null) {
            socket.send(new DatagramPacket(first, first.length, server));
        }
        boolean resending = flight.seals() > 0;
        long cappedSeen = flight.sealCapped();
        long lastSendMillis = PredictReconcileDatagramSession.nowMillis();
        long deadline = lastSendMillis + RECEIVE_TIMEOUT_MILLIS;
        boolean holding = false; // a flag, never a clock sentinel: the monotonic clock may read negative
        long holdEnd = 0L;
        while (keepListening(collector)) {
            long now = PredictReconcileDatagramSession.nowMillis();
            if (!holding && collector.done()) {
                // The listing decided with the document incomplete
                holding = true;
                holdEnd = holdDeadline(now, deadline);
            }
            long leftMillis = (holding ? holdEnd : deadline) - now;
            if (leftMillis <= 0) {
                break;
            }
            long waitMillis = leftMillis;
            if (resending && !holding) {
                long sinceSendMillis = now - lastSendMillis;
                if (sinceSendMillis >= COOKIED_RETRANSMIT_MILLIS) {
                    byte[] resend = flight.retransmitOrNull();
                    if (resend != null) {
                        socket.send(new DatagramPacket(resend, resend.length, server));
                    }
                    lastSendMillis = PredictReconcileDatagramSession.nowMillis();
                    continue;
                }
                waitMillis = Math.min(leftMillis, COOKIED_RETRANSMIT_MILLIS - sinceSendMillis);
            }
            socket.setSoTimeout((int) Math.max(1L, waitMillis));
            packet.setLength(packet.getData().length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException idle) {
                if (resending && !holding) {
                    continue; // the resend slice elapsed - the loop re-checks the deadline
                }
                break; // silence, or the hold ran out
            }
            DatagramFrameCodec.Frame frame = server.equals(packet.getSocketAddress())
                    ? DatagramFrameCodec.decode(packet.getData(), packet.getLength()) : null;
            if (frame == null) {
                collector.noteIgnored();
                continue;
            }
            // in the hold a WELCOME is the collector's too: counted ignored, never the flight's (it decides nothing)
            if (holding || frame.channel() != DatagramFrameCodec.WireChannel.WELCOME) {
                collector.offer(packet.getSocketAddress(), packet.getData(), packet.getLength());
                continue;
            }
            Handshake13Flight.Event event = flight.onWelcome(frame.payload(),
                    PredictReconcileDatagramSession.nowMillis());
            Handshake13Flight.Event.Kind kind = event.kind();
            if (kind == Handshake13Flight.Event.Kind.SEND) {
                byte[] seal = event.datagram();
                collector.noteCookieChallenge();
                socket.send(new DatagramPacket(seal, seal.length, server));
                resending = true;
                lastSendMillis = PredictReconcileDatagramSession.nowMillis();
            } else if (kind == Handshake13Flight.Event.Kind.REJECTED) {
                collector.noteReject(event.rejected().reason());
            } else if (kind == Handshake13Flight.Event.Kind.ADMITTED) {
                collector.noteAdmission();
            } else if (kind == Handshake13Flight.Event.Kind.HELD) {
                // HELD, nothing sealed — by CAUSE: the flight's seal-cap counter moved, else the pair budget
                long capped = flight.sealCapped();
                collector.noteHeld(capped > cappedSeen);
                cappedSeen = capped;
            } else {
                collector.noteIgnored(); // IGNORE (not ours, forged, a sealed unit)
            }
        }
    }

    /**
     * The receive loop's condition: listen while nothing is decided, or while the decided fetch still holds for a seen,
     * incomplete match document. With no document seen this is exactly {@code !done()}, so such a fetch ends where
     * the listing decides it.
     *
     *
     */
    static boolean keepListening(Collector collector) {
        return !collector.done() || collector.wantsHold();
    }

    /**
     * When the hold past the listing ends: {@link #COOKIED_RETRANSMIT_MILLIS} after the moment the listing decided
     * ({@code doneAt}), never past the try's own {@code tryDeadline}. WHY that window: the door sends the document
     * BEFORE the listing, so a chunk still missing at the listing is a reordered or lost datagram, and a short hold
     * catches the reordered ones without delaying a fetch whose document is already whole. Both arguments are the
     * same monotonic millisecond clock (either sign); pure.
     *
     *
     */
    static long holdDeadline(long doneAt, long tryDeadline) {
        return Math.min(doneAt + COOKIED_RETRANSMIT_MILLIS, tryDeadline);
    }

    /** The flight constructor's message IS the refused field's name (its Failure law); only the two fields a caller of
     * {@link #fetch13} supplies pass through, anything else reads {@code argument} — a value never reaches the text. */
    private static String refusedField(IllegalArgumentException refused) {
        String field = refused.getMessage();
        return "print".equals(field) ? "fingerprint" : "cid".equals(field) ? "client id" : "argument";
    }
}
