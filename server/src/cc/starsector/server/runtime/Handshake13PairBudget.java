package cc.starsector.server.runtime;

import java.util.Arrays;

/**
 * The client's per-host budget for sealing hs-13 envelopes against door share pairs.
 * WHY: a door learns one new yes / no about the typed secret ONLY when the client seals
 * against a share pair {@code (YbM, YbA)} it has not sealed against before — a re-seal on the same pair (new nonce,
 * new cookie, new {@code a}) re-asks the same two generators. Without a budget a fake door that answers every cookie
 * change with a fresh pair reads 2 guesses per re-seal at the kit's own retry cadence (several per second, for as long
 * as the victim retries); with it a NEW pair takes one token from a per-host bucket of {@value #CAPACITY}, +1 per
 * {@value #REFILL_MILLIS} ms, so a fake door gets at most 6 guesses at once and then 4 / min — never the cheaper oracle
 * than the real door's own per-key rate. An honest door changes its pair once per 60 s cookie bucket + once per serve
 * rebirth, so an honest sequence never waits (only more than 3 serve lives inside 30 s — a crash loop — waits, at
 * most 30 s). Sealing against a pair the {@link Host} REMEMBERS is free.
 *
 * <p><b>Scope of those numbers</b>: they bound ONE process on
 * the {@link Handshake13Flight.Lane#PASSWORD} lane. The join window and each game launch start full (Shape, below), so
 * one join attempt can hand a fake door about twice the burst — 12 guesses at once (inferred from the two buckets,
 * unmeasured). On the {@link Handshake13Flight.Lane#TOKEN} lane the secret is not in the generator at all
 * ({@code prs = EMPTY}; the token rides the key mix), so this budget rations nothing there: ONE seal gives an active
 * man-in-the-middle an offline test of the token — nothing against a minted token (128 random bits), a real oracle on
 * a HAND-WRITTEN one.
 *
 * <p><b>Shape.</b> ONE instance per process ({@link #process()}; the join window and each game launch start
 * full) shared by every sender (the dial, the join window's probe, the in-session re-HELLO pump, VCHK), keyed by the
 * caller's host key — an opaque String compared with {@code equals} (the kit passes the TYPED host, lower-cased, +
 * port — never the resolved address, so a door re-answering the typed name's DNS earns no bucket per answer;
 * this class never parses it). Rows build their own instances.
 *
 * <p><b>Bounds and their cost.</b> A {@link Host} remembers its last {@value #PAIRS_REMEMBERED} granted pairs,
 * least-recently-used out: a pair that fell off costs a token again (the safe side). The budget remembers
 * {@value #HOSTS_REMEMBERED} host keys, least-recently-used out — THE BOUND'S COST: a dropped host comes back FULL
 * ({@value #CAPACITY} tokens), so a door earns an early refill when the victim's process asks under 64 other host keys
 * between two of its asks — a key per typed host + port, so DNS re-answers for one typed name never mint one (a
 * door's control of the player's typed names is the residual). A {@link Host} a caller still holds after its key was dropped keeps working, detached.
 *
 * <p><b>Clock.</b> {@code nowMillis} = the caller's MONOTONIC millisecond clock, any sign ({@code System.nanoTime() /
 * 1_000_000} has an arbitrary origin). A clock that goes backwards refills nothing: the refill anchor stays where it
 * was, so a backward excursion and its return mint no token (a permanent backward jump delays the next refill by the
 * jump — the safe side).
 *
 * <p><b>Threads.</b> Self-synchronized: {@link #host} on the budget, every {@link Host} method on that host; three
 * sender threads share one instance. Nothing here calls out while holding a lock.
 *
 * <p><b>Classloader B</b> (this class will ride the client jar): {@code java.lang} and
 * {@code java.util.Arrays} / {@code java.util.Locale} only — no logging, no I/O, no {@code java.util.concurrent}, no
 * {@code java.net} ({@link #hostKey} takes the typed host as a String for that reason). A caller
 * bug (a null key, a share not 32 B) = {@code IllegalArgumentException} naming the field, never its bytes.
 *
 *
 */
public final class Handshake13PairBudget {

    /** Tokens a host starts with and never exceeds (provisional). */
    public static final int CAPACITY = 3;
    /** One token returns per this many milliseconds. */
    public static final long REFILL_MILLIS = 30_000L;
    public static final int PAIRS_REMEMBERED = 8, HOSTS_REMEMBERED = 64;

    /** {@code YbM} / {@code YbA} are X25519 u-coordinates, 32 B each. */
    private static final int SHARE_BYTES = 32;

    private static final Handshake13PairBudget PROCESS = new Handshake13PairBudget();

    private final String[] keys = new String[HOSTS_REMEMBERED];
    private final Host[] hosts = new Host[HOSTS_REMEMBERED];
    private final long[] lastUse = new long[HOSTS_REMEMBERED];
    private int hostCount;
    private long useClock;

    /** The ONE per-process instance every sender shares. */
    public static Handshake13PairBudget process() {
        return PROCESS;
    }

    /** A private budget (rows; a sender always uses {@link #process()}). */
    public Handshake13PairBudget() {
    }

