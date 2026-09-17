package djmax;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

/**
 * A custom, theme-colored title bar — minimize / maximize-restore / close, and drag-to-move —
 * for a frame that's undecorated even in windowed mode, so the native OS title bar (a plain white
 * or system-themed strip) never clashes with the rest of this dark pink/purple interface. Attach
 * with {@link HubWindow}'s frame at {@code BorderLayout.NORTH} of the same root the game content
 * sits in; only present while {@link HubWindow} isn't in fullscreen (fullscreen has no chrome to
 * show at all).
 * <p>
 * There is no edge-drag resize here (undecorated frames don't get that for free, and building it
 * is a lot of machinery for a mod window) — {@link #toggleMaximize} is the one way to change size
 * beyond whatever {@link HubWindow}'s default is, which matches what was actually asked for
 * (minimize / enlarge / close), not arbitrary resizing.
 */
final class TitleBar extends JPanel {
    private static final Color BG = new Color(24, 17, 26);
    private static final Color TEXT = new Color(225, 210, 222);
    private static final Color HOVER = new Color(52, 32, 48);
    private static final Color CLOSE_HOVER = new Color(214, 48, 90);
    private static final int HEIGHT = 34;

    private final JFrame frame;
    private Point dragOffset;
    private Rectangle restoreBounds;
    private boolean maximized;

    TitleBar(JFrame frame, String title) {
        this.frame = frame;
        setLayout(new BorderLayout());
        setBackground(BG);
        setPreferredSize(new Dimension(10, HEIGHT));

        JLabel titleLabel = new JLabel("  " + title);
        titleLabel.setForeground(TEXT);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        add(titleLabel, BorderLayout.WEST);

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(chromeButton("─", this::minimize, HOVER));       // ─
        buttons.add(chromeButton("□", this::toggleMaximize, HOVER)); // □
        buttons.add(chromeButton("×", this::close, CLOSE_HOVER));    // ×
        add(buttons, BorderLayout.EAST);

        MouseAdapter dragStart = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragOffset = e.getPoint();
            }
        };
        MouseMotionAdapter dragMove = new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragOffset == null || maximized) {
                    return;
                }
                Point onScreen = e.getLocationOnScreen();
                frame.setLocation(onScreen.x - dragOffset.x, onScreen.y - dragOffset.y);
            }
        };
        addMouseListener(dragStart);
        addMouseMotionListener(dragMove);
        titleLabel.addMouseListener(dragStart);
        titleLabel.addMouseMotionListener(dragMove);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    toggleMaximize();
                }
            }
        });
    }

    private JButton chromeButton(String symbol, Runnable action, Color hoverBg) {
        JButton b = new JButton(symbol);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(true);
        b.setOpaque(true);
        b.setBackground(BG);
        b.setForeground(TEXT);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        b.setPreferredSize(new Dimension(44, HEIGHT));
        b.addActionListener(e -> action.run());
        b.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                b.setBackground(hoverBg);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                b.setBackground(BG);
            }
        });
        return b;
    }

    private void minimize() {
        frame.setExtendedState(Frame.ICONIFIED);
    }

    /** Manual bounds toggle rather than {@code setExtendedState(MAXIMIZED_BOTH)} — more reliably
     *  respected on Windows for an undecorated frame, and this way {@link #restoreBounds} always
     *  has an exact bounds to go back to regardless of platform quirks. */
    private void toggleMaximize() {
        if (maximized) {
            if (restoreBounds != null) {
                frame.setBounds(restoreBounds);
            }
            maximized = false;
        } else {
            restoreBounds = frame.getBounds();
            GraphicsConfiguration gc = frame.getGraphicsConfiguration();
            Rectangle screen = gc.getBounds();
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            frame.setBounds(
                    screen.x + insets.left,
                    screen.y + insets.top,
                    screen.width - insets.left - insets.right,
                    screen.height - insets.top - insets.bottom);
            maximized = true;
        }
    }

    private void close() {
        frame.dispatchEvent(new java.awt.event.WindowEvent(frame, java.awt.event.WindowEvent.WINDOW_CLOSING));
    }

    /** Clears the maximize/restore-bounds state — call after returning from fullscreen (see
     *  {@link HubWindow#applyDisplayMode}). Without this, if the player had double-clicked the title
     *  bar to maximize before switching to fullscreen and back, {@link #maximized} was still stuck
     *  {@code true} (this instance is reused for the whole window's lifetime, never recreated), which
     *  silently makes {@code dragMove} a no-op forever after — the window looked normal but could
     *  never be dragged again. {@code restoreBounds} is stale after a fullscreen round-trip too
     *  (it's from before, at whatever size the window happened to be) so it's dropped along with it
     *  rather than kept around to toggle back to something no longer relevant. */
    void resetMaximized() {
        maximized = false;
        restoreBounds = null;
    }
}
