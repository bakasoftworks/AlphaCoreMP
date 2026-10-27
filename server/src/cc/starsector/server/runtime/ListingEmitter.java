package cc.starsector.server.runtime;

import cc.starsector.server.runtime.ListingFrameCodec.ListingDown;
import cc.starsector.server.runtime.ListingFrameCodec.ListingEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * SS382 L3 structured-listing emitter (ledger):
 * pure mapping from the three no-arg listing sources — {@code /ship}
 * (canonical variant ids + per-seat marks), {@code /module}
 * ({@link SeatRebindArbiter.ModuleRow}), {@code /transfer} ({@link TransferTargets.Target}) —
 * to encoded {@code LSTD} frames ({@link ListingFrameCodec}). NO dispatch, NO session types,
 * NO listingId mint: the endpoint's dispatcher owns the per-seat listingId (latest-wins) and
 * the per-session CTRL sends with the ≤8-frames/tick drip (L3 part B).
 *
 * <p>Laws applied here (everything else is the codec's):
 * <ul>
 * <li><b>Cap law</b> — at most {@link ListingFrameCodec#MAX_TOTAL_ENTRIES} entries ride the
 *     wire (first N in source order — sources are deterministically ordered);
 *     {@code totalAvailable} carries the full source count (clamped to the wire ceiling) so
 *     the client renders its own "+N more" past the cap.</li>
 * <li><b>Empty source</b> — an EMPTY frame list (never a zero-entry frame, which the codec
 *     lawfully rejects): the dispatcher keeps its existing short CHTD reply for that case
 *     (e.g. "no transfer targets this round").</li>
 * <li><b>Occupant emission strip</b> — the codec REJECTS the widened smuggle set
 *     in occupant labels; this emitter STRIPS it before encode
 *     ({@link #emissionSafeOccupant}) so a lawfully-announced seat name that carries, say, a
 *     bidi control can never abort the listing. The stripped-empty survivor falls back to a
 *     constant, keeping the row lawful.</li>
 * </ul>
 *
 * <p>Law violations from a source (malformed token, bad label) THROW from the codec's encode
 * validation — fail-closed into the dispatcher's loud catch-all, never a partial listing.
 */
public final class ListingEmitter {

    /** Occupant fallback when the emission strip empties a label (all-smuggle name). */
    static final String OCCUPANT_STRIPPED_FALLBACK = "player";
    /** {@code yyyy-MM-dd} — the /unban row's day width ({@link #unbanEntries}). */
    private static final int DAY_CHARS = 10;

    private ListingEmitter() {}

    /** Encode the {@code /ship} listing ({@code ChatCommands.shipListingLines} row-for-row:
     * fielded wins over override, override annotates only when it differs). */
    public static List<byte[]> encodeShipListing(int seatId, int listingId, List<String> ids,
            String fieldedOrNull, String overrideOrNull) {
        return encodeListing(seatId, ListingFrameCodec.KIND_SHIP, listingId,
                shipEntries(ids, fieldedOrNull, overrideOrNull));
    }

    /** Encode the {@code /module} listing from the arbiter's structured rows. */
    public static List<byte[]> encodeModuleListing(int seatId, int listingId,
            List<SeatRebindArbiter.ModuleRow> rows) {
        return encodeListing(seatId, ListingFrameCodec.KIND_MODULE, listingId,
                moduleEntries(rows));
    }

    /** Encode the {@code /transfer} listing from the published census, marked for THIS seat. */
    public static List<byte[]> encodeTransferListing(int seatId, int listingId,
            List<TransferTargets.Target> targets) {
        return encodeListing(seatId, ListingFrameCodec.KIND_TRANSFER, listingId,
                transferEntries(seatId, targets));
    }

    /** {@code /ship} rows: token = the canonical id; marks mirror
     * {@code ChatCommands.shipListingLines} (same order, same precedence). */
    public static List<ListingEntry> shipEntries(List<String> ids, String fieldedOrNull,
            String overrideOrNull) {
        return shipEntries(ids, fieldedOrNull, overrideOrNull, null);
    }

    /**
     * The {@code /ship} rows plus each ship's DISPLAY name as the row's
     * occupantLabel, which the {@link ListingFrameCodec} ship-row field law makes optional. {@code nameOrNull} maps a
     * canonical id to its display name; a null function or a null/blank name = no label. The name is sanitized by
     * {@link VoiceMuteCommand#safeLabel} to printable ASCII, trimmed, cut at 24 before it rides, and a name that
     * sanitizes to nothing leaves the label absent. Display only: the token stays the id.
     */
    public static List<ListingEntry> shipEntries(List<String> ids, String fieldedOrNull,
            String overrideOrNull, Function<String, String> nameOrNull) {
        return shipEntries(ids, fieldedOrNull, overrideOrNull, nameOrNull, null);
    }

    /**
     * The named {@code /ship} rows plus each ship's HULL id as the row's
     * targetLabel, which the {@link ListingFrameCodec} ship-row field law makes optional. WHY on the row: the join
     * list and the in-game picker show and search the hull, and only the server knows it for a kept build.
     * {@code hullOrNull} maps a canonical id to its hull id; a null function or a null/blank hull = no label. The
     * hull is folded by {@link #shipHullLabelOrNull} (the occupant's printable-ASCII fold and trim), and a hull still
     * over the field's 24 chars rides ABSENT: an id cut short would name a different hull, so it is never cut and
     * never throws. Display only: the token stays the id.
     */
    public static List<ListingEntry> shipEntries(List<String> ids, String fieldedOrNull,
            String overrideOrNull, Function<String, String> nameOrNull, Function<String, String> hullOrNull) {
        Objects.requireNonNull(ids, "ids");
        List<ListingEntry> entries = new ArrayList<ListingEntry>(ids.size());
        for (String id : ids) {
            int mark;
            if (id.equals(fieldedOrNull)) {
                mark = ListingFrameCodec.MARK_SHIP_PILOTING;
            } else if (id.equals(overrideOrNull)) {
                mark = ListingFrameCodec.MARK_SHIP_NEXT_RESPAWN;
            } else {
                mark = ListingFrameCodec.MARK_SHIP_PLAIN;
            }
            String name = nameOrNull == null ? null : nameOrNull.apply(id);
            String label = name == null || name.trim().isEmpty() ? null : VoiceMuteCommand.safeLabel(name);
            String hull = shipHullLabelOrNull(hullOrNull == null ? null : hullOrNull.apply(id));
            entries.add(new ListingEntry(id, mark, false, hull, label, 0));
        }
        return entries;
    }

    /**
     * A hull id made lawful for a ship row's targetLabel — the occupant's
     * fold ({@link VoiceMuteCommand#safeLabel}: printable ASCII 0x20-0x7E kept, trimmed) WITHOUT its cut: null for a
     * null or all-unprintable input and for a result over {@link ListingFrameCodec#TARGET_LABEL_BUFFER_BYTES}
     * chars. Never throws.
     */
    static String shipHullLabelOrNull(String hullOrNull) {
        if (hullOrNull == null) {
            return null;
        }
        StringBuilder kept = new StringBuilder(hullOrNull.length());
        for (int i = 0; i < hullOrNull.length(); i++) {
            char c = hullOrNull.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                kept.append(c);
            }
        }
        String trimmed = kept.toString().trim();
        return trimmed.isEmpty() || trimmed.length() > ListingFrameCodec.TARGET_LABEL_BUFFER_BYTES ? null : trimmed;
    }

    /**
     * A ship listing re-ordered so the KEPT copies read newest first.
     * WHY: a host who keeps a ship several times gets copies whose ids differ only by a hash, and the clients paint the
     * rows in wire order, so the server's order is the only way to show which copy changed last.
     * {@code keptAtMsOrNull} maps an id to its keep time (epoch ms) or null. Returns a NEW list: the ids with a time,
     * sorted by it DESCENDING (a tie keeps the incoming order), sit as one block starting at the slot of the first
     * such id; every other id keeps its relative order, those before that slot stay in place and the rest follow the
     * block. The kept ids are contiguous in the listing's case-insensitive order, so in practice the block does not
     * move. A null function, a null or empty list, or no timed id = the same order back (a copy). A function that
     * throws reads as no time for that id, counted into one row per call; never throws.
     */
    public static List<String> keptNewestFirst(List<String> ids, Function<String, Long> keptAtMsOrNull) {
        if (ids == null) {
            return new ArrayList<String>();
        }
        if (keptAtMsOrNull == null || ids.isEmpty()) {
            return new ArrayList<String>(ids);
        }
        record Timed(String id, long at) {
        }
        List<Timed> timed = new ArrayList<Timed>();
        List<String> before = new ArrayList<String>();
        List<String> after = new ArrayList<String>();
        int faults = 0;
        String faultName = null;
        for (String id : ids) {
            Long at;
            try {
                at = keptAtMsOrNull.apply(id);
            } catch (RuntimeException unreadable) {
                at = null;
                faults++;
                faultName = unreadable.getClass().getSimpleName();
            }
            if (at != null) {
                timed.add(new Timed(id, at));
            } else {
                (timed.isEmpty() ? before : after).add(id);
            }
        }
        if (faults > 0) {
            System.out.println("listing-emitter kept_order_fault n=" + faults + " " + faultName);
        }
        if (timed.isEmpty()) {
            return new ArrayList<String>(ids);
        }
        timed.sort((a, b) -> Long.compare(b.at(), a.at())); // List.sort is stable: a tie keeps the incoming order
        List<String> out = new ArrayList<String>(ids.size());
        out.addAll(before);
        for (Timed t : timed) {
            out.add(t.id());
        }
        out.addAll(after);
        return out;
    }

    /** The door probe's KIND_MODSET rows — one per record of the boot mod set
     * (the set's own ordinal-id order): token = the mod id (the id law is a subset of the token
     * law), targetLabel = {@link ModSet#versionLabel}, hullWireId = {@link ModSet#filesPrefix} (jars word + data word),
     * mark = REQUIRED (required-IDENTICAL policy). Empty set ⇒ empty list (no listing). */
    public static List<ListingEntry> modSetEntries(ModSet set) {
        return modSetEntries(set, null);
    }

    /** The required rows (mark REQUIRED, id order) followed by the published
     * OPTIONAL rows (mark OPTIONAL, id order); {@code optionalOrNull} null/empty = the rung-2 shape. */
    public static List<ListingEntry> modSetEntries(ModSet set, ModSet optionalOrNull) {
        Objects.requireNonNull(set, "set");
        List<ListingEntry> entries = new ArrayList<ListingEntry>(
                set.size() + (optionalOrNull == null ? 0 : optionalOrNull.size()));
        for (ModSet.Record r : set.records()) {
            entries.add(new ListingEntry(r.id(), ListingFrameCodec.MARK_MODSET_REQUIRED, false,
                    ModSet.versionLabel(r.version()), null, ModSet.filesPrefix(r.jarsMd5(), r.dataMd5())));
        }
        if (optionalOrNull != null) {
            for (ModSet.Record r : optionalOrNull.records()) {
                entries.add(new ListingEntry(r.id(), ListingFrameCodec.MARK_MODSET_OPTIONAL, false,
                        ModSet.versionLabel(r.version()), null, ModSet.filesPrefix(r.jarsMd5(), r.dataMd5())));
            }
        }
        return entries;
    }

    /** {@code /module} rows: occupancy → kind-scoped mark; the claim rides the flag bit
     * (orthogonal axes); occupant label only on TAKEN, emission-stripped. */
    public static List<ListingEntry> moduleEntries(List<SeatRebindArbiter.ModuleRow> rows) {
        Objects.requireNonNull(rows, "rows");
        List<ListingEntry> entries = new ArrayList<ListingEntry>(rows.size());
        for (SeatRebindArbiter.ModuleRow row : rows) {
            int mark = switch (row.occupancy()) {
                case FREE -> ListingFrameCodec.MARK_MODULE_FREE;
                case YOU -> ListingFrameCodec.MARK_MODULE_YOU;
                case TAKEN -> ListingFrameCodec.MARK_MODULE_TAKEN;
                case DESTROYED -> ListingFrameCodec.MARK_MODULE_DESTROYED;
            };
            String occupant = row.occupancy() == SeatRebindArbiter.ModuleOccupancy.TAKEN
                    ? emissionSafeOccupant(row.occupantLabelOrNull()) : null;
            entries.add(new ListingEntry(row.token(), mark, row.claimedNextRespawn(),
                    null, occupant, 0));
        }
        return entries;
    }

    /** {@code /transfer} rows: status → mark for THIS seat ({@code TransferTargets
     * .listingLines} vocabulary), target label + hullWireId carried, occupant on
     * RESERVED/TAKEN only ({@code occupantOrSeatLabel} vocabulary, emission-stripped). */
    public static List<ListingEntry> transferEntries(int seatId,
            List<TransferTargets.Target> targets) {
        Objects.requireNonNull(targets, "targets");
        List<ListingEntry> entries = new ArrayList<ListingEntry>(targets.size());
        for (TransferTargets.Target target : targets) {
            int mark = switch (target.status()) {
                case AI -> ListingFrameCodec.MARK_TRANSFER_FREE;
                case PARKED -> ListingFrameCodec.MARK_TRANSFER_ABANDONED;
                case GRACE -> ListingFrameCodec.MARK_TRANSFER_RESERVED;
                case OCCUPIED -> target.occupantSeatId() == seatId
                        ? ListingFrameCodec.MARK_TRANSFER_YOU
                        : ListingFrameCodec.MARK_TRANSFER_TAKEN;
            };
            String occupant = ListingFrameCodec.markCarriesOccupant(mark)
                    ? emissionSafeOccupant(target.occupantOrSeatLabel()) : null;
            entries.add(new ListingEntry(target.token(), mark, false, target.label(),
                    occupant, target.hullWireId()));
        }
        return entries;
    }

    /**
     * The admin {@code /kick} listing rows — the {@code VoiceMuteCommand}
     * MUTE_LIST template verbatim (every OTHER live session with a real hull; label rides
     * BOTH targetLabel and occupantLabel like /mute; the picker pastes {@code /kick #<id>}).
     * The label is {@link VoiceMuteCommand#safeLabel}-sanitized with the {@code seat N}
     * fallback, so the listing encode can never throw on the caller's thread.
     * The label reads {@link AdminKickCommand.Target#name}
     * (the session's OWN resolved name) — the seat-name function is gone.
     */
    public static List<ListingEntry> kickEntries(long selfSessionId,
            List<AdminKickCommand.Target> live) {
        Objects.requireNonNull(live, "live");
        List<ListingEntry> entries = new ArrayList<ListingEntry>(live.size());
        for (AdminKickCommand.Target target : live) {
            if (target.sessionId() == selfSessionId || target.shipId() < 1L) {
                continue;
            }
            String label = playerLabel(target.seatId(), target.name());
            entries.add(new ListingEntry("seat" + target.seatId(),
                    ListingFrameCodec.MARK_KICK_PLAYER, false, label, label, target.shipId()));
        }
        return entries;
    }

    /**
     * The admin {@code /ban} MENU rows — the
     * {@link #kickEntries} row minus ADMIN sessions (a pick on one would only re-read
     * {@link AdminBanCommand#TARGET_ADMIN}); self and hull-less sessions skipped; the safe label
     * (else {@code seat N}) rides both label fields; the picker pastes {@code /ban #<id>}.
     * The label reads {@link AdminBanCommand.Target#name}
     * (the session's OWN resolved name — a nameless successor lists as {@code seat N}, never the
     * seat's previous occupant); the seat-name function is gone.
     */
    public static List<ListingEntry> banEntries(long selfSessionId,
            List<AdminBanCommand.Target> live) {
        return banEntries(selfSessionId, live, null);
    }

    /**
     * The {@code /ban} MENU rows + the OBSERVER rows
     * AFTER the hull rows — a live {@link AdminBanCommand.Target#observer} with no hull, not an
     * admin, not self (a hull-less NON-observer stays skipped: the row keys on the flag, never on
     * {@code shipId < 1} alone); {@code hullWireId} = {@link AdminBanCommand#OBSERVER_HANDLE_BASE}
     * {@code + slot} (slot 1.. in listing order, this call), token {@code obs<slot>}, label the
     * CONSTANT {@link #observerLabel} (an observer's name is never fanned). {@code handlesOut}
     * (nullable) receives each minted handle → session id — the ONE numbering the caller pins.
     */
    public static List<ListingEntry> banEntries(long selfSessionId,
            List<AdminBanCommand.Target> live, Map<Long, Long> handlesOut) {
        Objects.requireNonNull(live, "live");
        List<ListingEntry> entries = new ArrayList<ListingEntry>(live.size());
        for (AdminBanCommand.Target target : live) {
            if (target.sessionId() == selfSessionId || target.shipId() < 1L || target.admin()) {
                continue;
            }
            String label = playerLabel(target.seatId(), target.name());
            entries.add(new ListingEntry("seat" + target.seatId(),
                    ListingFrameCodec.MARK_BAN_PLAYER, false, label, label, target.shipId()));
        }
        int slot = 0;
        for (AdminBanCommand.Target target : live) {
            if (!target.observer() || target.shipId() >= 1L || target.admin()
                    || target.sessionId() == selfSessionId) {
                continue;
            }
            slot++;
            long handle = AdminBanCommand.OBSERVER_HANDLE_BASE + slot;
            String label = observerLabel(slot);
            entries.add(new ListingEntry("obs" + slot, ListingFrameCodec.MARK_BAN_PLAYER, false,
                    label, label, handle));
            if (handlesOut != null) {
                handlesOut.put(handle, target.sessionId());
            }
        }
        return entries;
    }

    /** An observer row's label — the CONSTANT
     *  {@code observer <slot>} (no player text); shared by the /ban + /mute rows and the /mute
     *  toggle reply. */
    static String observerLabel(int slot) {
        return "observer " + slot;
    }

    /** A player row's label — the name
     *  {@link VoiceMuteCommand#safeLabel}-sanitized, else ({@code null}, empty, or nothing
     *  printable left) {@code seat N}; shared by the /kick, /ban and /switchteams rows and the
     *  /kick reply. */
    static String playerLabel(int seatId, String nameOrNull) {
        String safe = nameOrNull == null ? null : VoiceMuteCommand.safeLabel(nameOrNull);
        return safe == null ? "seat " + seatId : safe;
    }

    /**
     * The admin {@code /unban} MENU rows over
     * {@link AdminBanCommand#adminCidEntriesNewestFirst} (display order).
     * Each row's n = the entry's 1-based SLOT in {@code pinKeys} — the caller's
     * tombstone-stable numbering ({@link AdminBanCommand#mergedPin}; the {@code /bans} numbering
     * that session sees), NOT its row position and not monotonic in display order; an entry
     * absent from the pin (impossible — the pin holds every current cid key) is skipped rather
     * than emitted with an unlawful {@code hullWireId}; token {@code ban<n>},
     * {@code hullWireId = n} (the picker pastes {@code /unban #<n>}, which the door resolves
     * against the caller's PINNED keys by KEY), targetLabel =
     * {@code <yyyy-MM-dd|-> <first 8 hex of key>[ <time left>]} (server-authored, ≤ 24 ASCII =
     * 19 + the W16 {@link BanTerm#remainingLabel} tail of ≤ 5 — an
     * out-of-range stamp renders {@code -}), occupantLabel = the entry's ban-time label (already
     * {@link VoiceMuteCommand#safeLabel}-clean — {@link BanSet.Entry} re-sanitizes on
     * construction) else {@code cid <first 12 hex>}. Never throws on the caller's thread.
     * The wall-clock form: {@link #unbanEntries(List, List, long)} at {@code currentTimeMillis}.
     */
    public static List<ListingEntry> unbanEntries(List<BanSet.Entry> newestFirst,
            List<String> pinKeys) {
        return unbanEntries(newestFirst, pinKeys, System.currentTimeMillis());
    }

    /** The same rows, each targetLabel + the entry's time left
     *  at {@code wallNow} ({@link BanTerm#remainingLabel}, ≤ 5 chars incl. its space, so 19 + 5 ≤ the
     *  LSTD 24-char target-label cap — a longer label threw on the READER thread). */
    public static List<ListingEntry> unbanEntries(List<BanSet.Entry> newestFirst,
            List<String> pinKeys, long wallNow) {
        Objects.requireNonNull(newestFirst, "newestFirst");
        Objects.requireNonNull(pinKeys, "pinKeys");
        // One O(pin) map, never an O(rows × pin) indexOf on the reader thread
        HashMap<String, Integer> slot = new HashMap<String, Integer>(pinKeys.size() * 2);
        for (int i = 0; i < pinKeys.size(); i++) {
            slot.putIfAbsent(pinKeys.get(i), i + 1);
        }
        List<ListingEntry> entries = new ArrayList<ListingEntry>(newestFirst.size());
        for (int i = 0; i < newestFirst.size(); i++) {
            BanSet.Entry e = newestFirst.get(i);
            Integer n = slot.get(e.key());
            if (n == null) {
                continue;
            }
            String key = CidDigest.body(e.key()); // the shown chars start after the h1: tag (all digest chars)
            String day = AdminBanCommand.day(e.atMillis());
            if (day.length() > DAY_CHARS) {
                day = "-"; // a hand-typed stamp past year 9999 ("+10000-...") would overflow the label
            }
            String target = day + " " + key.substring(0, Math.min(8, key.length()))
                    + BanTerm.remainingLabel(e.until(), wallNow);
            String occupant = e.label() != null ? e.label()
                    : "cid " + key.substring(0, Math.min(AdminBanCommand.KEY_ABBREV_CHARS, key.length()));
            entries.add(new ListingEntry("ban" + n, ListingFrameCodec.MARK_UNBAN_ENTRY, false,
                    target, occupant, n));
        }
        return entries;
    }

    /**
     * The admin {@code /switchteams} MENU rows — the
     * {@link #kickEntries} row shape, but the caller is LISTED (an admin switches their own team
     * too). ONE row per live player ("one listing per player,
     * toggle respawn/instant with arrow right/left, enter to issue"), token {@code seatN}, mark
     * {@code MARK_SWITCH_RESPAWN} = the non-disruptive default; the client's Left/Right flips
     * the row's mark LOCALLY and Enter pastes the mode the mark shows (the {@code /builds}
     * fly-next shape — the server sees only the paste). {@code filterOrNull} = the
     * {@code /switchteams <text>} pre-filter: an ASCII-folded substring over the SANITIZED label
     * (what the menu shows is what the filter matches), null = everyone.
     */
    public static List<ListingEntry> switchTeamEntries(List<AdminKickCommand.Target> live,
            IntFunction<String> seatName, String filterOrNull) {
        Objects.requireNonNull(live, "live");
        String wanted = filterOrNull == null ? null
                : filterOrNull.toLowerCase(java.util.Locale.ROOT);
        List<ListingEntry> entries = new ArrayList<ListingEntry>(live.size());
        for (AdminKickCommand.Target target : live) {
            if (target.shipId() < 1L) {
                continue;
            }
            String label = playerLabel(target.seatId(), seatName.apply(target.seatId()));
            if (wanted != null && !label.toLowerCase(java.util.Locale.ROOT).contains(wanted)) {
                continue;
            }
            entries.add(new ListingEntry("seat" + target.seatId(),
                    ListingFrameCodec.MARK_SWITCH_RESPAWN, false, label, label,
                    target.shipId()));
        }
        return entries;
    }

    /**
     * The admin {@code /debugships} listing rows — {@link #shipEntries}'
     * precedence verbatim (fielded wins over override, override annotates only when it
     * differs) on the DEBUGSHIP kind's own marks, so the picker's selection pastes
     * {@code /debugships <token>} (the KIND_SHIP paste {@code /ship <token>} DENIES
     * registry-only ids against the mod allowlist — the defect the kind split fixes).
     */
    public static List<ListingEntry> debugShipEntries(List<String> ids, String fieldedOrNull,
            String overrideOrNull) {
        Objects.requireNonNull(ids, "ids");
        List<ListingEntry> entries = new ArrayList<ListingEntry>(ids.size());
        for (String id : ids) {
            int mark;
            if (id.equals(fieldedOrNull)) {
                mark = ListingFrameCodec.MARK_DEBUGSHIP_PILOTING;
            } else if (id.equals(overrideOrNull)) {
                mark = ListingFrameCodec.MARK_DEBUGSHIP_NEXT_RESPAWN;
            } else {
                mark = ListingFrameCodec.MARK_DEBUGSHIP_PLAIN;
            }
            entries.add(new ListingEntry(id, mark, false, null, null, 0));
        }
        return entries;
    }

    /** The admin bare-{@code /wave} MENU rows — one plain-marked row per
     * fixed category ({@code WaveSchedule.WAVE_CATEGORIES}, list order); the picker pastes
     * {@code /wave <token>} and the next wave becomes that faction. */
    public static List<ListingEntry> waveEntries(List<String> categories) {
        Objects.requireNonNull(categories, "categories");
        List<ListingEntry> entries = new ArrayList<ListingEntry>(categories.size());
        for (String category : categories) {
            entries.add(new ListingEntry(category, ListingFrameCodec.MARK_WAVE_PLAIN,
                    false, null, null, 0));
        }
        return entries;
    }

    /** The admin {@code /spawnfoe} listing rows — one plain mark (a spawnable
     * foe has no seat state); the picker pastes {@code /spawnfoe <token>} (count = 1). */
    public static List<ListingEntry> spawnFoeEntries(List<String> ids) {
        return spawnEntries(ids, false);
    }

    /** The {@code /spawnfriend} twin — the same rows under
     * {@link ListingFrameCodec#MARK_SPAWNFRIEND_PLAIN} (the picker pastes
     * {@code /spawnfriend <token>}); {@code friendly=false} = {@link #spawnFoeEntries}. */
    public static List<ListingEntry> spawnEntries(List<String> ids, boolean friendly) {
        Objects.requireNonNull(ids, "ids");
        int mark = friendly ? ListingFrameCodec.MARK_SPAWNFRIEND_PLAIN
                : ListingFrameCodec.MARK_SPAWNFOE_PLAIN;
        List<ListingEntry> entries = new ArrayList<ListingEntry>(ids.size());
        for (String id : ids) {
            entries.add(new ListingEntry(id, mark, false, null, null, 0));
        }
        return entries;
    }

    /** Peer-build sharing v2: the {@code /share} rows — the caller's OWN accepted ids
     * (ledger mint order), mark = the share bit; the picker pastes {@code /share <token>} on an
     * OFF row and {@code /unshare <token>} on an ON row. Token only: no name, no hull, no label. */
    public static List<ListingEntry> shareEntries(List<SubmittedVariantLedger.ShareRow> rows) {
        Objects.requireNonNull(rows, "rows");
        List<ListingEntry> entries = new ArrayList<ListingEntry>(rows.size());
        for (SubmittedVariantLedger.ShareRow row : rows) {
            entries.add(new ListingEntry(row.canonicalId(), row.shared()
                    ? ListingFrameCodec.MARK_SHARE_ON : ListingFrameCodec.MARK_SHARE_OFF,
                    false, null, null, 0));
        }
        return entries;
    }

    /** The {@code /builds} rows — every shared id (any author, mint order), one plain
     * mark; a pick is a client-LOCAL save from the resident VDEF, never a send. */
    public static List<ListingEntry> buildsEntries(List<String> sharedIds) {
        Objects.requireNonNull(sharedIds, "sharedIds");
        List<ListingEntry> entries = new ArrayList<ListingEntry>(sharedIds.size());
        for (String id : sharedIds) {
            entries.add(new ListingEntry(id, ListingFrameCodec.MARK_BUILDS_PLAIN,
                    false, null, null, 0));
        }
        return entries;
    }

    /**
     * Chunk the (already kind-consistent) entries into lawful {@code LSTD} frames: cap at
     * {@link ListingFrameCodec#MAX_TOTAL_ENTRIES}, advertise the full source count via
     * {@code totalAvailable}, {@link ListingFrameCodec#ENTRIES_PER_FRAME} per non-final
     * frame. Empty input ⇒ empty list (see class javadoc).
     */
    public static List<byte[]> encodeListing(int seatId, int kind, int listingId,
            List<ListingEntry> allEntries) {
        Objects.requireNonNull(allEntries, "allEntries");
        if (allEntries.isEmpty()) {
            return List.of();
        }
        int totalEntries = Math.min(allEntries.size(), ListingFrameCodec.MAX_TOTAL_ENTRIES);
        int totalAvailable = Math.min(allEntries.size(), ListingFrameCodec.MAX_TOTAL_AVAILABLE);
        int seqCount = ListingFrameCodec.framesForTotal(totalEntries);
        List<byte[]> frames = new ArrayList<byte[]>(seqCount);
        for (int seqIndex = 0; seqIndex < seqCount; seqIndex++) {
            int from = seqIndex * ListingFrameCodec.ENTRIES_PER_FRAME;
            int to = Math.min(from + ListingFrameCodec.ENTRIES_PER_FRAME, totalEntries);
            frames.add(ListingFrameCodec.encodeDown(new ListingDown(seatId, kind, listingId,
                    seqIndex, seqCount, totalEntries, totalAvailable,
                    allEntries.subList(from, to))));
        }
        return frames;
    }

    /**
     * The widened smuggle strip at emission:
     * drop every {@link ListingFrameCodec#isWidenedSmuggleChar} code point, then trim; an
     * empty survivor becomes {@link #OCCUPANT_STRIPPED_FALLBACK}. Length laws survive the
     * strip (it only removes chars from an already-lawful seat name).
     */
    public static String emissionSafeOccupant(String label) {
        Objects.requireNonNull(label, "label");
        StringBuilder kept = new StringBuilder(label.length());
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (!ListingFrameCodec.isWidenedSmuggleChar(c)) {
                kept.append(c);
            }
        }
        String trimmed = kept.toString().trim();
        return trimmed.isEmpty() ? OCCUPANT_STRIPPED_FALLBACK : trimmed;
    }
}
