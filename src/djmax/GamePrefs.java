package djmax;

import java.nio.file.Path;

/**
 * The typed key/value store {@link RhythmSettings} reads and writes through — exactly the subset
 * of {@code com.group_finity.mascot.lumi.plugin.PluginPrefs}'s API this mod actually calls.
 * {@link LumiPrefsAdapter} implements this by delegating to a real {@code PluginPrefs} when running
 * as a Little LUMI plugin; {@link StandalonePrefs} implements it with its own properties file when
 * running standalone. Neither {@link RhythmSettings} nor anything it's used by needs to know which
 * one it has.
 */
interface GamePrefs {
    String get(String key, String fallback);

    int getInt(String key, int fallback);

    long getLong(String key, long fallback);

    boolean getBoolean(String key, boolean fallback);

    void set(String key, String value);

    void set(String key, int value);

    void set(String key, long value);

    void set(String key, boolean value);

    /** The file this store persists to — {@link RhythmSettings#dataDir()} reads its parent. */
    Path file();
}
