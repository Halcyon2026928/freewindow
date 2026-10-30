package io.github.halcyon2026928.freewindow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

import java.io.FileWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * ColorOS17：不再依赖 addFloatHandle(FloatHandleInfo,int) 精确签名。
 * 拦截系统 takeTaskSnapshot 结果，并从 Task.getSurfaceControl 补抓。
 */
public final class LivelyHook {

    private static final String TAG = "freewindow-lively";
    private static final String DUMP = "/data/system/fw_lively.txt";
    private static final ConcurrentHashMap<Integer, Bitmap> sSnapshots = new ConcurrentHashMap<>();
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static ClassLoader sClassLoader;

    private LivelyHook() {
    }

    /** 给坞窗取一张静态截图：用抓到的任务快照缓存。静态卡片只需要这张图。 */
    public static android.graphics.Bitmap snapshotOf(int taskId) {
        try {
            android.graphics.Bitmap cached = sSnapshots.get(taskId);
            if (cached != null && !cached.isRecycled()) return cached;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void log(String msg) {
        Log.e(TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
        try {
            FileWriter w = new FileWriter(DUMP, true);
            w.write(System.currentTimeMillis() + " " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    public static void install(ClassLoader cl) {
        sClassLoader = cl;
        log("install begin");
        hookByName(cl, "com.android.server.wm.FloatHandleController", "addFloatHandle", true);
        hookByName(cl, "com.android.server.wm.floathandle.FloatHandleViewManager", "addFloatHandleView", true);
        hookSnapshotMethods(cl, "com.android.server.wm.ActivityTaskManagerService");
        hookSnapshotMethods(cl, "com.android.server.wm.TaskSnapshotController");
        hookMethodsContaining(cl, "com.android.server.wm.TaskSnapshotCache", "putSnapshot");
        hookMethodsContaining(cl, "com.android.server.wm.WindowManagerService", "captureTaskSnapshot");
        dumpClass(cl, "com.android.server.wm.TaskSnapshotController");
        dumpClass(cl, "com.android.server.wm.WindowManagerService");
        dumpClass(cl, "com.android.server.wm.FloatHandleController");
        dumpClass(cl, "com.android.server.wm.floathandle.FloatHandleInfo");
        dumpClass(cl, "com.android.server.wm.ActivityTaskManagerService");
        log("install done");
    }

    private static void hookByName(ClassLoader cl, String className, String methodName, boolean afterAdd) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            int n = 0;
            XC_MethodHook hook = afterAdd ? afterAddHook() : null;
            for (Method m : c.getDeclaredMethods()) {
                if (!methodName.equals(m.getName())) continue;
                XposedBridge.hookMethod(m, hook);
                n++;
                log("hooked " + className + "." + m.getName() + sig(m));
            }
            log("hookByName " + className + "." + methodName + " count=" + n);
        } catch (Throwable t) {
            log("hookByName fail " + className + "." + methodName + " " + t);
        }
    }

    private static XC_MethodHook afterAddHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object info = findInfo(param.args);
                int taskId = readTaskId(info);
                if (info == null && param.args != null && param.args.length > 0
                        && param.args[0] instanceof Integer) {
                    taskId = (Integer) param.args[0];
                }
                log("afterAdd " + param.method.getName() + " taskId=" + taskId
                        + " info=" + (info == null ? "null" : info.getClass().getName()));
                if (taskId < 0) return;
                Bitmap bmp = readInfoSnapshot(info);
                if (bmp == null) bmp = captureTaskBitmap(info, taskId, sClassLoader);
                if (bmp != null) {
                    sSnapshots.put(taskId, bmp);
                    log("cached afterAdd taskId=" + taskId + " " + bmp.getWidth() + "x" + bmp.getHeight());
                } else {
                    log("afterAdd capture null taskId=" + taskId);
                }
            }
        };
    }

    private static void hookSnapshotMethods(ClassLoader cl, String className) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            int n = 0;
            XC_MethodHook after = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result == null || param.args == null || param.args.length == 0) return;
                    int taskId = -1;
                    if (param.args[0] instanceof Integer) {
                        taskId = (Integer) param.args[0];
                    }
                    if (taskId < 0) return;
                    Bitmap bmp = bitmapFromTaskSnapshot(result);
                    if (bmp != null) {
                        sSnapshots.put(taskId, bmp);
                        log("cached " + param.method.getName() + " taskId=" + taskId
                                + " " + bmp.getWidth() + "x" + bmp.getHeight());
                    }
                }
            };
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if (!name.toLowerCase().contains("snapshot")) continue;
                XposedBridge.hookMethod(m, after);
                n++;
                log("hooked snapshot " + className + "." + name + sig(m));
            }
            log("hookSnapshot " + className + " count=" + n);
        } catch (Throwable t) {
            log("hookSnapshot fail " + className + " " + t);
        }
    }

    private static void hookMethodsContaining(ClassLoader cl, String className, String methodName) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            int n = 0;
            XC_MethodHook hook = cacheSnapshotHook();
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                XposedBridge.hookMethod(m, hook);
                n++;
                log("hooked " + className + "." + m.getName() + sig(m));
            }
            log("hookMethods " + className + "." + methodName + " count=" + n);
        } catch (Throwable t) {
            log("hookMethods fail " + className + "." + methodName + " " + t);
        }
    }

    private static XC_MethodHook cacheSnapshotHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    log("sys " + param.thisObject.getClass().getSimpleName() + "."
                            + param.method.getName() + " " + describeArgs(param.args)
                            + " result=" + (param.getResult() == null ? "null"
                            : param.getResult().getClass().getSimpleName()));
                    int taskId = extractTaskId(param.args);
                    Object snap = findSnapshotObj(param.args, param.getResult());
                    Bitmap bmp = bitmapFromTaskSnapshot(snap);
                    if (bmp != null && taskId >= 0) {
                        sSnapshots.put(taskId, bmp);
                        log("cached sys " + param.method.getName() + " taskId=" + taskId
                                + " " + bmp.getWidth() + "x" + bmp.getHeight());
                    }
                } catch (Throwable t) {
                    log("cacheSnapshotHook " + t);
                }
            }
        };
    }

    private static String describeArgs(Object[] args) {
        if (args == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(",");
            Object a = args[i];
            sb.append(a == null ? "null" : a.getClass().getSimpleName());
        }
        return sb.append("]").toString();
    }

    private static int extractTaskId(Object[] args) {
        if (args == null) return -1;
        for (Object a : args) {
            if (a instanceof Integer) return (Integer) a;
            if (a == null) continue;
            try {
                return XposedHelpers.getIntField(a, "mTaskId");
            } catch (Throwable ignored) {
            }
            try {
                Object v = XposedHelpers.callMethod(a, "getTaskId");
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static Object findSnapshotObj(Object[] args, Object result) {
        if (isSnapshot(result)) return result;
        if (args == null) return null;
        for (Object a : args) {
            if (isSnapshot(a)) return a;
        }
        return null;
    }

    private static boolean isSnapshot(Object o) {
        if (o == null) return false;
        String n = o.getClass().getName();
        return n.contains("TaskSnapshot") || n.contains("ScreenshotHardwareBuffer")
                || n.contains("HardwareBuffer");
    }

    private static void dumpClass(ClassLoader cl, String className) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            log("dump " + className);
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("float") || n.contains("snap") || n.contains("surface")
                        || n.contains("task")) {
                    log("  M " + m.getName() + sig(m));
                }
            }
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                String n = f.getName().toLowerCase();
                if (n.contains("snap") || n.contains("icon") || n.contains("atms")) {
                    log("  F " + f.getName() + " " + f.getType().getSimpleName());
                }
            }
        } catch (Throwable t) {
            log("dump fail " + className + " " + t);
        }
    }

    public static ImageView createPreview(Context ctx, Object floatHandleInfo, ClassLoader cl) {
        final ImageView preview = new ImageView(ctx);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float radius = dp(ctx, 12);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFD0D0D0);
        bg.setCornerRadius(radius);
        preview.setBackground(bg);
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int w = view.getWidth();
                int h = view.getHeight();
                if (w > 0 && h > 0) {
                    outline.setRoundRect(0, 0, w, h, radius);
                }
            }
        });
        preview.setClipToOutline(true);
        preview.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                v.invalidateOutline();
                Object tag = v.getTag();
                if (tag instanceof Bitmap && right - left > 0 && bottom - top > 0) {
                    applyRoundedBitmap(preview, (Bitmap) tag, radius);
                    v.setTag(null);
                }
            }
        });

        final ClassLoader loader = cl != null ? cl : sClassLoader;
        final int taskId = readTaskId(floatHandleInfo);
        log("createPreview taskId=" + taskId + " cache=" + sSnapshots.containsKey(taskId)
                + " info=" + (floatHandleInfo == null ? "null" : floatHandleInfo.getClass().getName()));
        dumpInfo(floatHandleInfo);

        Bitmap ready = taskId >= 0 ? sSnapshots.get(taskId) : null;
        if (ready == null) {
            ready = readInfoSnapshot(floatHandleInfo);
            if (ready != null) {
                ready = softwareCopy(ready);
                if (ready != null && taskId >= 0) sSnapshots.put(taskId, ready);
            }
        }
        if (ready != null && !ready.isRecycled()) {
            applyRoundedBitmap(preview, ready, radius);
            log("preview from cache taskId=" + taskId);
            return preview;
        }

        final Object info = floatHandleInfo;
        Runnable fill = new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                Bitmap bmp = captureTaskBitmap(info, taskId, loader);
                if (bmp == null) {
                    log("capture null taskId=" + taskId + " try=" + tries);
                    if (tries++ < 3) {
                        sMain.postDelayed(this, 400);
                    }
                    return;
                }
                sSnapshots.put(taskId, bmp);
                applyRoundedBitmap(preview, bmp, radius);
                log("preview captured taskId=" + taskId + " " + bmp.getWidth() + "x" + bmp.getHeight());
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            fill.run();
        } else {
            sMain.post(fill);
        }
        return preview;
    }

    private static void applyRoundedBitmap(ImageView preview, Bitmap src, float radius) {
        if (src == null || src.isRecycled()) return;
        int w = preview.getWidth();
        int h = preview.getHeight();
        if (w <= 0 || h <= 0) {
            preview.setTag(src);
            preview.setImageBitmap(src);
            return;
        }
        preview.setImageBitmap(roundCrop(src, w, h, radius));
    }

    private static Bitmap roundCrop(Bitmap src, int w, int h, float radius) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float dx = (w - src.getWidth() * scale) * 0.5f;
        float dy = (h - src.getHeight() * scale) * 0.5f;
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);
        canvas.drawRoundRect(0, 0, w, h, radius, radius, paint);
        return out;
    }

    private static void dumpInfo(Object info) {
        if (info == null) return;
        try {
            StringBuilder sb = new StringBuilder("info fields:");
            Class<?> c = info.getClass();
            while (c != null && !c.equals(Object.class)) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(info);
                    String vs = v == null ? "null" : v.getClass().getSimpleName();
                    if (v instanceof Bitmap) {
                        Bitmap b = (Bitmap) v;
                        vs = "Bitmap " + b.getWidth() + "x" + b.getHeight() + " recycled=" + b.isRecycled();
                    }
                    sb.append(" ").append(f.getName()).append("=").append(vs);
                }
                c = c.getSuperclass();
            }
            log(sb.toString());
        } catch (Throwable t) {
            log("dumpInfo " + t);
        }
    }

    private static Object findInfo(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a != null && a.getClass().getName().contains("FloatHandleInfo")) return a;
        }
        return args.length > 0 ? args[0] : null;
    }

    private static Bitmap captureTaskBitmap(Object info, int taskId, ClassLoader cl) {
        Bitmap fromInfo = readInfoSnapshot(info);
        if (fromInfo != null) {
            log("capture from info.snapShot");
            return softwareCopy(fromInfo);
        }
        Object atms = getAtms(cl);
        Object task = null;
        try {
            Object rwc = XposedHelpers.getObjectField(atms, "mRootWindowContainer");
            task = invokeAny(rwc, "anyTaskForId", taskId);
            log("capture task=" + (task == null ? "null" : task.toString()));
        } catch (Throwable t) {
            log("anyTaskForId " + t);
        }
        Object wms = null;
        Object tsc = null;
        try {
            wms = XposedHelpers.getObjectField(atms, "mWindowManager");
        } catch (Throwable ignored) {
        }
        if (wms != null) {
            tsc = getField(wms, "mTaskSnapshotController");
            if (tsc == null) tsc = getField(wms, "mSnapshotController");
        }
        if (tsc == null) tsc = getField(atms, "mTaskSnapshotController");

        Object snapshot = null;
        Object lock = getField(atms, "mGlobalLock");
        if (lock != null) {
            synchronized (lock) {
                snapshot = invokeSnapshotApi(tsc, task, taskId);
                if (snapshot == null) snapshot = invokeSnapshotApi(wms, task, taskId);
                if (snapshot == null) snapshot = invokeAnyLocked(atms, "takeTaskSnapshot", taskId);
                if (snapshot == null) snapshot = invokeAnyLocked(atms, "getTaskSnapshot", taskId);
            }
        } else {
            snapshot = invokeSnapshotApi(tsc, task, taskId);
            if (snapshot == null) snapshot = invokeSnapshotApi(wms, task, taskId);
            if (snapshot == null) snapshot = invokeAny(atms, "takeTaskSnapshot", taskId);
            if (snapshot == null) snapshot = invokeAny(atms, "getTaskSnapshot", taskId);
        }
        if (snapshot instanceof Bitmap) {
            return softwareCopy((Bitmap) snapshot);
        }
        Bitmap fromSnap = bitmapFromTaskSnapshot(snapshot);
        if (fromSnap != null) {
            log("capture from TaskSnapshot " + fromSnap.getWidth() + "x" + fromSnap.getHeight());
            return fromSnap;
        }
        return captureFromSurface(taskId, cl, atms);
    }

    private static Object getField(Object obj, String name) {
        if (obj == null) return null;
        try {
            return XposedHelpers.getObjectField(obj, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeSnapshotApi(Object target, Object task, int taskId) {
        if (target == null) return null;
        String[] names = {
                "captureTaskSnapshot", "createTaskSnapshot", "snapshot", "recordSnapshot",
                "snapshotTask", "captureSnapshot", "takeSnapshot"
        };
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                boolean match = false;
                for (String n : names) {
                    if (n.equals(m.getName())) {
                        match = true;
                        break;
                    }
                }
                if (!match) continue;
                Object[] args = buildSnapshotArgs(m.getParameterTypes(), task, taskId);
                if (args == null) continue;
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(target, args);
                    log(target.getClass().getSimpleName() + "." + m.getName() + sig(m)
                            + " -> " + (r == null ? "null" : r.getClass().getSimpleName()));
                    if (isSnapshot(r)) return r;
                    if (r instanceof Bitmap) return r;
                } catch (Throwable t) {
                    log(m.getName() + sig(m) + " err " + t.getMessage());
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object[] buildSnapshotArgs(Class<?>[] types, Object task, int taskId) {
        if (types.length == 0) return null;
        Object[] args = new Object[types.length];
        boolean usedTask = false;
        boolean usedId = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (!usedTask && task != null && t.isInstance(task)) {
                args[i] = task;
                usedTask = true;
            } else if (!usedTask && task != null && t.getName().equals("com.android.server.wm.Task")) {
                args[i] = task;
                usedTask = true;
            } else if (!usedId && (t == int.class || t == Integer.class)) {
                args[i] = taskId;
                usedId = true;
            } else if (t == boolean.class || t == Boolean.class) {
                args[i] = Boolean.TRUE;
            } else if (t == float.class || t == Float.class) {
                args[i] = 1.0f;
            } else if (t == int.class || t == Integer.class) {
                args[i] = 0;
            } else if (t == Rect.class) {
                args[i] = new Rect();
            } else if (t.isArray() && t.getComponentType() == SurfaceControl.class) {
                args[i] = new SurfaceControl[0];
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private static Object getAtms(ClassLoader cl) {
        try {
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl),
                    "getInstance");
            return XposedHelpers.getObjectField(fhc, "mAtms");
        } catch (Throwable t) {
            log("getAtms " + t);
            return null;
        }
    }

    private static Object invokeAny(Object target, String name, int taskId) {
        if (target == null) return null;
        Object lock = null;
        try {
            lock = XposedHelpers.getObjectField(target, "mGlobalLock");
        } catch (Throwable ignored) {
        }
        if (lock != null) {
            synchronized (lock) {
                return invokeAnyLocked(target, name, taskId);
            }
        }
        return invokeAnyLocked(target, name, taskId);
    }

    private static Object invokeAnyLocked(Object target, String name, int taskId) {
        Class<?> c = target.getClass();
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (!name.equals(m.getName())) continue;
                Object[] args = buildArgs(m.getParameterTypes(), taskId);
                if (args == null) continue;
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(target, args);
                    log(name + sig(m) + " -> " + (r == null ? "null" : r.getClass().getSimpleName()));
                    if (r != null) return r;
                } catch (Throwable t) {
                    log(name + sig(m) + " err " + t.getMessage());
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object[] buildArgs(Class<?>[] types, int taskId) {
        if (types.length == 0) return null;
        if (types[0] != int.class && types[0] != Integer.class) return null;
        Object[] args = new Object[types.length];
        args[0] = Integer.valueOf(taskId);
        for (int i = 1; i < types.length; i++) {
            if (types[i] == boolean.class || types[i] == Boolean.class) {
                args[i] = Boolean.TRUE;
            } else if (types[i] == int.class || types[i] == Integer.class) {
                args[i] = 0;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private static Bitmap bitmapFromTaskSnapshot(Object snapshot) {
        if (snapshot == null || Build.VERSION.SDK_INT < 29) return null;
        try {
            HardwareBuffer buffer = (HardwareBuffer) XposedHelpers.callMethod(snapshot, "getHardwareBuffer");
            if (buffer == null) {
                log("snapshot hardwareBuffer null");
                return null;
            }
            ColorSpace cs = null;
            try {
                cs = (ColorSpace) XposedHelpers.callMethod(snapshot, "getColorSpace");
            } catch (Throwable ignored) {
            }
            Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, cs);
            if (hw == null) return null;
            return softwareCopy(hw);
        } catch (Throwable t) {
            log("wrapHardwareBuffer " + t);
            return null;
        }
    }

    private static Bitmap captureFromSurface(int taskId, ClassLoader cl, Object atms) {
        SurfaceControl sc = null;
        try {
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl),
                    "getInstance");
            Object got = invokeAny(fhc, "getTaskSurfaceControl", taskId);
            if (got instanceof SurfaceControl) sc = (SurfaceControl) got;
        } catch (Throwable t) {
            log("getTaskSurfaceControl " + t);
        }
        if (sc == null || !sc.isValid()) {
            sc = surfaceFromTask(atms, taskId);
        }
        if (sc == null || !sc.isValid()) {
            log("no valid surface taskId=" + taskId);
            return null;
        }
        Object buffer = captureLayers(cl, sc);
        if (buffer == null) {
            log("captureLayers null");
            return null;
        }
        try {
            HardwareBuffer hb = (HardwareBuffer) XposedHelpers.callMethod(buffer, "getHardwareBuffer");
            if (hb == null) return null;
            ColorSpace cs = null;
            try {
                cs = (ColorSpace) XposedHelpers.callMethod(buffer, "getColorSpace");
            } catch (Throwable ignored) {
            }
            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
            return softwareCopy(hw);
        } catch (Throwable t) {
            log("captureFromSurface wrap " + t);
            return null;
        }
    }

    private static SurfaceControl surfaceFromTask(Object atms, int taskId) {
        if (atms == null) return null;
        try {
            Object rwc = XposedHelpers.getObjectField(atms, "mRootWindowContainer");
            Object task = invokeAny(rwc, "anyTaskForId", taskId);
            if (task == null) {
                log("anyTaskForId null");
                return null;
            }
            Object sc = XposedHelpers.callMethod(task, "getSurfaceControl");
            log("task.getSurfaceControl " + sc);
            return sc instanceof SurfaceControl ? (SurfaceControl) sc : null;
        } catch (Throwable t) {
            log("surfaceFromTask " + t);
            return null;
        }
    }

    private static Object captureLayers(ClassLoader cl, SurfaceControl sc) {
        try {
            Class<?> screenCapture = Class.forName("android.window.ScreenCapture", false,
                    cl != null ? cl : SurfaceControl.class.getClassLoader());
            Class<?> argsCl = null;
            Class<?> builderCl = null;
            for (Class<?> inner : screenCapture.getDeclaredClasses()) {
                if ("LayerCaptureArgs".equals(inner.getSimpleName())) {
                    argsCl = inner;
                    for (Class<?> b : inner.getDeclaredClasses()) {
                        if ("Builder".equals(b.getSimpleName())) builderCl = b;
                    }
                }
            }
            if (builderCl != null) {
                Constructor<?> ctor = builderCl.getConstructor(SurfaceControl.class);
                Object builder = ctor.newInstance(sc);
                try {
                    builderCl.getMethod("setCaptureSecureLayers", boolean.class).invoke(builder, true);
                } catch (Throwable ignored) {
                }
                Object args = builderCl.getMethod("build").invoke(builder);
                Method cap = screenCapture.getMethod("captureLayers", argsCl);
                cap.setAccessible(true);
                Object r = cap.invoke(null, args);
                log("captureLayers(LayerCaptureArgs) -> "
                        + (r == null ? "null" : r.getClass().getSimpleName()));
                if (r != null) return r;
            } else {
                log("LayerCaptureArgs.Builder missing");
            }
            for (Method m : screenCapture.getDeclaredMethods()) {
                if (!"captureLayers".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                m.setAccessible(true);
                try {
                    Object r = null;
                    if (p.length == 1 && SurfaceControl.class.isAssignableFrom(p[0])) {
                        r = m.invoke(null, sc);
                    } else if (p.length == 3 && SurfaceControl.class.isAssignableFrom(p[0])
                            && p[1] == Rect.class && p[2] == float.class) {
                        r = m.invoke(null, sc, new Rect(), 1.0f);
                    }
                    log(m.getName() + sig(m) + " -> "
                            + (r == null ? "null" : r.getClass().getSimpleName()));
                    if (r != null) return r;
                } catch (Throwable t) {
                    log(m.getName() + sig(m) + " err " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            log("captureLayers " + t);
        }
        return null;
    }

    private static Bitmap readInfoSnapshot(Object info) {
        if (info == null) return null;
        String[] names = {"snapShot", "snapshot", "mSnapShot", "mSnapshot", "icon"};
        for (String n : names) {
            try {
                Object snap = XposedHelpers.getObjectField(info, n);
                if (snap instanceof Bitmap && !((Bitmap) snap).isRecycled()
                        && ((Bitmap) snap).getWidth() > 8 && ((Bitmap) snap).getHeight() > 8) {
                    return (Bitmap) snap;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Bitmap softwareCopy(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        try {
            Bitmap argb = src;
            if (src.getConfig() == Bitmap.Config.HARDWARE) {
                argb = src.copy(Bitmap.Config.ARGB_8888, false);
                if (argb == null) return null;
            }
            if (argb.getWidth() > 720) {
                int h = (int) (720f * argb.getHeight() / argb.getWidth());
                Bitmap scaled = Bitmap.createScaledBitmap(argb, 720, Math.max(h, 1), true);
                if (scaled != argb && argb != src) argb.recycle();
                return scaled;
            }
            if (argb != src) return argb;
            Bitmap copy = argb.copy(Bitmap.Config.ARGB_8888, false);
            return copy != null ? copy : argb;
        } catch (Throwable t) {
            log("softwareCopy " + t);
            return src;
        }
    }

    private static int readTaskId(Object info) {
        if (info == null) return -1;
        try {
            return XposedHelpers.getIntField(info, "taskId");
        } catch (Throwable ignored) {
        }
        try {
            Object v = XposedHelpers.callMethod(info, "getTaskId");
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String sig(Method m) {
        StringBuilder sb = new StringBuilder("(");
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(ps[i].getSimpleName());
        }
        return sb.append(")").toString();
    }

    private static int dp(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics());
    }
}
