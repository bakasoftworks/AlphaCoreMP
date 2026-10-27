package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * The FINAL verdict of a host's queued ship verb, mailed to the seat that typed it. A keep, a removal
 * of a kept ship and a put-back of a removed one only QUEUE work for the file writer: the chat arm
 * answers a queued line ({@code saving} / {@code removing} / {@code restoring}) and books the caller
 * here; the catalog lane's tick settles the booking once the writer's outcome and the listing
 * re-read say what really happened, or {@link #sweep} answers {@link #NO_RESULT} when nothing did
 * within {@link #NO_RESULT_MILLIS}. Each booking is answered exactly once.
 *
 * <p>SIM thread only (the verb drain books, the catalog lane's tick settles and sweeps): no lock,
 * no I/O. The mailer is the door's admin-receipt send, which clips every line to the chat law.
 * Keys are exact (the custom id for a keep, the kept stem for a removal or a put-back); session id
 * 0 is the console's (it has no chat seat) and is never booked. Bounded: at most
 * {@link #MAX_PENDING} bookings; past it the OLDEST is answered {@link #NO_RESULT} first, so no
 * caller waits on a booking that was silently dropped.
 *
 *
 */
public final class HostReceiptBook {

    /** A booking older than this is answered {@link #NO_RESULT} by {@link #sweep}. */
    public static final long NO_RESULT_MILLIS = 15_000L;
    /** The tail of a booking no outcome settled (the caller's prefix comes first). */
    public static final String NO_RESULT = "no result - check /shiplist";
    /** Bookings held at once (a host types one verb at a time; the door caps queued admin verbs). */
    public static final int MAX_PENDING = 64;

    /** The success tail of a ship that went back on {@code /ship} (a put-back or a show); the body = {@link #named}. */
    public static final String PUT_BACK = "put back - on /ship for every player";
    /** The success tail of a ship taken off {@code /ship} (a removed kept ship or a hide); the body = {@link #named}. */
    public static final String REMOVED = "removed - it leaves /ship for every player";
    /** The put-back of a SUPERSEDED ship: it is listed again and the copy that took its place is forgotten.
     * */
    public static final String PUT_BACK_COPY_GONE = "put back - the changed copy is gone";

    /** The success body of {@code /shipkeep <id> replaces <X>} once the copy lists and X left {@code /ship}.
     *Short on purpose: the receipt rides under {@code shipkeep <id>: } and the
     *  door clips at 120 chars, so the "flies at your next respawn" half lives in the room fan alone. */
    public static String changed(String name) {
        return named(name, "changed - the old one is off /ship");
    }

    private record Booking(long sessionId, String prefix, long bookedMillis) {
    }

    private final BiConsumer<Long, String> mailer;
    private final LinkedHashMap<String, Booking> pending = new LinkedHashMap<>();

    /** {@code mailer} = the door's admin-receipt send ({@code (sessionId, line)}). */
    public HostReceiptBook(BiConsumer<Long, String> mailer) {
        this.mailer = Objects.requireNonNull(mailer, "mailer");
    }

    /**
     * A SUCCESS body = the ship's quoted display name, then the tail. WHY
     * the name and never the kept stem: the receipt reaches the host's chat feed and the editor's card, and a stem
     * ({@code kept-<slug>-<hex>}) is an id the host never typed. The client's success test is exactly "the body
     * opens with a single quote", so a refusal body never does. {@code name} must already be bounded
     * ({@code CatalogPromotionLane.chatShipName}).
     */
    public static String named(String name, String tail) {
        return "'" + name + "' " + tail;
    }

    /** The success body of a keep that put a new ship on {@code /ship}. */
    public static String added(String name) {
        return named(name, "added - on /ship for every player");
    }

    /** The success body of a keep whose body was already kept (the ship is on {@code /ship}, nothing was written).
     * */
    public static String alreadyKept(String name) {
        return named(name, "already kept - nothing changed");
    }

    /**
     * Book the caller of one queued verb: {@code prefix} = its receipt head ({@code "<verb> <id>: "},
     * the id as typed). Session id 0 (a console verb) books nothing; a second booking on the same
     * key replaces the first (the newest caller gets the verdict).
     */
    public void expect(String key, long sessionId, String prefix, long nowMillis) {
        if (key == null || prefix == null || sessionId == 0L) {
            return;
        }
        pending.remove(key);
        if (pending.size() >= MAX_PENDING) {
            Iterator<Map.Entry<String, Booking>> oldest = pending.entrySet().iterator();
            Booking evicted = oldest.next().getValue();
            oldest.remove();
            System.out.println("host-receipt evicted cap=" + MAX_PENDING + " (the oldest booking answered no result)");
            mailer.accept(evicted.sessionId(), evicted.prefix() + NO_RESULT);
        }
        pending.put(key, new Booking(sessionId, prefix, nowMillis));
    }

    /** Mail {@code prefix + finalText} to the booked caller ONCE and forget the key; false = no booking. */
    public boolean settle(String key, String finalText) {
        Booking booking = key == null ? null : pending.remove(key);
        if (booking == null) {
            return false;
        }
        mailer.accept(booking.sessionId(), booking.prefix() + finalText);
        return true;
    }

    /** Answer {@link #NO_RESULT} to every booking older than {@link #NO_RESULT_MILLIS} and forget it. */
    public void sweep(long nowMillis) {
        if (pending.isEmpty()) {
            return;
        }
        List<Booking> due = new ArrayList<>();
        for (Iterator<Booking> it = pending.values().iterator(); it.hasNext(); ) {
            Booking booking = it.next();
            if (nowMillis - booking.bookedMillis() > NO_RESULT_MILLIS) {
                due.add(booking);
                it.remove();
            }
        }
        if (due.isEmpty()) {
            return;
        }
        System.out.println("host-receipt no_result count=" + due.size());
        for (Booking booking : due) {
            mailer.accept(booking.sessionId(), booking.prefix() + NO_RESULT);
        }
    }

    /** Bookings waiting for a verdict. */
    public int pending() {
        return pending.size();
    }
}
