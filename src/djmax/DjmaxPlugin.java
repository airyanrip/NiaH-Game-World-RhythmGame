package djmax;

import com.group_finity.mascot.lumi.plugin.LumiPlugin;
import com.group_finity.mascot.lumi.plugin.PluginContext;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Entry point: one tray/character-menu row opens the Hub (song library, settings, play, chart editor). */
public final class DjmaxPlugin implements LumiPlugin {

    // This mod's public/display name has since moved on to "NiaH's Game World" (plugin.json's
    // "name"), but its id stays "niah_rythmgame" deliberately — id changes are the risky part (see
    // below), so a display-only rename doesn't touch it. The id itself used to be "local.niah.djmax"
    // before an earlier rename. ctx.dataDir()/prefs() resolve entirely from the id, so an id change
    // alone would silently orphan every existing install's data (settings, song library, saved
    // charts, downloaded background frames, yt-dlp/ffmpeg tools) under the old folder —
    // migrateOldDataDirIfNeeded below copies it forward once, the first time this new id's own
    // folder is still empty.
    private static final String OLD_PLUGIN_ID = "local.niah.djmax";

    @Override
    public void start(PluginContext ctx) throws Exception {
        migrateOldDataDirIfNeeded(ctx);
        ctx.addTrayItem("니아의 게임월드", () -> ctx.onEdt(() -> HubWindow.open(ctx)));
        ctx.addCharacterMenuItem("니아의 게임월드", "Niah"::equals,
                (imageSet, mascotId) -> ctx.onEdt(() -> HubWindow.open(ctx)));
    }

    @Override
    public void stop() {
    }

    /** One-time copy of everything under the old id's plugindata folder into this (new-id) one —
     *  only when the new folder looks untouched (no settings.properties yet) and the old one
     *  actually has something to bring over; a genuinely fresh install (no old folder at all) is a
     *  cheap no-op. Copies rather than moves: the old folder is left alone afterward, so nothing is
     *  lost even if a user later reverts to a mod build still using the old id. Failure here must
     *  never stop the plugin from starting — worst case a returning user starts over fresh, same as
     *  a brand new install. */
    private static void migrateOldDataDirIfNeeded(PluginContext ctx) {
        try {
            Path newDir = ctx.dataDir(); // already created (possibly just now, empty) by this call
            Path oldDir = newDir.resolveSibling(OLD_PLUGIN_ID);
            if (oldDir.equals(newDir) || !Files.isDirectory(oldDir)) {
                return;
            }
            Path newSettings = newDir.resolve("settings.properties");
            Path oldSettings = oldDir.resolve("settings.properties");
            if (Files.exists(newSettings) || !Files.exists(oldSettings)) {
                return; // already migrated, or a genuinely fresh install with nothing old to bring over
            }
            copyRecursively(oldDir, newDir);
            ctx.log().info("이전 데이터 폴더(" + oldDir + ")를 새 폴더(" + newDir + ")로 이전했습니다.");
        } catch (Exception e) {
            ctx.log().warning("이전 데이터 이전 실패 (새로 시작합니다): " + e.getMessage());
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
