package djmax;

import javax.swing.JComponent;
import javax.swing.JFrame;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * An invisible border-only resize handle for an undecorated {@link JFrame} — with no native OS
 * chrome (see {@link TitleBar}), there's no edge to drag anymore, so this recreates just that: an
 * {@code N}-pixel-wide strip along each edge/corner that drags the frame's bounds and shows the
 * matching resize cursor, while everything past that strip is untouched.
 * <p>
 * Installed as the frame's glass pane rather than a border panel around {@link HubWindow}'s
 * content, so it can sit exactly on the true outer edge without stealing any layout space or
 * needing every screen to leave room for it. {@link #contains(int, int)} is overridden to return
 * {@code false} outside the border strip — the standard Swing idiom for "let mouse events at this
 * point fall through to whatever's underneath" — so the interior keeps working normally (song
 * list clicks, key presses, dragging sliders) with the glass pane only ever actually intercepting
 * input right at the edges.
 */
final class ResizeGlassPane extends JComponent {
    private static final int BORDER = 6;
    private static final int NORTH = 1, SOUTH = 2, EAST = 4, WEST = 8;

    private final JFrame frame;
    private final Dimension minSize;
    private int dragEdges;
    private Point dragMouseStart;
    private Rectangle dragBoundsStart;

    ResizeGlassPane(JFrame frame, Dimension minSize) {
        this.frame = frame;
        this.minSize = minSize;
        setOpaque(false);
        Handler h = new Handler();
        addMouseListener(h);
        addMouseMotionListener(h);
    }

    private int edgesAt(int x, int y) {
        int edges = 0;
        if (y < BORDER) edges |= NORTH;
        if (y >= getHeight() - BORDER) edges |= SOUTH;
        if (x < BORDER) edges |= WEST;
        if (x >= getWidth() - BORDER) edges |= EAST;
        return edges;
    }

    @Override
    public boolean contains(int x, int y) {
        return edgesAt(x, y) != 0;
    }

    private static int cursorFor(int edges) {
        boolean n = (edges & NORTH) != 0, s = (edges & SOUTH) != 0, e = (edges & EAST) != 0, w = (edges & WEST) != 0;
        if (n && w) return Cursor.NW_RESIZE_CURSOR;
        if (n && e) return Cursor.NE_RESIZE_CURSOR;
        if (s && w) return Cursor.SW_RESIZE_CURSOR;
        if (s && e) return Cursor.SE_RESIZE_CURSOR;
        if (n) return Cursor.N_RESIZE_CURSOR;
        if (s) return Cursor.S_RESIZE_CURSOR;
        if (e) return Cursor.E_RESIZE_CURSOR;
        if (w) return Cursor.W_RESIZE_CURSOR;
        return Cursor.DEFAULT_CURSOR;
    }

    private final class Handler extends MouseAdapter {
        @Override
        public void mouseMoved(MouseEvent e) {
            setCursor(Cursor.getPredefinedCursor(cursorFor(edgesAt(e.getX(), e.getY()))));
        }

        @Override
        public void mousePressed(MouseEvent e) {
            dragEdges = edgesAt(e.getX(), e.getY());
            dragMouseStart = e.getLocationOnScreen();
            dragBoundsStart = frame.getBounds();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (dragEdges == 0 || dragMouseStart == null) {
                return;
            }
            Point now = e.getLocationOnScreen();
            int dx = now.x - dragMouseStart.x;
            int dy = now.y - dragMouseStart.y;
            Rectangle b = new Rectangle(dragBoundsStart);
            if ((dragEdges & EAST) != 0) {
                b.width = Math.max(minSize.width, dragBoundsStart.width + dx);
            }
            if ((dragEdges & SOUTH) != 0) {
                b.height = Math.max(minSize.height, dragBoundsStart.height + dy);
            }
            if ((dragEdges & WEST) != 0) {
                int newWidth = Math.max(minSize.width, dragBoundsStart.width - dx);
                b.x = dragBoundsStart.x + (dragBoundsStart.width - newWidth);
                b.width = newWidth;
            }
            if ((dragEdges & NORTH) != 0) {
                int newHeight = Math.max(minSize.height, dragBoundsStart.height - dy);
                b.y = dragBoundsStart.y + (dragBoundsStart.height - newHeight);
                b.height = newHeight;
            }
            frame.setBounds(b);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            dragEdges = 0;
            dragMouseStart = null;
        }
    }
}
