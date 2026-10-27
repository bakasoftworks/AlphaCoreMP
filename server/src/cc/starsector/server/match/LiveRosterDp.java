package cc.starsector.server.match;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;

/**
 * The LIVE-ROSTER ratio behind the fortress wave's
 * stage-time prefix truncation ({@link WaveSchedule#setLiveRosterRatio}). Game-free by design —
 * every engine/seat read is an injected functional interface, so the contract suite pins the
 * whole fold with static fakes.
 *
 * <p>{@link #ratio()} = {@code clamp(multiplier × live_seat_dp / fullDp, FLOOR, 1.0)}, where
 * {@code multiplier} = the host's {@code match.waveRosterMultiplier} (1.0 = unset = the plain
 * share; the FLOOR and the 1.0 cap bound it either way) and {@code live_seat_dp} sums {@code dp(variant(seat))} over the OCCUPIED seats (live
 * connection — a dead-but-connected seat COUNTS, it respawns; a vacant AFK hull does not), and
 * {@code variant(seat)} = the last FIELDED variant ?? the next-respawn override ?? the
 * descriptor seat row. The fielded store is stale after a vacate (no remove site), which is
 * exactly why the fold iterates OCCUPANCY and only then asks it. A pod id
 * ({@code podExemptIds}) folds 0; an unresolvable id (raw DP NaN / non-positive) folds the
 * descriptor row's DP and counts into {@code unresolved=} — never a sim-thread throw.
 *
 * <p>Residency: read ONCE per wave stage by {@link WaveSchedule#drainDue}, never
 * per tick. Cost = one occupancy read + at most N memoized DP lookups.
 */
public final class LiveRosterDp {

    /**
     * The ratio floor — a FEEL dial, static in
     * v1 (the {@code WAVE_2_FALLBACK_DUE_SEC} precedent, not knob-housed), flight-graded. A lone
     * seat still faces a quarter-size wave (plus the fill's own min-1-row law).
     */
    public static final double FLOOR = 0.25d;

    /** The knob's inclusive ceiling (fortressDp's). */
    public static final int MAX_FULL_DP = 5000;

    /**
     * The {@code match.waveRosterMultiplier} window —
     * the host's scaling modifier on the live-roster share, inclusive [MIN, MAX]; UNIT = blank
     * = the share as computed ({@code 1.0 × x == x}, so the staging is byte-identical to the
     * knob's absence). The window is a typo guard (a stray 0 or an extra digit refuses the
     * boot); the FLOOR and the 1.0 cap still bound the product inside it.
     */
    public static final double MULTIPLIER_MIN = 0.1d;
    public static final double MULTIPLIER_MAX = 10.0d;
    public static final double MULTIPLIER_UNIT = 1.0d;

    /** Plain decimal only: no sign, no exponent, no bare leading point, no locale comma. */
    private static final Pattern MULTIPLIER_TEXT = Pattern.compile("[0-9]+(\\.[0-9]+)?");

    private final Supplier<? extends Collection<Integer>> occupancy;
    private final IntFunction<String> fieldedOrNull;
    private final IntFunction<String> overrideOrNull;
    private final IntFunction<String> descriptorRowOrNull;
    private final ToDoubleFunction<String> rawDpOrNaN;
    private final Set<String> podExemptIds;
    private final int fullDp;
    private final double multiplier;

    // Last-read state (the serve's wave_dp_basis row reads it right after drainDue, same
    // thread). faults = whole-read failures (ratio fell back to 1.0 = the static cap).
    private int lastSeats;
    private double lastSeatDp;
    private int lastUnresolved;
    private long faults;
    private boolean faultLogged;

