package djmax;

import com.group_finity.mascot.lumi.plugin.PluginContext;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.OverlayLayout;
import javax.swing.Scrollable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.event.ChangeEvent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/**
 * DJMAX Respect V-style settings screen: a flat dark row list under a big tab bar (DISPLAY / GAME
 * / SOUND / CONTROL), the focused row picked out with a pink border, instead of a generic
 * {@link javax.swing.JTabbedPane} of default Swing widgets. Painted with its own fixed palette —
 * same reasoning as {@link HubPanel} — so it reads as part of this game's own look rather than
 * whatever theme the host app happens to be in. One of {@link HubWindow}'s screens, not a dialog
 * of its own.
 * <p>
 * Palette matches Niah's own key art (magenta/hot-pink, purple, near-black); field names below
 * kept from the earlier gold/warm-gray DJMAX-style version to avoid renaming every call site —
 * only the RGB values changed. A pre-retheme source backup was taken before this change.
 * <p>
 * Every control here is staged, not live: moving a slider or flipping a toggle only updates what's
 * on screen. Nothing is written to {@link RhythmSettings} until the single 적용 button (or Enter)
 * is pressed, which commits every pending change at once. Two exceptions, both already an explicit
 * single action of their own rather than a toggle to leave half-set: the background-download
 * button (asking it to start downloading right now) and key rebinding (the button already shows
 * the new key the moment it's pressed — deferring that would just look broken). The
 * timing-calibration wizard and the "press a key now" capture box are brief modal helpers for one
 * input, not separate screens.
 */
final class SettingsScreen {
    private static final Color BG = new Color(18, 13, 20);
    private static final Color TAB_BAR_BG = new Color(24, 17, 26);
    private static final Color TAB_ACTIVE_BG = new Color(44, 28, 42);
    private static final Color TAB_UNDERLINE = new Color(255, 45, 138);
    private static final Color TAB_TEXT_ACTIVE = Color.WHITE;
    private static final Color TAB_TEXT_INACTIVE = new Color(160, 130, 155);
    private static final Color ROW_BG = new Color(42, 30, 46);
    private static final Color ROW_BORDER_FOCUS = new Color(255, 92, 170);
    private static final Color ROW_LABEL = new Color(215, 195, 210);
    private static final Color ROW_LABEL_FOCUS = new Color(255, 140, 195);
    private static final Color ROW_VALUE = Color.WHITE;
    private static final Color HEADER_TEXT = new Color(230, 220, 228);
    private static final Color HINT_TEXT = new Color(160, 135, 155);
    private static final Color ACCENT_GOLD = new Color(255, 45, 138); // vivid magenta (was gold)

    private SettingsScreen() {
    }

