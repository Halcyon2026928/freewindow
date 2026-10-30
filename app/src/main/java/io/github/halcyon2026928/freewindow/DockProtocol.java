package io.github.halcyon2026928.freewindow;

import android.content.Intent;

/**
 * system_server（{@link MainHook}）与 SystemUI（{@link SystemUiDock}）之间的坞窗协议。
 *
 * <p>方向一：system_server → SystemUI
 * <ul>
 *   <li>{@link #ACTION_SHOW}：悬浮球被点击，带 side / taskIds / packages / userIds</li>
 *   <li>{@link #ACTION_HIDE}：system_server 要求关闭（例如实时画面开关被关掉）</li>
 * </ul>
 *
 * <p>方向二：SystemUI → system_server，只有一条 {@link #ACTION_EVENT}：
 * <ul>
 *   <li>{@code shown}：坞窗已上屏，用于取消 system_server 的本地坞窗兜底</li>
 *   <li>{@code closed}：坞窗关闭。带 chosenTaskId（要在官方逻辑里恢复成迷你小窗的任务，-1 表示没有）、
 *       movedTaskIds（曾被搬到虚拟显示器的任务，需要重新最小化回悬浮球）、clearAll（是否清除后台）</li>
 * </ul>
 */
public final class DockProtocol {

    public static final String SYSTEMUI_PKG = "com.android.systemui";

    public static final String ACTION_SHOW =
            "io.github.halcyon2026928.freewindow.action.SHOW_DOCK";
    public static final String ACTION_HIDE =
            "io.github.halcyon2026928.freewindow.action.HIDE_DOCK";
    public static final String ACTION_EVENT =
            "io.github.halcyon2026928.freewindow.action.DOCK_EVENT";
    /** SystemUI → system_server：把某个幻影显示器的画面镜像到卡片图层。 */
    public static final String ACTION_MIRROR_ATTACH =
            "io.github.halcyon2026928.freewindow.action.MIRROR_ATTACH";
    /** SystemUI → system_server：撤掉镜像。 */
    public static final String ACTION_MIRROR_DETACH =
            "io.github.halcyon2026928.freewindow.action.MIRROR_DETACH";

    public static final String EXTRA_SIDE = "side";
    public static final String EXTRA_TASK_IDS = "task_ids";
    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_PACKAGES = "packages";
    public static final String EXTRA_USER_IDS = "user_ids";
    /** 每个任务对应的幻影显示器 id，0 表示当前没有在后台运行。 */
    public static final String EXTRA_DISPLAY_IDS = "display_ids";
    /** 静态应用用的截图（JPEG 字节），没有就是 null。 */
    public static final String EXTRA_SNAPSHOTS = "snapshots";
    /** 每个任务是不是动态应用（播放器/游戏），动态才挂实时镜像。 */
    public static final String EXTRA_DYNAMIC = "dynamic";
    /** 每个任务画面的宽高比（height / width），卡片按它决定预览区高度。 */
    public static final String EXTRA_ASPECTS = "aspects";
    public static final String EXTRA_SURFACE = "surface_control";
    public static final String EXTRA_MIRROR_KEY = "mirror_key";
    public static final String EXTRA_MIRROR_WIDTH = "mirror_width";
    public static final String EXTRA_MIRROR_HEIGHT = "mirror_height";
    /** 卡片圆角（px），直接作用到镜像图层上，不需要背景。 */
    public static final String EXTRA_CORNER = "corner";
    /** 这张卡片对应的应用包名（用于给该应用单独控帧）。 */
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_CHOSEN_TASK_ID = "chosen_task_id";
    public static final String EXTRA_MOVED_TASK_IDS = "moved_task_ids";
    public static final String EXTRA_CLEAR_ALL = "clear_all";

    public static final String EVENT_SHOWN = "shown";
    public static final String EVENT_CLOSED = "closed";

    private DockProtocol() {
    }

    public static Intent showIntent(int side, int[] taskIds, String[] packages, int[] userIds,
                                    int[] displayIds, byte[][] snapshots, boolean[] dynamic,
                                    float[] aspects) {
        Intent intent = new Intent(ACTION_SHOW);
        intent.setPackage(SYSTEMUI_PKG);
        intent.putExtra(EXTRA_SIDE, side);
        intent.putExtra(EXTRA_TASK_IDS, taskIds);
        intent.putExtra(EXTRA_PACKAGES, packages);
        intent.putExtra(EXTRA_USER_IDS, userIds);
        intent.putExtra(EXTRA_DISPLAY_IDS, displayIds);
        intent.putExtra(EXTRA_SNAPSHOTS, snapshots);
        intent.putExtra(EXTRA_DYNAMIC, dynamic);
        intent.putExtra(EXTRA_ASPECTS, aspects);
        return intent;
    }

    public static Intent mirrorAttachIntent(int taskId, int displayId, android.os.Parcelable surface,
                                            int mirrorKey, int width, int height, int corner,
                                            String pkg) {
        Intent intent = new Intent(ACTION_MIRROR_ATTACH);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        intent.putExtra(EXTRA_DISPLAY_IDS, displayId);
        intent.putExtra(EXTRA_MIRROR_KEY, mirrorKey);
        intent.putExtra(EXTRA_MIRROR_WIDTH, width);
        intent.putExtra(EXTRA_MIRROR_HEIGHT, height);
        intent.putExtra(EXTRA_CORNER, corner);
        intent.putExtra(EXTRA_PACKAGE, pkg);
        // SurfaceControl 是 Parcelable，可以直接随广播带过去。
        intent.putExtra(EXTRA_SURFACE, surface);
        return intent;
    }

    public static Intent mirrorDetachIntent(int mirrorKey) {
        Intent intent = new Intent(ACTION_MIRROR_DETACH);
        intent.putExtra(EXTRA_MIRROR_KEY, mirrorKey);
        return intent;
    }

    public static Intent hideIntent() {
        Intent intent = new Intent(ACTION_HIDE);
        intent.setPackage(SYSTEMUI_PKG);
        return intent;
    }

    public static Intent shownIntent() {
        Intent intent = new Intent(ACTION_EVENT);
        intent.putExtra(EXTRA_EVENT, EVENT_SHOWN);
        return intent;
    }

    public static Intent closedIntent(int chosenTaskId, int[] movedTaskIds, boolean clearAll) {
        Intent intent = new Intent(ACTION_EVENT);
        intent.putExtra(EXTRA_EVENT, EVENT_CLOSED);
        intent.putExtra(EXTRA_CHOSEN_TASK_ID, chosenTaskId);
        intent.putExtra(EXTRA_MOVED_TASK_IDS, movedTaskIds);
        intent.putExtra(EXTRA_CLEAR_ALL, clearAll);
        return intent;
    }

    public static boolean isEvent(Intent intent, String event) {
        return intent != null && event.equals(intent.getStringExtra(EXTRA_EVENT));
    }
}