    /**
     * {@code fullDp} = the resolved
     * {@code match.waveRosterFullDp} (must be in [1, {@link #MAX_FULL_DP}] — the OFF value 0
     * installs no supplier at all, so it is refused here).
     * {@code multiplier} = the resolved {@code match.waveRosterMultiplier}, finite in
     * [{@link #MULTIPLIER_MIN}, {@link #MULTIPLIER_MAX}] else refused;
     * {@link #MULTIPLIER_UNIT} = unset.
     */
    public LiveRosterDp(Supplier<? extends Collection<Integer>> occupancy,
            IntFunction<String> fieldedOrNull, IntFunction<String> overrideOrNull,
            IntFunction<String> descriptorRowOrNull, ToDoubleFunction<String> rawDpOrNaN,
            Set<String> podExemptIds, int fullDp, double multiplier) {
        this.occupancy = Objects.requireNonNull(occupancy, "occupancy");
        this.fieldedOrNull = Objects.requireNonNull(fieldedOrNull, "fieldedOrNull");
        this.overrideOrNull = Objects.requireNonNull(overrideOrNull, "overrideOrNull");
        this.descriptorRowOrNull = Objects.requireNonNull(descriptorRowOrNull,
                "descriptorRowOrNull");
        this.rawDpOrNaN = Objects.requireNonNull(rawDpOrNaN, "rawDpOrNaN");
        this.podExemptIds = Set.copyOf(Objects.requireNonNull(podExemptIds, "podExemptIds"));
        if (fullDp < 1 || fullDp > MAX_FULL_DP) {
            throw new IllegalArgumentException("match.waveRosterFullDp must be in [1, "
                    + MAX_FULL_DP + "] to install the live-roster ratio (0 = OFF installs"
                    + " nothing); got " + fullDp);
        }
        this.fullDp = fullDp;
        if (!(multiplier >= MULTIPLIER_MIN && multiplier <= MULTIPLIER_MAX)) {
            throw new IllegalArgumentException("match.waveRosterMultiplier must be in ["
                    + MULTIPLIER_MIN + ", " + MULTIPLIER_MAX + "]; got " + multiplier);
        }
        this.multiplier = multiplier;
    }

