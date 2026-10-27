package cc.starsector.server.runtime;

import cc.starsector.server.ops.OpsStatusFile;

/**
 * The operator surface of the custom-variant
 * lane, kept out of {@code PilotServeSession} (WARN-tier file) — the ops-file counters and the
 * nine house-file verbs {@code custom-submissions on|off} / {@code custom-reject <seat>} /
 * {@code custom-ban <id>} / {@code custom-export <id>} + their inverses
 * {@code custom-allow <seat>} / {@code custom-unban <id>} + the kept
 * store's {@code custom-keep <id>} / {@code custom-keep-export <name>} / {@code custom-forget <stem>}. Every entry runs on the SIM thread (the
 * operator-command drain), every effect is the lane's; a lane that is OFF answers "unavailable"
 * (the {@code skip_wave_refused} idiom). {@code custom-export} targets the lane's resolved
 * {@code custom.exportDir} ({@code variants-inbox/} at the config file's directory by default —
 * the standing mint flow's intake) via the off-thread persist writer.
 */
public final class CustomVariantOps {

    /** Store-writing custom verbs a replay door answered "replay" without enqueueing (see {@link #replayUnpersisted}). */
    private static final java.util.concurrent.atomic.AtomicLong REPLAY_UNPERSISTED =
            new java.util.concurrent.atomic.AtomicLong();

    private CustomVariantOps() {
    }

    /** The store-writing custom verbs a re-simulation answered
     *  "replay" without enqueueing — cumulative over every serve this JVM ran, never reset (a reader that
     *  shares the JVM across serves reads a before / after delta). 0 when no replay door ever ran one. */
    public static long replayUnpersisted() {
        return REPLAY_UNPERSISTED.get();
    }

    /** The verbs whose ONLY effect is a write through the persist
     *  writer into the host's own folders — the export inbox and the kept-ship store. The remaining custom
     *  verbs change the in-memory lane only, so a re-simulation still runs them as recorded. */
    private static boolean writesHostStore(OperatorCommandFile.Verb verb) {
        return switch (verb) {
            case CUSTOM_EXPORT, CUSTOM_KEEP, CUSTOM_KEEP_EXPORT, CUSTOM_FORGET -> true;
            // The host ship verbs' kept-store arms write the same store
            case SHIP_KEEP, SHIP_REMOVE, SHIP_RESTORE -> true;
            default -> false;
        };
    }

    /** The host ship verbs' refusal phrases a chat line and the editor's card show (each ≤ 43 chars, so a receipt
     *  echoing a 64-char id stays inside the chat law). */
    public static final String CUSTOMS_OFF = "customs are off on this server";
    public static final String NOT_A_SHIP = "not a ship on this server";
    public static final String NOTHING_TO_PUT_BACK = "nothing to put back";

    /**
     * A kept verb's refusal token as the host ship verbs word it. The tokens are the lane's own
     * ({@code CustomVariantLane}'s kept gate): {@code bad_name} only reaches a put-back that was not a removed
     * kept ship.
     */
    public static String hostRefusalPhrase(String token) {
        return switch (String.valueOf(token)) {
            case "banned" -> "banned on this server";
            case "unknown_id" -> NOT_A_SHIP;
            case "kept_full" -> "the kept list is full";
            case "kept_off", "persist_off" -> CUSTOMS_OFF;
            case "not_kept" -> "already removed";
            case "bad_name" -> NOTHING_TO_PUT_BACK;
            default -> "busy - try again";
        };
    }

    /** The host's kept ships and removed kept ships, each sorted by ASCII fold. */
    public record HostParts(java.util.List<String> kept, java.util.List<String> removed) {
    }

    private static final java.util.Comparator<String> FOLD_ORDER =
            java.util.Comparator.comparing(ChatCommands::asciiLower).thenComparing(java.util.Comparator.naturalOrder());

