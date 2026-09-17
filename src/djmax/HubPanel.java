package djmax;

import com.group_finity.mascot.lumi.plugin.PluginContext;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Song library, all on one screen — the "니아 리듬게임" hub. Painted with a fixed dark palette
 * (not {@link com.group_finity.mascot.lumi.plugin.PluginTheme}, which follows the host app's own
 * light/dark setting) so the song-select screen visually matches the gameplay screen it shares a
 * window with, instead of clashing if the app happens to be in light mode.
 * <p>
 * Palette matches Niah's own key art (magenta/hot-pink, purple hair, near-black) rather than the
 * earlier DJMAX-style cyan/gold — the field names below are the pre-retheme ones (ACCENT_CYAN,
 * ACCENT_GOLD) kept as-is to avoid a mechanical rename across every call site; only the RGB values
 * changed. A full pre-retheme source backup was taken before this change.
 */
final class HubPanel extends JPanel {
    private static final Color BG = new Color(18, 13, 20);
    private static final Color BG_PANEL = new Color(30, 22, 34);
    private static final Color BG_ROW = new Color(37, 27, 42);
    private static final Color BG_ROW_ALT = new Color(28, 20, 33);
    private static final Color BG_SELECTED = new Color(94, 30, 68);
    private static final Color BORDER = new Color(72, 48, 66);
    private static final Color ACCENT_CYAN = new Color(255, 105, 180); // hot pink (was cyan)
    private static final Color ACCENT_GOLD = new Color(255, 45, 138); // vivid magenta (was gold)
    private static final Color ACCENT_RED = new Color(255, 92, 122);
    private static final Color TEXT_LIGHT = new Color(238, 226, 236);
    private static final Color TEXT_DIM = new Color(178, 150, 172);
    // Rows 2+ away from the selected (centered) one — dimmed so the carousel's "focus" is obvious
    // at a glance, without needing to actually read every row to tell how far it is from center.
    private static final Color BG_ROW_FAR = new Color(20, 14, 22);
    private static final Color BG_ROW_FAR_ALT = new Color(16, 11, 18);
    private static final Color TEXT_FAR = new Color(120, 100, 112);
    // One color per difficulty page (EASY, NORMAL, HARD, matching Difficulty's declaration order) —
    // green/pink/red is the familiar traffic-light-ish convention, so the color alone hints at the
    // difficulty even before reading the label.
    private static final Color[] DIFFICULTY_COLORS = {new Color(120, 220, 140), ACCENT_CYAN, ACCENT_RED};
    // Deliberately much bigger than a song-list row thumbnail (see SongCarousel's own, smaller
    // sizes) — this is the one thing the player is about to actually play, so it gets the most
    // screen real estate of any single image here.
    private static final int SONG_DETAIL_THUMB_SIZE = 160;
    // Classic list mode only (SongCarousel has its own, separate row-size constants).
    private static final int THUMB_SIZE = 48;
    private static final int THUMB_SIZE_SELECTED = 68;
    private static final int ROW_HEIGHT = 80;

    private final PluginContext ctx;
    private final SongLibrary library;
    private final RhythmSettings settings;
    private final HubWindow hubWindow;
    private final Path chartsDir;
    private final File modTools;

    // songList never actually appears on screen (see SongCarousel) — it stays purely as the
    // selection model every other method here (Play/Edit/Delete, difficulty sync, the song-detail
    // card, etc.) already reads and writes, unchanged.
    private final DefaultListModel<SongLibrary.Song> listModel = new DefaultListModel<>();
    private final JList<SongLibrary.Song> songList = new JList<>(listModel);
    private final JLabel libraryStatus = new JLabel();
    private final JButton practiceModeButton = new JButton();
    private final JButton sideNotesButton = new JButton();
    private final JButton autoPlayButton = new JButton();
    private JLabel difficultyValueLabel;
    private JButton difficultyPrevButton;
    private JButton difficultyNextButton;
    private JLabel profileLevelLabel;
    private JProgressBar profileProgressBar;
    private JPanel profileCard;
    private Border profileCardDefaultBorder;
    // Set right before a real run starts (see captureProfileXpBaseline) so the next refreshProfile()
    // — fired once we're back at the Hub, after ctx.awardAffection() already landed — knows what to
    // animate FROM instead of just snapping straight to the already-current value. -1 means "no run
    // just finished, snap normally" (e.g. the very first call, at Hub construction).
    private int profileAnimBaselineTier = -1;
    private int profileAnimBaselinePct;
    private Timer profileAnimTimer;
    private JLabel songDetailThumb;
    private JLabel songDetailTitle;
    private JLabel songDetailChannel;
    private JLabel songDetailMeta;
    private JLabel songDetailBest;
    private JLabel songDetailBestScore;
    private JLabel songDetailJudge;
    // Holds whichever song-list widget is currently active (see applySongListStyle) — swapped
    // in place so switching styles in Settings doesn't need to rebuild the rest of the Hub.
    private JPanel songListContainer;
    // Keyed by absolute path + last-modified, so a re-downloaded/replaced thumbnail doesn't stay
    // stuck showing the old image. JList repaints cell renderers very often (scrolling, selection),
    // so decoding+scaling from disk on every paint would be wasteful — this decodes each thumbnail
    // once and reuses the already-scaled icon after that.
    private final Map<String, ImageIcon> thumbnailCache = new HashMap<>();
    private static final Map<Integer, ImageIcon> placeholderThumbnailCache = new HashMap<>();
    // A method reference expression creates a fresh instance every time it's evaluated, so
    // UiScale.addListener/removeListener need this same stored instance both times — passing
    // this::applyRowHeight to each separately would add one instance and try (and fail) to remove
    // a different, merely-equal-looking one, leaking the original forever.
    private final Runnable rowHeightListener = this::applyRowHeight;

    HubPanel(PluginContext ctx, RhythmSettings settings, HubWindow hubWindow) {
        this.ctx = ctx;
        this.library = new SongLibrary(ctx.dataDir().resolve("library"));
        this.settings = settings;
        this.hubWindow = hubWindow;
        this.chartsDir = ctx.dataDir().resolve("charts");
        this.modTools = ToolLocator.modTools(ctx.dataDir());

        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setPreferredSize(new Dimension(840, 560));
        setBackground(BG);
        setOpaque(true);

        // A split (not BorderLayout CENTER/EAST) so both sides actually gain space when the Hub
        // window is resized or maximized — a fixed-preferred-width EAST panel never grows past
        // its own preferred size no matter how big the frame gets.
        JSplitPane middle = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLibraryPanel(), buildSideColumn());
        middle.setResizeWeight(0.6);
        middle.setContinuousLayout(true);
        middle.setBorder(BorderFactory.createEmptyBorder());
        middle.setBackground(BG);
        middle.setOpaque(true);
        add(middle, BorderLayout.CENTER);
        add(buildBottomBar(), BorderLayout.SOUTH);

