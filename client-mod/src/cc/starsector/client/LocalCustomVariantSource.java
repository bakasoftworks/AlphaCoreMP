package cc.starsector.client;

import java.io.IOException;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;

import cc.starsector.client.net.ClientVariantDefinition;
import cc.starsector.client.net.ClientVariantJson;
import cc.starsector.client.net.LocalVariantIdLaw;

/**
 * The v0 client-local custom-variant FILE SOURCE (W15 v0; design of record):
 * a join pick {@code <id>} is "a local custom" when
 * {@code saves/common/cc_variants/<id>.variant.data} exists — read through the PUBLIC
 * {@link SettingsAPI#readTextFileFromCommon} (loader-B-clean: no {@code java.io.File} in the mod;
 * the game resolves {@code <saves>/common/<name>.data}), parsed under the server's own JSON law
 * ({@link ClientVariantJson}) and submitted ONCE per connect. Never {@code readJSONFromCommon}
 * (it memoizes — a re-edited file would submit stale). No bat / plan / env change: the id rides
 * the ordinary {@code ship=} → HELLO pick path.
 *
 * <p>Guards, in order: the {@link LocalVariantIdLaw} BEFORE composing the name (the game's own
 * check throws on {@code ..}/{@code :}); the 256 KB text cap ({@code ClientVariantJson}) before
 * the parse; the file's {@code variantId} must equal the stem (the HELLO pick and the VSUB's
 * echoed local id are the same handle — the door's held pick resolves on that equality);
 * {@code goalVariant} is forced false (a policy law — {@code VariantPolicy} refuses
 * true as {@code custom_shape_invalid}).
 */
final class LocalCustomVariantSource {
    /** The common-dir subfolder: {@code saves/common/cc_variants/}. */
    static final String COMMON_DIR = "cc_variants";
    static final String FILE_SUFFIX = ".variant";

    private LocalCustomVariantSource() {
    }

    static boolean isLocalIdLawful(String id) {
        return LocalVariantIdLaw.isLawful(id);
    }

    /** The common-file NAME the game resolves to {@code <saves>/common/<name>.data}. */
    static String commonName(String id) {
        return COMMON_DIR + "/" + id + FILE_SUFFIX;
    }

    /**
     * Load the local definition for {@code id}: {@code null} when no such file exists (the pick
     * is a server-catalog id or the default); {@link IllegalArgumentException} on a law
     * violation (id, JSON shape, record laws, stem mismatch); {@link IOException} on a read
     * failure or an empty file.
     */
    static ClientVariantDefinition load(String id) throws IOException {
        if (!isLocalIdLawful(id)) {
            throw new IllegalArgumentException("local variant id violates the file-id law"
                    + " ([0-9A-Za-z][0-9A-Za-z._-]*, no '..', <= " + LocalVariantIdLaw.MAX_CHARS
                    + " chars)");
        }
        SettingsAPI settings = Global.getSettings();
        String name = commonName(id);
        if (!settings.fileExistsInCommon(name)) {
            return null;
        }
        String text = settings.readTextFileFromCommon(name);
        if (text == null || text.isBlank()) {
            throw new IOException("local variant file is empty: saves/common/" + name + ".data");
        }
        ClientVariantDefinition def = ClientVariantJson.parse(text);
        if (!id.equals(def.variantId())) {
            throw new IllegalArgumentException("variantId '" + def.variantId()
                    + "' must equal the file stem '" + id + "' (the HELLO pick handle)");
        }
        if (def.goalVariant()) {
            PilotWatchLog.info("predict custom-variant: '" + id
                    + "' goalVariant=true forced FALSE (policy law, VariantPolicy custom_shape_invalid)");
            def = new ClientVariantDefinition(def.variantId(), def.hullId(), def.displayName(),
                    false, def.fluxVents(), def.fluxCapacitors(), def.hullMods(), def.permaMods(),
                    def.sMods(), def.sModdedBuiltIns(), def.suppressedMods(), def.wings(),
                    def.tags(), def.weaponGroups(), def.modules());
        }
        return def;
    }
}
