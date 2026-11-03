package cc.starsector.client.luna;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;

import cc.starsector.client.MatchMissionBuilder;
import cc.starsector.client.PilotWatchLog;
import cc.starsector.server.match.MatchDocumentLaws;
import cc.starsector.server.runtime.LunaSheetCodec;
import cc.starsector.server.runtime.LunaSyncExclusions;

/**
 * The kit half of the served LunaLib settings: the server's effective LunaSettings values are put into the joiner's
 * own LunaLib settings map for the length of ONE served match and put back afterwards. Nothing is ever saved to a
 * file on the joiner and nothing rides the wire here: the sheet arrived before the game booted.
 *
 * <ol>
 * <li><b>Application load</b> ({@link #loadSidecarAtApplicationLoad}): the join window staged the server's sheet as
 *     {@value #SIDECAR_NAME} beside the match file; it is read ONCE, bounded, through the same {@code FileChannel}
 *     seam the match file uses ({@code java.nio.file.Files} is denied to mod classes), its first line
 *     {@code luna.match=<id>} binds it to that launch, and the rest is decoded by the shared {@link LunaSheetCodec}.
 *     LunaLib is then linked ONCE through method handles (the kit compiles without LunaLib, and reflection is denied
 *     to mod classes): an {@code isModEnabled("lunalib")} guard, {@code Class.forName(name, false, scriptLoader)},
 *     {@code MethodHandles.publicLookup()}. Any link failure reads {@code lunalib off}, never a throw.</li>
 * <li><b>Combat init</b> ({@link #applyAtCombatInit}): the predict plugin's {@code init} runs before every other
 *     combat plugin's {@code init} and before any ship is deployed, so every reader sees the served value from its
 *     first read. Any snapshot an earlier match left unclaimed is restored FIRST; the sheet's match id must equal the
 *     configured match's; every row is RE-VALIDATED against this game's own LunaLib declarations (a modified server
 *     is in the threat model, so the server's own filtering is never trusted): its type, then, row by row, a number's
 *     declared range, a Radio's declared options and a Color's shape ({@link #validate}); the player's value of
 *     every key is snapshotted before its one {@code put}; each touched mod gets ONE {@code reportSettingsChanged}.
 *     At most {@link LunaSheetCodec#ROWS_MAX} in-memory puts, nothing waited on.</li>
 * <li><b>Restore</b> ({@link #restoreOnGameThread}, behind {@link LunaSyncState#drainOnGameThread}): GAME THREAD
 *     ONLY, because LunaLib's settings panel reads the same map every frame. Each mod's entry is FETCHED AGAIN (a dev
 *     reload replaces the entry object), every snapshotted key gets its old value back, one report per mod.</li>
 * </ol>
 * Exits that run off the game thread only raise {@link LunaSyncState#markRestorePending}; see that class.
 *
 * <p>Read-back: {@link #status()} is the {@code lunaSync} field of the editor host's {@code api/ping}:
 * {@code set:<rows>/<mods> missing:<k> skipped:<j> digest:<16 hex>}, {@code absent}, {@code lunalib off} or
 * {@code refused:<class>}. "set" counts puts, never their effect (a mod may read a setting only at game load).
 * Before the first apply of a decoded sheet it reads {@code set:0/0 missing:0 skipped:0 digest:<hex>}.
 *
 * <p>Logs: one row per load / apply / restore (counts, the digest and class SimpleNames only), plus one row per
 * row-guard refusal naming its mod and field ids (both codec-lawful tokens) and the refusal class; never a value.
 *
 *
 */
public final class LunaSyncApply {

    /** The staged sheet's file name, beside the match file in the kit's mod folder. */
    static final String SIDECAR_NAME = "luna-settings.txt";
    /** The sidecar's first line binds it to one launch: {@code luna.match=<match id>}. */
    static final String HEADER_PREFIX = "luna.match=";
    /**
     * The sidecar read cap: the header line at the longest lawful match id, its line break, and the codec's own
     * sheet cap. A larger file is never read past this and reads {@code refused:oversize}.
     */
    static final int SIDECAR_READ_MAX = HEADER_PREFIX.length() + MatchDocumentLaws.MATCH_ID_MAX + 1
            + LunaSheetCodec.TEXT_MAX;

