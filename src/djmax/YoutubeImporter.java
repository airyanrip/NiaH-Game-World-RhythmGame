package djmax;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Downloads a song's audio from a URL via a user-supplied {@code yt-dlp.exe} (which in turn needs
 * {@code ffmpeg.exe}, either next to it or on PATH, to extract/convert audio to WAV), then hands
 * back a file ready for {@link ChartGenerator#fromWav}.
 * <p>
 * Neither binary ships with this plugin — drop {@code yt-dlp.exe} (and {@code ffmpeg.exe} unless
 * it is already on PATH) into this plugin's own {@code tools\} folder under its plugindata
 * directory (see {@link ToolLocator}) — NOT the mod's distributed folder itself: Little LUMI 1.4.0
 * refuses to load a code mod whose own folder contains any executable at all, even locally.
 * Both are official open-source projects with their own licenses; fetching and placing them is a
 * deliberate, separate step so this mod does not silently bundle third-party binaries.
 */
final class YoutubeImporter {
    private static final Pattern URL_PATTERN = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);
    private static final long TIMEOUT_SECONDS = 180;

    private YoutubeImporter() {
    }

    static final class MissingToolException extends Exception {
        MissingToolException(String message) {
            super(message);
        }
    }

    /**
     * @param modTools this mod's own {@code tools\} folder ({@link ToolLocator#modTools}), or
     *                  {@code null} if it could not be resolved — PATH is still tried.
     * @return the downloaded WAV file, cached under {@code cacheDir}.
     */
    static File download(String url, File cacheDir, File modTools, Logger log) throws Exception {
        if (url == null || !URL_PATTERN.matcher(url.strip()).find()) {
            throw new IllegalArgumentException("http(s):// URL이 아닙니다: " + url);
        }
        File ytDlp = findTool("yt-dlp.exe", modTools);
        if (ytDlp == null) {
            throw new MissingToolException(
                    "yt-dlp.exe를 찾지 못했습니다"
                            + (modTools != null ? " (찾아본 곳: " + modTools.getAbsolutePath() + ")" : "")
                            + ". 위 경로에 yt-dlp.exe를 넣어주세요 "
                            + "(공식 배포: https://github.com/yt-dlp/yt-dlp/releases). "
                            + "ffmpeg.exe도 함께 넣거나 PATH에 있어야 오디오 추출이 됩니다.");
        }
        Files.createDirectories(cacheDir.toPath());
        // Title first so the library list shows something readable; the id suffix keeps it unique
        // and lets SongLibrary strip it back off for display.
        String outTemplate = new File(cacheDir, "%(title).80s [%(id)s].%(ext)s").getAbsolutePath();

        List<String> cmd = List.of(
                ytDlp.getAbsolutePath(),
                "-x", "--audio-format", "wav", "--audio-quality", "0",
                // Same base name as the wav (yt-dlp swaps only the extension), converted to jpg so
                // SongLibrary can find it without caring what format the source thumbnail was in —
                // this is what lets the Hub list and the in-game background show it, with no extra
                // download step of their own.
                "--write-thumbnail", "--convert-thumbnails", "jpg",
                "--no-playlist", "--no-progress",
                "-o", outTemplate,
                // Both fields at the same "after_move" stage, tab-separated in one line — two
                // separate --print calls would risk landing on different lines with nothing to
                // correlate them back together. The fallback chain covers uploads that only set
                // one of the three; "NA" (yt-dlp's own placeholder when none resolve) is treated as
                // blank by the caller rather than shown as a channel name.
                "--print", "after_move:%(filepath)s\t%(uploader,channel,uploader_id)s",
                url.strip());
        log.info("yt-dlp 실행: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        File ffmpeg = findTool("ffmpeg.exe", modTools);
        if (ffmpeg != null) {
            String ffmpegDir = ffmpeg.getParentFile().getAbsolutePath();
            pb.environment().merge("PATH", ffmpegDir, (old, add) -> add + File.pathSeparator + old);
        }
        long startedAt = System.currentTimeMillis();
        Process proc = pb.start();
        StringBuilder out = new StringBuilder();
        // yt-dlp.exe (frozen) writes console/pipe text in the OS's native codepage (MS949 on Korean
        // Windows), not UTF-8 — confirmed by testing (Windows.setting PYTHONUTF8/PYTHONIOENCODING
        // on the child process does not change it). The file it writes to disk is correctly named
        // either way (NTFS paths are real Unicode); only this stdout text needs the native charset,
        // so a Korean title in --print output decodes correctly instead of becoming mojibake that
        // then fails to resolve to the real file. JEP 400 made Charset.defaultCharset() UTF-8
        // regardless of locale, so this has to come from "native.encoding" instead.
        java.nio.charset.Charset nativeEncoding = java.nio.charset.Charset.forName(
                System.getProperty("native.encoding", "MS949"));
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), nativeEncoding))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
                log.fine("yt-dlp: " + line);
            }
        }
        boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("yt-dlp가 " + TIMEOUT_SECONDS + "초 안에 끝나지 않았습니다 (시간 초과)");
        }
        if (proc.exitValue() != 0) {
            throw new IOException("yt-dlp 실패 (exit " + proc.exitValue() + "):\n" + lastLines(out.toString(), 8));
        }
        String printed = lastNonEmptyLine(out.toString());
        String path = null;
        String channel = null;
        if (printed != null) {
            int tab = printed.indexOf('\t');
            path = tab >= 0 ? printed.substring(0, tab) : printed;
            if (tab >= 0) {
                String field = printed.substring(tab + 1).strip();
                channel = field.isBlank() || field.equals("NA") ? null : field;
            }
        }
        File wav = path != null ? new File(path) : null;
        if (wav == null || !wav.isFile()) {
            // Fall back to "whatever .wav just appeared in cacheDir" — covers the odd case where the
            // printed path still doesn't match a real file (encoding quirks, unusual titles, etc.)
            // even with UTF-8 forced above, without failing a download that actually succeeded.
            wav = newestWavSince(cacheDir, startedAt);
        }
        if (wav == null || !wav.isFile()) {
            throw new IOException("yt-dlp가 결과 파일 경로를 알려주지 않았습니다:\n" + lastLines(out.toString(), 8));
        }
        if (channel != null) {
            writeChannelSidecar(wav, channel);
        }
        return wav;
    }

    /** A same-stem {@code .channel.txt} next to the WAV — {@link SongLibrary} reads it back the
     *  same way it already finds a same-stem thumbnail. Best-effort: a WAV with no channel info is
     *  no worse off than before this existed. */
    private static void writeChannelSidecar(File wav, String channel) {
        try {
            String name = wav.getName();
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            File sidecar = new File(wav.getParentFile(), stem + ".channel.txt");
            Files.writeString(sidecar.toPath(), channel, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static File newestWavSince(File cacheDir, long sinceEpochMs) {
        File[] files = cacheDir.listFiles((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".wav"));
        if (files == null) {
            return null;
        }
        File newest = null;
        for (File f : files) {
            if (f.lastModified() >= sinceEpochMs - 1000 && (newest == null || f.lastModified() > newest.lastModified())) {
                newest = f;
            }
        }
        return newest;
    }

    private static File findTool(String exeName, File modTools) {
        if (modTools != null) {
            File candidate = new File(modTools, exeName);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(Pattern.quote(File.pathSeparator))) {
                File candidate = new File(dir, exeName);
                if (candidate.isFile()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String lastNonEmptyLine(String text) {
        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].trim();
            }
        }
        return null;
    }

    private static String lastLines(String text, int n) {
        String[] lines = text.split("\\R");
        int start = Math.max(0, lines.length - n);
        return String.join("\n", Arrays.copyOfRange(lines, start, lines.length));
    }
}
