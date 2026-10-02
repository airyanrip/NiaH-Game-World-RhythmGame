package djmax;

import javax.imageio.ImageIO;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import java.util.logging.Logger;

/** A small "bongo cat"-style drumming widget for the rhythm game screen — built from the Eternal
 *  Return Niah bongo-cat art set (user-supplied, {@code 이터널리턴 니아 봉고캣}), specifically its
 *  4-button variant: an idle pose, a blink, and 4 "hand pressing this one" frames, one per square,
 *  already drawn by the artist for a generic 4-button layout rather than the set's other QWER/ASDF
 *  keyboard variant (that one's for Little LUMI's own "Super Bongo" mod, a separate, already-
 *  installed mod for the main desktop character — see its README under
 *  {@code mods\.preserved-mod-*\} for the technique this borrows: swap the WHOLE displayed pose per
 *  input instead of compositing a separate hand layer live).
 * <p>
 *  Source frames highlight the on-screen button squares in reverse numeric order — {@code
 *  button-1.png} highlights the RIGHTMOST of the 4 squares, {@code button-4.png} the LEFTMOST
 *  (confirmed by inspecting each frame before import) — so the import step that brought these PNGs
 *  into {@code src/djmax/bongo/} already renamed them straight to lane order: {@code
 *  press_lane0.png} (leftmost, from {@code button-4.png}) through {@code press_lane3.png}
 *  (rightmost, from {@code button-1.png}). No reversal needed here.
 * <p>
 *  The body layer (idle/blink/press-lane-N) deliberately leaves the left arm cut off mid-sleeve —
 *  the art set's own "Slide" folder is a separate left-hand-on-mouse overlay (3 poses: idle, left-
 *  click, right-click) meant to be drawn on top at the exact same canvas position to complete the
 *  character. Not needed for actual gameplay (lanes are keyboard/controller-only, not mouse), but
 *  applied anyway so the widget doesn't show a visibly incomplete arm — real mouse button state
 *  (see {@link #setMouseButtonDown}) picks which of the 3 poses shows, even though nothing in the
 *  rhythm game itself depends on a mouse click.
 * <p>
 *  Loading swallows every exception and leaves {@link #load} returning {@code null} rather than
 *  ever risking the rhythm game itself over this purely decorative widget — callers just skip
 *  drawing it entirely when that happens. */
final class BongoCat {
    private static final Logger LOG = Logger.getLogger("djmax");
    private static final Random RNG = new Random();
    // Blinks land at a randomized point in this window rather than a fixed interval — "랜덤으로
    // ... 눈을 잠시 감았다 뜨게" — re-rolled after every blink (see scheduleNextBlink) so the gap
    // before the next one is never the same twice in a row.
    private static final long BLINK_MIN_INTERVAL_MS = 2200;
    private static final long BLINK_MAX_INTERVAL_MS = 5200;
    private static final long BLINK_DURATION_MS = 130;
    private static final long PRESS_HOLD_MS = 110;

    private final BufferedImage idle;
    private final BufferedImage idleBlink;
    private final BufferedImage[] press;
    private final BufferedImage mouseIdle;
    private final BufferedImage mouseLeftClick;
    private final BufferedImage mouseRightClick;

    private int activeLane = -1;
    private long activeUntilMs = 0;
    private boolean leftDown;
    private boolean rightDown;
    // -1 means "not yet scheduled" — currentFrame() lazily rolls the first one the first time it's
    // ever called, since there's no sensible "now" to schedule against at construction time.
    private long nextBlinkAtMs = -1;

    private BongoCat(BufferedImage idle, BufferedImage idleBlink, BufferedImage[] press,
            BufferedImage mouseIdle, BufferedImage mouseLeftClick, BufferedImage mouseRightClick) {
        this.idle = idle;
        this.idleBlink = idleBlink;
        this.press = press;
        this.mouseIdle = mouseIdle;
        this.mouseLeftClick = mouseLeftClick;
        this.mouseRightClick = mouseRightClick;
    }

