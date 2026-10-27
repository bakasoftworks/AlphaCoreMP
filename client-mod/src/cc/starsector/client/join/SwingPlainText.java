package cc.starsector.client.join;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/**
 * Plain-text Swing dialogs for the join window. WHY: a {@code JLabel} whose text starts with the six-character
 * HTML opener renders it as HTML ({@code BasicHTML.isHTMLString}), and {@code JOptionPane} turns each line of a
 * String message into such a label, so a server-published mod id leading a line of the Mods refusal rendered
 * server-chosen markup (an image tag loads synchronously on the EDT). Law: every dialog body that carries
 * server- or peer-derived text goes through here. The server ops GUI carries the twin
 * {@code cc.starsector.server.ops.SwingPlainText}.
 *
 */
final class SwingPlainText {

    /** The client property {@code BasicHTML.updateRenderer} reads: {@code Boolean.TRUE} = never build an HTML view. */
    static final String HTML_DISABLE = "html.disable";

    private SwingPlainText() {
    }

    /** Marks {@code c} so its later {@code setText} never builds an HTML view (labels, buttons, cell renderers). */
    static <T extends JComponent> T plain(T c) {
        c.putClientProperty(HTML_DISABLE, Boolean.TRUE);
        return c;
    }

    /** A read-only text area styled like the option pane's label (null text = empty). A text component never
     *  builds an HTML view, so this does not rely on {@code BasicOptionPaneUI} copying {@link #HTML_DISABLE}. */
    static JComponent message(String text) {
        JTextArea area = new JTextArea(text == null ? "" : text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(null);
        area.setLineWrap(false);
        Font font = UIManager.getFont("Label.font");
        if (font != null) {
            area.setFont(font);
        }
        Color foreground = UIManager.getColor("Label.foreground");
        if (foreground != null) {
            area.setForeground(foreground);
        }
        return plain(area);
    }

    /** {@code JOptionPane.showMessageDialog} with {@code text} shown as plain data (same parent, title, type). */
    static void showMessage(Component parent, String text, String title, int messageType) {
        JOptionPane.showMessageDialog(parent, message(text), title, messageType);
    }
}
