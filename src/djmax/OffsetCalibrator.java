package djmax;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.KeyEvent;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * A "tap along to the beat" wizard, shown as an in-window overlay (a dimmed scrim over the rest of
 * the Settings screen, with a centered card on top) rather than a separate {@code JDialog} — this
 * app already avoids ever opening a second real window (see {@link HubWindow}'s class doc), and a
 * modal dialog would have been the one exception.
 * <p>
 * Counts in groups of 4 — "1, 2, 3, TAP · 1, 2, 3, TAP · …" — instead of asking for a press on
 * every single click: three unaccented count clicks establish the groove, then the accented 4th is
 * the one instant actually measured, so the player only has to react roughly once every 2 seconds
 * instead of twice a second. Deliberately audio-only, no visual note to react to — see the class's
 * earlier revision history for why (this offset calibrates reaction to sound, not sight).
 * <p>
 * Sign convention: {@code medianDelta = pressTime - beatTime}; a positive value means presses land
 * <i>after</i> the audible tap on average, so the suggested offset is {@code -medianDelta} —
 * {@link RhythmPanel#nowMs()} adds the offset, and shifting "now" earlier by the amount the player
 * tends to be late makes an equally-late real press judge as on time.
 */
final class OffsetCalibrator {
    private static final int BEATS_PER_GROUP = 4;
    private static final int LEAD_IN_GROUPS = 1;
    private static final int MEASURED_GROUPS = 8;
    private static final int TOTAL_GROUPS = LEAD_IN_GROUPS + MEASURED_GROUPS;
    private static final int TOTAL_BEATS = TOTAL_GROUPS * BEATS_PER_GROUP;
    private static final long BEAT_MS = 500;

    private static final Color BG = new Color(18, 13, 20);
    private static final Color CARD_BG = new Color(30, 22, 34);
    private static final Color BORDER = new Color(72, 48, 66);
    private static final Color ACCENT = new Color(255, 45, 138);
    private static final Color TEXT_LIGHT = new Color(238, 226, 236);
    private static final Color TEXT_DIM = new Color(178, 150, 172);

    private OffsetCalibrator() {
    }

    /** The overlay's root component (add once to a {@code JLayeredPane}, initially invisible) plus
     *  the trigger that resets and shows it — call {@link #start()} fresh every time the player
     *  clicks "보정 도우미", the same way opening a new dialog used to. */
    static final class Overlay {
        final JComponent component;
        private final Runnable startAction;

        private Overlay(JComponent component, Runnable startAction) {
            this.component = component;
            this.startAction = startAction;
        }

        void start() {
            startAction.run();
        }
    }

    static Overlay build(RhythmSettings settings, LongConsumer onApply) {
        JLabel bigText = new JLabel("스페이스바로 시작", SwingConstants.CENTER);
        bigText.setForeground(TEXT_LIGHT);
        bigText.setFont(bigText.getFont().deriveFont(Font.BOLD, 40f));

        JLabel status = new JLabel("리듬에 맞춰 \"탭!\" 순간에 스페이스바를 눌러주세요", SwingConstants.CENTER);
        status.setForeground(TEXT_DIM);
        status.setFont(status.getFont().deriveFont(13f));

        JButton apply = new JButton("적용");
        stylePrimary(apply);
        JButton cancel = new JButton("취소");
        styleOutline(cancel);
        apply.setEnabled(false);

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.add(apply);
        buttons.add(cancel);

        JPanel card = new JPanel(new BorderLayout(10, 10));
        card.setBackground(CARD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1),
                BorderFactory.createEmptyBorder(24, 32, 18, 32)));
        card.setFocusable(true);
        card.add(bigText, BorderLayout.CENTER);
        card.add(status, BorderLayout.NORTH);
        card.add(buttons, BorderLayout.SOUTH);
        card.setPreferredSize(new Dimension(420, 260));

        Scrim scrim = new Scrim();
        scrim.setLayout(new GridBagLayout());
        scrim.add(card, new GridBagConstraints());
        scrim.setVisible(false);

        Clip countClip = buildClip(900, 16, 0.5);
        Clip tapClip = buildClip(1400, 20, 0.9);

        long[] tapBeatAt = new long[TOTAL_GROUPS];
        List<Long> deltas = new ArrayList<>();
        int[] beatIndex = {0};
        boolean[] running = {false};
        long[] medianResult = {settings.offsetMs()};

        Timer beatTimer = new Timer((int) BEAT_MS, null);
        beatTimer.setInitialDelay(0);
        beatTimer.addActionListener(e -> {
            int i = beatIndex[0]++;
            if (i >= TOTAL_BEATS) {
                beatTimer.stop();
                running[0] = false;
                long median = median(deltas);
                medianResult[0] = -median;
                if (deltas.isEmpty()) {
                    status.setText("박자를 못 받았습니다 — 다시 시도해보세요 (스페이스바)");
                    bigText.setText("측정 실패");
                    bigText.setForeground(TEXT_LIGHT);
                } else {
                    status.setText(deltas.size() + "번 입력, 평균 오차 " + median + "ms → 제안 오프셋 " + medianResult[0] + "ms");
                    bigText.setText(medianResult[0] + " ms");
                    bigText.setForeground(TEXT_LIGHT);
                    apply.setEnabled(true);
                }
                return;
            }
            int group = i / BEATS_PER_GROUP;
            int pos = i % BEATS_PER_GROUP;
            boolean isTap = pos == BEATS_PER_GROUP - 1;
            if (isTap) {
                tapBeatAt[group] = System.currentTimeMillis();
                playClick(tapClip);
                bigText.setText("탭!");
                bigText.setForeground(ACCENT);
            } else {
                playClick(countClip);
                bigText.setText(String.valueOf(pos + 1));
                bigText.setForeground(TEXT_LIGHT);
            }
        });

        Runnable resetAndShow = () -> {
            beatTimer.stop();
            running[0] = false;
            beatIndex[0] = 0;
            deltas.clear();
            java.util.Arrays.fill(tapBeatAt, 0L);
            apply.setEnabled(false);
            bigText.setForeground(TEXT_LIGHT);
            bigText.setText("스페이스바로 시작");
            status.setText("리듬에 맞춰 \"탭!\" 순간에 스페이스바를 눌러주세요");
            scrim.setVisible(true);
            card.requestFocusInWindow();
        };

        Runnable close = () -> {
            beatTimer.stop();
            running[0] = false;
            scrim.setVisible(false);
        };

        // Bound at WHEN_FOCUSED (not a raw KeyListener) so Swing's own, well-defined keybinding
        // priority applies: a WHEN_FOCUSED binding on the actual focus owner is resolved before any
        // WHEN_IN_FOCUSED_WINDOW binding elsewhere gets a look — in particular the Settings screen's
        // own ESC-closes-the-whole-screen binding on its root panel, which a raw KeyListener's
        // consume() would race against instead of reliably losing to.
        card.registerKeyboardAction(e -> close.run(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED);
        card.registerKeyboardAction(e -> {
            if (!running[0]) {
                running[0] = true;
                beatIndex[0] = 0;
                deltas.clear();
                apply.setEnabled(false);
                status.setText("박자에 맞춰 \"탭!\" 순간마다 스페이스바를 눌러주세요");
                beatTimer.start();
                return;
            }
            long now = System.currentTimeMillis();
            long best = Long.MAX_VALUE;
            int currentGroup = Math.min(beatIndex[0] / BEATS_PER_GROUP, TOTAL_GROUPS - 1);
            for (int g = LEAD_IN_GROUPS; g <= currentGroup; g++) {
                if (tapBeatAt[g] == 0) {
                    continue;
                }
                long delta = now - tapBeatAt[g];
                if (Math.abs(delta) < Math.abs(best) && Math.abs(delta) < BEAT_MS / 2) {
                    best = delta;
                }
            }
            if (best != Long.MAX_VALUE) {
                deltas.add(best);
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), JComponent.WHEN_FOCUSED);

        cancel.addActionListener(e -> close.run());
        apply.addActionListener(e -> {
            onApply.accept(medianResult[0]);
            close.run();
        });

        return new Overlay(scrim, resetAndShow);
    }

    private static long median(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(mid - 1) + sorted.get(mid)) / 2 : sorted.get(mid);
    }

    private static void playClick(Clip clip) {
        if (clip == null) {
            return;
        }
        clip.setFramePosition(0);
        clip.start();
    }

    private static Clip buildClip(double freqHz, int durationMs, double amplitude) {
        try {
            byte[] pcm = Tone.synth(freqHz, durationMs, amplitude);
            AudioFormat format = new AudioFormat(Tone.SAMPLE_RATE, 16, 1, true, false);
            Clip clip = AudioSystem.getClip();
            try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), format, pcm.length / 2)) {
                clip.open(in);
            }
            return clip;
        } catch (Exception e) {
            return null;
        }
    }

    private static void stylePrimary(JButton b) {
        b.setFocusPainted(false);
        b.setBackground(ACCENT);
        b.setForeground(Color.BLACK);
        b.setBorder(BorderFactory.createEmptyBorder(6, 18, 6, 18));
        b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        b.setOpaque(true);
    }

    private static void styleOutline(JButton b) {
        b.setFocusPainted(false);
        b.setBackground(CARD_BG);
        b.setForeground(TEXT_LIGHT);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(TEXT_DIM, 1),
                BorderFactory.createEmptyBorder(6, 14, 6, 14)));
        b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        b.setOpaque(true);
    }

    /** A full-bleed semi-transparent scrim — dims (but, since it sits above everything in its own
     *  {@code JLayeredPane} layer, also blocks clicks to) whatever is behind it while the wizard is
     *  up, exactly like a modal dialog's shade without actually being a separate window. */
    private static final class Scrim extends JPanel {
        Scrim() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(new Color(BG.getRed(), BG.getGreen(), BG.getBlue(), 190));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
