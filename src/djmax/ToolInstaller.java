package djmax;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    // Cap how often the UI callback fires — a fast connection can run this read-loop thousands of
    // times a second, which would flood the Hub's status label with far more updates than it
    // could ever paint.
    private static final long PROGRESS_INTERVAL_MS = 300;

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
            downloadFile(client, YT_DLP_URL, ytDlp, "yt-dlp.exe", progress);
        }
        if (!Files.isRegularFile(ffmpeg)) {
            progress.update("ffmpeg.exe 받는 중… (파일이 커서 조금 걸릴 수 있습니다)");
            downloadFfmpegFromZip(client, ffmpeg, progress);
        }
    }

    private static void downloadFile(HttpClient client, String url, Path dest, String label, Progress progress)
            throws IOException, InterruptedException {
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        downloadWithProgress(client, url, tmp, label, progress);
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    /** ffmpeg's official Windows builds are only distributed as a zip (containing ffmpeg.exe,
     *  ffprobe.exe, ffplay.exe, docs, ...) — this downloads that zip to a temp file, pulls out just
     *  the one entry ending in {@code bin/ffmpeg.exe} (the enclosing folder name embeds a version/
     *  date that changes release to release, so this matches by suffix rather than a fixed path),
     *  and discards the rest. */
    private static void downloadFfmpegFromZip(HttpClient client, Path ffmpegExeDest, Progress progress)
            throws IOException, InterruptedException {
        Path tmpZip = ffmpegExeDest.resolveSibling("ffmpeg-download.zip.part");
        downloadWithProgress(client, FFMPEG_ZIP_URL, tmpZip, "ffmpeg.exe", progress);
        try {
            boolean found = false;
            progress.update("ffmpeg.exe 압축 푸는 중…");
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

    /** Streams {@code url} into {@code tmpDest} in 64KB chunks (rather than one blocking
     *  whole-file write) so {@code progress} can be told, every {@link #PROGRESS_INTERVAL_MS} at
     *  most, how much has landed so far (plus a percentage when the server sends a
     *  {@code Content-Length}) and the current download speed measured over that interval. */
    private static void downloadWithProgress(HttpClient client, String url, Path tmpDest, String label,
            Progress progress) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            throw new IOException(url + " 다운로드 실패 (HTTP " + response.statusCode() + ")");
        }
        long total = response.headers().firstValueAsLong("content-length").orElse(-1);
        long downloaded = 0;
        long windowStart = System.currentTimeMillis();
        long windowBytes = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = response.body();
                OutputStream out = Files.newOutputStream(tmpDest)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloaded += read;
                windowBytes += read;
                long now = System.currentTimeMillis();
                long elapsed = now - windowStart;
                if (elapsed >= PROGRESS_INTERVAL_MS) {
                    double speedBps = windowBytes * 1000.0 / Math.max(1, elapsed);
                    progress.update(formatProgress(label, downloaded, total, speedBps));
                    windowStart = now;
                    windowBytes = 0;
                }
            }
        }
        progress.update(label + " 받기 완료 (" + formatSize(downloaded) + ")");
    }

    private static String formatProgress(String label, long downloaded, long total, double speedBps) {
        StringBuilder sb = new StringBuilder(label).append(" 받는 중… ").append(formatSize(downloaded));
        if (total > 0) {
            sb.append(" / ").append(formatSize(total))
                    .append(" (").append(Math.min(100, (int) (downloaded * 100L / total))).append("%)");
        }
        sb.append(" - ").append(formatSize((long) speedBps)).append("/s");
        return sb.toString();
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format("%.1fMB", bytes / (1024.0 * 1024));
        }
        if (bytes >= 1024) {
            return String.format("%.0fKB", bytes / 1024.0);
        }
        return bytes + "B";
    }
}
