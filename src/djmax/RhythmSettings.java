package djmax;

import com.group_finity.mascot.lumi.plugin.PluginPrefs;

import java.awt.event.KeyEvent;
import java.nio.file.Path;

/**
 * Typed access to this plugin's own settings ({@code plugindata\local.niah.djmax\settings.properties}
 * via {@link PluginPrefs}). No state of its own — every getter reads through, so the Hub/Settings
 * windows and gameplay always agree on the current value.
 */
final class RhythmSettings {
    static final int LANES = 4;
    private static final int[] DEFAULT_KEYS = {
            KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_SEMICOLON, KeyEvent.VK_QUOTE};

    enum DisplayMode { WINDOWED, FULLSCREEN }

    /** Where the play field sits horizontally when the window is wider than it needs — the common
     *  case, since a 4-lane field is much narrower than most windows once it's letterboxed to fit
     *  the window's height. See {@link RhythmPanel#updateRenderTransform()}. */
    enum HorizontalAnchor { LEFT, CENTER, RIGHT }

    /** The Hub's song picker: a conventional top-to-bottom, bounded scrolling list (LIST) or the
     *  centered, wraparound "wheel" carousel (CAROUSEL) — see {@link SongCarousel}. */
    enum SongListStyle { LIST, CAROUSEL }

    /** How falling lane notes are drawn — the ring/"coin" style with the pinwheel-cross glyph
     *  (RING, the default) or the original thin rounded-rectangle pill (CLASSIC) — see
     *  {@link RhythmPanel#paintNote}. */
    enum NoteStyle { RING, CLASSIC }

    private final PluginPrefs prefs;

    RhythmSettings(PluginPrefs prefs) {
        this.prefs = prefs;
    }

    /** {@code plugindata\local.niah.djmax\} — same folder the settings file itself lives in. */
    Path dataDir() {
        return prefs.file().getParent();
    }

    // ── rhythm-game level (Hub profile card) — a level this mod tracks itself, entirely separate
    // from Little LUMI's own app-wide character affection/tier (PluginContext.affectionTier() etc.)
    // — that one is a single value for the whole app installation, tied into Steam achievements.
    // Scoping this one per Steam account was asked for but isn't possible: the SDK exposes no Steam
    // identity to a plugin at all (com.group_finity.mascot.lumi.steam.* is explicitly off-limits —
    // see PluginContext.PROTECTED_PREFIXES), and reaching around that boundary into internals never
    // meant for plugin use would be both fragile (an app update could rename/remove it without
    // notice, as already happened once to this mod's ffmpeg tooling) and outside what a plugin
    // should be doing. So this is one plain local value instead — plugindata\local.niah.djmax\'s
    // own settings.properties, same file everything else here already lives in. A brand new install
    // starts at Lv.0 as requested. ──────────────────────────────────────────────────────────────
    private static final int RHYTHM_XP_PER_LEVEL = 100;

    private int rhythmXp() {
        return Math.max(0, prefs.getInt("rhythm_xp", 0));
    }

    /** Adds XP toward this mod's own rhythm-game level (see {@link #rhythmLevel()}) — never
     *  subtracts, {@code amount <= 0} is a no-op. */
    void addRhythmXp(int amount) {
        if (amount <= 0) {
            return;
        }
        prefs.set("rhythm_xp", rhythmXp() + amount);
    }

    /** Starts at 0 for a brand new account and rises every {@link #RHYTHM_XP_PER_LEVEL} XP. */
    int rhythmLevel() {
        return rhythmXp() / RHYTHM_XP_PER_LEVEL;
    }

    /** Progress toward the next level, 0..99. */
    int rhythmLevelProgressPercent() {
        return (rhythmXp() % RHYTHM_XP_PER_LEVEL) * 100 / RHYTHM_XP_PER_LEVEL;
    }

    // ── music / sfx volume: 0.0..1.0 ────────────────────────────────────────
    double musicVolume() {
        return clamp(prefs.getInt("music_volume_pct", 80) / 100.0, 0.0, 1.0);
    }

    void setMusicVolume(double v) {
        prefs.set("music_volume_pct", (int) Math.round(clamp(v, 0.0, 1.0) * 100));
    }

    double sfxVolume() {
        return clamp(prefs.getInt("sfx_volume_pct", 80) / 100.0, 0.0, 1.0);
    }

    void setSfxVolume(double v) {
        prefs.set("sfx_volume_pct", (int) Math.round(clamp(v, 0.0, 1.0) * 100));
    }

    // ── voice bark volume (Niah's "게임 시작"/"FEVER"/클리어/연습 패드 보이스), separate from SFX ──
    double voiceVolume() {
        return clamp(prefs.getInt("voice_volume_pct", 80) / 100.0, 0.0, 1.0);
    }

