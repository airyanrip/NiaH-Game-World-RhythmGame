package djmax;

import java.util.Map;

/**
 * This plugin's own four-language UI text (Korean/English/Japanese/Chinese) — a small in-memory
 * table, not {@code PluginContext.text()}: that follows the *app's* language, but this is a
 * setting the player picks specifically for the rhythm game, independent of the app's own UI
 * language.
 */
enum Lang {
    KO, EN, JA, ZH;

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
        String[] row = TABLE.get(key);
        if (row == null) {
            return key;
        }
        return row[settings.language().ordinal()];
    }

    // key -> {ko, en, ja, zh}
    private static final Map<String, String[]> TABLE = Map.ofEntries(
            // Hub
            Map.entry("hub.title", new String[]{"니아의 게임월드", "Niah's Game World", "ニアのゲームワールド", "尼雅的游戏世界"}),
            Map.entry("hub.songs", new String[]{"곡 목록", "Songs", "曲目リスト", "曲目列表"}),
            Map.entry("hub.addWav", new String[]{"WAV 파일 추가", "Add WAV file", "WAVファイルを追加", "添加WAV文件"}),
            Map.entry("hub.addUrl", new String[]{"URL로 추가", "Add from URL", "URLから追加", "通过URL添加"}),
            Map.entry("hub.deleteSelected", new String[]{"선택 삭제", "Delete selected", "選択項目を削除", "删除所选"}),
            Map.entry("hub.deleteAll", new String[]{"전체 삭제", "Delete all", "すべて削除", "全部删除"}),
            Map.entry("hub.play", new String[]{"재생", "Play", "再生", "播放"}),
            Map.entry("hub.editChart", new String[]{"채보 편집", "Edit chart", "譜面編集", "编辑谱面"}),
            Map.entry("hub.settings", new String[]{"설정", "Settings", "設定", "设置"}),
            Map.entry("hub.practice", new String[]{"연습", "Practice", "練習", "练习"}),
            Map.entry("hub.difficulty", new String[]{"난이도", "Difficulty", "難易度", "难度"}),
            Map.entry("hub.sortOrder", new String[]{"정렬", "Sort", "並び替え", "排序"}),
            Map.entry("hub.sortOrder.added", new String[]{"추가된 순서", "Date added", "追加順", "添加顺序"}),
            Map.entry("hub.sortOrder.title", new String[]{"가나다순", "Title (A-Z)", "タイトル順", "标题顺序 (A-Z)"}),
            Map.entry("hub.sortOrder.duration", new String[]{"노래 길이순", "Duration", "再生時間順", "时长顺序"}),
            Map.entry("hub.practiceMode", new String[]{"연습모드", "Practice Mode", "練習モード", "练习模式"}),
            Map.entry("hub.levelUp", new String[]{"LEVEL UP!", "LEVEL UP!", "LEVEL UP!", "LEVEL UP!"}),
            Map.entry("hub.sideNotes", new String[]{"Shift키 노트", "Shift Key Notes", "Shiftキーノーツ", "Shift键音符"}),
            Map.entry("hub.autoPlay", new String[]{"퍼펙트 오토", "Perfect Auto", "パーフェクトオート", "完美自动"}),
            Map.entry("hub.downloading", new String[]{"유튜브에서 내려받는 중…", "Downloading from YouTube…", "YouTubeからダウンロード中…", "正在从YouTube下载…"}),
            Map.entry("hub.pickSongFirst", new String[]{"먼저 곡을 골라주세요.", "Pick a song first.", "まず曲を選んでください。", "请先选择一首歌曲。"}),
            Map.entry("hub.confirmDeleteAll", new String[]{"라이브러리의 모든 곡을 지울까요?", "Delete every song in the library?", "ライブラリ内のすべての曲を削除しますか？", "要删除曲库中的所有歌曲吗？"}),
            Map.entry("hub.loadFailed", new String[]{"불러오기 실패", "Load failed", "読み込み失敗", "加载失败"}),
            Map.entry("hub.playFailed", new String[]{"재생 실패", "Playback failed", "再生失敗", "播放失败"}),

            // Settings dialog — tabs
            Map.entry("settings.title", new String[]{"설정", "Settings", "設定", "设置"}),
            Map.entry("settings.tab.display", new String[]{"디스플레이", "Display", "ディスプレイ", "显示"}),
            Map.entry("settings.tab.game", new String[]{"게임", "Game", "ゲーム", "游戏"}),
            Map.entry("settings.tab.sound", new String[]{"사운드", "Sound", "サウンド", "声音"}),
            Map.entry("settings.tab.controls", new String[]{"컨트롤", "Controls", "操作設定", "操作控制"}),
            Map.entry("settings.close", new String[]{"닫기", "Close", "閉じる", "关闭"}),

            // Display tab
            Map.entry("settings.displayMode", new String[]{"디스플레이 모드", "Display mode", "ディスプレイモード", "显示模式"}),
            Map.entry("settings.gamePosition", new String[]{"게임 화면 위치", "Game screen position", "ゲーム画面の位置", "游戏画面位置"}),
            Map.entry("settings.gamePosition.left", new String[]{"왼쪽", "Left", "左", "左"}),
            Map.entry("settings.gamePosition.center", new String[]{"가운데", "Center", "中央", "居中"}),
            Map.entry("settings.gamePosition.right", new String[]{"오른쪽", "Right", "右", "右"}),
            Map.entry("settings.songListStyle", new String[]{"곡 목록 방식", "Song list style", "曲目リストの表示方式", "曲目列表样式"}),
            Map.entry("settings.songListStyle.list", new String[]{"목록형 (기존)", "List (classic)", "リスト形式（従来）", "列表形式（经典）"}),
            Map.entry("settings.songListStyle.carousel", new String[]{"휠 방식", "Wheel", "ホイール形式", "转盘形式"}),
            Map.entry("settings.noteStyle", new String[]{"노트 모양", "Note style", "ノーツの形", "音符样式"}),
            Map.entry("settings.noteStyle.ring", new String[]{"아케이드 드롭", "Arcade Drop", "アーケードドロップ", "街机掉落"}),
            Map.entry("settings.noteStyle.classic", new String[]{"직사각형", "Rectangle", "長方形", "矩形"}),
            Map.entry("settings.windowed", new String[]{"창 모드", "Windowed", "ウィンドウモード", "窗口模式"}),
            Map.entry("settings.fullscreen", new String[]{"전체화면", "Fullscreen", "フルスクリーン", "全屏"}),
            Map.entry("settings.antiAliasing", new String[]{"계단현상 방지 (안티에일리어싱)", "Anti-aliasing", "アンチエイリアス（ジャギー軽減）", "抗锯齿"}),
            Map.entry("settings.sideInfoPanel", new String[]{"곡 정보 카드 (빈 공간)", "Song info card (side margin)", "曲情報カード（余白部分）", "曲目信息卡（侧边空白）"}),
            Map.entry("settings.sideInfoPanel.opacity", new String[]{"곡 정보 카드 투명도", "Song info card opacity", "曲情報カードの不透明度", "曲目信息卡不透明度"}),
            Map.entry("settings.fpsLimit", new String[]{"초당 프레임 제한", "Frame rate limit", "フレームレート制限", "帧率限制"}),
            Map.entry("settings.colorVision", new String[]{"색각 보정", "Color vision", "色覚補正", "色觉校正"}),
            Map.entry("settings.colorVision.normal", new String[]{"보통", "Normal", "標準", "普通"}),
            Map.entry("settings.colorVision.protanopia", new String[]{"1형 (적색약)", "Protanopia", "1型（赤色弱）", "一型（红色弱）"}),
            Map.entry("settings.colorVision.deuteranopia", new String[]{"2형 (녹색약)", "Deuteranopia", "2型（緑色弱）", "二型（绿色弱）"}),
            Map.entry("settings.colorVision.tritanopia", new String[]{"3형 (청색약)", "Tritanopia", "3型（青色弱）", "三型（蓝色弱）"}),

            // Game tab
            Map.entry("settings.language", new String[]{"언어", "Language", "言語", "语言"}),
            Map.entry("settings.difficulty", new String[]{"난이도", "Difficulty", "難易度", "难度"}),
            Map.entry("settings.difficulty.easy", new String[]{"쉬움", "Easy", "簡単", "简单"}),
            Map.entry("settings.difficulty.normal", new String[]{"보통", "Normal", "普通", "普通"}),
            Map.entry("settings.difficulty.hard", new String[]{"어려움", "Hard", "難しい", "困难"}),
            Map.entry("settings.countdown", new String[]{"카운트다운", "Countdown", "カウントダウン", "倒计时"}),
            Map.entry("settings.countdown.suffix", new String[]{"초 (0=끄기)", "sec (0=off)", "秒（0＝オフ）", "秒（0=关闭）"}),
            Map.entry("settings.judgmentWindow", new String[]{"판정 타이밍 조절 (넉넉함 ↔ 빡빡함)", "Judgment timing (lenient ↔ strict)", "判定タイミング調整（緩い↔厳しい）", "判定时机调整（宽松↔严格）"}),
            Map.entry("settings.noteTiming", new String[]{"노트 출력 타이밍 조절", "Note timing offset", "ノーツ表示タイミング調整", "音符显示时机调整"}),
            Map.entry("settings.calibrate", new String[]{"보정 도우미", "Calibration wizard", "補正ウィザード", "校准向导"}),
            Map.entry("settings.apply", new String[]{"적용", "Apply", "適用", "应用"}),
            Map.entry("settings.applied", new String[]{"적용됨", "Applied", "適用済み", "已应用"}),
            Map.entry("settings.on", new String[]{"켜짐", "On", "オン", "开启"}),
            Map.entry("settings.off", new String[]{"꺼짐", "Off", "オフ", "关闭"}),
            Map.entry("settings.hint", new String[]{"Esc 닫기 · Enter 적용", "Esc close · Enter apply", "Esc 閉じる・Enter 適用", "Esc 关闭 · Enter 应用"}),
            Map.entry("settings.restoreDefaults", new String[]{"기본값 복원", "Restore Defaults", "初期設定に戻す", "恢复默认设置"}),
            Map.entry("settings.restoreDefaults.confirm", new String[]{
                    "모든 설정(디스플레이/게임/사운드/키 설정)을 기본값으로 되돌릴까요?",
                    "Restore every setting (Display/Game/Sound/Controls) to its default?",
                    "すべての設定（ディスプレイ／ゲーム／サウンド／操作設定）を初期値に戻しますか？",
                    "要将所有设置（显示/游戏/声音/操作）恢复为默认值吗？"}),
            Map.entry("settings.exitGame", new String[]{"니아의 게임월드 전원 끄기", "Turn Off Niah's Game World", "ニアのゲームワールドを終了", "关闭尼雅的游戏世界"}),
            Map.entry("settings.exitGame.confirm", new String[]{
                    "니아의 게임월드를 종료할까요? 진행 중인 게임이 있다면 저장되지 않습니다.",
                    "Turn off Niah's Game World? Any game in progress will not be saved.",
                    "ニアのゲームワールドを終了しますか？プレイ中のゲームがある場合、保存されません。",
                    "要关闭尼雅的游戏世界吗？进行中的游戏将不会被保存。"}),
            Map.entry("settings.autoPauseOnFocusLoss", new String[]{"포커스 아웃 시 자동 일시정지", "Auto-pause when unfocused", "フォーカスが外れたら自動一時停止", "失去焦点时自动暂停"}),
            Map.entry("settings.background", new String[]{"배경 애니메이션 (유튜브 URL)", "Background animation (YouTube URL)", "背景アニメーション（YouTube URL）", "背景动画（YouTube URL）"}),
            Map.entry("settings.background.enabled", new String[]{"배경 애니메이션 켜기", "Enable background animation", "背景アニメーションを有効にする", "启用背景动画"}),
            Map.entry("settings.background.thumbnail", new String[]{"배경 썸네일 (애니메이션 꺼졌을 때)", "Background thumbnail (when animation is off)", "背景サムネイル（アニメーションオフ時）", "背景缩略图（动画关闭时）"}),
            Map.entry("settings.background.download", new String[]{"다운로드/추출", "Download & extract", "ダウンロード／抽出", "下载并提取"}),
            Map.entry("settings.background.aspect", new String[]{"화면비", "Aspect", "アスペクト比", "画面比例"}),
            Map.entry("settings.background.aspect.fit", new String[]{"원본 비율 유지", "Fit (keep ratio)", "元の比率を維持", "保持原比例"}),
            Map.entry("settings.background.aspect.fill", new String[]{"꽉 채우기", "Fill (crop)", "画面を埋める（切り抜き）", "填满（裁剪）"}),
            Map.entry("settings.background.aspect.stretch", new String[]{"늘리기", "Stretch", "引き伸ばす", "拉伸"}),
            Map.entry("settings.background.brightness", new String[]{"밝기", "Brightness", "明るさ", "亮度"}),
            Map.entry("settings.background.extracting", new String[]{"영상을 받아 프레임을 뽑는 중…", "Downloading and extracting frames…", "動画をダウンロードしてフレームを抽出中…", "正在下载视频并提取帧…"}),
            Map.entry("settings.background.done", new String[]{"프레임 준비 완료", "Frames ready", "フレームの準備完了", "帧准备完成"}),
            Map.entry("settings.background.failed", new String[]{"배경 준비 실패", "Background setup failed", "背景の準備に失敗", "背景设置失败"}),

            // Sound tab
            Map.entry("settings.outputDevice", new String[]{"재생 장치", "Output device", "再生デバイス", "输出设备"}),
            Map.entry("settings.outputDevice.default", new String[]{"(시스템 기본)", "(System default)", "（システム標準）", "（系统默认）"}),
            Map.entry("settings.musicVolume", new String[]{"연주 음악 음량", "Music volume", "音楽音量", "音乐音量"}),
            Map.entry("settings.sfxVolume", new String[]{"효과음 음량", "Sound effect volume", "効果音音量", "音效音量"}),
            Map.entry("settings.voiceEnabled", new String[]{"보이스 켜기", "Enable voice", "ボイスを有効にする", "启用语音"}),
            Map.entry("settings.voiceVolume", new String[]{"보이스 음량", "Voice volume", "ボイス音量", "语音音量"}),
            Map.entry("settings.continueSoundWhenUnfocused", new String[]{"백그라운드에서도 소리 재생", "Keep playing sound when unfocused", "フォーカスが外れても音を再生し続ける", "失去焦点时继续播放声音"}),

            // Controls tab
            Map.entry("settings.keys", new String[]{"키 설정", "Key bindings", "キー設定", "按键设置"}),
            Map.entry("settings.keys.pressNew", new String[]{"키를 누르세요…", "Press a key…", "キーを押してください…", "请按下按键…"}),
            Map.entry("settings.keys.side", new String[]{"사이드 노트 키", "Side note key", "サイドノーツキー", "侧边音符键"}),

            // Rhythm gameplay
            Map.entry("game.pause", new String[]{"ESC 일시정지 · R 다시 시작", "ESC pause · R restart", "ESC 一時停止・R リスタート", "ESC 暂停 · R 重新开始"}),
            Map.entry("game.pauseMenu.continue", new String[]{"계속하기", "CONTINUE", "続ける", "继续"}),
            Map.entry("game.pauseMenu.restart", new String[]{"다시 시작", "RESTART", "リスタート", "重新开始"}),
            Map.entry("game.pauseMenu.musicSelect", new String[]{"곡 선택", "MUSIC SELECT", "曲選択", "选择曲目"}),
            Map.entry("game.pauseMenu.exit", new String[]{"나가기", "EXIT", "終了", "退出"}),
            Map.entry("game.pauseMenu.hint", new String[]{"↑↓ 선택 · Enter 확정", "↑↓ select · Enter confirm", "↑↓ 選択・Enter 決定", "↑↓ 选择 · Enter 确认"}),
            Map.entry("game.pauseTitle", new String[]{"PAUSE", "PAUSE", "PAUSE", "PAUSE"}),
            Map.entry("game.over", new String[]{"GAME OVER", "GAME OVER", "GAME OVER", "GAME OVER"}),
            Map.entry("game.practiceMode", new String[]{"연습 모드 — 게임오버 없음", "PRACTICE MODE — no game over", "練習モード — ゲームオーバーなし", "练习模式 — 无游戏结束"}),
            Map.entry("game.results.hint", new String[]{"R 다시 시작 · 그 외 아무 키나 누르면 닫힙니다",
                    "R to restart · any other key closes",
                    "R でリスタート・他のキーで閉じます",
                    "R 重新开始 · 按其他任意键关闭"}),
            Map.entry("game.results.accuracy", new String[]{"정확도", "Accuracy", "正確率", "准确率"})
    );
}
