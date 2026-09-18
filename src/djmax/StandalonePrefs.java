package djmax;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/** {@link GamePrefs} for the standalone build — the same {@code settings.properties} shape a
 *  Little LUMI {@code PluginPrefs} would keep, just with no plugin id or app data folder in the
 *  picture: everything lives directly under whatever directory the standalone launcher hands in
 *  (see {@link StandaloneGameHost}). Loads lazily on first read, saves synchronously on every
 *  write (temp file + atomic move, falling back to a plain replace when the filesystem refuses
 *  the atomic move — same as what a real PluginPrefs does). */
final class StandalonePrefs implements GamePrefs {
    private final Path file;
    private final Properties values = new Properties();
    private boolean loaded;

    StandalonePrefs(Path file) {
        this.file = file;
    }

    @Override
    public synchronized String get(String key, String fallback) {
        load();
        String v = key == null ? null : values.getProperty(key);
        return v == null ? fallback : v;
    }

    @Override
    public int getInt(String key, int fallback) {
        String v = get(key, null);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public long getLong(String key, long fallback) {
        String v = get(key, null);
        if (v == null) {
            return fallback;
        }
        try {
            return Long.parseLong(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean fallback) {
        String v = get(key, null);
        return v == null ? fallback : Boolean.parseBoolean(v.strip());
    }

    @Override
    public void set(String key, String value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        synchronized (this) {
            load();
            values.setProperty(key, value);
            save();
        }
    }

    @Override
    public void set(String key, int value) {
        set(key, String.valueOf(value));
    }

    @Override
    public void set(String key, long value) {
        set(key, String.valueOf(value));
    }

    @Override
    public void set(String key, boolean value) {
        set(key, String.valueOf(value));
    }

    @Override
    public Path file() {
        return file;
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            values.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            Logger.getLogger("niah").log(Level.WARNING, "cannot read " + file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                values.store(new OutputStreamWriter(out, StandardCharsets.UTF_8), "Niah's Game World settings");
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Logger.getLogger("niah").log(Level.WARNING, "cannot save " + file, e);
        }
    }
}
