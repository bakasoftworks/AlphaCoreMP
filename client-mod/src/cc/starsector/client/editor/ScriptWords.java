package cc.starsector.client.editor;

import com.fs.starfarer.api.impl.hullmods.BallisticRangefinder;
import com.fs.starfarer.api.impl.hullmods.ConvertedHangar;
import com.fs.starfarer.api.impl.hullmods.EnergyBoltCoherer;
import com.fs.starfarer.api.impl.hullmods.HighScatterAmp;
import com.fs.starfarer.api.impl.hullmods.MissileAutoloader;

/**
 * The editor's own description text for the six hullmods whose tooltip the game assembles in code instead of
 * reading it from its data files - without this class the editor's description panel would be empty for them.
 * Every sentence is a plain explanation of the mechanic written for this editor; no game text is carried here.
 * Where an effect class exposes a number as a public static field, that field is read on every call (so a mod
 * that retunes it changes the number shown); numbers a class keeps private or in a table are written as literals.
 * Each method returns plain text: one paragraph per line, lines joined by a single newline.
 *
 */
final class ScriptWords {
    private ScriptWords() {}

    static String convertedHangar() {
        String mult = oneDecimal(ConvertedHangar.REPLACEMENT_TIME_MULT) + "x";
        int turnaround = Math.round(ConvertedHangar.REARM_TIME_FRACTION * 100f);
        int opPerPoint = (int) ConvertedHangar.FIGHTER_OP_PER_DP;
        return lines(
                "Rebuilds the ship's shuttle hangar into a single fighter bay. It is an improvised bay, so it"
                        + " works more slowly than the bays of a real carrier.",
                "Fighters take " + mult + " as long to refit. The replacement rate also takes " + mult
                        + " as long both to drop and to recover.",
                "Turnaround is slow as well: a craft that lands to rearm or repair stays in the bay for "
                        + turnaround + "% of its base refit time before relaunching, against under a second on"
                        + " a standard carrier.",
                "Minimum crew goes up by " + ConvertedHangar.CREW_REQ + ". Deployment points and the supply cost"
                        + " of recovering after deployment rise by 1 for every " + opPerPoint + " ordnance points"
                        + " spent on fighter wings, with a floor of +" + ConvertedHangar.MIN_DP + ", and the combat"
                        + " readiness lost on each deployment rises proportionally.");
    }

    static String highResSensors() {
        return lines(
                "On the campaign map, each ship fitted with it extends the fleet's sensor range: by 50 for a"
                        + " frigate, 75 for a destroyer, 100 for a cruiser and 150 for a capital ship.",
                "Fitting it on several ships has diminishing returns, so each additional ship contributes less.",
                "How soon that falloff starts depends on the largest bonus any single ship in the fleet provides:"
                        + " a larger top bonus pushes the falloff back.");
    }

    static String ballisticRangefinder() {
        int small1 = (int) BallisticRangefinder.BONUS_SMALL_1;
        int max1 = (int) BallisticRangefinder.BONUS_MAX_1;
        int small3 = (int) BallisticRangefinder.BONUS_SMALL_3;
        int medium3 = (int) BallisticRangefinder.BONUS_MEDIUM_3;
        int max3 = (int) BallisticRangefinder.BONUS_MAX_3;
        int hybridSmall1 = (int) (BallisticRangefinder.BONUS_SMALL_1 * BallisticRangefinder.HYBRID_MULT);
        int hybridSmall3 = (int) (BallisticRangefinder.BONUS_SMALL_3 * BallisticRangefinder.HYBRID_MULT);
        int hybridMedium3 = (int) (BallisticRangefinder.BONUS_MEDIUM_3 * BallisticRangefinder.HYBRID_MULT);
        int hybridMin = (int) BallisticRangefinder.HYBRID_BONUS_MIN;
        return lines(
                "Extends the base range of small and medium ballistic weapons, narrowing the gap to the reach of"
                        + " the bigger guns of their type. The size of the gain is set by the largest ballistic"
                        + " slot on the hull. Hybrid weapons gain much more; point-defense weapons gain nothing.",
                "If the largest ballistic slot is small or medium: small ballistic weapons +" + small1
                        + ". Hybrid weapons: small +" + hybridSmall1 + ", medium and large +" + hybridMin
                        + ". This bonus will not lift any weapon's base range past " + max1 + ".",
                "If the largest ballistic slot is large: small ballistic weapons +" + small3 + ", medium +"
                        + medium3 + ". Hybrid weapons: small +" + hybridSmall3 + ", medium +" + hybridMedium3
                        + ", large +" + hybridMin + ". This bonus will not lift any weapon's base range past "
                        + max3 + ".",
                "Other range modifiers are applied on top of the raised base range: a percentage range bonus grows"
                        + " this gain as well (+10% range makes it a tenth bigger), which most flat range bonuses"
                        + " do not get.");
    }

