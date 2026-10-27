package cc.starsector.server.match;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decoder for the fielded-content officer table {@code data/cc/officers.txt} (
 * the officer-skills wiring): a snapshot of a player fleet's per-ship officer rows
 * (the fleet file's {@code shipList}), variant ids remapped to the fielded {@code *_cc_*}
 * twins. ONE parser for BOTH ends — the server attach ({@code OfficerAttach}) and the client
 * parity attach ({@code OfficerLoadout}) decode the same mod-data bytes through this class
 * (the {@link MatchDescriptorCodec} shared-source pattern), so the stats the server simulates
 * and the stats the client predicts with cannot drift through a second parser.
 *
 * <p>Grammar, one officer per line (blank lines + {@code #} comments skipped):
 * <pre>officer|&lt;variantId&gt;|&lt;shipName&gt;|&lt;personality&gt;|&lt;portraitPath&gt;|&lt;level&gt;|&lt;skillId&gt;=&lt;level&gt;[,&lt;skillId&gt;=&lt;level&gt;...][|&lt;aiCoreId&gt;]</pre>
 * Skill levels are {@code 1} (base) or {@code 2} (elite) — the engine's
 * {@code setSkillLevel(id, 2f)} elite convention. Ship name splits on the FIRST
 * space into FullName first/last ("Myn Grendel 1" → "Myn" + "Grendel 1").
 * The OPTIONAL eighth field {@code aiCoreId} marks the captain an AI core
 * ({@code PersonAPI.setAICoreId} — the vanilla {@code AICoreOfficerPluginImpl} recipe's one
 * non-stat line; {@code isAICore()} is read by the engine's reserve fleet-strength and by
 * commander-skill predicates such as Cybernetic Augmentation, so BOTH ends must carry it).
 * Decoded from the CLOSED set {@link #AI_CORE_IDS} (the four vanilla core commodity ids),
 * fail-closed; absent / empty = a human captain (every pre-1393 row and line, byte-identical).
 * A non-empty field is the RAPP v4 line shape ({@code RosterAppendControlMessage}).
 *
 * <p>Fail-CLOSED: any malformed row throws {@link IllegalArgumentException} — a bad officer
 * table must never half-apply (one end attaching what the other end skipped is a stats
 * desync by construction). Classloader-B safe: pure string parse, no I/O, no reflection.
 */
public final class OfficerRosterCodec {

    /** One fielded officer: immutable, skills insertion-ordered (attach order = file order). */
    public static final class OfficerSpec {
        public final String variantId;
        public final String firstName;
        public final String lastName;
        public final String personality;
        public final String portraitPath;
        public final int level;
        public final Map<String, Float> skills;
        /** The AI-core commodity id ({@link #AI_CORE_IDS}) or {@code ""} = human. */
        public final String aiCoreId;

        OfficerSpec(String variantId, String firstName, String lastName, String personality,
                String portraitPath, int level, Map<String, Float> skills) {
            this(variantId, firstName, lastName, personality, portraitPath, level, skills, "");
        }

        /** The AI-core-carrying shape ({@code aiCoreId} from {@link #AI_CORE_IDS} or ""). */
        OfficerSpec(String variantId, String firstName, String lastName, String personality,
                String portraitPath, int level, Map<String, Float> skills, String aiCoreId) {
            this.variantId = variantId;
            this.firstName = firstName;
            this.lastName = lastName;
            this.personality = personality;
            this.portraitPath = portraitPath;
            this.level = level;
            this.skills = Collections.unmodifiableMap(skills);
            this.aiCoreId = aiCoreId == null ? "" : aiCoreId;
        }

        /** True when the captain is an AI core ({@code aiCoreId} non-empty). */
        public boolean isAICore() {
            return !aiCoreId.isEmpty();
        }

        /** The displayed name ({@code first last}, or just {@code first} when last is empty) —
         *  the ONE name both ends agree on; the first/last split is the grammar's first-space
         *  law and may differ from the minting site's split (name-only). */
        public String fullName() {
            return lastName.isEmpty() ? firstName : firstName + " " + lastName;
        }

        /**
         * Re-key to the fielded variant (hegemony w4.5 {@code falcon_Attack}): the same captain keyed to
         * the variant the hull is ACTUALLY fielded under. The officer table and a row-carried
         * spec are keyed by the authored GOAL id; an inflated wave row fields a server-minted
         * {@code cc_wave_*} id, and the RAPP v3 row's own-variant law ({@link #decodeOne}) then
         * threw {@code IllegalArgumentException} inside {@code serveFrame} — a serve-killing
         * fatal on the first officered inflated row. Re-key before the attach so the wire and
         * the engine member agree; a same-id call returns {@code this}.
         */
        public OfficerSpec withVariantId(String fieldedVariantId) {
            Objects.requireNonNull(fieldedVariantId, "fieldedVariantId");
            if (fieldedVariantId.equals(variantId)) {
                return this;
            }
            return new OfficerSpec(fieldedVariantId, firstName, lastName, personality,
                    portraitPath, level, skills, aiCoreId);
        }
    }

    /** The closed {@code aiCoreId} vocabulary — vanilla's four AI-core commodity ids
     *  (each one an {@code AICoreOfficerPluginImpl.createPerson} recipe). Anything else rejects. */
    public static final List<String> AI_CORE_IDS =
            List.of("alpha_core", "beta_core", "gamma_core", "omega_core");

    /** True when {@code id} is a member of {@link #AI_CORE_IDS}. */
    public static boolean isAiCoreId(String id) {
        return id != null && AI_CORE_IDS.contains(id);
    }

    /**
     * The RAPP v3 officer line's char bound — the whole line rides ONE
     * {@code writeUTF} field inside the datagram budget; a table row longer than this
     * is a config error (the boot sweep {@code OfficerAttach.validateRowSpecs} round-trips every
     * fieldable spec through {@link #encodeLine} so it fails at BOOT, never mid-round).
     */
    public static final int MAX_LINE_CHARS = 512;
    /** Skills per wire officer line (the boss carries 10; all-skills persons never ride). */
    public static final int MAX_LINE_SKILLS = 32;

    private OfficerRosterCodec() {
    }

    /**
     * Encode one spec as ONE grammar line (the RAPP v3 {@code officerLine}
     * payload — the client decodes it through {@link #decodeOne}, so the stats the server
     * attached and the stats the client twin predicts with come from the SAME parser).
     * Printable-ASCII, no field separators inside a token, ≤ {@link #MAX_LINE_CHARS}: a spec
     * that cannot round-trip THROWS {@link IllegalArgumentException} (our own tables mint every
     * spec; a violation is a config error, surfaced by the boot sweep + the contract suite).
     */
    public static String encodeLine(OfficerSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("officer spec must be non-null");
        }
        if (spec.skills.isEmpty() || spec.skills.size() > MAX_LINE_SKILLS) {
            throw new IllegalArgumentException("officer line (variantId " + spec.variantId.length()
                    + " chars): skills count outside 1.." + MAX_LINE_SKILLS + ": " + spec.skills.size());
        }
        StringBuilder sb = new StringBuilder(160);
        sb.append("officer|").append(lineToken(spec.variantId, "variantId"))
                // A blank name is legal (createPerson() = FullName("","")).
                .append('|').append(spec.fullName().trim().isEmpty() ? ""
                        : lineToken(spec.fullName(), "shipName"))
                .append('|').append(lineToken(spec.personality, "personality"))
                .append('|').append(lineToken(spec.portraitPath, "portraitPath"))
                .append('|').append(spec.level).append('|');
        boolean first = true;
        for (Map.Entry<String, Float> e : spec.skills.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            String id = lineToken(e.getKey(), "skillId");
            if (id.indexOf(',') >= 0 || id.indexOf('=') >= 0) {
                throw new IllegalArgumentException("officer line skill id '" + id
                        + "' carries a ',' or '='");
            }
            float lv = e.getValue() == null ? 0f : e.getValue().floatValue();
            if (lv != 1.0f && lv != 2.0f) {
                throw new IllegalArgumentException("officer line skill '" + id
                        + "' level must be 1 or 2, got " + lv);
            }
            sb.append(id).append('=').append(lv == 2.0f ? "2" : "1");
        }
        if (!spec.aiCoreId.isEmpty()) {
            // The optional eighth field — emitted ONLY for an AI core, so every human
            // line stays byte-identical to its pre-1393 shape.
            if (!isAiCoreId(spec.aiCoreId)) {
                throw new IllegalArgumentException("officer line aiCoreId ("
                        + spec.aiCoreId.length() + " chars) outside " + AI_CORE_IDS);
            }
            sb.append('|').append(spec.aiCoreId);
        }
        String line = sb.toString();
        if (line.length() > MAX_LINE_CHARS) {
            throw new IllegalArgumentException("officer line (variantId " + spec.variantId.length()
                    + " chars) is " + line.length() + " chars, cap " + MAX_LINE_CHARS);
        }
        return line;
    }

    /**
     * Decode EXACTLY ONE grammar line (the RAPP v3 wire payload). Rejects
     * (IllegalArgumentException — the frame readers translate to IOException, fail-closed):
     * empty/blank, over-long, any char outside printable ASCII 0x20..0x7E (kills newlines,
     * so a second line or a comment cannot ride), more than one decoded row, a skills count
     * over the cap, and (when {@code expectVariantId} is non-null) a variant id that is not
     * the row's own — the officer on a RAPP row is that hull's captain, never another's.
     */
    public static OfficerSpec decodeOne(String line, String expectVariantId) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("officer line must be non-blank");
        }
        if (line.length() > MAX_LINE_CHARS) {
            throw new IllegalArgumentException("officer line is " + line.length()
                    + " chars, cap " + MAX_LINE_CHARS);
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new IllegalArgumentException("officer line char " + i
                        + " outside printable ASCII: 0x" + Integer.toHexString(c));
            }
        }
        List<OfficerSpec> decoded = decode(line);
        if (decoded.size() != 1) {
            throw new IllegalArgumentException("officer line decoded to " + decoded.size()
                    + " rows, expected exactly 1");
        }
        OfficerSpec spec = decoded.get(0);
        if (spec.skills.size() > MAX_LINE_SKILLS) {
            throw new IllegalArgumentException("officer line carries " + spec.skills.size()
                    + " skills, cap " + MAX_LINE_SKILLS);
        }
        if (expectVariantId != null && !expectVariantId.equals(spec.variantId)) {
            throw new IllegalArgumentException("officer line variant (" + spec.variantId.length()
                    + " chars) is not the row's (" + expectVariantId.length() + " chars)");
        }
        return spec;
    }

    /**
     * The ONE public mint of an officer
     * LINE from an ENGINE-read captain. The growth capture ({@code bridge.EngineBornGrowthSeam
     * .observe}, where the spawned Ship is in hand) reads {@code getCaptain()} — the ShardSpawner
     * WING captain is a fresh {@code createPerson()}: blank name, reckless, the settings default
     * portrait, L1, PD/GI/IM elite — and hands the raw
     * fields here, so the bridge never formats grammar ({@link OfficerSpec}'s ctor stays
     * package-private) and the line is built by the same {@link #encodeLine} both ends decode.
     *
     * <p>TOTAL, fail-CLOSED: returns {@code ""} (= no captain; the CALLER counts the cause) and
     * never throws — the line rides a {@code GrowthEvent} whose compact ctor re-runs
     * {@link #decodeOne}, and a throw there becomes a fatal drain IOException. Bounds: level
     * 1..10; 1..{@link #MAX_LINE_SKILLS} skills, each a line token without ',' / '=' at level
     * exactly 1 or 2; {@code aiCoreId} in {@link #AI_CORE_IDS} or empty; personality a non-blank
     * line token; portrait passing {@link #isWireSafePortraitPath} (a line the client would refuse
     * is worse than none). A NAME failing the token bound — or one that alone pushes the line past
     * {@link #MAX_LINE_CHARS} — degrades to a BLANK name, never fails the line (the stats still
     * ride). The result is asserted to round-trip: {@code decodeOne(line, variantId)} must return
     * the same level, personality, portrait, core and skill set, else {@code ""}.
     *
     */
    public static String encodeEngineCaptain(String variantId, String firstName, String lastName,
            String personality, String portraitPath, int level, Map<String, Float> skillLevels,
            String aiCoreId) {
        if (level < 1 || level > 10 || skillLevels == null || skillLevels.isEmpty()
                || skillLevels.size() > MAX_LINE_SKILLS) {
            return "";
        }
        String core = aiCoreId == null ? "" : aiCoreId;
        if ((!core.isEmpty() && !isAiCoreId(core)) || !isLineTokenSafe(variantId)
                || !isLineTokenSafe(personality) || !isWireSafePortraitPath(portraitPath)) {
            return "";
        }
        Map<String, Float> skills = new LinkedHashMap<>();
        for (Map.Entry<String, Float> e : skillLevels.entrySet()) {
            String id = e.getKey();
            Float lv = e.getValue();
            if (!isLineTokenSafe(id) || id.indexOf(',') >= 0 || id.indexOf('=') >= 0
                    || lv == null || (lv.floatValue() != 1.0f && lv.floatValue() != 2.0f)
                    || skills.put(id.trim(), lv.floatValue()) != null) {
                return "";
            }
        }
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        if (!isNameCharsSafe(first) || !isNameCharsSafe(last)) {
            first = "";
            last = "";
        }
        String line = roundTripOrEmpty(new OfficerSpec(variantId.trim(), first, last,
                personality.trim(), portraitPath, level, skills, core), variantId);
        if (line.isEmpty() && !(first.isEmpty() && last.isEmpty())) {
            line = roundTripOrEmpty(new OfficerSpec(variantId.trim(), "", "",
                    personality.trim(), portraitPath, level, skills, core), variantId);
        }
        return line;
    }

    /**
     * The client's portrait bound, mirrored so the SERVER never mints a line the
     * client's {@code OfficerWireBounds.isSafePortraitPath} (client-mod) would
     * substitute — same prefix / suffix / 128-char cap / stem alphabet / traversal rejects. Now
     * the client DELEGATES to this one (the codec ships in the kit jar): this is
     * the ONE copy of the check; the client's javadoc documents the bound values.
     *
     */
    public static boolean isWireSafePortraitPath(String path) {
        final String prefix = "graphics/portraits/";
        final String suffix = ".png";
        if (path == null || path.length() > 128 || !path.startsWith(prefix)
                || !path.endsWith(suffix)) {
            return false;
        }
        String stem = path.substring(prefix.length(), path.length() - suffix.length());
        if (stem.isEmpty() || stem.contains("..") || stem.contains("//")
                || stem.startsWith("/") || stem.endsWith("/")) {
            return false;
        }
        for (int i = 0; i < stem.length(); i++) {
            char c = stem.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '/';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** {@link #lineToken}'s law as a predicate (non-blank, printable ASCII, no '|'). */
    private static boolean isLineTokenSafe(String raw) {
        return raw != null && !raw.trim().isEmpty() && isNameCharsSafe(raw);
    }

    /** Printable ASCII without '|' ({@link #lineToken}'s char law; empty passes). */
    private static boolean isNameCharsSafe(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c > 0x7E || c == '|') {
                return false;
            }
        }
        return true;
    }

    /** Encode + decode {@code spec}; the line iff every stat field survives, else {@code ""}. */
    private static String roundTripOrEmpty(OfficerSpec spec, String expectVariantId) {
        try {
            String line = encodeLine(spec);
            OfficerSpec back = decodeOne(line, expectVariantId);
            boolean same = back.level == spec.level && back.skills.equals(spec.skills)
                    && back.personality.equals(spec.personality)
                    && back.portraitPath.equals(spec.portraitPath)
                    && back.aiCoreId.equals(spec.aiCoreId);
            return same ? line : "";
        } catch (RuntimeException outOfBounds) {
            return ""; // counted by the caller's cause enum (the factory is total, never throws)
        }
    }

    private static String lineToken(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("officer line: empty " + field);
        }
        String v = raw.trim();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c > 0x7E || c == '|') {
                throw new IllegalArgumentException("officer line " + field
                        + " carries a non-printable-ASCII or '|' char at " + i + " of " + v.length());
            }
        }
        return v;
    }

    /** Decode the officer table text; empty input decodes to an empty list (no officers). */
    public static List<OfficerSpec> decode(String text) {
        List<OfficerSpec> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        int lineNo = 0;
        for (String rawLine : text.split("\r?\n", -1)) {
            lineNo++;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\\|", -1);
            if ((f.length != 7 && f.length != 8) || !"officer".equals(f[0])) {
                throw new IllegalArgumentException("officers.txt line " + lineNo
                        + ": expected 7 (or 8 with an aiCoreId) '|' fields starting 'officer',"
                        + " got " + f.length + " ('" + line + "')");
            }
            // The optional eighth field decodes from the CLOSED AI-core set only.
            String aiCoreId = "";
            if (f.length == 8) {
                aiCoreId = requireToken(f[7], lineNo, "aiCoreId");
                if (!isAiCoreId(aiCoreId)) {
                    throw new IllegalArgumentException("officers.txt line " + lineNo
                            + ": aiCoreId '" + aiCoreId + "' is not one of " + AI_CORE_IDS);
                }
            }
            String variantId = requireToken(f[1], lineNo, "variantId");
            String shipName = f[2].trim(); // Blank legal, the engine's nameless captain
            String personality = requireToken(f[3], lineNo, "personality");
            String portraitPath = requireToken(f[4], lineNo, "portraitPath");
            int level;
            try {
                level = Integer.parseInt(f[5].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("officers.txt line " + lineNo
                        + ": level must be an int, got '" + f[5] + "'");
            }
            if (level < 1 || level > 10) {
                throw new IllegalArgumentException("officers.txt line " + lineNo
                        + ": level out of range 1..10: " + level);
            }
            Map<String, Float> skills = new LinkedHashMap<>();
            for (String pair : requireToken(f[6], lineNo, "skills").split(",", -1)) {
                String[] kv = pair.trim().split("=", -1);
                if (kv.length != 2) {
                    throw new IllegalArgumentException("officers.txt line " + lineNo
                            + ": skill entry must be id=level, got '" + pair + "'");
                }
                String skillId = requireToken(kv[0], lineNo, "skillId");
                float skillLevel;
                try {
                    skillLevel = Float.parseFloat(kv[1].trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("officers.txt line " + lineNo
                            + ": skill level must be a number, got '" + kv[1] + "'");
                }
                if (skillLevel != 1.0f && skillLevel != 2.0f) {
                    throw new IllegalArgumentException("officers.txt line " + lineNo
                            + ": skill level must be 1 (base) or 2 (elite), got " + kv[1]);
                }
                if (skills.put(skillId, skillLevel) != null) {
                    throw new IllegalArgumentException("officers.txt line " + lineNo
                            + ": duplicate skill id '" + skillId + "'");
                }
            }
            for (OfficerSpec prior : out) {
                if (prior.variantId.equals(variantId)) {
                    throw new IllegalArgumentException("officers.txt line " + lineNo
                            + ": duplicate variant row '" + variantId + "'");
                }
            }
            int firstSpace = shipName.indexOf(' ');
            String firstName = firstSpace < 0 ? shipName : shipName.substring(0, firstSpace);
            String lastName = firstSpace < 0 ? "" : shipName.substring(firstSpace + 1).trim();
            out.add(new OfficerSpec(variantId, firstName, lastName, personality, portraitPath,
                    level, skills, aiCoreId));
        }
        return out;
    }

    private static String requireToken(String raw, int lineNo, String field) {
        String v = raw.trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException(
                    "officers.txt line " + lineNo + ": empty " + field);
        }
        return v;
    }
}
