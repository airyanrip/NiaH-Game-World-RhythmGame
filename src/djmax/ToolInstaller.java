package djmax;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Fetches {@code yt-dlp.exe}/{@code ffmpeg.exe} straight into this plugin's own private
 * {@code tools\} folder the first time either is missing, so a player never has to go find and
 * place these themselves — see {@link YoutubeImporter}/{@link BackgroundAnimator}'s own class docs
 * for why neither binary ships with the mod in the first place (Little LUMI 1.4.0 refuses to load
 * any mod folder containing an executable at all). Nothing here ever touches the distributed mod
 * folder: both downloads land only in {@code plugindata\<id>\tools\}, which that check never scans.
 * A failure here (offline, blocked network, the download host is down) is left for the caller to
 * handle — {@link YoutubeImporter}/{@link BackgroundAnimator} already fall back to their own
 * "여기 수동으로 넣어주세요" message once the tools still aren't there afterward.
 */
final class ToolInstaller {
    private ToolInstaller() {
    }

    // GitHub's "latest" redirect always resolves to whatever the newest release's asset is —
    // no version number to keep updated here.
    private static final String YT_DLP_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
    // A standard, actively maintained static Windows build ("essentials": ffmpeg/ffprobe/ffplay
    // plus the common codecs, no extras) — the same one this mod's own README already points
    // players to for a manual install.
    private static final String FFMPEG_ZIP_URL = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";

    @FunctionalInterface
    interface Progress {
        void update(String message);
    }

    /** Downloads whichever of yt-dlp.exe/ffmpeg.exe is missing from {@code toolsDir} — call off the
     *  EDT (this blocks on network I/O). A no-op (returns immediately) once both are already
     *  present, so callers can call this unconditionally before every use. */
    static void ensureTools(Path toolsDir, Progress progress) throws IOException, InterruptedException {
        Files.createDirectories(toolsDir);
        Path ytDlp = toolsDir.resolve("yt-dlp.exe");
        Path ffmpeg = toolsDir.resolve("ffmpeg.exe");
        if (Files.isRegularFile(ytDlp) && Files.isRegularFile(ffmpeg)) {
            return;
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL) // both URLs below redirect to a CDN asset
                .build();
        if (!Files.isRegularFile(ytDlp)) {
            progress.update("yt-dlp.exe 받는 중…");
            downloadFile(client, YT_DLP_URL, ytDlp);
        }
        if (!Files.isRegularFile(ffmpeg)) {
            progress.update("ffmpeg.exe 받는 중… (파일이 커서 조금 걸릴 수 있습니다)");
            downloadFfmpegFromZip(client, ffmpeg);
        }
    }

    private static void downloadFile(HttpClient client, String url, Path dest) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
        if (response.statusCode() / 100 != 2) {
            Files.deleteIfExists(tmp);
            throw new IOException(url + " 다운로드 실패 (HTTP " + response.statusCode() + ")");
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    /** ffmpeg's official Windows builds are only distributed as a zip (containing ffmpeg.exe,
     *  ffprobe.exe, ffplay.exe, docs, ...) — this downloads that zip to a temp file, pulls out just
     *  the one entry ending in {@code bin/ffmpeg.exe} (the enclosing folder name embeds a version/
     *  date that changes release to release, so this matches by suffix rather than a fixed path),
     *  and discards the rest. */
    private static void downloadFfmpegFromZip(HttpClient client, Path ffmpegExeDest) throws IOException, InterruptedException {
        Path tmpZip = ffmpegExeDest.resolveSibling("ffmpeg-download.zip.part");
        HttpRequest request = HttpRequest.newBuilder(URI.create(FFMPEG_ZIP_URL))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmpZip));
        if (response.statusCode() / 100 != 2) {
            Files.deleteIfExists(tmpZip);
            throw new IOException("ffmpeg 다운로드 실패 (HTTP " + response.statusCode() + ")");
        }
        try {
            boolean found = false;
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(tmpZip))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName().replace('\\', '/');
                    if (!entry.isDirectory() && name.endsWith("/bin/ffmpeg.exe")) {
                        Path tmpExe = ffmpegExeDest.resolveSibling(ffmpegExeDest.getFileName() + ".part");
                        Files.copy(zip, tmpExe, StandardCopyOption.REPLACE_EXISTING);
                        Files.move(tmpExe, ffmpegExeDest, StandardCopyOption.REPLACE_EXISTING);
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                throw new IOException("내려받은 압축 파일 안에서 ffmpeg.exe를 찾지 못했습니다.");
            }
        } finally {
            Files.deleteIfExists(tmpZip);
        }
    }
}
