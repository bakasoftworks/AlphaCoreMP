package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A read-only probe (the ONE probe that decides the R-hover wall):
 * the display twin provably lands on the right member, yet R on the own flagship shows a
 * SKILLED officer — top candidate is the {@code FleetMember.getCaptain()} flagship
 * short-circuit (iff raw {@code isFlagship} FIELD && fleetData
 * && {@code CampaignEngine.getInstance().getPlayerFleet()!=null} && playerFleet flagship ==
 * this, it returns {@code characterData.getPerson()} — bypassing the twin). Whether that
 * branch is LIVE in our launch shape is the "expected DEAD title-launched" ASSUMPTION,
 * never measured. This probe logs every input of that predicate + the identity of the person
 * the codex would read, right after the twin install (all references in scope there — the
 * twin is retained nowhere, so the compare CANNOT run anywhere else). Fix lanes are decided
 * BY BRANCH after the rows land (a learned lesson: never before).
 *
 * <p>Laws: read-only (the one {@code member.getCaptain()} call can lazily mint the vanilla
 * default captain ONLY when the member has none — ours always has the twin just installed);
 * fail-soft (a probe throw logs one WARN and never breaks the twin lane); single-line
 * printable rows; identities as identity-hashes (the {@code PressShadowProbe} idiom), never
 * {@code toString()}. The private raw {@code isFlagship} FIELD (the short-circuit reads the
 * field; the {@code isFlagship()} METHOD ORs in a commander clause — too broad) is
 * read via privateLookupIn+VarHandle resolved in the static init, force-warmed at
 * application load (the HulkKeepAliveAdapter pattern — never first-touched under the combat
 * SecurityManager).
 */
final class CodexCaptainProbe {

    /** Emission cap per boot: t0 own + settled re-fire + respawn + rival replicas, bounded. */
    private static final int MAX_ROWS = 6;

    /** The capRows enumeration cap (twin = 1+4 rows fits whole; all-skills truncates). */
    private static final int MAX_CAP_ROWS = 8;

    private static final VarHandle IS_FLAGSHIP_FIELD;
    private static final String RESOLVE_ERROR;
    private static int rows;

    static {
        VarHandle vh = null;
        String err = null;
        try {
            Class<?> fm = com.fs.starfarer.campaign.fleet.FleetMember.class;
            vh = MethodHandles.privateLookupIn(fm, MethodHandles.lookup())
                    .findVarHandle(fm, "isFlagship", boolean.class);
        } catch (Throwable t) {
            err = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        }
        IS_FLAGSHIP_FIELD = vh;
        RESOLVE_ERROR = err;
    }

    private CodexCaptainProbe() {
    }

    /** App-load warm receipt (forces the static VarHandle resolve on record, load-time). */
    static String describe() {
        return IS_FLAGSHIP_FIELD != null ? "ARMED (isFlagship VarHandle resolved)"
                : "FIELD-UNRESOLVED (" + RESOLVE_ERROR + ") — probe still logs the API rows";
    }

