package djmax;

import com.group_finity.mascot.lumi.plugin.MascotState;
import com.group_finity.mascot.lumi.plugin.PluginContext;

import javax.swing.Timer;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The real {@link MascotIntegration}, for the Little LUMI plugin build only — moved out of
 *  {@link HubWindow} as-is (same behavior, same heuristics) so that class no longer needs to
 *  import any {@code com.group_finity.mascot.lumi.plugin} type at all. See {@link
 *  MascotIntegration#pushAside} for what's guaranteed; everything below is best-effort on top of
 *  that, exactly as it was when this lived inline in {@link HubWindow}. */
final class LittleLumiMascotIntegration implements MascotIntegration {
    private final PluginContext ctx;

    /** Where a pushed-aside character came from, so it can run back to exactly that spot once the
     *  game ends instead of just being abandoned wherever it happened to end up sitting. */
    private record PushedMascot(String imageSet, int originalX, int originalY) {
    }

    // Keyed by mascot id — empty whenever no game is in progress, so release() knows whether
    // there's anything to send back and where each one actually came from.
    private final Map<Integer, PushedMascot> pushedMascots = new HashMap<>();

    /** One character's animated run from {@code startX} to {@code finalX} (by way of {@code viaX}
     *  — see {@link #pushAside}), driven by {@link #mascotRunTimer} rather than any walk/pathing
     *  the mascot's own AI might otherwise do, since {@code moveTo} is the only position control
     *  this SDK exposes — there is no "walk to this point" call to hand off to. */
    private static final class MascotRun {
        final int id;
        final int startX;
        final int viaX;
        final int finalX;
        final int y;
        final long startMs;
        final Runnable onArrive;

        MascotRun(int id, int startX, int viaX, int finalX, int y, long startMs, Runnable onArrive) {
            this.id = id;
            this.startX = startX;
            this.viaX = viaX;
            this.finalX = finalX;
            this.y = y;
            this.startMs = startMs;
            this.onArrive = onArrive;
        }
    }

    private static final long RUN_PHASE_MS = 380;
    private static final long SETTLE_PHASE_MS = 160;
    private static final long RUN_TOTAL_MS = RUN_PHASE_MS + SETTLE_PHASE_MS;

    private final List<MascotRun> activeMascotRuns = new ArrayList<>();
    private Timer mascotRunTimer;

    LittleLumiMascotIntegration(PluginContext ctx) {
        this.ctx = ctx;
    }

    /** Clears space around the game screen: every other desktop character currently on screen
     *  (Little LUMI, Miku, whichever skins are active — anything {@link PluginContext#mascots()}
     *  reports) runs to whichever side of the game window it's already closer to and sits down,
     *  instead of wandering across the play field mid-song — see {@link #startMascotRun} for the
     *  run animation itself. Best-effort throughout — the SDK has no API to directly set which way
     *  a character faces, only {@code moveTo} (position) and {@code behaveFor} (animation); the
     *  final short leg back toward the window right before sitting is a heuristic nudge (characters
     *  appear to face the direction they last actually moved), not a guarantee every skin's sit
     *  pose ends up looking at the screen. Never allowed to break the game itself if the mascot API
     *  misbehaves. */
    @Override
    public void pushAside(Rectangle gameBounds) {
        try {
            int gameCenterX = gameBounds.x + gameBounds.width / 2;
            Rectangle screen = ctx.foregroundScreen();
            pushedMascots.clear();
            for (MascotState m : ctx.mascots()) {
                boolean sendLeft = m.centerX() < gameCenterX;
                int mascotWidth = Math.max(1, m.bounds().width);
                int margin = 24;
                int targetX = sendLeft
                        ? Math.max(screen.x + margin, gameBounds.x - mascotWidth - margin)
                        : Math.min(screen.x + screen.width - mascotWidth - margin, gameBounds.x + gameBounds.width + margin);
                int startX = m.bounds().x;
                int y = m.bounds().y;
                int overshoot = sendLeft ? -40 : 40;
                String imageSet = m.imageSet();
                pushedMascots.put(m.id(), new PushedMascot(imageSet, startX, y));
                startMascotRun(m.id(), imageSet, startX, targetX + overshoot, targetX, y, () -> {
                    try {
                        if (ctx.behaviorNames(imageSet).contains("Sit")) {
                            ctx.behaveFor(imageSet, "Sit");
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception ex) {
            ctx.log().warning("캐릭터를 옆으로 옮기지 못했습니다: " + ex.getMessage());
        }
    }

    /** Hands control back once the run is over: each pushed-aside character runs back to exactly
     *  where it came from, then stands — not just abandoned wherever it happened to be sitting. */
    @Override
    public void release() {
        if (pushedMascots.isEmpty()) {
            return;
        }
        try {
            for (Map.Entry<Integer, PushedMascot> entry : pushedMascots.entrySet()) {
                int id = entry.getKey();
                PushedMascot info = entry.getValue();
                MascotState current = ctx.mascotById(id);
                int startX = current != null ? current.bounds().x : info.originalX();
                int y = current != null ? current.bounds().y : info.originalY();
                String imageSet = info.imageSet();
                startMascotRun(id, imageSet, startX, info.originalX(), info.originalX(), y, () -> {
                    try {
                        if (ctx.behaviorNames(imageSet).contains("Stand")) {
                            ctx.behaveFor(imageSet, "Stand");
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception ex) {
            ctx.log().warning("캐릭터 복귀 실패: " + ex.getMessage());
        } finally {
            pushedMascots.clear();
        }
    }

    /** Starts one character animating from {@code startX} to {@code finalX} by way of {@code viaX}
     *  (pass {@code finalX} again for {@code viaX} to skip the detour and just run straight there —
     *  used for the return trip, which has no "face the window" reason to overshoot). Sets a
     *  running/walking pose for the duration and calls {@code onArrive} the instant it lands, which
     *  is the caller's cue to switch to whatever pose comes after (Sit, Stand, …). */
    private void startMascotRun(int id, String imageSet, int startX, int viaX, int finalX, int y, Runnable onArrive) {
        try {
            Set<String> behaviors = ctx.behaviorNames(imageSet);
            String moveBehavior = behaviors.contains("Run") ? "Run" : behaviors.contains("Walk") ? "Walk" : null;
            if (moveBehavior != null) {
                ctx.behaveFor(imageSet, moveBehavior);
            }
        } catch (Exception ignored) {
        }
        // A quick MUSIC SELECT/EXIT right after starting a song can call release() before the
        // initial push run has finished — without this, both runs would tick at once and whichever
        // onArrive lands last wins, sometimes leaving a character stuck re-asserting "Sit" after
        // "Stand" already ran. Superseding any in-flight run for this id keeps exactly one
        // destination in play per mascot.
        activeMascotRuns.removeIf(run -> run.id == id);
        activeMascotRuns.add(new MascotRun(id, startX, viaX, finalX, y, System.currentTimeMillis(), onArrive));
        if (mascotRunTimer == null) {
            mascotRunTimer = new Timer(20, e -> tickMascotRuns());
            mascotRunTimer.setRepeats(true);
        }
        if (!mascotRunTimer.isRunning()) {
            mascotRunTimer.start();
        }
    }

    /** Advances every in-flight {@link MascotRun} one frame — two straight-line legs (current to
     *  {@code viaX} over {@link #RUN_PHASE_MS}, then {@code viaX} to {@code finalX} over {@link
     *  #SETTLE_PHASE_MS}), not eased: a fast, slightly blunt run reads as "hurrying," which is the
     *  point, more than a smoothed-out one would. */
    private void tickMascotRuns() {
        if (activeMascotRuns.isEmpty()) {
            mascotRunTimer.stop();
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<MascotRun> it = activeMascotRuns.iterator();
        while (it.hasNext()) {
            MascotRun run = it.next();
            long elapsed = now - run.startMs;
            boolean arrived = elapsed >= RUN_TOTAL_MS;
            int x;
            if (arrived) {
                x = run.finalX;
            } else if (elapsed < RUN_PHASE_MS) {
                double t = elapsed / (double) RUN_PHASE_MS;
                x = run.startX + (int) Math.round((run.viaX - run.startX) * t);
            } else {
                double t = (elapsed - RUN_PHASE_MS) / (double) SETTLE_PHASE_MS;
                x = run.viaX + (int) Math.round((run.finalX - run.viaX) * t);
            }
            try {
                ctx.moveTo(run.id, x, run.y);
            } catch (Exception ignored) {
            }
            if (arrived) {
                it.remove();
                if (run.onArrive != null) {
                    try {
                        run.onArrive.run();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }
}
