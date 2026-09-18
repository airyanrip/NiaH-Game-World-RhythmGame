package djmax;

import javax.swing.SwingUtilities;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/** {@link GameHost} for {@link StandaloneMain} — everything a Little LUMI plugin would normally
 *  get from the app (a data folder, settings storage, window placement, an icon) instead comes
 *  from this mod's own little corner of the user's home folder. There is no desktop mascot at all
 *  in this build, so {@link #mascotIntegration()} is {@link MascotIntegration#NONE} and {@link
 *  #awardBonus} is a no-op — this mod's own rhythm-game level ({@link RhythmSettings#addRhythmXp})
 *  is unaffected either way, since that's always stored in {@link #prefs()}, never through
 *  {@link #awardBonus}. */
final class StandaloneGameHost implements GameHost {
    private static final Logger LOG = Logger.getLogger("niah");

    private final Path dataDir;
    private final GamePrefs prefs;
    private boolean windowPlacedOnce;

    StandaloneGameHost() {
        // A dot-folder under the user's home, same idea as any other small standalone tool that
        // isn't installed through a package manager — nothing here depends on where the jar itself
        // sits, so it can be moved or renamed freely without losing saved songs/settings.
        this.dataDir = Path.of(System.getProperty("user.home"), ".niah-game-world");
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            LOG.warning("cannot create " + dataDir + ": " + e);
        }
        this.prefs = new StandalonePrefs(dataDir.resolve("settings.properties"));
    }

    @Override
    public Path dataDir() {
        return dataDir;
    }

    @Override
    public Logger log() {
        return LOG;
    }

    @Override
    public void onEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    @Override
    public GamePrefs prefs() {
        return prefs;
    }

    @Override
    public Image appIcon() {
        // Bundled at build time (see build_standalone.py) if an icon.png sits next to it — quietly
        // falls back to Swing's own default icon otherwise, exactly like leaving setIconImage
        // uncalled.
        try (InputStream in = StandaloneGameHost.class.getResourceAsStream("/icon.png")) {
            return in == null ? null : Toolkit.getDefaultToolkit().createImage(in.readAllBytes());
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void manageWindow(Window window, String key) {
        // No app to remember this across restarts for — centering it once, the first time it's
        // ever placed, is exactly what a plain new Swing window would do anyway.
        if (!windowPlacedOnce) {
            windowPlacedOnce = true;
            window.setLocationRelativeTo(null);
        }
    }

    @Override
    public void awardBonus(String source, int points) {
        // No character, no affection system — this mod's own level (RhythmSettings.addRhythmXp)
        // already tracks progress entirely on its own.
    }

    @Override
    public MascotIntegration mascotIntegration() {
        return MascotIntegration.NONE;
    }
}