    /** @param owner the shared window, used only as the parent for the small modal helper dialogs.
     *  @param onApplied called right after every pending change is written to {@link RhythmSettings}
     *  — lets the caller refresh anything else on screen (namely the Hub's practice pad, and a live
     *  windowed/fullscreen switch) that cached settings-derived state, so 적용 actually shows up
     *  immediately instead of only the next time that other screen is rebuilt.
     *  @param onReset the 기본값 복원 button, after confirming — the caller writes every default and
     *  rebuilds this screen from scratch so the widgets show what was just restored.
     *  @param onExit the 전원 끄기 button — closes the whole rhythm-game window, after confirming.
     *  @param onClose called when the player is done — the caller swaps back to the Hub screen. */
    static JComponent build(Window owner, PluginContext ctx, RhythmSettings settings, Runnable onApplied,
                             Runnable onReset, Runnable onExit, Runnable onClose) {
        List<Runnable> pending = new ArrayList<>();

        // The offset field the wizard writes into lives inside buildOffsetRow, built after this —
        // so the overlay's onApply callback goes through a holder that gets filled in once that
        // field exists, rather than the overlay needing the field directly.
        LongConsumer[] offsetApplyHolder = new LongConsumer[1];
        OffsetCalibrator.Overlay calibrationOverlay = OffsetCalibrator.build(settings, ms -> {
            if (offsetApplyHolder[0] != null) {
                offsetApplyHolder[0].accept(ms);
            }
        });

        String[] tabKeys = {"display", "game", "sound", "controls"};
        String[] tabLabels = {
                Lang.t(settings, "settings.tab.display"),
                Lang.t(settings, "settings.tab.game"),
                Lang.t(settings, "settings.tab.sound"),
                Lang.t(settings, "settings.tab.controls")};

        JPanel cardHost = new JPanel(new CardLayout());
        cardHost.setOpaque(false);
        cardHost.add(scrollable(buildDisplayTab(settings, pending)), tabKeys[0]);
        cardHost.add(scrollable(buildGameTab(ctx, settings, pending, calibrationOverlay, offsetApplyHolder)), tabKeys[1]);
        cardHost.add(scrollable(buildSoundTab(settings, pending)), tabKeys[2]);
        cardHost.add(scrollable(buildControlsTab(owner, settings)), tabKeys[3]);
        CardLayout cardLayout = (CardLayout) cardHost.getLayout();

        JButton[] tabButtons = new JButton[tabKeys.length];
        JPanel tabBar = new JPanel(new GridLayout(1, tabKeys.length, 2, 0));
        tabBar.setBackground(TAB_BAR_BG);
        for (int i = 0; i < tabKeys.length; i++) {
            int idx = i;
            JButton b = new JButton(tabLabels[i].toUpperCase(java.util.Locale.ROOT));
            tabButtons[i] = b;
            b.setFocusPainted(false);
            b.setOpaque(true);
            b.setFont(b.getFont().deriveFont(Font.BOLD, 15f));
            b.setBorder(BorderFactory.createEmptyBorder(12, 8, 9, 8));
            b.addActionListener(e -> {
                cardLayout.show(cardHost, tabKeys[idx]);
                setActiveTab(tabButtons, idx);
            });
            tabBar.add(b);
        }
        setActiveTab(tabButtons, 0);

        JPanel cardWrap = new JPanel(new BorderLayout());
        cardWrap.setBackground(BG);
        cardWrap.setBorder(BorderFactory.createEmptyBorder(20, 24, 8, 24));
        cardWrap.add(cardHost, BorderLayout.CENTER);

        JLabel appliedFeedback = new JLabel(" ");
        appliedFeedback.setForeground(ACCENT_GOLD);
        appliedFeedback.setFont(appliedFeedback.getFont().deriveFont(Font.BOLD, 12f));
        Timer feedbackClear = new Timer(1500, e -> appliedFeedback.setText(" "));
        feedbackClear.setRepeats(false);

        Runnable[] applyHolder = new Runnable[1];
        JButton apply = new JButton(Lang.t(settings, "settings.apply"));
        stylePrimaryButton(apply);
        JButton close = new JButton(Lang.t(settings, "settings.close"));
        styleOutlineButton(close);
        close.addActionListener(e -> onClose.run());
        applyHolder[0] = () -> {
            // Each control's write is independent — one throwing (a bad combo index, a device that
            // vanished, whatever) must not stop every pending change *after* it in the list from
            // applying too. Without this, a single failure could look like "nothing I changed took
            // effect" even though most of it actually did.
            for (Runnable action : pending) {
                try {
                    action.run();
                } catch (Exception ignored) {
                }
            }
            // Same reasoning for refreshing the practice pad: it must never suppress the 적용됨
            // confirmation below, which is the one visible proof anything happened at all.
            if (onApplied != null) {
                try {
                    onApplied.run();
                } catch (Exception ignored) {
                }
            }
            appliedFeedback.setText(Lang.t(settings, "settings.applied"));
            feedbackClear.restart();
        };
        apply.addActionListener(e -> applyHolder[0].run());

        JLabel hint = new JLabel(Lang.t(settings, "settings.hint"));
        hint.setForeground(HINT_TEXT);
        hint.setFont(hint.getFont().deriveFont(11f));

        JButton resetButton = new JButton(Lang.t(settings, "settings.restoreDefaults"));
        styleOutlineButton(resetButton, HINT_TEXT);
        resetButton.addActionListener(e -> {
            int confirmed = JOptionPane.showConfirmDialog(owner, Lang.t(settings, "settings.restoreDefaults.confirm"),
                    Lang.t(settings, "settings.restoreDefaults"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirmed == JOptionPane.YES_OPTION && onReset != null) {
                onReset.run();
            }
        });

        JButton exitButton = new JButton(Lang.t(settings, "settings.exitGame"));
        styleOutlineButton(exitButton, new Color(255, 92, 122));
        exitButton.addActionListener(e -> {
            int confirmed = JOptionPane.showConfirmDialog(owner, Lang.t(settings, "settings.exitGame.confirm"),
                    Lang.t(settings, "settings.exitGame"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirmed == JOptionPane.YES_OPTION && onExit != null) {
                onExit.run();
            }
        });

        JPanel bottomLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bottomLeft.setOpaque(false);
        bottomLeft.add(resetButton);
        bottomLeft.add(exitButton);
        bottomLeft.add(hint);

        JPanel bottomButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        bottomButtons.setOpaque(false);
        bottomButtons.add(appliedFeedback);
        bottomButtons.add(apply);
        bottomButtons.add(close);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(BG);
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 24, 14, 16));
        bottom.add(bottomLeft, BorderLayout.WEST);
        bottom.add(bottomButtons, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(tabBar, BorderLayout.NORTH);
        root.add(cardWrap, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        root.setPreferredSize(new Dimension(720, 560));

        // Esc/Enter work from anywhere on this screen, matching the DJMAX-style hint bar, without
        // stealing Enter from a text field the player is still typing into.
        root.registerKeyboardAction(e -> onClose.run(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        root.registerKeyboardAction(e -> applyHolder[0].run(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        // The calibration overlay sits on top of the ordinary settings UI — a "window within the
        // window" (a dimmed scrim plus a centered card) rather than a second real window.
        // OverlayLayout (not a JLayeredPane with a null layout and hand-tracked bounds — that
        // needed a resize listener to keep root's size in sync with its container's, and any gap
        // between the two showed up as this whole screen shrinking to a fixed size instead of
        // tracking the real window) stacks both children full-size and participates in the normal
        // Swing layout/validate cycle exactly like every other screen here, so it always tracks the
        // window's actual size the same way. Z-order (both paint and mouse dispatch) puts the
        // first-added child frontmost, so the overlay is added before root.
        JPanel stack = new JPanel();
        stack.setOpaque(false);
        root.setAlignmentX(0f);
        root.setAlignmentY(0f);
        calibrationOverlay.component.setAlignmentX(0f);
        calibrationOverlay.component.setAlignmentY(0f);
        stack.setLayout(new OverlayLayout(stack));
        stack.add(calibrationOverlay.component);
        stack.add(root);
        return stack;
    }

    private static void setActiveTab(JButton[] buttons, int activeIdx) {
        for (int i = 0; i < buttons.length; i++) {
            boolean active = i == activeIdx;
            buttons[i].setBackground(active ? TAB_ACTIVE_BG : TAB_BAR_BG);
            buttons[i].setForeground(active ? TAB_TEXT_ACTIVE : TAB_TEXT_INACTIVE);
            buttons[i].setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 3, 0, active ? TAB_UNDERLINE : TAB_BAR_BG),
                    BorderFactory.createEmptyBorder(12, 8, 6, 8)));
        }
    }

    /** Wraps a tab's row list so it scrolls (mouse wheel or the scrollbar) instead of just getting
     *  clipped when it's taller than the window — the Game tab in particular (language, countdown,
     *  judgment window, offset, auto-pause, and the whole background section) easily runs past the
     *  visible height, and with the window's size now fixed (see {@link HubWindow}) there is no
     *  other way to reach whatever falls below the fold. Wrapped in {@link ScrollableColumn} so the
     *  rows stretch to the full viewport width instead of a plain JPanel's default of shrinking to
     *  its own preferred width and leaving a dead strip on the right. */
    private static JScrollPane scrollable(JPanel tabContent) {
        JScrollPane scroll = new JScrollPane(new ScrollableColumn(tabContent),
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(28);
        scroll.getVerticalScrollBar().setBlockIncrement(160);
        ThemedScrollBar.style(scroll.getVerticalScrollBar(), BG, ROW_BG, ACCENT_GOLD);
        return scroll;
    }

    /** A vertical-only scrollable: tracks the viewport's width (rows fill it edge to edge) but not
     *  its height (so taller-than-viewport content actually scrolls instead of being squashed). */
    private static final class ScrollableColumn extends JPanel implements Scrollable {
        ScrollableColumn(JPanel content) {
            super(new BorderLayout());
            setOpaque(false);
            add(content, BorderLayout.CENTER);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 28;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 160;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    // ── display ──────────────────────────────────────────────────────────────

    private static JPanel buildDisplayTab(RhythmSettings settings, List<Runnable> pending) {
        JComboBox<String> modeCombo = styledCombo(
                Lang.t(settings, "settings.windowed"), Lang.t(settings, "settings.fullscreen"));
        modeCombo.setSelectedIndex(settings.displayMode() == RhythmSettings.DisplayMode.FULLSCREEN ? 1 : 0);
        pending.add(() -> settings.setDisplayMode(
                modeCombo.getSelectedIndex() == 1 ? RhythmSettings.DisplayMode.FULLSCREEN : RhythmSettings.DisplayMode.WINDOWED));

        String[] anchorLabels = {
                Lang.t(settings, "settings.gamePosition.left"),
                Lang.t(settings, "settings.gamePosition.center"),
                Lang.t(settings, "settings.gamePosition.right")};
        RhythmSettings.HorizontalAnchor[] anchorValues = RhythmSettings.HorizontalAnchor.values();
        JComboBox<String> anchorCombo = styledCombo(anchorLabels);
        anchorCombo.setSelectedIndex(settings.gameHorizontalAnchor().ordinal());
        pending.add(() -> settings.setGameHorizontalAnchor(anchorValues[anchorCombo.getSelectedIndex()]));

        String[] listStyleLabels = {
                Lang.t(settings, "settings.songListStyle.list"),
                Lang.t(settings, "settings.songListStyle.carousel")};
        RhythmSettings.SongListStyle[] listStyleValues = RhythmSettings.SongListStyle.values();
        JComboBox<String> listStyleCombo = styledCombo(listStyleLabels);
        listStyleCombo.setSelectedIndex(settings.songListStyle().ordinal());
        pending.add(() -> settings.setSongListStyle(listStyleValues[listStyleCombo.getSelectedIndex()]));

        String[] noteStyleLabels = {
                Lang.t(settings, "settings.noteStyle.ring"),
                Lang.t(settings, "settings.noteStyle.classic")};
        RhythmSettings.NoteStyle[] noteStyleValues = RhythmSettings.NoteStyle.values();
        JComboBox<String> noteStyleCombo = styledCombo(noteStyleLabels);
        noteStyleCombo.setSelectedIndex(settings.noteStyle().ordinal());
        pending.add(() -> settings.setNoteStyle(noteStyleValues[noteStyleCombo.getSelectedIndex()]));

        String[] hitEffectLabels = {
                Lang.t(settings, "settings.hitEffectStyle.ripple"),
                Lang.t(settings, "settings.hitEffectStyle.burst")};
        RhythmSettings.HitEffectStyle[] hitEffectValues = RhythmSettings.HitEffectStyle.values();
        JComboBox<String> hitEffectCombo = styledCombo(hitEffectLabels);
        hitEffectCombo.setSelectedIndex(settings.hitEffectStyle().ordinal());
        pending.add(() -> settings.setHitEffectStyle(hitEffectValues[hitEffectCombo.getSelectedIndex()]));

        String[] laneLayoutLabels = {
                Lang.t(settings, "settings.laneLayout.flat"),
                Lang.t(settings, "settings.laneLayout.perspective")};
        RhythmSettings.LaneLayout[] laneLayoutValues = RhythmSettings.LaneLayout.values();
        JComboBox<String> laneLayoutCombo = styledCombo(laneLayoutLabels);
        laneLayoutCombo.setSelectedIndex(settings.laneLayout().ordinal());
        pending.add(() -> settings.setLaneLayout(laneLayoutValues[laneLayoutCombo.getSelectedIndex()]));

        JComboBox<String> aaCombo = onOffCombo(settings, settings.antiAliasing());
        pending.add(() -> settings.setAntiAliasing(isOn(aaCombo)));

        JComboBox<String> sideInfoCombo = onOffCombo(settings, settings.sideInfoPanelEnabled());
        pending.add(() -> settings.setSideInfoPanelEnabled(isOn(sideInfoCombo)));

        JPanel sideInfoOpacityValue = percentSlider(settings.sideInfoPanelOpacityPercent(), 0, 100,
                settings::setSideInfoPanelOpacityPercent, pending);

        JComboBox<String> perfectFlashCombo = onOffCombo(settings, settings.perfectFlashEnabled());
        pending.add(() -> settings.setPerfectFlashEnabled(isOn(perfectFlashCombo)));

        JComboBox<String> fpsCombo = styledCombo("30", "60", "120", "144");
        fpsCombo.setSelectedItem(String.valueOf(settings.fpsLimit()));
        pending.add(() -> settings.setFpsLimit(Integer.parseInt((String) fpsCombo.getSelectedItem())));

        String[] cvLabels = {
                Lang.t(settings, "settings.colorVision.normal"),
                Lang.t(settings, "settings.colorVision.protanopia"),
                Lang.t(settings, "settings.colorVision.deuteranopia"),
                Lang.t(settings, "settings.colorVision.tritanopia")};
        ColorVision[] cvValues = ColorVision.values();
        JComboBox<String> cvCombo = styledCombo(cvLabels);
        cvCombo.setSelectedIndex(settings.colorVision().ordinal());
        pending.add(() -> settings.setColorVision(cvValues[cvCombo.getSelectedIndex()]));

        JPanel tab = new JPanel();
        tab.setOpaque(false);
        tab.setLayout(new BoxLayout(tab, BoxLayout.Y_AXIS));
        tab.add(tabHeader(Lang.t(settings, "settings.tab.display")));
        tab.add(row(Lang.t(settings, "settings.displayMode"), modeCombo));
        tab.add(row(Lang.t(settings, "settings.gamePosition"), anchorCombo));
        tab.add(row(Lang.t(settings, "settings.songListStyle"), listStyleCombo));
        tab.add(row(Lang.t(settings, "settings.noteStyle"), noteStyleCombo));
        tab.add(row(Lang.t(settings, "settings.hitEffectStyle"), hitEffectCombo));
        tab.add(row(Lang.t(settings, "settings.laneLayout"), laneLayoutCombo));
        tab.add(row(Lang.t(settings, "settings.antiAliasing"), aaCombo));
        tab.add(row(Lang.t(settings, "settings.sideInfoPanel"), sideInfoCombo));
        tab.add(row(Lang.t(settings, "settings.sideInfoPanel.opacity"), sideInfoOpacityValue));
        tab.add(row(Lang.t(settings, "settings.perfectFlash"), perfectFlashCombo));
        tab.add(row(Lang.t(settings, "settings.fpsLimit"), fpsCombo));
        tab.add(row(Lang.t(settings, "settings.colorVision"), cvCombo));
        return tab;
    }

    // ── game ─────────────────────────────────────────────────────────────────

    private static JPanel buildGameTab(PluginContext ctx, RhythmSettings settings, List<Runnable> pending,
                                        OffsetCalibrator.Overlay calibrationOverlay, LongConsumer[] offsetApplyHolder) {
        // Index matches Lang's own enum order (KO, EN, JA, ZH) so the combo's selection maps
        // straight to Lang.values()[index] — no per-language special-casing needed here.
        JComboBox<String> langCombo = styledCombo("한국어", "English", "日本語", "中文");
        langCombo.setSelectedIndex(settings.language().ordinal());
        pending.add(() -> settings.setLanguage(Lang.values()[langCombo.getSelectedIndex()]));

        JComboBox<String> countdownCombo = styledCombo("0", "3", "5");
        countdownCombo.setSelectedItem(String.valueOf(settings.countdownSeconds()));
        pending.add(() -> settings.setCountdownSeconds(Integer.parseInt((String) countdownCombo.getSelectedItem())));

        JPanel judgmentValue = percentSlider(settings.judgmentWindowScalePercent(), 50, 150,
                settings::setJudgmentWindowScalePercent, pending);

        JPanel offsetValue = buildOffsetRow(settings, pending, calibrationOverlay, offsetApplyHolder);

        JComboBox<String> autoPauseCombo = onOffCombo(settings, settings.autoPauseOnFocusLoss());
        pending.add(() -> settings.setAutoPauseOnFocusLoss(isOn(autoPauseCombo)));

        JComboBox<String> bgEnabledCombo = onOffCombo(settings, settings.backgroundEnabled());
        pending.add(() -> settings.setBackgroundEnabled(isOn(bgEnabledCombo)));

        JComboBox<String> bgThumbnailCombo = onOffCombo(settings, settings.backgroundThumbnailEnabled());
        pending.add(() -> settings.setBackgroundThumbnailEnabled(isOn(bgThumbnailCombo)));

        JPanel bgUrlValue = buildBackgroundUrlRow(ctx, settings);

        String[] aspectValues = {BackgroundAnimator.ASPECT_FIT, BackgroundAnimator.ASPECT_FILL, BackgroundAnimator.ASPECT_STRETCH};
        String[] aspectLabels = {
                Lang.t(settings, "settings.background.aspect.fit"),
                Lang.t(settings, "settings.background.aspect.fill"),
                Lang.t(settings, "settings.background.aspect.stretch")};
        JComboBox<String> aspectCombo = styledCombo(aspectLabels);
        int currentAspect = java.util.Arrays.asList(aspectValues).indexOf(settings.backgroundAspectMode());
        aspectCombo.setSelectedIndex(Math.max(0, currentAspect));
        pending.add(() -> settings.setBackgroundAspectMode(aspectValues[aspectCombo.getSelectedIndex()]));

        JPanel brightnessValue = percentSlider(settings.backgroundBrightnessPercent(), 0, 100,
                settings::setBackgroundBrightnessPercent, pending);

        JPanel tab = new JPanel();
        tab.setOpaque(false);
        tab.setLayout(new BoxLayout(tab, BoxLayout.Y_AXIS));
        tab.add(tabHeader(Lang.t(settings, "settings.tab.game")));
        tab.add(row(Lang.t(settings, "settings.language"), langCombo));
        tab.add(row(Lang.t(settings, "settings.countdown"), countdownCombo));
        tab.add(row(Lang.t(settings, "settings.judgmentWindow"), judgmentValue));
        tab.add(row(Lang.t(settings, "settings.noteTiming"), offsetValue));
        tab.add(row(Lang.t(settings, "settings.autoPauseOnFocusLoss"), autoPauseCombo));
        tab.add(row(Lang.t(settings, "settings.background.enabled"), bgEnabledCombo));
        tab.add(row(Lang.t(settings, "settings.background.thumbnail"), bgThumbnailCombo));
        tab.add(row(Lang.t(settings, "settings.background"), bgUrlValue));
        tab.add(row(Lang.t(settings, "settings.background.aspect"), aspectCombo));
        tab.add(row(Lang.t(settings, "settings.background.brightness"), brightnessValue));
        return tab;
    }

    private static JPanel buildOffsetRow(RhythmSettings settings, List<Runnable> pending,
                                          OffsetCalibrator.Overlay calibrationOverlay, LongConsumer[] offsetApplyHolder) {
        JPanel value = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        value.setOpaque(false);
        JTextField field = new JTextField(String.valueOf(settings.offsetMs()), 5);
        styleTextField(field);
        JLabel unit = new JLabel("ms");
        unit.setForeground(ROW_VALUE);
        JButton wizard = new JButton(Lang.t(settings, "settings.calibrate"));
        styleOutlineButton(wizard);
        // The wizard only fills in the field — it doesn't write to settings itself, so its result
        // is staged like everything else here and only takes effect once 적용 is pressed.
        offsetApplyHolder[0] = ms -> field.setText(String.valueOf(ms));
        wizard.addActionListener(e -> calibrationOverlay.start());
        value.add(field);
        value.add(unit);
        value.add(wizard);
        pending.add(() -> {
            try {
                settings.setOffsetMs(Long.parseLong(field.getText().strip()));
            } catch (NumberFormatException ignored) {
                field.setText(String.valueOf(settings.offsetMs()));
            }
        });
        return value;
    }

    private static JPanel buildBackgroundUrlRow(PluginContext ctx, RhythmSettings settings) {
        JPanel value = new JPanel(new BorderLayout(6, 0));
        value.setOpaque(false);
        JTextField urlField = new JTextField(settings.backgroundUrl());
        styleTextField(urlField);
        JButton download = new JButton(Lang.t(settings, "settings.background.download"));
        styleOutlineButton(download);
        JLabel status = new JLabel(" ");
        status.setForeground(HINT_TEXT);
        status.setFont(status.getFont().deriveFont(11f));
        // Starting a download is its own explicit one-shot action, not a preference to stage —
        // pressing it does the thing right now, same as the calibration wizard or key rebinding.
        download.addActionListener(e -> {
            String url = urlField.getText().strip();
            if (url.isEmpty()) {
                return;
            }
            settings.setBackgroundUrl(url);
            download.setEnabled(false);
            status.setText(Lang.t(settings, "settings.background.extracting"));
            File modTools = ToolLocator.modTools(settings.dataDir());
            Path framesDir = settings.dataDir().resolve("background").resolve("frames");
            Thread worker = new Thread(() -> {
                try {
                    if (modTools != null) {
                        try {
                            ToolInstaller.ensureTools(modTools.toPath(),
                                    message -> ctx.onEdt(() -> status.setText(message)));
                        } catch (Exception ex) {
                            ctx.log().warning("도구 자동 설치 실패 (수동 설치 안내로 대체): " + ex.getMessage());
                        }
                    }
                    BackgroundAnimator.download(url, framesDir, modTools, ctx.log());
                    ctx.onEdt(() -> {
                        status.setText(Lang.t(settings, "settings.background.done"));
                        download.setEnabled(true);
                    });
                } catch (Exception ex) {
                    ctx.log().warning("배경 준비 실패: " + ex.getMessage());
                    ctx.onEdt(() -> {
                        status.setText(Lang.t(settings, "settings.background.failed") + ": " + ex.getMessage());
                        download.setEnabled(true);
                    });
                }
            }, "niah-djmax-background-download");
            worker.setDaemon(true);
            worker.start();
        });
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setOpaque(false);
        top.add(urlField, BorderLayout.CENTER);
        top.add(download, BorderLayout.EAST);
        value.add(top, BorderLayout.CENTER);
        value.add(status, BorderLayout.SOUTH);
        return value;
    }

    // ── sound ────────────────────────────────────────────────────────────────

    private static JPanel buildSoundTab(RhythmSettings settings, List<Runnable> pending) {
        List<String> deviceNames = AudioDevices.listNames();
        String[] items = new String[deviceNames.size() + 1];
        items[0] = Lang.t(settings, "settings.outputDevice.default");
        for (int i = 0; i < deviceNames.size(); i++) {
            items[i + 1] = deviceNames.get(i);
        }
        JComboBox<String> deviceCombo = styledCombo(items);
        String current = settings.outputMixerName();
        deviceCombo.setSelectedItem(current.isBlank() ? items[0] : current);
        pending.add(() -> {
            int idx = deviceCombo.getSelectedIndex();
            settings.setOutputMixerName(idx <= 0 ? "" : deviceNames.get(idx - 1));
        });

        JPanel musicValue = percentSlider((int) Math.round(settings.musicVolume() * 100), 0, 100,
                v -> settings.setMusicVolume(v / 100.0), pending);
        JPanel sfxValue = percentSlider((int) Math.round(settings.sfxVolume() * 100), 0, 100,
                v -> settings.setSfxVolume(v / 100.0), pending);
        JPanel voiceValue = percentSlider((int) Math.round(settings.voiceVolume() * 100), 0, 100,
                v -> settings.setVoiceVolume(v / 100.0), pending);

        JComboBox<String> voiceEnabledCombo = onOffCombo(settings, settings.voiceEnabled());
        pending.add(() -> settings.setVoiceEnabled(isOn(voiceEnabledCombo)));

        JComboBox<String> continueUnfocusedCombo = onOffCombo(settings, settings.continueSoundWhenUnfocused());
        pending.add(() -> settings.setContinueSoundWhenUnfocused(isOn(continueUnfocusedCombo)));

        JPanel tab = new JPanel();
        tab.setOpaque(false);
        tab.setLayout(new BoxLayout(tab, BoxLayout.Y_AXIS));
        tab.add(tabHeader(Lang.t(settings, "settings.tab.sound")));
        tab.add(row(Lang.t(settings, "settings.outputDevice"), deviceCombo));
        tab.add(row(Lang.t(settings, "settings.musicVolume"), musicValue));
        tab.add(row(Lang.t(settings, "settings.sfxVolume"), sfxValue));
        tab.add(row(Lang.t(settings, "settings.voiceEnabled"), voiceEnabledCombo));
        tab.add(row(Lang.t(settings, "settings.voiceVolume"), voiceValue));
        tab.add(row(Lang.t(settings, "settings.continueSoundWhenUnfocused"), continueUnfocusedCombo));
        return tab;
    }

    // ── controls ─────────────────────────────────────────────────────────────

    private static JPanel buildControlsTab(Window owner, RhythmSettings settings) {
        // A live lane preview — the same colors/layout as the real game — so rebinding a key shows
        // exactly which lane it is, instead of a bare row of buttons with no game context. Key
        // rebinding itself stays immediate: the button already shows the freshly-pressed key the
        // instant it's captured, so staging it behind 적용 would just look like it didn't work.
        LanePreviewPanel preview = new LanePreviewPanel(settings);

        JPanel keysValue = new JPanel(new GridLayout(1, RhythmSettings.LANES, 6, 0));
        keysValue.setOpaque(false);
        int[] keys = settings.laneKeys();
        for (int lane = 0; lane < RhythmSettings.LANES; lane++) {
            JButton button = new JButton(KeyLabels.of(keys[lane]));
            styleOutlineButton(button);
            int laneFinal = lane;
            button.addActionListener(e -> rebindKey(owner, settings, laneFinal, button, preview));
            keysValue.add(button);
        }

        JButton sideKeyButton = new JButton(KeyLabels.of(settings.sideKey()));
        styleOutlineButton(sideKeyButton);
        sideKeyButton.addActionListener(e -> rebindSideKey(owner, settings, sideKeyButton));

        JPanel tab = new JPanel();
        tab.setOpaque(false);
        tab.setLayout(new BoxLayout(tab, BoxLayout.Y_AXIS));
        tab.add(tabHeader(Lang.t(settings, "settings.tab.controls")));
        tab.add(row(Lang.t(settings, "settings.keys"), keysValue));
        tab.add(row(Lang.t(settings, "settings.keys.side"), sideKeyButton));
        JPanel previewWrap = new JPanel(new BorderLayout());
        previewWrap.setOpaque(false);
        previewWrap.setBorder(BorderFactory.createEmptyBorder(14, 2, 0, 2));
        previewWrap.add(preview, BorderLayout.CENTER);
        tab.add(previewWrap);
        return tab;
    }

    private static void rebindKey(Window owner, RhythmSettings settings, int lane, JButton button, LanePreviewPanel preview) {
        String original = button.getText();
        button.setText(Lang.t(settings, "settings.keys.pressNew"));
        button.setEnabled(false);
        preview.setHighlightLane(lane);

        JDialog capture = new JDialog(owner, "", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        capture.setUndecorated(true);
        capture.setSize(1, 1);
        capture.setLocationRelativeTo(owner);
        capture.getRootPane().setFocusable(true);
        capture.getRootPane().addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (isDuplicateKey(settings, lane, code)) {
                    button.setText(original);
                } else {
                    settings.setLaneKey(lane, code);
                    button.setText(KeyLabels.of(code));
                }
                button.setEnabled(true);
                preview.setHighlightLane(-1);
                capture.dispose();
            }
        });
        capture.setVisible(true);
        javax.swing.SwingUtilities.invokeLater(() -> capture.getRootPane().requestFocusInWindow());
    }

    private static boolean isDuplicateKey(RhythmSettings settings, int lane, int code) {
        int[] keys = settings.laneKeys();
        for (int i = 0; i < keys.length; i++) {
            if (i != lane && keys[i] == code) {
                return true;
            }
        }
        return code == settings.sideKey();
    }

    /** The side-track note's own dedicated key — same capture dialog as a lane key, just without a
     *  lane to highlight in the preview (it isn't a lane key, so no {@link LanePreviewPanel} row
     *  lights up for it). */
    private static void rebindSideKey(Window owner, RhythmSettings settings, JButton button) {
        String original = button.getText();
        button.setText(Lang.t(settings, "settings.keys.pressNew"));
        button.setEnabled(false);

        JDialog capture = new JDialog(owner, "", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        capture.setUndecorated(true);
        capture.setSize(1, 1);
        capture.setLocationRelativeTo(owner);
        capture.getRootPane().setFocusable(true);
        capture.getRootPane().addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (isDuplicateSideKey(settings, code)) {
                    button.setText(original);
                } else {
                    settings.setSideKey(code);
                    button.setText(KeyLabels.of(code));
                }
                button.setEnabled(true);
                capture.dispose();
            }
        });
        capture.setVisible(true);
        javax.swing.SwingUtilities.invokeLater(() -> capture.getRootPane().requestFocusInWindow());
    }

    private static boolean isDuplicateSideKey(RhythmSettings settings, int code) {
        for (int laneKey : settings.laneKeys()) {
            if (laneKey == code) {
                return true;
            }
        }
        return false;
    }

    // ── row / tab-bar building blocks ────────────────────────────────────────

    private static JLabel tabHeader(String tabLabel) {
        JLabel header = new JLabel(tabLabel + " " + "SETTING");
        header.setForeground(HEADER_TEXT);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 22f));
        header.setBorder(BorderFactory.createEmptyBorder(0, 2, 14, 0));
        header.setHorizontalAlignment(SwingConstants.CENTER);
        header.setAlignmentX(Component.CENTER_ALIGNMENT);
        // A JLabel's maximum size defaults to its own preferred (text) size, which is narrower
        // than most tabs' widest row — so however each tab's rows happen to size the BoxLayout
        // column, the header only ever centers within its own tight text bounds, not the column's
        // actual width. Explicitly stretching it removes that dependency on what else is in the
        // tab, which is why Display (whose rows all happen to size out close to the header's own
        // width already) looked fine while Game/Sound/Controls (with a wider row or two) didn't.
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
        return header;
    }

