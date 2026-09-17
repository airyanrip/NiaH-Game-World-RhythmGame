package djmax;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Niah's voice barks — random lines played from a local, un-bundled folder
 * ({@code Downloads\NiaH_voice\NiaH_voice}), not copied into the mod's own folder: these look like
 * an unofficial extraction of Eternal Return's in-game voice files, not the official Nimble Neuron
 * fankit this mod otherwise sticks to (see {@code mods\Niah\NOTICE.txt} and the project's own
 * "원본 성우 wav 그대로 쓰지 않기" note). Referencing them externally means the shippable mod
 * folder never contains them; a missing file (e.g. on any other machine) is silently skipped,
 * never breaking gameplay.
 */
final class VoiceLines {
    private static final Path VOICE_DIR = Path.of(
            "C:\\Users\\AISW_AICOM\\Downloads\\NiaH_voice\\NiaH_voice");

    private static final String[] SHORT_PRESS = {
            "NiaH_attack1_05.wav", "NiaH_attack2_04.wav", "NiaH_attack2_05.wav", "NiaH_attack3_02.wav",
            "NiaH_attack3_03.wav", "NiaH_attack4_01.wav", "NiaH_attack4_02.wav", "NiaH_attack4_04.wav",
            "NiaH_attack4_03.wav", "NiaH_attack4_05.wav", "NiaH_attack5_05.wav", "NiaH_attack5_06.wav",
            "NiaH_attack5_08.wav", "NiaH_attack6_04.wav", "NiaH_attack6_05.wav", "NiaH_attack7_02.wav",
            "NiaH_attack7_03.wav", "NiaH_attack6_03.wav",
    };

    private static final String[] LONG_PRESS = {
            "NiaH_attack2_01.wav", "NiaH_attack2_01.wav", "NiaH_attack3_05.wav", "NiaH_attack5_03.wav",
            "NiaH_attack5_09.wav", "NiaH_attack5_10.wav", "NiaH_attack6_01.wav", "NiaH_attack6_02.wav",
            "NiaH_attack7_01.wav", "NiaH_attack7_04.wav", "NiaH_attack7_05.wav",
    };

    private static final String[] LONG_RELEASE = {
            "NiaH_attack5_01.wav", "NiaH_attack5_02.wav", "NiaH_attack5_04.wav", "NiaH_attack5_11.wav",
    };

    // This one pair always goes together — the release line was written as a direct continuation
    // of that specific long-press line, not a generic one.
    private static final String LONG_PRESS_FIXED_TRIGGER = "NiaH_attack7_05.wav";
    private static final String LONG_RELEASE_FIXED_REPLY = "NiaH_attack7_06.wav";

    private static final String[] GAME_START = {
            "NiaH_skillR1_01.wav", "NiaH_skillR1_02.wav", "NiaH_skillR1_03.wav", "NiaH_skillR1_04.wav",
    };

    private static final String[] FEVER_ENTER = {
            "NiaH_skillR2_05.wav", "NiaH_skillR2_06.wav", "NiaH_skillR2_07.wav", "NiaH_skillR2_08.wav",
            "NiaH_skillR2_09.wav", "NiaH_skillR2_10.wav", "NiaH_skillR2_11.wav", "NiaH_skillR2_12.wav",
    };

    private static final String[] CLEAR_PERFECT_FULL_COMBO = {
            "NiaH_kill1Player_1_01.wav", "NiaH_kill1Player_1_02.wav", "NiaH_kill9Player_1_01.wav",
            "NiaH_skillW1_01.wav", "NiaH_skillW1_02.wav", "NiaH_skillW1_03.wav", "NiaH_skillW1_04.wav",
            "NiaH_skillW1_05.wav", "NiaH_skillW1_06.wav", "NiaH_skillW1_07.wav", "NiaH_skillW1_08.wav",
            "NiaH_skillW1_09.wav",
    };

    private static final String[] CLEAR_FULL_COMBO = {
            "NiaH_kill7Player_1_01.wav", "NiaH_kill14Player_1_01.wav", "NiaH_skillW2_01.wav",
            "NiaH_skillW2_03.wav", "NiaH_skillW2_04.wav",
    };

    private static final String[] CLEAR_GOOD = {
            "NiaH_kill6Player_1_01.wav",
    };

    private static final String[] CLEAR_LOW_OR_FAIL = {
            "NiaH_killedByDoppelganger_1_01.wav", "NiaH_killedCommon_1_02.wav", "NiaH_killedCommon_1_03.wav",
            "NiaH_killedCommon_2_02.wav", "NiaH_killedCommon_2_03.wav", "NiaH_killedCommon_3_02.wav",
    };