    /**
     * The kept half of the host's ship list (serve / sim thread). {@code kept} = the kept stems the last kept read
     * listed; {@code removed} = the inbox's {@code kept-} files whose fold no kept stem holds (a removal moves the
     * file back to the inbox; a put-back copies it and leaves the inbox file), each rendered as the inbox name — the
     * exact token {@code custom-keep-export} takes for it. No lane / no kept listing = both empty.
     *
     */
    public static HostParts hostParts(CustomVariantLane laneOrNull) {
        CatalogPromotionLane promo = laneOrNull == null ? null : laneOrNull.promoOrNull();
        if (promo == null) {
            return new HostParts(java.util.List.of(), java.util.List.of());
        }
        java.util.List<String> kept = new java.util.ArrayList<>(promo.keptIds());
        kept.sort(FOLD_ORDER);
        java.util.Set<String> keptFolds = new java.util.HashSet<>();
        for (String stem : kept) {
            keptFolds.add(ChatCommands.asciiLower(stem));
        }
        java.util.List<String> removed = new java.util.ArrayList<>();
        for (String name : promo.inboxIds()) {
            if (name.startsWith(CatalogPromotionLane.INBOX_KEPT_PREFIX) && !keptFolds.contains(ChatCommands.asciiLower(name))) {
                removed.add(name);
            }
        }
        removed.sort(FOLD_ORDER);
        return new HostParts(java.util.List.copyOf(kept), java.util.List.copyOf(removed));
    }

    /** The CANONICAL kept stem whose ASCII fold equals {@code id}'s, null = not kept (a removal hands this spelling
     *  to the forget body, which compares exactly). */
    public static String keptStemOrNull(CustomVariantLane laneOrNull, String id) {
        CatalogPromotionLane promo = laneOrNull == null ? null : laneOrNull.promoOrNull();
        if (promo == null || id == null) {
            return null;
        }
        String fold = ChatCommands.asciiLower(id);
        for (String stem : promo.keptIds()) {
            if (ChatCommands.asciiLower(stem).equals(fold)) {
                return stem;
            }
        }
        return null;
    }

    /** The removed kept ship ({@link HostParts#removed}'s token) whose fold equals {@code id}'s, null = none.
     * */
    public static String removedStemOrNull(CustomVariantLane laneOrNull, String id) {
        if (id == null) {
            return null;
        }
        String fold = ChatCommands.asciiLower(id);
        for (String name : hostParts(laneOrNull).removed()) {
            if (ChatCommands.asciiLower(name).equals(fold)) {
                return name;
            }
        }
        return null;
    }

    /**
     * {@code /shipkeep <id>}: the custom-keep body under the head {@code shipkeep <id as typed>: }. Returns the
     * QUEUED line ({@code saving}) and books the caller for the final verdict (key = the custom id), or the refusal.
     *
     */
    public static String shipKeep(CustomVariantLane laneOrNull, PilotIntentDatagramServer door,
            OperatorCommandFile.Command command) {
        // "<id> replaces <X>" = the supersede: the head stays "shipkeep <id>: "
        // (the editor's slot key), the keep runs on the id alone, and the writer learns X through the sink's note so
        // the copy's .meta carries supersedes=<X> (read back at every kept scan, so a put-back finds the copy after
        // a restart). The note is taken by offerKeep inside keep(); a keep that did not queue clears it.
        String[] split = HiddenShipsLane.splitKeep(command.text());
        String id = split[0];
        String replaced = split[1];
        String head = "shipkeep " + id + ": ";
        String gate = hostGateOrNull(laneOrNull, door, command, head);
        if (gate != null) {
            return gate;
        }
        CustomVariantPersistSink sink = replaced == null ? null : laneOrNull.persistSinkOrNull();
        if (sink != null) {
            sink.noteSupersedes(id, replaced);
        }
        CustomVariantLane.KeptEnqueue e = laneOrNull.keep(id, head, true);
        if (sink != null && !e.queued()) {
            sink.forgetSupersedeNote(id);
        }
        return booked(laneOrNull, e, id, command, head);
    }

    /**
     * {@code /shipremove <id>} for a kept ship: the custom-forget body on {@code keptStem} (the CANONICAL spelling,
     * {@link #keptStemOrNull}) under {@code shipremove <id as typed>: }; books the caller (key = the stem).
     *
     */
    public static String shipRemoveKept(CustomVariantLane laneOrNull, PilotIntentDatagramServer door,
            OperatorCommandFile.Command command, String keptStem) {
        String head = "shipremove " + command.text() + ": ";
        String gate = hostGateOrNull(laneOrNull, door, command, head);
        if (gate != null) {
            return gate;
        }
        CustomVariantLane.KeptEnqueue e = laneOrNull.forget(keptStem, head, true);
        return booked(laneOrNull, e, keptStem, command, head);
    }

