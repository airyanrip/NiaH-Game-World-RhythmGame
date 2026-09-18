package djmax;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The single entry-point window. Song select, gameplay, settings, and the chart editor all share
 * one {@link JFrame} — DJMAX Respect V's screens replace each other in place rather than opening
 * a second window — by swapping which panel sits inside {@link #content} via {@link #setScreen}.
 * <p>
 * The frame's size and position are set exactly once, here, and never touched again on a screen
 * swap — {@link #setScreen} only ever swaps {@code content}'s child and calls {@code revalidate()}.
 * An earlier version called {@code frame.pack()} (and, for Settings/the chart editor, also
 * {@code setLocationRelativeTo(null)}) on every swap; since each screen has a different natural
 * size, that made the whole window visibly resize and jump position every time the player opened
 * Settings, the chart editor, or even just went back to the song list — exactly the kind of thing
 * that undermines "this is one continuous window," not just cosmetically annoying. Every screen
 * here already adapts to whatever size it's given (the Hub's {@link javax.swing.JSplitPane}, and
 * {@link RhythmPanel}'s letterboxed fit-to-size painting), so a single fixed window size works for
 * all of them; a real resize by the player is left alone and each screen
 * just re-fits itself on the next {@code revalidate()}/repaint.
 * <p>
 * The frame is undecorated in both windowed and fullscreen mode — {@link TitleBar} draws its own
 * theme-colored minimize/maximize/close chrome (and handles drag-to-move) for windowed mode, shown
 * at the top of {@link #root}, and {@link #resizeHandle} recreates the edge/corner drag-to-resize
 * an undecorated frame otherwise has no way to do; fullscreen has neither, since there's nothing to
 * move or resize. Because {@code setUndecorated}
 * never actually changes, {@link #applyDisplayMode} — a single, deliberate toggle triggered only
 * by the Settings screen's Apply button, never automatically on an ordinary screen swap — never
 * needs {@code frame.dispose()} either: an earlier version did dispose to toggle undecorated, and
 * that dispose() call, as a side effect, fired this class's own "the window is really closing"
 * cleanup below (dispose() posts a {@code WINDOW_CLOSED} event) — so applying fullscreen from
 * Settings ended up tearing the whole window down instead of actually going fullscreen. Not
 * disposing at all sidesteps that class of bug entirely, rather than working around it.
 * <p>
 * {@code frame.setIconImage(host.appIcon())} and {@code host.manageWindow(...)} are what the SDK
 * docs call "make a plugin window look like the app": the former gives it Little LUMI's own icon
 * (taskbar/title bar) instead of the default Java one, the latter has the app remember and
 * restore this window's position like it does its own windows — together, this reads as an
 * extra Little LUMI window rather than some unrelated program's window. The standalone build's
 * {@link StandaloneGameHost} answers both with its own simple defaults instead (see that class).
 */
final class HubWindow {
    private static HubWindow instance;

    // Comfortably fits every screen's natural content (Hub 840x560, Settings 720x560, the chart
    // editor 900-wide) with margin to spare, so nothing feels cramped or needs a scrollbar at the
    // default size. Screens smaller than this just sit inside the extra space; RhythmPanel (the
    // one screen that scales) fills it exactly via its own letterboxed fit-to-size painting.
    private static final Dimension DEFAULT_WINDOW_SIZE = new Dimension(960, 680);
    private static final Dimension MIN_WINDOW_SIZE = new Dimension(700, 500);

    private final GameHost host;
    private final RhythmSettings settings;
    private final JFrame frame;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel content = new JPanel(new BorderLayout());
    private final TitleBar titleBar;
    private final ResizeGlassPane resizeHandle;
    private final HubPanel hubPanel;
    private final GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
    private boolean fullscreen;
    private JComponent currentScreen;
    private Runnable currentScreenCloser = () -> {};

    private HubWindow(GameHost host) {
        this.host = host;
        this.settings = new RhythmSettings(host.prefs());
        this.frame = new JFrame(Lang.t(settings, "hub.title"));
        this.titleBar = new TitleBar(frame, Lang.t(settings, "hub.title"));
        this.resizeHandle = new ResizeGlassPane(frame, MIN_WINDOW_SIZE);
        this.hubPanel = new HubPanel(host, settings, this);

        this.fullscreen = settings.displayMode() == RhythmSettings.DisplayMode.FULLSCREEN
                && device.isFullScreenSupported();

        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setUndecorated(true); // always — TitleBar draws windowed-mode chrome itself
        root.add(content, BorderLayout.CENTER);
        if (!fullscreen) {
            root.add(titleBar, BorderLayout.NORTH);
        }
        frame.setGlassPane(resizeHandle);
        resizeHandle.setVisible(!fullscreen);
        frame.add(root);
        java.awt.Image icon = host.appIcon();
        if (icon != null) {
            frame.setIconImage(icon);
        }
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                instance = null;
                currentScreenCloser.run();
                host.mascotIntegration().release();
                hubPanel.close();
                if (device.getFullScreenWindow() == frame) {
                    device.setFullScreenWindow(null);
                }
            }
        });
        // Auto-pause (game screen) and the practice pad's "let go of everything" reset both need to
        // react to the whole window losing OS focus (alt-tab away) — wired once here at the window
        // level instead of being re-attached on every swap into either screen.
        frame.addWindowFocusListener(new WindowFocusListener() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
            }

            @Override
            public void windowLostFocus(WindowEvent e) {
                if (currentScreen instanceof RhythmPanel playing) {
                    playing.pauseForFocusLoss();
                }
            }
        });

        // Rebuilding every font on every intermediate frame of a resize-drag would be wasteful (and
        // visibly janky) — this waits for the drag to settle for a moment before actually
        // recomputing the scale and re-walking the tree. Fires for every resize path uniformly
        // (drag-resize, the title bar's maximize/restore, the fullscreen toggle), so none of those
        // need their own separate hook into this.
        Timer resizeSettleTimer = new Timer(120, e -> {
            UiScale.update(frame.getWidth(), frame.getHeight());
            UiScale.rescale(root);
        });
        resizeSettleTimer.setRepeats(false);
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                resizeSettleTimer.restart();
            }
        });

        showHub();
        frame.setMinimumSize(MIN_WINDOW_SIZE);
        if (fullscreen) {
            device.setFullScreenWindow(frame);
        } else {
            frame.setSize(DEFAULT_WINDOW_SIZE);
            frame.setVisible(true);
        }
        // Restores this window to wherever the player last left it (centred on the active monitor
        // the first time) instead of always re-centring — the same treatment the app gives its own
        // windows. Called once, here, after the frame has its real size and is on screen.
        host.manageWindow(frame, "hub");
        UiScale.update(frame.getWidth(), frame.getHeight());
        UiScale.rescale(root);
    }

    static void open(GameHost host) {
        if (instance != null) {
            instance.frame.toFront();
            instance.frame.requestFocus();
            return;
        }
        instance = new HubWindow(host);
    }

    /** Play button: swaps the same window's content over to a fresh gameplay screen. CONTINUE and
     *  RESTART stay inside the game screen; MUSIC SELECT, EXIT, and closing the results screen all
     *  come back through {@link #showHub()}. */
    void playChart(Chart chart, File thumbnail, Consumer<RhythmPanel.Result> onFinished) throws Exception {
        RhythmPanel panel = new RhythmPanel(chart, settings, thumbnail, onFinished);
        panel.setOnCloseRequested(this::showHub);
        setScreen(panel, panel::close);
        panel.requestFocusInWindow();
        panel.start();
        host.mascotIntegration().pushAside(frame.getBounds());
    }

    /** Settings button: swaps to the Display/Game/Sound/Controls screen. */
    void showSettings() {
        JComponent screen = SettingsScreen.build(frame, host, settings,
                this::onSettingsApplied, this::onSettingsReset, this::exit, this::showHub);
        setScreen(screen, () -> {});
    }

    /** Runs every time the Settings screen's 적용 button is pressed. */
    private void onSettingsApplied() {
        applyDisplayMode();
        hubPanel.applySongListStyle();
    }

    /** 기본값 복원: writes every default, applies the same way 적용 does, then rebuilds the
     *  Settings screen from scratch so its widgets show the values that were just restored instead
     *  of whatever the player had been about to apply. */
    private void onSettingsReset() {
        settings.resetToDefaults();
        onSettingsApplied();
        showSettings();
    }

    /** Switches the live frame between windowed and fullscreen if the display-mode setting just
     *  changed — triggered only from here (the Settings Apply button), never automatically on a
     *  screen swap. The frame stays undecorated the whole time (see the class doc), so this never
     *  needs {@code dispose()} (which earlier had to run here to toggle {@code setUndecorated} —
     *  and, as a side effect, misfired this class's own "window really closed" cleanup, since
     *  {@code dispose()} fires a {@code WINDOW_CLOSED} event); it only shows/hides {@link #titleBar}
     *  and hands the window to/from the display device's exclusive fullscreen mode. */
    private void applyDisplayMode() {
        boolean wantFullscreen = settings.displayMode() == RhythmSettings.DisplayMode.FULLSCREEN
                && device.isFullScreenSupported();
        if (wantFullscreen == fullscreen) {
            return;
        }
        try {
            if (wantFullscreen) {
                root.remove(titleBar);
                resizeHandle.setVisible(false);
                device.setFullScreenWindow(frame);
            } else {
                if (device.getFullScreenWindow() == frame) {
                    device.setFullScreenWindow(null);
                }
                // Windows can leave an undecorated frame's native peer "stuck" believing it's still
                // maximized/full-screen after setFullScreenWindow(null) — the window looks and acts
                // normal, but the OS silently ignores every later setLocation() call (drag-to-move
                // via the title bar becomes permanently dead). Forcing NORMAL state here is the
                // documented workaround: it makes the peer re-sync its real bounds/state instead of
                // keeping whatever internal flag exclusive fullscreen mode left set.
                frame.setExtendedState(Frame.NORMAL);
                titleBar.resetMaximized();
                root.add(titleBar, BorderLayout.NORTH);
                resizeHandle.setVisible(true);
                frame.setSize(DEFAULT_WINDOW_SIZE);
                frame.setVisible(true);
            }
            root.revalidate();
            root.repaint();
            fullscreen = wantFullscreen;
            if (currentScreen != null) {
                currentScreen.requestFocusInWindow();
            }
        } catch (Exception ex) {
            host.log().warning("디스플레이 모드 전환 실패: " + ex.getMessage());
        }
    }

    /** The Settings screen's "니아의 게임월드 전원 끄기" button — same as closing the window normally
     *  (all the usual cleanup runs via the window-closed listener below), just reachable without
     *  hunting for the title bar in fullscreen or after minimizing. */
    private void exit() {
        frame.dispose();
    }

    /** Edit-chart button: swaps to the chart editor for one song. */
    void showChartEditor(File songFile, String title, Path chartsDir, Runnable onSaved) throws Exception {
        ChartEditorScreen screen = ChartEditorScreen.build(host.log(), songFile, title, chartsDir,
                onSaved, this::showHub);
        setScreen(screen, screen::close);
    }

    /** Back to the song list — reached from Settings/Chart-editor "close", or from the game
     *  screen's MUSIC SELECT, EXIT, and results-screen key press. */
    private void showHub() {
        hubPanel.refreshProfile(); // a just-finished run may have changed the affection level shown
        host.mascotIntegration().release();
        setScreen(hubPanel, () -> {});
        hubPanel.requestFocusInWindow();
    }

    /** Swaps {@link #content} over to {@code screen} and releases whichever screen was showing
     *  before via its own {@code closer}. Deliberately never touches the frame's size or position —
     *  see the class doc for why that used to happen here and why it doesn't anymore. */
    private void setScreen(JComponent screen, Runnable closer) {
        Runnable previousCloser = currentScreenCloser;
        content.removeAll();
        content.add(screen, BorderLayout.CENTER);
        // Settings/the chart editor are rebuilt from scratch every time they're opened, straight
        // from design-size literals — this catches them up to whatever the window's current size
        // already calls for, exactly like the persistent Hub screen already is.
        UiScale.rescale(screen);
        content.revalidate();
        content.repaint();
        screen.requestFocusInWindow();
        currentScreen = screen;
        currentScreenCloser = closer == null ? () -> {} : closer;
        previousCloser.run();
    }
}
