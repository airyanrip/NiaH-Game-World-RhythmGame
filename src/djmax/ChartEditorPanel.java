package djmax;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Hand chart editor: a waveform + 4 lane timelines for the current view window. A quick click in a
 * lane adds/removes a tap note; press, drag, and release adds a long note spanning the drag (or
 * removes one it lands on). Space to play/pause, arrows to seek. No snapping — freeform placement,
 * since this is meant for fine-tuning by ear/eye, not a substitute for the auto-chart's detection.
 */
final class ChartEditorPanel extends JPanel {
    private static final int LANES = RhythmSettings.LANES;
    private static final int WAVEFORM_HEIGHT = 90;
    private static final int LANE_ROW_HEIGHT = 56;
    private static final int PANEL_WIDTH = 900;
    private static final long DEFAULT_VIEW_WIDTH_MS = 4000;
    private static final long HIT_TOLERANCE_MS = 90;
    private static final String[] LANE_LABELS = {"1", "2", "3", "4"};

    private final Clip clip;
    private final long lengthMs;
    private final float[] envelope; // one min/max-derived magnitude per column, spanning the whole song
    private final Timer loop;
    private final List<Note> notes = new ArrayList<>();
    // Side-track notes (Note.Kind.SIDE) from the loaded chart — this editor has no grid cell for
    // them (it's strictly a 4-lane timeline), only ChartGenerator places them, so they're carried
    // through untouched rather than dropped on save. See notes().
    private final List<Note> sideNotes = new ArrayList<>();

    private long viewStartMs = 0;
    private long viewWidthMs = DEFAULT_VIEW_WIDTH_MS;
    private boolean playing = false;
    private long pausedAtMs = 0;

    private int dragLane = -1;
    private long dragStartMs;
    private long dragCurrentMs;

    /** Note fall-speed multiplier for this chart — same field {@link ChartGenerator} derives
     *  automatically for an auto-chart, but here it's a direct, explicit editor control (see
     *  {@link #setSpeedMultiplier}) since a hand-authored chart has no note count for the
     *  generator's density-based estimate to work from. */
    private double speedMultiplier = 1.0;

    static final double MIN_SPEED_MULTIPLIER = 0.5;
    static final double MAX_SPEED_MULTIPLIER = 2.0;