    /**
     * {@code /shiprestore <id>} for a removed kept ship: the custom-keep-export body on {@code removedStem}
     * ({@link #removedStemOrNull}) under {@code shiprestore <id as typed>: }; books the caller (key = the inbox name,
     * which is the stem the ship lists under again — a kept stem names its body).
     *
     */
    public static String shipRestoreKept(CustomVariantLane laneOrNull, PilotIntentDatagramServer door,
            OperatorCommandFile.Command command, String removedStem) {
        return shipRestoreKept(laneOrNull, door, command, removedStem, null);
    }

    /**
     * The put-back that re-keeps {@code removedStem} WITH the
     * {@code supersedes} link {@code supersedesOrNull} (null = the plain put-back): the writer learns it through the
     * sink's note, as a supersede's keep does ({@link #shipKeep}), so the re-kept copy's {@code .meta} names the root
     * it still stands for. The note is keyed by the same canonical inbox name the keep-export offers; taken by
     * {@code offerKeepFromExport}, dropped when the keep did not queue.
     */
    public static String shipRestoreKept(CustomVariantLane laneOrNull, PilotIntentDatagramServer door,
            OperatorCommandFile.Command command, String removedStem, String supersedesOrNull) {
        String head = "shiprestore " + command.text() + ": ";
        String gate = hostGateOrNull(laneOrNull, door, command, head);
        if (gate != null) {
            return gate;
        }
        if (removedStem == null) {
            return head + NOTHING_TO_PUT_BACK;
        }
        CustomVariantPersistSink sink = supersedesOrNull == null ? null : laneOrNull.persistSinkOrNull();
        if (sink != null) {
            sink.noteSupersedes(removedStem, supersedesOrNull);
        }
        CustomVariantLane.KeptEnqueue e = laneOrNull.keepFromExport(removedStem, head, true);
        if (sink != null && !e.queued()) {
            sink.forgetSupersedeNote(removedStem);
        }
        return booked(laneOrNull, e, removedStem, command, head);
    }

    /** The host verbs' shared front: customs off, then a re-simulation (answered {@code replay}, nothing queued,
     *  nothing booked — the {@link #handle} order). Null = go on. */
    private static String hostGateOrNull(CustomVariantLane laneOrNull, PilotIntentDatagramServer door,
            OperatorCommandFile.Command command, String head) {
        if (laneOrNull == null) {
            System.out.println("custom-variant operator_refused verb=" + command.verb()
                    + " reason=lane_off (custom.submissions=false at boot)");
            return head + CUSTOMS_OFF;
        }
        if (door != null && door.isReplay() && writesHostStore(command.verb())) {
            REPLAY_UNPERSISTED.incrementAndGet();
            return head + "replay";
        }
        return null;
    }

    /** A queued job books its caller (a console verb, receipt id 0, never books); the line goes back either way. */
    private static String booked(CustomVariantLane lane, CustomVariantLane.KeptEnqueue e, String key,
            OperatorCommandFile.Command command, String head) {
        HostReceiptBook book = lane.receiptBookOrNull();
        if (e.queued() && book != null) {
            book.expect(key, command.receiptSessionId(), head, System.currentTimeMillis());
        }
        return e.line();
    }

    /** The lane's ledger for the ops seat rows (null = lane OFF ⇒ blank custom block). */
    public static SubmittedVariantLedger ledgerOrNull(CustomVariantLane laneOrNull) {
        return laneOrNull == null ? null : laneOrNull.ledger();
    }

