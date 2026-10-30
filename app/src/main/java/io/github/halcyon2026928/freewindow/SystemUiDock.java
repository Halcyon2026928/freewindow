package io.github.halcyon2026928.freewindow;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * SystemUI 侧的坞窗：只负责"显示"。
 *
 * <p>应用在 ColorOS 的幻影显示器上继续运行（后台保活），这里不搬任务、不改窗口归属，
 * 只把幻影显示器的内容镜像到卡片上。点卡片 = 按官方逻辑恢复成小窗；
 * 点坞窗外/关闭 = 收起坞窗，悬浮球照旧显示，应用继续在球里跑。
 */
public final class SystemUiDock {

    private static final String TAG = "[freewindow-dock]";
    /** 最多同时镜像几路画面。 */
    private static final int MAX_LIVE = 4;

    private static SystemUiDock sInstance;

    private final Context context;
    private final WindowManager windowManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Card> cards = new ArrayList<>();

    private View root;
    private ScrollView scroll;
    private int side = 1;
    private boolean showing;
    private boolean dismissing;

    public static void install(Context context) {
        if (sInstance != null) return;
        try {
            sInstance = new SystemUiDock(context);
            sInstance.registerReceiver();
            XposedBridge.log(TAG + " installed in " + context.getPackageName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " install failed: " + t);
        }
    }

    public static void installFromApplication(Application application) {
        if (application == null) return;
        install(application);
    }