    /** One DJMAX-style settings row: a label on the left, the value control filling the rest, on a
     *  flat warm-gray card that lights up gold when the control inside it has keyboard focus. */
    private static JPanel row(String label, JComponent control) {
        JPanel row = new JPanel(new BorderLayout(20, 0));
        row.setBackground(ROW_BG);
        row.setBorder(rowBorder(false));
        JLabel labelComp = new JLabel(label);
        labelComp.setForeground(ROW_LABEL);
        labelComp.setFont(labelComp.getFont().deriveFont(Font.BOLD, 14f));
        row.add(labelComp, BorderLayout.WEST);
        row.add(control, BorderLayout.CENTER);

        FocusListener highlight = new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                row.setBorder(rowBorder(true));
                labelComp.setForeground(ROW_LABEL_FOCUS);
            }

            @Override
            public void focusLost(FocusEvent e) {
                row.setBorder(rowBorder(false));
                labelComp.setForeground(ROW_LABEL);
            }
        };
        addFocusListenerDeep(control, highlight);

        JPanel spaced = new JPanel(new BorderLayout());
        spaced.setOpaque(false);
        spaced.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        spaced.add(row, BorderLayout.CENTER);
        return spaced;
    }

    /** Same line-border trick as a focus ring, just always gold and never toggled off, drawn
     *  around a slider/text row's value — used where a whole composite control acts as one row. */
    private static Border rowBorder(boolean focused) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(focused ? ROW_BORDER_FOCUS : ROW_BG, 2),
                BorderFactory.createEmptyBorder(9, 16, 9, 16));
    }

    private static void addFocusListenerDeep(Component c, FocusListener fl) {
        if (c instanceof JComboBox<?> || c instanceof JSlider || c instanceof JTextField || c instanceof AbstractButton) {
            c.addFocusListener(fl);
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                addFocusListenerDeep(child, fl);
            }
        }
    }

    /** A slider whose value is only staged: dragging it updates the on-screen "N%" label right
     *  away, but {@code onChange} (the actual {@link RhythmSettings} write) only runs when the
     *  caller's {@code pending} list is drained by the 적용 button. */
    private static JPanel percentSlider(int initial, int min, int max, IntConsumer onChange, List<Runnable> pending) {
        JPanel value = new JPanel(new BorderLayout(10, 0));
        value.setOpaque(false);
        JSlider slider = new JSlider(min, max, initial);
        slider.setOpaque(false);
        slider.setForeground(ROW_VALUE);
        JLabel percent = new JLabel(initial + "%");
        percent.setForeground(ROW_VALUE);
        percent.setFont(percent.getFont().deriveFont(Font.BOLD, 13f));
        percent.setPreferredSize(new Dimension(44, 20));
        percent.setHorizontalAlignment(SwingConstants.RIGHT);
        slider.addChangeListener((ChangeEvent e) -> percent.setText(slider.getValue() + "%"));
        value.add(slider, BorderLayout.CENTER);
        value.add(percent, BorderLayout.EAST);
        pending.add(() -> onChange.accept(slider.getValue()));
        return value;
    }

    private static JComboBox<String> onOffCombo(RhythmSettings settings, boolean initial) {
        JComboBox<String> combo = styledCombo(Lang.t(settings, "settings.on"), Lang.t(settings, "settings.off"));
        combo.setSelectedIndex(initial ? 0 : 1);
        return combo;
    }

    private static boolean isOn(JComboBox<String> combo) {
        return combo.getSelectedIndex() == 0;
    }

    private static JComboBox<String> styledCombo(String... items) {
        JComboBox<String> combo = new JComboBox<>(items);
        combo.setBackground(ROW_BG);
        combo.setForeground(ROW_VALUE);
        combo.setFocusable(true);
        combo.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        // Gives UiScale.rescale something real to work from: it walks the persistent component
        // tree rescaling whatever font each JComponent already has, and the combo box itself sits
        // in that tree (unlike the renderer below), so this is what makes the box's own
        // preferred/dropdown-row size actually grow with the window, not just the text drawn in it.
        combo.setFont(combo.getFont().deriveFont(Font.BOLD, 13f));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object v, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, v, index, isSelected, cellHasFocus);
                l.setHorizontalAlignment(SwingConstants.CENTER);
                // UiScale.f(13f), not a fixed 13f: this JLabel is a fresh, throwaway instance built
                // by Swing on every paint/dropdown-open, never a lasting part of the component tree,
                // so UiScale.rescale's tree walk can never reach it — reading the live scale factor
                // here instead is what actually keeps the dropdown/closed-box text from staying
                // pinned at a tiny fixed size no matter how big the window gets ("글자 크기가 너무
                // 작습니다... 화면 비율에 따라 크고 작아지게").
                l.setFont(l.getFont().deriveFont(Font.BOLD, UiScale.f(13f)));
                if (!isSelected) {
                    l.setBackground(ROW_BG);
                    l.setForeground(ROW_VALUE);
                }
                return l;
            }
        });
        return combo;
    }

    private static void styleTextField(JTextField f) {
        f.setBackground(new Color(42, 30, 42));
        f.setForeground(ROW_VALUE);
        f.setCaretColor(ROW_VALUE);
        f.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
    }

    private static void styleOutlineButton(JButton b) {
        styleOutlineButton(b, ACCENT_GOLD);
    }

    private static void styleOutlineButton(JButton b, Color accent) {
        b.setFocusPainted(false);
        b.setBackground(new Color(42, 30, 42));
        b.setForeground(accent);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accent, 1),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        b.setFont(b.getFont().deriveFont(Font.BOLD, 12f));
        b.setOpaque(true);
    }

    private static void stylePrimaryButton(JButton b) {
        b.setFocusPainted(false);
        b.setBackground(ACCENT_GOLD);
        b.setForeground(Color.BLACK);
        b.setBorder(BorderFactory.createEmptyBorder(6, 20, 6, 20));
        b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        b.setOpaque(true);
    }
}