    /** The {@code custom.*} globals (lane OFF = {@link OpsStatusFile.CustomStats#NONE}).
     * The rejected split, pending + global cap, the serve's policy knobs
     * ({@code config} — the boot-line facts, static for the serve) and the export readback.
     * + the kept store — the catalog lane's listing counts / ids /
     * over-cap flags (no catalog lane = zeros) and the writer's kept verdict + failures (no sink = "-"). */
    public static OpsStatusFile.CustomStats stats(CustomVariantLane laneOrNull, CustomVariantConfig config) {
        if (laneOrNull == null) {
            return OpsStatusFile.CustomStats.NONE;
        }
        SubmittedVariantLedger ledger = laneOrNull.ledger();
        int policy = laneOrNull.policyRefusals();
        int caps = ledger.rejectedAtGateCount();
        int engine = ledger.rejectedByRegistrarCount();
        int operator = laneOrNull.operatorRefusals();
        CatalogPromotionLane promo = laneOrNull.promoOrNull();
        CustomVariantPersistSink sink = laneOrNull.persistSinkOrNull();
        return new OpsStatusFile.CustomStats(ledger.acceptedCount(),
                policy + caps + engine + operator,
                ledger.bannedCount(), laneOrNull.accepting(),
                policy, caps, engine, operator,
                ledger.pendingCount(), SubmittedVariantLedger.GLOBAL_CAP,
                config.tier().name().toLowerCase(java.util.Locale.ROOT), config.perSeat(),
                config.sModCap() == CustomVariantConfig.S_MOD_CAP_ENGINE ? "engine" : String.valueOf(config.sModCap()),
                config.dModsAllowed() ? "allow" : "deny",
                laneOrNull.exportsWritten(), laneOrNull.exportsFailed(),
                promo == null ? 0 : promo.keptListed(),
                promo == null ? java.util.List.of() : promo.keptIds(),
                laneOrNull.keptFailed(),
                sink == null ? "-" : sink.keptLast(),
                promo == null ? java.util.List.of() : promo.inboxIds(),
                promo != null && promo.keptOverCap(),
                promo != null && promo.inboxOverCap(),
                laneOrNull.submitFrameEncodeFailed(), laneOrNull.vsubDecodeRefused());
    }

    /** Dispatch one drained custom verb; returns the one-line receipt (the D5 lane's text). */
    public static String handle(CustomVariantLane laneOrNull, PilotIntentDatagramServer door,
            OperatorCommandFile.Command command) {
        if (laneOrNull == null) {
            System.out.println("custom-variant operator_refused verb=" + command.verb()
                    + " reason=lane_off (custom.submissions=false at boot)");
            return command.verb().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                    + ": custom variants are off on this serve";
        }
        // A re-simulation replays a recorded export / keep / keep-export /
        // forget, but never moves a file in or out of the host's inbox or kept store: the verb is counted and
        // answered "replay" before the lane's enqueue (the ban file's idiom). A live door never takes this arm.
        if (door != null && door.isReplay() && writesHostStore(command.verb())) {
            REPLAY_UNPERSISTED.incrementAndGet();
            return "replay";
        }
        switch (command.verb()) {
            case CUSTOM_SUBMISSIONS -> {
                laneOrNull.setAccepting(command.autoOn());
                return "custom-submissions " + (command.autoOn() ? "on" : "off");
            }
            case CUSTOM_REJECT, CUSTOM_ALLOW -> {
                String author = null;
                if (door != null) {
                    for (PilotIntentDatagramServer.PeerSession s : door.liveSessions()) {
                        if (s.seatId() == command.seatId()) {
                            author = door.customAuthorKey(s);
                        }
                    }
                }
                return command.verb() == OperatorCommandFile.Verb.CUSTOM_REJECT
                        ? laneOrNull.rejectSeat(command.seatId(), author)
                        : laneOrNull.allowSeat(command.seatId(), author);
            }
            case CUSTOM_BAN -> {
                return laneOrNull.ban(command.text());
            }
            case CUSTOM_UNBAN -> {
                return laneOrNull.unban(command.text());
            }
            case CUSTOM_EXPORT -> {
                return laneOrNull.export(command.text());
            }
            case CUSTOM_KEEP -> {
                return laneOrNull.keep(command.text());
            }
            case CUSTOM_KEEP_EXPORT -> {
                return laneOrNull.keepFromExport(command.text());
            }
            case CUSTOM_FORGET -> {
                return laneOrNull.forget(command.text());
            }
            default -> throw new IllegalArgumentException("not a custom verb: " + command.verb());
        }
    }
}