    void setVoiceVolume(double v) {
        prefs.set("voice_volume_pct", (int) Math.round(clamp(v, 0.0, 1.0) * 100));
    }

    boolean voiceEnabled() {
        return prefs.getBoolean("voice_enabled", true);
    }

    void setVoiceEnabled(boolean on) {
        prefs.set("voice_enabled", on);
    }

    // ── note fall speed multiplier: how fast notes scroll (visual only) ────
    double noteSpeed() {
        return clamp(prefs.getInt("note_speed_pct", 100) / 100.0, 0.5, 2.5);
    }

    void setNoteSpeed(double v) {
        prefs.set("note_speed_pct", (int) Math.round(clamp(v, 0.5, 2.5) * 100));
    }

    // ── playback speed multiplier: actually resamples the audio (pitch moves with it) ─
    double playbackSpeed() {
        return clamp(prefs.getInt("playback_speed_pct", 100) / 100.0, 0.5, 2.0);
    }

    void setPlaybackSpeed(double v) {
        prefs.set("playback_speed_pct", (int) Math.round(clamp(v, 0.5, 2.0) * 100));
    }

    // ── difficulty (per song, not global — picked in the Hub next to the song list) ─────────
    Difficulty songDifficulty(String songFileName) {
        return Difficulty.fromName(prefs.get(songDifficultyKey(songFileName), null), Difficulty.NORMAL);
    }

    void setSongDifficulty(String songFileName, Difficulty d) {
        prefs.set(songDifficultyKey(songFileName), d.name());
    }

