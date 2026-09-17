package djmax;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

/**
 * A cheap "background video": a handful of PNG frames extracted once from a downloaded clip,
 * cycled at a low fps. Not a real video player (Java has no built-in video codec support) — this
 * trades smoothness for something that needs no extra runtime and cannot hang or crash mid-song.
 */
final class BackgroundAnimator {
    static final int FPS = 2;
    private static final int MAX_FRAMES = 48; // 24s of loop content at FPS=2
    private static final long TIMEOUT_SECONDS = 180;

    static final String ASPECT_FIT = "fit";
    static final String ASPECT_FILL = "fill";
    static final String ASPECT_STRETCH = "stretch";

    private final BufferedImage[] frames;
    private final long startedAtMs = System.currentTimeMillis();

    private BackgroundAnimator(BufferedImage[] frames) {
        this.frames = frames;
    }

    /** @return a player for the frames already extracted under {@code framesDir}, or {@code null} if none. */
    static BackgroundAnimator load(Path framesDir) {
        File[] files = framesDir.toFile().listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".png"));
        if (files == null || files.length == 0) {
            return null;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<BufferedImage> images = new ArrayList<>();
        for (File f : files) {
            try {
                BufferedImage img = ImageIO.read(f);
                if (img != null) {
                    images.add(img);
                }
            } catch (IOException ignored) {
                // skip a corrupt frame rather than fail the whole background
            }
        }
        return images.isEmpty() ? null : new BackgroundAnimator(images.toArray(new BufferedImage[0]));
    }

    private BufferedImage currentFrame() {
        long elapsed = System.currentTimeMillis() - startedAtMs;
        int idx = (int) ((elapsed / (1000L / FPS)) % frames.length);
        return frames[idx];
    }

    /** Draws the current frame scaled per {@code aspectMode}, then a black dimmer for {@code brightnessPercent} (0=none, 100=black). */
    void paint(Graphics2D g, int w, int h, String aspectMode, int brightnessPercent) {
        paintImage(g, currentFrame(), w, h, aspectMode, brightnessPercent);
    }

    /** The same aspect-fit/fill/stretch + dimmer treatment as {@link #paint}, for any single
     *  image — shared with {@link RhythmPanel}'s static-thumbnail fallback background (a song's
     *  thumbnail when the animated background above isn't enabled/available for it). */
    static void paintImage(Graphics2D g, BufferedImage image, int w, int h, String aspectMode, int brightnessPercent) {
        int fw = image.getWidth();
        int fh = image.getHeight();
        int dw, dh, dx, dy;
        if (ASPECT_STRETCH.equals(aspectMode)) {
            dw = w;
            dh = h;
            dx = 0;
            dy = 0;
        } else {
            boolean fill = ASPECT_FILL.equals(aspectMode);
            double scale = fill ? Math.max(w / (double) fw, h / (double) fh) : Math.min(w / (double) fw, h / (double) fh);
            dw = (int) Math.round(fw * scale);
            dh = (int) Math.round(fh * scale);
            dx = (w - dw) / 2;
            dy = (h - dh) / 2;
        }
        g.drawImage(image, dx, dy, dw, dh, null);
        if (brightnessPercent > 0) {
            int alpha = Math.max(0, Math.min(255, (int) Math.round(255 * (brightnessPercent / 100.0))));
            g.setColor(new Color(0, 0, 0, alpha));
            g.fillRect(0, 0, w, h);
        }
    }

    /** Downloads a low-res clip and extracts up to {@link #MAX_FRAMES} PNG frames into {@code framesDir}, then deletes the clip. */
    static void download(String url, Path framesDir, File modTools, Logger log) throws Exception {
        File ytDlp = requireTool("yt-dlp.exe", modTools);
        File ffmpeg = requireTool("ffmpeg.exe", modTools);

        Files.createDirectories(framesDir);
        File[] existing = framesDir.toFile().listFiles();
        if (existing != null) {
            for (File old : existing) {
                Files.deleteIfExists(old.toPath());
            }
        }
        File videoFile = framesDir.resolveSibling("background-source.mp4").toFile();
        Files.deleteIfExists(videoFile.toPath());

        List<String> download = List.of(
                ytDlp.getAbsolutePath(),
                "-f", "bv*[height<=480][ext=mp4]/b[height<=480]/worst",
                "--no-playlist", "--no-progress",
                "--ffmpeg-location", ffmpeg.getAbsolutePath(),
                "-o", videoFile.getAbsolutePath(),
                url.strip());
        log.info("배경 영상 다운로드: " + String.join(" ", download));
        runAndWait(download, log);

        if (!videoFile.isFile()) {
            throw new IOException("배경 영상 다운로드에 실패했습니다 (파일이 만들어지지 않음)");
        }

        List<String> extract = List.of(
                ffmpeg.getAbsolutePath(), "-y",
                "-i", videoFile.getAbsolutePath(),
                "-vf", "fps=" + FPS + ",scale=480:-1",
                "-frames:v", String.valueOf(MAX_FRAMES),
                framesDir.resolve("frame_%03d.png").toString());
        log.info("배경 프레임 추출: " + String.join(" ", extract));
        runAndWait(extract, log);

        Files.deleteIfExists(videoFile.toPath());

        File[] produced = framesDir.toFile().listFiles((d, n) -> n.endsWith(".png"));
        if (produced == null || produced.length == 0) {
            throw new IOException("프레임이 하나도 추출되지 않았습니다");
        }
    }

    private static void runAndWait(List<String> cmd, Logger log) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process proc = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.fine(line);
            }
        }
        boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("시간 초과 (" + TIMEOUT_SECONDS + "초)");
        }
        if (proc.exitValue() != 0) {
            throw new IOException("실패 (exit " + proc.exitValue() + ")");
        }
    }

    private static File requireTool(String exeName, File modTools) throws IOException {
        if (modTools != null) {
            File candidate = new File(modTools, exeName);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                File candidate = new File(dir, exeName);
                if (candidate.isFile()) {
                    return candidate;
                }
            }
        }
        throw new IOException(exeName + "를 찾지 못했습니다"
                + (modTools != null ? " (찾아본 곳: " + modTools.getAbsolutePath() + ")" : "") + ".");
    }
}
