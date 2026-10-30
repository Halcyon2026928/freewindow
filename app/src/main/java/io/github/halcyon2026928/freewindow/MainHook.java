package io.github.halcyon2026928.freewindow;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[freewindow-hook]";

    /** 配置在模块 app 的 SharedPreferences 中（与 SettingsActivity 一致），经 ConfigProvider 暴露。 */
    private static final String KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar";
    private static final String KEY_HIDE_THREE_DOT = "hide_three_dot_menu";
    private static final String KEY_ENABLE_DOCK = "enable_float_dock";
    private static final String KEY_ENABLE_LIVE = "enable_live_preview";
    private static final String KEY_ENABLE_HANGUP = "enable_background_run";

    /** 设为 true 时会把 FlexibleTaskCaptionView 的完整视图树打印到 Xposed 日志，便于在真机上确认三点按钮的确切 id。 */
    private static final boolean DUMP_CAPTION_TREE = false;

    /** 开关状态。首次自由小窗创建或点击悬浮球时经 ConfigProvider 跨进程读取。
     *  读取失败则沿用上次成功读取的配置（缓存兜底）。 */
    private boolean hideBottomBar = true;
    private boolean hideThreeDot = true;
    private boolean enableDock = true;
    private boolean enableLiveDock = true;
    private boolean enableHangup = true;
    private boolean configLoaded = false;

    /** 系统框架 ClassLoader。 */
    private static ClassLoader sClassLoader;
    /** 当前点击的悬浮球 View。 */
    private static Object sCurrentFloatHandleView;
    /** 坞窗容器（全局唯一）。 */
    private static FrameLayout sDockContainer;
    private static View sDockPanel;
    private static int sDockSide = 1;
    private static boolean sDockClosing = false;
    /** 官方 lively 坞窗滑入滑出：PathInterpolator(0.28, 0.4, 0.2, 1) / 457ms */
    private static final long DOCK_ANIM_MS = 457L;
    private static final PathInterpolator DOCK_INTERPOLATOR = new PathInterpolator(0.28f, 0.4f, 0.2f, 1.0f);
    /** 坞窗窗口在屏幕上的偏移（用于计算菜单位置）。 */
    private static int sDockWindowX = 0;
    private static int sDockWindowY = 0;
    /** 坞窗中的应用列表。 */
    private static final List<Object> sDockApps = new ArrayList<>();
    /** 坞窗是否正在显示。 */
    private static boolean sDockShowing = false;
    /** 正在走官方恢复流程：让悬浮球点击的拦截暂时放行。 */
    private static volatile boolean sRestoring = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("android".equals(lpparam.packageName)) {
            sClassLoader = lpparam.classLoader;
            hookSystemServer(lpparam);
        }
    }

    /** 从模块 app 的 ConfigProvider 读取开关状态。
     *  每次创建自由小窗（initCaptionView）时重新读取——开关修改后重新打开自由小窗即生效，无需重启设备；
     *  读取成功则更新开关字段；读取失败（如 app 进程被清理/停止导致 provider 不可达）则
     *  沿用上次成功读取的配置（缓存兜底）。 */
    private void loadConfig(Object captionView) {
        try {
            Context ctx = (Context) XposedHelpers.getObjectField(captionView, "mContext");
            loadConfigFromContext(ctx);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " loadConfig error, keep cached config: " + t.getMessage());
        }
    }

    /** 从 LSPosed XSharedPreferences 读取（重启后仍有效），再用 ConfigProvider 覆盖一次以便当场改开关。 */
    private void loadConfigFromContext(Context ctx) {
        loadFromXSharedPrefs();
        if (ctx == null) return;
        try {
            ContentResolver cr = ctx.getContentResolver();
            Cursor c = cr.query(ConfigProvider.CONFIG_URI, null, null, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        String key = c.getString(0);
                        int value = c.getInt(1);
                        if (KEY_HIDE_BOTTOM_BAR.equals(key)) {
                            hideBottomBar = value == 1;
                        } else if (KEY_HIDE_THREE_DOT.equals(key)) {
                            hideThreeDot = value == 1;
                        } else if (KEY_ENABLE_DOCK.equals(key)) {
                            enableDock = value == 1;
                        } else if (KEY_ENABLE_LIVE.equals(key)) {
                            enableLiveDock = value == 1;
                        } else if (KEY_ENABLE_HANGUP.equals(key)) {
                            enableHangup = value == 1;
                        }
                    }
                    configLoaded = true;
                    XposedBridge.log(TAG + " config(provider) -> hideBottomBar=" + hideBottomBar
                            + " hideThreeDot=" + hideThreeDot
                            + " enableDock=" + enableDock);
                } finally {
                    c.close();
                }
            } else {
                XposedBridge.log(TAG + " loadConfig: provider cursor null, use XSharedPreferences");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " loadConfig provider fail, use XSharedPreferences: " + t.getMessage());
        }
    }

    private void loadFromXSharedPrefs() {
        try {
            XSharedPreferences prefs = new XSharedPreferences(ModulePrefs.PKG, ModulePrefs.NAME);
            prefs.makeWorldReadable();
            prefs.reload();
            hideBottomBar = prefs.getBoolean(KEY_HIDE_BOTTOM_BAR, true);
            hideThreeDot = prefs.getBoolean(KEY_HIDE_THREE_DOT, true);
            enableDock = prefs.getBoolean(KEY_ENABLE_DOCK, true);
            enableLiveDock = prefs.getBoolean(KEY_ENABLE_LIVE, true);
            enableHangup = prefs.getBoolean(KEY_ENABLE_HANGUP, true);
            updateUserDynamicPackages(prefs.getString(ModulePrefs.KEY_DYNAMIC_APPS, ""));
            configLoaded = true;
            XposedBridge.log(TAG + " config(xprefs readable=" + prefs.getFile().canRead()
                    + ") -> hideBottomBar=" + hideBottomBar
                    + " hideThreeDot=" + hideThreeDot
                    + " enableDock=" + enableDock
                    + " enableLiveDock=" + enableLiveDock
                    + " enableHangup=" + enableHangup
                    + " dynamicWhitelist=" + sUserDynamicPackages.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " XSharedPreferences error: " + t.getMessage());
        }
    }

    private void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> captionViewClass = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleTaskCaptionView", lpparam.classLoader);

            // 注册 initCaptionView：首次自由小窗创建时读取开关配置，并按开关执行
            XposedHelpers.findAndHookMethod(captionViewClass, "initCaptionView",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object thisObj = param.thisObject;
                                loadConfig(thisObj);
                                // 配置读取失败（如重启后模块app未启动）时，使用默认值 true

                                // ---- 隐藏底部手势小横条（开关：hide_bottom_bar） ----
                                if (hideBottomBar) {
                                    View bottomFrame = (View) XposedHelpers.getObjectField(thisObj, "mBottomFrame");
                                    View bottomHandleView = (View) XposedHelpers.getObjectField(thisObj, "mBottomHandleView");
                                    View bottomHandleLongView = (View) XposedHelpers.getObjectField(thisObj, "mBottomHandleLongView");

                                    if (bottomFrame != null) bottomFrame.setVisibility(View.GONE);
                                    if (bottomHandleView != null) bottomHandleView.setVisibility(View.GONE);
                                    if (bottomHandleLongView != null) bottomHandleLongView.setVisibility(View.GONE);
                                    XposedBridge.log(TAG + " hidden freeform bottom handle bar");
                                }

                                // ---- 移除顶部三点菜单按钮（开关：hide_three_dot_menu） ----
                                if (hideThreeDot && thisObj instanceof View) {
                                    View captionRoot = (View) thisObj;
                                    if (DUMP_CAPTION_TREE) {
                                        dumpViewTree(captionRoot, 0);
                                    }
                                    View menuButton = removeThreeDotMenuButton(captionRoot);
                                    if (menuButton != null) {
                                        XposedBridge.log(TAG + " removed freeform three-dot menu button: " + describe(menuButton));
                                    } else {
                                        XposedBridge.log(TAG + " three-dot menu button not found (try DUMP_CAPTION_TREE=true to locate its id)");
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " initCaptionView hook error: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log(TAG + " FlexibleTaskCaptionView.initCaptionView hook success");

            // 第二层：拦截三点按钮区域的点击功能（开关：hide_three_dot_menu）
            hookSingleTapUpMenuBlock(captionViewClass);

            // 第三层：让触摸穿透应用（开关：hide_three_dot_menu）
            hookTouchThroughCarve(captionViewClass);

            // 第四层：点击悬浮球弹出坞窗
            hookFloatHandleClick(lpparam.classLoader);

            // 第五层：坞窗卡片静态画面（ImageView + TaskSnapshot）
            LivelyHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook FlexibleTaskCaptionView failed: " + t.getMessage());
        }

        // 第六层：后台保活——应用收进悬浮球后，用 ColorOS 的「幻影窗口/后台流」让它在系统虚拟显示器上继续运行。
        // 这样球的状态完全不受影响，画面也还在，坞窗卡片才有东西可取。
        try {
            loadFromXSharedPrefs();
            if (enableHangup) {
                MirageWindowHook.hook(lpparam.classLoader);
            } else {
                XposedBridge.log(TAG + " hangup disabled, skip MirageWindowHook");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " MirageWindowHook failed: " + t.getMessage());
        }
    }

    /**
     * hook FlexibleCaptionView.updateTouchableRegion(Region)：
     * 每次标题栏把触摸区域应用到 Surface 前，把三点按钮触摸区域挖空，
     * 使该位置触摸穿透到自由小窗中的应用。
     */
    private void hookTouchThroughCarve(Class<?> captionViewClass) {
        try {
            Class<?> baseClass = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleCaptionView", captionViewClass.getClassLoader());

            XposedHelpers.findAndHookMethod(baseClass, "updateTouchableRegion", Region.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!hideThreeDot) return;

                                Region region = (Region) param.args[0];
                                if (region == null) return;

                                Object regionMgr = XposedHelpers.getObjectField(param.thisObject, "mFlexibleCaptionViewRegionMgr");
                                if (regionMgr == null) return;

                                Rect btn = (Rect) XposedHelpers.getObjectField(regionMgr, "mHandleButtonRegion");
                                if (btn == null || btn.isEmpty()) return;

                                region.op(btn.left, btn.top, btn.right, btn.bottom, Region.Op.DIFFERENCE);
                                XposedBridge.log(TAG + " carved three-dot touch region for touch-through: "
                                        + btn.toShortString());
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " updateTouchableRegion carve error: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log(TAG + " touch-through carve hook success");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook updateTouchableRegion failed: " + t.getMessage());
        }
    }

    /**
     * 拦截 FlexibleCaptionView.onSingleTapUp(MotionEvent)：
     * 当 actionFlag == HANDLE_EVENT(1) 且触摸点位于三点按钮触摸区域（mHandleButtonRegion）时，
     * 直接返回 false，阻止系统弹出自由小窗菜单。
     */
    private void hookSingleTapUpMenuBlock(Class<?> captionViewClass) {
        try {
            Class<?> baseClass = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleCaptionView", captionViewClass.getClassLoader());

            XposedHelpers.findAndHookMethod(baseClass, "onSingleTapUp", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // 直接用开关状态（配置读取失败时使用默认值 true）
                                if (!hideThreeDot) return; // 开关关闭：不做点击拦截

                                Object thisObj = param.thisObject;
                                Object regionMgr = XposedHelpers.getObjectField(thisObj, "mFlexibleCaptionViewRegionMgr");
                                if (regionMgr == null) return;

                                int actionFlag = (int) XposedHelpers.getIntField(thisObj, "mActionFlag");
                                if (actionFlag != 1) return; // HANDLE_EVENT，仅三点/把手按钮区

                                Rect region = (Rect) XposedHelpers.getObjectField(regionMgr, "mHandleButtonRegion");
                                if (region == null || region.isEmpty()) return;

                                MotionEvent ev = (MotionEvent) param.args[0];
                                if (region.contains((int) ev.getX(), (int) ev.getY())) {
                                    XposedBridge.log(TAG + " blocked three-dot menu tap at " + (int) ev.getX() + "," + (int) ev.getY());
                                    param.setResult(false);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " onSingleTapUp block error: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log(TAG + " FlexibleCaptionView.onSingleTapUp menu block hook success");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook onSingleTapUp failed: " + t.getMessage());
        }
    }

    /**
     * 移除顶部“三点菜单按钮”（自由小窗的“浮窗控制器”）。
     *
     * 真机视图树确认（ColorOS，oplus 资源包）：
     *   flexible_button
     *     ├─ flexible_toolbar_mode   （工具栏：handle/full/mini/closed）
     *     └─ flexible_simple_mode    （简单模式，即截图中的三点控制器）
     *         ├─ control_bg
     *         └─ control_flexible    desc=浮窗控制器 ← 三点按钮本体
     *             ├─ control_left_ellipse / control_center_ellipse / control_right_ellipse（三个点）
     *
     * 主路径：按资源名精确移除 flexible_simple_mode 整个容器（连三点按钮带背景一起清掉，干净无残留）；
     * 若新版布局改名，则退化为移除 control_flexible，再退化为语义/兜底查找。
     */
    private View removeThreeDotMenuButton(View root) {
        if (root == null) return null;

        View simpleMode = findViewByResName(root, "flexible_simple_mode");
        if (simpleMode != null) {
            removeViewCleanly(simpleMode);
            return simpleMode;
        }
        View control = findViewByResName(root, "control_flexible");
        if (control != null) {
            removeViewCleanly(control);
            return control;
        }

        // 兜底：语义（menu/more/overflow/更多/菜单）+ 保守启发式，并排除把手类视图
        View semantic = findMenuButtonBySemantics(root);
        if (semantic != null) {
            removeViewCleanly(semantic);
            return semantic;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (isLikelyMenuButton(child)) {
                    removeViewCleanly(child);
                    return child;
                }
            }
        }
        return null;
    }

    /** 按资源名（如 "oplus:id/flexible_simple_mode"）在视图树中递归查找视图。 */
    private View findViewByResName(View view, String simpleResName) {
        if (view == null) return null;
        try {
            int id = view.getId();
            if (id != View.NO_ID) {
                String resName = view.getResources().getResourceName(id);
                if (resName != null && resName.endsWith("/" + simpleResName)) {
                    return view;
                }
            }
        } catch (Throwable ignored) {
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View hit = findViewByResName(group.getChildAt(i), simpleResName);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private View findMenuButtonBySemantics(View view) {
        if (view == null) return null;

        if (isSemanticMenuButton(view)) return view;

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View hit = findMenuButtonBySemantics(group.getChildAt(i));
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** 通过 id 资源名 / contentDescription 判断是否是“更多/菜单”按钮。 */
    private boolean isSemanticMenuButton(View v) {
        // 先排除把手类视图（handle），避免误伤底部把手/把手图标
        if (isHandleView(v)) return false;

        // id 资源名
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                String resName = v.getResources().getResourceName(id).toLowerCase();
                if (resName.contains("menu") || resName.contains("more")
                        || resName.contains("overflow")) {
                    // 排除通用的 Menu 类资源误报（例如标题为 menu 的容器）：只接受按钮型视图
                    if (isButtonLike(v)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        // contentDescription
        CharSequence cd = v.getContentDescription();
        if (cd != null) {
            String s = cd.toString().toLowerCase();
            if (s.contains("更多") || s.contains("菜单") || s.contains("menu") || s.contains("more")
                    || s.contains("overflow")) {
                if (isButtonLike(v)) return true;
            }
        }
        return false;
    }

    /** 判断视图是否是“把手”类（底部横条/把手），这些不是三点菜单按钮。 */
    private boolean isHandleView(View v) {
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                String resName = v.getResources().getResourceName(id).toLowerCase();
                if (resName.contains("handle")) return true;
            }
        } catch (Throwable ignored) {
        }
        CharSequence cd = v.getContentDescription();
        if (cd != null) {
            String s = cd.toString();
            if (s.contains("把手") || s.contains("handle")) return true;
        }
        return false;
    }

    /** 判断是否像按钮（可点击或为 ImageView/ImageButton 等控件型）。 */
    private boolean isButtonLike(View v) {
        return v.isClickable() || v instanceof ImageView
                || v.getClass().getSimpleName().toLowerCase().contains("imagebutton")
                || v.getClass().getSimpleName().toLowerCase().contains("button");
    }

    /**
     * 兜底启发式：判断一个顶层子视图是否“可能”是三点菜单按钮。
     * 只在语义定位失败时使用，且做了保守过滤，避免误删关闭/最小化按钮。
     */
    private boolean isLikelyMenuButton(View v) {
        if (v == null || !isButtonLike(v)) return false;
        if (isHandleView(v)) return false;
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                String resName = v.getResources().getResourceName(id).toLowerCase();
                // 明确是关闭/最小化/最大化/返回 的按钮，跳过
                if (resName.contains("close") || resName.contains("minimize")
                        || resName.contains("maximize") || resName.contains("back")) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }
        // 语义兜底：控件型、可点击、且没有被识别为关闭类 → 在标题栏顶层视为候选。
        // 说明：若真机上此候选不是三点按钮，请用 DUMP_CAPTION_TREE=true 定位真实 id 后收紧匹配。
        return true;
    }

    /** 从父容器中干净移除视图（真正删掉而非隐藏）；若没有父容器则退化为 GONE。 */
    private void removeViewCleanly(View v) {
        if (v == null) return;
        ViewGroup parent = (v.getParent() instanceof ViewGroup) ? (ViewGroup) v.getParent() : null;
        if (parent != null) {
            try {
                parent.removeView(v);
                return;
            } catch (Throwable ignored) {
            }
        }
        v.setVisibility(View.GONE);
    }

    /** 生成便于阅读的视图描述，用于日志。 */
    private String describe(View v) {
        StringBuilder sb = new StringBuilder(v.getClass().getSimpleName());
        sb.append('@').append(Integer.toHexString(v.hashCode()));
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                sb.append(" id=").append(v.getResources().getResourceName(id));
            }
        } catch (Throwable ignored) {
        }
        CharSequence cd = v.getContentDescription();
        if (cd != null && cd.length() > 0) sb.append(" desc=").append(cd);
        return sb.toString();
    }

    /** 打印标题栏视图树（用于在真机上确认三点按钮 id）。 */
    private void dumpViewTree(View v, int depth) {
        if (v == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(describe(v));
        XposedBridge.log(TAG + " tree: " + sb);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpViewTree(g.getChildAt(i), depth + 1);
        }
    }

    // ==================== 悬浮球坞窗 ====================

    /**
     * hook FloatHandleView.startFlexibleFreeformFromHandleView(FloatHandleInfo, String)：
     * 拦截点击悬浮球的默认行为（恢复自由小窗），改为弹出坞窗。
     */
    private void hookFloatHandleClick(final ClassLoader cl) {
        try {
            Class<?> fhvClass = XposedHelpers.findClass("com.android.server.wm.floathandle.FloatHandleView", cl);
            Class<?> fhiClass = XposedHelpers.findClass("com.android.server.wm.floathandle.FloatHandleInfo", cl);
            XposedHelpers.findAndHookMethod(fhvClass, "startFlexibleFreeformFromHandleView",
                    fhiClass, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // 我们自己正在走官方恢复：放行，别再弹坞窗。
                                if (sRestoring) return;
                                Object floatHandleView = param.thisObject;
                                try {
                                    Context ctx = (Context) XposedHelpers.callMethod(floatHandleView, "getContext");
                                    loadConfigFromContext(ctx);
                                } catch (Throwable ignored) {
                                }
                                if (!enableDock) {
                                    XposedBridge.log(TAG + " dock disabled, skip intercept");
                                    return;
                                }
                                // 该方法返回 boolean，setResult(null) 会在拆箱时 NPE 导致 system_server 软重启
                                if (sDockShowing) {
                                    param.setResult(false);
                                    return;
                                }
                                Object floatHandleInfo = param.args[0];
                                String reason = (String) param.args[1];
                                XposedBridge.log(TAG + " float handle clicked, reason=" + reason);
                                param.setResult(false);
                                sCurrentFloatHandleView = floatHandleView;
                                // 记住悬浮球所在线程（android.ui）的 Handler，后面隐藏/恢复都靠它。
                                if (floatHandleView instanceof View) {
                                    sHandleHandler = ((View) floatHandleView).getHandler();
                                }
                                // A 方案：坞窗交给 SystemUI（TextureView + VirtualDisplay 才拿得到实时画面），
                                // system_server 只在它没应答时回退到旧的自绘坞窗。
                                if (!tryRemoteDock(floatHandleView, floatHandleInfo, cl)) {
                                    showFloatDock(floatHandleView, floatHandleInfo, cl);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " hookFloatHandleClick error: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log(TAG + " FloatHandleView click hook success");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookFloatHandleClick failed: " + t.getMessage());
        }
    }

    // ==================== A 方案：坞窗交给 SystemUI（实时画面） ====================

    private static boolean sRemoteDockOpen = false;
    private static boolean sRemoteDockAcked = false;
    private static BroadcastReceiver sDockEventReceiver;
    private static Handler sMainHandler;
    private static Runnable sRemoteFallback;
    private static ClassLoader sPendingClassLoader;
    /**
     * 悬浮球视图所在线程（android.ui）的 Handler，点击那一刻抓的。
     * 不能在需要时用 {@code view.post()}：悬浮球被隐藏后视图处于未 attach 状态，
     * post 会把任务丢进 RunQueue 等下一次 traversal——而它再也不会 traversal 了，恢复动作永远不执行。
     */
    private static Handler sHandleHandler;

    /** 上次坞窗打开时的应用列表：任务搬到虚拟显示器后 ColorOS 会丢掉 FloatHandleInfo，用它兜底。 */
    private static int[] sCachedTaskIds;
    private static String[] sCachedPackages;
    private static int[] sCachedUserIds;

    /** 等任务落回主屏再最小化的后台线程。 */
    private static final java.util.concurrent.ExecutorService MINIMIZER =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "freewindow-minimize"));
    private static final long MINIMIZE_WAIT_MS = 2500L;

    private static Handler mainHandler() {
        if (sMainHandler == null) sMainHandler = new Handler(Looper.getMainLooper());
        return sMainHandler;
    }

    /** 在悬浮球自己的线程执行；拿不到线程返回 false，由调用方决定是否放弃。 */
    private static boolean runOnHandleThread(Runnable work) {
        if (sHandleHandler != null) {
            sHandleHandler.post(work);
            return true;
        }
        Object handle = sCurrentFloatHandleView;
        if (handle instanceof View && ((View) handle).post(work)) {
            return true;
        }
        return false;
    }

    /**
     * 请求 SystemUI 弹出实时坞窗。成功返回 true（旧坞窗不再创建）。
     *
     * <p>SystemUI 若在 700ms 内没有回 "shown"（进程没起来 / 作用域没勾 / hook 失败），
     * 自动回退到 system_server 里的旧坞窗，保证功能不会因为迁移而不可用。
     */
    private boolean tryRemoteDock(final Object floatHandleView, final Object floatHandleInfo,
                                  final ClassLoader cl) {
        if (!enableLiveDock) return false;
        try {
            final Context ctx = (Context) XposedHelpers.callMethod(floatHandleView, "getContext");
            if (ctx == null) return false;
            ensureDockEventReceiver(ctx);

            int side = 1;
            try {
                side = XposedHelpers.getIntField(floatHandleInfo, "side");
            } catch (Throwable ignored) {
            }

            List<Object> infos = dockInfos(cl);
            int[] taskIds;
            String[] packages;
            int[] userIds;
            if (!infos.isEmpty()) {
                int n = infos.size();
                taskIds = new int[n];
                packages = new String[n];
                userIds = new int[n];
                for (int i = 0; i < n; i++) {
                    Object info = infos.get(i);
                    taskIds[i] = XposedHelpers.getIntField(info, "taskId");
                    packages[i] = (String) XposedHelpers.callMethod(info, "getPackageName");
                    userIds[i] = (int) XposedHelpers.callMethod(info, "getUserId");
                }
                // 任务被搬到虚拟显示器后 ColorOS 会丢掉 FloatHandleInfo，
                // 所以自己记一份，下次开坞窗时列表才不会空。
                sCachedTaskIds = taskIds.clone();
                sCachedPackages = packages.clone();
                sCachedUserIds = userIds.clone();
            } else if (sCachedTaskIds != null && sCachedTaskIds.length > 0) {
                List<Integer> alive = new ArrayList<>();
                for (int i = 0; i < sCachedTaskIds.length; i++) {
                    if (findTaskObject(sCachedTaskIds[i], cl) != null) alive.add(i);
                }
                taskIds = new int[alive.size()];
                packages = new String[alive.size()];
                userIds = new int[alive.size()];
                for (int k = 0; k < alive.size(); k++) {
                    int i = alive.get(k);
                    taskIds[k] = sCachedTaskIds[i];
                    packages[k] = sCachedPackages[i];
                    userIds[k] = sCachedUserIds[i];
                }
                XposedBridge.log(TAG + " dock list from cache apps=" + taskIds.length);
            } else {
                return false;
            }
            if (taskIds.length == 0) return false;

            sRemoteDockAcked = false;
            sRemoteDockOpen = true;
            sDockShowing = true;
            sPendingClassLoader = cl;
            hideAllFloatHandles(cl, 0f);

            // UserHandle.ALL 是 @hide，直接用普通广播（system_server 以 system 身份发出）。
            int[] displayIds = new int[taskIds.length];
            byte[][] snapshots = new byte[taskIds.length][];
            boolean[] dynamicFlags = new boolean[taskIds.length];
            float[] aspects = new float[taskIds.length];
            for (int i = 0; i < taskIds.length; i++) {
                displayIds[i] = MirageWindowHook.mirageDisplayOf(taskIds[i]);
                snapshots[i] = encodeSnapshot(LivelyHook.snapshotOf(taskIds[i]));
                // 每个应用独立判定，结果按任务缓存：一张卡是动态的不会把别的卡拉黑。
                dynamicFlags[i] = resolveDynamic(ctx, taskIds[i], packages[i], snapshots[i]);
                aspects[i] = taskAspect(taskIds[i], cl);
            }
            ctx.sendBroadcast(DockProtocol.showIntent(side, taskIds, packages, userIds,
                    displayIds, snapshots, dynamicFlags, aspects));
            XposedBridge.log(TAG + " remote dock requested apps=" + taskIds.length + " side=" + side);

            sRemoteFallback = new Runnable() {
                @Override
                public void run() {
                    if (!sRemoteDockOpen || sRemoteDockAcked) return;
                    XposedBridge.log(TAG + " SystemUI dock did not answer, fall back to local dock");
                    sRemoteDockOpen = false;
                    sDockShowing = false;
                    hideAllFloatHandles(cl, 1f);
                    // 本地坞窗也要在悬浮球线程上创建：窗口的 ViewRootImpl 会绑定创建线程，
                    // 之后它的触摸回调 / 悬浮球动画都跟着这个线程走。
                    Runnable create = new Runnable() {
                        @Override
                        public void run() {
                            try {
                                showFloatDock(floatHandleView, floatHandleInfo, cl);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " local dock fallback failed: " + t);
                            }
                        }
                    };
                    if (runOnHandleThread(create)) return;
                    create.run();
                }
            };
            mainHandler().postDelayed(sRemoteFallback, 700L);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " tryRemoteDock failed: " + t);
            sRemoteDockOpen = false;
            sDockShowing = false;
            return false;
        }
    }


    // ---------- 静态 / 动态判定（完全由用户在设置里指定） ----------

    /** taskId → 判定结果，一次判定长期沿用，避免别的应用播放造成翻转。 */
    private static final java.util.Map<Integer, Boolean> sDynamicCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 用户在设置里勾选的"动态应用"；非空时以它为准。 */
    private static final java.util.Set<String> sUserDynamicPackages =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void updateUserDynamicPackages(String csv) {
        java.util.Set<String> parsed = new java.util.HashSet<>();
        if (csv != null && !csv.isEmpty()) {
            for (String item : csv.split(",")) {
                if (!item.isEmpty()) parsed.add(item);
            }
        }
        if (!parsed.equals(sUserDynamicPackages)) {
            sUserDynamicPackages.clear();
            sUserDynamicPackages.addAll(parsed);
            sDynamicCache.clear();
            XposedBridge.log(TAG + " dynamic whitelist updated size=" + parsed.size());
        }
    }

    /** 任务画面的宽高比（height / width），用于让卡片按应用真实比例留高度。 */
    private static float taskAspect(int taskId, ClassLoader cl) {
        try {
            Object task = findTaskObject(taskId, cl);
            if (task != null) {
                Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    float aspect = bounds.height() / (float) bounds.width();
                    return Math.max(1f, Math.min(aspect, 3f));
                }
            }
        } catch (Throwable ignored) {
        }
        return 16f / 9f;
    }

    private static boolean resolveDynamic(Context ctx, int taskId, String pkg, byte[] snapshot) {
        Boolean cached = sDynamicCache.get(taskId);
        if (cached != null) return cached;
        // 只有用户在设置里勾选的应用走实时镜像，其余一律静态截图。
        // 例外：截图拿不到时退回镜像，避免卡片只剩一个图标。
        boolean dynamic = (pkg != null && sUserDynamicPackages.contains(pkg)) || snapshot == null;
        sDynamicCache.put(taskId, dynamic);
        XposedBridge.log(TAG + " dynamic? taskId=" + taskId + " pkg=" + pkg
                + " dynamic=" + dynamic + " snapshot=" + (snapshot != null));
        return dynamic;
    }

    /** 把快照压成小体积 JPEG，随广播带给 SystemUI 当静态卡片图。 */
    private static byte[] encodeSnapshot(android.graphics.Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        try {
            android.graphics.Bitmap scaled = source;
            int width = source.getWidth();
            if (width > 280) {
                int height = Math.max(1, Math.round(280f * source.getHeight() / width));
                scaled = android.graphics.Bitmap.createScaledBitmap(source, 280, height, true);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out);
            if (scaled != source) scaled.recycle();
            return out.toByteArray();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " encodeSnapshot failed: " + t);
            return null;
        }
    }

    private static List<Object> dockInfos(ClassLoader cl) {
        List<Object> out = new ArrayList<>();
        try {
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl), "getInstance");
            Object list = XposedHelpers.getObjectField(fhc, "mFloatHandleInfoList");
            if (list instanceof List) {
                for (Object info : (List<?>) list) out.add(info);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " dockInfos error: " + t.getMessage());
        }
        return out;
    }

    private void ensureDockEventReceiver(final Context ctx) {
        if (sDockEventReceiver != null) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleDockEvent(context, intent);
            }
        };
        IntentFilter filter = new IntentFilter(DockProtocol.ACTION_EVENT);
        filter.addAction(DockProtocol.ACTION_MIRROR_ATTACH);
        filter.addAction(DockProtocol.ACTION_MIRROR_DETACH);
        try {
            java.lang.reflect.Method method = Context.class.getMethod("registerReceiver",
                    BroadcastReceiver.class, IntentFilter.class, int.class);
            method.invoke(ctx, receiver, filter, 0x2 /* RECEIVER_EXPORTED */);
        } catch (Throwable t) {
            ctx.registerReceiver(receiver, filter);
        }
        // 注册成功之后才落字段：注册失败下次还能重试。
        sDockEventReceiver = receiver;
        XposedBridge.log(TAG + " dock event receiver registered");
    }

    private void handleDockEvent(Context ctx, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (DockProtocol.ACTION_MIRROR_ATTACH.equals(action)) {
            handleMirrorAttach(ctx, intent);
            return;
        }
        if (DockProtocol.ACTION_MIRROR_DETACH.equals(action)) {
            handleMirrorDetach(intent);
            return;
        }
        String event = intent.getStringExtra(DockProtocol.EXTRA_EVENT);
        XposedBridge.log(TAG + " dock event=" + event);
        if (DockProtocol.EVENT_SHOWN.equals(event)) {
            sRemoteDockAcked = true;
            if (sRemoteFallback != null) mainHandler().removeCallbacks(sRemoteFallback);
            return;
        }
        if (!DockProtocol.EVENT_CLOSED.equals(event)) return;

        final int chosen = intent.getIntExtra(DockProtocol.EXTRA_CHOSEN_TASK_ID, -1);
        final int[] moved = intent.getIntArrayExtra(DockProtocol.EXTRA_MOVED_TASK_IDS);
        final boolean clearAll = intent.getBooleanExtra(DockProtocol.EXTRA_CLEAR_ALL, false);

        if (sRemoteFallback != null) mainHandler().removeCallbacks(sRemoteFallback);
        sRemoteDockOpen = false;
        sRemoteDockAcked = false;
        sDockShowing = false;

        // 广播接收器跑在 system_server main 上，但恢复迷你窗 / 重新最小化都会启动 ColorOS 的悬浮球动画，
        // 必须回到悬浮球自己的线程执行（否则 main 上 requestLayout → system_server 软重启）。
        Runnable work = new Runnable() {
            @Override
            public void run() {
                applyDockClosed(chosen, moved, clearAll);
            }
        };
        if (runOnHandleThread(work)) return;
        XposedBridge.log(TAG + " no handle thread, run dock close on main");
        work.run();
    }

    // ==================== 镜像：把幻影显示器的画面接到卡片图层 ====================

    private static final java.util.Map<Integer, android.view.SurfaceControl> sMirrors =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** mirrorKey → 包名，用于拆镜像时把帧率交还系统。 */
    private static final java.util.Map<Integer, String> sMirrorPackages =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * SystemUI 递过来一个卡片图层的 SurfaceControl，我们把幻影显示器的内容镜像上去。
     * 只读：不改任务归属、不动窗口、不影响 ColorOS 的球。
     */
    private void handleMirrorAttach(final Context ctx, final Intent intent) {
        // 读 DisplayContent 要拿 WMS 全局锁，绝不能占着 system_server 主线程去等。
        MINIMIZER.execute(new Runnable() {
            @Override
            public void run() {
                doMirrorAttach(ctx, intent);
            }
        });
    }

    private void doMirrorAttach(Context ctx, Intent intent) {
        final int key = intent.getIntExtra(DockProtocol.EXTRA_MIRROR_KEY, 0);
        final int displayId = intent.getIntExtra(DockProtocol.EXTRA_DISPLAY_IDS, 0);
        final int taskId = intent.getIntExtra(DockProtocol.EXTRA_TASK_ID, 0);
        final int targetW = intent.getIntExtra(DockProtocol.EXTRA_MIRROR_WIDTH, 0);
        final int targetH = intent.getIntExtra(DockProtocol.EXTRA_MIRROR_HEIGHT, 0);
        android.os.Parcelable extra = intent.getParcelableExtra(DockProtocol.EXTRA_SURFACE);
        if (!(extra instanceof android.view.SurfaceControl) || key == 0) {
            XposedBridge.log(TAG + " mirror attach ignored displayId=" + displayId + " key=" + key);
            return;
        }
        final android.view.SurfaceControl target = (android.view.SurfaceControl) extra;
        ClassLoader cl = sPendingClassLoader != null ? sPendingClassLoader : sClassLoader;
        try {
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl), "getInstance");
            Object atms = XposedHelpers.getObjectField(fhc, "mAtms");
            Object lock = null;
            try {
                lock = XposedHelpers.getObjectField(atms, "mGlobalLock");
            } catch (Throwable ignored) {
            }
            synchronized (lock != null ? lock : sMirrors) {
                android.view.SurfaceControl source = null;
                int sourceW = 0;
                int sourceH = 0;
                // 优先镜像任务自己的图层：幻影显示器整屏里应用只占一小块，镜像整屏会一片黑。
                if (taskId > 0) {
                    Object task = findTaskObject(taskId, cl);
                    if (task != null) {
                        Object surface = XposedHelpers.callMethod(task, "getSurfaceControl");
                        if (surface instanceof android.view.SurfaceControl) {
                            source = (android.view.SurfaceControl) surface;
                        }
                        try {
                            Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
                            if (bounds != null) {
                                sourceW = bounds.width();
                                sourceH = bounds.height();
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (source == null && displayId > 0) {
                    Object rwc = XposedHelpers.getObjectField(atms, "mRootWindowContainer");
                    Object dc = XposedHelpers.callMethod(rwc, "getDisplayContent", displayId);
                    if (dc != null) {
                        Object surface = XposedHelpers.callMethod(dc, "getSurfaceControl");
                        if (surface instanceof android.view.SurfaceControl) {
                            source = (android.view.SurfaceControl) surface;
                        }
                        int[] size = displaySize(dc);
                        sourceW = size[0];
                        sourceH = size[1];
                    }
                    if (source == null) {
                        XposedBridge.log(TAG + " mirror: no display surface displayId=" + displayId);
                    }
                }
                if (source == null || !source.isValid()) {
                    XposedBridge.log(TAG + " mirror: source invalid displayId=" + displayId
                            + " taskId=" + taskId);
                    return;
                }
                Method mirror = android.view.SurfaceControl.class
                        .getDeclaredMethod("mirrorSurface", android.view.SurfaceControl.class);
                mirror.setAccessible(true);
                android.view.SurfaceControl copy =
                        (android.view.SurfaceControl) mirror.invoke(null, source);
                if (copy == null) {
                    XposedBridge.log(TAG + " mirror: mirrorSurface returned null");
                    return;
                }
                android.view.SurfaceControl.Transaction tx =
                        new android.view.SurfaceControl.Transaction().reparent(copy, target);
                float scale = fitScale(sourceW, sourceH, targetW, targetH);
                final int corner = intent.getIntExtra(DockProtocol.EXTRA_CORNER, 0);
                if (scale > 0f) {
                    // 等比铺满卡片（fit：整屏完整显示），水平居中、顶部对齐，超出部分裁掉。
                    // setMatrix / setWindowCrop / setCornerRadius 是 @hide，编译期拿不到，反射调用。
                    int cropW = Math.min(sourceW, Math.round(targetW / scale));
                    int cropH = Math.min(sourceH, Math.round(targetH / scale));
                    int cropLeft = Math.max(0, (sourceW - cropW) / 2);
                    Method setMatrix = android.view.SurfaceControl.Transaction.class.getMethod(
                            "setMatrix", android.view.SurfaceControl.class,
                            float.class, float.class, float.class, float.class);
                    Method setWindowCrop = android.view.SurfaceControl.Transaction.class.getMethod(
                            "setWindowCrop", android.view.SurfaceControl.class, Rect.class);
                    setMatrix.invoke(tx, copy, scale, 0f, 0f, scale);
                    setWindowCrop.invoke(tx, copy, new Rect(cropLeft, 0, cropLeft + cropW, cropH));
                    tx.setPosition(copy, -cropLeft * scale, 0f);
                } else {
                    tx.setPosition(copy, 0f, 0f);
                }
                if (corner > 0) {
                    try {
                        Method setCorner = android.view.SurfaceControl.Transaction.class.getMethod(
                                "setCornerRadius", android.view.SurfaceControl.class, float.class);
                        setCorner.invoke(tx, copy, (float) corner);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " setCornerRadius failed: " + t);
                    }
                }
                tx.setLayer(copy, 0).apply();
                sMirrors.put(key, copy);
                // 官方给灵动卡片单独控帧（默认会降到低帧），这里把被镜像的应用顶到 60。
                String mirrorPkg = intent.getStringExtra(DockProtocol.EXTRA_PACKAGE);
                setPackageFrameRate(mirrorPkg, 60f);
                if (mirrorPkg != null && !mirrorPkg.isEmpty()) {
                    sMirrorPackages.put(key, mirrorPkg);
                }
                XposedBridge.log(TAG + " mirror attached key=" + key
                        + " displayId=" + displayId + " taskId=" + taskId
                        + " source=" + sourceW + "x" + sourceH
                        + " target=" + targetW + "x" + targetH
                        + " scale=" + scale);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " mirror attach failed displayId=" + displayId + ": " + t);
        }
    }

    /** cover 缩放：铺满目标区域，允许裁切。尺寸不可用时返回 0（不缩放）。 */
    private static float fitScale(int sourceW, int sourceH, int targetW, int targetH) {
        if (sourceW <= 0 || sourceH <= 0 || targetW <= 0 || targetH <= 0) return 0f;
        // 卡片按应用比例留的高度，这里用 min：整屏完整显示，不会被裁掉上下。
        return Math.min(targetW / (float) sourceW, targetH / (float) sourceH);
    }

    /** 显示器逻辑尺寸（px）。 */
    private static int[] displaySize(Object displayContent) {
        try {
            Object info = XposedHelpers.callMethod(displayContent, "getDisplayInfo");
            if (info != null) {
                int w = 0;
                int h = 0;
                try {
                    w = (int) XposedHelpers.callMethod(info, "getLogicalWidth");
                    h = (int) XposedHelpers.callMethod(info, "getLogicalHeight");
                } catch (Throwable ignored) {
                }
                if (w <= 0 || h <= 0) {
                    w = XposedHelpers.getIntField(info, "logicalWidth");
                    h = XposedHelpers.getIntField(info, "logicalHeight");
                }
                if (w > 0 && h > 0) return new int[]{w, h};
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " displaySize failed: " + t);
        }
        return new int[]{0, 0};
    }

    /**
     * 给单个应用控帧（官方 LivelyViewFpsPolicy 用的同一条通路）：
     * {@code oplus_vrr_service → IOPlusRefreshRate.setFrameRateTargetControl(fps, pkg, on, caller)}。
     * fps = 60 顶到 60 帧，fps = 0 交还给系统自动。
     */
    private static void setPackageFrameRate(String pkg, float fps) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Object binder = serviceManager.getMethod("getService", String.class)
                    .invoke(null, "oplus_vrr_service");
            if (binder == null) {
                XposedBridge.log(TAG + " vrr service unavailable pkg=" + pkg);
                return;
            }
            Class<?> stub = Class.forName("com.oplus.vrr.IOPlusRefreshRate$Stub");
            Object service = stub.getMethod("asInterface", android.os.IBinder.class)
                    .invoke(null, binder);
            if (service == null) return;
            service.getClass().getMethod("setFrameRateTargetControl",
                            float.class, String.class, boolean.class, String.class)
                    .invoke(service, fps, pkg, true, "freewindow");
            XposedBridge.log(TAG + " frame rate " + fps + " pkg=" + pkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " setFrameRateTargetControl failed pkg=" + pkg + ": " + t);
        }
    }

    private void handleMirrorDetach(Intent intent) {
        int key = intent.getIntExtra(DockProtocol.EXTRA_MIRROR_KEY, 0);
        String pkg = sMirrorPackages.remove(key);
        if (pkg != null && !pkg.isEmpty()) {
            // 交还帧率控制，别让应用一直锁在 60。
            setPackageFrameRate(pkg, 0f);
        }
        android.view.SurfaceControl copy = sMirrors.remove(key);
        if (copy == null) return;
        try {
            new android.view.SurfaceControl.Transaction().reparent(copy, null).apply();
            copy.release();
            XposedBridge.log(TAG + " mirror detached key=" + key);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " mirror detach failed key=" + key + ": " + t);
        }
    }

    /** 坞窗收起时兜底清掉所有镜像。 */
    private void releaseAllMirrors() {
        for (Integer key : new ArrayList<>(sMirrorPackages.keySet())) {
            String pkg = sMirrorPackages.remove(key);
            if (pkg != null && !pkg.isEmpty()) setPackageFrameRate(pkg, 0f);
        }
        for (Integer key : new ArrayList<>(sMirrors.keySet())) {
            android.view.SurfaceControl copy = sMirrors.remove(key);
            if (copy == null) continue;
            try {
                new android.view.SurfaceControl.Transaction().reparent(copy, null).apply();
                copy.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 只允许在悬浮球自己的线程执行。 */
    private void applyDockClosed(int chosen, int[] moved, boolean clearAll) {
        ClassLoader cl = sPendingClassLoader != null ? sPendingClassLoader : sClassLoader;
        try {
            // 坞窗收起时先把镜像拆掉。
            releaseAllMirrors();
            if (clearAll) {
                clearAllDockApps(cl);
            } else if (chosen > 0) {
                Object info = findDockInfo(chosen, cl);
                if (info != null) {
                    // 用户点了某张卡：按官方逻辑把它恢复成小窗（顺带会释放幻影显示器）。
                    restoreFloatInfo(info, cl);
                } else {
                    XposedBridge.log(TAG + " no float info for chosen taskId=" + chosen);
                }
            }
            // 没有选中卡片时：应用继续留在幻影显示器上跑，球照旧显示，什么都不做。
        } catch (Throwable t) {
            XposedBridge.log(TAG + " applyDockClosed error: " + t);
        }
        hideAllFloatHandles(cl, 1f);
    }

    /** 等任务落回 display 0 后再走官方恢复，避免对着还在虚拟显示器上的任务操作。 */
    private void scheduleRestore(final int taskId, final Object info, final ClassLoader cl) {
        MINIMIZER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    long deadline = android.os.SystemClock.uptimeMillis() + MINIMIZE_WAIT_MS;
                    while (android.os.SystemClock.uptimeMillis() < deadline) {
                        Object task = findTaskObject(taskId, cl);
                        if (task == null) break;
                        int displayId = -1;
                        try {
                            Object value = XposedHelpers.callMethod(task, "getDisplayId");
                            if (value instanceof Integer) displayId = (Integer) value;
                        } catch (Throwable ignored) {
                        }
                        if (displayId == 0) break;
                        android.os.SystemClock.sleep(80L);
                    }
                    runOnHandleThread(new Runnable() {
                        @Override
                        public void run() {
                            restoreFloatInfo(info, cl);
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " scheduleRestore failed taskId=" + taskId + ": " + t);
                }
            }
        });
    }

    private Object findDockInfo(int taskId, ClassLoader cl) {
        for (Object info : dockInfos(cl)) {
            try {
                if (XposedHelpers.getIntField(info, "taskId") == taskId) return info;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 官方恢复路径：直接回调 ColorOS 自己的"点悬浮球恢复小窗"入口
     * {@code FloatHandleView.startFlexibleFreeformFromHandleView(info, reason)}。
     *
     * <p>之前改成手动调 {@code startActivityByFloatInfo} 并魔改 type/zoomState，
     * 结果恢复成的是通用浮窗（没有小窗标题栏）。官方那条路会自己把 flexibleTaskInfo 的
     * bounds/scale/ratio 算好，才是真正的自由小窗。
     */
    private void restoreFloatInfo(Object info, ClassLoader cl) {
        sRestoring = true;
        try {
            Object handle = sCurrentFloatHandleView;
            if (handle != null) {
                try {
                    XposedHelpers.callMethod(handle, "startFlexibleFreeformFromHandleView",
                            info, "freewindow_restore");
                    XposedBridge.log(TAG + " restore via official handle path taskId="
                            + XposedHelpers.getIntField(info, "taskId"));
                    return;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " official restore failed, fallback: " + t);
                }
            }
            // 兜底：老的直接恢复。
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl), "getInstance");
            XposedHelpers.callMethod(fhc, "startActivityByFloatInfo", info, "lively_panel");
            XposedBridge.log(TAG + " restoreFloatInfo(fallback) done taskId="
                    + XposedHelpers.getIntField(info, "taskId"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " restoreFloatInfo failed: " + t);
        } finally {
            // 恢复期间别再拦一次悬浮球点击（否则会又弹一次坞窗）。
            if (sHandleHandler != null) {
                sHandleHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        sRestoring = false;
                    }
                }, 1200L);
            } else {
                sRestoring = false;
            }
        }
    }

    /**
     * 后台等任务落回 display 0，再立刻把它最小化回悬浮球。
     *
     * <p>顺序很重要：坞窗一关，SystemUI 才把任务搬回主屏；如果这时直接 startMinimize，
     * 任务还在虚拟显示器上，最小化不会生效，用户看到的就是"应用全屏回来了"。
     * 所以这里在后台线程轮询 displayId，落地后马上 post 回悬浮球线程执行最小化。
     */
    private void scheduleMinimize(final int taskId, final ClassLoader cl) {
        MINIMIZER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    long deadline = android.os.SystemClock.uptimeMillis() + MINIMIZE_WAIT_MS;
                    while (android.os.SystemClock.uptimeMillis() < deadline) {
                        Object task = findTaskObject(taskId, cl);
                        if (task == null) {
                            XposedBridge.log(TAG + " scheduleMinimize: task gone taskId=" + taskId);
                            return;
                        }
                        int displayId = -1;
                        try {
                            Object value = XposedHelpers.callMethod(task, "getDisplayId");
                            if (value instanceof Integer) displayId = (Integer) value;
                        } catch (Throwable ignored) {
                        }
                        if (displayId == 0) {
                            final int readyTask = taskId;
                            if (!runOnHandleThread(new Runnable() {
                                @Override
                                public void run() {
                                    // 关键一步：任务从虚拟显示器搬回来时已经变成"全屏窗口"，
                                    // 这时直接 startMinimize 不会生效（它只认自由小窗状态）。
                                    // 先按官方逻辑把它还原成迷你小窗，再让它缩回悬浮球。
                                    Object info = findDockInfo(taskId, cl);
                                    boolean restored = false;
                                    if (info != null) {
                                        restored = restoreFloatInfoQuiet(info, cl);
                                    }
                                    if (restored && sHandleHandler != null) {
                                        sHandleHandler.postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                doMinimize(readyTask, cl);
                                            }
                                        }, 220L);
                                    } else {
                                        doMinimize(readyTask, cl);
                                    }
                                }
                            })) {
                                XposedBridge.log(TAG + " scheduleMinimize: no handle thread taskId=" + taskId);
                            }
                            return;
                        }
                        android.os.SystemClock.sleep(80L);
                    }
                    XposedBridge.log(TAG + " scheduleMinimize: timeout waiting display 0 taskId=" + taskId);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " scheduleMinimize failed taskId=" + taskId + ": " + t);
                }
            }
        });
    }

    private static Object findTaskObject(int taskId, ClassLoader cl) {
        try {
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl), "getInstance");
            Object atms = XposedHelpers.getObjectField(fhc, "mAtms");
            Object rwc = XposedHelpers.getObjectField(atms, "mRootWindowContainer");
            return XposedHelpers.callMethod(rwc, "anyTaskForId", taskId);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 静默版：把任务先还原成 ColorOS 的迷你小窗状态（不打印 done 日志，失败只记一行）。
     * 返回是否成功调用到官方恢复接口。
     */
    private boolean restoreFloatInfoQuiet(Object info, ClassLoader cl) {
        try {
            try {
                XposedHelpers.callMethod(info, "setFlexibleTaskState", 2);
            } catch (Throwable ignored) {
            }
            try {
                int type = XposedHelpers.getIntField(info, "type");
                if (type == 0) XposedHelpers.setIntField(info, "type", 3);
            } catch (Throwable ignored) {
            }
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl), "getInstance");
            XposedHelpers.callMethod(fhc, "startActivityByFloatInfo", info, "lively_panel");
            XposedBridge.log(TAG + " pre-minimize restore taskId="
                    + XposedHelpers.getIntField(info, "taskId"));
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " pre-minimize restore failed: " + t);
            return false;
        }
    }

    /**
     * 把已经搬回 display 0 的任务重新最小化回悬浮球（必须在悬浮球线程执行）。
     * 链路对齐 ColorOS 自身：FlexibleWindowManagerService → FlexibleTaskController
     * → getFlexibleInfoManager(task).getZoomTaskInfo() → OplusFlexibleDCSManager.startMinimize(info, task, "8")
     */
    private void doMinimize(int taskId, ClassLoader cl) {
        try {
            Object task = findTaskObject(taskId, cl);
            if (task == null) {
                XposedBridge.log(TAG + " reMinimize: task not found taskId=" + taskId);
                return;
            }
            // 先把任务压回后台：任务离开 display 0 后 ColorOS 会丢掉它的 FloatHandleInfo，
            // startMinimize 已经没有作用对象，不压后台就会以全屏窗口停在桌面上。
            try {
                XposedHelpers.callMethod(task, "moveToBack", "freewindow", null);
                XposedBridge.log(TAG + " sent task to back taskId=" + taskId);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " moveToBack failed taskId=" + taskId + ": " + t);
            }
            Object fwms = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FlexibleWindowManagerService", cl),
                    "getInstance", new Object[]{null});
            Object ftc = XposedHelpers.callMethod(fwms, "getFlexibleTaskController");
            Object infoMgr = callOneArg(ftc, "getFlexibleInfoManager", task);
            if (infoMgr == null) {
                XposedBridge.log(TAG + " reMinimize: no flexible info manager taskId=" + taskId);
                return;
            }
            Object zoomInfo = XposedHelpers.callMethod(infoMgr, "getZoomTaskInfo");
            Object dcs = XposedHelpers.callMethod(ftc, "getmOplusFlexibleDCSManager");
            XposedHelpers.callMethod(dcs, "startMinimize", zoomInfo, task, "8");
            XposedBridge.log(TAG + " re-minimized taskId=" + taskId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " reMinimize failed taskId=" + taskId + ": " + t);
        }
    }

    /** 按名字找单参方法（参数类型按实参推断），用于跨 ROM 的方法签名差异。 */
    private static Object callOneArg(Object target, String name, Object argument) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            for (Method method : type.getDeclaredMethods()) {
                if (!name.equals(method.getName()) || method.getParameterCount() != 1) continue;
                Class<?> parameter = method.getParameterTypes()[0];
                if (!parameter.isInstance(argument)) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(target, argument);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " callOneArg " + name + " failed: " + t);
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    /** 显示坞窗：从悬浮球所在侧滑出，展示所有悬浮球应用卡片。 */
    private void showFloatDock(Object floatHandleView, Object floatHandleInfo, ClassLoader cl) {
        try {
            if (sDockClosing) {
                return;
            }
            if (sDockContainer != null) {
                return;
            }

            // 获取 Context
            Context ctx = (Context) XposedHelpers.callMethod(floatHandleView, "getContext");
            if (ctx == null) {
                XposedBridge.log(TAG + " showFloatDock: context is null");
                return;
            }

            // 获取屏幕尺寸
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            int screenWidth = ctx.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = ctx.getResources().getDisplayMetrics().heightPixels;

            // 判断悬浮球在左边还是右边
            int side = 1; // 默认右边
            try {
                side = XposedHelpers.getIntField(floatHandleInfo, "side");
            } catch (Throwable ignored) {
            }
            XposedBridge.log(TAG + " showFloatDock side=" + side);

            hideAllFloatHandles(cl, 0f);

            // 全屏透明容器
            final FrameLayout container = new FrameLayout(ctx);
            container.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 坞窗主体
            LinearLayout dock = new LinearLayout(ctx);
            dock.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable dockBg = new GradientDrawable();
            dockBg.setColor(0xE5E5E5E5); // #e5e5e5 with alpha
            int dockCorner = dpToPx(ctx, 20);
            if (side == 1) {
                // 右边弹出，左边圆角
                dockBg.setCornerRadii(new float[]{dockCorner, dockCorner, 0, 0, 0, 0, dockCorner, dockCorner});
            } else {
                // 左边弹出，右边圆角
                dockBg.setCornerRadii(new float[]{0, 0, dockCorner, dockCorner, dockCorner, dockCorner, 0, 0});
            }
            dock.setBackground(dockBg);

            int dockWidth = screenWidth * 4 / 9;
            int dockHeight = screenHeight - dpToPx(ctx, 100); // 上下各留50dp
            FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(dockWidth, dockHeight);
            dockParams.gravity = Gravity.CENTER_VERTICAL | (side == 1 ? Gravity.END : Gravity.START);
            container.addView(dock, dockParams);

            // 滚动区域（应用卡片列表）
            ScrollView scrollView = new ScrollView(ctx);
            scrollView.setBackgroundColor(0x00000000); // 透明
            scrollView.setNestedScrollingEnabled(false);
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            scrollView.setLayoutParams(scrollParams);

            LinearLayout cardList = new LinearLayout(ctx);
            cardList.setOrientation(LinearLayout.VERTICAL);
            cardList.setPadding(dpToPx(ctx, 12), dpToPx(ctx, 12), dpToPx(ctx, 12), dpToPx(ctx, 12));
            scrollView.addView(cardList);
            dock.addView(scrollView);

            // 清除按钮
            TextView clearBtn = new TextView(ctx);
            clearBtn.setText("清除");
            clearBtn.setTextColor(0xFF000000);
            clearBtn.setTextSize(15);
            clearBtn.setGravity(Gravity.CENTER);
            GradientDrawable clearBg = new GradientDrawable();
            clearBg.setColor(0xFFF3F3F3);
            clearBg.setCornerRadius(dpToPx(ctx, 24));
            clearBtn.setBackground(clearBg);
            LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                    dockWidth - dpToPx(ctx, 60), dpToPx(ctx, 48));
            clearParams.gravity = Gravity.CENTER_HORIZONTAL;
            clearParams.bottomMargin = dpToPx(ctx, 25);
            clearParams.topMargin = dpToPx(ctx, 5);
            dock.addView(clearBtn, clearParams);

            // 获取所有悬浮球应用
            sDockApps.clear();
            try {
                Object fhc = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl), "getInstance");
                Object infoList = XposedHelpers.getObjectField(fhc, "mFloatHandleInfoList");
                if (infoList instanceof List) {
                    for (Object info : (List<?>) infoList) {
                        sDockApps.add(info);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " get float handle list error: " + t.getMessage());
            }

            XposedBridge.log(TAG + " dock apps count=" + sDockApps.size());

            // 添加应用卡片
            for (Object info : sDockApps) {
                try {
                    addAppCard(ctx, cardList, info, cl);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " add app card error: " + t.getMessage());
                }
            }

            // 清除按钮点击
            final ClassLoader finalCl = cl;
            clearBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        clearAllDockApps(finalCl);
                        removeFloatDock();
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " clear all error: " + t.getMessage());
                    }
                }
            });

            // 点击坞窗外收回，点击坞窗内不拦截
            final LinearLayout finalDock = dock;
            container.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        int[] loc = new int[2];
                        finalDock.getLocationOnScreen(loc);
                        float x = event.getRawX();
                        float y = event.getRawY();
                        boolean insideDock = x >= loc[0] && x <= loc[0] + finalDock.getWidth()
                                && y >= loc[1] && y <= loc[1] + finalDock.getHeight();
                        if (!insideDock) {
                            removeFloatDock();
                            return true;
                        }
                    }
                    return false;
                }
            });

            // 窗口参数
            WindowManager.LayoutParams windowParams = new WindowManager.LayoutParams(2520); // TYPE_SYSTEM_EDGE_PANEL
            windowParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            try {
                Field pf = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
                pf.setAccessible(true);
                pf.setInt(windowParams, 16); // PRIVATE_FLAG_TRUSTED_OVERLAY
            } catch (Throwable ignored) {
            }
            windowParams.format = PixelFormat.TRANSLUCENT;
            windowParams.width = screenWidth;
            windowParams.height = screenHeight;
            windowParams.gravity = Gravity.TOP | Gravity.START;
            windowParams.setTitle("FreeWindowDock");

            wm.addView(container, windowParams);
            sDockContainer = container;
            sDockPanel = dock;
            sDockSide = side;
            sDockShowing = true;

            // 右侧：从右往左滑出；左侧：从左往右滑出（官方 lively 时长/插值）
            dock.setTranslationX(side == 1 ? dockWidth : -dockWidth);
            dock.animate()
                    .translationX(0)
                    .setDuration(DOCK_ANIM_MS)
                    .setInterpolator(DOCK_INTERPOLATOR)
                    .start();

            XposedBridge.log(TAG + " showed float dock, side=" + side + ", apps=" + sDockApps.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " showFloatDock error: " + t.getMessage());
        }
    }

    /** 添加一个应用卡片到坞窗。 */
    private void addAppCard(Context ctx, LinearLayout cardList, Object floatHandleInfo, ClassLoader cl) {
        // 获取包名和任务信息（FloatHandleInfo 直接有 getPackageName/getUserId 和 taskId 字段）
        String pkgName = "";
        int userId = 0;
        int taskId = -1;
        try {
            pkgName = (String) XposedHelpers.callMethod(floatHandleInfo, "getPackageName");
            userId = (int) XposedHelpers.callMethod(floatHandleInfo, "getUserId");
            taskId = XposedHelpers.getIntField(floatHandleInfo, "taskId");
        } catch (Throwable ignored) {
        }
        XposedBridge.log(TAG + " addAppCard pkg=" + pkgName + " taskId=" + taskId + " userId=" + userId);

        // 卡片容器（透明背景）
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dpToPx(ctx, 5);
        cardList.addView(card, cardParams);

        // 顶部栏：图标 + 名称 + 三点菜单
        LinearLayout topBar = new LinearLayout(ctx);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dpToPx(ctx, 10), dpToPx(ctx, 8), dpToPx(ctx, 10), dpToPx(ctx, 8));
        card.addView(topBar);

        // 应用图标
        ImageView icon = new ImageView(ctx);
        int iconSize = dpToPx(ctx, 36);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        topBar.addView(icon, iconParams);
        try {
            PackageManager pm = ctx.getPackageManager();
            // getApplicationInfoAsUser 是隐藏API，用反射调用
            ApplicationInfo appInfo = (ApplicationInfo) XposedHelpers.callMethod(pm,
                    "getApplicationInfoAsUser", pkgName, 512, userId);
            icon.setImageDrawable(pm.getApplicationIcon(appInfo));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " get icon error: " + t.getMessage());
        }

        // 应用名称
        TextView nameTv = new TextView(ctx);
        nameTv.setTextSize(14);
        nameTv.setTextColor(0xFF000000);
        nameTv.setMaxLines(1);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.leftMargin = dpToPx(ctx, 10);
        topBar.addView(nameTv, nameParams);
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo appInfo = (ApplicationInfo) XposedHelpers.callMethod(pm,
                    "getApplicationInfoAsUser", pkgName, 512, userId);
            CharSequence label = pm.getApplicationLabel(appInfo);
            nameTv.setText(label != null ? label : pkgName);
        } catch (Throwable t) {
            nameTv.setText(pkgName.isEmpty() ? "未知" : pkgName);
        }

        // 三点菜单按钮
        final TextView menuBtn = new TextView(ctx);
        menuBtn.setText("⋮");
        menuBtn.setTextSize(22);
        menuBtn.setTextColor(0xFF333333);
        topBar.addView(menuBtn);

        final String finalPkgName = pkgName;
        final int finalUserId = userId;
        final int finalTaskId = taskId;
        final Object finalInfo = floatHandleInfo;
        final ClassLoader finalCl = cl;

        menuBtn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                XposedBridge.log(TAG + " menuBtn onTouch action=" + event.getAction());
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    showAppMenu(ctx, menuBtn, finalPkgName, finalUserId, finalTaskId, finalInfo, finalCl);
                    return true;
                }
                return true;
            }
        });

        // 预览区：迷你浮窗静态画面（ImageView）
        ImageView preview = LivelyHook.createPreview(ctx, floatHandleInfo, cl);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(ctx, 200));
        previewParams.leftMargin = dpToPx(ctx, 10);
        previewParams.rightMargin = dpToPx(ctx, 10);
        previewParams.bottomMargin = dpToPx(ctx, 10);
        card.addView(preview, previewParams);

        View.OnClickListener restoreMini = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restoreMiniFromCard(finalInfo, finalCl);
            }
        };
        preview.setOnClickListener(restoreMini);
        card.setOnClickListener(restoreMini);
    }

    /**
     * 点击坞窗卡片：关闭坞窗，按官方 startActivityByFloatInfo 恢复为迷你浮窗。
     */
    private void restoreMiniFromCard(Object floatHandleInfo, ClassLoader cl) {
        try {
            XposedBridge.log(TAG + " restoreMiniFromCard");
            if (floatHandleInfo == null || cl == null) return;
            try {
                XposedHelpers.callMethod(floatHandleInfo, "setFlexibleTaskState", 2);
            } catch (Throwable ignored) {
                try {
                    XposedHelpers.setIntField(floatHandleInfo, "zoomState", 2);
                } catch (Throwable ignored2) {
                }
            }
            try {
                int type = XposedHelpers.getIntField(floatHandleInfo, "type");
                if (type == 0) {
                    XposedHelpers.setIntField(floatHandleInfo, "type", 3);
                }
            } catch (Throwable ignored) {
            }
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl), "getInstance");
            removeFloatDock();
            try {
                int tid = XposedHelpers.getIntField(floatHandleInfo, "taskId");
                MirageWindowHook.clearHangUp(tid);
            } catch (Throwable ignored) {
            }
            XposedHelpers.callMethod(fhc, "startActivityByFloatInfo", floatHandleInfo, "lively_panel");
            XposedBridge.log(TAG + " restoreMiniFromCard startActivityByFloatInfo done");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " restoreMiniFromCard error: " + t.getMessage());
        }
    }

    /** 弹出菜单（用 PopupWindow.showAsDropDown 自动锚定位置）。 */
    private static android.widget.PopupWindow sAppMenuPopup = null;

    /** 显示单个应用的弹出菜单（取消运行、取消静音、关闭）。 */
    private void showAppMenu(Context ctx, View anchor, final String pkgName, final int userId,
                             final int taskId, final Object floatHandleInfo, final ClassLoader cl) {
        XposedBridge.log(TAG + " showAppMenu called, pkg=" + pkgName);
        removeAppMenu();

        final int menuWidth = dpToPx(ctx, 120);
        final int itemHeight = dpToPx(ctx, 44);

        // 菜单容器
        LinearLayout menuContainer = new LinearLayout(ctx);
        menuContainer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setColor(0xFFFFFFFF);
        menuBg.setCornerRadius(dpToPx(ctx, 12));
        menuContainer.setBackground(menuBg);
        menuContainer.setElevation(dpToPx(ctx, 8));

        // 菜单项
        String[] items = {"取消运行", "取消静音", "关闭"};
        for (int i = 0; i < items.length; i++) {
            final String item = items[i];
            // 分隔线（第一项不加）
            if (i > 0) {
                View divider = new View(ctx);
                divider.setBackgroundColor(0x14000000);
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        menuWidth - dpToPx(ctx, 28), 1);
                dividerParams.leftMargin = dpToPx(ctx, 14);
                menuContainer.addView(divider, dividerParams);
            }
            TextView itemTv = new TextView(ctx);
            itemTv.setText(item);
            itemTv.setTextSize(14);
            itemTv.setTextColor(0xFF222222);
            itemTv.setGravity(Gravity.CENTER_VERTICAL);
            itemTv.setPadding(dpToPx(ctx, 16), 0, dpToPx(ctx, 16), 0);
            itemTv.setClickable(true);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(menuWidth, itemHeight);
            itemTv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    XposedBridge.log(TAG + " menu item clicked: " + item);
                    removeAppMenu();
                    if ("取消运行".equals(item)) {
                        removeSingleApp(pkgName, userId, taskId, floatHandleInfo, cl);
                        removeFloatDock();
                    }
                }
            });
            menuContainer.addView(itemTv, itemParams);
        }

        // 用 PopupWindow 自动锚定在 anchor 下方
        android.widget.PopupWindow popup = new android.widget.PopupWindow(
                menuContainer, menuWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(dpToPx(ctx, 8));
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setOnDismissListener(new android.widget.PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                sAppMenuPopup = null;
            }
        });
        sAppMenuPopup = popup;
        popup.showAsDropDown(anchor, -dpToPx(ctx, 30), dpToPx(ctx, 2));
        XposedBridge.log(TAG + " showAppMenu popup shown");
    }

    /** 移除弹出菜单。 */
    private void removeAppMenu() {
        if (sAppMenuPopup != null) {
            try {
                sAppMenuPopup.dismiss();
            } catch (Throwable ignored) {
            }
            sAppMenuPopup = null;
        }
    }

    /** 清除单个应用：用官方 removeFloatHandle 移除悬浮球 + 杀进程。 */
    private void removeSingleApp(String pkgName, int userId, int taskId, Object floatHandleInfo, ClassLoader cl) {
        try {
            // 官方逻辑：removeFloatHandle(int taskId, null, true, 5)
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl), "getInstance");
            XposedHelpers.callMethod(fhc, "removeFloatHandle", taskId, null, true, 5);
            XposedBridge.log(TAG + " official removeFloatHandle taskId=" + taskId);
            // 杀进程
            try {
                Object am = XposedHelpers.callStaticMethod(android.app.ActivityManager.class, "getService");
                XposedHelpers.callMethod(am, "forceStopPackage", pkgName, userId);
                XposedBridge.log(TAG + " force stopped " + pkgName);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " forceStop error: " + t.getMessage());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " removeSingleApp error: " + t.getMessage());
        }
    }

    /** 清除坞窗中所有应用。 */
    private void clearAllDockApps(ClassLoader cl) {
        // 不依赖 sDockApps：实时坞窗由 SystemUI 侧展示，system_server 这边只按官方数据源取列表。
        for (Object info : dockInfos(cl)) {
            try {
                String pkgName = (String) XposedHelpers.callMethod(info, "getPackageName");
                int userId = (int) XposedHelpers.callMethod(info, "getUserId");
                int taskId = XposedHelpers.getIntField(info, "taskId");
                removeSingleApp(pkgName, userId, taskId, info, cl);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " clear app error: " + t.getMessage());
            }
        }
        sDockApps.clear();
    }

    /** 移除坞窗：右侧从左往右滑入收回，左侧从右往左滑入收回，结束后再显示悬浮球。 */
    private void removeFloatDock() {
        if (sDockClosing) {
            return;
        }
        removeAppMenu();
        if (sDockContainer == null) {
            finishRemoveDock();
            return;
        }
        sDockClosing = true;
        View dock = sDockPanel;
        if (dock == null && sDockContainer.getChildCount() > 0) {
            dock = sDockContainer.getChildAt(0);
        }
        if (dock == null) {
            finishRemoveDock();
            return;
        }
        int width = dock.getWidth();
        if (width <= 0) {
            width = dock.getLayoutParams() != null ? dock.getLayoutParams().width : 0;
        }
        int outX = sDockSide == 1 ? width : -width;
        dock.animate().cancel();
        dock.animate()
                .translationX(outX)
                .setDuration(DOCK_ANIM_MS)
                .setInterpolator(DOCK_INTERPOLATOR)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        finishRemoveDock();
                    }
                })
                .start();
    }

    private void finishRemoveDock() {
        if (sDockContainer != null) {
            try {
                Context ctx = sDockContainer.getContext();
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(sDockContainer);
            } catch (Throwable ignored) {
            }
            sDockContainer = null;
        }
        sDockPanel = null;
        sDockShowing = false;
        sDockClosing = false;
        if (sClassLoader != null) {
            hideAllFloatHandles(sClassLoader, 1f);
        }
    }

    /**
     * 悬浮球显隐。
     *
     * <p><b>必须钉回悬浮球自己的线程</b>：ColorOS 的 {@code hideFloatHandleView()} /
     * {@code showFloatHandleView()} 内部就是 {@code startSwitchModeAnimation(...)}，
     * 弹簧动画绑定的是<b>调用线程</b>的 Choreographer；而悬浮球视图树是在 {@code android.ui} 线程创建的。
     * 一旦从 system_server main 调用（例如广播接收器回调），动画帧会在 main 上 requestLayout，
     * 触发 ViewRootImpl 的线程校验，直接崩掉 system_server（软重启）。
     *
     * <p>所以这里只把任务 post 给 handle 自己；post 不进去（未 attach）就放弃动画——
     * 宁可不隐藏悬浮球，也不能崩系统。
     */
    private void hideAllFloatHandles(final ClassLoader cl, final float alpha) {
        final Object handle = sCurrentFloatHandleView;
        if (!(handle instanceof View)) {
            XposedBridge.log(TAG + " no float handle view, skip show/hide alpha=" + alpha);
            return;
        }
        Runnable work = new Runnable() {
            @Override
            public void run() {
                applyHandleVisibility(cl, alpha);
            }
        };
        if (!runOnHandleThread(work)) {
            XposedBridge.log(TAG + " no handle thread, skip show/hide alpha=" + alpha);
        }
    }

    /** 只允许在悬浮球自己的线程执行。 */
    private void applyHandleVisibility(ClassLoader cl, float alpha) {
        boolean hide = alpha == 0f;
        applyHandleAlpha(sCurrentFloatHandleView, hide);
        Object fhc = null;
        try {
            fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl), "getInstance");
            Object floatManager = XposedHelpers.getObjectField(fhc, "mFloatManager");
            if (floatManager != null) {
                if (hide) {
                    // manager 内部已经带 startSwitchModeAnimation(current, 1)，不要再自己调一次。
                    XposedHelpers.callMethod(floatManager, "hideFloatHandleView");
                    XposedBridge.log(TAG + " hideAllFloatHandles: hideFloatHandleView");
                } else {
                    applyHandleAlpha(XposedHelpers.callMethod(floatManager, "getView"), false);
                    XposedHelpers.callMethod(floatManager, "showFloatHandleView");
                    XposedBridge.log(TAG + " hideAllFloatHandles: showFloatHandleView");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hideAllFloatHandles error: " + t.getMessage());
        }
        if (!hide) {
            final Object controller = fhc;
            restoreHandleCorners(sCurrentFloatHandleView, controller);
            if (sCurrentFloatHandleView instanceof View) {
                ((View) sCurrentFloatHandleView).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        restoreHandleCorners(sCurrentFloatHandleView, controller);
                    }
                }, DOCK_ANIM_MS);
            }
        }
    }

    private void applyHandleAlpha(Object handle, final boolean hide) {
        if (!(handle instanceof View)) return;
        final View v = (View) handle;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    XposedHelpers.callMethod(v, "setForceHidden", hide);
                } catch (Throwable ignored) {
                }
                v.setAlpha(hide ? 0f : 1f);
            }
        };
        if (!v.post(r)) {
            r.run();
        }
    }

    private void restoreHandleCorners(Object handle, Object fhc) {
        if (!(handle instanceof View)) return;
        final View v = (View) handle;
        v.post(new Runnable() {
            @Override
            public void run() {
                try {
                    android.os.IBinder token = v.getWindowToken();
                    if (token != null && fhc != null) {
                        XposedHelpers.callMethod(fhc, "resetFloatHandleViewCornersAndShadow", token);
                    }
                } catch (Throwable ignored) {
                }
                restoreOutline(v);
                try {
                    Object container = XposedHelpers.getObjectField(v, "mFloatHandleViewContainer");
                    if (container instanceof View) {
                        restoreOutline((View) container);
                    }
                } catch (Throwable ignored) {
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        restoreOutline(g.getChildAt(i));
                    }
                }
            }
        });
    }

    private void restoreOutline(View v) {
        if (v == null) return;
        v.setClipToOutline(true);
        v.invalidateOutline();
    }

    /** dp 转 px。 */
    private int dpToPx(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics());
    }
}
