package djmax;

import com.group_finity.mascot.lumi.plugin.PluginTheme;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Path;
import java.util.logging.Logger;

/** {@link ChartEditorPanel} plus a save/close toolbar — one of {@link HubWindow}'s screens, not a
 *  window of its own, so opening the editor never leaves the shared game window. */
final class ChartEditorScreen extends JPanel {
    private final ChartEditorPanel editor;

    private ChartEditorScreen(ChartEditorPanel editor) {
        super(new BorderLayout());
        this.editor = editor;
    }

    /** @param onClose called for "close" and after a successful "save then close" — the caller
     *  swaps the shared window back to whatever screen should show next (normally the Hub). */
    static ChartEditorScreen build(PluginTheme theme, Logger log, File songFile, String title,
                                    Path chartsDir, Runnable onSaved, Runnable onClose) throws Exception {
        Chart initial = ChartStore.load(chartsDir, songFile, title);
        if (initial == null) {
            initial = tryAutoDraft(songFile, title, log);
        }

        ChartEditorPanel panel = new ChartEditorPanel(songFile, initial);
        ChartEditorScreen screen = new ChartEditorScreen(panel);

        JLabel speedLabel = new JLabel("노트 속도(%)");
        speedLabel.setForeground(Color.LIGHT_GRAY);
        JSpinner speedSpinner = new JSpinner(new SpinnerNumberModel(
                (int) Math.round(panel.speedMultiplier() * 100),
                (int) Math.round(ChartEditorPanel.MIN_SPEED_MULTIPLIER * 100),
                (int) Math.round(ChartEditorPanel.MAX_SPEED_MULTIPLIER * 100),
                5));
        speedSpinner.addChangeListener(e -> panel.setSpeedMultiplier((Integer) speedSpinner.getValue() / 100.0));
        JButton save = new JButton("저장");
        theme.button(save, PluginTheme.Button.OUTLINE);
        JButton saveClose = new JButton("저장 후 닫기");
        theme.button(saveClose, PluginTheme.Button.PRIMARY);
        JButton close = new JButton("닫기");
        theme.button(close, PluginTheme.Button.OUTLINE);
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        toolbar.add(speedLabel);
        toolbar.add(speedSpinner);
        toolbar.add(save);
        toolbar.add(saveClose);
        toolbar.add(close);

        Runnable doSave = () -> {
            try {
                ChartStore.save(chartsDir, songFile, panel.lengthMs(), panel.notes(), panel.speedMultiplier());
                if (onSaved != null) {
                    onSaved.run();
                }
            } catch (Exception ex) {
                log.warning("채보 저장 실패: " + ex.getMessage());
                JOptionPane.showMessageDialog(screen, "저장 실패: " + ex.getMessage(),
                        "채보 편집", JOptionPane.ERROR_MESSAGE);
            }
        };
        save.addActionListener(e -> doSave.run());
        saveClose.addActionListener(e -> {
            doSave.run();
            onClose.run();
        });
        close.addActionListener(e -> onClose.run());

        screen.add(panel, BorderLayout.CENTER);
        screen.add(toolbar, BorderLayout.SOUTH);
        return screen;
    }

    /** No saved chart yet: offer the auto-chart (NORMAL) as a starting draft rather than an empty timeline. */
    private static Chart tryAutoDraft(File songFile, String title, Logger log) {
        try {
            return ChartGenerator.fromWav(songFile, title, Difficulty.NORMAL);
        } catch (Exception ex) {
            log.fine("자동 채보 초안 생성 건너뜀: " + ex.getMessage());
            return null;
        }
    }

    /** Releases the editor's audio preview resources — call when leaving this screen. */
    void close() {
        editor.close();
    }
}
