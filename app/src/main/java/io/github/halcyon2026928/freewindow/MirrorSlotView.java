package io.github.halcyon2026928.freewindow;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XposedBridge;

/**
 * 坞窗卡片：静态应用显示截图，动态应用（播放器/游戏）显示实时镜像。
 *
 * <p>每张卡片的判定和状态完全独立，互不影响：静态卡永远是截图，不会因为别的应用在放视频
 * 就跟着切到实时镜像、也不会跟着黑屏。
 */
public final class MirrorSlotView extends FrameLayout {

    private static final String TAG = "[freewindow-mirror]";
    private static final AtomicInteger KEYS = new AtomicInteger(1);

    private final SurfaceView surface;
    private final ImageView snapshot;
    private final ImageView icon;
    private final Handler main = new Handler(Looper.getMainLooper());

    private int taskId = -1;
    private int displayId = 0;
    private int mirrorKey;
    private boolean dynamic;
    private int generation;
    private String pkg = "";

    private static final int CORNER_DP = 12;

    /** 圆角 outline：外层容器和截图 ImageView 共用。 */
    private static final android.view.ViewOutlineProvider ROUND_RECT =
            new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    if (view.getWidth() > 0 && view.getHeight() > 0) {
                        float radius = CORNER_DP * view.getResources().getDisplayMetrics().density;
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                    }
                }
            };

    public MirrorSlotView(Context context) {
        super(context);
        setClipChildren(true);
        // 没有背景色，圆角靠 outline（截图）和镜像图层的 cornerRadius（实时画面）来保证。
        setClipToOutline(true);
        setOutlineProvider(ROUND_RECT);

        surface = new SurfaceView(context);
        surface.setZOrderMediaOverlay(false);
        addView(surface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        snapshot = new ImageView(context);
        snapshot.setScaleType(ImageView.ScaleType.CENTER_CROP);
        snapshot.setVisibility(GONE);
        // 截图是普通 View 的绘制内容，必须自己也裁一次圆角（外层 outline 裁不到它的位图）。
        snapshot.setClipToOutline(true);
        snapshot.setOutlineProvider(ROUND_RECT);
        addView(snapshot, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        LayoutParams iconParams = new LayoutParams(dp(40), dp(40));
        iconParams.gravity = Gravity.CENTER;
        addView(icon, iconParams);

        surface.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                if (dynamic && taskId > 0 && mirrorKey == 0) {
                    main.postDelayed(MirrorSlotView.this::attachMirror, 80L);
                }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                detachMirror();
            }
        });

        addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right - left == oldRight - oldLeft && bottom - top == oldBottom - oldTop) return;
                // 尺寸变化后 outline 要重算，否则圆角裁剪会失效。
                invalidateOutline();
                snapshot.invalidateOutline();
                if (!dynamic || taskId <= 0 || mirrorKey == 0) return;
                detachMirror();
                main.postDelayed(MirrorSlotView.this::attachMirror, 60L);
            }
        });
    }

    public void setIcon(Drawable drawable) {
        icon.setImageDrawable(drawable);
    }

    public void setPackageName(String pkg) {
        this.pkg = pkg == null ? "" : pkg;
    }

    public boolean isActive() {
        return taskId > 0;
    }

    public int getTaskId() {
        return taskId;
    }

    /**
     * @param dynamicApp 这张卡片是否走实时镜像；false 时只显示 [shot]（没有就显示图标）。
     * @param shot       静态截图，可为 null。
     */
    public void activate(int taskId, int sourceDisplayId, boolean dynamicApp, Bitmap shot) {
        if (taskId <= 0) return;
        generation++;
        detachMirror();
        this.taskId = taskId;
        this.displayId = sourceDisplayId;
        this.dynamic = dynamicApp;

        if (shot != null && !shot.isRecycled()) {
            snapshot.setImageBitmap(shot);
            snapshot.setVisibility(VISIBLE);
            icon.setVisibility(GONE);
        } else {
            snapshot.setVisibility(GONE);
            icon.setVisibility(VISIBLE);
        }

        if (!dynamicApp) {
            // 静态卡不需要 SurfaceView：它自带一层直角背景图层，会在圆角外透出灰直角。
            surface.setVisibility(INVISIBLE);
            clearSurfaceBackground();
            XposedBridge.log(TAG + " static card, snapshot only taskId=" + taskId
                    + " hasShot=" + (shot != null));
            return;
        }
        surface.setVisibility(VISIBLE);
        clearSurfaceBackground();
        XposedBridge.log(TAG + " dynamic card, attaching mirror taskId=" + taskId
                + " displayId=" + sourceDisplayId);
        if (surface.isAttachedToWindow()) {
            main.postDelayed(this::attachMirror, 80L);
        }
    }

    public void deactivate() {
        generation++;
        taskId = -1;
        displayId = 0;
        detachMirror();
    }

    private void hideOverlays() {
        snapshot.setVisibility(GONE);
        icon.setVisibility(GONE);
    }

    private void attachMirror() {
        if (taskId <= 0 || !dynamic || !isAttachedToWindow()) return;
        if (mirrorKey != 0) return;
        final int key = KEYS.getAndIncrement();
        mirrorKey = key;
        try {
            clearSurfaceBackground();
            android.view.SurfaceControl layer = surface.getSurfaceControl();
            if (layer == null || !layer.isValid()) {
                XposedBridge.log(TAG + " surface control not ready taskId=" + taskId);
                mirrorKey = 0;
                return;
            }
            Intent intent = DockProtocol.mirrorAttachIntent(taskId, displayId, layer, key,
                    getWidth(), getHeight(), dp(12), pkg);
            getContext().sendBroadcast(intent);
            XposedBridge.log(TAG + " mirror attach requested taskId=" + taskId
                    + " displayId=" + displayId + " key=" + key);
            // 动态应用：给镜像一点时间出帧，再把截图/图标收起来。
            main.postDelayed(() -> {
                if (mirrorKey == key) hideOverlays();
            }, 1200L);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " attach mirror failed taskId=" + taskId + ": " + t);
            mirrorKey = 0;
        }
    }

    /**
     * SurfaceView 会在窗口上**挖一个洞**：图层没内容时，洞里透出来的就是桌面。
     * 用涂色去遮会在"每次遍历重置背景层"后露出直角，所以这里干脆把背景层设透明，
     * 洞的问题改用"加载期间把 SurfaceView 挪出屏幕"来解决（见 {@link #attachMirror()}）。
     */
    private void clearSurfaceBackground() {
        try {
            java.lang.reflect.Field field = SurfaceView.class.getDeclaredField("mBackgroundControl");
            field.setAccessible(true);
            Object value = field.get(surface);
            if (!(value instanceof android.view.SurfaceControl)) return;
            android.view.SurfaceControl sc = (android.view.SurfaceControl) value;
            if (!sc.isValid()) return;
            new android.view.SurfaceControl.Transaction().setAlpha(sc, 0f).apply();
        } catch (Throwable ignored) {
        }
    }

    private void detachMirror() {
        if (mirrorKey == 0) return;
        int key = mirrorKey;
        mirrorKey = 0;
        try {
            getContext().sendBroadcast(DockProtocol.mirrorDetachIntent(key));
            XposedBridge.log(TAG + " mirror detach requested key=" + key);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " detach mirror failed key=" + key + ": " + t);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