    /**
     * One row per twin install (capped): every short-circuit predicate input + who
     * {@code member.getCaptain()} actually returns now that the twin is installed.
     *
     * <p>{@code capRows=[id:lvl,…]} enumerates the member captain's skill rows
     * (bounded {@link #MAX_CAP_ROWS}) — the twin's 1+4 rows NAME the unverified createPerson +4
     * baseline; {@code shipCap*} instruments the REAL target-HUD surface person
     * ({@code Ship.getCaptain()} — the HUD reads the combat Ship field, never the
     * member), incl. {@code isDefault()} now that it is THE load-bearing display/stat predicate.
     */
    static void probeAfterTwinInstall(ShipAPI ship, FleetMemberAPI member, PersonAPI real,
            PersonAPI twin) {
        if (rows >= MAX_ROWS) {
            return;
        }
        rows++;
        try {
            // The EXACT singleton the flagship short-circuit dereferences (engine class is
            // DoNotObfuscate; Global.getSector() may not be byte-identical, log both).
            com.fs.starfarer.campaign.CampaignEngine eng =
                    com.fs.starfarer.campaign.CampaignEngine.getInstance();
            Object sector = Global.getSector();
            Object playerFleet = eng == null ? null : eng.getPlayerFleet();
            Object flagship = null;
            if (playerFleet instanceof com.fs.starfarer.api.campaign.CampaignFleetAPI) {
                flagship = ((com.fs.starfarer.api.campaign.CampaignFleetAPI) playerFleet)
                        .getFlagship();
            }
            PersonAPI charData = eng == null ? null : eng.getPlayerPerson();
            PersonAPI defCmdr = null;
            CombatFleetManagerAPI mgr = Global.getCombatEngine() == null ? null
                    : Global.getCombatEngine().getFleetManager(0);
            if (mgr != null) {
                defCmdr = mgr.getDefaultCommander();
            }
            String flagField = "unresolved";
            if (IS_FLAGSHIP_FIELD != null
                    && member instanceof com.fs.starfarer.campaign.fleet.FleetMember) {
                flagField = String.valueOf((boolean) IS_FLAGSHIP_FIELD
                        .get((com.fs.starfarer.campaign.fleet.FleetMember) member));
            }
            PersonAPI cap = member.getCaptain();
            PersonAPI shipCap = ship.getCaptain();
            String who = cap == twin ? "twin" : cap == real ? "real"
                    : (cap != null && cap == charData) ? "characterData"
                    : (cap != null && cap == defCmdr) ? "defaultCommander" : "other";
            com.fs.starfarer.api.combat.ShipVariantAPI v = ship.getVariant();
            PilotWatchLog.info("codex captain probe variant="
                    + (v == null ? "null" : v.getHullVariantId())
                    + " engine=" + (eng == null ? "null" : "live")
                    + " sector=" + (sector == null ? "null" : "live")
                    + " playerFleet=" + (playerFleet == null ? "null" : "live")
                    + " flagshipIsMember=" + (playerFleet == null ? "na"
                            : String.valueOf(flagship == member))
                    + " isFlagshipField=" + flagField
                    + " memberCaptain=" + who
                    + " capSkills=" + (cap == null ? "na"
                            : String.valueOf(cap.getStats().getSkillsCopy().size()))
                    + " capRows=" + capRows(cap)
                    + " shipCap=" + ihash(shipCap)
                    + " shipCapDefault=" + (shipCap == null ? "na"
                            : String.valueOf(shipCap.isDefault()))
                    + " shipCapSkills=" + (shipCap == null ? "na"
                            : String.valueOf(shipCap.getStats().getSkillsCopy().size()))
                    + " ihash cap=" + ihash(cap) + " twin=" + ihash(twin) + " real=" + ihash(real)
                    + " charData=" + ihash(charData) + " defCmdr=" + ihash(defCmdr));
        } catch (Throwable t) {
            PilotWatchLog.info("codex captain probe WARN: " + t.getClass().getSimpleName()
                    + " (probe only; twin lane unaffected)");
        }
    }

    private static String ihash(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }

    /**
     * The person's skill rows as {@code [id:lvl,…]}, bounded to {@link #MAX_CAP_ROWS} with a
     * {@code +n} overflow suffix (bounded allocation; engine-authored ids, single-line).
     */
    private static String capRows(PersonAPI p) {
        if (p == null) {
            return "na";
        }
        java.util.List<com.fs.starfarer.api.characters.MutableCharacterStatsAPI.SkillLevelAPI>
                skills = p.getStats().getSkillsCopy();
        StringBuilder sb = new StringBuilder("[");
        int shown = Math.min(skills.size(), MAX_CAP_ROWS);
        for (int i = 0; i < shown; i++) {
            com.fs.starfarer.api.characters.MutableCharacterStatsAPI.SkillLevelAPI sl =
                    skills.get(i);
            if (i > 0) {
                sb.append(',');
            }
            float lvl = sl.getLevel();
            sb.append(sl.getSkill() == null ? "null" : sl.getSkill().getId()).append(':');
            if (lvl == (int) lvl) {
                sb.append((int) lvl);
            } else {
                sb.append(lvl);
            }
        }
        if (skills.size() > shown) {
            sb.append(",+").append(skills.size() - shown);
        }
        return sb.append(']').toString();
    }
}
