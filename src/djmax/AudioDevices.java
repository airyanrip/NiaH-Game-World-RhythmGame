package djmax;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import java.util.ArrayList;
import java.util.List;

/** Lists output devices (Mixers) that can actually hand out a {@link Clip}, for a settings picker. */
final class AudioDevices {
    private AudioDevices() {
    }

    static List<String> listNames() {
        List<String> names = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (supportsClip(info)) {
                names.add(info.getName());
            }
        }
        return names;
    }

    /** @return the matching {@link Mixer.Info}, or {@code null} for "system default" (blank/not found). */
    static Mixer.Info find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (info.getName().equals(name) && supportsClip(info)) {
                return info;
            }
        }
        return null;
    }

    /** Opens a {@link Clip} on the named mixer, falling back to the system default if unavailable. */
    static Clip openClip(String mixerName) throws Exception {
        Mixer.Info info = find(mixerName);
        return info == null ? AudioSystem.getClip() : AudioSystem.getClip(info);
    }

    private static boolean supportsClip(Mixer.Info info) {
        try {
            Mixer mixer = AudioSystem.getMixer(info);
            return mixer.isLineSupported(new Line.Info(Clip.class));
        } catch (Exception e) {
            return false;
        }
    }
}
