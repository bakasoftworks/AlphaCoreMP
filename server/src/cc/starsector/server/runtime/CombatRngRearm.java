package cc.starsector.server.runtime;

import cc.starsector.server.platform.HeadlessCombatContext.HeadlessCombatContextException;
import cc.starsector.server.platform.WaveShipNamer;
import cc.starsector.server.rngpin.CcRngKeyed;
import cc.starsector.server.rngpin.CcRngRedirect;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The per-round RNG RE-ARM of a pinned serve: every sim-relevant JVM-static {@link Random} is
 * reseeded from the pin's master seed KEYED BY THE ROUND INDEX, at each point a round's state is
 * born.
 *
 * <p><b>Why.</b> The boot pin seeds the global {@code Math.random} generator once, but the
 * warm-up round before it is JIT-timed: it stops on the first compile-quiet pass, so the number
 * of passes (and every draw they make) varies run to run. Round 0's roster is fielded by the LAST
 * warm-up pass's reset, and rounds 1+ by the wrap's reset; a redeploy mints ship ids, seeds each
 * AI's interval phases from {@code Math.random} and shuffles the deploy order. A seed applied
 * once, after all that, can never make two runs of the same recording bit-equal. So the re-arm
 * runs at three sites, each with the round's own seed:
 * <ol>
 *   <li>{@code "warmup"}: immediately before EVERY warm-up pass's reset (round 0) — whichever pass
 *       ends up last fields round 0 from the same state;</li>
 *   <li>{@code "wrap"}: immediately before the wrap's gated reset (round r + 1);</li>
 *   <li>{@code "frame0"}: the top of each round's frame 0, with its OWN salted seed — discards the
 *       wall-clock-variable draws between the reset and the first tick (lobby wait, the round-start
 *       gate, the round-0 warms) without replaying the reset's streams in-round.</li>
 * </ol>
 *
 * <p><b>What is reseeded</b> (each from the round seed {@code s}, with a distinct XOR so no two
 * streams share a seed): the {@code Math.random} holder ({@code s}); the JDK's shared shuffle
 * generator {@code Collections.r} ({@code s ^ 1}); the api's {@code Misc.random} ({@code s ^ 2});
 * the wave namer ({@code s ^ 3}); when the keyed {@code Random.<init>} pin is live in this
 * JVM, the keyed holder's master seed + its per-class construction counts ({@code s}); when
 * the call-site redirects (HOOK-0049 / HOOK-0050) armed, their holder's master seed + per-caller
 * indices ({@code s ^ 4}; the join is main's fold); and the engine's hulk-cut tesselator's
 * static generator ({@code s ^ 5}). WHY the tesselator: {@code Tesselator}
 * draws every hulk cut line (the piece polygons a death fans into {@code HKCG} frames) from ONE
 * static {@code Random} seeded at class init, so its position at a round's frame 0 was whatever
 * the warm-up's cuts left — the pins-ON proof's THIRD seam (tick 1928, the cut's point count
 * 41 / 38 / 45 across three JVMs with every {@code Math.random()} site count identical). Its
 * field name is engine-internal (unstable), so the handle resolves by TYPE: exactly one static {@code Random}
 * on the class, else the arm refuses (a game update that adds a second is a re-read, never a guess).
 *
 * <p><b>Default OFF.</b> With no pin seed configured, {@link #armOrNull()} returns {@code null}
 * and does nothing at all — no reflection, no class init, no reseed — and every {@link #rearm}
 * is a no-op, so the unpinned serve boots and runs byte-identically to before this class.
 * Pinned, a set-but-bad seed THROWS the pin's own {@link IllegalArgumentException} and a handle
 * that cannot be resolved (a missing {@code --add-opens}, an engine without {@code Misc}) THROWS
 * {@link HeadlessCombatContextException}: never a silent unarmed "determinism" run.
 *
 * <p><b>Frame-loop cost</b>: a re-arm is five {@code setSeed}s, one map
 * clear, three static-field reads and one stdout line, at most twice per served round (wrap +
 * frame 0) — never per tick, no added wait. Every handle is resolved once in {@link #armOrNull()}
 * on the boot thread, and with the warm-up on (the default) the same {@link #rearm} body already
 * ran there before the first served frame; only a {@code warmupRound=0} serve (the A/B control)
 * runs it first on round 0's frame 0.
 *
 * <p><b>Isolation</b>: {@link #isolate(Supplier)} brackets a server-side pass
 * that must not touch the round's streams — the wave inflater's engine pass, which builds vanilla
 * CAMPAIGN objects (a fleet, a member, the fit's ship) whose constructors draw {@code Math.random()}
 * and mint ids, at a tick the wall clock decides ({@code WaveLoadoutInflater.warmStep}'s 2 ms budget
 * after a mid-match {@code /wave} switch).: the census pair's draw tallies first differ
 * three ticks after the press, the fighters' interval-paced AI 20 ticks later (the reel's d2
 * divergence). The window snapshots every resolved {@link Random}'s state (seed word + the pending
 * gaussian pair) and restores it after the body, and routes the keyed-constructor and id-redirect
 * holders to their side streams ({@code CcRngKeyed.isolateBegin} / {@code CcRngRedirect.isolateBegin}),
 * so the body's draws and mints leave the live sequences exactly where they were. Faithful: vanilla
 * combat never inflates a fleet mid-battle, so these draws have no gameplay meaning. Unarmed = the
 * body runs bare (the unpinned serve stays byte-identical). Sim thread only, not reentrant (a nested
 * window runs bare inside the outer one).
 *
 * <p>Serve JVM only; the installed game is untouched (an RNG-determinism pin of the same class as
 * the boot pin it extends).
 */
public final class CombatRngRearm {

    /**
     * The derivation id a recording names (the seed scheme of {@link #roundSeed} + {@link #frame0Seed}).
     * A change to that derivation is a NEW id, never a silent re-cut: an old recording's re-sim must be
     * able to refuse a scheme it was not recorded under. Scheme 2: the frame-0 site draws its
     * OWN salted seed — scheme 1 re-used the pre-reset seed at frame 0, so the first in-round ship mint
     * repeated roster ship #0's keyed id (nothing was ever recorded under scheme 1).
     * Scheme 3: the id names the whole reseeded SET, not only the derivation —
     * the interval timers, the hulk-cut tesselator, and the per-pass warm-up salt
     * ({@link #warmupPassSeed}); no shipping recording exists under scheme 2.
     */
    public static final int SCHEME_ID = 3;

    /** The re-arm sites, as the {@code rng_rearm site=} receipt names them. */
    public static final String SITE_WARMUP = "warmup";
    public static final String SITE_WRAP = "wrap";
    public static final String SITE_FRAME0 = "frame0";

    /** SplitMix64 increment (golden ratio) — the same constant the keyed holder uses. */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    /**
     * Salts the frame-0 seed away from the round's pre-reset seed. WHY: both re-arms clear every
     * per-caller index, so with ONE seed per round the first {@code Misc.genUID} after frame 0
     * (a fly-in, a fighter, a wave ship) would mint roster ship #0's id again — the stable id the
     * wire registry interns (a throw) and the engine keys ship-local state on — and every keyed
     * {@code Random} / the Math stream would replay the reset's draws in-round.
     */
    private static final long FRAME0_SALT = 0xA0761D6478BD642FL;
    /**
     * Salts each warm-up pass's seed away from the round's pre-reset seed and from every other pass.
     * WHY: with ONE seed for every pass, each pass's reset re-mints the SAME
     * roster ship ids while the engine's id-keyed custom data survives the reset, so state a dead
     * pass keyed on an id aliases into round 0's ship of that id. The LAST pass's reset fields round
     * 0's roster from its own pass seed, so a re-simulation stays equal because it forces the
     * recorded pass count (a pass-count mismatch is the replay gate's SETUP line).
     */
    private static final long WARMUP_SALT = 0xE7037ED1A0B428DBL;
    private static final String MATH_HOLDER = "java.lang.Math$RandomNumberGeneratorHolder";
    private static final String MATH_FIELD = "randomNumberGenerator";
    private static final String COLLECTIONS_FIELD = "r";
    private static final String MISC_CLASS = "com.fs.starfarer.api.util.Misc";
    private static final String MISC_FIELD = "random";
    /** The engine's hulk-cut tesselator; its generator field's name is engine-internal — resolved by type. */
    public static final String TESSELATOR_CLASS = "com.fs.starfarer.util.Tesselator";
    /** The tesselator stream's salt off the round seed (0..4 are taken above; the contract row pins this value). */
    public static final long TESSELATOR_SALT = 5L;

    private static boolean armed;
    private static long master;
    private static Random mathRandom;
    private static Field collectionsRandom;
    private static Field miscRandomOrNull;
    private static Field tesselatorRandomOrNull;
    private static boolean keyed;
    private static long rearms;
    /** {@code java.util.Random}'s state: {@code seed} ({@link AtomicLong}), {@code nextNextGaussian}, {@code haveNextNextGaussian}. */
    private static Field randomSeed;
    private static Field randomNextNextGaussian;
    private static Field randomHaveNextNextGaussian;
    /** An {@link #isolate} window is open (sim thread; a nested call runs bare). */
    private static boolean isolating;
    private static long isolations;
    private static long isolationsMoved;

    private CombatRngRearm() {
    }

    /**
     * The round's seed: {@code splitMix64(master + (round + 1) * GOLDEN)} — PURE. Round {@code -1}
     * keys the match-wide stream ({@link #forcedWaveSeedOrZero()}); rounds 0.. key the per-round
     * re-arms. Scheme {@value #SCHEME_ID} (the id covers the whole reseeded set: timers, tesselator,
     * the warm-up salt).
     */
    public static long roundSeed(long master, int round) {
        return splitMix64(master + (round + 1L) * GOLDEN);
    }

    /**
     * The frame-0 seed of {@code round}: {@code splitMix64(roundSeed(master, round) ^ FRAME0_SALT)} —
     * PURE, never equal to the round's pre-reset seed (see {@link #FRAME0_SALT}). Scheme {@value #SCHEME_ID}
     * (the id covers the whole reseeded set: timers, tesselator, the warm-up salt).
     */
    public static long frame0Seed(long master, int round) {
        return splitMix64(roundSeed(master, round) ^ FRAME0_SALT);
    }

    /**
     * The seed of warm-up pass {@code pass} (0-based) of {@code round}:
     * {@code splitMix64(roundSeed(master, round) ^ WARMUP_SALT ^ pass)} — PURE; pass 0 included, it
     * is never the plain pre-reset seed {@link #roundSeed} nor the frame-0 seed {@link #frame0Seed}
     * (see {@link #WARMUP_SALT}). Scheme {@value #SCHEME_ID}.
     */
    public static long warmupPassSeed(long master, int round, int pass) {
        return splitMix64(roundSeed(master, round) ^ WARMUP_SALT ^ pass);
    }

    /**
     * Boot (BEFORE the warm-up): resolve the pin's master seed through the SAME key triple the
     * boot pin reads, and when one is set, resolve every handle once and arm.
     *
     * @return {@code null} when no seed is configured (default OFF: no side effect at all), else
     *         the one-line receipt {@code rng_rearm status=armed scheme=1 master=<seed>
     *         handles=math,collections,misc,namer keyed=<bool>}
     * @throws IllegalArgumentException a set-but-unparseable seed (the pin's own message)
     * @throws HeadlessCombatContextException a handle that cannot be resolved — in the serve JVM
     *         {@code Misc} included (the engine is up by then)
     */
    public static String armOrNull() {
        String raw = ServerConfig.resolve(CombatRngPinBoot.SEED_SYSPROP, CombatRngPinBoot.SEED_ENV,
                CombatRngPinBoot.SEED_FILE_KEY, CombatRngPinBoot.UNPINNED);
        String value = raw == null ? CombatRngPinBoot.UNPINNED : raw.trim();
        if (value.isEmpty() || CombatRngPinBoot.UNPINNED.equalsIgnoreCase(value)) {
            return null;
        }
        long seed;
        try {
            seed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(CombatRngPinBoot.SEED_SYSPROP + " must be a long seed or '"
                    + CombatRngPinBoot.UNPINNED + "', got: " + raw, e);
        }
        return arm(seed, true, TESSELATOR_CLASS);
    }

    /**
     * The game-free suite's arm: identical to {@link #armOrNull()} with {@code master} supplied,
     * except that an absent {@code Misc} (a JVM without the game api, where {@code Misc} cannot
     * initialize) or tesselator is reported under {@code missing=} instead of thrown. Never called by the serve.
     */
    public static String armForRows(long master) {
        return arm(master, false, TESSELATOR_CLASS);
    }

    /**
     * {@link #armForRows(long)} with the tesselator handle resolved on {@code tesselatorClassName} instead
     * of the engine's class — the suite drives the by-type resolve on a stand-in (one static {@code Random}
     * under an engine-internal-style name; two, none or no class = the refusal). Never called by the serve.
     */
    public static String armForRows(long master, String tesselatorClassName) {
        return arm(master, false, tesselatorClassName);
    }

    private static String arm(long masterSeed, boolean serveJvm, String tesselatorClassName) {
        // Resolve everything FIRST (no side effect), so a throw leaves the JVM exactly as it was.
        Random math = resolveMathRandom();
        Field shuffle = resolveCollectionsRandom();
        Field[] internals = resolveRandomInternals();
        Throwable miscFailure = null;
        Field misc = null;
        try {
            misc = resolveMiscRandom();
        } catch (Throwable t) { // NoClassDefFound / ExceptionInInitializer included: reported below, once
            miscFailure = t;
        }
        if (misc == null && serveJvm) {
            throw new HeadlessCombatContextException("rng_rearm: " + MISC_CLASS + "." + MISC_FIELD
                    + " did not resolve in the serve JVM (the engine is up by now) — refusing an"
                    + " unarmed pinned run", miscFailure);
        }
        Throwable tesselatorFailure = null;
        Field tesselator = null;
        try {
            tesselator = resolveTesselatorRandom(tesselatorClassName);
        } catch (Throwable t) { // absent class / 0 or 2+ static Randoms / a null generator: reported below, once
            tesselatorFailure = t;
        }
        if (tesselator == null && serveJvm) {
            throw new HeadlessCombatContextException("rng_rearm: " + tesselatorClassName + " static Random"
                    + " did not resolve in the serve JVM (the engine is up by now) — refusing an unarmed"
                    + " pinned run (the hulk-cut stream would stay per-JVM)", tesselatorFailure);
        }
        try {
            // The namer's class init builds its generator; do it here, never on a served frame.
            Class.forName(WaveShipNamer.class.getName(), true, WaveShipNamer.class.getClassLoader());
            // Collections.r is created lazily by the first shuffle: force it now, so every re-arm
            // finds a generator to reseed (a lazy one born mid-round would seed from the clock).
            if (shuffle.get(null) == null) {
                Collections.shuffle(new ArrayList<>(List.of(0, 1)));
            }
            if (!(shuffle.get(null) instanceof Random)) {
                throw new HeadlessCombatContextException("rng_rearm: java.util.Collections.r is not a Random");
            }
        } catch (ReflectiveOperationException e) {
            throw new HeadlessCombatContextException("rng_rearm: arm failed resolving the shuffle or namer handle", e);
        }
        boolean keyedReady = CcRngKeyed.isReady();
        boolean redirectReady = CcRngRedirect.isInited();
        synchronized (CombatRngRearm.class) {
            master = masterSeed;
            mathRandom = math;
            collectionsRandom = shuffle;
            miscRandomOrNull = misc;
            tesselatorRandomOrNull = tesselator;
            randomSeed = internals[0];
            randomNextNextGaussian = internals[1];
            randomHaveNextNextGaussian = internals[2];
            keyed = keyedReady;
            rearms = 0L;
            isolating = false;
            isolations = 0L;
            isolationsMoved = 0L;
            armed = true;
        }
        String missing = (misc == null ? "misc" : "") + (misc == null && tesselator == null ? "," : "")
                + (tesselator == null ? "tesselator" : "");
        String err = (misc == null ? miscFailure.getClass().getSimpleName() : "")
                + (misc == null && tesselator == null ? "," : "")
                + (tesselator == null ? tesselatorFailure.getClass().getSimpleName() : "");
        return "rng_rearm status=armed scheme=" + SCHEME_ID + " master=" + masterSeed
                + " handles=math,collections," + (misc != null ? "misc," : "") + "namer"
                + (tesselator != null ? ",tesselator" : "")
                + " keyed=" + keyedReady + " redirect=" + redirectReady
                + (missing.isEmpty() ? "" : " missing=" + missing + " err=" + err);
    }

    /**
     * Re-arm every handle for {@code round} (0-based) at {@code site} ({@link #SITE_WARMUP} /
     * {@link #SITE_WRAP} = the round's pre-reset seed {@link #roundSeed}; {@link #SITE_FRAME0} = its
     * own {@link #frame0Seed}); a no-op when not armed. Prints ONE {@code rng_rearm round= site= seed=}
     * line — the receipt a pinned serve's log is read for.
     */
    public static void rearm(int round, String site) {
        reseed(round, site, -1);
    }

    /**
     * {@link #rearm(int, String)} for warm-up pass {@code pass} (0-based, {@code >= 0}): the seed is
     * that pass's own {@link #warmupPassSeed}, never the round's plain pre-reset seed; the receipt line
     * names the pass.
     */
    public static void rearm(int round, String site, int pass) {
        if (pass < 0) {
            throw new IllegalArgumentException("rng_rearm: warm-up pass index must be >= 0, got " + pass);
        }
        reseed(round, site, pass);
    }

    /** The one re-arm body; {@code passOrNeg} {@code >= 0} = a warm-up pass's salted seed. */
    private static void reseed(int round, String site, int passOrNeg) {
        long seed;
        synchronized (CombatRngRearm.class) {
            if (!armed) {
                return;
            }
            seed = passOrNeg >= 0 ? warmupPassSeed(master, round, passOrNeg)
                    : SITE_FRAME0.equals(site) ? frame0Seed(master, round) : roundSeed(master, round);
            if (SITE_FRAME0.equals(site)) {
                // The Math.random() caller census window opens with the frame-0
                // stream (a no-op unless the premain armed the census — a diagnostic). ABOVE the reseed:
                // a draw between the reseed and the open would go uncounted.
                CcRngRedirect.censusOpen();
            }
            mathRandom.setSeed(seed);
            try {
                Object shuffle = collectionsRandom.get(null);
                if (shuffle instanceof Random r) {
                    r.setSeed(seed ^ 1L);
                }
                if (miscRandomOrNull != null && miscRandomOrNull.get(null) instanceof Random r) {
                    r.setSeed(seed ^ 2L);
                }
                // The hulk-cut stream: the engine's tesselator never re-seeds its
                // generator itself (its wall-clock re-seed is behind a flag vanilla leaves off), so a round's
                // first cut inherited the warm-up's draw position. Same instance, same seed law as misc.
                if (tesselatorRandomOrNull != null && tesselatorRandomOrNull.get(null) instanceof Random r) {
                    r.setSeed(seed ^ TESSELATOR_SALT);
                }
            } catch (IllegalAccessException e) { // accessibility was granted at arm: a throw is fail-closed
                throw new HeadlessCombatContextException("rng_rearm: a resolved handle became unreadable", e);
            }
            WaveShipNamer.reseed(seed ^ 3L);
            if (keyed) {
                CcRngKeyed.rearm(seed);
            }
            // The join: the call-site redirect holder (HOOK-0049 /
            // HOOK-0050 — ship ids + fighter landing keys) re-keys with the round too. It answers false
            // when the redirects never armed in this JVM (an un-inited holder has nothing to re-key).
            CcRngRedirect.rearm(seed ^ 4L);
            rearms++;
        }
        System.out.println("rng_rearm round=" + round + " site=" + site
                + (passOrNeg >= 0 ? " pass=" + passOrNeg : "") + " seed=" + seed);
    }

    /**
     * The wave loadout seed a pinned serve uses when {@code wave.seed} is blank: a non-zero
     * {@code roundSeed(master, -1)} (round {@code -1} = the match-wide stream; re-derived until
     * non-zero, since 0 is the wave knob's refused value). {@code 0} when not armed — the caller
     * then keeps its random fallback.
     */
    public static synchronized long forcedWaveSeedOrZero() {
        if (!armed) {
            return 0L;
        }
        long s = roundSeed(master, -1);
        while (s == 0L) {
            s = splitMix64(s + GOLDEN);
        }
        return s;
    }

    /** The derivation id a recording's header names; meaningful only when {@link #isArmed()}. */
    public static int schemeId() {
        return SCHEME_ID;
    }

    /** Whether {@link #armOrNull()} armed this JVM. */
    public static synchronized boolean isArmed() {
        return armed;
    }

    /** Re-arms run so far in this JVM (every site; 0 unarmed). */
    public static synchronized long rearmCount() {
        return rearms;
    }

    /**
     * Runs {@code body} inside an ISOLATION window (class javadoc):
     * every resolved stream's state is snapshotted before and restored after (a throw included), and the
     * keyed / redirect holders mint from their side streams meanwhile. Unarmed, or nested inside an open
     * window, the body runs bare. Sim thread only. Cost per window: four reflective reads + compares, no
     * allocation beyond three small arrays — bounded by the row it brackets (an engine pass of ~0.3–1.4 ms).
     *
     * @return the body's value
     * @throws HeadlessCombatContextException a resolved {@code Random} internal became unreadable (fail-closed)
     */
    public static <T> T isolate(Supplier<T> body) {
        Random[] streams;
        synchronized (CombatRngRearm.class) {
            if (!armed || isolating) {
                return body.get();
            }
            streams = resolvedStreams();
            isolating = true;
        }
        int n = streams.length;
        long[] words = new long[n];
        double[] gaussians = new double[n];
        boolean[] haveGaussians = new boolean[n];
        boolean moved = false;
        try {
            for (int i = 0; i < n; i++) {
                words[i] = seedWordOf(streams[i]).get();
                gaussians[i] = randomNextNextGaussian.getDouble(streams[i]);
                haveGaussians[i] = randomHaveNextNextGaussian.getBoolean(streams[i]);
            }
            CcRngKeyed.isolateBegin();
            CcRngRedirect.isolateBegin();
            try {
                return body.get();
            } finally {
                CcRngRedirect.isolateEnd();
                CcRngKeyed.isolateEnd();
                for (int i = 0; i < n; i++) {
                    AtomicLong word = seedWordOf(streams[i]);
                    if (word.get() != words[i]) {
                        moved = true;
                        word.set(words[i]);
                    }
                    if (randomHaveNextNextGaussian.getBoolean(streams[i]) != haveGaussians[i]
                            || randomNextNextGaussian.getDouble(streams[i]) != gaussians[i]) {
                        moved = true;
                        randomNextNextGaussian.setDouble(streams[i], gaussians[i]);
                        randomHaveNextNextGaussian.setBoolean(streams[i], haveGaussians[i]);
                    }
                }
            }
        } catch (IllegalAccessException e) { // accessibility was granted at arm: a throw is fail-closed
            throw new HeadlessCombatContextException("rng_isolate: a resolved Random internal became unreadable", e);
        } finally {
            synchronized (CombatRngRearm.class) {
                isolating = false;
                isolations++;
                if (moved) {
                    isolationsMoved++;
                }
            }
        }
    }

    /** {@link #isolate} windows closed so far in this JVM (0 unarmed; the receipt's {@code rows=}). */
    public static synchronized long isolationCount() {
        return isolations;
    }

    /** Windows whose body MOVED a resolved stream (the state restore did work; the receipt's {@code moved=}). */
    public static synchronized long isolationMovedCount() {
        return isolationsMoved;
    }

    /**
     * The resolved streams' seed words in handle order (math, collections, misc, tesselator — the absent
     * ones skipped) — the contract rows' proof that an {@link #isolate} window left every stream in place.
     *
     * @throws HeadlessCombatContextException when not armed, or a word is unreadable
     */
    public static long[] streamWords() {
        Random[] streams;
        synchronized (CombatRngRearm.class) {
            if (!armed) {
                throw new HeadlessCombatContextException("rng_isolate: streamWords() needs an armed re-arm");
            }
            streams = resolvedStreams();
        }
        long[] words = new long[streams.length];
        try {
            for (int i = 0; i < streams.length; i++) {
                words[i] = seedWordOf(streams[i]).get();
            }
        } catch (IllegalAccessException e) {
            throw new HeadlessCombatContextException("rng_isolate: a resolved Random internal became unreadable", e);
        }
        return words;
    }

    /** The live {@link Random} instances behind the resolved handles (caller holds the class lock). */
    private static Random[] resolvedStreams() {
        List<Random> live = new ArrayList<>(4);
        live.add(mathRandom);
        try {
            if (collectionsRandom.get(null) instanceof Random r) {
                live.add(r);
            }
            if (miscRandomOrNull != null && miscRandomOrNull.get(null) instanceof Random r) {
                live.add(r);
            }
            if (tesselatorRandomOrNull != null && tesselatorRandomOrNull.get(null) instanceof Random r) {
                live.add(r);
            }
        } catch (IllegalAccessException e) {
            throw new HeadlessCombatContextException("rng_rearm: a resolved handle became unreadable", e);
        }
        return live.toArray(new Random[0]);
    }

    private static AtomicLong seedWordOf(Random r) throws IllegalAccessException {
        if (randomSeed.get(r) instanceof AtomicLong word) {
            return word;
        }
        throw new HeadlessCombatContextException("rng_isolate: java.util.Random.seed is not an AtomicLong");
    }

    private static Random resolveMathRandom() {
        try {
            Field field = Class.forName(MATH_HOLDER).getDeclaredField(MATH_FIELD);
            field.setAccessible(true);
            if (field.get(null) instanceof Random r) {
                return r;
            }
            throw new HeadlessCombatContextException("rng_rearm: the Math.random holder did not contain a Random");
        } catch (HeadlessCombatContextException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new HeadlessCombatContextException("rng_rearm: failed to resolve the Math.random holder"
                    + " (requires --add-opens java.base/java.lang=ALL-UNNAMED)", e);
        }
    }

    /**
     * {@code java.util.Random}'s three state fields (JDK 17 and 25 alike: {@code seed}, {@code nextNextGaussian},
     * {@code haveNextNextGaussian}) for {@link #isolate}'s snapshot + restore. Needs the SAME
     * {@code --add-opens java.base/java.util} the shuffle handle needs; a JDK that renames them refuses the arm.
     */
    private static Field[] resolveRandomInternals() {
        try {
            Field seed = Random.class.getDeclaredField("seed");
            Field gaussian = Random.class.getDeclaredField("nextNextGaussian");
            Field haveGaussian = Random.class.getDeclaredField("haveNextNextGaussian");
            if (seed.getType() != AtomicLong.class || gaussian.getType() != double.class
                    || haveGaussian.getType() != boolean.class) {
                throw new HeadlessCombatContextException("rng_isolate: java.util.Random's state fields changed type");
            }
            seed.setAccessible(true);
            gaussian.setAccessible(true);
            haveGaussian.setAccessible(true);
            return new Field[] {seed, gaussian, haveGaussian};
        } catch (HeadlessCombatContextException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new HeadlessCombatContextException("rng_isolate: failed to resolve java.util.Random's state fields"
                    + " (requires --add-opens java.base/java.util=ALL-UNNAMED)", e);
        }
    }

    private static Field resolveCollectionsRandom() {
        try {
            Field field = Collections.class.getDeclaredField(COLLECTIONS_FIELD);
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != Random.class) {
                throw new HeadlessCombatContextException("rng_rearm: java.util.Collections.r is not a static Random");
            }
            field.setAccessible(true);
            return field;
        } catch (HeadlessCombatContextException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new HeadlessCombatContextException("rng_rearm: failed to resolve java.util.Collections.r"
                    + " (requires --add-opens java.base/java.util=ALL-UNNAMED)", e);
        }
    }

    /** Initializes {@code Misc} (its static init reads the game settings); throws when absent. */
    private static Field resolveMiscRandom() throws ReflectiveOperationException {
        Class<?> misc = Class.forName(MISC_CLASS, true, CombatRngRearm.class.getClassLoader());
        Field field = misc.getField(MISC_FIELD);
        if (!Modifier.isStatic(field.getModifiers()) || !(field.get(null) instanceof Random)) {
            throw new IllegalStateException(MISC_CLASS + "." + MISC_FIELD + " is not a static Random");
        }
        return field;
    }

    /**
     * Initializes the hulk-cut tesselator (its static init is fields only: constants, one {@code new
     * Random()} — keyed when HOOK-0044 is live — and a scratch list; no settings read) and finds its ONE
     * static {@link Random} by TYPE, since the field's name is engine-internal. Refuses on an absent class, on
     * 0 or 2+ static {@code Random} fields (never a guess between them) and on a null generator.
     */
    private static Field resolveTesselatorRandom(String className) throws ReflectiveOperationException {
        Class<?> tesselator = Class.forName(className, true, CombatRngRearm.class.getClassLoader());
        Field found = null;
        int count = 0;
        for (Field field : tesselator.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Random.class) {
                count++;
                found = field;
            }
        }
        if (count != 1) {
            throw new IllegalStateException(className + " declares " + count + " static java.util.Random"
                    + " fields; the hulk-cut handle needs exactly one");
        }
        found.setAccessible(true);
        if (!(found.get(null) instanceof Random)) {
            throw new IllegalStateException(className + "." + found.getName() + " holds no Random");
        }
        return found;
    }

    /** SplitMix64 finalizer — copied from the keyed holder (that class is bootstrap-side; this one stays dependency-free). */
    private static long splitMix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
