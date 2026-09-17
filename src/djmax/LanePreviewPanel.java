package djmax;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * A small static preview of the 4 lanes for the Controls tab — same colors as the real game, each
 * lane showing its current key, so rebinding a key shows exactly which lane it belongs to instead
 * of a bare list of buttons.
 */
final class LanePreviewPanel extends JPanel {
    private static final int LANE_WIDTH = 76;
    private static final int HEIGHT = 110;

    private final RhythmSettings settings;
    private int highlightLane = -1;

    LanePreviewPanel(RhythmSettings settings) {
        this.settings = settings;
        setPreferredSize(new Dimension(RhythmSettings.LANES * LANE_WIDTH, HEIGHT));
        setBackground(Color.BLACK);
    }

    /** Which lane is currently being rebound (glows), or -1 for none. */
    void setHighlightLane(int lane) {
        this.highlightLane = lane;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int[] keys = settings.laneKeys();
        ColorVision cv = settings.colorVision();
        int w = getWidth();
        int h = getHeight();
        int laneW = w / RhythmSettings.LANES;

        for (int lane = 0; lane < RhythmSettings.LANES; lane++) {
            int x = lane * laneW;
            boolean hi = lane == highlightLane;
            Color base = cv.laneColors()[lane];
            g.setColor(hi ? base : new Color(34, 25, 38));
            g.fillRect(x, 0, laneW, h);
            g.setColor(new Color(72, 55, 70));
            g.drawRect(x, 0, laneW - 1, h - 1);

            g.setColor(hi ? Color.BLACK : Color.WHITE);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 22f));
            String label = KeyLabels.of(keys[lane]);
            FontMetrics fm = g.getFontMetrics();
            int tx = x + (laneW - fm.stringWidth(label)) / 2;
            int ty = h / 2 + fm.getAscent() / 2 - 4;
            g.drawString(label, tx, ty);
        }
    }
}
