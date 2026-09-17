package djmax;

import com.group_finity.mascot.lumi.plugin.LumiPlugin;
import com.group_finity.mascot.lumi.plugin.PluginContext;

/** Entry point: one tray/character-menu row opens the Hub (song library, settings, play, chart editor). */
public final class DjmaxPlugin implements LumiPlugin {

    @Override
    public void start(PluginContext ctx) throws Exception {
        ctx.addTrayItem("니아 리듬게임", () -> ctx.onEdt(() -> HubWindow.open(ctx)));
        ctx.addCharacterMenuItem("니아 리듬게임", "Niah"::equals,
                (imageSet, mascotId) -> ctx.onEdt(() -> HubWindow.open(ctx)));
    }

    @Override
    public void stop() {
    }
}