    static String highScatterAmp() {
        int damage = (int) HighScatterAmp.DAMAGE_BONUS_PERCENT;
        int threshold = (int) HighScatterAmp.RANGE_THRESHOLD;
        int cut = Math.round((1f - HighScatterAmp.RANGE_MULT) * 100f);
        return lines(
                "Beam weapons do " + damage + "% more damage, and the flux their hits put on shields counts as"
                        + " hard flux.",
                "Beam range is shortened: whatever lies past " + threshold + " units is reduced by " + cut + "%.",
                "Range bonuses that work by a percentage or a multiplier, the Integrated Targeting Unit among"
                        + " them, then scale the shortened range, since the cut is taken from base range first.");
    }

    static String energyBoltCoherer() {
        int crewed = (int) EnergyBoltCoherer.CREWED_RANGE_BONUS;
        int automated = (int) EnergyBoltCoherer.RANGE_BONUS;
        int casualties = (int) EnergyBoltCoherer.CREW_CASUALTIES;
        return lines(
                "Increases the base range of every energy and hybrid weapon that is not a beam: +" + crewed
                        + " on a ship with a crew, +" + automated + " on an automated ship.",
                "Crew losses in combat are " + casualties + "% higher.",
                "A percentage range bonus grows this gain as well (+10% range makes it a tenth bigger), because"
                        + " the gain is part of base range.");
    }

    static String missileAutoloader() {
        int extraCooldown = (int) MissileAutoloader.BASIC_COOLDOWN;
        return lines(
                "Reloads missile weapons in small missile slots a limited number of times, drawing on a single"
                        + " reload pool that all of them share.",
                "A weapon qualifies only if it sits in a small missile slot, has limited ammo and does not"
                        + " regenerate it. A full reload restores the weapon's base magazine, without the extra"
                        + " rounds that skills or hullmods may add.",
                "The pool depends on hull size and on how many small missile slots the hull has, empty or not;"
                        + " fewer slots give a bigger pool. Frigate: 6 with one slot, 4 with more. Destroyer: 9"
                        + " with one slot, 4 with more. Cruiser: 15 with one or two slots, 12 with three, 8 with"
                        + " more. Capital: 24 with up to three slots, 18 with four to six, 10 with more.",
                "Each reload uses up part of the pool; the refit screen shows how much each weapon costs. When"
                        + " too little is left, the weapon gets a partial reload. After a reload the weapon waits "
                        + extraCooldown + " extra seconds on top of its normal cooldown before it can fire.");
    }

    /** Formats a value with at most one decimal place; a zero decimal is dropped (1.5 prints "1.5", 2.0 prints "2"). */
    private static String oneDecimal(float value) {
        int tenths = Math.round(value * 10f);
        String sign = tenths < 0 ? "-" : "";
        int abs = Math.abs(tenths);
        return abs % 10 == 0 ? sign + (abs / 10) : sign + (abs / 10) + "." + (abs % 10);
    }

    private static String lines(String... paragraphs) {
        return String.join("\n", paragraphs);
    }
}
