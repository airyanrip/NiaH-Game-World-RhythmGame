package djmax;

import java.awt.Image;
import java.awt.Window;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Everything the game code needs from whatever it's running inside of — a Little LUMI plugin
 * ({@link LittleLumiGameHost}, wrapping a real {@code PluginContext}) or the standalone build
 * ({@link StandaloneGameHost}, with its own data folder/settings file/window handling and no
 * mascot on screen to push aside). {@link HubWindow}, {@link HubPanel} and {@link SettingsScreen}
 * only ever talk to this interface — none of them import anything from
 * {@code com.group_finity.mascot.lumi.plugin}, so all three compile and run with no Little LUMI
 * SDK jar on the classpath at all.
 */
interface GameHost {
    /** This mod's own folder for saved songs, charts, settings, and downloaded tools. */
    Path dataDir();

    Logger log();

    /** Runs {@code task} on the Swing event thread. */
    void onEdt(Runnable task);

    GamePrefs prefs();

    /** The host app's icon, for the game window's title bar/taskbar entry — {@code null} to just
     *  leave Swing's default. */
    Image appIcon();

    /** Remembers/restores {@code window}'s position under {@code key} the way the host app does
     *  for its own windows — a no-op is perfectly fine (the window just keeps whatever position
     *  Swing gave it). */
    void manageWindow(Window window, String key);

    /** Credits a bonus outside this mod's own save data — Little LUMI's app-wide character
     *  affection, when there's a character to award it to; a no-op otherwise. This mod's own
     *  rhythm-game level ({@link RhythmSettings#addRhythmXp}) is tracked separately and always
     *  works regardless of what this returns. */
    void awardBonus(String source, int points);

    /** The "push mascots aside while playing, bring them back after" integration — {@link
     *  MascotIntegration#NONE} where there's no mascot at all. */
    MascotIntegration mascotIntegration();
}
