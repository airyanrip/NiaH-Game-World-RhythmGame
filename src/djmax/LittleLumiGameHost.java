package djmax;

import com.group_finity.mascot.lumi.plugin.PluginContext;

import java.awt.Image;
import java.awt.Window;
import java.nio.file.Path;
import java.util.logging.Logger;

/** {@link GameHost} for the Little LUMI plugin build — a thin wrapper over the real {@code
 *  PluginContext} this mod is handed in {@code DjmaxPlugin.start()}. This is the only file besides
 *  {@link DjmaxPlugin} and {@link LittleLumiMascotIntegration} that imports anything from {@code
 *  com.group_finity.mascot.lumi.plugin} — none of it reaches {@link HubWindow}, {@link HubPanel},
 *  {@link SettingsScreen} or {@link RhythmSettings}, which only ever see this interface. */
final class LittleLumiGameHost implements GameHost {
    private final PluginContext ctx;
    private final GamePrefs prefs;
    private final MascotIntegration mascotIntegration;

    LittleLumiGameHost(PluginContext ctx) {
        this.ctx = ctx;
        this.prefs = new LumiPrefsAdapter(ctx.prefs());
        this.mascotIntegration = new LittleLumiMascotIntegration(ctx);
    }

    @Override
    public Path dataDir() {
        return ctx.dataDir();
    }

    @Override
    public Logger log() {
        return ctx.log();
    }

    @Override
    public void onEdt(Runnable task) {
        ctx.onEdt(task);
    }

    @Override
    public GamePrefs prefs() {
        return prefs;
    }

    @Override
    public Image appIcon() {
        return ctx.appIcon();
    }

    @Override
    public void manageWindow(Window window, String key) {
        ctx.manageWindow(window, key);
    }

    @Override
    public void awardBonus(String source, int points) {
        ctx.awardAffection(source, points);
    }

    @Override
    public MascotIntegration mascotIntegration() {
        return mascotIntegration;
    }
}
