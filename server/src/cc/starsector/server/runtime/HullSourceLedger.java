package cc.starsector.server.runtime;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The per-tick hull-SOURCE ledger behind the
 * {@code src=} fields on the server's {@code dmg-taken} / {@code pilot-dmg-taken}
 * {@code type=hulldelta|armordelta} rows.
 *
 * <p>The rows are a POST-STEP hull DIFF ({@code EngineCombatBinding.observeHull}) and carry no
 * source by construction; the source exists only at the engine's {@code DamageListener} callback
 * ({@code Ship.applyDamage} → {@code reportDamageApplied(source, target,
 * result)}, {@code target.getParamAboutToApplyDamage()} = the projectile / beam / arc / system
 * param). {@link ArcHitServeProbe} already proxies that callback for EVERY damage event (one
 * cached invoke + one {@code isInstance} on the hot path) — this ledger rides the SAME callback
 * ({@link #record}: two more cached invokes, ship targets only) instead of a second listener, and
 * the binding joins on victim IDENTITY when it prints a row ({@link #describe}).
 *
 * <p>Tick window = exactly the diff window: everything recorded after the previous observation
 * pass and before this one ({@link #endTick} clears at the end of each pass; the engine step
 * and the serve loop share one thread, so no epoch bookkeeping is needed — a catch-up pass over
 * several steps folds them all, as the diff does).
 *
 * <p>Row grammar (appended fields, key-lookup consumers — {@code RowKv.psm1}):
 * <pre>
 *   src=&lt;S&gt;/&lt;kind[:id]&gt; srcN=&lt;reports&gt; [srcMulti=&lt;distinct sources&gt;] srcHull=&lt;Σ&gt; srcArmor=&lt;Σ&gt;
 *   src=script srcN=0     — the listener is live and NO hull/armor-touching report named this
 *                           victim in the window = a DIRECT hull write (the {@code setHitpoints}
 *                           class: vortex decay {@code ShroudedVortexAI}, fragment cost
 *                           {@code BaseFragmentMissileEffect}, {@code DisintegratorEffect})
 *                           = THE tell for a script-side hull write.
 *   src=blind             — the listener is not installed / went quiet (lane blind, never "script").
 *   type=shielded … src=&lt;S&gt;/&lt;kind[:id]&gt; srcN=&lt;reports&gt; [srcMulti=…] srcShield=&lt;Σ&gt;
 *                         — knob cc.server.shieldedRow only: a shield-touching
 *                           report named the victim and no diff / hull report outranked it.
 *   … srcArmor=&lt;Σ&gt; hitCell=&lt;ord,…&gt; hitCellPre=&lt;f,…&gt; hitMaskPre=&lt;f,…&gt; hitArmor=&lt;f,…&gt; hitHull=&lt;f,…&gt; hitKind=&lt;k,…&gt; [hitMore=&lt;n&gt;]
 *                         — knob cc.server.hitCellRow only: one
 *                           comma-list slot per srcN report, report order, ≤ {@link #HIT_SLOT_CAP} then
 *                           hitMore= (dropped slots). ord = x·h + y (the ArmorCellRetainer ordinal law =
 *                           the client's cell=), Pre = the cell / 21-cell footprint armor BEFORE the hit
 *                           ({@link #notePreHit}, the DamageTakenModifier tap), Armor/Hull = that report's
 *                           own amounts (0.0 on the untouched side); -1 / NaN / NaN = no pre-hit (unmapped).
 *                           hitKind = that report's param class, one char per slot ({@link #kindOf}: n none ·
 *                           s sys · a arc · b beam · m missile · p proj · h ship · o obj) — the row-level src=
 *                           kind names only the LAST report, so without it a grader pairs a projectile hit
 *                           with a same-cell beam tick.
 *                           A mapped slot's kind is classified at TAP time ({@link #notePreHit}'s param),
 *                           an unmapped one's at {@link #record} time; k nested — an outer pre-hit was still
 *                           pending (the kill edge's inner applyDamage/randomDamage,
 *                           which clear the param before the OUTER report); never pairable.
 *
 * </pre>
 * {@code S} is the binding's label for the LAST hull-touching source (wire id | {@code -} no ship
 * | {@code piece} a hulk clone | {@code ?}); {@code kind} ∈ {@code proj|missile|beam|arc|sys|ship|
 * none|obj} with the weapon id (else projectile spec id) where the API carries one. Shield-only
 * reports never name a hull source. Sums are the listener's {@code getDamageToHull} /
 * {@code getTotalDamageToArmor} — {@code dealt − srcHull} on a row = the unreported (direct-write)
 * share of a mixed tick.
 *
 * <p>Fail-soft throughout: a throwing label / kind read degrades the field, never the row.
 */
public final class HullSourceLedger {

    public static final String SRC_SCRIPT = "script";
    public static final String SRC_BLIND = "blind";
    /** Longest id / label token printed (weapon ids are short; a script's param string may not be). */
    public static final int TOKEN_MAX = 40;
    /** Victims retained per window — a guard against a pathological event flood, never reached in play. */
    static final int VICTIM_CAP = 4096;
    /** Pending pre-hits per victim (the LIFO stack; a 9th evicts the OLDEST, counted). */
    public static final int HIT_PENDING_CAP = 8;
    /** Hit* slots printed per row; the rest counted as {@code hitMore=}. */
    public static final int HIT_SLOT_CAP = 16;

    private static final String BEAM_API = "com.fs.starfarer.api.combat.BeamAPI";
    private static final String MISSILE_API = "com.fs.starfarer.api.combat.MissileAPI";
    private static final String PROJECTILE_API = "com.fs.starfarer.api.combat.DamagingProjectileAPI";
    private static final String ARC_API = "com.fs.starfarer.api.combat.EmpArcEntityAPI";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String WEAPON_API = "com.fs.starfarer.api.combat.WeaponAPI";
    private static final String SHIPWIDE_AI_FLAGS = "com.fs.starfarer.api.combat.ShipwideAIFlags";

    private static final class Entry {
        Object lastSource;
        Object lastParam;
        int reports;
        int distinctSources;
        float hull;
        float armor;
        float shields;
        /** Hull+armor of SELF-sourced reports ({@code source == victim}). */
        float self;
        /** The HULL part of {@link #self} (the crit DAMAGE amount). */
        float selfHull;
        /** The hit* slots, one per hull/armor-touching report (armed only; null = none). */
        HitSlots hits;
    }

    /**
     * One victim's pending pre-hits — a LIFO stack of {@link #HIT_PENDING_CAP} primitive
     * slots (the kill edge NESTS its eleven {@code applyDamage} calls inside the outer hit,
     * so the newest pre-hit belongs to the
     * next report).
     */
    private static final class HitStack {
        final int[] cell = new int[HIT_PENDING_CAP];
        final float[] pre = new float[HIT_PENDING_CAP];
        final float[] mask = new float[HIT_PENDING_CAP];
        /** Each pre-hit's TAP-time {@link HullSourceLedger#kindOf} char — the kill edge
         *  clears the param before the OUTER report, so a report-time read there is wrong. */
        final char[] kind = new char[HIT_PENDING_CAP];
        int n;
    }

    /** A row's aligned hit* lists (report order). */
    private static final class HitSlots {
        final int[] cell = new int[HIT_SLOT_CAP];
        final float[] pre = new float[HIT_SLOT_CAP];
        final float[] mask = new float[HIT_SLOT_CAP];
        final float[] armor = new float[HIT_SLOT_CAP];
        final float[] hull = new float[HIT_SLOT_CAP];
        /** Each stored slot's kind char (the {@code hitKind=} list; {@link HullSourceLedger#appendHit}). */
        final char[] kind = new char[HIT_SLOT_CAP];
        int n;
        int more;
    }

    /** This window's victims (identity-keyed engine objects; cleared by {@link #endTick}). */
    private final Map<Object, Entry> current = new IdentityHashMap<>();
    /**
     * The SHIELD window — victims a shield-touching report named this
     * tick, kept apart from {@link #current} so the hull rows' law ("a shield-only report never
     * names a hull source") stays byte-identical. Armed by the {@code cc.server.shieldedRow}
     * INSTRUMENT knob only ({@link #armShieldWindow}); unarmed, {@link #record} never reads the
     * shield sum and this map stays empty. The binding prints {@code type=shielded} for a victim
     * here whose hull/armor diffs did not move and whom no hull report named (the god-mode arena
     * seat holding its shield up: hull mult 1e-6 rounds every hull report to 0, the shield report
     * does not; a beam or a DEM payload at it becomes decidable).
     */
    private final Map<Object, Entry> shielded = new IdentityHashMap<>();
    private boolean shieldWindow;
    private boolean live;
    private long reports;
    private long shieldReports;
    private long attributed;
    private long unattributed;
    private long blindRows;
    private long shieldedRows;
    private long dropped;
    /**
     * Pending PRE-hits per victim — a SEPARATE identity map, never
     * {@link #current} (a pending entry there would flip {@link #reported} and mint a spurious
     * {@code type=absorbed} row). Armed by {@code cc.server.hitCellRow} only ({@link #armHitCell});
     * unarmed, {@link #notePreHit} is a no-op and no slot is ever appended (rows byte-identical). Meter
     * law: taps = paired (popped by a report) + unpaired (dropped at {@link #endTick}) + overflow (evicted
     * at {@link #HIT_PENDING_CAP} / refused at {@link #VICTIM_CAP}) + still pending.
     *
     */
    private final Map<Object, HitStack> hitPending = new IdentityHashMap<>();
    private boolean hitCellArmed;
    private long hitTaps;
    private long hitPaired;
    private long hitUnpaired;
    private long hitOverflow;

    // Param-kind resolution: lazy, from the first non-String param's loader; a miss ⇒ obj:<SimpleName>.
    private boolean kindsResolved;
    /** {@link #kindOf}'s one fault line was printed (the ledger has no fault meter). */
    private boolean kindFaultLogged;
    private Class<?> beamApi;
    private Class<?> missileApi;
    private Class<?> projectileApi;
    private Class<?> arcApi;
    private Class<?> shipApi;
    private Method beamGetWeapon;
    private Method projGetWeapon;
    private Method projGetSpecId;
    private Method weaponGetId;
    // DEM payload beams are fired by an FX drone (DEMScript.createFXDrone)
    // the wire never named, so their rows read src=?/beam:<payload>. The drone
    // carries its parent twice — getDroneSource() and the DRONE_MOTHERSHIP AI flag
    // (set by DEMScript) — the label unwraps to the parent BEFORE the binding's sourceLabel.
    private Method shipIsDrone;
    private Method shipGetDroneSource;
    private Method shipGetAIFlags;
    private Method aiFlagsGetCustom;
    private Object droneMothershipFlag;

    /** The feeding listener is installed: an empty window now means "no report", not "blind". */
    public void markLive() {
        live = true;
    }

    /** The feeding listener went quiet (fail-soft): rows read {@code src=blind} from here. */
    public void markBlind() {
        live = false;
    }

    public boolean live() {
        return live;
    }

    /** Arm / disarm the shield window (the {@code cc.server.shieldedRow} knob). */
    public void armShieldWindow(boolean on) {
        shieldWindow = on;
        if (!on) {
            shielded.clear();
        }
    }

    /** Whether the feeder should pay the shield-sum read at all (unarmed = the hot path without that read). */
    public boolean shieldWindowArmed() {
        return shieldWindow;
    }

    /**
     * Arm / disarm the hit* tokens (the {@code cc.server.hitCellRow} knob). A disarm
     * drops the pending pre-hits (counted unpaired).
     */
    public void armHitCell(boolean on) {
        hitCellArmed = on;
        if (!on) {
            dropPendingHits();
        }
    }

    public boolean hitCellArmed() {
        return hitCellArmed;
    }

    /**
     * Sim thread, from the {@code DamageTakenModifier} tap BEFORE the engine applies the
     * hit ({@code Ship.applyDamage} calls it before {@code applyDamageInner}): push the
     * victim's pre-hit cell ({@code ord} = x·h + y, {@code -1} off-grid) with the cell's and the 21-cell
     * footprint's armor as it stood. The next {@link #record} for this victim pops it (LIFO). A full stack
     * evicts its OLDEST entry (a leaked bottom never mis-pairs the live top); a new victim past
     * {@link #VICTIM_CAP} is refused — both counted {@code hit_cell_overflow}. Unarmed = no-op.
     *
     * <p>{@code param} = the tap's {@code args[0]}, classified HERE into the slot's
     * {@code kind} — the tap runs before the kill edge nests its inner hits, whose first report clears
     * the param, so the OUTER report's own param reads null.
     */
    public void notePreHit(Object victim, Object param, int ord, float cellPre, float maskPre) {
        if (!hitCellArmed || victim == null) {
            return;
        }
        hitTaps++;
        HitStack s = hitPending.get(victim);
        if (s == null) {
            if (hitPending.size() >= VICTIM_CAP) {
                hitOverflow++;
                return;
            }
            s = new HitStack();
            hitPending.put(victim, s);
        }
        if (s.n == HIT_PENDING_CAP) {
            hitOverflow++;
            System.arraycopy(s.cell, 1, s.cell, 0, HIT_PENDING_CAP - 1);
            System.arraycopy(s.pre, 1, s.pre, 0, HIT_PENDING_CAP - 1);
            System.arraycopy(s.mask, 1, s.mask, 0, HIT_PENDING_CAP - 1);
            System.arraycopy(s.kind, 1, s.kind, 0, HIT_PENDING_CAP - 1);
            s.n--;
        }
        s.cell[s.n] = ord;
        s.pre[s.n] = cellPre;
        s.mask[s.n] = maskPre;
        s.kind[s.n] = kindOf(param);
        s.n++;
    }

    /** {@link #notePreHit(Object, Object, int, float, float)} with no param (the slot's kind reads {@code n}). */
    public void notePreHit(Object victim, int ord, float cellPre, float maskPre) {
        notePreHit(victim, null, ord, cellPre, maskPre);
    }

    /**
     * Load + initialise {@link HitStack} and {@link HitSlots} at the tap's install instead of inside
     * the first armed hit — one measured first armed hit carried 11.2 ms of their cold loads. One of each
     * is allocated, one element of every array touched, both discarded: NO meter moves, no pending slot, no window
     * entry.
     */
    public void preloadHitSlots() {
        preloadHitSlots(null);
    }

    /** As {@link #preloadHitSlots()}, and with a non-null API object (the tap's live ship) also runs the one-time
     *  {@link #resolveKinds} on ITS loader here — the kind is classified at TAP time inside
     *  {@code Ship.applyDamage}, so without this the first armed hit would pay the class resolves inside the tap's
     *  cost meter. Reads nothing back; a resolve miss logs its once-line here instead. */
    public void preloadHitSlots(Object apiObject) {
        HitStack s = new HitStack();
        HitSlots h = new HitSlots();
        s.cell[0] = h.cell[0];
        s.pre[0] = h.pre[0] + h.armor[0];
        s.mask[0] = h.mask[0] + h.hull[0];
        s.kind[0] = h.kind[0];
        if (apiObject != null) {
            kindOf(apiObject);
        }
    }

    /** The victim's stack with its popped slot at index {@code n}, or {@code null} = nothing pending.
     *  the popped slot is NESTED when {@code n > 0} after the pop (an outer pre-hit still pending beneath it). */
    private HitStack popPreHit(Object victim) {
        HitStack s = hitPending.get(victim);
        if (s == null || s.n == 0) {
            return null;
        }
        s.n--;
        hitPaired++;
        return s;
    }

    /**
     * The victim's report threw before {@link #record} — drop ITS pre-hit (the top),
     * counted {@code hit_cell_unpaired}, so it never sits above an outer hit's and mis-pairs that report.
     */
    public void discardPreHit(Object victim) {
        HitStack s = hitCellArmed && victim != null ? hitPending.get(victim) : null;
        if (s != null && s.n > 0) {
            s.n--;
            hitUnpaired++;
        }
    }

    /**
     * Appends one slot to the entry's hit* lists: the popped pre-hit ({@code from} at index {@code n}) or
     * the unmapped {@code -1}/NaN/NaN, and its kind — {@code k} when {@code nested}, else the
     * popped pre-hit's TAP-time kind, else (unmapped) the report's own {@link #kindOf}{@code (param)};
     * past {@link #HIT_SLOT_CAP} only {@code more} counts (a dropped slot is never classified).
     *
     */
    private void appendHit(Entry e, HitStack from, boolean nested, float armor, float hull, Object param) {
        HitSlots h = e.hits;
        if (h == null) {
            h = new HitSlots();
            e.hits = h;
        }
        if (h.n >= HIT_SLOT_CAP) {
            h.more++;
            return;
        }
        int i = h.n++;
        h.cell[i] = from == null ? -1 : from.cell[from.n];
        h.pre[i] = from == null ? Float.NaN : from.pre[from.n];
        h.mask[i] = from == null ? Float.NaN : from.mask[from.n];
        h.armor[i] = armor;
        h.hull[i] = hull;
        h.kind[i] = nested ? 'k' : from == null ? kindOf(param) : from.kind[from.n];
    }

    private void dropPendingHits() {
        if (hitPending.isEmpty()) {
            return;
        }
        for (HitStack s : hitPending.values()) {
            hitUnpaired += s.n;
        }
        hitPending.clear();
    }

    /**
     * Sim thread, once per {@code reportDamageApplied} with a ship target. A report that touched
     * neither hull nor armor (shield hit, zero result) never names a hull source.
     */
    public void record(Object source, Object victim, Object param, float dmgHull, float dmgArmor) {
        record(source, victim, param, dmgHull, dmgArmor, 0f);
    }

    /**
     * {@link #record(Object, Object, Object, float, float)} plus the report's shield sum: with the
     * shield window armed, a shield-touching report ALSO lands in the shield window (whatever it
     * did to hull or armor — the binding's row precedence decides which window prints). Unarmed,
     * {@code dmgShields} is ignored.
     */
    public void record(Object source, Object victim, Object param, float dmgHull, float dmgArmor,
            float dmgShields) {
        if (victim == null) {
            return;
        }
        // EVERY ship-target report pops its pre-hit HERE, before the
        // zero-damage return below — a shield / zero report must not leave its cell for the next hit.
        HitStack hitTop = hitCellArmed ? popPreHit(victim) : null;
        boolean hitNested = hitTop != null && hitTop.n > 0; // An outer pre-hit still pending
        if (shieldWindow && Float.isFinite(dmgShields) && dmgShields > 0f) {
            Entry s = entryFor(shielded, victim);
            if (s != null) {
                shieldReports++;
                touch(s, source, param);
                s.shields += dmgShields;
            }
        }
        boolean hull = Float.isFinite(dmgHull) && dmgHull > 0f;
        boolean armor = Float.isFinite(dmgArmor) && dmgArmor > 0f;
        if (!hull && !armor) {
            return;
        }
        Entry e = entryFor(current, victim);
        if (e == null) {
            return;
        }
        reports++;
        touch(e, source, param);
        if (hull) {
            e.hull += dmgHull;
        }
        if (armor) {
            e.armor += dmgArmor;
        }
        // The crit's applyDamage(this, ..., this, ...) names
        // the ship as its own source (identity) — summed apart for selfReportedDamage.
        if (source == victim) {
            e.self += (hull ? dmgHull : 0f) + (armor ? dmgArmor : 0f);
            // The hull part apart = the crit DAMAGE amount.
            e.selfHull += hull ? dmgHull : 0f;
        }
        if (hitCellArmed) {
            appendHit(e, hitTop, hitNested, armor ? dmgArmor : 0f, hull ? dmgHull : 0f, param); // one slot per srcN report
        }
    }

    /**
     * The crit-lane branch tell: the hull+armor sum of reports in the
     * CURRENT window whose source IS {@code victim} (identity); {@code 0} when no entry.
     *
     */
    public float selfReportedDamage(Object victim) {
        Entry e = victim == null ? null : current.get(victim);
        return e == null ? 0f : e.self;
    }

    /**
     * The crit-lane DAMAGE AMOUNT: the HULL part only of the
     * CURRENT window's self-sourced reports for {@code victim} (identity); {@code 0} when no entry.
     * The branch TELL stays {@link #selfReportedDamage} (hull+armor); the armor part is the
     * armor-cell lane's, and the client presents DAMAGE through the direct setHitpoints door.
     *
     */
    public float selfReportedHullDamage(Object victim) {
        Entry e = victim == null ? null : current.get(victim);
        return e == null ? 0f : e.selfHull;
    }

    /** The victim's entry in {@code window}, minted under {@link #VICTIM_CAP}; {@code null} = dropped. */
    private Entry entryFor(Map<Object, Entry> window, Object victim) {
        Entry e = window.get(victim);
        if (e == null) {
            if (window.size() >= VICTIM_CAP) {
                dropped++;
                return null;
            }
            e = new Entry();
            window.put(victim, e);
        }
        return e;
    }

    /** Last-writer-wins source/param + the report and distinct-source counts. */
    private static void touch(Entry e, Object source, Object param) {
        if (e.reports == 0 || e.lastSource != source) {
            e.distinctSources++;
        }
        e.reports++;
        e.lastSource = source;
        e.lastParam = param;
    }

    /**
     * Serve thread, from the binding's observation pass for a victim whose row is being printed:
     * the row suffix (leading space). {@code sourceLabel} renders the source object (wire id or
     * the binding's fallbacks); {@code null} or a throw reads {@code ?}.
     */
    public String describe(Object victim, Function<Object, String> sourceLabel) {
        if (!live) {
            blindRows++;
            return " src=" + SRC_BLIND;
        }
        Entry e = victim == null ? null : current.get(victim);
        if (e == null) {
            unattributed++;
            return " src=" + SRC_SCRIPT + " srcN=0";
        }
        attributed++;
        return sourceFields(e, sourceLabel) + " srcHull=" + e.hull + " srcArmor=" + e.armor + hitFields(e.hits);
    }

    /**
     * {@code  hitCell=… hitCellPre=… hitMaskPre=… hitArmor=… hitHull=… hitKind=…[ hitMore=n]} —
     * comma lists, {@code Float.toString} values ({@link StringBuilder#append(float)}); {@code ""} when the
     * row's list is empty (unarmed, or armed after the reports landed).
     * <p>{@code hitKind=} = the slots' stored kind chars ({@link #appendHit}; one per {@code hitCell=}
     * entry, same order; never re-read from {@code lastParam} here), between {@code hitHull=} and
     * {@code hitMore=}.
     */
    private static String hitFields(HitSlots h) {
        if (h == null || h.n == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder(90 + 50 * h.n).append(" hitCell=");
        for (int i = 0; i < h.n; i++) {
            b.append(i == 0 ? "" : ",").append(h.cell[i]);
        }
        appendList(b, " hitCellPre=", h.pre, h.n);
        appendList(b, " hitMaskPre=", h.mask, h.n);
        appendList(b, " hitArmor=", h.armor, h.n);
        appendList(b, " hitHull=", h.hull, h.n);
        b.append(" hitKind=");
        for (int i = 0; i < h.n; i++) {
            b.append(i == 0 ? "" : ",").append(h.kind[i]);
        }
        if (h.more > 0) {
            b.append(" hitMore=").append(h.more);
        }
        return b.toString();
    }

    private static void appendList(StringBuilder b, String key, float[] values, int n) {
        b.append(key);
        for (int i = 0; i < n; i++) {
            b.append(i == 0 ? "" : ",").append(values[i]);
        }
    }

    /**
     * The round-line tail — {@code ""} unarmed (byte-identical round lines), else
     * {@code  hit_cell_taps= hit_cell_paired= hit_cell_unpaired= hit_cell_overflow=} (cumulative; law on
     * {@link #hitPending}).
     */
    public String hitCellMeterTokens() {
        if (!hitCellArmed) {
            return "";
        }
        return " hit_cell_taps=" + hitTaps + " hit_cell_paired=" + hitPaired
                + " hit_cell_unpaired=" + hitUnpaired + " hit_cell_overflow=" + hitOverflow;
    }

    /** Test seam: the victim's pending pre-hit depth. */
    public int hitPendingForTest(Object victim) {
        HitStack s = victim == null ? null : hitPending.get(victim);
        return s == null ? 0 : s.n;
    }

    /**
     * The {@code type=shielded} row's suffix — the shield window's entry for
     * {@code victim}: {@code src=<S>/<kind[:id]> srcN=<reports> [srcMulti=<n>] srcShield=<Σ>}
     * (the listener's {@code getDamageToShields} sum). Blind reads {@code src=blind}; a victim
     * the window never named (the binding asks {@link #shieldReported} first) reads {@code src=?
     * srcN=0}.
     */
    public String describeShield(Object victim, Function<Object, String> sourceLabel) {
        if (!live) {
            blindRows++;
            return " src=" + SRC_BLIND;
        }
        Entry e = victim == null ? null : shielded.get(victim);
        if (e == null) {
            return " src=? srcN=0";
        }
        shieldedRows++;
        return sourceFields(e, sourceLabel) + " srcShield=" + e.shields;
    }

    /** {@code src=<S>/<kind[:id]> srcN=<n> [srcMulti=<n>]} — the part both windows share. */
    private String sourceFields(Entry e, Function<Object, String> sourceLabel) {
        String label;
        try {
            label = sourceLabel == null ? null : sourceLabel.apply(unwrapDroneSource(e.lastSource));
        } catch (RuntimeException failure) {
            label = null;
        }
        return " src=" + sanitize(label == null ? "?" : label) + "/" + describeParam(e.lastParam)
                + " srcN=" + e.reports
                + (e.distinctSources > 1 ? " srcMulti=" + e.distinctSources : "");
    }

    /**
     * Whether a hull/armor-touching report named {@code victim} in the
     * open window — the binding's tell for a hit the post-step diff cannot show (a god-mode seat:
     * hull mult 1e-6 + armor mult 0, so the deltas round to nothing while the listener still
     * reports the source). {@code false} when blind (no listener = no report to speak of).
     */
    public boolean reported(Object victim) {
        return live && victim != null && current.containsKey(victim);
    }

    /**
     * Whether a shield-touching report named {@code victim} in the open
     * window of an ARMED, live ledger — the {@code type=shielded} row's tell (the binding asks
     * after {@link #reported}: a hull report outranks). Unarmed or blind = never.
     */
    public boolean shieldReported(Object victim) {
        return live && shieldWindow && victim != null && shielded.containsKey(victim);
    }

    /** End of the observation pass: both windows close (entries and their engine refs are dropped);
     *  the paired hit* lists go with {@link #current}, unpaired pre-hits are dropped (counted). */
    public void endTick() {
        current.clear();
        shielded.clear();
        dropPendingHits();
    }

    /**
     * A drone source (the DEM payload FX drone, any {@code isDrone()} hull) reads as its parent:
     * {@code getDroneSource()} first, the {@code DRONE_MOTHERSHIP} AI-flag custom second; a
     * non-drone, a null parent or any throw leaves the source untouched (byte-identical rows).
     */
    private Object unwrapDroneSource(Object source) {
        if (source == null) {
            return null;
        }
        try {
            resolveKinds(source.getClass().getClassLoader());
            if (shipApi == null || shipIsDrone == null || !shipApi.isInstance(source)
                    || !Boolean.TRUE.equals(shipIsDrone.invoke(source))) {
                return source;
            }
            Object parent = shipGetDroneSource == null ? null : shipGetDroneSource.invoke(source);
            if (parent == null && shipGetAIFlags != null && aiFlagsGetCustom != null
                    && droneMothershipFlag != null) {
                Object flags = shipGetAIFlags.invoke(source);
                parent = flags == null ? null : aiFlagsGetCustom.invoke(flags, droneMothershipFlag);
            }
            return parent != null && shipApi.isInstance(parent) ? parent : source;
        } catch (Throwable failure) {
            return source; // an unwrap miss never costs the label
        }
    }

    /** Hull/armor-touching reports recorded (cumulative). */
    public long reportCount() {
        return reports;
    }

    /** Rows that named a source. */
    public long attributedCount() {
        return attributed;
    }

    /** Rows that read {@code src=script}. */
    public long unattributedCount() {
        return unattributed;
    }

    /** Rows that read {@code src=blind}. */
    public long blindRowCount() {
        return blindRows;
    }

    /** Reports dropped at {@link #VICTIM_CAP} (either window). */
    public long droppedCount() {
        return dropped;
    }

    /** Shield-touching reports recorded into the armed shield window (cumulative). */
    public long shieldReportCount() {
        return shieldReports;
    }

    /** {@code type=shielded} rows whose suffix named a source. */
    public long shieldedRowCount() {
        return shieldedRows;
    }

    /** Victims in the open window (test seam). */
    public int windowSizeForTest() {
        return current.size();
    }

    /** {@link #describeParam} (test seam — the contract rows live in another package). */
    public String describeParamForTest(Object param) {
        return describeParam(param);
    }

    /** {@link #kindOf} (test seam: the rows pin its agreement with {@link #describeParam}). */
    public char kindOfForTest(Object param) {
        return kindOf(param);
    }

    /**
     * {@code kind[:id]} for the engine's damage param; never throws. The kind is {@link #kindOf}'s char (
     * ONE ladder, so the {@code src=} kind and the {@code hitKind=} char cannot drift); this adds the
     * weapon id, and a throwing id read falls through to {@code obj:<SimpleName>} as before.
     *
     */
    private String describeParam(Object param) {
        char kind = kindOf(param);
        try {
            switch (kind) {
                case 'n':
                    return "none";
                case 's':
                    return "sys:" + sanitize((String) param);
                case 'a':
                    return "arc";
                case 'b':
                    return "beam:" + weaponId(beamGetWeapon, param, null);
                case 'm':
                    return "missile:" + weaponId(projGetWeapon, param, projGetSpecId);
                case 'p':
                    return "proj:" + weaponId(projGetWeapon, param, projGetSpecId);
                case 'h':
                    return "ship";
                default:
                    break;
            }
        } catch (Throwable failure) {
            // fall through to the class name — a weapon-id read must never cost the row
        }
        return "obj:" + sanitize(param.getClass().getSimpleName());
    }

    /**
     * The param-class ladder both the {@code src=} kind ({@link #describeParam}) and the armed {@code hitKind=}
     * char read: {@code n} null · {@code s} String (system) · {@code a} arc · {@code b} beam
     * · {@code m} missile · {@code p} projectile (non-missile: MissileAPI extends DamagingProjectileAPI, so the
     * missile test runs first) · {@code h} ship · {@code o} any other object. No weapon read, no string build,
     * no allocation — ≤ 5 {@code isInstance} (after the one-time {@link #resolveKinds}). A throw reads {@code o}
     * and logs once (the slot and the row are kept).
     * <p>The armed {@code hitKind=} call sites are {@link #notePreHit} (every tapped
     * pre-hit, at TAP time) and {@link #appendHit} (an UNMAPPED stored slot only, at record time); {@code k}
     * (nested) is {@link #appendHit}'s, never this ladder's.
     */
    private char kindOf(Object param) {
        if (param == null) {
            return 'n';
        }
        if (param instanceof String) {
            return 's';
        }
        try {
            resolveKinds(param.getClass().getClassLoader());
            if (arcApi != null && arcApi.isInstance(param)) {
                return 'a';
            }
            if (beamApi != null && beamApi.isInstance(param)) {
                return 'b';
            }
            if (missileApi != null && missileApi.isInstance(param)) {
                return 'm';
            }
            if (projectileApi != null && projectileApi.isInstance(param)) {
                return 'p';
            }
            if (shipApi != null && shipApi.isInstance(param)) {
                return 'h';
            }
        } catch (Throwable failure) {
            if (!kindFaultLogged) {
                kindFaultLogged = true;
                System.out.println("hull-source kind FAIL site=kindOf " + failure.getClass().getSimpleName()
                        + " [reads o; logged once]");
            }
        }
        return 'o';
    }

    private void resolveKinds(ClassLoader loader) {
        if (kindsResolved) {
            return;
        }
        kindsResolved = true;
        ClassLoader l = loader == null ? HullSourceLedger.class.getClassLoader() : loader;
        try {
            Class<?> weaponApi = Class.forName(WEAPON_API, false, l);
            weaponGetId = weaponApi.getMethod("getId");
            projectileApi = Class.forName(PROJECTILE_API, false, l);
            projGetWeapon = projectileApi.getMethod("getWeapon");
            projGetSpecId = projectileApi.getMethod("getProjectileSpecId");
            missileApi = Class.forName(MISSILE_API, false, l);
            beamApi = Class.forName(BEAM_API, false, l);
            beamGetWeapon = beamApi.getMethod("getWeapon");
            arcApi = Class.forName(ARC_API, false, l);
            shipApi = Class.forName(SHIP_API, false, l);
            try {
                shipIsDrone = shipApi.getMethod("isDrone");
                shipGetDroneSource = shipApi.getMethod("getDroneSource");
                shipGetAIFlags = shipApi.getMethod("getAIFlags");
                Class<?> aiFlags = Class.forName(SHIPWIDE_AI_FLAGS, false, l);
                Class<?> flagEnum = Class.forName(SHIPWIDE_AI_FLAGS + "$AIFlags", false, l);
                aiFlagsGetCustom = aiFlags.getMethod("getCustom", flagEnum);
                for (Object constant : flagEnum.getEnumConstants()) {
                    if ("DRONE_MOTHERSHIP".equals(((Enum<?>) constant).name())) {
                        droneMothershipFlag = constant;
                    }
                }
            } catch (Throwable droneMiss) {
                // no drone seams on this API build: sources stay as the engine hands them
                shipIsDrone = null;
            }
        } catch (Throwable miss) {
            // API interfaces absent on this loader (a test JVM): every param reads obj:<SimpleName>
            beamApi = null;
            missileApi = null;
            projectileApi = null;
            arcApi = null;
            shipApi = null;
            // Name the cause — kindOf's own fault line never fires for it. Once per
            // ledger: the kindsResolved latch above runs this body once.
            System.out.println("hull-source kind FAIL site=resolveKinds " + miss.getClass().getSimpleName()
                    + " [API absent on this loader; every slot reads o, src= reads obj:; logged once]");
        }
    }

    private String weaponId(Method getWeapon, Object param, Method getSpecId) throws Exception {
        if (getWeapon != null && weaponGetId != null) {
            Object weapon = getWeapon.invoke(param);
            if (weapon != null) {
                Object id = weaponGetId.invoke(weapon);
                if (id != null) {
                    return sanitize(id.toString());
                }
            }
        }
        if (getSpecId != null) {
            Object specId = getSpecId.invoke(param);
            if (specId != null) {
                return sanitize(specId.toString());
            }
        }
        return "-";
    }

    /** One whitespace-free token: {@code [A-Za-z0-9_.:?-]} kept ({@code ?} = the unknown label), the
     *  rest {@code _}, capped, never empty. */
    public static String sanitize(String s) {
        if (s == null || s.isEmpty()) {
            return "-";
        }
        int n = Math.min(s.length(), TOKEN_MAX);
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == ':' || c == '-' || c == '?';
            b.append(ok ? c : '_');
        }
        return b.toString();
    }
}