    private static String songDifficultyKey(String songFileName) {
        return "song_difficulty_" + songFileName.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    // ── song list sort order (Hub) ──────────────────────────────────────────
    SongLibrary.SortOrder songSortOrder() {
        String v = prefs.get("song_sort_order", null);
        if (v == null) {
            return SongLibrary.SortOrder.TITLE;
        }
        try {
            return SongLibrary.SortOrder.valueOf(v.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SongLibrary.SortOrder.TITLE;
        }
    }

    void setSongSortOrder(SongLibrary.SortOrder order) {
        prefs.set("song_sort_order", order.name());
    }

    // ── best score (per song + difficulty — a Hard-difficulty best isn't comparable to Easy's) ──
    int bestScore(String songFileName, Difficulty diff) {
        return prefs.getInt(bestScoreKey(songFileName, diff), 0);
    }

    String bestRank(String songFileName, Difficulty diff) {
        return prefs.get(bestRankKey(songFileName, diff), "");
    }

    // Judgment breakdown for the current best run — shown next to the score/rank in the Hub's song
    // detail card, same as the results screen shows for the run that just finished.
    int bestPerfects(String songFileName, Difficulty diff) {
        return prefs.getInt(bestJudgeKey(songFileName, diff, "perfect"), 0);
    }

    int bestGreats(String songFileName, Difficulty diff) {
        return prefs.getInt(bestJudgeKey(songFileName, diff, "great"), 0);
    }

    int bestGoods(String songFileName, Difficulty diff) {
        return prefs.getInt(bestJudgeKey(songFileName, diff, "good"), 0);
    }

    int bestBreaks(String songFileName, Difficulty diff) {
        return prefs.getInt(bestJudgeKey(songFileName, diff, "break"), 0);
    }

    /** Records a run's score (and its judgment breakdown) if — and only if — it beats the current
     *  best for this song+difficulty.
     *  @return true if this run just set a new best, so the results screen can call it out. */
    boolean recordScore(String songFileName, Difficulty diff, int score, String rank,
                         int perfects, int greats, int goods, int breaks) {
        if (score <= bestScore(songFileName, diff)) {
            return false;
        }
        prefs.set(bestScoreKey(songFileName, diff), score);
        prefs.set(bestRankKey(songFileName, diff), rank);
        prefs.set(bestJudgeKey(songFileName, diff, "perfect"), perfects);
        prefs.set(bestJudgeKey(songFileName, diff, "great"), greats);
        prefs.set(bestJudgeKey(songFileName, diff, "good"), goods);
        prefs.set(bestJudgeKey(songFileName, diff, "break"), breaks);
        return true;
    }

    private static String bestScoreKey(String songFileName, Difficulty diff) {
        return "best_score_" + diff.name() + "_" + songFileName.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String bestRankKey(String songFileName, Difficulty diff) {
        return "best_rank_" + diff.name() + "_" + songFileName.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String bestJudgeKey(String songFileName, Difficulty diff, String tier) {
        return "best_" + tier + "_" + diff.name() + "_" + songFileName.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    // ── practice mode: play a real chart with no health gauge / game-over risk ─────────────
    boolean practiceMode() {
        return prefs.getBoolean("practice_mode", false);
    }

    void setPracticeMode(boolean on) {
        prefs.set("practice_mode", on);
    }

    // ── side notes on/off (Hub toggle, not Settings — same footing as practice mode) ───────
    boolean sideNotesEnabled() {
        return prefs.getBoolean("side_notes_enabled", true);
    }

    void setSideNotesEnabled(boolean on) {
        prefs.set("side_notes_enabled", on);
    }

    // ── auto play: every note is judged PERFECT the instant it's due, no input needed ──────
    // Same footing as practice mode — a Hub toggle, not tucked away in Settings, since it's
    // something a player flips on/off between songs (watching a chart, previewing a chart-editor
    // export, etc.), not a one-time preference. Also like practice mode, a run with this on never
    // records a best score or awards affection XP — see RhythmPanel/HubPanel's playSelected.
    boolean autoPlayEnabled() {
        return prefs.getBoolean("auto_play_enabled", false);
    }

    void setAutoPlayEnabled(boolean on) {
        prefs.set("auto_play_enabled", on);
    }

    // ── countdown, seconds (0 = off) ─────────────────────────────────────────
    int countdownSeconds() {
        int v = prefs.getInt("countdown_seconds", 3);
        return v < 0 ? 0 : Math.min(v, 9);
    }

    void setCountdownSeconds(int seconds) {
        prefs.set("countdown_seconds", Math.max(0, Math.min(seconds, 9)));
    }

    // ── timing offset, ms (added to "now" before judging a hit) ────────────
    long offsetMs() {
        return prefs.getLong("offset_ms", 0);
    }

    void setOffsetMs(long ms) {
        prefs.set("offset_ms", ms);
    }

    // ── judgment window scale: widens/narrows PERFECT/GREAT/GOOD together ──
    int judgmentWindowScalePercent() {
        return Math.max(50, Math.min(prefs.getInt("judgment_scale_pct", 100), 200));
    }

    void setJudgmentWindowScalePercent(int pct) {
        prefs.set("judgment_scale_pct", Math.max(50, Math.min(pct, 200)));
    }

    // ── lane keys (A S ; ' by default) ──────────────────────────────────────
    int[] laneKeys() {
        int[] keys = new int[LANES];
        for (int i = 0; i < LANES; i++) {
            keys[i] = prefs.getInt("lane_key_" + i, DEFAULT_KEYS[i]);
        }
        return keys;
    }

    void setLaneKey(int lane, int keyCode) {
        if (lane < 0 || lane >= LANES) {
            return;
        }
        prefs.set("lane_key_" + lane, keyCode);
    }

    void resetLaneKeys() {
        for (int i = 0; i < LANES; i++) {
            prefs.set("lane_key_" + i, DEFAULT_KEYS[i]);
        }
    }

    // ── side key (side-track notes — DJMAX's "Side Track", Shift by default) ───────────────
    int sideKey() {
        return prefs.getInt("side_key", KeyEvent.VK_SHIFT);
    }

    void setSideKey(int keyCode) {
        prefs.set("side_key", keyCode);
    }

    void resetSideKey() {
        prefs.set("side_key", KeyEvent.VK_SHIFT);
    }

    // ── display ──────────────────────────────────────────────────────────────
    DisplayMode displayMode() {
        return "FULLSCREEN".equals(prefs.get("display_mode", "WINDOWED"))
                ? DisplayMode.FULLSCREEN : DisplayMode.WINDOWED;
    }

    void setDisplayMode(DisplayMode mode) {
        prefs.set("display_mode", mode.name());
    }

    HorizontalAnchor gameHorizontalAnchor() {
        String v = prefs.get("game_h_anchor", null);
        if (v == null) {
            return HorizontalAnchor.CENTER;
        }
        try {
            return HorizontalAnchor.valueOf(v.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return HorizontalAnchor.CENTER;
        }
    }

    void setGameHorizontalAnchor(HorizontalAnchor anchor) {
        prefs.set("game_h_anchor", anchor.name());
    }

    SongListStyle songListStyle() {
        String v = prefs.get("song_list_style", null);
        if (v == null) {
            return SongListStyle.CAROUSEL;
        }
        try {
            return SongListStyle.valueOf(v.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SongListStyle.CAROUSEL;
        }
    }

    void setSongListStyle(SongListStyle style) {
        prefs.set("song_list_style", style.name());
    }

    NoteStyle noteStyle() {
        String v = prefs.get("note_style", null);
        if (v == null) {
            return NoteStyle.RING;
        }
        try {
            return NoteStyle.valueOf(v.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NoteStyle.RING;
        }
    }

    void setNoteStyle(NoteStyle style) {
        prefs.set("note_style", style.name());
    }

    boolean antiAliasing() {
        return prefs.getBoolean("anti_aliasing", true);
    }

    void setAntiAliasing(boolean on) {
        prefs.set("anti_aliasing", on);
    }

    int fpsLimit() {
        int v = prefs.getInt("fps_limit", 60);
        return switch (v) {
            case 30, 120, 144 -> v;
            default -> 60;
        };
    }

    void setFpsLimit(int fps) {
        prefs.set("fps_limit", fps);
    }

    ColorVision colorVision() {
        return ColorVision.fromName(prefs.get("color_vision", null), ColorVision.NORMAL);
    }

    void setColorVision(ColorVision mode) {
        prefs.set("color_vision", mode.name());
    }

    // ── language ─────────────────────────────────────────────────────────────
    Lang language() {
        return Lang.fromName(prefs.get("language", null), Lang.KO);
    }

    void setLanguage(Lang lang) {
        prefs.set("language", lang.name());
    }

    // ── focus behaviour ──────────────────────────────────────────────────────
    boolean autoPauseOnFocusLoss() {
        return prefs.getBoolean("auto_pause_on_focus_loss", true);
    }

    void setAutoPauseOnFocusLoss(boolean on) {
        prefs.set("auto_pause_on_focus_loss", on);
    }

    boolean continueSoundWhenUnfocused() {
        return prefs.getBoolean("continue_sound_unfocused", false);
    }

    void setContinueSoundWhenUnfocused(boolean on) {
        prefs.set("continue_sound_unfocused", on);
    }

    // ── background animation ────────────────────────────────────────────────
    boolean backgroundEnabled() {
        return prefs.getBoolean("background_enabled", false);
    }

    void setBackgroundEnabled(boolean on) {
        prefs.set("background_enabled", on);
    }

    /** The static fallback background — a song's own thumbnail (a YouTube import's), used behind
     *  the lanes whenever the animated loop above isn't on/ready for that song. On by default,
     *  since it's what makes YouTube imports look like anything more than a black lane column. */
    boolean backgroundThumbnailEnabled() {
        return prefs.getBoolean("background_thumbnail_enabled", true);
    }

    void setBackgroundThumbnailEnabled(boolean on) {
        prefs.set("background_thumbnail_enabled", on);
    }

    String backgroundUrl() {
        return prefs.get("background_url", "");
    }

    void setBackgroundUrl(String url) {
        prefs.set("background_url", url == null ? "" : url);
    }

    String backgroundAspectMode() {
        String v = prefs.get("background_aspect", BackgroundAnimator.ASPECT_FIT);
        return switch (v) {
            case BackgroundAnimator.ASPECT_FILL, BackgroundAnimator.ASPECT_STRETCH -> v;
            default -> BackgroundAnimator.ASPECT_FIT;
        };
    }

    void setBackgroundAspectMode(String mode) {
        prefs.set("background_aspect", mode);
    }

    int backgroundBrightnessPercent() {
        return Math.max(0, Math.min(prefs.getInt("background_brightness_pct", 50), 100));
    }

    void setBackgroundBrightnessPercent(int pct) {
        prefs.set("background_brightness_pct", Math.max(0, Math.min(pct, 100)));
    }

    // ── output device ────────────────────────────────────────────────────────
    String outputMixerName() {
        return prefs.get("output_mixer", "");
    }

    void setOutputMixerName(String name) {
        prefs.set("output_mixer", name == null ? "" : name);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Puts every setting the Settings screen shows back to its out-of-the-box default — used by
     *  that screen's 기본값 복원 button. Per-song difficulty and practice mode live on the Hub
     *  screen instead, not this one, so they're untouched here. */
    void resetToDefaults() {
        setDisplayMode(DisplayMode.WINDOWED);
        setGameHorizontalAnchor(HorizontalAnchor.CENTER);
        setSongListStyle(SongListStyle.CAROUSEL);
        setNoteStyle(NoteStyle.RING);
        setAntiAliasing(true);
        setFpsLimit(60);
        setColorVision(ColorVision.NORMAL);
        setLanguage(Lang.KO);
        setCountdownSeconds(3);
        setJudgmentWindowScalePercent(100);
        setOffsetMs(0);
        setAutoPauseOnFocusLoss(true);
        setBackgroundEnabled(false);
        setBackgroundThumbnailEnabled(true);
        setBackgroundUrl("");
        setBackgroundAspectMode(BackgroundAnimator.ASPECT_FIT);
        setBackgroundBrightnessPercent(50);
        setOutputMixerName("");
        setMusicVolume(0.8);
        setSfxVolume(0.8);
        setVoiceVolume(0.8);
        setVoiceEnabled(true);
        resetLaneKeys();
        resetSideKey();
    }
}