    static BongoCat load(int lanes) {
        try {
            BufferedImage idle = read("idle.png");
            if (idle == null) {
                return null;
            }
            BufferedImage idleBlink = read("idle_blink.png");
            BufferedImage[] press = new BufferedImage[lanes];
            for (int i = 0; i < lanes; i++) {
                press[i] = read("press_lane" + i + ".png");
            }
            BufferedImage mouseIdle = read("mouse_idle.png");
            BufferedImage mouseLeftClick = read("mouse_leftclick.png");
            BufferedImage mouseRightClick = read("mouse_rightclick.png");
            return new BongoCat(idle, idleBlink, press, mouseIdle, mouseLeftClick, mouseRightClick);
        } catch (Exception e) {
            LOG.warning("bongo cat assets failed to load: " + e);
            return null;
        }
    }

    private static BufferedImage read(String name) throws IOException {
        try (InputStream in = BongoCat.class.getResourceAsStream("bongo/" + name)) {
            return in == null ? null : ImageIO.read(in);
        }
    }

    /** The art's own height/width ratio, so the widget can be sized by width alone without
     *  stretching it. */
    double aspectRatio() {
        return idle.getHeight() / (double) idle.getWidth();
    }

    /** Call on every lane press (hit or empty press — same footing as the lane flash/key-down
     *  feedback this mirrors): shows that lane's pressed pose for {@link #PRESS_HOLD_MS}. */
    void onLaneHit(int lane, long nowWall) {
        if (lane < 0 || lane >= press.length || press[lane] == null) {
            return;
        }
        activeLane = lane;
        activeUntilMs = nowWall + PRESS_HOLD_MS;
    }

    /** @return whichever frame should be on screen right now: a lane's press pose if one landed
     *  within {@link #PRESS_HOLD_MS}, otherwise idle — swapped for the blink frame at randomized
     *  intervals (see {@link #scheduleNextBlink}) so the widget doesn't read as a frozen still
     *  image or blink with a noticeably mechanical, fixed rhythm. */
    BufferedImage currentFrame(long nowWall) {
        if (activeLane >= 0 && nowWall < activeUntilMs) {
            return press[activeLane];
        }
        if (idleBlink != null) {
            if (nextBlinkAtMs < 0) {
                scheduleNextBlink(nowWall);
            }
            if (nowWall < nextBlinkAtMs + BLINK_DURATION_MS) {
                if (nowWall >= nextBlinkAtMs) {
                    return idleBlink;
                }
            } else {
                scheduleNextBlink(nowWall);
            }
        }
        return idle;
    }

    private void scheduleNextBlink(long nowWall) {
        long span = BLINK_MAX_INTERVAL_MS - BLINK_MIN_INTERVAL_MS;
        nextBlinkAtMs = nowWall + BLINK_MIN_INTERVAL_MS + RNG.nextLong(span);
    }

    /** Tracks real left/right mouse button state for the "Slide" hand-on-mouse overlay — see the
     *  class doc. {@code button} is a raw {@link MouseEvent#getButton()} value; anything other than
     *  BUTTON1/BUTTON3 (a middle-click, say) is simply ignored. */
    void setMouseButtonDown(int button, boolean down) {
        if (button == MouseEvent.BUTTON1) {
            leftDown = down;
        } else if (button == MouseEvent.BUTTON3) {
            rightDown = down;
        }
    }

    /** @return the left-hand-on-mouse overlay frame to draw on top of {@link #currentFrame}, at the
     *  exact same position/size (every frame in this set shares one source canvas) — left-click or
     *  right-click if that button is currently down, otherwise idle; {@code null} only if the
     *  "Slide" art itself failed to load, in which case the caller just skips the overlay. */
    BufferedImage currentMouseFrame() {
        if (leftDown && mouseLeftClick != null) {
            return mouseLeftClick;
        }
        if (rightDown && mouseRightClick != null) {
            return mouseRightClick;
        }
        return mouseIdle;
    }
}
