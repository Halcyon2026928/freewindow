package io.github.halcyon2026928.freewindow;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.UserHandle;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 游戏助手 HangUpUtil 同一套幻影后台挂机 API：
 *   OplusMirageOptions.makeBackgroundStreamModeOptions()
 *   OplusMirageWindowManager.startMirageWindowMode(intent, bundle)
 *
 * ColorOS17 适配对齐 LuckyTool MultiApp 路径。
 * 悬浮球显隐对齐 FlymeFreeform：
 *   - 最小化后官方加球，恢复时允许官方 remove
 *   - 仅在幻影 startActivity 竞态窗口内短时挡住 remove
 *   - 坞窗显隐仍由 MainHook 走 hide/showFloatHandleView
 */
public class MirageWindowHook {

    private static final String TAG = "[freewindow-hangup]";
    private static final String CAR_DUMMY = "android.intent.action.OPLUS_MIRAGE_CAR_DUMMY";
    /** 幻影 startActivity 触发 removeFloatHandle 的短时保护，对齐官方恢复后可拆球。 */
    private static final long KEEP_FLOAT_MS = 2800L;
    /** 同一次最小化里 ColorOS 会多次调 startMinimize（operation 1/4/8…），这段时间内只处理第一次。 */
    private static final long MINIMIZE_DEDUPE_MS = 3000L;
    private static final AtomicInteger sHangUpTaskId = new AtomicInteger(-1);
    private static volatile long sKeepFloatUntil = 0L;
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    /** taskId → 幻影显示器 id。每个任务只允许存在一个，恢复/移除时按 id 释放。 */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Integer> sMirageDisplays =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Long> sMinimizeAt =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static ClassLoader sClassLoader;

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;
        XposedBridge.log(TAG + " === Game HangUp (LuckyTool C17 path) start ===");
        hookStartMinimize(cl);
        hookMoveTaskToBack(cl);
        hookStartActivityToMirageDisplay(cl);
        hookKeepFloatHandle(cl);
        hookClearHangUpOnRestore(cl);
        XposedBridge.log(TAG + " === Game HangUp done ===");
    }

    /** 外部（坞窗恢复）也可主动清挂机态，让官方 removeFloatHandle 生效，并释放幻影显示器。 */
    public static void clearHangUp(int taskId) {
        int cur = sHangUpTaskId.get();
        if (taskId < 0 || cur == taskId || taskId == Integer.MIN_VALUE) {
            sHangUpTaskId.set(-1);
            sKeepFloatUntil = 0L;
            XposedBridge.log(TAG + " hangUp cleared taskId=" + taskId);
        }
        if (taskId > 0) releaseMirage(taskId);
    }

    /**
     * 按 displayId 释放幻影显示器（官方 OplusMirageDisplayManagerService.releaseVirtualDisplayDevice）。
     * 不释放的话每次最小化都会新建一个 display，任务被反复拉起、显示器一直泄漏。
     */

    /** 这个任务当前在哪个幻影显示器上跑；0 表示没有（没在后台运行）。 */
    public static int mirageDisplayOf(int taskId) {
        Integer displayId = sMirageDisplays.get(taskId);
        return displayId == null ? 0 : displayId;
    }

    private static void releaseMirage(int taskId) {
        Integer displayId = sMirageDisplays.remove(taskId);
        if (displayId == null || displayId <= 0) return;
        ClassLoader cl = sClassLoader;
        if (cl == null) return;
        try {
            Object service = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(
                            "com.android.server.display.OplusMirageDisplayManagerService", cl), "getInstance");
            if (service != null) {
                XposedHelpers.callMethod(service, "releaseVirtualDisplayDevice", displayId.intValue());
                XposedBridge.log(TAG + " released mirage displayId=" + displayId + " taskId=" + taskId);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " releaseMirage failed displayId=" + displayId + ": " + t);
        }
    }

    /** LuckyTool FloatWindowBackRun：startMinimize 后挂机。 */
    private static void hookStartMinimize(final ClassLoader cl) {
        try {
            Class<?> dcs = XposedHelpers.findClass(
                    "com.android.server.wm.OplusFlexibleDCSManager", cl);
            XposedBridge.hookAllMethods(dcs, "startMinimize", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object task = findTask(param.args);
                        if (task == null) {
                            XposedBridge.log(TAG + " startMinimize: Task not in args");
                            return;
                        }
                        int taskId = taskIdOf(task);
                        int userId = userIdFromArgsOrTask(param.args, task);
                        Intent baseIntent = intentFromTask(task);
                        if (baseIntent == null) {
                            XposedBridge.log(TAG + " startMinimize: baseIntent null");
                            return;
                        }
                        // 去重：同一任务已有幻影显示器就直接复用，不能再开一个（否则显示器和应用实例都会泄漏）。
                        Integer existing = sMirageDisplays.get(taskId);
                        if (existing != null && existing > 0) {
                            XposedBridge.log(TAG + " startMinimize: already running displayId=" + existing
                                    + " taskId=" + taskId);
                            return;
                        }
                        long now = SystemClock.uptimeMillis();
                        Long last = sMinimizeAt.get(taskId);
                        if (last != null && now - last < MINIMIZE_DEDUPE_MS) {
                            XposedBridge.log(TAG + " startMinimize: duplicate call ignored taskId=" + taskId);
                            return;
                        }
                        sMinimizeAt.put(taskId, now);

                        if (userId >= 0) {
                            baseIntent.putExtra("TASKINFO_UID", userId);
                        }
                        sHangUpTaskId.set(taskId);
                        armKeepFloatWindow();
                        setTaskLively(task, true);
                        int displayId = startMirageWindow(cl, baseIntent);
                        if (displayId > 0) {
                            sMirageDisplays.put(taskId, displayId);
                        }
                        XposedBridge.log(TAG + " HangUp startMirageWindowMode taskId=" + taskId
                                + " userId=" + userId + " pkg=" + pkgOf(baseIntent)
                                + " displayId=" + displayId);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " startMinimize hangup error: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + " hooked startMinimize");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookStartMinimize fail: " + t);
        }
    }

    private static void hookMoveTaskToBack(ClassLoader cl) {
        try {
            Class<?> mws = XposedHelpers.findClass(
                    "com.android.server.wm.OplusMirageWindowManagerService", cl);
            XposedBridge.hookAllMethods(mws, "moveTaskToBack", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length == 0 || param.args[0] == null) return;
                        int id = taskIdOf(param.args[0]);
                        if (sHangUpTaskId.get() != -1 && id == sHangUpTaskId.get()) {
                            param.setResult(null);
                            XposedBridge.log(TAG + " moveTaskToBack blocked taskId=" + id);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + " hooked moveTaskToBack");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookMoveTaskToBack fail: " + t);
        }
    }

    /**
     * FlymeFreeform：不永久拦 remove；官方最小化加球、恢复拆球。
     * 仅短时挡住幻影 startActivity → isNeedRemoveFloat 那一次竞态。
     */
    private static void hookKeepFloatHandle(ClassLoader cl) {
        try {
            Class<?> fhc = XposedHelpers.findClass(
                    "com.android.server.wm.FloatHandleController", cl);
            XposedBridge.hookAllMethods(fhc, "removeFloatHandleInner", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (shouldKeepFloat(param.args)) {
                        param.setResult(null);
                        XposedBridge.log(TAG + " removeFloatHandleInner TEMP-BLOCK taskId="
                                + sHangUpTaskId.get());
                    }
                }
            });
            XposedBridge.hookAllMethods(fhc, "removeFloatHandle", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (shouldKeepFloat(param.args)) {
                        param.setResult(null);
                        XposedBridge.log(TAG + " removeFloatHandle TEMP-BLOCK taskId="
                                + sHangUpTaskId.get());
                    }
                }
            });
            XposedBridge.hookAllMethods(fhc, "isNeedRemoveFloat", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (shouldKeepFloat(param.args)) {
                        param.setResult(false);
                    }
                }
            });
            XposedBridge.log(TAG + " hooked keep FloatHandle (temp window)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookKeepFloatHandle fail: " + t);
        }
    }

    /** 恢复自由小窗时清挂机态，允许官方 removeFloatHandle。 */
    private static void hookClearHangUpOnRestore(ClassLoader cl) {
        try {
            Class<?> fhc = XposedHelpers.findClass(
                    "com.android.server.wm.FloatHandleController", cl);
            XposedBridge.hookAllMethods(fhc, "startActivityByFloatInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int tid = taskIdFromFloatArgs(param.args);
                    if (tid >= 0 && tid == sHangUpTaskId.get()) {
                        clearHangUp(tid);
                    }
                }
            });
            // 应用被真正移除（关掉/清后台）时也要释放幻影显示器。
            XposedBridge.hookAllMethods(fhc, "removeFloatHandleInner", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int tid = taskIdFromFloatArgs(param.args);
                    if (tid > 0) releaseMirage(tid);
                }
            });
            XposedBridge.log(TAG + " hooked clear hangUp on restore");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookClearHangUpOnRestore fail: " + t);
        }
    }

    private static void armKeepFloatWindow() {
        sKeepFloatUntil = SystemClock.uptimeMillis() + KEEP_FLOAT_MS;
    }

    private static boolean shouldKeepFloat(Object[] args) {
        if (SystemClock.uptimeMillis() > sKeepFloatUntil) return false;
        int hangId = sHangUpTaskId.get();
        if (hangId < 0 || args == null) return false;
        for (Object a : args) {
            if (a instanceof Integer && ((Integer) a) == hangId) return true;
            if (a != null && "com.android.server.wm.Task".equals(a.getClass().getName())
                    && taskIdOf(a) == hangId) {
                return true;
            }
            if (a != null && a.getClass().getName().contains("FloatHandleInfo")) {
                try {
                    if (XposedHelpers.getIntField(a, "taskId") == hangId) return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    /**
     * LuckyTool MultiAppFloatWindowBackRun（C15+/C17 关键）：
     * 系统默认 startActivityToMirageDisplay 对部分应用失败；
     * 改成系统 Context 把 Activity 真正拉到幻影 display，再 notifyCastSuccess。
     */
    private static void hookStartActivityToMirageDisplay(final ClassLoader cl) {
        try {
            Class<?> mws = XposedHelpers.findClass(
                    "com.android.server.wm.OplusMirageWindowManagerService", cl);
            XposedBridge.hookAllMethods(mws, "startActivityToMirageDisplay", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (sHangUpTaskId.get() < 0) return;
                        if (param.args == null || param.args.length < 2) return;

                        // 幻影拉起前再续一次短时保护，避免竞态晚到
                        armKeepFloatWindow();

                        final Parcelable parcelable = param.args[0] instanceof Parcelable
                                ? (Parcelable) param.args[0] : null;
                        final int displayId = ((Number) param.args[1]).intValue();
                        Bundle startOptions = null;
                        Object last = param.args[param.args.length - 1];
                        if (last instanceof Bundle) startOptions = (Bundle) last;

                        Object atms = XposedHelpers.getObjectField(param.thisObject, "mAtms");
                        final Context context =
                                (Context) XposedHelpers.getObjectField(atms, "mContext");
                        final ActivityOptions options;
                        if (startOptions != null) {
                            options = (ActivityOptions) XposedHelpers.callStaticMethod(
                                    ActivityOptions.class, "fromBundle", startOptions);
                        } else {
                            options = ActivityOptions.makeBasic();
                        }
                        options.setLaunchDisplayId(displayId);

                        final Object service = param.thisObject;
                        sMain.post(new Runnable() {
                            @Override
                            public void run() {
                                launchOnMirageDisplay(cl, service, context, parcelable,
                                        options, displayId);
                            }
                        });
                        param.setResult(null);
                        XposedBridge.log(TAG + " startActivityToMirageDisplay redirected"
                                + " displayId=" + displayId);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " startActivityToMirageDisplay error: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + " hooked startActivityToMirageDisplay (MultiApp path)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookStartActivityToMirageDisplay fail: " + t);
        }
    }

    private static void launchOnMirageDisplay(ClassLoader cl, Object service, Context context,
                                              Parcelable parcelable, ActivityOptions options,
                                              int displayId) {
        try {
            Intent intent = null;
            PendingIntent pending = null;
            if (parcelable instanceof Intent) {
                intent = (Intent) parcelable;
            } else if (parcelable instanceof PendingIntent) {
                pending = (PendingIntent) parcelable;
                try {
                    intent = (Intent) XposedHelpers.callMethod(pending, "getIntent");
                } catch (Throwable ignored) {
                }
            }
            if (intent == null) {
                notifyCastSuccess(cl, displayId);
                return;
            }
            if (CAR_DUMMY.equals(intent.getAction())) {
                try {
                    XposedHelpers.setIntField(service, "mRealCarDisplayId", displayId);
                } catch (Throwable ignored) {
                }
                notifyCastSuccess(cl, displayId);
                return;
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pending == null) {
                int uid = intent.getIntExtra("TASKINFO_UID", -1);
                if (uid > 0 && String.valueOf(uid).startsWith("999")) {
                    UserHandle uh = UserHandle.getUserHandleForUid(uid);
                    XposedHelpers.callMethod(context, "startActivityAsUser",
                            intent, options.toBundle(), uh);
                } else {
                    context.startActivity(intent, options.toBundle());
                }
            } else {
                pending.send(null, 0, null, null, null, null, options.toBundle());
            }
            notifyCastSuccess(cl, displayId);
            XposedBridge.log(TAG + " launched on mirage display=" + displayId
                    + " pkg=" + pkgOf(intent));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " launchOnMirageDisplay error: " + t);
            try {
                notifyCastSuccess(cl, displayId);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void notifyCastSuccess(ClassLoader cl, int displayId) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    "com.android.server.display.OplusMirageDisplayManagerService", cl);
            Object ins = XposedHelpers.callStaticMethod(c, "getInstance");
            if (ins != null) {
                XposedHelpers.callMethod(ins, "notifyCastSuccess", displayId);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " notifyCastSuccess fail: " + t);
        }
    }

    /** HangUpUtil / LuckyTool startMirageWindow。 */
    private static int startMirageWindow(ClassLoader cl, Intent intent) throws Exception {
        Class<?> optionsClass = XposedHelpers.findClass(
                "com.oplus.miragewindow.OplusMirageOptions", cl);
        Object options = XposedHelpers.callStaticMethod(
                optionsClass, "makeBackgroundStreamModeOptions");
        Bundle bundle = (Bundle) XposedHelpers.callMethod(options, "toBundle");

        Class<?> managerClass = XposedHelpers.findClass(
                "com.oplus.miragewindow.OplusMirageWindowManager", cl);
        Object manager = XposedHelpers.callStaticMethod(managerClass, "getInstance");

        Method target = null;
        for (Method m : managerClass.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if ("startMirageWindowMode".equals(m.getName())
                    && p.length == 2
                    && Intent.class.isAssignableFrom(p[0])
                    && Bundle.class.isAssignableFrom(p[1])) {
                target = m;
                break;
            }
        }
        if (target == null) {
            throw new NoSuchMethodException("startMirageWindowMode(Intent, Bundle)");
        }
        target.setAccessible(true);
        Object result = target.invoke(manager, intent, bundle);
        return result instanceof Number ? ((Number) result).intValue() : 0;
    }

    /** FlymeFreeform 官方保活开关，尽量在挂机前打开。 */
    private static void setTaskLively(Object task, boolean lively) {
        if (task == null) return;
        try {
            Object wrapper = task;
            try {
                wrapper = XposedHelpers.callMethod(task, "getWrapper");
            } catch (Throwable ignored) {
            }
            Object ext = wrapper;
            try {
                ext = XposedHelpers.callMethod(wrapper, "getExtImpl");
            } catch (Throwable ignored) {
            }
            String[] names = {
                    "setLively", "setMaintainTaskState", "setSceenOffPlay",
                    "setSkipMoveTaskToBack", "setIgnoreHiddenFlag",
                    "setInsensibleFlag", "dontPauseAfterQActivityIfNeed"
            };
            for (String name : names) {
                try {
                    XposedHelpers.callMethod(ext, name, lively);
                    XposedBridge.log(TAG + " " + name + "=" + lively);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " setTaskLively fail: " + t);
        }
    }

    private static Object findTask(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a != null && "com.android.server.wm.Task".equals(a.getClass().getName())) {
                return a;
            }
        }
        return null;
    }

    private static int taskIdFromFloatArgs(Object[] args) {
        if (args == null) return -1;
        for (Object a : args) {
            if (a == null) continue;
            if (a.getClass().getName().contains("FloatHandleInfo")) {
                try {
                    return XposedHelpers.getIntField(a, "taskId");
                } catch (Throwable ignored) {
                }
            }
            if ("com.android.server.wm.Task".equals(a.getClass().getName())) {
                return taskIdOf(a);
            }
        }
        return -1;
    }

    private static Intent intentFromTask(Object task) {
        try {
            Object taskInfo = XposedHelpers.callMethod(task, "getTaskInfo");
            Intent intent = (Intent) XposedHelpers.getObjectField(taskInfo, "baseIntent");
            return intent != null ? new Intent(intent) : null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " intentFromTask fail: " + t);
            return null;
        }
    }

    private static int userIdFromArgsOrTask(Object[] args, Object task) {
        if (args != null) {
            for (Object a : args) {
                if (a == null || "com.android.server.wm.Task".equals(a.getClass().getName())) {
                    continue;
                }
                try {
                    return XposedHelpers.getIntField(a, "userId");
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            return XposedHelpers.getIntField(task, "mUserId");
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int taskIdOf(Object task) {
        if (task == null) return -1;
        try {
            return XposedHelpers.getIntField(task, "mTaskId");
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String pkgOf(Intent intent) {
        if (intent == null) return "null";
        if (intent.getComponent() != null) return intent.getComponent().flattenToShortString();
        return String.valueOf(intent.getPackage());
    }
}