    enum ClearGrade { PERFECT_FULL_COMBO, FULL_COMBO, GOOD, LOW_OR_FAIL }

    private final String outputMixerName;
    private final double volume;
    private final boolean enabled;
    private final Random random = new Random();
    private Clip current;
    private boolean nextReleaseFixed;

    // Opening a fresh Clip (as opposed to SoundFX's pre-opened pool, reused via setFramePosition)
    // means every bark pays whatever the OS's audio backend charges to stand up a line — normally
    // fast, but some drivers/devices (Bluetooth output waking from idle, in particular) can stall
    // that for a couple of seconds. That used to happen right on the EDT, freezing the whole
    // practice pad's UI for as long as the OS took. A single background worker plays lines one at a
    // time instead, so the slow part never blocks Swing — worst case a bark starts a little late,
    // never a frozen screen. The queue holds at most one pending request: a fresh play() request
    // clears whatever hadn't started yet, so mashing keys still cuts off to the newest line instead
    // of queuing up a backlog of stale ones.
    private final BlockingQueue<String> pending = new ArrayBlockingQueue<>(1);
    private final Thread worker;
    private volatile boolean closed;

    VoiceLines(String outputMixerName, double volume, boolean enabled) {
        this.outputMixerName = outputMixerName;
        this.volume = volume;
        this.enabled = enabled;
        this.worker = new Thread(this::runLoop, "niah-djmax-voice-playback");
        worker.setDaemon(true);
        worker.start();
    }

    private void runLoop() {
        while (!closed) {
            String filename;
            try {
                filename = pending.take();
            } catch (InterruptedException e) {
                return;
            }
            playNow(filename);
        }
    }

    static boolean available() {
        return Files.isDirectory(VOICE_DIR);
    }

    void playShortPress() {
        play(pick(SHORT_PRESS));
    }

    /** Also remembers whether this line demands the fixed reply on release. */
    void playLongPress() {
        String file = pick(LONG_PRESS);
        nextReleaseFixed = LONG_PRESS_FIXED_TRIGGER.equals(file);
        play(file);
    }

    void playLongRelease() {
        String file = nextReleaseFixed ? LONG_RELEASE_FIXED_REPLY : pick(LONG_RELEASE);
        nextReleaseFixed = false;
        play(file);
    }

    void playGameStart() {
        play(pick(GAME_START));
    }

    void playFeverEnter() {
        play(pick(FEVER_ENTER));
    }

    void playClear(ClearGrade grade) {
        play(pick(switch (grade) {
            case PERFECT_FULL_COMBO -> CLEAR_PERFECT_FULL_COMBO;
            case FULL_COMBO -> CLEAR_FULL_COMBO;
            case GOOD -> CLEAR_GOOD;
            case LOW_OR_FAIL -> CLEAR_LOW_OR_FAIL;
        }));
    }

    private String pick(String[] files) {
        return files[random.nextInt(files.length)];
    }

    /** Validates and hands off to the background worker — returns immediately, never touches audio
     *  APIs itself, so callers on the EDT (every key press on the practice pad) never block. */
    private void play(String filename) {
        if (!enabled) {
            return;
        }
        File file = VOICE_DIR.resolve(filename).toFile();
        if (!file.isFile()) {
            return; // not present on this machine (or moved) — skip quietly, never break gameplay
        }
        pending.clear(); // only the newest bark matters — drop anything not yet started
        pending.offer(filename);
    }

    /** Runs only on {@link #worker} — the actual Clip open/start work, exactly as before, just off
     *  the EDT so however long the OS takes to hand out an audio line never freezes the UI. */
    private void playNow(String filename) {
        File file = VOICE_DIR.resolve(filename).toFile();
        if (!file.isFile()) {
            return;
        }
        try {
            if (current != null) {
                current.stop();
                current.close();
            }
            current = AudioDevices.openClip(outputMixerName);
            try (AudioInputStream in = AudioSystem.getAudioInputStream(file)) {
                current.open(in);
            }
            applyGain(current, volume);
            current.start();
        } catch (Exception ignored) {
            // a voice line failing to play must never break gameplay
        }
    }

    private static void applyGain(Clip c, double volume) {
        if (!c.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        FloatControl gain = (FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN);
        float dB = volume <= 0.0001 ? gain.getMinimum() : (float) (20.0 * Math.log10(volume));
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
    }

    void close() {
        closed = true;
        worker.interrupt();
        if (current != null) {
            current.close();
        }
    }
}
