package djmax;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The song library folder ({@code plugindata\niah_rythmgame\library\*.wav}) — both a WAV you
 * imported and a song downloaded from YouTube land here, side by side, so the Hub shows one list.
 */
final class SongLibrary {
    private static final Pattern YOUTUBE_ID_SUFFIX = Pattern.compile("\\s*\\[[A-Za-z0-9_-]{6,15}]$");
    private static final String[] THUMBNAIL_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"};

    /** @param thumbnail a same-named image next to the WAV (yt-dlp writes one for a YouTube
     *  import — see {@link YoutubeImporter}), or {@code null} for a WAV added by hand.
     *  @param channel the uploader/channel name yt-dlp reported at download time, or {@code ""}
     *  for a WAV added by hand (or an import where yt-dlp couldn't report one). */
    record Song(File file, String title, long sizeBytes, long durationMs, File thumbnail, String channel) {
    }

    /** ADDED reads the WAV's own last-modified time — the closest thing to "when this was added"
     *  without a separate log, since importing (a copy) or downloading (a fresh write) both set it
     *  to roughly that moment and nothing in this mod touches it afterward. */
    enum SortOrder { ADDED, TITLE, DURATION }

    private final Path libraryDir;

    SongLibrary(Path libraryDir) {
        this.libraryDir = libraryDir;
    }

    Path dir() {
        return libraryDir;
    }

    List<Song> list() {
        return list(SortOrder.TITLE);
    }

    List<Song> list(SortOrder order) {
        List<Song> songs = new ArrayList<>();
        File[] files = libraryDir.toFile().listFiles((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".wav"));
        if (files != null) {
            for (File f : files) {
                songs.add(new Song(f, titleFrom(f), f.length(), durationOf(f), thumbnailFor(f), channelFor(f)));
            }
        }
        songs.sort(comparatorFor(order));
        return songs;
    }

    private static Comparator<Song> comparatorFor(SortOrder order) {
        return switch (order) {
            case ADDED -> Comparator.comparingLong(s -> s.file().lastModified());
            case DURATION -> Comparator.comparingLong(Song::durationMs);
            case TITLE -> Comparator.comparing(s -> s.title().toLowerCase(java.util.Locale.ROOT));
        };
    }

    /** Copies an external WAV into the library, renaming on a collision rather than overwriting. */
    File importWav(File source) throws IOException {
        Files.createDirectories(libraryDir);
        File target = uniqueTarget(source.getName());
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }

    private File uniqueTarget(String name) {
        File candidate = libraryDir.resolve(name).toFile();
        if (!candidate.exists()) {
            return candidate;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            File next = libraryDir.resolve(base + " (" + i + ")" + ext).toFile();
            if (!next.exists()) {
                return next;
            }
        }
        return libraryDir.resolve(base + " (" + System.currentTimeMillis() + ")" + ext).toFile();
    }

    void delete(File song) {
        try {
            Files.deleteIfExists(song.toPath());
        } catch (IOException ignored) {
            // best-effort
        }
        File thumb = thumbnailFor(song);
        if (thumb != null) {
            try {
                Files.deleteIfExists(thumb.toPath());
            } catch (IOException ignored) {
                // best-effort
            }
        }
        try {
            Files.deleteIfExists(channelSidecar(song).toPath());
        } catch (IOException ignored) {
            // best-effort
        }
    }

    void deleteAll() {
        for (Song s : list()) {
            delete(s.file());
        }
    }

    long totalBytes() {
        long total = 0;
        for (Song s : list()) {
            total += s.sizeBytes();
        }
        return total;
    }

    private static String titleFrom(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return YOUTUBE_ID_SUFFIX.matcher(base).replaceAll("").strip();
    }

    /** Header-only read (no full decode) — cheap enough to call once per song every time the list
     *  is (re)built. @return 0 if the file can't be read as audio at all. */
    private static long durationOf(File wav) {
        try {
            AudioFileFormat aff = AudioSystem.getAudioFileFormat(wav);
            AudioFormat format = aff.getFormat();
            long frames = aff.getFrameLength();
            if (frames == AudioSystem.NOT_SPECIFIED || format.getFrameRate() <= 0) {
                return 0;
            }
            return Math.round(frames / format.getFrameRate() * 1000);
        } catch (Exception e) {
            return 0;
        }
    }

    /** A same-named image file next to {@code wav} (any of {@link #THUMBNAIL_EXTENSIONS}), or
     *  {@code null} if none exists. */
    private static File thumbnailFor(File wav) {
        String name = wav.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        File parent = wav.getParentFile();
        for (String ext : THUMBNAIL_EXTENSIONS) {
            File candidate = new File(parent, stem + ext);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    /** A same-stem {@code .channel.txt} next to {@code wav} (written by {@link
     *  YoutubeImporter#download} at import time), or {@code ""} if none exists. */
    private static String channelFor(File wav) {
        File sidecar = channelSidecar(wav);
        if (!sidecar.isFile()) {
            return "";
        }
        try {
            return Files.readString(sidecar.toPath(), java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "";
        }
    }

    private static File channelSidecar(File wav) {
        String name = wav.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return new File(wav.getParentFile(), stem + ".channel.txt");
    }
}
