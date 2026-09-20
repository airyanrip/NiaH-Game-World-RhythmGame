package djmax;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Cursor;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.Shape;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The DJMAX-style 4-lane play field: countdown, falling notes, hit feel (sound/flash/burst), results screen. */
public final class RhythmPanel extends JPanel {
    private static final int LANES = RhythmSettings.LANES;
    private static final int LANE_WIDTH = 120;
    private static final int PANEL_HEIGHT = 640;
    // 30% of the panel's height up from the bottom edge (70% down from the top).
    private static final int JUDGE_Y = (int) (PANEL_HEIGHT * 0.70);
    private static final double BASE_PIXELS_PER_MS = 0.45;

    // A DJMAX Respect V-style vertical health gauge flanking the lanes on both sides — the lane
    // area itself (JUDGE_Y, note x positions, etc.) stays exactly as it was; paintComponent just
    // draws it inside a translated Graphics2D so none of the lane-drawing math below needs to know
    // the bars exist. Only real challenge play (this panel) has a health gauge — the chart-less
    // practice pad next to the song list never judges anything, so there is nothing for it to lose.
    private static final int HEALTH_BAR_WIDTH = 22;
    private static final int HEALTH_BAR_MARGIN = 14;
    private static final int LANE_AREA_X = HEALTH_BAR_WIDTH + HEALTH_BAR_MARGIN;
    private static final int LANE_AREA_WIDTH = LANES * LANE_WIDTH;
    private static final int TOTAL_WIDTH = LANE_AREA_WIDTH + 2 * LANE_AREA_X;
    private static final double MAX_HEALTH = 100.0;
    private static final double HEALTH_LOSS_MISS = 10.0;
    private static final double HEALTH_LOSS_HOLD_FAIL = 6.0;
    // DJMAX Respect V: HP recovers from landing MAX (100%) judgments — GREAT/GOOD don't heal, only
    // the top tier does, so a recovery run means a run of PERFECTs, not just "not missing." Kept
    // small relative to HEALTH_LOSS_MISS on purpose — offsetting one miss takes several PERFECTs in
    // a row, not one, so the gauge still trends down under sloppy play and up under clean play.
    private static final double HEALTH_GAIN_PERFECT = 1.5;

    /** Silent scroll-in after the countdown: notes already need to be visible before the audio can
     *  possibly reach them, or the earliest ones appear already halfway down (unreactable). The
     *  chart's own t=0 lines up with the audio's t=0, so counting "now" up from -LEAD_IN_MS to 0
     *  during this window — no clip running yet — lets a note at t=0 arrive at the judgment line
     *  exactly as the audio starts, having scrolled the same distance every later note will. */
    private static final long LEAD_IN_MS = 2000;

    private static final long LANE_FLASH_MS = 130;
    // Two selectable per-hit feedback styles (RhythmSettings.HitEffectStyle) sharing one
    // EffectKind — a water-ripple spread reads as a slightly slower phenomenon than the old spark
    // burst, hence the longer duration.
    private static final long HIT_RIPPLE_MS = 320;
    private static final long HIT_BURST_MS = 240;
    private static final long MISS_FLASH_MS = 280;
    // A PERFECT hit's screen-wide judge-line flash + lingering afterglow — much longer than the
    // other feedback effects since the whole point is that it stays a while, not just pops.
    private static final long PERFECT_FLASH_MS = 650;
    private static final long COMBO_PULSE_MS = 160;
    private static final long MILESTONE_MS = 700;

    private final Chart chart;
    private final RhythmSettings settings;
    private final Clip clip;
    private final SoundFX sfx;
    private final VoiceLines voices;
    private final Timer loop;
    private final Consumer<Result> onFinished;
    private final int[] laneKeys;
    private final String[] laneLabels;
    private final int sideKey;
    private final double pixelsPerMs;
    private final long offsetMs;
    private final int countdownTotalMs;
    private final long perfectMs, greatMs, goodMs;
    // Side-track notes (Note.Kind.SIDE) are judged far more generously than a lane note — "정확도가
    // 조금 흔들려도 대부분 MAX 100% 판정을 쉽게 받을 수 있도록 보정" — since they're one big, deliberately
    // forgiving target rather than 4 precise ones.
    private final long sidePerfectMs, sideGreatMs, sideGoodMs;
    // How long after an early release a fast re-press still recovers the hold with no penalty
    // instead of it being a BREAK — "뗐다가 극도로 빠르게 다시 누르면 남은 구간을 이어서 처리".
    private static final long HOLD_RECOVERY_GRACE_MS = 150;
    // What a fully-MAX (100% accuracy) note is worth — unscaled by combo. Every note kind (tap,
    // hold, side) scores through the same accuracyPct * BASE_NOTE_SCORE formula, which is what
    // gives a side note "일반 노트 1개와 완전히 동일한 점수 비중" despite looking far bigger on screen.
    private static final int BASE_NOTE_SCORE = 300;
    // Running total for the results screen's overall accuracy% (and so its rank) — every fully
    // resolved note (tap, hold, side; success or BREAK) adds its own final accuracyPct here exactly
    // once, so the average is a true "how close were you, on average" rather than the old fixed
    // per-tier-count-weighted approximation.
    private double accuracySum = 0;
    private int accuracyCount = 0;
    private final ColorVision colorVision;
    private final BackgroundAnimator background;
    // A fallback for when the animated background above isn't on (or has no frames for this song
    // yet) but the song still has a thumbnail — a YouTube import's, from YoutubeImporter — so the
    // in-game screen isn't just a bare lane gradient. Loaded once, up front; a missing/unreadable
    // file just leaves this null rather than failing the whole song.
    private final BufferedImage thumbnail;
    // Set once from the Hub's practice-mode toggle when this song started — a real chart, real
    // judging and scoring, just no health gauge and so no way to game-over mid-song. See
    // damageHealth() and paintHealthBars().
    private final boolean practiceMode;
    // Set once from the Hub's auto-play toggle — every note is judged PERFECT the instant it's due,
    // no key press needed at all (see the top of tick()'s per-note loop). Same non-scoring footing
    // as practice mode: HubPanel.playSelected() skips recording a best score / awarding XP for it.
    private final boolean autoPlay;
    // This song's currently-selected Difficulty's Difficulty.scoreMultiplier, read once at
    // construction — see awardNote(). Deliberately not re-read per note: the Hub's difficulty
    // picker only ever takes effect on the NEXT play, same as everything else about the chart.
    private final double scoreMultiplier;
    private Runnable onCloseRequested;

    // The live fit-to-window transform, recomputed every paint by updateRenderTransform() — these
    // are what actually map the fixed-size design onto however big the window is right now (the
    // Hub's window size no longer changes per screen, and this panel just fits whatever it's given
    // instead of being sized from a "resolution %" setting), and mouse hit-testing
    // (pauseMenuItemAt) has to invert the same transform.
    private double renderScale = 1.0;
    private int renderOffsetX;
    private int renderOffsetY;

    private boolean counting;
    private int countdownRemainingMs;
    private long lastTickWallMs;
    private boolean leadingIn;
    private long leadInStartWallMs;
    /** The lead-in formula's "now" at the exact instant playback took over — see {@link #nowMs()}. */
    private long leadInEndNowMs;
    private boolean paused;
    /** True while the post-CONTINUE 3-2-1 countdown (see {@link #beginResumeCountdown}) is
     *  playing — {@link #paused} stays true the whole time (input/tick stay frozen exactly like a
     *  normal pause), this just picks which overlay {@link #paintComponent} draws and which one
     *  {@link #tick()} counts down. */
    private boolean resuming;
    private static final long RESUME_COUNTDOWN_MS = 3000;
    private static final String[] PAUSE_MENU = {"CONTINUE", "RESTART", "MUSIC SELECT", "EXIT"};
    private int pauseMenuIndex;
    /** Full-width hover/click bands for each pause item, in logical (pre-transform) coordinates — filled in by {@link #paintPauseMenu}. */
    private final Rectangle[] pauseMenuBounds = new Rectangle[PAUSE_MENU.length];

    private int score = 0;
    private int combo = 0;
    private int maxCombo = 0;
    private int perfects, greats, goods, misses;
    private boolean started = false;
    private boolean finished = false;
    private boolean resultReported = false;
    private boolean newBest = false;
    private String lastJudgeText = "";
    private long lastJudgeUntil = 0;
    private long comboPulseAt = 0;
    private String milestoneText = "";
    private long milestoneUntil = 0;
    private Result result;
    private double health = MAX_HEALTH;
    private boolean gameOver = false;

    // DJMAX Respect V's FEVER gauge: fills from hits (46 normal notes, or a long note held for a
    // total of 92 "ticks" — half a normal note's worth per tick — reach MAX alone). No score
    // bonus, no forced speed change, no time limit once triggered, and the player cannot cancel
    // it — it can only escalate, up to level 5, by refilling the gauge again while already active.
    private static final double FEVER_GAUGE_PER_NOTE = 100.0 / 46.0;
    private static final double FEVER_GAUGE_PER_HOLD_TICK = 100.0 / 92.0;
    private static final double FEVER_HOLD_TICK_MS = 100.0;
    private static final int FEVER_MAX_LEVEL = 5;
    private static final long FEVER_BANNER_MS = 900;
    private double feverGauge = 0;
    private int feverLevel = 0;
    private long feverBannerUntil = 0;

    private enum EffectKind { LANE_FLASH, HIT_EFFECT, MISS_FLASH, PERFECT_FLASH }
    /** {@code strength} scales a HIT_EFFECT's size/ring count — 1.0 for PERFECT, tapering down
     *  for GREAT/GOOD, so nailing the timing reads as visibly bigger than a scrappy press, not just
     *  a different color. Unused (always 1) for LANE_FLASH/MISS_FLASH. */
    private record Effect(long startedAtMs, int lane, Color color, EffectKind kind, float strength) {
    }
    private final List<Effect> effects = new ArrayList<>();

    /** Per-lane "is the key physically down right now" — a long note's tail needs this, not just keyPressed. */
    private final boolean[] laneKeyDown = new boolean[LANES];