        songList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                syncDifficultyDisplay();
                refreshSongDetail();
                // Only the classic list needs this nudge — SongCarousel always keeps the selection
                // centered on its own, on every repaint, regardless of how it last changed.
                if (settings.songListStyle() == RhythmSettings.SongListStyle.LIST) {
                    songList.ensureIndexIsVisible(songList.getSelectedIndex());
                }
            }
        });
        // The classic JList's own built-in Up/Down (bound at WHEN_FOCUSED, which wins over this
        // panel's WHEN_IN_FOCUSED_WINDOW binding below whenever the list itself has real focus)
        // stops dead at the first/last row instead of wrapping — disabled here so cycleSong's
        // wraparound behavior is the ONE consistent Up/Down behavior everywhere in the Hub,
        // regardless of which component happens to have focus.
        songList.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "none");
        songList.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "none");

        // The song-detail card's thumbnail slot is a fixed pixel number, not a font on some
        // component UiScale.rescale can walk to — it needs its own explicit nudge whenever the
        // scale changes.
        UiScale.addListener(rowHeightListener);
        applyRowHeight();

        // Enter plays the currently selected song from anywhere in the Hub — WHEN_IN_FOCUSED_WINDOW
        // so it works whether focus is on the carousel, the classic list, or a plain button, without
        // needing per-component wiring. Naturally shadowed while the URL field has focus: JTextField
        // already claims Enter for itself at the more specific WHEN_FOCUSED scope, so typing a URL
        // and pressing Enter there is unaffected.
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "hub.playSelected");
        getActionMap().put("hub.playSelected", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                playSelected();
            }
        });

        // Left/Right page through the selected song's difficulty from anywhere in the Hub, same
        // WHEN_IN_FOCUSED_WINDOW reach as Enter above — a plain vertical JList has no default
        // binding for either key (only Up/Down/Home/End/PageUp/PageDown), so this doesn't fight the
        // classic list style's own navigation, and the URL field's own Left/Right (cursor movement,
        // claimed at the more specific WHEN_FOCUSED scope) still wins while typing there.
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "hub.difficultyPrev");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "hub.difficultyNext");
        getActionMap().put("hub.difficultyPrev", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cycleDifficulty(-1);
            }
        });
        getActionMap().put("hub.difficultyNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cycleDifficulty(1);
            }
        });

        // Up/Down page through the song list itself, same WHEN_IN_FOCUSED_WINDOW reach as Enter and
        // Left/Right above — so the song can be changed no matter which control inside the Hub
        // currently has focus (a button, the URL field, the difficulty pager, ...), not only while
        // the list/carousel itself is focused. songList's own competing WHEN_FOCUSED Up/Down binding
        // is disabled where songList is set up, above, so this is the one and only Up/Down handler
        // regardless of focus.
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "hub.songPrev");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "hub.songNext");
        getActionMap().put("hub.songPrev", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cycleSong(-1);
            }
        });
        getActionMap().put("hub.songNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cycleSong(1);
            }
        });

        refreshLibrary();
    }

    /** A fixed pixel number {@link UiScale#rescale} can't reach by walking fonts — the song-detail
     *  card's thumbnail slot — so it needs its own explicit nudge whenever the scale changes. */
    private void applyRowHeight() {
        if (songDetailThumb != null) {
            int size = UiScale.px(SONG_DETAIL_THUMB_SIZE);
            songDetailThumb.setPreferredSize(new Dimension(size, size));
            refreshSongDetail();
            revalidate();
        }
        // SongCarousel isn't scaled by UiScale (see its own class doc); the classic list's row
        // height is, so it needs this same nudge the carousel doesn't.
        if (settings.songListStyle() == RhythmSettings.SongListStyle.LIST) {
            songList.setFixedCellHeight(UiScale.px(ROW_HEIGHT));
            songList.revalidate();
            songList.repaint();
        }
    }

    private JPanel buildLibraryPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBackground(BG);
        panel.setOpaque(true);

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setOpaque(false);
        north.add(sectionTitle(Lang.t(settings, "hub.songs")));
        north.add(buildSortRow());
        panel.add(north, BorderLayout.NORTH);

        // Only one song is ever "the" selection here — the only sensible mode for a song picker.
        songList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);

        songListContainer = new JPanel(new BorderLayout());
        songListContainer.setOpaque(false);
        applySongListStyle();
        panel.add(songListContainer, BorderLayout.CENTER);

        JPanel addRow = new JPanel();
        addRow.setLayout(new BoxLayout(addRow, BoxLayout.Y_AXIS));
        addRow.setBackground(BG);
        addRow.setOpaque(true);

        JButton addWav = new JButton(Lang.t(settings, "hub.addWav"));
        styleOutlineButton(addWav, ACCENT_CYAN);
        addWav.addActionListener(e -> addWavFile());
        addWav.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel urlRow = new JPanel(new BorderLayout(4, 0));
        urlRow.setOpaque(false);
        JTextField urlField = new JTextField();
        styleTextField(urlField);
        JButton addUrl = new JButton(Lang.t(settings, "hub.addUrl"));
        styleOutlineButton(addUrl, ACCENT_CYAN);
        urlRow.add(urlField, BorderLayout.CENTER);
        urlRow.add(addUrl, BorderLayout.EAST);
        addUrl.addActionListener(e -> {
            String url = urlField.getText();
            if (url != null && !url.isBlank()) {
                addFromYoutube(url.strip());
                urlField.setText("");
            }
        });

        JPanel deleteRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        deleteRow.setOpaque(false);
        JButton deleteSelected = new JButton(Lang.t(settings, "hub.deleteSelected"));
        styleOutlineButton(deleteSelected, ACCENT_RED);
        deleteSelected.addActionListener(e -> deleteSelected());
        JButton deleteAll = new JButton(Lang.t(settings, "hub.deleteAll"));
        styleOutlineButton(deleteAll, ACCENT_RED);
        deleteAll.addActionListener(e -> deleteAll());
        deleteRow.add(deleteSelected);
        deleteRow.add(deleteAll);

        addRow.add(addWav);
        addRow.add(Box.createVerticalStrut(4));
        addRow.add(urlRow);
        addRow.add(Box.createVerticalStrut(4));
        addRow.add(deleteRow);
        libraryStatus.setForeground(TEXT_DIM);
        libraryStatus.setFont(libraryStatus.getFont().deriveFont(11f));
        libraryStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        addRow.add(libraryStatus);

        panel.add(addRow, BorderLayout.SOUTH);
        return panel;
    }

    /** Rebuilds {@link #songListContainer} for whichever style is currently set — called once when
     *  the Hub is first built, and again from {@link HubWindow} every time the Settings screen's
     *  적용 button runs, so switching styles takes effect immediately. */
    void applySongListStyle() {
        songListContainer.removeAll();
        if (settings.songListStyle() == RhythmSettings.SongListStyle.CAROUSEL) {
            SongCarousel carousel = new SongCarousel(listModel, songList, songCarouselContent(), songCarouselPalette());
            carousel.setBorder(BorderFactory.createLineBorder(BORDER));
            songListContainer.add(carousel, BorderLayout.CENTER);
        } else {
            songListContainer.add(buildClassicSongList(), BorderLayout.CENTER);
            songList.ensureIndexIsVisible(songList.getSelectedIndex());
        }
        songListContainer.revalidate();
        songListContainer.repaint();
    }

    /** The conventional top-to-bottom, bounded list — "기존처럼" scrolling, a plain scrollbar, no
     *  forced centering and no distance-from-selection dimming (those are specifically what makes
     *  {@link SongCarousel} a carousel; a plain list doesn't need either). Still the same DJMAX-
     *  style "selected row is bigger" emphasis as always. */
    private JScrollPane buildClassicSongList() {
        songList.setCellRenderer((list, song, index, isSelected, cellHasFocus) -> {
            Color rowBg = isSelected ? BG_SELECTED : (index % 2 == 0 ? BG_ROW : BG_ROW_ALT);
            int thumbSize = UiScale.px(isSelected ? THUMB_SIZE_SELECTED : THUMB_SIZE);
            int thumbSlot = UiScale.px(THUMB_SIZE_SELECTED);

            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setOpaque(true);
            row.setBackground(rowBg);
            row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 10));

            // The thumbnail label's own slot always reserves THUMB_SIZE_SELECTED — only the icon
            // inside grows/shrinks — so the text column to its right never shifts sideways when a
            // row becomes (or stops being) the selected one.
            JLabel thumb = new JLabel(thumbnailIcon(song, thumbSize));
            thumb.setHorizontalAlignment(SwingConstants.CENTER);
            thumb.setPreferredSize(new Dimension(thumbSlot, thumbSlot));
            row.add(thumb, BorderLayout.WEST);

            JLabel title = new JLabel(songTitleText(song));
            title.setForeground(isSelected ? ACCENT_CYAN : TEXT_LIGHT);
            title.setFont(title.getFont().deriveFont(Font.BOLD, UiScale.f(isSelected ? 18f : 14f)));
            title.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel meta = new JLabel(songSubtitleText(song));
            meta.setForeground(TEXT_DIM);
            meta.setFont(meta.getFont().deriveFont(UiScale.f(isSelected ? 13f : 11f)));
            meta.setAlignmentX(Component.LEFT_ALIGNMENT);

            JPanel textColumn = new JPanel();
            textColumn.setOpaque(false);
            textColumn.setLayout(new BoxLayout(textColumn, BoxLayout.Y_AXIS));
            textColumn.add(Box.createVerticalGlue());
            textColumn.add(title);
            textColumn.add(Box.createVerticalStrut(2));
            textColumn.add(meta);
            textColumn.add(Box.createVerticalGlue());
            row.add(textColumn, BorderLayout.CENTER);
            return row;
        });
        songList.setFixedCellHeight(UiScale.px(ROW_HEIGHT));

        JScrollPane scroll = new JScrollPane(songList);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        scroll.getViewport().setBackground(BG_PANEL);
        ThemedScrollBar.style(scroll.getVerticalScrollBar(), BG_PANEL, BORDER, ACCENT_CYAN);
        ThemedScrollBar.style(scroll.getHorizontalScrollBar(), BG_PANEL, BORDER, ACCENT_CYAN);
        return scroll;
    }

    private String songTitleText(SongLibrary.Song song) {
        boolean hasChart = ChartStore.exists(chartsDir, song.file());
        return (hasChart ? "♪ " : "") + song.title();
    }

    private String songSubtitleText(SongLibrary.Song song) {
        Difficulty songDiff = settings.songDifficulty(song.file().getName());
        int best = settings.bestScore(song.file().getName(), songDiff);
        String bestSuffix = best <= 0 ? "" : "  ·  BEST " + String.format("%,d", best)
                + (settings.bestRank(song.file().getName(), songDiff).isEmpty() ? ""
                   : " (" + settings.bestRank(song.file().getName(), songDiff) + ")");
        return formatDuration(song.durationMs()) + "  ·  " + humanSize(song.sizeBytes()) + bestSuffix;
    }

    /** Row content for {@link SongCarousel} — delegates back to the same thumbnail cache and
     *  title/best-score formatting the rest of this screen already uses, so nothing about how a
     *  song's info actually looks had to be duplicated for the carousel. */
    private SongCarousel.RowContent songCarouselContent() {
        return new SongCarousel.RowContent() {
            @Override
            public ImageIcon thumbnail(SongLibrary.Song song, int size) {
                return thumbnailIcon(song, size);
            }

            @Override
            public String title(SongLibrary.Song song) {
                return songTitleText(song);
            }

            @Override
            public String subtitle(SongLibrary.Song song) {
                return songSubtitleText(song);
            }
        };
    }

    private SongCarousel.Palette songCarouselPalette() {
        return new SongCarousel.Palette() {
            public Color bg() {
                return BG_PANEL;
            }

            public Color rowNear() {
                return BG_ROW;
            }

            public Color rowNearAlt() {
                return BG_ROW_ALT;
            }

            public Color rowFar() {
                return BG_ROW_FAR;
            }

            public Color rowFarAlt() {
                return BG_ROW_FAR_ALT;
            }

            public Color selected() {
                return BG_SELECTED;
            }

            public Color border() {
                return ACCENT_CYAN;
            }

            public Color titleNear() {
                return TEXT_LIGHT;
            }

            public Color titleFar() {
                return TEXT_FAR;
            }

            public Color titleSelected() {
                return ACCENT_CYAN;
            }

            public Color subtitleNear() {
                return TEXT_DIM;
            }

            public Color subtitleFar() {
                return TEXT_FAR;
            }
        };
    }

    /** How the song list is ordered — a Hub-local preference, like per-song difficulty, so it's
     *  outside the Settings screen's "기본값 복원" scope (see {@link RhythmSettings#resetToDefaults}). */
    private JPanel buildSortRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        JLabel label = new JLabel(Lang.t(settings, "hub.sortOrder") + ":");
        label.setForeground(TEXT_DIM);
        label.setFont(label.getFont().deriveFont(12f));
        row.add(label);

        SongLibrary.SortOrder[] values = SongLibrary.SortOrder.values();
        String[] labels = {
                Lang.t(settings, "hub.sortOrder.added"),
                Lang.t(settings, "hub.sortOrder.title"),
                Lang.t(settings, "hub.sortOrder.duration")};
        JComboBox<String> combo = new JComboBox<>(labels);
        combo.setBackground(BG_ROW);
        combo.setForeground(TEXT_LIGHT);
        combo.setFocusable(true);
        combo.setFont(combo.getFont().deriveFont(12f));
        combo.setBorder(BorderFactory.createLineBorder(BORDER));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object v, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, v, index, isSelected, cellHasFocus);
                l.setBackground(isSelected ? BG_SELECTED : BG_ROW);
                l.setForeground(isSelected ? ACCENT_CYAN : TEXT_LIGHT);
                return l;
            }
        });
        combo.setSelectedIndex(settings.songSortOrder().ordinal());
        combo.addActionListener(e -> {
            SongLibrary.SortOrder order = values[combo.getSelectedIndex()];
            if (order != settings.songSortOrder()) {
                settings.setSongSortOrder(order);
                refreshLibrary();
            }
        });
        row.add(combo);
        return row;
    }

    /** Difficulty picked per song, right where the song is picked — not a single global setting,
     *  since a song's auto-generated chart is what the difficulty actually tunes. A pager (◀ value
     *  ▶) rather than a row of radio buttons: only one is ever selected at a time anyway, and this
     *  reads as "flip to decide" — one value on screen, arrows step to the next/previous. */
    private JPanel buildDifficultyRow() {
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));

        JLabel label = new JLabel(Lang.t(settings, "hub.difficulty"));
        label.setForeground(TEXT_DIM);
        label.setFont(label.getFont().deriveFont(12f));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton prev = new JButton("◀");
        JButton next = new JButton("▶");
        styleOutlineButton(prev, TEXT_LIGHT);
        styleOutlineButton(next, TEXT_LIGHT);
        difficultyPrevButton = prev;
        difficultyNextButton = next;

        difficultyValueLabel = new JLabel();
        difficultyValueLabel.setHorizontalAlignment(SwingConstants.CENTER);
        difficultyValueLabel.setForeground(ACCENT_CYAN);
        difficultyValueLabel.setFont(difficultyValueLabel.getFont().deriveFont(Font.BOLD, 15f));

        prev.addActionListener(e -> cycleDifficulty(-1));
        next.addActionListener(e -> cycleDifficulty(1));

        JPanel pager = new JPanel(new BorderLayout(8, 0));
        pager.setOpaque(false);
        pager.add(prev, BorderLayout.WEST);
        pager.add(difficultyValueLabel, BorderLayout.CENTER);
        pager.add(next, BorderLayout.EAST);
        pager.setAlignmentX(Component.CENTER_ALIGNMENT);

        column.add(label);
        column.add(Box.createVerticalStrut(2));
        column.add(pager);

        syncDifficultyDisplay();
        return column;
    }

    /** Steps the selected song by {@code dir} (±1) through {@link #listModel}, wrapping around at
     *  either end — same continuous "page" loop as {@link #cycleDifficulty}. Selection-change
     *  listeners already wired on {@link #songList} (ensure-visible, difficulty-pager sync, the
     *  detail card) take care of everything else once the index actually changes. */
    private void cycleSong(int dir) {
        int size = listModel.getSize();
        if (size == 0) {
            return;
        }
        int current = songList.getSelectedIndex();
        int next = current < 0 ? 0 : Math.floorMod(current + dir, size);
        songList.setSelectedIndex(next);
    }

    /** Steps the selected song's difficulty by {@code dir} (±1), wrapping around at either end —
     *  a continuous "page" loop rather than stopping dead at Easy/Hard. */
    private void cycleDifficulty(int dir) {
        SongLibrary.Song song = songList.getSelectedValue();
        if (song == null) {
            return;
        }
        Difficulty[] values = Difficulty.values();
        Difficulty current = settings.songDifficulty(song.file().getName());
        int next = Math.floorMod(current.ordinal() + dir, values.length);
        settings.setSongDifficulty(song.file().getName(), values[next]);
        syncDifficultyDisplay();
        // Best score/rank/judgment breakdown are all keyed per-difficulty (see
        // refreshSongDetail — it already blanks them out when this difficulty has no recorded
        // score yet), but this was missing here: changing difficulty only ever updated the pager
        // label above, so the detail card kept showing whatever the PREVIOUS difficulty's score
        // happened to be — looking exactly like the new, uncleared difficulty already had a score.
        refreshSongDetail();
    }

    /** Reflects the selected song's own saved difficulty in the pager — called on selection change
     *  and whenever the list is rebuilt, since re-selecting the same index fires no event. */
    private void syncDifficultyDisplay() {
        if (difficultyValueLabel == null) {
            return;
        }
        SongLibrary.Song song = songList.getSelectedValue();
        Difficulty current = song == null ? Difficulty.NORMAL : settings.songDifficulty(song.file().getName());
        String[] labels = {
                Lang.t(settings, "settings.difficulty.easy"),
                Lang.t(settings, "settings.difficulty.normal"),
                Lang.t(settings, "settings.difficulty.hard")};
        Color accent = DIFFICULTY_COLORS[current.ordinal()];
        difficultyValueLabel.setText(labels[current.ordinal()]);
        difficultyValueLabel.setForeground(accent);
        setOutlineAccent(difficultyPrevButton, accent);
        setOutlineAccent(difficultyNextButton, accent);
        difficultyPrevButton.setEnabled(song != null);
        difficultyNextButton.setEnabled(song != null);
    }

    /** Right-hand side column, importance-ordered top to bottom: a small player-profile card, the
     *  currently-selected song's own (bigger, more detailed) card, then the actions that act on it —
     *  ending in the biggest, most prominent element of all, the Play button itself. */
    private JPanel buildSideColumn() {
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        // Without this, the vertical glue at the very bottom pushes every card up against the very
        // top edge whenever this column has more height than its content needs (the common case) —
        // reads as everything being crammed into the top of the panel instead of sitting in it with
        // some breathing room.
        column.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));
        JPanel profile = buildProfileCard();
        profile.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(profile);
        column.add(Box.createVerticalStrut(10));
        JPanel songDetail = buildSongDetailCard();
        songDetail.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(songDetail);
        column.add(Box.createVerticalStrut(10));
        JPanel play = buildPlaySection();
        play.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(play);
        column.add(Box.createVerticalGlue());
        return column;
    }

    /** The least important card here — a small flourish, not something the player needs mid-flow —
     *  so it stays compact: a level number and a progress bar, nothing more. No portrait — the SDK
     *  exposes no way to pull the player's own Steam avatar/name into a plugin (PluginContext has
     *  no Steam-related API at all, only the affection/level system this card actually reads from),
     *  and a Niah picture here read as "this is Niah's profile," not the player's, so it was pulled
     *  rather than left showing the wrong identity. */
    private JPanel buildProfileCard() {
        JPanel card = new JPanel(new BorderLayout(10, 0));
        card.setBackground(BG_PANEL);
        card.setOpaque(true);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        profileCard = card;
        profileCardDefaultBorder = card.getBorder();

        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));

        JLabel name = new JLabel("니아");
        name.setForeground(TEXT_LIGHT);
        name.setFont(name.getFont().deriveFont(Font.BOLD, 14f));
        name.setAlignmentX(Component.LEFT_ALIGNMENT);

        profileLevelLabel = new JLabel();
        profileLevelLabel.setForeground(ACCENT_CYAN);
        profileLevelLabel.setFont(profileLevelLabel.getFont().deriveFont(Font.BOLD, 12f));
        profileLevelLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        profileProgressBar = new JProgressBar(0, 100);
        profileProgressBar.setPreferredSize(new Dimension(150, 7));
        profileProgressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 7));
        profileProgressBar.setForeground(ACCENT_GOLD);
        profileProgressBar.setBackground(BG_ROW);
        profileProgressBar.setBorderPainted(false);
        profileProgressBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        info.add(name);
        info.add(Box.createVerticalStrut(4));
        info.add(profileLevelLabel);
        info.add(Box.createVerticalStrut(3));
        info.add(profileProgressBar);
        card.add(info, BorderLayout.CENTER);

        refreshProfile();
        return card;
    }

    /** Reflects the current rhythm-game level/progress ({@link RhythmSettings#rhythmLevel()} — this
     *  mod's own local level, separate from Little LUMI's shared, app-wide character affection) —
     *  called once at construction and again every time the Hub is shown (a
     *  just-finished run may have just changed it). Animates the gain (see
     *  {@link #animateProfileXpGain}) instead of just snapping to the new value whenever a baseline
     *  was captured beforehand (see {@link #captureProfileXpBaseline}) and something actually
     *  changed; otherwise (Hub construction, or a practice run that earned nothing) it sets the
     *  display directly, same as before. */
    void refreshProfile() {
        if (profileLevelLabel == null) {
            return;
        }
        int tier = settings.rhythmLevel();
        int pct = settings.rhythmLevelProgressPercent();
        int fromTier = profileAnimBaselineTier;
        int fromPct = profileAnimBaselinePct;
        boolean animate = fromTier >= 0 && (fromTier != tier || fromPct != pct);
        profileAnimBaselineTier = -1;
        if (animate) {
            animateProfileXpGain(fromTier, fromPct, tier, pct);
        } else {
            profileLevelLabel.setForeground(ACCENT_CYAN);
            profileLevelLabel.setText("Lv." + tier + "  " + pct + "%");
            profileProgressBar.setValue(pct);
        }
    }

    /** Snapshots the current rhythm-game level/progress right before a real (non-practice) run
     *  starts — called from {@link #playSelected()} — so the next {@link #refreshProfile()} (once
     *  we're back at the Hub, after {@code settings.addRhythmXp()} has already landed) knows what to
     *  animate from instead of just snapping straight to the already-current value. */
    private void captureProfileXpBaseline() {
        profileAnimBaselineTier = settings.rhythmLevel();
        profileAnimBaselinePct = settings.rhythmLevelProgressPercent();
    }

    private static final long PROFILE_XP_FILL_MS = 700;
    private static final long PROFILE_XP_FLASH_MS = 650;

    /** The "경험치가 증가하는" presentation: the level bar visibly fills from where it was to where
     *  it is now, instead of the number just jumping — a same-tier gain is one smooth fill, while
     *  crossing into a new tier fills all the way to 100%, flashes a "LEVEL UP!" pulse in place of
     *  the usual text, then fills again from 0 under the new level number. Also pulses the whole
     *  card's border gold for the duration so the gain is noticeable even out of the corner of an
     *  eye, not just to someone already staring at the number. */
    private void animateProfileXpGain(int fromTier, int fromPct, int toTier, int toPct) {
        if (profileAnimTimer != null && profileAnimTimer.isRunning()) {
            profileAnimTimer.stop();
        }
        boolean levelUp = toTier > fromTier;
        long phase1End = levelUp ? PROFILE_XP_FILL_MS : 0;
        long phase2End = phase1End + (levelUp ? PROFILE_XP_FLASH_MS : 0);
        long totalMs = phase2End + PROFILE_XP_FILL_MS;
        long startMs = System.currentTimeMillis();

        if (profileCard != null) {
            profileCard.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ACCENT_GOLD, 2),
                    BorderFactory.createEmptyBorder(7, 9, 7, 9)));
        }
        profileAnimTimer = new Timer(16, null);
        profileAnimTimer.addActionListener(e -> {
            long elapsed = System.currentTimeMillis() - startMs;
            if (elapsed >= totalMs) {
                profileAnimTimer.stop();
                profileLevelLabel.setForeground(ACCENT_CYAN);
                profileLevelLabel.setText("Lv." + toTier + "  " + toPct + "%");
                profileProgressBar.setValue(toPct);
                if (profileCard != null) {
                    profileCard.setBorder(profileCardDefaultBorder);
                }
                return;
            }
            if (levelUp && elapsed < phase1End) {
                double t = easeOutCubic(elapsed / (double) phase1End);
                int pct = (int) Math.round(fromPct + (100 - fromPct) * t);
                profileLevelLabel.setForeground(ACCENT_CYAN);
                profileLevelLabel.setText("Lv." + fromTier + "  " + pct + "%");
                profileProgressBar.setValue(pct);
            } else if (levelUp && elapsed < phase2End) {
                double t = (elapsed - phase1End) / (double) PROFILE_XP_FLASH_MS;
                float pulse = (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 6));
                profileLevelLabel.setForeground(blend(ACCENT_GOLD, Color.WHITE, pulse));
                profileLevelLabel.setText(Lang.t(settings, "hub.levelUp"));
                profileProgressBar.setValue(100);
            } else {
                double t = easeOutCubic((elapsed - phase2End) / (double) PROFILE_XP_FILL_MS);
                int pct = (int) Math.round(toPct * t);
                profileLevelLabel.setForeground(ACCENT_CYAN);
                profileLevelLabel.setText("Lv." + toTier + "  " + pct + "%");
                profileProgressBar.setValue(pct);
            }
        });
        profileAnimTimer.start();
    }

    private static double easeOutCubic(double t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - t, 3);
    }

    private static Color blend(Color a, Color b, float t) {
        int r = Math.round(a.getRed() + (b.getRed() - a.getRed()) * t);
        int g = Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t);
        return new Color(r, g, bl);
    }

    /** The most informative card here — what you're actually about to play — so it gets the
     *  biggest thumbnail and the biggest text of anything besides the Play button itself. */
    private JPanel buildSongDetailCard() {
        JPanel card = new JPanel(new BorderLayout(0, 8));
        card.setBackground(BG_PANEL);
        card.setOpaque(true);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)));

        songDetailThumb = new JLabel();
        songDetailThumb.setHorizontalAlignment(SwingConstants.CENTER);
        int thumbSize = UiScale.px(SONG_DETAIL_THUMB_SIZE);
        songDetailThumb.setPreferredSize(new Dimension(thumbSize, thumbSize));
        card.add(songDetailThumb, BorderLayout.NORTH);

        songDetailTitle = new JLabel(" ");
        songDetailTitle.setForeground(TEXT_LIGHT);
        songDetailTitle.setFont(songDetailTitle.getFont().deriveFont(Font.BOLD, 19f));
        songDetailTitle.setHorizontalAlignment(SwingConstants.CENTER);

        songDetailChannel = new JLabel(" ");
        songDetailChannel.setForeground(TEXT_DIM);
        songDetailChannel.setFont(songDetailChannel.getFont().deriveFont(13f));
        songDetailChannel.setHorizontalAlignment(SwingConstants.CENTER);

        songDetailMeta = new JLabel(" ");
        songDetailMeta.setForeground(TEXT_DIM);
        songDetailMeta.setFont(songDetailMeta.getFont().deriveFont(13f));
        songDetailMeta.setHorizontalAlignment(SwingConstants.CENTER);

        // Best rank/score — split into its own big, standalone rank letter (same visual weight as
        // the results screen's centerpiece rank) plus a bold score line right under it, rather than
        // one smaller inline "S  BEST 123,456" caption — the biggest, boldest thing in this whole
        // card besides the song title itself, so "how well have I already done at this song" reads
        // at a glance. A plain (non-HTML) JLabel centers reliably in a BoxLayout column regardless
        // of content width — full-width maximumSize is what actually makes that centering visible
        // when the label's own preferred width is much narrower than the card.
        songDetailBest = new JLabel(" ");
        songDetailBest.setFont(songDetailBest.getFont().deriveFont(Font.BOLD, 34f));
        songDetailBest.setHorizontalAlignment(SwingConstants.CENTER);
        songDetailBest.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        songDetailBestScore = new JLabel(" ");
        songDetailBestScore.setForeground(TEXT_LIGHT);
        songDetailBestScore.setFont(songDetailBestScore.getFont().deriveFont(Font.BOLD, 16f));
        songDetailBestScore.setHorizontalAlignment(SwingConstants.CENTER);
        songDetailBestScore.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        songDetailJudge = new JLabel(" ");
        songDetailJudge.setFont(songDetailJudge.getFont().deriveFont(12f));
        songDetailJudge.setHorizontalAlignment(SwingConstants.CENTER);
        songDetailJudge.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JPanel textColumn = new JPanel();
        textColumn.setOpaque(false);
        textColumn.setLayout(new BoxLayout(textColumn, BoxLayout.Y_AXIS));
        songDetailTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        songDetailChannel.setAlignmentX(Component.CENTER_ALIGNMENT);
        songDetailMeta.setAlignmentX(Component.CENTER_ALIGNMENT);
        songDetailBest.setAlignmentX(Component.CENTER_ALIGNMENT);
        songDetailBestScore.setAlignmentX(Component.CENTER_ALIGNMENT);
        songDetailJudge.setAlignmentX(Component.CENTER_ALIGNMENT);
        textColumn.add(songDetailTitle);
        textColumn.add(Box.createVerticalStrut(4));
        textColumn.add(songDetailChannel);
        textColumn.add(Box.createVerticalStrut(8));
        textColumn.add(songDetailMeta);
        textColumn.add(Box.createVerticalStrut(6));
        textColumn.add(songDetailBest);
        textColumn.add(Box.createVerticalStrut(2));
        textColumn.add(songDetailBestScore);
        textColumn.add(Box.createVerticalStrut(4));
        textColumn.add(songDetailJudge);
        card.add(textColumn, BorderLayout.CENTER);

        refreshSongDetail();
        return card;
    }

    /** Called on every selection change and whenever the list itself is rebuilt. */
    private void refreshSongDetail() {
        if (songDetailThumb == null) {
            return;
        }
        SongLibrary.Song song = songList.getSelectedValue();
        if (song == null) {
            songDetailThumb.setIcon(null);
            songDetailTitle.setText(Lang.t(settings, "hub.pickSongFirst"));
            songDetailChannel.setText(" ");
            songDetailMeta.setText(" ");
            songDetailBest.setText(" ");
            songDetailBestScore.setText(" ");
            songDetailJudge.setText(" ");
            return;
        }
        songDetailThumb.setIcon(thumbnailIcon(song, UiScale.px(SONG_DETAIL_THUMB_SIZE)));
        songDetailTitle.setText(song.title());
        songDetailChannel.setText(song.channel().isBlank() ? " " : song.channel());
        songDetailMeta.setText(formatDuration(song.durationMs()));

        Difficulty diff = settings.songDifficulty(song.file().getName());
        String songFileName = song.file().getName();
        int best = settings.bestScore(songFileName, diff);
        if (best <= 0) {
            songDetailBest.setText(" ");
            songDetailBestScore.setText(" ");
            songDetailJudge.setText(" ");
        } else {
            String rank = settings.bestRank(songFileName, diff);
            songDetailBest.setForeground(rankColor(rank));
            songDetailBest.setText(rank.isEmpty() ? "-" : rank);
            songDetailBestScore.setText("BEST " + String.format("%,d", best));
            songDetailJudge.setText("<html>"
                    + "<span style='color:" + hex(JUDGE_PERFECT) + "'>PERFECT " + settings.bestPerfects(songFileName, diff) + "</span>"
                    + "&nbsp;&nbsp;<span style='color:" + hex(JUDGE_GREAT) + "'>GREAT " + settings.bestGreats(songFileName, diff) + "</span>"
                    + "&nbsp;&nbsp;<span style='color:" + hex(JUDGE_GOOD) + "'>GOOD " + settings.bestGoods(songFileName, diff) + "</span>"
                    + "&nbsp;&nbsp;<span style='color:" + hex(TEXT_DIM) + "'>BREAK " + settings.bestBreaks(songFileName, diff) + "</span>"
                    + "</html>");
        }
    }

    // Matches RhythmPanel's own results-screen palette (rankColor/judgeColor there) so a rank or
    // judgment tier reads as the same color whether you're looking at it on the results screen or
    // back here in the Hub.
    private static final Color JUDGE_PERFECT = new Color(255, 45, 138);
    private static final Color JUDGE_GREAT = new Color(190, 140, 230);
    private static final Color JUDGE_GOOD = new Color(255, 180, 210);

    private static Color rankColor(String rank) {
        return switch (rank) {
            case "S" -> new Color(255, 45, 138);
            case "A" -> new Color(255, 180, 210);
            case "B" -> new Color(190, 140, 230);
            case "C" -> new Color(205, 190, 200);
            default -> new Color(175, 158, 170);
        };
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** Everything that acts on the selected song, difficulty first (it shapes the chart the Play
     *  button is about to generate), practice mode and chart editing next, and finally Play itself
     *  at the bottom — the single biggest, most prominent control in this whole column. */
    private JPanel buildPlaySection() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JPanel difficultyRow = buildDifficultyRow();
        difficultyRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(difficultyRow);
        panel.add(Box.createVerticalStrut(6));

        JPanel secondaryRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        secondaryRow.setOpaque(false);
        // Font/background/opaque set up exactly once, here — refreshPracticeModeButton() only ever
        // swaps text and accent color afterward (see setOutlineAccent), never touching the font
        // again, or a later UiScale rescale's own font size would get clobbered back down to this
        // literal's un-scaled size on every toggle (looked like the button "shrinking" on click).
        styleOutlineButton(practiceModeButton, TEXT_DIM);
        practiceModeButton.addActionListener(e -> {
            settings.setPracticeMode(!settings.practiceMode());
            refreshPracticeModeButton();
        });
        refreshPracticeModeButton();
        styleOutlineButton(sideNotesButton, TEXT_DIM);
        sideNotesButton.addActionListener(e -> {
            settings.setSideNotesEnabled(!settings.sideNotesEnabled());
            refreshSideNotesButton();
        });
        refreshSideNotesButton();
        styleOutlineButton(autoPlayButton, TEXT_DIM);
        autoPlayButton.addActionListener(e -> {
            settings.setAutoPlayEnabled(!settings.autoPlayEnabled());
            refreshAutoPlayButton();
        });
        refreshAutoPlayButton();
        JButton edit = new JButton(Lang.t(settings, "hub.editChart"));
        styleOutlineButton(edit, TEXT_LIGHT);
        edit.addActionListener(e -> editSelected());
        secondaryRow.add(practiceModeButton);
        secondaryRow.add(sideNotesButton);
        secondaryRow.add(autoPlayButton);
        secondaryRow.add(edit);
        secondaryRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(secondaryRow);
        panel.add(Box.createVerticalStrut(10));

        JButton play = new JButton(Lang.t(settings, "hub.play"));
        stylePrimaryButton(play);
        play.setFont(play.getFont().deriveFont(Font.BOLD, 20f));
        play.setAlignmentX(Component.CENTER_ALIGNMENT);
        play.addActionListener(e -> playSelected());
        panel.add(play);

        return panel;
    }

    /** Only Settings lives down here now — the least frequently touched control, so the smallest
     *  and least prominent one, tucked out of the way at the very bottom of the whole screen. */
    private JPanel buildBottomBar() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        row.setBackground(BG);
        row.setOpaque(true);
        JButton settingsButton = new JButton(Lang.t(settings, "hub.settings"));
        styleOutlineButton(settingsButton, TEXT_LIGHT);
        settingsButton.addActionListener(e -> hubWindow.showSettings());
        row.add(settingsButton);
        return row;
    }

    /** Releases resources — call when the Hub window closes. */
    void close() {
        UiScale.removeListener(rowHeightListener);
    }

    private void refreshLibrary() {
        SongLibrary.Song selected = songList.getSelectedValue();
        listModel.clear();
        List<SongLibrary.Song> songs = library.list(settings.songSortOrder());
        for (SongLibrary.Song s : songs) {
            listModel.addElement(s);
        }
        if (selected != null) {
            for (int i = 0; i < listModel.size(); i++) {
                if (listModel.get(i).file().equals(selected.file())) {
                    songList.setSelectedIndex(i);
                    break;
                }
            }
        } else if (!songs.isEmpty()) {
            songList.setSelectedIndex(0);
        }
        libraryStatus.setText(songs.size() + " · " + humanSize(library.totalBytes()));
        syncDifficultyDisplay();
        refreshSongDetail();
    }

    private void addWavFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("WAV", "wav"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            library.importWav(chooser.getSelectedFile());
            refreshLibrary();
        } catch (Exception ex) {
            error(Lang.t(settings, "hub.loadFailed") + ": " + ex.getMessage());
        }
    }

    private void addFromYoutube(String url) {
        libraryStatus.setText(Lang.t(settings, "hub.downloading"));
        Thread worker = new Thread(() -> {
            try {
                ctx.log().info("유튜브에서 내려받는 중: " + url);
                YoutubeImporter.download(url, library.dir().toFile(), modTools, ctx.log());
                ctx.onEdt(this::refreshLibrary);
            } catch (YoutubeImporter.MissingToolException ex) {
                ctx.onEdt(() -> error(ex.getMessage()));
            } catch (Exception ex) {
                ctx.log().warning("유튜브 불러오기 실패: " + ex.getMessage());
                ctx.onEdt(() -> error(Lang.t(settings, "hub.loadFailed") + ": " + ex.getMessage()));
            }
        }, "niah-djmax-hub-youtube-import");
        worker.setDaemon(true);
        worker.start();
    }

    private void deleteSelected() {
        SongLibrary.Song song = songList.getSelectedValue();
        if (song == null) {
            return;
        }
        library.delete(song.file());
        ChartStore.delete(chartsDir, song.file());
        refreshLibrary();
    }

    private void deleteAll() {
        if (listModel.isEmpty()) {
            return;
        }
        int result = JOptionPane.showConfirmDialog(this,
                Lang.t(settings, "hub.confirmDeleteAll") + " (" + humanSize(library.totalBytes()) + ")",
                Lang.t(settings, "hub.deleteAll"), JOptionPane.YES_NO_OPTION);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        library.deleteAll();
        refreshLibrary();
    }

    /** Practice mode: picked here, next to Play, since it's "how the next Play behaves" — same
     *  reasoning as the per-song difficulty row. On: a real chart, real judging/scoring, just no
     *  health gauge and so no way to game-over. Off (default): the normal challenge run. */
    private void refreshPracticeModeButton() {
        boolean on = settings.practiceMode();
        practiceModeButton.setText(Lang.t(settings, "hub.practiceMode") + ": "
                + Lang.t(settings, on ? "settings.on" : "settings.off"));
        setOutlineAccent(practiceModeButton, on ? new Color(120, 220, 140) : TEXT_DIM);
    }

    /** Global override, independent of difficulty: off skips every {@link Note.Kind#SIDE} note at
     *  play time (see {@link #playSelected()}) without touching the cached/generated chart itself,
     *  so flipping it back on immediately shows them again with nothing to regenerate. Easy already
     *  never generates side notes at all (see {@link Difficulty#EASY}); this is for players who
     *  don't want them on Normal/Hard either. */
    private void refreshSideNotesButton() {
        boolean on = settings.sideNotesEnabled();
        sideNotesButton.setText(Lang.t(settings, "hub.sideNotes") + ": "
                + Lang.t(settings, on ? "settings.on" : "settings.off"));
        setOutlineAccent(sideNotesButton, on ? new Color(120, 220, 140) : TEXT_DIM);
    }

    private void refreshAutoPlayButton() {
        boolean on = settings.autoPlayEnabled();
        autoPlayButton.setText(Lang.t(settings, "hub.autoPlay") + ": "
                + Lang.t(settings, on ? "settings.on" : "settings.off"));
        setOutlineAccent(autoPlayButton, on ? new Color(120, 220, 140) : TEXT_DIM);
    }

    /** @return {@code chart} unchanged if side notes are on; otherwise a copy with every
     *  {@link Note.Kind#SIDE} note stripped out, notes list only — nothing else about the chart
     *  (its file, title, cached length) needs to change for this. */
    private Chart withSideNotesToggle(Chart chart) {
        if (settings.sideNotesEnabled()) {
            return chart;
        }
        List<Note> filtered = new ArrayList<>();
        for (Note n : chart.notes) {
            if (n.kind != Note.Kind.SIDE) {
                filtered.add(n);
            }
        }
        return new Chart(chart.audioFile, chart.title, chart.lengthMs, filtered, chart.speedMultiplier);
    }

    private void playSelected() {
        SongLibrary.Song song = songList.getSelectedValue();
        if (song == null) {
            error(Lang.t(settings, "hub.pickSongFirst"));
            return;
        }
        try {
            Chart chart = ChartStore.load(chartsDir, song.file(), song.title());
            if (chart == null) {
                Difficulty diff = settings.songDifficulty(song.file().getName());
                chart = ChartGenerator.fromWav(song.file(), song.title(), diff);
            }
            chart = withSideNotesToggle(chart);
            boolean practiceRun = settings.practiceMode();
            boolean autoRun = settings.autoPlayEnabled();
            // Auto play judges every note PERFECT on its own — same non-scoring footing as practice
            // mode, so it can't be used to farm affection XP or plant a fake best score.
            boolean scoringRun = !practiceRun && !autoRun;
            if (scoringRun) {
                captureProfileXpBaseline();
            }
            hubWindow.playChart(chart, song.thumbnail(), res -> {
                ctx.log().info("결과: score=" + res.score() + " rank=" + res.rank() + " maxCombo=" + res.maxCombo()
                        + (practiceRun ? " (연습)" : "") + (autoRun ? " (퍼펙트 오토)" : ""));
                if (scoringRun && res.cleared()) {
                    int xp = rhythmXpFor(res);
                    ctx.awardAffection("rhythm", xp); // Niah's own app-wide affection — untouched
                    settings.addRhythmXp(xp); // this mod's own local rhythm level
                }
                // RhythmPanel itself records the best score (it has the "new best?" check needed
                // for the results screen) — the list and the detail card just need to pick up
                // whatever change already happened.
                songList.repaint();
                refreshSongDetail();
            });
        } catch (Exception ex) {
            ctx.log().warning("재생 실패: " + ex.getMessage());
            error(Lang.t(settings, "hub.playFailed") + ": " + ex.getMessage());
        }
    }

    /** Rhythm-game XP for a genuine clear — a fail ({@link RhythmPanel.Result#cleared()} false)
     *  earns none at all, same as a failed run not setting a new best score. A rank-based base
     *  amount plus a modest combo bonus, so "결과에 따라 경험치가 늘어난다" actually tracks how well the
     *  run went instead of the old flat trickle purely from max combo (capped at 10 regardless of
     *  score or rank). */
    private static int rhythmXpFor(RhythmPanel.Result res) {
        int base = switch (res.rank()) {
            case "S" -> 40;
            case "A" -> 30;
            case "B" -> 20;
            case "C" -> 12;
            default -> 6;
        };
        int comboBonus = Math.min(15, res.maxCombo() / 10);
        return base + comboBonus;
    }

    private void editSelected() {
        SongLibrary.Song song = songList.getSelectedValue();
        if (song == null) {
            error(Lang.t(settings, "hub.pickSongFirst"));
            return;
        }
        try {
            hubWindow.showChartEditor(song.file(), song.title(), chartsDir, this::refreshLibrary);
        } catch (Exception ex) {
            ctx.log().warning("채보 편집기 열기 실패: " + ex.getMessage());
            error("채보 편집기를 열지 못했습니다: " + ex.getMessage());
        }
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, Lang.t(settings, "hub.title"), JOptionPane.ERROR_MESSAGE);
    }

    private static JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(ACCENT_CYAN);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        label.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 0));
        return label;
    }

    private static void styleOutlineButton(JButton b, Color accent) {
        b.setFocusPainted(false);
        b.setBackground(BG_PANEL);
        b.setOpaque(true);
        setOutlineAccent(b, accent);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 12f));
    }

    /** Just the accent color/border, no font — for a button re-styled repeatedly after a state
     *  change (e.g. {@link #refreshPracticeModeButton}). Re-deriving the font on every refresh
     *  would reset it to this literal's own un-scaled size, undoing whatever {@link UiScale} had
     *  since scaled it to; this only ever needs to run once, in {@link #styleOutlineButton}. */
    private static void setOutlineAccent(JButton b, Color accent) {
        b.setForeground(accent);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accent, 1),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
    }

    private static void stylePrimaryButton(JButton b) {
        b.setFocusPainted(false);
        b.setBackground(ACCENT_GOLD);
        b.setForeground(Color.BLACK);
        b.setBorder(BorderFactory.createEmptyBorder(8, 26, 8, 26));
        b.setFont(b.getFont().deriveFont(Font.BOLD, 16f));
        b.setOpaque(true);
    }

    private static void styleTextField(JTextField f) {
        f.setBackground(BG_PANEL);
        f.setForeground(TEXT_LIGHT);
        f.setCaretColor(TEXT_LIGHT);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format("%.1fMB", bytes / (1024.0 * 1024));
        }
        if (bytes >= 1024) {
            return String.format("%.0fKB", bytes / 1024.0);
        }
        return bytes + "B";
    }

    private static String formatDuration(long ms) {
        if (ms <= 0) {
            return "--:--";
        }
        long totalSec = ms / 1000;
        return String.format("%d:%02d", totalSec / 60, totalSec % 60);
    }

    /** @param size whatever pixel size the caller needs it at (the carousel's selected/side rows
     *  and the song-detail card each use a different one) — each size gets its own cache entry
     *  (and its own placeholder), since the same song can be rendered at several sizes at once. */
    private ImageIcon thumbnailIcon(SongLibrary.Song song, int size) {
        File file = song.thumbnail();
        if (file == null) {
            return placeholderThumbnail(size);
        }
        String key = file.getAbsolutePath() + "@" + file.lastModified() + "@" + size;
        return thumbnailCache.computeIfAbsent(key, k -> {
            ImageIcon loaded = loadThumbnail(file, size);
            return loaded != null ? loaded : placeholderThumbnail(size);
        });
    }

    /** Reads, center-crops to a square, and scales down to {@code size} — done once per (thumbnail
     *  file, size) pair and cached by {@link #thumbnailIcon}, not on every repaint. */
    private static ImageIcon loadThumbnail(File file, int size) {
        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) {
                return null;
            }
            int cropSize = Math.min(img.getWidth(), img.getHeight());
            BufferedImage square = img.getSubimage(
                    (img.getWidth() - cropSize) / 2, (img.getHeight() - cropSize) / 2, cropSize, cropSize);
            Image scaled = square.getScaledInstance(size, size, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (Exception ex) {
            return null; // a corrupt/unreadable thumbnail falls back to the placeholder, never breaks the list
        }
    }

    /** A generic "no thumbnail" tile — a WAV added by hand has no image to show. */
    private static ImageIcon placeholderThumbnail(int size) {
        return placeholderThumbnailCache.computeIfAbsent(size, s -> {
            BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BG_PANEL);
            g.fillRoundRect(0, 0, s, s, 8, 8);
            g.setColor(ACCENT_CYAN);
            g.setFont(g.getFont().deriveFont(Font.BOLD, s * 0.46f));
            FontMetrics fm = g.getFontMetrics();
            String note = "♪";
            g.drawString(note, (s - fm.stringWidth(note)) / 2, (s - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
            return new ImageIcon(img);
        });
    }
}
