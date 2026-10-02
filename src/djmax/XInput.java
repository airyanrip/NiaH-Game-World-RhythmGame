package djmax;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;

import java.util.logging.Logger;

/** Minimal JNA binding to Windows' XInput API — just enough to poll a connected Xbox-layout
 *  controller's current button state once per game tick. Not from jna-platform (which doesn't wrap
 *  XInput); this hand-written interface only needs {@code XInputGetState}, so it's a handful of
 *  struct fields and one method rather than a whole binding library. Uses the app's own bundled JNA
 *  (Little LUMI ships {@code jna-jpms}/{@code jna-platform-jpms} on its classpath already — see the
 *  Mod SDK docs: "Use JNA directly... do not put a second jna in plugins\lib") instead of bundling
 *  a second JNA jar in this plugin.
 * <p>
 *  Covers Xbox-layout (XInput) controllers only — the standard Windows game-controller API. A
 *  PlayStation-style controller needs either Steam Input (Steam's own system-wide "present this as
 *  an Xbox controller" remapping, on by default for most controllers in most Steam games) or a
 *  third-party driver to show up here; there is no portable way for a single plugin to also speak
 *  DirectInput/HID without a much larger native dependency, so that is out of scope.
 * <p>
 *  Every entry point swallows {@link Throwable} (not just {@link Exception} — a missing native
 *  library surfaces as {@link UnsatisfiedLinkError}, which is an {@link Error}) and falls back to
 *  "no controller" rather than ever risking the rhythm game itself over something as non-essential
 *  as gamepad support. */
final class XInput {
    private XInput() {
    }

    private static final Logger LOG = Logger.getLogger("djmax");
    private static final int ERROR_SUCCESS = 0;

    static final short BUTTON_DPAD_UP = 0x0001;
    static final short BUTTON_DPAD_DOWN = 0x0002;
    static final short BUTTON_DPAD_LEFT = 0x0004;
    static final short BUTTON_DPAD_RIGHT = 0x0008;
    static final short BUTTON_START = 0x0010;
    static final short BUTTON_BACK = 0x0020;
    static final short BUTTON_LEFT_THUMB = 0x0040;
    static final short BUTTON_RIGHT_THUMB = 0x0080;
    static final short BUTTON_LEFT_SHOULDER = 0x0100;
    static final short BUTTON_RIGHT_SHOULDER = 0x0200;
    static final short BUTTON_A = 0x1000;
    static final short BUTTON_B = 0x2000;
    static final short BUTTON_X = 0x4000;
    static final short BUTTON_Y = (short) 0x8000;

    /** Every button this mod lets a player bind to a lane/side key, in a fixed, stable order —
     *  what Settings' rebind list offers and iterates. */
    static final short[] BINDABLE_BUTTONS = {
            BUTTON_A, BUTTON_B, BUTTON_X, BUTTON_Y,
            BUTTON_LEFT_SHOULDER, BUTTON_RIGHT_SHOULDER,
            BUTTON_LEFT_THUMB, BUTTON_RIGHT_THUMB,
            BUTTON_DPAD_UP, BUTTON_DPAD_DOWN, BUTTON_DPAD_LEFT, BUTTON_DPAD_RIGHT,
    };

    static String buttonName(short mask) {
        return switch (mask) {
            case BUTTON_A -> "A";
            case BUTTON_B -> "B";
            case BUTTON_X -> "X";
            case BUTTON_Y -> "Y";
            case BUTTON_LEFT_SHOULDER -> "LB";
            case BUTTON_RIGHT_SHOULDER -> "RB";
            case BUTTON_LEFT_THUMB -> "LS";
            case BUTTON_RIGHT_THUMB -> "RS";
            case BUTTON_DPAD_UP -> "D-Up";
            case BUTTON_DPAD_DOWN -> "D-Down";
            case BUTTON_DPAD_LEFT -> "D-Left";
            case BUTTON_DPAD_RIGHT -> "D-Right";
            case BUTTON_START -> "Start";
            case BUTTON_BACK -> "Back";
            default -> "?";
        };
    }

    @Structure.FieldOrder({"wButtons", "bLeftTrigger", "bRightTrigger", "sThumbLX", "sThumbLY", "sThumbRX", "sThumbRY"})
    public static class XInputGamepad extends Structure {
        public short wButtons;
        public byte bLeftTrigger;
        public byte bRightTrigger;
        public short sThumbLX;
        public short sThumbLY;
        public short sThumbRX;
        public short sThumbRY;
    }

    @Structure.FieldOrder({"dwPacketNumber", "gamepad"})
    public static class XInputState extends Structure {
        public int dwPacketNumber;
        public XInputGamepad gamepad;

        public static class ByReference extends XInputState implements Structure.ByReference {
        }
    }

    private interface Lib extends StdCallLibrary {
        int XInputGetState(int dwUserIndex, XInputState.ByReference pState);
    }

    // Newest to oldest — whichever's actually present on this Windows install. xinput9_1_0 (the
    // Vista-era compatibility shim) is the one guaranteed to exist on every version this app
    // supports, so it's the final fallback rather than a hard failure.
    private static final String[] DLL_NAMES = {"xinput1_4", "xinput1_3", "xinput9_1_0"};

    private static volatile Lib lib;
    private static volatile boolean loadAttempted = false;

    private static Lib lib() {
        if (lib != null || loadAttempted) {
            return lib;
        }
        synchronized (XInput.class) {
            if (loadAttempted) {
                return lib;
            }
            loadAttempted = true;
            for (String name : DLL_NAMES) {
                try {
                    lib = Native.load(name, Lib.class);
                    LOG.info("XInput loaded (" + name + ") — controller support enabled");
                    return lib;
                } catch (Throwable ignored) {
                    // try the next DLL name
                }
            }
            LOG.info("No XInput DLL found — controller support disabled (keyboard still works)");
            return null;
        }
    }

    /** Polls controller {@code index} (0-3) for its current button bitmask. Returns {@code null} if
     *  XInput couldn't be loaded at all, or no controller is connected at that index — both are
     *  completely normal/expected, not errors (most players have no controller plugged in). */
    static Short poll(int index) {
        Lib l = lib();
        if (l == null) {
            return null;
        }
        try {
            XInputState.ByReference state = new XInputState.ByReference();
            int result = l.XInputGetState(index, state);
            if (result != ERROR_SUCCESS) {
                return null;
            }
            return state.gamepad.wButtons;
        } catch (Throwable t) {
            return null;
        }
    }
}
