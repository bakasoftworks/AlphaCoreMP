package cc.starsector.client;

/**
 * The unmatched-drop hull CREDIT ring (Shape A) — the pure law behind
 * {@code FoeHullReconcileLane.HullLedger}.
 *
 * <p>WHY: the ledger's {@code onWire} FIFO retire discards any wire drop left over once the replay
 * deque is empty. When the server's drop for a replica-fired volley reaches the ledger BEFORE the
 * local engine applies the same volley (a wire-first inversion), the later local pushes sit as
 * {@code outstanding} against a wire that already carries them, and the body rests that far below
 * wire (one recorded case: a 345.04 hp leftover discarded, the resting gap
 * 628.16). The leftover becomes a CREDIT that a
 * replica push arriving within {@link #CREDIT_TICKS} snapshot labels uses up before admission.
 *
 * <p>Law: a credit minted at label {@code M} is LIVE at label {@code S} iff
 * {@code 0 <= S - M <= CREDIT_TICKS} and dead otherwise (strict {@code >}: the measured lag-3
 * inversion, 105416/e199 row 26908, must survive).
 * Expiry runs on the snapshot LABEL, never sim seconds (the measure's unit). A label BEHIND the mint
 * (a regressed clock) is dead too, so a credit can never outlive a label regression. Consumption is
 * oldest-first over live slots only; a fully used slot is dropped; a dead slot waits for
 * {@link #expire} so it is counted. A full ring evicts its oldest slot (counted). A credit only lowers
 * the ledger's {@code outstanding} (the target moves toward the wire, never past it); the raw
 * scalars never see it. Render thread only; primitive state, allocation-free after construction,
 * O({@link #SLOTS}) per call (no per-tick loop).
 */
final class HullCreditRing {

    /** Snapshot labels a credit stays live (measured max attributed inversion lag = 3). */
    static final int CREDIT_TICKS = 3;
    /** Ring capacity (the measure saw at most two leftover drops inside one window). */
    static final int SLOTS = 4;

    /** Apply-source classes ({@code PredictReconcileCombatPlugin.hullSourceClass}). LOCAL = own
     *  ship, non-ship or unresolved source: never uses credit (an own hit leads the wire). */
    static final int SRC_LOCAL = 0;
    /** A wire-driven replica source ({@code foeShip} or a {@code RemoteEntity.ship}): uses credit. */
    static final int SRC_REPLICA = 1;
    /** Server-dealt (dead-source kill window / engine-door det): no entry; its drop IS the credit. */
    static final int SRC_SERVER_DEALT = 2;

    private final float[] hp = new float[SLOTS];
    private final long[] tick = new long[SLOTS];
    private int head;
    private int size;

    int minted;
    /** Applies that used up any credit (one per apply, however many slots it spanned). */
    int credited;
    int expired;
    int evicted;
    float creditedHp;
    float expiredHp;
    /** The hp twins so the credit hp reconciles — mintedHp = creditedHp +
     *  expiredHp + evictedHp + clearedHp + live (the hp a match reset drops without CLOSE is still counted). */
    float mintedHp;
    float evictedHp;
    float clearedHp;

    /** MEASUREMENT bound (labels past {@link #CREDIT_TICKS}) for {@link #lateAfterExpiry}: an apply
     *  later than {@code CREDIT_TICKS + LATE_WINDOW} labels after the dead credit's mint is not
     *  counted. It sizes how late the matching apply arrives (report-06 F1: 152022 expired 72 % of the
     *  credit hp, cause unsized); it never widens the live window (no behavior change). */
    static final int LATE_WINDOW = 8;

    /** The last {@link #SLOTS} EXPIRED credits (mint label + hp left at expiry), written by
     *  {@link #expire} when it drops a dead slot, read by an uncredited {@link #consume}; a matched
     *  entry is removed (one late apply never counts twice), a full memory drops its oldest. The
     *  meters: uncredited applies that fell within LATE_WINDOW of an expired credit, the hp they could
     *  have used ({@code min(apply, Σ matched)}), and the max label age since the oldest matched mint.
     * */
    private final float[] expHp = new float[SLOTS];
    private final long[] expLabel = new long[SLOTS];
    private int expHead;
    private int expSize;
    int lateAfterExpiry;
    float lateAfterExpiryHp;
    long lateMaxAgeLabels;