    private SystemUiDock(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    // ---------------- 跨进程入口 ----------------

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(DockProtocol.ACTION_SHOW);
        filter.addAction(DockProtocol.ACTION_HIDE);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                XposedBridge.log(TAG + " broadcast " + action);
                if (DockProtocol.ACTION_SHOW.equals(action)) {
                    show(intent);
                } else if (DockProtocol.ACTION_HIDE.equals(action)) {
                    dismiss(-1, false);
                }
            }
        };
        if (!registerExported(receiver, filter)) {
            XposedBridge.log(TAG + " receiver registration failed");
        }
    }

    private boolean registerExported(BroadcastReceiver receiver, IntentFilter filter) {
        try {
            Method method = Context.class.getMethod("registerReceiver",
                    BroadcastReceiver.class, IntentFilter.class, int.class);
            method.invoke(context, receiver, filter, 0x2 /* RECEIVER_EXPORTED */);
            return true;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable t) {
            XposedBridge.log(TAG + " registerReceiver(flags) failed: " + t);
        }
        try {
            context.registerReceiver(receiver, filter);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " registerReceiver failed: " + t);
            return false;
        }
    }

    private void send(Intent intent) {
        try {
            context.sendBroadcast(intent);
            XposedBridge.log(TAG + " sent " + intent.getAction()
                    + " event=" + intent.getStringExtra(DockProtocol.EXTRA_EVENT));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " send failed: " + t);
        }
    }

    // ---------------- 显示 / 关闭 ----------------

    private void show(Intent intent) {
        if (showing) {
            XposedBridge.log(TAG + " already showing, ignore");
            return;
        }
        int[] taskIds = intent.getIntArrayExtra(DockProtocol.EXTRA_TASK_IDS);
        String[] packages = intent.getStringArrayExtra(DockProtocol.EXTRA_PACKAGES);
        int[] userIds = intent.getIntArrayExtra(DockProtocol.EXTRA_USER_IDS);
        int[] displayIds = intent.getIntArrayExtra(DockProtocol.EXTRA_DISPLAY_IDS);
        byte[][] snapshots = null;
        try {
            Object raw = intent.getSerializableExtra(DockProtocol.EXTRA_SNAPSHOTS);
            if (raw instanceof byte[][]) snapshots = (byte[][]) raw;
        } catch (Throwable ignored) {
        }
        boolean[] dynamic = intent.getBooleanArrayExtra(DockProtocol.EXTRA_DYNAMIC);
        float[] aspects = intent.getFloatArrayExtra(DockProtocol.EXTRA_ASPECTS);
        if (taskIds == null || taskIds.length == 0) {
            XposedBridge.log(TAG + " show without tasks, ignore");
            return;
        }
        side = intent.getIntExtra(DockProtocol.EXTRA_SIDE, 1);
        try {
            buildWindow(taskIds, packages, userIds, displayIds, snapshots, dynamic, aspects);
            showing = true;
            dismissing = false;
            send(DockProtocol.shownIntent());
            main.post(this::assignVisibleSlots);
            XposedBridge.log(TAG + " shown side=" + side + " cards=" + cards.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " show failed: " + t);
            removeWindow();
        }
    }

    /** 收起坞窗。[clearAll] 为 true 时由 system_server 清掉所有后台应用（官方 removeFloatHandle + forceStop）。 */
    private void dismiss(int chosenTaskId, boolean clearAll) {
        if (!showing || dismissing) return;
        dismissing = true;
        showing = false;
        XposedBridge.log(TAG + " dismiss chosen=" + chosenTaskId + " clearAll=" + clearAll);
        // 先把坞窗撤掉（卡片、镜像随之消失），再通知 system_server 去恢复小窗 / 清后台。
        // 反过来做的话，小窗会先出现、卡片还停留一瞬，看着像"恢复时卡片没消失"。
        removeWindow();
        send(DockProtocol.closedIntent(chosenTaskId, new int[0], clearAll));
    }

    private void removeWindow() {
        if (root != null) {
            try {
                windowManager.removeViewImmediate(root);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " removeView failed: " + t);
            }
            root = null;
        }
        cards.clear();
        scroll = null;
        showing = false;
        dismissing = false;
    }

    // ---------------- UI ----------------

    private void buildWindow(int[] taskIds, String[] packages, int[] userIds, int[] displayIds,
                             byte[][] snapshots, boolean[] dynamic, float[] aspects) {
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;

        FrameLayout container = new FrameLayout(context);
        container.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout dock = new LinearLayout(context);
        dock.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable dockBg = new GradientDrawable();
        dockBg.setColor(0xE5E5E5E5);
        int corner = dp(20);
        if (side == 1) {
            dockBg.setCornerRadii(new float[]{corner, corner, 0, 0, 0, 0, corner, corner});
        } else {
            dockBg.setCornerRadii(new float[]{0, 0, corner, corner, corner, corner, 0, 0});
        }
        dock.setBackground(dockBg);
        dock.setClipToOutline(true);

        int dockWidth = screenWidth * 4 / 9;
        int dockHeight = screenHeight - dp(100);
        FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(dockWidth, dockHeight);
        dockParams.gravity = Gravity.CENTER_VERTICAL | (side == 1 ? Gravity.END : Gravity.START);
        container.addView(dock, dockParams);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setNestedScrollingEnabled(false);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll = scrollView;

        LinearLayout cardList = new LinearLayout(context);
        cardList.setOrientation(LinearLayout.VERTICAL);
        cardList.setPadding(dp(12), dp(12), dp(12), dp(12));
        scrollView.addView(cardList);
        dock.addView(scrollView);

        // 「清除」：清掉坞窗里所有后台应用（官方 removeFloatHandle + forceStop）。
        TextView clearBtn = new TextView(context);
        clearBtn.setText("清除");
        clearBtn.setTextColor(0xFF000000);
        clearBtn.setTextSize(15);
        clearBtn.setGravity(Gravity.CENTER);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(0xFFF3F3F3);
        closeBg.setCornerRadius(dp(24));
        clearBtn.setBackground(closeBg);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                dockWidth - dp(60), dp(48));
        closeParams.gravity = Gravity.CENTER_HORIZONTAL;
        closeParams.bottomMargin = dp(25);
        closeParams.topMargin = dp(5);
        dock.addView(clearBtn, closeParams);
        clearBtn.setOnClickListener(v -> dismiss(-1, true));

        for (int i = 0; i < taskIds.length; i++) {
            String pkg = packages != null && i < packages.length ? packages[i] : "";
            int userId = userIds != null && i < userIds.length ? userIds[i] : 0;
            int displayId = displayIds != null && i < displayIds.length ? displayIds[i] : 0;
            byte[] shot = snapshots != null && i < snapshots.length ? snapshots[i] : null;
            boolean isDynamic = dynamic != null && i < dynamic.length && dynamic[i];
            float aspect = aspects != null && i < aspects.length ? aspects[i] : 16f / 9f;
            cards.add(buildCard(cardList, taskIds[i], pkg, userId, displayId, shot, isDynamic,
                    aspect, dockWidth - dp(44)));
        }

        container.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                int[] loc = new int[2];
                dock.getLocationOnScreen(loc);
                float x = event.getRawX();
                float y = event.getRawY();
                boolean inside = x >= loc[0] && x <= loc[0] + dock.getWidth()
                        && y >= loc[1] && y <= loc[1] + dock.getHeight();
                if (!inside) {
                    dismiss(-1, false);
                    return true;
                }
            }
            return false;
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = resolveWindowType();
        params.format = PixelFormat.TRANSLUCENT;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        params.width = screenWidth;
        params.height = screenHeight;
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("FreeWindowDock");

        windowManager.addView(container, params);
        root = container;

        scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            main.removeCallbacks(assignTask);
            main.postDelayed(assignTask, 300L);
        });
        container.getViewTreeObserver().addOnGlobalLayoutListener(() -> main.post(assignTask));
    }

    private final Runnable assignTask = this::assignVisibleSlots;

    private Card buildCard(LinearLayout parent, int taskId, String pkg, int userId, int displayId,
                           byte[] shot, boolean dynamic, float aspect, int previewWidth) {
        android.graphics.Bitmap snapshot = decodeSnapshot(shot);
        Card card = new Card(taskId, pkg, userId, displayId, snapshot, dynamic);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(5);
        parent.addView(row, rowParams);

        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.addView(topBar);

        ImageView icon = new ImageView(context);
        int iconSize = dp(36);
        topBar.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        TextView name = new TextView(context);
        name.setTextSize(14);
        name.setTextColor(0xFF000000);
        name.setMaxLines(1);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.leftMargin = dp(10);
        topBar.addView(name, nameParams);

        Drawable appIcon = loadIcon(pkg, userId);
        if (appIcon != null) icon.setImageDrawable(appIcon);
        name.setText(loadLabel(pkg, userId));

        FrameLayout previewHost = new FrameLayout(context);
        // 按应用真实比例留高度：既不会裁掉上下，也不会留黑边（官方也是 itemHeight = width * ratio）。
        int previewHeight = Math.max(dp(120), Math.round(Math.max(1f, Math.min(aspect, 3f)) * previewWidth));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, previewHeight);
        previewParams.leftMargin = dp(10);
        previewParams.rightMargin = dp(10);
        previewParams.bottomMargin = dp(10);
        row.addView(previewHost, previewParams);

        MirrorSlotView slot = new MirrorSlotView(context);
        slot.setClipToOutline(true);
        slot.setClipChildren(true);
        slot.setIcon(appIcon);
        slot.setPackageName(pkg);
        previewHost.addView(slot, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        card.slot = slot;

        View.OnClickListener restore = v -> dismiss(card.taskId, false);
        previewHost.setOnClickListener(restore);
        row.setOnClickListener(restore);

        return card;
    }

    // ---------------- 镜像分配 ----------------

    private void assignVisibleSlots() {
        if (!showing || dismissing) return;
        int active = 0;
        for (Card card : cards) {
            if (card.slot.isActive()) active++;
        }
        for (Card card : cards) {
            if (active >= MAX_LIVE) break;
            if (card.slot.isActive()) continue;
            if (!isMostlyVisible(card.slot)) continue;
            card.slot.activate(card.taskId, card.displayId, card.dynamic, card.snapshot);
            active++;
            XposedBridge.log(TAG + " assigned taskId=" + card.taskId
                    + " displayId=" + card.displayId + " dynamic=" + card.dynamic
                    + " snapshot=" + (card.snapshot != null));
        }
    }

    private boolean isMostlyVisible(View view) {
        if (view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        Rect visible = new Rect();
        if (!view.getGlobalVisibleRect(visible)) return false;
        return visible.height() >= view.getHeight() / 2;
    }

    private static final class Card {
        final int taskId;
        final String pkg;
        final int userId;
        final int displayId;
        final android.graphics.Bitmap snapshot;
        final boolean dynamic;
        MirrorSlotView slot;

        Card(int taskId, String pkg, int userId, int displayId,
             android.graphics.Bitmap snapshot, boolean dynamic) {
            this.taskId = taskId;
            this.pkg = pkg;
            this.userId = userId;
            this.displayId = displayId;
            this.snapshot = snapshot;
            this.dynamic = dynamic;
        }
    }

    private android.graphics.Bitmap decodeSnapshot(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " decode snapshot failed: " + t);
            return null;
        }
    }

    // ---------------- 杂项 ----------------

    private Drawable loadIcon(String pkg, int userId) {
        ApplicationInfo info = loadAppInfo(pkg, userId);
        PackageManager pm = context.getPackageManager();
        if (info != null) {
            try {
                return pm.getApplicationIcon(info);
            } catch (Throwable ignored) {
            }
        }
        try {
            return pm.getApplicationIcon(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    private CharSequence loadLabel(String pkg, int userId) {
        PackageManager pm = context.getPackageManager();
        ApplicationInfo info = loadAppInfo(pkg, userId);
        if (info != null) {
            try {
                CharSequence label = pm.getApplicationLabel(info);
                if (label != null && label.length() > 0) return label;
            } catch (Throwable ignored) {
            }
        }
        return pkg == null ? "" : pkg;
    }

    private ApplicationInfo loadAppInfo(String pkg, int userId) {
        if (pkg == null || pkg.isEmpty()) return null;
        try {
            return (ApplicationInfo) XposedHelpers.callMethod(
                    context.getPackageManager(), "getApplicationInfoAsUser", pkg, 0, userId);
        } catch (Throwable t) {
            return null;
        }
    }

    private int resolveWindowType() {
        String[] candidates = {"TYPE_PHONE", "TYPE_SYSTEM_ALERT", "TYPE_APPLICATION_OVERLAY"};
        for (String name : candidates) {
            try {
                Field field = WindowManager.LayoutParams.class.getDeclaredField(name);
                field.setAccessible(true);
                int value = field.getInt(null);
                XposedBridge.log(TAG + " window type=" + name + "(" + value + ")");
                return value;
            } catch (Throwable ignored) {
            }
        }
        XposedBridge.log(TAG + " no named window type, fallback 2003");
        return 2003;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}
