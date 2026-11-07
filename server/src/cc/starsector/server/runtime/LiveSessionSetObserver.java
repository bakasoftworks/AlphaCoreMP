package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * The serve's per-tick live-set observer. It owns the id-to-session map pair the session diff reads, and
 * rebuilds and swaps them ONLY on a tick whose live list differs from the last one it saw; the caller runs
 * its death and join walks only when {@link #observe} says the set changed.
 *
 * <p>WHY: the serve frame used to rebuild the map and walk both sets every tick, inside a method too large
 * for the JIT to compile, so a steady set paid interpreted map work for an empty diff every tick. The
 * compare, the rebuild and the swap live here instead, in one small class the JIT compiles; a steady tick
 * costs one size check plus one identity compare per session, and allocates nothing.
 *
 *
 * <p>Why an identity compare and not a change counter on the session door: every door site that admits or
 * ends a session would have to bump the counter, and one missed bump leaves a join or a death never
 * diffed. The compare needs no cooperation from the door.
 *
 * <p>Invariants:
 * <ul>
 * <li>Unchanged = the same session objects in the same order as the last observe. A session's id is final
 * and a session never comes back to life, so an identical list means the map a rebuild would produce
 * equals the one held: skipping the rebuild changes no output. The same set in a new order reads as
 * changed, which costs a rebuild, never a wrong answer.</li>
 * <li>The held map and the scratch map are always two distinct maps: the constructor allocates two, and
 * only a changed observe swaps them. A swap on an unchanged tick would alias the two, the next
 * changed observe's clear would wipe the previous set, and the caller's diff would miss every death and
 * re-announce every survivor.</li>
 * <li>A changed observe clears and fills the OTHER map, so the map the caller took from the previous
 * observe still holds the previous set while the caller diffs against it.</li>
 * <li>A set that lasts past its first tick is also copied into the scratch map, once, on its first
 * unchanged observe. Both maps then hold every such set, as they did when the map was rebuilt and swapped
 * every tick; a HashMap's capacity, and so the iteration order the caller's death walk follows, depends
 * on the largest set the map ever held.</li>
 * <li>The held list is a private copy taken on a changed observe: a caller that later edits its own list
 * cannot make a changed set compare unchanged.</li>
 * </ul>
 * Sim-thread only; no locking.
 *
 * <p>
 *
 * @param <S> the session type; the serve uses {@code PilotIntentDatagramServer.PeerSession}
 */
public final class LiveSessionSetObserver<S> {

    private final ToLongFunction<S> idOf;
    /** The set as of the last observe, keyed by session id (signed: a negative id is normal). */
    private Map<Long, S> prevLive;
    /** The map the next changed observe clears and fills; never the same map as {@link #prevLive}. */
    private Map<Long, S> liveByIdScratch;
    /** A copy of the list the last changed observe saw; null until the first observe. */
    private List<S> prevLiveList;
    /** The scratch map still holds the set before the last change; the first unchanged observe refills it. */
    private boolean scratchBehind;

    /** Starts with an empty held set, so the first observe reports every live session as a join. */
    public LiveSessionSetObserver(ToLongFunction<S> idOf) {
        if (idOf == null) {
            throw new NullPointerException("live-set observer needs an id function");
        }
        this.idOf = idOf;
        this.prevLive = new HashMap<>();
        this.liveByIdScratch = new HashMap<>();
    }

    /**
     * Observes this tick's live list (read only, never kept). On a changed list the scratch map is rebuilt
     * from it and swapped in as {@link #liveById()}; the map the previous {@link #liveById()} returned is
     * left holding the previous set.
     *
     * @return true when the list differs from the last observed one (or this is the first observe)
     */
    public boolean observe(List<S> live) {
        if (sameAsLastObserved(live)) {
            if (scratchBehind) {
                fill(liveByIdScratch, live);
                scratchBehind = false;
            }
            return false;
        }
        Map<Long, S> liveById = liveByIdScratch;
        fill(liveById, live);
        liveByIdScratch = prevLive;
        prevLive = liveById;
        prevLiveList = new ArrayList<>(live);
        scratchBehind = true;
        return true;
    }

    /** The set as of the last observe; the same map instance across every unchanged observe. */
    public Map<Long, S> liveById() {
        return prevLive;
    }

    private boolean sameAsLastObserved(List<S> live) {
        List<S> last = prevLiveList;
        if (last == null || last.size() != live.size()) {
            return false;
        }
        int i = 0;
        for (S session : live) {
            if (session != last.get(i++)) {
                return false;
            }
        }
        return true;
    }

    private void fill(Map<Long, S> map, List<S> live) {
        map.clear();
        for (S session : live) {
            map.put(idOf.applyAsLong(session), session);
        }
    }
}
