package djmax;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
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
 *  Loading swallows every exception and leaves {@link #load} returning {@code null} rather than
 *  ever risking the rhythm game itself over this purely decorative widget — callers just skip
 *  drawing it entirely when that happens. */
final class BongoCat {
    private static final Logger LOG = Logger.getLogger("djmax");
    private static final long BLINK_EVERY_MS = 4200;
    private static final long BLINK_DURATION_MS = 130;
    private static final long PRESS_HOLD_MS = 110;

    private final BufferedImage idle;
    private final BufferedImage idleBlink;
    private final BufferedImage[] press;

    private int activeLane = -1;
    private long activeUntilMs = 0;

    private BongoCat(BufferedImage idle, BufferedImage idleBlink, BufferedImage[] press) {
        this.idle = idle;
        this.idleBlink = idleBlink;
        this.press = press;
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
            return new BongoCat(idle, idleBlink, press);
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
     *  within {@link #PRESS_HOLD_MS}, otherwise idle — periodically swapped for the blink frame so
     *  the widget doesn't read as a frozen still image. */
    BufferedImage currentFrame(long nowWall) {
        if (activeLane >= 0 && nowWall < activeUntilMs) {
            return press[activeLane];
        }
        if (idleBlink != null && nowWall % BLINK_EVERY_MS < BLINK_DURATION_MS) {
            return idleBlink;
        }
        return idle;
    }
}
