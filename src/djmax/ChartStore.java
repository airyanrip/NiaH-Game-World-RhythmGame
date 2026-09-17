package djmax;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-authored charts, one plain-text file per song: a couple of "# key=value" header lines
 * (song file name, audio length, note fall-speed multiplier) followed by one line per note —
 * "timeMs\tlane" for a tap, "timeMs\tlane\tendTimeMs" for a long note (endTimeMs > timeMs), or
 * "timeMs\tlane\tendTimeMs\tSIDE" for a side-track note (lane is unused/written as 0). The 4th
 * field is new — its absence means NORMAL, so every chart saved before {@link Note.Kind#SIDE}
 * existed still loads unchanged. Likewise "# speedMultiplier=" is missing from any chart saved
 * before the chart editor could set it, which loads as 1.0 (neutral, matching what those charts
 * always played at). Kept human-readable on purpose — this lives under {@code plugindata\}, not
 * the mod's shipped content, so none of the Workshop packaging extension rules apply to it.
 */
final class ChartStore {
    private ChartStore() {
    }

    static Path fileFor(Path chartsDir, File songFile) {
        return chartsDir.resolve(songFile.getName() + ".chart");
    }

    static boolean exists(Path chartsDir, File songFile) {
        return Files.isRegularFile(fileFor(chartsDir, songFile));
    }

    static void delete(Path chartsDir, File songFile) {
        try {
            Files.deleteIfExists(fileFor(chartsDir, songFile));
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** @return the saved chart, or {@code null} if this song has none. */
    static Chart load(Path chartsDir, File songFile, String title) throws IOException {
        Path file = fileFor(chartsDir, songFile);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        long lengthMs = 0;
        double speedMultiplier = 1.0;
        List<Note> notes = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("#")) {
                    if (line.startsWith("# lengthMs=")) {
                        try {
                            lengthMs = Long.parseLong(line.substring("# lengthMs=".length()).strip());
                        } catch (NumberFormatException ignored) {
                            // keep 0; a stale/hand-edited header should not crash the load
                        }
                    } else if (line.startsWith("# speedMultiplier=")) {
                        try {
                            speedMultiplier = Double.parseDouble(line.substring("# speedMultiplier=".length()).strip());
                        } catch (NumberFormatException ignored) {
                            // keep 1.0; a stale/hand-edited header should not crash the load
                        }
                    }
                    continue;
                }
                String[] parts = line.split("\\t");
                if (parts.length < 2 || parts.length > 4) {
                    continue;
                }
                try {
                    long timeMs = Long.parseLong(parts[0].strip());
                    int lane = Integer.parseInt(parts[1].strip());
                    long endTimeMs = parts.length >= 3 ? Long.parseLong(parts[2].strip()) : timeMs;
                    boolean side = parts.length == 4 && "SIDE".equals(parts[3].strip());
                    if (side) {
                        notes.add(new Note(timeMs, endTimeMs, -1, Note.Kind.SIDE));
                    } else if (lane >= 0 && lane < RhythmSettings.LANES) {
                        notes.add(new Note(timeMs, endTimeMs, lane));
                    }
                } catch (NumberFormatException ignored) {
                    // skip a malformed line rather than fail the whole chart
                }
            }
        }
        notes.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        return new Chart(songFile, title, lengthMs, notes, speedMultiplier);
    }

    static void save(Path chartsDir, File songFile, long lengthMs, List<Note> notes, double speedMultiplier) throws IOException {
        Files.createDirectories(chartsDir);
        Path file = fileFor(chartsDir, songFile);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        List<Note> sorted = new ArrayList<>(notes);
        sorted.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write("# niah-chart v2\n");
            w.write("# song=" + songFile.getName() + "\n");
            w.write("# lengthMs=" + lengthMs + "\n");
            w.write("# speedMultiplier=" + speedMultiplier + "\n");
            for (Note n : sorted) {
                if (n.kind == Note.Kind.SIDE) {
                    w.write(n.timeMs + "\t0\t" + n.endTimeMs + "\tSIDE\n");
                } else {
                    w.write(n.isHold()
                            ? n.timeMs + "\t" + n.lane + "\t" + n.endTimeMs + "\n"
                            : n.timeMs + "\t" + n.lane + "\n");
                }
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
