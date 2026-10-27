package cc.starsector.server.ops;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

import cc.starsector.server.match.ContentFingerprintMint;
import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.MatchPasswords;
import cc.starsector.server.match.MatchShapes;
import cc.starsector.server.runtime.HandshakeCodec;

/**
 * The ops map-size / station / backdrop APPLY family carved out of {@link ServerOpsCore}:
 *
 * the ONE {@code map.half} source row of {@code cc-server.properties} (reader, upsert editor,
 * reject-never-clamp parse, field prefill) plus the three boot-time descriptor re-author applies
 * that share one write leg - {@link #applyMapSize},
 * {@link #applyStationVariant} and {@link #applyTerrainBackdrop} -
 * with their combo prefills, the descriptor-set / deployed-root resolvers and the staged-tree
 * helpers. WHY a sibling: {@code ServerOpsCore} was past its size tier, and the release-parity
 * package match-setting apply is designed
 * to call these applies, so the family moved FIRST as a pure move (the {@link OpsMatchRows} carve
 * law): names, signatures, visibility, javadoc and bodies VERBATIM - provenance and law
 * citations inside each javadoc are the originals; only links to members that stayed in the core
 * were re-targeted. Dependency-clean: no code here calls back into the core. Callers = the web console's
 * prefills and Applies ({@link OpsConsole}), {@link ServerOpsLaunch}, {@link ServerOpsCore},
 * {@link ServeLauncher} and the match shapes; selftest rows =
 * {@link ServerOpsCoreSelftest} (the map-size / station / backdrop blocks) + {@code OpsWriterTextRows}
 * (OW1 {@code withMapHalfRow}).
 *
 * <p>The PACKAGE leg:
 *
 * {@link #applyPackageMatchSetting} re-makes ONE offered shape ({@code <pkg>/match/<picked>.txt})
 * per Apply - map size, station, backdrop or the NEW deploy CR ({@link #applyDeployCr} is its
 * owner-shape twin) - and re-keys that shape's sidecar {@code match=} component in place
 * ({@link ContentFingerprintMint#rekeySidecarLines}; no stage, no deployed tree, no props row:
 * the serve reads the half and the CR from the descriptor). Every descriptor + sidecar pair, owner
 * or package, lands through the ONE pair writer {@link #writeSidecar}. {@link #matchSettingsOf}
 * is the shells' prefill; the texts are {@link #texts()}.
 */
public final class OpsMatchApply {
    private OpsMatchApply() {
    }

    /** The ONE arena-size source row (the {@code MatchDescriptors} single-source law). */
    public static final String MAP_HALF_KEY = "map.half";
    /** Marker above an APPENDED row only — a replaced row keeps its hand-written comment. */
    public static final String MAP_HALF_COMMENT =
            "# combat map half-extent in su, both axes (ops map-size apply rewrites knob +"
                    + " live descriptors together)";
    /** Reject-never-clamp bounds for the map-size apply ({@link #parseMapHalf}), whole su. */
    public static final float MAP_HALF_MIN = 2000f;
    public static final float MAP_HALF_MAX = 60000f;

    /** The paths the shells hand {@link #applyMapSize} — system-property
     * overrides (the {@code cc.ops.briefFile} idiom) so the committed verifier drives a
     * fixture tree, never the real repo. Defaults = the LIVE descriptor pair (the
     * no-mass-rotate rule: every other descriptor re-authors on use) + the deployed client mod. */
    public static final String MAP_SIZE_DESCRIPTORS_PROPERTY = "cc.ops.mapSizeDescriptors";
    public static final String MAP_SIZE_DEPLOYED_ROOT_PROPERTY = "cc.ops.deployedModRoot";
    private static final String DEFAULT_MAP_SIZE_DESCRIPTORS =
            "generated/joiner-kit/match-standing.txt;"
                    + "generated/joiner-kit/match-bastion-fortress.txt";
    private static final String DEFAULT_DEPLOYED_MOD_ROOT =
            "C:/Program Files (x86)/Fractal Softworks/Starsector/mods/CC_Client";

    /** The paths the shells hand {@link #applyStationVariant} — its OWN set
     * (the {@link #MAP_SIZE_DESCRIPTORS_PROPERTY} idiom) because the map-size default pair
     * contains a stationless shape ({@code match-standing.txt}) and a station apply refuses
     * the WHOLE set on any stationless descriptor. Default = the standing fortress pair. */
    public static final String STATION_DESCRIPTORS_PROPERTY = "cc.ops.stationDescriptors";
    private static final String DEFAULT_STATION_DESCRIPTORS =
            "generated/joiner-kit/match-bastion-fortress.txt;"
                    + "generated/joiner-kit/match-bastion-fortress-ab.txt";

    // ------------------------------------------------------ map-size apply

    /** Raw value of the LAST uncommented {@code map.half=} row (the {@link ServerOpsCore#tickRateRowValue}
     * grammar: line-start exact, last-wins, commented mentions never match). */
    public static String mapHalfRowValue(List<String> lines) {
        String value = null;
        for (String line : lines) {
            if (line.startsWith(MAP_HALF_KEY + "=")) {
                value = line.substring(MAP_HALF_KEY.length() + 1);
            }
        }
        return value;
    }

