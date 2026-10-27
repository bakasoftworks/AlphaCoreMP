package cc.starsector.client.join;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.ComboBoxEditor;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;

/**
 * The join window's ship combo, LABELLED: the combo's items stay ship ids (the launch path, the remembered pick and
 * {@code JoinLaunchPlan.sanitizeShip} all read ids), and a row shows the ship's hull and its id, never its display
 * name: the hull says what the ship is, the id says which loadout it is, and the in-game picker shows the same two.
 * <ul>
 * <li>The popup rows ({@link #rowText}): the server's hull id when the probe carried one
 * ({@code DoorProbe.Result.hulls}), then {@link #SEPARATOR} and the id as a dimmer suffix; the id alone when the
 * server sent no hull for it (an older server, or a ship row without one).</li>
 * <li>The editor ({@link #shown} / {@link #toId}): {@code setItem(id)} shows the id itself; {@code getItem()} maps the
 * shown text back to the id: the item it last showed when the text is unchanged, else an exact display-name match
 * (ASCII case-fold, unique names only; the names map is still received, so a typed ship name still picks that ship),
 * else the raw typed text (a typed id, the existing fallback). A hull id never maps back: many ships share one hull,
 * so a typed hull id stays raw text like any other unknown word, never a guessed ship.</li>
 * </ul>
 * WHY no name: a row that repeated the display name beside the hull and the id read as noise, and two ships sharing
 * a name stayed apart only by the id anyway. Labels are DISPLAY ONLY, never a path, a command or a launch byte; every
 * label here was already cut at decode by the listing reader's label laws ({@code ClientListingReader}) and is painted
 * through {@link SwingPlainText#plain} labels, so a server label can never build an HTML view.
 * EDT-confined like the rest of the window.
 *
 */
final class ShipChoiceNames {

    /** Between a row's hull and its id: two spaces, an em dash, two spaces. */
    static final String SEPARATOR = "  " + (char) 0x2014 + "  ";

    private final Map<String, String> idToName = new HashMap<>();
    /** Id -> the server's hull id for it (display only; never mapped back: many ships share one hull). */
    private final Map<String, String> idToHull = new HashMap<>();
    /** The name's fold -> its id, for names that belong to exactly ONE id. */
    private final Map<String, String> foldToId = new HashMap<>();
    /** Folds two ids share — never mapped back (the editor's last-shown item still round-trips them). */
    private final Set<String> sharedFolds = new HashSet<>();

    /** Wraps {@code box}'s current renderer + editor (their look kept) with the naming pair; returns the map holder
     *  the caller fills. Builds Swing components only (no window), so it runs headless like the selftests' combos. */
    static ShipChoiceNames install(JComboBox<String> box) {
        ShipChoiceNames names = new ShipChoiceNames();
        box.setRenderer(names.new Renderer(box.getRenderer()));
        box.setEditor(names.new Editor(box.getEditor()));
        return names;
    }

    /** Replaces every known name and hull id with {@code names} / {@code hulls} (id -> label; null maps and
     *  null/empty entries skipped) — one served probe's whole set, so a stale server's labels never linger. */
    void replace(Map<String, String> names, Map<String, String> hulls) {
        idToName.clear();
        idToHull.clear();
        foldToId.clear();
        sharedFolds.clear();
        if (names != null) {
            for (Map.Entry<String, String> e : names.entrySet()) {
                put(e.getKey(), e.getValue());
            }
        }
        if (hulls != null) {
            for (Map.Entry<String, String> e : hulls.entrySet()) {
                putHull(e.getKey(), e.getValue());
            }
        }
    }

    /** One id's hull id (null/blank id or hull = ignored). */
    void putHull(String id, String hull) {
        if (id == null || id.isEmpty() || hull == null || hull.trim().isEmpty()) {
            return;
        }
        idToHull.put(id, hull);
    }

    /** One id's name (null/empty id or name = ignored). */
    void put(String id, String name) {
        if (id == null || id.isEmpty() || name == null || name.trim().isEmpty()) {
            return;
        }
        idToName.put(id, name);
        String fold = fold(name);
        if (sharedFolds.contains(fold)) {
            return;
        }
        String prior = foldToId.putIfAbsent(fold, id);
        if (prior != null && !prior.equals(id)) {
            foldToId.remove(fold);
            sharedFolds.add(fold);
        }
    }