    public RhythmPanel(Chart chart, RhythmSettings settings, File thumbnailFile, Consumer<Result> onFinished) throws Exception {
        this.chart = chart;
        this.settings = settings;
        this.onFinished = onFinished;
        this.laneKeys = settings.laneKeys();
        this.laneLabels = new String[LANES];
        for (int i = 0; i < LANES; i++) {
            laneLabels[i] = KeyLabels.of(laneKeys[i]);
        }
        this.sideKey = settings.sideKey();
        this.pixelsPerMs = BASE_PIXELS_PER_MS * settings.noteSpeed() * chart.speedMultiplier;
        this.offsetMs = settings.offsetMs();
        this.countdownTotalMs = settings.countdownSeconds() * 1000;
        int judgmentScale = settings.judgmentWindowScalePercent();
        this.perfectMs = 35L * judgmentScale / 100;
        this.greatMs = 75L * judgmentScale / 100;
        this.goodMs = 150L * judgmentScale / 100;
        this.sidePerfectMs = perfectMs * 4;
        this.sideGreatMs = greatMs * 3;
        this.sideGoodMs = goodMs * 5 / 2;
        this.colorVision = settings.colorVision();
        this.practiceMode = settings.practiceMode();
        this.autoPlay = settings.autoPlayEnabled();
        this.scoreMultiplier = settings.songDifficulty(chart.audioFile.getName()).scoreMultiplier;
        this.background = settings.backgroundEnabled()
                ? BackgroundAnimator.load(settings.dataDir().resolve("background").resolve("frames"))
                : null;
        this.thumbnail = settings.backgroundThumbnailEnabled() ? loadThumbnail(thumbnailFile) : null;

        setPreferredSize(new Dimension(TOTAL_WIDTH, PANEL_HEIGHT));
        setBackground(Color.BLACK);
        setFocusable(true);

        AudioFormat format;
        byte[] pcm;
        try (AudioInputStream in = AudioSystem.getAudioInputStream(chart.audioFile)) {
            format = in.getFormat();
            pcm = in.readAllBytes();
        }
        double speed = settings.playbackSpeed();
        if (speed != 1.0) {
            pcm = AudioResampler.resample(pcm, format, speed);
        }
        clip = AudioDevices.openClip(settings.outputMixerName());
        try (AudioInputStream resampled = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, pcm.length / format.getFrameSize())) {
            clip.open(resampled);
        }
        applyVolume(clip, settings.musicVolume());
        sfx = new SoundFX(settings.outputMixerName());
        sfx.setVolume(settings.sfxVolume());
        voices = new VoiceLines(settings.outputMixerName(), settings.voiceVolume(), settings.voiceEnabled());

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                // Quick restart works from any state — mid-play, paused, or on the results screen —
                // so a failed run can be immediately retried without going back through the Hub.
                if (code == KeyEvent.VK_R) {
                    restart();
                    return;
                }
                if (finished) {
                    if (onCloseRequested != null) {
                        onCloseRequested.run();
                    }
                    return;
                }
                if (paused) {
                    if (resuming) {
                        return; // ignore all input while the resume countdown plays
                    }
                    switch (code) {
                        case KeyEvent.VK_UP -> {
                            pauseMenuIndex = (pauseMenuIndex + PAUSE_MENU.length - 1) % PAUSE_MENU.length;
                            repaint();
                        }
                        case KeyEvent.VK_DOWN -> {
                            pauseMenuIndex = (pauseMenuIndex + 1) % PAUSE_MENU.length;
                            repaint();
                        }
                        case KeyEvent.VK_ENTER -> activatePauseMenuSelection();
                        case KeyEvent.VK_ESCAPE -> togglePause();
                        default -> {
                        }
                    }
                    return;
                }
                if (code == KeyEvent.VK_ESCAPE) {
                    if (!counting) {
                        togglePause();
                    }
                    return;
                }
                if (counting) {
                    return;
                }
                if (code == sideKey) {
                    handleSideHit();
                    return;
                }
                for (int lane = 0; lane < LANES; lane++) {
                    if (code == laneKeys[lane]) {
                        laneKeyDown[lane] = true;
                        handleHit(lane);
                        return;
                    }
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == sideKey) {
                    handleSideRelease();
                    return;
                }
                for (int lane = 0; lane < LANES; lane++) {
                    if (code == laneKeys[lane]) {
                        laneKeyDown[lane] = false;
                        handleRelease(lane);
                        return;
                    }
                }
            }
        });

        // Mouse works alongside the arrow keys on the pause menu: hover highlights, click selects.
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (!paused || resuming) {
                    return;
                }
                int hit = pauseMenuItemAt(e.getPoint());
                setCursor(Cursor.getPredefinedCursor(hit >= 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                if (hit >= 0 && hit != pauseMenuIndex) {
                    pauseMenuIndex = hit;
                    repaint();
                }
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!paused || resuming) {
                    return;
                }
                int hit = pauseMenuItemAt(e.getPoint());
                if (hit >= 0) {
                    pauseMenuIndex = hit;
                    activatePauseMenuSelection();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setCursor(Cursor.getDefaultCursor());
            }
        });

        loop = new Timer(1000 / settings.fpsLimit(), e -> tick());
    }

    private static void applyVolume(Clip target, double volume) {
        if (!target.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        FloatControl gain = (FloatControl) target.getControl(FloatControl.Type.MASTER_GAIN);
        float dB = volume <= 0.0001 ? gain.getMinimum() : (float) (20.0 * Math.log10(volume));
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
    }

    /** Runs when a key is pressed after the results screen is showing (the play window closes it). */
    public void setOnCloseRequested(Runnable onCloseRequested) {
        this.onCloseRequested = onCloseRequested;
    }

    /** Releases audio resources — call once the window holding this panel is gone.
     *  The actual {@code Clip.close()} calls run on a background thread: closing a
     *  {@code DirectAudioDevice$DirectClip} joins its internal playback thread and can block for
     *  seconds, and this method is invoked from the results-screen key handler on the EDT — doing
     *  that join there froze the whole window until the join finished (see the EDT watchdog log:
     *  a "game freezes on game over sometimes" report traced to exactly this stack). */
    public void close() {
        loop.stop();
        Clip closingClip = clip;
        SoundFX closingSfx = sfx;
        VoiceLines closingVoices = voices;
        Thread closer = new Thread(() -> {
            closingClip.close();
            closingSfx.close();
            closingVoices.close();
        }, "djmax-audio-close");
        closer.setDaemon(true);
        closer.start();
    }

    public void start() {
        if (countdownTotalMs > 0) {
            counting = true;
            countdownRemainingMs = countdownTotalMs;
            lastTickWallMs = System.currentTimeMillis();
        } else {
            beginLeadIn();
        }
        loop.start();
        requestFocusInWindow();
    }

    /** The window lost focus: pause automatically if the setting calls for it (music keeps playing
     *  if "continue sound when unfocused" is on; otherwise it stops like a normal ESC pause). */
    public void pauseForFocusLoss() {
        if (!settings.autoPauseOnFocusLoss() || !started || leadingIn || paused || finished || counting) {
            return;
        }
        paused = true;
        pauseMenuIndex = 0;
        if (!settings.continueSoundWhenUnfocused()) {
            clip.stop();
        }
        repaint();
    }

    /** Countdown (if any) has finished: start the silent scroll-in, audio still not playing.
     *  The "game start" voice bark fires right here, the instant the countdown ends. */
    private void beginLeadIn() {
        counting = false;
        started = true;
        leadingIn = true;
        leadInStartWallMs = System.currentTimeMillis();
        clip.setMicrosecondPosition(0);
        voices.playGameStart();
    }

    /** Lead-in reached t=0: the audio actually starts now, exactly where the scrolled-in notes are.
     *  @param leadInEndNow the wall-clock lead-in formula's own "now" at this exact instant — the
     *  tick loop only samples it once per frame, so by the time it crosses 0 and triggers this call
     *  it has usually already overshot to some small positive value (up to one frame's worth).
     *  {@code clip.getMicrosecondPosition()} starts back at ~0 the moment {@link Clip#start()} is
     *  called below, so without correcting for that overshoot {@link #nowMs()} would jump backward
     *  by it right at this handoff — every single game start, not just when a timing offset is set —
     *  which is exactly the "first note snaps upward" glitch. Stashing it here and folding it back
     *  in on the clip-position side of {@link #nowMs()} keeps the timeline perfectly continuous. */
    private void beginPlayback(long leadInEndNow) {
        leadInEndNowMs = leadInEndNow;
        leadingIn = false;
        clip.start();
    }

    /** ESC: freezes the clip where it is (its position is the "now" everything else reads, so
     *  simply not advancing it is the pause) and stops treating unhit notes as misses meanwhile.
     *  Unpausing (CONTINUE, or ESC again) doesn't resume immediately — it hands off to {@link
     *  #beginResumeCountdown}, which keeps everything frozen for one more 3-2-1 beat so the player
     *  gets their fingers back on the keys before notes start moving again. */
    private void togglePause() {
        if (!started || leadingIn) {
            return;
        }
        if (paused) {
            beginResumeCountdown();
        } else {
            paused = true;
            pauseMenuIndex = 0; // CONTINUE highlighted by default, same as DJMAX
            clip.stop();
        }
        repaint();
    }

    /** CONTINUE (or ESC) from the pause menu: reuses the same 3-2-1 overlay {@link #beginLeadIn}'s
     *  pre-song countdown uses. {@link #paused} is left true the whole time — {@link #tick()} and
     *  the key/mouse handlers keep treating this exactly like a normal pause (frozen notes, no
     *  input) until the countdown reaches zero, at which point playback actually resumes. */
    private void beginResumeCountdown() {
        resuming = true;
        countdownRemainingMs = (int) RESUME_COUNTDOWN_MS;
        lastTickWallMs = System.currentTimeMillis();
    }

    /** @return the pause menu item under {@code p} (panel/physical coordinates), or -1 for none. */
    private int pauseMenuItemAt(Point p) {
        double lx = (p.x - renderOffsetX) / renderScale - LANE_AREA_X;
        double ly = (p.y - renderOffsetY) / renderScale;
        for (int i = 0; i < pauseMenuBounds.length; i++) {
            if (pauseMenuBounds[i] != null && pauseMenuBounds[i].contains(lx, ly)) {
                return i;
            }
        }
        return -1;
    }

    /** Enter (or a mouse click) on the pause menu. */
    private void activatePauseMenuSelection() {
        switch (pauseMenuIndex) {
            case 0 -> togglePause();  // CONTINUE
            case 1 -> restart();      // RESTART
            case 2 -> leaveToHub();   // MUSIC SELECT
            default -> quit();        // EXIT
        }
    }

    /** MUSIC SELECT: hands control back to whoever owns this panel (the Hub window swaps its
     *  content back to the song list — see {@link HubWindow#playChart}). */
    private void leaveToHub() {
        loop.stop();
        clip.stop();
        if (onCloseRequested != null) {
            onCloseRequested.run();
        }
    }

    /** R: resets score/combo/judged notes and the clip, then starts over exactly like a fresh open. */
    private void restart() {
        clip.stop();
        for (Note n : chart.notes) {
            n.judged = false;
            n.holding = false;
            n.tailJudged = false;
            n.holdFailed = false;
            n.headAccuracy = -1;
            n.headTier = null;
            n.releasedEarly = false;
            n.releasedAtMs = 0;
            n.nextTickAtMs = -1;
        }
        score = 0;
        combo = 0;
        maxCombo = 0;
        perfects = 0;
        greats = 0;
        goods = 0;
        misses = 0;
        accuracySum = 0;
        accuracyCount = 0;
        lastJudgeText = "";
        lastJudgeUntil = 0;
        comboPulseAt = 0;
        milestoneText = "";
        milestoneUntil = 0;
        effects.clear();
        feverGauge = 0;
        feverLevel = 0;
        feverBannerUntil = 0;
        finished = false;
        resultReported = false;
        result = null;
        newBest = false;
        health = MAX_HEALTH;
        gameOver = false;
        paused = false;
        resuming = false;
        started = false;
        leadingIn = false;
        leadInEndNowMs = 0;
        if (countdownTotalMs > 0) {
            counting = true;
            countdownRemainingMs = countdownTotalMs;
            lastTickWallMs = System.currentTimeMillis();
        } else {
            beginLeadIn();
        }
        if (!loop.isRunning()) {
            loop.start();
        }
        repaint();
    }

    /** EXIT from the pause menu: leaves without finishing (no result is reported). */
    private void quit() {
        loop.stop();
        clip.stop();
        if (onCloseRequested != null) {
            onCloseRequested.run();
        }
    }

    /** During the lead-in this counts up from -LEAD_IN_MS to 0 in wall-clock time (no clip running
     *  yet); once playback actually starts it's the clip position instead, continuing from exactly
     *  where the lead-in side left off (see {@link #leadInEndNowMs} and {@link
     *  #beginPlayback(long)}) — the two must line up exactly at the switchover or notes visibly
     *  snap to a new position the instant it happens. {@code offsetMs} is folded into {@link
     *  #leadInEndNowMs} already by the time playback starts, so it's only added explicitly on the
     *  lead-in side here. */
    private long nowMs() {
        if (leadingIn) {
            return -LEAD_IN_MS + (System.currentTimeMillis() - leadInStartWallMs) + offsetMs;
        }
        return clip.getMicrosecondPosition() / 1000 + leadInEndNowMs;
    }

    private void tick() {
        if (counting) {
            long now = System.currentTimeMillis();
            countdownRemainingMs -= (int) (now - lastTickWallMs);
            lastTickWallMs = now;
            if (countdownRemainingMs <= 0) {
                beginLeadIn();
            }
            repaint();
            return;
        }
        if (resuming) {
            long now = System.currentTimeMillis();
            countdownRemainingMs -= (int) (now - lastTickWallMs);
            lastTickWallMs = now;
            if (countdownRemainingMs <= 0) {
                resuming = false;
                paused = false;
                clip.start();
            }
            repaint();
            return;
        }
        if (leadingIn) {
            long leadInNow = nowMs();
            if (leadInNow >= 0) {
                beginPlayback(leadInNow);
            }
            repaint();
            return;
        }
        if (paused) {
            return;
        }
        if (!started) {
            return;
        }
        long now = nowMs();
        int holdingCount = 0;
        for (Note n : chart.notes) {
            if (autoPlay && !n.judged && now >= n.timeMs) {
                // Auto play: the instant a note is due, judge it PERFECT on its own — no key press
                // needed. delta is hardcoded to 0 (not computed from `now`) so this is always exactly
                // PERFECT regardless of tick timing jitter, matching "완벽하게" judging.
                autoResolveHead(n, now);
            } else if (!n.judged && now - n.timeMs > outerWindowFor(n)) {
                // The head was never pressed in time — a BREAK, and for a long note that's the
                // whole note gone (nothing left to hold), not just a bad start.
                n.judged = true;
                n.tailJudged = true;
                n.holdFailed = n.isHold();
                recordBreak(n, HEALTH_LOSS_MISS);
            } else if (n.holding && !n.tailJudged) {
                if (now >= n.endTimeMs) {
                    // Held all the way through without an early release — a clean completion. Auto
                    // play passes the note's own endTimeMs instead of the real (possibly slightly
                    // overshot) tick time, so the release delta is always exactly 0 — guaranteed
                    // PERFECT, not just "usually close enough".
                    resolveHoldTail(n, autoPlay ? n.endTimeMs : now);
                } else {
                    holdingCount++;
                    // Tick combo: every FEVER_HOLD_TICK_MS of correctly-held time bumps the combo
                    // counter on its own, on top of the head/tail hits themselves — a while-loop
                    // (not a single if) so a stray frame hitch that skips past two tick boundaries
                    // at once still credits both instead of dropping one.
                    while (n.nextTickAtMs > 0 && now >= n.nextTickAtMs) {
                        combo++;
                        maxCombo = Math.max(maxCombo, combo);
                        n.nextTickAtMs += (long) FEVER_HOLD_TICK_MS;
                    }
                }
            } else if (n.releasedEarly && !n.tailJudged) {
                // Let go early, but not for long enough yet to count as abandoned — see
                // tryRecoverHold. Once this window passes with no re-press, it's a real BREAK.
                if (System.currentTimeMillis() - n.releasedAtMs > HOLD_RECOVERY_GRACE_MS) {
                    breakHold(n);
                }
            }
        }
        if (holdingCount > 0) {
            // FEVER's long-note "ticks": gauge trickles in continuously while correctly held, at
            // half a normal note's per-unit rate (92 ticks worth to fill the gauge alone) — and
            // twice as fast with two long notes held at once, same as two separate hits would be.
            addFeverGauge(holdingCount * FEVER_GAUGE_PER_HOLD_TICK * (1000.0 / settings.fpsLimit()) / FEVER_HOLD_TICK_MS);
        }
        if (!finished && !clip.isRunning() && now > 0) {
            finished = true;
            int accuracyPct = accuracyCount == 0 ? 0 : (int) Math.round(accuracySum / accuracyCount);
            result = new Result(score, maxCombo, perfects, greats, goods, misses, accuracyPct, true);
            // A failed run (game over) never reaches here — clip.isRunning() is already false the
            // instant triggerGameOver() stops it, so this only fires on a genuine clear, matching
            // the usual rhythm-game convention that a fail doesn't set a record. Auto play never
            // gets here as a "fail" either way (it never misses), but is excluded same as practice
            // mode so a hands-off run can't plant a fake best score.
            if (!practiceMode && !autoPlay) {
                String songFileName = chart.audioFile.getName();
                Difficulty diff = settings.songDifficulty(songFileName);
                newBest = settings.recordScore(songFileName, diff, result.score(), result.rank(),
                        result.perfects(), result.greats(), result.goods(), result.misses());
            }
            voices.playClear(clearGradeFor(result));
        }
        if (finished && !resultReported) {
            resultReported = true;
            loop.stop();
            try {
                // onFinished is the Hub's — it logs and awards affection, things this class has no
                // control over. Whatever it does must never stop the results screen below from
                // showing: this was calling it before the repaint() at the end of this method, so
                // an exception here used to skip that repaint entirely — and since loop.stop() had
                // already run, no later tick would ever repaint again either, leaving the last
                // gameplay frame on screen forever instead of the results/game-over screen.
                onFinished.accept(result);
            } catch (Exception ignored) {
            }
        }
        repaint();
    }

    /** A lane key was pressed: a fast re-press within {@link #HOLD_RECOVERY_GRACE_MS} of letting go
     *  of that lane's hold takes priority over starting a new judgment (see {@link #tryRecover}) —
     *  otherwise this is either a tap or a hold's head, found and scored by {@link #resolveHead}. */
    private void handleHit(int lane) {
        long nowWall = System.currentTimeMillis();
        effects.add(new Effect(nowWall, lane, Color.WHITE, EffectKind.LANE_FLASH, 1f));
        if (tryRecover(n -> n.kind == Note.Kind.NORMAL && n.lane == lane)) {
            return;
        }
        long now = nowMs();
        Note best = findBestUnjudged(now, n -> n.kind == Note.Kind.NORMAL && n.lane == lane, goodMs);
        if (best == null) {
            return; // empty press: no note nearby — still flashed the lane above, no penalty
        }
        resolveHead(best, now, nowWall, Math.abs(now - best.timeMs), false);
    }

    /** The side key was pressed — same as {@link #handleHit} but matched by {@link Note.Kind#SIDE}
     *  instead of a lane, and judged against the much more generous side windows. */
    private void handleSideHit() {
        long nowWall = System.currentTimeMillis();
        effects.add(new Effect(nowWall, -1, Color.WHITE, EffectKind.LANE_FLASH, 1f));
        if (tryRecover(n -> n.kind == Note.Kind.SIDE)) {
            return;
        }
        long now = nowMs();
        Note best = findBestUnjudged(now, n -> n.kind == Note.Kind.SIDE, sideGoodMs);
        if (best == null) {
            return;
        }
        resolveHead(best, now, nowWall, Math.abs(now - best.timeMs), true);
    }

    /** @return the closest not-yet-judged note matching {@code filter}, or {@code null} if none is
     *  within {@code outerMs} of {@code now} — shared search for both {@link #handleHit} and
     *  {@link #handleSideHit}, which differ only in what they're allowed to match. */
    private Note findBestUnjudged(long now, java.util.function.Predicate<Note> filter, long outerMs) {
        Note best = null;
        long bestDelta = Long.MAX_VALUE;
        for (Note n : chart.notes) {
            if (n.judged || !filter.test(n)) continue;
            long delta = Math.abs(now - n.timeMs);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = n;
            }
        }
        return (best != null && bestDelta <= outerMs) ? best : null;
    }

    /** A very fast re-press after an early release picks a still-recoverable hold back up with no
     *  penalty (see {@link Note#releasedEarly}) instead of being treated as a fresh tap/head press —
     *  matches "뗐다가 극도로 빠르게 다시 누르면 남은 구간을 이어서 처리". @return whether one was recovered. */
    private boolean tryRecover(java.util.function.Predicate<Note> filter) {
        long nowWall = System.currentTimeMillis();
        for (Note n : chart.notes) {
            if (n.releasedEarly && !n.tailJudged && filter.test(n)
                    && nowWall - n.releasedAtMs <= HOLD_RECOVERY_GRACE_MS) {
                n.holding = true;
                n.releasedEarly = false;
                return true;
            }
        }
        return false;
    }

    /** Judges a note's head: a tap resolves (and scores) immediately, exactly as it always has —
     *  but a hold's head only banks its own accuracy/tier ({@link Note#headAccuracy}, {@link
     *  Note#headTier}) and starts its tick-combo schedule; the note isn't fully scored until its
     *  tail resolves too (see {@link #resolveHoldTail}), since DJMAX judges the two independently
     *  and the final grade is whichever of the two is worse. */
    /** Auto play's judging: exactly what a real, perfectly-timed key press would do (lane flash
     *  included), just triggered by the note's own due time instead of an actual key event, and
     *  with {@code delta} forced to 0 so it's always PERFECT. */
    private void autoResolveHead(Note n, long now) {
        long nowWall = System.currentTimeMillis();
        boolean side = n.kind == Note.Kind.SIDE;
        int lane = side ? -1 : n.lane;
        effects.add(new Effect(nowWall, lane, Color.WHITE, EffectKind.LANE_FLASH, 1f));
        resolveHead(n, now, nowWall, 0, side);
    }

    private void resolveHead(Note n, long now, long nowWall, long delta, boolean side) {
        n.judged = true;
        long maxMs = side ? sidePerfectMs : perfectMs;
        long midMs = side ? sideGreatMs : greatMs;
        long outerMs = side ? sideGoodMs : goodMs;
        String tier = delta <= maxMs ? "PERFECT" : delta <= midMs ? "GREAT" : "GOOD";
        double acc = accuracyFor(delta, maxMs, outerMs);
        if (n.isHold()) {
            n.holding = true;
            n.headAccuracy = acc;
            n.headTier = tier;
            n.nextTickAtMs = now + (long) FEVER_HOLD_TICK_MS;
            combo++;
            maxCombo = Math.max(maxCombo, combo);
            playHeadFeel(n, tier, nowWall);
            comboMilestone(nowWall);
        } else {
            n.tailJudged = true;
            playHeadFeel(n, tier, nowWall);
            awardNote(n, acc, tier);
            comboMilestone(nowWall);
        }
        addFeverGauge(FEVER_GAUGE_PER_NOTE); // the head — a tap's whole note, or a hold's head
    }

    /** The sound/flash/health feedback for one judged press — shared by a tap's single judgment and
     *  both of a hold's two (head and tail), since all three are "a key landed with this timing". */
    private void playHeadFeel(Note n, String tier, long nowWall) {
        int lane = n.kind == Note.Kind.SIDE ? -1 : n.lane;
        switch (tier) {
            case "PERFECT" -> {
                sfx.playPerfect();
                healHealth(HEALTH_GAIN_PERFECT);
                if (settings.perfectFlashEnabled()) {
                    effects.add(new Effect(nowWall, -1, judgeColor(tier), EffectKind.PERFECT_FLASH, 1f));
                }
            }
            case "GREAT" -> sfx.playGreat();
            default -> sfx.playGood();
        }
        lastJudgeText = tier;
        lastJudgeUntil = nowWall + 400;
        comboPulseAt = nowWall;
        float strength = switch (tier) {
            case "PERFECT" -> 1f;
            case "GREAT" -> 0.75f;
            default -> 0.55f;
        };
        effects.add(new Effect(nowWall, lane, judgeColor(tier), EffectKind.HIT_EFFECT, strength));
    }

    /** Books one fully-resolved note's score/tally/accuracy contribution — a tap right away, or a
     *  hold once its tail lands, never both, so a hold is never double-scored between its head and
     *  tail the way an earlier version of this class used to. {@code accuracyPct} scales {@link
     *  #BASE_NOTE_SCORE} continuously (DJMAX's "1%~100%" fine-grained judgment) instead of the old
     *  flat per-tier points, which paid the same regardless of how close within a tier a press was.
     *  The whole per-note award (base + combo bonus) then scales by {@link #scoreMultiplier} — a
     *  deliberate reward for playing on a harder difficulty, not just whatever total happens to fall
     *  out of that difficulty simply having more or fewer notes. */
    private void awardNote(Note n, double accuracyPct, String tier) {
        combo++;
        maxCombo = Math.max(maxCombo, combo);
        switch (tier) {
            case "PERFECT" -> perfects++;
            case "GREAT" -> greats++;
            default -> goods++;
        }
        int basePoints = (int) Math.round(BASE_NOTE_SCORE * accuracyPct / 100.0);
        score += (int) Math.round((basePoints + combo) * scoreMultiplier);
        accuracySum += accuracyPct;
        accuracyCount++;
    }

    /** DJMAX-style continuous accuracy: 100 flat inside the tight MAX window, sliding linearly down
     *  to 1 at the edge of the outer window, 0 (BREAK) once that's exceeded — replaces the old fixed
     *  three-tier point values (300/200/100), which paid the same regardless of how close within a
     *  tier the press actually was. */
    private static double accuracyFor(long absDeltaMs, long maxMs, long outerMs) {
        if (absDeltaMs <= maxMs) {
            return 100.0;
        }
        if (absDeltaMs >= outerMs) {
            return 0.0;
        }
        double t = (absDeltaMs - maxMs) / (double) (outerMs - maxMs);
        return Math.max(1.0, 99.0 * (1.0 - t));
    }

    private long outerWindowFor(Note n) {
        return n.kind == Note.Kind.SIDE ? sideGoodMs : goodMs;
    }

    private void comboMilestone(long nowWall) {
        if (combo > 0 && combo % 10 == 0) {
            milestoneText = combo + " COMBO!";
            milestoneUntil = nowWall + MILESTONE_MS;
        }
    }

    /** Fills toward FEVER (never past level {@link #FEVER_MAX_LEVEL}); each fill-up escalates it
     *  by one level. A miss breaks it instead — see {@link #breakFeverOnMiss()}. */
    private void addFeverGauge(double amount) {
        if (feverLevel >= FEVER_MAX_LEVEL) {
            return;
        }
        feverGauge += amount;
        if (feverGauge >= 100.0 - 1e-6) { // tolerance: repeated fractional adds (92 ticks) can land at 99.999999…
            feverGauge -= 100.0;
            feverLevel++;
            voices.playFeverEnter();
            feverBannerUntil = System.currentTimeMillis() + FEVER_BANNER_MS;
            if (feverLevel >= FEVER_MAX_LEVEL) {
                feverGauge = 100.0;
            }
        }
    }

    /** A miss cuts FEVER off immediately — level and gauge both drop back to zero, so it has to be
     *  built back up from scratch rather than just pausing mid-run. Called from every miss site
     *  (auto-miss and an early-released/failed hold); a no-op unless FEVER was actually active
     *  (level > 0) — a miss while still just filling toward the first level doesn't reset that
     *  progress, only an active FEVER getting cut off does. */
    private void breakFeverOnMiss() {
        if (feverLevel == 0) {
            return;
        }
        feverLevel = 0;
        feverGauge = 0;
    }

    /** A lane key was let go — resolves whatever long note is currently being held in that lane, if any. */
    private void handleRelease(int lane) {
        resolveRelease(n -> n.kind == Note.Kind.NORMAL && n.lane == lane);
    }

    /** The side key was let go — same as {@link #handleRelease} but for whichever side-track hold
     *  is currently down, if any. */
    private void handleSideRelease() {
        resolveRelease(n -> n.kind == Note.Kind.SIDE);
    }

    /** A held key was let go: within the outer window of the tail counts as a genuine (if perhaps
     *  sloppy) release, judged the same way a tap would be, right now — see {@link
     *  #resolveHoldTail}. Any earlier and it's not an immediate BREAK either: it's marked {@link
     *  Note#releasedEarly} and given {@link #HOLD_RECOVERY_GRACE_MS} to be recovered ({@link
     *  #tryRecover}) before the main loop in {@code tick()} finally calls it a {@link #breakHold}. */
    private void resolveRelease(java.util.function.Predicate<Note> filter) {
        long now = nowMs();
        for (Note n : chart.notes) {
            if (n.isHold() && n.holding && !n.tailJudged && filter.test(n)) {
                if (now >= n.endTimeMs - outerWindowFor(n)) {
                    resolveHoldTail(n, now);
                } else {
                    n.holding = false;
                    n.releasedEarly = true;
                    n.releasedAtMs = System.currentTimeMillis();
                }
                return;
            }
        }
    }

    /** Resolves a long note's tail: judges the release's own timing exactly like a tap would, then
     *  combines it with the head's already-banked accuracy — whichever of the two is worse decides
     *  the note's final score/tier ("머리는 100%, 꼬리는 90% → 최종 90%"), booked here exactly once
     *  via {@link #awardNote} so a hold is never scored twice (once at head, again at tail). */
    private void resolveHoldTail(Note n, long now) {
        n.holding = false;
        n.tailJudged = true;
        boolean side = n.kind == Note.Kind.SIDE;
        long maxMs = side ? sidePerfectMs : perfectMs;
        long midMs = side ? sideGreatMs : greatMs;
        long outerMs = side ? sideGoodMs : goodMs;
        long delta = Math.abs(now - n.endTimeMs);
        String tailTier = delta <= maxMs ? "PERFECT" : delta <= midMs ? "GREAT" : "GOOD";
        double tailAcc = accuracyFor(delta, maxMs, outerMs);
        double finalAcc = Math.min(n.headAccuracy, tailAcc);
        String finalTier = tailAcc <= n.headAccuracy ? tailTier : n.headTier;
        playHeadFeel(n, tailTier, System.currentTimeMillis());
        awardNote(n, finalAcc, finalTier);
        repaint();
    }

    /** A hold dropped mid-way and never recovered within the grace window — "그 즉시 BREAK가 발생하고
     *  나머지 롱노트 구간이 모두 증발": whatever the head's own accuracy was doesn't matter once this
     *  fires, the whole note is gone, same as never having pressed it at all. */
    private void breakHold(Note n) {
        n.holding = false;
        n.tailJudged = true;
        n.holdFailed = true;
        recordBreak(n, HEALTH_LOSS_HOLD_FAIL);
        repaint();
    }

    /** Shared bookkeeping for every way a note can fully fail — dropped mid-hold ({@link
     *  #breakHold}) or never touched at all (the auto-miss check in {@code tick()}): breaks combo
     *  and FEVER, plays the miss feedback, and still counts toward the run's overall accuracy% (as
     *  0) so a BREAK actually pulls that average down instead of just being invisible to it.
     *  <p>
     *  Side notes are exempt entirely — missing one is completely free: no combo break, no health
     *  loss, no FEVER reset, and it doesn't even show up as a BREAK in the tally/accuracy% a normal
     *  note's miss would. Hitting one well still helps (score/combo/FEVER, same as any other note),
     *  but there's no downside to whiffing it — it just quietly disappears. */
    private void recordBreak(Note n, double healthLoss) {
        if (n.kind == Note.Kind.SIDE) {
            return;
        }
        long nowWall = System.currentTimeMillis();
        combo = 0;
        misses++;
        breakFeverOnMiss();
        sfx.playMiss();
        lastJudgeText = "BREAK";
        lastJudgeUntil = nowWall + 400;
        effects.add(new Effect(nowWall, n.lane, new Color(255, 80, 80), EffectKind.MISS_FLASH, 1f));
        accuracyCount++;
        damageHealth(healthLoss);
    }

    /** Drains the health gauge — a plain miss costs more than an early-released hold, since some of
     *  the hold was still completed correctly. Ends the run the instant it hits empty. A no-op in
     *  practice mode: misses still judge and break combo normally, they just carry no fail risk.
     *  Also a no-op in auto play — which should never actually reach here at all, since every note
     *  is judged PERFECT before it can miss, but guarded the same way as a belt-and-suspenders. */
    private void damageHealth(double amount) {
        if (practiceMode || autoPlay || gameOver || finished) {
            return;
        }
        health = Math.max(0, health - amount);
        if (health <= 0) {
            triggerGameOver();
        }
    }

    /** Heals the health gauge — only a PERFECT (DJMAX's "MAX"/100%) judgment calls this; GREAT and
     *  GOOD don't. Never overheals past {@link #MAX_HEALTH}, and does nothing once the run is over
     *  (game over or the song already finished) or in practice mode, where health isn't tracked. */
    private void healHealth(double amount) {
        if (practiceMode || gameOver || finished) {
            return;
        }
        health = Math.min(MAX_HEALTH, health + amount);
    }

    /** Health hit zero: stops the song right where it is and shows the GAME OVER screen — {@link
     *  #tick()}'s existing "finished && !resultReported" handling takes it from here exactly like
     *  a normal clear, just with {@link #gameOver} true so {@link #paintComponent} shows the fail
     *  screen instead of the results screen. */
    private void triggerGameOver() {
        gameOver = true;
        finished = true;
        clip.stop();
        int accuracyPct = accuracyCount == 0 ? 0 : (int) Math.round(accuracySum / accuracyCount);
        result = new Result(score, maxCombo, perfects, greats, goods, misses, accuracyPct, false);
        voices.playClear(VoiceLines.ClearGrade.LOW_OR_FAIL);
    }

    /** Recomputes {@link #renderScale}/{@link #renderOffsetX}/{@link #renderOffsetY} from this
     *  panel's current actual size — called once per paint; {@link #pauseMenuItemAt} reads the same
     *  three fields to turn a raw mouse point back into design-space coordinates. */
    // Below this, the side info card (title/difficulty/time/tally) is skipped entirely rather than
    // squeezed in — a sliver of margin too narrow for it to read cleanly is worse than no card.
    private static final int SIDE_INFO_MIN_MARGIN = 170;
    private static final int SIDE_INFO_WIDTH = 190;

    /** The song title/difficulty/elapsed-remaining time/live judgment tally, drawn in whichever
     *  letterboxed margin {@link RhythmSettings#gameHorizontalAnchor()} leaves empty beside the lane
     *  field — LEFT anchor empties the right margin, RIGHT anchor empties the left, and CENTER
     *  splits both evenly (this picks the wider of the two, which is the right margin at a tie).
     *  Skipped once results/game-over is showing (that screen already covers the same information),
     *  whenever neither margin is wide enough to hold it without overlapping the lane field, and
     *  whenever the player has turned it off in Settings ({@link RhythmSettings#sideInfoPanelEnabled()}). */
    private void paintSideInfoPanel(Graphics2D g) {
        if (finished || !settings.sideInfoPanelEnabled()) {
            return;
        }
        // Every fixed pixel/font size below is scaled by renderScale — the same factor the lane
        // field itself grows by — so this card grows and shrinks right along with it instead of
        // staying pinned at its design-time size no matter how big the letterboxed lane field gets
        // ("화면 비율에 비해 너무 작을 때가 많습니다"). This method draws in raw window coordinates
        // (before the lane field's own g.translate/scale), so it has to do that scaling itself
        // rather than inheriting it from a transform.
        int laneLeft = renderOffsetX;
        int laneRight = (int) Math.round(renderOffsetX + TOTAL_WIDTH * renderScale);
        int leftMargin = laneLeft;
        int rightMargin = getWidth() - laneRight;
        boolean useRight = rightMargin >= leftMargin;
        int marginWidth = useRight ? rightMargin : leftMargin;
        if (marginWidth < sp(SIDE_INFO_MIN_MARGIN)) {
            return;
        }

        int cardW = Math.min(sp(SIDE_INFO_WIDTH), marginWidth - sp(20));
        int cardX = useRight ? laneRight + (rightMargin - cardW) / 2 : (leftMargin - cardW) / 2;
        int cardY = Math.max(sp(20), renderOffsetY);
        int pad = sp(14);
        int corner = sp(14);

        g.setFont(g.getFont().deriveFont(Font.BOLD, sf(13f)));
        FontMetrics titleFm = g.getFontMetrics();
        String title = chart.title == null ? "" : chart.title;
        List<String> titleLines = wrapText(titleFm, title, cardW - pad * 2, 2);

        int lineH = sp(20);
        int gapSmall = sp(8);
        int gapMed = sp(12);
        int gapLarge = sp(14);
        int tickH = sp(4);
        int cardH = pad * 2 + titleLines.size() * lineH + gapSmall + lineH + gapMed + lineH + gapLarge
                + lineH + 4 * (lineH - tickH);

        int opacityPct = settings.sideInfoPanelOpacityPercent();
        int bgAlpha = Math.max(0, Math.min(255, Math.round(255 * opacityPct / 100f)));
        int borderAlpha = Math.max(0, bgAlpha / 5);
        g.setColor(new Color(20, 8, 18, bgAlpha));
        g.fillRoundRect(cardX, cardY, cardW, cardH, corner, corner);
        g.setColor(new Color(255, 255, 255, borderAlpha));
        g.drawRoundRect(cardX, cardY, cardW, cardH, corner, corner);

        int tx = cardX + pad;
        int ty = cardY + pad + titleFm.getAscent();

        g.setColor(Color.WHITE);
        for (String line : titleLines) {
            g.drawString(line, tx, ty);
            ty += lineH;
        }
        ty += gapSmall;

        g.setFont(g.getFont().deriveFont(Font.PLAIN, sf(12f)));
        g.setColor(new Color(200, 190, 200));
        g.drawString(Lang.t(settings, "hub.difficulty") + " " + difficultyLabel(), tx, ty);
        ty += lineH + gapMed;

        long elapsed = Math.max(0, Math.min(chart.lengthMs, nowMs()));
        long remaining = Math.max(0, chart.lengthMs - elapsed);
        g.setColor(Color.WHITE);
        g.drawString(formatTime(elapsed) + " / " + formatTime(chart.lengthMs), tx, ty);
        ty += lineH - tickH;
        g.setColor(new Color(160, 150, 160));
        g.setFont(g.getFont().deriveFont(Font.PLAIN, sf(11f)));
        g.drawString("-" + formatTime(remaining), tx, ty);
        ty += lineH + gapLarge;

        g.setFont(g.getFont().deriveFont(Font.BOLD, sf(12f)));
        g.setColor(new Color(255, 45, 138));
        g.drawString("PERFECT " + perfects, tx, ty);
        ty += lineH - tickH;
        g.setColor(new Color(190, 140, 230));
        g.drawString("GREAT " + greats, tx, ty);
        ty += lineH - tickH;
        g.setColor(new Color(255, 180, 210));
        g.drawString("GOOD " + goods, tx, ty);
        ty += lineH - tickH;
        g.setColor(Color.LIGHT_GRAY);
        g.drawString("BREAK " + misses, tx, ty);
    }

    /** Scales a design-time pixel size by {@link #renderScale} — see {@link #paintSideInfoPanel}. */
    private int sp(int baseSize) {
        return (int) Math.round(baseSize * renderScale);
    }

    /** Scales a design-time font point size by {@link #renderScale} — see {@link #paintSideInfoPanel}. */
    private float sf(float baseSize) {
        return (float) (baseSize * renderScale);
    }

    private String difficultyLabel() {
        Difficulty diff = settings.songDifficulty(chart.audioFile.getName());
        return switch (diff) {
            case EASY -> Lang.t(settings, "settings.difficulty.easy");
            case NORMAL -> Lang.t(settings, "settings.difficulty.normal");
            case HARD -> Lang.t(settings, "settings.difficulty.hard");
            case FAST -> Lang.t(settings, "settings.difficulty.fast");
        };
    }

    private static String formatTime(long ms) {
        long totalSec = Math.max(0, ms) / 1000;
        return String.format("%d:%02d", totalSec / 60, totalSec % 60);
    }

    /** Greedily wraps {@code text} to fit within {@code maxWidth}, truncating with an ellipsis past
     *  {@code maxLines} rather than letting a long song title overrun the card. */
    private static List<String> wrapText(FontMetrics fm, String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        String remaining = text;
        while (!remaining.isEmpty() && lines.size() < maxLines) {
            int fit = remaining.length();
            while (fit > 0 && fm.stringWidth(remaining.substring(0, fit)) > maxWidth) {
                fit--;
            }
            if (fit == remaining.length()) {
                lines.add(remaining);
                remaining = "";
            } else {
                boolean lastLine = lines.size() == maxLines - 1;
                if (lastLine) {
                    String ellipsis = "…";
                    int cut = fit;
                    while (cut > 0 && fm.stringWidth(remaining.substring(0, cut) + ellipsis) > maxWidth) {
                        cut--;
                    }
                    lines.add(remaining.substring(0, cut) + ellipsis);
                    remaining = "";
                } else {
                    int breakAt = remaining.lastIndexOf(' ', fit);
                    if (breakAt <= 0) {
                        breakAt = fit;
                    }
                    lines.add(remaining.substring(0, breakAt).strip());
                    remaining = remaining.substring(breakAt).strip();
                }
            }
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private void updateRenderTransform() {
        double scale = Math.min(getWidth() / (double) TOTAL_WIDTH, getHeight() / (double) PANEL_HEIGHT);
        renderScale = Math.max(scale, 0.01);
        int slack = (int) (getWidth() - TOTAL_WIDTH * renderScale); // leftover width once letterboxed
        renderOffsetX = switch (settings.gameHorizontalAnchor()) {
            case LEFT -> 0;
            case RIGHT -> slack;
            case CENTER -> slack / 2;
        };
        renderOffsetY = (int) ((getHeight() - PANEL_HEIGHT * renderScale) / 2);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                settings.antiAliasing() ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);

        // Fit the fixed TOTAL_WIDTH x PANEL_HEIGHT design at the largest scale that keeps it fully
        // inside whatever size this panel actually is right now, then centre it. Growing or
        // maximizing the Hub window while playing
        // now actually grows the play field (limited by whichever dimension runs out first — for a
        // narrow 4-lane field in a wide window that's almost always the height, so in practice it
        // fills edge-to-edge vertically with the lanes centred horizontally), instead of staying
        // pinned at whatever size the window happened to be when this screen was first shown.
        updateRenderTransform();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, getWidth(), getHeight());

        // The backdrop (animated loop, or a song's thumbnail) is drawn against the panel's real,
        // physical size — before the translate/scale below — so it always sits centred on the
        // actual screen/window and scales to that window's own aspect ratio, regardless of the
        // fixed-aspect letterbox the lanes render inside or which side settings.gameHorizontalAnchor()
        // pins those lanes to. It must stay independent of that anchor: it is the screen's backdrop,
        // not part of the play field, so it should never slide left/right along with the lanes.
        if (background != null) {
            background.paint(g, getWidth(), getHeight(), settings.backgroundAspectMode(), settings.backgroundBrightnessPercent());
        } else if (thumbnail != null) {
            BackgroundAnimator.paintImage(g, thumbnail, getWidth(), getHeight(),
                    settings.backgroundAspectMode(), settings.backgroundBrightnessPercent());
        }

        // Same reasoning as the backdrop above: drawn against the panel's real, physical size,
        // before the translate/scale below, so it lives in whichever letterboxed margin the lane
        // field's own gameHorizontalAnchor left empty (see paintSideInfoPanel) instead of being
        // squeezed into/scaled with the fixed-size lane design.
        //
        // Guarded — unlike everything else painted above and below it, this is a purely decorative
        // add-on with no gameplay role, so a bug in it (a divide-by-zero from an extreme
        // renderScale, a malformed song title, ...) must never be allowed to abort the rest of this
        // method: paintComponent has no other exception handling, and Swing's own paint dispatch
        // doesn't always surface an uncaught exception here the same way it does for a Timer/event
        // callback (it can end up on stderr instead of this app's own log, easy to miss) — so a
        // throw here could silently skip the lane field/notes/judge line drawn just below for every
        // subsequent frame, i.e. exactly "화면이 멈춘 것처럼 보인다" with no visible cause.
        try {
            paintSideInfoPanel(g);
        } catch (RuntimeException ex) {
            java.util.logging.Logger.getLogger("djmax").warning("side info card paint failed: " + ex);
        }

        g.translate(renderOffsetX, renderOffsetY);
        g.scale(renderScale, renderScale);
        int panelWidth = LANES * LANE_WIDTH;
        long nowWall = System.currentTimeMillis();

        if (!practiceMode) {
            paintHealthBars(g);
        }

        // Everything below draws exactly as it did before the health bars existed — it's just
        // aimed at a Graphics2D whose origin has been shifted past the left bar, so none of the
        // lane/note/UI coordinate math elsewhere in this class needs to know the bars are there.
        Graphics2D lane = (Graphics2D) g.create();
        lane.translate(LANE_AREA_X, 0);
        lane.clipRect(0, 0, panelWidth, PANEL_HEIGHT);

        paintLaneBackgrounds(lane, panelWidth);
        paintDirectionalArrows(lane, panelWidth);
        paintArenaFrame(lane, panelWidth);
        paintLaneFlashes(lane, nowWall);
        paintJudgeLine(lane, panelWidth);

        if (started) {
            long now = nowMs();
            // Side notes first, behind the lane notes drawn right after — a side note is a big
            // background-ish bar spanning every lane, and a lane note sitting on top of it (rather
            // than the other way around) is what makes the two kinds visually layer instead of one
            // just obscuring the other, matching DJMAX's own look.
            //
            // Within each of these two passes, notes are walked back-to-front by timeMs (chart.notes
            // is sorted ascending, so a reverse index walk visits the LATEST note first and the
            // EARLIEST one last) — painter's algorithm then draws last = on top, so if two notes
            // ever do end up close enough to visually overlap, the one arriving at the judgment line
            // sooner (further along, the one the player actually needs to react to first) is always
            // the one drawn on top, never hidden behind a note that isn't due yet.
            for (int i = chart.notes.size() - 1; i >= 0; i--) {
                Note n = chart.notes.get(i);
                if (n.kind != Note.Kind.SIDE) continue;
                if (n.isHold()) {
                    if (!n.tailJudged) {
                        paintNoteSafely(() -> paintSideHoldNote(lane, n, now));
                    }
                    continue;
                }
                if (n.judged) continue;
                double y = JUDGE_Y - (n.timeMs - now) * pixelsPerMs;
                if (y < -40 || y > PANEL_HEIGHT + 40) continue;
                paintNoteSafely(() -> paintSideNote(lane, n, y));
            }
            for (int i = chart.notes.size() - 1; i >= 0; i--) {
                Note n = chart.notes.get(i);
                if (n.kind == Note.Kind.SIDE) continue;
                if (n.isHold()) {
                    if (!n.tailJudged) {
                        paintNoteSafely(() -> paintHoldNote(lane, n, now));
                    }
                    continue;
                }
                if (n.judged) continue;
                long dtMs = n.timeMs - now;
                double y = JUDGE_Y - dtMs * pixelsPerMs;
                if (y < -40 || y > PANEL_HEIGHT + 40) continue;
                paintNoteSafely(() -> paintNote(lane, n, y));
            }
        }

        paintHitEffects(lane, nowWall);
        paintPerfectFlash(lane, panelWidth, nowWall);

        // All of this — lane key labels, the SCORE/combo/FEVER HUD, judgment/milestone/FEVER
        // popups — is live-play furniture with no reason to keep rendering once results/game over
        // is showing: it was drawn unconditionally before, so it kept sitting there (dimmed but
        // legible) underneath the results overlay, redundant with (and sometimes inconsistent
        // with — e.g. the live "COMBO" readout mid-song vs. the results card's own "MAX COMBO")
        // the results card's own numbers, reading as clutter crowding the results text from above.
        if (!finished) {
            lane.setFont(lane.getFont().deriveFont(Font.BOLD, 16f));
            for (int l = 0; l < LANES; l++) {
                lane.setColor(Color.LIGHT_GRAY);
                lane.drawString(laneLabels[l], l * LANE_WIDTH + LANE_WIDTH / 2 - 6, PANEL_HEIGHT - 10);
            }
            lane.setColor(Color.WHITE);
            lane.setFont(lane.getFont().deriveFont(Font.BOLD, 22f));
            lane.drawString("SCORE " + score, 12, 28);
            if (practiceMode) {
                lane.setColor(new Color(120, 220, 140));
                lane.setFont(lane.getFont().deriveFont(Font.BOLD, 12f));
                lane.drawString(Lang.t(settings, "game.practiceMode"), 12, 44);
            }
            paintCombo(lane, nowWall);
            paintFeverGauge(lane, panelWidth);

            if (nowWall < lastJudgeUntil) {
                lane.setColor(judgeColor(lastJudgeText));
                lane.setFont(lane.getFont().deriveFont(Font.BOLD, 28f));
                // Was a fixed "panelWidth/2 - 60" x offset — only actually centered "PERFECT" (7
                // characters); "GOOD"/"BREAK" etc. are narrower at this font size, so that assumed
                // half-width put them visibly left of center instead. Measuring each string's own
                // width is what drawCentered (used everywhere else this game shows a centered
                // line) already does.
                drawCentered(lane, lane.getFontMetrics(), lastJudgeText, panelWidth, JUDGE_Y - 40);
            }
            if (nowWall < milestoneUntil) {
                lane.setColor(new Color(255, 45, 138));
                lane.setFont(lane.getFont().deriveFont(Font.BOLD, 34f));
                FontMetrics fm = lane.getFontMetrics();
                drawCentered(lane, fm, milestoneText, panelWidth, 110);
            }
            if (nowWall < feverBannerUntil) {
                lane.setColor(new Color(255, 60, 160));
                lane.setFont(lane.getFont().deriveFont(Font.BOLD, 40f));
                FontMetrics fm = lane.getFontMetrics();
                String text = feverLevel <= 1 ? "FEVER!" : "FEVER Lv." + feverLevel + "!";
                drawCentered(lane, fm, text, panelWidth, 160);
            }
        }

        paintMissFlash(lane, panelWidth, nowWall);

        if (started && !finished) {
            lane.setFont(lane.getFont().deriveFont(Font.PLAIN, 12f));
            lane.setColor(new Color(140, 140, 140));
            lane.drawString(Lang.t(settings, "game.pause"), 10, PANEL_HEIGHT - 26);
        }

        if (counting || resuming) {
            paintCountdown(lane, panelWidth);
        }
        if (paused && !resuming) {
            paintPauseMenu(lane, panelWidth);
        }
        if (finished && result != null) {
            if (gameOver) {
                paintGameOver(lane, panelWidth);
            } else {
                paintResults(lane, panelWidth);
            }
        }

        lane.dispose();
        effects.removeIf(e -> ageMs(e, nowWall) > durationOf(e.kind()));
    }

    /** The two vertical health-gauge bars flanking the lanes, DJMAX Respect V-style: a miss or a
     *  failed hold drains it (see {@link #damageHealth}), and it never refills — hitting empty
     *  ends the run immediately, in {@link #triggerGameOver()}. */
    private void paintHealthBars(Graphics2D g) {
        int top = 20;
        int barH = PANEL_HEIGHT - 40;
        double frac = Math.max(0, Math.min(1, health / MAX_HEALTH));
        int filledH = (int) Math.round(barH * frac);
        Color fill = frac <= 0.25 ? new Color(255, 70, 70) : new Color(255, 95, 180);
        paintOneHealthBar(g, 6, top, barH, filledH, fill);
        paintOneHealthBar(g, TOTAL_WIDTH - HEALTH_BAR_WIDTH - 6, top, barH, filledH, fill);
    }

    private void paintOneHealthBar(Graphics2D g, int x, int top, int barH, int filledH, Color fill) {
        g.setColor(new Color(38, 20, 34));
        g.fillRoundRect(x, top, HEALTH_BAR_WIDTH, barH, 10, 10);
        if (filledH > 0) {
            g.setColor(fill);
            g.fillRoundRect(x, top + (barH - filledH), HEALTH_BAR_WIDTH, filledH, 10, 10);
        }
        g.setColor(new Color(255, 255, 255, 150));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(x, top, HEALTH_BAR_WIDTH, barH, 10, 10);
    }

    private void paintLaneBackgrounds(Graphics2D g, int panelWidth) {
        // Whichever background image just got drawn (the animated loop, or the thumbnail fallback)
        // needs to actually show through here — this used to check only `background`, so with the
        // animated loop off (the common case) the thumbnail was painted first and then immediately
        // covered by these fully-opaque lane rectangles, leaving the plain black lane look exactly
        // as if no background had been drawn at all.
        boolean translucent = background != null || thumbnail != null;
        for (int lane = 0; lane < LANES; lane++) {
            int x = lane * LANE_WIDTH;
            Color top = lane % 2 == 0 ? new Color(38, 27, 44) : new Color(28, 20, 32);
            Color bottom = lane % 2 == 0 ? new Color(18, 13, 20) : new Color(13, 10, 16);
            if (translucent) {
                top = withAlpha(top, 90);
                bottom = withAlpha(bottom, 60);
            }
            g.setPaint(new GradientPaint(x, 0, top, x, PANEL_HEIGHT, bottom));
            g.fillRect(x, 0, LANE_WIDTH, PANEL_HEIGHT);
        }
        g.setPaint(null);
        g.setColor(new Color(72, 55, 70));
        for (int lane = 1; lane < LANES; lane++) {
            g.drawLine(lane * LANE_WIDTH, 0, lane * LANE_WIDTH, PANEL_HEIGHT);
        }
    }

    // The neon magenta accent used for the arena frame/chevrons/judgment line — per a reference
    // screenshot the user supplied of a pink/purple skill-zone marker, adopted as this screen's
    // "energy" color instead of the plain white it used before, without touching the per-lane note
    // colors themselves (those still need to stay distinct from each other for readability).
    private static final Color NEON_PINK = new Color(255, 60, 190);
    // Cardinal (up/down/left/right) arrows get their own accent, distinct from the diagonal
    // corners' pink, so the two groups read as clearly different at a glance.
    private static final Color NEON_BLUE = new Color(70, 160, 255);
    // The note ring's hole background, and the cross glyph's own X-shaped gap — a dark plum rather
    // than white, matching the reference screenshot's own shadowed hole color.
    private static final Color NOTE_HOLE_BG = new Color(0x6E, 0x42, 0x73);

    /** The one arrow icon used at all 8 positions, in local unit coordinates (tip at the top,
     *  pointing up, roughly -1..1 on both axes) — a chevron arrowhead with a forked/notched tail,
     *  per the reference screenshot's arrow decals: the cardinal and diagonal markers there are the
     *  same shape, just different colors, not two different icons. Built once and reused (scaled/
     *  rotated/translated per placement) rather than rebuilt per arrow per frame. */
    private static final Path2D.Double DIRECTIONAL_ARROW_SHAPE = buildDirectionalArrowShape();

    private static Path2D.Double buildDirectionalArrowShape() {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(0, -1.0);
        p.lineTo(0.68, -0.05);
        p.lineTo(0.34, -0.05);
        p.lineTo(0.34, 0.62);
        p.lineTo(0, 0.32);
        p.lineTo(-0.34, 0.62);
        p.lineTo(-0.34, -0.05);
        p.lineTo(-0.68, -0.05);
        p.closePath();
        return p;
    }

    /** 8 directional arrow icons ringing the play field — up/down/left/right and the 4 diagonals —
     *  per the reference screenshot's compass-style arrangement of decals around its skill zone.
     *  Only the 4 cardinal ones point inward, toward the field's center (the top one points down,
     *  into the field — "위에 화살표의 경우 아래쪽으로 향하게"); the 4 diagonal corners point outward,
     *  away from the field, same as originally. The two groups are the same icon shape but different
     *  colors — blue for the cardinal ones, pink for the diagonal corners — not two different
     *  shapes. Low-alpha and static, so it reads as the zone's own boundary marking, not texture
     *  inside the play area. */
    private void paintDirectionalArrows(Graphics2D g, int panelWidth) {
        double cx = panelWidth / 2.0;
        double cy = PANEL_HEIGHT / 2.0;
        double edgeInset = 34;
        double size = 30;

        // {x, y, angleDegrees, isCardinal} — angle 0 = pointing up, clockwise from there. Cardinal
        // angles point inward (e.g. the top marker at angle 180 points down); diagonal angles point
        // outward (e.g. the top-left marker at angle 315 points up-left, away from the field).
        Object[][] placements = {
                {cx, edgeInset, 180.0, true},                                   // top
                {cx, PANEL_HEIGHT - edgeInset, 0.0, true},                      // bottom
                {edgeInset, cy, 90.0, true},                                    // left
                {panelWidth - edgeInset, cy, 270.0, true},                      // right
                {edgeInset, edgeInset, 315.0, false},                           // top-left
                {panelWidth - edgeInset, edgeInset, 45.0, false},               // top-right
                {edgeInset, PANEL_HEIGHT - edgeInset, 225.0, false},            // bottom-left
                {panelWidth - edgeInset, PANEL_HEIGHT - edgeInset, 135.0, false}, // bottom-right
        };

        java.awt.geom.AffineTransform old = g.getTransform();
        for (Object[] p : placements) {
            boolean cardinal = (boolean) p[3];
            g.setColor(cardinal ? withAlpha(NEON_BLUE, 90) : withAlpha(NEON_PINK, 90));
            g.translate((double) p[0], (double) p[1]);
            g.rotate(Math.toRadians((double) p[2]));
            g.scale(size, size);
            g.fill(DIRECTIONAL_ARROW_SHAPE);
            g.setTransform(old);
        }
    }

    /** A glowing double outline around the whole lane area — the closest a rectangular note highway
     *  can get to the reference screenshot's bright pink boundary line around its (rotated-square)
     *  skill zone, without actually turning the play field into an isometric diamond, which would
     *  break the falling-note gameplay this screen is built around. */
    private void paintArenaFrame(Graphics2D g, int panelWidth) {
        g.setColor(withAlpha(NEON_PINK, 35));
        g.setStroke(new BasicStroke(8f));
        g.drawRect(2, 2, panelWidth - 4, PANEL_HEIGHT - 4);
        g.setColor(withAlpha(NEON_PINK, 130));
        g.setStroke(new BasicStroke(2.5f));
        g.drawRect(2, 2, panelWidth - 4, PANEL_HEIGHT - 4);
    }

    /** The judgment line, reskinned from a flat white bar to a glowing magenta one (soft gradient
     *  glow above and below a bright white-hot core) matching this screen's new neon accent color. */
    private void paintJudgeLine(Graphics2D g, int panelWidth) {
        Paint old = g.getPaint();
        g.setPaint(new GradientPaint(0, JUDGE_Y - 8, withAlpha(NEON_PINK, 0), 0, JUDGE_Y, withAlpha(NEON_PINK, 170)));
        g.fillRect(0, JUDGE_Y - 8, panelWidth, 8);
        g.setPaint(new GradientPaint(0, JUDGE_Y + 4, withAlpha(NEON_PINK, 170), 0, JUDGE_Y + 12, withAlpha(NEON_PINK, 0)));
        g.fillRect(0, JUDGE_Y + 4, panelWidth, 8);
        g.setPaint(old);
        g.setColor(Color.WHITE);
        g.fillRect(0, JUDGE_Y, panelWidth, 4);
    }

    /** Runs one note's paint call, swallowing (and logging) any exception instead of letting it
     *  propagate out of {@code paintComponent} — an uncaught exception partway through painting the
     *  note list would abort every remaining note (and the judge line/HUD drawn after them) for that
     *  frame, and since {@code repaint()} keeps asking for the exact same frame, it can repeat on
     *  every subsequent one too: exactly "화면이 멈춘 것처럼 보인다" (the game looking frozen) with no
     *  logged crash, the same class of bug {@link #paintSideInfoPanel}'s own try-catch guards
     *  against. Worst case with this in place is one note's shape glitching or briefly vanishing,
     *  never the whole game stalling. */
    private static void paintNoteSafely(Runnable paint) {
        try {
            paint.run();
        } catch (RuntimeException ex) {
            java.util.logging.Logger.getLogger("djmax").warning("note paint failed: " + ex);
        }
    }

    private void paintNote(Graphics2D g, Note n, double y) {
        int lane = n.lane;
        if (settings.noteStyle() == RhythmSettings.NoteStyle.CLASSIC) {
            int x = lane * LANE_WIDTH + 10;
            int width = LANE_WIDTH - 20;
            int top = (int) Math.round(y) - 14;
            paintNoteCap(g, x, top, width, 28, 14, laneColor(lane));
        } else {
            int cx = lane * LANE_WIDTH + LANE_WIDTH / 2;
            paintNoteCross(g, cx, (int) Math.round(y), NOTE_CROSS_OUTER_RADIUS, laneColor(lane));
        }
    }

    /** A long note: a body bar from head to tail, a head cap, and (once being held) a glowing body
     *  and a head pinned to the judgment line — the head visually "locks in" while the tail keeps
     *  approaching, exactly the beatmania/DJMAX long-note look. */
    private void paintHoldNote(Graphics2D g, Note n, long now) {
        boolean holding = n.holding;
        // Pinned at the judge line for releasedEarly too, not just holding — otherwise the instant
        // a hold is let go early, this falls back to the timeMs-based formula below with `now`
        // already past n.timeMs (real time keeps advancing during the recovery grace window), which
        // sends headY shooting downward past the judge line — the bar suddenly looks like it grew
        // longer, then snaps back the moment breakHold()/a recovery re-press resolves it.
        double headY = (holding || n.releasedEarly) ? JUDGE_Y : JUDGE_Y - (n.timeMs - now) * pixelsPerMs;
        double tailY = JUDGE_Y - (n.endTimeMs - now) * pixelsPerMs;
        double top = Math.min(headY, tailY);
        double bottom = Math.max(headY, tailY);
        if (bottom < -40 || top > PANEL_HEIGHT + 40) {
            return;
        }

        // Ring style: narrower than the cross cap (see NOTE_CROSS_OUTER_RADIUS), like a bead on a
        // rail, rather than the old lane-filling pill width — matches the note shape better than a
        // bar exactly as wide as the cap sitting on it. Classic style: the original full pill width.
        boolean classic = settings.noteStyle() == RhythmSettings.NoteStyle.CLASSIC;
        int barWidth = classic ? LANE_WIDTH - 20 : NOTE_CROSS_OUTER_RADIUS + 10;
        int cx = n.lane * LANE_WIDTH + LANE_WIDTH / 2;
        int x = cx - barWidth / 2;
        paintHoldBar(g, x, (int) top, barWidth, Math.max(6, (int) (bottom - top)), 12, laneColor(n.lane), holding);

        if (!n.judged || holding) {
            if (classic) {
                paintNote(g, n, headY); // head cap — still the "press here" target, or pinned while held
            } else {
                // Arcade Drop long notes get their own head glyph — see paintArcadeHoldHead's doc —
                // instead of the plain tap-note ring: approachT drives the "converging onto the
                // center hole" indicator while still approaching (0 once actually held, since by
                // then the head press has already landed), spinAngle drives the arrows turning
                // clockwise in 90-degree steps only while actually being held.
                double approachT = holding ? 0.0
                        : Math.max(0.0, Math.min(1.0, (n.timeMs - now) / (double) ARCADE_APPROACH_MS));
                double spinAngle = holding ? arcadeSpinAngle(now - n.timeMs) : 0.0;
                paintArcadeHoldHead(g, cx, (int) Math.round(headY), ARCADE_HOLD_OUTER_RADIUS,
                        laneColor(n.lane), approachT, spinAngle, holding);
            }
        }
    }

    /** A side-track note's head/tap cap: one bar spanning every lane (not a single 100px column),
     *  colored by whether it's a tap-style ("일자" — yellow) or hold-style (sky-blue) side note, per
     *  DJMAX Respect V's own convention. */
    private void paintSideNote(Graphics2D g, Note n, double y) {
        int panelWidth = LANES * LANE_WIDTH;
        int top = (int) y - 16;
        Color base = n.isHold() ? SIDE_NOTE_HOLD_COLOR : SIDE_NOTE_TAP_COLOR;
        paintNoteCap(g, 6, top, panelWidth - 12, 32, 16, base);
    }

    /** A side-track hold: same head-to-tail body/head-cap shape as {@link #paintHoldNote}, just
     *  full-width and through {@link #paintSideNote} for the cap. */
    private void paintSideHoldNote(Graphics2D g, Note n, long now) {
        boolean holding = n.holding;
        // See paintHoldNote's identical fix — pinned for releasedEarly too, not just holding.
        double headY = (holding || n.releasedEarly) ? JUDGE_Y : JUDGE_Y - (n.timeMs - now) * pixelsPerMs;
        double tailY = JUDGE_Y - (n.endTimeMs - now) * pixelsPerMs;
        double top = Math.min(headY, tailY);
        double bottom = Math.max(headY, tailY);
        if (bottom < -40 || top > PANEL_HEIGHT + 40) {
            return;
        }
        int panelWidth = LANES * LANE_WIDTH;
        paintHoldBar(g, 6, (int) top, panelWidth - 12, Math.max(6, (int) (bottom - top)), 14, SIDE_NOTE_HOLD_COLOR, holding);

        if (!n.judged || holding) {
            paintSideNote(g, n, headY);
        }
    }

    /** Soft glow behind a note shape — 3 progressively larger, fainter rounded rects under the same
     *  {@code base} color, the cheapest approximation of a blurred glow Java2D supports without an
     *  actual blur filter, but enough to read as "this note has a bit of light around it" rather
     *  than a flat cutout shape. */
    private static void paintGlow(Graphics2D g, int x, int top, int width, int height, int arc, Color base, float alphaMul) {
        for (int i = 3; i >= 1; i--) {
            int pad = i * 3;
            int a = Math.round((28 - i * 6) * alphaMul);
            if (a <= 0) continue;
            g.setColor(withAlpha(base, a));
            g.fillRoundRect(x - pad, top - pad, width + pad * 2, height + pad * 2, arc + pad, arc + pad);
        }
    }

    /** One note "cap" — the pill shape a tap note, or a hold's/side note's head, actually is: a
     *  soft glow behind a vertically-gradiented body (lighter top fading to the true color, darker
     *  base) with a glossy highlight sheen near the top and a crisp dark outline — a step up from a
     *  flat single-color fill plus a flat highlight rectangle. */
    private static void paintNoteCap(Graphics2D g, int x, int top, int width, int height, int arc, Color base) {
        paintGlow(g, x, top, width, height, arc, base, 1f);
        Paint old = g.getPaint();
        g.setPaint(new GradientPaint(x, top, brighten(base, 0.35f), x, top + height, base.darker()));
        g.fillRoundRect(x, top, width, height, arc, arc);
        int sheenHeight = Math.max(2, Math.round(height * 0.55f));
        g.setPaint(new GradientPaint(x, top, withAlpha(Color.WHITE, 150), x, top + sheenHeight, withAlpha(Color.WHITE, 0)));
        g.fillRoundRect(x + 2, top + 2, Math.max(1, width - 4), sheenHeight, Math.max(2, arc - 4), Math.max(2, arc - 4));
        g.setPaint(old);
        g.setColor(base.darker().darker());
        g.setStroke(new BasicStroke(1.6f));
        g.drawRoundRect(x, top, width, height, arc, arc);
    }

    // Falling lane notes render as a ring/"donut" (per the original reference screenshot) with a
    // 4-way "pinwheel cross" glyph inside the hole (per a separate reference image of that exact
    // cross shape) — both kept together, not one replacing the other. Radius chosen to sit
    // comfortably inside a 120px lane with margin either side.
    private static final int NOTE_CROSS_OUTER_RADIUS = 42;

    // Arcade Drop long-note head — a separate glyph from the tap note's cross ring (per the user's
    // own reference photo): a disc with 4 chubby arrows pointing in from N/E/S/W toward a small
    // center hole, an "approach" indicator collapsing into that hole timed to land exactly on a
    // PERFECT hit, and — once actually being held — the arrows spinning fast one way, slowing to a
    // stop, then reversing (see paintArcadeHoldHead). Only a little bigger than the tap note's own
    // ring, not a dramatically larger disc — comfortably inside a 120px lane either way.
    private static final int ARCADE_HOLD_OUTER_RADIUS = 44;
    // The arrows themselves render at this fraction of the disc, not edge-to-edge — leaving a
    // visible margin between the arrow tails and the outer rim is what actually reads as "smaller
    // arrows," separate from (and in addition to) shrinking the disc itself.
    private static final double ARCADE_HOLD_ARROW_SCALE = 0.76;
    // The center hole/approach-indicator color — purple, not white, per draft PNG review.
    private static final Color ARCADE_CENTER_COLOR = new Color(190, 90, 255);
    // How long before the head's hit time the approach indicator starts visibly closing in — not
    // tied to the PERFECT/GREAT/GOOD judgment windows (those are about how forgiving a late/early
    // press is, this is purely a visual countdown), so it can be tuned independently.
    private static final long ARCADE_APPROACH_MS = 700;
    // One-directional, always clockwise: ease-out through a 90-degree turn, pause, then the next
    // 90-degree turn — never reversing. The arrow glyph has 4-fold rotational symmetry, so landing
    // on exactly 90 degrees looks identical to resting at 0, which is what makes each stop-and-go
    // read as a clean repeating step instead of a visible snap back.
    private static final double ARCADE_SPIN_STEP_RAD = Math.PI / 2;
    private static final long ARCADE_SPIN_ROTATE_MS = 650;
    private static final long ARCADE_SPIN_PAUSE_MS = 260;

    /** The arrows' rotation while a hold is actually being pressed: clockwise-only, in eased
     *  90-degree steps with a brief pause at each stop (see {@link #ARCADE_SPIN_STEP_RAD}), rather
     *  than a continuous spin — {@code elapsedMs} is any monotonically increasing time base (this
     *  note's own held duration is enough, it doesn't need to start at exactly 0). */
    private static double arcadeSpinAngle(long elapsedMs) {
        long cycleMs = ARCADE_SPIN_ROTATE_MS + ARCADE_SPIN_PAUSE_MS;
        long phase = Math.floorMod(elapsedMs, cycleMs);
        if (phase >= ARCADE_SPIN_ROTATE_MS) {
            return ARCADE_SPIN_STEP_RAD; // paused at the 90-degree mark
        }
        double t = phase / (double) ARCADE_SPIN_ROTATE_MS;
        double eased = 1 - Math.pow(1 - t, 3); // ease-out cubic: fast start, slows into the stop
        return ARCADE_SPIN_STEP_RAD * eased;
    }

    /** One chubby arrow in local unit coordinates (-1..1), tip toward the origin (center) and a
     *  short, wide tail near the rim, at the "top" (12 o'clock) position — unioning 3 more copies
     *  rotated 90/180/270 degrees gives all 4 (12/3/6/9 o'clock), each pointing straight in at the
     *  center. Built as two clearly distinct sharp-cornered pieces — a short constant-width tail
     *  segment, then a sudden outward flare into a much wider triangular head — rather than one
     *  smooth taper from tail to tip: a single continuous taper (the previous attempt) reads as
     *  just one triangle with no visible tail at all, which is exactly what "화살표가 머리 부분만
     *  보입니다" was pointing out. The flare/shoulder step is what actually makes it read as an
     *  arrow. Rounded off afterward by stroking that outline with a round-joined stroke and
     *  unioning the stroke shape back onto the fill — Java2D has no direct "rounded polygon"
     *  primitive, so inflating a sharp shape with a round stroke (which softens reflex/concave
     *  corners, like the shoulder notch here, into a curved flare too, not just convex ones) is the
     *  standard way to round every corner in one pass instead of hand-rounding each one. */
    private static final Area ARCADE_HOLD_ARROW_GLYPH = buildArcadeHoldArrowGlyph();

    // Triangle (head) : bar (tail) vertical-length ratio, approved via draft PNG review — the tip
    // is pulled back from -0.28 to -0.34 for a visible gap from the center hole, and the
    // shoulder/tail-front boundary is derived from that so the head is 65% and the tail 35% of the
    // resulting tip-to-tail-back span (was an even-ish split before, which read as "no visible
    // tail" — see buildArcadeHoldArrowGlyph's own doc).
    private static final double ARCADE_ARROW_TIP_Y = -0.34;
    private static final double ARCADE_ARROW_TAIL_BACK_Y = -0.95;
    private static final double ARCADE_ARROW_HEAD_FRACTION = 0.65;

    private static Area buildArcadeHoldArrowGlyph() {
        double span = ARCADE_ARROW_TAIL_BACK_Y - ARCADE_ARROW_TIP_Y; // negative
        double shoulderY = ARCADE_ARROW_TIP_Y + span * ARCADE_ARROW_HEAD_FRACTION;
        Path2D.Double sharp = new Path2D.Double();
        sharp.moveTo(-0.20, ARCADE_ARROW_TAIL_BACK_Y);  // tail, back-left  — short, constant-width tail
        sharp.lineTo(0.20, ARCADE_ARROW_TAIL_BACK_Y);   // tail, back-right
        sharp.lineTo(0.20, shoulderY);   // tail, front-right     — straight up, same width as the back
        sharp.lineTo(0.42, shoulderY);   // shoulder, right       — sudden flare, wider than the tail
        sharp.lineTo(0, ARCADE_ARROW_TIP_Y);  // tip, toward the center (blunt before rounding, gap from the hole)
        sharp.lineTo(-0.42, shoulderY);  // shoulder, left
        sharp.lineTo(-0.20, shoulderY);  // tail, front-left
        sharp.closePath();

        BasicStroke roundJoin = new BasicStroke(0.11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        Area arrow = new Area(sharp);
        arrow.add(new Area(roundJoin.createStrokedShape(sharp)));

        Area glyph = new Area(arrow);
        for (int i = 1; i < 4; i++) {
            glyph.add(new Area(java.awt.geom.AffineTransform.getRotateInstance(i * Math.PI / 2)
                    .createTransformedShape(arrow)));
        }
        return glyph;
    }

    /** The Arcade Drop hold note's head glyph. {@code approachT} is 1 while the note is still far
     *  off approaching (the bright indicator sits out near the arrow tips) down to 0 exactly at a
     *  PERFECT hit (it has fully collapsed onto — and lit up — the center hole); pass 0 once
     *  actually holding, since the head press has already landed by then. {@code spinAngleRad}
     *  rotates the 4 arrows (0 = resting, matching the tap note's own static cross) — only
     *  non-zero while actually being held. */
    private static void paintArcadeHoldHead(Graphics2D g, int cx, int cy, int outerR, Color base,
            double approachT, double spinAngleRad, boolean holding) {
        int d = outerR * 2;
        paintGlow(g, cx - outerR, cy - outerR, d, d, d, base, holding ? 1f : 0.8f);

        Ellipse2D outerEllipse = new Ellipse2D.Double(cx - outerR, cy - outerR, d, d);
        double centerHoleR = outerR * 0.15;
        Ellipse2D centerHole = new Ellipse2D.Double(cx - centerHoleR, cy - centerHoleR, centerHoleR * 2, centerHoleR * 2);

        // A dark "floor" disc, not a bright one — the reference photo's own asphalt-gray ground —
        // so the pink arrows read as the one clearly lit thing on it instead of blending into a
        // similarly-bright, similarly-colored background.
        Paint old = g.getPaint();
        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), (float) outerR,
                new float[]{0f, 1f},
                new Color[]{NOTE_HOLE_BG.brighter(), NOTE_HOLE_BG.darker()}));
        g.fill(outerEllipse);
        g.setPaint(old);

        java.awt.geom.AffineTransform t = java.awt.geom.AffineTransform.getTranslateInstance(cx, cy);
        t.rotate(spinAngleRad);
        t.scale(outerR * ARCADE_HOLD_ARROW_SCALE, outerR * ARCADE_HOLD_ARROW_SCALE);
        Shape arrows = t.createTransformedShape(ARCADE_HOLD_ARROW_GLYPH);

        // Translucent magenta/pink fill — the disc underneath shows faintly through it — with a
        // bright pink glowing rim: a soft wide band first, then a crisp bright core stroke on top,
        // same layered-glow idea as paintGlow but traced along the arrow outline itself rather than
        // a rounded-rect halo. "바닥이 투영되어 보이는 반투명 그래픽 + 밝은 분홍빛 발광 윤곽선".
        Color glowEdge = new Color(255, 140, 235);
        g.setColor(withAlpha(glowEdge, holding ? 90 : 60));
        g.setStroke(new BasicStroke(outerR * 0.16f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(arrows);
        g.setColor(withAlpha(brighten(base, 0.3f), holding ? 195 : 150));
        g.fill(arrows);
        g.setColor(withAlpha(glowEdge, holding ? 240 : 205));
        g.setStroke(new BasicStroke(outerR * 0.05f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(arrows);

        // Center hole (dark), then the approach indicator drawn on top of it, shrinking from just
        // inside the arrow tips down onto exactly this hole's size and brightening sharply as it
        // closes in — so the hole itself visibly lights up the instant it lands (a PERFECT hit),
        // rather than the hole just re-covering it back up.
        g.setColor(NOTE_HOLE_BG);
        g.fill(centerHole);

        double approachR = centerHoleR + (outerR * 0.20 - centerHoleR) * approachT;
        int glowAlpha = (int) Math.round(255 - 190 * approachT);
        g.setColor(withAlpha(ARCADE_CENTER_COLOR, glowAlpha));
        g.fill(new Ellipse2D.Double(cx - approachR, cy - approachR, approachR * 2, approachR * 2));

        g.setColor(withAlpha(ARCADE_CENTER_COLOR, 200));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(centerHole);

        Color rimColor = new Color(255, 140, 235);
        g.setColor(withAlpha(rimColor, 220));
        g.setStroke(new BasicStroke(2f));
        g.draw(outerEllipse);

        // A second, thinner, dimmer ring just inside the main rim — approved via draft PNG review.
        double innerBorderR = outerR - 4;
        Ellipse2D innerBorder = new Ellipse2D.Double(cx - innerBorderR, cy - innerBorderR,
                innerBorderR * 2, innerBorderR * 2);
        g.setColor(withAlpha(rimColor, 120));
        g.setStroke(new BasicStroke(1f));
        g.draw(innerBorder);
    }

    /** The inner cross glyph, in local unit coordinates (-1..1) — built exactly the way the
     *  reference icon reads: a plus/cross silhouette (a vertical bar unioned with a horizontal bar)
     *  with a bold "X" (two crossing diagonal bars) subtracted out of it, leaving 4 separate blue
     *  arms and a bold white X-shaped gap through the center — not 4 hand-placed triangles, which
     *  kept coming out a different shape than the reference on every earlier attempt. */
    private static final Area NOTE_CROSS_GLYPH = buildNoteCrossGlyph();

    private static Area buildNoteCrossGlyph() {
        double armHalfWidth = 0.38;
        Area plus = new Area(new java.awt.geom.Rectangle2D.Double(-armHalfWidth, -1.0, armHalfWidth * 2, 2.0));
        plus.add(new Area(new java.awt.geom.Rectangle2D.Double(-1.0, -armHalfWidth, 2.0, armHalfWidth * 2)));

        double xHalfWidth = 0.09;
        java.awt.geom.Rectangle2D.Double bar = new java.awt.geom.Rectangle2D.Double(-1.6, -xHalfWidth, 3.2, xHalfWidth * 2);
        Area xCut = new Area(java.awt.geom.AffineTransform.getRotateInstance(Math.PI / 4).createTransformedShape(bar));
        xCut.add(new Area(java.awt.geom.AffineTransform.getRotateInstance(-Math.PI / 4).createTransformedShape(bar)));
        plus.subtract(xCut);
        return plus;
    }

    /** A falling note as a ring/"donut": a soft glow, a gradient-filled ring with a real hole (via
     *  {@link Area} subtraction, so whatever's underneath actually shows through it rather than
     *  just being painted over), the cross glyph (see {@link #NOTE_CROSS_GLYPH}) inside that hole,
     *  and a bright rim highlight along the outer edge. */
    private static void paintNoteCross(Graphics2D g, int cx, int cy, int outerR, Color base) {
        // A thin band and a big hole — the previous, thicker band made the ring look oversized next
        // to the (comparatively small) glyph; this shrinks the band so the hole/glyph gets most of
        // the note's overall size instead — "모양과 원을 키워주시고 링은 작게".
        int innerR = Math.max(8, outerR - Math.max(7, (int) Math.round(outerR * 0.22)));
        int d = outerR * 2;
        paintGlow(g, cx - outerR, cy - outerR, d, d, d, base, 1f);

        Ellipse2D outerEllipse = new Ellipse2D.Double(cx - outerR, cy - outerR, d, d);
        Ellipse2D innerEllipse = new Ellipse2D.Double(cx - innerR, cy - innerR, innerR * 2, innerR * 2);
        Area ring = new Area(outerEllipse);
        ring.subtract(new Area(innerEllipse));

        Paint old = g.getPaint();

        // The hole: a dark plum (NOTE_HOLE_BG), not white — matches the reference screenshot's own
        // shadowed hole color. The glyph (painted on top, below) only fills its own 4 arms, so its
        // X-shaped gap naturally shows this same hole color through it, unpainted.
        g.setColor(NOTE_HOLE_BG);
        g.fill(innerEllipse);

        // The band: radial from the hole's edge (bright) out to the rim (the note's own dark base
        // color) — the cross-section of a lit, rounded tube, not a flat annulus of one color.
        // RadialGradientPaint demands strictly increasing fractions, so the middle stop can't be a
        // fixed 0.7f: a thin-enough band (innerR/outerR now well above 0.7, since the ring shrank)
        // pushes the first stop past a hardcoded 0.7 and throws IllegalArgumentException — this
        // derives the middle stop from the first instead, so it's always between it and 1.
        float bandStart = Math.max(0.01f, (float) innerR / outerR);
        float bandMid = bandStart + (1f - bandStart) * 0.5f;
        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), outerR,
                new float[]{bandStart, bandMid, 1f},
                new Color[]{brighten(base, 0.6f), base, base.darker()}));
        g.fill(ring);
        g.setPaint(old);

        // The cross glyph (see NOTE_CROSS_GLYPH): flat-colored, like the reference icon's blocks —
        // no gradient — with a thin darker outline. Deliberately smaller than the hole (not filling
        // it edge-to-edge) so there's a visible plum-colored margin between the glyph and the ring's
        // inner edge, not the glyph touching/clipped right at it.
        double armReach = innerR * 0.8;
        java.awt.geom.AffineTransform glyphT = java.awt.geom.AffineTransform.getTranslateInstance(cx, cy);
        glyphT.scale(armReach, armReach);
        Shape glyph = glyphT.createTransformedShape(NOTE_CROSS_GLYPH);
        Shape prevClip = g.getClip();
        g.clip(innerEllipse);
        g.setColor(base);
        g.fill(glyph);
        g.setColor(base.darker());
        g.setStroke(new BasicStroke(1f));
        g.draw(glyph);
        g.setClip(prevClip);

        // A thin seam between the hole and the band.
        g.setColor(withAlpha(Color.BLACK, 70));
        g.setStroke(new BasicStroke(1f));
        g.draw(innerEllipse);

        // Bright rim highlight along the very outer edge.
        g.setColor(withAlpha(Color.WHITE, 130));
        g.setStroke(new BasicStroke(1.8f));
        g.draw(outerEllipse);

        g.setColor(base.darker().darker());
        g.setStroke(new BasicStroke(1.4f));
        g.draw(ring);
    }

    /** A hold note's body bar — the same glow-plus-gradient treatment as {@link #paintNoteCap}, just
     *  stretched to the head-to-tail span instead of a fixed short cap, and brightened while
     *  actually being held (see {@link #brighten}) so a correctly-held note visibly lights up. */
    private static void paintHoldBar(Graphics2D g, int x, int top, int width, int height, int arc, Color base, boolean holding) {
        Color body = holding ? brighten(base, 0.25f) : base;
        paintGlow(g, x, top, width, height, arc, base, holding ? 1f : 0.6f);
        int alpha = holding ? 235 : 160;
        Paint old = g.getPaint();
        g.setPaint(new GradientPaint(x, top, withAlpha(brighten(body, 0.2f), alpha), x, top + height, withAlpha(body.darker(), alpha)));
        g.fillRoundRect(x, top, width, height, arc, arc);
        g.setPaint(old);
        g.setColor(base.darker().darker());
        g.setStroke(new BasicStroke(1.6f));
        g.drawRoundRect(x, top, width, height, arc, arc);
    }

    /** @return {@code c} shifted {@code amount} (0..1) of the way toward white — used for the top
     *  edge of a note's gradient fill and its held-brighter body, in place of AWT's own {@code
     *  Color.brighter()}, which scales each channel by a fixed factor and barely changes already-
     *  vivid, high-saturation colors (most of this game's lane/side colors) or does nothing at all
     *  to a channel that's already 0. */
    private static Color brighten(Color c, float amount) {
        int r = c.getRed() + Math.round((255 - c.getRed()) * amount);
        int gr = c.getGreen() + Math.round((255 - c.getGreen()) * amount);
        int b = c.getBlue() + Math.round((255 - c.getBlue()) * amount);
        return new Color(r, gr, b);
    }

    private void paintCombo(Graphics2D g, long nowWall) {
        long age = nowWall - comboPulseAt;
        float size = 22f;
        Color color = Color.WHITE;
        if (age >= 0 && age < COMBO_PULSE_MS) {
            double t = 1.0 - age / (double) COMBO_PULSE_MS;
            size = (float) (22 + 14 * t);
            color = new Color(255, (int) (45 + 155 * t), (int) (138 + 82 * t));
        }
        g.setColor(color);
        g.setFont(g.getFont().deriveFont(Font.BOLD, size));
        g.drawString("COMBO " + combo, 12, 58);
    }

    /** The FEVER gauge — a thin bar that fills toward the next level; once at level 5 it just stays lit. */
    private void paintFeverGauge(Graphics2D g, int panelWidth) {
        int barW = panelWidth - 24;
        int barH = 10;
        int x = 12, y = 66;
        g.setColor(new Color(255, 255, 255, 40));
        g.fillRoundRect(x, y, barW, barH, 6, 6);
        boolean maxed = feverLevel >= FEVER_MAX_LEVEL;
        double fillFrac = maxed ? 1.0 : feverGauge / 100.0;
        int fillW = (int) (barW * fillFrac);
        Color fillColor = feverLevel > 0 ? new Color(255, 45, 138) : new Color(190, 140, 230);
        g.setColor(fillColor);
        if (fillW > 0) {
            g.fillRoundRect(x, y, Math.max(barH, fillW), barH, 6, 6);
        }
        g.setColor(new Color(255, 255, 255, 160));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(x, y, barW, barH, 6, 6);
        if (feverLevel > 0) {
            g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
            g.setColor(Color.WHITE);
            g.drawString("FEVER Lv." + feverLevel, x + barW - 62, y + barH + 13);
        }
    }

    /** A key press's lane flash: a soft vertical light beam centered on the judgment line, brightest
     *  right there and fading both up and down, rather than a flat full-height white wash — reads as
     *  "light coming from where you pressed" instead of just the whole lane briefly greying lighter.
     *  {@code lane < 0} (the side key) lights the full width instead of one 120px column. */
    private void paintLaneFlashes(Graphics2D g, long nowWall) {
        int panelWidth = LANES * LANE_WIDTH;
        float jf = (float) JUDGE_Y / PANEL_HEIGHT;
        for (Effect e : effects) {
            if (e.kind() != EffectKind.LANE_FLASH) continue;
            long age = ageMs(e, nowWall);
            if (age > LANE_FLASH_MS) continue;
            float alpha = (float) (1.0 - age / (double) LANE_FLASH_MS);
            int x = e.lane() < 0 ? 0 : e.lane() * LANE_WIDTH;
            int width = e.lane() < 0 ? panelWidth : LANE_WIDTH;
            Paint old = g.getPaint();
            g.setPaint(new LinearGradientPaint(
                    new Point2D.Float(0, 0), new Point2D.Float(0, PANEL_HEIGHT),
                    new float[]{0f, jf - 0.12f, jf, jf + 0.18f, 1f},
                    new Color[]{
                            withAlpha(Color.WHITE, 0),
                            withAlpha(Color.WHITE, Math.round(28 * alpha)),
                            withAlpha(Color.WHITE, Math.round(150 * alpha)),
                            withAlpha(Color.WHITE, Math.round(40 * alpha)),
                            withAlpha(Color.WHITE, 0)}));
            g.fillRect(x, 0, width, PANEL_HEIGHT);
            g.setPaint(old);
        }
    }

    /** Dispatches to whichever hit-feedback visual {@link RhythmSettings.HitEffectStyle} the
     *  player picked in Settings — RIPPLE ({@link #paintHitRipple}, the default) or BURST ({@link
     *  #paintHitBurst}, the original radiating-spark version), never both. */
    private void paintHitEffects(Graphics2D g, long nowWall) {
        if (settings.hitEffectStyle() == RhythmSettings.HitEffectStyle.BURST) {
            paintHitBurst(g, nowWall);
        } else {
            paintHitRipple(g, nowWall);
        }
    }

    /** A hit's judgment feedback: a small "splash" dot right at the impact point, then a few
     *  staggered rings expanding outward and thinning/fading as they spread — a water ripple
     *  spreading from where a drop landed ("호수에 물방울 떨어진 것처럼 작은 물결이 치도록"). Same
     *  color per tier as before ({@link #judgeColor}); {@link Effect#strength()} scales how far
     *  the rings spread and how many overlap at once, so a PERFECT still visibly reads as bigger
     *  than a scrappy GOOD. */
    private void paintHitRipple(Graphics2D g, long nowWall) {
        int panelWidth = LANES * LANE_WIDTH;
        for (Effect e : effects) {
            if (e.kind() != EffectKind.HIT_EFFECT) continue;
            long age = ageMs(e, nowWall);
            if (age > HIT_RIPPLE_MS) continue;
            double t = age / (double) HIT_RIPPLE_MS;
            float strength = e.strength();
            int cx = e.lane() < 0 ? panelWidth / 2 : e.lane() * LANE_WIDTH + LANE_WIDTH / 2;
            int cy = JUDGE_Y;

            // The initial "splash" at the drop point — brief, small, and soft, not a hard flash.
            if (t < 0.25) {
                float dotAlpha = (float) (1.0 - t / 0.25) * strength;
                double dotRadius = 4 + 4 * strength;
                g.setColor(withAlpha(Color.WHITE, Math.round(140 * dotAlpha)));
                g.fill(new Ellipse2D.Double(cx - dotRadius, cy - dotRadius, dotRadius * 2, dotRadius * 2));
            }

            // 3 rings, each starting a little after the last (real ripples don't all move in
            // lockstep) — each thins and fades as it expands, so the outermost/oldest ring is
            // always the faintest and thinnest, like an actual wave losing amplitude as it spreads.
            double maxRadius = 12 + 30 * strength;
            for (int ring = 0; ring < 3; ring++) {
                double ringT = t - ring * 0.12;
                if (ringT < 0 || ringT > 1) continue;
                double radius = 3 + maxRadius * ringT;
                float ringAlpha = (float) (1.0 - ringT);
                g.setColor(withAlpha(e.color(), Math.round(180 * ringAlpha)));
                g.setStroke(new BasicStroke((float) Math.max(0.6, 2.2 * (1 - ringT))));
                g.draw(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
            }
        }
    }

    /** The original hit-judgment burst: a bright core flash, two staggered expanding rings, and a
     *  handful of sparks radiating outward — kept as the alternate {@link
     *  RhythmSettings.HitEffectStyle#BURST} option for players who preferred it over the ripple. */
    private void paintHitBurst(Graphics2D g, long nowWall) {
        int panelWidth = LANES * LANE_WIDTH;
        for (Effect e : effects) {
            if (e.kind() != EffectKind.HIT_EFFECT) continue;
            long age = ageMs(e, nowWall);
            if (age > HIT_BURST_MS) continue;
            double t = age / (double) HIT_BURST_MS;
            float alpha = (float) (1.0 - t);
            float strength = e.strength();
            int cx = e.lane() < 0 ? panelWidth / 2 : e.lane() * LANE_WIDTH + LANE_WIDTH / 2;
            int cy = JUDGE_Y;

            if (t < 0.35) {
                float coreAlpha = (float) (1.0 - t / 0.35) * strength;
                double coreRadius = 9 + 9 * strength;
                g.setColor(withAlpha(Color.WHITE, Math.round(200 * coreAlpha)));
                g.fill(new Ellipse2D.Double(cx - coreRadius, cy - coreRadius, coreRadius * 2, coreRadius * 2));
            }

            for (int ring = 0; ring < 2; ring++) {
                double ringT = Math.min(1.0, t + ring * 0.18);
                double radius = 4 + (10 + 30 * strength) * ringT;
                float ringAlpha = (float) (1.0 - ringT) * (ring == 0 ? 1f : 0.6f);
                g.setColor(withAlpha(e.color(), Math.round(200 * ringAlpha)));
                g.setStroke(new BasicStroke(ring == 0 ? 3f : 2f));
                g.draw(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
            }

            int sparkCount = 4 + Math.round(4 * strength);
            double travel = 12 + 40 * strength * t;
            double sparkLen = 3 + 9 * (1 - t);
            g.setColor(withAlpha(e.color(), Math.round(180 * alpha)));
            g.setStroke(new BasicStroke(2.2f));
            for (int i = 0; i < sparkCount; i++) {
                double angle = (Math.PI * 2 / sparkCount) * i + Math.PI / 6;
                double dx = Math.cos(angle), dy = Math.sin(angle);
                double x1 = cx + dx * travel, y1 = cy + dy * travel;
                double x2 = cx + dx * (travel + sparkLen), y2 = cy + dy * (travel + sparkLen);
                g.draw(new Line2D.Double(x1, y1, x2, y2));
            }
        }
    }

    private void paintMissFlash(Graphics2D g, int panelWidth, long nowWall) {
        for (Effect e : effects) {
            if (e.kind() != EffectKind.MISS_FLASH) continue;
            long age = ageMs(e, nowWall);
            if (age > MISS_FLASH_MS) continue;
            float alpha = (float) (1.0 - age / (double) MISS_FLASH_MS);
            g.setColor(withAlpha(e.color(), (int) (130 * alpha)));
            g.setStroke(new BasicStroke(6f));
            g.drawRect(2, 2, panelWidth - 4, PANEL_HEIGHT - 4);
        }
    }

    /** A soft, screen-wide flash at the judge line on a PERFECT hit, lingering and fading out
     *  rather than popping and vanishing — approved via draft mockup (Downloads\꼬미_니아모드\
     *  draft_perfect_flash_*.png), then toned down and de-edged after "너무 눈부시고 경계선도
     *  부자연스럽게 끊겨 보입니다": the first version filled a plain rectangle with a radial
     *  gradient, so the glow got hard-clipped at the rectangle's top/bottom edge well before the
     *  gradient itself had actually faded to transparent there — a visible seam, not a soft fade.
     *  Drawing an actual ellipse (a circle stretched wide via a temporary scale transform) instead
     *  means the shape's own boundary IS where the gradient reaches zero, in every direction, so
     *  there's no edge left to look unnatural. Peak brightness also came down a good deal (alphas
     *  roughly halved) per "너무 눈부시다". The band's height only shrinks a little as it fades
     *  (from {@code exp(-3.5t)} decay) — most of the falloff is in alpha, not size — so the
     *  afterglow reads as actually lingering at that spot, not just the peak flash shrinking away.
     *  A dim white-hot core rides on top for the first half of the effect, for the "섬광" (flash)
     *  part; past that it's just the fading colored glow, the "이펙트를 더 남기고" (leftover
     *  effect) part. */
    private void paintPerfectFlash(Graphics2D g, int panelWidth, long nowWall) {
        for (Effect e : effects) {
            if (e.kind() != EffectKind.PERFECT_FLASH) continue;
            long age = ageMs(e, nowWall);
            if (age > PERFECT_FLASH_MS) continue;
            double t = age / (double) PERFECT_FLASH_MS;
            double intensity = Math.exp(-3.5 * t);
            double bandHalfHeight = 46 + 30 * intensity;
            double bandHalfWidth = panelWidth * 0.68; // reaches a bit past the edges so the visible
                                                        // glow itself fades out approaching them,
                                                        // rather than being cut off by the panel edge
            int glowAlpha = (int) Math.round(70 * intensity); // was 220, then 120 — even less "눈부시다"
            Color base = e.color();
            Paint old = g.getPaint();

            fillSoftEllipse(g, panelWidth / 2.0, JUDGE_Y, bandHalfWidth, bandHalfHeight,
                    withAlpha(base, glowAlpha));

            if (intensity > 0.5) {
                float coreAlpha = (float) Math.min(1.0, (intensity - 0.5) * 2);
                fillSoftEllipse(g, panelWidth / 2.0, JUDGE_Y, bandHalfWidth * 0.7, bandHalfHeight * 0.6,
                        withAlpha(Color.WHITE, (int) (55 * coreAlpha))); // was 180, then 90 — even less "눈부시다"
            }
            g.setPaint(old);
        }
    }

    /** Fills an ellipse ({@code halfWidth} x {@code halfHeight} around {@code cx,cy}) with a
     *  radial gradient from {@code peakColor} at the center down to fully transparent right at
     *  the ellipse's own edge — drawn as a circle under a temporary scale transform rather than a
     *  literal {@link java.awt.geom.Ellipse2D}, since {@link RadialGradientPaint} is always
     *  circular; stretching the whole coordinate space (draw + paint together) is what turns that
     *  circle into a wide ellipse without the gradient and the fill shape ever disagreeing about
     *  where "zero" is — which is exactly what caused the hard edge this replaces. */
    private static void fillSoftEllipse(Graphics2D g, double cx, double cy, double halfWidth, double halfHeight,
            Color peakColor) {
        java.awt.geom.AffineTransform oldT = g.getTransform();
        g.translate(cx, cy);
        g.scale(halfWidth / halfHeight, 1.0);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(0, 0), (float) halfHeight,
                new float[]{0f, 1f}, new Color[]{peakColor, withAlpha(peakColor, 0)}));
        g.fillOval((int) Math.round(-halfHeight), (int) Math.round(-halfHeight),
                (int) Math.round(halfHeight * 2), (int) Math.round(halfHeight * 2));
        g.setTransform(oldT);
    }

    private static long ageMs(Effect e, long nowWall) {
        return nowWall - e.startedAtMs();
    }

    /** Not static (unlike the other paint helpers around it) since HIT_EFFECT's own duration
     *  depends on which of the two styles is currently selected — a ripple lingers longer than a
     *  burst does. */
    private long durationOf(EffectKind kind) {
        return switch (kind) {
            case LANE_FLASH -> LANE_FLASH_MS;
            case HIT_EFFECT -> settings.hitEffectStyle() == RhythmSettings.HitEffectStyle.BURST
                    ? HIT_BURST_MS : HIT_RIPPLE_MS;
            case MISS_FLASH -> MISS_FLASH_MS;
            case PERFECT_FLASH -> PERFECT_FLASH_MS;
        };
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private void paintCountdown(Graphics2D g, int w) {
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(0, 0, w, PANEL_HEIGHT);
        int shown = (countdownRemainingMs / 1000) + 1;
        String text = String.valueOf(Math.max(1, shown));
        g.setColor(Color.WHITE);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 120f));
        FontMetrics fm = g.getFontMetrics();
        drawCentered(g, fm, text, w, PANEL_HEIGHT / 2 + 40);
    }

    /** DJMAX-style pause: the game screen is fully hidden behind an opaque black screen with an
     *  arrow-key/Enter menu — not a translucent overlay on top of the frozen play field. */
    private void paintPauseMenu(Graphics2D g, int w) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, PANEL_HEIGHT);

        g.setColor(new Color(110, 85, 105));
        g.setFont(g.getFont().deriveFont(Font.BOLD, 30f));
        FontMetrics titleFm = g.getFontMetrics();
        drawCentered(g, titleFm, "PAUSE", w, 130);

        int itemY = 250;
        int itemGap = 58;
        for (int i = 0; i < PAUSE_MENU.length; i++) {
            boolean selected = i == pauseMenuIndex;
            g.setFont(g.getFont().deriveFont(selected ? Font.BOLD : Font.PLAIN, selected ? 24f : 20f));
            FontMetrics fm = g.getFontMetrics();
            String label = PAUSE_MENU[i];
            int y = itemY + i * itemGap;
            // A full-width band, not just the text's own bounds — a bigger, easier mouse target,
            // and stable across the font-size jump the selected item gets (no jumpy click zones).
            pauseMenuBounds[i] = new Rectangle(0, y - itemGap / 2, w, itemGap);
            if (selected) {
                int textWidth = fm.stringWidth(label);
                int padX = 28;
                int boxW = textWidth + padX * 2;
                g.setColor(new Color(255, 45, 138));
                g.fillRoundRect(w / 2 - boxW / 2, y - fm.getAscent(), boxW, fm.getHeight(), 10, 10);
                g.setColor(Color.WHITE);
            } else {
                g.setColor(new Color(190, 165, 185));
            }
            drawCentered(g, fm, label, w, y);
        }

        g.setFont(g.getFont().deriveFont(Font.PLAIN, 13f));
        g.setColor(new Color(140, 115, 135));
        FontMetrics hintFm = g.getFontMetrics();
        drawCentered(g, hintFm, Lang.t(settings, "game.pauseMenu.hint"), w, PANEL_HEIGHT - 36);
    }

    private void paintResults(Graphics2D g, int w) {
        g.setColor(new Color(20, 8, 18, 200));
        g.fillRect(0, 0, w, PANEL_HEIGHT);

        FontMetrics fm;
        // Centered within the 640-tall design instead of pinned to a fixed top offset: with the
        // accuracy%/judgment-tally lines this screen has grown to (added across a couple of earlier
        // sessions), a fixed cy=90 left a big empty gap at the bottom and read as everything crammed
        // toward the top ("글자들이 밀려서 위로 올라가는"). 140 (chosen so the block's actual visual
        // span — rank circle top to hint text bottom — lands close to centered in 640) replaces it.
        int cy = 140;

        g.setFont(g.getFont().deriveFont(Font.BOLD, 18f));
        g.setColor(Color.LIGHT_GRAY);
        fm = g.getFontMetrics();
        String title = chart.title == null ? "" : chart.title;
        drawCentered(g, fm, title, w, cy);
        cy += 60;

        Color rankColor = rankColor(result.rank());
        g.setColor(withAlpha(rankColor, 70));
        g.fillOval(w / 2 - 70, cy - 70, 140, 140);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 96f));
        g.setColor(rankColor);
        fm = g.getFontMetrics();
        // drawCentered's y is a baseline, not a visual center — fine for ordinary text, but at 96pt
        // the gap between "baseline" and "where the glyph actually looks centered" is big enough
        // that the rank letter always sat noticeably above the middle of its own circle behind it,
        // no matter how far down earlier fixes moved the whole results block (that only ever
        // repositioned this pair together, never their relative offset — "여전히 인터페이스[the
        // circle]보다 위치가 높고 변하질 않습니다"). Nudging the baseline down by half the
        // ascent-minus-descent gap centers the glyph's visible band on cy instead.
        int rankBaselineY = cy + (fm.getAscent() - fm.getDescent()) / 2;
        drawCentered(g, fm, result.rank(), w, rankBaselineY);
        cy += 70;

        g.setFont(g.getFont().deriveFont(Font.BOLD, 30f));
        g.setColor(Color.WHITE);
        fm = g.getFontMetrics();
        drawCentered(g, fm, "SCORE " + result.score(), w, cy);
        cy += 34;

        g.setFont(g.getFont().deriveFont(Font.BOLD, 15f));
        fm = g.getFontMetrics();
        if (newBest) {
            g.setColor(new Color(255, 215, 60));
            drawCentered(g, fm, "NEW BEST!", w, cy);
        } else {
            g.setColor(Color.GRAY);
            int best = settings.bestScore(chart.audioFile.getName(), settings.songDifficulty(chart.audioFile.getName()));
            drawCentered(g, fm, "BEST " + best, w, cy);
        }
        cy += 30;

        g.setFont(g.getFont().deriveFont(Font.PLAIN, 18f));
        fm = g.getFontMetrics();
        drawCentered(g, fm, "MAX COMBO " + result.maxCombo() + "   "
                + Lang.t(settings, "game.results.accuracy") + " " + result.accuracyPercent() + "%", w, cy);
        cy += 40;

        // 27px, not the tighter 24px this used before: an 18f line's actual rendered height (ascent
        // + descent + leading) runs close to that on some system fonts, which could let consecutive
        // tally lines' glyphs visually touch/overlap ("밀려서") instead of sitting cleanly apart.
        g.setColor(new Color(255, 45, 138));
        drawCentered(g, fm, "PERFECT " + result.perfects(), w, cy);
        cy += 27;
        g.setColor(new Color(190, 140, 230));
        drawCentered(g, fm, "GREAT " + result.greats(), w, cy);
        cy += 27;
        g.setColor(new Color(255, 180, 210));
        drawCentered(g, fm, "GOOD " + result.goods(), w, cy);
        cy += 27;
        g.setColor(Color.LIGHT_GRAY);
        drawCentered(g, fm, "BREAK " + result.misses(), w, cy);
        cy += 50;

        g.setFont(g.getFont().deriveFont(Font.PLAIN, 14f));
        g.setColor(Color.GRAY);
        fm = g.getFontMetrics();
        drawCentered(g, fm, Lang.t(settings, "game.results.hint"), w, cy);
    }

    /** The health gauge hit empty — a fail screen instead of {@link #paintResults}, same layout
     *  bones (title, big centerpiece, stats, restart hint) but no rank, since there isn't one. */
    private void paintGameOver(Graphics2D g, int w) {
        g.setColor(new Color(40, 0, 16, 210));
        g.fillRect(0, 0, w, PANEL_HEIGHT);

        FontMetrics fm;
        // See paintResults' identical comment — centered within the 640-tall design (140) rather
        // than pinned to a fixed top offset (was 90), which left the accuracy%/tally lines this
        // screen grew to crammed toward the top with a big empty gap below.
        int cy = 140;

        g.setFont(g.getFont().deriveFont(Font.BOLD, 18f));
        g.setColor(Color.LIGHT_GRAY);
        fm = g.getFontMetrics();
        String title = chart.title == null ? "" : chart.title;
        drawCentered(g, fm, title, w, cy);
        cy += 90;

        g.setColor(new Color(255, 60, 60));
        g.setFont(g.getFont().deriveFont(Font.BOLD, 52f));
        fm = g.getFontMetrics();
        drawCentered(g, fm, Lang.t(settings, "game.over"), w, cy);
        cy += 70;

        g.setFont(g.getFont().deriveFont(Font.BOLD, 28f));
        g.setColor(Color.WHITE);
        fm = g.getFontMetrics();
        drawCentered(g, fm, "SCORE " + result.score(), w, cy);
        cy += 36;

        g.setFont(g.getFont().deriveFont(Font.PLAIN, 16f));
        fm = g.getFontMetrics();
        drawCentered(g, fm, "MAX COMBO " + result.maxCombo() + "   "
                + Lang.t(settings, "game.results.accuracy") + " " + result.accuracyPercent() + "%", w, cy);
        cy += 34;

        // 27px — see paintResults' identical comment on why this isn't 24 anymore.
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 18f));
        fm = g.getFontMetrics();
        g.setColor(new Color(255, 45, 138));
        drawCentered(g, fm, "PERFECT " + result.perfects(), w, cy);
        cy += 27;
        g.setColor(new Color(190, 140, 230));
        drawCentered(g, fm, "GREAT " + result.greats(), w, cy);
        cy += 27;
        g.setColor(new Color(255, 180, 210));
        drawCentered(g, fm, "GOOD " + result.goods(), w, cy);
        cy += 27;
        g.setColor(Color.LIGHT_GRAY);
        drawCentered(g, fm, "BREAK " + result.misses(), w, cy);
        cy += 40;

        g.setFont(g.getFont().deriveFont(Font.PLAIN, 14f));
        g.setColor(Color.GRAY);
        fm = g.getFontMetrics();
        drawCentered(g, fm, Lang.t(settings, "game.results.hint"), w, cy);
    }

    private static void drawCentered(Graphics2D g, FontMetrics fm, String text, int width, int y) {
        int x = (width - fm.stringWidth(text)) / 2;
        g.drawString(text, x, y);
    }

    /** Which clear voice line fits this result — perfect full combo beats plain full combo beats rank. */
    private static VoiceLines.ClearGrade clearGradeFor(Result r) {
        if (r.misses() == 0 && r.greats() == 0 && r.goods() == 0 && r.perfects() > 0) {
            return VoiceLines.ClearGrade.PERFECT_FULL_COMBO;
        }
        if (r.misses() == 0) {
            return VoiceLines.ClearGrade.FULL_COMBO;
        }
        // S/A/B (70%+) reads as a solid clear despite the misses; C/D is the rough one.
        String rank = r.rank();
        if (rank.equals("S") || rank.equals("A") || rank.equals("B")) {
            return VoiceLines.ClearGrade.GOOD;
        }
        return VoiceLines.ClearGrade.LOW_OR_FAIL;
    }

    private static Color rankColor(String rank) {
        return switch (rank) {
            case "S" -> new Color(255, 45, 138);
            case "A" -> new Color(255, 180, 210);
            case "B" -> new Color(190, 140, 230);
            case "C" -> new Color(205, 190, 200);
            default -> new Color(175, 158, 170);
        };
    }

    /** @return the decoded image, or {@code null} for no file / an unreadable one — never throws,
     *  since a broken thumbnail must not stop the song from playing. */
    private static BufferedImage loadThumbnail(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return ImageIO.read(file);
        } catch (Exception ex) {
            return null;
        }
    }

    private Color laneColor(int lane) {
        return colorVision.laneColors()[lane];
    }

    // Side-track note colors — yellow for a tap-style side note, sky-blue for a hold-style one,
    // matching DJMAX Respect V's own "노란색/하늘색" side-note convention.
    private static final Color SIDE_NOTE_TAP_COLOR = new Color(255, 210, 60);
    private static final Color SIDE_NOTE_HOLD_COLOR = new Color(90, 200, 255);

    private Color judgeColor(String j) {
        return switch (j) {
            case "PERFECT" -> colorVision.perfectColor();
            case "GREAT" -> colorVision.greatColor();
            case "GOOD" -> colorVision.goodColor();
            case "BREAK" -> new Color(255, 80, 80);
            default -> Color.WHITE;
        };
    }

    /** {@code cleared}: false for a run that ended in {@link #triggerGameOver()} (health hit 0)
     *  rather than the song actually finishing — the Hub uses this to withhold rhythm-game XP and
     *  a new best-score record on a fail, same as most rhythm games only reward an actual clear. */
    public record Result(int score, int maxCombo, int perfects, int greats, int goods, int misses,
                          int accuracyPercent, boolean cleared) {

        /** The run's true average accuracy, 0..100 — the mean of every fully-resolved note's own
         *  continuous accuracy% (a BREAK contributes 0, a hold its head/tail minimum, a tap its own
         *  timing), computed as play happens (see RhythmPanel.accuracySum/accuracyCount). Replaces
         *  an older fixed-weight approximation (PERFECT=1.0/GREAT=0.7/GOOD=0.4 times a tier count)
         *  that couldn't tell a barely-GOOD hit from a nearly-GREAT one. */
        public int accuracyPercent() {
            return accuracyPercent;
        }

        /** S/A/B/C/D letter grade from {@link #accuracyPercent()}. */
        public String rank() {
            int acc = accuracyPercent();
            if (acc >= 95) return "S";
            if (acc >= 85) return "A";
            if (acc >= 70) return "B";
            if (acc >= 50) return "C";
            return "D";
        }
    }
}