    ChartEditorPanel(File songFile, Chart initialChart) throws Exception {
        setPreferredSize(new Dimension(PANEL_WIDTH, WAVEFORM_HEIGHT + LANES * LANE_ROW_HEIGHT + 30));
        setBackground(Color.BLACK);
        setFocusable(true);

        AudioFormat format;
        byte[] pcm;
        try (AudioInputStream in = AudioSystem.getAudioInputStream(songFile)) {
            format = in.getFormat();
            pcm = in.readAllBytes();
        }
        clip = AudioSystem.getClip();
        try (AudioInputStream in = AudioSystem.getAudioInputStream(songFile)) {
            clip.open(in);
        }
        this.lengthMs = (long) (clip.getMicrosecondLength() / 1000.0);
        this.envelope = buildEnvelope(pcm, format, 2000);

        if (initialChart != null) {
            for (Note n : initialChart.notes) {
                if (n.kind == Note.Kind.SIDE) {
                    sideNotes.add(n);
                } else {
                    notes.add(new Note(n.timeMs, n.endTimeMs, n.lane));
                }
            }
            speedMultiplier = clampSpeedMultiplier(initialChart.speedMultiplier);
        }

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                double pxPerMs = getWidth() / (double) viewWidthMs;
                long clickedMs = viewStartMs + (long) (e.getX() / pxPerMs);
                if (e.getY() < WAVEFORM_HEIGHT) {
                    seekTo(clickedMs);
                    return;
                }
                int lane = (e.getY() - WAVEFORM_HEIGHT) / LANE_ROW_HEIGHT;
                if (lane < 0 || lane >= LANES) {
                    return;
                }
                dragLane = lane;
                dragStartMs = clickedMs;
                dragCurrentMs = clickedMs;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragLane < 0) {
                    return;
                }
                int lane = dragLane;
                dragLane = -1;
                double pxPerMs = getWidth() / (double) viewWidthMs;
                long releaseMs = viewStartMs + (long) (e.getX() / pxPerMs);
                if (Math.abs(releaseMs - dragStartMs) <= HIT_TOLERANCE_MS) {
                    toggleNote(lane, dragStartMs); // a quick click, not a drag: plain tap note
                } else {
                    addHoldNote(lane, Math.min(dragStartMs, releaseMs), Math.max(dragStartMs, releaseMs));
                }
                repaint();
            }
        });

        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragLane < 0) {
                    return;
                }
                double pxPerMs = getWidth() / (double) viewWidthMs;
                dragCurrentMs = viewStartMs + (long) (e.getX() / pxPerMs);
                repaint();
            }
        });

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_SPACE -> togglePlay();
                    case KeyEvent.VK_LEFT -> seekTo(currentMs() - (e.isShiftDown() ? 5000 : 1000));
                    case KeyEvent.VK_RIGHT -> seekTo(currentMs() + (e.isShiftDown() ? 5000 : 1000));
                    case KeyEvent.VK_HOME -> seekTo(0);
                    case KeyEvent.VK_EQUALS, KeyEvent.VK_ADD -> zoom(0.8);
                    case KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> zoom(1.25);
                    default -> {
                    }
                }
            }
        });

        loop = new Timer(16, e -> {
            if (playing) {
                autoScroll();
                if (!clip.isRunning()) {
                    playing = false;
                }
            }
            repaint();
        });
        loop.start();
    }

    /** Editable lane notes plus whatever side-track notes the loaded chart already had, carried
     *  through untouched — see {@link #sideNotes}. */
    List<Note> notes() {
        List<Note> all = new ArrayList<>(notes.size() + sideNotes.size());
        all.addAll(notes);
        all.addAll(sideNotes);
        return all;
    }

    long lengthMs() {
        return lengthMs;
    }

    double speedMultiplier() {
        return speedMultiplier;
    }

    void setSpeedMultiplier(double v) {
        speedMultiplier = clampSpeedMultiplier(v);
    }

    private static double clampSpeedMultiplier(double v) {
        return Math.max(MIN_SPEED_MULTIPLIER, Math.min(MAX_SPEED_MULTIPLIER, v));
    }

    void close() {
        loop.stop();
        clip.close();
    }

    private void toggleNote(int lane, long atMs) {
        if (removeNoteAt(lane, atMs)) {
            return;
        }
        notes.add(new Note(Math.max(0, atMs), lane));
    }

    /** Adds a long note from {@code startMs} to {@code endMs}, first clearing anything already there. */
    private void addHoldNote(int lane, long startMs, long endMs) {
        Iterator<Note> it = notes.iterator();
        while (it.hasNext()) {
            Note n = it.next();
            if (n.lane == lane && overlaps(n, startMs - HIT_TOLERANCE_MS, endMs + HIT_TOLERANCE_MS)) {
                it.remove();
            }
        }
        notes.add(new Note(Math.max(0, startMs), endMs, lane));
    }

    /** @return true if a note at {@code atMs} (within tolerance, anywhere along a long note's body) was removed. */
    private boolean removeNoteAt(int lane, long atMs) {
        Iterator<Note> it = notes.iterator();
        while (it.hasNext()) {
            Note n = it.next();
            if (n.lane == lane && overlaps(n, atMs - HIT_TOLERANCE_MS, atMs + HIT_TOLERANCE_MS)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private static boolean overlaps(Note n, long rangeStart, long rangeEnd) {
        return n.timeMs <= rangeEnd && n.endTimeMs >= rangeStart;
    }

    private void togglePlay() {
        if (playing) {
            pausedAtMs = currentMs();
            clip.stop();
            playing = false;
        } else {
            clip.setMicrosecondPosition(pausedAtMs * 1000);
            clip.start();
            playing = true;
        }
        requestFocusInWindow();
    }

    private void seekTo(long ms) {
        long clamped = Math.max(0, Math.min(ms, lengthMs));
        boolean wasPlaying = playing;
        if (playing) {
            clip.stop();
        }
        clip.setMicrosecondPosition(clamped * 1000);
        pausedAtMs = clamped;
        if (wasPlaying) {
            clip.start();
        }
        centerViewOn(clamped);
        repaint();
    }

    private void zoom(double factor) {
        long newWidth = (long) (viewWidthMs * factor);
        viewWidthMs = Math.max(1000, Math.min(newWidth, 20_000));
        centerViewOn(currentMs());
        repaint();
    }

    private long currentMs() {
        return playing ? clip.getMicrosecondPosition() / 1000 : pausedAtMs;
    }

    private void autoScroll() {
        long now = currentMs();
        long viewEnd = viewStartMs + viewWidthMs;
        if (now > viewStartMs + viewWidthMs * 0.85 || now < viewStartMs) {
            centerViewOn(now);
        } else if (now > viewEnd) {
            centerViewOn(now);
        }
    }

    private void centerViewOn(long ms) {
        viewStartMs = Math.max(0, ms - viewWidthMs / 3);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        double pxPerMs = w / (double) viewWidthMs;

        paintWaveform(g, w, pxPerMs);
        paintLanes(g, w, pxPerMs);
        paintPlayhead(g, w, pxPerMs);
        paintHud(g);
    }

    private void paintWaveform(Graphics2D g, int w, double pxPerMs) {
        g.setColor(new Color(18, 18, 22));
        g.fillRect(0, 0, w, WAVEFORM_HEIGHT);
        g.setColor(new Color(90, 180, 255));
        int mid = WAVEFORM_HEIGHT / 2;
        long viewEndMs = viewStartMs + viewWidthMs;
        int colStartIdx = (int) (viewStartMs * envelope.length / Math.max(1, lengthMs));
        int colEndIdx = (int) (viewEndMs * envelope.length / Math.max(1, lengthMs));
        for (int col = Math.max(0, colStartIdx); col < Math.min(envelope.length, colEndIdx + 1); col++) {
            long colMs = (long) (col * (double) lengthMs / envelope.length);
            int x = (int) ((colMs - viewStartMs) * pxPerMs);
            int amp = (int) (envelope[col] * (WAVEFORM_HEIGHT / 2 - 4));
            g.drawLine(x, mid - amp, x, mid + amp);
        }
    }

    private void paintLanes(Graphics2D g, int w, double pxPerMs) {
        for (int lane = 0; lane < LANES; lane++) {
            int y = WAVEFORM_HEIGHT + lane * LANE_ROW_HEIGHT;
            g.setColor(lane % 2 == 0 ? new Color(26, 26, 30) : new Color(20, 20, 24));
            g.fillRect(0, y, w, LANE_ROW_HEIGHT);
            g.setColor(Color.DARK_GRAY);
            g.drawLine(0, y, w, y);
            g.setColor(Color.GRAY);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
            g.drawString(LANE_LABELS[lane] + "번 레인", 6, y + 14);
        }
        long viewEndMs = viewStartMs + viewWidthMs;
        for (Note n : notes) {
            if (n.endTimeMs < viewStartMs - 200 || n.timeMs > viewEndMs + 200) {
                continue;
            }
            int y = WAVEFORM_HEIGHT + n.lane * LANE_ROW_HEIGHT;
            g.setColor(laneColor(n.lane));
            if (n.isHold()) {
                int x1 = (int) ((n.timeMs - viewStartMs) * pxPerMs);
                int x2 = (int) ((n.endTimeMs - viewStartMs) * pxPerMs);
                g.fillRoundRect(Math.min(x1, x2), y + 18, Math.max(6, Math.abs(x2 - x1)), LANE_ROW_HEIGHT - 26, 8, 8);
                g.setColor(laneColor(n.lane).darker());
                g.fillRoundRect(x1 - 5, y + 14, 10, LANE_ROW_HEIGHT - 18, 6, 6); // head cap, a touch taller
            } else {
                int x = (int) ((n.timeMs - viewStartMs) * pxPerMs);
                g.fillRoundRect(x - 6, y + 18, 12, LANE_ROW_HEIGHT - 26, 6, 6);
            }
        }

        if (dragLane >= 0) {
            long a = Math.min(dragStartMs, dragCurrentMs);
            long b = Math.max(dragStartMs, dragCurrentMs);
            int x1 = (int) ((a - viewStartMs) * pxPerMs);
            int x2 = (int) ((b - viewStartMs) * pxPerMs);
            int y = WAVEFORM_HEIGHT + dragLane * LANE_ROW_HEIGHT;
            g.setColor(new Color(255, 255, 255, 130));
            g.fillRoundRect(x1, y + 18, Math.max(2, x2 - x1), LANE_ROW_HEIGHT - 26, 8, 8);
        }
    }

    private void paintPlayhead(Graphics2D g, int w, double pxPerMs) {
        long now = currentMs();
        int x = (int) ((now - viewStartMs) * pxPerMs);
        if (x < 0 || x > w) {
            return;
        }
        g.setColor(new Color(255, 255, 255, 220));
        g.fillRect(x, 0, 2, WAVEFORM_HEIGHT + LANES * LANE_ROW_HEIGHT);
    }

    private void paintHud(Graphics2D g) {
        int y = WAVEFORM_HEIGHT + LANES * LANE_ROW_HEIGHT + 20;
        g.setColor(Color.LIGHT_GRAY);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 13f));
        g.drawString(String.format("%s / %s   노트 %d개   보기 %.1f초   클릭=탭 노트  드래그=롱노트   [Space]재생 [←→]탐색 [+/-]확대",
                fmt(currentMs()), fmt(lengthMs), notes.size() + sideNotes.size(), viewWidthMs / 1000.0), 8, y);
    }

    private static String fmt(long ms) {
        long totalSec = ms / 1000;
        return String.format("%d:%02d", totalSec / 60, totalSec % 60);
    }

    private static Color laneColor(int lane) {
        return switch (lane) {
            case 0 -> new Color(255, 99, 132);
            case 1 -> new Color(255, 205, 86);
            case 2 -> new Color(75, 192, 192);
            default -> new Color(153, 102, 255);
        };
    }

    /** One magnitude value per column across the whole song (peak |sample| in that slice), for a cheap waveform. */
    private static float[] buildEnvelope(byte[] bytes, AudioFormat format, int columns) {
        int channels = format.getChannels();
        int bytesPerFrame = 2 * channels;
        boolean bigEndian = format.isBigEndian();
        int frameCount = bytes.length / bytesPerFrame;
        float[] env = new float[Math.max(1, columns)];
        if (frameCount == 0) {
            return env;
        }
        int framesPerCol = Math.max(1, frameCount / env.length);
        for (int col = 0; col < env.length; col++) {
            int start = col * framesPerCol;
            int end = Math.min(frameCount, start + framesPerCol);
            float peak = 0;
            for (int f = start; f < end; f++) {
                int base = f * bytesPerFrame;
                short s = bigEndian
                        ? (short) (((bytes[base] & 0xFF) << 8) | (bytes[base + 1] & 0xFF))
                        : (short) (((bytes[base + 1] & 0xFF) << 8) | (bytes[base] & 0xFF));
                float v = Math.abs(s / 32768f);
                if (v > peak) {
                    peak = v;
                }
            }
            env[col] = peak;
        }
        return env;
    }
}
