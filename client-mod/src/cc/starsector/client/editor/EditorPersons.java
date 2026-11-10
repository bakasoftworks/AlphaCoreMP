package cc.starsector.client.editor;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;

import cc.starsector.client.OfficerLoadout;

/**
 * The editor's detached person mints — the memoized all-skills {@code PersonAPI} sheets the oracle
 * and {@link EditorStats} hand to the engine's own stat passes
 * (moved out of {@link EditorOracle} unchanged).
 */
final class EditorPersons {
    private static volatile PersonAPI unionPerson;
    private static volatile PersonAPI captainPerson;   // The bastion seat captain's ship set (EditorStats)
    private static volatile PersonAPI commanderPerson; // A SECOND union-sheet person for the skills fab's token — /validate's unionPerson stays untouched

    private EditorPersons() {
    }

    /**
     * A first mint asked before the game has a sector. WHY: every mint ends in the engine's
     * {@code refreshCharacterStatsEffects}, whose first statement is {@code ListenerUtil
     * .reportAboutToRefreshCharacterStatEffects()}, a read through {@code Global.getSector()}. The editor starts in {@code onApplicationLoad}, before the title screen has made its
     * sector, so a request in that window died on a null read and the route answered HTTP 500 (the red roll:
     * {@code data/hull/wolf?allSkills=1} 118 s into a slow boot). {@link EditorRoutes#handle} answers this 503 "not
     * ready"; nothing is memoized, so the next request mints normally.
     */
    static final class NotReady extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        NotReady() {
            super("the game has not finished starting yet");
        }
    }

    private static void requireSector() {
        if (Global.getSector() == null) {
            throw new NotReady();
        }
    }

    static PersonAPI unionPerson() {
        PersonAPI p = unionPerson;
        if (p == null) {
            requireSector();
            p = OfficerLoadout.mintUnionPersonDetached();
            unionPerson = p;
        }
        return p;
    }

    /** The seat captain of the bastion shape — the ship-scope all-skills set, minted detached once. */
    static PersonAPI captainPerson() {
        PersonAPI p = captainPerson;
        if (p == null) {
            requireSector();
            p = OfficerLoadout.mintShipSkillsPersonDetached();
            captainPerson = p;
        }
        return p;
    }

    /** The skills fab's COMMANDER — a second union-sheet person (stats-identical to {@link #unionPerson()},
     *  the OfficerLoadout stand-in idiom) that carries the per-call fleet token, so the {@code /validate} person's
     *  stats are never re-derived under a token (the smoke's validate rows stay byte-for-byte). Minted detached once. */
    static PersonAPI commanderPerson() {
        PersonAPI p = commanderPerson;
        if (p == null) {
            requireSector();
            p = OfficerLoadout.mintUnionPersonDetached();
            // The bastion union commander's shape (OfficerLoadout.mintPerson never clears skipRefresh): the token
            // install's refreshCharacterStatsEffects(false) stays a NO-OP, so the FLEET-type skill effects never walk
            // into fleet.getStats() — the token's unmapped read (observed: CharacterStats
            // .refreshCharacterStatsEffects). The ship-stat applies (applyFleetwideToStats) read the skill
            // levels directly and need no refresh; the character-level effects were applied at mint (fleet-less).
            p.getStats().setSkipRefresh(true);
            commanderPerson = p;
        }
        return p;
    }

    static MutableCharacterStatsAPI personStatsOrNull(boolean allSkills) {
        return allSkills ? unionPerson().getStats() : null;
    }
}
