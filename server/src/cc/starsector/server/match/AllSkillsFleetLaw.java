package cc.starsector.server.match;

import java.util.ArrayList;
import java.util.List;

/**
 * W4 all-skills side-0 fleet-context — the SHARED half: the pure
 * walk/echo/shape law both jars carry. Split out of {@link AllSkillsFleetData}: that
 * class is java.lang.reflect throughout, and classloader B DENIES java.lang.reflect in the mod
 * jar (the B-deny gate measured 6 denied refs), so the client cannot ride the
 * reflective mint. The law that must be BYTE-IDENTICAL across ends lives here (JDK +
 * {@link MatchDescriptor} only, client build.ps1 allowlist); each end mints with its own
 * legal vehicle — server = reflective {@link AllSkillsFleetData}, client = the typed twin in
 * the client tree — and the {@code fleet_ctx} echo EQUALITY assert at watch grading is the
 * anti-drift guard replacing "one mint class both jars".
 */
public final class AllSkillsFleetLaw {

    private AllSkillsFleetLaw() {
    }

    /** Per-end station read for the walk law ({@code ShipVariantAPI.isStation()} both ends). */
    public interface StationCheck {
        boolean isStation(String variantId) throws Exception;
    }

    /** The minted context: the fab (as {@code FleetDataAPI}), its gate-2 token, and the echo. */
    public static final class Mint {
        /** The real engine {@code FleetData} holding the shadow roster (a {@code FleetDataAPI}). */
        public final Object fleetData;
        /** The fail-closed {@code CampaignFleetAPI} gate-2 token installed on the union stats. */
        public final Object fleetToken;
        /**
         * Canonical parity line body — IDENTICAL bytes on both ends for the same descriptor
         * (the watch grading assert lane; each end prefixes its own sidelog tag).
         */
        public final String echo;

        public Mint(Object fleetData, Object fleetToken, String echo) {
            this.fleetData = fleetData;
            this.fleetToken = fleetToken;
            this.echo = echo;
        }
    }

    /**
     * THE side-0 roster walk law (one law, both ends): every {@code side == 0} roster row whose
     * variant is not a STATION ({@code ShipVariantAPI.isStation()} — the same engine hint both
     * deploy lanes already key their station carve-outs on; a station is terrain-like, never a
     * campaign fleet member, so it must not inflate the OP/bay sums), expanded {@code count}
     * times, in declaration order. Seat and AI rows alike — the fleet is the FULL side-0 roster
     * exactly like a campaign fleet counts reserves, not just deployed hulls.
     */
    public static List<String> sideZeroShipVariantIds(List<MatchDescriptor.RosterRow> roster,
            StationCheck check) {
        if (roster == null || roster.isEmpty()) {
            throw new IllegalStateException("all-skills fleet-context: null/empty roster —"
                    + " a descriptor battle always carries roster rows");
        }
        List<String> ids = new ArrayList<>();
        for (MatchDescriptor.RosterRow row : roster) {
            if (row.side() != 0) {
                continue;
            }
            boolean station;
            try {
                station = check.isStation(row.variantId());
            } catch (Exception e) {
                throw new IllegalStateException("all-skills fleet-context: station check failed"
                        + " for variant '" + row.variantId() + "' — fail-closed, a misclassified"
                        + " row skews the throttle sums: " + e, e);
            }
            if (station) {
                continue;
            }
            for (int i = 0; i < row.count(); i++) {
                ids.add(row.variantId());
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalStateException("all-skills fleet-context: the side-0 walk produced"
                    + " ZERO ship rows — an empty fleet-data roster silently grants every"
                    + " threshold skill at maximum");
        }
        return ids;
    }

    /** Pure echo formatting (contract-row pinned — grading greps these exact keys). */
    public static String echoLine(int members, float phaseDp, float phaseThr, float pcSpeed,
            float pcPeak, float combatDp, float bays) {
        return "fleet_ctx side=0 members=" + members
                + " phase_dp=" + Math.round(phaseDp)
                + " pc_thr=" + Math.round(phaseThr)
                + " pc_speed=" + Math.round(pcSpeed)
                + " pc_peak=" + Math.round(pcPeak)
                + " combat_dp=" + Math.round(combatDp)
                + " bays=" + Math.round(bays);
    }
}