    /** The apply-first PREPAID stash (one amount + its hit tick): a wire-acked hit applied BEFORE the
     *  snapshot carrying its drop reached the ledger. {@link #netPrepaid} nets it out of that
     *  snapshot's leftover drop, so the hit's own drop never mints an ORPHAN credit a later replica
     *  push takes. */
    private float stashHp;
    private long stashTick;
    /** Stashes dropped un-netted: the first snapshot at or after the hit tick was more than
     *  {@link #CREDIT_TICKS} labels past it. */
    int prepaidExpired;
    /** The stash hp identity: every prepaid hp ends as netted
     *  (taken out of a drop), unmatched (a flat / short snapshot inside the window), expired, or
     *  cleared (RESUME) — or is still live in {@link #stashHp}. */
    float nettedHp, unmatchedHp, prepaidExpiredHp, clearedStashHp;

    /** One snapshot's leftover drop, stamped with that snapshot's label. */
    void mint(float amount, long label) {
        if (!(amount > 0f)) {
            return;
        }
        if (size == SLOTS) {
            evictedHp += hp[head];
            head = (head + 1) % SLOTS;
            size--;
            evicted++;
        }
        int idx = (head + size) % SLOTS;
        hp[idx] = amount;
        tick[idx] = label;
        size++;
        minted++;
        mintedHp += amount;
    }

    /** Uses up LIVE credits oldest-first against {@code applied}; returns the remainder (what the
     *  ledger still admits). A non-positive or NaN apply consumes nothing. */
    float consume(float applied, long nowLabel) {
        return consume(applied, nowLabel, Long.MIN_VALUE);
    }

    /** {@link #consume(float, long)} ORDER-BOUND: only credits minted at a label {@code >= minLabel}
     *  are used (a wire-acked hit's SERVER tick — its drop lands in the first snapshot labelled at or
     *  after it, so an older credit is never its own), and only an expired credit of such a label can
     *  count late. {@code Long.MIN_VALUE} = unbound. */
    float consume(float applied, long nowLabel, long minLabel) {
        if (!(applied > 0f)) {
            return applied;
        }
        float rem = applied;
        int i = 0;
        while (i < size && rem > 0f) {
            int idx = (head + i) % SLOTS;
            if (!live(tick[idx], nowLabel) || tick[idx] < minLabel) {
                i++;
                continue;
            }
            float take = Math.min(rem, hp[idx]);
            hp[idx] -= take;
            rem -= take;
            if (hp[idx] <= 0f) {
                removeAt(i);
            } else {
                i++;
            }
        }
        if (rem < applied) {
            credited++;
            creditedHp += applied - rem;
        } else {
            countLate(rem, nowLabel, minLabel);
        }
        return rem;
    }

    /** An uncredited apply of {@code rem} at {@code nowLabel}: counts it late when any expired credit
     *  minted {@code 0 <= nowLabel - label <= CREDIT_TICKS + LATE_WINDOW} labels ago (at a label
     *  {@code >= minLabel}) is remembered, and removes every matched entry. Measurement only.
     * */
    private void countLate(float rem, long nowLabel, long minLabel) {
        float matchedHp = 0f;
        long oldest = Long.MAX_VALUE;
        int i = 0;
        while (i < expSize) {
            int idx = (expHead + i) % SLOTS;
            long age = nowLabel - expLabel[idx];
            if (age >= 0L && age <= CREDIT_TICKS + LATE_WINDOW && expLabel[idx] >= minLabel) {
                matchedHp += expHp[idx];
                oldest = Math.min(oldest, expLabel[idx]);
                shiftOut(expHp, expLabel, expHead, expSize, i);
                expSize--;
            } else {
                i++;
            }
        }
        if (oldest != Long.MAX_VALUE) {
            lateAfterExpiry++;
            lateAfterExpiryHp += Math.min(rem, matchedHp);
            lateMaxAgeLabels = Math.max(lateMaxAgeLabels, nowLabel - oldest);
        }
    }

