package cc.starsector.client.join;

import java.awt.Component;
import java.awt.Dimension;
import java.util.List;

import javax.sound.sampled.Mixer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

import cc.starsector.client.voice.VoiceClientSettings;

/**
 * The joiner window's "Voice" box: microphone pick, enable,
 * gain slider, and a {@code Test} level bar so a player proves the mic works BEFORE the match
 * (in-game there is no device UI). Lives in {@code join/**} (b-deny-EXEMPT, join-leak-gated:
 * no in-game class may reference it); runs on the install's Zulu-17 JRE with the mod jar only,
 * so it touches Swing and the game-free {@link VoiceClientSettings} value, never a game API class.
 *
 * <p>Since GUI v2 step 4 the device list, the preselect, the file value and
 * the Test worker are {@link JoinVoiceCore} — ONE law with the browser joiner's voice bridge; this
 * panel is the Swing rendering of it: the worker's callbacks hop to the EDT through {@code
 * invokeLater} and re-check {@link JoinVoiceCore.Tester#isCurrent} on arrival (the generation guard
 * the panel always had). Toggle-off, Launch and window close all call {@link #stopTest()}.
 */
final class JoinVoicePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    static final String DEFAULT_DEVICE = JoinVoiceCore.DEFAULT_DEVICE;
    static final String QUALITY_SERVER_DEFAULT = "Server default";
    static final String CANNOT_OPEN = JoinVoiceCore.CANNOT_OPEN;
    static final String RESAMPLED_SUFFIX = JoinVoiceCore.RESAMPLED_SUFFIX;

    private final JComboBox<String> deviceBox;
    private final JCheckBox micOn;
    /** Opus complexity — index 0 = server default, 1..11 = 0..10. */
    private final JComboBox<String> quality;
    private final JSlider gain;
    private final JLabel gainLabel;
    private final JToggleButton test;
    private final JProgressBar level;
    private final JLabel status;
    private final List<Mixer.Info> mixers;
    private final JoinVoiceCore.Tester tester = new JoinVoiceCore.Tester();

    JoinVoicePanel(VoiceClientSettings prefill) {
        VoiceClientSettings p = prefill == null ? VoiceClientSettings.DEFAULTS : prefill;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Voice"),
                BorderFactory.createEmptyBorder(4, 8, 6, 8)));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        mixers = JoinVoiceCore.captureMixers();
        deviceBox = new JComboBox<>();
        deviceBox.addItem(DEFAULT_DEVICE);
        for (Mixer.Info info : mixers) {
            deviceBox.addItem(info.getName());
        }
        deviceBox.setSelectedIndex(JoinVoiceCore.preselect(mixers, p.micDevice));
        deviceBox.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                deviceBox.getPreferredSize().height));

        micOn = new JCheckBox("Enable microphone", p.micOn);
        quality = new JComboBox<>();
        quality.addItem(QUALITY_SERVER_DEFAULT);
        for (int c = 0; c <= VoiceClientSettings.MAX_COMPLEXITY; c++) {
            quality.addItem(String.valueOf(c));
        }
        quality.setSelectedIndex(p.complexity == VoiceClientSettings.SERVER_DEFAULT ? 0
                : p.complexity + 1);
        quality.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                quality.getPreferredSize().height));
        gain = new JSlider(0, JoinVoiceCore.GAIN_PERCENT_MAX, Math.max(0, //
                Math.min(JoinVoiceCore.GAIN_PERCENT_MAX, Math.round(p.micGain * 100f))));
        gain.setMajorTickSpacing(50);
        gain.setPaintTicks(true);
        gainLabel = new JLabel(gain.getValue() + "%");
        tester.gain = gain.getValue() / 100f;
        gain.addChangeListener(e -> {
            gainLabel.setText(gain.getValue() + "%");
            tester.gain = gain.getValue() / 100f;
        });

        test = new JToggleButton("Test");
        level = new JProgressBar(0, 100);
        level.setPreferredSize(new Dimension(220, 16));
        status = new JLabel(" ");

        add(labeled("Microphone:", deviceBox));
        add(Box.createVerticalStrut(4));
        micOn.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(micOn);
        add(Box.createVerticalStrut(4));
        JPanel gainRow = new JPanel();
        gainRow.setLayout(new BoxLayout(gainRow, BoxLayout.X_AXIS));
        gainRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        gainRow.add(new JLabel("Mic volume: "));
        gainRow.add(gain);
        gainRow.add(Box.createHorizontalStrut(6));
        gainRow.add(gainLabel);
        add(gainRow);
        add(Box.createVerticalStrut(4));
        add(labeled("Voice quality (Opus complexity 0-10):", quality));
        add(Box.createVerticalStrut(4));
        JPanel testRow = new JPanel();
        testRow.setLayout(new BoxLayout(testRow, BoxLayout.X_AXIS));
        testRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        testRow.add(test);
        testRow.add(Box.createHorizontalStrut(8));
        testRow.add(level);
        add(testRow);
        add(Box.createVerticalStrut(2));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(status);

        test.addActionListener(e -> {
            if (test.isSelected()) {
                startTest();
            } else {
                stopTest();
            }
        });
        deviceBox.addActionListener(e -> {
            if (test.isSelected()) {
                stopTest();
                startTest();
            }
        });
    }

    // ------------------------------------------------------------------ values

    /** The picked mixer's file value ("" = default). */
    String deviceValue() {
        return JoinVoiceCore.deviceValue(mixers, deviceBox.getSelectedIndex());
    }

    /** True when a non-default pick reduced to "" (a one-line note for the player). */
    boolean deviceNameLost() {
        return JoinVoiceCore.deviceNameLost(mixers, deviceBox.getSelectedIndex());
    }

    boolean micOnValue() {
        return micOn.isSelected();
    }

    float micGainValue() {
        return gain.getValue() / 100f;
    }

    /** The quality pick: {@code VoiceClientSettings.SERVER_DEFAULT} (−1) or complexity 0..10. */
    int complexityValue() {
        int idx = quality.getSelectedIndex();
        return idx <= 0 ? VoiceClientSettings.SERVER_DEFAULT : idx - 1;
    }

    // ------------------------------------------------------------------ test worker (JoinVoiceCore.Tester)

    private void startTest() {
        Mixer.Info mixer = JoinVoiceCore.mixerAt(mixers, deviceBox.getSelectedIndex());
        tester.start(mixer, new JoinVoiceCore.Listener() {
            @Override
            public void level(long runId, int percent) {
                SwingUtilities.invokeLater(() -> {
                    if (tester.isCurrent(runId)) {
                        level.setValue(percent);
                    }
                });
            }

            @Override
            public void status(long runId, String line) {
                SwingUtilities.invokeLater(() -> {
                    if (tester.isCurrent(runId)) {
                        status.setText(line);
                    }
                });
            }

            @Override
            public void failed(long runId, String line) {
                SwingUtilities.invokeLater(() -> {
                    if (tester.isCurrent(runId)) {
                        status.setText(line);
                        test.setSelected(false);
                        level.setValue(0);
                    }
                });
            }
        });
    }

    /** Stop the worker and close the line; safe to call repeatedly and from the EDT. */
    void stopTest() {
        tester.stop();
        level.setValue(0);
    }

    // ------------------------------------------------------------------ furniture

    private static JPanel labeled(String label, Component field) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(new JLabel(label + " "));
        p.add(field);
        return p;
    }
}