    /** The name known for {@code item}, else null. */
    String nameOrNull(Object item) {
        return item == null ? null : idToName.get(String.valueOf(item));
    }

    /** The hull id known for {@code item}, else null. */
    String hullOrNull(Object item) {
        return item == null ? null : idToHull.get(String.valueOf(item));
    }

    /**
     * A popup row's text: {@code hull + SEPARATOR + id} when the server sent a hull for the id, else the item as-is.
     * A known display name never paints. The bright head ({@link #headText}) + the dimmer {@code SEPARATOR + id} tail
     * the renderer paints are exactly this text split in two.
     */
    String rowText(Object item) {
        String hull = hullOrNull(item);
        return hull == null ? String.valueOf(item) : hull + SEPARATOR + item;
    }

    /** The row's bright head: the hull id when one is known, else the item as-is. */
    String headText(Object item) {
        String hull = hullOrNull(item);
        return hull == null ? String.valueOf(item) : hull;
    }

    /** The editor's text for {@code item}: the item as-is, never its display name (null = empty). */
    String shown(Object item) {
        return item == null ? "" : String.valueOf(item);
    }

    /** The editor text back to an item: the last-shown item when the text is still its {@link #shown} form, else the
     *  id of an exact (fold) unique name, else {@code raw} untouched (a typed id or the server-default label). */
    Object toId(Object raw, Object lastShown) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw);
        if (lastShown != null && text.equals(shown(lastShown))) {
            return lastShown;
        }
        String id = foldToId.get(fold(text));
        return id != null ? id : raw;
    }

    /** The match fold: trimmed, ASCII lower-cased (the labels are printable ASCII by the seat-name law). */
    static String fold(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /** The popup rows: the base renderer's look; a hulled row = a two-label panel (the hull bright, the dimmer
     *  {@code SEPARATOR + id} suffix); a hull-less row = the base label carrying the id alone. */
    private final class Renderer implements ListCellRenderer<String> {
        private final ListCellRenderer<? super String> base;
        private final JPanel row = new JPanel(new BorderLayout());
        private final JLabel headLabel = SwingPlainText.plain(new JLabel());
        private final JLabel idLabel = SwingPlainText.plain(new JLabel());

        Renderer(ListCellRenderer<? super String> base) {
            this.base = base;
            row.add(headLabel, BorderLayout.WEST);
            row.add(idLabel, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String value, int index,
                boolean isSelected, boolean cellHasFocus) {
            Component c = base.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (!(c instanceof JLabel)) {
                return c;
            }
            JLabel b = (JLabel) c;
            if (hullOrNull(value) == null) {
                // a hull-less row keeps the base label (the id), marked plain (the re-set drops any view built before)
                SwingPlainText.plain(b).setText(b.getText());
                return b;
            }
            row.setOpaque(true);
            row.setBackground(b.getBackground());
            row.setBorder(b.getBorder());
            headLabel.setFont(b.getFont());
            headLabel.setForeground(b.getForeground());
            headLabel.setText(headText(value));
            idLabel.setFont(b.getFont());
            idLabel.setForeground(blend(b.getForeground(), b.getBackground()));
            idLabel.setText(SEPARATOR + value);
            return row;
        }
    }

    /** Halfway between the text and background colours (the dimmer suffix); either null = the text colour. */
    private static Color blend(Color fg, Color bg) {
        if (fg == null || bg == null) {
            return fg;
        }
        return new Color((fg.getRed() + bg.getRed()) / 2, (fg.getGreen() + bg.getGreen()) / 2,
                (fg.getBlue() + bg.getBlue()) / 2);
    }

    /** The editor: the base editor's component + look; only the item <-> text mapping is ours. */
    private final class Editor implements ComboBoxEditor {
        private final ComboBoxEditor base;
        private Object lastShown;

        Editor(ComboBoxEditor base) {
            this.base = base;
        }

        @Override
        public Component getEditorComponent() {
            return base.getEditorComponent();
        }

        @Override
        public void setItem(Object item) {
            lastShown = item;
            base.setItem(item == null ? null : shown(item));
        }

        @Override
        public Object getItem() {
            return toId(base.getItem(), lastShown);
        }

        @Override
        public void selectAll() {
            base.selectAll();
        }

        @Override
        public void addActionListener(ActionListener l) {
            base.addActionListener(l);
        }

        @Override
        public void removeActionListener(ActionListener l) {
            base.removeActionListener(l);
        }
    }
}
