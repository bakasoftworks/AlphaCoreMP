package cc.starsector.server.bridge;

import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.OfficerRosterCodec;
import cc.starsector.server.match.WaveSchedule;
import cc.starsector.server.platform.OfficerAttach;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The STATIC, descriptor-derived STAR FORTRESS serve-boot
 * pre-flight. Two checks, both run once at boot with the engine's content already initialized
 * and BEFORE a single hull deploys:
 *
 * <ol>
 *   <li>DP CURVE — NARRATION ONLY (a refusal that never
 *       caught a real fault is dropped: the drift class the old ±15% refusal hunted — a
 *       vanilla balance patch moving {@code supplies/rec} — cannot occur under the
 *       session-start engine-jar pin, and its only catches on record were false-positive
 *       refusals of playable fights: the seat-swap boot refusal and the
 *       fitting-total gaps). {@code seatDp} = Σ over the descriptor's SEAT rows of the
 *       ENGINE's own {@code ShipHullSpecAPI.getSuppliesToRecover()} (the standing fortress
 *       today: grendel 18 + conquest 40 = 58; the declared-costless pod seat folds 0 —
 *       {@link #foldSeatDp}). Each compiled wave's DP is folded the same way
 *       and graded against {@code multiplier × seatDp} (SCALED mode: the owner total's
 *       targets) within {@link WaveSchedule#FORTRESS_CURVE_TOLERANCE} — out-of-band prints a
 *       loud WARN table and the boot proceeds; the game-free contract rows pin the authored
 *       tables themselves, so an authoring typo is caught at BUILD time, not by refusing a
 *       serve.</li>
 *   <li>OFFICER ROWS. Every variant the shape can field (descriptor rows + every scheduled wave
 *       hull) has its {@code officers.txt} row skill-id-checked against the engine's skill table
 *       ({@link OfficerAttach#validateRowsForFieldedVariants}) — a bad row kills the BOOT, not
 *       the mid-round wave that first carries it. And every {@code *_cc_*} officer TWIN the
 *       shape fields must HAVE a row: an absent/stale deployed table fail-softs to empty, which
 *       would otherwise boot green with the whole AI-core tier axis silently missing.</li>
 * </ol>
 *
 * <p>DP LAW: hull base DP is {@code getSuppliesToRecover} (the ship_data {@code
 * supplies/rec} column). NEVER {@code getFleetPointCost} — a separate column entirely (radiant
 * fleet-points 30 vs DP 60, the trap). Headless discipline: variant → hull spec
 * ONLY, no {@code FleetMember}, no fleet machinery, no campaign objects.
 */
public final class FortressServeBootChecks {

    private static final String VARIANT_API = "com.fs.starfarer.api.combat.ShipVariantAPI";
    private static final String HULL_SPEC_API = "com.fs.starfarer.api.combat.ShipHullSpecAPI";

    /**
     * The repo's officer-TWIN variant-id marker ({@code glimmer_cc_gamma}, {@code radiant_cc_alpha}
     * …): a cc-minted copy of a vanilla variant, which exists ONLY to carry an officers.txt row.
     * No vanilla id carries it.
     */
    private static final String TWIN_MARKER = "_cc_";

    private FortressServeBootChecks() {
    }

    /**
     * The ONE engine-fold DP resolver the scaled-chain compile injects into
     * {@link WaveSchedule#forShape(String, int, double, java.util.function.ToDoubleFunction)}
     * (memoized per instance — mint one per boot). Engine access stays lazy: binding no
     * resolver costs nothing on non-fortress serves.
     */
    public static java.util.function.ToDoubleFunction<String> engineDpResolver() {
        return new Dp()::of;
    }

    /**
     * The NON-throwing raw DP read the live-roster fold
     * ({@code LiveRosterDp}) injects — the engine {@code getSuppliesToRecover} read with NO
     * positivity law (the pod's 0 is legal there; the fold owns the exemption), returning
     * {@code NaN} for an id that does not resolve. Memoized per instance, failures included (a
     * bad id costs ONE engine attempt, never one per wave stage); each failing id counts into
     * {@link RawDpOrNaN#failures()} and logs ONCE with a sanitized id.
     */
    public static RawDpOrNaN engineRawDpOrNaN() {
        return new RawDpOrNaN(new Dp());
    }

    /** See {@link #engineRawDpOrNaN()}. */
    public static final class RawDpOrNaN implements java.util.function.ToDoubleFunction<String> {
        private final Dp dp;
        private final Set<String> failed = new LinkedHashSet<>();
        private long failures;

        private RawDpOrNaN(Dp dp) {
            this.dp = dp;
        }

        @Override
        public double applyAsDouble(String variantId) {
            if (variantId == null || failed.contains(variantId)) {
                return Double.NaN;
            }
            try {
                return dp.raw(variantId);
            } catch (RuntimeException fault) {
                failures++;
                failed.add(variantId);
                System.out.println("npeer-serve wave_roster_dp UNRESOLVED variant="
                        + sanitizedId(variantId) + " err=" + fault.getClass().getSimpleName()
                        + " failures=" + failures + " (folds the descriptor row's DP;"
                        + " logged once per id)");
                return Double.NaN;
            }
        }

        /** Distinct ids that failed to resolve so far. */
        public long failures() {
            return failures;
        }
    }

    /** A log-safe id (charset-bounded, ≤ 64 chars). */
    private static String sanitizedId(String id) {
        StringBuilder out = new StringBuilder(Math.min(id.length(), 64));
        for (int i = 0; i < id.length() && out.length() < 64; i++) {
            char c = id.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            out.append(safe ? c : '?');
        }
        return out.toString();
    }

    /**
     * Run the fortress pre-flight when {@code descriptor} is the fortress shape; a no-op for
     * every other shape (and for a boot with no descriptor at all). The PvP shape runs it too and
     * passes without a station: no check here reads a station row (the seat fold sums both teams).
     * THROWS only on structural
     * violations (no schedule compiled; a bad/missing officer row) — the DP-curve grade is
     * NARRATION (class javadoc item 1). The knob values must be the SAME ones the
     * serve compiles with (the both-call-sites law — the
     * {@code PersistentPilotIntentCombatServer.resolve*} statics are the one resolve each);
     * in scaled mode the curve basis becomes {@code total / Σ multipliers} so the narration
     * grades the generated chain against the authored curve at the operator's chosen size.
     * {@code category} = the parsed {@code match.waveCategory}.
     */
    public static void runIfFortress(MatchDescriptor descriptor, double fortressDpTotal,
            double[] waveScale, String category) {
        // The boot pre-flight also reads the ONE
        // match.waveRosterFullDp resolve, so a set-on-AUTO knob kills the BOOT (never a wave);
        // the match.waveRosterMultiplier resolve rides beside it.
        runIfFortress(descriptor, fortressDpTotal, waveScale, category,
                cc.starsector.server.runtime.PersistentPilotIntentCombatServer
                        .resolveWaveRosterFullDp(),
                cc.starsector.server.runtime.PersistentPilotIntentCombatServer
                        .resolveWaveRosterMultiplier());
    }

    /**
     * The 4-arg pre-flight plus the resolved
     * {@code match.waveRosterFullDp} (0 = off): THROWS when it is set on an AUTO fortress
     * ({@link WaveSchedule#requireRosterFullDpMode}); the band narration grades the static CAP
     * chain either way and notes {@code roster_full_dp=} so a reader knows live waves are ≤ it.
     * Plus the resolved {@code match.waveRosterMultiplier}
     * (1.0 = unset): THROWS when a set multiplier meets roster sizing OFF on a fortress
     * ({@link WaveSchedule#requireRosterMultiplierMode}) — the boot dies, never a wave.
     */
    public static void runIfFortress(MatchDescriptor descriptor, double fortressDpTotal,
            double[] waveScale, String category, int rosterFullDp, double rosterMultiplier) {
        if (descriptor == null || !MatchDescriptors.isFortressShape(descriptor.shape())) {
            return;
        }
        boolean rosterLive = WaveSchedule.requireRosterFullDpMode(
                descriptor.shape(), fortressDpTotal, rosterFullDp);
        WaveSchedule.requireRosterMultiplierMode(descriptor.shape(), rosterFullDp,
                rosterMultiplier);
        Dp dp = new Dp();
        // The SAME parsed match.fortressWaveScale + match.waveCategory the serve
        // compiles with ride the mint here, so the grade reads the exact chain the round
        // will field.
        WaveSchedule schedule = WaveSchedule.forShape(
                descriptor.shape(), 60, fortressDpTotal, waveScale, dp::of, category);
        if (schedule == null) {
            throw new IllegalStateException("fortress shape " + descriptor.shape()
                    + " compiled NO wave schedule — the DEFEND round has no win condition and"
                    + " no escalation");
        }
        // A BOSS-ONLY category (ziggurat) fields ONE curve-exempt wave — there
        // is no 7-wave curve to grade (the multiplier-count throw below would kill the boot), so
        // the band check is SKIPPED with a loud row and only the officer tail runs (the boss
        // captain is a row spec, swept there).
        if (WaveSchedule.isBossOnlyCategory(schedule.category())) {
            System.out.println("npeer-serve fortress_dp_curve SKIPPED boss_only shape="
                    + descriptor.shape() + " category=" + schedule.category()
                    + " waves=" + schedule.waveCount() + " hulls=" + schedule.totalHullCount()
                    + " (one timer-armed boss wave, curve-exempt under both"
                    + " mint modes)");
            officerTail(descriptor, schedule);
            return;
        }
        // ticksPerSecond only sets wave 2's arm-based due tick; the DP fold reads variant ids,
        // which are tick-rate independent — so the serve's real rate cannot change this verdict.
        List<String> seatVariants = new ArrayList<>();
        List<String> exemptSeats = new ArrayList<>();
        double seatDp = foldSeatDp(descriptor.shape(), descriptor.roster(), dp::raw,
                seatVariants, exemptSeats);
        List<List<String>> byWave = schedule.variantIdsByWave();
        double[] multipliers = WaveSchedule.fortressCurveMultipliers();
        if (byWave.size() != multipliers.length) {
            throw new IllegalStateException("fortress chain compiled " + byWave.size()
                    + " scheduled waves but the authored curve carries " + multipliers.length
                    + " multipliers — the authored chain and the curve must be re-ratified"
                    + " together");
        }
        // Scaled mode: the owner's total replaces seat DP as the curve basis
        // (basis = total / Σ multipliers, the WaveSchedule generator's own math) — the ±15%
        // band then grades the GENERATED chain exactly the way AUTO grades the authored one.
        boolean scaled = fortressDpTotal > 0.0d;
        // SCALED targets come from the ONE WaveSchedule seam (curve × scale × basis);
        // AUTO keeps the law — multiplier × the boot-folded seat DP.
        double[] scaledTargets = scaled
                ? WaveSchedule.fortressWaveTargets(fortressDpTotal, waveScale) : null;
        StringBuilder table = new StringBuilder();
        StringBuilder summary = new StringBuilder();
        boolean violated = false;
        double totalDp = 0.0d;
        for (int w = 0; w < byWave.size(); w++) {
            double waveDp = 0.0d;
            for (String variantId : byWave.get(w)) {
                waveDp += dp.of(variantId);
            }
            totalDp += waveDp;
            double target = scaled ? scaledTargets[w] : multipliers[w] * seatDp;
            double factor = multipliers[w] * (waveScale == null ? 1.0d : waveScale[w]);
            double delta = target == 0.0d ? 0.0d : (waveDp - target) / target;
            boolean ok = Math.abs(delta) <= WaveSchedule.FORTRESS_CURVE_TOLERANCE;
            violated |= !ok;
            table.append(String.format(Locale.ROOT,
                    "%n  w%d hulls=%d dp=%.1f target=%.2f (%.2fx %s) delta=%+.2f%% %s",
                    w + 2, byWave.get(w).size(), waveDp, target, factor,
                    scaled ? "basis" : "seatDp",
                    delta * 100.0d, ok ? "OK" : "OUT-OF-BAND"));
            summary.append(w == 0 ? "" : ",")
                    .append("w").append(w + 2).append("=")
                    .append(String.format(Locale.ROOT, "%.0f", waveDp));
        }
        if (violated) {
            // out-of-band is a LOUD WARN, never a refusal —
            // class javadoc item 1 carries the ZERO-CATCH rationale. The table names every
            // wave's delta so authoring/data drift stays visible in one grep.
            System.out.println("npeer-serve fortress_dp_curve WARN out_of_band (the ±"
                    + (int) Math.round(WaveSchedule.FORTRESS_CURVE_TOLERANCE * 100.0d)
                    + "% band is narration, boot proceeds): seat_dp="
                    + String.format(Locale.ROOT, "%.1f", seatDp)
                    + " over " + seatVariants.size() + " basis seat hulls " + seatVariants
                    + (exemptSeats.isEmpty() ? ""
                            : " (+" + exemptSeats.size() + " zero-DP-exempt " + exemptSeats + ")")
                    + " category=" + schedule.category()
                    + " mode=" + (scaled ? "scaled" : "auto") + table);
        }
        System.out.println("npeer-serve fortress_dp_curve shape=" + descriptor.shape()
                + " mode=" + (scaled ? "scaled" : "auto")
                + " category=" + schedule.category()
                + " in_band=" + !violated
                + " fortress_dp=" + String.format(Locale.ROOT, "%.0f", fortressDpTotal)
                + " wave_scale=" + (waveScale == null ? "unit" : waveScaleText(waveScale))
                + " seat_dp=" + String.format(Locale.ROOT, "%.0f", seatDp)
                + " seat_exempt=" + exemptSeats.size()
                + (exemptSeats.isEmpty() ? "" : " " + exemptSeats)
                + " waves=" + byWave.size()
                + " hulls=" + schedule.totalHullCount()
                + " total_wave_dp=" + String.format(Locale.ROOT, "%.0f", totalDp)
                + " tolerance=" + WaveSchedule.FORTRESS_CURVE_TOLERANCE
                + " per_wave_dp=" + summary
                // The graded chain is the CAP; live waves are ≤ it.
                + " roster_full_dp=" + (rosterLive ? rosterFullDp + " (live waves <= this cap)"
                        : "off")
                + " (engine getSuppliesToRecover, never getFleetPointCost)");

        officerTail(descriptor, schedule);
    }

    /**
     * The officer tail. (1) Officer rows for EVERY hull this shape can field — descriptor rows
     * first (seat hulls carry the owner-fleet twins), then every scheduled wave hull (the
     * AI-core twins). (2) The ROW-CARRIED specs of EVERY category's authored
     * pattern (the faction officer rule + the Ziggurat boss captain) — a live
     * {@code /wave <faction>} switch fields them mid-round with no second boot check, so each
     * one's skill ids resolve HERE or the boot dies naming the spec
     * ({@link OfficerAttach#validateRowSpecs}). The SCALED fill copies the same spec objects,
     * so one sweep of the authored patterns covers both mint modes.
     */
    private static void officerTail(MatchDescriptor descriptor, WaveSchedule schedule) {
        List<String> fielded = new ArrayList<>();
        for (MatchDescriptor.RosterRow row : descriptor.roster()) {
            fielded.add(row.variantId());
        }
        fielded.addAll(schedule.variantIds());
        requireTwinRows(fielded, OfficerAttach.validateRowsForFieldedVariants(fielded));
        List<OfficerRosterCodec.OfficerSpec> rowSpecs = new ArrayList<>();
        for (String category : WaveSchedule.WAVE_CATEGORIES) {
            for (List<WaveSchedule.WaveRow> wave : WaveSchedule.fortressWavePattern(category)) {
                for (WaveSchedule.WaveRow row : wave) {
                    if (row.officer() != null) {
                        rowSpecs.add(row.officer());
                    }
                }
            }
        }
        int checked = OfficerAttach.validateRowSpecs(rowSpecs);
        System.out.println("npeer-serve fortress_officer_row_specs categories="
                + WaveSchedule.WAVE_CATEGORIES.size() + " row_specs=" + rowSpecs.size()
                + " distinct_checked=" + checked
                + " (every category's officered rows, live-switchable)");
    }

    // The nearest-fitting-totals band scan and its scaledTotalFits
    // helper were DELETED with the refusal they served: with the DP-curve band demoted
    // to narration there is no refusal to hint at, and any typed total boots.
    //

    /** {@code match.fortressWaveScale} factors as the row text (diagnostics only). */
    private static String waveScaleText(double[] waveScale) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < waveScale.length; i++) {
            text.append(i == 0 ? "" : ",").append(waveScale[i]);
        }
        return text.toString();
    }

    /**
     * The fortress seat-DP fold, extracted so the contract suite can pin its
     * laws game-free ({@code rawDp} injected — the serve hands in the engine fold's RAW read,
     * the suite a static law table). Folds SEAT rows only. A variant declared in
     * {@link MatchDescriptors#ZERO_DP_SEAT_EXEMPT_VARIANT_IDS} contributes NOTHING to the
     * basis (a pod must never inflate wave difficulty) but is still READ: a declared-exempt id
     * whose engine read is not {@code <= 0} throws — the declaration went stale (a balance
     * patch priced the hull, or the read broke) and silently folding 0 would shrink the curve
     * basis. An UNDECLARED non-positive read keeps throwing (the blind-spot guard) — exemption
     * is a reviewed declaration, never an inference from a 0 read. Appends the folded basis
     * ids to {@code seatVariants} and the exempted ids to {@code exemptSeats}; returns the
     * basis DP, throwing if no positive basis remains (the whole curve is a multiple of it).
     */
    public static double foldSeatDp(String shape, List<MatchDescriptor.RosterRow> roster,
            java.util.function.ToDoubleFunction<String> rawDp,
            List<String> seatVariants, List<String> exemptSeats) {
        double seatDp = 0.0d;
        for (MatchDescriptor.RosterRow row : roster) {
            if (row.pilot() != MatchDescriptor.PilotKind.SEAT) {
                continue;
            }
            for (int i = 0; i < row.count(); i++) {
                double dp = rawDp.applyAsDouble(row.variantId());
                if (MatchDescriptors.ZERO_DP_SEAT_EXEMPT_VARIANT_IDS
                        .contains(row.variantId())) {
                    // !(dp <= 0) rather than dp > 0: a NaN read must also refuse.
                    if (!(dp <= 0.0d)) {
                        throw new IllegalStateException("variant '" + row.variantId()
                                + "' is declared zero-DP-exempt but reads DP " + dp
                                + " (getSuppliesToRecover) — the declaration went STALE (the"
                                + " hull now has a real cost) and folding 0 for it would"
                                + " silently shrink the curve basis; remove it from"
                                + " MatchDescriptors.ZERO_DP_SEAT_EXEMPT_VARIANT_IDS");
                    }
                    exemptSeats.add(row.variantId());
                    continue;
                }
                if (!Double.isFinite(dp) || dp <= 0.0d) {
                    throw new IllegalStateException("variant '" + row.variantId()
                            + "' reads DP " + dp + " (getSuppliesToRecover) — a zero-cost hull"
                            + " would make the curve check blind to it; a hull that GENUINELY"
                            + " costs 0 by vanilla data is an explicit"
                            + " MatchDescriptors.ZERO_DP_SEAT_EXEMPT_VARIANT_IDS declaration,"
                            + " never an inference");
                }
                seatVariants.add(row.variantId());
                seatDp += dp;
            }
        }
        if (seatVariants.isEmpty() || seatDp <= 0.0d) {
            throw new IllegalStateException("fortress descriptor " + shape
                    + " folded seat DP " + seatDp + " over " + seatVariants.size()
                    + " basis SEAT hulls (+" + exemptSeats.size() + " zero-DP-exempt)"
                    + " — the whole curve is a multiple of seat DP, so a zero basis"
                    + " would pass every wave silently");
        }
        return seatDp;
    }

    /**
     * Fail-CLOSED: every {@code *_cc_*} officer TWIN this shape fields MUST
     * carry a {@code data/cc/officers.txt} row. A twin IS its row — same hull, same loadout as
     * its vanilla base, minted only so the variant-keyed officer lane can express an AI-core
     * tier — so a twin with no row fields as a plain unofficered hull and the whole escalation
     * axis is silently absent. The sweep alone cannot catch that: "no row" is legal for the bare
     * vanilla ids, and an absent/stale deployed table fail-softs to an EMPTY map, which would
     * boot GREEN (the DP curve reads hull specs — those resolve from the {@code .variant} files
     * alone, so a kit that packs the twins with a stale table passes it either way).
     */
    private static void requireTwinRows(List<String> fielded, Set<String> officered) {
        if (!OfficerAttach.officerSkillsEnabled()) {
            // Knob OFF is an explicit operator decision (and the sweep just logged it): the whole
            // attach lane is down, which is not the silent data drift this check hunts.
            System.out.println("npeer-serve fortress_officer_rows SKIPPED knob_on=false"
                    + " (cc.server.officerSkills OFF — the AI-core tier axis is deliberately"
                    + " absent this boot)");
            return;
        }
        List<String> required = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> nonTwinExempt = new ArrayList<>();
        for (String variantId : new LinkedHashSet<>(fielded)) {
            if (variantId == null || !variantId.contains(TWIN_MARKER)) {
                continue;
            }
            if (MatchDescriptors.NON_TWIN_CC_VARIANT_IDS.contains(variantId)) {
                // A cc-ORIGINAL (pod) — marker-matched but not a twin. The
                // reverse guard keeps the declaration fresh: a row appearing for it would
                // attach an officer and silently un-isolate the seat.
                if (officered.contains(variantId)) {
                    throw new IllegalStateException("variant '" + variantId + "' is declared"
                            + " NOT an officer twin but the deployed officers.txt DOES carry a"
                            + " row for it — the declaration went STALE (the attach would"
                            + " silently un-isolate the pod seat); remove it from"
                            + " MatchDescriptors.NON_TWIN_CC_VARIANT_IDS or drop the row");
                }
                nonTwinExempt.add(variantId);
                continue;
            }
            required.add(variantId);
            if (!officered.contains(variantId)) {
                missing.add(variantId);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("fortress fields " + missing.size() + " officer TWIN"
                    + " variant(s) with NO officers.txt row: " + missing + " (of " + required.size()
                    + " twins fielded) — a twin exists ONLY to carry its row, so these would field"
                    + " as plain UNOFFICERED hulls and the commissioned AI-core escalation axis"
                    + " would be silently absent. The table fail-softs to EMPTY when the deployed"
                    + " mod data is absent or stale: re-roll the kit (officers.txt AND the *_cc_*"
                    + " .variant files are kit DATA).");
        }
        System.out.println("npeer-serve fortress_officer_rows twins_required=" + required.size()
                + " twins_officered=" + required.size() + " " + required
                + " non_twin_exempt=" + nonTwinExempt);
    }

    /** Per-boot memoized variant → hull-spec DP reader (one engine resolve per distinct id). */
    private static final class Dp {
        private final Map<String, Double> byVariant = new LinkedHashMap<>();
        private Method getHullSpec;
        private Method getSuppliesToRecover;

        /** Positivity-guarded read — the WAVE folds' law (a 0-DP wave hull blinds the band). */
        double of(String variantId) {
            double dp = raw(variantId);
            if (dp <= 0.0d) {
                throw new IllegalStateException("variant '" + variantId + "' reads DP " + dp
                        + " (getSuppliesToRecover) — a zero-cost hull would make the curve"
                        + " check blind to it");
            }
            return dp;
        }

        /**
         * Raw engine read, no positivity law — the SEAT fold applies its own (the declared
         * zero-DP-exempt pod row is legal there).
         */
        double raw(String variantId) {
            Double cached = byVariant.get(variantId);
            if (cached != null) {
                return cached;
            }
            try {
                if (getHullSpec == null) {
                    ClassLoader loader = FortressServeBootChecks.class.getClassLoader();
                    // Public API interfaces only (the probe lesson: the impl classes are
                    // name-mangled and inaccessible to a reflective invoke).
                    getHullSpec = Class.forName(VARIANT_API, false, loader)
                            .getMethod("getHullSpec");
                    getSuppliesToRecover = Class.forName(HULL_SPEC_API, false, loader)
                            .getMethod("getSuppliesToRecover");
                }
                Object variant = RealEngineDemoRoster.resolveVariantById(variantId);
                Object hullSpec = getHullSpec.invoke(variant);
                if (hullSpec == null) {
                    throw new IllegalStateException("variant '" + variantId
                            + "' resolved but carries a null hull spec");
                }
                double dp = ((Number) getSuppliesToRecover.invoke(hullSpec)).doubleValue();
                byVariant.put(variantId, dp);
                return dp;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("fortress DP read failed for variant '"
                        + variantId + "': " + e, e);
            }
        }
    }
}