    /**
     * The ONE host key every sender of this process passes to {@link #host} — the kit's dial, probe, re-HELLO pump and
     * VCHK (through {@code PredictDatagramDial.budgetHostKey}) and the drill client: the TYPED host, lower-cased
     * ({@code Locale.ROOT}), + ":" + port. WHY the typed locator, never the resolved address: a door that answers the
     * typed name's DNS with a new address per lookup must not earn a fresh bucket per answer; WHY
     * here: the drill client was still keying on the resolved address, so the law moved
     * to the one place every sender calls. A caller passes
     * {@code InetSocketAddress.getHostString()} — never a lookup: the name the address was built from, or a typed
     * literal's text. {@code IllegalArgumentException("typedHost")} on null. Row: Handshake13FlightContractRows B4.
     */
    public static String hostKey(String typedHost, int port) {
        if (typedHost == null) {
            throw new IllegalArgumentException("typedHost");
        }
        return typedHost.toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }

    /**
     * The {@link Host} for {@code hostKey}, created FULL on first sight; each call marks it most-recently-used. The
     * 65th distinct key drops the least-recently-used host (it comes back FULL — the bound's cost, class javadoc).
     * {@code IllegalArgumentException("hostKey")} on null.
     */
    public synchronized Host host(String hostKey) {
        if (hostKey == null) {
            throw new IllegalArgumentException("hostKey");
        }
        useClock++;
        for (int i = 0; i < hostCount; i++) {
            if (keys[i].equals(hostKey)) {
                lastUse[i] = useClock;
                return hosts[i];
            }
        }
        int slot;
        if (hostCount < HOSTS_REMEMBERED) {
            slot = hostCount++;
        } else {
            slot = 0;
            for (int i = 1; i < HOSTS_REMEMBERED; i++) {
                if (lastUse[i] < lastUse[slot]) {
                    slot = i;
                }
            }
        }
        keys[slot] = hostKey;
        hosts[slot] = new Host();
        lastUse[slot] = useClock;
        return hosts[slot];
    }

    @Override
    public String toString() {
        return "Handshake13PairBudget";
    }

    /**
     * One host's bucket + the pairs sealed against there. The pairs are the door's PUBLIC shares (compared with
     * {@code Arrays.equals}; nothing secret is held here).
     */
    public static final class Host {
        private int tokens = CAPACITY;
        private boolean anchored;
        private long anchorMillis;
        private long denied;
        private final byte[][] pairs = new byte[PAIRS_REMEMBERED][];
        private final long[] pairUse = new long[PAIRS_REMEMBERED];
        private int pairCount;
        private long pairClock;

        private Host() {
        }

        /**
         * {@code true} = the caller may seal against {@code (ybM, ybA)} now: free when this host remembers the pair
         * (it becomes most-recently-used), else one token is taken and the pair remembered. {@code false} = no token
         * (counted in {@link #denied()}); the pair is NOT remembered. {@code IllegalArgumentException} naming the
         * share when one is null or not 32 B (the flight passes decoded COOKIE13 shares, always 32 B).
         */
        public synchronized boolean admit(byte[] ybM, byte[] ybA, long nowMillis) {
            if (ybM == null || ybM.length != SHARE_BYTES) {
                throw new IllegalArgumentException("ybM");
            }
            if (ybA == null || ybA.length != SHARE_BYTES) {
                throw new IllegalArgumentException("ybA");
            }
            refill(nowMillis);
            byte[] pair = new byte[2 * SHARE_BYTES];
            System.arraycopy(ybM, 0, pair, 0, SHARE_BYTES);
            System.arraycopy(ybA, 0, pair, SHARE_BYTES, SHARE_BYTES);
            pairClock++;
            for (int i = 0; i < pairCount; i++) {
                if (Arrays.equals(pairs[i], pair)) {
                    pairUse[i] = pairClock;
                    return true;
                }
            }
            if (tokens <= 0) {
                denied++;
                return false;
            }
            tokens--;
            int slot;
            if (pairCount < PAIRS_REMEMBERED) {
                slot = pairCount++;
            } else {
                slot = 0;
                for (int i = 1; i < PAIRS_REMEMBERED; i++) {
                    if (pairUse[i] < pairUse[slot]) {
                        slot = i;
                    }
                }
            }
            pairs[slot] = pair;
            pairUse[slot] = pairClock;
            return true;
        }

        /** The tokens left at {@code nowMillis} (0..{@value #CAPACITY}), after the refill that time earns. */
        public synchronized int tokens(long nowMillis) {
            refill(nowMillis);
            return tokens;
        }

        /** How many {@link #admit} calls returned {@code false} on this host. */
        public synchronized long denied() {
            return denied;
        }

        @Override
        public String toString() {
            return "Handshake13PairBudget.Host";
        }

        /**
         * +1 token per whole {@link #REFILL_MILLIS} since the anchor, never above {@link #CAPACITY}; a full bucket
         * re-anchors at {@code now} (a full host banks no time). {@code now} before the anchor = refills nothing and
         * the anchor STAYS (class javadoc: a backward excursion mints no token on its return). A forward step too large
         * for a {@code long} difference refills to full.
         */
        private void refill(long now) {
            if (!anchored || tokens >= CAPACITY) {
                anchored = true;
                anchorMillis = now;
                return;
            }
            if (now < anchorMillis) {
                return;
            }
            long elapsed = now - anchorMillis;
            if (elapsed < 0L) {
                tokens = CAPACITY;
                anchorMillis = now;
                return;
            }
            long add = elapsed / REFILL_MILLIS;
            if (add <= 0L) {
                return;
            }
            if (add >= CAPACITY - tokens) {
                tokens = CAPACITY;
                anchorMillis = now;
            } else {
                tokens += (int) add;
                anchorMillis += add * REFILL_MILLIS;
            }
        }
    }
}
