package cc.starsector.server.runtime;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The hs-13 door's half of the CPace exchange: per cookie
 * time bucket ({@link DoorCookie#bucketOf}) two RANDOM scalars {@code bM}, {@code bA} and their shares
 * {@code YbM = bM·G_m}, {@code YbA = bA·G_a}, which the door sends in {@code COOKIE13} and opens the client's F2 with;
 * plus the per-serve lane salt and the per-admit auth share. WHY per bucket: a COOKIE13's shares must still open the
 * F2 that answers it for as long as its cookie verifies (the minted bucket and the one after it), and a scalar must
 * not outlive that window (erase = zeros, then dropped).
 *
 * <p><b>Generators</b>, computed ONCE in the constructor: {@code G_m = generator(prsMatch, CI)} — {@code prsMatch}
 * zero-length = the public generator (a token door or an open door); {@code G_a = generator(prsAdmin, CI)}, or, with
 * no admin secret armed, {@code generator(32 fresh random bytes, CI)} — a throwaway nobody can open, so the reply
 * never tells a non-holder whether an admin secret is armed. The PRS arrays are the caller's: read once,
 * never kept, never written.
 *
 * <p><b>Threading.</b> {@link #pairOrNull} (the door's reader thread, any thread) is a lookup under this object's
 * monitor and NEVER mints; {@link #roll} (the door's timer thread) draws + multiplies OUTSIDE the monitor and only
 * installs / erases under it, so a reader never waits for an X25519. The constructor mints the current bucket AND
 * the next one, and every {@code roll(n)} leaves {@code n} and {@code n + 1} held, so a reader at a minute boundary
 * meets a pair the timer already minted. An erase zeroes the {@link Pair}'s scalar arrays IN PLACE — a caller still
 * holding that {@code Pair} sees zeros, and an open over zeroed scalars proves nothing, so the door opens ONLY
 * through {@link #open} (an F2) or {@link #openWraps} (a VCHK13's wraps), which both re-check
 * the pair after EVERY open, failed or not.
 *
 * <p><b>VALUE-DOMAIN.</b> A bucket is a SIGNED long (the door clock is {@code System.nanoTime() / 1_000_000}; negative
 * is normal) and is never an array index. {@code nowBucket} = {@link Long#MIN_VALUE} or {@link Long#MAX_VALUE} =
 * {@code IllegalArgumentException} (a caller bug: {@code DoorCookie.bucketOf} never yields either, and {@code n ± 1}
 * must not overflow). At most three buckets are held at once ({@code n - 1}, {@code n}, {@code n + 1}).
 *
 * <p><b>Scalars</b> are multiplied ONLY through {@link HandshakeCrypto#scalarMult}; a {@code null} from it
 * (the JDK refused the point) = draw a new scalar, at most {@value #MINT_TRIES} tries, then
 * {@code IllegalStateException("hs13 share")}.
 *
 * <p><b>Classloader B</b> (the in-game loopback door will use this class): it references only
 * {@code java.lang}, {@code java.util.Arrays}, {@code java.security.SecureRandom}, {@link Handshake13} and
 * {@link HandshakeCrypto} — no logging, no I/O, no {@code java.util.concurrent}, no non-constant string concatenation.
 *
 * <p><b>No secret in any message:</b> every exception text and {@code toString} here is constant — no scalar, share,
 * salt or PRS byte.
 *
 *
 */
public final class DoorShares13 {

    /** Scalar draws per share before the class gives up (a JDK-refused share from a random scalar is ~never). */
    private static final int MINT_TRIES = 4;
    /** Bytes of the throwaway admin PRS drawn when no admin secret is armed. */
    private static final int THROWAWAY_PRS_BYTES = 32;
    /** Held buckets never exceed {@code n - 1, n, n + 1} (class javadoc). */
    private static final int MAX_HELD = 3;

    private final SecureRandom rng;
    private final byte[] generatorMatch;
    private final byte[] generatorAdmin;
    private final byte[] laneSalt;
    /** Guarded by {@code this}: the held pairs, null = a free slot; scanned by bucket VALUE, never indexed by it. */
    private final Pair[] held = new Pair[MAX_HELD];
    /** Guarded by {@code this}: the newest {@code nowBucket} installed (constructor or {@link #roll}). */
    private long newestNow;

    /**
     * Computes both generators, draws the lane salt and mints {@code nowBucket} and {@code nowBucket + 1}.
     * {@code IllegalArgumentException} naming the parameter: a null {@code prsMatch} or {@code rng}, a
     * {@code nowBucket} outside the domain (class javadoc).
     */
    public DoorShares13(byte[] prsMatch, byte[] prsAdminOrNull, SecureRandom rng, long nowBucket) {
        if (prsMatch == null) {
            throw new IllegalArgumentException("prsMatch");
        }
        if (rng == null) {
            throw new IllegalArgumentException("rng");
        }
        requireBucket(nowBucket);
        this.rng = rng;
        byte[] ci = Handshake13.ci();
        this.generatorMatch = HandshakeCrypto.generator(prsMatch, ci);
        if (prsAdminOrNull != null) {
            this.generatorAdmin = HandshakeCrypto.generator(prsAdminOrNull, ci);
        } else {
            byte[] throwaway = new byte[THROWAWAY_PRS_BYTES];
            rng.nextBytes(throwaway);
            this.generatorAdmin = HandshakeCrypto.generator(throwaway, ci);
            Arrays.fill(throwaway, (byte) 0);
        }
        this.laneSalt = new byte[Handshake13.SALT_BYTES];
        rng.nextBytes(laneSalt);
        held[0] = mint(nowBucket);
        held[1] = mint(nowBucket + 1);
        newestNow = nowBucket;
    }

    /**
     * One bucket's door half: {@code bM}, {@code bA} (two independent scalars, never one serving both kinds) and
     * {@code ybM}, {@code ybA}. The accessors return the arrays THEMSELVES (no copy: the reader thread calls them per
     * F2) — callers never write them; an erase zeroes {@code bM} / {@code bA} in place. {@code bM()} / {@code bA()}
     * are for the rows: the door never multiplies with them itself, it opens through {@link DoorShares13#open} /
     * {@link DoorShares13#openWraps}.
     */
    public static final class Pair {
        private final long bucket;
        private final byte[] bM, bA, ybM, ybA;

        private Pair(long bucket, byte[] bM, byte[] bA, byte[] ybM, byte[] ybA) {
            this.bucket = bucket;
            this.bM = bM;
            this.bA = bA;
            this.ybM = ybM;
            this.ybA = ybA;
        }

        public long bucket() {
            return bucket;
        }

        public byte[] bM() {
            return bM;
        }

        public byte[] bA() {
            return bA;
        }

        public byte[] ybM() {
            return ybM;
        }

        public byte[] ybA() {
            return ybA;
        }

        /** Constant text: never a scalar, a share or the bucket. */
        @Override
        public String toString() {
            return "DoorShares13.Pair";
        }

        private void erase() {
            Arrays.fill(bM, (byte) 0);
            Arrays.fill(bA, (byte) 0);
        }
    }

    /** The pair minted for {@code bucket}, or {@code null} (never minted, or erased). Any thread; NEVER mints. */
    public synchronized Pair pairOrNull(long bucket) {
        for (Pair p : held) {
            if (p != null && p.bucket == bucket) {
                return p;
            }
        }
        return null;
    }

    /**
     * What {@link #open} found. Read {@link #pairErased()} FIRST: when it is true the open proved nothing and
     * {@link #openedOrNull()} is {@code null} whatever the envelope held (so a caller that forgets the first read
     * treats it as a wrong secret — never as an admit).
     */
    public static final class OpenResult {
        private static final OpenResult ERASED = new OpenResult(null, true);
        private final Handshake13.Opened opened;
        private final boolean pairErased;

        private OpenResult(Handshake13.Opened opened, boolean pairErased) {
            this.opened = opened;
            this.pairErased = pairErased;
        }

        /** True = the pair was erased before the open finished: no verdict, no reply (the caller counts it). */
        public boolean pairErased() {
            return pairErased;
        }

        /** The codec's result on an intact pair; {@code null} = no wrap opened, OR the pair was erased. */
        public Handshake13.Opened openedOrNull() {
            return opened;
        }

        /** Constant text: never a root, a cid or a kind. */
        @Override
        public String toString() {
            return "DoorShares13.OpenResult";
        }
    }

    /**
     * The door's ONE way to open an F2 against {@code pair}: {@link Handshake13#openHelloOrNull}
     * over the pair's own scalars and this object's lane salt, then — WHATEVER the open returned — the pair must
     * still be the held pair of its bucket, else {@link OpenResult#pairErased()}. WHY the check is unconditional: an
     * erase zeroes the scalars in place, and an open that ran on zeroed scalars proves NOTHING — the key it derives
     * is {@code X25519(0, Ya)}, which a sender computes from its OWN share with no secret, so an envelope sealed
     * under it opens (row S8 pins that premise). {@link #pairOrNull} and the erase share this object's monitor, so a
     * pair still held AFTER the open was intact for every read the open made. READER THREAD, outside the door lock;
     * the X25519s run outside this object's monitor. Throws as {@link Handshake13#openHelloOrNull} does (a caller
     * bug, e.g. a null {@code mixesM}); a null {@code pair} = {@code IllegalArgumentException("pair")}.
     *
     */
    public OpenResult open(Pair pair, Handshake13.Seal13 seal, byte[][] mixesM) {
        if (pair == null) {
            throw new IllegalArgumentException("pair");
        }
        Handshake13.Opened opened =
                Handshake13.openHelloOrNull(seal, pair.bM, pair.bA, pair.ybM, pair.ybA, laneSalt, mixesM);
        if (pairOrNull(pair.bucket) != pair) {
            return OpenResult.ERASED;
        }
        return new OpenResult(opened, false);
    }

    /**
     * What {@link #openWraps} found — {@link OpenResult}'s twin over raw wraps. Read {@link #pairErased()} FIRST: when
     * it is true the open proved nothing and {@link #wrapsOrNull()} is {@code null} whatever the wraps held (so a
     * caller that forgets the first read treats it as a wrong secret — never as a pass).
     *
     */
    public static final class OpenWrapsResult {
        private static final OpenWrapsResult ERASED = new OpenWrapsResult(null, true);
        private final Handshake13.DoorWraps wraps;
        private final boolean pairErased;

        private OpenWrapsResult(Handshake13.DoorWraps wraps, boolean pairErased) {
            this.wraps = wraps;
            this.pairErased = pairErased;
        }

        /** True = the pair was erased before the open finished: no verdict, no reply, no revoke (the caller counts it). */
        public boolean pairErased() {
            return pairErased;
        }

        /**
         * The codec's verdict on an intact pair — the kind, the token index, {@code ck} and the root; {@code null} = no
         * wrap opened, OR the pair was erased. The caller opens the body under its {@code ck}, then zeroes {@code ck}.
         */
        public Handshake13.DoorWraps wrapsOrNull() {
            return wraps;
        }

        /** Constant text: never a key, a root or a kind. */
        @Override
        public String toString() {
            return "DoorShares13.OpenWrapsResult";
        }
    }

    /**
     * The door's ONE way to open a VCHK13's wraps against {@code pair}
     * ({@link #open}'s twin for an envelope that is not an F2): {@code ADb = Handshake13.adb(cookie, the pair's shares,
     * this object's lane salt)}, then {@link Handshake13#openWrapsOrNull} over the pair's own scalars, then — WHATEVER
     * it returned — the SAME unconditional re-check as {@link #open} (an erase zeroes the scalars in place, and an open
     * on zeroed scalars proves nothing; row DS-V1). {@code ada} is the caller's, cut by
     * {@code VariantCheckFrameCodec} ({@code 'V'} purpose). It opens NO body. On an intact pair the caller owns the
     * returned wraps' {@code ck} + root (and zeroes them); on an ERASED pair this method zeroes an opened {@code ck} +
     * root itself before it returns the erased result (whose {@code wrapsOrNull()} is null, so no caller could)
     * (like the intact path's zeroing, no row reads it).
     *
     * READER THREAD, outside the door lock; the X25519s run outside this object's monitor. Network arrays ({@code ya},
     * {@code wrapM}, {@code wrapA}, {@code ada}) null or the wrong length = no wrap opened, never a throw. A caller bug
     * throws {@code IllegalArgumentException}: a null {@code pair} ({@code "pair"}); a {@code cookie} outside 1..32 B
     * ({@code "cookie"}, from {@code adb}); a null / empty {@code mixesM} or a null entry ({@code "mixesM"}, from
     * {@code openWrapsOrNull}).
     *
     */
    public OpenWrapsResult openWraps(Pair pair, byte[] ya, byte[] wrapM, byte[] wrapA, byte[] ada, byte[] cookie,
            byte[][] mixesM) {
        if (pair == null) {
            throw new IllegalArgumentException("pair");
        }
        byte[] adb = Handshake13.adb(cookie, pair.ybM, pair.ybA, laneSalt);
        Handshake13.DoorWraps wraps = Handshake13.openWrapsOrNull(ya, wrapM, wrapA, ada, adb, pair.bM, pair.bA,
                pair.ybM, pair.ybA, mixesM);
        if (pairOrNull(pair.bucket) != pair) {
            // The ERASED arm drops the wraps, so it zeroes their keys first
            if (wraps != null) {
                Arrays.fill(wraps.ck(), (byte) 0);
                Arrays.fill(wraps.root(), (byte) 0);
            }
            return OpenWrapsResult.ERASED;
        }
        return new OpenWrapsResult(wraps, false);
    }

    /**
     * The timer thread's per-tick call: makes sure {@code nowBucket} and {@code nowBucket + 1} are held and erases
     * every bucket {@code < nowBucket - 1}. The missing pairs are minted OUTSIDE the monitor and installed under it.
     * Idempotent (a second call in the same bucket mints nothing); a clock that goes BACKWARDS ({@code nowBucket}
     * below the newest one installed) mints nothing and erases nothing. {@code IllegalArgumentException} for a
     * {@code nowBucket} outside the domain (class javadoc).
     */
    public void roll(long nowBucket) {
        requireBucket(nowBucket);
        boolean needNow;
        boolean needNext;
        synchronized (this) {
            if (nowBucket <= newestNow) {
                return;
            }
            needNow = pairOrNull(nowBucket) == null;
            needNext = pairOrNull(nowBucket + 1) == null;
        }
        Pair now = needNow ? mint(nowBucket) : null;
        Pair next = needNext ? mint(nowBucket + 1) : null;
        synchronized (this) {
            if (nowBucket <= newestNow) {
                // another roll installed this bucket (or a newer one) while we minted: ours are surplus
                eraseIfPresent(now);
                eraseIfPresent(next);
                return;
            }
            for (int i = 0; i < held.length; i++) {
                if (held[i] != null && held[i].bucket < nowBucket - 1) {
                    held[i].erase();
                    held[i] = null;
                }
            }
            install(now);
            install(next);
            newestNow = nowBucket;
        }
    }

    /** A copy of the per-serve {@value Handshake13#SALT_BYTES} B lane salt, fixed for this object's life. */
    public byte[] laneSalt() {
        return Arrays.copyOf(laneSalt, laneSalt.length);
    }

    /** One admit's auth share: the scalar {@code b″} and {@code Yb″ = b″·G_a}. */
    public static final class AuthShare {
        private final byte[] scalar, share;

        private AuthShare(byte[] scalar, byte[] share) {
            this.scalar = scalar;
            this.share = share;
        }

        public byte[] scalar() {
            return scalar;
        }

        public byte[] share() {
            return share;
        }

        /** Constant text: never the scalar or the share. */
        @Override
        public String toString() {
            return "DoorShares13.AuthShare";
        }
    }

    /**
     * A fresh {@code b″} and {@code Yb″ = b″·G_a} — one {@code scalarMult}, any thread, never under this
     * object's monitor. With no admin secret armed {@code G_a} is the throwaway generator (class javadoc).
     */
    public AuthShare newAuthShare() {
        byte[][] scalarAndShare = scalarAndShare(generatorAdmin);
        return new AuthShare(scalarAndShare[0], scalarAndShare[1]);
    }

    /** Buckets held now: 2 or 3 ({@code nowBucket + 1}, {@code nowBucket}, and {@code nowBucket - 1} when it was held) — the rows' observable and a later meter's. */
    public synchronized int held() {
        int n = 0;
        for (Pair p : held) {
            if (p != null) {
                n++;
            }
        }
        return n;
    }

    // --- internals -------------------------------------------------------------------------------------------

    private Pair mint(long bucket) {
        byte[][] m = scalarAndShare(generatorMatch);
        byte[][] a = scalarAndShare(generatorAdmin);
        return new Pair(bucket, m[0], a[0], m[1], a[1]);
    }

    /** {@code {scalar, scalar·g}}; a JDK-refused product draws again (at most {@value #MINT_TRIES} tries). */
    private byte[][] scalarAndShare(byte[] g) {
        for (int i = 0; i < MINT_TRIES; i++) {
            byte[] scalar = HandshakeCrypto.newScalar(rng);
            byte[] share = HandshakeCrypto.scalarMult(scalar, g);
            if (share != null) {
                return new byte[][] {scalar, share};
            }
            Arrays.fill(scalar, (byte) 0);
        }
        throw new IllegalStateException("hs13 share");
    }

    /** Caller holds {@code this}; {@code p} null = already held. Three slots suffice (class javadoc). */
    private void install(Pair p) {
        if (p == null) {
            return;
        }
        for (int i = 0; i < held.length; i++) {
            if (held[i] == null) {
                held[i] = p;
                return;
            }
        }
        p.erase();
        throw new IllegalStateException("hs13 share table");
    }

    private static void eraseIfPresent(Pair p) {
        if (p != null) {
            p.erase();
        }
    }

    private static void requireBucket(long nowBucket) {
        if (nowBucket == Long.MIN_VALUE || nowBucket == Long.MAX_VALUE) {
            throw new IllegalArgumentException("nowBucket");
        }
    }
}
