package cc.starsector.client.editor;

import com.fs.starfarer.api.combat.HullModEffect;
import com.fs.starfarer.api.impl.hullmods.BallisticRangefinder;
import com.fs.starfarer.api.impl.hullmods.ConvertedHangar;
import com.fs.starfarer.api.impl.hullmods.EnergyBoltCoherer;
import com.fs.starfarer.api.impl.hullmods.HighResSensors;
import com.fs.starfarer.api.impl.hullmods.HighScatterAmp;
import com.fs.starfarer.api.impl.hullmods.MissileAutoloader;
import com.fs.starfarer.api.loading.HullModSpecAPI;

/**
 * Vanilla's {@code hull_mods.csv} describes exactly two hullmods as
 * {@code <Tooltip created in script>} — {@code converted_hangar} and {@code hiressensors} — and paints their words
 * from {@code addPostDescriptionSection} at tooltip time. The editor's hover panel and the codex have no tooltip
 * maker, so they serve the editor's OWN words for those hullmods ({@link ScriptWords}: written fresh from a fact
 * sheet — no game text is carried in this jar), the numbers read off the vanilla effect class's
 * public static fields (a mod that retunes them in place keeps its numbers). The words are served only when the spec's
 * effect IS the vanilla class (a mod that swaps the script keeps its own text — the caller then keeps the game's
 * placeholder). No reflection: the mod jar is classloader-B denied.
 */
final class ScriptDescribed {

    /** The csv literal the game leaves in place of a description for a script-described hullmod (the hangar / sensors spelling). */
    static final String LITERAL = "<Tooltip created in script>";
    /**
     * The Ballistic Rangefinder's panel once read "&lt;tooltip created in the script due to lots of
     * highlighting&gt;": vanilla writes the sentinel in six spellings — that one, "…due to sections&gt;" (High Scatter Amplifier),
     * "…due to sections/other complexity&gt;" (Missile Autoloader), "&lt;tooltip created in the script&gt;" (Energy Bolt Coherer) and
     * "&lt;created by script&gt;" (the two shrouded mods) — and the exact-literal test let every one of them through to the panel as
     * raw angle-bracket text. One angle-bracketed phrase naming "script" is the family; a real description never starts with '&lt;'.
     */
    private static final java.util.regex.Pattern SENTINEL = java.util.regex.Pattern.compile("(?is)^\\s*<[^>]*script[^>]*>\\s*$");

    private ScriptDescribed() {
    }

    /** True when the game's description text is the script placeholder rather than words (any of the six spellings). */
    static boolean isPlaceholder(String text) {
        return text != null && (text.contains(LITERAL) || SENTINEL.matcher(text).matches());
    }

    /**
     * The editor's words for a script-described hullmod (paragraphs joined by {@code \n}), or {@code null} when this
     * mod is not one of the six it knows (or its script is not vanilla's) — the caller then keeps its text.
     */
    static String text(HullModSpecAPI m) {
        if (m == null || m.getId() == null) {
            return null;
        }
        HullModEffect effect;
        try {
            effect = m.getEffect();
        } catch (RuntimeException | LinkageError scriptBroke) {
            return null;
        }
        try {
            switch (m.getId()) {
                case "converted_hangar":
                    return effect instanceof ConvertedHangar ? ScriptWords.convertedHangar() : null;
                case "hiressensors":
                    return effect instanceof HighResSensors ? ScriptWords.highResSensors() : null;
                // The four weapon-side script-described mods (the shrouded pair has no words here; the
                // callers drop the sentinel to an empty description)
                case "ballistic_rangefinder":
                    return effect instanceof BallisticRangefinder ? ScriptWords.ballisticRangefinder() : null;
                case "high_scatter_amp":
                    return effect instanceof HighScatterAmp ? ScriptWords.highScatterAmp() : null;
                case "coherer":
                    return effect instanceof EnergyBoltCoherer ? ScriptWords.energyBoltCoherer() : null;
                case "missile_autoloader":
                    return effect instanceof MissileAutoloader ? ScriptWords.missileAutoloader() : null;
                default:
                    return null;
            }
        } catch (LinkageError implMissing) {   // the API impl class failed to link: keep the game's placeholder
            return null;
        }
    }
}
