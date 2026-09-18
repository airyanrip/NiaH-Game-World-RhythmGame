package djmax;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Entry point for the standalone build — {@code java -jar niah-game-world.jar}, no Little LUMI
 *  required. Opens the exact same Hub window the Little LUMI plugin does ({@link
 *  DjmaxPlugin#start} does the equivalent for that build), just handed a {@link
 *  StandaloneGameHost} instead of a real {@code PluginContext}-backed {@link LittleLumiGameHost}.
 *  See {@code build_standalone.py} for how this gets its own jar, built from a source set that
 *  excludes every file touching the Little LUMI SDK. */
public final class StandaloneMain {
    private StandaloneMain() {
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Swing's cross-platform default is a perfectly fine fallback.
        }
        SwingUtilities.invokeLater(() -> HubWindow.open(new StandaloneGameHost()));
    }
}
