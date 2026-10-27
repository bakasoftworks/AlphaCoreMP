package cc.starsector.client.join;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * The per-monitor-DPI relayout the retained Swing joiner never had
 * (a player's "elements disappearing randomly and this fixing itself when i transfer them
 * between monitors"). Ported from the Server Ops GUI, which carries the proven fix
 * ({@code ServerOpsGui.java} rows 595-723 + {@code OpsWindowLayout.invalidateTree}):
 * Swing measures text under the component's CURRENT monitor transform but
 * every descendant keeps the preferred size it cached under the monitor it was packed on, and
 * {@code frame.invalidate()} walks UP only — so after a cross onto a different-scale monitor the
 * labels/buttons clip to "..." and rows vanish until the next move happens to re-validate them.
 * The cure is a tree-wide invalidate + ONE validate whenever the frame's scale changes: a
 * {@value #POLL_MILLIS} ms scale poll, a {@code componentMoved} check, and one deferred settle
 * after show (WM_DPICHANGED can post after {@code setVisible}). Zero steady-state cost.
 *
 * <p>Two relayout policies: the join FORM (no scroll pane) re-pins its minimum to the freshly
 * measured preferred size and grows to fit ({@link #arm(JFrame, boolean)} with
 * {@code minFollowsPreferred}); the launch FACE keeps its small fixed minimum (its wrapping text
 * areas report a width-dependent preferred size). The old form pin —
 * {@code setMinimumSize(frame.getSize())} measured ONCE at pack — was the bug class: a minimum
 * frozen under the pack-time monitor's metrics.
 */
final class JoinWindowDpi {
    /** Scale-poll cadence (the ops GUI's value). */
    static final int POLL_MILLIS = 1000;
    /** One deferred relayout after show, for a WM_DPICHANGED that posts after {@code setVisible}. */
    static final int SETTLE_MILLIS = 200;
    private static final String ARMED = "cc.join.dpiArmed";
    /** The CURRENT relayout policy: re-set on every {@link #arm} call so the
     *  launch-face flip's {@code false} governs a later monitor cross, not the form's captured {@code true}. */
    private static final String MIN_FOLLOWS_PREFERRED = "cc.join.dpiMinFollowsPreferred";

    /** The policy the LAST {@link #arm} call set for this frame. */
    static boolean minFollowsPreferred(JFrame frame) {
        return Boolean.TRUE.equals(frame.getRootPane().getClientProperty(MIN_FOLLOWS_PREFERRED));
    }

    private JoinWindowDpi() {
    }

    /** Invalidate EVERY component under {@code root} (a plain {@code invalidate()} walks up only). */
    static void invalidateTree(Component root) {
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) {
                invalidateTree(child);
            }
        }
        root.invalidate();
    }

    /** Tree-wide re-measure under the frame's CURRENT monitor transform; the form policy also
     *  re-pins the minimum to the fresh preferred size and grows (never shrinks) to fit it. */
    static void relayout(JFrame frame, boolean minFollowsPreferred) {
        invalidateTree(frame);
        frame.validate();
        if (minFollowsPreferred) {
            Dimension pref = frame.getPreferredSize();
            frame.setMinimumSize(pref);
            if (frame.getWidth() < pref.width || frame.getHeight() < pref.height) {
                frame.setSize(Math.max(frame.getWidth(), pref.width),
                        Math.max(frame.getHeight(), pref.height));
                frame.validate();
            }
        }
        frame.repaint();
    }

    /** The frame's monitor scale (1.0 at 100 %, 1.5 at 150 %); 0 when it has no configuration yet. */
    static double scaleOf(JFrame frame) {
        GraphicsConfiguration gc = frame.getGraphicsConfiguration();
        return gc == null ? 0.0 : gc.getDefaultTransform().getScaleX();
    }

    /**
     * Arm the frame — call AFTER {@code pack()} (the guard seeds with the PACK-time scale so the
     * show-time landing on a different-DPI monitor counts as a change) and around
     * {@code setVisible(true)}. Idempotent for the poll + listeners (the join form flips into the
     * launch face inside the SAME frame); every call starts one fresh settle relayout.
     */
    static void arm(JFrame frame, boolean minFollowsPreferred) {
        // per call, BEFORE the armed guard: the poll + listener read the live property, never the
        // first call's captured argument (the face flip re-arms with false)
        frame.getRootPane().putClientProperty(MIN_FOLLOWS_PREFERRED, Boolean.valueOf(minFollowsPreferred));
        if (!Boolean.TRUE.equals(frame.getRootPane().getClientProperty(ARMED))) {
            frame.getRootPane().putClientProperty(ARMED, Boolean.TRUE);
            final double[] lastScale = {scaleOf(frame)};
            Runnable onScaleChange = () -> {
                double scale = scaleOf(frame);
                if (scale == 0.0) {
                    return;
                }
                if (lastScale[0] != 0.0 && scale != lastScale[0]) {
                    relayout(frame, minFollowsPreferred(frame));
                }
                lastScale[0] = scale;
            };
            Timer poll = new Timer(POLL_MILLIS, e -> onScaleChange.run());
            poll.setRepeats(true);
            poll.start();
            frame.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentMoved(ComponentEvent e) {
                    onScaleChange.run();
                }
            });
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    poll.stop();
                }
            });
        }
        Timer settle = new Timer(SETTLE_MILLIS, e -> relayout(frame, minFollowsPreferred(frame)));   // The live property, not this call's captured arg (a face flip re-arms before the timer fires)
        settle.setRepeats(false);
        settle.start();
    }

    /**
     * A holder whose maximum height follows the LIVE preferred height — for a field / combo inside
     * a vertical or horizontal {@code BoxLayout}, so the row never stretches. The build-time
     * {@code setMaximumSize(new Dimension(MAX, getPreferredSize().height))} pin it replaces froze
     * the pack-time monitor's row height (the same class as {@code OpsWindowLayout.stack}).
     */
    static JPanel heightToPreferred(JComponent inner) {
        JPanel hold = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        hold.setOpaque(false);
        hold.setAlignmentX(Component.LEFT_ALIGNMENT);
        hold.add(inner, BorderLayout.CENTER);
        return hold;
    }
}
