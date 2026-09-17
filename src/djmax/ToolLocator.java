package djmax;

import java.io.File;
import java.nio.file.Path;

/**
 * Finds this plugin's own "tools" folder — {@code <plugindata>\tools}, a sibling of {@code
 * library\}/{@code charts\}/{@code background\} under {@link
 * com.group_finity.mascot.lumi.plugin.PluginContext#dataDir()} — where a user drops {@code
 * yt-dlp.exe} and {@code ffmpeg.exe} for {@link YoutubeImporter}/{@link BackgroundAnimator} to use.
 * <p>
 * Deliberately NOT under the mod's own distributed folder ({@code mods\Niah\}) the way an earlier
 * version of this had it: Little LUMI 1.4.0 started refusing to load any code mod whose own folder
 * contains an executable at all (previously this only affected Workshop uploads, via {@code
 * pack_workshop.py}'s "실행 파일은 절대 가지 않습니다" exclusion — a purely local mod folder used to be
 * fine). {@code plugindata\}, this plugin's private runtime storage, isn't distributed mod content
 * and isn't subject to that check, so the tools live there instead.
 */
final class ToolLocator {
    private ToolLocator() {
    }

    /** {@code <plugindata>\tools}, given this plugin's data directory. */
    static File modTools(Path dataDir) {
        return dataDir == null ? null : dataDir.resolve("tools").toFile();
    }
}