    /**
     * The file's lines with the {@code map.half} row upserted. Unlike
     * {@link ServerOpsCore#withTickRateRow} there is NO default-removes rung: the row is a standing
     * documented knob (the single arena-size source) and the apply's whole job is to make the value
     * explicit — replace the first uncommented row in place (keeping any hand comment above
     * it; Properties-shadowed duplicates drop), or append marker + row at the end.
     */
    public static List<String> withMapHalfRow(List<String> lines, float mapHalf) {
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String line : lines) {
            if (line.startsWith(MAP_HALF_KEY + "=")) {
                if (!placed) {
                    out.add(MAP_HALF_KEY + "=" + mapHalfText(mapHalf));
                    placed = true;
                }
            } else {
                out.add(line);
            }
        }
        if (!placed) {
            out.add(MAP_HALF_COMMENT);
            out.add(MAP_HALF_KEY + "=" + mapHalfText(mapHalf));
        }
        return out;
    }

    /** Row/report text for a validated whole-su half — the file says {@code 9000}, never
     * {@code 9000.0} (the existing hand-written row's format). */
    public static String mapHalfText(float mapHalf) {
        return Integer.toString(Math.round(mapHalf));
    }

    /**
     * Strict map-half parse ({@code simSpeedLine} reject-never-clamp idiom): finite, whole
     * su, inside [{@link #MAP_HALF_MIN}, {@link #MAP_HALF_MAX}]. A bad value must never
     * silently mint — refusal happens HERE, before any write.
     */
    public static float parseMapHalf(String text) {
        String trimmed = text == null ? "" : text.trim();
        float parsed;
        try {
            parsed = Float.parseFloat(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("map half must be a number in ["
                    + mapHalfText(MAP_HALF_MIN) + ", " + mapHalfText(MAP_HALF_MAX)
                    + "] su, got '" + trimmed + "'");
        }
        if (!Float.isFinite(parsed) || parsed < MAP_HALF_MIN || parsed > MAP_HALF_MAX) {
            throw new IllegalArgumentException("map half must be in ["
                    + mapHalfText(MAP_HALF_MIN) + ", " + mapHalfText(MAP_HALF_MAX)
                    + "] su, got '" + trimmed + "'");
        }
        if (parsed != Math.rint(parsed)) {
            throw new IllegalArgumentException(
                    "map half must be a whole su count, got '" + trimmed + "'");
        }
        return parsed;
    }

    /** The descriptor files the shells' map-size apply re-authors: the ';'-separated
     * {@link #MAP_SIZE_DESCRIPTORS_PROPERTY} override when set, else the LIVE pair. */
    public static List<Path> mapSizeDescriptorFiles() {
        List<Path> out = new ArrayList<>();
        for (String p : System.getProperty(MAP_SIZE_DESCRIPTORS_PROPERTY,
                DEFAULT_MAP_SIZE_DESCRIPTORS).split(";")) {
            if (!p.isBlank()) {
                out.add(Paths.get(p.trim()));
            }
        }
        return out;
    }

    /** A PACKAGE's one standing descriptor as a prefill list: the {@code match.descriptor} row of
     * {@code propsLines}, resolved beside {@code configFile} (the serve's rule,
     * {@link ServerOpsCore#configRelative}); an absent or blank row = an empty list, and the
     * prefills fall back to their code default. WHY: the development defaults above are the owner
     * box's standing pair, which a package never carries - before this a package window's backdrop
     * and station prefills opened at their code defaults with a descriptor-unreadable line on
     * stderr, while owner mode prefilled from the standing match. The package applies
     * already act on the picked shape ({@link #applyPackageMatchSetting}); this is the read side.
     * Pinned by {@code OpsMatchApplySelftest} {@code pkg-prefill-standing}. */
    public static List<Path> standingDescriptorFiles(List<String> propsLines, Path configFile) {
        String row = ServerOpsCore.descriptorRowValue(propsLines);
        if (row == null || row.isBlank()) {
            return List.of();
        }
        return List.of(ServerOpsCore.configRelative(configFile, row));
    }

    /** The fortress descriptor set the station apply re-authors
     * ({@link #STATION_DESCRIPTORS_PROPERTY} override when set). */
    public static List<Path> stationDescriptorFiles() {
        List<Path> out = new ArrayList<>();
        for (String p : System.getProperty(STATION_DESCRIPTORS_PROPERTY,
                DEFAULT_STATION_DESCRIPTORS).split(";")) {
            if (!p.isBlank()) {
                out.add(Paths.get(p.trim()));
            }
        }
        return out;
    }

    /** The station-family combo prefill — the FIRST live descriptor's station
     * row mapped to its family (honest prefill: the combo opens showing what is ACTUALLY
     * baked). Unreadable/missing/stationless falls back to the code default's family; a file past
     * {@link ServeLauncher#MAX_CONFIG_BYTES} is unreadable (refused, never truncated). */
    public static String stationFamilyPrefill(List<Path> descriptorFiles) {
        try {
            MatchDescriptor decoded = MatchDescriptorCodec.decode(ServerOpsCore.readCappedLines(
                    descriptorFiles.get(0)));
            String stationId = stationVariantIdOrNull(decoded);
            if (stationId != null) {
                return MatchDescriptors.familyForStationVariant(stationId);
            }
        } catch (IOException | RuntimeException unreadable) {
            // fall through to the code default's family
        }
        return MatchDescriptors.familyForStationVariant(
                MatchDescriptors.BASTION_STATION_VARIANT_ID);
    }

    /** The backdrop combo prefill (the {@link #stationFamilyPrefill} idiom) —
     * the FIRST live descriptor's {@code map.terrain} word (honest prefill: the combo opens
     * showing what is ACTUALLY baked). Unreadable/missing (a file past
     * {@link ServeLauncher#MAX_CONFIG_BYTES} included) falls back to
     * {@link MatchDescriptor#TERRAIN_NONE} with ONE stderr line (fail-loud law).
     * */
    public static String terrainBackdropPrefill(List<Path> descriptorFiles) {
        if (descriptorFiles.isEmpty()) {
            System.err.println("[ops] backdrop prefill: no match.descriptor row - combo opens at "
                    + MatchDescriptor.TERRAIN_NONE);
            return MatchDescriptor.TERRAIN_NONE;
        }
        try {
            return MatchDescriptorCodec.decode(ServerOpsCore.readCappedLines(
                    descriptorFiles.get(0))).terrain();
        } catch (IOException | RuntimeException unreadable) {
            System.err.println("[ops] backdrop prefill: descriptor unreadable ("
                    + unreadable + ") - combo opens at " + MatchDescriptor.TERRAIN_NONE);
            return MatchDescriptor.TERRAIN_NONE;
        }
    }

    /** The deployed client-mod root the sidecar re-mint stages from
     * ({@link #MAP_SIZE_DEPLOYED_ROOT_PROPERTY} override when set). */
    public static Path mapSizeDeployedModRoot() {
        return Paths.get(System.getProperty(MAP_SIZE_DEPLOYED_ROOT_PROPERTY,
                DEFAULT_DEPLOYED_MOD_ROOT));
    }

    /** Map-size field prefill for both shells (the {@code tickRateField} prefill idiom):
     * the explicit {@code map.half} row when present, else the certified default. */
    public static String mapHalfFieldPrefill(List<String> lines) {
        String row = mapHalfRowValue(lines);
        return row == null || row.trim().isEmpty()
                ? mapHalfText(MatchDescriptors.CERTIFIED_MAP_HALF) : row.trim();
    }

    /** {@link #applyMapSize} outcome: {@code changed} false = the no-change fast path
     * (nothing written, seat tokens stay live); {@code report} = per-artifact rows the
     * shells surface verbatim (new match.ids + {@link #ROTATED_NOTE}). */
    public record MapSizeResult(boolean changed, float mapHalf, List<String> report) {
    }

    /**
     * The ops map-size apply — rewrite the ONE {@code map.half} source
     * row and re-author the LIVE descriptor set to the same value in one call
     * (validate-all-up-front, then write), retiring the split-brain class (engine at
     * the knob, clients at a stale descriptor). Per descriptor: shape is READ from the
     * existing file (never hardcoded), the re-mint rotates match.id + seat tokens (fresh
     * identity ⇒ the old seat tokens no longer get in; every join window fetches the new match,
     * so no kit is re-packed), and the door sidecar is re-minted in-process against the DEPLOYED client tree + the
     * new descriptor text ({@code arm-watch.ps1}'s staging shape, via the ONE minter
     * {@link ContentFingerprintMint#mintSidecarLines}). Paths are EXPLICIT so the verifier
     * drives a temp fixture tree, never the repo. The size applies at the NEXT serve boot —
     * a running serve keeps its loaded descriptor. Each descriptor + sidecar PAIR lands through
     * {@link #writeSidecar} (sidecar first; a failed descriptor move restores the old sidecar);
     * an IO failure mid-set leaves the earlier pairs re-authored and the rest untouched, and a
     * pair left split (the restore failed too) fails loud at the armed door's {@code match=}
     * composite check (and, in a package, {@code ServeLauncher}'s {@code sidecar_pair}
     * refusal) - there is no serve-entry lockstep guard.
     */
    public static MapSizeResult applyMapSize(Path propsFile, List<Path> descriptorFiles,
            Path deployedModRoot, String mapHalfText) throws IOException {
        float half = parseMapHalf(mapHalfText);
        if (!Files.isRegularFile(propsFile)) {
            throw new IllegalArgumentException("no properties file at " + propsFile);
        }
        if (!Files.isRegularFile(deployedModRoot.resolve("mod_info.json"))
                || !Files.isRegularFile(deployedModRoot.resolve("jars/cc-client-mod.jar"))) {
            throw new IllegalArgumentException("deployed client mod root " + deployedModRoot
                    + " is missing mod_info.json / jars/cc-client-mod.jar - cannot re-mint"
                    + " door sidecars against it");
        }
        // Decode AND pre-mint per descriptor up front: an unreadable file, a codec break, or
        // a shape spec mint() refuses must bounce the whole apply before the first write.
        List<MatchDescriptor> current = new ArrayList<>();
        List<MatchDescriptor> minted = new ArrayList<>();
        for (Path file : descriptorFiles) {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("no descriptor file at " + file);
            }
            MatchDescriptor decoded;
            try {
                decoded = MatchDescriptorCodec.decode(
                        Files.readAllLines(file, StandardCharsets.UTF_8));
                // Thread the decoded deployCr through the re-mint — the
                // map-size apply owns ONLY the map rows; resetting CR to certified here
                // would silently undo a configured match.deployCr at every size change.
                // Same law for the station family — a fortress descriptor's
                // CURRENT station variant threads through, never resets to default.
                String stationId = stationVariantIdOrNull(decoded);
                // the never-silently-reset law, 4th leg
                // (backdrop word threads through); the seat count threads through too
                // (a 40-player file re-mints at 40, never the spec's stock count)
                minted.add(MatchDescriptors.remint(decoded, half, decoded.deployCr(), stationId,
                        decoded.terrain()));
            } catch (RuntimeException bad) {
                throw new IllegalArgumentException("descriptor " + file
                        + " cannot be re-authored: " + bad.getMessage());
            }
            current.add(decoded);
        }
        boolean descriptorsAtN = true;
        for (MatchDescriptor d : current) {
            if (d.halfWidth() != half || d.halfHeight() != half) {
                descriptorsAtN = false;
            }
        }
        List<String> report = new ArrayList<>();
        // The config file's read, the no-change test over it, the compose and
        // the write run as ONE step under ServerOpsLaunch.PROPS_LOCK (the lock every console writer of that file
        // takes), so a write by another holder - the WATCH lease's clear, a Start's posture write - can never land
        // between this read and this write and be overwritten by the older copy.
        synchronized (ServerOpsLaunch.PROPS_LOCK) {
            String propsRaw = Files.readString(propsFile, StandardCharsets.UTF_8);
            List<String> propsNow = propsRaw.lines().toList();
            String rowNow = mapHalfRowValue(propsNow);
            float resolvedNow;
            try {
                resolvedNow = rowNow == null ? MatchDescriptors.CERTIFIED_MAP_HALF
                        : Float.parseFloat(rowNow.trim());
            } catch (NumberFormatException malformed) {
                resolvedNow = Float.NaN; // malformed row = a change (the rewrite repairs it)
            }
            if (resolvedNow == half && descriptorsAtN) {
                return new MapSizeResult(false, half, List.of("map.half already "
                        + mapHalfText(half) + " everywhere - nothing written; kits + seat"
                        + " tokens stay live"));
            }
            List<String> propsWant = withMapHalfRow(propsNow, half);
            if (!propsWant.equals(propsNow)) {
                String sep = propsRaw.contains("\r\n") ? "\r\n" : "\n";
                Files.writeString(propsFile, String.join(sep, propsWant) + sep,
                        StandardCharsets.UTF_8);
                report.add(propsFile + ": map.half row -> " + mapHalfText(half));
            } else {
                report.add(propsFile + ": map.half row already " + mapHalfText(half));
            }
        }
        reauthorDescriptors(descriptorFiles, minted, deployedModRoot, report,
                "map.half=" + mapHalfText(half));
        report.add(ROTATED_NOTE);
        return new MapSizeResult(true, half, report);
    }

    /**
     * The last report row of every owner Apply that re-minted (map size, station, backdrop, deploy CR): the new
     * identity shuts out the old seat tokens, and no kit is touched: a password-armed serve hands the new match to
     * every join window at its next Join, Refresh or R (a serve with no match password hands none).
     *
     */
    static final String ROTATED_NOTE = "match.id rotated: the old seat tokens are DEAD. Nothing to"
            + " re-pack or send - the kit stays the same; after Start every join window fetches the new match"
            + " (anyone in a game presses R in the black join-cc window).";

    /**
     * The shared descriptor re-author WRITE leg of the owner applies ({@link #applyMapSize} /
     * {@link #applyStationVariant} / {@link #applyTerrainBackdrop} / {@link #applyDeployCr}):
     * stage the deployed client tree ONCE, then per descriptor re-mint its door sidecar against
     * the staged tree carrying the fresh text ({@code arm-watch.ps1}'s staging shape, via the ONE
     * minter {@link ContentFingerprintMint#mintSidecarLines}) and land the pair through the ONE
     * pair writer {@link #writeSidecar} (sidecar first, atomic moves;
     * it replaced a descriptor-first plain write). {@code detail} is the
     * applied field's report-row rendering (constant per apply).
     */
    private static void reauthorDescriptors(List<Path> descriptorFiles,
            List<MatchDescriptor> minted, Path deployedModRoot, List<String> report,
            String detail) throws IOException {
        Path stage = Files.createTempDirectory("cc-descriptor-reauthor");
        try {
            Path stagedRoot = stage.resolve("CC_Client");
            copyTree(deployedModRoot, stagedRoot);
            for (int i = 0; i < descriptorFiles.size(); i++) {
                Path file = descriptorFiles.get(i);
                MatchDescriptor fresh = minted.get(i);
                String text = MatchDescriptorCodec.encode(fresh);
                Files.writeString(stagedRoot.resolve("match.txt"), text);
                writeSidecar(file, text, ContentFingerprintMint.mintSidecarLines(stagedRoot));
                report.add(file + ": re-authored shape=" + fresh.shape() + " " + detail
                        + " match.id=" + fresh.matchId() + " (+ .fingerprint re-minted)");
            }
        } finally {
            try {
                deleteTree(stage);
            } catch (IOException leak) {
                // best-effort cleanup - a leaked temp stage is benign, the apply landed
            }
        }
    }

    /** The decoded descriptor's station roster-row variant id, or null when the shape fields
     *  no station (the station family is a fortress-class property). */
    private static String stationVariantIdOrNull(MatchDescriptor descriptor) {
        for (MatchDescriptor.RosterRow row : descriptor.roster()) {
            if (MatchDescriptors.isStationVariantId(row.variantId())) {
                return row.variantId();
            }
        }
        return null;
    }

    /** {@link #applyStationVariant} outcome (the {@code MapSizeResult} idiom): {@code changed}
     * false = the no-change fast path (nothing written, kits/tokens stay live). */
    public record StationVariantResult(boolean changed, String stationVariantId,
            List<String> report) {
    }

    /**
     * Modeled on {@link #applyMapSize}: re-author the LIVE
     * fortress descriptor set to the {@code family} station variant
     * ({@code MatchDescriptors.stationVariantForFamily} — unknown family refuses). The
     * station is DESCRIPTOR-owned, never a props knob: the client mints the station hull
     * from ITS {@code match.txt} (the copy its join window fetched), and the three families field different module
     * clusters (24/23/12) — a server-side-only knob would field different hulls on the two sides =
     * structural desync. {@code propsFile} is validated for environment sanity but NO row is
     * written. Boot-time only by construction (a running serve keeps its loaded descriptor —
     * the {@link #applyMapSize} law); a game holding the old match is refused LOUDLY at the door
     * ({@code match_stale}: the session print's {@code match=} term carries match.id; there is no serve lockstep
     * guard). Validate-all-up-front, then write; the
     * re-mint ROTATES match.id + seat tokens (fresh identity ⇒ the old seat tokens no longer get
     * in; every join window fetches the new match); each descriptor's CURRENT map half + deploy CR thread through unchanged
     * (the never-silently-reset law, both legs). A descriptor with no station
     * row refuses the WHOLE apply before any write.
     */
    public static StationVariantResult applyStationVariant(Path propsFile,
            List<Path> descriptorFiles, Path deployedModRoot, String family)
            throws IOException {
        String wantId = MatchDescriptors.stationVariantForFamily(family);
        if (!Files.isRegularFile(propsFile)) {
            throw new IllegalArgumentException("no properties file at " + propsFile);
        }
        if (!Files.isRegularFile(deployedModRoot.resolve("mod_info.json"))
                || !Files.isRegularFile(deployedModRoot.resolve("jars/cc-client-mod.jar"))) {
            throw new IllegalArgumentException("deployed client mod root " + deployedModRoot
                    + " is missing mod_info.json / jars/cc-client-mod.jar - cannot re-mint"
                    + " door sidecars against it");
        }
        List<MatchDescriptor> minted = new ArrayList<>();
        boolean allAtWant = true;
        for (Path file : descriptorFiles) {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("no descriptor file at " + file);
            }
            try {
                MatchDescriptor decoded = MatchDescriptorCodec.decode(
                        Files.readAllLines(file, StandardCharsets.UTF_8));
                String current = stationVariantIdOrNull(decoded);
                if (current == null) {
                    throw new IllegalArgumentException("shape " + decoded.shape()
                            + " fields no station roster row - the station family applies"
                            + " only to fortress-class descriptors");
                }
                if (!wantId.equals(current)) {
                    allAtWant = false;
                }
                // the never-silently-reset law, 4th leg
                // (backdrop word threads through)
                minted.add(MatchDescriptors.remint(decoded, MatchDescriptors.serveMapHalf(decoded),
                        decoded.deployCr(), wantId, decoded.terrain()));
            } catch (RuntimeException bad) {
                throw new IllegalArgumentException("descriptor " + file
                        + " cannot be re-authored: " + bad.getMessage());
            }
        }
        if (allAtWant) {
            return new StationVariantResult(false, wantId, List.of("station variant already "
                    + wantId + " everywhere - nothing written; kits + seat tokens stay live"));
        }
        List<String> report = new ArrayList<>();
        reauthorDescriptors(descriptorFiles, minted, deployedModRoot, report,
                "station=" + wantId);
        report.add(ROTATED_NOTE);
        return new StationVariantResult(true, wantId, report);
    }

    /** {@link #applyTerrainBackdrop} outcome (the {@code StationVariantResult} idiom):
     * {@code changed} false = the no-change fast path (nothing written, kits/tokens stay
     * live). */
    public record BackdropResult(boolean changed, String terrain, List<String> report) {
    }

    /**
     * Modeled on {@link #applyStationVariant}: re-author ALL standing
     * descriptors to the backdrop word {@code wordText} ({@code map.terrain} — case + whitespace
     * folded, then the EXACT {@link MatchDescriptor#TERRAIN_WORDS} table; reject-never-clamp).
     * The backdrop is DESCRIPTOR-owned (the client mission builder applies it at define time
     * from ITS {@code match.txt}, the copy its join window fetched), never a props knob: {@code propsFile} is
     * validated for environment sanity but NO row is written. Boot-time only (a running serve keeps its
     * loaded descriptor). Applies to EVERY descriptor handed in — the shells pass
     * {@link #mapSizeDescriptorFiles()} since every shape may carry a backdrop (no station row
     * needed). Validate-all-up-front, then write; the re-mint ROTATES match.id + seat tokens
     * (the old seat tokens no longer get in; every join window fetches the new match); each descriptor's CURRENT map half, deploy CR and
     * station variant thread through unchanged (the never-silently-reset law).
     *
     */
    public static BackdropResult applyTerrainBackdrop(Path propsFile,
            List<Path> descriptorFiles, Path deployedModRoot, String wordText)
            throws IOException {
        String word = wordText == null ? "" : wordText.trim().toLowerCase(Locale.ROOT);
        if (!MatchDescriptor.isTerrainWord(word)) {
            throw new IllegalArgumentException("map.terrain must be one of "
                    + String.join("|", MatchDescriptor.TERRAIN_WORDS) + " (got '" + wordText
                    + "')");
        }
        if (!Files.isRegularFile(propsFile)) {
            throw new IllegalArgumentException("no properties file at " + propsFile);
        }
        if (!Files.isRegularFile(deployedModRoot.resolve("mod_info.json"))
                || !Files.isRegularFile(deployedModRoot.resolve("jars/cc-client-mod.jar"))) {
            throw new IllegalArgumentException("deployed client mod root " + deployedModRoot
                    + " is missing mod_info.json / jars/cc-client-mod.jar - cannot re-mint"
                    + " door sidecars against it");
        }
        List<MatchDescriptor> minted = new ArrayList<>();
        boolean allAtWant = true;
        for (Path file : descriptorFiles) {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("no descriptor file at " + file);
            }
            try {
                MatchDescriptor decoded = MatchDescriptorCodec.decode(
                        Files.readAllLines(file, StandardCharsets.UTF_8));
                allAtWant &= word.equals(decoded.terrain());
                String stationId = stationVariantIdOrNull(decoded);
                float half = MatchDescriptors.serveMapHalf(decoded);
                minted.add(MatchDescriptors.remint(decoded, half, decoded.deployCr(), stationId, word));
            } catch (RuntimeException bad) {
                throw new IllegalArgumentException("descriptor " + file
                        + " cannot be re-authored: " + bad.getMessage());
            }
        }
        if (allAtWant) {
            return new BackdropResult(false, word, List.of("backdrop already " + word
                    + " everywhere - nothing written; kits + seat tokens stay live"));
        }
        List<String> report = new ArrayList<>();
        reauthorDescriptors(descriptorFiles, minted, deployedModRoot, report,
                "backdrop=" + word);
        report.add(ROTATED_NOTE);
        return new BackdropResult(true, word, report);
    }

    // ------------------------- deploy CR + the package Applies

    /** {@link #applyDeployCr} outcome (the {@code StationVariantResult} idiom): {@code changed}
     * false = nothing written. */
    public record DeployCrResult(boolean changed, float deployCr, List<String> report) {
    }

    /**
     * The deploy-CR apply, owner shape:
     * re-author EVERY descriptor handed in to
     * {@code percentText} ({@link #parseDeployCrPercent}: a whole % 1..100 = {@code pct / 100f} in
     * (0, 1]); each descriptor's CURRENT half, station and backdrop thread through (the
     * never-silently-reset law). DESCRIPTOR-owned like the station / backdrop applies: the
     * client reads CR from ITS kit {@code match.txt}, the serve from its descriptor;
     * {@code propsFile} is validated, NO row is written. Unchanged = every descriptor already at
     * {@code Math.round(cr * 100) == pct}. Owner shells do not show it today (the field is
     * package-only); the package leg is {@link #applyPackageMatchSetting} {@link Op#DEPLOY_CR}.
     *
     */
    public static DeployCrResult applyDeployCr(Path propsFile, List<Path> descriptorFiles,
            Path deployedModRoot, String percentText) throws IOException {
        float cr = parseDeployCrPercent(percentText);
        int pct = Math.round(cr * 100);
        if (!Files.isRegularFile(propsFile)) {
            throw new IllegalArgumentException("no properties file at " + propsFile);
        }
        if (!Files.isRegularFile(deployedModRoot.resolve("mod_info.json"))
                || !Files.isRegularFile(deployedModRoot.resolve("jars/cc-client-mod.jar"))) {
            throw new IllegalArgumentException("deployed client mod root " + deployedModRoot
                    + " is missing mod_info.json / jars/cc-client-mod.jar - cannot re-mint"
                    + " door sidecars against it");
        }
        List<MatchDescriptor> minted = new ArrayList<>();
        boolean allAtWant = true;
        for (Path file : descriptorFiles) {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("no descriptor file at " + file);
            }
            try {
                MatchDescriptor decoded = MatchDescriptorCodec.decode(
                        Files.readAllLines(file, StandardCharsets.UTF_8));
                allAtWant &= Math.round(decoded.deployCr() * 100) == pct;
                minted.add(MatchDescriptors.remint(decoded, MatchDescriptors.serveMapHalf(decoded), cr,
                        stationVariantIdOrNull(decoded), decoded.terrain()));
            } catch (RuntimeException bad) {
                throw new IllegalArgumentException("descriptor " + file
                        + " cannot be re-authored: " + bad.getMessage());
            }
        }
        if (allAtWant) {
            return new DeployCrResult(false, cr, List.of("deploy CR already " + pct
                    + "% everywhere - nothing written; kits + seat tokens stay live"));
        }
        List<String> report = new ArrayList<>();
        reauthorDescriptors(descriptorFiles, minted, deployedModRoot, report, "deployCr=" + cr);
        report.add(ROTATED_NOTE);
        return new DeployCrResult(true, cr, report);
    }

    /**
     * The ONE deploy-CR percent parser (the package Apply and New match shape both set CR
     * through it): trimmed ASCII digits only (no sign, point or exponent), a whole number 1..100,
     * returned as {@code pct / 100f} in (0, 1] (the {@code MatchDescriptor} {@code rules.deployCr}
     * domain; 70 → 0.7f, the certified default). Anything else throws {@link ApplyRefused}
     * {@code deploy_cr} carrying {@link #R8_DEPLOY_CR} (an {@link IllegalArgumentException}).
     *
     */
    public static float parseDeployCrPercent(String text) {
        String t = text == null ? "" : text.trim();
        boolean digits = !t.isEmpty() && t.length() <= 9;
        for (int i = 0; digits && i < t.length(); i++) {
            digits = t.charAt(i) >= '0' && t.charAt(i) <= '9';
        }
        int pct = digits ? Integer.parseInt(t) : -1;
        if (pct < 1 || pct > 100) {
            throw refusedValue(Op.DEPLOY_CR, text);
        }
        return pct / 100f;
    }

    /**
     * The Players field: trimmed ASCII digits, at most 3, naming 1..{@link MatchDescriptors#MAX_PLAYERS}; anything
     * else throws {@link ApplyRefused} {@code players} carrying {@link #R9_PLAYERS}. The grammar matches
     * {@code SourceSeatCap.parseCap} (the D1 pre-commit) so a value the pre-write check
     * accepts is one the boot accepts.
     */
    public static int parsePlayers(String text) {
        String t = text == null ? "" : text.trim();
        boolean digits = !t.isEmpty() && t.length() <= 3;
        for (int i = 0; digits && i < t.length(); i++) {
            digits = t.charAt(i) >= '0' && t.charAt(i) <= '9';
        }
        int players = digits ? Integer.parseInt(t) : -1;
        if (players < 1 || players > MatchDescriptors.MAX_PLAYERS) {
            throw new ApplyRefused("players", R9_PLAYERS.replace("<x>", shown(text)));
        }
        return players;
    }

    /** The package Applies; {@link #what()} = the {@code <what>} word of the
     *  texts. {@link #PLAYERS}: the
     *  picked shape's seat count re-minted in place: the value takes the New-shape dialog's Players
     *  grammar (1..{@link MatchDescriptors#MAX_PLAYERS}, else {@link #R9_PLAYERS}), then the shape's
     *  own players range (else {@link #R10_PLAYERS_RANGE}); both refusals carry token {@code players}. */
    public enum Op {
        SIZE("map size"), STATION("station type"), BACKDROP("battle background"),
        DEPLOY_CR("deploy CR"), PLAYERS("player count"),
        /** The fortress / PvP switch, run by Start only
         *  ({@link #applyPvp}); never offered by the match-settings route, so no Apply button reaches it. */
        PVP("PvP mode");

        private final String what;

        Op(String what) {
            this.what = what;
        }

        public String what() {
            return what;
        }
    }

    /**
     * A package Apply outcome: {@code changed} = the shape's match was
     * re-minted (a new match.id; a game holding the old one is refused {@code match_stale} at the door, and every
     * join window fetches the new one); {@code repaired} = the
     * sidecar's {@code match=} did NOT name the descriptor's match.id before this Apply (a torn
     * pair; it does now); {@code note} = the host-facing sentence
     * ({@link OpsMatchApply#CHANGED} / {@link OpsMatchApply#UNCHANGED} /
     * {@link OpsMatchApply#REPAIRED}, filled); {@code matchTextChanged} = {@code changed}: the match itself
     * changed (the field keeps its wire name, the web answer's JSON key) - the page then re-reads its config;
     * a repair keeps the match; {@code settings} = the shape's values
     * after the Apply (the prefill). A refusal is THROWN as {@link ApplyRefused}.
     *
     */
    public record ApplyResult(boolean changed, boolean repaired, String note,
            boolean matchTextChanged, MatchSettings settings) {
    }

    /**
     * One shape's four Apply values as the fields show them: {@code half} whole
     * su ("9000"), {@code station} its family or {@code "-"} (no station), {@code backdrop} the
     * {@code map.terrain} word, {@code crPercent} {@code Math.round(cr * 100)} ("70");
     * {@code problem} null, or a bounded sentence (the four values blank) when the file cannot be
     * read / decoded - the prefill never throws to the EDT.
     *
     */
    public record MatchSettings(String half, String station, String backdrop, String crPercent,
            String problem) {
    }

    /**
     * A refused package Apply: {@link #getMessage()} = the host-facing sentence (one of the
     * texts, filled), {@link #token()} = a bounded word ({@code server_live}, {@code no_station},
     * {@code map_size}, {@code sidecar_damaged}, {@code other_version}, {@code not_offered},
     * {@code write_failed}, {@code write_torn}, {@code deploy_cr}, {@code station_word},
     * {@code backdrop_word}, {@code players}) for a log row or a row's assert - never the value or a path.
     *
     */
    public static final class ApplyRefused extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String token;

        ApplyRefused(String token, String sentence) {
            super(sentence);
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    /** The package match folder under the package root the offered set lists. */
    public static final String MATCH_DIR = "match";
    /** Read cap per match file (descriptor, sidecar, the old sidecar kept for a restore): 262144 B,
     *  {@code ServeLauncher.MAX_CONFIG_BYTES} (a file past it is refused, never truncated). */
    static final long MAX_MATCH_FILE_BYTES = ServeLauncher.MAX_CONFIG_BYTES;
    /** A typed value longer than 16 chars is refused before any parse (the longest legal value,
     *  {@code nebula_blue}, is 11). */
    public static final int MAX_VALUE_CHARS = 16;
    /** At most 256 {@code match/*.txt} names are offered (a listing bound, never a read). */
    static final int MAX_OFFERED = 256;
    /** {@link #writeSidecar}'s temp siblings: {@code <file>.apply-tmp} (not {@code *.txt}, so never offered). */
    static final String TEMP_SUFFIX = ".apply-tmp";

    // The texts.
    // No confirm text: an Apply writes on the press.
    public static final String CONFIRM_TITLE = "Match settings";
    /** The tail {@link #CHANGED} and {@link #REPAIRED} share: the change takes effect at the next Start (an Apply
     *  is refused while a serve runs), and nothing goes to the players - the server hands the match to every join
     *  window at its next Join or Refresh. */
    private static final String FETCH_TAIL = " It applies from the next round, when you press Start; nothing is sent"
            + " now - every player's next Join or Refresh fetches the new match.";
    /** The changed-value text in the fill site's tokens: {@code <what>} = the setting, {@code <value>} = the value
     *  as written (a CR as a percent, a map size as its half text); one plain sentence, then the tail.
     * */
    public static final String CHANGED = "<what> set to <value>." + FETCH_TAIL;
    public static final String UNCHANGED = "<shape>: <what> is already <value>. Nothing changed;"
            + " players' current match still works.";
    /** The torn-pair repair text; it ends as {@link #CHANGED} does ({@link #FETCH_TAIL}).
     * */
    public static final String REPAIRED = "<shape>: its two match files did not belong together;"
            + " now they do." + FETCH_TAIL;
    public static final String R1_LIVE = "Stop the server first (Kill).";
    public static final String R2_NO_STATION = "<shape> has no station.";
    public static final String R3_MAP_SIZE = "Map size must be a whole number from 2000 to 60000"
            + " (got '<x>').";
    /** R4 / R5's remedy = delete the pair and start again (the first start copies a shipped pair back when BOTH halves
     *  are absent; a host-made shape is made again) - never "re-extract": the zip carries no match files. The
     *  {@code .password} files sit beside the pair and are not named for deletion. */
    public static final String R4_SIDECAR = "The file match\\<name>.fingerprint is missing or"
            + " damaged. Delete match\\<name> and its .fingerprint and start again (a shipped shape is copied back;"
            + " a shape you made: make it again with New match shape). Your .password files are kept.";
    public static final String R5_VERSION = "This match file was made for another server version."
            + " Delete it and its .fingerprint and start again (a shipped shape is copied back; a shape you made:"
            + " make it again with New match shape).";
    public static final String R6_NOT_OFFERED = "Pick a match shape from the list.";
    public static final String R7_WRITE_KEPT = "Writing the match files failed (<class>); the old"
            + " files were kept. Press Apply again before you Start.";
    public static final String R7B_WRITE_TORN = "Writing the match files failed (<class>) and could"
            + " not be undone: press Apply again - Start refuses until it works.";
    public static final String R8_DEPLOY_CR = "Deploy CR must be a whole number from 1 to 100"
            + " (got '<x>').";
    /** The Players refusal (the host window's / web console's Players field, 1..MAX_PLAYERS). */
    public static final String R9_PLAYERS = "Players must be a whole number from 1 to "
            + MatchDescriptors.MAX_PLAYERS + " (got '<x>').";
    /** A Players count the grammar admits but the picked shape's own range does not: the New-shape dialog's
     *  sentence ({@link MatchShapes#T6_PLAYERS}) so both paths refuse one count with one text; {@code <shape>} = the
     *  file's shape spec, filled as that dialog fills it, {@code <min>} / {@code <max>} = its range.
     * */
    public static final String R10_PLAYERS_RANGE = MatchShapes.T6_PLAYERS + " (got '<x>').";
    /** The station / backdrop word refusals (a combo-picked word only goes wrong in a hand-made web request); worded
     *  from the field tips. */
    public static final String STATION_WORD = "Station type must be lowtech, midline or hightech"
            + " (got '<x>').";
    public static final String BACKDROP_WORD = "Battle background must be none, open, nebula,"
            + " nebula_blue or hyper (got '<x>').";
    /** The PvP mode texts. {@link #R10_PVP_PLAYERS} is the
     *  PvP twin of {@link #R10_PLAYERS_RANGE} (token {@code players}); {@link #R11_PVP_SHAPE} refuses ON for any
     *  shape but the fortress (token {@code pvp_shape}); {@link #PVP_CHANGED} is the Start receipt's sentence
     *  after a switch, and {@link #PVP_STATION_BACK} follows it when OFF brought the stock station back, so the
     *  host is told which station the match now fields; the three Start refusals name the inputs the serve
     *  would refuse to boot on. */
    public static final String PVP_WORD = "PvP mode must be on or off";
    public static final String R10_PVP_PLAYERS = "PvP mode needs at least 2 players: set Players to 2 or more,"
            + " then press Start.";
    public static final String R11_PVP_SHAPE = "PvP mode works with the fortress match only: pick the fortress"
            + " match, then press Start.";
    public static final String PVP_CHANGED = "PvP mode changed: every player's next Join or Refresh fetches"
            + " the match.";
    public static final String PVP_STATION_BACK = "Station is back to <family>.";
    public static final String PVP_START_LATCH = "PvP mode has no win, so a cleared wave cannot end its round:"
            + " delete the server.waveClearRoundEnd=true row from the server config, or set PvP mode to Off.";
    public static final String PVP_START_STATION = "This PvP match file has a station, and PvP fields none:"
            + " set PvP mode to Off, then press Start.";
    public static final String PVP_START_OWNER = "PvP mode changes from a server package's console only: set"
            + " it back to what the picked match file has, then press Start.";

    /** Every text template (core + the launcher's), for the text-law row.
     * */
    public static List<String> texts() {
        return List.of(CONFIRM_TITLE, CHANGED, UNCHANGED, REPAIRED, R1_LIVE,
                R2_NO_STATION, R3_MAP_SIZE, R4_SIDECAR, R5_VERSION, R6_NOT_OFFERED, R7_WRITE_KEPT,
                R7B_WRITE_TORN, R8_DEPLOY_CR, R9_PLAYERS, R10_PLAYERS_RANGE, STATION_WORD, BACKDROP_WORD,
                PVP_WORD, R10_PVP_PLAYERS, R11_PVP_SHAPE, PVP_CHANGED, PVP_STATION_BACK, PVP_START_LATCH,
                PVP_START_STATION, PVP_START_OWNER, ServeLauncher.SIDECAR_PAIR_SENTENCE, ServeLauncher.RIDER_MAP_HALF,
                ServeLauncher.RIDER_DEPLOY_CR);
    }

    /**
     * The offered set: the regular (not symlinked) {@code *.txt} files
     * directly under {@code <roots.pkg()>/match/}, sorted, absolute + normalized, at most
     * {@link #MAX_OFFERED}; names holding {@code -tokens} are never offered (the mint's tokens
     * sidecar). A NAME listing - no file is opened (the R6 check precedes every read). The
     * Start selector's {@code ServerOpsCore.descriptorOptions} is a codec-gated subset of this
     * set for a package (its row space resolves {@code match/...} against the config's folder,
     * the package root); it is not called here so this class never calls back into the core.
     *
     */
    public static List<Path> offeredDescriptors(OpsRoots roots) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(roots.pkg().resolve(MATCH_DIR))) {
            for (Path p : files.sorted().toList()) {
                String name = p.getFileName().toString();
                if (out.size() < MAX_OFFERED && name.endsWith(".txt") && !name.contains("-tokens")
                        && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    out.add(p.toAbsolutePath().normalize());
                }
            }
        } catch (IOException | RuntimeException noFolder) {
            System.err.println("[ops] match settings: the package match folder cannot be listed ("
                    + noFolder.getClass().getSimpleName() + ") - nothing is offered");
        }
        return List.copyOf(out);
    }

    /**
     * The shells' prefill for one shape: ONE bounded read (≤ 262144 B) + the
     * serve's codec. Never throws: a null path, an unreadable or undecodable file answers a
     * {@link MatchSettings} with a {@code problem} sentence (+ one stderr line naming the file
     * and the exception class only).
     */
    public static MatchSettings matchSettingsOf(Path descriptor) {
        if (descriptor == null) {
            return new MatchSettings("", "", "", "", "no match shape is picked");
        }
        try {
            return settingsOf(MatchDescriptorCodec.decode(
                    readCapped(descriptor, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException damaged) {
            String why = damaged.getClass().getSimpleName();
            System.err.println("[ops] match settings prefill: " + descriptor.getFileName()
                    + " cannot be read (" + why + ")");
            return new MatchSettings("", "", "", "", "the match file " + descriptor.getFileName()
                    + " cannot be read (" + why + ")");
        }
    }

    /**
     * The package match-setting Apply: re-make the ONE offered
     * shape {@code offeredDescriptorOrNull} with {@code op} = {@code value} and re-key its
     * sidecar. Order (each refusal THROWN as {@link ApplyRefused}, nothing written):
     * {@code serverLive} (the caller's ONE gate, {@code ServerOpsLaunch.serveBusy}) = R1; a path
     * not in {@link #offeredDescriptors} (relative = against the config's folder, the serve's
     * rule) = R6, before any file read; a value over 16 chars or off its grammar (half: whole su
     * 2000..60000; station: lowtech|midline|hightech; backdrop: a {@code map.terrain} word; CR: a
     * whole % 1..100; players: a whole count 1..{@link MatchDescriptors#MAX_PLAYERS}) = R3 /
     * station_word / backdrop_word / R8 / R9; the descriptor (≤ 262144 B) not
     * decodable, non-square or a shape this build cannot mint = R5; the sidecar
     * {@code <file>.fingerprint} (≤ 262144 B, not a symlink) missing / without a
     * {@code fingerprint=} {@code match=} component = R4, its {@code hs=} not this server's
     * handshake version = R5; a STATION op on a stationless shape = R2; a PLAYERS count outside
     * the file's shape range ({@link MatchDescriptors#playersRange}) = {@code players} R10 (a shape
     * with no players rule = R5).
     *
     * <p>Then: the value already baked = UNCHANGED (nothing written) unless the pair is TORN
     * (sidecar {@code match=} ≠ the descriptor's match.id) - then the sidecar alone is re-keyed to
     * the current id = REPAIRED. Otherwise re-mint (new match.id + seat tokens; every other value
     * threads through), {@link MatchDescriptors#redact} it when the input carried placeholders
     * (redacted in = redacted out: live tokens never reach {@code match/}), re-key the sidecar to
     * the new id and land the pair through {@link #writeSidecar} (write failure = R7, or R7b when
     * the old sidecar could not be restored). Writes ONLY {@code <file>}, {@code <file>.fingerprint}
     * and their two {@code .apply-tmp} siblings - never a props row, a password, a tokens file or
     * {@code serve-mods/}. No timeout, no thread, no process; at most 2 reads + 2 writes + 2 moves.
     * A PLAYERS Apply's unchanged test reads the FILE's own seat count (a file written at 40 seats
     * and asked for 40 is unchanged); a new count re-mints through the same one remint, every other
     * value threaded. Both value switches are expressions so a new {@link Op} fails the compile.
     * The PLAYERS leg
     */
    public static ApplyResult applyPackageMatchSetting(OpsRoots roots, Path offeredDescriptorOrNull,
            Op op, String value, boolean serverLive) {
        return applyPackageMatchSetting(roots, offeredDescriptorOrNull, op, value, null, serverLive);
    }

    /**
     * The PvP mode switch of ONE offered match file, run by Start when the page's PvP select differs from the
     * picked file (a Start-time fact: no Apply button, no config row). WHY one body with the other Applies: the
     * offered-set gate, the bounded reads, the sidecar checks, redaction and the one pair writer are the same
     * laws, so the switch cannot drift from them. Order (each refusal THROWN as {@link ApplyRefused}, nothing
     * written): {@code serverLive} = R1; not offered = R6; {@code value} other than on / off = {@code pvp_word}
     * ({@link #PVP_WORD}); {@code stationFamilyOrNull} not lowtech / midline / hightech (null, blank or "-" =
     * none) = {@code station_word}; the file undecodable = R5; the sidecar missing / damaged = R4, another
     * handshake version = R5. Then the mode: ON from {@link MatchDescriptors#FORTRESS_SHAPE} only (any other
     * shape = {@link #R11_PVP_SHAPE}) at 2 or more players (else {@link #R10_PVP_PLAYERS}); OFF from
     * {@link MatchDescriptors#PVP_SHAPE} only. The file already in the asked mode (OFF on any non-PvP file
     * included) = UNCHANGED with NOTHING written, not even a torn-pair re-key. A switch re-mints through
     * {@link MatchDescriptors#remintAs} at the file's own players, map half, deploy CR and backdrop: ON drops
     * the station; OFF takes the given family, else the fortress's stock station, and the note then SAYS which
     * one came back ({@link #PVP_STATION_BACK}), never silently. Redacted in = redacted out; the sidecar is
     * re-keyed to the new match.id and the pair lands through {@link #writeSidecar} - the only files written.
     *
     */
    public static ApplyResult applyPvp(OpsRoots roots, Path offeredDescriptorOrNull, String value,
            String stationFamilyOrNull, boolean serverLive) {
        return applyPackageMatchSetting(roots, offeredDescriptorOrNull, Op.PVP, value, stationFamilyOrNull,
                serverLive);
    }

    /** On | off (trimmed, any case) to true / false, else
     *  {@link ApplyRefused} {@code pvp_word} carrying {@link #PVP_WORD}; the Start parse and the switch share it. */
    public static boolean parsePvpMode(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (!"on".equals(t) && !"off".equals(t)) {
            throw new ApplyRefused("pvp_word", PVP_WORD);
        }
        return "on".equals(t);
    }

    /** true when {@code d} carries a station row - the input the
     *  serve refuses to boot on beside the PvP shape, asked by the Start's pre-write check. */
    public static boolean carriesStation(MatchDescriptor d) {
        return d != null && stationVariantIdOrNull(d) != null;
    }

    /** The one body of every package Apply and the PvP switch; {@code stationFamilyOrNull} is read by
     *  {@link Op#PVP} alone (the family an OFF switch brings back). */
    private static ApplyResult applyPackageMatchSetting(OpsRoots roots, Path offeredDescriptorOrNull,
            Op op, String value, String stationFamilyOrNull, boolean serverLive) {
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(op, "op");
        if (serverLive) {
            throw new ApplyRefused("server_live", R1_LIVE);
        }
        Path file = offeredOrNull(roots, offeredDescriptorOrNull);
        if (file == null) {
            throw new ApplyRefused("not_offered", R6_NOT_OFFERED);
        }
        String name = file.getFileName().toString();
        String typed = value == null ? "" : value;
        if (typed.length() > MAX_VALUE_CHARS) {
            throw refusedValue(op, typed);
        }
        float wantHalf = 0f;
        float wantCr = 0f;
        String wantStation = null;
        String wantWord = null;
        int wantPlayers = 0;
        boolean wantPvp = false;
        String pvpStation = null; // an OFF switch's family as a variant id; null = the fortress's stock station
        String valueText = switch (op) {
            case PVP -> {
                wantPvp = parsePvpMode(typed);
                String family = stationFamilyOrNull == null ? "" : stationFamilyOrNull.trim().toLowerCase(Locale.ROOT);
                if (!family.isEmpty() && !"-".equals(family)) {
                    try {
                        pvpStation = MatchDescriptors.stationVariantForFamily(
                                family.length() > MAX_VALUE_CHARS ? "" : family);
                    } catch (IllegalArgumentException bad) {
                        throw refusedValue(Op.STATION, stationFamilyOrNull);
                    }
                }
                yield wantPvp ? "on" : "off";
            }
            case SIZE -> {
                try {
                    wantHalf = parseMapHalf(typed);
                } catch (IllegalArgumentException bad) {
                    throw refusedValue(op, typed);
                }
                yield mapHalfText(wantHalf);
            }
            case PLAYERS -> {
                wantPlayers = parsePlayers(typed);
                yield Integer.toString(wantPlayers);
            }
            case STATION -> {
                String family = typed.trim().toLowerCase(Locale.ROOT);
                try {
                    wantStation = MatchDescriptors.stationVariantForFamily(family);
                } catch (IllegalArgumentException bad) {
                    throw refusedValue(op, typed);
                }
                yield family;
            }
            case BACKDROP -> {
                wantWord = typed.trim().toLowerCase(Locale.ROOT);
                if (!MatchDescriptor.isTerrainWord(wantWord)) {
                    throw refusedValue(op, typed);
                }
                yield wantWord;
            }
            case DEPLOY_CR -> {
                wantCr = parseDeployCrPercent(typed);
                yield Math.round(wantCr * 100) + "%";
            }
        };
        MatchDescriptor decoded;
        float half;
        try {
            decoded = MatchDescriptorCodec.decode(readCapped(file, StandardCharsets.UTF_8));
            half = op == Op.SIZE ? wantHalf : MatchDescriptors.serveMapHalf(decoded);
        } catch (IOException | RuntimeException otherVersion) {
            throw new ApplyRefused("other_version", R5_VERSION);
        }
        List<String> sidecarLines;
        String keyedId;
        String hs;
        Path sidecar = Paths.get(file + ".fingerprint");
        try {
            if (!Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("no regular sidecar file");
            }
            sidecarLines = readCapped(sidecar, StandardCharsets.ISO_8859_1);
            String composite = ContentFingerprints.parseSidecarFingerprint(sidecarLines, name);
            keyedId = ContentFingerprints.componentOrNull(composite, "match");
            hs = ContentFingerprints.componentOrNull(composite, "hs");
            if (keyedId == null || keyedId.isBlank()) {
                throw new IllegalArgumentException("no match= component");
            }
        } catch (IOException | RuntimeException damaged) {
            throw new ApplyRefused("sidecar_damaged", R4_SIDECAR.replace("<name>", name));
        }
        if (!String.valueOf(HandshakeCodec.HANDSHAKE_VERSION).equals(hs)) {
            throw new ApplyRefused("other_version", R5_VERSION);
        }
        String stationId = stationVariantIdOrNull(decoded);
        float cr = decoded.deployCr();
        String terrain = decoded.terrain();
        int players = decoded.seats().size();
        String targetShape = null; // non-null = a PvP switch re-mints into this shape
        boolean unchanged = switch (op) {
            case PVP -> {
                boolean filePvp = MatchDescriptors.isPvpShape(decoded.shape());
                if (wantPvp == filePvp) {
                    yield true;
                }
                if (wantPvp && !MatchDescriptors.FORTRESS_SHAPE.equals(decoded.shape())) {
                    throw new ApplyRefused("pvp_shape", R11_PVP_SHAPE);
                }
                if (wantPvp && players < 2) {
                    throw new ApplyRefused("players", R10_PVP_PLAYERS);
                }
                targetShape = wantPvp ? MatchDescriptors.PVP_SHAPE : MatchDescriptors.FORTRESS_SHAPE;
                stationId = wantPvp ? null : pvpStation;
                yield false;
            }
            case SIZE -> decoded.halfWidth() == wantHalf && decoded.halfHeight() == wantHalf;
            case STATION -> {
                if (stationId == null) {
                    throw new ApplyRefused("no_station", R2_NO_STATION.replace("<shape>", name));
                }
                boolean same = wantStation.equals(stationId);
                stationId = wantStation;
                yield same;
            }
            case BACKDROP -> {
                boolean same = wantWord.equals(terrain);
                terrain = wantWord;
                yield same;
            }
            case PLAYERS -> {
                requirePlayersInRange(decoded, wantPlayers, typed);
                boolean same = players == wantPlayers;
                players = wantPlayers;
                yield same;
            }
            case DEPLOY_CR -> {
                boolean same = Math.round(cr * 100) == Math.round(wantCr * 100);
                cr = wantCr;
                yield same;
            }
        };
        // a PvP switch in the asked mode writes nothing at all (Start runs it only on a flip)
        boolean torn = op != Op.PVP && !keyedId.trim().equals(decoded.matchId());
        String filled = (unchanged ? (torn ? REPAIRED : UNCHANGED) : op == Op.PVP ? PVP_CHANGED : CHANGED)
                .replace("<what>", op.what()).replace("<shape>", name).replace("<value>", valueText);
        if (unchanged) {
            if (torn) {
                writePairOrRefuse(file, null, rekeyOrRefuse(sidecarLines, decoded.matchId(), name));
            }
            return new ApplyResult(false, torn, filled, false, settingsOf(decoded));
        }
        MatchDescriptor minted;
        try {
            minted = targetShape != null
                    ? MatchDescriptors.remintAs(decoded, targetShape, players, half, cr, stationId, terrain)
                    : MatchDescriptors.remint(decoded, players, half, cr, stationId, terrain);
        } catch (RuntimeException unknownShape) {
            throw new ApplyRefused("other_version", R5_VERSION);
        }
        if (MatchPasswords.carriesRedactedTokens(decoded)) {
            minted = MatchDescriptors.redact(minted);
        }
        if (MatchDescriptors.FORTRESS_SHAPE.equals(targetShape) && stationId == null) {
            // OFF with no family picked: the stock station came back - said, never silent
            filled = filled + " " + PVP_STATION_BACK.replace("<family>", settingsOf(minted).station());
        }
        writePairOrRefuse(file, MatchDescriptorCodec.encode(minted),
                rekeyOrRefuse(sidecarLines, minted.matchId(), name));
        return new ApplyResult(true, torn, filled, true, settingsOf(minted));
    }

    /**
     * A PLAYERS count against the picked file's own shape range ({@link MatchDescriptors#playersRange}
     * of {@code decoded.shape()}). WHY a separate gate: the Players grammar is shape-free, the range
     * is not, and {@code remint} would throw on an out-of-range count into a catch that answers the
     * other-version text - so the range is asked first and refused in the Players wording. Only the
     * range LOOKUP is wrapped (a spec with no players rule = R5, the re-mint's own mapping); the
     * range refusal is thrown outside any catch, so no RuntimeException handler can swallow it.
     * {@code <shape>} is filled only after the lookup accepted the spec (a known spec, never raw file
     * text).
     */
    private static void requirePlayersInRange(MatchDescriptor decoded, int wantPlayers, String typed) {
        int[] range;
        try {
            range = MatchDescriptors.playersRange(decoded.shape());
        } catch (RuntimeException noRule) {
            throw new ApplyRefused("other_version", R5_VERSION);
        }
        if (wantPlayers < range[0] || wantPlayers > range[1]) {
            throw new ApplyRefused("players", R10_PLAYERS_RANGE.replace("<shape>", decoded.shape())
                    .replace("<min>", Integer.toString(range[0])).replace("<max>", Integer.toString(range[1]))
                    .replace("<x>", shown(typed)));
        }
    }

    /**
     * The ONE writer of a descriptor + sidecar PAIR
     * - the owner leg ({@link #reauthorDescriptors}) and the package
     * leg both land here. Writes the temp siblings {@code <sidecar>.apply-tmp} (its bytes composed by
     * THE one sidecar writer {@link ContentFingerprintMint#writeSidecar} - CRLF pinned, ISO-8859-1;
     * a re-keyed shipped file round-trips byte for byte on any host;
     * the join moved there, the pair order + rollback below stay here) and
     * {@code <descriptor>.apply-tmp} (UTF-8), then ATOMIC_MOVEs the SIDECAR first, then the
     * descriptor: while split, the sidecar names the NEW id nobody holds, so the door admits no one
     * (descriptor-first would admit old-text holders to the new map). A failure before the first
     * move changes nothing; a failed descriptor move writes the old sidecar bytes back from memory
     * (deletes the new one when there was none). {@code descriptorTextOrNull} null = re-key the
     * sidecar alone (a torn-pair repair). Temps are deleted on every failure path. Throws
     * {@link PairWriteFailed} ({@code undone} false = the old sidecar could not be restored).
     *
     */
    static void writeSidecar(Path descriptor, String descriptorTextOrNull, List<String> sidecarLines)
            throws PairWriteFailed {
        writeSidecar(descriptor, descriptorTextOrNull, sidecarLines, ATOMIC);
    }

    /** {@link #writeSidecar} with its move step injectable - the rollback rows hand a mover that
     *  fails the second (descriptor) move; production passes {@link #ATOMIC}. */
    static void writeSidecar(Path descriptor, String descriptorTextOrNull, List<String> sidecarLines,
            AtomicMover mover) throws PairWriteFailed {
        Path sidecar = Paths.get(descriptor + ".fingerprint");
        Path sidecarTemp = Paths.get(sidecar + TEMP_SUFFIX);
        Path descriptorTemp = Paths.get(descriptor + TEMP_SUFFIX);
        byte[] oldSidecar;
        try {
            oldSidecar = Files.exists(sidecar) ? readCappedBytes(sidecar) : null;
            ContentFingerprintMint.writeSidecar(sidecarTemp, sidecarLines);
            if (descriptorTextOrNull != null) {
                Files.writeString(descriptorTemp, descriptorTextOrNull, StandardCharsets.UTF_8);
            }
            mover.move(sidecarTemp, sidecar);
        } catch (IOException | RuntimeException first) {
            deleteTemps(sidecarTemp, descriptorTemp);
            throw new PairWriteFailed(first, true);
        }
        if (descriptorTextOrNull == null) {
            return;
        }
        try {
            mover.move(descriptorTemp, descriptor);
        } catch (IOException | RuntimeException second) {
            boolean undone = true;
            try {
                if (oldSidecar == null) {
                    Files.deleteIfExists(sidecar);
                } else {
                    Files.write(sidecar, oldSidecar);
                }
            } catch (IOException | RuntimeException restoreFailed) {
                second.addSuppressed(restoreFailed);
                undone = false;
            }
            deleteTemps(sidecarTemp, descriptorTemp);
            throw new PairWriteFailed(second, undone);
        }
    }

    /** The move step of {@link #writeSidecar}. */
    interface AtomicMover {
        void move(Path from, Path to) throws IOException;
    }

    /** Same-folder temp siblings, so an atomic rename is always available (replaces the target). */
    static final AtomicMover ATOMIC = (from, to) -> Files.move(from, to,
            StandardCopyOption.ATOMIC_MOVE);

    /** A {@link #writeSidecar} failure: {@code undone} true = the old pair stands (R7), false =
     *  the sidecar names the new id beside the old descriptor (R7b; the launcher refuses it).
     * */
    static final class PairWriteFailed extends IOException {
        private static final long serialVersionUID = 1L;
        final boolean undone;

        PairWriteFailed(Throwable cause, boolean undone) {
            super((undone ? "writing the match-file pair failed; the old pair was kept: "
                    : "writing the match-file pair failed and the old sidecar could not be"
                    + " restored: ") + cause, cause);
            this.undone = undone;
        }
    }

    private static void deleteTemps(Path... temps) {
        for (Path temp : temps) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException | RuntimeException leak) {
                System.err.println("[ops] match settings: a temp file could not be removed ("
                        + leak.getClass().getSimpleName() + ")");
            }
        }
    }

    private static void writePairOrRefuse(Path file, String textOrNull, List<String> sidecarLines) {
        try {
            writeSidecar(file, textOrNull, sidecarLines);
        } catch (PairWriteFailed failed) {
            String cause = failed.getCause().getClass().getSimpleName();
            throw failed.undone
                    ? new ApplyRefused("write_failed", R7_WRITE_KEPT.replace("<class>", cause))
                    : new ApplyRefused("write_torn", R7B_WRITE_TORN.replace("<class>", cause));
        }
    }

    private static List<String> rekeyOrRefuse(List<String> sidecarLines, String matchId, String name) {
        try {
            return ContentFingerprintMint.rekeySidecarLines(sidecarLines, matchId);
        } catch (IllegalArgumentException overCap) {
            throw new ApplyRefused("sidecar_damaged", R4_SIDECAR.replace("<name>", name));
        }
    }

    /** {@code offered} resolved like the serve resolves a row (relative = the config's folder),
     *  then matched against {@link #offeredDescriptors}; null = not offered. */
    private static Path offeredOrNull(OpsRoots roots, Path offered) {
        if (offered == null) {
            return null;
        }
        Path base = roots.config().toAbsolutePath().getParent();
        Path abs = (offered.isAbsolute() || base == null ? offered : base.resolve(offered))
                .toAbsolutePath().normalize();
        for (Path p : offeredDescriptors(roots)) {
            if (p.equals(abs)) {
                return p;
            }
        }
        return null;
    }

    private static MatchSettings settingsOf(MatchDescriptor d) {
        String station = stationVariantIdOrNull(d);
        return new MatchSettings(mapHalfText(MatchDescriptors.serveMapHalf(d)),
                station == null ? "-" : MatchDescriptors.familyForStationVariant(station),
                d.terrain(), Integer.toString(Math.round(d.deployCr() * 100)), null);
    }

    private static ApplyRefused refusedValue(Op op, String typed) {
        return switch (op) {
            case SIZE -> new ApplyRefused("map_size", R3_MAP_SIZE.replace("<x>", shown(typed)));
            case STATION -> new ApplyRefused("station_word",
                    STATION_WORD.replace("<x>", shown(typed)));
            case BACKDROP -> new ApplyRefused("backdrop_word",
                    BACKDROP_WORD.replace("<x>", shown(typed)));
            case DEPLOY_CR -> new ApplyRefused("deploy_cr", R8_DEPLOY_CR.replace("<x>", shown(typed)));
            case PLAYERS -> new ApplyRefused("players", R9_PLAYERS.replace("<x>", shown(typed)));
            case PVP -> new ApplyRefused("pvp_word", PVP_WORD);
        };
    }

    /** A typed value as a text shows it: trimmed, ≤ 16 chars (+ "..."), control chars as '?'. */
    static String shown(String value) {
        String v = value == null ? "" : value.trim();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length() && i < MAX_VALUE_CHARS; i++) {
            char c = v.charAt(i);
            b.append(c < 0x20 || c == 0x7F ? '?' : c);
        }
        return v.length() > MAX_VALUE_CHARS ? b + "..." : b.toString();
    }

    private static byte[] readCappedBytes(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes((int) MAX_MATCH_FILE_BYTES + 1);
            if (bytes.length > MAX_MATCH_FILE_BYTES) {
                throw new IOException(file.getFileName() + " is over the " + MAX_MATCH_FILE_BYTES
                        + " B read cap");
            }
            return bytes;
        }
    }

    private static List<String> readCapped(Path file, Charset charset) throws IOException {
        return new String(readCappedBytes(file), charset).lines().toList();
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void deleteTree(Path root) throws IOException { // package-private: ServerOpsCoreSelftest
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