    /**
     * The ONE parse law of {@code match.waveRosterFullDp}
     * — blank/absent/0 = OFF (returns 0); a STRICT whole int in [1, 5000] otherwise;
     * reject-never-clamp (the {@code resolveFortressDpTotal} idiom): anything else THROWS.
     */
    public static int parseFullDp(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("match.waveRosterFullDp must be a whole DP"
                    + " total in [0, " + MAX_FULL_DP + "] (0 = off); got '" + raw + "'");
        }
        if (parsed < 0 || parsed > MAX_FULL_DP) {
            throw new IllegalArgumentException("match.waveRosterFullDp must be in [0, "
                    + MAX_FULL_DP + "] (0 = off); got '" + raw + "'");
        }
        return parsed;
    }

    /**
     * The ONE parse law of
     * {@code match.waveRosterMultiplier}, the host's scaling modifier on the live-roster share
     * (WHY: a lone small ship read the 0.25 FLOOR, so waves were too thin to fight, and the
     * host needs a dial rather than a fixed share). Blank/absent =
     * {@link #MULTIPLIER_UNIT}; otherwise the trimmed text must be a plain decimal (digits, an
     * optional point and digits: no sign, no exponent, no bare {@code .5}, no comma) parsing to
     * a finite value in [{@link #MULTIPLIER_MIN}, {@link #MULTIPLIER_MAX}]. Reject-never-clamp:
     * anything else THROWS naming the key and the window (the echoed text is bounded).
     */
    public static double parseMultiplier(String raw) {
        if (raw == null || raw.isBlank()) {
            return MULTIPLIER_UNIT;
        }
        String text = raw.trim();
        double parsed = Double.NaN;
        if (MULTIPLIER_TEXT.matcher(text).matches()) {
            parsed = Double.parseDouble(text);
        }
        if (!(Double.isFinite(parsed) && parsed >= MULTIPLIER_MIN && parsed <= MULTIPLIER_MAX)) {
            throw new IllegalArgumentException("match.waveRosterMultiplier must be a plain"
                    + " decimal from " + MULTIPLIER_MIN + " to " + MULTIPLIER_MAX
                    + " (blank or 1 = no change); got '" + boundedEcho(text) + "'");
        }
        return parsed;
    }

    /** A refusal echo of operator text: at most 24 chars, outside [0-9A-Za-z.,+-] read '?'. */
    private static String boundedEcho(String text) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), 24));
        for (int i = 0; i < text.length() && out.length() < 24; i++) {
            char c = text.charAt(i);
            boolean safe = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c == '.' || c == ',' || c == '+' || c == '-';
            out.append(safe ? c : '?');
        }
        return out.toString();
    }

    /** The knob value this ratio divides by. */
    public int fullDp() {
        return fullDp;
    }

    /** The host's scaling modifier on the share this fold computes (1.0 = unset). */
    public double multiplier() {
        return multiplier;
    }

    /**
     * The live ratio {@code multiplier × sum / fullDp},
     * clamped to [{@link #FLOOR}, 1]. Never throws: a failed read (occupancy/resolver fault)
     * returns 1.0 = the static cap = today's wave (the multiplier never scales the fallback),
     * counts {@link #faults()} and logs ONCE.
     */
    public double ratio() {
        try {
            Collection<Integer> seats = occupancy.get();
            int n = 0;
            int unresolved = 0;
            double sum = 0.0d;
            if (seats != null) {
                for (Integer seat : seats) {
                    if (seat == null) {
                        continue;
                    }
                    int id = seat;
                    n++;
                    String variant = fieldedOrNull.apply(id);
                    if (variant == null) {
                        variant = overrideOrNull.apply(id);
                    }
                    String row = descriptorRowOrNull.apply(id);
                    if (variant == null) {
                        variant = row;
                    }
                    if (variant == null) {
                        unresolved++;
                        continue;
                    }
                    if (podExemptIds.contains(variant)) {
                        continue;
                    }
                    double dp = dpOrNaN(variant);
                    if (!(dp > 0.0d) || !Double.isFinite(dp)) {
                        unresolved++;
                        dp = 0.0d;
                        if (row != null && !row.equals(variant) && !podExemptIds.contains(row)) {
                            double rowDp = dpOrNaN(row);
                            dp = (rowDp > 0.0d && Double.isFinite(rowDp)) ? rowDp : 0.0d;
                        }
                    }
                    sum += dp;
                }
            }
            lastSeats = n;
            lastSeatDp = sum;
            lastUnresolved = unresolved;
            double r = multiplier * sum / fullDp;
            return r < FLOOR ? FLOOR : (r > 1.0d ? 1.0d : r);
        } catch (RuntimeException fault) {
            faults++;
            if (!faultLogged) {
                faultLogged = true;
                System.out.println("npeer-serve wave_dp_basis_fault err="
                        + fault.getClass().getSimpleName() + " faults=" + faults
                        + " (live-roster read failed; the wave stages at the static cap,"
                        + " ratio=1.0 — logged once)");
            }
            return 1.0d;
        }
    }

    /** A resolver fault folds as NaN (unresolved). */
    private double dpOrNaN(String variantId) {
        try {
            return rawDpOrNaN.applyAsDouble(variantId);
        } catch (RuntimeException fault) {
            faults++;
            return Double.NaN;
        }
    }

    /** Read faults so far (whole-read + resolver). */
    public long faults() {
        return faults;
    }

    /**
     * The last {@link #ratio()} read's terms for the
     * serve's {@code wave_dp_basis} row — {@code live_seats=N live_seat_dp=X unresolved=K}.
     */
    public String lastReadRow() {
        return "live_seats=" + lastSeats
                + " live_seat_dp=" + String.format(Locale.ROOT, "%.1f", lastSeatDp)
                + " unresolved=" + lastUnresolved;
    }
}