    static final String STATUS_ABSENT = "absent";
    static final String STATUS_LUNALIB_OFF = "lunalib off";
    static final String REFUSED = "refused:";
    /** LunaLib's declared type of a per-player key binding: such a row refuses the whole sheet. */
    static final String TYPE_KEYCODE = "Keycode";

    private static final String LUNALIB_MOD_ID = "lunalib";
    /**
     * The LunaLib classes the handles bind (LunaLib 2.0.3, read from its bytecode).
     *
     */
    private static final String LOADER_CLASS = "lunalib.backend.ui.settings.LunaSettingsLoader";
    private static final String SETTINGS_CLASS = "lunalib.lunaSettings.LunaSettings";
    private static final String DATA_CLASS = "lunalib.backend.ui.settings.LunaSettingsData";

    private static volatile Sidecar pending = Sidecar.ABSENT;
    private static volatile Access access;
    private static volatile String status = STATUS_ABSENT;
    private static volatile String linkDetail = "not probed";
    private static final AtomicInteger FAULTS = new AtomicInteger();

    private LunaSyncApply() {
    }

    /**
     * LunaLib's settings map as the apply and the restore reach it: a lookup by mod id plus three calls per entry,
     * the report, and the declared fields. The game binds it to LunaLib through method handles; the selftest binds
     * a fake map, so the apply and the restore run unchanged off the game.
     */
    interface Access {
        /** {@code getSettings().get(modId)}, fetched afresh on every call; null when this game holds no entry. */
        Object entryOrNull(String modId) throws Throwable;

        boolean has(Object entry, String fieldId) throws Throwable;

        Object opt(Object entry, String fieldId) throws Throwable;

        void put(Object entry, String fieldId, Object value) throws Throwable;

        void reportSettingsChanged(String modId) throws Throwable;

        /** Every declaration by mod id, then field id, for the named mods only (a later duplicate wins). */
        Map<String, Map<String, Declared>> declared(Set<String> modIds) throws Throwable;
    }

    /**
     * One LunaLib declaration as the re-validation reads it, every part from LunaLib's own getters for THIS game:
     * the declared {@code fieldType}; {@code min} / {@code max} exactly as LunaLib computed them at its load
     * (meaningful for Int and Double only: a blank or unparsable {@code minValue} leaves 0.0, a blank
     * {@code maxValue} reads 100.0, both clamped to plus or minus 2.1474836E9; a NaN bound admits no value); and
     * {@code secondary}, the declaration's {@code secondaryValue} as text ("" when null), a Radio's option list.
     *
     *
     *
     */
    record Declared(String type, double min, double max, String secondary) {
    }

    /** LunaLib's own slack at a Double bound (its text field's {@code 1.0E-6F}); see {@link #rowRefusal}. */
    static final double LUNA_BOUND_TOLERANCE = 1.0E-6F;

    /**
     * The decoded sidecar: the launch's match id, the sheet rows and the sheet's digest when {@code failStatus} is
     * null; else {@code failStatus} is the read-back word ({@code absent} or {@code refused:<class>}) and nothing applies.
     */
    record Sidecar(String matchId, List<LunaSheetCodec.Row> rows, String digest, String failStatus) {
        static final Sidecar ABSENT = new Sidecar(null, List.of(), null, STATUS_ABSENT);

        static Sidecar refused(String cls) {
            return new Sidecar(null, List.of(), null, REFUSED + cls);
        }

        boolean ok() {
            return failStatus == null;
        }
    }

    /**
     * Application load, once: read and decode the sidecar beside the configured match file, then link LunaLib.
     * Never throws (a failure is a status word and one counted fault); never touches LunaLib's map.
     */
    public static void loadSidecarAtApplicationLoad() {
        long t0 = System.nanoTime();
        Sidecar s;
        try {
            String descriptorPath = MatchMissionBuilder.cachedDescriptorPath();
            s = descriptorPath == null ? Sidecar.ABSENT
                    : readSidecar(Paths.get(descriptorPath).resolveSibling(SIDECAR_NAME));
        } catch (Throwable t) {
            FAULTS.incrementAndGet();
            s = Sidecar.refused("unreadable");
        }
        Access a = linkOrNull();
        install(a, s);
        prewarm();
        PilotWatchLog.info("luna-sync load: " + status + " link=" + linkDetail + " rows=" + s.rows().size()
                + " faults=" + FAULTS.get() + " ms=" + ((System.nanoTime() - t0) / 1_000_000L));
    }

