package djmax;

import java.util.Map;

/**
 * This plugin's own two-language UI text (Korean/English) — a small in-memory table, not
 * {@code PluginContext.text()}: that follows the *app's* language, but this is a setting the
 * player picks specifically for the rhythm game, independent of the app's own UI language.
 */
enum Lang {
    KO, EN;

    static Lang fromName(String name, Lang fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return valueOf(name.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Looks up {@code key} in the current {@link RhythmSettings} language; the key itself if missing. */
    static String t(RhythmSettings settings, String key) {
        String[] pair = TABLE.get(key);
        if (pair == null) {
            return key;
        }
        return settings.language() == EN ? pair[1] : pair[0];
    }

    // key -> {ko, en}
    private static final Map<String, String[]> TABLE = Map.ofEntries(
            // Hub
            Map.entry("hub.title", new String[]{"니아 리듬게임", "Niah Rhythm Game"}),
            Map.entry("hub.songs", new String[]{"곡 목록", "Songs"}),
            Map.entry("hub.addWav", new String[]{"WAV 파일 추가", "Add WAV file"}),
            Map.entry("hub.addUrl", new String[]{"URL로 추가", "Add from URL"}),
            Map.entry("hub.deleteSelected", new String[]{"선택 삭제", "Delete selected"}),
            Map.entry("hub.deleteAll", new String[]{"전체 삭제", "Delete all"}),
            Map.entry("hub.play", new String[]{"재생", "Play"}),
            Map.entry("hub.editChart", new String[]{"채보 편집", "Edit chart"}),
            Map.entry("hub.settings", new String[]{"설정", "Settings"}),
            Map.entry("hub.practice", new String[]{"연습", "Practice"}),
            Map.entry("hub.difficulty", new String[]{"난이도", "Difficulty"}),
            Map.entry("hub.sortOrder", new String[]{"정렬", "Sort"}),
            Map.entry("hub.sortOrder.added", new String[]{"추가된 순서", "Date added"}),
            Map.entry("hub.sortOrder.title", new String[]{"가나다순", "Title (A-Z)"}),
            Map.entry("hub.sortOrder.duration", new String[]{"노래 길이순", "Duration"}),
            Map.entry("hub.practiceMode", new String[]{"연습모드", "Practice Mode"}),
            Map.entry("hub.levelUp", new String[]{"LEVEL UP!", "LEVEL UP!"}),
            Map.entry("hub.sideNotes", new String[]{"일자 노트", "Side notes"}),
            Map.entry("hub.autoPlay", new String[]{"퍼펙트 오토", "Perfect Auto"}),
            Map.entry("hub.downloading", new String[]{"유튜브에서 내려받는 중…", "Downloading from YouTube…"}),
            Map.entry("hub.pickSongFirst", new String[]{"먼저 곡을 골라주세요.", "Pick a song first."}),
            Map.entry("hub.confirmDeleteAll", new String[]{"라이브러리의 모든 곡을 지울까요?", "Delete every song in the library?"}),
            Map.entry("hub.loadFailed", new String[]{"불러오기 실패", "Load failed"}),
            Map.entry("hub.playFailed", new String[]{"재생 실패", "Playback failed"}),

            // Settings dialog — tabs
            Map.entry("settings.title", new String[]{"설정", "Settings"}),
            Map.entry("settings.tab.display", new String[]{"디스플레이", "Display"}),
            Map.entry("settings.tab.game", new String[]{"게임", "Game"}),
            Map.entry("settings.tab.sound", new String[]{"사운드", "Sound"}),
            Map.entry("settings.tab.controls", new String[]{"컨트롤", "Controls"}),
            Map.entry("settings.close", new String[]{"닫기", "Close"}),

            // Display tab
            Map.entry("settings.displayMode", new String[]{"디스플레이 모드", "Display mode"}),
            Map.entry("settings.gamePosition", new String[]{"게임 화면 위치", "Game screen position"}),
            Map.entry("settings.gamePosition.left", new String[]{"왼쪽", "Left"}),
            Map.entry("settings.gamePosition.center", new String[]{"가운데", "Center"}),
            Map.entry("settings.gamePosition.right", new String[]{"오른쪽", "Right"}),
            Map.entry("settings.songListStyle", new String[]{"곡 목록 방식", "Song list style"}),
            Map.entry("settings.songListStyle.list", new String[]{"목록형 (기존)", "List (classic)"}),
            Map.entry("settings.songListStyle.carousel", new String[]{"휠 방식", "Wheel"}),
            Map.entry("settings.noteStyle", new String[]{"노트 모양", "Note style"}),
            Map.entry("settings.noteStyle.ring", new String[]{"링 (동전)", "Ring (coin)"}),
            Map.entry("settings.noteStyle.classic", new String[]{"기존 직사각형", "Classic rectangle"}),
            Map.entry("settings.windowed", new String[]{"창 모드", "Windowed"}),
            Map.entry("settings.fullscreen", new String[]{"전체화면", "Fullscreen"}),
            Map.entry("settings.antiAliasing", new String[]{"계단현상 방지 (안티에일리어싱)", "Anti-aliasing"}),
            Map.entry("settings.fpsLimit", new String[]{"초당 프레임 제한", "Frame rate limit"}),
            Map.entry("settings.colorVision", new String[]{"색각 보정", "Color vision"}),
            Map.entry("settings.colorVision.normal", new String[]{"보통", "Normal"}),
            Map.entry("settings.colorVision.protanopia", new String[]{"1형 (적색약)", "Protanopia"}),
            Map.entry("settings.colorVision.deuteranopia", new String[]{"2형 (녹색약)", "Deuteranopia"}),
            Map.entry("settings.colorVision.tritanopia", new String[]{"3형 (청색약)", "Tritanopia"}),

            // Game tab
            Map.entry("settings.language", new String[]{"언어", "Language"}),
            Map.entry("settings.difficulty", new String[]{"난이도", "Difficulty"}),
            Map.entry("settings.difficulty.easy", new String[]{"쉬움", "Easy"}),
            Map.entry("settings.difficulty.normal", new String[]{"보통", "Normal"}),
            Map.entry("settings.difficulty.hard", new String[]{"어려움", "Hard"}),
            Map.entry("settings.countdown", new String[]{"카운트다운", "Countdown"}),
            Map.entry("settings.countdown.suffix", new String[]{"초 (0=끄기)", "sec (0=off)"}),
            Map.entry("settings.judgmentWindow", new String[]{"판정 타이밍 조절 (넉넉함 ↔ 빡빡함)", "Judgment timing (lenient ↔ strict)"}),
            Map.entry("settings.noteTiming", new String[]{"노트 출력 타이밍 조절", "Note timing offset"}),
            Map.entry("settings.calibrate", new String[]{"보정 도우미", "Calibration wizard"}),
            Map.entry("settings.apply", new String[]{"적용", "Apply"}),
            Map.entry("settings.applied", new String[]{"적용됨", "Applied"}),
            Map.entry("settings.on", new String[]{"켜짐", "On"}),
            Map.entry("settings.off", new String[]{"꺼짐", "Off"}),
            Map.entry("settings.hint", new String[]{"Esc 닫기 · Enter 적용", "Esc close · Enter apply"}),
            Map.entry("settings.restoreDefaults", new String[]{"기본값 복원", "Restore Defaults"}),
            Map.entry("settings.restoreDefaults.confirm", new String[]{
                    "모든 설정(디스플레이/게임/사운드/키 설정)을 기본값으로 되돌릴까요?",
                    "Restore every setting (Display/Game/Sound/Controls) to its default?"}),
            Map.entry("settings.exitGame", new String[]{"니아 리듬게임 전원 끄기", "Turn Off Niah Rhythm Game"}),
            Map.entry("settings.exitGame.confirm", new String[]{
                    "니아 리듬게임을 종료할까요? 진행 중인 게임이 있다면 저장되지 않습니다.",
                    "Turn off Niah Rhythm Game? Any game in progress will not be saved."}),
            Map.entry("settings.autoPauseOnFocusLoss", new String[]{"포커스 아웃 시 자동 일시정지", "Auto-pause when unfocused"}),
            Map.entry("settings.background", new String[]{"배경 애니메이션 (유튜브 URL)", "Background animation (YouTube URL)"}),
            Map.entry("settings.background.enabled", new String[]{"배경 애니메이션 켜기", "Enable background animation"}),
            Map.entry("settings.background.thumbnail", new String[]{"배경 썸네일 (애니메이션 꺼졌을 때)", "Background thumbnail (when animation is off)"}),
            Map.entry("settings.background.download", new String[]{"다운로드/추출", "Download & extract"}),
            Map.entry("settings.background.aspect", new String[]{"화면비", "Aspect"}),
            Map.entry("settings.background.aspect.fit", new String[]{"원본 비율 유지", "Fit (keep ratio)"}),
            Map.entry("settings.background.aspect.fill", new String[]{"꽉 채우기", "Fill (crop)"}),
            Map.entry("settings.background.aspect.stretch", new String[]{"늘리기", "Stretch"}),
            Map.entry("settings.background.brightness", new String[]{"밝기", "Brightness"}),
            Map.entry("settings.background.extracting", new String[]{"영상을 받아 프레임을 뽑는 중…", "Downloading and extracting frames…"}),
            Map.entry("settings.background.done", new String[]{"프레임 준비 완료", "Frames ready"}),
            Map.entry("settings.background.failed", new String[]{"배경 준비 실패", "Background setup failed"}),

            // Sound tab
            Map.entry("settings.outputDevice", new String[]{"재생 장치", "Output device"}),
            Map.entry("settings.outputDevice.default", new String[]{"(시스템 기본)", "(System default)"}),
            Map.entry("settings.musicVolume", new String[]{"연주 음악 음량", "Music volume"}),
            Map.entry("settings.sfxVolume", new String[]{"효과음 음량", "Sound effect volume"}),
            Map.entry("settings.voiceEnabled", new String[]{"보이스 켜기", "Enable voice"}),
            Map.entry("settings.voiceVolume", new String[]{"보이스 음량", "Voice volume"}),
            Map.entry("settings.continueSoundWhenUnfocused", new String[]{"백그라운드에서도 소리 재생", "Keep playing sound when unfocused"}),

            // Controls tab
            Map.entry("settings.keys", new String[]{"키 설정", "Key bindings"}),
            Map.entry("settings.keys.pressNew", new String[]{"키를 누르세요…", "Press a key…"}),
            Map.entry("settings.keys.side", new String[]{"사이드 노트 키", "Side note key"}),

            // Rhythm gameplay
            Map.entry("game.pause", new String[]{"ESC 일시정지 · R 다시 시작", "ESC pause · R restart"}),
            Map.entry("game.pauseMenu.continue", new String[]{"계속하기", "CONTINUE"}),
            Map.entry("game.pauseMenu.restart", new String[]{"다시 시작", "RESTART"}),
            Map.entry("game.pauseMenu.musicSelect", new String[]{"곡 선택", "MUSIC SELECT"}),
            Map.entry("game.pauseMenu.exit", new String[]{"나가기", "EXIT"}),
            Map.entry("game.pauseMenu.hint", new String[]{"↑↓ 선택 · Enter 확정", "↑↓ select · Enter confirm"}),
            Map.entry("game.pauseTitle", new String[]{"PAUSE", "PAUSE"}),
            Map.entry("game.over", new String[]{"GAME OVER", "GAME OVER"}),
            Map.entry("game.practiceMode", new String[]{"연습 모드 — 게임오버 없음", "PRACTICE MODE — no game over"}),
            Map.entry("game.results.hint", new String[]{"R 다시 시작 · 그 외 아무 키나 누르면 닫힙니다",
                    "R to restart · any other key closes"}),
            Map.entry("game.results.accuracy", new String[]{"정확도", "Accuracy"})
    );
}