    /** Drops (and counts) every dead slot at {@code label}. */
    void expire(long label) {
        int i = 0;
        while (i < size) {
            int idx = (head + i) % SLOTS;
            if (live(tick[idx], label)) {
                i++;
                continue;
            }
            expired++;
            expiredHp += hp[idx];
            remember(tick[idx], hp[idx]);
            removeAt(i);
        }
    }

    /** Stashes {@code amount} for the apply-first wire-acked hit at server tick {@code hitTick} (the
     *  PREPAID stash); a second stash sums and keeps the LATER tick (netted once every stashed hit's
     *  snapshot is in). */
    void prepay(float amount, long hitTick) {
        if (!(amount > 0f)) {
            return;
        }
        if (!(stashHp > 0f) || hitTick > stashTick) {
            stashTick = hitTick;
        }
        stashHp += amount;
    }

    /** At a fresh snapshot {@code label} at or after the stash tick: nets the stash out of
     *  {@code drop} (the leftover the FIFO retire left; never below 0) and clears it — or, when the
     *  label is more than {@link #CREDIT_TICKS} past the tick, drops it counted {@link #prepaidExpired}.
     *  Returns the drop left to mint. */
    float netPrepaid(float drop, long label) {
        if (!(stashHp > 0f) || label < stashTick) {
            return drop;
        }
        float left = drop;
        if (label - stashTick > CREDIT_TICKS) {
            prepaidExpired++;
            prepaidExpiredHp += stashHp;
        } else {
            float take = drop > 0f ? Math.min(drop, stashHp) : 0f; // a flat / short snapshot: the rest is never coming
            nettedHp += take;
            unmatchedHp += stashHp - take;
            left = drop - take;
        }
        stashHp = 0f;
        return left;
    }

    /** The live prepaid stash hp (rows + diagnostics). */
    float stashHp() {
        return stashHp;
    }

    /** Stale-feed resume / replay clear: every credit gone (the entries twin); the hp is counted
     *  {@link #clearedHp}, the slot count is not. The expired-credit memory
     *  goes too (a resume reconciles from wire; nothing pre-stall counts late),
     *  and the prepaid stash (a pre-stall hit never nets a post-resume drop). */
    void clear() {
        for (int i = 0; i < size; i++) {
            clearedHp += hp[(head + i) % SLOTS];
        }
        head = 0;
        size = 0;
        expHead = 0;
        expSize = 0;
        clearedStashHp += stashHp;
        stashHp = 0f;
    }

    /** Appends one expired credit to the memory, dropping its oldest when full (uncounted: the memory
     *  is a measurement aid, not a ledger). */
    private void remember(long label, float amount) {
        if (expSize == SLOTS) {
            expHead = (expHead + 1) % SLOTS;
            expSize--;
        }
        int idx = (expHead + expSize) % SLOTS;
        expLabel[idx] = label;
        expHp[idx] = amount;
        expSize++;
    }

    /** Σ live credit at {@code nowLabel} (rows + diagnostics). */
    float live(long nowLabel) {
        float sum = 0f;
        for (int i = 0; i < size; i++) {
            int idx = (head + i) % SLOTS;
            if (live(tick[idx], nowLabel)) {
                sum += hp[idx];
            }
        }
        return sum;
    }

    int size() {
        return size;
    }

    private static boolean live(long mintLabel, long nowLabel) {
        long age = nowLabel - mintLabel;
        return age >= 0L && age <= CREDIT_TICKS;
    }

    /** Removes logical slot {@code i}, keeping order (shift the tail left; at most SLOTS-1 moves). */
    private void removeAt(int i) {
        shiftOut(hp, tick, head, size, i);
        size--;
    }

    /** Shifts logical slots {@code i+1..size-1} of a ring one left (the caller decrements its size);
     *  shared by the credit ring and the expired memory. */
    private static void shiftOut(float[] v, long[] labels, int head, int size, int i) {
        for (int j = i; j < size - 1; j++) {
            int to = (head + j) % SLOTS;
            int from = (head + j + 1) % SLOTS;
            v[to] = v[from];
            labels[to] = labels[from];
        }
    }
}
