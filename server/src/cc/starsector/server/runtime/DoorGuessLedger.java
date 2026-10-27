package cc.starsector.server.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The door's GUESS LEDGER — the credential
 * compare law of the v12 door.
 * Replaces the verified-attempt budget. Pure, clock-injected, game-free, NOT
 * thread-safe: the endpoint uses it ONLY under its admission lock, after the idempotent resend
 * and before the fingerprint / probe / credential steps of every cookie-verified HELLO.
 *
 * <p>Per source KEY ({@link DoorSourceBudget#sourceKey}: IPv4 as-is, IPv6 by /56) a gap of
 * {@code min(250 ms << failStreak, 8 s)} since the key's last grant. A NEVER-SEEN key spends a
 * GLOBAL first-seen window of {@value #NEW_KEYS_PER_SECOND}/s, and a held first-seen key is NOT
 * inserted (the key table turns over at most {@value #NEW_KEYS_PER_SECOND} keys/s, ≥ 68 min at
 * {@value #MAX_KEYS}, whatever the flood's packet rate). A key at failStreak ≥
 * {@value #SUSPECT_STREAK} spends a GLOBAL suspect window of {@value #SUSPECT_COMPARES_PER_SECOND}/s.
 * A (key, cid) LANE opened by a prior PASS is paced at 250 ms on its own stamp and touches
 * neither window nor the streak; a lane FAIL revokes the lane and raises the key's streak. A PASS
 * never resets the streak (decay = −1 per hour since the last fail, saturation
 * {@value #STREAK_SATURATION}) — a reset would let any match-password holder cycle
 * wrong-admin-guess → correct-match-PASS and keep an unwindowed ≈ 1/s per key against the admin
 * secret.
 *
 * <p>Rates, per resource (never one "years" figure): one key ≈ 0.125
 * compares/s steady (11 in its first minute); a /48 (256 keys) ≈ 768 one-time then ≈ 8/s; a /40
 * ≈ 56/s for ≈ 68 min then ≈ 26/s; ANY number of keys ≤ 3·16 + 8 + 65 536/3 600 ≈ 74 compares/s
 * (the ceiling). Cliff/residuals: first-time joiners are HELD during a fresh-key flood (/48 ≈ 16 s
 * once, /40 ≈ 68 min, > 65 536 keys the flood's length); returners (lanes) are immune; a
 * credential holder can churn the lane LRU (returners fall back to the window-free key path);
 * the MISTYPER residual — ≥ ~65 attacker keys (any free /48) starve a streak ≥ 3 key's compares
 * for ≤ 1 h per streak step (the global suspect window).
 *
 *
 * <p>The ≈ 74 above is a long-run average; the 74 is now
 * a PER-WINDOW bound (72) — in any one-second window never-passed keys reach the hs-13 open at
 * most 16 (first-seen) + {@value #UNPROVEN_OPENS_PER_SECOND} (unproven, {@link #admitOpen}) + 8
 * (suspect) = 72 times. Banked grants (≤ 3 per key) drain at 48/s: a /48's 768 opens over 16 s
 * (un-windowed: ≈ 0.15 s of reader time at once), a /40's 196 608 over ≈ 68 min (un-windowed:
 * ≈ 35–39 s of the reader at once, 2 X25519 per open); > 65 536 keys cycled through the keys LRU
 * re-enter with fresh grants and hold the window for the flood's length. Residual: a
 * never-passed key's RE-ask (a mistyper's retry; a key first seen by a blank or print-refused
 * ask, or shared with one) can be held for those drain times; a first right-password ask
 * (GRANTED_NEW) and every passed key or lane are outside the window.
 *
 * <p>Every bound is a code constant (no property / env knob — a security bound is never a knob);
 * the explicit-bounds ctor exists for contract rows only. Memory at the caps ≈ one
 * {@value #MAX_KEYS}-entry map + {@value #MAX_LANES} lanes.
 */
public final class DoorGuessLedger {

    /** The gap after a grant at streak 0 (= the client's HELLO retransmit cadence). */
    public static final long FLOOR_GAP_MILLIS = 250L;
    /** The gap cap (a held connect lands inside the client's 15 s budget: worst 8.25 s). */
    public static final long MAX_GAP_MILLIS = 8_000L;
    /** failStreak at which a key's compares spend the global suspect window. */
    public static final int SUSPECT_STREAK = 3;
    /** Never-seen keys granted per fixed one-second window, all sources together. */
    public static final int NEW_KEYS_PER_SECOND = 16;
    /** Compares by suspect keys (streak ≥ {@link #SUSPECT_STREAK}) per one-second window, all together. */
    public static final int SUSPECT_COMPARES_PER_SECOND = 8;
    /**
     * OPENS by never-passed keys on a window-free {@link Verdict#GRANTED_SEEN}
     * grant per one-second window, all keys together ({@link #admitOpen}). WHY: below {@link #SUSPECT_STREAK} a seen
     * key is paced by its own gap alone and keeps its unspent grants (≤ 3) for as long as it stays in the table —
     * decay is a no-op at streak 0 — so keys banked by a no-verdict F2, an ordinary FAIL or hourly decay could spend
     * them in ONE burst of opens (2 X25519 each, on the reader thread that serves every seat). 48 = 3 ×
     * {@link #NEW_KEYS_PER_SECOND}: banked grants drain exactly as fast as keys can enter, and never-passed opens stay
     * ≤ 16 + 48 + 8 = 72 per window.
     */
    public static final int UNPROVEN_OPENS_PER_SECOND = 48;
    /** One streak step decays per this much idle time since the key's last FAIL. */
    public static final long STREAK_DECAY_MILLIS = 3_600_000L;
    /** Bounded access-ordered LRU of remembered keys. */
    public static final int MAX_KEYS = 65_536;
    /** Bounded access-ordered LRU of PASS-opened (key, cid) lanes. */
    public static final int MAX_LANES = 4_096;
    /** Lanes one key may hold at once: a lane grant ignores the key's backoff,
     * so an uncapped key at streak 0 could pre-build thousands of lanes with the match password and
     * burn each for one unpaced admin-secret compare. 8 keeps a household's returners immune;
     * the 9th returner evicts the key's OLDEST lane; the cap bounds concurrent lanes, not
     * lifetime cids (probes, drills and fresh-install GUIs mint throwaway cids — a lifetime cap
     * stripped a returner's immunity). Economics unchanged: opening a lane still costs one
     * key-path grant. */
    public static final int MAX_LANES_PER_KEY = 8;
    /** The streak never climbs past this (the gap is capped long before; bounds the int). */
    public static final int STREAK_SATURATION = 30;

    /** One admit's outcome. */
    public enum Verdict {
        GRANTED_NEW, GRANTED_SEEN, GRANTED_SUSPECT, GRANTED_LANE,
        HELD_GAP, HELD_NEW_WINDOW, HELD_SUSPECT_WINDOW, HELD_LANE_GAP;

        /** True for the four GRANTED_* verdicts. */
        public boolean granted() {
            return this == GRANTED_NEW || this == GRANTED_SEEN || this == GRANTED_SUSPECT
                    || this == GRANTED_LANE;
        }
    }

    private static final class KeyState {
        long lastGrantMillis;
        int failStreak;
        long lastFailMillis;
        boolean dryRunOpen;
        /** The key's last PASS was the ADMIN secret — the only keys whose VCHK
         * may compare against it (a match-password holder's VCHK is never an admin oracle). */
        boolean dryRunAdmin;
        /** Lanes this key holds (≤ {@link #MAX_LANES_PER_KEY}); decremented on revoke and LRU eviction. */
        int lanesOpen;
        /** Set by every {@link #recordPass}, never cleared — it dies with this
         * key's LRU entry (an evicted key re-enters as never-passed). */
        boolean everPassed;

        KeyState(long lastGrantMillis, int failStreak, long lastFailMillis) {
            this.lastGrantMillis = lastGrantMillis;
            this.failStreak = failStreak;
            this.lastFailMillis = lastFailMillis;
        }
    }

    /** A PASS-opened (key, cid) lane — its last grant + the owning key (so an eviction or a
     * revoke gives the owner's slot back without touching the keys LRU). */
    private static final class Lane {
        long grantMillis;
        final KeyState owner;

        Lane(long grantMillis, KeyState owner) {
            this.grantMillis = grantMillis;
            this.owner = owner;
        }
    }

    private final long floorGapMillis;
    private final long maxGapMillis;
    private final int suspectStreak;
    private final int newPerSecond;
    private final int suspectPerSecond;
    private final int unprovenPerSecond;
    private final long streakDecayMillis;
    private final int maxKeys;
    private final int maxLanes;
    private final Map<String, KeyState> keys;
    private final Map<String, Lane> lanes;
    private boolean windowOpen;
    private long windowStartMillis;
    private int newCount;
    private int suspectCount;
    private int unprovenCount;
    private int keysInBackoff;
    private long laneCapEvictions;

    /** The production ledger (the constants). */
    public static DoorGuessLedger forDoor() {
        return new DoorGuessLedger(FLOOR_GAP_MILLIS, MAX_GAP_MILLIS, SUSPECT_STREAK,
                NEW_KEYS_PER_SECOND, SUSPECT_COMPARES_PER_SECOND, STREAK_DECAY_MILLIS, MAX_KEYS,
                MAX_LANES, UNPROVEN_OPENS_PER_SECOND);
    }

    /** Explicit bounds — contract rows ONLY
     * ({@link #forDoor()} is the one production path). Each ≥ 1, {@code maxGap >= floorGap}; the
     * unproven-open bound is the production {@link #UNPROVEN_OPENS_PER_SECOND}. */
    public DoorGuessLedger(long floorGapMillis, long maxGapMillis, int suspectStreak,
            int newPerSecond, int suspectPerSecond, long streakDecayMillis, int maxKeys,
            int maxLanes) {
        this(floorGapMillis, maxGapMillis, suspectStreak, newPerSecond, suspectPerSecond, streakDecayMillis,
                maxKeys, maxLanes, UNPROVEN_OPENS_PER_SECOND);
    }

    /** The explicit-bounds ctor with the unproven-open bound
     * ({@link #admitOpen}) LAST — contract rows ONLY. Each ≥ 1, {@code maxGap >= floorGap}. */
    public DoorGuessLedger(long floorGapMillis, long maxGapMillis, int suspectStreak,
            int newPerSecond, int suspectPerSecond, long streakDecayMillis, int maxKeys,
            int maxLanes, int unprovenPerSecond) {
        if (floorGapMillis < 1L || maxGapMillis < floorGapMillis || suspectStreak < 1
                || newPerSecond < 1 || suspectPerSecond < 1 || streakDecayMillis < 1L
                || maxKeys < 1 || maxLanes < 1 || unprovenPerSecond < 1) {
            throw new IllegalArgumentException("guess ledger bounds: each >= 1 and maxGap >= floorGap;"
                    + " got floorGap=" + floorGapMillis + " maxGap=" + maxGapMillis
                    + " suspectStreak=" + suspectStreak + " newPerSecond=" + newPerSecond
                    + " suspectPerSecond=" + suspectPerSecond + " streakDecay=" + streakDecayMillis
                    + " maxKeys=" + maxKeys + " maxLanes=" + maxLanes
                    + " unprovenPerSecond=" + unprovenPerSecond);
        }
        this.floorGapMillis = floorGapMillis;
        this.maxGapMillis = maxGapMillis;
        this.suspectStreak = suspectStreak;
        this.newPerSecond = newPerSecond;
        this.suspectPerSecond = suspectPerSecond;
        this.unprovenPerSecond = unprovenPerSecond;
        this.streakDecayMillis = streakDecayMillis;
        this.maxKeys = maxKeys;
        this.maxLanes = maxLanes;
        this.keys = new LinkedHashMap<String, KeyState>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, KeyState> eldest) {
                if (size() > DoorGuessLedger.this.maxKeys) {
                    if (eldest.getValue().failStreak >= 1) {
                        keysInBackoff--;
                    }
                    return true;
                }
                return false;
            }
        };
        this.lanes = new LinkedHashMap<String, Lane>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Lane> eldest) {
                if (size() > DoorGuessLedger.this.maxLanes) {
                    eldest.getValue().owner.lanesOpen--;
                    return true;
                }
                return false;
            }
        };
    }

    /** The lane key — null for a
     * null/blank session key (the HELLO's cid in BOTH door modes, never a seat token), else
     * {@code sourceKey + '\n' + sessionKey} (injective: a source key never contains {@code \n}). */
    public static String laneKey(String sourceKey, String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return null;
        }
        return sourceKey + '\n' + sessionKey;
    }

    /** The gap after a grant at {@code streak}
     * = {@code min(floor << streak, max)}, overflow-safe for any streak. */
    public long gapFor(int streak) {
        if (streak <= 0) {
            return floorGapMillis;
        }
        if (streak >= 62 || floorGapMillis > (maxGapMillis >> streak)) {
            return maxGapMillis;
        }
        return Math.min(floorGapMillis << streak, maxGapMillis);
    }

    /** The admit steps, in their order — lane,
     * first-seen key, lazy decay, gap, suspect window, seen grant. A held verdict records
     * nothing (a held first-seen key is NOT inserted).
     *
     * <p>The key is looked up FIRST (the keys
     * LRU touch, so a lane-riding returner keeps its key warm). A lane whose owner is no longer
     * the key's live state — the owner was evicted from the keys LRU (absent), or evicted and
     * re-inserted since (a different KeyState) — is ORPHANED: it is dropped (its dead owner's
     * {@code lanesOpen} decremented, bookkeeping exact) and the admit falls through to the key
     * path, so the key spends the first-seen window (GRANTED_NEW / HELD_NEW_WINDOW) before it can
     * own lanes again. A lane whose owner is present keeps the old behaviour. */
    public Verdict admit(String sourceKey, String laneKeyOrNull, long nowMillis) {
        Objects.requireNonNull(sourceKey, "sourceKey");
        rollWindow(nowMillis);
        KeyState st = keys.get(sourceKey); // the get IS the keys-LRU touch
        if (laneKeyOrNull != null) {
            Lane lane = lanes.get(laneKeyOrNull); // the get IS the lanes-LRU touch
            if (lane != null) {
                if (lane.owner == st) {
                    if (nowMillis - lane.grantMillis < floorGapMillis) {
                        return Verdict.HELD_LANE_GAP;
                    }
                    lane.grantMillis = nowMillis;
                    return Verdict.GRANTED_LANE;
                }
                lanes.remove(laneKeyOrNull); // orphaned: its owner left the keys LRU
                lane.owner.lanesOpen--;
            }
        }
        if (st == null) {
            if (newCount >= newPerSecond) {
                return Verdict.HELD_NEW_WINDOW;
            }
            keys.put(sourceKey, new KeyState(nowMillis, 0, nowMillis));
            newCount++;
            return Verdict.GRANTED_NEW;
        }
        decay(st, nowMillis);
        if (nowMillis - st.lastGrantMillis < gapFor(st.failStreak)) {
            return Verdict.HELD_GAP;
        }
        if (st.failStreak >= suspectStreak) {
            if (suspectCount >= suspectPerSecond) {
                return Verdict.HELD_SUSPECT_WINDOW;
            }
            suspectCount++;
            st.lastGrantMillis = nowMillis;
            return Verdict.GRANTED_SUSPECT;
        }
        st.lastGrantMillis = nowMillis;
        return Verdict.GRANTED_SEEN;
    }

    /** A PASSED compare — the streak is NOT
     * reset; the key's dry-run right opens (VCHK); a non-null lane opens / re-stamps. */
    public void recordPass(String sourceKey, String laneKeyOrNull, long nowMillis) {
        recordPass(sourceKey, laneKeyOrNull, nowMillis, false);
    }

    /** {@link #recordPass(String, String, long)} naming the secret class —
     * {@code adminSecret} = the compare matched the ADMIN password (the key's VCHK may then compare
     * against it; a match-password PASS closes that). An existing lane is re-stamped; a NEW lane
     * always opens — a key already holding {@link #MAX_LANES_PER_KEY} first evicts its OLDEST lane
     * (least recently granted: the first entry of the access-ordered lane map whose owner is this
     * key; {@link #laneCapEvictions()} counts it).
     * the eviction scan is O(maxLanes) worst case, on the reader thread under the
     * endpoint's lock, and runs ONLY when a full key opens a 9th lane.
     *
     * <p>Re-insert note: an ABSENT key is re-inserted here
     * (and in {@link #recordFail}) — the only re-insert paths left outside {@link #admit}; they
     * are kept because the endpoint always admits first (an admitted key is present unless the
     * compare itself raced an eviction, which the lock excludes). */
    public void recordPass(String sourceKey, String laneKeyOrNull, long nowMillis, boolean adminSecret) {
        Objects.requireNonNull(sourceKey, "sourceKey");
        KeyState st = keys.get(sourceKey);
        if (st == null) {
            st = new KeyState(nowMillis, 0, nowMillis);
            keys.put(sourceKey, st);
        }
        st.dryRunOpen = true;
        st.dryRunAdmin = adminSecret;
        st.everPassed = true;
        if (laneKeyOrNull != null) {
            Lane lane = lanes.get(laneKeyOrNull);
            if (lane != null && lane.owner != st) { // orphaned: never re-stamped
                lanes.remove(laneKeyOrNull);
                lane.owner.lanesOpen--;
                lane = null;
            }
            if (lane != null) {
                lane.grantMillis = nowMillis;
            } else {
                if (st.lanesOpen >= MAX_LANES_PER_KEY) {
                    evictOldestLaneOf(st);
                }
                st.lanesOpen++;
                lanes.put(laneKeyOrNull, new Lane(nowMillis, st));
            }
        }
    }

    /** Drop {@code owner}'s least recently
     * granted lane — the lane map is access-ordered, so the first match is the oldest. */
    private void evictOldestLaneOf(KeyState owner) {
        for (java.util.Iterator<Lane> it = lanes.values().iterator(); it.hasNext(); ) {
            if (it.next().owner == owner) {
                it.remove();
                owner.lanesOpen--;
                laneCapEvictions++;
                return;
            }
        }
    }

    /** A FAILED compare — the lane (if any)
     * is revoked, the key's streak climbs (saturating), the dry-run right is NOT touched. */
    public void recordFail(String sourceKey, String laneKeyOrNull, long nowMillis) {
        Objects.requireNonNull(sourceKey, "sourceKey");
        if (laneKeyOrNull != null) {
            Lane revoked = lanes.remove(laneKeyOrNull);
            if (revoked != null) {
                revoked.owner.lanesOpen--;
            }
        }
        KeyState st = keys.get(sourceKey);
        if (st == null) {
            keys.put(sourceKey, new KeyState(nowMillis, 1, nowMillis));
            keysInBackoff++;
            return;
        }
        if (st.failStreak == 0) {
            keysInBackoff++;
        }
        if (st.failStreak < STREAK_SATURATION) {
            st.failStreak++;
        }
        st.lastFailMillis = nowMillis;
    }

    /** true iff this key PASSED a
     * cookie-verified compare and no VCHK mismatch revoked it since (the VCHK gate). One map
     * lookup; never inserts (an absent or evicted key reads closed). */
    public boolean dryRunOpen(String sourceKey) {
        KeyState st = keys.get(sourceKey);
        return st != null && st.dryRunOpen;
    }

    /** A VCHK mismatch closes the key's dry
     * run (never a {@link #recordFail}: VCHK is un-cookied and spoofable). */
    public void revokeDryRun(String sourceKey) {
        KeyState st = keys.get(sourceKey);
        if (st != null) {
            st.dryRunOpen = false;
        }
    }

    /** True iff the key's dry run is open AND its last PASS was the ADMIN
     * secret — the VCHK compare consults the admin password only then. Never inserts. */
    public boolean dryRunAdmin(String sourceKey) {
        KeyState st = keys.get(sourceKey);
        return st != null && st.dryRunOpen && st.dryRunAdmin;
    }

    /** true iff this key has PASSED a compare
     * ({@link #recordPass}) since it entered the keys LRU: the hs-13 door
     * asks a passed key's budget BEFORE the handshake open. Like {@link #dryRunOpen}: the caller
     * holds the endpoint's admission lock (this class is not thread-safe); one map lookup; never
     * inserts (an absent or evicted key reads false). */
    public boolean everPassed(String sourceKey) {
        KeyState st = keys.get(sourceKey);
        return st != null && st.everPassed;
    }

    /**
     * The UNPROVEN-OPEN window — true = the open may run, false = this one-second
     * window's unproven bound ({@link #UNPROVEN_OPENS_PER_SECOND} at the door) is spent and NOTHING is recorded (the
     * caller answers nothing; the key's next ask still waits the gap its {@link #admit} grant stamped). Only a
     * {@link Verdict#GRANTED_SEEN} of a key that never PASSED spends a slot (an absent key reads never-passed): NEW and
     * SUSPECT grants spent their own windows in {@link #admit}, a LANE grant implies a PASS (lanes open only in
     * {@link #recordPass}), and a passed key's opens face the door's per-request budgets instead.
     *
     * <p>WHY it counts OPENS, never grants: the hs-13 door asks it AFTER every exit that records no verdict (a ban /
     * hold, the print, the no-secret bit, a token-door probe / observer) and immediately before the open, so a grant
     * that leaves early spends nothing — a window spent by grants would let a dozen keys re-asking with no secret
     * hold every never-passed re-ask, forever. The caller passes the SAME {@code nowMillis} its
     * {@link #admit} used. Same lock law as {@link #admit}; never inserts.
     */
    public boolean admitOpen(String sourceKey, Verdict granted, long nowMillis) {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(granted, "granted");
        if (!granted.granted()) {
            return false; // A HELD_* verdict never opens (no caller passes one; fail-closed)
        }
        if (granted != Verdict.GRANTED_SEEN || everPassed(sourceKey)) {
            return true;
        }
        rollWindow(nowMillis);
        if (unprovenCount >= unprovenPerSecond) {
            return false;
        }
        unprovenCount++;
        return true;
    }

    /** Lanes the key holds now (≤ {@link #MAX_LANES_PER_KEY}), −1 if absent. */
    public int lanesOpenOf(String sourceKey) {
        KeyState st = keys.get(sourceKey);
        return st == null ? -1 : st.lanesOpen;
    }

    /** Keys remembered (≤ maxKeys); contract-row observable. */
    public int trackedKeys() {
        return keys.size();
    }

    /** PASS-opened lanes remembered (≤ maxLanes); contract-row observable. */
    public int trackedLanes() {
        return lanes.size();
    }

    /** Keys whose STORED streak is ≥ 1; decay is lazy (applied at the key's next admit), so idle
     * keys over-count — the door line's {@code attempt_held_sources=}. (
     * was "exact through decay and eviction"). */
    public int keysInBackoff() {
        return keysInBackoff;
    }

    /** Lifetime count of lanes evicted
     * because their key already held {@link #MAX_LANES_PER_KEY} and opened a new one — the door
     * line's {@code guess_lane_capped=}. */
    public long laneCapEvictions() {
        return laneCapEvictions;
    }

    /** The key's stored streak (decay applies lazily at its next admit), −1 if absent; never inserts. */
    public int streakOf(String sourceKey) {
        KeyState st = keys.get(sourceKey);
        return st == null ? -1 : st.failStreak;
    }

    public long floorGapMillis() {
        return floorGapMillis;
    }

    public long maxGapMillis() {
        return maxGapMillis;
    }

    public int suspectStreak() {
        return suspectStreak;
    }

    /** Bound getter, named after {@link #NEW_KEYS_PER_SECOND}. */
    public int newKeysPerSecond() {
        return newPerSecond;
    }

    /** Bound getter, named after {@link #SUSPECT_COMPARES_PER_SECOND}. */
    public int suspectComparesPerSecond() {
        return suspectPerSecond;
    }

    /** Bound getter, named after {@link #UNPROVEN_OPENS_PER_SECOND}. */
    public int unprovenOpensPerSecond() {
        return unprovenPerSecond;
    }

    public long streakDecayMillis() {
        return streakDecayMillis;
    }

    public int maxKeys() {
        return maxKeys;
    }

    public int maxLanes() {
        return maxLanes;
    }

    /** The DoorSourceBudget idiom: one fixed one-second window (its three counts — first-seen,
     * suspect, unproven-open), reset at ≥ 1 000 ms or when the clock runs backwards. */
    private void rollWindow(long nowMillis) {
        if (!windowOpen || nowMillis - windowStartMillis >= 1_000L || nowMillis < windowStartMillis) {
            windowOpen = true;
            windowStartMillis = nowMillis;
            newCount = 0;
            suspectCount = 0;
            unprovenCount = 0;
        }
    }

    /** Lazy decay — one step per full decay period since the last fail. */
    private void decay(KeyState st, long nowMillis) {
        if (st.failStreak <= 0) {
            return;
        }
        long steps = (nowMillis - st.lastFailMillis) / streakDecayMillis;
        if (steps <= 0L) {
            return;
        }
        int lowered = (int) Math.max(0L, st.failStreak - steps);
        st.failStreak = lowered;
        st.lastFailMillis += steps * streakDecayMillis;
        if (lowered == 0) {
            keysInBackoff--;
        }
    }
}
