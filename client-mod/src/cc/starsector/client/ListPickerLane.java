package cc.starsector.client;

import cc.starsector.client.net.ClientListingRecord;
import cc.starsector.client.net.ClientListingReader;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.LabelAPI;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The large-list picker — the GAME-TYPED half of the LSTD listing panel
 * (SS382 L4 Part B). Owns exactly what the pure
 * {@link ListPickerLaw} declines to: LSTD frame intake+assembly, all rendering (modal panel +
 * the knob-OFF feed fallback; the SS405 auto-open re-cut retired the prompt line), measured
 * geometry, and resolve-to-send. Everything
 * here runs on the RENDER thread ({@link #pump} off the advance relay, {@link #feedPickerInput}
 * off the input relay, {@link #render} off the UI-coords pass) — the only cross-thread touch is
 * the session's own synchronized {@code takeListings()} drain-take (the XFER/AIMP internal-route
 * idiom) and {@link ChatLane#queueCommandSend}'s synchronized deposit.
 *
 * <p><b>Assembly law (fail-closed):</b> frames arrive exactly-once in-order on CTRL, one
 * listing dripping at a time per seat, but a NEWER listingId supersedes mid-drip — so assembly
 * is per-listingId latest-wins: a higher id drops any partial older one; a cross-frame
 * disagreement (kind/seqCount/totals/seq order — per-frame laws already held in the decoder)
 * POISONS that whole listingId (never a partial render; the player re-asks). A complete listing
 * is offered to the law ({@code cc.predict.listPicker} ON) or rendered as client-capped feed
 * lines (OFF — the opt-out of "make way": the server keeps ONE reply shape either way).
 * No live combat UI at pump time ⇒ taken frames and any partial assembly are DROPPED (the
 * collision law — between-round targets go stale; the player re-asks).
 *
 * <p><b>Geometry (measured, the reviewer-promoted requirement; bank {@code pp-recon/j}):</b>
 * bottom HUD top edge y=220 (shipInfo 800x200 inBL(20,20));
 * vanilla feed rows stack DOWN from (25, screenH-25) at 30 px pitch.
 * The panel anchors RIGHT (x1 = screenW-25, width {@value #PANEL_WIDTH} — owner correction at the
 * proof flight: the LEFT column carries the server-HUD status stack and the panel sat on top
 * of it, so left-anchoring is dead; at 1280 the panel spans 855..1255, inside the right third and
 * clear of the center third at 853.3), vertically centered in the band between an 8-row defensive
 * feed estimate and the HUD band (20 px clearance both ends) — the band also clears the top-right
 * command-points HUD (top ~140 px) and the bottom-right weapon readout (inside the HUD band). Visible rows are computed ONCE from the
 * live screen height — {@code (screenH - 565) / 20} clamped [1,12] — so a short band means FEWER
 * ROWS, never center/HUD overlap: 11 rows at 1280x800 (the owner's measured resolution), 10 at
 * the 1280x768 floor.
 *
 * <p><b>Text law:</b> every rendered string is decode-validated (tokens/targetLabels printable
 * ASCII; occupantLabel passed the seat-name law + the widened C1/bidi rejection BOTH sides), so
 * there is no re-filter here — only a pure visual clamp at {@value #VISUAL_MAX_CHARS} chars
 * (the bubble-lane idiom). Logs are machine rows without player-authored text: occupant labels
 * are never logged, and the selection log is length-only.
 *
 * <p>Fail-soft: any Throwable in intake/render/input dead-latches the lane for the ROUND and
 * swaps in a FRESH law instance (never leave the keyboard captured by a dead modal — the
 * ChatLane force-close idiom); {@link #resetForMatchReset} re-arms and drops the round's
 * listing (the auto-close on round end).
 *
 * <p>Knob {@code cc.predict.listPicker} = {@code on|off}, compiled DEFAULT ON, invalid THROWS at
 * class load (the {@code cc.predict.chatBubbles} family shape) — a deliberate
 * player-facing toggle, NO expiry. {@code off} = no panel, no key grab ever; complete
 * listings render as client-capped feed lines + "+N more" (the pre-L3 presentation, client-side).
 */
final class ListPickerLane {

    static final boolean ON = parseOnOff(System.getProperty("cc.predict.listPicker", "on"));

    /** Panel width (px): 400 right-anchored puts the left edge at screenW-425 — at the 1280
     *  floor that is 855, past the center-third boundary 853.3 (bank {@code pp-recon/j}
     *  arithmetic, re-run for the right-anchor owner correction). */
    private static final int PANEL_WIDTH = 400;
    /** Bottom-HUD top edge (px, bottom-left origin) — measured. */
    private static final int HUD_TOP_Y = 220;
    /** Defensive live-feed row estimate for the band arithmetic (occupancy is not readable). */
    private static final int FEED_ROWS_ESTIMATE = 8;
    /** Visual clamp for one rendered row (display only; the wire strings stay untouched). */
    private static final int VISUAL_MAX_CHARS = 60;
    /** Knob-OFF fallback: client-authored visible feed cap (then "+N more" — the law). */
    private static final int FALLBACK_MAX_LINES = 15;
    private static final int ERROR_LOG_MAX = 8;

    private final PredictReconcileCombatPlugin plugin;

    // The pure law — lazy-built so visibleRows can come from the LIVE screen height (geometry
    // is the lane's concern by the law's ctor contract). Non-final: a lane failure swaps in a
    // fresh IDLE instance so the keyboard is never left captured (the chatInput idiom).
    private ListPickerLaw law;
    /** A Left/Right tap used to write the settings file synchronously
     *  on the render thread per tap. Now the latest value is HELD and written once — after
     *  {@link #SAVE_DEBOUNCE_NANOS} of no further change, when the panel is no longer open, or
     *  at the round reset. A clamped no-op step (already at a ladder end) changes nothing and
     *  neither applies nor writes. */
    private cc.starsector.client.voice.VoiceClientSettings pendingSave;
    private long pendingSaveNanos;
    private static final long SAVE_DEBOUNCE_NANOS = 750_000_000L;

    /** Whether the picker panel owns the keyboard — the voice
     * PTT relay's "no picker open" gate (render thread only, like the law). */
    boolean isPanelOpen() {
        ListPickerLaw l = law;
        return l != null && l.isOpen();
    }
    private int lawRows;
    private boolean pickerLaneDead;

    // Per-listingId latest-wins assembly (render thread only; class javadoc law).
    private int asmListingId;
    private int asmKind;
    private int asmSeqCount;
    private int asmTotalEntries;
    private int asmTotalAvailable;
    private int asmNextSeq;
    private boolean asmClosed; // poisoned or already delivered: drop this id's further frames
    private final ArrayList<ClientListingRecord.Entry> asmEntries = new ArrayList<>();
    /** The last bare moderation verb typed — {@code true} after a bare
     *  {@code /unban} (the next KIND_UNBAN listing opens at {@code lift}), {@code false} after a bare
     *  {@code /banned} (opens at the 1d rung). Render thread (the chat SENT intercept + assembly). */
    private boolean unbanMenuOpensAtLift;
    /** v2: token → {@code "<name>" (<Hull>)} for the CURRENT KIND_SHARE / KIND_BUILDS listing
     *  ({@link #resolveBuildRows}); render thread; cleared per such listing. */
    private final java.util.HashMap<String, String> buildLabels = new java.util.HashMap<>();
    /** Hull id → hull NAME for the latest /ship listing, resolved
     *  ONCE at its landing ({@link #onFrame} via {@link ListPickerLaw#resolveShipHullNames}); the row text only
     *  reads it (a map get per drawn row, no settings call, no allocation). Render thread. */
    private java.util.Map<String, String> shipHullNames = java.util.Map.of();
    /** Hull ids the game's own specs could not name since load (the row shows the id instead). */
    private int hullNameMisses;
    /** The /builds row token whose FLY NEXT cell reads YES (at most one — {@code current.data}
     *  holds one stem), and token → the local stem this round saved it as (a re-YES never re-saves). Render thread. */
    private String flyYesToken;
    private final java.util.HashMap<String, String> flyStems = new java.util.HashMap<>();

    private int listingsAssembled;  // picker listing_assembled — complete listings delivered
    private int listingsDropped;    // fail-closed whole-listing drops (cross-frame violation)
    private int staleFramesDropped; // frames of a superseded/closed listingId
    private int uiNullDropped;      // frames discarded by the no-live-combat-UI law
    private int selectionsSent;     // picker_selected — commands deposited on the chat lane
    private int laneErrors;         // capped error log (dead-latch or fallback addMessage)
    /** The last {@code session.reAdmits()} seen by
     *  {@link #pump} ({@code -1} = never); lane-lifetime, NOT re-armed per round. */
    private long seenReAdmits = -1L;
    /** picker_readmit_resets — watermark resets on a
     *  re-admit (printed on the reset line itself; each reset is one line, bounded by re-admits). */
    private int pickerReAdmitResets;

    // Render furniture (render thread only; lazy-built, dies with the round).
    private LabelAPI headerLabel;
    private LabelAPI[] rowLabels;
    private String[] rowShown; // per label: the last drawn text + value key
    private String headerShown; // the header's last drawn text (re-text + re-highlight on change only)
    /** The Left/Right hint cell — chevrons, the picker font has no arrow glyphs. */
    static final String LEFT_RIGHT_HINT = "< >";
    private float rowTextH;
    private Color headerColor;
    private Color rowColor;
    private Color valueColor;

    ListPickerLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Per-frame housekeeping off the advance relay: drain-take queued LSTD frames from the live
     * session, assemble per-listingId, deliver complete listings (law offer or feed fallback),
     * then tick the law's park/auto-open/idle machinery. No live combat UI ⇒ every taken frame and
     * any partial assembly are dropped (the collision law). Runs dead too — the take keeps the
     * session queue drained either way (bounded regardless; this just keeps counters honest).
     */
    void pump() {
        flushPendingSettingsSave(false);
        cc.starsector.client.net.PredictSession session = plugin.session;
        if (session == null) {
            return;
        }
        List<ClientListingRecord> frames = session.takeListings();
        if (frames == null) {
            // Proxy-stubbed selftest sessions null EVERY method, interface defaults included
            // (FoePoseSeenLatchSmoke drives the real advance()); a null take is an empty take.
            frames = List.of();
        }
        if (pickerLaneDead) {
            if (!frames.isEmpty()) {
                staleFramesDropped += frames.size();
            }
            return;
        }
        try {
            // Read AFTER the take — a frame the successor session minted
            // was enqueued after its reAdmits++, so this read already sees the bump for it.
            latchReAdmitWatermark(session.reAdmits());
            CombatEngineAPI engine = plugin.engine;
            CombatUIAPI ui = engine != null ? engine.getCombatUI() : null;
            if (ui == null) {
                if (!frames.isEmpty() || asmListingId != 0) {
                    uiNullDropped += frames.size();
                    clearAssembly();
                }
                if (law != null) {
                    law.dropForRound(); // Between rounds the listing dies with its targets
                }
                return;
            }
            ensureLaw();
            long now = System.currentTimeMillis();
            for (ClientListingRecord frame : frames) {
                onFrame(frame, now);
            }
            law.tick(plugin.chat.isBoxOpen(), now);
        } catch (Throwable t) {
            killLane("pump", t);
        }
    }

    /**
     * Feed KEYBOARD events to the {@link ListPickerLaw} — called from the input relay directly
     * BEFORE {@link ChatLane#feedChatInput} (MODAL-FIRST: an OPEN panel
     * consumes selection Enter before chat's closed-box open-on-RETURN can double-act on it;
     * an open box has the picker PARKED = all-PASS by law, so chat still owns its keyboard —
     * the order IS load-bearing, the pre-fix "belt only" claim was the defect). The event-loop
     * shape is the ChatLane
     * contract verbatim: skip consumed/non-keyboard, read all four values BEFORE any consume,
     * consume on every verdict except PASS. A SELECTED verdict deposits the law's command
     * string on the chat lane's render→net send queue (the unchanged CHTU path). While the
     * panel is open, engine-POLLED ship verbs are additionally blocked per frame — the
     * chat-open key-block generalized to chat-open OR picker-open.
     */
    void feedPickerInput(List<InputEventAPI> events) {
        if (!ON || pickerLaneDead || law == null || law.mode() == ListPickerLaw.Mode.IDLE) {
            return;
        }
        try {
            for (InputEventAPI event : events) {
                if (event.isConsumed() || !event.isKeyboardEvent()) {
                    continue;
                }
                // Read BEFORE consume: the engine's consumed-event guard throws on post-consume
                // type/value access (the ChatLane law).
                boolean keyDown = event.isKeyDownEvent();
                boolean isRepeat = event.isRepeat();
                int keyCode = event.getEventValue();
                char eventChar = event.getEventChar();
                ListPickerLaw.Result verdict = law.onKeyboardEvent(
                        keyDown, isRepeat, keyCode, eventChar, System.currentTimeMillis());
                if (verdict == ListPickerLaw.Result.PASS) {
                    continue;
                }
                event.consume();
                if (verdict == ListPickerLaw.Result.SELECTED) {
                    String command = law.selectedCommand();
                    // selectedKind(), never kind(): SELECTED has already dropped the listing
                    // (kind() == 0 here) — an earlier build sent every /settings pick as chat.
                    if (law.selectedKind() == ListPickerLaw.KIND_LOCAL_SETTINGS) {
                        applyLocalSettingsPick(command); // L5: never leaves the client
                        continue;
                    }
                    if (law.selectedKind() == ClientListingReader.KIND_BUILDS) {
                        if (command.startsWith("flynext ")) {
                            applyFlyNextToggle(command.substring("flynext ".length())); // In place, never sent
                        } else {
                            applyLocalBuildSave(command); // a /builds pick saves LOCALLY
                        }
                        continue;
                    }
                    plugin.chat.queueCommandSend(command);
                    selectionsSent++;
                    // selectedKind (kind() is 0 post-drop) + the
                    // row index + the term rung (a ladder constant). Never the row LABEL: a /kick /ban
                    // /unban /mute row names a seat — player text stays out of the log.
                    PilotWatchLog.info("picker selected #" + selectionsSent
                            + " kind=" + law.selectedKind() + " row=" + law.selectedRow()
                            + " rung=" + (law.selectedRung() == null ? "-" : law.selectedRung())
                            + " (" + command.length() + " chars)");
                }
            }
            if (law.isOpen()) {
                plugin.blockShipKeyCommandsWhileTyping();
            }
        } catch (Throwable t) {
            killLane("input", t);
        }
    }

    /** The renderInUICoords relay, LAST in the pass — the modal draws over every HUD lane.
     *  OPEN draws the panel; other modes draw nothing (SS405 retired the prompt line). */
    void render() {
        if (!ON || pickerLaneDead || law == null) {
            return;
        }
        try {
            if (law.mode() == ListPickerLaw.Mode.OPEN) {
                drawPanel();
            }
        } catch (Throwable t) {
            killLane("draw", t);
        }
    }

    /** Per-ROUND re-arm at the match-reset rebuild: the listing dies with the round, the
     *  dead-latch re-arms, labels rebuild lazily (fresh combat = fresh furniture). */
    void resetForMatchReset() {
        flushPendingSettingsSave(true);
        if (law != null) {
            law.dropForRound();
        }
        clearAssembly();
        pickerLaneDead = false;
        headerLabel = null;
        rowLabels = null;
        flyYesToken = null; // Every FLY NEXT cell starts NO in a fresh round
        flyStems.clear();
    }

    /**
     *
     * The re-admit watermark (the {@code ChatLane.declareAutoShareAfterAdmit} reAdmits idiom): the
     * server's successor PeerSession restarts its LSTD listingId mint at 0 ("NOT copied on
     * takeover", {@code PilotIntentDatagramServer} PeerSession.lastListingId), so after a same-life
     * B4 re-admit every fresh listing sat at or below the old {@link #asmListingId} and was dropped
     * as stale — /ship, /transfer, /builds showed no panel for the rest of the round. When the
     * count MOVES: drop the assembly + the law's listing and log once per move. First sight
     * latches silently. Render thread.
     */
    private void latchReAdmitWatermark(long reAdmits) {
        long seen = seenReAdmits;
        seenReAdmits = reAdmits;
        if (!reAdmitMoved(seen, reAdmits)) {
            return;
        }
        int oldId = asmListingId;
        clearAssembly();
        if (law != null) {
            law.dropForRound();
        }
        pickerReAdmitResets++;
        PilotWatchLog.info("list-picker re-admit watermark reset from=" + oldId
                + " reAdmits=" + seen + "->" + reAdmits
                + " picker_readmit_resets=" + pickerReAdmitResets
                + " (listing_assembled=" + listingsAssembled
                + " stale_frames_dropped=" + staleFramesDropped + ")");
    }

    /**
     *
     * The pure watermark law: {@code true} iff a latched count exists ({@code seen >= 0}) and
     * {@code now} differs from it — an advance is a re-admit; a drop can only be a different
     * session object, whose server mint also restarted, so it resets too. {@code seen < 0} =
     * first sight (latch silently). ListPickerLawSelftest rows RA1-RA4.
     */
    static boolean reAdmitMoved(long seen, long now) {
        return seen >= 0L && now != seen;
    }

    // ---------------------------------------------------------------- assembly

    private void onFrame(ClientListingRecord frame, long now) {
        if (frame.listingId() < asmListingId) {
            staleFramesDropped++; // a superseded listing's undripped remainder (or reorder noise)
            return;
        }
        if (frame.listingId() > asmListingId) {
            if (asmListingId != 0 && !asmClosed && asmNextSeq > 0) {
                listingsDropped++; // latest-wins: the newer mint kills the partial older listing
            }
            asmListingId = frame.listingId();
            asmKind = frame.kind();
            asmSeqCount = frame.seqCount();
            asmTotalEntries = frame.totalEntries();
            asmTotalAvailable = frame.totalAvailable();
            asmNextSeq = 0;
            asmClosed = false;
            asmEntries.clear();
        } else if (asmClosed) {
            staleFramesDropped++; // duplicate/late frame of a poisoned or delivered listing
            return;
        }
        if (frame.kind() != asmKind || frame.seqCount() != asmSeqCount
                || frame.totalEntries() != asmTotalEntries
                || frame.totalAvailable() != asmTotalAvailable
                || frame.seqIndex() != asmNextSeq) {
            poisonListing("cross-frame mismatch id=" + asmListingId
                    + " seq=" + frame.seqIndex() + "/" + asmNextSeq);
            return;
        }
        asmEntries.addAll(frame.entries());
        asmNextSeq++;
        if (asmNextSeq < asmSeqCount) {
            return;
        }
        if (asmEntries.size() != asmTotalEntries) {
            poisonListing("entry total mismatch id=" + asmListingId
                    + " got=" + asmEntries.size() + " want=" + asmTotalEntries);
            return;
        }
        List<ClientListingRecord.Entry> entries = List.copyOf(asmEntries);
        asmClosed = true;
        asmEntries.clear();
        listingsAssembled++;
        PilotWatchLog.info("picker listing_assembled #" + listingsAssembled + " kind=" + asmKind
                + " id=" + asmListingId + " entries=" + entries.size()
                + " available=" + asmTotalAvailable);
        if (asmKind == ClientListingReader.KIND_SHARE || asmKind == ClientListingReader.KIND_BUILDS) {
            entries = resolveBuildRows(asmKind, entries);
            if (entries.isEmpty()) {
                return; // every id lacked a resident body — nothing to show (logged in resolveBuildRows)
            }
        }
        if (asmKind == ClientListingReader.KIND_SHIP) {
            // the hull names resolve HERE, once per landed listing — never in the per-frame row text
            shipHullNames = ListPickerLaw.resolveShipHullNames(asmKind, entries, this::hullNameOrId);
        }
        if (ON) {
            if (asmKind == ClientListingReader.KIND_UNBAN && unbanMenuOpensAtLift) {
                entries = ListPickerLaw.openedAtLift(asmKind, entries); // A bare /unban opens at lift
            }
            law.offerListing(asmKind, entries, asmTotalAvailable, plugin.chat.isBoxOpen(), now,
                    asmKind == ClientListingReader.KIND_SHIP ? shipHullNames : java.util.Map.of());
        } else {
            pushFallbackFeedLines(asmKind, entries, asmTotalAvailable);
        }
    }

    /**
     * Peer-build sharing v2: a KIND_SHARE / KIND_BUILDS row
     * carries the canonical id as its token ONLY — the display name + hull resolve HERE from
     * the resident VDEF ({@code PeerBuilds.rowLabel}: folded + registered + hull-bearing, the
     * author's name sanitized DATA); an id with no resident body is SKIPPED fail-soft (logged,
     * never a throw — the wire row was lawful). A KIND_BUILDS listing is the server's shared
     * set of record: EVERY id it named (resident or not) refreshes the editor {@code peers}
     * gate ({@code PeerBuilds.noteSharedListing}). Render thread (the assembly's own).
     */
    private List<ClientListingRecord.Entry> resolveBuildRows(int kind,
            List<ClientListingRecord.Entry> entries) {
        buildLabels.clear();
        List<ClientListingRecord.Entry> kept = new ArrayList<>(entries.size());
        int skipped = 0;
        if (kind == ClientListingReader.KIND_BUILDS) {
            List<String> ids = new ArrayList<>(entries.size());
            for (ClientListingRecord.Entry e : entries) {
                ids.add(e.token());
            }
            cc.starsector.client.editor.PeerBuilds.noteSharedListing(ids);
            // A YES survives a re-list only while its stem is still the next-connect build (the editor's
            // FLY / Delete may have moved current.data since)
            if (flyYesToken != null
                    && !flyStems.getOrDefault(flyYesToken, "").equals(CcClientModPlugin.currentBuild())) {
                flyYesToken = null;
            }
        }
        for (ClientListingRecord.Entry e : entries) {
            String label = cc.starsector.client.editor.PeerBuilds.rowLabel(e.token());
            if (label == null) {
                skipped++;
                continue;
            }
            buildLabels.put(e.token(), label);
            kept.add(e);
        }
        if (skipped > 0) {
            PilotWatchLog.info("picker builds: kind=" + kind + " skipped " + skipped + "/" + entries.size()
                    + " row(s) with no resident body (kept " + kept.size() + ")");
        }
        return kept;
    }

    /**
     * A SELECTED verdict on a KIND_BUILDS listing — {@code savebuild <id>} (the law's
     * local grammar) → {@code PeerBuilds.saveFromRegistry(id, null)} under the per-round
     * budget; the outcome line lands in the chat log as a {@code customs} line, never a send.
     */
    private void applyLocalBuildSave(String command) {
        String id = command.startsWith("savebuild ") ? command.substring("savebuild ".length()).trim() : "";
        String line;
        if (id.isEmpty()) {
            return; // not our grammar (unreachable: the law minted it)
        } else if (!plugin.chat.peerSaveAllowed()) {
            line = PeerBuildVerbLaw.LIMIT_REACHED;
        } else {
            cc.starsector.client.editor.PeerBuilds.Result res =
                    cc.starsector.client.editor.PeerBuilds.saveFromRegistry(id, null);
            if (res.saved) {
                plugin.chat.notePeerSaved();
            }
            line = res.message;
        }
        try {
            plugin.chat.enqueueChatMessage(new cc.starsector.client.net.ClientChatMessage(
                    0, VariantReceiptLane.AUTHOR, line));
        } catch (RuntimeException never) {
            PilotWatchLog.error("picker builds: save outcome line refused by the chat law", never);
        }
    }

    /**
     * Left/Right on a /builds row flips its FLY NEXT cell. YES = that build is
     * the ship at the next connect: saved locally first unless this round already saved it (the Enter-save
     * budget, the -2 suffix law), then {@code current.data} names the stem — a new YES retires the old one.
     * NO = the stem stops being current. The outcome is a {@code customs} line, never a send.
     */
    private void applyFlyNextToggle(String token) {
        String stem = flyStems.get(token);
        String line;
        if (token.equals(flyYesToken)) {
            CcClientModPlugin.clearCurrentBuildIf(stem);
            flyYesToken = null;
            line = "fly next: NO - local build " + stem + " is no longer your next ship";
        } else if (stem == null && !plugin.chat.peerSaveAllowed()) {
            line = PeerBuildVerbLaw.LIMIT_REACHED;
        } else {
            line = null;
            if (stem == null) {
                cc.starsector.client.editor.PeerBuilds.Result res =
                        cc.starsector.client.editor.PeerBuilds.saveFromRegistry(token, null);
                if (res.saved) {
                    plugin.chat.notePeerSaved();
                    stem = res.stem;
                    flyStems.put(token, stem);
                } else {
                    line = res.message;
                }
            }
            if (stem != null) {
                if (CcClientModPlugin.setCurrentBuild(stem)) {
                    flyYesToken = token;
                    line = "fly next: YES - local build " + stem + " is your ship at the next connect";
                } else {
                    line = "fly next: not set - local build " + stem + " could not be made current";
                }
            }
        }
        try {
            plugin.chat.enqueueChatMessage(new cc.starsector.client.net.ClientChatMessage(
                    0, VariantReceiptLane.AUTHOR, line));
        } catch (RuntimeException never) {
            PilotWatchLog.error("picker builds: fly-next outcome line refused by the chat law", never);
        }
    }

    /** Fail-closed whole-listing drop: the id stops accepting frames, nothing renders. */
    private void poisonListing(String reason) {
        asmClosed = true;
        asmEntries.clear();
        listingsDropped++;
        laneErrors++;
        if (laneErrors <= ERROR_LOG_MAX) {
            PilotWatchLog.error("picker listing_dropped (fail-closed, drops=" + listingsDropped
                    + "): " + reason, null);
        }
    }

    private void clearAssembly() {
        asmListingId = 0;
        asmNextSeq = 0;
        asmClosed = false;
        asmEntries.clear();
    }

    // ---------------------------------------------------------------- knob-OFF fallback

    /**
     * The knob-OFF path: render the SAME assembled LSTD as plain vanilla-feed lines, capped
     * client-side + "+N more" — the pre-L3 presentation re-created locally by choice (the server
     * keeps ONE reply shape). Per-message fail-soft with the capped error log (the
     * drainChatMessages idiom); segments strictly alternate Color/String (the engine's feed THROWS
     * otherwise).
     */
    private void pushFallbackFeedLines(int kind, List<ClientListingRecord.Entry> entries,
            int totalAvailable) {
        CombatEngineAPI engine = plugin.engine;
        CombatUIAPI ui = engine != null ? engine.getCombatUI() : null;
        if (ui == null) {
            return; // pump gated on a live UI; a same-frame teardown just drops the listing
        }
        ensureColors();
        int shown = Math.min(entries.size(), FALLBACK_MAX_LINES);
        for (int i = 0; i < shown; i++) {
            try {
                ui.addMessage(0, headerColor, "server: ", rowColor,
                        rowTextFor(kind, entries.get(i)).text);
            } catch (Throwable t) {
                laneErrors++;
                if (laneErrors <= ERROR_LOG_MAX) {
                    PilotWatchLog.error("picker fallback: addMessage FAILED (line dropped,"
                            + " lane lives; errors=" + laneErrors + "): " + t, t);
                }
            }
        }
        int more = totalAvailable - shown;
        if (more > 0) {
            try {
                ui.addMessage(0, headerColor, "server: ", rowColor, "+" + more + " more");
            } catch (Throwable ignore) {
                // the capped log above covers the shape; a lost tail line is cosmetic
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    /** The modal panel at the measured band geometry (class javadoc): backing + thin border +
     *  cursor highlight in one quad batch, then the header and row labels. */
    private void drawPanel() {
        com.fs.starfarer.api.SettingsAPI settings = com.fs.starfarer.api.Global.getSettings();
        ensureColors();
        ensureLabels(settings);
        int rows = lawRows;
        float screenH = settings.getScreenHeight();
        float panelH = 20f * rows + 40f;
        float bandBottom = HUD_TOP_Y + 20f;
        float bandTop = screenH - 25f - 30f * FEED_ROWS_ESTIMATE - 20f;
        float slack = bandTop - bandBottom - panelH;
        float y0 = bandBottom + Math.max(0f, slack / 2f);
        float yTop = y0 + panelH;
        float x1 = settings.getScreenWidth() - 25f;
        float x0 = x1 - PANEL_WIDTH;

        List<ClientListingRecord.Entry> window = law.visibleWindow();
        int first = law.firstVisibleIndex();
        int cursorSlot = law.cursorIndex() - first;

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(0f, 0f, 0f, 0.55f); // backing (the bubble recipe)
        GL11.glVertex2f(x0 - 3f, y0);
        GL11.glVertex2f(x1 + 3f, y0);
        GL11.glVertex2f(x1 + 3f, yTop);
        GL11.glVertex2f(x0 - 3f, yTop);
        GL11.glColor4f(1f, 1f, 1f, 0.25f); // thin border quads (the hull-bar prim)
        borderQuads(x0 - 3f, y0, x1 + 3f, yTop);
        if (cursorSlot >= 0 && cursorSlot < window.size()) {
            float hy = rowY(yTop, cursorSlot);
            GL11.glColor4f(1f, 1f, 1f, 0.18f); // cursor-row highlight
            GL11.glVertex2f(x0 - 1f, hy - 2f);
            GL11.glVertex2f(x1 + 1f, hy - 2f);
            GL11.glVertex2f(x1 + 1f, hy + rowTextH + 2f);
            GL11.glVertex2f(x0 - 1f, hy + rowTextH + 2f);
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f); // the font renderer binds its own glyph texture

        int filteredCount = law.filteredCount();
        int last = filteredCount == 0 ? 0 : Math.min(first + rows, filteredCount);
        StringBuilder header = new StringBuilder(96);
        header.append(kindName(law.kind())).append(' ')
                .append(filteredCount == 0 ? 0 : first + 1).append('-').append(last)
                .append(" of ").append(filteredCount);
        int more = law.totalAvailable() - law.totalEntries();
        if (more > 0) {
            header.append(" (+").append(more).append(" more)");
        }
        // Left/Right hint = a cyan "< >" cell right after the count (the
        // "(left/right: officer, enter: spawn)" tail truncated to "(left/ri..." — a left and right arrow in
        // cyan is the brevity hint). The picker font
        // (victor10.fnt) is Latin-1 only — no U+2190/2192 arrow glyphs, the « » cells are 1-px
        // blanks — so the chevrons ARE the arrows. Placed before the filter hint so a long header
        // truncates the filter words, never the arrows. Same cell on /switchteams.
        String hint = null;
        if (law.kind() == ClientListingReader.KIND_SWITCHTEAMS
                || law.kind() == ClientListingReader.KIND_SPAWNFOE
                || law.kind() == ClientListingReader.KIND_SPAWNFRIEND
                || law.kind() == ClientListingReader.KIND_BAN        // The term ladders
                || law.kind() == ClientListingReader.KIND_UNBAN) {
            hint = LEFT_RIGHT_HINT;
            header.append("  ").append(hint);
        }
        String filter = law.filterText();
        if (filter.isEmpty()) {
            header.append("  (type to filter)");
        } else {
            header.append("  filter: ").append(filter).append('_');
        }
        // SIZE BEFORE TEXT (a
        // value range once lost its cyan on first open): the engine label re-wraps to its CURRENT width on every setText
        // (the engine's label renderer — width 0 ⇒ a '\n' inserted per glyph)
        // and highlightFirst/Last index the WRAPPED text, so a fresh 0-size label's
        // value range lands on the wrong glyphs once the real width re-wraps it. Every label is
        // sized at creation (ensureLabels) AND before each setText here.
        headerLabel.getPosition().setSize(PANEL_WIDTH - 6f, rowTextH);
        headerLabel.getPosition().setLocation(x0 + 6f, yTop - 9f - rowTextH);
        String headerText = clampVisual(header.toString());
        if (!headerText.equals(headerShown)) {
            headerShown = headerText;
            headerLabel.setText(headerText);
            if (hint != null && headerText.contains(hint)) {
                headerLabel.setHighlightColor(valueColor);
                headerLabel.highlightFirst(hint);
            } else {
                headerLabel.setHighlight();
            }
        }
        headerLabel.render(1f);

        for (int i = 0; i < window.size() && i < rowLabels.length; i++) {
            LabelAPI label = rowLabels[i];
            RowText rt = rowTextFor(law.kind(), window.get(i));
            String shown = clampVisual(rt.text);
            String key = rt.lead + "|" + rt.value + "|" + shown;
            label.getPosition().setSize(PANEL_WIDTH - 12f, rowTextH); // before setText (see the header note)
            label.getPosition().setLocation(x0 + 9f, rowY(yTop, i));
            if (!key.equals(rowShown[i])) {
                // re-text + re-highlight only on change — every highlight call invalidates the
                // label's GL list. The engine label keeps ONE range (highlightFirst/Last replace it, an empty
                // setHighlight() clears it), so a recycled label never shows a stale value.
                rowShown[i] = key;
                label.setText(shown);
                if (rt.value != null && shown.contains(rt.value)) {
                    label.setHighlightColor(valueColor);
                    if (rt.lead) {
                        label.highlightFirst(rt.value);
                    } else {
                        label.highlightLast(rt.value);
                    }
                } else {
                    label.setHighlight();
                }
            }
            label.render(1f);
        }
    }

    /** Row i's label-bottom y inside the panel (top pad 9 + header band 22, then 20 px pitch). */
    private float rowY(float yTop, int i) {
        return yTop - 9f - 22f - (i * 20f) - rowTextH - 2f;
    }

    /** Four 1-px border quads for the already-open GL_QUADS batch. */
    private static void borderQuads(float x0, float y0, float x1, float y1) {
        GL11.glVertex2f(x0, y0);
        GL11.glVertex2f(x1, y0);
        GL11.glVertex2f(x1, y0 + 1f);
        GL11.glVertex2f(x0, y0 + 1f);
        GL11.glVertex2f(x0, y1 - 1f);
        GL11.glVertex2f(x1, y1 - 1f);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x0, y1);
        GL11.glVertex2f(x0, y0);
        GL11.glVertex2f(x0 + 1f, y0);
        GL11.glVertex2f(x0 + 1f, y1);
        GL11.glVertex2f(x0, y1);
        GL11.glVertex2f(x1 - 1f, y0);
        GL11.glVertex2f(x1, y0);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x1 - 1f, y1);
    }

    // ---------------------------------------------------------------- /settings (L5, local)

    /** The chat lane's SENT intercept reports every line sent; a bare
     *  {@code /unban} or {@code /banned} (either case, trimmed) sets which rung the next KIND_UNBAN
     *  listing opens at — both verbs are the same UNBAN_LIST server-side. Any other line: no change. */
    void noteSentLine(String sent) {
        Boolean opener = ListPickerLaw.unbanOpenerFor(sent); // the rule is the law's (row B9)
        if (opener != null) {
            unbanMenuOpensAtLift = opener;
        }
    }

    /**
     * W12 VOICE L5: {@code /settings} typed in chat — open the
     * CLIENT-LOCAL voice settings menu as a picker listing (kind
     * {@link ListPickerLaw#KIND_LOCAL_SETTINGS}, never on the wire). Render thread, off the
     * chat lane's SENT intercept; needs a live combat UI like {@link #pump} (no UI ⇒ no-op —
     * the line was already swallowed). Fail-soft like the rest of the lane.
     */
    void openSettings() {
        if (!ON || pickerLaneDead) {
            return;
        }
        try {
            CombatEngineAPI engine = plugin.engine;
            CombatUIAPI ui = engine != null ? engine.getCombatUI() : null;
            if (ui == null) {
                return;
            }
            ensureLaw();
            List<ClientListingRecord.Entry> rows = cc.starsector.client.voice.VoiceSettingsMenu
                    .root(currentVoiceSettings(), currentVoiceSheet());
            law.offerListing(ListPickerLaw.KIND_LOCAL_SETTINGS, rows, rows.size(),
                    plugin.chat.isBoxOpen(), System.currentTimeMillis());
            PilotWatchLog.info("picker: /settings opened (" + rows.size() + " rows)");
        } catch (Throwable t) {
            killLane("settings", t);
        }
    }

    /**
     * A captured {@code /help} reply ({@link HelpMenuLaw}, off the chat
     * lane's drain) opened as the client-local {@link ListPickerLaw#KIND_LOCAL_HELP} menu: each line wrapped at
     * {@link #VISUAL_MAX_CHARS} ({@link HelpMenuLaw#wrap}) so the row clamp never cuts one, one row per wrapped
     * piece through the SAME row path as every listing (token = the row text, the {@code VoiceSettingsMenu} row
     * shape; {@code rowText} + {@code clampVisual}). The rows are WIRE text already under the chat law — never
     * logged (counts only), never parsed, never a command (Enter is inert on this kind). Latest-wins like
     * {@link #openSettings}: a listing already up is replaced (the player just asked for help). Render thread.
     *
     * @return true when the panel took the rows; false (knob off, lane dead, no combat UI, nothing to show, or a
     *         failure, which dead-latches the lane like every other entry) = the caller puts the lines in the feed
     */
    boolean openHelp(List<String> lines) {
        if (!ON || pickerLaneDead || lines == null || lines.isEmpty()) {
            return false;
        }
        try {
            CombatEngineAPI engine = plugin.engine;
            CombatUIAPI ui = engine != null ? engine.getCombatUI() : null;
            if (ui == null) {
                return false;
            }
            List<String> rows = HelpMenuLaw.wrapAll(lines, VISUAL_MAX_CHARS);
            if (rows.isEmpty()) {
                return false;
            }
            List<ClientListingRecord.Entry> entries = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                entries.add(new ClientListingRecord.Entry(rows.get(i), ClientListingReader.MARK_SHIP_PLAIN, false,
                        null, null, i));
            }
            ensureLaw();
            law.offerListing(ListPickerLaw.KIND_LOCAL_HELP, entries, entries.size(),
                    plugin.chat.isBoxOpen(), System.currentTimeMillis());
            PilotWatchLog.info("picker: /help opened (" + lines.size() + " lines, " + rows.size() + " rows)");
            return true;
        } catch (Throwable t) {
            killLane("help", t);
            return false;
        }
    }

    /** The live client's settings, else the file (no session-bound voice client yet). */
    private cc.starsector.client.voice.VoiceClientSettings currentVoiceSettings() {
        cc.starsector.client.voice.VoiceClient voice = plugin.voice();
        if (voice != null) {
            return voice.settings();
        }
        return cc.starsector.client.voice.VoiceSettingsStore.load(
                com.fs.starfarer.api.Global.getSettings(), PilotWatchLog::info);
    }

    /** The session's VCFG sheet (resolves the server-default rows), null before a session. */
    private cc.starsector.client.voice.VoiceSheet currentVoiceSheet() {
        cc.starsector.client.voice.VoiceClient voice = plugin.voice();
        return voice != null ? voice.sheet() : null;
    }

    /**
     * A SELECTED verdict on the local settings listing: parse the {@code settings #<id>}
     * grammar, apply the pick purely ({@code VoiceSettingsMenu.pick}), hand a changed value to
     * the live voice client + the common file, then re-offer the outcome's listing so the panel
     * stays open (a ladder pick returns to ROOT, a ladder root row descends).
     */
    private void applyLocalSettingsPick(String command) {
        long id;
        int step = 0; // "settings #<id> +1|-1" = an in-place Left/Right step
        try {
            String rest = command.substring(command.indexOf('#') + 1).trim();
            int sp = rest.indexOf(' ');
            if (sp > 0) {
                step = Integer.parseInt(rest.substring(sp + 1).trim());
                rest = rest.substring(0, sp);
            }
            id = Long.parseLong(rest);
        } catch (RuntimeException e) {
            return; // not our grammar (unreachable: the law minted it)
        }
        cc.starsector.client.voice.VoiceClientSettings before = currentVoiceSettings();
        cc.starsector.client.voice.VoiceSettingsMenu.Outcome outcome = step != 0
                ? cc.starsector.client.voice.VoiceSettingsMenu.step(id, step, before,
                        currentVoiceSheet())
                : cc.starsector.client.voice.VoiceSettingsMenu.pick(id, before, currentVoiceSheet());
        // A value-identical outcome (a clamped step at a ladder end) is a no-op
        // — nothing applied, nothing written; a real change applies now and saves coalesced.
        if (outcome.next != null
                && !(before != null && before.serialize().equals(outcome.next.serialize()))) {
            cc.starsector.client.voice.VoiceClient voice = plugin.voice();
            if (voice != null) {
                voice.applySettings(outcome.next);
            }
            plugin.hudReadout.onSettings(outcome.next); // HUD toggles follow the pick, session or not
            plugin.chat.onSettings(outcome.next); // v2: a changed auto-share toggle re-declares (silent)
            pendingSave = outcome.next;
            pendingSaveNanos = System.nanoTime();
            PilotWatchLog.info("voice settings: " + outcome.next);
        }
        law.offerListing(ListPickerLaw.KIND_LOCAL_SETTINGS, outcome.listing,
                outcome.listing.size(), plugin.chat.isBoxOpen(), System.currentTimeMillis());
    }

    /** Write the held settings value once its debounce elapsed, the panel closed, or when forced. */
    private void flushPendingSettingsSave(boolean force) {
        if (pendingSave == null) {
            return;
        }
        if (!force && law != null && law.isOpen()
                && System.nanoTime() - pendingSaveNanos < SAVE_DEBOUNCE_NANOS) {
            return;
        }
        cc.starsector.client.voice.VoiceClientSettings s = pendingSave;
        pendingSave = null;
        cc.starsector.client.voice.VoiceSettingsStore.save(
                com.fs.starfarer.api.Global.getSettings(), s, PilotWatchLog::info);
    }

    // ---------------------------------------------------------------- furniture

    /** Lazy law build: visible rows from the LIVE screen height (bank {@code pp-recon/j}
     *  recipe (screenH-565)/20, clamped [1,12] — fewer rows, never overlap), fail-soft 8. */
    private void ensureLaw() {
        if (law != null) {
            return;
        }
        int rows = 8;
        try {
            float screenH = com.fs.starfarer.api.Global.getSettings().getScreenHeight();
            rows = Math.max(1, Math.min(12, (int) ((screenH - 565f) / 20f)));
        } catch (Throwable ignore) {
            // fall through — geometry must never cost the lane; 8 rows fit every sane band
        }
        lawRows = rows;
        law = new ListPickerLaw(rows);
    }

    /** The ensureChatColors law: HUD-native colors with fixed fail-soft fallbacks. */
    private void ensureColors() {
        if (headerColor != null) {
            return;
        }
        Color name = null;
        Color text = null;
        Color cyan = null;
        try {
            com.fs.starfarer.api.SettingsAPI settings = com.fs.starfarer.api.Global.getSettings();
            name = settings.getColor("textFriendColor");
            text = settings.getColor("standardTextColor");
            cyan = settings.getColor("mountCyanColor");
        } catch (Throwable ignore) {
            // fall through to the fixed fallbacks — a color must never cost the lane
        }
        headerColor = name != null ? name : new Color(100, 255, 100);
        rowColor = text != null ? text : Color.WHITE;
        valueColor = cyan != null ? cyan : new Color(0, 255, 200); // SET values

    }

    private void ensureLabels(com.fs.starfarer.api.SettingsAPI settings) {
        if (rowLabels != null) {
            return;
        }
        headerLabel = settings.createLabel("_", "graphics/fonts/victor10.fnt");
        headerLabel.setAlignment(Alignment.LMID);
        headerLabel.setColor(headerColor);
        rowTextH = headerLabel.computeTextHeight("Ag");
        // Sized at birth: a 0-wide label wraps its first setText glyph-by-glyph and the value
        // highlight indexes that wrapped text (the draw loop's SIZE BEFORE TEXT note).
        headerLabel.getPosition().setSize(PANEL_WIDTH - 6f, rowTextH);
        headerShown = null;
        rowLabels = new LabelAPI[lawRows];
        rowShown = new String[lawRows];
        for (int i = 0; i < rowLabels.length; i++) {
            rowLabels[i] = settings.createLabel("_", "graphics/fonts/victor10.fnt");
            rowLabels[i].setAlignment(Alignment.LMID);
            rowLabels[i].setColor(rowColor);
            rowLabels[i].getPosition().setSize(PANEL_WIDTH - 12f, rowTextH);
        }
    }

    private void killLane(String where, Throwable t) {
        pickerLaneDead = true;
        int rows = lawRows > 0 ? lawRows : 8;
        law = new ListPickerLaw(rows); // fresh IDLE: the keyboard is never left captured
        clearAssembly();
        PilotWatchLog.error("predict list-picker: " + where + " FAILED, picker disabled until"
                + " next round: " + t, t);
    }

    // ---------------------------------------------------------------- row vocabulary

    /** Human kind names for the panel header (presentation only; kinds are decoder-pinned). */
    private static String kindName(int kind) {
        if (kind == ListPickerLaw.KIND_LOCAL_SETTINGS) {
            return "settings (voice)";
        }
        if (kind == ListPickerLaw.KIND_LOCAL_HELP) {
            return "help"; // The /help menu
        }
        if (kind == ClientListingReader.KIND_TRANSFER) {
            return "transfer targets";
        }
        if (kind == ClientListingReader.KIND_MUTE) {
            return "mute (voice)";
        }
        if (kind == ClientListingReader.KIND_KICK) {
            return "kick (admin)";
        }
        if (kind == ClientListingReader.KIND_BAN) {
            return "ban (admin)"; // The /ban no-arg menu
        }
        if (kind == ClientListingReader.KIND_UNBAN) {
            return "unban (admin)"; // The /unban no-arg menu
        }
        if (kind == ClientListingReader.KIND_SWITCHTEAMS) {
            return "switch team (admin)"; //
        }
        if (kind == ClientListingReader.KIND_DEBUGSHIP) {
            return "debug ships (admin)";
        }
        if (kind == ClientListingReader.KIND_SPAWNFOE) {
            return "spawn foe (admin)";
        }
        if (kind == ClientListingReader.KIND_SPAWNFRIEND) {
            return "spawn friend (admin)"; // The player-side twin
        }
        if (kind == ClientListingReader.KIND_WAVE) {
            return "next wave faction (admin)";
        }
        if (kind == ClientListingReader.KIND_SHARE) {
            return "share my builds (Enter toggles)";
        }
        if (kind == ClientListingReader.KIND_BUILDS) {
            return "player builds (Enter saves, arrows fly next)";
        }
        if (kind == ClientListingReader.KIND_MODULE) {
            return "modules";
        }
        return "ships";
    }

    /** A row's display text + its SET value drawn cyan ({@code null} = none) —
     *  the text's tail, or the leading FLY NEXT cell when {@code lead}. */
    private static final class RowText {
        final String text;
        final String value;
        final boolean lead;

        RowText(String text, String value, boolean lead) {
            this.text = text;
            this.value = value;
            this.lead = lead;
        }
    }

    /** v2: the build kinds render {@code <id> "<name>" (<Hull>) [<state>]} from the label map; a
     *  /builds row LEADS with its FLY NEXT cell, so the visual clamp never hides it. */
    private RowText rowTextFor(int kind, ClientListingRecord.Entry e) {
        if (kind == ClientListingReader.KIND_SHARE || kind == ClientListingReader.KIND_BUILDS) {
            String label = buildLabels.get(e.token());
            StringBuilder out = new StringBuilder(96);
            String value = null;
            if (kind == ClientListingReader.KIND_BUILDS) {
                value = e.token().equals(flyYesToken) ? "YES" : "NO";
                out.append("FLY NEXT: ").append(value).append("  ");
            }
            out.append(e.token());
            if (label != null) {
                out.append(' ').append(label);
            }
            if (e.markCode() == ClientListingReader.MARK_SHARE_ON) {
                value = "(shared)";
                out.append(' ').append(value);
            } else if (e.markCode() == ClientListingReader.MARK_SHARE_OFF) {
                value = "(not shared)";
                out.append(' ').append(value);
            }
            return new RowText(out.toString(), value, kind == ClientListingReader.KIND_BUILDS);
        }
        return rowText(kind, e, shipHullNames);
    }

    /**
     * One hull id → its hull NAME off the game's own spec
     * ({@code getHullSpec(id).getHullName()}), fail-soft: a hull this client does not have, a null spec or
     * name, or any throw answers the id itself and counts {@link #hullNameMisses} (logged up to
     * {@code ERROR_LOG_MAX} times with the throwable's simple name only — the id is server-sent). Called only
     * from the listing landing, never per frame.
     */
    private String hullNameOrId(String hullId) {
        String name = null;
        Throwable why = null;
        try {
            com.fs.starfarer.api.combat.ShipHullSpecAPI spec =
                    com.fs.starfarer.api.Global.getSettings().getHullSpec(hullId);
            name = spec == null ? null : spec.getHullName();
        } catch (RuntimeException miss) {
            why = miss;
        }
        if (name != null && !name.isBlank()) {
            return name;
        }
        hullNameMisses++;
        if (hullNameMisses <= ERROR_LOG_MAX) {
            PilotWatchLog.info("picker hull_name_miss misses=" + hullNameMisses + " cause="
                    + (why == null ? "no-name" : why.getClass().getSimpleName()) + " (the row shows the hull id)");
        }
        return hullId;
    }

    /**
     * One row's display text — the server prose vocabulary re-rendered from the structured
     * marks ({@code ListingEmitter} is the source of the mapping; the decoder pinned occupant
     * presence ⇔ the three occupant-carrying marks, so the "player" fallback is unreachable
     * belt). Transfer rows carry the sanitized target hull label between token and mark.
     */
    private static RowText rowText(int kind, ClientListingRecord.Entry e) {
        return rowText(kind, e, java.util.Map.of());
    }

    /** {@link #rowText(int, ClientListingRecord.Entry)} with the landed /ship listing's hull names: a /ship row
     *  that carries a hull id takes {@link #shipHullRowText} (a hull with no landed name shows its hull id). */
    private static RowText rowText(int kind, ClientListingRecord.Entry e, java.util.Map<String, String> hullNames) {
        if (kind == ClientListingReader.KIND_SHIP && e.targetLabel() != null) {
            return shipHullRowText(e, hullNames.getOrDefault(e.targetLabel(), e.targetLabel()));
        }
        if (kind == ClientListingReader.KIND_UNBAN) {
            /* An /unban menu row leads with WHO (the stored
             * name or `cid <12 hex>` — the occupant label, decoder-REQUIRED on mark 31), then the
             * server-authored `<yyyy-MM-dd|-> <8 hex>[ <time left>]` target label (the W16 tail:
             * `~NNm`/`~NNh`/`~NNd`/`>99d`/`perm`); the `ban<n>` token is not
             * shown (the paste reads hullWireId). The token fallback is an unreachable belt. */
            String who = e.occupantLabel() != null ? e.occupantLabel() : e.token();
            /* + the row's ladder term cell ` (<term>)`, drawn
             * cyan (the /mute idiom; `(1d)` on an untouched row — the open rung,
             * ListPickerLaw.UNBAN_TERM_OPEN); worst case 24+2+24+7 = 57 chars
             * < VISUAL_MAX_CHARS, so the clamp never hides the cell. */
            String term = "(" + ListPickerLaw.rowTerm(kind, e) + ")";
            return new RowText((e.targetLabel() != null ? who + "  " + e.targetLabel() : who)
                    + " " + term, term, false);
        }
        StringBuilder out = new StringBuilder(64);
        String value = e.value(); // a /settings row's SET value; a /ban row's term is re-cut below
        // A row leads with its token; a /ship row's display name never paints (the list reads hull and id only;
        // the filter still matches the name). A /ship row that carries a hull never reaches here (shipHullRowText),
        // so a hull-less /ship row is its id plus the mark, and the targetLabel append below never prints a hull id.
        out.append(e.token());
        if (e.targetLabel() != null) {
            out.append(' ').append(e.targetLabel());
        }
        String occupant = e.occupantLabel() != null ? e.occupantLabel() : "player";
        switch (e.markCode()) {
            case ClientListingReader.MARK_SHIP_PILOTING:
            case ClientListingReader.MARK_DEBUGSHIP_PILOTING: // D6: same ship semantics
                out.append(" (piloting)");
                break;
            case ClientListingReader.MARK_SHIP_NEXT_RESPAWN:
            case ClientListingReader.MARK_DEBUGSHIP_NEXT_RESPAWN:
                out.append(" (next respawn)");
                break;
            case ClientListingReader.MARK_MODULE_FREE:
            case ClientListingReader.MARK_TRANSFER_FREE:
                out.append(" (free)");
                break;
            case ClientListingReader.MARK_MODULE_YOU:
            case ClientListingReader.MARK_TRANSFER_YOU:
                out.append(" (you)");
                break;
            case ClientListingReader.MARK_MODULE_TAKEN:
            case ClientListingReader.MARK_TRANSFER_TAKEN:
                out.append(" (taken: ").append(occupant).append(')');
                break;
            case ClientListingReader.MARK_MODULE_DESTROYED:
                out.append(" (destroyed)");
                break;
            case ClientListingReader.MARK_TRANSFER_ABANDONED:
                out.append(" (abandoned)");
                break;
            case ClientListingReader.MARK_TRANSFER_RESERVED:
                out.append(" (reserved: ").append(occupant).append(')');
                break;
            case ClientListingReader.MARK_MUTE_OFF: // W12 VOICE L3 (brief D13): muted state — a SET value (cyan)
                value = "(unmuted)";
                out.append(' ').append(value);
                break;
            case ClientListingReader.MARK_MUTE_ON:
                value = "(MUTED)";
                out.append(' ').append(value);
                break;
            case ClientListingReader.MARK_KICK_PLAYER: // D4a: no state axis; the row is
                break;                                 // "seatN <label>" — no suffix
            case ClientListingReader.MARK_BAN_PLAYER:
                // The kick row + its ladder
                // term cell, a SET value (cyan, the /mute idiom) — `(1d)` on an untouched row.
                value = "(" + ListPickerLaw.rowTerm(ClientListingReader.KIND_BAN, e) + ")";
                out.append(' ').append(value);
                break;
            case ClientListingReader.MARK_SWITCH_INSTANT: // The mark IS the mode
                out.append(" (instant)");
                break;
            case ClientListingReader.MARK_SWITCH_RESPAWN:
                out.append(" (at respawn)");
                break;
            case ClientListingReader.MARK_SPAWNFOE_PLAIN: // The officer option cell — a SET
            case ClientListingReader.MARK_SPAWNFRIEND_PLAIN: // value (cyan, the /mute idiom), N/A default
                value = "(N/A)";
                out.append(' ').append(value);
                break;
            case ClientListingReader.MARK_SPAWNFOE_OFFICER:
            case ClientListingReader.MARK_SPAWNFRIEND_OFFICER:
                value = "(officer)";
                out.append(' ').append(value);
                break;
            default:
                break; // MARK_SHIP_PLAIN and anything the decoder would have rejected
        }
        if (e.claimedNextRespawn()) {
            out.append(" (next respawn)");
        }
        return new RowText(out.toString(), value, false);
    }

    /**
     * A /ship row that carries a hull, as its OWN case:
     * {@code <Hull> - <id>[ (piloting)| (next respawn)]}: what the ship is, then which loadout, the id once; the
     * display name never paints (the filter still matches it). The separator is ASCII on purpose: the picker font
     * (victor10.fnt) maps U+00B7 to a 1-px blank cell, like the guillemets noted at the header hint. The hull name
     * comes from the landing's map, never a lookup here. A long id can push the mark past the 60-char clamp.
     *
     */
    private static RowText shipHullRowText(ClientListingRecord.Entry e, String hull) {
        StringBuilder out = new StringBuilder(96);
        out.append(hull).append(" - ").append(e.token());
        if (e.markCode() == ClientListingReader.MARK_SHIP_PILOTING) {
            out.append(" (piloting)");
        } else if (e.markCode() == ClientListingReader.MARK_SHIP_NEXT_RESPAWN) {
            out.append(" (next respawn)");
        }
        return new RowText(out.toString(), null, false);
    }

    /** Pure visual clamp (the bubble-lane idiom) — wire strings are never re-filtered. */
    private static String clampVisual(String s) {
        return s.length() > VISUAL_MAX_CHARS ? s.substring(0, VISUAL_MAX_CHARS - 3) + "..." : s;
    }

    private static boolean parseOnOff(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(value)) {
            return true;
        }
        if ("off".equals(value)) {
            return false;
        }
        throw new IllegalStateException("cc.predict.listPicker must be on|off; got '" + raw + "'");
    }
}
