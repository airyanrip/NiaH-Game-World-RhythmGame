package djmax;

import com.group_finity.mascot.lumi.plugin.PluginPrefs;

import java.nio.file.Path;

/** {@link GamePrefs} backed by a real Little LUMI {@code PluginPrefs} — every call is a straight
 *  delegate. Only ever constructed by {@link LittleLumiGameHost}, never by the standalone build
 *  (which has no {@code PluginPrefs} to wrap in the first place — see {@link StandalonePrefs}). */
final class LumiPrefsAdapter implements GamePrefs {
    private final PluginPrefs prefs;

    LumiPrefsAdapter(PluginPrefs prefs) {
        this.prefs = prefs;
    }

    @Override
    public String get(String key, String fallback) {
        return prefs.get(key, fallback);
    }

    @Override
    public int getInt(String key, int fallback) {
        return prefs.getInt(key, fallback);
    }

    @Override
    public long getLong(String key, long fallback) {
        return prefs.getLong(key, fallback);
    }

    @Override
    public boolean getBoolean(String key, boolean fallback) {
        return prefs.getBoolean(key, fallback);
    }

    @Override
    public void set(String key, String value) {
        prefs.set(key, value);
    }

    @Override
    public void set(String key, int value) {
        prefs.set(key, value);
    }

    @Override
    public void set(String key, long value) {
        prefs.set(key, value);
    }

    @Override
    public void set(String key, boolean value) {
        prefs.set(key, value);
    }

    @Override
    public Path file() {
        return prefs.file();
    }
}