    /** The {@code api/ping} {@code lunaSync} word: one volatile read, safe from any thread. */
    public static String status() {
        return status;
    }

    /**
     * Arm the apply: the linked map (null = LunaLib off) and the decoded sidecar, and the read-back word they give.
     * The application load's own step; the selftest arms its fake map through it.
     */
    static void install(Access a, Sidecar s) {
        access = a;
        pending = s == null ? Sidecar.ABSENT : s;
        status = initialStatus(pending, a);
    }

    static String initialStatus(Sidecar s, Access a) {
        if (!s.ok()) {
            return s.failStatus();
        }
        if (a == null) {
            return STATUS_LUNALIB_OFF;
        }
        return setStatus(0, 0, 0, 0, s.digest());
    }

    static String setStatus(int rows, int mods, int missing, int skipped, String digest) {
        return "set:" + rows + "/" + mods + " missing:" + missing + " skipped:" + skipped + " digest:" + digest;
    }

    /**
     * Read the sidecar BOUNDED: a size over {@link #SIDECAR_READ_MAX} refuses before any byte is read, and the read
     * itself stops one byte past the cap (a file that grew after the size check stays bounded). A missing file is
     * {@code absent}; any other I/O fault {@code refused:unreadable} (the exception text is never kept).
     */
    static Sidecar readSidecar(Path path) {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            if (ch.size() > SIDECAR_READ_MAX) {
                return Sidecar.refused("oversize");
            }
            ByteBuffer buf = ByteBuffer.allocate(SIDECAR_READ_MAX + 1);
            int n;
            do {
                n = ch.read(buf);
            } while (n >= 0 && buf.hasRemaining());
            if (buf.position() > SIDECAR_READ_MAX) {
                return Sidecar.refused("oversize");
            }
            return parseSidecar(new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8));
        } catch (NoSuchFileException absent) {
            return Sidecar.ABSENT;
        } catch (IOException | RuntimeException unreadable) {
            FAULTS.incrementAndGet();
            return Sidecar.refused("unreadable");
        }
    }

    /**
     * Line 1 = {@code luna.match=<id>} (1..{@link MatchDocumentLaws#MATCH_ID_MAX} printable ASCII, else
     * {@code refused:header}); the rest = the sheet, decoded by the shared codec (its refusal's class word becomes
     * {@code refused:<class>}). The digest is over the sheet text exactly as staged.
     */
    static Sidecar parseSidecar(String text) {
        int nl = text.indexOf('\n');
        if (nl < 0 || !text.startsWith(HEADER_PREFIX)) {
            return Sidecar.refused("header");
        }
        String matchId = text.substring(HEADER_PREFIX.length(), nl);
        if (!lawfulMatchId(matchId)) {
            return Sidecar.refused("header");
        }
        String sheet = text.substring(nl + 1);
        LunaSheetCodec.Decoded d = LunaSheetCodec.decode(sheet);
        if (!d.ok()) {
            String refusal = d.refusal();
            int sp = refusal.indexOf(' ');
            return Sidecar.refused(sp < 0 ? refusal : refusal.substring(0, sp));
        }
        return new Sidecar(matchId, d.rows(), LunaSheetCodec.digest16(sheet), null);
    }

    private static boolean lawfulMatchId(String id) {
        if (id.isEmpty() || id.length() > MatchDocumentLaws.MATCH_ID_MAX) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c <= ' ' || c > '~') {
                return false;
            }
        }
        return true;
    }

    /**
     * Combat init, GAME THREAD: restore any unclaimed snapshot first, then apply the sheet for this match. Never
     * throws: a fault is counted and read back as {@code refused:fault}. ONE log row.
     *
     * @param configuredMatchId the match id of the match file this game booted with; null = none configured
     * @param engine the combat engine this init runs under, recorded in the published snapshot so a drain point in
     *     any OTHER engine (the title screen's) restores it even when no exit raised a mark
     *     ({@link LunaSyncState#restoreIfForeign}); compared by reference only
     */
    public static void applyAtCombatInit(String configuredMatchId, CombatEngineAPI engine) {
        long t0 = System.nanoTime();
        String detail;
        boolean restoredFirst = false;
        try {
            restoredFirst = restoreOnGameThread();
            detail = applyOnGameThread(configuredMatchId, engine);
        } catch (Throwable t) {
            FAULTS.incrementAndGet();
            status = REFUSED + "fault";
            detail = "fault=" + t.getClass().getSimpleName();
        }
        PilotWatchLog.info("luna-sync apply: " + status + " " + detail + " restored_first=" + restoredFirst
                + " faults=" + FAULTS.get() + " ms=" + ((System.nanoTime() - t0) / 1_000_000L));
    }

    private static String applyOnGameThread(String configuredMatchId, CombatEngineAPI engine) throws Throwable {
        Sidecar s = pending;
        Access a = access;
        if (!s.ok() || a == null) {
            return "idle";
        }
        if (!s.matchId().equals(configuredMatchId)) {
            status = REFUSED + "match";
            return "puts=0";
        }
        Set<String> mods = new HashSet<>();
        for (LunaSheetCodec.Row r : s.rows()) {
            mods.add(r.modId());
        }
        List<LunaSheetCodec.Row> toApply = new ArrayList<>(s.rows().size());
        int[] counts = new int[2];
        String refusal = validate(s.rows(), a.declared(mods), toApply, counts);
        if (refusal != null) {
            status = REFUSED + refusal;
            return "puts=0";
        }
        int missing = counts[0];
        int skipped = counts[1];
        int puts = 0;
        int failed = 0;
        Set<String> touched = new LinkedHashSet<>();
        List<LunaSyncState.Entry> snap = new ArrayList<>(toApply.size());
        try {
            for (LunaSheetCodec.Row r : toApply) {
                try {
                    Object entry = a.entryOrNull(r.modId());
                    if (entry == null) {
                        missing++;
                        continue;
                    }
                    // LunaLib writes every declared key at load, so an absent key is version skew: skipped, never added
                    if (!a.has(entry, r.fieldId())) {
                        skipped++;
                        continue;
                    }
                    Object old = a.opt(entry, r.fieldId());
                    if (old == null) {
                        skipped++;
                        continue;
                    }
                    // snapshot BEFORE the put: a put that throws leaves the old value, and restoring it is harmless
                    snap.add(new LunaSyncState.Entry(r.modId(), r.fieldId(), old));
                    a.put(entry, r.fieldId(), typedValue(r));
                    puts++;
                    touched.add(r.modId());
                } catch (Throwable rowFault) {
                    failed++;
                }
            }
        } finally {
            publishSnapshot(new LunaSyncState.Snapshot(s.matchId(), snap, engine));
        }
        for (String mod : touched) {
            try {
                a.reportSettingsChanged(mod);
            } catch (Throwable reportFault) {
                failed++;
            }
        }
        if (failed > 0) {
            FAULTS.addAndGet(failed);
        }
        status = setStatus(puts, touched.size(), missing, skipped, s.digest());
        return "puts=" + puts + " row_faults=" + failed;
    }

    /**
     * The re-validation against this game's own declarations. Returns the refusal class or null; on null,
     * {@code toApply} holds the rows to put and {@code counts} = {missing, skipped}.
     * <ul>
     * <li>{@code excluded}: ANY row the shared exclusion law names (a whole excluded mod, among them LunaLib's own and
     *     ours; a table row; a key-like field name). Checked over every row first, so no later row can mask it.</li>
     * <li>{@code key}: a row this game declares {@value #TYPE_KEYCODE} (a per-player binding).</li>
     * <li>{@code type}: the declared type's wire letter differs from the row's letter (a type with no letter, such as
     *     an enum or a text row, never matches).</li>
     * <li>counted, not refused: a row for a mod this game declares nothing for (missing); a field it does not declare
     *     (skipped).</li>
     * <li>counted as skipped, never refusing the sheet: a row the per-row guard ({@link #rowRefusal}) refuses by its
     *     DECLARED type (an Int or Double outside the declared min..max, a Radio value outside the declared options,
     *     a Color LunaLib's own read could not decode). The guard runs only once every row has passed the three
     *     refusals above, so no row of a refused sheet is guarded or logged; each guarded row logs ONE
     *     {@code luna-sync row_refused} line (mod and field ids plus the class word, never the value) and the other
     *     rows apply.</li>
     * </ul>
     *
     *
     */
    static String validate(List<LunaSheetCodec.Row> rows, Map<String, Map<String, Declared>> declared,
            List<LunaSheetCodec.Row> toApply, int[] counts) {
        for (LunaSheetCodec.Row r : rows) {
            if (LunaSyncExclusions.excluded(r.modId(), r.fieldId())) {
                return "excluded";
            }
        }
        List<LunaSheetCodec.Row> typed = new ArrayList<>(rows.size());
        List<Declared> typedDecl = new ArrayList<>(rows.size());
        for (LunaSheetCodec.Row r : rows) {
            Map<String, Declared> fields = declared.get(r.modId());
            if (fields == null) {
                counts[0]++;
                continue;
            }
            Declared d = fields.get(r.fieldId());
            if (d == null) {
                counts[1]++;
                continue;
            }
            if (TYPE_KEYCODE.equals(d.type())) {
                return "key";
            }
            if (letterOf(d.type()) != r.type()) {
                return "type";
            }
            typed.add(r);
            typedDecl.add(d);
        }
        for (int i = 0; i < typed.size(); i++) {
            LunaSheetCodec.Row r = typed.get(i);
            String cls = rowRefusal(typedDecl.get(i), r.value());
            if (cls == null) {
                toApply.add(r);
                continue;
            }
            counts[1]++;
            // the decode already proved both ids codec-lawful; re-checked so no other caller can put free text in a log
            String mod = LunaSheetCodec.lawfulModId(r.modId()) ? r.modId() : "?";
            String field = LunaSheetCodec.lawfulFieldId(r.fieldId()) ? r.fieldId() : "?";
            PilotWatchLog.info("luna-sync row_refused mod=" + mod + " field=" + field + " class=" + cls);
        }
        return null;
    }

    /**
     * The per-row guard by the row's DECLARED type (never by its wire letter), after the letter compare passed:
     * {@code range} = an Int or Double outside {@code min <= v <= max} (inclusive at both ends; an unparsable or
     * non-finite number is refused too); {@code option} = a Radio value equal to none of {@link #radioOptionsHold}'s
     * options; {@code shape} = a Color {@link #colorDecodes} refuses; null = no further check or the row passes
     * (String, Boolean and every other declared type). A mod may cache a served number without a clamp of its own, so
     * an out-of-range value would reach it unguarded.
     *
     *
     */
    static String rowRefusal(Declared d, String value) {
        String type = d.type();
        if ("Int".equals(type)) {
            try {
                int v = Integer.parseInt(value);
                return d.min() <= v && v <= d.max() ? null : "range";
            } catch (NumberFormatException unparsable) {
                return "range";
            }
        }
        if ("Double".equals(type)) {
            try {
                double v = Double.parseDouble(value);
                // LunaLib's own bound compare, mirrored: its loader parses a declared bound with Float.parseFloat and
                // widens it (a declared 0.1 reads 0.10000000149...), while every value it writes is the exact double
                // (0.1), so an exact compare refused an honest host's value AT a decimal bound. LunaLib's
                // text field compares with a 1.0E-6F tolerance; the same here. A NaN
                // bound is refused on its own: the tolerance compare alone would admit it (every compare false).
                if (!Double.isFinite(v) || Double.isNaN(d.min()) || Double.isNaN(d.max())) {
                    return "range";
                }
                return v - LUNA_BOUND_TOLERANCE > d.max() || d.min() > v + LUNA_BOUND_TOLERANCE ? "range" : null;
            } catch (NumberFormatException unparsable) {
                return "range";
            }
        }
        if ("Radio".equals(type)) {
            return radioOptionsHold(d.secondary(), value) ? null : "option";
        }
        if ("Color".equals(type)) {
            return colorDecodes(value) ? null : "shape";
        }
        return null;
    }

    /**
     * True when {@code value} equals (exactly) one of the Radio options LunaLib's settings panel builds from the
     * declaration's {@code secondaryValue}: split on every literal {@code ","} (empty pieces kept, as Kotlin's
     * {@code split(",")} keeps them) and each piece trimmed with {@link #lunaTrim}. A blank {@code secondaryValue}
     * declares no options and holds no value.
     *
     *
     *
     */
    static boolean radioOptionsHold(String secondary, String value) {
        if (secondary == null || lunaTrim(secondary).isEmpty()) {
            return false;
        }
        int from = 0;
        while (true) {
            int comma = secondary.indexOf(',', from);
            String option = comma < 0 ? secondary.substring(from) : secondary.substring(from, comma);
            if (lunaTrim(option).equals(value)) {
                return true;
            }
            if (comma < 0) {
                return false;
            }
            from = comma + 1;
        }
    }

    /**
     * True when LunaLib's own Color read would decode {@code value}: {@code "#"} is prepended when the text holds no
     * {@code '#'} anywhere, the result is trimmed with {@link #lunaTrim} and upper-cased ({@code Locale.ROOT}), then
     * {@code java.awt.Color.decode} (a static parse; no toolkit).
     *
     *
     *
     */
    static boolean colorDecodes(String value) {
        String text = value.indexOf('#') < 0 ? "#" + value : value;
        try {
            java.awt.Color.decode(lunaTrim(text).toUpperCase(Locale.ROOT));
            return true;
        } catch (NumberFormatException badShape) {
            return false;
        }
    }

    /**
     * Kotlin's {@code CharSequence.trim()} as LunaLib calls it: strips leading and trailing chars that are
     * {@code Character.isWhitespace} OR {@code Character.isSpaceChar} (a no-break space too, unlike
     * {@code String.trim}).
     *
     *
     */
    static String lunaTrim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && kotlinWhitespace(s.charAt(start))) {
            start++;
        }
        while (end > start && kotlinWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean kotlinWhitespace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** LunaLib's declared {@code fieldType} (exact case, as LunaLib compares it) to the sheet's letter; 0 = none. */
    static char letterOf(String fieldType) {
        if (fieldType == null) {
            return 0;
        }
        switch (fieldType) {
            case "Boolean":
                return LunaSheetCodec.TYPE_BOOLEAN;
            case "Int":
                return LunaSheetCodec.TYPE_INT;
            case "Double":
                return LunaSheetCodec.TYPE_DOUBLE;
            case "String":
            case "Color":
            case "Radio":
                return LunaSheetCodec.TYPE_STRING;
            default:
                return 0;
        }
    }

    /** The value object org.json would have parsed the row's text into (the codec already proved the grammar). */
    static Object typedValue(LunaSheetCodec.Row r) {
        switch (r.type()) {
            case LunaSheetCodec.TYPE_BOOLEAN:
                return Boolean.valueOf(r.value());
            case LunaSheetCodec.TYPE_INT:
                return Integer.valueOf(r.value());
            case LunaSheetCodec.TYPE_DOUBLE:
                return Double.valueOf(r.value());
            default:
                return r.value();
        }
    }

    /**
     * Publish the apply's snapshot. The apply restored any earlier snapshot first, so the publish normally finds the
     * slot empty. If it does not (a snapshot nobody claimed), that snapshot holds the player's TRUE values for its
     * keys, while this apply's snapshot recorded the earlier match's served values for them: the two are merged with
     * the earlier one winning, so the next restore still puts back the player's own values. A merge that still
     * cannot publish is a counted fault.
     */
    private static void publishSnapshot(LunaSyncState.Snapshot snap) {
        if (snap.entries().isEmpty() || LunaSyncState.publish(snap)) {
            return;
        }
        LunaSyncState.Snapshot prior = LunaSyncState.claimSnapshotOrNull();
        if (!LunaSyncState.publish(prior == null ? snap : merged(prior, snap))) {
            FAULTS.incrementAndGet();
        }
    }

    static LunaSyncState.Snapshot merged(LunaSyncState.Snapshot earlier, LunaSyncState.Snapshot later) {
        List<LunaSyncState.Entry> out = new ArrayList<>(earlier.entries());
        Set<String> keys = new HashSet<>();
        for (LunaSyncState.Entry e : earlier.entries()) {
            keys.add(e.modId() + '\t' + e.fieldId());
        }
        for (LunaSyncState.Entry e : later.entries()) {
            if (keys.add(e.modId() + '\t' + e.fieldId())) {
                out.add(e);
            }
        }
        // the served values now live under the later apply's engine, so the merge keeps that one
        return new LunaSyncState.Snapshot(later.matchId(), out, later.engine());
    }

    /**
     * GAME THREAD ONLY: clear the restore mark, claim the published snapshot and put every snapshotted value back
     * into each mod's entry as it is NOW (fetched again: a dev reload replaces the entry object), then report each
     * touched mod once. A mod whose entry is gone, or a key with no recorded value, is counted and left alone (never a
     * remove). Returns true when a mark was cleared or a snapshot was restored. Never throws; ONE log row when a
     * snapshot was restored.
     */
    public static boolean restoreOnGameThread() {
        boolean marked = LunaSyncState.clearMarkOnGameThread();
        LunaSyncState.Snapshot s = LunaSyncState.claimSnapshotOrNull();
        if (s == null) {
            return marked;
        }
        long t0 = System.nanoTime();
        int puts = 0;
        int gone = 0;
        int failed = 0;
        Set<String> touched = new LinkedHashSet<>();
        try {
            Access a = access;
            if (a == null) {
                failed = s.entries().size();
            } else {
                for (LunaSyncState.Entry e : s.entries()) {
                    try {
                        Object entry = a.entryOrNull(e.modId());
                        if (entry == null || e.oldValue() == null) {
                            gone++;
                            continue;
                        }
                        a.put(entry, e.fieldId(), e.oldValue());
                        puts++;
                        touched.add(e.modId());
                    } catch (Throwable putFault) {
                        failed++;
                    }
                }
                for (String mod : touched) {
                    try {
                        a.reportSettingsChanged(mod);
                    } catch (Throwable reportFault) {
                        failed++;
                    }
                }
            }
        } catch (Throwable t) {
            failed++;
        }
        if (failed > 0) {
            FAULTS.addAndGet(failed);
        }
        PilotWatchLog.info("luna-sync restore: puts=" + puts + " mods=" + touched.size() + " gone=" + gone
                + " row_faults=" + failed + " ms=" + ((System.nanoTime() - t0) / 1_000_000L));
        return true;
    }

    /** The load-time link: LunaLib enabled AND every handle bound, else null with the reason kept for the load row. */
    private static Access linkOrNull() {
        try {
            if (!Global.getSettings().getModManager().isModEnabled(LUNALIB_MOD_ID)) {
                linkDetail = "disabled";
                return null;
            }
            Access a = HandleAccess.link(Global.getSettings().getScriptClassLoader());
            linkDetail = "ok";
            return a;
        } catch (Throwable t) {
            FAULTS.incrementAndGet();
            linkDetail = t.getClass().getSimpleName();
            return null;
        }
    }

    /**
     * First-touch, at application load, of the classes the combat-init apply and the restore construct, so neither
     * loads a class mid-combat (the application-load pre-warm precedent of this mod).
     */
    private static void prewarm() {
        try {
            LunaSyncState.Snapshot warm = new LunaSyncState.Snapshot("", List.of(new LunaSyncState.Entry("", "", "")), null);
            LunaSyncExclusions.excluded("", "");
            letterOf(warm.matchId());
            rowRefusal(new Declared(warm.matchId(), 0.0, 0.0, ""), "");
        } catch (Throwable t) {
            FAULTS.incrementAndGet();
        }
    }

    /**
     * LunaLib bound through method handles (exact types; every lookup at load, so a missing member is
     * {@code lunalib off} before any match). The map entry is LazyLib's {@code CommonDataJSONObject}, a subclass of
     * the game's {@code org.json.JSONObject}, so the JSONObject members bind it. A declaration's {@code getMinValue} /
     * {@code getMaxValue} ({@code ()D}) and {@code getSecondaryValue} ({@code ()Ljava/lang/Object;}) feed the per-row
     * guard; like the other declaration getters they are erased to an {@code Object} receiver.
     *
     *
     *
     */
    private static final class HandleAccess implements Access {
        private final MethodHandle getSettingsMh;
        private final MethodHandle getSettingsDataMh;
        private final MethodHandle reportMh;
        private final MethodHandle putMh;
        private final MethodHandle optMh;
        private final MethodHandle hasMh;
        private final MethodHandle modIdMh;
        private final MethodHandle fieldIdMh;
        private final MethodHandle fieldTypeMh;
        private final MethodHandle minValueMh;
        private final MethodHandle maxValueMh;
        private final MethodHandle secondaryValueMh;

        private HandleAccess(MethodHandle[] h) {
            getSettingsMh = h[0];
            getSettingsDataMh = h[1];
            reportMh = h[2];
            putMh = h[3];
            optMh = h[4];
            hasMh = h[5];
            modIdMh = h[6];
            fieldIdMh = h[7];
            fieldTypeMh = h[8];
            minValueMh = h[9];
            maxValueMh = h[10];
            secondaryValueMh = h[11];
        }

        static HandleAccess link(ClassLoader scripts) throws Throwable {
            Class<?> loader = Class.forName(LOADER_CLASS, false, scripts);
            Class<?> settings = Class.forName(SETTINGS_CLASS, false, scripts);
            Class<?> data = Class.forName(DATA_CLASS, false, scripts);
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            MethodType getter = MethodType.methodType(String.class);
            MethodType erased = MethodType.methodType(String.class, Object.class);
            MethodType erasedDouble = MethodType.methodType(double.class, Object.class);
            return new HandleAccess(new MethodHandle[] {
                    l.findStatic(loader, "getSettings", MethodType.methodType(Map.class)),
                    l.findStatic(loader, "getSettingsData", MethodType.methodType(List.class)),
                    l.findStatic(settings, "reportSettingsChanged", MethodType.methodType(void.class, String.class)),
                    l.findVirtual(JSONObject.class, "put",
                            MethodType.methodType(JSONObject.class, String.class, Object.class)),
                    l.findVirtual(JSONObject.class, "opt", MethodType.methodType(Object.class, String.class)),
                    l.findVirtual(JSONObject.class, "has", MethodType.methodType(boolean.class, String.class)),
                    l.findVirtual(data, "getModID", getter).asType(erased),
                    l.findVirtual(data, "getFieldID", getter).asType(erased),
                    l.findVirtual(data, "getFieldType", getter).asType(erased),
                    l.findVirtual(data, "getMinValue", MethodType.methodType(double.class)).asType(erasedDouble),
                    l.findVirtual(data, "getMaxValue", MethodType.methodType(double.class)).asType(erasedDouble),
                    l.findVirtual(data, "getSecondaryValue", MethodType.methodType(Object.class))
                            .asType(MethodType.methodType(Object.class, Object.class)),
            });
        }

        @Override
        public Object entryOrNull(String modId) throws Throwable {
            Map<?, ?> map = (Map<?, ?>) getSettingsMh.invokeExact();
            return map == null ? null : map.get(modId);
        }

        @Override
        public boolean has(Object entry, String fieldId) throws Throwable {
            return (boolean) hasMh.invokeExact((JSONObject) entry, fieldId);
        }

        @Override
        public Object opt(Object entry, String fieldId) throws Throwable {
            return (Object) optMh.invokeExact((JSONObject) entry, fieldId);
        }

        @Override
        public void put(Object entry, String fieldId, Object value) throws Throwable {
            JSONObject self = (JSONObject) putMh.invokeExact((JSONObject) entry, fieldId, value); // returns the entry
        }

        @Override
        public void reportSettingsChanged(String modId) throws Throwable {
            reportMh.invokeExact(modId);
        }

        @Override
        public Map<String, Map<String, Declared>> declared(Set<String> modIds) throws Throwable {
            Map<String, Map<String, Declared>> out = new HashMap<>();
            List<?> all = (List<?>) getSettingsDataMh.invokeExact();
            if (all == null) {
                return out;
            }
            for (Object d : all) {
                if (d == null) {
                    continue;
                }
                String mod = (String) modIdMh.invokeExact(d);
                if (mod == null || !modIds.contains(mod)) {
                    continue;
                }
                String field = (String) fieldIdMh.invokeExact(d);
                String type = (String) fieldTypeMh.invokeExact(d);
                double min = (double) minValueMh.invokeExact(d);
                double max = (double) maxValueMh.invokeExact(d);
                Object secondary = (Object) secondaryValueMh.invokeExact(d);
                Map<String, Declared> fields = out.get(mod);
                if (fields == null) {
                    fields = new HashMap<>();
                    out.put(mod, fields);
                }
                fields.put(field, new Declared(type, min, max, secondary == null ? "" : String.valueOf(secondary)));
            }
            return out;
        }
    }
}
