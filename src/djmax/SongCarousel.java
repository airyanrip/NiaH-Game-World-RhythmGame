package djmax;

import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.Timer;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A vertical "wheel picker" for the song list: the selected song sits enlarged in the exact
 * vertical center, its neighbors stack above and below it at a shorter step than their own height
 * so each row visually tucks slightly behind the one nearer the center — real depth, not just a
 * flat scrolling list — and the whole thing wraps: scrolling or dragging past the last song
 * continues straight from the first (and vice versa), so the selected song can always be centered
 * no matter where it sits in the library, not just away from the very top/bottom. Every selection
 * change — wheel, keyboard, click, or letting go of a drag — slides the whole picker into its new
 * resting position over a couple hundred ms (see {@link #OFFSET_DECAY}) rather than snapping
 * instantly, so paging through songs reads as turning to the next one, not just relabeling the
 * center row.
 * <p>
 * {@code selectionSource}'s own selection model stays the single source of truth for "which song
 * is selected" — every other method elsewhere that already reads or writes it (difficulty sync,
 * the song-detail card, Play/Edit/Delete) keeps working completely unchanged; this class only
 * replaces how that selection is drawn and how the player scrolls through it. It never shows
 * itself — {@code selectionSource} (and its {@code JScrollPane}, if it has one) can simply never be
 * added to the visible component tree.
 */
final class SongCarousel extends JComponent {
    /** Rows 2+ away from the selected one are drawn with {@code far}'s colors — everything the
     *  caller already computes for its own palette, handed in once rather than duplicated here. */
    interface Palette {
        Color bg();

        Color rowNear();

        Color rowNearAlt();

        Color rowFar();

        Color rowFarAlt();

        Color selected();

        Color border();

        Color titleNear();

        Color titleFar();

        Color titleSelected();

        Color subtitleNear();

        Color subtitleFar();
    }

    /** Everything about a row's content this class doesn't own — thumbnail decoding/caching, and
     *  the exact title/subtitle text — stays the caller's responsibility. */
    interface RowContent {
        ImageIcon thumbnail(SongLibrary.Song song, int size);

        String title(SongLibrary.Song song);

        String subtitle(SongLibrary.Song song);
    }

    // "Design" sizes at layoutScale == 1.0 — updateLayoutMetrics() scales all of these (and the
    // fonts in paintRow) to whatever multiple of this design actually fills the panel's real
    // height, so the 5 rows always fill it edge to edge (minus OUTER_MARGIN) instead of sitting at
    // a fixed pixel size regardless of how big or small this panel actually is.
    private static final int DESIGN_CENTER_HEIGHT = 140;
    private static final int DESIGN_SIDE_HEIGHT = 96;
    private static final int DESIGN_STEP = 70; // < either height above, so adjacent cards overlap
    private static final int DESIGN_SIDE_MARGIN = 6;
    private static final int DESIGN_THUMB_PAD = 10;
    // Left un-scaled, deliberately: a small, roughly-constant breathing gap at the very top/bottom
    // of the panel regardless of its size, not something that should grow on a bigger screen.
    private static final int OUTER_MARGIN = 10;
    // 2 neighbors either side of the selection, 5 rows total — more than that and the "which one
    // is actually in front" depth effect this whole widget is built around stops reading, since
    // there's too much simultaneously in view to tell what's near versus far anymore.
    private static final int MAX_REACH = 2;

    private static final long MARQUEE_PAUSE_START_MS = 900;
    private static final long MARQUEE_PAUSE_END_MS = 900;
    private static final double MARQUEE_PX_PER_SEC = 38;
    // Applied to visualOffsetPx every render tick while not actively dragging — an exponential
    // ease-out that settles to (visually) zero in well under 150ms, so paging by wheel, keyboard,
    // click, or letting go of a drag all end in the same short "slide into place" instead of an
    // instant jump — the flip/page motion that actually reads as "picking a song."
    private static final double OFFSET_DECAY = 0.6;
    // Hard ceiling on visualOffsetPx as a fraction of the current (scaled) step, independent of how
    // large a jump or how many rapid selection changes fed into it — without this, scrolling
    // several notches before the previous slide had time to settle just kept adding onto whatever
    // was left, so a quick flurry could build up to several steps' worth of displacement: the whole
    // picker would visibly fly past its resting rows (only MAX_REACH of which are ever actually
    // drawn) before slowly crawling back, which is exactly the "list position goes strange" glitch
    // this constant exists to rule out entirely.
    private static final double MAX_VISUAL_OFFSET_FRACTION = 0.6;

    private final DefaultListModel<SongLibrary.Song> model;
    private final JList<SongLibrary.Song> selectionSource;
    private final RowContent content;
    private final Palette palette;
    private final Timer renderTimer = new Timer(16, e -> tickAnimation());

    // Scaled from DESIGN_* by updateLayoutMetrics() every paint — see that method's doc.
    private int centerHeight = DESIGN_CENTER_HEIGHT;
    private int sideHeight = DESIGN_SIDE_HEIGHT;
    private int stepPx = DESIGN_STEP;
    private int sideMargin = DESIGN_SIDE_MARGIN;
    private int thumbPad = DESIGN_THUMB_PAD;
    private double layoutScale = 1.0;

    // How far the whole picker is currently displaced from its resting (selection-centered) layout,
    // in pixels — driven 1:1 by the mouse while dragging, and eased back to 0 by renderTimer
    // otherwise (see OFFSET_DECAY) after any selection change, animated or not.
    private double visualOffsetPx;
    private boolean dragging;
    private double dragStartOffsetPx;
    private int dragStartY;
    private boolean dragged;
    // Which song's title the marquee cycle below is currently timed against, and when that cycle
    // started — reset the instant the selection changes to a different song, so a freshly-centered
    // title always starts its scroll from the very beginning, never mid-cycle.
    private Object marqueeSongKey;
    private long marqueeStartMs;

    SongCarousel(DefaultListModel<SongLibrary.Song> model, JList<SongLibrary.Song> selectionSource,
                 RowContent content, Palette palette) {
        this.model = model;
        this.selectionSource = selectionSource;
        this.content = content;
        this.palette = palette;
        setOpaque(true);
        setBackground(palette.bg());
        setFocusable(true);
        setPreferredSize(new Dimension(320, 400));

        model.addListDataListener(new ListDataListener() {
            @Override
            public void intervalAdded(ListDataEvent e) {
                repaint();
            }

            @Override
            public void intervalRemoved(ListDataEvent e) {
                repaint();
            }

            @Override
            public void contentsChanged(ListDataEvent e) {
                repaint();
            }
        });
        selectionSource.addListSelectionListener(e -> repaint());

        addMouseWheelListener(e -> {
            requestFocusInWindow();
            step(e.getWheelRotation());
        });

        MouseAdapter drag = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                dragStartY = e.getYOnScreen();
                // Picks up from wherever the view currently is (mid ease-out or not) instead of
                // snapping to 0 first — grabbing the wheel again before the last page finished
                // settling continues smoothly rather than jumping.
                dragStartOffsetPx = visualOffsetPx;
                dragging = true;
                dragged = false;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                visualOffsetPx = dragStartOffsetPx + (e.getYOnScreen() - dragStartY);
                if (Math.abs(visualOffsetPx - dragStartOffsetPx) > 3) {
                    dragged = true;
                }
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = false;
                if (dragged) {
                    int stepsMoved = -(int) Math.round(visualOffsetPx / (double) stepPx);
                    applySelectionDelta(stepsMoved);
                } else {
                    selectAtY(e.getY());
                }
                dragged = false;
                repaint();
            }
        };
        addMouseListener(drag);
        addMouseMotionListener(drag);

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_UP) {
                    step(-1);
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    step(1);
                }
            }
        });

        renderTimer.setRepeats(true);
    }

    /** Advances the paging ease-out (see {@link #OFFSET_DECAY}) and the title marquee — one timer
     *  driving both, since both are just "repaint on a schedule until settled/looped." */
    private void tickAnimation() {
        if (!dragging) {
            if (Math.abs(visualOffsetPx) > 0.4) {
                visualOffsetPx *= OFFSET_DECAY;
            } else {
                visualOffsetPx = 0;
            }
        }
        repaint();
    }

    /** Only ticks the render timer while this widget is actually part of a displayable hierarchy —
     *  no point animating (or spending CPU on) a title/page transition nobody can see, e.g. while
     *  some other Hub screen is showing instead. */
    @Override
    public void addNotify() {
        super.addNotify();
        renderTimer.start();
    }

    @Override
    public void removeNotify() {
        renderTimer.stop();
        super.removeNotify();
    }

    /** Moves the selection by {@code delta} rows, wrapping past either end. */
    private void step(int delta) {
        applySelectionDelta(delta);
    }

    private void selectAtY(int y) {
        int centerY = getHeight() / 2;
        int stepsFromCenter = (int) Math.round((y - centerY) / (double) stepPx);
        applySelectionDelta(stepsFromCenter);
    }

    /** Changes the selection by {@code delta} rows (wrapping past either end) and hands the visual
     *  slide this causes over to {@link #tickAnimation}: since every row is about to be redrawn
     *  {@code delta} slots further along, adding {@code delta * stepPx} to {@code visualOffsetPx}
     *  first keeps every row exactly where it already was on screen for this one frame, so the
     *  ease-out that follows is a slide from "still at the old position" to "at rest," never a jump. */
    private void applySelectionDelta(int delta) {
        int size = model.getSize();
        if (size == 0 || delta == 0) {
            return;
        }
        int current = selectionSource.getSelectedIndex();
        int base = current < 0 ? 0 : current;
        double maxOffset = stepPx * MAX_VISUAL_OFFSET_FRACTION;
        double proposed = visualOffsetPx + delta * (double) stepPx;
        visualOffsetPx = Math.max(-maxOffset, Math.min(maxOffset, proposed));
        selectionSource.setSelectedIndex(Math.floorMod(base + delta, size));
    }

    /** Scales centerHeight/sideHeight/stepPx/sideMargin/thumbPad/layoutScale from DESIGN_* so the
     *  MAX_REACH*2+1 rows always fill this panel's actual current height (minus OUTER_MARGIN top
     *  and bottom) instead of sitting at one fixed pixel size regardless of how big or small this
     *  panel actually is — called once per paint, which already runs after every resize. */
    private void updateLayoutMetrics() {
        double designHalfSpan = MAX_REACH * DESIGN_STEP + DESIGN_SIDE_HEIGHT / 2.0;
        double availableHalf = Math.max(1, getHeight() / 2.0 - OUTER_MARGIN);
        layoutScale = availableHalf / designHalfSpan;
        centerHeight = (int) Math.round(DESIGN_CENTER_HEIGHT * layoutScale);
        sideHeight = (int) Math.round(DESIGN_SIDE_HEIGHT * layoutScale);
        stepPx = Math.max(1, (int) Math.round(DESIGN_STEP * layoutScale));
        sideMargin = Math.max(1, (int) Math.round(DESIGN_SIDE_MARGIN * layoutScale));
        thumbPad = Math.max(1, (int) Math.round(DESIGN_THUMB_PAD * layoutScale));
    }

    @Override
    protected void paintComponent(Graphics g0) {
        updateLayoutMetrics();
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        g.setColor(palette.bg());
        g.fillRect(0, 0, w, h);

        int size = model.getSize();
        if (size == 0) {
            return;
        }
        int selected = selectionSource.getSelectedIndex();
        if (selected < 0) {
            selected = 0;
        }
        int centerY = h / 2 + (int) Math.round(visualOffsetPx);
        // Capped at MAX_REACH regardless of how much taller the panel is than that — 5 rows showing
        // at once is the point, not however many would technically fit. size/2 (not (size-1)/2 —
        // that undercounted by one row for every even-sized library, most visibly a 2-song library
        // never showing its second song at all) is exactly how far you can go from the selected
        // index in either direction before wrapping back onto a slot you've already placed: at
        // reach == size/2 the two extreme slots legitimately land on the same "opposite" song for
        // an even-sized ring (correct — it really is equidistant either way), never on two
        // different songs colliding in one slot.
        int reach = Math.min(MAX_REACH, size / 2);

        // Farthest first, nearest (selected) last — later painting sits visually on top, which is
        // what makes the selected card read as tucked in front of, not beside, its neighbors.
        for (int d = reach; d >= 0; d--) {
            if (d == 0) {
                paintRow(g, Math.floorMod(selected, size), centerY, 0);
            } else {
                paintRow(g, Math.floorMod(selected - d, size), centerY - d * stepPx, d);
                paintRow(g, Math.floorMod(selected + d, size), centerY + d * stepPx, d);
            }
        }
    }

    /** @param distance 0 for the selected (centered) row, otherwise how many visual slots away
     *  from center this row was placed — the "far" (2+) dimming reads off this, not off the raw
     *  index difference, so it stays correct right across the wraparound seam. */
    private void paintRow(Graphics2D g, int index, int rowCenterY, int distance) {
        boolean selected = distance == 0;
        int height = selected ? centerHeight : sideHeight;
        if (rowCenterY + height / 2 < 0 || rowCenterY - height / 2 > getHeight()) {
            return; // fully off-panel, not worth drawing
        }
        SongLibrary.Song song = model.get(index);
        // Every non-selected row counts as "far" now — the selected card needs to be the one
        // obviously bright thing on screen, not just slightly brighter than its neighbors.
        boolean far = distance >= 1;

        int top = rowCenterY - height / 2;
        int w = getWidth();
        Color bg = selected ? palette.selected() : far ? (index % 2 == 0 ? palette.rowFar() : palette.rowFarAlt())
                : (index % 2 == 0 ? palette.rowNear() : palette.rowNearAlt());
        g.setColor(bg);
        g.fillRoundRect(sideMargin, top, w - sideMargin * 2, height, 16, 16);
        if (selected) {
            g.setColor(palette.border());
            g.setStroke(new BasicStroke((float) Math.max(1, 2 * layoutScale)));
            g.drawRoundRect(sideMargin, top, w - sideMargin * 2, height, 16, 16);
        }

        int thumbSize = height - thumbPad * 2;
        ImageIcon icon = content.thumbnail(song, thumbSize);
        int thumbX = sideMargin + thumbPad;
        int thumbY = rowCenterY - thumbSize / 2;
        if (icon != null) {
            g.drawImage(icon.getImage(), thumbX, thumbY, thumbSize, thumbSize, null);
        }

        int textX = thumbX + thumbSize + (int) Math.round(12 * layoutScale);
        int textRight = w - sideMargin - thumbPad;
        Shape oldClip = g.getClip();
        g.clipRect(textX, top, Math.max(0, textRight - textX), height);

        Color titleColor = selected ? palette.titleSelected() : far ? palette.titleFar() : palette.titleNear();
        g.setColor(titleColor);
        g.setFont(getFont().deriveFont(Font.BOLD, (float) ((selected ? 22f : 16f) * layoutScale)));
        FontMetrics titleFm = g.getFontMetrics();
        String titleText = content.title(song);
        int titleWidth = titleFm.stringWidth(titleText);
        int availableWidth = Math.max(0, textRight - textX);
        int titleDrawX = textX;
        // Only the selected title ever marquees — a wall of simultaneously-scrolling titles across
        // all 5 rows would be far busier than helpful; the centered one is the only text anyone is
        // actually trying to read at any given moment anyway.
        if (selected && titleWidth > availableWidth) {
            Object key = song.file();
            if (!key.equals(marqueeSongKey)) {
                marqueeSongKey = key;
                marqueeStartMs = System.currentTimeMillis();
            }
            long scrollDistance = titleWidth - availableWidth;
            double marqueeSpeed = MARQUEE_PX_PER_SEC * layoutScale;
            long scrollDurationMs = Math.max(1, Math.round(scrollDistance / marqueeSpeed * 1000));
            long totalCycleMs = MARQUEE_PAUSE_START_MS + scrollDurationMs + MARQUEE_PAUSE_END_MS;
            long phase = (System.currentTimeMillis() - marqueeStartMs) % totalCycleMs;
            double offset;
            if (phase < MARQUEE_PAUSE_START_MS) {
                offset = 0;
            } else if (phase < MARQUEE_PAUSE_START_MS + scrollDurationMs) {
                offset = (phase - MARQUEE_PAUSE_START_MS) / (double) scrollDurationMs * scrollDistance;
            } else {
                offset = scrollDistance; // paused, fully scrolled — the end of the title is showing
            }
            titleDrawX = textX - (int) Math.round(offset);
        }
        int titleBaselineNudge = (int) Math.round(4 * layoutScale);
        int lineGap = (int) Math.round(16 * layoutScale);
        g.drawString(titleText, titleDrawX, rowCenterY - titleBaselineNudge);

        g.setColor(far ? palette.subtitleFar() : palette.subtitleNear());
        g.setFont(getFont().deriveFont((float) ((selected ? 15f : 12f) * layoutScale)));
        g.drawString(content.subtitle(song), textX, rowCenterY - titleBaselineNudge + titleFm.getDescent() + lineGap);

        g.setClip(oldClip);

        // A flat black wash over the whole card, on top of everything (thumbnail included) — the
        // further from the selection, the more it recedes into the background, instead of every
        // non-selected row reading as equally "not it" regardless of how close it actually is.
        if (!selected) {
            int shadeAlpha = Math.min(180, distance * 90);
            g.setColor(new Color(0, 0, 0, shadeAlpha));
            g.fillRoundRect(sideMargin, top, w - sideMargin * 2, height, 16, 16);
        }
    }
}
