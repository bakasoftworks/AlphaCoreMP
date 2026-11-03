package cc.starsector.server.runtime;

import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The serve's ONE LunaSettings sheet mint, run once at boot at the mod-set moment (never on the door's reader thread):
 * the effective value of every pushable LunaLib setting the enabled mods declare, judged row by row, then minted
 * into the {@link ProbeLunaSheet} a password-proven probe reply serves so each joiner plays under the host's
 * settings.
 *
 * <p>WHY a declared-field walk and never the raw value map: LunaLib keeps stale keys a mod dropped from its CSV in
 * the saved file and in memory forever, so only what a mod DECLARES today (with its type) may ride; the value is
 * then read live from the map LunaLib's own getters read.
 *
 * <p>Two halves. {@link #build} is the game-free judge (fake maps in the contract rows): a row rides only when its
 * mod is not excluded whole, its type is one of {@code Boolean Int Double String Color Radio} (a {@code Keycode} is
 * per-player, {@code Text} / {@code Header} carry no value, {@code Enum} / {@code Multichoice} have no LunaLib getter
 * that combat code could read), its key is on neither the built-in table ({@link LunaSyncExclusions}, which also
 * holds the key-name net) nor the host's {@code luna.sync.exclude} row, its ids are wire-lawful, and its value is
 * present and passes {@link LunaSheetCodec#lawfulValue} as {@code String.valueOf} prints it (over 120 chars = skipped,
 * never truncated). A field id one mod declares more than once rides ONCE as its first declaration (type included):
 * the codec refuses a duplicate key and would otherwise drop the whole sheet. A cap breach drops the WHOLE sheet (the
 * kit would refuse it); a zero-row sheet is not served.
 * {@link #fromLunaLib} is the live reader: plain reflection through the script class loader that loaded the mods
 * (this class is server-only and never packed into the kit jar, whose class loader denies reflection), and every
 * fault there reads {@code lunalib=not-loaded} with a null sheet, never a throw that would fail a serve boot.
 *
 * <p>Log law: the rows print counts, a digest, mod ids from the minted mod-set census and an exception's simple
 * name; never a setting value or a field id (a value may be anything a host typed into a mod's settings).
 *
 *
 */
public final class LunaSheetMint {

    /** LunaLib's settings store (a Kotlin object; its two statics below are {@code @JvmStatic}). */
    static final String LOADER_CLASS = "lunalib.backend.ui.settings.LunaSettingsLoader";
    /** One declared setting: a Kotlin data class with public getters. */
    static final String DATA_CLASS = "lunalib.backend.ui.settings.LunaSettingsData";
    /** The value object's base type (each map entry is LazyLib's subclass of it). */
    static final String JSON_CLASS = "org.json.JSONObject";

    private static final String NO_DIGEST = "-";
    private static final String SHEET_PREFIX = "luna sheet ";

    /** The sheet this serve holds for its probe replies (null = none), installed once at boot by {@link #installServed}. */
    private static volatile ProbeLunaSheet served;

    private LunaSheetMint() {
    }

    /**
     * One declared field as LunaLib declares it: {@code fieldType} spelled as the mod's CSV spells it ({@code Boolean
     * Int Double String Color Radio Keycode Enum Multichoice Text Header}, case-sensitive like LunaLib's own parse).
     */
    public record Declared(String fieldId, String fieldType) {
    }

    /**
     * One mint's outcome. Every count is non-negative. {@code mods} = distinct mods among the minted rows; {@code rows}
     * = the rows that passed every filter (the sheet's row count when minted, the dropped count on a drop);
     * {@code excluded} = declared rows a type, the table, the key-name net, the host row or a whole-mod entry kept
     * off; {@code skipped} = pushable rows with an absent, unlawful or over-long value or an unlawful id, plus every
     * later copy of a field id its mod already declared;
     * {@code dropClass} = the breached cap's class word when the whole sheet was dropped, else null; {@code bytes} =
     * the canonical text's UTF-8 size (0 with no row); {@code starvedMods} = mods (sorted) that declared at least one
     * pushable row yet contributed none.
     */
    public record Result(ProbeLunaSheet sheetOrNull, int mods, int rows, int excluded, int skipped, String dropClass,
            int bytes, List<String> starvedMods) {
    }

    /**
     * One mint with its duplicate count, which {@link Result} does not carry: {@code duplicates} (>= 0) = the later
     * copies of a field id a mod declared more than once, each dropped (its first declaration rides) and each also
     * counted in {@code result.skipped()}.
     *
     *
     */
    public record Counted(Result result, int duplicates) {
    }

    /** The host's exclude row parsed: lawful {@code <modId>:<fieldId>} items (sorted) and the malformed count (>= 0). */
    public record HostExclude(Set<String> items, int malformed) {
    }

    /** The empty outcome: no LunaLib, no rows. */
    private static final Result NONE = new Result(null, 0, 0, 0, 0, null, 0, List.of());

    /**
     * The game-free judge. {@code declaredByMod} = each mod's declared fields (walked in sorted mod order);
     * {@code valuesByMod.apply(modId)} = that mod's live values by field id (null = none); {@code hostExcluded} =
     * {@code <modId>:<fieldId>} items (null = none). Never throws on a breach: a cap or law breach while minting
     * reads {@code dropClass} with a null sheet.
     */
    public static Result build(Map<String, List<Declared>> declaredByMod,
            Function<String, Map<String, Object>> valuesByMod, Set<String> hostExcluded) {
        return buildCounted(declaredByMod, valuesByMod, hostExcluded).result();
    }

    /**
     * {@link #build} plus its duplicate count (the boot path's call, so {@link #printRows(boolean, Counted, Set,
     * PrintStream)} can warn). A field id one mod declares again is judged once, at its FIRST declaration (type
     * included): the codec refuses a duplicate (modId, fieldId) key, so keeping both would drop the whole sheet, and
     * every later copy counts skipped whatever its type. The set is per mod: two mods may share a field id.
     *
     *
     */
    public static Counted buildCounted(Map<String, List<Declared>> declaredByMod,
            Function<String, Map<String, Object>> valuesByMod, Set<String> hostExcluded) {
        Set<String> host = hostExcluded == null ? Set.of() : hostExcluded;
        TreeMap<String, List<Declared>> sorted = new TreeMap<>();
        if (declaredByMod != null) {
            for (Map.Entry<String, List<Declared>> e : declaredByMod.entrySet()) {
                sorted.put(e.getKey() == null ? "" : e.getKey(), e.getValue() == null ? List.of() : e.getValue());
            }
        }
        List<LunaSheetCodec.Row> rows = new ArrayList<>();
        List<String> starved = new ArrayList<>();
        int excluded = 0;
        int skipped = 0;
        int duplicates = 0;
        int mods = 0;
        for (Map.Entry<String, List<Declared>> e : sorted.entrySet()) {
            String modId = e.getKey();
            List<Declared> fields = e.getValue();
            if (LunaSyncExclusions.excludedMod(modId)) {
                excluded += fields.size();
                continue;
            }
            Map<String, Object> values = null;
            Set<String> seen = new HashSet<>();
            int pushable = 0;
            int contributed = 0;
            for (Declared d : fields) {
                String fieldId = d == null ? null : d.fieldId();
                if (fieldId != null && !seen.add(fieldId)) {
                    skipped++;
                    duplicates++;
                    continue;
                }
                char letter = d == null ? 0 : letterOrZero(d.fieldType());
                if (letter == 0 || LunaSyncExclusions.excluded(modId, fieldId) || host.contains(modId + ":" + fieldId)) {
                    excluded++;
                    continue;
                }
                pushable++;
                if (!LunaSheetCodec.lawfulModId(modId) || !LunaSheetCodec.lawfulFieldId(fieldId)) {
                    skipped++;
                    continue;
                }
                if (values == null) {
                    Map<String, Object> got = valuesByMod == null ? null : valuesByMod.apply(modId);
                    values = got == null ? Map.of() : got;
                }
                Object value = values.get(fieldId);
                if (value == null) {
                    skipped++;
                    continue;
                }
                String text = String.valueOf(value);
                if (text.length() > LunaSheetCodec.VALUE_MAX || !LunaSheetCodec.lawfulValue(letter, text)) {
                    skipped++;
                    continue;
                }
                rows.add(new LunaSheetCodec.Row(modId, fieldId, letter, text));
                contributed++;
            }
            if (contributed > 0) {
                mods++;
            } else if (pushable > 0) {
                starved.add(modId);
            }
        }
        List<String> starvedMods = Collections.unmodifiableList(starved);
        return new Counted(mint(rows, mods, excluded, skipped, starvedMods), duplicates);
    }

    /** Mint the judged {@code rows} (counts as the walk made them) into the {@link Result}; never throws on a breach. */
    private static Result mint(List<LunaSheetCodec.Row> rows, int mods, int excluded, int skipped,
            List<String> starvedMods) {
        if (rows.isEmpty()) {
            return new Result(null, 0, 0, excluded, skipped, null, 0, starvedMods);
        }
        int bytes = wouldBeBytes(rows);
        try {
            String text = LunaSheetCodec.canonicalText(rows);
            ProbeLunaSheet sheet = ProbeLunaSheet.of(text);
            return new Result(sheet, mods, rows.size(), excluded, skipped, null, LunaSheetCodec.utf8Length(text),
                    starvedMods);
        } catch (IllegalArgumentException breach) {
            return new Result(null, mods, rows.size(), excluded, skipped, classWord(breach.getMessage()), bytes,
                    starvedMods);
        } catch (IllegalStateException selfCheck) {
            // the codec's mint and its decode law disagree: the sheet is dropped whole and the drop row says so
            return new Result(null, mods, rows.size(), excluded, skipped, "selfcheck", bytes, starvedMods);
        }
    }

    /**
     * The live reader over LunaLib's own statics, resolved through {@code modLoader} (the script class loader that
     * loaded the enabled mods' jars; null = none): {@code LunaSettingsLoader.getSettingsData()} (each element's
     * {@code getModID / getFieldID / getFieldType}) and {@code getSettings()} (mod id to the value object, read by
     * {@code JSONObject.opt}). Prints, in order: a {@code luna-sync read-fault=} row when a fault other than an
     * absent LunaLib hit the read, the drop row, one warn row per starved mod of the minted mod-set census, one
     * duplicates warn row when a mod declared a field id twice, then exactly ONE boot row ({@link #bootRow}). Never
     * throws.
     */
    public static Result fromLunaLib(ClassLoader modLoader, Set<String> hostExcluded, PrintStream out) {
        Map<String, List<Declared>> declared = null;
        Map<String, Map<String, Object>> values = null;
        Class<?> loaderClass = null;
        if (modLoader != null) {
            try {
                loaderClass = Class.forName(LOADER_CLASS, false, modLoader);
            } catch (ClassNotFoundException absent) {
                loaderClass = null; // LunaLib is not an enabled mod: the normal no-sheet serve
            } catch (Throwable fault) {
                out.println("luna-sync read-fault=" + simpleName(fault));
            }
        }
        if (loaderClass != null) {
            try {
                Class<?> dataClass = Class.forName(DATA_CLASS, false, modLoader);
                Class<?> json = Class.forName(JSON_CLASS, false, modLoader);
                Method modIdOf = dataClass.getMethod("getModID");
                Method fieldIdOf = dataClass.getMethod("getFieldID");
                Method typeOf = dataClass.getMethod("getFieldType");
                Method opt = json.getMethod("opt", String.class);
                Object jsonNull = json.getField("NULL").get(null);
                List<?> data = (List<?>) loaderClass.getMethod("getSettingsData").invoke(null);
                Map<?, ?> settings = (Map<?, ?>) loaderClass.getMethod("getSettings").invoke(null);
                Map<String, List<Declared>> byMod = new TreeMap<>();
                for (Object d : data == null ? List.of() : data) {
                    if (!dataClass.isInstance(d)) {
                        continue;
                    }
                    String mod = textOrEmpty(modIdOf.invoke(d));
                    byMod.computeIfAbsent(mod, k -> new ArrayList<>())
                            .add(new Declared(textOrEmpty(fieldIdOf.invoke(d)), textOrEmpty(typeOf.invoke(d))));
                }
                Map<String, Map<String, Object>> live = new HashMap<>();
                for (Map.Entry<String, List<Declared>> e : byMod.entrySet()) {
                    Object entry = settings == null ? null : settings.get(e.getKey());
                    Map<String, Object> modValues = new HashMap<>();
                    if (json.isInstance(entry)) {
                        for (Declared f : e.getValue()) {
                            Object v = opt.invoke(entry, f.fieldId());
                            if (scalar(v) && !jsonNull.equals(v)) {
                                modValues.put(f.fieldId(), v);
                            }
                        }
                    }
                    live.put(e.getKey(), modValues);
                }
                declared = byMod;
                values = live;
            } catch (Throwable fault) {
                // LunaLib resolved but its shape did not bind (a renamed getter, a missing class): loud, never thrown
                declared = null;
                out.println("luna-sync read-fault=" + simpleName(fault));
            }
        }
        if (declared == null) {
            out.println(bootRow(false, NONE));
            return NONE;
        }
        Map<String, Map<String, Object>> liveValues = values;
        Counted counted = buildCounted(declared, liveValues::get, hostExcluded);
        printRows(true, counted, censusIdsOrNull(), out);
        return counted.result();
    }

    /** {@link #printRows(boolean, Counted, Set, PrintStream)} for a mint with no duplicate count (none warned). */
    public static void printRows(boolean loaded, Result r, Set<String> censusOrNull, PrintStream out) {
        printRows(loaded, new Counted(r, 0), censusOrNull, out);
    }

    /**
     * The rows {@link #fromLunaLib} prints for a read LunaLib, in order: the drop row (a dropped sheet), one warn row
     * per starved mod that is in {@code censusOrNull} (null = no census minted: none), ONE
     * {@code luna-sync warn duplicates=<n>} row when {@code c.duplicates()} is at least 1 (a count only, never which
     * field), then the boot row.
     *
     *
     */
    public static void printRows(boolean loaded, Counted c, Set<String> censusOrNull, PrintStream out) {
        Result r = c.result();
        if (r.dropClass() != null) {
            out.println("luna-sync dropped=" + r.dropClass() + " rows=" + r.rows() + " bytes=" + r.bytes());
        }
        if (censusOrNull != null) {
            for (String mod : r.starvedMods()) {
                if (censusOrNull.contains(mod)) {
                    out.println("luna-sync warn mod=" + mod + " rows=0");
                }
            }
        }
        if (c.duplicates() > 0) {
            out.println("luna-sync warn duplicates=" + c.duplicates());
        }
        out.println(bootRow(loaded, r));
    }

    /**
     * The one boot row: {@code luna-sync lunalib=<loaded|not-loaded> mods=<M> rows=<N> excluded=<K> skipped=<J>
     * digest=<16 lowercase hex|->} (counts non-negative; {@code -} = no sheet served).
     */
    public static String bootRow(boolean loaded, Result r) {
        return "luna-sync lunalib=" + (loaded ? "loaded" : "not-loaded") + " mods=" + r.mods() + " rows=" + r.rows()
                + " excluded=" + r.excluded() + " skipped=" + r.skipped() + " digest="
                + (r.sheetOrNull() == null ? NO_DIGEST : r.sheetOrNull().digest16());
    }

    /**
     * The host's {@code luna.sync.exclude} row: comma-separated items, each trimmed; an empty item is ignored; an
     * item is lawful as {@code <modId>:<fieldId>} split at its LAST colon (a mod id may hold a colon, a field id may
     * not) with {@link LunaSheetCodec#lawfulModId} and {@link LunaSheetCodec#lawfulFieldId}; anything else counts
     * malformed, never a throw. Null = an empty row.
     */
    public static HostExclude parseHostExclude(String row) {
        Set<String> items = new TreeSet<>();
        int malformed = 0;
        if (row != null) {
            for (String raw : row.split(",", -1)) {
                String item = raw.trim();
                if (item.isEmpty()) {
                    continue;
                }
                int colon = item.lastIndexOf(':');
                String mod = colon < 0 ? "" : item.substring(0, colon);
                String field = colon < 0 ? "" : item.substring(colon + 1);
                if (!LunaSheetCodec.lawfulModId(mod) || !LunaSheetCodec.lawfulFieldId(field)) {
                    malformed++;
                    continue;
                }
                items.add(mod + ":" + field);
            }
        }
        return new HostExclude(Collections.unmodifiableSet(items), malformed);
    }

    /**
     * The term the reel META stamps and the re-simulation compares: {@code <digest16>:<rows>} of the served sheet
     * (the probe's announce term for the same text), {@link LunaSheetCodec#NO_SHEET_TERM} for none.
     */
    public static String termOf(ProbeLunaSheet sheetOrNull) {
        return sheetOrNull == null ? LunaSheetCodec.NO_SHEET_TERM : sheetOrNull.digest16() + ":" + sheetOrNull.rows();
    }

    /** Hold the boot's sheet for the door's construction (null = none served). */
    public static void installServed(ProbeLunaSheet sheetOrNull) {
        served = sheetOrNull;
    }

    /** The sheet {@link #installServed} holds, or null (no boot mint ran, no LunaLib, nothing pushable, a drop). */
    public static ProbeLunaSheet servedOrNull() {
        return served;
    }

    /**
     * The engine's script class loader (the one that loaded every enabled mod's jar and the mods' plugins), read
     * the way the content init reads it: {@code Global.getSettings().getScriptClassLoader()} through this class's
     * own loader. Null when no engine booted (a rig's fake binding); a fault prints one {@code read-fault} row.
     */
    public static ClassLoader scriptLoaderOrNull(PrintStream out) {
        try {
            ClassLoader app = LunaSheetMint.class.getClassLoader();
            Object settings = Class.forName("com.fs.starfarer.api.Global", true, app).getMethod("getSettings")
                    .invoke(null);
            if (settings == null) {
                return null;
            }
            Object loader = Class.forName("com.fs.starfarer.api.SettingsAPI", true, app)
                    .getMethod("getScriptClassLoader").invoke(settings);
            return loader instanceof ClassLoader ? (ClassLoader) loader : null;
        } catch (ClassNotFoundException noEngine) {
            return null; // no game API on this classpath: nothing to read
        } catch (Throwable fault) {
            out.println("luna-sync read-fault=" + simpleName(fault) + " at=script-loader");
            return null;
        }
    }

    /** The minted mod-set census's ids, or null when no set was minted (the warn rows need the census). */
    private static Set<String> censusIdsOrNull() {
        ModSet set = ModSet.installedOrNull();
        if (set == null) {
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (ModSet.Record r : set.records()) {
            ids.add(r.id());
        }
        return ids;
    }

    /** The pushed letter for a declared type, 0 = never pushed (an unknown type included: default-deny). */
    static char letterOrZero(String fieldType) {
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

    /** The class word of a mint refusal ({@code "rows count=257"} reads {@code rows}); a non-word reads {@code unknown}. */
    static String classWord(String message) {
        String m = message == null ? "" : message;
        if (m.startsWith(SHEET_PREFIX)) {
            m = m.substring(SHEET_PREFIX.length());
        }
        int space = m.indexOf(' ');
        String word = space < 0 ? m : m.substring(0, space);
        if (word.isEmpty() || word.length() > 24) {
            return "unknown";
        }
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (!((c >= 'a' && c <= 'z') || c == '_')) {
                return "unknown";
            }
        }
        return word;
    }

    /** The canonical text's UTF-8 size for {@code rows} (order-free: only the index digits depend on position). */
    private static int wouldBeBytes(List<LunaSheetCodec.Row> rows) {
        int n = ("luna.v=" + LunaSheetCodec.VERSION + "\n").length() + ("luna.count=" + rows.size() + "\n").length();
        for (int i = 0; i < rows.size(); i++) {
            LunaSheetCodec.Row r = rows.get(i);
            n += ("luna." + i + "=").length() + LunaSheetCodec.utf8Length(r.modId()) + 1
                    + LunaSheetCodec.utf8Length(r.fieldId()) + 3 + LunaSheetCodec.utf8Length(r.value()) + 1;
        }
        return n;
    }

    /** A value org.json parsed as a scalar; a nested object or array never rides (its text is not a setting). */
    private static boolean scalar(Object v) {
        return v instanceof Boolean || v instanceof Number || v instanceof CharSequence;
    }

    private static String textOrEmpty(Object v) {
        return v instanceof String ? (String) v : "";
    }

    private static String simpleName(Throwable t) {
        Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
        return cause.getClass().getSimpleName();
    }
}
